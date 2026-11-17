package com.bolt.browser.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;

import com.bolt.browser.core.DownloadEngine;
import com.bolt.browser.core.Tab;
import com.bolt.browser.core.TabManager;
import com.bolt.browser.media.MediaControlService;
import com.bolt.browser.media.MediaHook;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Browser-wide data clearing used by both "Clear browsing data" and the
 * clear-on-exit checklist.
 *
 * v1.3.13 fixes:
 *  - WHY THE APP CACHE SURVIVED BEFORE: the physical directory wipe ran on a
 *    background thread with a 400 ms delay, and the exit path called
 *    finishAffinity() immediately after — on aggressively-managed devices the
 *    now-cached process was killed mid-wipe. Cookies/history/logins are
 *    in-memory + tiny-DB operations that completed synchronously, which is
 *    exactly why users saw "history and logins clear fine but the cache never
 *    does". The exit path now runs an ORDERED sequence that ends with the
 *    process being killed only AFTER the wipe finished (see performExitClear).
 *  - Chromium keeps writing cache files while live tabs exist, so the exit
 *    wipe destroys the WebViews BEFORE deleting directories — nothing can
 *    re-populate the cache afterwards.
 *  - The Chromium cache sweep also covers Service Worker CacheStorage /
 *    ScriptCache (the biggest quiet storage hogs on media sites) and
 *    PnaclTranslationCache.
 */
public final class DataWipe {

    private DataWipe() {}

    /** Full browser-wide wipe for the interactive "Clear browsing data" dialog.
     *  Runs the quick parts synchronously; the physical cache delete continues
     *  on a worker thread and reports the freed bytes back (the process is
     *  alive — the user is still in the app — so completion is guaranteed). */
    public static void wipeAll(final Context ctx, boolean cache, boolean history,
            boolean cookies, boolean storage, boolean passwords) {
        wipeAll(ctx, cache, history, cookies, storage, passwords, null);
    }

    public static void wipeAll(final Context ctx, boolean cache, boolean history,
            boolean cookies, boolean storage, boolean passwords,
            final FreedCallback onFreed) {
        final Context app = ctx.getApplicationContext();
        TabManager tm = TabManager.get(app);
        List<Tab> tabs = new ArrayList<>(tm.tabs);

        // 1. wipe live pages from the inside FIRST (async IndexedDB /
        //    CacheStorage / service-worker deletion needs the page alive)
        if (storage || cookies) {
            for (Tab t : tabs) {
                if (t.web != null && !t.isHome) {
                    try { t.web.evaluateJavascript(MediaHook.wipePageData(), null); }
                    catch (Throwable ignored) {}
                }
            }
        }
        if (cookies) {
            try {
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookies(null);
                cm.removeSessionCookies(null);
                cm.flush();
            } catch (Throwable ignored) {}
        }
        if (storage) {
            try { WebStorage.getInstance().deleteAllData(); } catch (Throwable ignored) {}
        }
        if (passwords) {
            try { WebViewDatabase.getInstance(app).clearFormData(); } catch (Throwable ignored) {}
            try { WebViewDatabase.getInstance(app).clearHttpAuthUsernamePassword(); } catch (Throwable ignored) {}
        }
        if (history) {
            Prefs.get(app).clearHistory();
        }
        if (cache) {
            // clearCache(true) is per-application and needs any live WebView;
            // fall back to a throwaway one when none exists yet
            boolean cleared = false;
            for (Tab t : tabs) {
                if (t.web != null) {
                    try { t.web.clearCache(true); cleared = true; } catch (Throwable ignored) {}
                }
            }
            if (!cleared) {
                try {
                    WebView tmp = new WebView(app);
                    tmp.clearCache(true);
                    tmp.destroy();
                } catch (Throwable ignored) {}
            }
            deleteCacheDirsAsync(app, onFreed);
        }
    }

    public interface FreedCallback { void onFreed(long bytes); }

