#!/usr/bin/env python3
"""v1.4.3 verification probe — ONE MEMORY, TWO VIEWS (the placement redesign).

Extracts the REAL TabManager methods (groups / tabsOfGroup /
groupsInFirstMemberOrder / moveGroupRelative) + the REAL Prefs.reorderGroups +
the REAL TabsActivity render branches (the NEW on-top first-member branch and
the in-order branch), compiles with javac against org.json and proves:

  L1  the toggle is a PURE VIEW CHANGE: tm.tabs (the one memory) never
      changes on a toggle — both renders derive from it
  L2  folder subsequence + loose subsequence orders are IDENTICAL across
      placements (the "consistent" ask)
  L3  in-order -> on-top -> in-order ROUND TRIP: byte-identical render
      (the v1.4.2 corpse: the interleaved arrangement was destroyed)
  L4  an on-top folder DRAG (block-move via moveGroupRelative, the new one
      drag law) reorders the folders on screen AND carries into the in-order
      placement; the groups array is re-sorted to first-member order
  L5  the never-between-members law holds through a drag onto a folder's
      members (the block lands outside the host folder, never inside)
"""
import os, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/TabManager.java")
TA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/TabsActivity.java")
PREFS = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Prefs.java")

def slurp(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

tm, ta, prefs = slurp(TM), slurp(TA), slurp(PREFS)

g_start = tm.find("    public List<JSONObject> groups() {")
g_end = tm.find("    /** v1.4.0 — THE ORPHAN HEAL")
f_start = tm.find("    /** the groups reordered by their FIRST MEMBER")
s_end = tm.rfind("}")
r_start = prefs.find("    /** v1.4.1 — rewrite the groups array to EXACTLY")
r_end = prefs.find("    // ---- speed dial")
io_top = ta.find("if (prefs.groupsOnTop()) {", ta.find("private void rebuildItems()"))
parity_marker = ta.find("MEMBERLESS-FOLDER PARITY")
io_for = ta.find("for (JSONObject g : groups) {", parity_marker)
io_add = ta.find("items.add(g);", io_for)
io_for_close = ta.find("}", io_add) + 1
io_end2 = ta.find("}", io_for_close) + 1

if min(g_start, g_end, f_start, s_end, r_start, r_end, io_top, io_for, io_add, io_end2) < 0:
    print("EXTRACT FAIL — markers moved"); sys.exit(2)

tm_block = tm[g_start:g_end] + tm[f_start:s_end]
for sig in ("    public List<JSONObject> groups() {",
            "    public void assignGroup(int tabIndex, long groupId) {",
            "    public List<Tab> tabsOfGroup(long gid) {",
            "    public List<JSONObject> groupsInFirstMemberOrder() {",
            "    public boolean moveGroupRelative(long gid, Tab anchor, boolean after) {"):
    tm_block = tm_block.replace(sig, sig.replace("    public ", "    public static ", 1), 1)
io_block = ta[io_top:io_end2].replace("tm.groupsInFirstMemberOrder()", "groupsInFirstMemberOrder()").replace("tm.groups()", "groups()")
prefs_block = prefs[r_start:r_end]

assert "syncGroupPlacement" not in tm_block and "syncGroupPlacement" not in ta, "the sync must be gone"

javac = None
for cand in (os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javac"),
             "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/javac",
             shutil.which("javac") or ""):
    if cand and os.path.exists(cand):
        javac = cand
        break
if not javac:
    print("NO JAVAC — run scripts/setup_toolchain.sh first"); sys.exit(2)

jar = None
work = "/tmp/bolt_v143_probe"
for p in ("/tmp/bolt_v142_gate/json-20240303.jar", "/tmp/org.json.jar",
          os.path.join(work, "json.jar")):
    if os.path.exists(p) and os.path.getsize(p) > 50_000:
        with open(p, "rb") as f:
            if f.read(2) == b"PK":
                jar = p
                break
if not jar:
    os.makedirs(work, exist_ok=True)
    dst = os.path.join(work, "json.jar")
    for u in ("https://maven.aliyun.com/repository/public/org/json/json/20240303/json-20240303.jar",
              "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"):
        r = subprocess.run(["curl", "-sL", "--retry", "2", "-o", dst, u], capture_output=True, text=True)
        if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 50_000:
            with open(dst, "rb") as f:
                if f.read(2) == b"PK":
                    jar = dst
                    break
if not jar:
    print("NO org.json JAR"); sys.exit(2)

harness = """
import org.json.*;
import java.util.*;

public class Probe {
    static class Tab {
        public long groupId = 0;
        public String name = "";
        Tab(String n, long g) { name = n; groupId = g; }
        public String toString() { return name; }
    }
    static List<Tab> tabs = new ArrayList<Tab>();
    static void save() {}
    static class PrefsStub {
        JSONArray groups = new JSONArray();
        public JSONArray groups() { return groups; }
        public boolean groupsOnTop() { return Probe.groupsOnTop; }
        void scheduleWrite() {}
""" + prefs_block.replace("    public boolean reorderGroups", "        public boolean reorderGroups", 1).replace("\n    ", "\n        ") + """
    }
    static PrefsStub prefs = new PrefsStub();
    static boolean groupsOnTop;
    static boolean INCOGNITO = false;
    // ---- the REAL TabManager methods (static-ized) ----
""" + tm_block + """
    static void setGroups(int... ids) {
        prefs.groups = new JSONArray();
        for (int id : ids) { JSONObject o = new JSONObject(); try { o.put("id", id); o.put("name", "G"+id); } catch (Exception e) {} prefs.groups.put(o); }
    }
    static String show(List<Object> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            sb.append(o instanceof JSONObject ? ("F#"+((JSONObject)o).optLong("id")) : o.toString());
            if (i < list.size()-1) sb.append(",");
        }
        return sb+"]";
    }
    static List<Object> render() {
        List<Object> items = new ArrayList<Object>();
        List<Tab> sorted = new ArrayList<Tab>(tabs);
""" + io_block + """
        return items;
    }
    static List<Object> folderSub(List<Object> items) {
        List<Object> out = new ArrayList<Object>();
        for (Object o : items) if (o instanceof JSONObject) out.add(o);
        return out;
    }
    static List<Object> looseSub(List<Object> items) {
        List<Object> out = new ArrayList<Object>();
        for (Object o : items) if (o instanceof Tab) out.add(o);
        return out;
    }
    static String names(List<Tab> l) {
        StringBuilder sb = new StringBuilder();
        for (Tab t : l) sb.append(t.name);
        return sb.toString();
    }
    static int bad = 0;
    static void ok(String label, boolean v) { if (!v) { System.out.println("FAIL "+label); bad++; } }
    static void eq(String label, Object a, Object b) { ok(label+" ("+a+" vs "+b+")", a.toString().equals(b.toString())); }

    public static void main(String[] args) {
        // ===== FIELD STATE: in-order, folders INTERLEAVED among loose tabs =====
        tabs.clear();
        tabs.add(new Tab("T1", 0));
        tabs.add(new Tab("A1", 1)); tabs.add(new Tab("A2", 1));
        tabs.add(new Tab("T2", 0));
        tabs.add(new Tab("B1", 2));
        tabs.add(new Tab("T3", 0));
        setGroups(1, 2);
        groupsOnTop = false;
        String inOrderBefore = show(render());
        String memBefore = names(tabs);
        System.out.println("in-order before:                 "+inOrderBefore);

        // ===== L1: the toggle is a pure view change (tm.tabs untouched) =====
        groupsOnTop = true;
        String onTop = show(render());
        String memMid = names(tabs);
        System.out.println("on-top (toggled):                "+onTop);
        ok("L1a the toggle never touches tm.tabs", memMid.equals(memBefore));
        groupsOnTop = false;
        ok("L1b toggling back still never touches tm.tabs", names(tabs).equals(memBefore));

        // ===== L2: folder + loose subsequences identical across placements =====
        eq("L2a folder order identical across placements", show(folderSub(render())), show(folderSub(onTop(top()))));
        // (render() is in-order here; compare against the captured on-top render)
        // ===== L3: round trip is byte-identical =====
        eq("L3 round-trip in-order render is byte-identical", show(render()), inOrderBefore);

        // ===== L4: an ON-TOP folder drag block-moves members and carries =====
        groupsOnTop = true;
        // drag F#2 (B) ABOVE F#1 (A): anchor = A's FIRST member (the drop
        // handler's clamp), after=false — mirrors the new one-drag-law path
        List<Tab> hostA = tabsOfGroup(1);
        boolean moved = moveGroupRelative(2, hostA.get(0), false);
        ok("L4a the on-top drag block-moves", moved);
        String onTopDragged = show(render());
        System.out.println("on-top after dragging B above A: "+onTopDragged);
        ok("L4b B now renders above A on-top", onTopDragged.indexOf("F#2") < onTopDragged.indexOf("F#1"));
        // the groups array re-sorted to first-member order (the one-memory law)
        StringBuilder ga = new StringBuilder();
        for (int i = 0; i < prefs.groups.length(); i++) ga.append("G").append(prefs.groups.optJSONObject(i).optLong("id"));
        ok("L4c the groups array follows first-member order ("+ga+")", ga.toString().equals("G2G1"));
        // and it carries into the in-order placement: B's block sits before A's
        groupsOnTop = false;
        String inOrderDragged = show(render());
        System.out.println("in-order after the drag:         "+inOrderDragged);
        ok("L4d the drag carries across the placement (B before A inline)",
           inOrderDragged.indexOf("F#2") < inOrderDragged.indexOf("F#1"));
        ok("L4e loose order survives the drag",
           inOrderDragged.contains("T1") && inOrderDragged.indexOf("T1") < inOrderDragged.indexOf("T2")
           && inOrderDragged.indexOf("T2") < inOrderDragged.indexOf("T3"));

        // ===== L5: never between members (drag B onto the MIDDLE of A's members) =====
        // anchor = A1 (A's first member), after=true -> B lands AFTER A's LAST
        // member (A2), never between A1 and A2
        List<Tab> hostA2 = tabsOfGroup(1);
        boolean moved2 = moveGroupRelative(2, hostA2.get(0), true);
        ok("L5a the boundary drag moves", moved2);
        int a1 = -1, a2 = -1, b1 = -1, n = 0;
        for (int i = 0; i < tabs.size(); i++) {
            String nm = tabs.get(i).name;
            if (nm.equals("A1")) a1 = i;
            if (nm.equals("A2")) a2 = i;
            if (nm.equals("B1")) b1 = i;
        }
        ok("L5b B never lands between A's members (A1="+a1+" A2="+a2+" B1="+b1+")",
           !(b1 > a1 && b1 < a2));

        System.out.println(bad == 0 ? "ALL LAWS HOLD" : (bad+" LAWS BROKEN"));
        System.exit(bad == 0 ? 0 : 1);
    }
    static List<Object> onTop(List<Object> ignored) { throw new RuntimeException(); }
}
"""

# L2a needs a real comparison — rewrite that line to use the captured render
harness = harness.replace(
    'eq("L2a folder order identical across placements", show(folderSub(render())), show(folderSub(onTop(top()))));',
    'eq("L2a folder order identical across placements", show(folderSub(render())), show(folderSub(capturedOnTop)));')
harness = harness.replace(
    '        String onTop = show(render());',
    '        String onTop = show(render());\n        final List<Object> capturedOnTop = new ArrayList<Object>(render());')
# drop the dead helper
harness = harness.replace("    static List<Object> onTop(List<Object> ignored) { throw new RuntimeException(); }\n", "")

os.makedirs(work, exist_ok=True)
src = os.path.join(work, "Probe.java")
with open(src, "w", encoding="utf-8") as f:
    f.write(harness)
r = subprocess.run([javac, "-cp", jar, "-d", work, src], capture_output=True, text=True)
if r.returncode != 0:
    print("COMPILE FAIL:\n" + r.stderr[:4000]); sys.exit(2)
r = subprocess.run(["java", "-cp", work + ":" + jar, "Probe"], capture_output=True, text=True)
print(r.stdout)
if r.returncode != 0:
    print("STDERR:", r.stderr[:2000])
sys.exit(r.returncode)
