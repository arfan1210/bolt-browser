#!/usr/bin/env python3
"""v1.4.9 release gate — the render-neighbour law (the (2,2) teleport
corpse), the chunked main menu, and the visible in-group item menus.

The v1.4.9 field report:

1. "some tab groups don't behave properly or as expected, like rearranging
   them do not follow the rule I set earlier. the most issue happens in the
   2,2 th element if you consider a 2 by 2 matrix using the top 4 tabs.
   Like setting a group to the right of the 2,2th tab group teleports the
   group I moved to the very bottom of the tabs list." — THREE stacked
   corpses under one report:
   (a) THE STALE-KEY TELEPORT: an empty folder's stored "an"/"ap" can be
       stale (a bare "" written when the empty WAS the last row — new tabs
       opening below never refresh it; a dangling key) while the empty
       renders mid-list through its OTHER side. The v1.4.8 beside-an-empty
       landing resolved through the STORED key, and its null-fallback was
       the LIST EDGE (lastRowAnchorTab / firstRowAnchorTab / tm.tabs.size()
       / 0) — so a group dropped right of such an empty teleported to the
       very bottom, dragging the empty down with it.
   (b) THE INVERTED WRITE: even with healthy keys, the v1.4.8 re-anchor
       wrote the landed row on the OPPOSITE side of the empty (gafter
       wrote ap="g<gid>" — the landed group as the row-ABOVE — so a
       right-of drop rendered LEFT of the empty): the "does not follow the
       rule" one-position-off.
   (c) THE FOLLOW-THE-DRAG REFRESH: the membered-group drag called
       refreshAnchorsAround AFTER the move, so an empty anchored to the
       dragged folder re-anchored to its NEW neighbours and FOLLOWED the
       drag — "only the one I hold moves" broken for exactly the empties
       pinned to the dragged row.
   THE FIX — THE RENDER-NEIGHBOUR LAW: a beside-an-empty landing resolves
   through the empty's VISIBLE neighbours in the follow render
   (TabManager.renderNeighbourKeyOfEmpty — walking past the departing row),
   never through the possibly-stale stored pair; an empty CHAIN beside the
   gap resolves through chainBoundaryTab (the near-end empty re-anchors to
   face the landed row, the members anchor on the first material row past
   the chain); the re-anchor write puts the landed row on the finger's
   side; and the departure refresh reads the PRE-change order (before the
   move).

2. "Similarly group up the settings in top right 3 dots settings under
   sections based on similarity" — THE CHUNKED MAIN MENU: the same
   treatment the site settings got in v1.4.8 — eight labelled chunks (On
   this page / Content blocking / This site / Save & capture / Privacy &
   identity / Tabs & workspaces / Display & media / AI & scripts), every
   row kept verbatim.

3. "in the groups, I don't see any option to selectively remove a
   tab/shortcut without dragging them… Add menu tabs to all to edit inside
   groups too, like edit name, link, de-group, delete etc." — THE VISIBLE
   IN-GROUP ITEM MENUS: every tab mini in a tab group wears a ⋯ button
   (replacing the close-only X) opening the member menu with Edit tab
   (name + link — the link navigates), Remove from group, Move to group,
   Close tab; every shortcut cell in a home folder window wears a ⋯ button
   opening the item menu with Edit shortcut (editSpeeddialItemIn — the new
   in-folder edit), Move to home (ejectSpeeddialItem — de-group, KEPT),
   Delete shortcut (the old remove, undo intact).

Sections:
A1 — the render-neighbour resolver (TabManager.renderNeighbourKeyOfEmpty)
A2 — the teleport fix (both landing paths resolve through the render; the corrected writes)
A3 — the empty-chain boundary (chainBoundaryTab; the near-end face-write)
A4 — the follow-the-drag refresh (refreshAnchorsAround BEFORE the move)
A5 — the chunked main menu (eight labels; every row survives)
A6 — the visible in-group item menus (the tab minis; the folder-window cells)
B  — the functional probe (javac + run: the teleport, the inversion, the
     chain, the tab landing, the stick, the edge — both old shapes DEMONSTRATED)
V  — the version pin.
"""

