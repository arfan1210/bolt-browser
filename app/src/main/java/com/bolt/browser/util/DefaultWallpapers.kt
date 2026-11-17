package com.bolt.browser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.sin

/**
 * v1.3.39 — BUILT-IN ABSTRACT WALLPAPERS (new code → Kotlin).
 *
 * Field request: "give some nice default home wallpapers to choose from
 * (abstract patterns for dark mode and light modes, around 5 wallpapers
 * should suffice)."
 *
 * Five patterns, each rendered for BOTH lightnesses from one palette pair
 * so the wallpaper always agrees with the skin: dawn mesh, tide waves,
 * orbit rings, dot grid and shard angles. Rendered procedurally at pick
 * time (1080×1920) and saved through the SAME wallpaper.jpg slot the photo
 * picker uses — every existing dim/blur/crop control works on them.
 */
object DefaultWallpapers {

    val NAMES = arrayOf("Dawn", "Tide", "Orbit", "Grid", "Shards")

    // {dark A, dark B, light A, light B} — base gradient ends
    private val PALETTES = arrayOf(
            intArrayOf(0xFF221A3E.toInt(), 0xFF0B0B12.toInt(), 0xFFF6E7FF.toInt(), 0xFFEDE6FA.toInt()),   // dawn (violet)
            intArrayOf(0xFF0E2A33.toInt(), 0xFF08131A.toInt(), 0xFFDDF3F7.toInt(), 0xFFE8F1F6.toInt()),   // tide (teal)
            intArrayOf(0xFF251436.toInt(), 0xFF0D0A1A.toInt(), 0xFFFDE9F2.toInt(), 0xFFF0E8F8.toInt()),   // orbit (plum)
            intArrayOf(0xFF152318.toInt(), 0xFF0A0F0B.toInt(), 0xFFE9F4E6.toInt(), 0xFFEFF4EC.toInt()),   // grid (moss)
            intArrayOf(0xFF301B18.toInt(), 0xFF120908.toInt(), 0xFFFBEADF.toInt(), 0xFFF4E7DC.toInt())    // shards (ember)
    )

    /** render pattern [idx] (dark or light) into a w×h bitmap. */
    @JvmStatic
    fun render(c: Context, idx: Int, dark: Boolean, w: Int, h: Int): Bitmap {
        val i = ((idx % NAMES.size) + NAMES.size) % NAMES.size
        val pal = PALETTES[i]
        val a = if (dark) pal[0] else pal[2]
        val b = if (dark) pal[1] else pal[3]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.25f, h.toFloat(), a, b,
                Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        val aa = if (dark) 0x4C else 0x5E
        val secondary = if (dark) 0xFF9A7BFF.toInt() else 0xFF7B5CFF.toInt()
        val tertiary = if (dark) 0xFF00E5C0.toInt() else 0xFF00A878.toInt()
        when (i) {
            0 -> dawn(cv, w, h, secondary, tertiary, aa)
            1 -> tide(cv, w, h, secondary, tertiary, aa, dark)
            2 -> orbit(cv, w, h, secondary, tertiary, aa)
            3 -> grid(cv, w, h, secondary, tertiary, aa)
            else -> shards(cv, w, h, secondary, tertiary, aa)
        }
        return bmp
    }

    private fun alphaOf(rgb: Int, alpha: Int): Int = (rgb and 0x00FFFFFF) or (alpha shl 24)

    /** soft radial "sunrise" blobs. */
    private fun dawn(cv: Canvas, w: Int, h: Int, a1: Int, a2: Int, aa: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val blobs = listOf(
                Triple(w * 0.78f, h * 0.16f, (min(w, h) * 0.7f) to a1),
                Triple(w * 0.18f, h * 0.72f, (min(w, h) * 0.8f) to a2),
                Triple(w * 0.62f, h * 0.90f, (min(w, h) * 0.5f) to a1))
        for ((x, y, s) in blobs) {
            val r = s.first
            val col = s.second
            p.shader = RadialGradient(x, y, r, alphaOf(col, aa + 0x14), alphaOf(col, 0x00),
                    Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, r, p)
        }
        p.shader = null
    }

    /** layered sine waves. */
    private fun tide(cv: Canvas, w: Int, h: Int, a1: Int, a2: Int, aa: Int, dark: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.strokeWidth = w * 0.012f
        p.style = Paint.Style.STROKE
        for (k in 0 until 5) {
            val phase = k * 0.9f
            val amp = h * (0.055f + k * 0.016f)
            val col = if (k % 2 == 0) a1 else a2
            p.color = alphaOf(col, (if (dark) aa - k * 8 else aa + 0x0A))
            val path = Path()
            var x = 0f
            while (x <= w) {
                val y = h * (0.32f + k * 0.1f) + amp * sin((x / w * 2.2 * Math.PI).toFloat() + phase)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += w / 48f
            }
            cv.drawPath(path, p)
        }
    }

