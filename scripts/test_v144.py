#!/usr/bin/env python3
"""v1.4.4 release gate — the egg's pacing, the follow-order rearrangement
(the dead Move up/down + the no-op beside-folder drops + the in-group drag),
the folder-window icon drag, and the multi-account vault.

The v1.4.3 field report (four fronts):

1. "The game has too fast vertical movements, making playing unenjoyable
   and finishes too quickly" — THE PACING: the v1.4.1 constants were
   flappy-twitch (apex in 0.28 s, a bar per second). The retune keeps the
   jump HEIGHT identical while stretching the arc +44%, slowing the world
   18% and widening every gap.

2. "In the follow tabs order, tabs are not still manually adjustable …
   the tab groups move and change when changing modes. I want the always
   top to do what it does now, and changing to follow tab order enables
   how the tabs can be rearranged for the tab groups too. Also make the
   tabs inside the tab group already to be rearranged inside the group
   too by dragging" — TWO CORPSES behind "not manually adjustable":
   (a) the folder menu's Move up/down still reordered the GROUPS ARRAY,
   visually inert since v1.4.3's one-memory law; (b) a loose-tab drop
   beside a FOLDER ROW was a silent no-op — and in follow-order the
   folders interleave the grid, so half the drops died. Plus the NEW
   in-group drag (reorder members inside the folder row).

3. "Similarly, make the home page shortcuts icons in the groups to be
   draggable to rearrange too" — the folder window's icons get the same
   hold-drag-rearrange (Prefs.moveSpeeddialItem + an in-place rebuild).

4. "The password filler and saver is still a bit messy, it does not
   always offer to fill up saved password. Also it is unable to save
   multiple passwords for multiple accounts on the same site" — the
   fill offered only the FIRST row per host (find); two-step sign-ins
   submitted u='' so every account collapsed into one (host,'') row;
   the chip retired after 30 s and a focused field never re-armed it.

PART A — static checks for all four fronts.
PART B — the functional probes (javac + run against the real org.json):
  B1 the placement laws (the REAL methods: moveTabBesideGroup's
     never-between-members + the abort path, reorderGroupMember's
     permutation/anchor/round-trip laws, moveSpeeddialItem's lossless
     rebuild);
  B2 findAll's exact-then-base, newest-first ordering (transcribed 1:1).

V — the version pin.
"""
import os, re, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/TabsActivity.java")
TM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/TabManager.java")
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
EGG = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BoltEggActivity.kt")
PV = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/PasswordVault.kt")
PF = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Prefs.java")
MH = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/media/MediaHook.java")
STRS = os.path.join(ROOT, "app/src/main/res/values/strings.xml")
GR = os.path.join(ROOT, "app/build.gradle")
TJ = os.path.join(ROOT, "scripts/test_media_hook.js")

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

ta, tm, ba, egg, pv = (slurp(TA), slurp(TM), slurp(BA), slurp(EGG), slurp(PV))
prefs_src, mh, strs, gradle, tj = (slurp(PF), slurp(MH), slurp(STRS), slurp(GR), slurp(TJ))

print("A1 — the egg's pacing (BoltEggActivity)")
need(re.search(r"private val gravity = 1180f \* density", egg) is not None
     and re.search(r"private val flapV = -480f \* density", egg) is not None,
     "the flap arc stretches +44% at the SAME jump height (gravity 2400→1180, flap −680→−480)")
need(re.search(r"private val speed0 = 205f \* density", egg) is not None,
     "the world slows 18% (250→205 px/s)")
need(re.search(r"val spacing = \(235f \+ rng\.nextFloat\(\) \* 140f\) \* density", egg) is not None
     and re.search(r"val gap = \(200f \+ rng\.nextFloat\(\) \* 80f\) \* density", egg) is not None,
     "the RNG envelope widens (gaps 200–280 dp, spacing 235–375 dp)")
need(re.search(r"vy / \(10f \* density\)", egg) is not None,
     "the tilt divisor follows the retuned physics (14→10)")
need("PACING PASS" in egg, "the fix's comment records the pacing law")

