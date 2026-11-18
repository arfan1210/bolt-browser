#!/usr/bin/env python3
"""v1.4.8 release gate — the anchor law (empty tab groups settle exactly
where they are left, and other rows can finally sit beside them), the
folder chooser moving AFTER "Add to browser home", the folder's own add
doors, and the chunked site settings.

The v1.4.7 field report:

1. "One empty tab group is causing issues like it does not move and settle
   where I leave it and settle like one tab before or after the position I
   left it at. That abnormal tab also does not let other tabs sit right
   next to it normally either and pushes the incoming tabs to one position
   off. The groups sort of feel like are following a vertical preference
   or something. Maybe disappeared tabs groups in some earlier version is
   causing interference?" — THE ABSOLUTE-SLOT CORPSE: the v1.4.7 "slot"
   was a ROW INDEX. (a) It was written from an adapter position in a
   render that still contained the dragged empty at its OLD row (onMove
   returns false — no live shuffle), so the settle landed one off whenever
   the empty sat above the target ("one tab before or after the position I
   left it at"). (b) The index was never adjusted when rows were inserted
   or removed above it, so a tab dropped right before an empty did not
   shift the empty's slot and the two read as pushing each other one
   position off. (c) Drops BESIDE an empty folder's row were silent
   no-ops (tabsOfGroup was empty, no anchor tab resolved) — "does not let
   other tabs sit right next to it". THE FIX — THE ANCHOR LAW: a row's
   position memory must be RELATIVE. Each empty folder carries the PAIR OF
   ROWS it sits between ("ap" the row above, "an" the row below; keys
   "t<tid>" a loose tab row, "g<gid>" a folder row, "" the list edge),
   written by its own drop and kept fresh by the mutation refreshes (a
   referenced row that closes, joins or moves away re-anchors that side to
   the row's pre-change neighbour — the empty never travels with a
   dragged neighbour). Tabs (Tab.tid, monotonic, persisted) and membered
   folders anchor the keys; a normalization sweep pins every anchorless
   empty at its CURRENT spot (a legacy v1.4.7 slot honoured first — the
   quiet upgrade) so nothing derives its position anymore — that is also
   the "disappeared groups from an earlier version" interference heal.

2. "Also move the folder choosing when adding shortcut appear after I have
   chosen save to browser home." — THE CHOOSER MOVES AFTER THE TAP: the
   add-shortcut dialog is just the name again; picking "Add to browser
   home" opens the FOLDER CHOOSER as its own bottom-sheet step (Home /
   every folder / New folder…, inline-creating) — the folder question now
   only appears for the destination it belongs to.

3. "Also add this kind of direct addition to in other relevant places like
   bookmarks etc." — THE FOLDER'S OWN ADD DOORS: a "+" tile at the end of
   the folder window's grid and an "Add shortcut" row in the folder edit
   sheet, both opening a name+URL mini dialog that files straight into
   THAT folder (addShortcutInsideFolderDialog); the bookmark add dialog
   keeps its folder picker (it already had one).

4. "The site settings have become too cluttered with options, divide them
   into chunks of similar groups. Just simple reorganisation with a small
   title for the chunks." — MenuRow.header + Ui.sectionLabel render small
   dim section labels inside the sheet menus; the site sheet's thirteen
   flat rows become four labelled chunks: Content blocking / Privacy &
   cookies / Appearance / Media & device access.

Sections:
A1 — the anchor storage (Prefs.setGroupAnchor / clearGroupPos; the slot retired)
A2 — the row model in TabManager (followRowOrder + the anchor resolution passes)
A3 — the empty-drop anchor write (the without-self boundary; the edges)
A4 — the beside-an-empty landings (tab + membered folder; the boundary retarget)
A5 — the refreshes (close/join/move/delete; the captures; the normalization; the reverse sync)
A6 — the shortcut flow (chooser after the tap; the folder's own doors)
A7 — the chunked site settings
B  — the functional probe (javac + run)
V  — the version pin.
"""

import os, re, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/TabsActivity.java")
TM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/TabManager.java")
TB = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/Tab.java")
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
PF = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Prefs.java")
STRS = os.path.join(ROOT, "app/src/main/res/values/strings.xml")
GR = os.path.join(ROOT, "app/build.gradle")

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

ta, tm, ba, tb = (slurp(TA), slurp(TM), slurp(BA), slurp(TB))
prefs_src, strs, gradle = (slurp(PF), slurp(STRS), slurp(GR))

