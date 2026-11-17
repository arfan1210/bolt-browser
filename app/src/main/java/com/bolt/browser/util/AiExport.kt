package com.bolt.browser.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * v1.3.87 — THE AI RESPONSE EXPORTER (new code → Kotlin, per the standing
 * language rule). The field report: "add feature like the pc extensions
 * that can download gemini, gpt, zai, qwen etc ai's response as pdf or
 * image (like it also lets me select which responses I want to keep and
 * maintains the formatting)".
 *
 * APPROACH — THE LIVE PAGE IS THE RENDERER (image path). Instead of
 * cloning DOM and hoping the site's scoped CSS follows (it doesn't —
 * Tailwind hashes, CSS modules, :where() chains), the picker marks the
 * SELECTED response nodes in the page itself, hides everything that is
 * not on a keep→body ancestor path, and OPENS the fixed-height chat
 * scrollers (height:auto / overflow:visible) so the whole conversation
 * lays out in the document flow. The capture then walks the WebView
 * exactly like FullShot — what lands in the file is the site's OWN
 * rendering of its OWN markup: formatting, code blocks, math, tables,
 * all intact.
 *
 * v1.3.92 — THE NATIVE PICKER (the v1.3.91 report: "I am in zai chat web
 * and don't see any options to export or select a message to save as pdf
 * or image"). The picker chrome (numbered circles + the bottom action
 * bar) used to be INJECTED INTO THE PAGE — absolutely-positioned divs +
 * a position:fixed bar + a <style> element. That is fragile in exactly
 * the ways a React chat SPA is hostile: a transformed/filtered app root
 * turns position:fixed into position:relative-to-that-root (the bar can
 * land anywhere), inner scrollers desync the absolutely-positioned
 * circles the moment the chat scrolls, and a strict style-src CSP kills
 * the whole <style> sheet. The chrome now lives in ANDROID: the page JS
 * only MARKS candidates (data-bolt-ai), keeps selection state, reports
 * rects, hit-tests manual taps, and preps export mode — [BrowserActivity]
 * draws the circles, the bottom bar and the hints as real views over the
 * WebView. No site CSS/CSP/transform can hide the picker anymore.
 *
 * v1.3.92 — THE VECTOR PDF. "Saving a pdf means I want to save as a
 * vector file, not just a screenshot wrapped in pdf file." The PDF button
 * no longer stitches bitmap strips into PdfDocument pages: the selected
 * responses are extracted as STRUCTURED BLOCKS (headings, paragraphs,
 * list items, code, quotes, table cells — [blocksJs]) and rendered by
 * [VectorPdf] with real selectable TEXT (PdfDocument + StaticLayout +
 * TextPaint). The IMAGE path keeps the pixel-perfect site rendering; the
 * PDF path is the portable, searchable, selectable document.
 *
 * PAGE CONTRACT (the JS this object ships; the NATIVE side drives):
 *  - [pickerJs]: discovery + marking + the click-swallow (manual mode
 *    turns every tap into a selection); returns {"n":count,"manual":0|1}.
 *  - [rectsJs]: the sync payload — every candidate/manual pick with its
 *    viewport rect and selection state, for the native circles.
 *  - [toggleJs] / [setAllJs]: selection changes (native circle taps).
 *  - [blocksJs]: the structured extraction for the vector PDF.
 *  - [prepJs]: export mode for the IMAGE capture (the DOM surgery), then
 *    Bolt.aiCapture(mode, count) — unchanged from v1.3.87.
 *  - [restoreJs]: strip every marker/outline and picker listener.
 *
 * SELECTION (per-site first, generic fallback for the "etc"):
 *  - gemini.google.com ……… model-response
 *  - chatgpt.com / chat.openai.com … [data-message-author-role="assistant"]
 *  - chat.z.ai, chat.qwen.ai ……… the markdown family (react-markdown UIs)
 *  - claude.ai ……………………… data-testid assistant-message
 *  - generic ……………………… outermost visible element whose class smells of
 *      markdown / prose / response / message-content / answer, >= 40 chars
 */
