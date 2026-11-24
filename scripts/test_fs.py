#!/usr/bin/env python3
"""v1.3.60 fullscreen-covenant regression test (THE 'zoomed-in view instead
of true fullscreen' + 'picture in picture is fully broken' field report).

Field history: the v1.3.59 report named two failures on YouTube DESKTOP
view -- floating (userscript) overlays staying visible "in fullscreen",
and fullscreen sometimes being a zoomed-in view instead of the real thing
-- plus PiP fully broken. One mechanism produces all three symptoms:

THE PHANTOM FULLSCREEN. When the ACTIVITY dies while a video is
element-fullscreen (closing the PiP window with its X, an OEM kill, a
crash, a legit recreate), nothing ever told the RENDERER its fullscreen
view was gone: onDestroy never invoked CustomViewCallback.
onCustomViewHidden(). The WebView survives (the app-scoped TabManager
keeps it; zombie adoption re-attaches it), so the page comes back with
document.fullscreenElement STILL set -- the player keeps its fullscreen
styling inside the (desktop-UA, overview-scaled) WebView = the
"zoomed-in view"; page-level floating overlays (userscript panels) ride
along; and because the APP side has customView == null, the first
fullscreen tap is consumed just exiting the phantom while PiP
eligibility (customView != null, the v1.3.41 video-only rule) never
arms. One poisoned session makes both features look dead.

The fullscreen API itself was verified INTACT before this fix (the
shipped v1.3.59 dex keeps BoltChrome.onShowCustomView +
onHideCustomView with code, so WebView's doesSupportFullscreen()
reflection passes and document.fullscreenEnabled is true).

v1.3.60 fixes, pinned by this gate:
  1. THE ORPHAN EXIT -- onDestroy, when customView != null: remove the
     view, notify the renderer (onCustomViewHidden), clear the fields.
     Playback is untouched (the zombie/phoenix paths keep working).
  2. THE HEALER -- healRendererFullscreen(w, where): when the app has NO
     customView, probe document.fullscreenElement; a set value is a
     phantom -> document.exitFullscreen() + 'fs heal' diag. Wired at
     showTab (covers zombie adoption + every switch) and onPageFinished.
     Re-checks customView == null at ANSWER time (never exit a real
     fullscreen that started between probe and callback).
  3. THE FLOATING-SURFACE SWEEP -- onShowCustomView now also hides the
     runtime-added overlays (quick panel, OTP/pw/profile chips) that
     attach to flRoot AFTER the customView and would paint ABOVE the
     video; hideCustomViewInternal re-asserts the media FAB's rule.
  4. EVIDENCE LINES -- 'fs show' / 'fs hide' / 'fs heal' / 'fs orphan'
     so the next field report can tell "the API never fired" (no fs
     show) apart from "it fired and something else went wrong".
  5. PiP WIRING UNTOUCHED -- the v1.3.41 video-only eligibility and the
     v1.3.38 auto-enter params keep their exact semantics (they were
     correct; only the phantom starved them of a customView).

v1.3.62 round 3 (the SAME field report, unchanged after v1.3.61 -- the
user was asked to check ONLINE sources; the Chromium source was read and
the mechanism found, see below). Round 2 (v1.3.61) pinned the viewport
decoupling, which turned out to be a NO-OP: useWideViewPort maps to a
LOAD-TIME quirk preference (aw_settings.cc) never re-applied to a live
document. THE REAL MECHANISM (verified in third_party/blink/renderer/core/
frame/fullscreen_controller.cc): a GRANTED requestFullscreen only resolves
when the next VisualPropertiesUpdated reaches the renderer -- DidEnterFull-
screen is what applies the fullscreen constraints (page scale 1, layout =
view size) AND moves the element into the DOM top layer, and its own TODO
admits early-outs can leave the promise hanging. An unsettled enter wedges
the controller in kEnteringFullscreen: re-requests AND exits are ignored,
the app-side custom view stays up showing the RAW page at its desktop
overview/pinch scale (the "zoomed-in view"), with NO top layer (userscript
page-DOM overlays ride the video), and the PiP shrink inherits the broken
geometry. Fixes, pinned by this gate:
  11. THE SETTLE KICK -- two scheduled 1-px padding nudges on the custom
      view force a real size change through FullScreenView ->
      SynchronizeVisualProperties -> VisualPropertiesUpdated -> the settle.
  12. THE SETTLE PROBE + CSS RESCUE -- after the kicks, document.fullscreen
      -Element is probed while the custom view is up (evidence fs fsel=);
      a still-wedged state gets a scoped class pinning the largest playing
      video to the viewport (fixed/100vw/100vh/max z-index/contain) so the
      wedge looks like true fullscreen at ANY window size incl. PiP.
  13. THE RACE-FREE RE-ACCEPT -- the stale-view teardown no longer notifies
      the old renderer (the async exit reply could tear the NEW fullscreen
      down); the healer owns the renderer side.
  14. THE PiP KICK -- the same nudge at onPictureInPictureModeChanged re-
      syncs the renderer at the shrunk bounds (the stale-surface half-video
      render).
  15. THE VIDEO PROBE -- aspect + scroll capture with a LARGEST-video
      fallback (an ad/preview must not hand PiP a wrong aspect).

Round 4 (v1.3.63 -- the v1.3.62 field report, 4 issues). The v1.3.62
log itself named the mobile killer: `fs pip aspect=89/50` at 14:46:51.824
then `fs hide` at +263 ms -- the PiP KICK was scheduled at +250 ms (fs hide
ONE FRAME after it; v1.3.61, same moment without the kick, kept the view
12 s). A resize at the shrunk size makes the MOBILE player (fullscreen-
Element = the player) exit element fullscreen, so the browser chrome
painted into the floating window ("the pip shows only the browser
elements"). Desktop <html> fullscreen ignored the nudge AND kept the
broken geometry -- the kick bought nothing. Fixes, pinned by this gate:
  16. THE PiP KICK IS BANNED -- no setPadding nudge may return to
      onPictureInPictureModeChanged (it killed the mobile custom view).
  17. PiP ASPECT = THE LIVE WINDOW -- pipAspectNow(): the laid-out custom
      view's own pixel size (clamped, 16:9 fallback) feeds BOTH PiP paths;
      the v1.3.60/62 half-video geometry was the 20:9 activity vs the
      16:9 armed window -- a non-uniform shrink Chromium's own rwhva.cc
      warns about. Even a stale surface then renders as a correct scaled
      snapshot. Re-armed at the 300 ms settle kick (pre-layout arm fell
      back to 16:9).
  18. THE IN-PIP DEATH PATH -- hideCustomViewInternal with inPip set keeps
      the chrome hidden and PINS the largest playing video (forced CSS
      rescue) so a page-side fullscreen exit inside the floating window
      still shows the video, not the toolbars; the expand-back retires
      the pin.
  19. DOCUMENT-FULLSCREEN OVERLAY CONCEALMENT -- desktop YouTube full-
      screens <html>, so body-level userscript panels (z 2147483647, the
      Tampermonkey convention) are INSIDE the fullscreen element and the
      top layer cannot cover them ("the script overlay does make it into
      the fullscreen still"). MediaHook now hides body/documentElement
      children with computed position fixed|absolute AND z >= 100000000
      while document-level fullscreen is live (restored on exit);
      window.__boltFsSweep lets Java force/clear it; the rescue z is
      raised to the true max 2147483647 and its apply/clear wire the
      sweep.
(The notification seek-sync fix of the same report lives in
scripts/test_notifsync.py -- MediaControlService territory.)

Round 7 (v1.3.66 -- the v1.3.65 field report: desktop PiP WORKS PERFECTLY
("well done, a lot of the fixes worked and the pip works somewhat ... it
works perfectly in desktop site"), mobile does not: "in mobile site I
cannot keep the video playing, it pauses just as I play it. also the built
in voice booster floating button makes it into the pip view, which it
should not (it happens only in mobile view, desktop view it does not
happen)"; "the video now do take up the entire pip view" CONFIRMED). The
log decoded both: 'fs pip pin=1 player=0' on m.youtube.com vs 'player=1'
on desktop -- the ±15px box-tracking walk failed on mobile, the BARE
video got pinned, and the mobile player (its box collapsed under the
fixed child) reverses every play through its own size/visibility logic;
the FAB re-show fired on the playing-state flip the mobile pause+re-play
produces, with no inPip/customView guard. Fixes, pinned by this gate:
  29. THE KNOWN-PLAYER FAST PATH -- PipVideo.pinJs matches
      .html5-video-player/#movie_player/ytm-video-player/ytd-player via
      closest BEFORE the walk (desktop pins the same element it already
      found; mobile gets the PLAYER pin the walk could not give it);
  30. THE PiP PLAY SENTINEL -- pinJs(pipMode=true) arms
      window.__boltpip (unpinJs disarms; unpinBody must NOT -- the re-pin
      cycle inlines it and would cancel the arming, a bug the mock suite
      caught); MediaHook pipFight re-plays a NON-USER pause of the active
      element while the flag is up, bounded (5 tries), each try a
      'pipfight' diag event -> 'fs pip fight n=' evidence;
  31. THE PLAYER-API PLAY -- __boltCmd toggle/play/pause/stop also drive
      playVideo()/pauseVideo() on the known player when exposed (the
      state-machine-sanctioned play the player cannot reverse);
  32. THE FAB GUARD -- the media FAB is chrome: it must never re-show
      inside the PiP window or under a fullscreen custom view (the state
      flip + onResume guards) and PiP enter hides it outright (it can be
      live when PiP is entered from an inline playing video).

Round 6 (v1.3.65 -- the v1.3.64 field report: PiP "still broken ... clearly struggling to identify the media from the ui background"; the user's directive: IMPORT a working PiP instead of fixing the broken one, video-only + close/fullscreen/play-pause controls; NEW: "the top toolbar disappeared after I returned back to bolt from the pip view. In fullscreen browsing mode"). Two proven open-source designs were imported: the "Maximize Video" userscript's page takeover (a fixed box resolves against ANY transformed/contained ancestor -- the v1.3.62-64 bare-video pin rendered inside the ANCESTOR's box, which IS the half-video-plus-page-UI symptom; the fix neutralizes EVERY ancestor, pins the PLAYER, overflow-hides html/body, adds 1px edge strips) as new util/PipVideo.kt, and Google's canonical PiP sample's window+controls (RemoteAction setActions: play/pause toggle via the service's CMD_TOGGLE + fullscreen via the task's own activity; the VIDEO's aspect owns the window). The behavioral coverage of the engine itself lives in scripts/test_pipvideo.py + test_pipvideo.js (extracted-from-Kotlin mock-DOM suite). Fixes, pinned by this gate:
  25. THE IMPORTED ENGINE -- fsApplyCssRescue delegates to PipVideo.pinJs
      and fsRemoveCssRescueNow to PipVideo.unpinJs; the engine carries the
      ancestor neutralizer + player pin + strips + flicker-free beat;
  26. THE VIDEO OWNS THE WINDOW -- pipAspectNow arms fsVideoAspect first
      (the v1.3.63 window-aspect bet is deliberately inverted: the takeover
      pin owns the render at ANY size, so the video aspect no longer costs
      a non-uniform shrink -- and it stops letterboxing ~20% of the box);
  27. THE IMPORTED CONTROLS -- PipVideo.params/actions wired on every path
      (enter, 31+ arming, in-PiP refresh) with CMD_TOGGLE + ic_ntf_expand
      + REORDER_TO_FRONT; 'fs pip ctl=' evidence; state flips refresh the
      icon while in PiP;
  28. THE EXPAND-BACK TOOLBAR RESTORE -- onPictureInPictureModeChanged
      (false) with customView == null restores the chrome VISIBILITY the
      in-PiP death path left GONE (showBarsNow only moves TRANSLATION; a
      translation on a GONE view paints nothing).

Round 5 (v1.3.64 -- the v1.3.63 field report: desktop PiP "only top
half of the horizontal video ... with rest just being the youtube search
bar or other youtube ui"; mobile PiP "refuses to play at all, I press
play, it stops instantly" + same page-UI-in-box; plus the site-settings
menu UX: "options that change the website view drastically ... I would
like the menus to close automatically"). The v1.3.63 reactive design bet
on the page's own fullscreen discipline surviving the shrink, and lost on
BOTH sites -- the page re-lays out its normal chrome into the floating
window, and the death-path pin needed a PLAYING video exactly when the
site had paused it. The mobile play-stop was the lifecycle: onPause keys
the keep-alive on the background-play pref / site opt-outs only -- PiP is
a WATCHING state and must be foreground-equivalent for the media tab.
Fixes, pinned by this gate:
  20. THE PiP TAKEOVER -- the video is pinned AT PiP ENTER (playing OR
      paused-with-frame, largest first), not after the custom view dies;
      the floating window's every pixel belongs to the video whatever the
      page does with its fullscreen state.
  21. THE PIN WATCHDOG -- pipPinBeat re-asserts pin + sweep every 900 ms
      for the whole PiP session (player DOM re-creation, ad switches,
      page-side fullscreen exits); stops at expand-back / death.
  22. THE GUARDED RETIRE -- fsRemoveCssRescue is a no-op while inPip (the
      1600 ms settle probe + tab/page retires must not unpin the floating
      window); fsRemoveCssRescueNow is the unconditional retire used by
      the expand-back and onDestroy (dying in PiP must unpin the zombie).
  23. PiP IS A WATCHING STATE -- onPause keeps the media/holds-media tab's
      WebView resumed + engages the visibility spoof whenever inPip or a
      fullscreen custom view is up at pause time (it fires BEFORE the
      PiP-mode callback on both paths), regardless of bgPlay/noBg.
  24. THE MENU AUTO-CLOSE -- openNewTab and wipeSiteData dismiss all
      overlays (drastic view changes must be SEEN, with immediate toast
      feedback for the wipe).
(The v1.3.63 notification seek-sync of the same family lives in
scripts/test_notifsync.py -- untouched and still green.)

Round 2 (v1.3.61) historical notes (the mobile-site control group): the only
structural difference between the sites in Bolt is the VIEWPORT (mobile
pages carry a device-width viewport meta at page scale 1; desktop pages
lay out against the ~980 CSS-px wide viewport at an overview/pinch
scale), and WebView's HTML fullscreen re-parents the WHOLE page surface
(Chromium FullScreenView), so the fullscreen element renders against
that SAME scaled viewport. Fixes, pinned by this gate:
  6. THE VIEWPORT DECOUPLING -- armFullscreenViewportAndAspect(): while
     the custom view owns the screen the OWNING WebView drops
     useWideViewPort + loadWithOverviewMode (live settings -- the latter
     performs resetScrollAndScaleState in the glue), so the fullscreen
     element fills the view at scale 1 exactly like the mobile site,
     at ANY window size (fullscreen AND the PiP shrink). Applied ONLY
     to pages without a viewport meta (the probe) -- mobile pages are
     bit-for-bit untouched. Scroll saved before, restored after.
     restoreFullscreenViewport() at hideCustomViewInternal + onDestroy
     (the WebView SURVIVES the activity -- a narrowed zombie would hand
     the next session a phone-width desktop page).
  7. DEFENSIVE RE-ACCEPT -- onShowCustomView with a STALE customView
     used to REJECT the new view, leaving the site's request dead and
     its player CSS-maximized ("pseudo fullscreen"); it now tears the
     stale one down (exitFullscreenVideo notifies the old renderer) and
     ACCEPTS the new view. Only a refused teardown still rejects.
  8. PiP ASPECT + CLEAN RELAYOUT -- enterPipIfEligible/updatePipParams
     carry setAspectRatio (the probed video aspect, 16:9 fallback,
     clamped to 2.39:1..1:2.39) instead of letting the system take the
     activity's stale aspect (the off-center half-video letterbox);
     onPictureInPictureModeChanged re-asserts match_parent + requestLayout
     so the custom view re-measures at the shrunk bounds.
  9. PSEUDO-FULLSCREEN WATCHDOG -- MediaHook reports a playing video
     covering ~the whole viewport while document.fullscreenElement is
     null ('pseudo' 1/0, edge-triggered); JsBridge sweeps Bolt's floating
     chrome (bars + panel + chips + FAB) out of the video and restores
     it when the state ends. Plus 'fserr' (fullscreenerror) evidence so
     the next report can name a REJECTED requestFullscreen at a glance.
 10. HEAL-AT-PLAY -- the v1.3.60 heal moments (showTab/pageFinished) all
     sit in the PAST of an SPA document that never reloads; a state
     report (a new play) now re-runs the healer, debounced to 1/3 s.

Also runs a BEHAVIORAL mirror of the fullscreen lifecycle state machine
(app customView x renderer fullscreenElement) proving: with the v1.3.60+
rules no phantom state survives any reachable event sequence, while the
v1.3.59 rules leave the phantom stuck after an activity death (the
negative case that produced this field report).
"""
import os, re, sys, pathlib

