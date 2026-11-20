package com.bolt.browser.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.app.SearchManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebViewDatabase;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bolt.browser.R;
import com.bolt.browser.adblock.FilterEngine;
import com.bolt.browser.core.DownloadEngine;
import com.bolt.browser.core.Tab;
import com.bolt.browser.core.TabManager;
import com.bolt.browser.media.MediaControlService;
import com.bolt.browser.media.MediaHook;
import com.bolt.browser.scripts.UserscriptEngine;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.PasskeyCompat;
import com.bolt.browser.util.Prefs;
import com.bolt.browser.util.Ui;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BrowserActivity extends AppCompatActivity {

    protected Prefs prefs;
    protected TabManager tm;
    protected boolean incognito = false;

    protected FrameLayout flRoot, flContent;
    protected ImageButton btnSiteSettings, btnReload, btnMore, btnBack, btnForward,
            btnHome, btnMenu, btnFindPrev, btnFindNext, btnFindClose,
            btnTtsPlay, btnTtsStop, fabMedia;
    /** v1.3.35: the tabs button IS the count square (tv_tab_count) — a
     *  TextView, not an ImageButton (the old 4-square icon + overlaid
     *  number was the "number overlaps the borders" bug). */
    protected View btnTabs;
    protected TextView tvUrl, tvTabCount, tvFindCount, tvTtsStatus;
    protected ImageView ivLock;
    protected View vProgress;
    protected LinearLayout findbar, ttsbar;
    protected EditText etFind;
    protected View homeView;
    /** v1.3.53 — the Fx.homeGen value the CURRENT homeView was built at.
     *  A favicon landing / wallpaper-style change bumps homeGen; showTab
     *  rebuilds the home when it is next shown with a stale stamp, and
     *  onResume refreshes it when the home is the visible tab. This is the
     *  replacement for the old (v1.3.39–v1.3.52) "favicon bumps skinGen"
     *  channel, which RECREATED the whole activity on the next
     *  pause+resume and reloaded every page — the aggressive-reload field
     *  report. */
    protected int homeShownGen = Fx.homeGen;
    protected android.widget.ScrollView homeScroll;
    protected final List<View> sdCells = new ArrayList<>();
    protected View sdGhost, sdOverlay;
    protected int sdDragSrc = -1;
    protected boolean sdDragMoved;
    /** v1.3.90 — the finger's last raw position on any tile (see
     *  tilePressFx); the drag ghost lifts from HERE, not from the cell. */
    protected float sdLastX, sdLastY;
    /** v1.3.90 — the constant grab LIFT: the ghost floats this far above the
     *  finger so the hand never covers the flying icon (the launcher grab
     *  contract); tracking stays 1:1 underneath. */
    protected int sdLiftDp = 14;
    /** v1.3.90 — the insertion index the live gap preview is showing (-1 =
     *  none); tracked so the shift work only runs when the gap MOVES. */
    protected int sdGapShown = -2;
    /** v1.3.92 — THE PAGED MATRIX (the field spec: "a 6*7 matrix of the
     * icons filling left to right. When it goes over 6*7, make the whole
     * matrix able to be swiped horizontally to access more icons by
     * scrolling right or left with option to customise right or left for
     * left handed and right handed person"). The pager holds one
     * SdGridLayout page per 42 shortcuts; the handedness setting flips
     * its layout direction (RTL = the first page sits on the RIGHT, the
     * pages extend leftward — the left-handed layout). */
    protected androidx.viewpager2.widget.ViewPager2 sdPager;
    /** v1.3.92 — THE DWELL-TIMER GROUP (the field spec: "use the tabs logic
     * of having to drag and hold B in center of A to merge and create
     * group, use the tabs animation of like the time counter to tell to
     * group here as well"). The v1.3.91 release-gated merge is GONE: a
     * release inside the center zone could still surprise-merge (the
     * field kept reading accidental merges as deletions). The tabs
     * contract instead: hovering the finger in a tile's CENTER arms a
     * progress pill that fills over 1000 ms — the merge fires the moment
     * it fills (mid-hold, never at release), and ANY movement out of the
     * zone cancels it. A drop is ALWAYS a plain reorder now; grouping
     * can only happen through the completed timer, so nothing a drop
     * does can ever read as a deletion. */
    protected int sdFolderTarget = -1;
    protected int sdDwellProgress = 0;
    protected TextView sdDwellPill;
    protected android.graphics.drawable.ClipDrawable sdDwellClip;
    /** the edge-advance direction (+1 = toward the visually-next page) —
     * recorded when the edge timer is armed; the pager's RTL flag flips
     * the mapping (see sdDragMove). */
    protected int sdEdgeDir = 0;
    /** v1.3.88 — drag origin (the 16 dp drift test) + the ghost's half-size,
     * captured by beginSdDrag for the shared move/end handlers. */
    protected float sdOriginX, sdOriginY;
    protected int sdGhostHalf = 24;
    /** v1.3.97 — POSITION-TRUE REBUILD snapshots (see refreshHomeQuiet): the
     * pager page + home scroll a drag-driven refreshHome must land back on,
     * so a reorder drop / a dwell merge never reads as "the home page
     * refreshed" (the v1.3.96 field report). -1/0 = nothing to restore. */
    protected int homeRestorePage = -1;
    protected int homeRestoreScroll = 0;
    /** v1.3.97 — true while the drag was started from the tile menu's Move
     * row: the hold-menu must NOT re-arm over a menu-started drag (the user
     * already chose Move; the still moment of grabbing the tile again is
     * placement, not a menu request). Reset in endSdDrag. */
    protected boolean sdDragNoMenu;
    /** v1.4.0 — true while the dragged tile is a FOLDER (a speeddial group):
     *  a folder drag is REORDER-ONLY ("let the folders be draggable, only
     *  draggable to rearrange order, nothing else") — the dwell-merge never
     *  arms, so a folder can never merge into a tile or another folder.
     *  Set in beginSdDrag, reset in endSdDrag. */
    protected boolean sdDragSrcIsGroup;
    /** v1.4.0 — the live launcher folder window (showSdFolderWindow): the
     *  scrim that closes it on outside taps, plus the panel itself; both
     *  ride flRoot like the drag ghost so they float over the whole chrome. */
    protected View sdFolderScrim;
    protected LinearLayout sdFolderWin;
    /** v1.4.4 — THE FOLDER-WINDOW ICON DRAG (the report: "make the home page
     *  shortcuts icons in the groups to be draggable to rearrange too").
     *  The launcher-grid law (v1.3.88) once more: the cell keeps its touch
     *  stream after the hold lifts, so one listener owns tap (open) /
     *  hold-still (the item menu) / hold+move (rearrange inside the window,
     *  Prefs.moveSpeeddialItem + an in-place grid rebuild — no re-anchor
     *  pop). The window is a single live surface, so the state lives on the
     *  activity like sdFolderWin itself. */
    protected View sdWinDragCell;
    protected int sdWinFolderIdx = -1, sdWinDragFrom = -1, sdWinDragTo = -1;
    protected boolean sdWinDragMoved, sdWinHeld;
    protected float sdWinDownX, sdWinDownY, sdWinLastX, sdWinLastY, sdWinLiftX, sdWinLiftY;
    protected final Handler sdWinH = new Handler();
    protected Runnable sdWinLiftRun;
    protected View sdWinLiftCandidate, sdWinRimmed;
    protected android.widget.GridLayout sdWinGrid;
    /** v1.3.98 — THE ARMED CELL (the pager-clash fix, see beginSdDrag): the
     * no-intercept flag must be raised on the CELL that owns the in-flight
     * touch stream, because requestDisallowInterceptTouchEvent propagates
     * UP the parent chain — and the view that steals horizontal drags for
     * paging is ViewPager2's INNER RecyclerView, which sits BELOW the
     * ViewPager2 itself. Cleared in endSdDrag. */
    protected View sdDragCell;

    protected final Handler ui = new Handler(Looper.getMainLooper());
    protected boolean inspectorOn = false;
    protected boolean mediaPlaying = false;
    protected boolean mediaBoostable = false;
    /** the tab that LAST reported playing media — notification commands and
     *  media state must target THIS tab, not the active one (a background
     *  tab can be the one playing audio; targeting activeTab paused nothing
     *  and made background playback feel "finicky"). */
    protected Tab mediaTab;
    /** v1.3.21: renderer-crash auto-repair is a once-per-PROCESS event — the
     *  Chromium cache wipe is only the response to a crash LOOP (3 deaths of
     *  the same tab in 30 s), never to a single glitch. */
    protected static boolean sRendererRepairDone = false;
    protected View customView;
    protected WebChromeClient.CustomViewCallback customCallback;
    protected PermissionRequest pendingPermRequest;
    protected TextToSpeech tts;
    protected boolean ttsReady, ttsPaused;
    protected List<String> ttsChunks = new ArrayList<>();
    protected int ttsIdx = 0;
    protected int themeHash = 0;
    /** v1.3.37 — the OTHER recreate channel: Fx.skinGen is bumped by
     *  urlBarBottom / wallpaper changes (things that change LAYOUT, not
     *  theme colors the themeHash already covers). v1.3.36 bumped it but
     *  nothing consumed it — the bottom-address-bar toggle only applied
     *  after a process restart; this closes that gap. */
    protected int seenGen = Fx.skinGen;
    protected int shownTabIndex = -1;   // tab currently attached to flContent (avoids re-attach churn)
    /** v1.3.51 — the tab OBJECT currently attached to flContent. Indexes
     *  LIE after a workspace switch (WorkspacesActivity.doSwitch replaces
     *  the whole list in place): the new space's active index can equal the
     *  OLD one, and the index-based "same tab" checks then kept showing
     *  the previous space's page (or a blank strip) until some later event
     *  forced a re-attach — the "workspace changes don't appear instant"
     *  field report. Identity is by OBJECT, everywhere it matters. */
    protected Tab shownTabRef = null;
    /** v1.3.29: last applied renderer-policy signature — applyRendererPolicies
     *  is called on every media event; identical vectors are skipped (IPC + diag). */
    protected String lastPolicySig = null;
    protected android.app.Dialog sheetDialog;      // open bottom-sheet menu (main/site settings)
    protected View quickPanel;                     // floating quick-settings overlay
    protected View quickScrim;                     // tap-to-close scrim behind quickPanel
    /** v1.3.31 — the pending WebView file-picker callback. MUST be answered
     *  exactly once (with null on cancel) or Chromium refuses to ever open
     *  the chooser again on that page ("Duplicate showFileChooser result"). */
    protected android.webkit.ValueCallback<android.net.Uri[]> fileUploadCallback;
    /** v1.3.31 — the OTP fill chip: shown while a 2FA input is focused and a
     *  matching authenticator account exists; tap = fill the page's field. */
    protected TextView otpChip;
    protected org.json.JSONObject otpChipEntry;   // the entry the chip offers
    protected long otpChipShownAt = 0;            // auto-retire after 30 s
    protected boolean activityBackground = false;  // true between onStop..onStart
    protected boolean userExit = false;             // set when exiting via the power button path
    /** v1.3.79 — THE UNIVERSAL FAVICON EXTRACTOR (injected once per host
     *  per session at onPageFinished; see BrowserClient.onPageFinished).
     *  The page collects its own icon candidates from the DOM (icon,
     *  shortcut icon, apple-touch-icon, mask-icon; SVGs outrank bitmaps
     *  because the browser's SVG renderer rasterizes them crisp at any
     *  size — that is the one thing BitmapFactory can never do, and it is
     *  why z.ai's logo.svg never landed through any existing path), fetches
     *  the bytes (CORS-clean fetch, same-origin <img> fallback), draws the
     *  best one into a 96px canvas and hands the PNG data URL to
     *  Bolt.favIcon. A tainted canvas (cross-origin without CORS) throws
     *  and the next candidate gets its turn; total failure reports
     *  Bolt.favMiss so the injection never repeats for the session. */
    private static final String FAV_EXTRACT_JS =
        "(function(){try{" +
        "function miss(){try{Bolt.favMiss(location.host);}catch(e){}}" +
        "var ls=document.querySelectorAll('link[rel~=\"icon\"],link[rel=\"apple-touch-icon\"],link[rel=\"shortcut icon\"],link[rel=\"mask-icon\"]');" +
        "var cs=[];" +
        "for(var i=0;i<ls.length;i++){try{" +
        "var href=ls[i].getAttribute('href');if(!href)continue;" +
        "var u=new URL(href,location.href);" +
        "if(u.protocol!=='http:'&&u.protocol!=='https:'&&u.protocol!=='data:')continue;" +
        "var sz=(ls[i].getAttribute('sizes')||'').toLowerCase();var px=0;" +
        "if(sz.indexOf('any')>=0)px=512;else{var m=sz.match(/(\\d+)x(\\d+)/);if(m)px=parseInt(m[1],10);}" +
        "var ty=(ls[i].getAttribute('type')||'').toLowerCase();" +
        "var svg=ty.indexOf('svg')>=0||/\\.svg($|\\?)/i.test(u.pathname);" +
        "var sc=px+(svg?1000:0);" +
        "if((ls[i].getAttribute('rel')||'').indexOf('apple-touch')>=0)sc+=60;" +
        "cs.push({u:u.href,sc:sc});}catch(e){}}" +
        "cs.sort(function(a,b){return b.sc-a.sc;});" +
        "var seen={},list=[];" +
        "for(var j=0;j<cs.length&&list.length<4;j++){if(seen[cs[j].u])continue;seen[cs[j].u]=1;list.push(cs[j]);}" +
        "if(!list.length){miss();return;}" +
        "var idx=0;" +
        "function send(bmp){var S=96;var cv=document.createElement('canvas');cv.width=S;cv.height=S;" +
        "var cx=cv.getContext('2d');" +
        "var bwd=bmp.width||S,bh=bmp.height||S;" +
        "var k=Math.min(S/bwd,S/bh);var w=bwd*k,h=bh*k;" +
        "cx.drawImage(bmp,(S-w)/2,(S-h)/2,w,h);" +
        "var out=cv.toDataURL('image/png');" +
        "try{Bolt.favIcon(location.host,out);}catch(e){miss();}}" +
        "function viaImg(url){var b=new Image();" +
        "b.onload=function(){try{if(!b.width&&!b.height){next();return;}send(b);}catch(e){next();}};" +
        "b.onerror=function(){next();};b.src=url;}" +
        "function next(){if(idx>=list.length){miss();return;}var c=list[idx++];" +
        "try{fetch(c.u,{mode:'cors',credentials:'omit'}).then(function(r){if(!r.ok)throw 0;return r.blob();})" +
        ".then(function(bl){var o=URL.createObjectURL(bl);var b=new Image();" +
        "b.onload=function(){try{URL.revokeObjectURL(o);if(!b.width&&!b.height){next();return;}send(b);}catch(e){next();}};" +
        "b.onerror=function(){try{URL.revokeObjectURL(o);}catch(e){}next();};b.src=o;})" +
        ".catch(function(){viaImg(c.u);});}catch(e){viaImg(c.u);}}" +
        "next();" +
        "}catch(e){try{Bolt.favMiss(location.host);}catch(x){}}})();";

    // ---- v1.3.77 cache-guard loops (the storage law: under 50 MB total,
    // ideally under 40 while using, around 20 after closing) ----
    /** v1.3.77 — THE FOREGROUND AUDIT LOOP: a heavy video session never
     *  pauses the app, so an onResume-only audit could not see the caches
     *  grow mid-session. Every 3 minutes while resumed the guard
     *  re-measures (its own 2-minute rate limit dedupes a just-fired
     *  trim). Cancelled in onStop. */
    protected final Runnable cacheAuditTick = new Runnable() {
        @Override public void run() {
            try {
                android.webkit.WebView cw = null;
                synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
                    for (Tab t : tm.tabs) if (t.web != null) { cw = t.web; break; }
                }
                com.bolt.browser.util.CacheGuard.check(BrowserActivity.this, false, cw);
            } catch (Throwable ignored) {}
            // v1.3.79: 3 -> 6 minutes — every tick walks thousands of cache
            // files on disk (a recurring wakeup that keeps the device out of
            // deep idle; the battery report). The exit/startup sweeps still
            // bound everything; this loop only catches long live sessions.
            ui.postDelayed(this, 6 * 60 * 1000L);
        }
    };
    /** v1.3.77 — THE DELAYED CLOSE-OUT SWEEP: five minutes after the
     *  browser leaves the screen (the swipe-away case keeps the process
     *  alive with no onDestroy ever coming), every cache subtree is
     *  emptied physically — "around 20 MB after closing" must hold even
     *  when death arrives without a callback. Cancelled by onResume. */
    protected final Runnable cacheBgSweep = new Runnable() {
        @Override public void run() {
            try { com.bolt.browser.util.CacheGuard.exit(BrowserActivity.this); }
            catch (Throwable ignored) {}
        }
    };
    /** v1.3.27: armed in onStop when the app leaves with live background media
     *  and no battery-optimization whitelist — consumed by onResume to show
     *  the one-time background-survival offer (BackgroundSurvival). */
    protected boolean pendingBoOffer = false;
    protected final List<String> scriptMenuCmds = new ArrayList<>(); // GM_registerMenuCommand names

    // ---- v1.3.38 new-feature state ----
    /** the omnibox field while the sheet is up (a voice result fills it). */
    protected EditText activeOmniField;
    /** v1.4.12 — the omnibox image-search door: the file the camera app is
     *  writing into (ACTION_IMAGE_CAPTURE with EXTRA_OUTPUT — full-res
     *  capture needs the FileProvider path, not the data-extra thumbnail). */
    protected File vsearchCapture;
    /** the password fill chip (mirrors the OTP chip). */
    protected TextView pwChip;
    protected final Runnable pwChipRetire = this::hidePwChip;
    /** selection-actions host, built once (BoltWeb hands it every ActionMode). */
    protected com.bolt.browser.util.SelectionActions.Host selHost;
    /** true while the browser sits in the system picture-in-picture window. */
    protected boolean inPip = false;

    // ---- v1.3.61 fullscreen-desktop parity ----
    /** v1.3.61 — TRUE while a (pseudo-)fullscreen video owns the screen WITHOUT
     *  the app holding a customView (the page maximized its player by CSS —
     *  YouTube desktop's fallback). Bolt's floating chrome must never ride it. */
    protected boolean pseudoFullscreen = false;
    /** the WebView the fullscreen-entry scroll was captured from (exit-time
     *  restore — the renderer's DidEnterFullscreen zeroes the visual-viewport
     *  offset, so Bolt puts the reader back where they fullscreened). */
    protected WebView fsScrollWeb = null;
    /** scroll position saved at fullscreen entry, restored on exit. */
    protected int fsSavedScrollX = 0, fsSavedScrollY = 0;
    /** v1.3.61 — the fullscreen video's aspect (W/H) if known (videoWidth x
     *  videoHeight probed at fullscreen entry); Rational.ONE when unknown. */
    protected android.util.Rational fsVideoAspect = null;
    /** v1.3.91 — TRUE between onShowCustomView and the aspect probe's
     *  answer (or its 650 ms fallback): the orientation is LOCKED in the
     *  user's CURRENT posture until the video's own aspect decides the
     *  rotation (see fsEnterAfterAspect — the Shorts-flicker contract). */
    protected boolean fsAwaitAspect = false;
    /** v1.3.61 — heals are debounced per WebView: a heal probe is only useful
     *  when a NEW play may have armed the player's fullscreen button. */
    protected long lastFsHealAt = 0L;
    /** v1.3.62 — the WebView carrying the CSS-rescue class for a WEDGED
     *  fullscreen enter (see fsSettleProbe); null when no rescue is live. */
    protected WebView fsSettleWeb = null;
    /** v1.3.64 — the in-PiP pin watchdog beat (see onPictureInPictureMode
     *  Changed): re-asserts the video pin + overlay sweep every 900 ms while
     *  the floating window is up, so the page's own fullscreen exit, player
     *  DOM re-creation and ad/main-video switches can never hand the window
     *  back to page UI. Self-stops the moment inPip flips false; onDestroy's
     *  removeCallbacksAndMessages kills it with everything else. */
    protected final Runnable pipPinBeat = new Runnable() {
        @Override public void run() {
            try {
                if (!inPip) return;
                WebView w = fsSettleWeb != null ? fsSettleWeb
                        : (activeTab() == null ? null : activeTab().web);
                if (w != null) fsApplyCssRescue(w, true, true);
            } catch (Throwable ignored) {}
            if (inPip) ui.postDelayed(this, 900);
        }
    };
    /** v1.3.64 — one 'fs pip pin=' evidence line per PiP session (the first
     *  pin attempt's answer, whether it found a video or not). */
    protected boolean fsPipPinSaid = false;
    /** v1.3.66 — one 'fs pip fight' evidence line per PiP session (the
     *  in-page PiP play sentinel engaging — see MediaHook.pipFight). */
    protected boolean fsPipFightSaid = false;
    /** v1.3.67 — one 'fs pip exitgate' evidence line per PiP session (the
     *  in-page EXIT GATE swallowing the page's automatic exitFullscreen at
     *  the shrunk viewport — MediaHook.__boltGateExit; its presence + NO
     *  'fs hide inpip' line means the mobile session took the desktop
     *  configuration: custom view alive, nothing to fight). */
    protected boolean fsPipExitSaid = false;
    /** v1.3.68 — one 'fs pip gate' evidence line per PiP session (the
     *  in-page PAUSE GATE swallowing the mobile player's own page-side
     *  pause — MediaHook's HTMLMediaElement.prototype.pause wrap; the
     *  payload carries the SAME decoder: js=1 st=… names the pausing
     *  module, js=0 = no JS caller). Its presence + continuous audio =
     *  the reversal met the gate; 'fs pip gate' + 'fs pip fight' both
     *  present = the rate limiter degraded to the fight (still bounded). */
    protected boolean fsPipGateSaid = false;

    /** set while WE start another activity in this task — onUserLeaveHint
     *  also fires for in-app navigation, which must NOT auto-PiP. */
    protected boolean internalLaunch = false;

    // ---- v1.3.19 download-complete popup ----
    protected DownloadEngine.CompletionListener dlCompletion;
    protected final List<DownloadEngine.Item> dlPopupQueue = new ArrayList<>();
    protected AlertDialog dlPopup;                  // currently-showing completion dialog

    // ---- v1.3.40 DOWNLOAD SHELF (round-4 #8): the Chrome-style strip of
    // running/recent downloads above the bottom bar. Engine listener is
    // attached LAZILY (only once the engine exists — first enqueue or a
    // warm start with live downloads); a cold start with no engine shows
    // nothing, per the engine's no-side-effects rule. ----
    protected android.widget.HorizontalScrollView dlShelf;
    protected LinearLayout dlShelfItems;
    protected DownloadEngine.Listener dlShelfListener;
    /** ids observed ACTIVE at least once — terminal items only show if they
     *  were seen running this session (yesterday's DONE never re-appears). */
    protected final java.util.HashSet<Long> dlShelfSeen = new java.util.HashSet<>();
    /** terminal items the user ✕'d away (cleared if the id goes active again) */
    protected final java.util.HashSet<Long> dlShelfDismissed = new java.util.HashSet<>();
    /** v1.3.85 — hosts already shown the upload-401 hint this session
     *  (once per host per app session; the zai decoder's user-facing half).
     *  @see BoltClient#onReceivedHttpError */
    protected final java.util.HashSet<String> uploadAuthHinted = new java.util.HashSet<>();
    // v1.3.95 — per-session dedupe of the "upload auth" diag line (a
    // spamming telemetry beacon must not flood the diagnostics)
    protected final java.util.HashSet<String> uploadAuthLogged = new java.util.HashSet<>();
    protected final Runnable dlShelfRefresh = this::updateDlShelf;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Fx.theme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);
        prefs = Prefs.get(this);
        prefs.setUiMode(getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK);
        tm = TabManager.get(this);
        incognito = TabManager.INCOGNITO;
        // v1.3.33: skin rides the hash so a skin change in Appearance
        // recreates the browser on the next resume (same mechanism the
        // accent/light-dark toggle already used)
        themeHash = Fx.accent(this) + (prefs.isDark() ? 1 : 0) * 100000 + prefs.skin() * 10000000;
        seenGen = Fx.skinGen;
        // v1.3.13: WebViews are owned by their creating ACTIVITY. Any WebView
        // still held by the app-scoped TabManager belongs to a previous
        // (destroyed) activity instance — zombie playback leftovers. They
        // cannot be safely re-attached here, so destroy them now; the tabs
        // rebuild fresh from the saved session. Also informs the media service.
        destroyStaleWebViews();
        // v1.3.76 — CACHE GUARD, startup sweep: with the stale renderers
        // dead and the fresh ones not yet spun up, every WebView disk-cache
        // file is a dead file — if the video-session bloat (FB/YouTube
        // HTTP cache, Code Cache, Service Worker caches) crossed the
        // budget, this is the one moment it can all be physically deleted
        // at zero risk. Own thread, never blocks the startup path.
        try { com.bolt.browser.util.CacheGuard.check(this, true); } catch (Throwable ignored) {}
        // v1.3.79 — a previous session's exit sweep that a process death
        // interrupted (start marker without done) finishes NOW, before any
        // tab WebView exists — the site-data diet is the one part that
        // cannot wait for the next close.
        try { com.bolt.browser.util.CacheGuard.completePendingExit(this); } catch (Throwable ignored) {}

        wireViews();
        // v1.3.36: the address-bar placement (top by default; the
        // Opera-style bottom dock re-orders the chrome column) + the
        // edge-swipe navigation overlay
        applyChromeLayout();
        setupEdgeSwipe();
        buildHomeView();
        FilterEngine.warmAsync(this);
        MediaHookInit();
        // v1.3.19: download-complete popup — the engine reports each finished
        // file here; if the browser is visible it asks "Open file?" right away,
        // otherwise the item queues and the dialog appears on the next resume.
        // Static registration: constructing the engine on every launch would
        // revive dead downloads and start the download service (cold-start
        // side effects the engine design deliberately avoids).
        dlCompletion = this::onDownloadComplete;
        DownloadEngine.addCompletionListener(dlCompletion);
        // v1.3.40 download shelf: find the views + prime visibility (the
        // engine listener attaches lazily — see ensureDlShelfListener)
        setupDlShelf();
        // v1.3.40 — PRE-ADDED BANG PRESETS (task 17): !w/!yt/!g/… work on
        // the first omnibox use; seeding is once-ever and never touches a
        // user-curated engines list
        try { com.bolt.browser.util.Engines.seedPresets(this); } catch (Throwable ignored) {}
        // ask for notification permission up-front (once) so the media
        // notification never interrupts the first playback with a dialog
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 66);
        }

        handleIntent(getIntent());
        if (tm.tabs.isEmpty()) tm.addTab(new Tab());
        showTab(tm.active < 0 ? 0 : tm.active);
        updateBars();
        // v1.3.21: after a troubled session the evidence follows the user —
        // one tap on the startup dialog copies the diag report (WebView
        // version, renderer didCrash/priority fingerprints, exception stacks)
        // to the clipboard so it can be pasted into the next chat message.
        // That turns "it crashed" field reports into a diagnosis.
        if (!incognito) {
            try {
                String summary = com.bolt.browser.util.Diag.lastSessionSummary(this);
                if (summary != null) showStabilityReport(summary);
            } catch (Throwable ignored) {}
        }
    }

    /** v1.3.21: "last session had trouble" dialog. Shows once per cold start,
     *  only when the previous session recorded renderer restarts or uncaught
     *  exceptions. Copy report = full diag log to clipboard. */
    private void showStabilityReport(String summary) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle("Stability report")
                    .setMessage("Last session: " + summary + ".\n\nA diagnostics report was "
                            + "recorded that identifies the crash cause (web view version, "
                            + "renderer fingerprints, error stacks). Copy it and send it along "
                            + "if the problem repeats — it replaces guesswork.")
                    .setPositiveButton("Copy report", (d, w) -> {
                        try {
                            String log = com.bolt.browser.util.Diag.tail(this, 20000);
                            ClipboardManager cm =
                                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("bolt-diag",
                                    log.isEmpty() ? "(no diagnostics recorded)" : log));
                            Fx.toast(this, "Report copied to clipboard");
                        } catch (Throwable t) {
                            try { Fx.toast(this, "Copy failed"); } catch (Throwable ignored) {}
                        }
                    })
                    .setNegativeButton("Dismiss", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    protected void MediaHookInit() {}

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        // v1.3.79 — the search widget tap: open the omnibox ready to type
        try {
            if (i != null && i.getBooleanExtra("bolt_focus_search", false)) {
                // v1.4.18 — THE SESSION-AWARE WIDGET (the "external search
                // bounces the incognito user back to normal mode"
                // report): the widget enters through THIS face by design
                // (one PendingIntent, one entry point); when the last
                // foreground Bolt session was incognito, the tap belongs
                // in the PRIVATE session — forward and get out of the way.
                if (forwardToIncognitoIfLast(i)) return;
                ui.postDelayed(() -> {
                    try { showOmnibox(); } catch (Throwable ignored) {}
                }, 350);
            }
        } catch (Throwable ignored) {}
        // v1.4.12 — THE SYSTEM SEARCH ENTRIES (the manifest's new WEB_SEARCH /
        // SEND text/plain / PROCESS_TEXT filters, one per launcher face).
        // The query rides a different extra per surface: SearchManager.QUERY
        // for WEB_SEARCH, EXTRA_TEXT for the share sheet, and
        // EXTRA_PROCESS_TEXT for the text-selection floating toolbar.
        // v1.4.14 — THE NEW-TAB LAW: all three now funnel into
        // openQueryInNewTab() instead of loadInTab(). A search that arrives
        // from OUTSIDE the browser must never overwrite the page in the
        // user's active tab — it resolves through the same resolver the
        // omnibox uses (resolveInput: URL-vs-query, engine, bangs, tracker
        // cleaner) and opens in its OWN tab. Inline like the VIEW branch
        // below (openQueryInNewTab builds its own tab, so it has no
        // dependency on the active tab onCreate has not created yet).
        try {
            if (i != null && i.getAction() != null) {
                String act = i.getAction();
                String q = null;
                if (Intent.ACTION_WEB_SEARCH.equals(act)) {
                    q = i.getStringExtra(SearchManager.QUERY);
                } else if (Intent.ACTION_SEND.equals(act)
                        && "text/plain".equals(i.getType())) {
                    q = i.getStringExtra(Intent.EXTRA_TEXT);
                } else if (Intent.ACTION_PROCESS_TEXT.equals(act)) {
                    CharSequence cs = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
                    q = cs == null ? null : cs.toString();
                }
                if (q != null && !q.trim().isEmpty()) {
                    // v1.4.18 — THE SESSION-AWARE SEARCH FACE: the alias
                    // row the OS picked is the MAIN-process one (the
                    // v1.4.17 "(Incognito)" row is the manual override,
                    // frequently collapsed into the toolbar overflow);
                    // when the last foreground session was private, the
                    // query forwards into the private process instead of
                    // surfacing the normal task (whose back stack still
                    // held the normal TABS MENU — the bounce the report
                    // named). Diag breadcrumb either way: every field
                    // report now says which face handled the search.
                    if (forwardToIncognitoIfLast(i)) return;
                    try {
                        com.bolt.browser.util.Diag.log(this,
                                "search intent " + act + " handled (normal)");
                    } catch (Throwable ignored) {}
                    try { openQueryInNewTab(q.trim()); } catch (Throwable ignored) {}
                    return;   // a search entry is fully handled — never fall
                              // through to the VIEW/data branch as well
                }
            }
        } catch (Throwable ignored) {}
        if (i == null || i.getData() == null) return;
        String u = i.getDataString();
        if (u != null && u.startsWith("http")) {
            Tab t = new Tab();
            t.isHome = false;
            t.url = u;
            tm.addTab(t);
            showTab(tm.tabs.size() - 1);
        } else if (u != null && (u.startsWith("file://") || u.startsWith("content://"))) {
            // saved offline pages (.mht) — from the Saved pages list or other apps
            openArchive(u);
        }
    }

    /** v1.4.18 — THE SESSION-AWARE FORWARD (the "external search bounces
     *  the incognito user back to the normal tabs menu" report): when the
     *  last foreground Bolt session was INCOGNITO (SessionState's record,
     *  written by whichever BrowserActivity variant last resumed), an
     *  external search intent or widget tap that resolved into THIS
     *  normal face is re-fired at IncognitoActivity — the private process
     *  with its own cookie jar — and this activity finishes so the normal
     *  task (and its stale tabs-menu back stack) never surfaces over it.
     *  The forward happens BEFORE onResume, so this face's own resume
     *  never overwrites the record it just read. Never fires from the
     *  incognito process itself (there the search already IS private),
     *  and a missing record reads normal — the feature cannot hijack a
     *  normal user's search into private mode by accident. The v1.4.17
     *  SearchIncognito alias stays: it is the MANUAL door. */
    private boolean forwardToIncognitoIfLast(Intent i) {
        if (incognito) return false;
        boolean lastIncognito;
        try {
            lastIncognito = com.bolt.browser.util.SessionState
                    .lastForegroundIncognito(this);
        } catch (Throwable t) {
            return false;
        }
        if (!lastIncognito) return false;
        try {
            Intent fwd = new Intent(this, IncognitoActivity.class);
            fwd.setAction(i == null ? null : i.getAction());
            if (i != null && i.getExtras() != null) fwd.putExtras(i.getExtras());
            fwd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                com.bolt.browser.util.Diag.log(this, "search fwd -> incognito ("
                        + (i == null ? "widget" : i.getAction()) + ")");
            } catch (Throwable ignored) {}
            startActivity(fwd);
        } catch (Throwable t) {
            return false;   // the forward itself failed: fall through to
                            // normal handling rather than eating the search
        }
        finish();
        return true;
    }

    /** open a saved web archive in a new tab: file:// loads directly;
     *  content:// (SAF) is copied into the cache dir first because WebView's
     *  content access is disabled. */
    protected void openArchive(final String uriString) {
        try {
            String fileUrl;
            if (uriString.startsWith("file://")) {
                fileUrl = uriString;
            } else {
                Uri cu = Uri.parse(uriString);
                File tmp = new File(getCacheDir(),
                        "offline-" + System.currentTimeMillis() + ".mht");
                try (java.io.InputStream in = getContentResolver().openInputStream(cu);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    if (in == null) throw new java.io.IOException("no stream");
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                fileUrl = "file://" + tmp.getAbsolutePath();
            }
            Tab t = new Tab();
            t.isHome = false;
            t.url = fileUrl;
            t.title = "Saved page";
            tm.addTab(t);
            showTab(tm.tabs.size() - 1);
        } catch (Throwable e) {
            Fx.toast(this, "Can't open saved page");
        }
    }

    /** v1.4.7 — the orientation the window had when the app last left the
     *  foreground (captured at onPause). The background-rotation mute
     *  (MediaHook) swallows the page's resize/orientationchange while the
     *  page is really hidden, so a rotation that happened while the app was
     *  away must be followed by ONE marked resync resize on the return —
     *  this field is how the return knows one happened. */
    private int pausedOrientation = -1;

    @Override
    protected void onResume() {
        // v1.4.18 — THE SESSION RECORD: whichever process's browser face
        // resumes is the session the user is in LAST; the main-process
        // search faces read this (fresh, cross-process) to decide whether
        // an external search forwards into the private session. First
        // statement of onResume so no early return below can skip it.
        try {
            com.bolt.browser.util.SessionState.noteForeground(this, incognito);
        } catch (Throwable ignored) {}
        // v1.3.76 — CACHE GUARD, mid-session audit (rate-limited to one pass
        // per 5 minutes; over budget it trims the HTTP cache through the
        // instance API that is safe with live renderers — ANY of this app's
        // WebViews works, the effect is app-global)
        try {
            android.webkit.WebView cw = null;
            synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
                for (Tab t : tm.tabs) if (t.web != null) { cw = t.web; break; }
            }
            com.bolt.browser.util.CacheGuard.check(this, false, cw);
        } catch (Throwable ignored) {}
        // v1.3.77 — coming back cancels the close-out sweep (the user
        // returned; the caches are warm again) and re-arms the foreground
        // audit loop (a video session that never pauses the app still
        // gets measured every 3 minutes)
        try {
            ui.removeCallbacks(cacheBgSweep);
            ui.removeCallbacks(cacheAuditTick);
            ui.postDelayed(cacheAuditTick, 6 * 60 * 1000L);   // v1.3.79: 3 -> 6 min (battery)
        } catch (Throwable ignored) {}
        // v1.3.17: stop pretending the window is visible — the real VISIBLE
        // dispatch passes through the filter unchanged, so releasing here is
        // safe even mid-rotation (the flag survives onPause→onStop→onResume
        // dips, which is exactly what keeps rotation seamless too).
        com.bolt.browser.ui.MediaAwareContent.releaseSpoof();
        super.onResume();
        // v1.3.38: stale in-app-launch marks must never block a legit
        // auto-PiP (onUserLeaveHint consumes the flag; this catches any
        // launch that somehow never produced one)
        internalLaunch = false;
        // re-apply theme if changed in settings (v1.3.33: skin too;
        // v1.3.37: + skinGen for layout-level changes)
        int nowHash = Fx.accent(this) + (prefs.isDark() ? 1 : 0) * 100000 + prefs.skin() * 10000000;
        if (nowHash != themeHash || Fx.skinGen != seenGen) {
            seenGen = Fx.skinGen;
            recreate();
            return;
        }
        syncFromTabManager();
        // v1.3.53: home-CONTENT changes (a favicon landed, wallpaper style
        // adjusted, suggested-sites toggled) rebuild ONLY the home view —
        // never the activity (a recreate destroys every tab's WebView and
        // reloads pages: the v1.3.52 field report). Off-home: showTab's
        // homeGen check rebuilds it when the user next goes home.
        if (Fx.homeGen != homeShownGen && homeView != null) {
            Tab ht = activeTab();
            if (ht != null && ht.isHome) refreshHome();
        }
        // v1.3.39: fullscreen browsing + the night-screen filter re-apply on
        // every resume (their toggles live in Settings / the quick panel)
        try {
            com.bolt.browser.util.Fullscreen.apply(this, customView == null && barsShown);
            setupHoverButton();
        } catch (Throwable ignored) {}
        try { com.bolt.browser.util.NightScreen.apply(this); } catch (Throwable ignored) {}
        try { scheduleNightRecheck(); } catch (Throwable ignored) {}
        // defensive: bars must never stay hidden outside fullscreen video
        // ("top bar disappears sometimes" hardening) — v1.3.33 also resets the
        // scroll-hide translation
        if (customView == null) {
            try {
                View tb = findViewById(R.id.topbar);
                View bb = findViewById(R.id.bottombar);
                if (tb != null && tb.getVisibility() != View.VISIBLE) tb.setVisibility(View.VISIBLE);
                if (bb != null && bb.getVisibility() != View.VISIBLE) bb.setVisibility(View.VISIBLE);
                showBarsNow(false);
            } catch (Throwable ignored) {}
        }
        // resume ALL WebViews paused by onPause() (v1.3.11: the pause decision
        // is now per-tab, so the resume must be too; calling onResume() on a
        // WebView that was never paused is harmless)
        synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
            for (Tab t : tm.tabs) {
                if (t.web != null) {
                    try { t.web.onResume(); } catch (Throwable ignored) {}
                }
            }
        }
        // v1.4.7 — THE ROTATION RESYNC: while the app was away, the
        // background-rotation mute (MediaHook's pipMuted) swallowed every
        // resize/orientationchange the device rotation produced — an
        // invisible-to-invisible rotation must never reach the player's
        // reaction handlers (the "background video playback stops when I
        // rotate phone to landscape" report). Coming back with the
        // orientation CHANGED, a media-holding page's layout state is
        // stale (it still believes the old window). Dispatch ONE synthetic
        // resize marked __boltRS (the v1.3.69 resync-exemption idiom from
        // PipVideo.pinJs — it passes even if a mute is still armed) to
        // every media-holding tab so the player re-reads the live window
        // size. ~450 ms out: past the first layout pass of the new
        // configuration. A foreground rotation needs no resync (the page
        // saw the real events — the mute only arms while really hidden).
        try {
            int nowOr = getResources().getConfiguration().orientation;
            if (pausedOrientation >= 0 && nowOr != pausedOrientation) {
                synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
                    for (Tab t : tm.tabs) {
                        if (t.web != null && (t.holdsMedia || t.mediaPlaying)) {
                            final Tab ft = t;
                            ui.postDelayed(() -> {
                                try {
                                    if (ft.web != null) ft.web.evaluateJavascript(
                                            "try{var __rse=new Event('resize');__rse.__boltRS=1;"
                                                    + "window.dispatchEvent(__rse);}catch(x){}", null);
                                } catch (Throwable ignored) {}
                            }, 450);
                        }
                    }
                }
            }
            pausedOrientation = nowOr;
        } catch (Throwable ignored) {}
        // v1.3.25: back in front — the visible tab must regain never-waived
        // priority (it may have been yielding while hidden with media playing
        // in another tab).
        applyRendererPolicies("resume");
        // settings may have flipped the booster toggle while we were away —
        // re-evaluate the floating media button immediately
        // v1.3.66 — never while the PiP window is up (it shrinks the whole
        // activity; the FAB would ride the video in the floating window)
        // v1.3.79: the FAB is the VIDEO CONTROL button — visible whenever a
        // video plays and ANY of its features (booster / silence skip) is on
        fabMedia.setVisibility(
                mediaPlaying && (prefs.mediaBooster() || prefs.mediaSkip()) && !inPip
                        ? View.VISIBLE : View.GONE);
        // v1.3.19: downloads that finished while the browser was away
        showQueuedDlPopups();
        restoreBars();
        // v1.3.27 — contextual background-survival offer: either the PREVIOUS
        // session was killed while playing (hard evidence from the heartbeat
        // mark, set at app start) or the user backgrounded a playing session
        // without the battery whitelist (armed in onStop). One dialog per
        // install; Settings > Media re-offers at any time. Never in incognito.
        // v1.3.30 — the whitelist RESET case: the v1.3.29 field kill landed
        // with bo=optimizable even though the user had enabled everything
        // after the v1.3.28 report (a reinstall or the OEM battery manager
        // had silently re-applied "optimization"). While background play is
        // on and the app is UNPROTECTED, re-arm the one-shot offer and show
        // it at most once per 6 h — with a kill on record it fires right
        // here, with the evidence attached.
        boolean boNagDue = false;
        try {
            long now = System.currentTimeMillis();
            com.bolt.browser.util.Prefs pp = com.bolt.browser.util.Prefs.get(this);
            boolean unprotected = !incognito && prefs.bgPlay()
                    && !com.bolt.browser.util.BackgroundSurvival.isIgnoring(this);
            if (unprotected && now - pp.i("boNagAt", 0) > 6L * 60L * 60L * 1000L) {
                pp.set("boNagAt", now);
                pp.set("askedBo", false);   // phase 1 may fire again — it is OFF
                boNagDue = true;
            }
        } catch (Throwable ignored) {}
        try {
            String ev = com.bolt.browser.media.MediaControlService.lastDeathEvidence;
            com.bolt.browser.media.MediaControlService.lastDeathEvidence = null;
            if (!incognito && (ev != null || pendingBoOffer || boNagDue)) {
                pendingBoOffer = false;
                com.bolt.browser.util.BackgroundSurvival.maybeOfferOnce(this, prefs, ev);
            } else {
                pendingBoOffer = false;
            }
        } catch (Throwable ignored) {}
        // v1.3.38 TAB SLEEP: back in the foreground — background tabs idle
        // past the configured minutes give their renderer's RAM back (the
        // tab keeps url + scroll + thumbnail and reloads on demand).
        try { com.bolt.browser.util.TabSleep.sweep(this, tm, tm.active); }
        catch (Throwable ignored) {}
        // v1.3.38: auto-PiP eligibility may have changed while away (the
        // toggle in Settings, or media state) — re-publish it
        try { updatePipParams(); } catch (Throwable ignored) {}
    }

    /** v1.3.39: the night-screen AUTO schedule re-checks every minute while
     *  the browser is resumed (from/to times live in deep settings). */
    private Runnable nightRecheck;
    private void scheduleNightRecheck() {
        if (nightRecheck == null) {
            nightRecheck = () -> {
                try { com.bolt.browser.util.NightScreen.recheck(this); } catch (Throwable ignored) {}
                try { ui.postDelayed(nightRecheck, 60_000L); } catch (Throwable ignored) {}
            };
        }
        try { ui.removeCallbacks(nightRecheck); } catch (Throwable ignored) {}
        try { ui.postDelayed(nightRecheck, 60_000L); } catch (Throwable ignored) {}
    }

    protected void syncFromTabManager() {
        if (tm.active < 0) tm.active = 0;
        if (tm.tabs.isEmpty()) tm.addTab(new Tab());
        Tab cur = activeTab();
        // only rebuild home if the shared homeView is missing (NOT on every resume)
        if (cur.isHome && homeView == null) buildHomeView();
        // v1.3.51: "same tab" means the same OBJECT. The old index check let
        // a workspace switch (the whole list replaced in place) through
        // whenever the new active index happened to equal the shown one —
        // the browser then kept the outgoing space's (already-destroyed)
        // page up and the switch "did not appear" until a later event.
        if (cur == shownTabRef && flContent.getChildCount() > 0) {
            updateBars();   // same tab already displayed: just refresh bar state, no re-attach
            return;
        }
        showTab(tm.active);
    }

    @Override
    protected void onPause() {
        // v1.3.17: PREVENTIVE background play. Engage the window-visibility
        // spoof BEFORE super.onPause() — the window can be reported hidden the
        // moment pausing completes, and Chromium must not learn it: the page
        // would flip document.hidden and pause itself, leaving only our
        // seconds-late resurrection (the audible gap, and the miss cases).
        // While engaged, MediaAwareContent re-dispatches GONE as VISIBLE to
        // the WebView subtree, so the page never pauses and never enters the
        // hidden state that makes notification PLAY a dead button.
        boolean bgOn = prefs.bgPlay();
        // v1.3.64 — PiP IS A WATCHING STATE. While the floating window is up
        // — or is ABOUT to be: onPause runs BEFORE onPictureInPictureMode-
        // Changed(true) on BOTH the 26–30 onUserLeaveHint path and the 31+
        // auto-enter path, and the only PiP-eligible state is a fullscreen
        // custom view — the media tab is foreground-equivalent REGARDLESS of
        // the background-play pref or a site's background opt-out (those
        // govern AUDIO in a hidden background, not a window the user is
        // actively watching). Letting the WebView pause/hide here is the
        // v1.3.63 mobile report: "the pip just refuses to play at all, I
        // press play, it stops instantly" — a paused renderer never runs the
        // tap, and a page that learned it is hidden reverses every play its
        // own logic can see.
        boolean pipWatch = inPip || customView != null;
        boolean anyMedia = false;
        synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
            for (Tab t : tm.tabs) {
                if (t.web == null) continue;
                if ((pipWatch && (t.mediaPlaying || t.holdsMedia))
                        || (bgOn && (t.mediaPlaying || t.holdsMedia) && !siteBlockBg(t))) {
                    anyMedia = true;
                    break;
                }
            }
        }
        // authoritative set (not just engage): clears any stale flag left by
        // an activity destroyed while backgrounded (flag survived, no dispatch
        // ever corrected it) — a no-media leave must report GONE for real, or
        // the renderer would stay unthrottled in the background for nothing.
        if (anyMedia) com.bolt.browser.ui.MediaAwareContent.engageSpoof();
        else com.bolt.browser.ui.MediaAwareContent.releaseSpoof();
        super.onPause();
        // v1.3.11 rebuild — per-tab background decision. A tab's WebView keeps
        // running through onPause ONLY when background play is on AND that tab
        // is the one actually playing media (and the site hasn't opted out of
        // background play). Everything else is paused. The old code only ever
        // considered the ACTIVE tab: media in background tabs leaked audio with
        // bgPlay OFF, and a playing non-active tab could be paused (or not) by
        // whichever tab happened to be in front — another "sometimes".
        // v1.3.64: the pipWatch clause keeps the watched tab alive in PiP too.
        synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
            for (Tab t : tm.tabs) {
                if (t.web == null) continue;
                // v1.3.16: keep alive any tab HOLDING media — playing (v1.3.11) OR
                // paused-but-loaded (holdsMedia). A paused media tab is still the
                // notification's target; web.onPause() on it freezes the renderer
                // and every later notification PLAY tap dies silently.
                boolean keep = (pipWatch && (t.mediaPlaying || t.holdsMedia))
                        || (bgOn && (t.mediaPlaying || t.holdsMedia) && !siteBlockBg(t));
                if (!keep) {
                    try { t.web.onPause(); } catch (Throwable ignored) {}
                }
            }
        }
        // v1.3.25: leaving the foreground is the decisive moment for the media
        // renderer — pin the priorities NOW: media tab(s) stay at never-waived
        // max, hidden non-media tabs yield so pressure reclaims them first.
        applyRendererPolicies("pause");
        Tab t = activeTab();
        if (t != null && t.web != null) t.capture();
        // v1.4.7 — remember the orientation we left in; the resume path
        // compares against it to know the device rotated while the app was
        // away (the rotation resync — see onResume)
        try { pausedOrientation = getResources().getConfiguration().orientation; }
        catch (Throwable ignored) {}
        // v1.4.16 — THE GHOST SWEEP: a speed-dial lift dragged to the exact
        // moment the activity pauses (incoming call, overlay, quick home
        // swipe) left the sdGhost floating over flRoot forever — the touch
        // stream that would have ended the drag is gone, so the ghost sat
        // frozen on top of the home screen on resume. Remove it NOW; the
        // drag-end path (endSdDrag) is parent-guarded, so a resumed drag
        // that somehow continues simply re-arms a fresh ghost.
        try {
            if (sdGhost != null && sdGhost.getParent() == flRoot) {
                flRoot.removeView(sdGhost);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityBackground = true;
        // v1.3.27: leaving the app FOR REAL with live background media and no
        // battery whitelist — arm the one-time survival offer for the return
        // (this is the exact moment the OEM killer is about to get us).
        try {
            if (!incognito && prefs.bgPlay()) {
                boolean anyMedia = false;
                synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
                    for (Tab t : tm.tabs) {
                        if (t.web != null && (t.mediaPlaying || t.holdsMedia)
                                && !siteBlockBg(t)) { anyMedia = true; break; }
                    }
                }
                if (anyMedia && !com.bolt.browser.util.BackgroundSurvival.isIgnoring(this)) {
                    pendingBoOffer = true;
                }
            }
        } catch (Throwable ignored) {}
        // v1.3.18: flush the tab state NOW. onStop is the last lifecycle call
        // GUARANTEED to run when the app leaves the foreground — onDestroy
        // never fires if the process is killed from recents or by an OEM
        // killer, and that is exactly how "open 2 new tabs → clear recents →
        // reopen → old tabs only" lost them. The write is synchronous, small
        // (a few hundred bytes of JSON), and skips incognito at the source.
        try { tm.flushSave(); } catch (Throwable ignored) { }
        // v1.3.38 app lock: leaving the foreground arms the re-lock clock
        try { com.bolt.browser.util.AppLock.onActivityStop(); } catch (Throwable ignored) {}
        // v1.3.40 — download shelf listener off (the engine itself survives)
        try {
            if (dlShelfListener != null && DownloadEngine.exists())
                DownloadEngine.get(this).removeListener(dlShelfListener);
        } catch (Throwable ignored) {}
        // v1.4.16 — THE GHOST SWEEP, onStop edition: onPause can be skipped
        // (a dialog over a paused-but-visible activity, OEM lifecycle quirks)
        // while onStop still fires — the same frozen sdGhost sweep runs here
        // so no lifecycle path can strand the lift ghost on flRoot.
        try {
            if (sdGhost != null && sdGhost.getParent() == flRoot) {
                flRoot.removeView(sdGhost);
            }
        } catch (Throwable ignored) {}
        // v1.3.13: nothing else to do here. The in-page spoof only acts while
        // the page is REALLY hidden (no arming needed), and the media service
        // keeps the process + playback alive on its own.
        // v1.3.77 — the storage law's close-out arm: stop the foreground
        // audit loop. PiP never lands here (paused-but-visible skips
        // onStop), so a floating video keeps its caches; a truly-closed
        // browser does not.
        // v1.3.79 — THE DEATH-WINDOW KILL: the 45 s delay was a bet that
        // the process lives 45 s after swipe-away. On this browser's own
        // field hardware (realme/ColorOS — the same OEM killer the
        // v1.3.27-28 background-survival work documented killing at ~80 s
        // WITH the whitelist) the kill lands in SECONDS: the Handler
        // runnable dies with the process and the sweep never runs — which
        // is exactly the v1.3.78 report ("51mb with 46mb in app data,
        // even after 10 minutes of closing the app fully"). The sweep now
        // fires IMMEDIATELY at onStop on its own thread (1-3 s of deletes,
        // comfortably inside any post-stop lifetime), with two fallbacks:
        // screen-off uses a 60 s arm (locking the screen is not "closing";
        // the next cold start catches a death inside that window), and the
        // start/done marker pair lets the next cold start finish a sweep
        // that a racing kill still interrupted.
        try {
            ui.removeCallbacks(cacheAuditTick);
            ui.removeCallbacks(cacheBgSweep);
            boolean screenOn = true;
            try {
                android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
                if (pm != null) screenOn = pm.isInteractive();
            } catch (Throwable ignored) {}
            if (screenOn) {
                com.bolt.browser.util.CacheGuard.exit(BrowserActivity.this);
            } else {
                ui.postDelayed(cacheBgSweep, 60 * 1000L);
            }
        } catch (Throwable ignored) {}
        // v1.3.79 — prefs flush: the debounced writer's window closes here
        // (synchronous, small — the one lifecycle moment disk waits for us)
        try { if (!incognito) prefs.flush(); } catch (Throwable ignored) {}
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityBackground = false;
        // v1.3.38 APP LOCK (opt-in, Privacy → "App lock"): cold start — or
        // returning after 60 s away with "lock when backgrounded" on — gates
        // the session behind the translucent LockActivity + the device
        // credential. Incognito inherits this override too.
        try {
            if (com.bolt.browser.util.AppLock.onActivityStart(this)) {
                startActivity(new Intent(this, com.bolt.browser.ui.LockActivity.class));
            }
        } catch (Throwable ignored) {}
        // v1.3.40 — download shelf: if the engine is alive (download ran
        // before / revived by the service), reattach + refresh the strip
        try {
            if (DownloadEngine.exists()) {
                ensureDlShelfListener();
                ui.removeCallbacks(dlShelfRefresh);
                ui.postDelayed(dlShelfRefresh, 350);
            }
        } catch (Throwable ignored) {}
        // v1.3.40 — SCHEDULED AUTO-BACKUP (round-4 #14): a few seconds
        // after launch, when the weekly toggle is on and 7 days passed.
        // Cheap check on the main thread; the zip writes on a worker.
        try {
            if (!incognito) ui.postDelayed(() -> {
                try { com.bolt.browser.util.AutoBackup.maybeBackup(this); }
                catch (Throwable ignored) {}
            }, 8000L);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ v1.3.38 picture-in-picture

    /** in-app activity launches are marked so onUserLeaveHint (which fires
     *  for EVERY user-initiated foreground switch, not just home) can tell
     *  "opened settings" apart from "left the app". */
    @Override
    public void startActivity(android.content.Intent intent) {
        internalLaunch = true;
        super.startActivity(intent);
    }

    @Override
    public void startActivity(android.content.Intent intent, android.os.Bundle options) {
        internalLaunch = true;
        super.startActivity(intent, options);
    }

    @Override
    public void startActivityForResult(android.content.Intent intent, int requestCode) {
        internalLaunch = true;
        super.startActivityForResult(intent, requestCode);
    }

    @Override
    public void startActivityForResult(android.content.Intent intent, int requestCode, android.os.Bundle options) {
        internalLaunch = true;
        super.startActivityForResult(intent, requestCode, options);
    }

    /** v1.3.38 — AUTO PICTURE-IN-PICTURE (menu item 9; pref "autoPip",
     *  Settings → Media): leaving via HOME/RECENTS while media plays (or a
     *  fullscreen video is up) shrinks the browser into the floating window
     *  instead of just audio. API 31+ gets the seamless system-managed
     *  auto-enter (updatePipParams); 26–30 use this onUserLeaveHint fallback. */
    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        try {
            boolean inApp = internalLaunch;
            internalLaunch = false;
            if (inApp) return;                        // settings/pickers, not "leaving"
            if (android.os.Build.VERSION.SDK_INT >= 31) return;   // auto-enter owns it
            enterPipIfEligible();
        } catch (Throwable ignored) {}
    }

    /** shared by the auto path (onUserLeaveHint) and the manual menu row /
     *  quick action. No-op below API 26 or with nothing playing.
     *  v1.3.41 — PiP only from a FULLSCREEN VIDEO (Chrome's own rule):
     *  shrinking a non-fullscreen page put the WHOLE SITE in the floating
     *  window (the v1.3.40 field report — "shows the entire site instead of
     *  just the video"). Plain media now takes the classic background-audio
     *  path instead: no window, live notification, playback uninterrupted.
     *  v1.3.63 — THE ASPECT COMES FROM THE WINDOW, NOT THE VIDEO. The
     *  v1.3.60/62 reports' "only bottom half of the video shows with top
     *  half black bars" / "top half of the video in the bottom half of the
     *  pip" is the aspect-jump signature: the activity is FORCED LANDSCAPE
     *  at fullscreen (≈20:9 on this device) while the v1.3.61 probe armed a
     *  16:9 window — the PiP shrink was then NOT a uniform scale, and
     *  Chromium's own PiP comment (rwhva.cc) warns exactly that the shrink
     *  "is not guaranteed to be simply a scale from the fullscreen size".
     *  A window whose aspect EQUALS the live custom-view bounds makes the
     *  shrink a pure uniform scale of the exact current geometry — even a
     *  stale surface then renders as a correct scaled snapshot, and the
     *  16:9 video simply letterboxes inside via the page's own fullscreen
     *  CSS. See pipAspectNow(). */
    protected void enterPipIfEligible() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26) return;
            if (customView == null) return;   // only a fullscreen video is PiP-able
            // v1.3.65 — THE IMPORTED WINDOW (Google's canonical PiP sample):
            // the VIDEO's aspect (the whole picture edge-to-edge) plus the
            // two RemoteActions (play/pause toggle + fullscreen; the
            // system's own X closes) — the actions render in the tap-menu
            // on 8-11 and in the window's button row on 12+
            android.app.PictureInPictureParams p =
                    com.bolt.browser.util.PipVideo.params(
                            this, pipAspectNow(), mediaPlaying, false);
            if (p != null) {
                enterPictureInPictureMode(p);
                return;
            }
            android.app.PictureInPictureParams.Builder b =
                    new android.app.PictureInPictureParams.Builder();
            try {
                b.setAspectRatio(pipAspectNow());
            } catch (Throwable ignored) {}
            enterPictureInPictureMode(b.build());
        } catch (Throwable ignored) {}
    }

    /** v1.3.63 — the PiP window MUST inherit the aspect of the window it is
     *  shrinking: the laid-out custom view's own pixel size (the exact
     *  geometry the renderer last composed), clamped into PiP's legal
     *  2.39:1..1:2.39 range. Fallback 16:9 when nothing measurable is up
     *  (pre-layout arm is re-armed by the settle kick + probe answer). */
    protected android.util.Rational pipAspectNow() {
        // v1.3.65 — THE VIDEO OWNS THE WINDOW: the probed (or pin-answer)
        // video aspect first, so the whole picture renders edge-to-edge
        // with zero letterbox waste; the live custom-view bounds and 16:9
        // stay as fallbacks. (v1.3.63 inherited the custom-view/screen
        // aspect to keep the shrink uniform for a STALE surface — the
        // v1.3.65 takeover pin owns the render at ANY window size, so the
        // uniform-shrink bet is no longer worth ~20% of the box.)
        try {
            if (fsVideoAspect != null) return fsVideoAspect;
        } catch (Throwable ignored) {}
        try {
            View cv = customView;
            if (cv != null && cv.getWidth() > 20 && cv.getHeight() > 20) {
                return clampVideoAspect(
                        (float) cv.getWidth() / (float) cv.getHeight());
            }
        } catch (Throwable ignored) {}
        return new android.util.Rational(16, 9);
    }

    /** API 31+: the swipe-up-home gesture auto-enters PiP with the system's
     *  own transition — no onUserLeaveHint flash. Re-applied whenever the
     *  media state flips (see JsBridge.mediaEvent) and at fullscreen-video
     *  enter/exit (v1.3.41 — eligibility is now video-only). v1.3.63: carries
     *  pipAspectNow() — the LIVE window aspect (see enterPipIfEligible);
     *  re-armed again at the 300 ms settle kick and the probe answer because
     *  the first arm at onShowCustomView can run before the custom view has
     *  ever been laid out (getWidth()==0 → 16:9 fallback would hand the
     *  auto-enter a mismatched aspect again). */
    protected void updatePipParams() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                // v1.3.65 — one builder (util/PipVideo.kt) now owns aspect
                // + actions on every path: the armed auto-enter params
                // carry the same control set the manual enter uses
                android.app.PictureInPictureParams p =
                        com.bolt.browser.util.PipVideo.params(this,
                                pipAspectNow(), mediaPlaying,
                                prefs.b("autoPip", true) && customView != null);
                if (p != null) setPictureInPictureParams(p);
            }
        } catch (Throwable ignored) {}
    }

    /** v1.3.65 — refresh the PiP window's ACTIONS while it is up (the
     *  play/pause ICON follows the known playing state; the command
     *  itself always sends CMD_TOGGLE, so a stale icon can never desync
     *  the action). Safe no-op below API 26 or outside PiP. */
    protected void updatePipActions() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26 || !inPip) return;
            android.app.PictureInPictureParams p =
                    com.bolt.browser.util.PipVideo.params(this,
                            pipAspectNow(), mediaPlaying,
                            prefs.b("autoPip", true) && customView != null);
            if (p != null) setPictureInPictureParams(p);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
            android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        inPip = isInPictureInPictureMode;
        if (isInPictureInPictureMode) {
            // chrome has no business in a 240 dp window: bars away, overlays
            // closed, keyboard down — the page/video owns every pixel
            dismissAllOverlays();
            hideKeyboard();
            try { hideBarsNow(false); } catch (Throwable ignored) {}
            // v1.3.66 — the media FAB is chrome too: PiP can be entered from
            // an INLINE playing video (menu → Picture-in-picture), where the
            // FAB is live — shrink it with the activity and it rides the
            // video in the floating window (the mobile-report booster bug).
            // The expand-back restores re-evaluate it by their own rules.
            try {
                if (fabMedia != null) fabMedia.setVisibility(View.GONE);
            } catch (Throwable ignored) {}
            // v1.3.41: in the video-only PiP the customView must be the
            // front-most child of the root — nothing may paint above it
            if (customView != null) {
                try {
                    // v1.3.61 — CLEAN RELAYOUT at the shrunk bounds: the PiP
                    // resize reuses the custom view's last measure all too
                    // often (Chromium's own PiP comment: the shrink "is not
                    // guaranteed to be simply a scale from the fullscreen
                    // size"), and a stale measure is exactly the off-center
                    // half-video geometry the v1.3.60 report named. Re-assert
                    // match_parent, request the layout pass, and bring the
                    // video back to the front in the same beat.
                    customView.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
                    customView.requestLayout();
                    if (flRoot != null) flRoot.requestLayout();
                } catch (Throwable ignored) {}
                try { flRoot.bringChildToFront(customView); } catch (Throwable ignored) {}
                try {
                    // v1.3.63 — evidence: the WINDOW aspect actually used for
                    // the PiP (pipAspectNow) + the probed VIDEO aspect for
                    // comparison (the v1.3.60/62 geometry bugs were the gap
                    // between the two)
                    com.bolt.browser.util.Diag.log(this, "fs pip aspect="
                            + pipAspectNow().toString()
                            + " video=" + (fsVideoAspect == null ? "16:9" : fsVideoAspect.toString()));
                } catch (Throwable ignored) {}
                // v1.3.63 — THE PiP KICK IS REMOVED. The v1.3.62 field log
                // caught it red-handed: on the MOBILE site the custom view
                // died at pip+263 ms — the kick was scheduled at +250 ms
                // (fs hide one frame after it), and v1.3.61 (no kick) kept
                // the same view alive through PiP. A resize at the shrunk
                // size makes the mobile player — whose fullscreenElement is
                // the PLAYER — exit element fullscreen (the browser chrome
                // then paints into the floating window: "the pip shows only
                // the browser elements"). Desktop <html> fullscreen ignored
                // the nudge, so the kick bought nothing there either. The
                // geometry itself is now fixed where it is actually broken:
                // the PiP window's aspect (pipAspectNow below).
                // v1.3.61 — CLEAN RELAYOUT stays: re-assert match_parent,
                // request the layout pass, and bring the video back to the
                // front in the same beat.
                // v1.3.64 — THE PiP TAKEOVER: pin the video NOW, not after
                // the custom view dies. The v1.3.63 report proved the page's
                // own fullscreen discipline does NOT survive the shrink on
                // EITHER site (mobile: "the same youtube ui being in the pip
                // view area box"; desktop: "only top half of the video with
                // rest being the youtube search bar" — the page re-lays out
                // its NORMAL chrome into the floating window), and the
                // reactive death-path pin needs a PLAYING video exactly when
                // the site has just paused it. Pinning at ENTER (playing OR
                // paused-with-frame, largest first) + the watchdog beat
                // below makes the video the ONLY thing allowed in the box,
                // at any window size, whatever the page does with its
                // fullscreen state.
                try {
                    fsApplyCssRescue(activeTab() == null ? null : activeTab().web,
                            true, true);
                } catch (Throwable ignored) {}
            }
            // v1.3.64 — the pin watchdog: re-assert every 900 ms for the
            // whole PiP session (player DOM re-creation, ad switches, the
            // page's own fullscreen exit). Armed unconditionally so a
            // raced/absent custom view still gets the beat.
            try {
                ui.removeCallbacks(pipPinBeat);
                ui.postDelayed(pipPinBeat, 400);
            } catch (Throwable ignored) {}
            // v1.3.65 — THE IMPORTED CONTROLS: arm the window's actions
            // (play/pause toggle + fullscreen; the system's own X closes)
            // the moment the window exists, and say so once per session —
            // 'fs pip ctl=2' is the next report's decode line for them
            try {
                updatePipActions();
                com.bolt.browser.util.Diag.log(this, "fs pip ctl=2");
            } catch (Throwable ignored) {}
        } else {
            // v1.3.64 — leaving PiP: stop the pin watchdog, then retire the
            // video pin FOR REAL (fsRemoveCssRescueNow — the guarded variant
            // is a no-op while inPip, and inPip is already false here). The
            // expanded activity must never keep a viewport-pinned video.
            try { ui.removeCallbacks(pipPinBeat); } catch (Throwable ignored) {}
            fsPipPinSaid = false;
            fsPipFightSaid = false;
            fsPipExitSaid = false;
            fsPipGateSaid = false;
            try { fsRemoveCssRescueNow(); } catch (Throwable ignored) {}
            // v1.3.63 — the in-PiP death path returned early and left the
            // immersive window flags armed (SENSOR_LANDSCAPE + KEEP_SCREEN_ON
            // would survive into the expanded, non-fullscreen page); with no
            // custom view the window must go back to normal here
            if (customView == null) {
                try {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } catch (Throwable ignored) {}
                try {
                    setRequestedOrientation(
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                } catch (Throwable ignored) {}
                // v1.3.65 — THE EXPAND-BACK TOOLBAR RESTORE (the v1.3.64
                // report: "the top toolbar disappeared after I returned
                // back to bolt from the pip view. In fullscreen browsing
                // mode"). The in-PiP death path keeps the chrome
                // VISIBILITY-gone (onShowCustomView set topbar/top_group/
                // bottombar GONE; the death path's early return skips the
                // normal restore by design), so this expand-back used to
                // run showBarsNow on GONE views — a translation on a
                // hidden view paints nothing and the toolbar stayed gone.
                // Mirror the normal hide-path restore exactly: visibility
                // first, then the shelf / FAB rules, then the skin, bars.
                try {
                    View tg = findViewById(R.id.top_group);
                    View tb = findViewById(R.id.topbar);
                    View bb = findViewById(R.id.bottombar);
                    if (tg != null) tg.setVisibility(View.VISIBLE);
                    if (tb != null) tb.setVisibility(View.VISIBLE);
                    if (bb != null) bb.setVisibility(View.VISIBLE);
                } catch (Throwable ignored) {}
                try { updateDlShelf(); } catch (Throwable ignored) {}
                try {
                    if (fabMedia != null) fabMedia.setVisibility(
                            mediaPlaying && (prefs.mediaBooster() || prefs.mediaSkip())
                                    ? View.VISIBLE : View.GONE);
                } catch (Throwable ignored) {}
                try { applySkinChrome(); } catch (Throwable ignored) {}
                try { restoreBars(); } catch (Throwable ignored) {}
            }
            try { showBarsNow(false); } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        // v1.4.15 — THE HANDLER SWEEP. sdWinH (the speed-dial hold-to-lift
        // timer) and suggestH (the omnibox network debounce) were created as
        // fields but only ui was ever cleaned here — a destroy with callbacks
        // pending (a held speed-dial item, a 300 ms suggest timer mid-pause)
        // kept the whole dying Activity reachable from its Handler's queued
        // messages. Placed ABOVE the incognito early-return so BOTH process
        // paths sweep. (suggestGen makes any in-flight SUGGEST_BUILD apply a
        // no-op; the ui sweep above already drops its posted apply.)
        sdWinH.removeCallbacksAndMessages(null);
        suggestH.removeCallbacksAndMessages(null);
        suggestPending = null;
        // v1.4.16 — THE DIALOG SWEEP: a destroy with the omnibox sheet up
        // (back-press out of the browser, an OEM kill mid-typing, a
        // configuration recreate) leaked the Dialog window — its Window
        // held the dead Activity's context until the system tore it down.
        // dismissAllOverlays() is NOT called on this path, so the sheet
        // must be dismissed here explicitly. Above the incognito early-return
        // so BOTH process paths sweep.
        try {
            if (omniboxDialog != null && omniboxDialog.isShowing()) omniboxDialog.dismiss();
        } catch (Throwable ignored) {}
        hideOtpChip();                                  // v1.3.31: no dangling fill chip
        hidePwChip();                                   // v1.3.38: password chip too
        // v1.3.60 — THE ORPHAN EXIT (the phantom's source of truth): dying
        // while a fullscreen video is up (closing the PiP window with X, an
        // OEM kill, a crash, a legit recreate) must tell the RENDERER its
        // fullscreen view is gone. Nothing else ever did — the WebView
        // survives in the app-scoped TabManager (zombie/adoption), the page
        // comes back with document.fullscreenElement still set, the player
        // renders "zoomed-in" pseudo-fullscreen under the desktop-UA scale,
        // and PiP eligibility (customView != null) never re-arms: one dying
        // session made BOTH the v1.3.59 field reports ("sometimes full
        // screen is just zoomed in view", "picture in picture is fully
        // broken"). onCustomViewHidden() only collapses the fullscreen
        // STYLING — playback is untouched, so the zombie-audio path and the
        // phoenix keep working exactly as before.
        if (customView != null) {
            try { flRoot.removeView(customView); } catch (Throwable ignored) {}
            try {
                if (customCallback != null) customCallback.onCustomViewHidden();
            } catch (Throwable ignored) {}
            try {
                com.bolt.browser.util.Diag.log(this, "fs orphan host="
                        + com.bolt.browser.util.Fx.hostOf(
                                activeTab() == null ? "" : activeTab().url));
            } catch (Throwable ignored) {}
            customView = null;
            customCallback = null;
        }
        // v1.3.62 — retire the wedged-enter rescue on death too (the WebView
        // SURVIVES the activity in the app-scoped TabManager; a leftover
        // __boltfsf class would pin the next session's video to the viewport
        // outside any fullscreen state). v1.3.64: the NOW variant — the plain
        // one is inPip-guarded, and dying while IN PiP (the window's X) is
        // exactly the case where the zombie must be unpinned.
        try { fsRemoveCssRescueNow(); } catch (Throwable ignored) {}
        pseudoFullscreen = false;
        // v1.3.19: never let the app-scoped download engine hold a dead activity
        if (dlCompletion != null) DownloadEngine.removeCompletionListener(dlCompletion);
        if (dlPopup != null) { try { dlPopup.dismiss(); } catch (Throwable ignored) {} dlPopup = null; }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Throwable ignored) {}
        }
        try { tm.flushSave(); } catch (Throwable ignored) {}   // v1.3.79: synchronous — the process can die right after onDestroy
        // v1.3.77 — THE EXIT SWEEP: the user closed the browser; every cache
        // subtree plus the app's own cacheDir empties physically on the
        // guard's own thread (files a dying renderer still holds open are
        // skipped — the next cold start finishes them). Runs for incognito
        // too (before its early return below): incognito wipes MORE, never
        // less.
        try { com.bolt.browser.util.CacheGuard.exit(this); } catch (Throwable ignored) {}
        if (incognito) {
            wipeIncognito();
            return;
        }
        if (!userExit && MediaControlService.isPlaying()) {
            // v1.3.13 zombie playback: the user did not exit, and media is
            // playing — the WebViews keep living in the app-scoped TabManager
            // (background tabs already run detached in this architecture, so
            // detached playback is proven). Detach them from this dying view
            // tree WITHOUT pausing or destroying; the media service owns the
            // process lifetime from here.
            synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
                for (Tab t : tm.tabs) {
                    if (t.web != null) {
                        try {
                            if (t.web.getParent() instanceof ViewGroup)
                                ((ViewGroup) t.web.getParent()).removeView(t.web);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } else {
            destroyStaleWebViews();
        }
        super.onDestroy();
    }

    /** Destroy every WebView owned by a PREVIOUS activity instance (zombie
     *  leftovers). WebViews created by THIS activity are left for the normal
     *  teardown / next relaunch (same as pre-v1.3.13 behaviour).
     *  v1.3.30 — ADOPTION EXCEPTION: a WebView that holds live/loaded media
     *  from another context (the phoenix's headless resume web, or a zombie
     *  web still playing after the activity died) is NOT destroyed — killing
     *  it is exactly the "reopened Bolt and the music died" bug. showTab()
     *  re-attaches and rewires it to this activity's clients instead. */
    protected void destroyStaleWebViews() {
        try {
            // v1.4.11: the thread law — snapshot under the monitor, destroy
            // OUTSIDE it (a WebView destroy() is long; background readers
            // holding the list's monitor — the media scans, the phoenix —
            // must never wait on a teardown). The snapshot's indices feed
            // onTabGone exactly like the live list's used to.
            List<Tab> staleSnap;
            synchronized (tm.tabs) {
                staleSnap = new ArrayList<>(tm.tabs);
            }
            for (int i = 0; i < staleSnap.size(); i++) {
                Tab t = staleSnap.get(i);
                if (t.web == null) continue;
                if (t.web.getContext() != this) {
                    if (t.mediaPlaying || t.holdsMedia) continue;   // adopt, don't destroy
                    try {
                        if (t.web.getParent() instanceof ViewGroup)
                            ((ViewGroup) t.web.getParent()).removeView(t.web);
                        t.web.destroy();
                    } catch (Throwable ignored) {}
                    t.web = null;
                    t.mediaPlaying = false;
                    t.holdsMedia = false;
                    MediaControlService.onTabGone(this, i);
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean siteBlockBg(Tab t) {
        String host = Fx.hostOf(t.url);
        return !host.isEmpty() && prefs.siteBool(Fx.baseDomain(host), "noBg", false);
    }

    protected void wipeIncognito() {
        wipeIncognitoData();
        // separate process: leave no residue
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** v1.4.19 — the WIPE half of [wipeIncognito], split so the exit
     *  button can finish its activities BEFORE the process dies (see
     *  exitNow: a kill without a finish leaves the task record alive and
     *  the OS simply restarts IncognitoActivity — the "exit lands on
     *  incognito HOME" half of the crash-loop report). */
    protected void wipeIncognitoData() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            WebStorage.getInstance().deleteAllData();
            WebViewDatabase.getInstance(this).clearFormData();
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
        } catch (Throwable ignored) {}
        // v1.4.11: the thread law — snapshot under the monitor, destroy
        // OUTSIDE it (clearCache(true) + destroy() are long teardowns)
        List<Tab> wipeSnap;
        synchronized (tm.tabs) { wipeSnap = new ArrayList<>(tm.tabs); }
        for (Tab t : wipeSnap) {
            if (t.web != null) {
                try { t.web.clearCache(true); t.web.destroy(); } catch (Throwable ignored) {}
            }
        }
        synchronized (tm.tabs) { tm.tabs.clear(); }
    }

    // ------------------------------------------------------------ wiring

    /** v1.4.19 — THE OFFLINE ERROR OVERLAY. The old onReceivedError built a
     *  sad-page HTML and called view.loadData(...) — loadData REPLACES the
     *  WebView's URL with a data:text/html,... URL, doUpdateVisitedHistory
     *  then wrote THAT into t.url, and tabs.json persisted it, so the tab
     *  reloaded the ERROR PAGE forever on restart (the captured-URL corpse:
     *  the tab's address bar literally read data:text/html,<html><body…).
     *  The error is now a native overlay ON TOP of flContent: the tab's
     *  WebView URL, history entry and persisted url all keep the ORIGINAL
     *  page, the overlay carries a Retry button that re-loads it, and a
     *  Diag breadcrumb records the URL that must survive. */
    private android.view.View errOverlay = null;

    protected void showErrorOverlay(WebView view, String origUrl) {
        try {
            Tab t = tabOf(view);
            if (t != null) t.errUrl = origUrl;
            hideErrorOverlay();
            LinearLayout ov = new LinearLayout(this);
            ov.setOrientation(LinearLayout.VERTICAL);
            ov.setGravity(Gravity.CENTER);
            ov.setBackgroundColor(Fx.webBg(this));
            int pad = Fx.dp(this, 24);
            ov.setPadding(pad, pad, pad, pad);
            TextView face = new TextView(this);
            face.setText(":(");
            face.setTextSize(34f);
            face.setGravity(Gravity.CENTER);
            face.setTextColor(Fx.txt(this));
            ov.addView(face, new LinearLayout.LayoutParams(-2, -2));
            TextView msg = new TextView(this);
            msg.setText("Can't reach this page");
            msg.setTextSize(16f);
            msg.setGravity(Gravity.CENTER);
            msg.setTextColor(Fx.txt(this));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
            mp.topMargin = Fx.dp(this, 10);
            ov.addView(msg, mp);
            TextView url = new TextView(this);
            url.setText(origUrl);
            url.setTextSize(12f);
            url.setGravity(Gravity.CENTER);
            url.setMaxLines(2);
            url.setTextColor(Fx.dim(this));
            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-1, -2);
            up.topMargin = Fx.dp(this, 8);
            up.leftMargin = Fx.dp(this, 12);
            up.rightMargin = Fx.dp(this, 12);
            ov.addView(url, up);
            // v1.4.19 — the RETRY affordance (the spec's third leg): a
            // failed load must be one tap from trying again
            android.widget.Button retry = new android.widget.Button(this);
            retry.setText("Retry");
            retry.setTextColor(android.graphics.Color.WHITE);
            GradientDrawable rbg = new GradientDrawable();
            rbg.setColor(Fx.accent(this));
            rbg.setCornerRadius(Fx.dp(this, 10));
            retry.setBackground(rbg);
            retry.setPadding(Fx.dp(this, 22), Fx.dp(this, 10), Fx.dp(this, 22), Fx.dp(this, 10));
            final WebView w = view;
            final String target = origUrl;
            retry.setOnClickListener(b -> {
                hideErrorOverlay();
                try { w.loadUrl(target); } catch (Throwable ignored) {}
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
            rp.topMargin = Fx.dp(this, 22);
            ov.addView(retry, rp);
            flContent.addView(ov, new android.view.ViewGroup.LayoutParams(-1, -1));
            flContent.bringChildToFront(ov);
            errOverlay = ov;
        } catch (Throwable ignored) {}
    }

    protected void hideErrorOverlay() {
        try {
            if (errOverlay != null) {
                android.view.ViewGroup p = (android.view.ViewGroup) errOverlay.getParent();
                if (p != null) p.removeView(errOverlay);
                errOverlay = null;
            }
        } catch (Throwable ignored) {}
    }

    protected void wireViews() {
        flRoot = findViewById(R.id.fl_root);
        flContent = findViewById(R.id.fl_content);
        btnSiteSettings = findViewById(R.id.btn_site_settings);
        btnReload = findViewById(R.id.btn_reload);
        btnMore = findViewById(R.id.btn_more);
        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);
        btnHome = findViewById(R.id.btn_home);
        // v1.3.35: the tabs button is the count square itself.
        // v1.3.78: the CELL is the button — the 22x24dp square was a
        // miss-prone target and taps around it fell through the overlay
        // to the page (the v1.3.77 field report); the full-height cell
        // (btn_tabs, with the same ripple as its neighbours) owns the click
        tvTabCount = findViewById(R.id.tv_tab_count);
        btnTabs = findViewById(R.id.btn_tabs);
        btnMenu = findViewById(R.id.btn_menu);
        tvUrl = findViewById(R.id.tv_url);
        ivLock = findViewById(R.id.iv_lock);
        vProgress = findViewById(R.id.v_progress);
        findbar = findViewById(R.id.findbar);
        etFind = findViewById(R.id.et_find);
        tvFindCount = findViewById(R.id.tv_find_count);
        btnFindPrev = findViewById(R.id.btn_find_prev);
        btnFindNext = findViewById(R.id.btn_find_next);
        btnFindClose = findViewById(R.id.btn_find_close);
        ttsbar = findViewById(R.id.ttsbar);
        btnTtsPlay = findViewById(R.id.btn_tts_play);
        btnTtsStop = findViewById(R.id.btn_tts_stop);
        tvTtsStatus = findViewById(R.id.tv_tts_status);
        fabMedia = findViewById(R.id.fab_media);

        int dim = Fx.tint(this);
        int[] tinted = {R.id.btn_site_settings, R.id.btn_reload, R.id.btn_more, R.id.btn_back,
                R.id.btn_forward, R.id.btn_home, R.id.btn_menu,
                R.id.btn_find_prev, R.id.btn_find_next, R.id.btn_find_close, R.id.btn_tts_stop};
        for (int id : tinted) {
            View v = findViewById(id);
            if (v instanceof ImageView) {
                ((ImageView) v).setColorFilter(dim);
                // v1.3.35: Mono's thicker monoline icon weight — the same vector
                // drawn twice with a ~1dp offset (Ui.embolden)
                com.bolt.browser.util.Ui.skinIcon((ImageView) v);
            }
        }
        ivLock.setColorFilter(dim);
        fabMedia.setColorFilter(0xFFFFFFFF);

        // v1.3.33 — skin the floating chrome + wire scroll-to-hide bars
        applySkinChrome();
        setupBarScrollHiding();

        // v1.3.35 — pull-to-refresh: the content pane handles the nested-scroll
        // overscroll at the page top; a refresh reloads the active tab (or
        // refreshes the home screen) and onPageFinished retires the spinner.
        if (flContent instanceof MediaAwareContent) {
            MediaAwareContent mac = (MediaAwareContent) flContent;
            mac.setPullEnabled(prefs.pullToRefresh());
            mac.setOnRefreshListener(() -> {
                Tab t = activeTab();
                if (t == null || t.isHome) {
                    if (t != null) refreshHome();
                    mac.setRefreshing(false);
                } else if (t.web != null) {
                    t.web.reload();   // spinner retires in onPageFinished
                } else {
                    mac.setRefreshing(false);
                }
            });
        }

        // press feedback: scale + icon tint shift while pressed. v1.3.46: the
        // seven bare bar icons use the GLASS-AWARE variant (their tint follows
        // the adaptive verdict, which can flip mid-press)
        pressFxBar(btnSiteSettings, btnSiteSettings);
        pressFxBar(btnReload, btnReload);
        pressFxBar(btnMore, btnMore);
        pressFxBar(btnBack, btnBack);
        pressFxBar(btnForward, btnForward);
        pressFxBar(btnHome, btnHome);
        // v1.3.79 — THE VISIBLE TAB PRESS. The v1.3.78 cell-scale was
        // invisible: scaling a FULL-HEIGHT cell around its center moves the
        // little 22x24dp square a couple of pixels and nothing else. The
        // square itself now answers the press (scale + accent flash +
        // haptic) while the cell keeps owning the click.
        pressFxTabCell(btnTabs, tvTabCount);
        pressFxBar(btnMenu, btnMenu);
        pressFx(btnFindPrev, btnFindPrev, dim);
        pressFx(btnFindNext, btnFindNext, dim);
        pressFx(btnFindClose, btnFindClose, dim);
        pressFx(btnTtsStop, btnTtsStop, dim);
        pressFx(btnTtsPlay, btnTtsPlay, dim);   // v1.3.79: the one bar button that had none

        btnSiteSettings.setOnClickListener(v -> showSiteSettings());
        btnReload.setOnClickListener(v -> {
            Tab t = activeTab();
            if (t.isHome) refreshHome();
            else if (t.web != null) t.web.reload();
        });
        btnMore.setOnClickListener(v -> showMainMenu());
        btnBack.setOnClickListener(v -> goBack());
        btnForward.setOnClickListener(v -> {
            Tab t = activeTab();
            // v1.3.84 — the audited history step (see HistoryNav): the
            // forward twin of the toolbar back
            if (t.web != null && t.web.canGoForward())
                com.bolt.browser.util.HistoryNav.step(BrowserActivity.this, t.web, 1);
        });
        btnHome.setOnClickListener(v -> goHome());
        btnTabs.setOnClickListener(v -> openTabsView());
        btnMenu.setOnClickListener(v -> Ui.launchSatellite(this, SettingsHomeActivity.class));
        tvUrl.setOnClickListener(v -> showOmnibox());
        fabMedia.setOnClickListener(v -> showMediaSheet());
        setupFabDrag();
        setupHoverButton();     // v1.3.39 — fullscreen browsing's moveable restore button
        tvUrl.setOnLongClickListener(v -> {
            showSiteSettings();
            return true;
        });

        // v1.3.37 — LONG-PRESS QUICK ACTIONS on every toolbar button (the
        // reload button's old fixed hard-reload long-press is now just its
        // DEFAULT: remappable in Appearance → Interaction). Each press
        // haptic-ticks then dispatches the configured code.
        btnReload.setOnLongClickListener(v -> quickPress("reload", v));
        btnMore.setOnLongClickListener(v -> quickPress("more", v));
        btnBack.setOnLongClickListener(v -> quickPress("back", v));
        btnForward.setOnLongClickListener(v -> quickPress("forward", v));
        btnHome.setOnLongClickListener(v -> quickPress("home", v));
        btnTabs.setOnLongClickListener(v -> quickPress("tabs", v));
        btnMenu.setOnLongClickListener(v -> quickPress("menu", v));

        btnFindPrev.setOnClickListener(v -> {
            Tab t = activeTab();
            if (t.web != null) t.web.findNext(false);
        });
        btnFindNext.setOnClickListener(v -> {
            Tab t = activeTab();
            if (t.web != null) t.web.findNext(true);
        });
        btnFindClose.setOnClickListener(v -> closeFind());
        etFind.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                Tab t = activeTab();
                if (t.web != null && s.length() > 0) t.web.findAllAsync(s.toString());
                else if (t.web != null) t.web.clearMatches();
            }
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
        });
        etFind.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                Tab t = activeTab();
                if (t.web != null) t.web.findNext(true);
                return true;
            }
            return false;
        });

        btnTtsPlay.setOnClickListener(v -> toggleTts());
        btnTtsStop.setOnClickListener(v -> stopTts());
    }

    protected void refreshHome() {
        dismissSdFolderWindow();   // v1.4.0: the grid under the window is being rebuilt
        detachTabsForSwitch(tm.active);   // v1.3.19: media tabs stay attached
        buildHomeView();
        showTab(tm.active);
    }

    /** v1.3.97 — a drag-driven rebuild (a reorder drop, a dwell merge) is
     *  QUIET and POSITION-TRUE: the same pager page and the same scroll the
     *  finger left come back (snapshotted here, consumed by buildHomeView),
     *  and the entrance stagger does not replay (the debounce window is
     *  pre-armed so animateHomeGrid no-ops). A plain refreshHome here is
     *  what the field read as "the shortcut icons causes home page to
     *  refresh when dragged" — the home is NOT being opened, it is being
     *  EDITED; an edit must not reset the view. */
    protected void refreshHomeQuiet() {
        try { if (sdPager != null) homeRestorePage = sdPager.getCurrentItem(); }
        catch (Throwable ignored) {}
        try { if (homeScroll != null) homeRestoreScroll = homeScroll.getScrollY(); }
        catch (Throwable ignored) {}
        lastHomeGridAnim = android.os.SystemClock.uptimeMillis();
        refreshHome();
    }

    // ------------------------------------------------------------ v1.3.36 chrome layout + edge swipe

    /** true when the URL bar is docked at the BOTTOM (the urlBarBottom
     *  pref) — every chrome helper mirrors its geometry around this. */
    protected boolean urlBarAtBottom() { return prefs.urlBarBottom(); }

    /** v1.3.36 — the Opera-style "address bar at bottom" placement: the
     *  chrome column re-orders so the NAV row sits at the top and the URL
     *  row (with find/TTS riders) docks at the bottom. Everything addresses
     *  the bars by ID, so this is a pure child-order swap; the skin pass
     *  (applySkinChrome) and the bar-slide directions mirror accordingly. */
    protected void applyChromeLayout() {
        try {
            if (!urlBarAtBottom()) return;
            LinearLayout chrome = (LinearLayout) findViewById(R.id.top_group).getParent();
            View tg = findViewById(R.id.top_group);
            View bb = findViewById(R.id.bottombar);
            View shelf = findViewById(R.id.dl_shelf);
            chrome.removeView(tg);
            chrome.removeView(bb);
            if (shelf != null) chrome.removeView(shelf);
            // after removal the Space (weight 1) is the only child left:
            // nav row at the top, URL row docked at the bottom, and the
            // v1.3.40 download shelf directly ABOVE the bottom-docked row
            // (it hugs the bottom edge in both layouts)
            chrome.addView(bb, 0);
            if (shelf != null) chrome.addView(shelf, 2);
            chrome.addView(tg, shelf != null ? 3 : 2);
        } catch (Throwable ignored) {}
        applySkinChrome();
    }

    // ------------------------------------------------------------ v1.3.40 download shelf

    /** find the shelf views + build the (reusable) engine listener. The
     *  listener is NOT attached here — see ensureDlShelfListener. */
    protected void setupDlShelf() {
        try {
            dlShelf = findViewById(R.id.dl_shelf);
            dlShelfItems = findViewById(R.id.ll_shelf_items);
            if (dlShelf == null || dlShelfItems == null) return;
            dlShelfListener = () -> ui.post(() -> {
                ui.removeCallbacks(dlShelfRefresh);
                ui.postDelayed(dlShelfRefresh, 200);   // throttle progress ticks
            });
            updateDlShelf();
        } catch (Throwable ignored) {}
    }

    /** attach the shelf listener to the (existing!) engine — called only
     *  after an enqueue from this activity or a warm start with the engine
     *  alive, so a cold start never constructs the engine. */
    protected void ensureDlShelfListener() {
        if (dlShelfListener == null || dlShelf == null) return;
        try { DownloadEngine.get(this).addListener(dlShelfListener); } catch (Throwable ignored) {}
    }

    /** rebuild the shelf chips: ACTIVE items (queued/running/paused) first
     *  in queue order, then up to 3 terminal items THIS session watched run
     *  and the user has not dismissed. Empty → strip hides itself. */
    protected void updateDlShelf() {
        if (dlShelf == null || dlShelfItems == null || isFinishing()) return;
        try {
            dlShelfItems.removeAllViews();
            List<DownloadEngine.Item> show = new ArrayList<>();
            if (DownloadEngine.exists()) {
                List<DownloadEngine.Item> list = DownloadEngine.get(this).list();
                for (DownloadEngine.Item it : list) {
                    if (it.status == DownloadEngine.ST_QUEUED
                            || it.status == DownloadEngine.ST_RUNNING
                            || it.status == DownloadEngine.ST_PAUSED) {
                        show.add(it);
                        dlShelfSeen.add(it.id);
                        dlShelfDismissed.remove(it.id);
                    }
                }
                int terminal = 0;
                for (int i = list.size() - 1; i >= 0 && terminal < 3; i--) {
                    DownloadEngine.Item it = list.get(i);
                    if (it.status != DownloadEngine.ST_DONE && it.status != DownloadEngine.ST_FAILED) continue;
                    if (!dlShelfSeen.contains(it.id) || dlShelfDismissed.contains(it.id)) continue;
                    show.add(it);
                    terminal++;
                }
            }
            for (DownloadEngine.Item it : show) dlShelfItems.addView(buildDlChip(it));
            dlShelf.setVisibility(show.isEmpty() || customView != null
                    ? View.GONE : View.VISIBLE);
        } catch (Throwable ignored) {}
    }

    /** one shelf chip: [icon | name / status | primary action | ✕]. */
    protected View buildDlChip(final DownloadEngine.Item it) {
        final DownloadEngine eng = DownloadEngine.get(this);
        int dim = Fx.dim(this), txt = Fx.txt(this);
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        try { chip.setBackground(Fx.cardBg(this, 14)); } catch (Throwable ignored) {}
        chip.setPadding(Fx.dp(this, 10), Fx.dp(this, 6), Fx.dp(this, 4), Fx.dp(this, 6));

        ImageView ic = new ImageView(this);
        // v1.3.59: the chip wears the FILE-TYPE icon (brand hue) instead of
        // the generic download glyph — same vocabulary as the redesigned
        // Downloads screen
        String dcat = com.bolt.browser.ui.DlTypes.INSTANCE.catOf(it.mime, it.name);
        ic.setImageResource(com.bolt.browser.ui.DlTypes.INSTANCE.iconOf(dcat));
        ic.setColorFilter(com.bolt.browser.ui.DlTypes.INSTANCE.colorOf(dcat));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Fx.dp(this, 18), Fx.dp(this, 18));
        ilp.rightMargin = Fx.dp(this, 8);
        chip.addView(ic, ilp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(it.name == null ? "" : it.name);
        name.setTextColor(txt);
        name.setTextSize(14);
        name.setTypeface(com.bolt.browser.util.Ui.medium());
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView sub = new TextView(this);
        sub.setText(dlShelfStatus(it));
        sub.setTextColor(dim);
        sub.setTextSize(12);
        sub.setMaxLines(1);
        col.addView(name);
        col.addView(sub);
        chip.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageButton act = new ImageButton(this);
        act.setBackgroundResource(R.drawable.bg_item);
        act.setColorFilter(dim);
        int blp = Fx.dp(this, 38);
        if (it.status == DownloadEngine.ST_RUNNING || it.status == DownloadEngine.ST_QUEUED) {
            act.setImageResource(R.drawable.ic_pause);
            act.setContentDescription(getString(R.string.pause_dl));
            act.setOnClickListener(v -> eng.pause(it.id));
        } else if (it.status == DownloadEngine.ST_PAUSED) {
            act.setImageResource(R.drawable.ic_play);
            act.setContentDescription(getString(R.string.resume_dl));
            act.setOnClickListener(v -> eng.resume(it.id));
        } else if (it.status == DownloadEngine.ST_FAILED) {
            act.setImageResource(R.drawable.ic_reload);
            act.setContentDescription(getString(R.string.retry));
            act.setOnClickListener(v -> eng.retry(it.id));
        } else {
            act.setImageResource(R.drawable.ic_folder);
            act.setContentDescription(getString(R.string.open_file));
            // v1.3.59: opening counts as handled — the chip retires with it
            act.setOnClickListener(v -> openShelfDone(it));
        }
        chip.addView(act, new LinearLayout.LayoutParams(blp, blp));

        ImageButton x = new ImageButton(this);
        x.setImageResource(R.drawable.ic_close);
        x.setBackgroundResource(R.drawable.bg_item);
        x.setColorFilter(dim);
        x.setContentDescription(getString(R.string.cancel));
        x.setOnClickListener(v -> {
            if (it.status == DownloadEngine.ST_QUEUED || it.status == DownloadEngine.ST_RUNNING
                    || it.status == DownloadEngine.ST_PAUSED) {
                eng.cancel(it.id);
            } else {
                dlShelfDismissed.add(it.id);
            }
            updateDlShelf();
        });
        chip.addView(x, new LinearLayout.LayoutParams(blp, blp));

        // tapping the chip body = the same primary action as the button
        chip.setOnClickListener(v -> {
            if (it.status == DownloadEngine.ST_DONE) openShelfDone(it);
            else if (it.status == DownloadEngine.ST_FAILED) eng.retry(it.id);
            else if (it.status == DownloadEngine.ST_PAUSED) eng.resume(it.id);
            else eng.pause(it.id);
        });
        return chip;
    }

    /** the chip's status line (percent · speed / state). */
    protected String dlShelfStatus(DownloadEngine.Item it) {
        try {
            int pct = it.total > 0 ? (int) Math.min(100, 100 * it.done / it.total) : -1;
            switch (it.status) {
                case DownloadEngine.ST_RUNNING:
                    if (it.speed > 0) return (pct >= 0 ? pct + "% · " : "")
                            + android.text.format.Formatter.formatShortFileSize(this, it.speed) + "/s";
                    return pct >= 0 ? pct + "% · downloading" : "downloading";
                case DownloadEngine.ST_QUEUED: return "queued";
                case DownloadEngine.ST_PAUSED: return (pct >= 0 ? pct + "% · " : "") + "paused";
                case DownloadEngine.ST_FAILED: return "failed · tap to retry";
                default: return "done · tap to open";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** open a finished shelf item — reuses the download-popup's opener. */
    protected void openShelfFile(DownloadEngine.Item it) {
        openShelfDone(it);
    }

    /** v1.3.59 — "opened to view" retires the chip. Field report: "I want
     *  the bottom pop up to go away once I either have opened to view it or
     *  closed it" — every OPEN path off the shelf now dismisses the chip
     *  (the ✕ / dialog paths already did their half). */
    protected void openShelfDone(DownloadEngine.Item it) {
        try {
            dlShelfDismissed.add(it.id);
            updateDlShelf();
        } catch (Throwable ignored) {}
        openDownloadedFile(it);
    }

    /** v1.3.36 — EDGE-SWIPE NAVIGATION (pref-gated): an invisible full-screen
     *  overlay consumes ONLY touches that begin in the ~22dp screen edges;
     *  every other touch falls through untouched (a non-clickable View that
     *  returns false from onTouchEvent is transparent to the dispatch chain —
     *  the same property the floating chrome relies on). Dragging inward from
     *  the left edge = BACK, from the right = FORWARD, with a floating arrow
     *  indicator that grows with the drag (the Opera/Samsung feel). */
    protected void setupEdgeSwipe() {
        try {
            if (!prefs.edgeNav() || flRoot == null) return;
            if (edgeNavView != null && edgeNavView.getParent() == flRoot) return;
            edgeNavView = new View(this) {
                private float downX, downY;
                private boolean tracking, horizontal;
                @Override
                public boolean onTouchEvent(android.view.MotionEvent e) {
                    int edge = Fx.dp(getContext(), 22);
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downX = e.getX();
                            downY = e.getY();
                            // only the two screen edges belong to us
                            tracking = downX <= edge || downX >= getWidth() - edge;
                            horizontal = false;
                            return tracking;
                        case android.view.MotionEvent.ACTION_MOVE:
                            if (!tracking) return false;
                            float dx = e.getX() - downX, dy = e.getY() - downY;
                            if (!horizontal && Math.abs(dx) > Fx.dp(getContext(), 14)
                                    && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                                horizontal = true;
                            }
                            if (horizontal) showEdgeIndicator(downX <= edge, dx);
                            return true;
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL:
                            if (tracking && horizontal) {
                                float dx2 = e.getX() - downX;
                                if (Math.abs(dx2) > Fx.dp(getContext(), 48)) {
                                    if (downX <= edge && dx2 > 0) goBack();
                                    else if (downX >= getWidth() - edge && dx2 < 0) {
                                        Tab t = activeTab();
                                        if (t != null && t.web != null && t.web.canGoForward())
                                            com.bolt.browser.util.HistoryNav.step(
                                                    BrowserActivity.this, t.web, 1);
                                    }
                                }
                            }
                            tracking = false;
                            horizontal = false;
                            hideEdgeIndicator();
                            return tracking;
                    }
                    return false;
                }
            };
            // sits directly above the CONTENT and below everything else
            // (chrome, FAB, and the blur layers the glass pass adds later) —
            // toolbar buttons and the media FAB keep their touches, and the
            // page keeps every non-edge touch
            int idx = flRoot.indexOfChild(flContent) + 1;
            flRoot.addView(edgeNavView, idx, new FrameLayout.LayoutParams(-1, -1));
        } catch (Throwable ignored) {}
    }

    protected View edgeNavView;

    /** the floating back/forward arrow shown while an edge drag is armed —
     *  a soft accent circle whose scale/alpha follow the drag distance. */
    protected void showEdgeIndicator(boolean leftEdge, float dx) {
        try {
            if (edgeIndicator == null) {
                edgeIndicator = new ImageView(this);
                android.graphics.drawable.GradientDrawable g =
                        new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                g.setColor(Fx.accentSoft(this));
                g.setStroke(Math.max(1, Fx.dp(this, 1)), Fx.outline(this));
                edgeIndicator.setBackground(g);
                int s = Fx.dp(this, 46);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s,
                        Gravity.CENTER_VERTICAL | (leftEdge ? Gravity.START : Gravity.END));
                lp.leftMargin = lp.rightMargin = Fx.dp(this, 18);
                flRoot.addView(edgeIndicator, lp);
            }
            // only show an arrow for the direction the gesture can complete
            boolean back = leftEdge && dx > 0;
            boolean fwd = !leftEdge && dx < 0;
            if (!back && !fwd) { hideEdgeIndicator(); return; }
            edgeIndicator.setImageResource(back ? R.drawable.ic_back : R.drawable.ic_forward);
            edgeIndicator.setColorFilter(Fx.accentText(this));
            float p = Math.min(1f, Math.abs(dx) / Fx.dp(this, 90));
            edgeIndicator.setScaleX(0.6f + 0.4f * p);
            edgeIndicator.setScaleY(0.6f + 0.4f * p);
            edgeIndicator.setAlpha(0.25f + 0.75f * p);
            edgeIndicator.bringToFront();
        } catch (Throwable ignored) {}
    }

    protected ImageView edgeIndicator;

    protected void hideEdgeIndicator() {
        try {
            if (edgeIndicator != null && edgeIndicator.getParent() == flRoot) {
                flRoot.removeView(edgeIndicator);
            }
            edgeIndicator = null;
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ v1.3.37 toolbar swipe → switch tabs

    /**
     * v1.3.37 — SWIPE A TOOLBAR TO SWITCH TABS (pref toolbarSwipe): a
     * horizontal drag that starts on the address bar or the nav bar cycles
     * to the next/previous tab, with a floating pill that previews the
     * target ("3/7 · page title") while the finger moves.
     *
     * IMPLEMENTATION NOTE — why this watches at the ACTIVITY level instead
     * of a bar's OnTouchListener: both bars are full of clickable children
     * (buttons, the URL field), and a listener that returns false on DOWN
     * only keeps receiving events while its view stays the touch target
     * (the v1.3.36 lesson). dispatchTouchEvent sees EVERY touch before any
     * child, so it can observe from above: taps fall through untouched
     * (clicks fire exactly as before), and once the drag is unmistakably
     * horizontal a synthetic ACTION_CANCEL is pushed through
     * super.dispatchTouchEvent so the pressed button/omnibox aborts its
     * pending click — then this activity owns the gesture to the end.
     * Dialog-based overlays (omnibox, sheets) sit in other windows and are
     * naturally unaffected.
     */
    private float tswipeDownX, tswipeDownY;
    private boolean tswipeArmed, tswipeHorizontal, tswipeOnTopBar;
    private TextView tswipePill;

    /** v1.3.51 — THE TOOLBAR RETURN GUARANTEE (field: "while scrolling,
     *  toolbars disappear but refuse to appear back when scrolling back up;
     *  they only come back after reopening the app from recents"). A finger
     *  dragging DOWN the screen IS a scroll up — and this observer sees every
     *  touch at the ACTIVITY level, so the bars come back even when the
     *  WebView's own scroll listener is gated (an overlay flag that outlived
     *  its UI) or dead (a re-parented/rebuilt view): the raw gesture stream
     *  is the one channel that cannot go stale. Lesson 20's observer pattern
     *  again — watch from above, never intercept. */
    private float scrollShowDownY = 0f;
    private boolean scrollShowArmed = false;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        try {
            int act = e.getActionMasked();
            if (act == android.view.MotionEvent.ACTION_DOWN) {
                tswipeHorizontal = false;
                scrollShowDownY = e.getY();
                // arm for EVERY gesture: once per touch, only when the bars
                // are actually away — a shown state disarms immediately so a
                // downward drag INSIDE the omnibox dropdown or a sheet can
                // never fire a redundant show
                scrollShowArmed = !barsShown && customView == null
                        && !com.bolt.browser.util.Fullscreen.on(this);
                tswipeArmed = prefs != null && prefs.toolbarSwipe() && tm != null
                        && tm.tabs.size() > 1 && barsShown && touchOnChromeBar(e);
                if (tswipeArmed) {
                    tswipeDownX = e.getX();
                    tswipeDownY = e.getY();
                }
            } else if (act == android.view.MotionEvent.ACTION_MOVE && scrollShowArmed) {
                // 24dp of deliberate downward finger travel = a scroll up.
                // Taps never qualify; the bars return on the same flick the
                // user expects them to. The quick panel is an in-window
                // overlay that rides the bars — never re-show the chrome
                // while it is open (its chips scroll horizontally anyway).
                if (e.getY() - scrollShowDownY > Fx.dp(this, 24)) {
                    scrollShowArmed = false;
                    if (quickPanel == null || quickPanel.getVisibility() != View.VISIBLE)
                        showBarsNow(true);
                }
                if (barsShown) scrollShowArmed = false;
            }
            if (act == android.view.MotionEvent.ACTION_UP
                    || act == android.view.MotionEvent.ACTION_CANCEL)
                scrollShowArmed = false;
            if (tswipeHorizontal) {
                // the gesture is ours until the finger lifts
                if (act == android.view.MotionEvent.ACTION_MOVE) {
                    showTabSwipePill(e.getX() - tswipeDownX);
                    return true;
                } else {
                    float dx = e.getX() - tswipeDownX;
                    if (act == android.view.MotionEvent.ACTION_UP
                            && Math.abs(dx) > Fx.dp(this, 56)) {
                        int target = tabSwipeTarget(dx);
                        if (target >= 0 && target != tm.active) {
                            com.bolt.browser.util.Ui.tap(flRoot);
                            // v1.3.96 — THE DISPATCH LAW (same class as the
                            // home-drag crash): showTab swaps the tab's view
                            // tree, and this runs INSIDE the UP dispatch.
                            // Posted past the event so the framework's touch
                            // loops finish over a stable tree.
                            final int swTarget = target;
                            ui.post(() -> {
                                try {
                                    if (tm != null && swTarget >= 0
                                            && swTarget < tm.tabs.size()
                                            && swTarget != tm.active)
                                        showTab(swTarget, true);   // viaSwipe — no re-stamp
                                } catch (Throwable ignored) {}
                            });
                        }
                    }
                    hideTabSwipePill();
                    tswipeArmed = tswipeHorizontal = false;
                    return true;
                }
            } else if (tswipeArmed && act == android.view.MotionEvent.ACTION_MOVE) {
                // confirming MOVE: declare ownership + cancel the child press
                float dx = e.getX() - tswipeDownX, dy = e.getY() - tswipeDownY;
                if (Math.abs(dx) > Fx.dp(this, 26)
                        && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    tswipeHorizontal = true;
                    try {
                        android.view.MotionEvent c = android.view.MotionEvent.obtain(
                                e.getDownTime(), e.getEventTime(),
                                android.view.MotionEvent.ACTION_CANCEL,
                                e.getX(), e.getY(), e.getMetaState());
                        super.dispatchTouchEvent(c);
                        c.recycle();
                    } catch (Throwable ignored) {}
                    showTabSwipePill(dx);
                    return true;
                }
            } else if (act == android.view.MotionEvent.ACTION_UP
                    || act == android.view.MotionEvent.ACTION_CANCEL) {
                tswipeArmed = false;              // never armed across gestures
            }
        } catch (Throwable ignored) {}
        return super.dispatchTouchEvent(e);
    }

    /** was the DOWN inside one of the two floating bars (window coords,
     *  translation-aware for the scroll-to-hide slides)? The draggable media
     *  FAB is excluded — its drag must never become a tab switch. */
    private boolean touchOnChromeBar(android.view.MotionEvent e) {
        try {
            if (fabMedia != null && fabMedia.getVisibility() == View.VISIBLE) {
                int[] fLoc = new int[2];
                fabMedia.getLocationInWindow(fLoc);
                int fy = fLoc[1] + (int) fabMedia.getTranslationY();
                if (e.getX() >= fLoc[0] && e.getX() <= fLoc[0] + fabMedia.getWidth()
                        && e.getY() >= fy && e.getY() <= fy + fabMedia.getHeight()) {
                    return false;
                }
            }
        } catch (Throwable ignored) {}
        View top = findViewById(R.id.topbar);
        View bottom = findViewById(R.id.bottombar);
        if (inChromeBarRect(top, e)) { tswipeOnTopBar = true; return true; }
        if (inChromeBarRect(bottom, e)) { tswipeOnTopBar = false; return true; }
        return false;
    }

    private boolean inChromeBarRect(View v, android.view.MotionEvent e) {
        try {
            if (v == null || v.getVisibility() != View.VISIBLE) return false;
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            int ty = loc[1] + (int) v.getTranslationY();
            return e.getX() >= loc[0] && e.getX() <= loc[0] + v.getWidth()
                    && e.getY() >= ty && e.getY() <= ty + v.getHeight();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * v1.3.46 — THE WALK IS THE PIXELS. Three field rounds of "the swipe is
     *  opposite" (v1.3.43 ping-pong, v1.3.44 re-stamp, v1.3.45 wrap) could
     *  not be reproduced against the derived walk — sortedForDisplay() is
     *  the SAME function the switcher renders, and in manual mode the walk
     *  is provably the rendered order. So the walk now COPIES the switcher's
     *  rendered arrangement (TabsActivity.renderedWalk(): the loose tiles in
     *  exactly the order the grid laid them out, refreshed on every switcher
     *  rebuild). Left swipe = the tile BEFORE the current one in what the
     *  user last SAW; right = after; no wrap (v1.3.45). Falls back to the
     *  derived walk only before the switcher has ever opened this process,
     *  or when the current tab is foldered (not a tile — the fallback walks
     *  its own folder's members).
     */
    private List<Tab> tabSwipeOrder() {
        try {
            Tab cur = tm.tabs.get(Math.max(0, Math.min(tm.active, tm.tabs.size() - 1)));
            if (cur != null) {
                List<Tab> snap = com.bolt.browser.ui.TabsActivity.renderedWalk();
                if (snap != null && snap.size() > 1) {
                    // keep only live tabs (the snapshot may outlive closes)
                    List<Tab> live = new ArrayList<>();
                    for (Tab t : snap) if (tm.tabs.contains(t)) live.add(t);
                    if (live.contains(cur) && live.size() > 1) return live;
                }
            }
            List<Tab> order = tm.sortedForDisplay(prefs.tabsSort());
            long curGid = cur == null ? 0 : cur.groupId;
            List<Tab> visible = new ArrayList<>();
            for (Tab t : order)
                if (t.groupId == 0 || (curGid != 0 && t.groupId == curGid))
                    visible.add(t);
            if (visible.size() > 1) return visible;
            return order;
        } catch (Throwable t) {
            return tm.tabs;
        }
    }

    /**
     * v1.3.38 FIELD FIX — the swipe direction matches the tab row's own
     * geometry: SWIPE RIGHT opens the tab that sits to the RIGHT of the
     * current one in the switcher, SWIPE LEFT the one to the LEFT.
     *
     * v1.3.41 FIELD FIX — "to the right of current tab" means the DISPLAYED
     * arrangement (sortedForDisplay of the live sort), every mode.
     *
     * v1.3.44 FIELD FIX — swipe switches skip the lastVisitedAt re-stamp
     * (showTab(idx, viaSwipe)) so the walk never re-sorts itself mid-march,
     * and the walk is the VISIBLE tile set (tabSwipeOrder).
     *
     * v1.3.45 FIELD FIX ("as confusing as ever") — THE WRAPAROUND IS GONE.
     * The cyclic walk was the last mirror: with two tiles [A, B], swiping
     * RIGHT from B wrapped to A — literally "swipe right opens the LEFT
     * tab", the exact complaint. Swiping toward a side with no further
     * tile now does NOTHING (edge): swipe right opens the next tile to the
     * right, swipe left the previous tile to the left, and at either end
     * the gesture simply has nowhere to go — the pill says so by dimming
     * (no target, no arrow). Repeated one-direction swipes march to the
     * end of the row/grid and stop.
     *
     * v1.3.47 FIELD FIX (LESSON 32 — four rounds solved it) — THE USER'S
     * SWIPE NAMING IS INVERTED RELATIVE TO THE FINGER. Their "swipe left"
     * is the finger moving RIGHT (they name the direction the STRIP travels
     * under the finger / the side they aim AT, not the finger's travel).
     * Cross-checked against EVERY field report: v1.3.45 "3 tabs serial
     * [gf, yt, gg], youtube open, manual — if I swip left I should move to
     * greasyfork but I am moving to google": the v1.3.45/46 code opened
     * pos-1 on finger-LEFT (= gf), so the gesture that actually produced
     * GOOGLE was finger-RIGHT — which is what the user calls "swipe left".
     * v1.3.46 "swipe left is opening right tab and swipe right is opening
     * left tab" describes the SAME code exactly under that naming. THE
     * WANTED SEMANTICS = standard ViewPager: finger LEFT -> the NEXT tile
     * (visually to the right), finger RIGHT -> the PREVIOUS tile (visually
     * to the left). NO WRAP stays (edge = nothing).
     */
    private int tabSwipeTarget(float dx) {
        int n = tm.tabs.size();
        if (n < 2) return -1;
        // the target resolves through the DISPLAYED arrangement; ViewPager
        // semantics (lesson 32): finger RIGHT -> the tile BEFORE (visually
        // left), finger LEFT -> the tile AFTER (visually right).
        try {
            List<Tab> order = tabSwipeOrder();
            if (order.isEmpty()) return -1;
            Tab cur = tm.tabs.get(Math.max(0, Math.min(tm.active, n - 1)));
            int pos = order.indexOf(cur);
            if (pos < 0) return -1;
            // v1.3.45: NO WRAP — pos±1 only when that tile exists.
            // v1.3.47: THE DIRECTION IS FLIPPED (lesson 32 above).
            int target = dx > 0 ? pos - 1 : pos + 1;
            if (target < 0 || target >= order.size()) return -1;
            int idx = tm.tabs.indexOf(order.get(target));
            return (idx < 0 || idx == tm.active) ? -1 : idx;
        } catch (Throwable t) {
            int i = dx > 0 ? tm.active - 1 : tm.active + 1;
            return (i < 0 || i >= n) ? -1 : i;
        }
    }

    /** the floating "‹ 3/7 · Title" pill that follows the drag — v1.3.47
     *  (lesson 32): the arrow names the TARGET'S side in the walk — the
     *  finger-RIGHT gesture (the user's "swipe left") targets the tile
     *  BEFORE, so it wears "‹"; finger-LEFT wears "›". At an EDGE (no tile
     *  that way) the pill dims to 55% and shows the CURRENT tab with a
     *  plain "·" instead of hiding — the user sees WHY the release does
     *  nothing. */
    private void showTabSwipePill(float dx) {
        try {
            if (tswipePill == null) {
                tswipePill = new TextView(this);
                tswipePill.setTextSize(14);
                tswipePill.setTypeface(com.bolt.browser.util.Ui.medium());
                tswipePill.setTextColor(Fx.txt(this));
                tswipePill.setMaxLines(1);
                android.graphics.drawable.GradientDrawable g =
                        new android.graphics.drawable.GradientDrawable();
                g.setCornerRadius(Fx.dp(this, 999));
                g.setColor(Fx.surface2(this));
                g.setStroke(Math.max(1, Fx.dp(this, 1)), Fx.outline(this));
                tswipePill.setBackground(g);
                int ph = Fx.dp(this, 14), pv = Fx.dp(this, 8);
                tswipePill.setPadding(ph, pv, ph, pv);
                tswipePill.setMaxWidth(Fx.dp(this, 280));
                tswipePill.setEllipsize(android.text.TextUtils.TruncateAt.END);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                        Gravity.CENTER_HORIZONTAL | (tswipeOnTopBar
                                ? Gravity.TOP : Gravity.BOTTOM));
                lp.topMargin = lp.bottomMargin = Fx.dp(this, 70);
                flRoot.addView(tswipePill, lp);
            } else {
                // reuse: the pill follows whichever bar this swipe started on
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)
                        tswipePill.getLayoutParams();
                lp.gravity = Gravity.CENTER_HORIZONTAL | (tswipeOnTopBar
                        ? Gravity.TOP : Gravity.BOTTOM);
                tswipePill.setLayoutParams(lp);
            }
            int target = tabSwipeTarget(dx);
            if (target < 0) {
                // v1.3.45 EDGE state: dimmed, no arrow, names the CURRENT
                // tab — "this is where you are; nothing lies that way".
                Tab cur = tm.tabs.get(Math.max(0,
                        Math.min(tm.active, tm.tabs.size() - 1)));
                if (cur == null) { tswipePill.setVisibility(View.INVISIBLE); return; }
                String cn = cur.isHome ? getString(R.string.home)
                        : (cur.title == null || cur.title.isEmpty()
                                ? Fx.hostOf(cur.url) : cur.title);
                tswipePill.setText("\u00B7  " + cn);
                tswipePill.setVisibility(View.VISIBLE);
                tswipePill.setAlpha(0.55f * Math.min(1f,
                        Math.abs(dx) / Fx.dp(this, 56)));
                tswipePill.bringToFront();
                return;
            }
            Tab t = tm.tabs.get(target);
            String name = t.isHome ? getString(R.string.home)
                    : (t.title == null || t.title.isEmpty() ? Fx.hostOf(t.url) : t.title);
            // v1.3.44: the pill numbers the target by its place in the walk
            // order (the visible tiles), not the raw list index — matches
            // what the switcher grid shows.
            String num;
            try {
                List<Tab> order = tabSwipeOrder();
                int p = order.indexOf(t);
                num = (p >= 0 ? p + 1 : target + 1) + "/"
                        + (p >= 0 ? order.size() : tm.tabs.size());
            } catch (Throwable er) {
                num = (target + 1) + "/" + tm.tabs.size();
            }
            tswipePill.setText((dx > 0 ? "\u2039 " : "\u203A ") + num + "  \u00B7  " + name);
            tswipePill.setVisibility(View.VISIBLE);
            tswipePill.setAlpha(Math.min(1f, Math.abs(dx) / Fx.dp(this, 56)));
            tswipePill.bringToFront();
        } catch (Throwable ignored) {}
    }

    private void hideTabSwipePill() {
        try {
            if (tswipePill != null) {
                tswipePill.animate().alpha(0f).setDuration(140)
                        .withEndAction(() -> { try {
                            tswipePill.setVisibility(View.INVISIBLE);
                        } catch (Throwable ignored) {} }).start();
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ v1.3.37 long-press quick actions

    /** dispatch a toolbar button's configured long-press action. */
    protected boolean quickPress(String button, View v) {
        try { com.bolt.browser.util.Ui.tap(v); } catch (Throwable ignored) {}
        runQuickAction(prefs.lpAction(button,
                com.bolt.browser.util.QuickActions.defaultFor(button)));
        return true;
    }

    protected void runQuickAction(String code) {
        if (code == null || com.bolt.browser.util.QuickActions.NONE.equals(code)) return;
        Tab t = activeTab();
        switch (code) {
            case com.bolt.browser.util.QuickActions.NEW_TAB: openNewTab(null); break;
            case com.bolt.browser.util.QuickActions.CLOSE_TAB: {
                if (t == null) break;
                int was = tm.active;
                // v1.4.8 — the departure refresh + last-member capture
                // (a toolbar close can empty a group: the folder row keeps
                // its spot; anchored empties around the row re-anchor)
                try {
                    if (t.groupId != 0 && tm.tabsOfGroup(t.groupId).size() == 1)
                        tm.captureGroupRowAnchors(t.groupId);
                    tm.refreshAnchorsAround("t" + t.tid);
                } catch (Throwable ignored) {}
                tm.close(tm.active);
                showTab(Math.max(0, tm.active));
                showUndoSnack(was);
                break;
            }
            case com.bolt.browser.util.QuickActions.TAB_HISTORY: showTabHistory(); break;
            case com.bolt.browser.util.QuickActions.TABS: openTabsView(); break;
            case com.bolt.browser.util.QuickActions.HOME: goHome(); break;
            case com.bolt.browser.util.QuickActions.BOOKMARKS:
                Ui.launchSatellite(this, BookmarksActivity.class); break;
            case com.bolt.browser.util.QuickActions.HISTORY:
                Ui.launchSatellite(this, HistoryActivity.class); break;
            case com.bolt.browser.util.QuickActions.DOWNLOADS:
                Ui.launchSatellite(this, DownloadsActivity.class); break;
            case com.bolt.browser.util.QuickActions.APPEARANCE:
                Ui.launchSatellite(this, AppearanceActivity.class); break;
            case com.bolt.browser.util.QuickActions.SHARE: sharePage(); break;
            case com.bolt.browser.util.QuickActions.FIND: openFind(); break;
            case com.bolt.browser.util.QuickActions.HARD_RELOAD:
                if (t != null && t.web != null) t.web.loadUrl(t.url);
                break;
            case com.bolt.browser.util.QuickActions.QR: startQrScan(); break;
            // v1.3.38 additions (new features → toolbar shortcuts)
            case com.bolt.browser.util.QuickActions.WATCH_LATER: addWatchLater(); break;
            case com.bolt.browser.util.QuickActions.VOICE: startVoiceSearch(1010); break;
            case com.bolt.browser.util.QuickActions.SHARE_QR: sharePageAsQr(); break;
            case com.bolt.browser.util.QuickActions.SLEEP: sleepOthersNow(); break;
            case com.bolt.browser.util.QuickActions.PIP:
                // v1.3.41: video-only PiP — explain instead of a silent no-op
                if (customView == null) Fx.toast(this, "Enter fullscreen video first");
                else enterPipIfEligible();
                break;
            // v1.3.39 additions (round-3 menu features → toolbar shortcuts)
            case com.bolt.browser.util.QuickActions.READER: openReader(); break;
            case com.bolt.browser.util.QuickActions.OUTLINE: showOutline(); break;
            case com.bolt.browser.util.QuickActions.FULLSHOT: takeFullShot(); break;
            case com.bolt.browser.util.QuickActions.FULLSCREEN: toggleFullscreenMode(); break;
            case com.bolt.browser.util.QuickActions.NIGHT:
                com.bolt.browser.util.NightScreen.toggle(this); break;
            case com.bolt.browser.util.QuickActions.TRASH:
                startActivity(new Intent(this, com.bolt.browser.ui.TabTrashActivity.class)); break;
            default: break;
        }
    }

    // ------------------------------------------------------------ v1.3.39 round-3 feature actions

    /** per-site dark resolved for the current domain (override → global). */
    protected boolean siteDarkNow(String base) {
        try {
            return prefs.siteBool(base, "dark", prefs.webDark());
        } catch (Throwable t) {
            return prefs.webDark();
        }
    }

    /** v1.3.94 — apply the smart inversion NOW (the no-designed-dark
     *  fallback, or the declared-but-never-darkened case) plus the late
     *  pull (only ever inversion → none: a page that darkens after all
     *  gets its own colors back — re-inverting mid-read would flash).
     *  v1.4.18: with the ask-first hold retired, THIS is the only commit-
     *  time outcome for a probed-light page (see runWebDarkPass). */
    protected void darkInvertNow(final WebView w, final String host) {
        try {
            w.evaluateJavascript(MediaHook.cssInjectOnce(
                    "bolt-darkcss", MediaHook.darkFallbackCss()), null);
            w.evaluateJavascript(MediaHook.darkSmartJs(), null);
            // v1.3.91 — THE LATE RE-PROBE (2.5 s): SPAs apply their theme
            // AFTER onLoad (YouTube flips html[dark] from the user's stored
            // preference a beat later; many apps set CSS variables on first
            // data arrival). Only ever inversion->none.
            ui.postDelayed(() -> {
                try {
                    // v1.4.18 — THE BACKGROUND-TAB FIX: the old guard
                    // (w != activeTab().web -> return) aborted the late
                    // probe for every BACKGROUND tab, and the finish-time
                    // backstop skips urls the commit pass already spoke
                    // for (the run token) — so a tab loaded in the
                    // background could keep Bolt's inversion even after
                    // its site self-darkened, forever. The guard now
                    // checks the tab is still ALIVE (it was closed ->
                    // give up); fullscreen video only shields the ACTIVE
                    // tab it actually belongs to.
                    if (tabOf(w) == null) return;
                    Tab at = activeTab();
                    if (at != null && w == at.web && customView != null) return;
                    w.evaluateJavascript(MediaHook.darkProbeJs(), res2 -> {
                        try {
                            if ("1".equals(
                                    res2 == null ? "0" : res2.replace("\"", ""))) {
                                w.evaluateJavascript(
                                        MediaHook.darkClearJs(), null);
                                com.bolt.browser.util.Diag.log(getApplicationContext(),
                                        "dark late-flip " + host);
                            }
                        } catch (Throwable ignored) {}
                    });
                } catch (Throwable ignored) {}
            }, 2500);
        } catch (Throwable ignored) {}
    }

    /** v1.4.17 — THE COMMIT-TIME DARK PASS (the dark-web white-flash fix).
     * The dark decision chain (probe -> invert) used to run only at
     * onPageFinished, so a light site under the dark-web engine painted
     * WHITE from first paint until the last subresource landed — on a slow
     * page that is seconds of white between two dark pages, which reads as
     * a flash even though every probe was honest. The chain now runs at
     * onPageCommitVisible — the earliest moment the new document exists
     * with its render-blocking CSS parsed — and this method is the one
     * body both callbacks share. The guard law lives in the callers: the
     * per-tab run token (Tab.darkPassUrl) records that the chain has
     * spoken for THIS url, because an unguarded second run would probe
     * the ALREADY-INVERTED page, read it as painted-dark, and clear
     * Bolt's own inversion — the probe cannot tell site-dark from
     * bolt-dark. Native force-dark stays retired (see Tab.applyWebDark:
     * a no-op at target 34, and the v1.3.91 double-inversion corpse).
     *
     * v1.4.18 — THE ASK-FIRST HOLD IS RETIRED (the "dark engine paints a
     * light site WHITE until it finishes loading" report). The v1.3.94
     * chain ran probe -> ASK (does the page DECLARE dark?) -> hold for
     * its own dark (~3 s via darkAwaitSelfDark, now deleted) -> invert.
     * A page that merely DECLARES dark (a color-scheme meta, any
     * prefers-color-scheme rule, a data-theme attribute — very common)
     * but renders LIGHT under a fresh cookie jar therefore sat WHITE for
     * the whole hold. A declaration is not evidence the page WILL
     * self-darken; the probe's painted-light reading is evidence it has
     * NOT. The commit pass now inverts the moment the probe reads light,
     * and the site that genuinely darkens on its own a beat later is
     * pulled back by the 2.5 s late re-probe inside darkInvertNow
     * (inversion -> none only) — the trade is a brief double-dark on
     * that rare async-dark class, accepted knowingly in exchange for
     * zero white hold on every merely-declaring site. The run-token
     * guard and the finish-time backstop are unchanged. */
    protected void runWebDarkPass(final WebView view, final String url) {
        try {
            final String host = Fx.hostOf(url);
            String base = Fx.baseDomain(host);
            if (!siteDarkNow(base)) return;
            // v1.4.18 — THE COMMIT BREADCRUMB: the field report needed
            // proof the commit hook fires on device at all (a static pin
            // proves the code exists, not that the door opens); every
            // diag report now carries the hook's firing per host.
            com.bolt.browser.util.Diag.log(getApplicationContext(),
                    "dark commit-pass " + host);
            Tab t = tabOf(view);
            if (t != null) t.darkPassUrl = url;
            view.evaluateJavascript(MediaHook.darkProbeJs(), res -> {
                try {
                    boolean nativeDark = "1".equals(
                            res == null ? "0" : res.replace("\"", ""));
                    if (nativeDark) {
                        view.evaluateJavascript(
                                MediaHook.darkClearJs(), null);
                        com.bolt.browser.util.Diag.log(getApplicationContext(),
                                "dark native-site " + host);
                    } else {
                        // v1.4.18 — LIGHT AT COMMIT = INVERT NOW (the
                        // ask-first hold died here; see the method doc)
                        darkInvertNow(view, host);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** toggle this site's dark override + reload (page-menu chip). */
    protected void toggleSiteDark() {
        final String base = curDomain();
        boolean now = !siteDarkNow(base);
        prefs.setSite(base, "dark", now);
        dismissAllOverlays();
        Fx.toast(this, "Dark site " + (now ? "ON" : "OFF") + " · " + base);
        Tab t = activeTab();
        if (t != null && t.web != null && !t.isHome) {
            t.applyWebDark(this, t.web);
            t.web.reload();
        }
    }

    /** v1.3.40 — per-site tracker-cleaning resolution (override → global). */
    protected boolean siteCleanNow(String base) {
        try {
            return prefs.siteBool(base, "clean", prefs.cleanTrackers());
        } catch (Throwable t) {
            return prefs.cleanTrackers();
        }
    }

    /** v1.3.40 — toggle this site's tracker-cleaning override (page-menu
     *  row; the global master switch lives in Privacy). */
    protected void toggleSiteClean() {
        final String base = curDomain();
        boolean now = !siteCleanNow(base);
        prefs.setSite(base, "clean", now);
        dismissAllOverlays();
        Fx.toast(this, "Tracker cleaning " + (now ? "ON" : "OFF") + " · " + base);
    }

    /** v1.3.40 — PER-SITE ZOOM (round-4 #5): slider dialog 60–200% with a
     *  "Default" reset; applies live via Tab.applyZoom (textZoom — no
     *  reload). Pairs with the per-site dark/desktop overrides. */
    protected void siteZoomDialog() {
        final String base = curDomain();
        if (base == null || base.isEmpty()) {
            Fx.toast(this, "Open a page first");
            return;
        }
        final int cur = prefs.siteInt(base, "zoom", -1);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 22);
        wrap.setPadding(p, p / 2, p, 0);
        final TextView val = new TextView(this);
        val.setTextColor(Fx.txt(this));
        val.setTextSize(18);
        val.setGravity(android.view.Gravity.CENTER);
        final android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(140);   // 60..200%
        sb.setProgress((cur >= 60 ? Math.min(cur, 200) : prefs.fontSize()) - 60);
        val.setText((sb.getProgress() + 60) + "%");
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int v, boolean u) {
                val.setText((v + 60) + "%");
            }
            public void onStartTrackingTouch(android.widget.SeekBar s) {}
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        wrap.addView(val);
        wrap.addView(sb);
        new AlertDialog.Builder(this)
                .setTitle("Text size for " + base)
                .setView(wrap)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    int z = sb.getProgress() + 60;
                    prefs.setSite(base, "zoom", z);
                    Tab t = activeTab();
                    if (t != null && t.web != null) t.applyZoom(BrowserActivity.this, t.web);
                    Fx.toast(BrowserActivity.this, "Zoom " + z + "% · " + base);
                })
                .setNeutralButton("Default", (d, w) -> {
                    prefs.setSite(base, "zoom", -1);
                    Tab t = activeTab();
                    if (t != null && t.web != null) t.applyZoom(BrowserActivity.this, t.web);
                    Fx.toast(BrowserActivity.this, "Zoom follows font size · " + base);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** READER VIEW (menu-3 #1): extract the article in-page, open the
     *  reader screen. Text-reflow zoom lives there (menu-3 #14). */
    protected void openReader() {
        final Tab t = activeTab();
        if (t == null || t.isHome || t.web == null) {
            Fx.toast(this, "Open an article first");
            return;
        }
        final String url = t.url;
        try { t.capture(); } catch (Throwable ignored) {}
        t.web.evaluateJavascript(com.bolt.browser.util.ReaderMode.EXTRACT_JS, value -> {
            String json = null;
            if (value != null && !"null".equals(value) && value.length() > 2) {
                json = value;
            }
            if (json == null) {
                Fx.toast(this, "No article found on this page");
                return;
            }
            try {
                Intent i = new Intent(this, com.bolt.browser.ui.ReaderActivity.class);
                i.putExtra("url", url);
                i.putExtra("json", json);
                startActivity(i);
            } catch (Throwable ignored2) {}
        });
    }

    /** PAGE OUTLINE (menu-3 #12): headings h1–h3 → jump sheet. */
    protected void showOutline() {
        final Tab t = activeTab();
        if (t == null || t.isHome || t.web == null) {
            Fx.toast(this, "Open a page first");
            return;
        }
        dismissAllOverlays();
        t.web.evaluateJavascript(com.bolt.browser.util.Outline.JS, value -> {
            List<String[]> heads = com.bolt.browser.util.Outline.parse(value);
            if (heads.size() < 3) {
                Fx.toast(this, "This page has no outline");
                return;
            }
            List<MenuRow> rows = new ArrayList<>();
            for (int i = 0; i < heads.size() && i < 60; i++) {
                final int idx = i;
                String[] h = heads.get(i);
                int level = Integer.parseInt(h[0]);
                MenuRow r = new MenuRow();
                r.icon = level == 1 ? R.drawable.ic_outline
                        : (level == 2 ? R.drawable.ic_chevron : R.drawable.ic_arrow_down);
                r.title = com.bolt.browser.util.Outline.pad(level) + h[1];
                r.hint = "Heading level " + level + " — tap to scroll the page here.";
                r.click = v -> {
                    dismissAllOverlays();
                    if (t.web != null)
                        t.web.evaluateJavascript(com.bolt.browser.util.Outline.jumpJs(idx), null);
                };
                rows.add(r);
            }
            showSheetMenu("Outline · " + heads.size() + " headings", rows);
        });
    }

    /** FULL-PAGE SCREENSHOT (menu-3 #3): stitch-scroll the whole page. */
    protected void takeFullShot() {
        final Tab t = activeTab();
        if (t == null || t.isHome || t.web == null) {
            Fx.toast(this, "Open a page first");
            return;
        }
        dismissAllOverlays();
        Fx.toast(this, "Capturing full page…");
        boolean barsWere = barsShown;
        hideBarsNow(false);
        com.bolt.browser.util.FullShot.barsVisible(this, false);
        // v1.3.96 — the same height law as Save-as-PDF: measure the page's
        // OWN height in the DOM (contentHeight is viewport-clamped on
        // inner-scroller SPAs — the capture would tile one screen).
        final WebView fw = t.web;
        fw.evaluateJavascript(com.bolt.browser.util.AiExport.heightJs(), hres -> {
            int parsed = 0;
            try {
                String s = hres == null ? "0" : hres;
                Object v = new org.json.JSONTokener(s).nextValue();
                if (v instanceof String) s = (String) v;
                parsed = Integer.parseInt(s.trim());
            } catch (Throwable ignored) {}
            final int jsH = parsed;   // final for the capture below
            com.bolt.browser.util.FullShot.capture(this, fw, "Bolt-fullpage-", jsH, res -> {
                com.bolt.browser.util.FullShot.barsVisible(this, true);
                if (barsWere) showBarsNow(false);
                if (res.ok) {
                    Fx.toast(this, "Saved to Downloads — long enough pages may show repeated sticky headers");
                    com.bolt.browser.util.FullShot.share(this, res.uri);
                } else {
                    Fx.toast(this, res.msg);
                }
            });
        });
    }

    /** open a URL in the CURRENT tab (link preview's "Open"). */
    public void openUrlCurrentTab(String url) {
        try { loadInTab(url); } catch (Throwable ignored) {}
    }

    /** v1.3.87 — THE AI RESPONSE EXPORTER entry. v1.3.91 — MANUAL MODE.
     *  v1.3.92 — THE NATIVE PICKER (the field: "I am in zai chat web and
     *  don't see any options to export or select a message to save as pdf
     *  or image"): the page-injected chrome (absolutely-positioned circles
     *  + a position:fixed bar + a <style> element) died exactly the way a
     *  React chat SPA kills it — a transformed app root breaks fixed
     *  positioning, inner scrollers desync absolute circles, strict CSP
     *  kills the sheet. The chrome is now ANDROID views over the WebView
     *  (circles + bottom bar + hint, children of flContent = the exact
     *  web frame); the page JS only marks candidates, keeps selection
     *  state, reports rects and hit-tests manual taps. No site CSS can
     *  hide the picker. */
    protected void startAiExport() {
        Tab t = activeTab();
        if (t == null || t.isHome || t.web == null) {
            Fx.toast(this, "Open a page first");
            return;
        }
        dismissAllOverlays();
        try {
            final WebView w = t.web;
            w.evaluateJavascript(com.bolt.browser.util.AiExport.pickerJs(), res -> {
                try {
                    // v1.3.96 — THE QUIET CORPSE (the field: "Ai response
                    // saver does nothing when pressed"). evaluateJavascript
                    // hands the JS return back as a JSON-QUOTED string
                    // ("{\"n\":3,\"manual\":0}") — new JSONObject on that
                    // throws ("A JSONObject text must begin with '{'") and
                    // the old catch SWALLOWED it, so the picker never
                    // started, on ANY site, since the v1.3.92 native chrome
                    // (the three "I don't see a way to save the ai response"
                    // reports were THIS bug, not discoverability). Unquote
                    // first — the same JSONTokener double-parse aiSyncOnce
                    // always used — and NAME any failure instead of dying
                    // silently.
                    String s = res == null ? "{}" : res;
                    Object v = new org.json.JSONTokener(s).nextValue();
                    if (v instanceof String) s = (String) v;
                    org.json.JSONObject o = new org.json.JSONObject(s);
                    if (o.has("err")) {
                        if ("busy".equals(o.optString("err"))) {
                            endAiPick(true);
                            Fx.toast(BrowserActivity.this, "Picker reset — try Save AI response again");
                        } else {
                            Fx.toast(BrowserActivity.this, "Could not start the response picker");
                        }
                        return;
                    }
                    int n = o.optInt("n", 0);
                    boolean manual = o.optInt("manual", 0) == 1;
                    aiPickStart(w, n, manual);
                    if (manual) {
                        Fx.toast(BrowserActivity.this,
                                "No auto-found answers — tap the blocks you want, then Save");
                    } else {
                        Fx.toast(BrowserActivity.this, n + (n == 1 ? " response" : " responses")
                                + " found — pick, then save");
                    }
                } catch (Throwable t2) {
                    // v1.3.96 — never a silent corpse: a picker that cannot
                    // start must SAY so (the unquoted-JSON bug died here
                    // invisibly for three versions)
                    Fx.toast(BrowserActivity.this, "Could not start the response picker");
                }
            });
        } catch (Throwable ignored) {
            Fx.toast(this, "Could not start the response picker");
        }
    }

    // ---------------- v1.3.92 the NATIVE AI-export picker chrome ----------------

    /** overlay layer (flContent child = exactly the web frame), the bottom
     *  action bar, the count label, the manual-mode hint, and the pinned
     *  WebView. Circles are (re)built on every sync — the PAGE owns all
     *  selection state, Java only renders it. */
    protected android.widget.FrameLayout aiPickLayer;
    protected LinearLayout aiBar;
    protected TextView aiCount;
    protected TextView aiHint;
    protected WebView aiWeb;
    protected boolean aiManual;

    /** the 700 ms poll: rect refresh + the teardown guards (tab switch,
     *  layer gone). The page's scroll listener calls Bolt.aiSync() for the
     *  fast path; this catches everything else (virtualized lists that
     *  move rects without scroll events, missed callbacks). */
    protected final Runnable aiPoll = new Runnable() {
        @Override public void run() {
            try {
                if (aiPickLayer == null || aiWeb == null) return;   // stopped — do not re-post
                Tab t = activeTab();
                if (t == null || t.web != aiWeb) { endAiPick(true); return; }
                aiSyncOnce();
            } catch (Throwable ignored) {}
            try { ui.postDelayed(this, 700); } catch (Throwable ignored) {}
        }
    };

    /** build the native chrome and take the first sync. */
    protected void aiPickStart(WebView w, int n, boolean manual) {
        try {
            endAiPick(true);   // any previous picker (page side + native)
            aiWeb = w;
            aiManual = manual;
            android.widget.FrameLayout layer = new android.widget.FrameLayout(this);
            aiPickLayer = layer;
            // NOT clickable: touches between the circles must fall through
            // to the page (scrolling the chat while picking is the whole
            // point of a live picker) — a non-consuming overlay lets the
            // WebView underneath keep the gesture.
            layer.setClickable(false);
            flContent.addView(layer, new FrameLayout.LayoutParams(-1, -1));

            // ---- the bottom action bar ----
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(Fx.dp(this, 18));
            bg.setColor(0xF21B1B2E);
            bar.setBackground(bg);
            bar.setElevation(Fx.dp(this, 8));
            int bp = Fx.dp(this, 6);
            bar.setPadding(bp, bp, bp, bp);
            aiBar = bar;
            aiCount = new TextView(this);
            aiCount.setTextColor(0xFFFFFFFF);
            aiCount.setTextSize(13);
            aiCount.setTypeface(com.bolt.browser.util.Ui.medium());
            aiCount.setText(manual ? "0 selected" : ("0 of " + n + " selected"));
            bar.addView(aiCount, new LinearLayout.LayoutParams(-2, -2));
            if (!manual) {
                bar.addView(aiChip("All", 0xFF7C4DFF, v -> {
                    try { aiWeb.evaluateJavascript(
                            com.bolt.browser.util.AiExport.setAllJs(true), null); } catch (Throwable ignored) {}
                    aiSyncOnce();
                }));
                bar.addView(aiChip("None", 0x33FFFFFF, v -> {
                    try { aiWeb.evaluateJavascript(
                            com.bolt.browser.util.AiExport.setAllJs(false), null); } catch (Throwable ignored) {}
                    aiSyncOnce();
                }));
            }
            bar.addView(aiChip("Save PDF", 0xFF7C4DFF, v -> aiSaveVector()));
            bar.addView(aiChip("Save image", 0xFF2E7D32, v -> aiSaveImage()));
            bar.addView(aiChip("\u00d7", 0x33FFFFFF, v -> endAiPick(true)));
            android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                    -2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            blp.bottomMargin = Fx.dp(this, 18);
            blp.leftMargin = Fx.dp(this, 12);
            blp.rightMargin = Fx.dp(this, 12);
            layer.addView(bar, blp);

            // ---- the manual-mode hint (top) ----
            if (manual) {
                TextView hint = new TextView(this);
                hint.setTextColor(0xFFFFFFFF);
                hint.setTextSize(13);
                hint.setTypeface(com.bolt.browser.util.Ui.medium());
                hint.setText("Tap the blocks you want to export");
                android.graphics.drawable.GradientDrawable hb =
                        new android.graphics.drawable.GradientDrawable();
                hb.setCornerRadius(Fx.dp(this, 14));
                hb.setColor(0xF21B1B2E);
                hint.setBackground(hb);
                int hp = Fx.dp(this, 12);
                hint.setPadding(hp, Fx.dp(this, 7), hp, Fx.dp(this, 7));
                aiHint = hint;
                android.widget.FrameLayout.LayoutParams hlp = new android.widget.FrameLayout.LayoutParams(
                        -2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
                hlp.topMargin = Fx.dp(this, 14);
                layer.addView(hint, hlp);
            }
            ui.removeCallbacks(aiPoll);
            ui.postDelayed(aiPoll, 350);
            aiSyncOnce();
        } catch (Throwable ignored) {
            endAiPick(true);
        }
    }

    /** one pill button for the action bar. */
    protected TextView aiChip(String label, int bgColor,
            android.view.View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13);
        t.setTypeface(com.bolt.browser.util.Ui.medium());
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(Fx.dp(this, 12));
        g.setColor(bgColor);
        t.setBackground(g);
        int p = Fx.dp(this, 8);
        t.setPadding(p, Fx.dp(this, 7), p, Fx.dp(this, 7));
        t.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Fx.dp(this, 5);
        t.setLayoutParams(lp);
        return t;
    }

    /** pull the rect/state payload from the page and re-render circles. */
    protected void aiSyncOnce() {
        WebView w = aiWeb;
        if (w == null || aiPickLayer == null || flContent == null) return;
        try {
            w.evaluateJavascript(com.bolt.browser.util.AiExport.rectsJs(), res -> {
                try {
                    if (aiPickLayer == null) return;
                    String s = res == null ? "{}" : res;
                    Object v = new org.json.JSONTokener(s).nextValue();
                    if (v instanceof String) v = new org.json.JSONTokener((String) v).nextValue();
                    if (!(v instanceof org.json.JSONObject)) return;
                    org.json.JSONObject o = (org.json.JSONObject) v;
                    org.json.JSONArray r = o.optJSONArray("r");
                    int sel = o.optInt("sel", 0);
                    int n = o.optInt("n", 0);
                    if (aiCount != null) {
                        aiCount.setText(aiManual ? (sel + " selected")
                                : (sel + " of " + n + " selected"));
                    }
                    renderAiCircles(w, r);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** rebuild the numbered circles from the payload (cheap; syncs are
     *  scroll/user-driven, not per frame). */
    protected void renderAiCircles(WebView w, org.json.JSONArray r) {
        android.widget.FrameLayout layer = aiPickLayer;
        if (layer == null) return;
        // clear the previous circles (children 2.. — bar and hint stay)
        for (int i = layer.getChildCount() - 1; i >= 0; i--) {
            View c = layer.getChildAt(i);
            if (c != aiBar && c != aiHint) layer.removeView(c);
        }
        if (r == null) return;
        int[] wl = new int[2], ll = new int[2];
        try { w.getLocationInWindow(wl); layer.getLocationInWindow(ll); } catch (Throwable t) { return; }
        int vw = w.getWidth(), vh = w.getHeight();
        int d = Fx.dp(this, 30);
        for (int i = 0; i < r.length(); i++) {
            try {
                org.json.JSONObject e = r.optJSONObject(i);
                if (e == null) continue;
                float l = (float) e.optDouble("l", 0), t = (float) e.optDouble("t", 0);
                float ww = (float) e.optDouble("w", 0), hh = (float) e.optDouble("h", 0);
                // only circles over the visible viewport
                if (l + ww <= 0 || l >= vw || t + hh <= 0 || t >= vh) continue;
                boolean selc = e.optInt("s", 0) == 1;
                final String id = e.optString("id", "");
                final boolean isMan = "m".equals(e.optString("k", "c"));
                TextView c = new TextView(this);
                c.setText(isMan ? "\u2713" : String.valueOf(Integer.parseInt(id) + 1));
                c.setTextColor(0xFFFFFFFF);
                c.setTextSize(13);
                c.setTypeface(com.bolt.browser.util.Ui.medium());
                c.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable g =
                        new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                g.setColor(selc ? 0xFF7C4DFF : 0xEE1B1B2E);
                g.setStroke(Fx.dp(this, 2), selc ? 0xFFFFFFFF : 0xFF7C4DFF);
                c.setBackground(g);
                c.setElevation(Fx.dp(this, 6));
                c.setOnClickListener(v -> {
                    try {
                        if (aiWeb == null) return;
                        aiWeb.evaluateJavascript(isMan
                                ? com.bolt.browser.util.AiExport.toggleMJs(id)
                                : com.bolt.browser.util.AiExport.toggleJs(
                                        Integer.parseInt(id)), null);
                    } catch (Throwable ignored) {}
                    aiSyncOnce();
                });
                android.widget.FrameLayout.LayoutParams lp =
                        new android.widget.FrameLayout.LayoutParams(d, d);
                lp.leftMargin = Math.round(wl[0] - ll[0] + l + Fx.dp(this, 5));
                lp.topMargin = Math.round(wl[1] - ll[1] + t + Fx.dp(this, 5));
                layer.addView(c, lp);
            } catch (Throwable ignored) {}
        }
    }

    /** Save PDF — THE VECTOR PATH: the selection is extracted as
     *  structured blocks and rendered by VectorPdf with real selectable
     *  text (the v1.3.91 report: "saving a pdf means I want to save as a
     *  vector file, not just a screenshot wrapped in pdf file"). */
    protected void aiSaveVector() {
        final WebView w = aiWeb;
        if (w == null) return;
        Fx.toast(this, "Building the vector PDF…");
        try {
            w.evaluateJavascript(com.bolt.browser.util.AiExport.blocksJs(), res -> {
                try {
                    String s = res == null ? "" : res;
                    Object v = new org.json.JSONTokener(s).nextValue();
                    if (v instanceof String) s = (String) v; else s = "";
                    java.util.ArrayList<com.bolt.browser.util.VectorPdf.Blk> blocks =
                            com.bolt.browser.util.VectorPdf.parseBlocks(s);
                    if (blocks.isEmpty()) {
                        Fx.toast(BrowserActivity.this, "Nothing selected yet");
                        return;
                    }
                    String host = Fx.hostOf(w.getUrl() == null ? "" : w.getUrl());
                    com.bolt.browser.util.Diag.log(getApplicationContext(),
                            "ai export vector blocks=" + blocks.size());
                    endAiPick(true);
                    com.bolt.browser.util.VectorPdf.save(BrowserActivity.this, blocks,
                            "Bolt-AI-export-", host, (ok, uri, msg) -> {
                                Fx.toast(BrowserActivity.this, msg);
                                if (ok && uri != null) {
                                    com.bolt.browser.util.AiExport.share(
                                            BrowserActivity.this, uri, true);
                                }
                            });
                } catch (Throwable t) {
                    Fx.toast(BrowserActivity.this, "Render failed");
                }
            });
        } catch (Throwable t) {
            Fx.toast(this, "Render failed");
        }
    }

    /** Save image — the pixel-perfect path: the page preps export mode
     *  (inline-styled hide/open surgery) and hands off through the
     *  Bolt.aiCapture bridge (bars hide, FullShot stitches, restore). */
    protected void aiSaveImage() {
        final WebView w = aiWeb;
        if (w == null) return;
        try {
            android.widget.FrameLayout layer = aiPickLayer;
            if (layer != null) layer.setVisibility(View.INVISIBLE);
            w.evaluateJavascript(com.bolt.browser.util.AiExport.prepJs("image"), res -> {
                try {
                    Object v = res == null ? "0"
                            : new org.json.JSONTokener(res).nextValue();
                    int n = v instanceof Number ? ((Number) v).intValue() : 0;
                    if (n <= 0) {
                        if (aiPickLayer != null) aiPickLayer.setVisibility(View.VISIBLE);
                        Fx.toast(BrowserActivity.this, "Nothing selected yet");
                    }
                    // n > 0: the bridge flow owns the rest (capture ->
                    // restore -> endAiPick happens in aiCapture)
                } catch (Throwable t) {
                    if (aiPickLayer != null) aiPickLayer.setVisibility(View.VISIBLE);
                }
            });
        } catch (Throwable t) {
            if (aiPickLayer != null) aiPickLayer.setVisibility(View.VISIBLE);
        }
    }

    /** end the picker: teardown the page side (markers, outlines, surgery,
     *  listeners) and remove the native chrome. Safe to call any time. */
    protected void endAiPick(boolean pageTeardown) {
        try { ui.removeCallbacks(aiPoll); } catch (Throwable ignored) {}
        WebView w = aiWeb;
        if (pageTeardown && w != null) {
            try {
                w.evaluateJavascript(com.bolt.browser.util.AiExport.restoreJs(), null);
            } catch (Throwable ignored) {}
        }
        try {
            if (aiPickLayer != null && aiPickLayer.getParent() == flContent) {
                flContent.removeView(aiPickLayer);
            }
        } catch (Throwable ignored) {}
        aiPickLayer = null;
        aiBar = null;
        aiCount = null;
        aiHint = null;
        aiWeb = null;
    }

    /** v1.3.87 — end export mode on a tab (after capture / on failure).
     *  v1.3.92: also ends the native picker if one is up on that tab. */
    protected void restoreAiPage(Tab t) {
        try {
            if (t != null && t.web != null)
                t.web.evaluateJavascript(com.bolt.browser.util.AiExport.restoreJs(), null);
        } catch (Throwable ignored) {}
    }

    /** v1.3.91 — THE SAVE-PAGE SHEET: one entry, four outputs (PDF /
     *  Print / HTML / offline MHT). Each option toasts its honest
     *  result; the PDF + HTML land in Downloads and offer the share
     *  sheet right away. */
    protected void showSavePageSheet() {
        final Tab t = activeTab();
        if (t == null || t.isHome || t.web == null) {
            Fx.toast(this, "Open a page first");
            return;
        }
        final String title = t.web.getTitle() == null ? Fx.hostOf(t.url) : t.web.getTitle();
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.save_pdf));
        opts.add(getString(R.string.print_pdf));
        opts.add(getString(R.string.save_html));
        opts.add(getString(R.string.save_page_offline));
        // v1.3.95 — the AI-response saver rides IN THE SAVE SHEET too: three
        // field reports said "I don't see a way to save the zai or any ai
        // response as pdf or image" — the feature lived only as a menu
        // card. This is where a user who wants to SAVE something looks.
        opts.add(getString(R.string.save_ai_response));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.save_page))
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        // v1.3.92 — THE VECTOR PATH: the system print preview
                        // renders Chromium's own vector output; "Save as PDF"
                        // there produces a real text PDF (the field: "saving
                        // a pdf means I want to save as a vector file, not
                        // just a screenshot wrapped in pdf file").
                        // v1.3.95 — THE SILENT STITCH IS DEAD: when the print
                        // screen cannot open on this device (the v1.3.94
                        // field silently fell back to the raster page image),
                        // the user now CHOOSES the fallback — a TEXT vector
                        // PDF (selectable, reader-formatted) or the honest
                        // image PDF — and diagnostics name the print failure.
                        boolean printed = com.bolt.browser.util.PageSave.savePdfVector(
                                BrowserActivity.this, t.web, title);
                        if (printed) {
                            Fx.toast(BrowserActivity.this,
                                    "Choose Save as PDF in the preview — a true vector file");
                        } else {
                            new AlertDialog.Builder(BrowserActivity.this)
                                    .setTitle("Print screen unavailable")
                                    .setMessage("The system print screen (which renders the " +
                                            "true vector PDF) did not open on this device. " +
                                            "Choose a fallback:\n\n\u2022 Text PDF — real selectable text, " +
                                            "reader formatting\n\u2022 Image PDF — the page exactly " +
                                            "as it looks (text not selectable)")
                                    .setPositiveButton("Text PDF", (d2, x) ->
                                            com.bolt.browser.util.PageSave.saveTextPdf(
                                                    BrowserActivity.this, t.web, title, res -> {
                                                        Fx.toast(BrowserActivity.this, res.msg);
                                                        if (res.ok && res.uri != null)
                                                            shareSavedFile(res.uri, "application/pdf");
                                                    }))
                                    .setNeutralButton("Image PDF", (d2, x) ->
                                            com.bolt.browser.util.PageSave.savePdf(
                                                    BrowserActivity.this, t.web, title, res -> {
                                                        Fx.toast(BrowserActivity.this, res.msg);
                                                        if (res.ok && res.uri != null)
                                                            shareSavedFile(res.uri, "application/pdf");
                                                    }))
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                        }
                    } else if (w == 1) {
                        com.bolt.browser.util.PageSave.print(BrowserActivity.this, t.web, title);
                    } else if (w == 2) {
                        Fx.toast(BrowserActivity.this, "Building the HTML archive…");
                        com.bolt.browser.util.PageSave.saveHtml(
                                BrowserActivity.this, t.web, title, res -> {
                                    Fx.toast(BrowserActivity.this, res.msg);
                                    if (res.ok && res.uri != null) shareSavedFile(res.uri, "text/html");
                                });
                    } else if (w == 4) {
                        // v1.3.95 — the response picker straight from the
                        // save sheet (the discoverability pass)
                        startAiExport();
                    } else {
                        saveOffline();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** the share sheet for a freshly saved file (Bolt's own file provider
     *  uri from MediaStore / file://). */
    protected void shareSavedFile(android.net.Uri uri, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.share)));
        } catch (Throwable ignored) {}
    }

    /** open a URL in a NEW background tab (link preview uses this). */
    public void openUrlNewTab(String url) {
        try {
            Tab t = new Tab();
            t.isHome = false;
            t.url = url;
            tm.addTab(t);
            showTab(tm.tabs.size() - 1);
        } catch (Throwable ignored) {}
    }

    /** hold BACK/FORWARD → the tab's own back/forward stack (the
     *  Chrome/Firefox pattern), as a modern sheet; rows jump straight to
     *  that entry via goBackOrForward. */
    protected void showTabHistory() {
        try {
            Tab t = activeTab();
            if (t == null || t.web == null) {
                com.bolt.browser.util.Ui.snackbar(this,
                        getString(R.string.qa_tab_history) + " — " + getString(R.string.home),
                        null, null);
                return;
            }
            WebBackForwardList hl = t.web.copyBackForwardList();
            List<MenuRow> rows = new ArrayList<>();
            int cur = hl.getCurrentIndex();
            for (int i = hl.getSize() - 1; i >= 0; i--) {
                final int step = i - cur;
                String title = hl.getItemAtIndex(i).getTitle();
                String url = hl.getItemAtIndex(i).getUrl();
                if ((title == null || title.isEmpty()) && url != null) title = Fx.hostOf(url);
                if (title == null || title.isEmpty()) title = url;
                MenuRow r = row(step == 0 ? R.drawable.ic_check : R.drawable.ic_history,
                        title, v -> {
                            dismissAllOverlays();
                            if (t.web != null)
                                com.bolt.browser.util.HistoryNav.step(
                                        BrowserActivity.this, t.web, step);
                        });
                r.sub = url;
                if (step == 0) r.value = getString(R.string.current_tab_entry);
                rows.add(r);
            }
            if (rows.isEmpty()) {
                com.bolt.browser.util.Ui.snackbar(this,
                        getString(R.string.qa_tab_history) + " — " + Fx.hostOf(t.url),
                        null, null);
                return;
            }
            showSheetMenu(getString(R.string.qa_tab_history), rows);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ v1.3.38 features

    /** menu / quick-action entry: fire the system speech recognizer. From
     *  the omnibox (1010) the transcript fills the field; from the home
     *  pill (1011) it searches straight away. */
    protected void startVoiceSearch(int req) {
        com.bolt.browser.util.VoiceSearch.launch(this, req);
    }

    /** SHARE PAGE AS QR — the current URL rendered as a scannable QR with
     *  share / save / copy actions (menu item 3). */
    protected void sharePageAsQr() {
        Tab t = activeTab();
        if (t == null || t.url == null || t.url.isEmpty() || t.isHome) {
            Fx.toast(this, "Open a page first");
            return;
        }
        com.bolt.browser.util.ShareQr.show(this, t.url, t.title);
    }

    /** WATCH LATER — queue the current page (unread, floated to the top). */
    protected void addWatchLater() {
        Tab t = activeTab();
        if (t == null || t.isHome || t.url == null || t.url.isEmpty()) {
            Fx.toast(this, "Open a page first");
            return;
        }
        if (com.bolt.browser.util.WatchLater.add(this, t.url, t.title)) {
            com.bolt.browser.util.Ui.snackbar(this, "Saved for later", "View",
                    () -> startActivity(new Intent(this,
                            com.bolt.browser.ui.WatchLaterActivity.class)));
        }
    }

    /** TAB SLEEP / FOCUS MODE — freeze every background tab (renderer RAM
     *  back to the system; urls + positions + thumbnails survive). */
    protected void sleepOthersNow() {
        int n = com.bolt.browser.util.TabSleep.sleepOthers(this, tm, tm.active);
        Fx.toast(this, n > 0 ? "Slept " + n + " background tab" + (n == 1 ? "" : "s")
                : "Nothing to sleep");
    }

    /** the browser-side half of SelectionActions (menu item 10). */
    protected com.bolt.browser.util.SelectionActions.Host selActionsHost() {
        if (selHost == null) selHost = new SelActionsHost();
        return selHost;
    }

    protected class SelActionsHost implements com.bolt.browser.util.SelectionActions.Host {
        @Override public void onSearchSelection(String text) {
            loadInTab(text);   // the omnibox flow: URL-vs-query + engine
        }
        @Override public void onTranslateSelection(String text) {
            // v1.3.71 — the floating translation box: translation + meaning
            // (dictionary senses) + Copy, IN PLACE in this tab (was: a
            // translate.google.com tab that broke continuity). Uses the
            // last page-translation target if any, else the device language.
            try {
                Tab t = activeTab();
                if (t == null || t.web == null) return;
                com.bolt.browser.util.PageTranslate.select(t.web, text);
            } catch (Throwable ignored) {}
        }
        @Override public void onBeamSelection(String text) {
            com.bolt.browser.util.ShareQr.show(BrowserActivity.this, text, null);
        }
    }

    // ------------------------------------------------------------ v1.3.38 password vault

    /** a form submit with a password field reached the bridge — offer to
     *  save (main process only; incognito NEVER stores identity data).
     *  v1.4.4 — the already-saved guard is EXACT (host, user, pass): the
     *  old find(host)-first-row comparison re-offered a stored account on
     *  every sign-in and hid the multi-account story; the sheet title now
     *  NAMES the account ("user @ host") so saving a second login reads as
     *  what it is. */
    protected void maybeOfferSavePw(final String host, final String user, final String pass) {
        try {
            if (incognito || host == null || host.isEmpty()
                    || pass == null || pass.isEmpty()) return;
            if (!prefs.b("pwSave", true)) return;
            String base = Fx.baseDomain(host);
            if (prefs.siteBool(base, "noPwSave", false)) return;
            final String user0 = user == null ? "" : user;
            if (com.bolt.browser.util.PasswordVault.savedAlready(this, host, user0, pass))
                return;   // this exact login is already current
            String who = user0.isEmpty() ? host : user0 + " @ " + host;
            com.bolt.browser.util.Ui.choiceSheet(this,
                    getString(R.string.save_password) + " · " + who,
                    new String[]{ getString(R.string.save), getString(R.string.not_now),
                            getString(R.string.never_for_this_site) }, -1, idx -> {
                        if (idx == 0) {
                            // v1.3.95 — the save runs OFF THE MAIN THREAD
                            // (keystore decrypts + fsync + the 150 ms
                            // backoffs belong there) and the toast NAMES
                            // the stage: the umbrella "vault write failed"
                            // hid three different refusals, and the
                            // unreadable-corpse case — the one with a USER
                            // recovery path (the vault screen's reset) —
                            // looked identical to a keystore failure.
                            final String host0 = host, pass0 = pass, user1 = user0;
                            new Thread(() -> {
                                final int code = com.bolt.browser.util.PasswordVault.saveEx(
                                        BrowserActivity.this, host0, user1, pass0);
                                ui.post(() -> {
                                    if (code == com.bolt.browser.util.PasswordVault.SAVE_OK) {
                                        Fx.toast(BrowserActivity.this, "Login saved");
                                    } else if (code == com.bolt.browser.util.PasswordVault.SAVE_UNREADABLE) {
                                        Fx.toast(BrowserActivity.this,
                                                "Could not save — the vault file is unreadable on this device. "
                                                        + "Menu → Saved passwords → Reset vault, then save again");
                                    } else if (code == com.bolt.browser.util.PasswordVault.SAVE_VERIFY) {
                                        Fx.toast(BrowserActivity.this,
                                                "Could not save — vault round-trip check failed (see diagnostics)");
                                    } else {
                                        Fx.toast(BrowserActivity.this,
                                                "Could not save — vault write failed (see diagnostics)");
                                    }
                                });
                            }, "bolt-pwsave").start();
                        } else if (idx == 2) {
                            prefs.setSite(base, "noPwSave", true);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    /** v1.3.78 — THE KEYBOARD-AWARE CHIP ANCHOR (kept as the FALLBACK).
     *  The fill chips used to sit at a fixed 96dp above the bottom bar —
     *  INVISIBLE under the open keyboard. The margin rides the live
     *  visible-frame bottom (keyboard top + 14dp, never less than the old
     *  96dp). v1.3.79: no longer the primary anchor — see
     *  [anchorChipToField] (the field report: "the bolt pop up to fill in
     *  the info does not appear" — a keyboard-anchored chip is invisible
     *  whenever the IME is closed, and appears far from the field until it
     *  opens; the chip now sits at the FIELD, below or above by screenspace). */
    protected void anchorChipAboveKeyboard(TextView chip) {
        try {
            if (chip == null || chip.getLayoutParams() == null) return;
            placeChip(chip, -1, -1);
        } catch (Throwable ignored) {}
    }

    /** v1.3.79 — THE FIELD ANCHOR ("Make it so that the login prompt always
     *  pops up just below or above (depending on available screenspace) the
     *  typing space"). Asks the page where the FOCUSED field is (the media
     *  hook exposes __boltFieldRect — pw/otp/form aware) and places the chip
     *  directly under it, or over it when the keyboard leaves no room below.
     *  Falls back to the keyboard anchor when the page will not answer. */
    protected void anchorChipToField(TextView chip) {
        try {
            final TextView c = chip;
            Tab t = activeTab();
            if (t == null || t.web == null) { anchorChipAboveKeyboard(chip); return; }
            final android.webkit.WebView w = t.web;
            w.evaluateJavascript("(window.__boltFieldRect&&window.__boltFieldRect())||null", v -> {
                try {
                    int at = -1, ab = -1;
                    if (v != null && !v.equals("null") && v.startsWith("{")) {
                        org.json.JSONObject o = (org.json.JSONObject)
                                new org.json.JSONTokener(v).nextValue();
                        // CSS px -> device px, then page coords -> window coords
                        float density = getResources().getDisplayMetrics().density;
                        int[] loc = new int[2];
                        w.getLocationInWindow(loc);
                        at = loc[1] + (int) (o.optDouble("t") * density);
                        ab = loc[1] + (int) (o.optDouble("b") * density);
                    }
                    final int fat = at, fab = ab;
                    ui.post(() -> {
                        try { if (c.getParent() != null) placeChip(c, fat, fab); }
                        catch (Throwable ignored) {}
                    });
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {
            anchorChipAboveKeyboard(chip);
        }
    }

    /** v1.3.79 — the one positioning primitive for every fill chip:
     *  [anchorBottomScreen] >= 0 = the focused field's window coords (chip
     *  goes below it, flipping above when the IME leaves no room); -1 = the
     *  v1.3.78 keyboard fallback. Top-gravity + margin, so re-anchoring as
     *  the keyboard opens/closes never fights an old bottomMargin. */
    protected void placeChip(TextView chip, int anchorTopScreen, int anchorBottomScreen) {
        try {
            if (chip == null || chip.getLayoutParams() == null) return;
            chip.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int ch = Math.max(chip.getMeasuredHeight(), Fx.dp(this, 36));
            android.graphics.Rect vf = new android.graphics.Rect();
            flRoot.getWindowVisibleDisplayFrame(vf);
            int top;
            if (anchorBottomScreen >= 0 && anchorTopScreen >= 0) {
                top = anchorBottomScreen + Fx.dp(this, 10);
                if (top + ch > vf.bottom - Fx.dp(this, 10)) {
                    // no room below (keyboard / bottom of screen) — flip above
                    top = Math.max(vf.top + Fx.dp(this, 10),
                            anchorTopScreen - ch - Fx.dp(this, 10));
                }
            } else {
                int kb = flRoot.getHeight() - vf.bottom;
                top = flRoot.getHeight() - Math.max(Fx.dp(this, 96), kb + Fx.dp(this, 14)) - ch;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) chip.getLayoutParams();
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            lp.topMargin = Math.max(Fx.dp(this, 8), top);
            lp.bottomMargin = 0;
            chip.setLayoutParams(lp);
        } catch (Throwable ignored) {}
    }

    /** re-anchor a chip as the keyboard finishes opening (the focusin that
     *  creates the chip lands BEFORE the IME has resized the window — and
     *  the field itself may scroll as the page reflows). v1.3.79: every
     *  settle pass re-queries the FIELD rect, not just the keyboard. */
    protected void chipSettle(TextView chip) {
        try {
            final TextView c = chip;
            ui.postDelayed(() -> { try { if (c.getParent() != null) anchorChipToField(c); } catch (Throwable ignored) {} }, 350);
            ui.postDelayed(() -> { try { if (c.getParent() != null) anchorChipToField(c); } catch (Throwable ignored) {} }, 900);
        } catch (Throwable ignored) {}
    }

    /** a password field got focus — offer the saved login as a fill chip
     *  (the OTP chip's sibling; auto-retires after 30 s).
     *  v1.3.91 — HOST-AWARE: the page's reported host is the lookup key
     *  (the favicon law — the page knows where it is), falling back to
     *  the tab's URL host when the report is empty.
     *  v1.4.4 — MULTI-ACCOUNT: the chip offers EVERY login the site has
     *  (findAll — exact host first, then base-domain, newest first). One
     *  login: the chip fills it directly (unchanged). Several: the chip
     *  reads "Saved logins · N" and opens an ACCOUNT PICKER (username
     *  rows) — the multi-account report: the vault held both rows but the
     *  chip only ever filled the first, reading as "cannot save multiple
     *  passwords for the same site". */
    protected void showPwChip() { showPwChip(""); }

    protected void showPwChip(String pageHost) {
        try {
            if (incognito) return;
            if (pwChip != null && pwChip.getParent() != null) return;
            String host = pageHost;
            if (host == null || host.trim().isEmpty()) {
                host = Fx.hostOf(activeTab().url == null ? "" : activeTab().url);
            }
            java.util.List<com.bolt.browser.util.PasswordVault.Entry> es =
                    com.bolt.browser.util.PasswordVault.findAll(this, host);
            if (es.isEmpty()) {
                // v1.4.4 — the page-host lookup found nothing: try the TAB's
                // host before giving up (a login saved under the tab's URL
                // host while the focus report carries an SSO subdomain —
                // or vice versa — still deserves its chip)
                String tabHost = Fx.hostOf(activeTab().url == null ? "" : activeTab().url);
                if (!tabHost.isEmpty() && !tabHost.equals(host)) {
                    es = com.bolt.browser.util.PasswordVault.findAll(this, tabHost);
                }
            }
            if (es.isEmpty()) return;
            boolean anySecret = false;
            for (com.bolt.browser.util.PasswordVault.Entry e : es) {
                if (e.pass != null && !e.pass.isEmpty()) { anySecret = true; break; }
            }
            if (!anySecret) return;
            final java.util.List<com.bolt.browser.util.PasswordVault.Entry> picks = es;
            final boolean multi = picks.size() > 1;
            final String chipHost = host;   // captured by the picker lambda below
            TextView tv = new TextView(this);
            android.graphics.drawable.GradientDrawable chipBg =
                    new android.graphics.drawable.GradientDrawable();
            chipBg.setColor(Fx.accent(this));
            chipBg.setCornerRadius(Fx.dp(this, 18));
            tv.setBackground(chipBg);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tv.setTextSize(15);
            int ph = Fx.dp(this, 16), pv = Fx.dp(this, 10);
            tv.setPadding(ph, pv, ph, pv);
            tv.setText(multi
                    ? getString(R.string.pw_fill_multi, picks.size())
                    : getString(R.string.pw_fill_chip,
                            picks.get(0).user.isEmpty() ? picks.get(0).host : picks.get(0).user));
            tv.setOnClickListener(v -> {
                if (!multi) { fillPwFromChip(picks.get(0)); return; }
                // the account picker: one row per saved login (user, or the
                // host when the username was never captured)
                String[] names = new String[picks.size()];
                for (int i = 0; i < picks.size(); i++) {
                    com.bolt.browser.util.PasswordVault.Entry e = picks.get(i);
                    names[i] = (e.user.isEmpty() ? e.host : e.user)
                            + (e.host.equals(chipHost) ? "" : " · " + e.host);
                }
                com.bolt.browser.util.Ui.choiceSheet(BrowserActivity.this,
                        getString(R.string.pw_pick_account), names, -1, pi ->
                                fillPwFromChip(picks.get(pi)));
            });
            pwChip = tv;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            lp.topMargin = Fx.dp(this, 96);           // baseline; anchorChipToField places it at the field
            flRoot.addView(tv, lp);
            anchorChipToField(tv);
            chipSettle(tv);
            ui.removeCallbacks(pwChipRetire);
            ui.postDelayed(pwChipRetire, 30_000);
        } catch (Throwable ignored) {}
    }

    /** tap on the chip: push the saved login into the page's form. */
    protected void fillPwFromChip(com.bolt.browser.util.PasswordVault.Entry e) {
        try {
            if (e == null) { hidePwChip(); return; }
            String u = e.user.replace("\\", "\\\\").replace("'", "\\'");
            String p = e.pass.replace("\\", "\\\\").replace("'", "\\'");
            evalMedia("window.__boltFillPw&&window.__boltFillPw('" + u + "','" + p + "')");
            hidePwChip();
        } catch (Throwable ignored) {}
    }

    protected void hidePwChip() {
        ui.removeCallbacks(pwChipRetire);
        try {
            if (pwChip != null && pwChip.getParent() == flRoot) flRoot.removeView(pwChip);
        } catch (Throwable ignored) {}
        pwChip = null;
    }

    // ------------------------------------------------------------ v1.3.39 profile fill chip

    protected TextView profileChip;
    protected final Runnable profileChipRetire = this::hideProfileChip;

    /** the "Fill profile" chip on detected address/card forms (the pw chip's
     *  sibling; 30 s auto-retire). */
    protected void showProfileChip() {
        try {
            if (incognito) return;
            if (profileChip != null && profileChip.getParent() != null) return;
            final List<com.bolt.browser.util.Profiles.Profile> ps =
                    com.bolt.browser.util.Profiles.list(this);
            if (ps.isEmpty()) return;
            TextView tv = new TextView(this);
            android.graphics.drawable.GradientDrawable chipBg =
                    new android.graphics.drawable.GradientDrawable();
            chipBg.setColor(Fx.accent(this));
            chipBg.setCornerRadius(Fx.dp(this, 18));
            tv.setBackground(chipBg);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tv.setTextSize(15);
            tv.setPadding(Fx.dp(this, 16), Fx.dp(this, 10), Fx.dp(this, 16), Fx.dp(this, 10));
            tv.setText("Fill profile · " + (ps.get(0).getLabel().isEmpty() ? "default" : ps.get(0).getLabel()));
            tv.setOnClickListener(v -> {
                if (ps.size() == 1) {
                    fillProfileFromChip(ps.get(0));
                } else {
                    String[] names = new String[ps.size()];
                    for (int i = 0; i < ps.size(); i++)
                        names[i] = ps.get(i).getLabel().isEmpty() ? "Profile " + (i + 1) : ps.get(i).getLabel();
                    com.bolt.browser.util.Ui.choiceSheet(this, "Fill with profile",
                            names, -1, idx -> fillProfileFromChip(ps.get(idx)));
                }
            });
            profileChip = tv;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            lp.topMargin = Fx.dp(this, 96);           // baseline; anchorChipToField places it at the field
            flRoot.addView(tv, lp);
            anchorChipToField(tv);
            chipSettle(tv);
            ui.removeCallbacks(profileChipRetire);
            ui.postDelayed(profileChipRetire, 30_000);
        } catch (Throwable ignored) {}
    }

    /** push the chosen profile into the page's form fields. */
    protected void fillProfileFromChip(com.bolt.browser.util.Profiles.Profile p) {
        try {
            if (p == null) { hideProfileChip(); return; }
            String json = com.bolt.browser.util.Profiles.fillJson(p)
                    .replace("\\", "\\\\").replace("'", "\\'");
            evalMedia("window.__boltFillProfile&&window.__boltFillProfile('" + json + "')");
            hideProfileChip();
        } catch (Throwable ignored) {}
    }

    protected void hideProfileChip() {
        ui.removeCallbacks(profileChipRetire);
        try {
            if (profileChip != null && profileChip.getParent() == flRoot) flRoot.removeView(profileChip);
        } catch (Throwable ignored) {}
        profileChip = null;
    }

    // ------------------------------------------------------------ v1.3.37 QR scanner

    /** menu / quick-action entry: open the live scanner and handle the
     *  decoded text in onActivityResult (request 1006). */
    protected void startQrScan() {
        try {
            startActivityForResult(new Intent(this, QrScanActivity.class), 1006);
        } catch (Throwable t) {
            Fx.toast(this, getString(R.string.scan_qr));
        }
    }

    /** the decoded QR: URLs offer open-here / open-in-new-tab, anything
     *  else offers search; both offer copy. Never auto-navigates. */
    protected void showQrResult(String text) {
        try {
            final String q = text.trim();
            boolean urlish = q.startsWith("http://") || q.startsWith("https://")
                    || q.contains("://") || q.startsWith("www.")
                    || q.matches("(?i)[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?");
            List<MenuRow> rows = new ArrayList<>();
            MenuRow head = row(R.drawable.ic_qr, q, null);
            rows.add(head);
            if (urlish) {
                rows.add(row(R.drawable.ic_globe, getString(R.string.open_here),
                        v -> { dismissAllOverlays(); loadInTab(q); }));
                rows.add(row(R.drawable.ic_plus, getString(R.string.open_in_new_tab),
                        v -> { dismissAllOverlays(); openNewTab(q); }));
            } else {
                rows.add(row(R.drawable.ic_search, getString(R.string.search_for_this),
                        v -> { dismissAllOverlays(); loadInTab(q); }));
            }
            rows.add(row(R.drawable.ic_copy, getString(R.string.copy),
                    v -> {
                        dismissAllOverlays();
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager)
                                            getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("QR", q));
                        } catch (Throwable ignored) {}
                        Fx.toast(this, getString(R.string.copied));
                    }));
            showSheetMenu(getString(R.string.qr_scanned), rows);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ v1.3.37 home wallpaper

    private Bitmap homeWallBg;
    private long homeWallStamp = -1;

    /** v1.3.37 — the custom start-page background. v1.3.38: the render
     *  pipeline lives in WallpaperStyle (Kotlin — dim/blur/zoom/pan, the
     *  field report's "same as picked" default with crop + blur options);
     *  this Java side only owns the CACHE (bitmap keyed on file stamp +
     *  style stamp — refreshHome rebuilds the home view on EVERY home show,
     *  re-rendering each time would hammer a 3 GB device). */
    private Drawable homeWallpaperDrawable() {
        try {
            File f = new File(getFilesDir(), "wallpaper.jpg");
            if (!f.exists()) return null;
            com.bolt.browser.util.WallpaperStyle.Params p =
                    com.bolt.browser.util.WallpaperStyle.params(this);
            long stamp = f.lastModified() * 31L
                    + com.bolt.browser.util.WallpaperStyle.stamp(p);
            if (homeWallBg != null && stamp == homeWallStamp) {
                return layerWallpaper(new BitmapDrawable(getResources(), homeWallBg), p);
            }
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            Bitmap bmp = com.bolt.browser.util.WallpaperStyle.render(
                    f, dm.widthPixels, dm.heightPixels, p, dm.widthPixels);
            if (bmp == null) return null;
            try { if (homeWallBg != null) homeWallBg.recycle(); } catch (Throwable ignored) {}
            homeWallBg = bmp;
            homeWallStamp = stamp;
            return layerWallpaper(new BitmapDrawable(getResources(), homeWallBg), p);
        } catch (Throwable t) {
            return null;
        }
    }

    /** photo + the optional dim veil (0 by default — the photo stays exactly
     *  as picked; the veil is the user's own legibility choice). */
    private Drawable layerWallpaper(Drawable photo,
            com.bolt.browser.util.WallpaperStyle.Params p) {
        if (p.dim <= 0) return photo;
        android.graphics.drawable.ColorDrawable scrim =
                new android.graphics.drawable.ColorDrawable(
                        (Fx.bg(this) & 0x00FFFFFF)
                                | ((p.dim * 255 / 100) << 24));
        return new android.graphics.drawable.LayerDrawable(
                new Drawable[]{ photo, scrim });
    }

    /** drop the cached wallpaper bitmap (after picking/removing). */
    protected void invalidateHomeWallpaper() {
        try { if (homeWallBg != null) homeWallBg.recycle(); } catch (Throwable ignored) {}
        homeWallBg = null;
        homeWallStamp = -1;
    }

    /** Appearance row + the home screen's long-press both land here.
     *  v1.3.38: three options — Default / Choose image / Adjust (the
     *  crop+blur+dim screen, the field report's "option to crop and apply
     *  blur as needed"). */
    protected void showWallpaperSheet() {
        com.bolt.browser.util.Ui.choiceSheet(this, getString(R.string.wallpaper),
                new String[]{ getString(R.string.wallpaper_default),
                        "Built-in wallpapers",
                        getString(R.string.wallpaper_choose),
                        getString(R.string.wallpaper_adjust) },
                com.bolt.browser.util.HomeWallpaper.active(this) ? 3 : 0, idx -> {
                    if (idx == 0) removeWallpaper();
                    else if (idx == 1) com.bolt.browser.util.DefaultWallpapers.showPicker(this);
                    else if (idx == 3) startActivity(new Intent(this,
                            com.bolt.browser.ui.WallpaperAdjustActivity.class));
                    else pickWallpaper();
                });
    }

    protected void pickWallpaper() {
        com.bolt.browser.util.HomeWallpaper.choose(this, 1007);
    }

    protected void removeWallpaper() {
        com.bolt.browser.util.HomeWallpaper.remove(this, this::onWallpaperChanged);
    }

    /** shared tail of every wallpaper change: drop the cached bitmap and
     *  re-render the start page when it is on screen right now. */
    protected void onWallpaperChanged() {
        invalidateHomeWallpaper();
        Tab t = activeTab();
        if (t != null && t.isHome) refreshHome();
    }

    /** decode the picked image, persist it (long edge capped at 1440 —
     *  plenty for a background, kind to a 3 GB device), show it immediately
     *  (the immediate refresh covers the case where the browser itself is
     *  the picker's caller; the homeGen bump covers Appearance-initiated
     *  changes via the home-rebuild-on-resume channel). */
    protected void applyWallpaper(Uri uri) {
        com.bolt.browser.util.HomeWallpaper.apply(this, uri, this::onWallpaperChanged);
    }

    // ------------------------------------------------------------ tabs

    protected Tab activeTab() {
        return tm.current();
    }

    /** find the tab owning this WebView (not just the active one — fixes
     *  stale URLs/titles on tabs that finished loading in the background) */
    protected Tab tabOf(WebView v) {
        synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
            for (Tab t : tm.tabs) if (t.web == v) return t;
        }
        return null;
    }

    protected void showTab(int idx) { showTab(idx, false); }

    /** v1.3.44: viaSwipe=true when the switch came from the toolbar swipe —
     *  those switches skip the lastVisitedAt re-stamp (see tabSwipeTarget)
     *  so the swipe walks the arrangement the user last SAW in the
     *  switcher instead of re-sorting it under their finger. Every other
     *  switch (switcher tap, link, restore…) still stamps — the sort stays
     *  truthful for deliberate navigation. */
    protected void showTab(int idx, boolean viaSwipe) {
        if (idx < 0 || idx >= tm.tabs.size()) idx = 0;
        tm.active = idx;
        // v1.3.31: "sort by last visited" bookkeeping — stamped whenever a
        // tab becomes the shown one (persisted with the next debounced save)
        try {
            Tab st = tm.tabs.get(idx);
            if (st != null && !viaSwipe) st.lastVisitedAt = System.currentTimeMillis();
        } catch (Throwable ignored) {}
        // v1.3.18: the active index is part of the persisted state — persist
        // the switch (debounced) so a kill mid-session restores the right tab.
        tm.scheduleSave();
        Tab t = tm.tabs.get(idx);
        // v1.3.97 — PULL-TO-REFRESH IS A WEB-PAGE GESTURE. On the home/start
        // tab it has nothing to reload, yet the old listener ran a FULL
        // refreshHome() — and a drag begun on a shortcut tile (the finger
        // moving before the long-press arms) falls through to the home
        // ScrollView, whose unconsumed overscroll became a pull: release,
        // and the entire home rebuilt under the user's finger ("the
        // shortcut icons causes home page to refresh when dragged"). The
        // standard behavior (Chrome/Firefox): the gesture reloads the PAGE,
        // never the start screen. Enabled per-tab here; prefs read live.
        try {
            if (t != null && flContent instanceof MediaAwareContent) {
                ((MediaAwareContent) flContent)
                        .setPullEnabled(prefs.pullToRefresh() && !t.isHome);
            }
        } catch (Throwable ignored) {}
        hideQuickPanel();
        hidePwChip();                                 // v1.3.38: no dangling fill chip
        // v1.4.16 — THE FIND-BAR BLEED-OVER: openFind() runs against the page
        // it was opened ON, but a tab switch (switcher tap, tab notification,
        // external intent) left the bar up with its live-match state — the
        // NEXT page inherited a search UI whose matches, count and query all
        // belonged to the page being left. Hide the bar, clear the query and
        // the count, and clearMatches on the DEPARTING tab (shownTabRef still
        // points at it here — it is re-pinned to the new tab further below).
        try {
            if (findbar != null && findbar.getVisibility() == View.VISIBLE) {
                findbar.setVisibility(View.GONE);
                Tab leftTab = shownTabRef != null ? shownTabRef : activeTab();
                if (leftTab != null && leftTab.web != null) {
                    try { leftTab.web.clearMatches(); } catch (Throwable ignored) {}
                }
                if (etFind != null) etFind.setText("");
                if (tvFindCount != null) tvFindCount.setText("");
                hideKeyboard();
            }
        } catch (Throwable ignored) {}
        exitFullscreenVideo();
        // v1.3.61 — a pseudo-fullscreen state belongs to the page being left;
        // showBarsNow below already brings the chrome back for the fresh tab
        if (pseudoFullscreen) pseudoFullscreen = false;
        // v1.3.62 — the wedged-enter rescue belongs to the page being left too
        try { fsRemoveCssRescue(); } catch (Throwable ignored) {}
        // fresh thumbnail of the tab we are leaving (prevents stale previews
        // in the tab grid — "ghosting of previous tabs screens"). v1.3.51:
        // by OBJECT — after a workspace swap the old INDEX points into the
        // NEW list and captured the wrong tab.
        if (shownTabRef != null && shownTabRef != t) {
            try {
                if (shownTabRef.web != null) shownTabRef.capture();
            } catch (Throwable ignored) {}
        }
        // cross-tab switch: an opaque veil is placed ON TOP of the new content
        // and dropped with a quick fade once the new view has drawn — this
        // covers the outgoing WebView's lingering last frame ("ghosting of the
        // last opened page") during the surface hand-off. v1.3.51: by OBJECT
        // (same-index switches across a workspace swap ARE cross-tab).
        boolean crossTab = shownTabRef != null && shownTabRef != t;
        // v1.3.19: cross-tab media carry-over. The old code detached EVERY
        // tab's WebView here (removeAllViews) — and a detached WebView's
        // renderer is hidden at the native level, which is exactly why music
        // in tab 1 died the moment you switched to tab 2. Tabs that hold live
        // or loaded media (bgPlay on, no per-site opt-out) now STAY attached
        // to the window, merely INVISIBLE: window visibility keeps arriving
        // as VISIBLE, the renderer never learns the tab left the screen, and
        // playback carries over while you read the other tab. Every other
        // child (outgoing WebView, home view, stray veils) detaches as before.
        detachTabsForSwitch(idx);
        View shown;
        if (t.isHome) {
            // v1.3.53: rebuild when home CONTENT landed since this view was
            // built (favicons, wallpaper style) — a REBUILD, never a
            // recreate; detachTabsForSwitch above already removed the old
            // home view from flContent, so this is a clean swap.
            if (homeView == null || Fx.homeGen != homeShownGen) {
                buildHomeView();
            }
            if (homeView.getParent() != flContent) flContent.addView(homeView);
            flContent.bringChildToFront(homeView);
            settleHomeGrid();   // v1.3.44: un-stick any interrupted entrance
            shown = homeView;
            hideErrorOverlay();   // v1.4.19: the home tab has no error face
        } else {
            JsBridge bridge = new JsBridge();
            WebView w = t.buildWeb(this, new BoltClient(), new BoltChrome(), bridge, incognito);
            // v1.4.19 — THE REFUSAL PATH: buildWeb now returns null instead of
            // crashing when WebView construction is impossible (the incognito
            // data-dir collision — see Tab.buildWeb's construction guard).
            // Degrade visibly: the tab list keeps the row, the user gets an
            // honest toast, no process dies.
            if (w == null) {
                try { Fx.toast(this, "Can't create this page's view — try reopening the tab"); } catch (Throwable ignored) {}
                return;
            }
            // v1.3.30 — ADOPTED WEB: buildWeb returned an existing WebView
            // built by another context (the phoenix's headless resume, or a
            // zombie media web from a previous activity instance). Rewire it
            // to THIS activity's clients so browsing is fully normal from
            // here on — setWebViewClient/setWebChromeClient are documented
            // live operations and never touch the running media pipeline, so
            // playback carries through the rewire. The fresh "Bolt" bridge
            // re-add replaces the phoenix bridge on the NEXT page load; until
            // then the phoenix bridge keeps forwarding media events (it
            // routes through the static service entry, instance-safe).
            if (w.getContext() != this) {
                try { w.setWebViewClient(new BoltClient()); } catch (Throwable ignored) {}
                try { w.setWebChromeClient(new BoltChrome()); } catch (Throwable ignored) {}
            }
            try { w.addJavascriptInterface(bridge, "Bolt"); } catch (Throwable ignored) {}
            bridge.attach(w);
            if (w.getParent() != null && w.getParent() != flContent)
                ((ViewGroup) w.getParent()).removeView(w);
            if (w.getParent() != flContent) flContent.addView(w);
            w.setVisibility(View.VISIBLE);
            flContent.bringChildToFront(w);
            w.setDownloadListener(downloadListener);
            w.setOnLongClickListener(v -> handleLongPress(w));
            // v1.3.38 — selection bubble actions: BoltWeb lets the browser
            // wrap the text-selection ActionMode and append Search /
            // Translate / Copy / Show QR to the system's floating menu.
            try {
                if (w instanceof com.bolt.browser.ui.BoltWeb) {
                    ((com.bolt.browser.ui.BoltWeb) w).actionModeWrapper =
                            com.bolt.browser.util.SelectionActions.wrap(
                                    this, selActionsHost(), w);
                }
            } catch (Throwable ignored) {}
            // v1.3.33: scroll-to-hide floating bars
            try { w.setOnScrollChangeListener(webScrollListener); } catch (Throwable ignored) {}
            // v1.3.35: pull-to-refresh rides the nested-scroll API
            try { w.setNestedScrollingEnabled(true); } catch (Throwable ignored) {}
            // v1.3.60: every tab switch probes for a PHANTOM renderer-side
            // fullscreen (see healRendererFullscreen) — the zombie-adoption
            // case (a WebView that survived this activity's predecessor
            // while its video was fullscreen) renders "zoomed-in" pseudo-
            // fullscreen and deadlocks PiP until the phantom is exited.
            // Guarded by customView == null inside, so a REAL fullscreen
            // video (or PiP) is never touched.
            try { healRendererFullscreen(w, "showTab"); } catch (Throwable ignored) {}
            shown = w;
            // v1.4.19 — THE OVERLAY RIDES ITS TAB: switching back to a tab
            // whose last load failed re-shows the native error overlay (the
            // overlay is per-activity; the per-tab errUrl marker is its
            // memory); a healthy tab retires it.
            if (t.errUrl != null && t.errUrl.startsWith("http")) {
                showErrorOverlay(w, t.errUrl);
            } else {
                hideErrorOverlay();
            }
            try { w.onResume(); } catch (Throwable ignored) {}
            if (t.url != null && !t.url.isEmpty() && w.getUrl() == null) {
                t.applyUa(this, w);
                if (t.renderCrashHold) {
                    // v1.3.19: this page crashed the renderer twice in 30 s —
                    // show the notice instead of auto-reloading into a loop
                    t.renderCrashHold = false;   // one shot; reload/navigate works normally after
                    // v1.3.20: one tap copies the diagnostics log (renderer-gone
                    // history + any uncaught exceptions) so the next field report
                    // comes with evidence instead of guesswork.
                    // v1.3.33: skin-aware colors (was hardcoded dark palette)
                    w.loadData("<html><body style='background:" + String.format("#%06X", 0xFFFFFF & Fx.webBg(this))
                            + ";color:" + String.format("#%06X", 0xFFFFFF & Fx.txt(this))
                            + ";font-family:sans-serif;text-align:center;padding-top:40%'><h2>:(</h2><p>This page kept crashing</p>"
                            + "<p style='color:" + String.format("#%06X", 0xFFFFFF & Fx.dim(this))
                            + ";font-size:12px'>" + t.url + "</p>"
                            + "<p style='margin-top:32px'><button onclick='Bolt.copyDiag()' "
                            + "style='background:" + String.format("#%06X", 0xFFFFFF & Fx.accent(this))
                            + ";color:#fff;border:none;padding:10px 18px;"
                            + "border-radius:8px;font-size:14px'>Copy diagnostics</button></p></body></html>",
                            "text/html", "utf-8");
                } else {
                    // v1.4.21 — THE HISTORY-FIRST LAW (the "back history
                    // lost after tab/app switch" corpse): a rebuilt WebView
                    // first restores its captured navigation state
                    // (saveState taken at sleep/trim/render-death; the cold
                    // blob from tabs.json when the process died) — the
                    // back/forward list AND the current item come back, so
                    // Back goes to the previous page instead of out of the
                    // tab to the home screen. restoreHist false (nothing
                    // captured / stale blob / provider mismatch) falls back
                    // to exactly the old loadUrl shape.
                    boolean restored = false;
                    try { restored = t.restoreHist(w); } catch (Throwable ignored) {}
                    if (!restored) {
                        w.loadUrl(t.url);
                    } else {
                        try {
                            com.bolt.browser.util.Diag.log(this, "tab hist restore idx=" + idx
                                    + (t.sleepState != null ? " warm" : " cold"));
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
        if (crossTab) addSwitchVeil(shown);
        shownTabIndex = idx;
        shownTabRef = t;   // v1.3.51: object identity (see the field comment)
        updateBars();
        applyContentInsets();
        // v1.3.33: a fresh page starts at the top — bars slide back in
        // v1.3.40: ...except in fullscreen browsing, where the chrome stays
        // aggressively out of the way (field: "keep them hidden a bit more
        // aggressively as they cover up content very often")
        if (com.bolt.browser.util.Fullscreen.on(this) && customView == null)
            hideBarsNow(false);
        else
            showBarsNow(false);
        // v1.3.25: re-pin renderer priorities on every switch — the tab that
        // just became visible regains full protection; hidden tabs yield if
        // some tab is holding media (see applyRendererPolicies).
        applyRendererPolicies("switch");
    }

    /**
     * v1.3.25 — the media tab's "dedicated RAM": a dynamic WebView renderer
     * priority policy that sacrifices hidden non-media tabs so the renderer
     * playing music never loses its memory.
     *
     * PLATFORM FACTS (AOSP WebSettings docs): the default policy —
     * RENDERER_PRIORITY_IMPORTANT, never waived — is the MAXIMUM protection a
     * WebView renderer can get; any other setting only makes the system kill
     * it MORE aggressively. That is why v1.3.20 removed the (harmful) demotion
     * of the media renderer itself: you cannot protect the media tab more than
     * the default already does.
     *
     * What CAN be done is the mirror image: while any tab holds media, every
     * OTHER hidden tab gets (IMPORTANT, waived-when-hidden) — the platform's
     * documented sacrifice mode. Under memory pressure lmkd then reclaims the
     * hidden tabs' renderers FIRST, freeing exactly the RAM the media
     * renderer needs to keep playing; the media tab (and the visible tab)
     * stay at the never-waived maximum. When nothing holds media, every tab
     * is restored to the default max policy — zero behavior change vs
     * v1.3.24 in the no-media case.
     *
     * Renderer deaths from the waiver are fully handled: onRenderProcessGone
     * rebuilds the tab when it is next shown (v1.3.19+), so a sacrificed tab
     * reloads on return — the accepted trade for uninterrupted playback.
     *
     * Note the interplay with MediaAwareContent: while the background-play
     * spoof is engaged, the media tab's WebView keeps receiving VISIBLE window
     * dispatches, so "not visible" never becomes true for it — even when the
     * app is backgrounded, the media renderer's (IMPORTANT, false) policy and
     * the mediaPlayback foreground service hold it at FGS grade.
     */
    protected void applyRendererPolicies(String why) {
        if (android.os.Build.VERSION.SDK_INT < 26) return; // setRendererPriorityPolicy is API 26+
        try {
            boolean anyMedia = false;
            synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
                for (Tab t : tm.tabs) {
                    if (t != null && (t.mediaPlaying || t.holdsMedia)) { anyMedia = true; break; }
                }
            }
            // v1.3.29 — signature gate. This runs on EVERY media event
            // ("media" reason), which during SPA/mix metadata churn was
            // ~1 Hz: each call walked every tab issuing a
            // setRendererPriorityPolicy IPC + a Diag file write, all while
            // backgrounded. When the computed policy VECTOR is unchanged
            // (same tabs, same waive bits) there is nothing to do — skip the
            // IPCs and the diag line entirely.
            StringBuilder sig = new StringBuilder(anyMedia ? "M" : "-");
            synchronized (tm.tabs) {   // v1.4.11: the thread law — size()+get() as one step
                for (int i = 0; i < tm.tabs.size(); i++) {
                    Tab t = tm.tabs.get(i);
                    if (t == null || t.web == null) continue;
                    boolean media = (t.mediaPlaying || t.holdsMedia);
                    boolean visible = (i == tm.active && shownTabIndex == i && !t.isHome);
                    // visible or media-holding tabs: (IMPORTANT, false) = never
                    // waived = platform max. Hidden non-media tabs while media is
                    // live somewhere: (IMPORTANT, true) = full protection in front,
                    // WAIVED while hidden = first to be reclaimed under pressure.
                    boolean waive = anyMedia && !media && !visible;
                    sig.append(i).append(waive ? 'w' : media ? 'm' : visible ? 'v' : '-');
                }
            }
            String sigStr = sig.toString();
            if (sigStr.equals(lastPolicySig)) return;
            lastPolicySig = sigStr;
            synchronized (tm.tabs) {   // v1.4.11: the thread law — size()+get() as one step
                for (int i = 0; i < tm.tabs.size(); i++) {
                    Tab t = tm.tabs.get(i);
                    if (t == null || t.web == null) continue;
                    boolean media = (t.mediaPlaying || t.holdsMedia);
                    boolean visible = (i == tm.active && shownTabIndex == i && !t.isHome);
                    boolean waive = anyMedia && !media && !visible;
                    try {
                        t.web.setRendererPriorityPolicy(
                                android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT, waive);
                    } catch (Throwable ignored) {}
                }
            }
            try {
                com.bolt.browser.util.Diag.log(this, "rendererPolicies " + why
                        + " anyMedia=" + anyMedia);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /**
     * v1.3.25 — cooperate with real memory pressure instead of waiting for
     * lmkd to shoot the whole process. Full-screen tab thumbnails (ARGB_8888
     * bitmaps, several MB each) are the single biggest app-process allocation
     * this browser holds; dropping the non-active ones under pressure
     * (RUNNING_CRITICAL while foreground, BACKGROUND+ while hidden) directly
     * shrinks the footprint the kernel scores us by — the difference between
     * the media renderer surviving or not on low-RAM devices. Thumbnails
     * regenerate on tab switches (Tab.capture) and the tabs grid already
     * renders a placeholder when snap is null, so nothing breaks.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        boolean critical = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
        if (!critical) return;
        int dropped = 0;
        synchronized (tm.tabs) {   // v1.4.11: the thread law — size()+get() as one step
            for (int i = 0; i < tm.tabs.size(); i++) {
                Tab t = tm.tabs.get(i);
                if (t == null || t.snap == null) continue;
                if (i == tm.active) continue;   // keep the visible tab's live preview
                try { t.snap.recycle(); } catch (Throwable ignored) {}
                t.snap = null;
                dropped++;
            }
        }
        try {
            com.bolt.browser.util.Diag.log(this, "trimMemory level=" + level
                    + " thumbnailsDropped=" + dropped);
        } catch (Throwable ignored) {}
        // v1.3.27 — TAB SLEEPING ("give the music the memory it needs").
        // While media is live somewhere, release the WebViews of every
        // NON-media, non-visible tab under real pressure: the TABS stay in
        // the list and rebuild (v1.4.21: through saveState/restoreState —
        // the back/forward list survives) on their next show (the v1.3.19
        // renderer-death machinery, now used proactively — Chrome's
        // tab-discard strategy). Bolt's renderer footprint shrinks by
        // hundreds of MB BEFORE lmkd has to shoot the whole process, so the
        // media renderer keeps its RAM and the music keeps playing. At
        // TRIM_COMPLETE while backgrounded — the last level before the whole
        // process is a candidate — even the ACTIVE tab's WebView is released;
        // it restores from its captured state on return.
        // v1.4.21 — THE SOFTENED TRIGGER (the field root cause: the device
        // fired trimMemory level=15 on EVERY app switch, so background tabs
        // slept on each switch and their back history died with the
        // renderer — the sleep path itself was history-safe only after the
        // saveState law above; the TRIGGER was the noise). The sleep pass
        // now runs from TRIM_MEMORY_MODERATE (60) up — level 15
        // (RUNNING_CRITICAL) and UI_HIDDEN (20) keep every tab's renderer;
        // the thumbnail drop above (cheap, regenerates) keeps its old gate.
        int slept = 0;
        try {
            boolean realPressure = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE;
            boolean anyMedia = false;
            synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
                for (Tab t : tm.tabs) {
                    if (t != null && (t.mediaPlaying || t.holdsMedia)) { anyMedia = true; break; }
                }
            }
            if (anyMedia && realPressure) {
                boolean lastDitch = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                        && activityBackground;
                // v1.4.11: the thread law — sleepTab() destroys a WebView
                // (long teardown), so iterate a SNAPSHOT taken under the
                // monitor, never the live list
                List<Tab> sleepSnap;
                synchronized (tm.tabs) { sleepSnap = new ArrayList<>(tm.tabs); }
                for (int i = 0; i < sleepSnap.size(); i++) {
                    Tab t = sleepSnap.get(i);
                    if (t == null || t.web == null) continue;
                    boolean media = t.mediaPlaying || t.holdsMedia;
                    boolean active = (i == tm.active);
                    if (media) continue;                         // NEVER touch the music
                    if (active && !lastDitch) continue;          // keep the return-to page
                    if (i == shownTabIndex && !lastDitch) continue; // attached & visible right now
                    sleepTab(t);
                    slept++;
                }
            }
        } catch (Throwable ignored) {}
        if (slept > 0) {
            try {
                com.bolt.browser.util.Diag.log(this, "tabSleep level=" + level
                        + " tabs=" + slept + " bg=" + activityBackground);
            } catch (Throwable ignored) {}
            tm.scheduleSave();
        }
        // pressure may have changed which tabs should yield — re-pin now
        applyRendererPolicies("trim");
    }

    /** v1.3.27 — destroy a sleeping tab's WebView but KEEP the tab (it
     *  rebuilds from t.url on its next show — the same recovery path a
     *  renderer death takes). Teardown order mirrors TabManager.close().
     *  v1.4.21 — HISTORY LIVES + THE SLEEP FLAGS: the trim path now
     *  captures saveState() BEFORE the blank+destroy (the back/forward
     *  list survives the rebuild) and sets the same sleeping flags
     *  TabSleep.sleep() does, so the wake restores the scroll position
     *  too. And the trigger itself softened: see the level>=60 gate in
     *  onTrimMemory (the device fired level=15 on EVERY app switch —
     *  brief switches no longer destroy anything). */
    protected void sleepTab(Tab t) {
        if (t.web == null) return;
        try { t.web.stopLoading(); } catch (Throwable ignored) {}
        try { t.sleepScrollY = t.web.getScrollY(); } catch (Throwable ignored) {}
        try { t.saveHist(t.web); } catch (Throwable ignored) {}
        try { t.web.loadUrl("about:blank"); } catch (Throwable ignored) {}
        try {
            if (t.web.getParent() instanceof ViewGroup)
                ((ViewGroup) t.web.getParent()).removeView(t.web);
        } catch (Throwable ignored) {}
        try { t.web.destroy(); } catch (Throwable ignored) {}
        t.web = null;
        t.sleeping = true;
        t.mediaPlaying = false;
        t.holdsMedia = false;
    }

    /** v1.3.19: selective detach for showTab(). Media-holding tabs keep their
     *  WebView attached (INVISIBLE — still windowed, still receiving VISIBLE
     *  window visibility, media pipeline untouched); everything else in the
     *  content pane is removed exactly like the old removeAllViews(). The
     *  shown tab's OWN web is never swept either (no detach blip when
     *  re-showing / returning to it — showTab makes it VISIBLE + front), and
     *  going home on a playing tab keeps that tab's web attached too (music
     *  survives the trip to the home screen). */
    protected void detachTabsForSwitch(int showIdx) {
        try {
            List<WebView> keepShown = null;   // target's own web — stays attached as-is
            List<WebView> keepHidden = null;  // other media tabs — attached, INVISIBLE
            Tab tgt = showIdx >= 0 && showIdx < tm.tabs.size() ? tm.tabs.get(showIdx) : null;
            if (tgt != null && tgt.web != null && !tgt.isHome) {
                keepShown = new ArrayList<>();
                keepShown.add(tgt.web);
            } else if (tgt != null && tgt.web != null && prefs.bgPlay()
                    && (tgt.mediaPlaying || tgt.holdsMedia) && !siteBlockBg(tgt)) {
                // target went home but holds media — keep its web alive hidden
                keepHidden = new ArrayList<>();
                keepHidden.add(tgt.web);
            }
            if (prefs.bgPlay()) {
                synchronized (tm.tabs) {   // v1.4.11: the thread law — size()+get() as one step
                    for (int i = 0; i < tm.tabs.size(); i++) {
                        if (i == showIdx) continue;
                        Tab t = tm.tabs.get(i);
                        if (t == null || t.web == null) continue;
                        if ((t.mediaPlaying || t.holdsMedia) && !siteBlockBg(t)) {
                            if (keepHidden == null) keepHidden = new ArrayList<>();
                            if (!keepHidden.contains(t.web)) keepHidden.add(t.web);
                        }
                    }
                }
            }
            for (int i = flContent.getChildCount() - 1; i >= 0; i--) {
                View c = flContent.getChildAt(i);
                if (keepShown != null && keepShown.contains(c)) {
                    // stays attached; showTab makes it VISIBLE + brings it to front
                } else if (keepHidden != null && keepHidden.contains(c)) {
                    c.setVisibility(View.INVISIBLE);   // attached, not drawn
                    // belt & suspenders: assert media-pipeline liveness —
                    // web.onResume() is what thaws a frozen renderer (v1.3.16)
                    try { ((WebView) c).onResume(); } catch (Throwable ignored) {}
                } else {
                    flContent.removeViewAt(i);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** opaque cover masking the previous tab's last frame until the newly
     *  attached view completes its first draw (then fades out in ~100 ms;
     *  hard fallback removes it after 400 ms no matter what). */
    protected void addSwitchVeil(final View content) {
        try {
            final android.widget.FrameLayout fl = flContent;
            final View veil = new View(this);
            veil.setBackgroundColor(Fx.bg(this));
            fl.addView(veil, new android.widget.FrameLayout.LayoutParams(-1, -1));
            final Runnable drop = () -> {
                try {
                    if (veil.getParent() == fl) {
                        veil.animate().alpha(0f).setDuration(100L).withEndAction(() -> {
                            try { fl.removeView(veil); } catch (Throwable ignored) {}
                        }).start();
                    }
                } catch (Throwable ignored) {}
            };
            content.getViewTreeObserver().addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    try {
                        content.getViewTreeObserver().removeOnPreDrawListener(this);
                    } catch (Throwable ignored) {}
                    content.post(drop);
                    return true;
                }
            });
            veil.postDelayed(drop, 400);
        } catch (Throwable ignored) {}
    }

    protected void updateBars() {
        Tab t = activeTab();
        String u = t.isHome ? "" : t.url;
        tvUrl.setText(t.isHome ? getString(R.string.app_name) : (u == null ? "" : u));
        ivLock.setImageResource(incognito ? R.drawable.ic_incognito
                : (u != null && u.startsWith("https") ? R.drawable.ic_lock : R.drawable.ic_lock));
        if (!u.isEmpty() && !u.startsWith("https") && !t.isHome) {
            ivLock.setColorFilter(0xFFF59E0B);
        } else {
            ivLock.setColorFilter(Fx.tint(this));
        }
        tvTabCount.setText(tm.tabs.size() > 99 ? "99+" : String.valueOf(tm.tabs.size()));
        btnBack.setEnabled(true); // enabled visual state handled by alpha
        btnBack.setAlpha(t.web != null && t.web.canGoBack() ? 1f : 0.35f);
        btnForward.setAlpha(t.web != null && t.web.canGoForward() ? 1f : 0.35f);
        btnReload.setImageResource(R.drawable.ic_reload);
    }

    // ------------------------------------------------------------ v1.3.33 skin chrome + scroll-hiding bars

    /** paint the floating bars + system bars per skin. Opaque colorBg for
     *  classic skins (identical to the pre-v1.3.33 inline layout), smoked
     *  glass with a hairline rim for Aura — the page shows through. */
    protected void applySkinChrome() {
        try {
            View top = findViewById(R.id.topbar);
            View bottom = findViewById(R.id.bottombar);
            // v1.3.36: the rimmed edge follows the bar's ACTUAL screen side —
            // with the URL bar docked at bottom, topbar sits at the bottom
            // v1.3.39 ADAPTIVE GLASS (field report: "make it detect if the
            // page is white or black near the bars and apply glass effect
            // accordingly … also increase the transparency a bit more"):
            // the blur snapshots' luminance picks WHITE or BLACK glass per
            // bar, replacing the app-lightness guess; GlassAdapt carries the
            // (lower) alpha.
            // v1.3.40 EDGE-TRUE GLASS: each bar wears the color sampled at
            // ITS own screen edge. (The v1.3.39 mapping fed the two bars
            // each other's edge verdicts whenever the URL bar was docked
            // at the bottom.) topEdgeGlassWhite is always the WINDOW-TOP
            // strip's verdict, whoever the bar view is.
            if (top != null) {
                boolean topAtTopEdge = !urlBarAtBottom();   // topbar docks bottom when flipped
                top.setBackground(Fx.isGlass(this)
                        ? com.bolt.browser.util.GlassAdapt.barBg(this, topAtTopEdge,
                                topAtTopEdge ? topEdgeGlassWhite : botEdgeGlassWhite)
                        : Fx.barBg(this, !urlBarAtBottom()));
            }
            if (bottom != null) {
                boolean botAtTopEdge = urlBarAtBottom();
                bottom.setBackground(Fx.isGlass(this)
                        ? com.bolt.browser.util.GlassAdapt.barBg(this, botAtTopEdge,
                                botAtTopEdge ? topEdgeGlassWhite : botEdgeGlassWhite)
                        : Fx.barBg(this, urlBarAtBottom()));
            }
            // system bars match the chrome (Aura: adaptive glass; else bg).
            // The TOP-of-screen bar decides the status-bar tint.
            boolean topEdgeWhite = topEdgeGlassWhite;
            int sb = Fx.isGlass(this)
                    ? com.bolt.browser.util.GlassAdapt.sysBar(topEdgeWhite)
                    : Fx.bg(this);
            android.view.Window w = getWindow();
            // v1.3.39: colors are set even in fullscreen browsing — while the
            // system bars are hidden they are invisible anyway, and the
            // bars-shown state needs the right tint immediately.
            if (w != null && customView == null) {
                w.setStatusBarColor(sb);
                w.setNavigationBarColor(sb);
            }
            // progress strip follows the runtime accent
            try {
                View p = findViewById(R.id.v_progress);
                if (p != null) p.setBackgroundColor(Fx.accent(this));
            } catch (Throwable ignored) {}
            // v1.3.40: the page viewport accounts for the bars (field ask)
            applyContentInsets();
            // v1.3.46 (field: "in the dark glass, the number of tabs in the
            // tab rectangle is not there"): the glyphs that sit DIRECTLY on
            // the glass (the count square + the bare toolbar icons) now
            // follow the ADAPTIVE verdict — over a light page the bar turns
            // WHITE glass and the near-white number/icons washed out (the
            // count square has a transparent fill, unlike the URL chip with
            // its own surface). White glass -> dark indigo glyphs; dark
            // glass -> pure white (unchanged from v1.3.45).
            applyGlassGlyphs();
            // v1.3.35: real backdrop blur behind the Aura glass — v1.3.40
            // removed the API-31 gate (StackBlur below 31); the SAMPLING no
            // longer depends on the blur views at all (PixelSnap)
            setupGlassBlur();
        } catch (Throwable ignored) {}
    }

    /** v1.3.46 — glyph color for a glass bar by its adaptive verdict. Dark
     *  glass keeps v1.3.45's pure white; white glass goes dark indigo (the
     *  white-glass rim family, a step stronger). Non-glass skins never call
     *  this (they keep Fx.tint/Fx.txt). */
    protected int glassGlyphColor(boolean whiteGlass) {
        return whiteGlass ? 0xFF33305A : 0xFFFFFFFF;
    }

    /** which EDGE's verdict a bar wears (mirrors the background logic in
     *  applySkinChrome: the topbar docks the bottom when the URL bar flips). */
    protected boolean barGlassWhite(View bar) {
        boolean topbarAtTopEdge = bar != null && bar.getId() == R.id.topbar;
        boolean atTopEdge = topbarAtTopEdge != urlBarAtBottom();
        return atTopEdge ? topEdgeGlassWhite : botEdgeGlassWhite;
    }

    /** live tint for a bar icon — verdict-aware on glass skins, Fx.tint
     *  elsewhere; also the press-release restore color (pressFxBar). */
    protected int glyphTintOf(View icon) {
        try {
            if (icon == null || !Fx.isGlass(this)) return Fx.tint(this);
            View top = findViewById(R.id.topbar);
            View bottom = findViewById(R.id.bottombar);
            if (isInside(icon, top)) return glassGlyphColor(barGlassWhite(top));
            if (isInside(icon, bottom)) return glassGlyphColor(barGlassWhite(bottom));
        } catch (Throwable ignored) {}
        return Fx.tint(this);
    }

    private boolean isInside(View child, View ancestor) {
        if (child == null || ancestor == null) return false;
        android.view.ViewParent p = child.getParent();
        while (p != null) {
            if (p == ancestor) return true;
            p = p.getParent();
        }
        return false;
    }

    /** v1.3.91 — THE ONE COLOR LAW for the tab-count number: the live
     *  adaptive glass verdict of the BOTTOM bar (exactly what
     *  applyGlassGlyphs paints). The v1.3.79 press effect restored the
     *  color with a HARDCODED prefs-dark heuristic instead — whenever the
     *  verdict disagreed (a light page under dark mode, a dark glass over
     *  a light page), the number came back the color of its own box and
     *  VANISHED: "sometimes the number in the tabs disappear when I tap
     *  on it". Every restore path now funnels through this. */
    protected int tabCountGlyphColor() {
        try {
            if (Fx.isGlass(this)) {
                View bottom = findViewById(R.id.bottombar);
                return glassGlyphColor(barGlassWhite(bottom));
            }
            return Fx.txt(this);
        } catch (Throwable t) {
            return Fx.txt(this);
        }
    }

    /** v1.3.46 — paint every glyph that sits directly on the glass bars with
     *  its bar's verdict color: the bare toolbar icons, and the tab-count
     *  square (text + box stroke). The URL text/lock keep their colors (they
     *  sit on the URL chip's own translucent surface, which never washes
     *  out), as do the find/TTS riders. Idempotent; runs from every
     *  applySkinChrome pass (verdict flips, layout flips, theme changes). */
    protected void applyGlassGlyphs() {
        try {
            View top = findViewById(R.id.topbar);
            View bottom = findViewById(R.id.bottombar);
            boolean glass = Fx.isGlass(this);
            int topTint = glass ? glassGlyphColor(barGlassWhite(top)) : Fx.tint(this);
            int botTint = glass ? glassGlyphColor(barGlassWhite(bottom)) : Fx.tint(this);
            int[] topIcons = {R.id.btn_site_settings, R.id.btn_reload, R.id.btn_more};
            for (int id : topIcons) {
                View v = findViewById(id);
                if (v instanceof ImageButton) ((ImageButton) v).setColorFilter(topTint);
            }
            int[] botIcons = {R.id.btn_back, R.id.btn_forward, R.id.btn_home, R.id.btn_menu};
            for (int id : botIcons) {
                View v = findViewById(id);
                if (v instanceof ImageButton) ((ImageButton) v).setColorFilter(botTint);
            }
            // the count square: number + box stroke follow its bar's verdict
            // (the XML defaults remain correct for non-glass skins)
            if (tvTabCount != null) {
                tvTabCount.setTextColor(tabCountGlyphColor());
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(Fx.dp(this, 7));
                g.setStroke(Math.max(1, Fx.dp(this, 2)),
                        glass ? botTint : Fx.tint(this));
                tvTabCount.setBackground(g);
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- v1.3.40 — the page accounts for the bars ----------------
    //
    // Field ask: "make the websites load up such that it accounts for the
    // bars and starts loading from below the top search bar and till above
    // the bottom toolbar". The chrome still floats OVER the content (the
    // glass needs that), but the WebView viewport is inset by the bar
    // heights, so pages lay out BETWEEN the bars — nothing critical renders
    // underneath them. Bars that hide on scroll overlay the page again
    // (standard Chrome behavior); fullscreen browsing drops the insets to
    // zero for true edge-to-edge.

    /** inset the content viewport by the bar heights (0 in fullscreen). */
    protected void applyContentInsets() { applyContentInsets(false); }

    /** the running content-inset animation, if any (cancelled by a new target). */
    protected android.animation.ValueAnimator insetAnim;

    /** v1.3.76 — THE OSCILLATION KILL. Hiding/showing the bars animates
     *  flContent's padding, which RESIZES the WebView; Chromium's scroll
     *  anchoring answers that resize with a synthetic scroll delta in the
     *  OPPOSITE direction, which re-toggled the bars, which resized again
     *  — the "toolbars appear-disappear quickly and won't let me scroll,
     *  stuck in an oscillating state" report. Two guards: while an inset
     *  animation settles (animation + margin) bar logic ignores scroll
     *  deltas entirely, and no two toggles may land closer than 350 ms. */
    protected long insetSettleUntil = 0L;
    protected long lastBarToggleAt = 0L;

    /** v1.3.43: the content insets FOLLOW the bars. The v1.3.33-42 layout
     *  kept the bar-height padding when the bars scrolled away, leaving two
     *  dead strips (the v1.3.42 field report: "the contents don't readjust
     *  to take over the empty spaces created by the vanished toolbar") —
     *  hidden bars now hand their space to the page, and take it back when
     *  they return, animated in the same beat as the bar slide (150ms).
     *  Fullscreen browsing keeps its true edge-to-edge zeros.
     *  v1.3.81 — THE GHOST KILL: the 90ms per-frame padding animator RESIZED
     *  the WebView ~12 times per toggle, and on every one of those frames
     *  the newly exposed strip showed the WebView background until the
     *  renderer painted the new size — the "black rectangle ghost in the
     *  bottom portion that takes more time to go away" report (webBg is
     *  near-black in dark skins, i.e. a strip the same color as the bar that
     *  just left). The padding is now set ONCE, instantly; the callers
     *  sequence it under the covering bars (hide: insets first, slide one
     *  beat later; show: insets first, bars slide in over the vacated
     *  strips, which are flContent's colorBg — the same family the bars
     *  paint). One resize still echoes once, so the v1.3.76 settle window
     *  stays armed. */
    protected void applyContentInsets(boolean animate) {
        try {
            if (flContent == null) return;
            int tp, bp;
            if (com.bolt.browser.util.Fullscreen.on(this)) {
                tp = 0; bp = 0;
            } else if (urlBarAtBottom()) {
                tp = Fx.dp(this, 54);    // bottombar docks the top edge when flipped
                bp = Fx.dp(this, 58);    // top_group (url bar) docks the bottom
            } else {
                tp = Fx.dp(this, 58);
                bp = Fx.dp(this, 54);
            }
            // v1.3.43: bars hidden by scroll/keyboard -> the page takes the space
            if (!barsShown && barsAutoHidden) { tp = 0; bp = 0; }
            // v1.3.89 — THE FULLSCREEN IME PAD rides on top of whatever the
            // bars computed: in fullscreen browsing the window NEVER resizes
            // for the keyboard (SYSTEM_UI_FLAG_FULLSCREEN defeats
            // adjustResize on <30; setDecorFitsSystemWindows(false) on 30+),
            // so the IME used to cover the page's composer — "the typing
            // area does not move above the keyboard… I have to blind type".
            // While the keyboard is open the content pads up by the live
            // keyboard height (the fixed-position composers ride the
            // resized WebView, exactly what adjustResize does natively) and
            // down below the status-bar strip — the top edge is the
            // system's shade-gesture zone, and content parked under it ate
            // taps ("tapping the search bar… seems to be tapping the focus
            // on the notification drawer").
            if (fsKbBottom > 0 && com.bolt.browser.util.Fullscreen.on(this)
                    && customView == null) {
                tp = fsKbTop;
                bp = fsKbBottom;
            }
            int curT = flContent.getPaddingTop(), curB = flContent.getPaddingBottom();
            if (curT == tp && curB == bp) return;
            if (insetAnim != null) { insetAnim.cancel(); insetAnim = null; }
            // v1.3.76: the settle window — a resize echo still lands once and
            // bar logic must not hear it
            insetSettleUntil = android.os.SystemClock.uptimeMillis() + 90L + 320L;
            flContent.setPadding(0, tp, 0, bp);
        } catch (Throwable ignored) {}
    }

    // ---------------- v1.3.35 — REAL backdrop blur behind the glass bars ----------------
    //
    // Two ImageViews sit BETWEEN the page and the chrome, each showing a
    // snapshot of the content directly behind its bar with a RenderEffect
    // blur applied to the VIEW (API 31+). The translucent glass gradient
    // then paints over the blurred copy — the frosted-glass reading the
    // flat alpha fill could only approximate. Snapshots refresh throttled
    // (~120ms) as the page scrolls and when the bars reappear; they fade
    // out with the bars. Costs nothing on non-glass skins or API < 31.

    protected ImageView blurTop, blurBottom;
    private long lastBlurSnap;
    private boolean blurRetryPosted;

    protected void setupGlassBlur() {
        try {
            // v1.3.40: the API-31 gate is GONE (that teardown is what killed
            // glass sampling on pre-12 devices — the whole adaptive-glass
            // field bug). Below 31 the backdrop bitmap gets StackBlur'd
            // instead of RenderEffect, so frosted glass works on EVERY API.
            if (!Fx.isGlass(this) || flRoot == null || flContent == null) {
                teardownGlassBlur();
                sampleGlass();
                return;
            }
            if (flContent.getWidth() <= 0) {
                // not laid out yet (first applySkinChrome runs in onCreate) —
                // retry once after the layout pass
                if (!blurRetryPosted) {
                    blurRetryPosted = true;
                    flRoot.post(this::setupGlassBlur);
                }
                return;
            }
            blurRetryPosted = false;
            if (blurTop == null) {
                blurTop = new ImageView(this);
                blurTop.setScaleType(ImageView.ScaleType.FIT_XY);
                if (Build.VERSION.SDK_INT >= 31)
                    blurTop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                            Fx.dp(this, 18), Fx.dp(this, 18), android.graphics.Shader.TileMode.CLAMP));
                blurBottom = new ImageView(this);
                blurBottom.setScaleType(ImageView.ScaleType.FIT_XY);
                if (Build.VERSION.SDK_INT >= 31)
                    blurBottom.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                            Fx.dp(this, 18), Fx.dp(this, 18), android.graphics.Shader.TileMode.CLAMP));
                int contentIdx = flRoot.indexOfChild(flContent);
                flRoot.addView(blurTop, contentIdx + 1);
                flRoot.addView(blurBottom, contentIdx + 2);
            }
            // v1.3.36: each blur layer sits behind ITS bar wherever the
            // urlBarBottom placement put that bar
            FrameLayout.LayoutParams lpt = new FrameLayout.LayoutParams(-1, Fx.dp(this, 58));
            lpt.gravity = urlBarAtBottom() ? Gravity.BOTTOM : Gravity.TOP;
            blurTop.setLayoutParams(lpt);
            FrameLayout.LayoutParams lpb = new FrameLayout.LayoutParams(-1, Fx.dp(this, 54));
            lpb.gravity = urlBarAtBottom() ? Gravity.TOP : Gravity.BOTTOM;
            blurBottom.setLayoutParams(lpb);
            blurTop.setAlpha(barsShown ? 1f : 0f);
            blurBottom.setAlpha(barsShown ? 1f : 0f);
            updateBlurSnapshots();
        } catch (Throwable ignored) {}
    }

    protected void teardownGlassBlur() {
        try {
            if (blurTop != null) { flRoot.removeView(blurTop); blurTop = null; }
            if (blurBottom != null) { flRoot.removeView(blurBottom); blurBottom = null; }
        } catch (Throwable ignored) {}
    }

    /** refresh the blurred backdrops (throttled ~120ms; called from scroll
     *  + bar-show + page-finish). v1.3.40: the glass tint keeps updating
     *  even when the blur views are absent — sampleGlass() is independent. */
    protected void updateBlurSnapshots() {
        try {
            if (blurTop == null && blurBottom == null) { sampleGlass(); return; }
            if (!barsShown) return;
            long now = android.os.SystemClock.uptimeMillis();
            // v1.3.79: 120 -> 400 ms — at 120 ms the glass pass ran two
            // PixelCopy GPU readbacks (+ StackBlur on <31) ~8x/second for
            // the whole scroll ("consumes battery faster than other light
            // browsers"); 400 ms keeps the glass honestly adaptive at a
            // quarter of the cost
            if (now - lastBlurSnap < 400) return;
            lastBlurSnap = now;
            // v1.3.36: the snapshot side follows the bar's actual placement
            snapBlur(blurTop, !urlBarAtBottom());
            snapBlur(blurBottom, urlBarAtBottom());
        } catch (Throwable ignored) {}
    }

    private void snapBlur(ImageView iv, boolean topEdge) {
        if (iv == null || flContent == null || flContent.getWidth() <= 0) return;
        try {
            android.graphics.Rect r = glassStripRect(topEdge);
            if (r == null) return;
            // v1.3.40: PixelCopy captures what is actually composited on
            // the screen — the old software draw (flContent.draw(canvas))
            // returns BLANK for GPU-composited WebView content, which was
            // the whole "glass never detects white" field bug.
            com.bolt.browser.util.PixelSnap.copyRect(this, r, b -> {
                try {
                    if (b == null) return;
                    noteGlassLum(topEdge, com.bolt.browser.util.GlassAdapt.luminance(b));
                    android.graphics.Bitmap show = b;
                    if (Build.VERSION.SDK_INT < 31)
                        show = com.bolt.browser.util.StackBlur.blur(b, 12);  // RenderEffect is 31+
                    iv.setImageBitmap(show);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** v1.3.40: window rect of the content strip adjacent to the given
     *  screen edge — what the glass "sees" under that edge's bar. Pages
     *  start below the bar (applyContentInsets), so this strip is the
     *  first band of actual page content — "detect if the page is white
     *  or black NEAR the bars" (field report, v1.3.39). */
    private android.graphics.Rect glassStripRect(boolean topEdge) {
        try {
            if (flContent == null || flContent.getWidth() <= 0 || flContent.getHeight() <= 0)
                return null;
            int[] loc = new int[2];
            flContent.getLocationInWindow(loc);
            int tp = flContent.getPaddingTop(), bp = flContent.getPaddingBottom();
            int h = Fx.dp(this, 40);
            int yTop, yBot;
            if (topEdge) {
                yTop = loc[1] + tp;
                yBot = Math.min(yTop + h, loc[1] + flContent.getHeight() - bp);
            } else {
                yBot = loc[1] + flContent.getHeight() - bp;
                yTop = Math.max(yBot - h, loc[1] + tp);
            }
            if (yBot - yTop < 8) return null;
            return new android.graphics.Rect(loc[0], yTop, loc[0] + flContent.getWidth(), yBot);
        } catch (Throwable t) { return null; }
    }

    /** v1.3.40: glass-tint sampling decoupled from the blur backdrops —
     *  runs on the PixelSnap worker so it never jankies the UI thread and
     *  keeps working on skins/APIs where the blur views are absent. */
    private long lastGlassSample;
    protected void sampleGlass() {
        try {
            if (!Fx.isGlass(this)) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastGlassSample < 400) return;   // v1.3.79: 120 -> 400 ms (battery)
            lastGlassSample = now;
            final android.graphics.Rect rt = glassStripRect(true);
            final android.graphics.Rect rb = glassStripRect(false);
            if (rt == null && rb == null) return;
            com.bolt.browser.util.PixelSnap.bg(() -> {
                try {
                    if (rt != null) {
                        Float lt = com.bolt.browser.util.PixelSnap.stripLumSync(
                                BrowserActivity.this, flContent, true, Fx.dp(BrowserActivity.this, 40));
                        if (lt != null) ui.post(() -> noteGlassLum(true, lt));
                    }
                    if (rb != null) {
                        Float lb = com.bolt.browser.util.PixelSnap.stripLumSync(
                                BrowserActivity.this, flContent, false, Fx.dp(BrowserActivity.this, 40));
                        if (lb != null) ui.post(() -> noteGlassLum(false, lb));
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** bars currently slid in (true) or out (false) */
    protected boolean barsShown = true;
    /** accumulated scroll delta since the last bar toggle decision */
    protected int scrollDeltaSinceBar = 0;

    // ---------------- v1.3.39 adaptive-glass state ----------------
    /** v1.3.40 EDGE semantics: luminance of the content at the WINDOW-TOP /
     *  WINDOW-BOTTOM strips (whose bar wears which color is resolved in
     *  applySkinChrome). */
    protected float topEdgeLum = 0.5f, botEdgeLum = 0.5f;
    /** which glass (white/black) each EDGE currently wears — hysteresis
     *  inside GlassAdapt.isWhite keeps scrolling pages from strobing. */
    protected boolean topEdgeGlassWhite = false, botEdgeGlassWhite = false;

    /** repaint the bars when a strip's luminance crossed the glass band. */
    protected void noteGlassLum(boolean topEdge, float lum) {
        boolean was = topEdge ? topEdgeGlassWhite : botEdgeGlassWhite;
        boolean now = com.bolt.browser.util.GlassAdapt.isWhite(was ? 1f : 0f, lum);
        if (topEdge) { topEdgeLum = lum; topEdgeGlassWhite = now; }
        else { botEdgeLum = lum; botEdgeGlassWhite = now; }
        if (was != now) applySkinChrome();
    }

    /**
     * v1.3.33 — Chrome-style scroll-to-hide floating bars. The bars overlay
     * the page (see activity_browser.xml); scrolling DOWN slides them out
     * (fullscreen content), scrolling UP slides them back. Suppressed while
     * fullscreen video plays, the find/TTS bars are open, or the keyboard is
     * up (the bars would just cover the shrunk page).
     */
    protected void setupBarScrollHiding() {
        try {
            flRoot.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    android.graphics.Rect r = new android.graphics.Rect();
                    flRoot.getWindowVisibleDisplayFrame(r);
                    int visible = r.height();
                    boolean kb = flRoot.getHeight() - visible > Fx.dp(this, 180);
                    // v1.3.43: act only on real keyboard TRANSITIONS. The
                    // v1.3.33-42 listener re-evaluated (and re-SHOWED the
                    // bars) on EVERY layout pass — and in fullscreen browsing
                    // the immersive transition itself lays the window out, so
                    // every scroll-hide popped the bars straight back ("the
                    // bars don't hide at all when I scroll"). In fullscreen
                    // the hover button owns showing, per the v1.3.40 design.
                    if (kb == kbWasOpen) return;
                    kbWasOpen = kb;
                    // v1.3.89 — THE FULLSCREEN IME PAD: in fullscreen
                    // browsing the window never resizes for the keyboard
                    // (immersive defeats adjustResize), so the IME covered
                    // the page's composer — the blind-typing report. On the
                    // open transition the content pads by the LIVE keyboard
                    // height (visible-frame loss; the window is stable so
                    // the number is exact) plus the status strip; on close
                    // the pad clears. ONE instant resize, exactly like the
                    // native adjustResize beat — the v1.3.78 truce law (no
                    // animated second resize while the IME holds the
                    // InputConnection) is honored: applyContentInsets(false).
                    if (com.bolt.browser.util.Fullscreen.on(this)
                            && customView == null && flContent != null) {
                        if (kb) {
                            fsKbBottom = Math.max(0, flRoot.getHeight() - visible);
                            fsKbTop = statusBarInset();
                            applyContentInsets(false);
                        } else {
                            fsKbTop = 0; fsKbBottom = 0;
                            applyContentInsets(false);
                        }
                    }
                    // v1.3.78 — THE KEYBOARD TRUCE: the bars STAY while the
                    // keyboard is open. v1.3.33-77 hid them (and animated
                    // the content insets — a second, 15-frame resize of the
                    // WebView) exactly while the IME was opening, and the
                    // field read it as "the keyboard stops after entering
                    // one letter": two competing resizes of the view that
                    // holds the InputConnection is the classic IME restart
                    // trigger. Chrome keeps its toolbar over the keyboard;
                    // so does Bolt now. The close path is unchanged: when
                    // the keyboard leaves, scroll-hidden bars come back.
                    if (!kb && !barsShown && barsAutoHidden
                            && !com.bolt.browser.util.Fullscreen.on(this)) showBarsNow(true);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** last keyboard state seen by the layout listener (v1.3.43). */
    protected boolean kbWasOpen = false;

    /** v1.3.89 — the live fullscreen IME pad (see applyContentInsets).
     *  Zero when no keyboard is open (or not fullscreen) — the normal
     *  inset values apply untouched. */
    protected int fsKbTop = 0, fsKbBottom = 0;

    /** v1.3.89 — the status-bar strip height (the system's shade-gesture
     *  zone at the top edge). Internal resource lookup with a sane
     *  fallback; on cutout devices the resource includes the cutout. */
    protected int statusBarInset() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return Fx.dp(this, 24);
    }

    /** true when the bars were hidden by scroll/keyboard (not fullscreen) */
    protected boolean barsAutoHidden = false;

    protected void showBarsNow(boolean animate) {
        barsShown = true;
        barsAutoHidden = false;
        lastBarToggleAt = android.os.SystemClock.uptimeMillis();   // v1.3.76: toggle cooldown
        // v1.3.41: a fresh bar state starts a fresh scroll accumulation —
        // v1.3.33-40 let the counter survive show/hide, so scrolling UP
        // while bars were hidden (or a previous page's scroll) left a stale
        // NEGATIVE hole the next downward scroll had to dig out of before
        // the hide threshold could ever fire (the "scrolling doesn't hide
        // the toolbar" field report in fullscreen).
        scrollDeltaSinceBar = 0;
        try {
            // v1.3.39: in fullscreen browsing the SYSTEM bars follow ours —
            // shown while the chrome is visible, immersive when it hides.
            if (com.bolt.browser.util.Fullscreen.on(this) && customView == null)
                com.bolt.browser.util.Fullscreen.apply(this, true);
            View tg = findViewById(R.id.top_group);
            View bb = findViewById(R.id.bottombar);
            // v1.3.47 field ("hide and appear even faster"): the slide is
            // 120ms -> 70ms — a flick, not an animation.
            if (tg != null) {
                if (animate) tg.animate().translationY(0f).setDuration(70L).start();
                else tg.setTranslationY(0f);
            }
            if (bb != null) {
                if (animate) bb.animate().translationY(0f).setDuration(70L).start();
                else bb.setTranslationY(0f);
            }
            // v1.3.40: the download shelf rides with the bars
            View sh = findViewById(R.id.dl_shelf);
            if (sh != null) {
                if (animate) sh.animate().translationY(0f).setDuration(70L).start();
                else sh.setTranslationY(0f);
            }
            // v1.3.35: the blurred backdrops reappear with the bars
            if (blurTop != null && blurBottom != null) {
                if (animate) {
                    blurTop.animate().alpha(1f).setDuration(70L).start();
                    blurBottom.animate().alpha(1f).setDuration(70L).start();
                } else {
                    blurTop.setAlpha(1f);
                    blurBottom.setAlpha(1f);
                }
                ui.postDelayed(this::updateBlurSnapshots, 120);
            }
            // v1.3.43: the page gives the bars their space back — same beat
            // as the slide-in (the "contents don't readjust" field fix).
            // v1.3.81: ONE instant set (ghost kill — no per-frame resize);
            // the vacated strips show flContent's colorBg, the same family
            // the arriving bars paint, so the swap reads seamless.
            applyContentInsets(false);
        } catch (Throwable ignored) {}
    }

    protected void hideBarsNow(boolean animate) {
        if (customView != null) return;          // fullscreen video owns the screen
        if (findbar.getVisibility() == View.VISIBLE
                || ttsbar.getVisibility() == View.VISIBLE) return;
        barsShown = false;
        barsAutoHidden = true;
        lastBarToggleAt = android.os.SystemClock.uptimeMillis();   // v1.3.76: toggle cooldown
        scrollDeltaSinceBar = 0;                 // v1.3.41: fresh state, fresh accumulator
        try {
            // v1.3.39: sliding the chrome away also drops the system bars in
            // fullscreen mode (true edge-to-edge, notch included when the
            // notch toggle says so).
            if (com.bolt.browser.util.Fullscreen.on(this))
                com.bolt.browser.util.Fullscreen.apply(this, false);
            View tg = findViewById(R.id.top_group);
            View bb = findViewById(R.id.bottombar);
            // v1.3.36: each bar slides toward ITS own screen edge (top_group
            // owns the top edge normally, the bottom edge when docked).
            // v1.3.81: when ANIMATING, the translations are issued by the
            // delayed slide below (ghost-kill sequence — the bars hold
            // their covering position for one beat while the single inset
            // resize repaints underneath them); only the non-animated path
            // snaps them away right here.
            boolean flip = urlBarAtBottom();
            if (!animate) {
                if (tg != null) {
                    float off = tg.getHeight();
                    if (off == 0f) off = Fx.dp(this, 62);
                    tg.setTranslationY(flip ? off : -off);
                }
                if (bb != null) {
                    float off = bb.getHeight();
                    if (off == 0f) off = Fx.dp(this, 56);
                    bb.setTranslationY(flip ? -off : off);
                }
                View sh = findViewById(R.id.dl_shelf);
                if (sh != null && sh.getVisibility() == View.VISIBLE) {
                    float soff = sh.getHeight();
                    if (soff == 0f) soff = Fx.dp(this, 56);
                    sh.setTranslationY(soff);
                }
            }
            // v1.3.35: fade the blurred backdrops out with the bars
            if (blurTop != null && blurBottom != null) {
                if (animate) {
                    blurTop.animate().alpha(0f).setDuration(70L).start();
                    blurBottom.animate().alpha(0f).setDuration(70L).start();
                } else {
                    blurTop.setAlpha(0f);
                    blurBottom.setAlpha(0f);
                }
            }
            // v1.3.81 — GHOST-KILL SEQUENCE: the ONE instant inset change
            // (a single WebView resize) lands FIRST, while the bars still
            // cover their strips; the slide starts one beat later so
            // Chromium's first frame at the new size is already composited
            // before the bar leaves its strip exposed. The old order
            // (animated insets racing the slide) is what painted the
            // "black rectangle ghost" strip.
            applyContentInsets(false);
            if (animate) {
                ui.postDelayed(() -> {
                    try {
                        // state may have flipped back to shown inside the
                        // 50 ms beat — never slide a bar away that the user
                        // just got back (showBarsNow already zeroed it)
                        if (barsShown) return;
                        View tg2 = findViewById(R.id.top_group);
                        View bb2 = findViewById(R.id.bottombar);
                        boolean flip2 = urlBarAtBottom();
                        if (tg2 != null) {
                            float off = tg2.getHeight();
                            if (off == 0f) off = Fx.dp(this, 62);
                            tg2.animate().translationY(flip2 ? off : -off).setDuration(70L).start();
                        }
                        if (bb2 != null) {
                            float off = bb2.getHeight();
                            if (off == 0f) off = Fx.dp(this, 56);
                            bb2.animate().translationY(flip2 ? -off : off).setDuration(70L).start();
                        }
                        View sh2 = findViewById(R.id.dl_shelf);
                        if (sh2 != null && sh2.getVisibility() == View.VISIBLE) {
                            float soff = sh2.getHeight();
                            if (soff == 0f) soff = Fx.dp(this, 56);
                            sh2.animate().translationY(soff).setDuration(70L).start();
                        }
                    } catch (Throwable ignored) {}
                }, 50L);
            }
        } catch (Throwable ignored) {}
    }

    /** call whenever an overlay opens that anchors to the bars (omnibox,
     *  quick panel, find bar) so the bars are guaranteed visible */
    protected void ensureBarsShown() {
        if (!barsShown) showBarsNow(true);
    }

    /** scroll listener attached to every WebView as it is shown */
    protected final View.OnScrollChangeListener webScrollListener =
            (v, scrollX, scrollY, oldScrollX, oldScrollY) -> onContentScrolled(scrollY - oldScrollY, scrollY);

    /** v1.3.51 — the keyboard's LIVE state. The layout listener's kbWasOpen
     *  is an edge detector (it only updates on TRANSITIONS) — a restore path
     *  must ask the window itself, so a missed/dropped transition can never
     *  strand the bars behind a stale flag. */
    protected boolean keyboardOpen() {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            flRoot.getWindowVisibleDisplayFrame(r);
            return flRoot.getHeight() - r.height() > Fx.dp(this, 180);
        } catch (Throwable t) { return kbWasOpen; }
    }

    protected void onContentScrolled(int delta, int scrollY) {
        try {
            Tab t = activeTab();
            if (t == null || t.isHome) { ensureBarsShown(); return; }
            if (customView != null) return;
            // v1.3.76 — THE SETTLE GUARD: while an inset animation is still
            // settling (or a bar toggle just landed <350ms ago), scroll deltas
            // are resize echoes / anchoring corrections, not intent — swallow
            // them whole so the hide->resize->show->resize loop can't start.
            // The user's OWN scrolling in that window only delays the next
            // toggle by at most ~0.4s; the accumulator resets so no half-dug
            // hole survives to misfire later.
            long nowMs = android.os.SystemClock.uptimeMillis();
            if (nowMs < insetSettleUntil || nowMs - lastBarToggleAt < 350L) {
                scrollDeltaSinceBar = 0;
                return;
            }
            // v1.3.48 — the scroll-hide KILL SWITCH (Appearance →
            // Interaction "Hide bars when scrolling", default ON; field:
            // "some people might not" like the fast disappearing toolbars).
            // OFF: scrolling never hides the bars in EITHER mode. Bars a
            // previous scroll hid come back once — but NOT while the
            // keyboard is really up (v1.3.51: asked LIVE, not from the
            // transition flag — a stuck kbWasOpen used to strand the bars
            // until a resume) and NOT in fullscreen browsing (the hover
            // button owns showing there).
            if (!prefs.b("hideBarsScroll", true)) {
                if (!barsShown && barsAutoHidden && !keyboardOpen()
                        && !com.bolt.browser.util.Fullscreen.on(this)) {
                    showBarsNow(false);
                }
                return;
            }
            scrollDeltaSinceBar += delta;
            if (com.bolt.browser.util.Fullscreen.on(this)) {
                // v1.3.40 AGGRESSIVE: in fullscreen browsing ANY downward
                // scroll hides the chrome, and scrolling up never pops it
                // back — the hover button owns showing.
                // v1.3.43: 8dp -> 6dp. v1.3.47 field ("make the toolbars
                // hide and appear even faster when scrolling"): 6dp -> 3dp
                // — the bars leave at the FIRST flicker of intent.
                if (barsShown && scrollDeltaSinceBar > Fx.dp(this, 3)) {
                    scrollDeltaSinceBar = 0;
                    hideBarsNow(true);
                } else if (Math.abs(scrollDeltaSinceBar) > Fx.dp(this, 200)) {
                    scrollDeltaSinceBar = 0;
                }
            } else if (!barsShown && scrollDeltaSinceBar < -Fx.dp(this, 6)) {
                // v1.3.47: 12dp -> 6dp — the bars return at the first hint
                // of an upward flick (field: "appear even faster").
                // v1.3.51: the SHOW runs BEFORE the find/tts overlay gate —
                // those overlays ANCHOR to the bars, so showing is always
                // safe; the old shared early-return could strand the bars
                // behind an overlay flag that outlived its UI (the
                // "refuses to appear back" field report).
                scrollDeltaSinceBar = 0;
                showBarsNow(true);
            } else if (findbar.getVisibility() == View.VISIBLE
                    || ttsbar.getVisibility() == View.VISIBLE) {
                return;   // never HIDE while an anchored overlay is open
            } else if (barsShown && scrollDeltaSinceBar > Fx.dp(this, 4)
                    && scrollY > Fx.dp(this, 8)) {
                // v1.3.43: 12dp -> 8dp accumulate + 24dp -> 12dp depth.
                // v1.3.47 field: 8dp -> 4dp accumulate, 12dp -> 8dp depth —
                // hide the instant the finger means it; the depth gate now
                // only excludes the top sliver (rubber-band overscroll).
                scrollDeltaSinceBar = 0;
                hideBarsNow(true);
            } else if (Math.abs(scrollDeltaSinceBar) > Fx.dp(this, 200)) {
                scrollDeltaSinceBar = 0;   // long glide without crossing a threshold
            }
            // v1.3.35: keep the glass blur tracking the page (throttled inside)
            if (barsShown) updateBlurSnapshots();
        } catch (Throwable ignored) {}
    }

    protected void openTabsView() {
        Class<?> cls = incognito ? TabsIncognitoActivity.class : TabsActivity.class;
        startActivity(new Intent(this, cls));
    }

    protected void goBack() {
        Tab t = activeTab();
        if (t.web != null && t.web.canGoBack()) {
            // v1.3.84 — THE AUDITED HISTORY STEP (HistoryNav.kt): the
            // "back keeps me on the page but the URL bar moves" class of
            // silent no-ops gets audited ~1.1 s later and forced with a
            // plain loadUrl when the WebView never actually navigated
            com.bolt.browser.util.HistoryNav.step(this, t.web, -1);
        } else if (tm.tabs.size() > 1) {
            // v1.3.36: closing the last page of a tab from the toolbar now
            // offers UNDO, same as the switcher's close paths
            int was = tm.active;
            // v1.4.8 — the departure refresh + last-member capture
            try {
                if (t.groupId != 0 && tm.tabsOfGroup(t.groupId).size() == 1)
                    tm.captureGroupRowAnchors(t.groupId);
                tm.refreshAnchorsAround("t" + t.tid);
            } catch (Throwable ignored) {}
            tm.close(tm.active);
            showTab(Math.max(0, tm.active));
            showUndoSnack(was);
        } else if (mediaPlaying && prefs.bgPlay() && !siteBlockBg(t)) {
            moveTaskToBack(true);
        } else {
            confirmExit();
        }
    }

    /** v1.3.36 — the browser-side undo snackbar (toolbar-initiated closes).
     *  v1.4.6: every undo in the app shares the one 3.5 s window. */
    protected void showUndoSnack(int wasIdx) {
        try {
            if (!tm.undoAvailable()) return;
            String name = tm.lastClosed.isHome ? getString(R.string.home)
                    : (tm.lastClosed.title == null || tm.lastClosed.title.isEmpty()
                    ? tm.lastClosed.url : tm.lastClosed.title);
            com.bolt.browser.util.Ui.snackbar(this,
                    getString(R.string.tab_closed) + " · " + name,
                    getString(R.string.undo), () -> {
                        Tab back = tm.reopenLast();
                        if (back != null) showTab(tm.tabs.indexOf(back));
                    }, com.bolt.browser.util.Ui.UNDO_MS, null);
        } catch (Throwable ignored) {}
    }

    protected void goHome() {
        Tab t = activeTab();
        t.capture();
        t.isHome = true;
        t.url = "";
        showTab(tm.active);
    }

    /**
     * v1.4.14 — THE ONE URL-vs-QUERY RESOLVER, extracted from loadInTab so
     * the external search doors (openQueryInNewTab — system text selection,
     * share sheet, WEB_SEARCH) share the omnibox's exact resolution: scheme
     * passthrough, bare-host heuristics, custom engine bangs, the selected
     * engine's search URL, and the tracker cleaner. One resolution, many
     * doors — a search from outside must resolve EXACTLY like one typed.
     */
    protected String resolveInput(String q) {
        String url;
        if (q.startsWith("http://") || q.startsWith("https://") || q.contains("://")
                || q.startsWith("about:") || q.startsWith("file://")) {
            url = q;
        } else if (q.startsWith("www.") || q.matches("(?i)[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?")
                || q.matches("(?i)[a-z0-9-]+\\.[a-z]{2,}.*")) {
            url = "https://" + q;
        } else {
            // v1.3.39 — CUSTOM ENGINE BANGS (menu-3 #6): "!w cats" routes
            // through the engine that owns the bang (keyboard-GO path; the
            // suggestion row handles its own clicks).
            String bangUrl = com.bolt.browser.util.Engines.resolveBang(this, q);
            if (bangUrl != null) {
                url = bangUrl;
            } else {
            String[] engines = getResources().getStringArray(R.array.search_engine_urls);
            int ei = prefs.searchEngine();
            String fmt = engines[Math.max(0, Math.min(engines.length - 1, ei))];
            url = fmt.replace("%s", URLEncoder.encode(q));
            }
        }
        // v1.3.40 — TRACKER CLEANER (round-4 #4) on typed/pasted opens:
        // strip utm_*/fbclid/gclid/… before the load. Per-site off via the
        // page-menu row; global toggle in Privacy.
        if (url.startsWith("http")) {
            try {
                if (com.bolt.browser.util.UrlClean.enabled(this, url)) {
                    String cleaned = com.bolt.browser.util.UrlClean.clean(url);
                    if (!cleaned.equals(url)) {
                        com.bolt.browser.util.UrlClean.toastStripped(this,
                                com.bolt.browser.util.UrlClean.count(url));
                        url = cleaned;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return url;
    }

    protected void loadInTab(String input) {
        if (input == null || input.trim().isEmpty()) return;
        String url = resolveInput(input.trim());
        Tab t = activeTab();
        // GHOSTING FIX (same-tab path): a tab sitting on the home screen KEEPS
        // its WebView — with the last painted frame of the page the user left
        // ("opened youtube, went home, opened facebook -> youtube visible while
        // facebook loads"). Reusing that WebView for the next URL shows the stale
        // frame until the new page commits. The v1.3.9 veil only covered CROSS-tab
        // switches. Leaving home = leaving that page for good: drop the stale
        // WebView and build a fresh one (blank until the new page paints).
        boolean fromHome = t.isHome;
        if (fromHome && t.web != null) {
            try {
                if (t.web.getParent() instanceof android.view.ViewGroup)
                    ((android.view.ViewGroup) t.web.getParent()).removeView(t.web);
                t.web.destroy();
            } catch (Throwable ignored) {}
            t.web = null;
            t.snap = null;
        }
        t.isHome = false;
        t.url = url;
        String host = Fx.hostOf(url);
        t.applyUa(this, t.web);
        if (t.web == null) {
            showTab(tm.active); // builds web and loads
            t = activeTab();
            if (t.url == null || !t.url.equals(url)) {
                t.url = url;
                t.isHome = false;
            }
        } else {
            boolean noJs = prefs.siteBool(Fx.baseDomain(host), "noJs", false);
            t.web.getSettings().setJavaScriptEnabled(!noJs);
            t.web.loadUrl(url);
            showTab(tm.active);
        }
        hideKeyboard();
    }

    protected void openNewTab(String url) {
        // v1.4.14 — THE PROCESS LAW (the incognito intent leak fix): every
        // internal door that opens a NEW tab from an existing one (this
        // method, openUrlNewTab, loadInTab, the quick actions) creates it
        // in THIS process's TabManager directly — tm is per-process, so an
        // IncognitoActivity caller gets an incognito tab by construction
        // and can never spawn into the normal browser (or vice versa).
        // Nothing here fires an intent to BrowserActivity.class; the only
        // cross-process hop that exists is TabsActivity's explicit
        // "switch to normal" chip (a user-visible process switch, not a
        // tab creation).
        // v1.3.64 — a new tab DRASTICALLY changes the viewing area; the menu
        // that launched it must close so the user SEES the tap's answer (the
        // fresh tab becoming the view), instead of the sheet parked on top of
        // a view that already changed behind it (the v1.3.63 site-settings
        // report: "options that change the website view drastically like
        // that, I would like the menus to close automatically"). Idempotent
        // for the callers that already dismissed their own overlays.
        dismissAllOverlays();
        Tab t = new Tab();
        if (url != null && url.startsWith("http")) {
            t.isHome = false;
            t.url = url;
        }
        tm.addTab(t);
        showTab(tm.tabs.size() - 1);
    }

    /**
     * v1.4.14 — EXTERNALLY-ORIGINATED SEARCHES OPEN IN THEIR OWN TAB. The
     * v1.4.12 entry funneled PROCESS_TEXT / WEB_SEARCH / SEND queries into
     * loadInTab(), which loads into the ACTIVE tab — a "Search in Bolt"
     * from another app overwrote whatever page the user was reading. The
     * side door now behaves like Chrome's: the query resolves through the
     * SAME resolver the omnibox uses (resolveInput — engine, bangs, tracker
     * cleaner) and opens in a NEW tab; the user's current tab is untouched.
     * Runs inline (not posted) exactly like the VIEW branch below: unlike
     * loadInTab it has no dependency on an existing active tab — it builds
     * its own — and onCreate's empty-tab check then sees a tab and adds no
     * spare home tab (a cold-start search lands as ONE tab, the same shape
     * a cold-start VIEW link produces).
     */
    protected void openQueryInNewTab(String input) {
        if (input == null || input.trim().isEmpty()) return;
        String url = resolveInput(input.trim());
        try { dismissAllOverlays(); } catch (Throwable ignored) {}
        Tab t = new Tab();
        t.isHome = false;
        t.url = url;
        tm.addTab(t);
        showTab(tm.tabs.size() - 1);
        hideKeyboard();
    }

    protected void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(flRoot.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ web clients

    protected class BoltClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url == null) return false;
            if (url.startsWith("bolt:")) {
                goHome();
                return true;
            }
            // v1.3.31 — authenticator onboarding: sites (and setup QRs) expose
            // otpauth://totp/... links; tapping one in Bolt offers a one-tap
            // add to the built-in authenticator instead of a dead end.
            // Incognito: identity data is main-process-only — open the main
            // process's add flow? No: simply decline (return true) so nothing
            // half-works there.
            if (url.startsWith("otpauth://") && !incognito) {
                final com.bolt.browser.util.Totp.OtpSecret o =
                        com.bolt.browser.util.Totp.parseOtpAuth(url);
                if (o != null) {
                    final String name = com.bolt.browser.util.Totp.displayName(o.issuer, o.account);
                    new AlertDialog.Builder(BrowserActivity.this)
                            .setMessage(getString(R.string.auth_add_q, name))
                            .setPositiveButton(R.string.yes, (d, w) -> {
                                org.json.JSONObject saved = prefs.addAuth(o);
                                if (saved.length() > 0) {
                                    Fx.toast(BrowserActivity.this,
                                            getString(R.string.auth_added));
                                }
                            })
                            .setNegativeButton(R.string.no, null)
                            .show();
                } else {
                    Fx.toast(BrowserActivity.this, getString(R.string.auth_bad_secret));
                }
                return true;
            } else if (url.startsWith("otpauth://")) {
                return true;   // incognito: no identity data by design
            }
            // userscript install: Greasy Fork / OpenUserJS links (.user.js)
            if (url.startsWith("http") && url.endsWith(".user.js")) {
                installUserscript(url);
                return true;
            }
            String host = Fx.hostOf(url);
            String curHost = Fx.hostOf(activeTab().url != null ? activeTab().url : "");

            // adult block (main frame navigations)
            if (prefs.blockAdult() && FilterEngine.get().isAdultHost(host)) {
                Fx.toast(BrowserActivity.this, "Blocked (adult filter)");
                return true;
            }

            // non-web schemes -> external apps
            if (!url.startsWith("http:") && !url.startsWith("https:")) {
                String base = Fx.baseDomain(curHost);
                boolean noExt = prefs.siteBool(base, "noExt", false);
                if (noExt) {
                    Fx.toast(BrowserActivity.this, "External app blocked");
                    return true;
                }
                try {
                    if (url.startsWith("intent://")) {
                        Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        i.addCategory(Intent.CATEGORY_BROWSABLE);
                        i.setComponent(null);
                        try {
                            startActivity(i);
                        } catch (Throwable noApp) {
                            // v1.4.19 — THE INTENT FALLBACK (root-caused from
                            // the captured URL: an intent:// Facebook link with
                            // S.browser_fallback_url=… whose web fallback was
                            // NEVER loaded — no app installed meant "No app
                            // can open this" AND a dead end). The Android
                            // intent-URI contract says the fallback URL is the
                            // page to open when no app services the intent —
                            // Chrome's behavior, and the site's own honest
                            // answer. Extract it (pure, probe-pinned) and LOAD
                            // it in THIS tab instead of toasting.
                            String fb = com.bolt.browser.util.IntentFallback
                                    .fallbackUrl(url);
                            if (fb != null) {
                                try {
                                    com.bolt.browser.util.Diag.log(BrowserActivity.this,
                                            "intent fallback -> " + fb);
                                } catch (Throwable ignored) {}
                                view.loadUrl(fb);
                            } else {
                                Fx.toast(BrowserActivity.this, "No app can open this");
                            }
                        }
                    } else {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    }
                } catch (Throwable e) {
                    Fx.toast(BrowserActivity.this, "No app can open this");
                }
                return true;
            }

            // block 3rd-party navigation per-site
            if (!curHost.isEmpty() && !host.isEmpty()
                    && prefs.siteBool(Fx.baseDomain(curHost), "block3rd", false)
                    && !Fx.baseDomain(host).equals(Fx.baseDomain(curHost))) {
                Fx.toast(BrowserActivity.this, "Blocked 3rd-party site: " + host);
                return true;
            }

            // v1.3.40 — TRACKER CLEANER on link clicks / JS navigations
            // (round-4 #4). The cleaned URL re-enters this callback once
            // and passes through clean — UrlClean is idempotent, so this
            // can never loop a navigation.
            if (com.bolt.browser.util.UrlClean.enabled(BrowserActivity.this, url)) {
                String cleaned = com.bolt.browser.util.UrlClean.clean(url);
                if (!cleaned.equals(url)) {
                    com.bolt.browser.util.UrlClean.toastStripped(BrowserActivity.this,
                            com.bolt.browser.util.UrlClean.count(url));
                    view.loadUrl(cleaned);
                    return true;
                }
            }

            // apply per-site settings before load — on the tab that OWNS this
            // WebView. v1.3.25: this used activeTab() unconditionally, so any
            // navigation inside an attached-but-INVISIBLE media tab (redirects,
            // SPA route changes) wrote ITS url onto the ACTIVE tab (omnibox
            // showed the wrong address) and applied the wrong site's UA prefs
            // to that WebView.
            Tab navTab = tabOf(view);
            if (navTab == null) navTab = activeTab();
            String bd = Fx.baseDomain(host);
            navTab.url = url;
            try {
                boolean noJs = prefs.siteBool(bd, "noJs", false);
                view.getSettings().setJavaScriptEnabled(!noJs);
                navTab.applyUa(BrowserActivity.this, view);
                // v1.3.40 — per-site zoom rides with the UA on every
                // main-frame navigation (textZoom applies live)
                navTab.applyZoom(BrowserActivity.this, view);
            } catch (Throwable ignored) {}
            return false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                Uri u = request.getUrl();
                String url = u.toString();
                if (!url.startsWith("http")) return null;
                // v1.4.20 — the v1.4.19 visual-search intercept door is
                // RETIRED with the Bing SBI upload path it served (see
                // VisualSearch's header): every engine rides Google's
                // 302-shaped search-by-image route now, and a 3xx needs no
                // body carrier. No armed map, no serve, nothing to consume.
                // v1.3.84 — THE BODY CARRIERS ARE OFF-LIMITS (the zai upload
                // 401 field report: "uploading files through zai gave 401
                // unauthorised"). zai's chat upload is a cross-origin
                // fetch POST to api.z.ai/api/v1/…/upload whose Authorization
                // Bearer header the page attaches CONDITIONALLY — and both
                // interception surfaces below judge a request ONLY by its
                // URL (extension typing) and method-less assumptions, so a
                // POST/PUT whose presigned/object URL ends in .png is
                // "an image" to them. A stubbed or emptied request anywhere
                // in the upload's auth chain sends the real POST out with NO
                // bearer token -> the server answers 401. THE LAW: a request
                // that carries a body (any method beyond GET/HEAD — uploads,
                // presigned PUTs, CORS preflights, API mutations) is a user
                // action, never a cacheable ad/tracker subresource — the
                // data saver must never stub it and the adblock network pass
                // must never block it. False positives on POSTs cost exactly
                // this bug; ads and trackers overwhelmingly ride GETs.
                String method = null;
                try { method = request.getMethod(); } catch (Throwable ignored) {}
                boolean carriesBody = method != null
                        && !"GET".equals(method) && !"HEAD".equals(method);
                // v1.3.39 DATA SAVER (menu-3 #10, default off): images get a
                // 1×1 PNG while the connection is metered (or forced) — the
                // blocked counter feeds the toggle's saved-traffic chip.
                if (!carriesBody
                        && !request.isForMainFrame()
                        && com.bolt.browser.util.DataSaver.active(BrowserActivity.this)
                        && com.bolt.browser.util.DataSaver.looksLikeImage(url,
                                request.getRequestHeaders() != null
                                        ? request.getRequestHeaders().get("Accept") : null)) {
                    com.bolt.browser.util.DataSaver.noteBlocked(BrowserActivity.this);
                    return com.bolt.browser.util.DataSaver.emptyImage();
                }
                if (carriesBody) return null;   // the body-carrier law, enforced
                String pageHost = Fx.hostOf(view.getUrl() != null ? view.getUrl() : activeTab().url);
                String reqHost = u.getHost();
                if (reqHost == null) return null;

                // adult block for subresources too
                if (prefs.blockAdult() && FilterEngine.get().isAdultHost(reqHost)) {
                    return empty();
                }

                String base = Fx.baseDomain(pageHost);
                if (prefs.siteAdblock(base) == 1) { /* site forces blocking */ }
                else if (!prefs.adblockEnabled()) return null;
                else if (prefs.siteBool(base, "noAds", false)) return null; // allowlisted
                else if (prefs.siteAdblock(base) == 0) return null;         // site disabled

                FilterEngine.Type type = inferType(request);
                if (type == FilterEngine.Type.DOCUMENT) return null;
                if (type == FilterEngine.Type.MEDIA) {
                    // be conservative: only block media when rule targets media explicitly
                }
                List<String> siteRules = prefs.siteFilters(base);
                if (FilterEngine.get().blocked(url, pageHost, type, siteRules)) {
                    return empty();
                }
            } catch (Throwable ignored) {}
            return null;
        }

        private WebResourceResponse empty() {
            return new WebResourceResponse("text/plain", "utf-8",
                    new ByteArrayInputStream(new byte[0]));
        }

        /** v1.3.81 — THE BACK/FORWARD SILENT NO-OP KILL (the field report:
         *  "browser's back and forward don't load the previous and forward
         *  website, just the links appear but the same page remains").
         *  WebViewClient's DEFAULT onFormResubmission implementation is
         *  EMPTY, and Chromium WAITS for the app to send one of the two
         *  messages before the navigation may proceed — so every
         *  back/forward that lands on the result of a POST form (search
         *  submissions, logins, checkouts — extremely common) hung forever:
         *  the history index moved (URL bar + button states update) but the
         *  old document stayed on screen. Chrome-mobile behavior: resubmit
         *  silently, the page loads. */
        @Override
        public void onFormResubmission(WebView view, android.os.Message dontResend,
                android.os.Message resend) {
            try { resend.sendToTarget(); } catch (Throwable ignored) {}
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            // v1.3.92 — the AI-export picker dies with the document it was
            // reading (the new document has no markers/state; the native
            // chrome would float over an unrelated page)
            try { if (aiWeb == view) endAiPick(false); } catch (Throwable ignored) {}
            // v1.3.84 — a navigation that is neither our pending step's
            // target nor its departure URL retires the step's audit (the
            // user moved on; a forced loadUrl must never yank their page)
            com.bolt.browser.util.HistoryNav.noteNav(view, url);
            // v1.3.78 — pin the icon host at the moment the page STARTS: the
            // page that starts owns every onReceivedIcon that later lands on
            // this view (reading getUrl() at icon time attributed the OLD
            // page's bitmap to the NEW page's host — the wrong-icon report)
            try {
                com.bolt.browser.util.FaviconStore.pinViewHost(view, url);
            } catch (Throwable ignored) {}
            // v1.3.23: passkey compat fallback — only fires when document-start
            // registration was unavailable (old WebView); the script is
            // idempotent so double injection is harmless.
            PasskeyCompat.injectNow(view);
            // leaving the page while a fullscreen video is up must restore the bars
            exitFullscreenVideo();
            // v1.3.61 — the new page starts with a clean pseudo state too
            if (pseudoFullscreen) pseudoFullscreen = false;
            // v1.3.62 — and a clean rescue state (a new document has no
            // __boltfsf class; the old one's WebView may carry it)
            try { fsRemoveCssRescue(); } catch (Throwable ignored) {}
            // reset per-page script menu commands + stale storage caches
            if (tabOf(view) == tm.current()) scriptMenuCmds.clear();
            try {
                view.evaluateJavascript("window.__boltMenuCmds=undefined;" +
                        "window.__boltStorageEvent=undefined;", null);
            } catch (Throwable ignored) {}
            Tab t = tabOf(view);
            if (t != null) {
                // v1.4.19 — THE data: GUARD (the offline-error corpse's
                // mechanism): a data: URL is never a tab's address — it is a
                // loadData artifact (the render-crash notice, any legacy
                // error page). Overwriting t.url with it would persist the
                // ephemeral page over the tab's real address.
                if (!(url != null && url.startsWith("data:")
                        && t.url != null && t.url.startsWith("http"))) {
                    t.url = url;
                }
                // v1.4.19 — a new navigation retires the offline error
                // overlay (and its per-tab marker)
                t.errUrl = null;
                hideErrorOverlay();
                t.isHome = false;
                // v1.4.17 — THE DARK CANVAS FOLLOWS THE NEW DOMAIN: the
                // site-dark decision is per-base-domain, and the navigation
                // may be crossing from a dark-engine site to a light one
                // (or back) — applyWebDark re-reads the (just-assigned) url
                // and repaints the unpainted canvas before the first paint
                // of the new document (the white-flash fix's canvas leg).
                try { t.applyWebDark(BrowserActivity.this, view); } catch (Throwable ignored) {}
                // v1.3.98 — THE UA HEAL, START HALF (see Tab.applyUaFor): this
                // is the ONLY hook that sees BACK/FORWARD navigations
                // (shouldOverrideUrlLoading fires for neither) — the path
                // where a desktop-forced tab came back from an external
                // link with the mobile UA still live and re-rendered mobile
                // ("falls back to mobile view when the link changes").
                // Write-only-if-different: no-op on every ordinary load; a
                // genuine change rides Chromium's reload-the-in-flight-load
                // (crbug 40340203, fixed) so the back page lands DESKTOP.
                try {
                    t.applyUaFor(BrowserActivity.this, view, url, "start");
                } catch (Throwable ignored) {}
                // v1.3.18: persist at navigation START (debounced) — a process
                // death mid-load must not revert the tab to a lost home tab.
                tm.scheduleSave();
                if (t == activeTab()) updateBars();
            }
            vProgress.getLayoutParams().width = Fx.dp(BrowserActivity.this, 20);
            vProgress.requestLayout();
            vProgress.setVisibility(View.VISIBLE);
            // core injection (bridge + media hook + userscripts); onPageStarted
            // can land in the outgoing document, so re-inject at commit too
            String core = MediaHook.core(prefs);
            view.evaluateJavascript(core, null);
            // v1.3.58 — frame hooks (supersedes v1.3.57's ClipboardWrite):
            // the blob:null field log proved Gemini mints its download blobs
            // inside an OPAQUE-ORIGIN (sandboxed) iframe — no main-frame
            // fetch/<img>/DOM hunt can EVER reach those bytes, and a
            // main-frame-only clipboard shim misses any button living in a
            // frame. FrameHooks is registered as a DOCUMENT-START script in
            // EVERY frame (Tab.buildWeb → FrameHooks.arm, origin rule "*" —
            // opaque origins included, verified in Chromium source); it
            // wraps URL.createObjectURL to hold the Blob OBJECT in the
            // minting frame and relays needBlob/copyText across frames via
            // postMessage (the one channel that crosses opaque origins).
            // This evaluateJavascript is the belt-and-braces main-frame
            // re-arm for the same moments as the media core — ALWAYS,
            // incognito included.
            com.bolt.browser.util.FrameHooks.injectNow(view);
            // v1.3.72 — shadow gate re-arm (see Tab.buildWeb): the
            // attachShadow wrap must be in place before page scripts mint
            // roots; onPageStarted can land in the outgoing document, so
            // re-inject at commit too (the __boltShrArmed guard makes
            // re-runs no-ops)
            com.bolt.browser.util.ShadowGate.injectNow(view);
            if (!incognito) injectUserscripts(view, url, "start");
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            // v1.3.98 — THE UA HEAL, COMMIT HALF: onPageStarted carries the
            // INITIAL url of a redirect chain, so a redirect INTO a
            // desktop-flagged site still committed a mobile-UA document (the
            // start half saw the shortlink's domain). At commit the FINAL url
            // is known: if the UA genuinely changed here, the committed page
            // was loaded with the wrong one — reload ONCE with the right UA
            // (the reload's own start/commit find everything matching and
            // write nothing, so this terminates and never loops).
            try {
                Tab ct = tabOf(view);
                if (ct != null && ct.applyUaFor(BrowserActivity.this, view, url, "commit")) {
                    view.reload();
                }
            } catch (Throwable ignored) {}
            // the new document definitely exists now — re-run the core + any
            // document-start userscripts that missed the early window
            view.evaluateJavascript(MediaHook.core(prefs), null);
            // v1.3.58 — frame hooks re-arm (see onPageStarted)
            com.bolt.browser.util.FrameHooks.injectNow(view);
            // v1.3.72 — shadow gate re-arm (see onPageStarted)
            com.bolt.browser.util.ShadowGate.injectNow(view);
            // v1.3.80 — ADBLOCK COSMETIC AT FIRST PAINT: the cosmetic pass
            // used to run ONLY at onPageFinished, so ads painted fully and
            // were hidden a beat later (the classic ad-flash). Commit-visible
            // is the earliest moment the new document reliably exists;
            // cssInjectOnce replaces the same style element at finish, and
            // the procedural pass carries its own __boltProc guard.
            injectAdblockCosmetic(view, url);
            // v1.4.17 — THE WHITE-FLASH KILL: the dark decision chain
            // (probe -> ask -> invert) runs HERE, at the earliest moment
            // the new document exists with its render-blocking CSS parsed,
            // instead of waiting for onPageFinished — a light site under
            // the dark-web engine used to paint white from this moment
            // until the last subresource landed (seconds on a slow page).
            // The chain body + the run-token guard law: runWebDarkPass.
            runWebDarkPass(view, url);
            if (!incognito) injectUserscripts(view, url, "start");
        }

        /** v1.3.27 — SPA navigation tracking (THE "back to YouTube home" bug).
         *  onPageStarted / onPageFinished / shouldOverrideUrlLoading NEVER
         *  fire for in-app route changes (history.pushState/replaceState) —
         *  and YouTube's home → watch transition is pure SPA, so t.url stayed
         *  "youtube.com/" for the whole session. Every recovery path then
         *  loaded the wrong page: a killed process restored the tab to the
         *  HOME page ("video gone, sitting on YouTube home with no video
         *  selected"), a dead renderer rebuilt to home, and even the omnibox
         *  showed the stale address. doUpdateVisitedHistory is the WebView
         *  callback that DOES fire for those navigations (it also re-fires on
         *  ordinary loads — hence the no-change dedup below). */
        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            super.doUpdateVisitedHistory(view, url, isReload);
            // v1.3.84 — the same-document twin of the onPageStarted retire
            // (pushState navigations never fire onPageStarted)
            com.bolt.browser.util.HistoryNav.noteNav(view, url);
            if (url == null) return;
            Tab t = tabOf(view);
            if (t == null) return;
            if (url.equals(t.url)) return;
            // v1.4.19 — THE data: GUARD (see onPageStarted): a data: URL is
            // a loadData artifact, never the tab's navigable/persisted
            // address — the offline-error corpse's exact corruption path
            // (doUpdateVisitedHistory fired with data:text/html,… AFTER the
            // old loadData error page, wrote it into t.url, and tabs.json
            // kept it forever).
            if (url.startsWith("data:") && t.url != null && t.url.startsWith("http")) {
                return;
            }
            t.url = url;
            t.isHome = false;
            // v1.3.88 — THE DOWNLOAD-COMMIT HOOK: an armed dlUrl arriving
            // here is the blank download page LANDING (the field case the
            // v1.3.87 synchronous check missed — the commit happens after
            // onDownloadStart). Step back at once, whatever the network
            // speed, instead of waiting for the blind 400/1500 ms checks;
            // the resulting goBack re-fires this callback with the real
            // page and heals t.url.
            if (t.dlUrl != null && url.equals(t.dlUrl)
                    && t.dlFromUrl != null && !t.dlFromUrl.isEmpty()
                    && !url.equals(t.dlFromUrl)) {
                final Tab dt = t;
                final String dpre = t.dlFromUrl;
                ui.postDelayed(() -> dlStepBack(dt, dpre), 250);
            }
            // persist the route change (debounced) — a kill mid-video now
            // restores the WATCH page, not the page the SPA started from
            tm.scheduleSave();
            if (t == activeTab()) updateBars();
        }

        /** v1.3.80 — the adblock cosmetic decision, mirroring the NETWORK
         *  interceptor's logic: LIST selectors only run when the adblock is
         *  on (globally, forced per-site, not allowlisted). The old
         *  finish-only pass injected LIST selectors even with the adblock
         *  OFF — only the user's own rules should survive that. */
        protected boolean adblockListsOn(String base) {
            int siteMode = prefs.siteAdblock(base);
            return siteMode == 1
                    || (prefs.adblockEnabled()
                        && !prefs.siteBool(base, "noAds", false)
                        && siteMode != 0);
        }

        /** the CSS payload for a host: list selectors (when the adblock is
         *  on) + the user's own per-site rules + zaps. */
        protected String adblockCssFor(String host, String base) {
            return FilterEngine.get().cssFor(host,
                    prefs.siteFilters(base), prefs.siteZaps(base),
                    adblockListsOn(base));
        }

        /** the procedural :has-text() payload for a host (same rule). */
        protected String adblockProcFor(String host, String base) {
            return FilterEngine.get().proceduralJs(host,
                    prefs.siteFilters(base), adblockListsOn(base));
        }

        /** v1.3.80 — the early cosmetic pass (see onPageCommitVisible). */
        protected void injectAdblockCosmetic(WebView view, String url) {
            try {
                if (url == null || !url.startsWith("http")) return;
                String host = Fx.hostOf(url);
                String base = Fx.baseDomain(host);
                String css = adblockCssFor(host, base);
                if (!css.isEmpty())
                    view.evaluateJavascript(MediaHook.cssInjectOnce("bolt-adcss", css), null);
                String proc = adblockProcFor(host, base);
                if (!proc.isEmpty()) view.evaluateJavascript(proc, null);
            } catch (Throwable ignored) {}
        }

        /** inject userscripts for the given phase: "start" (document-start),
         *  "end" (DOMContentLoaded-ish) or "idle" (after the page settled). */
        protected void injectUserscripts(final WebView view, final String url, final String phase) {
            if (url == null || !url.startsWith("http")) return;
            new Thread(() -> {
                try {
                    List<UserscriptEngine.Script> list =
                            UserscriptEngine.forUrl(getApplicationContext(), url, phase);
                    for (UserscriptEngine.Script s : list) {
                        final String js = UserscriptEngine.wrap(s);
                        ui.post(() -> {
                            try { view.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                        });
                    }
                } catch (Throwable ignored) {}
            }, "usinj").start();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            vProgress.setVisibility(View.GONE);
            // v1.3.35: pull-to-refresh spinner retires with the page load
            if (flContent instanceof MediaAwareContent)
                ((MediaAwareContent) flContent).setRefreshing(false);
            // v1.3.35: refresh the glass-blur backdrops for the new content
            ui.postDelayed(BrowserActivity.this::updateBlurSnapshots, 120);
            // v1.3.79 — THE UNIVERSAL FAVICON PASS: once per host per session,
            // ask the PAGE to rasterize its own <link rel=icon> (the browser's
            // SVG renderer draws SVG favicons to a canvas — BitmapFactory
            // cannot, onReceivedIcon often never fires for them, and DDG/root
            // ico have no SVG support: that was the z.ai logo.svg miss). The
            // page fetches the icon bytes (CORS-permitting), draws them into a
            // 96px canvas and hands Bolt a PNG data URL. Skip when we already
            // hold an icon for the host or already tried this session.
            try {
                if (!incognito) {
                    String host = Fx.hostOf(url == null ? "" : url);
                    if (com.bolt.browser.util.FaviconStore.domPending(host)) {
                        view.evaluateJavascript(FAV_EXTRACT_JS, null);
                    }
                }
            } catch (Throwable ignored) {}
            // v1.3.60: a load that finished while the app has no customView
            // must not carry a PHANTOM renderer-side fullscreen into the
            // fresh document (see healRendererFullscreen) — cheap probe,
            // no-op on healthy pages.
            try { healRendererFullscreen(view, "pageFinished"); } catch (Throwable ignored) {}
            Tab t = tabOf(view);
            if (t != null) {
                t.url = url;
                if (view.getTitle() != null) t.title = view.getTitle();
                if (t == activeTab()) updateBars();
                tm.scheduleSave();   // v1.3.79: debounced — the direct save serialized every tab on the MAIN thread per title change
                // v1.3.38 TAB SLEEP: a woken tab reloads its URL — hand the
                // reading position back once the page is in.
                try { com.bolt.browser.util.TabSleep.restoreScroll(t, view); }
                catch (Throwable ignored) {}
                // v1.3.71 — a sticky page-translation session survives the
                // navigation: the engine's JS context died with the old
                // document (the v1.3.70 chat.z.ai "nodes=0" was exactly
                // that race — a restored page still blank at tap time).
                try { com.bolt.browser.util.PageTranslate.onPageLoaded(view); }
                catch (Throwable ignored) {}
                // v1.3.40 — TAB SEARCH BY PAGE CONTENT (round-4 #7):
                // remember a slice of the visible text so the switcher's
                // search finds tabs by what is ON the page, not just
                // title/url. Never in incognito; never persisted.
                if (!incognito && !t.isHome && t.web != null) {
                    try {
                        view.evaluateJavascript(
                                "(function(){try{return JSON.stringify((document.body&&document.body.innerText||'').slice(0,4000))}catch(e){return '\"\"'}})()",
                                v -> {
                                    try {
                                        Object o = new org.json.JSONTokener(v).nextValue();
                                        if (o instanceof String) t.pageText = (String) o;
                                    } catch (Throwable ignored) {}
                                });
                    } catch (Throwable ignored) {}
                }
                // v1.3.40: in fullscreen browsing a finished page hides the
                // chrome right away (bars cover content otherwise)
                if (com.bolt.browser.util.Fullscreen.on(BrowserActivity.this) && customView == null)
                    hideBarsNow(false);
            }
            // cosmetic ad hiding + zaps + dark fallback
            // v1.3.80: the shared injectAdblockCosmetic() pass (also run at
            // commit-visible — see the ad-flash note there) + the per-site
            // dark fallback appended into the SAME bolt-adcss style element
            // (cssInjectOnce replaces, never stacks).
            try {
                String host = Fx.hostOf(url);
                String base = Fx.baseDomain(host);
                String css = adblockCssFor(host, base);
                // v1.3.39 PER-SITE DARK (menu-3 #5): the page-menu chip stores
                // a per-site override on top of the global dark-web toggle.
                // v1.3.40: algorithmic darkening (API 33+) is a NO-OP while
                // the app theme is LIGHT — it only follows the app's own
                // dark mode, which was the "per site dark mode still depends
                // on theme dark or light mode" field bug. Sites that want
                // dark now get the CSS inversion fallback on 33+ light mode
                // (and <29 as before); 29–32 keeps FORCE_DARK, which is
                // theme-independent.
                // v1.3.81 — THE FORCE-DARK TRUTH: setForceDark is DEAD for
                // apps targeting API 33+ (Bolt targets 34 — the platform
                // ignores the call outright), so 29–32 was a silent no-op
                // hole in the old condition; and on 33+ the algorithmic
                // darkener only fires when the WebView's theme is genuinely
                // dark, which needs the new android:isLightTheme=false on
                // the dark skins (styles.xml). The CSS inversion fallback
                // now covers EVERY case the native path cannot:
                //   <33 any mode        -> CSS (setForceDark is dead)
                //   33+ app dark        -> native algorithmic darkening
                //   33+ app light       -> CSS
                // v1.3.87 — THE ALREADY-DARK GATE + THE MEDIA EXCLUSIONS
                // (the field report: "the dark site is not properly
                // detecting if the website is already dark and ends up
                // inversing on the dark website making it white. Also it
                // inverts images"). The dark CSS moved to its OWN
                // bolt-darkcss style (adcss stays adblock-only) and is
                // injected only after the in-page luminance probe says the
                // page is NOT already dark — an already-dark site keeps its
                // own colors AND any stale inversion from an earlier page
                // state is removed. When we DO invert, the background-image
                // scan pins .__boltNI on every element whose computed
                // background-image carries a url() so class-set photos stop
                // coming out negative.
                view.evaluateJavascript(MediaHook.cssInjectOnce("bolt-adcss", css), null);
                // v1.3.91 — ONE ENGINE: the CSS inversion + the smart probe
                // now run on EVERY API level and BOTH app themes (the old
                // carve-out handed 33+ app-dark pages to Chromium's
                // algorithmic darkener — which inverts images/designs and
                // DOUBLE-DARKENS sites that ship their own dark mode: "the
                // dark mode still sucks, does not properly detect sites
                // with good dark modes already and inverts it"). The probe
                // gates the inversion: an already-dark page keeps its own
                // colors and any stale inversion from an earlier state is
                // removed. When we DO invert, the smart pass
                // (MediaHook.darkSmartJs) pins .__boltNI on every
                // url()-carrying element (class-set photos stay positive)
                // AND runs the PARITY WALK (.__boltDK on every subtree
                // that already paints dark — the v1.3.91 "blind inversion"
                // report: dark greys stay dark, white-on-dark stays white,
                // only genuinely light surfaces flip).
                // v1.4.17 — THE FINISH-TIME BACKSTOP: the dark decision
                // chain moved to onPageCommitVisible (runWebDarkPass — the
                // white-flash fix; the full v1.3.94 ASK-FIRST order and its
                // field history live there and in Tab.applyWebDark). This
                // site now re-runs the chain ONLY when the commit pass never
                // fired for THIS url (an exotic WebView that skips commit),
                // guarded by the per-tab run token: an unguarded second run
                // would probe the already-inverted page, read it as painted
                // dark, and clear Bolt's own inversion.
                Tab dt = tabOf(view);
                if (dt == null || !url.equals(dt.darkPassUrl)) {
                    runWebDarkPass(view, url);
                }
                String proc = adblockProcFor(host, base);
                if (!proc.isEmpty()) view.evaluateJavascript(proc, null);
            } catch (Throwable ignored) {}
            if (!incognito) {
                String title = view.getTitle() == null ? "" : view.getTitle();
                prefs.addHistory(title, url);
            }
            // re-apply saved media settings (SPA navigations reset them)
            view.evaluateJavascript("window.__boltSetMedia && window.__boltSetMedia('"
                    + MediaHook.mediaSettingsJson(prefs).replace("'", "\\'") + "')", null);
            // userscripts: document-end now, document-idle shortly after
            if (!incognito) {
                injectUserscripts(view, url, "end");
                ui.postDelayed(() -> injectUserscripts(view, url, "idle"), 450);
            }
        }

        @Override
        public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
            if (failingUrl != null && failingUrl.equals(view.getUrl())) {
                // v1.4.19 — THE ERROR PAGE MUST NEVER BECOME THE TAB'S URL.
                // The old body built the sad-page HTML around failingUrl and
                // called view.loadData(html, "text/html", "utf-8") — loadData
                // REPLACES the WebView's URL with a data:text/html,… URL,
                // doUpdateVisitedHistory then wrote THAT into t.url, and
                // tabs.json / the phoenix restore persisted it: on reconnect
                // the tab reloaded the ERROR PAGE forever, never the site
                // (the captured-URL corpse — the evidence was literally a
                // tab whose address bar read data:text/html,<html><body…).
                // The error is now the native OVERLAY on flContent; the
                // tab's URL stays the original page (t.url, falling back to
                // the failing URL when the tab has no http address), and the
                // breadcrumb records the URL that must survive.
                Tab et = tabOf(view);
                String orig = (et != null && et.url != null && et.url.startsWith("http"))
                        ? et.url : failingUrl;
                try {
                    com.bolt.browser.util.Diag.log(BrowserActivity.this,
                            "offline fail code=" + code + " orig=" + orig);
                } catch (Throwable ignored) {}
                showErrorOverlay(view, orig);
            }
        }

        // v1.3.85 — THE UPLOAD-401 DECODER (the field report, twice: "the
        // file uploads is maybe broken, uploading files through zai gave
        // 401 unauthorised error" / "zai 401 unauthorised still remains
        // when uploading anything"). The zai webapp was fetched and its
        // shipped bundle read: the chat attachment POSTs to
        // /api/v1/files/ with an UNCONDITIONAL `authorization: Bearer
        // ${localStorage.token}` header — and the /api/v1/auths/ bootstrap
        // hands every visitor a GUEST token that the server ACCEPTS for
        // chat but REJECTS for uploads ("You do not have permission to
        // access this resource", 401 — reproduced with a live guest
        // session). So a 401 on a body-carrying request is, most of the
        // time, the SITE saying "sign in first", not a broken browser:
        // chat keeps working for guests, which is exactly why it looked
        // like an upload bug. Bolt can see the verdict — WebViewClient
        // delivers every resource's HTTP error here — so now it TELLS the
        // user (once per host per session) and leaves the evidence in
        // diagnostics for any residual case:
        //   upload auth 401 POST https://chat.z.ai/api/v1/files/…
        // The next "uploads broken" report either carries that line (site
        // policy — sign in) or doesn't (a real transport bug — keep
        // digging). The body-carrier law (v1.3.84) stays: Bolt never
        // touches these requests; it only reads the server's own answer.
        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                WebResourceResponse resp) {
            try {
                int code = resp.getStatusCode();
                if (code != 401 && code != 403) return;
                String method = null;
                try { method = request.getMethod(); } catch (Throwable ignored) {}
                String url = null;
                try { url = request.getUrl().toString(); } catch (Throwable ignored) {}
                // v1.3.95 — OPTIONS PREFLIGHTS ARE NOT UPLOADS: a CORS
                // handshake carries no user content (the v1.3.94 log showed
                // the SAME "upload auth 403 OPTIONS …rum.aliyuncs.com/rum/monitor"
                // line every few minutes — chat.z.ai's own telemetry beacon
                // failing its preflight, no user action involved). Only real
                // body carriers count; and each host+code+method logs ONCE
                // per session (a spamming beacon no longer floods the diag).
                boolean bodyCarrier = method != null
                        && !"GET".equals(method) && !"HEAD".equals(method)
                        && !"OPTIONS".equals(method);
                // the evidence channel: every auth rejection on a
                // body-carrying request lands in diagnostics
                if (bodyCarrier) {
                    String host = null;
                    try { host = request.getUrl().getHost(); } catch (Throwable ignored) {}
                    String dedupKey = code + " " + method + " " + host;
                    if (uploadAuthLogged.add(dedupKey)) {
                        com.bolt.browser.util.Diag.log(BrowserActivity.this,
                                "upload auth " + code + " " + method + " "
                                        + (url == null ? "?" : url.substring(0, Math.min(110, url.length()))));
                    }
                    // the honest hint, once per host per app session —
                    // never more (API probes 401 all day on some sites)
                    if (code == 401 && host != null && !incognito
                            && uploadAuthHinted.add(host)) {
                        Fx.toast(BrowserActivity.this,
                                getString(R.string.upload_auth_hint));
                    }
                }
            } catch (Throwable ignored) {}
        }

        // v1.3.19: renderer death must NEVER terminate the whole app. The
        // default (unoverridden) WebViewClient behavior is process death —
        // exactly the two v1.3.18 field reports: "launching a YouTube video
        // crashes the browser" (renderer crash on a heavy watch page) and
        // "with another browser open, Bolt's notification + background
        // playback vanish" (the system killed the backgrounded renderer to
        // reclaim memory; the app died with it). Official pattern (Managing
        // WebView): remove + destroy the dead WebView, rebuild lazily,
        // return true so the app lives on.
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Tab t = tabOf(view);
            // v1.3.20: persist the event — rendererPriorityAtExit() is the
            // empirical fingerprint of the v1.3.19 regression (media tabs died
            // at priority 1 = BOUND after the demotion) and didCrash=false
            // marks system kills (LMK) vs true renderer crashes.
            try {
                com.bolt.browser.util.Diag.logSync(BrowserActivity.this,
                        "rendererGone tab=" + (t == null ? "?" : tm.tabs.indexOf(t))
                                + " url=" + (t == null || t.url == null ? "" : t.url)
                                + " didCrash=" + detail.didCrash()
                                + " prioAtExit=" + detail.rendererPriorityAtExit()
                                + " webview=" + android.os.SystemClock.elapsedRealtime());
            } catch (Throwable ignored) {}
            // a fullscreen surface over a dead renderer is a black hole — leave it
            if (customView != null) exitFullscreenVideo();
            // v1.4.21 — HISTORY LIVES (the phoenix path): the renderer is
            // gone but the navigation history lives in the BROWSER process
            // (WebView's own model — render death ≠ history death), and the
            // view object can still serialize it. Capture saveState BEFORE
            // the destroy so the rebuilt WebView restores the back/forward
            // list; a capture that comes back empty (controller already
            // torn down) keeps the tab's last captured blob instead.
            if (t != null) {
                try { t.saveHist(view); } catch (Throwable ignored) {}
            }
            try {
                if (view.getParent() instanceof ViewGroup)
                    ((ViewGroup) view.getParent()).removeView(view);
            } catch (Throwable ignored) {}
            try { view.destroy(); } catch (Throwable ignored) {}
            if (t == null) return true;   // unknown/detached view — nothing else to fix
            t.web = null;
            t.mediaPlaying = false;
            t.holdsMedia = false;
            if (mediaTab == t) mediaTab = null;
            // v1.3.25: repair only fires for TRUE crashes (see below) — an
            // lmkd kill of a deliberately-waived hidden tab must not wipe caches.
            boolean repair = false;
            // crash-loop guard: repeated crashes of the SAME tab within 30 s.
            // v1.3.21 escalation ladder: at the 3rd death Bolt wipes the
            // Chromium cache trees ONCE per process and retries the page
            // against clean caches (a corrupted Code Cache / GPU shader entry
            // is the classic cause of a DETERMINISTIC per-site renderer
            // crash that survives app updates — deleting those files is the
            // documented cure and is exactly what "clear cache" does). Only
            // after two MORE post-repair deaths does the crash-notice page
            // stop the loop (docs-recommended).
            // v1.3.25: only TRUE crashes count. didCrash=false is the OS
            // reclaiming the renderer for memory (lmkd) — and since v1.3.25
            // hidden non-media tabs are deliberately WAIVED while media plays
            // (applyRendererPolicies), those kills are the DESIGN working, not
            // a stability signal: they must never escalate to the cache wipe
            // or the crash-notice page.
            if (detail.didCrash()) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - t.lastRenderCrashAt > 30000) t.renderCrashCount = 0;
                t.lastRenderCrashAt = now;
                int crashes = ++t.renderCrashCount;
                if (crashes == 3 && !sRendererRepairDone) {
                    sRendererRepairDone = true;
                    repair = true;
                }
                if (crashes > 4) t.renderCrashHold = true;
            }
            int idx = tm.tabs.indexOf(t);
            if (idx >= 0) {
                try { MediaControlService.onTabGone(BrowserActivity.this, idx); }
                catch (Throwable ignored) {}
            }
            // rebuild the visible tab immediately (fresh WebView reloads its
            // URL); a crashed background tab rebuilds when it is next shown,
            // and a crash while the app is backgrounded defers to the resume
            // sync (shownTabIndex=-1 forces the re-attach).
            if (t == activeTab()) {
                shownTabIndex = -1;
                shownTabRef = null;   // v1.3.51: the object channel resets with it
                if (repair) {
                    // wipe on a worker thread (bounded dirs, sub-second), then
                    // rebuild so the retry loads against clean caches
                    new Thread(() -> {
                        long freed = 0;
                        try {
                            freed = com.bolt.browser.util.DataWipe
                                    .wipeRendererCaches(BrowserActivity.this);
                        } catch (Throwable ignored) {}
                        final long f = freed;
                        ui.post(() -> {
                            try {
                                com.bolt.browser.util.Diag.log(BrowserActivity.this,
                                        "REPAIR chromium caches wiped freed=" + f
                                                + "B; retry " + (t.url == null ? "" : t.url));
                            } catch (Throwable ignored) {}
                            if (!activityBackground && !isFinishing()) {
                                try { showTab(tm.active); } catch (Throwable ignored) {}
                            }
                        });
                    }, "bolt-render-repair").start();
                } else if (!activityBackground && !isFinishing()) {
                    ui.post(() -> {
                        try { showTab(tm.active); } catch (Throwable ignored) {}
                    });
                }
            }
            return true;
        }
    }

    protected class BoltChrome extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            if (newProgress >= 100) {
                vProgress.setVisibility(View.GONE);
            } else {
                vProgress.setVisibility(View.VISIBLE);
                int w = (int) (flRoot.getWidth() * newProgress / 100f);
                vProgress.getLayoutParams().width = Math.max(Fx.dp(BrowserActivity.this, 6), w);
                vProgress.requestLayout();
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            super.onReceivedTitle(view, title);
            Tab t = tabOf(view);
            if (t != null && title != null) {
                t.title = title;
                tm.scheduleSave();   // v1.3.79: debounced — the direct save serialized every tab on the MAIN thread per title change
            }
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
            super.onReceivedIcon(view, icon);
            // v1.3.39: keep the site's icon — home shortcut tiles, tab cards
            // and link previews all read the store (never fetches anything).
            // NEVER from the incognito process: persisted icons would leave a
            // trail of incognito-visited sites in shared storage.
            if (incognito) return;
            try {
                // v1.3.78: the PIN owns attribution (getUrl() at icon time
                // races navigations); getUrl() is only the fallback for a
                // view whose start we somehow missed
                // v1.3.81: THE RACE GUARD — an icon landing inside the 700ms
                // window after this view's pin CHANGED hosts belongs to the
                // OUTGOING page (its request was still in flight when the
                // user navigated). Storing it poisoned the new host's slot
                // (google's G under z.ai) and the DOM extractor then never
                // ran ("an icon exists"). Drop it; the page's own icon
                // arrives later or via the extractor.
                if (com.bolt.browser.util.FaviconStore.inRaceWindow(view)) return;
                String host = com.bolt.browser.util.FaviconStore.pinnedHost(view);
                if (host == null || host.isEmpty())
                    host = Fx.hostOf(view.getUrl() != null ? view.getUrl()
                            : (tabOf(view) != null ? tabOf(view).url : ""));
                com.bolt.browser.util.FaviconStore.store(
                        BrowserActivity.this, host, icon);
            } catch (Throwable ignored) {}
        }

        // fullscreen video
        @Override
        public void onShowCustomView(View v, CustomViewCallback callback) {
            if (customView != null) {
                // v1.3.61 — DEFENSIVE RE-ACCEPT. This used to REJECT the new
                // view (callback.onCustomViewHidden(); return): a STALE
                // customView (left over from a path that missed the cleanup —
                // every enumerated one exits, but the field proved reachable
                // twice already) made the renderer's fullscreen request die
                // silently, and the site fell back to its CSS-maximized
                // "pseudo fullscreen" INSIDE the overview-scaled desktop
                // viewport — the v1.3.59/60 "zoomed-in view instead of true
                // full screen". Tearing the stale view down and ACCEPTING the
                // new one keeps the fullscreen state machine in sync instead.
                // v1.3.62 — RACE-FREE TEARDOWN: the old code notified the
                // renderer via exitFullscreenVideo() (onCustomViewHidden ->
                // the renderer's exit is a MOJO round trip) and the reply
                // (glue onHideCustomView) could land AFTER this new view was
                // already accepted — tearing the NEW fullscreen down a few
                // milliseconds after it started (the "fullscreen flashes and
                // dies" failure). The stale view is now removed WITHOUT
                // notifying; the v1.3.60 healer owns the renderer side of any
                // desync at the next showTab/pageFinished/play moment.
                try { com.bolt.browser.util.Diag.log(BrowserActivity.this, "fs restale"); } catch (Throwable ignored) {}
                try { flRoot.removeView(customView); } catch (Throwable ignored) {}
                customView = null;
                customCallback = null;
                // fall through: accept the new view below
            }
            customView = v;
            customCallback = callback;
            flRoot.addView(v, new FrameLayout.LayoutParams(-1, -1));
            flRoot.bringChildToFront(v);
            findViewById(R.id.topbar).setVisibility(View.GONE);
            findViewById(R.id.bottombar).setVisibility(View.GONE);
            // v1.3.40: the download shelf is chrome too — never over the video
            View sh = findViewById(R.id.dl_shelf);
            if (sh != null) sh.setVisibility(View.GONE);
            // v1.3.33: hide the whole floating chrome column (the progress
            // strip + find/tts bars ride inside top_group and would otherwise
            // paint over the video)
            findViewById(R.id.top_group).setVisibility(View.GONE);
            // v1.3.61: a pseudo-fullscreen state must never outlive the real
            // one — the watcher's sweep is fully undone by the real path's own
            // restore in hideCustomViewInternal
            if (pseudoFullscreen) pseudoFullscreen = false;
            // v1.3.60: the runtime-added floating surfaces are swept too —
            // they attach to flRoot AFTER the customView, so Android paints
            // them ABOVE the video (the XML chrome is below it by addView
            // order, but the quick panel and the fill chips are added later
            // and would ride over fullscreen video)
            try { hideQuickPanel(); } catch (Throwable ignored) {}
            try { hideOtpChip(); } catch (Throwable ignored) {}
            try { hidePwChip(); } catch (Throwable ignored) {}
            try { hideProfileChip(); } catch (Throwable ignored) {}
            hideSystemBars();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            // v1.3.91 — THE ORIENTATION CONTRACT (the field report: "youtube
            // shorts sometimes have flickering toolbars in fullscreen mode").
            // The old code forced SENSOR_LANDSCAPE on EVERY custom view and
            // then kicked the viewport TWICE (1-px padding at 300 ms and
            // 1000 ms) plus a probe — four layout mutations in the first
            // 1.6 s of every fullscreen enter. A PORTRAIT video (Shorts,
            // Reels, TikToks) on a portrait phone got rotated to a landscape
            // it never needed, and every one of those mutations re-fired the
            // page's resize handler — YouTube's player chrome toggles on
            // viewport changes, so the toolbars FLASHED. The new sequence:
            // hold the CURRENT orientation (LOCKED) until the aspect probe
            // answers (probeFullscreenVideo below), then follow the VIDEO
            // (landscape -> sensor landscape; portrait -> sensor PORTRAIT —
            // Chrome keeps a portrait video portrait); the settle kicks only
            // run when the orientation did NOT change (a rotation IS the
            // VisualPropertiesUpdated the enter needs to settle — see
            // fsEnterAfterAspect).
            fsAwaitAspect = true;
            setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            // v1.3.62 — THE SETTLE KICK + VIDEO PROBE (replaces the v1.3.61
            // viewport decoupling, which was a no-op: useWideViewPort is a
            // load-time quirk pref, and its resetScrollAndScaleState fired in
            // the middle of the enter handshake). Chromium's FullscreenController
            // does NOT resolve a granted requestFullscreen until a
            // VisualPropertiesUpdated (a REAL size/inset change) reaches the
            // renderer — "the visual property updates *must* call
            // DidEnterFullscreen() in order for the requestFullscreen promise
            // to be resolved. There are early outs ... that may prevent this
            // from happening" (fullscreen_controller.cc, its own TODO). An
            // unresolved enter wedges: no top layer (page overlays — userscript
            // panels — ride the video), no scale reset (the desktop page keeps
            // its 980-px overview/pinch scale = the "zoomed-in view"), and
            // the app-side custom view still owns the screen. The kick forces a
            // real 1-px size change through the FullScreenView, guaranteeing
            // the sync that settles the request. v1.3.91: the kicks are
            // gated by fsEnterAfterAspect — a rotation settles itself, a
            // no-rotation enter kicks once, probes at 700 ms, and only a
            // still-wedged page gets the second kick + the rescue probe.
            try { probeFullscreenVideo(); } catch (Throwable ignored) {}
            final View cv = v;
            ui.postDelayed(() -> {
                // v1.3.63 — the custom view has been laid out by now: re-arm
                // the auto-PiP params with the REAL window aspect (the arm at
                // the top of this method can run before the first layout
                // pass — getWidth()==0 fell back to 16:9, handing API 31+
                // auto-enter the mismatched aspect the v1.3.60/62 geometry
                // bugs were made of)
                try { if (customView == cv) updatePipParams(); } catch (Throwable ignored) {}
            }, 300);
            // v1.3.91 — the fallback clock: if the aspect probe never
            // answered (a stalled renderer), the orientation decision runs
            // anyway at 650 ms on the legacy landscape default so the
            // fullscreen can never stay LOCKED forever.
            ui.postDelayed(() -> {
                try {
                    if (customView == cv && fsAwaitAspect) {
                        fsAwaitAspect = false;
                        fsEnterAfterAspect(0f);
                    }
                } catch (Throwable ignored) {}
            }, 650);
            try {
                com.bolt.browser.util.Diag.log(BrowserActivity.this, "fs show host="
                        + com.bolt.browser.util.Fx.hostOf(
                                activeTab() == null ? "" : activeTab().url));
            } catch (Throwable ignored) {}
            // v1.3.41: PiP eligibility flips with the video — auto-enter is
            // armed by params, not computed at gesture time, so it must be
            // live BEFORE the user swipes home
            try { updatePipParams(); } catch (Throwable ignored) {}
        }

        @Override
        public void onHideCustomView() {
            hideCustomViewInternal(false);
        }

        // camera / mic
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            final String host = Fx.hostOf(activeTab().url);
            final String base = Fx.baseDomain(host);
            boolean any = false, denied = false;
            for (String r : request.getResources()) {
                if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    any = true;
                    if (prefs.siteBool(base, "noCam", false)) denied = true;
                } else if (r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    any = true;
                    if (prefs.siteBool(base, "noMic", false)) denied = true;
                }
            }
            if (!any) { // other permissions (midi etc) -> deny
                ui.post(() -> {
                    try { request.deny(); } catch (Throwable ignored) {}
                });
                return;
            }
            if (denied) {
                Fx.toast(BrowserActivity.this, "Camera/mic blocked for this site");
                ui.post(() -> {
                    try { request.deny(); } catch (Throwable ignored) {}
                });
                return;
            }
            // request android runtime permissions
            List<String> need = new ArrayList<>();
            for (String r : request.getResources()) {
                if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        && ContextCompat.checkSelfPermission(BrowserActivity.this, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
                if (r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        && ContextCompat.checkSelfPermission(BrowserActivity.this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
            }
            if (need.isEmpty()) {
                ui.post(() -> {
                    try { request.grant(request.getResources()); } catch (Throwable ignored) {}
                });
            } else {
                pendingPermRequest = request;
                ActivityCompat.requestPermissions(BrowserActivity.this,
                        need.toArray(new String[0]), 77);
            }
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                GeolocationPermissions.Callback cb) {
            cb.invoke(origin, false, false); // always deny location
        }

        /**
         * v1.3.31 — THE file-upload fix. A WebView <input type="file"> does
         *  nothing by itself: Chromium hands the app a ValueCallback and
         *  expects THE APP to run a system file picker and deliver the picked
         *  Uris back (WebChromeClient.onShowFileChooser). Bolt never
         *  implemented it — every "upload" button on every site was a silent
         *  no-op (the v1.3.30 field report: "clicking upload option gives no
         *  way to select files").
         *
         *  Mechanics: FileChooserParams.createIntent() builds an
         *  ACTION_GET_CONTENT intent carrying the page's accept types +
         *  multiple-select mode. Raw GET_CONTENT intents with exotic accept
         *  types (".csv", "image/heic"…) can fail to resolve on some ROMs, so
         *  it is wrapped in a chooser (always resolvable) and a bare fallback
         *  covers a broken chooser. The callback is delivered EXACTLY once in
         *  onActivityResult (1005) — including null on cancel — or the NEXT
         *  picker on that page refuses to open (Chromium's duplicate-result
         *  guard). Also replaces any stale callback (a second picker opened
         *  without the first being answered). Works for the incognito process
         *  too (the method lives on the shared activity).
         */
        @Override
        public boolean onShowFileChooser(WebView view,
                android.webkit.ValueCallback<android.net.Uri[]> callback,
                FileChooserParams params) {
            if (fileUploadCallback != null) {
                // a previous picker was never answered — retire it first
                try { fileUploadCallback.onReceiveValue(null); } catch (Throwable ignored) {}
            }
            fileUploadCallback = callback;
            Intent i = null;
            try { i = params.createIntent(); } catch (Throwable ignored) {}
            if (i == null) {
                i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
            }
            try {
                startActivityForResult(Intent.createChooser(i, getString(R.string.pick_files)), 1005);
            } catch (Throwable t) {
                try {
                    startActivityForResult(i, 1005);
                } catch (Throwable t2) {
                    fileUploadCallback = null;
                    try { callback.onReceiveValue(null); } catch (Throwable ignored) {}
                    Fx.toast(BrowserActivity.this, getString(R.string.no_file_picker));
                    return false;
                }
            }
            return true;
        }

        // popup blocker
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                android.os.Message resultMsg) {
            if (!prefs.popupBlock()) return false;
            // capture popup target URL with a throwaway WebView
            final String[] popupUrl = {null};
            WebView probe = new WebView(BrowserActivity.this);
            probe.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
            probe.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    popupUrl[0] = url;
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(probe);
            resultMsg.sendToTarget();
            ui.postDelayed(() -> {
                String pu = popupUrl[0];
                if (pu == null || pu.equals("about:blank")) return;
                final String base = Fx.baseDomain(Fx.hostOf(activeTab().url));
                new AlertDialog.Builder(BrowserActivity.this)
                        .setTitle(R.string.popup_title)
                        .setMessage(getString(R.string.popup_msg, pu.length() > 80 ? pu.substring(0, 80) + "…" : pu))
                        .setPositiveButton(R.string.allow, (d, w) -> openNewTab(pu))
                        .setNeutralButton(R.string.always_allow, (d, w) -> {
                            prefs.setSite(base, "popupAllow", true);
                            openNewTab(pu);
                        })
                        .setNegativeButton(R.string.block, null)
                        .show();
            }, 350);
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {}
    }

    /** v1.3.86 — recent download-attribute names (href → name), recorded by
     *  the FrameHooks click listener via Bolt.noteDownloadAnchor BEFORE
     *  onDownloadStart fires, so blob:/data:/https downloads can offer the
     *  REAL filename instead of the blob UUID / CDN hash token. Linked-map
     *  LRU; any thread (the bridge arrives on the JavaBridge thread, reads
     *  happen on the UI thread — both under the map's monitor). */
    protected final java.util.LinkedHashMap<String, String> dlAnchorNames =
            new java.util.LinkedHashMap<String, String>() {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, String> eldest) {
                    return size() > 12;
                }
            };

    /** v1.3.86 — bridge target for the FrameHooks a[download] click capture.
     *  Synthetic element.click() downloads (zai's export flow) dispatch
     *  through capture listeners, so the href→name pair lands here before
     *  the WebView fires onDownloadStart for the same URL. */
    @JavascriptInterface
    public void noteDownloadAnchor(final String href, final String name) {
        try {
            if (href == null || href.isEmpty() || name == null) return;
            String n = sanitizeDownloadName(name);
            if (n == null) return;
            synchronized (dlAnchorNames) {
                dlAnchorNames.remove(href);   // LRU refresh
                dlAnchorNames.put(href, n);
            }
            com.bolt.browser.util.Diag.log(getApplicationContext(), "dl anchor " + n);
        } catch (Throwable ignored) {}
    }

    /** the remembered a[download] name for this download URL, or null.
     *  Used as the dialog default / blob-save name so the file lands with
     *  the site's own filename. */
    protected String downloadAnchorName(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            synchronized (dlAnchorNames) {
                return dlAnchorNames.get(url);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** clean a download-attribute value into a filename: take the
     *  basename (sites sometimes pass paths), strip separators/controls/
     *  quotes, cap length. Null when nothing usable remains. */
    protected static String sanitizeDownloadName(String n) {
        if (n == null) return null;
        n = n.trim();
        if (n.isEmpty()) return null;
        int s = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (s >= 0 && s < n.length() - 1) n = n.substring(s + 1);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length() && i < 120; i++) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c == '\0') sb.append('_');
            else if (c == '"' || c == '\'' || c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        n = sb.toString().trim();
        return n.isEmpty() ? null : n;
    }

    protected final android.webkit.DownloadListener downloadListener =
            new android.webkit.DownloadListener() {
        @Override
        public void onDownloadStart(final String url, String userAgent, String contentDisposition,
                String mimetype, long contentLength) {
            // v1.3.57 — field evidence: which URL/mime actually reached the
            // listener (the next "download fails" report must show this line;
            // truncated — data: URLs can be huge)
            try {
                com.bolt.browser.util.Diag.log(BrowserActivity.this, "dl offer " +
                        (url == null ? "" : url.substring(0, Math.min(100, url.length()))));
            } catch (Throwable ignored) {}
            // data: downloads (some pages hand these to the listener directly)
            if (url != null && url.startsWith("data:")) {
                String dname = downloadAnchorName(url);
                String dn = dname != null ? dname
                        : android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
                // v1.4.20 — the ask-every-time router (default ASK): the
                // data: door rides the rename + destination dialog too;
                // Direct keeps the old silent save
                if (DownloadEngine.dlDialogRoute(prefs.downloadAsk(), url)) {
                    showDownloadDialog(url, userAgent, contentDisposition, mimetype,
                            contentLength, dn);
                } else {
                    enqueueDownload(url, userAgent, dn, mimetype);
                }
                return;
            }
            // v1.4.16 — BLOB: DOWNLOADS GO STRAIGHT THROUGH THE JS BRIDGE.
            // A blob: URL's bytes exist only inside the page that minted
            // them; HttpURLConnection can never fetch one (MalformedURLException
            // x4 retries -> failed row, and the name guess degraded to a
            // UUID/.bin). The v1.3.58 rescue chain (util/BlobDownload.kt:
            // frame dance -> fetch -> own-img -> hunts) reads the blob
            // JS-side as a Base64 data URL and hands it to the JS-bridge
            // method Bolt.gmSaveBlob (the annotated bridge surface — count
            // unchanged), which calls
            // DownloadEngine.saveDataUrl — exact bytes, real mime, honest
            // name (the anchor's download attribute when the page sent one).
            // Routed HERE (before the dialog) so no blob ever depends on the
            // dialog's confirm to reach the rescue.
            if (url != null && url.startsWith("blob:")) {
                String bname = downloadAnchorName(url);
                if (bname == null) {
                    bname = DownloadEngine.humanName(url,
                            android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype));
                }
                Tab bt = activeTab();
                // v1.4.20 — the ask-every-time door rides INSIDE the
                // delivery bridge (gmSaveBlob consults the pref): the
                // rescue chain starts here and reads the bytes exactly as
                // before; Ask OFFERS them to the dialog when they arrive,
                // Direct saves at once. No toast under Ask — the dialog
                // itself is the feedback.
                boolean blobAsk = DownloadEngine.dlDialogRoute(prefs.downloadAsk(), url);
                com.bolt.browser.util.BlobDownload.start(
                        bt == null ? null : bt.web, url, bname, mimetype);
                try {
                    com.bolt.browser.util.Diag.log(BrowserActivity.this, "blobdl start name=" + bname
                            + " mime=" + mimetype + " ask=" + blobAsk);
                } catch (Throwable ignored) {}
                ensureDlShelfListener();
                if (!blobAsk) {
                    Fx.toast(BrowserActivity.this, getString(R.string.download_started) + ": " + bname);
                }
                return;
            }
            // userscript install: Greasy Fork / OpenUserJS .user.js links
            String guessed = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
            if (url != null && (url.endsWith(".user.js") || guessed.endsWith(".user.js"))) {
                installUserscript(url);
                return;
            }
            // v1.3.87 — THE DOWNLOAD-PAGE REPAIR (before the dialog: the
            // pair is armed synchronously, so a save racing the posted
            // goBack still persists the real page)
            repairDownloadPage(url);
            // v1.4.20 — the ask-every-time router: Direct skips the
            // confirm and enqueues with the honest guessed name (the
            // engine refines mime/extension on the response anyway);
            // Ask (the default) keeps the rename + destination dialog
            if (DownloadEngine.dlDialogRoute(prefs.downloadAsk(), url)) {
                showDownloadDialog(url, userAgent, contentDisposition, mimetype, contentLength);
            } else {
                enqueueDownload(url, userAgent,
                        DownloadEngine.humanName(url,
                                android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)),
                        mimetype);
            }
        }
    };

    /** v1.3.87 — THE DOWNLOAD-PAGE REPAIR, v1.3.88 ROUND 2 (the field
     *  report: "after downloading and closing the browser, the download link
     *  stays as the only website and I cannot go back the page before I
     *  pressed the download link"). Some download flows COMMIT the download
     *  URL into the WebView (the GitHub releases pattern: anchor → 302 →
     *  attachment), leaving a blank page — and the tab-restore path rebuilds
     *  a single-entry history from the saved URL, so BACK falls out of the
     *  tab entirely. v1.3.87 armed the repair pair ONLY when the download
     *  URL had already committed at onDownloadStart time — in the field the
     *  commit lands AFTER the listener fires, so nothing ever armed,
     *  doUpdateVisitedHistory set t.url = the download URL, and the tab
     *  persisted the dead entry. ROUND 2 arms in BOTH states: the current
     *  page IS the origin when the commit has not landed yet (the common
     *  case), and the back-forward walk covers the already-committed one.
     *  The moment an armed dlUrl lands in the visited history (the commit
     *  hook in doUpdateVisitedHistory) the WebView steps back; blind
     *  400/1500 ms checks cover commits that never fire the callback.
     *  Blob:/data: downloads never navigate and never match. */
    protected void repairDownloadPage(final String dlUrl) {
        try {
            if (dlUrl == null || !dlUrl.startsWith("http")) return;
            // the DownloadListener interface carries no view — a background
            // tab's WebView can be the downloader: prefer the tab actually
            // sitting on the download URL (EXACT match only — about:blank
            // is indistinguishable across tabs), else the active tab
            Tab t = activeTab();
            if (t == null || t.web == null) return;
            if (!dlUrl.equals(t.web.getUrl())) {
                synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
                    for (Tab o : tm.tabs) {
                        if (o != null && o.web != null && dlUrl.equals(o.web.getUrl())) { t = o; break; }
                    }
                }
            }
            WebBackForwardList hl = t.web.copyBackForwardList();
            int cur = hl.getCurrentIndex();
            String curUrl = t.web.getUrl();
            boolean onDl = dlStrandedOn(t.web, dlUrl);
            // the page the download came FROM:
            // (a) committed already -> walk back past blanks and any earlier
            //     copy of the download URL itself;
            // (b) not committed yet -> the CURRENT page is the origin; arm
            //     NOW, before the blank arrives (the v1.3.87 blind spot).
            String pre = null;
            if (onDl) {
                for (int i = cur - 1; i >= 0; i--) {
                    String u = hl.getItemAtIndex(i).getUrl();
                    if (u != null && u.startsWith("http") && !u.equals(dlUrl)
                            && !"about:blank".equals(u)) { pre = u; break; }
                }
            } else if (curUrl != null && curUrl.startsWith("http")
                    && !curUrl.equals(dlUrl)) {
                pre = curUrl;
            }
            if (pre == null) return;
            t.dlUrl = dlUrl;
            t.dlFromUrl = pre;
            tm.scheduleSave();
            final Tab tt = t;
            final String prePage = pre;
            // late-commit checks: the blank page can land well after the
            // dialog (any of these no-ops when the page never stranded)
            ui.postDelayed(() -> dlStepBack(tt, prePage), 400);
            ui.postDelayed(() -> dlStepBack(tt, prePage), 1500);
        } catch (Throwable ignored) {}
    }

    /** v1.3.88 — true when the WebView sits on the (blank) download page. */
    protected boolean dlStrandedOn(WebView w, String dlUrl) {
        String u = w.getUrl();
        return u != null && (u.equals(dlUrl) || "about:blank".equals(u) || u.isEmpty());
    }

    /** v1.3.88 — the ONE raw goBack of the download-repair family (armed by
     *  repairDownloadPage's late checks and by the doUpdateVisitedHistory
     *  commit hook): step the stranded WebView back to the page the download
     *  came from. Re-verified at fire time — a user who navigated meanwhile
     *  is never yanked; idempotent once healed. */
    protected void dlStepBack(final Tab t, final String prePage) {
        try {
            if (t == null || t.web == null || !tm.tabs.contains(t)) return;
            if (t.dlUrl == null || t.dlFromUrl == null) return;
            if (dlStrandedOn(t.web, t.dlUrl) && t.web.canGoBack()) {
                t.web.goBack();
                com.bolt.browser.util.Diag.log(getApplicationContext(),
                        "dl repair " + Fx.hostOf(prePage));
            }
        } catch (Throwable ignored) {}
    }

    protected TextView dlDestLabel; // destination row of the currently open download dialog

    /** download confirm dialog: rename + choose destination (public Downloads or SAF folder) */
    protected void showDownloadDialog(final String url, final String ua, String cd,
            final String mime, long len) {
        showDownloadDialog(url, ua, cd, mime, len, null);
    }

    /** v1.4.20 — the full form: nameHint is the honest name a rescue
     *  already chose (the blob chain's anchor/download attribute — the
     *  data: URL it hands over would otherwise guess garbage); the info
     *  line names the SAVED BYTES for data: offers instead of a host. */
    protected void showDownloadDialog(final String url, final String ua, String cd,
            final String mime, long len, final String nameHint) {
        int dim = Fx.dim(this);
        int txt = Fx.txt(this);
        // v1.3.86 — the REAL filename, in order of trust: (1) the anchor's
        // download attribute (recorded by the FrameHooks click capture —
        // the zai case: blob URLs whose UUID used to become the name);
        // (2) the humanized URL guess (blob UUIDs / CDN hash tokens become
        // host-YYYYMMDD-HHMM, the v1.3.78 law, now applied on the dialog
        // path too — it previously only ran when enqueue got an empty
        // name, which the dialog never sends).
        // v1.4.20: (1.5) a caller-supplied nameHint (the blob rescue's
        // chosen name) sits between them.
        String attr = downloadAnchorName(url);
        String guessed = attr != null ? attr
                : (nameHint != null && !nameHint.isEmpty() ? nameHint
                : DownloadEngine.humanName(url,
                        android.webkit.URLUtil.guessFileName(url, cd, mime)));
        if (attr != null) {
            try {
                com.bolt.browser.util.Diag.log(this, "dl via anchor " + attr);
            } catch (Throwable ignored) {}
        }

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 20);
        wrap.setPadding(p, p / 2, p, 0);

        String size = len > 0 ? android.text.format.Formatter.formatShortFileSize(this, len)
                : "unknown size";
        TextView info = new TextView(this);
        // v1.4.20: a data: offer has no host — the info line names the
        // bytes (the rescue fetched them from the page)
        info.setText((url != null && url.startsWith("data:") ? "saved from page" : Fx.hostOf(url))
                + "  •  " + size);
        info.setTextColor(dim);
        info.setTextSize(13);
        info.setPadding(0, Fx.dp(this, 8), 0, 0);
        wrap.addView(info, new LinearLayout.LayoutParams(-1, -2));

        final EditText etName = new EditText(this);
        etName.setText(guessed);
        etName.setTextColor(txt);
        etName.setSingleLine(true);
        wrap.addView(etName, new LinearLayout.LayoutParams(-1, -2));

        // destination row: public Downloads, or the persisted SAF folder
        LinearLayout dest = new LinearLayout(this);
        dest.setOrientation(LinearLayout.HORIZONTAL);
        dest.setGravity(Gravity.CENTER_VERTICAL);
        dest.setPadding(0, Fx.dp(this, 6), 0, 0);
        TextView dl = new TextView(this);
        dl.setText(R.string.save_to);
        dl.setTextColor(dim);
        dl.setTextSize(14);
        dest.addView(dl, new LinearLayout.LayoutParams(-2, -2));
        dlDestLabel = new TextView(this);
        dlDestLabel.setTextColor(Fx.accentText(this));
        dlDestLabel.setTextSize(14);
        dlDestLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        dlDestLabel.setSingleLine(true);
        dlDestLabel.setPadding(Fx.dp(this, 8), 0, Fx.dp(this, 8), 0);
        String cur = prefs.downloadDir();
        dlDestLabel.setText(cur.isEmpty() ? getString(R.string.downloads_public) : safFolderName(cur));
        dest.addView(dlDestLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView change = new TextView(this);
        change.setText(R.string.change);
        change.setTextColor(Fx.accentText(this));
        change.setTextSize(14);
        dest.addView(change, new LinearLayout.LayoutParams(-2, -2));
        TextView reset = new TextView(this);
        reset.setText(R.string.reset);
        reset.setTextColor(dim);
        reset.setTextSize(14);
        reset.setPadding(Fx.dp(this, 10), 0, 0, 0);
        dest.addView(reset, new LinearLayout.LayoutParams(-2, -2));
        change.setOnClickListener(v -> pickDownloadFolder());
        dest.setOnClickListener(v -> pickDownloadFolder());
        reset.setOnClickListener(v -> {
            prefs.setDownloadDir("");
            dlDestLabel.setText(getString(R.string.downloads_public));
        });
        wrap.addView(dest, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle(R.string.download_file)
                .setView(wrap)
                .setPositiveButton(R.string.download, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) name = guessed;
                    enqueueDownload(url, ua, name, mime);
                    dlDestLabel = null;
                })
                .setNegativeButton(R.string.cancel, (d, w) -> dlDestLabel = null)
                .setOnCancelListener(d -> dlDestLabel = null)
                .show();
    }

    /** SAF folder picker for downloads */
    protected void pickDownloadFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, 1002);
        } catch (Throwable e) {
            Fx.toast(this, "No file picker available");
        }
    }

    protected String safFolderName(String treeUri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri));
            String last = id == null ? "" : id;
            int c = last.lastIndexOf(':');
            if (c >= 0) last = last.substring(c + 1);
            return last.isEmpty() ? "Chosen folder" : last;
        } catch (Throwable e) {
            return "Chosen folder";
        }
    }

    /** enqueue into Bolt's own download engine. The system DownloadManager was
     *  removed entirely: it failed on SAF destinations because the downloads
     *  provider process cannot write to content URIs granted to this app, and
     *  it never shared the WebView's session cookies. */
    protected void enqueueDownload(String url, String ua, String name, String mime) {
        if (url == null || url.isEmpty()) return;
        if (name == null || name.isEmpty()) name = "download";
        if (url.startsWith("data:")) {
            DownloadEngine.get(this).saveDataUrl(url, name, mime);
            ensureDlShelfListener();   // v1.3.40 download shelf
            Fx.toast(this, getString(R.string.download_started) + ": " + name);
            return;
        }
        if (url.startsWith("blob:")) {
            // v1.3.56 — blob: downloads (Gemini/AI image saves, canvas
            // exports, generated PDFs…): the bytes live ONLY inside the
            // page that created them and Java cannot fetch a blob: URL —
            // the old path died here with "Can't download this link
            // type". Pull them through the page itself (util/
            // BlobDownload.kt chain v3: frame dance → fetch → own-img →
            // hunts → honest failure; the v1.3.58 frame-dance stage covers
            // blob:null URLs minted in opaque-origin iframes — Gemini's
            // case — via the FrameHooks per-frame blob cache).
            Tab bt = activeTab();
            com.bolt.browser.util.BlobDownload.start(
                    bt == null ? null : bt.web, url, name, mime);
            // v1.3.57 — field evidence: the chain was entered (a later
            // gmSaveBlob line proves bytes came back; neither line + a
            // "Download failed" toast means every stage missed)
            try {
                com.bolt.browser.util.Diag.log(this, "blobdl start name=" + name
                        + " mime=" + mime);
            } catch (Throwable ignored) {}
            ensureDlShelfListener();   // v1.3.40 download shelf
            Fx.toast(this, getString(R.string.download_started) + ": " + name);
            return;
        }
        if (!url.startsWith("http")) {
            Fx.toast(this, "Can't download this link type");
            return;
        }
        String wua = ua;
        try {
            if ((wua == null || wua.isEmpty()) && activeTab().web != null)
                wua = activeTab().web.getSettings().getUserAgentString();
        } catch (Throwable ignored) {}
        String pageUrl = activeTab().url;
        String referer = pageUrl != null && pageUrl.startsWith("http") ? pageUrl : "";
        if (Build.VERSION.SDK_INT <= 28
                && prefs.downloadDir().isEmpty()
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            // public Downloads needs the legacy storage grant on API <= 28;
            // ask once and enqueue right after (or fall back to the app folder)
            pendingDl = new String[]{url, name, mime == null ? "" : mime,
                    wua == null ? "" : wua, referer};
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 78);
            return;
        }
        // Android 13+: download progress/completion notifications need the
        // runtime permission — ask once on the first download (non-blocking;
        // the download itself proceeds either way)
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 79);
            }
        } catch (Throwable ignored) {}
        DownloadEngine.get(this).enqueue(url, name, mime, wua, referer);
        ensureDlShelfListener();   // v1.3.40: the shelf follows every download
        Fx.toast(this, getString(R.string.download_started) + ": " + name);
    }

    /** pending download awaiting the legacy storage permission (API <= 28) */
    protected String[] pendingDl;

    /** images behind blob:/data: URLs cannot be fetched by Java — pull the bytes
     *  through the page's own fetch() and hand them to the engine as a data URL. */
    protected void downloadInPageImage(final String pageUrl, String name) {
        Tab t = activeTab();
        if (t.web == null) return;
        if (name == null || name.isEmpty()) name = "image_" + System.currentTimeMillis();
        // NOTE: no blind ".png" here — the engine sniffs the real bytes and
        // appends the correct extension (jpeg/webp/png/gif) at save time.
        final String nm = name;
        if (pageUrl.startsWith("data:")) {
            // v1.4.20 — the ask-every-time router rides the long-press data:
            // door too (the name this path derived is the dialog's hint)
            if (DownloadEngine.dlDialogRoute(prefs.downloadAsk(), pageUrl)) {
                showDownloadDialog(pageUrl, null, null, null, 0, nm);
            } else {
                DownloadEngine.get(this).saveDataUrl(pageUrl, nm, null);
                ensureDlShelfListener();   // v1.3.40 download shelf
                Fx.toast(this, getString(R.string.download_started));
            }
            return;
        }
        if (pageUrl.startsWith("blob:")) {
            // v1.3.56 — route blob: images through the full rescue chain
            // (util/BlobDownload.kt) with the EXACT tab that long-pressed:
            // the old single fetch() died on strict-CSP sites (Gemini's
            // connect-src has no blob:) and evalMedia could inject into a
            // background media tab instead of the one showing the image.
            // v1.4.20: the ask-every-time door rides inside gmSaveBlob.
            com.bolt.browser.util.BlobDownload.start(t.web, pageUrl, nm, null);
            ensureDlShelfListener();   // v1.3.40 download shelf
            Fx.toast(this, getString(R.string.download_started));
            return;
        }
        // v1.4.20 — the fetch rescue delivers to gmSaveBlob, which consults
        // the ask pref itself (one delivery contract for every rescue)
        String js = "(function(){try{fetch(" + JSONObject.quote(pageUrl) + ")"
                + ".then(function(r){return r.blob();})"
                + ".then(function(b){var fr=new FileReader();fr.onload=function(){"
                + "try{Bolt.gmSaveBlob(" + JSONObject.quote(nm) + ",b.type||'',fr.result);}catch(e){}" + "};"
                + "fr.onerror=function(){try{Bolt.notify('Download failed');}catch(x){}};"
                + "fr.readAsDataURL(b);})"
                + ".catch(function(e){try{Bolt.notify('Download failed');}catch(x){}});}catch(e){}})();";
        evalMedia(js);
        Fx.toast(this, getString(R.string.download_started));
    }

    /** fetch a .user.js, parse its header, confirm and install it */
    protected void installUserscript(final String url) {
        Fx.toast(this, "Fetching script…");
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                String code = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                if (!code.contains("==UserScript==")) {
                    code = "// ==UserScript==\n// @name Script\n// @match *\n// ==/UserScript==\n" + code;
                }
                final String fcode = code;
                final UserscriptEngine.Script s = UserscriptEngine.parse(code,
                        "script_" + System.currentTimeMillis() + ".user.js");
                ui.post(() -> new AlertDialog.Builder(BrowserActivity.this)
                        .setTitle(R.string.install_script)
                        .setMessage(s.name + "\n\nRuns on: " + TextUtils.join(", ", s.matches))
                        .setPositiveButton(R.string.add, (d, w) -> {
                            java.io.File f = new java.io.File(prefs.scriptsDir(), s.file);
                            Fx.writeText(f, fcode);
                            Fx.toast(BrowserActivity.this, getString(R.string.script_installed));
                            Tab t = activeTab();
                            if (t.web != null && !t.isHome
                                    && UserscriptEngine.matches(s, t.url)) {
                                t.web.reload(); // script applies to the page we came from
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show());
            } catch (Throwable e) {
                ui.post(() -> Fx.toast(BrowserActivity.this, "Install failed"));
            }
        }, "usinst").start();
    }

    // ------------------------------------------------------------ long-press context menu

    protected boolean handleLongPress(WebView w) {
        try {
            android.webkit.WebView.HitTestResult hr = w.getHitTestResult();
            if (hr == null) return false;
            int type = hr.getType();
            final String extra = hr.getExtra();
            if (extra == null) return false;
            boolean isHttp = extra.startsWith("http");
            boolean isData = extra.startsWith("data:");
            boolean isBlob = extra.startsWith("blob:");
            boolean isImage = type == android.webkit.WebView.HitTestResult.IMAGE_TYPE
                    || type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
            if (isImage) {
                if (!isHttp && !isData && !isBlob) return false;
            } else if (type == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                if (!isHttp) return false;
            } else {
                return false; // plain text / unknown -> default selection behaviour
            }
            final List<String> opts = new ArrayList<>();
            final List<Runnable> acts = new ArrayList<>();
            if (isImage) {
                final String img = extra;
                opts.add(getString(R.string.download_image));
                acts.add(() -> {
                    if (img.startsWith("http")) {
                        // v1.4.20 — the ask-every-time router on the long-press
                        // http door: Direct skips the confirm (the in-page
                        // fetch rescue saves straight through); Ask keeps the
                        // rename + destination dialog
                        if (DownloadEngine.dlDialogRoute(prefs.downloadAsk(), img))
                            showDownloadDialog(img, null, null, mimeOf(img), 0);
                        else
                            downloadInPageImage(img, null);
                    }
                    else
                        downloadInPageImage(img, null); // blob:/data: via in-page fetch
                });
                opts.add(getString(R.string.open_image_new_tab));
                acts.add(() -> openNewTab(img));
                opts.add(getString(R.string.copy_image_url));
                acts.add(() -> copyText(img));
                opts.add(getString(R.string.share_image_url));
                acts.add(() -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, img);
                    startActivity(Intent.createChooser(i, getString(R.string.share)));
                });
            } else if (type == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                // v1.3.39 — LINK PREVIEW CARD (menu-3 #4): "long-press a link
                // → a peek card (title/host/description) with open-new-tab /
                // watch-later / copy actions, instead of only the raw URL
                // menu." Everything local: the favicon store supplies the
                // site avatar; nothing is prefetched over the network.
                final String link = extra;
                com.bolt.browser.util.LinkPreview.show(this, link);
                return true;
            } else {
                return false; // plain text / unknown -> default selection behaviour
            }
            new AlertDialog.Builder(this)
                    .setItems(opts.toArray(new String[0]), (d, wi) -> acts.get(wi).run())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    protected String mimeOf(String url) {
        String u = url.toLowerCase();
        if (u.endsWith(".png")) return "image/png";
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg";
        if (u.endsWith(".gif")) return "image/gif";
        if (u.endsWith(".webp")) return "image/webp";
        if (u.endsWith(".svg")) return "image/svg+xml";
        if (u.endsWith(".bmp")) return "image/bmp";
        if (u.endsWith(".avif")) return "image/avif";
        return null;
    }

    protected void copyText(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Bolt", s));
            Fx.toast(this, getString(R.string.copied));
        } catch (Throwable ignored) {}
    }

    /** WebView's WebResourceRequest does not expose the resource type, so infer it
     *  from the URL path extension (same approach as other WebView-based blockers).
     *
     *  v1.4.11 — THE ADBLOCK BLINDSPOT: extension-less endpoints. The old
     *  walk typed every URL without a file extension as OTHER, so `$script`
     *  rules never fired on the modern tracker shape — collection/beacon
     *  endpoints (/collect, /tag.js-less API paths, /beacon.gif-less POSTs)
     *  that serve JavaScript or are fetched AS scripts with no `.js` in the
     *  path. The request's Accept header now disambiguates the fall-through:
     *  a fetch that asks for image/* first is an IMAGE (an <img> tag / a CSS
     *  background), text/css first is a STYLESHEET, and a JavaScript MIME
     *  first is a SCRIPT. Only the FIRST (preferred) MIME in the list is
     *  trusted — jQuery-style XHRs (Accept: "application/json, text/javascript,
     *  star-slash-star") list text/javascript second and must NOT be typed
     *  SCRIPT (over-blocking JSON APIs would break sites); an extension in
     *  the path still wins over the header, keeping every shipped extension
     *  decision identical. */
    protected static FilterEngine.Type inferType(WebResourceRequest req) {
        if (req.isForMainFrame()) return FilterEngine.Type.DOCUMENT;
        String path = req.getUrl().getPath();
        String p = path == null ? "" : path.toLowerCase();
        if (p.endsWith(".js") || p.endsWith(".mjs")) return FilterEngine.Type.SCRIPT;
        if (p.endsWith(".css")) return FilterEngine.Type.STYLESHEET;
        if (p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg")
                || p.endsWith(".gif") || p.endsWith(".webp") || p.endsWith(".svg")
                || p.endsWith(".ico") || p.endsWith(".bmp") || p.endsWith(".avif")) {
            return FilterEngine.Type.IMAGE;
        }
        if (p.endsWith(".woff") || p.endsWith(".woff2") || p.endsWith(".ttf")
                || p.endsWith(".otf") || p.endsWith(".eot")) return FilterEngine.Type.FONT;
        if (p.endsWith(".mp4") || p.endsWith(".webm") || p.endsWith(".mp3")
                || p.endsWith(".m4a") || p.endsWith(".aac") || p.endsWith(".ogg")
                || p.endsWith(".wav") || p.endsWith(".m3u8") || p.endsWith(".ts")
                || p.endsWith(".mov")) return FilterEngine.Type.MEDIA;
        // v1.4.11: no extension signal — the Accept header's preferred MIME
        // says what the fetcher is (see the blindspot note above)
        String accept = null;
        try {
            java.util.Map<String, String> hs = req.getRequestHeaders();
            if (hs != null) {
                for (java.util.Map.Entry<String, String> e : hs.entrySet()) {
                    if ("accept".equalsIgnoreCase(e.getKey())) { accept = e.getValue(); break; }
                }
            }
        } catch (Throwable ignored) {}
        if (accept != null && !accept.isEmpty()) {
            String first = accept.split(",", 2)[0].split(";", 2)[0]
                    .trim().toLowerCase();
            if (first.startsWith("image/")) return FilterEngine.Type.IMAGE;
            if (first.equals("text/css")) return FilterEngine.Type.STYLESHEET;
            if (first.equals("application/javascript")
                    || first.equals("text/javascript")
                    || first.equals("application/ecmascript")
                    || first.equals("application/x-ecmascript")) {
                return FilterEngine.Type.SCRIPT;
            }
            if (first.startsWith("video/") || first.startsWith("audio/")) {
                return FilterEngine.Type.MEDIA;
            }
            if (first.startsWith("font/")) return FilterEngine.Type.FONT;
        }
        return FilterEngine.Type.OTHER;
    }

    // ------------------------------------------------------------ JS bridge

    /** Java bridge exposed to pages as window.Bolt. One instance per WebView
     *  (Tab.buildWeb registers it when the WebView is created); attach() pins
     *  the owning WebView so async callbacks (GM_xhr etc.) can always reach
     *  the right page even if the user switched tabs mid-request. */
    protected class JsBridge {
        private volatile WebView attached;

        public void attach(WebView w) {
            // v1.4.9: the UserscriptEngine.registerWeb(w) call is gone — it
            // fed a static WebView registry that nothing ever read (delivery
            // uses this bridge's own `attached` field), while the registry's
            // strong references pinned every destroyed tab's Activity
            // context in memory (the leak corpse)
            attached = w;
        }

        private void eval(String js) {
            WebView w = attached;
            if (w == null) {
                Tab t = activeTab();
                if (t != null) w = t.web;
            }
            final WebView fw = w;
            if (fw != null) ui.post(() -> {
                try { fw.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public String getMedia() {
            return MediaHook.mediaSettingsJson(prefs);
        }

        /** v1.3.87 — THE AI EXPORTER's page→Java handoff: the picker has
         *  marked the selected responses and put the page into export mode
         *  (AiExport.pickerJs); capture the CURRENT WebView (the `attached`
         *  pin, not the active tab — the user may switch tabs mid-pick),
         *  restore the page when it finishes, then toast + share. Runs on
         *  the JavaBridge thread; all UI work is posted. */
        @JavascriptInterface
        public void aiCapture(final String mode, final int count) {
            ui.post(() -> {
                try {
                    WebView w = attached;
                    if (w == null) {
                        Tab t = activeTab();
                        if (t != null) w = t.web;
                    }
                    if (w == null || count <= 0) return;
                    final WebView fw = w;
                    final boolean pdf = "pdf".equals(mode);
                    Fx.toast(BrowserActivity.this, pdf ? "Rendering PDF…" : "Capturing image…");
                    boolean barsWere = barsShown;
                    hideBarsNow(false);
                    com.bolt.browser.util.FullShot.barsVisible(BrowserActivity.this, false);
                    com.bolt.browser.util.Diag.log(getApplicationContext(),
                            "ai export " + (pdf ? "pdf" : "image") + " n=" + count);
                    com.bolt.browser.util.AiExport.capture(BrowserActivity.this, fw, pdf, r -> {
                        com.bolt.browser.util.FullShot.barsVisible(BrowserActivity.this, true);
                        if (barsWere) showBarsNow(false);
                        try {
                            fw.evaluateJavascript(
                                    com.bolt.browser.util.AiExport.restoreJs(), null);
                        } catch (Throwable ignored) {}
                        // v1.3.92 — the native picker ends with the capture
                        try { endAiPick(false); } catch (Throwable ignored) {}
                        if (r.ok) {
                            Fx.toast(BrowserActivity.this, "Saved to Downloads");
                            com.bolt.browser.util.AiExport.share(BrowserActivity.this, r.uri, pdf);
                        } else {
                            Fx.toast(BrowserActivity.this, r.msg);
                        }
                    });
                } catch (Throwable ignored) {}
            });
        }

        /** v1.3.92 — the picker's fast scroll sync: the page's capture-phase
         *  scroll listener (ANY scroller, throttled 120 ms) fires this so
         *  the native circles follow the conversation while it scrolls. */
        @JavascriptInterface
        public void aiSync() {
            ui.post(() -> {
                try {
                    if (aiPickLayer != null && aiWeb != null) aiSyncOnce();
                } catch (Throwable ignored) {}
            });
        }

        /** v1.3.20: copy the diagnostics log (renderer deaths, uncaught
         *  exceptions, app starts) to the clipboard — surfaced as a button on
         *  the "page kept crashing" notice, works from any page. */
        @JavascriptInterface
        public void copyDiag() {
            ui.post(() -> {
                try {
                    String log = com.bolt.browser.util.Diag.tail(BrowserActivity.this, 20000);
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("bolt-diag",
                            log.isEmpty() ? "(no diagnostics recorded)" : log));
                    Fx.toast(BrowserActivity.this, "Diagnostics copied to clipboard");
                } catch (Throwable t) {
                    try { Fx.toast(BrowserActivity.this, "Copy failed"); } catch (Throwable ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void mediaEvent(final String name, final String json) {
            ui.post(() -> {
                try {
                    // v1.3.13: forward EVERY media event to the service first —
                    // the service owns the notification lifecycle and keeps
                    // working while this activity is being torn down (zombie
                    // mode). Incognito runs in a separate process and its
                    // WebViews are not reachable from the main-process service,
                    // so no events are forwarded there.
                    // v1.3.67 — THE EXIT GATE's evidence line: the in-page gate
                    // swallowed the page's automatic exitFullscreen inside the
                    // PiP entry window (MediaHook.__boltGateExit, armed by
                    // PipVideo.pinJs's __boltpipAt stamp). One line per PiP
                    // session; n = swallowed calls so far. exitgate + NO 'fs
                    // hide inpip' = the mobile session took the DESKTOP path
                    // (custom view alive) — the intended v1.3.67 outcome.
                    if ("pipexit".equals(name)) {
                        try {
                            if (!fsPipExitSaid) {
                                fsPipExitSaid = true;
                                int n = 0;
                                try { n = new JSONObject(json).optInt("n", 0); }
                                catch (Throwable ignored) {}
                                com.bolt.browser.util.Diag.log(BrowserActivity.this,
                                        "fs pip exitgate n=" + n);
                            }
                        } catch (Throwable ignored) {}
                        return;
                    }
                    // v1.3.66 — the PiP play sentinel's evidence line: the
                    // in-page pipFight re-played against the mobile player's
                    // pause reversal (armed by PipVideo.pinJs's
                    // window.__boltpip; one line per PiP session — the 'n'
                    // is the try count, so the next report decodes whether
                    // the fight held or the bound gave out)
                    // v1.3.67 — the payload now carries the DECODER: js=1
                    // st=… = the pause's JS call stack (the pausing module,
                    // named — MediaHook's pause wrap recorded it while the
                    // session was armed); js=0 = NO JS caller = a renderer-
                    // side pause (a different bug class, pipWatch territory).
                    // v1.3.68 — THE js BIT FIX: the JS sends js as a NUMBER
                    // ({js: st?1:0}) and org.json's optBoolean does NOT
                    // coerce numbers — every real JS pause decoded as js=0
                    // in the v1.3.67 field log while the st= stack sat right
                    // beside it naming g.fZ.pause (player-plasma). Parse
                    // numerically, boolean tolerated.
                    if ("pipfight".equals(name)) {
                        try {
                            if (!fsPipFightSaid) {
                                fsPipFightSaid = true;
                                int n = 0;
                                boolean js = false;
                                String st = "";
                                try {
                                    JSONObject o = new JSONObject(json);
                                    n = o.optInt("n", 0);
                                    js = o.optInt("js", 0) != 0
                                            || o.optBoolean("js", false);
                                    st = o.optString("st", "");
                                } catch (Throwable ignored) {}
                                StringBuilder line = new StringBuilder("fs pip fight n=")
                                        .append(n).append(" js=").append(js ? 1 : 0);
                                if (st.length() > 0) {
                                    String t = st.replace("\n", " ")
                                            .replace("\r", " ").trim();
                                    if (t.length() > 300) t = t.substring(0, 300);
                                    line.append(" st=").append(t);
                                }
                                com.bolt.browser.util.Diag.log(BrowserActivity.this,
                                        line.toString());
                            }
                        } catch (Throwable ignored) {}
                        return;
                    }
                    // v1.3.68 — THE PAUSE GATE's evidence line: the in-page
                    // gate swallowed the mobile player's OWN page-side
                    // pause() of the active element (it never executed —
                    // the reversal became inaudible by construction). Same
                    // decoder payload as pipfight: js=1 st=… NAMES the
                    // pausing module even with the gate active (the next
                    // report's exact input), js=0 = no JS caller. One line
                    // per PiP session; n = swallows so far at report time
                    // (reported at 1, 2, 3, then every 20th).
                    if ("pipgate".equals(name)) {
                        try {
                            if (!fsPipGateSaid) {
                                fsPipGateSaid = true;
                                int n = 0;
                                boolean js = false;
                                String st = "";
                                try {
                                    JSONObject o = new JSONObject(json);
                                    n = o.optInt("n", 0);
                                    js = o.optInt("js", 0) != 0
                                            || o.optBoolean("js", false);
                                    st = o.optString("st", "");
                                } catch (Throwable ignored) {}
                                StringBuilder line = new StringBuilder("fs pip gate n=")
                                        .append(n).append(" js=").append(js ? 1 : 0);
                                if (st.length() > 0) {
                                    String t = st.replace("\n", " ")
                                            .replace("\r", " ").trim();
                                    if (t.length() > 300) t = t.substring(0, 300);
                                    line.append(" st=").append(t);
                                }
                                com.bolt.browser.util.Diag.log(BrowserActivity.this,
                                        line.toString());
                            }
                        } catch (Throwable ignored) {}
                        return;
                    }
                    if (!incognito) {
                        Tab evt = tabOf(attached);
                        MediaControlService.onMediaEvent(getApplicationContext(),
                                evt != null ? tm.tabs.indexOf(evt) : -1, name, json);
                    }
                    // v1.3.27 — position beats land on the TAB: the save path
                    // (TabManager.persistUrl) folds the last known position
                    // into a YouTube watch URL as &t=<sec>s, so a session
                    // killed mid-video reopens approximately where the music
                    // stopped instead of restarting the video from zero.
                    if ("time".equals(name)) {
                        try {
                            Tab pt = tabOf(attached);
                            if (pt != null) {
                                double pp = new JSONObject(json).optDouble("pos", -1);
                                if (pp >= 0) pt.mediaPosSec = pp;
                            }
                        } catch (Throwable ignored) {}
                        return;
                    }
                    // v1.3.61 — PSEUDO-FULLSCREEN (the page CSS-maximized its
                    // player without the Fullscreen API; see the watcher in
                    // MediaHook.core). Bolt's floating chrome must not ride
                    // the video: sweep the same surfaces the real fullscreen
                    // path sweeps. Restores the moment the state ends, on any
                    // tab switch, page load, or real fullscreen exit — and
                    // the scroll-up raw gesture still brings the bars back
                    // (the v1.3.51 toolbar return guarantee) while it lasts.
                    if ("pseudo".equals(name)) {
                        boolean on = "1".equals(json);
                        try {
                            com.bolt.browser.util.Diag.log(BrowserActivity.this,
                                    "fs pseudo " + (on ? "on" : "off"));
                        } catch (Throwable ignored) {}
                        if (on && customView == null && !inPip) {
                            pseudoFullscreen = true;
                            try { hideQuickPanel(); } catch (Throwable ignored) {}
                            try { hideOtpChip(); } catch (Throwable ignored) {}
                            try { hidePwChip(); } catch (Throwable ignored) {}
                            try { hideProfileChip(); } catch (Throwable ignored) {}
                            try {
                                if (fabMedia != null) fabMedia.setVisibility(View.GONE);
                            } catch (Throwable ignored) {}
                            try { hideBarsNow(false); } catch (Throwable ignored) {}
                        } else if (!on && pseudoFullscreen && customView == null && !inPip) {
                            pseudoFullscreen = false;
                            try {
                                if (fabMedia != null) fabMedia.setVisibility(
                                        mediaPlaying && (prefs.mediaBooster() || prefs.mediaSkip()) ? View.VISIBLE : View.GONE);
                            } catch (Throwable ignored) {}
                            try { showBarsNow(false); } catch (Throwable ignored) {}
                        }
                        return;
                    }
                    // v1.3.61 — evidence: the page's requestFullscreen was
                    // REJECTED (the "API never fired" failure class — the
                    // fs show line will be missing for that attempt)
                    if ("fserr".equals(name)) {
                        try {
                            com.bolt.browser.util.Diag.log(BrowserActivity.this,
                                    "fs err host=" + com.bolt.browser.util.Fx.hostOf(
                                            activeTab() == null ? "" : activeTab().url));
                        } catch (Throwable ignored) {}
                        return;
                    }
                    // v1.3.61 — HEAL-AT-PLAY: a NEW playing state is exactly
                    // when the player re-arms its fullscreen button — the
                    // phantom-heal moments of v1.3.60 (showTab +
                    // onPageFinished) all sit in the PAST of an SPA document
                    // that never reloads, so a phantom seeded after them (a
                    // PiP close mid-video, a task restore) survived until the
                    // next navigation. Debounced to one probe per 3 s.
                    if ("state".equals(name)) {
                        try {
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (now - lastFsHealAt > 3000L) {
                                lastFsHealAt = now;
                                healRendererFullscreen(attached, "play");
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (!"state".equals(name)) return;
                    JSONObject o = new JSONObject(json);
                    boolean playing = o.optBoolean("playing");
                    mediaBoostable = o.optBoolean("boostable");
                    // per-tab truth: remember WHICH tab is playing (notification
                    // commands target it, and it is the WebView that must survive
                    // backgrounding). The old code let any tab's report overwrite
                    // the one global flag — with a paused second tab open, the flag
                    // flipped false every second (service-stop races, wake-lock churn).
                    Tab mt = tabOf(attached);
                    if (mt != null) mt.mediaPlaying = playing;
                    // v1.3.27: state reports carry the position too — keep the
                    // tab's resume point fresh for the persist path (&t=…)
                    if (mt != null) {
                        double pp = o.optDouble("pos", -1);
                        if (pp >= 0) mt.mediaPosSec = pp;
                    }
                    // v1.3.16: track loaded-media tabs (playing OR paused with a
                    // real duration). onPause() keeps such WebViews alive so the
                    // notification's PLAY can still reach a live renderer — a
                    // Chromium-paused WebView ignores element.play() (the "play
                    // from notification does nothing" bug). 'none' reports carry
                    // dur=0 and clear the flag; every page load re-reports.
                    if (mt != null && (playing || o.optDouble("dur", 0) > 0)) {
                        mt.holdsMedia = true;
                    } else if (mt != null && o.optDouble("dur", 0) <= 0
                            && o.optDouble("pos", 0) <= 0) {
                        mt.holdsMedia = false;
                    }
                    // v1.3.20/v1.3.25 — renderer priority history: v1.3.19
                    // demoted the MEDIA tab's renderer to BOUND here, which was
                    // exactly backwards (BOUND is BELOW the default max — the
                    // "YouTube crashes at video load" regression; v1.3.20
                    // removed it). v1.3.25 takes the mirror-image approach,
                    // which is the only remaining headroom the platform offers:
                    // keep the media tab at the never-waived maximum and make
                    // every OTHER hidden tab yield (waived-when-hidden) while
                    // media is live — hidden renderers get reclaimed first under
                    // pressure, dedicating that RAM to the media renderer. The
                    // default policy stays in effect whenever nothing holds
                    // media. See applyRendererPolicies().
                    applyRendererPolicies("media");
                    if (playing && mt != null) mediaTab = mt;
                    boolean any = anyTabMediaPlaying();
                    if (any != mediaPlaying) {
                        mediaPlaying = any;
                        // the floating media button is the booster's control surface:
                        // with the in-page booster toggle OFF it must not appear
                        // (userscripts provide their own floating panel then).
                        // v1.3.66 — NEVER inside the PiP window or under a
                        // fullscreen custom view: the state flip is exactly
                        // when the mobile site pauses on the fullscreen exit
                        // and the user re-plays in the floating window — the
                        // unguarded re-show painted the FAB into the shrunken
                        // activity (the v1.3.65 report: "the built in voice
                        // booster floating button makes it into the pip view").
                        fabMedia.setVisibility(
                                any && (prefs.mediaBooster() || prefs.mediaSkip()) && !inPip
                                        && customView == null
                                        ? View.VISIBLE : View.GONE);
                        // v1.3.38: auto-PiP eligibility flipped with it
                        updatePipParams();
                        // v1.3.65: the PiP play/pause icon follows the
                        // playing state while the window is up
                        if (inPip) updatePipActions();
                    }
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public void inspectorPick(final String json) {
            ui.post(() -> showInspectorDialog(json));
        }

        /** v1.3.31 — the page's OTP-input focus report from MediaHook's
         *  detector: focus on a 2FA input (true) offers the matching
         *  authenticator code as a fill chip; blur (false) retires it. */
        @JavascriptInterface
        public void otpField(final boolean focused) {
            ui.post(() -> {
                try {
                    if (focused) showOtpChip(); else hideOtpChip();
                } catch (Throwable ignored) {}
            });
        }

        /** v1.3.38 — password vault: a login form SUBMIT carries
         *  host/user/password (the save offer), and focusing a password
         *  field asks the vault for a fill chip. In-process bridge only —
         * nothing leaves the device. Incognito ignores both by design. */
        @JavascriptInterface
        public void pwSubmit(final String host, final String user, final String pass) {
            if (incognito) return;
            ui.post(() -> maybeOfferSavePw(host, user, pass));
        }

        @JavascriptInterface
        public void pwField(final String host, final boolean focused) {
            // v1.3.91 — the page's OWN host rides the focus report (an
            // SSO/embedded login form can live on an origin the tab URL
            // never shows; the fill lookup keyed on the tab host missed it
            // = "Bolt asks to save but never fills")
            if (incognito) return;
            ui.post(() -> {
                try {
                    if (focused) showPwChip(host); else hidePwChip();
                } catch (Throwable ignored) {}
            });
        }

        /** v1.3.79 — THE UNIVERSAL FAVICON PASS. The page rasterized one of
         *  its own DOM icon candidates (SVG included — BitmapFactory can
         *  never read those, which is why z.ai's logo.svg never landed) and
         *  hands the PNG over with ITS OWN hostname (the page is the one
         *  source of truth for where it is — no getUrl() race, no view
         *  attribution). Never in incognito. */
        @JavascriptInterface
        public void favIcon(final String host, final String dataUrl) {
            if (incognito) return;
            try {
                com.bolt.browser.util.FaviconStore.storePngDataUrl(
                        getApplicationContext(), host, dataUrl);
            } catch (Throwable ignored) {}
        }

        /** the extraction found nothing it could rasterize — mark the host
         *  tried so the injection does not repeat this session. */
        @JavascriptInterface
        public void favMiss(final String host) {
            if (incognito) return;
            try {
                com.bolt.browser.util.FaviconStore.markDomTried(host);
            } catch (Throwable ignored) {}
        }

        /** v1.3.39 — PROFILES (menu-3 #13): a form-ish field got focus →
         *  offer the profile chip; blur retires it. */
        @JavascriptInterface
        public void formField(final boolean focused) {
            if (incognito) return;
            ui.post(() -> {
                try {
                    if (focused) showProfileChip(); else hideProfileChip();
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public void notify(final String msg) {
            ui.post(() -> Fx.toast(BrowserActivity.this, msg));
        }

        @JavascriptInterface
        public void copy(final String text) {
            ui.post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Bolt", text));
                    Fx.toast(BrowserActivity.this, getString(R.string.copied));
                } catch (Throwable ignored) {}
            });
        }

        /** v1.3.58 — the PAGE's own Copy button, any frame (FrameHooks
         *  clipboard shim → Bolt.clipPage directly, or the copyText relay
         *  from a bridge-less frame). Same clipboard write + "Copied" toast
         *  as copy(), plus the field-evidence diag line the next report
         *  needs: `clip page n=<chars> via=direct|relay` — its presence
         *  proves the shim is installed and reached; its absence proves
         *  the button never called writeText in any covered frame. */
        @JavascriptInterface
        public void clipPage(final String text, final String via) {
            try {
                com.bolt.browser.util.Diag.log(getApplicationContext(), "clip page n="
                        + (text == null ? 0 : text.length())
                        + ("relay".equals(via) ? " via=relay" : " via=direct"));
            } catch (Throwable ignored) {}
            ui.post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Bolt", text));
                    Fx.toast(BrowserActivity.this, getString(R.string.copied));
                } catch (Throwable ignored) {}
            });
        }

        /** v1.3.58 — per-stage trace from the blob-download chain
         *  (BlobDownload.kt v3): `blobdl ask|frame|fetch|ownimg|hunt|
         *  anchor|allmiss` — with `dl offer` / `blobdl start` / `gmSaveBlob`
         *  this makes the next "download fails" report name the exact
         *  dead step in one look. */
        @JavascriptInterface
        public void dlTrace(final String s) {
            try {
                com.bolt.browser.util.Diag.log(getApplicationContext(),
                        "blobdl " + (s == null ? "" : s));
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void openTab(final String url) {
            ui.post(() -> openNewTab(url));
        }

        // ---------------- userscript (GM_*) support ----------------

        /** whole per-script storage map as JSON (sync return) */
        @JavascriptInterface
        public String gmValues(String uuid) {
            return UserscriptEngine.gmValues(uuid);
        }

        @JavascriptInterface
        public void gmSet(String uuid, String key, String valueJson) {
            UserscriptEngine.gmSet(getApplicationContext(), uuid, key, valueJson);
        }

        @JavascriptInterface
        public void gmDelete(String uuid, String key) {
            UserscriptEngine.gmDelete(uuid, key);
        }

        @JavascriptInterface
        public String gmResource(String uuid, String name) {
            return UserscriptEngine.resourceText(uuid, name);
        }

        /** GM_xmlhttpRequest: executed off-thread, events delivered via
         *  window.__boltXhrEvent(id, {...}) back into the calling page. */
        @JavascriptInterface
        public void gmXhr(final String json) {
            UserscriptEngine.performXhr(getApplicationContext(), json, this::eval);
        }

        /** GM_xmlhttpRequest abort (from the returned object's abort()). */
        @JavascriptInterface
        public void gmXhrAbort(final String id) {
            UserscriptEngine.gmXhrAbort(id);
        }

        /** GM_download */
        @JavascriptInterface
        public void gmDownload(final String json) {
            try {
                JSONObject o = new JSONObject(json);
                final String url = o.optString("url");
                String nm = o.optString("name");
                if (nm == null || nm.isEmpty())
                    nm = android.webkit.URLUtil.guessFileName(url, null, null);
                final String name = nm;
                JSONObject h = o.optJSONObject("headers");
                final String ua = h == null ? "" : h.optString("User-Agent", h.optString("user-agent", ""));
                ui.post(() -> {
                    // v1.4.16 — THE SCHEME ROUTER: GM_download used to feed
                    // every URL straight to DownloadEngine.enqueue, so a
                    // userscript handing over a blob:/data: URL died in
                    // HttpURLConnection (blob:) or as a raw enqueue (data:)
                    // — the exact "fails via HttpURLConnection, defaults to
                    // .bin" corpse. enqueueDownload owns the routing law:
                    // data: -> saveDataUrl, blob: -> the JS-bridge rescue,
                    // http(s) -> the engine. Same door for every caller.
                    enqueueDownload(url, name, o.optString("mime", ""), ua);
                });
            } catch (Throwable ignored) {}
        }

        /** in-page fetched blob/data image save (long-press download).
         *  v1.4.20 — THE ASK-EVERY-TIME ROUTER RIDES THE BRIDGE: every
         *  rescue caller (the blob chain, the long-press fetch rescue)
         *  delivers here unchanged — the v1.3.58 contract — and the pref
         *  picks the door: Ask opens the rename + destination dialog
         *  with the rescued bytes (name + approx size from the data
         *  URL), Direct saves at once with the toast. */
        @JavascriptInterface
        public void gmSaveBlob(final String name, final String mime, final String dataUrl) {
            // v1.3.57 — field evidence: bytes came back from a page-side
            // rescue (length of the data URL ≈ 4/3 × the payload)
            try {
                com.bolt.browser.util.Diag.log(getApplicationContext(), "gmSaveBlob "
                        + (dataUrl == null ? 0 : dataUrl.length()) + "B name=" + name);
            } catch (Throwable ignored) {}
            ui.post(() -> {
                if (dataUrl == null || !dataUrl.startsWith("data:")) return;
                if (DownloadEngine.dlDialogRoute(prefs.downloadAsk(), dataUrl)) {
                    int comma = dataUrl.indexOf(',');
                    long approx = (long) ((dataUrl.length() - Math.max(0, comma)) * 3L / 4L);
                    showDownloadDialog(dataUrl, null, null,
                            (mime == null || mime.isEmpty()) ? null : mime, approx, name);
                } else {
                    DownloadEngine.get(BrowserActivity.this).saveDataUrl(dataUrl, name, mime);
                    ensureDlShelfListener();   // v1.3.40 download shelf
                    Fx.toast(BrowserActivity.this, getString(R.string.download_started) + ": " + name);
                }
            });
        }

        /** GM_notification */
        @JavascriptInterface
        public void gmNotify(final String title, final String text) {
            ui.post(() -> {
                try {
                    androidx.core.app.NotificationCompat.Builder b =
                            new androidx.core.app.NotificationCompat.Builder(BrowserActivity.this,
                                    com.bolt.browser.BoltApp.CH_DL)
                                    .setSmallIcon(R.drawable.ic_notify_script)
                                    .setContentTitle(title == null || title.isEmpty()
                                            ? "Userscript" : title)
                                    .setContentText(text == null ? "" : text)
                                    .setAutoCancel(true);
                    androidx.core.app.NotificationManagerCompat.from(BrowserActivity.this)
                            .notify(900000 + (int) (System.currentTimeMillis() % 99000), b.build());
                } catch (Throwable ignored) {}
            });
        }

        /** GM_registerMenuCommand — surfaced in the browser's 3-dot menu */
        @JavascriptInterface
        public void gmMenuCmd(final String name) {
            ui.post(() -> {
                if (name != null && !name.isEmpty() && !scriptMenuCmds.contains(name))
                    scriptMenuCmds.add(name);
            });
        }
    }

    // ------------------------------------------------------------ download-complete popup (v1.3.19)

    /** engine callback (main thread): a download just finished. Ask the user
     *  now if the browser is visible; otherwise queue for the next resume. */
    protected void onDownloadComplete(DownloadEngine.Item it) {
        if (it == null || it.status != DownloadEngine.ST_DONE) return;
        boolean busy = activityBackground || isFinishing()
                || (dlPopup != null && dlPopup.isShowing());
        if (!busy) {
            showDlPopup(it);
        } else if (dlPopupQueue.size() < 5) {   // bounded backlog; notification covers the rest
            dlPopupQueue.add(it);
        }
    }

    /** pop the next queued completion (onResume + dialog-dismiss chain). */
    protected void showQueuedDlPopups() {
        if (dlPopup != null && dlPopup.isShowing()) return;
        if (dlPopupQueue.isEmpty()) return;
        showDlPopup(dlPopupQueue.remove(0));
    }

    protected void showDlPopup(final DownloadEngine.Item it) {
        try {
            AlertDialog d = new AlertDialog.Builder(BrowserActivity.this)
                    .setTitle(R.string.download_complete)
                    .setMessage(it.name == null ? "" : it.name)
                    .setPositiveButton(R.string.open_file, (dlg, w) -> openDownloadedFile(it))
                    .setNegativeButton(R.string.cancel, null)
                    .create();
            d.setOnDismissListener(dl -> {
                dlPopup = null;
                // v1.3.59: the complete-dialog counts as HANDLED either way —
                // Open OR Cancel retires the matching shelf chip, so the
                // bottom popup never lingers past the dialog that answered it
                try {
                    dlShelfDismissed.add(it.id);
                    updateDlShelf();
                } catch (Throwable ignored) {}
                showQueuedDlPopups();   // chain through a backlog one by one
            });
            d.show();
            dlPopup = d;
        } catch (Throwable ignored) {}
    }

    protected void openDownloadedFile(DownloadEngine.Item it) {
        try {
            Intent i = DownloadEngine.get(this).openFileIntent(it);
            if (i == null) {
                Fx.toast(this, "Can't open this file type");
                return;
            }
            startActivity(i);
        } catch (Throwable e) {
            Fx.toast(this, "No app can open this file");
        }
    }

    // ------------------------------------------------------------ media controls

    /** live aggregation across all tabs — the single source of truth for
     *  "is any tab playing media" (replaces last-report-wins) */
    protected boolean anyTabMediaPlaying() {
        synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
            for (Tab t : tm.tabs) {
                if (t.mediaPlaying) return true;
            }
        }
        return false;
    }

    // (v1.3.13) stopMediaSvc / updateMediaService / onCommand are GONE:
    // the media service now receives events straight from the page bridge and
    // routes commands itself — no activity-side listener, no service-state
    // pushing, no notification churn. The service survives this activity.

    protected void setupFabDrag() {
        fabMedia.setOnTouchListener(new View.OnTouchListener() {
            float dX, dY; boolean moved;
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        dX = v.getX() - e.getRawX();
                        dY = v.getY() - e.getRawY();
                        moved = false;
                        return false; // let click work if no move
                    case android.view.MotionEvent.ACTION_MOVE:
                        float nx = e.getRawX() + dX, ny = e.getRawY() + dY;
                        if (Math.abs(nx - v.getX()) > 8 || Math.abs(ny - v.getY()) > 8) moved = true;
                        if (moved) {
                            v.setX(Math.max(0, Math.min(flRoot.getWidth() - v.getWidth(), nx)));
                            v.setY(Math.max(0, Math.min(flRoot.getHeight() - v.getHeight(), ny)));
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }
        });
    }

    protected void showMediaSheet() {
        android.view.View lay = getLayoutInflater().inflate(R.layout.sheet_media, null);
        final AlertDialog d = new AlertDialog.Builder(this).setView(lay).create();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        android.view.Window win = d.getWindow();
        SeekBarHelper sbSpeed = new SeekBarHelper();
        TextView tvSpeed = lay.findViewById(R.id.tv_speed_val);
        TextView tvVol = lay.findViewById(R.id.tv_volume_val);
        android.widget.SeekBar seekSpeed = lay.findViewById(R.id.sb_speed);
        android.widget.SeekBar seekVol = lay.findViewById(R.id.sb_volume);
        // v1.3.34: the modern switch replaces the Holo-era android.widget.Switch
        android.view.ViewGroup vocalSlot = lay.findViewById(R.id.fl_vocal_slot);
        com.bolt.browser.util.Ui.ModernSwitch swVocal =
                com.bolt.browser.util.Ui.modernSwitch(this, prefs.vocalOn(), on -> {
                    prefs.setVocalOn(on);
                    evalMedia("window.__boltCmd&&window.__boltCmd('vocal'," + on + ")");
                });
        vocalSlot.addView(swVocal);
        // v1.3.79 — THE SILENCE SKIPPER switch: the media FAB is Bolt's
        // VIDEO CONTROL button now — booster and skip are two independent
        // toggles on the same sheet ("I can toggle on either the booster or
        // skip silence or both")
        try {
            android.view.ViewGroup skipSlot = lay.findViewById(R.id.fl_skip_slot);
            com.bolt.browser.util.Ui.ModernSwitch swSkip =
                    com.bolt.browser.util.Ui.modernSwitch(this, prefs.mediaSkip(), on -> {
                        prefs.setMediaSkip(on);
                        evalMedia("window.__boltCmd&&window.__boltCmd('skip'," + on + ")");
                        // v1.3.87 — the factor is a SETTING now (Prefs
                        // skipFactor, default 3, range 1-8); the toast
                        // quotes the live value (the v1.3.85 lesson: the
                        // label must never lie about the engine)
                        if (on) Fx.toast(this, getString(
                                R.string.skip_silence_on_toast, prefs.skipFactor()));
                    });
            skipSlot.addView(swSkip);
        } catch (Throwable ignored) {}
        // v1.3.87 — THE TUNING ROW: speed 1x-8x, start-after, rewind and
        // ignore-short-silences, one tap under the toggle (SkipTune.kt is
        // the shared sheet; the push pattern is the toggle's own)
        try {
            TextView tune = lay.findViewById(R.id.tv_skip_tune);
            if (tune != null) tune.setOnClickListener(v ->
                    com.bolt.browser.util.SkipTune.sheet(BrowserActivity.this, prefs, () ->
                            pushMediaSettingsToTabs()));
        } catch (Throwable ignored) {}

        float speed = prefs.lastSpeed();
        int vol = prefs.lastVolume();
        seekSpeed.setMax(39);
        seekSpeed.setProgress((int) (speed * 10) - 1);
        tvSpeed.setText(String.format(Locale.US, "%.1fx", speed));
        seekVol.setProgress(vol - 100);
        tvVol.setText(vol + "%");
        if (!mediaBoostable) tvVol.setText(vol + "% (n/a)");

        seekSpeed.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                float v = Math.max(0.1f, (p + 1) / 10f);
                tvSpeed.setText(String.format(Locale.US, "%.1fx", v));
                prefs.setLastSpeed(v);
                evalMedia("window.__boltCmd&&window.__boltCmd('speed'," + v + ")");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        seekVol.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                int v = 100 + p;
                tvVol.setText(mediaBoostable ? v + "%" : v + "% (n/a)");
                prefs.setLastVolume(v);
                evalMedia("window.__boltCmd&&window.__boltCmd('vol'," + v + ")");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        d.show();
    }

    protected void evalMedia(String js) {
        // target the tab actually playing media (the in-app media sheet is the
        // booster surface — applying speed/volume to a silent foreground tab
        // while a background tab plays did nothing, which felt broken)
        Tab t = (mediaTab != null && mediaTab.web != null && tm.tabs.contains(mediaTab))
                ? mediaTab : activeTab();
        if (t.web != null) {
            try { t.web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
        }
    }

    /** v1.3.87 — broadcast the current media settings JSON into every live
     *  tab (the SkipTune sheet applies knobs that the old 'skip' command
     *  never carried; __boltSetMedia is the full-JSON path the settings
     *  screens already used). */
    protected void pushMediaSettingsToTabs() {
        try {
            String js = "window.__boltSetMedia && window.__boltSetMedia('"
                    + com.bolt.browser.media.MediaHook.mediaSettingsJson(prefs)
                            .replace("'", "\\'") + "')";
            synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
                for (Tab t : tm.tabs) {
                    if (t.web != null) {
                        try { t.web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static class SeekBarHelper {}

    // ------------------------------------------------------------ home view (NTP)

    protected void buildHomeView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int txt = Fx.txt(this);
        int dim = Fx.dim(this);

        // v1.3.37 — CUSTOM WALLPAPER (normal profile only): the saved photo
        // fills the window edge to edge. v1.3.38 FIELD FIX ("the image added
        // as background seems very faded, I want it to be same"): the fixed
        // 60% scrim is GONE — dim now defaults to 0 (the photo renders
        // exactly as picked) and dim/blur/zoom/pan live in Appearance →
        // Start page → Wallpaper → Adjust. The bars float OVER the content,
        // so the photo is genuinely full-bleed.
        if (!incognito && prefs.homeWallpaper()) {
            Drawable wp = homeWallpaperDrawable();
            if (wp != null) root.setBackground(wp);
        }

        // whole home content scrolls once the shortcut grid fills up
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        android.widget.LinearLayout.LayoutParams slp =
                new android.widget.LinearLayout.LayoutParams(-1, -1);
        scroll.setLayoutParams(slp);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        // v1.3.33: the chrome bars now FLOAT over the content — pad the home
        // column clear of them (58dp top bar + 54dp bottom bar).
        // v1.3.36: the paddings follow the bars' ACTUAL sides when the
        // address bar is docked at the bottom (nav 54 top, url 58 bottom).
        int topPad = urlBarAtBottom() ? 54 : 58;
        int bottomPad = urlBarAtBottom() ? 58 : 54;
        content.setPadding(Fx.dp(this, 16), Fx.dp(this, 40) + Fx.dp(this, topPad),
                Fx.dp(this, 16), Fx.dp(this, 24) + Fx.dp(this, bottomPad));
        scroll.addView(content);

        if (incognito) {
            LinearLayout banner = new LinearLayout(this);
            banner.setOrientation(LinearLayout.VERTICAL);
            banner.setPadding(Fx.dp(this, 18), Fx.dp(this, 18), Fx.dp(this, 18), Fx.dp(this, 18));
            banner.setBackground(cardBg());
            ImageView ic = new ImageView(this);
            ic.setImageResource(R.drawable.ic_incognito);
            ic.setColorFilter(Fx.accentText(this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            banner.addView(ic, lp);
            TextView ht = new TextView(this);
            ht.setText(R.string.incognito);
            ht.setTextColor(Fx.accentText(this));
            ht.setTextSize(17);
            ht.setGravity(Gravity.CENTER);
            ht.setPadding(0, Fx.dp(this, 8), 0, 0);
            banner.addView(ht);
            TextView hint = new TextView(this);
            hint.setText(R.string.incognito_hint);
            hint.setTextColor(dim);
            hint.setTextSize(13);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, Fx.dp(this, 6), 0, 0);
            banner.addView(hint);
            content.addView(banner, new LinearLayout.LayoutParams(-1, -2));
            View sp = new View(this);
            content.addView(sp, new LinearLayout.LayoutParams(1, Fx.dp(this, 24)));
        }

        // logo + name — v1.3.79 THE CUSTOMIZABLE LOGO: 0 = the Bolt mark,
        // 1 = blank (no logo, no name — the clean wallpaper look), 2 = a
        // custom image (filesDir/home_logo.png, picked from the gallery,
        // alpha kept via PNG). Long-press the logo to change it any time.
        int logoMode = incognito ? 0 : prefs.homeLogo();
        if (logoMode != 1) {
            ImageView logo = new ImageView(this);
            android.graphics.Bitmap custom = null;
            if (logoMode == 2) {
                try {
                    java.io.File lf = prefs.homeLogoFile();
                    if (lf.exists()) custom = android.graphics.BitmapFactory.decodeFile(lf.getPath());
                } catch (Throwable ignored) {}
                if (custom == null) logoMode = 0;   // missing file -> default
            }
            if (logoMode == 2 && custom != null) {
                logo.setImageBitmap(custom);
                logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            } else {
                logo.setImageResource(R.mipmap.ic_launcher);
            }
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Fx.dp(this, 52), Fx.dp(this, 52));
            llp.gravity = Gravity.CENTER_HORIZONTAL;
            llp.bottomMargin = Fx.dp(this, 4);
            content.addView(logo, llp);
            pressFx(logo);
            logo.setOnLongClickListener(v -> { showHomeLogoPicker(); return true; });
            TextView name = new TextView(this);
            name.setText("Bolt");
            name.setTextColor(Fx.accentText(this));
            name.setTextSize(24);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setGravity(Gravity.CENTER);
            content.addView(name, new LinearLayout.LayoutParams(-1, -2));
        }

        // search pill
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(getDrawable(R.drawable.bg_urlbar));
        pill.setPadding(Fx.dp(this, 16), 0, Fx.dp(this, 16), 0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, Fx.dp(this, 46));
        plp.topMargin = Fx.dp(this, 16);
        plp.bottomMargin = Fx.dp(this, 22);
        content.addView(pill, plp);
        ImageView si = new ImageView(this);
        si.setImageResource(R.drawable.ic_search);
        si.setColorFilter(dim);
        pill.addView(si, new LinearLayout.LayoutParams(Fx.dp(this, 20), Fx.dp(this, 20)));
        TextView stv = new TextView(this);
        stv.setHint(R.string.search_hint);
        stv.setHintTextColor(dim);
        stv.setTextColor(txt);
        stv.setTextSize(16);
        stv.setPadding(Fx.dp(this, 10), 0, 0, 0);
        pill.addView(stv, new LinearLayout.LayoutParams(0, -2, 1f));
        // v1.3.38 — the home-pill mic (voice search, menu item 2): speak →
        // instant search through the same loadInTab flow as typing.
        if (!incognito) {
            try {
                ImageView mic = new ImageView(this);
                mic.setImageResource(R.drawable.ic_mic);
                mic.setColorFilter(dim);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                        Fx.dp(this, 22), Fx.dp(this, 22));
                mlp.rightMargin = Fx.dp(this, 8);
                pill.addView(mic, mlp);
                mic.setOnClickListener(v -> startVoiceSearch(1011));
            } catch (Throwable ignored) {}
        }
        // v1.3.36: press feedback on the search pill (the modern-tiles pass
        // also touched the home screen — every tappable now answers touch)
        pressFx(pill);
        pill.setOnClickListener(v -> {
            showOmnibox();
        });

        // v1.3.92 — THE 6x7 PAGED MATRIX (the field spec: "I want a 6*7
        // matrix of the icons filling left to right. When it goes over
        // 6*7, make the whole matrix able to be swiped horizontally to
        // access more icons by scrolling right or left with option to
        // customise right or left for left handed and right handed
        // person"). The centered-LinearLayout-rows grid (unlimited
        // vertical scroll, half-slot gap preview) is GONE: a ViewPager2
        // holds one SdGridLayout page per 42 tiles — 6 columns x 7 rows,
        // filled left-to-right — and the handedness setting flips the
        // pager's layout direction so the first page sits on the RIGHT
        // for left-handed users (pages extend leftward). The pages are
        // swiped horizontally like an Android home screen; during a drag,
        // resting the finger at the pager's edge turns the page (see
        // sdEdgeTurn). The grid keeps its fixed 7-row height; the rest of
        // the home (logo, pill, suggested) scrolls as before.
        prefs.ensureSpeeddial();
        org.json.JSONArray sd = prefs.speeddial();
        int n = sd.length();
        homeScroll = scroll;
        sdCells.clear();
        final int perPage = 42;   // 6 cols x 7 rows
        int pageCount = Math.max(1, (n + 1 + perPage - 1) / perPage);   // + the plus tile
        androidx.viewpager2.widget.ViewPager2 pager =
                new androidx.viewpager2.widget.ViewPager2(this);
        sdPager = pager;
        pager.setOffscreenPageLimit(2);   // neighbor pages stay attached (drag hit-tests + no churn)
        if (prefs.b("sdPagesRtl", false)) {
            pager.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        } else {
            pager.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }
        pager.setAdapter(new androidx.recyclerview.widget.RecyclerView
                .Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            @Override
            public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(
                    android.view.ViewGroup parent, int viewType) {
                SdGridLayout g = new SdGridLayout(BrowserActivity.this);
                // v1.3.94 — THE PAGER LAW (field crash: "Pages must fill the
                // whole ViewPager2"). ViewPager2's attach listener REQUIRES
                // every page's RecyclerView.LayoutParams to be (-1,-1) — a
                // bare-created view gets LinearLayoutManager's default
                // generateDefaultLayoutParams() = (-2,-2) WRAP_CONTENT (decompiled:
                // bipush -2, bipush -2) and the VERY FIRST page attach throws
                // IllegalStateException. This crashed EVERY home view build
                // since v1.3.92 (the home renders lazily — only when the
                // current tab IS the home — so it read as "refuses to open a
                // new tab"; after the v1.3.93 import restored a home-current
                // tab state it became a startup crash loop).
                g.setLayoutParams(new androidx.recyclerview.widget.RecyclerView
                        .LayoutParams(-1, -1));
                return new androidx.recyclerview.widget.RecyclerView.ViewHolder(g) {};
            }
            @Override
            public void onBindViewHolder(
                    androidx.recyclerview.widget.RecyclerView.ViewHolder h, int pos) {
                SdGridLayout g = (SdGridLayout) h.itemView;
                g.setPage(pos);
            }
            @Override
            public int getItemCount() {
                return pageCount;
            }
            @Override
            public long getItemId(int pos) { return pos; }
            @Override
            public void onViewAttachedToWindow(
                    androidx.recyclerview.widget.RecyclerView.ViewHolder h) {
                super.onViewAttachedToWindow(h);
                // the visible page's cells are the live hit-test set
                try {
                    if (h.itemView instanceof SdGridLayout) {
                        SdGridLayout g = (SdGridLayout) h.itemView;
                        if (g.pageIndex == pager.getCurrentItem()) rebuildSdCells();
                    }
                } catch (Throwable ignored) {}
            }
        });
        pager.registerOnPageChangeCallback(
                new androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int pos) {
                super.onPageSelected(pos);
                rebuildSdCells();
            }
        });
        // v1.3.97 — POSITION-TRUE REBUILD (see refreshHomeQuiet): a drag-driven
        // rebuild lands back on the page the finger edited, not page 0. The
        // snapshot is consumed once (a plain build/entrance leaves it at -1).
        if (homeRestorePage >= 0 && homeRestorePage < pageCount) {
            final int rp = homeRestorePage;
            pager.post(() -> {
                try {
                    pager.setCurrentItem(rp, false);
                    rebuildSdCells();
                } catch (Throwable ignored) {}
            });
        }
        homeRestorePage = -1;
        LinearLayout.LayoutParams pagerLp = new LinearLayout.LayoutParams(-1, Fx.dp(this, 82) * 7);
        content.addView(pager, pagerLp);
        // v1.3.44 — the launcher entrance: tiles pop in with a staggered
        // spring (debounced so favicon refreshes don't replay it)
        pager.post(() -> animateHomeGrid(pager));

        // v1.3.36 — SUGGESTED SITES: when the speed dial is still sparse, the
        // most-visited hosts from history appear as one-tap chips below the
        // grid. v1.3.38 FIELD FIX ("Remove the suggested sites an customize
        // from homepage to settings"): the row is OFF by default now — it
        // reads as clutter on a wallpaper home — and lives under
        // Appearance → Start page → "Suggested sites" for whoever wants it
        // back.
        try {
            if (!incognito && prefs.b("suggestedSites", false) && n < 6) {
                addSuggestedSites(content, sd);
            }
        } catch (Throwable ignored) {}

        // v1.3.38 FIELD FIX: the Customize chip is GONE from the home itself
        // (the wallpaper now stays exactly as picked — the whole point of
        // the fix — and its controls live in Settings → Appearance → Start
        // page). Long-pressing the empty home stays as the invisible
        // accelerator (the ScrollView must be explicitly long-clickable —
        // it consumes touches by being scrollable, so a parent's long-click
        // would never see them).
        if (!incognito) {
            try {
                scroll.setLongClickable(true);
                scroll.setOnLongClickListener(v -> { showWallpaperSheet(); return true; });
            } catch (Throwable ignored) {}
        }
        root.addView(scroll);
        homeView = root;
        // v1.3.97 — POSITION-TRUE REBUILD, scroll half: same page AND same
        // vertical offset the finger left (consumed once; a plain build
        // starts at the top as before).
        if (homeRestoreScroll > 0) {
            final int ry = homeRestoreScroll;
            scroll.post(() -> { try { scroll.scrollTo(0, ry); } catch (Throwable ignored) {} });
        }
        homeRestoreScroll = 0;
        homeShownGen = Fx.homeGen;   // v1.3.53: this build is current (see the field comment)
    }

    /** v1.3.36 — the suggested-sites row: horizontal chips of the user's
     *  most-visited hosts (not already pinned), tap = open, long-press =
     *  pin to the speed dial. */
    protected void addSuggestedSites(LinearLayout content, org.json.JSONArray sd) {
        java.util.HashMap<String, int[]> freq = new java.util.HashMap<>();   // host -> {count, lastIdx}
        java.util.HashSet<String> pinned = new java.util.HashSet<>();
        for (int i = 0; i < sd.length(); i++) {
            org.json.JSONObject o = sd.optJSONObject(i);
            if (o != null && !o.optBoolean("group", false))
                pinned.add(Fx.hostOf(o.optString("url")));
        }
        org.json.JSONArray hist = prefs.history();
        for (int i = hist.length() - 1; i >= 0; i--) {
            org.json.JSONObject o = hist.optJSONObject(i);
            if (o == null) continue;
            String u = o.optString("url");
            if (!u.startsWith("http")) continue;
            String host = Fx.hostOf(u);
            if (host.isEmpty() || pinned.contains(host)) continue;
            int[] f = freq.get(host);
            if (f == null) freq.put(host, new int[]{1, i});
            else { f[0]++; }
        }
        // rank: visits first, recency as the tie-break (history index grows
        // toward newer entries — larger lastIdx = seen more recently)
        List<String[]> ranked = new ArrayList<>();
        for (java.util.Map.Entry<String, int[]> e : freq.entrySet()) {
            if (e.getValue()[0] >= 2 || freq.size() < 6)
                ranked.add(new String[]{e.getKey(),
                        String.valueOf(e.getValue()[0] * 1000000L + e.getValue()[1])});
        }
        java.util.Collections.sort(ranked, (a, b) -> Long.compare(
                Long.parseLong(b[1]), Long.parseLong(a[1])));
        if (ranked.isEmpty()) return;
        TextView cap = new TextView(this);
        cap.setText(getString(R.string.suggested_sites));
        cap.setTextSize(13);
        cap.setTypeface(com.bolt.browser.util.Ui.medium());
        cap.setTextColor(Fx.dim(this));
        cap.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Fx.dp(this, 20);
        clp.bottomMargin = Fx.dp(this, 8);
        content.addView(cap, clp);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        int cp = Fx.dp(this, 14), cv = Fx.dp(this, 9);
        for (int i = 0; i < Math.min(8, ranked.size()); i++) {
            final String host = ranked.get(i)[0];
            TextView chip = new TextView(this);
            chip.setText(host);
            chip.setTextSize(14);
            chip.setTypeface(com.bolt.browser.util.Ui.medium());
            chip.setTextColor(Fx.txt(this));
            chip.setPadding(cp, cv, cp, cv);
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(Fx.dp(this, 999));
            g.setColor(Fx.surface2(this));
            g.setStroke(Math.max(1, Fx.dp(this, 1)), Fx.outline(this));
            chip.setBackground(g);
            chip.setOnClickListener(v -> loadInTab("https://" + host));
            chip.setOnLongClickListener(v -> {
                try {
                    prefs.addSpeeddial(host, "https://" + host);
                    refreshHome();
                    com.bolt.browser.util.Ui.tap(v);
                    Fx.toast(this, getString(R.string.added_to_home));
                } catch (Throwable ignored) {}
                return true;
            });
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
            hlp.rightMargin = Fx.dp(this, 8);
            chips.addView(chip, hlp);
        }
        hs.addView(chips);
        content.addView(hs, new LinearLayout.LayoutParams(-1, -2));
    }

    /** v1.3.44 — the launcher-style grid entrance: tiles pop in with a
     *  staggered spring (scale 0.6 + alpha 0 → overshoot to 1, 28ms
     *  apart). DEBOUNCED 2.5s: refreshHome() rebuilds the grid on favicon
     *  captures, pulls-to-refresh and menu edits — those must not replay
     *  the show; arriving on the home tab (seconds later) should.
     *  v1.3.92 — the matrix walk: the pager's attached SdGridLayout pages
     *  replace the row walk (the row-wrap field retired). */
    protected long lastHomeGridAnim;

    protected void animateHomeGrid(androidx.viewpager2.widget.ViewPager2 pager) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastHomeGridAnim < 2500) return;
            lastHomeGridAnim = now;
            int i = 0;
            for (SdGridLayout g : sdGridPages()) {
                for (int c = 0; c < g.getChildCount(); c++) {
                    View cell = g.getChildAt(c);
                    cell.setAlpha(0f);
                    cell.setScaleX(0.6f);
                    cell.setScaleY(0.6f);
                    cell.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(300).setStartDelay(28L * i)
                            .setInterpolator(
                                    new android.view.animation.OvershootInterpolator(1.35f))
                            .start();
                    i++;
                }
            }
        } catch (Throwable ignored) {}
    }

    /** v1.3.44 — safety net for the entrance animation: if the home is
     *  detached mid-pop (user leaves within the 300ms), ViewPropertyAnimator
     *  cancels and the cells could stay at alpha<1/scale<1 forever (the
     *  homeView instance is REUSED). Called whenever the home becomes the
     *  shown tab: snaps anything still mid-entrance to its settled state.
     *  v1.3.92 — the matrix walk. */
    protected void settleHomeGrid() {
        try {
            for (SdGridLayout g : sdGridPages()) {
                for (int c = 0; c < g.getChildCount(); c++) {
                    View cell = g.getChildAt(c);
                    if (cell.getAlpha() < 1f || cell.getScaleX() != 1f) {
                        cell.animate().cancel();
                        cell.setAlpha(1f);
                        cell.setScaleX(1f);
                        cell.setScaleY(1f);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /** v1.3.92 — the attached SdGridLayout pages of the live pager (the
     *  entrance animation, the settle net and the drag machinery all walk
     *  these). */
    protected java.util.ArrayList<SdGridLayout> sdGridPages() {
        java.util.ArrayList<SdGridLayout> out = new java.util.ArrayList<>();
        try {
            androidx.viewpager2.widget.ViewPager2 p = sdPager;
            if (p == null) return out;
            for (int i = 0; i < p.getChildCount(); i++) {
                walkGrids(p.getChildAt(i), out);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private void walkGrids(View v, java.util.ArrayList<SdGridLayout> out) {
        if (v instanceof SdGridLayout) {
            out.add((SdGridLayout) v);
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) walkGrids(vg.getChildAt(i), out);
        }
    }

    /** v1.3.92 — THE LAUNCHER MATRIX: one 6-col x 7-row page of the home
     *  shortcut grid. Children are laid out by SLOT (reading order,
     *  left-to-right within the row, top-to-bottom); [setPage] fills the
     *  page with its slice of the speeddial (the plus tile closes the last
     *  page). The drag preview shifts children to their would-be slots
     *  through [shiftTo] — FULL slots including the row wrap (the v1.3.90
     *  half-slot translationX could never express "the last tile of row 2
     *  slides to the head of row 3", which is why the old grid read as a
     *  mess the moment anything moved). */
    protected class SdGridLayout extends android.view.ViewGroup {
        public static final int COLS = 6;
        public static final int ROWS = 7;
        public static final int PER_PAGE = COLS * ROWS;
        public int pageIndex = -1;
        int cellW = 1, rowH = 1;

        SdGridLayout(android.content.Context c) {
            super(c);
        }

        /** fill this page with its slice of the speeddial (idempotent). */
        public void setPage(int pos) {
            pageIndex = pos;
            removeAllViews();
            org.json.JSONArray sd = prefs.speeddial();
            int n = sd.length();
            int start = pos * PER_PAGE;
            int end = Math.min(n, start + PER_PAGE);
            for (int i = start; i < end; i++) {
                org.json.JSONObject o = sd.optJSONObject(i);
                if (o == null) continue;
                View cell = o.optBoolean("group", false)
                        ? groupCell(o, i) : tileCell(o.optString("title"), o.optString("url"), i);
                addView(cell);
            }
            // the plus tile lives in the slot right after the last item
            // (page n/PER_PAGE; a full page pushes it to the next page)
            if (pos == n / PER_PAGE) {
                addView(plusCell(n));
            }
            // rebuild the hit-test set if this is the visible page
            try {
                androidx.viewpager2.widget.ViewPager2 p = sdPager;
                if (p != null && p.getCurrentItem() == pos) rebuildSdCells();
            } catch (Throwable ignored) {}
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int w = android.view.View.MeasureSpec.getSize(wms);
            rowH = Fx.dp(BrowserActivity.this, 82);
            cellW = Math.max(1, w / COLS);
            setMeasuredDimension(w, resolveSize(rowH * ROWS, hms));
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                c.measure(android.view.View.MeasureSpec.makeMeasureSpec(cellW,
                                android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(rowH,
                                android.view.View.MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                int col = i % COLS, row = i / COLS;
                c.layout(col * cellW, row * rowH, col * cellW + cellW, row * rowH + rowH);
            }
        }

        /** the natural x of a slot's left edge (preview math). */
        public float slotX(int slot) {
            return (slot % COLS) * (float) cellW;
        }

        /** the natural y of a slot's top edge. */
        public float slotY(int slot) {
            return (slot / COLS) * (float) rowH;
        }
    }

    /** v1.3.92 — the live hit-test set: the VISIBLE page's cells (the
     *  folder center-zone test needs laid-out rects; off-screen pages are
     *  irrelevant — the finger is always over the visible one). */
    protected void rebuildSdCells() {
        try {
            sdCells.clear();
            androidx.viewpager2.widget.ViewPager2 p = sdPager;
            if (p == null) return;
            int cur = p.getCurrentItem();
            for (SdGridLayout g : sdGridPages()) {
                if (g.pageIndex == cur) {
                    for (int i = 0; i < g.getChildCount(); i++) sdCells.add(g.getChildAt(i));
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    /** v1.3.44 — the modern shortcut tile: every home icon now sits in a
     *  48dp skin-aware SHELL (glass fill + hairline rim on Aura, solid
     *  surface + outline elsewhere, per-skin icon radius, 2dp lift). Icons
     *  used to float bare over the wallpaper, where transparent favicons
     *  half-vanished; the shell gives each tile one crisp platform — the
     *  iOS-tray / Pixel-at-3 look — and doubles as the press-feedback
     *  surface (see tilePressFx). */
    protected android.widget.FrameLayout tileShell(View inner) {
        android.widget.FrameLayout sh = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(Fx.dp(this, Fx.iconRadius(this)));
        if (Fx.isGlass(this)) {
            boolean dark = prefs.isDark();
            g.setColor(dark ? 0xB3141420 : 0xCCF4F5FB);
            g.setStroke(Math.max(1, Fx.dp(this, 1)),
                    dark ? 0x33FFFFFF : 0x291B1B2E);
        } else {
            g.setColor(Fx.surface(this));
            g.setStroke(Math.max(1, Fx.dp(this, 1)), Fx.outline(this));
        }
        sh.setBackground(g);
        sh.setElevation(Fx.dp(this, 2));
        sh.addView(inner, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        sh.setLayoutParams(new LinearLayout.LayoutParams(Fx.dp(this, 48), Fx.dp(this, 48)));
        return sh;
    }

    /** v1.3.44 — springy tile press: the SHELL dips to 0.88 while touched
     *  and springs back with overshoot on release (the label stays put —
     *  the old pressFx shrank the whole cell including the text). The
     *  ACTION_CANCEL branch also catches the moment the drag-to-group
     *  overlay takes over, so the tile springs home while the ghost flies.
     *  v1.3.89 — THE CLOBBER FIX: this used to setOnTouchListener(...) and
     *  SILENTLY REPLACE the drag feed the v1.3.88 fix had just installed
     *  (tileCell/groupCell set sdCellTouch, then tilePressFx overwrote
     *  it) — the in-flight drag was dead again and only the hold-menu ever
     *  fired, so dragging still needed menu → Move ("without having to
     *  click on the move, that is fully redundant"). The listener now
     *  COMPOSES: press-fx first, then whatever the drag feed says (it only
     *  consumes once a drag is armed).
     *  v1.4.6 — THE CLOBBER, ROUND TWO (lesson 98): the folder window's
     *  cells were wired wireSdWinCell(...) and then tilePressFx(...) — the
     *  second setOnTouchListener SILENTLY REPLACED the first, exactly the
     *  v1.3.89 corpse reborn in new code: the v1.4.4 drag wiring (and the
     *  v1.4.5 interception fix inside it) never ran on device; the window's
     *  icons only ever got press-fx + sdCellTouch (a no-op when no GRID
     *  drag is armed). The feed is now a PARAMETER so every surface
     *  installs ONE composed listener — the window passes its own drag
     *  feed (sdWinCellTouch), the grid keeps sdCellTouch. */
    protected void tilePressFx(View cell, final View shell) {
        tilePressFx(cell, shell, sdCellTouch);
    }

    protected void tilePressFx(View cell, final View shell,
            final android.view.View.OnTouchListener dragFeed) {
        cell.setOnTouchListener((v, e) -> {
            // v1.3.90 — the finger's last known RAW position, recorded on
            // EVERY event (even before a drag arms): beginSdDrag starts the
            // ghost under the finger INSTEAD of under the cell — the
            // long-press threshold is ~500 ms of natural drift, and a ghost
            // that appears at the cell only to snap to the finger on the
            // first MOVE reads as "the icon doesn't follow my finger
            // accurately" (the v1.3.89 field report).
            try {
                sdLastX = e.getRawX();
                sdLastY = e.getRawY();
            } catch (Throwable ignored) {}
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    try {
                        shell.animate().scaleX(0.88f).scaleY(0.88f)
                                .setDuration(90).start();
                    } catch (Throwable ignored) {}
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    try {
                        shell.animate().scaleX(1f).scaleY(1f).setDuration(260)
                                .setInterpolator(
                                        new android.view.animation.OvershootInterpolator(2.2f))
                                .start();
                    } catch (Throwable ignored) {}
                    break;
            }
            return dragFeed != null && dragFeed.onTouch(v, e); // never swallow; clicks still fire
        });
    }

    protected View tileCell(final String title, final String url, final int idx) {
        int txt = Fx.txt(this);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        // v1.3.44: 48dp shell + 3dp side padding keeps the 54dp row pitch
        // (6 tiles still fit a 360dp screen)
        cell.setPadding(Fx.dp(this, 3), Fx.dp(this, 6), Fx.dp(this, 3), Fx.dp(this, 6));
        // v1.3.39 (field report: "For the home shortcuts, I want it to have
        // the site's logo"): the tile shows the captured FAVICON on the
        // site's gradient; the letter stays the fallback for sites never
        // visited (nothing is fetched — the store fills as you browse).
        // v1.3.44: the avatar is 38dp inside the shell, with a
        // proportionate (iconRadius*0.8) corner instead of the card radius.
        String host = Fx.hostOf(url);
        int innerR = (int) (Fx.iconRadius(this) * 0.8f);
        android.widget.ImageView tile = new android.widget.ImageView(this);
        tile.setImageDrawable(com.bolt.browser.util.FaviconStore.avatar(
                this, host, title, 38, innerR));
        tile.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        android.widget.FrameLayout shell = tileShell(tile);
        cell.addView(shell);
        TextView label = new TextView(this);
        // v1.3.39: uniform labels — one line, capped length, same width box
        // ("a limit to how many characters can be shown to keep all the
        // shortcuts uniform")
        label.setText(shortcutLabel(title));
        label.setTextColor(txt);
        label.setTextSize(11);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, Fx.dp(this, 5), 0, 0);
        cell.addView(label, new LinearLayout.LayoutParams(Fx.dp(this, 56), -2));
        cell.setTag(idx);
        cell.setOnClickListener(v -> loadInTab(url));
        cell.setOnLongClickListener(v -> {
            beginSdDrag(idx, cell);
            return true;
        });
        // v1.3.89: the drag feed rides INSIDE tilePressFx's composed
        // listener (see the clobber fix there) — a second direct install
        // here would be overwritten one line later anyway.
        tilePressFx(cell, shell);
        return cell;
    }

    /** v1.3.39: the uniform shortcut label — capped at 10 chars (middle
     *  ellipsis), so every tile in the row reads the same size. */
    protected String shortcutLabel(String title) {
        String t = (title == null ? "" : title).trim();
        if (t.isEmpty()) t = "?";
        return t.length() <= 10 ? t
                : t.substring(0, 4) + "…" + t.substring(t.length() - 5);
    }

    /** the modern tile background: rounded square with a vertical two-tone
     *  gradient derived from the title's hash — the LibreTube/Google TV
     *  tile feel. v1.3.44: radiusDp overrides the skin card radius (the
     *  38dp folder tile + the 48dp drag ghost want proportionate corners). */
    protected android.graphics.drawable.GradientDrawable tileBg(String s, int radiusDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        int hue = Math.abs((s == null ? "" : s).hashCode()) % 360;
        int top = android.graphics.Color.HSVToColor(new float[]{hue, 0.52f, 0.62f});
        int bottom = android.graphics.Color.HSVToColor(new float[]{(hue + 18) % 360, 0.55f, 0.46f});
        g.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        g.setColors(new int[]{top, bottom});
        g.setCornerRadius(Fx.dp(this, radiusDp > 0 ? radiusDp : Fx.radius(this)));
        return g;
    }

    /** group (folder) tile — v1.3.44: launcher-style member PREVIEW: up to
     *  four mini favicons in a 2x2 on the gradient tile (the letter stays
     *  the fallback for empty folders), the whole 38dp tile riding in the
     *  same glass shell as every other icon, count badge on the shell's
     *  corner. */
    protected View groupCell(final org.json.JSONObject o, final int idx) {
        int txt = Fx.txt(this);
        String name = o.optString("name", "Group");
        org.json.JSONArray items = o.optJSONArray("items");
        int count = items == null ? 0 : items.length();
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(Fx.dp(this, 3), Fx.dp(this, 6), Fx.dp(this, 3), Fx.dp(this, 6));
        int innerR = (int) (Fx.iconRadius(this) * 0.8f);
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(tileBg(name, innerR));
        // the 2x2 member preview — mini avatars of the first four members
        java.util.ArrayList<android.widget.ImageView> minis = new java.util.ArrayList<>();
        for (int m = 0; m < count && minis.size() < 4; m++) {
            org.json.JSONObject it = items.optJSONObject(m);
            if (it == null) continue;
            try {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageDrawable(com.bolt.browser.util.FaviconStore.avatar(this,
                        Fx.hostOf(it.optString("url")), it.optString("title"), 13, 3));
                minis.add(iv);
            } catch (Throwable ignored) {}
        }
        if (!minis.isEmpty()) {
            for (int rr = 0; rr < 2; rr++) {
                LinearLayout rowL = new LinearLayout(this);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                for (int cc = 0; cc < 2; cc++) {
                    int k = rr * 2 + cc;
                    if (k >= minis.size()) break;
                    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                            Fx.dp(this, 13), Fx.dp(this, 13));
                    if (cc == 0) ip.rightMargin = Fx.dp(this, 2);
                    if (rr == 0) ip.bottomMargin = Fx.dp(this, 2);
                    // an odd last mini gets centered under the pair above
                    if (cc == 0 && k + 1 >= minis.size()) ip.leftMargin = Fx.dp(this, 15);
                    rowL.addView(minis.get(k), ip);
                }
                tile.addView(rowL, new LinearLayout.LayoutParams(-2, -2));
            }
        } else {
            // empty folder (or nothing stored yet): the letter tile
            TextView lt = new TextView(this);
            lt.setText(letterOf(name));
            lt.setTextColor(0xFFFFFFFF);
            lt.setTextSize(16);
            lt.setGravity(Gravity.CENTER);
            lt.setTypeface(com.bolt.browser.util.Ui.medium());
            tile.addView(lt, new LinearLayout.LayoutParams(-2, -2));
        }
        android.widget.FrameLayout shell = tileShell(tile);
        // the gradient tile is a fixed 38x38 regardless of the mini grid
        tile.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                Fx.dp(this, 38), Fx.dp(this, 38), Gravity.CENTER));
        // member count badge on the shell's corner
        TextView badge = new TextView(this);
        badge.setText(String.valueOf(count));
        badge.setTextColor(Fx.onAccent(this));
        badge.setTextSize(10);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(com.bolt.browser.util.Ui.medium());
        android.graphics.drawable.GradientDrawable bd =
                new android.graphics.drawable.GradientDrawable();
        bd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bd.setColor(Fx.accent(this));
        badge.setBackground(bd);
        android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                Fx.dp(this, 16), Fx.dp(this, 16), Gravity.END | Gravity.TOP);
        blp.rightMargin = Fx.dp(this, 2);
        blp.topMargin = Fx.dp(this, 2);
        shell.addView(badge, blp);
        cell.addView(shell);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(txt);
        label.setTextSize(11);
        label.setSingleLine(true);
        label.setPadding(0, Fx.dp(this, 5), 0, 0);
        cell.addView(label, new LinearLayout.LayoutParams(-2, -2));
        cell.setTag(idx);
        // v1.4.0 — THE LAUNCHER FOLDER WINDOW: tapping a folder opens it as a
        // floating window showing the icons inside (was: the bottom sheet
        // text list); holding it opens the EDIT menu (sdGroupEditMenu via
        // the hold-menu door), dragging it reorders (reorder-only).
        cell.setOnClickListener(v -> showSdFolderWindow(idx));
        cell.setOnLongClickListener(v -> {
            beginSdDrag(idx, cell);
            return true;
        });
        // v1.3.89: composed inside tilePressFx (the clobber fix)
        tilePressFx(cell, shell);
        return cell;
    }

    /** "+" tile used to add new shortcuts — v1.3.44: rides in the same
     *  shell as the others (icon-in-shell, not text-on-square) so the grid
     *  reads as one family. v1.3.92: carries its GLOBAL index as the tag
     * (== the item count — the slot right after the last shortcut) so the
     *  gap preview and the insert math can shift it like any tile; the
     *  folder target skips it (findSdFolderTarget). */
    protected View plusCell(final int idx) {
        int dim = Fx.dim(this);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(Fx.dp(this, 3), Fx.dp(this, 6), Fx.dp(this, 3), Fx.dp(this, 6));
        android.widget.FrameLayout inner = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(Fx.dp(this, (int) (Fx.iconRadius(this) * 0.8f)));
        g.setColor(Fx.surface2(this));
        g.setStroke(Math.max(1, Fx.dp(this, 1)), dim);
        inner.setBackground(g);
        android.widget.ImageView plus = new android.widget.ImageView(this);
        plus.setImageResource(R.drawable.ic_plus);
        plus.setColorFilter(Fx.accentText(this));
        inner.addView(plus, new android.widget.FrameLayout.LayoutParams(
                Fx.dp(this, 20), Fx.dp(this, 20), Gravity.CENTER));
        android.widget.FrameLayout shell = tileShell(inner);
        // set AFTER tileShell — its addView would wrap-content the frame
        inner.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                Fx.dp(this, 38), Fx.dp(this, 38), Gravity.CENTER));
        cell.addView(shell);
        TextView label = new TextView(this);
        label.setText(R.string.add_shortcut2);
        label.setTextColor(dim);
        label.setTextSize(11);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, Fx.dp(this, 5), 0, 0);
        cell.addView(label, new LinearLayout.LayoutParams(Fx.dp(this, 56), -2));
        cell.setTag(idx);
        cell.setOnClickListener(v -> addSpeeddialDialog());
        tilePressFx(cell, shell);
        return cell;
    }

    protected String letterOf(String s) {
        if (s == null || s.isEmpty()) return "?";
        return s.substring(0, 1).toUpperCase();
    }

    // ---------------- home shortcut drag-to-group ----------------

    protected void beginSdDrag(int idx, View cell) {
        if (sdOverlay != null || cell == null || flRoot == null) return;
        sdDragSrc = idx;
        sdDragMoved = false;
        // v1.4.0 — a FOLDER drag is reorder-only: no dwell merge arms while
        // this is true (the sdDragMove guard + the belt-and-braces checks in
        // startSdDwell/sdDwellTick)
        sdDragSrcIsGroup = prefs.isSpeeddialGroup(idx);
        dismissSdFolderWindow();
        final int half = Fx.dp(this, 24);
        sdGhostHalf = half;
        // v1.3.90 — the drag origin is the FINGER's last known position
        // (recorded by the composed tile listener on every event); the cell
        // rect is only the never-touched fallback (defense: sdLastX==0).
        if (sdLastX <= 0 && sdLastY <= 0) {
            final android.graphics.Rect r = new android.graphics.Rect();
            if (!cell.getGlobalVisibleRect(r)) {
                int[] loc = new int[2];
                cell.getLocationOnScreen(loc);
                r.set(loc[0], loc[1], loc[0] + cell.getWidth(), loc[1] + cell.getHeight());
            }
            sdLastX = r.centerX();
            sdLastY = r.centerY();
        }
        sdOriginX = sdLastX;
        sdOriginY = sdLastY;
        // v1.3.90 — the drift test now measures from the FINGER (the ghost
        // lifts under it), not from the cell center: a long-press's natural
        // drift must not instantly read as drag-moved (the menu-vs-drag
        // contract: still finger = menu, moving finger = drag).
        org.json.JSONObject o = prefs.speeddialAt(idx);
        String name = o == null ? "?" : (o.optBoolean("group", false)
                ? o.optString("name") : o.optString("title"));
        // v1.3.89 — THE REAL-ICON GHOST (the field: "the drag does not have
        // the nice animation of the icons of the site themselves moving,
        // only the first letter logo moves"). The ghost used to be a letter
        // TextView on the hash gradient — it now IS the tile: the shortcut
        // case carries the site's own favicon avatar in the same glass
        // shell the grid uses; a group carries its 2x2 member preview
        // (fallback: the letter, as before). The SOURCE CELL fades to 25%
        // for the grab feel and returns on drop/end (endSdDrag).
        View g;
        if (o != null && o.optBoolean("group", false)) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            int innerR = (int) (Fx.iconRadius(this) * 0.8f);
            tile.setBackground(tileBg(name, innerR));
            org.json.JSONArray items = o.optJSONArray("items");
            int count = items == null ? 0 : items.length();
            java.util.ArrayList<android.widget.ImageView> minis = new java.util.ArrayList<>();
            for (int m = 0; m < count && minis.size() < 4; m++) {
                org.json.JSONObject it = items.optJSONObject(m);
                if (it == null) continue;
                try {
                    android.widget.ImageView iv = new android.widget.ImageView(this);
                    iv.setImageDrawable(com.bolt.browser.util.FaviconStore.avatar(this,
                            Fx.hostOf(it.optString("url")), it.optString("title"), 13, 3));
                    minis.add(iv);
                } catch (Throwable ignored) {}
            }
            if (!minis.isEmpty()) {
                for (int rr = 0; rr < 2; rr++) {
                    LinearLayout rowL = new LinearLayout(this);
                    rowL.setOrientation(LinearLayout.HORIZONTAL);
                    for (int cc = 0; cc < 2; cc++) {
                        int k = rr * 2 + cc;
                        if (k >= minis.size()) break;
                        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                                Fx.dp(this, 13), Fx.dp(this, 13));
                        if (cc == 0) ip.rightMargin = Fx.dp(this, 2);
                        if (rr == 0) ip.bottomMargin = Fx.dp(this, 2);
                        if (cc == 0 && k + 1 >= minis.size()) ip.leftMargin = Fx.dp(this, 15);
                        rowL.addView(minis.get(k), ip);
                    }
                    tile.addView(rowL, new LinearLayout.LayoutParams(-2, -2));
                }
            } else {
                TextView lt = new TextView(this);
                lt.setText(letterOf(name));
                lt.setTextColor(0xFFFFFFFF);
                lt.setTextSize(16);
                lt.setGravity(Gravity.CENTER);
                lt.setTypeface(com.bolt.browser.util.Ui.medium());
                tile.addView(lt, new LinearLayout.LayoutParams(-2, -2));
            }
            tile.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    Fx.dp(this, 38), Fx.dp(this, 38), Gravity.CENTER));
            g = tileShell(tile);
        } else {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            int innerR = (int) (Fx.iconRadius(this) * 0.8f);
            iv.setImageDrawable(com.bolt.browser.util.FaviconStore.avatar(
                    this, Fx.hostOf(o == null ? "" : o.optString("url")), name, 38, innerR));
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    Fx.dp(this, 38), Fx.dp(this, 38), Gravity.CENTER));
            g = tileShell(iv);
        }
        g.setElevation(Fx.dp(this, 14));
        sdGhost = g;
        flRoot.addView(g, new FrameLayout.LayoutParams(half * 2, half * 2));
        // v1.3.90 — THE GRAB CONTRACT: the ghost appears UNDER THE FINGER
        // (sdLastX/Y — recorded by the composed tile listener on every
        // event, so no snap from the cell to the finger on the first MOVE)
        // and floats sdLiftDp ABOVE it (the hand never covers the flying
        // icon; tracking stays 1:1 underneath — "the icons don't follow the
        // finger dragging it accurately" fixed at both ends).
        g.setX(sdLastX - half);
        g.setY(sdLastY - half - Fx.dp(this, sdLiftDp));
        // v1.3.79 — THE LIFT: the ghost rises under the finger (scale +
        // fade-in), the launcher grab feel; the settle animates away on drop
        g.setScaleX(0.6f); g.setScaleY(0.6f); g.setAlpha(0f);
        g.animate().scaleX(1.14f).scaleY(1.14f).alpha(0.96f).setDuration(140).start();
        try { com.bolt.browser.util.Ui.tap(g); } catch (Throwable ignored) {}
        // v1.3.89 — the source cell dims while its icon flies (the
        // launcher grab contract: the icon LIFTED OUT, not a copy)
        try { cell.animate().alpha(0.25f).setDuration(120).start(); } catch (Throwable ignored) {}
        View ov = new View(this);
        sdOverlay = ov;
        // v1.3.88 — the overlay no longer carries the drag logic: a view
        // added mid-gesture never receives the in-flight MOVE stream (the
        // touch target is fixed at ACTION_DOWN), which is why the v1.3.79
        // drag never moved and only the hold-menu ever fired. It now merely
        // shields NEW touches while a drag is armed; the CELLS feed the
        // gesture (sdCellTouch below) and both paths share sdDragMove /.
        // sdDragEnd.
        ov.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_MOVE:
                    sdDragMove(e.getRawX(), e.getRawY());
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    sdDragEnd(e.getRawX(), e.getRawY());
                    return true;
                default:
                    return true;
            }
        });
        flRoot.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        flRoot.bringChildToFront(ov);
        flRoot.bringChildToFront(g);
        if (homeScroll != null) homeScroll.requestDisallowInterceptTouchEvent(true);
        // v1.3.92 — the pager must NOT steal the horizontal MOVE stream
        // (ViewPager2 is a RecyclerView: it intercepts horizontal drags for
        // paging; the drag owns the gesture until it ends — endSdDrag)
        try { if (sdPager != null) sdPager.requestDisallowInterceptTouchEvent(true); }
        catch (Throwable ignored) {}
        // v1.3.98 — THE PAGER CLASH, FIXED AT THE RIGHT LEVEL (the field:
        // "the dragging right on home screen icon and the swiping right to
        // scroll the home screen icons are interfering"). THE DECOMPILE
        // (ViewPager2 1.1.0): ViewPager2 itself overrides NO touch method —
        // the paging interception lives in its INNER RecyclerViewImpl
        // (onInterceptTouchEvent/onTouchEvent), a CHILD of the ViewPager2.
        // requestDisallowInterceptTouchEvent propagates UP from the view it
        // is called on — so the v1.3.92 call above armed the ViewPager2 and
        // every ANCESTOR, but never the inner RecyclerView BELOW it: the
        // moment an armed drag moved horizontally past touch slop, the inner
        // RecyclerView intercepted, the cell's stream was CANCELLED (the
        // drag folded away) and the page swiped instead — the exact clash.
        // Vertical reordering never tripped it (a horizontal LinearLayoutManager
        // ignores vertical drags), which is why "rearranging works" while a
        // RIGHTWARD drag paged the home. TWO locks now, belt and braces:
        // (a) the flag raised on the CELL itself — it walks the REAL chain
        // (SdGridLayout -> inner RecyclerView -> ViewPager2 -> homeScroll),
        // disarming every interceptor between the finger and the window;
        // (b) setUserInputEnabled(false) — the documented ViewPager2 input
        // kill switch: RecyclerViewImpl's touch methods BOTH gate on it, so
        // the pager cannot intercept or scroll even if a stray ACTION_DOWN
        // resets the flag chain mid-drag. setCurrentItem (the edge turn) is
        // programmatic and unaffected.
        try {
            android.view.ViewParent cp = cell.getParent();
            if (cp != null) cp.requestDisallowInterceptTouchEvent(true);
        } catch (Throwable ignored) {}
        sdDragCell = cell;
        try { if (sdPager != null) sdPager.setUserInputEnabled(false); }
        catch (Throwable ignored) {}
        // v1.3.81: hold still -> the menu opens by itself (see sdHoldMenu).
        // v1.3.95 — 600 -> 1200 ms (the field: "the menu pops up too quickly
        // I cannot test the dragging or merging"): the menu must lose the
        // race against a deliberate, unhurried drag start — ANY movement
        // past ~touch-slop cancels the menu for good (sdDragMove), so the
        // menu is reachable only through a genuinely still hold.
        // v1.3.97 — 1200 -> 550 ms (the field: "the duration to activate the
        // menu is a bit too long"): with the lift at the system long-press
        // (~400 ms) the menu lands ~1 s after touch-down — the launcher
        // cadence. Two doors, neither of which can ambush a drag: any 6 dp
        // drift still cancels the hold timer for good, and a release
        // without a real drag opens the menu instead (sdDragEnd) — so a
        // slow drag start is safe AND a still hold is quick. A menu-started
        // drag (the Move row) skips the timer entirely (sdDragNoMenu): the
        // still moment of re-grabbing the tile is placement, not a menu
        // request.
        if (!sdDragNoMenu) {
            ui.removeCallbacks(sdHoldMenu);
            ui.postDelayed(sdHoldMenu, 550);
        }
        sdGapShown = -2;   // v1.3.90: no gap preview until the finger moves
    }

    /** v1.3.88 — ONE drag-motion handler, fed from BOTH the cells' own
     *  touch stream (the in-flight gesture that began the long-press —
     *  THE fix: the touch target is fixed at ACTION_DOWN, so a view added
     *  mid-gesture like the old overlay never sees the ongoing MOVEs) and
     *  the shield overlay (a fresh touch during an armed drag). Ghost
     *  follows the finger; >16 dp of drift cancels the hold-menu.
     *  v1.3.92 — THE FULL-SPEC DRAG (the field: "Let say I have these
     *  icons in serial: A B C D. I hold B and drag it to left of A, A
     *  moves right to make space for B before A…"): (a) the LIVE GAP is
     *  now FULL-SLOT and row-wrapping (showSdGap through the slot
     *  matrix); (b) grouping is the DWELL TIMER (the tabs contract —
     *  the finger resting in a tile's CENTER arms a progress pill that
     *  fills for 1000 ms and merges when FULL, see sdDwellTick); (c) the
     *  EDGE TURN moves the drag to the next page when the finger rests
     *  at the pager's edge. */
    protected void sdDragMove(float x, float y) {
        // v1.3.96 — the drag may already be DISARMED (sdDragEnd ran; the
        // posted teardown has not landed yet). Events in that one-frame
        // window must not move a ghost or reshuffle a gap for a dead drag.
        if (sdDragSrc < 0) return;
        if (sdGhost != null) {
            // v1.3.90 — the lift is constant (sdLiftDp above the finger):
            // 1:1 tracking, the hand never covers the icon
            sdGhost.setX(x - sdGhostHalf);
            sdGhost.setY(y - sdGhostHalf - Fx.dp(this, sdLiftDp));
        }
        // v1.3.95 — THE MENU-CANCEL IS TIGHTER THAN THE DRAG ARM: any drift
        // past ~touch-slop (6 dp) kills the hold-menu for good (the user's
        // "without moving ONLY" contract — a hesitant slow start must
        // never see the menu pop), while the drag itself still engages at
        // the old 16 dp (that is the reorder-vs-tap threshold, unchanged).
        if (Math.abs(x - sdOriginX) > Fx.dp(this, 6)
                || Math.abs(y - sdOriginY) > Fx.dp(this, 6)) {
            ui.removeCallbacks(sdHoldMenu);
        }
        if (!sdDragMoved
                && (Math.abs(x - sdOriginX) > Fx.dp(this, 16)
                || Math.abs(y - sdOriginY) > Fx.dp(this, 16))) {
            sdDragMoved = true;
            // the finger moved — this is a drag, not a menu hold
            ui.removeCallbacks(sdHoldMenu);
        }
        // v1.3.92 — the dwell-timer group: entering a tile's center zone
        // starts the charge; ANY change of target resets it.
        // v1.4.0 — NOT FOR FOLDER DRAGS: a dragged folder can only reorder
        // ("only draggable to rearrange order, nothing else") — hovering a
        // folder over a tile/group center must never charge a merge, so the
        // whole dwell path is skipped and the live gap keeps working.
        if (sdDragSrcIsGroup) {
            if (sdFolderTarget != -1) {
                sdFolderTarget = -1;
                resetSdDwell();
            }
        } else {
            int ft = findSdFolderTarget(x, y);
            if (ft != sdFolderTarget) {
                sdFolderTarget = ft;
                resetSdDwell();
                if (ft >= 0) startSdDwell(ft);
            }
        }
        // THE LIVE GAP: full-slot, row-wrapping, frozen while the dwell
        // charges (the ChromeOS law — the grid does not reshuffle under a
        // grouping gesture)
        if (sdFolderTarget < 0) {
            try {
                int ins = findSdInsertIndex(x, y, sdDragSrc);
                if (ins != sdGapShown) showSdGap(ins);
            } catch (Throwable ignored) {}
        } else if (sdGapShown != -2) {
            showSdGap(-1);   // collapse the gap the moment grouping arms
        }
        // THE EDGE TURN
        sdEdgeArm(x, y);
    }

    /** v1.3.88 — the drop, shared by the cell feed and the overlay.
     *  v1.3.90 — THE LAUNCHER CONTRACT: a QUICK drop REORDERS (on a tile
     *  by its half, on empty space by the nearest slot).
     *  v1.3.92 — A DROP IS ALWAYS A REORDER. The v1.3.91 release-gated
     *  merge is retired: grouping now happens ONLY through the completed
     *  dwell timer (sdDwellTick — the tabs contract the field asked for:
     *  "use the tabs logic of having to drag and hold B in center of A to
     *  merge and create group"), so nothing a drop does can ever read as
     *  a deletion. Deletion stays menu-only with its UNDO snackbar. */
    protected void sdDragEnd(float x, float y) {
        // v1.3.96 — THE DISPATCH CONTRACT (the field crash: "dragging home
        // screen icons to rearrange crashes the browser" —
        // IllegalStateException "already recycled once" at
        // ViewGroup$TouchTarget.recycle, arriving on a BATCHED move during
        // doFrame). This method fires from INSIDE onTouch (the cell feed
        // sdCellTouch / the shield overlay), i.e. while the framework is
        // still walking its touch-target lists through the very tree being
        // torn down: endSdDrag() + refreshHome() used to remove the
        // overlay/pill and rebuild the ENTIRE home MID-DISPATCH. Detaching
        // views mid-dispatch makes every ancestor's in-flight target loop
        // iterate stale locals (API 29 detaches synthesise CANCELs and
        // recycle targets the loop still holds), the TouchTarget pool
        // corrupts, and a later batched event detonates. THE STANDARD
        // LAUNCHER LAW: a touch callback only RECORDS the outcome; every
        // view-tree mutation happens on the NEXT frame, past the end of
        // the dispatch. The drag is disarmed synchronously (re-entrant
        // listener calls no-op) and the teardown/reorder/menu is POSTED.
        if (sdDragSrc < 0) return;   // no drag armed / already ending
        ui.removeCallbacks(sdHoldMenu);
        final int src = sdDragSrc;
        final boolean moved = sdDragMoved;
        final float fx = x, fy = y;
        // disarm NOW — both feeds' later events must find a dead drag
        sdDragSrc = -1;
        sdDragMoved = false;
        sdFolderTarget = -1;
        ui.post(() -> {
            try {
                endSdDrag();
                if (!moved && src >= 0) {
                    sdTileMenu(src);
                } else if (moved && src >= 0) {
                    // REORDER everywhere: the hovered slot + its half decides
                    // (computed against the still-live tree — refreshHome
                    // has not run yet, so sdPager + the grid are the ones
                    // the finger left)
                    int insert = findSdInsertIndex(fx, fy, src);
                    if (insert >= 0 && insert != src && insert != src + 1) {
                        prefs.moveSpeeddial(src, insert);
                        // v1.3.97 — QUIET + POSITION-TRUE (refreshHomeQuiet):
                        // the rebuilt grid lands on the same page/scroll with
                        // NO entrance replay — a real reorder must not read
                        // as "the home refreshed" either.
                        refreshHomeQuiet();
                    }
                    // v1.3.97 — a NO-OP drop (the slot the finger left is
                    // where the tile already lives) builds NOTHING: the
                    // settle above already restored every cell. v1.3.96 ran
                    // a full refreshHome here — a small drag + release
                    // rebuilt the entire home (scroll reset, page reset,
                    // tiles re-pop) for nothing: "the shortcut icons causes
                    // home page to refresh when dragged".
                }
            } catch (Throwable ignored) {}
        });
    }

    /** v1.3.88 — THE IN-FLIGHT DRAG FEED (the fix for "the browser home
     *  customization… is not draggable, it makes a sort of weird pop up
     *  and does not drag"). beginSdDrag fires from onLongClick ~500 ms
     *  into a touch the CELL already owns; the full-screen overlay added
     *  at that moment never receives the ongoing MOVE stream, so the ghost
     *  never moved and the only thing that ever fired was the 600 ms
     *  hold-menu timer — "the weird pop up". A view KEEPS receiving its
     *  touch stream after the long-click (the launcher-grid pattern), so
     *  every shortcut/group cell carries this listener: once a drag is
     *  armed, its MOVEs drive the ghost/target/hold-merge and its UP/CANCEL
     *  finish the drag. With no drag armed the listener stays transparent
     *  so taps and long-presses keep working untouched. */
    protected final android.view.View.OnTouchListener sdCellTouch =
            (v, e) -> {
                if (sdOverlay == null) return false;   // no drag armed
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_MOVE:
                        sdDragMove(e.getRawX(), e.getRawY());
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        sdDragEnd(e.getRawX(), e.getRawY());
                        return true;
                    default:
                        return false;
                }
            };

    /** v1.3.91 — THE CENTER-ZONE CANDIDATE. The zone is the middle ~38%
     *  of a tile (the Launcher3 folderCreationRadius law: a radius
     *  BETWEEN the icon's visible radius and the cell's — tighter than
     *  the reorder zone, so passing over a tile can never arm it).
     *  v1.3.92 — only the dwell path consumes it (the target of the
     *  progress pill); the plus tile (tag == n) is never a group target. */
    protected int findSdFolderTarget(float x, float y) {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            int best = -1; double bestD = Double.MAX_VALUE;
            int n = prefs.speeddial().length();
            for (View c : sdCells) {
                Object tag = c.getTag();
                if (!(tag instanceof Integer)) continue;
                int i = (Integer) tag;
                if (i == sdDragSrc) continue;
                if (i >= n) continue;   // the plus tile
                if (!c.getGlobalVisibleRect(r)) continue;
                if (!r.contains((int) x, (int) y)) continue;
                double d = Math.hypot(r.centerX() - x, r.centerY() - y);
                // the folder radius: 38% of the cell's short edge (a 56dp
                // cell = ~21dp — the center circle, not the tile)
                double rad = Math.min(r.width(), r.height()) * 0.38;
                if (d <= rad && d < bestD) { bestD = d; best = i; }
            }
            return best;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------- v1.3.92 the dwell-timer group (the tabs contract) ----------------

    /** arm the charge: show the progress pill over the target and start
     *  the 40 ms tick loop (1000 ms to full — v1.3.100 widened it from
     *  600: the field report said the pill filled too fast to appreciate
     *  the animation; a full second is the launcher cadence and still
     *  cancels instantly on any drift out of the center zone).
     *  v1.3.96 — the whole body is POSTED (it addViews the pill to flRoot
     *  and startSdDwell fires from sdDragMove, INSIDE the touch dispatch —
     *  the sdDragEnd law). The staleness guard re-checks the drag and the
     *  target at run time: a pill for a target the finger already left is
     *  never born. */
    protected void startSdDwell(final int target) {
        if (sdDragSrcIsGroup) return;   // v1.4.0: a folder drag never merges
        ui.post(() -> {
            try {
                if (sdOverlay == null || sdDragSrc < 0 || sdFolderTarget != target)
                    return;   // stale — the drag ended or the finger moved on
                sdDwellProgress = 0;
                View cell = sdCellOfIndex(target);
                if (cell == null) return;
            TextView pill = new TextView(this);
            pill.setText(getString(R.string.hold_to_group_hint));
            pill.setTextColor(0xFFFFFFFF);
            pill.setTextSize(13);
            pill.setTypeface(com.bolt.browser.util.Ui.medium());
            int p = Fx.dp(this, 7);
            pill.setPadding(Fx.dp(this, 12), p, Fx.dp(this, 12), p);
            android.graphics.drawable.GradientDrawable track =
                    new android.graphics.drawable.GradientDrawable();
            track.setCornerRadius(Fx.dp(this, 999));
            track.setColor(0xE61B1B2E);
            android.graphics.drawable.GradientDrawable fill =
                    new android.graphics.drawable.GradientDrawable();
            fill.setCornerRadius(Fx.dp(this, 999));
            fill.setColor(Fx.accent(this));
            sdDwellClip = new android.graphics.drawable.ClipDrawable(fill,
                    Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
            android.graphics.drawable.LayerDrawable ld =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{track, sdDwellClip});
            pill.setBackground(ld);
            pill.setElevation(Fx.dp(this, 10));
            sdDwellPill = pill;
            flRoot.addView(pill, new FrameLayout.LayoutParams(-2, -2));
            pill.measure(android.view.View.MeasureSpec.makeMeasureSpec(0,
                            android.view.View.MeasureSpec.UNSPECIFIED),
                    android.view.View.MeasureSpec.makeMeasureSpec(0,
                            android.view.View.MeasureSpec.UNSPECIFIED));
            android.graphics.Rect r = new android.graphics.Rect();
            cell.getGlobalVisibleRect(r);
            int[] fl = new int[2];
            flRoot.getLocationOnScreen(fl);
            pill.setTranslationX(r.centerX() - fl[0] - pill.getMeasuredWidth() / 2f);
            pill.setTranslationY(r.top - fl[1] - pill.getMeasuredHeight()
                    - Fx.dp(this, 8));
            pill.setAlpha(0f);
            pill.animate().alpha(1f).setDuration(100).start();
            } catch (Throwable ignored) {}
            ui.removeCallbacks(sdDwellTick);
            ui.postDelayed(sdDwellTick, 40);
        });
    }

    /** reset the charge (target changed / drag ended / pill done).
     *  v1.3.96 — the pill REMOVAL is POSTED: resetSdDwell fires from
     *  sdDragMove (inside the touch dispatch), and removeView on flRoot
     *  mid-dispatch is the TouchTarget-corruption class (see sdDragEnd).
     *  The pill reference is captured so a newer pill is never touched. */
    protected void resetSdDwell() {
        try { ui.removeCallbacks(sdDwellTick); } catch (Throwable ignored) {}
        sdDwellProgress = 0;
        final TextView pill = sdDwellPill;
        sdDwellPill = null;
        sdDwellClip = null;
        if (pill != null) ui.post(() -> {
            try { if (pill.getParent() == flRoot) flRoot.removeView(pill); }
            catch (Throwable ignored) {}
        });
    }

    /** the charge loop: fills the pill; the merge fires the moment it
     *  fills (mid-hold — never at release). Any exit of the center zone
     *  cancels (see sdDragMove). */
    protected final Runnable sdDwellTick = new Runnable() {
        @Override public void run() {
            try {
                if (sdOverlay == null || sdFolderTarget < 0 || sdDragSrcIsGroup) {
                    resetSdDwell();
                    return;
                }
                sdDwellProgress += 40;
                try {
                    if (sdDwellClip != null) sdDwellClip.setLevel(
                            (int) Math.min(10000, sdDwellProgress * 10000L / 1000L));
                } catch (Throwable ignored) {}
                if (sdDwellProgress >= 1000) {
                    int src = sdDragSrc, tgt = sdFolderTarget;
                    resetSdDwell();
                    if (src >= 0 && tgt >= 0 && src != tgt) {
                        try { prefs.mergeSpeeddial(src, tgt); } catch (Throwable ignored) {}
                        try { com.bolt.browser.util.Ui.tap(flRoot); } catch (Throwable ignored) {}
                        Fx.toast(BrowserActivity.this, getString(R.string.grouped));
                        endSdDrag();
                        // v1.3.97 — quiet + position-true (the merge happens
                        // on the page the finger is ON; a full reset here
                        // read as the home "refreshing")
                        refreshHomeQuiet();
                    }
                    return;
                }
                ui.postDelayed(this, 40);
            } catch (Throwable ignored) {}
        }
    };

    /** v1.3.92 — THE EDGE TURN: resting the dragged tile at the pager's
     *  horizontal edge for ~650 ms advances to the next page (the drag
     * stays armed; the gap preview collapses while the page settles). The
     * direction is VISUAL (near the right edge = the page that lies to
     * the right), mapped through the pager's RTL flag. */
    protected void sdEdgeArm(float x, float y) {
        try {
            androidx.viewpager2.widget.ViewPager2 p = sdPager;
            if (p == null) return;
            int[] pl = new int[2];
            p.getLocationOnScreen(pl);
            float relX = x - pl[0];
            if (y < pl[1] || y > pl[1] + p.getHeight()
                    || relX < 0 || relX >= p.getWidth()) {
                if (sdEdgeDir != 0) { ui.removeCallbacks(sdEdgeTurn); sdEdgeDir = 0; }
                return;
            }
            boolean rtl = p.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int edge = Fx.dp(this, 44);
            int dir = 0;   // logical units (+1 = next page index)
            if (relX < edge) dir = rtl ? 1 : -1;
            else if (relX > p.getWidth() - edge) dir = rtl ? -1 : 1;
            if (dir == 0) {
                if (sdEdgeDir != 0) { ui.removeCallbacks(sdEdgeTurn); sdEdgeDir = 0; }
                return;
            }
            if (dir != sdEdgeDir) {
                sdEdgeDir = dir;
                ui.removeCallbacks(sdEdgeTurn);
                ui.postDelayed(sdEdgeTurn, 650);
            }
        } catch (Throwable ignored) {}
    }

    protected final Runnable sdEdgeTurn = new Runnable() {
        @Override public void run() {
            try {
                androidx.viewpager2.widget.ViewPager2 p = sdPager;
                if (p == null || sdOverlay == null || sdEdgeDir == 0) return;
                int next = p.getCurrentItem() + sdEdgeDir;
                int count = p.getAdapter() == null ? 1 : p.getAdapter().getItemCount();
                if (next >= 0 && next < count) {
                    p.setCurrentItem(next, true);
                    showSdGap(-1);
                    sdGapShown = -2;
                }
                // stay armed for continuous turning while the finger rests
                ui.postDelayed(this, 950);
            } catch (Throwable ignored) {}
        }
    };

    /** v1.3.81 - THE HOLD-MENU. The v1.3.79 tile menu only appeared on
     *  RELEASE of a stationary hold, and any finger drift past 16dp while
     *  holding silently re-routed the release into the reorder/merge path -
     *  "holding on it does not show any actions". A stationary hold now
     *  shows the menu BY ITSELF (the launcher convention), ending the
     *  drag; moving the finger first still drags (the timer cancels the
     *  moment the drag engages).
     *  v1.3.95 — 1200 ms and movement-certain: the field could not test
     *  drag/merge because the menu won the race at 600 ms; the delay
     *  doubled and any 6dp drift cancels it (see sdDragMove).
     *  v1.3.97 — 550 ms (the field: "the duration to activate the menu is
     *  a bit too long"): the release-without-move door (sdDragEnd) makes a
     *  long window unnecessary, so the hold door lands at the launcher
     *  cadence (~1 s from touch-down) while any 6dp drift still cancels
     *  it for good. Not armed at all for a menu-started drag (sdDragNoMenu). */
    protected final Runnable sdHoldMenu = new Runnable() {
        @Override public void run() {
            try {
                if (sdOverlay == null) return;   // drag already over
                int src = sdDragSrc;
                if (src < 0 || sdDragMoved) return;
                endSdDrag();
                if (src >= 0) sdTileMenu(src);
            } catch (Throwable ignored) {}
        }
    };

    /** v1.3.92 — THE LIVE GAP, FULL-SLOT AND ROW-WRAPPING (the field:
     * "I hold B and drag it to left of A, A moves right to make space for
     * B before A. I hold and drag D to before B, B and C move right to
     * make space for D. I hold and drag B to right of D, CD moves left to
     * make space for B."). Every attached page's tiles animate to the
     * slot they would occupy if the drop landed at [insert] — a WHOLE
     * slot, wrapping across rows (the v1.3.90 half-slot translationX
     * could not express a row wrap, which is why the old grid "becomes a
     * mess when I try to move"). Tiles whose new slot lands on another
     * page stay put for the preview (they land at refreshHome). */
    protected void showSdGap(int insert) {
        try {
            sdGapShown = insert;
            for (SdGridLayout g : sdGridPages()) {
                int pageStart = g.pageIndex * SdGridLayout.PER_PAGE;
                for (int ci = 0; ci < g.getChildCount(); ci++) {
                    View c = g.getChildAt(ci);
                    Object tag = c.getTag();
                    if (!(tag instanceof Integer)) continue;
                    int i = (Integer) tag;
                    if (i == sdDragSrc) continue;
                    float tx = 0f, ty = 0f;
                    if (insert >= 0) {
                        int newIdx = sdPreviewNewIndex(i, sdDragSrc, insert);
                        int curLocal = i - pageStart;
                        int newLocal = newIdx - pageStart;
                        if (curLocal >= 0 && curLocal < SdGridLayout.PER_PAGE
                                && newLocal >= 0 && newLocal < SdGridLayout.PER_PAGE) {
                            tx = g.slotX(newLocal) - g.slotX(curLocal);
                            ty = g.slotY(newLocal) - g.slotY(curLocal);
                        }
                    }
                    c.animate().translationX(tx).translationY(ty).setDuration(150).start();
                }
            }
        } catch (Throwable ignored) {}
    }

    /** the post-move index of item [i] if item [src] moved to insertion
     *  point [ins] (all indices in ORIGINAL list coordinates — the
     *  moveSpeeddial convention). The A-B-C-D law the field spelled out. */
    protected int sdPreviewNewIndex(int i, int src, int ins) {
        int p = (ins > src) ? ins - 1 : ins;
        int posW = (i < src) ? i : i - 1;
        return (posW >= p) ? posW + 1 : posW;
    }

    /** v1.3.92 — the REORDER landing index from the SLOT MATRIX: the
     *  finger over the pager resolves to (page, row, col); the hovered
     *  cell's horizontal half decides before/after (the user's own words:
     *  "drag it to left of A" / "drag B to right of D"). -1 = not over
     *  the grid (keep position). */
    protected int findSdInsertIndex(float x, float y, int srcIdx) {
        try {
            androidx.viewpager2.widget.ViewPager2 pager = sdPager;
            if (pager == null) return -1;
            int[] pl = new int[2];
            pager.getLocationOnScreen(pl);
            float relX = x - pl[0];
            float relY = y - pl[1];
            if (relX < 0 || relX >= pager.getWidth()
                    || relY < 0 || relY >= pager.getHeight()) return -1;
            boolean rtl = pager.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int pw = pager.getWidth();
            // mirror into LTR space so one slot math serves both hands
            // (relX is already clamped inside [0, pw) by the guard above —
            // the finger is over the CURRENT page during a drag; pages
            // advance only through the edge turn, so no page offset is
            // folded in — the old always-zero offset term is gone)
            float inX = rtl ? (pw - 1 - relX) : relX;
            int cur = pager.getCurrentItem();
            int cellW = Math.max(1, pw / SdGridLayout.COLS);
            int rowH = Fx.dp(this, 82);
            int col = Math.min(SdGridLayout.COLS - 1, Math.max(0, (int) (inX / cellW)));
            int row = Math.min(SdGridLayout.ROWS - 1, Math.max(0, (int) (relY / rowH)));
            int n = prefs.speeddial().length() + 1;   // + the plus tile
            int global = Math.min(cur * SdGridLayout.PER_PAGE
                    + row * SdGridLayout.COLS + col, n - 1);
            // the hovered cell's center, back in VISUAL space
            float cxLtr = col * cellW + cellW / 2f;
            float cx = rtl ? (pw - 1 - cxLtr) : cxLtr;
            boolean after = relX > cx;
            return after ? global + 1 : global;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** v1.3.91 — retired with the dwell-merge: the whole-tile hover target
     *  is gone (the CENTER ZONE is the only folder path now — see
     *  findSdFolderTarget). Kept as a no-op stub so any missed caller from
     *  the composed listeners stays safe. */
    protected int findSdTarget(float x, float y) {
        return findSdFolderTarget(x, y);
    }

    protected void setSdTarget(int t) {
        // no-op: the v1.3.92 dwell pass owns all target feedback
    }

    protected void endSdDrag() {
        try { ui.removeCallbacks(sdHoldMenu); } catch (Throwable ignored) {}   // v1.3.81
        // v1.3.92 — the dwell charge + the edge timer retract with the drag
        try { ui.removeCallbacks(sdEdgeTurn); sdEdgeDir = 0; } catch (Throwable ignored) {}
        try { resetSdDwell(); } catch (Throwable ignored) {}
        sdFolderTarget = -1;
        try { if (sdOverlay != null && sdOverlay.getParent() == flRoot) flRoot.removeView(sdOverlay); }
        catch (Throwable ignored) {}
        // v1.3.79 — the ghost folds away with a settle instead of vanishing
        try {
            if (sdGhost != null && sdGhost.getParent() == flRoot) {
                final View ghost = sdGhost;
                ghost.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f)
                        .setDuration(120).withEndAction(() -> {
                            try { if (ghost.getParent() == flRoot) flRoot.removeView(ghost); }
                            catch (Throwable ignored) {}
                        }).start();
            }
        } catch (Throwable ignored) {}
        sdOverlay = null;
        sdGhost = null;
        setSdTarget(-1);
        for (SdGridLayout g : sdGridPages()) {
            for (int ci = 0; ci < g.getChildCount(); ci++) {
                View c = g.getChildAt(ci);
                try { c.animate().scaleX(1f).scaleY(1f).setDuration(90).start(); } catch (Throwable ignored) {}
                // v1.3.89 — the lifted-out source cell returns (see beginSdDrag)
                try { c.animate().alpha(1f).setDuration(120).start(); } catch (Throwable ignored) {}
                // v1.3.92 — the live-gap preview closes on BOTH axes
                try { c.animate().translationX(0f).translationY(0f).setDuration(140).start(); } catch (Throwable ignored) {}
            }
        }
        sdGapShown = -2;
        if (homeScroll != null) homeScroll.requestDisallowInterceptTouchEvent(false);
        // v1.3.92 — the pager gets its swipe back
        try { if (sdPager != null) sdPager.requestDisallowInterceptTouchEvent(false); }
        catch (Throwable ignored) {}
        // v1.3.98 — the pager-clash locks open in the other direction: the
        // swipe returns the moment no drag is armed (unarmed swipes page the
        // home — the launcher standard — they only lose to an ARMED drag).
        try { if (sdPager != null) sdPager.setUserInputEnabled(true); }
        catch (Throwable ignored) {}
        try {
            if (sdDragCell != null && sdDragCell.getParent() != null)
                sdDragCell.getParent().requestDisallowInterceptTouchEvent(false);
        } catch (Throwable ignored) {}
        sdDragCell = null;
        sdDragSrc = -1;
        sdDragMoved = false;
        sdDragNoMenu = false;   // v1.3.97: the next drag arms the hold-menu again
        sdDragSrcIsGroup = false;   // v1.4.0: the next drag re-tests for a folder
    }

    /** long-press menu for a shortcut tile.
     *  v1.3.97 — THE ICON-ROW SHEET (the field: "add icons to the menu, pure
     *  text looks boring"): the plain AlertDialog list is gone; the same
     *  showSheetMenu the group sheet and the main menu use renders every
     *  action with its glyph in the skin-aware icon container, so all the
     *  app's menus read as one family. The Move row sets sdDragNoMenu —
     *  the re-grab of the tile must not re-pop this sheet mid-drag. */
    protected void sdTileMenu(final int idx) {
        org.json.JSONObject o = prefs.speeddialAt(idx);
        if (o == null) return;
        if (o.optBoolean("group", false)) {
            // v1.4.0 — holding a FOLDER opens its EDIT menu (rename /
            // remove shortcuts / ungroup / delete); tapping opens the
            // floating window (showSdFolderWindow). This is the hold-menu
            // door (sdHoldMenu) and the release-without-move door
            // (sdDragEnd) — both land here for folders.
            sdGroupEditMenu(idx);
            return;
        }
        final String url = o.optString("url");
        String title = o.optString("title");
        List<MenuRow> rows = new ArrayList<>();
        rows.add(row(R.drawable.ic_globe, getString(R.string.open), v -> {
            dismissAllOverlays();
            loadInTab(url);
        }));
        rows.add(row(R.drawable.ic_open_new, getString(R.string.open_in_new_tab), v -> {
            dismissAllOverlays();
            openNewTab(url);
        }));
        rows.add(row(R.drawable.ic_edit, getString(R.string.edit_shortcut),   // v1.3.79: name AND address
                v -> {
            dismissAllOverlays();
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            final EditText etName = new EditText(this);
            etName.setHint(R.string.title_label);
            etName.setText(title);
            final EditText etUrl = new EditText(this);
            etUrl.setHint(R.string.url_label);
            etUrl.setText(url);
            etUrl.setSingleLine(true);
            int pad = Fx.dp(this, 8);
            box.setPadding(pad, pad, pad, 0);
            box.addView(etName);
            box.addView(etUrl);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.edit_shortcut)
                    .setView(box)
                    .setPositiveButton(R.string.save, (d2, w2) -> {
                        prefs.editSpeeddial(idx,
                                etName.getText().toString().trim(),
                                etUrl.getText().toString().trim());
                        refreshHome();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }));
        rows.add(row(R.drawable.ic_move, getString(R.string.move_shortcut),   // v1.3.79: re-enter drag from the menu
                v -> {
            dismissAllOverlays();
            // re-enter drag mode for this tile (move by menu); the hold-menu
            // stays out of it (v1.3.97 — see sdDragNoMenu)
            View cell = sdCellOfIndex(idx);
            if (cell != null) {
                sdDragNoMenu = true;
                beginSdDrag(idx, cell);
            } else refreshHome();
        }));
        rows.add(row(R.drawable.ic_folder_plus, getString(R.string.new_folder),  // v1.3.79: empty folder tile
                v -> {
            dismissAllOverlays();
            final EditText et = new EditText(this);
            et.setHint(R.string.title_label);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.new_folder)
                    .setView(et)
                    .setPositiveButton(R.string.save, (d2, w2) -> {
                        prefs.addSpeeddialFolder(et.getText().toString().trim());
                        refreshHome();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }));
        rows.add(row(R.drawable.ic_delete, getString(R.string.delete), v -> {
            // v1.3.91 — DELETE + UNDO (the Fenix/DuckDuckGo pattern: a
            // removal from the menu is never final; the entry is captured
            // whole and the snackbar's Undo puts it back at the SAME index.
            // v1.4.6: the window is Ui.UNDO_MS everywhere (3.5 s).
            dismissAllOverlays();
            final org.json.JSONObject gone = prefs.speeddialAt(idx);
            prefs.removeSpeeddial(idx);
            refreshHome();
            if (gone != null) {
                com.bolt.browser.util.Ui.snackbar(this,
                        getString(R.string.shortcut_removed),
                        getString(R.string.undo), () -> {
                            try {
                                prefs.insertSpeeddial(Math.min(idx,
                                        prefs.speeddial().length()), gone);
                                refreshHome();
                            } catch (Throwable ignored) {}
                        }, com.bolt.browser.util.Ui.UNDO_MS, null);
            }
        }));
        showSheetMenu(title, rows);
    }

    /** the live cell view for a speeddial index (drag re-entry from the
     *  tile menu needs it). */
    protected View sdCellOfIndex(int idx) {
        for (View c : sdCells) {
            Object tag = c.getTag();
            if (tag instanceof Integer && (Integer) tag == idx) return c;
        }
        return null;
    }

    /** v1.3.79 — THE LOGO PICKER (long-press the home logo): Bolt mark /
     *  blank / a custom image from the gallery. */
    protected void showHomeLogoPicker() {
        try {
            List<String> opts = new ArrayList<>();
            opts.add(getString(R.string.home_logo_default));
            opts.add(getString(R.string.home_logo_blank));
            opts.add(getString(R.string.home_logo_custom));
            new AlertDialog.Builder(this)
                    .setTitle(R.string.home_logo)
                    .setItems(opts.toArray(new String[0]), (d, w) -> {
                        if (w == 0) { prefs.setHomeLogo(0); refreshHome(); }
                        else if (w == 1) { prefs.setHomeLogo(1); refreshHome(); }
                        else {
                            try {
                                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                                i.addCategory(Intent.CATEGORY_OPENABLE);
                                i.setType("image/*");
                                startActivityForResult(Intent.createChooser(i,
                                        getString(R.string.home_logo)), 1012);
                            } catch (Throwable t) {
                                Fx.toast(this, getString(R.string.wallpaper_pick_failed));
                            }
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } catch (Throwable ignored) {}
    }

    /** v1.3.79 — decode the picked logo (long edge capped at 512, PNG so
     *  transparency survives) and persist it as filesDir/home_logo.png. */
    protected void applyHomeLogo(final android.net.Uri uri) {
        new Thread(() -> {
            boolean ok = false;
            try {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in != null) {
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
                    try { in.close(); } catch (Throwable ignored) {}
                    if (bmp != null) {
                        int longE = Math.max(bmp.getWidth(), bmp.getHeight());
                        if (longE > 512) {
                            float sc = 512f / longE;
                            android.graphics.Bitmap sm = android.graphics.Bitmap.createScaledBitmap(bmp,
                                    Math.round(bmp.getWidth() * sc),
                                    Math.round(bmp.getHeight() * sc), true);
                            if (sm != bmp) bmp.recycle();
                            bmp = sm;
                        }
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(prefs.homeLogoFile());
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 92, fo);
                        try { fo.close(); } catch (Throwable ignored) {}
                        bmp.recycle();
                        ok = prefs.homeLogoFile().length() > 0;
                    }
                }
            } catch (Throwable ignored) {}
            final boolean saved = ok;
            ui.post(() -> {
                if (saved) {
                    prefs.setHomeLogo(2);
                    refreshHome();
                    Fx.toast(BrowserActivity.this, getString(R.string.saved));
                } else {
                    Fx.toast(BrowserActivity.this, getString(R.string.wallpaper_pick_failed));
                }
            });
        }, "bolt-logo").start();
    }

    // ---------------- v1.4.0 the launcher folder window + the folder edit menu ----------------

    /** dismiss the live folder window, no animation (the rebuild paths —
     *  refreshHome, tab switches, a new drag — want it gone instantly). */
    protected void dismissSdFolderWindow() {
        try {
            if (sdFolderScrim != null && sdFolderScrim.getParent() == flRoot)
                flRoot.removeView(sdFolderScrim);
        } catch (Throwable ignored) {}
        try {
            if (sdFolderWin != null && sdFolderWin.getParent() == flRoot)
                flRoot.removeView(sdFolderWin);
        } catch (Throwable ignored) {}
        sdFolderScrim = null;
        sdFolderWin = null;
        // v1.4.4 — the window's drag state dies with the window (a live
        // gesture would otherwise feed a detached grid)
        sdWinDragCell = null;
        sdWinGrid = null;
        sdWinFolderIdx = -1;
        sdWinDragFrom = sdWinDragTo = -1;
        sdWinRimmed = null;
        sdWinHeld = false;
        sdWinH.removeCallbacksAndMessages(null);
    }

    /** dismiss with the close animation (the scrim tap + the open-a-member
     *  paths — the panel folds away the way it popped in). */
    protected void dismissSdFolderWindowAnimated() {
        final View scrim = sdFolderScrim;
        final LinearLayout panel = sdFolderWin;
        sdFolderScrim = null;
        sdFolderWin = null;
        try {
            if (scrim != null && scrim.getParent() == flRoot) {
                scrim.animate().alpha(0f).setDuration(100).withEndAction(() -> {
                    try { if (scrim.getParent() == flRoot) flRoot.removeView(scrim); }
                    catch (Throwable ignored) {}
                }).start();
            }
        } catch (Throwable ignored) {}
        try {
            if (panel != null && panel.getParent() == flRoot) {
                panel.animate().scaleX(0.88f).scaleY(0.88f).alpha(0f)
                        .setDuration(100).withEndAction(() -> {
                            try { if (panel.getParent() == flRoot) flRoot.removeView(panel); }
                            catch (Throwable ignored) {}
                        }).start();
            }
        } catch (Throwable ignored) {}
    }

    /** v1.4.0 — THE LAUNCHER FOLDER WINDOW (the report: "when I tap
     *  homescreen shortcuts groups, I want the group to open like a
     *  floating window showing the icons inside the group"). A dim scrim
     *  plus a rounded panel floating over the grid, anchored to the folder
     *  tile and clamped on screen; inside: the folder name, the member
     *  ICONS (the same favicon-tile family as the home cells, 3 per row,
     *  scrollable past nine), and a quiet Rename/Ungroup row. Tap an icon
     *  to open it; hold an icon for Open-in-new-tab / Remove-from-folder;
     *  tap the scrim to close. Editing the group itself stays on the HOLD
     *  door (sdGroupEditMenu) — tap opens, hold edits, drag reorders. */
    protected void showSdFolderWindow(final int idx) {
        org.json.JSONObject o = prefs.speeddialAt(idx);
        if (o == null || !o.optBoolean("group", false)) return;
        if (flRoot == null) return;
        if (sdFolderWin != null) dismissSdFolderWindow();
        try {
            final String name = o.optString("name", "Group");
            org.json.JSONArray items = o.optJSONArray("items");
            final int count = items == null ? 0 : items.length();
            int txt = Fx.txt(this);
            int dim = Fx.dim(this);

            // ---- the panel ----
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable pg =
                    new android.graphics.drawable.GradientDrawable();
            pg.setCornerRadius(Fx.dp(this, 26));
            if (Fx.isGlass(this)) {
                boolean dark = prefs.isDark();
                pg.setColor(dark ? 0xF0141420 : 0xFCF4F5FB);
                pg.setStroke(Math.max(1, Fx.dp(this, 1)),
                        dark ? 0x33FFFFFF : 0x291B1B2E);
            } else {
                pg.setColor(Fx.surface(this));
                pg.setStroke(Math.max(1, Fx.dp(this, 1)), Fx.outline(this));
            }
            panel.setBackground(pg);
            panel.setElevation(Fx.dp(this, 12));
            int pad = Fx.dp(this, 14);
            panel.setPadding(pad, pad, pad, Fx.dp(this, 8));

            // title: the folder name + member count
            TextView title = new TextView(this);
            title.setText(name);
            title.setTextColor(txt);
            title.setTextSize(16);
            title.setTypeface(com.bolt.browser.util.Ui.medium());
            title.setGravity(Gravity.CENTER);
            title.setSingleLine(true);
            panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
            TextView sub = new TextView(this);
            sub.setText(count + " " + getString(R.string.shortcuts));
            sub.setTextColor(dim);
            sub.setTextSize(11);
            sub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(-1, -2);
            slp2.bottomMargin = Fx.dp(this, 6);
            panel.addView(sub, slp2);

            // the member icons: 3 per row, scrolling past nine
            android.widget.ScrollView gridScroll = new android.widget.ScrollView(this);
            gridScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            android.widget.GridLayout grid = new android.widget.GridLayout(this);
            grid.setColumnCount(3);
            grid.setOrientation(android.widget.GridLayout.HORIZONTAL);
            int cellW = Fx.dp(this, 64);
            // v1.4.1 — THE SHARED-LAYOUTPARAMS CORPSE (the v1.4.0 report:
            // "opening it, shows all the icons inside the group overlapping
            // above each other instead of forming a nice matrix"). GridLayout's
            // validateLayoutParams() auto-flows UNDEFINED specs and then WRITES
            // the resolved cell back into the child's LayoutParams
            // (setCellGroup -> setRowSpecSpan/setColumnSpecSpan, verified in
            // the AOSP GridLayout source). The v1.4.0 window shared ONE glp
            // object across every cell: child 0 resolved to (0,0), the shared
            // spec turned DEFINED, and every later child read that same spec —
            // ALL icons stacked in cell (0,0). The cure is structural: a FRESH
            // LayoutParams per cell carrying EXPLICIT row/column specs — the
            // matrix is declared, never auto-resolved, so no write-back can
            // move a neighbour.
            // v1.4.4 — the cells build through ONE shared helper now: the
            // initial layout AND the in-place rebuild after an in-window
            // drag reorder run the same code (sdWinFillGrid), so the
            // rearranged window can never drift from a fresh one.
            sdWinGrid = grid;
            sdWinFolderIdx = idx;
            boolean any = sdWinFillGrid(grid, cellW, txt);
            if (!any) {
                // the empty folder: the fill hint (full-width, NOT a 64dp cell)
                TextView hint = new TextView(this);
                hint.setText(R.string.folder_empty_hint);
                hint.setTextColor(dim);
                hint.setTextSize(12);
                hint.setGravity(Gravity.CENTER);
                hint.setPadding(Fx.dp(this, 8), Fx.dp(this, 14), Fx.dp(this, 8), Fx.dp(this, 14));
                grid.addView(hint, new android.widget.GridLayout.LayoutParams(
                        android.widget.GridLayout.spec(0, 1),
                        android.widget.GridLayout.spec(0, 3)));
            }
            gridScroll.addView(grid, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            LinearLayout.LayoutParams gsl = new LinearLayout.LayoutParams(-2, -2);
            gsl.topMargin = Fx.dp(this, 2);
            panel.addView(gridScroll, gsl);

            // the quiet action row: Rename | Ungroup
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER);
            bar.setPadding(0, Fx.dp(this, 4), 0, Fx.dp(this, 2));
            TextView rename = new TextView(this);
            rename.setText(R.string.rename);
            rename.setTextColor(Fx.accentText(this));
            rename.setTextSize(13);
            rename.setTypeface(com.bolt.browser.util.Ui.medium());
            rename.setPadding(Fx.dp(this, 14), Fx.dp(this, 10), Fx.dp(this, 14), Fx.dp(this, 10));
            rename.setOnClickListener(v -> {
                dismissSdFolderWindow();
                sdRenameGroupDialog(idx);
            });
            bar.addView(rename);
            TextView ungroup = new TextView(this);
            ungroup.setText(R.string.ungroup);
            ungroup.setTextColor(Fx.accentText(this));
            ungroup.setTextSize(13);
            ungroup.setTypeface(com.bolt.browser.util.Ui.medium());
            ungroup.setPadding(Fx.dp(this, 14), Fx.dp(this, 10), Fx.dp(this, 14), Fx.dp(this, 10));
            ungroup.setOnClickListener(v -> {
                dismissSdFolderWindow();
                prefs.dissolveSpeeddialGroup(idx);
                refreshHomeQuiet();
                Fx.toast(BrowserActivity.this, getString(R.string.ungrouped));
            });
            bar.addView(ungroup);
            panel.addView(bar, new LinearLayout.LayoutParams(-2, -2));

            // ---- measure + anchor to the folder tile ----
            int pw = Math.min(flRoot.getWidth() - Fx.dp(this, 32),
                    3 * cellW + 3 * Fx.dp(this, 8) + 2 * pad);
            panel.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int ph = panel.getMeasuredHeight();
            int maxH = (int) (flRoot.getHeight() * 0.6f);
            if (ph > maxH) {
                ph = maxH;
                panel.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY));
            }
            android.graphics.Rect r = new android.graphics.Rect();
            View cell = sdCellOfIndex(idx);
            int[] fl = new int[2];
            flRoot.getLocationOnScreen(fl);
            boolean have = cell != null && cell.getGlobalVisibleRect(r);
            int x, y;
            if (have) {
                x = r.centerX() - fl[0] - pw / 2;
                y = r.top - fl[1] - ph - Fx.dp(this, 10);
                if (y < Fx.dp(this, 16)) y = r.bottom - fl[1] + Fx.dp(this, 10);
            } else {
                x = (flRoot.getWidth() - pw) / 2;
                y = (flRoot.getHeight() - ph) / 2;
            }
            x = Math.max(Fx.dp(this, 16), Math.min(x, flRoot.getWidth() - pw - Fx.dp(this, 16)));
            y = Math.max(Fx.dp(this, 16), Math.min(y, flRoot.getHeight() - ph - Fx.dp(this, 16)));

            // ---- the scrim (tap to close) ----
            View scrim = new View(this);
            scrim.setBackgroundColor(0x59000000);
            scrim.setOnClickListener(v -> dismissSdFolderWindowAnimated());
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(140).start();

            flRoot.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(pw,
                    ph > maxH ? ph : -2);   // capped panels ride the EXACT height
            panel.setX(x);
            panel.setY(y);
            panel.setAlpha(0f);
            panel.setScaleX(0.9f);
            panel.setScaleY(0.9f);
            flRoot.addView(panel, plp);
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(150)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f))
                    .start();
            sdFolderScrim = scrim;
            sdFolderWin = panel;
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------- v1.4.4 the folder-window icon drag

    /** (re)fill the folder window's grid from the LIVE folder json — the
     *  one builder for the initial layout and every in-place rebuild. */
    protected boolean sdWinFillGrid(android.widget.GridLayout grid, int cellW, int txt) {
        grid.removeAllViews();
        org.json.JSONObject g = sdWinFolderIdx >= 0 ? prefs.speeddialAt(sdWinFolderIdx) : null;
        org.json.JSONArray items = g == null ? null : g.optJSONArray("items");
        boolean any = false;
        int placed = 0;
        if (items != null) {
            for (int m = 0; m < items.length(); m++) {
                org.json.JSONObject it = items.optJSONObject(m);
                if (it == null) continue;
                sdWinAddCell(grid, cellW, txt, it.optString("url"), it.optString("title"), m);
                placed++;
                any = true;
            }
        }
        // v1.4.8 — THE "+" TILE (the "add this kind of direct addition to
        // other relevant places" half): a folder finally grows from ITS OWN
        // window too — the tile sits one slot past the last member, opens
        // the name+URL mini dialog, and the shortcut lands straight INTO
        // this folder (Prefs.addSpeeddialItemTo). The in-window drag math
        // is untouched: the tile is the LAST grid child, so a drop onto it
        // computes t = memberCount (the append position) exactly as
        // dropping past the last member always did.
        try {
            int row = placed / 3, col = placed % 3;
            LinearLayout plus = new LinearLayout(this);
            plus.setOrientation(LinearLayout.VERTICAL);
            plus.setGravity(Gravity.CENTER_HORIZONTAL);
            plus.setPadding(0, Fx.dp(this, 4), 0, Fx.dp(this, 4));
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(R.drawable.ic_plus);
            int side = Fx.dp(this, 34);
            android.widget.FrameLayout shell = tileShell(iv);
            try { com.bolt.browser.util.Ui.iconify(iv, false); } catch (Throwable ignored) {}
            iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(side, side, Gravity.CENTER));
            plus.addView(shell);
            TextView lbl = new TextView(this);
            lbl.setText(R.string.add);
            lbl.setTextColor(Fx.dim(this));
            lbl.setTextSize(10);
            lbl.setSingleLine(true);
            lbl.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(cellW - Fx.dp(this, 8), -2);
            llp.topMargin = Fx.dp(this, 5);
            plus.addView(lbl, llp);
            plus.setOnClickListener(v -> {
                com.bolt.browser.util.Ui.tap(v);
                addShortcutInsideFolderDialog(sdWinFolderIdx);
            });
            grid.addView(plus, new android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(row, 1),
                    android.widget.GridLayout.spec(col, 1)));
        } catch (Throwable ignored) {}
        return any;
    }

    /** one folder-window cell: the icon + label + tap + the v1.4.4 drag
     *  wiring (hold+move rearranges inside the window; hold-still opens the
     *  item menu; tap opens the site). The v1.4.1 fresh-LayoutParams law
     *  (explicit spec(row)/spec(col) per cell) carries over verbatim. */
    protected void sdWinAddCell(final android.widget.GridLayout grid, final int cellW,
            int txt, final String url, final String tTitle, final int itemIdx) {
        LinearLayout cellV = new LinearLayout(this);
        cellV.setOrientation(LinearLayout.VERTICAL);
        cellV.setGravity(Gravity.CENTER_HORIZONTAL);
        cellV.setPadding(0, Fx.dp(this, 4), 0, Fx.dp(this, 4));
        android.widget.ImageView av = new android.widget.ImageView(this);
        int innerR = (int) (Fx.iconRadius(this) * 0.8f);
        av.setImageDrawable(com.bolt.browser.util.FaviconStore.avatar(
                this, Fx.hostOf(url), tTitle, 34, innerR));
        av.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        android.widget.FrameLayout shell = tileShell(av);
        // v1.4.9 — THE VISIBLE ⋯ (the report: "no indication that a menu
        // tab exists there"): every cell in the folder window wears its own
        // menu button — Open / Edit shortcut (name+address) / Move to home
        // (de-group) / Delete — no hold gesture needed to find any of it.
        // The button is a child of the shell, so its taps never reach the
        // cell's composed drag listener (tilePressFx + sdWinCellTouch);
        // touches on the rest of the cell keep the full gesture set.
        android.widget.ImageButton more = new android.widget.ImageButton(this);
        more.setImageResource(R.drawable.ic_more);
        more.setBackground(getDrawable(R.drawable.bg_tab_btn));
        more.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        more.setPadding(Fx.dp(this, 3), Fx.dp(this, 3), Fx.dp(this, 3), Fx.dp(this, 3));
        try { com.bolt.browser.util.Ui.iconify(more, false); } catch (Throwable ignored) {}
        android.widget.FrameLayout.LayoutParams mlp = new android.widget.FrameLayout.LayoutParams(
                Fx.dp(this, 20), Fx.dp(this, 20), Gravity.TOP | Gravity.END);
        mlp.rightMargin = Fx.dp(this, -2);
        mlp.topMargin = Fx.dp(this, -2);
        more.setLayoutParams(mlp);
        more.setOnClickListener(v -> {
            com.bolt.browser.util.Ui.tap(v);
            sdWinItemMenu(itemIdx, url, tTitle);
        });
        shell.addView(more);
        // set AFTER tileShell (its addView wrap-contents the frame)
        av.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                Fx.dp(this, 34), Fx.dp(this, 34), Gravity.CENTER));
        cellV.addView(shell);
        TextView lbl = new TextView(this);
        lbl.setText(tTitle == null || tTitle.isEmpty() ? Fx.hostOf(url) : tTitle);
        lbl.setTextColor(txt);
        lbl.setTextSize(10);
        lbl.setSingleLine(true);
        lbl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                cellW - Fx.dp(this, 8), -2);
        llp.topMargin = Fx.dp(this, 5);
        cellV.addView(lbl, llp);
        cellV.setOnClickListener(v -> {
            dismissSdFolderWindowAnimated();
            dismissAllOverlays();
            loadInTab(url);
        });
        // v1.4.6 — THE CLOBBER, ROUND TWO (lesson 98): the old order here
        // was wireSdWinCell(...) then tilePressFx(cellV, shell) — the second
        // call's setOnTouchListener SILENTLY REPLACED the first, so the
        // v1.4.4 drag wiring (and the v1.4.5 interception arms inside it)
        // never ran on device: the window's icons only ever had press-fx +
        // the GRID's drag feed (sdCellTouch — a no-op inside the window),
        // so "the shortcuts inside the group don't move when I hold and
        // drag" while the data layer and every source gate stayed green.
        // ONE composed install now: tilePressFx takes the drag feed as a
        // parameter and the window passes ITS OWN (sdWinCellTouch builds
        // it — tap stays a click, hold-still the item menu, hold+move the
        // reorder, the interception law armed at lift).
        final android.view.View.OnTouchListener winDrag =
                sdWinCellTouch(itemIdx, url, tTitle);
        tilePressFx(cellV, shell, winDrag);
        android.widget.GridLayout.LayoutParams glp =
                new android.widget.GridLayout.LayoutParams();
        glp.rowSpec = android.widget.GridLayout.spec(itemIdx / 3);
        glp.columnSpec = android.widget.GridLayout.spec(itemIdx % 3);
        glp.width = cellW;
        glp.height = -2;
        glp.setMargins(Fx.dp(this, 4), Fx.dp(this, 4), Fx.dp(this, 4), Fx.dp(this, 4));
        grid.addView(cellV, glp);
    }

    /** the whole folder-window gesture per cell: tap = open (the click
     *  listener above), hold-still = the item menu, hold+move = rearrange
     *  (the tabs-mini pattern: lift at ~380 ms, siblings glide, drop
     *  resolves the insertion).
     *  v1.4.6 — THE CLOBBER, ROUND TWO: this used to INSTALL itself
     *  (setOnTouchListener) and sdWinAddCell then called tilePressFx one
     *  line later, which REPLACED it — the drag (and the v1.4.5
     *  interception arms inside it) never ran on device. The builder now
     *  RETURNS the listener; sdWinAddCell hands it to tilePressFx as the
     *  composed drag feed (press-fx first, then this), so exactly ONE
     *  listener ever owns the cell. */
    protected android.view.View.OnTouchListener sdWinCellTouch(final int itemIdx,
            final String url, final String tTitle) {
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (sdWinItemCount() < 2) return false;   // nothing to rearrange
                    sdWinDownX = sdWinLastX = e.getRawX();
                    sdWinDownY = sdWinLastY = e.getRawY();
                    sdWinHeld = false;
                    sdWinLiftCandidate = v;
                    sdWinH.removeCallbacksAndMessages(null);
                    sdWinLiftRun = () -> {
                        if (sdWinLiftCandidate == v && sdWinDragCell == null) {
                            sdWinHeld = true;
                            sdWinLiftX = sdWinLastX;
                            sdWinLiftY = sdWinLastY;
                            sdWinDragCell = v;
                            sdWinDragFrom = itemIdx;
                            sdWinDragTo = itemIdx;
                            sdWinDragMoved = false;
                            // v1.4.5 — THE INTERCEPTION LAW: the window's
                            // cells ride a ScrollView — without the disallow
                            // flag the first vertical MOVE is intercepted, the
                            // cell receives ACTION_CANCEL and the drag dies
                            // before a slot moved (the v1.4.4 corpse: the
                            // hold lifted, the drag itself was dead). The
                            // flag arms the parent + every ANCESTOR (the
                            // scroll view, the panel, the root) — released at
                            // UP/CANCEL below.
                            try {
                                android.view.ViewParent vp = v.getParent();
                                if (vp != null) vp.requestDisallowInterceptTouchEvent(true);
                            } catch (Throwable ignored) {}
                            try {
                                com.bolt.browser.util.Ui.tap(v);
                                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start();
                                v.setElevation(Fx.dp(BrowserActivity.this, 8));
                            } catch (Throwable ignored) {}
                        }
                    };
                    sdWinH.postDelayed(sdWinLiftRun, 380);
                    return false;      // the click machinery runs until the lift
                case android.view.MotionEvent.ACTION_MOVE:
                    sdWinLastX = e.getRawX();
                    sdWinLastY = e.getRawY();
                    if (sdWinDragCell != null && v == sdWinDragCell) {
                        sdWinDragMove(e.getRawX(), e.getRawY());
                        return true;
                    }
                    if (!sdWinHeld && (Math.abs(e.getRawX() - sdWinDownX) > Fx.dp(this, 8)
                            || Math.abs(e.getRawY() - sdWinDownY) > Fx.dp(this, 8))) {
                        sdWinH.removeCallbacksAndMessages(null);   // a scroll, not a hold
                    }
                    return false;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    sdWinH.removeCallbacksAndMessages(null);
                    sdWinLiftCandidate = null;
                    // v1.4.5 — the gesture ends: hand the touch stream back
                    // (the interception law; the window must scroll again)
                    try {
                        android.view.ViewParent vp = v.getParent();
                        if (vp != null) vp.requestDisallowInterceptTouchEvent(false);
                    } catch (Throwable ignored) {}
                    if (sdWinDragCell != null && v == sdWinDragCell) {
                        sdWinDragEnd(e.getRawX(), e.getRawY());
                        return true;
                    }
                    if (sdWinHeld) {     // lifted but never moved: the item menu
                        sdWinHeld = false;
                        try {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                        } catch (Throwable ignored) {}
                        sdWinItemMenu(itemIdx, url, tTitle);
                        return true;    // consume: a hold, not a tap
                    }
                    return false;
                default:
                    return false;
            }
        };
    }

    /** the live folder's item count (the window's own source of truth). */
    protected int sdWinItemCount() {
        try {
            org.json.JSONObject g = sdWinFolderIdx >= 0 ? prefs.speeddialAt(sdWinFolderIdx) : null;
            org.json.JSONArray items = g == null ? null : g.optJSONArray("items");
            return items == null ? 0 : items.length();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** the v1.4.0 item menu, fired from the hold-and-release AND (v1.4.9)
     *  from the cell's own ⋯ button — the visible door the report asked
     *  for ("no indication that a menu tab exists there"): every shortcut
     *  inside a home folder can be opened, EDITED (name + address),
     *  DE-GROUPED (moved to the home grid, kept) or deleted without
     *  touching a drag gesture. */
    protected void sdWinItemMenu(final int itemIdx, final String url, final String tTitle) {
        List<MenuRow> rows = new ArrayList<>();
        rows.add(row(R.drawable.ic_globe, getString(R.string.open), v2 -> {
            dismissAllOverlays();
            dismissSdFolderWindow();
            loadInTab(url);
        }));
        rows.add(row(R.drawable.ic_open_new, getString(R.string.open_in_new_tab), v2 -> {
            dismissAllOverlays();
            dismissSdFolderWindow();
            openNewTab(url);
        }));
        // v1.4.9 — EDIT NAME + LINK (the report: "edit name, link, de-group,
        // delete etc. to all"): the same two-field dialog the top-level
        // tile's Edit shortcut uses, writing through the new in-folder
        // edit (editSpeeddialItemIn)
        rows.add(row(R.drawable.ic_edit, getString(R.string.edit_shortcut), v2 -> {
            final int at = sdWinFolderIdx;
            final org.json.JSONObject item = shortcutItemAt(at, itemIdx);
            if (item == null) return;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            final EditText etName = new EditText(this);
            etName.setHint(R.string.title_label);
            etName.setText(item.optString("title"));
            final EditText etUrl = new EditText(this);
            etUrl.setHint(R.string.url_label);
            etUrl.setText(item.optString("url"));
            etUrl.setSingleLine(true);
            int pad = Fx.dp(this, 8);
            box.setPadding(pad, pad, pad, 0);
            box.addView(etName);
            box.addView(etUrl);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.edit_shortcut)
                    .setView(box)
                    .setPositiveButton(R.string.save, (d2, w2) -> {
                        try {
                            prefs.editSpeeddialItemIn(at, itemIdx,
                                    etName.getText().toString().trim(),
                                    etUrl.getText().toString().trim());
                        } catch (Throwable ignored) {}
                        refreshHomeQuiet();
                        // the open window rebuilds in place with the new name/icon
                        try { rebuildSdFolderWindowInPlace(); } catch (Throwable ignored) {}
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }));
        // v1.4.9 — DE-GROUP (the report's "de-group"): the shortcut moves
        // OUT to the home grid's top level and is KEPT (ejectSpeeddialItem)
        // — the old menu's only "remove" DELETED it, so there was no way to
        // unfile a shortcut without dragging it onto the grid
        rows.add(row(R.drawable.ic_move, getString(R.string.move_to_home), v2 -> {
            final int at = sdWinFolderIdx;
            dismissAllOverlays();
            try { prefs.ejectSpeeddialItem(at, itemIdx); } catch (Throwable ignored) {}
            dismissSdFolderWindow();
            refreshHomeQuiet();
            Fx.toast(this, getString(R.string.move_to_home));
        }));
        rows.add(row(R.drawable.ic_delete, getString(R.string.delete_shortcut), v2 -> {
            // v1.4.6 — DELETE + UNDO (the "an undo for deleting anything"
            // family): removeSpeeddialItem can destroy the shortcut AND
            // dissolve the folder (a 2-item folder degenerates to a plain
            // shortcut, the last item removes the whole entry), so the
            // WHOLE folder entry is captured — Undo puts it back at the
            // same index, whatever the folder degenerated into in between.
            // THE NO-OP CORPSE: the capture (and the remove!) must happen
            // BEFORE dismissAllOverlays — it tears the folder window down
            // and zeroes sdWinFolderIdx, so the OLD order read -1 and
            // removeSpeeddialItem guarded out: a dead button since v1.4.0
            // (lesson 92's family).
            final int at = sdWinFolderIdx;
            final org.json.JSONObject gone = prefs.speeddialAt(at);
            dismissAllOverlays();
            prefs.removeSpeeddialItem(at, itemIdx);
            dismissSdFolderWindow();
            refreshHomeQuiet();
            if (gone != null) {
                com.bolt.browser.util.Ui.snackbar(this,
                        getString(R.string.shortcut_removed),
                        getString(R.string.undo), () -> {
                            try {
                                if (at >= 0 && at < prefs.speeddial().length()) {
                                    prefs.removeSpeeddial(at);   // the degenerated remnant
                                }
                                prefs.insertSpeeddial(Math.min(at,
                                        prefs.speeddial().length()), gone);
                                refreshHomeQuiet();
                            } catch (Throwable ignored) {}
                        }, com.bolt.browser.util.Ui.UNDO_MS, null);
            }
        }));
        showSheetMenu(tTitle == null || tTitle.isEmpty() ? Fx.hostOf(url) : tTitle, rows);
    }

    /** v1.4.9 — the live item object at (folderIdx, itemIdx) — the edit
     *  dialog's source of truth (the url/title captured at bind time go
     *  stale the moment anything edits the entry). */
    protected org.json.JSONObject shortcutItemAt(int folderIdx, int itemIdx) {
        try {
            org.json.JSONObject g = folderIdx >= 0 ? prefs.speeddialAt(folderIdx) : null;
            org.json.JSONArray items = g == null ? null : g.optJSONArray("items");
            return items == null || itemIdx < 0 || itemIdx >= items.length()
                    ? null : items.optJSONObject(itemIdx);
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.9 — rebuild the OPEN folder window's grid in place (an in-place
     *  edit changed a name/icon): re-runs sdWinFillGrid on the live window
     *  without tearing the overlay down. */
    protected void rebuildSdFolderWindowInPlace() {
        try {
            if (sdWinGrid != null && sdWinFolderIdx >= 0) {
                int cw = sdWinGrid.getWidth() > 0
                        ? sdWinGrid.getWidth() / 3 : Fx.dp(this, 104);
                sdWinFillGrid(sdWinGrid, cw, Fx.txt(this));
            }
        } catch (Throwable ignored) {}
    }

    /** a MOVE while the window drag is armed: the cell rides the finger,
     *  the hovered cell wears the accent rim, and the siblings glide into
     *  their would-be slots (slot math on BASE positions — translations
     *  never feed the math). */
    protected void sdWinDragMove(float rawX, float rawY) {
        if (sdWinDragCell == null || sdWinGrid == null) return;
        if (!sdWinDragMoved) {
            if (Math.abs(rawX - sdWinLiftX) < Fx.dp(this, 8)
                    && Math.abs(rawY - sdWinLiftY) < Fx.dp(this, 8)) return;
            sdWinDragMoved = true;
        }
        sdWinDragCell.setTranslationX(rawX - sdWinLiftX);
        sdWinDragCell.setTranslationY(rawY - sdWinLiftY);
        try {
            int n = sdWinGrid.getChildCount();
            // the insertion target: the cell whose BASE rect holds the
            // finger (nearest by center otherwise); t = its others-index,
            // +1 when the finger sits past its center
            int hit = -1, nearest = -1;
            double bestD = Double.MAX_VALUE;
            android.graphics.Rect r = new android.graphics.Rect();
            for (int i = 0; i < n; i++) {
                View c = sdWinGrid.getChildAt(i);
                if (c == sdWinDragCell) continue;
                c.getGlobalVisibleRect(r);
                if (r.contains((int) rawX, (int) rawY)) { hit = i; break; }
                double d = Math.hypot(r.centerX() - rawX, r.centerY() - rawY);
                if (d < bestD) { bestD = d; nearest = i; }
            }
            int over = hit >= 0 ? hit : nearest;
            if (over < 0) return;
            View ov = sdWinGrid.getChildAt(over);
            ov.getGlobalVisibleRect(r);
            boolean past = (rawY > r.centerY() + 0.26 * r.height())
                    || (Math.abs(rawY - r.centerY()) <= 0.26 * r.height()
                        && rawX > r.centerX());
            // the others-index of the hovered cell + past decides t
            int t = 0;
            for (int i = 0; i < n; i++) {
                if (i == sdWinDragFrom) continue;
                if (i == over) break;
                t++;
            }
            if (past) t++;
            // the rim follows the hovered cell
            if (sdWinRimmed != ov) {
                if (sdWinRimmed != null) sdWinRimmed.setForeground(null);
                android.graphics.drawable.GradientDrawable rg =
                        new android.graphics.drawable.GradientDrawable();
                rg.setCornerRadius(Fx.dp(this, 14));
                rg.setStroke(Math.max(2, Fx.dp(this, 2)), Fx.accent(this));
                ov.setForeground(rg);
                sdWinRimmed = ov;
            }
            if (t != sdWinDragTo) {
                sdWinDragTo = t;
                // siblings glide into their would-be slots
                int pitchX = ov.getWidth() + Fx.dp(this, 8);
                int pitchY = ov.getHeight() + Fx.dp(this, 8);
                for (int i = 0; i < n; i++) {
                    View c = sdWinGrid.getChildAt(i);
                    if (c == sdWinDragCell) continue;
                    int oj = i < sdWinDragFrom ? i : i - 1;      // others-index
                    int wj = oj < t ? oj : oj + 1;               // after inserting at t
                    c.animate()
                            .translationX(((wj % 3) - (i % 3)) * (float) pitchX)
                            .translationY(((wj / 3) - (i / 3)) * (float) pitchY)
                            .setDuration(130).start();
                }
            }
        } catch (Throwable ignored) {}
    }

    /** the drop: never moved = nothing (the menu fired at UP already for
     *  holds); moved = Prefs.moveSpeeddialItem + the in-place rebuild. */
    protected void sdWinDragEnd(float rawX, float rawY) {
        final View cell = sdWinDragCell;
        final int from = sdWinDragFrom, to = sdWinDragTo;
        final boolean moved = sdWinDragMoved;
        final int folderIdx = sdWinFolderIdx;
        // disarm first — the rebuild re-adds every cell
        sdWinDragCell = null;
        sdWinDragFrom = sdWinDragTo = -1;
        sdWinH.removeCallbacksAndMessages(null);
        try {
            if (sdWinRimmed != null) { sdWinRimmed.setForeground(null); sdWinRimmed = null; }
            if (sdWinGrid != null) {
                for (int i = 0; i < sdWinGrid.getChildCount(); i++) {
                    View c = sdWinGrid.getChildAt(i);
                    c.animate().translationX(0).translationY(0).setDuration(160).start();
                }
            }
            if (cell != null) {
                cell.animate().translationX(0).translationY(0)
                        .scaleX(1f).scaleY(1f).setDuration(moved ? 160 : 80).start();
                cell.setElevation(0f);
            }
        } catch (Throwable ignored) {}
        if (moved && from >= 0 && to >= 0 && to != from) {
            // the drop lands on the NEXT frame — never mutate the view tree
            // from inside the touch dispatch (the v1.3.96 law)
            ui.post(() -> {
                try {
                    if (prefs.moveSpeeddialItem(folderIdx, from, to)) {
                        int txt = Fx.txt(BrowserActivity.this);
                        sdWinFillGrid(sdWinGrid, Fx.dp(BrowserActivity.this, 64), txt);
                    }
                } catch (Throwable ignored) {}
            });
        }
    }

    /** v1.4.8 — THE FOLDER'S OWN ADD DOOR (the README's oldest open
     *  candidate, born with the folder picker ask: "add this kind of
     *  direct addition to other relevant places"): a name+URL mini dialog
     *  that files the shortcut straight INTO folder [idx] — reachable from
     *  the folder window's "+" tile and the folder edit sheet's "Add
     *  shortcut" row, so a folder grows from its own side too, not only
     *  by dragging tiles onto it from the grid. Rebuilds the open window
     *  in place when the add lands in the folder it shows. */
    protected void addShortcutInsideFolderDialog(final int folderIdx) {
        org.json.JSONObject g = folderIdx >= 0 ? prefs.speeddialAt(folderIdx) : null;
        if (g == null || !g.optBoolean("group", false)) {
            Fx.toast(this, "Folder is gone");
            return;
        }
        int txt = Fx.txt(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 20);
        wrap.setPadding(p, p / 2, p, 0);
        final EditText etTitle = new EditText(this);
        etTitle.setHint(R.string.name);
        etTitle.setTextColor(txt);
        wrap.addView(etTitle, new LinearLayout.LayoutParams(-1, -2));
        final EditText etUrl = new EditText(this);
        etUrl.setHint(R.string.shortcut_url_hint);
        etUrl.setTextColor(txt);
        wrap.addView(etUrl, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.add_shortcut_to_folder,
                        g.optString("name", "")))
                .setView(wrap)
                .setPositiveButton(R.string.add, (d, w) -> {
                    String t2 = etTitle.getText().toString().trim();
                    String u2 = etUrl.getText().toString().trim();
                    if (t2.isEmpty()) t2 = Fx.hostOf(u2);
                    if (!u2.startsWith("http")) {
                        Fx.toast(this, "Enter a full https:// address");
                        return;
                    }
                    if (prefs.addSpeeddialItemTo(folderIdx, t2, u2)) {
                        Fx.toast(this, getString(R.string.added_to_folder,
                                g.optString("name", "")));
                        // the open window rebuilds in place when it shows
                        // this folder; the home grid refreshes quietly
                        try {
                            if (sdWinGrid != null && sdWinFolderIdx == folderIdx) {
                                sdWinFillGrid(sdWinGrid, Fx.dp(this, 64), txt);
                            } else {
                                refreshHomeQuiet();
                            }
                        } catch (Throwable ignored) {}
                    } else {
                        Fx.toast(this, "Could not add to folder");
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** v1.4.0 — the rename dialog shared by the window's Rename row and the
     *  edit menu (was inline in the old group sheet). */
    protected void sdRenameGroupDialog(final int idx) {
        org.json.JSONObject o = prefs.speeddialAt(idx);
        if (o == null || !o.optBoolean("group", false)) return;
        final EditText et = new EditText(this);
        et.setText(o.optString("name", "Group"));
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_group)
                .setView(et)
                .setPositiveButton(R.string.save, (d2, w2) -> {
                    prefs.renameSpeeddial(idx, et.getText().toString().trim());
                    refreshHomeQuiet();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** v1.4.0 — THE FOLDER EDIT MENU (the report: "edit the group by holding
     *  on it"): the hold door for a home folder (sdHoldMenu/sdDragEnd route
     *  here through sdTileMenu). Open (the window), Rename, Remove
     *  shortcut…, Ungroup, Delete folder (with the v1.3.91 undo snackbar). */
    protected void sdGroupEditMenu(final int idx) {
        org.json.JSONObject o = prefs.speeddialAt(idx);
        if (o == null || !o.optBoolean("group", false)) return;
        final String name = o.optString("name", "Group");
        List<MenuRow> rows = new ArrayList<>();
        rows.add(row(R.drawable.ic_folder, getString(R.string.open), v -> {
            dismissAllOverlays();
            showSdFolderWindow(idx);
        }));
        // v1.4.8 — the edit sheet's ADD door (the folder picker ask's other
        // half): a shortcut lands straight into THIS folder
        rows.add(row(R.drawable.ic_plus, getString(R.string.add_shortcut), v -> {
            dismissAllOverlays();
            addShortcutInsideFolderDialog(idx);
        }));
        rows.add(row(R.drawable.ic_edit, getString(R.string.rename_group), v -> {
            dismissAllOverlays();
            sdRenameGroupDialog(idx);
        }));
        rows.add(row(R.drawable.ic_delete, getString(R.string.remove_shortcut), v -> {
            dismissAllOverlays();
            org.json.JSONArray its = prefs.speeddialAt(idx) == null ? null
                    : prefs.speeddialAt(idx).optJSONArray("items");
            if (its == null || its.length() == 0) return;
            String[] names = new String[its.length()];
            for (int i = 0; i < its.length(); i++)
                names[i] = its.optJSONObject(i) == null ? "?" : its.optJSONObject(i).optString("title");
            new AlertDialog.Builder(this)
                    .setTitle(R.string.remove_shortcut)
                    .setItems(names, (d2, w2) -> {
                        prefs.removeSpeeddialItem(idx, w2);
                        refreshHomeQuiet();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }));
        rows.add(row(R.drawable.ic_close, getString(R.string.ungroup), v -> {
            dismissAllOverlays();
            prefs.dissolveSpeeddialGroup(idx);
            refreshHomeQuiet();
            Fx.toast(BrowserActivity.this, getString(R.string.ungrouped));
        }));
        rows.add(row(R.drawable.ic_delete, getString(R.string.delete_folder), v -> {
            // v1.3.91 pattern: delete is never final — the entry is
            // captured whole and Undo puts it back at the SAME index
            // (v1.4.6: the window is Ui.UNDO_MS everywhere, 3.5 s)
            dismissAllOverlays();
            final org.json.JSONObject gone = prefs.speeddialAt(idx);
            prefs.removeSpeeddial(idx);
            refreshHomeQuiet();
            if (gone != null) {
                com.bolt.browser.util.Ui.snackbar(this,
                        getString(R.string.folder_deleted),
                        getString(R.string.undo), () -> {
                            try {
                                prefs.insertSpeeddial(Math.min(idx,
                                        prefs.speeddial().length()), gone);
                                refreshHomeQuiet();
                            } catch (Throwable ignored) {}
                        }, com.bolt.browser.util.Ui.UNDO_MS, null);
            }
        }));
        showSheetMenu(name, rows);
    }

    protected void addSpeeddialDialog() {
        int txt = Fx.txt(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 20);
        wrap.setPadding(p, p / 2, p, 0);
        final EditText etTitle = new EditText(this);
        etTitle.setHint(R.string.name);
        etTitle.setTextColor(txt);
        wrap.addView(etTitle, new LinearLayout.LayoutParams(-1, -2));
        final EditText etUrl = new EditText(this);
        etUrl.setHint("https://…");
        etUrl.setTextColor(txt);
        wrap.addView(etUrl, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_shortcut2)
                .setView(wrap)
                .setPositiveButton(R.string.add, (d, w) -> {
                    String t = etTitle.getText().toString().trim();
                    String u = etUrl.getText().toString().trim();
                    if (t.isEmpty()) t = Fx.hostOf(u);
                    if (u.startsWith("http")) {
                        prefs.addSpeeddial(t, u);
                        refreshHome();
                    } else {
                        Fx.toast(this, "Enter a full https:// address");
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------------- press feedback ----------------

    /** press animation: scale down + optional icon tint to accent while pressed.
     *  v1.3.36 FIELD FIX ("options stay minified after holding and not
     *  clicking"): the listener returns false on ACTION_DOWN, so any target
     *  that is NOT clickable loses the touch stream to its parent right
     *  after DOWN — ACTION_UP/CANCEL never arrive and the row stays at 0.9
     *  scale forever. Forcing clickable keeps the view in the touch chain
     *  (harmless for rows with no click listener) so the reset always runs.
     *  Also: a light haptic tick on press (pref-gated inside Ui.tap). */
    protected void pressFx(final View target, final ImageView icon, final int restoreTint) {
        if (target == null) return;
        try {
            if (!target.isClickable() && !target.isLongClickable()) target.setClickable(true);
        } catch (Throwable ignored) {}
        target.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    try {
                        com.bolt.browser.util.Ui.tap(target);
                        if (icon != null) icon.setColorFilter(Fx.accent(BrowserActivity.this));
                        target.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start();
                    } catch (Throwable ignored) {}
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    try {
                        if (icon != null) icon.setColorFilter(restoreTint);
                        target.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                    } catch (Throwable ignored) {}
                    break;
            }
            return false; // never swallow events; clicks still fire
        });
    }

    /** v1.3.46 — the glass-bar variant: the release restores the tint LIVE
     *  (glyphTintOf) instead of a color captured at onCreate, because the
     *  adaptive glass verdict can flip (white <-> dark) under the user's
     *  finger while the button is held. */
    /** v1.3.79 — the tab-cell press: the COUNT SQUARE animates (0.8 scale +
     *  accent-tinted flash), the cell itself does not move — the cell is a
     *  full-height hit target, scaling it was invisible ("touching the tab
     *  in the bottom toolbar does not have any animation to let me know it
     *  is pressed"). Restore re-tints with the live chrome color. */
    protected void pressFxTabCell(final View cell, final TextView square) {
        if (cell == null) return;
        try {
            if (!cell.isClickable() && !cell.isLongClickable()) cell.setClickable(true);
        } catch (Throwable ignored) {}
        cell.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    try {
                        com.bolt.browser.util.Ui.tap(cell);
                        if (square != null) {
                            square.animate().scaleX(0.78f).scaleY(0.78f).setDuration(90).start();
                            square.setTextColor(Fx.accent(BrowserActivity.this));
                        }
                    } catch (Throwable ignored) {}
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    try {
                        if (square != null) {
                            square.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
                            // v1.3.91 — THE ONE COLOR LAW: restore through
                            // the same live adaptive verdict applyGlassGlyphs
                            // paints (the old hardcoded prefs-dark heuristic
                            // disagreed with the verdict on light-page/dark
                            // mode mixes and the number turned the color of
                            // its own box = "the number in the tabs
                            // disappear when I tap on it")
                            square.setTextColor(tabCountGlyphColor());
                        }
                    } catch (Throwable ignored) {}
                    break;
            }
            return false; // never swallow events; clicks still fire
        });
    }

    protected void pressFxBar(final View target, final ImageView icon) {
        if (target == null) return;
        try {
            if (!target.isClickable() && !target.isLongClickable()) target.setClickable(true);
        } catch (Throwable ignored) {}
        target.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    try {
                        com.bolt.browser.util.Ui.tap(target);
                        if (icon != null) icon.setColorFilter(Fx.accent(BrowserActivity.this));
                        target.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start();
                    } catch (Throwable ignored) {}
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    try {
                        if (icon != null) icon.setColorFilter(glyphTintOf(icon));
                        target.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                    } catch (Throwable ignored) {}
                    break;
            }
            return false; // never swallow events; clicks still fire
        });
    }

    protected void pressFx(View target) {
        pressFx(target, null, 0);
    }

    protected android.graphics.drawable.Drawable cardBg() {
        // v1.3.33: skin-aware (Aura glass, Origins soft outline, classic plain)
        return Fx.cardBg(this, 16);
    }

    // ------------------------------------------------------------ quick settings panel

    protected void toggleQuickPanel() {
        if (quickPanel != null && quickPanel.getParent() != null) {
            hideQuickPanel();
        } else {
            showQuickPanel();
        }
    }

    protected void showQuickPanel() {
        hideQuickPanel();
        ensureBarsShown();   // v1.3.33: the panel anchors under the top bar
        final Tab t = activeTab();
        final String base = curDomain();
        final boolean onWeb = !t.isHome && !base.isEmpty();
        int dim = Fx.tint(this);
        int txt = Fx.txt(this);

        // scrim: tap anywhere outside closes the panel
        quickScrim = new View(this);
        quickScrim.setBackgroundColor(0x22000000);
        quickScrim.setOnClickListener(v -> hideQuickPanel());
        flRoot.addView(quickScrim, new FrameLayout.LayoutParams(-1, -1));
        flRoot.bringChildToFront(quickScrim);

        // floating card
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(getDrawable(R.drawable.bg_card));
        panel.setElevation(Fx.dp(this, 8));
        int pad = Fx.dp(this, 10);
        panel.setPadding(pad, pad / 2, pad, pad);

        TextView head = new TextView(this);
        head.setText(onWeb ? base : getString(R.string.quick_settings));
        head.setTextSize(11);
        head.setTextColor(dim);
        head.setPadding(Fx.dp(this, 4), 0, 0, Fx.dp(this, 3));
        panel.addView(head, new LinearLayout.LayoutParams(-2, -2));

        // the single, horizontally scrolling chip row
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(row, new ViewGroup.LayoutParams(-2, -2));

        // 1. ad block (global)
        row.addView(quickChip(R.drawable.ic_filter, getString(R.string.ad_blocking),
                prefs.adblockEnabled(), v -> {
            boolean now = !prefs.adblockEnabled();
            prefs.setAdblock(now);
            Fx.toast(this, getString(R.string.ad_blocking) + ": " + (now ? "ON" : "OFF"));
            dismissAllOverlays();
            if (t.web != null && !t.isHome) t.web.reload();
        }));
        // 2. desktop site (per-site, reload)
        row.addView(quickChip(R.drawable.ic_desktop, getString(R.string.desktop_site),
                onWeb && prefs.siteBool(base, "desktop", false), v -> {
            if (!onWeb) { Fx.toast(this, "Open a website first"); return; }
            boolean now = !prefs.siteBool(base, "desktop", false);
            prefs.setSite(base, "desktop", now);
            Fx.toast(this, getString(R.string.desktop_site) + ": " + (now ? "ON" : "OFF"));
            dismissAllOverlays();
            if (t.web != null) {
                t.applyUa(this, t.web);
                t.web.reload();
            }
        }));
        // 3. user agent (opens chooser)
        row.addView(quickChip(R.drawable.ic_mobile, getString(R.string.user_agent),
                prefs.siteInt(base, "ua", -1) >= 0, v -> {
            if (!onWeb) { Fx.toast(this, "Open a website first"); return; }
            hideQuickPanel();
            chooseUa(base);
        }));
        // 4. javascript (per-site, reload)
        row.addView(quickChip(R.drawable.ic_code, getString(R.string.block_javascript),
                onWeb && prefs.siteBool(base, "noJs", false), v -> {
            if (!onWeb) { Fx.toast(this, "Open a website first"); return; }
            boolean now = !prefs.siteBool(base, "noJs", false);
            prefs.setSite(base, "noJs", now);
            Fx.toast(this, "JavaScript: " + (now ? "blocked" : "allowed"));
            dismissAllOverlays();
            if (t.web != null) t.web.reload();
        }));
        // 5. cookies (per-site, reload)
        row.addView(quickChip(R.drawable.ic_lock, getString(R.string.block_cookies),
                onWeb && prefs.siteBool(base, "noCookies", false), v -> {
            if (!onWeb) { Fx.toast(this, "Open a website first"); return; }
            boolean now = !prefs.siteBool(base, "noCookies", false);
            prefs.setSite(base, "noCookies", now);
            Fx.toast(this, "Cookies: " + (now ? "blocked" : "allowed"));
            dismissAllOverlays();
            if (t.web != null) t.web.reload();
        }));
        // 6. clear site data (per-site, reload)
        row.addView(quickChip(R.drawable.ic_delete, getString(R.string.clear_site_data),
                false, v -> {
            if (!onWeb) { Fx.toast(this, "Open a website first"); return; }
            dismissAllOverlays();
            confirmWipeSite(base);
        }));
        // 7. theme (cycles light/dark/auto -> activity recreates). v1.3.33:
        // with a non-Classic skin the cycle is meaningless — the chip jumps
        // straight to the Appearance picker instead.
        int themeNow = prefs.i("theme", 1);
        row.addView(quickChip(R.drawable.ic_moon, getString(R.string.theme),
                themeNow == 1 && prefs.skin() == 0, v -> {
            if (prefs.skin() != 0) {
                hideQuickPanel();
                startActivity(new Intent(this, AppearanceActivity.class));
                return;
            }
            int next = (themeNow + 1) % 3;
            prefs.set("theme", next);
            Fx.toast(this, next == 0 ? "Light theme" : (next == 1 ? "Dark theme" : "Auto theme"));
            recreate();
        }));
        // 8. find in page
        row.addView(quickChip(R.drawable.ic_search, getString(R.string.find_in_page),
                false, v -> {
            dismissAllOverlays();
            openFind();
        }));
        // 9. full site settings sheet
        row.addView(quickChip(R.drawable.ic_tune, getString(R.string.site_settings),
                false, v -> {
            hideQuickPanel();
            showSiteSettings();
        }));

        panel.addView(hsv, new LinearLayout.LayoutParams(-2, -2));
        quickPanel = panel;

        View tb = findViewById(R.id.topbar);
        int top = tb != null && tb.getHeight() > 0
                ? tb.getHeight() + Fx.dp(this, 4) : Fx.dp(this, 66);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2);
        plp.gravity = Gravity.TOP | Gravity.START;
        plp.leftMargin = Fx.dp(this, 8);
        plp.topMargin = top;
        flRoot.addView(panel, plp);
        flRoot.bringChildToFront(panel);
    }

    protected void hideQuickPanel() {
        try {
            if (quickPanel != null && quickPanel.getParent() == flRoot) flRoot.removeView(quickPanel);
        } catch (Throwable ignored) {}
        try {
            if (quickScrim != null && quickScrim.getParent() == flRoot) flRoot.removeView(quickScrim);
        } catch (Throwable ignored) {}
        quickPanel = null;
        quickScrim = null;
    }

    /** small pressable toggle chip used by the quick settings panel */
    protected View quickChip(int icon, String label, boolean on, View.OnClickListener click) {
        int dim = Fx.tint(this);
        int txt = Fx.txt(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(Fx.dp(this, 10), Fx.dp(this, 5), Fx.dp(this, 10), Fx.dp(this, 5));
        c.setBackground(getDrawable(on ? R.drawable.bg_quickchip_on : R.drawable.bg_quickchip));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Fx.dp(this, 30));
        lp.rightMargin = Fx.dp(this, 6);
        c.setLayoutParams(lp);
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setColorFilter(on ? Fx.accentText(this) : dim);
        c.addView(ic, new LinearLayout.LayoutParams(Fx.dp(this, 14), Fx.dp(this, 14)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(on ? Fx.accentText(this) : txt);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = Fx.dp(this, 5);
        c.addView(tv, tlp);
        c.setOnClickListener(click);
        pressFx(c, ic, on ? Fx.accentText(this) : dim);
        return c;
    }

    /** programmatically leave fullscreen video (page navigation, tab switch, back) */
    protected void exitFullscreenVideo() {
        if (customView == null) return;
        try { if (customCallback != null) customCallback.onCustomViewHidden(); } catch (Throwable ignored) {}
        hideCustomViewInternal(false);
    }

    protected void hideCustomViewInternal(boolean invokeCallback) {
        if (customView == null) return;
        fsAwaitAspect = false;   // v1.3.91: the pending aspect decision dies with the view
        try { flRoot.removeView(customView); } catch (Throwable ignored) {}
        customView = null;
        if (invokeCallback) {
            try { if (customCallback != null) customCallback.onCustomViewHidden(); } catch (Throwable ignored) {}
        }
        customCallback = null;
        // v1.3.63 — THE IN-PIP DEATH PATH. The custom view can die while the
        // activity is shrunk into the floating window (the v1.3.62 field log:
        // the mobile site's player exits element fullscreen ~260 ms into PiP;
        // any page-side exitFullscreen at the tiny viewport does the same).
        // The normal restore below is EXACTLY the "the pip shows only the
        // browser elements like site settings icon, refresh icon, basically
        // the top bar and the bottom toolbars" bug — browser chrome painted
        // into a 240-dp window. Instead: keep every bar hidden, PIN the
        // largest playing video to the viewport (the same CSS rescue the
        // wedged-enter path uses, forced past the customView guard) so the
        // floating window keeps showing the video, and log the evidence.
        // The normal restore runs when the window expands back
        // (onPictureInPictureModeChanged(false) retires the rescue and shows
        // the bars) or at onDestroy.
        if (inPip) {
            try {
                com.bolt.browser.util.Diag.log(this, "fs hide inpip (video pinned, chrome stays hidden)");
            } catch (Throwable ignored) {}
            try {
                // v1.3.67 — arm the POST-EXIT mute: the custom view died
                // inside PiP (the exit gate's entry window was missed or the
                // exit came later), so MediaHook now also mutes resize /
                // orientationchange for the rest of the session (the player's
                // post-exit relayout reactions — the pause loop's other
                // driver). Desktop never runs this branch (its custom view
                // survives the shrink), so its deliveries stay untouched.
                // unpinJs clears the flag with the disarm.
                WebView mw = activeTab() == null ? null : activeTab().web;
                if (mw != null) mw.evaluateJavascript(
                        "try{window.__boltpipx=1}catch(e){}", null);
            } catch (Throwable ignored) {}
            try {
                // v1.3.64: pipMode selector — playing OR paused-with-frame
                // (the site often pauses the video in the same breath it
                // exits fullscreen; a playing-only pin would find nothing
                // exactly here and leave the box to the page UI)
                fsApplyCssRescue(activeTab() == null ? null : activeTab().web, true, true);
            } catch (Throwable ignored) {}
            try { fsRestoreScroll(); } catch (Throwable ignored) {}
            if (pseudoFullscreen) pseudoFullscreen = false;
            try { hideBarsNow(false); } catch (Throwable ignored) {}
            try { updatePipParams(); } catch (Throwable ignored) {}
            return;
        }
        // v1.3.62 — hand the page back its scroll (the renderer's
        // DidEnterFullscreen zeroed the visual-viewport offset while the
        // video owned the screen) and retire the wedged-enter CSS rescue
        // (if any was live) — both BEFORE the page becomes visible again
        // so the reader lands exactly where they fullscreened
        try { fsRemoveCssRescue(); } catch (Throwable ignored) {}
        try { fsRestoreScroll(); } catch (Throwable ignored) {}
        // v1.3.61 — a pseudo-fullscreen video can no longer be live either
        if (pseudoFullscreen) {
            pseudoFullscreen = false;
            try { com.bolt.browser.util.Diag.log(this, "fs pseudo off via hide"); } catch (Throwable ignored) {}
        }
        View tg = findViewById(R.id.top_group);
        View tb = findViewById(R.id.topbar);
        View bb = findViewById(R.id.bottombar);
        if (tg != null) tg.setVisibility(View.VISIBLE);
        if (tb != null) tb.setVisibility(View.VISIBLE);
        if (bb != null) bb.setVisibility(View.VISIBLE);
        // v1.3.60: the media FAB returns with the chrome — per its own rule
        // (only while media plays AND the booster is on); it was not hidden
        // on enter (it sits BELOW the customView in z-order), but its state
        // must be re-asserted here because mediaPlaying may have flipped
        // while the video owned the screen.
        try {
            fabMedia.setVisibility(
                    mediaPlaying && (prefs.mediaBooster() || prefs.mediaSkip()) ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) {}
        // v1.3.40: the download shelf returns with the rest of the chrome
        // (updateDlShelf decides GONE when there is nothing to show)
        try { updateDlShelf(); } catch (Throwable ignored) {}
        applySkinChrome();     // v1.3.33: re-paint bar + system-bar colors after immersive
        showBarsNow(false);
        restoreBars();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        // v1.3.60: evidence line — pairs with fs show
        try {
            com.bolt.browser.util.Diag.log(this, "fs hide");
        } catch (Throwable ignored) {}
        // v1.3.41: no fullscreen video → auto-PiP is off again
        try { updatePipParams(); } catch (Throwable ignored) {}
    }

    /** v1.3.60 — THE PHANTOM-FULLSCREEN HEALER.
     *
     * The v1.3.59 field report: "sometimes full screen is just zoomed in
     * view instead of true full screen" and "picture in picture is fully
     * broken". Root cause (one mechanism, both symptoms): when this
     * ACTIVITY dies while a video is element-fullscreen — closing the PiP
     * window with its X, an OEM kill, a crash, a legit recreate() — nothing
     * ever told the RENDERER its fullscreen view was gone (onDestroy never
     * invoked CustomViewCallback.onCustomViewHidden). The WebView survives
     * (zombie/adoption keeps it alive in the app-scoped TabManager), so the
     * page comes back with document.fullscreenElement STILL set: the player
     * keeps its fullscreen styling inside the (desktop-UA, overview-scaled)
     * WebView = the "zoomed-in view"; every floating page overlay stays
     * visible ("scripts overlay not hiding"); and because the APP side has
     * customView == null, the first fullscreen tap is consumed just
     * EXITING the phantom while PiP eligibility (customView != null) never
     * arms — one poisoned session makes both features look dead.
     *
     * The heal is dirt cheap and strictly safe: only when the app has NO
     * customView (never during real fullscreen or PiP), ask the page for
     * its fullscreen state; if a phantom exists, exit it and log the
     * evidence. Wired at showTab (covers zombie adoption + every switch)
     * and onPageFinished (covers any load that lands while desynced).
     * exitFullscreen() needs no user gesture, and an exiting page keeps
     * playing (only the fullscreen styling collapses). */
    protected void healRendererFullscreen(WebView w, String where) {
        if (w == null || customView != null) return;
        try {
            w.evaluateJavascript(
                    "(function(){try{return document.fullscreenElement?1:0}catch(e){return -1}})()",
                    v -> {
                        try {
                            // re-check at ANSWER time: a real fullscreen may
                            // have started between probe and callback — never
                            // exit that one
                            if ("1".equals(v) && customView == null) {
                                try {
                                    w.evaluateJavascript(
                                            "try{document.exitFullscreen()}catch(e){}", null);
                                } catch (Throwable ignored) {}
                                try {
                                    com.bolt.browser.util.Diag.log(this, "fs heal where=" + where
                                            + " host=" + com.bolt.browser.util.Fx.hostOf(
                                                    tabOf(w) == null ? "" : tabOf(w).url));
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    });
        } catch (Throwable ignored) {}
    }

    /** v1.3.62 — the fullscreen video probe (aspect + scroll), replacing the
     *  v1.3.61 viewport decoupling. THE DECOUPLING WAS A NO-OP: useWideViewPort
     *  maps to Chromium's load-time wide_viewport QUIRK preference (aw_settings.cc:
     *  wide_viewport_quirk=true + use_wide_viewport), consumed when the
     *  document's viewport policy is computed (load/navigation) — flipping it on
     *  a LIVE document re-runs nothing, so the 980-px desktop layout survived
     *  untouched (the v1.3.61 report: "still here fully"). Worse, its companion
     *  setLoadWithOverviewMode(false) fires resetScrollAndScaleState in the
     *  MIDDLE of the enter handshake (a page-scale mutation while the renderer
     *  is applying the fullscreen constraints) — pure risk, zero benefit; both
     *  are gone. What stays is what worked: the VIDEO ASPECT probe (PiP's
     *  setAspectRatio feeds from it) and the SCROLL capture (the renderer's
     *  DidEnterFullscreen zeroes the visual-viewport offset; exit hands the
     *  reader back where they fullscreened). The probe prefers the fullscreen
     *  element's own video and falls back to the LARGEST video on the page
     *  (never the first — an ad/preview/Shorts must not hand PiP a wrong
     *  aspect). */
    protected void probeFullscreenVideo() {
        final WebView w = activeTab() == null ? null : activeTab().web;
        if (w == null) return;
        fsVideoAspect = null;
        try {
            w.evaluateJavascript(
                    "(function(){try{"
                    + "var v=document.fullscreenElement?document.fullscreenElement.querySelector('video'):null;"
                    + "if(!v){var vs=document.querySelectorAll('video');var ba=-1;"
                    + "for(var i=0;i<vs.length;i++){var x=vs[i];"
                    + "if(!x.videoWidth||!x.videoHeight)continue;"
                    + "var a=x.videoWidth*x.videoHeight;if(a>ba){ba=a;v=x;}}}"
                    + "var a=(v&&v.videoWidth>0&&v.videoHeight>0)?(v.videoWidth/v.videoHeight):0;"
                    + "return a.toFixed(4)+':'+window.scrollX+':'+window.scrollY;"
                    + "}catch(e){return 'x'}})()",
                    ans -> {
                        try {
                            // ANSWER-time guard (the healer's pattern): the
                            // fullscreen may already be gone — apply nothing
                            if (customView == null || ans == null) return;
                            String raw = ans.replace("\"", "");
                            if (raw.length() < 3 || raw.startsWith("x")) return;
                            String[] p = raw.split(":");
                            if (p.length < 3) return;
                            try {
                                float a = Float.parseFloat(p[0]);
                                if (a > 0f) fsVideoAspect = clampVideoAspect(a);
                            } catch (Throwable ignored) {}
                            fsScrollWeb = w;
                            try { fsSavedScrollX = (int) Float.parseFloat(p[1]); } catch (Throwable ignored) {}
                            try { fsSavedScrollY = (int) Float.parseFloat(p[2]); } catch (Throwable ignored) {}
                            // v1.3.91 — the orientation decision the enter
                            // was waiting for (see fsEnterAfterAspect; the
                            // 650 ms fallback in onShowCustomView covers a
                            // stalled answer)
                            if (fsAwaitAspect) {
                                float a2 = 0f;
                                try { a2 = Float.parseFloat(p[0]); } catch (Throwable ignored) {}
                                fsAwaitAspect = false;
                                try { fsEnterAfterAspect(a2); } catch (Throwable ignored) {}
                            }
                            // refresh the armed PiP params with the real aspect
                            try { updatePipParams(); } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                    });
        } catch (Throwable ignored) {}
    }

    /** v1.3.91 — THE ASPECT-DRIVEN ENTER (the Shorts-flicker contract).
     *  Runs once per onShowCustomView, from the aspect probe's answer (or
     *  its 650 ms fallback). Decides the orientation from the VIDEO's own
     *  aspect — a portrait video (Shorts/Reels/TikTok) stays PORTRAIT
     *  instead of being force-rotated into a landscape it never needed —
     *  and gates the settle machinery on whether a rotation is actually
     *  coming: a rotation IS a real size change, the very thing
     *  Chromium's FullscreenController needs to resolve the enter, so the
     *  1-px kicks (each one a viewport mutation the page's resize
     *  handlers answer — YouTube toggles its player chrome on them = the
     *  flicker) are only spent when NOTHING else will settle the request:
     *  no-rotation enters kick ONCE at ~350 ms, probe fsel at ~800 ms,
     *  and only a still-wedged page gets the second kick + the rescue
     *  probe. Rotation enters just probe late (the rotation settles). */
    protected void fsEnterAfterAspect(float aspect) {
        if (customView == null) return;
        final View cv = customView;
        boolean landscapeVideo = aspect <= 0f || aspect >= 1f;   // unknown -> legacy default
        int cur = getResources().getConfiguration().orientation;
        boolean willRotate = landscapeVideo
                ? cur != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                : cur != android.content.res.Configuration.ORIENTATION_PORTRAIT;
        try {
            setRequestedOrientation(landscapeVideo
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        } catch (Throwable ignored) {}
        try {
            com.bolt.browser.util.Diag.log(this, "fs enter a=" + aspect
                    + (landscapeVideo ? " land" : " port") + (willRotate ? " rot" : " norot"));
        } catch (Throwable ignored) {}
        if (willRotate) {
            // the rotation is the settle — only verify it landed (late
            // probe; rescue if the rotation did NOT settle the request)
            ui.postDelayed(() -> {
                try { if (customView == cv) fsSettleProbe(); } catch (Throwable ignored) {}
            }, 1100);
        } else {
            // no size change is coming: ONE kick, then the probe at 800 ms
            // (its callback runs the CSS rescue if the page is still
            // wedged — the probe IS the second safety net; spending a
            // second 1-px kick on every enter was the flicker tax)
            ui.postDelayed(() -> {
                try { if (customView == cv) fsSettleKick(cv); } catch (Throwable ignored) {}
            }, 350);
            ui.postDelayed(() -> {
                try { if (customView == cv) fsSettleProbe(); } catch (Throwable ignored) {}
            }, 800);
        }
    }

    /** v1.3.62 — THE SETTLE KICK. Chromium's FullscreenController only
     *  resolves a GRANTED requestFullscreen when the next
     *  VisualPropertiesUpdated (a real size/inset change) reaches the renderer
     *  — DidEnterFullscreen applies the fullscreen constraints (page scale 1,
     *  layout = view size) AND moves the element into the DOM top layer. When
     *  the enter sequence produces no such change (the custom view measures
     *  the same size the WebView already had — immersive browsing, a landscape
     *  desktop page, or one of the glue's admitted early-outs), the request
     *  wedges unresolved: no top layer (page overlays — userscript panels —
     *  ride the video), no scale reset (the desktop page keeps its overview/
     *  pinch scale = the "zoomed-in view instead of true full screen"), and
     *  both a re-request and an exit are IGNORED while the controller sits in
     *  kEnteringFullscreen. This kick forces a REAL 1-px size change through
     *  the custom view (a FrameLayout whose child is Chromium's FullScreenView
     *  — padding shrinks the child's measured size), which guarantees an
     *  AwContents size change -> SynchronizeVisualProperties ->
     *  VisualPropertiesUpdated -> the settle. One pixel of black for at most
     *  a couple of frames on a black-background surface: invisible. */
    protected void fsSettleKick(final View v) {
        if (v == null || customView == null || customView != v) return;
        try {
            v.setPadding(0, 0, 0, 1);
            v.requestLayout();
        } catch (Throwable ignored) {}
        ui.postDelayed(() -> {
            try {
                if (customView == v) {
                    v.setPadding(0, 0, 0, 0);
                    v.requestLayout();
                }
            } catch (Throwable ignored) {}
        }, 120);
    }

    /** v1.3.62 — THE SETTLE PROBE + CSS RESCUE. After both kicks, ask the
     *  page whether document.fullscreenElement ever engaged while the app
     *  holds the custom view. fsel=1: the enter settled (the normal case —
     *  mobile site, and desktop whenever the enter carried its own size
     *  change) — also retire the rescue class in case a LATE settle followed
     *  an earlier rescue. fsel=0: the wedged state — the custom view owns the
     *  screen but the page never got its top layer, so the reader is looking
     *  at the RAW desktop page at overview/pinch scale with every page
     *  overlay visible. The rescue pins the LARGEST playing video to the
     *  viewport (position:fixed, inset 0, z-index max, object-fit contain):
     *  the wedge then looks and behaves like true fullscreen (video fills the
     *  screen, overlays covered) at ANY window size — including the PiP
     *  shrink, which reflows 100vw/100vh to the floating window. Scoped to a
     *  class Bolt adds itself; removed at exit/tab-switch/page-start and by
     *  any later settle. */
    protected void fsSettleProbe() {
        if (customView == null) return;
        final WebView w = activeTab() == null ? null : activeTab().web;
        if (w == null) return;
        try {
            w.evaluateJavascript(
                    "(function(){try{return document.fullscreenElement?1:0}catch(e){return -1}})()",
                    v -> {
                        try {
                            if (customView == null) return;
                            boolean settled = "1".equals(v);
                            try {
                                com.bolt.browser.util.Diag.log(BrowserActivity.this,
                                        "fs fsel=" + (settled ? "1" : "0"));
                            } catch (Throwable ignored) {}
                            if (settled) {
                                // v1.3.64: the GUARDED retire — while in PiP
                                // the takeover pin must survive this probe
                                // (the floating window lives on the pin),
                                // outside PiP a late settle still unpins.
                                fsRemoveCssRescue();
                            } else {
                                // v1.3.64: in PiP use the pip selector too
                                // (playing OR paused-with-frame + the
                                // re-pin cycle) — the strict playing-only
                                // pick could stack a second pinned video
                                // under/over the takeover's choice.
                                fsApplyCssRescue(w, false, inPip);
                            }
                        } catch (Throwable ignored) {}
                    });
        } catch (Throwable ignored) {}
    }

    /** v1.3.62 — apply the wedged-enter rescue CSS to the largest playing
     *  video of the given WebView (see fsSettleProbe). Idempotent.
     *  v1.3.63 — force=true skips the customView guard: the IN-PIP death
     *  path (hideCustomViewInternal) applies the pin AFTER the custom view
     *  is already gone, so the video keeps owning the floating window. It
     *  also forces the page-side document-fullscreen overlay sweep
     *  (window.__boltFsSweep — MediaHook) so userscript panels cannot ride
     *  the pinned video, and the pin's z-index is now the true max
     *  (2147483647; the user's own panel sits at exactly that value and won
     *  the tree-order tie against v1.3.62's ...645).
     *  v1.3.64 — pipMode=true is THE PIP TAKEOVER selector: prefer the
     *  largest PLAYING video, fall back to the largest video WITH A FRAME
     *  (paused videos keep their picture — the v1.3.63 report's mobile box
     *  sat on page UI precisely because the site had paused the video and
     *  the playing-only pin found nothing), and re-cycle the class off every
     *  other video first so exactly ONE video is ever pinned (player DOM
     *  re-creation + ad switches must not stack pins). Evidence: one
     *  'fs pip pin=1|0' line per PiP session. */
    protected void fsApplyCssRescue(final WebView w, boolean force) {
        fsApplyCssRescue(w, force, false);
    }

    protected void fsApplyCssRescue(final WebView w, boolean force, final boolean pipMode) {
        if (w == null || (customView == null && !force)) return;
        fsSettleWeb = w;
        if (!pipMode) {
            try {
                com.bolt.browser.util.Diag.log(this, "fs rescue on host="
                        + com.bolt.browser.util.Fx.hostOf(
                                tabOf(w) == null ? "" : tabOf(w).url));
            } catch (Throwable ignored) {}
        }
        try {
            // v1.3.63 — force the page-side overlay sweep first (hides
            // userscript panels and any other ≥1e8-z floating overlay)
            w.evaluateJavascript("try{window.__boltFsSweep&&window.__boltFsSweep(1)}catch(e){}",
                    null);
        } catch (Throwable ignored) {}
        try {
            // v1.3.65 — THE IMPORTED ENGINE (util/PipVideo.kt, the
            // "Maximize Video" model): replaces the v1.3.62–64 bare-video
            // pin. A fixed box resolves against ANY ancestor carrying a
            // transform/filter/perspective/contain — on YouTube the
            // "pinned" video rendered inside the ANCESTOR's box ("top
            // half of the video with the rest being the youtube search
            // bar"). The takeover is three layers: html/body overflow
            // hidden; EVERY ancestor neutralized (transform/contain/
            // filter none, z-index auto, overflow visible, fixed →
            // absolute) so position:fixed means THE VIEWPORT; the PLAYER
            // (the ancestor whose box tracks the video's) pinned to
            // 100vw×100vh with object-fit:contain on its videos + 1px
            // edge strips. Flicker-free beat (a live pin still holding
            // the best video is not touched). Answer "<code>:<aspect>":
            // 1 player / 2 bare video / 3 already-pinned / 0 nothing; the
            // aspect refreshes the window so an ad switch cannot shape
            // it wrong either.
            w.evaluateJavascript(
                    com.bolt.browser.util.PipVideo.pinJs(pipMode),
                    pipMode ? v -> {
                        try {
                            String raw = v == null ? "0:0" : v.replace("\"", "");
                            int c = raw.indexOf(':');
                            if (c < 0) c = raw.length();
                            String code = raw.substring(0, c);
                            String rest = c < raw.length() ? raw.substring(c + 1) : "";
                            boolean ok = "1".equals(code) || "2".equals(code)
                                    || "3".equals(code);
                            float a = -1f;
                            try { a = Float.parseFloat(rest); } catch (Throwable ignored) {}
                            if (a > 0f && (fsVideoAspect == null
                                    || Math.abs(fsVideoAspect.floatValue() - a) > 0.02f)) {
                                fsVideoAspect = clampVideoAspect(a);
                                try {
                                    if (inPip) updatePipActions();
                                    else updatePipParams();
                                } catch (Throwable ignored) {}
                            }
                            // v1.3.64 — one evidence line per PiP session
                            // (kept; now with the target + aspect detail)
                            if (!fsPipPinSaid) {
                                fsPipPinSaid = true;
                                StringBuilder line = new StringBuilder("fs pip pin=")
                                        .append(ok ? "1" : "0");
                                if (ok) {
                                    line.append(" player=")
                                            .append("1".equals(code) ? "1" : "0");
                                    if ("3".equals(code)) line.append(" held");
                                    if (a > 0f) line.append(" as=")
                                            .append(clampVideoAspect(a).toString());
                                }
                                com.bolt.browser.util.Diag.log(BrowserActivity.this,
                                        line.toString());
                            }
                        } catch (Throwable ignored) {}
                    } : null);
        } catch (Throwable ignored) {}
    }

    /** v1.3.62 — retire the wedged-enter rescue CSS (fullscreen exit, tab
     *  switch, page start, activity death, or a LATE settle that made the
     *  real top layer engage). Idempotent; safe from teardown paths.
     *  v1.3.63 — also clears the page-side overlay sweep (restore every
     *  overlay the document-fullscreen concealment hid).
     *  v1.3.64 — GUARDED while in PiP: the settle probe's "fsel=1 -> retire"
     *  and the tab-switch/page-start retires all fire happily INSIDE a PiP
     *  session now that the takeover keeps a pin live for the whole session
     *  — unpinning there would hand the floating window straight back to
     *  page UI (the exact v1.3.63 bug). Only the expand-back and the
     *  activity death may unpin: they call fsRemoveCssRescueNow(). */
    protected void fsRemoveCssRescue() {
        if (inPip) return;
        fsRemoveCssRescueNow();
    }

    /** v1.3.64 — the UNCONDITIONAL retire (expand-back, onDestroy). */
    protected void fsRemoveCssRescueNow() {
        WebView w = fsSettleWeb;
        fsSettleWeb = null;
        if (w == null) return;
        try {
            com.bolt.browser.util.Diag.log(this, "fs rescue off");
        } catch (Throwable ignored) {}
        try {
            w.evaluateJavascript("try{window.__boltFsSweep&&window.__boltFsSweep(0)}catch(e){}",
                    null);
        } catch (Throwable ignored) {}
        try {
            // v1.3.65 — the imported engine's full restore: the takeover
            // classes off every layer, the edge strips + style element
            // removed, and the legacy v1.3.64 __boltfsf pin swept too (a
            // zombie upgraded mid-session must never stay pinned)
            w.evaluateJavascript(
                    com.bolt.browser.util.PipVideo.unpinJs(), null);
        } catch (Throwable ignored) {}
    }

    /** v1.3.62 — restore the page scroll captured at fullscreen entry (the
     *  renderer's DidEnterFullscreen zeroes the visual-viewport offset; the
     *  exit-side reset re-fits the page but leaves it at the top). */
    protected void fsRestoreScroll() {
        WebView w = fsScrollWeb;
        fsScrollWeb = null;
        if (w == null) return;
        try {
            w.evaluateJavascript("try{window.scrollTo(" + fsSavedScrollX + ","
                    + fsSavedScrollY + ")}catch(e){}", null);
        } catch (Throwable ignored) {}
    }

    /** v1.3.61 — clamp a video aspect into PiP's legal window
     * (2.39:1 .. 1:2.39 per PictureInPictureParams.Builder) as a Rational
     * with bounded terms; null never (callers want a usable value). */
    protected android.util.Rational clampVideoAspect(float aspect) {
        if (!(aspect > 0f) || Float.isNaN(aspect)) aspect = 16f / 9f;
        if (aspect > 2.39f) aspect = 2.39f;
        if (aspect < 1f / 2.39f) aspect = 1f / 2.39f;
        int num = Math.round(aspect * 100f);
        int den = 100;
        try { return new android.util.Rational(num, den); }
        catch (Throwable t) { return new android.util.Rational(16, 9); }
    }

    /** make the browser window immersive (fullscreen video) */
    protected void hideSystemBars() {
        try {
            android.view.Window w = getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
                w.getInsetsController().hide(
                        android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars());
            } else {
                w.getDecorView().setSystemUiVisibility(
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
            w.setStatusBarColor(android.graphics.Color.BLACK);
            w.setNavigationBarColor(android.graphics.Color.BLACK);
        } catch (Throwable ignored) {}
    }

    /** restore the normal window: status/nav bars tinted like the toolbars */
    protected void restoreBars() {
        if (customView != null) return; // keep immersive while video is fullscreen
        try {
            // v1.3.39: adaptive glass decides the tint (was a fixed isDark()
            // guess); the bar at the TOP edge owns the status-bar color.
            // v1.3.40: edge semantics (see applySkinChrome).
            boolean topEdgeWhite = topEdgeGlassWhite;
            int bg = Fx.isGlass(this)
                    ? com.bolt.browser.util.GlassAdapt.sysBar(topEdgeWhite)
                    : Fx.bg(this);
            android.view.Window w = getWindow();
            if (w == null) return;
            // v1.3.39: fullscreen browsing keeps its own immersive window
            // state (Fullscreen.apply); only the non-fullscreen path re-fits
            // the decor here.
            if (com.bolt.browser.util.Fullscreen.on(this)) {
                com.bolt.browser.util.Fullscreen.apply(this, barsShown);
                return;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(true);
                w.getInsetsController().show(
                        android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars());
            } else {
                w.getDecorView().setSystemUiVisibility(
                        prefs.isDark() ? android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                : (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                        | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
            }
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
            if (Build.VERSION.SDK_INT >= 26) {
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ v1.3.39 fullscreen browsing

    /** the moveable hovering button that brings the bars back. */
    protected com.bolt.browser.util.HoverButton hoverBtn;

    protected boolean fullscreenOn() {
        return com.bolt.browser.util.Fullscreen.on(this);
    }

    /** wire the hover button (created once per activity instance; visibility
     *  follows the fullscreen pref on every resume). */
    protected void setupHoverButton() {
        try {
            if (hoverBtn == null && flRoot != null) {
                hoverBtn = new com.bolt.browser.util.HoverButton(this, flRoot);
                hoverBtn.onTap(() -> {
                    // press = bring the chrome (and the system bars) back;
                    // pressing again while shown slides everything away.
                    if (barsShown) hideBarsNow(true);
                    else showBarsNow(true);
                });
            }
            if (hoverBtn != null)
                hoverBtn.setVisible(fullscreenOn() && customView == null);
        } catch (Throwable ignored) {}
    }

    /** toggle fullscreen browsing from the menu / quick action. */
    protected void toggleFullscreenMode() {
        boolean now = !fullscreenOn();
        prefs.set(com.bolt.browser.util.Fullscreen.PREF, now);
        Fx.skinGen++;              // long-lived screens + the browser recreate
        Fx.toast(this, now ? getString(R.string.fullscreen_mode) + " ON"
                : getString(R.string.fullscreen_mode) + " OFF");
        // apply immediately here too (no restart needed in THIS window)
        com.bolt.browser.util.Fullscreen.apply(this, barsShown);
        setupHoverButton();
        applyContentInsets();
        // v1.3.40: turning fullscreen ON hides the chrome immediately — the
        // v1.3.39 code SHOWED it here, which was the "not hiding the search
        // and tool bars" field bug
        if (now) hideBarsNow(false);
        else {
            showBarsNow(false);
            restoreBars();
        }
    }

    // ------------------------------------------------------------ sheet menus

    protected static class MenuRow {
        int icon;
        String title, sub, value;
        String hint;                 // v1.3.39: long-press explainer text
        View.OnClickListener click;
        boolean switchRow;
        boolean checked;
        boolean header;              // v1.4.8: a section label row (title only,
                                     // rendered as a small dim label — the
                                     // "divide the site settings into chunks"
                                     // reorganisation)
        android.widget.CompoundButton.OnCheckedChangeListener switchChange;
    }

    /** v1.4.8 — a section-label row for the sheet menus: small dim medium
     *  label, no icon, no chrome — the same look the deep settings' grouped
     *  cards use (Ui.sectionLabel). */
    protected MenuRow headerRow(String title) {
        MenuRow r = new MenuRow();
        r.header = true;
        r.title = title;
        return r;
    }

    protected void showSheetMenu(String title, List<MenuRow> rows) {
        showSheetMenu(title, null, rows);
    }

    /** v1.3.39 HYBRID MENU — a horizontally scrolling CARD tier on top of
     *  the vertical list (field report: "long lists are pretty exhausting
     *  to look at … a mixture of horizontally scrolling cards and vertically
     *  scrolling lists in the same menu"). [cards] may be null. */
    protected void showSheetMenu(String title, List<com.bolt.browser.util.MenuCards.Card> cards,
            List<MenuRow> rows) {
        // v1.3.34: the modern sheet — Ui.sheetBody supplies the grab handle +
        // padding, Ui.sheetBg the skin-aware rounded-top background (glass
        // keeps its translucent fill + hairline rim so the dimmed page reads
        // through). Rows: neutral icon containers + modern switches + value
        // chips (item_menu_row).
        LinearLayout list = com.bolt.browser.util.Ui.sheetBody(this);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(list);
        sc.setBackground(com.bolt.browser.util.Ui.sheetBg(this));
        sc.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int tint = Fx.tint(this);
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(18);
        tv.setTypeface(com.bolt.browser.util.Ui.medium());
        tv.setTextColor(Fx.txt(this));
        tv.setPadding(Fx.dp(this, 6), Fx.dp(this, 12), Fx.dp(this, 6), Fx.dp(this, 8));
        list.addView(tv);
        // the horizontal quick-action tier (long-press any card for the
        // explainer — MenuCards wires it)
        if (cards != null && !cards.isEmpty()) {
            View strip = com.bolt.browser.util.MenuCards.cardStrip(this, cards);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.bottomMargin = Fx.dp(this, 8);
            list.addView(strip, slp);
        }
        for (MenuRow r : rows) {
            // v1.4.8 — a section label row: the small dim title between
            // chunks (no icon, no switch, no press feedback)
            if (r.header) {
                list.addView(com.bolt.browser.util.Ui.sectionLabel(this, r.title));
                continue;
            }
            View row = getLayoutInflater().inflate(R.layout.item_menu_row, list, false);
            // icon container: the placeholder icon fills its 40dp frame and
            // gets the neutral box treatment (surface2 fill + tint icon)
            ImageView ic = row.findViewById(R.id.iv_menu_icon);
            android.widget.FrameLayout.LayoutParams clp =
                    new android.widget.FrameLayout.LayoutParams(-1, -1);
            ic.setLayoutParams(clp);
            ic.setImageResource(r.icon);
            com.bolt.browser.util.Ui.iconify(ic, false);
            TextView t = row.findViewById(R.id.tv_menu_title);
            t.setText(r.title);
            if (r.sub != null) {
                TextView s = row.findViewById(R.id.tv_menu_sub);
                s.setText(r.sub);
                s.setVisibility(View.VISIBLE);
            }
            if (r.value != null) {
                TextView v = row.findViewById(R.id.tv_menu_value);
                v.setText(r.value);
                v.setTextColor(Fx.accentText(this));
                v.setTextSize(13);
                v.setTypeface(com.bolt.browser.util.Ui.medium());
                v.setVisibility(View.VISIBLE);
            }
            LinearLayout ll = row.findViewById(R.id.ll_menu_row);
            if (r.switchRow) {
                com.bolt.browser.util.Ui.ModernSwitch sw =
                        com.bolt.browser.util.Ui.modernSwitch(this, r.checked, on -> {
                            if (r.switchChange != null) r.switchChange.onCheckedChanged(null, on);
                        });
                ll.addView(sw, new LinearLayout.LayoutParams(-2, -2));
                row.setClickable(false);
            } else if (r.click != null) {
                row.setOnClickListener(r.click);
            }
            // v1.3.39: long-press any menu row → the explainer popup (field
            // report: "for every button … make long pressing them make a
            // popup explaining its function and feature")
            if (r.hint != null) {
                com.bolt.browser.util.MenuCards.explainRow(row, this, r.title, r.hint);
            }
            pressFx(row, ic, tint);
            list.addView(row);
        }
        final android.app.Dialog d = new android.app.Dialog(this);
        d.setContentView(sc);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout(-1, -2);
            w.setGravity(Gravity.BOTTOM);
        }
        d.setOnDismissListener(dd -> {
            if (sheetDialog == dd) sheetDialog = null;
        });
        d.show();
        sheetDialog = d;
    }

    /** close every open menu/overlay and give the page focus back */
    protected void dismissAllOverlays() {
        try {
            if (sheetDialog != null && sheetDialog.isShowing()) sheetDialog.dismiss();
            sheetDialog = null;
        } catch (Throwable ignored) {}
        dismissSdFolderWindow();                        // v1.4.0: the folder window
        hideQuickPanel();
        hideOtpChip();                                 // v1.3.31
        hidePwChip();                                  // v1.3.38
        hideProfileChip();                             // v1.3.39
        try {
            if (omniboxDialog != null && omniboxDialog.isShowing()) omniboxDialog.dismiss();
        } catch (Throwable ignored) {}
        hideKeyboard();
    }

    // ------------------------------------------------------------ v1.3.31 OTP fill chip

    /** the live-code updater for the OTP chip (1 s cadence, 30 s lifetime). */
    protected final Runnable otpChipTick = this::otpChipTickRun;

    /** Show the fill chip: a focused 2FA input + an authenticator account
     *  (issuer matched to the current host first, else the first entry).
     *  Tap = fill the page's field with the CURRENT code. */
    protected void showOtpChip() {
        try {
            if (incognito) return;                    // identity data: main process only
            if (otpChip != null && otpChip.getParent() != null) return;
            String host = Fx.hostOf(activeTab().url == null ? "" : activeTab().url);
            org.json.JSONObject e = prefs.authMatchForHost(host);
            if (e == null) e = prefs.firstAuthEntry();
            if (e == null) return;                    // nothing to offer
            otpChipEntry = e;
            otpChipShownAt = System.currentTimeMillis();
            TextView tv = new TextView(this);
            // v1.3.33: runtime accent (bg_pill would use the skin default)
            android.graphics.drawable.GradientDrawable chipBg =
                    new android.graphics.drawable.GradientDrawable();
            chipBg.setColor(Fx.accent(this));
            chipBg.setCornerRadius(Fx.dp(this, 18));
            tv.setBackground(chipBg);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tv.setTextSize(15);
            int ph = Fx.dp(this, 16), pv = Fx.dp(this, 10);
            tv.setPadding(ph, pv, ph, pv);
            tv.setOnClickListener(v -> fillOtpFromChip());
            otpChip = tv;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            lp.topMargin = Fx.dp(this, 96);           // baseline; anchorChipToField places it at the field
            flRoot.addView(tv, lp);
            anchorChipToField(tv);
            chipSettle(tv);
            ui.removeCallbacks(otpChipTick);
            ui.post(otpChipTick);
        } catch (Throwable ignored) {}
    }

    protected void otpChipTickRun() {
        try {
            TextView tv = otpChip;
            org.json.JSONObject e = otpChipEntry;
            if (tv == null || e == null || tv.getParent() == null) {
                ui.removeCallbacks(otpChipTick);
                return;
            }
            if (System.currentTimeMillis() - otpChipShownAt > 30_000) {
                hideOtpChip();
                return;
            }
            long now = System.currentTimeMillis();
            String code = com.bolt.browser.util.Totp.codeFor(e.optString("secret", ""),
                    e.optString("algorithm", "SHA1"), e.optInt("digits", 6),
                    e.optInt("period", 30), now);
            int left = com.bolt.browser.util.Totp.secondsLeft(e.optInt("period", 30), now);
            String name = com.bolt.browser.util.Totp.displayName(
                    e.optString("issuer", ""), e.optString("account", ""));
            tv.setText(getString(R.string.auth_fill_chip, name) + "   "
                    + (code == null ? "······" : code) + "   " + left + "s");
            ui.postDelayed(otpChipTick, 1000);
        } catch (Throwable ignored) {}
    }

    /** tap on the chip: push the CURRENT code into the page's OTP field. */
    protected void fillOtpFromChip() {
        try {
            org.json.JSONObject e = otpChipEntry;
            if (e == null) { hideOtpChip(); return; }
            String code = com.bolt.browser.util.Totp.codeFor(e.optString("secret", ""),
                    e.optString("algorithm", "SHA1"), e.optInt("digits", 6),
                    e.optInt("period", 30), System.currentTimeMillis());
            if (code != null) {
                evalMedia("window.__boltFillOtp&&window.__boltFillOtp('" + code + "')");
            }
            hideOtpChip();
        } catch (Throwable ignored) {}
    }

    protected void hideOtpChip() {
        ui.removeCallbacks(otpChipTick);
        try {
            if (otpChip != null && otpChip.getParent() == flRoot) flRoot.removeView(otpChip);
        } catch (Throwable ignored) {}
        otpChip = null;
        otpChipEntry = null;
    }

    // ------------------------------------------------------------ main menu

    protected void showMainMenu() {
        final Tab t = activeTab();
        List<MenuRow> rows = new ArrayList<>();
        // ---------------- v1.3.39 the CARD tier (hybrid menu) ----------------
        // Field report: "the site settings and top right 3 dot settings … a
        // mixture of horizontally scrolling cards and vertically scrolling
        // lists in the same menu." The quick actions live up here; the long
        // tail stays vertical below. Long-press any card = the explainer.
        List<com.bolt.browser.util.MenuCards.Card> cards = new ArrayList<>();
        cards.add(card(R.drawable.ic_plus, "New tab",
                "Open a fresh empty tab in the background and switch to it.", () -> openNewTab(null)));
        // v1.3.91 — MOVED TO THE FRONT (was 8th; the field report read the
        // buried card as "the feature is still not present").
        // v1.3.95 — RENAMED "AI export" -> "Save AI response": the THIRD
        // field report in a row said "I don't see a way to save the zai or
        // any ai response as pdf or image" — the feature was on the front
        // row the whole time under a name that does not say what it does.
        // The label now names the outcome; the row also lives inside the
        // Save page sheet now, where a user who wants to SAVE something
        // looks first.
        cards.add(card(R.drawable.ic_download, "Save AI response",
                "Pick chat answers on this page (Zai, ChatGPT, Gemini, Claude, Qwen… — or tap blocks to select on any site) and save them as a PDF (selectable text) or a PNG image — the site's own formatting intact.",
                () -> startAiExport()));
        cards.add(card(R.drawable.ic_share, "Share",
                "Send the current page link through Android's share sheet (apps, copy, nearby).", () -> sharePage()));
        cards.add(card(R.drawable.ic_qr, "Scan QR",
                "Open the camera and read a QR code — the result can be opened or copied.", () -> startQrScan()));
        cards.add(card(R.drawable.ic_bookmark, "Later",
                "Save this page to the watch-later queue — unread until you come back to it.", () -> addWatchLater()));
        cards.add(card(R.drawable.ic_reader, "Reader",
                "Reader view: the article as clean text with font, width, serif and dark controls. Pinch inside it re-wraps the text.", () -> openReader()));
        cards.add(card(R.drawable.ic_search, "Find",
                "Search for words on this page; matches are highlighted and counted.", () -> openFind()));
        cards.add(card(R.drawable.ic_print, "Shot",
                "Full-page screenshot: capture the whole scrollable page as one PNG, saved to Downloads (share it from there).", () -> takeFullShot()));
        cards.add(card(R.drawable.ic_mic, "Voice",
                "Speak a search query — the transcript fills the address field.", () -> startVoiceSearch(1010)));

        // ---------------- the ROW tier ----------------
        // v1.4.9 — CHUNKED INTO GROUPS (the report: "group up the settings
        // in top right 3 dots settings under sections based on similarity"
        // — the same treatment the site settings got in v1.4.8): the long
        // flat tail becomes labelled chunks — page actions, blocking,
        // per-site overrides, saving, identity, tabs, display, AI &
        // scripts — so a scroll finds its family fast. Every row keeps its
        // exact behavior; only the order and the labels changed.

        // --- On this page ---
        rows.add(headerRow("On this page"));
        // v1.3.38 — SHARE PAGE AS QR: beam the URL to any nearby camera.
        rows.add(row(R.drawable.ic_qr, "Share as QR code",
                "Show this page's link as a scannable QR code — save it, share it, or copy the text.",
                v -> sharePageAsQr()));
        // v1.3.39 — PAGE OUTLINE (menu-3 #12): jump table for long articles.
        rows.add(row(R.drawable.ic_outline, "Page outline",
                "A table of contents built from the page's headings — tap a line to jump there.",
                v -> showOutline()));
        rows.add(row(R.drawable.ic_globe, getString(R.string.translate_page),
                "Translate the foreign-language parts of this page in place — same tab, "
                        + "still logged in; a pill offers undo.",
                v -> translatePage()));
        rows.add(row(R.drawable.ic_dl_image, getString(R.string.translate_screen),
                "Translate what is ON SCREEN right now — even text baked into images "
                        + "(manga, photos, canvas). Offline text recognition, then a "
                        + "translatable overlay; Save exports the picture.",
                v -> translateScreen()));
        rows.add(row(R.drawable.ic_code, getString(R.string.inspect_elements),
                "Inspect the page's DOM and run JavaScript against it (dev tools).",
                v -> startInspector()));
        rows.add(row(R.drawable.ic_speak, getString(R.string.text_to_speech),
                "Read the page aloud with the system voice — controls ride a small bar.",
                v -> startTts()));

        // --- Content blocking ---
        rows.add(headerRow("Content blocking"));
        MenuRow ad = row(R.drawable.ic_filter, getString(R.string.ad_blocking),
                "Master switch for the ad and tracker blocker; per-site control lives behind the tune button.",
                v -> {
                    boolean now = !prefs.adblockEnabled();
                    prefs.setAdblock(now);
                    Fx.toast(this, getString(R.string.ad_blocking) + ": " + (now ? "ON" : "OFF"));
                    dismissAllOverlays();
                    if (t.web != null) t.web.reload();
                });
        ad.value = prefs.adblockEnabled() ? "ON" : "OFF";
        rows.add(ad);
        // v1.3.40 — CLEAN TRACKERS per-site switch (round-4 #4): the global
        // toggle lives in Privacy; this row is the per-site override.
        MenuRow ct = row(R.drawable.ic_filter, "Clean trackers",
                "Strip tracking parameters (utm_*, fbclid, gclid…) from links you open and share. This row is the per-site override.",
                v -> toggleSiteClean());
        ct.value = siteCleanNow(curDomain()) ? "ON" : "OFF";
        rows.add(ct);

        // --- This site ---
        rows.add(headerRow("This site"));
        // v1.3.39 — PER-SITE DARK (menu-3 #5): one-tap dark override for this site.
        MenuRow sd = row(R.drawable.ic_moon, "Dark site",
                "Force a dark version of THIS site only (stored as a site override; the global toggle stays in Appearance).",
                v -> toggleSiteDark());
        sd.value = siteDarkNow(curDomain()) ? "ON" : "OFF";
        rows.add(sd);
        // v1.3.40 — PER-SITE ZOOM (round-4 #5): a remembered textZoom
        // override for this site, like desktop/dark.
        MenuRow pz = row(R.drawable.ic_tune, "Site zoom",
                "A text-size override remembered for this site — for sites with tiny or huge text. Default follows the global font size.",
                v -> siteZoomDialog());
        int zPct = prefs.siteInt(curDomain(), "zoom", -1);
        pz.value = zPct >= 60 ? zPct + "%" : "Default";
        rows.add(pz);
        MenuRow desk = row(R.drawable.ic_desktop, getString(R.string.desktop_site),
                "Load the desktop version of this site (per-site user agent override).",
                v -> {
                    String base = curDomain();
                    boolean now = !prefs.siteBool(base, "desktop", false);
                    prefs.setSite(base, "desktop", now);
                    dismissAllOverlays();
                    if (t.web != null) {
                        t.applyUa(this, t.web);
                        t.web.reload();
                    }
                });
        String base = curDomain();
        desk.value = prefs.siteBool(base, "desktop", false) ? "ON" : "OFF";
        rows.add(desk);

        // --- Save & capture ---
        rows.add(headerRow("Save & capture"));
        rows.add(row(R.drawable.ic_bookmark, getString(R.string.add_bookmark),
                "Bookmark this page (folders can be chosen in the bookmarks screen).",
                v -> addBookmark()));
        rows.add(row(R.drawable.ic_home, getString(R.string.add_shortcut),
                "Put a shortcut to this page on the home screen.", v -> addShortcut()));
        // v1.3.91 — THE SAVE-PAGE PASS (the field report: "add options to
        // save pages as pdf, print, html etc. By maintaining formatting"):
        // one sheet, four outputs — PDF (the print adapter straight to
        // Downloads), PRINT (the system preview), HTML (the single-file
        // self-serializing archive) and the legacy offline MHT.
        rows.add(row(R.drawable.ic_download, getString(R.string.save_page),
                "Save or print this page: PDF file, system print, a single-file HTML archive, or the offline (.mht) reader copy — formatting kept.",
                v -> showSavePageSheet()));
        // v1.3.38 — WATCH LATER with the unread count riding the row.
        MenuRow wl = row(R.drawable.ic_bookmark, "Watch later",
                "The save-for-later queue: unread items first, tap to open and mark read.",
                v -> startActivity(new Intent(this, com.bolt.browser.ui.WatchLaterActivity.class)));
        try {
            int unread = com.bolt.browser.util.WatchLater.unread(this);
            if (unread > 0) wl.value = String.valueOf(unread);
        } catch (Throwable ignored) {}
        rows.add(wl);

        // --- Privacy & identity --- (main process only; incognito has no
        // identity data by design — the whole chunk hides there)
        if (!incognito) {
            rows.add(headerRow("Privacy & identity"));
            // v1.3.31 — the built-in authenticator: 2FA codes while signing in
            MenuRow auth = row(R.drawable.ic_lock, getString(R.string.authenticator),
                    "Two-factor codes built into the browser — paste a setup key or import from Google Authenticator.",
                    v -> startActivity(new Intent(this,
                            com.bolt.browser.ui.AuthenticatorActivity.class)));
            try {
                auth.value = String.valueOf(prefs.authEntries().length());
            } catch (Throwable ignored) {}
            rows.add(auth);
            // v1.3.38 — the password vault (credential-gated).
            MenuRow pw = row(R.drawable.ic_lock, "Saved passwords",
                    "The login vault: encrypted with the Android keystore, gated by the device credential; save offers appear on sign-in forms.",
                    v -> startActivity(new Intent(this,
                            com.bolt.browser.ui.PasswordVaultActivity.class)));
            try { pw.value = String.valueOf(com.bolt.browser.util.PasswordVault.count(this)); }
            catch (Throwable ignored) {}
            rows.add(pw);
            // v1.3.39 — PROFILES (menu-3 #13).
            MenuRow pr = row(R.drawable.ic_person, "Profiles",
                    "Address & card profiles (encrypted) — tap to manage; matching forms get a one-tap fill chip.",
                    v -> startActivity(new Intent(this,
                            com.bolt.browser.ui.ProfilesActivity.class)));
            try { pr.value = String.valueOf(com.bolt.browser.util.Profiles.count(this)); }
            catch (Throwable ignored) {}
            rows.add(pr);
        }

        // --- Tabs & workspaces ---
        rows.add(headerRow("Tabs & workspaces"));
        // v1.3.40 — WORKSPACES (field task 15: Profiles + Sessions merged
        // into Chrome-style spaces; sessions.json migrates automatically).
        if (!incognito && com.bolt.browser.util.Workspaces.enabled(this)) {
            MenuRow wsp = row(R.drawable.ic_person, "Workspaces",
                    "Named spaces with their own tab sets — switch and the open tabs ride along. One live session; last-tabs recovery untouched.",
                    v -> startActivity(new Intent(this,
                            com.bolt.browser.ui.WorkspacesActivity.class)));
            wsp.value = com.bolt.browser.util.Workspaces.active(this);
            rows.add(wsp);
        }
        // v1.3.38 — TAB SLEEP / FOCUS MODE.
        rows.add(row(R.drawable.ic_moon, "Sleep other tabs",
                "Freeze every background tab — their renderers' RAM comes back; each wakes exactly where you left it.",
                v -> { dismissAllOverlays(); sleepOthersNow(); }));

        // --- Display & media ---
        rows.add(headerRow("Display & media"));
        // v1.3.39 — FULLSCREEN BROWSING + NIGHT SCREEN quick toggles.
        MenuRow fs = row(R.drawable.ic_expand, getString(R.string.fullscreen_mode),
                "Hide every bar and the Android status bar for true edge-to-edge browsing — a moveable floating button brings them back (toggle also in the surface Settings).",
                v -> { dismissAllOverlays(); toggleFullscreenMode(); });
        fs.value = fullscreenOn() ? "ON" : "OFF";
        rows.add(fs);
        MenuRow ns = row(R.drawable.ic_moon, "Night screen",
                "A warm amber veil over the page for night reading — intensity and an on/off schedule live in deep settings.",
                v -> { dismissAllOverlays(); com.bolt.browser.util.NightScreen.toggle(this); });
        ns.value = com.bolt.browser.util.NightScreen.scheduled(this) ? "ON" : "OFF";
        rows.add(ns);
        // v1.3.38 — manual picture-in-picture.
        // v1.3.41 — video-only: with no fullscreen video the row explains
        // itself instead of doing nothing (the v1.3.40 behavior shrank the
        // WHOLE SITE into the floating window)
        if (Build.VERSION.SDK_INT >= 26) {
            rows.add(row(R.drawable.ic_arrow_up, "Picture-in-picture",
                    "Float the video you are watching fullscreen. Play a video, tap its fullscreen button, then use this (or swipe home).",
                    v -> {
                        dismissAllOverlays();
                        if (customView == null) {
                            Fx.toast(this, "Enter fullscreen video first");
                            return;
                        }
                        enterPipIfEligible();
                    }));
        }

        // --- AI & scripts ---
        rows.add(headerRow("AI & scripts"));
        // v1.3.75 — BOLT AI: the browser-wide assistant. The user's ask:
        // "a browser wide AI, accessible anywhere inside the browser… chat
        // with it for help or ask to summarise the page I am on… translate
        // what I see, including images… quick plug and play, completely
        // free no hassle." One chat panel (this menu reaches it on any page
        // and on home; Settings has its own row), powered by the same
        // AiBridge config the Translate-screen AI row uses — editable any
        // time from the panel's gear (the v1.3.74 "no option to change
        // inputs" complaint).
        rows.add(row(R.drawable.ic_fire, "Bolt AI",
                "Your browser-wide AI assistant — chat for help, summarize the page "
                        + "you're on, explain a selection, or translate what you see "
                        + "(manga and images included). Free with a Gemini key or any "
                        + "OpenAI-compatible endpoint; the key stays on this device.",
                v -> {
                    dismissAllOverlays();
                    try {
                        com.bolt.browser.util.BoltAi.open(this,
                                t == null ? null : t.web,
                                url -> openNewTab(url));
                    } catch (Throwable e) {
                        Fx.toast(this, "Could not open Bolt AI");
                    }
                }));
        MenuRow scripts = row(R.drawable.ic_script, getString(R.string.active_scripts),
                "The userscripts currently running on this page.",
                v -> showActiveScripts());
        try {
            List<UserscriptEngine.Script> act = UserscriptEngine.forUrl(this, t.url);
            scripts.value = String.valueOf(act.size());
        } catch (Throwable ignored) {}
        rows.add(scripts);
        rows.add(row(R.drawable.ic_script, getString(R.string.manage_scripts),
                "The userscript manager: install, edit, enable and disable Tampermonkey-style scripts.",
                v -> startActivity(new Intent(this, com.bolt.browser.scripts.UserscriptsActivity.class))));
        // GM_registerMenuCommand entries of the current page
        if (!scriptMenuCmds.isEmpty() && !t.isHome) {
            for (int i = 0; i < scriptMenuCmds.size(); i++) {
                final int idx = i;
                rows.add(row(R.drawable.ic_script, scriptMenuCmds.get(i),
                        "A menu command registered by one of this page's userscripts.",
                        v -> evalMedia("window.__boltRunMenuCmd&&window.__boltRunMenuCmd(" + idx + ")")));
            }
        }

        // the terminal action stays apart at the very bottom
        rows.add(row(R.drawable.ic_power, getString(R.string.exit_browser),
                "Close every tab and leave the browser.", v -> {
                    dismissAllOverlays();
                    confirmExit();
                }));
        showSheetMenu(getString(R.string.more_options), cards, rows);
    }

    protected MenuRow row(int icon, String title, View.OnClickListener click) {
        MenuRow r = new MenuRow();
        r.icon = icon;
        r.title = title;
        r.click = click;
        return r;
    }

    /** v1.3.39: row + hint (the long-press explainer). */
    protected MenuRow row(int icon, String title, String hint, View.OnClickListener click) {
        MenuRow r = row(icon, title, click);
        r.hint = hint;
        return r;
    }

    /** v1.3.39: a hybrid-menu card. */
    protected com.bolt.browser.util.MenuCards.Card card(int icon, String label, String hint,
            Runnable click) {
        return com.bolt.browser.util.MenuCards.card(icon, label, hint, click);
    }

    protected String curDomain() {
        return Fx.baseDomain(Fx.hostOf(activeTab().url));
    }

    // ------------------------------------------------------------ site settings

    protected void showSiteSettings() {
        final String domain = curDomain();
        if (domain.isEmpty()) {
            Fx.toast(this, "Open a website first");
            return;
        }
        // v1.3.39 HYBRID: the destructive/rare actions ride a horizontal
        // CARD tier (field report: the site settings sheet was the poster
        // child of "exhausting to look at"); per-site toggles stay vertical.
        List<com.bolt.browser.util.MenuCards.Card> cards = new ArrayList<>();
        cards.add(card(R.drawable.ic_edit, "Filters",
                "Hand-written adblock rules for this site only (hosts/selectors), one per line.",
                () -> editSiteFilters(domain)));
        cards.add(card(R.drawable.ic_delete, "Wipe",
                "Clear this site's cookies, storage and cache — logins reset, trackers forget you.",
                () -> confirmWipeSite(domain)));
        cards.add(card(R.drawable.ic_history, "Reset",
                "Drop every per-site override for " + domain + " back to the global defaults.",
                () -> {
                    prefs.resetSite(domain);
                    Fx.toast(this, "Site settings reset");
                    reloadIfLive();
                }));

        // v1.4.8 — CHUNKED INTO GROUPS (the report: "The site settings have
        // become too cluttered with options, divide them into chunks of
        // similar groups. Just simple reorganisation with a small title for
        // the chunks to make scrolling through them easier."): thirteen
        // flat rows became four labelled chunks — blocking, privacy,
        // appearance, media & device — so a scroll finds its family fast.
        List<MenuRow> rows = new ArrayList<>();
        // --- Content blocking ---
        rows.add(headerRow("Content blocking"));
        MenuRow ad = new MenuRow();
        ad.icon = R.drawable.ic_filter;
        ad.title = getString(R.string.ads_and_trackers);
        ad.hint = "Block ads and trackers on " + domain + " (the master switch is in the quick settings card on the settings home).";
        ad.switchRow = true;
        int sState = prefs.siteAdblock(domain);
        ad.checked = sState == 1 || (sState == -1 && prefs.adblockEnabled() && !prefs.siteBool(domain, "noAds", false));
        ad.switchChange = (b, on) -> {
            if (on) {
                prefs.setSite(domain, "adblock", true);
                prefs.setSite(domain, "noAds", false);
            } else {
                prefs.setSite(domain, "adblock", false);
                prefs.setSite(domain, "noAds", true);
            }
            reloadIfLive();
        };
        rows.add(ad);
        rows.add(switchRow(R.drawable.ic_code, getString(R.string.block_javascript),
                "Disable JavaScript on this site — breaks interactive apps but stops every script.",
                prefs.siteBool(domain, "noJs", false), (b, on) -> {
                    prefs.setSite(domain, "noJs", on);
                    reloadIfLive();
                }));
        rows.add(switchRow(R.drawable.ic_script, getString(R.string.disable_userscripts),
                "Stop userscripts from running on this site.",
                prefs.siteBool(domain, "noScripts", false), (b, on) -> prefs.setSite(domain, "noScripts", on)));
        // --- Privacy & cookies ---
        rows.add(headerRow("Privacy & cookies"));
        rows.add(switchRow(R.drawable.ic_incognito, getString(R.string.block_3rd_party),
                "Refuse third-party requests on this site (network-level cut-off).",
                prefs.siteBool(domain, "block3rd", false), (b, on) -> prefs.setSite(domain, "block3rd", on)));
        rows.add(switchRow(R.drawable.ic_lock, getString(R.string.block_cookies),
                "Reject this site's cookies and local storage (you will not stay logged in).",
                prefs.siteBool(domain, "noCookies", false), (b, on) -> {
                    prefs.setSite(domain, "noCookies", on);
                    reloadIfLive();
                }));
        // v1.3.39 — the vault's per-site opt-out moved here where it belongs.
        rows.add(switchRow(R.drawable.ic_lock, "Offer to save logins",
                "Show Bolt's save-password sheet when you sign in on this site.",
                !prefs.siteBool(domain, "noPwSave", false), (b, on) ->
                        prefs.setSite(domain, "noPwSave", !on)));
        // --- Appearance ---
        rows.add(headerRow("Appearance"));
        // v1.3.39 — PER-SITE DARK switch (menu-3 #5's settings home).
        rows.add(switchRow(R.drawable.ic_moon, "Dark site",
                "Force this site to render dark (per-site override of the global dark-web toggle).",
                prefs.siteBool(domain, "dark", prefs.webDark()), (b, on) -> {
                    prefs.setSite(domain, "dark", on);
                    reloadIfLive();
                }));
        rows.add(switchRow(R.drawable.ic_desktop, getString(R.string.always_desktop),
                "Always request the DESKTOP version of this site.",
                prefs.siteBool(domain, "desktop", false), (b, on) -> {
                    prefs.setSite(domain, "desktop", on);
                    reloadIfLive();
                }));
        MenuRow ua = row(R.drawable.ic_mobile, getString(R.string.user_agent),
                "Which user agent this site sees: the live mobile UA, a desktop one, or the global default.",
                v -> chooseUa(domain));
        int uai = prefs.siteInt(domain, "ua", -1);
        String[] names = getResources().getStringArray(R.array.ua_names);
        ua.value = uai >= 0 && uai < names.length ? names[uai] : getString(R.string.ua_default);
        rows.add(ua);
        // --- Media & device access ---
        rows.add(headerRow("Media & device access"));
        rows.add(switchRow(R.drawable.ic_volume, getString(R.string.disable_bg_play),
                "Stop audio/video playback from continuing when you leave this site.",
                prefs.siteBool(domain, "noBg", false), (b, on) -> prefs.setSite(domain, "noBg", on)));
        rows.add(switchRow(R.drawable.ic_mic, getString(R.string.disable_camera),
                "Deny camera access to this site.",
                prefs.siteBool(domain, "noCam", false), (b, on) -> prefs.setSite(domain, "noCam", on)));
        rows.add(switchRow(R.drawable.ic_mic, getString(R.string.disable_mic),
                "Deny microphone access to this site.",
                prefs.siteBool(domain, "noMic", false), (b, on) -> prefs.setSite(domain, "noMic", on)));
        rows.add(switchRow(R.drawable.ic_power, getString(R.string.block_external),
                "Block external app launches (intent:// links) from this site.",
                prefs.siteBool(domain, "noExt", false), (b, on) -> prefs.setSite(domain, "noExt", on)));
        showSheetMenu(domain, cards, rows);
    }

    protected MenuRow switchRow(int icon, String title, boolean checked,
            android.widget.CompoundButton.OnCheckedChangeListener change) {
        return switchRow(icon, title, null, checked, change);
    }

    /** v1.3.39: switch row + long-press explainer. */
    protected MenuRow switchRow(int icon, String title, String hint, boolean checked,
            android.widget.CompoundButton.OnCheckedChangeListener change) {
        MenuRow r = new MenuRow();
        r.icon = icon;
        r.title = title;
        r.hint = hint;
        r.switchRow = true;
        r.checked = checked;
        r.switchChange = change;
        return r;
    }

    protected void reloadIfLive() {
        dismissAllOverlays();
        Tab t = activeTab();
        if (t.web != null && !t.isHome) t.web.reload();
    }

    protected void editSiteFilters(final String domain) {
        List<String> rules = prefs.siteFilters(domain);
        StringBuilder sb = new StringBuilder();
        for (String r : rules) sb.append(r).append('\n');
        final EditText et = new EditText(this);
        et.setText(sb.toString());
        et.setMinLines(6);
        et.setGravity(Gravity.TOP);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setTextSize(14);
        new AlertDialog.Builder(this)
                .setTitle(R.string.custom_filters)
                .setView(et)
                .setPositiveButton(R.string.save, (d, w) -> {
                    org.json.JSONArray a = new org.json.JSONArray();
                    for (String line : et.getText().toString().split("\n")) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("!")) a.put(line);
                    }
                    prefs.setSite(domain, "filters", a);
                    Fx.toast(this, "Filters saved");
                    reloadIfLive();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    protected void chooseUa(final String domain) {
        String[] names = getResources().getStringArray(R.array.ua_names);
        int cur = prefs.siteInt(domain, "ua", -1);
        final String[] full = new String[names.length + 1];
        full[0] = getString(R.string.ua_default) + " (follow global)";
        System.arraycopy(names, 0, full, 1, names.length);
        dismissAllOverlays();
        // v1.3.34: modern bottom-sheet chooser (was an XP-era radio dialog)
        com.bolt.browser.util.Ui.choiceSheet(this, getString(R.string.user_agent), full, cur + 1,
                w -> {
                    prefs.setSite(domain, "ua", w - 1);
                    Tab t = activeTab();
                    if (t.web != null && !t.isHome) {
                        t.applyUa(this, t.web);
                        t.web.reload();
                    }
                });
    }

    // ------------------------------------------------------------ site data wipe

    protected void confirmWipeSite(final String domain) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_site_data)
                .setMessage("Remove all cookies, localStorage, IndexedDB, caches and service "
                        + "workers for " + domain + "?")
                .setPositiveButton(R.string.delete, (d, w) -> wipeSiteData(domain))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Domains whose cookies back the login of the wiped site (SSO families). */
    private static final java.util.Map<String, String[]> SSO_FAMILY = new java.util.HashMap<>();
    static {
        // YouTube login state lives on google.com cookies -- clearing only
        // youtube.com never logs the user out (the #1 old complaint).
        SSO_FAMILY.put("youtube.com", new String[]{"google.com", "accounts.google.com"});
        SSO_FAMILY.put("google.com", new String[]{"youtube.com", "accounts.google.com"});
        SSO_FAMILY.put("googlevideo.com", new String[]{"youtube.com", "google.com"});
        SSO_FAMILY.put("ytimg.com", new String[]{"youtube.com", "google.com"});
        SSO_FAMILY.put("facebook.com", new String[]{"facebook.net"});
        SSO_FAMILY.put("accounts.google.com", new String[]{"google.com", "youtube.com"});
    }

    protected void wipeSiteData(String domain) {
        // v1.3.64 — the wipe DRASTICALLY changes the website view (WebViews
        // die, the page rebuilds): the site-settings sheet must close NOW so
        // the user watches the wipe happen instead of a menu parked over a
        // view that already changed behind it, and an immediate toast answers
        // the DELETE tap (the "Site data cleared" line still lands when the
        // wipe completes) — the v1.3.63 report: "show me the button was
        // pressed and it is doing what it is supposed to".
        dismissAllOverlays();
        try { Fx.toast(this, "Clearing " + domain + "…"); } catch (Throwable ignored) {}
        final String base = Fx.baseDomain(domain);
        // related SSO domains + every host variant of each
        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
        for (String d : relatedDomains(base)) {
            for (String v : new String[]{d, "www." + d, "m." + d, "music." + d, "accounts." + d}) {
                if (v.contains(".")) targets.add(v);
            }
        }
        // 0. Collect affected tabs. Wipe their storage FROM THE PAGE FIRST (async
        //    CacheStorage / service-worker deletion needs the page alive to
        //    run), and only kill the page ~600ms later. v1.3.54: the window was
        //    260ms — too short for the async unregister()/caches.delete()
        //    promises to reliably finish before the renderer died. (The in-page
        //    indexedDB.deleteDatabase() is NOT made reliable by any window: a
        //    page holding its own IDB connections open — YouTube — BLOCKS the
        //    delete request forever; that deletion moved to step 5's on-disk
        //    sweep, the only deterministic path.)
        final List<Tab> affected = new ArrayList<>();
        synchronized (tm.tabs) {   // v1.4.11: the thread law — size()+get() as one step
            for (int i = 0; i < tm.tabs.size(); i++) {
                Tab t2 = tm.tabs.get(i);
                if (t2 == null) continue;
                String tb = Fx.baseDomain(Fx.hostOf(t2.url));
                boolean hit = false;
                for (String d : relatedDomains(base)) if (d.equals(tb)) hit = true;
                if (hit) affected.add(t2);
            }
        }
        final String[] restoreUrls = new String[affected.size()];
        for (int i = 0; i < affected.size(); i++) {
            Tab t2 = affected.get(i);
            restoreUrls[i] = t2.isHome ? null : t2.url;
            if (t2.web != null && !t2.isHome) {
                try { t2.web.evaluateJavascript(MediaHook.wipePageData(), null); } catch (Throwable ignored) {}
            }
        }
        final boolean activeAffected = affected.contains(activeTab());
        // v1.3.55: the EXACT hosts of the affected tabs join the wipe
        // targets. A CHIPS partition key is the schemeful site of the
        // top-level page — studio.youtube.com and youtube.com hold
        // SEPARATE partitioned copies of the same Domain=.youtube.com
        // cookie — so every host actually in use must be expired through
        // its own URL (and its IndexedDB dirs swept under its own
        // https_<host>_ prefix, which the shared targets set below feeds).
        for (Tab t2 : affected) {
            if (t2 == null || t2.isHome) continue;
            try {
                String th = Fx.hostOf(t2.url);
                if (th != null && th.contains(".") && !targets.contains(th)) targets.add(th);
            } catch (Throwable ignored) {}
        }
        // v1.3.55: best-effort READ-ONLY scan of the on-disk cookie jars for
        // any OTHER host of the family that holds a first-party PARTITIONED
        // (CHIPS) copy of its cookies (studio.youtube.com, tv., … — the
        // static variants + open tabs are only an approximation of every
        // partition a site has ever written). It runs in parallel with the
        // 600 ms in-page window and is consumed (bounded, never hanging the
        // UI) right before the expiry pass; every failure degrades to the
        // static list.
        final java.util.Set<String> chipsHosts =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
        final Thread chipsScan = new Thread(() -> {
            try {
                chipsHosts.addAll(com.bolt.browser.util.CookiePartitionScan
                        .scanFirstPartyPartitionHosts(BrowserActivity.this,
                                new java.util.HashSet<>(relatedDomains(base))));
            } catch (Throwable ignored) {}
        }, "bolt-chips-scan");
        chipsScan.start();
        // 1. after the in-page wipe had time to run: DESTROY the affected
        //    WebViews. Live renderers keep service-worker registrations and
        //    IndexedDB connections warm — on YouTube data silently survived
        //    wipes for exactly this reason. Fresh WebViews come back in step 6.
        ui.postDelayed(() -> {
            for (Tab t2 : affected) {
                if (t2.web != null) {
                    try { t2.web.stopLoading(); } catch (Throwable ignored) {}
                    try { t2.web.loadUrl("about:blank"); } catch (Throwable ignored) {}
                    try {
                        if (t2.web.getParent() instanceof ViewGroup)
                            ((ViewGroup) t2.web.getParent()).removeView(t2.web);
                        t2.web.destroy();
                    } catch (Throwable ignored) {}
                    t2.web = null;
                }
            }
            if (activeAffected) { shownTabIndex = -1; shownTabRef = null; } // force a clean rebuild
            // v1.3.55: fold the partition scan's hosts in (the scan had the
            // whole in-page window as head start; 400 ms more at the very
            // most, and only if a truly huge cookie jar is being ground
            // through). They join the expiry enumeration, the verification
            // AND the IndexedDB sweep's prefix set.
            // v1.3.79: 400 -> 200 ms — a main-thread join is a jank by
            // definition; the scan already had the whole in-page window as
            // head start, and whatever lands after this still folds into
            // the verification pass below
            try { chipsScan.join(200); } catch (InterruptedException ignored) {}
            if (!chipsHosts.isEmpty()) targets.addAll(chipsHosts);
            // 2. expire cookies (first pass)
            try {
                CookieManager cm = CookieManager.getInstance();
                for (String d : targets) expireCookies(cm, d, Fx.baseDomain(d));
                cm.flush();
            } catch (Throwable ignored) {}
            // 3. per-origin localStorage / IndexedDB / appcache -- use the REAL
            //    origin list from WebStorage so nothing is missed, plus variants
            try {
                final WebStorage ws = WebStorage.getInstance();
                final String fBase = base;
                try {
                    ws.getOrigins(new android.webkit.ValueCallback<java.util.Map>() {
                        @Override
                        public void onReceiveValue(java.util.Map origins) {
                            if (origins == null) return;
                            for (Object key : origins.keySet()) {
                                String o = String.valueOf(key);
                                String ob = Fx.baseDomain(Fx.hostOf(o));
                                boolean hit = false;
                                for (String d : relatedDomains(fBase)) if (d.equals(ob)) hit = true;
                                if (!hit) continue;
                                try { ws.deleteOrigin(o); } catch (Throwable ignored) {}
                            }
                        }
                    });
                } catch (Throwable ignored) {}
                for (String h : targets) {
                    try { ws.deleteOrigin("https://" + h); } catch (Throwable ignored) {}
                    try { ws.deleteOrigin("http://" + h); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            // v1.3.52 FIELD FIX ("clearing one site's data is clearing the site
            //    data of all websites"): this flow used to end with THREE global
            //    calls that made the per-site wipe a whole-browser wipe in
            //    disguise -- (a) WebViewDatabase.clearFormData() (the saved-form
            //    database is GLOBAL: every site's saved fields died with one),
            //    (b) a throwaway WebView.clearCache(true) (the HTTP cache is
            //    SHARED: every website's cached resources died, EVERY time), and
            //    (c) whenever any target cookie survived the per-site expiry,
            //    removeAllCookies() nuked the WHOLE jar on the theory it was a
            //    rare "last resort". Modern WebView cookie classes (partitioned
            //    / CHIPS cookies, any setCookie rejection) make the old
            //    `contains("=")` verification see "survivors" routinely, so the
            //    nuke fired on ordinary sites and logged the user out of the
            //    entire web. All three are GONE: a per-site wipe now has a
            //    per-site blast radius BY CONSTRUCTION -- the site's own
            //    storage/CacheStorage already died in-page (step 0) and
            //    per-origin (step 3), and a cookie that somehow survives the
            //    expiry gets ONE more targeted pass, an honest toast and a diag
            //    line naming it. NEVER global damage again.
            // 4. verify + second targeted cookie pass; survivors get a THIRD
            //    targeted pass, then honest reporting -- the jar is never nuked
            ui.postDelayed(() -> {
                java.util.List<String> survivors = expireAndVerify(targets);
                if (!survivors.isEmpty()) survivors = expireAndVerify(targets);
                if (!survivors.isEmpty()) {
                    try {
                        StringBuilder sb = new StringBuilder("siteWipe survivors");
                        for (String s : survivors) sb.append(' ').append(s);
                        com.bolt.browser.util.Diag.log(BrowserActivity.this, sb.toString());
                    } catch (Throwable ignored) {}
                    Fx.toast(BrowserActivity.this, getString(R.string.site_data_cleared)
                            + " (" + survivors.size() + " cookies survived)");
                } else {
                    Fx.toast(BrowserActivity.this, getString(R.string.site_data_cleared));
                }
                // 5. v1.3.54 FIRST-VISIT PASS — physically delete the site's
                //    per-origin storage DIRECTORIES from the WebView profiles:
                //    IndexedDB (+ the legacy File System / WebSQL trees). Why
                //    this had to exist: the in-page indexedDB.deleteDatabase()
                //    can never complete while the page is alive (the page's OWN
                //    open connections BLOCK the delete request — exactly
                //    YouTube's case, a heavy IDB user), and
                //    WebStorage.deleteOrigin's documented contract covers only
                //    AppCache/WebSQL/localStorage — IndexedDB is NOT in it
                //    (chromium issue 41199168: Won't Fix). So every previous
                //    wipe PROMISED IndexedDB deletion in the dialog text but
                //    structurally could not deliver it, and the surviving IDB
                //    carried the site's device-local memory (YouTube's
                //    signed-out history) straight through every wipe — "logins
                //    clear, history remains". The sweep runs on its own thread
                //    and touches ONLY directories named https_<target-host>_ /
                //    http_<target-host>_ under the IndexedDB / File System /
                //    databases subtrees of app_webview* — the per-site blast
                //    radius stays BY CONSTRUCTION (the shared Local Storage
                //    LevelDB is never touched here; it dies per-origin via
                //    deleteOrigin + the in-page clear).
                final boolean[] rebuilt = {false};
                final Runnable rebuild = () -> {
                    if (rebuilt[0]) return;
                    rebuilt[0] = true;
                    // 6. rebuild the affected tabs with fresh, cookieless,
                    //    storage-less WebViews
                    for (int i = 0; i < affected.size(); i++) {
                        Tab t2 = affected.get(i);
                        if (restoreUrls[i] != null && !restoreUrls[i].isEmpty()) {
                            t2.url = restoreUrls[i];
                            t2.isHome = false;
                        } else {
                            t2.isHome = true;
                            t2.url = "";
                        }
                    }
                    if (activeAffected || shownTabIndex < 0) showTab(tm.active);
                    else updateBars();
                };
                try {
                    com.bolt.browser.util.SiteStorageWipe.wipeOriginStorage(
                            BrowserActivity.this, targets,
                            r -> ui.post(() -> {
                                try {
                                    StringBuilder sb = new StringBuilder("siteWipe storage ")
                                            .append(r.freedBytes).append("B");
                                    for (int i = 0; i < r.origins.size() && i < 12; i++)
                                        sb.append(' ').append(r.origins.get(i));
                                    com.bolt.browser.util.Diag.log(BrowserActivity.this, sb.toString());
                                } catch (Throwable ignored) {}
                                rebuild.run();
                            }));
                } catch (Throwable err) {
                    rebuild.run();
                }
                // safety: file IO must never be able to hang the UI — rebuild
                // regardless after 1500ms even if the sweep thread stalls
                ui.postDelayed(rebuild, 1500);
            }, 300);
        }, 600);
    }

    /** one full expiry pass over the wipe targets, then the names (host:cookie)
     *  of any cookie that still reads back with a value -- for honest
     *  per-site reporting. v1.3.52: the old remains-check was `contains("=")`
     *  which counted our own empty-value expiry residue as survivors and then
     *  nuked the whole jar; this pass skips empty-value pairs (residue, not
     *  live cookies) and only reports what verifiably survived. */
    private java.util.List<String> expireAndVerify(java.util.Set<String> targets) {
        java.util.List<String> survivors = new ArrayList<>();
        try {
            CookieManager cm = CookieManager.getInstance();
            for (String d : targets) expireCookies(cm, d, Fx.baseDomain(d));
            cm.flush();
            for (String d : targets) {
                for (String scheme : new String[]{"https", "http"}) {
                    String c = cm.getCookie(scheme + "://" + d + "/");
                    if (c == null) continue;
                    for (String part : c.split(";")) {
                        String name = part.split("=", 2)[0].trim();
                        if (name.isEmpty()) continue;
                        // "name=" with nothing after it is expiry residue, not a
                        // live cookie; a real survivor carries a value
                        int eq = part.indexOf('=');
                        String val = eq >= 0 ? part.substring(eq + 1).trim() : "";
                        if (val.isEmpty()) continue;
                        survivors.add(d + ":" + name);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return survivors;
    }

    /** base + SSO-family domains that must be wiped together */
    private java.util.List<String> relatedDomains(String base) {
        java.util.List<String> out = new ArrayList<>();
        if (base == null || base.isEmpty()) return out;
        out.add(base);
        String[] fam = SSO_FAMILY.get(base);
        if (fam != null) for (String f : fam) if (!out.contains(f)) out.add(f);
        return out;
    }

    /** expires every cookie visible to the host variants of domain/base
     *  (handles __Secure- / __Host- prefixed cookies which reject plain expiry).
     *  v1.3.55: also expires the CHIPS PARTITIONED twin of every cookie —
     *  see the partAttr comment below; without it YouTube's signed-out
     *  identity cookies (YSC, VISITOR_INFO1_LIVE,
     *  VISITOR_PRIVACY_METADATA, __Secure-YNID, __Secure-ROLLOUT_TOKEN —
     *  all set with the Partitioned attribute) survive every pass of every
     *  wipe and keep the visitor profile (and its recommendations) alive. */
    private void expireCookies(CookieManager cm, String domain, String base) {
        // v1.3.55: music./accounts. joined www./m. — a CHIPS partition key
        // is the EXACT schemeful site of the top-level page, so a copy of
        // the cookie exists per host ever visited top-level and each must
        // be expired through its own URL (getCookie/setCookie scope the
        // partition to the URL's host in WebView: chromium's
        // GetCookieListAsyncHelper/SetCookieHelper derive the key from
        // SchemefulSite(url)).
        String[] hosts = {domain, base, "www." + base, "m." + base,
                "music." + base, "accounts." + base};
        for (String h : hosts) {
            if (h == null || h.isEmpty() || !h.contains(".")) continue;
            for (String scheme : new String[]{"https", "http"}) {
                String u = scheme + "://" + h + "/";
                String cookies = cm.getCookie(u);
                if (cookies == null) continue;
                for (String c : cookies.split(";")) {
                    String name = c.split("=", 2)[0].trim();
                    if (name.isEmpty()) continue;
                    boolean secureOnly = name.startsWith("__Secure-") || name.startsWith("__Host-");
                    boolean hostOnly = name.startsWith("__Host-");
                    String secAttr = (secureOnly || scheme.equals("https")) ? "; Secure" : "";
                    try {
                        // the PLAIN twin — deletes the NON-partitioned copy
                        // (the v1.3.52 machinery that verifiably killed logins)
                        cm.setCookie(u, name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; "
                                + "Max-Age=0; Path=/" + secAttr);
                        if (!hostOnly) {
                            cm.setCookie(u, name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; "
                                    + "Max-Age=0; Path=/; Domain=" + base + secAttr);
                            cm.setCookie(u, name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; "
                                    + "Max-Age=0; Path=/; Domain=." + base + secAttr);
                        }
                        // v1.3.55 — THE CHIPS TWIN. WebView stores cookies set
                        // with the Partitioned attribute PER PARTITION, keyed
                        // by the schemeful site of the top-level page, and
                        // CookieManager.setCookie() only writes into that
                        // partition when the cookie STRING itself carries the
                        // Partitioned attribute — chromium's CanonicalCookie::
                        // Create drops the supplied partition key for strings
                        // without it (canonical_cookie.cc:439), so a plain
                        // expiry cookie is always NON-partitioned and can
                        // never match, let alone delete, the partitioned copy
                        // (the "siteWipe survivors" that carried YouTube's
                        // signed-out visitor identity — YSC,
                        // VISITOR_INFO1_LIVE, VISITOR_PRIVACY_METADATA,
                        // __Secure-YNID, __Secure-ROLLOUT_TOKEN — straight
                        // through every wipe; WebView has partitioned cookies
                        // ON by default — androidx config
                        // getPartitionedCookiesEnabled() defaults true). With
                        // the attribute present, WebView derives the delete's
                        // partition key from THIS URL's own host — which is
                        // why every host variant must be enumerated above. A
                        // Partitioned cookie must also be Secure, so this
                        // twin is emitted on the https scheme only (where
                        // secAttr already carries Secure); on a WebView with
                        // CHIPS disabled the string is simply rejected — a
                        // harmless no-op, the plain twin above still owns
                        // that world.
                        if (scheme.equals("https")) {
                            cm.setCookie(u, name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; "
                                    + "Max-Age=0; Path=/" + secAttr + "; Partitioned");
                            if (!hostOnly) {
                                cm.setCookie(u, name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; "
                                        + "Max-Age=0; Path=/; Domain=" + base + secAttr + "; Partitioned");
                                cm.setCookie(u, name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; "
                                        + "Max-Age=0; Path=/; Domain=." + base + secAttr + "; Partitioned");
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    // ------------------------------------------------------------ omnibox

    /** v1.4.18 — the omnibox Copy target (PURE, probe-pinned per the
     *  v1.4.11 parse-gate law): the field's text when there is any;
     *  otherwise the ACTIVE tab's URL when the tab shows a real page;
     *  null when there is nothing useful to copy (home tab, no URL) —
     *  the caller toasts instead of a silent no-op. */
    static String omniCopyText(String fieldText, String tabUrl, boolean tabIsHome) {
        if (fieldText != null && !fieldText.isEmpty()) return fieldText;
        if (tabIsHome) return null;
        if (tabUrl == null || tabUrl.isEmpty()) return null;
        return tabUrl;
    }

    /** v1.4.19 — PURE (probe-pinned): the engine-chip logo resource for an
     *  engine index (R.array.search_engine_names order: 0 Google,
     *  1 DuckDuckGo, 2 Bing, 3 Brave, 4 Startpage). Brand logos keep their
     *  own colors — the chip NEVER tints them; the generic magnifier (the
     *  custom / unknown fallback) follows the theme tint instead. */
    static int engineLogo(int engineIdx) {
        switch (engineIdx) {
            case 0: return R.drawable.ic_engine_google;
            case 1: return R.drawable.ic_engine_ddg;
            case 2: return R.drawable.ic_engine_bing;
            case 3: return R.drawable.ic_engine_brave;
            case 4: return R.drawable.ic_engine_startpage;
            default: return R.drawable.ic_search;
        }
    }

    /** v1.4.19 — may Bolt AI's ATTACHMENT picker run on this face? Main
     *  process only, never the incognito face: a picked file's read grant
     *  and its bytes must never enter :incognito (the identity-data law);
     *  the result routing also rides this activity's onActivityResult. */
    public boolean aiAttachAllowed() { return !incognito; }

    /** v1.4.19 — PURE (probe-pinned): the label a long-press tooltip shows
     *  — the explicit tooltip when present, the contentDescription otherwise
     *  (every icon-only button carries at least one; the gate audits the
     *  layouts). */
    static String tooltipFor(String contentDesc, String tooltipText) {
        if (tooltipText != null && !tooltipText.isEmpty()) return tooltipText;
        return contentDesc == null ? "" : contentDesc;
    }

    /** v1.4.19 — the programmatic tooltip (androidx TooltipCompat): the
     *  android:tooltipText XML attribute only inflates on API 26+ and
     *  Bolt's minSdk is 24 — this backports the long-press tooltip to
     *  24/25 for buttons with no consuming long-click listener (the
     *  omnibox strip; the toolbar's long-press IS its quick action, which
     *  is why those listeners legitimately consume). */
    protected static void applyTooltip(View v, String label) {
        try { androidx.appcompat.widget.TooltipCompat.setTooltipText(v, label); }
        catch (Throwable ignored) {}
    }

    /** v1.4.19 — set the engine chip's glyph to the ACTIVE engine's logo
     *  (brand marks untinted; the generic fallback keeps the theme tint).
     *  Called when the omnibox opens and again on every engine switch.
     *  v1.4.21 — THE BINDING BREADCRUMB (the "Bing/Brave show the DDG
     *  duck" report): the drawable resource name actually set rides to
     *  Diag on every application, so the next report proves which mark
     *  the device drew per engine (engineLogo's mapping is pure and
     *  probe-pinned; this line is the on-device truth). */
    protected void applyEngineLogo(View lay) {
        try {
            ImageView iv = lay.findViewById(R.id.iv_omni_search);
            if (iv == null) return;
            int logo = engineLogo(prefs.searchEngine());
            iv.setImageResource(logo);
            iv.setColorFilter(logo == R.drawable.ic_search ? Fx.tint(this) : null);
            try {
                com.bolt.browser.util.Diag.log(this, "engine logo set engine="
                        + prefs.searchEngine() + " res="
                        + getResources().getResourceEntryName(logo));
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** v1.3.36 FIELD FIX ("the search bar should stay at top or animate
     *  coming down from top — popping out from the bottom breaks the
     *  immersion"): the omnibox is now a TOP-ANCHORED dropdown — it hangs
     * from the top edge exactly where the toolbar URL field sits (rounded
     * BOTTOM corners), slides DOWN into view like Chrome's address dropdown,
     * and the suggestion list drops below it. With the address bar docked
     * at the bottom (urlBarBottom pref) it anchors to the bottom instead,
     * mirroring the bar it belongs to. */
    protected void showOmnibox() {
        hideQuickPanel();
        ensureBarsShown();   // v1.3.33: bars visible while typing a URL
        android.view.View lay = getLayoutInflater().inflate(R.layout.dialog_omnibox, null);
        final EditText et = lay.findViewById(R.id.et_omni);
        final RecyclerView rv = lay.findViewById(R.id.rv_suggest);
        final boolean topAnchor = !prefs.urlBarBottom();
        // v1.3.36: the sheet hangs from the bar's edge — rounded bottom
        // corners when at top, rounded top corners when at bottom
        lay.setBackground(topAnchor
                ? com.bolt.browser.util.Ui.sheetTopBg(this)
                : com.bolt.browser.util.Ui.sheetBg(this));
        try {
            lay.findViewById(R.id.omni_handle).setVisibility(topAnchor ? View.GONE : View.VISIBLE);
        } catch (Throwable ignored) {}
        // icon tinting
        int dim = Fx.tint(this);
        // v1.4.19 — THE ENGINE LOGO ON THE CHIP: the left glyph is the
        // ACTIVE engine's brand mark (Google / DuckDuckGo / Bing / Brave /
        // Startpage — untinted brand colors; the generic magnifier fallback
        // keeps the theme tint). Re-applied on every engine switch (see
        // showEnginePicker).
        applyEngineLogo(lay);
        try { lay.<ImageView>findViewById(R.id.iv_omni_engine_arrow).setColorFilter(dim); } catch (Throwable ignored) {}
        // v1.4.19 — THE ICON-ONLY TOOLTIP LAW, programmatic half: the XML
        // android:tooltipText only inflates on API 26+; these carry the
        // backport for 24/25 (TooltipCompat)
        applyTooltip(lay.findViewById(R.id.omni_engine), getString(R.string.search_engine));
        applyTooltip(lay.findViewById(R.id.btn_omni_clear), getString(R.string.clear));
        applyTooltip(lay.findViewById(R.id.btn_omni_copy), getString(R.string.copy));
        applyTooltip(lay.findViewById(R.id.btn_omni_paste), getString(R.string.paste));
        applyTooltip(lay.findViewById(R.id.btn_omni_dup), getString(R.string.duplicate_tab));
        applyTooltip(lay.findViewById(R.id.btn_omni_cam), "Image search");
        applyTooltip(lay.findViewById(R.id.btn_omni_voice), "Voice search");
        try { lay.<ImageButton>findViewById(R.id.btn_omni_clear).setColorFilter(dim); } catch (Throwable ignored) {}
        try { lay.<ImageButton>findViewById(R.id.btn_omni_copy).setColorFilter(dim); } catch (Throwable ignored) {}
        try { lay.<ImageButton>findViewById(R.id.btn_omni_paste).setColorFilter(dim); } catch (Throwable ignored) {}
        try { lay.<ImageButton>findViewById(R.id.btn_omni_dup).setColorFilter(dim); } catch (Throwable ignored) {}
        try { lay.<ImageButton>findViewById(R.id.btn_omni_cam).setColorFilter(dim); } catch (Throwable ignored) {}
        // v1.4.12 — THE ENGINE CHIP: the search glyph + drop arrow at the
        // field's left open the engine picker (Google / DuckDuckGo / Bing /
        // Brave / Startpage). A pick applies to the query in the field —
        // see showEnginePicker for the re-run rule.
        try {
            lay.findViewById(R.id.omni_engine).setOnClickListener(v -> {
                Ui.tap(v);
                showEnginePicker(lay, et);
            });
        } catch (Throwable ignored) {}
        // v1.4.12 — THE IMAGE-SEARCH DOOR: the camera glyph at the field's
        // right picks a picture (camera or gallery) and routes it to the
        // selected engine's visual search.
        try {
            lay.findViewById(R.id.btn_omni_cam).setOnClickListener(v -> {
                Ui.tap(v);
                showImageSearchPicker();
            });
        } catch (Throwable ignored) {}
        // v1.3.38 — the omnibox mic (voice search): the transcript drops
        // into the field (editable before GO — the Google-search-bar feel).
        // Hidden when the device has no recognizer at all.
        try {
            android.widget.ImageButton mic = lay.findViewById(R.id.btn_omni_voice);
            if (mic != null) {
                if (!incognito && com.bolt.browser.util.VoiceSearch.available(this)) {
                    mic.setColorFilter(dim);
                    mic.setOnClickListener(v -> {
                        Ui.tap(v);
                        startVoiceSearch(1010);
                    });
                } else {
                    mic.setVisibility(View.GONE);
                }
            }
        } catch (Throwable ignored) {}
        // v1.4.16 — THE TWO-ROW OMNIBOX. The single-row congestion fix is
        // structural now: Row 1 (engine chip | field weight 1 | clear) is
        // all chrome the TEXT needs, and every former trailer icon moved
        // to Row 2 (omni_actions: copy, paste, duplicate, image search,
        // voice search — one evenly weighted 44dp slot each).
        // v1.4.18 re-point (2026-11-19): the strip is NO LONGER text-gated.
        // The v1.4.16 spec ("shown ONLY while the field holds text") hid
        // image search / paste / duplicate / voice behind the FIRST
        // keystroke — the field report: "tap the search box and the action
        // buttons are invisible until I type". The strip now shows the
        // moment the omnibox opens (focus law); only the CLEAR button
        // keeps following the field's empty/typing state (clearing an
        // empty field is a no-op, so it stays hidden while empty).
        final View actionsRow = lay.findViewById(R.id.omni_actions);
        final android.widget.ImageButton clearB = lay.findViewById(R.id.btn_omni_clear);
        final Boolean[] omniEmptyCache = new Boolean[1];
        final Runnable syncOmniIcons = () -> {
            try {
                // v1.4.18 — THE FOCUS LAW: one guaranteed show per open (a
                // cheap visibility read when already visible), then the
                // v1.4.15 empty-cache law keeps driving ONLY the clear
                // button: an EMPTY<->TYPING flip costs one setVisibility,
                // every other keystroke is one array read.
                if (actionsRow != null && actionsRow.getVisibility() != View.VISIBLE) {
                    actionsRow.setVisibility(View.VISIBLE);
                }
                boolean empty = et.getText().length() == 0;
                if (omniEmptyCache[0] != null && omniEmptyCache[0] == empty) return;
                omniEmptyCache[0] = empty;
                if (clearB != null) clearB.setVisibility(empty ? View.GONE : View.VISIBLE);
            } catch (Throwable ignored) {}
        };
        rv.setLayoutManager(new LinearLayoutManager(this));
        // v1.3.36: cap the suggestion list so the window never grows past the
        // keyboard line (top-anchored + ADJUST_RESIZE would otherwise clip)
        try { rv.getLayoutParams().height = Fx.dp(this, 296); } catch (Throwable ignored) {}
        final SuggestAdapter ad = new SuggestAdapter(et);
        rv.setAdapter(ad);
        et.setText(activeTab().isHome ? "" : activeTab().url);
        // select-all so typing instantly replaces the old URL
        et.selectAll();
        et.requestFocus();
        activeOmniField = et;   // v1.3.38: the voice result fills this field
        // paste from clipboard
        lay.findViewById(R.id.btn_omni_paste).setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && cm.getPrimaryClip() != null
                        && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                    et.setText(t);
                    et.setSelection(et.getText().length());
                } else {
                    Fx.toast(this, "Clipboard is empty");
                }
            } catch (Throwable ignored) {}
        });
        // copy current field
        // v1.4.18 — EMPTY-FIELD COPY (the focus law's sibling): the strip
        // is up before typing now, so Copy must do something useful
        // there — an empty field copies the ACTIVE TAB'S URL (the user
        // cleared the field but still wants the address); a home tab has
        // nothing to copy and says so instead of silently doing nothing.
        lay.findViewById(R.id.btn_omni_copy).setOnClickListener(v -> {
            try {
                Tab cur = activeTab();
                String txt = omniCopyText(et.getText().toString(),
                        cur == null ? null : cur.url, cur == null || cur.isHome);
                if (txt == null) {
                    Fx.toast(this, "Nothing to copy");
                    return;
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Bolt", txt));
                Fx.toast(this, getString(R.string.copied));
            } catch (Throwable ignored) {}
        });
        // clear field
        lay.findViewById(R.id.btn_omni_clear).setOnClickListener(v -> {
            et.setText("");
            et.requestFocus();
        });
        // v1.4.16 — DUPLICATE TAB (Row 2): the switcher's per-card duplicate
        // (same URL in a fresh tab inserted right beside the source) promoted
        // to the omnibox action strip. Dismisses the sheet — the action is
        // done, the typing flow is over.
        lay.findViewById(R.id.btn_omni_dup).setOnClickListener(v -> {
            Ui.tap(v);
            try {
                Tab cur = activeTab();
                if (cur != null) {
                    Tab n = new Tab();
                    n.isHome = cur.isHome;
                    n.url = cur.url;
                    n.title = cur.title;
                    int idx = tm.tabs.indexOf(cur);
                    tm.tabs.add(idx >= 0 ? idx + 1 : tm.tabs.size(), n);
                    tm.save();
                    Fx.toast(this, getString(R.string.duplicate_tab));
                }
            } catch (Throwable ignored) {}
            omniboxDismiss(lay);
        });
        et.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                syncOmniIcons.run();   // v1.4.14: the icon strip follows the field's state
                ad.update(s.toString());
                scheduleNetSuggest(ad, s.toString());   // v1.4.13: the debounced engine pass
            }
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
        });
        // the OPENING state: the field was prefilled BEFORE the watcher went
        // on (no suggest pass for a pre-existing URL), so the icon strip is
        // synced once here — prefilled URL = typing state, home tab = empty
        syncOmniIcons.run();
        et.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadInTab(et.getText().toString());
                omniboxDismiss(lay);
                return true;
            }
            return false;
        });
        final android.app.Dialog d = new android.app.Dialog(this);
        d.setContentView(lay);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout(-1, -2);
            w.setGravity(topAnchor ? Gravity.TOP : Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
        omniboxDialog = d;
        ad.setDialog(d);
        // v1.3.36: the immersion fix — slide the sheet DOWN from the top edge
        // (or UP from the bottom when the bar lives there), like the toolbar
        // itself grew a dropdown.
        try {
            float from = topAnchor ? -Fx.dp(this, 72) : Fx.dp(this, 72);
            lay.setTranslationY(from);
            lay.setAlpha(0f);
            lay.animate().translationY(0f).alpha(1f).setDuration(220L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        } catch (Throwable ignored) {}
        et.postDelayed(() -> {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, 0);
            } catch (Throwable ignored) {}
        }, 100);
    }

    /**
     * v1.4.12 — THE ENGINE PICKER: a bottom sheet over the omnibox (the two
     * anchor at opposite screen edges, so they stack cleanly). A pick sets
     * the default engine — the same pref Settings owns, so the switch sticks
     * everywhere — and when the field holds a live QUERY (not the prefilled
     * URL of the open page, not something URL-shaped), that query re-runs
     * through the newly picked engine immediately: "switch engines for the
     * current query" in one tap.
     */
    protected void showEnginePicker(View lay, EditText et) {
        final String[] names = getResources().getStringArray(R.array.search_engine_names);
        Ui.choiceSheet(this, getString(R.string.search_engine), names,
                prefs.searchEngine(), idx -> {
                    try { prefs.set("searchEngine", idx); } catch (Throwable ignored) {}
                    // v1.4.19 — the chip's logo follows the switch
                    // immediately (the active engine is visible in the
                    // field the user is still typing into)
                    try { applyEngineLogo(lay); } catch (Throwable ignored) {}
                    String q = et == null ? "" : et.getText().toString().trim();
                    String cur = "";
                    try {
                        Tab at = activeTab();
                        cur = at == null || at.url == null ? "" : at.url;
                    } catch (Throwable ignored) {}
                    boolean urlish = q.startsWith("http") || q.contains("://")
                            || q.startsWith("www.") || q.equals(cur)
                            || q.matches("(?i)[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?");
                    String name = names[Math.max(0, Math.min(names.length - 1, idx))];
                    if (!q.isEmpty() && !urlish) {
                        loadInTab(q);            // the current query, new engine
                        omniboxDismiss(lay);
                    } else {
                        Fx.toast(this, getString(R.string.search_engine) + ": " + name);
                    }
                });
    }

    /**
     * v1.4.12 — THE IMAGE-SEARCH DOOR: camera or gallery; the picture then
     * routes to the selected engine's visual-search endpoint (see
     * VisualSearch for the per-engine mapping — engines without an upload
     * endpoint of their own ride their results provider: DDG/Brave via
     * Bing, Startpage via Google).
     */
    protected void showImageSearchPicker() {
        Ui.choiceSheet(this, "Image search",
                new String[]{"Take photo", "Choose image"}, -1, idx -> {
                    if (idx == 0) {
                        if (ContextCompat.checkSelfPermission(BrowserActivity.this,
                                Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                            launchVisualCamera();
                        } else {
                            ActivityCompat.requestPermissions(BrowserActivity.this,
                                    new String[]{Manifest.permission.CAMERA}, 1023);
                        }
                    } else {
                        try {
                            Intent gi = new Intent(Intent.ACTION_GET_CONTENT);
                            gi.addCategory(Intent.CATEGORY_OPENABLE);
                            gi.setType("image/*");
                            startActivityForResult(gi, 1022);
                        } catch (Throwable t) { Fx.toast(this, "No gallery app"); }
                    }
                });
    }

    /** full-res capture needs EXTRA_OUTPUT through the FileProvider (the
     *  data-extra path returns a ~thumbnail bitmap). The capture lands in
     *  the share dir the QR png already rides. */
    protected void launchVisualCamera() {
        try {
            File dir = new File(getFilesDir(), "shared");
            if (!dir.isDirectory()) dir.mkdirs();
            File f = new File(dir, "vsearch-capture.jpg");
            if (f.exists()) f.delete();
            vsearchCapture = f;
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    "com.bolt.browser.fileprovider", f);
            Intent ci = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            ci.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            ci.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(ci, 1021);
        } catch (Throwable t) { Fx.toast(this, "Camera unavailable"); }
    }

    /** upload the picked/captured picture and load the engine's answer. */
    protected void runVisualSearch(File imageFile) {
        int ei = prefs.searchEngine();
        String[] names = getResources().getStringArray(R.array.search_engine_names);
        String name = names[Math.max(0, Math.min(names.length - 1, ei))];
        final com.bolt.browser.util.VisualSearch.Endpoint ep =
                com.bolt.browser.util.VisualSearch.endpointFor(ei);
        // v1.4.20 — the single-route toast: EVERY engine rides Google's
        // search-by-image (the field-tested cookie/UA-continuity door); the
        // toast names the route actually used, exactly as the approval
        // asked, so a Bing/DDG/Brave pick never looks like it uploaded
        // somewhere it did not.
        Fx.toast(this, ep.via == null
                ? "Visual search: " + name
                : "Via " + ep.via + " — " + name + " image search rides Google");
        com.bolt.browser.util.VisualSearch.search(this, ei, imageFile, url -> {
            if (url != null) {
                try { loadInTab(url); } catch (Throwable ignored) {}
            } else {
                Fx.toast(BrowserActivity.this, "Upload failed — manual image search");
                try { loadInTab(ep.home); } catch (Throwable ignored) {}
            }
            try { imageFile.delete(); } catch (Throwable ignored) {}
        });
    }

    protected void omniboxDismiss(View lay) {
        hideKeyboard();
        // v1.4.13: a dismissed omnibox kills its pending suggestion request
        // too — the debounce timer never fires into a dead dialog
        if (suggestPending != null) { suggestH.removeCallbacks(suggestPending); suggestPending = null; }
        try {
            if (omniboxDialog != null && omniboxDialog.isShowing()) {
                // v1.3.36: retract the way it came in — a quick slide back to
                               // the edge, then the window goes
                final android.app.Dialog d = omniboxDialog;
                final boolean topAnchor = !prefs.urlBarBottom();
                try {
                    if (lay != null) {
                        lay.animate().translationY(topAnchor ? -Fx.dp(this, 72) : Fx.dp(this, 72))
                                .alpha(0f).setDuration(140L)
                                .withEndAction(d::dismiss).start();
                        ui.postDelayed(d::dismiss, 260L);   // safety net
                        return;
                    }
                } catch (Throwable ignored) {}
                d.dismiss();
            }
        } catch (Throwable ignored) {}
    }

    protected android.app.Dialog omniboxDialog;

    // ------------------------------------------------------------ v1.4.13
    // THE OMNIBOX NETWORK DEBOUNCE. The report: "the omnibox fires a
    // network request for search suggestions on every single keystroke,
    // causing network flooding and UI stutter." Every keystroke RESETS a
    // 300 ms timer (removeCallbacks BEFORE postDelayed — the debounce
    // law: a new keystroke must kill the pending request, never stack
    // behind it), so only a typing PAUSE becomes a request. The local
    // rows (tabs/bookmarks/history/instant answers) still re-filter on
    // EVERY keystroke — memory-resident, but v1.4.15 moved their build
    // off the main thread (SUGGEST_BUILD below); the NETWORK stays
    // debounced AND the build stays off-thread — two separate doors.
    private final Handler suggestH = new Handler(Looper.getMainLooper());
    private Runnable suggestPending;

    // v1.4.15 — THE LOCAL-SUGGEST BUILD THREAD. v1.4.13 debounced only the
    // NETWORK pass; the local pass (bookmarks + speed-dial + the 400-entry
    // history walk, every entry string-matched) still ran INSIDE
    // afterTextChanged — synchronous JSON-array walking on the main thread,
    // per keystroke, which is the "typing lag" arithmetic. The build now runs
    // on ONE process-wide daemon thread (BrowserActivity exists in at most
    // one process at a time, so one idle thread is the whole footprint).
    // Every update() call — TextWatcher keystrokes and the debounced
    // setWebSuggestions refresh, all main-thread — bumps suggestGen and
    // carries the bump into the build; the apply pass (items swap +
    // notifyDataSetChanged, posted through the ACTIVITY's ui handler so
    // onDestroy's existing removeCallbacksAndMessages kills any still-queued
    // one) lands only when its generation is still the newest. Stale builds
    // still cost their walk but can never reach the screen. Until a fresh
    // pass lands the PREVIOUS rows stay up (no flash of emptiness mid-word).
    private static final java.util.concurrent.ExecutorService SUGGEST_BUILD =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bolt-suggest");
                t.setDaemon(true);
                return t;
            });
    /** main-thread-confined: bumped by every update(), compared by every apply. */
    private int suggestGen = 0;

    /** park [q] behind the 300 ms typing pause; called on every keystroke. */
    protected void scheduleNetSuggest(SuggestAdapter ad, String q) {
        if (suggestPending != null) suggestH.removeCallbacks(suggestPending);
        final String query = q;
        suggestPending = () -> {
            suggestPending = null;
            fireNetSuggest(ad, query);
        };
        suggestH.postDelayed(suggestPending, 300L);
    }

    /** the pause elapsed — NOW the one request may fire (and only if the
     *  privacy doors all open). */
    private void fireNetSuggest(SuggestAdapter ad, String q) {
        try {
            if (incognito) return;                        // privacy law: never in private mode
            if (!prefs.suggestNetwork()) return;          // the settings off door
            String t = q == null ? "" : q.trim();
            if (t.isEmpty() || t.startsWith("!")) return; // bangs route through custom engines
            boolean urlish = t.startsWith("http") || t.contains("://")
                    || t.startsWith("www.")
                    || t.matches("(?i)[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?");
            if (urlish) return;                            // URLs get no suggestions
            final String query = t;
            com.bolt.browser.util.NetSuggest.fetch(prefs.searchEngine(), query,
                    rows -> {
                        // the stale guard: only re-filter when the field
                        // still holds the query these rows answer
                        try {
                            if (ad == null) return;
                            String now = ad.currentQuery() == null
                                    ? "" : ad.currentQuery().trim();
                            if (!query.equals(now)) return;
                            ad.setWebSuggestions(query, rows);
                        } catch (Throwable ignored) {}
                    });
        } catch (Throwable ignored) {}
    }

    protected class SuggestAdapter extends RecyclerView.Adapter<SuggestAdapter.H> {
        private final List<Object[]> items = new ArrayList<>(); // {icon, title, sub, action}
        private android.app.Dialog dialog;
        // v1.3.95 — the omnibox field this list serves (the FILL button
        // edits it in place — the dialog stays open, the list re-filters)
        private final EditText field;

        SuggestAdapter(EditText field) { this.field = field; }

        void setDialog(android.app.Dialog d) { dialog = d; }

        // v1.4.13 — THE DEBOUNCED ENGINE ROWS (NetSuggest): the query they
        // answer is carried WITH them (webQuery) — a later keystroke that
        // changed the field never shows the previous pause's rows.
        private String webQuery = "";
        private List<String> webRows = new ArrayList<>();

        /** what the field holds right now (the stale-response guard reads it). */
        String currentQuery() {
            try { return field.getText().toString(); } catch (Throwable t) { return ""; }
        }

        /** the debounced network answer landed — re-filter with the rows
         *  attached (only called when the field still holds [query]). */
        void setWebSuggestions(String query, List<String> rows) {
            webQuery = query == null ? "" : query;
            webRows = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
            update(currentQuery());
        }

        /** v1.3.36 — the suggestion pass: OPEN TAB matches first (the "switch
         *  to tab" rows Chrome shows), then history, then bookmarks, then the
         *  search row. "action" is either an URL, "search:q", or "tab:idx".
         *  v1.4.15 — THE OFF-THREAD BUILD: update() is now a DISPATCHER. The
         *  row build (the JSON walks) runs on SUGGEST_BUILD; the apply (items
         *  swap + notify) posts back through the activity's ui handler and
         *  lands only if its generation is still the newest. Old rows stay on
         *  screen until the fresh pass replaces them — smoother than the old
         *  clear-first flash of emptiness. */
        void update(String q) {
            final String query = q == null ? "" : q;
            final int gen = ++suggestGen;          // main-thread only (see field)
            final String wq = webQuery;            // snapshot: written on main
            final List<String> wr = webRows;
            try {
                SUGGEST_BUILD.execute(() -> {
                    try {
                        final List<Object[]> fresh = buildRows(query, wq, wr);
                        ui.post(() -> {
                            try {
                                if (gen != suggestGen) return;   // stale guard
                                items.clear();
                                items.addAll(fresh);
                                notifyDataSetChanged();
                            } catch (Throwable ignored) {}
                        });
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}   // executor rejected (process dying)
        }

        /** the row build, pure except for reads (prefs snapshots, tm under its
         *  monitor, resources) — safe off the main thread by design. */
        private List<Object[]> buildRows(String q, String wq, List<String> wr) {
            List<Object[]> out = new ArrayList<>();
            String ql = q.toLowerCase();
            // 0) v1.3.39 INSTANT ANSWER (menu-3 #2): math and unit queries
            // answer LOCALLY — "12*34", "100kg in lbs", "2h30m in minutes".
            // Tap copies the result.
            try {
                String ans = com.bolt.browser.util.InstantAnswers.answer(q);
                if (ans != null) {
                    items.add(new Object[]{R.drawable.ic_calc, "= " + ans,
                            "Instant answer — tap to copy", "copy:" + ans});
                }
            } catch (Throwable ignored) {}
            // 1) open tabs — switching beats reloading
            try {
                synchronized (tm.tabs) {   // v1.4.11: the thread law — size()+get() as one step
                    for (int i = 0; i < tm.tabs.size() && out.size() < 3; i++) {
                        Tab t = tm.tabs.get(i);
                        if (t == tm.current()) continue;
                        String title = t.isHome ? getString(R.string.home)
                                : (t.title == null ? "" : t.title);
                        String url = t.url == null ? "" : t.url;
                        if (!ql.isEmpty() && (title.toLowerCase().contains(ql)
                                || url.toLowerCase().contains(ql))) {
                            out.add(new Object[]{R.drawable.ic_tabcount,
                                    title.isEmpty() ? url : title,
                                    getString(R.string.switch_to_tab), "tab:" + i});
                        }
                    }
                }
            } catch (Throwable ignored) {}
            // 2-3) v1.4.2 — THE LOCAL-FIRST SUGGESTION ORDER (the field
            // ask: "when searching, keep the options from bookmarks and
            // shortcuts as a higher priority recommendation when typing
            // to search"): with suggestLocalFirst ON, BOOKMARKS and
            // HOME-SCREEN SHORTCUTS (the speed dial — folder entries
            // included, the first time shortcuts suggest at all) rank
            // ABOVE history — what you deliberately saved beats what you
            // merely visited. OFF restores the classic order (history
            // first, bookmarks after, no shortcut rows). Open tabs keep
            // the very top either way (switching beats reloading).
            boolean localFirst = prefs.suggestLocalFirst();
            if (localFirst) {
                // 2a) bookmarks — the curated shelf
                org.json.JSONArray bm = prefs.bookmarks();
                int bmFound = 0;
                for (int i = 0; i < bm.length() && bmFound < 4; i++) {
                    org.json.JSONObject o = bm.optJSONObject(i);
                    if (o == null) continue;
                    String u = o.optString("url");
                    String t = o.optString("title");
                    if (u.toLowerCase().contains(ql) || t.toLowerCase().contains(ql)) {
                        out.add(new Object[]{R.drawable.ic_bookmark, t.isEmpty() ? u : t, u, u});
                        bmFound++;
                    }
                }
                // 2b) shortcuts — the speed dial, folders dug through
                try {
                    org.json.JSONArray sd = prefs.speeddial();
                    int sdFound = 0;
                    for (int i = 0; i < sd.length() && sdFound < 3; i++) {
                        org.json.JSONObject o = sd.optJSONObject(i);
                        if (o == null) continue;
                        if (o.optBoolean("group", false)) {
                            org.json.JSONArray inner = o.optJSONArray("items");
                            if (inner == null) continue;
                            for (int j = 0; j < inner.length() && sdFound < 3; j++) {
                                org.json.JSONObject s = inner.optJSONObject(j);
                                if (s == null) continue;
                                String u = s.optString("url");
                                String t = s.optString("title");
                                if (u.toLowerCase().contains(ql) || t.toLowerCase().contains(ql)) {
                                    out.add(new Object[]{R.drawable.ic_home,
                                            t.isEmpty() ? u : t,
                                            getString(R.string.shortcut_in, o.optString("name")), u});
                                    sdFound++;
                                }
                            }
                        } else {
                            String u = o.optString("url");
                            String t = o.optString("title");
                            if (u.toLowerCase().contains(ql) || t.toLowerCase().contains(ql)) {
                                out.add(new Object[]{R.drawable.ic_home,
                                        t.isEmpty() ? u : t,
                                        getString(R.string.home_shortcut), u});
                                sdFound++;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            // 2c) history — v1.3.79: capped to the newest 400 entries. A
            // no-match keystroke used to walk all 2000 (two allocations per
            // entry, on the main thread, per keystroke) which is exactly
            // the "typing in the address bar feels sluggish" arithmetic
            org.json.JSONArray hist = prefs.history();
            int found = 0;
            int histCap = localFirst ? 3 : 4;
            int histFloor = Math.max(0, hist.length() - 400);
            for (int i = hist.length() - 1; i >= histFloor && found < histCap; i--) {
                org.json.JSONObject o = hist.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url");
                String t = o.optString("title");
                if (u.toLowerCase().contains(ql) || t.toLowerCase().contains(ql)) {
                    out.add(new Object[]{R.drawable.ic_history, t.isEmpty() ? u : t, u, u});
                    found++;
                }
            }
            // 3) bookmarks in the CLASSIC order (local-first OFF) — with ON
            // they already ranked above history at 2a
            if (!localFirst) {
                org.json.JSONArray bm = prefs.bookmarks();
                for (int i = 0; i < bm.length() && out.size() < 8; i++) {
                    org.json.JSONObject o = bm.optJSONObject(i);
                    if (o == null) continue;
                    String u = o.optString("url");
                    String t = o.optString("title");
                    if (u.toLowerCase().contains(ql) || t.toLowerCase().contains(ql)) {
                        out.add(new Object[]{R.drawable.ic_bookmark, t.isEmpty() ? u : t, u, u});
                    }
                }
            }
            // 4) search row (always present)
            // v1.3.39 CUSTOM ENGINES + BANGS (menu-3 #6): "!w cats" routes to
            // the engine carrying the bang; the row names it so you see the
            // route before you press go.
            String bangUrl = com.bolt.browser.util.Engines.resolveBang(BrowserActivity.this, q);
            String[] names = getResources().getStringArray(R.array.search_engine_names);
            String eng = names[Math.max(0, Math.min(names.length - 1, prefs.searchEngine()))];
            if (bangUrl != null) {
                out.add(new Object[]{R.drawable.ic_search,
                        getString(R.string.search_with, eng) + ": " + q,
                        "custom engine · " + bangUrl, bangUrl});
            } else {
                out.add(new Object[]{R.drawable.ic_search,
                        getString(R.string.search_with, eng) + ": " + q, q, "search:" + q});
            }
            // 5) v1.4.13 — THE DEBOUNCED ENGINE ROWS: up to five completions
            // from the selected engine, shown ONLY while the field still
            // holds the exact query they answer (webQuery — a keystroke
            // past the pause hides them until the next pause's answer).
            // They arrive via setWebSuggestions, never a keystroke.
            if (!wr.isEmpty() && q.trim().equalsIgnoreCase(wq)) {
                for (String s : wr) {
                    if (out.size() >= 15) break;
                    out.add(new Object[]{R.drawable.ic_search, s,
                            getString(R.string.search_with, eng), "search:" + s});
                }
            }
            return out;
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new H(getLayoutInflater().inflate(R.layout.item_suggest, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int pos) {
            final Object[] it = items.get(pos);
            h.icon.setImageResource((int) it[0]);
            h.icon.setColorFilter(Fx.dim(h.itemView.getContext()));
            h.title.setText((String) it[1]);
            h.sub.setText((String) it[2]);
            // v1.3.95 — THE FILL BUTTON: rows that carry a location (a
            // history/bookmark URL, an open tab's URL) get the pencil; a
            // past SEARCH row fills its decoded QUERY ("how to make rice"),
            // anything else fills the URL. Rows with nothing to fill (the
            // search row, instant answers, copy rows) keep the chevron.
            final String action = (String) it[3];
            String fillText = null;
            if (action != null && !action.startsWith("search:")
                    && !action.startsWith("copy:")) {
                String u = action;
                if (action.startsWith("tab:")) {
                    try {
                        int ti = Integer.parseInt(action.substring(4));
                        Tab tt = ti >= 0 && ti < tm.tabs.size() ? tm.tabs.get(ti) : null;
                        u = (tt == null || tt.isHome) ? null : tt.url;
                    } catch (Throwable t) { u = null; }
                }
                if (u != null && u.startsWith("http")) {
                    String q = com.bolt.browser.util.Engines.queryOf(
                            BrowserActivity.this, u);
                    fillText = q != null ? q : u;
                }
            }
            final String fill = fillText;
            if (fill != null && h.fill != null && h.chev != null) {
                h.fill.setVisibility(View.VISIBLE);
                h.chev.setVisibility(View.GONE);
                h.fill.setOnClickListener(v -> {
                    com.bolt.browser.util.Ui.tap(v);
                    try {
                        field.setText(fill);
                        field.setSelection(fill.length());
                        field.requestFocus();
                        // the TextWatcher re-filters the list on the new
                        // text; the dialog STAYS OPEN — the whole point is
                        // to keep editing ("add the ball only")
                    } catch (Throwable ignored) {}
                });
            } else {
                if (h.fill != null) h.fill.setVisibility(View.GONE);
                if (h.chev != null) h.chev.setVisibility(View.VISIBLE);
                if (h.fill != null) h.fill.setOnClickListener(null);
            }
            h.itemView.setOnClickListener(v -> {
                com.bolt.browser.util.Ui.tap(v);
                String u = (String) it[3];
                if (u.startsWith("search:")) loadInTab(u.substring(7));
                else if (u.startsWith("copy:")) {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Bolt", u.substring(5)));
                        Fx.toast(BrowserActivity.this, getString(R.string.copied));
                    } catch (Throwable ignored) {}
                }
                else if (u.startsWith("tab:")) {
                    // v1.3.36: switch to the already-open tab
                    try {
                        int idx = Integer.parseInt(u.substring(4));
                        if (idx >= 0 && idx < tm.tabs.size()) showTab(idx);
                    } catch (Throwable ignored) {}
                }
                else loadInTab(u);
                if (dialog != null) dialog.dismiss();
                hideKeyboard();
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        class H extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView title, sub;
            android.widget.ImageButton fill;   // v1.3.95 — the fill button
            View chev;                          // v1.3.95 — the chevron it replaces
            H(View v) {
                super(v);
                icon = v.findViewById(R.id.iv_sug_icon);
                title = v.findViewById(R.id.tv_sug_title);
                sub = v.findViewById(R.id.tv_sug_sub);
                fill = v.findViewById(R.id.btn_sug_fill);
                chev = v.findViewById(R.id.iv_sug_chevron);
                try { if (fill != null) fill.setColorFilter(Fx.dim(v.getContext())); }
                catch (Throwable ignored) {}
            }
        }
    }

    // ------------------------------------------------------------ actions

    protected void sharePage() {
        Tab t = activeTab();
        String share = t.url == null ? "" : t.url;
        // v1.3.40 — shared links leave clean (round-4 #4): utm_*/fbclid/
        // gclid… stripped so the recipient gets the plain article URL
        try {
            if (com.bolt.browser.util.UrlClean.enabled(this, share))
                share = com.bolt.browser.util.UrlClean.clean(share);
        } catch (Throwable ignored) {}
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, share);
        i.putExtra(Intent.EXTRA_SUBJECT, t.title);
        startActivity(Intent.createChooser(i, getString(R.string.share)));
    }

    protected void printPage() {
        Tab t = activeTab();
        if (t.web == null) return;
        try {
            android.print.PrintManager pm = (android.print.PrintManager) getSystemService(Context.PRINT_SERVICE);
            pm.print("Bolt", t.web.createPrintDocumentAdapter(t.title),
                    new android.print.PrintAttributes.Builder().build());
        } catch (Throwable e) {
            Fx.toast(this, "Print failed");
        }
    }

    protected void addBookmark() {
        Tab t = activeTab();
        if (t.url == null || t.url.isEmpty() || t.isHome) {
            Fx.toast(this, "Open a page first");
            return;
        }
        int txt = Fx.txt(this);
        int dim = Fx.dim(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 20);
        wrap.setPadding(p, p / 2, p, 0);
        final EditText etTitle = new EditText(this);
        etTitle.setText(t.title == null ? "" : t.title);
        etTitle.setTextColor(txt);
        etTitle.setHint(R.string.name);
        wrap.addView(etTitle, new LinearLayout.LayoutParams(-1, -2));
        final EditText etUrl = new EditText(this);
        etUrl.setText(t.url);
        etUrl.setTextColor(txt);
        wrap.addView(etUrl, new LinearLayout.LayoutParams(-1, -2));
        // folder row: tap to pick which folder the bookmark goes into
        LinearLayout frow = new LinearLayout(this);
        frow.setOrientation(LinearLayout.HORIZONTAL);
        frow.setGravity(Gravity.CENTER_VERTICAL);
        frow.setPadding(0, Fx.dp(this, 6), 0, 0);
        TextView fl = new TextView(this);
        fl.setText(R.string.folder);
        fl.setTextColor(dim);
        fl.setTextSize(14);
        frow.addView(fl, new LinearLayout.LayoutParams(-2, -2));
        final String[] chosen = {""};
        final TextView fname = new TextView(this);
        fname.setText(R.string.root_folder);
        fname.setTextColor(Fx.accentText(this));
        fname.setTextSize(14);
        fname.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        fname.setPadding(Fx.dp(this, 8), 0, Fx.dp(this, 8), 0);
        frow.addView(fname, new LinearLayout.LayoutParams(0, -2, 1f));
        frow.setOnClickListener(v -> {
            final List<String> folders = prefs.bookmarkFolders();
            List<String> opts = new ArrayList<>();
            opts.add(getString(R.string.root_folder));
            opts.addAll(folders);
            opts.add(getString(R.string.new_folder));
            new AlertDialog.Builder(BrowserActivity.this)
                    .setTitle(R.string.folder)
                    .setItems(opts.toArray(new String[0]), (d2, w2) -> {
                        if (w2 == opts.size() - 1) {
                            final EditText et = new EditText(BrowserActivity.this);
                            new AlertDialog.Builder(BrowserActivity.this)
                                    .setTitle(R.string.new_folder)
                                    .setView(et)
                                    .setPositiveButton(R.string.add, (d3, w3) -> {
                                        chosen[0] = et.getText().toString().trim();
                                        fname.setText(chosen[0]);
                                    })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                        } else if (w2 == 0) {
                            chosen[0] = "";
                            fname.setText(R.string.root_folder);
                        } else {
                            chosen[0] = folders.get(w2 - 1);
                            fname.setText(chosen[0]);
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        wrap.addView(frow, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_bookmark)
                .setView(wrap)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String title2 = etTitle.getText().toString().trim();
                    String url2 = etUrl.getText().toString().trim();
                    if (title2.isEmpty()) title2 = Fx.hostOf(url2);
                    prefs.addBookmark(title2, url2, chosen[0]);
                    Fx.toast(this, getString(R.string.bookmark_added));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    protected void saveOffline() {
        Tab t = activeTab();
        if (t.web == null || t.isHome || t.url == null || t.url.isEmpty()) {
            Fx.toast(this, "Open a page first");
            return;
        }
        int dim = Fx.dim(this);
        int txt = Fx.txt(this);
        String name = (t.title == null || t.title.isEmpty()
                ? Fx.hostOf(t.url) : t.title)
                .replaceAll("[^a-zA-Z0-9 .-]", "_");
        if (name.length() > 40) name = name.substring(0, 40);
        if (!name.toLowerCase().endsWith(".mht")) name = name + ".mht";
        final String defName = name;

        // confirm + rename + choose destination (in-app offline pages or SAF folder)
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 20);
        wrap.setPadding(p, p / 2, p, 0);
        final EditText etName = new EditText(this);
        etName.setText(name);
        etName.setTextColor(txt);
        etName.setSingleLine(true);
        wrap.addView(etName, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout dest = new LinearLayout(this);
        dest.setOrientation(LinearLayout.HORIZONTAL);
        dest.setGravity(Gravity.CENTER_VERTICAL);
        dest.setPadding(0, Fx.dp(this, 6), 0, 0);
        TextView dl = new TextView(this);
        dl.setText(R.string.save_to);
        dl.setTextColor(dim);
        dl.setTextSize(14);
        dest.addView(dl, new LinearLayout.LayoutParams(-2, -2));
        dlDestLabel = new TextView(this);
        dlDestLabel.setTextColor(Fx.accentText(this));
        dlDestLabel.setTextSize(14);
        dlDestLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        dlDestLabel.setSingleLine(true);
        dlDestLabel.setPadding(Fx.dp(this, 8), 0, Fx.dp(this, 8), 0);
        // v1.3.9: saved pages use their OWN folder (independent from downloads)
        String cur = prefs.savedPagesDir();
        dlDestLabel.setText(cur.isEmpty() ? getString(R.string.offline_in_app) : safFolderName(cur));
        dest.addView(dlDestLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView change = new TextView(this);
        change.setText(R.string.change);
        change.setTextColor(Fx.accentText(this));
        change.setTextSize(14);
        dest.addView(change, new LinearLayout.LayoutParams(-2, -2));
        TextView reset = new TextView(this);
        reset.setText(R.string.reset);
        reset.setTextColor(dim);
        reset.setTextSize(14);
        reset.setPadding(Fx.dp(this, 10), 0, 0, 0);
        dest.addView(reset, new LinearLayout.LayoutParams(-2, -2));
        change.setOnClickListener(v -> pickSavedPagesFolder());
        dest.setOnClickListener(v -> pickSavedPagesFolder());
        reset.setOnClickListener(v -> {
            prefs.setSavedPagesDir("");
            dlDestLabel.setText(getString(R.string.offline_in_app));
        });
        wrap.addView(dest, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle(R.string.save_page_offline)
                .setView(wrap)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String nm = etName.getText().toString().trim();
                    if (nm.isEmpty()) nm = defName;
                    final String fn = nm;
                    final File tmp = new File(getCacheDir(),
                            "offline-" + System.currentTimeMillis() + ".mht");
                    try {
                        if (Build.VERSION.SDK_INT >= 26) {
                            t.web.saveWebArchive(tmp.getAbsolutePath(), false,
                                    path -> finishOfflineArchive(path, fn));
                        } else {
                            t.web.saveWebArchive(tmp.getAbsolutePath());
                            finishOfflineArchive(tmp.getAbsolutePath(), fn);
                        }
                    } catch (Throwable e) {
                        Fx.toast(this, "Save failed");
                    }
                    dlDestLabel = null;
                })
                .setNegativeButton(R.string.cancel, (d, w) -> dlDestLabel = null)
                .setOnCancelListener(d -> dlDestLabel = null)
                .show();
    }

    /** SAF folder picker for saved pages (independent from the downloads one) */
    protected void pickSavedPagesFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, 1004);
        } catch (Throwable e) {
            Fx.toast(this, "No file picker available");
        }
    }

    /** move a finished web archive into the saved-pages SAF folder (or the in-app offline dir) */
    protected void finishOfflineArchive(final String tmpPath, final String name) {
        new Thread(() -> {
            try {
                // v1.3.9: saved pages NEVER go to the downloads folder — they use
                // their own savedPagesDir (or the in-app dir), so they always
                // show up in the Saved pages list.
                String dd = prefs.savedPagesDir();
                boolean toSaf = Build.VERSION.SDK_INT >= 29 && dd != null && !dd.isEmpty();
                if (toSaf) {
                    try {
                        Uri tree = Uri.parse(dd);
                        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree,
                                DocumentsContract.getTreeDocumentId(tree));
                        Uri target = DocumentsContract.createDocument(
                                getContentResolver(), doc, "application/x-mht", name);
                        if (target != null) {
                            try (java.io.InputStream in = new java.io.FileInputStream(tmpPath);
                                 java.io.OutputStream out =
                                         getContentResolver().openOutputStream(target, "wt")) {
                                byte[] buf = new byte[8192];
                                int n;
                                while (out != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                            }
                            ui.post(() -> Fx.toast(BrowserActivity.this,
                                    getString(R.string.saved_to) + ": " + name));
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
                File dir = prefs.offlineDir();
                if (!dir.exists()) dir.mkdirs();
                File outF = new File(dir, name);
                try (java.io.InputStream in = new java.io.FileInputStream(tmpPath);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(outF)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                ui.post(() -> Fx.toast(BrowserActivity.this, getString(R.string.saved_offline)));
            } catch (Throwable e) {
                ui.post(() -> Fx.toast(BrowserActivity.this, "Save failed"));
            } finally {
                try { new File(tmpPath).delete(); } catch (Throwable ignored) {}
            }
        }, "offsave").start();
    }

    protected void saveOfflineLegacy() {
        Tab t = activeTab();
        if (t.web == null || t.isHome || t.url == null || t.url.isEmpty()) return;
        try {
            File dir = prefs.offlineDir();
            if (!dir.exists()) dir.mkdirs();
            String name = (t.title == null || t.title.isEmpty()
                    ? Fx.hostOf(t.url) : t.title)
                    .replaceAll("[^a-zA-Z0-9 .-]", "_");
            if (name.length() > 40) name = name.substring(0, 40);
            final File out = new File(dir, name + "-" + System.currentTimeMillis() + ".mht");
            if (Build.VERSION.SDK_INT >= 26) {
                t.web.saveWebArchive(out.getAbsolutePath(), false, path ->
                        Fx.toast(this, getString(R.string.saved_offline) + ": " + out.getName()));
            } else {
                t.web.saveWebArchive(out.getAbsolutePath());
                Fx.toast(this, getString(R.string.saved_offline));
            }
        } catch (Throwable e) {
            Fx.toast(this, "Save failed");
        }
    }

    /** copy a saved web archive into the SAF location the user picked */
    protected void copyToSaf(final String path, final Uri target) {
        new Thread(() -> {
            try (java.io.InputStream in = new java.io.FileInputStream(path);
                 java.io.OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                byte[] buf = new byte[8192];
                int n;
                while (out != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                String seg = target.getLastPathSegment();
                ui.post(() -> Fx.toast(this, getString(R.string.saved_to) + ": "
                        + (seg == null ? "selected folder" : seg)));
            } catch (Throwable e) {
                ui.post(() -> Fx.toast(this, "Save failed"));
            } finally {
                try { new File(path).delete(); } catch (Throwable ignored) {}
            }
        }, "safcopy").start();
    }

    /** "Add to home" dialog: put the current page on the browser's own home
     *  page (speed dial) or pin it to the launcher.
     *  v1.4.7 — THE FOLDER PICKER (the "implement the adding shortcuts
     *  directly to folders while adding in that website" report): Add-to-
     *  browser-home can land the shortcut directly INSIDE a chosen home
     *  folder (Prefs.addSpeeddialItemTo) instead of always on the loose
     *  grid — a folder previously only grew by dragging tiles onto it.
     *  v1.4.8 — THE CHOOSER MOVES AFTER THE TAP (the v1.4.7 report: "move
     *  the folder choosing when adding shortcut appear after I have chosen
     *  save to browser home"): the dialog is just the name again; picking
     *  "Add to browser home" opens the FOLDER CHOOSER as its own step —
     *  a bottom sheet (Home / every folder / New folder…, inline-creating)
     *  — so the folder question only appears for the destination it
     *  belongs to. Add-to-launcher is untouched (launcher pins have no
     *  folders). */
    protected void addShortcut() {
        Tab t = activeTab();
        if (t.url == null || t.url.isEmpty() || t.isHome) {
            Fx.toast(this, "Open a page first");
            return;
        }
        int txt = Fx.txt(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 20);
        wrap.setPadding(p, p / 2, p, 0);
        final EditText etName = new EditText(this);
        etName.setText(t.title == null || t.title.isEmpty() ? Fx.hostOf(t.url) : t.title);
        etName.setTextColor(txt);
        etName.setSingleLine(true);
        wrap.addView(etName, new LinearLayout.LayoutParams(-1, -2));
        final String url = t.url;
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_shortcut)
                .setView(wrap)
                .setPositiveButton(R.string.add_to_browser_home, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) name = Fx.hostOf(url);
                    pickShortcutFolderThenAdd(name, url);
                })
                .setNeutralButton(R.string.add_to_launcher, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) name = Fx.hostOf(url);
                    pinToLauncher(url, name);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** v1.4.8 — STEP TWO of the add-shortcut flow: the folder chooser,
     *  opened only after "Add to browser home" was chosen. A bottom sheet
     *  (radio dots, the standard chooser): Home (the top-level grid, the
     *  default), one row per existing home folder, and New folder… (name
     *  prompt, Prefs.addSpeeddialFolder) — the fresh folder is selected
     *  straight into the add. Falls back to the top level whenever the
     *  chosen folder refuses the write (the guarded-data law). */
    protected void pickShortcutFolderThenAdd(final String name, final String url) {
        final java.util.List<Integer> folderIdx = new ArrayList<>();   // sheet row -> folder index
        final java.util.List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.home));                            // row 0: top level
        folderIdx.add(-1);
        for (int i = 0; i < prefs.speeddial().length(); i++) {
            if (prefs.isSpeeddialGroup(i)) {
                org.json.JSONObject g = prefs.speeddialAt(i);
                opts.add(g == null ? getString(R.string.folder)
                        : g.optString("name", getString(R.string.folder)));
                folderIdx.add(i);
            }
        }
        final int newRow = opts.size();                                // the New folder… row
        opts.add(getString(R.string.new_folder));
        com.bolt.browser.util.Ui.choiceSheet(this, getString(R.string.choose_folder),
                opts.toArray(new String[0]), 0, w -> {
                    if (w == newRow) {
                        // New folder…: create inline, then add straight into it
                        final EditText et = new EditText(this);
                        et.setHint(R.string.title_label);
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.new_folder)
                                .setView(et)
                                .setPositiveButton(R.string.save, (d2, w2) -> {
                                    int made = prefs.addSpeeddialFolder(
                                            et.getText().toString().trim());
                                    addShortcutIntoFolder(made, name, url);
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    } else {
                        addShortcutIntoFolder(folderIdx.get(w), name, url);
                    }
                });
    }

    /** v1.4.8 — the shared landing: file the shortcut into folder [idx]
     *  (a top-level add when idx < 0), with the guarded fallback and the
     *  honest toast either way. */
    protected void addShortcutIntoFolder(int idx, String name, String url) {
        if (idx >= 0) {
            org.json.JSONObject g = prefs.speeddialAt(idx);
            if (prefs.addSpeeddialItemTo(idx, name, url)) {
                Fx.toast(this, getString(R.string.added_to_folder,
                        g == null ? "" : g.optString("name", "")));
            } else {
                prefs.addSpeeddial(name, url);
                Fx.toast(this, getString(R.string.shortcut_added));
            }
        } else {
            prefs.addSpeeddial(name, url);
            Fx.toast(this, getString(R.string.shortcut_added));
        }
        Tab cur = activeTab();
        if (cur.isHome) refreshHome();
    }

    /** classic launcher pin (the old "add to home screen") */
    protected void pinToLauncher(String url, String label) {
        if (Build.VERSION.SDK_INT >= 25) {
            try {
                android.content.pm.ShortcutManager sm =
                        getSystemService(android.content.pm.ShortcutManager.class);
                if (sm != null && sm.isRequestPinShortcutSupported()) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.setPackage(getPackageName());
                    android.content.pm.ShortcutInfo info =
                            new android.content.pm.ShortcutInfo.Builder(this,
                                    "bolt_" + Math.abs(url.hashCode()))
                                    .setShortLabel(label.length() > 20 ? label.substring(0, 20) : label)
                                    .setIntent(i)
                                    .build();
                    sm.requestPinShortcut(info, null);
                    Fx.toast(this, getString(R.string.shortcut_added));
                } else {
                    Fx.toast(this, "Launcher does not support shortcuts");
                }
            } catch (Throwable e) {
                Fx.toast(this, "Shortcut failed");
            }
        } else {
            Fx.toast(this, "Not supported below Android 7.1");
        }
    }

    protected void openFind() {
        ensureBarsShown();   // v1.3.33: the find bar rides with the top group
        findbar.setVisibility(View.VISIBLE);
        etFind.requestFocus();
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etFind, 0);
        } catch (Throwable ignored) {}
    }

    protected void closeFind() {
        findbar.setVisibility(View.GONE);
        Tab t = activeTab();
        if (t.web != null) t.web.clearMatches();
        tvFindCount.setText("");
        hideKeyboard();
    }

    protected void translatePage() {
        Tab t = activeTab();
        if (t == null || t.web == null) return;
        final String url = t.url;
        if (url == null || url.isEmpty()) {
            Fx.toast(this, "No page to translate");
            return;
        }
        // v1.3.70 — IN-PLACE TRANSLATION. The old flow routed through the
        // retired translate.google.com/translate proxy in a NEW TAB (and the
        // proxied copy was cookieless — "logged out"). Now the language
        // dialog feeds PageTranslate, which swaps the foreign-language text
        // INSIDE this same tab (tap the pill to undo). The dialog pre-checks
        // the device's own language.
        String[] langs = getResources().getStringArray(R.array.translate_langs);
        final String[] codes = getResources().getStringArray(R.array.translate_codes);
        int def = 0;
        try {
            String l = java.util.Locale.getDefault().getLanguage();
            if ("zh".equals(l)) l = "zh-CN";
            for (int i = 0; i < codes.length; i++) {
                if (codes[i].equals(l)) { def = i; break; }
            }
        } catch (Throwable ignored) { }
        new AlertDialog.Builder(this)
                .setTitle(R.string.translate_to)
                .setSingleChoiceItems(langs, def, (d, w) -> {
                    try { d.dismiss(); } catch (Throwable ignored) { }
                    try {
                        com.bolt.browser.util.Diag.log(BrowserActivity.this,
                                "tx pick tl=" + codes[w] + " host="
                                        + com.bolt.browser.util.Fx.hostOf(url));
                        Fx.toast(BrowserActivity.this, "Translating to " + langs[w] + "…");
                        com.bolt.browser.util.PageTranslate.begin(t.web, codes[w]);
                    } catch (Throwable e) {
                        Fx.toast(BrowserActivity.this, "Translation failed");
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * v1.3.72 — TRANSLATE SCREEN: capture what the WebView is showing
     * (PixelCopy — the GPU-true path), run OFFLINE text recognition over
     * the pixels (ML Kit, bundled: Latin + Japanese + Chinese), translate
     * every recognized block through the same guarded endpoint the page
     * translator uses, and show a full-screen overlay with the
     * translations drawn over the original (Save exports the picture).
     * The cure for text the DOM walk can never reach: manga scans,
     * photos, canvas renderings.
     */
    protected void translateScreen() {
        Tab t = activeTab();
        if (t == null || t.web == null) { Fx.toast(this, "No page to capture"); return; }
        try {
            // v1.3.75: the AI-key link opens in a NEW TAB — the v1.3.74 flow
            // navigated the current tab (the manga image's own tab) away to
            // AI Studio, destroying the thing being translated
            com.bolt.browser.util.ScreenTranslate.start(this, t.web,
                    url -> openNewTab(url));
        } catch (Throwable e) {
            Fx.toast(this, "Screen translation failed");
        }
    }

    protected void startInspector() {
        inspectorOn = true;
        Fx.toast(this, getString(R.string.inspector_on));
        evalMedia("window.__boltInspect&&window.__boltInspect(true)");
    }

    protected void showInspectorDialog(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String sel = o.optString("sel");
            final String base = curDomain();
            String msg = o.optString("tag") + (o.optString("id").isEmpty() ? "" : "#" + o.optString("id"))
                    + "\n" + (o.optString("cls").isEmpty() ? "" : o.optString("cls") + "\n")
                    + o.optInt("w") + " x " + o.optInt("h") + " px"
                    + (o.optString("text").isEmpty() ? "" : "\n\"" + o.optString("text") + "\"");
            final String fSel = sel;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dev_tools)
                    .setMessage(msg)
                    .setPositiveButton(R.string.zap_element, (d, w) -> {
                        prefs.addZap(base, fSel);
                        evalMedia("(function(){try{document.querySelectorAll('"
                                + fSel.replace("'", "\\'") + "').forEach(function(e){e.remove();});}catch(e){}})()");
                        Fx.toast(this, "Hidden. It will stay hidden on this site.");
                    })
                    .setNeutralButton(R.string.remove_once, (d, w) ->
                            evalMedia("(function(){try{document.querySelectorAll('"
                                    + fSel.replace("'", "\\'") + "').forEach(function(e){e.remove();});}catch(e){}})()"))
                    .setNegativeButton(R.string.cancel, null)
                    .setOnDismissListener(d -> {})
                    .show();
        } catch (Throwable ignored) {}
    }

    protected void showActiveScripts() {
        try {
            final List<UserscriptEngine.Script> list =
                    UserscriptEngine.forUrl(this, activeTab().url);
            if (list.isEmpty()) {
                Fx.toast(this, "No scripts active on this page");
                startActivity(new Intent(this, com.bolt.browser.scripts.UserscriptsActivity.class));
                return;
            }
            String[] names = new String[list.size()];
            boolean[] on = new boolean[list.size()];
            for (int i = 0; i < list.size(); i++) {
                names[i] = list.get(i).name;
                on[i] = list.get(i).enabled;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.active_scripts)
                    .setMultiChoiceItems(names, on, (d, w, isChecked) -> {
                        java.util.Map<String, Boolean> st = UserscriptEngine.loadState(this);
                        st.put(list.get(w).file, isChecked);
                        UserscriptEngine.saveState(this, st);
                    })
                    .setPositiveButton(R.string.done, (d, w) -> reloadIfLive())
                    .setNeutralButton(R.string.manage_scripts, (d, w) ->
                            startActivity(new Intent(this,
                                    com.bolt.browser.scripts.UserscriptsActivity.class)))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ TTS

    protected void startTts() {
        Tab t = activeTab();
        if (t.web == null || t.isHome) return;
        if (tts == null) {
            tts = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    try { tts.setLanguage(Locale.getDefault()); } catch (Throwable ignored) {}
                }
                if (ttsReady) extractAndSpeak();
            });
        } else if (ttsReady) {
            extractAndSpeak();
        }
    }

    protected void extractAndSpeak() {
        Tab t = activeTab();
        if (t.web == null) return;
        t.web.evaluateJavascript("window.__boltText&&window.__boltText()", (ValueCallback<String>) value -> {
            ui.post(() -> {
                String text = value == null ? "" : value;
                if (text.startsWith("\"")) text = text.substring(1);
                if (text.endsWith("\"")) text = text.substring(0, text.length() - 1);
                text = text.replace("\\n", " ").replace("\\\"", "\"").trim();
                if (text.isEmpty()) {
                    Fx.toast(BrowserActivity.this, "Nothing to read");
                    return;
                }
                ttsChunks = chunkText(text);
                ttsIdx = 0;
                ttsPaused = false;
                ttsbar.setVisibility(View.VISIBLE);
                btnTtsPlay.setImageResource(R.drawable.ic_pause);
                speakNext();
            });
        });
    }

    protected List<String> chunkText(String text) {
        List<String> out = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?;])\\s+");
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            if (cur.length() + s.length() > 240) {
                if (cur.length() > 0) out.add(cur.toString());
                cur = new StringBuilder(s);
            } else {
                cur.append(s).append(' ');
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    protected void speakNext() {
        if (tts == null || ttsIdx >= ttsChunks.size()) {
            stopTts();
            return;
        }
        tvTtsStatus.setText(getString(R.string.tts_playing) + " " + (ttsIdx + 1) + "/" + ttsChunks.size());
        tts.speak(ttsChunks.get(ttsIdx), TextToSpeech.QUEUE_FLUSH, null, "bolt_" + ttsIdx);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onError(String id) { ui.post(() -> speakNext()); }
            @Override public void onDone(String id) {
                ttsIdx++;
                ui.post(() -> speakNext());
            }
        });
    }

    protected void toggleTts() {
        if (ttsPaused) {
            ttsPaused = false;
            btnTtsPlay.setImageResource(R.drawable.ic_pause);
            speakNext();
        } else {
            ttsPaused = true;
            btnTtsPlay.setImageResource(R.drawable.ic_play);
            try { tts.stop(); } catch (Throwable ignored) {}
        }
    }

    protected void stopTts() {
        if (tts != null) try { tts.stop(); } catch (Throwable ignored) {}
        ttsbar.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ exit

    protected void confirmExit() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.exit_warning)
                .setPositiveButton(R.string.yes, (d, w) -> exitNow())
                .setNegativeButton(R.string.no, null)
                .show();
    }

    protected void exitNow() {
        // v1.3.13: the exit-with-clear path is fully ordered and runs the wipe
        // to COMPLETION before the app goes away (DataWipe.performExitClear):
        // pages wiped -> media paused -> service stopped -> WebViews destroyed
        // (Chromium stops writing cache files) -> cache dirs emptied
        // SYNCHRONOUSLY -> finish + process kill so nothing ever re-writes.
        // The v1.3.12 flaw: the wipe ran on a delayed background thread right
        // before finishAffinity() — on aggressive devices the cached process
        // died mid-wipe, so history/logins (synchronous) cleared but the
        // physical cache never did.
        userExit = true;
        if (incognito) {
            try { stopService(new Intent(this, MediaControlService.class)); } catch (Throwable ignored) {}
            // v1.4.19 — THE EXIT MUST LEAVE (the "exit/power button lands on
            // incognito HOME" report): wipeIncognito ends with killProcess,
            // and killing the process WITHOUT finishing its activities
            // leaves the task record alive — the OS simply RESTARTS
            // IncognitoActivity into a fresh :incognito process (incognito
            // home; and before the suffix-first fix each restart crashed
            // and fell back to the normal task). The activities are now
            // finished and the task REMOVED first, the wipe runs, and the
            // kill is POSTED 300 ms so AMS has seen the finish
            // transactions before the death arrives — exit means LEAVE.
            // (onDestroy still runs its own wipeIncognito as the final
            // backstop; ordering finish-before-kill is the fix.)
            try { finishAndRemoveTask(); } catch (Throwable ignored) {}
            try { finishAffinity(); } catch (Throwable ignored) {}
            try {
                com.bolt.browser.util.Diag.log(this,
                        "exit incognito: wipe + finish + leave");
            } catch (Throwable ignored) {}
            wipeIncognitoData();
            ui.postDelayed(() -> {
                try { android.os.Process.killProcess(android.os.Process.myPid()); } catch (Throwable ignored) {}
            }, 300L);
            return;
        }
        // both exit flavours go through the SAME ordered routine: with the
        // clear checklist on it wipes everything and hard-kills; with it off
        // it still pauses media, stops the service, retires the renderers and
        // finishes (fast relaunch, nothing playing behind a dead service)
        com.bolt.browser.util.DataWipe.performExitClear(this, prefs);
    }

    // ------------------------------------------------------------ system

    @Override
    public void onBackPressed() {
        if (quickPanel != null && quickPanel.getParent() != null) {
            hideQuickPanel();
            return;
        }
        if (customView != null) {
            exitFullscreenVideo();
            return;
        }
        if (findbar.getVisibility() == View.VISIBLE) {
            closeFind();
            return;
        }
        if (ttsbar.getVisibility() == View.VISIBLE) {
            stopTts();
            return;
        }
        Tab t = activeTab();
        if (t.web != null && t.web.canGoBack()) {
            // v1.3.84 — the audited history step, same as the toolbar back
            com.bolt.browser.util.HistoryNav.step(this, t.web, -1);
            return;
        }
        if (!t.isHome && !t.url.isEmpty()) {
            goHome();
            return;
        }
        goBack();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 66) { // notifications granted -> the media service
            // operates autonomously now; nothing to re-drive from here
        } else if (requestCode == 78) {
            // legacy storage permission for public-Downloads saves (API <= 28)
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingDl != null) {
                String[] p = pendingDl;
                pendingDl = null;
                DownloadEngine.get(this).enqueue(p[0], p[1],
                        p[2] == null || p[2].isEmpty() ? null : p[2], p[3], p[4]);
                ensureDlShelfListener();   // v1.3.40 download shelf
                Fx.toast(this, getString(R.string.download_started) + ": " + p[1]
                        + (ok ? "" : " (app folder)"));
            }
        } else if (requestCode == 77 && pendingPermRequest != null) {
            boolean ok = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            try {
                if (ok) pendingPermRequest.grant(pendingPermRequest.getResources());
                else pendingPermRequest.deny();
            } catch (Throwable ignored) {}
            pendingPermRequest = null;
        } else if (requestCode == 1023) {
            // v1.4.12 — the image-search capture: CAMERA granted -> the
            // camera app finally launches (EXTRA_OUTPUT capture needs the
            // permission held because the manifest declares CAMERA)
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchVisualCamera();
            } else {
                Fx.toast(this, "Camera permission needed for photo search");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // v1.3.31 — WebView file upload (see onShowFileChooser). FIRST in the
        // chain and ALWAYS answers the callback exactly once, null on cancel:
        // Chromium's duplicate-result guard would otherwise kill every later
        // picker on that page.
        if (requestCode == 1005) {
            android.webkit.ValueCallback<android.net.Uri[]> cb = fileUploadCallback;
            fileUploadCallback = null;
            if (cb == null) return;
            android.net.Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                try {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        result = new android.net.Uri[n];
                        for (int i = 0; i < n; i++) {
                            result[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        result = new android.net.Uri[]{ data.getData() };
                    }
                } catch (Throwable ignored) {}
            }
            try { cb.onReceiveValue(result); } catch (Throwable ignored) {}
            return;
        }
        // v1.3.37 — QR scanner result (request 1006): the decoded text goes
        // through the confirmation sheet (open / search / copy), never an
        // automatic navigation.
        if (requestCode == 1006) {
            if (resultCode == RESULT_OK && data != null) {
                String text = data.getStringExtra(QrScanActivity.EXTRA_TEXT);
                if (text != null && !text.trim().isEmpty()) showQrResult(text);
            }
            return;
        }
        // v1.3.37 — start-page wallpaper picked (request 1007)
        if (requestCode == 1007) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                applyWallpaper(data.getData());
            }
            return;
        }
        // v1.3.38 — VOICE SEARCH. From the omnibox (1010): the transcript
        // fills the still-open field (editable before GO). From the home
        // pill (1011): instant search — the Chrome voice-search behavior.
        if (requestCode == 1010 || requestCode == 1011) {
            if (resultCode == RESULT_OK && data != null) {
                String said = com.bolt.browser.util.VoiceSearch.result(data);
                if (said != null && !said.isEmpty()) {
                    if (requestCode == 1010 && activeOmniField != null
                            && activeOmniField.getParent() != null) {
                        try {
                            activeOmniField.setText(said);
                            activeOmniField.setSelection(said.length());
                            activeOmniField.requestFocus();
                        } catch (Throwable ignored) {}
                    } else {
                        loadInTab(said);
                    }
                }
            }
            return;
        }
        if (requestCode == 1001 && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            final Uri target = data.getData();
            Tab t = activeTab();
            if (t.web == null) return;
            final File tmp = new File(getCacheDir(), "offline-" + System.currentTimeMillis() + ".mht");
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    t.web.saveWebArchive(tmp.getAbsolutePath(), false, path -> copyToSaf(path, target));
                } else {
                    t.web.saveWebArchive(tmp.getAbsolutePath());
                    copyToSaf(tmp.getAbsolutePath(), target);
                }
            } catch (Throwable e) {
                saveOfflineLegacy();
            }
        } else if (requestCode == 1002 && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            // SAF folder chosen for downloads (the download dialog is the only
            // 1002 caller since saved pages got their own independent picker)
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) {}
            prefs.setDownloadDir(tree.toString());
            Fx.toast(this, getString(R.string.download_folder_set));
            if (dlDestLabel != null) {
                dlDestLabel.setText(safFolderName(prefs.downloadDir()));
            }
        } else if (requestCode == 1004 && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            // SAF folder chosen for SAVED PAGES (independent picker)
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) {}
            prefs.setSavedPagesDir(tree.toString());
            Fx.toast(this, getString(R.string.saved_pages_folder_set));
            if (dlDestLabel != null) {
                dlDestLabel.setText(safFolderName(prefs.savedPagesDir()));
            }
        } else if (requestCode == 1012 && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            // v1.3.79 — the custom home logo landed (showHomeLogoPicker)
            applyHomeLogo(data.getData());
        }
        // v1.4.12 — THE OMNIBOX IMAGE-SEARCH DOOR. Camera capture (1021):
        // the picture already sits in the FileProvider file (EXTRA_OUTPUT —
        // the result intent carries no data). Gallery pick (1022): the
        // content stream is copied into the cache dir first because the
        // decoder needs a seekable file.
        if (requestCode == 1030) {
            // v1.4.19 — Bolt AI's temporary attachment pick (ACTION_GET_CONTENT,
            //  image or text): the result goes straight back to the AI panel
            //  (decode + staging happen off-thread there)
            try {
                com.bolt.browser.util.BoltAi.onAttachmentResult(this,
                        data == null ? null : data.getData());
            } catch (Throwable t) {
                Fx.toast(this, "Couldn't read the file");
            }
            return;
        }
        if (requestCode == 1021) {
            File f = vsearchCapture;
            vsearchCapture = null;
            if (resultCode == RESULT_OK && f != null && f.exists() && f.length() > 0) {
                runVisualSearch(f);
            } else {
                try { if (f != null) f.delete(); } catch (Throwable ignored) {}
            }
            return;
        }
        if (requestCode == 1022) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    File tmp = new File(getCacheDir(), "vsearch-pick.jpg");
                    try (java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                         java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        if (in == null) throw new java.io.IOException("no stream");
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    runVisualSearch(tmp);
                } catch (Throwable t) { Fx.toast(this, "Couldn't read the image"); }
            }
            return;
        }
    }
}