object AiExport {

    private const val SETTLE = 260L          // per-strip settle (FullShot's own)
    private const val RELAYOUT = 450L        // export-mode relayout before measuring

    // ------------------------------------------------------------------ JS

    /** the picker core: discovery, marking, selection state, scroll sync.
     *  Returns {"n":count} JSON. All chrome is NATIVE (v1.3.92). */
    @JvmStatic
    fun pickerJs(): String = "(function(){try{" +
            "if(window.__boltAiPick)return '{\"n\":0,\"err\":\"busy\"}';" +
            // ---- response discovery ----
            "function txt(e){return (e.innerText||'').trim();}" +
            "function vis(e){try{var r=e.getBoundingClientRect();return r.width>40&&r.height>20;}catch(x){return false;}}" +
            "function collect(sel){var out=[];try{var l=document.querySelectorAll(sel);" +
            "for(var i=0;i<l.length;i++){var e=l[i];if(!vis(e)||txt(e).length<40)continue;out.push(e);}}catch(x){}" +
            // keep the OUTERMOST match only (markdown-in-markdown wrappers)
            "var res=[];for(var a=0;a<out.length;a++){var inn=false;" +
            "for(var b=0;b<out.length;b++){if(b!==a&&out[b].contains(out[a])){inn=true;break;}}" +
            "if(!inn)res.push(out[a]);}return res;}" +
            "var host='';try{host=location.hostname;}catch(x){}" +
            "var sel=null;" +
            "if(/(^|\\.)gemini\\.google\\.com$/.test(host))sel='model-response,.response-container-content';" +
            "else if(/(^|\\.)chatgpt\\.com$/.test(host)||/(^|\\.)chat\\.openai\\.com$/.test(host))sel='[data-message-author-role=\"assistant\"]';" +
            "else if(/(^|\\.)claude\\.ai$/.test(host))sel='[data-testid=\"assistant-message\"],.font-claude-message';" +
            "else if(/(^|\\.)z\\.ai$/.test(host)||/(^|\\.)chat\\.z\\.ai$/.test(host)||/(^|\\.)qwen\\.ai$/.test(host)||/(^|\\.)chat\\.qwen\\.ai$/.test(host))sel='[class*=\"markdown\"]';" +
            "var nodes=sel?collect(sel):[];" +
            "if(!nodes.length)nodes=collect('[class*=\"markdown\"],[class*=\"prose\"],[class*=\"response\"],[class*=\"message-content\"],[class*=\"msg-content\"],[class*=\"answer\"],[data-testid*=\"message\"],.font-claude-message,article');" +
            // MANUAL MODE: no candidate found (a new/unknown AI site, a DOM
            // change, anything) is no dead end — every tap selects the
            // tapped block. The feature works on ANY site.
            "var manual=!nodes.length;" +
            // ---- marking + selection state (NO page chrome — v1.3.92) ----
            "for(var i=0;i<nodes.length;i++){try{nodes[i].setAttribute('data-bolt-ai',String(i));}catch(x){}}" +
            "var mid=0;" +
            "function uid(e){try{var u=e.getAttribute('data-bolt-ai-m');if(!u){u='m'+(mid++);e.setAttribute('data-bolt-ai-m',u);}return u;}catch(x){return 'm0';}}" +
            "function mark(e,on){try{if(on){e.classList.add('__boltAiSel');" +
            "e.style.outline='3px solid #7C4DFF';e.style.outlineOffset='2px';}" +
            "else{e.classList.remove('__boltAiSel');e.style.outline='';e.style.outlineOffset='';}}catch(x){}}" +
            "function selCount(){var n2=0;for(var q=0;q<nodes.length;q++){if(nodes[q].classList.contains('__boltAiSel'))n2++;}" +
            "try{n2+=document.querySelectorAll('[data-bolt-ai-m].__boltAiSel').length;}catch(x){}return n2;}" +
            "window.__boltAiToggle=function(ix){try{var e=nodes[ix];if(!e)return selCount();" +
            "mark(e,!e.classList.contains('__boltAiSel'));return selCount();}catch(x){return 0;}};" +
            "window.__boltAiToggleM=function(u){try{var e=document.querySelector('[data-bolt-ai-m=\"'+u+'\"]');" +
            "if(e){mark(e,!e.classList.contains('__boltAiSel'));}return selCount();}catch(x){return 0;}};" +
            "window.__boltAiSetAll=function(on){try{for(var q=0;q<nodes.length;q++)mark(nodes[q],!!on);" +
            "var ms=document.querySelectorAll('[data-bolt-ai-m]');" +
            "for(var w=0;w<ms.length;w++)mark(ms[w],!!on);return selCount();}catch(x){return 0;}};" +
            // ---- the sync payload: rects + state for the NATIVE circles ----
            "window.__boltAiRects=function(){try{var out=[];function add(e,id,k){" +
            "var r=e.getBoundingClientRect();out.push({k:k,id:id,l:r.left,t:r.top,w:r.width,h:r.height," +
            "s:e.classList.contains('__boltAiSel')?1:0});}" +
            "for(var q=0;q<nodes.length;q++){try{if(nodes[q].isConnected)add(nodes[q],String(q),'c');}catch(x){}}" +
            "var ms=document.querySelectorAll('[data-bolt-ai-m]');" +
            "for(var w=0;w<ms.length;w++){try{add(ms[w],ms[w].getAttribute('data-bolt-ai-m'),'m');}catch(x){}}" +
            "return JSON.stringify({n:nodes.length,sel:selCount(),manual:manual?1:0,r:out});}catch(e){return '{}';}};" +
            // ---- the vector extraction: selected elements as blocks ----
            "window.__boltAiBlocks=function(){try{var roots=[];" +
            "for(var q=0;q<nodes.length;q++){if(nodes[q].classList.contains('__boltAiSel'))roots.push(nodes[q]);}" +
            "var ms=document.querySelectorAll('[data-bolt-ai-m].__boltAiSel');" +
            "for(var w=0;w<ms.length;w++){var dup=false;for(var q2=0;q2<roots.length;q2++){if(roots[q2]===ms[w])dup=true;}" +
            "if(!dup)roots.push(ms[w]);}" +
            "if(!roots.length)return '';var bs=[];" +
            "function own(el){var t='';for(var c=0;c<el.childNodes.length;c++){var nd=el.childNodes[c];" +
            "if(nd.nodeType===3)t+=nd.nodeValue;}return t.replace(/\\s+/g,' ').trim();}" +
            "for(var r=0;r<roots.length;r++){var root=roots[r];" +
            "var els=root.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td,th,dt,dd,figcaption');" +
            "var any=false;" +
            "for(var j=0;j<els.length&&bs.length<4000;j++){var el=els[j];var t;" +
            "if(el.tagName==='PRE'){t=(el.innerText||'').replace(/\\u00a0/g,' ').trim();if(t.length>20000)t=t.slice(0,20000);" +
            "if(t){bs.push({k:'pre',t:t});any=true;}}else{t=own(el);if(!t)continue;if(t.length>20000)t=t.slice(0,20000);" +
            "bs.push({k:el.tagName.toLowerCase(),t:t});any=true;}}" +
            "if(!any){var rt=(root.innerText||'').replace(/\\u00a0/g,' ').trim();" +
            "if(rt){var parts=rt.split(/\\n\\s*\\n/);for(var p2=0;p2<parts.length&&bs.length<4000;p2++){var pt=parts[p2].trim();" +
            "if(pt)bs.push({k:'p',t:pt.replace(/\\n/g,' ').slice(0,20000)});}}}}" +
            "return JSON.stringify(bs);}catch(e){return '';}};" +
            // ---- click swallow: nav-block while picking; manual mode also
            // selects the tapped block (climb to the outermost ancestor
            // below body whose text is still short — the block feel)
            "function swallow(ev){try{if(manual){var el=ev.target;var guard=0;" +
            "while(el&&el.parentElement&&el.parentElement!==document.body" +
            "&&(el.textContent||'').trim().length<80&&guard++<12){el=el.parentElement;}" +
            "if(el&&el!==document.body&&el!==document.documentElement){uid(el);" +
            "mark(el,!el.classList.contains('__boltAiSel'));" +
            // the native circle must appear at once — the poll is 700 ms
            "try{Bolt.aiSync();}catch(x){}}}" +
            "}" +
            "catch(x){}" +
            "ev.stopPropagation();ev.preventDefault();}" +
            "document.addEventListener('click',swallow,true);" +
            // ---- scroll sync: ANY scroller (capture) -> Bolt.aiSync(),
            // throttled 120 ms (the native circles follow the chat)
            "var lastS=0;function onScroll(){var now=Date.now();if(now-lastS<120)return;lastS=now;" +
            "try{Bolt.aiSync();}catch(x){}}" +
            "document.addEventListener('scroll',onScroll,true);" +
            // ---- export mode (the IMAGE path) — v1.3.92: INLINE STYLES,
            // not classes. The v1.3.87-91 corpse: the surgery added
            // .__boltAiHide/.__boltAiOpen/.__boltAiExp classes that NO
            // stylesheet anywhere ever defined (the picker chrome <style>
            // carried only the circles/bar rules) — nothing was ever
            // hidden, no scroller was ever opened, so the image export
            // captured the WHOLE page (or just the viewport on chat
            // sites with fixed-height scrollers). The classes stay as
            // MARKERS (the gates pin them) but the EFFECT is inline CSS
            // set through the CSSOM — CSP style-src-elem cannot block it.
            "window.__boltAiPrep=function(mode){var ks=[];" +
            "for(var q=0;q<nodes.length;q++){if(nodes[q].classList.contains('__boltAiSel'))ks.push(nodes[q]);}" +
            "var ms=document.querySelectorAll('[data-bolt-ai-m].__boltAiSel');" +
            "for(var w=0;w<ms.length;w++)ks.push(ms[w]);" +
            "if(!ks.length)return 0;" +
            // strip the selection outlines — picker state, not page content
            "for(var z=0;z<ks.length;z++){try{ks[z].style.outline='';ks[z].style.outlineOffset='';}catch(x){}}" +
            "var keepSet=new Set(ks);var pathSet=new Set();" +
            "for(var z2=0;z2<ks.length;z2++){var e=ks[z2];" +
            "while(e&&e!==document.body&&e!==document.documentElement){pathSet.add(e);e=e.parentElement;}}" +
            // HIDE everything outside keep→body paths (inline, reversible)
            "try{var all=document.body.querySelectorAll('*');" +
            "for(var z3=0;z3<all.length;z3++){var el=all[z3];" +
            "if(keepSet.has(el)||pathSet.has(el))continue;" +
            "var p=el.parentElement,ins=false;" +
            "while(p){if(keepSet.has(p)){ins=true;break;}p=p.parentElement;}" +
            "if(!ins){try{el.setAttribute('data-bolt-ai-d',el.style.display||'');" +
            "el.style.display='none';el.classList.add('__boltAiHide');}catch(x){}}}}catch(x){}" +
            // OPEN the fixed-height chat scrollers on the keep path
            "pathSet.forEach(function(e2){try{" +
            "e2.setAttribute('data-bolt-ai-h',e2.style.height||'');" +
            "e2.setAttribute('data-bolt-ai-o',e2.style.overflow||'');" +
            "e2.setAttribute('data-bolt-ai-mh',e2.style.maxHeight||'');" +
            "e2.style.height='auto';e2.style.overflow='visible';e2.style.maxHeight='none';" +
            "e2.classList.add('__boltAiOpen');}catch(x){}});" +
            "for(var z4=0;z4<ks.length;z4++){try{ks[z4].classList.add('__boltAiOpen');}catch(x){}}" +
            "try{document.documentElement.classList.add('__boltAiExp');}catch(x){}" +
            "try{window.scrollTo(0,0);}catch(x){}" +
            "try{Bolt.aiCapture(mode,ks.length);}catch(e3){try{window.__boltAiRestore&&window.__boltAiRestore();}catch(x){}}" +
            "return ks.length;};" +
            // ---- restore (Java calls after capture / teardown / failure) ----
            "window.__boltAiRestore=function(){try{" +
            "var sel2=document.querySelectorAll('.__boltAiSel');" +
            "for(var w=0;w<sel2.length;w++){try{sel2[w].classList.remove('__boltAiSel');" +
            "sel2[w].style.outline='';sel2[w].style.outlineOffset='';}catch(x){}}" +
            "var h=document.querySelectorAll('.__boltAiHide');" +
            "for(var y=0;y<h.length;y++){try{h[y].style.display=h[y].getAttribute('data-bolt-ai-d')||'';" +
            "h[y].removeAttribute('data-bolt-ai-d');h[y].classList.remove('__boltAiHide');}catch(x){}}" +
            "var o=document.querySelectorAll('.__boltAiOpen');" +
            "for(var y2=0;y2<o.length;y2++){try{var oe=o[y2];" +
            "if(oe.hasAttribute('data-bolt-ai-h')){oe.style.height=oe.getAttribute('data-bolt-ai-h')||'';" +
            "oe.style.overflow=oe.getAttribute('data-bolt-ai-o')||'';" +
            "oe.style.maxHeight=oe.getAttribute('data-bolt-ai-mh')||'';" +
            "oe.removeAttribute('data-bolt-ai-h');oe.removeAttribute('data-bolt-ai-o');" +
            "oe.removeAttribute('data-bolt-ai-mh');}" +
            "oe.classList.remove('__boltAiOpen');}catch(x){}}" +
            "try{document.documentElement.classList.remove('__boltAiExp');}catch(x){}" +
            "try{document.removeEventListener('click',swallow,true);}catch(x){}" +
            "try{document.removeEventListener('scroll',onScroll,true);}catch(x){}" +
            "var da=document.querySelectorAll('[data-bolt-ai]');" +
            "for(var d=0;d<da.length;d++)da[d].removeAttribute('data-bolt-ai');" +
            "var dm=document.querySelectorAll('[data-bolt-ai-m]');" +
            "for(var d2=0;d2<dm.length;d2++)dm[d2].removeAttribute('data-bolt-ai-m');" +
            "delete window.__boltAiPick;}catch(x){}};" +
            "window.__boltAiPick=1;" +
            "return '{\"n\":'+nodes.length+',\"manual\":'+(manual?1:0)+'}';" +
            "}catch(e){return '{\"n\":0,\"err\":\"'+String(e).slice(0,60)+'\"}'}})()"

