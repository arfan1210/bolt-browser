/* v1.3.87 — behavioral test for the AiExport picker JS (mock-DOM).
 * v1.3.92 — REWRITTEN FOR THE NATIVE PICKER: the page chrome (circles +
 * bar + <style>) is GONE — the picker only marks candidates, keeps
 * selection state, reports rects, hit-tests manual taps, extracts blocks
 * and preps export mode (inline CSS now — the v1.3.87-91 classes were
 * never styled by anything, so the hide/open surgery had NO effect).
 * Verifies: discovery + outermost dedupe, marking, toggle/rects payload,
 * the inline-styled export surgery, the restore contract, manual mode,
 * the busy guard, per-site selectors, and the block extraction.
 */
const fs = require('fs'), path = require('path');
const KT = path.join(__dirname, '..', 'app/src/main/java/com/bolt/browser/util/AiExport.kt');
const src = fs.readFileSync(KT, 'utf8');

function extractKotlinString(method) {
  const re = new RegExp('fun ' + method + '\\(\\): String =([\\s\\S]*?)(?=\\n    // |\\n    /\\*\\*|\\n})');
  const m = src.match(re);
  if (!m) throw new Error(method + ' not found in AiExport.kt');
  const lits = m[1].match(/"((?:[^"\\]|\\.)*)"/g) || [];
  return lits.map(s => s.slice(1, -1)
      .replace(/\\\\/g, '\x00').replace(/\\"/g, '"').replace(/\x00/g, '\\')).join('');
}

const PICKER = extractKotlinString('pickerJs');
const RESTORE = extractKotlinString('restoreJs');

let failures = 0;
function assert(cond, msg) {
  if (cond) console.log('  PASS ' + msg);
  else { failures++; console.log('  FAIL ' + msg); }
}

// ------------------------------------------------------------ mock DOM
function makeDom(hostname, build) {
  const all = [];
  function el(tag, cls, text) {
    const e = {
      tagName: String(tag).toUpperCase(),
      className: cls || '',
      attrs: {},
      children: [],
      parent: null,
      style: {},
      listeners: {},
      removed: false,
      textNodes: [],
      rect: { left: 10, top: 10, width: 200, height: 100 },
      // innerText mirrors the REAL DOM: explicit text when set, otherwise
      // the concatenation of the descendants (a structured response must
      // pass the 40-char discovery floor through its children's text)
      _text: text || '',
      get innerText() {
        if (e._text) return e._text;
        let s = e.textNodes.map(t => t.nodeValue).join('');
        e.children.forEach(c => { s += (s ? '\n' : '') + (c.innerText || ''); });
        return s;
      },
      set innerText(v) { e._text = v; },
      getBoundingClientRect() { return e.rect; },
      get parentElement() { return e.parent; },
      get isConnected() { return !e.removed && (e.parent ? e.parent.isConnected : e === body || e === documentElement); },
      get childNodes() {
        // text nodes first is fine for the own() extractor (it concatenates)
        return e.textNodes.concat(e.children);
      },
      appendChild(c) { c.parent = e; e.children.push(c); return c; },
      remove() { e.removed = true; },
      addEventListener(t, fn) { (e.listeners[t] = e.listeners[t] || []).push(fn); },
      dispatch(t) {
        const ev = { target: e, stopped: false,
          stopPropagation() { ev.stopped = true; }, preventDefault() {} };
        (e.listeners[t] || []).forEach(fn => fn(ev));
        return ev;
      },
      contains(o) {
        if (o === e) return true;
        return e.children.some(c => c.contains && c.contains(o));
      },
      closest(sel) {
        let p = e;
        while (p) { if (matches(p, sel)) return p; p = p.parent; }
        return null;
      },
      setAttribute(k, v) { e.attrs[k] = String(v); },
      getAttribute(k) { return k in e.attrs ? e.attrs[k] : null; },
      removeAttribute(k) { delete e.attrs[k]; },
      hasAttribute(k) { return k in e.attrs; },
      querySelectorAll(sel) {
        const out = [];
        (function walk(n) { n.children.forEach(c => { if (matches(c, sel)) out.push(c); walk(c); }); })(e);
        return out;
      }
    };
    e.classList = {
      add(c) { if (!hasClass(e, c)) e.className = (e.className + ' ' + c).trim(); },
      remove(c) { e.className = e.className.split(/\s+/).filter(x => x && x !== c).join(' '); },
      contains(c) { return hasClass(e, c); }
    };
    all.push(e);
    return e;
  }
  // a text node child (the own() extractor reads nodeValue + nodeType 3)
  function addText(e, s) { e.textNodes.push({ nodeType: 3, nodeValue: s }); }
  function hasClass(e, c) { return e.className.split(/\s+/).indexOf(c) >= 0; }
  function matches(e, sel) {
    return sel.split(',').some(part => {
      part = part.trim();
      if (part === '*') return true;
      let m;
      if ((m = part.match(/^\.([\w-]+)$/))) return hasClass(e, m[1]);
      if ((m = part.match(/^\[([\w-]+)\*="([^"]*)"\]$/))) {
        const v = m[1] === 'class' ? e.className : (e.attrs[m[1]] || '');
        return v.indexOf(m[2]) >= 0;
      }
      if ((m = part.match(/^\[([\w-]+)="([^"]*)"\]$/))) {
        const v = m[1] === 'class' ? e.className : (e.attrs[m[1]] || '');
        return v === m[2];
      }
      if ((m = part.match(/^\[([\w-]+)\]$/))) {
        return m[1] === 'class' ? !!e.className : (m[1] in e.attrs);
      }
      return e.tagName === part.toUpperCase();
    });
  }
  const documentElement = el('html', '');
  const body = el('body', '');
  documentElement.appendChild(body);
  const doc = {
    documentElement, body,
    createElement: (t) => el(t, ''),
    getElementById: (id) => all.find(e => e.attrs.id === id && !e.removed) || null,
    querySelectorAll: (sel) => {
      const out = [];
      (function walk(n) { n.children.forEach(c => { if (matches(c, sel)) out.push(c); walk(c); }); })(body);
      return out;
    },
    querySelector: (sel) => {
      const l = doc.querySelectorAll(sel);
      return l.length ? l[0] : null;
    },
    listeners: {},
    addEventListener(t, fn) { (doc.listeners[t] = doc.listeners[t] || []).push(fn); },
    removeEventListener(t, fn) {
      const l = doc.listeners[t] || [];
      const i = l.indexOf(fn);
      if (i >= 0) l.splice(i, 1);
    }
  };
  const docDispatch = (target) => {
    const ev = { target, stopped: false,
      stopPropagation() { ev.stopped = true; }, preventDefault() {} };
    (doc.listeners.click || []).forEach(fn => fn(ev));
    return ev;
  };
  const aiCalls = [];
  const aiSyncs = [];
  const win = {
    scrollX: 0, scrollY: 0,
    scrollTo: () => {},
  };
  const Bolt = {
    aiCapture: (mode, n) => aiCalls.push({ mode, n }),
    aiSync: () => aiSyncs.push(1)
  };
  build({ el, addText, body, doc, all, win, Bolt, aiCalls, hasClass });
  const run = (js) => new Function('document', 'location', 'window', 'Bolt',
      'return ' + js)(doc, { hostname }, win, Bolt);
  return { doc, all, run, win, Bolt, aiCalls, aiSyncs, hasClass, body, documentElement, docDispatch };
}

function hasCls(e, c) { return e.className && e.className.split(/\s+/).indexOf(c) >= 0; }

// ------------------------------------------------------------ the tests

console.log('== P1/P2/P3: picker on a zai-shaped page (v1.3.92 native chrome) ==');
{
  const marks = [];
  const env = makeDom('chat.z.ai', ({ el, body }) => {
    const scroller = el('div', 'chat-scroll');
    body.appendChild(scroller);
    const header = el('header', 'site-header', 'Z.ai');
    body.appendChild(header);
    const r1 = el('div', 'markdown', 'Answer one: the code you want is `npm test` and it runs the whole suite.');
    const r2 = el('div', 'markdown', 'Answer two: longer text with some more words to pass the forty character floor easily.');
    const r3 = el('div', 'markdown', 'Answer three: another full answer with plenty of characters to be counted.');
    const user = el('div', 'user-bubble', 'how do I run tests?');
    scroller.appendChild(r1); scroller.appendChild(user);
    scroller.appendChild(r2); scroller.appendChild(r3);
    // a nested markdown-in-markdown wrapper (outermost wins)
    const wrap = el('div', 'markdown', 'Answer one wrapper text that is long enough to count as a response too.');
    const inner = el('div', 'markdown prose', 'inner nested copy that should not double count at all.');
    wrap.appendChild(inner);
    scroller.appendChild(wrap);
    marks.push(r1, r2, r3, wrap);
  });

  const res = env.run(PICKER);
  const parsed = JSON.parse(res);
  assert(parsed.n === 4, `discovery finds 4 outermost responses (got ${parsed.n})`);

  // the page is CLEAN: no injected chrome (the chrome is native now)
  assert(env.all.every(e => !hasCls(e, '__boltAiChk') && !hasCls(e, '__boltAiBar') && !hasCls(e, '__boltAiHint')),
    'NO page-injected chrome (circles/bar/hint are native views)');
  // the candidates are MARKED
  assert(marks.every((m, i) => m.getAttribute('data-bolt-ai') === String(i)),
    'each candidate carries its data-bolt-ai index');

  // P2: toggle 0 and 2 through the native-side entry points
  assert(env.win.__boltAiToggle(0) === 1, 'toggle(0) selects (returns count 1)');
  env.win.__boltAiToggle(2);
  assert(marks[0].style.outline.indexOf('#7C4DFF') >= 0 && hasCls(marks[0], '__boltAiSel'),
    'the selected block gets the inline outline');
  assert(!hasCls(marks[1], '__boltAiSel'), 'the untouched block stays unselected');
  // the rects payload drives the native circles
  const payload = JSON.parse(env.win.__boltAiRects());
  assert(payload.n === 4 && payload.sel === 2 && payload.manual === 0,
    `the sync payload reports n/sel/manual (got ${JSON.stringify({ n: payload.n, sel: payload.sel })})`);
  assert(payload.r.length === 4 && payload.r[0].s === 1 && payload.r[1].s === 0 && payload.r[2].s === 1,
    'the payload carries per-candidate rects + selection state');
  assert(payload.r[0].k === 'c' && payload.r[0].id === '0', 'candidate ids are strings');
  // deselect again
  env.win.__boltAiToggle(0);
  assert(!hasCls(marks[0], '__boltAiSel') && marks[0].style.outline === '',
    'toggle off clears the outline');

  // P3: prep('pdf') — the inline-styled export surgery
  env.win.__boltAiSetAll(false);
  env.win.__boltAiToggle(0);
  env.win.__boltAiToggle(2);
  const n = env.win.__boltAiPrep('pdf');
  assert(n === 2, `prep hands the selection count to the bridge (got ${n})`);
  assert(env.aiCalls.length === 1 && env.aiCalls[0].mode === 'pdf' && env.aiCalls[0].n === 2,
    `Bolt.aiCapture('pdf', 2) hands off (got ${JSON.stringify(env.aiCalls)})`);
  const headerEl = env.all.find(e => e.tagName === 'HEADER');
  assert(hasCls(headerEl, '__boltAiHide') && headerEl.style.display === 'none',
    'site chrome (header) is hidden VIA INLINE CSS (the v1.3.87-91 class was never styled)');
  const userMsg = env.all.find(e => hasCls(e, 'user-bubble'));
  assert(userMsg.style.display === 'none', 'the unselected user message is hidden');
  assert(marks[1].style.display === 'none' && marks[3].style.display === 'none',
    'the UNSELECTED responses are hidden');
  assert(marks[0].style.display !== 'none' && marks[2].style.display !== 'none',
    'the SELECTED responses stay visible');
  const scroller = env.all.find(e => hasCls(e, 'chat-scroll'));
  assert(scroller.style.height === 'auto' && scroller.style.overflow === 'visible'
      && scroller.style.maxHeight === 'none' && hasCls(scroller, '__boltAiOpen'),
    'the fixed-height chat scroller is OPENED (inline height/overflow/maxHeight)');
  assert(hasCls(env.documentElement, '__boltAiExp'), 'export mode is set on <html>');
  assert(marks[0].style.outline === '', 'the selection outline is stripped before the capture');

  // P4: the restore contract — everything returns EXACTLY
  env.run(RESTORE);
  assert(env.all.every(e => !hasCls(e, '__boltAiHide') && !hasCls(e, '__boltAiOpen')
      && !hasCls(e, '__boltAiSel')),
    'restore removes every hide/open/sel class');
  assert(headerEl.style.display === '' && scroller.style.height === ''
      && scroller.style.overflow === '' && scroller.style.maxHeight === '',
    'restore returns the ORIGINAL inline styles');
  assert(!hasCls(env.documentElement, '__boltAiExp'), 'export mode is cleared from <html>');
  assert(marks.every(m => m.getAttribute('data-bolt-ai') === null),
    'the candidate markers are removed');
  assert(env.win.__boltAiPick === undefined, 'the busy flag is freed (picker can run again)');
  const tap = env.docDispatch(marks[0]);
  assert(!tap.stopped, 'after restore, taps pass through to the page again');
}

console.log('== P5: the zero case -> MANUAL MODE ==');
{
  const env = makeDom('example.com', ({ el, body }) => {
    body.appendChild(el('div', 'plain-article', 'Just some text, nothing chat-like in here at all.'));
  });
  const res = JSON.parse(env.run(PICKER));
  assert(res.n === 0 && !res.err && res.manual === 1,
    'a non-chat page reports zero + manual (the never-dead picker)');
  // tap-to-select through the document capture listener
  const target = env.all.find(e => e.className === 'plain-article');
  const syncs0 = env.aiSyncs.length;
  const ev = env.docDispatch(target);
  assert(ev.stopped, 'a manual-mode tap is consumed (no navigation)');
  assert(hasCls(target, '__boltAiSel') && target.style.outline.indexOf('#7C4DFF') >= 0,
    'the tapped block is selected (inline outline)');
  assert(target.getAttribute('data-bolt-ai-m') !== null,
    'the manual pick carries a stable uid');
  assert(env.aiSyncs.length > syncs0, 'the tap notifies Java (Bolt.aiSync — the native circle appears at once)');
  const payload = JSON.parse(env.win.__boltAiRects());
  assert(payload.manual === 1 && payload.r.length === 1 && payload.r[0].k === 'm' && payload.r[0].s === 1,
    'manual picks ride the same rects payload (kind m)');
  env.docDispatch(target);
  assert(!hasCls(target, '__boltAiSel'), 'tapping again deselects');
}

console.log('== P6: the busy guard ==');
{
  const env = makeDom('chat.z.ai', ({ el, body }) => {
    const sc = el('div', 'chat-scroll'); body.appendChild(sc);
    sc.appendChild(el('div', 'markdown', 'A response long enough to be counted as a candidate answer here.'));
  });
  const r1 = JSON.parse(env.run(PICKER));
  assert(r1.n === 1, 'first invoke picks up the response');
  const r2 = JSON.parse(env.run(PICKER));
  assert(r2.n === 0 && r2.err === 'busy', 'second invoke while picking returns busy, no rebuild');
  env.run(RESTORE);
  assert(env.win.__boltAiPick === undefined, 'restore tears the picker fully down');
  const r3 = JSON.parse(env.run(PICKER));
  assert(r3.n === 1, 'after restore the picker can start again');
}

console.log('== P7: per-site selectors (gemini + chatgpt shapes) ==');
{
  const env = makeDom('gemini.google.com', ({ el, body }) => {
    const sc = el('div', 'conversation'); body.appendChild(sc);
    const a = el('model-response', '', 'Gemini answer one with enough characters to be a candidate.');
    const b = el('model-response', '', 'Gemini answer two with enough characters to be a candidate.');
    sc.appendChild(a); sc.appendChild(b);
  });
  const r = JSON.parse(env.run(PICKER));
  assert(r.n === 2, `gemini: model-response custom elements are found (got ${r.n})`);
}
{
  const env = makeDom('chatgpt.com', ({ el, body }) => {
    const sc = el('div', 'thread'); body.appendChild(sc);
    const a = el('div', 'msg'); a.attrs['data-message-author-role'] = 'assistant';
    a.innerText = 'ChatGPT answer one with enough characters to be a candidate.';
    const u = el('div', 'msg'); u.attrs['data-message-author-role'] = 'user';
    u.innerText = 'the user question which is long enough to be a candidate too';
    sc.appendChild(a); sc.appendChild(u);
  });
  const r = JSON.parse(env.run(PICKER));
  assert(r.n === 1, `chatgpt: only assistant messages are found (got ${r.n})`);
}

console.log('== P8: the vector extraction (blocks for the vector PDF) ==');
{
  const env = makeDom('chat.z.ai', ({ el, addText, body }) => {
    const sc = el('div', 'chat-scroll'); body.appendChild(sc);
    const r1 = el('div', 'markdown');
    const h2 = el('h2', ''); addText(h2, 'Running tests');
    const p = el('p', ''); addText(p, 'Use npm test to run the whole suite at once.');
    const pre = el('pre', ''); addText(pre, 'line one\nline two');
    const li = el('li', ''); addText(li, 'a list item worth keeping');
    const p2 = el('p', ''); addText(p2, 'closing paragraph that is long enough to matter.');
    r1.appendChild(h2); r1.appendChild(p); r1.appendChild(pre); r1.appendChild(li); r1.appendChild(p2);
    const r2 = el('div', 'markdown', 'Second answer with plenty of characters to be counted as one.');
    sc.appendChild(r1); sc.appendChild(r2);
  });
  env.run(PICKER);
  env.win.__boltAiSetAll(true);
  const blocks = JSON.parse(env.win.__boltAiBlocks());
  assert(blocks.length === 6, `headings/paragraphs/code/list extract as blocks (got ${blocks.length})`);
  assert(blocks[0].k === 'h2' && blocks[0].t === 'Running tests',
    'the heading keeps its kind + own text');
  assert(blocks[2].k === 'pre' && blocks[2].t.indexOf('line two') >= 0,
    'code blocks keep their line breaks');
  assert(blocks[3].k === 'li', 'list items carry their kind');
  const kinds = blocks.map(b => b.k).join(',');
  assert(kinds === 'h2,p,pre,li,p,p', `document order preserved (got ${kinds})`);
  // unselected -> nothing
  env.win.__boltAiSetAll(false);
  assert(env.win.__boltAiBlocks() === '', 'with nothing selected the extraction is empty');
}

console.log(failures === 0 ? '\nALL TESTS PASSED' : `\n${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
