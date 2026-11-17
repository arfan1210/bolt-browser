import java.util.*;

/** v1.3.49→v1.3.50 finger-true drag table test: mirrors DragMergeCallback's
 *  LIVE position math (the onChildDraw model) and asserts the USER'S spec
 *  after the v1.3.48/50 field reports ("the held tab must strictly follow
 *  my finger and never move anywhere on its own except the slight
 *  alignments during snapping, and must not disappear out of screen";
 *  v1.3.50: "follow the finger more closely and strictly"):
 *
 *   1. RAW: the stock ItemTouchHelper deltas are viewport-stable (stock
 *      getSelectedDxDy = mSelectedStart + mD - current left/top — verified
 *      by decompiling recyclerview-1.3.2), so under a STATIONARY finger the
 *      drawn card position must stay EXACTLY constant however far the grid
 *      auto-scrolls — the v1.3.47 dragScrollComp bug was a SECOND
 *      compensation stacked on the stock one (and polluted the X axis with
 *      a vertical quantity), which made the card run away diagonally.
 *   2. FREE SPACE: position = raw (dX/dY) exactly — 1:1 with the finger,
 *      no elastic chase, never leaves the screen while the finger is on it.
 *   3. SNAP: over a card (grid still) the eased OFFSET eases toward the
 *      zone anchor on top of raw — the only self-motion is that glide, and
 *      v1.3.50 CAPS it at a QUARTER of the card per axis: the alignment is
 *      always "slight", even onto a full-width GROUP row whose center
 *      anchor used to yank the card by up to half a screen.
 *   4. SCROLL: while the grid moves (lastGridScrollAt < 120ms ago) the snap
 *      offset target is 0 — the card rides the finger, never an anchor on a
 *      card that is itself flying past (the "jumps around" hop).
 *   5. MIXED: any interleaving of finger moves, scrolls and zone entries
 *      keeps the drawn position within the ±0.25-card envelope of the raw
 *      finger-true position, and on screen whenever the finger is.
 *   6. THE DROP CAPTURE (v1.3.50): clearView captures the drop point from
 *      the LAST DRAWN smX/smY, never from the view's translation (the
 *      stock recover + super.clearView zero it first) — the settle starts
 *      exactly where the finger was; the card never visits the old slot. */

public class SimDrag {
    static final int ZONE_NONE = 0, ZONE_LEFT = 1, ZONE_CENTER = 2, ZONE_RIGHT = 3;
    static final long GRID_MOVING_WINDOW_MS = 120L;
    static final float EASE = 0.60f;   // the per-frame snap-offset lerp (v1.3.50: 0.45 -> 0.60)
    static final float CAP_F = 0.25f;   // the per-axis snap-offset cap (a quarter of the card)