BASE = pathlib.Path(os.environ.get(
    "BOLT_TEST_BASE",
    # v1.4.10 re-point (2026-11-17): the private bolt-light-browser checkout is gone — gates read the PUBLIC source checkout
    "/home/z/my-project/bolt-browser"
    "/app/src/main/java/com/bolt/browser"))
failures = []


def rd(p):
    return (BASE / p).read_text(errors="ignore")


def strip_comments(text):
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text


def body_of(src, start_marker, end_marker):
    """slice [start_marker .. end_marker) from the source"""
    i = src.find(start_marker)
    if i < 0:
        return ""
    j = src.find(end_marker, i)
    return src[i:j] if j > i else src[i:]


# ============================================================
# PART 1 — static pins on BrowserActivity.java (the code, not comments)
# ============================================================
ba = strip_comments(rd("ui/BrowserActivity.java"))

# ---- 1a. onShowCustomView: the floating-surface sweep ----
show = body_of(ba, "public void onShowCustomView(", "public void onHideCustomView()")
if not show:
    failures.append("onShowCustomView not found")
for m in ("hideQuickPanel", "hideOtpChip", "hidePwChip", "hideProfileChip"):
    if m + "(" not in show:
        failures.append("onShowCustomView no longer hides " + m +
                        " -- runtime-added flRoot children paint ABOVE "
                        "the customView (the 'floating overlay in "
                        "fullscreen' report)")
