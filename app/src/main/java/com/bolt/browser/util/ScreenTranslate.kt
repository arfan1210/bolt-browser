package com.bolt.browser.util

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.3.72 — TRANSLATE SCREEN (new code → Kotlin; the standing language
 * rule). The v1.3.71 field report asked for it plainly: "add something
 * like translate screen (you know so that it can translate even on
 * images like for example manga in japanese to english)".
 *
 * v1.3.73 — THE SIZE LAW REWRITE. The v1.3.72 build shipped ML Kit's
 * BUNDLED OCR models and the APK jumped 2,541,549 B -> 23,426,101 B;
 * the user's law is a super-light base APK (≤ 5-6 MB — "22mb is much
 * higher than I can afford"). The bundled models are gone. OCR now runs
 * in a headless 1×1 WebView ([OcrHost]) on tesseract.js 5.1.1 (pinned,
 * LSTM-only wasm core) — the engine and the tessdata_fast language
 * models are fetched ONCE on demand and cached; the APK carries only a
 * 4 KB host page. No Google Play Services, no bundled models, works on
 * every device whose WebView has WebAssembly.
 *
 * v1.3.74 — THE MANGA PASS + THE AI BRANCH (the user's report: "it is
 * not detecting all the texts I see in a random japanese manga panel,
 * and the part it does detect, I am seeing random gibberish"). Two
 * cures ride the same on-demand law:
 *  - MANGA PREPROCESSING: a fit-to-screen manga capture leaves CJK
 *    strokes 12-18 px tall — below tesseract's comfort zone (that is
 *    the missed lines AND the gibberish). Manga scripts now feed the
 *    engine a ×2-upscaled, grayscale, percentile-contrast-stretched
 *    PNG (line art hates JPEG ringing), and a "Manga HD" script pulls
 *    tessdata_best jpn_vert (14.3 MB, one time, metered-confirmed) for
 *    the accuracy the fast model cannot reach on stylized art.
 *  - THE AI SCRIPT (row #1 in the picker): the screenshot goes to a
 *    vision LLM (Gemini free tier or any OpenAI-compatible endpoint)
 *    that reads manga better than any OCR the APK could afford — see
 *    util/AiBridge.kt for the feasibility verdict on the user's
 *    account-linking idea. The picker opens the key setup when no key
 *    is configured; a per-run confirm states exactly what leaves the
 *    device. Boxes come back on a 1000×1000 normalized grid.
 *
 * v1.3.75 — THE HONEST BUGFIX PASS (the user's report: "The AI mode also
 * is pretty buggy with no option to change inputs I once make"). The AI
 * stage's three v1.3.74 wounds: (1) the Gemini reply envelope was never
 * unwrapped, so every Gemini run "found no text" (fixed in AiBridge);
 * (2) the key-setup link navigated the CURRENT TAB — the manga image's
 * own tab — away to AI Studio (now a new tab via the opener
 * BrowserActivity passes in); (3) saving a key dead-ended the run (now
 * the capture continues). The picker gains an "AI settings" neutral
 * button — provider/key/model/base editable ANY time — and the AI
 * request ate a 2200 px image until v1.4.12's payload diet (1280 px
 * q75, ~200 KB — the mobile-network timeout/400/413 class). 
 * startWithScript() is the Bolt AI panel's direct entry.
 *
 * v1.4.22 — THE HYBRID TURN (the release note: "AI screen translate is
 * much more accurate — on-device OCR reads the text and its exact boxes
 * first, then ONE AI request translates the whole screen's text with full
 * context"). The AI path no longer branches away from OCR — it RUNS the
 * same on-device pass, then [runHybridStage] sends the extracted TEXT to
 * AiBridge.textTranslate in ONE numbered-lines request: the model cannot
 * invent box positions (it never sees one — the OCR's own boxes are kept),
 * terminology stays consistent (one reply for the whole screen), and the
 * screenshot is only ever sent when OCR cannot read the screen at all.
 * The old visual path ([runAiStage]) survives as the FALLBACK — full
 * screen first, tiling ONLY on a genuine MAX_TOKENS cut, tiles carrying
 * the full-screen transcript as context.
 *
 * The pipeline:
 *
 *   capture (PixelSnap.viewShot — the window compositor's own PixelCopy
 *     path, the v1.3.40-proven GPU-true capture)
 *     → mode pick (AI — the primary, now the OCR-first hybrid; Basic
 *       OCR — the universal no-AI fallback, EN+JP+CN in one pass;
 *       remembered)
 *     → downscale ≤1600 px + JPEG q90 (the vision fallback's own ask
 *       re-encodes ≤1280 px q75, ~200 KB, only when it fires), pushed to
 *       the OCR WebView in ~200 KB chunks (binder-safe)
 *     → OCR (tesseract.js; language models served from filesDir by
 *       shouldInterceptRequest at https://bolt.ocr/tess — never real
 *       network; paragraph-level units, the same granularity ML Kit's
 *       textBlocks gave; CJK space noise stripped) — BOTH modes ride this
 *       pass since v1.4.22 (the AI one under "— AI (reading)")
 *     → hybrid (v1.4.22): ONE AiBridge.textTranslate request for EVERY
 *       OCR line — numbered lines, the whole screen as context, the
 *       translations written back onto the OCR's OWN boxes (the model
 *       never sees a pixel or a coordinate); Basic: every block through
 *       [PageTranslate.gtx] — the SAME endpoint, SAME SSRF whitelist and
 *       SAME dual-shape parser the page engine rides; one translation
 *       channel everywhere
 *     → overlay (a full-screen dialog: the capture with each block's
 *       translation drawn OVER the original — the box fill and text
 *       color follow the region's sampled luminance, text auto-shrinks
 *       to fit the block, tap a box for the original + Copy, a language
 *       chip re-translates into any target, an OCR chip re-runs with a
 *       different script, Save renders the overlay into the bitmap and
 *       files it under Pictures).
 *
 * Privacy posture (v1.4.22, the hybrid turn): the Basic road's screenshot
 * never leaves the device — and neither does the AI road's, anymore: the
 * on-device OCR reads it and only the EXTRACTED TEXT goes to the provider
 * (one request, always confirmed first); the screenshot itself is only
 * ever sent when OCR cannot read the screen at all (the vision fallback,
 * confirmed separately). The other network touches are (a) the pinned
 * cdn.jsdelivr.net engine download and (b) the same translate endpoint
 * the page translator already uses, carrying the recognized TEXT (never
 * the image). On metered connections the one-time model download asks
 * first.
 */
object ScreenTranslate {

    /** hosts NOTHING of its own — every translation goes through PageTranslate.gtx */
    private val POOL: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "bolt-scr").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** cap per screen — line-level units, so dense pages get more of them
     *  (v1.3.74: 120 -> 160 — a busy manga page is line-dense) */
    private const val MAXBLK = 160

    // ---------------- v1.3.73: the script presets + model law ----------------

    /** v1.3.78 — TWO MODES, AT THE USER'S WORD: "The other modes are
     *  pretty uneffective still, so just remove them all, and keep an
     *  universal alternative screen translate non ai option in case my ai
     *  api is blocked or rate limited as it is free tier." The script zoo
     *  (Manga / Manga HD / JP / CN / EN) is GONE; "ai" is the primary and
     *  "auto" (EN+JP+CN in one pass) is the universal no-AI fallback —
     *  same engine, same overlay, one line on the picker. */
    private val SCRIPT_KEYS = arrayOf("ai", "auto")
    private val SCRIPT_LABELS = arrayOf(
        "AI \u2014 translate (recommended)",
        "Basic OCR \u2014 no AI (works offline of the AI key)")

    private fun langsOf(k: String): Array<String> = when (k) {
        // "ai" never reaches the OCR engine — the pipeline branches first;
        // "auto" is THE universal OCR pass: EN+JP+CN in one run
        else -> arrayOf("chi_sim", "jpn", "eng")
    }

    private fun scriptLabel(k: String): String = if (k == "ai") "AI" else "Basic"

    /** the only langs the downloader + interceptor will ever serve
     *  (v1.3.78: jpn_vert + jpn_vertb left with the manga scripts they
     *  existed for — the on-disk diet deletes their files too) */
    private val LANG_OK = setOf("eng", "jpn", "chi_sim")

    /** pinned model sources (https, GET, no redirects — the relay discipline).
     *  v1.3.74: tessdata_best for the HD alias, with its own fail-over. */
    private val DL_HOSTS = arrayOf(
        "https://cdn.jsdelivr.net/gh/tesseract-ocr/tessdata_fast@main/",
        "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/")
    /** known sizes (bytes) — drives the metered-connection estimate
     *  (v1.3.78: the vertical/best models left with their scripts) */
    private val MODEL_BYTES = mapOf(
        "eng" to 4113088L, "jpn" to 2471260L, "chi_sim" to 2469156L)

    /** v1.3.78 — THE MODEL DIET: the manga scripts are gone, so their
     *  models (jpn_vert fast 3.0 MB + jpn_vertb best 14.3 MB) are dead
     *  weight on disk — part of the "still over 50 mb after closing"
     *  storage bill. Deleted once per process, with the freed bytes named
     *  in the diag line.
     *  v1.3.82: public + @JvmStatic — the UPDATE receiver calls it too
     *  (an app that cannot open — the v1.3.81 launcher trap — never runs
     *  the lazy OCR-path diet, so the legacy tree survived updates
     *  forever; now every APK install sweeps it). */
    @Volatile private var dietDone = false
    @JvmStatic
    fun modelDiet(a: Context) {
        if (dietDone) return
        dietDone = true
        try {
            var freed = 0L
            // v1.3.79: the models live in cacheDir now; the diet also
            // removes the whole legacy filesDir/ocrfast tree on upgrade
            try {
                val legacy = File(a.filesDir, "ocrfast")
                if (legacy.isDirectory) {
                    legacy.listFiles()?.forEach { f ->
                        val n = f.length()
                        if (f.delete() && n > 0) freed += n
                    }
                    try { legacy.delete() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            val dir = File(a.cacheDir, "ocrfast")
            if (!dir.isDirectory) return
            for (lang in arrayOf("jpn_vert", "jpn_vertb")) {
                val f = File(dir, "$lang.traineddata")
                if (f.exists()) {
                    val n = f.length()
                    if (f.delete() && n > 0) freed += n
                }
                try { File(dir, "$lang.traineddata.tmp").delete() } catch (_: Throwable) {}
            }
            if (freed > 0) try {
                Diag.log(a, "scr model diet freed=" + (freed / 1048576) + "mb")
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /** a fast model is never smaller than this — guards truncated downloads */
    private const val MIN_MODEL = 400_000L

    /** remembered target (survives dialog closes within the process) */
    @Volatile private var lastTl: String? = null

    /** remembered OCR script */
    @Volatile private var lastScript: String = "auto"

    /** the live overlay (only one at a time) */
    private var dialog: Dialog? = null
    private var viewRef: ScreenView? = null
    private var schip: TextView? = null

    /** the headless OCR WebView (alive while the overlay is open, for re-runs) */
    private var host: OcrHost? = null

    /** the live progress dialog (model download / engine / recognition) */
    private var prog: ScrProgress? = null

    /** per-run guards: generation token + cancel flag (late callbacks no-op) */
    private val gen = AtomicInteger(0)
    @Volatile private var cancelled = false

    /** v1.3.74: the AI privacy confirm fires ONCE per process (still
     *  honest on the very first send of every run of the app) */
    @Volatile private var aiConfirmed = false

    /** the tab the AI setup link opens in (weak — never outlives the tab) */
    private var aiWeb: java.lang.ref.WeakReference<WebView>? = null

    /** v1.3.75: the NEW-TAB opener BrowserActivity passes in — the "Get a
     *  free Gemini key" link must NOT navigate the user's current tab (the
     *  v1.3.74 flow loaded AI Studio INTO the manga image's tab, destroying
     *  the thing being translated). Null → the old same-tab fallback. */
    @Volatile private var tabOpener: AiBridge.LinkOpener? = null

    /** one OCR block: box in BITMAP coordinates + texts + render cache */
    private class SBlock(val box: Rect, val orig: String, val light: Boolean) {
        @Volatile var trans: String? = null
        @Volatile var rev: Int = 0
        // v1.3.76 — the AI pass's matching kit: sampled surrounding color
        // (0 = not sampled -> legacy light/dark card), the original text's
        // single-line pixel height (font-size matching), vertical flag
        @Volatile var bg: Int = 0
        @Volatile var thPx: Float = 0f
        var layout: StaticLayout? = null
        var layoutRev: Int = -1
        var layoutW: Int = -1
        var layoutH: Int = -1
    }

    /** entry point — BrowserActivity's menu row. */
    @JvmStatic
    fun start(a: Activity, w: WebView?) = start(a, w, null)

    /**
     * v1.3.75: [openUrl] opens the AI-key link in a NEW TAB when the
     * caller can do that (BrowserActivity passes openNewTab); null keeps
     * the v1.3.74 same-tab fallback.
     */
    @JvmStatic
    fun start(a: Activity, w: WebView?, openUrl: AiBridge.LinkOpener?) {
        if (w == null) { Fx.toast(a, "No page to capture"); return }
        aiWeb = java.lang.ref.WeakReference(w)
        tabOpener = openUrl
        try { Diag.log(a, "scr ask") } catch (_: Throwable) {}
        dismiss()
        pickScript(a) { k ->
            lastScript = k
            try { Diag.log(a, "scr script $k") } catch (_: Throwable) {}
            Fx.toast(a, "Reading the screen\u2026")
            try {
                PixelSnap.viewShot(a, w) { b ->
                    try {
                        val bmp = b ?: softwareShot(w)
                        if (bmp == null) {
                            Fx.toast(a, "Could not capture the screen")
                            return@viewShot
                        }
                        try { Diag.log(a, "scr cap w=" + bmp.width + " h=" + bmp.height) }
                        catch (_: Throwable) {}
                        beginPipeline(a, bmp)
                    } catch (t: Throwable) {
                        Fx.toast(a, "Screen translation failed")
                    }
                }
            } catch (t: Throwable) {
                Fx.toast(a, "Screen translation failed")
            }
        }
    }

    /**
     * v1.3.75 — the Bolt AI panel's entry: run ONE script directly, no
     * picker (the panel's "Translate this screen" chip means the AI path;
     * the overlay's OCR: chip still re-picks any script afterwards).
     */
    @JvmStatic
    fun startWithScript(a: Activity, w: WebView?, script: String, openUrl: AiBridge.LinkOpener?) {
        if (w == null) { Fx.toast(a, "No page to capture"); return }
        aiWeb = java.lang.ref.WeakReference(w)
        tabOpener = openUrl
        lastScript = script
        try { Diag.log(a, "scr ask ai") } catch (_: Throwable) {}
        dismiss()
        Fx.toast(a, "Reading the screen\u2026")
        try {
            PixelSnap.viewShot(a, w) { b ->
                try {
                    val bmp = b ?: softwareShot(w)
                    if (bmp == null) {
                        Fx.toast(a, "Could not capture the screen")
                        return@viewShot
                    }
                    try { Diag.log(a, "scr cap w=" + bmp.width + " h=" + bmp.height) }
                    catch (_: Throwable) {}
                    beginPipeline(a, bmp)
                } catch (t: Throwable) {
                    Fx.toast(a, "Screen translation failed")
                }
            }
        } catch (t: Throwable) {
            Fx.toast(a, "Screen translation failed")
        }
    }

    /** API 24/25 / detached-window fallback (may be blank — best effort). */
    private fun softwareShot(w: WebView): Bitmap? {
        return try {
            if (w.width <= 0 || w.height <= 0) return null
            val b = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
            w.draw(Canvas(b))
            b
        } catch (t: Throwable) { null }
    }

    // ---------------- the pipeline (capture → models → OCR → blocks) ----------

    /** encode result handed to the OCR stage */
    private class Enc(val b64: String, val w: Int, val h: Int)

    private fun beginPipeline(a: Activity, bmp: Bitmap) {
        val g = gen.incrementAndGet()
        cancelled = false
        POOL.execute {
            try {
                pipeline(a, bmp, g)
            } catch (t: Throwable) {
                failOcr(a, bmp, g, "pipe", null)
            }
        }
    }

    /** v1.3.74: the shared POOL-thread pipeline — the AI script branches
     *  before any OCR/model work; manga scripts get the HD preprocess.
     *  v1.4.22 — THE HYBRID REORDER: the AI script no longer branches AWAY
     *  from OCR — it RUNS the same on-device read the Basic mode rides
     *  (the same models, the same encode), and the done callback's hybrid
     *  flag hands the lines to runHybridStage. Only a model download
     *  failure skips straight to the vision fallback — without the
     *  models there is nothing on-device to read, and "ai" must not die
     *  where the Basic road would (the fallback reads it instead). */
    private fun pipeline(a: Activity, bmp: Bitmap, g: Int) {
        if (gen.get() != g) return
        try { modelDiet(a) } catch (_: Throwable) {}
        val k = lastScript
        if (k == "ai") {
            if (gen.get() != g) return
            val langs = langsOf(k)
            if (!ensureModels(a, bmp, g, langs)) {
                if (gen.get() != g) return
                if (cancelled) cancelOcr(a, bmp, g)
                else {
                    // v1.4.22 — the OCR models are what makes the hybrid
                    // on-device; without them the vision fallback is the
                    // only reader left (the user still gets a translate)
                    try { Diag.log(a, "scr hybrid models-fail -> vision") } catch (_: Throwable) {}
                    runAiStage(a, bmp, g)
                }
                return
            }
            if (gen.get() != g) return
            val enc = encodeFor(bmp, k)
            if (enc == null) { failOcr(a, bmp, g, "encode", "Could not read the screen"); return }
            main.post { runOcrStage(a, bmp, enc, langs, g, true) }
            return
        }
        val enc = encodeFor(bmp, k)
        if (gen.get() != g) return
        if (enc == null) { failOcr(a, bmp, g, "encode", "Could not read the screen"); return }
        val langs = langsOf(k)
        if (!ensureModels(a, bmp, g, langs)) {
            if (gen.get() != g) return
            if (cancelled) cancelOcr(a, bmp, g)
            else failOcr(a, bmp, g, "dl", "Couldn't download OCR data — check connection")
            return
        }
        if (gen.get() != g) return
        main.post { runOcrStage(a, bmp, enc, langs, g, false) }
    }

    /** downscale ≤1600 px + JPEG q90 → base64 (binder-safe chunking comes later). */
    private fun encodeJpeg(bmp: Bitmap): Enc? {
        return encodeScaled(bmp, 1600, 90)
    }

    /**
     * v1.4.12 — THE PAYLOAD DIET. v1.3.75 raised the AI path to 2200 px q90
     * ("more pixels" for manga strokes), but a modern phone screenshot at
     * 2200 px q90 base64s to 2–4 MB — mobile networks time out and strict
     * gateways answer 400/413 before the request ever lands. 1280 px long
     * edge at q75 base64s to ~200 KB: still comfortably legible for a vision
     * model's text extraction (the pixels of a 1080p screenshot mostly
     * survive 1:1), and it uploads near-instantly even on a weak cell link.
     * (The v1.3.75 "gibberish" report was the OCR FALLBACK being inaccurate,
     * not the AI resolution — the diet does not reopen it.)
     */
    private fun encodeAi(bmp: Bitmap): Enc? {
        return encodeScaled(bmp, 1280, 75)
    }

    /** the shared downscale+JPEG encoder behind [encodeJpeg]/[encodeAi]. */
    private fun encodeScaled(bmp: Bitmap, maxEdge: Int, q: Int): Enc? {
        return try {
            val sc = minOf(1f, maxEdge.toFloat() / maxOf(bmp.width, bmp.height))
            val w = maxOf(1, (bmp.width * sc).toInt())
            val h = maxOf(1, (bmp.height * sc).toInt())
            val src = if (sc < 1f) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
            val bo = ByteArrayOutputStream()
            src.compress(Bitmap.CompressFormat.JPEG, q, bo)
            if (src !== bmp) try { src.recycle() } catch (_: Throwable) {}
            Enc(Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP), w, h)
        } catch (t: Throwable) { null }
    }

    /** v1.3.78: ONE OCR encoder — the proven JPEG path (the manga PNG
     *  preprocess left with the manga scripts). */
    private fun encodeFor(bmp: Bitmap, script: String): Enc? = encodeJpeg(bmp)
    private fun modelFile(a: Context, lang: String): File =
        File(File(a.cacheDir, "ocrfast"), "$lang.traineddata")

    /**
     * make sure every needed model is on disk, downloading the missing
     * ones (with a byte-accurate progress dialog, and a confirmation
     * first when the connection is metered). BLOCKING — POOL only.
     */
    private fun ensureModels(a: Activity, bmp: Bitmap, g: Int, langs: Array<String>): Boolean {
        val todo = langs.distinct().filter {
            val f = modelFile(a, it); !f.exists() || f.length() <= MIN_MODEL
        }
        if (todo.isEmpty()) return true
        val bytes = todo.sumOf { MODEL_BYTES[it] ?: 2_500_000L }
        val metered = try {
            (a.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.isActiveNetworkMetered ?: false
        } catch (_: Throwable) { false }
        if (metered && !confirmDialog(a,
                "Download OCR data?",
                "Translate screen needs to download " +
                        String.format("%.1f", bytes / 1048576.0) +
                        " MB of language data (one time).")) {
            return false
        }
        val pr = onMainGet { ScrProgress.show(a, "Translate screen") { cancelOcr(a, bmp, g) } }
            ?: return false
        prog = pr
        try {
            for ((i, lang) in todo.withIndex()) {
                if (cancelled || gen.get() != g) return false
                if (!downloadModel(a, lang, pr, i, todo.size)) return false
            }
        } finally {
            main.post { pr.close() }
            if (prog === pr) prog = null
        }
        return true
    }

    /** pinned https GET → cacheDir/ocrfast (v1.3.79: re-downloadable data
     *  IS cache — the close-out sweep frees it with everything else), with
     *  the second host as fail-over.
     *  v1.3.78: one fast repo pair — the best-grade alias left with its script. */
    private fun downloadModel(a: Activity, lang: String, pr: ScrProgress, idx: Int, total: Int): Boolean {
        if (lang !in LANG_OK) return false
        val dir = File(a.cacheDir, "ocrfast")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "$lang.traineddata")
        val tmp = File(dir, "$lang.traineddata.tmp")
        val remote = lang
        var fb = -1
        for ((hi, host) in DL_HOSTS.withIndex()) {
            try {
                val c = URL(host + remote + ".traineddata").openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 30000
                c.instanceFollowRedirects = false
                try {
                    if (c.responseCode != 200) continue
                    val len = c.contentLengthLong
                    c.inputStream.use { ins ->
                        FileOutputStream(tmp).use { fos ->
                            val buf = ByteArray(16384)
                            var n: Int
                            var got = 0L
                            while (ins.read(buf).also { n = it } >= 0) {
                                if (cancelled) {
                                    try { tmp.delete() } catch (_: Throwable) {}
                                    return false
                                }
                                fos.write(buf, 0, n)
                                got += n
                                if (len > 0) {
                                    val pct = ((got * 100) / len).toInt()
                                    pr.set("OCR data ${idx + 1}/$total: $lang $pct%", pct)
                                } else pr.set("OCR data ${idx + 1}/$total: $lang\u2026", -1)
                            }
                        }
                    }
                } finally {
                    try { c.disconnect() } catch (_: Throwable) {}
                }
                if (tmp.length() > MIN_MODEL) {
                    if (out.exists()) out.delete()
                    if (tmp.renameTo(out)) {
                        fb = hi
                        try { Diag.log(a, "scr ocr dl $lang ok=" + out.length() + " fb=$fb") }
                        catch (_: Throwable) {}
                        return true
                    }
                }
            } catch (_: Throwable) { /* fail over to the next pinned host */ }
        }
        try { tmp.delete() } catch (_: Throwable) {}
        try { Diag.log(a, "scr ocr dlfail $lang") } catch (_: Throwable) {}
        return false
    }

    /** runs on MAIN: spin the OCR host (lazily) and fire the recognize job.
     *  v1.4.22 — [hybrid] marks the AI path's read: the progress title
     *  says "— AI (reading)" and ocrDone hands the lines to the hybrid
     *  translator instead of the per-line gtx road (the SAME tesseract
     *  pass either way — the hybrid's accuracy gain costs no second
     *  read). */
    private fun runOcrStage(a: Activity, bmp: Bitmap, enc: Enc, langs: Array<String>, g: Int,
                            hybrid: Boolean) {
        if (gen.get() != g) return
        if (prog == null) {
            prog = ScrProgress.show(a,
                if (hybrid) "Translate screen — AI (reading)" else "Translate screen") { cancelOcr(a, bmp, g) }
        }
        val h = try {
            host ?: OcrHost(a).also { host = it }
        } catch (t: Throwable) {
            failOcr(a, bmp, g, "host", "Text recognition unavailable")
            return
        }
        h.onProgress = { s, p -> prog?.set(mapStatus(s, p), p) }
        val t0 = System.currentTimeMillis()
        h.run(langs, enc.b64,
            { json -> POOL.execute { ocrDone(a, bmp, enc, json, g, t0, hybrid) } },
            { msg -> failOcr(a, bmp, g, msg, null) })
    }

    private fun mapStatus(s: String, pct: Int): String = when {
        s.contains("core") -> "Loading OCR engine\u2026 (first time ~4 MB)"
        s.contains("initializ") -> "Starting OCR\u2026"
        s.contains("recogniz") -> "Reading text\u2026 $pct%"
        s.contains("language") -> "Loading OCR data\u2026"
        else -> "Reading text\u2026"
    }

    /** OCR result → SBlocks (bitmap coords, luminance-sampled) → overlay.
     *  v1.4.22 — [hybrid] (the AI path's read, " (hybrid read)" on the
     *  diag line): an EMPTY read means the screen has no machine-readable
     *  text — the vision fallback reads it (the manga case tesseract
     *  cannot crack, 'no OCR text'); otherwise the blocks go to
     *  [runHybridStage] — ONE text request for all lines — never the
     *  per-line gtx road. */
    private fun ocrDone(a: Activity, bmp: Bitmap, enc: Enc, json: String, g: Int, t0: Long,
                        hybrid: Boolean) {
        if (gen.get() != g) return
        try {
            val arr = JSONArray(json)
            val sx = bmp.width.toFloat() / enc.w
            val sy = bmp.height.toFloat() / enc.h
            val blocks = ArrayList<SBlock>()
            var chars = 0
            for (i in 0 until arr.length()) {
                val e = arr.optJSONArray(i) ?: continue
                if (e.length() < 5) continue
                val txt = cjkNormalize(e.optString(4).trim())
                if (txt.length < 2) continue
                val l = (e.optInt(0) * sx).toInt().coerceIn(0, bmp.width - 2)
                val t = (e.optInt(1) * sy).toInt().coerceIn(0, bmp.height - 2)
                val r = (e.optInt(2) * sx).toInt().coerceIn(l + 2, bmp.width)
                val b = (e.optInt(3) * sy).toInt().coerceIn(t + 2, bmp.height)
                val rect = Rect(l, t, r, b)
                if (rect.width() < 6 || rect.height() < 6) continue
                chars += txt.count { !it.isWhitespace() }
                // v1.3.77 — EVERY mode gets the matching kit: the ink
                // analysis tightens tesseract's boxes to the glyphs, paints
                // the card in the LOCAL background, and reads the original's
                // own line height for the font ("all the modes are not
                // accurate enough" — the v1.3.76 kit was AI-only)
                val an = analyze(bmp, rect)
                val sb = SBlock(an.rect, txt, regionLight(bmp, an.rect))
                sb.bg = an.bg
                sb.thPx = an.linePx
                blocks.add(sb)
                if (blocks.size >= MAXBLK) break
            }
            try {
                Diag.log(a, "scr ocr langs=" + langsOf(lastScript).joinToString("+") +
                        " chars=" + chars + " lines=" + blocks.size +
                        " ms=" + (System.currentTimeMillis() - t0) +
                        (if (hybrid) " (hybrid read)" else ""))
            } catch (_: Throwable) {}
            if (blocks.isEmpty()) {
                // v1.4.22 — the hybrid's one honest door to the vision
                // road: OCR read NOTHING (AiBridge's 'no OCR text' guard is
                // the same verdict from the other side) — a vision model
                // reads manga/handwriting tesseract cannot
                if (hybrid) runAiStage(a, bmp, g)
                else failOcr(a, bmp, g, "empty", "No readable text on this screen")
                return
            }
            if (hybrid) runHybridStage(a, bmp, blocks, g)
            else main.post { deliverBlocks(a, bmp, blocks, g) }
        } catch (t: Throwable) {
            failOcr(a, bmp, g, "parse", null)
        }
    }

    /** blocks ready → new overlay, or swap into the open one (script re-run). */
    private fun deliverBlocks(a: Activity, bmp: Bitmap, blocks: ArrayList<SBlock>, g: Int) {
        if (gen.get() != g) return
        prog?.close()
        prog = null
        schip?.text = "Mode: " + scriptLabel(lastScript)
        val v = viewRef
        if (v != null && v.bmpRef() === bmp) {
            v.blocks.clear()
            v.blocks.addAll(blocks)
            translateAll(a, v, lastTl ?: PageTranslate.deviceTl())
        } else {
            openOverlay(a, bmp, blocks, false)
        }
    }

    /** tesseract pads CJK output with spaces — strip them between CJK chars. */
    private fun cjkNormalize(s: String): String {
        if (s.none { it.code >= 0x2E80 }) return s
        return try {
            s.replace(Regex("(?<=[\\u2E80-\\u9FFF\\u3040-\\u30FF\\uFF66-\\uFF9D])\\s+(?=[\\u2E80-\\u9FFF\\u3040-\\u30FF\\uFF66-\\uFF9D])"), "")
        } catch (t: Throwable) { s }
    }

    /** failure exit — recycles the bitmap only while the overlay does NOT own it. */
    private fun failOcr(a: Activity, bmp: Bitmap?, g: Int, detail: String, userMsg: String?) {
        main.post {
            try {
                if (gen.get() != g) return@post
                prog?.close(); prog = null
                teardownHost()
                val v = viewRef
                if (bmp != null && (v == null || v.bmpRef() !== bmp)) {
                    try { bmp.recycle() } catch (_: Throwable) {}
                }
                try { Diag.log(a, "scr ocr fail $detail") } catch (_: Throwable) {}
                if (!cancelled && userMsg != null) Fx.toast(a, userMsg)
                else if (!cancelled) Fx.toast(a, "Text recognition failed — try again")
            } catch (_: Throwable) {}
        }
    }

    // ---------------- v1.3.74: the AI stage — v1.4.22: the hybrid + the vision fallback ----------------

    /** v1.4.20 — PURE (probe-pinned): re-place a box that arrived as 0-1
     *  fractions of ONE TILE onto the full image (the tile's pixel rect
     *  within it — the right/bottom tiles eat the rounding remainder, so
     *  the mapping is exact per tile, not a uniform 1/cols guess). */
    internal fun tilePlace(bx: Float, by: Float, bw: Float, bh: Float,
                           tx: Int, ty: Int, tw: Int, th: Int,
                           iw: Int, ih: Int): FloatArray {
        return floatArrayOf((tx + bx * tw) / iw, (ty + by * th) / ih,
                (bw * tw) / iw, (bh * th) / ih)
    }

    /** v1.4.20 — PURE (probe-pinned): intersection-over-union of two 0-1
     *  fraction boxes — the seam-dedup verdict (a box straddling a tile
     *  border can come back from both sides; > 0.6 = the same text). */
    internal fun iou(ax: Float, ay: Float, aw: Float, ah: Float,
                     bx: Float, by: Float, bw: Float, bh: Float): Float {
        val x0 = maxOf(ax, bx); val y0 = maxOf(ay, by)
        val x1 = minOf(ax + aw, bx + bw); val y1 = minOf(ay + ah, by + bh)
        if (x1 <= x0 || y1 <= y0) return 0f
        val inter = (x1 - x0) * (y1 - y0)
        val uni = aw * ah + bw * bh - inter
        return if (uni <= 0f) 0f else inter / uni
    }

    /** v1.4.20 — THE TILE ROAD: one 2x2 pass over the capture. Each tile
     *  reuses the SAME encoder + visionTranslate (its own cap ladder); a
     *  tile that fails costs its region (logged), the boxes that return
     *  are re-placed (tilePlace) and seam-deduped (iou > 0.6). Returns
     *  null only when every region failed.
     *  v1.4.21 — PROACTIVE (the rung moved to the FRONT): the tile pass is
     *  now the DEFAULT for every AI screen translate — the dense screen on
     *  a low-ceiling lite model is the real cut-off case, and waiting for
     *  a 65536 MAX_TOKENS exhaust to find out costs the user two doomed
     *  full-screen requests first. Plus ONE recursion rung: a tile that
     *  STILL exhausts the cap (shouldTile) is split 2x2 again — a dense
     *  quadrant gets four half-density sub-requests instead of a lost
     *  region.
     *  v1.4.22 — THE CUT ROAD, CONTEXTED (supersedes the proactive rung —
     *  the hybrid already read the screen, so the tile pass fires only
     *  when the vision FALLBACK's own full-screen ask was genuinely cut,
     *  and every tile rides [ctx] — the full-screen transcript
     *  (AiBridge.lastTranscript) — as prompt context: seams keep the
     *  whole-screen terminology, and the model is told in so many words
     *  to never re-emit the context as new boxes). The merged-boxes
     *  evidence line names the count and the head — the first three
     *  boxes' fractions, the tile math's report card. */
    private fun translateTiled(a: Activity, bmp: Bitmap, tl: String,
                               g: Int, ctx: String): ArrayList<AiBridge.AiBox>? {
        val merged = translateRegion(a, bmp, tl, g, 0, 0, bmp.width, bmp.height, 0, ctx)
        if (merged != null) {
            // v1.4.22 — diagnostics: how many boxes survived the seam
            // dedup, and where the first ones landed (full-image fractions)
            try {
                val sb = StringBuilder()
                for (i in 0 until minOf(3, merged.size)) {
                    val b = merged[i]
                    sb.append(String.format("(%.3f,%.3f,%.3f,%.3f)", b.x, b.y, b.w, b.h))
                }
                Diag.log(a, "scr ai merged boxes=" + merged.size + " head=" + sb)
            } catch (_: Throwable) {}
        }
        return merged
    }

    /** one (sub)region through the AI; depth caps the recursion at one
     *  extra level (a 2x2 of 2x2 = 16 sub-requests worst case, bounded).
     *  v1.4.22: [ctx] — the full-screen transcript — rides every tile's
     *  prompt (and every split's) unchanged. */
    private fun translateRegion(a: Activity, bmp: Bitmap, tl: String, g: Int,
                                rx: Int, ry: Int, rw: Int, rh: Int,
                                depth: Int, ctx: String): ArrayList<AiBridge.AiBox>? {
        val merged = ArrayList<AiBridge.AiBox>()
        val cols = 2
        val rows = 2
        val tw = rw / cols
        val th = rh / rows
        if (tw < 32 || th < 32) return null   // too small to halve further
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (gen.get() != g || cancelled) return null
                val x = rx + col * tw
                val y = ry + row * th
                val w = if (col == cols - 1) rx + rw - x else tw
                val h = if (row == rows - 1) ry + rh - y else th
                val tile = try {
                    Bitmap.createBitmap(bmp, x, y, w, h)
                } catch (_: Throwable) { null } ?: continue
                val enc = encodeAi(tile)
                try { tile.recycle() } catch (_: Throwable) {}
                if (enc == null) continue
                val part = AiBridge.visionTranslate(a, enc.b64, tl, enc.w, enc.h, ctx)
                if (part == null) {
                    // v1.4.21 — the per-tile rung: a region that exhausted
                    // the cap ladder (shouldTile) splits once more instead
                    // of losing its text; any other failure costs the region
                    if (depth < 1 && AiBridge.shouldTile(AiBridge.lastError)) {
                        try { Diag.log(a, "scr ai tile split r$row c$col depth=$depth") } catch (_: Throwable) {}
                        val sub = translateRegion(a, bmp, tl, g, x, y, w, h, depth + 1, ctx)
                        if (sub != null) {
                            for (b in sub) {
                                var dup = false
                                for (m in merged) {
                                    if (iou(m.x, m.y, m.w, m.h,
                                            b.x, b.y, b.w, b.h) > 0.6f) { dup = true; break }
                                }
                                if (!dup) merged.add(b)
                            }
                        }
                        continue
                    }
                    try { Diag.log(a, "scr ai tile r$row c$col fail " +
                            AiBridge.lastError.take(40)) } catch (_: Throwable) {}
                    continue
                }
                for (b in part) {
                    val f = tilePlace(b.x, b.y, b.w, b.h, x, y, w, h,
                            bmp.width, bmp.height)
                    var dup = false
                    for (m in merged) {
                        if (iou(m.x, m.y, m.w, m.h, f[0], f[1], f[2], f[3]) > 0.6f) {
                            dup = true; break
                        }
                    }
                    if (!dup) merged.add(AiBridge.AiBox(f[0], f[1], f[2], f[3],
                            b.orig, b.trans, b.th, b.vert))
                }
            }
        }
        return if (merged.isEmpty()) null else merged
    }

    /**
     * v1.4.22 — THE HYBRID STAGE (the release note's "ONE AI request").
     * POOL thread, straight off ocrDone's blocks. Sends the EXTRACTED
     * TEXT — never the screenshot — to AiBridge.textTranslate in ONE
     * numbered-lines request with the whole screen as context, then
     * writes the translations back onto the OCR's OWN boxes: the model
     * never sees a coordinate, so it cannot invent one, and one reply
     * keeps terminology consistent across the entire screen. Coverage
     * (how many numbered lines came back) rides the diag line — a model
     * that skipped lines leaves those boxes untranslated, never guessed.
     * Every failure hands the screen to the vision fallback
     * ([runAiStage]) EXCEPT the honest cut-off (too many lines for one
     * reply even at 65536) — that one fails outright: sending a
     * screenshot of text we already hold, to be read again, is the one
     * trade the hybrid exists to eliminate.
     */
    private fun runHybridStage(a: Activity, bmp: Bitmap, blocks: ArrayList<SBlock>, g: Int) {
        if (gen.get() != g) return
        if (!AiBridge.configured(a)) {
            main.post {
                if (gen.get() != g) return@post
                try { Diag.log(a, "scr ai cfg") } catch (_: Throwable) {}
                // v1.3.75: saving a key CONTINUES the run on the blocks
                // still in hand — the v1.3.74 flow dead-ended here
                AiBridge.configDialog(a, { url -> openAiUrl(a, url) }) {
                    if (gen.get() == g) {
                        try { Diag.log(a, "scr ai cfg saved") } catch (_: Throwable) {}
                        POOL.execute {
                            try { runHybridStage(a, bmp, blocks, g) } catch (_: Throwable) {}
                        }
                    }
                }
            }
            return
        }
        if (!aiConfirmed) {
            // confirmDialog is POOL-safe (it posts the dialog to main itself
            // and latches HERE — wrapping it in onMainGet would deadlock main)
            // v1.4.22 — the honest new wording: TEXT leaves, not the screen;
            // the screenshot is named as the exception, not the rule
            val ok = confirmDialog(a, "Send the screen's text for AI translation?",
                "AI translate reads this screen ON-DEVICE with OCR, then sends the " +
                        "EXTRACTED TEXT to " + AiBridge.providerName(a) +
                        " in one request. The screenshot itself is only ever sent when " +
                        "OCR cannot read the screen at all (the vision fallback). " +
                        "Everything else in Bolt stays on-device.")
            if (!ok) { cancelOcr(a, bmp, g); return }
            aiConfirmed = true
        }
        if (gen.get() != g) return
        // v1.4.22: the OCR stage's "— AI (reading)" progress rides ON — the
        // confirm latch and the text ask happen under the same dialog; only
        // a cold entry with none open shows a fresh one
        val pr = prog ?: onMainGet { ScrProgress.show(a, "Translate screen — AI") { cancelOcr(a, bmp, g) } }
                ?: return
        prog = pr
        try {
            val tl = lastTl ?: PageTranslate.deviceTl()
            try {
                Diag.log(a, "scr hybrid ask prov=" + AiBridge.providerName(a) +
                        " model=" + aiModelForDiag(a) + " lines=" + blocks.size)
            } catch (_: Throwable) {}
            val t0 = System.currentTimeMillis()
            pr.set("Translating\u2026", -1)
            val texts = ArrayList<String>(blocks.size)
            for (b in blocks) texts.add(b.orig)
            val map = AiBridge.textTranslate(a, texts, tl)
            if (gen.get() != g) return
            if (map == null) {
                if (AiBridge.lastError != AiBridge.CUT_OFF_ERR) {
                    // v1.4.22 — any text failure that is NOT the honest
                    // cut-off falls back to the vision road: the key may be
                    // gone, the provider may 5xx, the OpenAI-compatible
                    // endpoint has no text road at all — the screen is
                    // still translatable the old way
                    try {
                        Diag.log(a, "scr hybrid text-fail " + AiBridge.lastError.take(60) +
                                " -> vision")
                    } catch (_: Throwable) {}
                    runAiStage(a, bmp, g)
                } else {
                    // the ONE failure that does not: too many lines even at
                    // 65536 — re-sending pixels to re-read text we hold
                    // would be a lie about what the hybrid is for
                    failOcr(a, bmp, g, "ai " + AiBridge.lastError.take(60),
                        "AI translation " + AiBridge.lastError)
                }
                return
            }
            // write the translations back onto the OCR's OWN boxes — 1-based
            // numbers to match the numbered-lines protocol; coverage counts
            // the lines the model actually answered
            var covered = 0
            var idx = 0
            for (b in blocks) {
                idx++
                val t = map[idx]
                if (t != null) {
                    b.trans = t
                    b.rev = 1
                    covered++
                }
            }
            try {
                Diag.log(a, "scr hybrid ok lines=" + blocks.size + " covered=" + covered +
                        " ms=" + (System.currentTimeMillis() - t0))
            } catch (_: Throwable) {}
            if (covered == 0) {
                failOcr(a, bmp, g, "ai empty", "No readable text on this screen")
                return
            }
            main.post { deliverAiBlocks(a, bmp, blocks, g) }
        } finally {
            main.post { pr.close() }
            if (prog === pr) prog = null
        }
    }

    /**
     * POOL thread. v1.4.22 — THE VISION FALLBACK (demoted from the primary
     * by the hybrid turn): the old whole-screenshot road, now reached
     * only when OCR read nothing (ocrDone's empty branch), the OCR models
     * failed to download (pipeline), or the text request itself failed
     * (runHybridStage). The FULL screen is asked in ONE shot — boxes arrive
     * on a 1000×1000 grid — and tiling fires ONLY on a genuine MAX_TOKENS
     * cut of that ask (the reply was cut after the whole cap ladder), the
     * tiles then riding the full-screen transcript as context so seams
     * keep the whole-screen terminology and never re-box already-read
     * text (see translateTiled / AiBridge.HYBRID_CTX).
     */
    private fun runAiStage(a: Activity, bmp: Bitmap, g: Int) {
        if (gen.get() != g) return
        if (!AiBridge.configured(a)) {
            main.post {
                if (gen.get() != g) return@post
                try { Diag.log(a, "scr ai cfg") } catch (_: Throwable) {}
                // v1.3.75: saving a key CONTINUES the run on the capture
                // that is still in hand — the v1.3.74 flow dead-ended here
                // (the user had to re-pick AI and re-capture the screen)
                AiBridge.configDialog(a, { url -> openAiUrl(a, url) }) {
                    if (gen.get() == g) {
                        try { Diag.log(a, "scr ai cfg saved") } catch (_: Throwable) {}
                        POOL.execute {
                            try { runAiStage(a, bmp, g) } catch (_: Throwable) {}
                        }
                    }
                }
            }
            return
        }
        if (!aiConfirmed) {
            // confirmDialog is POOL-safe (it posts the dialog to main itself
            // and latches HERE — wrapping it in onMainGet would deadlock main)
            val ok = confirmDialog(a, "Send the screenshot for AI translation?",
                "AI translate sends this screenshot to " + AiBridge.providerName(a) +
                        ". Everything else in Bolt stays on-device. The AI reads and " +
                        "translates it in one pass.")
            if (!ok) { cancelOcr(a, bmp, g); return }
            aiConfirmed = true
        }
        if (gen.get() != g) return
        // v1.4.22: same reuse rule as the hybrid stage — the fallback rides
        // the "(reading)" dialog that is already open instead of flashing a
        // second one at the user
        val pr = prog ?: onMainGet { ScrProgress.show(a, "Translate screen — AI") { cancelOcr(a, bmp, g) } }
                ?: return
        prog = pr
        try {
            val tl = lastTl ?: PageTranslate.deviceTl()
            try {
                Diag.log(a, "scr ai vision ask prov=" + AiBridge.providerName(a) +
                        " model=" + aiModelForDiag(a))
            } catch (_: Throwable) {}
            val t0 = System.currentTimeMillis()
            // v1.4.22 — FULL SCREEN FIRST (supersedes the v1.4.21 proactive
            // tiles-first rung): the hybrid already proved the screen is
            // UNREADABLE on-device, so the fallback buys the one-shot
            // whole-screen read — four quadrant asks are the remedy for a
            // genuinely cut REPLY, not a default. The tiles wait for exactly
            // that verdict below.
            pr.set("Translating\u2026", -1)
            val enc = encodeAi(bmp)
            var boxes: ArrayList<AiBridge.AiBox>? = null
            if (enc != null) {
                boxes = AiBridge.visionTranslate(a, enc.b64, tl, enc.w, enc.h, "")
            }
            if (gen.get() != g) return
            if (boxes == null && enc != null && AiBridge.shouldTile(AiBridge.lastError)) {
                // v1.4.22 — THE ONE HONEST CUT: the full-screen reply hit
                // MAX_TOKENS through the whole ladder, and its partial text
                // (AiBridge.lastTranscript) is exactly the context the tiles
                // need — smaller regions, SAME terminology, and the context
                // preface tells the model to never re-emit it as new boxes
                pr.set("Translating in regions\u2026", -1)
                try {
                    Diag.log(a, "scr ai full cut -> tiles ctxlen=" +
                            AiBridge.lastTranscript.length)
                } catch (_: Throwable) {}
                boxes = translateTiled(a, bmp, tl, g, AiBridge.lastTranscript)
            }
            if (gen.get() != g) return
            try { Diag.log(a, "scr ai vision done ms=" +
                    (System.currentTimeMillis() - t0)) } catch (_: Throwable) {}
            if (boxes == null) {
                failOcr(a, bmp, g, "ai " + AiBridge.lastError.take(60),
                    "AI translation failed — " + AiBridge.lastError.take(60))
                return
            }
            val blocks = ArrayList<SBlock>()
            for (b in boxes) {
                if (blocks.size >= MAXBLK) break
                // v1.3.77: AiBox arrives in 0-1 fractions of the SENT image —
                // map straight into bitmap pixels (no 1000-grid detour)
                val l = (b.x * bmp.width).toInt().coerceIn(0, bmp.width - 2)
                val t = (b.y * bmp.height).toInt().coerceIn(0, bmp.height - 2)
                val r = ((b.x + b.w) * bmp.width).toInt().coerceIn(l + 2, bmp.width)
                val bo = ((b.y + b.h) * bmp.height).toInt().coerceIn(t + 2, bmp.height)
                val rect = Rect(l, t, r, bo)
                if (rect.width() < 6 || rect.height() < 6) continue
                // v1.3.77 — the matching kit's second act: the ink analysis
                // shrinks the model's box to the TEXT ITSELF ("overlaying
                // covering only the old text portion"), the ring gives the
                // local background, the median ink band gives the original's
                // own line height (the model's th is only the fallback now).
                // v1.3.78 — NO ROTATION, EVER: the field said the translated
                // text is "90 degree rotated on the screen in the correct
                // place and size" — a vertical original gets HORIZONTAL
                // multi-line text riding the column, never sideways glyphs.
                val an = analyze(bmp, rect)
                val sb = SBlock(an.rect, b.orig, regionLight(bmp, an.rect))
                sb.trans = b.trans
                sb.rev = 1
                sb.bg = an.bg
                sb.thPx = if (an.linePx > 0f) an.linePx else b.th * bmp.height
                blocks.add(sb)
            }
            try {
                Diag.log(a, "scr ai ok lines=" + blocks.size +
                        " ms=" + (System.currentTimeMillis() - t0))
            } catch (_: Throwable) {}
            if (blocks.isEmpty()) {
                failOcr(a, bmp, g, "ai empty", "No readable text on this screen")
                return
            }
            main.post { deliverAiBlocks(a, bmp, blocks, g) }
        } finally {
            main.post { pr.close() }
            if (prog === pr) prog = null
        }
    }

    /** the model name WITHOUT the key ever leaving the diag channel.
     *  v1.4.21: names the EFFECTIVE translate model (the dedicated
     *  translate-model picker when set, the shared chat model otherwise). */
    private fun aiModelForDiag(a: Activity): String {
        return try {
            val sp = a.getSharedPreferences("bolt_ai", Context.MODE_PRIVATE)
            val m = sp.getString("translateModel", "")!!.ifBlank {
                sp.getString("model", "")!!
            }
            m.take(40).ifBlank { "default" }
        } catch (_: Throwable) { "default" }
    }

    /** AI blocks ready — translations are ALREADY in them (no gtx pass). */
    private fun deliverAiBlocks(a: Activity, bmp: Bitmap, blocks: ArrayList<SBlock>, g: Int) {
        if (gen.get() != g) return
        prog?.close()
        prog = null
        schip?.text = "Mode: AI"
        val v = viewRef
        if (v != null && v.bmpRef() === bmp) {
            v.blocks.clear()
            v.blocks.addAll(blocks)
            v.invalidate()
        } else {
            openOverlay(a, bmp, blocks, true)
        }
    }

    /** user-cancel exit (progress dialog Cancel / metered decline mid-download). */
    private fun cancelOcr(a: Activity, bmp: Bitmap?, g: Int) {
        cancelled = true
        main.post {
            try {
                prog?.close(); prog = null
                teardownHost()
                val v = viewRef
                if (bmp != null && (v == null || v.bmpRef() !== bmp)) {
                    try { bmp.recycle() } catch (_: Throwable) {}
                }
                try { Diag.log(a, "scr ocr cancel") } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    /** run [fn] on the main thread and wait briefly for its value (POOL only). */
    private fun <T> onMainGet(fn: () -> T): T? {
        var out: T? = null
        val lat = CountDownLatch(1)
        main.post {
            try { out = fn() } catch (_: Throwable) {} finally { lat.countDown() }
        }
        try { lat.await(30, TimeUnit.SECONDS) } catch (_: Throwable) {}
        return out
    }

    /** blocking yes/no dialog (POOL only — the latch waits for the user). */
    private fun confirmDialog(a: Activity, title: String, msg: String): Boolean {
        val lat = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        main.post {
            try {
                AlertDialog.Builder(a)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton("Download") { d, _ ->
                        ok.set(true)
                        try { d.dismiss() } catch (_: Throwable) {}
                    }
                    .setNegativeButton("Cancel") { d, _ -> }
                    .setOnDismissListener { lat.countDown() }
                    .show()
            } catch (t: Throwable) { lat.countDown() }
        }
        try { lat.await(180, TimeUnit.SECONDS) } catch (_: Throwable) {}
        return ok.get()
    }

    /**
     * v1.3.77 — THE INK ANALYSIS (the matching kit's second act). The
     * v1.3.76 field report: "the manga text place detection and overlaying
     * covering only the old text portion got worse" — a card can only
     * cover the old text if its rect IS the text's extent, and neither
     * the model nor tesseract returns glyph-tight boxes on stylised art.
     * This measures the INK directly: sample the local background ring
     * (bubble paper / page tone / panel dark — [sampleRing]), profile the
     * pixels inside the box that differ from that background by
     * luminance, shrink the rect to the actual ink extents (guarded: a
     * side may never remove more than half the span — a bad ring guess
     * must not gut a good box), and read the dominant ink-band height
     * along the rows — the ORIGINAL's own line height, which is what the
     * overlay's font size starts from (v1.3.78: one axis for every text;
     * vertical originals ride the same row bands). POOL thread only (it walks
     * the box pixels, sampled ~64×64 max).
     */
    private class InkInfo(val rect: Rect, val bg: Int, val linePx: Float)

    /** ink = luminance ≥ this far from the sampled background */
    private const val INK_DELTA = 48

    private fun analyze(bmp: Bitmap, box: Rect): InkInfo {
        return try {
            val bg = sampleRing(bmp, box)
            val L = box.left.coerceAtLeast(0)
            val T = box.top.coerceAtLeast(0)
            val R = box.right.coerceAtMost(bmp.width)
            val B = box.bottom.coerceAtMost(bmp.height)
            if (R - L < 10 || B - T < 10) return InkInfo(box, bg, 0f)
            val lumBg = lumOf(bg)
            val step = maxOf(1, maxOf(R - L, B - T) / 64)
            val nRows = (B - T + step - 1) / step
            val nCols = (R - L + step - 1) / step
            val rowInk = IntArray(nRows)
            val colInk = IntArray(nCols)
            for (iy in 0 until nRows) {
                val y = minOf(B - 1, T + iy * step)
                for (ix in 0 until nCols) {
                    val x = minOf(R - 1, L + ix * step)
                    if (kotlin.math.abs(lumOf(bmp.getPixel(x, y)) - lumBg) >= INK_DELTA) {
                        rowInk[iy]++; colInk[ix]++
                    }
                }
            }
            // a row/column counts as inky at ~8% ink coverage — strokes
            // cover 30-60% of a text line's width, so 8% keeps faint
            // stylised glyphs and drops bleed-through noise
            val rowTh = maxOf(1, nCols / 12)
            val colTh = maxOf(1, nRows / 12)
            val r0 = firstInky(rowInk, rowTh)
            val r1 = if (r0 < 0) -1 else lastInky(rowInk, rowTh)
            val c0 = firstInky(colInk, colTh)
            val c1 = if (c0 < 0) -1 else lastInky(colInk, colTh)
            var nl = L; var nt = T; var nr = R; var nb = B
            if (r0 >= 0 && r1 >= r0) {
                val candTop = T + r0 * step - step
                val candBot = T + (r1 + 1) * step
                // a side may never remove more than half the span — a bad
                // ring guess must not gut a good box, but a bubble-margin
                // box (30-40% padding per side) MUST tighten
                if (candTop > T + (B - T) / 2) nt = candTop.coerceAtLeast(T)
                if (candBot < B - (B - T) / 2) nb = candBot.coerceAtMost(B)
            }
            if (c0 >= 0 && c1 >= c0) {
                val candLeft = L + c0 * step - step
                val candRight = L + (c1 + 1) * step
                if (candLeft > L + (R - L) / 2) nl = candLeft.coerceAtLeast(L)
                if (candRight < R - (R - L) / 2) nr = candRight.coerceAtMost(R)
            }
            var rect = Rect(nl, nt, nr, nb)
            if (rect.width() < 6 || rect.height() < 6) rect = Rect(box)
            // the ORIGINAL's own line height: median ink-band run along
            // the rows — the glyph/line height the font starts from
            // (v1.3.78: vertical originals ride the same row bands — one
            // stacked character per band is still the glyph size, the right
            // starting font for the horizontal text that replaced the
            // rotated-card design the field rejected)
            val linePx = medianBand(rowInk, rowTh, step)
            InkInfo(rect, bg, linePx)
        } catch (t: Throwable) {
            InkInfo(Rect(box), 0, 0f)
        }
    }

    /** first index whose count ≥ th, -1 when none */
    private fun firstInky(a: IntArray, th: Int): Int {
        for (i in a.indices) if (a[i] >= th) return i
        return -1
    }

    /** last index whose count ≥ th, -1 when none */
    private fun lastInky(a: IntArray, th: Int): Int {
        for (i in a.indices.reversed()) if (a[i] >= th) return i
        return -1
    }

    /** median run-length (in px) of the ≥th bands — the glyph/line height */
    private fun medianBand(a: IntArray, th: Int, step: Int): Float {
        val runs = ArrayList<Int>()
        var i = 0
        while (i < a.size) {
            if (a[i] >= th) {
                var j = i
                while (j < a.size && a[j] >= th) j++
                runs.add(j - i)
                i = j
            } else i++
        }
        if (runs.isEmpty()) return 0f
        runs.sort()
        return runs[runs.size / 2] * step.toFloat()
    }

    /** ITU-R BT.601 luminance of a 0xAARRGGBB color */
    private fun lumOf(c: Int): Int =
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** average luminance under the box (>127 = light region). */
    private fun regionLight(bmp: Bitmap, r: Rect): Boolean {
        return try {
            var sum = 0L
            var n = 0
            val sx = maxOf(1, r.width() / 20)
            val sy = maxOf(1, r.height() / 20)
            var y = r.top.coerceAtLeast(0)
            while (y < r.bottom.coerceAtMost(bmp.height)) {
                var x = r.left.coerceAtLeast(0)
                while (x < r.right.coerceAtMost(bmp.width)) {
                    val p = bmp.getPixel(x, y)
                    sum += (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    n++
                    x += sx
                }
                y += sy
            }
            n > 0 && (sum / n) > 127
        } catch (t: Throwable) { true }
    }

    /**
     * v1.3.76 — the BACKGROUND RING. The v1.3.75 card was a fixed
     * light/dark slab, and the field said it "does not match the
     * backgrounds". This samples a thin ring JUST OUTSIDE the AI's tight
     * box (the bubble paper / page tone / panel dark that surrounds the
     * text), trims outliers with a median band, and returns the local
     * color — the card then paints with what is actually behind the text.
     * 0 on any failure (caller falls back to the legacy card).
     */
    private fun sampleRing(bmp: Bitmap, r: Rect): Int {
        return try {
            val m = maxOf(2, minOf(8, minOf(r.width(), r.height()) / 6))
            val pts = ArrayList<Long>(24)
            val l = r.left - m
            val rt = r.right + m
            val tp = r.top - m
            val bt = r.bottom + m
            // 4 samples per side, stepped along the ring
            for (i in 0 until 4) {
                val fx = l + (r.width() + 2 * m) * i / 3
                val fy = tp + (r.height() + 2 * m) * i / 3
                pts.add(point(bmp, fx, tp))
                pts.add(point(bmp, fx, bt))
                pts.add(point(bmp, l, fy))
                pts.add(point(bmp, rt, fy))
            }
            val good = pts.filter { it >= 0 }
            if (good.size < 6) return 0
            // median band: drop the wildest quartiles (art strokes that
            // clipped the ring) and average the middle
            val sorted = good.sorted()
            val lo = sorted.size / 4
            val hi = sorted.size - lo
            var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
            for (i in lo until hi) {
                val p = sorted[i].toInt()
                rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF; n++
            }
            if (n == 0) 0 else Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
        } catch (t: Throwable) { 0 }
    }

    /** one clamped pixel as 0xAARRGGBB, -1 when out of range */
    private fun point(bmp: Bitmap, x: Int, y: Int): Long {
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return -1L
        return bmp.getPixel(x, y).toLong() and 0xFFFFFFFFL
    }

    // ---------------- translation ----------------

    private fun translateAll(a: Activity, view: ScreenView, tl: String) {
        val blocks = view.blocks
        view.chip?.text = "\u2192 $tl \u00b7 \u2026"
        val ok = AtomicInteger(0)
        val done = AtomicInteger(0)
        val total = blocks.size
        for (b in blocks) {
            b.trans = null
            b.rev++
            POOL.execute {
                val r = PageTranslate.gtx(b.orig, tl)
                b.trans = r.first
                if (r.first != null) ok.incrementAndGet()
                val d = done.incrementAndGet()
                main.post {
                    try {
                        if (view.chip != null) {
                            view.chip?.text = if (d < total) "\u2192 $tl \u00b7 $d/$total"
                            else "\u2192 $tl"
                        }
                        view.invalidate()
                        if (d == total) {
                            try { Diag.log(a, "scr tx tl=$tl blocks=$total ok=" +
                                    ok.get() + " fail=" + (total - ok.get())) } catch (_: Throwable) {}
                            if (ok.get() == 0) Fx.toast(a, "Translation failed \u2014 check connection")
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    // ---------------- the overlay ----------------

    private fun openOverlay(a: Activity, bmp: Bitmap, blocks: ArrayList<SBlock>, ai: Boolean) {
        val tl = lastTl ?: PageTranslate.deviceTl()
        val view = ScreenView(a, bmp, blocks)
        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        val pad = Fx.dp(a, 10).toInt()
        bar.setPadding(pad, pad, pad, pad)

        fun chip(label: String, sizeDp: Int, bold: Boolean): TextView {
            val t = TextView(a)
            t.text = label
            t.textSize = sizeDp.toFloat()
            t.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            t.setTextColor(Color.WHITE)
            t.setPadding(Fx.dp(a, 12).toInt(), Fx.dp(a, 7).toInt(), Fx.dp(a, 12).toInt(), Fx.dp(a, 7).toInt())
            try { t.setBackgroundResource(com.bolt.browser.R.drawable.bg_chip) } catch (_: Throwable) {}
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = Fx.dp(a, 8).toInt()
            t.layoutParams = lp
            return t
        }

        val chip = chip(tl.uppercase(), 12, true)
        chip.setOnClickListener {
            try { Diag.log(a, "scr pick") } catch (_: Throwable) {}
            pickLanguage(a, tl) { newTl ->
                lastTl = newTl
                translateAll(a, view, newTl)
            }
        }
        view.chip = chip
        val script = chip("Mode: " + scriptLabel(lastScript), 11, false)
        script.setOnClickListener {
            try { Diag.log(a, "scr scriptpick") } catch (_: Throwable) {}
            pickScript(a) { k ->
                if (k != lastScript) {
                    lastScript = k
                    try { Diag.log(a, "scr script $k") } catch (_: Throwable) {}
                    reRun(a, view, k)
                }
            }
        }
        schip = script
        // v1.3.77 — THE RECAPTURE CHIP (the live-reading loop): the v1.3.76
        // report said screen translate "is still not usable to translate
        // manga panels live" — the friction was the walk back through the
        // menu + picker for every new page. One tap now dismisses the
        // overlay, waits out the dialog animation, re-captures the CURRENT
        // screen and re-runs the SAME script — flip the manga page, tap ↻,
        // read, repeat.
        val redo = chip("\u21bb", 15, false)
        redo.setOnClickListener {
            try { Diag.log(a, "scr redo") } catch (_: Throwable) {}
            val w = aiWeb?.get()
            val op = tabOpener
            val k = lastScript
            dismiss()
            if (w != null && !a.isFinishing) {
                main.postDelayed({
                    try { startWithScript(a, w, k, op) } catch (_: Throwable) {}
                }, 450)
            }
        }
        val save = chip("Save", 12, false)
        save.setOnClickListener { save(a, view) }
        val close = chip("\u2715", 15, false)
        close.setOnClickListener { dismiss() }

        bar.addView(chip)
        bar.addView(script)
        bar.addView(redo)
        bar.addView(save)
        bar.addView(close)

        val root = FrameLayout(a)
        root.setBackgroundColor(Color.BLACK)
        val flp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(view, flp)
        val blp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bar.layoutParams = blp
        root.addView(bar)

        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(root)
        d.setOnDismissListener {
            view.closed = true
            view.chip = null
            if (dialog === d) dialog = null
            viewRef = null
            schip = null
            teardownHost()
        }
        try {
            d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        } catch (_: Throwable) {}
        d.show()
        try {
            d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        } catch (_: Throwable) {}
        dialog = d
        viewRef = view
        // v1.3.74: AI blocks arrive WITH their translations (the model read
        // AND translated in one pass) — the per-line gtx round is for OCR
        if (!ai) translateAll(a, view, tl)
    }

    /** re-run on the SAME capture with a different script (overlay stays). */
    private fun reRun(a: Activity, view: ScreenView, k: String) {
        val bmp = view.bmpRef()
        val g = gen.incrementAndGet()
        cancelled = false
        POOL.execute {
            try {
                pipeline(a, bmp, g)
            } catch (t: Throwable) {
                failOcr(a, null, g, "pipe2", null)
            }
        }
    }

    /** the mode picker (AI / Basic OCR — v1.3.78: the script zoo is gone
     *  at the user's word; the universal Basic pass is the no-AI fallback).
     *  v1.3.75: the neutral "AI settings" button makes the provider / key /
     *  model / base URL EDITABLE any time — the v1.3.74 flow offered no way
     *  back into the config once a key existed ("no option to change inputs
     *  I once make"). Saving a key continues straight into the AI script. */
    private fun pickScript(a: Activity, onPick: (String) -> Unit) {
        try {
            var def = SCRIPT_KEYS.indexOf(lastScript)
            if (def < 0) def = 0
            AlertDialog.Builder(a)
                .setTitle("Translate screen")
                .setSingleChoiceItems(SCRIPT_LABELS, def) { d, w ->
                    try { d.dismiss() } catch (_: Throwable) {}
                    onPick(SCRIPT_KEYS[w])
                }
                .setNeutralButton("AI settings") { d, _ ->
                    try { d.dismiss() } catch (_: Throwable) {}
                    try { Diag.log(a, "scr ai cfg edit") } catch (_: Throwable) {}
                    AiBridge.configDialog(a, { url -> openAiUrl(a, url) }) {
                        // saved a key — run the AI script the user was
                        // clearly setting up (the capture follows normally)
                        onPick("ai")
                    }
                }
                .setNegativeButton(com.bolt.browser.R.string.cancel, null)
                .show()
        } catch (t: Throwable) {
            Fx.toast(a, "Could not open the language picker")
        }
    }

    /** the key-link opener — NEW TAB when BrowserActivity wired one, the
     *  old same-tab load otherwise. */
    private fun openAiUrl(a: Activity, url: String) {
        val opener = tabOpener
        if (opener != null) {
            try { Fx.toast(a, "Opening in a new tab — come back after pasting the key") } catch (_: Throwable) {}
            try { opener.open(url) } catch (_: Throwable) {}
            return
        }
        try {
            val w = aiWeb?.get()
            if (w != null) {
                Fx.toast(a, "Opening AI Studio — create a free key, then come back")
                w.post { try { w.loadUrl(url) } catch (_: Throwable) {} }
            }
        } catch (_: Throwable) {}
    }

    /** the same single-choice language dialog the page translator uses. */
    private fun pickLanguage(a: Activity, cur: String, onPick: (String) -> Unit) {
        try {
            val langs = a.resources.getStringArray(com.bolt.browser.R.array.translate_langs)
            val codes = a.resources.getStringArray(com.bolt.browser.R.array.translate_codes)
            var def = 0
            for (i in codes.indices) if (codes[i] == cur) { def = i; break }
            AlertDialog.Builder(a)
                .setTitle(com.bolt.browser.R.string.translate_to)
                .setSingleChoiceItems(langs, def) { d, w ->
                    try { d.dismiss() } catch (_: Throwable) {}
                    onPick(codes[w])
                }
                .setNegativeButton(com.bolt.browser.R.string.cancel, null)
                .show()
        } catch (t: Throwable) {
            Fx.toast(a, "Could not open the language picker")
        }
    }

    @JvmStatic
    fun dismiss() {
        val d = dialog ?: return
        dialog = null
        viewRef = null
        schip = null
        prog?.close()
        prog = null
        teardownHost()
        try { d.dismiss() } catch (_: Throwable) {}
    }

    private fun teardownHost() {
        val h = host ?: return
        host = null
        h.destroy()
    }

    // ---------------- save (composite render → Pictures) ----------------

    private fun save(a: Activity, view: ScreenView) {
        Thread({
            var uri: android.net.Uri? = null
            try {
                val out = view.composite() ?: throw IllegalStateException("composite failed")
                val name = "Bolt-translate-" + System.currentTimeMillis() + ".png"
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val cv = android.content.ContentValues()
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    uri = a.contentResolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    if (uri == null) throw IllegalStateException("insert failed")
                    a.contentResolver.openOutputStream(uri)?.use { o ->
                        out.compress(Bitmap.CompressFormat.PNG, 100, o)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES)
                    if (dir != null && !dir.exists()) dir.mkdirs()
                    val f = java.io.File(dir, name)
                    java.io.FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    uri = android.net.Uri.fromFile(f)
                }
                try { if (out !== view.bmpRef()) out.recycle() } catch (_: Throwable) {}
            } catch (t: Throwable) {
                uri = null
            }
            val ok = uri != null
            try { Diag.log(a, "scr save ok=" + (if (ok) 1 else 0)) } catch (_: Throwable) {}
            a.runOnUiThread {
                Fx.toast(a, if (ok) "Saved to Pictures" else "Save failed")
            }
        }, "bolt-scrsave").start()
    }

    // ---------------- the progress dialog (downloads / engine / OCR) ----------

    private class ScrProgress private constructor(private val dlg: AlertDialog,
                                                  private val tv: TextView,
                                                  private val bar: ProgressBar) {
        fun set(msg: String, pct: Int) {
            main.post {
                try {
                    tv.text = msg
                    bar.isIndeterminate = pct < 0
                    if (pct >= 0) bar.progress = pct
                } catch (_: Throwable) {}
            }
        }
        fun close() {
            main.post { try { dlg.dismiss() } catch (_: Throwable) {} }
        }

        companion object {
            fun show(a: Activity, title: String, onCancel: () -> Unit): ScrProgress {
                val tv = TextView(a)
                tv.text = "Starting\u2026"
                val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
                bar.max = 100
                bar.isIndeterminate = true
                val pad = Fx.dp(a, 20).toInt()
                val wrap = LinearLayout(a)
                wrap.orientation = LinearLayout.VERTICAL
                wrap.setPadding(pad, pad / 2, pad, 0)
                wrap.addView(tv)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                wrap.addView(bar, lp)
                val dlg = AlertDialog.Builder(a)
                    .setTitle(title)
                    .setView(wrap)
                    .setNegativeButton("Cancel") { d, _ ->
                        try { d.dismiss() } catch (_: Throwable) {}
                        onCancel()
                    }
                    .setCancelable(false)
                    .show()
                return ScrProgress(dlg, tv, bar)
            }
        }
    }

    // ---------------- the headless OCR WebView (tesseract.js host) ------------

    /**
     * A 1×1 INVISIBLE headless WebView. Loads the 4 KB asset page (base URL
     * pinned to cdn.jsdelivr.net so the tesseract.js engine + wasm core are
     * same-origin fetches served by the CDN's immutable cache), and serves
     * language models from filesDir through shouldInterceptRequest at
     * https://bolt.ocr/tess — the host never resolves anywhere; EVERY request
     * to it stays on the device. Results come back through the BoltOcr bridge.
     *
     * v1.4.11 — THE CONTEXT LEAK. The host WebView used to be built with the
     * ACTIVITY (`WebView(a)`) and attached to the activity's decor view — but
     * this object lives in the `ScreenTranslate` singleton, which outlives
     * every activity. Screen rotation destroyed the activity while the
     * singleton kept the WebView (context → the whole activity + its view
     * tree) pinned forever: the classic rotation leak. The WebView is now
     * built with `a.applicationContext` (an app-scoped context that never
     * dies) and is NEVER attached — a headless WebView needs no window to
     * load a page and run JS/wasm; every other context use inside this class
     * (asset read, model files, diag log) rides the same appCtx so no
     * activity reference survives construction.
     */
    private class OcrHost(a: Activity) {
        private val wv: WebView
        private val appCtx: Context = a.applicationContext
        private val mainH = Handler(Looper.getMainLooper())
        @Volatile private var destroyed = false
        @Volatile private var ready = false
        @Volatile private var onDone: ((String) -> Unit)? = null
        @Volatile private var onFail: ((String) -> Unit)? = null
        var onProgress: ((String, Int) -> Unit)? = null
        private var wd: Runnable? = null
        private var diagN = 0

        init {
            // v1.4.11: appCtx, never the activity — see the class header (the
            // rotation leak). The activity parameter is read exactly once
            // here and never stored.
            wv = WebView(appCtx)
            wv.layoutParams = ViewGroup.LayoutParams(1, 1)
            wv.visibility = View.INVISIBLE
            val s = wv.settings
            s.javaScriptEnabled = true
            // no domStorage / no fileAccess / no database — the page owns
            // nothing local; models arrive through the interceptor below
            wv.addJavascriptInterface(Bridge(), "BoltOcr")
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView,
                                                     req: WebResourceRequest): WebResourceResponse? {
                    val u: Uri = req.url
                    if (u.host == "bolt.ocr") {
                        val lang = (u.lastPathSegment ?: "").substringBefore('.')
                        if (lang in LANG_OK) {
                            val f = modelFile(appCtx, lang)
                            if (f.exists() && f.length() > MIN_MODEL) {
                                val r = WebResourceResponse("application/octet-stream", null,
                                    FileInputStream(f))
                                r.responseHeaders = mapOf(
                                    "Access-Control-Allow-Origin" to "*",
                                    "Cache-Control" to "no-store")
                                return r
                            }
                        }
                        // unknown/missing model: a clean local 404 (never a
                        // real DNS attempt at bolt.ocr)
                        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                            mapOf("Access-Control-Allow-Origin" to "*"),
                            ByteArrayInputStream(ByteArray(0)))
                    }
                    return null // the pinned cdn.jsdelivr.net engine fetches pass through
                }
            }
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (diagN < 12) {
                        diagN++
                        try { Diag.log(appCtx, "scr ocr console " + m.message()) } catch (_: Throwable) {}
                    }
                    return true
                }
            }
            // v1.4.11: NOT attached — the old decorView addView pinned the
            // WebView to the (dying-on-rotation) activity's window; a
            // headless app-context WebView runs its page unattached.
            val html = try {
                appCtx.assets.open("scr_ocr.html").bufferedReader().use { it.readText() }
            } catch (t: Throwable) { "" }
            wv.loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "utf-8", null)
        }

        /** push the image in binder-safe chunks, then fire the OCR job. */
        fun run(langs: Array<String>, b64: String, done: (String) -> Unit, fail: (String) -> Unit) {
            mainH.post {
                if (destroyed) { fail("destroyed"); return@post }
                onDone = done
                onFail = fail
                pushWhenReady(langs.joinToString("+"), b64, 0)
            }
        }

        private fun pushWhenReady(langs: String, b64: String, tries: Int) {
            if (destroyed) return
            if (!ready) {
                if (tries >= 40) { val f = onFail; onDone = null; onFail = null; f?.invoke("page-timeout"); return }
                mainH.postDelayed({ pushWhenReady(langs, b64, tries + 1) }, 500)
                return
            }
            for (c in b64.chunked(200_000)) {
                wv.evaluateJavascript("window.__push('$c')", null)
            }
            wv.evaluateJavascript("window.__ocr('$langs')", null)
            val w = Runnable {
                val f = onFail ?: return@Runnable
                onDone = null
                onFail = null
                f.invoke("timeout")
            }
            wd = w
            mainH.postDelayed(w, 150_000)
        }

        fun destroy() {
            destroyed = true
            onDone = null
            onFail = null
            wd?.let { mainH.removeCallbacks(it) }
            mainH.post {
                try { (wv.parent as? ViewGroup)?.removeView(wv) } catch (_: Throwable) {}
                try { wv.destroy() } catch (_: Throwable) {}
            }
        }

        /** the page → Kotlin channel (bridge thread). */
        private inner class Bridge {
            @JavascriptInterface
            fun ready() { ready = true }

            @JavascriptInterface
            fun progress(status: String, pct: Int) {
                onProgress?.invoke(status, pct)
            }

            @JavascriptInterface
            fun done(json: String) {
                val d = onDone ?: return
                onDone = null
                onFail = null
                wd?.let { mainH.removeCallbacks(it) }
                d.invoke(json)
            }

            @JavascriptInterface
            fun fail(msg: String) {
                val f = onFail ?: return
                onDone = null
                onFail = null
                wd?.let { mainH.removeCallbacks(it) }
                f.invoke(msg)
            }

            @JavascriptInterface
            fun diag(s: String) {
                if (diagN < 12) {
                    diagN++
                    try { Diag.log(appCtx, "scr ocr js $s") } catch (_: Throwable) {}
                }
            }
        }
    }

    // ---------------- the view (bitmap + translated boxes) ----------------

    private class ScreenView(a: Context, val bmp: Bitmap, val blocks: ArrayList<SBlock>) :
            View(a) {

        @Volatile var chip: TextView? = null
        var closed = false

        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val tmp = RectF()
        private val dens = resources.displayMetrics.density
        private val minPx = 9f * dens
        private val maxPx = 26f * dens

        fun bmpRef(): Bitmap = bmp

        override fun onDraw(c: Canvas) {
            try {
                c.drawColor(Color.BLACK)
                val vw = width.toFloat()
                val vh = height.toFloat()
                if (vw <= 0 || vh <= 0) return
                val sc = minOf(vw / bmp.width, vh / bmp.height)
                val dx = (vw - bmp.width * sc) / 2f
                val dy = (vh - bmp.height * sc) / 2f
                tmp.set(dx, dy, dx + bmp.width * sc, dy + bmp.height * sc)
                c.drawBitmap(bmp, null, tmp, null)
                for (b in blocks) drawBlock(c, b, sc, dx, dy)
            } catch (t: Throwable) {}
        }

        private fun drawBlock(c: Canvas, b: SBlock, sc: Float, dx: Float, dy: Float) {
            val l = dx + b.box.left * sc
            val t = dy + b.box.top * sc
            val r = dx + b.box.right * sc
            val bt = dy + b.box.bottom * sc
            if (r - l < 4 || bt - t < 4) return
            val tr = b.trans
            tmp.set(l, t, r, bt)
            // v1.3.76 — the matching kit: card paints the SAMPLED local
            // background (bubble paper / page tone), text contrasts with
            // that color, corners scale with the box, and the font starts
            // from the ORIGINAL text's own line height instead of w/6
            val bgc = if (b.bg != 0) b.bg
                else if (b.light) Color.argb(255, 252, 252, 253) else Color.argb(255, 18, 19, 23)
            val lum = (Color.red(bgc) * 299 + Color.green(bgc) * 587 + Color.blue(bgc) * 114) / 1000
            val radius = (minOf(r - l, bt - t) * 0.22f).coerceIn(4f * dens, 22f * dens)
            if (tr != null) {
                // translated: a card over the original text
                fillP.color = if (b.bg != 0)
                    Color.argb(242, Color.red(bgc), Color.green(bgc), Color.blue(bgc))
                else if (b.light) Color.argb(238, 252, 252, 253)
                else Color.argb(232, 18, 19, 23)
                c.drawRoundRect(tmp, radius, radius, fillP)
                val text = tr.ifBlank { b.orig }
                val tcol = if (lum > 140) Color.argb(255, 17, 18, 22)
                           else Color.argb(255, 240, 242, 246)
                run {
                    // v1.3.78: ONE text path — horizontal, wrapped,
                    // vertically centered. A tall narrow card (a manga
                    // column) reads as stacked lines, never sideways.
                    val lay = layoutFor(b, text, (tmp.width() - 8f * dens).toInt().coerceAtLeast(12),
                            (tmp.height() - 6f * dens).toInt(), tcol)
                    c.save()
                    c.clipRect(tmp)
                    // v1.3.77: the shrunk-to-fit text centers vertically in
                    // the card — a small translation riding a tall bubble
                    // must not cling to the card's top edge
                    val cy = t + ((bt - t) - lay.height) / 2f
                    c.translate(l + 4f * dens, if (cy > t) cy else t + 3f * dens)
                    lay.draw(c)
                    c.restore()
                }
            } else {
                // not yet translated (or failed): outlined original, dimmed
                lineP.color = Color.argb(150, 138, 180, 255)
                c.drawRoundRect(tmp, radius, radius, lineP)
                val lay = layoutFor(b, b.orig, (tmp.width() - 8f * dens).toInt().coerceAtLeast(12),
                        (tmp.height() - 6f * dens).toInt(), Color.argb(165, 255, 255, 255))
                c.save()
                c.clipRect(tmp)
                c.translate(l + 4f * dens, t + 3f * dens)
                lay.draw(c)
                c.restore()
            }
        }

        /** auto-shrinking StaticLayout, cached per (block, revision, size).
         *  v1.3.76: the STARTING size comes from the original text's own
         *  line height (thPx * sc) when the AI reported it — the v1.3.75
         *  w/6 heuristic is only the fallback now. v1.3.77: the start is
         *  also capped by the CARD's own height (a single line can never
         *  tower over its box — a wrong th must not force three shrink
         *  loops before the text fits). */
        private fun layoutFor(b: SBlock, text: String, wpx: Int, hpx: Int, color: Int): StaticLayout {
            val rev = b.rev
            val ex = b.layout
            if (ex != null && b.layoutRev == rev && b.layoutW == wpx && b.layoutH == hpx) return ex
            val tp = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
            tp.color = color
            tp.typeface = Typeface.DEFAULT_BOLD
            val cap = maxOf(minPx, minOf(maxPx * 1.6f, hpx.toFloat()))
            var size = (if (b.thPx > 0f) (b.thPx * scaleHint()) else (wpx / 6f))
                .coerceIn(minPx, cap)
            var lay: StaticLayout
            while (true) {
                tp.textSize = size
                lay = StaticLayout.Builder.obtain(text, 0, text.length, tp, wpx)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, 1.08f)
                        .setIncludePad(false)
                        .build()
                if (lay.height <= hpx || size <= minPx) break
                size = (size * 0.86f).coerceAtLeast(minPx)
            }
            b.layout = lay
            b.layoutRev = rev
            b.layoutW = wpx
            b.layoutH = hpx
            return lay
        }

        /** the screen-scale for thPx (bitmap px -> view px) */
        private fun scaleHint(): Float {
            val vw = width.toFloat()
            val vh = height.toFloat()
            if (vw <= 0 || vh <= 0 || bmp.width <= 0 || bmp.height <= 0) return 1f
            return minOf(vw / bmp.width, vh / bmp.height)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_UP) return true
            try {
                val vw = width.toFloat()
                val vh = height.toFloat()
                if (vw <= 0 || vh <= 0) return true
                val sc = minOf(vw / bmp.width, vh / bmp.height)
                val dx = (vw - bmp.width * sc) / 2f
                val dy = (vh - bmp.height * sc) / 2f
                val x = e.x
                val y = e.y
                var hit: SBlock? = null
                for (b in blocks) {
                    val l = dx + b.box.left * sc
                    val t = dy + b.box.top * sc
                    val r = dx + b.box.right * sc
                    val bt = dy + b.box.bottom * sc
                    if (x >= l && x <= r && y >= t && y <= bt) { hit = b; break }
                }
                hit?.let { showBlock(context, it) }
            } catch (t: Throwable) {}
            return true
        }

        private fun showBlock(ctx: Context, b: SBlock) {
            try {
                val tr = b.trans
                val msg = b.orig + "\n\n\u2193\n\n" + (tr ?: "\u2026")
                AlertDialog.Builder(ctx)
                    .setTitle(if (tr != null) "Translated text" else "Original text")
                    .setMessage(msg)
                    .setPositiveButton("Copy") { d, _ ->
                        try {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("bolt", (tr ?: b.orig)))
                            Fx.toast(ctx, "Copied \u2713")
                        } catch (t: Throwable) {}
                        try { d.dismiss() } catch (_: Throwable) {}
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (t: Throwable) {}
        }

        /** bitmap + overlays rendered at BITMAP resolution (for Save). */
        fun composite(): Bitmap? {
            return try {
                val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawBitmap(bmp, 0f, 0f, null)
                val saveMin = minPx / 2f   // bitmap px are denser than view px at fit<1
                for (b in blocks) drawBlockInto(c, b, saveMin)
                out
            } catch (t: Throwable) { null }
        }

        private fun drawBlockInto(c: Canvas, b: SBlock, minSize: Float) {
            val l = b.box.left.toFloat()
            val t = b.box.top.toFloat()
            val r = b.box.right.toFloat()
            val bt = b.box.bottom.toFloat()
            if (r - l < 4 || bt - t < 4) return
            tmp.set(l, t, r, bt)
            val tr = b.trans
            // v1.3.76: the composite paints the SAME matched card the live
            // overlay shows — sampled background, contrast text, scaled
            // corners, th-based font (v1.3.78: horizontal text only)
            val bgc = if (b.bg != 0) b.bg
                else if (b.light) Color.argb(255, 252, 252, 253) else Color.argb(255, 18, 19, 23)
            val lum = (Color.red(bgc) * 299 + Color.green(bgc) * 587 + Color.blue(bgc) * 114) / 1000
            val radius = (minOf(r - l, bt - t) * 0.22f).coerceIn(4f, 24f)
            if (tr != null) {
                fillP.color = if (b.bg != 0)
                    Color.argb(242, Color.red(bgc), Color.green(bgc), Color.blue(bgc))
                else if (b.light) Color.argb(238, 252, 252, 253)
                else Color.argb(232, 18, 19, 23)
                c.drawRoundRect(tmp, radius, radius, fillP)
                val text = tr.ifBlank { b.orig }
                val tcol = if (lum > 140) Color.argb(255, 17, 18, 22)
                           else Color.argb(255, 240, 242, 246)
                run {
                    // v1.3.78: the composite paints the same horizontal,
                    // centered card the live overlay shows — no rotated
                    // columns anywhere
                    val lay = fit(b, text, (tmp.width() - 8).toInt().coerceAtLeast(12),
                            (tmp.height() - 6).toInt(), tcol, minSize)
                    c.save()
                    c.clipRect(tmp)
                    val cy = t + ((bt - t) - lay.height) / 2f
                    c.translate(l + 4, if (cy > t) cy else t + 3)
                    lay.draw(c)
                    c.restore()
                }
            }
        }

        private fun fit(b: SBlock, text: String, wpx: Int, hpx: Int, color: Int,
                        minSize: Float): StaticLayout {
            val tp = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
            tp.color = color
            tp.typeface = Typeface.DEFAULT_BOLD
            // bitmap space: thPx is already in bitmap pixels (scale 1);
            // the start is capped by the card height like the live overlay
            val cap = maxOf(minSize, minOf(42f, hpx.toFloat()))
            var size = (if (b.thPx > 0f) b.thPx else (wpx / 6f))
                .coerceIn(minSize, cap)
            var lay: StaticLayout
            while (true) {
                tp.textSize = size
                lay = StaticLayout.Builder.obtain(text, 0, text.length, tp, wpx)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, 1.08f)
                        .setIncludePad(false)
                        .build()
                if (lay.height <= hpx || size <= minSize) break
                size = (size * 0.86f).coerceAtLeast(minSize)
            }
            return lay
        }
    }
}
