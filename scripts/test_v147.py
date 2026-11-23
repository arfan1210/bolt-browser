#!/usr/bin/env python3
"""v1.4.7 release gate — the sticky empty tab groups, the rotation-stop of
background playback, and add-shortcut-directly-to-folder.

The v1.4.6 field report:

1. "Some tab groups 'stick' to another, like moving one group moves
   another empty one, I don't know if it happens only for empty one
   though. Fix so that only the one I hold moves" — THE DERIVED-POSITION
   STICK: the v1.4.6 anchor chained every memberless folder right after
   the LAST folder row the follow walk emitted, so dragging that folder
   (or any drag that changed which folder row was last) dragged the
   empties with it; on-top, groupsInFirstMemberOrder's MAX_VALUE sort
   stomped empties to the array's end on every membered move. THE FIX is
   a TWO-MEMORY position law for empty folders:
   - the GROUPS ARRAY carries each empty's inter-folder slot (the
     membered-slot MERGE: p(E) = # membered folders before it in the old
     array; the merge re-emits membered by first member but keeps every
     empty between the SAME membered neighbours — the on-top memory);
   - the group's "slot" field carries its rendered ROW index in the
     follow placement (written by a drop of THAT folder, by the
     placement-switch materialize, or by the last-member swipe capture;
     slotless empties keep the v1.4.6 convention);
   - dragging an EMPTY folder is no longer a silent no-op: follow writes
     the slot at the drop row; on-top block-moves the array entry beside
     the target folder (moveEmptyGroupBeside).

2. "Background video playback stops when I rotate phone to landscape,
   only plays in portrait mode for now, fix it so it does in both" —
   THE ROTATION TRIPLE: (a) MediaHook's pipMuted now also mutes
   resize/orientationchange while the page is REALLY HIDDEN with the
   spoof engaged (an invisible-to-invisible rotation must never reach the
   player's reaction handlers — the same reaction family the PiP mutes
   were built for); (b) BrowserActivity remembers the orientation at
   onPause and, returning to a CHANGED orientation, dispatches ONE
   synthetic resize marked __boltRS (the v1.3.69 resync-exemption idiom)
   to every media-holding tab so the player re-reads the live window;
   (c) MediaControlService's DisplayListener re-arms the bounded
   resume-guard on a FRESH base (rotAt) when the display rotates while
   backgrounded media is expected — the engine-side pause a rotation can
   provoke lands with no page event the mute could stop, and the original
   guard window (~1.5 s from backgrounding) is long expired. A FOCUS-LOSS
   VETO (the no-op focus listener now latches timestamps) keeps calls and
   other apps' audio un-resurrected.

3. "Also implement the adding shortcuts directly to folders while adding
   in that website" — THE FOLDER PICKER: the add-shortcut dialog gains a
   pill row (Home / every home folder / New folder… — inline creation),
   and Add-to-browser-home lands the shortcut directly INSIDE the chosen
   folder (Prefs.addSpeeddialItemTo) instead of always on the loose grid.

PART A — static checks. PART B — the functional probe (javac + run
against the real org.json): the merge law (the old MAX_VALUE sort
DEMONSTRATES the stick, the merge proves the empty keeps its
neighbours), the follow-walk slot law (a membered drag leaves the
slotted empty's row untouched), the drop-slot + slotless conventions,
and addSpeeddialItemTo.
V — the version pin.
"""
import os, re, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/TabsActivity.java")
TM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/TabManager.java")
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
MC = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/media/MediaControlService.java")
MH = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/media/MediaHook.java")
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

ta, tm, ba, mc = (slurp(TA), slurp(TM), slurp(BA), slurp(MC))
mhs, prefs_src, strs, gradle = (slurp(MH), slurp(PF), slurp(STRS), slurp(GR))

print("A1 — the membered-slot merge (groupsInFirstMemberOrder)")
merge = tm.split("public List<JSONObject> groupsInFirstMemberOrder()")[1][:3200] if "public List<JSONObject> groupsInFirstMemberOrder()" in tm else ""
need("List<JSONObject> membered = new ArrayList<>();" in merge
     and "List<JSONObject> empties = new ArrayList<>();" in merge,
     "the merge partitions membered vs empty (empties are no longer sorted to the end)")
