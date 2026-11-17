package com.bolt.browser.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bolt.browser.R
import com.bolt.browser.util.Fx
import com.bolt.browser.util.Prefs
import com.bolt.browser.util.ReaderMode
import com.bolt.browser.util.Ui

/**
 * v1.3.39 — the READER SCREEN (new code → Kotlin).
 *
 * Renders the extracted article HTML in an offline WebView with
 * ReaderMode.css typography. The control bar carries:
 *  - A- / A+      : font size (15..30 px)
 *  - Width        : content column 60..100%
 *  - Serif        : sans/serif toggle
 *  - Light/Dark/Sepia chip
 *  - TEXT-REFLOW ZOOM (menu-3 item 14, "read mode only"): a pinch on the
 *    article changes textZoom — the text scales and the column re-wraps,
 *    exactly the reflow behavior the user described, confined to the
 *    reader so the normal browser keeps its pinch semantics.
 *  - "Open page"  : jump back into the live page.
 *
 * v1.3.40 — READER EXTRAS (round-4 menu item 9):
 *  - estimated reading time in the byline ("≈ 4 min read")
 *  - FIND IN ARTICLE (⌕): a find row under the header using the WebView's
 *    native matcher (count + ▲/▼ step-through + highlight-all)
 *  - HEADING KEYS: ↑/↓ pills over the content jump to the previous/next
 *    h1–h3 ("3/9" position label). JS is enabled for the jump calls — the
 *    article HTML is sanitized first (script/style/iframe blocks and
 *    on*= handlers stripped), so nothing from the source page can run.
 */
class ReaderActivity : AppCompatActivity() {

    private var web: WebView? = null
    private var html = ""
    private var title0 = ""
    private var byline = ""
    private var words = 0
    private var mode = 0            // 0 light 1 dark 2 sepia
    private var fontPx = 18
    private var widthPct = 88
    private var serif = false
    private var zoomPct = 100

    // v1.3.40 find-in-article
    private var findBar: LinearLayout? = null
    private var findField: EditText? = null
    private var findCount: TextView? = null