if "fs show" not in show:
    failures.append("onShowCustomView lost the 'fs show' evidence line")
if "updatePipParams()" not in show:
    failures.append("onShowCustomView no longer arms PiP params at "
                    "fullscreen-video enter (the v1.3.41 rule)")

# ---- 1b. hideCustomViewInternal: FAB restore + evidence ----
hide = body_of(ba, "protected void hideCustomViewInternal(",
                     "protected void healRendererFullscreen(")
if not hide:
    failures.append("hideCustomViewInternal not found")
if "fabMedia.setVisibility(" not in hide:
    failures.append("hideCustomViewInternal no longer re-asserts the "
                    "media FAB rule on exit")
if "fs hide" not in hide:
    failures.append("hideCustomViewInternal lost the 'fs hide' evidence line")
if "updatePipParams()" not in hide:
    failures.append("hideCustomViewInternal no longer disarms PiP params "
                    "at fullscreen-video exit")

# ---- 1c. the healer: probe + exit + double guard + diag ----
heal = body_of(ba, "protected void healRendererFullscreen(",
                   "protected void hideSystemBars(")
if not heal:
    failures.append("healRendererFullscreen not found")
if "document.fullscreenElement" not in heal:
    failures.append("healRendererFullscreen does not probe "
                    "document.fullscreenElement")
if "document.exitFullscreen()" not in heal:
    failures.append("healRendererFullscreen does not call "
                    "document.exitFullscreen()")
if "customView != null" not in heal:
    failures.append("healRendererFullscreen lost the entry guard "
                    "(customView != null -> return): a real fullscreen "
                    "video or PiP must never be healed away")
if 'if ("1".equals(v) && customView == null)' not in heal:
    failures.append("healRendererFullscreen lost the ANSWER-time "
                    "customView re-check -- a real fullscreen that starts "
                    "between probe and callback would be killed")
if "fs heal" not in heal:
    failures.append("healRendererFullscreen lost the 'fs heal' evidence line")

# ---- 1d. healer wiring: showTab + onPageFinished ----
if 'healRendererFullscreen(w, "showTab")' not in ba:
    failures.append("healRendererFullscreen not wired at showTab (the "
                    "zombie-adoption heal moment)")
if 'healRendererFullscreen(view, "pageFinished")' not in ba:
    failures.append("healRendererFullscreen not wired at onPageFinished "
                    "(a load that lands while desynced)")
n_heal_calls = len(re.findall(r"healRendererFullscreen\(", ba))
if n_heal_calls < 3:   # definition + 2 wiring sites
    failures.append("healRendererFullscreen wiring shrank: expected at "
                    "least 3 references (def + showTab + pageFinished), "
                    "found %d" % n_heal_calls)

# ---- 1e. onDestroy: the orphan exit ----
od = body_of(ba, "protected void onDestroy(", "protected void destroyStaleWebViews(")
if not od:
    failures.append("onDestroy not found")
if "customView != null" not in od:
    failures.append("onDestroy no longer checks for an orphaned customView "
                    "-- dying while a fullscreen video is up is the "
                    "phantom's only source")
if "customCallback.onCustomViewHidden()" not in od:
    failures.append("onDestroy never notifies the RENDERER on death while "
                    "fullscreen (onCustomViewHidden) -- the phantom "
                    "fullscreen survives the activity")
if "fs orphan" not in od:
    failures.append("onDestroy lost the 'fs orphan' evidence line")

# ---- 1f. PiP semantics unchanged (the v1.3.41 / v1.3.38 rules) ----
pip_body = body_of(ba, "protected void enterPipIfEligible()",
                        "protected void updatePipParams()")
if "if (customView == null) return;" not in pip_body:
    failures.append("enterPipIfEligible lost the video-only gate "
                    "(customView == null -> return)")
if "enterPictureInPictureMode(" not in pip_body:
    failures.append("enterPipIfEligible no longer enters PiP")
# v1.3.65: setAutoEnterEnabled moved into PipVideo.params (SDK>=31 guarded
# there); the SEMANTICS stay pinned on both sides — Java still computes
# autoPip && customView and passes it, Kotlin still arms it on 31+
if "prefs.b(\"autoPip\", true) && customView != null" not in ba \
        or "setAutoEnterEnabled(autoEnter)" not in rd("util/PipVideo.kt"):
    failures.append("updatePipParams lost the autoEnter = autoPip && "
                    "customView semantics")

# ---- 1g. v1.3.62 DEFENSIVE RE-ACCEPT, RACE-FREE: a stale customView must
#          be torn down WITHOUT notifying (the v1.3.61 path notified via
#          exitFullscreenVideo -> onCustomViewHidden -> the renderer's exit
#          is a MOJO round trip -> glue onHideCustomView could land AFTER the
#          new view was accepted and tear THE NEW fullscreen down) and the
#          new view ACCEPTED. The healer owns the renderer side afterwards.
if "fs restale" not in show:
    failures.append("onShowCustomView lost the 'fs restale' evidence line "
                    "(a stale view was torn down and re-accepted)")
if "exitFullscreenVideo()" in show:
    failures.append("onShowCustomView re-accept still tears the stale view "
                    "down via exitFullscreenVideo -- the async renderer exit "
                    "reply can kill the NEW view (the fullscreen-flashes-and-"
                    "dies race)")
if show.count("callback.onCustomViewHidden()") > 0:
    failures.append("onShowCustomView still rejects via onCustomViewHidden -- "
                    "every rejection path is a dead site request")
if "customView = null" not in show:
    failures.append("onShowCustomView re-accept does not clear the stale "
                    "customView before accepting the new one")

# ---- 1h. v1.3.62/v1.3.91 THE SETTLE: aspect-driven scheduling ----
# v1.3.91: the kicks + probe moved OUT of onShowCustomView into
# fsEnterAfterAspect (the Shorts-flicker contract: the orientation follows
# the VIDEO, a coming rotation IS the settle so no kicks, a no-rotation
# enter kicks ONCE + probes at 800 ms; a 650 ms fallback clock decides if
# the aspect probe stalls). The enter itself must hold the current posture
# until the aspect answers and arm the fallback.
if "probeFullscreenVideo()" not in show:
    failures.append("onShowCustomView does not run probeFullscreenVideo "
                    "(the video-aspect + scroll probe never fires)")
if "SCREEN_ORIENTATION_LOCKED" not in show or "fsAwaitAspect = true;" not in show:
    failures.append("onShowCustomView does not LOCK the posture while the "
                    "aspect probe answers (the v1.3.91 Shorts contract)")
enter = ba.split("protected void fsEnterAfterAspect")[1][:3000] if "protected void fsEnterAfterAspect" in ba else ""
if "fsSettleKick(cv);" not in enter:
    failures.append("fsEnterAfterAspect never kicks a no-rotation enter "
                    "(the wedged-enter settle is unreachable)")
if "fsSettleProbe()" not in enter:
    failures.append("fsEnterAfterAspect never probes the settle state -- the "
                    "wedged-enter classifier (fs fsel=) goes silent")
if "SCREEN_ORIENTATION_SENSOR_PORTRAIT" not in enter:
    failures.append("fsEnterAfterAspect does not follow a portrait video "
                    "(Shorts/Reels stay portrait -- the Chrome law)")

# ---- 1i. v1.3.62 the viewport decoupling must be GONE (it was a no-op:
#          useWideViewPort is a load-time quirk pref, never re-applied to a
#          live document -- and its resetScrollAndScaleState fired mid-
#          handshake). Any live-settings toggle on the fullscreen path is a
#          regression risk with zero render effect.
if "armFullscreenViewportAndAspect" in ba:
    failures.append("armFullscreenViewportAndAspect still exists -- the "
                    "v1.3.61 viewport decoupling was proven a no-op and "
                    "must not ship again")
if "restoreFullscreenViewport" in ba:
    failures.append("restoreFullscreenViewport still exists (dead code from "
                    "the removed decoupling)")
