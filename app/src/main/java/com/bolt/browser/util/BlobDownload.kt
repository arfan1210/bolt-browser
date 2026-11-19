package com.bolt.browser.util

import android.webkit.WebView
import org.json.JSONObject

/**
 * v1.3.56 → v1.3.57 → v1.3.58 — BLOB-DOWNLOAD RESCUE, round 3.
 *
 * v1.3.56 taught the lesson (and v1.3.57's field report confirmed it the
 * hard way): the bytes of a `blob:` download exist ONLY inside the page
 * that created them, Java cannot fetch a blob: URL, and Gemini's live CSP
 * `connect-src` has NO `blob:` — so the classic inject-a-fetch rescue dies
 * before a byte moves. The v1.3.56 fallback HUNTED THE DOM for the image
 * the page was displaying — blind to shadow DOM and detached anchors.
 * v1.3.57 stopped asking the DOM and MINTED its own `<img src=blobUrl>` —
 * still not enough: the v1.3.57 field report showed `dl offer blob:null/…`
 * + `blobdl start` with NO gmSaveBlob line. Every stage missed.
 *
 * THE v1.3.58 ROOT CAUSE — `blob:null/`. The URL's origin is NULL: the
 * blob was minted inside an OPAQUE-ORIGIN frame (a sandboxed iframe).
 * Verified with a live browser experiment this session: a blob created in
 * `<iframe sandbox>` gets exactly a `blob:null/<uuid>` URL, and the parent
 * frame can NEVER read those bytes — fetch() rejects, XHR errors, even a
 * minted `new Image(); src=blob:null/…` fires onerror. Gemini's image
 * card mints its download blob in such a frame, so every main-frame
 * rescue (fetch, own-img, DOM hunts) is STRUCTURALLY dead for it. The
 * long-press save keeps working because that <img> src is a plain https
 * URL — only the button's fresh blob lives behind the opaque wall.
 *
 * THE FIX — ask the frame that OWNS the bytes. util/FrameHooks.kt (also
 * v1.3.58) registers a document-start script in EVERY frame — Chromium's
 * per-frame origin matching includes opaque origins under the "*" rule
 * (js_communication.cc RunScriptsAtDocumentStart + origin_matcher.cc
 * MatchAllOriginsRule — verified in source) — which wraps
 * URL.createObjectURL and holds the Blob OBJECT in the minting frame
 * (readable via FileReader even after revokeObjectURL). This chain's new
 * stage 0 broadcasts `needBlob` to every child frame and takes the first
 * `blobData` answer relayed back up; the holder's own FileReader gives
 * the EXACT original bytes — no CSP involved (not a fetch), no DOM
 * knowledge, no canvas re-encode. The v1.3.57 stages stay as fallbacks
 * for pages whose frames predate the hook or lack it.
 *
 * THE CHAIN v3 (one injection, stages in order, first success wins):
 *  0. FRAME DANCE (NEW — the v1.3.58 killer): if the FrameHooks map
 *     exists, hand back a held blob directly or broadcast needBlob to all
 *     child frames; first blobData reply wins (≤1.2 s wait). Skipped
 *     instantly (zero cost) when the hook isn't installed.
 *  1. PAGE FETCH — fetch(blobUrl) → FileReader → data URL. Exact bytes;
 *     works wherever CSP permits blob: in connect-src.
 *  2. OWN IMG — `new Image(); src=blobUrl` → canvas → toDataURL (works
 *     for same-origin blob:https URLs; dead for blob:null by origin).
 *  3. DISPLAYED-IMG HUNT — every <img> whose currentSrc/src IS the blob,
 *     piercing open shadow roots (revoked-URL case).
 *  4. ANCHOR HUNT — the light-DOM walk up the a[download] anchor's tree.
 *  5. HONEST FAILURE — Bolt.notify('Download failed') + dlTrace('allmiss').
 *
 * DELIVERY rides the EXISTING bridge (Bolt.gmSaveBlob →
 * DownloadEngine.saveDataUrl) — no new @JavascriptInterface here. The
 * injection targets the WebView passed by the caller — the tab that
 * actually offered the download — never evalMedia's media tab.
 *
 * FIELD EVIDENCE (v1.3.58 adds Bolt.dlTrace → `blobdl …` lines):
 *   dl offer <url>            onDownloadStart fired
 *   blobdl start name=…       the chain was entered
 *   blobdl ask                the dance broadcast went out
 *   blobdl frame              a frame answered with the bytes (the fix!)
 *   blobdl fetch|ownimg|hunt|anchor   which fallback stage saved
 *   blobdl allmiss            every stage missed → 'Download failed' toast
 *   gmSaveBlob <n>B name=…    bytes reached the engine
 *
 * Blast radius: nothing else changes. http(s) downloads, data: downloads
 * and the userscript GM_download path are untouched; this chain only runs
 * for URLs that were rejected outright before v1.3.56.
 */
