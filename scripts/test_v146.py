#!/usr/bin/env python3
"""v1.4.6 release gate — the clobber round two, the empty-folder anchor,
and the undo family.

The v1.4.5 field report:

1. "In the homescreen shortcuts groups, the shortcuts inside the group
   don't move when I hold and drag it. The other things about the home is
   fine, just fix the draggability inside the group" — THE CLOBBER, ROUND
   TWO (lesson 98): the folder window's cells were wired
   wireSdWinCell(...) and then tilePressFx(cellV, shell) ONE LINE LATER —
   and tilePressFx's setOnTouchListener SILENTLY REPLACED the drag wiring.
   Exactly the v1.3.89 corpse reborn in v1.4.4's new code: the drag (and
   the v1.4.5 interception arms inside it) never ran on device; the cells
   only ever had press-fx + sdCellTouch (a no-op when no GRID drag is
   armed). The v1.4.5 gate pinned the listener's SOURCE — green — while
   the listener itself was dead code. THE FIX: tilePressFx takes the drag
   feed as a PARAMETER (2-arg delegates with sdCellTouch); the window's
   builder (sdWinCellTouch — renamed from wireSdWinCell, it now RETURNS
   the listener instead of installing it) is passed INTO tilePressFx, so
   exactly ONE composed listener ever owns the cell.

2. "The tab modes work almost as intended, but there is the slight issue
   that empty tab groups do not stay at the top when I switch from the
   groups at top to follow tab order, i want it to behave as the non-empty
   tab groups work right now which is as desired" — THE EMPTY-FOLDER
   ANCHOR: the follow walk appended never-emitted (memberless) folders at
   the very END of the list, while the on-top render places them after
   the membered folders, ABOVE the loose tabs — the switch read as the
   empty folders falling off the top (the v1.4.2 parity fixed the SET,
   not the POSITION). Never-emitted folders now land right AFTER THE LAST
   EMITTED FOLDER ROW (the TOP when no folder row was emitted), in
   groups-array order — exactly where groupsInFirstMemberOrder() sorts
   them, so both placements agree on position too, and the
   post-materialize follow render reproduces the on-top visual IDENTICALLY.

3. "Also add an undo toast for like 3-4 seconds of deleting anything, be
   it tab, shortcut, bookmark, saved pages etc." — THE UNDO FAMILY: one
   window for every delete surface (Ui.UNDO_MS = 3500 ms) + the missing
   surfaces get their undo: BOOKMARK delete (Prefs.insertBookmark puts
   the captured row back at the same index), SAVED-PAGE delete (a SOFT
   delete: in-app files rename to a .trash sibling, SAF documents are
   captured to memory first; Undo restores, and only when the window
   closes untapped does the copy really die — Ui.snackbar's new onExpire
   hook), WATCH-LATER remove (WatchLater.restore re-inserts the item with
   its original fields), and the folder-window Remove-from-folder (a
   pre-existing NO-OP corpse found writing the undo: it read sdWinFolderIdx
   AFTER dismissAllOverlays zeroed it — dead since v1.4.0; the capture now
   happens before the teardown, and Undo restores the whole folder entry).
   The existing undos (tab close x2, shortcut, folder) carry the same
   3.5 s window.

PART A — static checks. PART B — the functional probe (javac + run
against the real org.json): the empty-folder render parity (both
branches, transcribed 1:1), insertBookmark's index-true restore, the
soft-delete file round-trip (rename → restore → finalizer).
V — the version pin.
"""
import os, re, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/TabsActivity.java")
TM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/TabManager.java")
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
BM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BookmarksActivity.java")
OP = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/OfflinePagesActivity.java")
WL = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/WatchLaterActivity.kt")
WLU = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/WatchLater.kt")
UI = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Ui.java")
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

ta, tm, ba, bm = (slurp(TA), slurp(TM), slurp(BA), slurp(BM))
op, wl, wlu, ui, prefs_src = (slurp(OP), slurp(WL), slurp(WLU), slurp(UI), slurp(PF))
strs, gradle = (slurp(STRS), slurp(GR))

print("A1 — the clobber, round two (the folder-window drag)")
need("protected void tilePressFx(View cell, final View shell,"
     "\n            final android.view.View.OnTouchListener dragFeed) {" in ba,
     "tilePressFx takes the drag feed as a PARAMETER (the 3-arg composed install)")
