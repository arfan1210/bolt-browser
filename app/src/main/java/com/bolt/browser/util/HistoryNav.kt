package com.bolt.browser.util

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebBackForwardList
import android.webkit.WebView
import java.util.WeakHashMap

/**
 * v1.3.84 — THE HISTORY NAVIGATOR (new code → Kotlin; the standing language
 * rule). The field reports, twice now:
 *
 *  - v1.3.80: "browser's back and forward don't load the previous and forward
 *    website, just the links appear but the same page remains."
 *  - v1.3.83: "when I am in facebook post 1, I go to post 2, pressing the
 *    back in toolbar keeps me in post 2 but the search bar shows url of
 *    post 1. The back seems to work mostly if there is a domain change."
 *
 * v1.3.84 shipped the audited step (below) and it WORKED for YouTube — but
 * the v1.3.84 field report came back: "youtube back forward/previous work
 * but facebook just brings to home page". THE POST-MORTEM (v1.3.85):
 *
 * **THE CHURN KILL.** Facebook's SPA rewrites the CURRENT entry's URL
 * constantly — `replaceState` bolt tracking params onto the page you are
 * sitting on (`?wtsid=…`, `?mibextid=…`, `?__cft__[0]=…`, `?rdid=…`).
 * Every rewrite fires doUpdateVisitedHistory with a URL that matches
 * NEITHER the step's target NOR its departure EXACTLY — so v1.3.84's
 * noteNav retired the audit (a "different page" by exact match), or the
 * probe reported `nav drift` (href not exactly the departure URL) instead
 * of the desync it actually was. Either way the force never fired, the
 * renderer stayed on post 2 while the browser-side index had ALREADY moved
 * to post 1's slot — and the user's SECOND back press then stepped from
 * that advanced index: post1's slot → home. "Back just brings to home
 * page" = one dropped step + one double-step.
 *
 * v1.3.85 — THREE TIGHTENINGS, one invariant:
 *
 *  1. **LOOSE PAGE EQUALITY** ([looseSameUrl]): two URLs are the same page
 *     when scheme + host + path match (trailing slash tolerated) and their
 *     queries match after stripping KNOWN TRACKING PARAMS (the Facebook
 *     churn set + generic marketing params, [STRIP_PARAMS]/[STRIP_PREFIX]).
 *     Discriminating params survive: `/watch?v=a` vs `/watch?v=b` are still
 *     different pages, so the audit can never confuse the page's own
 *     re-navigation with churn. The FORCE TARGET IS ALWAYS THE RAW HISTORY
 *     ENTRY URL — loosening only ever decides WHETHER to force, never
 *     where to land.
 *  2. **INDEX-AWARE noteNav**: a doUpdateVisitedHistory/onPageStarted URL
 *     that is not our page only retires the audit when the history index
 *     has LEFT both the departure and the target slot. replaceState churn
 *     keeps the index put — audit stays armed; a pushState (the user's own
 *     SPA tap) or a fresh load moves it — audit retires, never yanks.
 *  3. **STAGE 2 IS FINAL**: a stage-2 drift (renderer somewhere else
 *     entirely — redirect chains) now clears the audit instead of leaving
 *     it pending forever.
 *
 * THE INVARIANT (unchanged): the force only ever fires when the WebView
 * still sits EXACTLY where the step started — index in {from, to} AND the
 * renderer's own location.href still on the departure PAGE (loosely). A
 * user who taps a link, types a URL or pushes state cancels the audit
 * first. Over-cancelling degrades to the unaudited behaviour;
 * under-forcing was the bug.
 *
 * Every step and every force lands in diagnostics (`nav step` / `nav
 * force` / `nav drift`), so the next field report on back/forward carries
 * evidence.
 */
object HistoryNav {

    private val ui = Handler(Looper.getMainLooper())

    private class Pending(
        @Volatile var gen: Long,
        val fromIndex: Int,
        val toIndex: Int,
        val targetUrl: String,
        val fromUrl: String,
    )

    /** per-WebView audit state; dies with the view */
    private val pending = WeakHashMap<WebView, Pending>()

    /** per-WebView monotonic generation counter — a new step retires older audits */
    private val genOf = WeakHashMap<WebView, Long>()

