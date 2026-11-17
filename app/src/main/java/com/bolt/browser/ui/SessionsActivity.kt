package com.bolt.browser.ui

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
import com.bolt.browser.util.Prefs
import com.bolt.browser.util.Sessions
import com.bolt.browser.util.Ui

/**
 * v1.3.39 — the SESSION MANAGER screen (new code → Kotlin; menu-3 item
 * 15). Reached from Deep settings → General (only while the toggle is
 * on). Save = name + snapshot of the open tabs; restore ASKS append vs
 * replace so the single-session model and the last-tabs recovery stay
 * untouched; share exports the session as a link list.
 */
class SessionsActivity : AppCompatActivity() {

    private var list: LinearLayout? = null
    private var emptyState: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Fx.theme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generic_list)
        findViewById<TextView>(R.id.tv_g_title).text = "Sessions"
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

        list = LinearLayout(this)
        (list as LinearLayout).orientation = LinearLayout.VERTICAL
        root.addView(list, LinearLayout.LayoutParams(-1, -2))

        emptyState = Ui.emptyState(this, R.drawable.ic_history,
                "No saved sessions", "Save the current tabs under a name to reopen the set later")
        root.addView(emptyState, LinearLayout.LayoutParams(-1, -2))

        val save = Ui.filled(this, "Save current tabs")
        save.setOnClickListener { Ui.tap(it); showSave() }
        val slp = LinearLayout.LayoutParams(-1, Fx.dp(this, 48))
        slp.topMargin = Fx.dp(this, 14)
        root.addView(save, slp)
        rebuild()
    }

    private fun currentTabs(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val tm = com.bolt.browser.core.TabManager.get(this)
            for (t in tm.tabs) {
                if (t.isHome || t.url.isNullOrEmpty() || !t.url.startsWith("http")) continue
                out.add(Pair(t.title ?: "", t.url))
            }
        } catch (t: Throwable) { }
        return out
    }

    private fun showSave() {
        val tabs = currentTabs()
        if (tabs.isEmpty()) { Fx.toast(this, "No open pages to save"); return }
        val et = EditText(this)
        et.hint = "Session name"
        AlertDialog.Builder(this)
                .setTitle("Save session (${tabs.size} tabs)")
                .setView(et)
                .setPositiveButton(R.string.save) { _, _ ->
                    val n = et.text.toString().trim().ifEmpty {
                        java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US)
                                .format(java.util.Date())
                    }
                    if (Sessions.save(this, n, tabs)) Fx.toast(this, "Session saved")
                    rebuild()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun rebuild() {
        val host = list ?: return
        host.removeAllViews()
        val ss = Sessions.list(this)
        emptyState?.visibility = if (ss.isEmpty()) View.VISIBLE else View.GONE
        for ((i, s) in ss.withIndex()) host.addView(sessionRow(s, i))
    }

    private fun sessionRow(s: Sessions.Saved, idx: Int): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.background = Fx.cardBg(this, 0)
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 13), Fx.dp(this, 16), Fx.dp(this, 13))
        val t = TextView(this)
        t.text = s.name
        t.textSize = 16f
        t.typeface = Ui.medium()
        t.setTextColor(Fx.txt(this))
        row.addView(t, LinearLayout.LayoutParams(-1, -2))
        val d = TextView(this)
        d.text = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
                .format(java.util.Date(s.at)) + " · " + s.tabs.size + " tabs"
        d.textSize = 13f
        d.setTextColor(Fx.dim(this))
        row.addView(d, LinearLayout.LayoutParams(-1, -2))

        // quick action cards: restore / share / delete
        val cards = ArrayList<MenuCards.Card>()
        cards.add(MenuCards.card(R.drawable.ic_reload, "Restore", "Reopen this session's tabs — you choose whether to add them to the open tabs or replace them (the live session and last-tabs recovery are never touched).", Runnable { askRestore(s) }))
        cards.add(MenuCards.card(R.drawable.ic_share, "Share", "Share the session as a plain list of links (title + URL per line).", Runnable {
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_SEND)
                i.type = "text/plain"
                i.putExtra(android.content.Intent.EXTRA_TEXT, Sessions.shareText(s))
                startActivity(android.content.Intent.createChooser(i, "Share session"))
            } catch (t: Throwable) { }
        }))
        cards.add(MenuCards.card(R.drawable.ic_delete, "Delete", "Remove this saved session for good.", Runnable {
            Sessions.deleteAt(this, idx); rebuild()
        }))
        val strip = MenuCards.cardStrip(this, cards)
        strip.setPadding(0, Fx.dp(this, 8), 0, 0)
        row.addView(strip, LinearLayout.LayoutParams(-1, -2))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Fx.dp(this, 6)
        row.layoutParams = lp
        return row
    }

    private fun askRestore(s: Sessions.Saved) {
        AlertDialog.Builder(this)
                .setTitle("Restore \"${s.name}\"")
                .setMessage("${s.tabs.size} tabs — add them to the open tabs, or replace what is open now?")
                .setPositiveButton("Add to tabs") { _, _ -> restore(s, false) }
                .setNeutralButton("Replace") { _, _ -> restore(s, true) }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun restore(s: Sessions.Saved, replace: Boolean) {
        try {
            val tm = com.bolt.browser.core.TabManager.get(this)
            if (replace) {
                // replace = close everything, then restore (still ONE live
                // session — tabs.json keeps describing exactly one set)
                while (tm.tabs.isNotEmpty()) tm.close(tm.tabs.size - 1)
            }
            for ((_, u) in s.tabs) {
                val t = com.bolt.browser.core.Tab()
                t.isHome = false
                t.url = u
                tm.tabs.add(t)
            }
            tm.active = tm.tabs.size - 1
            tm.save()
        } catch (t: Throwable) { }
        finish()
    }
}