    // v1.3.40 heading keys
    private var hdgPos: TextView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Fx.theme(this)
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        mode = prefs.i("readerMode", if (prefs.isDark()) 1 else 0)
        fontPx = prefs.i("readerFont", 18)
        widthPct = prefs.i("readerWidth", 88)
        serif = prefs.b("readerSerif", false)
        zoomPct = prefs.i("readerZoom", 100)
        val url = intent?.getStringExtra("url") ?: ""
        val payload = intent?.getStringExtra("json") ?: "{}"
        try {
            val o = org.json.JSONObject(payload)
            html = o.optString("html")
            title0 = o.optString("title")
            byline = o.optString("byline")
            words = o.optInt("words", 0)
        } catch (t: Throwable) { }
        if (words <= 0 && html.isNotEmpty()) {
            // older payloads / saved paragraphs carry no count — estimate
            words = html.replace(Regex("<[^>]+>"), " ")
                    .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        // ---- header ----
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(Fx.dp(this, 8), Fx.dp(this, 6), Fx.dp(this, 8), Fx.dp(this, 6))
        bar.background = com.bolt.browser.util.Ui.sheetTopBg(this)
        val back = ImageButton(this)
        back.setImageResource(R.drawable.ic_back)
        back.background = null
        back.setColorFilter(Fx.tint(this))
        back.setOnClickListener { finish() }
        back.contentDescription = "Back"
        bar.addView(back, LinearLayout.LayoutParams(Fx.dp(this, 42), Fx.dp(this, 42)))
        val tv = TextView(this)
        tv.text = title0.ifEmpty { getString(R.string.reader_view) }
        tv.setTextColor(Fx.txt(this))
        tv.textSize = 16f
        tv.typeface = Ui.medium()
        tv.maxLines = 1
        bar.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(toolButton("A-", "Smaller text") { stepFont(-1) })
        bar.addView(toolButton("A+", "Larger text") { stepFont(1) })
        bar.addView(toolButton("↔", "Column width") { stepWidth() })
        bar.addView(toolButton("Aa", "Serif / sans") { serif = !serif; render() })
        bar.addView(toolButton("◐", "Light / dark / sepia") { mode = (mode + 1) % 3; render() })
        // v1.3.40 — find in article (round-4 #9)
        bar.addView(toolButton("⌕", "Find in article") { toggleFind() })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        // ---- v1.3.40 find row (hidden until ⌕) ----
        findBar = LinearLayout(this)
        findBar!!.orientation = LinearLayout.HORIZONTAL
        findBar!!.gravity = Gravity.CENTER_VERTICAL
        findBar!!.background = com.bolt.browser.util.Ui.sheetTopBg(this)
        findBar!!.setPadding(Fx.dp(this, 10), Fx.dp(this, 4), Fx.dp(this, 4), Fx.dp(this, 4))
        findBar!!.visibility = View.GONE
        val ff = EditText(this)
        ff.hint = "Find in article"
        ff.setTextColor(Fx.txt(this))
        ff.textSize = 15f
        ff.setSingleLine()
        ff.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        ff.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { doFind(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        ff.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                doFindNext(true); true
            } else false
        }
        findField = ff
        findBar!!.addView(ff, LinearLayout.LayoutParams(0, -2, 1f))
        findCount = TextView(this)
        findCount!!.textSize = 13f
        findCount!!.setTextColor(Fx.dim(this))
        findCount!!.setPadding(Fx.dp(this, 6), 0, Fx.dp(this, 6), 0)
        findBar!!.addView(findCount, LinearLayout.LayoutParams(-2, -2))
        findBar!!.addView(navButton("▲") { doFindNext(false) })
        findBar!!.addView(navButton("▼") { doFindNext(true) })
        findBar!!.addView(navButton("✕") { toggleFind() })
        root.addView(findBar, LinearLayout.LayoutParams(-1, -2))

        // ---- article ----
        val w = WebView(this)
        web = w
        // v1.3.40: JS on for the heading-jump helper calls — the rendered
        // HTML is sanitized in render() (scripts/styles/iframes/on*= all
        // stripped), so enabling JS cannot execute anything from the page.
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.setBackgroundColor(0)
        // reflow zoom via pinch: onScaleChange re-applies textZoom
        w.setWebChromeClient(object : android.webkit.WebChromeClient() { })
        // v1.3.40 — native find-in-page results feed the count label
        try {
            w.setFindListener { active, total, _ ->
                findCount?.text = if (total > 0) "${active + 1}/$total" else "0"
            }
        } catch (ignored: Throwable) {}
        // pinch → textZoom (the reflow). The default pinch would zoom the
        // whole layout; intercepting scale changes maps them to type size.
        attachReflow(w)
        root.addView(w, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // "Open page" chip + v1.3.40 heading keys over the content bottom
        val open = Ui.filled(this, getString(R.string.open_page))
        open.setOnClickListener { v ->
            Ui.tap(v)
            if (url.isNotEmpty()) {
                val i = android.content.Intent(this, BrowserActivity::class.java)
                i.data = android.net.Uri.parse(url)
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                try { startActivity(i) } catch (t: Throwable) { }
            }
            finish()
        }
        val chipWrap = LinearLayout(this)
        chipWrap.orientation = LinearLayout.VERTICAL
        chipWrap.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        chipWrap.setPadding(0, 0, 0, Fx.dp(this, 18))
        // v1.3.40 — heading keys (round-4 #9): jump between the article's
        // h1–h3 without leaving the reading flow
        val navRow = LinearLayout(this)
        navRow.orientation = LinearLayout.HORIZONTAL
        navRow.gravity = Gravity.CENTER_HORIZONTAL
        navRow.addView(navButton("↑ prev heading") { jumpHeading(-1) })
        hdgPos = TextView(this)
        hdgPos!!.textSize = 12f
        hdgPos!!.setTextColor(Fx.dim(this))
        hdgPos!!.gravity = Gravity.CENTER
        navRow.addView(hdgPos, LinearLayout.LayoutParams(Fx.dp(this, 44), Fx.dp(this, 34)))
        navRow.addView(navButton("next heading ↓") { jumpHeading(1) })
        chipWrap.addView(navRow, LinearLayout.LayoutParams(-2, -2))
        val openLp = LinearLayout.LayoutParams(-2, Fx.dp(this, 42))
        openLp.topMargin = Fx.dp(this, 8)
        chipWrap.addView(open, openLp)
        addContentView(chipWrap, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT))

        render()
    }

