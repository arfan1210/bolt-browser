package com.bolt.browser.ui

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bolt.browser.R
import com.bolt.browser.util.Fx
import com.bolt.browser.util.HomeWallpaper
import com.bolt.browser.util.Ui
import com.bolt.browser.util.WallpaperStyle

/**
 * v1.3.38 — the wallpaper ADJUST screen (Appearance → Start page →
 * Wallpaper → "Adjust", new code in Kotlin per the README language rule).
 *
 * FIELD FIX this answers: "the image added as background seems very faded, I
 * want it to be same, or have option to crop and apply blur as needed".
 * The live preview renders through the SAME engine the browser uses
 * (WallpaperStyle), so there is no "adjusted one thing, got another":
 *  - Dim 0–70% (default 0 = the photo exactly as picked — the old fixed 60%
 *    scrim is what read as "faded")
 *  - Blur 0–100% (StackBlur; strength anchored to the real screen width)
 *  - Zoom 100–300% + Pan X/Y — the crop control: zoom in, then slide the
 *    crop window to the part of the photo that should own the screen
 */
class WallpaperAdjustActivity : AppCompatActivity() {

    private lateinit var prefs: com.bolt.browser.util.Prefs
    private var params = WallpaperStyle.Params()
    private var preview: ImageView? = null
    private var emptyState: LinearLayout? = null
    private var controls: LinearLayout? = null
    private val reRender = Runnable { renderPreview() }