# v1.4.29 re-point (2026-11-24): the related popup child (the Imgur Google
# sign-in fix, BrowserActivity.buildPopupChild) builds a FRESH WebView at
# popup time and gives it the same BUILD-time viewport quirks Tab.buildWeb
# gives every tab — quirk prefs are load-time there too (the child has no
# live document to re-evaluate them mid-page; the fullscreen law's target is
# the LIVE path only). The check now scopes out that builder body; any other
# viewport toggle in BrowserActivity still fails.
ba_scope = re.sub(r"private WebView buildPopupChild\(WebView opener\) \{[\s\S]*?private class PopupChildClient", "", ba)
if "setUseWideViewPort(" in ba_scope or "setLoadWithOverviewMode(" in ba_scope:
    failures.append("a live viewport-settings toggle appeared in "
                    "BrowserActivity -- quirk prefs do not re-run the "
                    "viewport policy of a live document (verified in "
                    "aw_settings.cc); toggling them mid-fullscreen is pure "
                    "risk")

# ---- 1i-2. v1.3.62 THE SETTLE KICK: a real 1-px size change through the
#            custom view (Chromium FullscreenController only resolves a
#            granted requestFullscreen on the next VisualPropertiesUpdated --
#            no size/inset change at enter = the wedge: no top layer, no
#            scale reset, exit AND re-request both ignored).
kick = body_of(ba, "protected void fsSettleKick(", "protected void fsSettleProbe(")
if not kick:
    failures.append("fsSettleKick not found")
else:
    for frag in ("setPadding(0, 0, 0, 1)", "setPadding(0, 0, 0, 0)",
                 "requestLayout()", "customView != v"):
        if frag not in kick:
            failures.append("fsSettleKick missing '" + frag + "' (the "
                            "guaranteed size change + the still-live guard)")

# ---- 1i-3. v1.3.62 THE SETTLE PROBE + CSS RESCUE: after the kicks, probe
#            document.fullscreenElement; fsel=1 -> retire the rescue; fsel=0
#            -> pin the largest playing video to the viewport so the wedge
#            still looks like fullscreen (video fills the screen, overlays
#            covered) at any window size incl. the PiP shrink.
probe = body_of(ba, "protected void fsSettleProbe(",
                    "protected void fsApplyCssRescue(")
if not probe:
    failures.append("fsSettleProbe not found")
else:
    for frag in ("document.fullscreenElement", "fs fsel=",
                 "fsRemoveCssRescue()", "fsApplyCssRescue(w, false, inPip)"):
        if frag not in probe:
            failures.append("fsSettleProbe missing '" + frag + "'")
rescue = body_of(ba, "protected void fsApplyCssRescue(",
                      "protected void fsRemoveCssRescue(")
if not rescue:
    failures.append("fsApplyCssRescue not found")
else:
    # v1.3.65: the engine JS moved to util/PipVideo.kt (the language rule);
    # this side keeps the wiring: the delegation, the guard, the sweep, the
    # evidence line
    if "PipVideo.pinJs(pipMode)" not in rescue:
        failures.append("fsApplyCssRescue no longer delegates to the imported "
                        "engine (PipVideo.pinJs) -- the v1.3.62-64 inline pin "
                        "is the half-video-plus-page-UI bug")
    if "boolean force" not in rescue or "customView == null && !force" not in rescue:
        failures.append("fsApplyCssRescue lost the force overload (the "
                        "in-PiP death path pins with customView == null)")
    if "__boltFsSweep" not in rescue:
        failures.append("fsApplyCssRescue no longer forces the page-side "
                        "overlay sweep (userscript panels ride the pin)")
    if "fs pip pin=" not in rescue:
        failures.append("fsApplyCssRescue lost the 'fs pip pin=' evidence line "
                        "(one per PiP session: did the takeover find a video)")
unrescue = body_of(ba, "protected void fsRemoveCssRescue(",
                        "protected void fsRestoreScroll(")
if not unrescue:
    failures.append("fsRemoveCssRescue not found")
else:
    if "PipVideo.unpinJs()" not in unrescue:
        failures.append("fsRemoveCssRescueNow no longer delegates to the "
                        "imported engine's full restore (PipVideo.unpinJs)")
    if "__boltFsSweep" not in unrescue:
        failures.append("fsRemoveCssRescue no longer clears the page-side "
                        "overlay sweep (hidden overlays would stay hidden "
                        "after the rescue retires)")

# ---- 1i-4. v1.3.62 rescue retirement wiring (v1.3.64 semantics): the
#            GUARDED fsRemoveCssRescue runs at the settle probe, the normal
#            hide, showTab and onPageStarted (a no-op while in PiP by
#            design); the UNCONDITIONAL fsRemoveCssRescueNow runs at the
#            expand-back and onDestroy (dying in PiP must unpin the zombie
#            WebView -- a leftover __boltfsf class would pin the next
#            session's video outside any fullscreen state).
n_plain = len(re.findall(r"fsRemoveCssRescue\(\)", ba))
if n_plain < 4:
    failures.append("fsRemoveCssRescue wiring shrank: expected >= 4 call "
                    "sites (settle-probe + hide + showTab + onPageStarted), "
                    "found %d" % n_plain)
if len(re.findall(r"fsRemoveCssRescueNow\(\)", ba)) < 3:
    failures.append("fsRemoveCssRescueNow wiring shrank: expected >= 3 "
                    "references (def + expand-back + onDestroy) -- these are "
                    "the two paths that MUST unpin even mid-PiP")
unrescue_head = body_of(ba, "protected void fsRemoveCssRescue()",
                        "protected void fsRemoveCssRescueNow(")
if "if (inPip) return;" not in unrescue_head:
    failures.append("fsRemoveCssRescue lost the inPip guard -- the 1600 ms "
                    "settle probe and the tab/page retires run INSIDE a PiP "
                    "session now; unpinning there hands the floating window "
                    "back to page UI (the v1.3.63 bug)")

# ---- 1i-5. v1.3.62 THE VIDEO PROBE: largest-video fallback (never the
#            first -- an ad/preview must not hand PiP a wrong aspect),
#            scroll capture for the exit restore, and NO viewport toggles.
vprobe = body_of(ba, "protected void probeFullscreenVideo(",
                      "protected void fsSettleKick(")
if not vprobe:
    failures.append("probeFullscreenVideo not found")
else:
    for frag in ("document.fullscreenElement", "videoWidth", "fsVideoAspect",
                 "fsScrollWeb = w", "updatePipParams()"):
        if frag not in vprobe:
            failures.append("probeFullscreenVideo missing '" + frag + "'")
    if "var ba=-1" not in vprobe:
        failures.append("probeFullscreenVideo lost the LARGEST-video "
                        "fallback (a first-match querySelector would hand "
                        "PiP an ad's or preview's aspect)")

# ---- 1i-6. v1.3.62 scroll restore at exit (DidEnterFullscreen zeroed the
#            visual-viewport offset; the reader lands back where they
#            fullscreened).
fscr = body_of(ba, "protected void fsRestoreScroll(",
                    "protected void hideSystemBars(")
if not fscr:
    failures.append("fsRestoreScroll not found")
else:
    if "window.scrollTo(" not in fscr or "fsSavedScrollY" not in fscr:
        failures.append("fsRestoreScroll does not restore the captured "
                        "scroll position")
if "fsRestoreScroll()" not in hide:
    failures.append("hideCustomViewInternal does not restore the page scroll "
                    "at fullscreen exit")

# ---- 1j. v1.3.61/63 PiP: window aspect + clean relayout at the shrunk bounds --
if "setAspectRatio(" not in pip_body:
    failures.append("enterPipIfEligible no longer sets an aspect "
                    "(the system was taking the activity's stale aspect -> "
                    "the off-center half-video letterbox)")
up = body_of(ba, "protected void updatePipParams()",
                 "public void onPictureInPictureModeChanged(")
# v1.3.65: setAspectRatio lives in PipVideo.params now; every Java path
# must build the window through it (aspect + actions stay one unit)
if "PipVideo.params" not in up or "pipAspectNow()" not in up:
    failures.append("updatePipParams no longer builds the window through "
                    "PipVideo.params(this, pipAspectNow(), ...) -- the "
                    "aspect and the control actions are one unit now")
if "fsVideoAspect" not in up and "fsVideoAspect" not in pip_body:
    failures.append("no PiP path arms the probed VIDEO aspect -- v1.3.65 "
                    "deliberately INVERTED the v1.3.63 window-aspect bet: "
                    "the takeover pin owns the render at any window size, "
                    "so the video aspect is free and stops letterboxing "
                    "~20% of the box")
pipc = body_of(ba, "public void onPictureInPictureModeChanged(",
                   "protected void onDestroy(")
for frag in ("setLayoutParams(new FrameLayout.LayoutParams(-1, -1))",
             "requestLayout()", "bringChildToFront(customView)", "fs pip"):
    if frag not in pipc:
        failures.append("onPictureInPictureModeChanged missing '" + frag +
                        "' (the clean relayout at the shrunk bounds)")
