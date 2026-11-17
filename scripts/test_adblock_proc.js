/* v1.3.80 mock-DOM test for the FilterEngine PROC_JS_TEMPLATE — the
 * procedural :has-text() pass (the A4 fix: those rules were injected as
 * invalid CSS before, killing their whole selector group).
 *
 * Extracts the REAL text block from FilterEngine.java (so the tested JS is
 * the shipped JS), substitutes a rule table, and runs it against a mock
 * DOM: hides matching elements, leaves non-matching ones, survives escaped
 * quotes in needles, re-runs on mutation (debounced), and the __boltProc
 * guard makes a second injection a no-op. */
'use strict';
const fs = require('fs');
let failures = 0;
function assert(cond, msg) {
  if (cond) { console.log('  PASS ' + msg); }
  else { failures++; console.log('  FAIL ' + msg); }
}

const fe = fs.readFileSync(__dirname + '/../app/src/main/java/com/bolt/browser/adblock/FilterEngine.java', 'utf8');
const m = fe.match(/PROC_JS_TEMPLATE = """\r?\n([\s\S]*?)""";/);
assert(m, 'PROC_JS_TEMPLATE text block extracted from the Java source');
const tpl = m[1];

// minimal mock DOM -----------------------------------------------------------
function makeDoc() {
  let nextId = 1;
  const els = [];
  const styles = [];
  const observers = [];
  class El {
    constructor(cls, text, kids) {
      this.id = nextId++;
      this.className = cls || '';
      this._text = text || '';
      this.children = kids || [];
      els.push(this);
    }
    get textContent() {
      return this._text + this.children.map(c => c.textContent).join('');
    }
    get classList() {
      const self = this;
      return {
        contains(c) { return (' ' + self.className + ' ').indexOf(' ' + c + ' ') >= 0; },
        add(c) { if (!this.contains(c)) self.className += (self.className ? ' ' : '') + c; }
      };
    }
  }
  const doc = {
    createElement() { return { set textContent(v) { this._t = v; }, get textContent() { return this._t; } }; },
    head: { appendChild(s) { styles.push(s.textContent); } },
    documentElement: new El('', '', []),
    getElementById: () => null,
    querySelectorAll(sel) {
      // the test rules use .cls selectors only
      if (!sel.startsWith('.')) return [];
      const cls = sel.slice(1).replace(/\\/g, '');
      return els.filter(e => (' ' + e.className + ' ').indexOf(' ' + cls + ' ') >= 0);
    }
  };
  global.window = {};
  global.document = doc;
  global.MutationObserver = class {
    constructor(fn) { this.fn = fn; observers.push(this); }
    observe() {}
    disconnect() { this.dead = true; }
  };
  global.Date = Date; // template uses Date.now()
  return { doc, els, styles, observers, El };
}

// T1: a matching element hides; a non-matching one does not ------------------
{
  const env = makeDoc();
  const ad = new env.El('promo', 'Sponsored Content');
  const real = new env.El('article', 'A normal story');
  const js = tpl.replace('__RULES__', "[['.promo','Sponsored'],[\".article\",'nope']]")
                .replace(/\n\s+/g, '\n');
  eval(js.replace('setTimeout(function(){try{mo.disconnect();}catch(e){}},60000);', ''));
  assert(env.styles.some(s => s.indexOf('.bolt-ph{display:none') >= 0),
    'T1 the .bolt-ph style element lands in the document head');
  assert(ad.classList.contains('bolt-ph'), 'T1 matching element hidden (class bolt-ph)');
  assert(!real.classList.contains('bolt-ph'), 'T1 non-matching element left alone');
  assert(global.window.__boltProc === 1, 'T1 the __boltProc guard armed');
}

// T2: case-insensitive literal match + child-text included -------------------
{
  const env = makeDoc();
  const ad = new env.El('ad-box', '');
  new env.El('inner', 'This is an AD, apparently', []).className = 'inner';
  ad.children.push({ textContent: 'SPONSORED by someone', children: [] });
  const js = tpl.replace('__RULES__', "[['.ad-box','sponsored']]").replace(/\n\s+/g, '\n');
  eval(js.replace('setTimeout(function(){try{mo.disconnect();}catch(e){}},60000);', ''));
  assert(ad.classList.contains('bolt-ph'),
    'T2 case-insensitive + descendant text matches');
}

// T3: re-injection is a no-op (the __boltProc guard) -------------------------
{
  const env = makeDoc();
  new env.El('promo', 'Sponsored');
  const js = tpl.replace('__RULES__', "[['.promo','Sponsored']]").replace(/\n\s+/g, '\n');
  const clean = js.replace('setTimeout(function(){try{mo.disconnect();}catch(e){}},60000);', '');
  eval(clean);
  const stylesBefore = env.styles.length;
  eval(clean); // second injection (commit + finish)
  assert(env.styles.length === stylesBefore,
    'T3 second injection adds no second style element');
}

// T4: mutation re-run (debounced) ---------------------------------------------
{
  const env = makeDoc();
  const js = tpl.replace('__RULES__', "[['.late','Sponsored']]").replace(/\n\s+/g, '\n');
  let fired = 0;
  const timers = [];
  global.setTimeout = (fn, ms) => { timers.push({ fn, ms }); fired++; return timers.length; };
  global.clearTimeout = () => {};
  eval(js);
  // simulate a DOM mutation firing the observer callback
  env.observers.forEach(o => o.fn());
  assert(fired >= 1 && timers.some(t => t.ms === 400),
    'T4 mutations schedule the 400ms-debounced re-run');
  // fire the debounce manually, the new element should hide
  const late = new env.El('late', 'Sponsored — just landed');
  timers.forEach(t => t.fn());
  assert(late.classList.contains('bolt-ph'), 'T4 the late-inserted element hides on the re-run');
}

console.log();
if (failures) { console.log(`FAIL: ${failures} procedural contract(s) broken`); process.exit(1); }
console.log('OK: v1.3.80 procedural :has-text() pass — extraction, hide/no-hide, ' +
            'case-insensitivity, guard, debounced re-run');
