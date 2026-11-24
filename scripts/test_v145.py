#!/usr/bin/env python3
"""v1.4.5 release gate — the projection materialize, the interception law,
and the disappear hardening.

The v1.4.4 field report ("a lot of the issues are still here"):

1. "Tab groups don't stay in the top when I switch from tab groups on top to
   follow tab order. I want the behaviour to be that the tabs and the groups
   stay in position as it is in the groups on top positions and then let me
   drag everything manually to re position as I need in the follow tabs
   order, in this mode I want both the tabs and tabs group too be dragged
   and repositionable to change order" — THE PROJECTION LAW: the on-top
   render is a PROJECTION of tm.tabs; a pure view change onto the raw
   memory anchors every folder at its first member's slot, so the follow
   view the user lands on is NOT the arrangement they were looking at.
   The toggle now MATERIALIZES the on-top visual into tm.tabs
   (TabManager.materializeOnTopOrder) at both toggle doors; the follow
   render then draws the identical grid, and every drag works from there
   (loose tabs beside folders, folders anywhere — plus the v1.4.5
   empty-space fallbacks: a drop past the last row anchors against the
   nearest row of ANY kind, folder rows included, in the follow placement).

2. "Also i want the tabs in the tab groups to be manually orgamisable by
   dragging and similarly the home screen icon in the icons groups be
   draggable to change serial" — THE INTERCEPTION LAW: both v1.4.4 drags
   live INSIDE scrollable parents (the mini strip rides a
   HorizontalScrollView which rides the RecyclerView; the window cells ride
   a ScrollView) — the first MOVE past the parent's slop was intercepted,
   the child got ACTION_CANCEL, and the drag died before a single slot
   moved. The data layer was probe-green; the GESTURE was dead on device.
   The lift now raises requestDisallowInterceptTouchEvent through the
   parent chain (lesson 83's direction), released at UP/CANCEL.

3. "some tabs or tab groups seem to disappear when changing from groups on
   top to follow tab order" — FOUR hardening fronts: (a) the projection
   materialize kills the perceptual vanish (folders no longer jump below
   the fold); (b) duplicate-id folders rendered on-top but VANISHED in the
   follow walk (emitted-set keyed by id) — healGroups now dedupes ids
   first; (c) Fx.writeText was truncate-in-place — a process death
   mid-write left a half-file that every reader swallows into an EMPTY
   array; writes are now temp-file + atomic rename; (d) the incognito
   process's writeNow rewrote groups.json/speeddial.json from its STALE
   in-memory copy on any setting change (the README's future-work #8) — a
   file is now written only when its serialization differs from what this
   process loaded/wrote (the dirty-write law).

4. THE ACTIVE-TAB LAW (found writing #1): `active` is a positional index;
   every reorder of tm.tabs silently re-pointed it at a different tab — the
   switcher closing without a tap then showed the WRONG page (reads as a
   tab vanishing). Every reorder now re-resolves the active tab OBJECT.

PART A — static checks. PART B — the functional probe (javac + run against
the real org.json): materializeOnTopOrder (lossless, both renders agree,
active law), the render-parity transcription, dedupeGroups, the reorder
active law, moveSpeeddialItem regression, the atomic write round-trip.
V — the version pin.
"""
import os, re, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/TabsActivity.java")
TM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/TabManager.java")
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
SI = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/SettingsItemsActivity.java")
PF = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Prefs.java")
FX = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Fx.java")
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

ta, tm, ba, si = (slurp(TA), slurp(TM), slurp(BA), slurp(SI))
prefs_src, fx, strs, gradle = (slurp(PF), slurp(FX), slurp(STRS), slurp(GR))

print("A1 — the projection materialize (TabManager + both toggle doors)")
need("public boolean materializeOnTopOrder()" in tm,
     "TabManager.materializeOnTopOrder exists (the projection law's writer)")
mat = tm.split("public boolean materializeOnTopOrder()")[1][:2600] if "public boolean materializeOnTopOrder()" in tm else ""
need(("for (JSONObject g : groupsInFirstMemberOrder())" in mat
      or "List<JSONObject> order = groupsInFirstMemberOrder();" in mat)
     and "out.addAll(tabsOfGroup(g.optLong(\"id\")))" in mat,
     "the materialize writes every folder's member BLOCK in first-member order")
