package com.bolt.browser.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.bolt.browser.core.Tab
import com.bolt.browser.core.TabManager

/**
 * v1.3.38 — TAB SLEEP / FOCUS MODE (menu item 7 of the round-2 modernization
 * menu; new code → Kotlin per the README language rule).
 *
 * Sleeping a tab DESTROYS its WebView (the renderer's RAM goes back to the
 * system — the point on a 3 GB device) while the Tab keeps url + scroll
 * position + its last thumbnail; selecting the tab rebuilds the WebView,
 * reloads the URL, and scrolls back to where you were.
 *
 * Never sleeps: the active tab, home tabs (a plain View, no renderer),
 * tabs holding/pausing media (the notification must keep reaching them).
 *
 * Triggers: automatic after "sleepAfter" minutes idle (Settings → Tabs,
 * default 30), the "Sleep other tabs" quick action / page-menu row (focus
 * mode), and "Sleep tab" in the switcher's per-tab options.
 */
object TabSleep {

    /** idle minutes before a background tab sleeps; 0 = automatic off. */
    @JvmStatic
    fun minutes(c: Context): Int = Prefs.get(c).i("sleepAfter", 30).coerceAtLeast(0)

    @JvmStatic
    fun canSleep(t: Tab): Boolean {
        return !t.isHome && t.web != null && !t.sleeping
                && !t.holdsMedia && !t.mediaPlaying
    }

    /** main-thread only (view detach + capture). Returns true when slept.
     *  v1.4.21 — HISTORY LIVES: the sleep captures the WebView's
     *  saveState() BEFORE the destroy (sleepState for the warm wake,
     *  histBlob for tabs.json) so the rebuilt view restores its
     *  back/forward list instead of loading t.url onto an empty one —
     *  the "back history lost after tab switch" corpse. */
    @JvmStatic
    fun sleep(t: Tab): Boolean {
        if (!canSleep(t)) return false
        val w = t.web ?: return false
        try { t.capture() } catch (ignored: Throwable) {}
        try { t.sleepScrollY = w.scrollY } catch (ignored: Throwable) {}
        try { t.saveHist(w) } catch (ignored: Throwable) {}
        try {
            (w.parent as? ViewGroup)?.removeView(w)
        } catch (ignored: Throwable) {}
        try { w.destroy() } catch (ignored: Throwable) {}
        t.web = null
        t.sleeping = true
        t.mediaPlaying = false
        t.boostable = false
        return true
    }

    /** focus mode: everything except [keepIndex]. Returns how many slept. */
    @JvmStatic
    fun sleepOthers(c: Context, tm: TabManager, keepIndex: Int): Int {
        var n = 0
        // v1.4.9: the thread law — snapshot under the list monitor, sleep
        // (a WebView teardown) outside it
        val snapshot: List<Tab> = synchronized(tm.tabs) { ArrayList(tm.tabs) }
        for (i in snapshot.indices) {
            if (i == keepIndex) continue
            if (sleep(snapshot[i])) n++
        }
        if (n > 0) tm.scheduleSave()
        return n
    }

    /** the automatic pass — run when the browser resumes. */
    @JvmStatic
    fun sweep(c: Context, tm: TabManager, activeIndex: Int) {
        val min = minutes(c)
        if (min <= 0) return
        val cutoff = System.currentTimeMillis() - min * 60_000L
        var n = 0
        // v1.4.9: the thread law — snapshot under the list monitor, sleep
        // (a WebView teardown) outside it
        val snapshot: List<Tab> = synchronized(tm.tabs) { ArrayList(tm.tabs) }
        for (i in snapshot.indices) {
            if (i == activeIndex) continue
            val t = snapshot[i]
            if (t.lastVisitedAt in 1 until cutoff && canSleep(t)) {
                if (sleep(t)) n++
            }
        }
        if (n > 0) tm.scheduleSave()
    }

    /** woken in showTab: the rebuilt WebView loads the url; this restores
     *  the reading position once the page finished. */
    @JvmStatic
    fun restoreScroll(t: Tab, w: View) {
        if (!t.sleeping || t.sleepScrollY <= 0) {
            t.sleeping = false
            return
        }
        val y = t.sleepScrollY
        t.sleeping = false
        t.sleepScrollY = 0
        try {
            w.postDelayed({
                try { (w as? android.webkit.WebView)?.scrollTo(0, y) } catch (ignored: Throwable) {}
            }, 350)
        } catch (ignored: Throwable) {}
    }
}