# v1.3.63 THE PiP KICK IS BANNED: the v1.3.62 field log caught it killing
# the MOBILE custom view (fs hide one frame after the +250 ms nudge; the
# browser chrome then painted into the floating window). It bought nothing
# on desktop either (the geometry came from the aspect mismatch, fixed by
# pipAspectNow).
if "setPadding(" in pipc:
    failures.append("onPictureInPictureModeChanged still nudges the custom "
                    "view (the v1.3.62 PiP kick) -- it killed the mobile "
                    "custom view and fixed nothing; the geometry fix is "
                    "pipAspectNow, not a padding nudge")
# v1.3.63: leaving PiP must retire the video-pin rescue (the in-PiP death
# path may have applied it)
if "fsRemoveCssRescue()" not in pipc and "fsRemoveCssRescueNow()" not in pipc:
    failures.append("onPictureInPictureModeChanged(false) no longer retires "
                    "the CSS rescue -- an expanded activity must never keep "
                    "a viewport-pinned video")
if "fsRemoveCssRescueNow()" not in pipc:
    failures.append("onPictureInPictureModeChanged(false) must call the "
                    "UNCONDITIONAL fsRemoveCssRescueNow (inPip is already "
                    "false there, but the guarded variant must never be the "
                    "expand-back's unpin)")
# v1.3.63: the in-PiP death path returns early and skips the normal
# immersive teardown -- the expand-back must reset the window flags itself
# (a landscape-locked, keep-screen-on expanded page would be a new bug)
if "SCREEN_ORIENTATION_UNSPECIFIED" not in pipc \
        or "FLAG_KEEP_SCREEN_ON" not in pipc:
    failures.append("onPictureInPictureModeChanged(false) does not reset the "
                    "immersive window flags left by the in-PiP death path "
                    "(landscape lock + keep-screen-on would leak into the "
                    "expanded page)")

# ---- 1t. v1.3.65 THE IMPORTED ENGINE (util/PipVideo.kt): the Maximize-
#          Video-grade takeover. THE CONTAINING-BLOCK FIX is the headline:
#          neutralize EVERY ancestor (transform/contain/filter none, z-index
#          auto, overflow visible, fixed -> absolute) so position:fixed means
#          THE VIEWPORT; pin the PLAYER (the ancestor whose box tracks the
#          video's); html/body overflow hidden; 1px edge strips; the videos
#          under the pinned player forced to fill it.
try:
    pipv = rd("util/PipVideo.kt")
except Exception:
    pipv = ""   # absent (pre-v1.3.65 source): every engine pin fails below
for frag in ("overflow:hidden", ".__boltpva", "transform:none",
             "contain:none", "filter:none", "z-index:auto",
             ".__boltpvaf{position:absolute", ".__boltpvp{position:fixed",
             "100vw", "100vh", "object-fit:contain",
             "z-index:2147483647", "background-color:#000",
             ".__boltpvp video", "width:100%", ".__boltpve",
             "__boltpvl", "__boltpvr", "offsetWidth-best.offsetWidth"):
    if frag not in pipv:
        failures.append("PipVideo.kt missing the takeover piece '" + frag +
                        "' (the containing-block fix / the player pin / the "
                        "edge strips / the fill rule / the player walk)")

# ---- 1u. v1.3.65 THE IMPORTED CONTROLS (Google's canonical PiP sample):
#          RemoteActions on every window path. Play/pause = the service's
#          own CMD_TOGGLE PendingIntent (audio focus + un-conceal + renderer
#          wake ride there); fullscreen = the task's own activity
#          (NEW_TASK|REORDER_TO_FRONT); the icons are the SELF-COLORED
#          ic_ntf_* family (SystemUI inflates them against ITS theme).
for frag in ("setActions(", "RemoteAction", "CMD_TOGGLE",
             "ic_ntf_play", "ic_ntf_pause", "ic_ntf_expand",
             "FLAG_ACTIVITY_REORDER_TO_FRONT", "setAutoEnterEnabled"):
    if frag not in pipv:
        failures.append("PipVideo.kt missing the window/controls piece '" +
                        frag + "' (the RemoteAction control set / the "
                        "expand path / the 31+ auto-enter arming)")
if "updatePipActions" not in pipc:
    failures.append("onPictureInPictureModeChanged(true) does not arm the "
                    "window actions (updatePipActions) -- the controls must "
                    "exist the moment the window does")
if "fs pip ctl=" not in pipc:
    failures.append("the 'fs pip ctl=' evidence line is gone (the next "
                    "report's decode line for the controls)")
if "updatePipActions" not in up:
    failures.append("updatePipActions not defined next to updatePipParams "
                    "(the in-PiP action refresh: the play/pause icon "
                    "follows the state while the window is up)")
jbstate = body_of(ba, '"state".equals(name)', '"time".equals(name)')
if not jbstate:
    jbstate = ba  # fall back to the whole file for this one wiring pin
if "if (inPip) updatePipActions();" not in jbstate:
    failures.append("media state flips no longer refresh the PiP actions "
                    "while in PiP (a stale play/pause icon)")

# ---- 1v. v1.3.65 THE EXPAND-BACK TOOLBAR RESTORE: the in-PiP death path
#          keeps the chrome VISIBILITY-gone; the expand-back must mirror
#          the normal hide-path restore (visibility first -- showBarsNow
#          only moves TRANSLATION, and a translation on a GONE view paints
#          nothing = the "toolbar disappeared after returning from pip").
leave_pip = pipc[pipc.find("} else {"):] if "} else {" in pipc else ""
for frag in ("R.id.top_group", "R.id.topbar", "R.id.bottombar",
             "setVisibility(View.VISIBLE)", "applySkinChrome()"):
    if frag not in leave_pip:
        failures.append("onPictureInPictureModeChanged(false) missing '" +
                        frag + "' -- the expand-back toolbar restore (the "
                        "v1.3.64 'toolbar disappeared' bug)")

# ---- 1w. v1.3.66 THE MOBILE PLAYER PIN + THE PiP PLAY SENTINEL + THE FAB
#          GUARD. Desktop works; mobile's 'player=0' bare-video pin is the
#          difference (the collapsed player reverses plays), and the FAB
#          re-show on the state flip painted the booster button into the
#          floating window (the flip only happens on mobile: the video
#          pauses at the custom-view death and the user re-plays).
#  (a) the known-player fast path: the engine pins the PLAYER on mobile
for frag in ("best.closest", ".html5-video-player,#movie_player",
             "ytm-video-player", "ytd-player", "kp.contains(best)"):
    if frag not in pipv:
        failures.append("PipVideo.kt missing the known-player fast-path "
                        "piece '" + frag + "' (the mobile 'fs pip pin=1 "
                        "player=0' bare-video pin -- the collapsed player "
                        "reversing every play)")
fast_path = body_of(pipv, "var pl=best", "// the three-layer") if pipv else ""
if "if(pl===best)" not in fast_path:
    failures.append("the box-tracking walk is no longer the FALLBACK -- the "
                    "fast path must fall through to it for generic pages")
#  (b) the sentinel flag: armed by the PIP selector only, disarmed by
#      unpinJs ONLY (the re-pin cycle inlines unpinBody -- a disarm there
#      cancels the arming on every full pin; the mock suite caught it)
if "window.__boltpip=1" not in body_of(pipv, "if (pipMode) {", "} else {"):
    failures.append("the PIP selector no longer arms window.__boltpip "
                    "(MediaHook.pipFight's gate)")
unpin_js_fn = body_of(pipv, "fun unpinJs()", "private fun unpinBody()")
unpin_body_fn = body_of(pipv, "private fun unpinBody()", "fun params(")
if "window.__boltpip=0" not in unpin_js_fn:
    failures.append("unpinJs does not disarm the PiP sentinel (pipFight "
                    "must stand down when the window is gone)")
if "window.__boltpip=0" in unpin_body_fn:
    failures.append("unpinBody disarms the sentinel -- the pin's own re-pin "
                    "cycle inlines it and would cancel the arming on every "
                    "full pin (the T11 ordering bug the mock suite caught)")
#  (c) MediaHook: pipFight (bounded, non-user, PiP-only) + ytApi (the
#      state-machine play) + the pause-branch hook
mh = strip_comments(rd("media/MediaHook.java"))
for frag in ("function pipFight", "window.__boltpip&&", "pipTries>7",
             "safePlay(el);ytApi(el,true)", "function ytApi",
             "playVideo", "pauseVideo", "pipHeld=Date.now()",
             "pipfight"):
    if frag not in mh:
        failures.append("MediaHook.java missing the PiP play-sentinel "
                        "piece '" + frag + "' (the mobile pause-on-play "
                        "fight / the player-API play / the evidence)")
