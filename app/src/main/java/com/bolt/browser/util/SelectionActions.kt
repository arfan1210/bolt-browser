package com.bolt.browser.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import org.json.JSONTokener

/**
 * v1.3.38 — SELECTION BUBBLE ACTIONS (menu item 10 of the round-2
 * modernization menu; new code → Kotlin per the README language rule).
 *
 * Wraps the WebView's own text-selection ActionMode callback (BoltWeb hands
 * it over) and APPENDS our items to the floating menu — the system's
 * Cut/Copy/Share rows are populated by the ORIGINAL callback and stay
 * untouched:
 *  - SEARCH — the selection straight into the omnibox flow (loadInTab
 *    resolves URL-vs-query + the picked engine)
 *  - TRANSLATE — the selection through the page-translation flow
 *  - COPY — clipboard (the quick path; the system's copy stays too)
 *  - SHOW QR — the selection as a scannable QR (beam text to another phone)
 *
 * The selection text is captured when the menu opens, and re-captured
 * (throttled) every time the menu re-prepares as the user edits the
 * selection; every action finishes the ActionMode so the menu never
 * lingers — except a Copy that is still waiting for its text (see below).
 *
 * v1.3.56 — THE GEMINI COPY FIX ("Gemini replies take too long to copy —
 * many minutes, if it copies at all"). Three real defects, all in this
 * file's capture model:
 *  1. ONE-SHOT ASYNC CAPTURE WITH A SILENT NO-OP GAP. The selection was
 *     read once, asynchronously, when the menu opened, on the assumption
 *     the callback lands "long before a human can tap". On a heavy page
 *     under memory pressure (Gemini on a low-RAM device — the field log's
 *     trimMemory level=15/80 lines), the evaluateJavascript round-trip can
 *     take MINUTES, and Copy tapped inside the gap did nothing at all —
 *     no clipboard write, no toast, nothing ("if it copies at all").
 *     Fix: capture at open AND at every throttled onPrepareActionMode, and
 *     when Copy is tapped with no text in hand, show "Copying…" and force
 *     a fresh capture that completes the clipboard write in its callback.
 *     The ActionMode is finished only AFTER the text is real — finishing
 *     early would clear the very selection the capture needs to read; on
 *     failure the menu stays open with an honest hint, so the system's
 *     own Copy row (still there, untouched) is one tap away.
 *  2. SHADOW-DOM BLINDNESS. window.getSelection().toString() returns ''
 *     for text inside shadow DOM, and Gemini is a web-components app —
 *     some replies could NEVER be captured. The capture now falls back to
 *     Selection.getComposedRanges() (Chromium 128+) and to the focused
 *     element's shadowRoot.getSelection() chain.
 *  3. LITERAL-ESCAPE CLIPBOARD. evaluateJavascript hands back a
 *     JSON-ENCODED string; the old unquote (trim + removeSurrounding("\""))
 *     left the escapes as literal text, so every multi-line reply copied
 *     with literal \n sequences in it. JSONTokener now decodes properly.
 */
object SelectionActions {

    // v1.3.79 — THE POSITION TRUTH: the floating ActionMode asks its callback
    // for the toolbar position through ActionMode.Callback2.onGetContentRect,
    // and a wrapper implementing only the plain Callback interface ERASED the
    // Callback2-ness — the toolbar fell back to the window's top edge ("the
    // copy paste select all pop up box still shows high up on top of the top
    // search bar"). Callback below is a Callback2 that DELEGATES the rect to
    // the original (Chromium's) callback, so the menu lands at the selection.

    private const val GROUP = 0xB017
    private const val ID_SEARCH = 1
    private const val ID_TRANSLATE = 2
    private const val ID_COPY = 3
    private const val ID_QR = 4

    /** capture re-arm interval for onPrepareActionMode (the user dragging
     *  selection handles fires it constantly; a query per fire would pile
     *  up on exactly the busy pages this fix exists for) */
    private const val RECAPTURE_MS = 300L

    /** the shadow-DOM-aware selection reader. Stage 1 is the classic
     *  window.getSelection().toString(); stage 2 crosses shadow boundaries
     *  via getComposedRanges() (each StaticRange is re-based onto a live
     *  Range so cloneContents works); stage 3 walks the focused element's
     *  open shadow roots. First non-blank result wins. */
    private const val CAPTURE_JS = "(function(){try{" +
        "function cl(s){return (s||'').replace(/\\s+/g,'').length;}" +
        "var s=window.getSelection?window.getSelection():null;" +
        "var t=s?String(s):'';" +
        "if(cl(t))return t;" +
        "if(s&&typeof s.getComposedRanges==='function'){try{" +
        "var rs=s.getComposedRanges();" +
        "if(rs&&rs.length){var d=document.createElement('div');" +
        "for(var i=0;i<rs.length;i++){try{" +
        "var r=document.createRange();" +
        "r.setStart(rs[i].startContainer,rs[i].startOffset);" +
        "r.setEnd(rs[i].endContainer,rs[i].endOffset);" +
        "var c=r.cloneContents();while(c.firstChild)d.appendChild(c.firstChild);" +
        "}catch(e){}}" +
        "var t2=d.textContent||'';if(cl(t2))return t2;}}catch(e){}}" +
        "try{var el=document.activeElement,dp=0;" +
        "while(el&&el.shadowRoot&&dp<30){var sr=el.shadowRoot;" +
        "if(sr.getSelection){var st=String(sr.getSelection());if(cl(st))return st;}" +
        "el=sr.activeElement;dp++;}}catch(e){}" +
        "return t||''}catch(e){return ''}})()"