    /** Java → page: read the sync payload (rects + state). */
    @JvmStatic
    fun rectsJs(): String =
            "(function(){try{return window.__boltAiRects?window.__boltAiRects():'{}';}catch(e){return '{}';}})()"

    /** Java → page: toggle candidate [i]; returns the new selected count. */
    @JvmStatic
    fun toggleJs(i: Int): String =
            "(function(){try{return window.__boltAiToggle?window.__boltAiToggle($i):0;}catch(e){return 0;}})()"

    /** Java → page: toggle a manual pick by uid; returns the new count. */
    @JvmStatic
    fun toggleMJs(u: String): String =
            "(function(){try{return window.__boltAiToggleM?window.__boltAiToggleM('" +
                    u.replace("[^a-zA-Z0-9]".toRegex(), "") + "'):0;}catch(e){return 0;}})()"

    /** Java → page: select/deselect everything. */
    @JvmStatic
    fun setAllJs(on: Boolean): String =
            "(function(){try{return window.__boltAiSetAll?window.__boltAiSetAll(${if (on) 1 else 0}):0;}catch(e){return 0;}})()"

    /** Java → page: the structured blocks of the selection (vector PDF). */
    @JvmStatic
    fun blocksJs(): String =
            "(function(){try{return window.__boltAiBlocks?window.__boltAiBlocks():'';}catch(e){return '';}})()"

