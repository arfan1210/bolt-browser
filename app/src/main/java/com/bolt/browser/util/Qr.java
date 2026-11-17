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
     * Open the picked image (content:// or file://), downsampled to roughly
     * maxDim px on the long edge, and decode any QR inside it.
     * Returns the QR text or null (absent / unreadable / IO error).
     */
    public static String decodeUri(android.content.Context ctx, Uri uri, int maxDim) {
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            try { in.close(); } catch (Throwable ignored) {}
            int sample = 1;
            int big = Math.max(o.outWidth, o.outHeight);
            while (big / (sample * 2) >= Math.max(600, maxDim / 2)) sample *= 2;
            InputStream in2 = ctx.getContentResolver().openInputStream(uri);
            if (in2 == null) return null;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeStream(in2, null, o2);
            try { in2.close(); } catch (Throwable ignored) {}
            return decode(bmp);
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
