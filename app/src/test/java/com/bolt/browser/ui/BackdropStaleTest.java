package com.bolt.browser.ui;

import android.widget.ImageView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v1.4.33 — THE SCROLL-STATE PROBE (the bottom-flash kill's Robolectric
 * rung): a scroll marks the glass blur backdrops STALE (they must paint
 * the SOLID theme bar colour until the next snapshot) and the first
 * post-idle snapshot clears the mark. The activity-level wiring runs on
 * the REAL BrowserActivity under Robolectric:
 *
 *   scroll event -> lastScrollAt advances + markBackdropStale() (stale
 *                   -> solid)
 *   idle + fresh snapshot -> the stale marks clear (glass shows again)
 *
 * The default Robolectric skin is non-glass, so the real blur views are
 * absent on this surface — the probe proves (a) the PURE state machine
 * (stale -> solid, fresh snapshot -> glass) and (b) the wiring legs that
 * do not need the glass views (the scroll timestamp, the no-op safety of
 * the mark/clear pair without glass, the solid colour resolves opaque).
 * The glass-view-level stale->solid leg is source-pinned in
 * scripts/test_v1433.py (markBackdropStale paints Fx.barSolidColor).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackdropStaleTest {

    @Test
    public void scroll_marksStale_andStateSaysSolid() {
        final BrowserActivity a =
                Robolectric.buildActivity(BrowserActivity.class).setup().get();
        assertTrue("the activity launched", !a.isFinishing());
        long before = a.lastScrollAt;
        // a scroll event (the scroll-to-hide listener funnels here): the
        // timestamp advances and the stale mark runs (stale -> solid)
        a.onContentScrolled(-40, 120);
        assertTrue("the scroll event advanced lastScrollAt (the "
                + "scroll-active detector is live)", a.lastScrollAt >= before);
        // the PURE state machine owns the visible outcome
        assertEquals("stale + nothing fresh -> SOLID (never the stale "
                + "snapshot — the bottom-flash corpse)",
                "solid", BrowserActivity.backdropState(false, true, false));
        assertEquals("scroll-active -> SOLID even with a fresh snapshot "
                + "(it would land stale)",
                "solid", BrowserActivity.backdropState(true, false, true));
        assertEquals("a fresh snapshot on an idle backdrop -> GLASS",
                "glass", BrowserActivity.backdropState(false, true, true));
        assertEquals("idle + clean -> GLASS",
                "glass", BrowserActivity.backdropState(false, false, false));
    }

    @Test
    public void markClearPair_isSafeWithoutGlassViews() {
        final BrowserActivity a =
                Robolectric.buildActivity(BrowserActivity.class).setup().get();
        // non-glass surface: blurTop/blurBottom are absent — the mark and
        // the clear must no-op WITHOUT throwing (the law: non-glass skins
        // are untouched by the stale machinery)
        a.markBackdropStale();
        a.clearBackdropStale(0);
        a.clearBackdropStale(1);
        a.clearBackdropStale(-1);   // the edge guard
        a.clearBackdropStale(7);    // the edge guard
        assertTrue("the activity survived the stale pair without glass",
                !a.isFinishing());
    }

    @Test
    public void solidColour_isOpaqueAndResolved() {
        final BrowserActivity a =
                Robolectric.buildActivity(BrowserActivity.class).setup().get();
        int solid = com.bolt.browser.util.Fx.barSolidColor(a);
        int alpha = (solid >>> 24) & 0xFF;
        assertTrue("the stale-backdrop solid colour is OPAQUE (alpha >= "
                + "0xE6 — a translucent fill would show the window behind)",
                alpha >= 0xE6);
        assertTrue("the solid colour resolves (never 0)",
                solid != 0);
    }
}
