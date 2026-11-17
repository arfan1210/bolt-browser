package com.bolt.browser.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bolt.browser.R
import com.bolt.browser.util.Fx
import com.bolt.browser.util.MenuCards
import com.bolt.browser.util.Ui
import com.bolt.browser.util.Workspaces

/**
 * v1.3.40 — WORKSPACES (new code → Kotlin; field task 15): the Chrome-style
 * merge of the v1.3.39 "session manager" + the backup login-name "profile"
 * into ONE concept — named spaces (Study / Entertainment / Work …), each
 * carrying its own tab set and its own profile record.
 *
 * ONE SPACE IS LIVE AT A TIME. Switching snapshots the open tabs into the
 * outgoing space and loads the target's tabs into the single live session
 * (close-all + reopen, exactly the restore flow the session manager used —
 * tabs.json still describes exactly one set, so last-tabs recovery and the
 * no-multiple-sessions rule are untouched). Cookie isolation is a round-5
 * follow-up; this pass swaps tabs + the backup profile name.
 *
 * Reached from: surface Settings → Workspaces card, the browser 3-dot menu
 * (normal mode only), deep settings → General, and both settings searches.
 */
class WorkspacesActivity : AppCompatActivity() {

    private var list: LinearLayout? = null
    private val incognito get() = com.bolt.browser.core.TabManager.INCOGNITO

    override fun onCreate(savedInstanceState: Bundle?) {
        Fx.theme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generic_list)
        findViewById<TextView>(R.id.tv_g_title).text = "Workspaces"
        findViewById<View>(R.id.rv_g).visibility = View.GONE
        findViewById<View>(R.id.btn_g_back).post {
            findViewById<android.widget.ImageButton>(R.id.btn_g_back).setColorFilter(Fx.dim(this))
        }
        findViewById<View>(R.id.btn_g_back).setOnClickListener { finish() }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = Fx.dp(this, 18)
        root.setPadding(pad, pad, pad, pad + Fx.dp(this, 12))
        val scroller = android.widget.ScrollView(this)
        scroller.setFillViewport(true)
        scroller.addView(root, LinearLayout.LayoutParams(-1, -2))
        (findViewById<View>(R.id.btn_g_back).parent.parent as android.view.ViewGroup)
                .addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        val note = TextView(this)
        note.text = if (incognito) {
            "Read-only in private mode — open Workspaces from the normal browser to switch spaces."
        } else {
            "One space is live at a time. Switching saves the open tabs into the outgoing " +
                    "space and opens the target's tabs — your single session and last-tabs " +
                    "recovery are never touched."
        }
        note.textSize = 13f
        note.setTextColor(Fx.dim(this))
        note.setPadding(0, 0, 0, Fx.dp(this, 10))
        root.addView(note, LinearLayout.LayoutParams(-1, -2))

        list = LinearLayout(this)
        (list as LinearLayout).orientation = LinearLayout.VERTICAL
        root.addView(list, LinearLayout.LayoutParams(-1, -2))

