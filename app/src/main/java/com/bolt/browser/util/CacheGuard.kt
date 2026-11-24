package com.bolt.browser.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import java.io.File

/**
 * v1.3.76 — CACHE GUARD (new code → Kotlin; the standing language rule).
 *
 * THE FIELD REPORT: "watching videos on facebook and youtube starts
 * accumulating storage in the browser data, maybe the cache based storage
 * for temporary data architecture is not holding on effectively."
 *
 * THE DIAGNOSIS (why "temporary" storage stopped being temporary):
 * WebView's disk caches have NO eviction pressure a light browser ever
 * exerts. Four subtrees grow without bound while video sites are used:
 *  - `Cache/` — the HTTP disk cache: Facebook's CDN video segments,
 *    thumbnails and player payloads are range-request responses with
 *    generous freshness, and they pile up per video watched;
 *  - `Code Cache/` — compiled JS: YouTube's player alone is megabytes of
 *    bytecode, recompiled and re-stored on every site update;
 *  - `Service Worker/CacheStorage/` — Facebook's service worker caches
 *    script and media subresources per origin, and nothing ever trims it
 *    (this is the biggest silent grower on FB);
 *  - `GPUCache/` + `Service Worker/ScriptCache` — smaller, same disease.
 * Chromium's own HTTP cache eviction only triggers at OS-reported disk
 * pressure — a 3 GB daily-driver phone hits "browser data is huge" in
 * Android settings LONG before the OS cares. So the browser itself has
 * to hold the line: MEASURE, and trim at budgets.
 *
 * v1.3.77 — THE USER'S STORAGE LAW, TAKEN AT ITS WORD. The v1.3.76
 * budgets (150/300 MB) were "a light browser's ceiling for a 3 GB
 * device" — but the field came back with real numbers: "I want the app
 * storage to remain around and within 50mb. Ideally less than 40 while
 * using and around 20 after closing it, but is now sitting at 60mb."
 * At 60 MB total the guard had NEVER fired — a budget that never fires
 * is a decoration. The new policy is sized from the arithmetic of the
 * user's own numbers: app code + site stores + OCR models cost roughly
 * 15–35 MB, so the cache subtrees must live inside ~24 MB for the total
 * to stay under 40 MB WHILE USING, and must be ~0 after closing for the
 * total to land near 20 MB. Three windows now enforce that:
 *  - STARTUP SWEEP (cold start, no renderer alive): over SOFT (24 MB)
 *    → physically empty every cache subtree (unchanged mechanics, the
 *    threshold moved from 150 to where it actually bites).
 *  - MID-SESSION AUDIT (onResume + a 3-minute foreground loop, one pass
 *    per 2 minutes): over SOFT → `WebView.clearCache(true)` posted to
 *    main (the HTTP cache, safe with live renderers) AND the Service
 *    Worker / GPUCache / Media Cache subtrees swept physically (a live
 *    service worker answers a cache miss from the network — pages keep
 *    working; shaders and media re-buffer). Over HARD (40 MB) the Code
 *    Cache joins (JS recompiles on the next load — slower loads are the
 *    price of the ceiling, and only a heavy session ever pays it).
 *  - EXIT SWEEP ([exit], new): physically empty EVERY cache subtree plus
 *    the app's own cacheDir, unconditionally — called from
 *    BrowserActivity.onDestroy (the user closed the browser) and from a
 *    5-minute-delayed onStop runnable (the swipe-away case where the
 *    process outlives the activity and no onDestroy ever runs; cancelled
 *    by onResume). "Around 20 MB after closing" is a promise about disk
 *    state AFTER death, so the sweep must run before death whenever the
 *    process gets the chance — and the next cold start's startup sweep
 *    finishes anything a racing renderer still held open.
 *
 * Honesty rules: never a file outside the allowlisted cache subtrees and
 * the app's own cacheDir (same discipline as SiteStorageWipe — cookies,
 * localStorage, IndexedDB and every other per-site store are NOT cache
 * and never touched; the OCR models under files/ocrfast are NOT cache —
 * they are user-downloaded data with their own metered confirm, and the
 * app size + those models are the reason "20 MB after closing" reads as
 * "around", not "at most"); every trim lands a diag line (`cache guard …`)
 * so field reports can see it working; the freed number is what the
 * sweep itself deleted.
 */
object CacheGuard {

