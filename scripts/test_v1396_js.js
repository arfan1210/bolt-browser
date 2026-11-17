#!/usr/bin/env node
/* v1.3.96 — functional probe of the NEW page-geometry JS (extracted from
 * the Kotlin sources, run against a minimal DOM mock): heightJs, flattenJs,
 * flatRestoreJs (AiExport) and the PARSE-FIXED textBlocksJs (PageSave — the
 * v1.3.95 corpse). Not a UI test: it proves the JS *does what the fix
 * claims* on a mock that mirrors the field case (an SPA with a fixed-height
 * inner scroller holding the whole conversation). */
const fs = require('fs'), path = require('path'), {execFileSync} = require('child_process');
const ROOT = path.join(__dirname, '..');

function extract(ktPath, fn) {
  const src = fs.readFileSync(path.join(ROOT, ktPath), 'utf8');
  const m = src.match(new RegExp('fun ' + fn + '\\(\\): String =([\\s\\S]*?)(?=\\n    // |\\n    /\\*\\*|\\n})'));
  if (!m) throw new Error('no body for ' + fn);
  const lits = m[1].match(/"((?:[^"\\]|\\.)*)"/g) || [];
  return lits.map(s => s.slice(1, -1)
      .replace(/\\\\/g, '\x00').replace(/\\"/g, '"').replace(/\x00/g, '\\')).join('');
}

// ---- minimal DOM mock: a scrollable SPA -------------------------------
function el(tag, o) {
  o = o || {};
  const base = Object.assign({
    tagName: tag.toUpperCase(),
    nodeType: 1,
    style: {},
    attrs: {},
    children: [],
    parentNode: null,
    className: o.className || '',
    id: '',
    classList: {
      _s: new Set(),
      add(c) { this._s.add(c); }, remove(c) { this._s.delete(c); },
      contains(c) { return this._s.has(c); },
    },
    setAttribute(k, v) { this.attrs[k] = v; },
    getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; },
    removeAttribute(k) { delete this.attrs[k]; },
    querySelectorAll(sel) { return all(this).filter(n => matchSel(n, sel)); },
    appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
    cloneNode(deep) {
      const c = el(this.tagName, {
        className: this.className, textContent: this.textContent,
        scrollHeight: this.scrollHeight, clientHeight: this.clientHeight,
        overflowY: this.overflowY,
      });
      if (deep) this.children.forEach(ch => c.appendChild(ch.cloneNode(true)));
      return c;
    },
    closest(sel) {
      let p = this;
      while (p && p.tagName) { if (matchSel(p, sel)) return p; p = p.parentNode; }
      return null;
    },
    getBoundingClientRect() { return {left: 0, top: 0, width: 800, height: this.clientHeight || 600}; },
  }, o);
  // className and classList must stay in sync (the mock's contract)
  if (base.className) base.className.split(/\s+/).forEach(c => base.classList.add(c));
  return base;
}
function all(n) { const out = []; walk(n); function walk(x) { out.push(x); (x.children || []).forEach(walk); } return out; }
function matchSel(n, sel) {
  // the shapes our probes use: '*' tag lists and .class
  if (sel.indexOf('[') >= 0 || sel.indexOf(':') >= 0) return false;
  return sel.split(',').some(s => {
    s = s.trim();
    if (s === '*') return true;
    if (s[0] === '.') return n.classList.contains(s.slice(1));
    return n.tagName === s.toUpperCase();
  });
}

let fails = 0;
function need(cond, why) {
  console.log((cond ? '  PASS ' : '  FAIL ') + why);
  if (!cond) fails++;
}

// build the mock page: html > body > (header, chatPane[scroller] > msgs, footer)
const html = el('html', {scrollHeight: 900, offsetHeight: 900, clientHeight: 600});
const body = el('body', {scrollHeight: 900, offsetHeight: 900});
html.appendChild(body);
const chat = el('div', {scrollHeight: 12000, clientHeight: 600, overflowY: 'scroll'});
body.appendChild(chat);
for (let i = 0; i < 40; i++) chat.appendChild(el('p', {textContent: 'message ' + i}));
const small = el('div', {scrollHeight: 300, clientHeight: 200, overflowY: 'auto'});
body.appendChild(small);

const doc = {
  documentElement: html, body,
  scrollingElement: html,
  querySelectorAll: (s) => all(body).filter(n => matchSel(n, s)),
};

// getComputedStyle mock: overflowY from the stored prop
global.window = {innerHeight: 600};
const gCS = (e) => ({get overflowY() { return e.overflowY || 'visible'; }});
function runJsDoc(js, d) {
  const fn = new Function('document', 'window', 'getComputedStyle', 'Set',
      'return ' + js);
  return fn(d || doc, global.window, gCS, Set);
}
function runJs(js) { return runJsDoc(js, doc); }

console.log('H — heightJs sees through the inner scroller');
const h = runJs(extract('app/src/main/java/com/bolt/browser/util/AiExport.kt', 'heightJs'));
need(h === '900', 'heightJs returns the ROOT document height (got ' + h + ')');

console.log('F — flattenJs opens the big fixed-height scroller');
const opened = runJs(extract('app/src/main/java/com/bolt/browser/util/AiExport.kt', 'flattenJs'));
need(opened === '1', 'exactly the chat pane is flattened (got ' + opened + ')');
need(chat.style.height === 'auto' && chat.style.overflow === 'visible' && chat.style.maxHeight === 'none',
     'the chat pane is open (height:auto / overflow:visible / max-height:none)');
need(chat.classList.contains('__boltFlat'), 'the flat marker class rides it');
need(chat.getAttribute('data-bolt-fh') === '', 'the original inline height is saved for restore');
need(small.style.height !== 'auto', 'the small scroller is left alone');

console.log('R — flatRestoreJs puts the page back');
runJs(extract('app/src/main/java/com/bolt/browser/util/AiExport.kt', 'flatRestoreJs'));
need(!chat.classList.contains('__boltFlat') && chat.style.overflow === '',
     'styles + markers restored (idempotent)');

console.log('T — the parse-fixed text walk returns blocks');
// richer mock for the reader walk
const body2 = el('body', {});
const article = el('article');
body2.appendChild(article);
article.appendChild(el('h1', {textContent: 'How to make rice'}));
article.appendChild(el('p', {textContent: 'Wash the rice.'}));
article.appendChild(el('table'));
const tr = el('tr'); article.children[2].appendChild(tr);
tr.appendChild(el('td', {textContent: '2 cups'}));
article.appendChild(el('pre', {textContent: 'code\n  block'}));
const nav = el('nav', {className: 'share'});
article.appendChild(nav);
const doc2 = {documentElement: html, body: body2, scrollingElement: html,
  querySelectorAll: (s) => all(body2).filter(n => matchSel(n, s))};
const out = runJsDoc(extract('app/src/main/java/com/bolt/browser/util/PageSave.kt', 'textBlocksJs'), doc2);
let parsed;
try { parsed = JSON.parse(out); } catch (e) { parsed = null; }
need(Array.isArray(parsed) && parsed.length === 4,
     'the walk returns the h1 + p + td + pre (got ' + (parsed && parsed.length) + ')');
need(parsed && parsed.some(b => b.k === 'td' && b.t === '2 cups'),
     'the table cell rides the walk (the v1.3.96 widening)');

console.log();
if (fails) { console.log('v1396 js-probe: ' + fails + ' FAILURE(S)'); process.exit(1); }
console.log('v1396 js-probe: all green');