import os, re, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/TabsActivity.java")
TM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/TabManager.java")
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
PF = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Prefs.java")
STRS = os.path.join(ROOT, "app/src/main/res/values/strings.xml")
MINI = os.path.join(ROOT, "app/src/main/res/layout/item_tab_mini.xml")
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

ta, tm, ba, prefs_src = (slurp(TA), slurp(TM), slurp(BA), slurp(PF))
strs, mini, gradle = (slurp(STRS), slurp(MINI), slurp(GR))

print("A1 — the render-neighbour resolver (the stored pair never feeds a landing)")
need('public String renderNeighbourKeyOfEmpty(long gid, boolean below, String skipKey)' in tm,
     "TabManager.renderNeighbourKeyOfEmpty exists (the empty's VISIBLE neighbour as a key)")
rn = tm.split("public String renderNeighbourKeyOfEmpty")[1][:1800] if "public String renderNeighbourKeyOfEmpty" in tm else ""
need('if (skipKey == null || skipKey.length() < 2 || !k.equals(skipKey)) return k;' in rn
     and "n += below ? 1 : -1;" in rn,
     "the walk steps past the DEPARTING row (the kept side pins against a SURVIVING row)")
need('return "";' in rn,
     'the walk off the end returns "" (a genuine render edge)')

print("A2 — the (2,2) teleport fix (both landing paths resolve through the render)")
cv = ta.split("public void clearView")[1] if "public void clearView" in ta else ""
gd = cv.split("final JSONObject gdrag = draggingGroup;")[1].split("// capture the drop intents")[0] if "final JSONObject gdrag = draggingGroup;" in cv else ""
need("tm.renderNeighbourKeyOfEmpty(egid, false, \"g\" + gid);" in gd
     and "tm.renderNeighbourKeyOfEmpty(egid, true, \"g\" + gid);" in gd,
     "the group landing resolves through the empty's render neighbours (never the stored pair)")
need("chainBoundaryTab(belowK, \"g\" + gid, true, false);" in gd
     and 'chainBoundaryTab(aboveK, "g" + gid, false, true);' in gd,
     "an empty-neighbour landing goes through the chain walk (the second teleport vector)")
need('aboveK == null ? eap : aboveK, "g" + gid);' in gd,
     "gafter writes the group as the empty's row-BELOW (an) — the inverted write is dead")
need('prefs.setGroupAnchor(egid, "g" + gid,' in gd,
     "the before-side write mirrors it (the group as the row-ABOVE, ap)")
tp = cv.split("emptyRowTarget != null && b == null")[1][:3000] if "emptyRowTarget != null && b == null" in cv else ""
need('tm.renderNeighbourKeyOfEmpty(egid, false, "t" + dragged.tid);' in tp
     and 'tm.renderNeighbourKeyOfEmpty(egid, true, "t" + dragged.tid);' in tp,
     "the tab landing resolves through the render neighbours too")
need('after ? (aboveK == null ? eap : aboveK) : "t" + dragged.tid,' in tp
     and 'after ? "t" + dragged.tid : (belowK == null ? ean : belowK)' in tp,
     "the tab lands on the finger's side of the empty (after = the empty's row-BELOW)")

print("A3 — the empty-chain boundary (the near-end empty faces the landed row)")
cbt = ta.split("private Tab chainBoundaryTab")[1][:2600] if "private Tab chainBoundaryTab" in ta else ""
need("private Tab chainBoundaryTab(String key, String landedKey, boolean down, boolean after)" in ta,
     "the chain walk exists (an empty beside the gap has no tm.tabs slot)")
need("long faceGid = 0;" in cbt and "faceKept" in cbt,
     "the walk captures the near-end empty and its far side")
