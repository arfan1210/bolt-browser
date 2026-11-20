#!/usr/bin/env python3
"""v1.3.89 gate — the six-pack field fix (fullscreen keyboard, fullscreen
tap-steal, QR quality, desktop viewport, home drag round 3, the perf pass).
Static contract tests over the exact sources, the repo's convention since
v1.3.31.

J1 THE FULLSCREEN IME PAD — in fullscreen browsing the window never resizes
   for the keyboard (immersive defeats adjustResize on both API paths), so
   the IME covered the page's composer ("the typing area does not move
   above the keyboard... I have to blind type"). The kb transition in the
   global layout listener now pads flContent by the LIVE keyboard height
   (bottom) and the status strip (top), instantly, and clears on close.
J2 THE TAP-STEAL STRIP — the top status-height zone is the system's shade
   gesture area; content parked under it ate taps ("tapping the search bar
   instead does nothing... the notification drawer"). The IME pad's TOP
   half pushes interactive content below the strip while typing.
Q1 QR QUALITY — the viewfinder hole grows 250dp -> ~78% of the screen's
   short edge (capped 340dp), the picker takes the LARGEST stream <= 1920
   (was smallest >= 640-1280), the lens runs CONTINUOUS_PICTURE autofocus,
   and the Y-plane buffer is reused (no per-frame 2 MB allocations).
D1 THE DESKTOP VIEWPORT — a desktop UA must also carry a desktop LAYOUT
   viewport: responsive apps (code.qwen.ai, the z.ai dashboard) pick their
   layout from media queries / innerWidth and a phone-width viewport serves
   the mobile wall no matter what the UA says. webauthn_compat.js now
   forces <meta name=viewport> to width=1280 when the live UA is desktop
   (parser-injected metas via MutationObserver + DOMContentLoaded sweep +
   already-desktop pages left alone).
H1 HOME DRAG ROUND 3 — the v1.3.88 cell-feed fix was CLOBBERED: tileCell /
   groupCell installed sdCellTouch and then tilePressFx overwrote the
   listener one line later, so the drag still needed menu -> Move. The
   press-fx listener now COMPOSES the drag feed. The ghost is the REAL
   tile (favicon avatar / group member preview, not the first letter) and
   the source cell dims to 25% while its icon flies.
P1 THE PERF PASS — the blob map pins a 48 MB TOTAL byte budget (was 32 MB
   x 6 by key count = a 192 MB worst-case RAM pin behind the crashes /
   background-play kills), and the skipper loop runs ADAPTIVE cadence (60ms
   only while playing & visible; 250ms playing hidden; 1s idle) with a
   per-chain REUSED analyser buffer.
V1 version pin 108 / 1.3.95.

Usage: python3 scripts/test_v1389.py
"""
import re
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")


def rd(p):
    with open(os.path.join(ROOT, p), encoding="utf-8") as f:
        return f.read()


def need(cond, msg):
    if not cond:
        print("  FAIL  " + msg)
        need.failed = True
    else:
        print("  PASS  " + msg)


need.failed = False

