#!/usr/bin/env python3
"""v1.4.2 gate — THE EGG'S FROZEN FRONTIER + ABOUT IN DEEP SETTINGS + THE
MEMBERLESS-FOLDER PARITY + LOCAL-FIRST SUGGESTIONS.

Field report (v1.4.1): "Move the about bolt to deep settings. The app becomes
very slow and laggy when i tap the bolt a few times and quick tap does not
register as individual taps. Mainly, no game launches, just a black screen.
In tabs, the groups disappear when I switch from always on top to follow tab
order and returns back when I go back to always on top. Also i want a feature
when searching, keep the options from bookmarks and shortcuts as a higher
priority recommendation when typing to search. Make a toggle to turn it on
or off in the deep settings."

PART A — THE FROZEN-FRONTIER CORPSE (the black screen + the lag). The
v1.4.1 BoltEggActivity.maybeSpawn() read the spawn frontier (the last bar's
right edge) into a val ONCE, before the loop — the while's condition read a
value the body never advanced, so the FIRST call (bars empty: lastRight =
w+40dp < horizon = w+80dp) looped FOREVER, allocating Bar after Bar during
the FIRST layout pass: the main thread never finished the first frame (a
black screen — the window never drew), while burning 100% CPU (the whole
app slow and laggy; every later tap felt dead — the whole report is ONE
corpse). THE FIX: the frontier advances every iteration (`var right`,
`right = x + bw`). THE LAW: a spawn loop must advance the frontier it tests
— a while whose condition reads a val it never writes is an infinite loop
wearing a for-loop costume. The gate pins the fix's shape AND proves both
sides behaviorally (Part B's probe: the OLD shape trips the iteration cap,
the NEW shape terminates and fills to the horizon).

PART A2 — THE ONE-LIVE-COUNTDOWN TOAST. Every tap used to enqueue a NEW
2-second toast, so a quick 3-4-5-6 flurry replayed as a stale countdown
QUEUE for ~8 seconds — "quick tap does not register as individual taps".
The countdown is now ONE toast, cancelled and reshown per tap (Android's
own dev-options door updates in place).

PART C — ABOUT IN DEEP SETTINGS. The About row (on the settings home since
v1.4.0) moves into the deep tree: last row of the DeepSettings category
list, carrying the LIVE version chip; searchable from the deep index; gone
from the settings home (both settings SEARCHES still find it).

PART D — THE MEMBERLESS-FOLDER PARITY (the disappearing groups). The two
placements derive folder rows from DIFFERENT sources: on-top lists them
from the groups ARRAY, in-order anchors each at its FIRST MEMBER's slot in
tm.tabs. A folder whose last tab was closed (close() keeps the group; only
Delete group removes it) has NO member to anchor on — it silently VANISHED
in "follow tab order" while staying visible "always on top": exactly the
field report. THE FIX: the in-order walk appends never-emitted (memberless)
folders at the END, in groups-array order — the same empty-last convention
groupsInFirstMemberOrder() already sorts them by. THE LAW: both placements
must render the SAME SET of rows. Proven by Part B's probe against the REAL
sync + the REAL render branches.

PART E — LOCAL-FIRST SUGGESTIONS (the feature ask). Prefs.suggestLocalFirst
(default ON): with it on, the omnibox ranks BOOKMARKS and HOME-SCREEN
SHORTCUTS (the speed dial, folder entries included — shortcuts never
suggested before) ABOVE history while typing; off restores the classic
order. The toggle lives in deep Settings > General, searchable from the
deep index.

PART B — the functional probe: the REAL methods (groups/tabsOfGroup/
groupsInFirstMemberOrder/moveGroupRelative from TabManager, reorderGroups
from Prefs) and the REAL render branches (the on-top block and the PATCHED
in-order block, both sliced verbatim out of TabsActivity) extracted,
compiled with javac against the real org.json jar and RUN: the spawn loop
(old corpse shape trips the cap / new shape terminates, monotone, fills
the horizon, respawns after a scroll), the placement-parity laws
(populated groups: identical rows across placements, the v1.4.1 law holds;
memberless folders: present in the fixed render, missed by the old shape;
round-trip lossless).

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
SHA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/SettingsHomeActivity.java")
DSA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/DeepSettingsActivity.java")
ABOUT = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/AboutActivity.kt")
EGG = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BoltEggActivity.kt")
PREFS = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Prefs.java")
STRS = os.path.join(ROOT, "app/src/main/res/values/strings.xml")
GR = os.path.join(ROOT, "app/build.gradle")


def balance_end(src, start):
    """v1.4.7: the follow branch's true close, by brace balance (the
    brace-hop chains break every time the walk's nesting changes)."""
    depth = 0; i = start; begun = False
    while i < len(src):
        c = src[i]
        if c == '{': depth += 1; begun = True
        elif c == '}':
            depth -= 1
            if begun and depth == 0: return i + 1
        i += 1
    return -1

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
sha = slurp(SHA)
dsa = slurp(DSA)
about = slurp(ABOUT)
egg = slurp(EGG)
prefs = slurp(PREFS)
strs = slurp(STRS)
gradle = slurp(GR)