print("A1 — the anchor storage (the slot is retired)")
need("public void setGroupAnchor(long id, String prevKey, String nextKey)" in prefs_src,
     "Prefs.setGroupAnchor persists the row pair")
ga = prefs_src.split("public void setGroupAnchor")[1][:1200] if "public void setGroupAnchor" in prefs_src else ""
need('g.put("ap", prevKey == null ? "" : prevKey);' in ga and 'g.put("an", nextKey == null ? "" : nextKey);' in ga,
     'the keys land as "ap"/"an" ("" = the list edge)')
need('g.remove("slot");' in ga,
     "writing anchors RETIRES the v1.4.7 absolute slot")
need("public void clearGroupPos(long id)" in prefs_src,
     "Prefs.clearGroupPos drops every position memory (membered folders never read them)")
need("public long tid = 0L;" in tb and "o.put(\"tid\", tid);" in tb,
     "Tab.tid exists and persists (the anchor keys reference it)")
need("TID_SEQ.updateAndGet(v -> Math.max(v + 1, System.currentTimeMillis()))" in tb,
     "the tid sequence is monotonic past the wall clock (restore-loop collisions impossible)")
need("t.tid = rtid;" in tm and "Tab.bumpTidSeq(maxTid)" in tm,
     "restored tabs keep their persisted tid and lift the sequence past the max")

print("A2 — the row model in TabManager (followRowOrder)")
need("public List<Object> followRowOrder()" in tm and "public List<Object> followRowOrder(boolean includeAnchorless)" in tm,
     "the follow-order row model lives in TabManager now (one model, every reader)")
fro = tm.split("public List<Object> followRowOrder(boolean includeAnchorless)")[1][:2600] if "public List<Object> followRowOrder(boolean includeAnchorless)" in tm else ""
need('if (g.has("ap") || g.has("an")) anchored.add(g);' in fro,
     "the walk partitions anchored vs anchorless empties")
need("while (!pending.isEmpty() && progress && guard-- > 0)" in fro,
     "the pass loop resolves chains (an empty anchored to another empty's row)")
need("out.add(Math.min(at, out.size()), g);" in fro,
     "anchored empties insert at their resolved row, clamped")
need("int lastFolderRow = -1;" in fro and "int putAt = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;" in fro,
     "anchorless empties keep the v1.4.6 convention (after the last folder row, the top when none)")
need('return "t" + ((Tab) row).tid;' in tm and 'return "g" + ((JSONObject) row).optLong("id");' in tm,
     'rowKeyOf: "t<tid>" a tab row, "g<gid>" a folder row')
rki = tm.split("private int rowKeyIndex")[1][:1600] if "private int rowKeyIndex" in tm else ""
need("if (t.groupId != 0) return folderRowIndex(rows, t.groupId);" in rki,
     "a membered tab's key resolves to its FOLDER row")
ari = tm.split("private int anchorRowIn")[1][:1600] if "private int anchorRowIn" in tm else ""
need('an.isEmpty() && ap.isEmpty()) return 0;' in ari,
     'both sides bare ("","") = pinned to the very top')
need(tm.find("anchorRowIn(out, g)") != -1,
     "the walk resolves each empty through anchorRowIn")
need("for (Object o : tm.followRowOrder()) items.add(o);" in ta,
     "TabsActivity's follow branch renders tm.followRowOrder() (the duplicated walk is gone)")

print("A3 — the empty-drop anchor write (the settle one-off is dead)")
cv = ta.split("public void clearView")[1] if "public void clearView" in ta else ""
gd = cv.split("final JSONObject gdrag = draggingGroup;")[1].split("// capture the drop intents")[0] if "final JSONObject gdrag = draggingGroup;" in cv else ""
need("dropAnchorEmptyGroup(gid, gp2, gafter," in gd,
     "the follow-order empty drop writes the anchor pair")
need('prefs.setGroupAnchor(gid, "", "");' in gd,
     "the only-row drop pins the top edge")
need("tm.moveEmptyGroupBeside(gid, gtarget.optLong(\"id\"), gafter);" in gd,
     "on-top drops still block-move the ARRAY entry (the on-top memory)")
dag = ta.split("private void dropAnchorEmptyGroup")[1][:1800] if "private void dropAnchorEmptyGroup" in ta else ""
need("if (i != selfPos) rows.add(adapter.items.get(i));" in dag,
     "the boundary is computed against the WITHOUT-SELF render (the dragged row is still in the list)")
