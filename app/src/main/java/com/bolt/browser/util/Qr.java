package com.bolt.browser.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.InputStream;

/**
 * v1.3.32 — Android glue over {@link QrCore}: Bitmap ↔ int[] pixels.
 *
 * The Google-transfer flow never needs a live camera — the Google
 * Authenticator export QR is shown on THIS phone, so the natural capture is
 * a screenshot (or a photo of a second device's screen), picked through the
 * system image picker (ACTION_GET_CONTENT needs no runtime permission).
 * Big screenshots are downsampled before decode: ZXing works on 1–2 MP
 * images quickly, but a 4 MP+ buffer is needlessly slow on a 3 GB device.
 */
public final class Qr {

    private Qr() {}

    /** Decode a QR from a Bitmap (returns null when none found). */
    public static String decode(Bitmap bmp) {
        if (bmp == null) return null;
        Bitmap b = bmp;
        // downsample very large images (photos of another screen)
        int maxDim = Math.max(b.getWidth(), b.getHeight());
        if (maxDim > 2000) {
            float scale = 1600f / maxDim;
            b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale),
                    Math.round(b.getHeight() * scale), true);
        }
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        return QrCore.decode(px, w, h);
    }

    /**
     * Open the picked image (content:// or file://) and decode any QR
     * inside it. Returns the QR text or null (absent / unreadable / IO
     * error).
     * v1.4.27 — THE ROBUST GALLERY LADDER (the "photo-from-device not
     * detected" corpse): the old flow decoded ONCE at ~2000 px and ran the
     * plain two-attempt decode on it — a QR that occupies part of a large
     * or stylized/low-contrast photo never survived. The new flow:
     * (1) FULL resolution first — the smallest inSampleSize that keeps
     *     the decode inside the memory law (8 MP int[] = 32 MB peak on
     *     the 3 GB device floor);
     * (2) that pass rides the SAME multi-pass kit the camera uses
     *     ([QrCore.decodeRobust]: plain + inverted ARGB, the luma
     *     contrast-stretch + half-scale passes, the rotations, and the
     *     center-crop windows for a code that occupies part of the
     *     frame);
     * (3) progressive downscales ([maxDim], 1200, 800) each through the
     *     same robust ladder — a small code on a huge photo sometimes
     *     needs the downsampled geometry to binarize at all.
     */
    public static String decodeUri(android.content.Context ctx, Uri uri, int maxDim) {
        Bitmap base = null;
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            try { in.close(); } catch (Throwable ignored) {}
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            // the memory law: the smallest sample holding the decode under
            // ~8 MP (a 12 MP photo rides 1/4 area — still its own pixels
            // for any code a fifth of the frame)
            int sample = 1;
            while ((long) (w / sample) * (h / sample) > 8_000_000L) sample *= 2;
            InputStream in2 = ctx.getContentResolver().openInputStream(uri);
            if (in2 == null) return null;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            base = BitmapFactory.decodeStream(in2, null, o2);
            try { in2.close(); } catch (Throwable ignored) {}
            if (base == null) return null;
            // (1) the full-resolution robust pass
            int bw = base.getWidth(), bh = base.getHeight();
            int[] px = new int[bw * bh];
            base.getPixels(px, 0, bw, 0, 0, bw, bh);
            String r = QrCore.decodeRobust(px, bw, bh);
            if (r != null) return r;
            // (2) the progressive downscales (maxDim -> 1200 -> 800)
            int longEdge = Math.max(bw, bh);
            int[] rungs = {Math.max(600, maxDim), 1200, 800};
            for (int target : rungs) {
                if (longEdge <= target + 80) continue;   // effectively same scale
                float f = (float) target / longEdge;
                int sw = Math.max(1, Math.round(bw * f));
                int sh = Math.max(1, Math.round(bh * f));
                Bitmap s = null;
                try {
                    s = Bitmap.createScaledBitmap(base, sw, sh, true);
                    if (s == null || s == base) continue;
                    int[] spx = new int[sw * sh];
                    s.getPixels(spx, 0, sw, 0, 0, sw, sh);
                    r = QrCore.decodeRobust(spx, sw, sh);
                    if (r != null) return r;
                } finally {
                    if (s != null && s != base) try { s.recycle(); } catch (Throwable ignored) {}
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (base != null) try { base.recycle(); } catch (Throwable ignored) {}
        }
    }

    /** Render text as a QR Bitmap (black on white — scanners require the
     *  light background regardless of the app theme). Null when undecodable. */
    public static Bitmap encodeBitmap(String text, int sizePx) {
        int[] px = QrCore.encode(text, sizePx);
        if (px == null) return null;
        return Bitmap.createBitmap(px, sizePx, sizePx, Bitmap.Config.ARGB_8888);
    }
}