    /**
     * v1.3.85 — the tracking params Facebook and friends bolt onto the SAME
     * page's URL via replaceState. Stripped for COMPARISON ONLY (the force
     * always navigates to the raw history-entry URL). `v`, `id`, `p` and
     * friends are deliberately ABSENT: they discriminate pages.
     */
    private val STRIP_PARAMS = setOf(
            // facebook churn (observed in the field + the SPA's own share links)
            "fbclid", "mibextid", "rdid", "_rdr", "wtsid", "wtsidr",
            "m_entstream_id", "hc_ref", "refid", "notif_id", "notif_t",
            "sfnsn", "locale2", "_fb_noscript", "ref", "referrer",
            // generic marketing / share attribution
            "gclid", "yclid", "msclkid", "igshid", "mc_cid", "mc_eid",
            "si", "feature", "app", "usp", "utm_source", "utm_medium",
            "utm_campaign", "utm_term", "utm_content", "utm_id",
    )

    /** param-name prefixes that are tracking in bulk (__cft__[0], __tn__, _ga…) */
    private val STRIP_PREFIXES = arrayOf(
            "__cft__", "__tn__", "__xts__", "_ga", "utm_", "hsa_", "mkttid_",
    )

    private fun nextGen(w: WebView): Long {
        synchronized(pending) {
            val g = (genOf[w] ?: 0L) + 1L
            genOf[w] = g
            return g
        }
    }

    /**
     * Issue a history step (±1 or any goBackOrForward offset) with the audit
     * armed. Call on the UI thread (all Bolt call sites are button/edge/back
     * handlers). Never throws; a web without history is a no-op.
     */
    @JvmStatic
    fun step(c: Context?, w: WebView?, offset: Int) {
        if (w == null || offset == 0) return
        val hl: WebBackForwardList = try {
            w.copyBackForwardList()
        } catch (e: Throwable) {
            return
        }
        val from = hl.currentIndex
        val to = from + offset
        if (to < 0 || to >= hl.size) return
        val targetUrl = hl.getItemAtIndex(to).url ?: return
        val fromUrl = hl.getItemAtIndex(from).url ?: ""
        val myGen = nextGen(w)
        synchronized(pending) {
            pending[w] = Pending(myGen, from, to, targetUrl, fromUrl)
        }
        try {
            Diag.log(c, "nav step off=$offset idx=$from->$to " +
                    targetUrl.take(90))
        } catch (ignored: Throwable) {}
        try {
            w.goBackOrForward(offset)
        } catch (ignored: Throwable) {
            return
        }
        // stage 1 — the no-op check (same-document commits land well inside)
        ui.postDelayed({
            if (!stillArmed(w, myGen)) return@postDelayed
            audit(c, w, stage = 1)
        }, 1100L)
        // stage 2 — the hang check (nothing legitimate is still provisional)
        ui.postDelayed({
            if (!stillArmed(w, myGen)) return@postDelayed
            audit(c, w, stage = 2)
        }, 4000L)
    }

    /**
     * Observe a navigation event for this WebView (onPageStarted AND
     * doUpdateVisitedHistory — the same-document case never fires
     * onPageStarted). v1.3.85 — INDEX-AWARE: churn-tolerant.
     *
     * Retire the audit ONLY when the URL is a genuinely different page AND
     * the history index has left both the departure slot and the target
     * slot. Facebook's replaceState churn rewrites the CURRENT entry's URL
     * without moving the index — the audit must survive that (the v1.3.84
     * exact-match retirement is what let the desync live long enough to
     * double-step the user into the home page).
     */
    @JvmStatic
    fun noteNav(w: WebView?, url: String?) {
        if (w == null || url == null) return
        synchronized(pending) {
            val p = pending[w] ?: return
            // our own URLs (exact or churned) — churn on the target slot or
            // on the departure slot keeps the audit armed
            if (looseSameUrl(url, p.targetUrl) || looseSameUrl(url, p.fromUrl)) {
                return
            }
            // a different page: only the INDEX tells churn from a real move.
            // replaceState keeps the index on the current entry — armed;
            // a pushState / fresh load leaves {from, to} — retire.
            val idx = try {
                w.copyBackForwardList().currentIndex
            } catch (e: Throwable) {
                -999
            }
            if (idx != p.fromIndex && idx != p.toIndex) {
                p.gen++   // retire: the page moved on without our step
                pending.remove(w)
            }
        }
    }

    private fun stillArmed(w: WebView, gen: Long): Boolean {
        synchronized(pending) {
            val p = pending[w] ?: return false
            return p.gen == gen
        }
    }