cmd_fn = body_of(mh, "window.__boltCmd=function", "window.__boltSetMedia")
if "ytApi(v,true)" not in cmd_fn or "ytApi(v,false)" not in cmd_fn:
    failures.append("__boltCmd no longer drives the player API (the "
                    "state-machine play the mobile player cannot reverse)")
#  (d) the FAB guard: the state flip + onResume + PiP enter
if "!inPip" not in jbstate or "customView == null" not in jbstate:
    failures.append("the media state flip can still re-show the FAB inside "
                    "the PiP window / under a custom view (the booster-"
                    "button-in-pip report)")
resume_fn = body_of(ba, "protected void onResume()", "protected void onStop()")
if "!inPip" not in resume_fn:
    failures.append("onResume can still re-show the FAB while the PiP "
                    "window is up")
if "fabMedia.setVisibility(View.GONE)" not in pipc:
    failures.append("PiP enter does not hide the media FAB (it can be live "
                    "when PiP is entered from an inline playing video)")
#  (e) the pipfight evidence line
if '"pipfight".equals(name)' not in ba or "fs pip fight" not in ba:
    failures.append("the pipfight bridge + 'fs pip fight' evidence line are "
                    "gone (the next report's decode for the sentinel)")
if "fsPipFightSaid = false" not in pipc:
    failures.append("leaving PiP does not reset fsPipFightSaid (one fight "
                    "line per PiP session)")

# ---- 1m-2. v1.3.63 THE IN-PIP DEATH PATH: a custom view that dies while the
#          activity is shrunk must NOT restore the browser chrome into the
#          floating window (the v1.3.62 "pip shows only the browser elements"
#          symptom) -- it keeps the bars hidden and pins the largest playing
#          video instead; the expand-back retires the pin (pinned in 1j).
for frag in ("if (inPip)", "fsApplyCssRescue(", "hideBarsNow(false)",
             "fs hide inpip"):
    if frag not in hide:
        failures.append("hideCustomViewInternal missing the in-PiP death "
                        "path piece '" + frag + "' (chrome restored into "
                        "the floating window / no video pin / no evidence)")
hide_code = hide[hide.find("if (inPip)"):hide.find("if (inPip)") + 1200] \
    if "if (inPip)" in hide else ""
if "return;" not in hide_code:
    failures.append("the in-PiP death path does not return early -- the "
                    "normal chrome restore would still run inside PiP")
if ", true)" not in hide_code:
    failures.append("the in-PiP death path does not force the rescue past "
                    "the customView guard (customView is already null)")
if ", true, true)" not in hide_code:
    failures.append("the in-PiP death path does not use the PIP selector "
                    "(fsApplyCssRescue(..., true, true): playing OR "
                    "paused-with-frame -- the site often pauses the video in "
                    "the same breath it exits fullscreen)")

# ---- 1n. v1.3.63 pipAspectNow: the live window aspect helper ----
pan = body_of(ba, "protected android.util.Rational pipAspectNow(",
                   "protected void updatePipParams()")
if not pan:
    failures.append("pipAspectNow not found -- both PiP paths need the LIVE "
                    "window aspect (the uniform-shrink guarantee)")
else:
    for frag in ("cv.getWidth()", "cv.getHeight()", "clampVideoAspect(",
                 "Rational(16, 9)"):
        if frag not in pan:
            failures.append("pipAspectNow missing '" + frag + "'")
if "pipAspectNow()" not in pip_body:
    failures.append("enterPipIfEligible does not use pipAspectNow()")
# the delayed re-arm (the arm at enter can run pre-layout -> 16:9 fallback)
if show.count("updatePipParams()") < 2:
    failures.append("onShowCustomView no longer re-arms the PiP params at "
                    "the 300 ms settle kick -- an arm before the first "
                    "layout pass falls back to 16:9 (the mismatched aspect "
                    "again)")

# ---- 1o. v1.3.63 DOCUMENT-FULLSCREEN OVERLAY CONCEALMENT (MediaHook) ----
mh = rd("media/MediaHook.java")
for frag in ("__fsSweep", "__fsIsDoc", "__fsHidden", "__boltFsPrevVis",
             "window.__boltFsSweep", "100000000", "documentElement",
             "fullscreenchange"):
    if frag not in mh:
        failures.append("MediaHook lost the document-fullscreen overlay "
                        "concealment piece '" + frag + "' (desktop YT "
                        "fullscreens <html>: userscript panels are INSIDE "
                        "the fullscreen element and the top layer cannot "
                        "cover them)")

# ---- 1p. v1.3.64 THE PiP TAKEOVER + PIN WATCHDOG: the video is pinned at
#          PiP ENTER (not after the custom view dies) and re-asserted every
#          900 ms for the whole session -- the page's own fullscreen
#          discipline does not survive the shrink on either site, and a
#          reactive pin needs a PLAYING video exactly when the site has
#          just paused it.
pipc_norm = re.sub(r"\s+", " ", pipc)
if "fsApplyCssRescue(activeTab() == null ? null : activeTab().web, true, true)" \
        not in pipc_norm:
    failures.append("onPictureInPictureModeChanged(true) does not pin the "
                    "video AT ENTER (the PiP takeover: fsApplyCssRescue(..., "
                    "true, true) while the custom view is still up)")
if "pipPinBeat" not in pipc:
    failures.append("onPictureInPictureModeChanged does not wire the pin "
                    "watchdog beat (pipPinBeat)")
if "ui.postDelayed(pipPinBeat, 400)" not in pipc:
    failures.append("the pin watchdog is not armed at PiP enter (+400 ms "
                    "first beat)")
if "ui.removeCallbacks(pipPinBeat)" not in pipc:
    failures.append("leaving PiP does not stop the pin watchdog")
beat = body_of(ba, "protected final Runnable pipPinBeat",
                   "/** set while WE start another activity")
if not beat:
    # fall back: slice to the next field declaration
    beat = body_of(ba, "protected final Runnable pipPinBeat",
                        "protected boolean fsPipPinSaid")
if not beat:
    failures.append("pipPinBeat not found -- the in-PiP pin re-assert loop "
                    "is the whole takeover's durability")
else:
    for frag in ("if (!inPip) return;", "fsApplyCssRescue(w, true, true)",
                 "postDelayed(this, 900)", "fsSettleWeb != null"):
        if frag not in beat:
            failures.append("pipPinBeat missing '" + frag + "' (stops when "
                            "PiP ends / re-asserts with the pip selector / "
                            "900 ms cadence / follows the pinned WebView)")
    if "ui.postDelayed(this, 900)" in beat and "if (inPip) ui.postDelayed" not in beat:
        failures.append("pipPinBeat re-posts unconditionally -- it must stop "
                        "when the floating window is gone")

# ---- 1q. v1.3.64 THE PIP SELECTOR (fsApplyCssRescue pipMode) -- v1.3.65:
#          the selector moved into PipVideo.pinJs (pip vs strict variants);
#          pin it on the Kotlin source (pipv, loaded at 1t). The re-pin
#          cycle must keep exactly ONE pin live across player DOM churn
#          (tracked element + sweep).
for frag in ("fba", "if(!best)best=fb;", "v.paused||v.ended||!v.videoWidth",
             "__boltpvpEl", "classList.remove('__boltpvp')"):
    if frag not in pipv:
        failures.append("PipVideo lost the selector/engine piece '" + frag +
                        "' (two-tier pip selector / strict playing-only / "
                        "tracked-pin re-cycle)")
if "boolean pipMode" not in rescue:
    failures.append("fsApplyCssRescue lost the pipMode overload")

# ---- 1r. v1.3.64 PiP IS A WATCHING STATE (onPause): the media tab stays
#          resumed + spoofed while the floating window is up (or is about
#          to be -- onPause fires BEFORE onPictureInPictureModeChanged on
#          both paths), regardless of bgPlay / per-site noBg.
onpause = body_of(ba, "protected void onPause(", "protected void onStop(")
if not onpause:
    failures.append("onPause not found")
else:
    if "boolean pipWatch = inPip || customView != null;" not in onpause:
        failures.append("onPause lost the pipWatch clause (inPip || "
                        "customView != null) -- a fullscreen custom view at "
                        "pause time IS the PiP-eligible state")
    if "(pipWatch && (t.mediaPlaying || t.holdsMedia))" not in onpause:
        failures.append("onPause does not keep the media tab alive while "
                        "pipWatch -- the paused/hidden renderer is the "
                        "'press play in the pip and it stops instantly' bug")
    if "engageSpoof()" not in onpause:
        failures.append("onPause no longer engages the visibility spoof")

# ---- 1s. v1.3.64 THE MENU AUTO-CLOSE: drastic view changes must close the
#          menus that launched them (the site-settings report) -- openNewTab
#          (the main-menu 'New tab' card) and wipeSiteData (the site-settings
#          'Wipe' card) both dismiss every overlay first; the wipe also
#          answers the DELETE tap with an immediate toast.
ont = body_of(ba, "protected void openNewTab(String url)",
                  "protected void hideKeyboard()")
