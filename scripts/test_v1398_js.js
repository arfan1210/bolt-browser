#!/usr/bin/env node
/* v1.3.98 — functional probe of the LIVE VIEWPORT GUARD (extracted from
 * assets/webauthn_compat.js and run against a minimal DOM mock). Not a UI
 * test: it proves the guard *does what the fix claims* on the exact field
 * corpses —
 *   1. the SPA route change: the page REWRITES the existing meta's content
 *      attribute to width=device-width long after load; the guard must put
 *      width=1280 back (the v1.3.89 observer never even subscribed to
 *      attribute mutations — this is the headline corpse).
 *   2. the settled state never loops: our own rewrite fires the observer
 *      again; the >=1024 rule must end the round-trip.
 *   3. the missing-meta corpse: a page with NO viewport meta gets one
 *      injected at sweep time (WebView's meta-less fallback is 980px).
 *   4. already-desktop-wide pages are left as authored.
 *   5. the guard NEVER rests: a rewrite a minute later is still answered
 *      (no disconnect anywhere in the script).
 */
const fs = require('fs'), path = require('path');
const ROOT = path.join(__dirname, '..');

const src = fs.readFileSync(path.join(ROOT, 'app/src/main/assets/webauthn_compat.js'), 'utf8');

// ---- extract the desktop-viewport block exactly as shipped ------------
const start = src.indexOf("if ((UA_WIN || UA_MAC) && !UA_MOBILE) {");
const endMarker = "log('desktop viewport forced to ' + DW_W + 'px (live guard)')";
const end = src.indexOf(endMarker);
if (start < 0 || end < 0) throw new Error('desktop viewport block not found');
const afterLog = src.indexOf('} catch (eDW) {}', end);
if (afterLog < 0) throw new Error('block closer not found');
const block = src.slice(start, afterLog + '} catch (eDW) {}'.length) + '\n  }';

let pass = 0, fail = 0;
function need(cond, why) {
  console.log((cond ? '  PASS ' : '  FAIL ') + why);
  cond ? pass++ : fail++;
}

// ---- the mock DOM ------------------------------------------------------
function makeDom(withMeta) {
  const observers = [];
  const listeners = {};
  const doc = {
    nodeType: 9, tagName: '#document',
    documentElement: makeNode('html', {}),
    head: makeNode('head', {}),
    createElement(tag) { return makeNode(tag, {}); },
    querySelectorAll(sel) {
      if (!/viewport/.test(sel)) return [];
      const metas = all(doc.documentElement).filter(n =>
        n.tagName === 'META' && (n.getAttribute('name') || '').toLowerCase() === 'viewport');
      return metas;
    },
    addEventListener(ev, fn) { (listeners[ev] = listeners[ev] || []).push(fn); },
  };
  doc.documentElement.parentNode = doc;
  doc.documentElement.appendChild(doc.head);

  function makeNode(tag, o) {
    const n = Object.assign({
      tagName: tag.toUpperCase(), nodeType: 1, attrs: {}, children: [],
      parentNode: null,
      setAttribute(k, v) { n.attrs[k] = v; deliverAttr(n); },
      getAttribute(k) { return k in n.attrs ? n.attrs[k] : null; },
      appendChild(c) { c.parentNode = n; n.children.push(c); deliverAdd(c); return c; },
    }, o);
    return n;
  }
  function all(node) {
    const out = [];
    (function walk(x) {
      if (x.tagName) out.push(x);
      (x.children || []).forEach(walk);
    })(node);
    return out;
  }
  // MutationObserver mock: records the observe() options and calls back on
  // attribute changes / node additions, exactly like the real one (microtask
  // batches simplified to synchronous delivery — same semantics for the probe)
  function deliverAttr(target) {
    observers.forEach(o => {
      if (!o.opts.attributes) return;
      if (o.opts.attributeFilter && !o.opts.attributeFilter.includes('content')) return;
      o.cb([{ type: 'attributes', target, attributeName: 'content' }]);
    });
  }
  function deliverAdd(node) {
    observers.forEach(o => {
      if (!o.opts.childList) return;
      o.cb([{ type: 'childList', addedNodes: [node], removedNodes: [] }]);
    });
  }
  global.MutationObserver = class {
    constructor(cb) { this.cb = cb; }
    observe(t, opts) { this.opts = opts; observers.push(this); }
    disconnect() { this.dead = true; }
  };
  global.document = doc;
  global.window = { addEventListener(ev, fn) { (listeners['w:' + ev] = listeners['w:' + ev] || []).push(fn); } };

  if (withMeta) {
    const m = doc.createElement('meta');
    m.setAttribute('name', 'viewport');
    m.setAttribute('content', 'width=device-width, initial-scale=1');
    // attribute delivery would fire during setup; clear observers first
    observers.length = 0;
    doc.head.appendChild(m);
  }
  return { doc, observers, fire(ev) { (listeners[ev] || []).forEach(f => f()); } };
}

