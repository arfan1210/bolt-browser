import java.util.*;

/** v1.3.47 toolbar-swipe walk table test: mirrors tabSwipeOrder/tabSwipeTarget
 *  with the RENDERED-SNAPSHOT path (TabsActivity.renderedWalk() — the loose
 *  tiles in exactly the order the switcher last LAID THEM OUT) ahead of the
 *  derived manual walk.
 *
 *  DIRECTION (LESSON 32 — four field rounds solved it): the USER'S swipe
 *  naming is INVERTED relative to the finger. Their "swipe left" is the
 *  finger moving RIGHT; their "swipe right" is the finger moving LEFT (they
 *  name the direction the STRIP travels / the side they aim at). The wanted
 *  semantics = standard ViewPager:
 *     finger LEFT  (dx<0, user's "swipe right") -> the tile AFTER  (visually right)
 *     finger RIGHT (dx>0, user's "swipe left")  -> the tile BEFORE (visually left)
 *  at an edge, NO switch (no wrap).
 *
 *  The user's worked example (v1.3.45 report): 3 tabs in serial [greasyfork,
 *  youtube, google], youtube open, manual order => user's LEFT (finger right)
 *  must open GREASYFORK, user's RIGHT (finger left) must open GOOGLE.
 *  The v1.3.46 report ("swipe left is opening right tab and swipe right is
 *  opening left tab") describes the OLD dx>0->pos+1 code exactly — the flip
 *  below is what turns both observations into the wanted behavior.
 *
 *  v1.3.47 also REMOVED the sort options: the walk is manual-only (the sim
 *  asserts every walk resolves to list order). */

public class SimSwipe {
    static class T { String name; long gid; T(String n, long g){name=n;gid=g;} }

    /** sortedForDisplay: manual only (v1.3.47 — Prefs.tabsSort() is pinned
     *  to 0; mode 1/2 no longer exist in the UI). */
    static List<T> sortedForDisplay(List<T> tabs) {
        return new ArrayList<>(tabs);
    }

    /** the switcher's rendered arrangement (rebuildItems, non-search): loose
     *  tiles (groupId==0) in list order — the grid's top-left->bottom-right
     *  walk. */
    static List<T> renderedWalk(List<T> tabs) {
        List<T> walk = new ArrayList<>();
        for (T t : sortedForDisplay(tabs)) if (t.gid == 0) walk.add(t);
        return walk;
    }

    /** tabSwipeOrder v1.3.46+: the rendered SNAPSHOT wins when it is live
     *  and contains the current tab; otherwise the derived visible walk
     *  (loose tiles + the current folder's members). */
    static List<T> walk(List<T> tabs, List<T> snapshot, T cur) {
        if (snapshot != null && snapshot.size() > 1) {
            List<T> live = new ArrayList<>();
            for (T t : snapshot) if (tabs.contains(t)) live.add(t);
            if (live.contains(cur) && live.size() > 1) return live;
        }
        List<T> sorted = sortedForDisplay(tabs);
        List<T> vis = new ArrayList<>();
        for (T t : sorted) if (t.gid == 0 || (cur.gid != 0 && t.gid == cur.gid)) vis.add(t);
        return vis.size() > 1 ? vis : sorted;
    }

    /** tabSwipeTarget v1.3.47 (LESSON 32): finger RIGHT (dx>0) -> the tile
     *  BEFORE (visually left); finger LEFT (dx<0) -> the tile AFTER. */
    static String target(List<T> tabs, int active, List<T> snapshot, float dx) {
        T cur = tabs.get(active);
        List<T> w = walk(tabs, snapshot, cur);
        int pos = w.indexOf(cur);
        if (pos < 0) return "NONE";
        int t = dx > 0 ? pos - 1 : pos + 1;
        if (t < 0 || t >= w.size()) return "EDGE";
        return w.get(t).name;
    }

    static int fails = 0;
    static void check(boolean ok, String msg) { if (!ok) { fails++; System.out.println("FAIL: " + msg); } }

    /** the table row prints in the USER'S naming (their swipe left/right),
     *  with the finger direction noted for the implementer. */
    static void row(String label, List<T> tabs, int active, List<T> snap) {
        List<T> s = sortedForDisplay(tabs);
        StringBuilder order = new StringBuilder();
        for (T t : s) order.append(t.gid == 0 ? t.name : t.name.toLowerCase());
        System.out.printf("%-36s grid=%-24s cur=%s | userLEFT(finger->)=%-5s userRIGHT(finger<-)=%s%n",
                label, order, tabs.get(active).name,
                target(tabs, active, snap, +1), target(tabs, active, snap, -1));
    }

