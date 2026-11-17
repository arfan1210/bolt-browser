package com.bolt.browser.util

import android.graphics.Bitmap

/**
 * v1.3.38 — the wallpaper blur: Mario Klingemann's classic StackBlur ported
 * to Kotlin. Chosen over RenderEffect (API 31+, blurs only a view's own
 * content) and the abandoned RenderScript so blur works on every supported
 * API level with zero new dependencies.
 *
 * O(1) work per pixel (sliding-window sums + a precomputed division table),
 * so a full-resolution pass is ~100 ms on the 3 GB target — and it runs at
 * most once per wallpaper/style change because the browser caches the
 * rendered bitmap (see WallpaperStyle + BrowserActivity.homeWallpaperBg).
 */
object StackBlur {

    /** Return a blurred COPY of [src] (or [src] itself when radius < 1). */
    @JvmStatic
    fun blur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (radius < 1 || w < 3 || h < 3) return src
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        stackBlur(pix, w, h, radius.coerceAtMost(Math.min(w, h) / 2 - 1))
        return Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun stackBlur(pix: IntArray, w: Int, h: Int, radius: Int) {
        val div = radius + radius + 1
        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val vmin = IntArray(Math.max(w, h))
        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) dv[i] = i / div

        // ---- horizontal pass: per-row sliding window over the raw pixels,
        // channel sums parked in r/g/b ----
        var yw = 0
        var yi = 0
        for (y in 0 until h) {
            var rsum = 0; var gsum = 0; var bsum = 0
            for (i in -radius..radius) {
                val p = pix[yi + Math.min(wm, Math.max(i, 0))]
                rsum += (p shr 16) and 0xFF
                gsum += (p shr 8) and 0xFF
                bsum += p and 0xFF
            }
            for (x in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]
                if (y == 0) vmin[x] = Math.min(x + radius + 1, wm)
                val add = pix[yw + vmin[x]]
                val rem = pix[yw + Math.max(x - radius, 0)]
                rsum += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                gsum += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                bsum += (add and 0xFF) - (rem and 0xFF)
                yi++
            }
            yw += w
        }

        // ---- vertical pass: sliding window down the r/g/b columns, result
        // written back into pix ----
        for (x in 0 until w) {
            var rsum = 0; var gsum = 0; var bsum = 0
            var yp = -radius * w
            for (i in -radius..radius) {
                val yi2 = Math.max(0, yp) + x
                rsum += r[yi2]; gsum += g[yi2]; bsum += b[yi2]
                yp += w
            }
            var yi3 = x
            for (y in 0 until h) {
                pix[yi3] = (0xFF shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                if (x == 0) vmin[y] = Math.min(y + radius + 1, hm) * w
                val addIdx = x + vmin[y]
                val remIdx = x + Math.max(y - radius, 0) * w
                rsum += r[addIdx] - r[remIdx]
                gsum += g[addIdx] - g[remIdx]
                bsum += b[addIdx] - b[remIdx]
                yi3 += w
            }
        }
    }
}
