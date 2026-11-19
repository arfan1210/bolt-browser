package com.bolt.browser.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.bolt.browser.util.Fx;
import com.bolt.browser.util.PasskeyCompat;
import com.bolt.browser.util.Prefs;
import com.bolt.browser.util.WebAuthnHelper;

import org.json.JSONArray;
import org.json.JSONObject;

/** One browser tab: WebView wrapper + meta. */
public class Tab {
    public WebView web;
    public View homeView;      // native new-tab page (only for home tabs)
    public String url = "";
    public String title = "";
    public Bitmap snap;
    public long groupId = 0;   // 0 = ungrouped
    public boolean isHome = true;
    public boolean loading;
    public boolean boostable;  // media chain routable
    /** runtime-only: this tab's page last reported media PLAYING. Per-tab truth —
     *  the activity aggregates across tabs; the old single global flag took the
     *  LAST report from ANY tab, so with 2+ tabs it flickered false every second
     *  (paused tabs also report) and caused wake-lock / service-stop churn. */
    public boolean mediaPlaying;
    /** v1.3.16: this tab's page reported a LOADED media element (playing OR
     *  paused with a real duration). A merely-paused media tab is still the
     *  media-notification owner and must stay resumable from the notification:
     *  BrowserActivity.onPause() must NOT call web.onPause() on it (a frozen
     *  renderer swallows every later __boltCmd('play') — the "notification play
     *  does nothing after pause → re-enter → leave" bug). Cleared when the page
     *  reports 'none' (no/ended media) or the WebView is destroyed. */
    public boolean holdsMedia;
    /** v1.3.27: last reported playback position of this tab's media element
     *  (seconds, from the page bridge's state/time beats). Used ONLY by the
     *  persistence path: a playing YouTube watch URL is saved with
     *  &t=<sec>s so a killed session restores approximately where the video
     *  was — the difference between "music resumed" and "sat on YouTube
     *  home with no video selected". */
    public double mediaPosSec;
    /** v1.3.19: renderer-crash loop guard. A page that keeps killing the
     *  renderer must not be auto-reloaded forever (official Managing WebView
     *  guidance) — after 2 crashes in 30 s the next rebuild shows the crash
     *  notice instead of reloading the same page again. */
    public long lastRenderCrashAt = 0;
    public int renderCrashCount = 0;
    public boolean renderCrashHold = false;
    /** v1.3.87 — download-repair pair: when a download leaves the WebView
     *  stranded on the (blank) download URL — the GitHub releases pattern
     *  — dlUrl is that URL and dlFromUrl the real page it came from.
     *  BrowserActivity.repairDownloadPage() arms both BEFORE the blank can
     *  arrive, so a save racing the repair persists the real page
     *  (TabManager.resumeUrl swaps the copy). v1.3.88: the pair IS
     *  serialized while the tab is actually stranded (toJson gates on
     *  url==dlUrl) — a process death mid-download restores the real page
     *  via the TabManager constructor's swap. */
    public String dlUrl;
    public String dlFromUrl;
    /** v1.3.31 — epoch ms when the tab was created ("sort by newest opened").
     *  Persisted; sessions restored from old saves carry 0 and fall back to
     *  list-position order as the final tiebreaker. */
    public long createdAt = 0L;
    /** v1.3.31 — epoch ms when the tab last became the active/shown tab
     *  ("sort by last visited"). Updated by showTab + tabs-grid taps;
     *  persisted; 0 for legacy saves. */
    public long lastVisitedAt = 0L;
    /** v1.3.100 — THE AUTH-CHAIN UA HOLD (the qwen-connect corpse: "in
     *  qwen coder website, the browser looks like refreshes when I try to
     *  connect my github. I am unable to connect my github to qwen
     *  coder"). An OAuth/SSO round-trip is a CROSS-SITE redirect chain that
     *  starts and ends on the relying party (qwen.ai -> github.com ->
     *  qwen.ai/callback?code=...), and the per-site UA decision fired at
     *  EVERY hop: github.com carries no desktop pref, so the v1.3.98 heal
     *  flipped the WebView UA to mobile the moment the chain left qwen —
     *  then flipped it back to desktop when the callback committed — and a
     *  mid-flight UA write makes Chromium reload the in-flight/committed
     *  page, re-requesting the SINGLE-USE authorization code (the visible
     *  "refresh"; the second exchange dies, the connect fails). Chrome
     *  evaluates the per-site UA PER REQUEST and never reloads; a WebView
     *  has ONE UA per view, so the round-trip must ride ONE stable UA —
     *  the one the relying party's page already carried when the flow left
     *  it (which IS that site's own pref: the page was committed under
     *  it). The hold covers the IdP's base domain + the RP's base domain
     *  from the authorize hop until the response lands (or 10 minutes, or
     *  a navigation to a third domain — whichever comes first). UI-thread
     *  only (every caller is a WebView callback). */
    public String authHoldIdp = null;
    public String authHoldRp = null;
    public long authHoldAt = 0L;
    /** v1.3.100 — the auth-chain hold TTL: an OAuth dance takes under two
     *  minutes; ten covers a slow login typing round-trip and still lapses
     *  long before a stuck hold could confuse a later session. */
    static final long AUTH_HOLD_MS = 10L * 60L * 1000L;
    // v1.3.38 TAB SLEEP: the renderer is destroyed but the tab survives
    // (url + scroll position + thumbnail); showTab rebuilds + reloads, and
    // BoltClient.onPageFinished hands the reading position back (TabSleep).
    public boolean sleeping = false;
    public int sleepScrollY = 0;
    /** v1.3.40 — TAB SEARCH BY PAGE CONTENT (round-4 #7): a transient slice
     *  of the page's visible text (≤4000 chars, captured in onPageFinished).
     *  Deliberately NOT persisted — it is a search aid, not history; and it
     *  is NEVER captured in incognito (no page text leaves the private
     *  session's memory). */
    public String pageText = "";

