package com.bolt.browser.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream

/** v1.3.39: capture callback (android.util.Consumer is a hidden API — own type). */
fun interface OnShotDone { fun done(r: ShotResult) }

/** v1.3.39: capture outcome (ok / saved Uri / message). */
class ShotResult(@JvmField val ok: Boolean, @JvmField val uri: android.net.Uri?,
                 @JvmField val msg: String)

/**
 * v1.3.39 — FULL-PAGE SCREENSHOT (new code → Kotlin; menu-3 item 3).
 * v1.3.40 — REWRITTEN ON PixelCopy (field report: "the long screenshot
 * remains mostly blank"). The old engine drew the view into a SOFTWARE
 * canvas per strip — modern GPU-composited WebView frames do not render
 * into software canvases, so every strip came out blank. The new engine
 * copies each strip from the WINDOW COMPOSITOR via [PixelSnap]/PixelCopy,
 * which captures what is actually on screen, GPU textures included.
 *
 * Still stitch-scrolling: walk the WebView down its content height one
 * viewport at a time, let each frame settle ~260 ms, copy the visible
 * strip, blit it into the master bitmap, restore the original scroll.
 * Fixed headers/footers will repeat across strips — the known trade-off
 * of every scroll-stitch implementation (Chrome's full-page capture has
 * the same artifact) and the reason the result is capped at 16000 px.
 *
 * Saves PNG to Downloads via MediaStore (29+) or the classic path, then
 * hands the Uri back for the share sheet.
 */
object FullShot {

    private const val CAP = 16000
    private const val SETTLE = 260L

    /**
     * [onDone] runs on the MAIN thread with the result; capture itself
     * orchestrates on the main thread (view drawing must) while the save
     * runs on a worker.
     */
    @JvmStatic
    fun capture(a: Activity, w: WebView, onDone: OnShotDone) {
        capture(a, w, "Bolt-fullpage-", onDone)
    }

    /** v1.3.87 — named variant (the AI exporter reuses the stitch engine
     * under its own filename). */
    @JvmStatic
    fun capture(a: Activity, w: WebView, namePrefix: String, onDone: OnShotDone) {
        capture(a, w, namePrefix, 0, onDone)
    }

    /** v1.3.96 — the height-aware variant: [jsHeight] is the DOM's own
     * scrollHeight (AiExport.heightJs, CSS px — the same units
     * contentHeight uses); 0 falls back to contentHeight. Inner-scroller
     * SPAs never report their full extent through contentHeight, and the
     * stitch then missed everything below the fold ("does not cover the
     * whole webpage"). The PNG master bitmap keeps the CAP — one
     * vw×pageH RGB_565 allocation is memory-bound on 2 GB devices. */
    @JvmStatic
    fun capture(a: Activity, w: WebView, namePrefix: String, jsHeight: Int,
            onDone: OnShotDone) {
        val h = Handler(Looper.getMainLooper())
        try {
            val ch = if (jsHeight > 0) jsHeight.toFloat() else w.contentHeight.toFloat()
            val pageH = Math.round(ch * w.scale).coerceAtMost(CAP)
            val vw = w.width
            val vh = w.height
            if (vw <= 0 || vh <= 0 || pageH <= 0) {
                onDone.done(ShotResult(false, null, "Page is not ready yet"))
                return
            }
            val sx = w.scrollX
            val sy = w.scrollY
            // master is 565 (halves the memory of a 1080×16000 strip);
            // PixelCopy needs an 8888 temp per strip — blitted then freed.
            val bmp = Bitmap.createBitmap(vw, pageH, Bitmap.Config.RGB_565)
            val cv = Canvas(bmp)
            cv.drawColor(Color.WHITE)
            cv.clipRect(0, 0, vw, pageH)
            val steps = (pageH + vh - 1) / vh
            val step = intArrayOf(0)
            // one iteration: scroll to the next offset, settle, copy the
            // visible strip FROM THE WINDOW (GPU truth), blit, continue.
            val loop = object : Runnable {
                override fun run() {
                    try {
                        if (step[0] > steps) {
                            w.scrollTo(sx, sy)
                            persist(a, bmp, namePrefix, onDone)
                            return
                        }
                        val maxY = (pageH - vh).coerceAtLeast(0)
                        val y = (step[0] * vh).coerceAtMost(maxY)
                        w.scrollTo(0, y)
                        h.postDelayed({
                            try {
                                // the ACTUAL scroll after clamping is the
                                // strip's true offset inside the page
                                val yOff = w.scrollY
                                com.bolt.browser.util.PixelSnap.viewShot(a, w) { b ->
                                    try {
                                        val piece = b ?: softwarePiece(w, vw, vh)
                                        if (piece != null) {
                                            cv.save()
                                            cv.translate(0f, -yOff.toFloat())
                                            cv.drawBitmap(piece, 0f, 0f, null)
                                            cv.restore()
                                            piece.recycle()
                                        }
                                    } catch (t: Throwable) {
                                        try { b?.recycle() } catch (ignored: Throwable) { }
                                    }
                                    step[0]++
                                    h.post(this)
                                }
                            } catch (t: Throwable) {
                                step[0]++
                                h.post(this)
                            }
                        }, SETTLE)
                    } catch (t: Throwable) {
                        w.scrollTo(sx, sy)
                        onDone.done(ShotResult(false, null, "Capture failed"))
                    }
                }
            }
            h.post(loop)
        } catch (t: Throwable) {
            onDone.done(ShotResult(false, null, "Capture failed"))
        }
    }

