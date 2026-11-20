package com.bolt.browser.util

import android.webkit.WebView
import java.util.Collections
import java.util.WeakHashMap

/**
 * v1.3.58 — THE FRAME RELAY: one document-start script for EVERY frame.
 *
 * THE v1.3.57 FIELD REPORT (round 3 of the Gemini bugs): the download
 * BUTTON in generated images still failed (dl offer + blobdl start logged,
 * no gmSaveBlob — every rescue stage missed) and the COPY button below
 * replies was still slow-or-dead. The v1.3.58 root-cause hunt found the
 * fingerprint both bugs share, in the field log itself:
 *
 *   dl offer blob:null/046765b3-980d-4793-804b-f3777adcd683
 *
 * `blob:null/` — the blob was minted in an OPAQUE-ORIGIN frame (a
 * sandboxed iframe). That single fact kills every main-frame rescue
 * structurally, verified by a live browser experiment this session:
 * a blob created inside <iframe sandbox> gets a `blob:null/…` URL, and
 * the PARENT frame can NEVER read it — fetch() rejects ("Failed to
 * fetch"), XHR errors, even `new Image(); src=blob:null/…` fires onerror.
 * So v1.3.57's chain (page fetch → own-img mint → DOM hunts), all of it
 * injected into the MAIN frame only, could not reach those bytes no
 * matter what. The displayed image long-press keeps working because that
 * <img> src is a plain https URL; only the BUTTON's freshly-minted blob
 * lives in the opaque frame. The copy button is the same story with a
 * different mechanism: if the button's handler runs in a frame that has
 * no Bolt bridge (and v1.3.57's shim was main-frame-only anyway, with a
 * premature-guard hole — see below), navigator.clipboard.writeText dies
 * there exactly as it does in the main frame.
 *
 * THE FIX — get a hook INSIDE the frame that owns the bytes/clipboard:
 * `WebViewCompat.addDocumentStartJavaScript(web, HOOK_JS, {"*"})`.
 * Chromium's implementation (components/js_injection/renderer/
 * js_communication.cc, RunScriptsAtDocumentStart) is a per-frame
 * RenderFrameObserver that matches EACH frame's security origin against
 * the rules — and the "*" rule (origin_matcher.cc, MatchAllOriginsRule::
 * Evaluate) returns kInclude UNCONDITIONALLY. So the script runs in the
 * main frame, every same-site iframe, every cross-site iframe, and every
 * opaque-origin SANDBOXED iframe — before any page script. This is the
 * same mechanism PasskeyCompat.apply already uses (field-proven), just
 * with a bigger payload. The script does three things, all idempotent:
 *
 *  1. BLOB CAPTURE — wrap URL.createObjectURL in whatever frame calls
 *     it; hold the Blob OBJECT (not the URL) in a per-frame map, capped
 *     (10 entries, ≤16 MB each — FIFO). Holding the object beats every
 *     URL-based rescue: a Blob stays readable via FileReader even after
 *     the page calls revokeObjectURL (the anchor is already dead by
 *     then), and it needs zero knowledge of the page's DOM (immune to
 *     shadow DOM, detached anchors, carousel redesigns — v1.3.57's
 *     lessons).
 *  2. FRAME RELAY — a message listener in every frame. `needBlob`
 *     (sent by the download chain, BlobDownload.kt v3) is forwarded
 *     down to child frames; a frame holding the blob answers with the
 *     full data URL, relayed upward frame-by-frame to the top. postMessage
 *     is the ONE channel that crosses opaque origins by design — the
 *     live experiment proved the relay works from a sandboxed iframe.
 *     `copyText` rides the same relay upward: a bridge-less frame's
 *     clipboard write reaches the top frame, which owns the Bolt bridge.
 *  3. CLIPBOARD SHIM — v1.3.57's util/ClipboardWrite.kt generalized.
 *     Fixes its two holes: (a) the guard `__boltClip=1` was set BEFORE
 *     the capability check, so a document where navigator.clipboard
 *     wasn't ready yet was poisoned for its whole lifetime even though
 *     onPageCommitVisible re-injected (same document, guard already
 *     set) — now the shim retries (250 ms × 40) until the clipboard
 *     object exists and only then sets the guard; (b) coverage was
 *     main-frame-only — now the document-start registration carries it
 *     into EVERY frame, and frames without a Bolt bridge route through
 *     the copyText relay instead of dying. writeText resolves through
 *     Bolt.clipPage (new bridge method: copies + logs a `clip page`
 *     field-evidence line); write()'s text/plain fast path is kept;
 *     readText/read/execCommand stay NATIVE (reading stays gated — the
 *     shim restores desktop-Chrome sanitized-WRITE parity, widens
 *     nothing).
 *  4. DOWNLOAD-NAME CAPTURE (v1.3.86) — a capture-phase click listener
 *     records every `a[download]` click's href→name pair and hands it to
 *     the new Bolt.noteDownloadAnchor bridge (frames without a bridge
 *     relay upward like copyText). Android WebView's onDownloadStart
 *     NEVER receives the download attribute's value — the v1.3.85 field
 *     report ("download names are random string (zai files)") was blob
 *     URLs UUID-guessed into filenames; zai's export flow mints
 *     blob-URL anchors with the REAL name and clicks them
 *     programmatically, which this listener sees (synthetic clicks
 *     dispatch through capture listeners too).
 *  5. PER-FRAME PASSWORD FILL (v1.4.25) — THE SILENT-GUARD CORPSE: the
 *     fill chip called `window.__boltFillPw && window.__boltFillPw(u,p)`
 *     in the MAIN frame only, so a login form inside a cross-origin SSO
 *     iframe (the DIU portal: the form lives on auth1.diu.edu.bd, never
 *     reachable from the main document) filled NOTHING, silently. Now:
 *     every frame gets the robust fill (native value setter +
 *     input/change events — React/Vue forms notice; username hunt by
 *     autocomplete/name/id/mail/phone), a login field in ANY frame arms
 *     the chip (its own host riding up — the v1.3.91 SSO host law, at
 *     last per-frame), and the fill is ROUTED: the tap runs
 *     __boltFillPwAll in the top frame, which fills locally and posts
 *     `fillPw` ONLY to the child frames that reported a login field
 *     (recorded by window identity when `pwArm` climbed) — the password
 *     is never broadcast, and the answer (`fillRes`) climbs back so
 *     Bolt.pwFillResult can toast + Diag the outcome. A miss is VISIBLE
 *     (Java-side timeout), never silent.
 *
 * v1.3.86 — BLOB CAPTURE WIDENED TO EVERY TYPE. The image-only filter
 *     was a v1.3.58 scoping decision that turned into the v1.3.85 field
 *     bug "text files does not download": zai's text/CSV/PDF exports
 *     mint a Blob, click a download anchor, and REVOKE the URL in the
 *     same tick — after that no fetch can ever read the URL again, and
 *     the rescue chain's non-image stages are image-only by design.
 *     The held OBJECT survives the revoke (FileReader keeps reading it),
 *     so the map now holds any blob ≤32 MB (6 FIFO entries — 192 MB
 *     worst-case pin, vs the old 160 MB image-only ceiling).
 *
 * REGISTRATION: arm() once per WebView (WeakHashMap guard) from
 * Tab.buildWeb — BEFORE the first loadUrl (document-start scripts only
 * cover frames that begin loading after the call). injectNow() is the
 * belt-and-braces evaluateJavascript fallback at onPageStarted +
 * onPageCommitVisible (same moments MediaHook.core uses): it covers the
 * main frame on WebViews too old for DOCUMENT_START_SCRIPT and any
 * document that somehow predates arming. The script is one idempotent
 * IIFE — double injection is a no-op.
 *
 * SECURITY: the relay accepts only `{__bolt:1,…}` messages. needBlob
 * answers with bytes the receiving frame itself created (a malicious
 * frame can only give away its own blobs); copyText can write text to
 * the clipboard — exactly what any focused page can already do through
 * the shimmed writeText in its own frame (write-only parity, no reads);
 * dlName only reports the filename a visible anchor already offered
 * the user. The blob map holds blobs of any type, capped; it dies with
 * the frame.
 *
 * BLAST RADIUS: MediaHook.core untouched (check_js / test_media_hook
 * gates intact); SelectionActions untouched; the webauthn/passkey
 * document-start script untouched (separate registration, same
 * mechanism). New @JavascriptInterface methods (Bolt.clipPage,
 * Bolt.dlTrace — BrowserActivity) are additive; the blob-download
 * chain's delivery still rides Bolt.gmSaveBlob.
 */
