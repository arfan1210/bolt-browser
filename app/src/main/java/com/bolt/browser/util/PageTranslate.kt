package com.bolt.browser.util

import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v1.3.70/71 — IN-PLACE PAGE TRANSLATION (new code → Kotlin; the standing
 * language rule). The v1.3.69-and-older "Translate page" routed the tab to
 * `translate.google.com/translate?…&u=<url>` — a server-side proxy Google
 * retired years ago; what remains of it opens a NEW page, and even in its
 * prime the proxied copy carried no cookies ("logged out of the site" — the
 * v1.3.69 field report). The cure is the same class of move as reader mode:
 * the page is translated WHERE IT STANDS, in the same tab, and only the
 * parts that are actually in another language.
 *
 * v1.3.71 — THE COLLECTOR REBUILD (the v1.3.70 field report: "it fails to
 * detect youtube comments to translate, zai chats are not detected to
 * translate, nothing is working"). The v1.3.70 walk recursed `childNodes`
 * only — blind to SHADOW DOM content, to same-origin IFRAME documents, and
 * its single 900-node session cap disconnected the observer on the first
 * heavy page so late-loaded comments never translated; there was also no
 * viewport priority, so a big page looked dead while the queue crawled.
 * The rebuild follows the working implementations this repo read before
 * writing it (lesson 56):
 *  - Firefox `translations-document.sys.mjs` — every discovered root
 *    (document, title, EACH shadow root) is registered and MUTATION-OBSERVED
 *    separately (`#addShadowRootsToObserver`), and requests are prioritized
 *    by viewport position (its four IntersectionObservers; v2 uses the
 *    two-bucket approximation — in-viewport first, DOM order within);
 *  - read-frog (open-source immersive-translate class) — the walk pierces
 *    OPEN shadow roots (`element.shadowRoot.children`) and its own comment
 *    says why the observer needs the same help: "observers do not pierce
 *    the shadow boundary".
 * Also new: the start diag now carries REJECTION COUNTERS (`shr= ifr= ce=
 * tn= ah= nr= more=`) so the next field report decodes itself, and the
 * session is STICKY — Kotlin re-begins it on onPageFinished (the engine's
 * JS context dies with the old document; the v1.3.70 chat.z.ai "nodes=0"
 * was a restored page still blank at tap time, and the finished load never
 * got the engine).
 *
 * v1.3.72 — THE UNIVERSALITY PASS (the v1.3.71 field report: "the zai
 * chats in chinese is not being detected… it says nothing to translate
 * here… make sure translation works everywhere"). chat.z.ai (an Open
 * WebUI fork) mounts its ENTIRE app in `<div id="app"
 * style="display: contents">` — an element with display:contents generates
 * NO BOX of its own, so `getClientRects()` is empty and the v1.3.71
 * not-rendered filter hard-skipped the WHOLE APP SUBTREE: nodes=0, the
 * "Nothing to translate" toast, on a page full of Chinese (and the
 * English UI bits were invisible to the walk too — the smoking gun that
 * the kill was near the root, not in the chat list). Three cures ship:
 *  - `rendered()` now passes display:contents elements through (a
 *    TRANSPARENT container, not hidden content) and counts them (dc=);
 *  - a ZERO-NODE RESCAN LADDER (1 s/2.5 s/5 s/9 s) replaces the instant
 *    "done empty" — SPAs still streaming, restored tabs still blank get
 *    four more chances; the FINAL pass RELAXES the translate=no and
 *    not-rendered filters (the user asked explicitly — a site that hides
 *    all its text behind those hints still gets translated);
 *  - CLOSED shadow roots: ShadowGate.kt wraps attachShadow at document
 *    start (every frame) and records each minted root into
 *    `window.__boltShr`; the walker reads that registry per document
 *    (cshr= counts the pierces) — the pre-registered lever, shipped.
 *
 * Architecture (four pieces):
 *  1. THE ENGINE (ENGINE below) — an ES5 IIFE evaluated into the live page.
 *     It walks text nodes through the document, OPEN shadow roots and
 *     same-origin iframe documents (skipping script/style/code/pre/svg/
 *     contenteditable/translate=no/not-rendered subtrees; aria-hidden is
 *     counted but NOT skipped — YouTube marks visible comments with it), pre-filters text
 *     already majority target-script, queues the rest IN-VIEWPORT-FIRST,
 *     and swaps each node's text in place — original values are remembered
 *     in a Map so the pill's "show original" reverts exactly. A small fixed
 *     pill shows progress; tap = cancel (while running) / revert (when
 *     done) / retry (on failure). A MutationObserver (re-observing every
 *     discovered root each wave) translates content the page adds later;
 *     a chained wave drains the capped backlog. Detected-source == target
 *     ⇒ the node is left untouched ("the other language parts only").
 *  2. THE SELECTION BOX (SELECTION_JS below) — select text → Translate in
 *     the selection menu: a floating box appears near the selection with
 *     the translation, the transliteration, dictionary senses (the
 *     "meaning", via dt=bd) and a Copy button — replaces the v1.3.70-and-
 *     older behavior of opening translate.google.com in a new tab (which
 *     broke continuity). Uses its own bridge channel (`fetchSel` /
 *     `__boltTxDone2`) so it never fights a running page translation.
 *  3. THE RELAY (`Relay`) — one instance registered per WebView as
 *     `BoltTx` (Tab.buildWeb, same pattern as BoltWebAuthn), because the
 *     shared `Bolt` bridge routes evals to the ACTIVE tab — a user who
 *     switches tabs mid-translation would get another page's results
 *     otherwise. The relay holds a WeakReference to ITS OWN web: results
 *     always land in the tab that asked. It fetches the translation
 *     endpoint off the UI thread on a shared daemon pool and delivers via
 *     `window.__boltTxDone` / `__boltTxDone2`. Doing the HTTP in Kotlin
 *     (not page-side fetch) makes translation work on CSP-strict sites
 *     (the Gemini-class `connect-src` walls that killed v1.3.57's
 *     page-side fetches) and on http pages (no mixed-content wall at all).
 *  4. THE GUARD — the relay will only ever fetch the two translate hosts
 *     (`HOSTS`, https only, no redirects): a bridge the whole web can call
 *     must never become a general-purpose cross-origin proxy (SSRF).
 *     [gtx] exposes the SAME guarded query to Kotlin-side callers (the
 *     v1.3.72 screen translator) — one endpoint, one whitelist, everywhere.
 *
 * Endpoint: Google's free `translate_a/single?client=gtx` (the same one the
 * userscript ecosystem has ridden for a decade). The engine requests the
 * `dj=1` JSON shape but parses BOTH response shapes defensively; failures
 * fail over to the second host, back off on 429, and every outcome lands in
 * the diag log under the `tx ` prefix (`tx start/done/cancel/revert/retry/
 * rearm/sel …`) so field reports decode the same way `fs `/`siteWipe `
 * lines do.
 */
object PageTranslate {