        val add = Ui.filled(this, "New workspace")
        add.setOnClickListener { Ui.tap(it); showNew() }
        val alp = LinearLayout.LayoutParams(-1, Fx.dp(this, 48))
        alp.topMargin = Fx.dp(this, 14)
        root.addView(add, alp)
        rebuild()
    }

    private fun rebuild() {
        val host = list ?: return
        host.removeAllViews()
        val spaces = Workspaces.list(this)
        val act = Workspaces.active(this)
        for (s in spaces) host.addView(spaceCard(s, s.name == act))
    }

    /** deterministic avatar color per space name (Chrome-profile feel). */
    private fun avatarColor(name: String): Int {
        val palette = intArrayOf(
                0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), 0xFF10B981.toInt(),
                0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFF06B6D4.toInt(),
                0xFFEC4899.toInt(), 0xFF84CC16.toInt())
        return palette[Math.floorMod(name.hashCode(), palette.size)]
    }

    private fun spaceCard(s: Workspaces.Space, isActive: Boolean): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.background = Fx.cardBg(this, 0)
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 13), Fx.dp(this, 16), Fx.dp(this, 13))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        // avatar circle with the space's initial
        val av = TextView(this)
        av.text = s.name.trim().take(1).uppercase()
        av.textSize = 20f
        av.typeface = Ui.medium()
        av.setTextColor(0xFFFFFFFF.toInt())
        av.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(avatarColor(s.name))
        av.background = bg
        head.addView(av, LinearLayout.LayoutParams(Fx.dp(this, 44), Fx.dp(this, 44)))

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = s.name
        t.textSize = 17f
        t.typeface = Ui.medium()
        t.setTextColor(Fx.txt(this))
        titles.addView(t, LinearLayout.LayoutParams(-1, -2))
        val d = TextView(this)
        d.text = describe(s)
        d.textSize = 13f
        d.setTextColor(Fx.dim(this))
        titles.addView(d, LinearLayout.LayoutParams(-1, -2))
        val hlp = LinearLayout.LayoutParams(0, -2, 1f)
        hlp.marginStart = Fx.dp(this, 14)
        head.addView(titles, hlp)

        if (isActive) {
            val chip = TextView(this)
            chip.text = "ACTIVE"
            chip.textSize = 11f
            chip.typeface = Ui.medium()
            chip.setTextColor(Fx.accent(this))
            chip.setBackgroundResource(R.drawable.bg_chip)
            chip.setPadding(Fx.dp(this, 10), Fx.dp(this, 4), Fx.dp(this, 10), Fx.dp(this, 4))
            head.addView(chip, LinearLayout.LayoutParams(-2, -2))
        }
        row.addView(head, LinearLayout.LayoutParams(-1, -2))

        // action cards per space (hybrid tier, same DNA as the old sessions)
        val cards = ArrayList<MenuCards.Card>()
        if (isActive) {
            cards.add(MenuCards.card(R.drawable.ic_person, "Profile",
                    "The login name attached to this workspace — it rides along on backups and exports.",
                    Runnable { editProfile(s) }))
        } else if (!incognito) {
            cards.add(MenuCards.card(R.drawable.ic_reload, "Switch",
                    "Make this the live space: the open tabs are saved into the outgoing space, then this space's tabs open. Still ONE live session; last-tabs recovery is never touched.",
                    Runnable { doSwitch(s) }))
        }
        if (!incognito) {
            cards.add(MenuCards.card(R.drawable.ic_download, "Save tabs",
                    "Copy the currently open tabs into this space without switching to it.",
                    Runnable {
                        val tabs = Workspaces.liveTabs(this)
                        if (tabs.isEmpty()) { Fx.toast(this, "No open pages to save"); return@Runnable }
                        Workspaces.save(this, s.name, s.profile, tabs)
                        Fx.toast(this, "${tabs.size} tabs saved into \"${s.name}\"")
                        rebuild()
                    }))
        }
        cards.add(MenuCards.card(R.drawable.ic_edit, "Rename",
                "Change this workspace's name.", Runnable { showRename(s) }))
        cards.add(MenuCards.card(R.drawable.ic_share, "Share",
                "Share this workspace as a plain list of links (title + URL per line).", Runnable {
                    try {
                        val i = android.content.Intent(android.content.Intent.ACTION_SEND)
                        i.type = "text/plain"
                        i.putExtra(android.content.Intent.EXTRA_TEXT, Workspaces.shareText(s))
                        startActivity(android.content.Intent.createChooser(i, "Share workspace"))
                    } catch (t: Throwable) { }
                }))
        if (!isActive) {
            cards.add(MenuCards.card(R.drawable.ic_delete, "Delete",
                    "Remove this workspace for good (the live space can't be deleted).",
                    Runnable {
                        AlertDialog.Builder(this)
                                .setMessage("Delete \"${s.name}\"?")
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    Workspaces.delete(this, s.name)
                                    rebuild()
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                    }))
        }
        val strip = MenuCards.cardStrip(this, cards)
        strip.setPadding(0, Fx.dp(this, 8), 0, 0)
        row.addView(strip, LinearLayout.LayoutParams(-1, -2))

        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Fx.dp(this, 6)
        row.layoutParams = lp
        return row
    }

    private fun describe(s: Workspaces.Space): String {
        val act = Workspaces.active(this)
        val liveCount = Workspaces.liveTabs(this).size
        val n = if (s.name == act) (if (liveCount > 0) liveCount else s.tabCount) else s.tabCount
        val when_ = if (s.at > 0) java.text.SimpleDateFormat(" · MMM d, HH:mm",
                java.util.Locale.US).format(java.util.Date(s.at)) else ""
        val prof = if (s.profile.isNotEmpty()) " · ${s.profile}" else ""
        return "$n tabs$when_$prof"
    }

    // ---------------------------------------------------------------- actions

    private fun doSwitch(s: Workspaces.Space) {
        try {
            val tm = com.bolt.browser.core.TabManager.get(this)
            val tabs = Workspaces.switchTo(this, s.name, Workspaces.liveTabs(this))
            // load the target space into THE single live session (same flow
            // the session manager's "replace" restore used)
            while (tm.tabs.isNotEmpty()) tm.close(tm.tabs.size - 1)
            for ((_, u) in tabs) {
                val t = com.bolt.browser.core.Tab()
                t.isHome = false
                t.url = u
                tm.tabs.add(t)
            }
            if (tm.tabs.isEmpty()) {
                val home = com.bolt.browser.core.Tab()
                tm.tabs.add(home)
            }
            tm.active = tm.tabs.size - 1
            tm.save()
        } catch (t: Throwable) { }
        Fx.toast(this, "Switched to \"${s.name}\"")
        finish()
    }

    private fun editProfile(s: Workspaces.Space) {
        val et = EditText(this)
        et.setText(s.profile)
        AlertDialog.Builder(this)
                .setTitle(R.string.profile)
                .setMessage("The name attached to backups and exports while this workspace is live.")
                .setView(et)
                .setPositiveButton(R.string.save) { _, _ ->
                    val n = et.text.toString().trim()
                    Workspaces.save(this, s.name, n,
                            if (s.name == Workspaces.active(this))
                                Workspaces.liveTabs(this) else s.tabs)
                    if (s.name == Workspaces.active(this)) {
                        com.bolt.browser.util.Prefs.get(this).set("profileName", n)
                    }
                    rebuild()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun showRename(s: Workspaces.Space) {
        val et = EditText(this)
        et.setText(s.name)
        AlertDialog.Builder(this)
                .setTitle("Rename workspace")
                .setView(et)
                .setPositiveButton(R.string.save) { _, _ ->
                    val n = et.text.toString().trim()
                    if (n.isEmpty() || n == s.name) return@setPositiveButton
                    if (!Workspaces.rename(this, s.name, n)) {
                        Fx.toast(this, "That name is already used")
                    }
                    rebuild()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun showNew() {
        val et = EditText(this)
        et.hint = "Workspace name"
        AlertDialog.Builder(this)
                .setTitle("New workspace")
                .setView(et)
                .setPositiveButton(R.string.save) { _, _ ->
                    val n = et.text.toString().trim()
                    if (n.isEmpty()) return@setPositiveButton
                    if (Workspaces.list(this).any { it.name == n }) {
                        Fx.toast(this, "That name is already used")
                        return@setPositiveButton
                    }
                    Workspaces.save(this, n, "", emptyList())
                    rebuild()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }
}