    /** the bridge into the browser (an interface so this file has no
     *  dependency on BrowserActivity — Incognito reuses it). */
    interface Host {
        fun onSearchSelection(text: String)
        fun onTranslateSelection(text: String)
        fun onBeamSelection(text: String)
    }

    @JvmStatic
    fun wrap(activity: Activity, host: Host, web: WebView): (ActionMode.Callback) -> ActionMode.Callback =
        { orig -> Callback(activity, host, web, orig) }

    private class Callback(
        val a: Activity,
        val host: Host,
        val web: WebView,
        val orig: ActionMode.Callback,
    ) : ActionMode.Callback2() {

        var selection: String? = null
        private var lastCaptureAt = 0L
        private var capturePending = false

        /** evaluateJavascript returns a JSON-encoded value: "null" for null,
         *  a quoted string (with \n, \", \\, \uXXXX escapes) for text. The
         *  v1.3.38 unquote left those escapes as LITERAL characters — every
         *  multi-line copy (any Gemini reply) carried literal \n text. */
        private fun decode(v: String?): String? {
            if (v == null || v == "null") return null
            return try {
                if (v.startsWith("\"")) JSONTokener(v).nextValue() as? String else v
            } catch (ignored: Throwable) { v }
        }

        /** read the selection through the shadow-aware JS. [force] bypasses
         *  the throttle (menu open, Copy tap); [onDone] receives the decoded
         *  text (null if the page never answers). */
        private fun capture(force: Boolean = false, onDone: ((String?) -> Unit)? = null) {
            val now = SystemClock.uptimeMillis()
            if (!force && (capturePending || now - lastCaptureAt < RECAPTURE_MS)) {
                onDone?.invoke(selection)
                return
            }
            lastCaptureAt = now
            capturePending = true
            try {
                web.evaluateJavascript(CAPTURE_JS) { v ->
                    capturePending = false
                    val t = decode(v)
                    if (!t.isNullOrBlank()) selection = t
                    onDone?.invoke(t)
                }
            } catch (ignored: Throwable) {
                capturePending = false
                onDone?.invoke(null)
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // let the WebView's own callback populate cut/copy/share first
            val ok = try {
                orig.onCreateActionMode(mode, menu)
            } catch (ignored: Throwable) {
                true
            }
            capture(force = true)
            try { menu.add(GROUP, ID_SEARCH, Menu.CATEGORY_SYSTEM + 1, "Search") } catch (ignored: Throwable) {}
            try { menu.add(GROUP, ID_TRANSLATE, Menu.CATEGORY_SYSTEM + 2, "Translate") } catch (ignored: Throwable) {}
            try { menu.add(GROUP, ID_COPY, Menu.CATEGORY_SYSTEM + 3, "Copy") } catch (ignored: Throwable) {}
            try { menu.add(GROUP, ID_QR, Menu.CATEGORY_SYSTEM + 4, "Show QR") } catch (ignored: Throwable) {}
            return ok
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val r = try {
                orig.onPrepareActionMode(mode, menu)
            } catch (ignored: Throwable) {
                false
            }
            // v1.3.56: the menu re-prepares as the selection changes —
            // keep the captured text fresh (throttled) so the Copy tap
            // usually finds the text already in hand
            capture()
            return r
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val text = selection
            when (item.itemId) {
                ID_SEARCH -> {
                    if (!text.isNullOrBlank()) host.onSearchSelection(text)
                    mode.finish(); return true
                }
                ID_TRANSLATE -> {
                    if (!text.isNullOrBlank()) host.onTranslateSelection(text)
                    mode.finish(); return true
                }
                ID_COPY -> {
                    if (!text.isNullOrBlank()) {
                        // fast path: the capture already landed
                        copy(text)
                        mode.finish()
                        return true
                    }
                    // v1.3.56 slow path: a heavy page (Gemini under memory
                    // pressure) hasn't answered the capture yet — or the
                    // text hides in shadow DOM. NEVER a silent no-op: say
                    // we're on it, force a fresh read, and finish the copy
                    // when the text is real. The ActionMode must stay
                    // alive until then (finishing it clears the selection
                    // the read depends on); on failure it also stays, so
                    // the SYSTEM copy row is still right there.
                    try {
                        Fx.toast(a, a.getString(com.bolt.browser.R.string.copying))
                    } catch (ignored: Throwable) {}
                    capture(force = true) { t ->
                        if (!t.isNullOrBlank()) {
                            copy(t)
                            try { mode.finish() } catch (ignored: Throwable) {}
                        } else {
                            try {
                                Fx.toast(a, a.getString(
                                        com.bolt.browser.R.string.copy_slow_hint))
                            } catch (ignored: Throwable) {}
                        }
                    }
                    return true
                }
                ID_QR -> {
                    if (!text.isNullOrBlank()) host.onBeamSelection(text)
                    mode.finish(); return true
                }
                else -> {}
            }
            return try {
                orig.onActionItemClicked(mode, item)
            } catch (ignored: Throwable) {
                false
            }
        }

        /** v1.3.79 — THE POSITION FIX: delegate the floating toolbar's
         *  content rect to the ORIGINAL callback (the live selection bounds);
         *  empty/failed fallback keeps the view-bounds default. */
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val o = orig
            if (o is ActionMode.Callback2) {
                try {
                    o.onGetContentRect(mode, view, outRect)
                    if (!outRect.isEmpty) return
                } catch (_: Throwable) {}
            }
            super.onGetContentRect(mode, view, outRect)
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selection = null
            try { orig.onDestroyActionMode(mode) } catch (ignored: Throwable) {}
        }

        private fun copy(text: String) {
            try {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Bolt", text))
                Fx.toast(a, a.getString(com.bolt.browser.R.string.copied))
            } catch (ignored: Throwable) {}
        }
    }
}
