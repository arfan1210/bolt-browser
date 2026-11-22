package com.bolt.browser.util;

import java.util.EnumMap;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * v1.3.32 — the QR engine behind the authenticator's Google-transfer flow,
 * split from the Android glue ({@link Qr}) so it compiles standalone for
 * scripts/test_qr.py (decode-from-pixels + encode-to-pixels, both directions
 * exercised offline).
 *
 * Decode: int[] ARGB pixels (exactly what Bitmap.getPixels produces) →
 * RGBLuminanceSource → HybridBinarizer → QRCodeReader with TRY_HARDER.
 * Screenshots of the Google Authenticator export QR are clean, but photos
 * taken of another screen benefit from the extra effort.
 *
 * Encode: text → QR (error correction M — the level Google Authenticator
 * itself uses when showing setup QRs) → int[] ARGB pixels (0xFF000000 /
 * 0xFFFFFFFF) with a 4-module quiet zone, ready for Bitmap.createBitmap.
 */
public final class QrCore {

    private QrCore() {}

    /** Decode a QR code from ARGB pixels. Returns the embedded text or null. */
    public static String decode(int[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height) return null;
        try {
            LuminanceSource src = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
            QRCodeReader reader = new QRCodeReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
            try {
                Result r = reader.decode(bmp, hints);
                return r.getText();
            } catch (NotFoundException first) {
                // one retry inverted (dark screenshots / night-mode screens can flip)
                try {
                    Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(src.invert())), hints);
                    return r.getText();
                } catch (Throwable ignored) {
                    return null;
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v1.3.37 — decode a QR from a RAW LUMINANCE plane (the Y of a camera
     * YUV_420_888 frame, already de-strided by the caller). Goes straight to
     * PlanarYUVLuminanceSource — no int[] ARGB conversion, no extra copy —
     * so live scanning stays cheap on 3 GB devices. Same hint set + inverted
     * retry as the ARGB path. In-plane rotation is fine: the ZXing detector
     * infers orientation from the three finder patterns, so a portrait-held
     * phone reading an upright code (sensor frame rotated 90°) just works.
     * v1.4.26 — THE STYLIZED PASSES (the weak-on-stylized-codes field
     * report): after the plain + inverted attempts, (a) a 2/98-percentile
     * CONTRAST STRETCH of the luma plane (stylized/colored codes with
     * tinted or low-contrast modules binarize far better once the range is
     * blown out) and (b) a HALF-SCALE pass (the ZXing recommendation for
     * big/blurry/dense codes — a nearest-neighbour 2:1 subsample sharpens
     * module edges relative to finder patterns). Each pass carries its own
     * inverted retry. Probe-pinned in scripts/test_v1426.py.
     */
    public static String decodeLuma(byte[] y, int width, int height) {
        if (y == null || width <= 0 || height <= 0 || y.length < width * height) return null;
        String r = decodeLuma2(y, width, height);
        if (r != null) return r;
        // (a) the stretched plane
        try {
            byte[] st = stretchLuma(y, width, height);
            if (st != null) {
                r = decodeLuma2(st, width, height);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        // (b) the half-scale plane
        try {
            int hw = width / 2, hh = height / 2;
            if (hw >= 64 && hh >= 64) {
                byte[] hs = halfLuma(y, width, height);
                if (hs != null) {
                    r = decodeLuma2(hs, hw, hh);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** the two-attempt core (plain, then inverted) both higher callers ride. */
    private static String decodeLuma2(byte[] y, int width, int height) {
        try {
            LuminanceSource src = new com.google.zxing.PlanarYUVLuminanceSource(
                    y, width, height, 0, 0, width, height, false);
            BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
            QRCodeReader reader = new QRCodeReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
            try {
                Result r = reader.decode(bmp, hints);
                return r.getText();
            } catch (NotFoundException first) {
                try {
                    Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(src.invert())), hints);
                    return r.getText();
                } catch (Throwable ignored) {
                    return null;
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v1.4.26 — PURE (probe-pinned): the 2/98-percentile contrast stretch of
     * a luma plane. A 256-bin histogram over the whole plane; lum <= p2
     * maps to 0, lum >= p98 maps to 255, linear between (same law the
     * screen-translate tiles ride). Returns null when the plane is already
     * contrasty (p98 - p2 < 24) or the histogram is degenerate.
     */
    public static byte[] stretchLuma(byte[] y, int width, int height) {
        try {
            if (y == null || width <= 0 || height <= 0 || y.length < width * height) return null;
            int[] hist = new int[256];
            int n = width * height;
            for (int i = 0; i < n; i++) {
                int v = y[i] & 0xFF;
                hist[v]++;
            }
            int p2 = 0, p98 = 255;
            long acc = 0;
            for (int i = 0; i < 256; i++) {
                acc += hist[i];
                if (acc >= (long) n * 2 / 100) { p2 = i; break; }
            }
            acc = 0;
            for (int i = 255; i >= 0; i--) {
                acc += hist[i];
                if (acc >= (long) n * 2 / 100) { p98 = i; break; }
            }
            if (p98 - p2 < 24) return null;
            int[] map = new int[256];
            for (int i = 0; i < 256; i++) {
                int v = (i - p2) * 255 / (p98 - p2);
                map[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
            }
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = (byte) map[y[i] & 0xFF];
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.26 — PURE (probe-pinned): the nearest-neighbour 2:1 subsample
     *  of a luma plane (even rows/cols) — the big/blurry-code pass. */
    public static byte[] halfLuma(byte[] y, int width, int height) {
        try {
            if (y == null || width < 2 || height < 2 || y.length < width * height) return null;
            int hw = width / 2, hh = height / 2;
            byte[] out = new byte[hw * hh];
            for (int r = 0; r < hh; r++) {
                for (int c = 0; c < hw; c++) {
                    out[r * hw + c] = y[(2 * r) * width + (2 * c)];
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v1.4.27 — THE ARGB MULTI-PASS (the photo-from-device corpse): the
     * gallery path's decode used to be the plain two-attempt ARGB call on
     * one 2000px downsample — a stylized / low-contrast QR inside a large
     * photo never survived it. This is the SAME robust ladder the camera
     * rides, lifted to ARGB pixels: (1) the plain + inverted RGB pass,
     * then (2) the LUMINANCE ladder on the BT.601 plane — plain + inverted,
     * the 2/98 percentile CONTRAST STRETCH (each with its inverted retry)
     * and the HALF-SCALE pass (the stylized-code kit, probe-pinned in
     * scripts/test_v1426.py).
     */
    public static String decodeMulti(int[] pixels, int width, int height) {
        String r = decode(pixels, width, height);
        if (r != null) return r;
        try {
            byte[] y = lumaOf(pixels, width, height);
            r = decodeLuma(y, width, height);
            if (r != null) return r;
        } catch (Throwable ignored) {}
        return null;
    }

    /** v1.4.27 — PURE (probe-pinned): the BT.601 luma plane of an ARGB
     *  int[] buffer (the exact formula the stretch/half passes ride). */
    public static byte[] lumaOf(int[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0
                || pixels.length < width * height) return null;
        byte[] y = new byte[width * height];
        for (int i = 0; i < width * height; i++) {
            int p = pixels[i];
            y[i] = (byte) ((299 * (p & 0xFF) + 587 * ((p >> 8) & 0xFF)
                    + 114 * ((p >> 16) & 0xFF)) / 1000);
        }
        return y;
    }

    /**
     * v1.4.27 — PURE (probe-pinned): rotate a luma plane 90 degrees
     * clockwise. Out(x', y') = In(y, h-1-x) — the belt-and-braces pass for
     * a photo whose code was scanned sideways (ZXing infers orientation
     * from the finder patterns, but a stretched/exif-flipped photo can
     * still lose them; two cheap rotations cost nothing on a one-shot
     * decode). w x h -> h x w.
     */
    public static byte[] rotate90Luma(byte[] y, int width, int height) {
        if (y == null || width <= 0 || height <= 0 || y.length < width * height) return null;
        int hw = height, hh = width;
        byte[] out = new byte[hw * hh];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                // (r, c) -> (c, height-1-r) in the rotated plane
                out[(height - 1 - r) * hw + c] = y[r * width + c];
            }
        }
        return out;
    }

    /**
     * v1.4.27 — PURE (probe-pinned): a sub-rectangle crop of an ARGB buffer
     * (l, t, r, b — right/bottom EXCLUSIVE, clamped to the source). Null
     * on a degenerate window.
     */
    public static int[] cropArgb(int[] px, int w, int h, int l, int t, int r, int b) {
        if (px == null || w <= 0 || h <= 0) return null;
        if (l < 0) l = 0;
        if (t < 0) t = 0;
        if (r > w) r = w;
        if (b > h) b = h;
        if (r - l < 8 || b - t < 8) return null;
        int cw = r - l, ch = b - t;
        int[] out = new int[cw * ch];
        for (int row = 0; row < ch; row++) {
            System.arraycopy(px, (t + row) * w + l, out, row * cw, cw);
        }
        return out;
    }

    /**
     * v1.4.27 — THE ROBUST PHOTO LADDER (the "photo-from-device not
     * detected" corpse): the camera's full kit on a picked photo, at the
     * scale the caller hands over (the gallery path tries FULL resolution
     * first, then progressive downscales). The ladder, in order:
     *  (1) the whole image through [decodeMulti] (plain + inverted ARGB,
     *      then the luma stretch/half passes);
     *  (2) the 90/180/270 rotations of the luma plane through the
     *      two-attempt core (belt-and-braces for a sideways scan);
     *  (3) CENTER-CROPS at this scale — a QR occupying only part of a
     *      photo keeps its module pixels only inside a crop: the center
     *      70% window, then the four 50% quadrant windows, each through
     *      [decodeMulti]. Every pass is bounded and failure moves on.
     */
    public static String decodeRobust(int[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0) return null;
        try {
            String r = decodeMulti(pixels, width, height);
            if (r != null) return r;
        } catch (Throwable ignored) {}
        // (2) the rotations (two-attempt core each — the finder patterns
        // usually self-orient; this is the cheap belt-and-braces pass)
        try {
            byte[] y0 = lumaOf(pixels, width, height);
            if (y0 != null) {
                byte[] y90 = rotate90Luma(y0, width, height);
                if (y90 != null) {
                    int w90 = height, h90 = width;
                    String r = decodeLuma2(y90, w90, h90);
                    if (r != null) return r;
                    byte[] y180 = rotate90Luma(y90, w90, h90);
                    if (y180 != null) {
                        r = decodeLuma2(y180, width, height);
                        if (r != null) return r;
                        byte[] y270 = rotate90Luma(y180, width, height);
                        if (y270 != null) {
                            r = decodeLuma2(y270, w90, h90);
                            if (r != null) return r;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        // (3) the center-crops: the 80% center window + the four ~50%+ quadrant
        // windows (a QR occupying part of a photo survives at CROP scale)
        try {
            int m10 = width / 10;
            int v10 = height / 10;
            int[][] wins = {
                    {m10, v10, width - m10, height - v10},          // center 80%x80%
                    {0, 0, width / 2 + m10, height / 2 + v10},       // top-left
                    {width / 2 - m10, 0, width, height / 2 + v10},  // top-right
                    {0, height / 2 - v10, width / 2 + m10, height},// bottom-left
                    {width / 2 - m10, height / 2 - v10, width, height} // bottom-right
            };
            for (int[] win : wins) {
                int[] crop = cropArgb(pixels, width, height, win[0], win[1], win[2], win[3]);
                if (crop == null) continue;
                String r = decodeMulti(crop, win[2] - win[0], win[3] - win[1]);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Encode text into a QR code as ARGB pixels (black modules on white).
     * Returns null when the text cannot be encoded (empty / too long).
     */
    public static int[] encode(String text, int sizePx) {
        try {
            if (text == null || text.isEmpty() || sizePx < 64) return null;
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 4);          // 4-module quiet zone
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            com.google.zxing.common.BitMatrix m = new QRCodeWriter()
                    .encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int[] out = new int[sizePx * sizePx];
            for (int y = 0; y < sizePx; y++) {
                for (int x = 0; x < sizePx; x++) {
                    out[y * sizePx + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
