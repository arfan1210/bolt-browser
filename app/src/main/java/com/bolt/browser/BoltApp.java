package com.bolt.browser;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.FileReader;

public class BoltApp extends Application {
    public static final String CH_MEDIA = "media";
    public static final String CH_DL = "downloads";
    /** v1.3.28 — recovery notices from the alarm watchdog (one heads-up when
     *  the OS/OEM killer stops Bolt while playing; silent afterwards). */
    public static final String CH_ALERT = "playback_alerts";

    // v1.3.15: app-visibility tracking for the media service. Counting STARTED
    // activities (not resumed ones) means full-screen in-app screens (Settings,
    // the tab switcher) do NOT read as "backgrounded" — only the last activity
    // really stopping does. The engage signal is debounced 700 ms as a backstop
    // (v1.3.16: the primary focus take is now immediate — see
    // MediaControlService.markBackgrounded) so a fast activity recreate can
    // never take background audio focus spuriously.
    // Static + volatile so the media service can read app visibility without
    // holding an Application reference (v1.3.16: a service created while the
    // app is already backgrounded — zombie restart — initializes its own
    // background state from here).
    private static volatile int startedActivities = 0;
    /** v1.3.24: the resumed activity of this process — the passkey
     *  authenticator's consent dialog needs a live window token; WebViews
     *  survive activity recreation, so the context they were built with is
     *  NOT a reliable dialog owner (this tracker always is). */
    private static volatile android.app.Activity resumedActivity = null;
    private final Handler appH = new Handler(Looper.getMainLooper());
    private final Runnable bgEngage = () ->
            com.bolt.browser.media.MediaControlService.onAppBackground();

    /** v1.3.16: true while at least one activity of THIS process is started. */
    public static boolean isAppForeground() { return startedActivities > 0; }

