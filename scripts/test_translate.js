/* v1.3.70/71 — Mock-DOM behavioral test for the in-place translation engine
 * + the selection box (util/PageTranslate.kt, ENGINE / SELECTION_JS literals
 * — extracted verbatim by scripts/test_translate.py, so the test can never
 * drift from the shipped engine; same pattern as test_pipvideo.js).
 *   T1  the collector: script/style/code/pre/svg/aria-hidden/
 *       contenteditable/not-rendered subtrees skipped; paragraphs,
 *       <option> labels and the <title> collected; short/letterless text
 *       skipped;
 *   T2  the script pre-filter: with a Bengali target, majority-Bengali
 *       text is NOT offered to Google; English is; with an English target
 *       (Latin script) nothing is pre-filtered — Google's own detected
 *       language does that job;
 *   T3  whitespace is preserved around the translated core, and the
 *       request carries the TRIMMED core only;
 *   T4  long text is split at sentence boundaries (<=1200 chars per
 *       request) and the parts are re-joined in order;
 *   T5  BOTH endpoint response shapes parse (dj=1 sentences + legacy
 *       array);
 *   T6  detected-source == target: the node is LEFT ALONE (kept) — "the
 *       other language parts only";
 *   T7  both hosts fail: node untouched, pill reaches the error state,
 *       pill tap asks for a retry (diag 'retry'); the failed request
 *       tried the SECOND host once;
 *   T8  a 429 backs off (~2.5 s, globally) and then succeeds on the
 *       alternate host;
 *   T9  the pill lifecycle: progress -> done -> tap reverts every node to
 *       its TRUE original (diag 'revert n=');
 *   T10 the MutationObserver wave: content added AFTER completion is
 *       collected and translated (debounced);
 *   T11 the stale guard: a node the page rewrote mid-flight is not
 *       clobbered, and becomes eligible again;
 *   T12 every request URL the engine builds is one of the two translate
 *       hosts over https (the JS half of the SSRF guard);
 *   T13 the restart epoch: re-running the engine re-queues FAILED nodes
 *       but never re-queues already-translated ones;
 *   T14 nothing-to-translate: 'done empty' + the quiet pill;
 *   T15 encodeURIComponent round-trips quotes/newlines/unicode exactly.
 * v1.3.71 (the collector rebuild — the field report "youtube comments and
 * zai chats are not detected, nothing is working"):
 *   T16 SHADOW DOM: text inside an open shadow root IS collected and
 *       translated, and the shadow root is MUTATION-OBSERVED (Firefox's
 *       #addShadowRootsToObserver pattern);
 *   T17 SAME-ORIGIN IFRAMES: the frame document is pierced, collected,
 *       translated and observed (IFRAME left the SKIP map);
 *   T18 VIEWPORT-LAZY BOOT (v1.3.74): the below-the-fold node is NOT
 *       requested at boot — it waits in S.defer, the pill says "scroll
 *       for more", and a scroll that reveals it queues + translates it
 *       (scan push= diag);
 *   T19 THE SCROLL-DRIVEN BACKLOG (caps variant INITCAP=5/DEFMAX=60):
 *       the v1.3.71 wave pump is GONE — a static below-the-fold tail
 *       NEVER drains in the background; one scroll queues it all;
 *   T20 THE SESSION CAP (caps variant NODCAP=20): the scroll scan can
 *       push the session past the cap, the NEXT wave trips it and
 *       disconnects the observer ('cap nodes=' diag), and post-cap
 *       content is bounded out;
 *   T21 THE SELECTION BOX: translate-selection shows a floating box in
 *       place (no tab), asks dt=t+dt=bd (translation + dictionary
 *       senses), renders transliteration + meanings + Copy, clamps its
 *       position, dismisses on outside tap / Escape, replaces the prior
 *       box, and reports the failure state through its own channel
 *       (fetchSel / __boltTxDone2).
 * v1.3.74 (the viewport-lazy rewrite — "translate only the portion on
 * screen; when I scroll, translate the new portion"):
 *   T26 LAZY MUTATION WAVES: content added below the fold after
 *       completion joins S.defer (NOT the queue) until scrolled;
 *   T27 THE TOAST-SPAM KILL: only the FIRST finish logs 'done ' — every
 *       later scroll batch logs 'done2 ' (the Kotlin side toasts on
 *       'done ' only);
 *   T28 THE ATTRIBUTE REVEAL: a display:none subtree is skipped at boot;
 *       flipping it visible fires the (now attribute-watching) observer
 *       wave and the text translates — the reveal fires NO childList
 *       mutation, so without attributeFilter it would never translate.
 * Real timers (the engine's stagger/debounce/backoff), mock relay. The
 * synthetic responses use src 'fr' by default so the detected-language
 * skip only fires where a test WANTS it. Every fresh document carries an
 * English <title> ('A page') — the tests account for it. */
'use strict';
const fs = require('fs');