need("int[] p = new int[empties.size()];" in merge,
     "p(E) = # membered folders before E in the OLD array (the inter-folder slot memory)")
need("for (int j = 0; j <= membered.size(); j++)" in merge,
     "the emit loop: p=0 empties, M'_1, p=1 empties, ... M'_k, p=k empties")
need("Integer.MAX_VALUE" in merge and "Collections.sort(membered" in merge,
     "membered folders still sort by first-member (the v1.4.3 law)")
need("if (empties.isEmpty()) return membered;" in merge,
     "the no-empties fast path returns the plain membered sort")
need(tm.find("moveEmptyGroupBeside") < tm.find("public boolean moveGroupRelative"),
     "moveEmptyGroupBeside lives in TabManager (beside its membered sibling)")

meb = tm.split("public boolean moveEmptyGroupBeside")[1][:2200] if "public boolean moveEmptyGroupBeside" in tm else ""
need("if (!tabsOfGroup(gid).isEmpty()) return false;" in meb,
     "moveEmptyGroupBeside refuses membered folders (they keep block-moving)")
need("list.add(after ? at + 1 : at, drag);" in meb and "prefs.reorderGroups(list);" in meb,
     "the array move is a TRUE java-List insert + reorderGroups (never JSONArray.put(int,·))")

print("A2 — the follow walk (empty folders place themselves; v1.4.8 re-pin: the slot became the ANCHOR PAIR and the walk moved into TabManager.followRowOrder — see test_v148)")
fw = tm.split("public List<Object> followRowOrder(boolean includeAnchorless)")[1][:3200] if "public List<Object> followRowOrder(boolean includeAnchorless)" in tm else ""
need('if (g.has("ap") || g.has("an")) anchored.add(g);' in fw,
     "the walk partitions anchored vs anchorless never-emitted folders")
need("while (!pending.isEmpty() && progress && guard-- > 0)" in fw,
     "the pass loop resolves chains (an empty anchored to another empty's row)")
need("out.add(Math.min(at, out.size()), g);" in fw,
     "anchored empties insert at their resolved row, clamped")
need("int lastFolderRow = -1;" in fw and "int putAt = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;" in fw
     and "out.add(putAt++, g);" in fw,
     "anchorless empties keep the v1.4.6 convention (after the last folder row, the top when none)")
need("for (Object o : tm.followRowOrder()) items.add(o);" in ta,
     "TabsActivity renders the one row model (the duplicated walk is gone)")

print("A3 — the empty-folder drop (the held folder finally moves)")
cv = ta.split("public void clearView")[1] if "public void clearView" in ta else ""
gd = cv.split("final JSONObject gdrag = draggingGroup;")[1].split("// capture the drop intents")[0] if "final JSONObject gdrag = draggingGroup;" in cv else ""
need("boolean gEmpty = tm.tabsOfGroup(gid).isEmpty();" in gd,
     "the drop path detects the dragged EMPTY folder")
need("dropAnchorEmptyGroup(gid, gp2, gafter," in gd,
     "follow-order drops write the ANCHOR PAIR (any row anchors: folder or loose tab; v1.4.8 — the slot index was the settle corpse)")
need("tm.moveEmptyGroupBeside(gid, gtarget.optLong(\"id\"), gafter);" in gd,
     "on-top drops block-move the ARRAY entry beside the target folder")
need("public void setGroupSlot(long id, int slot)" in prefs_src
     and "g.remove(\"slot\")" in prefs_src,
     "Prefs.setGroupSlot persists (and can clear) a folder's slot — retired by the anchor law but kept for clean legacy writes")
need("public void setGroupAnchor(long id, String prevKey, String nextKey)" in prefs_src,
     "Prefs.setGroupAnchor persists the v1.4.8 anchor pair (ap/an)")

print("A4 — the re-pin + the last-member capture (v1.4.8 re-pin: the anchor pair, not the slot index)")
mat = tm.split("public boolean materializeOnTopOrder()")[1][:3600] if "public boolean materializeOnTopOrder()" in tm else ""
need("prefs.setGroupAnchor(g.optLong(\"id\")," in mat
     and "tabsOfGroup(g.optLong(\"id\")).isEmpty()" in mat,
     "the placement-switch materialize re-pins every empty's ANCHORS to its on-top folder-block neighbours")
