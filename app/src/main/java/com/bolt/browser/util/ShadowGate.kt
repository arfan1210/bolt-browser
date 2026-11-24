package com.bolt.browser.util

import android.webkit.WebView
import java.util.Collections
import java.util.WeakHashMap

/**
 * v1.3.72 — SHADOW GATE (new code → Kotlin; the standing language rule).
 *
 * The README's pre-registered lever, now shipped. PageTranslate's walker
 * pierces OPEN shadow roots (`element.shadowRoot`), but a root created
 * `attachShadow({mode:'closed'})` is unreadable from outside BY DESIGN —
 * `element.shadowRoot` returns null and the subtree is invisible to every
 * DOM walker (the v1.3.71 README called this out as the "all counters
 * zero" nodes=0 case). The only universal cure is to be in the room when
 * the root is minted: wrap `Element.prototype.attachShadow` BEFORE page
 * scripts run and record every root it returns into a per-window
 * registry (`window.__boltShr`), which the translation walker then reads.
 *
 * This is the same move Firefox's translations engine cannot make (it
 * walks only reachable roots) and the same move immersive-translate-class
 * extensions DO make — their collectors check a hook registry first.
 *
 * Registration follows the FrameHooks pattern exactly (same feature gate,
 * same "*" origin rule, once per WebView): document-start in EVERY frame
 * — each frame's registry lives on that frame's OWN window, and the
 * walker reads the registry of whichever document it is walking.
 *
 * The wrap is ES5 (old-WebView safe), idempotent (double injection at
 * onPageStarted + onPageCommitVisible must not double-wrap) and never
 * throws into the page. A page script that later OVERWRITES
 * `attachShadow` with its own function loses recording for roots minted
 * through the overwrite — accepted; the open-root path still covers
 * those sites, and the diag's `cshr=` counter names the gap.
 */
object ShadowGate {

    /**
     * The document-start payload. Records every attachShadow result into
     * `window.__boltShr` (a dense array of ShadowRoot objects, both open
     * and closed — dedup by identity). The `__boltAS` marker on the
     * prototype prevents double-wrapping; `window.__boltShrArmed` guards
     * re-injection into the same document.
     */
    internal const val GATE_JS = "(function(){try{" +
            "if(window.__boltShrArmed)return;" +
            "window.__boltShrArmed=1;" +
            "var E=Element.prototype;" +
            "if(E&&typeof E.attachShadow=='function'&&!E.__boltAS){" +
            "var orig=E.attachShadow;" +
            "try{Object.defineProperty(E,'__boltAS',{value:orig,writable:false," +
            "enumerable:false,configurable:false})}catch(e){E.__boltAS=orig}" +
            "E.attachShadow=function(init){" +
            "var r=orig.call(this,init);" +
            "try{" +
            "if(r){" +
            "var a=window.__boltShr;" +
            "if(!a)a=window.__boltShr=[];" +
            "var dup=false;" +
            "for(var i=0;i<a.length;i++)if(a[i]===r){dup=true;break}" +
            "if(!dup)a.push(r)" +
            "}" +
            "}catch(e2){}" +
            "return r};" +
            "}" +
            "}catch(e){}})();"

    /** WebViews already carrying the document-start registration. */
    private val ARMED = WeakHashMap<WebView, Boolean>()
    /** v1.4.31 — the one-attempt map (see the law note inside arm()). */
    private val TRIED = WeakHashMap<WebView, Boolean>()

    /** v1.4.31 — process-wide fallback-eval counter for the perf nav line. */
    @JvmField var evalCount: Int = 0
    @JvmField var evalBytes: Long = 0

    /**
     * Register GATE_JS as a document-start script on this WebView — every
     * frame of every future navigation, opaque-origin sandboxed iframes
     * included. MUST run before the WebView's first loadUrl; once per
     * WebView (addDocumentStartJavaScript flushes the back-forward
     * cache). Mirrors FrameHooks.arm: same gate, same wildcard rule, any
     * thread, never throws.
     */
    @JvmStatic
    fun arm(w: WebView?) {
        if (w == null) return
        // v1.4.31 — THE SINGLE-INJECTION LAW (mirrors FrameHooks): TRIED
        // marks the one attempt, ARMED a SUCCESSFUL registration
        synchronized(TRIED) {
            if (TRIED.containsKey(w)) return
            TRIED[w] = true
        }
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(
                            androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                        w, GATE_JS, Collections.singleton("*"))
                synchronized(ARMED) { ARMED[w] = true }
            }
        } catch (ignored: Throwable) {
            // old WebView / registration race: injectNow still covers the
            // main frame at the onPage moment
        }
    }

    /**
     * Evaluate GATE_JS in the given WebView's main frame NOW. Belt-and-
     * braces beside the document-start registration — covers WebViews
     * without DOCUMENT_START_SCRIPT support and documents that predate
     * arm(). Idempotent: the __boltShrArmed guard makes re-runs no-ops.
     * Called at the same moment as FrameHooks.injectNow
     * (onPageStarted — v1.4.31 keeps ONE fallback per document, ALWAYS,
     * incognito included).
     * v1.4.31 — THE SINGLE-INJECTION LAW: a WebView whose registration
     * succeeded never re-evaluates the script here.
     */
    @JvmStatic
    fun injectNow(web: WebView?) {
        if (web == null) return
        if (ARMED.containsKey(web)) return
        evalCount++
        evalBytes += GATE_JS.length
        try {
            web.post {
                try { web.evaluateJavascript(GATE_JS, null) } catch (ignored: Throwable) {}
            }
        } catch (ignored: Throwable) {}
    }
}