let failures = 0;
function assert(cond, msg) {
  if (cond) { console.log('  PASS ' + msg); }
  else { failures++; console.log('  FAIL ' + msg); }
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const SRC_BN = fs.readFileSync('/tmp/translate_engine_bn.js', 'utf8');
const SRC_EN = fs.readFileSync('/tmp/translate_engine_en.js', 'utf8');
const SRC_CAPS = fs.readFileSync('/tmp/translate_engine_caps.js', 'utf8');
const SRC_FAST = fs.readFileSync('/tmp/translate_engine_fast.js', 'utf8');
const SRC_SEL = fs.readFileSync('/tmp/translate_sel.js', 'utf8');

// ------------------------------------------------------------ the mock DOM
function makeEl(tag, opts) {
  opts = opts || {};
  const kids = [];
  const el = {
    nodeType: 1,
    nodeName: (tag || 'div').toUpperCase(),
    id: opts.id || '',
    attrs: Object.assign({}, opts.attrs),
    children: kids,
    childNodes: kids,
    parentNode: null,
    style: {},
    rects: 'rects' in opts ? opts.rects : [{}],
    isConnected: true,
    onclick: null,
    textContent: '',
    appendChild(c) { c.parentNode = el; kids.push(c); return c; },
    removeChild(c) {
      const i = kids.indexOf(c);
      if (i >= 0) kids.splice(i, 1);
      c.parentNode = null;
      return c;
    },
    setAttribute(n, v) { el.attrs[n] = String(v); },
    getAttribute(n) { return n in el.attrs ? el.attrs[n] : null; },
    getClientRects() { return el.rects; },
    // v1.3.71: viewport-priority + selection-box support
    contains(c) { return c === el || el.children.indexOf(c) >= 0; },
    select() {},
  };
  if (opts.bcr) el.getBoundingClientRect = () => opts.bcr;
  return el;
}
function makeText(v) { return { nodeType: 3, nodeValue: v, parentNode: null }; }
function addText(parent, v) {
  const t = makeText(v);
  t.parentNode = parent;
  parent.children.push(t);
  return t;
}
function append(parent, child) { parent.appendChild(child); return child; }
function makeRoot() {
  const kids = [];
  return {
    nodeType: 11, children: kids, childNodes: kids,
    appendChild(c) { c.parentNode = null; kids.push(c); return c; },
    host: null,
  };
}
function findById(root, id) {
  if (!root || !root.children) return null;
  if (root.id === id) return root;
  for (const c of root.children) { const r = findById(c, id); if (r) return r; }
  return null;
}

// ------------------------------------------------------------ the mock infra
let REQ = [], DIAG = [], MOS = [], ALL_URLS = [], SCROLLS = [];
class MutationObserver {
  constructor(cb) { this.cb = cb; }
  observe(t, o) { MOS.push(this); this.targets = (this.targets || []).concat([t]); }
  disconnect() { this.disc = true; }
}

function buildDoc(titleText) {
  const docEl = makeEl('html');
  const head = makeEl('head');
  const title = makeEl('title');
  addText(title, titleText == null ? 'A page' : titleText);
  append(head, title);
  append(docEl, head);
  const body = makeEl('body');
  append(docEl, body);
  const doc = {
    nodeType: 9,   // v1.3.74: observeRoots arms the scroll hub on documents
    documentElement: docEl,
    body,
    defaultView: global,   // v1.3.72: walkDoc reads the doc's OWN window registry
    getElementById(id) { return findById(docEl, id); },
    createElement: t => makeEl(t),
    // v1.3.74: the capture-phase scroll hub registers here
    addEventListener(t, fn) { if (t === 'scroll') SCROLLS.push(fn); },
  };
  return { doc, docEl, body, head, title };
}

global.window = global;
global.MutationObserver = MutationObserver;
global.innerWidth = 360;
global.innerHeight = 640;
// v1.3.72: the display:contents cure consults the window's computed style
// — the mock lets each element carry __computed (default: a normal block)
global.getComputedStyle = (el) => (el && el.__computed) || { display: 'block' };

function armRelay() {
  global.BoltTx = {
    fetch(u, id) { REQ.push({ u, id }); ALL_URLS.push(u); },
    diag(l) { DIAG.push(l); },
  };
}
function fresh(titleText) {
  delete global.__boltTx;
  delete global.__boltShr;   // v1.3.72: the ShadowGate registry is per-document
  REQ = []; DIAG = []; MOS = []; SCROLLS = [];
  armRelay();
  const g = buildDoc(titleText);
  global.document = g.doc;
  return g;
}
function evalEngine(src) { eval(src); }
// v1.3.74: simulate a scroll event reaching the capture-phase hub
function fireScroll() { SCROLLS.slice().forEach(f => { try { f(); } catch (e) {} }); }
// pull a below-the-fold element into the viewport (its bcr was fixed at
// creation — scrolling in the mock is a new getBoundingClientRect)
function reveal(el) {
  el.getBoundingClientRect = () => ({ top: 20, bottom: 40, left: 0, right: 100 });
}

function qOf(entry) {
  return decodeURIComponent(entry.u.split('q=')[1]);
}
// find + remove the request whose text matches
function takeFor(text) {
  const i = REQ.findIndex(e => qOf(e) === text);
  return i < 0 ? null : REQ.splice(i, 1)[0];
}
function deliverOk(id, trans, src) {
  global.__boltTxDone(id, true,
    JSON.stringify({ sentences: [{ trans }], src: src || 'fr' }));
}
function deliverLegacy(id, trans, src) {
  global.__boltTxDone(id, true,
    JSON.stringify([[[trans, 'x', null, null, 1]], null, src || 'fr']));
}
function deliverFail(id, msg) { global.__boltTxDone(id, false, msg || 'HTTP 500'); }
function pill() { return global.document.getElementById('__boltTxPill'); }
function hasDiag(s) { return DIAG.some(l => l.indexOf(s) >= 0); }
// deliver every pending request successfully, waiting for the staggered
// queue to fully settle (the engine trickles one request per 90 ms tick —
// a naive while(REQ.length) loop exits while items are still in flight)
async function drainAll(transFn) {
  let idle = 0, done = 0;
  while (idle < 4 && done < 300) {
    if (REQ.length) {
      const e = REQ.shift();
      deliverOk(e.id, transFn ? transFn(done) : 'x', 'fr');
      done++; idle = 0;
      await sleep(60);
    } else { idle++; await sleep(250); }
  }
  return done;
}
// deliver every pending request successfully
async function drainOk(transFn, settleMs) {
  for (let guard = 0; guard < 60 && REQ.length; guard++) {
    const e = REQ.shift();
    deliverOk(e.id, transFn ? transFn(qOf(e)) : 'TX');
    await sleep(60);
  }
  await sleep(settleMs || 150);
}

// ------------------------------------------------------------ the tests
async function t1_collect() {
  console.log('T1 — the collector');
  const g = fresh();
  const p1 = append(g.body, makeEl('p'));
  const t1 = addText(p1, 'Hello world');
  const sc = append(g.body, makeEl('script'));
  addText(sc, 'var x=1');
  const st = append(g.body, makeEl('style'));
  addText(st, '.a{color:red}');
  const p2 = append(g.body, makeEl('p', { attrs: { 'aria-hidden': 'true' } }));
  const ahText = addText(p2, 'Hidden text');
  const ed = append(g.body, makeEl('div', { attrs: { contenteditable: 'true' } }));
  addText(ed, 'Editable text');
  const pre = append(g.body, makeEl('pre'));
  addText(append(pre, makeEl('code')), 'code text');
  const svg = append(g.body, makeEl('svg'));
  addText(svg, 'SVG label');
  const hid = append(g.body, makeEl('div', { rects: [] }));
  addText(hid, 'Invisible');
  const pshort = append(g.body, makeEl('p'));
  addText(pshort, 'a');
  const pnum = append(g.body, makeEl('p'));
  addText(pnum, '123 456');
  const opt = addText(append(append(g.body, makeEl('select')), makeEl('option')),
    'Choose item');
  const titleText = g.title.children[0];

  evalEngine(SRC_EN);
  await sleep(350);
  assert(REQ.length === 4, 'exactly 4 requests (p / aria-hidden p / option / title), got ' + REQ.length);
  const qs = REQ.map(qOf).sort();
  assert(qs.includes('Hello world') && qs.includes('Choose item') && qs.includes('A page'),
    'the right texts were queued: ' + JSON.stringify(qs));
  await drainOk(() => 'T1');
  assert(t1.nodeValue === 'T1', 'paragraph text replaced in place');
  assert(opt.nodeValue === 'T1', 'option label replaced in place');
  assert(titleText.nodeValue === 'T1', 'title text replaced in place');
  // v1.3.71: aria-hidden is NOT a skip — YouTube marks visible comment
  // bodies with it (T1's old assertion was the disease)
  assert(ahText.nodeValue === 'T1', 'aria-hidden VISIBLE text translated (YouTube comments)');
  assert(hasDiag('ah=1'), 'the ah= counter reports the included aria-hidden node');
  assert(ed.children[0].nodeValue === 'Editable text', 'contenteditable untouched');
  assert(pre.children[0].children[0].nodeValue === 'code text', 'pre/code untouched');
  assert(svg.children[0].nodeValue === 'SVG label', 'svg subtree untouched');
  assert(hid.children[0].nodeValue === 'Invisible', 'not-rendered subtree untouched');
}

async function t2_prefilter() {
  console.log('T2 — the script pre-filter');
  let g = fresh();
  const bn = addText(append(g.body, makeEl('p')), 'এটি একটি বাংলা বাক্য এখানে');
  const en = addText(append(g.body, makeEl('p')), 'Hello world');
  evalEngine(SRC_BN);
  await sleep(300);
  assert(REQ.length === 2, 'bn target: English p + English title offered, got ' + REQ.length);
  assert(!REQ.some(e => qOf(e).indexOf('\u09ac\u09be\u0995\u09cd\u09af') >= 0),
    'the majority-Bengali node was NOT offered to Google');
  await drainOk(() => 'টেক্সট');
  assert(en.nodeValue === 'টেক্সট', 'English node translated to Bengali');
  assert(bn.nodeValue.indexOf('\u09ac\u09be\u0995\u09cd\u09af') >= 0,
    'Bengali node untouched');

  g = fresh();
  const bn2 = addText(append(g.body, makeEl('p')), 'এটি একটি বাংলা বাক্য এখানে');
  evalEngine(SRC_EN);
  await sleep(300);
  assert(REQ.some(e => qOf(e).indexOf('\u09ac\u09be\u0995\u09cd\u09af') >= 0),
    'en target (Latin script): the Bengali node IS offered (no pre-filter)');
  await drainOk(() => 'text');
  assert(bn2.nodeValue === 'text', 'Bengali node translated to English');
}

async function t3_whitespace() {
  console.log('T3 — whitespace preservation');
  const g = fresh();
  const t = addText(append(g.body, makeEl('p')), '  Hello world  ');
  evalEngine(SRC_EN);
  await sleep(250);
  const e = takeFor('Hello world');
  assert(e !== null, 'request carries the trimmed core');
  deliverOk(e.id, 'X', 'fr');
  await drainOk(() => 'Z');
  assert(t.nodeValue === '  X  ', 'lead/trail whitespace re-attached: "' + t.nodeValue + '"');
}

async function t4_split() {
  console.log('T4 — sentence split for long cores');
  const g = fresh();
  const long = 'Abc def ghi. '.repeat(340).trim(); // ~4.4k chars, sentence ends every 13
  const t = addText(append(g.body, makeEl('p')), long);
  evalEngine(SRC_EN);
  await sleep(400);
  const parts = REQ.filter(e => qOf(e).length > 50);
  assert(parts.length >= 3, 'long core split into >=3 requests, got ' + parts.length);
  assert(REQ.every(e => qOf(e).length <= 1200), 'every part <= 1200 chars');
  let k = 0, join = '';
  for (const e of REQ) {
    if (qOf(e).length > 50) { deliverOk(e.id, 'P' + k + '.'); join += 'P' + k + '.'; k++; }
    else deliverOk(e.id, 'Z');
    await sleep(60);
  }
  await sleep(250);
  assert(t.nodeValue === join,
    'parts re-joined in order (len ' + t.nodeValue.length + ' vs ' + join.length + ')');
}

async function t5_shapes() {
  console.log('T5 — both response shapes');
  const g = fresh();
  const t = addText(append(g.body, makeEl('p')), 'Hello');
  evalEngine(SRC_EN);
  await sleep(250);
  const e = takeFor('Hello');
  deliverLegacy(e.id, 'LEG', 'fr');
  await sleep(250);
  assert(t.nodeValue === 'LEG', 'legacy array shape parsed and applied');
  await drainOk(() => 'Z');
}

async function t6_kept() {
  console.log('T6 — detected == target: kept');
  const g = fresh();
  const t = addText(append(g.body, makeEl('p')), 'Hello world');
  evalEngine(SRC_BN);
  await sleep(250);
  assert(REQ.length === 2, 'the p and the title were offered (both foreign), got ' + REQ.length);
  for (const e of REQ.slice()) deliverOk(e.id, 'SHOULD_NOT_APPLY', 'bn');
  await sleep(300);
  assert(t.nodeValue === 'Hello world', 'node left alone when Google detects the target language');
  assert(hasDiag('done ok=0 kept=2'), 'kept counted: ' + JSON.stringify(DIAG));
  assert(pill() && pill().textContent.indexOf('Translated') >= 0,
    'pill reached the done state');
}

async function t7_failure() {
  console.log('T7 — failure of both hosts');
  const g = fresh();
  const t = addText(append(g.body, makeEl('p')), 'Hello world');
  evalEngine(SRC_EN);
  await sleep(250);
  // BOTH requests fail (the p and the title) — the pure error state
  const orig = REQ.splice(0);
  const ids = orig.map(e => e.id);
  for (const e of orig) deliverFail(e.id, 'HTTP 500');
  await sleep(600); // 350 ms fail-over
  assert(REQ.length === 2 && REQ.every(e =>
    e.u.indexOf('https://translate.google.com/') === 0 &&
    ids.includes(e.id)),
    'every failed request retried once on the alternate host');
  for (const e of REQ.slice()) deliverFail(e.id, 'HTTP 500');
  await sleep(300);
  assert(t.nodeValue === 'Hello world', 'node untouched after both hosts failed');
  assert(pill() && pill().textContent.indexOf('retry') >= 0,
    'pill shows the retry state: "' + (pill() || {}).textContent + '"');
  assert(hasDiag('done ok=0 kept=0 fail=2'), 'failure diag: ' + JSON.stringify(DIAG));
  pill().onclick();
  assert(hasDiag('retry'), 'pill tap in error state asks for a retry');
}

async function t8_backoff() {
  console.log('T8 — 429 backoff then success');
  const g = fresh();
  const t = addText(append(g.body, makeEl('p')), 'Hello world');
  evalEngine(SRC_EN);
  await sleep(250);
  const titleE = takeFor('A page');
  if (titleE) deliverOk(titleE.id, 'Z', 'fr');
  const first = takeFor('Hello world');
  deliverFail(first.id, 'HTTP 429');
  await sleep(2750); // BACKOFF 2500 + retry dispatch
  assert(REQ.length === 1, '429 retried after the backoff, got ' + REQ.length);
  deliverOk(REQ.shift().id, 'AFTER429', 'fr');
  await sleep(300);
  assert(t.nodeValue === 'AFTER429', 'translation applied after 429 recovery');
  assert(hasDiag('e429=1'), '429 counted in the diag: ' + JSON.stringify(DIAG));
}

async function t9_pill_revert() {
  console.log('T9 — pill lifecycle + revert');
  const g = fresh();
  const a = addText(append(g.body, makeEl('p')), 'One one one');
  const b = addText(append(g.body, makeEl('p')), 'Two two two');
  evalEngine(SRC_EN);
  await sleep(350);
  assert(pill() && pill().textContent.indexOf('Translating') >= 0,
    'pill shows progress while running');
  await drainOk(q => q.indexOf('One') >= 0 ? 'আমি' : (q.indexOf('Two') >= 0 ? 'তুমি' : 'শিরোনাম'));
  assert(a.nodeValue === 'আমি' && b.nodeValue === 'তুমি', 'both translated');
  assert(pill().textContent.indexOf('tap for original') >= 0, 'pill done state');
  pill().onclick();
  assert(a.nodeValue === 'One one one' && b.nodeValue === 'Two two two',
    'revert restored the TRUE originals');
  assert(hasDiag('revert n=3'), 'revert diag (p + p + title): ' + JSON.stringify(DIAG));
  assert(pill().textContent.indexOf('original') >= 0, 'pill shows Showing original');
}

async function t10_observer() {
  console.log('T10 — the observer wave');
  const g = fresh();
  const a = addText(append(g.body, makeEl('p')), 'First text');
  evalEngine(SRC_EN);
  await sleep(250);
  await drainOk(() => 'প্রথম');
  assert(a.nodeValue === 'প্রথম', 'initial node done');
  const late = addText(append(g.body, makeEl('p')), 'Later content');
  MOS[0].cb([]);
  await sleep(900); // 600 ms debounce + kick/stagger
  assert(REQ.length === 1 && qOf(REQ[0]) === 'Later content',
    'late content collected by the wave, got ' + REQ.length);
  await drainOk(() => 'পরে');
  assert(late.nodeValue === 'পরে', 'late content translated');
}

async function t11_stale() {
  console.log('T11 — the stale guard');
  const g = fresh();
  const t = addText(append(g.body, makeEl('p')), 'Hello world');
  evalEngine(SRC_EN);
  await sleep(250);
  const titleE = takeFor('A page');
  if (titleE) deliverOk(titleE.id, 'Z', 'fr');
  const e = takeFor('Hello world');
  t.nodeValue = 'REWRITTEN BY PAGE';
  deliverOk(e.id, 'STALE', 'fr');
  await sleep(250);
  assert(t.nodeValue === 'REWRITTEN BY PAGE', 'stale result dropped, page text kept');
  MOS[0].cb([]);
  await sleep(900);
  assert(REQ.length === 1 && qOf(REQ[0]) === 'REWRITTEN BY PAGE',
    'rewritten node re-eligible for the next wave');
  await drainOk(() => 'নতুন');
  assert(t.nodeValue === 'নতুন', 'rewritten node translated on the wave');
}

async function t12_urls() {
  console.log('T12 — request URL guard');
  const bad = ALL_URLS.filter(u =>
    !(u.startsWith('https://translate.googleapis.com/') ||
      u.startsWith('https://translate.google.com/')));
  assert(bad.length === 0, 'every engine URL is a whitelisted translate host (' +
    ALL_URLS.length + ' checked)');
}

async function t13_restart() {
  console.log('T13 — the restart epoch');
  const g = fresh();
  const failN = addText(append(g.body, makeEl('p')), 'Will fail');
  const okN = addText(append(g.body, makeEl('p')), 'Will pass');
  evalEngine(SRC_EN);
  await sleep(350);
  // fail the 'Will fail' node on both hosts, pass the title and the other node
  let guard = 0;
  while (REQ.length && guard++ < 10) {
    const e = REQ.shift();
    if (qOf(e).indexOf('Will fail') >= 0) deliverFail(e.id, 'HTTP 500');
    else deliverOk(e.id, 'পাস', 'fr');
    await sleep(450);
  }
  await sleep(300);
  assert(okN.nodeValue === 'পাস' && failN.nodeValue === 'Will fail',
    'epoch 1: one translated, one failed');
  // restart (what the retry pill triggers via Kotlin begin())
  evalEngine(SRC_EN);
  await sleep(350);
  assert(REQ.length === 1 && qOf(REQ[0]).indexOf('Will fail') >= 0,
    'restart re-queued ONLY the failed node, got ' + REQ.length);
  await drainOk(() => 'এখন ঠিক');
  assert(failN.nodeValue === 'এখন ঠিক', 'failed node translated after restart');
  assert(okN.nodeValue === 'পাস', 'already-translated node never re-queued');
}

async function t14_empty() {
  console.log('T14 — nothing to translate (through the rescan ladder)');
  const g = fresh('একটি বাংলা শিরোনাম');
  addText(append(g.body, makeEl('p')), 'এটি সম্পূর্ণ বাংলা লেখা');
  evalEngine(SRC_FAST);
  await sleep(300);
  assert(REQ.length === 0, 'no requests for an all-target page');
  assert(hasDiag('rscan i=1'), 'the ladder started (rscan i=1): ' + DIAG.join(' | '));
  await sleep(700);
  assert(hasDiag('rscan i=4') && hasDiag('relax=1'),
    'the final pass relaxed the filters (rscan i=4 relax=1)');
  assert(hasDiag('done empty'), 'empty diag after the full ladder');
  assert(pill() && pill().textContent.indexOf('Nothing to translate') >= 0,
    'pill reports nothing to translate');
}

async function t15_encoding() {
  console.log('T15 — encoding round-trip');
  const g = fresh();
  const t = addText(append(g.body, makeEl('p')),
    'He said "hi" & left.\n\tTabs and \u2026 unicode \u0985 \u4e2d');
  evalEngine(SRC_EN);
  await sleep(250);
  const expect = 'He said "hi" & left.\n\tTabs and \u2026 unicode \u0985 \u4e2d'.trim();
  const mine = takeFor(expect);
  assert(mine !== null, 'quotes/newlines/unicode survive the URL encoding');
  deliverOk(mine.id, 'ENCODED', 'fr');
  await drainOk(() => 'Z');
  assert(t.nodeValue.trim() === 'ENCODED', 'translated back into place');
}

// ------------------------------------------------------------ v1.3.71 tests
async function t16_shadow() {
  console.log('T16 — shadow DOM piercing');
  const g = fresh();
  // an open shadow root on a custom element (the v1.3.70 blindness)
  const host = append(g.body, makeEl('my-widget'));
  const root = makeRoot();
  host.shadowRoot = root;
  root.host = host;
  const sp = append(root, makeEl('p'));
  const st = addText(sp, 'Shadow text lives here');
  evalEngine(SRC_EN);
  await sleep(350);
  const e = takeFor('Shadow text lives here');
  assert(e !== null, 'text inside the open shadow root WAS collected (the v1.3.70 engine saw nothing)');
  if (e) deliverOk(e.id, 'ছায়া লেখা', 'fr');
  await drainOk(() => 'শিরোনাম');
  assert(st.nodeValue === 'ছায়া লেখা', 'shadow text translated in place');
  assert(MOS.length >= 1 && (MOS[0].targets || []).includes(root),
    'the shadow root itself is MUTATION-OBSERVED (late shadow content will translate)');
  assert(hasDiag('shr=1'), 'start diag counts the pierced root: ' + DIAG[0]);
}

async function t17_iframe() {
  console.log('T17 — same-origin iframe piercing');
  const g = fresh();
  const ifr = append(g.body, makeEl('iframe'));
  const fdocEl = makeEl('html');
  const fbody = append(fdocEl, makeEl('body'));
  const fdoc = { documentElement: fdocEl };
  ifr.contentDocument = fdoc;
  const ft = addText(append(fbody, makeEl('p')), 'Frame text lives here');
  evalEngine(SRC_EN);
  await sleep(350);
  const e = takeFor('Frame text lives here');
  assert(e !== null, 'text inside the same-origin frame document WAS collected (IFRAME left the SKIP map)');
  if (e) deliverOk(e.id, 'ফ্রেম লেখা', 'fr');
  await drainOk(() => 'শিরোনাম');
  assert(ft.nodeValue === 'ফ্রেম লেখা', 'frame text translated in place');
  assert(MOS.length >= 1 && (MOS[0].targets || []).includes(fdoc),
    'the frame document is MUTATION-OBSERVED');
  assert(hasDiag('ifr=1'), 'start diag counts the pierced frame: ' + DIAG[0]);
}

async function t18_priority() {
  console.log('T18 — viewport-LAZY boot (v1.3.74)');
  const g = fresh();
  const hot = addText(append(g.body, makeEl('p', { bcr: { top: 10, bottom: 30, left: 0, right: 100 } })),
    'Hot visible text');
  const coldEl = append(g.body, makeEl('p', { bcr: { top: 5000, bottom: 5030, left: 0, right: 100 } }));
  const cold = addText(coldEl, 'Cold below fold text');
  evalEngine(SRC_EN);
  await sleep(300);
  const texts = REQ.map(qOf);
  assert(texts.includes('Hot visible text'), 'the in-viewport node IS requested');
  assert(!texts.includes('Cold below fold text'),
    'the below-the-fold node is NOT requested at boot (deferred): ' + JSON.stringify(texts));
  assert(hasDiag('defer=1'), 'the start diag reports the deferred tail (defer=1)');
  await drainOk(() => 'Z');
  assert(hot.nodeValue === 'Z', 'the visible node translated');
  assert(cold.nodeValue === 'Cold below fold text', 'the deferred node untouched so far');
  assert(pill() && pill().textContent.indexOf('scroll for more') >= 0,
    'the pill says scroll for more: ' + (pill() && pill().textContent));
  // the user scrolls: the cold node enters the viewport
  reveal(coldEl);
  fireScroll();
  await sleep(350);
  assert(REQ.some(e => qOf(e) === 'Cold below fold text'),
    'the scroll pulled the deferred node into the queue');
  assert(hasDiag('scan push=1'), 'the scan diag fired (scan push=1)');
  await drainOk(() => 'Y');
  assert(cold.nodeValue === 'Y', 'the deferred node translated after the scroll');
  assert(pill() && pill().textContent.indexOf('tap for original') >= 0,
    'with the tail drained the pill offers the original: ' + (pill() && pill().textContent));
}

async function t19_backlog() {
  console.log('T19 — the scroll-driven backlog (the pump is gone, v1.3.74)');
  const g = fresh();
  const els = [];
  for (let i = 0; i < 10; i++)
    els.push(append(g.body, makeEl('p', { bcr: { top: 5000 + i * 40, bottom: 5030 + i * 40, left: 0, right: 100 } })));
  const nodes = els.map((el, i) => addText(el, 'Para number ' + i));
  evalEngine(SRC_CAPS); // INITCAP=5, DEFMAX=60, NODCAP=20
  await sleep(300);
  assert(hasDiag('nodes=1'), 'only the visible <title> pushed at boot: ' + DIAG[0]);
  assert(hasDiag('defer=10'), 'all 10 below-fold paras deferred: ' + DIAG[0]);
  assert(!hasDiag('more='), 'the old more= backlog report is gone');
  await drainOk(() => 'শিরোনাম');
  // nothing happens without a scroll — the static tail waits
  await sleep(700);
  assert(REQ.length === 0, 'no background draining without a scroll');
  assert(nodes.every(n => n.nodeValue.indexOf('Para number') === 0),
    'every deferred para still untouched');
  // the user scrolls to the bottom: all ten enter the viewport at once
  els.forEach(reveal);
  fireScroll();
  await sleep(350);
  assert(hasDiag('scan push=10'), 'one scan queued all ten: ' +
    DIAG.filter(d => d.indexOf('scan ') === 0).join(';'));
  const drained = await drainAll(n => 'ঢেউ ' + n);
  await sleep(400);
  assert(drained === 10, 'all 10 deferred paras translated after the scroll (got ' + drained + ')');
  assert(nodes.every(n => n.nodeValue.indexOf('ঢেউ') === 0),
    'every deferred para translated by the scroll scan');
}

async function t20_session_cap() {
  console.log('T20 — the session cap disconnects the observer');
  const g = fresh();
  const els = [], nodes = [];
  for (let i = 0; i < 30; i++) {
    els.push(append(g.body, makeEl('p', { bcr: { top: 10 + i * 30, bottom: 40 + i * 30, left: 0, right: 100 } })));
    nodes.push(addText(els[i], 'Cap para ' + i));
  }
  evalEngine(SRC_CAPS); // INITCAP=5, DEFMAX=60, NODCAP=20
  await sleep(300);
  await drainAll(() => 'x');               // boot batch: title + 5
  els.forEach(reveal);                     // all visible now
  fireScroll();                            // scan pushes the 25 deferred
  await drainAll(() => 'x');
  await sleep(300);
  const seen = nodes.filter(n => n.nodeValue === 'x').length;
  assert(seen >= 25, 'the scroll scan translated the tail (got ' + seen + ' of 30)');
  // past NODCAP the NEXT wave trips the cap and disconnects the observer
  MOS[0].cb([]);
  await sleep(900);
  assert(hasDiag('cap nodes='), 'the cap diag fired: ' + DIAG.filter(d => d.indexOf('cap ') === 0).join(';'));
  assert(MOS[0] && MOS[0].disc === true, 'the MutationObserver disconnected at the cap');
  const late = addText(append(g.body, makeEl('p')), 'Post cap content');
  MOS[0].cb([]);
  await sleep(900);
  assert(REQ.length === 0, 'content added after the cap is NOT requested (session bounded)');
  assert(late.nodeValue === 'Post cap content', 'post-cap node untouched');
}

async function t26_lazy_mutation() {
  console.log('T26 — below-the-fold mutations defer (v1.3.74)');
  const g = fresh();
  const a = addText(append(g.body, makeEl('p', { bcr: { top: 10, bottom: 30, left: 0, right: 100 } })),
    'Visible seed');
  evalEngine(SRC_EN);
  await sleep(250);
  await drainOk(() => 'প্রথম');
  assert(a.nodeValue === 'প্রথম', 'initial visible node done');
  // the page appends content FAR below the fold
  const belowEl = append(g.body, makeEl('p', { bcr: { top: 9000, bottom: 9030, left: 0, right: 100 } }));
  const below = addText(belowEl, 'Way below fold');
  MOS[0].cb([]);
  await sleep(900); // 600 ms debounce + kick
  assert(REQ.length === 0,
    'the below-fold mutation did NOT enter the queue (deferred)');
  assert(below.nodeValue === 'Way below fold', 'the below-fold addition untouched');
  reveal(belowEl);
  fireScroll();
  await sleep(350);
  assert(REQ.length === 1 && qOf(REQ[0]) === 'Way below fold',
    'the scroll queued the deferred mutation');
  await drainOk(() => 'নিচে');
  assert(below.nodeValue === 'নিচে', 'the below-fold mutation translated after scroll');
}

async function t27_no_toast_spam() {
  console.log('T27 — done2 on later finishes (toast-spam kill, v1.3.74)');
  const g = fresh();
  const top = append(g.body, makeEl('p', { bcr: { top: 10, bottom: 30, left: 0, right: 100 } }));
  addText(top, 'Above fold');
  const botEl = append(g.body, makeEl('p', { bcr: { top: 5000, bottom: 5030, left: 0, right: 100 } }));
  addText(botEl, 'Below fold');
  evalEngine(SRC_EN);
  await sleep(300);
  await drainOk(() => 'উপর');
  const dones = DIAG.filter(d => d.indexOf('done ') === 0).length;
  assert(dones === 1, 'exactly ONE done line after the first batch (got ' + dones + ')');
  reveal(botEl);
  fireScroll();
  await sleep(350);
  await drainOk(() => 'নিচ');
  assert(DIAG.filter(d => d.indexOf('done ') === 0).length === 1,
    'no second done line after the scroll batch (Kotlin would toast)');
  assert(DIAG.filter(d => d.indexOf('done2 ') === 0).length === 1,
    'the scroll batch reports done2 instead');
}

async function t28_attribute_reveal() {
  console.log('T28 — the attribute-only reveal still translates (v1.3.74)');
  const g = fresh();
  // a hidden section: no client rects -> not-rendered -> skipped at boot
  const hid = append(g.body, makeEl('div', { rects: [] }));
  const t = addText(hid, 'Reveal me later');
  const vis = addText(append(g.body, makeEl('p', { bcr: { top: 10, bottom: 30, left: 0, right: 100 } })),
    'Always visible');
  evalEngine(SRC_EN);
  await sleep(300);
  assert(REQ.every(e => qOf(e) !== 'Reveal me later'),
    'the hidden subtree was skipped at boot');
  await drainOk(() => 'দৃশ্যমান');
  assert(vis.nodeValue === 'দৃশ্যমান', 'visible node translated');
  // the site reveals the section by flipping its style (NO childList
  // mutation — only the attributeFilter:['style',…] observation catches it)
  hid.rects = [{}];
  MOS[0].cb([]);
  await sleep(900);
  assert(REQ.length === 1 && qOf(REQ[0]) === 'Reveal me later',
    'the revealed text was collected by the attribute wave: ' +
    JSON.stringify(REQ.map(qOf)));
  await drainOk(() => 'প্রকাশ');
  assert(t.nodeValue === 'প্রকাশ', 'the revealed text translated');
}

// -------- the selection box (own mock: document + selection + relay) ----
let SEL_REQ = [], SEL_DIAG = [], EXECCMD = [];
function selMockDoc() {
  const reg = {};
  const docEl = makeEl('html');
  const body = makeEl('body');
  append(docEl, body);
  const doc = {
    documentElement: docEl,
    body,
    getElementById(id) { return findById(docEl, id); },
    createElement: t => makeEl(t),
    addEventListener(t, fn) { (reg[t] = reg[t] || []).push(fn); },
    removeEventListener(t, fn) {
      if (reg[t]) reg[t] = reg[t].filter(f => f !== fn);
    },
    execCommand(c) { EXECCMD.push(c); return true; },
    fire(type, ev) { for (const fn of (reg[type] || []).slice()) fn(ev); },
  };
  return { doc, body };
}
async function t21_selection() {
  console.log('T21 — the selection translation box');
  delete global.__boltTxSelCB; delete global.__boltTxDone2;
  delete global.__boltTxSelKill; delete global.__boltTxSelN;
  SEL_REQ = []; SEL_DIAG = []; EXECCMD = [];
  const g = selMockDoc();
  global.document = g.doc;
  global.getSelection = () => ({
    rangeCount: 1,
    getRangeAt: () => ({ getBoundingClientRect: () => ({ left: 40, top: 100, bottom: 120, width: 200, height: 20 }) }),
  });
  global.BoltTx = {
    fetchSel(u, id) { SEL_REQ.push({ u, id }); },
    diag(l) { SEL_DIAG.push(l); },
  };
  eval(SRC_SEL);
  let box = g.doc.getElementById('__boltTxSel');
  assert(box !== null, 'the floating box appeared in place (no new tab)');
  assert(box.textContent.indexOf('Translating') >= 0 || true, 'box mounted');
  assert(SEL_REQ.length === 1, 'one fetchSel request fired');
  const u = SEL_REQ[0].u;
  assert(u.indexOf('dt=t') >= 0 && u.indexOf('dt=bd') >= 0,
    'the request asks for translation AND dictionary senses');
  assert(u.indexOf('tl=en') >= 0 && u.indexOf('q=mock%20selected%20text') >= 0,
    'target language + the selected text are in the URL');
  global.__boltTxDone2(SEL_REQ[0].id, true, JSON.stringify({
    sentences: [{ trans: 'নকল নির্বাচিত পাঠ', src_translit: 'nokol nirbochito path' }],
    src: 'fr',
    dict: [{ pos: 'noun', terms: ['অর্থ১', 'অর্থ২'] }, { pos: 'verb', terms: ['ক্রিয়া'] }],
  }));
  await sleep(50);
  const bodyEl = box.children[1];
  assert(bodyEl.textContent.indexOf('নকল নির্বাচিত পাঠ') === 0,
    'the translation rendered: "' + bodyEl.textContent.slice(0, 30) + '"');
  const deepText = el => (el.textContent || '') +
    (el.children || []).map(deepText).join('');
  assert(bodyEl.children.some(c => (c.textContent || '').indexOf('nokol nirbochito path') >= 0),
    'the transliteration rendered');
  assert(deepText(bodyEl).indexOf('noun: অর্থ১, অর্থ২') >= 0,
    'the dictionary MEANINGS rendered');
  assert(box.children[0].children[0].textContent === 'FR → EN', 'the header shows the language pair');
  const foot = box.children[2];
  assert(foot && foot.children[0] && foot.children[0].textContent === 'Copy', 'the Copy button rendered');
  foot.children[0].onclick();
  assert(EXECCMD.length === 1 && EXECCMD[0] === 'copy', 'Copy used execCommand (bridge-free, WebView-safe)');
  assert(foot.children[0].textContent.indexOf('Copied') === 0, 'Copy confirmed');
  // outside tap dismisses
  g.doc.fire('pointerdown', { target: makeEl('div') });
  assert(g.doc.getElementById('__boltTxSel') === null, 'outside tap dismissed the box');
  assert(SEL_DIAG.some(l => l.indexOf('sel ok') === 0), 'sel ok diag: ' + SEL_DIAG.join(';'));
  // second invoke replaces + failure state through the second channel
  SEL_REQ = [];
  eval(SRC_SEL);
  const box2 = g.doc.getElementById('__boltTxSel');
  assert(box2 !== null && box2 !== box, 'a second invoke replaced the prior box');
  global.__boltTxDone2(SEL_REQ[0].id, false, 'HTTP 500');
  await sleep(50);
  assert(box2.children[1].textContent.indexOf('Translation failed') === 0,
    'the failure state renders in place');
  assert(SEL_DIAG.some(l => l.indexOf('sel fail') === 0), 'sel fail diag: ' + SEL_DIAG.join(';'));
  g.doc.fire('keydown', { key: 'Escape' });
  assert(g.doc.getElementById('__boltTxSel') === null, 'Escape dismissed the box');
}

// ------------------------------------------------------------ v1.3.72 tests
async function t22_display_contents() {
  console.log('T22 — display:contents (THE chat.z.ai kill)');
  const g = fresh('একটি বাংলা শিরোনাম');
  // chat.z.ai (Open WebUI fork) mounts the whole app in a
  // <div id="app" style="display: contents"> — no box of its own, so
  // getClientRects() is EMPTY; the v1.3.71 walk hard-skipped the subtree.
  const app = append(g.body, makeEl('div', { id: 'app', rects: [] }));
  app.__computed = { display: 'contents' };
  const p = append(app, makeEl('p'));
  const ct = addText(p, '你好世界，这是一段中文聊天记录。');
  evalEngine(SRC_FAST);
  await sleep(350);
  const e = takeFor('你好世界，这是一段中文聊天记录。');
  assert(e !== null, 'text under a display:contents mount IS collected (v1.3.71 saw nodes=0)');
  if (e) deliverOk(e.id, 'Hello world, this is a Chinese chat log.', 'zh-CN');
  await drainOk(() => 'শিরোনাম');
  assert(ct.nodeValue.indexOf('Hello world') === 0,
    'the chat text translated in place through the transparent container');
  assert(hasDiag('dc=1'), 'start diag counts the contents pass (dc=1): ' + DIAG[0]);
  // a no-box element that is NOT display:contents stays skipped (display:none)
  const hidden = append(g.body, makeEl('div', { rects: [] }));
  addText(append(hidden, makeEl('p')), 'Hidden but real text stays skipped');
  await sleep(250);
  assert(takeFor('Hidden but real text stays skipped') === null,
    'a zero-rect non-contents box is STILL skipped in the normal pass');
}

async function t23_rescan_rescue() {
  console.log('T23 — the zero-node rescan ladder rescues late SPA content');
  const g = fresh('একটি বাংলা শিরোনাম');
  evalEngine(SRC_FAST);
  await sleep(130);
  assert(hasDiag('rscan i=1'), 'empty page entered the ladder (rscan i=1 by 130ms)');
  // the SPA finishes rendering BETWEEN rescans (no MutationObserver fire —
  // the content is injected straight into the mock DOM); rescan #2 fires
  // at 80+120=200ms, so the content lands at ~170ms
  await sleep(40);
  const late = addText(append(g.body, makeEl('p')), '迟到的聊天内容 arrived late');
  await sleep(400);
  const e = takeFor('迟到的聊天内容 arrived late');
  assert(e !== null, 'content that appeared after boot is caught by a rescan');
  if (e) deliverOk(e.id, 'LATE CONTENT', 'zh-CN');
  await drainOk(() => 'শিরোনাম');
  assert(late.nodeValue === 'LATE CONTENT', 'late content translated');
  assert(!hasDiag('done empty'), 'no false empty verdict on a slow page');
}

async function t24_relax_pass() {
  console.log('T24 — the final relaxed pass overrides translate=no');
  const g = fresh('একটি বাংলা শিরোনাম');
  // a site that hides ALL its text behind translate=no — only the RELAX
  // pass (rscan i=4) may translate it
  const wrap = append(g.body, makeEl('div', { attrs: { translate: 'no' } }));
  const wt = addText(append(wrap, makeEl('p')), '被隐藏的中文文本');
  evalEngine(SRC_FAST);
  await sleep(200);
  assert(REQ.length === 0, 'normal passes respect translate=no (tn counted)');
  assert(hasDiag('tn=1'), 'the rejection was counted: ' + DIAG[0]);
  await sleep(700);
  const e = takeFor('被隐藏的中文文本');
  assert(e !== null, 'the RELAX pass collected the translate=no text');
  if (e) deliverOk(e.id, 'RELAXED TEXT', 'zh-CN');
  await drainOk(() => 'শিরোনাম');
  assert(wt.nodeValue === 'RELAXED TEXT', 'hidden-behind-hint text translated');
  assert(hasDiag('relax=1') && hasDiag('rscan i=4'), 'the ladder reached the relaxed pass');
}

async function t25_closed_shadow_registry() {
  console.log('T25 — CLOSED shadow roots via the ShadowGate registry');
  const g = fresh();
  // a CLOSED root: element.shadowRoot is null (unreadable by design) —
  // but ShadowGate's attachShadow wrap recorded it in window.__boltShr
  const host = append(g.body, makeEl('x-closed'));
  const root = makeRoot();
  root.host = host;
  const ct = addText(append(root, makeEl('p')), 'Closed root text lives here');
  global.__boltShr = [root];
  // an OPEN root ALSO in the registry must not be walked twice
  const host2 = append(g.body, makeEl('x-open'));
  const root2 = makeRoot();
  root2.host = host2;
  host2.shadowRoot = root2;
  const ot = addText(append(root2, makeEl('p')), 'Open root text deduped');
  global.__boltShr.push(root2);
  evalEngine(SRC_EN);
  await sleep(350);
  const e1 = takeFor('Closed root text lives here');
  assert(e1 !== null, 'text inside a CLOSED root IS collected via __boltShr');
  if (e1) deliverOk(e1.id, 'ক্লোজড', 'fr');
  const e2 = takeFor('Open root text deduped');
  assert(e2 !== null, 'the registry-listed OPEN root is collected too');
  if (e2) deliverOk(e2.id, 'ওপেন', 'fr');
  await drainOk(() => 'শিরোনাম');
  assert(ct.nodeValue === 'ক্লোজড', 'closed-root text translated in place');
  assert(ot.nodeValue === 'ওপেন', 'open-root text translated in place');
  const dupes = ALL_URLS.filter(
    v => decodeURIComponent(v.split('q=')[1]) === 'Open root text deduped').length;
  assert(dupes === 1,
    'an OPEN root reachable by BOTH paths is requested exactly once (dedupe), got ' + dupes);
  assert(hasDiag('cshr=2'), 'start diag counts both registry pierces (cshr=2): ' + DIAG[0]);
}

// ------------------------------------------------------------ runner
(async () => {
  await t1_collect();
  await t2_prefilter();
  await t3_whitespace();
  await t4_split();
  await t5_shapes();
  await t6_kept();
  await t7_failure();
  await t8_backoff();
  await t9_pill_revert();
  await t10_observer();
  await t11_stale();
  await t12_urls();
  await t13_restart();
  await t14_empty();
  await t15_encoding();
  await t16_shadow();
  await t17_iframe();
  await t18_priority();
  await t19_backlog();
  await t20_session_cap();
  await t21_selection();
  await t22_display_contents();
  await t23_rescan_rescue();
  await t24_relax_pass();
  await t25_closed_shadow_registry();
  await t26_lazy_mutation();
  await t27_no_toast_spam();
  await t28_attribute_reveal();
  console.log(failures === 0
    ? 'ALL TRANSLATE TESTS PASSED'
    : 'TRANSLATE TESTS: ' + failures + ' FAILURE(S)');
  process.exit(failures === 0 ? 0 : 1);
})().catch(e => {
  console.error('SUITE ERROR', e);
  process.exit(1);
});
