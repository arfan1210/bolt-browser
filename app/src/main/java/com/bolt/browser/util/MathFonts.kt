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
 * v1.4.25 — THE CORE-FONT REBUY + THE RETRY LAW (reliability over the last
 * ~100 KB).
 *
 * THE FIELD CORPSE: "math dl fail dl base/jlm_cmex10.ttf" — the v1.4.24
 * font-pack download was (a) skipped ENTIRELY on metered connections, (b)
 * locked out for the whole process after ONE failure (failedProc), (c) had
 * no manual retry. On the user's metered mobile data the pack never landed,
 * so EVERY formula — a plain fraction included — rendered through the weak
 * v1.4.20 subset spans.
 *
 * THE FIX:
 *  - The 7 CORE fonts ride the APK again (mirrored in
 *    scripts/thin_jlatexmath.py's CORE set): cmex10 (extensible
 *    delimiters/big operators), cmmi10 (math italic), cmmib10 (bold math
 *    italic), cmr10 (roman digits/parens), cmsy10 (symbols/greek
 *    variants), msam10 + msbm10 (AMS symbols, blackboard bold) —
 *    fractions, matrices, Greek, integrals, sums and AMS symbols work
 *    OFFLINE and ON METERED data immediately. The engine loads typefaces
 *    LAZILY (FontInfo.getFont, verified against upstream v0.2.0), so the
 *    23 missing OPTIONAL fonts cannot break engine init — only a formula
 *    that actually needs one (\mathfrak, \mathscr, rare CM variants)
 *    throws, AiRender.mathDrawable catches, and the v1.4.20 subset shows.
 *  - The remaining 23 OPTIONAL fonts (euler/fraktur, optional CM shapes,
 *    special/stmary/dsrom, rsfs) stay ON-DEMAND for advanced TeX — same
 *    hosts, same filesDir/jlmfonts cache, same marker.
 *  - THE RETRY LAW: a failed attempt no longer locks the process out —
 *    the next math render retries after a 20 s back-off (no hammering a
 *    dead network, but also no corpse), and a MANUAL door forces an
 *    immediate attempt that ignores both the back-off and the metered
 *    skip (the user asked for it with their own tap). Status is first-
 *    class: [status] reports core/downloading/ready/failed-retry and the
 *    Bolt AI panel shows a live "Math fonts" chip.
 */
object MathFonts {

    private val main = Handler(Looper.getMainLooper())