    /** over SOFT the next check trims. v1.3.77: 150 MB -> 24 MB — the
     *  user's law is "under 50 MB total, ideally under 40 while using"
     *  and app code + site stores + OCR models already cost 15–35 MB of
     *  that; a budget that never fires (60 MB total vs a 150 MB budget)
     *  is a decoration, not a guard. */
    private const val SOFT = 24L * 1024 * 1024

    /** over HARD a mid-session check also sweeps the Code Cache (the
     *  JS-compile grower — only a heavy session crosses 40 MB of cache
     *  subtrees, and recompiling on the next load is the honest price) */
    private const val HARD = 40L * 1024 * 1024

    /** mid-session audits are rate-limited (v1.3.77: one per 2 minutes —
     *  the foreground loop asks every 3, so a just-fired trim is not
     *  re-measured while its own clearCache is still in flight) */
    private const val MIN_INTERVAL_MS = 2 * 60 * 1000L

    /** v1.3.79 — THE SITE-DATA DIET. The v1.3.78 close-out emptied every
     *  CACHE subtree and the field still read "46 mb in app data": the
     *  grower is Local Storage + IndexedDB + service-worker registrations
     *  of logged-in sites (YouTube's IDB alone is tens of MB; they are
     *  site state, not cache — which is why v1.3.78 deliberately never
     *  touched them). The user's law is now explicit — "I need under
     *  20mb" — so the exit sweep takes them too, BEHIND A TOGGLE that
     *  defaults ON (Settings → Data → "Deep clean on close"): cookies
     *  are KEPT, so most sign-ins survive; a few token-in-LocalStorage
     *  sites sign out, and pages rebuild their caches on the next visit.
     *  Honesty rules unchanged: never outside this list, never mid-session.
     *  v1.3.82: "File System" joins (Chromium's per-site file store of
     *  the deprecated FileSystem API — sites keep REAL files there,
     *  separate from cookies and from IDB; the storage saga's blind
     *  spot) and "Session Storage" (per-tab stores that should have
     *  died with their tab but linger in leveldb). Both are site state,
     *  so they ride the SAME toggle — never the mid-session set.
     *  v1.3.86 — LOCAL STORAGE GOES BUDGET-GATED. The "a few
     *  token-in-LocalStorage sites sign out" line was the v1.3.85 401
     *  field report in disguise: zai keeps its auth JWT in
     *  localStorage.token, the wipe turned every reopen into an
     *  INVISIBLE GUEST session (guests can chat — the UI looks signed-in
     *  — but uploads get 401 "You do not have permission"; the guest
     *  bootstrap even rewrites the token cookie). Local Storage is
     *  KBs-to-few-MB in normal use (IndexedDB and Service Worker are the
     *  real growers and still go), so it now survives the diet unless it
     *  is ITSELF the grower (>= LOCAL_STORAGE_KEEP_BYTES) — logins live,
     *  the "under 20mb" arithmetic barely notices. */
    private val SITE_DATA = arrayOf(
            "Local Storage", "IndexedDB", "databases", "Databases",
            "Service Worker", "File System", "Session Storage")

    /** v1.3.86 — Local Storage survives the diet below this size; a bigger
     *  store means some origin is abusing it and the old wipe applies
     *  again (sign-outs accepted — the storage law outranks them there). */
    private const val LOCAL_STORAGE_KEEP_BYTES = 8L * 1024 * 1024

    /** the ONLY subtrees this guard may ever measure or delete — cache by
     *  construction: everything here rebuilds on demand and holds nothing
     *  the site cannot re-fetch or re-compile. `Cache` is the HTTP disk
     *  cache, `Code Cache` compiled JS, `GPUCache`/`GrShaderCache`/
     *  `ShaderCache` GPU shader caches (current WebView builds carry more
     *  than one), the two Service Worker trees are the SW's own fetch
     *  caches, `blob_storage` backs blob: URLs (a video session leaves
     *  tens of MB), `Crashpad` holds crash dumps. v1.3.78 additions:
     *  blob_storage + Crashpad + the shader caches — the v1.3.77 sweep
     *  never saw them and the total stayed "over 50 mb after closing".
     *  v1.3.82: `Optimization Guide` joins — the WebView provider's
     *  prediction-model cache (re-downloaded on demand; it is NOT site
     *  data and NOT user data — the one multi-MB subtree the allowlist
     *  was still blind to). */
    private val SUBTREES = arrayOf(
        "Cache", "Code Cache", "GPUCache", "Media Cache",
        "GrShaderCache", "ShaderCache", "blob_storage", "Crashpad",
        "Optimization Guide",
        "Service Worker" + File.separator + "CacheStorage",
        "Service Worker" + File.separator + "ScriptCache")

