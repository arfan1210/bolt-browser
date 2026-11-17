package com.bolt.browser.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bolt.browser.R
import com.bolt.browser.util.Fx
import com.bolt.browser.util.MenuCards
import com.bolt.browser.util.Prefs
import com.bolt.browser.util.TabTrash
import com.bolt.browser.util.Ui

/**
 * v1.3.39 — the TAB TRASH BIN screen (new code → Kotlin; menu-3 item 7).
 * Reached from the tabs switcher's trash button. Closed tabs rest here
 * for 7 days; restore reopens them as fresh tabs (exactly like a
 * crash-restore — URL + scroll position).
 */
class TabTrashActivity : AppCompatActivity() {

    private var list: LinearLayout? = null
    private var emptyState: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Fx.theme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generic_list)
        findViewById<TextView>(R.id.tv_g_title).text = "Recently closed"
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

        emptyState = Ui.emptyState(this, R.drawable.ic_trash,
                "Nothing here", "Closed tabs are kept for 7 days")
        root.addView(emptyState, LinearLayout.LayoutParams(-1, -2))

        val sweep = Ui.quiet(this, "Restore all · Empty trash")
        sweep.setOnClickListener {
            Ui.tap(it)
            val urls = TabTrash.restoreAll(this)
            if (urls.isEmpty()) { Fx.toast(this, "Trash is empty"); return@setOnClickListener }
            openAll(urls)
        }
        val clp = LinearLayout.LayoutParams(-1, Fx.dp(this, 48))
        clp.topMargin = Fx.dp(this, 14)
        root.addView(sweep, clp)
        rebuild()
    }

    private fun openAll(urls: List<String>) {
        try {
            val tm = com.bolt.browser.core.TabManager.get(this)
            for (u in urls) {
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

    private fun rebuild() {
        val host = list ?: return
        host.removeAllViews()
        val arr = TabTrash.list(this)
        emptyState?.visibility = if (arr.length() == 0) View.VISIBLE else View.GONE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            host.addView(trashRow(o.optString("title"), o.optString("url"), i))
        }
    }

    private fun trashRow(title: String, url: String, idx: Int): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Fx.cardBg(this, 0)
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 13), Fx.dp(this, 16), Fx.dp(this, 13))
        val av = android.widget.ImageView(this)
        av.setImageDrawable(com.bolt.browser.util.FaviconStore.avatar(
                this, Fx.hostOf(url), Fx.baseDomain(Fx.hostOf(url)), 36))
        row.addView(av, LinearLayout.LayoutParams(Fx.dp(this, 36), Fx.dp(this, 36)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = title.ifEmpty { url }
        t.textSize = 15f
        t.typeface = Ui.medium()
        t.setTextColor(Fx.txt(this))
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(t, LinearLayout.LayoutParams(-1, -2))
        val s = TextView(this)
        s.text = Fx.hostOf(url)
        s.textSize = 12f
        s.setTextColor(Fx.dim(this))
        s.maxLines = 1
        col.addView(s, LinearLayout.LayoutParams(-1, -2))
        val clp = LinearLayout.LayoutParams(0, -2, 1f)
        clp.leftMargin = Fx.dp(this, 12)
        row.addView(col, clp)
        val chip = Ui.valueChip(this, "restore")
        row.addView(chip, LinearLayout.LayoutParams(-2, -2))
        MenuCards.explain(row, this, "Closed tab",
                "Closed $title — restoring reopens it as it was (URL + position).")
        row.setOnClickListener {
            Ui.tap(it)
            val u = TabTrash.restore(this, idx)
            if (u.isNotEmpty()) openAll(listOf(u)) else rebuild()
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Fx.dp(this, 6)
        row.layoutParams = lp
        return row
    }
}