    private fun audit(c: Context?, w: WebView, stage: Int) {
        try {
            if (!w.isAttachedToWindow) {   // tab switched away — stand down
                synchronized(pending) { pending.remove(w) }
                return
            }
            val hl = w.copyBackForwardList()
            val now = hl.currentIndex
            var p: Pending? = null
            synchronized(pending) { p = pending[w] }
            val pd = p ?: return
            val busy = try {
                w.progress in 1..99
            } catch (e: Throwable) {
                false
            }
            // stage 1 excuses a live cross-document load; stage 2 does not
            if (now == pd.fromIndex && !busy) {
                force(c, w, pd, "noop s$stage idx=$now")
                return
            }
            // the index moved (or is busy) — ask the RENDERER where it is;
            // evaluateJavascript answers with a JSON-quoted string
            w.evaluateJavascript(
                    "(function(){try{return location.href}catch(e){return ''}})()") { v ->
                try {
                    val href = unquote(v)
                    if (href.isEmpty()) return@evaluateJavascript
                    if (looseSameUrl(href, pd.targetUrl)) {
                        // landed (v1.3.85: even churned — post1?mibextid=…)
                        // — retire the audit, navigation healthy
                        synchronized(pending) { pending.remove(w) }
                        return@evaluateJavascript
                    }
                    if (looseSameUrl(href, pd.fromUrl)) {
                        // browser-side moved but the renderer never did (or
                        // nothing at all moved) — the dropped-step signature.
                        // v1.3.85: LOOSE match — the renderer sitting on the
                        // departure page with churned tracking params is the
                        // SAME signature (the v1.3.84 exact match logged this
                        // as drift and never forced — the facebook hole).
                        force(c, w, pd, "desync s$stage idx=$now")
                        return@evaluateJavascript
                    }
                    // somewhere else entirely (redirect chain, the page's own
                    // re-navigation) — log it, never force over it. Stage 2
                    // is final: stop auditing (v1.3.85 — the pending entry
                    // used to linger forever after a stage-2 drift).
                    Diag.log(c, "nav drift s$stage idx=$now href=" + href.take(90))
                    if (stage >= 2) {
                        synchronized(pending) { pending.remove(w) }
                    }
                } catch (ignored: Throwable) {}
            }
        } catch (ignored: Throwable) {}
    }

    private fun force(c: Context?, w: WebView, p: Pending, why: String) {
        synchronized(pending) { pending.remove(w) }
        try {
            Diag.log(c, "nav force $why -> " + p.targetUrl.take(90))
            // ALWAYS the raw history-entry URL — never a churn-stripped one
            w.loadUrl(p.targetUrl)
        } catch (ignored: Throwable) {}
    }

    private fun unquote(v: String?): String {
        if (v.isNullOrEmpty()) return ""
        var s = v.trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        return s
    }

    /** exact match after trailing-slash + case-irrelevant-fragment trim */
    private fun sameUrl(a: String, b: String): Boolean {
        if (a == b) return true
        val x = a.trimEnd('/')
        val y = b.trimEnd('/')
        return x.isNotEmpty() && x == y
    }

    /**
     * v1.3.85 — LOOSE PAGE EQUALITY: same scheme + host + path (trailing
     * slash tolerated) and same query after stripping known tracking
     * params; fragment ignored. This is a COMPARISON for audit decisions
     * only — navigations always use the raw entry URL. Discriminating
     * params (v=, id=, p=…) are never stripped, so two different videos or
     * posts are still different pages and the audit can never force over
     * the page's own re-navigation.
     */
    private fun looseSameUrl(a: String, b: String): Boolean {
        if (sameUrl(a, b)) return true
        return try {
            val ua = Uri.parse(a)
            val ub = Uri.parse(b)
            val sa = ua.scheme?.lowercase()
            val sb = ub.scheme?.lowercase()
            if (sa != sb || sa == null) return false
            val ha = ua.host?.lowercase()
            val hb = ub.host?.lowercase()
            if (ha == null || ha != hb) return false
            val pa = (ua.path ?: "").trimEnd('/')
            val pb = (ub.path ?: "").trimEnd('/')
            if (pa != pb) return false
            canonQuery(ua.query) == canonQuery(ub.query)
        } catch (t: Throwable) {
            false
        }
    }

    /** the query with tracking params removed and pairs sorted ("" if none) */
    private fun canonQuery(q: String?): String {
        if (q.isNullOrEmpty()) return ""
        val kept = ArrayList<String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=')
            if (key in STRIP_PARAMS) continue
            var prefixed = false
            for (pre in STRIP_PREFIXES) {
                if (key.startsWith(pre)) { prefixed = true; break }
            }
            if (prefixed) continue
            kept.add(pair)
        }
        if (kept.isEmpty()) return ""
        kept.sort()
        return kept.joinToString("&")
    }
}
