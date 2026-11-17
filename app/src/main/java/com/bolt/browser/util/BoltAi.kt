package com.bolt.browser.util

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v1.3.75 — BOLT AI, THE BROWSER-WIDE ASSISTANT (new code → Kotlin; the
 * standing language rule).
 *
 * THE USER'S ASK, VERBATIM: "The AI I wanted to be like a browser wide AI,
 * that will be accessible anywhere inside the browser, like in settings,
 * tabs, websites and I will be able to chat with it for help or ask to
 * sumarise the page I am on and stuff like that. Being given that
 * capability, then I want it to be able to translate what I see, including
 * images and stuff. For the AI, I don't need it to be frontier model or
 * anything, just quick plug and play, completely free no hassle."
 *
 * THE SHAPE THAT SHIPPED:
 *  - ONE panel, opened from the main menu (any page, home, anywhere in
 *    the browser surface) AND from the settings home — a chat sheet that
 *    rides Ui.sheetBg (skin-aware) at 92% screen height.
 *  - CHAT for help: free-form multi-turn conversation through
 *    [AiBridge.chat] (Gemini free tier by default, or any
 *    OpenAI-compatible endpoint — the SAME config Translate screen's AI
 *    row uses; the gear re-opens it, so the "inputs" are editable any
 *    time — the v1.3.74 complaint).
 *  - SUMMARIZE THIS PAGE: title + meta description + the page's visible
 *    innerText (capped 12,000 chars — [PAGE_JS]) goes to the model with a
 *    summarize instruction. innerText is visibility-aware; shadow-DOM
 *    text is a known miss (registered as an edge).
 *  - EXPLAIN SELECTION: the current DOM selection ([SEL_JS], capped
 *    6,000 chars) — "what does this mean?" without leaving the page.
 *  - TRANSLATE THIS SCREEN: hands off to ScreenTranslate's AI script —
 *    the proven overlay with translated boxes over the capture; the
 *    panel closes so the capture sees the page clean.
 *  - FREE NO HASSLE: a free Gemini AI Studio key (one paste, no billing,
 *    works on de-Googled devices). Pollinations' keyless endpoint was
 *    re-checked 2026-11-08 and is now PAYWALLED (anonymous generation
 *    removed) — no reliable keyless text/vision endpoint exists, so the
 *    free key stays the lowest-friction honest answer.
 *
 * Privacy posture: only what the user asks about leaves the device —
 * typed chat text, or the page's text / the selection after a
 * once-per-process confirm naming the provider. The key never rides a
 * diag line or toast. History is in-memory only (dies with the process).
 */
object BoltAi {

    /** one worker — chat requests are serial by nature (and rate-limit kind) */
    private val POOL: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bolt-ai").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** in-memory chat history — survives panel close, dies with the process */
    private val history = ArrayList<AiBridge.Msg>()

    /** page-content sends confirm ONCE per process (same law as the vision confirm) */
    @Volatile private var pageConfirmed = false

    /** the live panel */
    private var dialog: Dialog? = null
    private var list: LinearLayout? = null
    private var input: EditText? = null
    private var provLine: TextView? = null
    @Volatile private var busy = false

    /** turns of context sent per request (older history stays UI-only).
     *  v1.3.76: 12 → 8 — the free tier meters INPUT tokens, and a leaner
     *  window is the cheapest latency/rate-limit win there is. */
    private const val MAX_TURNS = 8

    // ---------------- the page extractors (evaluated in the LIVE page) ----------------

    /** title + meta description + visible innerText, capped for the model */
    const val PAGE_JS = """
(function(){try{
  var t=document.title||'';
  var d='';
  try{var m=document.querySelector('meta[name=description]');if(m)d=m.content||''}catch(e){}
  var b=(document.body?document.body.innerText:'')||'';
  b=b.replace(/[ \t]+/g,' ').replace(/\n{3,}/g,'\n\n').trim();
  return JSON.stringify({t:t.slice(0,200),d:d.slice(0,400),x:b.slice(0,12000)});
}catch(e){return null}})()
"""

    /** the current DOM selection, capped ("" when nothing is selected) */
    const val SEL_JS = """
(function(){try{
  var s=window.getSelection?window.getSelection().toString():'';
  return s?s.slice(0,6000):'';
}catch(e){return ''}})()
"""

    /** evaluateJavascript returns a JSON-encoded value — decode honestly
     *  (the quote-strip shortcut breaks on escaped quotes inside page text) */
    private fun decodeJs(value: String?): String? {
        if (value == null || value == "null") return null
        return try {
            val arr = JSONArray("[" + value + "]")
            if (arr.length() == 1) arr.optString(0, "") else null
        } catch (_: Throwable) { value }
    }