    /** software fallback for one strip (old APIs / detached windows) —
     * may be blank on GPU-accelerated devices, but keeps the loop alive. */
    private fun softwarePiece(w: WebView, vw: Int, vh: Int): Bitmap? {
        return try {
            val b = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            w.draw(c)
            b
        } catch (t: Throwable) { null }
    }

    private fun persist(a: Activity, bmp: Bitmap, onDone: OnShotDone) {
        persist(a, bmp, "Bolt-fullpage-", onDone)
    }

    /** v1.3.87 — named variant for the AI exporter. */
    private fun persist(a: Activity, bmp: Bitmap, namePrefix: String, onDone: OnShotDone) {
        Thread({
            var uri: android.net.Uri? = null
            var msg = "Saved to Downloads"
            try {
                val name = namePrefix + System.currentTimeMillis() + ".png"
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val cv = android.content.ContentValues()
                    cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                    uri = a.contentResolver.insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    if (uri == null) throw IllegalStateException("insert failed")
                    a.contentResolver.openOutputStream(uri)?.use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (dir != null && !dir.exists()) dir.mkdirs()
                    val f = File(dir, name)
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    uri = android.net.Uri.fromFile(f)
                }
            } catch (t: Throwable) {
                msg = "Save failed"
            }
            try { bmp.recycle() } catch (t: Throwable) { }
            val finalUri = uri
            val finalMsg = msg
            a.runOnUiThread { onDone.done(ShotResult(finalUri != null, finalUri, finalMsg)) }
        }, "bolt-fullshot").start()
    }

    /** the share intent for a finished capture. */
    @JvmStatic
    fun share(a: Activity, uri: android.net.Uri?) {
        if (uri == null) return
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_SEND)
            i.type = "image/png"
            i.putExtra(android.content.Intent.EXTRA_STREAM, uri)
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(android.content.Intent.createChooser(i, "Share full-page screenshot"))
        } catch (t: Throwable) {
            Fx.toast(a, "Share failed")
        }
    }

    /** hide bolt's chrome so the capture is purely the page (caller's job). */
    @JvmStatic
    fun barsVisible(a: Activity, visible: Boolean) {
        try {
            val tg = a.findViewById<View>(com.bolt.browser.R.id.top_group)
            val bb = a.findViewById<View>(com.bolt.browser.R.id.bottombar)
            tg?.visibility = if (visible) View.VISIBLE else View.GONE
            bb?.visibility = if (visible) View.VISIBLE else View.GONE
        } catch (t: Throwable) { }
    }
}