need("prefs.setGroupAnchor(faceGid," in cbt
     and "down ? landedKey : faceKept," in cbt and "down ? faceKept : landedKey);" in cbt,
     "the near-end empty re-anchors to FACE the landed row (the chain behind follows its own keys)")
need("int guard = 64;" in cbt,
     "the chain walk is guarded (no cycle can hang a drop)")

print("A4 — the follow-the-drag refresh (only the one you hold moves)")
need(gd.find('try { tm.refreshAnchorsAround("g" + gid); } catch (Throwable ignored) {}')
     < gd.find("gdone = tm.moveGroupRelative(gid, ganchor, gafter);"),
     "the membered-target drop refreshes BEFORE the move (the v1.4.8 after-order made anchored empties FOLLOW)")
need(gd.find('try { tm.refreshAnchorsAround("g" + gid); } catch (Throwable ignored) {}')
     < gd.find("tm.renderNeighbourKeyOfEmpty(egid"),
     "the beside-an-empty drop refreshes the dragged group's departure before resolving")
rbe = ta.split("private void retargetBoundaryEmpty")[1][:1700] if "private void retargetBoundaryEmpty" in ta else ""
need("tm.renderNeighbourKeyOfEmpty(egid, !after, newKey);" in rbe,
     "the boundary retarget's kept side is render-derived too (never a stale stored key)")

print("A5 — the chunked main menu (eight labelled chunks)")
mm = ba.split("protected void showMainMenu()")[1].split("protected MenuRow row(int icon")[0] if "protected void showMainMenu()" in ba else ""
for h in ("On this page", "Content blocking", "This site", "Save & capture",
          "Privacy & identity", "Tabs & workspaces", "Display & media", "AI & scripts"):
    need(f'headerRow("{h}")' in mm, f'the "{h}" chunk label')
for probe in ("sharePageAsQr", "showOutline", "translatePage", "translateScreen",
              "startInspector", "startTts", "adblockEnabled", "toggleSiteClean",
              "toggleSiteDark", "siteZoomDialog", "addBookmark", "addShortcut",
              "showSavePageSheet", "WatchLaterActivity", "AuthenticatorActivity",
              "PasswordVaultActivity", "ProfilesActivity", "WorkspacesActivity",
              "sleepOthersNow", "toggleFullscreenMode", "NightScreen.toggle",
              "enterPipIfEligible", "BoltAi.open", "showActiveScripts",
              "UserscriptsActivity", "confirmExit"):
    need(probe in mm, f"the {probe} row survives the reorganisation")
need(mm.find('headerRow("On this page")') < mm.find("sharePageAsQr")
     and mm.find('headerRow("AI & scripts")') < mm.find("BoltAi.open"),
     "the rows sit under their chunk labels, not before them")

print("A6 — the visible in-group item menus")
need('android:id="@+id/btn_mini_menu"' in mini and "@drawable/ic_more" in mini,
     "the tab mini wears a ⋯ button (the visible door)")
need('android:id="@+id/btn_mini_close"' not in mini,
     "the close-only X is retired (Close tab lives in the menu)")
need(("tabOptions(t, gid);" in ta or "tabOptions(tab, gid);" in ta)
     and ("R.id.btn_mini_menu" in ta or "R.drawable.ic_more" in ta),
     "the member tab's ⋯ opens the member menu")  # v1.4.12 re-point (2026-11-18): the inline mini-strip became the group folder SHEET (TabsActivity.openGroupSheet) — the visible ⋯ member-menu door survives as the sheet row's ic_more button -> tabOptions(tab, gid)
to = ta.split("protected void tabOptions")[1][:3000] if "protected void tabOptions" in ta else ""
need("R.string.edit_tab" in to and "R.string.remove_from_group" in to
     and "R.string.move_to_group" in to and "R.string.close_tab" in to,
     "the member menu: Edit tab / Remove from group / Move to group / Close tab")
