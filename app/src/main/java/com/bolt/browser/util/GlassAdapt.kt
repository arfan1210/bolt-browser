package com.bolt.browser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable

/**
 * v1.3.39 — ADAPTIVE GLASS (new code → Kotlin).
 *
 * Field request: "For the glass theme, make it detect if the page is white
 * or black near the bars and apply glass effect accordingly (white
 * transparent glass when stuff below bar is white and black transparent
 * glass when stuff is dark under glass, also increase the transparency a
 * bit more)."
 *
 * The blur-snapshot machinery (v1.3.35) already draws the content directly
 * behind each bar into a bitmap — we now read its average luminance and
 * tint the glass to MATCH the page instead of the app lightness: frosted
 * white over white pages, smoked black over dark pages. And the fill
 * alpha drops from 90% to 62% — noticeably more see-through.
 */
object GlassAdapt {

    /** fill alpha — "increase the transparency a bit more" (was 0xE6/90%). */
    private const val ALPHA = 0x9E          // 62%

    /** luminance band where either glass reads well (no flip-flop churn). */
    private const val WHITE_ABOVE = 0.55f
    private const val DARK_BELOW = 0.45f

    /** average luminance 0..1 of the snapshot behind a bar (cheap: every
     *  8th pixel, RGB_565-safe). Returns 0.5 when it can't be read. */
    @JvmStatic
    fun luminance(b: Bitmap?): Float {
        if (b == null || b.width < 4 || b.height < 4) return 0.5f
        return try {
            var sum = 0.0
            var n = 0
            val step = Math.max(4, b.height / 8) * Math.max(1, b.width / 32)
            var y = 0
            while (y < b.height) {
                var x = 0
                while (x < b.width) {
                    val p = b.getPixel(x, y)
                    sum += (0.299 * android.graphics.Color.red(p)
                            + 0.587 * android.graphics.Color.green(p)
                            + 0.114 * android.graphics.Color.blue(p)) / 255.0
                    n++
                    x += Math.max(1, b.width / 16)
                }
                y += Math.max(1, b.height / 8)
            }
            if (n == 0) 0.5f else (sum / n).toFloat()
        } catch (t: Throwable) { 0.5f }
    }

    /** true when the luminance band says "white glass" (hysteresis between
     *  the two thresholds keeps scrolling pages from strobing). */
    @JvmStatic
    fun isWhite(prev: Float, lum: Float): Boolean =
            if (prev > WHITE_ABOVE) lum > DARK_BELOW
            else if (prev < DARK_BELOW) lum > WHITE_ABOVE
            else lum > 0.5f

    /** the adaptive glass bar background. [white] = white glass. */
    @JvmStatic
    fun barBg(c: Context, topEdge: Boolean, white: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.setOrientation(if (topEdge) GradientDrawable.Orientation.TOP_BOTTOM
                else GradientDrawable.Orientation.BOTTOM_TOP)
        if (white) {
            g.setColors(intArrayOf(0x30FFFFFF, ALPHA shl 24 or 0xFAFAFF))
            g.setStroke(Math.max(1, Fx.dp(c, 1)), 0x241B1B2E)
        } else {
            g.setColors(intArrayOf(0x14FFFFFF, ALPHA shl 24 or 0x0E0E1A))
            g.setStroke(Math.max(1, Fx.dp(c, 1)), 0x2EFFFFFF)
        }
        return g
    }

    /** system-bar color that matches the glass over the given lightness. */
    @JvmStatic
    fun sysBar(white: Boolean): Int =
            if (white) (ALPHA shl 24) or 0xF4F4FA else (ALPHA shl 24) or 0x0E0E1A
}
