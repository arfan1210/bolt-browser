package com.bolt.browser.util

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton

/**
 * v1.3.39 — TRUE FULLSCREEN BROWSING (new code → Kotlin).
 *
 * Field request: "Add a fullscreen mode to hide all bars and remove the
 * notification bar of android (true full screen even on notched screens,
 * with a toggle in settings to enable notch or not). Keep a hovering
 * button (moveable) to press on to show the bars again."
 *
 * Two levers:
 *  - SYSTEM bars: immersive sticky (API < 30) / insetsController hide with
 *    BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE (30+). While hidden the decor
 *    stops fitting system windows so the PAGE owns the notch area too —
 *    LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES draws content edge to edge
 *    (the notch toggle turns that into DEFAULT = the system blacks the
 *    shoulder zone out itself).
 *  - BOLT bars: the existing scroll-to-hide chrome keeps working; the
 *    hovering [HoverButton] toggles them when the page never scrolls.
 *
 * The window insets flicker between decor-fits states is invisible in
 * practice because the bars themselves are animating in the same beat.
 */
object Fullscreen {

    /** pref keys — surfaced on the settings HOME (surface settings). */
    const val PREF = "fullscreenBrowse"      // default OFF
    const val PREF_NOTCH = "fullscreenNotch" // default ON (use the notch area)

    @JvmStatic
    fun on(c: android.content.Context): Boolean =
            Prefs.get(c).b(PREF, false)

    @JvmStatic
    fun notch(c: android.content.Context): Boolean =
            Prefs.get(c).b(PREF_NOTCH, true)

    /** apply the immersive window state for the CURRENT pref values. */
    @JvmStatic
    fun apply(a: Activity) = window(a, on(a), notch(a))

    /**
     * @param showBars true while Bolt's own chrome is visible — the system
     * bars follow it (shown + decor fits windows); false hides everything.
     */
    @JvmStatic
    fun apply(a: Activity, showBars: Boolean) = window(a, on(a) && !showBars, notch(a))

    /** the low-level window state. */
    @JvmStatic
    fun window(a: Activity, immersive: Boolean, useNotch: Boolean) {
        try {
            val w: Window = a.window ?: return
            if (Build.VERSION.SDK_INT >= 30) {
                val ctl = w.insetsController ?: return
                if (immersive) {
                    w.setDecorFitsSystemWindows(false)
                    ctl.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
                    ctl.hide(android.view.WindowInsets.Type.statusBars()
                            or android.view.WindowInsets.Type.navigationBars())
                } else {
                    ctl.show(android.view.WindowInsets.Type.statusBars()
                            or android.view.WindowInsets.Type.navigationBars())
                    w.setDecorFitsSystemWindows(true)
                }
            } else {
                val v = w.decorView
                // pre-30 immersive: clear, then (re)apply. Single-expression
                // chains — Kotlin infix calls cannot break across lines here.
                val clear = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                var vis = (v.systemUiVisibility and clear.inv()) or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                if (immersive) {
                    vis = vis or (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                }
                v.systemUiVisibility = vis
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val lp = w.attributes
                // SHORT_EDGES only takes effect together with the
                // LAYOUT_FULLSCREEN flags; DEFAULT hands the shoulder zone
                // to the system (the classic letterboxed notch look)
                lp.layoutInDisplayCutoutMode = if (useNotch)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                w.setAttributes(lp)
            }
            if (immersive) {
                w.statusBarColor = Color.TRANSPARENT
                w.navigationBarColor = Color.TRANSPARENT
            }
        } catch (t: Throwable) { }
    }
}

/**
 * The MOVEABLE hovering button that brings the bars back in fullscreen
 * mode. Mirrors the media FAB's drag handling (BrowserActivity's
 * setupFabDrag pattern) but persists its position as window fractions so
 * it survives recreation; sticks inside the window on every drag frame.
 */
class HoverButton(private val activity: Activity, root: ViewGroup) {

    private val btn: ImageButton = ImageButton(activity)
    private var listener: Runnable? = null

    init {
        btn.setImageResource(com.bolt.browser.R.drawable.ic_bars)
        btn.setBackgroundResource(com.bolt.browser.R.drawable.bg_fab)
        btn.setColorFilter(0xFFFFFFFF.toInt())
        val pad = Fx.dp(activity, 10)
        btn.setPadding(pad, pad, pad, pad)
        btn.visibility = View.GONE
        btn.contentDescription = "Show bars"
        val size = Fx.dp(activity, 44)
        val lp = android.widget.FrameLayout.LayoutParams(size, size)
        restore(lp)
        root.addView(btn, lp)
        wireDrag()
        btn.setOnClickListener { v ->
            Ui.tap(v)
            listener?.run()
        }
    }

    fun onTap(l: Runnable?) { listener = l }

    private fun restore(lp: android.widget.FrameLayout.LayoutParams) {
        try {
            val p = Prefs.get(activity)
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            btn.translationX = p.i("hoverX", -1).toFloat()   // -1 = unset
            btn.translationY = p.i("hoverY", -1).toFloat()
            if (p.i("hoverX", -1) < 0) {
                // first run: right edge, ~40% down (clear of the media FAB)
                btn.post {
                    try {
                        val root = btn.parent as? ViewGroup ?: return@post
                        val maxX = (root.width - btn.width).toFloat()
                        val maxY = (root.height - btn.height).toFloat()
                        btn.translationX = maxX
                        btn.translationY = maxY * 0.42f
                    } catch (t: Throwable) { }
                }
            }
        } catch (t: Throwable) { }
    }

    private fun wireDrag() {
        btn.setOnTouchListener(object : View.OnTouchListener {
            var dX = 0f; var dY = 0f; var moved = false
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        dX = v.translationX - e.rawX
                        dY = v.translationY - e.rawY
                        moved = false
                        return false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val root = v.parent as? ViewGroup ?: return true
                        val nx = e.rawX + dX
                        val ny = e.rawY + dY
                        if (Math.abs(nx - v.translationX) > 8 || Math.abs(ny - v.translationY) > 8) moved = true
                        if (moved) {
                            v.translationX = Math.max(0f, Math.min((root.width - v.width).toFloat(), nx))
                            v.translationY = Math.max(0f, Math.min((root.height - v.height).toFloat(), ny))
                            return true
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (moved) save()
                    }
                }
                return false
            }
        })
    }

    private fun save() {
        try {
            val p = Prefs.get(activity)
            p.set("hoverX", Math.round(btn.translationX))
            p.set("hoverY", Math.round(btn.translationY))
        } catch (t: Throwable) { }
    }

    fun setVisible(on: Boolean) {
        btn.visibility = if (on) View.VISIBLE else View.GONE
    }
}
