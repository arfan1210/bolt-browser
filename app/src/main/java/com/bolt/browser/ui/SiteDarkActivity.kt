package com.bolt.browser.ui

import android.content.Intent
import android.os.Bundle
import com.bolt.browser.R
import com.bolt.browser.util.Fx
import com.bolt.browser.util.Prefs

/**
 * v1.3.40 — AUTO-DARK EXCEPTIONS MANAGER (round-4 menu item 12).
 *
 * One screen for the whole per-site dark backlog the v1.3.39 page-menu
 * chip builds up: every site with a "dark" override in sites.json gets a
 * switch row (tap = flip this site), plus bulk actions:
 *  - Turn all ON  / Turn all OFF  (bulk toggle)
 *  - Reset all (header trash) — removes ONLY the "dark" key from every
 *    site; adblock/UA/zoom/JS overrides on the same sites survive.
 *
 * Reached from Appearance (under "Darken websites"), and from both
 * settings-search indexes.
 */
class SiteDarkActivity : BaseListActivity() {

    // build() runs from the BASE onCreate, before this class's own field
    // initializers — lazy, not lateinit
    private val p: Prefs by lazy { Prefs.get(this) }

    override fun title(): String = "Per-site dark"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // header action = Reset all (confirm first)
        actionBtn.setImageResource(R.drawable.ic_trash)
        actionBtn.setOnClickListener { confirmResetAll() }
    }

    override fun build() {
        val rows = ArrayList<Row>()
        val sites = p.siteKeysWith("dark")

        // ---- bulk actions ----
        val bulk = Row()
        bulk.section = "Bulk"
        rows.add(bulk)
        if (sites.isNotEmpty()) {
            val on = Row().apply {
                icon = R.drawable.ic_moon
                title = "Turn all ON"
                sub = "Every listed site gets its dark override switched on"
                click = android.view.View.OnClickListener {
                    for (d in sites) p.setSite(d, "dark", true)
                    Fx.toast(this@SiteDarkActivity, "Dark forced ON for ${sites.size} site${if (sites.size == 1) "" else "s"}")
                    refresh()
                }
                chevron = false
            }
            rows.add(on)
            val off = Row().apply {
                icon = R.drawable.ic_sun
                title = "Turn all OFF"
                sub = "Every listed site gets its dark override switched off"
                click = android.view.View.OnClickListener {
                    for (d in sites) p.setSite(d, "dark", false)
                    Fx.toast(this@SiteDarkActivity, "Dark forced OFF for ${sites.size} site${if (sites.size == 1) "" else "s"}")
                    refresh()
                }
                chevron = false
            }
            rows.add(off)
        }

        // ---- the exceptions list ----
        if (sites.isEmpty()) {
            val hint = Row().apply {
                icon = R.drawable.ic_info
                title = "No per-site dark overrides yet"
                sub = "Use the page menu → \"Dark site\" while on a site and the override lands here"
                click = null
                chevron = false
            }
            rows.add(hint)
            setRows(rows)
            return
        }
        for (d in sites) {
            val r = Row().apply {
                icon = R.drawable.ic_moon
                title = d
                sub = if (p.siteBool(d, "dark", false)) "forced dark"
                      else "forced light"
                switchRow = true
                checked = p.siteBool(d, "dark", false)
                switchChange = android.widget.CompoundButton.OnCheckedChangeListener { _, on ->
                    p.setSite(d, "dark", on)
                }
                hint = "This site's dark override (set from the page menu). Flip it here; Reset all in the header clears every override."
            }
            rows.add(r)
        }
        setRows(rows)
    }

    private fun confirmResetAll() {
        val n = p.siteKeysWith("dark").size
        if (n == 0) {
            Fx.toast(this, "Nothing to reset")
            return
        }
        android.app.AlertDialog.Builder(this)
                .setMessage("Remove the dark override from all $n site${if (n == 1) "" else "s"}? Other per-site settings stay.")
                .setPositiveButton(R.string.yes) { _, _ ->
                    val done = p.removeSiteKeyAll("dark")
                    Fx.toast(this, "Cleared $done override${if (done == 1) "" else "s"}")
                    refresh()
                }
                .setNegativeButton(R.string.no, null)
                .show()
    }

    companion object {
        /** the count badge the linking rows show. */
        @JvmStatic
        fun count(ctx: android.content.Context): Int {
            return try {
                Prefs.get(ctx).siteKeysWith("dark").size
            } catch (t: Throwable) {
                0
            }
        }

        @JvmStatic
        fun launch(ctx: android.content.Context) {
            try {
                ctx.startActivity(Intent(ctx, SiteDarkActivity::class.java))
            } catch (ignored: Throwable) {}
        }
    }
}
