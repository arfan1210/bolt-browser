#!/usr/bin/env python3
"""v1.3.92 gate — the six-pack field fix (the native AI picker + the vector
PDF, the launcher home matrix, the long-hold copy, the transient-failure
vault pass, the dark parity engine). Static contract tests over the exact
sources, the repo's convention since v1.3.31.

A1 THE NATIVE PICKER (the field: "I am in zai chat web and don't see any
   options to export or select a message to save as pdf or image") — the
   chrome is ANDROID views over the WebView (circles + bottom bar + hint),
   not page-injected divs: a transformed app root can no longer hide the
   bar, inner scrollers can no longer desync the circles, CSP can no
   longer kill the sheet. The page JS only marks candidates, keeps
   selection state, reports rects (scroll-synced), hit-tests manual taps
   and extracts blocks.
V1 THE VECTOR PDF ("saving a pdf means I want to save as a vector file,
   not just a screenshot wrapped in pdf file") — the AI export's Save PDF
   renders REAL selectable text (VectorPdf: PdfDocument + StaticLayout +
   TextPaint, A4, headings/code/lists); the page Save-as-PDF opens the
   system print preview (Chromium's own vector output; the stitch engine
   stays as the fallback). The v1.3.87-91 export-mode classes were never
   styled by anything — the hide/open surgery now lands as INLINE CSS.
H1 THE LAUNCHER HOME MATRIX (the A-B-C-D spec) — a 6x7 slot matrix paged
   horizontally (ViewPager2, 42 tiles per page), full-slot row-wrapping
   gap preview, the dwell-timer group (the tabs progress pill, merge when
   FULL — never at release), the edge page-turn, and the handedness
   setting (first page right for left-handed).
C1 THE LONG-HOLD COPY ("Bolt AI's message copied by long hold instead of
   tap — tap causes accidental copying a lot").
P1 THE TRANSIENT-FAILURE VAULT PASS ("some sites give could not save -
   vault write failed") — a successful decrypt clears the sticky
   unreadable flag; the write and the verify each retry once.
D1 THE DARK PARITY ENGINE ("it still blindly inverts colours… dark grey
   bright… white elements become black on black") — the walk preserves
   subtrees that already paint dark (.__boltDK cancels the global
   inversion exactly, contrast-compensated); photos keep their overlays.
"""
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app/src/main/java/com/bolt/browser"

act = (APP / "ui/BrowserActivity.java").read_text(encoding="utf-8")
appearance = (APP / "ui/AppearanceActivity.java").read_text(encoding="utf-8")
aie = (APP / "util/AiExport.kt").read_text(encoding="utf-8")
psave = (APP / "util/PageSave.kt").read_text(encoding="utf-8")
vpdf = (APP / "util/VectorPdf.kt").read_text(encoding="utf-8")
boltai = (APP / "util/BoltAi.kt").read_text(encoding="utf-8")
vault = (APP / "util/PasswordVault.kt").read_text(encoding="utf-8")
media = (APP / "media/MediaHook.java").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
strings = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")

fails = 0
def need(cond, msg):
    global fails
    if cond:
        print("  OK  " + msg)
    else:
        fails += 1
        print("  FAIL " + msg)

print("A1 — the native AI-export picker")
need("aiPickLayer" in act and "aiPickStart" in act and "endAiPick" in act
     and "aiSyncOnce" in act,
     "the native chrome lives in BrowserActivity (layer + bar + circles)")
need("flContent.addView(layer" in act,
     "the overlay rides flContent (exactly the web frame — no toolbar overlap)")
need("layer.setClickable(false)" in act,
     "the overlay is pass-through (chat scrolling stays live while picking)")
need("public void aiSync()" in act,
     "the Bolt.aiSync bridge feeds the fast scroll path")
need("document.addEventListener('scroll',onScroll,true)" in aie,
     "the page watches ANY scroller (capture) and syncs the circles")
need("out.push({k:k,id:id,l:r.left,t:r.top,w:r.width,h:r.height," in aie,
     "the rects payload carries kind/id/rect/selection")
need("data-bolt-ai" in aie and "data-bolt-ai-m" in aie,
     "candidates + manual picks carry stable markers")
need("style.outline='3px solid #7C4DFF'" in aie,
     "selection outlines are inline CSSOM (CSP-proof)")
need("__boltAiChk" not in aie and "__boltAiBar" not in aie and "__boltAiHint" not in aie,
     "NO page-injected chrome remains (the v1.3.91 fragility is gone)")
need("if (aiWeb == view) endAiPick(false);" in act,
     "a navigation tears the picker down (no stale chrome over a new page)")
need("t.web != aiWeb" in act,
     "a tab switch ends the picker (the poll guard)")

print("V1 — the vector PDF")
need((ROOT / "app/src/main/java/com/bolt/browser/util/VectorPdf.kt").exists(),
     "the VectorPdf engine exists (new code -> Kotlin per the rule)")
need("StaticLayout" in vpdf and "TextPaint" in vpdf and "PdfDocument" in vpdf,
     "real vector text: StaticLayout + TextPaint into PdfDocument pages")