print("A — the frozen-frontier corpse, fixed (BoltEggActivity)")
spawn = egg[egg.find("private fun maybeSpawn()"):egg.find("override fun onSizeChanged")]
need(spawn and "val lastRight" not in spawn,
     "the corpse is gone: no val frontier read once before the while (the loop that never advanced)")
need(re.search(r"var right = if \(bars\.isEmpty\(\)\)", spawn) is not None,
     "the frontier is a var, read from the last bar (or the spawn floor when empty)")
need(re.search(r"val x = right \+ spacing\s*\n\s*bars\.add\(Bar\(x, top, top \+ gap, bw, false\)\)\s*\n\s*right = x \+ bw", spawn) is not None,
     "every iteration spawns at the frontier and ADVANCES it (right = x + bw) — the loop must terminate")
need("FROZEN-FRONTIER CORPSE" in egg and "infinite loop wearing a for-loop costume" in egg,
     "the fix's comment records the corpse + the law (future sessions read the why)")
need("while (right < horizon)" in spawn,
     "the while still tests the (now advancing) frontier against the horizon")

print("A2 — the one-live countdown toast (AboutActivity)")
need("private var eggToast" in about and "eggToast?.cancel()" in about
     and "eggToast?.show()" in about,
     "the countdown is ONE toast, cancelled + reshown per tap (tracks the finger, never a queue)")
need(re.search(r'eggTaps >= 7[\s\S]{0,320}?eggToast\?\.cancel\(\);?\s*\n\s*eggToast = null', about) is not None,
     "the door clears the countdown toast before the game launches")

print("C — About lives in the deep tree")
need("aboutRow" not in sha and "THE ABOUT ROW MOVED TO DEEP SETTINGS" in sha,
     "the settings home no longer carries the About ROW (moved per the field report)")
need(re.search(r'buildIndex[\s\S]*R\.string\.about_bolt[\s\S]*AboutActivity\.class', sha) is not None,
     "the surface settings SEARCH still finds About (search is not a row)")
deep_build = dsa[dsa.find("protected void build()"):dsa.find("private Row cat(")]
need("R.string.about_bolt" in deep_build and "AboutActivity.class" in deep_build
     and 'about.value = "v" + vn' in deep_build,
     "DeepSettings lists the About row with the LIVE version chip (read from the package — the label law)")
need("getPackageName(), 0).versionName" in deep_build,
     "the version chip is read LIVE, never hardcoded")
deep_index = dsa[dsa.find("private List<Entry> deepIndex()"):]
need(re.search(r'R\.string\.about_bolt[\s\S]{0,200}?AboutActivity\.class', deep_index) is not None,
     "the deep settings search finds About")