    /** v1.4.17 — THE DARK-PASS RUN TOKEN (the white-flash fix): the web-dark
     *  decision chain (probe -> ask -> invert) now runs at onPageCommitVisible
     *  — the earliest moment the document exists — instead of waiting for
     *  onPageFinished, so a light site under the dark-web engine no longer
     *  paints white for the whole subresource tail. This field holds the URL
     *  the chain last ran FOR on this tab; onPageFinished re-runs the chain
     *  only when its URL differs (the backstop for a commit that never
     *  fired), because a second unguarded run would probe the ALREADY
     *  INVERTED page, read it as "painted dark", and clear Bolt's own
     *  inversion — the probe cannot tell site-dark from bolt-dark. UI-thread
     *  only (every caller is a WebView callback). */
    public String darkPassUrl = null;

    /** v1.4.8 — THE TAB ID (the empty-group anchor law): a stable identity
     *  for a tab ROW, distinct from every other live tab and stable across
     *  restarts (persisted as "tid"). createdAt cannot serve (a restore
     *  loop stamps many tabs in the same millisecond — the same collision
     *  that once birthed duplicate group ids); the id comes from a
     *  process-wide monotonic sequence seeded past every restored tid
     *  (TabManager bumps it after loading), so it never repeats. The
     *  follow-order render keys rows by it ("t<tid>" in a group's
     *  ap/an anchor pair). */
    public long tid = 0L;

    /** v1.4.19 — THE OFFLINE-ERROR MARKER: non-null while this tab's last
     *  load FAILED and the native error overlay is its face (the overlay
     *  is per-activity, so switching back to the tab re-shows it; any new
     * navigation clears it). Never persisted — it is view state, not data. */
    public volatile String errUrl = null;
    /** the monotonic tid sequence — max(now, last+1) keeps it increasing
     *  even when the wall clock stutters; TabManager bumps it past every
     *  restored tid at load so a fresh tab can never collide with a
     *  restored one. */
    private static final java.util.concurrent.atomic.AtomicLong TID_SEQ =
            new java.util.concurrent.atomic.AtomicLong(0L);

    public Tab() {
        createdAt = System.currentTimeMillis();
        tid = TID_SEQ.updateAndGet(v -> Math.max(v + 1, System.currentTimeMillis()));
    }