sw = ta.split("public void onSwiped")[1][:1600] if "public void onSwiped" in ta else ""
need("tm.tabsOfGroup(t.groupId).size() == 1" in sw and "tm.captureGroupRowAnchors(t.groupId);" in sw,
     "the swipe that EMPTIES a group captures the folder row's neighbours as its anchors (before the close)")

print("A5 — the rotation triple (background playback survives landscape)")
pm = mhs.split("function pipMuted")[1][:2400] if "function pipMuted" in mhs else ""
need("if(ev&&ev.__boltRS)return false;" in pm,
     "the __boltRS resync exemption stays FIRST (PiP's synthetic resize passes)")
need("if(spoofOn&&typeof isReallyHidden==='function'&&isReallyHidden())return true;" in pm,
     "resize/orientationchange are muted while really-hidden (the background-rotation mute)")
need(pm.find("if(window.__boltpipx)return true;") < pm.find("isReallyHidden())return true;"),
     "the PiP mute keeps priority order (exemption -> pipx -> background)")
need("private int pausedOrientation = -1;" in ba,
     "BrowserActivity tracks the orientation it left in")
need("pausedOrientation = getResources().getConfiguration().orientation;" in ba.split("protected void onPause()")[1][:5200],
     "onPause captures the orientation")
rs = ba.split("THE ROTATION RESYNC")[1][:2200] if "THE ROTATION RESYNC" in ba else ""
need("nowOr != pausedOrientation" in rs and "holdsMedia || t.mediaPlaying" in rs,
     "the resume resync fires only when the orientation CHANGED while away, on media-holding tabs")
need("__rse.__boltRS=1;" in rs and "window.dispatchEvent(__rse);" in rs,
     "the resync dispatches ONE synthetic resize marked __boltRS (the v1.3.69 idiom)")
need("private long rotAt = 0;" in mc and "registerRotation();" in mc and "unregisterRotation();" in mc,
     "MediaControlService has the rotation state + DisplayListener lifecycle")
rot = mc.split("private void onDisplayMaybeRotated()")[1][:1800] if "private void onDisplayMaybeRotated()" in mc else ""
need("if (!appBg) return;" in rot and "rotAt = SystemClock.elapsedRealtime();" in rot
     and "armGuard();" in rot,
     "a display rotation while backgrounded re-arms the guard on a fresh base")
gc = mc.split("private void bgGuardCheck()")[1][:1600] if "private void bgGuardCheck()" in mc else ""
need("long base = Math.max(bgEnteredAt, rotAt);" in gc,
     "the guard discriminates against the NEWEST of backgrounding/rotation")
need("focusLostAt > focusGainedAt" in gc,
     "the focus-loss veto: a call's or another app's pause is never resurrected")
need("focusLostAt = SystemClock.elapsedRealtime();" in mc.split("noOpFocus = change -> {")[1][:900],
     "the focus listener latches loss/gain timestamps (still a no-op DECISION-MAKER)")

print("A6 — add-shortcut-directly-to-folder")
need("public boolean addSpeeddialItemTo(int groupIdx, String title, String url)" in prefs_src,
     "Prefs.addSpeeddialItemTo exists")
ait = prefs_src.split("public boolean addSpeeddialItemTo")[1][:1200]
need("!g.optBoolean(\"group\", false)) return false;" in ait and "items.put(o);" in ait,
     "it guards on a live group and APPENDS (put(Object) — the AOSP put(int,·) trap avoided)")
# v1.4.8 re-pin: the pill row moved OUT of the dialog — the folder chooser
# now appears AFTER "Add to browser home" (the v1.4.7 report's flow ask)
asd = ba.split("protected void addShortcut()")[1].split("protected void pinToLauncher")[0] if "protected void addShortcut()" in ba else ""
psf = ba.split("protected void pickShortcutFolderThenAdd")[1].split("protected void addShortcutIntoFolder")[0] if "protected void pickShortcutFolderThenAdd" in ba else ""
need("pickShortcutFolderThenAdd(name, url);" in asd,
     "Add to browser home opens the folder chooser as its own step (v1.4.8: after the tap)")