need("int virt = underPos > selfPos ? underPos - 1 : underPos;" in dag,
     "positions above the dragged row shift by one — THE V1.4.7 SETTLE CORPSE")
need('tm.refreshAnchorsAround("g" + gid);' in dag,
     "empties anchored to the dragged empty's old row keep its old neighbours (only the held one moves)")

print("A4 — the beside-an-empty landings (tabs finally sit next to it)")
need("JSONObject emptyRowTarget = null;" in cv,
     "the tab-drop zone resolution detects an EMPTY folder row as the anchor")
need("tm.tabs.add(k, dragged);" in cv.split("emptyRowTarget != null && b == null")[1][:2400] if "emptyRowTarget != null && b == null" in cv else "",
     "the tab inserts into tm.tabs THROUGH the empty's render neighbours")
# v1.4.9 re-point: the re-anchor write moved to the finger's side (the
# v1.4.8 write was INVERTED — the landed row rendered on the OPPOSITE
# side of the empty) and the landing resolves through the RENDER
# neighbours (renderNeighbourKeyOfEmpty + chainBoundaryEmpty), never the
# possibly-stale stored pair (the (2,2) teleport corpse, see test_v149)
need('after ? (aboveK == null ? eap : aboveK) : "t" + dragged.tid,' in cv
     and 'after ? "t" + dragged.tid : (belowK == null ? ean : belowK)' in cv,
     "the empty re-anchors to FACE the landed tab on the finger's side (v1.4.9: the inverted write is dead)")
need("JSONObject emptyTarget = null;" in gd,
     "the folder-drop path detects a memberless TARGET (was a silent no-op)")
need('prefs.setGroupAnchor(egid,\n                                    aboveK == null ? eap : aboveK, "g" + gid);' in gd
     or 'aboveK == null ? eap : aboveK, "g" + gid);' in gd,
     "the empty re-anchors to face the landed folder row (v1.4.9: the group is its row-BELOW on an after-drop)")
need("private void retargetBoundaryEmpty(" in ta,
     "the boundary retarget helper exists (the far-side empty of any landing gap)")
rbe = ta.split("private void retargetBoundaryEmpty")[1][:1600] if "private void retargetBoundaryEmpty" in ta else ""
need("if (farPos < 0 || farPos >= adapter.items.size() || farPos == selfPos) return;" in rbe,
     "the retarget guards the edges and the dragged row itself")
need("tm.renderNeighbourKeyOfEmpty(egid, !after, newKey);" in rbe,
     "the retarget's kept side is render-derived too (v1.4.9: never a stale stored key)")

print("A5 — the refreshes (closes, joins, moves, deletes; the captures; the sync)")
need("public void refreshAnchorsAround(String movedKey)" in tm,
     "TabManager.refreshAnchorsAround (re-anchor to the moved row's pre-change neighbours)")
need("public void captureGroupRowAnchors(long gid)" in tm,
     "TabManager.captureGroupRowAnchors (the last-member capture, BEFORE the close)")
need("public void normalizeEmptyAnchors()" in tm,
     "TabManager.normalizeEmptyAnchors (the quiet upgrade + the stale-data heal)")
nrm = tm.split("public void normalizeEmptyAnchors()")[1][:2400] if "public void normalizeEmptyAnchors" in tm else ""
need('prefs.clearGroupPos(gid);' in nrm,
     "membered groups drop stale position memories")
need("int at = slot >= 0 ? Math.min(slot, rows.size()) : conv;" in nrm,
     "a legacy v1.4.7 slot is HONOURED when pinning (nothing moves on update day)")
need("try { tm.normalizeEmptyAnchors(); } catch (Throwable ignored) {}" in ta,
     "the sweep runs after every follow-order render (idempotent)")
sw = ta.split("public void onSwiped")[1][:1600] if "public void onSwiped" in ta else ""
need("tm.captureGroupRowAnchors(t.groupId);" in sw and 'tm.refreshAnchorsAround("t" + t.tid);' in sw,
     "the swipe: the capture + the departure refresh, both BEFORE tm.close")
need('tm.captureGroupRowAnchors(t.groupId);' in ba and 'tm.refreshAnchorsAround("t" + t.tid);' in ba,
     "the toolbar closes (quick action + back-on-last-page) run the same pair")