    /** v1.3.53 — true once the user actually CHANGED the wallpaper style
     *  (a slider moved, or Reset). onStop bumps the home rebuild channel
     *  only when dirty: the old code bumped skinGen UNCONDITIONALLY on
     *  every stop, so merely VISITING this screen recreated the browser
     *  on return and reloaded every tab (part of the aggressive-reload
     *  field report). */
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Fx.theme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generic_list)
        prefs = com.bolt.browser.util.Prefs.get(this)
        findViewById<TextView>(R.id.tv_g_title).text = getString(R.string.wallpaper)
        findViewById<View>(R.id.rv_g).visibility = View.GONE
        findViewById<View>(R.id.btn_g_back).post {
            findViewById<android.widget.ImageButton>(R.id.btn_g_back).setColorFilter(Fx.dim(this))
        }
        findViewById<View>(R.id.btn_g_back).setOnClickListener { finish() }

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val pad = Fx.dp(this, 18)
        list.setPadding(pad, pad, pad, pad + Fx.dp(this, 12))
        val scroller = ScrollView(this)
        scroller.setFillViewport(true)
        scroller.overScrollMode = View.OVER_SCROLL_NEVER
        scroller.addView(list, LinearLayout.LayoutParams(-1, -2))
        (findViewById<View>(R.id.btn_g_back).parent.parent as android.view.ViewGroup)
                .addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        params = WallpaperStyle.params(this)

        // ---- live preview ----
        val dm = resources.displayMetrics
        val previewHeight = (dm.heightPixels * 0.38f).toInt()
        val pv = ImageView(this)
        pv.scaleType = ImageView.ScaleType.CENTER_CROP
        val pvBg = android.graphics.drawable.GradientDrawable()
        pvBg.setCornerRadius(Fx.dp(this, 18).toFloat())
        pvBg.setColor(Fx.surface(this))
        pv.background = pvBg
        pv.clipToOutline = true
        list.addView(pv, LinearLayout.LayoutParams(-1, previewHeight))
        preview = pv

        // ---- sliders ----
        val sliders = LinearLayout(this)
        sliders.orientation = LinearLayout.VERTICAL
        list.addView(sliders, LinearLayout.LayoutParams(-1, -2))
        controls = sliders

        slider(sliders, "Dim (legibility veil)", 0, WallpaperStyle.DIM_MAX, params.dim, "%") { v ->
            params = params.copy(dim = v); persist()
        }
        slider(sliders, "Blur", 0, WallpaperStyle.BLUR_MAX, params.blur, "%") { v ->
            params = params.copy(blur = v); persist()
        }
        slider(sliders, "Crop zoom", WallpaperStyle.ZOOM_MIN - 100,
                WallpaperStyle.ZOOM_MAX - 100, params.zoom - 100, "%") { v ->
            params = params.copy(zoom = v + 100); persist()
        }
        slider(sliders, "Crop position  X", -WallpaperStyle.PAN_RANGE,
                WallpaperStyle.PAN_RANGE, params.panX, "") { v ->
            params = params.copy(panX = v); persist()
        }
        slider(sliders, "Crop position  Y", -WallpaperStyle.PAN_RANGE,
                WallpaperStyle.PAN_RANGE, params.panY, "") { v ->
            params = params.copy(panY = v); persist()
        }

        // ---- buttons ----
        val choose = Ui.filled(this, "Choose image")
        choose.setOnClickListener {
            HomeWallpaper.choose(this, 1012)
        }
        val clp = LinearLayout.LayoutParams(-1, Fx.dp(this, 48))
        clp.topMargin = Fx.dp(this, 18)
        list.addView(choose, clp)

        val reset = Ui.quiet(this, "Reset style (as picked)")
        reset.setOnClickListener {
            Ui.tap(it)
            dirty = true
            params = WallpaperStyle.Params()
            WallpaperStyle.reset(this)
            rebuildSliders(sliders)
            renderPreview()
        }
        val rlp = LinearLayout.LayoutParams(-1, Fx.dp(this, 48))
        rlp.topMargin = Fx.dp(this, 10)
        list.addView(reset, rlp)

        val remove = Ui.quiet(this, getString(R.string.wallpaper_default))
        remove.setOnClickListener {
            Ui.tap(it)
            HomeWallpaper.remove(this, null)
            params = WallpaperStyle.Params()
            WallpaperStyle.reset(this)
            syncVisibility()
            renderPreview()
        }
        rlp.topMargin = Fx.dp(this, 10)
        list.addView(remove, rlp)

        val note = TextView(this)
        note.text = "Dim 0 keeps the photo exactly as picked. Crop zoom + " +
                "position choose which part fills the screen; blur softens a " +
                "busy photo behind the tiles."
        note.textSize = 13f
        note.setTextColor(Fx.dim(this))
        note.setPadding(0, Fx.dp(this, 16), 0, 0)
        list.addView(note, LinearLayout.LayoutParams(-1, -2))

        // ---- empty state (no wallpaper yet) ----
        val empty = Ui.emptyState(this, R.drawable.ic_droplet,
                "No wallpaper set", "Pick a photo to start")
        list.addView(empty, LinearLayout.LayoutParams(-1, -2))
        emptyState = empty

        syncVisibility()
        preview?.post { renderPreview() }
    }

    private fun persist() {
        dirty = true
        WallpaperStyle.apply(this, params)
        preview?.removeCallbacks(reRender)
        preview?.postDelayed(reRender, 140)
    }

    private fun syncVisibility() {
        val active = HomeWallpaper.active(this)
        preview?.visibility = if (active) View.VISIBLE else View.GONE
        controls?.visibility = if (active) View.VISIBLE else View.GONE
        emptyState?.visibility = if (active) View.GONE else View.VISIBLE
    }

    /** re-render the preview through the exact engine the browser uses. */
    private fun renderPreview() {
        val pv = preview ?: return
        try {
            val w = pv.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val h = pv.height.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels * 0.38f).toInt()
            Thread {
                val bmp = WallpaperStyle.render(WallpaperStyle.file(this), w, h,
                        params, resources.displayMetrics.widthPixels)
                runOnUiThread {
                    try {
                        if (bmp != null) {
                            pv.setImageBitmap(bmp)
                            syncVisibility()
                        } else {
                            pv.setImageDrawable(null)
                            syncVisibility()
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            }.start()
        } catch (ignored: Throwable) {
        }
    }

    /** label + live value + SeekBar row; [onChange] gets the user value. */
    private fun slider(parent: LinearLayout, label: String, min: Int, max: Int,
            value: Int, unit: String, onChange: (Int) -> Unit): SeekBar {
        val wrap = LinearLayout(parent.context)
        wrap.orientation = LinearLayout.VERTICAL
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.typeface = Ui.medium()
        t.setTextColor(Fx.dim(this))
        top.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        val v = TextView(this)
        fun fmt(x: Int) = if (unit.isEmpty()) x.toString() else "$x$unit"
        v.text = fmt(value)
        v.textSize = 14f
        v.typeface = Ui.medium()
        v.setTextColor(Fx.accentText(this))
        top.addView(v, LinearLayout.LayoutParams(-2, -2))
        wrap.addView(top, LinearLayout.LayoutParams(-1, -2))
        val sb = SeekBar(this)
        sb.max = (max - min).coerceAtLeast(0)
        sb.progress = (value - min).coerceIn(0, max - min)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val user = p + min
                v.text = fmt(user)
                onChange(user)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        wrap.addView(sb, LinearLayout.LayoutParams(-1, -2))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Fx.dp(this, 6)
        lp.bottomMargin = Fx.dp(this, 10)
        parent.addView(wrap, lp)
        return sb
    }

    /** after Reset: rebuild the slider rows with the fresh values. */
    private fun rebuildSliders(host: LinearLayout) {
        host.removeAllViews()
        slider(host, "Dim (legibility veil)", 0, WallpaperStyle.DIM_MAX, params.dim, "%") { v ->
            params = params.copy(dim = v); persist()
        }
        slider(host, "Blur", 0, WallpaperStyle.BLUR_MAX, params.blur, "%") { v ->
            params = params.copy(blur = v); persist()
        }
        slider(host, "Crop zoom", WallpaperStyle.ZOOM_MIN - 100,
                WallpaperStyle.ZOOM_MAX - 100, params.zoom - 100, "%") { v ->
            params = params.copy(zoom = v + 100); persist()
        }
        slider(host, "Crop position  X", -WallpaperStyle.PAN_RANGE,
                WallpaperStyle.PAN_RANGE, params.panX, "") { v ->
            params = params.copy(panX = v); persist()
        }
        slider(host, "Crop position  Y", -WallpaperStyle.PAN_RANGE,
                WallpaperStyle.PAN_RANGE, params.panY, "") { v ->
            params = params.copy(panY = v); persist()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri? = data?.data
        if (requestCode == 1012 && resultCode == RESULT_OK && uri != null) {
            HomeWallpaper.apply(this, uri) {
                params = WallpaperStyle.Params()
                WallpaperStyle.reset(this)
                controls?.let { rebuildSliders(it) }
                syncVisibility()
                renderPreview()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // v1.3.53: only a REAL style change asks the browser for a home
        // re-render — and it asks on the homeGen channel (a home REBUILD,
        // never an activity recreate: the old unconditional skinGen++
        // reloaded every tab after a mere visit to this screen). Choose /
        // Remove bump their own channels inside HomeWallpaper.
        if (dirty) Fx.homeGen++
    }
}
