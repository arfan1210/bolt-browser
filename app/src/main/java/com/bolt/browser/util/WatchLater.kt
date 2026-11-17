package com.bolt.browser.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * v1.3.38 — the WATCH LATER list (the user's "I guess you can add a watch
 * later or similar" — menu item 5 realized as the reading/video queue; new
 * code → Kotlin per the README language rule).
 *
 * A plain JSON queue in filesDir/watchlater.json: {url, title, addedAt, read}.
 * Unread items float to the top; opening an item from the list marks it read
 * (Chrome's reading-list behavior). Included in the backup zip since v1.3.38.
 */
object WatchLater {

    class Item(val url: String, val title: String, val addedAt: Long, val read: Boolean)

    private fun file(c: Context): File = File(Prefs.get(c).filesDir(), "watchlater.json")

    // v1.3.79 — mtime cache (the 3-dot menu reads this on the main thread)
    @Volatile private var cache: List<Item>? = null
    @Volatile private var cacheAt = 0L

    @JvmStatic
    fun list(c: Context): List<Item> {
        val f = file(c)
        if (!f.exists()) { cache = null; return emptyList() }
        try {
            val mt = f.lastModified()
            val hit = cache
            if (hit != null && mt == cacheAt) return hit
        } catch (_: Throwable) {}
        return try {
            val arr = JSONArray(String(FileInputStream(f).use { it.readBytes() }, Charsets.UTF_8))
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isBlank()) continue
                out.add(Item(url, o.optString("title"), o.optLong("addedAt", 0),
                        o.optBoolean("read", false)))
            }
            // unread first, newest first inside each group
            out.sortWith(compareByDescending<Item> { it.read }.thenByDescending { it.addedAt })
            try { cache = out; cacheAt = f.lastModified() } catch (_: Throwable) {}
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun persist(c: Context, items: List<Item>) {
        try {
            val arr = JSONArray()
            for (m in items) {
                val o = JSONObject()
                o.put("url", m.url)
                o.put("title", m.title)
                o.put("addedAt", m.addedAt)
                o.put("read", m.read)
                arr.put(o)
            }
            FileOutputStream(file(c)).use { it.write(arr.toString().toByteArray(Charsets.UTF_8)) }
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun add(c: Context, url: String, title: String): Boolean {
        if (url.isBlank()) return false
        val items = list(c).toMutableList()
        // already saved? bump it back to the top as unread instead of duplicating
        items.removeAll { it.url == url }
        items.add(0, Item(url, title.ifBlank { url }, System.currentTimeMillis(), false))
        persist(c, items)
        return true
    }

    @JvmStatic
    fun contains(c: Context, url: String): Boolean = list(c).any { it.url == url }

    /** opening (or re-saving) an item flips it read. */
    @JvmStatic
    fun markRead(c: Context, url: String, read: Boolean) {
        val items = list(c).toMutableList()
        var changed = false
        for (i in items.indices) {
            if (items[i].url == url && items[i].read != read) {
                items[i] = Item(items[i].url, items[i].title, items[i].addedAt, read)
                changed = true
            }
        }
        if (changed) persist(c, items)
    }

    @JvmStatic
    fun remove(c: Context, url: String) {
        val items = list(c).toMutableList()
        items.removeAll { it.url == url }
        persist(c, items)
    }

    /** v1.4.6 — the undo path for a remove (the user's "an undo for
     *  deleting anything" ask): re-inserts the captured item with its
     *  ORIGINAL fields (addedAt/read survive), so the sorted view places
     *  it back exactly where it was. */
    @JvmStatic
    fun restore(c: Context, url: String, title: String, addedAt: Long, read: Boolean) {
        if (url.isBlank()) return
        val items = list(c).toMutableList()
        items.removeAll { it.url == url }
        items.add(Item(url, title, addedAt, read))
        persist(c, items)
    }

    @JvmStatic
    fun clearRead(c: Context): Int {
        val items = list(c)
        val n = items.count { it.read }
        persist(c, items.filterNot { it.read })
        return n
    }

    @JvmStatic
    fun count(c: Context): Int = list(c).size

    @JvmStatic
    fun unread(c: Context): Int = list(c).count { !it.read }
}