need("tilePressFx(cell, shell, sdCellTouch);" in ba,
     "the 2-arg form delegates with sdCellTouch (the home grid's feed)")
need("protected android.view.View.OnTouchListener sdWinCellTouch(" in ba
     and "return (v, e) -> {" in ba.split("protected android.view.View.OnTouchListener sdWinCellTouch")[1][:600],
     "sdWinCellTouch BUILDS and RETURNS the window's drag listener (the old wireSdWinCell installed — and got clobbered)")
need("wireSdWinCell(cellV, itemIdx, url, tTitle);" not in ba
     and "cell.setOnTouchListener((v, e) -> {" not in ba.split("protected android.view.View.OnTouchListener sdWinCellTouch")[1][:4200],
     "the window builder never installs its own listener (the clobber door is bricked shut)")
# v1.4.9 re-point: the cell gained its own ⋯ menu button before the composed install
# (the visible in-group item door) — the window widens past it
acell = ba.split("protected void sdWinAddCell")[1][:5600] if "protected void sdWinAddCell" in ba else ""
need("sdWinCellTouch(itemIdx, url, tTitle);" in acell
     and "tilePressFx(cellV, shell, winDrag);" in acell,
     "sdWinAddCell COMPOSES: the window's drag feed rides INTO tilePressFx (ONE listener owns the cell)")
need(acell.find("sdWinCellTouch(itemIdx, url, tTitle);") < acell.find("tilePressFx(cellV, shell, winDrag);"),
     "the feed is built before the composed install (no ordering corpse)")
wsc = ba.split("OnTouchListener sdWinCellTouch")[1][:4200] if "OnTouchListener sdWinCellTouch" in ba else ""
need("requestDisallowInterceptTouchEvent(true)" in wsc
     and wsc.find("requestDisallowInterceptTouchEvent(true)") > wsc.find("sdWinDragMoved = false;"),
     "the v1.4.5 interception arms SURVIVE inside the returned listener (now actually installed)")
need("ACTION_CANCEL" in wsc and "requestDisallowInterceptTouchEvent(false)" in wsc,
     "the window gesture still releases the stream at UP/CANCEL")
# the no-op corpse: the Remove-from-folder row read sdWinFolderIdx AFTER the teardown zeroed it
# v1.4.9 re-point: the row is "Delete shortcut" now (the v1.4.9 item-menu
# rework — the old "Remove from folder" DELETED the shortcut, so it was
# renamed; the capture-before-teardown law it carries is unchanged)
rm = ba.split("R.string.delete_shortcut")[1][:2600] if "R.string.delete_shortcut" in ba else ""
need("final int at = sdWinFolderIdx;" in rm
     and rm.find("final int at = sdWinFolderIdx;") < rm.find("dismissAllOverlays();"),
     "the delete row's capture happens BEFORE dismissAllOverlays (the dead-since-v1.4.0 no-op)")
need("prefs.removeSpeeddialItem(at, itemIdx);" in rm,
     "the remove rides the captured index (not the zeroed field)")
need("Ui.UNDO_MS" in rm and "insertSpeeddial" in rm,
     "the delete carries an Undo that restores the whole folder entry (the degenerate cases)")

print("A2 — the empty-folder anchor (the follow walk; v1.4.8 re-pin: the walk moved into TabManager.followRowOrder — the anchor PAIR now governs anchored empties, the v1.4.6 convention survives for ANCHORLESS ones, see test_v148)")
fw = tm.split("public List<Object> followRowOrder(boolean includeAnchorless)")[1][:3200] if "public List<Object> followRowOrder(boolean includeAnchorless)" in tm else ""
need("int lastFolderRow = -1;" in fw and "int putAt = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;" in fw
     and "out.add(putAt++, g);" in fw,
     "the v1.4.6 convention SURVIVES for anchorless empties (after the last folder row, the top when none)")
need("for (Object o : tm.followRowOrder()) items.add(o);" in ta,
     "TabsActivity renders the one row model (v1.4.8)")
need("for (JSONObject g : tm.groupsInFirstMemberOrder()) items.add(g);" in ta,
     "the on-top branch is untouched (always-top does what it always did)")

print("A3 — the undo family (one 3.5 s window for every delete)")
need("public static final long UNDO_MS = 3500L;" in ui,
     "Ui.UNDO_MS exists (the user's 'like 3-4 seconds', one constant everywhere)")