if not ont:
    failures.append("openNewTab not found")
elif "dismissAllOverlays()" not in ont:
    failures.append("openNewTab does not close the menus -- the fresh tab "
                    "must be the VISIBLE answer to the tap, not a sheet "
                    "parked over a changed view")
wipe = body_of(ba, "protected void wipeSiteData(String domain)",
                   "private java.util.List<String> expireAndVerify(")
if not wipe:
    wipe = body_of(ba, "protected void wipeSiteData(String domain)",
                       "protected void expireCookies(")
if not wipe:
    wipe = body_of(ba, "protected void wipeSiteData(String domain)",
                       "private void expireCookies(")
if not wipe:
    failures.append("wipeSiteData not found")
else:
    if "dismissAllOverlays()" not in wipe:
        failures.append("wipeSiteData does not close the site-settings "
                        "sheet -- the user must SEE the page die and rebuild")
    if "Clearing" not in wipe:
        failures.append("wipeSiteData has no immediate feedback toast -- the "
                        "final 'cleared' line lands seconds later; the DELETE "
                        "tap needs its own answer")

# ---- 1k. v1.3.61 pseudo-fullscreen watcher + Java sweep ----
for frag in ("__pfCheck", "'pseudo'", "fullscreenerror", "'fserr'"):
    if frag not in mh:
        failures.append("MediaHook lost the pseudo-fullscreen watcher "
                        "piece '" + frag + "'")
if "document.fullscreenElement" not in mh or "0.95" not in mh:
    failures.append("pseudo watcher no longer gates on "
                    "document.fullscreenElement / the 95% coverage rule")
jb = body_of(ba, '"pseudo".equals(name)', '"state".equals(name)')
if not jb or "customView == null" not in jb or "!inPip" not in jb:
    failures.append("the pseudo handler lost its guards (customView == "
                    "null && !inPip: a REAL fullscreen or PiP state must "
                    "never be swept)")
if "hideBarsNow(false)" not in jb or "showBarsNow(false)" not in jb:
    failures.append("the pseudo handler no longer sweeps/restores the bars")
if '"fserr".equals(name)' not in ba:
    failures.append("the fullscreenerror evidence channel is gone")

# ---- 1l. v1.3.61 heal-at-play (debounced) ----
if 'healRendererFullscreen(attached, "play")' not in ba:
    failures.append("heal-at-play missing: a state report (new play) no "
                    "longer re-runs the healer -- SPA documents never "
                    "reload, so a phantom seeded after the last "
                    "show/pageFinished survived forever")
if "lastFsHealAt" not in ba:
    failures.append("heal-at-play lost its debounce field")
n_heal_calls = len(re.findall(r"healRendererFullscreen\(", ba))
if n_heal_calls < 4:   # definition + showTab + pageFinished + play
    failures.append("healRendererFullscreen wiring shrank: expected at "
                    "least 4 references (def + showTab + pageFinished + "
                    "play), found %d" % n_heal_calls)

# ---- 1x. v1.3.67 THE PiP SURVIVAL KIT: the exit gate, the reaction
#      mutes, the pause decoder, the longer fight. The v1.3.66 report:
#      'fs pip pin=1 player=1' (the player pin WORKED) + 'fs pip fight
#      n=1' + the mobile video still pauses instantly -- the handoff's own
#      prediction ("the reversal is stronger than the bound; the next
#      lever is swallowing the player's fullscreenchange/resize reactions
#      while in PiP"). Desktop untouched by construction: its custom view
#      survives the shrink (no death path, no __boltpipx) and no
#      fullscreenchange fires while armed.
#  (a) the EXIT GATE: the arming stamp + the swallow
#      v1.3.68: the whole-session gate — the stamp moment ALSO resets the
#      GESTURE escape (__boltPipG, once per session; a per-beat reset would
#      eat a real tap on interactive surfaces)
for frag in ("if(!window.__boltpipAt){window.__boltpipAt=Date.now();",
             "window.__boltPipG=0;}"):
    if frag not in pipv:
        failures.append("PipVideo.pinJs no longer stamps __boltpipAt once "
                        "per session + resetting the gesture escape (the "
                        "exit gate's clock -- a per-beat restamp keeps the "
                        "gate alive forever)")
for frag in ("function __boltPipEntry", "function __boltGateExit",
             "function __boltWrapExit", "exitFullscreen",
             "webkitExitFullscreen", "'pipexit'", "__boltPipExits"):
    if frag not in mh:
        failures.append("MediaHook lost the EXIT GATE piece '" + frag +
                        "' (the page's automatic exitFullscreen at the "
                        "shrunk viewport -- the mobile/desktop split)")
#  (b) the REACTION MUTES: PIPSET + pipMuted + the death-path latch
#      v1.3.68: the blur mute joins the set (page-level blur while armed)
for frag in ("PIPSET", "function pipMuted", "__boltpipx", "blur:1"):
    if frag not in mh:
        failures.append("MediaHook lost the REACTION MUTE piece '" + frag +
                        "' (fullscreenchange/resize/blur not delivered to the "
                        "page while the session is armed / the death latch)")
if "window.__boltpipx=1" not in hide:
    failures.append("the in-PiP death path does not arm __boltpipx (the "
                    "post-exit resize/orientationchange mute)")
for frag in ("window.__boltpipx=0", "wasPip=!!window.__boltpip",
             "new Event('resize')", "new Event('fullscreenchange')"):
    if frag not in pipv:
        failures.append("PipVideo.unpinJs lost the resync piece '" + frag +
                        "' (the disarm must clear the latches and hand the "
                        "page one synthetic resync)")
#  (c) the PAUSE DECODER: the wrap + the payload
for frag in ("__HME.prototype.pause", "__boltPPS", "function pipEvt",
             "js:st?1:0", "JSON.stringify"):
    if frag not in mh:
        failures.append("MediaHook lost the PAUSE DECODER piece '" + frag +
                        "' (the recorded pause stack -- the next report "
                        "names the pausing module, or learns it was "
                        "renderer-side)")
#  (d) the LONGER FIGHT: the new bound + the re-arm probe
for frag in ("function pipDelay", "pipTries>7", "pipDoneAt", "15000"):
    if frag not in mh:
        failures.append("MediaHook lost the LONGER FIGHT piece '" + frag +
                        "' (8 tries + the 15 s re-arm probe -- a page that "
                        "pauses once per state change keeps meeting one "
                        "bounded fight per change)")
#  (e) the evidence lines: exitgate + the decoded fight
for frag in ('"pipexit".equals(name)', 'fs pip exitgate',
             'fsPipExitSaid', 'optBoolean("js"', 'optString("st"'):
    if frag not in ba:
        failures.append("BrowserActivity lost the v1.3.67 evidence piece '" +
                        frag + "' (the exitgate line + the decoded fight "
                        "line -- the next report's decode)")
if "fsPipExitSaid = false" not in pipc:
    failures.append("leaving PiP does not reset fsPipExitSaid (one "
                    "exitgate line per PiP session)")

# ---- 1y. v1.3.68 THE PAUSE GATE + THE DECODER FIX: the v1.3.67 field
#      log's own decode -- 'fs pip fight n=1 js=0 st=... at __HME.pause |
#      at g.fZ.pause (player-plasma base.js)' -- convicted YouTube's OWN
#      mobile player JS of the pause (the js=0 was an org.json optBoolean
#      parse bug: the JS sends js as a NUMBER), and the user's "one word
#      plays, 2 second pause, one word play and repeat" WAS the pipFight
#      schedule itself losing. A counter that plays AFTER the pause can
#      never produce continuous audio -- v1.3.68 swallows the pause at the
#      method so it NEVER EXECUTES.
#  (a) THE PAUSE GATE: the swallow + the hatches + the rate limiter
for frag in ("pipGateTripped", "pipGateN", "'pipgate'",
             "ytApi(this,true)", "__userPaused&&!this.ended",
             "Date.now()-lastGesture>=2000", "pipGateCool"):
    if frag not in mh:
        failures.append("MediaHook lost the PAUSE GATE piece '" + frag +
                        "' (the non-user page-side pause of the active "
                        "element never executes while the session is armed)")
if "window.__boltpip)window.__boltPipG=1" not in mh:
    failures.append("onGesture no longer arms the exit gate's gesture "
                    "escape (interactive surfaces lose their exit button)")
#  (b) THE DECODER FIX + UPGRADE: numeric js parse + the longer excerpt
if 'optInt("js", 0) != 0' not in ba:
    failures.append("BrowserActivity still mis-parses the numeric js bit "
                    "(org.json optBoolean does not coerce numbers -- every "
                    "real JS pause decoded as js=0 in the v1.3.67 field log)")