need("com.bolt.browser.util.Ui.choiceSheet(this, getString(R.string.choose_folder)," in psf,
     "the chooser sheet: Home (default) + every folder + New folder…")
need("if (prefs.isSpeeddialGroup(i)) {" in psf,
     "every existing home folder gets a row")
need("prefs.addSpeeddialFolder(" in psf,
     "New folder… creates inline AND adds straight into it")
need("prefs.addSpeeddialItemTo(idx, name, url)" in ba,
     "Add-to-browser-home lands the shortcut INSIDE the chosen folder (the shared landing)")
need('name="added_to_folder">Added to %1$s<' in strs,
     "the toast string exists")
need("pinToLauncher(url, name);" in asd,
     "Add-to-launcher is untouched (launcher pins have no folders)")

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
    work = "/tmp/bolt_v147_gate"
    for p in (os.path.join(work, "json.jar"), "/tmp/bolt_v146_gate/json.jar",
              "/tmp/bolt_v145_gate/json.jar", "/tmp/org.json.jar"):
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
    static class Tab {
        public long groupId = 0;
        public String name = "";
        Tab(String n, long g) { name = n; groupId = g; }
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
    // the OLD order (v1.4.6 and earlier): membered by first member,
    // empties stomped to the END by the MAX_VALUE sort
    static List<JSONObject> oldOrder() {
        List<JSONObject> out = groups();
        HashMap<Long, Integer> firstAt = new HashMap<Long, Integer>();
        for (int i = 0; i < tabs.size(); i++) {
            long gid = tabs.get(i).groupId;
            if (gid != 0 && !firstAt.containsKey(gid)) firstAt.put(gid, i);
        }
        final HashMap<Long, Integer> fa = firstAt;
        Collections.sort(out, (a, b) -> Integer.compare(
                fa.getOrDefault(a.optLong("id"), Integer.MAX_VALUE),
                fa.getOrDefault(b.optLong("id"), Integer.MAX_VALUE)));
        return out;
    }
    // the NEW merge, transcribed 1:1 from TabManager.groupsInFirstMemberOrder
    static List<JSONObject> mergedOrder() {
        List<JSONObject> all = groups();
        HashMap<Long, Integer> firstAt = new HashMap<Long, Integer>();
        for (int i = 0; i < tabs.size(); i++) {
            long gid = tabs.get(i).groupId;
            if (gid != 0 && !firstAt.containsKey(gid)) firstAt.put(gid, i);
        }
        List<JSONObject> membered = new ArrayList<JSONObject>();
        List<JSONObject> empties = new ArrayList<JSONObject>();
        for (JSONObject g : all) {
            if (g != null && firstAt.containsKey(g.optLong("id"))) membered.add(g);
            else if (g != null) empties.add(g);
        }
        final HashMap<Long, Integer> fa = firstAt;
        Collections.sort(membered, (a, b) -> Integer.compare(
                fa.getOrDefault(a.optLong("id"), Integer.MAX_VALUE),
                fa.getOrDefault(b.optLong("id"), Integer.MAX_VALUE)));
        if (empties.isEmpty()) return membered;
        int[] p = new int[empties.size()];
        for (int i = 0, m = 0; i < all.size(); i++) {
            JSONObject g = all.get(i);
            if (g == null) continue;
            if (firstAt.containsKey(g.optLong("id"))) { m++; continue; }
            int e = empties.indexOf(g);
            if (e >= 0) p[e] = m;
        }
        List<JSONObject> out = new ArrayList<JSONObject>(all.size());
        for (int j = 0; j <= membered.size(); j++) {
            for (int e = 0; e < empties.size(); e++)
                if (p[e] == j) out.add(empties.get(e));
            if (j < membered.size()) out.add(membered.get(j));
        }
        return out;
    }
    // the NEW follow walk's empty placement, transcribed 1:1 from
    // TabsActivity.rebuildItems (the follow branch, after the membered walk)
    static List<String> renderFollow() {
        List<Object> items = new ArrayList<Object>();
        HashSet<Long> emitted = new HashSet<Long>();
        List<JSONObject> gs = groups();
        for (Tab t : new ArrayList<Tab>(tabs)) {   // manual sort = list order
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
    // the v1.4.6 follow placement (all empties after the last folder row) —
    // demonstrates the stick
    static List<String> oldRenderFollow() {
        List<Object> items = new ArrayList<Object>();
        HashSet<Long> emitted = new HashSet<Long>();
        List<JSONObject> gs = groups();
        int lastFolderRow = -1;
        for (Tab t : new ArrayList<Tab>(tabs)) {
            if (t.groupId != 0 && !emitted.contains(t.groupId)) {
                for (JSONObject g : gs) {
                    if (g != null && g.optLong("id") == t.groupId) { items.add(g); break; }
                }
                emitted.add(t.groupId);
                lastFolderRow = items.size() - 1;
            }
            if (t.groupId == 0) items.add(t);
        }
        int putAt = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;
        for (JSONObject g : gs) {
            if (g != null && !emitted.contains(g.optLong("id"))) items.add(putAt++, g);
        }
        List<String> out = new ArrayList<String>();
        for (Object o : items) out.add(o instanceof JSONObject ? "G"+((JSONObject)o).optLong("id") : ((Tab)o).name);
        return out;
    }
    // addSpeeddialItemTo, transcribed 1:1 from Prefs
    static JSONArray speeddial = new JSONArray();
    static boolean addSpeeddialItemTo(int groupIdx, String title, String url) {
        try {
            JSONObject g = groupIdx >= 0 && groupIdx < speeddial.length()
                    ? speeddial.optJSONObject(groupIdx) : null;
            if (g == null || !g.optBoolean("group", false)) return false;
            JSONArray items = g.optJSONArray("items");
            if (items == null) items = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("url", url);
            items.put(o);
            g.put("items", items);
            return true;
        } catch (Throwable t) { return false; }
    }

    static int bad = 0;
    static void check(boolean ok, String why) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + why);
        if (!ok) bad++;
    }
    static String j(List<String> l) { return String.join(",", l); }

    public static void main(String[] args) throws Exception {
        // ---- B1: THE MERGE LAW (on-top's memory) ----
        // arrangement [G1][E1][G2] (E1 empty between them); the user drags
        // G2 ABOVE G1 (its first member now leads tm.tabs)
        tabs = new ArrayList<Tab>();
        tabs.add(new Tab("t2", 200));   // G2's member first (dragged up)
        tabs.add(new Tab("t1", 100));
        setGroups(100, 700, 200);       // array: G1, E1(700), G2
        List<String> oldO = new ArrayList<String>(), newO = new ArrayList<String>();
        for (JSONObject g : oldOrder()) oldO.add("G"+g.optLong("id"));
        for (JSONObject g : mergedOrder()) newO.add("G"+g.optLong("id"));
        check(j(oldO).equals("G200,G100,G700"),
                "the OLD MAX_VALUE sort stomps E1 to the END (the on-top stick demonstrated)");
        check(j(newO).equals("G200,G700,G100"),
                "the MERGE keeps E1 between the SAME membered neighbours (G2, E1, G1)");
        // and dragging G1 below G2 keeps E1 in the middle too
        tabs = new ArrayList<Tab>();
        tabs.add(new Tab("t2", 200));
        tabs.add(new Tab("t1", 100));
        // array after the G1-drag re-sort: membered G2,G1 with E1 p=1 kept
        setGroups(200, 700, 100);
        newO = new ArrayList<String>();
        for (JSONObject g : mergedOrder()) newO.add("G"+g.optLong("id"));
        check(j(newO).equals("G200,G700,G100"),
                "E1 still between its neighbours after the OTHER folder moved (only the held one moves)");

        // ---- B2: THE WALK SLOT LAW (follow's memory) ----
        // [G1row][E1 slot=1][G2row][L1][L2]: the user drags G2 to the BOTTOM
        tabs = new ArrayList<Tab>();
        tabs.add(new Tab("t1", 100));
        tabs.add(new Tab("L1"));
        tabs.add(new Tab("L2"));
        tabs.add(new Tab("t2", 200));   // G2's member dragged to the bottom
        setGroups(100, 700, 200);
        setSlot(700, 1);                // E1's slot from its last placement
        List<String> now = renderFollow();
        check(j(now).equals("G100,G700,L1,L2,G200"),
                "the membered drag leaves the slotted EMPTY at its row (E1 stays at index 1)");
        check(!j(oldRenderFollow()).equals(j(now)),
                "the OLD walk would have dragged E1 along (demonstrated: " + j(oldRenderFollow()) + ")");

        // pre-drag sanity: same tabs with G2 still up top
        tabs = new ArrayList<Tab>();
        tabs.add(new Tab("t1", 100));
        tabs.add(new Tab("t2", 200));
        tabs.add(new Tab("L1"));
        tabs.add(new Tab("L2"));
        check(j(renderFollow()).equals("G100,G700,G200,L1,L2"),
                "before the drag E1 sits at index 1 between the folders (the slot is honest)");

        // ---- B3: the slotless convention + the bottom slot ----
        setSlot(700, 99);               // a stale/late slot clamps to the end
        check(j(renderFollow()).endsWith("G700"),
                "an out-of-range slot clamps to the end (the drop-past-everything case)");
        JSONObject e1 = null;
        for (int i = 0; i < groups.length(); i++)
            if (groups.optJSONObject(i) != null && groups.optJSONObject(i).optLong("id") == 700)
                e1 = groups.optJSONObject(i);
        e1.remove("slot");              // a pre-v1.4.7 empty: slotless
        check(j(renderFollow()).equals("G100,G200,G700,L1,L2"),
                "a slotless empty keeps the v1.4.6 convention (after the last folder row, above the loose tabs)");

        // same-slot ties keep groups-array order
        setGroups(100, 700, 701, 200);  // E1(700) then E2(701), both slotless
        setSlot(700, 1); setSlot(701, 1);
        check(j(renderFollow()).equals("G100,G700,G701,G200,L1,L2"),
                "same-slot ties keep groups-array order (E1 before E2)");

        // ---- B4: the empty DROP lands where the finger was ----
        // dropping E1 AFTER L2 (the last row, index 4 of the pre-drop render)
        // is a plain slot write — no member math exists for an empty folder
        setGroups(100, 700, 200);
        setSlot(700, 4);                // [G1][G2][L1][L2] + drop after L2
        tabs = new ArrayList<Tab>();
        tabs.add(new Tab("t1", 100));
        tabs.add(new Tab("t2", 200));
        tabs.add(new Tab("L1"));
        tabs.add(new Tab("L2"));
        check(j(renderFollow()).equals("G100,G200,L1,L2,G700"),
                "an empty dropped at the bottom RENDERS at the bottom (the no-op drag is dead)");

        // ---- B5: addSpeeddialItemTo ----
        speeddial = new JSONArray();
        JSONObject folder = new JSONObject();
        folder.put("group", true); folder.put("name", "News"); folder.put("items", new JSONArray());
        JSONObject plain = new JSONObject();
        plain.put("title", "A site"); plain.put("url", "https://a.example");
        speeddial.put(plain); speeddial.put(folder);
        check(addSpeeddialItemTo(1, "Paper", "https://paper.example") == true,
                "addSpeeddialItemTo appends into a live folder");
        JSONArray items = speeddial.optJSONObject(1).optJSONArray("items");
        check(items.length() == 1 && items.optJSONObject(0).optString("url").equals("https://paper.example"),
                "the appended item carries its url (the site-adding flow's payload)");
        check(addSpeeddialItemTo(0, "X", "https://x.example") == false,
                "a non-group index is refused (the caller falls back to the top level)");
        check(addSpeeddialItemTo(9, "X", "https://x.example") == false,
                "an out-of-range index is refused");

        System.out.println(bad == 0 ? "PROBE OK" : "PROBE BAD " + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
"""
    work = "/tmp/bolt_v147_gate"
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
        need(rr.returncode == 0, "the probe runs green (the merge, the slot law, the folder add)")

print("V — the version pin")
need(re.search(r'versionCode 143\b', gradle) is not None, "versionCode 143")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName "1\.4\.29"', gradle) is not None, 'versionName "1.4.7"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): the escaped-form recovery (the phantom-backslash corpse)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print()
print(f"{'GATE GREEN' if fails == 0 else 'GATE RED (' + str(fails) + ')'}")
sys.exit(0 if fails == 0 else 1)
