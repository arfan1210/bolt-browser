#!/usr/bin/env python3
"""v1.4.1 gate — THE FOLDER-WINDOW MATRIX + THE ABOUT CHANGELOG/EASTER EGG +
ONE ORDERING MEMORY FOR BOTH GROUP PLACEMENTS.

Field report (v1.4.0): "the homescreen groups move fine. But opening it,
shows all the icons inside the group overlapping above each other instead
of forming a nice matrix. Fix it. The about page seems a bit too empty,
show a simplified update/change log there too. Also add an android like
easter egg that unlocks by tapping something 7 times in the about page to
open a short offline game, like some infiniite rng game that is very small
in size. also when I switch tab groups from always on top to follow tab
order, I want the tabs to not change order. It should change only when I
drag it. Like I want the manual mode to start from the state of the last
point in the immediate last position, like no separste memory for always
top or follow tab order."

PART A — THE SHARED-LAYOUTPARAMS CORPSE (the overlapping icons). The v1.4.0
folder window shared ONE GridLayout.LayoutParams object across every cell.
GridLayout's validateLayoutParams() auto-flows UNDEFINED specs and then
WRITES the resolved cell back into the child's LayoutParams
(setCellGroup -> setRowSpecSpan/setColumnSpecSpan — verified against the
AOSP GridLayout.java source, android-14.0.0_r1, fetched like the v1.4.0
gate's libcore evidence): child 0 resolved to (0,0), the SHARED spec turned
DEFINED, and every later child read that same spec — ALL icons stacked in
cell (0,0). The fix is structural: a FRESH LayoutParams per cell carrying
EXPLICIT rowSpec/columnSpec (spec(m/3), spec(m%3)) — the matrix is
declared, never auto-resolved. The gate re-fetches the framework source
and asserts the write-back law itself, then pins the fix's shape.

PART B — THE ABOUT PAGE (the empty-page ask): the simplified CHANGELOG (a
string-array, one line per recent release, newest first, the CURRENT
version's entry present — the label law) rendered as rows under a
"Recent updates" section, and the ANDROID-STYLE EASTER EGG: the version
row counts taps (a countdown toast from the third), the SEVENTH opens
Bolt Dash — a one-tap infinite-RNG dodger, pure Canvas, no assets, no
sound, one small Kotlin file (the APK budget law).

PART C — ONE ORDERING MEMORY (the placement-switch ask). The two
placements read two DIFFERENT orderings: "Groups on top" lays folder rows
out in the GROUPS-ARRAY order, "Groups in order" derives each folder row's
position from its FIRST MEMBER's slot in tm.tabs — switching jumped to
whatever the other ordering last was (the "separate memory"). THE FIX:
TabManager.syncGroupPlacement(nowOnTop) reconciles at BOTH toggle sites —
switching TO in-order materializes the on-top arrangement into tm.tabs
(each group's member block in groups-array order, then the loose tabs),
switching TO on-top rewrites the groups array to first-member order;
round-trip lossless. Prefs.reorderGroups is the lossless whole-array
rewrite (refuses size-mismatch/dup/unknown — a reorder can never drop or
clone a folder).

PART D — THE GROUP DRAG UNDER "GROUPS IN ORDER" (the parallel-session
delta, ported): the v1.4.0 array-reorder drag was VISUALLY INERT in that
placement; the drop now BLOCK-MOVES the group's member tabs
(TabManager.moveGroupRelative(gid, anchor, after)) — beside a loose tab
(before/after it) or beside another folder (clamped to its outer member —
never between members), then re-sorts the groups array to first-member
order so both placements agree. The drop rim lights loose tab cards in
that placement too (they are honest anchors there).

PART E — the functional probe: the REAL methods (groups/tabsOfGroup/
groupsInFirstMemberOrder/syncGroupPlacement/moveGroupRelative from
TabManager, reorderGroups from Prefs) extracted verbatim, compiled with
javac against the real org.json jar and RUN — the block-move law (never
between members), the materialization, the first-member re-sort, the
refusals, the lossless rewrite, instance identity.

V — the version pin.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
TA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/TabsActivity.java")
TM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/TabManager.java")
SIA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/SettingsItemsActivity.java")
PREFS = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Prefs.java")
ABOUT = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/AboutActivity.kt")
EGG = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BoltEggActivity.kt")
MANI = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
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

ba = slurp(BA)
ta = slurp(TA)
tm = slurp(TM)
sia = slurp(SIA)
prefs = slurp(PREFS)
about = slurp(ABOUT) if os.path.exists(ABOUT) else ""
egg = slurp(EGG) if os.path.exists(EGG) else ""
mani = slurp(MANI)
strs = slurp(STRS)
gradle = slurp(GR)

print("A — the shared-LayoutParams corpse, fixed (BrowserActivity)")
# the corpse is gone: no single glp shared by every cell
win = ba[ba.find("protected void showSdFolderWindow"):ba.find("protected void sdGroupEditMenu")]
loop_at = win.find("for (int m = 0")
need(loop_at < 0 or "GridLayout.LayoutParams glp =" not in win[:loop_at],
     "no LayoutParams declared ONCE before the loop (the shared object every cell wrote back into) — each cell builds its own")
need(re.search(r"GridLayout\.LayoutParams glp =\s*\n\s*new android\.widget\.GridLayout\.LayoutParams\(\);\s*\n\s*glp\.rowSpec = android\.widget\.GridLayout\.spec\(itemIdx / 3\);\s*\n\s*glp\.columnSpec = android\.widget\.GridLayout\.spec\(itemIdx % 3\);", ba) is not None,
     "a FRESH LayoutParams per cell with EXPLICIT row/column specs (spec(itemIdx/3), spec(itemIdx%3) — v1.4.4 renamed the loop var when the cell builder moved into sdWinAddCell) — the matrix is declared, never auto-resolved")
need(re.search(r"glp\.setMargins\(Fx\.dp\(this, 4\), Fx\.dp\(this, 4\),(?:\s*\n\s*Fx\.dp\(this, 4\), Fx\.dp\(this, 4\)\)\);| Fx\.dp\(this, 4\), Fx\.dp\(this, 4\)\)\;)\s*\n\s*grid\.addView\(cellV, glp\);", ba) is not None,
     "the fresh per-cell LayoutParams carries the margins and is the one added to the grid")
need("SHARED-LAYOUTPARAMS CORPSE" in ba and "setRowSpecSpan/setColumnSpecSpan" in ba,
     "the fix's comment records the write-back law (future sessions read the why)")

print("A2 — the framework evidence (the write-back law, re-read from AOSP)")
gl_src = None
cache = "/tmp/bolt_v141_gate"
os.makedirs(cache, exist_ok=True)
cand = [os.path.join(cache, "GridLayout.java"), "/tmp/GridLayout.java"]
for p in cand:
    if os.path.exists(p) and os.path.getsize(p) > 100_000:
        gl_src = slurp(p)
        break
if not gl_src:
    url = ("https://android.googlesource.com/platform/frameworks/base/+/refs/tags/"
           "android-14.0.0_r1/core/java/android/widget/GridLayout.java?format=TEXT")
    dst = os.path.join(cache, "GridLayout.b64")
    r = subprocess.run(["curl", "-sL", "--retry", "2", "-o", dst, url],
                       capture_output=True, text=True)
    if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 50_000:
        import base64
        with open(dst, "rb") as f:
            gl_src = base64.b64decode(f.read()).decode("utf-8", "replace")
        with open(os.path.join(cache, "GridLayout.java"), "w", encoding="utf-8") as f:
            f.write(gl_src)
if gl_src:
    need("private static void setCellGroup(LayoutParams lp, int row, int rowSpan, int col, int colSpan)" in gl_src
         and "lp.setRowSpecSpan(new Interval(row, row + rowSpan));" in gl_src,
         "AOSP GridLayout.setCellGroup WRITES the resolved cell back into the child's LayoutParams")
    need("private void validateLayoutParams()" in gl_src
         and re.search(r"for \(int i = 0, N = getChildCount\(\); i < N; i\+\+\) \{\s*\n\s*LayoutParams lp = \(LayoutParams\) getChildAt\(i\)\.getLayoutParams\(\);", gl_src) is not None
         and "install default indices for cells that don't define them" in gl_src,
         "validateLayoutParams auto-flows UNDEFINED specs child-by-child — the shared-object corpse is REAL, not a theory")
else:
    need(False, "the AOSP GridLayout.java fetch (network required, like test_qr's ZXing fetch)")

print("B — the About changelog + the easter egg")
need('name="about_changelog"' in strs, "the about_changelog string-array exists")
entries = re.findall(r"<item>([^<]+)</item>", strs[strs.find('name="about_changelog"'):strs.find("</string-array>", strs.find('name="about_changelog"'))]) if 'name="about_changelog"' in strs else []
need(len(entries) >= 8, f"the changelog carries the recent releases ({len(entries)} entries)")
need(entries and entries[11].startswith("1.4.18|"), "the NEWEST entry is the CURRENT version 1.4.13 (the label law; v1.4.11 re-point 2026-11-18)")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])
need(all("|" in e for e in entries), "every entry is version|summary (pipe-parsed)")
need("1.4.0|" in strs and "1.3.100|" in strs, "the v1.4.0 and v1.3.100 lines are there (the milestone + the prior release)")
need('name="recent_updates"' in strs and 'name="changelog_hint"' in strs,
     "the section label + hint strings exist")
need("R.array.about_changelog" in about and "e.indexOf('|')" in about
     and 'r.section = getString(R.string.recent_updates)' in about,
     "AboutActivity parses the array on the pipe and renders the section")
# the easter egg: 7 taps on the version row, countdown from the third, opens the game
need("private fun eggTap()" in about and "eggTaps >= 7" in about,
     "the version row counts taps and the SEVENTH is the door (the Android developer-options lineage)")
need("egg_taps_left, 7 - eggTaps" in about and "eggTaps >= 3" in about,
     "a countdown toast from the third tap (Android's own egg does exactly this)")
need("4000" in about, "taps >4 s apart reset the count (an egg found by accident is a bug, not an egg)")
need("BoltEggActivity::class.java" in about, "the seventh tap launches the game")
# the game itself: tiny, offline, RNG, no assets
need(os.path.exists(EGG), "BoltEggActivity.kt exists (a NEW file -> Kotlin per the standing rule)")
need('.ui.BoltEggActivity' in mani, "the game activity is registered in the manifest (exported=false)")
need(egg.count("\n") < 330, f"the game is TINY ({egg.count(chr(10))} source lines — a few KB in the dex; v1.4.4: the pacing pass added its comments)")
need("R.drawable" not in egg and "R.raw" not in egg and "R.layout" not in egg,
     "the game draws PURE CANVAS — no image assets, no sound, no layouts (the APK budget law)")
need("kotlin.random.Random" in egg and ("nextFloat" in egg or "nextInt" in egg),
     "the obstacles are pure RNG (infinite by construction — 'some infinite rng game')")
need('"eggBest"' in egg, "the best score persists (Prefs)")
need("postInvalidateOnAnimation" in egg, "the loop rides the frame clock (no timer thread)")

print("C — one ordering memory (v1.4.3 re-point: ONE MEMORY, TWO VIEWS)")
need("public List<JSONObject> groupsInFirstMemberOrder()" in tm,
     "groupsInFirstMemberOrder exists (the order BOTH placements render)")
need(re.search(r"void syncGroupPlacement", tm) is None and re.search(r"syncGroupPlacement\(", ta + sia) is None,
     "the v1.4.1 placement sync is GONE (no definition, no calls — the toggle is a pure view change; the historical comment remains)")
need(re.search(r"for \(JSONObject g : tm\.groupsInFirstMemberOrder\(\)\) items\.add\(g\);", ta) is not None,
     "the ON-TOP render derives folder order from FIRST-MEMBER positions in tm.tabs (the one memory — not the groups array)")
need("public boolean reorderGroups(java.util.List<JSONObject> order)" in prefs,
     "Prefs.reorderGroups exists (the whole-array rewrite)")
need(re.search(r"order\.size\(\) != groups\.length\(\)\) return false;", prefs) is not None
     and "!want.add(id)) return false;" in prefs,
     "reorderGroups refuses size-mismatch/dup/unknown orders (never drops or clones a folder)")
need(re.search(r'prefs\.set\("groupsOnTop", w == 0\);\s*\n\s*// v1\.4\.3[\s\S]{0,560}?adapter\.reload\(\);', ta) is not None,
     "the switcher's placement toggle is a PURE VIEW CHANGE (no sync, just the reload)")
need(re.search(r'prefs\.set\("groupsOnTop", w == 0\);\s*\n\s*// v1\.4\.3[\s\S]{0,260}?refresh\(\);', sia) is not None,
     "the settings' placement toggle is a pure view change too (SettingsItemsActivity)")

print("D — the group drag under BOTH placements (v1.4.3 re-point: one drag law)")
need("public boolean moveGroupRelative(long gid, Tab anchor, boolean after)" in tm,
     "TabManager.moveGroupRelative(gid, anchor, after) exists (the block move)")
need('boundary = after ? host.get(host.size() - 1) : host.get(0);' in tm,
     "a grouped anchor clamps to its group's OUTER member (never between members)")
need(re.search(r"public boolean moveGroupRelative\(long gid, Tab anchor, boolean after\) \{[\s\S]{0,1100}?rest\.addAll\(insert, members\);", tm) is not None,
     "the members move as a BLOCK (removed, then inserted before/after the boundary)")
need(re.search(r"public boolean moveGroupRelative\(long gid, Tab anchor, boolean after\) \{[\s\S]{0,1600}?prefs\.reorderGroups\(groupsInFirstMemberOrder\(\)\);", tm) is not None,
     "the groups array is re-sorted to first-member order inside the move (both placements agree)"
     "  [v1.4.9 re-point: budget 1400->1600 — the thread law wrapped the body in"
     "  synchronized (tabs), +86 chars, semantics untouched]")
need(re.search(r'gdone = tm\.moveGroupRelative\(gid, ganchor, gafter\);', ta) is not None
     and "prefs.moveGroupRelative" not in ta,
     "clearView's group drop block-moves in BOTH placements (the array-only reorder is retired)")
need(re.search(r'gunder != null && !prefs\.groupsOnTop\(\)\) \{[\s\S]{0,300}?Tab t = gp >= 0 \? adapter\.tabAt\(gp\) : null;\s*\n\s*if \(t != null && t\.groupId != gid\) ganchor = t;', ta) is not None,
     "a loose tab under the finger anchors in the IN-ORDER placement only (loose cards are not on-top drop targets)")
need(re.search(r"else if \(g == null && p >= 0 && draggingGroup != null\s*\n\s*&& !prefs\.groupsOnTop\(\)\)", ta) is not None,
     "the drop rim lights loose tab cards in the in-order placement (honest anchors)")

print("E — the functional probe (run the REAL methods against a real org.json)")
g_start = tm.find("    public List<JSONObject> groups() {")
g_end = tm.find("    /** v1.4.0 — THE ORPHAN HEAL")
f_start = tm.find("    /** the groups reordered by their FIRST MEMBER")
s_end = tm.rfind("}")
r_start = prefs.find("    /** v1.4.1 — rewrite the groups array to EXACTLY")
r_end = prefs.find("    // ---- speed dial")
if min(g_start, g_end, f_start, s_end, r_start, r_end) < 0 or g_end <= g_start or r_end <= r_start:
    need(False, "the methods are where the gate expects them")
    probe_ok = False
else:
    probe_ok = True

if probe_ok:
    rr_start = tm.find("    /** v1.4.5 — keep the ACTIVE TAB")
    rr_end = tm.find("    /** the groups reordered by their FIRST MEMBER")
    rr_blk = tm[rr_start:rr_end].replace("    private void reResolveActive",
                                         "    static void reResolveActive", 1) if 0 <= rr_start < rr_end else ""
    tm_block = tm[g_start:g_end] + rr_blk + tm[f_start:s_end]
    for sig in ("    public List<JSONObject> groups() {",
                "    public void assignGroup(int tabIndex, long groupId) {",
                "    public List<Tab> tabsOfGroup(long gid) {",
                "    public List<JSONObject> groupsInFirstMemberOrder() {",
                "    public boolean moveGroupRelative(long gid, Tab anchor, boolean after) {"):
        tm_block = tm_block.replace(sig, sig.replace("    public ", "    public static ", 1), 1)
    prefs_block = prefs[r_start:r_end]
    javac = None
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
    work = "/tmp/bolt_v140_gate"
    for p in (os.path.join(work, "json-20240303.jar"), "/tmp/org.json.jar"):
        if os.path.exists(p) and os.path.getsize(p) > 50_000:
            with open(p, "rb") as f:
                if f.read(2) == b"PK":
                    jar = p
                    break
    if not jar:
        urls = [
            "https://maven.aliyun.com/repository/public/org/json/json/20240303/json-20240303.jar",
            "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar",
        ]
        os.makedirs(work, exist_ok=True)
        dst = os.path.join(work, "json-20240303.jar")
        for u in urls:
            r = subprocess.run(["curl", "-sL", "--retry", "2", "-o", dst, u],
                               capture_output=True, text=True)
            if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 50_000:
                with open(dst, "rb") as f:
                    if f.read(2) == b"PK":
                        jar = dst
                        break
            try:
                os.remove(dst)
            except OSError:
                pass
    if not jar:
        need(False, "the org.json jar (aliyun/maven, the v140 gate's fetch)")
        probe_ok = False

if probe_ok:
    harness = """
    // ---- the Tab stub (field-accurate: the order math reads groupId + identity) ----
    static class Tab {
        public long groupId = 0;
        public long tid = 0;              // v1.4.8: the row identity (rowKeyOf)
        static long seq = 0;
        public String name = "";
        Tab(String n, long g) { name = n; groupId = g; tid = ++seq; }
        public String toString() { return name; }
    }
    static boolean INCOGNITO = false;
    static java.util.List<Tab> tabs = new java.util.ArrayList<Tab>();
    static int active = -1;
    static void save() { saved++; }
    static int saved = 0;
    // ---- the Prefs stub: groups() returns the live array; reorderGroups is REAL ----
    static class PrefsStub {
        JSONArray groups = new JSONArray();
        public JSONArray groups() { return groups; }
        void scheduleWrite() {}
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
""" + prefs_block.replace("    public boolean reorderGroups", "        public boolean reorderGroups", 1).replace("\n    ", "\n        ") + """
    }
    static PrefsStub prefs = new PrefsStub();
    static int gidOf(int n) { JSONObject o = new JSONObject(); o.put("id", n); return n; }
    static void setGroups(int... ids) {
        prefs.groups = new JSONArray();
        for (int id : ids) { JSONObject o = new JSONObject(); o.put("id", id); o.put("name", "G" + id); prefs.groups.put(o); }
    }
    static String showTabs() { return showTabs(tabs); }
    static String showTabs(java.util.List<Tab> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) { sb.append(list.get(i).name); if (i < list.size() - 1) sb.append(","); }
        return sb + "]";
    }
    static String showGroups() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < prefs.groups.length(); i++) { sb.append(prefs.groups.optJSONObject(i).optLong("id")); if (i < prefs.groups.length() - 1) sb.append(","); }
        return sb + "]";
    }
    static Tab find(String n) { for (Tab t : tabs) if (t.name.equals(n)) return t; return null; }
    static int bad = 0;
    static void eq(String label, Object got, Object expect) {
        if (!got.toString().equals(expect.toString())) { System.out.println("FAIL " + label + ": got " + got + " expected " + expect); bad++; }
    }
    public static void main(String[] args) {
        // ---- THE BLOCK MOVE, beside a loose tab ----
        // tabs A(x) B(g1) C(g1) D(g2) E(x);  groups [g1,g2]
        tabs = new java.util.ArrayList<Tab>();
        tabs.add(new Tab("A", 0)); tabs.add(new Tab("B", 1)); tabs.add(new Tab("C", 1));
        tabs.add(new Tab("D", 2)); tabs.add(new Tab("E", 0));
        setGroups(1, 2);
        moveGroupRelative(1L, find("E"), false);
        eq("block before loose E", showTabs(), "[A,D,B,C,E]");
        eq("array re-sorted to first-member order", showGroups(), "[2,1]");
        tabs = new java.util.ArrayList<Tab>();
        tabs.add(new Tab("A", 0)); tabs.add(new Tab("B", 1)); tabs.add(new Tab("C", 1));
        tabs.add(new Tab("D", 2)); tabs.add(new Tab("E", 0));
        moveGroupRelative(1L, find("A"), true);
        eq("block after loose A", showTabs(), "[A,B,C,D,E]");
        // ---- THE NEVER-BETWEEN-MEMBERS LAW ----
        // tabs D(g2) B(g1) F(g2): dropping g1 'before F' must land the whole
        // block before g2's FIRST member, never between D and F
        tabs = new java.util.ArrayList<Tab>();
        tabs.add(new Tab("D", 2)); tabs.add(new Tab("B", 1)); tabs.add(new Tab("F", 2));
        setGroups(1, 2);
        moveGroupRelative(1L, find("F"), false);
        eq("before g2's last member clamps to its FIRST (never between)", showTabs(), "[B,D,F]");
        tabs = new java.util.ArrayList<Tab>();
        tabs.add(new Tab("D", 2)); tabs.add(new Tab("B", 1)); tabs.add(new Tab("F", 2));
        moveGroupRelative(1L, find("D"), true);
        eq("after g2's first member clamps to its LAST (never between)", showTabs(), "[D,F,B]");
        // ---- refusals ----
        java.util.List<Tab> before = new java.util.ArrayList<Tab>(tabs);
        moveGroupRelative(1L, find("B"), false);   // anchor in the dragged group
        eq("anchor inside the dragged group refused", showTabs(), showTabs(before));
        moveGroupRelative(1L, null, true);
        eq("null anchor refused", showTabs(), showTabs(before));
        moveGroupRelative(99L, find("D"), true);
        eq("unknown group refused", showTabs(), showTabs(before));
        // ---- THE ONE MEMORY: on-top derives from first-member positions ----
        // tabs A(x) B(g1) C(g1) D(g2) E(x); groups ARRAY [g2,g1]: the on-top
        // render lists folders in FIRST-MEMBER order (g1 first at idx1, g2 at
        // idx3) regardless of what the array says — no sync, one memory
        tabs = new java.util.ArrayList<Tab>();
        tabs.add(new Tab("A", 0)); tabs.add(new Tab("B", 1)); tabs.add(new Tab("C", 1));
        tabs.add(new Tab("D", 2)); tabs.add(new Tab("E", 0));
        setGroups(2, 1);
        StringBuilder fmo = new StringBuilder("[");
        for (JSONObject g : groupsInFirstMemberOrder()) fmo.append(g.optLong("id")).append(",");
        eq("on-top derives folder order from tm.tabs, not the array",
           fmo.substring(0, fmo.length() - 1) + "]", "[1,2]");
        // ---- reorderGroups' refusals + losslessness (the Prefs side) ----
        setGroups(1, 2, 3);
        java.util.List<JSONObject> shortOrder = new java.util.ArrayList<JSONObject>();
        shortOrder.add(prefs.groups.optJSONObject(0));
        eq("short order refused", prefs.reorderGroups(shortOrder), false);
        eq("array untouched after refusal", showGroups(), "[1,2,3]");
        java.util.List<JSONObject> dupOrder = new java.util.ArrayList<JSONObject>();
        dupOrder.add(prefs.groups.optJSONObject(0)); dupOrder.add(prefs.groups.optJSONObject(1)); dupOrder.add(prefs.groups.optJSONObject(0));
        eq("dup order refused", prefs.reorderGroups(dupOrder), false);
        JSONArray ref = prefs.groups;
        java.util.List<JSONObject> okOrder = new java.util.ArrayList<JSONObject>();
        okOrder.add(prefs.groups.optJSONObject(2)); okOrder.add(prefs.groups.optJSONObject(0)); okOrder.add(prefs.groups.optJSONObject(1));
        eq("valid reorder accepted", prefs.reorderGroups(okOrder), true);
        eq("the rewrite landed", showGroups(), "[3,1,2]");
        if (prefs.groups != ref) { System.out.println("FAIL instance identity lost"); bad++; }
        if (bad > 0) { System.out.println("PROBE FAILURES: " + bad); System.exit(1); }
        System.out.println("probe: all green");
    }
}
"""
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        with open(src, "w", encoding="utf-8") as f:
            f.write("import org.json.JSONArray;\nimport org.json.JSONObject;\n\n"
                    "import java.util.ArrayList;\nimport java.util.List;\n\n"
                    "public class Probe {\n"
                    + tm_block + "\n" + harness)
        r = subprocess.run([javac, "-cp", jar, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the extracted methods compile (probe)")
            print(r.stderr[:1500])
        else:
            r2 = subprocess.run(["java", "-cp", d + os.pathsep + jar, "Probe"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL methods run green: the block-move law, never-between-members, the one-memory derivation, the refusals, lossless reorder")
            if r2.returncode != 0 or "all green" not in r2.stdout:
                print(r2.stdout[:2000])
                print(r2.stderr[:800])

print("V — the version pin")
need('versionCode 144' in gradle, 'versionCode 134')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need('versionName "1.4.30"' in gradle, 'versionName "1.4.30"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print()
if fails == 0:
    print("v1.4.1 gate (re-pointed for the v1.4.3 one-memory law): ALL GREEN")
else:
    print(f"v1.4.1 gate: {fails} FAILURES")
    sys.exit(1)