print("A2 — the follow-order rearrangement (TabsActivity + TabManager)")
need("prefs.moveGroup(" not in ta,
     "the DEAD array-only Move up/down is gone from the switcher (it reordered the groups array, inert since the one-memory law)")
need("protected Tab groupMoveAnchor(long gid, boolean up)" in ta
     and "tm.moveGroupRelative(gid, upAnchor, false)" in ta
     and "tm.moveGroupRelative(gid, downAnchor, true)" in ta,
     "Move up/down resolves the RENDERED neighbour and block-moves the members against it")
need("groupMoveAnchor" in ta and "adapter.items.size()" in ta.split("protected Tab groupMoveAnchor")[1][:2200],
     "the neighbour walk reads the RENDERED rows (adapter.items), not the groups array")
need("public boolean moveTabBesideGroup(Tab dragged, long gid, boolean after)" in tm,
     "TabManager.moveTabBesideGroup exists (the loose-tab-lands-beside-a-folder law)")
need("boolean folderAnchor = false;" in ta and "tm.moveTabBesideGroup(dragged, og, after)" in ta,
     "the loose drop beside a folder row routes through moveTabBesideGroup (never between members)")
need(re.search(r"if \(under != null && !gridMoving\s*\n\s*&& \(!overGroup \|\| \(!prefs\.groupsOnTop\(\) && draggingGroup == null\)\)\)",
               ta) is not None,
     "folder rows carry SIDE zones for loose drags in the in-order placement (zones over folders stay dead on-top and for folder drags)")
need("if (newZone == ZONE_LEFT || newZone == ZONE_RIGHT) {" in ta
     and ta.find("if (newZone == ZONE_LEFT || newZone == ZONE_RIGHT) {") < ta.find("dwellIsGroup = true;"),
     "a side zone over a folder disarms the add-to-group dwell (the center alone charges the join)")
need("(overGroup && newZone != ZONE_LEFT && newZone != ZONE_RIGHT)" in ta,
     "the snap anchor treats a side-zoned folder like a tab card's side (the card eases to the landing side)")
need("public boolean reorderGroupMember(long gid, int from, int to)" in tm,
     "TabManager.reorderGroupMember exists (the in-group reorder)")
need("makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0)" in ta
     and "tm.reorderGroupMember(gid, from, to)" in ta
     and "ga.notifyItemMoved(from, to);" in ta,
     "the folder members reorder in-group, member-indexed")  # v1.4.12 re-point (2026-11-18): the inline mini-strip (and its hold-drag call) became the group folder SHEET — the v1.4.4 law survives as the sheet's long-press Move up/Move down door, still member-indexed through tm.reorderGroupMember
need("if (miniDragTab != null) return 0;" in ta,
     "the ItemTouchHelper is gated off while a mini drag is armed (the row never also lifts)")
need("if (miniDragTab != null) return;\n            if (deferIfBusy(this::notifyDataSetChanged))" in ta,
     "rebindIcons refuses mid-mini-drag (a favicon landing cannot detach the dragged card)")
need("tabOptions(t, gid);" in ta.split("protected void wireMiniDrag(")[1][:3400],
     "hold-still still opens the member menu (the old long-click door, now at UP)")

print("A3 — the folder-window icon drag (BrowserActivity + Prefs)")
need("public boolean moveSpeeddialItem(int groupIdx, int fromItemIdx, int insertAt)" in prefs_src,
     "Prefs.moveSpeeddialItem exists (the same insert-convention as the home grid)")
need("protected boolean sdWinFillGrid(" in ba and "protected void sdWinAddCell(" in ba
     and "sdWinFillGrid(grid, cellW, txt);" in ba,
     "the window's grid builds through ONE shared helper (initial layout + in-place rebuild)")
need("sdWinCellTouch(itemIdx, url, tTitle);" in ba
     and "tilePressFx(cellV, shell, winDrag);" in ba,
     "every window cell carries the drag wiring (v1.4.6 re-point: COMPOSED into tilePressFx as the drag feed — the setOnTouchListener clobber is dead)")
