package com.bolt.browser.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.3.39 — TAB TRASH BIN (new code → Kotlin; menu-3 item 7).
 * User spec: "default on, turn off-able in deep settings".
 *
 * Every closed tab (and everything "Close all" sweeps) drops into
 * filesDir/tabtrash.json with its URL/title/scroll/group; entries expire
 * after 7 days. Restore rebuilds the tab exactly like a crash-restore
 * does (WebView lazily rebuilt from the URL). Kept SEPARATE from the
 * one-tab undo snackbar — the trash is the "three days later" safety net.
 */
object TabTrash {

    const val PREF = "tabTrash"
    private const val KEEP_MS = 7L * 24 * 3600 * 1000

    private fun file(c: Context): File = File(Prefs.get(c).filesDir(), "tabtrash.json")

    @JvmStatic
    fun enabled(c: Context): Boolean = Prefs.get(c).b(PREF, true)

    /** record a tab about to be closed (home tabs carry nothing worth keeping). */
    @JvmStatic
    fun onClose(c: Context, t: com.bolt.browser.core.Tab?) {
        try {
            if (t == null || !enabled(c) || t.isHome || t.url.isNullOrEmpty()
                    || !t.url.startsWith("http")) return
            val arr = read(c)
            val o = JSONObject()
            o.put("t", System.currentTimeMillis())
            o.put("url", t.url)
            o.put("title", t.title ?: "")
            o.put("group", t.groupId)
            try { o.put("scroll", t.sleepScrollY) } catch (e: Throwable) { }
            arr.put(o)
            write(c, prune(arr))
        } catch (e: Throwable) { }
    }

    private fun prune(arr: JSONArray): JSONArray {
        val now = System.currentTimeMillis()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (now - o.optLong("t") < KEEP_MS) out.put(o)
        }
        return out
    }

    private fun read(c: Context): JSONArray = Fx.readJsonArray(file(c))

    private fun write(c: Context, arr: JSONArray) = Fx.writeJsonArray(file(c), arr)

    /** the live entries (pruned), newest first. */
    @JvmStatic
    fun list(c: Context): JSONArray {
        val arr = prune(read(c))
        write(c, arr)
        val out = JSONArray()
        for (i in arr.length() - 1 downTo 0) out.put(arr.optJSONObject(i))
        return out
    }

    /** restore entry [idx] as a fresh tab; returns its URL ("" = miss). */
    @JvmStatic
    fun restore(c: Context, idx: Int): String {
        try {
            val arr = read(c)
            if (idx < 0 || idx >= arr.length()) return ""
            val o = arr.optJSONObject(idx) ?: return ""
            arr.remove(idx)
            write(c, arr)
            return o.optString("url")
        } catch (t: Throwable) { return "" }
    }

    /** restore everything (Close-all undo); returns the URL list. */
    @JvmStatic
    fun restoreAll(c: Context): List<String> {
        try {
            val arr = read(c)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val u = arr.optJSONObject(i)?.optString("url") ?: continue
                if (u.isNotEmpty()) out.add(u)
            }
            write(c, JSONArray())
            return out
        } catch (t: Throwable) { return emptyList() }
    }

    @JvmStatic
    fun clear(c: Context) = write(c, JSONArray())
}