    // ---------------- the panel ----------------

    /**
     * Opens the Bolt AI sheet. [web] is the current tab's WebView (null
     * from surfaces that have no page — settings; the page chips explain
     * themselves). [openUrl] opens links in a NEW TAB when the caller can
     * (BrowserActivity — an [AiBridge.LinkOpener]); null → the key link is
     * copied to the clipboard.
     */
    @JvmStatic
    fun open(a: Activity, web: WebView?, openUrl: AiBridge.LinkOpener?) {
        try { Diag.log(a, "ai open") } catch (_: Throwable) {}
        dismiss()
        val pad = Fx.dp(a, 16).toInt()

        // ---- header ----
        val head = LinearLayout(a)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(pad, Fx.dp(a, 14).toInt(), pad, Fx.dp(a, 6).toInt())

        val title = TextView(a)
        title.text = "\u26A1 Bolt AI"
        title.textSize = 17f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Fx.txt(a))
        head.addView(title, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val prov = TextView(a)
        prov.textSize = 12f
        prov.setTextColor(Fx.dim(a))
        provLine = prov
        head.addView(prov, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun headBtn(label: String, click: (View) -> Unit): TextView {
            val b = TextView(a)
            b.text = label
            b.textSize = 15f
            b.setTextColor(Fx.dim(a))
            b.setPadding(Fx.dp(a, 10).toInt(), Fx.dp(a, 6).toInt(),
                Fx.dp(a, 10).toInt(), Fx.dp(a, 6).toInt())
            b.setOnClickListener(click)
            head.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return b
        }
        headBtn("\u2699") { openConfig(a, web, openUrl) }
        headBtn("\u2715") { dismiss() }

        // ---- quick-action chips ----
        val chipsRow = HorizontalScrollView(a)
        chipsRow.isHorizontalScrollBarEnabled = false
        val chips = LinearLayout(a)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(pad, 0, pad, Fx.dp(a, 6).toInt())
        chipsRow.addView(chips)

        fun chip(label: String, click: (View) -> Unit): TextView {
            val t = TextView(a)
            t.text = label
            t.textSize = 12.5f
            t.setTextColor(Fx.txt(a))
            t.setPadding(Fx.dp(a, 12).toInt(), Fx.dp(a, 8).toInt(),
                Fx.dp(a, 12).toInt(), Fx.dp(a, 8).toInt())
            val bg = GradientDrawable()
            bg.cornerRadius = Fx.dp(a, 18).toFloat()
            bg.setColor(Fx.surface2(a))
            t.background = bg
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = Fx.dp(a, 8).toInt()
            t.layoutParams = lp
            t.setOnClickListener(click)
            chips.addView(t)
            return t
        }
        chip("Summarize this page") { summarize(a, web, openUrl) }
        chip("Explain selection") { explainSelection(a, web, openUrl) }
        chip("Translate this screen") {
            if (web == null) { Fx.toast(a, "Open a page first"); return@chip }
            dismiss()
            try { ScreenTranslate.startWithScript(a, web, "ai", openUrl) }
            catch (t: Throwable) { Fx.toast(a, "Screen translation failed") }
        }
        chip("Clear") {
            history.clear()
            renderAll(a)
            Fx.toast(a, "Chat cleared")
        }

        // ---- the message list ----
        val scroller = ScrollView(a)
        val msgs = LinearLayout(a)
        msgs.orientation = LinearLayout.VERTICAL
        msgs.setPadding(pad, Fx.dp(a, 4).toInt(), pad, Fx.dp(a, 8).toInt())
        scroller.addView(msgs)
        list = msgs

        // ---- the input row ----
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(pad, Fx.dp(a, 4).toInt(), pad, pad)

        val et = EditText(a)
        et.hint = "Ask Bolt AI anything\u2026"
        et.textSize = 14.5f
        et.setTextColor(Fx.txt(a))
        et.setHintTextColor(Fx.dim(a))
        et.background = null
        et.maxLines = 5
        et.imeOptions = EditorInfo.IME_ACTION_SEND
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped(a, web, openUrl); true
            } else false
        }
        row.addView(et, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        input = et

        val send = TextView(a)
        send.text = "\u27A4"
        send.textSize = 18f
        send.setTextColor(Color.WHITE)
        val sbg = GradientDrawable()
        sbg.cornerRadius = Fx.dp(a, 22).toFloat()
        sbg.setColor(Fx.accent(a))
        send.background = sbg
        send.setPadding(Fx.dp(a, 14).toInt(), Fx.dp(a, 10).toInt(),
            Fx.dp(a, 14).toInt(), Fx.dp(a, 10).toInt())
        send.setOnClickListener { sendTyped(a, web, openUrl) }
        row.addView(send)

        // ---- assemble ----
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        body.addView(head)
        body.addView(chipsRow)
        body.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(row)

        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(body)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val dm = a.resources.displayMetrics
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (dm.heightPixels * 0.92f).toInt())
            w.setGravity(Gravity.BOTTOM)
        }
        body.background = Ui.sheetBg(a)
        d.setOnDismissListener {
            if (dialog === d) { dialog = null; list = null; input = null; provLine = null }
        }
        d.show()
        dialog = d

        renderAll(a)
        // a fresh panel opens on the keyboard-ready field when empty
        if (history.isEmpty()) try { et.requestFocus() } catch (_: Throwable) {}
    }

    @JvmStatic
    fun dismiss() {
        val d = dialog ?: return
        dialog = null
        list = null
        input = null
        provLine = null
        try { d.dismiss() } catch (_: Throwable) {}
    }

    /** the config gear + the setup card's button — editable ANY time */
    private fun openConfig(a: Activity, web: WebView?, openUrl: AiBridge.LinkOpener?) {
        val opener: (String) -> Unit = if (openUrl != null) {
            { url -> try { openUrl.open(url) } catch (_: Throwable) {} }
        } else { { url ->
            try {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Bolt AI key link", url))
                Toast.makeText(a, "Link copied — open it in Bolt, then paste the key here",
                    Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
                Toast.makeText(a, "Get a free key at aistudio.google.com/apikey",
                    Toast.LENGTH_LONG).show()
            }
        } }
        AiBridge.configDialog(a, opener) { refreshHeader(a) }
    }

    private fun refreshHeader(a: Activity) {
        try {
            provLine?.text = if (AiBridge.configured(a)) AiBridge.providerName(a) else "Not set up"
        } catch (_: Throwable) {}
    }

    // ---------------- rendering ----------------

    /** re-renders the whole conversation (open + Clear) */
    private fun renderAll(a: Activity) {
        val l = list ?: return
        l.removeAllViews()
        if (!AiBridge.configured(a)) addSetupCard(a)
        if (history.isEmpty()) {
            addBubble(a, "Hi — I'm Bolt AI. Ask me anything, or use the chips " +
                    "above to summarize the page you're on, explain a selection, " +
                    "or translate what you see (manga and images included). " +
                    "Long-press any message to copy it.", false)
            return
        }
        for (m in history) addBubble(a, m.text, m.role == "user")
        scrollDown()
    }

    /** the one-paste setup card shown when no key is configured */
    private fun addSetupCard(a: Activity) {
        val l = list ?: return
        val card = TextView(a)
        card.text = "Set up Bolt AI — one paste, free:\n" +
                "a FREE key from Google AI Studio (your Google account, no billing) " +
                "or any OpenAI-compatible endpoint. The key stays on this device.\n" +
                "Tap \u2699 (or here) to configure."
        card.textSize = 13f
        card.setTextColor(Fx.txt(a))
        val bg = GradientDrawable()
        bg.cornerRadius = Fx.dp(a, 16).toFloat()
        bg.setColor(Fx.accentSoft(a))
        bg.setStroke(Math.max(1, Fx.dp(a, 1)), Fx.accent(a))
        card.background = bg
        val pad = Fx.dp(a, 12).toInt()
        card.setPadding(pad, pad, pad, pad)
        card.setOnClickListener { openConfig(a, null, null) }
        l.addView(card)
        val sp = View(a)
        sp.layoutParams = LinearLayout.LayoutParams(1, Fx.dp(a, 8).toInt())
        l.addView(sp)
    }

    /** one chat bubble — user right/accent, AI left/surface. v1.3.76:
     *  SINGLE-TAP COPY (the field ask: "an option to like copy the prompts
     *  and replies with a single tap"). v1.3.92 — LONG-HOLD COPY (the
     *  v1.3.91 report: "I want Bolt AI's message to be copied by long hold
     *  instead of tap as It will cause accidental copying a lot") — a tap
     *  on a bubble fired mid-scroll or mid-read and the clipboard silently
     *  changed; the copy gesture is now a deliberate ~500 ms hold, with a
     *  haptic tick at the moment it lands. */
    private fun addBubble(a: Activity, text: String, user: Boolean): TextView {
        val l = list ?: return TextView(a)
        val t = TextView(a)
        t.text = text
        t.textSize = 14.5f
        t.setTextColor(if (user) Color.WHITE else Fx.txt(a))
        val bg = GradientDrawable()
        bg.cornerRadius = Fx.dp(a, 16).toFloat()
        if (user) {
            bg.setColor(Fx.accent(a))
            bg.cornerRadii = floatArrayOf(
                Fx.dp(a, 16).toFloat(), Fx.dp(a, 16).toFloat(),
                Fx.dp(a, 4).toFloat(), Fx.dp(a, 4).toFloat(),
                Fx.dp(a, 16).toFloat(), Fx.dp(a, 16).toFloat(),
                Fx.dp(a, 16).toFloat(), Fx.dp(a, 16).toFloat())
        } else {
            bg.setColor(Fx.surface2(a))
        }
        t.background = bg
        val pad = Fx.dp(a, 12).toInt()
        t.setPadding(pad, pad, pad, pad)
        val lp = LinearLayout.LayoutParams(
            (a.resources.displayMetrics.widthPixels * 0.82f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = Fx.dp(a, 8).toInt()
        t.layoutParams = lp
        // v1.3.92: long-press -> this bubble's text goes to the clipboard
        t.setOnLongClickListener {
            try {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Bolt AI", t.text.toString()))
                try { com.bolt.browser.util.Ui.tap(t) } catch (_: Throwable) {}
                Fx.toast(a, "Copied")
            } catch (_: Throwable) {
                Fx.toast(a, "Copy failed")
            }
            true
        }
        l.addView(t)
        return t
    }

    private fun scrollDown() {
        try {
            val l = list ?: return
            l.post { (l.parent as? ScrollView)?.smoothScrollTo(0, l.bottom) }
        } catch (_: Throwable) {}
    }

    // ---------------- the actions ----------------

    /** typed chat: history append + the thinking bubble + the request */
    private fun sendTyped(a: Activity, web: WebView?, openUrl: AiBridge.LinkOpener?) {
        if (busy) { Fx.toast(a, "Still thinking\u2026"); return }
        val et = input ?: return
        val text = et.text.toString().trim()
        if (text.isEmpty()) return
        if (!AiBridge.configured(a)) {
            Fx.toast(a, "Set up Bolt AI first — one free key")
            openConfig(a, web, openUrl)
            return
        }
        et.setText("")
        ask(a, text)
    }

    /** the shared request path — [userText] is already final */
    private fun ask(a: Activity, userText: String) {
        history.add(AiBridge.Msg("user", userText))
        addBubble(a, userText, true)
        val thinking = addBubble(a, "\u2026", false)
        scrollDown()
        busy = true
        val t0 = System.currentTimeMillis()
        POOL.execute {
            val turns = if (history.size > MAX_TURNS)
                history.subList(history.size - MAX_TURNS, history.size).toList() else history.toList()
            val reply = AiBridge.chat(a, SYSTEM, turns)
            main.post {
                busy = false
                try {
                    if (reply == null) {
                        try { Diag.log(a, "ai fail " + AiBridge.lastError.take(60)) } catch (_: Throwable) {}
                        thinking.text = "\u26A0 " + AiBridge.lastError
                        thinking.setTextColor(0xFFB33939.toInt())
                    } else {
                        try {
                            Diag.log(a, "ai ok ms=" + (System.currentTimeMillis() - t0) +
                                    " chars=" + reply.length)
                        } catch (_: Throwable) {}
                        history.add(AiBridge.Msg("assistant", reply))
                        thinking.text = reply
                        thinking.setTextColor(Fx.txt(a))
                    }
                } catch (_: Throwable) {}
                scrollDown()
            }
        }
    }

    /** the system prompt — Bolt's standing instruction */
    private val SYSTEM: String
        get() = "You are Bolt AI, the built-in assistant of Bolt Light Browser " +
                "(a fast, private Android browser). Be helpful, accurate and " +
                "concise; prefer short structured answers over walls of text. " +
                "When the user shares page content, ground your answer in it " +
                "and say when it is not enough. Reply in " +
                langName(PageTranslate.deviceTl()) + "."

    private fun langName(tl: String): String = when (tl) {
        "en" -> "English"; "bn" -> "Bengali"; "hi" -> "Hindi"; "ar" -> "Arabic"
        "ur" -> "Urdu"; "ru" -> "Russian"; "zh-CN" -> "Simplified Chinese"
        "zh-TW" -> "Traditional Chinese"; "ja" -> "Japanese"; "ko" -> "Korean"
        "fr" -> "French"; "de" -> "German"; "es" -> "Spanish"; "pt" -> "Portuguese"
        "it" -> "Italian"; "id" -> "Indonesian"; "tr" -> "Turkish"; "vi" -> "Vietnamese"
        "th" -> "Thai"
        else -> tl
    }

    /** SUMMARIZE THIS PAGE — extract, confirm once, ask */
    private fun summarize(a: Activity, web: WebView?, openUrl: AiBridge.LinkOpener?) {
        if (busy) { Fx.toast(a, "Still thinking\u2026"); return }
        if (web == null) { Fx.toast(a, "Open a page first"); return }
        if (!AiBridge.configured(a)) {
            Fx.toast(a, "Set up Bolt AI first — one free key")
            openConfig(a, web, openUrl)
            return
        }
        web.evaluateJavascript(PAGE_JS) { value ->
            val json = decodeJs(value)
            var title = ""; var desc = ""; var text = ""
            if (json != null) {
                try {
                    val o = JSONObject(json)
                    title = o.optString("t", ""); desc = o.optString("d", ""); text = o.optString("x", "")
                } catch (_: Throwable) {}
            }
            if (text.isBlank()) {
                Fx.toast(a, "No readable text on this page")
                return@evaluateJavascript
            }
            try { Diag.log(a, "ai page chars=" + text.length) } catch (_: Throwable) {}
            val go = {
                val msg = "Summarize this page for me — the key points in a few " +
                        "bullets, then one line on what the page is for.\n\n" +
                        "TITLE: " + title + "\n" +
                        "URL: " + (web.url ?: "") + "\n" +
                        (if (desc.isNotBlank()) "DESCRIPTION: " + desc + "\n" else "") +
                        "\nPAGE TEXT:\n" + text
                ask(a, msg)
            }
            if (pageConfirmed) go()
            else {
                try { Diag.log(a, "ai confirm page") } catch (_: Throwable) {}
                android.app.AlertDialog.Builder(a)
                    .setTitle("Send this page to Bolt AI?")
                    .setMessage("The page's text (title, address and visible " +
                            "content — up to 12,000 characters) is sent to " +
                            AiBridge.providerName(a) + ". Everything else in " +
                            "Bolt stays on-device. This confirm appears once " +
                            "per app run.")
                    .setPositiveButton("Send") { d, _ ->
                        try { d.dismiss() } catch (_: Throwable) {}
                        pageConfirmed = true
                        go()
                    }
                    .setNegativeButton(com.bolt.browser.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** EXPLAIN SELECTION — the current DOM selection, no page dump */
    private fun explainSelection(a: Activity, web: WebView?, openUrl: AiBridge.LinkOpener?) {
        if (busy) { Fx.toast(a, "Still thinking\u2026"); return }
        if (web == null) { Fx.toast(a, "Open a page first"); return }
        if (!AiBridge.configured(a)) {
            Fx.toast(a, "Set up Bolt AI first — one free key")
            openConfig(a, web, openUrl)
            return
        }
        web.evaluateJavascript(SEL_JS) { value ->
            val sel = decodeJs(value) ?: ""
            if (sel.isBlank()) {
                Fx.toast(a, "Long-press to select text on the page first")
                return@evaluateJavascript
            }
            try { Diag.log(a, "ai sel chars=" + sel.length) } catch (_: Throwable) {}
            val go = {
                val msg = "I selected this text on the page I'm reading — explain " +
                        "what it means, in context and in plain words:\n\n\"" + sel + "\""
                ask(a, msg)
            }
            if (pageConfirmed) go()
            else {
                try { Diag.log(a, "ai confirm sel") } catch (_: Throwable) {}
                android.app.AlertDialog.Builder(a)
                    .setTitle("Send the selection to Bolt AI?")
                    .setMessage("Only the selected text (up to 6,000 characters) " +
                            "is sent to " + AiBridge.providerName(a) + ". " +
                            "Everything else in Bolt stays on-device. This " +
                            "confirm appears once per app run.")
                    .setPositiveButton("Send") { d, _ ->
                        try { d.dismiss() } catch (_: Throwable) {}
                        pageConfirmed = true
                        go()
                    }
                    .setNegativeButton(com.bolt.browser.R.string.cancel, null)
                    .show()
            }
        }
    }
}