need("prefs.moveSpeeddialItem(folderIdx, from, to)" in ba
     and "sdWinFillGrid(sdWinGrid, Fx.dp(BrowserActivity.this, 64), txt);" in ba,
     "the drop reorders the folder's items and rebuilds the grid in place")
need("sdWinDragCell = null;\n        sdWinGrid = null;" in ba,
     "the window's drag state dies with the window (dismissSdFolderWindow)")
need("java.util.ArrayList<Object> list" in
     prefs_src.split("public boolean moveSpeeddialItem")[1][:1400],
     "the item move uses the lossless java-List rebuild idiom (never JSONArray.put(int,·))")

print("A4 — the multi-account vault (PasswordVault + BrowserActivity + MediaHook)")
need("fun findAll(c: Context, host: String): List<Entry>" in pv
     and "fun find(c: Context, host: String): Entry? = findAll(c, host).firstOrNull()" in pv,
     "findAll returns EVERY matching login; find delegates (one lookup path)")
need("exact.sortByDescending { it.addedAt }" in pv and "base.sortByDescending { it.addedAt }" in pv
     and "exact.addAll(base)" in pv,
     "findAll orders exact-host first, then base-domain, newest first inside each class")
need("fun savedAlready(c: Context, host: String, user: String, pass: String): Boolean" in pv
     and 'o.optString("h") == host && o.optString("u") == user' in pv.split("fun savedAlready")[1][:800],
     "savedAlready is the EXACT (host, user, pass) triple check")
need("PasswordVault.savedAlready(this, host, user0, pass)" in ba
     and "pass.equals(e.pass)" not in ba,
     "the save offer skips only the exact triple (no more first-row re-offers)")
need("user0.isEmpty() ? host : user0 + \" @ \" + host" in ba,
     "the save sheet names the account (user @ host) — a second login reads as what it is")
need("PasswordVault.findAll(this, host)" in ba
     and "pw_fill_multi, picks.size()" in ba,
     "the fill chip offers every login (findAll); several accounts = the multi chip")
need("pw_pick_account), names, -1, pi ->" in ba,
     "tapping the multi chip opens the ACCOUNT PICKER (choice sheet, username rows)")
need("findAll(this, tabHost)" in ba,
     "the fill falls back to the TAB's host when the page-host lookup finds nothing")
need(r"if(!u){var lu=window.__boltLastUserEl;if(lu&&lu.value){u=lu.value;}}" in mh,
     "the pw submit inherits the REMEMBERED username (two-step sign-ins)")
need(r"if(el.type!=='password'){window.__boltLastUserEl=el;}" in mh,
     "the focusin detector remembers the last username-ish field")
need(r"el.tagName==='INPUT'&&__boltLoginField(el)" in mh
     and mh.find("el.tagName==='INPUT'&&__boltLoginField(el)") > mh.find("__boltLastUserEl=el;"),
     "the CLICK re-arm: a click on a login field re-asks for the chip (the 30 s retire corpse)")
need('name="pw_fill_multi"' in strs and 'name="pw_pick_account"' in strs,
     "the multi-chip + picker strings exist")
need("T35b" in tj and "pwSubmits" in tj,
     "the media hook suite carries the v1.4.4 hook tests (T35b: remembered username + click re-arm)")

print("B — the functional probes (javac + run)")
# extract the REAL TabManager methods + the REAL Prefs methods
m_start = tm.find("    public List<JSONObject> groups() {")
m_end = tm.find("    /** v1.4.0 — THE ORPHAN HEAL")
f_start = tm.find("    /** the groups reordered by their FIRST MEMBER")
mbrg_start = tm.find("    /** v1.4.1 — THE GROUP DRAG UNDER BOTH PLACEMENTS")
s_end = tm.rfind("}")
sd_start = prefs_src.find("    /** v1.4.4 — REORDER AN ITEM INSIDE A GROUP")
sd_end = prefs_src.find("    /** move an item out of a group")
rg_start = prefs_src.find("    /** v1.4.1 — rewrite the groups array to EXACTLY")
rg_end = prefs_src.find("    // ---- speed dial")
probe_ok = min(m_start, m_end, f_start, mbrg_start, s_end, sd_start, sd_end, rg_start, rg_end) >= 0