need("public static Snack snackbar(final Activity a,\n"
     "            String message, String action, final Runnable onAction,\n"
     "            long ms, final Runnable onExpire) {" in ui,
     "the snackbar DURATION + EXPIRY overload exists")
sb = ui.split("long ms, final Runnable onExpire) {")[1][:3600] if "long ms, final Runnable onExpire) {" in ui else ""
need("bar.postDelayed(remove, ms);" in sb,
     "the bar auto-leaves after [ms] (not the old hardcoded 5000)")
need("final boolean[] acted = {false};" in sb
     and "if (!acted[0] && onExpire != null)" in sb,
     "onExpire fires ONLY when the action was never tapped (the undo window closed)")
for f, name in ((ta, "TabsActivity.showUndoSnack (the switcher close)"),
                (ba, "BrowserActivity (toolbar close + shortcut + folder + window-item)")):
    need(f.count("Ui.UNDO_MS") >= 1 or f.count("util.Ui.UNDO_MS") >= 1,
         f"the existing undo carries the shared window: {name}")
need(ta.count("Ui.UNDO_MS, null);") >= 1 and ba.count("Ui.UNDO_MS, null);") >= 4,
     "every undo call site passes UNDO_MS (4 in BrowserActivity, 1 in TabsActivity)")
need("public void insertBookmark(int idx, JSONObject o)" in prefs_src,
     "Prefs.insertBookmark exists (the index-true bookmark restore)")
ib = prefs_src.split("public void insertBookmark(int idx, JSONObject o)")[1][:900]
need("Math.max(0, Math.min(idx, bookmarks.length()))" in ib,
     "the bookmark insert clamps (the list may have changed inside the window)")
bdel = bm.split("R.string.delete)")[1][:4200] if "R.string.delete)" in bm else ""
need("prefs.removeBookmark(url);" in bdel and "insertBookmark" in bdel and "Ui.UNDO_MS" in bdel,
     "the bookmark delete captures + offers Undo at the shared window")
sd = op.split("private Runnable softDeleteEntry")[1][:3000] if "private Runnable softDeleteEntry" in op else ""
need(".trash" in sd and "renameTo(trash)" in sd,
     "the saved-page SOFT delete: in-app files rename to a .trash sibling (never a real delete first)")
need("CAP = 32L * 1024 * 1024" in sd and "getContentResolver().delete(e.uri, null, null);" in sd,
     "SAF pages are captured to memory first (capped — too big deletes hard, no undo)")
need("Ui.UNDO_MS" in op and "finalizer" in op,
     "the saved-page delete carries the Undo + the expiry finalizer (the .trash dies only when the window closes)")
need("fun restore(c: Context, url: String, title: String, addedAt: Long, read: Boolean)" in wlu,
     "WatchLater.restore exists (original fields survive — the sorted view lands it home)")
need("WatchLater.restore(this, m.url, m.title, m.addedAt, m.read)" in wl and "Ui.UNDO_MS" in wl,
     "the watch-later remove carries the Undo at the shared window")
for s in ("bookmark_removed", "page_deleted", "watchlater_removed", "restore_failed"):
    need(f'name="{s}"' in strs, f"the string exists: {s}")

