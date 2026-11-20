#!/usr/bin/env python3
"""v1.3.88 gate — the four-pack field fix (downloads round 2, desktop/UA,
QR preview, home drag). Static contract tests over the exact sources, the
repo's convention since v1.3.31.

I1 DOWNLOADS ROUND 2 — the v1.3.87 repair armed only when the download URL
   had ALREADY committed at onDownloadStart; in the field the commit lands
   AFTER the listener, so nothing armed and the tab persisted the dead
   entry. Round 2: arm in BOTH states, a doUpdateVisitedHistory hook steps
   back the moment an armed dlUrl commits, blind 400/1500 ms checks cover
   the rest, the pair serializes and the TabManager restore swaps it.
I2 DESKTOP/UA — the client-hints spoof must AGREE with the live UA (a
   hardcoded mobile:true made every hints-reading site serve mobile on a
   desktop UA), and a per-site "Default (mobile)" pick (stored ua=0) must
   not permanently pin the site over the desktop toggle + global choice.
   Behavioral half lives in scripts/test_webauthn_compat.js (5 new cases).
I3 QR PREVIEW — portrait applied NO transform (the v1.3.37 comment believed
   camera2 display-rotates previews; that is camera1 behavior) so the
   landscape sensor buffer was stretched onto the portrait view = squished.
   The transform now rotates by (sensorOrientation - displayRotation) and
   cover-scales.
I4 HOME DRAG — the drag overlay added mid-gesture never receives the
   in-flight MOVE stream (touch target fixed at ACTION_DOWN); the only thing
   that ever fired was the 600 ms hold-menu = "the weird pop up". The cells
   themselves now feed the drag (launcher-grid pattern).
I5 version pin (re-pointed at the current release each version, per convention).

Usage: python3 scripts/test_v1388.py
"""
import re
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")


def rd(p):
    with open(os.path.join(ROOT, p), encoding="utf-8") as f:
        return f.read()