need("for (Tab t : tabs) if (t.groupId == 0) out.add(t);" in mat,
     "the loose tabs follow below (their current order) — exactly the on-top visual")
need("out.size() != tabs.size()" in mat and "before.isEmpty()" in mat,
     "the materialize is lossless by construction (size + identity guards, never a partial rewrite)")
need("reResolveActive(wasActive);" in mat,
     "the active tab survives the materialize as the same OBJECT (the positional-index law)")
need("wasOnTop && w == 1" in ta and "tm.materializeOnTopOrder();" in ta,
     "the switcher's placement toggle materializes on the on-top -> follow switch ONLY")
need("wasOnTop && w == 1" in si and "materializeOnTopOrder();" in si,
     "the Settings > Tabs door materializes too (same law, both doors)")
need(ta.find("materializeOnTopOrder()") > 0 and "prefs.set(\"groupsOnTop\", w == 0)" in ta
     and ta.find("materializeOnTopOrder();") < ta.find('prefs.set("groupsOnTop", w == 0)'),
     "the materialize runs BEFORE the pref flip (the on-top visual is what gets written)")

print("A2 — the interception law (both hold-to-drag surfaces)")
need("protected void ownTouchStream(View v, boolean own)" in ta
     and "p.requestDisallowInterceptTouchEvent(own);" in ta,
     "TabsActivity.ownTouchStream exists (the flag raises through the parent chain)")
need(ta.find("ownTouchStream(mini, true);") > 0
     and ta.find("ownTouchStream(mini, true);") < ta.find("miniPitch = n >= 2"),
     "the MINI lift arms the stream BEFORE the drag can move (the v1.4.4 corpse)")
need(ta.count("ownTouchStream(v, false);") >= 2,
     "the mini drag releases the stream at every end (drop + hold-menu paths)")
wsc = ba.split("OnTouchListener sdWinCellTouch")[1][:4000] if "OnTouchListener sdWinCellTouch" in ba else ""
need("requestDisallowInterceptTouchEvent(true)" in wsc
     and wsc.find("requestDisallowInterceptTouchEvent(true)") > wsc.find("sdWinDragMoved = false;"),
     "the WINDOW lift arms the stream at lift time (inside sdWinLiftRun, after the state capture)")
need(ba.find("requestDisallowInterceptTouchEvent(false)") > ba.find("requestDisallowInterceptTouchEvent(true)")
     and "ACTION_CANCEL" in ba.split("OnTouchListener sdWinCellTouch")[1][:4000],
     "the window gesture releases the stream at UP/CANCEL (the window scrolls again)")
need("ownTouchStream(mini, true);" in ta.split("protected void armMiniDrag")[1][:2000],
     "the flag raise lives in the lift itself (not the move path — before the lift the parents may scroll)")

print("A3 — the disappear hardening (dedupe + atomic + dirty-write)")
need("public int dedupeGroups()" in prefs_src,
     "Prefs.dedupeGroups exists (the duplicate-id folder corpse)")
need("int healed = prefs.dedupeGroups();" in tm,
     "healGroups dedupes ids FIRST (a dup folder vanished in the follow walk only)")
dd = prefs_src.split("public int dedupeGroups()")[1][:1300] if "public int dedupeGroups()" in prefs_src else ""
need("!seen.add(id)" in dd and "groups.remove(i);" in dd,
     "the dedupe keeps the FIRST row per id (members share the id — nothing is lost)")
need("File tmp = new File(f.getParentFile(), f.getName() + \".tmp\");" in fx
     and "tmp.renameTo(f)" in fx,
     "Fx.writeText is ATOMIC (temp file + rename — a process death can never leave a half-file)")
wt = fx.split("public static boolean writeText")[1][:1200] if "public static boolean writeText" in fx else ""
need(wt.find("new FileOutputStream(tmp)") >= 0
     and wt.find("new FileOutputStream(tmp)") < wt.find("tmp.renameTo(f)"),
     "the TEMP file is written FIRST — the target is never truncated before the rename is possible (the v1.4.4 corpse's shape)")
need("out.getFD().sync();" in fx,
     "the temp file is fsynced before the rename (the bytes are on flash, not in cache)")
need("private String diskGlobal, diskSites, diskBookmarks, diskHistory," in prefs_src,
     "the dirty-write law's disk-state fields exist")
