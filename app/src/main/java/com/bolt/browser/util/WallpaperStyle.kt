package com.bolt.browser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import java.io.File

/**
 * v1.3.38 — the wallpaper RENDER ENGINE (new code → Kotlin, per the README's
 * language rule). Shared by the browser's start page and the
 * WallpaperAdjustActivity preview, so what you adjust is exactly what ships.
 *
 * v1.3.38 FIELD FIX it implements ("the image added as background seems very
 * faded, I want it to be same, or have option to crop and apply blur as
 * needed"):
 *  - DIM defaults to 0 — the photo renders EXACTLY as picked (the old fixed
 *    60% scrim is gone; the dim slider is the user's optional legibility veil)
 *  - CROP = zoom (100–300%) + pan X/Y sliders — the cover-crop window can be
 *    enlarged and slid around, so any part of the photo can own the screen
 *  - BLUR = 0–100% → a 1–25 px StackBlur pass
 *
 * Pipeline: sampled decode → cover-scale × zoom → pan-crop → optional blur →
 * RGB_565 bitmap. The dim scrim stays a LayerDrawable concern of the caller
 * (the veil color follows the skin lightness).
 */
object WallpaperStyle {

    data class Params(
        @JvmField val dim: Int = 0,       // 0..70 (% veil over the photo)
        @JvmField val blur: Int = 0,      // 0..100 (% → 1..25 px radius)
        @JvmField val zoom: Int = 100,    // 100..300 (% of the cover scale)
        @JvmField val panX: Int = 0,      // -100..100 (crop window horizontal position)
        @JvmField val panY: Int = 0,      // -100..100 (crop window vertical position)
    )

    const val DIM_MAX = 70
    const val BLUR_MAX = 100
    const val ZOOM_MIN = 100
    const val ZOOM_MAX = 300
    const val PAN_RANGE = 100

    @JvmStatic
    fun params(c: Context): Params {
        val p = Prefs.get(c)
        return Params(
            p.i("wallDim", 0).coerceIn(0, DIM_MAX),
            p.i("wallBlur", 0).coerceIn(0, BLUR_MAX),
            p.i("wallZoom", 100).coerceIn(ZOOM_MIN, ZOOM_MAX),
            p.i("wallX", 0).coerceIn(-PAN_RANGE, PAN_RANGE),
            p.i("wallY", 0).coerceIn(-PAN_RANGE, PAN_RANGE),
        )
    }

    @JvmStatic
    fun apply(c: Context, p: Params) {
        prefs(c).set("wallDim", p.dim)
        prefs(c).set("wallBlur", p.blur)
        prefs(c).set("wallZoom", p.zoom)
        prefs(c).set("wallX", p.panX)
        prefs(c).set("wallY", p.panY)
    }

    @JvmStatic
    fun reset(c: Context) = apply(c, Params())

    private fun prefs(c: Context) = Prefs.get(c)

    /** changes whenever any style knob moves — the browser's render cache key. */
    @JvmStatic
    fun stamp(p: Params): Long {
        var s = 1125899906842597L
        s = 31 * s + p.dim
        s = 31 * s + p.blur
        s = 31 * s + p.zoom
        s = 31 * s + p.panX
        s = 31 * s + p.panY
        return s
    }

    fun stamp(c: Context): Long = stamp(params(c))

    /** 0..100 pref → 1..25 px radius (fine steps at the low end matter most). */
    @JvmStatic
    fun blurRadius(blurPct: Int): Int = ((blurPct.coerceIn(0, 100) * 25) / 100).coerceAtLeast(1)

    /**
     * Render [file] for a [vw]×[vh] viewport with style [p].
     * [nominalWidth] anchors the blur radius so the ADJUST PREVIEW (a small
     * viewport) shows the same blur strength as the real screen — the radius
     * scales with the render width.
     */
    @JvmStatic
    fun render(file: File, vw: Int, vh: Int, p: Params, nominalWidth: Int): Bitmap? {
        if (vw <= 0 || vh <= 0 || !file.exists()) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            // sample toward ~2× the viewport (zoom crops upscale happily; 2×
            // headroom keeps pan/zoom crops crisp without full-size decodes)
            var sample = 1
            val need = Math.max(vw, vh) * 2
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= need) sample *= 2
            val raw = BitmapFactory.decodeFile(file.path,
                    BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null

            // cover-scale × zoom
            val zoomF = p.zoom / 100f
            val scale = Math.max(vw / raw.width.toFloat(), vh / raw.height.toFloat()) * zoomF
            val sw = Math.max(vw, Math.round(raw.width * scale))
            val sh = Math.max(vh, Math.round(raw.height * scale))
            val scaled = if (sw == raw.width && sh == raw.height) raw
                else Bitmap.createScaledBitmap(raw, sw, sh, true)
            if (scaled != raw) raw.recycle()

            // pan-crop: -100..100 maps across the available slack (0 = centered)
            val slackX = (sw - vw).coerceAtLeast(0)
            val slackY = (sh - vh).coerceAtLeast(0)
            val offX = Math.round(slackX * ((p.panX + PAN_RANGE) / 200f)).coerceIn(0, slackX)
            val offY = Math.round(slackY * ((p.panY + PAN_RANGE) / 200f)).coerceIn(0, slackY)
            var out = if (slackX == 0 && slackY == 0) scaled
                else Bitmap.createBitmap(scaled, offX, offY, vw, vh)
            if (out != scaled) scaled.recycle()

            if (p.blur > 0) {
                val radius = Math.max(1,
                        Math.round(blurRadius(p.blur) * (vw.toFloat() / Math.max(1, nominalWidth))))
                val blurred = StackBlur.blur(out, radius)
                if (blurred != out) out.recycle()
                out = blurred
            }

            // RGB_565 (photos carry no alpha — half the bytes of ARGB_8888)
            val five = if (out.config == Bitmap.Config.RGB_565) out
                else out.copy(Bitmap.Config.RGB_565, false)
            if (five != out) out.recycle()
            five
        } catch (t: Throwable) {
            null
        }
    }

    /** the full start-page background: photo + optional skin-tinted dim veil. */
    @JvmStatic
    fun background(c: Context, vw: Int, vh: Int, p: Params): Drawable? {
        val f = file(c)
        if (!f.exists()) return null
        val bmp = render(f, vw, vh, p, Math.max(1, vw)) ?: return null
        val photo: Drawable = BitmapDrawable(c.resources, bmp)
        if (p.dim <= 0) return photo
        val veil = ColorDrawable((skinBg(c) and 0x00FFFFFF) or
                ((p.dim * 255 / 100) shl 24))
        return LayerDrawable(arrayOf(photo, veil))
    }

    private fun skinBg(c: Context): Int = try {
        Fx.bg(c)
    } catch (t: Throwable) {
        Color.WHITE
    }

    @JvmStatic
    fun file(c: Context): File = File(prefs(c).filesDir(), "wallpaper.jpg")
}
