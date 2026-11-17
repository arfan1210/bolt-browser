package com.bolt.browser.ui

import android.content.Context
import android.view.ActionMode
import android.webkit.WebView

/**
 * v1.3.38 — the content WebView as its own class (menu item 10's selection
 * bubble actions; new code → Kotlin per the README language rule).
 *
 * The ONLY reason this class exists: text-selection ActionModes are started
 * BY the WebView through View.startActionMode — overriding it here is the
 * one interception point where our selection-action items can join the
 * system's floating menu (Search / Translate / Copy / QR beam, via
 * SelectionActions). Every Tab's WebView is built through Tab.buildWeb,
 * which instantiates BoltWeb instead of plain WebView — no other
 * behavior changes.
 *
 * v1.3.39 — LINK PREVIEW CARDS (menu-3 #4) ride the browser's existing
 * long-press hit-test handler (BrowserActivity.handleLongPress routes
 * SRC_ANCHOR presses to util/LinkPreview); no extra touch tracking here —
 * one long-press, one popup.
 *
 * v1.3.78 — THE FRESH-RECT GUARANTEE (the field report: "when I select
 * something like text by holding, the cut, copy, select all menu for some
 * reason appear at the very top of the screen, far from the text I
 * selected"). The FloatingActionMode positions its toolbar from the
 * selection rect Chromium reports at creation time — and after the
 * keyboard/inset resize churn a heavy session produces, that first rect
 * can be stale (mapped against a viewport the renderer has since
 * re-laid-out), which clamps the toolbar to the window's top. The
 * ActionMode's own invalidateContentRect() re-asks the callback (and
 * repositions the popup against the CURRENT view geometry); posting it
 * twice after start lets the correct rect land once the selection has
 * settled. Harmless when the first rect was already right — the rect
 * just re-reports identical.
 */
open class BoltWeb(context: Context) : WebView(context) {

    /** set by the browser; wraps every selection ActionMode callback. */
    @JvmField var actionModeWrapper: ((ActionMode.Callback) -> ActionMode.Callback)? = null

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? {
        val w = actionModeWrapper
        val mode = if (w != null) super.startActionMode(w.invoke(callback))
                else super.startActionMode(callback)
        freshRect(mode)
        return mode
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        val w = actionModeWrapper
        val mode = if (w != null) super.startActionMode(w.invoke(callback), type)
                else super.startActionMode(callback, type)
        freshRect(mode)
        return mode
    }

    /** v1.3.78 — ask the ActionMode to re-read its content rect once the
     *  selection (and any concurrent layout) has settled. */
    private fun freshRect(mode: ActionMode?) {
        if (mode == null) return
        post { try { mode.invalidateContentRect() } catch (_: Throwable) {} }
        postDelayed({ try { mode.invalidateContentRect() } catch (_: Throwable) {} }, 350)
    }
}