    /** Java → page: enter export mode and capture (the IMAGE path). */
    @JvmStatic
    fun prepJs(mode: String): String =
            "(function(){try{return window.__boltAiPrep?window.__boltAiPrep('$mode'):0;}catch(e){return 0;}})()"

    /** Java → page: end picker + export mode (idempotent, try/catch inside). */
    @JvmStatic
    fun restoreJs(): String =
            "(function(){try{window.__boltAiRestore&&window.__boltAiRestore();}catch(e){}})();"

    // ------------------------------------------------- v1.3.96 page geometry

    /** v1.3.96 — THE PAGE'S OWN HEIGHT (the field: "Save as pdf … does not
     *  cover the whole webpage to be saved"). WebView.contentHeight is
     *  stale or viewport-clamped on inner-scroller SPAs (chat layouts,
     *  desktop-mode sites): the stitch walk tiled only what the root
     *  scroller exposed. The DOM's max scrollHeight/offsetHeight across
     *  html/body/scrollingElement is the number to tile. CSS px — the
     *  caller multiplies by w.scale exactly like the contentHeight path. */
    @JvmStatic
    fun heightJs(): String =
            "(function(){try{var d=document,b=d.documentElement,s=d.scrollingElement||b;" +
            "var h=0;if(b){h=Math.max(h,b.scrollHeight,b.offsetHeight);}" +
            "if(d.body){h=Math.max(h,d.body.scrollHeight,d.body.offsetHeight);}" +
            "if(s&&s!==b){h=Math.max(h,s.scrollHeight,s.offsetHeight);}" +
            "return String(Math.ceil(h));}catch(e){return '0'}})()"

