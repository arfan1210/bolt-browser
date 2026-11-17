package com.bolt.browser.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bolt.browser.R
import com.bolt.browser.util.Fx
import com.bolt.browser.util.Prefs
import com.bolt.browser.util.Ui
import com.bolt.browser.util.WatchLater

/**
 * v1.3.38 — the WATCH LATER screen (the reading/video queue; new code →
 * Kotlin per the README language rule).
 *
 * Unread items first with a dot marker, read items dimmed. Tap = open in the
 * current tab + mark read; long-press = options (mark read/unread, remove).
 * "Clear read" sweeps the finished tail. Entries: page menu "Watch later",
 * the watch_later quick action, and the settings search index.
 */
class WatchLaterActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var list: LinearLayout? = null
    private var emptyState: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Fx.theme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generic_list)
        prefs = Prefs.get(this)
        findViewById<TextView>(R.id.tv_g_title).text = "Watch later"
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
        scroller.overScrollMode = View.OVER_SCROLL_NEVER
        scroller.addView(root, LinearLayout.LayoutParams(-1, -2))
        (findViewById<View>(R.id.btn_g_back).parent.parent as android.view.ViewGroup)
                .addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        list = LinearLayout(this)
        (list as LinearLayout).orientation = LinearLayout.VERTICAL
        root.addView(list, LinearLayout.LayoutParams(-1, -2))

        emptyState = Ui.emptyState(this, R.drawable.ic_bookmark,
                "Nothing saved for later", "Use the page menu → \"Watch later\"")
        root.addView(emptyState, LinearLayout.LayoutParams(-1, -2))

        val clear = Ui.quiet(this, "Clear read items")
        clear.setOnClickListener {
            Ui.tap(it)
            val n = WatchLater.clearRead(this)
            Fx.toast(this, if (n > 0) "Removed $n read items" else "No read items")
            rebuild()
        }
        val clp = LinearLayout.LayoutParams(-1, Fx.dp(this, 48))
        clp.topMargin = Fx.dp(this, 14)
        root.addView(clear, clp)

        // deep link: WatchLaterActivity can be asked to open a URL straight away
        intent?.dataString?.let { open(it) }
        rebuild()
    }

    private fun rebuild() {
        val host = list ?: return
        host.removeAllViews()
        val items = WatchLater.list(this)
        emptyState?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (m in items) host.addView(itemRow(m))
    }

    private fun itemRow(m: WatchLater.Item): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Fx.cardBg(this, 0)
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 14), Fx.dp(this, 16), Fx.dp(this, 14))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Fx.dp(this, 4)
        row.layoutParams = lp

        // unread dot
        val dot = View(this)
        val g = android.graphics.drawable.GradientDrawable()
        g.shape = android.graphics.drawable.GradientDrawable.OVAL
        g.setColor(if (m.read) 0x00000000 else Fx.accent(this))
        dot.background = g
        row.addView(dot, LinearLayout.LayoutParams(Fx.dp(this, 9), Fx.dp(this, 9)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = m.title.ifBlank { m.url }
        t.textSize = 16f
        t.typeface = Ui.medium()
        t.setTextColor(if (m.read) Fx.dim(this) else Fx.txt(this))
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(t, LinearLayout.LayoutParams(-1, -2))
        val s = TextView(this)
        s.text = Fx.hostOf(m.url) + " · " + ago(m.addedAt)
        s.textSize = 13f
        s.setTextColor(Fx.dim(this))
        s.maxLines = 1
        s.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(s, LinearLayout.LayoutParams(-1, -2))
        val clp2 = LinearLayout.LayoutParams(0, -2, 1f)
        clp2.leftMargin = Fx.dp(this, 12)
        row.addView(col, clp2)

        val chev = android.widget.ImageView(this)
        chev.setImageResource(R.drawable.ic_chevron)
        chev.setColorFilter(Fx.dim(this))
        row.addView(chev, LinearLayout.LayoutParams(Fx.dp(this, 18), Fx.dp(this, 18)))

        row.setOnClickListener { v ->
            Ui.tap(v)
            open(m.url)
        }
        row.setOnLongClickListener {
            Ui.tap(it)
            options(m)
            true
        }
        return row
    }

    private fun options(m: WatchLater.Item) {
        val opts = arrayOf(if (m.read) "Mark as unread" else "Mark as read", "Remove")
        Ui.choiceSheet(this, m.title.ifBlank { m.url }, opts, -1) { which ->
            when (which) {
                0 -> WatchLater.markRead(this, m.url, !m.read)
                1 -> {
                    // v1.4.6 — REMOVE + UNDO (the "an undo for deleting
                    // anything" family): the item is captured with its
                    // fields; Undo re-inserts it exactly (addedAt/read
                    // survive, the sorted view lands it back home).
                    WatchLater.remove(this, m.url)
                    Ui.snackbar(this, getString(R.string.watchlater_removed),
                            getString(R.string.undo), {
                        WatchLater.restore(this, m.url, m.title, m.addedAt, m.read)
                        rebuild()
                    }, Ui.UNDO_MS, null)
                }
            }
            rebuild()
        }
    }

    /** open in the browser and mark read (result contract: data = url). */
    private fun open(url: String) {
        WatchLater.markRead(this, url, true)
        val i = android.content.Intent(this, BrowserActivity::class.java)
        i.data = android.net.Uri.parse(url)
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
    }

    private fun ago(t: Long): String {
        val min = (System.currentTimeMillis() - t) / 60000L
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 60 * 24 -> "${min / 60} h ago"
            else -> "${min / (60 * 24)} d ago"
        }
    }
}
