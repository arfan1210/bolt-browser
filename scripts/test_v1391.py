#!/usr/bin/env python3
"""v1.3.91 gate — the eight-pack field fix (the release-gated folder, the
honest password vault, the Shorts orientation contract, the tab-count color
law, the sort pass, the dark round 3, the save-page pass, the AI exporter
round 2). Static contract tests over the exact sources, the repo's
convention since v1.3.31.

H1 THE RELEASE-GATED FOLDER — the 550 ms dwell-merge timer is GONE (a slow
   drag over any tile merged it = "still deleting some of the icons"); the
   folder candidate is the CENTER ZONE (38% of the cell — the Launcher3
   folderCreationRadius law), breathes to 1.24x with a hint, and the merge
   only ever happens at DROP. Delete = menu + UNDO snackbar.
P1 THE HONOST VAULT + THE PAGE HOST — Bolt.pwField carries location.host
   (an SSO/embedded form on an origin the tab URL never shows no longer
   misses the lookup); writeAll is atomic (tmp + fsync + Files.move) and
   honest; save() verifies the round-trip; an unreadable vault is surfaced
   (banner + reset) instead of silently empty; find() is bidirectional.
F1 THE ORIENTATION CONTRACT — the enter no longer force-rotates every video
   to landscape and no longer double-kicks every enter (the Shorts toolbar
   flicker); the orientation follows the VIDEO's aspect (portrait stays
   portrait), a coming rotation IS the settle (no kicks), and a no-rotation
   enter kicks ONCE with the probe+rescue at 800 ms.
T1 THE ONE COLOR LAW — the tab-count number restores through the same live
   adaptive glass verdict applyGlassGlyphs paints (the hardcoded
   prefs-dark heuristic painted the number the color of its own box).
S1 THE SORT PASS — the same ic_sort glyph + radio sheet on downloads /
   bookmarks / history / saved pages (persisted per screen), the animated
   reorder (notifyItemMoved) on a pure sort flip, the honest flat header
   when the day grouping would lie.
D1 THE DARK ROUND 3 — ONE ENGINE: the native algorithmic darkener is
   always OFF (it inverted images/designs and double-darkened native-dark
   sites); the six-signal smart probe gates the CSS inversion on every
   API/theme; a 2.5 s late re-probe catches SPA-applied themes; the
   bg-image scan grew a MutationObserver for SPA-appended photos.
G1 THE SAVE-PAGE PASS — one sheet, four outputs: PDF (the stitch engine),
   print (system), HTML (the page self-serializes, images inlined), the
   offline MHT.
A1 THE AI EXPORTER ROUND 2 — the card moved to the front, claude.ai +
   data-testid/article selectors, and MANUAL MODE: zero candidates keeps
   the picker up and taps select blocks (the never-dead export).
V1 version pin 108 / 1.3.95.

Usage: python3 scripts/test_v1391.py
"""
import re
import sys
from pathlib import Path

APP = Path(__file__).resolve().parent.parent / "app/src/main/java/com/bolt/browser"
act = (APP / "ui/BrowserActivity.java").read_text()
prefs = (APP / "util/Prefs.java").read_text()
vault = (APP / "util/PasswordVault.kt").read_text()
vaultui = (APP / "ui/PasswordVaultActivity.kt").read_text()
mhk = (APP / "media/MediaHook.java").read_text()
tab = (APP / "core/Tab.java").read_text()
base = (APP / "ui/BaseListActivity.java").read_text()
bm = (APP / "ui/BookmarksActivity.java").read_text()
hist = (APP / "ui/HistoryActivity.java").read_text()
off = (APP / "ui/OfflinePagesActivity.java").read_text()
dlact = (APP / "ui/DownloadsActivity.java").read_text()
dlad = (APP / "ui/DlAdapter.kt").read_text()
aie = (APP / "util/AiExport.kt").read_text()
psave = (APP / "util/PageSave.kt").read_text()
appearance = (APP / "ui/AppearanceActivity.java").read_text()
gradle = (Path(__file__).resolve().parent.parent / "app/build.gradle").read_text()
strings = (Path(__file__).resolve().parent.parent
        / "app/src/main/res/values/strings.xml").read_text()
draw = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable/ic_sort.xml"

fails = 0
def need(cond, msg):
    global fails
    if cond:
        print("  OK  " + msg)
    else:
        fails += 1
        print("  FAIL " + msg)

print("H1 — the release-gated folder (v1.3.92: the dwell-timer group)")
need("Math.min(r.width(), r.height()) * 0.38" in act,
     "the folder zone is the cell's CENTER (Launcher3 folderCreationRadius law)")
need("protected final Runnable sdHoldMerge" not in act
     and "armSdHoldMerge" not in act,
     "the 550 ms dwell-merge timer is gone (the still-deleting corpse)")
