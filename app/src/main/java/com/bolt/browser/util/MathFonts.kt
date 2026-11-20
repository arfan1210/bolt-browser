package com.bolt.browser.util

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.4.24 — THE MATH FONT PACK, ON DEMAND (the size law, take two).
 *
 * The user has asked twice for a smaller APK. v1.4.21 shipped
 * jlatexmath-android with its whole bundled font set: 30 TTF binaries,
 * 455,768 B raw — about 470 KB of the 3,325,166 B v1.4.23 APK (TTFs barely
 * deflate). The ENGINE CODE stays bundled (the thin AAR,
 * scripts/thin_jlatexmath.py); the FONT PACK downloads ONCE on the first
 * math render — exactly the v1.3.73 tesseract.js law — into
 * filesDir/jlmfonts, and every later render reads it locally.
 *
 * The hosts are the UPSTREAM repo's own assets, pinned by tag, the same
 * pair tesseract rides (jsDelivr first, raw.githubusercontent fail-over);
 * the files are byte-identical to the AAR's (built from the same tag —
 * spot-verified md5 on the build machine). HTTPS GET, no redirects, size
 * floor per file — the relay discipline.
 *
 * Failure posture (the user's own words): "a failed download falls back to
 * the v1.4.20 LaTeX-subset spans, never a crash". AiRender.mathDrawable
 * returns null while the pack is absent/downloading, the bubble renders
 * the honest subset, and the bubbles that WOULD have carried full TeX are
 * re-rendered the moment the pack lands (AiRender.refreshPendings on the
 * main thread). Metered connections skip the download entirely (the
 * tessdata metered-confirm precedent — but silently: math is a chat
 * nicety; the spans render fine and the next attempt rides a wifi).
 */
object MathFonts {

    private val main = Handler(Looper.getMainLooper())

    /** one serial download thread — never the main thread, never parallel */
    private val POOL: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bolt-math").apply { isDaemon = true }
    }

    /** the pack's home: filesDir/jlmfonts/<fonts-relative path> */
    fun packDir(c: Context): File = File(c.filesDir, "jlmfonts")

    /** written when all 30 fonts landed — the presence check is one stat */
    private fun marker(c: Context): File = File(packDir(c), ".ok")

    /** v1.4.24 — is the pack on disk? (called on the render path — cheap) */
    @JvmStatic
    fun present(c: Context): Boolean {
        val m = marker(c)
        return m.exists() && m.length() > 0
    }

    /** the door the seam class calls: a relative engine path -> pack file */
    @JvmStatic
    fun packFile(c: Context, rel: String): File? {
        if (rel.startsWith("fonts/")) {
            val f = File(packDir(c), rel)
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    /** a downloaded font smaller than this is a truncated/error page */
    private const val MIN_FONT = 1024L

    /** pinned hosts (https GET, no redirects — the relay discipline).
     *  The paths are the upstream repo's own asset tree at tag v0.2.0 —
     *  the exact bytes the AAR bundled. */
    private val DL_HOSTS = arrayOf(
        "https://cdn.jsdelivr.net/gh/noties/jlatexmath-android@0.2.0/jlatexmath/src/main/assets/org/scilab/forge/jlatexmath/fonts/",
        "https://raw.githubusercontent.com/noties/jlatexmath-android/v0.2.0/jlatexmath/src/main/assets/org/scilab/forge/jlatexmath/fonts/")

    /** the exact 30-file list (mirrors scripts/thin_jlatexmath.py's removal
     *  list — edit the two together). Sizes are the upstream AAR's own. */
    private val PACK = arrayOf(
        "base/jlm_cmex10.ttf", "base/jlm_cmmi10.ttf", "base/jlm_cmmib10.ttf",
        "euler/jlm_eufb10.ttf", "euler/jlm_eufm10.ttf",
        "latin/jlm_cmr10.ttf", "latin/jlm_jlmbi10.ttf", "latin/jlm_jlmbx10.ttf",
        "latin/jlm_jlmi10.ttf", "latin/jlm_jlmr10.ttf", "latin/jlm_jlmsb10.ttf",
        "latin/jlm_jlmsbi10.ttf", "latin/jlm_jlmsi10.ttf", "latin/jlm_jlmss10.ttf",
        "latin/jlm_jlmtt10.ttf",
        "latin/optional/jlm_cmbx10.ttf", "latin/optional/jlm_cmbxti10.ttf",
        "latin/optional/jlm_cmss10.ttf", "latin/optional/jlm_cmssbx10.ttf",
        "latin/optional/jlm_cmssi10.ttf", "latin/optional/jlm_cmti10.ttf",
        "latin/optional/jlm_cmtt10.ttf",
        "maths/jlm_cmbsy10.ttf", "maths/jlm_cmsy10.ttf", "maths/jlm_msam10.ttf",
        "maths/jlm_msbm10.ttf", "maths/jlm_rsfs10.ttf", "maths/jlm_special.ttf",
        "maths/jlm_stmary10.ttf", "maths/optional/jlm_dsrom10.ttf")

    /** the whole pack, raw bytes (drives the metered skip note + diag) */
    private const val PACK_BYTES = 455768L

    /** one failed attempt per process — no hammering a dead network */
    @Volatile private var failedProc = false
    private val inFlight = AtomicBoolean(false)
    private val onReadyList = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Ensure the pack is on disk. Returns immediately: present -> the
     * callback runs on main; absent -> the download starts on the math
     * thread and the callback fires when it lands (or never — the callers
     * already rendered the honest fallback). Thread-safe; extra callers
     * during a download just queue on the same completion.
     */
    @JvmStatic
    fun ensureAsync(c: Context, onReady: (() -> Unit)? = null) {
        val app = c.applicationContext
        if (present(app)) {
            if (onReady != null) main.post(onReady)
            return
        }
        if (onReady != null && !onReadyList.contains(onReady)) onReadyList.add(onReady)
        if (failedProc) return
        if (!inFlight.compareAndSet(false, true)) return
        POOL.execute {
            val ok = try {
                ensureBlocking(app)
            } catch (t: Throwable) {
                false
            }
            inFlight.set(false)
            if (ok) {
                val cbs = ArrayList(onReadyList)
                onReadyList.clear()
                for (cb in cbs) {
                    try { main.post(cb) } catch (t: Throwable) {}
                }
            }
        }
    }

    /** BLOCKING — the math thread only. Downloads every missing font
     *  (tmp file + atomic rename, both hosts per file), writes the marker
     *  on full success. Metered connections skip everything (retry rides
     *  the next unmetered render). */
    private fun ensureBlocking(c: Context): Boolean {
        val dir = packDir(c)
        if (!dir.exists()) dir.mkdirs()
        var downloaded = 0
        var bytes = 0L
        for (rel in PACK) {
            val f = File(dir, rel)
            if (f.exists() && f.length() >= MIN_FONT) continue
            val tmp = File(dir, "$rel.tmp")
            if (dlOne(c, rel, tmp)) {
                f.parentFile?.mkdirs()
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) {
                    try { tmp.delete() } catch (t: Throwable) {}
                    fail(c, "rename $rel")
                    return false
                }
                downloaded++
                bytes += f.length()
            } else {
                try { tmp.delete() } catch (t: Throwable) {}
                fail(c, "dl $rel")
                return false
            }
        }
        // the marker: one stat on the render path
        try {
            val m = marker(c)
            val w = java.io.PrintWriter(FileOutputStream(m))
            w.println("v1.4.24 pack: 30 fonts, " + PACK_BYTES + " B upstream tag v0.2.0")
            w.close()
        } catch (t: Throwable) {}
        try {
            Diag.log(c, "math dl ok files=$downloaded bytes=$bytes" +
                    (if (downloaded == 0) " (already present)" else ""))
        } catch (t: Throwable) {}
        try {
            if (downloaded > 0) Fx.toast(c, "Math fonts ready — formulas render fully")
        } catch (t: Throwable) {}
        return true
    }

    /** one font through the host pair; tmp file; false on failure. */
    private fun dlOne(c: Context, rel: String, tmp: File): Boolean {
        val metered = try {
            (c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.isActiveNetworkMetered ?: false
        } catch (t: Throwable) { false }
        if (metered) {
            try {
                Diag.log(c, "math metered skip (pack " + (PACK_BYTES / 1024) + " KB)")
            } catch (t: Throwable) {}
            return false
        }
        var hostUsed = -1
        for ((hi, host) in DL_HOSTS.withIndex()) {
            try {
                val conn = URL(host + rel).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = false
                try {
                    if (conn.responseCode != 200) continue
                    FileOutputStream(tmp).use { fos ->
                        conn.inputStream.use { ins ->
                            val buf = ByteArray(16384)
                            var n: Int
                            while (ins.read(buf).also { n = it } >= 0) fos.write(buf, 0, n)
                        }
                    }
                } finally {
                    try { conn.disconnect() } catch (t: Throwable) {}
                }
                if (tmp.length() >= MIN_FONT) {
                    hostUsed = hi
                    break
                }
            } catch (t: Throwable) { /* fail over to the next pinned host */ }
        }
        return hostUsed >= 0
    }

    private fun fail(c: Context, detail: String) {
        failedProc = true
        try { Diag.log(c, "math dl fail $detail") } catch (t: Throwable) {}
    }
}