object FrameHooks {

    /**
     * The per-frame script. Deliberately regex-free and $-free with
     * single-quoted JS strings only (the transport lesson: escape-heavy
     * literals are corruption bait — scripts/test_blobdl.py reconstructs
     * this with find()+slicing, never regex, and asserts the semantic
     * fragments).
     */
    internal const val HOOK_JS = "(function(){try{" +
            // idempotency: one install per frame, ever
            "if(window.__boltFH)return;window.__boltFH=1;" +
            // ---- 1. blob capture: hold the Blob OBJECT in the frame that
            // mints it (a Blob stays FileReader-readable even after
            // revokeObjectURL; the bytes of a blob:null URL are reachable
            // NOWHERE else — opaque-origin frames are sealed) ----
            "try{" +
            "window.__boltBlobs={};" +
            "window.__boltBlobBytes=0;" +
            "var __co=URL.createObjectURL;" +
            "if(typeof __co==='function'&&!URL.__boltWrapped){" +
            "URL.__boltWrapped=1;" +
            "URL.createObjectURL=function(b){" +
            "var u=__co.call(URL,b);" +
            "try{" +
            // v1.3.86: EVERY type — text/CSV/PDF/zip exports (the zai
            // flow) revoke their URL in the same tick as the download
            // click; only the held OBJECT stays readable afterwards.
            // v1.3.89: THE BYTE BUDGET — the old rule (any blob <=32MB,
            // keep 6 by key count) could pin 192 MB FOREVER on a 3 GB
            // field device already riding trim-memory storms; every zai
            // chat export added 10-30 MB of dead weight until the frame
            // died, and the LMK answered with renderer kills (more
            // frequent crashes, background play dying). The map now
            // keeps a 48 MB TOTAL budget, evicting oldest-first until the
            // newcomer fits; one huge blob > 48 MB is simply not held
            // (the disk-download stages still run).
            "if(b&&typeof b.size==='number'&&b.size>0&&b.size<=50331648){" +
            "var m=window.__boltBlobs;" +
            "var bs=window.__boltBlobBytes||0;" +
            "while((bs+(b.size||0)>50331648)||Object.keys(m).length>=6){" +
            "var ks=Object.keys(m);" +
            "if(!ks.length)break;" +
            "var old=m[ks[0]];" +
            "try{bs-=old&&typeof old.size==='number'?old.size:0;}catch(x){}" +
            "delete m[ks[0]];" +
            "}" +
            "if(bs+(b.size||0)<=50331648){" +
            "m[u]=b;" +
            "bs+=b.size||0;" +
            "}" +
            "window.__boltBlobBytes=bs;" +
            "}" +
            "}catch(e){}" +
            "return u;" +
            "};" +
            "}" +
            "}catch(e){}" +
            // ---- 2. frame relay: needBlob forwards DOWN to child frames
            // and is answered by the holder; blobData and copyText climb
            // UP to the top frame (postMessage crosses opaque origins) ----
            "try{" +
            "var TOP=false;" +
            "try{TOP=window.parent===window;}catch(e){TOP=true;}" +
            // v1.4.25 — the login-field frames that reported up (window
            // identities; the fill is ROUTED to exactly these, never
            // broadcast)
            "window.__boltPwKids=[];" +
            "window.addEventListener('message',function(e){" +
            "var d=e&&e.data;" +
            "if(!d||d.__bolt!==1)return;" +
            "try{" +
            "if(d.t==='needBlob'){" +
            "var fr=document.querySelectorAll('iframe');" +
            "for(var i=0;i<fr.length;i++){" +
            "try{fr[i].contentWindow.postMessage({__bolt:1,t:'needBlob',u:d.u},'*');}catch(x){}" +
            "}" +
            "var b=window.__boltBlobs&&window.__boltBlobs[d.u];" +
            "if(b){" +
            "var r=new FileReader();" +
            "r.onload=function(){" +
            "try{parent.postMessage({__bolt:1,t:'blobData',u:d.u,mime:b.type||'',d:r.result},'*');}catch(x){}" +
            "};" +
            "r.readAsDataURL(b);" +
            "}" +
            "}else if(d.t==='blobData'){" +
            "if(!TOP){try{parent.postMessage(d,'*');}catch(x){}" +
            "}" +
            "}else if(d.t==='copyText'){" +
            "if(TOP){" +
            "try{" +
            "if(window.Bolt&&Bolt.clipPage)Bolt.clipPage(d.text,'relay');" +
            "else if(window.Bolt&&Bolt.copy)Bolt.copy(String(d.text==null?'':d.text));" +
            "}catch(x){}" +
            "}else{" +
            "try{parent.postMessage(d,'*');}catch(x){}" +
            "}" +
            "}else if(d.t==='dlName'){" +
            "if(TOP){" +
            "try{if(window.Bolt&&Bolt.noteDownloadAnchor)Bolt.noteDownloadAnchor(d.u,d.n);}catch(x){}" +
            "}else{" +
            "try{parent.postMessage(d,'*');}catch(x){}" +
            "}" +
            // v1.4.25 — THE PASSWORD RELAY: pwArm registers/unregisters the
            // reporting child (by window identity) and climbs to TOP,
            // where the SSO host rides Bolt.pwField (chip arming from ANY
            // frame — the v1.3.91 law finally reaches iframes); fillPw
            // fills THIS frame and answers; fillRes climbs to TOP ->
            // Bolt.pwFillResult (toast + Diag — a miss is visible)
            "}else if(d.t==='pwArm'){" +
            "var ks=window.__boltPwKids;" +
            "if(d.on){var have=false;" +
            "for(var qi=0;qi<ks.length;qi++){if(ks[qi]===e.source){have=true;break;}}" +
            "if(!have)try{ks.push(e.source);}catch(x){}}" +
            "else{for(var qj=ks.length-1;qj>=0;qj--){" +
            "try{if(ks[qj]===e.source)ks.splice(qj,1);}catch(x){}}}" +
            "if(!TOP){try{parent.postMessage(d,'*');}catch(x){}" +
            "}else{try{if(window.Bolt&&Bolt.pwField)" +
            "Bolt.pwField(String(d.host||''),d.on?true:false);}catch(x){}}" +
            "}else if(d.t==='fillPw'){" +
            "var rr=null;try{rr=window.__boltFillPw(d.u,d.p);}catch(x){rr=null;}" +
            "var rok=rr&&rr.ok!==undefined?rr.ok:rr;" +
            "if(!TOP){try{parent.postMessage({__bolt:1,t:'fillRes'," +
            "ok:rok?1:0,host:location.host},'*');}catch(x){}}" +
            "}else if(d.t==='fillRes'){" +
            "if(TOP){try{if(window.Bolt&&Bolt.pwFillResult)" +
            "Bolt.pwFillResult(d.ok?true:false,String(d.host||''));}catch(x){}}" +
            "else{try{parent.postMessage(d,'*');}catch(x){}" +
            "}" +
            "}" +
            "}catch(x){}" +
            "});" +
            "}catch(e){}" +
            // ---- 3. clipboard shim: v1.3.57's ClipboardWrite generalized
            // to every frame. RETRIES until navigator.clipboard exists and
            // only then sets the guard (the v1.3.57 hole: the guard was set
            // first, so an early document stayed poisoned forever). Frames
            // without a Bolt bridge route through the copyText relay ----
            "function armClip(n){" +
            "if(window.__boltClip)return;" +
            "var nc=null;" +
            "try{nc=navigator.clipboard;}catch(e){}" +
            "if(!nc||typeof nc.writeText!=='function'){" +
            "if(n<40)setTimeout(function(){armClip(n+1);},250);" +
            "return;" +
            "}" +
            "var haveBridge=false;" +
            "try{haveBridge=!!(window.Bolt&&(Bolt.clipPage||Bolt.copy));}catch(e){}" +
            "var isFrame=false;" +
            "try{isFrame=window.parent!==window;}catch(e){}" +
            "if(!haveBridge&&!isFrame){window.__boltClip=1;return;}" +
            "function deliver(t){" +
            "t=String(t==null?'':t);" +
            "if(haveBridge){" +
            "if(Bolt.clipPage)Bolt.clipPage(t,'direct');" +
            "else Bolt.copy(t);" +
            "}else{" +
            "parent.postMessage({__bolt:1,t:'copyText',text:t},'*');" +
            "}" +
            "}" +
            "function shim(t){" +
            "return new Promise(function(res,rej){" +
            "try{deliver(t);res();}catch(e){rej(e);}" +
            "});" +
            "}" +
            "function put(o,k,v){" +
            "try{Object.defineProperty(o,k,{value:v,writable:true,configurable:true});}" +
            "catch(e){try{o[k]=v;}catch(x){}}" +
            "}" +
            "put(nc,'writeText',shim);" +
            "var pr=(Object.getPrototypeOf?Object.getPrototypeOf(nc):null);" +
            "if(pr&&pr!==nc)put(pr,'writeText',shim);" +
            "var ww=nc.write;" +
            "if(typeof ww==='function'){" +
            "put(nc,'write',function(items){" +
            "try{" +
            "var it=(items&&items.length)?items[0]:null;" +
            "if(it&&typeof it.getType==='function'){" +
            "return it.getType('text/plain').then(function(b){" +
            "return b.text();" +
            "}).then(shim).catch(function(){" +
            "return ww.call(nc,items);" +
            "});" +
            "}" +
            "}catch(e){}" +
            "return ww.call(nc,items);" +
            "});" +
            "}" +
            "window.__boltClip=1;" +
            "}" +
            "armClip(0);" +
            // ---- 4. download-name capture (v1.3.86): anchor clicks with a
            // download attribute carry the REAL filename; WebView's
            // onDownloadStart never receives it. CAPTURE phase (synthetic
            // element.click() dispatches pass through it too); frames
            // without a Bolt bridge relay upward via the dlName message
            // exactly like copyText ----
            "try{" +
            "document.addEventListener('click',function(e){" +
            "try{" +
            "var n=e.target;if(!n||!n.closest)return;" +
            "var a=n.closest('a[download]');if(!a)return;" +
            "var h=a.href||'';if(!h)return;" +
            "var d=a.getAttribute('download');if(!d)return;" +
            "if(window.Bolt&&Bolt.noteDownloadAnchor){Bolt.noteDownloadAnchor(h,d);return;}" +
            "if(window.parent&&window.parent!==window){" +
            "try{parent.postMessage({__bolt:1,t:'dlName',u:h,n:d},'*');}catch(x){}" +
            "}" +
            "}catch(x){}" +
            "},true);" +
            "}catch(e){}" +
            // ---- 5. per-frame password fill (v1.4.25): the robust fill in
            // EVERY frame (the native value setter + input/change events so
            // React/Vue-controlled inputs notice; the username hunt rides
            // autocomplete/name/id/mail/phone), the login-field focusin
            // arming with the frame's OWN host climbing to the chip, and
            // the TOP-side orchestrator that ROUTES the fill to the
            // reporting frames ----
            "try{" +
            "function __boltSetVal(el,v){try{" +
            "var d=null;try{d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');}catch(x){}" +
            "try{if(d&&d.set){d.set.call(el,v);}else{el.value=v;}}catch(x){try{el.value=v;}catch(y){}}" +
            "try{el.dispatchEvent(new Event('input',{bubbles:true}));}catch(x){}" +
            "try{el.dispatchEvent(new Event('change',{bubbles:true}));}catch(x){}" +
            "}catch(e){}}" +
            "function __boltUserFor(pw){try{" +
            "var f=null;try{f=pw.form?pw.form:(pw.closest?pw.closest('form'):null);}catch(x){}" +
            "var root=(f&&f.querySelectorAll)?f:document;" +
            "var ins=root.querySelectorAll('input');" +
            "for(var i=0;i<ins.length;i++){var el=ins[i];var ty=(el.type||'').toLowerCase();" +
            "if(ty==='password'||ty==='hidden'||ty==='submit'||ty==='button'||ty==='checkbox'||ty==='radio'||ty==='file')continue;" +
            "var ac=(el.getAttribute('autocomplete')||'').toLowerCase();" +
            "var nm=((el.name||'')+' '+(el.id||'')+' '+ac).toLowerCase();" +
            "if(ac.indexOf('username')>=0||ty==='email'||nm.indexOf('user')>=0||nm.indexOf('login')>=0||nm.indexOf('account')>=0||nm.indexOf('mail')>=0||nm.indexOf('phone')>=0||nm.indexOf('mobile')>=0)return el;}" +
            "var all=document.querySelectorAll('input');var last=null;" +
            "for(var j=0;j<all.length;j++){var e2=all[j];var t2=(e2.type||'').toLowerCase();" +
            "if(t2==='text'||t2==='email'||t2==='tel')last=e2;if(e2===pw)break;}" +
            "return last;}catch(e){return null;}}" +
            "window.__boltFillPw=function(u,p){try{" +
            "var el=null;var ins=document.querySelectorAll('input[type=password]');" +
            "if(window.__boltPwFocused&&(window.__boltPwFocused.type||'').toLowerCase()==='password')el=window.__boltPwFocused;" +
            "else if(ins.length)el=ins[0];" +
            "if(!el){var fu=window.__boltPwFocused||null;" +
            "if(fu){__boltSetVal(fu,u);return {ok:1};}return {ok:0};}" +
            "var us=__boltUserFor(el);if(us&&u&&us!==el){__boltSetVal(us,u);}" +
            "__boltSetVal(el,p);return {ok:1};}catch(e){return {ok:0};}};" +
            "function __boltLoginField(el){try{" +
            "if(!el||el.tagName!=='INPUT')return false;" +
            "var ty=(el.type||'').toLowerCase();if(ty==='password')return true;" +
            "if(ty!=='text'&&ty!=='email'&&ty!=='tel')return false;" +
            "var ac=(el.getAttribute('autocomplete')||'').toLowerCase();" +
            "if(ac.indexOf('username')>=0)return true;" +
            "var f=null;try{f=el.form?el.form:(el.closest?el.closest('form'):null);}catch(x){}" +
            "var root=(f&&f.querySelector)?f:document;" +
            "return !!(root.querySelector&&root.querySelector('input[type=password]'));" +
            "}catch(e){return false}}" +
            "var __boltPWArmed=false;" +
            "function __boltPwArm(on){try{" +
            "parent.postMessage({__bolt:1,t:'pwArm',on:on?1:0,host:location.host},'*');" +
            "}catch(x){}}" +
            "document.addEventListener('focusin',function(ev){try{" +
            "var el=ev.target;var is=__boltLoginField(el);" +
            "if(is&&!__boltPWArmed){__boltPWArmed=true;window.__boltPwFocused=el;" +
            "if(el.type!=='password'){window.__boltLastUserEl=el;}" +
            "__boltPwArm(true);}" +
            "else if(!is&&__boltPWArmed){__boltPWArmed=false;window.__boltPwFocused=null;" +
            "__boltPwArm(false);}}catch(e){}},true);" +
            "window.__boltFillPwAll=function(u,p){try{" +
            "var r=null;try{r=window.__boltFillPw(u,p);}catch(x){r=null;}" +
            "var ok=r&&r.ok!==undefined?r.ok:r;" +
            "if(ok){try{if(window.Bolt&&Bolt.pwFillResult)Bolt.pwFillResult(true,location.host);}catch(x){}}" +
            "var kids=window.__boltPwKids||[];" +
            "for(var i=0;i<kids.length;i++){try{kids[i].postMessage({__bolt:1,t:'fillPw',u:u,p:p},'*');}catch(x){}}" +
            "if(!ok&&kids.length===0){try{if(window.Bolt&&Bolt.pwFillResult)Bolt.pwFillResult(false,location.host);}catch(x){}}" +
            "}catch(e){}};" +
            "}catch(e){}" +
            "}catch(e){}})();"

