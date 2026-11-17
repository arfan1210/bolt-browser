package com.bolt.browser.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.bolt.browser.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * v1.3.37 — the start-page wallpaper engine, shared by the browser (its own
 * Customize chip + long-press home) and Appearance → Start page.
 *
 * The picked image is persisted as filesDir/wallpaper.jpg with the long edge
 * capped at 1440 px (plenty for a background; kind to 3 GB devices), JPEG 86.
 * BrowserActivity decodes + center-crops it per-window on demand (cached).
 *
 * v1.3.53: every successful change bumps Fx.homeGen so BrowserActivity
 * rebuilds its home view on the next home show / resume — a home REBUILD,
 * never an activity recreate (the old skinGen channel destroyed every
 * tab's WebView and reloaded pages: the aggressive-reload field report).
 * A wallpaper change that moves the Material-You accent also flips
 * themeHash, and THAT recreate is legitimate (colors genuinely changed).
 */
public final class HomeWallpaper {

    private HomeWallpaper() {}

    public static File file(Context c) {
        return new File(Prefs.get(c).filesDir(), "wallpaper.jpg");
    }

    public static boolean active(Context c) {
        try {
            return Prefs.get(c).homeWallpaper() && file(c).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /** fire the system image picker (no runtime permission needed). */
    public static void choose(Activity a, int requestCode) {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            a.startActivityForResult(
                    Intent.createChooser(i, a.getString(R.string.wallpaper)), requestCode);
        } catch (Throwable t) {
            Fx.toast(a, a.getString(R.string.wallpaper_pick_failed));
        }
    }

    /** decode the picked image and persist it (background thread; the UI
     *  callback runs on the main thread on success). */
    public static void apply(final Activity a, final Uri uri, final Runnable done) {
        new Thread(() -> {
            File out = file(a);
            boolean ok = false;
            try {
                InputStream in = a.getContentResolver().openInputStream(uri);
                if (in != null) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    try { in.close(); } catch (Throwable ignored) {}
                    if (bmp != null) {
                        int longE = Math.max(bmp.getWidth(), bmp.getHeight());
                        if (longE > 1440) {
                            float s = 1440f / longE;
                            Bitmap sm = Bitmap.createScaledBitmap(bmp,
                                    Math.round(bmp.getWidth() * s),
                                    Math.round(bmp.getHeight() * s), true);
                            if (sm != bmp) bmp.recycle();
                            bmp = sm;
                        }
                        FileOutputStream fo = new FileOutputStream(out);
                        bmp.compress(Bitmap.CompressFormat.JPEG, 86, fo);
                        try { fo.close(); } catch (Throwable ignored) {}
                        bmp.recycle();
                        ok = out.exists() && out.length() > 0;
                    }
                }
            } catch (Throwable ignored) {}
            final boolean saved = ok;
            a.runOnUiThread(() -> {
                if (saved) {
                    Prefs.get(a).set("homeWallpaper", true);
                    Fx.homeGen++;          // browser rebuilds its home on next show/resume
                    Fx.toast(a, a.getString(R.string.wallpaper_set));
                    if (done != null) try { done.run(); } catch (Throwable ignored) {}
                } else {
                    Fx.toast(a, a.getString(R.string.wallpaper_pick_failed));
                }
            });
        }, "bolt-wallpaper").start();
    }

    /** drop the wallpaper and restore the default background. */
    public static void remove(Activity a, Runnable done) {
        Prefs.get(a).set("homeWallpaper", false);
        try { file(a).delete(); } catch (Throwable ignored) {}
        Fx.homeGen++;
        Fx.toast(a, a.getString(R.string.wallpaper_removed));
        if (done != null) try { done.run(); } catch (Throwable ignored) {}
    }
}