for frag in ("slice(0,7)", "lastIndexOf('/')", "slice(0,320)"):
    if frag not in mh:
        failures.append("MediaHook lost the decoder excerpt upgrade '" +
                        frag + "' (the v1.3.67 stack truncated after two "
                        "frames -- the CALLER of the pausing module was cut)")
#  (c) the evidence line: fs pip gate
for frag in ('"pipgate".equals(name)', 'fs pip gate', 'fsPipGateSaid'):
    if frag not in ba:
        failures.append("BrowserActivity lost the v1.3.68 evidence piece '" +
                        frag + "' (the pause gate's decode line)")
if "fsPipGateSaid = false" not in pipc:
    failures.append("leaving PiP does not reset fsPipGateSaid (one "
                    "gate line per PiP session)")
if "window.__boltPipG=0" not in pipv:
    failures.append("PipVideo.unpinJs does not clear the gesture escape "
                    "with the disarm")

# ---- 1z. v1.3.69 THE OUTER-SIZE SPOOF + THE MARKED RESYNC + THE LIMITER
#      RECALIBRATION: the v1.3.68 field log ('fs pip gate' AND 'fs pip
#      fight' with the SAME bY stack + keepalive pos FROZEN at 30/360 for
#      minutes) decoded, against the player's own shipped code (build
#      a5c2aafe), to a POLLING pause: gY (the resize handler) latches
#      pictureInPicture when outer area < 33% of screen area -- Android's
#      activity-PiP shrink is indistinguishable from browser PiP -- and
#      bY (the onVideoProgress tick) then re-issues pauseVideo() on EVERY
#      tick. The gate swallowed at ~4 Hz and the 12-in-3s limiter tripped
#      EXACTLY at that cadence, manufacturing the "2 s plays, 2 s pause"
#      field stutter. v1.3.69 kills the premise (armed outerWidth/
#      outerHeight report the screen dims), hands the player ONE marked
#      resync resize so gY un-latches, and recalibrates the limiter so a
#      steady protective cadence can never trip again.
#  (a) THE OUTER-SIZE SPOOF (MediaHook): install + armed lie + delegation
for frag in ("function pipOuterSpoof", "pipOuterSpoof();",
             "['outerWidth',sw],['outerHeight',sh]",
             "if(window.__boltpip)return val;",
             "Object.getOwnPropertyDescriptor(Window.prototype,name)",
             "__boltOuterArm"):
    if frag not in mh:
        failures.append("MediaHook lost the OUTER-SIZE SPOOF piece '" +
                        frag + "' (the player's window-area PiP detection "
                        "reads the true shrunk outer -- the pause poll "
                        "re-arms on every progress tick)")
#  (b) THE MARKED RESYNC: the exemption in pipMuted + the one dispatch at
#      the first arm (PipVideo)
if "ev&&ev.__boltRS)return false" not in mh:
    failures.append("pipMuted lost the __boltRS exemption (the resync "
                    "resize is muted with every other resize -- gY never "
                    "re-evaluates, the pictureInPicture premise persists)")
for frag in ("__rse.__boltRS=1", "new Event('resize')"):
    if frag not in pipv:
        failures.append("PipVideo.pinJs lost the marked resync piece '" +
                        frag + "' (the premise stays latched until the "
                        "next real resize, which the mutes swallow)")
if "if(!window.__boltpipAt){window.__boltpipAt=Date.now();" not in pipv:
    failures.append("the resync escaped the first-arm guard (a per-beat "
                    "dispatch would re-deliver resizes every 900 ms)")
#  (c) THE LIMITER RECALIBRATION: 24-in-2s trips, 3 s cooldown -- and the
#      old 12-in-3s calibration (the stutter manufacture) is GONE
for frag in ("pipGateAt.length===24&&now-pipGateAt[0]<2000",
             "pipGateCool=now+3000"):
    if frag not in mh:
        failures.append("MediaHook lost the recalibrated limiter piece '" +
                        frag + "' (a steady swallow cadence must never "
                        "trip -- the v1.3.68 field stutter)")
if "pipGateAt.length===12" in mh:
    failures.append("the OLD 12-in-3s limiter is still present (it tripped "
                    "at the player's own ~4 Hz poll cadence and "
                    "manufactured the v1.3.68 stutter)")

# ============================================================
# PART 2 — behavioral mirror of the fullscreen lifecycle
# ============================================================
# State: (app_cv, ren_fs): the app-side customView and the renderer-side
# document.fullscreenElement. The phantom is (False, True) -- the page
# believes it is fullscreen while the app shows no customView: the
# player renders "zoomed-in" pseudo-fullscreen, page overlays ride
# along, the first fullscreen tap is consumed exiting it, and PiP
# eligibility (customView) is starved.


def make_step(v1360):
    """event -> state transition for the given rule set.

    request        a healthy page request: both sides engage
    exit/app_exit  page-side exit (onHideCustomView) or app-side exit
                   (hideCustomViewInternal notifies the renderer too)
    activity_death ACTIVITY dies while fullscreen (PiP-close X, OEM kill,
                   crash, recreate). v1.3.60: THE ORPHAN EXIT notifies the
                   renderer. v1.3.59: nothing did -- the surviving WebView
                   kept its renderer-side fullscreen (the field bug).
    desync         residual paths that can still leave renderer-side
                   fullscreen with no app customView even WITH the orphan
                   exit (e.g. an adopted web whose exit raced a kill).
                   Modeled as spontaneous: this is what the HEALER exists
                   for -- belt and braces.
    heal           showTab / onPageFinished: runs only when the app has
                   no customView; a set fullscreenElement is exited.
    play           v1.3.61 heal-at-play: a media state report re-runs the
                   SAME healer (debounced) -- an extra heal moment inside
                   SPA documents that never reload.
    """
    def step(cv, fs, ev):
        if ev == "request":
            return (True, True)
        if ev in ("exit", "app_exit"):
            return (False, False)
        if ev == "activity_death":
            # the activity's view tree always dies with it (customView is
            # cleared); v1.3.60 additionally notifies the renderer
            return (False, False) if v1360 else (False, fs)
        if ev == "desync":
            return (False, True)
        if ev == "heal" or ev == "play":
            # v1.3.59 had no healer at all: showTab/onPageFinished did
            # nothing about renderer-side fullscreen. v1.3.61 adds the
            # play moment with the SAME semantics (heal-at-play).
            if not v1360:
                return (cv, fs)
            return (cv, fs) if cv else (cv, False)
        raise AssertionError(ev)
    return step


def reachable(v1360, max_depth=5):
    step = make_step(v1360)
    events = ["request", "exit", "app_exit", "activity_death", "desync",
              "heal", "play"]
    seen = {(False, False)}
    frontier = [(False, False)]
    for _ in range(max_depth):
        nxt = []
        for st in frontier:
            for ev in events:
                ns = step(st[0], st[1], ev)
                if ns not in seen:
                    seen.add(ns)
                    nxt.append(ns)
        frontier = nxt
    return seen, step


# ---- invariant A (v1.3.60): no phantom SURVIVES a heal moment ----
states60, step60 = reachable(True)
survivors = [s for s in states60 if step60(s[0], s[1], "heal") == (False, True)]
if survivors:
    failures.append("BEHAVIORAL: with v1.3.60 rules a phantom state "
                    "survives a heal moment: %s -- the healer must retire "
                    "renderer-side fullscreen whenever the app has no "
                    "customView" % survivors)

# ---- invariant B (v1.3.59 negative control): the phantom was reachable ----
# and STUCK: no orphan exit on death, no healer at show/pageFinished.
states59, step59 = reachable(False)
stuck59 = [s for s in states59 if s == (False, True)
           and step59(s[0], s[1], "heal") == (False, True)]
if not stuck59:
    failures.append("BEHAVIORAL (negative control): the v1.3.59 rules "
                    "should leave the phantom reachable-and-stuck -- the "
                    "mirror no longer models the field bug it guards")

# ---- the PiP revival story, end to end ----
# fullscreen video -> activity dies (PiP close) -> relaunch (heal moment)
# -> fullscreen request again: eligibility (customView) must re-engage.
st = (False, False)
seq = [("request", (True, True)),
       ("activity_death", (False, False)),
       ("heal", (False, False)),
       ("request", (True, True))]
for ev, expect in seq:
    st = step60(st[0], st[1], ev)
    if st != expect:
        failures.append("BEHAVIORAL: the death->relaunch->request story "
                        "broke: after %s expected %s got %s" % (ev, expect, st))
if not st[0]:
    failures.append("BEHAVIORAL: PiP eligibility (customView) did not "
                    "recover after the phantom was cleaned -- the whole "
                    "point of v1.3.60")

# ============================================================
print(("FAIL: %d problem(s)" % len(failures)) if failures else
      "test_fs: OK (fullscreen covenant + PiP revival pins + lifecycle mirror)")
for f in failures:
    print("  - " + f)
sys.exit(1 if failures else 0)
