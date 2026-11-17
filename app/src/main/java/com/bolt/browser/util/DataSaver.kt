package com.bolt.browser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * v1.3.39 — DATA SAVER (new code → Kotlin; menu-3 item 10, default OFF).
 *
 * Blocks image sub-resources while the connection is metered (or always,
 * when forced): BoltClient.shouldInterceptRequest hands out a 1×1
 * transparent PNG instead of fetching, and every blocked request is
 * counted so the toggle row can show the traffic it saved (estimated from
 * the response sizes we never downloaded is impossible without fetching —
 * so the counter reports blocked requests + a conservative KB estimate).
 */
object DataSaver {

    const val PREF = "dataSaver"            // master (default OFF)
    const val PREF_FORCE = "dataSaverForce" // block even on unmetered

    /** the 1×1 transparent PNG every blocked image gets. */
    private val EMPTY_PNG: ByteArray by lazy {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val bo = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, bo)
        b.recycle()
        bo.toByteArray()
    }

    @JvmStatic
    fun enabled(c: Context): Boolean = Prefs.get(c).b(PREF, false)

    /** true when images should be dropped right now. */
    @JvmStatic
    fun active(c: Context): Boolean {
        if (!enabled(c)) return false
        if (Prefs.get(c).b(PREF_FORCE, false)) return true
        return try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            cm.isActiveNetworkMetered      // API 24+ (our minSdk)
        } catch (t: Throwable) { false }
    }

    /** image-ish heuristic for a sub-resource request. */
    @JvmStatic
    fun looksLikeImage(url: String?, accept: String?): Boolean {
        if (url == null) return false
        val u = url.lowercase()
        if (u.startsWith("data:")) return false
        val ext = u.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        if (ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "ico", "svg"))
            return true
        val a = (accept ?: "").lowercase()
        return a.startsWith("image/") || a.contains("image/")
    }

    /** count a blocked request (called on the IO threads — synchronized). */
    @JvmStatic
    fun noteBlocked(c: Context) {
        try {
            synchronized(this) {
                val p = Prefs.get(c)
                p.set("dsCount", p.i("dsCount", 0) + 1)
                p.set("dsEst", p.i("dsEst", 0) + 48)      // ~48 KB avg per image
            }
        } catch (t: Throwable) { }
    }

    /** reset the counters (when the toggle turns off). */
    @JvmStatic
    fun reset(c: Context) {
        try {
            Prefs.get(c).set("dsCount", 0)
            Prefs.get(c).set("dsEst", 0)
        } catch (t: Throwable) { }
    }

    /** "blocked N requests · ~X MB" chip text. */
    @JvmStatic
    fun stats(c: Context): String {
        val p = Prefs.get(c)
        val n = p.i("dsCount", 0)
        val kb = p.i("dsEst", 0)
        val mb = kb / 1024.0
        return String.format(java.util.Locale.US, "%d · ~%.1f MB", n, mb)
    }

    /** the empty response BoltClient returns for a blocked image. */
    @JvmStatic
    fun emptyImage(): android.webkit.WebResourceResponse {
        val r = android.webkit.WebResourceResponse("image/png", null,
                ByteArrayInputStream(EMPTY_PNG))
        // cacheable: every image slot on the page asks again otherwise
        try {
            val h = java.util.Collections.singletonMap("Cache-Control", "max-age=3600")
            r.responseHeaders = h
        } catch (t: Throwable) { }
        return r
    }
}