    /** v1.4.8 — after restoring persisted tabs, lift the tid sequence past
     *  the highest restored id so a new tab can never reuse a restored
     *  tab's identity (an anchor must never point at the wrong row). */
    public static void bumpTidSeq(long restoredMax) {
        TID_SEQ.updateAndGet(v -> Math.max(v, restoredMax));
    }

    public WebView buildWeb(Context c, WebViewClient vc, WebChromeClient cc,
                            Object jsBridge, boolean incognito) {
        if (web != null) return web;
        // v1.4.13 — THE PRE-WARM TAKE: pull BoltApp's spare WebView when the
        // pool still holds one (its MutableContextWrapper swaps to THIS
        // caller's context inside take — see core/WebPool.java) and fall
        // back to plain construction otherwise. Everything below configures
        // either instance identically: the settings block is idempotent and
        // the once-per-WebView guards (PasskeyCompat, FrameHooks, ShadowGate)
        // see a fresh view either way.
        WebView w = com.bolt.browser.core.WebPool.take(c);
        if (w == null) {
            // v1.4.19 — THE CONSTRUCTION GUARD (the incognito crash loop's
            // hardening leg): this fallback was NOT try/catch-wrapped, so a
            // data-directory failure (the AwDataDirLock collision — see
            // BoltApp's suffix-first law) escaped as an UNCAUGHT
            // RuntimeException and killed the process, which the OS then
            // restarted into the same crash. Two refusals now degrade
            // instead of crashing: (a) :incognito never constructs against
            // the directory the MAIN process owns when its own suffix
            // failed — buildWeb returns null and the caller shows the tab
            // without a live page (a toast + Diag line name the cause);
            // (b) ANY construction failure is caught, logged, and returned
            // as null — never a process kill.
            if (com.bolt.browser.BoltApp.incognitoSuffixFailed) {
                try {
                    com.bolt.browser.util.Diag.log(c,
                            "web construct REFUSED: incognito data-dir suffix failed");
                } catch (Throwable ignored) {}
                return null;
            }
            try {
                w = new com.bolt.browser.ui.BoltWeb(c);
            } catch (Throwable t) {
                try {
                    com.bolt.browser.util.Diag.log(c, "web construct failed cls="
                            + t.getClass().getName());
                } catch (Throwable ignored) {}
                return null;
            }
        }
        // opaque background: transparent WebView surfaces can flash the previous
        // tab's last frame when they are re-attached ("ghosting of previous tabs")
        // v1.3.33: skin-aware (Fx.webBg resolves from the skin pref directly —
        // this also runs in the service process where no activity theme exists)
        try {
            w.setBackgroundColor(Fx.webBg(c));
        } catch (Throwable ignored) {}
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false); // allows notification-triggered play
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(true); // needed to open saved offline .mht pages
        s.setAllowContentAccess(false);
        if (Prefs.get(c).dnt()) {
            // best-effort DNT header via user agent suffix
        }
        applyUa(c, w);
        applyZoom(c, w);   // v1.3.40: per-site zoom override, else global font size
        applyWebDark(c, w);
        // v1.3.20: WebAuthn/passkeys (Kotlin helper) — WebView's native engine
        // in browser mode; feature-detected, never throws, no-op on old
        // WebViews. Central spot: every tab WebView incl. restores/incognito.
        WebAuthnHelper.enablePasskeys(w);
        // v1.3.23: passkey compatibility layer — completes the JS capability
        // surface (GitHub/Google probes) + Chrome-shaped UA + document-start
        // injection of assets/webauthn_compat.js. Never throws; custom UAs
        // chosen by the user are untouched (see PasskeyCompat).
        PasskeyCompat.apply(c, w);
        // v1.3.98 — the client-hints twin at BUILD time: PasskeyCompat just
        // wrote the chromeUa string WITHOUT metadata, and the first
        // applyUaFor (build path) sees an unchanged string and would skip
        // the metadata write — every first page load would then double-load
        // when the navigation hooks close the gap. Setting the matching
        // identity HERE means the WebView is consistent from the very first
        // request (feature-gated inside; old WebViews keep today's
        // behavior — the JS spoof still carries the JS side there).
        try {
            com.bolt.browser.util.UaIdentity.applyFor(
                    c, w.getSettings(), PasskeyCompat.chromeUa());
        } catch (Throwable ignored) {}
        // v1.3.58 — frame hooks (util/FrameHooks.kt): the SAME document-start
        // mechanism as PasskeyCompat above, carrying the per-frame blob
        // capture + relay + clipboard shim into EVERY frame — opaque-origin
        // (sandboxed) iframes included, which is where Gemini mints its
        // `blob:null/…` download URLs (the v1.3.58 root cause; main-frame
        // rescues are structurally blind there). Must run BEFORE the WebView's
        // first loadUrl — document-start scripts only cover frames that begin
        // loading after registration; once per WebView (WeakHashMap-guarded
        // inside arm()). Never throws; on WebViews without the
        // DOCUMENT_START_SCRIPT feature the main-frame fallback injection
        // (BrowserActivity onPageStarted/onPageCommitVisible) keeps v1.3.57
        // parity.
        com.bolt.browser.util.FrameHooks.arm(w);
        // v1.3.72 — shadow gate (util/ShadowGate.kt): the third document-start
        // registration, wrapping Element.prototype.attachShadow so every
        // minted shadow root (CLOSED ones included) is recorded into
        // window.__boltShr for the page translator's walker — a closed root
        // is otherwise unreadable from outside by design. Same rules as the
        // two above: before first loadUrl, once per WebView, never throws.
        com.bolt.browser.util.ShadowGate.arm(w);
        // v1.3.24: bolt's own on-device authenticator — the CEREMONY backend.
        // Exposed as window.BoltWebAuthn (probed by webauthn_compat.js, bound
        // ahead of the native engine). Available at document start in every
        // document (addJavascriptInterface), never in incognito (passkeys
        // are an identity feature, and the incognito process shares no
        // storage with the main one by design).
        if (!incognito) {
            try {
                w.addJavascriptInterface(
                        new com.bolt.browser.util.BoltAuthenticator(c), "BoltWebAuthn");
            } catch (Throwable ignored) { }
        }
        // v1.3.70 — the translation relay (util/PageTranslate.kt): ONE
        // INSTANCE PER WEB, registered like BoltWebAuthn above, because the
        // shared Bolt bridge routes evals to the ACTIVE tab — a user who
        // switches tabs mid-translation would get another page's results
        // otherwise. Holds a WeakReference to ITS OWN web, so answers
        // always land in the tab that asked. Enabled in incognito too:
        // translation is page CONTENT the user explicitly requested (not
        // identity), and the network call only ever happens on that tap.
        // Hosts hard-whitelisted inside (never a cross-origin proxy).
        try {
            w.addJavascriptInterface(
                    new com.bolt.browser.util.PageTranslate.Relay(w), "BoltTx");
        } catch (Throwable ignored) { }
        w.setWebViewClient(vc);
        w.setWebChromeClient(cc);
        if (jsBridge != null) w.addJavascriptInterface(jsBridge, "Bolt");
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(w, true);
        w.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web = w;
        return w;
    }

    public void applyUa(Context c, WebView w) {
        applyUaFor(c, w, url, null);
    }

    /** v1.3.98 — applyUa with the URL GIVEN (not t.url): the navigation
     *  hooks (onPageStarted / onPageCommitVisible) know the url being loaded
     *  even while t.url is still settling, and they are the ONLY paths that
     *  see back/forward and redirect navigations — shouldOverrideUrlLoading
     *  fires for NEITHER, so the per-site desktop/UA decision never ran on
     *  them and the WebView kept whatever UA the LAST link click computed.
     *  THE FIELD CORPSE ("the desktop site works temporarily and falls back
     *  to mobile maybe when the link changes"): a desktop-forced tab that
     *  followed an external link (UA -> mobile for the other site) and then
     *  pressed BACK re-requested the desktop site WITH THE MOBILE UA — the
     *  server answered mobile and the toggle looked broken until manually
     *  re-flipped. The heal runs on every main-frame start/commit, and the
     *  write-only-if-different law keeps it side-effect-free whenever the
     *  UA already matches (the v1.3.25 single-write contract is untouched).
     *  @return true when the UA actually CHANGED (the caller may reload —
     *  a commit-time change means the committed page was loaded with the
     *  wrong UA; a start-time change rides Chromium's own reload-the-
     *  in-flight-load behavior, crbug 40340203, fixed).
     *  @param reason diagnostics tag for the diag line (null = silent). */
    public boolean applyUaFor(Context c, WebView w, String url, String reason) {
        if (w == null) return false;
        // v1.3.98 — the heal is a PER-SITE decision: it needs an http(s)
        // origin. Schemeless documents (about:blank popups, data:/blob:
        // views, the crash-notice loadData) have no site prefs, and a
        // commit-time reload there could wipe opener-written content —
        // they KEEP the live UA exactly as pre-v1.3.98 (the global
        // default was already applied at build by PasskeyCompat.fixUa).
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return false;
        }
        // v1.3.100 — THE AUTH-CHAIN HOLD (see the field docs above). Order
        // matters: the RESPONSE check first (a single-use auth landing is
        // never reloaded, whatever else is going on), the REQUEST check
        // second (arms/refreshes the hold; the chain leaves with the UA
        // the relying party's page already carries), then the held-chain
        // skip for the IdP/RP domains. Every branch here returns WITHOUT a
        // write — no setUserAgentString, no metadata, no reload — so the
        // chain's identity is frozen end to end. A navigation to a third
        // domain disarms and heals normally (the user left the flow).
        {
            String authHost = Fx.hostOf(url);
            String authBase = Fx.baseDomain(authHost);
            long now = System.currentTimeMillis();
            boolean held = authHoldIdp != null && now - authHoldAt < AUTH_HOLD_MS;
            if (isAuthResponseUrl(url)) {
                if (held) { authHoldIdp = null; authHoldRp = null; }
                if (reason != null) {
                    try {
                        com.bolt.browser.util.Diag.log(c, "ua auth rsp-skip"
                                + (held ? "-end" : "") + " host=" + authHost);
                    } catch (Throwable ignored) {}
                }
                return false;
            }
            if (isAuthRequestUrl(url)) {
                // the RP is the base the flow LEAVES — the WebView's live
                // (outgoing) page, read before the chain commits anywhere.
                // A same-domain re-arm (IdP login pages bounce back through
                // the authorize endpoint) resolves the RP to the IdP
                // itself: the EARLIER rp survives instead of being
                // clobbered, so the final callback stays covered.
                String rp = null;
                try {
                    String cb = Fx.baseDomain(Fx.hostOf(w.getUrl()));
                    if (cb.length() > 0 && !cb.equals(authBase)) rp = cb;
                } catch (Throwable ignored) {}
                if (rp == null && authHoldRp != null && authHoldRp.length() > 0
                        && !authHoldRp.equals(authBase)) rp = authHoldRp;
                authHoldIdp = authBase;
                authHoldRp = rp;
                authHoldAt = now;
                if (reason != null) {
                    try {
                        com.bolt.browser.util.Diag.log(c, "ua auth arm idp=" + authBase
                                + (rp == null ? "" : " rp=" + rp));
                    } catch (Throwable ignored) {}
                }
                return false;
            }
            if (held && (authBase.equals(authHoldIdp)
                    || (authHoldRp != null && authBase.equals(authHoldRp)))) {
                if (reason != null) {
                    try {
                        com.bolt.browser.util.Diag.log(c, "ua auth hold-skip host="
                                + authHost);
                    } catch (Throwable ignored) {}
                }
                return false;
            }
            if (held) {
                authHoldIdp = null;   // third domain: the flow is over
                authHoldRp = null;
            }
        }
        Prefs p = Prefs.get(c);
        String host = Fx.hostOf(url);
        int idx = p.uaIndex();
        if (host != null && !host.isEmpty()) {
            // site prefs are always stored under the BASE domain (youtube.com),
            // never under the full host (www.youtube.com) -- reading with the raw
            // host made the UA / desktop toggles silently do nothing.
            String base = Fx.baseDomain(host);
            if (p.siteBool(base, "desktop", false)) idx = 1;
            int siteUa = p.siteInt(base, "ua", -1);
            // v1.3.88: only a REAL per-site UA choice (1+) overrides — the
            // desktop toggle and the global preference must both be able to
            // beat a stored 0 ("Default (mobile)", the chooser's first
            // entry). A stored 0 used to pin the site to mobile forever:
            // the desktop chip said ON while the page stayed mobile.
            if (siteUa > 0) idx = siteUa;
        }
        String[] uas = c.getResources().getStringArray(com.bolt.browser.R.array.ua_values);
        String ua = idx > 0 && idx < uas.length ? uas[idx] : "";
        if (ua.isEmpty()) ua = PasskeyCompat.chromeUa();
        // v1.3.99 — THE LIVING DESKTOP UA (the identity-agreement law, see
        // PasskeyCompat.desktopChromeUa). The Chrome desktop preset was
        // frozen at Chrome/124 (mid-2024) while UaIdentity's native hints
        // and the JS spoof claimed the DETECTED engine (156): a UA string
        // and a userAgentData that disagree about the browser version is a
        // bot fingerprint (grok.com's Cloudflare gate reads exactly that
        // pair), and a 2.5-year-old version fails feature gates on its
        // own. The preset's UA is composed from the detected engine major
        // — the chromeUa pattern — so string, native hints and JS fallback
        // all claim the same N. Shape-matched (Windows + Chrome) so the
        // Firefox/Safari presets pass through untouched.
        if (ua.contains("Windows NT") && ua.contains("Chrome/")) {
            ua = PasskeyCompat.desktopChromeUa();
        }
        // v1.3.25 — THE LINK-CLICK REGRESSION FIX. Chromium reloads the CURRENT
        // page whenever the WebView UA string is set to a DIFFERENT value
        // (SetUserAgentOverride -> reload; chromium issue 40340203, confirmed by
        // field reports: "the WebView will reload when we change the User-Agent").
        // v1.3.23 appended PasskeyCompat.fixUa() here, so every navigation did
        // setUserAgentString(null)  [chromeUa -> system default = CHANGE 1]
        // followed by fixUa()        [default -> chromeUa       = CHANGE 2]
        // — two reloads of the page you were on, preempting the pending
        // navigation: tap a Google result -> Google reloads instead of the site
        // opening; every link/button-driven navigation did the same. Typing a
        // URL still worked only because loadUrl() is issued after the churn and
        // wins the race.
        // The UA must now be computed to its FINAL value and written ONCE, and
        // only when it actually differs from the live one. No change -> no
        // setUserAgentString call -> no reload -> navigations proceed.
        // v1.3.98 reading of the same issue: the reload fires "if the current
        // page is in the process of being loaded to make the whole page being
        // loaded with a single UA" — i.e. a start-time write reloads the
        // IN-FLIGHT load with the new UA (exactly the back-navigation heal),
        // and an idle-time write reloads nothing (link clicks stay single-load).
        WebSettings s = w.getSettings();
        String cur = s.getUserAgentString();
        boolean changed = false;
        if (!ua.equals(cur)) {
            s.setUserAgentString(ua);
            changed = true;
        }
        // v1.3.98 — THE NATIVE CLIENT-HINTS TWIN (see UaIdentity.kt): the UA
        // string and the UA-CH metadata must agree, or a hints-reading site
        // sees a desktop UA with mobile/no hints and serves the mobile layout
        // anyway. Rides the same write-only-if-different law (shape-level
        // compare) so the steady state costs nothing.
        try {
            com.bolt.browser.util.UaIdentity.applyFor(c, s, ua);
        } catch (Throwable ignored) {}
        if (changed && reason != null) {
            try {
                com.bolt.browser.util.Diag.log(c, "ua heal " + reason
                        + " host=" + (host == null ? "" : host)
                        + " -> " + (idx == 1 ? "desktop" : "ua" + idx));
            } catch (Throwable ignored) {}
        }
        return changed;
    }

    /** v1.3.100 — an OAuth/SAML AUTHORIZE REQUEST (the hop that leaves the
     *  relying party for the identity provider): a query carrying
     *  client_id TOGETHER WITH redirect_uri or response_type — the shape
     *  every OAuth2/OIDC authorize endpoint emits (github.com/login/oauth/
     *  authorize, accounts.google.com/o/oauth2/auth, ...) — or a SAML
     *  redirect-binding SAMLRequest. Reached only from main-frame hooks
     *  (shouldOverrideUrlLoading / onPageStarted / onPageCommitVisible),
     *  so an API iframe's client_id can never arm the hold. */
    static boolean isAuthRequestUrl(String url) {
        String q = urlQueryOf(url);
        return qHasAny(q, "samlrequest")
                || (qHasAny(q, "client_id")
                    && (qHasAny(q, "redirect_uri") || qHasAny(q, "response_type")));
    }

    /** v1.3.100 — an auth RESPONSE / single-use landing: the query OR the
     *  fragment carries the OAuth2/OIDC response params (code, error...,
     *  id_token, access_token) or the SAML response pair. These pages are
     *  SINGLE-USE — an authorization code is consumed by its first
     *  exchange and a reload re-sends it, killing the handshake (the
     *  v1.3.98 commit heal's reload-once was exactly the corpse under
     *  qwen's failed GitHub connect). Bias is deliberate: a false
     *  positive skips ONE heal (a page renders with the chain's UA until
     *  the next navigation — cosmetic, self-healing), a false negative
     *  reloads a single-use page (fatal to the sign-in). */
    static boolean isAuthResponseUrl(String url) {
        String[] names = {"code", "error", "error_code", "error_description",
                "id_token", "access_token", "token", "samlresponse",
                "relaystate", "nonce", "iss"};
        return qHasAny(urlQueryOf(url), names) || qHasAny(urlRefOf(url), names);
    }

    /** the query string (between '?' and '#'), "" when absent. */
    private static String urlQueryOf(String url) {
        try {
            if (url == null) return "";
            int q = url.indexOf('?');
            if (q < 0) return "";
            int h = url.indexOf('#', q + 1);
            return h > q ? url.substring(q + 1, h) : url.substring(q + 1);
        } catch (Throwable t) { return ""; }
    }

    /** the fragment (after '#'), "" when absent — OIDC implicit/hybrid
     *  flows deliver their tokens there. */
    private static String urlRefOf(String url) {
        try {
            if (url == null) return "";
            int h = url.indexOf('#');
            return h >= 0 && h < url.length() - 1 ? url.substring(h + 1) : "";
        } catch (Throwable t) { return ""; }
    }

    /** exact param-NAME match (lowercased) against a query or fragment —
     *  never a substring: promo_code= or ?error-page= must not trip the
     *  auth detectors. */
    private static boolean qHasAny(String q, String... names) {
        if (q == null || q.isEmpty() || names == null || names.length == 0) return false;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String n = (eq >= 0 ? pair.substring(0, eq) : pair).trim().toLowerCase();
            for (String want : names) if (n.equals(want)) return true;
        }
        return false;
    }

    /** v1.3.40 — PER-SITE PAGE ZOOM (round-4 #5): the site's remembered
     *  textZoom override (sites.json "zoom", 60..200; unset = -1) wins over
     *  the global font-size slider, exactly like the per-site desktop/dark
     *  overrides. Applied at WebView build + every main-frame navigation
     *  (BrowserActivity calls it beside applyUa) — textZoom applies live,
     *  no reload needed. */
    public void applyZoom(Context c, WebView w) {
        if (w == null) return;
        try {
            Prefs p = Prefs.get(c);
            String base = Fx.baseDomain(Fx.hostOf(url == null ? "" : url));
            int z = p.siteInt(base, "zoom", -1);
            w.getSettings().setTextZoom(z >= 60 ? Math.min(z, 300) : p.fontSize());
        } catch (Throwable t) {
            try { w.getSettings().setTextZoom(Prefs.get(c).fontSize()); } catch (Throwable ignored) {}
        }
    }

    public void applyWebDark(Context c, WebView w) {
        // v1.3.39 PER-SITE DARK (menu-3 #5): the site's override wins over
        // the global dark-web toggle (siteBool "dark"; unset = global).
        // v1.3.91 — THE CSS ENGINE OWNS WEB DARK, ALWAYS. The native
        // paths are retired: setForceDark is a no-op for apps targeting
        // 33+ (the platform ignores it), and setAlgorithmicDarkeningAllowed
        // — the 33+ path the v1.3.81 era leaned on — is EXACTLY the
        // "inverts images and defining designs" + "double-inverts sites
        // with good dark modes" the v1.3.90 field report describes:
        // Chromium's auto-darkener rewrites colors itself, has NO
        // already-dark detection, and cannot be gated per-page-state. The
        // CSS inversion (MediaHook.darkFallbackCss) gated by the SMART
        // PROBE (darkProbeJs: bg luminance, color-scheme, meta
        // theme-color, theme ATTRIBUTES, text-color test, element
        // sampling) runs on EVERY API level and every theme — one
        // engine, one law: probe first, invert only a LIGHT page, never
        // touch images (the media exclusions + the bg-image scan + the
        // MutationObserver keep photos positive).
        // Both setters are now always OFF so no WebView ever carries a
        // second darkener under ours.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                w.getSettings().setAlgorithmicDarkeningAllowed(false);
            } catch (Throwable ignored) {}
        } else if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                w.getSettings().setForceDark(WebSettings.FORCE_DARK_OFF);
            } catch (Throwable ignored) {}
        }
        // v1.4.17 — THE DARK CANVAS (the white-flash report's native leg).
        // The report's proposed cure — setForceDark(FORCE_DARK_ON) plus
        // setAlgorithmicDarkeningAllowed(true) — is the exact pair this
        // method has retired since v1.3.81/v1.3.91, for documented
        // reasons: setForceDark is IGNORED outright for apps targeting
        // API 33+ (Bolt targets 34 — the call is a no-op on every device
        // this build can install on), and the algorithmic darkener has
        // no already-dark detection — it double-darkens sites that ship
        // their own dark mode and inverts images and defining designs
        // (the v1.3.90/v1.3.91 field reports). The white flash it was
        // asked to cure is real, though, and the honest leg is the CANVAS:
        // Fx.webBg follows the APP theme, so a per-site dark override on a
        // light theme (or any dark-web navigation before first paint)
        // opened on a light canvas. While the dark-web engine is on for
        // THIS tab's domain, the unpainted WebView surface is the skin's
        // dark base (Fx.webBgDark); otherwise the theme-aware webBg stands
        // (the v1.3.33 ghosting fix, unchanged). The blank canvas is the
        // ONLY thing this colors — once the page paints, its own colors
        // own the surface.
        try {
            boolean siteDark;
            try {
                String base = Fx.baseDomain(Fx.hostOf(url == null ? "" : url));
                Prefs p = Prefs.get(c);
                siteDark = p.siteBool(base, "dark", p.webDark());
            } catch (Throwable t) {
                siteDark = Prefs.get(c).webDark();
            }
            w.setBackgroundColor(siteDark ? Fx.webBgDark(c) : Fx.webBg(c));
        } catch (Throwable ignored) {}
    }

    public void capture() {
        if (web != null && !isHome) {
            snap = Fx.screenshot(web);
        } else if (homeView != null) {
            snap = Fx.screenshot(homeView);
        }
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("url", url == null ? "" : url);
            o.put("title", title == null ? "" : title);
            o.put("group", groupId);
            o.put("home", isHome);
            // v1.4.8: the tab id — the anchor keys reference it, so it must
            // survive restarts (a restored tab keeps its identity; the
            // constructor's fresh id is only a legacy-save fallback)
            o.put("tid", tid);
            // v1.3.31: sort keys ("sort by newest opened / last visited")
            if (createdAt > 0) o.put("ca", createdAt);
            if (lastVisitedAt > 0) o.put("lv", lastVisitedAt);
            // v1.3.88: the download-repair pair persists ONLY while the tab
            // is genuinely stranded (url == dlUrl) — a kill mid-download
            // then restores the real page (TabManager's constructor swap)
            // instead of rebuilding onto the dead single-entry blank.
            if (dlUrl != null && dlFromUrl != null && dlUrl.length() > 0
                    && dlFromUrl.length() > 0 && url != null && dlUrl.equals(url)) {
                o.put("dlu", dlUrl);
                o.put("dlf", dlFromUrl);
            }
            return o;
        } catch (Throwable e) {
            return new JSONObject();
        }
    }
}
