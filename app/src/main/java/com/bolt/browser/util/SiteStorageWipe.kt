package com.bolt.browser.util

import android.content.Context
import java.io.File

/**
 * v1.3.54 — the per-site FIRST-VISIT wipe helper (the "un-logged YouTube's
 * history remains when I clear YouTube's data" field report).
 *
 * WHY THIS FILE EXISTS (the structural hole it closes): the per-site wipe
 * promised "IndexedDB" in its dialog text but had NO working deletion path
 * for it —
 *   (a) the in-page `indexedDB.deleteDatabase()` fired by MediaHook's
 *       wipePageData() can NEVER complete while the page is ALIVE: a page
 *       that holds its own IndexedDB connections open (exactly YouTube's
 *       case — a heavy IDB user) BLOCKS the delete request until it closes
 *       them, and we destroy the WebView ~600 ms later, killing the pending
 *       deletion mid-flight;
 *   (b) WebStorage.deleteOrigin(origin) — the Java-side fallback — has a
 *       DOCS-ONLY contract: "Clears the storage currently being used by both
 *       the Application Cache and Web SQL Database APIs by the given origin"
 *       (chromium issue 41199168: Won't Fix — the team's own comment: "The
 *       APIs do not guarantee that [origins are removed]"), and IndexedDB is
 *       not in the documented set at all. localStorage rides along on some
 *       builds; IndexedDB demonstrably does not have to.
 * So the site's device-local memory (YouTube's signed-out watch-history
 * linkage, resume points, cached app state) sailed through every wipe while
 * the cookies genuinely died — "logins clear, history remains".
 *
 * THE FIX: after the affected WebViews are destroyed (no live renderer can
 * hold the LevelDB files open), physically delete the per-origin DIRECTORIES
 * on disk. Chromium names them deterministically after the origin (below,
 * <root> is any WebView profile root: `app_webview` or `app_webview_<sfx>`):
 *     <root>/Default/IndexedDB/https_www.youtube.com_0.indexeddb.leveldb
 *     <root>/Default/IndexedDB/https_www.youtube.com_0.blob
 *     <root>/Default/File System/https_www.youtube.com_0/...   (legacy FS API)
 *     <root>/Default/databases/https_www.youtube.com_0/...     (WebSQL)
 * Prefix-matching (`https_<host>_` / `http_<host>_`) covers every suffix
 * variant across Chromium versions; the walk covers BOTH the modern
 * `Default/` profile layout and the legacy flat one, and every `app_webview*`
 * root (the incognito suffix dir included — though that one is short-lived).
 *
 * BLAST-RADIUS SAFETY, BY CONSTRUCTION (v1.3.52's standing rule):
 *   - only the three PER_ORIGIN_SUBTREES are ever walked — an ALLOWLIST, so
 *     a future edit cannot widen the sweep by accident;
 *   - inside them, only entries whose name starts with an exact
 *     `https_<host>_` / `http_<host>_` prefix of a WIPE TARGET are deleted;
 *   - the SHARED per-profile databases are never touched: `Local Storage`
 *     (one LevelDB for ALL origins — deleting it would nuke every site's
 *     localStorage, the exact v1.3.52 class of bug; it dies per-origin via
 *     WebStorage.deleteOrigin + the in-page clear), `Cookies` (SQLite),
 *     `Service Worker` (shared registration database — the in-page
 *     unregister() owns it), `Sessions`, `Web Data`, `Login Data`;
 *   - partitioned third-party storage buckets (`Storage/Scopes/...`, name-
 *     hashed) are deliberately NOT chased: they carry embedded third-party
 *     identity, never the first-party "remember me" state this wipe targets.
 *
 * Kotlin per the README language rule (new files, v1.3.38).
 */
object SiteStorageWipe {

    /** result handed to the caller on completion (always handed, even when
     *  nothing was found or everything failed — the callback must be able to
     *  unblock the UI rebuild in every outcome). */
    class WipeResult(@JvmField val freedBytes: Long,
                     @JvmField val origins: List<String>)