act = rd("app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
qr = rd("app/src/main/java/com/bolt/browser/ui/QrScanActivity.java")
js = rd("app/src/main/assets/webauthn_compat.js")
fhk = rd("app/src/main/java/com/bolt/browser/util/FrameHooks.kt")
mhk = rd("app/src/main/java/com/bolt/browser/media/MediaHook.java")
gradle = rd("app/build.gradle")

print("J1 — the fullscreen IME pad (blind typing)")
need("protected int fsKbTop = 0, fsKbBottom = 0;" in act,
     "the pad fields exist (zero = keyboard closed)")
need("protected int statusBarInset()" in act
     and '"status_bar_height", "dimen", "android"' in act,
     "the status-strip helper reads the platform dimension")
need("fsKbBottom = Math.max(0, flRoot.getHeight() - visible);" in act,
     "the pad bottom is the LIVE keyboard height (visible-frame loss)")
need("fsKbTop = statusBarInset();" in act,
     "the pad top is the shade-gesture strip (the tap-steal fix)")
need("if (fsKbBottom > 0 && com.bolt.browser.util.Fullscreen.on(this)" in act
     and "customView == null) {\n                tp = fsKbTop;\n                bp = fsKbBottom;" in act,
     "applyContentInsets applies the pad only in fullscreen + no video customView")
need("fsKbTop = 0; fsKbBottom = 0;" in act,
     "the pad clears on keyboard close")
need(act.count("applyContentInsets(false);") >= 3,
     "the pad rides the instant (non-animated) inset path — the v1.3.78 truce law")

print("J2 — the pad is armed from the keyboard TRANSITION detector")
need("kbWasOpen = kb;" in act and "fsKbBottom = Math.max(0" in act,
     "the same transition edge that owns the truce also owns the pad")

print("Q1 — QR: bigger hole, best quality, autofocus, no churn")
need("int hole = Math.min(Fx.dp(this, 340), (int) (shortEdge * 0.78f));" in qr,
     "the viewfinder hole is ~78% of the short edge (cap 340dp, was 250dp)")
need("private Size pickLargest(List<Size> sizes, int cap)" in qr,
     "the best-quality picker exists")
need("pickLargest(Arrays.asList(map.getOutputSizes(SurfaceTexture.class)), 1920)" in qr
     and "pickLargest(Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)), 1920)" in qr,
     "preview AND analysis take the largest <= 1920 stream")
need("CONTROL_AF_MODE_CONTINUOUS_PICTURE" in qr,
     "continuous-picture autofocus is requested")
need("private static byte[] yBuf = null;" in qr
     and "if (yBuf == null || yBuf.length < w * h) yBuf = new byte[w * h];" in qr,
     "the Y-plane buffer is reused across frames (no 2 MB per decode)")

print("D1 — the desktop viewport (webauthn_compat.js)")
need("if ((UA_WIN || UA_MAC) && !UA_MOBILE) {" in js,
     "the viewport forcing keys on the LIVE desktop UA shape")
need("m.setAttribute('content', 'width=' + DW_W)" in js
     and "var DW_W = 1280" in js,
     "viewport metas are forced to width=1280 (the >=1024 breakpoint family)")
need("if (w && parseFloat(w[1]) >= 1024) return" in js,
     "pages that already declare a desktop width are left as authored")
need("childList: true, subtree: true," in js
     and "attributes: true, attributeFilter: ['content', 'name']" in js,
     "the metas are caught by a MutationObserver before layout locks (v1.3.98: attribute rewrites too)")
need("document.addEventListener('DOMContentLoaded', function () {" in js
     and "dwSweep()" in js,
     "the DOMContentLoaded sweep is the fallback layer")
need("dwObs.disconnect" not in js,
     "the guard never rests (v1.3.98: the 4 s disconnect was the SPA-route-change corpse)")

print("H1 — home drag round 3: the clobber fix + the real-icon ghost")
need("tilePressFx(cell, shell, sdCellTouch);" in act
     and "return dragFeed != null && dragFeed.onTouch(v, e);" in act,
     "tilePressFx COMPOSES press-fx with the drag feed (the clobber fix; v1.4.6 re-point: the 2-arg form DELEGATES)")
need(act.count("setOnTouchListener(sdCellTouch)") == 0,
     "no direct installs remain (the clobbered v1.3.88 pair is gone)")
need("THE REAL-ICON GHOST" in act,
     "the ghost upgrade is documented at the build site")
need("FaviconStore.avatar(\n                    this, Fx.hostOf(o == null ? \"\" : o.optString(\"url\")), name, 38, innerR)" in act,
     "the shortcut ghost carries the site's own favicon avatar")
need("cell.animate().alpha(0.25f).setDuration(120).start()" in act,
     "the source cell dims while its icon flies (the launcher grab contract)")
need("c.animate().alpha(1f).setDuration(120).start()" in act,
     "the source cell returns on drop/end")

print("P1 — the perf pass")
need("b.size<=50331648" in fhk
     and "while((bs+(b.size||0)>50331648)||Object.keys(m).length>=6){" in fhk
     and "window.__boltBlobBytes=0;" in fhk,
     "the blob map pins a 48 MB total byte budget, oldest-first eviction")
need("33554432" not in fhk,
     "the old 32 MB-per-blob x6 rule is gone (the 192 MB worst-case pin)")
need("var live=0;" in mhk and "live++;" in mhk,
     "the skipper tick counts LIVE (playing) elements")
need("if(!c.skBuf){try{c.skBuf=new Float32Array(an.fftSize);}catch(x){return;}}" in mhk,
     "the analyser buffer is reused per chain (no per-tick allocation)")
need("setTimeout(skTick,live&&!hid?60:live?250:skEls.size?1000:400);}" in mhk,
     "adaptive cadence: 60ms playing+visible / 250ms playing hidden / 1s idle")
need("var hid=false;try{hid=isReallyHidden();}catch(x){}" in mhk,
     "the hidden state feeds the cadence (no 16 Hz timer in the background)")

print("V1 — version pin")
m = re.search(r"versionCode\s+(\d+)", gradle)
m2 = re.search(r'versionName\s+"([^"]+)"', gradle)
need(m and m.group(1) == "136" and m2 and m2.group(1) == "1.4.22",  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "build.gradle pins 136 / 1.4.22 (got %s / %s)" % (  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
         m.group(1) if m else "?", m2.group(1) if m2 else "?"))

print()
if need.failed:
    print("v1.3.89 gate: 1 FAILURE(S)")
    sys.exit(1)
print("v1.3.89 gate: ALL CONTRACTS HOLD")