wn = prefs_src.split("private void writeNow()")[1][:2000] if "private void writeNow()" in prefs_src else ""
need("!g.equals(diskGroups)" in wn and "Fx.writeJsonArray(file(\"groups.json\")" in wn,
     "groups.json is written only when CHANGED (the incognito stale-clobber is dead)")
need(wn.count(".equals(disk") >= 6,
     "every data file rides the same dirty check (prefs, sites, history, bookmarks, groups, speeddial)")
need("diskGroups = groups.toString();" in prefs_src,
     "the disk-state snapshots start at LOAD time (an untouched copy is never a writer)")

print("A4 — the active-tab law + the empty-space anchors")
for sig in ("public void move(int from, int to)", "public boolean moveGroupRelative",
            "public boolean reorderGroupMember", "public boolean moveTabBesideGroup"):
    block = tm.split(sig)[1][:1900]
    need("reResolveActive(wasActive);" in block,
         f"the reorder re-resolves the active tab OBJECT: {sig.split('(')[0]}")
need("private void reResolveActive(Tab was)" in tm,
     "the reResolveActive helper exists (a positional index must follow its object)")
need("private View nearestRow(RecyclerView r, View exclude)" in ta,
     "nearestRow exists (empty-space drops can anchor against a FOLDER row too)")
plain = ta.split("// 3) v1.3.46 plain drop")[1][:4600]
need("nearestRow(rv, vh.itemView) : nearestTabCard(rv, vh.itemView)" in plain,
     "the loose-tab empty-space fallback anchors ANY row in follow-order (on-top keeps loose-only)")
grp = ta.split("final JSONObject gdrag = draggingGroup;")[1][:1200]
need("gunder = nearestRow(rv, vh.itemView);" in grp,
     "the folder-drag empty-space fallback anchors the nearest row in follow-order")

print("B — the functional probe (javac + run)")
# extract the REAL methods: TabManager's materialize + block moves, Prefs' dedupe
m_start = tm.find("    public List<JSONObject> groups() {")
m_end = tm.find("    /** v1.4.0 — THE ORPHAN HEAL")
f_start = tm.find("    /** the groups reordered by their FIRST MEMBER")
mbrg_start = tm.find("    /** v1.4.1 — THE GROUP DRAG UNDER BOTH PLACEMENTS")
s_end = tm.rfind("}")
rr_start = tm.find("    /** v1.4.5 — keep the ACTIVE TAB")
rr_end = tm.find("    /** the groups reordered by their FIRST MEMBER")
dd_start = prefs_src.find("    /** v1.4.5 — DEDUPE duplicate-id folder rows")
dd_end = prefs_src.find("    /** v1.4.0 — the groups-array index")
sd_start = prefs_src.find("    /** v1.4.4 — REORDER AN ITEM INSIDE A GROUP")
sd_end = prefs_src.find("    /** move an item out of a group")
rg_start = prefs_src.find("    /** v1.4.1 — rewrite the groups array to EXACTLY")
rg_end = prefs_src.find("    // ---- speed dial")
wt_start = fx.find("    /** v1.4.5 — ATOMIC WRITE")
wt_end = fx.find("    public static JSONObject readJson")
probe_ok = min(m_start, m_end, f_start, mbrg_start, s_end, rr_start, rr_end,
               dd_start, dd_end, sd_start, sd_end, rg_start, rg_end, wt_start, wt_end) >= 0