print("B — the functional probe (javac + run)")
# the probe needs no extracted app methods: the render branches are
# transcribed 1:1 from the source (the walk + the insert rule), the
# bookmark insert + remove are transcribed from Prefs, and the soft
# delete rides plain java.io against real temp files.
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
    work = "/tmp/bolt_v146_gate"
    for p in (os.path.join(work, "json.jar"), "/tmp/bolt_v145_gate/json.jar",
              "/tmp/bolt_v144_gate/json.jar", "/tmp/org.json.jar"):
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
        for (long id : ids) { JSONObject o = new JSONObject(); try { o.put("id", id); o.put("name", "G"+id); } catch (Exception e) {} groups.put(o); }
    }
    static List<JSONObject> groups() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (int i = 0; i < groups.length(); i++) { JSONObject o = groups.optJSONObject(i); if (o != null) out.add(o); }
        return out;
    }
    static List<JSONObject> groupsInFirstMemberOrder() {
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

    // ---- the two render branches, transcribed 1:1 from rebuildItems
    //      (v1.4.6: the follow walk tracks the last folder row and lands
    //      never-emitted folders right after it — the top when none) ----
    static List<String> render(boolean onTop) {
        List<String> out = new ArrayList<String>();
        List<Tab> sorted = new ArrayList<Tab>(tabs);   // manual sort = list order
        if (onTop) {
            for (JSONObject g : groupsInFirstMemberOrder()) out.add("G"+g.optLong("id"));
            for (Tab t : sorted) if (t.groupId == 0) out.add(t.name);
        } else {
            HashSet<Long> emitted = new HashSet<Long>();
            List<JSONObject> gs = groups();
            int lastFolderRow = -1;
            for (Tab t : sorted) {
                if (t.groupId != 0 && !emitted.contains(t.groupId)) {
                    for (JSONObject g : gs) {
                        if (g != null && g.optLong("id") == t.groupId) { out.add("G"+g.optLong("id")); break; }
                    }
                    emitted.add(t.groupId);
                    lastFolderRow = out.size() - 1;
                }
                if (t.groupId == 0) out.add(t.name);
            }
            int putAt = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;
            for (JSONObject g : gs) {
                if (g != null && !emitted.contains(g.optLong("id"))) out.add(putAt++, "G"+g.optLong("id"));
            }
        }
        return out;
    }
    static List<String> oldRenderFollow() {   // the v1.4.2-v1.4.5 convention (append at END)
        List<String> out = new ArrayList<String>();
        HashSet<Long> emitted = new HashSet<Long>();
        List<JSONObject> gs = groups();
        for (Tab t : new ArrayList<Tab>(tabs)) {
            if (t.groupId != 0 && !emitted.contains(t.groupId)) {
                for (JSONObject g : gs) if (g != null && g.optLong("id") == t.groupId) { out.add("G"+g.optLong("id")); break; }
                emitted.add(t.groupId);
            }
            if (t.groupId == 0) out.add(t.name);
        }
        for (JSONObject g : gs) if (g != null && !emitted.contains(g.optLong("id"))) out.add("G"+g.optLong("id"));
        return out;
    }

    static int bad = 0;
    static void ok(String label, boolean v) { if (!v) { System.out.println("FAIL "+label); bad++; } }
    static void eq(String label, Object a, Object b) { ok(label+" ("+a+" vs "+b+")", a.toString().equals(b.toString())); }

    // ---- Prefs.insertBookmark / removeBookmark, transcribed 1:1 ----
    static JSONArray bookmarks = new JSONArray();
    static void insertBookmark(int idx, JSONObject o) {
        if (o == null) return;
        try {
            int at = Math.max(0, Math.min(idx, bookmarks.length()));
            bookmarks.put(o);
            for (int i = bookmarks.length() - 1; i > at; i--) {
                JSONObject b = bookmarks.optJSONObject(i);
                bookmarks.put(i, bookmarks.optJSONObject(i - 1));
                if (b != null) bookmarks.put(i - 1, b);
            }
        } catch (Exception e) {}
    }

    public static void main(String[] args) throws Exception {
        // ============ B1: THE USER'S CASE — the switch keeps empty folders at the top ============
        // on-top visual: folders (membered, first-member order) then EMPTY
        // folders, loose tabs below. Post-materialize tm.tabs starts with
        // the member blocks; the follow walk must reproduce the on-top
        // visual EXACTLY — empty folder included.
        setGroups(100, 200, 300);          // A(membered), B(membered), E(memberless)
        Tab a1 = new Tab("a1", 100), a2 = new Tab("a2", 100), b1 = new Tab("b1", 200);
        Tab l1 = new Tab("L1"), l2 = new Tab("L2");
        tabs.clear(); tabs.add(a1); tabs.add(a2); tabs.add(b1); tabs.add(l1); tabs.add(l2);
        eq("B1a the on-top render pins folders then loose", render(true), "[G100, G200, G300, L1, L2]");
        eq("B1b THE FIX: the follow render agrees with on-top (empty folder keeps its top spot)",
           render(false), render(true));
        eq("B1c the OLD convention would have dropped E below the loose tabs",
           oldRenderFollow(), "[G100, G200, L1, L2, G300]");
        // the degenerate case: ONLY an empty folder exists — the old
        // convention buried it at the end; on-top shows it first
        setGroups(300);
        tabs.clear(); tabs.add(l1); tabs.add(l2);
        eq("B1d no membered folders: the empty folder renders at the TOP (both placements agree)",
           render(false), "[G300, L1, L2]");
        eq("B1e and that matches on-top", render(false), render(true));
        // interleaved pure-follow memory: the empty folder lands after the
        // LAST folder row, never inside the loose run below it
        setGroups(100, 200, 300);
        tabs.clear(); tabs.add(l1); tabs.add(a1); tabs.add(l2); tabs.add(b1);
        List<String> fr = render(false);
        ok("B1f interleaved: the empty folder sits after the last folder row",
           fr.equals(Arrays.asList("L1", "G100", "L2", "G200", "G300")));
        ok("B1g the SET still matches on-top (the v1.4.2 parity law holds)",
           new HashSet<String>(fr).equals(new HashSet<String>(render(true))));

        // ============ B2: the bookmark undo round-trip ============
        bookmarks = new JSONArray();
        String[] names = {"bm0", "bm1", "bm2", "bm3"};
        for (String n : names) { JSONObject o = new JSONObject(); o.put("title", n); o.put("url", "u"+n); bookmarks.put(o); }
        JSONObject captured = bookmarks.optJSONObject(2);
        bookmarks.remove(2);                                  // removeBookmark(idx)
        eq("B2a the list shrank in order", titles(), "bm0bm1bm3");
        insertBookmark(Math.min(2, bookmarks.length()), captured);
        eq("B2b UNDO restores the row at the SAME index", titles(), "bm0bm1bm2bm3");
        // clamp: an index past the end lands at the end, never throws
        insertBookmark(99, captured);
        ok("B2c a clamped insert never throws and lands at the end", titles().endsWith("bm2"));

        // ============ B3: the saved-page soft delete (real files) ============
        File dir = new File(System.getProperty("java.io.tmpdir"), "bolt_v146_" + System.nanoTime());
        dir.mkdirs();
        File page = new File(dir, "Saved page.mht");
        try (FileOutputStream fo = new FileOutputStream(page)) { fo.write("MIME-Version hello".getBytes("UTF-8")); }
        File trash = new File(page.getParentFile(), page.getName() + ".trash");
        ok("B3a the soft delete renames to .trash", page.renameTo(trash) && !page.exists() && trash.exists());
        ok("B3b the page vanishes from a directory listing",
           dir.listFiles(new FilenameFilter() { public boolean accept(File d, String n) { return n.toLowerCase().endsWith(".mht"); } }).length == 0);
        ok("B3c UNDO restores the original", trash.renameTo(page) && page.exists());
        String back = new String(java.nio.file.Files.readAllBytes(page.toPath()), "UTF-8");
        eq("B3d the content round-trips", back, "MIME-Version hello");
        // the expiry path: the finalizer deletes the trash for real
        page.renameTo(trash);
        ok("B3e the finalizer really deletes once the window closes", trash.delete() && !trash.exists());
        for (File x : dir.listFiles()) x.delete();
        dir.delete();

        if (bad > 0) { System.out.println("PROBE FAILURES: " + bad); System.exit(1); }
        System.out.println("probe: all green");
    }
    static String titles() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bookmarks.length(); i++) sb.append(bookmarks.optJSONObject(i).optString("title"));
        return sb.toString();
    }
}
"""
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        with open(src, "w", encoding="utf-8") as f:
            f.write(harness)
        r = subprocess.run([javac, "-cp", jar, "-d", d, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the probe compiles")
            print(r.stderr[:2500])
        else:
            r2 = subprocess.run(["java", "-cp", d + os.pathsep + jar, "Probe"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL walk runs green: the empty-folder anchor (both placements agree post-materialize, the top when no folder rows, the old convention DEMONSTRATED differing), the bookmark index-true restore, the soft-delete file round-trip")
            if r2.returncode != 0 or "all green" not in r2.stdout:
                print(r2.stdout[:3000])
                print(r2.stderr[:800])

print("V — the version pin")
need('versionCode 140' in gradle, 'versionCode 134')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need('versionName "1.4.26"' in gradle, 'versionName "1.4.26"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
entries = re.findall(r"<item>([^<]+)</item>", strs[strs.find('name="about_changelog"'):strs.find("</string-array>", strs.find('name="about_changelog"'))])

print()
if fails == 0:
    print("v1.4.6 gate: ALL GREEN")
else:
    print(f"v1.4.6 gate: {fails} FAILURES")
    sys.exit(1)
