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

    /** v1.4.28 — the full-resolution ceiling for the gallery ladder's FIRST
     *  pass: photos up to this size ride their OWN pixels before any
     *  downsample (a 12 MP photo = 48 MB int[] + bitmap — accepted for a
     *  one-shot gallery decode; a 24 MP photo = 96 MB still clears on the
     *  3 GB floor). Above the ceiling the full pass starts at the memory
     *  law's sample instead — a 50 MP panorama never OOMs the pick. */
    private static final long FULL_RES_MAX = 24_000_000L;

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
    /**
     * v1.4.27 — THE ROBUST GALLERY LADDER (the "photo-from-device not
     *  detected" corpse): the old flow decoded ONCE at ~2000 px and ran the
     *  plain two-attempt decode on it. Every scale rides the SAME multi-
     *  pass kit the camera uses ([QrCore.decodeRobust]: plain + inverted
     *  ARGB, the luma contrast-stretch + half-scale passes, the 0/90/180/
     *  270 rotations, and the center-crop windows for a code that occupies
     *  part of the frame).
     *  v1.4.28 — FULL RESOLUTION FIRST (the "stylized QR in a 12 MP photo
     *  not detected" corpse): the v1.4.27 ladder still decimated a 12 MP
     *  photo to ≤8 MP (sample 2 — every module pixel HALVED) before the
     *  first pass, and a small code in a big photo lost its geometry before
     *  ANY pass ran. The ladder now opens with the TRUE full-resolution
     *  pass (sample 1, memory cost accepted for the one-shot gallery pick,
     *  bounded at [FULL_RES_MAX] for the OOM-panorama floor), THEN the
     *  memory-law sample (≤8 MP), THEN the downscales — every scale through
     *  the full robust kit (rotations + crops at EACH scale).
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
            // the memory law for the LATER rungs: the smallest sample holding
            // the decode under ~8 MP (a 12 MP photo rides 1/4 area at sample 2)
            int sample = 1;
            while ((long) (w / sample) * (h / sample) > 8_000_000L) sample *= 2;
            // (1) FULL RESOLUTION FIRST — the true pixels, the memory cost
            // accepted for the gallery pick; bounded at FULL_RES_MAX so a
            // 50 MP panorama starts at the memory law instead of OOMing
            long mp = (long) w * h;
            if (mp <= FULL_RES_MAX && sample > 1) {
                try {
                    String r = decodeAtSample(ctx, uri, 1);
                    if (r != null) return r;
                } catch (Throwable ignored) {}   // OOM etc: the ladder continues
            }
            // (2) the memory-law pass (≤8 MP) + the progressive downscales
            // (maxDim -> 1200 -> 800)
            base = decodeBitmapAtSample(ctx, uri, sample);
            if (base == null) return null;
            return decodeLadder(base, maxDim);
        } catch (Throwable t) {
            return null;
        } finally {
            if (base != null) try { base.recycle(); } catch (Throwable ignored) {}
        }
    }

    /** v1.4.28 — open + decode the picked image at one sample size (null
     *  when unreadable); the caller recycles. */
    private static Bitmap decodeBitmapAtSample(android.content.Context ctx, Uri uri,
                                               int sample) {
        try {
            InputStream in2 = ctx.getContentResolver().openInputStream(uri);
            if (in2 == null) return null;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeStream(in2, null, o2);
            try { in2.close(); } catch (Throwable ignored) {}
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.28 — the full-resolution FIRST pass on its own bitmap (the
     *  caller holds no base bitmap yet at that point). */
    private static String decodeAtSample(android.content.Context ctx, Uri uri, int sample) {
        Bitmap b = decodeBitmapAtSample(ctx, uri, sample);
        if (b == null) return null;
        try {
            int bw = b.getWidth(), bh = b.getHeight();
            int[] px = new int[bw * bh];
            b.getPixels(px, 0, bw, 0, 0, bw, bh);
            return QrCore.decodeRobust(px, bw, bh);
        } finally {
            try { b.recycle(); } catch (Throwable ignored) {}
        }
    }

    /** v1.4.28 — the ≤8 MP pass + the progressive downscales, each through
     *  [QrCore.decodeRobust] (the rotations + center-crops at EACH scale).
     *  [base] is owned by the caller (recycled there). */
    private static String decodeLadder(Bitmap base, int maxDim) {
        try {
            int bw = base.getWidth(), bh = base.getHeight();
            int[] px = new int[bw * bh];
            base.getPixels(px, 0, bw, 0, 0, bw, bh);
            String r = QrCore.decodeRobust(px, bw, bh);
            if (r != null) return r;
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