// ---- run the block under a desktop UA ---------------------------------
function runGuard(dom) {
  const sandbox = `
    var navigator = { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36' };
    var console = { info() {} };
    function log() {}
    var CONFIG = { debug: false, chromeMajor: 157, chromeFullVersion: '157.0.8103.50', platformVersion: '14.0.0' };
    var LIVE_UA = navigator.userAgent;
    var UA_WIN = LIVE_UA.indexOf('Windows NT') !== -1;
    var UA_MAC = LIVE_UA.indexOf('Macintosh') !== -1;
    var UA_IOS = false;
    var UA_MOBILE = !UA_WIN && !UA_MAC && LIVE_UA.indexOf('Mobile') !== -1;
    ${block}
  `;
  // eslint-disable-next-line no-new-func
  new Function(sandbox)();
  return null;
}

console.log('1 — the SPA route change (attribute rewrite) is answered');
{
  const dom = makeDom(false);
  runGuard(dom);          // document-start: the guard installs FIRST
  // the parser then inserts the page's own meta (the real order)
  const meta = dom.doc.createElement('meta');
  meta.setAttribute('name', 'viewport');
  meta.setAttribute('content', 'width=device-width, initial-scale=1');
  dom.doc.head.appendChild(meta);
  need(meta.getAttribute('content') === 'width=1280',
       'the parser-inserted meta is forced to width=1280 before first layout');
  // the SPA rewrites it a MINUTE later (after any 4s window — the v1.3.89 corpse)
  meta.setAttribute('content', 'width=device-width, initial-scale=1');
  need(meta.getAttribute('content') === 'width=1280',
       'the late attribute rewrite is ANSWERED (width=1280 restored)');
}

console.log('2 — the round-trip settles (no loop)');
{
  const dom = makeDom(true);
  runGuard(dom);
  const meta = dom.doc.querySelectorAll('meta[name="viewport"]')[0];
  let rounds = 0;
  const orig = meta.setAttribute.bind(meta);
  meta.setAttribute = function (k, v) { rounds++; orig(k, v); };
  meta.setAttribute('content', 'width=device-width');
  need(meta.getAttribute('content') === 'width=1280' && rounds <= 3,
       'our own rewrite is the last one (idempotent settle, <=3 writes)');
}

console.log('3 — the missing-meta corpse (injection)');
{
  const dom = makeDom(false);
  runGuard(dom);
  need(dom.doc.querySelectorAll('meta[name="viewport"]').length === 0, 'no meta exists yet');
  dom.fire('DOMContentLoaded');
  const metas = dom.doc.querySelectorAll('meta[name="viewport"]');
  need(metas.length === 1 && metas[0].getAttribute('content') === 'width=1280',
       'a missing meta is INJECTED at sweep (width=1280)');
}

console.log('4 — already-desktop-wide pages are left as authored');
{
  const dom = makeDom(false);
  const m = dom.doc.createElement('meta');
  m.setAttribute('name', 'viewport');
  m.setAttribute('content', 'width=1440');
  dom.observers.length = 0;
  dom.doc.head.appendChild(m);
  runGuard(dom);
  need(m.getAttribute('content') === 'width=1440',
       'width=1440 survives untouched (the >=1024 rule)');
}

console.log('5 — the guard never rests');
{
  need(!/dwObs\.disconnect/.test(src),
       'no disconnect anywhere in webauthn_compat.js (the 4s rest is gone)');
  const dom = makeDom(true);
  runGuard(dom);
  const meta = dom.doc.querySelectorAll('meta[name="viewport"]')[0];
  // simulate "a minute later": nothing but the rewrite itself
  meta.setAttribute('content', 'width=device-width, maximum-scale=1');
  need(meta.getAttribute('content') === 'width=1280',
       'a rewrite long after load is still answered (observer alive)');
}

console.log('6 — the pageshow sweep (bfcache restore)');
{
  const dom = makeDom(true);
  runGuard(dom);
  const meta = dom.doc.querySelectorAll('meta[name="viewport"]')[0];
  // bfcache freeze "loses" our value: rewrite WITHOUT delivery (simulates a
  // restored document whose meta was left mid-rewrite)
  meta.attrs['content'] = 'width=device-width';
  dom.fire('w:pageshow');
  need(meta.getAttribute('content') === 'width=1280',
       'pageshow re-sweeps a restored document');
}

console.log();
if (fail) { console.log(`v1.3.98 JS probe: ${fail} FAILURE(S)`); process.exit(1); }
console.log(`v1.3.98 JS probe: all green (${pass})`);