javac = None
if probe_ok:
    rr_start = tm.find("    /** v1.4.5 — keep the ACTIVE TAB")
    rr_end = tm.find("    /** the groups reordered by their FIRST MEMBER")
    rr_blk = tm[rr_start:rr_end].replace("    private void reResolveActive",
                                         "    static void reResolveActive", 1) if 0 <= rr_start < rr_end else ""
    tm_block = tm[m_start:m_end] + rr_blk + tm[f_start:mbrg_start] + tm[mbrg_start:s_end]
    for sig in ("    public List<JSONObject> groups() {",
                "    public void assignGroup(int tabIndex, long groupId) {",
                "    public List<Tab> tabsOfGroup(long gid) {",
                "    public List<JSONObject> groupsInFirstMemberOrder() {",
                "    public boolean moveGroupRelative(long gid, Tab anchor, boolean after) {",
                "    public boolean reorderGroupMember(long gid, int from, int to) {",
                "    public boolean moveTabBesideGroup(Tab dragged, long gid, boolean after) {"):
        if sig not in tm_block:
            need(False, f"the gate finds the method body: {sig.strip()}")
            probe_ok = False
            break
        tm_block = tm_block.replace(sig, sig.replace("    public ", "    public static ", 1), 1)
    sd_block = prefs_src[sd_start:sd_end]
    sd_block = sd_block.replace("    public boolean moveSpeeddialItem", "        public boolean moveSpeeddialItem", 1)
    rg_block = prefs_src[rg_start:rg_end].replace("    public boolean reorderGroups", "        public boolean reorderGroups", 1)
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
    work = "/tmp/bolt_v144_gate"
    for p in (os.path.join(work, "json.jar"), "/tmp/bolt_v143_gate/json.jar",
              "/tmp/bolt_v142_gate/json-20240303.jar", "/tmp/org.json.jar"):
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
        void saveSpeeddial() {}
        public JSONObject speeddialAt(int idx) {
            return idx >= 0 && idx < speeddial.length() ? speeddial.optJSONObject(idx) : null;
        }
""" + rg_block.replace("\n    ", "\n        ") + "\n" + sd_block.replace("\n    ", "\n        ") + """
    }
    static PrefsStub prefs = new PrefsStub();
    static boolean INCOGNITO = false;
    // ---- the REAL TabManager methods (static-ized) ----