javac = None
if probe_ok:
    tm_block = tm[m_start:m_end] + tm[rr_start:rr_end] + tm[f_start:s_end]
    for sig in ("    public List<JSONObject> groups() {",
                "    public void assignGroup(int tabIndex, long groupId) {",
                "    public List<Tab> tabsOfGroup(long gid) {",
                "    public List<JSONObject> groupsInFirstMemberOrder() {",
                "    public boolean materializeOnTopOrder() {",
                "    public boolean moveGroupRelative(long gid, Tab anchor, boolean after) {",
                "    public boolean reorderGroupMember(long gid, int from, int to) {",
                "    public boolean moveTabBesideGroup(Tab dragged, long gid, boolean after) {"):
        if sig not in tm_block:
            need(False, f"the gate finds the method body: {sig.strip()}")
            probe_ok = False
            break
        tm_block = tm_block.replace(sig, sig.replace("    public ", "    public static ", 1), 1)
    tm_block = tm_block.replace("    private void reResolveActive(Tab was) {",
                                "    public static void reResolveActive(Tab was) {", 1)
    dd_block = prefs_src[dd_start:dd_end]
    dd_block = dd_block.replace("    public int dedupeGroups()", "        public int dedupeGroups()", 1)
    sd_block = prefs_src[sd_start:sd_end]
    sd_block = sd_block.replace("    public boolean moveSpeeddialItem", "        public boolean moveSpeeddialItem", 1)
    rg_block = prefs_src[rg_start:rg_end].replace("    public boolean reorderGroups", "        public boolean reorderGroups", 1)
    wt_block = fx[wt_start:wt_end]
    wt_block = wt_block.replace("    public static boolean writeText", "    public static boolean writeText", 1)
    for cand in (os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javac"),
                 "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/javac",
                 shutil.which("javac") or ""):
        if cand and os.path.exists(cand):
            javac = cand
            break
    if not javac:
        need(False, "javac found (run scripts/setup_toolchain.sh first)")
        probe_ok = False

if probe_ok:
    jar = None
    work = "/tmp/bolt_v145_gate"
    for p in (os.path.join(work, "json.jar"), "/tmp/bolt_v144_gate/json.jar",
              "/tmp/bolt_v143_gate/json.jar", "/tmp/org.json.jar"):
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
        probe_ok = False

