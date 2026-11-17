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
     */
    public static String decodeLuma(byte[] y, int width, int height) {
        if (y == null || width <= 0 || height <= 0 || y.length < width * height) return null;
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
