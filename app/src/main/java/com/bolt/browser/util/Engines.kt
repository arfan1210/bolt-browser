package com.bolt.browser.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.3.39 — CUSTOM SEARCH ENGINES + BANG KEYWORDS (new code → Kotlin;
 * menu-3 item 6, config in DEEP settings per the user's spec).
 *
 * engines.json: [{name, url ("…%s…"), bang ("w")}]
 * The omnibox routes "!w cats" → the engine with bang "w"; the plain
 * search row gains every custom engine as its own choice. Built-ins stay
 * first-class (Settings → General → Search engine untouched) — customs
 * ADD to the vocabulary.
 */
object Engines {

    private fun file(c: Context): File = File(Prefs.get(c).filesDir(), "engines.json")

    class Engine(@JvmField val name: String, @JvmField val url: String, @JvmField val bang: String)

    /** v1.3.40 — PRE-ADDED BANG PRESETS (field-report task: "pre-added !w
     *  bang presets"): the classic DuckDuckGo-style vocabulary, seeded ONCE
     *  (bangsSeeded flag) and only while engines.json is still empty —
     *  anyone who curates their own list never gets overwritten, and a
     *  user who deletes the presets never sees them return. */
    private val PRESETS = arrayOf(
            arrayOf("Wikipedia", "https://en.wikipedia.org/w/index.php?search=%s", "w"),
            arrayOf("YouTube", "https://www.youtube.com/results?search_query=%s", "yt"),
            arrayOf("Google", "https://www.google.com/search?q=%s", "g"),
            arrayOf("GitHub", "https://github.com/search?q=%s", "gh"),
            arrayOf("Stack Overflow", "https://stackoverflow.com/search?q=%s", "so"),
            arrayOf("Amazon", "https://www.amazon.com/s?k=%s", "a"),
            arrayOf("Google Maps", "https://www.google.com/maps/search/%s", "m"),
            arrayOf("Google Images", "https://www.google.com/search?tbm=isch&q=%s", "i"),
            arrayOf("DuckDuckGo", "https://duckduckgo.com/?q=%s", "d"),
            arrayOf("Google News", "https://news.google.com/search?q=%s", "n"))

    @JvmStatic
    fun seedPresets(c: Context) {
        try {
            val p = Prefs.get(c)
            if (p.b("bangsSeeded", false)) return
            p.set("bangsSeeded", true)
            if (list(c).isNotEmpty()) return   // user already curated engines
            for (e in PRESETS) add(c, e[0], e[1], e[2])
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun list(c: Context): List<Engine> {
        val out = ArrayList<Engine>()
        try {
            val arr = Fx.readJsonArray(file(c))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val url = o.optString("url")
                if (name.isEmpty() || url.isEmpty() || !url.contains("%s")) continue
                out.add(Engine(name, url, o.optString("bang").lowercase()))
            }
        } catch (t: Throwable) { }
        return out
    }

    private fun save(c: Context, list: List<Engine>) {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("name", e.name)
            o.put("url", e.url)
            o.put("bang", e.bang)
            arr.put(o)
        }
        Fx.writeJsonArray(file(c), arr)
    }

    @JvmStatic
    fun add(c: Context, name: String, url: String, bang: String): String? {
        val n = name.trim()
        val u = url.trim()
        val b = bang.trim().lowercase().removePrefix("!")
        if (n.isEmpty()) return "Name is required"
        if (!u.contains("%s")) return "The URL needs %s where the query goes"
        if (!u.startsWith("http")) return "The URL must start with https://"
        val l = list(c).toMutableList()
        if (l.any { it.bang.isNotEmpty() && it.bang == b && b.isNotEmpty() })
            return "The !${b} keyword is already used"
        l.add(Engine(n, u, b))
        save(c, l)
        return null
    }

    @JvmStatic
    fun removeAt(c: Context, idx: Int) {
        val l = list(c).toMutableList()
        if (idx in l.indices) { l.removeAt(idx); save(c, l) }
    }

    /**
     * Resolve an omnibox input carrying a bang ("!w cats" / "!yt lofi").
     * Returns the final URL (engine template with the encoded query), or
     * null when the input has no known bang.
     */
    @JvmStatic
    fun resolveBang(c: Context, input: String): String? {
        val q = input.trim()
        if (!q.startsWith("!")) return null
        val sp = q.indexOf(' ')
        if (sp < 2) return null
        val bang = q.substring(1, sp).lowercase()
        val rest = q.substring(sp + 1).trim()
        if (rest.isEmpty()) return null
        for (e in list(c)) {
            if (e.bang == bang) return e.url.replace("%s", java.net.URLEncoder.encode(rest, "UTF-8"))
        }
        return null
    }

    /**
     * v1.3.95 — REVERSE-RESOLVE a search-engine URL back to its QUERY text
     * (the omnibox fill button: tap a history row of a past search —
     * "how to make rice" — and the query lands in the field editable, so
     * "rice balls" is one append instead of a retype). Matches the five
     * built-in engine templates plus every custom engine by the
     * prefix/suffix around %s, then a conservative generic pass (the
     * q / query / search_query / text params, /search/<q> path styles).
     * Returns null when [url] is not a recognizable search URL.
     */
    @JvmStatic
    fun queryOf(c: Context, url: String?): String? {
        if (url.isNullOrEmpty() || !url.startsWith("http")) return null
        val u = url.substringBefore('#')
        val tpls = ArrayList<String>()
        try {
            tpls.addAll(c.resources.getStringArray(com.bolt.browser.R.array.search_engine_urls))
        } catch (ignored: Throwable) {}
        try { for (e in list(c)) tpls.add(e.url) } catch (ignored: Throwable) {}
        for (tpl in tpls) {
            val i = tpl.indexOf("%s")
            if (i < 0) continue
            val pre = tpl.substring(0, i)
            val post = tpl.substring(i + 2)
            if (!u.startsWith(pre)) continue
            val mid = if (post.isNotEmpty()) {
                if (!u.endsWith(post)) continue
                u.substring(pre.length, u.length - post.length)
            } else u.substring(pre.length)
            val dec = decodeQuery(mid.substringBefore('&')) ?: continue
            if (dec.isNotBlank()) return dec
        }
        // generic pass: any ?q= / &query= / search_query= / text= param
        try {
            val qs = u.substringAfter('?', "")
            for (pair in qs.split('&')) {
                if (pair.isEmpty()) continue
                val k = pair.substringBefore('=')
                if (k == "q" || k == "query" || k == "search_query"
                        || k == "text" || k == "k") {
                    val v = decodeQuery(pair.substringAfter('=', "")) ?: continue
                    if (v.isNotBlank()) return v
                }
            }
            val m = Regex("/(?:maps/)?search/([^/?&]+)").find(u)
            if (m != null) {
                val v = decodeQuery(m.groupValues[1])
                if (!v.isNullOrBlank()) return v
            }
        } catch (ignored: Throwable) {}
        return null
    }

    /** '+'-aware query decoding (URLEncoder wrote the space as +). */
    private fun decodeQuery(s: String): String? = try {
        java.net.URLDecoder.decode(s, "UTF-8").trim()
    } catch (t: Throwable) { s }
}
