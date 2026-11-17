import java.util.*;

/** v1.3.46 snap-zone drop table test: mirrors DragMergeCallback's plain-drop
 *  index math (zoneOf + the clearView insertion) and asserts the USER'S spec:
 *  "if I place it to left side on the tab, place the new tab to the left
 *  [of it] ... if I place the held tab on right side of the other lower tab,
 *  make space ... one space rightwards" — i.e. ZONE_LEFT => the held tab ends
 *  up IMMEDIATELY BEFORE the anchor in the final order, ZONE_RIGHT =>
 *  IMMEDIATELY AFTER, ZONE_CENTER (unarmed) => NO-OP. Every permutation must
 *  be a permutation, every index in bounds, and a drop whose insertion slot
 *  equals the dragged tab's home slot must reproduce the identity order. */

public class SimSnap {
    static final int ZONE_NONE = 0, ZONE_LEFT = 1, ZONE_CENTER = 2, ZONE_RIGHT = 3;

    /** zoneOf(): finger fraction across the card (clamped past the edges,
     *  so empty-space drops resolve to the nearest side). Card view splits
     *  horizontally, list view vertically — same arithmetic either way. */
    static int zoneOf(float f) {
        f = Math.max(0f, Math.min(1f, f));
        return f < 0.34f ? ZONE_LEFT : f < 0.66f ? ZONE_CENTER : ZONE_RIGHT;
    }

    /** insertion slot in the list WITHOUT the dragged tab (the final index
     *  the dragged tab lands at), or -1 for a no-op drop. */
    static int insertIndex(int n, int orig, int anchor, int zone) {
        if (zone != ZONE_LEFT && zone != ZONE_RIGHT) return -1;  // center = group zone, unarmed = no-op
        if (anchor < 0 || anchor >= n || anchor == orig) return -1;
        int reduced = anchor > orig ? anchor - 1 : anchor;
        return reduced + (zone == ZONE_RIGHT ? 1 : 0);
    }

    /** the final order after the drop (identity when idx < 0 or idx == orig). */
    static List<Integer> drop(int n, int orig, int idx) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(i);
        if (idx < 0 || idx == orig) return l;
        int t = l.remove(orig);
        l.add(Math.min(idx, l.size()), t);
        return l;
    }

    static int fails = 0;

    static void check(boolean ok, String msg) {
        if (!ok) { fails++; System.out.println("FAIL: " + msg); }
    }

    public static void main(String[] a) {
        // ---- 1) the full drop table: n=2..8, every orig, every anchor, both sides
        int cases = 0;
        for (int n = 2; n <= 8; n++) {
            for (int orig = 0; orig < n; orig++) {
                for (int anchor = 0; anchor < n; anchor++) {
                    for (int zone : new int[]{ZONE_LEFT, ZONE_RIGHT, ZONE_CENTER, ZONE_NONE}) {
                        cases++;
                        int idx = insertIndex(n, orig, anchor, zone);
                        if (zone == ZONE_CENTER || zone == ZONE_NONE || anchor == orig) {
                            check(idx == -1, "non-insert zone/anchor must be a no-op: n=" + n
                                    + " orig=" + orig + " anchor=" + anchor + " zone=" + zone);
                            continue;
                        }
                        check(idx >= 0 && idx < n, "index in bounds: n=" + n + " orig=" + orig
                                + " anchor=" + anchor + " zone=" + zone + " -> " + idx);
                        List<Integer> fin = drop(n, orig, idx);
                        // permutation: all n distinct
                        check(new HashSet<>(fin).size() == n && fin.size() == n,
                                "permutation intact: n=" + n + " orig=" + orig + " anchor=" + anchor);
                        // THE SPEC: dragged lands immediately before (LEFT) / after (RIGHT) the anchor
                        int fi = fin.indexOf(orig), fa = fin.indexOf(anchor);
                        if (zone == ZONE_LEFT)
                            check(fi >= 0 && fa >= 0 && fi == fa - 1,
                                    "LEFT: dragged immediately BEFORE anchor: n=" + n + " orig="
                                            + orig + " anchor=" + anchor + " -> " + fin);
                        else
                            check(fi >= 0 && fa >= 0 && fi == fa + 1,
                                    "RIGHT: dragged immediately AFTER anchor: n=" + n + " orig="
                                            + orig + " anchor=" + anchor + " -> " + fin);
                        // the no-op equivalence: an insertion slot equal to the
                        // home slot reproduces the identity order
                        if (idx == orig)
                            check(fin.equals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7).subList(0, n)),
                                    "k==orig is identity: n=" + n + " orig=" + orig);
                    }
                }
            }
        }
        System.out.println("drop table: " + cases + " cases, " + fails + " failures so far");

        // ---- 2) the user's worked example, 3 tabs [greasyfork, youtube, google]
        // drag GOOGLE (orig 2) onto the LEFT side of YOUTUBE (anchor 1):
        int idx = insertIndex(3, 2, 1, ZONE_LEFT);
        List<Integer> fin = drop(3, 2, idx);
        check(fin.equals(Arrays.asList(0, 2, 1)),
                "drop google left-of-youtube => [gf, gg, yt], got " + fin);
        // drag GREASYFORK (orig 0) onto the RIGHT side of YOUTUBE (anchor 1):
        idx = insertIndex(3, 0, 1, ZONE_RIGHT);
        fin = drop(3, 0, idx);
        check(fin.equals(Arrays.asList(1, 0, 2)),
                "drop greasyfork right-of-youtube => [yt, gf, gg], got " + fin);
        // already-left no-op: drag GF (orig 0) to the LEFT of YT (anchor 1)
        idx = insertIndex(3, 0, 1, ZONE_LEFT);
        check(idx == 0, "drop-where-it-already-is => k==orig (no-op), got k=" + idx);

        // ---- 3) the zone fractions (incl. the empty-space clamp)
        check(zoneOf(-0.5f) == ZONE_LEFT, "finger past the left edge => LEFT");
        check(zoneOf(0.0f) == ZONE_LEFT, "left edge => LEFT");
        check(zoneOf(0.33f) == ZONE_LEFT, "33% => LEFT");
        check(zoneOf(0.34f) == ZONE_CENTER, "34% => CENTER");
        check(zoneOf(0.5f) == ZONE_CENTER, "50% => CENTER");
        check(zoneOf(0.65f) == ZONE_CENTER, "65% => CENTER");
        check(zoneOf(0.67f) == ZONE_RIGHT, "67% => RIGHT");
        check(zoneOf(1.0f) == ZONE_RIGHT, "right edge => RIGHT");
        check(zoneOf(1.7f) == ZONE_RIGHT, "finger past the right edge => RIGHT");

        System.out.println(fails == 0 ? "SimSnap ALL PASS (" + cases + " drop cases + zones)"
                : "SimSnap FAILURES: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
