package com.bolt.browser.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bolt.browser.R
import com.bolt.browser.util.Engines
import com.bolt.browser.util.Fx
import com.bolt.browser.util.MenuCards
import com.bolt.browser.util.Prefs
import com.bolt.browser.util.Ui

/**
 * v1.3.39 — CUSTOM SEARCH ENGINES (new code → Kotlin; menu-3 item 6).
 * Reached from Deep settings → General → "Custom search engines".
 * Lists the engines with their !bang chips, adds via a small form
 * (name / URL template with %s / bang keyword), deletes on long-press.
 */
class EnginesActivity : AppCompatActivity() {

    private var list: LinearLayout? = null
    private var emptyState: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Fx.theme(this)
        super.onCreate(savedInstanceState)
        // v1.3.40 — presets ride along once (never over a curated list)
        try { Engines.seedPresets(this) } catch (ignored: Throwable) {}
        setContentView(R.layout.activity_generic_list)
        findViewById<TextView>(R.id.tv_g_title).text = "Custom search engines"
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

        root.addView(Ui.sectionLabel(this,
                "Type the bang in the address bar: \"!w cats\" searches with the engine"))

        list = LinearLayout(this)
        (list as LinearLayout).orientation = LinearLayout.VERTICAL
        root.addView(list, LinearLayout.LayoutParams(-1, -2))

        emptyState = Ui.emptyState(this, R.drawable.ic_search,
                "No custom engines", "Add one below — e.g. Wikipedia")
        root.addView(emptyState, LinearLayout.LayoutParams(-1, -2))

        val add = Ui.filled(this, "Add engine")
        add.setOnClickListener { Ui.tap(it); showAdd() }
        val alp = LinearLayout.LayoutParams(-1, Fx.dp(this, 48))
        alp.topMargin = Fx.dp(this, 14)
        root.addView(add, alp)
        rebuild()
    }

    private fun rebuild() {
        val host = list ?: return
        host.removeAllViews()
        val es = Engines.list(this)
        emptyState?.visibility = if (es.isEmpty()) View.VISIBLE else View.GONE
        for ((i, e) in es.withIndex()) host.addView(engineRow(e, i))
    }

    private fun engineRow(e: Engines.Engine, idx: Int): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Fx.cardBg(this, 0)
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 13), Fx.dp(this, 16), Fx.dp(this, 13))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = e.name
        t.textSize = 16f
        t.typeface = Ui.medium()
        t.setTextColor(Fx.txt(this))
        col.addView(t, LinearLayout.LayoutParams(-1, -2))
        val s = TextView(this)
        s.text = e.url
        s.textSize = 12f
        s.setTextColor(Fx.dim(this))
        s.maxLines = 1
        s.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        col.addView(s, LinearLayout.LayoutParams(-1, -2))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (e.bang.isNotEmpty()) {
            val chip = Ui.valueChip(this, "!" + e.bang)
            row.addView(chip, LinearLayout.LayoutParams(-2, -2))
        }
        MenuCards.explain(row, this, e.name,
                "Searches with $e.name. In the address bar type \"!${e.bang} <query>\"" +
                        if (e.bang.isEmpty()) " (no bang keyword set — plain name only)" else "")
        row.setOnLongClickListener {
            Ui.tap(it)
            androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage("Remove \"${e.name}\"?")
                    .setPositiveButton(R.string.remove) { _, _ ->
                        Engines.removeAt(this, idx); rebuild()
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            true
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Fx.dp(this, 6)
        row.layoutParams = lp
        return row
    }

    private fun showAdd() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = Fx.dp(this, 20)
        col.setPadding(pad, Fx.dp(this, 10), pad, Fx.dp(this, 4))
        val name = EditText(this)
        name.hint = "Name (Wikipedia)"
        col.addView(name, LinearLayout.LayoutParams(-1, -2))
        val url = EditText(this)
        url.hint = "URL with %s — https://en.m.wikipedia.org/w/index.php?search=%s"
        col.addView(url, LinearLayout.LayoutParams(-1, -2))
        val bang = EditText(this)
        bang.hint = "Bang keyword (w) — optional"
        col.addView(bang, LinearLayout.LayoutParams(-1, -2))
        androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Add search engine")
                .setView(col)
                .setPositiveButton(R.string.add) { _, _ ->
                    val err = Engines.add(this, name.text.toString(), url.text.toString(),
                            bang.text.toString())
                    if (err != null) Fx.toast(this, err)
                    rebuild()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }
}