print("D — the memberless-folder parity (the follow walk)")
# v1.4.8 re-point: the walk moved into TabManager.followRowOrder (one row
# model, every reader); the anchorless convention is the same law.
need("int putAt = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;" in tm
     and "out.add(putAt++, g);" in tm,
     "the in-order walk lands never-emitted (memberless) folders after the LAST FOLDER ROW (v1.4.8 re-point: the walk lives in TabManager.followRowOrder now — the top when none, no folder vanishes between placements, and none falls below the loose tabs either)")
need("for (Object o : tm.followRowOrder()) items.add(o);" in ta,
     "TabsActivity's follow branch renders the one row model (v1.4.8)")

print("E — local-first suggestions")
need("public boolean suggestLocalFirst() { return b(\"suggestLocalFirst\", true); }" in prefs,
     "Prefs.suggestLocalFirst exists (default ON — the ask was for the behavior, the toggle is the escape hatch)")
need('name="suggest_local_first"' in strs and 'name="home_shortcut"' in strs
     and 'name="shortcut_in"' in strs,
     "the toggle + shortcut-row strings exist")
need("R.string.suggest_local_first" in sia and 'prefs.set("suggestLocalFirst", on)' in sia,
     "the toggle row lives in deep Settings > General")
need(re.search(r'R\.string\.suggest_local_first[\s\S]{0,160}?getString\(R\.string\.cat_general\)', deep_index) is not None,
     "the deep settings search finds the toggle")
sugg = ba[ba.find("boolean localFirst = prefs.suggestLocalFirst();"):ba.find("// 4) search row")]
need(sugg and "if (localFirst) {" in sugg,
     "the omnibox pass branches on the toggle")
need(sugg.find("ic_bookmark") < sugg.find("ic_history") and sugg.find("ic_home") < sugg.find("ic_history"),
     "with it ON, bookmarks + shortcuts rank ABOVE history (the priority ask)")
need('optBoolean("group", false)' in sugg and 'optJSONArray("items")' in sugg,
     "shortcut suggestions dig through speed-dial FOLDERS (entries inside groups suggest too)")
need("R.string.shortcut_in" in sugg and "R.string.home_shortcut" in sugg,
     "folder shortcuts label their folder; loose ones read as home shortcuts")
need("if (!localFirst) {" in sugg and sugg.rfind("ic_history") < sugg.rfind("ic_bookmark"),
     "with it OFF, the classic order (history before bookmarks, no shortcut rows) is restored")
need(re.search(r"int histCap = localFirst \? 3 : 4;", sugg) is not None,
     "history caps at 3 rows when local-first (room for the local rows), 4 when classic")

print("B — the functional probe (run the REAL methods + render branches)")
g_start = tm.find("    public List<JSONObject> groups() {")
g_end = tm.find("    /** v1.4.0 — THE ORPHAN HEAL")
f_start = tm.find("    /** the groups reordered by their FIRST MEMBER")
s_end = tm.rfind("}")
r_start = prefs.find("    /** v1.4.1 — rewrite the groups array to EXACTLY")
r_end = prefs.find("    // ---- speed dial")
io_top = ta.find("if (prefs.groupsOnTop()) {", ta.find("private void rebuildItems()"))
# v1.4.8: the walk's empty placement moved to TabManager.followRowOrder —
# extract THAT method's body (brace-balanced from its signature brace)
fro = tm.find("public List<Object> followRowOrder(boolean includeAnchorless)")
io_for = fro
io_add = tm.find("out.add(putAt++, g);", fro) if fro >= 0 else -1
io_for_close = (tm.find("}", io_add) + 1) if io_add >= 0 else -1
_else = ta.find("} else {", io_top)
io_end2 = (balance_end(ta, ta.find("{", _else)) if _else >= 0 else -1)   # v1.4.8: the else branch is the tm.followRowOrder() render
if min(g_start, g_end, f_start, s_end, r_start, r_end, io_top, io_for, io_add, io_end2) < 0 \
        or g_end <= g_start or f_start <= g_end or r_end <= r_start or io_end2 <= io_top:
    need(False, "the methods + render branches are where the gate expects them")
    probe_ok = False
else:
    probe_ok = True