    private lateinit var prefs: Prefs

    private fun toolButton(label: String, hint: String, click: () -> Unit): View {
        val b = TextView(this)
        b.text = label
        b.textSize = 16f
        b.typeface = Ui.medium()
        b.setTextColor(Fx.txt(this))
        b.gravity = Gravity.CENTER
        b.setPadding(Fx.dp(this, 10), Fx.dp(this, 10), Fx.dp(this, 10), Fx.dp(this, 10))
        b.setOnClickListener { click() }
        com.bolt.browser.util.MenuCards.explain(b, this, label + " — reader", hint)
        val lp = LinearLayout.LayoutParams(-2, Fx.dp(this, 42))
        lp.leftMargin = Fx.dp(this, 2)
        b.layoutParams = lp
        return b
    }

    /** small pill button used by the find row and the heading keys. */
    private fun navButton(label: String, click: () -> Unit): View {
        val b = TextView(this)
        b.text = label
        b.textSize = 13f
        b.typeface = Ui.medium()
        b.setTextColor(Fx.txt(this))
        b.gravity = Gravity.CENTER
        b.setPadding(Fx.dp(this, 10), 0, Fx.dp(this, 10), 0)
        try { b.background = com.bolt.browser.util.Ui.rowBg(this) } catch (ignored: Throwable) {}
        b.setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(-2, Fx.dp(this, 34))
        lp.leftMargin = Fx.dp(this, 4)
        b.layoutParams = lp
        return b
    }

    private fun stepFont(d: Int) {
        fontPx = (fontPx + d).coerceIn(14, 32)
        prefs.set("readerFont", fontPx)
        render()
    }

    private fun stepWidth() {
        // cycle 88 → 72 → 100 → 88
        widthPct = when (widthPct) { 88 -> 72; 72 -> 100; else -> 88 }
        prefs.set("readerWidth", widthPct)
        render()
    }

    // ------------------------------------------------------------ v1.3.40 reader extras

