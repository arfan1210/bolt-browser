#!/usr/bin/env python3
"""v1.3.97 gate — the v1.3.96 field report: the phantom refresh, the slow
menu, the direct drag, the iconless menu.

PHANTOM REFRESH — "The shortcut icons causes home page to refresh when
   dragged." THREE corpses, one symptom:
   (a) PULL-TO-REFRESH ON THE HOME: the gesture is a web-page reload, but
       the listener ran a FULL refreshHome() on the start tab. A drag
       begun on a tile (finger moving before the long-press arms) falls
       through to the home ScrollView; its unconsumed overscroll became a
       pull; release rebuilt the whole home. Standard behavior
       (Chrome/Firefox): pull-to-refresh reloads the PAGE, never the
       start screen. showTab now sets setPullEnabled(pref && !t.isHome)
       per tab, live with the pref.
   (b) THE NO-OP DROP: a moved drop ALWAYS ran refreshHome() — even when
       the tile landed where it already lived (insert == src / src+1):
       scroll reset, pager reset to page 0, entrance replay. A no-op drop
       now builds NOTHING.
   (c) THE REAL-REORDER RESET: a genuine reorder rebuilt at scroll 0 /
       page 0 with a possible stagger replay. Drag-driven rebuilds are
       QUIET + POSITION-TRUE (refreshHomeQuiet): the same pager page +
       scroll come back (homeRestorePage/homeRestoreScroll, consumed once
       by buildHomeView), and the entrance is pre-debounced
       (lastHomeGridAnim) so it never replays. The dwell merge rides it.

MENU TIMING — "the duration to activate the menu is a bit too long":
   the hold-menu armed at 1200 ms past the lift (~1.6 s from touch-down).
   550 ms now (launcher cadence: lift ~400 ms + hold ~550 ms ≈ 1 s), and
   the release-without-move door (sdDragEnd) still opens the menu — two
   doors, neither of which can ambush a drag (6 dp drift still cancels
   the timer for good). A menu-started drag (the Move row) does not arm
   the timer at all (sdDragNoMenu): the still moment of re-grabbing the
   tile is placement, not a menu request.

MENU ICONS — "add icons to the menu, pure text looks boring": the tile
   menu was a plain AlertDialog.setItems text list; it is now the app's
   standard showSheetMenu icon-row sheet (the group sheet's family):
   Open / Open in new tab / Edit / Move / New folder / Delete, each with
   its glyph. New vectors: ic_open_new, ic_move, ic_folder_plus (all
   XML-parsed by this gate — the parse-gate law extends to drawables).
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
GR = os.path.join(ROOT, "app/build.gradle")
DR = os.path.join(ROOT, "app/src/main/res/drawable")

fails = 0

def need(cond, why):
    global fails
    tag = "PASS" if cond else "FAIL"
    print(f"  {tag} {why}")
    if not cond:
        fails += 1

def slurp(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

ba = slurp(BA)
gradle = slurp(GR)

print("A — pull-to-refresh is a WEB-page gesture (the phantom refresh, corpse a)")
need(re.search(r"setPullEnabled\(prefs\.pullToRefresh\(\) && !t\.isHome\)", ba) is not None,
     "showTab enables the pull only on WEB tabs (never the home/start tab)")
m = re.search(r"mac\.setOnRefreshListener\(\(\) -> \{([\s\S]*?)\n        \}\);", ba)
need(m is not None and "refreshHome()" in m.group(1),
     "the (now unreachable) listener home branch still degrades safely")
# the hook must run on every tab switch, not once at init
need(re.search(r"Tab t = tm\.tabs\.get\(idx\);[\s\S]{0,900}?setPullEnabled\(prefs\.pullToRefresh\(\) && !t\.isHome\)", ba) is not None,
     "the per-tab pull hook sits inside showTab (switch-time, pref read live)")

print("B — the no-op drop builds NOTHING (corpse b)")
m = re.search(r"protected void sdDragEnd\(float x, float y\) \{[\s\S]*?ui\.post\(\(\) -> \{([\s\S]*?)\}\);", ba)
need(m is not None, "sdDragEnd's posted body found")
if m:
    body = m.group(1)
    need(re.search(r"if \(insert >= 0 && insert != src && insert != src \+ 1\) \{\s*\n\s*prefs\.moveSpeeddial\(src, insert\);\s*\n[\s\S]{0,600}?refreshHomeQuiet\(\);", body) is not None,
         "the rebuild (refreshHomeQuiet) rides INSIDE the real-reorder guard")
    # the moved branch must contain exactly ONE rebuild call and no bare refreshHome
    moved_branch = re.search(r"\} else if \(moved && src >= 0\) \{([\s\S]*?)\n                \}", body)
    need(moved_branch is not None
         and "refreshHomeQuiet();" in moved_branch.group(1)
         and "refreshHome();" not in moved_branch.group(1),
         "a no-op drop rebuilds nothing (refreshHome only behind a real insert)")

print("C — quiet + position-true rebuilds (corpse c)")
need(re.search(r"protected void refreshHomeQuiet\(\) \{[\s\S]*?homeRestorePage = sdPager\.getCurrentItem\(\);", ba) is not None
     and re.search(r"protected void refreshHomeQuiet\(\) \{[\s\S]*?homeRestoreScroll = homeScroll\.getScrollY\(\);", ba) is not None
     and re.search(r"protected void refreshHomeQuiet\(\) \{[\s\S]*?lastHomeGridAnim = android\.os\.SystemClock\.uptimeMillis\(\);", ba) is not None,
     "refreshHomeQuiet snapshots page + scroll and pre-debounces the entrance")
need(re.search(r"if \(homeRestorePage >= 0 && homeRestorePage < pageCount\) \{[\s\S]{0,320}?pager\.setCurrentItem\(rp, false\);", ba) is not None,
     "buildHomeView restores the pager page (consumed once)")
need(re.search(r"if \(homeRestoreScroll > 0\) \{[\s\S]{0,240}?scroll\.scrollTo\(0, ry\);", ba) is not None,
     "buildHomeView restores the vertical scroll (consumed once)")
need(re.search(r"sdDwellProgress >= 1000[\s\S]{0,1100}?refreshHomeQuiet\(\);", ba) is not None,
     "the dwell merge rebuilds quiet + position-true too")

print("D — the menu is quick; the drag is primary")
need("ui.postDelayed(sdHoldMenu, 550);" in ba,
     "the hold-menu arms at 550 ms past the lift (was 1200 — the launcher cadence)")
need("ui.postDelayed(sdHoldMenu, 1200);" not in ba,
     "no 1200 ms arm survives anywhere")
need(re.search(r"if \(!sdDragNoMenu\) \{\s*\n\s*ui\.removeCallbacks\(sdHoldMenu\);\s*\n\s*ui\.postDelayed\(sdHoldMenu, 550\);", ba) is not None,
     "the hold-menu arm is guarded by sdDragNoMenu (menu-started drags skip it)")
need(re.search(r"endSdDrag\(\) \{[\s\S]{0,4200}?sdDragNoMenu = false;", ba) is not None,
     "endSdDrag re-arms the hold-menu for the next drag (v1.3.98: window widened — the pager-clash restore sits between)")
need(re.search(r"sdDragMove\(float x, float y\) \{[\s\S]{0,1400}?removeCallbacks\(sdHoldMenu\)", ba) is not None,
     "the 6 dp drift cancel survives (a slow drag start never sees the menu)")
need(re.search(r"if \(!moved && src >= 0\) \{\s*\n\s*sdTileMenu\(src\);", ba) is not None,
     "release-without-move still opens the menu (the second door)")

print("E — the tile menu is the icon-row sheet")
need(re.search(r"protected void sdTileMenu\(final int idx\) \{[\s\S]{0,700}?List<MenuRow> rows", ba) is not None,
     "sdTileMenu builds MenuRow rows (the sheet, not a text dialog)")
need(re.search(r"protected void sdTileMenu[\s\S]*?showSheetMenu\(title, rows\);", ba) is not None,
     "sdTileMenu renders through showSheetMenu")
seg = ba.split("protected void sdTileMenu")[1].split("protected View sdCellOfIndex")[0]
for ic, why in (("ic_globe", "Open"), ("ic_open_new", "Open in new tab"),
                ("ic_edit", "Edit shortcut"), ("ic_move", "Move"),
                ("ic_folder_plus", "New folder"), ("ic_delete", "Delete")):
    need(f"row(R.drawable.{ic}," in seg, f"the {why} row carries its icon ({ic})")
need("setItems(" not in seg, "no plain text list survives in the tile menu")
need("sdDragNoMenu = true;" in seg,
     "the Move row marks the drag menu-less (no re-pop mid-drag)")
for ic in ("ic_open_new", "ic_move", "ic_folder_plus"):
    p = os.path.join(DR, ic + ".xml")
    ok = False
    if os.path.exists(p):
        try:
            root = ET.parse(p).getroot()
            # attrs live in the android namespace — match by local name
            attrs = {k.split("}")[-1]: v for k, v in root.attrib.items()}
            ok = root.tag.endswith("vector") and attrs.get("viewportWidth") == ("960" if ic == "ic_open_new" else "24")  # v1.4.17 re-point (2026-11-18): ic_open_new is a Material Symbols glyph now (viewBox 0 -960 960 960, re-based via the translateY(960) group); ic_move/ic_folder_plus keep the 24 viewport
        except Exception:
            ok = False
    need(ok, f"{ic}.xml exists and parses as a 24dp vector")

print("V — version pin")
need("versionCode 144" in gradle and 'versionName "1.4.30"' in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "build.gradle pins 144 / 1.4.30")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print()
if fails:
    print(f"v1.3.97 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.3.97 gate: all green")