    /** what a startup sweep may physically delete (everything); the
     *  mid-session SOFT set is the renderer-tolerant caches (a live SW
     *  re-fetches, shaders re-compile, media re-buffers — pages keep
     *  working); the HARD set adds the Code Cache (JS recompiles).
     *  v1.3.78: blob_storage NEVER joins the mid set — a live page's
     *  not-yet-read blob file must not vanish under it (held-open files
     *  are skipped by delete() anyway, but unopened ones are fair game
     *  and would break the page). */
    private val STARTUP_SET = SUBTREES
    private val MID_SET = arrayOf(
        "Service Worker" + File.separator + "CacheStorage",
        "Service Worker" + File.separator + "ScriptCache",
        "GPUCache", "Media Cache", "GrShaderCache", "ShaderCache", "Crashpad")
    private val HARD_SET = arrayOf("Code Cache")

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var lastAuditAt = 0L
    @Volatile private var running = false

    /** v1.4.31 — THE FILE-VISIT BUDGET (mid-session passes only; the
     *  trim-storm corpse: the audit walked tens of thousands of cache
     *  entries back to back while the user was switching tabs). A pass
     *  touches at most this many entries measuring/deleting; whatever it
     *  did not reach waits for the next trigger. Startup and exit sweeps
     *  are UNBOUNDED — the storage law's promises are measured at true
     *  start/close, never budgeted. */
    private const val FILE_BUDGET = 60_000L
    @Volatile private var visitLeft = Long.MAX_VALUE

