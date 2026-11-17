package com.bolt.browser.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * All browser settings. Global prefs live in filesDir/prefs.json (durable user data),
 * temp stuff in cacheDir. Per-site settings in filesDir/sites.json.
 * History/bookmarks/groups/speed dial in their own JSON files.
 */
public class Prefs {
    private static Prefs instance;
    private final File dir;
    private final File cacheDir;
    private JSONObject global;
    private JSONObject sites;
    private JSONArray bookmarks, history, groups, speeddial, customLists;

    // v1.4.5 — THE DIRTY-WRITE LAW (the cross-process clobber corpse): the
    // incognito process loads its own in-memory copies of these files at
    // ITS startup, and writeNow() used to rewrite EVERY file from that
    // copy — so any setting change in incognito (the placement toggle
    // among them) silently reverted groups.json/speeddial.json on disk to
    // whatever the incognito process had loaded, DESTROYING folders and
    // home shortcuts created in the main process since ("some tabs or
    // tab groups seem to disappear"). A file is now written ONLY when its
    // in-memory serialization differs from the last state this process
    // wrote/loaded — an untouched copy is never a writer. The disk*
    // strings are updated only after a successful write so a failed write
    // retries on the next pass.
    private String diskGlobal, diskSites, diskBookmarks, diskHistory,
            diskGroups, diskSpeeddial;

    private Prefs(Context c) {
        dir = c.getFilesDir();
        cacheDir = c.getCacheDir();
        global = Fx.readJson(file("prefs.json"));
        sites = Fx.readJson(file("sites.json"));
        bookmarks = Fx.readJsonArray(file("bookmarks.json"));
        history = Fx.readJsonArray(file("history.json"));
        groups = Fx.readJsonArray(file("groups.json"));
        speeddial = Fx.readJsonArray(file("speeddial.json"));
        customLists = global.optJSONArray("customLists");
        if (customLists == null) customLists = new JSONArray();
        synchronized (WRITE_LOCK) {
            diskGlobal = global.toString();
            diskSites = sites.toString();
            diskBookmarks = bookmarks.toString();
            diskHistory = history.toString();
            diskGroups = groups.toString();
            diskSpeeddial = speeddial.toString();
        }
    }

    public static synchronized Prefs get(Context c) {
        if (instance == null) instance = new Prefs(c.getApplicationContext());
        return instance;
    }

    private File file(String n) { return new File(dir, n); }
    public File filesDir() { return dir; }
    public File cacheDirectory() { return cacheDir; }
    public File filtersDir() { return new File(dir, "filters"); }
    public File scriptsDir() { return new File(dir, "scripts"); }
    public File offlineDir() { return new File(dir, "offline"); }

    // ---- global getters/setters ----
    public boolean b(String k, boolean def) { return global.optBoolean(k, def); }
    public int i(String k, int def) { return global.optInt(k, def); }
    public long l(String k, long def) { return global.optLong(k, def); }
    public String s(String k, String def) { return global.optString(k, def); }
    /** true when the key was ever written (v1.3.35: mode migration check). */
    public boolean has(String k) { return global.has(k); }

    public void set(String k, Object v) {
        try {
            synchronized (WRITE_LOCK) { global.put(k, v); }
            scheduleWrite();
        } catch (Throwable ignored) {}
    }

