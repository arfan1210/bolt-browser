package com.bolt.browser.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * v1.4.29 — THE PREVIEW GEOMETRY PROBE (the rotated/misaligned + black-
 * region corpse). QrPreviewGeom derives the TextureView transform in the
 * coordinates TextureView CONSUMES (view-local geometry: the buffer is
 * first stretched to fill the view, then the matrix maps view coords to
 * view coords). The old buffer-space recipe passed its own "fills view"
 * check while the APPLIED matrix left the real view uncovered — the
 * preview landed rotated and misaligned with a black region (the
 * portrait Realme's bottom half).
 *
 * This probe pins the law for PORTRAIT and LANDSCAPE views across ALL
 * FOUR quarter-turns (sensor 0/90/180/270 with the display at 0):
 * the displayed content bounds are CENTERED on the view and COVER both
 * axes, and the matrix (the values fed to Matrix.setTransform) really
 * maps the stretched content there. The pure class is the same code the
 * scanner runs — scripts/test_v1429.py compiles + runs this exact file
 * outside Robolectric too.
 */
public class QrPreviewGeomTest {

    private static final float EPS = 0.02f;

    private static void assertCenteredCovers(String label,
            float viewW, float viewH, float bufW, float bufH, int theta) {
        float[] b = QrPreviewGeom.contentBounds(viewW, viewH, bufW, bufH, theta);
        float left = b[0], top = b[1], right = b[2], bottom = b[3];
        // CENTERED: the bounds' center is the view's center
        assertEquals(label + ": centered horizontally",
                viewW / 2f, (left + right) / 2f, EPS);
        assertEquals(label + ": centered vertically",
                viewH / 2f, (top + bottom) / 2f, EPS);
        // COVERS BOTH AXES: the content is >= the view on width and height
        assertTrue(label + ": covers the width (" + (right - left) + " >= " + viewW + ")",
                right - left >= viewW - 1f);
        assertTrue(label + ": covers the height (" + (bottom - top) + " >= " + viewH + ")",
                bottom - top >= viewH - 1f);
        assertTrue(label + ": the fills invariant holds",
                QrPreviewGeom.fills(viewW, viewH, bufW, bufH, theta));
    }

    /** the matrix must land the STRETCHED content exactly on contentBounds:
     *  the view-rect center stays put and one corner maps outside-or-on
     *  both edges (cover), matching the pure bounds. */
    private static void assertMatrixMatchesBounds(String label,
            float viewW, float viewH, float bufW, float bufH, int theta) {
        float[] m = QrPreviewGeom.matrix(viewW, viewH, bufW, bufH, theta);
        float[] b = QrPreviewGeom.contentBounds(viewW, viewH, bufW, bufH, theta);
        // the view center (where the buffer center sits after the implicit
        // stretch) must map to the view center
        float[] c = QrPreviewGeom.mapPoint(m, viewW / 2f, viewH / 2f);
        assertEquals(label + ": matrix keeps the center (x)",
                viewW / 2f, c[0], EPS);
        assertEquals(label + ": matrix keeps the center (y)",
                viewH / 2f, c[1], EPS);
        // the STRETCHED buffer's top-left view position maps to the
        // upright content's top-left bound
        float[] tl = QrPreviewGeom.mapPoint(m, 0f, 0f);
        float[] br = QrPreviewGeom.mapPoint(m, viewW, viewH);
        float minX = Math.min(tl[0], br[0]);
        float maxX = Math.max(tl[0], br[0]);
        float minY = Math.min(tl[1], br[1]);
        float maxY = Math.max(tl[1], br[1]);
        // for quarter-turns the rotated corners ARE the bound hull — after
        // also mapping the other two corners, take the true hull
        float[] tr = QrPreviewGeom.mapPoint(m, viewW, 0f);
        float[] bl = QrPreviewGeom.mapPoint(m, 0f, viewH);
        minX = Math.min(minX, Math.min(tr[0], bl[0]));
        maxX = Math.max(maxX, Math.max(tr[0], bl[0]));
        minY = Math.min(minY, Math.min(tr[1], bl[1]));
        maxY = Math.max(maxY, Math.max(tr[1], bl[1]));
        assertEquals(label + ": matrix hull == content bounds (left)",
                b[0], minX, 1f);
        assertEquals(label + ": matrix hull == content bounds (top)",
                b[1], minY, 1f);
        assertEquals(label + ": matrix hull == content bounds (right)",
                b[2], maxX, 1f);
        assertEquals(label + ": matrix hull == content bounds (bottom)",
                b[3], maxY, 1f);
    }

    @Test
    public void portrait_allFourQuarterTurns_centeredAndCovering() {
        for (int sensor = 0; sensor < 360; sensor += 90) {
            int theta = QrPreviewGeom.theta(sensor, 0);
            assertCenteredCovers("portrait 720x1600 buf 1920x1080 theta=" + theta,
                    720, 1600, 1920, 1080, theta);
            assertMatrixMatchesBounds("portrait 720x1600 buf 1920x1080 theta=" + theta,
                    720, 1600, 1920, 1080, theta);
            assertCenteredCovers("portrait 1080x2340 buf 1920x1080 theta=" + theta,
                    1080, 2340, 1920, 1080, theta);
        }
    }

    @Test
    public void landscape_allFourQuarterTurns_centeredAndCovering() {
        for (int sensor = 0; sensor < 360; sensor += 90) {
            int theta = QrPreviewGeom.theta(sensor, 0);
            assertCenteredCovers("landscape 2340x1080 buf 1920x1080 theta=" + theta,
                    2340, 1080, 1920, 1080, theta);
            assertMatrixMatchesBounds("landscape 2340x1080 buf 1920x1080 theta=" + theta,
                    2340, 1080, 1920, 1080, theta);
            assertCenteredCovers("landscape 1600x720 buf 1280x720 theta=" + theta,
                    1600, 720, 1280, 720, theta);
        }
    }

    @Test
    public void displayRotation_shiftsTheta() {
        assertEquals(90, QrPreviewGeom.theta(90, 0));
        assertEquals(0, QrPreviewGeom.theta(90, 90));
        assertEquals(180, QrPreviewGeom.theta(90, 270));
        assertEquals(270, QrPreviewGeom.theta(0, 90));
    }

    @Test
    public void coverScale_fillsBothAxes_exactlyOneAxisTight() {
        // the classic cases from the v1.4.27 fills law
        assertEquals(2.5f, QrPreviewGeom.coverScale(720, 1600, 640, 480, 90), 1e-4f);
        assertEquals(1.25f, QrPreviewGeom.coverScale(720, 1600, 1280, 720, 90), 1e-4f);
        // theta 0 (upright buffer) fills too
        assertTrue(QrPreviewGeom.fills(720, 1600, 1280, 720, 0));
    }

    /** THE CORPSE DEMONSTRATION: the old buffer-space matrix (translate
     *  -buf/2, rotate, cover-scale against BUFFER dims, center) leaves the
     *  real view NOT covered once TextureView's implicit stretch applies
     *  (its matrix runs in view space) — the black-region symptom. The
     *  view-space matrix covers. Pinned so the corpse cannot silently
     *  return as a "simplification". */
    @Test
    public void oldBufferSpaceMatrix_theCorpse_doesNotCoverTheView() {
        float viewW = 720, viewH = 1600, bufW = 1920, bufH = 1080;
        int theta = 90;
        // the v1.4.26 recipe, computed in buffer space and then APPLIED in
        // view space (what TextureView does): the view rect's corners go
        // through T(-buf/2) R S T(c) — as the old code's matrix mapped
        // whatever points TextureView gave it (view points!)
        float s = QrPreviewGeom.coverScale(viewW, viewH, bufW, bufH, theta);
        double a = Math.toRadians(theta);
        float c = (float) Math.cos(a), sn = (float) Math.sin(a);
        // old matrix rows: x' = s(c x - sn y) + cx - s(c bufW/2 - sn bufH/2) ...
        float[] old = new float[] {
                s * c, -s * sn,
                viewW / 2f - s * (c * bufW / 2f - sn * bufH / 2f),
                s * sn, s * c,
                viewH / 2f - s * (sn * bufW / 2f + c * bufH / 2f)
        };
        // apply the OLD matrix to the VIEW rect (TextureView's real
        // semantics) and take the hull
        float[][] corners = {{0, 0}, {viewW, 0}, {viewW, viewH}, {0, viewH}};
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] p : corners) {
            float x = old[0] * p[0] + old[1] * p[1] + old[2];
            float y = old[3] * p[0] + old[4] * p[1] + old[5];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        boolean covered = minX <= 0.5f && maxX >= viewW - 0.5f
                && minY <= 0.5f && maxY >= viewH - 0.5f;
        assertTrue("the old buffer-space matrix leaves the real view "
                + "UNCOVERED (hull " + minX + ".." + maxX + " x " + minY + ".."
                + maxY + ") — the rotated/black-region corpse", !covered);
        // the v1.4.29 matrix covers
        assertTrue("the view-space matrix covers both axes",
                QrPreviewGeom.fills(viewW, viewH, bufW, bufH, theta));
    }

    /** degenerate input degrades to identity + a non-filling verdict —
     *  never a crash, never a bogus cover claim. */
    @Test
    public void degenerate_input_degradesHonestly() {
        assertEquals(0f, QrPreviewGeom.coverScale(720, 1600, 0, 0, 90), 1e-6f);
        assertTrue(!QrPreviewGeom.fills(720, 1600, 0, 0, 90));
        float[] m = QrPreviewGeom.matrix(720, 1600, 0, 0, 90);
        assertEquals(1f, m[0], 1e-6f);
        assertEquals(1f, m[4], 1e-6f);
    }

    /** the diagnostic label carries the content dims (the readable
     *  residual the field log needs). */
    @Test
    public void contentLabel_namesTheScaledContent() {
        assertEquals("1200x1600", QrPreviewGeom.contentLabel(720, 1600, 640, 480, 90));
    }
}