    /**
     * v1.3.77 — THE EXIT SWEEP. Physically empties EVERY cache subtree
     * plus the app's own cacheDir, unconditionally (no budget, no rate
     * limit): "around 20 MB after closing" is a promise about the disk
     * state AFTER the browser is gone, so at close time the temporary
     * data must actually be gone. Called from BrowserActivity.onDestroy
     * and (v1.3.79) IMMEDIATELY from onStop — the 45 s delay assumed the
     * process lives 45 s after swipe-away, and on the OEM-killer hardware
     * this browser fields-tests on (realme/ColorOS) that assumption is
     * simply false: the kill lands in seconds, the Handler runnable dies
     * with the process, and the user reads the pre-sweep total ten
     * minutes later ("still sitting at 51mb... 46mb in app data, even
     * after 10 minutes of closing the app fully"). Files a dying renderer
     * still holds open are skipped, never fatal — the next cold start's
     * startup sweep finishes them. Own thread; never blocks the caller.
     * v1.3.79: with "Deep clean on close" ON (default), the site-data
     * diet joins (Local Storage + IndexedDB + SW registrations + legacy
     * databases; cookies KEPT so most sign-ins survive) and the marker
     * pair (.boltexit-start/.boltexit-done) lets the NEXT cold start
     * finish a sweep a racing process death interrupted.
     * v1.4.31 — THE EXIT-SWEEP CADENCE LAW (the tab-hist storm corpse:
     * "cache guard exit … deep=1" fired at EVERY onStop — 15 times in 90
     * minutes — because v1.3.79 traded the v1.3.77 delay for an
     * immediate sweep, and every tab switch that parked the activity ran
     * the full close-out + site-data diet while the user was coming
     * right back). exit() now runs ONLY on TRUE exits, three doors:
     *   destroy — BrowserActivity.onDestroy (the real close);
     *   user    — an explicit user-initiated sweep (Settings "Clean now");
     *   delayed — the 5-minute delayed onStop runnable, CANCELLED by
     *             onResume (the v1.3.77 cadence restored; the OEM-kill
     *             window is still covered by the start/done marker pair
     *             + the next cold start's pending-exit finisher).
     * The door rides the diag line so the field can see which door
     * fired. The startup sweep and the pending-exit finisher are
     * UNCHANGED — the ~20 MB promise still holds at true close.
     */
    @JvmStatic
    @JvmOverloads
    fun exit(app: Context, door: String = "destroy") {
        val ctx = app.applicationContext
        // v1.4.31 — the exit sweep is UNBOUNDED (the ~20 MB promise is
        // measured at true close; the budget is a mid-session guard only)
        visitLeft = Long.MAX_VALUE
        synchronized(this) {
            if (running) return
            running = true
        }
        Thread({
            try {
                markExit(ctx, started = true)
                val dirs = cacheDirs(ctx)
                val before = sizeOf(dirs)
                var freed = emptyAll(dirs)
                // the app's own cacheDir is cache by definition (temp
                // captures, scratch files, AND v1.3.79: the on-demand OCR
                // models moved here from filesDir — re-downloadable by
                // construction, which is what "cache" means in the
                // storage law's arithmetic) — its CONTENTS go too, and so
                // does the app-specific EXTERNAL cache dir
                try {
                    val cd = ctx.cacheDir
                    if (cd.isDirectory) freed += emptyAll(listOf(cd))
                } catch (_: Throwable) {}
                try {
                    val ed = ctx.externalCacheDir
                    if (ed != null && ed.isDirectory) freed += emptyAll(listOf(ed))
                } catch (_: Throwable) {}
                // v1.3.79 — THE SITE-DATA DIET (toggle-gated, default ON);
                // v1.3.86 — LOGIN PRESERVATION: Local Storage survives
                // unless it is the grower (see LOCAL_STORAGE_KEEP_BYTES);
                // IndexedDB / Service Worker / File System / Session
                // Storage / legacy databases always go
                var deep = false
                try {
                    if (Prefs.get(ctx).deepCleanExit()) {
                        deep = true
                        val lsDirs = localStorageDirs(ctx)
                        val lsSize = sizeOf(lsDirs)
                        val keepLs = lsSize < LOCAL_STORAGE_KEEP_BYTES
                        if (!keepLs) {
                            try {
                                // the proper API first (clean LevelDB shutdown
                                // semantics); the physical sweep below catches
                                // whatever the async delete leaves behind
                                android.webkit.WebStorage.getInstance().deleteAllData()
                            } catch (_: Throwable) {}
                            try { Thread.sleep(250) } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                        freed += emptyAll(siteDataDirs(ctx, keepLs))
                        if (keepLs) {
                            try {
                                Diag.log(ctx, "cache guard ls-kept " + mb(lsSize)
                                        + "mb (logins)")
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
                // v1.3.78 — THE EVIDENCE LINE: if the total is still over
                // target the report can now NAME the grower. One diag line
                // with the biggest app_webview children after the sweep
                // (anything the allowlist may never touch deliberately)
                // + the app's own filesDir top items.
                try {
                    Diag.log(ctx, "cache guard exit door=" + door + " " +
                            mb(before) + "mb freed=" + mb(freed) + "mb" +
                            (if (deep) " deep=1" else "") + breakdown(ctx))
                } catch (_: Throwable) {}
                markExit(ctx, started = false)
            } catch (_: Throwable) {
                // a cache trim must never crash the browser
            } finally {
                running = false
            }
        }, "bolt-cache-exit").start()
    }

    /** v1.3.79 — the interrupted-sweep markers. exit() writes the "start"
     *  marker first and the "done" marker last; a cold start that finds
     *  start-without-done finishes the diet (the process died mid-sweep). */
    private fun markExit(ctx: Context, started: Boolean) {
        try {
            val f = File(ctx.filesDir, if (started) ".boltexit-start" else ".boltexit-done")
            f.writeText(if (started) "s" else "d")
            if (!started) File(ctx.filesDir, ".boltexit-start").delete()
        } catch (_: Throwable) {}
    }

    /** true when a previous session's exit sweep was interrupted by process
     *  death (start marker without done) — the cold start asks once, before
     *  any tab WebView exists. */
    @JvmStatic
    fun pendingExit(ctx: Context): Boolean = try {
        File(ctx.filesDir, ".boltexit-start").exists() &&
                !File(ctx.filesDir, ".boltexit-done").exists()
    } catch (_: Throwable) { false }

    /** the interrupted-sweep finisher: no cache work (the startup check
     *  owns caches), ONLY the site-data diet + markers. */
    @JvmStatic
    fun completePendingExit(app: Context) {
        val ctx = app.applicationContext
        if (!pendingExit(ctx)) return
        visitLeft = Long.MAX_VALUE
        Thread({
            try {
                try {
                    if (Prefs.get(ctx).deepCleanExit()) {
                        // v1.3.86 — the same login-preserving budget as exit()
                        val keepLs = sizeOf(localStorageDirs(ctx)) < LOCAL_STORAGE_KEEP_BYTES
                        if (!keepLs) {
                            try { android.webkit.WebStorage.getInstance().deleteAllData() } catch (_: Throwable) {}
                        }
                        val freed = emptyAll(siteDataDirs(ctx, keepLs))
                        Diag.log(ctx, "cache guard resume-deep freed=" + mb(freed) + "mb"
                                + (if (keepLs) " ls-kept (logins)" else ""))
                    }
                } catch (_: Throwable) {}
                markExit(ctx, started = false)
            } catch (_: Throwable) {
            } finally {
            }
        }, "bolt-cache-resume").start()
    }

    /** every SITE-DATA directory that exists (the deep-clean delete set,
     *  across every app_webview* root and both on-disk layouts).
     *  v1.3.86: keepLocalStorage=true excludes the Local Storage trees —
     *  the login-preservation budget decided the caller keeps them. */
    private fun siteDataDirs(ctx: Context, keepLocalStorage: Boolean = false): List<File> {
        val out = ArrayList<File>()
        val dataDir = try {
            ctx.applicationInfo.dataDir
        } catch (_: Throwable) { null } ?: return out
        val roots = File(dataDir).listFiles() ?: return out
        for (root in roots) {
            val rn = root.name
            if (!root.isDirectory) continue
            if (!(rn == "app_webview" || rn.startsWith("app_webview_"))) continue
            for (profile in listOf(File(root, "Default"), root)) {
                for (sub in SITE_DATA) {
                    if (keepLocalStorage && sub == "Local Storage") continue
                    val d = File(profile, sub)
                    if (d.isDirectory) out.add(d)
                }
            }
        }
        return out
    }

    /** the Local Storage trees alone (the v1.3.86 budget's measurer). */
    private fun localStorageDirs(ctx: Context): List<File> {
        val out = ArrayList<File>()
        try {
            val dataDir = ctx.applicationInfo.dataDir ?: return out
            val roots = File(dataDir).listFiles() ?: return out
            for (root in roots) {
                if (!root.isDirectory) continue
                if (!(root.name == "app_webview" || root.name.startsWith("app_webview_"))) continue
                for (profile in listOf(File(root, "Default"), root)) {
                    val d = File(profile, "Local Storage")
                    if (d.isDirectory) out.add(d)
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    /** v1.3.78 — the biggest on-disk children, for the field's eyes:
     *  ` | wv:Local Storage=12mb IndexedDB=8mb` + ` | files:ocrfast=9mb`.
     *  Read-only; nothing is deleted by this walk. */
    private fun breakdown(ctx: Context): String {
        return try {
            val dataDir = ctx.applicationInfo.dataDir ?: return ""
            val parts = ArrayList<String>()
            val wvRoots = File(dataDir).listFiles()?.filter {
                it.isDirectory && (it.name == "app_webview" || it.name.startsWith("app_webview_"))
            } ?: emptyList()
            val kidSizes = HashMap<String, Long>()
            for (root in wvRoots) {
                val cs = root.listFiles() ?: continue
                for (c in cs) {
                    val key = if (c.isDirectory) c.name else "(files)"
                    kidSizes[key] = (kidSizes[key] ?: 0L) + du(c)
                }
                val def = File(root, "Default")
                if (def.isDirectory) {
                    val dcs = def.listFiles() ?: continue
                    for (c in dcs) {
                        val key = "Default/" + c.name
                        kidSizes[key] = (kidSizes[key] ?: 0L) + du(c)
                    }
                }
            }
            kidSizes.entries.filter { it.value > 1048576L }
                .sortedByDescending { it.value }.take(6)
                .forEach { parts.add(it.key.substring(0, minOf(22, it.key.length)) +
                        "=" + mb(it.value) + "mb") }
            val sb = StringBuilder()
            if (parts.isNotEmpty()) sb.append(" | wv:").append(parts.joinToString(" "))
            // v1.3.79: the whole filesDir, top-3 children — any grower gets
            // named, not just the (now-relocated) OCR models
            try {
                val fk = HashMap<String, Long>()
                val fs = ctx.filesDir.listFiles() ?: emptyArray()
                for (f in fs) fk[f.name] = du(f)
                fk.entries.filter { it.value > 1048576L }
                        .sortedByDescending { it.value }.take(3)
                        .forEach { sb.append(" | files:").append(it.key.substring(0, minOf(14, it.key.length)))
                                .append("=").append(mb(it.value)).append("mb") }
            } catch (_: Throwable) {}
            // and the app's own cacheDir (should read ~0 after the sweep)
            try {
                val cd = du(ctx.cacheDir)
                if (cd > 1048576L) sb.append(" | cache:").append(mb(cd)).append("mb")
            } catch (_: Throwable) {}
            sb.toString()
        } catch (_: Throwable) { "" }
    }

    /**
     * One guard pass. [startup] = true for the cold-start sweep (full
     * authority, always runs); false for the rate-limited mid-session
     * audit. [web] is ANY live WebView of this app — `clearCache` is an
     * INSTANCE API whose effect is app-global (the whole HTTP cache), so
     * one is as good as another; null (no tabs) skips the API path and
     * leaves the HTTP cache to the next startup sweep. Safe to call from
     * anywhere — the work rides its own thread and the only UI-thread
     * touch is the posted `clearCache`.
     */
    @JvmStatic
    @JvmOverloads
    fun check(app: Context, startup: Boolean, web: WebView? = null) {
        val ctx = app.applicationContext
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (running) return
            if (!startup && lastAuditAt != 0L && now - lastAuditAt < MIN_INTERVAL_MS) return
            lastAuditAt = now
            running = true
        }
        Thread({
            try {
                run(ctx, startup, web)
            } catch (_: Throwable) {
                // a cache trim must never crash the browser
            } finally {
                running = false
            }
        }, "bolt-cache-guard").start()
    }

    private fun run(ctx: Context, startup: Boolean, web: WebView?) {
        // v1.4.31 — the file-visit budget: mid-session passes are bounded,
        // the startup sweep is not (see FILE_BUDGET)
        visitLeft = if (startup) Long.MAX_VALUE else FILE_BUDGET
        val dirs = cacheDirs(ctx)
        val before = sizeOf(dirs)
        if (before < SOFT) {
            if (startup) try {
                Diag.log(ctx, "cache guard ok " + mb(before))
            } catch (_: Throwable) {}
            return
        }
        if (startup) {
            // no renderer is alive at cold start (BrowserActivity calls this
            // right after destroyStaleWebViews): every cache file is dead —
            // delete the CONTENTS, the directories themselves stay. Every
            // one of these rebuilds on demand; that is what cache means.
            val freed = emptyAll(dirsOf(dirs, STARTUP_SET))
            Diag.log(ctx, "cache guard start " + mb(before) + "mb freed=" + mb(freed) + "mb")
            return
        }
        // mid-session: the API path first — safe with live renderers and it
        // owns the HTTP cache (the video grower) end to end. clearCache is
        // an INSTANCE method with an app-global effect; one WebView of the
        // app is as good as any other
        main.post {
            try {
                web?.clearCache(true)
            } catch (_: Throwable) {
                // a dead/detached instance — the startup sweep owns the case
            }
        }
        // v1.3.77: the renderer-tolerant subtrees sweep at SOFT now (the
        // SW caches are Facebook's biggest silent grower — waiting for
        // 300 MB was waiting forever); the Code Cache joins at HARD
        var freed = emptyAll(dirsOf(dirs, MID_SET))
        if (before >= HARD) {
            freed += emptyAll(dirsOf(dirs, HARD_SET))
        }
        Diag.log(ctx, "cache guard trim " + mb(before) + "mb api=1" +
                (if (freed > 0) " freed=" + mb(freed) + "mb" else ""))
        if (freed > 12L * 1024 * 1024) {
            main.post {
                try {
                    Fx.toast(ctx, "Trimmed " + mb(freed) + " MB of temporary cache")
                } catch (_: Throwable) {}
            }
        }
    }

    /** every allowlisted cache directory that exists, across every
     *  `app_webview*` root and both on-disk layouts (modern `<root>/Default/
     *  <sub>` and legacy flat `<root>/<sub>`) — the same walk SiteStorageWipe
     *  uses, so multi-profile builds are covered for free. */
    private fun cacheDirs(ctx: Context): List<File> {
        val out = ArrayList<File>()
        val dataDir = try {
            ctx.applicationInfo.dataDir
        } catch (_: Throwable) { null } ?: return out
        val roots = File(dataDir).listFiles() ?: return out
        for (root in roots) {
            val rn = root.name
            if (!root.isDirectory) continue
            if (!(rn == "app_webview" || rn.startsWith("app_webview_"))) continue
            for (profile in listOf(File(root, "Default"), root)) {
                for (sub in SUBTREES) {
                    val d = File(profile, sub)
                    if (d.isDirectory) out.add(d)
                }
            }
        }
        return out
    }

    /** the subset of [all] whose path ends with one of [set]'s relative
     *  paths (path-suffix match: `…/Default/Cache` matches "Cache" and
     *  `…/Service Worker/CacheStorage` matches its two-segment name) */
    private fun dirsOf(all: List<File>, set: Array<String>): List<File> {
        val out = ArrayList<File>()
        for (d in all) {
            val p = d.absolutePath
            for (s in set) {
                if (p.endsWith(s)) { out.add(d); break }
            }
        }
        return out
    }

    /** total bytes under every dir (missing dirs are free) */
    private fun sizeOf(dirs: List<File>): Long {
        var total = 0L
        for (d in dirs) total += du(d)
        return total
    }

    private fun du(f: File): Long {
        var total = 0L
        try {
            if (visitLeft <= 0) return 0L
            if (f.isFile) {
                visitLeft--
                return f.length()
            }
            val cs = f.listFiles() ?: return 0L
            for (c in cs) total += du(c)
        } catch (_: Throwable) {}
        return total
    }

    /** delete the CONTENTS of each dir (the dir itself stays — Chromium
     *  expects its cache roots to exist); returns bytes removed */
    private fun emptyAll(dirs: List<File>): Long {
        var freed = 0L
        for (d in dirs) {
            val cs = try { d.listFiles() } catch (_: Throwable) { null } ?: continue
            for (c in cs) freed += rm(c)
        }
        return freed
    }

    /** delete f if possible; returns the bytes actually removed (a file a
     *  live process still holds is skipped, never fatal — it dies at the
     *  next sweep) */
    private fun rm(f: File): Long {
        return try {
            var freed = 0L
            if (visitLeft <= 0) return 0L
            if (f.isDirectory) {
                val cs = f.listFiles()
                if (cs != null) for (c in cs) freed += rm(c)
            }
            try { freed += f.length() } catch (_: Throwable) {}
            visitLeft--
            if (f.delete()) freed else 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun mb(b: Long): Long = b / (1024 * 1024)

    /** v1.3.82 — THE FIELD-VISIBLE STORAGE REPORT (Settings → Privacy →
     *  Storage): the storage-law saga ran blind from the field side
     *  ("still 50mb" arrived with no diag line attached, and every
     *  round-trip cost a release). Same read-only walk the exit diag
     *  line uses, plus the numbers Android's own Settings page shows:
     *  the data dir total, the cache portion of it, and the APK. BLOCKING
     *  (a full disk walk) — call from a worker thread, never the UI. */
    @JvmStatic
    fun report(ctx: Context): String {
        return try {
            val app = ctx.applicationContext
            val dataDir = try {
                app.applicationInfo.dataDir
            } catch (_: Throwable) { null }
            val data = dataDir?.let { du(File(it)) } ?: 0L
            val cache = du(app.cacheDir) +
                    (try { app.externalCacheDir?.let { du(it) } ?: 0L } catch (_: Throwable) { 0L })
            val apk = try {
                File(app.applicationInfo.sourceDir).length()
            } catch (_: Throwable) { 0L }
            val sb = StringBuilder()
            sb.append("App data: ").append(mb(data)).append(" MB")
                    .append("  (temporary cache: ").append(mb(cache)).append(" MB)")
                    .append("\nApp code (APK): ").append(mb(apk)).append(" MB")
            val bk = breakdown(app)
            if (bk.isNotEmpty()) sb.append("\n").append(bk.trimStart(' ', '|'))
            sb.append("\n\nData that survives every sweep: cookies + saved " +
                    "logins, Local Storage (login tokens — kept under " +
                    "8 MB), filters, bookmarks/history, saved pages, the " +
                    "custom wallpaper — user data, never deleted here.")
            sb.toString()
        } catch (_: Throwable) {
            "App data: ? (measurement failed)"
        }
    }
}