    // ---- v1.3.79 — THE WRITE DEBOUNCE (the tap-lag fix, root cause #1) ----
    // set() used to serialize the ENTIRE prefs.json and write it to flash
    // on the CALLING thread (the main thread) on EVERY change — and the
    // media sheet's SeekBars alone called it dozens of times per second
    // while dragging. addHistory did the same with the whole 2000-entry
    // history array on every page finish. Every mutation now lands in
    // memory and a single background writer coalesces the disk work
    // (400 ms window, single-threaded, snapshot under the lock).
    // flush() is the synchronous barrier for lifecycle moments (onStop,
    // onDestroy) — the one place disk writes are allowed to wait.
    private static final Object WRITE_LOCK = new Object();
    private static final java.util.concurrent.ScheduledExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bolt-prefs-write");
                t.setDaemon(true);
                return t;
            });
    private java.util.concurrent.ScheduledFuture<?> pendingWrite;

    private void scheduleWrite() {
        try {
            synchronized (this) {
                if (pendingWrite != null) pendingWrite.cancel(false);
                pendingWrite = WRITER.schedule(this::writeNow, 400,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ignored) {}
    }

    /** the coalesced write (writer thread): one pass, every DIRTY file —
     *  v1.4.5: a file is dirty only when its live serialization differs
     *  from the last state this process loaded/wrote (the dirty-write
     *  law; an untouched copy never writes — the incognito process can
     *  no longer clobber main-process folders with its stale snapshot). */
    private void writeNow() {
        try {
            synchronized (this) { pendingWrite = null; }
            String p, st, h, b, g, sd;
            synchronized (WRITE_LOCK) {
                p = global.toString(); st = sites.toString();
                h = history.toString(); b = bookmarks.toString();
                g = groups.toString(); sd = speeddial.toString();
            }
            if (!p.equals(diskGlobal)
                    && Fx.writeText(file("prefs.json"), p)) diskGlobal = p;
            if (!st.equals(diskSites)
                    && Fx.writeText(file("sites.json"), st)) diskSites = st;
            if (!h.equals(diskHistory)
                    && Fx.writeJsonArray(file("history.json"), new JSONArray(h))) diskHistory = h;
            if (!b.equals(diskBookmarks)
                    && Fx.writeJsonArray(file("bookmarks.json"), new JSONArray(b))) diskBookmarks = b;
            if (!g.equals(diskGroups)
                    && Fx.writeJsonArray(file("groups.json"), new JSONArray(g))) diskGroups = g;
            if (!sd.equals(diskSpeeddial)
                    && Fx.writeJsonArray(file("speeddial.json"), new JSONArray(sd))) diskSpeeddial = sd;
        } catch (Throwable ignored) {}
    }

    /** synchronous barrier — call at onStop/onDestroy so a process death
     *  right after closing never loses the last window of changes. */
    public void flush() {
        try {
            synchronized (this) {
                if (pendingWrite != null) pendingWrite.cancel(false);
                pendingWrite = null;
            }
            writeNow();
        } catch (Throwable ignored) {}
    }

    // ---- themed ----
    /** v1.3.33 skins: 0 = Classic, 1 = Aura (translucent glass), 2 = Mono
     * (true black flat), 3 = Origins (warm soft), 4 = Pulse (vibrant).
     * v1.3.35: every skin has BOTH lightness variants — the "theme" pref is
     * now the GLOBAL mode (0 light / 1 dark / 2 follow-system) for all skins,
     * not just Classic. Migration: Origins was light-only before v1.3.35, so
     * an Origins user without an explicit mode pin keeps LIGHT. */
    public int skin() { return i("skin", 0); }
    public boolean isDark() {
        // one-time migration: pre-v1.3.35 Origins (light-only) users
        if (skin() == 3 && !has("theme")) set("theme", 0);
        int t = i("theme", 1); // default dark (what every pre-v1.3.35 skin showed)
        if (t == 2) {
            int nm = cResNight();
            return nm == 1;
        }
        return t == 1;
    }
    private int cResNight = -1;
    public void setUiMode(int ui) { cResNight = ui; }
    private int cResNight() { return cResNight; }

    /** v1.3.35 accent modes: -1 = Auto (each skin's signature accent), -2 =
     * Wallpaper (Material You, API 31+), >=0 = the fixed swatch. Default is
     * Auto so switching skins changes the whole personality. Existing
     * installs that picked a swatch keep it (the pref is only written on
     * tap). */
    public int accentIndex() { return i("accent", -1); }

    // ---- interaction prefs ----
    /** pull-to-refresh on the page (nested-scroll overscroll at top). */
    public boolean pullToRefresh() { return b("pullToRefresh", true); }
    /** v1.3.36 — the address bar docked at the BOTTOM (Opera-style). Off by
     *  default (the v1.3.35 look). Toggling bumps Fx.skinGen so the browser
     *  recreates with the re-laid-out chrome. */
    public boolean urlBarBottom() { return b("urlBarBottom", false); }
    /** v1.3.36 — edge-swipe back/forward navigation. */
    public boolean edgeNav() { return b("edgeNav", true); }
    /** v1.3.36 — light haptic ticks on key interactions (switches, tab
     *  closes, menu picks). performHapticFeedback still respects the
     *  system-wide haptics setting. */
    public boolean haptics() { return b("haptics", true); }
    /** v1.3.37 — swipe the address/nav bar sideways to switch tabs. */
    public boolean toolbarSwipe() { return b("toolbarSwipe", true); }
    /** v1.3.37 — long-press quick action for a toolbar button ("back",
     * "forward", "home", "tabs", "menu", "more", "reload"); see
     * BrowserActivity.QA_* for the codes. */
    public String lpAction(String key, String def) { return s("lp_" + key, def); }

    /** v1.3.37 — custom photo background on the start page. The image lives
     *  at filesDir/wallpaper.jpg (written by the picker); normal profile
     *  only — incognito keeps its neutral look by design. */
    public boolean homeWallpaper() { return b("homeWallpaper", false); }

    // ---- frequently used ----
    public boolean adblockEnabled() { return b("adblock", true); }

    /** v1.3.40 — URL tracker-parameter cleaner (round-4 #4): global master
     *  switch; per-site overrides live in sites.json under "clean". */
    public boolean cleanTrackers() { return b("cleanTrackers", true); }
    public void setAdblock(boolean v) { set("adblock", v); }
    public boolean popupBlock() { return b("popupBlock", true); }
    public boolean blockAdult() { return b("blockAdult", false); }
    public boolean bgPlay() { return b("bgPlay", true); }
    public boolean mediaNotif() { return b("mediaNotif", true); }
    /** v1.3.94 — FOLLOW-THE-SKIN DEFAULT (the field spec: "if the browser
     * is in dark mode, all sites will open by default in dark mode").
     * Unset = the skin's own mode; an explicit toggle (Settings or the
     * per-site chip) still wins over the default. */
    public boolean webDark() { return has("webDark") ? b("webDark", isDark()) : isDark(); }
    public boolean dnt() { return b("dnt", false); }
    public boolean restoreTabs() { return b("restoreTabs", true); }

    // ---- v1.3.31 tabs switcher preferences ----
    /** v1.3.47 FIELD ("remove the different sorting options for tabs, just
     *  keep the manual one"): the sort is GONE — pinned to 0 (manual = list
     *  order) forever, so legacy installs that saved 1/2 collapse to manual
     *  on upgrade with no migration needed. The UI (switcher menu, Settings
     *  > Tabs, settings search) no longer exposes any sort chooser. */
    public int tabsSort() { return 0; }
    /** true = groups pinned to the top of the switcher (v1.3.30 behavior);
     *  false = groups appear in line, at the position of their first member
     *  (file-manager style). */
    public boolean groupsOnTop() { return b("groupsOnTop", true); }
    /** true = 2-column thumbnail cards (v1.3.30 behavior); false = compact
     *  1-column rows. */
    public boolean tabsAsCards() { return b("tabsAsCards", true); }
    public int searchEngine() { return i("searchEngine", 0); }
    /** v1.4.2 — SEARCH-SUGGESTION PRIORITY (the field ask: "when searching,
     *  keep the options from bookmarks and shortcuts as a higher priority
     *  recommendation when typing to search. Make a toggle to turn it on
     *  or off in the deep settings"). true = the omnibox ranks BOOKMARKS
     *  and HOME-SCREEN SHORTCUTS (speed dial, folder entries included)
     *  above history while typing; false = the classic order (history
     *  first, bookmarks after, no shortcut rows). Default ON — the ask
     *  was for the behavior, the toggle is the escape hatch. */
    public boolean suggestLocalFirst() { return b("suggestLocalFirst", true); }
    public String homepage() { return s("homepage", "bolt:home"); }
    public int fontSize() { return i("fontSize", 100); }
    public int uaIndex() { return i("ua", 0); }
    public String profileName() { return s("profileName", ""); }

    // SAF download/offline save directory (persisted tree uri)
    public String downloadDir() { return s("downloadDir", ""); }
    public void setDownloadDir(String uri) { set("downloadDir", uri == null ? "" : uri); }

    // SEPARATE SAF folder for saved offline pages ("saved pages") — kept
    // independent from the downloads folder on purpose (user request): ""
    // means the in-app offline dir, which is what the Saved pages list reads.
    public String savedPagesDir() { return s("savedPagesDir", ""); }
    public void setSavedPagesDir(String uri) { set("savedPagesDir", uri == null ? "" : uri); }

    // media settings (last used)
    public int lastVolume() { return i("mediaVol", 100); }
    public void setLastVolume(int v) { set("mediaVol", v); }
    public float lastSpeed() { return (float) global.optDouble("mediaSpeed", 1.0); }
    public void setLastSpeed(float v) { set("mediaSpeed", (double) v); }
    public boolean vocalOn() { return b("mediaVocal", false); }
    public void setVocalOn(boolean v) { set("mediaVocal", v); }

    /** In-page media booster (volume/vocal EQ/audio chains + playbackRate
     *  enforcement). Turn OFF when a userscript attaches its own audio graph
     *  (e.g. a YouTube booster script) — two graphs on the same <video>
     *  clash and blink. Default ON for stock behaviour. */
    public boolean mediaBooster() { return b("mediaBooster", true); }
    public void setMediaBooster(boolean v) { set("mediaBooster", v); }

    // ---- v1.3.79 accessors ----
    /** THE SILENCE SKIPPER (default off — an opt-in video-control feature).
     *  With it on, the in-page media hook watches each playing element's
     *  audio level through the booster's WebAudio graph and temporarily
     *  raises playbackRate through silent stretches (restoring it the
     *  moment audio returns). */
    public boolean mediaSkip() { return b("mediaSkip", false); }
    public void setMediaSkip(boolean v) { set("mediaSkip", v); }

    // ---- v1.3.87 accessors: the four skip-silence hysteresis knobs ----
    /** SKIP FACTOR, RELATIVE to the user's speed: silence rides at
     *  base x kf (1x base + kf 3 -> 3x; 1.5x base + kf 3 -> 4.5x). The
     *  v1.3.84 hardcoded 4 proved too hot in the field ("relative 4x is a
     *  bit too fast, maybe relative 3x"); default 3, range 1-8. */
    public int skipFactor() { return Math.max(1, Math.min(8, i("skipFactor", 3))); }
    public void setSkipFactor(int v) { set("skipFactor", Math.max(1, Math.min(8, v))); }
    /** START AFTER: ms of continuous quiet before the skip engages
     *  (default 200; 50-2000). Lower = catches silence faster but may
     *  trip on quiet musical passages. */
    public int skipAttack() { return Math.max(50, Math.min(2000, i("skipAttack", 200))); }
    public void setSkipAttack(int v) { set("skipAttack", Math.max(50, Math.min(2000, v))); }
    /** STOP BEFORE: ms rewound when audio returns, so the onset that was
     *  consumed at high speed replays clean (default 150; 0-600). This is
     *  the "deactivation sounds late" cure: the rewind un-fast-forwards
     *  the beginning of the next spoken part. */
    public int skipRelease() { return Math.max(0, Math.min(600, i("skipRelease", 150))); }
    public void setSkipRelease(int v) { set("skipRelease", Math.max(0, Math.min(600, v))); }
    /** IGNORE SILENCES under this many ms of CONTENT time (default 400;
     *  100-3000): a quiet stretch that proves shorter than this when audio
     *  returns has its whole skip UNDONE (playback rewinds to where the
     *  skip began) and the region is banned until playback passes it —
     *  short pauses between words stay natural instead of stuttering. */
    public int skipMinSilence() { return Math.max(100, Math.min(3000, i("skipMinSilence", 400))); }
    public void setSkipMinSilence(int v) { set("skipMinSilence", Math.max(100, Math.min(3000, v))); }

    /** THE SITE-DATA DIET ("Deep clean on close", DEFAULT ON per the user's
     *  storage law: "I need under 20mb"). On close: Local Storage +
     *  IndexedDB + service workers go, cookies are KEPT (most sign-ins
     *  survive; a few token-in-LS sites sign out). */
    public boolean deepCleanExit() { return b("deepCleanExit", true); }
    public void setDeepCleanExit(boolean v) { set("deepCleanExit", v); }

    /** HOME LOGO: 0 = the Bolt mark (default), 1 = blank (no logo, no name
     *  — the clean wallpaper look), 2 = a custom image (filesDir/
     *  home_logo.png, picked from the gallery and downscaled once). */
    public int homeLogo() { return i("homeLogo", 0); }
    public void setHomeLogo(int v) { set("homeLogo", v); }
    /** the custom home logo file (may not exist). */
    public File homeLogoFile() { return new File(dir, "home_logo.png"); }

    /** APP ICON (activity-alias swap): 0 = Bolt Blue (default), 1 = Midnight,
     *  2 = Sunrise, 3 = Forest, 4 = Mono. API 26+ only — the alias icons are
     *  adaptive XML; older devices keep the default launcher PNG. */
    public int appIcon() { return i("appIcon", 0); }
    public void setAppIcon(int v) { set("appIcon", v); }

    // exit checklist
    public boolean clearExit(String what) { return b("exit_" + what, false); }

    // filter lists
    public boolean listEnabled(String key) { return b("list_" + key, true); }
    public void setListEnabled(String key, boolean v) { set("list_" + key, v); }
    public JSONArray customLists() { return customLists; }
    public void saveCustomLists() { set("customLists", customLists); }

    // ---- per-site prefs ----
    private JSONObject site(String domain, boolean create) {
        if (domain == null || domain.isEmpty()) return new JSONObject();
        JSONObject o = sites.optJSONObject(domain);
        if (o == null && create) {
            o = new JSONObject();
            try { sites.put(domain, o); } catch (Throwable ignored) {}
        }
        return o == null ? new JSONObject() : o;
    }

    public boolean siteBool(String domain, String key, boolean def) {
        JSONObject o = site(domain, false);
        return o.optBoolean(key, def);
    }

    public int siteInt(String domain, String key, int def) {
        JSONObject o = site(domain, false);
        return o.optInt(key, def);
    }

    public void setSite(String domain, String key, Object value) {
        if (domain == null || domain.isEmpty()) return;
        try {
            JSONObject o = site(domain, true);
            o.put(key, value);
            sites.put(domain, o);
            scheduleWrite(); // v1.3.79: sites.json rides the coalesced writer
        } catch (Throwable ignored) {}
    }

    /** tri-state: -1 = follow global */
    public int siteAdblock(String domain) {
        JSONObject o = site(domain, false);
        if (!o.has("adblock")) return -1;
        return o.optBoolean("adblock") ? 1 : 0;
    }

    public List<String> siteFilters(String domain) {
        List<String> out = new ArrayList<>();
        JSONArray a = site(domain, false).optJSONArray("filters");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    public void addSiteFilter(String domain, String rule) {
        try {
            JSONObject o = site(domain, true);
            JSONArray a = o.optJSONArray("filters");
            if (a == null) a = new JSONArray();
            a.put(rule);
            o.put("filters", a);
            sites.put(domain, o);
            scheduleWrite(); // v1.3.79: sites.json rides the coalesced writer
        } catch (Throwable ignored) {}
    }

    /** element zaps: CSS selectors always hidden on this site */
    public List<String> siteZaps(String domain) {
        List<String> out = new ArrayList<>();
        JSONArray a = site(domain, false).optJSONArray("zaps");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    public void addZap(String domain, String selector) {
        try {
            JSONObject o = site(domain, true);
            JSONArray a = o.optJSONArray("zaps");
            if (a == null) a = new JSONArray();
            a.put(selector);
            o.put("zaps", a);
            sites.put(domain, o);
            scheduleWrite(); // v1.3.79: sites.json rides the coalesced writer
        } catch (Throwable ignored) {}
    }

    /** v1.3.40 — AUTO-DARK EXCEPTIONS MANAGER (round-4 #12): every domain
     *  carrying a given override key (e.g. "dark"), sorted. */
    public java.util.List<String> siteKeysWith(String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            java.util.Iterator<String> it = sites.keys();
            while (it.hasNext()) {
                String d = it.next();
                JSONObject o = sites.optJSONObject(d);
                if (o != null && o.has(key)) out.add(d);
            }
            java.util.Collections.sort(out);
        } catch (Throwable ignored) {}
        return out;
    }

    /** remove ONE override key from every site (Reset all in the manager);
     *  returns how many sites were touched. Never wipes the whole site
     *  object — adblock/UA/zoom overrides survive a dark reset. */
    public int removeSiteKeyAll(String key) {
        int n = 0;
        try {
            java.util.Iterator<String> it = sites.keys();
            while (it.hasNext()) {
                String d = it.next();
                JSONObject o = sites.optJSONObject(d);
                if (o != null && o.has(key)) {
                    o.remove(key);
                    sites.put(d, o);
                    n++;
                }
            }
            if (n > 0) scheduleWrite(); // v1.3.79
        } catch (Throwable ignored) {}
        return n;
    }

    public void resetSite(String domain) {
        sites.remove(domain);
        scheduleWrite(); // v1.3.79: sites.json rides the coalesced writer
    }

    public List<String> knownDomains() {
        List<String> out = new ArrayList<>();
        Iterator<String> it = sites.keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    // ---- bookmarks (with folders; folder "" = root) ----
    public JSONArray bookmarks() { return bookmarks; }

    public void addBookmark(String title, String url) { addBookmark(title, url, ""); }

    public void addBookmark(String title, String url, String folder) {
        if (url == null || url.isEmpty()) return;
        try {
            for (int i = 0; i < bookmarks.length(); i++) {
                if (bookmarks.getJSONObject(i).optString("url").equals(url)) return;
            }
            JSONObject o = new JSONObject();
            o.put("title", title == null ? url : title);
            o.put("url", url);
            o.put("folder", folder == null ? "" : folder);
            o.put("time", System.currentTimeMillis());
            bookmarks.put(o);
            saveBookmarks();
        } catch (Throwable ignored) {}
    }

    public void removeBookmark(int idx) {
        bookmarks.remove(idx);
        saveBookmarks();
    }

    /** v1.4.6 — the undo path for a bookmark delete (the user's ask: an
     *  undo for "deleting anything"): re-insert the captured row at the
     *  SAME index (clamped — the list may have changed inside the window;
     *  the insertSpeeddial bubble, JSONArray has no insert). */
    public void insertBookmark(int idx, JSONObject o) {
        if (o == null) return;
        try {
            int at = Math.max(0, Math.min(idx, bookmarks.length()));
            bookmarks.put(o);
            for (int i = bookmarks.length() - 1; i > at; i--) {
                JSONObject b = bookmarks.optJSONObject(i);
                bookmarks.put(i, bookmarks.optJSONObject(i - 1));
                if (b != null) bookmarks.put(i - 1, b);
            }
            saveBookmarks();
        } catch (Throwable ignored) {}
    }

    public void removeBookmark(String url) {
        for (int i = bookmarks.length() - 1; i >= 0; i--) {
            JSONObject o = bookmarks.optJSONObject(i);
            if (o != null && o.optString("url").equals(url)) bookmarks.remove(i);
        }
        saveBookmarks();
    }

    public void moveBookmark(String url, String folder) {
        for (int i = 0; i < bookmarks.length(); i++) {
            JSONObject o = bookmarks.optJSONObject(i);
            if (o != null && o.optString("url").equals(url)) {
                try { o.put("folder", folder == null ? "" : folder); } catch (Throwable ignored) {}
            }
        }
        saveBookmarks();
    }

    public void renameBookmark(String url, String newTitle) {
        for (int i = 0; i < bookmarks.length(); i++) {
            JSONObject o = bookmarks.optJSONObject(i);
            if (o != null && o.optString("url").equals(url)) {
                try { o.put("title", newTitle); } catch (Throwable ignored) {}
            }
        }
        saveBookmarks();
    }

    public List<String> bookmarkFolders() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < bookmarks.length(); i++) {
            JSONObject o = bookmarks.optJSONObject(i);
            if (o == null) continue;
            String f = o.optString("folder", "");
            if (!f.isEmpty() && !out.contains(f)) out.add(f);
        }
        return out;
    }

    public int bookmarkCountIn(String folder) {
        int n = 0;
        for (int i = 0; i < bookmarks.length(); i++) {
            JSONObject o = bookmarks.optJSONObject(i);
            if (o == null) continue;
            String f = o.optString("folder", "");
            if (folder.equals(f)) n++;
        }
        return n;
    }

    public void renameFolder(String oldName, String newName) {
        for (int i = 0; i < bookmarks.length(); i++) {
            JSONObject o = bookmarks.optJSONObject(i);
            if (o != null && oldName.equals(o.optString("folder", ""))) {
                try { o.put("folder", newName); } catch (Throwable ignored) {}
            }
        }
        saveBookmarks();
    }

    public void deleteFolder(String name) {
        for (int i = 0; i < bookmarks.length(); i++) {
            JSONObject o = bookmarks.optJSONObject(i);
            if (o != null && name.equals(o.optString("folder", ""))) {
                try { o.put("folder", ""); } catch (Throwable ignored) {}
            }
        }
        saveBookmarks();
    }

    public void saveBookmarks() { scheduleWrite(); } // v1.3.79

    public boolean isBookmarked(String url) {
        for (int i = 0; i < bookmarks.length(); i++) {
            if (bookmarks.optJSONObject(i).optString("url").equals(url)) return true;
        }
        return false;
    }

    // ---- history ----
    public JSONArray history() { return history; }

    public void addHistory(String title, String url) {
        if (url == null || url.isEmpty() || url.startsWith("bolt:")) return;
        try {
            if (history.length() > 0 && history.getJSONObject(history.length() - 1).optString("url").equals(url)) {
                history.getJSONObject(history.length() - 1).put("time", System.currentTimeMillis());
                return;
            }
            JSONObject o = new JSONObject();
            o.put("title", title == null ? url : title);
            o.put("url", url);
            o.put("time", System.currentTimeMillis());
            synchronized (WRITE_LOCK) {
                history.put(o);
                while (history.length() > 2000) history.remove(0);
            }
            scheduleWrite();
        } catch (Throwable ignored) {}
    }

    public void clearHistory() {
        history = new JSONArray();
        saveHistory();
    }

    /** v1.3.79: schedules the coalesced write — callers live on the main
     *  thread and the 2000-entry serialize belongs on the writer. */
    public void saveHistory() { scheduleWrite(); }

    // ---- tab groups ----
    public JSONArray groups() { return groups; }

    public JSONObject addGroup(String name, int colorIdx) {
        try {
            JSONObject g = new JSONObject();
            g.put("id", System.currentTimeMillis());
            g.put("name", name);
            g.put("color", colorIdx);
            groups.put(g);
            scheduleWrite(); // v1.3.79 groups
            return g;
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    public void renameGroup(long id, String name) {
        for (int i = 0; i < groups.length(); i++) {
            if (groups.optJSONObject(i) != null && groups.optJSONObject(i).optLong("id") == id) {
                try { groups.getJSONObject(i).put("name", name); } catch (Throwable ignored) {}
            }
        }
        scheduleWrite(); // v1.3.79 groups
    }

    public void setGroupColor(long id, int c) {
        for (int i = 0; i < groups.length(); i++) {
            if (groups.optJSONObject(i) != null && groups.optJSONObject(i).optLong("id") == id) {
                try { groups.getJSONObject(i).put("color", c); } catch (Throwable ignored) {}
            }
        }
        scheduleWrite(); // v1.3.79 groups
    }

    /** collapsed state of a tab group in the tab switcher (persisted) */
    public void setGroupCollapsed(long id, boolean collapsed) {
        for (int i = 0; i < groups.length(); i++) {
            if (groups.optJSONObject(i) != null && groups.optJSONObject(i).optLong("id") == id) {
                try { groups.getJSONObject(i).put("collapsed", collapsed); } catch (Throwable ignored) {}
            }
        }
        scheduleWrite(); // v1.3.79 groups
    }

    /** v1.4.7 — an EMPTY tab group's rendered row index in the follow-order
     *  placement (its own position memory; membered folders derive from
     *  tm.tabs). -1 clears a stale slot (a folder that gained members).
     *  v1.4.8 — RETIRED by the anchor law (an absolute index is not a
     *  memory: the next insert or close anywhere above it silently shifts
     *  every row under it — the "settles one position off / pushes the
     *  incoming tab one off" corpse); kept only so legacy writes clear
     *  cleanly. See setGroupAnchor. */
    public void setGroupSlot(long id, int slot) {
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && g.optLong("id") == id) {
                try {
                    if (slot >= 0) g.put("slot", slot);
                    else g.remove("slot");
                } catch (Throwable ignored) {}
            }
        }
        scheduleWrite(); // v1.4.7 groups
    }

    /** v1.4.8 — THE ANCHOR LAW: an EMPTY tab group's follow-order position
     *  is the PAIR OF ROWS it sits between, not a row index. Keys:
     *  "t<tid>" a tab row (a membered tab's key means its FOLDER row),
     *  "g<gid>" a folder row; the empty string = no row on that side (the
     *  list's edge). Written by the empty's OWN drop, by the placement
     *  materialize, by the last-member capture, and by the anchor refresh
     *  that runs when a referenced row closes, joins or moves away. Both
     *  sides empty ("","") = pinned to the very top. */
    public void setGroupAnchor(long id, String prevKey, String nextKey) {
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && g.optLong("id") == id) {
                try {
                    g.put("ap", prevKey == null ? "" : prevKey);
                    g.put("an", nextKey == null ? "" : nextKey);
                    g.remove("slot");   // the v1.4.7 absolute index is retired
                } catch (Throwable ignored) {}
            }
        }
        scheduleWrite(); // v1.4.8 groups
    }

    /** v1.4.8 — clear every position memory of a group (it gained members:
     *  its row derives from the first member now, and the next time it
     *  empties the last-member capture rewrites the anchors). */
    public void clearGroupPos(long id) {
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && g.optLong("id") == id) {
                try {
                    g.remove("slot");
                    g.remove("ap");
                    g.remove("an");
                } catch (Throwable ignored) {}
            }
        }
        scheduleWrite(); // v1.4.8 groups
    }

    public void removeGroup(long id) {
        for (int i = groups.length() - 1; i >= 0; i--) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && g.optLong("id") == id) groups.remove(i);
        }
        scheduleWrite(); // v1.3.79 groups
    }

    /** v1.4.5 — DEDUPE duplicate-id folder rows (the "a tab group
     *  disappears when changing modes" corpse): the on-top placement lists
     *  EVERY array row, but the follow-order walk emits the FIRST id match
     *  and skips later duplicates (the emitted-set is keyed by id) — a
     *  duplicate-id folder renders on-top and VANISHES in follow-order.
     *  IDs come from currentTimeMillis (addGroup), so a same-millisecond
     *  pair is all it takes. Keep the FIRST occurrence of each id, drop
     *  the rest (the members all point at the shared id — nothing is
     *  lost). Returns how many rows were dropped. */
    public int dedupeGroups() {
        try {
            java.util.HashSet<Long> seen = new java.util.HashSet<>();
            int dropped = 0;
            for (int i = groups.length() - 1; i >= 0; i--) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) continue;
                long id = g.optLong("id");
                if (id != 0 && !seen.add(id)) {
                    groups.remove(i);
                    dropped++;
                }
            }
            if (dropped > 0) scheduleWrite();
            return dropped;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** v1.4.0 — the groups-array index of a group id (-1 = absent). */
    public int indexOfGroup(long id) {
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && g.optLong("id") == id) return i;
        }
        return -1;
    }

    /** v1.3.40 — groups can be REORDERED (menu Move up/down, and since v1.4.0
     *  the drag). v1.4.0 CORPSE FIX (the "when I move them with the move from
     *  menu, it disappears" report): the old body did remove(idx) then
     *  groups.put(to, g) — but Android's JSONArray.put(int, Object) is a
     *  REPLACE (verified against AOSP libcore android-8→14: pad-with-nulls
     *  then values.set), NEVER an insert — so every move OVERWROTE a
     *  neighbour group: [G1,G2] "move G2 up" produced [G2] (G1 gone), and
     *  the group's tabs stayed in tm.tabs with a dead groupId — invisible
     *  in the switcher (grouped tabs render only under their group row)
     *  yet still counted by the toolbar ("the folder count did not go
     *  down"). The rebuild idiom below can never lose an entry. */
    public boolean moveGroup(long id, int delta) {
        int idx = indexOfGroup(id);
        if (idx < 0) return false;
        return moveGroupTo(id, idx + delta);
    }

    /** v1.4.0 — move group [id] to groups-array position [toIdx] (the
     *  dragged-folder drop calls this with the resolved slot). INSERT
     *  semantics, provably lossless: rebuild the order in a java List
     *  (List.add(int,E) is a true insert), then rewrite the SAME JSONArray
     *  instance in place (holders of prefs.groups() keep their reference). */
    public boolean moveGroupTo(long id, int toIdx) {
        int idx = indexOfGroup(id);
        int n = groups.length();
        if (idx < 0 || toIdx < 0 || toIdx >= n || idx == toIdx) return false;
        try {
            JSONObject g = groups.optJSONObject(idx);
            if (g == null) return false;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Object v = groups.opt(i);
                if (i == idx || v == null) continue;
                list.add(v);
            }
            list.add(toIdx, g);              // the true insert
            while (groups.length() > 0) groups.remove(0);
            for (Object v : list) groups.put(v);
            scheduleWrite();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.4.0 — land group [draggedId] immediately BEFORE/AFTER group
     *  [targetId] (the dragged-folder drop). The final index is computed
     *  on the list WITHOUT the dragged group, so it is correct whichever
     *  side the dragged group comes from. */
    public boolean moveGroupRelative(long draggedId, long targetId, boolean after) {
        int si = indexOfGroup(draggedId), gi = indexOfGroup(targetId);
        if (si < 0 || gi < 0 || si == gi) return false;
        int giW = gi > si ? gi - 1 : gi;    // target's index without the dragged
        return moveGroupTo(draggedId, after ? giW + 1 : giW);
    }

    /** v1.4.1 — rewrite the groups array to EXACTLY [order] (every live
     *  group must appear exactly once; anything else is a no-op refusal —
     *  a reorder can never drop or clone a folder). The same lossless
     *  rebuild idiom as moveGroupTo: true-insert into a java List, then
     *  rewrite the SAME JSONArray instance in place. v1.4.3: keeps the
     *  array in first-member order (TabManager.moveGroupRelative calls it
     *  after every group move) so every reader of the array agrees with
     *  the ONE ordering memory (tm.tabs). */
    public boolean reorderGroups(java.util.List<JSONObject> order) {
        try {
            if (order == null || order.size() != groups.length()) return false;
            java.util.HashSet<Long> want = new java.util.HashSet<>();
            for (JSONObject g : order) {
                long id = g.optLong("id");
                if (id == 0 || !want.add(id)) return false;  // dup/invalid
            }
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null || !want.contains(g.optLong("id"))) return false;
            }
            while (groups.length() > 0) groups.remove(0);
            for (JSONObject g : order) groups.put(g);
            scheduleWrite();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- speed dial (entries: {title,url}; groups: {group:true,name,items:[{title,url}]}) ----
    public JSONArray speeddial() { return speeddial; }

    public JSONObject speeddialAt(int idx) {
        return idx >= 0 && idx < speeddial.length() ? speeddial.optJSONObject(idx) : null;
    }

    public boolean isSpeeddialGroup(int idx) {
        JSONObject o = speeddialAt(idx);
        return o != null && o.optBoolean("group", false);
    }

    /** add {title,url} at top level */
    public void addSpeeddial(String title, String url) {
        try {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("url", url);
            speeddial.put(o);
            saveSpeeddial();
        } catch (Throwable ignored) {}
    }

    /** v1.4.7 — ADD A SHORTCUT DIRECTLY INTO A FOLDER (the "implement the
     *  adding shortcuts directly to folders while adding in that website"
     *  report): append {title,url} to group [groupIdx]'s items. Fails
     *  cleanly (returns false) when the index is not a live group — callers
     *  fall back to the top level. Appending via put(Object) is safe (the
     *  AOSP put(int,·) REPLACE trap only bites positional writes). */
    public boolean addSpeeddialItemTo(int groupIdx, String title, String url) {
        try {
            JSONObject g = speeddialAt(groupIdx);
            if (g == null || !g.optBoolean("group", false)) return false;
            JSONArray items = g.optJSONArray("items");
            if (items == null) items = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("url", url);
            items.put(o);
            g.put("items", items);
            saveSpeeddial();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void renameSpeeddial(int idx, String title) {
        JSONObject o = speeddialAt(idx);
        if (o == null) return;
        try { o.put("title", title); } catch (Throwable ignored) {}
        if (o.optBoolean("group", false)) {
            try { o.put("name", title); } catch (Throwable ignored) {}
        }
        saveSpeeddial();
    }

    /** v1.3.79 — REORDER: move entry fromIdx so it lands at insertion point
     *  toIdx (the "dragged smoothly to be placed and repositioned" ask).
     *  toIdx is the target position in the CURRENT list; after removal the
     *  index shifts down by one when moving forward, which this accounts
     *  for. Groups move as one tile. */
    public void moveSpeeddial(int fromIdx, int toIdx) {
        int n = speeddial.length();
        if (fromIdx < 0 || fromIdx >= n || toIdx < 0 || toIdx > n || fromIdx == toIdx) return;
        try {
            Object o = speeddial.opt(fromIdx);
            if (o == null) return;
            // v1.4.0 CORPSE FIX (the corpse under the v1.3.89/90 "dragging
            // the shortcuts is STILL DELETING some of the icons" reports):
            // the old body did remove(fromIdx) + speeddial.put(insert, o) —
            // Android's JSONArray.put(int, Object) REPLACES (AOSP libcore:
            // pad-then-set), never inserts — so every drop whose insertion
            // point sat INSIDE the remaining list (all backward drags:
            // "D before B" in A,B,C,D left [A,D,C] with B gone; most middle
            // drags too) silently ATE the neighbour icon. The gesture layer
            // was rebuilt three times (v1.3.90 drop=reorder, v1.3.91
            // release-gate, v1.3.92 A-B-C-D law) while this data-layer
            // corpse survived them all. The rebuild idiom (a java List's
            // TRUE insert, then an in-place rewrite of the same JSONArray)
            // can never lose an entry.
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(n - 1);
            for (int i = 0; i < n; i++) {
                if (i == fromIdx) continue;
                Object v = speeddial.opt(i);
                if (v != null) list.add(v);
            }
            // toIdx is an INSERTION POINT in the original list; in the list
            // without the item that is toIdx when moving backward, toIdx-1
            // when moving forward (the moveSpeeddial convention, unchanged)
            int insert = toIdx > fromIdx ? toIdx - 1 : toIdx;
            insert = Math.max(0, Math.min(insert, list.size()));
            list.add(insert, o);            // the true insert
            while (speeddial.length() > 0) speeddial.remove(0);
            for (Object v : list) speeddial.put(v);
            saveSpeeddial();
        } catch (Throwable ignored) {}
    }

    /** v1.3.79 — NEW FOLDER: an empty group tile the user can drag others
     *  into (named on creation; rename works like any tile). */
    public int addSpeeddialFolder(String name) {
        try {
            JSONObject g = new JSONObject();
            g.put("group", true);
            g.put("name", name == null || name.trim().isEmpty() ? "Folder" : name.trim());
            g.put("items", new JSONArray());
            speeddial.put(g);
            saveSpeeddial();
            return speeddial.length() - 1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** v1.3.79 — EDIT: title AND url for a plain shortcut. */
    public void editSpeeddial(int idx, String title, String url) {
        JSONObject o = speeddialAt(idx);
        if (o == null || o.optBoolean("group", false)) return;
        try {
            if (title != null) o.put("title", title);
            if (url != null && url.trim().startsWith("http")) o.put("url", url.trim());
            saveSpeeddial();
        } catch (Throwable ignored) {}
    }

    /** v1.4.9 — edit an item INSIDE a home folder (name and/or address) —
     *  the folder-window ⋯ menu's Edit shortcut row (editSpeeddial only
     *  reaches top-level entries; an in-folder item is a nested object). */
    public void editSpeeddialItemIn(int groupIdx, int itemIdx, String title, String url) {
        try {
            JSONObject g = speeddialAt(groupIdx);
            if (g == null || !g.optBoolean("group", false)) return;
            JSONArray items = g.optJSONArray("items");
            JSONObject it = items == null || itemIdx < 0 || itemIdx >= items.length()
                    ? null : items.optJSONObject(itemIdx);
            if (it == null) return;
            if (title != null) it.put("title", title);
            if (url != null && url.trim().startsWith("http")) it.put("url", url.trim());
            saveSpeeddial();
        } catch (Throwable ignored) {}
    }

    /** merge entry srcIdx into entry dstIdx (creates or joins a group) */
    public void mergeSpeeddial(int srcIdx, int dstIdx) {
        if (srcIdx == dstIdx) return;
        JSONObject src = speeddialAt(srcIdx);
        JSONObject dst = speeddialAt(dstIdx);
        if (src == null || dst == null) return;
        try {
            JSONArray items;
            if (dst.optBoolean("group", false)) {
                items = dst.optJSONArray("items");
                if (items == null) items = new JSONArray();
            } else {
                items = new JSONArray();
                JSONObject d2 = new JSONObject();
                d2.put("title", dst.optString("title"));
                d2.put("url", dst.optString("url"));
                items.put(d2);
                dst.put("group", true);
                dst.put("name", dst.optString("title"));
                dst.remove("url");
            }
            if (src.optBoolean("group", false)) {
                JSONArray sub = src.optJSONArray("items");
                if (sub != null) for (int i = 0; i < sub.length(); i++) items.put(sub.get(i));
            } else {
                JSONObject s2 = new JSONObject();
                s2.put("title", src.optString("title"));
                s2.put("url", src.optString("url"));
                items.put(s2);
            }
            dst.put("items", items);
            speeddial.remove(srcIdx);
            saveSpeeddial();
        } catch (Throwable ignored) {}
    }

    /** v1.4.4 — REORDER AN ITEM INSIDE A GROUP (the report: "make the home
     *  page shortcuts icons in the groups to be draggable to rearrange
     *  too"). [insertAt] uses the moveSpeeddial convention: the index in
     *  the list WITHOUT the dragged item (0..n-1) — the same law the home
     *  grid's drag rides, so the drop math is identical everywhere. True
     *  move via the java-List rebuild idiom (never JSONArray.put(int,·) —
     *  the AOSP put REPLACES, lesson from moveGroup's corpse). */
    public boolean moveSpeeddialItem(int groupIdx, int fromItemIdx, int insertAt) {
        try {
            JSONObject g = speeddialAt(groupIdx);
            if (g == null || !g.optBoolean("group", false)) return false;
            JSONArray items = g.optJSONArray("items");
            int n = items == null ? 0 : items.length();
            if (n < 2 || fromItemIdx < 0 || fromItemIdx >= n
                    || insertAt < 0 || insertAt > n - 1) return false;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (i == fromItemIdx) continue;
                list.add(items.get(i));
            }
            list.add(Math.min(insertAt, list.size()), items.get(fromItemIdx));
            JSONArray out = new JSONArray();
            for (Object v : list) out.put(v);
            g.put("items", out);
            saveSpeeddial();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** move an item out of a group back to the top level */
    public void ejectSpeeddialItem(int groupIdx, int itemIdx) {
        JSONObject g = speeddialAt(groupIdx);
        if (g == null || !g.optBoolean("group", false)) return;
        JSONArray items = g.optJSONArray("items");
        if (items == null || itemIdx < 0 || itemIdx >= items.length()) return;
        try {
            speeddial.put(items.get(itemIdx));
            items.remove(itemIdx);
            if (items.length() == 1) {
                // single leftover item: dissolve back into a plain tile
                JSONObject only = items.optJSONObject(0);
                if (only != null) {
                    g.remove("group");
                    g.remove("items");
                    g.remove("name");
                    g.put("title", only.optString("title"));
                    g.put("url", only.optString("url"));
                }
            } else {
                g.put("items", items);
            }
            saveSpeeddial();
        } catch (Throwable ignored) {}
    }

    public void removeSpeeddialItem(int groupIdx, int itemIdx) {
        JSONObject g = speeddialAt(groupIdx);
        if (g == null || !g.optBoolean("group", false)) return;
        JSONArray items = g.optJSONArray("items");
        if (items == null || itemIdx < 0 || itemIdx >= items.length()) return;
        try {
            items.remove(itemIdx);
            if (items.length() == 0) {
                speeddial.remove(groupIdx);
            } else if (items.length() == 1) {
                JSONObject only = items.optJSONObject(0);
                if (only != null) {
                    g.remove("group");
                    g.remove("items");
                    g.remove("name");
                    g.put("title", only.optString("title"));
                    g.put("url", only.optString("url"));
                }
            } else {
                g.put("items", items);
            }
            saveSpeeddial();
        } catch (Throwable ignored) {}
    }

    /** unfold a group: all items go back to the top level */
    public void dissolveSpeeddialGroup(int groupIdx) {
        JSONObject g = speeddialAt(groupIdx);
        if (g == null || !g.optBoolean("group", false)) return;
        JSONArray items = g.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                try { speeddial.put(items.get(i)); } catch (Throwable ignored) {}
            }
        }
        speeddial.remove(groupIdx);
        saveSpeeddial();
    }

    public void saveSpeeddial() { scheduleWrite(); } // v1.3.79

    /** v1.3.91 — UNDO INSERT: put a captured entry back at a given index
     *  (the delete-undo snackbar's restore path; clamped to the list). */
    public void insertSpeeddial(int idx, JSONObject o) {
        if (o == null) return;
        try {
            int at = Math.max(0, Math.min(idx, speeddial.length()));
            speeddial.put(o);                    // append at the end…
            for (int i = speeddial.length() - 1; i > at; i--) {
                // …then bubble it left to the requested slot (JSONArray has
                // no insert; the bubble is the cheapest correct move)
                JSONObject b = speeddial.optJSONObject(i);
                speeddial.put(i, speeddial.optJSONObject(i - 1));
                if (b != null) speeddial.put(i - 1, b);
            }
            saveSpeeddial();
        } catch (Throwable ignored) {}
    }

    public void removeSpeeddial(int idx) {
        speeddial.remove(idx);
        saveSpeeddial();
    }

    /** seed defaults on first run or from most-visited history */
    public void ensureSpeeddial() {
        if (speeddial.length() > 0) return;
        if (history.length() == 0) {
            try {
                String[][] defaults = {
                        {"Google", "https://www.google.com"},
                        {"YouTube", "https://www.youtube.com"},
                        {"Wikipedia", "https://www.wikipedia.org"},
                        {"GitHub", "https://github.com"},
                        {"Reddit", "https://www.reddit.com"},
                        {"X", "https://x.com"},
                        {"Facebook", "https://www.facebook.com"},
                        {"Amazon", "https://www.amazon.com"}};
                for (String[] d : defaults) {
                    JSONObject o = new JSONObject();
                    o.put("title", d[0]);
                    o.put("url", d[1]);
                    speeddial.put(o);
                }
                saveSpeeddial();
                return;
            } catch (Throwable ignored) {}
        }
        try {
            // seed from most visited history hosts
            java.util.LinkedHashMap<String, JSONObject> top = new java.util.LinkedHashMap<>();
            for (int i = history.length() - 1; i >= 0 && top.size() < 8; i--) {
                JSONObject h = history.optJSONObject(i);
                if (h == null) continue;
                String u = h.optString("url");
                String host = Fx.hostOf(u);
                if (host.isEmpty() || top.containsKey(host)) continue;
                JSONObject o = new JSONObject();
                o.put("title", host);
                o.put("url", u);
                top.put(host, o);
            }
            for (JSONObject o : top.values()) speeddial.put(o);
            saveSpeeddial();
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- v1.3.31 authenticator

    /**
     * Bolt's built-in authenticator entries (filesDir/authenticator.json).
     * Entry shape: {id, issuer, account, secret(normalized base32),
     * algorithm(SHA1|SHA256|SHA512), digits(6-8), period(s), addedAt}.
     * Plain JSON in the app-private dir — the same protection Google
     * Authenticator gives secrets on a non-rooted device (rooted/backup
     * extraction is out of scope; the file is excluded from backups by
     * allowBackup=false). MAIN process only (incognito has its own dir and
     * deliberately no identity data).
     */
    public JSONArray authEntries() {
        return Fx.readJsonArray(file("authenticator.json"));
    }

    /** add an entry; returns the stored JSONObject (empty on failure). */
    public JSONObject addAuth(Totp.OtpSecret o) {
        try {
            JSONArray a = authEntries();
            JSONObject e = new JSONObject();
            e.put("id", System.currentTimeMillis());
            e.put("issuer", o.issuer == null ? "" : o.issuer);
            e.put("account", o.account == null ? "" : o.account);
            e.put("secret", o.secret);
            e.put("algorithm", o.algorithm);
            e.put("digits", o.digits);
            e.put("period", o.period);
            e.put("addedAt", System.currentTimeMillis());
            a.put(e);
            Fx.writeJsonArray(file("authenticator.json"), a);
            return e;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public boolean removeAuth(long id) {
        try {
            JSONArray a = authEntries();
            JSONArray out = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && e.optLong("id") == id) { removed = true; continue; }
                out.put(e == null ? new JSONObject() : e);
            }
            if (removed) Fx.writeJsonArray(file("authenticator.json"), out);
            return removed;
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.3.32 — does an entry with this exact secret already exist?
     *  Import dedupe key: the secret IS the identity (same key ⇒ same code
     *  generator, whatever the label says). */
    public boolean hasAuthSecret(String normalizedSecret) {
        try {
            if (normalizedSecret == null || normalizedSecret.isEmpty()) return false;
            JSONArray a = authEntries();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && normalizedSecret.equals(e.optString("secret", ""))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** v1.3.32 — batch add (Google Authenticator import). Entries whose
     *  secret already exists are SKIPPED (not duplicated) — returns the
     *  number actually added. */
    public int addAuthList(java.util.List<Totp.OtpSecret> list) {
        int added = 0;
        if (list == null) return 0;
        for (Totp.OtpSecret o : list) {
            if (o == null || o.secret == null || o.secret.isEmpty()) continue;
            if (hasAuthSecret(o.secret)) continue;
            JSONObject e = addAuth(o);
            if (e.length() > 0) added++;
        }
        return added;
    }

    /** the entry whose issuer best matches the given host ("" when none):
     *  exact base-domain match first (accounts.google.com -> "Google" is
     *  special-cased), then a contains-match, then null. Used by the
     *  page-fill chip to pick WHICH code to offer. */
    public JSONObject authMatchForHost(String host) {
        try {
            JSONArray a = authEntries();
            if (a.length() == 0) return null;
            String h = host == null ? "" : host.toLowerCase();
            String base = Fx.baseDomain(h);
            // well-known issuer aliases (host fragment -> issuer name)
            String[] aliases = {"google", "github", "facebook", "amazon", "microsoft", "dropbox", "twitter", "x.com"};
            JSONObject fallback = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                String issuer = e.optString("issuer", "").toLowerCase();
                if (issuer.isEmpty()) continue;
                if (!base.isEmpty() && (base.contains(issuer) || issuer.contains(base))) return e;
                for (String al : aliases) {
                    if (h.contains(al) && (issuer.contains(al) || al.contains(issuer))) {
                        if (fallback == null) fallback = e;
                    }
                }
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        }
    }

    /** first entry (used when nothing matches but codes exist). */
    public JSONObject firstAuthEntry() {
        try {
            JSONArray a = authEntries();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && !e.optString("secret", "").isEmpty()) return e;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