    /** v1.3.96 — THE SCROLLER FLATTEN (the generic page-save image path).
     *  A page that scrolls INSIDE a fixed-height div never shows its full
     *  content to the root walk. Reversibly open the big inner scrollers
     *  (height:auto / overflow:visible / max-height:none through the
     *  CSSOM — no stylesheet, CSP-proof), exactly what the AI export's
     *  prepJs does for its keep-paths, here for the whole page. Only
     *  scrollers taller than their client box AND over half the viewport
     *  (the main chat/article panes), capped at 40 elements. */
    @JvmStatic
    fun flattenJs(): String =
            "(function(){try{" +
            "if(window.__boltFlat)return '1';" +
            "var els=document.body?document.body.querySelectorAll('*'):[];" +
            "var n=0,vh=window.innerHeight||0;" +
            "for(var i=0;i<els.length&&n<40;i++){var e=els[i];" +
            "var cs;try{cs=getComputedStyle(e);}catch(x){continue;}" +
            "var oy=cs.overflowY;if(oy!=='auto'&&oy!=='scroll')continue;" +
            "if(e.scrollHeight<=e.clientHeight+40)continue;" +
            "if(e.clientHeight<vh*0.5)continue;" +
            "e.setAttribute('data-bolt-fh',e.style.height||'');" +
            "e.setAttribute('data-bolt-fo',e.style.overflow||'');" +
            "e.setAttribute('data-bolt-fm',e.style.maxHeight||'');" +
            "e.style.height='auto';e.style.overflow='visible';e.style.maxHeight='none';" +
            "e.classList.add('__boltFlat');n++;}" +
            "window.__boltFlat=1;return String(n);}catch(e){return '0'}})()"

