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
import com.google.zxing.common.GlobalHistogramBinarizer;
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

    /** Decode a QR code from ARGB pixels. Returns the embedded text or null.
     *  v1.4.32 — THE DUAL-BINARIZER LAW: every leg tries HybridBinarizer
     *  THEN GlobalHistogramBinarizer (dot/rounded/stylized modules binarize
     *  better globally than on Hybrid's adaptive block grid — the
     *  chess.com-style corpse). Order: Hybrid plain, Hybrid invert,
     *  Global plain, Global invert. The extra attempts ride the FAILURE
     *  path only — a clean code still exits on the first attempt. */
    public static String decode(int[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height) return null;
        try {
            LuminanceSource src = new RGBLuminanceSource(width, height, pixels);
            return decodeDual(src);
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.32 — the dual-binarizer, plain+invert core EVERY leg rides:
     *  Hybrid plain → Hybrid invert → Global plain → Global invert. */
    private static String decodeDual(LuminanceSource src) {
        try {
            QRCodeReader reader = new QRCodeReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
            try {
                Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
                return r.getText();
            } catch (NotFoundException first) {
                try {
                    Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(src.invert())), hints);
                    return r.getText();
                } catch (NotFoundException second) {
                    try {
                        Result r = reader.decode(new BinaryBitmap(new GlobalHistogramBinarizer(src)), hints);
                        return r.getText();
                    } catch (NotFoundException third) {
                        try {
                            Result r = reader.decode(new BinaryBitmap(new GlobalHistogramBinarizer(src.invert())), hints);
                            return r.getText();
                        } catch (Throwable ignored) {
                            return null;
                        }
                    }
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
        // (c) v1.4.33 — THE DOT-CLOSING LEG (the final leg): dot/rounded
        // modules at ~50% duty cycle mis-bin under BOTH binarizers —
        // Hybrid's adaptive block grid and Global's histogram both slice
        // through the dots' soft edges and the finder runs come out
        // speckled. A morphological CLOSING (max-filter then min-filter,
        // 3x3) seals the dots into solid connected blobs before the
        // same dual-binarizer core rides again. Rides the FAILURE path
        // only — a plain QR exits on the first leg (the budget law).
        try {
            byte[] cl = close3x3(y, width, height);
            if (cl != null) {
                r = decodeLuma2(cl, width, height);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        // (d) v1.4.34 — THE QUARTER-SCALE CLOSING LEG: halfLuma twice, the
        // closing morphology, then the dual-binarizer core. The dense-dot
        // class (chess.com-style grids photographed at arm's length)
        // blurs at full AND half scale — the quarter plane sharpens the
        // module edges twice more and the closing merges what remains
        // into solid blobs the binarizers segment. Failure path only
        // (the budget law); the 128-edge keeps the quarter plane >= 64
        // (the ZXing detector floor the half leg carries).
        try {
            int hw = width / 2, hh = height / 2;
            if (hw >= 128 && hh >= 128) {
                byte[] hs = halfLuma(y, width, height);
                int qw = hw / 2, qh = hh / 2;
                if (hs != null && qw >= 64 && qh >= 64) {
                    byte[] qs = halfLuma(hs, hw, hh);
                    if (qs != null) {
                        byte[] cl = close3x3(qs, qw, qh);
                        if (cl != null) {
                            r = decodeLuma2(cl, qw, qh);
                            if (r != null) return r;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** the dual-binarizer two-attempt core (plain, invert, Global pair)
     *  every luma caller rides. v1.4.32 — Hybrid THEN Global per leg. */
    private static String decodeLuma2(byte[] y, int width, int height) {
        try {
            LuminanceSource src = new com.google.zxing.PlanarYUVLuminanceSource(
                    y, width, height, 0, 0, width, height, false);
            return decodeDual(src);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v1.4.33 — the PRE-CLOSED plane's entry: plain + invert through the
     * dual-binarizer core, for a caller that already ran its own
     * morphology (the live failure leg's dilate-only half-res plane).
     * No extraction, no ladder — the caller owns the geometry; this is
     * the core only. */
    public static String decodeClosed(byte[] y, int width, int height) {
        if (y == null || width <= 0 || height <= 0 || y.length < width * height) return null;
        return decodeLuma2(y, width, height);
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

    /** v1.4.33 — PURE (probe-pinned): the 3x3 MAX filter (grayscale
     *  dilation) — every pixel becomes the brightest of its 3x3
     *  neighborhood. Isolated dark modules shrink; the light background
     *  grows over single-pixel speckle. The closing leg's first pass. */
    public static byte[] dilate3x3(byte[] y, int width, int height) {
        try {
            if (y == null || width <= 2 || height <= 2
                    || y.length < width * height) return null;
            byte[] out = new byte[width * height];
            for (int r = 0; r < height; r++) {
                int r0 = Math.max(0, r - 1), r2 = Math.min(height - 1, r + 1);
                for (int c = 0; c < width; c++) {
                    int c0 = Math.max(0, c - 1), c2 = Math.min(width - 1, c + 1);
                    int m = 0;
                    for (int rr = r0; rr <= r2; rr++) {
                        int base = rr * width;
                        for (int cc = c0; cc <= c2; cc++) {
                            int v = y[base + cc] & 0xFF;
                            if (v > m) m = v;
                        }
                    }
                    out[r * width + c] = (byte) m;
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.33 — PURE (probe-pinned): the 3x3 MIN filter (grayscale
     *  erosion) — every pixel becomes the darkest of its 3x3 neighborhood.
     *  The closing leg's second pass (restores what the dilation grew). */
    private static byte[] erode3x3(byte[] y, int width, int height) {
        try {
            if (y == null || width <= 2 || height <= 2
                    || y.length < width * height) return null;
            byte[] out = new byte[width * height];
            for (int r = 0; r < height; r++) {
                int r0 = Math.max(0, r - 1), r2 = Math.min(height - 1, r + 1);
                for (int c = 0; c < width; c++) {
                    int c0 = Math.max(0, c - 1), c2 = Math.min(width - 1, c + 1);
                    int m = 255;
                    for (int rr = r0; rr <= r2; rr++) {
                        int base = rr * width;
                        for (int cc = c0; cc <= c2; cc++) {
                            int v = y[base + cc] & 0xFF;
                            if (v < m) m = v;
                        }
                    }
                    out[r * width + c] = (byte) m;
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.33 — PURE (probe-pinned): the morphological CLOSING of a luma
     *  plane — dilate3x3 then erode3x3. Dark dot modules at ~50% duty
     *  merge into solid connected blobs the binarizers can segment; thin
     *  light gaps BETWEEN dots stay light (a closing never widens a dark
     *  structure past its dilation). The dot-closing leg's plane. */
    public static byte[] close3x3(byte[] y, int width, int height) {
        try {
            byte[] d = dilate3x3(y, width, height);
            if (d == null) return null;
            byte[] e = erode3x3(d, width, height);
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.33 — PURE (probe-pinned): the MAX chroma spread of a chroma
     *  plane — the degeneracy check. A plane whose brightest chroma
     *  distance is under 8 carries no hue structure at all (a flat/gray
     *  scene): the chroma leg SKIPS it (the skip law — decoding a flat
     *  plane through four binarizer runs buys nothing). */
    public static int chromaMaxSpread(byte[] chroma) {
        try {
            if (chroma == null) return 0;
            int m = 0;
            for (byte b : chroma) {
                int v = b & 0xFF;
                if (v > m) m = v;
            }
            return m;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * v1.4.32 — THE CHROMA-SPREAD PLANE (PURE, probe-pinned; the
     * colour-on-colour corpse: the live path decoded ONLY the Y plane, so
     * modules with EQUAL luma but different hue — blue-on-green — were
     * literally invisible to every binarizer). Builds |U-128| + |V-128|
     * per pixel from tight, already-de-strided half-resolution U/V planes
     * (YUV420 chroma is natively subsampled 2): two hues of equal luma
     * separate on chroma distance even when luma cannot see them. Returns
     * null on a degenerate pair. The caller decodes the result through
     * {@link #decodeChroma} — the FINAL leg of the luma ladder (plain +
     * invert, dual-binarizer), reached only after plain+invert+stretch+
     * half all fail (the runtime-budget law: the U/V extraction and this
     * plane run ONLY on that failure leg, keeping the 4 fps budget).
     */
    public static byte[] chromaOf(byte[] u, byte[] v, int cw, int ch) {
        try {
            if (u == null || v == null || cw <= 0 || ch <= 0
                    || u.length < cw * ch || v.length < cw * ch) return null;
            int n = cw * ch;
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                int du = (u[i] & 0xFF) - 128;
                int dv = (v[i] & 0xFF) - 128;
                int s = Math.abs(du) + Math.abs(dv);
                out[i] = (byte) (s > 255 ? 255 : s);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v1.4.32 — decode the CHROMA-SPREAD plane (the luma ladder's final
     * leg): plain + invert through the dual-binarizer core. PURE w.r.t.
     * the plane (no extraction here — the caller owns when U/V leave the
     * camera frame).
     * v1.4.33 — THE DEGENERATE SKIP: a chroma plane whose max spread is
     * under 8 is hue-FLAT (a gray/monochrome scene — every |U-128|+|V-128|
     * read noise-level) — decodeChroma returns null without spending the
     * four binarizer runs; the ladder moves on (to the closing leg).
     */
    public static String decodeChroma(byte[] chroma, int cw, int ch) {
        if (chroma == null || cw <= 0 || ch <= 0 || chroma.length < cw * ch) return null;
        if (chromaMaxSpread(chroma) < 8) return null;   // the degenerate skip
        return decodeLuma2(chroma, cw, ch);
    }

    /**
     * v1.4.32 — PURE (probe-pinned): the chroma-spread plane of an ARGB
     * int[] buffer at HALF resolution (nearest-neighbour 2:1 subsample) —
     * the gallery leg. Per pixel the BT.601 chroma spread
     * |U-128| + |V-128| (U = (-169R - 331G + 500B)/1000 + 128,
     * V = (500R - 419G - 81B)/1000 + 128) — the same measure the live
     * YUV planes ride, derived from the RGB triple when no real U/V plane
     * exists. Output dims: ceil(w/2) x ceil(h/2).
     */
    public static byte[] chromaOfArgb(int[] px, int width, int height) {
        try {
            if (px == null || width <= 0 || height <= 0
                    || px.length < width * height) return null;
            int cw = (width + 1) / 2, ch = (height + 1) / 2;
            byte[] out = new byte[cw * ch];
            for (int r = 0; r < ch; r++) {
                int sr = Math.min(2 * r, height - 1);
                for (int c = 0; c < cw; c++) {
                    int sc = Math.min(2 * c, width - 1);
                    int p = px[sr * width + sc];
                    int red = p & 0xFF, grn = (p >> 8) & 0xFF, blu = (p >> 16) & 0xFF;
                    int du = (-169 * red - 331 * grn + 500 * blu) / 1000;
                    int dv = (500 * red - 419 * grn - 81 * blu) / 1000;
                    int s = Math.abs(du) + Math.abs(dv);
                    out[r * cw + c] = (byte) (s > 255 ? 255 : s);
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
        byte[] fullLuma = null;
        try {
            byte[] y = lumaOf(pixels, width, height);
            fullLuma = y;
            r = decodeLuma(y, width, height);
            if (r != null) return r;
        } catch (Throwable ignored) {}
        // v1.4.32 — THE CHROMA LEG (the gallery colour-on-colour corpse):
        // after the ARGB pair and the full luma ladder both fail, the
        // half-resolution chroma-spread plane gets its plain+invert,
        // dual-binarizer pass at the same scale the caller handed over.
        // v1.4.33 — the leg SKIPS a degenerate plane (max spread < 8 —
        // decodeChroma refuses flat hue) instead of spending its runs.
        try {
            byte[] chroma = chromaOfArgb(pixels, width, height);
            if (chroma != null) {
                r = decodeChroma(chroma, (width + 1) / 2, (height + 1) / 2);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        // v1.4.33 — THE FINAL CLOSING LEG (after the chroma leg, per the
        // dot-closing law): the chroma plane said "no hue structure" (or
        // simply missed) — the last geometry is the HALF-scale closed
        // luma plane: closing merges the dots AND the half-scale
        // sharpens module edges, the pair that cracks chess.com-style
        // dot grids at photo resolution. Rides the failure path only.
        // v1.4.34 — the leg grew the QUARTER-scale closed pass (the
        // dense-dot class blurs through full AND half; the quarter plane
        // is the final geometry that still carries the finder runs).
        try {
            int hw = width / 2, hh = height / 2;
            if (fullLuma != null && hw >= 64 && hh >= 64) {
                byte[] hs = halfLuma(fullLuma, width, height);
                if (hs != null) {
                    byte[] cl = close3x3(hs, hw, hh);
                    if (cl != null) {
                        r = decodeLuma2(cl, hw, hh);
                        if (r != null) return r;
                    }
                    int qw = hw / 2, qh = hh / 2;
                    if (qw >= 64 && qh >= 64) {
                        byte[] qs = halfLuma(hs, hw, hh);
                        if (qs != null) {
                            byte[] cl2 = close3x3(qs, qw, qh);
                            if (cl2 != null) {
                                r = decodeLuma2(cl2, qw, qh);
                                if (r != null) return r;
                            }
                        }
                    }
                }
            }
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
     * CLOCKWISE (the source's bottom-left becomes the destination's
     * top-left). dst(x', y') = src(x = y', y = H-1-x'): w x h -> h x w.
     * The belt-and-braces pass for a photo whose code was scanned sideways
     * (ZXing infers orientation from the finder patterns, but a
     * stretched/exif-flipped photo can still lose them; the cheap
     * rotations cost nothing on a one-shot decode).
     */
    public static byte[] rotate90Luma(byte[] y, int width, int height) {
        if (y == null || width <= 0 || height <= 0 || y.length < width * height) return null;
        int dw = height;                  // the rotated plane is h wide...
        int dh = width;                   // ...and w high
        byte[] out = new byte[dw * dh];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                // src(row r, col c) -> dst(x' = c, y' = H-1-r)
                out[c * dw + (height - 1 - r)] = y[r * width + c];
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
        // (3) the CROPS: a QR occupying part of a photo keeps its module
        // pixels only inside a crop, and HybridBinarizer's block grid is
        // geometry-sensitive — so the ladder spans TWO granularities: the
        // 80% center window + the four ~55% quadrant windows, then the
        // five thirds (four corners + center, 1/3 x 1/3 each — a code
        // a quarter of the frame sits tight inside one of them). Each
        // through [decodeMulti]. Every pass is bounded and failure moves on.
        try {
            int m10 = width / 10;
            int v10 = height / 10;
            int w3 = width / 3, h3 = height / 3;
            int[][] wins = {
                    {m10, v10, width - m10, height - v10},          // center 80%x80%
                    {0, 0, width / 2 + m10, height / 2 + v10},     // top-left
                    {width / 2 - m10, 0, width, height / 2 + v10},  // top-right
                    {0, height / 2 - v10, width / 2 + m10, height},// bottom-left
                    {width / 2 - m10, height / 2 - v10, width, height}, // bottom-right
                    {0, 0, w3, h3},                                 // the tight thirds
                    {2 * w3, 0, width, h3},
                    {0, 2 * h3, w3, height},
                    {2 * w3, 2 * h3, width, height},
                    {w3, h3, 2 * w3, 2 * h3}
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