    /** one serial download thread — never the main thread, never parallel */
    private val POOL: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bolt-math").apply { isDaemon = true }
    }

    /** the pack's home: filesDir/jlmfonts/<fonts-relative path> */
    fun packDir(c: Context): File = File(c.filesDir, "jlmfonts")

    /** written when all 23 optional fonts landed — the presence check is one stat */
    private fun marker(c: Context): File = File(packDir(c), ".ok")

    /** v1.4.25 — is the OPTIONAL pack on disk? (core math needs nothing) */
    @JvmStatic
    fun present(c: Context): Boolean {
        val m = marker(c)
        return m.exists() && m.length() > 0
    }

    /**
     * v1.4.25 — THE 7 CORE fonts, bundled IN the APK by
     * scripts/thin_jlatexmath.py (the CORE set there mirrors THIS one —
     * edit the two together). Present offline/metered; covers fractions,
     * matrices, Greek, integrals, sums, AMS symbols.
     */
    val CORE = setOf(
            "base/jlm_cmex10.ttf", "base/jlm_cmmi10.ttf", "base/jlm_cmmib10.ttf",
            "latin/jlm_cmr10.ttf", "maths/jlm_cmsy10.ttf", "maths/jlm_msam10.ttf",
            "maths/jlm_msbm10.ttf")

    /**
     * v1.4.25 — THE STATUS DOOR (downloading / ready / failed-retry / core):
     * what the manual UI and the diag read. Never throws, never touches
     * the network.
     */
    @JvmStatic
    fun status(c: Context): String {
        if (present(c)) return "ready"
        if (inFlight.get()) return "downloading"
        if (lastFailAt.get() > 0) return "failed-retry"
        return "core"          // core math works; the optional pack is absent
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

    /** v1.4.25 — the OPTIONAL 23-file list (the core 7 moved INTO the APK;
     *  mirrors scripts/thin_jlatexmath.py's removal list — edit the two
     *  together). Sizes are the upstream AAR's own. */
    private val PACK = arrayOf(
        "euler/jlm_eufb10.ttf", "euler/jlm_eufm10.ttf",
        "latin/jlm_jlmbi10.ttf", "latin/jlm_jlmbx10.ttf",
        "latin/jlm_jlmi10.ttf", "latin/jlm_jlmr10.ttf", "latin/jlm_jlmsb10.ttf",
        "latin/jlm_jlmsbi10.ttf", "latin/jlm_jlmsi10.ttf", "latin/jlm_jlmss10.ttf",
        "latin/jlm_jlmtt10.ttf",
        "latin/optional/jlm_cmbx10.ttf", "latin/optional/jlm_cmbxti10.ttf",
        "latin/optional/jlm_cmss10.ttf", "latin/optional/jlm_cmssbx10.ttf",
        "latin/optional/jlm_cmssi10.ttf", "latin/optional/jlm_cmti10.ttf",
        "latin/optional/jlm_cmtt10.ttf",
        "maths/jlm_cmbsy10.ttf", "maths/jlm_rsfs10.ttf", "maths/jlm_special.ttf",
        "maths/jlm_stmary10.ttf", "maths/optional/jlm_dsrom10.ttf")

    /** the optional pack, raw bytes (drives the metered skip note + diag) */
    private const val PACK_BYTES = 289048L

    /**
     * v1.4.25 — THE RETRY LAW: a failed attempt no longer poisons the
     * process (the v1.4.24 corpse: failedProc). The next math render
     * retries once this many ms have passed; the MANUAL door ignores the
     * back-off entirely.
     */
    private val lastFailAt = java.util.concurrent.atomic.AtomicLong(0L)
    private const val RETRY_GAP_MS = 20_000L

    private val inFlight = AtomicBoolean(false)
    private val onReadyList = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Ensure the OPTIONAL pack is on disk. Returns immediately: present ->
     * the callback runs on main; absent -> the download starts on the math
     * thread and the callback fires when it lands (or never — the callers
     * already rendered through the CORE fonts or the honest subset).
     * [force] = the manual door: skips the metered guard AND the retry
     * back-off (the user tapped; their data, their call).
     */
    @JvmStatic
    @JvmOverloads
    fun ensureAsync(c: Context, onReady: (() -> Unit)? = null, force: Boolean = false) {
        val app = c.applicationContext
        if (present(app)) {
            if (onReady != null) main.post(onReady)
            return
        }
        if (onReady != null && !onReadyList.contains(onReady)) onReadyList.add(onReady)
        if (!force && inFlight.get()) return
        // v1.4.25 — the retry back-off (auto path only): the next render
        // retries once the gap passes; a manual tap never waits
        if (!force && System.currentTimeMillis() - lastFailAt.get() < RETRY_GAP_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        POOL.execute {
            val ok = try {
                ensureBlocking(app, force)
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

    /**
     * v1.4.25 — THE MANUAL DOOR (the "Download full math fonts" action):
     * force the optional-pack attempt NOW, metered included, back-off
     * ignored. Returns the status the caller toasts (never throws).
     */
    @JvmStatic
    fun downloadNow(c: Context): String {
        val app = c.applicationContext
        val st = status(app)
        if (st == "ready") return "ready"
        ensureAsync(app, null, true)
        return status(app)          // "downloading" (or "core" as it starts)
    }

    /** BLOCKING — the math thread only. Downloads every missing optional
     *  font (tmp file + atomic rename, both hosts per file), writes the
     *  marker on full success. The AUTO path skips metered connections
     *  (core math already works; the retry rides the next render); the
     *  FORCED (manual) path proceeds on metered data — the user asked. */
    private fun ensureBlocking(c: Context, force: Boolean): Boolean {
        val dir = packDir(c)
        if (!dir.exists()) dir.mkdirs()
        val metered = try {
            (c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.isActiveNetworkMetered ?: false
        } catch (t: Throwable) { false }
        if (metered && !force) {
            try {
                Diag.log(c, "math dl metered skip (optional " + (PACK_BYTES / 1024) +
                        " KB; core fonts are bundled — tap Math fonts to force)")
            } catch (t: Throwable) {}
            return false            // NOT a failure — no back-off, no lockout
        }
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
            w.println("v1.4.25 optional pack: 23 fonts, " + PACK_BYTES +
                    " B (core 7 bundled in the APK) upstream tag v0.2.0")
            w.close()
        } catch (t: Throwable) {}
        lastFailAt.set(0L)
        try {
            Diag.log(c, "math dl ok files=$downloaded bytes=$bytes" +
                    (if (downloaded == 0) " (already present)" else ""))
        } catch (t: Throwable) {}
        try {
            if (downloaded > 0) Fx.toast(c, "Math fonts ready — advanced TeX enabled")
        } catch (t: Throwable) {}
        return true
    }

    /** one font through the host pair; tmp file; false on failure. */
    private fun dlOne(c: Context, rel: String, tmp: File): Boolean {
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

    /** v1.4.25 — a failure STAMPS the retry clock (the next render retries
     *  after the back-off); it no longer locks the process out. */
    private fun fail(c: Context, detail: String) {
        lastFailAt.set(System.currentTimeMillis())
        try { Diag.log(c, "math dl fail $detail (core math unaffected; retries on next render)") }
        catch (t: Throwable) {}
    }
}