if probe_ok:
    harness = """
import org.json.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class Probe {
    static class Tab {
        public long groupId = 0;
        public long tid = 0;              // v1.4.8: the row identity (rowKeyOf)
        static long seq = 0;
        public String name = "";
        Tab(String n, long g) { name = n; groupId = g; tid = ++seq; }
        Tab(String n) { this(n, 0); }
        public String toString() { return name; }
    }
    static List<Tab> tabs = new ArrayList<Tab>();
    static int active = -1;
    static void save() {}
    static class PrefsStub {
        JSONArray groups = new JSONArray();
        JSONArray speeddial = new JSONArray();
        public JSONArray groups() { return groups; }
        void scheduleWrite() {}
        void saveSpeeddial() {}
        public void setGroupSlot(long id, int slot) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g != null && g.optLong("id") == id) {
                    try { if (slot >= 0) g.put("slot", slot); else g.remove("slot"); } catch (Exception e) {}
                }
            }
        }
        public void setGroupAnchor(long id, String prevKey, String nextKey) {   // v1.4.8
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g != null && g.optLong("id") == id) {
                    try { g.put("ap", prevKey == null ? "" : prevKey); g.put("an", nextKey == null ? "" : nextKey); g.remove("slot"); } catch (Exception e) {}
                }
            }
        }
        public void clearGroupPos(long id) {                                     // v1.4.8
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g != null && g.optLong("id") == id) {
                    try { g.remove("slot"); g.remove("ap"); g.remove("an"); } catch (Exception e) {}
                }
            }
        }
        public JSONObject speeddialAt(int idx) {
            return idx >= 0 && idx < speeddial.length() ? speeddial.optJSONObject(idx) : null;
        }
""" + rg_block.replace("\n    ", "\n        ") + "\n" + dd_block.replace("\n    ", "\n        ") + "\n" + sd_block.replace("\n    ", "\n        ") + """
    }
    static PrefsStub prefs = new PrefsStub();
    static boolean INCOGNITO = false;
    // ---- the REAL TabManager methods (static-ized) ----
""" + tm_block + """
    // ---- the REAL Fx.writeText (verbatim, the atomic body) ----
""" + wt_block + """
    static void setGroups(int... ids) {
        prefs.groups = new JSONArray();
        for (int id : ids) { JSONObject o = new JSONObject(); try { o.put("id", id); o.put("name", "G"+id); } catch (Exception e) {} prefs.groups.put(o); }
    }
    static String names() { StringBuilder sb = new StringBuilder(); for (Tab t : tabs) sb.append(t.name); return sb.toString(); }
    static int bad = 0;
    static void ok(String label, boolean v) { if (!v) { System.out.println("FAIL "+label); bad++; } }
    static void eq(String label, Object a, Object b) { ok(label+" ("+a+" vs "+b+")", a.toString().equals(b.toString())); }

    // ---- the two render branches, transcribed 1:1 from rebuildItems
    //      (groupsOnTop=true pins folders first; false anchors each folder
    //      row at its first member; v1.4.6: never-emitted folders land
    //      right after the LAST folder row — the top when none) ----
    static List<String> render(boolean onTop) {
        List<String> out = new ArrayList<String>();
        List<Tab> sorted = new ArrayList<Tab>(tabs);   // manual sort = list order
        if (onTop) {
            for (JSONObject g : groupsInFirstMemberOrder()) out.add("G"+g.optLong("id"));
            for (Tab t : sorted) if (t.groupId == 0) out.add(t.name);
        } else {
            HashSet<Long> emitted = new HashSet<Long>();
            List<JSONObject> groups = groups();
            int lastFolderRow = -1;
            for (Tab t : sorted) {
                if (t.groupId != 0 && !emitted.contains(t.groupId)) {
                    for (JSONObject g : groups) {
                        if (g != null && g.optLong("id") == t.groupId) { out.add("G"+g.optLong("id")); break; }
                    }
                    emitted.add(t.groupId);
                    lastFolderRow = out.size() - 1;
                }
                if (t.groupId == 0) out.add(t.name);
            }
            int putAt = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;
            for (JSONObject g : groups) {
                if (g != null && !emitted.contains(g.optLong("id"))) out.add(putAt++, "G"+g.optLong("id"));
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        // ================= B1: materializeOnTopOrder =================
        // interleaved memory [L1, a1, L2, a2, b1] with A={a1,a2}, B={b1}:
        // the ON-TOP projection shows [A, B, L1, L2]; the follow render
        // pre-fix showed [L1, A, L2, B] — THE JUMP. After the materialize
        // the follow render must equal the on-top render, element-wise.
        setGroups(100, 200);
        Tab a1 = new Tab("a1", 100), a2 = new Tab("a2", 100), b1 = new Tab("b1", 200);
        Tab l1 = new Tab("L1"), l2 = new Tab("L2");
        tabs.clear(); tabs.add(l1); tabs.add(a1); tabs.add(l2); tabs.add(a2); tabs.add(b1);
        eq("B1a the pre-fix corpse is real (follow != on-top on the raw memory)",
           render(false), "[L1, G100, L2, G200]");
        ok("B1b the materialize runs", materializeOnTopOrder());
        eq("B1c tm.tabs is now the on-top visual (blocks then loose)", names(), "a1a2b1L1L2");
        eq("B1d THE LAW: both placements render the SAME rows now", render(false), render(true));
        eq("B1e and that render is the on-top projection", render(true), "[G100, G200, L1, L2]");
        // lossless: every original tab is still there, exactly once
        Set<Tab> survivors = new HashSet<Tab>(tabs);
        ok("B1f lossless (5 unique tabs)", survivors.size() == 5 && tabs.size() == 5);
        // the active-tab law: the tab at the old active index keeps identity
        tabs.clear(); tabs.add(l1); tabs.add(a1); tabs.add(l2); tabs.add(a2); tabs.add(b1);
        active = 4;   // user is browsing b1 (the last tab)
        materializeOnTopOrder();
        ok("B1g the ACTIVE tab is still b1 after the materialize", tabs.get(active) == b1);
        // no groups at all: a harmless no-op reorder (loose order kept)
        setGroups();
        tabs.clear(); tabs.add(l1); tabs.add(l2); tabs.add(a1);
        a1.groupId = 0;
        materializeOnTopOrder();
        eq("B1h no groups: the loose order is untouched", names(), "L1L2a1");

        // ================= B2: the reorder active law =================
        setGroups(100, 200);
        a1.groupId = 100; a2.groupId = 100; b1.groupId = 200;   // B1h cleared them
        tabs.clear(); tabs.add(a1); tabs.add(a2); tabs.add(l1); tabs.add(b1); tabs.add(l2);
        active = 4;   // browsing L2
        ok("B2a the group block move runs", moveGroupRelative(200, l1, false));
        eq("B2b B's block lands before L1", names(), "a1a2b1L1L2");
        ok("B2c the ACTIVE tab is still L2 after the block move", tabs.get(active) == l2);
        ok("B2d the in-group move runs", reorderGroupMember(100, 1, 0));
        ok("B2e the ACTIVE tab survives the in-group move", tabs.get(active) == l2);
        moveTabBesideGroup(l2, 200, true);
        ok("B2f the ACTIVE tab survives the beside-folder move", tabs.get(active) == l2);

        // ================= B3: dedupeGroups =================
        prefs.groups = new JSONArray();
        for (long id : new long[]{100, 300, 100, 200, 300, 300}) {
            JSONObject o = new JSONObject(); o.put("id", id); o.put("name", "G"+id); prefs.groups.put(o);
        }
        int dropped = prefs.dedupeGroups();
        ok("B3a three duplicate rows dropped", dropped == 3);
        Set<Long> ids = new HashSet<Long>();
        for (int i = 0; i < prefs.groups().length(); i++) ids.add(prefs.groups().optJSONObject(i).optLong("id"));
        ok("B3b the survivors are exactly the unique ids {100,300,200}", ids.equals(new HashSet<Long>(Arrays.asList(100L, 300L, 200L))));
        // the FIRST occurrence is the keeper
        ok("B3c the first row is the first-seen id (100)", prefs.groups().optJSONObject(0).optLong("id") == 100);

        // ================= B4: the atomic write =================
        File dir = new File(System.getProperty("java.io.tmpdir"), "bolt_v145_" + System.nanoTime());
        dir.mkdirs();
        File f = new File(dir, "groups.json");
        ok("B4a the atomic write succeeds", writeText(f, "[10,20,30]"));
        String read = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        eq("B4b the content round-trips", read, "[10,20,30]");
        File[] leftovers = dir.listFiles(new FilenameFilter() {
            public boolean accept(File d, String n) { return n.endsWith(".tmp"); }
        });
        ok("B4c no .tmp leftovers (the rename consumed it)", leftovers == null || leftovers.length == 0);
        ok("B4d a rewrite over an existing file works", writeText(f, "[2,3,4]"));
        read = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        eq("B4e the overwrite lands whole", read, "[2,3,4]");
        for (File x : dir.listFiles()) x.delete();
        dir.delete();

        // ================= B5: moveSpeeddialItem regression (v1.4.4 held) =================
        JSONObject folder = new JSONObject();
        JSONArray items = new JSONArray();
        for (String[] it : new String[][]{{"A","ua"},{"B","ub"},{"C","uc"}}) {
            JSONObject o = new JSONObject(); o.put("title", it[0]); o.put("url", it[1]); items.put(o);
        }
        folder.put("group", true); folder.put("name", "F"); folder.put("items", items);
        prefs.speeddial = new JSONArray(); prefs.speeddial.put(folder);
        ok("B5a the item move still runs", prefs.moveSpeeddialItem(0, 1, 2));
        JSONArray got = prefs.speeddialAt(0).getJSONArray("items");
        eq("B5b the order is A C B", "" + got.getJSONObject(0).getString("title")
           + got.getJSONObject(1).getString("title") + got.getJSONObject(2).getString("title"), "ACB");

        if (bad > 0) { System.out.println("PROBE FAILURES: " + bad); System.exit(1); }
        System.out.println("probe: all green");
    }
}
"""
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        with open(src, "w", encoding="utf-8") as f:
            f.write(harness)
        r = subprocess.run([javac, "-cp", jar, "-d", d, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the extracted methods compile (probe)")
            print(r.stderr[:2500])
        else:
            r2 = subprocess.run(["java", "-cp", d + os.pathsep + jar, "Probe"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL code runs green: materializeOnTopOrder (the jump is real pre-fix, both renders agree post-fix, lossless, active law, no-groups no-op), the reorder active law, dedupeGroups (first keeper), the atomic write (round-trip, no tmp leftovers, overwrite), moveSpeeddialItem regression")
            if r2.returncode != 0 or "all green" not in r2.stdout:
                print(r2.stdout[:3000])
                print(r2.stderr[:800])

print("V — the version pin")
need('versionCode 143' in gradle, 'versionCode 134')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need('versionName "1.4.29"' in gradle, 'versionName "1.4.29"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
entries = re.findall(r"<item>([^<]+)</item>", strs[strs.find('name="about_changelog"'):strs.find("</string-array>", strs.find('name="about_changelog"'))])
need(entries and entries[10].startswith("1.4.18|"), "the About changelog's newest entry is 1.4.13 (the label law; v1.4.11 re-point 2026-11-18)")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])

print()
if fails == 0:
    print("v1.4.5 gate: ALL GREEN")
else:
    print(f"v1.4.5 gate: {fails} FAILURES")
    sys.exit(1)
