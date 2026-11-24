package com.bolt.browser.util;

/**
 * v1.4.29 — PURE QR-preview transform geometry (probe-pinned:
 * scripts/test_v1429.py compiles + runs THIS file; the Robolectric
 * QrPreviewGeomTest covers portrait/landscape x the four quarter-turns).
 *
 * <p>THE CORPSE (the "preview rotated / black lower half" field report):
 * {@code TextureView.setTransform()} transforms VIEW-LOCAL geometry. The
 * SurfaceTexture buffer is first stretched to fill the view (the implicit
 * buffer-to-view stretch: buffer {@code (bufW, bufH)} is drawn onto the
 * view rect {@code (viewW, viewH)}), and the transform matrix then maps
 * view coordinates to view coordinates. The v1.4.26 matrix was built in
 * BUFFER coordinates — translate by {@code -bufW/2}, rotate, cover-scale
 * against the buffer dims — so whatever "filled the view" in buffer space
 * did not cover the actual view once applied: the content landed rotated
 * and mis-scaled inside the view, leaving black regions (the bottom-half
 * black panel on the portrait Realme).
 *
 * <p>THE LAW (v1.4.29): compute the transform in the coordinates
 * TextureView consumes. With {@code F} the implicit stretch
 * ({@code F(x,y) = (x*viewW/bufW, y*viewH/bufH)}) and {@code G} the
 * desired buffer-to-view mapping (center the buffer, rotate by
 * {@code theta}, uniformly cover-scale, center in the view), the matrix
 * is {@code M = G o F^-1}:
 *
 * <pre>
 *   M = T(viewW/2, viewH/2) . S(s) . R(theta) . T(-bufW/2, -bufH/2) . S(bufW/viewW, bufH/viewH)
 * </pre>
 *
 * <p>the rightmost {@code S} undoing the implicit stretch FIRST (it is
 * applied to the point first). {@code s} is the classic cover scale
 * {@code max(viewW/uw, viewH/uh)} over the upright content dims — the
 * scaled upright content then covers BOTH view axes and is exactly
 * centered. No Android types: this file must compile and run under a
 * bare javac/java for the release gates.
 */
public final class QrPreviewGeom {

    private QrPreviewGeom() {}

    /** the upright rotation: the classic JPEG-orientation formula
     *  (sensor orientation minus display rotation), quarter-turns only. */
    public static int theta(int sensorOrientation, int displayDegrees) {
        return ((sensorOrientation - displayDegrees) % 360 + 360) % 360;
    }

    /** the upright content WIDTH after rotating the raw buffer by theta. */
    public static float uprightW(float bufW, float bufH, int theta) {
        return theta % 180 == 0 ? bufW : bufH;
    }

    /** the upright content HEIGHT after rotating the raw buffer by theta. */
    public static float uprightH(float bufW, float bufH, int theta) {
        return theta % 180 == 0 ? bufH : bufW;
    }

    /** the cover scale: the scaled upright content is >= the view on BOTH
     *  axes (aspect preserved), one axis fitting exactly. 0 on degenerate
     *  input (no transform can cover a zero-sized view or buffer). */
    public static float coverScale(float viewW, float viewH,
                                   float bufW, float bufH, int theta) {
        float uw = uprightW(bufW, bufH, theta);
        float uh = uprightH(bufW, bufH, theta);
        if (viewW <= 0f || viewH <= 0f || uw <= 0f || uh <= 0f) return 0f;
        return Math.max(viewW / uw, viewH / uh);
    }

    /** the fills-both-axes invariant of the cover recipe (the v1.4.27
     *  assertion, in pure form): the scaled upright content is >= the
     *  view on width and height alike. */
    public static boolean fills(float viewW, float viewH,
                                float bufW, float bufH, int theta) {
        float s = coverScale(viewW, viewH, bufW, bufH, theta);
        if (s <= 0f) return false;
        float uw = uprightW(bufW, bufH, theta);
        float uh = uprightH(bufW, bufH, theta);
        return uw * s >= viewW - 1f && uh * s >= viewH - 1f;
    }

    /**
     * THE VIEW-SPACE MATRIX — the nine values in
     * {@code android.graphics.Matrix.getValues()} order
     * (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y,
     * MPERSP_0, MPERSP_1, MPERSP_2), fed to
     * {@code TextureView.setTransform()} via {@code Matrix.setValues()}.
     *
     * <p>Expanded from
     * {@code T(viewW/2, viewH/2) . S(s) . R(theta) . T(-bufW/2, -bufH/2) . S(bufW/viewW, bufH/viewH)}
     * — the rightmost stretch-undo is what the v1.4.26 recipe lacked.
     * On degenerate input the identity is returned (the preview keeps its
     * default fill-the-view mapping instead of collapsing).
     */
    public static float[] matrix(float viewW, float viewH,
                                 float bufW, float bufH, int theta) {
        float s = coverScale(viewW, viewH, bufW, bufH, theta);
        float[] id = {1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
        if (s <= 0f) return id;
        double a = Math.toRadians(theta);
        double c = Math.cos(a), sn = Math.sin(a);
        float ex = bufW / viewW;   // F^-1: the implicit stretch undone
        float ey = bufH / viewH;
        float[] m = new float[9];
        m[0] = (float) (s * c * ex);              // MSCALE_X
        m[1] = (float) (-s * sn * ey);            // MSKEW_X
        m[2] = viewW / 2f - (float) (s * c * bufW / 2.0)
                          + (float) (s * sn * bufH / 2.0);   // MTRANS_X
        m[3] = (float) (s * sn * ex);             // MSKEW_Y
        m[4] = (float) (s * c * ey);              // MSCALE_Y
        m[5] = viewH / 2f - (float) (s * sn * bufW / 2.0)
                          - (float) (s * c * bufH / 2.0);   // MTRANS_Y
        m[6] = 0f;
        m[7] = 0f;
        m[8] = 1f;
        return m;
    }

    /** affine map of one VIEW-space point through a [9]-values matrix
     *  (probe helper; the runtime feeds the same values to setTransform). */
    public static float[] mapPoint(float[] m, float x, float y) {
        return new float[] {
                m[0] * x + m[1] * y + m[2],
                m[3] * x + m[4] * y + m[5]
        };
    }

    /**
     * the displayed content bounds in view coordinates after the
     * transform: {left, top, right, bottom}, centered on the view and
     * covering both axes (the cover recipe's own geometry — the probe
     * asserts centered + covering for portrait/landscape x the four
     * quarter-turns).
     */
    public static float[] contentBounds(float viewW, float viewH,
                                        float bufW, float bufH, int theta) {
        float s = coverScale(viewW, viewH, bufW, bufH, theta);
        float cw = uprightW(bufW, bufH, theta) * s;
        float ch = uprightH(bufW, bufH, theta) * s;
        float cx = viewW / 2f, cy = viewH / 2f;
        return new float[] {cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f};
    }

    /** the readable residual for the diag line: "content=WxH". */
    public static String contentLabel(float viewW, float viewH,
                                      float bufW, float bufH, int theta) {
        float s = coverScale(viewW, viewH, bufW, bufH, theta);
        int cw = Math.round(uprightW(bufW, bufH, theta) * s);
        int ch = Math.round(uprightH(bufW, bufH, theta) * s);
        return cw + "x" + ch;
    }
}