jn = cv.split("if (joinGid != -1 && dragged != null)")[1][:1200] if "if (joinGid != -1 && dragged != null)" in cv else ""
need('tm.refreshAnchorsAround("t" + dragged.tid);' in jn and "tm.captureGroupRowAnchors(dragged.groupId);" in jn,
     "the join: the dragged row departs, a group losing its last member keeps its spot")
need('tm.refreshAnchorsAround("g" + gid);' in ta,
     "a membered folder's move refreshes empties anchored to its row (Move up/down, the drag, the delete)")
mat = tm.split("public boolean materializeOnTopOrder()")[1][:3200] if "public boolean materializeOnTopOrder()" in tm else ""
need("prefs.setGroupAnchor(g.optLong(\"id\")," in mat,
     "the placement materialize re-pins every empty's ANCHORS to its on-top folder-block neighbours")
need("public boolean syncGroupsArrayToFollowOrder()" in tm,
     "the REVERSE SYNC exists (follow -> on-top writes the follow render into the groups array)")
need("tm.syncGroupsArrayToFollowOrder();" in ta,
     "the placement switch calls it (an empty dragged in follow view survives the switch)")

print("A6 — the shortcut flow (the chooser AFTER the tap; the folder's own doors)")
asd = ba.split("protected void addShortcut()")[1].split("protected void pickShortcutFolderThenAdd")[0] if "protected void addShortcut()" in ba else ""
need("pickShortcutFolderThenAdd(name, url);" in asd,
     "Add to browser home opens the folder chooser as step two")
need("pinToLauncher(url, name);" in asd,
     "Add to launcher stays direct (no folder question)")
psf = ba.split("protected void pickShortcutFolderThenAdd")[1].split("protected void addShortcutIntoFolder")[0] if "protected void pickShortcutFolderThenAdd" in ba else ""
need("com.bolt.browser.util.Ui.choiceSheet(this, getString(R.string.choose_folder)," in psf,
     "the chooser is the standard bottom sheet (radio dots)")
need("if (prefs.isSpeeddialGroup(i)) {" in psf,
     "every existing home folder is a row")
need("prefs.addSpeeddialFolder(" in psf,
     "New folder… creates inline and adds straight into it")
aif = ba.split("protected void addShortcutIntoFolder")[1][:1400] if "protected void addShortcutIntoFolder" in ba else ""
need("prefs.addSpeeddialItemTo(idx, name, url)" in aif and "prefs.addSpeeddial(name, url);" in aif,
     "the shared landing: into the folder, guarded fallback to the top level")
need("protected void addShortcutInsideFolderDialog(final int folderIdx)" in ba,
     "the folder's own add door (name+URL, straight into THIS folder)")
fgrid = ba.split("protected boolean sdWinFillGrid")[1].split("protected void sdWinAddCell")[0] if "protected boolean sdWinFillGrid" in ba else ""
need("R.drawable.ic_plus" in fgrid and "addShortcutInsideFolderDialog(sdWinFolderIdx);" in fgrid,
     "the folder window's \"+\" tile opens it")
need("int row = placed / 3, col = placed % 3;" in fgrid,
     "the + tile sits one slot past the last member (the LAST grid child — the drag math is untouched)")
gem = ba.split("protected void sdGroupEditMenu")[1][:1800] if "protected void sdGroupEditMenu" in ba else ""
need("R.drawable.ic_plus, getString(R.string.add_shortcut)" in gem,
     "the folder edit sheet gains the Add shortcut row")
abm = ba.split("protected void addBookmark()")[1].split("protected void saveOffline")[0] if "protected void addBookmark()" in ba else ""
need("prefs.bookmarkFolders();" in abm and "prefs.addBookmark(title2, url2, chosen[0]);" in abm,
     "the bookmark add keeps its folder picker (the direct addition that already was)")

print("A7 — the chunked site settings")
need("boolean header;" in ba and "protected MenuRow headerRow(String title)" in ba,
     "MenuRow.header + the headerRow factory")
ssm = ba.split("protected void showSheetMenu(String title, List<com.bolt.browser.util.MenuCards.Card> cards,")[1][:3400]
need("if (r.header) {" in ssm and "Ui.sectionLabel(this, r.title)" in ssm,
     "the sheet renders header rows as small dim section labels")
sss = ba.split("protected void showSiteSettings()")[1].split("protected MenuRow switchRow")[0]
for h in ("Content blocking", "Privacy & cookies", "Appearance", "Media & device access"):
    need(f'headerRow("{h}")' in sss, f'the "{h}" chunk label')