    public static void main(String[] a) {
        // THE FIELD REPORT (v1.3.45): 3 tabs in serial [gf, yt, gg], youtube
        // open, manual order. User's swipe LEFT (finger RIGHT) -> greasyfork;
        // user's swipe RIGHT (finger LEFT) -> google.
        List<T> man = Arrays.asList(new T("gf",0), new T("yt",0), new T("gg",0));
        List<T> snap = renderedWalk(man);
        row("FIELD: manual 3 serial, cur=yt", man, 1, snap);
        check(target(man, 1, snap, +1).equals("gf"),
                "field case: user LEFT (finger right) -> greasyfork");
        check(target(man, 1, snap, -1).equals("gg"),
                "field case: user RIGHT (finger left) -> google");
        // same walk with NO snapshot yet (first launch, switcher never opened)
        row("same, NO snapshot (fallback)", man, 1, null);
        check(target(man, 1, null, +1).equals("gf"), "fallback: user LEFT -> greasyfork");
        check(target(man, 1, null, -1).equals("gg"), "fallback: user RIGHT -> google");

        // edges: first + last tile, no wrap (v1.3.45 rule stays). The first
        // tile has nothing BEFORE it => user LEFT is an EDGE; the last tile
        // has nothing AFTER it => user RIGHT is an EDGE.
        check(target(man, 0, snap, +1).equals("EDGE"), "first tile: user LEFT is an EDGE");
        check(target(man, 2, snap, -1).equals("EDGE"), "last tile: user RIGHT is an EDGE");
        check(target(man, 0, snap, -1).equals("yt"), "first tile: user RIGHT -> yt");
        check(target(man, 2, snap, +1).equals("yt"), "last tile: user LEFT -> yt");

        // two tiles only (the v1.3.44 "wrap made it a mirror" case): A,B —
        // from A: user LEFT is an EDGE (nothing before); user RIGHT -> B.
        List<T> two = Arrays.asList(new T("A",0), new T("B",0));
        List<T> twoSnap = renderedWalk(two);
        check(target(two, 0, twoSnap, +1).equals("EDGE"), "[A,B] from A: user LEFT is an EDGE");
        check(target(two, 0, twoSnap, -1).equals("B"), "[A,B] from A: user RIGHT -> B");
        check(target(two, 1, twoSnap, -1).equals("EDGE"), "[A,B] from B: user RIGHT is an EDGE");
        check(target(two, 1, twoSnap, +1).equals("A"), "[A,B] from B: user LEFT -> A");

        // manual + folder: A,B in folder 5; C,D,E loose. current D.
        List<T> fol = Arrays.asList(new T("A",5), new T("B",5),
                new T("C",0), new T("D",0), new T("E",0));
        List<T> folSnap = renderedWalk(fol);
        row("manual + folder(a,b), cur=D", fol, 3, folSnap);
        check(target(fol, 3, folSnap, +1).equals("C"), "folder walk: D user LEFT -> C");
        check(target(fol, 3, folSnap, -1).equals("E"), "folder walk: D user RIGHT -> E");
        // current tab INSIDE the folder: not a tile -> derived fallback walks
        // the loose tiles + its own folder's members
        row("cur=B (in folder 5)", fol, 1, folSnap);
        String lb = target(fol, 1, folSnap, +1);
        check(!lb.equals("NONE") && !lb.equals("EDGE"), "foldered current still walkable, got " + lb);

        // stale snapshot (a tab closed since): entries filtered to live tabs
        List<T> closed = new ArrayList<>(Arrays.asList(
                new T("A",0), new T("B",0), new T("C",0), new T("D",0)));
        List<T> staleSnap = renderedWalk(closed);
        closed.remove(1);   // B closed after the snapshot
        row("stale snapshot (B closed), cur=C", closed, 1, staleSnap);
        check(target(closed, 1, staleSnap, +1).equals("A"), "stale: C user LEFT -> A (B filtered out)");
        check(target(closed, 1, staleSnap, -1).equals("D"), "stale: C user RIGHT -> D");

        System.out.println(fails == 0 ? "SimSwipe ALL PASS" : "SimSwipe FAILURES: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