object BlobDownload {

    /**
     * Run the rescue chain in the given WebView (the tab that offered the
     * download — the bytes can only be read by the page that owns them).
     * Safe from any thread: evaluateJavascript must run on the UI thread,
     * so we post there.
     *
     * v1.4.20 — THE ASK DOOR RIDES THE BRIDGE, NOT THE CHAIN: the chain is
     * byte-identical to v1.3.58 (it always delivers to Bolt.gmSaveBlob);
     * the ask-every-time pref is consulted JAVA-SIDE inside the bridge —
     * Ask opens the rename + destination dialog with the rescued bytes,
     * Direct saves at once. One delivery contract, two doors, zero
     * divergence between rescue callers (the download listener AND the
     * long-press fetch rescue both land here).
     */
    @JvmStatic
    fun start(web: WebView?, url: String, name: String, mime: String?) {
        if (web == null || !url.startsWith("blob:")) return
        val js = chainJs(url, name, mime ?: "")
        try {
            web.post {
                try { web.evaluateJavascript(js, null) } catch (ignored: Throwable) {}
            }
        } catch (ignored: Throwable) {}
    }

    /**
     * The rescue chain as one self-contained IIFE. Package-visible so the
     * regression gate (scripts/test_blobdl.py) can assert every stage.
     * JSONObject.quote produces a JS string literal for the three values
     * (handles quotes, backslashes, newlines, unicode).
     */
    internal fun chainJs(url: String, name: String, mime: String): String {
        val qU = JSONObject.quote(url) ?: "\"\""
        val qN = JSONObject.quote(name) ?: "\"download\""
        val qM = JSONObject.quote(mime) ?: "\"\""
        return "(function(){try{" +
                "var U=" + qU + ",N=" + qN + ",M=" + qM + ";" +
                // normalize the legacy jpeg alias so canvas encoding is valid
                "if(M=='image/jpg')M='image/jpeg';" +
                "var done=false;" +
                "function trace(s){try{Bolt.dlTrace(s);}catch(x){}}" +
                "function no(){if(done)return;done=true;" +
                "trace('allmiss');" +
                "try{Bolt.notify('Download failed');}catch(x){}}" +
                "function ok(d,m){if(done)return;done=true;" +
                "try{Bolt.gmSaveBlob(N,m||M||'',d);}catch(e){no();}}" +
                // shared saver: draw any loaded img into a canvas at natural
                // size and hand the data URL to the engine (toDataURL is NOT
                // a fetch — page CSP cannot block it)
                "function saveImg(img){try{" +
                "var w=img.naturalWidth||img.width||0,h=img.naturalHeight||img.height||0;" +
                "if(!w||!h)return false;" +
                "var c=document.createElement('canvas');c.width=w;c.height=h;" +
                "c.getContext('2d').drawImage(img,0,0,w,h);" +
                "var m=(M&&/^(image\\/(png|jpeg|webp))$/.test(M))?M:'image/png';" +
                "var d=(m=='image/jpeg')?c.toDataURL(m,0.92):c.toDataURL(m);" +
                "ok(d,m);return true;}catch(e){return false;}}" +
                // ---- stage 3: every <img> the page is displaying, PIERCING
                // open shadow roots (Gemini's gallery lives inside web
                // components; the v1.3.56 light-DOM query was blind to it).
                // BFS over document + every element's shadowRoot, capped. ----
                "function allImgs(){" +
                "var out=[],q=[document],n=0;" +
                "while(q.length&&n<4000){" +
                "var r=q.shift();n++;" +
                "try{" +
                "var l=r.querySelectorAll('img');" +
                "for(var i=0;i<l.length;i++)out.push(l[i]);" +
                "var els=r.querySelectorAll('*');" +
                "for(var j=0;j<els.length;j++){" +
                "if(els[j].shadowRoot)q.push(els[j].shadowRoot);}" +
                "}catch(e){}}" +
                "return out;}" +
                "function huntStage(){try{" +
                "var l=allImgs();" +
                "for(var i=0;i<l.length;i++){" +
                "if((l[i].currentSrc||l[i].src||'')==U){if(saveImg(l[i])){trace('hunt');return true;}}}" +
                "}catch(e){}" +
                "return false;}" +
                // ---- stage 4: the download anchor that was clicked — the
                // nearest <img> up its tree (light DOM; kept from v1.3.56 as
                // the last resort — a detached anchor is invisible to this
                // too) ----
                "function anchorStage(){try{" +
                "var a=document.querySelectorAll('a[download]');" +
                "for(var j=0;j<a.length;j++){" +
                "if((a[j].href||'')!=U)continue;" +
                "var p=a[j];" +
                "for(var up=0;up<8&&p;p=p.parentElement){" +
                "if(p.querySelector){var im=p.querySelector('img');" +
                "if(im&&saveImg(im)){trace('anchor');return true;}}" +
                "var sib=p.nextElementSibling;" +
                "for(var s=0;s<4&&sib;sib=sib.nextElementSibling,s++){" +
                "if(sib.querySelector){var i2=sib.querySelector('img');" +
                "if(i2&&saveImg(i2)){trace('anchor');return true;}}}}}" +
                "}catch(e){}" +
                "return false;}" +
                "function tail(){if(done)return;" +
                "if(!huntStage()&&!anchorStage())no();}" +
                // ---- stage 2: MINT OUR OWN <img> from the blob URL — the
                // v1.3.57 fix (same-origin blob:https URLs only; blob:null
                // dies here by construction — stage 0 exists for those) ----
                "function ownStage(){" +
                "var ran=false;" +
                "function next(){if(ran||done)return;ran=true;tail();}" +
                "try{" +
                "var im=new Image();" +
                "im.onload=function(){if(done)return;if(!saveImg(im)){next();}else trace('ownimg');};" +
                "im.onerror=function(){if(done)return;next();};" +
                "im.src=U;" +
                // safety net: if neither callback fires in 15 s (a blob is
                // in-memory, so this should never trigger), try the hunts
                "setTimeout(function(){if(!im.complete)next();},15000);" +
                "}catch(e){next();}}" +
                // ---- stage 1: plain fetch (exact bytes; CSP may reject) ----
                "function fetchStage(){" +
                "try{" +
                "fetch(U).then(function(r){if(!r.ok)throw 0;return r.blob();})" +
                ".then(function(b){var fr=new FileReader();" +
                "fr.onload=function(){trace('fetch');ok(fr.result,(b&&b.type)||'')};" +
                "fr.onerror=function(){ownStage()};" +
                "fr.readAsDataURL(b);})" +
                ".catch(function(e){ownStage();});" +
                "}catch(e){ownStage();}" +
                "}" +
                // ---- stage 0 (v1.3.58): THE FRAME DANCE — ask every frame
                // for the blob by URL. blob:null URLs are minted inside
                // opaque-origin (sandboxed) iframes whose bytes NO main-frame
                // fetch, img or DOM hunt can ever reach; FrameHooks.kt
                // holds the Blob OBJECT in the minting frame (document-start
                // script, wildcard origin rule) and relays the data URL back
                // up through postMessage — the one channel that crosses
                // opaque origins. Zero cost when the hook isn't installed.
                // (NOTE: no double quotes in these comments — the gate
                // reconstructs the JS by scanning string literals.) ----
                "function readHeld(b){" +
                "var fr=new FileReader();" +
                "fr.onload=function(){trace('frame');ok(fr.result,(b&&b.type)||'')};" +
                "fr.onerror=function(){fetchStage()};" +
                "fr.readAsDataURL(b);" +
                "}" +
                "function frameStage(){" +
                "try{" +
                "if(!window.__boltBlobs)return false;" +
                "}catch(e){return false;}" +
                "var b=window.__boltBlobs[U];" +
                "if(b){readHeld(b);return true;}" +
                "var fr=document.querySelectorAll('iframe');" +
                "if(!fr||!fr.length)return false;" +
                "var got=false;" +
                "function onMsg(e){" +
                "var d=e&&e.data;" +
                "if(!d||d.__bolt!==1||d.t!=='blobData'||d.u!==U||got)return;" +
                "got=true;" +
                "try{window.removeEventListener('message',onMsg);}catch(x){}" +
                "trace('frame');" +
                "ok(d.d,d.mime);" +
                "}" +
                "window.addEventListener('message',onMsg);" +
                "trace('ask');" +
                "for(var i=0;i<fr.length;i++){" +
                "try{fr[i].contentWindow.postMessage({__bolt:1,t:'needBlob',u:U},'*');}catch(x){}" +
                "}" +
                "setTimeout(function(){" +
                "if(got||done)return;" +
                "try{window.removeEventListener('message',onMsg);}catch(x){}" +
                "fetchStage();" +
                "},1200);" +
                "return true;" +
                "}" +
                "if(!frameStage())fetchStage();" +
                "}catch(e){try{Bolt.notify('Download failed');}catch(x){}}})();"
    }
}