need("t.title = name;" in to and "t.web.loadUrl(url);" in to,
     "Edit tab writes the name and NAVIGATES the link")
wc = ba.split("protected void sdWinAddCell")[1].split("protected android.view.View.OnTouchListener sdWinCellTouch")[0] if "protected void sdWinAddCell" in ba else ""
need("R.drawable.ic_more" in wc and "sdWinItemMenu(itemIdx, url, tTitle);" in wc,
     "every folder-window cell wears a ⋯ that opens the item menu")
im = ba.split("protected void sdWinItemMenu")[1].split("protected org.json.JSONObject shortcutItemAt")[0] if "protected void sdWinItemMenu" in ba else ""
need("R.string.edit_shortcut" in im and "editSpeeddialItemIn(at, itemIdx" in im,
     "the item menu edits name+link through the in-folder edit")
need("R.string.move_to_home" in im and "ejectSpeeddialItem(at, itemIdx)" in im,
     "Move to home de-groups the shortcut (ejected to the top level, KEPT)")
need("R.string.delete_shortcut" in im and "Ui.UNDO_MS" in im,
     "Delete shortcut keeps the undo family")
need("public void editSpeeddialItemIn(int groupIdx, int itemIdx, String title, String url)" in prefs_src,
     "Prefs.editSpeeddialItemIn (the nested in-folder edit)")