    /** v1.3.96 — undo [flattenJs] (inline styles restored from their
     *  data- attributes; idempotent). */
    @JvmStatic
    fun flatRestoreJs(): String =
            "(function(){try{var f=document.querySelectorAll('.__boltFlat');" +
            "for(var i=0;i<f.length;i++){var e=f[i];" +
            "e.style.height=e.getAttribute('data-bolt-fh')||'';" +
            "e.style.overflow=e.getAttribute('data-bolt-fo')||'';" +
            "e.style.maxHeight=e.getAttribute('data-bolt-fm')||'';" +
            "e.removeAttribute('data-bolt-fh');e.removeAttribute('data-bolt-fo');" +
            "e.removeAttribute('data-bolt-fm');e.classList.remove('__boltFlat');}" +
            "delete window.__boltFlat;}catch(e){}})();"

    // -------------------------------------------------------------- capture

    /**
     * Capture the prepared (export-mode) page. [pdf] selects the legacy
     * strip-stitch PdfDocument pipeline (the page-as-rendered visual; the
     * v1.3.92 VECTOR path lives in [VectorPdf] and never scrolls the
     * page); otherwise the FullShot PNG stitch under an AI-export name.
     * [onDone] runs on the MAIN thread. The RELAYOUT settle lets the
     * export-mode CSS (scrollers opened, siblings hidden) settle before
     * the content height is measured.
     */
    @JvmStatic
    fun capture(a: Activity, w: WebView, pdf: Boolean, onDone: OnShotDone) {
        capture(a, w, pdf, "Bolt-AI-export-", onDone)
    }