    /** WebViews already carrying the document-start registration. */
    private val ARMED = WeakHashMap<WebView, Boolean>()

    /**
     * Register HOOK_JS as a document-start script on this WebView — every
     * frame of every future navigation, opaque-origin sandboxed iframes
     * included (the "*" rule includes unconditionally; see class doc).
     * MUST run before the WebView's first loadUrl; once per WebView
     * (addDocumentStartJavaScript flushes the back-forward cache, so
     * repeat calls are not free). Mirrors PasskeyCompat.apply: same
     * feature gate, same wildcard rule, any thread (AwContents owns the
     * threading). Never throws.
     */
    @JvmStatic
    fun arm(w: WebView?) {
        if (w == null) return
        synchronized(ARMED) {
            if (ARMED.containsKey(w)) return
            ARMED[w] = true
        }
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(
                            androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                        w, HOOK_JS, Collections.singleton("*"))
            }
        } catch (ignored: Throwable) {
            // old WebView / registration race: injectNow still covers the
            // main frame; frame coverage degrades to v1.3.57 parity
        }
    }

    /**
     * Evaluate HOOK_JS in the given WebView's main frame NOW (any thread:
     * posts to the WebView's UI thread). Belt-and-braces beside the
     * document-start registration — covers WebViews without
     * DOCUMENT_START_SCRIPT support and documents that predate arm().
     * Idempotent: the __boltFH guard makes re-runs no-ops. This replaces
     * v1.3.57's ClipboardWrite.injectNow at the same two call moments
     * (onPageStarted + onPageCommitVisible, ALWAYS, incognito included).
     */
    @JvmStatic
    fun injectNow(web: WebView?) {
        if (web == null) return
        try {
            web.post {
                try { web.evaluateJavascript(HOOK_JS, null) } catch (ignored: Throwable) {}
            }
        } catch (ignored: Throwable) {}
    }
}