    /**
     * v1.3.13 ORDERED exit-clear. Runs on the calling (main) thread — the UI
     * blocks for the file deletion, which is the entire point: the wipe is
     * COMPLETED before the app is allowed to die.
     *
     * Sequence:
     *   1. pause all playing media + stop the media service
     *   2. wipe pages from the inside + cookies/storage/passwords/history
     *   3. destroy every WebView  (Chromium stops writing cache files)
     *   4. clearCache(true) on a throwaway WebView (flush HTTP cache index)
     *   5. physically empty cache + code_cache + external cache + every
     *      Chromium cache subtree under app_webview*  (synchronous!)
     *   6. finishAffinity() and kill our own process (so nothing re-writes),
     *      unless a download is in flight (its foreground service owns the
     *      process then; the wipe has already completed either way)
     *
     * Swipe-from-recents NEVER comes through here — only the power button
     * exit paths call this.
     */
    public static void performExitClear(final Context ctx, final Prefs prefs) {
        final Context app = ctx.getApplicationContext();
        final boolean wCache = prefs.clearExit("cache");
        final boolean wHistory = prefs.clearExit("history");
        final boolean wCookies = prefs.clearExit("cookies");
        final boolean wStorage = prefs.clearExit("storage");
        final boolean wPasswords = prefs.clearExit("passwords");

        // 1. media: pause everything, then stop the service
        TabManager tm = TabManager.get(app);
        for (Tab t : new ArrayList<>(tm.tabs)) {
            if (t.web != null) {
                try { t.web.evaluateJavascript(
                        "window.__boltCmd&&window.__boltCmd('pause')", null); }
                catch (Throwable ignored) {}
            }
        }
        try { app.stopService(new Intent(app, MediaControlService.class)); } catch (Throwable ignored) {}

        // 2. wipe pages from the inside + the quick data stores
        if (wStorage || wCookies) {
            for (Tab t : new ArrayList<>(tm.tabs)) {
                if (t.web != null && !t.isHome) {
                    try { t.web.evaluateJavascript(MediaHook.wipePageData(), null); }
                    catch (Throwable ignored) {}
                }
            }
        }
        if (wCookies) {
            try {
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookies(null);
                cm.removeSessionCookies(null);
                cm.flush();
            } catch (Throwable ignored) {}
        }
        if (wStorage) {
            try { WebStorage.getInstance().deleteAllData(); } catch (Throwable ignored) {}
        }
        if (wPasswords) {
            try { WebViewDatabase.getInstance(app).clearFormData(); } catch (Throwable ignored) {}
            try { WebViewDatabase.getInstance(app).clearHttpAuthUsernamePassword(); } catch (Throwable ignored) {}
        }
        if (wHistory) {
            prefs.clearHistory();
        }

        // 3. ALWAYS destroy the WebViews on the exit path. Two reasons:
        //    - with the renderers gone nothing re-writes the cache behind the
        //      delete (the wipe would be undone seconds later otherwise);
        //    - the service was just stopped, and WebViews that outlive it would
        //      keep playing audio with NO notification and NO control surface.
        //      Tabs restore from the saved session on next launch either way.
        for (Tab t : new ArrayList<>(tm.tabs)) {
            if (t.web != null) {
                try {
                    t.web.stopLoading();
                    if (t.web.getParent() instanceof android.view.ViewGroup) {
                        ((android.view.ViewGroup) t.web.getParent()).removeView(t.web);
                    }
                    t.web.destroy();
                } catch (Throwable ignored) {}
                t.web = null;
                t.mediaPlaying = false;
            }
        }

        if (wCache) {
            // 4. flush Chromium's HTTP-cache bookkeeping with a throwaway WebView
            try {
                WebView tmp = new WebView(app);
                tmp.clearCache(true);
                tmp.destroy();
            } catch (Throwable ignored) {}
            // 5. synchronous physical wipe — COMPLETED before we leave
            deleteCacheDirsSync(app);
        }

        // 6. leave. The process kill only happens when data was actually wiped
        //    (a hard, guaranteed-complete exit); a plain exit keeps the cached
        //    process for a fast relaunch — with the renderers dead there is
        //    nothing left that could misbehave. Active downloads always win.
        try {
            if (ctx instanceof Activity) ((Activity) ctx).finishAffinity();
        } catch (Throwable ignored) {}
        boolean anyClear = wCache || wHistory || wCookies || wStorage || wPasswords;
        if (anyClear) {
            boolean downloadsActive = false;
            try { downloadsActive = DownloadEngine.get(app).anyActive(); } catch (Throwable ignored) {}
            if (!downloadsActive) {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }
    }

    /**
     * Async cache delete for the interactive clear path (the app is open, the
     * process alive — the thread is guaranteed to finish).
     */
    public static void deleteCacheDirsAsync(final Context ctx, final FreedCallback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            long freed = deleteCacheDirsSync(app);
            if (cb != null) cb.onFreed(freed);
        }, "bolt-cache-wipe").start();
    }

    /**
     * REALLY delete every cache-shaped directory — this is what "Clear cache"
     * must actually remove:
     *  - the app cache + code-cache + external-cache dirs (Android Settings'
     *    "Cache" figure). This already includes the download .part staging
     *    area (failed-download residue) and temp offline-page copies.
     *  - Chromium WebView's own caches inside <dataDir>/app_webview*: the HTTP
     *    disk cache (Default/Cache), the JS code cache, GPU/shader caches,
     *    Service Worker CacheStorage + ScriptCache, blob staging and crashpad
     *    minidumps. THESE LIVE IN APP DATA, NOT THE CACHE DIR — they are the
     *    bulk of what grows while you browse, and they are exactly why "the
     *    app cache is still there" after Clear. Login state (Cookies, Local
     *    Storage, IndexedDB, Web Data, Service Worker registrations) is NEVER
     *    touched here.
     * Returns bytes freed.
     */
    public static long deleteCacheDirsSync(Context ctx) {
        Context app = ctx.getApplicationContext();
        long freed = 0;
        try { freed += rmrfContents(app.getCacheDir()); } catch (Throwable ignored) {}
        try { freed += rmrfContents(app.getCodeCacheDir()); } catch (Throwable ignored) {}
        try {
            File ec = app.getExternalCacheDir();
            if (ec != null) freed += rmrfContents(ec);
        } catch (Throwable ignored) {}
        freed += wipeWebViewCaches(app);
        return freed;
    }

    /** directory names that are pure cache inside a Chromium profile — never
     *  delete anything else (Cookies / Local Storage / IndexedDB / Web Data /
     *  Sessions / Service Worker REGISTRATIONS hold logins + site data and
     *  must survive a CACHE-only clear; the CacheStorage/ScriptCache INSIDE
     *  Service Worker is re-downloadable cache and IS wiped) */
    private static final java.util.Set<String> CHROMIUM_CACHE_DIRS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "Cache", "Code Cache", "GPUCache", "DawnCache",
                    "DawnGraphiteCache", "DawnWebGPUCache", "GrShaderCache",
                    "ShaderCache", "blob_storage", "PnaclTranslationCache",
                    "CacheStorage", "ScriptCache"));

    /** Chromium keeps its profile under <dataDir>/app_webview (and
     *  app_webview_<suffix> for the incognito process). Walk it and empty
     *  every cache-shaped directory in place. */
    private static long wipeWebViewCaches(Context app) {
        String dataDir = null;
        try { dataDir = app.getApplicationInfo().dataDir; } catch (Throwable ignored) {}
        if (dataDir == null) return 0;
        File[] children = new File(dataDir).listFiles();
        if (children == null) return 0;
        long freed = 0;
        for (File f : children) {
            try {
                if (!f.isDirectory()) continue;
                String n = f.getName();
                if (n.equals("app_webview") || n.startsWith("app_webview_")) {
                    freed += pruneCacheDirs(f, 0);
                }
            } catch (Throwable ignored) {}
        }
        return freed;
    }

    /** depth-limited descent: empty cache-named dirs, clear Crashpad reports,
     *  recurse into everything else */
    private static long pruneCacheDirs(File dir, int depth) {
        if (dir == null || depth > 6) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        long freed = 0;
        for (File f : children) {
            try {
                if (!f.isDirectory()) continue;
                String n = f.getName();
                if ("Crashpad".equals(n)) {
                    // crash minidumps accumulate silently with use — pure junk.
                    // Only the reports; the crashpad client's own metadata files
                    // are tiny and it holds handles to them.
                    File rep = new File(f, "reports");
                    if (rep.isDirectory()) freed += rmrfContents(rep);
                } else if (CHROMIUM_CACHE_DIRS.contains(n)) {
                    freed += rmrfContents(f); // keep the dir shell, contents gone
                } else {
                    freed += pruneCacheDirs(f, depth + 1);
                }
            } catch (Throwable ignored) {}
        }
        return freed;
    }

    /** delete everything INSIDE dir (keep the dir itself); second pass
     *  retries stragglers. Returns bytes freed. */
    private static long rmrfContents(File dir) {
        if (dir == null || !dir.exists()) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        long freed = 0;
        List<File> failed = new ArrayList<>();
        for (File f : children) {
            long n = rmrf(f);
            if (n >= 0) freed += n; else failed.add(f);
        }
        for (File f : failed) rmrf(f); // one retry for transient locks
        return freed;
    }

    /** recursively delete f. Returns bytes freed, or -1 if something survived. */
    private static long rmrf(File f) {
        if (f == null || !f.exists()) return 0;
        long freed = 0;
        boolean ok = true;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    long n = rmrf(c);
                    if (n >= 0) freed += n; else ok = false;
                }
            }
        } else {
            try { freed = f.length(); } catch (Throwable ignored) {}
        }
        try { if (!f.delete()) ok = false; } catch (Throwable ignored) { ok = false; }
        return ok ? freed : -1;
    }

    /** Reload every open tab so live pages drop their in-memory login state. */
    public static void reloadAllTabs(Context ctx) {
        TabManager tm = TabManager.get(ctx.getApplicationContext());
        for (Tab t : new ArrayList<>(tm.tabs)) {
            if (t.web != null && !t.isHome && t.url != null && t.url.startsWith("http")) {
                try {
                    java.util.Map<String, String> noCache =
                            new java.util.HashMap<>();
                    noCache.put("Cache-Control", "no-cache");
                    t.web.loadUrl(t.url, noCache);
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * v1.3.21 — renderer-crash auto-repair. Wipes ONLY the Chromium cache
     * trees under app_webview* (HTTP cache, JS/Code Cache, GPU+shader caches,
     * CacheStorage/ScriptCache, crashpad reports) — the classic cause of
     * DETERMINISTIC per-site renderer crashes that survive app updates is a
     * corrupted entry in exactly those files (Chromium re-reads the same bad
     * bytecode/shader for the same heavy site every time). Login state and
     * site data are untouched; the app cache dir (download .part staging!) is
     * NOT touched either. Also callable manually from Settings > Advanced >
     * "Repair web engine". Returns bytes freed.
     */
    public static long wipeRendererCaches(Context ctx) {
        return wipeWebViewCaches(ctx.getApplicationContext());
    }
}
