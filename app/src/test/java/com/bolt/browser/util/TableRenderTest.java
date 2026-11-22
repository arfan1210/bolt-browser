package com.bolt.browser.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.Spanned;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * v1.4.28 — THE TABLE RENDER PROBE (the invisible-table corpse): the
 * v1.4.27 TableSpan — a ReplacementSpan drawing the grid inside one text
 * line — rendered NOTHING in the field (whatever its draw swallowed, the
 * bubble showed only the bare U+2009 slot; the user saw "tables
 * invisible" with the raw source riding the tag). The v1.4.28 fix renders
 * every table as a REAL View ([AiRender.tableView]) inside a bubble
 * CONTAINER ([AiRender.fillMarkdown] interleaving text segments and table
 * views at the marker slots).
 *
 * This probe runs the REAL pipeline under Robolectric's instrumented
 * graphics and asserts PIXELS: a 3x3 GFM table renders with visible
 * borders and cell text (the requirement's own words). A View whose
 * onDraw paints nothing now FAILS the release; the never-blank law (the
 * monospace fallback inside the draw catch) is exercised by the
 * degenerate-geometry case.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
// v1.4.28 — NATIVE graphics: the pixel probe needs Robolectric's real
// instrumented Canvas/Paint (the LEGACY shadow mode draws nothing and
// reports degenerate font metrics — the probe would fail for the wrong
// reason).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TableRenderTest {

    private static final String MD = "Compare the engines:\n\n"
            + "| Engine | Mode | Size |\n"
            + "| --- | --- | --- |\n"
            + "| Bolt | light | 3 MB |\n"
            + "| Chrome | full | 140 MB |\n"
            + "| Firefox | full | 90 MB |\n"
            + "\nAs you can see, Bolt wins.\n";

    /** render -> the marker slot; splitTables -> [text, TABLE, text]. */
    @Test
    public void split_threeByThree_producesTablePartBetweenText() {
        Context ctx = RuntimeEnvironment.getApplication();
        Spanned s = AiRender.INSTANCE.render(ctx, MD);
        assertNotNull("the reply rendered", s);
        assertTrue("the intro text survived the render",
                s.toString().contains("Compare the engines"));
        List<Object> parts = AiRender.INSTANCE.splitTables(s);
        assertTrue("the split found the table part (more than one part)",
                parts.size() > 1);
        int tables = 0;
        for (Object p : parts) {
            if (!(p instanceof Spanned)) tables++;
        }
        assertEquals("exactly ONE table part", 1, tables);
        // the table's CELL TEXT rides the table part (the view), not the
        // text segments — the v1.4.27 corpse showed neither
        for (Object p : parts) {
            if (p instanceof Spanned) {
                assertFalse("the table content is not duplicated into the "
                        + "text segments", p.toString().contains("140 MB"));
            }
        }
    }

    /** fillMarkdown -> the container holds the text segments AND the
     *  table view, in order. */
    @Test
    public void fill_container_interleavesSegmentsAndTableView() {
        Context ctx = RuntimeEnvironment.getApplication();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        AiRender.fillMarkdown(ctx, box, MD);
        int textViews = 0;
        int others = 0;
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (c instanceof TextView) textViews++;
            else others++;
        }
        assertTrue("the bubble carries text segments (at least the intro "
                + "and the outro)", textViews >= 2);
        assertTrue("the bubble carries the TABLE VIEW child", others >= 1);
    }

    /** THE PIXEL PROBE: the table view measures to a real grid and its
     *  onDraw paints BORDERS and CELL TEXT — the corpse's "renders
     *  nothing" cannot recur without this failing the release. */
    @Test
    public void table_onDraw_paintsBordersAndCellText() {
        Context ctx = RuntimeEnvironment.getApplication();
        // pin the LIGHT theme: the pixel bands below assume the light
        // palette (dark text ink; the dark theme's white rules over a
        // white probe canvas would paint nothing legible). theme 0 = light
        // (Prefs.isDark's own law).
        com.bolt.browser.util.Prefs.get(ctx).set("theme", 0);
        List<List<String>> rows = new ArrayList<>();
        rows.add(Arrays.asList("Engine", "Mode", "Size"));
        rows.add(Arrays.asList("Bolt", "light", "3 MB"));
        rows.add(Arrays.asList("Chrome", "full", "140 MB"));
        rows.add(Arrays.asList("Firefox", "full", "90 MB"));
        View v = AiRender.INSTANCE.tableView(ctx, rows);
        assertNotNull("the table view built", v);
        int parentW = ctx.getResources().getDisplayMetrics().widthPixels;
        assertTrue("the probe display is sane", parentW >= 320);
        v.measure(View.MeasureSpec.makeMeasureSpec(parentW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        int w = v.getMeasuredWidth();
        int h = v.getMeasuredHeight();
        assertTrue("the table measures a real width (" + w + ")", w >= 100);
        assertTrue("the table measures a real height (" + h + ")", h >= 80);
        assertTrue("the table fits the width budget", w <= parentW);
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        v.draw(canvas);
        int dark = 0;      // cell text + the bold header (dark ink)
        int rule = 0;      // the semi-transparent rules over white = gray band
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int p : px) {
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            int lum = (r * 299 + g * 587 + b * 114) / 1000;
            if (lum < 110) dark++;
            else if (lum < 240) rule++;
        }
        assertTrue("the table painted CELL TEXT ink (dark pixels, found "
                + dark + ") — the invisible-table corpse", dark >= 40);
        assertTrue("the table painted its GRID (rule/band pixels, found "
                + rule + ")", rule >= 40);
        // never-blank law: a degenerate row set still renders SOMETHING
        View deg = AiRender.INSTANCE.tableView(ctx,
                java.util.Collections.singletonList(
                        java.util.Collections.singletonList("x")));
        assertNotNull("the degenerate table builds a view", deg);
        String grid = AiRender.INSTANCE.renderTable(
                java.util.Collections.singletonList(
                        java.util.Collections.singletonList("x")));
        assertFalse("the monospace fallback still renders text",
                grid.isEmpty());
    }
}