    static int fails = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { fails++; System.out.println("FAIL: " + msg); }
    }

    /** the simulated drag frame state */
    static class Drag {
        // the held card's home slot (RV coordinates) and size
        float homeTop, homeLeft, w, h;
        // stock ItemTouchHelper fields
        float mSelectedStartX, mSelectedStartY;   // home left/top at grab
        float mDx, mDy;                            // finger delta since grab
        float itemTop, itemLeft;                   // the card's CURRENT layout pos
        // the callback's own state
        float snapOX, snapOY, smX, smY;
        long lastGridScrollAt;
        long now;
        boolean tabsAsCards = true;

        Drag(float top, float left, float ww, float hh) {
            homeTop = top; homeLeft = left; w = ww; h = hh;
            itemTop = top; itemLeft = left;
            mSelectedStartX = left; mSelectedStartY = top;
        }

        /** stock getSelectedDxDy (decompiled): the deltas the callback gets. */
        float stockDx() { return mSelectedStartX + mDx - itemLeft; }
        float stockDy() { return mSelectedStartY + mDy - itemTop; }

        /** the other cards on the board (RV coords): [left, top, w, h, isGroup]. */
        List<float[]> others = new ArrayList<>();

        /** rv.scrollBy(0, dy): repositions every child, fires onDragScroll. */
        void gridScroll(float dy) {
            itemTop -= dy;                 // the held card's home slot moves
            for (float[] o : others) o[1] -= dy;
            now += 16L;
            lastGridScrollAt = now;        // onDragScroll stamps the time
        }

        /** finger MOVE (viewport delta). */
        void finger(float dx, float dy) {
            mDx += dx; mDy += dy;
            now += 16L;
        }

        /** a beat with no input (lets the ease advance). */
        void idle() { now += 16L; }

        /** one onChildDraw frame: mirrors the v1.3.49 model. under = the
         *  card under the finger (null = free space), null zones = grid
         *  moving. Returns the card's drawn TOP-LEFT (visual position). */
        float[] drawFrame(float[] under) {
            float dX = stockDx(), dY = stockDy();
            float cx = itemLeft + w / 2f + dX;
            float cy = itemTop + h / 2f + dY;
            boolean gridMoving = lastGridScrollAt > 0L
                    && now - lastGridScrollAt < GRID_MOVING_WINDOW_MS;
            boolean overGroup = under != null && under[4] == 1f;
            float tOX = 0f, tOY = 0f;
            if (under != null && !gridMoving) {
                // zoneOf on the under card
                float f = tabsAsCards
                        ? (cx - under[0]) / Math.max(1f, under[2])
                        : (cy - under[1]) / Math.max(1f, under[3]);
                f = Math.max(0f, Math.min(1f, f));
                int zone = f < 0.34f ? ZONE_LEFT : f < 0.66f ? ZONE_CENTER : ZONE_RIGHT;
                float wx, wy;
                if (overGroup || zone == ZONE_CENTER) {
                    wx = (under[0] + under[0] + under[2]) / 2f;
                    wy = (under[1] + under[1] + under[3]) / 2f;
                } else if (tabsAsCards) {
                    float off = under[2] * 0.30f;
                    wx = zone == ZONE_LEFT ? under[0] + off : under[0] + under[2] - off;
                    wy = (under[1] + under[1] + under[3]) / 2f;
                } else {
                    float off = under[3] * 0.30f;
                    wx = (under[0] + under[0] + under[2]) / 2f;
                    wy = zone == ZONE_LEFT ? under[1] + off : under[1] + under[3] - off;
                }
                tOX = wx - (itemLeft + w / 2f) - dX;
                tOY = wy - (itemTop + h / 2f) - dY;
            }
            // v1.3.50 — the "slight alignment" CAP: a quarter of the card
            // per axis, mirrored from onChildDraw.
            float capX = w * CAP_F, capY = h * CAP_F;
            if (tOX > capX) tOX = capX; else if (tOX < -capX) tOX = -capX;
            if (tOY > capY) tOY = capY; else if (tOY < -capY) tOY = -capY;
            snapOX += (tOX - snapOX) * EASE;
            snapOY += (tOY - snapOY) * EASE;
            smX = dX + snapOX;
            smY = dY + snapOY;
            return new float[]{itemLeft + smX, itemTop + smY};
        }

        /** the raw (finger-true) drawn position, no snap. */
        float[] rawPos() { return new float[]{itemLeft + stockDx(), itemTop + stockDy()}; }
    }

    public static void main(String[] a) {
        final float W = 500f, H = 2200f;          // the RV viewport
        final float CARD_W = 240f, CARD_H = 184f; // a tab card

        // ---- 1) THE AUTO-SCROLL DRIFT KILL: stationary finger, grid scrolls
        // a 30-tab list end to end (~4000px). The drawn card must stay
        // EXACTLY where the finger holds it — no drift in EITHER axis (the
        // v1.3.47 bug slid it sideways by the full vertical scroll).
        {
            Drag d = new Drag(100f, 20f, CARD_W, CARD_H);
            d.finger(30f, 60f);                    // grab + a small move
            float[] p0 = d.drawFrame(null);
            float[] raw0 = d.rawPos();
            check(Math.abs(p0[0] - raw0[0]) < 0.01f && Math.abs(p0[1] - raw0[1]) < 0.01f,
                    "free-space start = raw (1:1 with the finger)");
            for (int i = 0; i < 250; i++) {        // 250 x 16px = 4000px scroll
                d.gridScroll(16f);
                float[] p = d.drawFrame(null);
                check(Math.abs(p[0] - p0[0]) < 0.01f,
                        "X must not drift during vertical auto-scroll (i=" + i
                                + ", dx=" + (p[0] - p0[0]) + ")");
                check(Math.abs(p[1] - p0[1]) < 0.01f,
                        "Y must not drift under a stationary finger (i=" + i + ")");
                check(p[0] >= -CARD_W && p[0] <= W && p[1] >= -CARD_H && p[1] <= H,
                        "card must stay on screen during the whole scroll (i=" + i + ")");
                if (fails > 5) break;
            }
        }

        // ---- 2) THE FREE-SPACE 1:1: fast finger flings, no scroll — the
        // drawn position equals the raw finger-true position EVERY frame
        // (no elastic chase lag).
        {
            Drag d = new Drag(100f, 20f, CARD_W, CARD_H);
            Random r = new Random(7);
            for (int i = 0; i < 500; i++) {
                d.finger((r.nextFloat() - 0.5f) * 60f, (r.nextFloat() - 0.5f) * 60f);
                float[] p = d.drawFrame(null);
                float[] raw = d.rawPos();
                check(Math.abs(p[0] - raw[0]) < 0.01f && Math.abs(p[1] - raw[1]) < 0.01f,
                        "free space = exact finger follow (i=" + i + ")");
                if (fails > 5) break;
            }
        }

        // ---- 3) THE SNAP ENVELOPE: finger parked over another card, grid
        // still — the offset eases in; the glide is bounded by the anchor
        // distance (<= one card dimension), and the END state pins the
        // card's center on the zone anchor (the v1.3.46 spec, preserved).
        {
            Drag d = new Drag(100f, 20f, CARD_W, CARD_H);
            // a card two rows below the held card's home
            float[] target = {20f, 600f, CARD_W, CARD_H, 0f};
            d.others.add(target);
            // drag the finger onto that card's LEFT zone (near-left)
            float fingerCx = target[0] + 0.1f * CARD_W;   // 10% across = LEFT zone
            float fingerCy = target[1] + 0.5f * CARD_H;
            // home center = (140, 192); finger must land there:
            d.finger(fingerCx - (20f + CARD_W / 2f), fingerCy - (100f + CARD_H / 2f));
            float[] snapped = null;
            for (int i = 0; i < 60; i++) snapped = d.drawFrame(target);   // let it settle
            float drawnCx = snapped[0] + CARD_W / 2f, drawnCy = snapped[1] + CARD_H / 2f;
            float wantX = target[0] + 0.30f * CARD_W;     // the LEFT anchor
            float wantY = target[1] + CARD_H / 2f;        // the row center
            check(Math.abs(drawnCx - wantX) <= 1f, "snap end state X on the zone anchor: got "
                    + drawnCx + " want " + wantX);
            check(Math.abs(drawnCy - wantY) <= 1f, "snap end state Y on the row center: got "
                    + drawnCy + " want " + wantY);
            float[] raw = d.rawPos();
            check(Math.abs(snapped[0] - raw[0]) <= CARD_W && Math.abs(snapped[1] - raw[1]) <= CARD_H,
                    "the snap glide never exceeds one card from the finger-true position");
            check(Math.abs(d.snapOX) <= CARD_W * CAP_F + 0.5f,
                    "v1.3.50: the settled snap offset respects the quarter-card cap: got "
                            + d.snapOX + " cap " + (CARD_W * CAP_F));
        }

        // ---- 4) SNAP SUPPRESSED WHILE THE GRID MOVES: same snapped state,
        // then the grid auto-scrolls — the offset must ease back to ZERO
        // (the card rides the finger), and with the finger stationary the
        // drawn position must return to the pre-snap raw spot and HOLD.
        {
            Drag d = new Drag(100f, 20f, CARD_W, CARD_H);
            float[] target = {20f, 600f, CARD_W, CARD_H, 0f};
            d.others.add(target);
            d.finger(0f, 500f);
            for (int i = 0; i < 60; i++) d.drawFrame(target);        // snapped
            float[] rawBefore = d.rawPos();
            for (int i = 0; i < 30; i++) { d.gridScroll(16f); d.drawFrame(target); }
            float[] p = d.drawFrame(target);
            check(Math.abs(p[0] - rawBefore[0]) <= 1f && Math.abs(p[1] - rawBefore[1]) <= 1f,
                    "during the scroll the card glides back to the finger and holds: got ("
                            + p[0] + "," + p[1] + ") want (" + rawBefore[0] + "," + rawBefore[1] + ")");
            check(Math.abs(d.snapOX) <= 1f && Math.abs(d.snapOY) <= 1f,
                    "snap offset eases to zero while the grid moves");
        }

        // ---- 5) MIXED CHAOS: random interleaving of finger moves, scrolls
        // and hover targets — every frame the drawn position must sit
        // within one card of raw (the glide envelope) and on-screen
        // whenever the finger-true position is.
        {
            Drag d = new Drag(100f, 20f, CARD_W, CARD_H);
            float[] target = {260f, 900f, CARD_W, CARD_H, 0f};
            d.others.add(target);
            Random r = new Random(42);
            for (int i = 0; i < 2000; i++) {
                int roll = r.nextInt(10);
                if (roll < 4) d.finger((r.nextFloat() - 0.5f) * 80f, (r.nextFloat() - 0.5f) * 80f);
                else if (roll < 7) d.gridScroll((r.nextFloat() - 0.5f) * 40f);
                else d.idle();
                // rowUnder(): the target counts as "under" only when the
                // finger-true card center is inside its bounds.
                float dX = d.stockDx(), dY = d.stockDy();
                float cx = d.itemLeft + CARD_W / 2f + dX, cy = d.itemTop + CARD_H / 2f + dY;
                boolean inside = cx >= target[0] && cx <= target[0] + target[2]
                        && cy >= target[1] && cy <= target[1] + target[3];
                float[] p = d.drawFrame(inside ? target : null);
                float[] raw = d.rawPos();
                check(Math.abs(p[0] - raw[0]) <= CARD_W * CAP_F + 1f
                                && Math.abs(p[1] - raw[1]) <= CARD_H * CAP_F + 1f,
                        "chaos frame inside the quarter-card envelope (i=" + i + ")");
                boolean rawOn = raw[0] >= -CARD_W && raw[0] <= W && raw[1] >= -CARD_H && raw[1] <= H;
                if (rawOn) check(p[0] >= -2 * CARD_W && p[0] <= W + CARD_W
                        && p[1] >= -2 * CARD_H && p[1] <= H + CARD_H,
                        "chaos frame not off-screen while the finger holds it on (i=" + i + ")");
                if (fails > 5) break;
            }
        }

        // ---- 6) THE OLD BUG, REPRODUCED AS A GUARD: with the v1.3.47 math
        // (raw + dragScrollComp on BOTH axes) a 4000px scroll drives the
        // card ~4000px sideways — assert the NEW math keeps it put, and
        // document the delta explicitly.
        {
            Drag d = new Drag(100f, 20f, CARD_W, CARD_H);
            d.finger(30f, 60f);
            float[] p0 = d.drawFrame(null);
            for (int i = 0; i < 250; i++) { d.gridScroll(16f); d.drawFrame(null); }
            float[] p1 = d.drawFrame(null);
            float drift = Math.abs(p1[0] - p0[0]) + Math.abs(p1[1] - p0[1]);
            check(drift < 1f, "total drift over a 4000px auto-scroll < 1px (was ~4000px+4000px"
                    + " with the v1.3.47 double-compensation): got " + drift);
        }

        // ---- 7) THE CAP (v1.3.50): a full-width GROUP row whose CENTER
        // anchor sits far from the finger — the uncapped model would yank
        // the card by up to half a screen; the capped model keeps the
        // alignment within a QUARTER of the card, forever "slight".
        {
            Drag d = new Drag(100f, 20f, CARD_W, CARD_H);
            float[] group = {0f, 300f, 500f, 80f, 1f};   // full-width group row
            d.others.add(group);
            // park the finger at the FAR LEFT of the row (the center anchor
            // is ~250px to the right — far beyond the cap)
            float fingerCx = group[0] + 0.05f * group[2];
            float fingerCy = group[1] + 0.5f * group[3];
            d.finger(fingerCx - (20f + CARD_W / 2f), fingerCy - (100f + CARD_H / 2f));
            float[] p = null;
            for (int i = 0; i < 60; i++) p = d.drawFrame(group);   // let it settle
            float[] raw = d.rawPos();
            check(Math.abs(p[0] - raw[0]) <= CARD_W * CAP_F + 1f,
                    "group-row center anchor capped on X: got " + (p[0] - raw[0])
                            + " cap " + (CARD_W * CAP_F));
            check(Math.abs(p[1] - raw[1]) <= CARD_H * CAP_F + 1f,
                    "group-row center anchor capped on Y: got " + (p[1] - raw[1])
                            + " cap " + (CARD_H * CAP_F));
        }

        // ---- 8) THE DROP CAPTURE (v1.3.50): on release the stock recover
        // animation + super.clearView zero the view's TRANSLATION before
        // clearView's body runs — the drop point must be captured from the
        // LAST DRAWN smX/smY, and the settle must start exactly there. The
        // regression guard for "the held tab flies back to its old position
        // and appears in the position I chose after a delay".
        {
            // (a) snapped release: capture == the drawn position
            Drag d = new Drag(100f, 20f, CARD_W, CARD_H);
            float[] target = {20f, 600f, CARD_W, CARD_H, 0f};
            d.others.add(target);
            d.finger(100f, 480f);                          // drag onto the lower card
            float[] snapped = null;
            for (int i = 0; i < 30; i++) snapped = d.drawFrame(target);
            check(Math.abs(d.snapOX) > 0.5f,
                    "sanity: the snap offset is engaged at release (snapOX=" + d.snapOX + ")");
            check(Math.abs((d.itemLeft + d.smX) - snapped[0]) < 0.01f
                            && Math.abs((d.itemTop + d.smY) - snapped[1]) < 0.01f,
                    "drop capture (snapped release) = the last DRAWN position");
            // (b) free-space release far below home: capture == drawn, and a
            // translation-based capture (what super zeroed) would read the
            // OLD HOME SLOT — the v1.3.50 fly-back, documented as a guard
            d.finger(0f, 1000f);                          // leave every card
            float[] freed = null;
            for (int i = 0; i < 30; i++) freed = d.drawFrame(null);
            check(Math.abs((d.itemLeft + d.smX) - freed[0]) < 0.01f
                            && Math.abs((d.itemTop + d.smY) - freed[1]) < 0.01f,
                    "drop capture (free release) = the last DRAWN position");
            check(Math.abs(d.itemTop - freed[1]) > CARD_H * 0.5f,
                    "a translation-based capture after super = the OLD HOME SLOT"
                            + " (the fly-back this test forbids)");
        }

        // ---- 9) THE AUTO-SCROLL ZONE (v1.3.51, field: "the area where the
        // scrolling activates is small. Make the scrolling activate whenever
        // the held tab reaches or moves past either the top edge or bottom of
        // viewable screen"): the TRIGGER is the held CARD'S OWN EDGES vs the
        // viewable grid's edges — NOT the old invisible finger-centre band.
        // The SPEED curve is the approved v1.3.50 one: depth measured from
        // the card's CENTRE, so the card arrives at the edge at working
        // speed (never the band's crawl) and caps at the deep-past max.
        {
            final float RVH = 2200f;                       // the viewable grid
            float band = Math.max(120f, Math.min(225f, RVH * 0.20f));  // sim densities
            // the shipped trigger (mirrors dragAutoScroll): {dir, mag}
            // cardTop/cardBot = the card's DRAWN bounds (viewport-stable).
            java.util.function.BiFunction<float[], Float, float[]> zone =
                    (bounds, rvH) -> {
                        float cardTop = bounds[0], cardBot = bounds[1];
                        float half = (cardBot - cardTop) * 0.5f;
                        if (cardTop <= 0f) return new float[]{-1f, Math.min(1f, (half - cardTop) / band)};
                        if (cardBot >= rvH) return new float[]{1f, Math.min(1f, (cardBot - rvH + half) / band)};
                        return new float[]{0f, 0f};
                    };
            // (a) mid-list card, fully inside the viewable area: NO scroll —
            // the card must be able to travel the whole grid without the
            // list running away under it
            check(zone.apply(new float[]{300f, 484f}, RVH)[0] == 0f,
                    "card fully inside the viewable area does not scroll");
            // (b) the card's TOP edge exactly REACHES the top edge: the
            // scroll ACTIVATES (field: "whenever the held tab REACHES"),
            // already at the old curve's working speed (mag = half/band —
            // the v1.3.50 band put the centre half-a-card into the zone at
            // exactly this card position)
            float[] zReach = zone.apply(new float[]{0f, 184f}, RVH);
            check(zReach[0] == -1f, "card top edge reaching the top edge activates the scroll");
            check(Math.abs(zReach[1] - (92f / band)) < 0.01f,
                    "speed at reach = the v1.3.50 curve at that card position (half/band)");
            // (c) moves PAST the top edge: still scrolling, deeper = faster,
            // capped at the deep-past max
            float[] zPast = zone.apply(new float[]{-140f, 44f}, RVH);
            check(zPast[0] == -1f && zPast[1] > zReach[1],
                    "deeper past the top edge scrolls faster");
            float[] zMax = zone.apply(new float[]{-900f, -716f}, RVH);
            check(zMax[0] == -1f && zMax[1] == 1f, "deep past the edge caps at mag 1");
            // (d) the BOTTOM edge, mirrored: reach activates, past deepens
            float[] zBot = zone.apply(new float[]{2016f, RVH}, RVH);
            check(zBot[0] == 1f, "card bottom edge reaching the bottom edge activates the scroll");
            check(zone.apply(new float[]{1980f, 2164f}, RVH)[0] == 0f,
                    "card bottom just SHORT of the edge does not scroll");
            float[] zBotPast = zone.apply(new float[]{2100f, 2284f}, RVH);
            check(zBotPast[0] == 1f && zBotPast[1] > zBot[1],
                    "deeper past the bottom edge scrolls faster");
            // (e) THE OLD MODEL'S BLIND SPOT IS GONE: a card held with its
            // top edge well PAST the top edge (deep past, the old band's
            // fast zone) and simultaneously its bottom far above the grid's
            // bottom — exactly one direction may run
            check(zone.apply(new float[]{-400f, -216f}, RVH)[0] == -1f,
                    "card fully above the fold scrolls up only");
        }

        System.out.println(fails == 0
                ? "SimDrag ALL PASS (finger-true drag model + drop capture + auto-scroll zone, v1.3.51)"
                : "SimDrag FAILED: " + fails);
        if (fails > 0) System.exit(1);
    }
}
