package com.bolt.browser.util

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v1.4.13 — OMNIBOX NETWORK SUGGESTIONS (the debounced engine pass).
 *
 * THE REPORT: "the omnibox fires a network request for search suggestions
 * on every single keystroke, causing network flooding and UI stutter."
 * The suggestion network itself is new here — and it is BORN DEBOUNCED:
 * BrowserActivity's TextWatcher parks every keystroke behind a 300 ms
 * Handler/Runnable delay (removeCallbacks + postDelayed — see
 * scheduleNetSuggest), so only a typing PAUSE becomes a request. This
 * object owns the request side of that contract.
 *
 * The privacy laws baked in on day one:
 *  - NEVER in incognito (the caller checks; this object double-checks
 *    nothing about the session because it never sees it — no query leaves
 *    the device in private mode, period).
 *  - A settings toggle (Prefs.suggestNetwork, default ON) is the off door.
 *  - The query goes ONLY to the SELECTED engine's suggest endpoint — the
 *    same provider that would see the full query at GO time anyway.
 *
 * Endpoint shape: the OpenSearch "osjson" pair `[echo, [suggestion, …]]`
 * (what Firefox's client param asks Google for; DDG/Bing/Brave publish
 * the same shape). Every failure degrades to an empty list — a dead or
 * reshaped endpoint must NEVER break typing; the worst case is no rows.
 */
object NetSuggest {

    /** the answer handoff (a fun interface SAM — the v1.4.12 law: a plain
     *  Kotlin function type boxes its return and Java lambdas at the call
     *  site fail to compile). */
    fun interface Callback {
        fun onSuggestions(rows: List<String>)
    }

    /** one background thread: requests are serialized behind the debounce,
     *  so one worker can never pile up (a queued second request always
     *  supersedes the first — the caller drops stale answers anyway). */
    private val net: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bolt-suggest").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** rising generation: a response only hands its answer up while it is
     *  still the NEWEST request (the stale-response guard lives here, not
     *  in the caller — a late answer after a new request is dropped even
     *  if the query text happens to match). */
    private val gen = java.util.concurrent.atomic.AtomicLong(0)

    /** the osjson endpoint per built-in engine index (arrays.xml order:
     *  Google, DuckDuckGo, Bing, Brave, Startpage); null = no suggestions
     *  (custom engines ride bangs, not this pass). */
    @JvmStatic
    fun endpointFor(engineIdx: Int): String? = when (engineIdx) {
        0 -> "https://suggestqueries.google.com/complete/search?client=firefox&hl="
        1 -> "https://ac.duckduckgo.com/ac/?type=list&q="
        2 -> "https://api.bing.com/osjson.aspx?query="
        3 -> "https://search.brave.com/api/suggest?q="
        4 -> "https://startpage.com/suggestions?format=json&query="
        else -> null
    }

    /**
     * Ask the engine for completions of [query]; [cb] receives the plain
     * suggestion strings ON THE MAIN THREAD (empty on any failure/timeout).
     * Safe to call from the UI thread only (the debounce Runnable does).
     */
    @JvmStatic
    fun fetch(engineIdx: Int, query: String, cb: Callback) {
        val base = endpointFor(engineIdx) ?: return
        val q = query.trim()
        if (q.isEmpty() || q.startsWith("!")) return   // bangs route elsewhere
        val myGen = gen.incrementAndGet()
        val lang = try {
            java.util.Locale.getDefault().language.take(2)
        } catch (t: Throwable) { "en" }
        val url = if (engineIdx == 0) base + lang + "&q=" + enc(q) else base + enc(q)
        net.execute {
            val out = try {
                request(url, q)
            } catch (t: Throwable) {
                emptyList()
            }
            if (gen.get() == myGen) {
                main.post { try { cb.onSuggestions(out) } catch (ignored: Throwable) {} }
            }
        }
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8")

    /** the GET + osjson parse (background thread). */
    private fun request(url: String, q: String): List<String> {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 4000
            con.readTimeout = 4000
            con.instanceFollowRedirects = true
            con.setRequestProperty("Accept", "application/json")
            con.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                            + "Chrome/124 Mobile Safari/537.36")
            val code = con.responseCode
            if (code != 200) return emptyList()
            val body = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body, q)
        } finally {
            try { con.disconnect() } catch (ignored: Throwable) {}
        }
    }

    /** `[echo, [s1, s2, …]]` with strings OR `{phrase: "…"}` objects
     *  (Google's client=chrome shape uses objects; client=firefox uses
     *  strings — accept both, never crash). Visible for the gate's probe
     *  pins. */
    @JvmStatic
    fun parse(body: String, q: String): List<String> {
        val a = JSONArray(body)
        if (a.length() < 2) return emptyList()
        val arr = a.optJSONArray(1) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = when (val el = arr.opt(i)) {
                is String -> el
                is org.json.JSONObject -> el.optString("phrase")
                else -> null
            } ?: continue
            val t = s.trim()
            // the echo of the query itself is not a suggestion worth a row
            if (t.isEmpty() || t.equals(q, ignoreCase = true)) continue
            out.add(t)
            if (out.size >= 5) break   // cap: five rows is plenty under a keyboard
        }
        return out
    }
}
