package com.bolt.browser.util

import android.content.Context
import java.io.File

/**
 * v1.3.55 — the CHIPS partition-host scanner (the "siteWipe survivors"
 * field report: the same five youtube.com cookies — YSC,
 * VISITOR_INFO1_LIVE, VISITOR_PRIVACY_METADATA, __Secure-YNID,
 * __Secure-ROLLOUT_TOKEN — survived every expiry pass on every host
 * variant, and the signed-out recommendations they carry survived with
 * them).
 *
 * WHY THIS FILE EXISTS: YouTube's signed-out identity cookies are set with
 * the `Partitioned` (CHIPS) attribute. WebView stores each one PER
 * PARTITION, keyed by the schemeful site of the top-level page that was
 * active when it was set — so ONE logical cookie is MANY on-disk rows,
 * one per host ever visited top-level (youtube.com, www., m., music.,
 * accounts., studio., …). The per-site wipe's expiry now sends the
 * `Partitioned` twin of every expiry string (BrowserActivity.expireCookies,
 * v1.3.55) and WebView derives the delete's partition key from the URL it
 * is set through — but the static host list (d/www/m/music/accounts) plus
 * the open tabs' hosts is an APPROXIMATION of every partition a site may
 * have written. This scanner closes the gap: it READS the cookie jars on
 * disk and returns every host that actually holds a first-party partitioned
 * copy of the family's cookies, so the expiry can enumerate them all.
 *
 * SAFETY: strictly READ-ONLY — one SELECT over the `cookies` table of every
 * WebView profile root's `Cookies` SQLite file, never a write, never a
 * delete (all mutation stays behind CookieManager.setCookie). Failures —
 * mid-migration schema, missing file — return an empty list; the caller
 * degrades to the static host approximation. Runs on the caller's thread:
 * start it on a background thread and join with a timeout (the wipe flow
 * gives it the 600 ms in-page window as a head start).
 *
 * v1.4.11 — THE LOCK-CONTENTION RETRY. Opening the Cookies file straight
 * from disk while Chromium's cookie backend is mid-write throws
 * SQLiteDatabaseLockedException ("database is locked") — the old catch
 * swallowed it as a generic best-effort failure, so the scan silently
 * returned nothing exactly when the user was ACTIVELY BROWSING (the one
 * moment the disk jar is busiest). A lock is TRANSIENT, not fatal: each
 * profile is now retried with a bounded backoff (60/120/240 ms — the whole
 * retry budget stays inside the wipe flow's head-start window) before
 * degrading. Every other failure stays one-shot best-effort as before.
 *
 * WHAT IT DELIBERATELY IGNORES: rows with has_cross_site_ancestor=1
 * (third-party partitions — the cookie was set from a cross-site embed;
 * CookieManager.setCookie can only address kSameSite keys, and third-party
 * identity is explicitly outside the per-site wipe's contract since
 * v1.3.54) and partitions whose top-level host is not itself in the wipe
 * family (same line: embedded state of OTHER sites is not this site's
 * "remember me").
 *
 * Kotlin per the README language rule (new files, v1.3.38).
 */
object CookiePartitionScan {