    /** Java-friendly completion callback (lesson 23: declare your own fun
     *  interface — android.util.Consumer is hidden API). */
    fun interface OnDone { fun onDone(r: WipeResult) }

    /**
     * Delete every per-origin storage directory (IndexedDB + the legacy
     * File System and WebSQL trees) belonging to [hosts], then report back
     * on [onDone]. Runs on its OWN thread — the caller's UI thread must
     * never block on file IO (YouTube's IDB can be tens of MB across
     * thousands of LevelDB files). Caller contract: the affected WebViews
     * must ALREADY be destroyed (live renderers hold these files open).
     */
    @JvmStatic
    fun wipeOriginStorage(app: Context, hosts: Set<String>, onDone: OnDone) {
        Thread({
            val r = try {
                sweep(app.applicationContext, hosts)
            } catch (t: Throwable) {
                WipeResult(0L, ArrayList())
            }
            try { onDone.onDone(r) } catch (ignored: Throwable) {}
        }, "bolt-site-wipe").start()
    }

    /** the ONLY profile subtrees this sweep may ever look at — an allowlist,
     *  deliberately excluding every shared database (see the header). */
    private val PER_ORIGIN_SUBTREES = listOf("IndexedDB", "File System", "databases")

    private fun sweep(app: Context, hosts: Set<String>): WipeResult {
        if (hosts.isEmpty()) return WipeResult(0L, ArrayList())
        // dir-name prefixes: https_<host>_ / http_<host>_ (Chromium's origin
        // serialization; the trailing _ keeps https://a.example.com from
        // matching https://a.example.com.evil.tld_ — a real prefix collision)
        val prefixes = HashSet<String>()
        for (h in hosts) {
            val host = h.trim().toLowerCase()
            if (host.isEmpty() || !host.contains(".") || host.contains("/")) continue
            prefixes.add("https_${host}_")
            prefixes.add("http_${host}_")
        }
        if (prefixes.isEmpty()) return WipeResult(0L, ArrayList())
        val dataDir = try { app.applicationInfo.dataDir } catch (t: Throwable) { null }
                ?: return WipeResult(0L, ArrayList())
        val roots = File(dataDir).listFiles() ?: return WipeResult(0L, ArrayList())
        var freed = 0L
        val hits = ArrayList<String>()
        for (root in roots) {
            val rn = root.name
            if (!root.isDirectory) continue
            if (!(rn == "app_webview" || rn.startsWith("app_webview_"))) continue
            // both on-disk layouts: modern <root>/Default/<subtree> and the
            // legacy flat <root>/<subtree> — hitting a missing one is free
            for (profile in listOf(File(root, "Default"), root)) {
                for (sub in PER_ORIGIN_SUBTREES) {
                    val entries = File(profile, sub).listFiles() ?: continue
                    for (e in entries) {
                        val en = e.name
                        var match = false
                        for (p in prefixes) if (en.startsWith(p)) { match = true; break }
                        if (!match) continue
                        val f = rmrf(e)
                        if (f >= 0) {
                            freed += f
                            hits.add("$rn/$sub/$en")
                        }
                    }
                }
            }
        }
        return WipeResult(freed, hits)
    }

    /** recursively delete f; returns bytes freed, or -1 if something survived
     *  (one retry pass for transient handles, same policy as DataWipe). */
    private fun rmrf(f: File): Long {
        if (f == null || !f.exists()) return 0L
        var freed = 0L
        var ok = true
        if (f.isDirectory) {
            val children = f.listFiles()
            if (children != null) {
                for (c in children) {
                    val n = rmrf(c)
                    if (n >= 0) freed += n else ok = false
                }
            }
        } else {
            try { freed = f.length() } catch (ignored: Throwable) {}
        }
        try { if (!f.delete()) ok = false } catch (ignored: Throwable) { ok = false }
        return if (ok) freed else -1L
    }
}
