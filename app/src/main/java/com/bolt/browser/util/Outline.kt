package com.bolt.browser.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.3.39 — ARTICLE OUTLINE NAVIGATOR (new code → Kotlin; menu-3 item 12).
 *
 * "a slide-out table of contents built from headings (h1–h3) for long
 * pages; tap to jump; great with tab-sleep + reader later."
 *
 * The outline is EXTRACTED IN THE PAGE (see [JS]) where every heading
 * gets an index marker; jumping re-runs a scrollIntoView on that marker —
 * no layout assumptions in Java/Kotlin.
 */
object Outline {

    /** extract h1–h3 as [{l:level, t:text}]; null/empty when the page is
     *  too short to bother (fewer than 3 headings). */
    const val JS = """
(function(){
  try{
    var hs=document.querySelectorAll('h1,h2,h3');
    if(!hs||hs.length<3)return null;
    var out=[];
    for(var i=0;i<hs.length;i++){
      var h=hs[i];
      var t=(h.textContent||'').replace(/\s+/g,' ').trim();
      if(!t||t.length>90)continue;
      h.setAttribute('data-bolt-outline',i);
      out.push({l:parseInt(h.tagName.substring(1),10),t:t});
    }
    return JSON.stringify(out);
  }catch(e){return null;}
})()
"""

    /** scroll to heading [idx] (the marker the extractor stamped). */
    @JvmStatic
    fun jumpJs(idx: Int): String =
            "(function(){var h=document.querySelector('[data-bolt-outline=\"$idx\"]');" +
                    "if(h)h.scrollIntoView({behavior:'smooth',block:'start'});})()"

    /** parse the extractor's JSON into [level, text] pairs (empty on
     *  unreadable) — plain arrays so Java callers need no kotlin.Pair. */
    @JvmStatic
    fun parse(json: String?): List<Array<String>> {
        val out = ArrayList<Array<String>>()
        if (json == null) return out
        return try {
            val arr = JSONArray(json.replace("^\"|\"$".toRegex(), ""))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(arrayOf(o.optInt("l", 1).toString(), o.optString("t")))
            }
            out
        } catch (t: Throwable) { out }
    }

    /** the label for a level (indentation hint for the sheet rows). */
    @JvmStatic
    fun pad(level: Int): String = when (level) { 1 -> ""; 2 -> "    "; else -> "        " }
}

/**
 * v1.3.39 — SESSION MANAGER (new code → Kotlin; menu-3 item 15).
 * User spec: "optional from deep settings, but should not interfere with
 * no multiple sessions and recover last tabs".
 *
 * sessions.json: [{name, t, tabs:[{title,url}]}]. Saving snapshots the
 * CURRENT tab set; restoring APPENDS the saved tabs (or replaces, after
 * an explicit confirm) — either way the single-session model and the
 * crash/last-session restore path are untouched: the browser still saves
 * exactly one live tabs.json. Off by default; the deep-settings toggle
 * gates every entry point.
 */
object Sessions {

    const val PREF = "sessionManager"

    private fun file(c: android.content.Context): File =
            File(Prefs.get(c).filesDir(), "sessions.json")

    @JvmStatic
    fun enabled(c: android.content.Context): Boolean = Prefs.get(c).b(PREF, false)

    class Saved(@JvmField val name: String, @JvmField val at: Long,
                @JvmField val tabs: List<Pair<String, String>>)

    @JvmStatic
    fun list(c: android.content.Context): List<Saved> {
        val out = ArrayList<Saved>()
        try {
            val arr = Fx.readJsonArray(file(c))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tabs = ArrayList<Pair<String, String>>()
                val ta = o.optJSONArray("tabs") ?: JSONArray()
                for (j in 0 until ta.length()) {
                    val t = ta.optJSONObject(j) ?: continue
                    val u = t.optString("url")
                    if (u.isNotEmpty()) tabs.add(Pair(t.optString("title"), u))
                }
                out.add(Saved(o.optString("name"), o.optLong("t"), tabs))
            }
        } catch (t: Throwable) { }
        return out
    }

    @JvmStatic
    fun save(c: android.content.Context, name: String,
             tabs: List<Pair<String, String>>): Boolean {
        try {
            val arr = Fx.readJsonArray(file(c))
            val o = JSONObject()
            o.put("name", name)
            o.put("t", System.currentTimeMillis())
            val ta = JSONArray()
            for ((t, u) in tabs) {
                val to = JSONObject()
                to.put("title", t)
                to.put("url", u)
                ta.put(to)
            }
            o.put("tabs", ta)
            arr.put(o)
            return Fx.writeJsonArray(file(c), arr)
        } catch (t: Throwable) { return false }
    }

    @JvmStatic
    fun deleteAt(c: android.content.Context, idx: Int) {
        try {
            val arr = Fx.readJsonArray(file(c))
            arr.remove(idx)
            Fx.writeJsonArray(file(c), arr)
        } catch (t: Throwable) { }
    }

    /** "share as a link list" — one URL per line, title-prefixed. */
    @JvmStatic
    fun shareText(s: Saved): String {
        val sb = StringBuilder(s.name).append(" (").append(s.tabs.size).append(" tabs)\n\n")
        for ((t, u) in s.tabs) sb.append(t.ifEmpty { u }).append("\n").append(u).append("\n\n")
        return sb.toString().trim()
    }
}