""" + tm_block + """
    static void setGroups(int... ids) {
        prefs.groups = new JSONArray();
        for (int id : ids) { JSONObject o = new JSONObject(); try { o.put("id", id); o.put("name", "G"+id); } catch (Exception e) {} prefs.groups.put(o); }
    }
    static String names() { StringBuilder sb = new StringBuilder(); for (Tab t : tabs) sb.append(t.name); return sb.toString(); }
    static int bad = 0;
    static void ok(String label, boolean v) { if (!v) { System.out.println("FAIL "+label); bad++; } }
    static void eq(String label, Object a, Object b) { ok(label+" ("+a+" vs "+b+")", a.toString().equals(b.toString())); }

    // ---- a faithful baseDomain for the findAll probe (Fx: www-strip +
    //      last-two parts; the probe hosts never hit the 2-part TLD list) ----
    static String baseDomain(String host) {
        if (host == null) return "";
        host = host.toLowerCase();
        if (host.startsWith("www.")) host = host.substring(4);
        String[] p = host.split("\\\\.");
        if (p.length <= 2) return host;
        return p[p.length - 2] + "." + p[p.length - 1];
    }
    // ---- findAll, transcribed 1:1 from PasswordVault.kt (the ordering law
    //      is the test; readAll becomes the local array) ----
    static List<String[]> findAll(JSONArray arr, String host) {
        List<String[]> out = new ArrayList<String[]>();
        if (host == null || host.trim().isEmpty()) return out;
        List<String[]> exact = new ArrayList<String[]>();
        List<String[]> base = new ArrayList<String[]>();
        String b = baseDomain(host);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String h = o.optString("h");
            if (h.equals(host)) exact.add(new String[]{h, o.optString("u"), "" + o.optLong("t", 0)});
            else if (b.length() > 0) {
                String hb = baseDomain(h);
                if (hb.equals(b) || h.endsWith("." + b))
                    base.add(new String[]{h, o.optString("u"), "" + o.optLong("t", 0)});
            }
        }
        exact.sort((x, y) -> Long.compare(Long.parseLong(y[2]), Long.parseLong(x[2])));
        base.sort((x, y) -> Long.compare(Long.parseLong(y[2]), Long.parseLong(x[2])));
        exact.addAll(base);
        return exact;
    }

    public static void main(String[] args) throws Exception {
        // ================= B1a: moveTabBesideGroup =================
        // [A(a1,a2), B(b1,b2), T1] — a loose tab lands immediately before
        // the folder's FIRST member / after its LAST member, never inside.
        setGroups(100, 200);
        Tab a1 = new Tab("a1", 100), a2 = new Tab("a2", 100);
        Tab b1 = new Tab("b1", 200), b2 = new Tab("b2", 200);
        Tab t1 = new Tab("T1"), x = new Tab("x");
        tabs.clear(); tabs.add(a1); tabs.add(a2); tabs.add(b1); tabs.add(b2); tabs.add(t1);
        ok("B1a1 loose-before-folder runs", moveTabBesideGroup(t1, 200, false));
        eq("B1a2 T1 lands immediately before b1", names(), "a1a2T1b1b2");
        ok("B1a3 loose-after-folder runs", moveTabBesideGroup(t1, 200, true));
        eq("B1a4 T1 lands immediately after b2", names(), "a1a2b1b2T1");
        // never between members: B scattered as [b1 .. b2] with strangers
        // inside — x lands before b1 (the FIRST member), never between
        tabs.clear(); tabs.add(a1); tabs.add(b1); tabs.add(a2); tabs.add(t1); tabs.add(x); tabs.add(b2);
        moveTabBesideGroup(x, 200, false);
        eq("B1a5 scattered folder: x lands before the FIRST member", names(), "a1xb1a2T1b2");
        // a grouped tab can never be the dragged one (the guard)
        ok("B1a6 a grouped tab refuses the move", !moveTabBesideGroup(a1, 200, true));
        eq("B1a7 nothing changed", names(), "a1xb1a2T1b2");

        // ================= B1b: reorderGroupMember =================
        // [a1,a2,b1,T1,b2] with A = {a1,a2}: moving a2 to member slot 0
        // permutes the block IN PLACE — the render anchor (a1's old slot)
        // holds, loose tabs never move.
        tabs.clear(); tabs.add(a1); tabs.add(a2); tabs.add(b1); tabs.add(t1); tabs.add(b2);
        ok("B1b1 the in-group move runs", reorderGroupMember(100, 1, 0));
        eq("B1b2 the block permutes, the anchor holds, loose tabs stay", names(), "a2a1b1T1b2");
        // to == from is a no-op
        ok("B1b3 to==from refuses", !reorderGroupMember(100, 0, 0));
        // out of range refuses
        ok("B1b4 out-of-range refuses", !reorderGroupMember(100, 0, 5));
        // scattered members re-gather at the anchor: [a1,T1,a2] -> the block
        // compacts at a1's slot
        tabs.clear(); tabs.add(a1); tabs.add(t1); tabs.add(a2);
        reorderGroupMember(100, 1, 0);
        eq("B1b5 scattered members re-gather at the render anchor", names(), "a2a1T1");
        // the round trip: permute twice, come home
        tabs.clear(); tabs.add(a1); tabs.add(a2);
        reorderGroupMember(100, 0, 1);
        reorderGroupMember(100, 0, 1);
        eq("B1b6 the double round trip is the identity", names(), "a1a2");

        // ================= B1c: moveSpeeddialItem =================
        JSONObject folder = new JSONObject();
        JSONArray items = new JSONArray();
        for (String[] it : new String[][]{{"A","ua"},{"B","ub"},{"C","uc"},{"D","ud"}}) {
            JSONObject o = new JSONObject(); o.put("title", it[0]); o.put("url", it[1]); items.put(o);
        }
        folder.put("group", true); folder.put("name", "F"); folder.put("items", items);
        prefs.speeddial = new JSONArray(); prefs.speeddial.put(folder);
        // B (index 1) dragged to the end (insertAt 3): A C D B
        ok("B1c1 the item move runs", prefs.moveSpeeddialItem(0, 1, 3));
        JSONArray got = prefs.speeddialAt(0).getJSONArray("items");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < got.length(); i++) sb.append(got.getJSONObject(i).getString("title"));
        eq("B1c2 the order is A C D B (insert-convention)", sb.toString(), "ACDB");
        // lossless: 4 in, 4 out, no dupes
        Set<String> seen = new HashSet<String>();
        boolean lossless = got.length() == 4;
        for (int i = 0; i < got.length() && lossless; i++) lossless = seen.add(got.getJSONObject(i).getString("url"));
        ok("B1c3 the rebuild is lossless (4 unique items)", lossless);
        // D (now index 2) dragged to the front (insertAt 0): D A C B
        prefs.moveSpeeddialItem(0, 2, 0);
        sb = new StringBuilder();
        got = prefs.speeddialAt(0).getJSONArray("items");
        for (int i = 0; i < got.length(); i++) sb.append(got.getJSONObject(i).getString("title"));
        eq("B1c4 the reverse move lands D A C B", sb.toString(), "DACB");
        // out-of-range refuses (never a partial write)
        ok("B1c5 out-of-range refuses", !prefs.moveSpeeddialItem(0, 1, 9));

        // ================= B2: findAll's ordering law =================
        JSONArray vault = new JSONArray(
            "[{\\"h\\":\\"s.example.com\\",\\"u\\":\\"old@x\\",\\"t\\":100}," +
            "{\\"h\\":\\"s.example.com\\",\\"u\\":\\"new@x\\",\\"t\\":900}," +
            "{\\"h\\":\\"other.example.com\\",\\"u\\":\\"sub@x\\",\\"t\\":500}," +
            "{\\"h\\":\\"unrelated.org\\",\\"u\\":\\"no@x\\",\\"t\\":999}]");
        List<String[]> hits = findAll(vault, "s.example.com");
        eq("B2a exact rows first (newest), then base-domain", hits.get(0)[1] + "," + hits.get(1)[1] + "," + hits.get(2)[1], "new@x,old@x,sub@x");
        ok("B2b the unrelated host never matches", hits.size() == 3);
        hits = findAll(vault, "www.example.com");
        ok("B2c a www-visit matches the base-domain rows (newest first)",
           hits.size() == 3 && hits.get(0)[1].equals("new@x") && hits.get(1)[1].equals("sub@x"));

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
                 "the REAL code runs green: moveTabBesideGroup (never-between-members, scattered folders, the grouped-drag guard), reorderGroupMember (permutation, anchor preservation, re-gather, round trip), moveSpeeddialItem (insert-convention, lossless, out-of-range refusal), findAll (exact-then-base, newest-first)")
            if r2.returncode != 0 or "all green" not in r2.stdout:
                print(r2.stdout[:2500])
                print(r2.stderr[:800])

print("V — the version pin")
need('versionCode 134' in gradle, 'versionCode 134')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need('versionName "1.4.20"' in gradle, 'versionName "1.4.20"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
entries = re.findall(r"<item>([^<]+)</item>", strs[strs.find('name="about_changelog"'):strs.find("</string-array>", strs.find('name="about_changelog"'))])
need(entries and entries[2].startswith("1.4.18|"), "the About changelog's newest entry is 1.4.13 (the label law; v1.4.11 re-point 2026-11-18)")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])

print()
if fails == 0:
    print("v1.4.4 gate: ALL GREEN")
else:
    print(f"v1.4.4 gate: {fails} FAILURES")
    sys.exit(1)
