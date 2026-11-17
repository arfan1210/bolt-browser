package com.bolt.browser.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bolt.browser.R
import com.bolt.browser.util.Fx
import com.bolt.browser.util.Prefs
import com.bolt.browser.util.QuickActions
import com.bolt.browser.util.Ui

/**
 * v1.3.38 — the QUICK ACTIONS settings screen (new code → Kotlin, per the
 * README language rule). FIELD FIX: "I want the quick actions to be
 * customisable in the settings, it works though" — the remapping UI used to
 * be a two-level nested sheet inside Appearance; it is now a real screen
 * with one row per toolbar button, the CURRENT action as a value chip, and a
 * one-tap remap sheet (per-button allowed lists keep every menu short).
 *
 * Entry points: Appearance → Interaction → "Long-press toolbar buttons",
 * and the Settings search index.
 */
class QuickActionsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var rows: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Fx.theme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generic_list)
        prefs = Prefs.get(this)
        findViewById<TextView>(R.id.tv_g_title).text = getString(R.string.long_press_actions)
        findViewById<View>(R.id.rv_g).visibility = View.GONE
        findViewById<View>(R.id.btn_g_back).post {
            findViewById<android.widget.ImageButton>(R.id.btn_g_back).setColorFilter(Fx.dim(this))
        }
        findViewById<View>(R.id.btn_g_back).setOnClickListener { finish() }

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val pad = Fx.dp(this, 18)
        list.setPadding(pad, pad, pad, pad + Fx.dp(this, 12))
        val scroller = ScrollView(this)
        scroller.setFillViewport(true)
        scroller.overScrollMode = View.OVER_SCROLL_NEVER
        scroller.addView(list, LinearLayout.LayoutParams(-1, -2))
        (findViewById<View>(R.id.btn_g_back).parent.parent as android.view.ViewGroup)
                .addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        val intro = TextView(this)
        intro.text = getString(R.string.long_press_actions_desc)
        intro.textSize = 13f
        intro.setTextColor(Fx.dim(this))
        intro.setPadding(0, 0, 0, Fx.dp(this, 10))
        list.addView(intro, LinearLayout.LayoutParams(-1, -2))

        rows = LinearLayout(this)
        rows!!.orientation = LinearLayout.VERTICAL
        list.addView(rows, LinearLayout.LayoutParams(-1, -2))
        buildRows()

        val reset = Ui.quiet(this, "Reset all to defaults")
        reset.setOnClickListener {
            Ui.tap(it)
            for (b in QuickActions.BUTTONS) prefs.set("lp_$b", QuickActions.defaultFor(b))
            buildRows()
        }
        val rlp = LinearLayout.LayoutParams(-1, Fx.dp(this, 48))
        rlp.topMargin = Fx.dp(this, 16)
        list.addView(reset, rlp)
    }

    private fun buildRows() {
        val host = rows ?: return
        host.removeAllViews()
        for (button in QuickActions.BUTTONS) {
            host.addView(buttonRow(button))
        }
    }

    /** one toolbar button: label + current action chip + chevron; tap = remap. */
    private fun buttonRow(button: String): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Fx.cardBg(this, 0)
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 14), Fx.dp(this, 16), Fx.dp(this, 14))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Fx.dp(this, 4)
        row.layoutParams = lp

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = QuickActions.buttonLabel(this, button)
        title.textSize = 16f
        title.setTextColor(Fx.txt(this))
        col.addView(title, LinearLayout.LayoutParams(-1, -2))
        val sub = TextView(this)
        sub.text = currentLabel(button)
        sub.textSize = 13f
        sub.setTextColor(Fx.dim(this))
        col.addView(sub, LinearLayout.LayoutParams(-1, -2))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        val chev = ImageView(this)
        chev.setImageResource(R.drawable.ic_chevron)
        chev.setColorFilter(Fx.dim(this))
        val clp = LinearLayout.LayoutParams(Fx.dp(this, 18), Fx.dp(this, 18))
        clp.leftMargin = Fx.dp(this, 6)
        row.addView(chev, clp)

        row.setOnClickListener {
            Ui.tap(it)
            val acts = QuickActions.actionsFor(button)
            val cur = prefs.lpAction(button, QuickActions.defaultFor(button))
            var sel = -1
            val labels = ArrayList<String>()
            for (a in acts) {
                labels.add(QuickActions.label(this, a))
                if (a == cur) sel = labels.size - 1
            }
            Ui.choiceSheet(this, QuickActions.buttonLabel(this, button),
                    labels.toTypedArray(), sel) { pick ->
                if (pick in acts.indices) {
                    prefs.set("lp_$button", acts[pick])
                    buildRows()
                }
            }
        }
        return row
    }

    private fun currentLabel(button: String): String {
        val cur = prefs.lpAction(button, QuickActions.defaultFor(button))
        return "Long-press → " + QuickActions.label(this, cur)
    }
}