    /** show/hide the find row (⌕). Hiding also clears the highlights. */
    private fun toggleFind() {
        val fb = findBar ?: return
        val showing = fb.visibility == View.VISIBLE
        if (showing) {
            fb.visibility = View.GONE
            try { web?.clearMatches() } catch (ignored: Throwable) {}
            findCount?.text = ""
            hideKb()
        } else {
            fb.visibility = View.VISIBLE
            findField?.requestFocus()
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(findField, 0)
            } catch (ignored: Throwable) {}
        }
    }

    private fun doFind(q: String) {
        try {
            if (q.isEmpty()) {
                web?.clearMatches()
                findCount?.text = ""
            } else {
                web?.findAllAsync(q)
            }
        } catch (ignored: Throwable) {}
    }

    private fun doFindNext(down: Boolean) {
        try { web?.findNext(down) } catch (ignored: Throwable) {}
    }

    private fun hideKb() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow((findField ?: return).windowToken, 0)
        } catch (ignored: Throwable) {}
    }

    /** jump to the previous/next h1–h3; the index lives in the page
     *  (window.__bh) so it resets with every re-render. */
    private fun jumpHeading(dir: Int) {
        val w = web ?: return
        w.evaluateJavascript(
                "(function(){var h=document.querySelectorAll('h1,h2,h3');" +
                "if(!h.length)return '0/0';" +
                "if(window.__bh==null)window.__bh=-1;" +
                "window.__bh=Math.min(Math.max(window.__bh+(" + dir + "),0),h.length-1);" +
                "h[window.__bh].scrollIntoView({behavior:'smooth',block:'start'});" +
                "return (window.__bh+1)+'/'+h.length})()") { v ->
            try {
                val s = (v ?: "").replace("\"", "")
                if (s.isNotEmpty() && s != "null") {
                    hdgPos?.text = if (s == "0/0") "—" else s
                }
            } catch (ignored: Throwable) {}
        }
    }

    // ------------------------------------------------------------ render

    private fun render() {
        val w = web ?: return
        prefs.set("readerMode", mode)
        prefs.set("readerSerif", serif)
        prefs.set("readerZoom", zoomPct)
        w.settings.textZoom = zoomPct
        val body = StringBuilder()
        body.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
        body.append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
        body.append("<style>").append(ReaderMode.css(mode == 1, mode == 2, widthPct, fontPx, serif))
                .append("</style></head><body><div class='wrap'>")
        body.append("<h1>").append(esc(title0)).append("</h1>")
        // v1.3.40 — reading-time estimate rides the byline (220 wpm)
        var bl = byline
        if (words > 120) {
            val mins = Math.max(1, Math.round(words / 220.0).toInt())
            bl = if (bl.isEmpty()) "≈ $mins min read" else "$bl · ≈ $mins min read"
        }
        if (bl.isNotEmpty()) body.append("<p class='byline'>").append(esc(bl)).append("</p>")
        body.append(sanitize(html))
        body.append("</div></body></html>")
        w.loadDataWithBaseURL(null, body.toString(), "text/html", "utf-8", null)
        // a re-render resets the article: matches + heading index start over
        try { w.clearMatches() } catch (ignored: Throwable) {}
        findCount?.text = ""
        hdgPos?.text = ""
    }

    /** strip everything executable from the extracted HTML before it is
     *  rendered with JS enabled (v1.3.40 heading keys): script/style/iframe
     *  blocks and inline on*= handlers all go. */
    private fun sanitize(h: String): String {
        return h
                .replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
                .replace(Regex("(?is)<script[^>]*/>"), "")
                .replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
                .replace(Regex("(?is)<iframe[^>]*>.*?</iframe>"), "")
                .replace(Regex("(?is)<iframe[^>]*/?>"), "")
                .replace(Regex("(?is)<object[^>]*>.*?</object>"), "")
                .replace(Regex("(?is)<embed[^>]*>"), "")
                .replace(Regex("(?i)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"), "")
                .replace(Regex("(?i)\\shref\\s*=\\s*\"javascript:[^\"]*\""), " href=\"#\"")
                .replace(Regex("(?i)\\shref\\s*=\\s*'javascript:[^']*'"), " href='#'")
    }

    /**
     * TEXT-REFLOW ZOOM — the reader-only feature (menu-3 item 14): a pinch
     * scales the TEXT (textZoom) and re-wraps the column. Implemented with
     * a scale-gesture detector that consumes the gesture so WebView's
     * layout zoom never engages.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachReflow(w: WebView) {
        val det = android.view.ScaleGestureDetector(this,
                object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                        val z = (zoomPct * detector.scaleFactor).coerceIn(70f, 260f)
                        if (Math.abs(z - zoomPct) >= 1f) {
                            zoomPct = z.toInt()
                            w.settings.textZoom = zoomPct
                            prefs.set("readerZoom", zoomPct)
                        }
                        return true
                    }
                })
        w.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                det.onTouchEvent(e)
                return det.isInProgress
            }
        })
    }

    private fun esc(s: String?): String =
            (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
