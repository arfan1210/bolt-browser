package com.bolt.browser.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.3.40 — WORKSPACES (new code → Kotlin; field task 15: "merge Profiles +
 * Sessions into Chrome-style Workspaces — Study / Entertainment / Work").
 *
 * A workspace is a NAMED SPACE with its own tab set and its own PROFILE
 * RECORD (the login name attached to backups/exports). One space is LIVE at
 * a time: the browser's tab session ALWAYS stays a single session — a
 * switch snapshots the live tabs into the outgoing space, then loads the
 * target space's tabs into the live session. No parallel WebViews, no
 * second process; last-tab recovery (tabs.json) keeps describing exactly
 * one set — the active space's.
 *
 * Storage: workspaces.json = [{name, t, profile, tabs:[{title,url}]}].
 * MIGRATION: on first touch, every v1.3.39 saved session (sessions.json,
 * the "Session manager") becomes a workspace; the old file is left on disk
 * untouched as a rollback copy. First run with no sessions seeds the field
 * report's trio — Study, Entertainment, Work — plus the implicit default
 * space Personal.
 *
 * Cookie/data isolation is a deliberate ROUND-5 follow-up: this pass swaps
 * tabs and the profile name only.
 */
object Workspaces {

    const val PREF_ENABLED = "workspaces"
    const val PREF_ACTIVE = "activeWorkspace"
    const val DEFAULT_SPACE = "Personal"

    private fun file(c: Context): File =
            File(Prefs.get(c).filesDir(), "workspaces.json")

    class Space(@JvmField val name: String, @JvmField val at: Long,
                @JvmField val profile: String,
                @JvmField val tabs: List<Pair<String, String>>) {
        val tabCount: Int get() = tabs.size
    }

    @JvmStatic
    fun enabled(c: Context): Boolean = Prefs.get(c).b(PREF_ENABLED, true)

    /** the name of the live space (never empty). */
    @JvmStatic
    fun active(c: Context): String {
        val n = Prefs.get(c).s(PREF_ACTIVE, DEFAULT_SPACE)
        return n.ifEmpty { DEFAULT_SPACE }
    }

    // ---------------------------------------------------------------- read

    @JvmStatic
    fun list(c: Context): List<Space> {
        migrateIfNeeded(c)
        val out = ArrayList<Space>()
        try {
            val arr = Fx.readJsonArray(file(c))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(fromJson(o))
            }
        } catch (t: Throwable) { }
        // the active space must always exist as a record; materialize it if
        // a migration produced a list without it (keeps "Personal" real)
        val act = active(c)
        if (out.none { it.name == act }) {
            out.add(0, Space(act, System.currentTimeMillis(),
                    Prefs.get(c).profileName(), liveTabs(c)))
            persist(c, out)
        }
        return out
    }

    private fun fromJson(o: JSONObject): Space {
        val tabs = ArrayList<Pair<String, String>>()
        val ta = o.optJSONArray("tabs") ?: JSONArray()
        for (j in 0 until ta.length()) {
            val t = ta.optJSONObject(j) ?: continue
            val u = t.optString("url")
            if (u.isNotEmpty()) tabs.add(Pair(t.optString("title"), u))
        }
        return Space(o.optString("name"), o.optLong("t"),
                o.optString("profile"), tabs)
    }

    private fun persist(c: Context, spaces: List<Space>) {
        try {
            val arr = JSONArray()
            for (s in spaces) {
                val o = JSONObject()
                o.put("name", s.name)
                o.put("t", s.at)
                o.put("profile", s.profile)
                val ta = JSONArray()
                for ((t, u) in s.tabs) {
                    val to = JSONObject()
                    to.put("title", t)
                    to.put("url", u)
                    ta.put(to)
                }
                o.put("tabs", ta)
                arr.put(o)
            }
            Fx.writeJsonArray(file(c), arr)
        } catch (t: Throwable) { }
    }

    // ---------------------------------------------------------------- write

    /** upsert a space (by name) with the given tabs/profile. */
    @JvmStatic
    fun save(c: Context, name: String, profile: String,
             tabs: List<Pair<String, String>>): Boolean {
        if (name.isBlank()) return false
        val spaces = ArrayList(list(c))
        var found = false
        for (i in spaces.indices) {
            if (spaces[i].name == name) {
                spaces[i] = Space(name, System.currentTimeMillis(), profile, tabs)
                found = true
            }
        }
        if (!found) spaces.add(Space(name, System.currentTimeMillis(), profile, tabs))
        persist(c, spaces)
        return true
    }

    @JvmStatic
    fun rename(c: Context, old: String, new: String): Boolean {
        if (new.isBlank()) return false
        val spaces = ArrayList(list(c))
        if (spaces.any { it.name == new }) return false   // name taken
        for (i in spaces.indices) {
            if (spaces[i].name == old) {
                spaces[i] = Space(new, spaces[i].at, spaces[i].profile, spaces[i].tabs)
            }
        }
        persist(c, spaces)
        if (active(c) == old) Prefs.get(c).set(PREF_ACTIVE, new)
        return true
    }

    @JvmStatic
    fun delete(c: Context, name: String) {
        if (name == active(c)) return           // never delete the live space
        val spaces = ArrayList(list(c)).filter { it.name != name }
        persist(c, spaces)
    }

    /**
     * The switch: snapshot the live tabs into the outgoing space, point the
     * live-space pref at the target, apply the target's profile name, and
     * return the target's tabs for the caller to load into the (single)
     * live session. The caller owns the TabManager swap.
     */
    @JvmStatic
    fun switchTo(c: Context, target: String,
                 liveTabs: List<Pair<String, String>>): List<Pair<String, String>> {
        val spaces = ArrayList(list(c))
        val act = active(c)
        // 1. the outgoing space keeps the live tabs
        for (i in spaces.indices) {
            if (spaces[i].name == act) {
                spaces[i] = Space(act, System.currentTimeMillis(),
                        spaces[i].profile, liveTabs)
            }
        }
        // 2. target becomes the live space
        Prefs.get(c).set(PREF_ACTIVE, target)
        var prof = ""
        var tabs = emptyList<Pair<String, String>>()
        for (i in spaces.indices) {
            if (spaces[i].name == target) {
                prof = spaces[i].profile
                tabs = spaces[i].tabs
            }
        }
        persist(c, spaces)
        // 3. the active workspace's profile rides along on backups
        if (prof.isNotEmpty()) Prefs.get(c).set("profileName", prof)
        return tabs
    }

    // ---------------------------------------------------------------- misc

    /** the live tab set, http pages only (the same filter Sessions used).
     *  NEVER in the incognito process: incognito tabs must not leak into a
     *  persisted store (TabManager itself never persists them either). */
    @JvmStatic
    fun liveTabs(c: Context): List<Pair<String, String>> {
        if (com.bolt.browser.core.TabManager.INCOGNITO) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        try {
            val tm = com.bolt.browser.core.TabManager.get(c)
            // v1.4.9: the thread law — iteration under the list monitor
            synchronized(tm.tabs) {
                for (t in tm.tabs) {
                    if (t.isHome || t.url.isNullOrEmpty() || !t.url.startsWith("http")) continue
                    out.add(Pair(t.title ?: "", t.url))
                }
            }
        } catch (t: Throwable) { }
        return out
    }

    /** "share as a link list" — same format the session manager used. */
    @JvmStatic
    fun shareText(s: Space): String {
        val sb = StringBuilder(s.name).append(" (").append(s.tabs.size).append(" tabs)\n\n")
        for ((t, u) in s.tabs) sb.append(t.ifEmpty { u }).append("\n").append(u).append("\n\n")
        return sb.toString().trim()
    }

    // ---------------------------------------------------------------- migration

    /**
     * One-shot: v1.3.39 sessions.json → workspaces.json (each saved session
     * becomes a space; the old file is KEPT as the rollback copy). First
     * run with nothing to import seeds Personal + Study / Entertainment /
     * Work. Idempotent: runs only while workspaces.json is absent.
     */
    @JvmStatic
    fun migrateIfNeeded(c: Context) {
        try {
            if (file(c).exists()) return
            val spaces = ArrayList<Space>()
            try {
                val arr = Fx.readJsonArray(
                        File(Prefs.get(c).filesDir(), "sessions.json"))
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    spaces.add(fromJson(o))
                }
            } catch (t: Throwable) { }
            if (spaces.isEmpty()) {
                // seed the field report's trio + the implicit default
                spaces.add(Space(DEFAULT_SPACE, System.currentTimeMillis(),
                        Prefs.get(c).profileName(), liveTabs(c)))
                for (n in arrayOf("Study", "Entertainment", "Work")) {
                    spaces.add(Space(n, 0L, "", emptyList()))
                }
            } else if (spaces.none { it.name == active(c) }) {
                // migrated sessions but no active-named space: keep Personal
                // as the live space so nothing gets overwritten by accident
                spaces.add(0, Space(DEFAULT_SPACE, System.currentTimeMillis(),
                        Prefs.get(c).profileName(), liveTabs(c)))
            }
            persist(c, spaces)
        } catch (t: Throwable) { }
    }
}
