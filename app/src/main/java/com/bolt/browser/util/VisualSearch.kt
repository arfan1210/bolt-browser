package com.bolt.browser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * v1.4.12 — OMNIBOX IMAGE SEARCH (the camera/gallery door).
 *
 * Routes a picture to the SELECTED engine's visual-search endpoint. The
 * built-in engine list (R.array.search_engine_names) is the index space:
 *
 *   0 Google      -> Google's classic search-by-image POST (field
 *                    "encoded_image"), which 302s to a stable results URL.
 *   1 DuckDuckGo  -> has NO public reverse-image upload of its own; DDG's
 *                    image index is Bing-sourced, so the picture rides
 *                    Bing's visual search (field "imageBin").
 *   2 Bing        -> Bing visual search POST (field "imageBin").
 *   3 Brave       -> no public upload endpoint either; Bing as well.
 *   4 Startpage   -> serves Google results; the Google endpoint it is.
 *
 * The upload is a hand-rolled multipart/form-data POST on
 * HttpURLConnection (no extra dependency): Java does not auto-follow a
 * POST's 3xx, so the redirect is read manually and re-GET; a direct 200
 * (Bing's shape) is saved as a local HTML result page. Both paths end in a
 * URL loadInTab() can render. On network failure the caller gets null and
 * falls back to the engine's visual-search HOME page (manual upload there).
 */
object VisualSearch {

    /** result callback (a fun-interface so Java call sites can lambda it;
     *  Kotlin's (String?) -> Unit boxes its return and breaks Java lambdas) */
    fun interface OnResult { fun onResult(url: String?) }

    class Endpoint(@JvmField val postUrl: String, @JvmField val field: String,
            @JvmField val via: String?) {
        /** the engine's visual-search landing page (manual-upload fallback) */
        @JvmField val home: String = if (field == "encoded_image")
            "https://lens.google.com/" else "https://www.bing.com/images?q=&tsc=ImageBasicUpload"
    }

    /** engine index (R.array.search_engine_names order) -> endpoint */
    @JvmStatic
    fun endpointFor(engineIdx: Int): Endpoint = when (engineIdx) {
        0, 4 -> Endpoint("https://www.google.com/searchbyimage/upload",
                "encoded_image", if (engineIdx == 4) "Google" else null)
        2 -> Endpoint("https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIVPV",
                "imageBin", null)
        else -> Endpoint("https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIVPV",
                "imageBin", "Bing")   // DDG (1) and Brave (3)
    }

    /**
     * Downscale + JPEG the picked picture and POST it, handing the loadable
     * result URL to [onDone] ON THE CALLING THREAD'S schedule — onDone
     * itself always runs on the caller's thread via [main] posting. null
     * result = network failure.
     *
     * v1.4.14 — THE OOM CONTRACT: a gallery pick can be a 12 MP photo
     * (4000×3000 → a naive full decode is ~48 MB of ARGB, and the field
     * report named an OutOfMemoryError crash on exactly that flow). The
     * decode is bounds-first (inJustDecodeBounds) with an inSampleSize
     * that lands the long edge at [MAX_EDGE] = 1280 px, an OutOfMemoryError
     * retry ladder that halves the resolution again (up to 3 retries), and
     * only then a JPEG compress — the multipart body never carries more
     * than the engine needs.
     */
    @JvmStatic
    fun search(c: Context, engineIdx: Int, imageFile: File,
            onDone: OnResult) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            var result: String? = null
            try {
                val bmp = decodeCapped(imageFile, MAX_EDGE)
                if (bmp != null) {
                    val bo = ByteArrayOutputStream()
                    // v1.4.17 — THE PAYLOAD FIDELITY PASS (the "grey /
                    // broken image" report): the engine-side matcher reads
                    // fine structure, and q85 was the edge where fine text
                    // in a photo started smearing into grey blocks. q90 is
                    // the fidelity/cost knee for JPEG uploads (still well
                    // under a 1280-px frame's budget); the fine scale below
                    // already rides createScaledBitmap's bilinear FILTER
                    // (the API's filter=true — the SDK's quality knob for
                    // scaled bitmaps), and the multipart boundary has always
                    // been strict CRLF, so the three legs of the report's
                    // diagnosis are pinned here explicitly.
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, bo)
                    bmp.recycle()
                    val ep = endpointFor(engineIdx)
                    result = post(ep, bo.toByteArray(), c)
                }
            } catch (t: Throwable) {
                result = null
            }
            val r = result
            main.post { onResultOf(onDone, r) }
        }.start()
    }

    private fun onResultOf(cb: OnResult, url: String?) { cb.onResult(url) }

    /** the visual-search upload cap: 1280 px of long edge is more than any
     *  engine's matcher uses, and bounds the decode + the POST body. */
    private const val MAX_EDGE = 1280

    /** decode with a hard cap on the long edge. Bounds-first
     *  (inJustDecodeBounds), then an inSampleSize chosen so the sampled
     *  decode's LONG edge lands within [maxEdge] (halve while the longest
     *  halved dimension still clears the cap — extreme aspect ratios are
     *  exactly the memory bombs, so the cap rides the long edge, not both
     *  edges), then the fine scale to the exact cap. v1.4.14: an
     *  OutOfMemoryError on the sampled decode retries at double the sample
     *  (≤3 retries) — memory pressure degrades the resolution, it never
     *  crashes the process. */
    private fun decodeCapped(f: File, maxEdge: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var s = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (s * 2) >= maxEdge) s *= 2
        var rough: Bitmap? = null
        for (attempt in 0 until 4) {
            try {
                val o2 = BitmapFactory.Options().apply { inSampleSize = s }
                rough = BitmapFactory.decodeFile(f.absolutePath, o2)
                break
            } catch (oom: OutOfMemoryError) {
                s *= 2   // the retry ladder: half the pixels, try again
            } catch (t: Throwable) {
                return null
            }
        }
        if (rough == null) return null
        val sc = minOf(1f, maxEdge.toFloat() / maxOf(rough.width, rough.height))
        if (sc >= 1f) return rough
        // filter=true = bilinear resampling — the SDK's scale-quality knob
        // (pinned by the v1.4.17 fidelity pass; false would nearest-sample
        // the fine scale and alias text into the grey blocks the report
        // described)
        val fine = Bitmap.createScaledBitmap(rough,
                (rough.width * sc).toInt().coerceAtLeast(1),
                (rough.height * sc).toInt().coerceAtLeast(1), true)
        if (fine !== rough) rough.recycle()
        return fine
    }

    /**
     * The multipart POST. Returns the URL to load:
     *   - 3xx: the Location target (re-GET'd with redirects followed to the
     *     final stable URL — Google's shape);
     *   - 200: the response body is the results page itself (Bing's shape)
     *     — saved to cache and returned as a file:// URL.
     *   - anything else / IOException: null (caller falls back to home).
     */
    private fun post(ep: Endpoint, jpeg: ByteArray, c: Context): String? {
        val boundary = "BoltVisualSearch" + System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Disposition: form-data; name=\"").append(ep.field)
                .append("\"; filename=\"image.jpg\"\r\n")
        sb.append("Content-Type: image/jpeg\r\n\r\n")
        val head = sb.toString().toByteArray(Charsets.US_ASCII)
        val tail = ("\r\n--$boundary--\r\n").toByteArray(Charsets.US_ASCII)

        val conn = URL(ep.postUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.instanceFollowRedirects = false   // a POST 3xx is never auto-followed
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // a browser-ish UA: engine endpoints quality-gate botless clients
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/124.0.0.0 Mobile Safari/537.36")
            conn.getOutputStream().use { os ->
                os.write(head); os.write(jpeg); os.write(tail)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                var loc = conn.getHeaderField("Location")
                if (loc.isNullOrBlank()) return null
                if (!loc.startsWith("http")) {
                    // relative Location — resolve against the POST host
                    val u = URL(URL(ep.postUrl), loc)
                    loc = u.toString()
                }
                return follow(loc)
            }
            if (code == 200) {
                val body = conn.inputStream.use { it.readBytes() }
                if (body.isEmpty()) return null
                val dir = File(c.filesDir, "visual")
                if (!dir.isDirectory) dir.mkdirs()
                val f = File(dir, "visual-" + System.currentTimeMillis() + ".html")
                f.writeBytes(body)
                return "file://" + f.absolutePath
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** GET a redirect target following further 3xx to the final URL. */
    private fun follow(start: String): String? {
        var url = start
        for (i in 0 until 5) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/124.0.0.0 Mobile Safari/537.36")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return url
                    url = if (loc.startsWith("http")) loc
                    else URL(URL(url), loc).toString()
                    continue
                }
                return url
            } finally {
                conn.disconnect()
            }
        }
        return url
    }
}