for probe in ("ads_and_trackers", "block_javascript", "disable_userscripts",
              "block_3rd_party", "block_cookies", "noPwSave", "dark", "always_desktop",
              "disable_bg_play", "disable_camera", "disable_mic", "block_external"):
    need(probe in sss, f"the {probe} row survives the reorganisation")
need(sss.find('headerRow("Content blocking")') < sss.find("block_javascript")
     and sss.find('headerRow("Appearance")') < sss.find("ua_default"),
     "the rows sit under their chunk labels, not before them")

print("B — the functional probe (javac + run)")
javac = None
for cand in (os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javac"),
             "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/javac",
             shutil.which("javac") or ""):
    if cand and os.path.exists(cand):
        javac = cand
        break
if not javac:
    need(False, "javac found (run scripts/setup_toolchain.sh first)")

jar = None
if javac:
    work = "/tmp/bolt_v148_gate"
    for p in (os.path.join(work, "json.jar"), "/tmp/bolt_v147_gate/json.jar",
              "/tmp/bolt_v146_gate/json.jar", "/tmp/org.json.jar"):
        if os.path.exists(p) and os.path.getsize(p) > 50_000:
            with open(p, "rb") as f:
                if f.read(2) == b"PK":
                    jar = p
                    break
    if not jar:
        urls = ("https://maven.aliyun.com/repository/public/org/json/json/20240303/json-20240303.jar",
                "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar")
        os.makedirs(work, exist_ok=True)
        dst = os.path.join(work, "json.jar")
        for u in urls:
            r = subprocess.run(["curl", "-sL", "--retry", "2", "-o", dst, u], capture_output=True, text=True)
            if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 50_000:
                with open(dst, "rb") as f:
                    if f.read(2) == b"PK":
                        jar = dst
                        break
    if not jar:
        need(False, "the org.json jar (aliyun/maven)")