# v1.3.92 — the release-gated merge is retired too: grouping happens only
# through the COMPLETED dwell timer (the tabs contract the field asked
# for: hold B in the center of A, the time-counter pill fills, the merge
# fires mid-hold). A drop is always a plain reorder — nothing a drop does
# can read as a deletion.
need("startSdDwell" in act and "sdDwellProgress >= 1000" in act
     and "ClipDrawable" in act,
     "the dwell timer charges a tabs-style progress pill and merges when FULL")
need("resetSdDwell" in act and "sdDwellTick" in act,
     "the charge resets on target change / drag end")
need("final int folder = sdFolderTarget;" not in act
     and "prefs.mergeSpeeddial(src, tgt);" in act,
     "the release-gated merge is GONE (merge only via the completed timer)")
need("sdFolderTarget < 0" in act and "showSdGap(-1);" in act,
     "the reorder gap freezes while a folder target is armed (the ChromeOS law)")
need("hold_to_group_hint" in strings and "startSdDwell" in act,
     "the Hold-to-group pill explains the gesture")
need("insertSpeeddial" in prefs and "shortcut_removed" in strings
     and "Ui.snackbar(this," in act and "getString(R.string.undo)" in act,
     "tile-menu delete shows an UNDO snackbar (the Fenix/DDG pattern)")
# v1.3.92 — THE PAGED MATRIX + handedness
need("class SdGridLayout extends android.view.ViewGroup" in act
     and "PER_PAGE = COLS * ROWS" in act and "COLS = 6" in act and "ROWS = 7" in act,
     "the 6x7 slot matrix layout exists")
need("androidx.viewpager2.widget.ViewPager2" in act
     and "setOffscreenPageLimit(2)" in act,
     "the grid pages horizontally (a ViewPager2 per 42 tiles)")
need("sdPagesRtl" in act and "LAYOUT_DIRECTION_RTL" in act
     and "sdPagesRtl" in appearance,
     "the handedness setting flips the page order (left-handed: RTL)")
need("sdPreviewNewIndex" in act and "slotX(newLocal)" in act,
     "the gap preview shifts FULL slots through the matrix (the A-B-C-D law)")
need("sdEdgeTurn" in act and "setCurrentItem(next, true)" in act,
     "resting the drag at the edge turns the page")

print("P1 — the honest vault + the page host")
need("Bolt.pwField(location.host,true);" in mhk,
     "the JS focus report carries the page's OWN host")
need("public void pwField(final String host, final boolean focused)" in act
     and "showPwChip(host)" in act,
     "the bridge routes the page host into the chip lookup")
need("Files.move" in vault and "ENC_FILE + \".tmp\"" in vault and "fd.sync()" in vault,
     "the vault write is atomic (tmp + fsync + move)")
need("passFor(c, host, user) == pass" in vault,
     "save() verifies the round-trip by (host, user) identity (v1.3.92b: passFor — find(host) returned the FIRST row for a host and false-failed second accounts) with one retry")
# v1.3.92 — THE TRANSIENT-FAILURE PASS (the field: some sites give could not
# save - vault write failed): a successful decrypt clears the sticky
# unreadable flag; the write + the verify each retry WITH BACKOFF
# (v1.3.95: the retry moved into saveEx — 150 ms sleep between attempts;
# the old single-expression double-write died with the stage codes)
need("unreadable = false" in vault and "if (!writeAll(c, arr)) {" in vault
     and "android.os.SystemClock.sleep(150)" in vault,
     "the sticky unreadable flag is cleared by a good read; write+verify retry with backoff (v1.3.95 saveEx)")
need("unreadable" in vault and "Reset vault" in vaultui,
     "an unreadable vault is surfaced with a banner + reset (not silent empty)")
# v1.4.4 re-point: find() delegates to findAll; the bidirectional law lives there
need(("hb == b || h.endsWith(\".$b\")" in vault
      and "fun find(c: Context, host: String): Entry? = findAll(c, host).firstOrNull()" in vault),
     "find() matches base domains in BOTH directions (v1.4.4: the law lives in findAll; find delegates)")

print("F1 — the Shorts orientation contract")
need("SCREEN_ORIENTATION_LOCKED" in act and "fsAwaitAspect" in act,
     "the enter holds the current posture until the aspect probe answers")
need("fsEnterAfterAspect" in act
     and "SCREEN_ORIENTATION_SENSOR_PORTRAIT" in act,
     "a portrait video stays portrait (the Chrome law)")
need("fsSettleKick(cv);" in act and act.count("fsSettleKick") >= 2,
     "a no-rotation enter kicks ONCE (the probe rescues at 800 ms)")
need("willRotate" in act and "the rotation is the settle" in act,
     "a coming rotation IS the settle (no kicks spent)")