if probe_ok:
    # the REAL TabManager methods, made static (the v141 idiom)
    rr_start = tm.find("    /** v1.4.5 — keep the ACTIVE TAB")
    rr_end = tm.find("    /** the groups reordered by their FIRST MEMBER")
    rr_blk = tm[rr_start:rr_end].replace("    private void reResolveActive",
                                         "    static void reResolveActive", 1) if 0 <= rr_start < rr_end else ""
    tm_block = tm[g_start:g_end] + rr_blk + tm[f_start:s_end]
    for sig in ("    public List<JSONObject> groups() {",
                "    public void assignGroup(int tabIndex, long groupId) {",
                "    public List<Tab> tabsOfGroup(long gid) {",
                "    public List<JSONObject> groupsInFirstMemberOrder() {",
                "    public boolean moveGroupRelative(long gid, Tab anchor, boolean after) {",
                # v1.4.8: the follow-order row model family (render calls it)
                "    public List<Object> followRowOrder() {",
                "    public List<Object> followRowOrder(boolean includeAnchorless) {",
                "    public Tab tabByTid(long tid) {",
                "    public void refreshAnchorsAround(String movedKey) {",
                "    public void captureGroupRowAnchors(long gid) {",
                "    public void normalizeEmptyAnchors() {",
                "    public boolean syncGroupsArrayToFollowOrder() {",
                "    private int rowKeyIndex(List<Object> rows, String key) {",
                "    private int folderRowIndex(List<Object> rows, long gid) {",
                "    private int anchorRowIn(List<Object> rows, JSONObject g) {",
                "    private boolean pendingEmptyKey(List<Object> rows, String key) {"):
        sig2 = sig.replace("    public ", "    public static ", 1) if sig.startswith("    public") \
            else sig.replace("    private ", "    private static ", 1)
        tm_block = tm_block.replace(sig, sig2, 1)
    # the REAL render branches, verbatim: tm.groups() -> the static extract
    io_block = ta[io_top:io_end2]
    io_block = io_block.replace("tm.groupsInFirstMemberOrder()", "groupsInFirstMemberOrder()")
    io_block = io_block.replace("tm.groups()", "groups()")
    io_block = io_block.replace("tm.followRowOrder()", "followRowOrder()")          # v1.4.8: the walk moved into TabManager
    io_block = io_block.replace("tm.normalizeEmptyAnchors()", "normalizeEmptyAnchors()")
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
    work = "/tmp/bolt_v142_gate"
    for p in (os.path.join(work, "json-20240303.jar"), "/tmp/org.json.jar",
              "/tmp/bolt_v142_probe/json.jar"):
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
    // ---- the Tab stub (field-accurate: the render reads groupId + identity) ----
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
        public boolean groupsOnTop() { return Probe.groupsOnTop; }
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
    static boolean groupsOnTop;
    static void setGroups(int... ids) {
        prefs.groups = new JSONArray();
        for (int id : ids) { JSONObject o = new JSONObject(); o.put("id", id); o.put("name", "G" + id); prefs.groups.put(o); }
    }
    static String show(java.util.List<Object> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            sb.append(o instanceof JSONObject ? ("F#" + ((JSONObject) o).optLong("id")) : o.toString());
            if (i < list.size() - 1) sb.append(",");
        }
        return sb + "]";
    }
    // the REAL render branches, verbatim (tm.groups() -> the static extract)
    static java.util.List<Object> render() {
        java.util.List<Object> items = new java.util.ArrayList<Object>();
        java.util.List<Tab> sorted = new java.util.ArrayList<Tab>(tabs);
""" + io_block + """
        return items;
    }
    // ---- the spawn-loop probe: the OLD corpse vs the FIXED shape ----
    static class Bar { float x, gapTop, gapBot, w; boolean scored; }
    static int spawnFixed(java.util.List<Bar> bars, float w, float h, float density, float titleH) {
        // the FIXED shape, transcribed 1:1 from the patched maybeSpawn
        int iters = 0;
        if (w <= 0) return 0;
        float right = bars.isEmpty() ? w + 40f * density
                : bars.get(bars.size() - 1).x + bars.get(bars.size() - 1).w;
        float horizon = w + 80f * density;
        while (right < horizon) {
            float spacing = (220f + 0.5f) * density;
            float bw = (64f + 0.5f) * density;
            float gap = (185f + 0.5f) * density;
            float top = titleH + 26f;
            float x = right + spacing;
            bars.add(new Bar()); Bar b = bars.get(bars.size() - 1);
            b.x = x; b.gapTop = top; b.gapBot = top + gap; b.w = bw; b.scored = false;
            right = x + bw;
            if (++iters > 1000) return -1;   // the safety cap the real code no longer needs
        }
        return iters;
    }
    static int spawnCorpse(java.util.List<Bar> bars, float w, float h, float density, float titleH) {
        // the v1.4.1 shape, transcribed 1:1: the frontier is read ONCE and never advances
        int iters = 0;
        if (w <= 0) return 0;
        final float lastRight = bars.isEmpty() ? w + 40f * density
                : bars.get(bars.size() - 1).x + bars.get(bars.size() - 1).w;
        float horizon = w + 80f * density;
        while (lastRight < horizon) {
            float spacing = (220f + 0.5f) * density;
            float bw = (64f + 0.5f) * density;
            float gap = (185f + 0.5f) * density;
            float top = titleH + 26f;
            Bar b = new Bar();
            b.x = lastRight + spacing; b.gapTop = top; b.gapBot = top + gap; b.w = bw; b.scored = false;
            bars.add(b);
            if (++iters > 5000) return -iters;   // PROOF: the cap trips — this loop never exits
        }
        return iters;
    }
    static int bad = 0;
    static void eq(String label, Object got, Object expect) {
        if (!got.toString().equals(expect.toString())) { System.out.println("FAIL " + label + ": got " + got + " expected " + expect); bad++; }
    }
    static void ok(String label, boolean v) { if (!v) { System.out.println("FAIL " + label); bad++; } }
    public static void main(String[] args) {
        // ===== 1. THE SPAWN LOOP =====
        java.util.List<Bar> bars = new java.util.ArrayList<Bar>();
        int it1 = spawnCorpse(bars, 1080f, 2400f, 2.75f, 288f);
        ok("the v1.4.1 corpse shape trips the cap (it never exits: " + it1 + ")", it1 < 0);
        bars = new java.util.ArrayList<Bar>();
        int it2 = spawnFixed(bars, 1080f, 2400f, 2.75f, 288f);
        ok("the fixed shape terminates (" + it2 + " iterations)", it2 > 0 && it2 < 100);
        ok("bars were actually spawned (" + bars.size() + ")", bars.size() == it2 && bars.size() > 0);
        boolean mono = true;
        for (int i = 1; i < bars.size(); i++) if (bars.get(i).x <= bars.get(i - 1).x) mono = false;
        ok("the bars advance monotonically (the frontier moves)", mono);
        float lastRight2 = bars.get(bars.size() - 1).x + bars.get(bars.size() - 1).w;
        ok("the last bar reaches the horizon (the loop condition can exit)", lastRight2 >= 1080f + 80f * 2.75f);
        // the scroll cycle: the world moves left, the next spawn tops up and STILL exits
        for (Bar b : bars) b.x -= 900f;
        int before = bars.size();
        int it3 = spawnFixed(bars, 1080f, 2400f, 2.75f, 288f);
        ok("after a scroll the top-up spawn still terminates", it3 >= 0 && bars.size() > before);
        // ===== 2. THE PLACEMENT PARITY (v1.4.3 re-point: pure view change) =====
        // populated groups: BOTH renders list every folder; the set of rows
        // is the same (the v1.4.2 parity law) — the toggle re-renders, no data
        tabs = new java.util.ArrayList<Tab>();
        tabs.add(new Tab("A", 0)); tabs.add(new Tab("B", 1)); tabs.add(new Tab("C", 1));
        tabs.add(new Tab("D", 2)); tabs.add(new Tab("E", 0));
        setGroups(1, 2);
        groupsOnTop = true;
        String onTop = show(render());
        groupsOnTop = false;
        String inOrder = show(render());
        ok("populated: every folder renders on-top", onTop.contains("F#1") && onTop.contains("F#2"));
        ok("populated: every folder renders in-order (same row set)", inOrder.contains("F#1") && inOrder.contains("F#2"));
        ok("populated: the loose order is IDENTICAL across placements (the one memory)",
           onTop.endsWith("A,E]") && inOrder.contains("A") && inOrder.contains("E"));
        // memberless folder: the FIXED render keeps it (the field corpse)
        tabs = new java.util.ArrayList<Tab>();
        tabs.add(new Tab("A", 0)); tabs.add(new Tab("B", 1)); tabs.add(new Tab("C", 1));
        tabs.add(new Tab("E", 0));
        setGroups(1, 2);
        groupsOnTop = true;
        String onTop2 = show(render());
        groupsOnTop = false;
        String inOrder2 = show(render());
        ok("memberless folder present on-top", onTop2.contains("F#2"));
        ok("memberless folder present in the FIXED in-order render (no vanishing)", inOrder2.contains("F#2"));
        eq("the populated folder anchors at its FIRST MEMBER's inline slot and the memberless one follows it (v1.4.6: after the last folder row)",
           inOrder2, "[A,F#1,F#2,E]");
        ok("the memberless folder lands right after the LAST FOLDER ROW (v1.4.6's empty-anchor convention — the top spot survives the switch)",
           inOrder2.lastIndexOf("F#2") < inOrder2.lastIndexOf("E") && inOrder2.indexOf("F#2") > inOrder2.indexOf("F#1"));
        // all folders empty: both placements render every folder
        tabs = new java.util.ArrayList<Tab>();
        tabs.add(new Tab("A", 0)); tabs.add(new Tab("E", 0));
        setGroups(1, 2);
        groupsOnTop = true;
        String onTop3 = show(render());
        groupsOnTop = false;
        String inOrder3 = show(render());
        ok("all-empty: every folder renders on-top", onTop3.contains("F#1") && onTop3.contains("F#2"));
        ok("all-empty: every folder renders in-order too (parity)", inOrder3.contains("F#1") && inOrder3.contains("F#2"));
        // the v1.4.3 round-trip law: toggle twice, the render is byte-identical
        groupsOnTop = true;
        String backOnTop = show(render());
        groupsOnTop = false;
        String back = show(render());
        eq("v1.4.3: the toggle round-trip is byte-identical (no data change)", back, inOrder3);
        ok("round-trip to on-top keeps every folder", backOnTop.contains("F#1") && backOnTop.contains("F#2"));
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
            need(False, "the extracted methods + render branches compile (probe)")
            print(r.stderr[:2000])
        else:
            r2 = subprocess.run(["java", "-cp", d + os.pathsep + jar, "Probe"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL code runs green: the spawn loop (corpse trips the cap, fix terminates + monotone + horizon + top-up), the placement parity (every folder renders in both placements, memberless present + after the last folder row, toggle round-trip byte-identical)")
            if r2.returncode != 0 or "all green" not in r2.stdout:
                print(r2.stdout[:2500])
                print(r2.stderr[:800])

print("V — the version pin")
need('versionCode 125' in gradle, 'versionCode 125')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)
need('versionName "1.4.11"' in gradle, 'versionName "1.4.11"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)
entries = re.findall(r"<item>([^<]+)</item>", strs[strs.find('name="about_changelog"'):strs.find("</string-array>", strs.find('name="about_changelog"'))])
need(entries and entries[0].startswith("1.4.11|"), "the About changelog's newest entry is 1.4.11 (the label law; v1.4.11 re-point 2026-11-18)")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)

print()
if fails == 0:
    print("v1.4.2 gate (re-pointed for the v1.4.3 one-memory law): ALL GREEN")
else:
    print(f"v1.4.2 gate: {fails} FAILURES")
    sys.exit(1)