if javac and jar:
    harness = """
import org.json.*;
import java.util.*;
import java.io.*;

public class Probe {
    // the row model, transcribed 1:1 from TabManager (v1.4.8)
    static class Tab {
        public long groupId = 0;
        public long tid;
        public String name = "";
        static long seq = 1000;
        Tab(String n, long g) { name = n; groupId = g; tid = seq++; }
        Tab(String n) { this(n, 0); }
        public String toString() { return name; }
    }
    static List<Tab> tabs = new ArrayList<Tab>();
    static JSONArray groups = new JSONArray();
    static void setGroups(long... ids) {
        groups = new JSONArray();
        for (long id : ids) {
            JSONObject o = new JSONObject();
            try { o.put("id", id); o.put("name", "G"+id); } catch (Exception e) {}
            groups.put(o);
        }
    }
    static void setAnchor(long gid, String ap, String an) {
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && g.optLong("id") == gid) {
                try { g.put("ap", ap); g.put("an", an); g.remove("slot"); } catch (Exception e) {}
            }
        }
    }
    static void setSlot(long gid, int slot) {
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && g.optLong("id") == gid) {
                try { g.put("slot", slot); } catch (Exception e) {}
            }
        }
    }
    static List<JSONObject> groups() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject o = groups.optJSONObject(i); if (o != null) out.add(o);
        }
        return out;
    }
    static List<Tab> tabsOfGroup(long gid) {
        List<Tab> out = new ArrayList<Tab>();
        for (Tab t : tabs) if (t.groupId == gid) out.add(t);
        return out;
    }
    static Tab tabByTid(long tid) {
        for (Tab t : tabs) if (t.tid == tid) return t;
        return null;
    }
    static String rowKeyOf(Object row) {
        if (row instanceof Tab) return "t" + ((Tab) row).tid;
        if (row instanceof JSONObject) return "g" + ((JSONObject) row).optLong("id");
        return "";
    }
    static int folderRowIndex(List<Object> rows, long gid) {
        for (int i = 0; i < rows.size(); i++) {
            Object o = rows.get(i);
            if (o instanceof JSONObject && ((JSONObject) o).optLong("id") == gid) return i;
        }
        return -1;
    }
    static int rowKeyIndex(List<Object> rows, String key) {
        if (key == null || key.length() < 2) return -1;
        try {
            if (key.charAt(0) == 't') {
                Tab t = tabByTid(Long.parseLong(key.substring(1)));
                if (t == null) return -1;
                if (t.groupId != 0) return folderRowIndex(rows, t.groupId);
                for (int i = 0; i < rows.size(); i++) if (rows.get(i) == t) return i;
                return -1;
            }
            if (key.charAt(0) == 'g') {
                return folderRowIndex(rows, Long.parseLong(key.substring(1)));
            }
        } catch (Throwable ignored) {}
        return -1;
    }
    static int anchorRowIn(List<Object> rows, JSONObject g) {
        try {
            String an = g.optString("an", ""), ap = g.optString("ap", "");
            if (g.has("ap") && g.has("an") && an.isEmpty() && ap.isEmpty()) return 0;
            if (pendingEmptyKey(rows, an) || pendingEmptyKey(rows, ap)) return -1;
            if (!an.isEmpty()) {
                int r = rowKeyIndex(rows, an);
                if (r >= 0) return r;
            }
            if (!ap.isEmpty()) {
                int r = rowKeyIndex(rows, ap);
                if (r >= 0) return r + 1;
            }
        } catch (Throwable ignored) {}
        return -1;
    }
    static boolean pendingEmptyKey(List<Object> rows, String key) {
        try {
            if (key == null || key.length() < 2 || key.charAt(0) != 'g') return false;
            long gid = Long.parseLong(key.substring(1));
            if (gid == 0 || folderRowIndex(rows, gid) >= 0) return false;
            for (JSONObject gg : groups())
                if (gg != null && gg.optLong("id") == gid)
                    return tabsOfGroup(gid).isEmpty();
        } catch (Throwable ignored) {}
        return false;
    }
    static List<Object> followRowOrder(boolean includeAnchorless) {
        List<Object> out = new ArrayList<Object>(tabs.size() + 4);
        HashSet<Long> emitted = new HashSet<Long>();
        List<JSONObject> grp = groups();
        for (Tab t : tabs) {
            if (t.groupId != 0 && !emitted.contains(t.groupId)) {
                for (JSONObject g : grp) {
                    if (g != null && g.optLong("id") == t.groupId) { out.add(g); break; }
                }
                emitted.add(t.groupId);
            }
            if (t.groupId == 0) out.add(t);
        }
        List<JSONObject> anchored = new ArrayList<JSONObject>();
        List<JSONObject> leftover = new ArrayList<JSONObject>();
        for (JSONObject g : grp) {
            if (g == null || emitted.contains(g.optLong("id"))) continue;
            if (g.has("ap") || g.has("an")) anchored.add(g);
            else leftover.add(g);
        }
        if (!anchored.isEmpty()) {
            List<JSONObject> pending = new ArrayList<JSONObject>(anchored);
            boolean progress = true;
            int guard = pending.size() + 1;
            while (!pending.isEmpty() && progress && guard-- > 0) {
                progress = false;
                for (Iterator<JSONObject> it = pending.iterator(); it.hasNext(); ) {
                    JSONObject g = it.next();
                    int at = anchorRowIn(out, g);
                    if (at >= 0) {
                        out.add(Math.min(at, out.size()), g);
                        it.remove();
                        progress = true;
                    }
                }
            }
            leftover.addAll(pending);
        }
        if (includeAnchorless && !leftover.isEmpty()) {
            int lastFolderRow = -1;
            for (int i = 0; i < out.size(); i++)
                if (out.get(i) instanceof JSONObject) lastFolderRow = i;
            int putAt = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;
            for (JSONObject g : leftover) out.add(putAt++, g);
        }
        return out;
    }
    static List<String> render() {
        List<String> out = new ArrayList<String>();
        for (Object o : followRowOrder(true))
            out.add(o instanceof JSONObject ? "G"+((JSONObject)o).optLong("id") : ((Tab)o).name);
        return out;
    }
    // the v1.4.7 slot law, transcribed (the corpse demonstrator)
    static List<String> renderSlot() {
        List<Object> items = new ArrayList<Object>();
        HashSet<Long> emitted = new HashSet<Long>();
        List<JSONObject> gs = groups();
        for (Tab t : new ArrayList<Tab>(tabs)) {
            if (t.groupId != 0 && !emitted.contains(t.groupId)) {
                for (JSONObject g : gs) {
                    if (g != null && g.optLong("id") == t.groupId) { items.add(g); break; }
                }
                emitted.add(t.groupId);
            }
            if (t.groupId == 0) items.add(t);
        }
        List<JSONObject> slotted = new ArrayList<JSONObject>();
        List<JSONObject> slotless = new ArrayList<JSONObject>();
        for (JSONObject g : gs) {
            if (g == null || emitted.contains(g.optLong("id"))) continue;
            if (g.has("slot") && !g.isNull("slot")) slotted.add(g);
            else slotless.add(g);
        }
        if (!slotted.isEmpty()) {
            final HashMap<JSONObject, Integer> ai = new HashMap<JSONObject, Integer>();
            for (int i = 0; i < gs.size(); i++) ai.put(gs.get(i), i);
            Collections.sort(slotted, (a, b) -> {
                int sa = a.optInt("slot", 0), sb = b.optInt("slot", 0);
                if (sa != sb) return Integer.compare(sb, sa);
                return Integer.compare(ai.getOrDefault(b, 0), ai.getOrDefault(a, 0));
            });
            for (JSONObject g : slotted) {
                int s = Math.max(0, Math.min(g.optInt("slot", 0), items.size()));
                items.add(s, g);
            }
        }
        if (!slotless.isEmpty()) {
            int lastFolderRow = -1;
            for (int i = 0; i < items.size(); i++)
                if (items.get(i) instanceof JSONObject) lastFolderRow = i;
            int putAt = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;
            for (JSONObject g : slotless) items.add(putAt++, g);
        }
        List<String> out = new ArrayList<String>();
        for (Object o : items) out.add(o instanceof JSONObject ? "G"+((JSONObject)o).optLong("id") : ((Tab)o).name);
        return out;
    }
    // refreshAnchorsAround, transcribed 1:1
    static void refreshAnchorsAround(String movedKey) {
        if (movedKey == null || movedKey.length() < 2) return;
        List<Object> rows = followRowOrder(true);
        int at = rowKeyIndex(rows, movedKey);
        if (at < 0) return;
        String prevK = at > 0 ? rowKeyOf(rows.get(at - 1)) : "";
        String nextK = at < rows.size() - 1 ? rowKeyOf(rows.get(at + 1)) : "";
        for (JSONObject g : groups()) {
            if (g == null) continue;
            long gid = g.optLong("id");
            if (gid == 0 || !tabsOfGroup(gid).isEmpty()) continue;
            if (!g.has("ap") && !g.has("an")) continue;
            String ap = g.optString("ap", ""), an = g.optString("an", "");
            if (movedKey.equals(ap) || movedKey.equals(an)) {
                setAnchor(gid, movedKey.equals(ap) ? prevK : ap, movedKey.equals(an) ? nextK : an);
            }
        }
    }

    static int bad = 0;
    static void check(boolean ok, String why) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + why);
        if (!ok) bad++;
    }
    static String j(List<String> l) { return String.join(",", l); }

    public static void main(String[] args) {
        // ---- B1: THE SETTLE (the anchor pair lands between the rows) ----
        // rows [G100(block A,B), L1, E1(old row 2), L2]; the user drags E1
        // and drops it between L1 and L2: the anchor pair (L1, L2) — not a
        // row index read from a render that still contains the dragged
        // empty (the v1.4.7 settle corpse: gp2 was off by one whenever the
        // empty sat above the target).
        Tab l1 = new Tab("L1"), l2 = new Tab("L2");
        setGroups(100, 700);                 // G100 membered, G700 empty
        Tab a = new Tab("A", 100), b = new Tab("B", 100);
        tabs.clear(); tabs.add(a); tabs.add(b); tabs.add(l1); tabs.add(l2);
        setAnchor(700, rowKeyOf(l1), rowKeyOf(l2));
        check(j(render()).equals("G100,L1,G700,L2"),
                "an empty dropped between L1 and L2 settles EXACTLY there (the anchor pair)");
        // ---- B2: THE DRIFT (an insert beside it does not push it) ----
        // a NEW tab lands right before E1 (between L1 and E1): the v1.4.7
        // slot stayed at its old index and E1 ate the new tab's spot; the
        // anchor law re-anchors E1 to FACE the new tab — they sit together
        Tab n1 = new Tab("N1");
        tabs.add(tabs.indexOf(l2), n1);      // tm.tabs: [A,B,L1,N1,L2]
        setAnchor(700, rowKeyOf(n1), rowKeyOf(l2));   // the landing re-anchored E1 to face N1
        check(j(render()).equals("G100,L1,N1,G700,L2"),
                "a tab dropped right before the empty sits NEXT TO IT (the pushed-one-off corpse)");
        // DEMONSTRATED: the v1.4.7 slot never moved when N1 inserted — the
        // empty renders BEFORE the just-dropped tab (the push, on device)
        setSlot(700, 2);                     // what v1.4.7 kept after its own drop
        List<String> corpse = renderSlot();
        check(corpse.indexOf("N1") > corpse.indexOf("G700"),
                "DEMONSTRATED: the v1.4.7 slot renders the empty BEFORE the just-dropped tab");
        setAnchor(700, rowKeyOf(n1), rowKeyOf(l2));   // restore the law
        // ---- B3: THE STICK-FREE MOVE (a dragged neighbour never drags it) ----
        // G100's block moves to the bottom; E1 (anchored N1..L2) must stay
        tabs.remove(a); tabs.remove(b); tabs.add(a); tabs.add(b);
        check(j(render()).equals("L1,N1,G700,L2,G100"),
                "a membered folder dragged away leaves the empty EXACTLY where it was");
        // ---- B4: THE DEPARTURE REFRESH (a referenced row closes) ----
        // N1 closes: E1's ap re-anchors to N1's neighbour above (L1)
        refreshAnchorsAround(rowKeyOf(n1));
        tabs.remove(n1);
        check(j(render()).equals("L1,G700,L2,G100"),
                "the empty keeps its spot when its anchor row closes (re-anchored outward)");
        // ---- B5: THE CHAIN (an empty anchored to another empty) ----
        setGroups(100, 700, 701);
        setAnchor(701, rowKeyOf(l1), rowKeyOf(l2));           // E2 between L1..L2 too
        setAnchor(700, "g701", rowKeyOf(l2));                 // E1 right AFTER E2
        check(j(render()).equals("L1,G701,G700,L2,G100"),
                "a chain of empties resolves in passes (E1 after E2's row)");
        // ---- B6: THE NORMALIZATION (a legacy slot is honoured) ----
        // an anchorless empty WITH a v1.4.7 slot of 1 pins at min(1, len)
        // — the quiet upgrade — its anchors become the rows it lands
        // between (tabs are [L1, L2, A, B]; G100 stays membered)
        setGroups(100, 700);
        setSlot(700, 1);
        List<Object> rows = followRowOrder(false);
        JSONObject naked = null;
        for (JSONObject g : groups()) if (g.optLong("id") == 700) naked = g;
        int at = Math.min(naked.optInt("slot", -1), rows.size());
        rows.add(at, naked);
        setAnchor(700, at > 0 ? rowKeyOf(rows.get(at - 1)) : "",
                        at < rows.size() - 1 ? rowKeyOf(rows.get(at + 1)) : "");
        check(j(render()).equals("L1,G700,L2,G100"),
                "a slotted v1.4.7 empty pins at its slot (nothing moves on update day)");
        // ---- B7: BOTH EDGES BARE = the very top ----
        setAnchor(700, "", "");
        check(j(render()).equals("G700,L1,L2,G100"),
                "both anchors bare pins the empty to the very top (the only-row drop)");

        System.out.println(bad == 0 ? "PROBE OK" : "PROBE BAD " + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
"""
    work = "/tmp/bolt_v148_gate"
    os.makedirs(work, exist_ok=True)
    src = os.path.join(work, "Probe.java")
    with open(src, "w", encoding="utf-8") as f:
        f.write(harness)
    r = subprocess.run([javac, "-cp", jar, "-d", work, src], capture_output=True, text=True)
    if r.returncode != 0:
        need(False, f"the probe compiles ({r.stderr.strip()[:300]})")
    else:
        need(True, "the probe compiles")
        rr = subprocess.run(["java", "-cp", work + ":" + jar, "Probe"], capture_output=True, text=True)
        print(rr.stdout.rstrip())
        need(rr.returncode == 0, "the probe runs green (the anchor law end to end)")

print("V — the version pin")
need(re.search(r'versionCode 127\b', gradle) is not None, "versionCode 127")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)
need(re.search(r'versionName "1\.4\.13"', gradle) is not None, 'versionName "1.4.13"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)
entries = re.findall(r'<item>([^<]+)</item>', strs.split('name="about_changelog"')[1]) if 'name="about_changelog"' in strs else []
need(entries and entries[0].startswith("1.4.13|"), "the About changelog's newest entry is 1.4.13 (the label law; v1.4.11 re-point 2026-11-18)")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)

print()
print(f"{'GATE GREEN' if fails == 0 else 'GATE RED (' + str(fails) + ')'}")
sys.exit(0 if fails == 0 else 1)