    /**
     * Every host holding a FIRST-PARTY partitioned (CHIPS) copy of any
     * cookie whose base domain is in [family]. [family] is the wipe's
     * related-domains set (e.g. [youtube.com, google.com,
     * accounts.google.com] for a youtube.com wipe). Both the cookie's own
     * domain AND the partition's top-level host must be inside the family.
     */
    @JvmStatic
    fun scanFirstPartyPartitionHosts(app: Context, family: Set<String>): List<String> {
        val out = HashSet<String>()
        if (family.isEmpty()) return ArrayList(out)
        val dataDir = try { app.applicationInfo.dataDir } catch (t: Throwable) { null }
                ?: return ArrayList(out)
        val roots = File(dataDir).listFiles() ?: return ArrayList(out)
        for (root in roots) {
            val rn = root.name
            if (!root.isDirectory) continue
            if (!(rn == "app_webview" || rn.startsWith("app_webview_"))) continue
            // both on-disk layouts: modern <root>/Default/Cookies and the
            // legacy flat <root>/Cookies — a missing one is skipped for free
            for (profile in listOf(File(root, "Default"), root)) {
                val db = File(profile, "Cookies")
                if (!db.isFile) continue
                // v1.4.11 — the lock-contention retry: a busy jar is
                // TRANSIENT; anything else stays best-effort one-shot
                var attempt = 0
                while (true) {
                    try {
                        scanDb(db, family, out)
                        break
                    } catch (e: android.database.sqlite.SQLiteException) {
                        if (!isLockError(e) || attempt >= LOCK_BACKOFF_MS.size) break
                        try {
                            Thread.sleep(LOCK_BACKOFF_MS[attempt].toLong())
                        } catch (_: InterruptedException) {
                            return ArrayList(out)   // wipe flow moving on without us
                        }
                        attempt++
                    } catch (ignored: Throwable) {
                        // mid-migration schema, unreadable WAL — best effort
                        // only, never fatal
                        break
                    }
                }
            }
        }
        return ArrayList(out)
    }

    /** The retry backoff for a locked jar (60/120/240 ms — bounded inside the
     *  wipe flow's 600 ms head-start window; see the class header). */
    private val LOCK_BACKOFF_MS = intArrayOf(60, 120, 240)

    /** A busy jar: SQLiteDatabaseLockedException, or the SQLiteException
     *  spellings some WebView builds throw for SQLITE_BUSY/SQLITE_LOCKED
     *  ("database is locked" / "database table is locked"). */
    private fun isLockError(t: Throwable): Boolean =
            t is android.database.sqlite.SQLiteDatabaseLockedException ||
                    (t.message?.contains("locked", ignoreCase = true) ?: false)

    /** ONE read-only SELECT over a Cookies jar (open → query → close). Throws
     *  through whatever SQLite said, so the caller's retry loop can see locks. */
    private fun scanDb(db: File, family: Set<String>, out: HashSet<String>) {
        val sql = android.database.sqlite.SQLiteDatabase.openDatabase(
                db.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try {
            // host_key carries a leading dot for Domain= cookies
            // (".youtube.com"); top_frame_site_key is a serialized
            // SchemefulSite ("https://www.youtube.com"). Empty
            // top_frame_site_key = a plain non-partitioned cookie.
            // has_cross_site_ancestor=0 keeps first-party (kSameSite)
            // partitions only — the ones setCookie can address.
            sql.rawQuery(
                    "SELECT DISTINCT host_key, top_frame_site_key FROM cookies " +
                    "WHERE top_frame_site_key != '' AND has_cross_site_ancestor = 0",
                    null).use { c ->
                while (c.moveToNext()) {
                    val hostKey = c.getString(0) ?: continue
                    val tfsk = c.getString(1) ?: continue
                    val partHost = hostOfSite(tfsk) ?: continue
                    if (!partHost.contains(".")) continue
                    val cookieBase = Fx.baseDomain(Fx.hostOf(hostKey.trimStart('.')))
                    val partBase = Fx.baseDomain(Fx.hostOf(partHost))
                    if (cookieBase.isEmpty() || !family.contains(cookieBase)) continue
                    if (partBase.isEmpty() || !family.contains(partBase)) continue
                    out.add(partHost)
                }
            }
        } finally {
            try { sql.close() } catch (ignored: Throwable) {}
        }
    }

    /** "https://www.youtube.com[/:port/…]" (a serialized SchemefulSite)
     *  -> "www.youtube.com"; null when it is not an http(s) site. */
    private fun hostOfSite(site: String): String? {
        val m = Regex("^https?://([^/:]+)").find(site) ?: return null
        return m.groupValues[1].lowercase()
    }
}