    @JvmStatic
    fun capture(a: Activity, w: WebView, pdf: Boolean, prefix: String, onDone: OnShotDone) {
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({
            try {
                // v1.3.96 — measure the page's TRUE height in the DOM first
                // (heightJs): contentHeight is viewport-clamped on
                // inner-scroller pages and the stitch missed everything
                // below the fold ("does not cover the whole webpage").
                // Measured AFTER the RELAYOUT settle so a prep/flatten has
                // already re-flowed. 0 = the old contentHeight fallback.
                w.evaluateJavascript(heightJs()) { res ->
                    var jsH = 0
                    try {
                        var s = res ?: "0"
                        val v = org.json.JSONTokener(s).nextValue()
                        if (v is String) s = v
                        jsH = s.toIntOrNull() ?: 0
                    } catch (_: Throwable) { jsH = 0 }
                    try {
                        if (pdf) capturePdf(a, w, prefix, jsH, onDone)
                        else FullShot.capture(a, w, prefix, jsH, onDone)
                    } catch (t: Throwable) {
                        onDone.done(ShotResult(false, null, "Capture failed"))
                    }
                }
            } catch (t: Throwable) {
                onDone.done(ShotResult(false, null, "Capture failed"))
            }
        }, RELAYOUT)
    }

    private fun capturePdf(a: Activity, w: WebView, prefix: String,
            jsHeight: Int, onDone: OnShotDone) {
        val h = Handler(Looper.getMainLooper())
        // v1.3.96 — the height prefers the DOM's own number (jsHeight —
        // see heightJs) and the CAP doubles to 32000: the PDF path holds
        // no master bitmap (each strip is captured, blitted into the page
        // and recycled), so the old 16000 ceiling only truncated long
        // pages ("does not cover the whole webpage"). 32000 px at a
        // ~800 px viewport is ~40 strips ≈ 15 s worst case — bounded.
        val ch = if (jsHeight > 0) jsHeight.toFloat() else w.contentHeight.toFloat()
        val pageH = Math.round(ch * w.scale).coerceIn(1, 32000)
        val vw = w.width
        val vh = w.height
        if (vw <= 0 || vh <= 0 || pageH <= 0) {
            onDone.done(ShotResult(false, null, "Page is not ready yet"))
            return
        }
        val sx = w.scrollX
        val sy = w.scrollY
        // A4-aspect page height ROUNDED UP to whole viewport strips, so
        // pages tile the content exactly: strip i lives at
        // (i % stripsPerPage) * vh inside page (i / stripsPerPage).
        val stripsPerPage = ((vw * 1.414f).toInt() + vh - 1) / vh
        val pageHpt = stripsPerPage * vh
        val totalStrips = (pageH + vh - 1) / vh
        val doc = android.graphics.pdf.PdfDocument()
        val si = intArrayOf(0)

        val loop = object : Runnable {
            override fun run() {
                try {
                    if (si[0] >= totalStrips) {
                        w.scrollTo(sx, sy)
                        Thread({ persistPdf(a, doc, prefix, onDone) }, "bolt-aipdf").start()
                        return
                    }
                    val i = si[0]
                    val maxY = (pageH - vh).coerceAtLeast(0)
                    w.scrollTo(0, (i * vh).coerceAtMost(maxY))
                    h.postDelayed({
                        try {
                            val yOff = w.scrollY
                            PixelSnap.viewShot(a, w) { b ->
                                try {
                                    val piece = b ?: softwarePiece(w, vw, vh)
                                    if (piece != null) {
                                        val p = i / stripsPerPage
                                        val pageStart = p * pageHpt
                                        val posInPage = (i - p * stripsPerPage) * vh
                                        // this strip's OWN region on the page:
                                        // never past the page, never past the
                                        // content (a scroll-clamped strip's
                                        // repeated rows fall outside the clip)
                                        val bottom = minOf(posInPage + vh,
                                                pageH - pageStart, pageHpt)
                                        if (bottom > posInPage) {
                                            val pg = doc.startPage(
                                                    android.graphics.pdf.PdfDocument.PageInfo
                                                            .Builder(vw, pageHpt, p + 1).create())
                                            val cv = pg.canvas
                                            cv.save()
                                            cv.clipRect(0f, posInPage.toFloat(),
                                                    vw.toFloat(), bottom.toFloat())
                                            cv.translate(0f, (yOff - pageStart).toFloat())
                                            cv.drawBitmap(piece, 0f, 0f, null)
                                            cv.restore()
                                            doc.finishPage(pg)
                                        }
                                        piece.recycle()
                                    }
                                } catch (t: Throwable) {
                                    try { b?.recycle() } catch (ignored: Throwable) {}
                                }
                                si[0]++
                                h.post(this)
                            }
                        } catch (t: Throwable) {
                            si[0]++
                            h.post(this)
                        }
                    }, SETTLE)
                } catch (t: Throwable) {
                    w.scrollTo(sx, sy)
                    try { doc.close() } catch (ignored: Throwable) {}
                    onDone.done(ShotResult(false, null, "Capture failed"))
                }
            }
        }
        h.post(loop)
    }

