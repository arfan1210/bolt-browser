#!/usr/bin/env python3
"""v1.4.0 gate — THE PUT-AS-INSERT CORPSE + THE LAUNCHER FOLDER + THE
DRAGGABLE TAB GROUPS + ABOUT/UPDATE-CHECKER.

Field report (v1.3.100): "When I tap homescreen shortcuts groups, I want
the group to open like a floating window showing the icons inside the
group. And edit the group by holding on it. Also let the folders to be
draggable, only draggable to rearrange order, nothing else. Oh I found a
new big [bug]. Tab groups are not draggable, and when I move them with the
move from menu, it disappears. Make it draggable as well and fix the
disappearing issue. I don't see the folders, but the folder count in the
toolbar did not go down. Also, I think it is time to move to v1.4.0 from
the next one. To celebrate, add an about section of the app in the browser
settings with small info, version, and update checker."

PART 1 — THE PUT-AS-INSERT CORPSE (the disappearing folders, root-caused
against the framework itself). Android's JSONArray.put(int, Object) PADS
WITH NULLS THEN SETS (values.set) — it REPLACES, never inserts; verified
this session against AOSP libcore android-8→14
(android.googlesource.com platform/libcore json/src/main/java/org/json/
JSONArray.java). TWO app methods used it as if it inserted:
  - Prefs.moveGroup (tab groups, since v1.3.40): remove(idx) then
    groups.put(to, g) — [G1,G2] "move G2 up" produced [G2] (G1 GONE),
    [G1,G2,G3] "move G1 down" produced [G2,G1] (G3 GONE). The overwritten
    group vanished from the switcher while its tabs stayed in tm.tabs
    with a dead groupId — invisible (grouped tabs render only under their
    group row) yet still counted: "I don't see the folders, but the folder
    count in the toolbar did not go down". EXACTLY the report.
  - Prefs.moveSpeeddial (home reorder, since v1.3.79): the same idiom —
    "drag D before B" in A,B,C,D produced [A,D,C] (B EATEN). This is the
    corpse UNDER the v1.3.89/90 "dragging the shortcuts is STILL DELETING
    some of the icons" reports: the gesture layer was rebuilt three times
    (v1.3.90 drop=reorder, v1.3.91 release-gate, v1.3.92 A-B-C-D law)
    while the data layer kept losing the neighbour. insertSpeeddial
    (v1.3.91) even documents the truth — "JSONArray has no insert; the
    bubble is the cheapest correct move" — but nobody went back to the
    two movers.
THE FIX: both movers rebuild the order in a java.util.ArrayList (List.add
is a TRUE insert) and rewrite the SAME JSONArray instance in place, so
entry counts are preserved BY CONSTRUCTION. Proven by the compiled probe
below (every from/to pair for n=2..7, the A-B-C-D law, the group cases).

PART 2 — THE ORPHAN HEAL (TabManager.healGroups): tabs whose groupId
points at a nonexistent group are reset to ungrouped once per switcher
open — the user's hidden tabs COME BACK, and the toast says why.

PART 3 — THE DRAGGABLE TAB GROUPS (TabsActivity): group rows get drag
flags (no swipe); a group drag is REORDER-ONLY (no dwell, no merge, no
snap anchors — the row rides the finger 1:1); other group rows wear the
accent rim; the drop's half decides BEFORE/AFTER and calls
prefs.moveGroupRelative (insert semantics); the settle rides
settleDropSlide (now Object-keyed through itemKey so the folder row
glides from the DROP POINT too).

PART 4 — THE LAUNCHER FOLDER WINDOW (home): tapping a folder opens a
floating window — scrim + rounded panel anchored to the tile, the member
ICONS (favicon tiles, 3 per row, scrollable), tap-to-open, hold-an-icon
for open-in-new-tab/remove-from-folder, Rename/Ungroup on the quiet
bottom row. HOLDING a folder opens the EDIT menu (sdGroupEditMenu: Open /
Rename / Remove shortcut… / Ungroup / Delete folder with the v1.3.91 undo
snackbar). DRAGGING a folder is reorder-only: sdDragSrcIsGroup gates the
dwell path in sdDragMove, startSdDwell and sdDwellTick — "only draggable
to rearrange order, nothing else."

PART 5 — ABOUT + THE UPDATE CHECKER (AboutActivity.kt, Kotlin per the
standing language rule): the live version chip (PackageManager, never
hardcoded — the label law), the real engine row (WebView package
version), the update check against the PUBLIC mirror (anonymous; the
private repo is unreachable from the field), version-true comparison
(component-wise: 1.4.0 > 1.3.100 — a string compare says the opposite),
and on a find the APK URL opens IN BOLT (ACTION_VIEW — the app's own
download engine fetches it). Reached from the settings-home About row
(with the live version chip) and the settings search index.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREFS = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Prefs.java")
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
TA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/TabsActivity.java")
TM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/TabManager.java")
SHA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/SettingsHomeActivity.java")
ABOUT = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/AboutActivity.kt")
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

prefs = slurp(PREFS)
ba = slurp(BA)
ta = slurp(TA)
tm = slurp(TM)
sha = slurp(SHA)
about = slurp(ABOUT) if os.path.exists(ABOUT) else ""
mani = slurp(MANI)
strs = slurp(STRS)
gradle = slurp(GR)

print("A — the put-as-insert fix, source shape (Prefs.java)")
need("public int indexOfGroup(long id)" in prefs,
     "indexOfGroup exists (the shared lookup)")
need("public boolean moveGroupTo(long id, int toIdx)" in prefs,
     "moveGroupTo exists (the drag-drop absolute mover)")
need(re.search(r"public boolean moveGroup\(long id, int delta\) \{\s*\n\s*int idx = indexOfGroup\(id\);\s*\n\s*if \(idx < 0\) return false;\s*\n\s*return moveGroupTo\(id, idx \+ delta\);", prefs) is not None,
     "moveGroup delegates to moveGroupTo (one implementation of 'move', not two)")
need("public boolean moveGroupRelative(long draggedId, long targetId, boolean after)" in prefs,
     "moveGroupRelative exists (the before/after drop semantics)")
need(re.search(r"int giW = gi > si \? gi - 1 : gi;[\s\S]{0,120}?moveGroupTo\(draggedId, after \? giW \+ 1 : giW\)", prefs) is not None,
     "moveGroupRelative computes the final index on the list WITHOUT the dragged group")
# the lossless idiom in BOTH movers
need(prefs.count("list.add(toIdx, g);") == 1 and prefs.count("while (groups.length() > 0) groups.remove(0);") >= 1,
     "moveGroupTo rebuilds via a true List insert + in-place rewrite (groups instance preserved)"
     " (v1.4.1: the same idiom also powers reorderGroups now — count >= 1)")
need(prefs.count("list.add(insert, o);") == 1 and prefs.count("while (speeddial.length() > 0) speeddial.remove(0);") == 1,
     "moveSpeeddial rebuilds via a true List insert + in-place rewrite")
need("speeddial.put(insert, o);" not in prefs and "groups.put(to, g);" not in prefs,
     "the SET-semantics put(int, ·) CALLS are gone from both movers (only the comments remember them)")
need(re.search(r"int insert = toIdx > fromIdx \? toIdx - 1 : toIdx;\s*\n\s*insert = Math\.max\(0, Math\.min\(insert, list\.size\(\)\)\);", prefs) is not None,
     "moveSpeeddial keeps the insertion-point convention (toIdx-1 forward / toIdx backward)")
need("verified against AOSP libcore android-8" in prefs,
     "the fix's comment records the framework evidence (future sessions read the why)")

print("B — the functional probe (the parse-gate law: run the REAL movers)")
# extract the group movers (indexOfGroup..moveGroupRelative) + moveSpeeddial verbatim
g_start = prefs.find("    public int indexOfGroup(long id) {")
g_end = prefs.find("    // ---- speed dial")
s_start = prefs.find("    public void moveSpeeddial(int fromIdx, int toIdx) {")
s_end = prefs.find("    /** v1.3.79 — NEW FOLDER:")
if min(g_start, g_end, s_start, s_end) < 0 or g_end <= g_start or s_end <= s_start:
    need(False, "the mover methods are where the gate expects them")
    probe_ok = False
else:
    probe_ok = True
if probe_ok:
    group_movers = prefs[g_start:g_end]
    sd_mover = prefs[s_start:s_end]
    # the probe is a static harness: staticize the method declarations and
    # stub Prefs' persistence hooks (the probe tests the ORDER MATH, not IO)
    for sig in ("    public int indexOfGroup(long id) {",
                "    public boolean moveGroup(long id, int delta) {",
                "    public boolean moveGroupTo(long id, int toIdx) {",
                "    public boolean moveGroupRelative(long draggedId, long targetId, boolean after)"):
        group_movers = group_movers.replace(sig, sig.replace("    public ", "    public static ", 1), 1)
    sd_mover = sd_mover.replace("    public void moveSpeeddial(int fromIdx, int toIdx) {",
                                "    public static void moveSpeeddial(int fromIdx, int toIdx) {", 1)
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
    # org.json jar (the app uses Android's platform org.json; the maven jar
    # shares the SET-then-pad semantics the probe must run against)
    jar = None
    work = "/tmp/bolt_v140_gate"
    os.makedirs(work, exist_ok=True)
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
        need(False, "the org.json jar (aliyun/maven, like test_qr's ZXing fetch)")
        probe_ok = False

if probe_ok:
    harness = """
    static JSONArray arr(String... names) {
        JSONArray a = new JSONArray();
        for (String s : names) { JSONObject o = new JSONObject(); o.put("name", s); a.put(o); }
        return a;
    }
    static JSONArray arrId(long... ids) {
        JSONArray a = new JSONArray();
        for (long id : ids) { JSONObject o = new JSONObject(); o.put("id", id); a.put(o); }
        return a;
    }
    static String show(JSONArray a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            sb.append(o == null ? "null" : (o.keySet().contains("name") ? o.optString("name") : String.valueOf(o.optLong("id"))));
            if (i < a.length() - 1) sb.append(",");
        }
        return sb + "]";
    }
    static int bad = 0;
    static void eq(String label, JSONArray a, String expect) {
        String got = show(a);
        if (!got.equals(expect)) { System.out.println("FAIL " + label + ": got " + got + " expected " + expect); bad++; }
    }
    public static void main(String[] args) {
        // ---- the field's A-B-C-D law (the cases the old code ate) ----
        speeddial = arr("A","B","C","D"); moveSpeeddial(3, 1); eq("drag D before B", speeddial, "[A,D,B,C]");
        speeddial = arr("A","B","C","D"); moveSpeeddial(1, 0); eq("drag B before A", speeddial, "[B,A,C,D]");
        speeddial = arr("A","B","C","D"); moveSpeeddial(1, 3); eq("drag B after C", speeddial, "[A,C,B,D]");
        speeddial = arr("A","B","C","D"); moveSpeeddial(1, 4); eq("drag B after D (end)", speeddial, "[A,C,D,B]");
        speeddial = arr("A","B");          moveSpeeddial(1, 0); eq("two: B before A", speeddial, "[B,A]");
        // no-ops unchanged
        speeddial = arr("A","B","C","D"); moveSpeeddial(2, 2); eq("no-op drop", speeddial, "[A,B,C,D]");
        speeddial = arr("A","B","C","D"); moveSpeeddial(2, 3); eq("no-op drop +1", speeddial, "[A,B,C,D]");
        // ---- the fuzz: EVERY (from,to) preserves the multiset and lands right ----
        for (int n = 2; n <= 7; n++) {
            String[] names = new String[n];
            for (int i = 0; i < n; i++) names[i] = String.valueOf((char)('A'+i));
            for (int from = 0; from < n; from++) {
                for (int to = 0; to <= n; to++) {
                    speeddial = arr(names);
                    moveSpeeddial(from, to);
                    if (speeddial.length() != n) { System.out.println("FAIL length n=" + n + " from=" + from + " to=" + to); bad++; continue; }
                    int finalIdx = to > from ? to - 1 : to;
                    java.util.ArrayList<String> exp = new java.util.ArrayList<>();
                    for (int i = 0; i < n; i++) if (i != from) exp.add(names[i]);
                    exp.add(finalIdx, names[from]);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < n; i++) { sb.append(exp.get(i)); if (i < n-1) sb.append(","); }
                    eq("fuzz n=" + n + " from=" + from + " to=" + to, speeddial, "[" + sb + "]");
                }
            }
        }
        // ---- the reported tab-group cases (the disappearing neighbours) ----
        groups = arrId(10, 20);          moveGroup(20, -1);      eq("G2 up (2 groups)", groups, "[20,10]");
        groups = arrId(10, 20, 30);      moveGroup(20, 1);       eq("G2 down", groups, "[10,30,20]");
        groups = arrId(10, 20, 30);      moveGroup(10, 1);       eq("G1 down", groups, "[20,10,30]");
        groups = arrId(10, 20, 30);      moveGroup(30, -1);      eq("G3 up", groups, "[10,30,20]");
        groups = arrId(10);              moveGroup(10, -1);      eq("single up refused", groups, "[10]");
        groups = arrId(10, 20);          moveGroup(10, -1);      eq("top up refused", groups, "[10,20]");
        groups = arrId(10, 20);          moveGroup(20, 1);       eq("bottom down refused", groups, "[10,20]");
        // ---- moveGroupTo + moveGroupRelative (the drag paths) ----
        groups = arrId(10, 20, 30, 40);  moveGroupTo(40, 0);     eq("G4 to top", groups, "[40,10,20,30]");
        groups = arrId(10, 20, 30, 40);  moveGroupTo(10, 3);     eq("G1 to bottom", groups, "[20,30,40,10]");
        groups = arrId(10, 20, 30, 40);  moveGroupTo(20, 2);     eq("G2 to slot 2", groups, "[10,30,20,40]");
        groups = arrId(10, 20, 30);      moveGroupTo(99, 1);     eq("missing id refused", groups, "[10,20,30]");
        groups = arrId(10, 20, 30, 40);  moveGroupRelative(10, 40, false); eq("G1 before G4", groups, "[20,30,10,40]");
        groups = arrId(10, 20, 30, 40);  moveGroupRelative(40, 10, false); eq("G4 before G1", groups, "[40,10,20,30]");
        groups = arrId(10, 20, 30, 40);  moveGroupRelative(10, 40, true);  eq("G1 after G4", groups, "[20,30,40,10]");
        groups = arrId(10, 20, 30, 40);  moveGroupRelative(20, 20, false); eq("self refused", groups, "[10,20,30,40]");
        // instance identity (holders of prefs.groups() keep their array)
        JSONArray ref = groups;
        moveGroupTo(10, 2);
        if (groups != ref) { System.out.println("FAIL instance identity lost"); bad++; }
        if (bad > 0) { System.out.println("PROBE FAILURES: " + bad); System.exit(1); }
        System.out.println("probe: all green");
    }
}
"""
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        with open(src, "w", encoding="utf-8") as f:
            f.write("import org.json.JSONArray;\nimport org.json.JSONObject;\n\n"
                    "public class Probe {\n"
                    "    static void scheduleWrite() {}\n"
                    "    static void saveSpeeddial() {}\n"
                    "    static JSONArray speeddial, groups;\n"
                    + group_movers + sd_mover
                    + harness)
        r = subprocess.run([javac, "-cp", jar, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the extracted movers compile (probe)")
            print(r.stderr[:1200])
        else:
            r2 = subprocess.run(["java", "-cp", d + os.pathsep + jar, "Probe"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL movers run green: the A-B-C-D law, the 156-case fuzz, the group cases, moveGroupRelative")
            if r2.returncode != 0 or "all green" not in r2.stdout:
                print(r2.stdout[:1500])
                print(r2.stderr[:800])

print("C — the launcher folder window + edit menu (BrowserActivity)")
need("protected void showSdFolderWindow(final int idx)" in ba
     and "cell.setOnClickListener(v -> showSdFolderWindow(idx));" in ba,
     "tapping a folder opens the floating window (groupCell routes to it)")
need("cell.setOnLongClickListener(v -> {" in ba and "beginSdDrag(idx, cell);" in ba,
     "long-pressing a folder still arms the drag (reorder)")
need("protected void sdGroupEditMenu(final int idx)" in ba
     and re.search(r'if \(o\.optBoolean\("group", false\)\) \{\s*\n\s*// v1\.4\.0[\s\S]{0,400}?sdGroupEditMenu\(idx\);', ba) is not None,
     "the hold door for a folder opens the EDIT menu (sdTileMenu's group branch)")
need("protected void sdRenameGroupDialog(final int idx)" in ba,
     "the rename dialog is shared (window row + edit menu)")
need("folder_empty_hint" in strs and "remove_from_folder" in strs and "group_orphans_healed" in strs,
     "the new strings exist (empty hint / remove-from-folder / heal toast)")
need(re.search(r"rows\.add\(row\(R\.drawable\.ic_delete, getString\(R\.string\.delete_folder\)[\s\S]{0,700}?Ui\.snackbar", ba) is not None,
     "Delete folder carries the v1.3.91 undo snackbar")
need("dismissSdFolderWindow();" in ba.split("protected void dismissAllOverlays")[1][:600],
     "dismissAllOverlays closes the folder window")
need("protected void refreshHome() {\n        dismissSdFolderWindow();" in ba,
     "a home rebuild closes the folder window (the grid under it is rebuilt)")
# the window's member rows open + the per-icon long-press menu
# v1.4.4 re-point: the long-click became the hold-at-UP door — the same
# rows now live in sdWinItemMenu; v1.4.6 re-point: the builder is
# sdWinCellTouch (the clobber fix — it RETURNS the listener now)
# v1.4.9 re-point: the menu grew the full item-edit set (Edit shortcut /
# Move to home (de-group) / Delete shortcut) and its own ⋯ button on every
# cell; "Remove from folder" (which DELETED) became Delete shortcut
need(re.search(r"protected void sdWinItemMenu[\s\S]{0,4200}?delete_shortcut", ba) is not None
     and re.search(r"protected void sdWinItemMenu[\s\S]{0,3000}?move_to_home", ba) is not None
     and re.search(r"OnTouchListener sdWinCellTouch[\s\S]{0,4600}?sdWinItemMenu\(itemIdx, url, tTitle\);", ba) is not None
     and re.search(r"sdWinAddCell[\s\S]{0,4200}?ic_more[\s\S]{0,1400}?sdWinItemMenu\(itemIdx, url, tTitle\)", ba) is not None,
     "holding an icon inside the window offers the item menu (v1.4.9: the ⋯ button opens it too)")
# reorder-only folder drags
need("sdDragSrcIsGroup = prefs.isSpeeddialGroup(idx);" in ba,
     "beginSdDrag records whether the dragged tile is a FOLDER")
need(re.search(r"if \(sdDragSrcIsGroup\) \{\s*\n\s*if \(sdFolderTarget != -1\) \{\s*\n\s*sdFolderTarget = -1;", ba) is not None,
     "sdDragMove skips the dwell path for folder drags (reorder-only)")
need("if (sdDragSrcIsGroup) return;   // v1.4.0: a folder drag never merges" in ba,
     "startSdDwell refuses folder drags (belt and braces)")
need("sdFolderTarget < 0 || sdDragSrcIsGroup" in ba,
     "the dwell tick refuses folder drags too (no stale pill can merge)")
need("sdDragSrcIsGroup = false;   // v1.4.0: the next drag re-tests for a folder" in ba,
     "endSdDrag resets the folder flag")

print("D — the orphan heal (TabManager + TabsActivity)")
need(re.search(r"public int healGroups\(\) \{[\s\S]{0,900}?groupId = 0;", tm) is not None,
     "TabManager.healGroups resets dead groupIds to ungrouped")
need(re.search(r"int healed = tm\.healGroups\(\);[\s\S]{0,200}?group_orphans_healed", ta) is not None,
     "the switcher heals once per open and toasts the recovery")

print("E — the draggable tab groups (TabsActivity)")
need(re.search(r'if \(adapter\.isGroup\(vh\.getBindingAdapterPosition\(\)\)\) \{[\s\S]{0,1400}?ItemTouchHelper\.UP[\s\S]{0,300}?ItemTouchHelper\.END,\s*\n\s*0\);', ta) is not None,
     "group rows get DRAG flags and NO swipe (a folder is never swiped away)")
need("private JSONObject draggingGroup = null;" in ta
     and "draggingGroup = gp >= 0 ? adapter.groupAt(gp) : null;" in ta,
     "the callback tracks the dragged GROUP (the merge machinery is skipped)")
need(re.search(r"if \(draggingGroup != null\) \{\s*\n\s*View gunder = rowUnder\(r, item, cx, cy\);\s*\n\s*updateGroupDropStripe\(gunder\);", ta) is not None,
     "onChildDraw's group branch: raw finger-follow + the drop rim, no dwell/merge/zone")
need("private void updateGroupDropStripe(View under)" in ta,
     "the group-drop rim helper exists (group rows light up, tab cards never do)")
need(re.search(r'final JSONObject gdrag = draggingGroup;[\s\S]{0,12600}?gdone = tm\.moveGroupRelative\(gid, ganchor, gafter\);', ta) is not None,
     "the drop resolves before/after on the row under the finger and BLOCK-MOVES the members via tm.moveGroupRelative"
     " (v1.4.3: one drag law — the array-only reorder is retired; v1.4.8 re-pin: the window widens past the"
     " beside-an-empty branch that now precedes the membered call; v1.4.9 re-pin: the render-neighbour"
     " branch widened the window again)")
need(re.search(r"private void settleDropSlide\(RecyclerView\.ViewHolder vh, final Object dragged", ta) is not None,
     "settleDropSlide takes the dragged ITEM (Tab or group — the folder row glides from the drop point)")
need("if (key != null && key.equals(itemKey(dragged))) {" in ta,
     "the drop-point identity goes through itemKey (fresh group JSONObjects match)")

print("F — About + the update checker (Kotlin, the language rule)")
need(os.path.exists(ABOUT), "AboutActivity.kt exists (a NEW file → Kotlin per the standing rule)")
need('.ui.AboutActivity' in mani,
     "AboutActivity is registered in the manifest")
need("RELEASES_API" in about and "bolt-browser-releases/releases/latest" in about,
     "the checker reads the PUBLIC mirror anonymously (the private repo is unreachable in the field)")
need("fun tagParts(tag: String?): IntArray?" in about and "fun isNewer(candidate: IntArray?, current: IntArray?): Boolean" in about,
     "the version parse + compare exist (component-wise)")
need("toBoolean() not in about and " not in about and 'versionName = "1' not in about
     and 'version = "1' not in about,
     "no hardcoded version strings in the About screen (the label law: everything reads the live package)")
need("getPackageInfo(packageName, 0).versionName" in about,
     "the version chip reads the installed package")
need(re.search(r"R\.drawable\.ic_info[\s\S]{0,600}?AboutActivity\.class", sha) is not None,
     "the settings home carries the About row (ic_info + the live version chip)")
need(re.search(r"new Entry\(R\.drawable\.ic_info, getString\(R\.string\.about_bolt\)", sha) is not None,
     "the settings search index carries About")
for s in ("about_bolt", "check_for_updates", "up_to_date", "update_available",
          "get_update", "update_check_failed", "engine_version"):
    need(f'name="{s}"' in strs, f"the string {s} exists")

print("V — the version pin (the v1.4.0 milestone)")
need('versionCode 128' in gradle, 'versionCode 128 (v1.4.10 re-point 2026-11-17)')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)
need('versionName "1.4.14"' in gradle, 'versionName "1.4.14"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)

print()
if fails == 0:
    print("v1.4.0 gate: ALL GREEN")
else:
    print(f"v1.4.0 gate: {fails} FAILURES")
    sys.exit(1)