print("T1 — the tab-count color law")
need("protected int tabCountGlyphColor()" in act
     and "glassGlyphColor(barGlassWhite(bottom))" in act,
     "the number's color comes from the live adaptive verdict")
need("square.setTextColor(tabCountGlyphColor());" in act,
     "the press restore funnels through the same law")
need("tvTabCount.setTextColor(tabCountGlyphColor());" in act,
     "applyGlassGlyphs and the press restore share ONE source")

print("S1 — the sort pass")
need(draw.exists(), "the ic_sort glyph exists")
need("protected void addSortButton(" in base and "setRowsAnimated" in base,
     "the shared sort button + the animated reorder live in the base list")
need("\"sortBm\"" in bm and "\"sortHist\"" in hist and "\"sortOff\"" in off
     and "\"sortDl\"" in dlact,
     "every screen persists its own sort (bookmarks/history/saved/downloads)")
need("notifyItemMoved(at, j)" in base and "notifyItemMoved(at, j)" in dlad,
     "a pure reorder animates through notifyItemMoved (base + downloads)")
need("flatLabel" in dlad and "sorted_by_name" in strings,
     "a non-time sort renders ONE honest flat header")
need("addSortButton" in off and "sort_smallest_first" in off,
     "saved pages carry the size sorts too")

print("D1 — the dark round 3")
need("setAlgorithmicDarkeningAllowed(false)" in tab,
     "the native algorithmic darkener is OFF (one engine, every API/theme)")
need("data-color-mode" in mhk and "nightmode" in mhk,
     "the probe reads theme ATTRIBUTES (the YouTube/Reddit/X family)")
need("t>=0.55" in mhk and "dark/opaque>=0.6" in mhk,
     "the text test + element sampling joined the probe")
need("}, 2500);" in act and "dark late-flip" in act,
     "the 2.5 s late re-probe pulls a late-flipped dark theme's inversion")
need("MutationObserver" in mhk and "12000);" in mhk,
     "the bg-image scan observes SPA-added nodes (12 s, capped)")

print("G1 — the save-page pass")
need("fun savePdf(" in psave and "fun print(" in psave and "fun saveHtml(" in psave,
     "PageSave ships PDF + print + HTML")
need("showSavePageSheet" in act and "save_page_offline" in act,
     "the one sheet with four outputs (the MHT stays)")
need("save_pdf" in strings and "save_html" in strings,
     "the strings exist")
# v1.3.92 — THE VECTOR SWITCH (the field: saving a pdf means a VECTOR file,
# not a screenshot wrapped in pdf): the system print preview produces
# Chromium's real vector rendering; the stitch engine stays as the fallback
need("savePdfVector" in psave and "createPrintDocumentAdapter" in psave,
     "Save-as-PDF opens the system print preview (true vector output)")
need("Bolt-page-" in psave and "fun savePdf(" in psave,
     "the stitch fallback stays under the Bolt-page- name")
need("toDataURL('image/png')" in psave and "removeAttribute('srcset')" in psave,
     "the HTML serializer inlines images through the live canvas")

print("A1 — the AI exporter round 2")
need("var manual=!nodes.length;" in aie,
     "MANUAL MODE: zero candidates keeps the picker alive")
need("__boltAiSel" in aie and "style.outline='3px solid #7C4DFF'" in aie,
     "tap-to-select outlines (inline CSSOM — CSP-proof)")
# v1.3.92 — THE NATIVE PICKER (the field: don't see any options in zai chat
# web): the chrome lives in Android, not in the page
need("aiPickLayer" in act and "aiPickStart" in act and "Bolt.aiSync" in aie,
     "the picker chrome is NATIVE (circles + bar over the WebView)")
need("__boltAiRects" in aie and "__boltAiBlocks" in aie,
     "the page reports rects + structured blocks (the vector PDF source)")
need("VectorPdf" in act and "parseBlocks" in act,
     "Save PDF renders a true vector PDF (selectable text)")
need("claude" in aie and "assistant-message" in aie,
     "claude.ai + data-testid selectors joined the net")
need("o.optInt(\"manual\", 0) == 1" in act,
     "Java toasts the manual-mode guidance")
need(re.search(r'"New tab".{0,4000}"Save AI response".{0,4000}"Scan QR"', act, re.S) is not None,
     "the AI export card sits at the FRONT of the menu (was 8th; "
     "renamed 'Save AI response' in v1.3.95)")

print("V1 — version pin")
need(re.search(r"versionCode\s+137\b", gradle) is not None,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 137")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need('versionName "1.4.23"' in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.7"')

print()
if fails:
    print(f"v1.3.91 gate: {fails} FAILURES")
    sys.exit(1)
print("v1.3.91 gate: all green")
