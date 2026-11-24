package com.bolt.browser.util;

import android.content.Context;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * v1.4.29 — THE WIDE-TABLE SCROLL PROBE (the task's own verify words):
 * "a dense table overflows its viewport naturally, text metrics are not
 * shrunk, horizontal scrolling reaches the final columns, surrounding
 * text remains in order, and vertical chat scrolling still works."
 *
 * The v1.4.28 renderer shrank a too-wide table's type ONCE, bounded at
 * 0.55x, and hosted the view width-filled in the chat child without a
 * horizontal viewport — dense AI tables became specks and/or clipped
 * their rightmost columns on a phone. The v1.4.29 law: the type NEVER
 * shrinks, the view measures its NATURAL content width
 * ([AiRender.TableView.naturalWidthPx]), and [AiRender.addTable] wraps it
 * in a HorizontalScrollView sized to that exact width — the wrapper only
 * intercepts HORIZONTAL drags, so the chat's vertical scrolling (the
 * parent container's job) is untouched by construction.
 *
 * Runs under Robolectric's real measure/layout pipeline (the same
 * NATIVE-graphics requirement class as TableRenderTest, but these probes
 * assert GEOMETRY — widths, scroll reach, order — not pixels).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TableScrollTest {

    /** a six-column dense table: far wider than any phone viewport (each
     *  cell rides a long token row — the AI-comparison-table shape the
     *  report described). Robolectric's probe display is 320 px wide, so
     *  the natural grid lands ~8x the viewport. */
    private static List<List<String>> denseRows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(Arrays.asList(
                "Feature column header A", "Feature column header B",
                "Feature column header C", "Feature column header D",
                "Feature column header E", "Feature column header F"));
        for (int i = 0; i < 6; i++) {
            rows.add(Arrays.asList(
                    "row " + i + " measures aaaaaaaaaaaaaaaaaaaaaaaa",
                    "row " + i + " measures bbbbbbbbbbbbbbbbbbbbbbbb",
                    "row " + i + " measures cccccccccccccccccccccccc",
                    "row " + i + " measures dddddddddddddddddddddddd",
                    "row " + i + " measures eeeeeeeeeeeeeeeeeeeeeeee",
                    "row " + i + " measures ffffffffffffffffffffffff"));
        }
        return rows;
    }

    private static String wideTableMd() {
        StringBuilder sb = new StringBuilder("Before the table.\n\n");
        sb.append("| Feature column header A | Feature column header B | "
                + "Feature column header C | Feature column header D | "
                + "Feature column header E | Feature column header F |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        for (int i = 0; i < 6; i++) {
            sb.append("| row ").append(i).append(" measures aaaaaaaaaaaaaaaaaaaaaaaa | row ").append(i)
                    .append(" measures bbbbbbbbbbbbbbbbbbbbbbbb | row ").append(i).append(" measures cccccccccccccccccccccccc | row ")
                    .append(i).append(" measures dddddddddddddddddddddddd | row ").append(i)
                    .append(" measures eeeeeeeeeeeeeeeeeeeeeeee | row ").append(i).append(" measures ffffffffffffffffffffffff |\n");
        }
        sb.append("\nAfter the table.\n");
        return sb.toString();
    }

    /** THE NO-SHRINK LAW: a dense table's natural width overflows the
     *  viewport honestly, and the cell type stays at the full 14.5sp base
     *  (the v1.4.28 0.55x corpse cannot recur without this failing). */
    @Test
    public void dense_table_naturalWidthOverflows_andTextNeverShrinks() {
        Context ctx = RuntimeEnvironment.getApplication();
        View v = AiRender.INSTANCE.tableView(ctx, denseRows());
        assertNotNull("the dense table builds", v);
        assertTrue("the builder returns the drawn-grid TableView",
                v instanceof AiRender.TableView);
        AiRender.TableView tv = (AiRender.TableView) v;
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int maxW = (int) (screenW * 0.94f);
        int natural = tv.naturalWidthPx();
        assertTrue("a dense table's NATURAL width overflows the width budget ("
                + natural + " vs " + maxW + ") — it overflows honestly, not "
                + "by shrinking", natural > maxW);
        float base = 14.5f * ctx.getResources().getDisplayMetrics().scaledDensity;
        assertEquals("the cell type NEVER shrinks (the tiny-text corpse)",
                base, tv.currentTextSizePx(), 0.01f);
    }

    /** THE BUBBLE LAW: the wide table rides a HorizontalScrollView whose
     *  child keeps its NATURAL width inside a bubble-wide wrapper, and the
     *  horizontal scroll REACHES THE FINAL COLUMNS. The surrounding text
     *  segments stay in document order (before + after the table). */
    @Test
    public void bubble_wrapsWideTableInHScroll_andScrollReachesLastColumn() {
        Context ctx = RuntimeEnvironment.getApplication();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        AiRender.fillMarkdown(ctx, box, wideTableMd());
        HorizontalScrollView hs = null;
        int textViews = 0;
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (c instanceof TextView) textViews++;
            if (c instanceof HorizontalScrollView) hs = (HorizontalScrollView) c;
        }
        assertNotNull("the wide table rides a HorizontalScrollView in the bubble", hs);
        assertTrue("the surrounding text segments stay in order (before + "
                + "after the table)", textViews >= 2);
        View child = hs.getChildAt(0);
        assertNotNull("the wrapper hosts the table view", child);
        assertTrue("the child IS the drawn-grid TableView (order + grid "
                + "unchanged)", child instanceof AiRender.TableView);
        AiRender.TableView tv = (AiRender.TableView) child;
        int bubbleW = ctx.getResources().getDisplayMetrics().widthPixels;
        assertTrue("the probe display is sane", bubbleW >= 320);
        box.measure(View.MeasureSpec.makeMeasureSpec(bubbleW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        box.layout(0, 0, bubbleW, box.getMeasuredHeight());
        int natural = tv.naturalWidthPx();
        assertEquals("the child keeps its NATURAL width inside the wrapper "
                + "(no clip, no shrink)", natural, child.getMeasuredWidth());
        assertTrue("the table honestly overflows the bubble ("
                + child.getMeasuredWidth() + " vs " + hs.getWidth() + ")",
                child.getMeasuredWidth() > hs.getWidth());
        // the scroll reaches the FINAL columns
        int maxScroll = child.getMeasuredWidth() - hs.getWidth();
        hs.scrollTo(maxScroll, 0);
        assertEquals("the horizontal scroll reaches the rightmost columns",
                Math.max(0, maxScroll), hs.getScrollX());
        // the wrapper NEVER scrolls vertically (the chat's vertical
        // scrolling stays the parent container's job)
        hs.scrollTo(0, 9999);
        assertEquals("the wrapper carries no vertical scroll range", 0, hs.getScrollY());
    }

    /** THE NORMAL-TABLE LAW: a table that fits the bubble measures exactly
     *  its content, shows no overflow, and the scroll stays parked — the
     *  normal-sized behavior is unchanged from v1.4.28 (same grid, same
     *  order; no dead space beyond the drawn grid). */
    @Test
    public void normal_table_fitsWithoutScrolling() {
        Context ctx = RuntimeEnvironment.getApplication();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        AiRender.fillMarkdown(ctx, box, "Intro.\n\n| A | B |\n| --- | --- |\n"
                + "| one | two |\n\nOutro.\n");
        HorizontalScrollView hs = null;
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (c instanceof HorizontalScrollView) hs = (HorizontalScrollView) c;
        }
        assertNotNull("the normal table rides the same wrapper", hs);
        View child = hs.getChildAt(0);
        assertTrue(child instanceof AiRender.TableView);
        int bubbleW = ctx.getResources().getDisplayMetrics().widthPixels;
        box.measure(View.MeasureSpec.makeMeasureSpec(bubbleW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        box.layout(0, 0, bubbleW, box.getMeasuredHeight());
        AiRender.TableView tv = (AiRender.TableView) child;
        int natural = tv.naturalWidthPx();
        assertEquals("the child measures exactly its content", natural,
                child.getMeasuredWidth());
        assertTrue("a normal-sized table FITS the bubble (no scrollbar)",
                child.getMeasuredWidth() <= hs.getWidth());
        hs.scrollTo(9999, 0);
        assertEquals("no horizontal range to scroll", 0, hs.getScrollX());
    }

    /** v1.4.30 — THE SCROLL-PRESERVATION LAW (the re-fill yank corpse):
     *  a RE-fill (the streaming completion, the math-font landing) keeps
     *  each table scroller's scrollX BY TABLE INDEX — a user reading a
     *  wide table's right columns is not yanked back to the left edge.
     *  Also pins the idempotent re-fill: the child count does not grow
     *  (the stale children — the thinking dots, the pre-font segments —
     *  never stack below the fresh render). */
    @Test
    public void refill_preservesTableScrollX_andDoesNotDuplicateChildren() {
        Context ctx = RuntimeEnvironment.getApplication();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        AiRender.fillMarkdown(ctx, box, wideTableMd());
        HorizontalScrollView hs1 = findScroller(box);
        assertNotNull("the first fill hosts the scroller", hs1);
        int bubbleW = ctx.getResources().getDisplayMetrics().widthPixels;
        box.measure(View.MeasureSpec.makeMeasureSpec(bubbleW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        box.layout(0, 0, bubbleW, box.getMeasuredHeight());
        View child1 = hs1.getChildAt(0);
        int maxScroll = child1.getMeasuredWidth() - hs1.getWidth();
        assertTrue("the fixture overflows (a scroll to preserve)",
                maxScroll > 0);
        hs1.scrollTo(maxScroll, 0);
        int before = box.getChildCount();
        // the RE-FILL (refreshPendings' math-font path re-fills with the
        // same raw markdown; the streaming path does the same)
        AiRender.fillMarkdown(ctx, box, wideTableMd());
        assertEquals("the re-fill is IDEMPOTENT (no duplicated children)",
                before, box.getChildCount());
        HorizontalScrollView hs2 = findScroller(box);
        assertNotNull(hs2);
        assertTrue("the re-fill rebuilt a NEW scroller", hs2 != hs1);
        // the restore rides the view's own post — a detached probe view
        // never drains that queue, so AiRender's restore runs its
        // DETACHED leg synchronously (the layout-then-scroll leg)
        assertEquals("the re-fill preserved the sideways scroll position",
                maxScroll, hs2.getScrollX());
    }

    /** v1.4.30 — THE COPY-THROUGH LAW: a long-press that lands ON the
     *  table (the grid view or its wrapper) rises to the BUBBLE
     *  container's long-press listener — the grid and the wrapper never
     *  claim click/long-click, so the raw-source copy wins through the
     *  table. */
    @Test
    public void table_andWrapper_neverClaimClick_orLongClick() {
        Context ctx = RuntimeEnvironment.getApplication();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        AiRender.fillMarkdown(ctx, box, wideTableMd());
        HorizontalScrollView hs = findScroller(box);
        assertNotNull(hs);
        View child = hs.getChildAt(0);
        assertTrue(child instanceof AiRender.TableView);
        assertFalse("the grid view never claims clicks (the copy-through "
                + "law)", child.isClickable());
        assertFalse("the grid view never claims long-presses",
                child.isLongClickable());
        assertFalse("the wrapper never claims clicks", hs.isClickable());
        assertFalse("the wrapper never claims long-presses",
                hs.isLongClickable());
    }

    private static HorizontalScrollView findScroller(LinearLayout box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (c instanceof HorizontalScrollView) return (HorizontalScrollView) c;
        }
        return null;
    }
}
