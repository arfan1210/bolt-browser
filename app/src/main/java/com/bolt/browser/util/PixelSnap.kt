package com.bolt.browser.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.webkit.WebView

/**
 * v1.3.40: capture callback (android.util.Consumer is a hidden API — own
 * type, and `fun interface` so Java lambdas map to it directly). */
fun interface OnSnap { fun done(b: Bitmap?) }

/**
 * v1.3.40 — HARDWARE-SAFE CONTENT CAPTURE (new code → Kotlin).
 *
 * Root cause of two v1.3.39 field bugs ("glass still never detects white
 * pages", "the long screenshot remains mostly blank"): `view.draw(canvas)`
 * on a SOFTWARE bitmap does not render modern GPU-composited WebView
 * content — Chromium textures live on the GPU and a software draw of the
 * view hierarchy paints the page area blank. Both the adaptive-glass
 * luminance sampling and FullShot stitched from exactly such draws, so
 * both saw blank content on hardware-accelerated devices.
 *
 * THE FIX: [android.view.PixelCopy] — the window compositor's own copy
 * path (API 26+). It copies what is actually ON SCREEN, GPU textures
 * included. API 24/25 fall back to the (imperfect) software draw — those
 * old devices almost always run software rendering anyway.
 *
 * Every helper takes the strip it needs, not the whole window, and
 * reports success/failure so callers can fall back.
 */
object PixelSnap {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Copy a strip of the ACTIVITY WINDOW into a bitmap, synchronously
     * (blocks the calling thread until the copy lands; safe from the main
     * thread because the result is consumed via [onDone] posted back).
     *
     * @param rootInWindow the view whose window coords define the strip
     * @param top true = strip at the view's TOP edge; false = BOTTOM edge
     * @param hPx strip height in px
     */
    @JvmStatic
    fun strip(a: Activity, rootInWindow: View, top: Boolean, hPx: Int, onDone: OnSnap) {
        try {
            val w: Window = a.window ?: return onDone.done(null)
            if (rootInWindow.width <= 0 || rootInWindow.height <= 0) return onDone.done(null)
            val loc = IntArray(2)
            rootInWindow.getLocationInWindow(loc)
            val h = Math.min(hPx, rootInWindow.height)
            val r = Rect(loc[0],
                    if (top) loc[1] else loc[1] + rootInWindow.height - h,
                    loc[0] + rootInWindow.width,
                    if (top) loc[1] + h else loc[1] + rootInWindow.height)
            copyRect(a, r, onDone)
        } catch (t: Throwable) { onDone.done(null) }
    }

    /**
     * The general form: copy an arbitrary window rect. Runs on a worker,
     * hands the bitmap back on the MAIN thread (PixelCopy itself must be
     * called from a thread with a Looper — we use the main one).
     */
    @JvmStatic
    fun copyRect(a: Activity, r: Rect, onDone: OnSnap) {
        try {
            if (r.width() <= 0 || r.height() <= 0) return onDone.done(null)
            if (r.width() * r.height() > 40_000_000) return onDone.done(null)   // sanity cap
            val w: Window = a.window ?: return onDone.done(null)
            val bmp = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try {
                    PixelCopy.request(w, r, bmp, { res ->
                        if (res == PixelCopy.SUCCESS) main.post { onDone.done(bmp) }
                        else main.post { onDone.done(null) }
                    }, main)
                } catch (t: Throwable) {
                    // some OEMs throw on detached windows — software path
                    softwareFallback(a, r, bmp, onDone)
                }
            } else {
                softwareFallback(a, r, bmp, onDone)
            }
        } catch (t: Throwable) { onDone.done(null) }
    }

    /** API 24/25: software draw of the decor (best effort, may be blank). */
    private fun softwareFallback(a: Activity, r: Rect, bmp: Bitmap,
                                 onDone: OnSnap) {
        try {
            val dv = a.window?.decorView ?: return onDone.done(null)
            val cv = Canvas(bmp)
            val loc = IntArray(2)
            dv.getLocationInWindow(loc)
            cv.translate((-loc[0]).toFloat(), (-loc[1]).toFloat())
            cv.clipRect(r)
            dv.draw(cv)
            main.post { onDone.done(bmp) }
        } catch (t: Throwable) { onDone.done(null) }
    }

    /**
     * Synchronous luminance sample of a strip — for the adaptive glass
     * (needs an answer NOW, throttling is the caller's job). Returns
     * null when the copy is not available; callers keep their last value.
     *
     * IMPORTANT: must be called from a BACKGROUND thread (it blocks).
     * The glass path calls it from a single-thread executor below.
     */
    @JvmStatic
    fun stripLumSync(a: Activity, rootInWindow: View, top: Boolean, hPx: Int): Float? {
        try {
            val done = arrayOfNulls<Bitmap>(1)
            val gate = Object()
            val called = booleanArrayOf(false)
            strip(a, rootInWindow, top, hPx, OnSnap { b ->
                synchronized(gate) {
                    done[0] = b
                    called[0] = true
                    gate.notifyAll()
                }
            })
            synchronized(gate) {
                var waited = 0L
                while (!called[0] && waited < 400) {
                    gate.wait(50)
                    waited += 50
                }
            }
            val b = done[0] ?: return null
            val lum = GlassAdapt.luminance(b)
            try { b.recycle() } catch (ignored: Throwable) { }
            return lum
        } catch (t: Throwable) { return null }
    }

    /** the single background executor the glass sampler rides. */
    private val exe = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "bolt-pixelsnap").apply { isDaemon = true }
    }

    /** hand a runnable to the sampler thread. */
    @JvmStatic
    fun bg(r: Runnable) {
        try { exe.execute(r) } catch (t: Throwable) { }
    }

    /**
     * Full-page capture via per-strip PixelCopy + stitch (FullShot's new
     * engine, v1.3.40). The WebView is scrolled one viewport at a time
     * (caller's choreography); for each step we copy the strip of the
     * window where the view currently shows content.
     *
     * @param yOffset the amount of page already stitched above the current
     *                viewport (strip goes at bmp height [yOffset]).
     */
    interface StitchSink {
        /** draw the received strip at [yOff] into the page bitmap; true = keep going. */
        fun piece(bmp: Bitmap, yOff: Int, viewTop: Int): Boolean
        fun fail(msg: String)
    }

    /**
     * Copy what the WebView shows on screen right now (the visible
     * viewport) — the capture side of the stitch. Returns via [onDone] on
     * the main thread; null means "could not capture this strip".
     */
    @JvmStatic
    fun viewShot(a: Activity, v: WebView, onDone: OnSnap) {
        try {
            if (v.width <= 0 || v.height <= 0) return onDone.done(null)
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            // subtract scrollbars? WebView reports its bounds in window
            // space already including scrollbars — copy the raw rect.
            val r = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            copyRect(a, r, onDone)
        } catch (t: Throwable) { onDone.done(null) }
    }
}