    /** concentric rings around an off-center point. */
    private fun orbit(cv: Canvas, w: Int, h: Int, a1: Int, a2: Int, aa: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        val cx = w * 0.7f
        val cy = h * 0.3f
        for (k in 1..6) {
            p.strokeWidth = w * (0.006f + k * 0.0025f)
            p.color = alphaOf(if (k % 2 == 0) a1 else a2, (aa - k * 5).coerceAtLeast(0x10))
            cv.drawCircle(cx, cy, min(w, h) * k * 0.16f, p)
        }
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, min(w, h) * 0.34f,
                alphaOf(a1, aa + 0x1E), alphaOf(a1, 0x00), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, min(w, h) * 0.34f, p)
    }

    /** dot grid with a radial emphasis fade. */
    private fun grid(cv: Canvas, w: Int, h: Int, a1: Int, a2: Int, aa: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val step = min(w, h) / 14f
        val r = step * 0.16f
        val cx = w * 0.3f
        val cy = h * 0.4f
        var y = step / 2
        while (y < h) {
            var x = step / 2
            while (x < w) {
                val d = Math.hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
                val near = Math.max(0f, 1f - d / (min(w, h) * 1.2f))
                p.color = alphaOf(if (((x / step).toInt() + (y / step).toInt()) % 2 == 0) a1 else a2,
                        (aa * (0.25f + near * 0.75f)).toInt())
                cv.drawCircle(x, y, r * (0.7f + near * 0.9f), p)
                x += step
            }
            y += step
        }
    }

    /** angled translucent bands (mountain-shard feel). */
    private fun shards(cv: Canvas, w: Int, h: Int, a1: Int, a2: Int, aa: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (k in 0 until 7) {
            val col = if (k % 3 == 0) a2 else a1
            p.color = alphaOf(col, (aa - k * 6).coerceAtLeast(0x10))
            val path = Path()
            val x0 = -w * 0.2f + k * w * 0.17f
            path.moveTo(x0, h * 1.05f)
            path.lineTo(x0 + w * 0.42f, h * 1.05f)
            path.lineTo(x0 + w * 0.76f, -h * 0.05f)
            path.lineTo(x0 + w * 0.34f, -h * 0.05f)
            path.close()
            cv.drawPath(path, p)
        }
    }

    /** persist pattern [idx] as the active wallpaper (background thread,
     *  UI callback on success). Mirrors HomeWallpaper.apply's contract. */
    @JvmStatic
    fun applyAsWallpaper(a: android.app.Activity, idx: Int, done: Runnable?) {
        val dark = Prefs.get(a).isDark()
        Thread({
            var ok = false
            try {
                val bmp = render(a, idx, dark, 1080, 1920)
                val out = File(Prefs.get(a).filesDir(), "wallpaper.jpg")
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                ok = out.exists() && out.length() > 0
            } catch (t: Throwable) { }
            val finalOk = ok
            a.runOnUiThread {
                if (finalOk) {
                    Prefs.get(a).set("homeWallpaper", true)
                    Fx.homeGen++   // v1.3.53: home rebuild channel — never a browser recreate
                    Fx.toast(a, "Wallpaper set — " + NAMES[((idx % NAMES.size) + NAMES.size) % NAMES.size])
                    if (done != null) try { done.run() } catch (t: Throwable) { }
                } else {
                    Fx.toast(a, a.getString(com.bolt.browser.R.string.wallpaper_pick_failed))
                }
            }
        }, "bolt-defwp").start()
    }

    /** picker sheet: 5 thumbnails in a horizontal card strip → tap applies. */
    @JvmStatic
    fun showPicker(a: android.app.Activity) {
        try {
            val list = Ui.sheetBody(a)
            val tv = TextView(a)
            tv.text = "Built-in wallpapers"
            tv.textSize = 18f
            tv.typeface = Ui.medium()
            tv.setTextColor(Fx.txt(a))
            tv.setPadding(Fx.dp(a, 6), Fx.dp(a, 12), Fx.dp(a, 6), Fx.dp(a, 8))
            list.addView(tv, LinearLayout.LayoutParams(-1, -2))

            val strip = LinearLayout(a)
            strip.orientation = LinearLayout.HORIZONTAL
            val dark = Prefs.get(a).isDark()
            val size = Fx.dp(a, 96)
            for (i in NAMES.indices) {
                val idx = i
                val col = LinearLayout(a)
                col.orientation = LinearLayout.VERTICAL
                col.gravity = android.view.Gravity.CENTER_HORIZONTAL
                col.setPadding(Fx.dp(a, 6), Fx.dp(a, 8), Fx.dp(a, 6), Fx.dp(a, 8))
                col.setBackgroundResource(com.bolt.browser.R.drawable.bg_card)
                val iv = ImageView(a)
                val bmp = render(a, idx, dark, 120, 200)
                iv.setImageBitmap(bmp)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                col.addView(iv, LinearLayout.LayoutParams(size, Fx.dp(a, 160)))
                val name = TextView(a)
                name.text = NAMES[idx]
                name.textSize = 12f
                name.typeface = Ui.medium()
                name.setTextColor(Fx.txt(a))
                name.gravity = android.view.Gravity.CENTER
                col.addView(name, LinearLayout.LayoutParams(size, -2))
                col.setOnClickListener {
                    Ui.tap(it)
                    applyAsWallpaper(a, idx, null)
                }
                MenuCards.explain(col, a, NAMES[idx],
                        "Built-in abstract wallpaper (${if (dark) "dark" else "light"} palette). " +
                                "Applies instantly; dim/blur/crop still adjustable in the wallpaper studio.")
                val lp = LinearLayout.LayoutParams(-2, -2)
                lp.rightMargin = Fx.dp(a, 10)
                strip.addView(col, lp)
            }
            val hs = HorizontalScrollView(a)
            hs.setHorizontalScrollBarEnabled(false)
            hs.addView(strip)
            list.addView(hs, LinearLayout.LayoutParams(-1, -2))

            val note = TextView(a)
            note.text = "Rendered on-device for the current mode — patterns for dark and light."
            note.textSize = 13f
            note.setTextColor(Fx.dim(a))
            note.setPadding(Fx.dp(a, 16), Fx.dp(a, 10), Fx.dp(a, 16), Fx.dp(a, 6))
            list.addView(note, LinearLayout.LayoutParams(-1, -2))

            Ui.showSheet(a, list)
        } catch (t: Throwable) { }
    }
}