act = rd("app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
tab = rd("app/src/main/java/com/bolt/browser/core/Tab.java")
tm = rd("app/src/main/java/com/bolt/browser/core/TabManager.java")
qr = rd("app/src/main/java/com/bolt/browser/ui/QrScanActivity.java")
js = rd("app/src/main/assets/webauthn_compat.js")
gradle = rd("app/build.gradle")

fails = []


def need(cond, msg):
    if not cond:
        fails.append(msg)
        print(f"  FAIL  {msg}")
    else:
        print(f"  PASS  {msg}")


print("I1 — the download repair, round 2")
need("protected void repairDownloadPage(final String dlUrl)" in act,
     "the repair still runs from onDownloadStart")
need("repairDownloadPage(url);" in act, "still wired before the dialog")
# the BOTH-STATES arming: not-committed-yet uses the current page as origin
need("not committed yet -> the CURRENT page is the origin" in act
     and "} else if (curUrl != null && curUrl.startsWith(\"http\")" in act
     and "pre = curUrl;" in act,
     "arms with the CURRENT page when the commit has not landed (the field case)")
need("dlStrandedOn(t.web, dlUrl)" in act and "protected boolean dlStrandedOn" in act,
     "the stranded test is its own helper")
# the commit hook
need("THE DOWNLOAD-COMMIT HOOK" in act and "url.equals(t.dlUrl)" in act
     and "dlStepBack(dt, dpre), 250" in act,
     "doUpdateVisitedHistory steps back the moment an armed dlUrl commits")
need("dlStepBack(tt, prePage), 400" in act and "dlStepBack(tt, prePage), 1500" in act,
     "blind late-commit checks at 400 + 1500 ms")
need(act.count("t.web.goBack();") == 1 and "protected void dlStepBack" in act,
     "the ONE raw goBack lives in dlStepBack (the repair family's single exit)")
need('o.put("dlu", dlUrl);' in tab and 'o.put("dlf", dlFromUrl);' in tab
     and "dlUrl.equals(url)" in tab,
     "Tab serializes the pair ONLY while genuinely stranded")
need('o.optString("dlu", "")' in tm and "dlu.equals(t.url)" in tm
     and "t.url = dlf;" in tm,
     "the TabManager restore swaps a persisted strand onto the real page")
need("t.dlUrl = dlUrl;" in act and "t.dlFromUrl = pre;" in act
     and "tm.scheduleSave();" in act,
     "the pair arms synchronously and a save is scheduled at once")

print("I2 — desktop site / user agent")
need("var LIVE_UA" in js and "LIVE_UA = String(navigator.userAgent || '')" in js,
     "the spoof derives from the LIVE navigator.userAgent")
need("mobile: UA_MOBILE," in js and "platform: UA_PLATFORM," in js,
     "userAgentData mobile/platform follow the UA (no hardcoded mobile:true)")
need("mobile: true," not in js, "no hardcoded mobile:true anywhere")
need("UA_NO_HINTS" in js and "delete Navigator.prototype.userAgentData" in js,
     "Firefox/Safari UAs expose NO userAgentData (like the real browsers)")
need("UA_NAV_PLATFORM" in js and "'Win32'" in js and "'MacIntel'" in js,
     "navigator.platform derives too (Win32/MacIntel/Linux armv8l)")
need("(UA_WIN || UA_MAC) ? 'x86' : 'arm'" in js
     and "UA_ANDROID ? (CONFIG.deviceModel || '') : ''" in js,
     "high-entropy hints match the platform (x86, empty model on desktop)")
need("if (siteUa > 0) idx = siteUa;" in tab,
     "applyUa: only a REAL per-site UA choice overrides — stored 0 no longer pins the site")
need("beat a stored 0" in tab,
     "the stored-0 law is documented at the decision")

print("I3 — the QR preview transform")
need("(sensorOrientation - degrees + 360) % 360" in qr,
     "rotates by (sensorOrientation - displayRotation) — the JPEG-orientation formula")
need("float uw = (theta % 180 == 0) ? bw : bh;" in qr
     and "float s = Math.max(viewW / uw, viewH / uh);" in qr,
     "cover-scale keeps the aspect (never squished)")
need("matrix.postRotate(theta);" in qr and "matrix.postScale(s, s);" in qr,
     "the matrix rotates then cover-scales, centered")
need("setRectToRect" not in qr,
     "the old landscape-only recipe is gone")
need("PREVIEW stream is NOT rotated by camera2" in qr,
     "the design comment no longer lies (the v1.3.37 belief that caused the bug)")

print("I4 — the home drag (the in-flight feed)")
need("protected final android.view.View.OnTouchListener sdCellTouch" in act,
     "the shared cell touch listener exists")
need("if (sdOverlay == null) return false;" in act,
     "the listener is transparent when no drag is armed (taps/long-presses intact)")
need("protected void sdDragMove(float x, float y)" in act
     and "protected void sdDragEnd(float x, float y)" in act,
     "the drag motion + drop are shared handlers")
need("sdDragMove(e.getRawX(), e.getRawY());" in act,
     "the handlers are fed from real MOVE events")
# v1.3.89 re-point: the two direct setOnTouchListener(sdCellTouch) installs
# were SILENTLY CLOBBERED one line later by tilePressFx's own listener (the
# v1.3.88 fix never reached the field). The feed now rides INSIDE
# tilePressFx's composed listener, so BOTH cell types get it via their
# tilePressFx call — and the clobber is structurally impossible.
need(act.count("setOnTouchListener(sdCellTouch)") == 0,
     "no direct installs remain (they were the clobbered pair)")
need("tilePressFx(cell, shell, sdCellTouch);" in act
     and "return dragFeed != null && dragFeed.onTouch(v, e);" in act,
     "tilePressFx COMPOSES press-fx with the drag feed (the clobber fix; v1.4.6 re-point: the 2-arg form DELEGATES — the feed is a param so the folder window passes its own)")
need(act.count("tilePressFx(cell, shell);") == 3,
     "all three cell builders ride the composed listener (shortcut + group + plus)")
need("sdGhostHalf" in act and "sdOriginX" in act,
     "origin + ghost size captured for the shared handlers")
need("shields NEW touches while a drag is armed" in act,
     "the overlay is documented as a shield, not the drag driver")
need("never receives the ongoing MOVE stream" in act,
     "the touch-target law is documented where it bit")

print("I5 — version pin")
need('versionName "1.4.24"' in gradle and "versionCode 138" in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "111 / 1.3.98")

print()
if fails:
    print(f"v1.3.88 gate: {len(fails)} FAILURE(S)")
    sys.exit(1)
print("v1.3.88 gate: ALL CONTRACTS HOLD")