    /** hosts the relay may ever fetch — the SSRF guard. */
    private val HOSTS =
        arrayOf("translate.googleapis.com", "translate.google.com")

    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** one shared pool for every tab's relay (engine runs 4 concurrent
     *  workers; the selection box rides the same pool) */
    private val POOL: ExecutorService = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "bolt-tx").apply { isDaemon = true }
    }

    /** last target language per WebView — the retry hook re-begins with it */
    private val LAST: MutableMap<WebView, String> =
        Collections.synchronizedMap(WeakHashMap<WebView, String>())

    /** v1.3.71 — sticky sessions: while true, onPageFinished re-begins the
     *  translation (the engine's JS context dies with the old document).
     *  Cleared on revert/cancel — the user said stop. */
    private val STICKY: MutableMap<WebView, Boolean> =
        Collections.synchronizedMap(WeakHashMap<WebView, Boolean>())

    /** v1.3.70 — kick off (or restart) translation of THIS web's page.
     * @JvmStatic: BrowserActivity (Java) calls this as
     * PageTranslate.begin(...) — a Kotlin object member is otherwise an
     * INSTANCE method from Java's point of view (javac error at 1.3.70's
     * first build). */
    @JvmStatic
    fun begin(w: WebView, tl: String) {
        try { LAST[w] = tl } catch (_: Throwable) {}
        try { STICKY[w] = true } catch (_: Throwable) {}
        try {
            w.post { try { w.evaluateJavascript(startJs(tl), null) } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
    }

    @JvmStatic
    fun startJs(tl: String): String = ENGINE.replace("__TL__", tl)

    /** v1.3.71 — the device's own language as a translate code (the dialog
     *  and the selection box share it). */
    @JvmStatic
    fun deviceTl(): String {
        val l = try {
            java.util.Locale.getDefault().language ?: ""
        } catch (_: Throwable) { "" }
        if (l.isEmpty()) return "en"
        return if ("zh" == l) "zh-CN" else l
    }

    /** v1.3.71 — the SELECTION box: show text's translation in place (was:
     *  a translate.google.com tab that broke continuity). Uses the last
     *  page-translation target if any, else the device language. */
    @JvmStatic
    fun select(w: WebView, text: String?) {
        if (text.isNullOrEmpty()) return
        val tl = try { LAST[w] } catch (_: Throwable) { null } ?: deviceTl()
        val js = SELECTION_JS.replace("__TL__", tl)
            .replace("__TEXT__", JSONObject.quote(text.take(1500)))
        try {
            w.post { try { w.evaluateJavascript(js, null) } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
    }

    /** v1.3.71 — BoltClient.onPageFinished hook: a sticky session survives
     *  navigation (the restored-page race the v1.3.70 log caught — engine
     *  ran on the blank pre-load document, "nodes=0", and the real page
     *  never got it). No-op unless this web has an active session. */
    @JvmStatic
    fun onPageLoaded(w: WebView) {
        val tl = try { LAST[w] } catch (_: Throwable) { null } ?: return
        if (try { STICKY[w] } catch (_: Throwable) { null } != true) return
        try { Diag.log(w.context, "tx rearm tl=$tl") } catch (_: Throwable) {}
        begin(w, tl)
    }

    /**
     * The in-page engine. ES5, one IIFE, zero dollar signs (Kotlin raw
     * string — a stray dollar would be a template), no Promise
     * (old-WebView safe). `__TL__` is substituted by [startJs];
     * scripts/test_translate.py extracts this literal verbatim and
     * node --checks it, so it can never drift from the tests.
     *
     * v1.3.74 — VIEWPORT-LAZY TRANSLATION (the user's report: "instead of
     * trying to translate the entire page at once, and taking the long
     * time, translate only the portion on screen; when I scroll, translate
     * the new portion — that way it avoids wasting time and power"). The
     * cold queue is GONE: below-the-fold nodes go to S.defer, a capture-
     * phase scroll hub (one listener per document; the live epoch's scan
     * fn is reached through S so epoch restarts never orphan it) drains
     * them as they enter the viewport, and mutation waves push VISIBLE
     * fresh nodes only (style/class flips are now observed too — an
     * accordion reveal fires no childList mutation, and the revealed text
     * was never collected because not-rendered at boot).
     * v1.3.76 — THE DISPATCH FIX + THE IO TAIL. The v1.3.74 report said
 *   "still a bit slower than expected" and the code showed why: step()
 *   fed ONE request per 90 ms tick, so CONC=4 was a fiction — the visible
 *   batch's LAST request waited N*90 ms just to LEAVE (a 40-node viewport
 *   = 3.6 s of pure stagger). step() now refills every free slot in one
 *   pass (CONC=6, STAGGER=45), and the deferred tail drains through an
 *   IntersectionObserver when the platform has one (the scroll-scan
 *   re-read getBoundingClientRect on up to 4000 deferred nodes every
 *   250 ms of scrolling — interleaved with translation DOM writes that
 *   is forced-reflow thrash, the "scrolling lags" half of the report;
 *   IO computes intersections in the browser's own pass, zero scroll
 *   listeners). The scroll hub stays as the no-IO fallback.
 */
    private const val ENGINE = """
(function(){
'use strict';
try{
if(!window.MutationObserver||!document.body||!document.documentElement)return;
var TL='__TL__';
var MAXCHAR=1200,CONC=6,STAGGER=45,INITCAP=1500,NODCAP=6000,PERCAP=3,BACKOFF=2500,DEFMAX=4000,SCROLLD=250;

// ---------- state (survives restarts; first translation wins) ----------
var S=window.__boltTx;
if(!S)S=window.__boltTx={};
if(S.kill)try{S.kill()}catch(e){}
if(!S.orig||typeof S.orig.has!=='function')S.orig=new Map();
if(!S.cnt||typeof S.cnt.has!=='function')S.cnt=new Map();
S.seen=new Set();
S.epoch=(S.epoch||0)+1;
var E=S.epoch;
S.stopped=false;S.fin=false;S.phase=1;S.backUntil=0;S.looping=0;S.rscan=0;S.defer=[];S.done1=false;
S.observed=new Set();
try{if(S.io)S.io.disconnect()}catch(e){}
S.io=null;
try{if(S.mo)S.mo.disconnect()}catch(e){}
S.mo=null;
function alive(){return S.epoch===E&&!S.stopped}

// ---------- character classes (manual — anchor-free, old-WebView safe) ----------
function isWs(c){return c==' '||c=='\n'||c=='\t'||c=='\r'||c=='\f'||c=='\v'||c=='\u00a0'||c=='\u200b'||c=='\u3000'||c=='\ufeff'}
function isLetter(c){var x=c.charCodeAt(0);
  return (x>=0x41&&x<=0x5A)||(x>=0x61&&x<=0x7A)||x==0xAA||x==0xB5||x==0xBA
  ||(x>=0xC0&&x<=0x2AF)||(x>=0x370&&x<=0x6FF)||(x>=0x700&&x<=0x7FF)
  ||(x>=0x840&&x<=0x8FF)||(x>=0x900&&x<=0xDFF)||(x>=0xE00&&x<=0x10FF)
  ||(x>=0x1100&&x<=0x11FF)||(x>=0x1200&&x<=0x137F)||(x>=0x1780&&x<=0x17FF)
  ||(x>=0x1E00&&x<=0x1FFF)||(x>=0x2C60&&x<=0x2C7F)||(x>=0xA720&&x<=0xA7FF)
  ||(x>=0x3040&&x<=0x30FF)||(x>=0x3400&&x<=0x4DBF)||(x>=0x4E00&&x<=0x9FFF)
  ||(x>=0xA000&&x<=0xA4CF)||(x>=0xAC00&&x<=0xD7AF)||(x>=0xF900&&x<=0xFAFF)}
function hasLetter(s){for(var i=0;i<s.length;i++)if(isLetter(s.charAt(i)))return true;return false}

// the target's OWN script (only where unique — Latin-script targets rely on
// the response's detected language instead, so French text on an English
// target is still offered to Google and skipped by its own answer)
var TRN={'bn':[[0x0980,0x09FF]],'hi':[[0x0900,0x097F]],
 'ar':[[0x0600,0x06FF],[0x0750,0x077F]],'ur':[[0x0600,0x06FF],[0x0750,0x077F]],
 'ru':[[0x0400,0x04FF]],'zh-CN':[[0x3400,0x4DBF],[0x4E00,0x9FFF]],
 'ja':[[0x3040,0x30FF]],'ko':[[0xAC00,0xD7AF],[0x1100,0x11FF]]};
var TR=TRN[TL]||null;
function inTr(c){if(!TR)return false;var x=c.charCodeAt(0);
  for(var i=0;i<TR.length;i++)if(x>=TR[i][0]&&x<=TR[i][1])return true;return false}
function fracTr(s){var L=0,T=0;
  for(var i=0;i<s.length;i++){var c=s.charAt(i);if(isLetter(c)){L++;if(inTr(c))T++}}
  return L>0?T/L:1}

// ---------- whitespace-preserving core split ----------
function coreSplit(s){
  var a=0,b=s.length;
  while(a<b&&isWs(s.charAt(a)))a++;
  while(b>a&&isWs(s.charAt(b-1)))b--;
  return [s.slice(0,a),s.slice(a,b),s.slice(b)]}

// ---------- sentence split for long cores ----------
function splitCore(s){
  if(s.length<=MAXCHAR)return [s];
  var out=[],i=0;
  while(i<s.length){
    if(s.length-i<=MAXCHAR){out.push(s.slice(i));break}
    var win=s.slice(i,i+MAXCHAR),cut=-1;
    for(var k=win.length-1;k>60;k--){
      var c=win.charAt(k);
      if(c=='.'||c=='?'||c=='!'||c==';'||c==':'||c=='\u0964'||c=='\u3002'||c=='\uff01'||c=='\uff1f'){cut=k+1;break}}
    if(cut<0)cut=MAXCHAR;
    out.push(s.slice(i,i+cut));i+=cut}
  return out}

// ---------- collector v2 (v1.3.71: pierces shadow roots + same-origin
// iframes; counts rejections so field reports decode themselves) ----------
var SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,TEMPLATE:1,TEXTAREA:1,CODE:1,PRE:1,
 KBD:1,SAMP:1,TT:1,XMP:1,PLAINTEXT:1,SVG:1,MATH:1,CANVAS:1,VIDEO:1,
 AUDIO:1,OBJECT:1,EMBED:1,META:1,LINK:1,BASE:1};
var REJ={ce:0,tn:0,ah:0,nr:0,dc:0};
// v1.3.72: true only during the FINAL zero-node rescan — translate=no and
// not-rendered bow to it (the user explicitly asked for a translation)
var RELAX=false;
var NROOTS=0,NIFR=0,NREG=0;
function attr(el,n){try{return el.getAttribute(n)}catch(e){return null}}
function rendered(el,t){
  if(t=='OPTION'||t=='TITLE')return true;
  try{
    var r=el.getClientRects?el.getClientRects():null;
    if(!r||r.length>0)return true;
    // v1.3.72: display:contents generates NO box of its own (empty client
    // rects) but its children ARE rendered — chat.z.ai mounts the whole
    // app in <div id=app style="display: contents"> and the v1.3.71 walk
    // hard-skipped that subtree (nodes=0 on a page full of Chinese). A
    // contents element is a TRANSPARENT container, not hidden content.
    var v=null;try{v=(el.ownerDocument&&el.ownerDocument.defaultView)||window}catch(e2){}
    var cs=null;try{cs=(v&&v.getComputedStyle)?v.getComputedStyle(el,null):null}catch(e3){}
    if(cs&&String(cs.display||'')=='contents'){REJ.dc++;return true}
    return false
  }catch(e){return true}}
function okText(node){
  var raw=node.nodeValue||'';
  if(raw.length<2)return false;
  if(S.orig.has(node))return false;
  if((S.cnt.get(node)||0)>=PERCAP)return false;
  var p=coreSplit(raw),core=p[1];
  if(core.length<2)return false;
  if(!hasLetter(core))return false;
  if(TR&&fracTr(core)>0.9)return false;
  return true}
function walk(node,skip,out,roots){
  if(!node)return;
  if(node.nodeType==3){if(!skip&&okText(node))out.push(node);return}
  // v1.3.71: shadow roots / document fragments are walkable containers
  // (a ShadowRoot is nodeType 11 — the v1.3.70 walk returned early on it)
  if(node.nodeType==11){var kf=node.childNodes;
    for(var j=0;j<kf.length;j++)walk(kf[j],skip,out,roots);return}
  if(node.nodeType!=1)return;
  var t=node.nodeName||'';
  var sk=skip||SKIP[t];
  if(!sk&&node.id=='__boltTxPill')sk=true;
  if(!sk&&node.id=='__boltTxSel')sk=true;
  if(!sk){
    var ce=attr(node,'contenteditable');
    if(ce=='true'||ce==''){sk=true;REJ.ce++}
    // v1.3.72: translate=no / not-rendered bow to the RELAX pass (the
    // final zero-node rescan) — a site that hides ALL its text behind
    // those hints still gets the translation the user asked for
    if(!sk&&!RELAX&&attr(node,'translate')=='no'){sk=true;REJ.tn++}
    // v1.3.71: aria-hidden is COUNTED, not skipped — the live m.youtube
    // test caught YouTube marking the fully-VISIBLE comment bodies
    // (<p class=YtmCommentRendererText aria-hidden=true>): aria-hidden
    // means "out of the accessibility tree", NOT "invisible", and neither
    // Firefox's translations engine nor read-frog skips it (they check
    // real visibility). The not-rendered check owns invisibility.
    if(!sk&&attr(node,'aria-hidden')=='true')REJ.ah++
    if(!sk&&!RELAX&&!rendered(node,t)){sk=true;REJ.nr++}}
  if(sk)return;
  // same-origin iframe documents (cross-origin contentDocument is null /
  // throws — counted nowhere, skipped safely). v1.3.72: walkDoc also
  // reads the IFRAME's OWN attachShadow registry (ShadowGate is registered
  // document-start in every frame, so each frame records its own roots).
  if(t=='IFRAME'){
    try{
      var cd=node.contentDocument;
      if(cd&&cd.documentElement){NIFR++;if(roots)roots.push(cd);walkDoc(cd,out,roots)}
    }catch(e){}
    return}
  // OPEN shadow roots (Firefox's #addShadowRootsToObserver pattern)
  var sr=null;try{sr=node.shadowRoot}catch(e){}
  if(sr){NROOTS++;if(roots)roots.push(sr);walk(sr,false,out,roots)}
  var kids=node.childNodes;
  for(var i=0;i<kids.length;i++)walk(kids[i],sk,out,roots)}

// v1.3.72 — walk ONE document plus every root ShadowGate recorded in
// that document's own window (`window.__boltShr`, both open and closed;
// closed roots are otherwise unreadable BY DESIGN — the wrap is the only
// universal way in). Deduped per pass; disconnected hosts skipped.
function walkDoc(doc,out,roots){
  try{walk(doc.documentElement,false,out,roots)}catch(e){}
  var a=null;
  try{var v=doc.defaultView;a=v?(v.__boltShr||null):null}catch(e2){}
  if(a&&a.length){
    var seen=null;
    try{seen=new Set()}catch(e3){}
    for(var i=0;i<a.length;i++){
      var r=null;try{r=a[i]}catch(e4){}
      if(!r)continue;
      // isConnected===false (strict): hosts without the property (old
      // WebViews) stay walkable — a bare falsy check would skip them all
      try{if(r.host&&r.host.isConnected===false)continue}catch(e5){}
      if(seen){if(seen.has(r))continue;try{seen.add(r)}catch(e6){}}
      NREG++;
      if(roots)roots.push(r);
      try{walk(r,false,out,roots)}catch(e7){}}}}

// ---------- viewport priority (Firefox's intersection observers, the
// two-bucket approximation: in-viewport first, DOM order within) ----------
function inView(node){
  try{
    var p=node.parentNode;
    if(!p||!p.getBoundingClientRect)return true;
    var r=p.getBoundingClientRect();
    if(!r)return true;
    var vw=(window.innerWidth||(document.documentElement&&document.documentElement.clientWidth))||360;
    var vh=(window.innerHeight||(document.documentElement&&document.documentElement.clientHeight))||640;
    return r.bottom>-120&&r.top<vh+120&&r.right>-120&&r.left<vw+120
  }catch(e){return true}}
function splitHot(arr){
  var hot=[],cold=[],i;
  for(i=0;i<arr.length;i++){if(inView(arr[i]))hot.push(arr[i]);else cold.push(arr[i])}
  return {h:hot,c:cold}}

// ---------- v1.3.74: the scroll hub + the viewport scanner ----------
// ONE capture-phase scroll listener per document (it catches the page
// scroll AND every nested overflow scroller — scroll does not bubble,
// but capture passes through document). The handler reaches the LIVE
// epoch's scan through window.__boltTx, so retries never orphan it and
// a stale epoch's closure is never called again.
var scrollFn=null;
function armScrollOn(doc){
  try{
    if(!doc||!doc.addEventListener)return;
    if(doc.__boltTxScrollArmed)return;
    doc.__boltTxScrollArmed=1;
    doc.addEventListener('scroll',scrollFn,true)}catch(e){}}
function armScroll(){
  if(!scrollFn){
    var last=0,tmo=null;
    scrollFn=function(){
      var wait=last+SCROLLD-Date.now();
      if(wait<=0){
        if(tmo){try{clearTimeout(tmo)}catch(e){}tmo=null}
        last=Date.now();
        var s=window.__boltTx;
        if(s&&!s.stopped&&typeof s.scan=='function'){try{s.scan()}catch(e){}}
        return}
      if(!tmo)tmo=setTimeout(function(){
        tmo=null;last=Date.now();
        var s=window.__boltTx;
        if(s&&!s.stopped&&typeof s.scan=='function'){try{s.scan()}catch(e){}}
      },wait)}}
  armScrollOn(document);
  try{window.addEventListener('scroll',scrollFn)}catch(e){}}

// ---------- v1.3.76: the IO tail (the scroll-lag cure) ----------
// When the platform has IntersectionObserver, deferred nodes are OBSERVED
// once and the browser itself reports the ones entering the viewport (its
// own render pass — no JS rect reads, no scroll-listener work, no
// forced-reflow interleaving with the translation's own DOM writes).
// Without IO the v1.3.74 scroll-scan below stays the fallback.
var USEIO=('IntersectionObserver' in window);
function ioEnsure(){
  if(S.io||!USEIO)return S.io;
  try{
    S.io=new IntersectionObserver(function(ents){
      if(!alive()||S.stopped||!ents||!ents.length)return;
      var hot=[],i;
      for(i=0;i<ents.length;i++){
        var en=ents[i];
        if(!en||!en.isIntersecting)continue;
        var n=en.target;
        try{S.io.unobserve(n)}catch(e){}
        if(!S.seen.has(n))hot.push(n)}
      if(hot.length){
        var dd=dedupe(hot),q0=QHOT.length;
        for(i=0;i<dd.length;i++)push(dd[i],true);
        var added=QHOT.length-q0;
        if(added>0){
          totalQ+=added;
          S.fin=false;
          pill=ensurePill();
          pillState(1);
          try{BoltTx.diag('io push='+added)}catch(e){}
          kick()}}},
    {rootMargin:'140px 0px'});
  }catch(e){USEIO=false;S.io=null}
  return S.io}
// push a batch of cold nodes into S.defer (with the DEFMAX cap) and, when
// IO is live, observe exactly the ones that landed — returns the dropped
// count so the boot diag can still say drop=N
function stashCold(arr){
  if(!arr||!arr.length)return 0;
  var i,kept=0;
  for(i=0;i<arr.length;i++){
    if(S.defer.length>=DEFMAX)break;
    S.defer.push(arr[i]);kept++}
  if(kept&&USEIO){
    var io=ioEnsure();
    if(io)for(i=0;i<kept;i++){try{io.observe(arr[i])}catch(e){}}}
  return arr.length-kept}
// drain the deferred tail WITHOUT IO: dead nodes are dropped, visible ones
// are pushed as hot, the rest stay deferred for the next scroll
function scanVisible(){
  if(!alive()||S.stopped)return;
  // v1.3.76: IO owns the tail when present — the hub stays armed as a
  // fallback but must never double-drain
  if(USEIO)return;
  var d=S.defer;
  if(!d||!d.length)return;
  if(S.seen.size>NODCAP){
    S.defer=[];
    try{if(S.mo)S.mo.disconnect()}catch(e){}
    try{if(S.io)S.io.disconnect()}catch(e){}S.io=null;
    try{BoltTx.diag('cap nodes='+S.seen.size)}catch(e2){}
    return}
  var keep=[],hot=[],i;
  for(i=0;i<d.length;i++){
    var n=d[i];
    if(!n||!n.parentNode)continue;
    if(!S.seen.has(n)&&inView(n))hot.push(n);
    else keep.push(n)}
  S.defer=keep;
  if(!hot.length)return;
  var dd=dedupe(hot),q0=QHOT.length,i2;
  for(i2=0;i2<dd.length;i2++)push(dd[i2],true);
  var added=QHOT.length-q0;
  if(added>0){
    totalQ+=added;
    S.fin=false;
    pill=ensurePill();
    pillState(1);
    try{BoltTx.diag('scan push='+added+' defer='+keep.length)}catch(e){}
    kick()}}

// ---------- counters ----------
var QHOT=[],QCOLD=[],busy=0,okReq=0,keptReq=0,failReq=0,totalQ=0,t0=Date.now(),e429=0,ex=0;

// ---------- the pill ----------
function pillTap(){
  if(S.phase==1)cancel();
  else if(S.phase==2){if(okReq+keptReq>0)revert()}
  else if(S.phase==3){try{BoltTx.diag('retry')}catch(e){}}}
function ensurePill(){
  var d=null;
  try{d=document.getElementById('__boltTxPill')}catch(e){}
  if(!d){
    d=document.createElement('div');
    d.id='__boltTxPill';
    try{d.style.cssText='position:fixed;left:10px;bottom:calc(10px + env(safe-area-inset-bottom,0px));z-index:2147483647;background:rgba(20,21,24,0.87);color:#fff;font:600 12px/1.4 system-ui,sans-serif;padding:7px 12px;border-radius:999px;box-shadow:0 2px 10px rgba(0,0,0,0.35);cursor:pointer;user-select:none;-webkit-user-select:none;touch-action:manipulation;max-width:62vw;white-space:nowrap;overflow:hidden;text-overflow:ellipsis'}catch(e){}
    try{document.body.appendChild(d)}catch(e){}}
  try{d.onclick=pillTap}catch(e){}
  return d}
function pillState(ph){
  if(!alive())return;
  S.phase=ph;
  if(ph==1)pill.textContent='Translating\u2026 0%';
  else if(ph==2)pill.textContent='\u2713 Translated \u00b7 tap for original';
  else if(ph==3)pill.textContent='\u2717 Translation failed \u00b7 tap to retry'}
function pillRun(){
  if(!alive())return;
  var total=okReq+keptReq+failReq;
  if(totalQ>0)pill.textContent='Translating\u2026 '+Math.round(100*total/totalQ)+'%';
  else pill.textContent='Translating\u2026'}
function hide(){try{if(pill&&pill.parentNode)pill.parentNode.removeChild(pill)}catch(e){}}

// ---------- relay plumbing (Kotlin-side HTTP, CSP/CORS-proof) ----------
var CB={},cbn=0;
window.__boltTxDone=function(id,ok,body){
  var r=CB[id];
  if(!r)return;
  if(ok){delete CB[id];try{r.cb(body)}catch(e){}return}
  var m=String(body||'');
  if(r.tries<2&&m.indexOf('blocked')<0){
    r.tries++;
    r.url=r.url.indexOf('translate.googleapis.com')>=0?
      r.url.replace('translate.googleapis.com','translate.google.com'):
      r.url.replace('translate.google.com','translate.googleapis.com');
    if(m.indexOf('429')>=0){e429++;S.backUntil=Date.now()+BACKOFF}else{ex++}
    var wait=m.indexOf('429')>=0?BACKOFF:350;
    setTimeout(function(){
      if(!CB[id]||S.stopped||S.epoch!==E)return;
      setTimeout(function(){var rr=CB[id];if(rr){delete CB[id];try{rr.cb(null)}catch(x){}}},14000);
      try{BoltTx.fetch(r.url,id)}catch(e){delete CB[id];try{r.cb(null)}catch(x){}}},wait);
    return}
  delete CB[id];
  if(m.indexOf('429')>=0)e429++;else ex++;
  try{r.cb(null)}catch(e){}};
function ask(url,cb){
  var id='b'+(++cbn);
  CB[id]={cb:cb,url:url,tries:1};
  setTimeout(function(){var r=CB[id];if(r){delete CB[id];try{r.cb(null)}catch(e){}}},14000);
  try{BoltTx.fetch(url,id)}catch(e){delete CB[id];cb(null)}}

// ---------- response parser (both endpoint shapes, defensively) ----------
function parseTx(b){
  try{
    var j=JSON.parse(b);
    if(j&&j.sentences&&j.sentences.length){
      var s='';
      for(var i=0;i<j.sentences.length;i++){var q=j.sentences[i];if(q&&typeof q.trans=='string')s+=q.trans}
      return {t:s,src:(typeof j.src=='string')?j.src:''}}
    if(j&&j[0]&&j[0].length){
      var s2='';
      for(var k=0;k<j[0].length;k++){var g=j[0][k];if(g&&typeof g[0]=='string')s2+=g[0]}
      return {t:s2,src:(j&&typeof j[2]=='string')?j[2]:''}}
  }catch(e){}
  return null}
function sameLang(a,b){
  if(!a||!b)return false;
  a=a.toLowerCase();b=b.toLowerCase();
  if(a==b)return true;
  return a.split('-')[0]==b.split('-')[0]}

// ---------- the worker pool (hot queue first, then the rest) ----------
function push(node,hot){
  if(S.seen.has(node))return false;
  S.seen.add(node);
  var raw=node.nodeValue||'';
  var p=coreSplit(raw);
  var parts=splitCore(p[1]);
  var h={n:node,lead:p[0],tail:p[2],raw:raw,got:0,parts:parts.length,res:new Array(parts.length)};
  for(var i=0;i<parts.length;i++)(hot?QHOT:QCOLD).push({h:h,pi:i,text:parts[i]});
  return true}
function nextIt(){
  if(QHOT.length)return QHOT.shift();
  if(QCOLD.length)return QCOLD.shift();
  return null}
function run(it){
  var url='https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl='+TL+'&dt=t&dj=1&q='+encodeURIComponent(it.text);
  ask(url,function(body){
    busy--;
    if(!alive())return;
    if(body==null){failReq++}
    else{
      var r=parseTx(body);
      if(!r){failReq++}
      else{
        var keep=sameLang(r.src,TL);
        if(keep)keptReq++;else okReq++;
        var h=it.h;
        h.res[it.pi]=keep?it.text:r.t;
        h.got++;
        if(h.got==h.parts){
          if(h.n.parentNode&&h.n.nodeValue===h.raw){
            if(!S.orig.has(h.n))S.orig.set(h.n,h.raw);
            S.quiet=Date.now()+80;
            try{h.n.nodeValue=h.lead+h.res.join('')+h.tail}catch(e){}
            S.cnt.set(h.n,(S.cnt.get(h.n)||0)+1)}
          else{S.seen.delete(h.n)}}}}
    pillRun();
    kick()})}
function kick(){
  if(S.stopped||S.looping===E)return;
  S.looping=E;
  setTimeout(step,0)}
// v1.3.72: identity dedupe for collected nodes (the registry walk can
// reach an OPEN root both via element.shadowRoot and via __boltShr)
function dedupe(arr){
  var u=null,o=[],i;
  try{u=new Set()}catch(e){}
  for(i=0;i<arr.length;i++){var x=arr[i];
    if(u){if(u.has(x))continue;u.add(x)}
    else{var d=false;for(var j=0;j<o.length;j++)if(o[j]===x){d=true;break}if(d)continue}
    o.push(x)}
  return o}
// the zero-node rescan ladder (v1.3.72 — see finish())
var RD=[1000,2500,5000,9000];
function step(){
  if(!alive()){if(S.looping===E)S.looping=0;return}
  if(S.backUntil&&Date.now()<S.backUntil){setTimeout(step,S.backUntil-Date.now()+30);return}
  // v1.3.76: refill EVERY free slot in one pass — the v1.3.74 tick fed
  // one request per 90 ms, so a 40-node viewport waited 3.6 s before its
  // LAST request even left ("slower than expected"); slots now fill to
  // CONC and every completion's kick() refills what it freed
  while(busy<CONC){
    var it=nextIt();
    if(!it)break;
    busy++;
    run(it)}
  if(QHOT.length||QCOLD.length||busy>0){setTimeout(step,STAGGER);return}
  if(S.looping===E)S.looping=0;
  finish()}
function finish(){
  if(!alive()||S.fin)return;
  S.fin=true;
  var ms=Date.now()-t0;
  // v1.3.74: 'done2' on every finish after the first — the Kotlin side
  // toasts on 'done ' only, and a scroll-driven session would toast on
  // every viewport batch otherwise (toast spam while scrolling)
  try{BoltTx.diag((S.done1?'done2 ':'done ')+'ok='+okReq+' kept='+keptReq+' fail='+failReq+' ms='+ms+' e429='+e429+' ex='+ex)}catch(e){}
  S.done1=true;
  var total=okReq+keptReq+failReq;
  if(total==0){
    // v1.3.74: the page HAS translatable text — just not in THIS
    // viewport. Stay armed (scroll pulls the next batch); no ladder, no
    // 'done empty' toast — the report was about a page full of content.
    if(S.defer&&S.defer.length){
      S.phase=2;
      pill.textContent='\u2713 In view: nothing to translate \u00b7 scroll';
      return}
    // v1.3.72: ZERO queueable nodes is usually a RACE (SPA still
    // streaming, restored tab still blank) or an over-strict filter —
    // rescan on a rising ladder before declaring the page empty. The
    // FINAL pass RELAXES translate=no / not-rendered (explicit user ask
    // beats site hints). Diag: 'rscan i= relax= nodes='.
    if(S.rscan<RD.length){
      S.rscan++;
      pill.textContent='Reading the page\u2026';
      setTimeout(function(){
        if(!alive())return;
        RELAX=S.rscan>=RD.length;
        S.fin=false;
        var out=[],roots=[];
        walkDoc(document,out,roots);
        observeRoots(roots);
        var fresh=dedupe(out),i;
        var ff=[],k;
        for(k=0;k<fresh.length;k++){if(!S.seen.has(fresh[k]))ff.push(fresh[k])}
        try{BoltTx.diag('rscan i='+S.rscan+' relax='+(RELAX?1:0)+' nodes='+ff.length)}catch(e2){}
        if(ff.length){
          var sh=splitHot(ff);
          var q0=QHOT.length;
          for(i=0;i<sh.h.length;i++)push(sh.h[i],true);
          stashCold(sh.c);
          var added=QHOT.length-q0;
          RELAX=false;
          if(added>0){
            totalQ+=added;
            pill=ensurePill();
            pillState(1);
            kick();
            return}
          finish();
          return}
        RELAX=false;
        finish()
      },RD[S.rscan-1]);
      return}
    try{BoltTx.diag('done empty')}catch(e){}
    pill.textContent='\u2713 Nothing to translate';
    setTimeout(hide,1600)}
  else if(okReq==0&&keptReq==0){
    pillState(3)}
  else{
    pillState(2);
    if(S.defer&&S.defer.length)pill.textContent='\u2713 Translated \u00b7 scroll for more';
    else if(failReq>0)pill.textContent='\u2713 Translated ('+failReq+' failed) \u00b7 tap for original'}
  // v1.3.71's backlog pump is GONE (v1.3.74): the scroll scanner owns
  // the deferred tail now — nothing translates until it is looked at
}

// ---------- cancel / revert ----------
function cancel(){
  if(!alive())return;
  S.stopped=true;
  try{BoltTx.diag('cancel got='+(okReq+keptReq+failReq)+' of '+totalQ)}catch(e){}
  pill.textContent='Cancelled';
  setTimeout(hide,1400)}
function revert(){
  if(!alive())return;
  S.stopped=true;
  var n=0;
  S.quiet=Date.now()+600;
  S.orig.forEach(function(v,k){try{if(k.nodeValue!==v){k.nodeValue=v}n++}catch(e){}});
  try{BoltTx.diag('revert n='+n)}catch(e){}
  S.orig=new Map();
  pill.textContent='Showing original';
  setTimeout(hide,1200)}

// ---------- the observer (lazy content keeps translating; v1.3.71:
// re-discovers and re-observes shadow roots / iframe docs every wave;
// v1.3.74: attributes TOO — a reveal that only flips style/class fires
// no childList mutation, and the revealed text was skipped at boot as
// not-rendered, so without this it would NEVER translate) ----------
var OBS={childList:true,characterData:true,subtree:true,
 attributes:true,attributeFilter:['style','class','hidden','open']};
var waveT=null;
function observeRoots(roots){
  if(!S.mo||!roots)return;
  for(var i=0;i<roots.length;i++){
    var r=roots[i];
    if(!r||S.observed.has(r))continue;
    try{S.observed.add(r);S.mo.observe(r,OBS)}catch(e){}
    // v1.3.74: iframe documents get their OWN scroll hub — a scroll
    // inside an embedded frame never reaches the parent document's
    // capture listener, but its deferred text must still drain
    try{if(r.nodeType==9)armScrollOn(r)}catch(e2){}}}
function queueWave(){
  if(waveT)return;
  waveT=setTimeout(function(){
    waveT=null;
    if(!alive())return;
    if(S.seen.size>NODCAP){try{if(S.mo)S.mo.disconnect()}catch(e){}try{if(S.io)S.io.disconnect()}catch(e2){}S.defer=[];try{BoltTx.diag('cap nodes='+S.seen.size)}catch(e3){}return}
    var out=[],roots=[];
    walkDoc(document,out,roots);
    observeRoots(roots);
    // only UNSEEN nodes count — a mutation-heavy page re-presents the same
    // text constantly (the live m.youtube test caught the v1.3.71-draft
    // bug: no-op waves re-finished every 600 ms and spammed done/toasts)
    var fresh=[],i;
    var dd=dedupe(out);
    for(i=0;i<dd.length;i++){if(!S.seen.has(dd[i]))fresh.push(dd[i])}
    if(fresh.length){
      // v1.3.74: mutation waves are viewport-lazy too — visible fresh
      // text translates now, below-the-fold additions join S.defer and
      // wait for their scroll (the old WAVEBUD pump translated
      // everything the page ever mutated in)
      var sh=splitHot(fresh);
      var q0=QHOT.length;
      for(i=0;i<sh.h.length;i++)push(sh.h[i],true);
      stashCold(sh.c);
      var added=QHOT.length-q0;
      if(added>0){
        totalQ+=added;
        pill=ensurePill();
        S.fin=false;
        pillState(1);
        kick()}}
  },600)}
function armObserver(){
  try{
    if(!window.MutationObserver)return null;
    var mo=new MutationObserver(function(){
      if(Date.now()<(S.quiet||0))return;
      queueWave()});
    mo.observe(document.documentElement,OBS);
    return mo}catch(e){return null}}

// ---------- boot ----------
var pill=ensurePill();
pillState(1);
S.kill=function(){S.stopped=true};
S.quiet=Date.now()+120;
S.scan=scanVisible;
var out=[],roots=[];
walkDoc(document,out,roots);
var sh=splitHot(dedupe(out));
// v1.3.74: ONLY the viewport translates at boot — the rest of the page
// waits in S.defer until it is scrolled into view (the user's power
// saving; INITCAP still bounds the pathological all-fixed page)
var n=Math.min(sh.h.length,INITCAP);
var drop=0,i,k;
for(i=0;i<n;i++)push(sh.h[i],true);
// v1.3.76: the cold tail goes through stashCold (defer + IO observe when
// the platform has IntersectionObserver — the scroll-lag cure)
drop+=stashCold(sh.h.slice(n));
drop+=stashCold(sh.c);
totalQ=QHOT.length+QCOLD.length;
S.mo=armObserver();
observeRoots(roots);
armScroll();
try{BoltTx.diag('start tl='+TL+' nodes='+n+' parts='+totalQ+' defer='+S.defer.length+(drop?' drop='+drop:'')+' shr='+NROOTS+' ifr='+NIFR+' ce='+REJ.ce+' tn='+REJ.tn+' ah='+REJ.ah+' nr='+REJ.nr+' dc='+REJ.dc+' cshr='+NREG)}catch(e){}
if(totalQ==0){
  if(S.defer.length){S.phase=2;pill.textContent='\u2713 In view: nothing to translate \u00b7 scroll'}
  else finish();
  return}
kick();
}catch(e){try{if(window.BoltTx)BoltTx.diag('err '+String(e&&e.message||e).substring(0,120))}catch(x){}}
})()
"""

    /**
     * The selection-box payload (v1.3.71). ES5 IIFE; `__TL__` and
     * `__TEXT__` are substituted by [select] (the text arrives as a
     * JSON-quoted literal via JSONObject.quote). Shows a floating box near
     * the selection with the translation, transliteration, dictionary
     * senses (dt=bd — the "meaning") and Copy; dismiss on outside tap /
     * Escape; a second invoke replaces the first. Uses the relay's SECOND
     * channel (`fetchSel` / `__boltTxDone2`) so it never collides with a
     * running page translation.
     */
    private const val SELECTION_JS = """
(function(){
'use strict';
try{
if(!document.body)return;
var TL='__TL__';
var TXT=__TEXT__;
var W=window;
function mk(tag,css,txt){var d=document.createElement(tag);try{d.style.cssText=css}catch(e){}if(txt!=null)d.textContent=txt;return d}
if(W.__boltTxSelKill)try{W.__boltTxSelKill()}catch(e){}
if(!W.__boltTxSelCB)W.__boltTxSelCB={};
W.__boltTxDone2=function(id,ok,body){
  var r=W.__boltTxSelCB[id];
  if(!r)return;
  delete W.__boltTxSelCB[id];
  try{r(ok?body:null)}catch(e){}};
var box=mk('div','position:fixed;left:0;top:0;z-index:2147483647;max-width:88vw;width:340px;background:rgba(17,18,22,0.95);color:#fff;font:13px/1.5 system-ui,-apple-system,sans-serif;border-radius:14px;box-shadow:0 8px 30px rgba(0,0,0,0.45);overflow:hidden;-webkit-user-select:text;user-select:text');
box.id='__boltTxSel';
var hd=mk('div','display:flex;align-items:center;padding:9px 12px 6px;font:600 10px/1 system-ui;color:#9aa0ab;letter-spacing:0.4px;text-transform:uppercase');
var lang=mk('span',null,'Translating');
hd.appendChild(lang);
var xBtn=mk('div','margin-left:auto;cursor:pointer;padding:2px 6px;color:#9aa0ab;font-size:14px;line-height:1','\u2715');
hd.appendChild(xBtn);
var body=mk('div','padding:2px 12px 10px;font-size:14px;line-height:1.55;max-height:34vh;overflow:auto;white-space:pre-wrap;word-break:break-word');
body.textContent='\u2026';
box.appendChild(hd);
box.appendChild(body);
document.body.appendChild(box);
var x=null,y=null;
try{
  var sel=W.getSelection?W.getSelection():null;
  if(sel&&sel.rangeCount){
    var r=sel.getRangeAt(0).getBoundingClientRect();
    if(r&&(r.width>0||r.height>0)){x=r.left;y=r.bottom+10}}
}catch(e){}
try{
  var bw=box.offsetWidth||300,vw=W.innerWidth||360,vh=W.innerHeight||640;
  if(x==null){x=(vw-bw)/2;y=vh-(box.offsetHeight||120)-24}
  if(x+bw>vw-8)x=vw-8-bw;
  if(x<8)x=8;
  box.style.left=Math.round(x)+'px';
  box.style.top=Math.round(Math.max(8,y))+'px'
}catch(e){}
function kill(){
  try{document.removeEventListener('pointerdown',outTap,true)}catch(e){}
  try{document.removeEventListener('touchstart',outTap,true)}catch(e){}
  try{document.removeEventListener('keydown',keyHit,true)}catch(e){}
  try{if(box.parentNode)box.parentNode.removeChild(box)}catch(e){}
  if(W.__boltTxSelKill===kill)W.__boltTxSelKill=null}
function outTap(ev){try{if(ev.target&&box.contains&&!box.contains(ev.target))kill()}catch(e){}}
function keyHit(ev){if((ev&&ev.key=='Escape')||(ev&&ev.keyCode==27))kill()}
try{document.addEventListener('pointerdown',outTap,true)}catch(e){}
try{document.addEventListener('touchstart',outTap,true)}catch(e){}
try{document.addEventListener('keydown',keyHit,true)}catch(e){}
W.__boltTxSelKill=kill;
xBtn.onclick=kill;
var q='';
try{q=encodeURIComponent(TXT)}catch(e){}
if(q.length>6000){try{TXT=TXT.slice(0,1000);q=encodeURIComponent(TXT)}catch(e2){}}
var url='https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl='+TL+'&dt=t&dt=bd&dj=1&q='+q;
var id='s'+((W.__boltTxSelN=(W.__boltTxSelN||0)+1));
W.__boltTxSelCB[id]=function(b){
  if(b==null){
    body.textContent='Translation failed \u2014 tap \u2715 to close';
    try{if(W.BoltTx)BoltTx.diag('sel fail')}catch(e){}}
  else{
    var j=null;try{j=JSON.parse(b)}catch(e){}
    var t='',tr='',src='',dict=null,i,k;
    if(j&&j.sentences&&j.sentences.length){
      for(i=0;i<j.sentences.length;i++){var s=j.sentences[i]||{};
        if(typeof s.trans=='string')t+=s.trans;
        if(typeof s.translit=='string'&&s.translit)tr=s.translit;
        if(typeof s.src_translit=='string'&&s.src_translit)tr=s.src_translit}
      src=(typeof j.src=='string')?j.src:'';
      if(j.dict&&j.dict.length)dict=j.dict}
    else if(j&&j[0]&&j[0].length){
      for(k=0;k<j[0].length;k++){var g=j[0][k];if(g&&typeof g[0]=='string')t+=g[0]}
      src=(j&&typeof j[2]=='string')?j[2]:'';
      if(j&&j[1]&&j[1].length)dict=j[1]}
    if(!t&&dict&&dict[0]&&dict[0].terms&&dict[0].terms.length)t=dict[0].terms[0];
    lang.textContent=((src||'?')+' \u2192 '+TL).toUpperCase();
    body.textContent=t||'(no translation)';
    if(tr)body.appendChild(mk('div','margin-top:4px;color:#9aa0ab;font-size:12px','\u2022 '+tr));
    if(dict){
      var dl=mk('div','margin-top:8px;padding-top:8px;border-top:1px solid rgba(255,255,255,0.14)');
      for(var d=0;d<dict.length&&d<4;d++){
        var e2=dict[d]||{};
        var pos=e2.pos||'';
        var terms=(e2.terms||[]).slice(0,4).join(', ');
        if(pos||terms)dl.appendChild(mk('div','color:#c8cdd6;font-size:12px;line-height:1.6',(pos?pos+': ':'')+terms))}
      if(dl.childNodes.length)body.appendChild(dl)}
    var ft=mk('div','display:flex;gap:10px;padding:8px 12px;border-top:1px solid rgba(255,255,255,0.10)');
    var cp=mk('div','cursor:pointer;color:#8ab4ff;font:600 12px/1 system-ui;padding:5px 9px;border-radius:8px;background:rgba(138,180,255,0.12)','Copy');
    cp.onclick=function(){
      try{
        var ta=document.createElement('textarea');
        ta.value=t||'';
        ta.style.cssText='position:fixed;left:-9999px;top:0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
        cp.textContent='Copied \u2713';
        setTimeout(function(){try{cp.textContent='Copy'}catch(e){}},1200)
      }catch(e){}};
    ft.appendChild(cp);
    var cl=mk('div','cursor:pointer;color:#9aa0ab;font:600 12px/1 system-ui;padding:5px 9px','Close');
    cl.onclick=kill;
    ft.appendChild(cl);
    box.appendChild(ft);
    try{
      var vh2=W.innerHeight||640,bh2=box.offsetHeight||120,ny=parseFloat(box.style.top)||0;
      if(ny+bh2>vh2-8){ny=Math.max(8,vh2-8-bh2);box.style.top=Math.round(ny)+'px'}
    }catch(e){}
    try{if(W.BoltTx)BoltTx.diag('sel ok src='+src+' len='+t.length)}catch(e){}}};
try{
  if(W.BoltTx&&BoltTx.fetchSel){
    BoltTx.fetchSel(url,id);
    try{BoltTx.diag('sel show len='+TXT.length+' tl='+TL)}catch(e5){}}
  else body.textContent='Translation unavailable'
}catch(e){body.textContent='Translation failed'}
}catch(e){try{if(window.BoltTx)BoltTx.diag('sel err '+String(e&&e.message||e).substring(0,100))}catch(x6){}}
})()
"""

    /**
     * The per-WebView relay (`window.BoltTx`). One instance per tab — the
     * shared Bolt bridge evals into the ACTIVE tab, which would misroute a
     * backgrounded translation; this one always answers the web that asked.
     */
    class Relay(web: WebView) {
        private val wref = WeakReference(web)

        /** page asked for a translation URL — fetch it off-thread, answer
         *  into OUR OWN web via window.__boltTxDone. Host-whitelisted. */
        @JavascriptInterface
        fun fetch(url: String, cb: String) {
            POOL.execute {
                val r = httpGet(url)
                deliver(cb, r.first, r.second)
            }
        }

        /** v1.3.71 — the SELECTION box's channel (own callback namespace,
         *  so it never collides with a running page translation). */
        @JavascriptInterface
        fun fetchSel(url: String, cb: String) {
            POOL.execute {
                val r = httpGet(url)
                deliverSel(cb, r.first, r.second)
            }
        }

        /** the engine's evidence channel — 'tx ' diag lines + the small set
         *  of user-facing outcomes (toast / retry hook). */
        @JavascriptInterface
        fun diag(line: String) {
            val w = wref.get() ?: return
            val l = if (line.length > 300) line.substring(0, 300) else line
            try { Diag.log(w.context, "tx $l") } catch (_: Throwable) {}
            // v1.3.71: revert/cancel end the STICKY session — the user said
            // stop, so onPageFinished must not re-arm it
            if (l.startsWith("revert") || l.startsWith("cancel")) {
                try { STICKY[w] = false } catch (_: Throwable) {}
                return
            }
            // v1.3.70 retry: the error pill's tap re-begins with the last tl
            if (l == "retry") {
                val tl = LAST[w]
                if (tl != null) begin(w, tl)
                return
            }
            // user-facing outcomes (the start toast comes from the activity)
            if (l == "done empty") {
                w.post { try { Fx.toast(w.context, "Nothing to translate here") } catch (_: Throwable) {} }
                return
            }
            if (l.startsWith("done ")) {
                val ok = intArg(l, "ok=")
                val kept = intArg(l, "kept=")
                val fail = intArg(l, "fail=")
                val msg = if (ok <= 0 && kept <= 0 && fail > 0)
                    "Translation failed \u2014 tap the pill to retry"
                else
                    "Translated \u2713 \u2014 tap the pill for the original"
                w.post { try { Fx.toast(w.context, msg) } catch (_: Throwable) {} }
            }
        }

        private fun intArg(line: String, key: String): Int {
            return try {
                val i = line.indexOf(key)
                if (i < 0) 0
                else line.substring(i + key.length)
                    .takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            } catch (_: Throwable) { 0 }
        }

        private fun deliver(cb: String, ok: Boolean, body: String) {
            val w = wref.get() ?: return
            val js = ("window.__boltTxDone&&window.__boltTxDone("
                    + JSONObject.quote(cb) + "," + (if (ok) "true" else "false")
                    + "," + JSONObject.quote(body) + ")")
            try {
                w.post { try { w.evaluateJavascript(js, null) } catch (_: Throwable) {} }
            } catch (_: Throwable) {}
        }

        private fun deliverSel(cb: String, ok: Boolean, body: String) {
            val w = wref.get() ?: return
            val js = ("window.__boltTxDone2&&window.__boltTxDone2("
                    + JSONObject.quote(cb) + "," + (if (ok) "true" else "false")
                    + "," + JSONObject.quote(body) + ")")
            try {
                w.post { try { w.evaluateJavascript(js, null) } catch (_: Throwable) {} }
            } catch (_: Throwable) {}
        }
    }

    /** Kotlin-side HTTP for the relay. https + HOSTS only, no redirects
     *  (the SSRF guard); gzip-aware; capped reads; never throws. */
    private fun httpGet(raw: String): Pair<Boolean, String> {
        var c: HttpURLConnection? = null
        return try {
            val u = URL(raw)
            if (!"https".equals(u.protocol, ignoreCase = true) ||
                HOSTS.none { it.equals(u.host, ignoreCase = true) }
            ) return Pair(false, "blocked")
            c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("Referer", "https://translate.google.com/")
            val code = try { c.responseCode } catch (_: Throwable) { -1 }
            val gz = "gzip".equals(c.contentEncoding, ignoreCase = true)
            val stream: InputStream? =
                if (code in 200..299) c.inputStream else c.errorStream
            val body = readBounded(stream, gz)
            if (code in 200..299) Pair(true, body)
            else Pair(false, "HTTP " + code)
        } catch (e: Throwable) {
            Pair(false, ("ERR " + (e.message ?: e.javaClass.simpleName)).take(60))
        } finally {
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun readBounded(s: InputStream?, gz: Boolean): String {
        if (s == null) return ""
        return try {
            val inn: InputStream = if (gz) java.util.zip.GZIPInputStream(s) else s
            val buf = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var n = inn.read(buf)
            while (n >= 0 && out.size() < 2_000_000) {
                out.write(buf, 0, n)
                n = inn.read(buf)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        } catch (_: Throwable) { "" }
    }

    /**
     * v1.3.72 — a DIRECT gtx query for Kotlin-side callers (the screen
     * translator's overlay). Same endpoint, same [httpGet] SSRF guard,
     * both response shapes parsed, host fail-over built in — one endpoint,
     * one whitelist, everywhere. Returns (translation|null, detectedSource).
     * BLOCKING: call from a worker thread only.
     */
    @JvmStatic
    fun gtx(text: String, tl: String): Pair<String?, String> {
        val t = text.trim()
        if (t.isEmpty()) return Pair(null, "")
        val q = try {
            java.net.URLEncoder.encode(t.take(1500), "UTF-8")
        } catch (_: Throwable) { return Pair(null, "") }
        val path = ("translate_a/single?client=gtx&sl=auto&tl=" + tl +
                "&dt=t&dj=1&q=" + q)
        for (h in HOSTS) {
            val r = httpGet("https://$h/$path")
            if (!r.first) continue
            val p = parseGtx(r.second) ?: continue
            return p
        }
        return Pair(null, "")
    }

    /** both gtx response shapes (dj=1 object / legacy nested array). */
    private fun parseGtx(b: String): Pair<String?, String>? {
        val s = b.trim()
        if (s.isEmpty()) return null
        return try {
            if (s.startsWith("[")) {
                val a = org.json.JSONArray(s)
                val seg = a.optJSONArray(0) ?: return null
                val sb = StringBuilder()
                for (i in 0 until seg.length()) {
                    val g = seg.optJSONArray(i) ?: continue
                    sb.append(g.optString(0, ""))
                }
                if (sb.isEmpty()) return null
                val src = if (a.length() > 2 && !a.isNull(2)) a.optString(2) else ""
                Pair(sb.toString(), src)
            } else {
                val o = JSONObject(s)
                val seg = o.optJSONArray("sentences") ?: return null
                val sb = StringBuilder()
                for (i in 0 until seg.length()) {
                    val so = seg.optJSONObject(i) ?: continue
                    sb.append(so.optString("trans", ""))
                }
                if (sb.isEmpty()) return null
                Pair(sb.toString(), o.optString("src", ""))
            }
        } catch (_: Throwable) { null }
    }
}