    /** v1.3.24: the currently resumed activity (null while backgrounded /
     *  in the incognito process — which has no callbacks and no passkeys). */
    public static android.app.Activity currentActivity() {
        android.app.Activity a = resumedActivity;
        if (a == null || a.isFinishing()) return null;
        if (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed()) return null;
        return a;
    }

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        // v1.3.94 — THE STAGED-IMPORT APPLY. Runs before ContentProviders,
        // before onCreate, before ANY file-backed singleton (Prefs,
        // TabManager, the speed-dial store) constructs — so the imported
        // files are on disk BEFORE anything loads them, and no stale
        // in-memory state can clobber them (the v1.3.93 import wrote
        // straight into filesDir under the live process and lost).
        try { applyStagedImport(); } catch (Throwable ignored) {}
    }

    /** v1.3.94 — apply filesDir/.bolt-import/ into filesDir, then remove
     * the tree. Idempotent: a crash mid-apply leaves the remaining files
     * staged and the next start finishes the job (each file overwrites). */
    private void applyStagedImport() {
        // main process only — the :incognito process shares filesDir and a
        // parallel apply would race the moves (and the import UI never
        // runs there anyway)
        if (processName().endsWith(":incognito")) return;
        java.io.File base = getFilesDir();
        java.io.File staging = new java.io.File(base, ".bolt-import");
        java.io.File[] top = staging.listFiles();
        if (top == null || top.length == 0) return;
        int n = moveTree(staging, base, 0);
        deleteTree(staging);
        try {
            com.bolt.browser.util.Diag.logSync(this, "import applied " + n + " files");
        } catch (Throwable ignored) {}
    }

    private static int moveTree(java.io.File srcDir, java.io.File dstDir, int n) {
        java.io.File[] fs = srcDir.listFiles();
        if (fs == null) return n;
        for (java.io.File f : fs) {
            if (f.isDirectory()) {
                n = moveTree(f, new java.io.File(dstDir, f.getName()), n);
                try { f.delete(); } catch (Throwable ignored) {}
            } else {
                try {
                    java.io.File dst = new java.io.File(dstDir, f.getName());
                    dst.getParentFile().mkdirs();
                    if (dst.isDirectory()) continue;   // never trade a dir for a file
                    java.nio.file.Files.move(f.toPath(), dst.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    n++;
                } catch (Throwable ignored) {}
            }
        }
        return n;
    }

    private static void deleteTree(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] ch = f.listFiles();
        if (ch != null) for (java.io.File c : ch) deleteTree(c);
        try { f.delete(); } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        String pn = processName();
        // v1.3.20: persist every uncaught exception BEFORE the process dies —
        // field crash reports without a logcat capture were pure guesswork
        // through v1.3.19. The log is copied out via Bolt.copyDiag() from the
        // crash-notice page (or any page: Bolt bridge is registered on every
        // WebView). Chained to the previous handler so system crash handling
        // (dialog, process death) proceeds exactly as before.
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                com.bolt.browser.util.Diag.logSync(BoltApp.this,
                        "UNCAUGHT " + (t == null ? "?" : t.getName()) + ": " + e);
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                com.bolt.browser.util.Diag.logSync(BoltApp.this, sw.toString());
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
        try {
            String vn = "";
            try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Throwable ignored) {}
            // v1.3.21: the two facts every crash report was missing — which
            // Android System WebView build is on the device (renderer crashes
            // and the WebAuthn feature gate are both WebView-version facts)
            // and whether the WebAuthn/passkey engine is available at all.
            String wv = "";
            try {
                android.content.pm.PackageInfo wp =
                        androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this);
                if (wp != null && wp.versionName != null) wv = " webview=" + wp.versionName;
            } catch (Throwable ignored) {}
            String wa = "";
            try {
                wa = " webauthn=" + (com.bolt.browser.util.WebAuthnHelper
                        .isPasskeySupportAvailable() ? "supported" : "UNSUPPORTED");
            } catch (Throwable ignored) {}
            com.bolt.browser.util.Diag.logSync(this,
                    "app start v" + vn
                            + " proc=" + pn
                            + " os=" + android.os.Build.VERSION.RELEASE
                            + "/sdk" + android.os.Build.VERSION.SDK_INT
                            + " dev=" + android.os.Build.MODEL
                            + wv + wa);
        } catch (Throwable ignored) {}
        if (pn.endsWith(":incognito")) {
            // True separate-process incognito: own WebView data dir (cookies, cache, storage)
            try {
                WebView.setDataDirectorySuffix("boltinc");
            } catch (Throwable ignored) {}
        }
        // userscript engine: capture the app context early so GM storage reads
        // (gmValues/wrap) work before the first GM_xmlhttpRequest
        com.bolt.browser.scripts.UserscriptEngine.init(this);
        // v1.3.48 — NIGHT SCREEN EVERYWHERE: one amber veil per activity
        // window (the v1.3.39 filter only tinted the browser's fl_root —
        // the "not in the tabs, not in the settings" field report).
        // Registered OUTSIDE the process check: the incognito process keeps
        // its own night prefs and gets the same mechanics. Recolors on every
        // resume so pref changes + the schedule re-evaluate immediately.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            // v1.3.51 — INSTANT RE-SKIN (field: "make all changes appear
            // instant — appearance, workspace etc. All."): per-activity
            // theme stamps. A skin/mode/accent change made anywhere (the
            // Appearance picker, the quick panel's theme chip) re-themes
            // EVERY screen the moment it arrives — previously only the
            // browser recreated (its own themeHash check) and Appearance
            // itself; every other live screen (settings home, deep settings,
            // tabs switcher, workspaces, bookmarks…) kept the OLD skin until
            // the OS happened to rebuild it. Weak keys: a destroyed activity
            // never delays collection. Main-thread only (lifecycle
            // callbacks), no synchronization needed.
            private final java.util.WeakHashMap<Activity, int[]> themeSeen =
                    new java.util.WeakHashMap<>();

            @Override public void onActivityResumed(Activity a) {
                try { com.bolt.browser.util.NightScreen.apply(a); } catch (Throwable ignored) {}
                try {
                    // The BROWSER family runs its own richer recreate check
                    // (themeHash + skinGen, incl. layout changes like the
                    // address-bar position) — never double-recreate it. The
                    // camera scanner and the credential gate keep their
                    // continuous sessions; the skin is irrelevant there.
                    if (!(a instanceof com.bolt.browser.ui.BrowserActivity)
                            && !(a instanceof com.bolt.browser.ui.QrScanActivity)
                            && !(a instanceof com.bolt.browser.ui.LockActivity)) {
                        int stamp = com.bolt.browser.util.Fx.themeStamp(a);
                        int[] seen = themeSeen.get(a);
                        if (seen == null) themeSeen.put(a, new int[]{stamp});
                        else if (seen[0] != stamp) {
                            seen[0] = stamp;
                            a.recreate();   // re-theme NOW, before the user sees a stale frame
                        }
                    }
                } catch (Throwable ignored) {}
            }
            @Override public void onActivityDestroyed(Activity a) {
                try { com.bolt.browser.util.NightScreen.cleanup(a); } catch (Throwable ignored) {}
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        });
        // storage hygiene (v1.3.12): purge orphaned/stale download part files
        // on every start — failed-download residue lives in the CACHE dir and
        // must never accumulate. Off the main thread; static on purpose so it
        // never constructs the download engine (no foreground services on a
        // cold start).
        if (!pn.endsWith(":incognito")) {
            // v1.3.27: turn a leftover media heartbeat into kill evidence
            // (MediaControlService.checkPrevDeath) BEFORE any UI runs —
            // BrowserActivity.onResume then reads lastDeathEvidence to offer
            // the background-survival dialog right after a killed session
            // ("music died while I read a PDF" finally leaves evidence).
            try {
                com.bolt.browser.media.MediaControlService.checkPrevDeath(this);
            } catch (Throwable ignored) {}
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                @Override public void onActivityStarted(Activity a) {
                    if (++startedActivities == 1) {
                        appH.removeCallbacks(bgEngage);
                        com.bolt.browser.media.MediaControlService.onAppForeground();
                    }
                }
                @Override public void onActivityStopped(Activity a) {
                    if (--startedActivities == 0) {
                        // immediate mark (timestamps the transition for the
                        // resume-guard's pause discrimination; v1.3.16: also
                        // takes background audio focus IMMEDIATELY — the engine
                        // decides within milliseconds whether backgrounded media
                        // may keep playing, and a 700 ms-late focus take meant a
                        // forced pause that the guard only reversed ~1 s later,
                        // i.e. the audible "slight pause" on leaving the app)...
                        com.bolt.browser.media.MediaControlService.onAppBackgrounding();
                        // ...debounced backstop engage (no-op if already engaged)
                        appH.postDelayed(bgEngage, 700);
                    }
                }
                @Override public void onActivityCreated(Activity a, Bundle b) { }
                @Override public void onActivityResumed(Activity a) {
                    resumedActivity = a;   // v1.3.24: passkey consent dialog owner
                }
                @Override public void onActivityPaused(Activity a) {
                    if (resumedActivity == a) resumedActivity = null;
                }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                @Override public void onActivityDestroyed(Activity a) { }
            });
            new Thread(() -> {
                try { com.bolt.browser.core.DownloadEngine.purgeStaleParts(BoltApp.this); }
                catch (Throwable ignored) {}
            }, "bolt-purge").start();
            // v1.3.82 — THE LAUNCHER GUARD: PackageManager component-enabled
            // state is PERSISTENT DEVICE STATE (it outlives process death,
            // force-stop AND updates). The v1.3.81 update removed the old
            // colorway aliases while the main activity carried a survived
            // explicit DISABLE — zero launcher components, the app invisible
            // and un-openable. Every process start now verifies the launcher
            // face and self-heals (stranded -> default bolt + pref reset).
            // The MY_PACKAGE_REPLACED receiver covers the can't-launch case.
            new Thread(() -> {
                try { com.bolt.browser.util.LauncherGuard.heal(BoltApp.this); }
                catch (Throwable ignored) {}
            }, "bolt-launcher-guard").start();
        }
        // make Android/data/com.bolt.browser exist (it never did: nothing ever
        // touched the external files dir). NOTE for users: on Android 11+ the
        // system hides Android/data from file managers regardless; the app's
        // real private data lives in /data/data/com.bolt.browser (root only),
        // and downloads/saved pages go to the folders picked in Settings.
        try { getExternalFilesDir(null); } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CH_MEDIA,
                    getString(R.string.notification_channel_media),
                    NotificationManager.IMPORTANCE_LOW); // silent, stays in shade
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            NotificationChannel dl = new NotificationChannel(CH_DL,
                    getString(R.string.notification_channel_downloads),
                    NotificationManager.IMPORTANCE_LOW);
            dl.setShowBadge(false);
            nm.createNotificationChannel(dl);
            // v1.3.28: the watchdog's "Playback interrupted — tap to resume"
            // notice; DEFAULT importance so it heads-ups once and never rings
            // again (auto-cancel, no ongoing posts on this channel).
            NotificationChannel al = new NotificationChannel(CH_ALERT,
                    getString(R.string.notification_channel_alert),
                    NotificationManager.IMPORTANCE_DEFAULT);
            al.setShowBadge(false);
            nm.createNotificationChannel(al);
        }
    }

    static String processName() {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/self/cmdline"));
            String s = r.readLine();
            r.close();
            return s == null ? "" : s.trim();
        } catch (Throwable e) {
            return "";
        }
    }
}