    /** software fallback for one strip (old APIs / detached windows) —
     *  may be blank on GPU-accelerated devices, but keeps the loop alive. */
    private fun softwarePiece(w: WebView, vw: Int, vh: Int): Bitmap? {
        return try {
            val b = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            w.draw(c)
            b
        } catch (t: Throwable) { null }
    }

    internal fun persistPdf(a: Activity, doc: android.graphics.pdf.PdfDocument,
            prefix: String, onDone: OnShotDone) {
        var uri: android.net.Uri? = null
        var msg = "Saved to Downloads"
        try {
            val name = prefix + System.currentTimeMillis() + ".pdf"
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues()
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                uri = a.contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri == null) throw IllegalStateException("insert failed")
                try {
                    val os = a.contentResolver.openOutputStream(uri)
                    // v1.3.93 — a null stream is a FAILURE (the old ?.use left
                    // uri non-null: success reported over a 0-byte row)
                    if (os == null) throw IllegalStateException("stream null")
                    os.use { doc.writeTo(it) }
                } catch (t2: Throwable) {
                    // no 0-byte orphan row stays in Downloads
                    try { a.contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
                    uri = null
                    throw t2
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                if (dir != null && !dir.exists()) dir.mkdirs()
                val f = java.io.File(dir, name)
                java.io.FileOutputStream(f).use { doc.writeTo(it) }
                uri = android.net.Uri.fromFile(f)
            }
        } catch (t: Throwable) {
            msg = "Save failed"
        }
        try { doc.close() } catch (t: Throwable) {}
        val finalUri = uri
        val finalMsg = msg
        a.runOnUiThread { onDone.done(ShotResult(finalUri != null, finalUri, finalMsg)) }
    }

    /** the share sheet for a finished export. */
    @JvmStatic
    fun share(a: Activity, uri: android.net.Uri?, pdf: Boolean) {
        if (uri == null) return
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_SEND)
            i.type = if (pdf) "application/pdf" else "image/png"
            i.putExtra(android.content.Intent.EXTRA_STREAM, uri)
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(android.content.Intent.createChooser(i, "Share AI export"))
        } catch (t: Throwable) {
            Fx.toast(a, "Share failed")
        }
    }
}
