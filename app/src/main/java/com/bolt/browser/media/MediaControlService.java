package com.bolt.browser.media;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.NotificationCompat;

import com.bolt.browser.BoltApp;
import com.bolt.browser.R;
import com.bolt.browser.core.Tab;
import com.bolt.browser.core.TabManager;
import com.bolt.browser.ui.BrowserActivity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v1.3.13 media service rebuild — Opera-style background playback.
 *
 * Behaviour contract (rebuilt from the user's spec):
 *  - The notification appears the moment any tab starts playing media
 *    (event-driven: the JS core reports 'state' the instant a media element
 *    starts) — not just for YouTube, for EVERY site with a <video>/<audio>.
 *  - While PLAYING the notification is ongoing: it cannot be swiped away and
 *    the media keeps running in-app, backgrounded, and after the app is swiped
 *    from recents (onTaskRemoved keeps the service — the WebView survives in
 *    the TabManager and the FGS keeps the process alive).
 *  - While PAUSED the notification STAYS visible but becomes swipeable — it
 *    only disappears when the USER swipes it (that swipe also pauses the media
 *    and stops the service). Previously the notification vanished on pause.
 *  - Notification + lockscreen + MediaSession controls (play/pause, ±10s,
 *    seek bar) drive the media through the page's __boltCmd — they work with
 *    the app open, backgrounded, or closed.
 *  - v1.3.15: focus strategy split by visibility. FOREGROUND the service
 *    requests nothing (v1.3.14 behavior, field-verified clean: the engine
 *    owns focus there, and fighting it caused the 1 Hz audible stutter).
 *    The moment the app is REALLY backgrounded — last activity stopped,
 *    tracked by BoltApp so Settings/tab-switcher screens never trigger it,
 *    debounced so fast recreates never spuriously engage — and media is
 *    live, the service takes audio focus ONCE with a NO-OP listener:
 *    holding focus is what makes Android treat the app as a legitimate
 *    background media app so the engine keeps playing (that flag was
 *    load-bearing in v1.3.13; removing it in v1.3.14 killed background
 *    playback entirely — the version history proves it). The no-op listener
 *    means we never fight the engine again — it reacts to real focus losses
 *    (calls, other apps' music) by itself. A bounded resume-guard reverses
 *    an engine pause that lands AFTER backgrounding (timestamp-discriminated
 *    from a user pause before it; dispatched-command tracking guards the
 *    notification/headset pauses) — max 2 attempts, never a war. Returning
 *    to the foreground releases the focus and disarms the guard.
 *  - v1.3.16: two field-reported bugs fixed on top of v1.3.15.
 *    (1) "Slight pause before background playback starts": the focus take
 *        moved from a 700 ms debounce to the SAME main-loop turn as the last
 *        activity's onStop (the engine decides within milliseconds whether
 *        backgrounded media may continue — a late focus take meant a forced
 *        pause the guard only reversed ~1.3 s later); the guard's checks are
 *        denser (300/800/1600/3000 ms, max 3 attempts); and a quick-return
 *        "dip heal" undoes any pause our own focus take caused during a
 *        rotation/recreate dip.
 *    (2) "Play from the notification does nothing after pause → re-enter →
 *        leave": a merely-PAUSED media tab was Chromium-paused by
 *        BrowserActivity.onPause (only PLAYING tabs were exempt), so every
 *        notification PLAY tap hit a frozen renderer. Fixed on both sides:
 *        tabs holding loaded media are now exempt from web.onPause()
 *        (Tab.holdsMedia), and sendCmd() resumes the WebView before any
 *        non-pause command — healing zombies and legacy states alike.
 *  - Media events arrive from the page bridge via onMediaEvent(); commands
 *    go back through the tab's WebView, resolved from the shared TabManager
 *    (which keeps holding the WebViews even after the activity is gone).
 */
public class MediaControlService extends Service {

    public static final String CMD = "cmd";
    public static final int CMD_TOGGLE = 1, CMD_REWIND = 2, CMD_FORWARD = 3, CMD_CLOSE = 4,
            CMD_PLAY = 5, CMD_PAUSE = 6;

    private static final String ACTION_EVENT = "com.bolt.browser.media.EVENT";
    private static final String ACTION_DISMISS = "com.bolt.browser.media.DISMISS";
    private static final String ACTION_WATCHDOG = "com.bolt.browser.media.WATCHDOG";
    private static final String ACTION_PHOENIX = "com.bolt.browser.media.PHOENIX";
    private static final String EXTRA_TAB = "tab";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_JSON = "json";
    private static final int NOTIF_ID = 42;

    private static volatile MediaControlService instance;
    // ---------------------------------------------------------------- state
    private MediaSession session;
    private NotificationManager nm;
    private AudioManager audio;
    private PowerManager.WakeLock wl;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean playing = false;
    private volatile int mediaTabIdx = -1;      // tab owning the current media
    private String title = "";
    private String domain = "";
    private long posMs = 0, durMs = 0;
    private float rate = 1f;
    private long posAt = 0;                     // elapsed-realtime of last position beat
    private BecomingNoisy noisy;
    private boolean noisyRegistered = false;

    // ---- v1.3.15 background focus + resume guard ----
    private AudioFocusRequest bgReq;
    private final AudioManager.OnAudioFocusChangeListener noOpFocus = change -> {
        // v1.4.7 — still a no-op DECISION-MAKER (we never fight the engine —
        // the v1.3.13 pause-war law), but the ROTATION GUARD needs to know
        // when another audio source (a call, another app's music) took the
        // focus away: a pause landing right after a focus loss is a REAL
        // event and must never be resurrected against. Timestamps only.
        try {
            if (change == AudioManager.AUDIOFOCUS_LOSS
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
                focusLostAt = SystemClock.elapsedRealtime();
            else if (change == AudioManager.AUDIOFOCUS_GAIN)
                focusGainedAt = SystemClock.elapsedRealtime();
        } catch (Throwable ignored) {}
    };
    private boolean appBg = false;              // no activity of the app is started
    private boolean bgFocus = false;            // we hold audio focus while bg
    private long bgEnteredAt = 0;               // when the last activity stopped
    private boolean wasPlayingAtBg = false;      // v1.3.16: playing when the app left
    private long lastPauseReportAt = 0;         // when media last went not-playing
    private boolean lastNone = false;           // last pause report was 'none' (ended)
    private boolean guardArmed = false;
    private int guardAttempts = 0;
    private boolean userPausedSinceBg = false;  // a pause command was dispatched
    private final Runnable bgGuard = this::bgGuardCheck;
    // v1.4.7 — THE ROTATION GUARD (the "background video playback stops when
    // I rotate phone to landscape" report): a device rotation while the app
    // is backgrounded can pause media WITHOUT any page event reaching our
    // JS mute (an engine-side reaction to the resize on a concealed
    // renderer). The guard's discrimination window (pauses within ~1.5 s of
    // backgrounding) is long gone by the time anyone rotates a phone — the
    // DisplayListener re-arms it with a FRESH base (rotAt) on every display
    // rotation while backgrounded media is expected. A pause that follows a
    // FOCUS LOSS (call, other app) is never resurrected (focusLostAt/
    // focusGainedAt latch above).
    private long rotAt = 0;                     // last display rotation while bg (0 = none)
    private long focusLostAt = 0, focusGainedAt = 0;
    private android.hardware.display.DisplayManager dispMgr = null;
    private int lastDispRot = -1;
    private final android.hardware.display.DisplayManager.DisplayListener rotListener =
            new android.hardware.display.DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {}
        @Override public void onDisplayRemoved(int displayId) {}
        @Override public void onDisplayChanged(int displayId) {
            try { onDisplayMaybeRotated(); } catch (Throwable ignored) {}
        }
    };

    private boolean lastPublishedPlaying = false;
    private String lastPublishedTitle = "", lastPublishedDomain = "";
    /** v1.3.28 — the session metadata carries the DURATION the seekbar is
     *  drawn from; without it in the change gate a late-arriving duration
     *  (SPA autoplay: loadedmetadata after play) never re-published the
     *  metadata, leaving the notification on the previous video's total. */
    private long lastPublishedDurMs = -1;

    // ---- v1.3.27 kill forensics ----
    /** evidence line from the PREVIOUS process's death — set at app start by
     *  checkPrevDeath() from the leftover heartbeat mark; read by
     *  BrowserActivity.onResume to offer the background-survival dialog at
     *  exactly the moment the user comes back to a killed session. */
    public static volatile String lastDeathEvidence;
    /** v1.3.28 — wall-clock time of the PROC_KILL detection (0 = none), read
     *  by the alarm watchdog to decide whether a recovery notification is
     *  still fresh enough to post. */
    public static volatile long lastDeathAt = 0;
    /** v1.3.30 — the resume URL of the media that died ("" when the old
     *  heartbeat mark carried none). The phoenix reloads exactly this. */
    public static volatile String lastDeathUrl = "";
    /** v1.3.30 — true when the death heartbeat says battery optimization was
     *  NOT whitelisted at death (the v1.3.29 field kill happened with
     *  bo=optimizable — a silently-reset whitelist). Read by the survival
     *  dialog flow to re-arm the one-time offer with hard evidence. */
    public static volatile boolean lastDeathBo = true;
    /** v1.3.30 — true when the death heartbeat was written by a phoenix-born
     *  playback session (diag/evidence only — the hourly rate cap is the
     *  real loop guard). */
    public static volatile boolean lastDeathPh = false;
    private long svcStartedAt = 0;
    private final Runnable keepAlive = this::keepAliveBeat;

    // ---- v1.3.28 background autoplay transition + alarm watchdog ----
    /** A video ENDED while the app is backgrounded: the next queued video is
     *  expected within seconds (YouTube autoplay gap + buffering). During the
     *  window the service KEEPS its foreground status, wake lock and
     *  heartbeat — the v1.3.27 1.5 s idle retirement decayed the process
     *  from fg-service (adj 200) to a plain started service (adj 500+, the
     *  most killable class) exactly when memory pressure hits, and forced a
     *  destroy/recreate notification blink between every two videos. */
    private static final long BG_TRANSITION_MS = 30_000;
    private boolean bgTransition = false;
    private long bgTransitionDeadline = 0;
    private final Runnable transitionTimeout = this::transitionExpired;
    /** liveness alarm cadence while playing (survives the process itself).
     *  v1.3.29: 45 s → 95 s. At 45 s (< the 60 s keepalive beat) the alarm
     *  fired once between EVERY pair of beats while healthy — 60 exact
     *  RTC_WAKEUP fires an hour for nothing. Above the beat period each beat
     *  re-arms past the pending fire, so a healthy session NEVER fires it; a
     *  kill is still detected ≤95 s after the last beat (inside the
     *  receiver's 120 s evidence-freshness window).
     *  v1.3.33: 95 s → 65 s. The v1.3.32 field report says the post-kill
     *  silence ("pause after a while then continues") is TOO LONG. With the
     *  beat at 60 s, an interval of 65 s keeps the "never fires healthy"
     *  property (5 s margin against a late beat) while shrinking the
     *  worst-case detection gap from 95 s to 65 s and the average from ~65 s
     *  to ~35 s. The only cost: a beat delayed >5 s lets one alarm fire
     *  healthy — the receiver just re-arms (harmless, and rare: beats only
     *  log + write a tiny file). DO NOT re-arm this alarm from anything
     *  faster than the beat (e.g. the v1.3.33 position heartbeat) — a
     *  fresher alarm means a LONGER post-kill wait, exactly the opposite of
     *  the goal. */
    private static final long WATCHDOG_MS = 65_000;
    private boolean watchdogLogged = false;

    // ---------------- v1.3.30 PHOENIX (auto-resume after a process kill) ----------------
    /** THE field evidence: the v1.3.29 log shows the kill landing mid-song
     *  (~6 s after the last heartbeat, bo=optimizable, adj=200), the sticky
     *  service restarting and immediately retiring ("svc destroy
     *  playing=false up=0s") — the user gets a dead phone until they tap
     *  the recovery notification. On this 3 GB realme no legal lever keeps
     *  the process alive through xbrowser+ZAI pressure (adj 200 is the
     *  ceiling; lmkd shoots perceptible apps when thrashing at critical
     *  levels), so v1.3.30 stops pretending the kill is preventable and
     *  makes it BORING: the restarted service rebuilds the media tab in a
     *  headless WebView, reloads the &t= resume URL and the music comes
     *  back by itself ~15-25 s after the kill. If it cannot (timeout, no
     *  URL, rate-capped), the one-tap recovery notification is posted —
     *  exactly the v1.3.28 behavior. Rate caps keep it honest: max 2
     *  auto-resumes per rolling hour and 20 s between attempts, so a
     *  sustained pressure storm degrades to notifications, never a
     *  kill/restart loop. */
    private static final long PHOENIX_DELAY_MS = 2_500;       // v1.3.33: 8 s → 2.5 s (the "let the killer settle" wait was the longest single slice of audible silence after detection; the memory gate + rate caps below are the real storm protection)
    private static final long PHOENIX_NUDGE_MS = 9_000;      // v1.3.33: 18 s → 9 s, and the nudge REPEATS every 7 s — one early play command beats autoplay-stall at half the old latency
    private static final long PHOENIX_NUDGE_REPEAT_MS = 7_000;
    private static final long PHOENIX_TIMEOUT_MS = 35_000;    // give up → recovery notification
    private static final int PHOENIX_MAX_PER_HOUR = 2;
    private static final long PHOENIX_MIN_GAP_MS = 20_000;
    private static final long PHOENIX_EVIDENCE_FRESH_MS = 5 * 60_000;
    private static final long PHOENIX_MIN_AVAIL_MB = 300;     // do not feed a thrashing system
    /** the headless WebView being loaded (null once playback started — the
     *  Tab owns the web from then on). */
    private WebView phoenixWeb;
    private int phoenixIdx = -1;
    /** true from the moment a phoenix attempt loads until this service
     *  instance dies — the heartbeat marks such sessions so the evidence
     *  line can say "a phoenix-born session died". */
    private boolean phoenixActive = false;
    private final Runnable phoenixStart = this::phoenixStartRun;
    private final Runnable phoenixNudge = this::phoenixNudgeRun;
    private final Runnable phoenixTimeout = this::phoenixTimeoutRun;
    /** v1.3.30 — position (ms) the death heartbeat carried — what the
     *  phoenix reloads from. */
    public static volatile long lastDeathPosMs = 0;
    /** true while a phoenix attempt is scheduled (between tryPhoenix and the
     *  delay firing) — Handler.hasCallbacks is API 29+ and minSdk is 24. */
    private boolean phoenixArmed = false;
    private int phoenixMemRetries = 0;

    // ---------------- v1.3.29 background concealment ----------------
    /** how long after backgrounding/screen-off the renderer stays "visible"
     *  (the seamless-handoff window) before it is told the truth. */
    private static final long CONCEAL_DELAY_MS = 15_000;
    private boolean concealed = false;          // the renderer was told the real GONE
    private boolean concealLatched = false;     // a 'concealfail' disabled it for this session
    /** v1.3.90 — rate limit for ctxheal diag lines (one per 10 s; the JS
     *  heal can fire on every timeupdate while a context stays dead). */
    private long lastCtxHealLog = 0;
    private boolean screenOff = false;          // screen-off also qualifies (activity-paused-but-top)
    private final Runnable concealCheck = this::concealCheckRun;
    private android.content.BroadcastReceiver screenRx = null;
    // v1.3.29 notification repost gate (see notifyUi)
    private boolean lastNotifPlaying = false;
    private String lastNotifTitle = null, lastNotifDomain = null;
    private long lastNotifDurMs = -1;
    private int lastNotifArtGen = -1;
    private long lastPostAt = 0;
    // v1.3.29 playback-state publish gate (see publishSessionState)
    private long lastPublishAt = -60_000;
    private long lastPosPublished = -1;

    // ---- v1.3.18 notification artwork (video thumbnail) ----
    /** decoded thumbnail: attached to the MediaSession metadata (ART/ALBUM_ART
     *  — what Android 13+ MediaStyle notifications render) AND the
     *  notification large icon (pre-13 look). Replaced when the playing video
     *  changes; single slot, ~0.5 MB — no cache management needed. */
    private Bitmap artBitmap;
    /** URL of the artwork currently held or being fetched ("" = none). */
    private String artUrl = "";
    /** generation counter: a fetch that finishes after a newer one started
     *  (fast video switching) is dropped instead of overwriting newer art. */
    private int artGen = 0;
    private ExecutorService artEx;                 // single-threaded artwork downloader
    // v1.3.22: artwork dimensions that crash API<=29 SystemUI (see avoidArtSizes)
    private int[] avoidArtSizes;
    private final Runnable delayedStopIfIdle = () -> {
        // v1.3.24: sticky notification — while any tab still holds loaded or
        // playing media (video paused, page open), the notification stays.
        // Only when NOTHING holds media anywhere may the idle timer retire it
        // (true orphan: every page reported 'none' or was torn down).
        if (playing || instance == null) return;
        if (anyTabHoldsMedia()) return;
        stopEverything();
    };

    // ---------------------------------------------------------------- static API

    /** Page bridge entry point (any thread). Delivers a media event.
     *  When the service is not running, only PLAYING events may spawn it
     *  (startForegroundService must be followed by startForeground — a stale
     *  pause event would crash the app; a stale event is dropped instead). */
    public static void onMediaEvent(Context c, int tabIndex, String name, String json) {
        MediaControlService s = instance;
        if (s != null) {
            s.handleEvent(tabIndex, name, json);
            return;
        }
        boolean playingEvent = false;
        if ("state".equals(name)) {
            try { playingEvent = new JSONObject(json == null ? "{}" : json)
                    .optBoolean("playing", false); } catch (Throwable ignored) { }
        }
        if (!playingEvent) return; // stale event for a dead service — drop
        Intent i = new Intent(c, MediaControlService.class);
        i.setAction(ACTION_EVENT);
        i.putExtra(EXTRA_TAB, tabIndex);
        i.putExtra(EXTRA_NAME, name == null ? "" : name);
        i.putExtra(EXTRA_JSON, json == null ? "{}" : json);
        try {
            c.startForegroundService(i);
        } catch (Throwable t) {
            try { c.startService(i); } catch (Throwable ignored) { }
        }
    }

    /** A tab was closed / its WebView destroyed: forget it as the media owner. */
    public static void onTabGone(Context c, int tabIndex) {
        MediaControlService s = instance;
        if (s != null) {
            s.forgetTab(tabIndex);
            return;
        }
        // not running: nothing to do
    }

    /** BoltApp lifecycle: the LAST activity just stopped (immediate, not
     *  debounced — timestamps the transition so the guard can tell an engine
     *  pause that lands AFTER backgrounding from a user pause made before it). */
    public static void onAppBackgrounding() {
        MediaControlService s = instance;
        if (s != null) s.ui.post(s::markBackgrounded);
    }

    /** BoltApp lifecycle, debounced (still no activity): take background
     *  audio focus and arm the resume guard. */
    public static void onAppBackground() {
        MediaControlService s = instance;
        if (s != null) s.ui.post(s::engageBackground);
    }

    /** BoltApp lifecycle: an activity started again — release background focus. */
    public static void onAppForeground() {
        MediaControlService s = instance;
        if (s != null) s.ui.post(s::markForegrounded);
    }

    public static boolean isRunning() { return instance != null; }

    /** v1.4.21 — settings hook: the Show-media-artwork toggle flipped;
     *  re-publish NOW so the notification adopts (or drops) its artwork on
     *  the spot instead of waiting for the next title/state beat. No-op
     *  when the service is not running (the pref is read fresh at every
     *  publish anyway). */
    public static void requestPublish(Context c) {
        MediaControlService s = instance;
        if (s != null) s.ui.post(() -> {
            try {
                // the artwork presence itself is the visible change — age
                // the art bookkeeping so notifyUi's repost gate fires
                s.lastNotifArtGen = -1;
                s.notifyUi();
            } catch (Throwable ignored) {}
        });
    }

    public static boolean isPlaying() {
        MediaControlService s = instance;
        return s != null && s.playing;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // v1.3.16: a service created while the app is ALREADY backgrounded
        // (zombie restart after a process kill, or a play-from-notification
        // race) must start out knowing it is backgrounded, or it will skip
        // the focus take that keeps the engine playing in the background.
        if (!BoltApp.isAppForeground()) {
            appBg = true;
            bgEnteredAt = SystemClock.elapsedRealtime();
        }
        registerScreen();                              // v1.3.29: screen-off is bg playback too
        registerRotation();                            // v1.4.7: display rotation re-arms the guard
        nm = getSystemService(NotificationManager.class);
        audio = getSystemService(AudioManager.class);
        session = new MediaSession(this, "BoltMedia");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { dispatch(CMD_PLAY); }
            @Override public void onPause() { dispatch(CMD_PAUSE); }
            @Override public void onRewind() { dispatch(CMD_REWIND); }
            @Override public void onFastForward() { dispatch(CMD_FORWARD); }
            @Override public void onStop() { dispatch(CMD_CLOSE); stopEverything(); }
            @Override public void onSeekTo(long pos) {
                // v1.3.63 — THE PAUSED-SEEK DESYNC FIX (part 1 of 3). The
                // v1.3.62 field report: "the notification video progress bar
                // loses sync to the video if I pause and adjust the playhead
                // from notification". The old path ONLY dispatched seekTo to
                // the page: the paused-side handlers below never adopted the
                // new position and never re-published the PlaybackState, so
                // SystemUI kept interpolating from the LAST published
                // (frozen) position — the dragged playhead snapped back and
                // stayed wrong until PLAY. Adopt the seek target NOW
                // (optimistic — exactly what the user dragged), stamp posAt,
                // and FORCE a publish so the bar holds the target. The page's
                // own 'time'/'state' beats correct posMs within ~1 s (parts
                // 2 and 3 make those beats able to publish while paused).
                try {
                    posMs = Math.max(0, pos);
                    posAt = SystemClock.elapsedRealtime();
                    publishSessionState(true);
                } catch (Throwable ignored) {}
                sendCmd("seekTo", pos / 1000.0);
            }
        }, ui);
        session.setActive(true);
        try {
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        } catch (Throwable ignored) { }
        svcStartedAt = SystemClock.elapsedRealtime();
        try {
            com.bolt.browser.util.Diag.log(this, "svc create bg=" + appBg);
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // system restarted us (START_STICKY) after killing the process.
            // v1.3.30 PHOENIX: the media state and the WebViews died with the
            // process — but the heartbeat mark outlived it, so BoltApp has
            // already turned it into PROC_KILL evidence + the resume URL.
            // Instead of retiring quietly, try to bring the music back by
            // itself (rate/memory gated); only when the phoenix does not
            // apply does the old quiet-retire path run.
            if (tryPhoenix("system-restart")) return START_STICKY;
            try {
                com.bolt.browser.util.Diag.log(this,
                        "svc system-restart (null intent) — previous process was "
                                + "killed while the service was up");
            } catch (Throwable ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_EVENT.equals(action)) {
            handleEvent(intent.getIntExtra(EXTRA_TAB, -1),
                    intent.getStringExtra(EXTRA_NAME),
                    intent.getStringExtra(EXTRA_JSON));
        } else if (ACTION_PHOENIX.equals(action)) {
            // v1.3.30: the alarm watchdog detected a kill (the sticky restart
            // may have been late or absent) — same phoenix attempt path.
            // NOTE: this service was started with startForegroundService(),
            // so the startForeground contract must be satisfied even on the
            // no-go path — goForeground() then stopEverything() (which calls
            // stopForeground) keeps the notification flash invisible while
            // never leaving the contract unmet (RemoteServiceException).
            if (!tryPhoenix("watchdog")) {
                goForeground();
                stopEverything();
                return START_NOT_STICKY;
            }
        } else if (intent.hasExtra(CMD)) {
            int cmd = intent.getIntExtra(CMD, 0);
            if (cmd == CMD_CLOSE) {
                dispatch(CMD_CLOSE);
                stopEverything();
                return START_NOT_STICKY;
            }
            dispatch(cmd);
        } else if (ACTION_DISMISS.equals(action)) {
            // user swiped the paused notification away
            dispatch(CMD_PAUSE);
            stopEverything();
            return START_NOT_STICKY;
        }
        if (playing) {
            goForeground();
        } else if (phoenixWeb == null && !phoenixArmed) {
            // service started while idle (event race): re-assert state once.
            // (A phoenix attempt in flight keeps its own foreground state.)
            notifyUi();
        }
        return START_STICKY;
    }

    /** The app is swiped from recents: playback + notification must SURVIVE.
     *  The WebView lives on in the shared TabManager and this foreground
     *  service keeps the process alive. This used to stopSelf() — that is
     *  exactly why "closed too" never worked. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // deliberately do nothing (see comment above)
        try {
            com.bolt.browser.util.Diag.log(this, "taskRemoved playing=" + playing
                    + " — playback + notification survive by design");
        } catch (Throwable ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        ui.removeCallbacksAndMessages(null);
        // v1.3.30: same leak guard as stopEverything — a loading phoenix web
        // must die with the service (an ADOPTED, playing one is already
        // phoenixWeb == null and lives on in the TabManager by design).
        if (phoenixWeb != null) {
            try {
                Tab t = tabAt(phoenixIdx);
                if (t != null && t.web == phoenixWeb) {
                    t.web = null;
                    t.mediaPlaying = false;
                    t.holdsMedia = false;
                }
            } catch (Throwable ignored) {}
            try { phoenixWeb.destroy(); } catch (Throwable ignored) {}
            phoenixWeb = null;
        }
        try {
            com.bolt.browser.util.Diag.log(this, "svc destroy playing=" + playing
                    + " appBg=" + appBg + " up="
                    + ((SystemClock.elapsedRealtime() - svcStartedAt) / 1000) + "s");
        } catch (Throwable ignored) {}
        releaseWakeLock();
        unregisterScreen();                            // v1.3.29
        unregisterRotation();                          // v1.4.7
        unregisterNoisy();
        abandonBgFocus();
        artGen++;                                  // v1.3.18: cancel any in-flight art fetch
        try { if (artEx != null) artEx.shutdownNow(); } catch (Throwable ignored) { }
        try {
            if (session != null) {
                session.setActive(false);
                session.release();
            }
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- events

    private void handleEvent(final int tabIndex, final String name, final String json) {
        ui.post(() -> {
            try {
                if (name == null) return;
                JSONObject o = json != null ? new JSONObject(json) : new JSONObject();
                if ("state".equals(name)) {
                    boolean isPlaying = o.optBoolean("playing", false);
                    if (isPlaying) {
                        ui.removeCallbacks(delayedStopIfIdle);
                        mediaTabIdx = tabIndex;
                        String t = o.optString("title", "");
                        if (t.length() > 0 && !t.equals(title)) title = t;
                        // v1.3.18: artwork from the page report (MediaSession
                        // artwork / poster / og:image) — fetch + attach to the
                        // notification + session metadata.
                        String art = o.optString("art", "");
                        if (!art.isEmpty() && !art.equals(artUrl)) fetchArt(art);
                        // notification content line: the media tab's host
                        try {
                            Tab w = resolveMediaTab();
                            if (w != null && w.url != null) {
                                String host = com.bolt.browser.util.Fx.hostOf(w.url);
                                if (host != null && !host.isEmpty()) domain = host;
                            }
                        } catch (Throwable ignored) { }
                        double dur = o.optDouble("dur", 0);
                        if (dur > 0) durMs = (long) (dur * 1000);
                        posMs = (long) (o.optDouble("pos", 0) * 1000);
                        rate = (float) o.optDouble("rate", 1);
                        posAt = SystemClock.elapsedRealtime();
                        if (!playing) {
                            playing = true;
                            // v1.3.30 PHOENIX SUCCESS: this playing report came
                            // out of the headless resume WebView (or displaced
                            // it) — the attempt is over, the Tab owns the web,
                            // the normal playing flow takes it from here.
                            if (phoenixWeb != null) {
                                ui.removeCallbacks(phoenixNudge);
                                ui.removeCallbacks(phoenixTimeout);
                                phoenixWeb = null;
                                try {
                                    com.bolt.browser.util.Diag.log(this,
                                            "phoenix playing — auto-resume "
                                                    + "succeeded (system kill "
                                                    + ((System.currentTimeMillis()
                                                    - lastDeathAt) / 1000)
                                                    + "s ago)");
                                } catch (Throwable ignored) {}
                            }
                            lastNone = false;
                            bgTransition = false;          // v1.3.28: the next queued video arrived
                            ui.removeCallbacks(transitionTimeout);
                            registerNoisy();
                            acquireWakeLock();
                            goForeground();
                            startKeepAlive();
                            startPosBeat();               // v1.3.33: fresh resume positions
                            ensureWatchdog();              // v1.3.28: liveness alarm that outlives the process
                            scheduleConceal();              // v1.3.29: tell the renderer the truth 15 s in
                            // play pressed from the notification while the app
                            // is backgrounded: the focus + guard apply too
                            if (appBg) { acquireBgFocus(); armGuard(); }
                        }
                        notifyUi();
                    } else {
                        // v1.3.24 — THE tab-switch notification bug: only the
                        // MEDIA TAB's own reports govern its playback state.
                        // Before this, a no-media page loading in ANOTHER tab
                        // sent a 'none' state report that was treated as a
                        // pause of the PLAYING tab: notification detached, idle
                        // timer armed, service dead 1.5 s later — while the
                        // video kept playing (its 'playing' state reports are
                        // deduped by the page-side hook, so nothing ever
                        // revived it). Reports from any other tab are noise.
                        if (mediaTabIdx >= 0 && tabIndex != mediaTabIdx) return;
                        double dur = o.optDouble("dur", 0);
                        boolean none = o.optDouble("pos", 0) <= 0 && dur <= 0;
                        if (playing) {
                            // pause (page-reported) — stay visible, become swipeable
                            playing = false;
                            lastPauseReportAt = SystemClock.elapsedRealtime();
                            posMs = (long) (o.optDouble("pos", 0) * 1000);
                            posAt = SystemClock.elapsedRealtime();
                            if (appBg && none && durMs > 0 && !userPausedSinceBg) {
                                // v1.3.28 — background AUTOPLAY TRANSITION: the
                                // video ended on its own while backgrounded (a
                                // user pause sets userPausedSinceBg and an
                                // engine pause reports 'paused', not 'none').
                                // HOLD the foreground service + wake lock +
                                // heartbeat through the gap so the process
                                // never decays below fg-service importance
                                // (adj 200 → 500+ in v1.3.27's 1.5 s idle
                                // retirement — the most killable moment of a
                                // queued-video session) and the next video's
                                // events land in a live service. The
                                // transition timer below retires everything
                                // cleanly if no next video arrives.
                                bgTransition = true;
                                bgTransitionDeadline = SystemClock.elapsedRealtime() + BG_TRANSITION_MS;
                                ui.removeCallbacks(transitionTimeout);
                                ui.postDelayed(transitionTimeout, BG_TRANSITION_MS);
                                // v1.3.29: the next queued video must start
                                // from a page Chromium believes is visible —
                                // the empirical "hidden-state pages ignore
                                // programmatic play()" risk. Un-conceal for
                                // the transition; the next video's play-start
                                // re-arms concealment 15 s later.
                                unconceal();
                                notifyUi();
                            } else {
                                bgTransition = false;
                                ui.removeCallbacks(transitionTimeout);
                                retireFromPlaying();
                                notifyUi();
                            }
                        }
                        if (dur > 0) durMs = (long) (dur * 1000);
                        // v1.3.63 — THE PAUSED-SEEK DESYNC FIX (part 2): a
                        // 'state' report from an ALREADY-PAUSED page (the
                        // seekTo command's own report(true), a seek landing,
                        // metadata refresh) carries the page's true position —
                        // adopt it and let the jumped-gate publish if it moved.
                        // Before, posMs was only ever updated on the
                        // playing→paused TRANSITION, so every later paused
                        // position change was invisible to the seekbar.
                        if (!playing) {
                            long p = (long) (o.optDouble("pos", 0) * 1000);
                            if (p > 0 || none) {
                                posMs = p;
                                posAt = SystemClock.elapsedRealtime();
                                publishSessionState(false);
                            }
                        }
                        // 'none' (all media ended) vs 'paused': paused keeps a
                        // pos/dur, none reports zeros. Only none may retire the
                        // notification — a paused one stays until the user swipes.
                        lastNone = none;
                        if (none && !bgTransition) maybeIdle();
                    }
                } else if ("concealfail".equals(name)) {
                    // v1.3.29: the in-page sentinel lost its bounded fight —
                    // Chromium or the page refuses playback from the
                    // concealed (hidden-renderer) state on this site. Restore
                    // the always-lie NOW and latch concealment off for the
                    // rest of this service instance: worst case is one short
                    // blip right after backgrounding, once, and then exactly
                    // the v1.3.28 behavior (working playback, higher cost).
                    // v1.3.30: the v1.3.29 field log proved this rollback
                    // fires on YouTube on the user's device EVERY session —
                    // the once-per-session retry is what the user hears as a
                    // ~3 s blip after every backgrounding. PERSIST the latch
                    // now (keyed to the app version so each release re-tests
                    // the behavior once — a WebView update can change it) and
                    // the blip happens at most once per install/version.
                    concealLatched = true;
                    try {
                        com.bolt.browser.util.Prefs.get(this)
                                .set("concealLatchVer",
                                        com.bolt.browser.BuildConfig.VERSION_CODE);
                    } catch (Throwable ignored) {}
                    unconceal();
                    try {
                        com.bolt.browser.util.Diag.log(this, "bgConceal ROLLBACK — "
                                + "playback refused the hidden state; always-lie "
                                + "restored, latch PERSISTED for this version");
                    } catch (Throwable ignored) {}
                } else if ("ctxheal".equals(name)) {
                    // v1.3.90 — THE CONTEXT SELF-HEAL, JAVA HALF. The
                    // in-page ctxHeal() (MediaHook) re-resumes AudioContexts
                    // that Chromium suspended on the hidden/concealed
                    // renderer — a routed element (booster EQ / skipper
                    // analyser) is SILENCED by a suspended context with no
                    // pause event: the notification says playing, the user
                    // hears nothing ("background playback stops a lot more
                    // often than it used to"). ok=1: the resume took (the
                    // common case — sticky activation from the user's play
                    // tap). ok=0: the platform refuses the resume while
                    // concealed — roll concealment back to the always-lie
                    // (session latch only: un-concealing makes the renderer
                    // "visible" again, which is exactly what lets the
                    // context resume; the v1.3.29 concealfail contract,
                    // minus the persisted latch because this one heals
                    // itself the moment the lie is restored).
                    int nHealed = o.optInt("n", 0);
                    boolean okHeal = o.optBoolean("ok", true);
                    long nowC = SystemClock.elapsedRealtime();
                    if (nowC - lastCtxHealLog > 10_000) {
                        lastCtxHealLog = nowC;
                        try {
                            com.bolt.browser.util.Diag.log(this, "bg ctx-heal n="
                                    + nHealed + " ok=" + (okHeal ? 1 : 0)
                                    + " (suspended audio context resumed)");
                        } catch (Throwable ignored) {}
                    }
                    if (!okHeal && concealed) {
                        concealLatched = true;   // session-only (see above)
                        unconceal();
                        try {
                            com.bolt.browser.util.Diag.log(this, "bg ctx-heal "
                                    + "ROLLBACK — context refused the resume; "
                                    + "always-lie restored for the session");
                        } catch (Throwable ignored) {}
                    }
                } else if ("time".equals(name)) {
                    // v1.3.24: a position beat from a playing tab is liveness
                    // proof — it must cancel the idle timer just like a playing
                    // state report does (the old code let the timer survive
                    // beats, so a beat arriving after a stray 'none' from
                    // another tab never saved the notification).
                    ui.removeCallbacks(delayedStopIfIdle);
                    posMs = (long) (o.optDouble("pos", 0) * 1000);
                    double dur = o.optDouble("dur", 0);
                    if (dur > 0) durMs = (long) (dur * 1000);
                    rate = (float) o.optDouble("rate", 1);
                    posAt = SystemClock.elapsedRealtime();
                    // v1.3.63 — THE PAUSED-SEEK DESYNC FIX (part 3): beats
                    // now publish even while PAUSED. A paused page still beats
                    // on seeks (timeupdate fires once per seek), and those were
                    // dropped (`if (playing) publishSessionState()`) — exactly
                    // the v1.3.62 desync. The gates inside publishSessionState
                    // keep the IPC discipline: no play-state change, no jump,
                    // <60 s → no publish, so a paused idle page costs nothing.
                    publishSessionState(false);
                }
            } catch (Throwable ignored) { }
        });
    }

    // ---------------- v1.3.29 concealment ----------------

    /** (Re)arm the one-shot concealment timer: 15 s after the app left (or
     *  the screen went off) with playback live, tell the renderer the truth
     *  so Chromium's background machinery (decode suspension, timer
     *  throttling, memory purge) re-engages while the in-page JS spoof keeps
     *  the player believing it is visible. */
    private void scheduleConceal() {
        if (concealLatched || concealed) return;
        // v1.3.30: the latch is PERSISTED per app version — a device/WebView
        // whose Chromium refuses hidden playback (the v1.3.29 field log:
        // rollback on YouTube every session) must not re-pay the ~3 s blip
        // on every session start. Each version bump re-tests concealment
        // exactly once (a WebView update can flip the behavior either way).
        try {
            if (com.bolt.browser.util.Prefs.get(this).i("concealLatchVer", 0)
                    == com.bolt.browser.BuildConfig.VERSION_CODE) return;
        } catch (Throwable ignored) {}
        ui.removeCallbacks(concealCheck);
        ui.postDelayed(concealCheck, CONCEAL_DELAY_MS);
    }

    private void concealCheckRun() {
        if (concealLatched || concealed) return;
        // only live background playback qualifies — foreground playback or a
        // paused notification target keeps the full always-lie treatment
        if (!(appBg || screenOff) || !playing) return;
        if (!com.bolt.browser.util.Prefs.get(this).bgPlay()) return;
        try {
            boolean ok = com.bolt.browser.ui.MediaAwareContent.concealNow();
            if (ok) {
                concealed = true;
                com.bolt.browser.util.Diag.log(this, "bgConceal on — renderer told "
                        + "the truth (decode suspend + timer throttle + memory "
                        + "purge engage; the in-page JS spoof holds the page)");
            }
        } catch (Throwable ignored) {}
    }

    /** Restore the always-lie (or let the real VISIBLE through) and cancel
     *  any pending concealment. Runs on: rollback, user pause while
     *  backgrounded (notification PLAY must land on a live "visible" page —
     *  the v1.3.17 Bug B), the background autoplay-transition window,
     *  playback retirement, and foreground return. Idempotent. */
    private void unconceal() {
        ui.removeCallbacks(concealCheck);
        if (concealed) {
            concealed = false;
            try { com.bolt.browser.ui.MediaAwareContent.unconcealNow(); } catch (Throwable ignored) {}
        }
    }

    /** v1.3.29: screen-off is background playback too (activity often only
     *  pauses, not stops — the app stays "top" and never reports bg). Pocket
     *  playback is exactly where decode suspension pays the most battery. */
    // ---------------------------------------------------------------- v1.4.7 rotation guard

    /** DisplayListener registration: fires onDisplayChanged for every display
     *  state change; we diff the rotation ourselves (the listener has no
     *  rotation-only callback). Runs on the service's main handler — the
     *  guard arming is already main-thread state. */
    private void registerRotation() {
        try {
            dispMgr = getSystemService(android.hardware.display.DisplayManager.class);
            if (dispMgr == null) return;
            android.view.Display d = dispMgr.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d != null) lastDispRot = d.getRotation();
            dispMgr.registerDisplayListener(rotListener, ui);
        } catch (Throwable ignored) {}
    }

    private void unregisterRotation() {
        try { if (dispMgr != null) dispMgr.unregisterDisplayListener(rotListener); }
        catch (Throwable ignored) {}
        dispMgr = null;
    }

    /** The display changed; when its ROTATION actually flipped and the app is
     *  backgrounded with media expected, re-arm the resume guard on a fresh
     *  base (rotAt) — the engine-side pause a rotation can provoke on a
     *  concealed renderer lands with no page event our JS mute could stop,
     *  and the original guard window (~1.5 s from backgrounding) is long
     *  expired by the time anyone rotates a phone. A fresh attempt budget
     *  per rotation event, still bounded (3), never a war; user pauses,
     *  ended media and FOCUS LOSSES (a call, another app's audio) are all
     *  respected — see bgGuardCheck. */
    private void onDisplayMaybeRotated() {
        if (dispMgr == null) return;
        android.view.Display d = dispMgr.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if (d == null) return;
        int r = d.getRotation();
        if (r == lastDispRot) return;
        lastDispRot = r;
        if (!appBg) return;                       // foreground rotation = the page's own events
        if (userPausedSinceBg || lastNone || durMs <= 0) return;
        if (!playing && !wasPlayingAtBg) return;  // nothing was ever expected to play
        rotAt = SystemClock.elapsedRealtime();
        guardAttempts = 0;                        // a fresh budget per rotation event
        armGuard();
        try {
            com.bolt.browser.util.Diag.log(this, "rot guard armed r=" + r
                    + " playing=" + playing);
        } catch (Throwable ignored) {}
    }

    private void registerScreen() {
        try {
            screenRx = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    String a = intent == null ? "" : intent.getAction();
                    screenOff = Intent.ACTION_SCREEN_OFF.equals(a);
                    if (screenOff) {
                        if (playing && !concealLatched) scheduleConceal();
                    } else if (!appBg) {
                        // screen back on while we are still the top app: the
                        // user is about to look at us again
                        unconceal();
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenRx, f);
        } catch (Throwable ignored) {}
    }

    private void unregisterScreen() {
        try { if (screenRx != null) unregisterReceiver(screenRx); } catch (Throwable ignored) {}
        screenRx = null;
    }

    /** media ended on every tab ('none' state) — retire only when NOTHING
     *  anywhere still holds media. v1.3.24 per field request: the notification
     *  is STICKY — a paused or merely-loaded media element in any tab keeps it
     *  alive until a new video starts, the tab/browser closes, or the user
     *  swipes it away. A short grace period still covers track changes /
     *  playlist advances. */
    private void maybeIdle() {
        ui.removeCallbacks(delayedStopIfIdle);
        ui.postDelayed(delayedStopIfIdle, 1500);
    }

    /** v1.3.24: true when any tab still holds loaded or playing media — the
     *  notification's reason to exist. Only the reports of the media tab
     *  itself (and tab close / manual dismiss / service death) may retire it. */
    private boolean anyTabHoldsMedia() {
        try {
            TabManager tm = TabManager.get(this);
            synchronized (tm.tabs) {   // v1.4.9: the thread law — iteration under the monitor
                for (Tab t : tm.tabs) {
                    if (t != null && (t.mediaPlaying || t.holdsMedia)) return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** v1.3.24: index of some tab holding/playing media, for ownership
     *  handover when the media tab is closed (-1 = none). */
    private int indexOfAnyMediaTab() {
        try {
            TabManager tm = TabManager.get(this);
            synchronized (tm.tabs) {   // v1.4.9: the thread law — size()+get() as one step
                for (int i = 0; i < tm.tabs.size(); i++) {
                    Tab t = tm.tabs.get(i);
                    if (t != null && (t.mediaPlaying || t.holdsMedia)) return i;
                }
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    private void forgetTab(int tabIndex) {
        ui.post(() -> {
            try {
                // TabManager.close() removes the tab BEFORE calling here, so
                // indices above the closed slot shift down by one — keep the
                // media-owner index aligned (a stale index made the service
                // target the WRONG tab's WebView after a close).
                if (tabIndex >= 0 && mediaTabIdx > tabIndex) {
                    mediaTabIdx--;          // the media tab shifted down; it was NOT closed
                    return;
                }
                if (mediaTabIdx == tabIndex) {
                    // the media tab itself was closed: hand the notification
                    // to another tab that still holds media, if any — its next
                    // state report takes over from there (v1.3.24).
                    int succ = indexOfAnyMediaTab();
                    playing = false;
                    if (succ >= 0) {
                        mediaTabIdx = succ;
                        detachForeground();
                        notifyUi();
                    } else {
                        detachForeground();
                        notifyUi();
                        maybeIdle();
                    }
                }
            } catch (Throwable ignored) { }
        });
    }

    // ---------------------------------------------------------------- commands

    private void dispatch(int cmd) {
        switch (cmd) {
            case CMD_TOGGLE:
                if (playing) userPausedSinceBg = true; // only when it will PAUSE
                sendCmd("toggle", null);
                break;
            case CMD_PLAY:
                userPausedSinceBg = false;            // explicit resume re-arms guard
                sendCmd("play", null);
                break;
            case CMD_PAUSE:
                userPausedSinceBg = true;             // never resurrect a user pause
                sendCmd("pause", null);
                break;
            case CMD_REWIND: sendCmd("seek", -10.0); break;
            case CMD_FORWARD: sendCmd("seek", 10.0); break;
            case CMD_CLOSE:
                userPausedSinceBg = true;
                sendCmd("pause", null);
                stopEverything();
                break;
        }
    }

    /** evaluateJavascript __boltCmd on the media tab's WebView. The WebView is
     *  resolved fresh from the TabManager every time: it keeps working after
     *  the activity is destroyed (TabManager is app-scoped) — that is what
     *  makes closed-app control possible.
     *  v1.3.16: non-pause commands first RESUME the WebView. A renderer frozen
     *  by web.onPause() (legacy path, bgPlay-off session, or a zombie adopted
     *  while paused) accepts the script but the frozen media pipeline ignores
     *  element.play() — exactly the "tapping play in the notification does
     *  nothing, even repeatedly" field report. Pausing commands skip the
     *  resume: no point waking a renderer just to freeze it again. */
    private void sendCmd(String cmd, Double arg) {
        ui.post(() -> {
            Tab w = resolveMediaTab();
            if (w == null || w.web == null) { maybeIdle(); return; }
            if (!"pause".equals(cmd) && !"stop".equals(cmd)) {
                // v1.3.41: the v1.3.17 Bug-B path was documented but never
                // wired — a PLAY landing on a CONCEALED renderer (hidden,
                // decode-suspended, timers throttled) is exactly the "tapping
                // play in the notification does nothing, even repeatedly"
                // field report. Un-conceal FIRST so the renderer believes it
                // is visible again, then wake the WebView, then run the
                // command. The next 'playing' report re-arms concealment 15 s
                // later (scheduleConceal), so the battery win is preserved.
                unconceal();
                try { w.web.onResume(); } catch (Throwable ignored) {}
            }
            StringBuilder js = new StringBuilder("window.__boltCmd&&window.__boltCmd('").append(cmd).append('\'');
            if (arg != null) js.append(',').append(arg);
            js.append(')');
            try {
                w.web.evaluateJavascript(js.toString(), null);
            } catch (Throwable ignored) { }
        });
    }

    private Tab resolveMediaTab() {
        try {
            TabManager tm = TabManager.get(this);
            synchronized (tm.tabs) {   // v1.4.9: the thread law — bounds check + get + scan atomically
                if (mediaTabIdx >= 0 && mediaTabIdx < tm.tabs.size()) {
                    return tm.tabs.get(mediaTabIdx);
                }
                // fall back to any tab with live media
                for (Tab t : tm.tabs) {
                    if (t != null && t.mediaPlaying) return t;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private void stopEverything() {
        playing = false;
        ui.removeCallbacksAndMessages(null);
        // v1.3.30: a phoenix attempt that never reached playback dies with
        // the service — its headless web must not leak as a zombie renderer
        phoenixCancelTimers();
        if (phoenixWeb != null) {
            try {
                Tab t = tabAt(phoenixIdx);
                if (t != null && t.web == phoenixWeb) {
                    t.web = null;
                    t.mediaPlaying = false;
                    t.holdsMedia = false;
                }
            } catch (Throwable ignored) {}
            try { phoenixWeb.stopLoading(); } catch (Throwable ignored) {}
            try { phoenixWeb.destroy(); } catch (Throwable ignored) {}
            phoenixWeb = null;
        }
        bgTransition = false;
        unconceal();                                   // v1.3.29: restore the renderer's real state
        releaseWakeLock();
        stopKeepAlive();
        stopPosBeat();                                // v1.3.33
        clearHeartbeat();
        cancelWatchdog(this);       // v1.3.28: no liveness watch while retired
        watchdogLogged = false;
        unregisterNoisy();
        disarmGuard();
        abandonBgFocus();
        artBitmap = null;      // v1.3.18: drop the held thumbnail
        artUrl = "";
        artGen++;
        try { if (session != null) session.setActive(false); } catch (Throwable ignored) { }
        try { if (nm != null) nm.cancel(NOTIF_ID); } catch (Throwable ignored) { }
        stopForeground(true);
        stopSelf();
    }

    // ---------------------------------------------------------------- foreground / notification

    private void goForeground() {
        try {
            // plain 2-arg startForeground: the mediaPlayback type comes from the
            // manifest declaration (required on API 29+, ignored below)
            startForeground(NOTIF_ID, build());
        } catch (Throwable ignored) { }
    }

    /** paused: detach the notification so it STAYS but becomes swipeable */
    private void detachForeground() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }

    private PendingIntent pi(int cmd) {
        Intent i = new Intent(this, MediaControlService.class);
        i.putExtra(CMD, cmd);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, cmd, i, flags);
    }

    private PendingIntent dismissPi() {
        Intent i = new Intent(this, MediaControlService.class);
        i.setAction(ACTION_DISMISS);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, 99, i, flags);
    }

    private Notification build() {
        Intent open = new Intent(this, BrowserActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int oflags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent content = PendingIntent.getActivity(this, 10, open, oflags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, BoltApp.CH_MEDIA)
                .setSmallIcon(R.drawable.ic_notify_media)
                .setContentTitle(title == null || title.isEmpty() ? getString(R.string.app_name) : title)
                .setContentText(domain == null ? "" : domain)
                .setOngoing(playing)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(content)
                // v1.3.18: video thumbnail — v1.3.22+ artwork channels: a
                // FRESH COPY per post goes to the large icon (per-post parcel
                // copy = safe channel on every version) and another to the
                // session metadata (below) — see artCopy().
                // v1.4.21: THE THREE-CHANNEL LAW — largeIcon, METADATA_KEY_ART
                // and METADATA_KEY_ALBUM_ART each take their OWN artCopy()
                // (three independent instances per publish). Sharing one
                // instance across the two metadata keys was the v1.4.20
                // crash's true shape: SystemUI recycles one processed
                // bitmap and the twin key then parcels a RECYCLED bitmap.
                .setLargeIcon(artCopy())
                // swiping the (paused) notification dismisses it and pauses media
                .setDeleteIntent(dismissPi())
                // v1.3.39 FIELD FIX ("video notification is not showing the
                // buttons to controll media … but do not appear"): the action
                // icons were theme-tinted vectors (?attr/colorTint) — SystemUI
                // renders notification actions in ITS OWN process/theme where
                // that attr cannot resolve, so every button painted BLANK while
                // the tap targets kept working. Notification icons must be
                // self-colored: ic_ntf_* are the same paths hardcoded WHITE.
                .addAction(R.drawable.ic_ntf_prev, getString(R.string.back), pi(CMD_REWIND))
                .addAction(playing ? R.drawable.ic_ntf_pause : R.drawable.ic_ntf_play,
                        getString(R.string.media_controls), pi(CMD_TOGGLE))
                .addAction(R.drawable.ic_ntf_next, getString(R.string.forward), pi(CMD_FORWARD))
                .addAction(R.drawable.ic_ntf_close, getString(R.string.stop), pi(CMD_CLOSE));
        try {
            b.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(android.support.v4.media.session.MediaSessionCompat.Token
                            .fromToken(session.getSessionToken()))
                    .setShowActionsInCompactView(0, 1, 2));
        } catch (Throwable ignored) { }
        return b.build();
    }

    private void notifyUi() {
        try {
            publishSessionState();
            // v1.3.29 repost gate: nm.notify() re-parcels RemoteViews (incl.
            // a fresh artCopy() bitmap) and makes SystemUI relayout — v1.3.28
            // re-posted on every state event, ≈1 Hz during mix/SPA metadata
            // churn, all while backgrounded. Repost only when
            // notification-VISIBLE content changed (play/pause icon, title,
            // domain, seekbar total, artwork), plus a 5-minute keepalive
            // repost while playing (self-heal should SystemUI ever drop it).
            boolean changed = (playing != lastNotifPlaying)
                    || !android.text.TextUtils.equals(title == null ? "" : title, lastNotifTitle)
                    || !android.text.TextUtils.equals(domain == null ? "" : domain, lastNotifDomain)
                    || durMs != lastNotifDurMs
                    || artGen != lastNotifArtGen;
            long now = SystemClock.elapsedRealtime();
            if (changed || (playing && now - lastPostAt >= 300_000)) {
                lastNotifPlaying = playing;
                lastNotifTitle = title == null ? "" : title;
                lastNotifDomain = domain == null ? "" : domain;
                lastNotifDurMs = durMs;
                lastNotifArtGen = artGen;
                lastPostAt = now;
                nm.notify(NOTIF_ID, build());
            }
        } catch (Throwable ignored) { }
        syncWakeLock();
    }

    // ---------------------------------------------------------------- v1.3.18 artwork (+ v1.3.22 hardening)

    /**
     * v1.3.22 — never hand the system a poisoned bitmap.
     *
     * SystemUI (any OEM build) may recycle artwork it pulled out of the
     * MediaSession metadata. Re-posting that recycled instance in a later
     * notification update is a Bad-notification APP CRASH (the field-reported
     * "RemoteServiceException: Bad notification(id=42) ... Couldn't inflate
     * contentViews ... The given region must intersect with the Bitmap's
     * dimensions" — the same Android-10 family androidx/media issue #3118
     * and Firefox fenix#18400 hit). If our bitmap was recycled behind our
     * back, DROP it and clear artUrl so the next page state report re-fetches
     * a fresh one instead of crash-looping. Returns null when there is no
     * usable artwork.
     */
    private Bitmap safeArt() {
        Bitmap b = artBitmap;
        if (b == null) return null;
        try {
            if (!b.isRecycled() && b.getWidth() > 0 && b.getHeight() > 0) return b;
        } catch (Throwable ignored) { }
        artBitmap = null;
        artUrl = "";                     // allow a re-fetch of a fresh instance
        return null;
    }

    /** v1.3.27 — fresh copy of the artwork (null when none/degenerate).
     *  SystemUI's media pipeline RECYCLES bitmaps it processes (androidx/media
     *  #3118 family, fenix #18400); handing out a fresh copy per publish —
     *  instead of the master instance — means a bitmap the system killed
     *  behind our back can never be re-parceled by us. That copy discipline
     *  is what makes the metadata artwork channel safe to reopen on ALL
     *  Android versions below 13 too (the v1.3.22 SDK>=33 gate saved the
     *  crashes but also killed the thumbnail on the field-reported Android
     *  10: its SystemUI renders media art from the session METADATA, and the
     *  builder large icon alone shows nothing there).
     *  v1.4.20: on API <= 29 the copy also rides the artwork CAP — see
     *  api29ArtCap(); a copy that cannot be made safe returns null and the
     *  notification goes out TEXT-ONLY (no artwork beats an app kill).
     *  v1.4.21 — THE GUARANTEED LEG: on API <= 29 artwork leaves this
     *  service AT ALL only when the user opted in (the Show media artwork
     *  setting, default OFF there — three artwork fixes failed to stop the
     *  process kills: the shared-instance recycle root-caused this round
     *  rides the per-channel copies, and the OFF default guarantees the
     *  user's device). Suppression logs once per artwork generation so the
     *  next diag.log names the door. */
    private Bitmap artCopy() {
        if (!artworkAllowed()) {
            if (safeArt() != null) logArtSuppressed();
            return null;
        }
        Bitmap b = safeArt();
        if (b == null) return null;
        try {
            Bitmap out = b.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            return api29SafeArt(out);
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.21 — the artwork master-switch resolver. PURE in its inputs
     *  (pref, API level): the default is OFF on API <= 29 (the SystemUI
     *  artwork-recycle family lives there — the user's Android 10 died
     *  three times over it) and ON above. A user who opts in accepts the
     *  risk knowingly; the per-channel fresh copies (v1.4.21) + the size
     *  cap (v1.4.20) keep even that path as guarded as it can be. */
    public static boolean artworkDefaultOn(int sdkInt) { return sdkInt >= 30; }

    /** v1.4.21 — is ANY artwork allowed to leave the service right now? */
    private boolean artworkAllowed() {
        try {
            return com.bolt.browser.util.Prefs.get(this).mediaArtwork();
        } catch (Throwable t) {
            return artworkDefaultOn(android.os.Build.VERSION.SDK_INT);
        }
    }

    /** v1.4.21 — the suppression breadcrumb: artwork EXISTS but the API-29
     *  gate holds it back. Once per artwork generation (the rate the
     *  60-second diag cadence tolerates — a per-publish line would spam the
     *  1 Hz state beats). */
    private void logArtSuppressed() {
        try {
            long gen = artGen;
            if (gen == lastArtSuppressedGen) return;
            lastArtSuppressedGen = gen;
            com.bolt.browser.util.Diag.log(this,
                    "art suppressed api<=29 gen=" + gen + " (settings: media artwork)");
        } catch (Throwable ignored) {}
    }

    private long lastArtSuppressedGen = -1;

    /**
     * v1.4.20 — the API<=29 artwork cap (the crash that survived the 1px
     * shrink).
     *
     * FIELD ROOT CAUSE (Realme RMX2020, SDK 29, diag.log): media autoplay
     * (scrolling Google image/video results) posted artwork whose dimensions
     * disagreed with what that OEM's SystemUI crop pipeline expected —
     * "RemoteServiceException: Bad notification(tag=null, id=42) ... Couldn't
     * inflate contentViews ... The given region must intersect with the
     * Bitmap's dimensions" — killing the whole process, repeatedly. The
     * exception is posted back by the system ASYNC, so no try/catch around
     * nm.notify() can ever catch it: the BITMAP must be made safe before it
     * leaves the process. The v1.3.22/27 guards (fresh ARGB_8888 copy + the
     * 1px avoidApi29SysUiSize shrink) reduce but do NOT eliminate the
     * failure — a 1px miss is still a near-fatal size, and OEM crops vary.
     *
     * The cure, per the v1.4.20 field order: every artwork that leaves this
     * service on API <= 29 is SMALL (cap below), validated (non-null,
     * non-recycled, positive dimensions, ARGB_8888), and a whole margin
     * below the device's fatal 1/6-screen sizes — and the moment anything
     * cannot be guaranteed, the artwork is dropped and the notification
     * goes out text-only. Pure resolver (probe-pinned): given the device's
     * largest fatal size, the longest-edge cap for all artwork.
     */
    static int api29ArtCap(int fatalMax) {
        if (fatalMax <= 0) return 256;              // unknown device: plain cap
        int cap = Math.min(256, fatalMax - 48);     // a whole margin below fatal
        return Math.max(cap, 64);                   // paranoid floor
    }

    /**
     * v1.4.20 — validate + cap one artwork bitmap for API <= 29. Returns a
     * bitmap whose longest edge is within api29ArtCap, config ARGB_8888,
     * dimensions positive, never recycled — or null when in doubt (the
     * caller then ships text-only). On API > 29 this is the identity (the
     * copy discipline alone has kept those versions crash-free).
     */
    private Bitmap api29SafeArt(Bitmap b) {
        try {
            if (b == null || b.isRecycled() || b.getWidth() <= 0 || b.getHeight() <= 0)
                return null;
            if (Build.VERSION.SDK_INT > 29) return b;
            int[] fatal = avoidArtSizes();
            int fatalMax = 0;
            for (int f : fatal) if (f > fatalMax) fatalMax = f;
            int cap = api29ArtCap(fatalMax);
            int w = b.getWidth(), h = b.getHeight();
            if (Math.max(w, h) > cap) {
                int nw = Math.max(1, (int) Math.round(w * (cap / (double) Math.max(w, h))));
                int nh = Math.max(1, (int) Math.round(h * (cap / (double) Math.max(w, h))));
                Bitmap s = Bitmap.createScaledBitmap(b, nw, nh, true);
                if (s != b) {
                    try { b.recycle(); } catch (Throwable ignored) { }
                    b = s;
                }
            }
            if (b == null || b.isRecycled() || b.getWidth() <= 0 || b.getHeight() <= 0)
                return null;
            if (b.getConfig() != android.graphics.Bitmap.Config.ARGB_8888) {
                Bitmap c = b.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                if (c != b) {
                    try { b.recycle(); } catch (Throwable ignored) { }
                    b = c;
                }
            }
            if (b == null || b.isRecycled() || b.getConfig() != android.graphics.Bitmap.Config.ARGB_8888) {
                com.bolt.browser.util.Diag.log(this, "art drop unsafe");
                return null;
            }
            return b;
        } catch (Throwable t) {
            com.bolt.browser.util.Diag.log(this, "art drop exc");
            return null;
        }
    }

    /**
     * v1.3.22 — artwork sizes that crash Android 10 (API 29) SystemUI.
     *
     * API 29 SystemUI resizes incoming media-session artwork to 1/6 of the
     * longest screen dimension. When the artwork's larger dimension is
     * EXACTLY that size, Bitmap.createScaledBitmap returns the same instance,
     * SystemUI recycles it, and the app crashes on the next notification
     * inflation. Google's own workaround (androidx/media #3118, commit
     * 2d454f5, "Fix SysUI crash/device reboot issue on API 29 for specific
     * bitmap size") shrinks the offending dimension by one pixel — we mirror
     * it, including their display-size computation (getSize + getRealSize +
     * the rotated variant), and apply it to every artwork that leaves this
     * service on API <= 29 (the Palette-based color extraction of that era is
     * crash-prone for media bitmaps in general — fenix#18400, SO 54948936).
     */
    private int[] avoidArtSizes() {
        if (Build.VERSION.SDK_INT > 29) return new int[0];
        if (avoidArtSizes != null) return avoidArtSizes;
        try {
            Display d = getSystemService(WindowManager.class).getDefaultDisplay();
            Point cur = new Point();
            d.getSize(cur);
            Point real = new Point();
            d.getRealSize(real);
            // rotated-orientation variant, computed exactly like androidx/media
            Point rot = new Point(
                    real.y - (real.x - cur.x),
                    real.x - (real.y - cur.y));
            avoidArtSizes = new int[]{
                    Math.max(cur.x / 6, cur.y / 6),
                    Math.max(rot.x / 6, rot.y / 6)};
        } catch (Throwable t) {
            avoidArtSizes = new int[0];
        }
        return avoidArtSizes;
    }

    /** shrink by one pixel when a dimension equals a SysUI-fatal size */
    private Bitmap avoidApi29SysUiSize(Bitmap b) {
        try {
            if (b == null || b.isRecycled()) return b;
            int[] avoid = avoidArtSizes();
            int w = b.getWidth(), h = b.getHeight();
            if (avoid.length == 0 || w <= 1 || h <= 1) return b;
            if (Math.max(w, h) != avoid[0] && (avoid.length < 2 || Math.max(w, h) != avoid[1]))
                return b;
            if (w == avoid[0] || (avoid.length >= 2 && w == avoid[1])) w--;
            if (h == avoid[0] || (avoid.length >= 2 && h == avoid[1])) h--;
            Bitmap scaled = Bitmap.createScaledBitmap(b, w, h, true);
            if (scaled != b) {
                try { b.recycle(); } catch (Throwable ignored) { }
            }
            return scaled;
        } catch (Throwable t) {
            return b;
        }
    }

    /** Fetch + attach the artwork of the playing video. Runs the download on a
n     *  single background thread (8 s timeouts, ≤4 MB, downsampled to ≤512 px on
     *  the longest edge); the result is applied on the ui thread and the
     *  notification + session metadata are re-published. A stale fetch (video
     *  switched again mid-download) is dropped via the generation counter.
     *  data: URIs are decoded directly (some players inline poster frames).
     *  v1.3.22: the decode result is additionally checked for degenerate
     *  dimensions and, on API <= 29, moved off the SysUI-fatal 1/6-screen
     *  sizes before it is ever published anywhere. */
    private void fetchArt(final String url) {
        artUrl = url;
        final int gen = ++artGen;
        if (artEx == null) artEx = Executors.newSingleThreadExecutor();
        final String ua =
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36";
        try {
            artEx.execute(() -> {
                Bitmap b = null;
                try {
                    byte[] data = loadArtBytes(url, ua);
                    if (data != null && data.length > 0) b = decodeSampled(data, 512);
                    if (b != null && !b.isRecycled() && b.getWidth() > 0 && b.getHeight() > 0)
                        b = avoidApi29SysUiSize(b);
                    else b = null;
                } catch (Throwable ignored) { }
                final Bitmap bmp = b;
                ui.post(() -> {
                    if (gen != artGen) return;         // stale fetch
                    // v1.4.20: the MASTER itself rides the API<=29 cap, so
                    // both publish channels (large icon + session metadata)
                    // start from a small, validated bitmap; a fetch that
                    // cannot be made safe is DROPPED — text-only beats the
                    // async Bad-notification process kill.
                    Bitmap safe = api29SafeArt(bmp);
                    if (safe == null) {
                        com.bolt.browser.util.Diag.log(this,
                                "art cap drop gen=" + gen);
                        return;
                    }
                    artBitmap = safe;
                    // force a session-metadata re-publish (metaChanged dedup
                    // would otherwise swallow the new ART bitmap)
                    lastPublishedTitle = null;
                    notifyUi();
                });
            });
        } catch (Throwable ignored) { }
    }

    /** raw bytes for an artwork URL — http(s) via HttpURLConnection (follows
     *  redirects) or inline base64 data: URIs. Bounded to 4 MB. */
    private static byte[] loadArtBytes(String url, String ua) {
        try {
            if (url.startsWith("data:")) {
                int c = url.indexOf(',');
                if (c > 0 && url.substring(0, c).contains("base64")) {
                    return android.util.Base64.decode(url.substring(c + 1),
                            android.util.Base64.DEFAULT);
                }
                return null;
            }
            if (!url.startsWith("http:") && !url.startsWith("https:")) return null;
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", ua);
            con.setRequestProperty("Accept", "image/*,*/*;q=0.8");
            try (InputStream in = con.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 4 * 1024 * 1024) return null;  // sanity bound
                }
                return out.toByteArray();
            } finally {
                try { con.disconnect(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** decode with inSampleSize so the longest edge is ≤ maxEdge (keeps the
     *  notification update cheap; YouTube thumbs are 480x360 already). */
    private static Bitmap decodeSampled(byte[] data, int maxEdge) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxEdge) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.3.63 — force overload: onSeekTo must push its optimistic position
     *  through the change gates (no play-state flip, no jump vs the frozen
     *  lastPosPublished, possibly <60 s since the last publish — all three
     *  gates would have swallowed the very publish that keeps the dragged
     *  playhead from snapping back). */
    private void publishSessionState() { publishSessionState(false); }

    private void publishSessionState(boolean force) {
        try {
            if (session == null) return;
            boolean republishState = lastPublishedPlaying != playing;
            if (republishState) lastPublishedPlaying = playing;
            long pos = posMs;
            if (playing) {
                pos = posMs + (long) ((SystemClock.elapsedRealtime() - posAt) * rate);
            }
            // v1.3.29 binder discipline: setPlaybackState is an IPC into the
            // media-session system service, and every publish re-renders
            // SystemUI's media control. v1.3.28 published on EVERY 1 Hz beat
            // (3600 IPC/hour) for a seekbar that interpolates by itself from
            // (position, speed, updateTime). Publish only when the play state
            // changed, the position JUMPED vs the extrapolation (a seek the
            // interpolation cannot smooth over), or 60 s of drift needs
            // re-syncing.
            long now = SystemClock.elapsedRealtime();
            boolean jumped = lastPosPublished >= 0 && (
                    playing
                            ? Math.abs(pos - (lastPosPublished
                                    + (long) ((now - lastPublishAt) * rate)))
                            : Math.abs(pos - lastPosPublished)) > 2500;
            if (force || republishState || jumped || (now - lastPublishAt) >= 60_000) {
                lastPublishAt = now;
                lastPosPublished = pos;
                session.setPlaybackState(new PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY
                                | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE
                                | PlaybackState.ACTION_REWIND
                                | PlaybackState.ACTION_FAST_FORWARD
                                | PlaybackState.ACTION_SEEK_TO
                                | PlaybackState.ACTION_STOP
                                // v1.3.39: Android 13+'s SystemUI media
                                // carousel renders its OWN prev/next buttons
                                // from the SKIP_* action bits — without them the
                                // buttons exist but never light up.
                                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                                | PlaybackState.ACTION_SKIP_TO_NEXT)
                        .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                                pos, playing ? rate : 0f)
                        .build());
            }
            boolean metaChanged = !android.text.TextUtils.equals(
                    (title == null || title.isEmpty()) ? "" : title, lastPublishedTitle)
                    || !android.text.TextUtils.equals(domain == null ? "" : domain, lastPublishedDomain)
                    // v1.3.28: duration is part of the metadata the seekbar is
                    // drawn from — a late-arriving duration must re-publish
                    // (the notification used to keep the previous video's
                    // total while the next one played)
                    || durMs != lastPublishedDurMs;
            if (metaChanged) {
                lastPublishedTitle = (title == null || title.isEmpty()) ? "" : title;
                lastPublishedDomain = domain == null ? "" : domain;
                lastPublishedDurMs = durMs;
                MediaMetadata.Builder mb = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE,
                                (title == null || title.isEmpty()) ? getString(R.string.app_name) : title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, domain == null ? "" : domain)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs);
                // v1.3.18: artwork on the session metadata — what MediaStyle
                // notifications render (all Android versions; the pre-13 ones
                // too — that is the channel the user's Android 10 actually
                // displays, restored in v1.3.27).
                // v1.3.22 — THE FIX for the YouTube/notification crash: bitmap
                // metadata was restricted to Android 13+ because SystemUI
                // recycles what it processes on API <= 29 ("Couldn't inflate
                // contentViews / region must intersect the Bitmap's dimensions"
                // — androidx/media #3118; fenix #18400). v1.3.27 reopens the
                // channel on ALL versions with the poisoning made impossible:
                // artCopy() hands SystemUI a fresh copy (never our master), the
                // decode-time 1px shrink keeps the SysUI-fatal 1/6-screen sizes
                // closed (avoidApi29SysUiSize, Google's own fix mirrored), and
                // safeArt() still drops any master that somehow got recycled.
                // v1.4.21 — THE SHARED-INSTANCE KILL (root-caused from the
                // v1.4.20 diag.log): the OLD shape parked the SAME artCopy()
                // instance into BOTH METADATA_KEY_ART and METADATA_KEY_ALBUM_
                // ART. API-29 SystemUI recycles one processed bitmap; the
                // other key then holds a RECYCLED bitmap and the next
                // notification inflation throws "The given region must
                // intersect with the Bitmap's dimensions" — the async
                // RemoteServiceException that killed the process. Every
                // artwork channel now takes its OWN fresh copy (three
                // independent instances per publish — see build() for the
                // large icon) so no channel can ever hold what another was
                // handed. And per artworkAllowed(): on API <= 29 the whole
                // artwork pipeline is OFF unless the user opts in (stability
                // beats album art after three failed artwork fixes).
                Bitmap art = artCopy();
                if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
                Bitmap art2 = artCopy();
                if (art2 != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art2);
                session.setMetadata(mb.build());
            }
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- v1.3.27 kill forensics

    /**
     * 60 s heartbeat while playing: a diag line with the process's LIVE
     * oom_score_adj (the importance class the kernel actually sees — the
     * number that decides who lmkd shoots first) plus a filesDir mark.
     *
     * WHY: the v1.3.26 field report ("music died while I read a PDF; no
     * crash logs; notification + video gone") left ZERO evidence — a
     * whole-process kill writes nothing anywhere. The heartbeat mark
     * outlives the process, so the next app start turns it into hard data:
     * "died ~Ns after the last beat, while playing <title> at Xs". If the
     * last beats show adj~0-200 (perceptible/foreground) the kill came from
     * an OEM battery manager or extreme pressure; if adj had decayed to
     * ~500+ the service itself was already gone — a different bug. Either
     * way the NEXT field report becomes conclusive.
     */
    private void startKeepAlive() {
        ui.removeCallbacks(keepAlive);
        ui.postDelayed(keepAlive, 60_000);
    }

    private void stopKeepAlive() {
        ui.removeCallbacks(keepAlive);
    }

    // ---------------- v1.3.33 position heartbeat ----------------

    /** v1.3.33 — the 15 s position mark. WHY: the death heartbeat (which
     *  carries the position the phoenix resumes from) was written only by
     *  the 60 s keepalive beat, so a kill 59 s into a beat resumed the video
     *  up to a MINUTE back — the user relistens a chunk after every
     *  auto-resume. posMs itself is fresh to ~1 s (the page's 1 Hz beats),
     *  so simply writing the mark more often fixes it: at most 15 s of
     *  replay. Deliberately does NOT log (60 s diag cadence stays) and does
     *  NOT re-arm the watchdog alarm — a fresher alarm would push post-kill
     *  detection LATER (see WATCHDOG_MS). Cost: ~4 tiny file writes a
     *  minute while audio plays — noise next to the renderer itself. */
    private final Runnable posBeat = this::posBeatRun;

    private void startPosBeat() {
        ui.removeCallbacks(posBeat);
        ui.postDelayed(posBeat, 15_000);
    }

    private void stopPosBeat() {
        ui.removeCallbacks(posBeat);
    }

    private void posBeatRun() {
        if (!playing) return;                     // chain ends with playback
        try { writeHeartbeat(); } catch (Throwable ignored) {}
        ui.postDelayed(posBeat, 15_000);
    }

    private void keepAliveBeat() {
        if (!playing) {
            // v1.3.28: keep the beat chain (and the alarm watchdog re-arm)
            // alive through the background autoplay-transition window — a
            // buffering stall must never let the liveness alarm lapse while
            // the next queued video is still expected.
            if (bgTransition) {
                try { ensureWatchdog(); } catch (Throwable ignored) {}
                ui.postDelayed(keepAlive, 60_000);
            }
            return;
        }
        try {
            long up = (SystemClock.elapsedRealtime() - svcStartedAt) / 1000;
            boolean bo = com.bolt.browser.util.BackgroundSurvival.isIgnoring(this);
            com.bolt.browser.util.Diag.log(this, "keepalive up=" + up + "s"
                    + " pos=" + (posMs / 1000) + "/" + (durMs / 1000) + "s"
                    + " adj=" + adjText()
                    + " bo=" + (bo ? "whitelisted" : "optimizable")
                    + " bg=" + appBg
                    + " conceal=" + (concealed ? "on" : (concealLatched ? "off(latched)" : "off"))
                    + (phoenixActive ? " phoenix=born" : ""));
            writeHeartbeat();
            ensureWatchdog();          // v1.3.28: keep the liveness alarm fresh
        } catch (Throwable ignored) {}
        ui.postDelayed(keepAlive, 60_000);
    }

    /** the process's importance as the kernel scores it ("?" when the ROM
     *  blocks the read). ~0-200 = foreground/perceptible (a kill there is
     *  extreme pressure or an OEM killer); ~500+ = the service was already
     *  gone and the process had decayed to cacheable — different bug. */
    private static String adjText() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/oom_score_adj"))) {
            String s = r.readLine();
            return s == null ? "?" : s.trim();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** filesDir mark: "<wall-ms>|<pos-ms>|<dur-ms>|<title-ish>|<bo>|<ph>|<url>".
     *  Deleted the moment playback cleanly stops — so its mere PRESENCE at
     *  the next app start means the previous process died WHILE PLAYING (it
     *  never got to run the cleanup path).
     *  v1.3.30: bo/ph flags + the media tab's RESUME URL ride along — the
     *  phoenix needs the exact reload target, and the kill evidence can now
     *  say "died with battery optimization ON" (the silently-reset
     *  whitelist case the v1.3.29 field log caught). Old 4-field marks parse
     *  defensively. */
    private void writeHeartbeat() {
        try {
            String t = title == null ? "" : title;
            if (t.length() > 80) t = t.substring(0, 80);
            boolean bo = com.bolt.browser.util.BackgroundSurvival.isIgnoring(this);
            String url = "";
            try {
                Tab mt = resolveMediaTab();
                if (mt != null) url = TabManager.resumeUrl(mt);
            } catch (Throwable ignored) {}
            com.bolt.browser.util.Fx.writeText(
                    new java.io.File(getFilesDir(), "media_heartbeat"),
                    System.currentTimeMillis() + "|" + posMs + "|" + durMs
                            + "|" + t.replace('\n', ' ')
                            + "|" + (bo ? 1 : 0)
                            + "|" + (phoenixActive ? 1 : 0)
                            + "|" + (url == null ? "" : url));
        } catch (Throwable ignored) {}
    }

    private void clearHeartbeat() {
        try {
            //noinspection ResultOfMethodCallIgnored
            new java.io.File(getFilesDir(), "media_heartbeat").delete();
        } catch (Throwable ignored) {}
    }

    /**
     * Called once at app start (BoltApp.onCreate): turns a leftover heartbeat
     * into evidence + a diag line. Returns the human line (also kept in
     * {@link #lastDeathEvidence} for BrowserActivity's survival dialog) or
     * null when the previous session closed cleanly / the mark is stale.
     */
    public static String checkPrevDeath(Context c) {
        try {
            java.io.File f = new java.io.File(c.getFilesDir(), "media_heartbeat");
            if (!f.exists()) return null;
            String s = com.bolt.browser.util.Fx.readText(f);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\\|", -1);
            if (p.length < 4) return null;
            long wall = 0;
            try { wall = Long.parseLong(p[0].trim()); } catch (Throwable ignored) {}
            long ageMs = wall > 0 ? System.currentTimeMillis() - wall : -1;
            String t = p[3];
            long pos = 0, dur = 0;
            try { pos = Long.parseLong(p[1].trim()); } catch (Throwable ignored) {}
            try { dur = Long.parseLong(p[2].trim()); } catch (Throwable ignored) {}
            if (ageMs < 0 || ageMs > 30L * 60L * 1000L) {
                com.bolt.browser.util.Diag.logSync(c, "PROC_KILL stale heartbeat (age="
                        + (ageMs / 1000) + "s) — not treated as a kill");
                return null;
            }
            // v1.3.30 extended fields (old 4-field marks keep the defaults)
            boolean bo = true, ph = false;
            String deathUrl = "";
            try {
                if (p.length > 4) bo = "1".equals(p[4].trim());
                if (p.length > 5) ph = "1".equals(p[5].trim());
                if (p.length > 6 && p[6] != null) deathUrl = p[6].trim();
            } catch (Throwable ignored) {}
            lastDeathBo = bo;
            lastDeathPh = ph;
            lastDeathUrl = deathUrl == null ? "" : deathUrl;
            lastDeathPosMs = pos;
            String ev = "Bolt was stopped by the system ~" + (ageMs / 1000)
                    + "s after its last playback heartbeat, while playing"
                    + (t.isEmpty() ? " media" : " \u201C" + t + "\u201D")
                    + " (at " + (pos / 1000) + "s"
                    + (dur > 0 ? " of " + (dur / 1000) + "s" : "") + ")"
                    + (!bo ? " — battery optimization was ON (the whitelist had "
                            + "silently reset; the kill went straight through it)" : "")
                    + ".";
            com.bolt.browser.util.Diag.logSync(c, "PROC_KILL " + ev
                    + " No Java crash was logged — this was the OS/OEM killer "
                    + "(lmkd under extreme pressure or the manufacturer's "
                    + "battery manager), not a Bolt bug.");
            lastDeathEvidence = ev;
            lastDeathAt = System.currentTimeMillis();
            return ev;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- v1.3.30 phoenix

    /**
     * Attempt the automatic playback resume. Returns true when a phoenix
     * attempt is now scheduled/in flight (the caller keeps the service up);
     * false when it does not apply (the caller falls back to the legacy
     * retire / notification paths).
     *
     * Guards, in order:
     *  - already playing / a phoenix already in flight — nothing to do;
     *  - no PROC_KILL evidence, or older than 5 min — nothing to resume;
     *  - no resume URL in the death heartbeat (pre-v1.3.30 mark) — the
     *    one-tap notification path stays the recovery;
     *  - background play disabled by the user — respect it;
     *  - the app is in the foreground — BrowserActivity's own restore (the
     *    field-proven &t= reload) owns this case;
     *  - rate caps: max 2 auto-resumes per rolling hour, 20 s minimum
     *    between attempts — a sustained kill storm degrades to the
     *    one-tap notification instead of a kill/restart loop;
     *  - system memory below ~300 MB free — feeding a thrashing system a
     *    fresh renderer is asking for a second kill; retry a few times,
     *    then fall back.
     */
    private boolean tryPhoenix(String trigger) {
        try {
            if (playing || phoenixWeb != null || phoenixArmed) return false;
            String ev = lastDeathEvidence;
            long at = lastDeathAt;
            String url = lastDeathUrl == null ? "" : lastDeathUrl;
            if (ev == null || ev.isEmpty() || at <= 0
                    || System.currentTimeMillis() - at > PHOENIX_EVIDENCE_FRESH_MS) return false;
            if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                try {
                    com.bolt.browser.util.Diag.log(this, "phoenix skip "
                            + "(" + trigger + ") — no resume URL in the death mark");
                } catch (Throwable ignored) {}
                return false;
            }
            if (!com.bolt.browser.util.Prefs.get(this).bgPlay()) return false;
            if (BoltApp.isAppForeground()) return false;
            // rolling hourly window
            com.bolt.browser.util.Prefs p = com.bolt.browser.util.Prefs.get(this);
            long now = System.currentTimeMillis();
            long win = p.i("phoenixWin", 0);
            int cnt = p.i("phoenixCnt", 0);
            if (now - win > 3_600_000L) { win = now; cnt = 0; }
            long lastAt = p.i("phoenixLastAt", 0);
            if (cnt >= PHOENIX_MAX_PER_HOUR || now - lastAt < PHOENIX_MIN_GAP_MS) {
                try {
                    com.bolt.browser.util.Diag.log(this, "phoenix skip (" + trigger
                            + ") — rate cap (" + cnt + "/h, last "
                            + ((now - lastAt) / 1000) + "s ago); one-tap "
                            + "notification stays the recovery");
                } catch (Throwable ignored) {}
                return false;
            }
            p.set("phoenixWin", win);
            p.set("phoenixCnt", cnt + 1);
            p.set("phoenixLastAt", now);
            phoenixMemRetries = 0;
            try {
                com.bolt.browser.util.Diag.log(this, "phoenix armed (" + trigger
                        + "; kill " + ((now - at) / 1000) + "s ago, host="
                        + com.bolt.browser.util.Fx.hostOf(url) + ", attempt "
                        + (cnt + 1) + "/" + PHOENIX_MAX_PER_HOUR + " this hour)");
            } catch (Throwable ignored) {}
            // foreground NOW: the process must hold FGS grade while the resume
            // loads (a plain background service is adj 500+ — the most
            // killable class, exactly what we are recovering from)
            goForeground();
            ui.removeCallbacks(phoenixStart);
            ui.postDelayed(phoenixStart, PHOENIX_DELAY_MS);
            phoenixArmed = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** the delay elapsed (killer app had time to settle): memory-gate, find
     *  the tab, build the headless WebView, load the resume URL. */
    private void phoenixStartRun() {
        phoenixArmed = false;                     // the scheduled attempt has fired
        try {
            if (playing || phoenixWeb != null) return;               // something else took over
            if (BoltApp.isAppForeground()) {                         // user came back — the
                phoenixCancelTimers();                               // activity's restore owns it
                try {
                    com.bolt.browser.util.Diag.log(this, "phoenix abort — app back in "
                            + "foreground; BrowserActivity restore owns recovery");
                } catch (Throwable ignored) {}
                stopEverything();
                return;
            }
            // memory gate: do not feed a thrashing system
            try {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                ActivityManager am = getSystemService(ActivityManager.class);
                if (am != null) {
                    am.getMemoryInfo(mi);
                    long availMb = mi.availMem / (1024L * 1024L);
                    if (availMb < PHOENIX_MIN_AVAIL_MB && phoenixMemRetries < 3) {
                        phoenixMemRetries++;
                        try {
                            com.bolt.browser.util.Diag.log(this, "phoenix wait — only "
                                    + availMb + "MB free (retry " + phoenixMemRetries + "/3)");
                        } catch (Throwable ignored) {}
                        ui.postDelayed(phoenixStart, 20_000);
                        return;
                    }
                    if (availMb < PHOENIX_MIN_AVAIL_MB) {
                        phoenixGiveUp("memory still critical (" + availMb + "MB free)");
                        return;
                    }
                }
            } catch (Throwable ignored) {}
            phoenixLoad();
        } catch (Throwable t) {
            phoenixGiveUp("start error: " + t);
        }
    }

    /** build the headless WebView for the media tab and load the resume URL. */
    private void phoenixLoad() {
        try {
            String url = lastDeathUrl == null ? "" : lastDeathUrl;
            if (url.isEmpty()) { phoenixGiveUp("no resume URL"); return; }
            TabManager tm = TabManager.get(this);
            // find the tab the dead session was playing (match on the URL
            // with the &t= position param stripped — the persisted tab url
            // and the heartbeat url differ only in that param)
            int idx = -1;
            Tab t;
            synchronized (tm.tabs) {   // v1.4.9: the thread law — the phoenix runs off the UI thread
                String bare = stripPosParam(url);
                for (int i = 0; i < tm.tabs.size(); i++) {
                    Tab tt = tm.tabs.get(i);
                    if (tt == null || tt.url == null || tt.isHome) continue;
                    if (stripPosParam(tt.url).equals(bare)) { idx = i; break; }
                }
                t = idx >= 0 ? tm.tabs.get(idx) : null;
            }
            if (t == null) {
                phoenixGiveUp("media tab not found in the saved session");
                return;
            }
            if (t.web != null) {
                // the tab already has a live WebView (activity raced us back
                // to life) — do not build a second one
                phoenixGiveUp("tab already has a live WebView");
                return;
            }
            PhoenixBridge bridge = new PhoenixBridge(idx);
            WebView w = t.buildWeb(getApplicationContext(), new PhoenixClient(idx),
                    new android.webkit.WebChromeClient(), bridge, false);
            // v1.4.19 — buildWeb's construction guard can refuse (return
            // null) instead of crashing; the phoenix gives up cleanly, the
            // same as any other failed build
            if (w == null) { phoenixGiveUp("web build refused"); return; }
            t.holdsMedia = true;      // after a successful build — never leave a stale flag on a failed one
            phoenixWeb = w;
            phoenixIdx = idx;
            phoenixActive = true;
            mediaTabIdx = idx;                       // notification cmds target this tab
            // the never-attached lie: a detached WebView keeps its LAST known
            // window visibility — the field-proven zombie-playback effect.
            // A freshly built one has none, so dispatch VISIBLE explicitly
            // (the same dispatch concealNow() uses, in the honest direction)
            // before the page ever gets a chance to see itself hidden.
            try { w.dispatchWindowVisibilityChanged(View.VISIBLE); } catch (Throwable ignored) {}
            try {
                com.bolt.browser.util.Diag.log(this, "phoenix load — headless resume of "
                        + com.bolt.browser.util.Fx.hostOf(url) + " at t="
                        + (lastDeathPosMs / 1000) + "s");
            } catch (Throwable ignored) {}
            w.loadUrl(url);
            ui.removeCallbacks(phoenixNudge);
            ui.removeCallbacks(phoenixTimeout);
            ui.postDelayed(phoenixNudge, PHOENIX_NUDGE_MS);
            ui.postDelayed(phoenixTimeout, PHOENIX_TIMEOUT_MS);
        } catch (Throwable t) {
            phoenixGiveUp("load error: " + t);
        }
    }

    /** 9 s in, still not playing: give the page an explicit play command
     *  through the normal command path (it resumes the WebView first).
     *  v1.3.33: REPEATS every 7 s until the timeout — a single 18 s nudge
     *  meant a page whose renderer wasn't ready yet sat silent for the whole
     *  remaining window. Each repeat is one no-op command when already
     *  playing (guarded below). */
    private void phoenixNudgeRun() {
        if (playing || phoenixWeb == null) return;
        try {
            com.bolt.browser.util.Diag.log(this, "phoenix nudge — explicit play command");
        } catch (Throwable ignored) {}
        sendCmd("play", null);
        ui.removeCallbacks(phoenixNudge);
        ui.postDelayed(phoenixNudge, PHOENIX_NUDGE_REPEAT_MS);
    }

    /** 35 s in, still not playing: the site refused headless playback —
     *  post the one-tap recovery notification and retire. */
    private void phoenixTimeoutRun() {
        if (playing || phoenixWeb == null) return;
        phoenixGiveUp("no playback within " + (PHOENIX_TIMEOUT_MS / 1000) + "s");
    }

    private void phoenixCancelTimers() {
        ui.removeCallbacks(phoenixStart);
        ui.removeCallbacks(phoenixNudge);
        ui.removeCallbacks(phoenixTimeout);
        phoenixArmed = false;
    }

    /** clean end of a failed phoenix attempt: destroy the headless WebView,
     *  post the v1.3.28-style one-tap recovery notification, retire. */
    private void phoenixGiveUp(String why) {
        phoenixCancelTimers();
        try {
            com.bolt.browser.util.Diag.log(this, "phoenix give-up (" + why
                    + ") — one-tap recovery notification posted instead");
        } catch (Throwable ignored) {}
        if (phoenixWeb != null) {
            try {
                Tab t = tabAt(phoenixIdx);
                if (t != null && t.web == phoenixWeb) {
                    t.web = null;
                    t.mediaPlaying = false;
                    t.holdsMedia = false;
                }
            } catch (Throwable ignored) {}
            try { phoenixWeb.stopLoading(); } catch (Throwable ignored) {}
            try { phoenixWeb.loadUrl("about:blank"); } catch (Throwable ignored) {}
            try { phoenixWeb.destroy(); } catch (Throwable ignored) {}
            phoenixWeb = null;
        }
        phoenixActive = false;
        if (mediaTabIdx == phoenixIdx) mediaTabIdx = -1;
        phoenixIdx = -1;
        String ev = lastDeathEvidence;
        if (ev != null && !ev.isEmpty()) {
            try { MediaWatchdog.postRecoveryNotification(this, ev); } catch (Throwable ignored) {}
        }
        stopEverything();
    }

    private Tab tabAt(int idx) {
        try {
            TabManager tm = TabManager.get(this);
            synchronized (tm.tabs) {   // v1.4.9: the thread law — bounds check + get atomically
                if (idx >= 0 && idx < tm.tabs.size()) return tm.tabs.get(idx);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** "https://m.youtube.com/watch?v=X&t=42s" -> "https://m.youtube.com/watch?v=X" */
    private static String stripPosParam(String u) {
        if (u == null) return "";
        try {
            String s = java.util.regex.Pattern
                    .compile("([?&])(t|start)=([^&#]*)").matcher(u).replaceAll("");
            if (s.endsWith("?") || s.endsWith("&")) s = s.substring(0, s.length() - 1);
            return s;
        } catch (Throwable t) {
            return u;
        }
    }

    /**
     * v1.3.30 — the phoenix's page bridge. The activity's JsBridge is UI-
     * scoped; this carries ONLY what background playback needs: media events
     * (into the service's own handler, same instance) plus the tab-flag
     * bookkeeping the activity's bridge normally does. When the user returns
     * and the activity shows this tab, showTab() rewires the WebView to the
     * activity's own clients — the phoenix bridge then simply goes quiet.
     */
    private class PhoenixBridge {

        private final int idx;
        private final android.content.Context appCtx;

        PhoenixBridge(int idx) {
            this.idx = idx;
            this.appCtx = getApplicationContext();
        }

        @android.webkit.JavascriptInterface
        public void mediaEvent(final String name, final String json) {
            ui.post(() -> {
                try {
                    Tab t = tabAt(idx);
                    if (t != null && t.web != null) {
                        if ("state".equals(name)) {
                            org.json.JSONObject o = json != null
                                    ? new org.json.JSONObject(json) : new org.json.JSONObject();
                            boolean playing = o.optBoolean("playing", false);
                            t.mediaPlaying = playing;
                            double pp = o.optDouble("pos", -1);
                            if (pp >= 0) t.mediaPosSec = pp;
                            if (playing || o.optDouble("dur", 0) > 0) t.holdsMedia = true;
                        } else if ("time".equals(name)) {
                            double pp = json != null
                                    ? new org.json.JSONObject(json).optDouble("pos", -1) : -1;
                            if (pp >= 0) t.mediaPosSec = pp;
                        }
                    }
                    // static router, NOT this.handleEvent: after the activity
                    // adopts the WebView this bridge may outlive the service
                    // instance that built it — the static path revives or
                    // re-targets the service exactly like the activity bridge
                    // does, instead of talking to a dead instance.
                    MediaControlService.onMediaEvent(appCtx, idx, name, json);
                } catch (Throwable ignored) {}
            });
        }

        @android.webkit.JavascriptInterface
        public void notify(String msg) { /* not needed headless */ }
    }

    /**
     * v1.3.30 — the phoenix's WebViewClient: injects the MediaHook core
     * (media events + the background spoof — the page must believe it is
     * visible from the first document), tracks the tab URL (SPA-safe), and
     * routes requests through the adblock engine (no ad blast out of a
     * background resume). Replaced wholesale by BrowserActivity's BoltClient
     * when the user returns and the tab is shown.
     */
    private class PhoenixClient extends WebViewClient {

        private final int idx;

        PhoenixClient(int idx) { this.idx = idx; }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            // headless: never chase external apps — non-web schemes die here
            return url != null && !url.startsWith("http:") && !url.startsWith("https:");
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            try { view.evaluateJavascript(
                    com.bolt.browser.media.MediaHook.core(
                            com.bolt.browser.util.Prefs.get(MediaControlService.this)), null);
            } catch (Throwable ignored) {}
            Tab t = tabAt(idx);
            if (t != null && url != null) { t.url = url; t.isHome = false; }
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            try { view.evaluateJavascript(
                    com.bolt.browser.media.MediaHook.core(
                            com.bolt.browser.util.Prefs.get(MediaControlService.this)), null);
            } catch (Throwable ignored) {}
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            super.doUpdateVisitedHistory(view, url, isReload);
            if (url == null) return;
            Tab t = tabAt(idx);
            if (t == null || url.equals(t.url)) return;
            t.url = url;
            t.isHome = false;
            try { TabManager.get(MediaControlService.this).scheduleSave(); } catch (Throwable ignored) {}
        }

        @Override
        public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                android.net.Uri u = request.getUrl();
                String url = u.toString();
                if (!url.startsWith("http")) return null;
                String pageHost = com.bolt.browser.util.Fx.hostOf(
                        view.getUrl() != null ? view.getUrl() : "");
                String reqHost = u.getHost();
                if (reqHost == null) return null;
                com.bolt.browser.util.Prefs p = com.bolt.browser.util.Prefs.get(MediaControlService.this);
                String base = com.bolt.browser.util.Fx.baseDomain(pageHost);
                if (p.siteAdblock(base) == 1) { /* site forces blocking */ }
                else if (!p.adblockEnabled()) return null;
                else if (p.siteBool(base, "noAds", false)) return null;
                else if (p.siteAdblock(base) == 0) return null;
                if (inferType(request) == com.bolt.browser.adblock.FilterEngine.Type.DOCUMENT) return null;
                java.util.List<String> siteRules = p.siteFilters(base);
                if (com.bolt.browser.adblock.FilterEngine.get()
                        .blocked(url, pageHost, inferType(request), siteRules)) {
                    return new android.webkit.WebResourceResponse("text/plain", "utf-8",
                            new java.io.ByteArrayInputStream(new byte[0]));
                }
            } catch (Throwable ignored) {}
            return null;
        }

        private com.bolt.browser.adblock.FilterEngine.Type inferType(WebResourceRequest req) {
            try {
                if (req.isForMainFrame()) return com.bolt.browser.adblock.FilterEngine.Type.DOCUMENT;
                String path = req.getUrl().getPath();
                if (path == null) return com.bolt.browser.adblock.FilterEngine.Type.OTHER;
                String q = path.toLowerCase();
                if (q.endsWith(".js") || q.endsWith(".mjs")) return com.bolt.browser.adblock.FilterEngine.Type.SCRIPT;
                if (q.endsWith(".css")) return com.bolt.browser.adblock.FilterEngine.Type.STYLESHEET;
                if (q.endsWith(".png") || q.endsWith(".jpg") || q.endsWith(".jpeg")
                        || q.endsWith(".gif") || q.endsWith(".webp") || q.endsWith(".svg")
                        || q.endsWith(".ico") || q.endsWith(".bmp") || q.endsWith(".avif"))
                    return com.bolt.browser.adblock.FilterEngine.Type.IMAGE;
                if (q.endsWith(".woff") || q.endsWith(".woff2") || q.endsWith(".ttf")
                        || q.endsWith(".otf") || q.endsWith(".eot"))
                    return com.bolt.browser.adblock.FilterEngine.Type.FONT;
                if (q.endsWith(".mp4") || q.endsWith(".webm") || q.endsWith(".mp3")
                        || q.endsWith(".m4a") || q.endsWith(".aac") || q.endsWith(".ogg")
                        || q.endsWith(".wav") || q.endsWith(".m3u8") || q.endsWith(".ts")
                        || q.endsWith(".mov"))
                    return com.bolt.browser.adblock.FilterEngine.Type.MEDIA;
            } catch (Throwable ignored) {}
            return com.bolt.browser.adblock.FilterEngine.Type.OTHER;
        }

        @Override
        public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            try {
                if (view == phoenixWeb) phoenixGiveUp("renderer died during resume");
            } catch (Throwable ignored) {}
            return true;   // the web is destroyed; do not crash the process
        }
    }

    // ---------------------------------------------------------------- v1.3.28 transition + watchdog

    /** the old pause cleanup, factored out so both the normal pause path and
     *  the expired autoplay-transition path share it. */
    private void retireFromPlaying() {
        releaseWakeLock();
        stopKeepAlive();
        stopPosBeat();                                // v1.3.33
        clearHeartbeat();
        detachForeground();
        unconceal();                                   // v1.3.29: nothing playing — no reason to stay hidden
    }

    /** the background autoplay-transition window closed with no next video:
     *  fall back to the exact v1.3.27 paused semantics (swipeable
     *  notification, idle retirement, heartbeat cleared). */
    private void transitionExpired() {
        if (playing || !bgTransition) return;
        bgTransition = false;
        try {
            com.bolt.browser.util.Diag.log(this, "svc transition expired (no next "
                    + "video in " + (BG_TRANSITION_MS / 1000) + "s) — retiring");
        } catch (Throwable ignored) {}
        retireFromPlaying();
        notifyUi();
        maybeIdle();
    }

    /** v1.3.28 — the alarm watchdog. AOSP (android-10 OomAdjuster) pins a
     *  backgrounded mediaPlayback FGS at PERCEPTIBLE_APP_ADJ=200 — the exact
     *  class lmkd shoots when the device thrashes at critical pressure
     *  ("do not kill perceptible apps UNLESS thrashing at critical levels",
     *  lmkd.cpp), and realme's own RAM cleaner sits on top of that. No legal
     *  in-app lever moves adj below 200 — so on this hardware a kill WILL
     *  land sometimes; the fix is to make it cost ONE TAP instead of a silent
     *  dead phone: a 45 s setExactAndAllowWhileIdle alarm, re-armed on every
     *  beat while playing, that survives the process itself. If the process
     *  dies, the alarm still fires, the receiver sees fresh PROC_KILL
     *  evidence and posts "Playback interrupted — tap to resume" (the
     *  v1.3.27 &t= restore machinery does the rest, field-proven). Alarms
     *  registered with the system are dropped only on force-stop — an lmkd
     *  kill leaves them armed. */
    private static PendingIntent watchdogPi(Context c) {
        Intent i = new Intent(c, MediaWatchdog.class);
        i.setAction(ACTION_WATCHDOG);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, 77, i, flags);
    }

    public static void armWatchdog(Context c) {
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am == null) return;
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + WATCHDOG_MS, watchdogPi(c));
        } catch (Throwable t) {
            try {
                AlarmManager am = c.getSystemService(AlarmManager.class);
                if (am != null) am.set(AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + WATCHDOG_MS, watchdogPi(c));
            } catch (Throwable ignored) { }
        }
    }

    public static void cancelWatchdog(Context c) {
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am != null) am.cancel(watchdogPi(c));
        } catch (Throwable ignored) { }
        try { watchdogPi(c).cancel(); } catch (Throwable ignored) { }
    }

    private void ensureWatchdog() {
        armWatchdog(this);
        if (!watchdogLogged) {
            watchdogLogged = true;
            try {
                com.bolt.browser.util.Diag.log(this, "watchdog armed (95s liveness "
                        + "alarm — survives the process; never fires while healthy "
                        + "(each 60s beat re-arms past it); recovery notification "
                        + "if Bolt is killed while playing)");
            } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------- v1.3.15 background focus + guard

    private void markBackgrounded() {
        appBg = true;
        bgEnteredAt = SystemClock.elapsedRealtime();
        userPausedSinceBg = false;
        wasPlayingAtBg = playing;
        rotAt = 0;                                 // v1.4.7: a fresh background period has no rotation yet
        // v1.3.16: engage IMMEDIATELY — same main-loop turn as the last
        // activity's onStop. The engine decides within milliseconds whether
        // backgrounded media may keep playing and consults app-held audio
        // focus; v1.3.15's 700 ms-late take meant the engine had already
        // paused, and the guard only resurrected it ~1.3 s later — the
        // audible "slight pause before background playback starts". If media
        // is not live yet (nothing playing, nothing loaded) the +700 ms
        // backstop (engageBackground) still catches late starters.
        if (playing || durMs > 0) {
            acquireBgFocus();
            armGuard();
        }
        // v1.3.29: leaving the app with live playback starts the concealment
        // clock — 15 s of seamless handoff, then the renderer learns the truth
        if (playing) scheduleConceal();
    }

    private void engageBackground() {
        if (!appBg) return;
        if (playing || durMs > 0) {
            acquireBgFocus();
            // v1.3.16: backstop only — never reset a live guard (that would
            // restart its attempt budget mid-flight); DO re-arm one that
            // exhausted itself, and arm fresh if markBackgrounded() skipped
            // (no live media at background time).
            if (!guardArmed) armGuard();
        }
    }

    private void markForegrounded() {
        // v1.3.16 dip-heal: a QUICK return (rotation / recreate dip / brief
        // glance) inside the window where we held background focus. If media
        // was playing when the app left and is paused now, the most likely
        // cause is our own focus take displacing the engine's focus (or the
        // engine's background pause landing right before the return) — undo
        // it with one play command. Explicit user pauses set
        // userPausedSinceBg and are never resurrected; ended media
        // (lastNone) and never-loaded media (durMs) are excluded too.
        boolean heal = appBg && bgFocus
                && SystemClock.elapsedRealtime() - bgEnteredAt < 1500
                && wasPlayingAtBg && !playing && !userPausedSinceBg
                && !lastNone && durMs > 0;
        appBg = false;
        disarmGuard();
        abandonBgFocus();
        unconceal();                                   // v1.3.29: back in front — the system's own
        // VISIBLE dispatch + this restore the renderer's full priority
        if (heal) sendCmd("play", null);
    }

    /** Hold audio focus while backgrounded — this is what makes Android treat
     *  the app as a real background media app so the engine keeps playing (the
     *  load-bearing flag v1.3.14 removed). The listener is deliberately a
     *  no-op: the engine reacts to actual focus changes itself, and OUR
     *  reacting to them is exactly what started the v1.3.13 pause war. */
    private void acquireBgFocus() {
        if (bgFocus || audio == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (bgReq == null) {
                    bgReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                    .build())
                            .setOnAudioFocusChangeListener(noOpFocus)
                            .build();
                }
                audio.requestAudioFocus(bgReq);
            } else {
                audio.requestAudioFocus(noOpFocus, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
            bgFocus = true;
        } catch (Throwable ignored) { }
    }

    private void abandonBgFocus() {
        if (!bgFocus || audio == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && bgReq != null) audio.abandonAudioFocusRequest(bgReq);
            else audio.abandonAudioFocus(noOpFocus);
        } catch (Throwable ignored) { }
        bgFocus = false;
    }

    /** Bounded resume-guard: if the engine pauses media AFTER the app was
     *  backgrounded (timestamp-discriminated) and no user pause command was
     *  dispatched since, reverse it — up to 3 attempts in the first ~3 s,
     *  never an unbounded war. v1.3.16: the first check moved to 300 ms
     *  (v1.3.15's 600/1800/3500 schedule left a worst-case ~1.3 s audible
     *  gap between the engine's background pause and the resurrection). */
    private void armGuard() {
        guardArmed = true;
        guardAttempts = 0;
        ui.removeCallbacks(bgGuard);
        ui.postDelayed(bgGuard, 300);
        ui.postDelayed(bgGuard, 800);
        ui.postDelayed(bgGuard, 1600);
        ui.postDelayed(bgGuard, 3000);
    }

    private void disarmGuard() {
        guardArmed = false;
        ui.removeCallbacks(bgGuard);
    }

    private void bgGuardCheck() {
        if (!guardArmed || !appBg) return;
        if (playing) return;                          // still / again playing
        if (userPausedSinceBg) return;                // the user (or headset) paused
        if (lastNone) return;                         // media ENDED — no resurrect
        // v1.4.7 — THE ROTATION BASE: a display rotation while backgrounded
        // re-opened this window (see onDisplayMaybeRotated); the newest of
        // bgEnteredAt / rotAt decides which pauses are transition noise.
        long base = Math.max(bgEnteredAt, rotAt);
        // v1.3.16: the guard only reverses the TRANSITION pause (an engine
        // pause landing within ~1.5 s of the base event). A pause that lands
        // LATER is a real event — a phone call or another app taking focus —
        // and resurrecting against it would be wrong; the user taps play when
        // they want it back.
        long dt = lastPauseReportAt - base;
        if (dt < 0) return;                           // pause predates the base = user's own
        if (dt > 1500) return;                        // late pause (call etc.) — leave it paused
        // v1.4.7 — THE FOCUS-LOSS VETO: a call or another app's audio takes
        // the focus BEFORE the engine pauses; a pause that lands while the
        // focus is lost (or within ~250 ms of losing it) is a REAL event,
        // rotation or not — never resurrect against it.
        if (focusLostAt > focusGainedAt
                || (focusLostAt > 0 && base - focusLostAt < 250
                    && lastPauseReportAt >= focusLostAt)) return;
        if (durMs <= 0) return;                       // nothing real was ever loaded
        if (guardAttempts >= 3) { guardArmed = false; return; }
        guardAttempts++;
        sendCmd("play", null);
    }

    // ---------------------------------------------------------------- becoming-noisy

    private class BecomingNoisy extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            dispatch(CMD_PAUSE); // headphones unplugged
        }
    }

    private void registerNoisy() {
        if (!noisyRegistered) {
            try {
                noisy = new BecomingNoisy();
                registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
                noisyRegistered = true;
            } catch (Throwable ignored) { }
        }
    }

    private void unregisterNoisy() {
        if (noisyRegistered && noisy != null) {
            try { unregisterReceiver(noisy); } catch (Throwable ignored) { }
            noisyRegistered = false;
        }
    }

    /** CPU awake while media plays with the screen off — without it the
     *  renderer stalls minutes into background playback. v1.3.28: also held
     *  through the background autoplay-transition window (the next queued
     *  video's player timers must keep running or the gap becomes a stall). */
    private void syncWakeLock() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm == null) return;
            boolean needWl = playing || bgTransition;
            if (needWl && wl == null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bolt:media");
                wl.setReferenceCounted(false);
                // v1.3.27: 12 h cap — long Mix/playlist sessions outgrew the
                // old 4 h one (the lock quietly expiring mid-session stalled
                // screen-off playback hours in).
                wl.acquire(12 * 60 * 60 * 1000L);
            } else if (!needWl && wl != null) {
                try { wl.release(); } catch (Throwable ignored) { }
                wl = null;
            }
        } catch (Throwable ignored) { }
    }

    private void releaseWakeLock() {
        try { if (wl != null) { wl.release(); wl = null; } } catch (Throwable ignored) { }
    }

    private void acquireWakeLock() { syncWakeLock(); }
}