need("PAGE_W = 595" in vpdf and "PAGE_H = 842" in vpdf,
     "A4 pages in PostScript points")
need("fun parseBlocks" in vpdf and "class Blk" in vpdf,
     "the block model parses the page extraction")
need("__boltAiBlocks" in aie and "tagName==='PRE'" in aie,
     "the page extracts structured blocks (code keeps its breaks)")
need("aiSaveVector" in act and "VectorPdf.save" in act,
     "Save PDF renders the selection through the vector engine")
need("savePdfVector" in psave and "createPrintDocumentAdapter" in psave,
     "page Save-as-PDF opens the system print preview (Chromium vector)")
need("Bolt-page-" in psave,
     "the stitch fallback keeps the Bolt-page- name")
need("data-bolt-ai-d" in aie and "style.display='none'" in aie,
     "the image-path surgery is INLINE CSS (the v1.3.87-91 classes had no rules)")
need("style.height='auto'" in aie and "style.overflow='visible'" in aie,
     "the chat scrollers open inline for the capture")

print("H1 — the launcher home matrix")
need("class SdGridLayout extends android.view.ViewGroup" in act,
     "the slot-matrix layout exists")
need("COLS = 6" in act and "ROWS = 7" in act and "PER_PAGE = COLS * ROWS" in act,
     "6 columns x 7 rows = 42 slots per page")
need("androidx.viewpager2.widget.ViewPager2" in act and "viewpager2:viewpager2:1.1.0" in gradle,
     "the grid pages horizontally (ViewPager2 dependency)")
need("sdPagesRtl" in act and "LAYOUT_DIRECTION_RTL" in act
     and "sdPagesRtl" in appearance,
     "the handedness setting flips the page order (left-handed = RTL)")
need("sdPreviewNewIndex" in act and "slotX(newLocal)" in act and "slotY(newLocal)" in act,
     "the gap preview shifts FULL slots through the matrix (the A-B-C-D law)")
need("startSdDwell" in act and "sdDwellProgress >= 1000" in act
     and "ClipDrawable" in act and "hold_to_group_hint" in strings,
     "the dwell timer: the tabs-style progress pill merges when FULL")
need("final int folder = sdFolderTarget;" not in act,
     "NO release-gated merge (a drop is always a plain reorder)")
need("sdEdgeTurn" in act and "setCurrentItem(next, true)" in act,
     "resting the drag at the edge turns the page")
need("sdPager.requestDisallowInterceptTouchEvent(true)" in act,
     "the pager cannot steal the in-flight drag MOVE stream")
need("plusCell(final int idx)" in act and "i >= n) continue;   // the plus tile" in act,
     "the plus tile carries its index and is never a group target")

print("C1 — the long-hold copy")
need("t.setOnLongClickListener {" in boltai and "setPrimaryClip" in boltai,
     "Bolt AI messages copy on LONG-press")
need("t.setOnClickListener {\n            try {\n                val cm" not in boltai,
     "the single-tap copy path is gone")
need("Long-press any message to copy it." in boltai,
     "the intro bubble teaches the new gesture")

print("P1 — the transient-failure vault pass")
need(vault.count("unreadable = false") >= 2,
     "a successful decrypt clears the sticky unreadable flag")
need("if (!writeAll(c, arr)) {" in vault
     and "android.os.SystemClock.sleep(150)" in vault,
     "the write retries once (with 150 ms backoff — v1.3.95 saveEx) before reporting failure")
need("var ok = passFor(c, host, user) == pass" in vault and "if (!ok) {" in vault,
     "the round-trip verify retries once, BY (host, user) identity (passFor — find(host) returned the FIRST row for a host and false-failed second accounts)")

print("D1 — the dark parity engine")
need(".__boltDK" in media and "contrast(1.111)" in media,
     "the parity class cancels the global inversion EXACTLY (contrast-compensated)")
need("darkSmartJs" in media and "parityEl" in media and "want=(l<=0.35)?0:1" in media.replace(" ", ""),
     "the walk preserves dark subtrees (lum <= 0.35, the dark-grey family)")
need("cs.filter&&cs.filter!=='none'" in media.replace(" ", "").replace("cs.filter&&cs.filter", "cs.filter&&cs.filter"),
     "an element with its own filter is never clobbered")
need("bi.indexOf('url(')>=0){want=0" in media.replace(" ", ""),
     "photo grounds keep their text overlays (parity 0)")
need("darkClearJs" in media and "classList.remove('__boltDK')" in media,
     "pulling the inversion strips the pin/walk classes (no inverted patches)")
need(act.count("darkClearJs()") >= 2 and "darkSmartJs()" in act,
     "both pull sites (native-dark + late flip) use the full clear")

print("V2 — version pin")
need("versionCode 133" in gradle and 'versionName "1.4.19"' in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)
     "build.gradle pins 133 / 1.4.19")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)

print()
if fails:
    print(f"v1.3.92 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.3.92 gate: all green")