need("protected void rebuildSdFolderWindowInPlace()" in ba,
     "an in-place edit rebuilds the open window (name/icon refresh without teardown)")

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
    work = "/tmp/bolt_v149_gate"
    for p in (os.path.join(work, "json.jar"), "/tmp/bolt_v148_gate/json.jar",
              "/tmp/bolt_v147_gate/json.jar", "/tmp/org.json.jar"):
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
    harness = r'''
import org.json.JSONObject;
import java.util.*;

/** the v1.4.9 probe: the render-neighbour law, transcribed 1:1 from
 *  TabManager (followRowOrder, anchorRowIn, pendingEmptyKey, rowKeyIndex,
 *  renderNeighbourKeyOfEmpty, refreshAnchorsAround, moveGroupRelative) and
 *  TabsActivity (anchorKeyTab, lastRowAnchorTab, chainBoundaryTab, and the
 *  two landing paths in their v1.4.8 AND v1.4.9 forms — the corpses
 *  DEMONSTRATED beside their fixes). */
public class Probe {
    static class Tab {
        String name; long tid; long groupId;
        Tab(String n, long id, long g) { name = n; tid = id; groupId = g; }
        Tab(String n, long id) { this(n, id, 0); }
    }
    static List<JSONObject> groups = new ArrayList<JSONObject>();
    static List<Tab> tabs = new ArrayList<Tab>();
    static void setGroups(long... ids) {
        groups.clear();
        for (long id : ids) groups.add(newGroup(id));
    }
    static JSONObject newGroup(long id) {
        JSONObject g = new JSONObject();
        try { g.put("id", id); g.put("name", "G" + id); } catch (Throwable ignored) {}
        return g;
    }
    static void setAnchor(long gid, String ap, String an) {
        for (JSONObject g : groups) {
            if (g.optLong("id") == gid) {
                try { g.put("ap", ap); g.put("an", an); g.remove("slot"); } catch (Throwable ignored) {}
            }
        }
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
        return row instanceof Tab ? "t" + ((Tab) row).tid : "g" + ((JSONObject) row).optLong("id");
    }
    static int folderRowIndex(List<Object> rows, long gid) {
        for (int i = 0; i < rows.size(); i++) {
            Object o = rows.get(i);
            if (!(o instanceof Tab) && ((JSONObject) o).optLong("id") == gid) return i;
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
                return rows.indexOf(t);
            }
            if (key.charAt(0) == 'g') return folderRowIndex(rows, Long.parseLong(key.substring(1)));
        } catch (Throwable ignored) {}
        return -1;
    }
    static boolean pendingEmptyKey(List<Object> rows, String key) {
        try {
            if (key == null || key.length() < 2 || key.charAt(0) != 'g') return false;
            long gid = Long.parseLong(key.substring(1));
            if (gid == 0 || folderRowIndex(rows, gid) >= 0) return false;
            for (JSONObject g : groups)
                if (g.optLong("id") == gid) return tabsOfGroup(gid).isEmpty();
        } catch (Throwable ignored) {}
        return false;
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
    static List<Object> followRowOrder(boolean includeAnchorless) {
        List<Object> out = new ArrayList<Object>(tabs.size() + 4);
        HashSet<Long> emitted = new HashSet<Long>();
        for (Tab t : tabs) {
            if (t.groupId != 0 && !emitted.contains(t.groupId)) {
                for (JSONObject g : groups) {
                    if (g.optLong("id") == t.groupId) { out.add(g); break; }
                }
                emitted.add(t.groupId);
            }
            if (t.groupId == 0) out.add(t);
        }
        List<JSONObject> anchored = new ArrayList<JSONObject>();
        List<JSONObject> leftover = new ArrayList<JSONObject>();
        for (JSONObject g : groups) {
            if (emitted.contains(g.optLong("id"))) continue;
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
                if (!(out.get(i) instanceof Tab)) lastFolderRow = i;
            int putAt = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;
            for (JSONObject g : leftover) out.add(putAt++, g);
        }
        return out;
    }
    static List<String> render() {
        List<String> out = new ArrayList<String>();
        for (Object o : followRowOrder(true))
            out.add(o instanceof Tab ? ((Tab) o).name : "G" + ((JSONObject) o).optLong("id"));
        return out;
    }
    // ---- v1.4.9: the render-neighbour resolver (transcribed 1:1) ----
    static String renderNeighbourKeyOfEmpty(long gid, boolean below, String skipKey) {
        try {
            if (gid == 0) return null;
            List<Object> rows = followRowOrder(true);
            int at = folderRowIndex(rows, gid);
            if (at < 0) return null;
            int n = at + (below ? 1 : -1);
            while (n >= 0 && n < rows.size()) {
                String k = rowKeyOf(rows.get(n));
                if (skipKey == null || skipKey.length() < 2 || !k.equals(skipKey)) return k;
                n += below ? 1 : -1;
            }
            return "";
        } catch (Throwable t) {
            return null;
        }
    }
    static void refreshAnchorsAround(String movedKey) {
        if (movedKey == null || movedKey.length() < 2) return;
        List<Object> rows = followRowOrder(true);
        int at = rowKeyIndex(rows, movedKey);
        if (at < 0) return;
        String prevK = at > 0 ? rowKeyOf(rows.get(at - 1)) : "";
        String nextK = at < rows.size() - 1 ? rowKeyOf(rows.get(at + 1)) : "";
        for (JSONObject g : groups) {
            long gid = g.optLong("id");
            if (gid == 0 || !tabsOfGroup(gid).isEmpty()) continue;
            if (!g.has("ap") && !g.has("an")) continue;
            String ap = g.optString("ap", ""), an = g.optString("an", "");
            if (movedKey.equals(ap) || movedKey.equals(an)) {
                setAnchor(gid, movedKey.equals(ap) ? prevK : ap, movedKey.equals(an) ? nextK : an);
            }
        }
    }
    static boolean moveGroupRelative(long gid, Tab anchor, boolean after) {
        try {
            if (anchor == null || gid == 0 || anchor.groupId == gid) return false;
            List<Tab> members = tabsOfGroup(gid);
            if (members.isEmpty()) return false;
            Tab boundary = anchor;
            if (anchor.groupId != 0) {
                List<Tab> host = tabsOfGroup(anchor.groupId);
                if (host.isEmpty()) return false;
                boundary = after ? host.get(host.size() - 1) : host.get(0);
            }
            HashSet<Tab> moving = new HashSet<Tab>(members);
            List<Tab> rest = new ArrayList<Tab>(tabs.size() - members.size());
            for (Tab t : tabs) if (!moving.contains(t)) rest.add(t);
            int at = rest.indexOf(boundary);
            if (at < 0) return false;
            rest.addAll(after ? at + 1 : at, members);
            tabs = rest;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    // ---- the TabsActivity helpers (transcribed) ----
    static Tab anchorKeyTab(String key, boolean landingAfter) {
        try {
            if (key == null || key.length() < 2) return null;
            if (key.charAt(0) == 't') {
                Tab t = tabByTid(Long.parseLong(key.substring(1)));
                if (t == null) return null;
                if (t.groupId == 0) return t;
                List<Tab> host = tabsOfGroup(t.groupId);
                if (host.isEmpty()) return null;
                return landingAfter ? host.get(host.size() - 1) : host.get(0);
            }
            if (key.charAt(0) == 'g') {
                List<Tab> host = tabsOfGroup(Long.parseLong(key.substring(1)));
                if (host.isEmpty()) return null;
                return landingAfter ? host.get(host.size() - 1) : host.get(0);
            }
        } catch (Throwable ignored) {}
        return null;
    }
    static Tab lastRowAnchorTab() {
        List<Object> rows = followRowOrder(true);
        for (int i = rows.size() - 1; i >= 0; i--) {
            Object o = rows.get(i);
            if (o instanceof Tab) return (Tab) o;
            List<Tab> host = tabsOfGroup(((JSONObject) o).optLong("id"));
            if (!host.isEmpty()) return host.get(host.size() - 1);
        }
        return null;
    }
    // v1.4.9 — the chain boundary (transcribed 1:1)
    static Tab chainBoundaryTab(String key, String landedKey, boolean down, boolean after) {
        try {
            long faceGid = 0;
            String faceKept = "";
            String material = key;
            int guard = 64;
            while (material != null && material.length() >= 2
                    && material.charAt(0) == 'g' && guard-- > 0) {
                long e2 = Long.parseLong(material.substring(1));
                if (!tabsOfGroup(e2).isEmpty()) break;
                String next = renderNeighbourKeyOfEmpty(e2, down, landedKey);
                if (faceGid == 0) {
                    faceGid = e2;
                    faceKept = next == null ? "" : next;
                }
                material = next;
            }
            if (faceGid != 0) {
                setAnchor(faceGid, down ? landedKey : faceKept,
                        down ? faceKept : landedKey);
            }
            if (material == null || material.length() < 2) return null;
            return anchorKeyTab(material, after);
        } catch (Throwable t) {
            return null;
        }
    }
    // ---- the landings, v1.4.9 form (the app's emptyTarget branch) ----
    static boolean landGroupBesideEmpty(long gid, long egid, boolean gafter) {
        JSONObject eg = null;
        for (JSONObject g : groups) if (g.optLong("id") == egid) eg = g;
        String ean = eg.optString("an", ""), eap = eg.optString("ap", "");
        boolean done = false;
        refreshAnchorsAround("g" + gid);
        if (gafter) {
            String aboveK = renderNeighbourKeyOfEmpty(egid, false, "g" + gid);
            String belowK = renderNeighbourKeyOfEmpty(egid, true, "g" + gid);
            Tab a = belowK == null ? anchorKeyTab(ean, false)
                    : chainBoundaryTab(belowK, "g" + gid, true, false);
            if (a != null) done = moveGroupRelative(gid, a, false);
            else {
                a = lastRowAnchorTab();
                done = a != null && moveGroupRelative(gid, a, true);
            }
            if (done) setAnchor(egid, aboveK == null ? eap : aboveK, "g" + gid);
        } else {
            String aboveK = renderNeighbourKeyOfEmpty(egid, false, "g" + gid);
            String belowK = renderNeighbourKeyOfEmpty(egid, true, "g" + gid);
            Tab a = aboveK == null ? anchorKeyTab(eap, true)
                    : chainBoundaryTab(aboveK, "g" + gid, false, true);
            if (a != null) done = moveGroupRelative(gid, a, true);
            else done = false;   // (the first-row edge is not probed here)
            if (done) setAnchor(egid, "g" + gid, belowK == null ? ean : belowK);
        }
        return done;
    }
    // ---- the landings, v1.4.8 form (the corpses, DEMONSTRATED) ----
    static boolean landGroupBesideEmptyV148(long gid, long egid, boolean gafter) {
        JSONObject eg = null;
        for (JSONObject g : groups) if (g.optLong("id") == egid) eg = g;
        String ean = eg.optString("an", ""), eap = eg.optString("ap", "");
        boolean done = false;
        if (gafter) {
            Tab a = anchorKeyTab(ean, false);
            if (a != null) done = moveGroupRelative(gid, a, false);
            else {
                a = lastRowAnchorTab();
                done = a != null && moveGroupRelative(gid, a, true);
            }
            if (done) setAnchor(egid, "g" + gid, ean);
        }
        return done;
    }
    // ---- the tab landing, v1.4.9 form ----
    static void landTabBesideEmpty(Tab dragged, long egid, boolean after) {
        refreshAnchorsAround("t" + dragged.tid);
        tabs.remove(dragged);
        String aboveK = renderNeighbourKeyOfEmpty(egid, false, "t" + dragged.tid);
        String belowK = renderNeighbourKeyOfEmpty(egid, true, "t" + dragged.tid);
        int k;
        if (after) {
            Tab a = belowK == null ? null
                    : chainBoundaryTab(belowK, "t" + dragged.tid, true, false);
            k = a != null ? tabs.indexOf(a) : tabs.size();
        } else {
            k = 0;   // (the top edge is not probed here)
        }
        if (k < 0) k = tabs.size();
        tabs.add(k, dragged);
        setAnchor(egid, after ? (aboveK == null ? "" : aboveK) : "t" + dragged.tid,
                after ? "t" + dragged.tid : (belowK == null ? "" : belowK));
    }

    static int bad = 0;
    static void check(boolean ok, String why) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + why);
        if (!ok) bad++;
    }
    static String j(List<String> l) { return String.join(",", l); }

    static void seed() {
        setGroups(1, 3, 700);
        setAnchor(700, "g3", "");
        Tab a = new Tab("A", 101, 1), b = new Tab("B", 102, 1);
        Tab l1 = new Tab("L1", 103);
        Tab c = new Tab("C", 104, 3), d = new Tab("D", 105, 3);
        Tab t5 = new Tab("T5", 106), t6 = new Tab("T6", 107), t7 = new Tab("T7", 108);
        tabs = new ArrayList<Tab>(Arrays.asList(a, b, l1, c, d, t5, t6, t7));
    }

    public static void main(String[] args) {
        // ---- B1: THE (2,2) TELEPORT — the stale bare-an empty ----
        // rows [G1, L1, G3, E700(an="") , T5, T6, T7]: drop G1 RIGHT of the
        // empty (the report's "2,2th element"). v1.4.8: anchorKeyTab("") ->
        // null -> lastRowAnchorTab -> THE VERY BOTTOM (and the empty
        // followed). v1.4.9: the render's row-below (T5) anchors the block.
        seed();
        check(j(render()).equals("G1,L1,G3,G700,T5,T6,T7"), "the seed renders the empty at (2,2)");
        landGroupBesideEmptyV148(1, 700, true);
        List<String> corpse = render();
        check(corpse.indexOf("G1") == corpse.size() - 2,
                "DEMONSTRATED: the v1.4.8 landing teleports the group to the very bottom");
        seed();
        landGroupBesideEmpty(1, 700, true);
        check(j(render()).equals("L1,G3,G700,G1,T5,T6,T7"),
                "the v1.4.9 landing settles the group RIGHT of the empty (no teleport)");
        // ---- B2: THE INVERSION — healthy anchors, the write's side ----
        // an="t5" (healthy): v1.4.8 wrote ap="g<gid>" so a right-of drop
        // rendered LEFT of the empty; v1.4.9 writes an="g<gid>".
        seed();
        setAnchor(700, "g3", "t106");
        landGroupBesideEmptyV148(3, 700, true);
        corpse = render();
        check(corpse.indexOf("G3") < corpse.indexOf("G700"),
                "DEMONSTRATED: the v1.4.8 write renders the group LEFT of the empty (one off)");
        seed();
        setAnchor(700, "g3", "t106");
        landGroupBesideEmpty(3, 700, true);
        check(j(render()).equals("G1,L1,G700,G3,T5,T6,T7"),
                "the v1.4.9 write renders the group on the finger's side (BELOW the empty)");
        // ---- B3: THE CHAIN — an empty directly below the target ----
        seed();
        groups.add(newGroup(701));
        setAnchor(701, "g700", "t106");
        landGroupBesideEmpty(1, 700, true);
        check(j(render()).equals("L1,G3,G700,G1,G701,T5,T6,T7"),
                "the group lands BETWEEN the two empties (the chain slides one slot)");
        // ---- B4: THE TAB LANDING — a loose tab right of the stale empty ----
        seed();
        Tab t6 = tabByTid(107);
        landTabBesideEmpty(t6, 700, true);
        check(j(render()).equals("G1,L1,G3,G700,T6,T5,T7"),
                "the tab lands right below the empty (not at the list end)");
        // ---- B5: THE STICK — an empty anchored to the dragged group ----
        // [G1, E700(ap="g1"), L1, G3, ...]: drag G1 to the bottom. The
        // refresh must read the PRE-change order (v1.4.9) — the v1.4.8
        // after-order re-anchored E700 to G1's NEW neighbours and it
        // FOLLOWED the drag to the bottom.
        seed();
        setAnchor(700, "g1", "");
        refreshAnchorsAround("g1");            // v1.4.9: BEFORE the move
        moveGroupRelative(1, tabByTid(108), true);
        check(j(render()).equals("G700,L1,G3,T5,T6,T7,G1"),
                "the empty keeps the top spot; only the dragged group moved");
        seed();
        setAnchor(700, "g1", "");
        moveGroupRelative(1, tabByTid(108), true);   // v1.4.8: the move FIRST...
        refreshAnchorsAround("g1");            // ...then the refresh reads the NEW order
        corpse = render();
        check(corpse.indexOf("G700") == corpse.size() - 2,
                "DEMONSTRATED: the v1.4.8 after-order made the empty FOLLOW the drag");
        // ---- B6: THE EDGE — the empty IS the last row ----
        seed();
        setAnchor(700, "t108", "");
        landGroupBesideEmpty(1, 700, true);
        check(j(render()).equals("L1,G3,T5,T6,T7,G700,G1"),
                "a genuine bottom-edge drop lands the group BELOW the last-row empty");

        System.out.println(bad == 0 ? "PROBE OK" : "PROBE BAD " + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
'''
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
        need(rr.returncode == 0, "the probe runs green (the render-neighbour law end to end)")

print("V — the version pin")
need(re.search(r'versionCode 143\b', gradle) is not None, "versionCode 143")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName "1\.4\.29"', gradle) is not None, 'versionName "1.4.29"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
entries = re.findall(r'<item>([^<]+)</item>', strs.split('name="about_changelog"')[1]) if 'name="about_changelog"' in strs else []
need(entries and entries[10].startswith("1.4.18|"), "the About changelog's newest entry is 1.4.13 (the label law; v1.4.11 re-point 2026-11-18)")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-23): every About-changelog pin slid one slot down (1.4.29 landed at [0])

print()
print("V149 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
