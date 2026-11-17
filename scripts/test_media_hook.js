/* Mock-DOM test suite for the JS produced by MediaHook.core() (v1.3.13).
 * Loads /tmp/media_hook_core.js (extracted from the Java source by
 * scripts/check_js.py) and exercises the merged media engine:
 * spoof getters + reversibility + re-spoof, away-event swallowing (page-level
 * only), media state machine on <video> AND <audio>, commands, time beats,
 * v1.3.18 title priority / artwork harvest / metadatachange re-report,
 * v1.3.28 deterministic YouTube artwork, late-duration beat re-send and
 * SPA-navigation re-reports. */
'use strict';
const fs = require('fs');
var v1379ran = false;   // hoisted: the async chains can fire before the tail definitions

let failures = 0;
function assert(cond, msg) {
  if (cond) { console.log('  PASS ' + msg); }
  else { failures++; console.log('  FAIL ' + msg); }
}
function assertEq(actual, expected, msg) {
  if (actual === expected) { console.log('  PASS ' + msg); }
  else { failures++; console.log('  FAIL ' + msg + ' (actual=' + actual + ', expected=' + expected + ')'); }
}

const HOOK = fs.readFileSync('/tmp/media_hook_core.js', 'utf8')
  .replace('var SET={};', 'var SET={vol:100,vocal:false,speed:1,bgPlay:true,booster:false,noCookies:false};');

function makeEnv(opts) {
  opts = opts || {};
  const events = [];
  const otpCalls = [];   // v1.3.31: Bolt.otpField(true/false) reports
  const listeners = new Map();
  function reg(node, type, fn, opts) {
    const capture = opts === true || !!(opts && opts.capture);
    if (!listeners.has(node)) listeners.set(node, new Map());
    const m = listeners.get(node);
    if (!m.has(type)) m.set(type, []);
    m.get(type).push({ fn, capture });
  }
  function unreg(node, type, fn, opts) {
    const capture = opts === true || !!(opts && opts.capture);
    const m = listeners.get(node);
    if (!m) return;
    const arr = m.get(type);
    if (!arr) return;
    const i = arr.findIndex(h => h.fn === fn && h.capture === capture);
    if (i >= 0) arr.splice(i, 1);
  }
  function runAt(node, ev, captureOnly) {
    const m = listeners.get(node);
    if (!m) return;
    const arr = m.get(ev.type);
    if (!arr) return;
    for (const h of [...arr]) {
      if (captureOnly && !h.capture) continue;
      try { h.fn.call(node, ev); } catch (e) {}
      if (ev.__stopped) return;
    }
  }
  function dispatch(type, target, extra) {
    const ev = {
      type, target, __stopped: false,
      stopImmediatePropagation() { this.__stopped = true; },
      stopPropagation() { this.__stopped = true; },
      preventDefault() { }
    };
    if (extra) Object.assign(ev, extra);   // v1.3.69: marked resync events (__boltRS)
    runAt(win, ev, true);
    if (ev.__stopped) return ev;
    runAt(doc, ev, true);
    if (ev.__stopped) return ev;
    runAt(target, ev, false);
    return ev;
  }

  let reallyHidden = false;
  const DocumentProto = {};
  Object.defineProperty(DocumentProto, 'hidden', { configurable: true, get: () => reallyHidden });
  Object.defineProperty(DocumentProto, 'visibilityState', { configurable: true, get: () => reallyHidden ? 'hidden' : 'visible' });
  // v1.3.67: the EXIT GATE's input — a real Document.prototype.exitFullscreen
  // installed BEFORE the hook runs, so __boltWrapExit wraps it (counted)
  let exitCalls = 0;
  DocumentProto.exitFullscreen = function () { exitCalls++; };

  const win = { addEventListener: (t, f, o) => reg(win, t, f, o), removeEventListener: (t, f, o) => unreg(win, t, f, o) };
  // v1.3.63 document-fullscreen overlay sweep inputs: computed styles for
  // body-level overlays (tests set el.__cs; default = static/auto = skipped)
  win.getComputedStyle = (el) => (el && el.__cs) || { position: 'static', zIndex: 'auto' };
  // v1.3.61 pseudo-fullscreen watcher inputs: viewport size + the
  // (mock) element currently in renderer fullscreen
  let fullscreenEl = null;
  win.innerWidth = opts.vw || 393;
  win.innerHeight = opts.vh || 800;
  // v1.3.69 — outer-size spoof inputs: a screen object + the pre-PiP
  // outer sizes (plain values; the hook shadows them with armed-guarded
  // accessors at boot). Absent by default: the spoof stays inert and
  // every pre-existing test sees an untouched window.
  if (opts.screen) win.screen = opts.screen;
  if (opts.outer) { win.outerWidth = opts.outer[0]; win.outerHeight = opts.outer[1]; }
  // v1.3.69 T34: MUTABLE outer sizes (an accessor over live vars) so a test
  // can shrink the "window" the way Android PiP does — the hook's install
  // captures the accessor as `orig` and the disarmed reads delegate to it
  if (opts.outerLive) {
    let ow = opts.outer ? opts.outer[0] : 393, oh = opts.outer ? opts.outer[1] : 800;
    Object.defineProperty(win, 'outerWidth', { configurable: true, get: () => ow });
    Object.defineProperty(win, 'outerHeight', { configurable: true, get: () => oh });
    win.__setOuter = (w, h) => { ow = w; oh = h; };
  }
  const doc = {
    addEventListener: (t, f, o) => reg(doc, t, f, o),
    removeEventListener: (t, f, o) => unreg(doc, t, f, o),
    querySelector: (sel) => {
      if (opts.ogTitle && /og:title/.test(sel)) return { content: opts.ogTitle };
      if (opts.ogImage && /og:image/.test(sel)) return { content: opts.ogImage };
      return null;
    },
    title: opts.docTitle !== undefined ? opts.docTitle : 'Mock Page',
    createElement: () => ({ style: {}, set id(v) {}, get id() { return ''; }, remove() {}, appendChild() {} }),
    documentElement: { appendChild() {}, children: [] },
    body: { innerText: 'hello world', children: [] },
    get fullscreenElement() { return fullscreenEl; },
    set fullscreenElement(v) { fullscreenEl = v; }
  };
  Object.setPrototypeOf(doc, DocumentProto);
  doc.hasFocus = function () { return !reallyHidden; };

  const HTMLMediaElement = class HTMLMediaElement { };
  class FakeMedia {
    constructor(tag) {
      this.tagName = tag.toUpperCase();
      this.paused = true; this.ended = false;
      this.currentTime = 0; this.duration = 600; this.playbackRate = 1;
      this.isConnected = true; this.title = '';
      // v1.3.61 pseudo-watcher geometry: an inline (non-covering) video by
      // default, tests override .rect / .videoWidth / .videoHeight
      this.videoWidth = 1280; this.videoHeight = 720;
      this.rect = { left: 10, top: 300, width: 373, height: 210 };
      this.getBoundingClientRect = () => this.rect;
    }
    fire(type) { dispatch(type, this); }
    play() { this.paused = false; this.fire('play'); return Promise.resolve(); }
    // v1.3.67: pause lives on the SHARED prototype (one link up the chain)
    // so the hook's HTMLMediaElement.prototype.pause wrap — the PAUSE
    // DECODER — is exercised for real: v.pause() goes through the wrap,
    // records __boltPPS while armed, then calls through
    end() { this.ended = true; this.fire('ended'); }
  }
  Object.setPrototypeOf(FakeMedia.prototype, HTMLMediaElement.prototype);
  HTMLMediaElement.prototype.pause = function () { this.paused = true; this.fire('pause'); };
  // querySelectorAll for scan(): returns media elements
  const knownMedia = [];
  doc.querySelectorAll = (sel) => sel === 'video,audio' ? knownMedia : [];

  const Bolt = {
    // v1.3.61: 'pseudo'/'fserr' carry bare "0"/"1" payloads — parse when
    // possible, keep the raw string otherwise (state events stay objects)
    mediaEvent: (name, json) => events.push({ name, o: (() => { try { return JSON.parse(json); } catch (e) { return json; } })() }),
    inspectorPick: () => {},
    // v1.3.31: OTP field focus reports (built-in authenticator page detection)
    otpField: (b) => otpCalls.push(b),
    // v1.3.79: username-first login detection + the favicon extractor's
    // landing (recorded for the new tests)
    pwField: (h, b) => pwCalls.push(b),   // v1.3.91: (host, focused)
    // v1.4.4: the submit report (host, user, pass) — the remembered-username tests
    pwSubmit: (h, u, p) => pwSubmits.push([h, u, p]),
    favIcon: () => {},
    favMiss: () => {}
  };
  const pwCalls = [];
  const pwSubmits = [];

  // v1.3.18: mock navigator.mediaSession (page-side MediaSession metadata —
  // the primary source for notification title + artwork).
  const msListeners = new Map();
  const ms = opts.noMs ? undefined : (opts.ms || {
    metadata: null,
    addEventListener(type, fn) {
      if (!msListeners.has(type)) msListeners.set(type, []);
      msListeners.get(type).push(fn);
    },
    fire(type) { (msListeners.get(type) || []).forEach(f => f({ type })); }
  });
  const nav = { mediaSession: ms };

  // v1.3.29: record every setTimeout DELAY the hook requests (clamped to 5 ms
  // for execution, so cadence tests observe the REQUESTED value).
  const timerLog = [];

  const env = {
    window: win, document: doc, events, dispatch, FakeMedia, knownMedia, listeners, otpCalls, pwCalls, pwSubmits,
    setHidden(h) { reallyHidden = h; },
    getExitCalls: () => exitCalls,
    performance: { now: () => Date.now() },
    ms, nav, timers: timerLog,
    fireMetadataChange() { if (ms && ms.fire) ms.fire('metadatachange'); }
  };

  win.requestAnimationFrame = (cb) => setTimeout(() => cb(Date.now()), 5);
  win.cancelAnimationFrame = (id) => clearTimeout(id);

  const fn = new Function('window', 'document', 'Document', 'HTMLMediaElement',
    'performance', 'setTimeout', 'clearTimeout', 'setInterval', 'location', 'Bolt', 'navigator', HOOK);
  fn(win, doc, { prototype: DocumentProto }, HTMLMediaElement, env.performance,
    (cb, ms) => { timerLog.push(ms || 0); return setTimeout(cb, Math.min(ms || 0, 5)); }, clearTimeout,
    () => 0, opts.loc || { href: 'https://example.com/v' }, Bolt, nav);

  return env;
}

console.log('== T1: spoof getters + hasFocus ==');
{
  const e = makeEnv();
  assert(e.document.hidden === false, 'document.hidden spoofed false');
  assert(e.document.visibilityState === 'visible', 'visibilityState spoofed visible');
  assert(e.document.hasFocus() === true, 'hasFocus true');
  e.setHidden(true);
  assert(e.document.hidden === false, 'hidden still spoofed when really hidden');
  assert(e.document.visibilityState === 'visible', 'visibilityState still spoofed');
}

console.log('== T2: away-event swallowing (page-level only) ==');
{
  const e = makeEnv();
  let sawHidden = 0, sawBlur = 0, elemSaw = 0;
  e.document.addEventListener('visibilitychange', () => { if (e.document.hidden) sawHidden++; });
  e.window.addEventListener('blur', () => { sawBlur++; });
  e.document.addEventListener('blur', (ev) => { if (ev.target && ev.target.tagName === 'INPUT') elemSaw++; }, true);

  e.setHidden(true);
  e.dispatch('visibilitychange', e.document);
  assert(sawHidden === 0, 'page visibilitychange swallowed while hidden');
  e.dispatch('blur', e.window);
  assert(sawBlur === 0, 'page window blur swallowed while hidden');
  e.setHidden(false);
  const input = { tagName: 'INPUT' };
  e.dispatch('blur', input);
  assertEq(elemSaw, 1, 'element-level blur NOT swallowed (visible)');
  e.dispatch('blur', e.window);
  assertEq(sawBlur, 1, 'window blur passes through when visible');
  e.setHidden(true);
  e.dispatch('blur', input);
  assertEq(elemSaw, 1, 'element blur NOT delivered to element handlers even while hidden (delegated doc capture is swallowed, acceptable)');
}

console.log('== T3: media state machine (video AND audio) ==');
{
  const e = makeEnv();
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  let states = e.events.filter(x => x.name === 'state').map(x => x.o.playing);
  assert(states[states.length - 1] === true, 'video play -> state playing');
  v.pause();
  states = e.events.filter(x => x.name === 'state').map(x => x.o.playing);
  assert(states[states.length - 1] === false, 'video pause -> state paused');
  v.end();
  states = e.events.filter(x => x.name === 'state').map(x => x.o.playing);
  assert(states[states.length - 1] === false, 'video ended -> state not playing');

  const a = new e.FakeMedia('audio');
  e.knownMedia.push(a);
  a.play();
  states = e.events.filter(x => x.name === 'state').map(x => x.o.playing);
  assert(states[states.length - 1] === true, 'AUDIO element also tracked (any-site)');
}

console.log('== T4: commands ==');
{
  const e = makeEnv();
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  v.pause();
  e.window.__boltCmd('play');
  assert(v.paused === false, 'cmd play resumes');
  e.window.__boltCmd('pause');
  assert(v.paused === true, 'cmd pause pauses');
  e.window.__boltCmd('toggle');
  assert(v.paused === false, 'cmd toggle resumes');
  e.window.__boltCmd('seek', -10);
  assert(Math.abs(v.currentTime - 0) < 0.01, 'cmd seek clamped at 0');
  v.currentTime = 100;
  e.window.__boltCmd('seek', 10);
  assert(Math.abs(v.currentTime - 110) < 0.01, 'cmd seek +10 works');
  e.window.__boltCmd('seekTo', 42);
  assert(Math.abs(v.currentTime - 42) < 0.01, 'cmd seekTo absolute works');
  e.window.__boltCmd('speed', 2);
  assert(v.playbackRate === 2, 'cmd speed works');
}

console.log('== T5: settings push / re-spoof ==');
{
  const e = makeEnv();
  e.window.__boltSetMedia('{"vol":100,"vocal":false,"speed":1,"bgPlay":false,"booster":false,"noCookies":false}');
  e.setHidden(true);
  assert(e.document.hidden === true, 'bgPlay=false -> hidden real again');
  assert(e.document.hasFocus() === false, 'hasFocus real again');
  let saw = 0;
  e.document.addEventListener('visibilitychange', () => saw++);
  e.dispatch('visibilitychange', e.document);
  assert(saw === 1, 'visibilitychange delivered after unspoof');
  e.window.__boltSetMedia('{"vol":100,"vocal":false,"speed":1,"bgPlay":true,"booster":false,"noCookies":false}');
  assert(e.document.hidden === false, 're-spoof restores hidden=false');
  saw = 0;
  e.dispatch('visibilitychange', e.document);
  assert(saw === 0, 'visibilitychange swallowed again after re-spoof');
}

console.log('== T6: time beats while playing ==');
{
  const e = makeEnv();
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  v.currentTime = 30;
  v.fire('timeupdate');
  const times = e.events.filter(x => x.name === 'time');
  assert(times.length === 1 && Math.abs(times[0].o.pos - 30) < 0.01, 'time beat sent on timeupdate');
  v.currentTime = 31;
  v.fire('timeupdate');
  const times2 = e.events.filter(x => x.name === 'time');
  assert(times2.length === 1, 'time beats throttled (~1s)');
}

console.log('== T7: title reporting ==');
{
  const e = makeEnv();
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  const st = e.events.filter(x => x.name === 'state').pop();
  assert(st.o.title === 'Mock Page', 'state includes page title');
}

console.log('== T8: title priority (mediaSession > document.title > og:title) ==');
{
  // mediaSession metadata title wins
  const e = makeEnv({ ms: { metadata: { title: 'MS Title' } }, docTitle: 'Doc Title', ogTitle: 'OG Title' });
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  const st = e.events.filter(x => x.name === 'state').pop();
  assertEq(st.o.title, 'MS Title', 'mediaSession metadata title wins');

  // no mediaSession -> document.title, " - YouTube" suffix trimmed
  const e2 = makeEnv({ noMs: true, docTitle: 'My Video - YouTube' });
  const v2 = new e2.FakeMedia('video');
  e2.knownMedia.push(v2);
  v2.play();
  const st2 = e2.events.filter(x => x.name === 'state').pop();
  assertEq(st2.o.title, 'My Video', 'document.title fallback with YouTube suffix trimmed');

  // no mediaSession, no document.title -> og:title
  const e3 = makeEnv({ noMs: true, docTitle: '', ogTitle: 'OG Title' });
  const v3 = new e3.FakeMedia('video');
  e3.knownMedia.push(v3);
  v3.play();
  const st3 = e3.events.filter(x => x.name === 'state').pop();
  assertEq(st3.o.title, 'OG Title', 'og:title is the last resort');
}

console.log('== T9: video switch re-reports (playing -> playing) ==');
{
  const e = makeEnv();
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  const before = e.events.filter(x => x.name === 'state').length;
  // SPA video switch: no pause in between, only the metadata changes
  e.ms.metadata = { title: 'Video Y' };
  e.fireMetadataChange();
  const states = e.events.filter(x => x.name === 'state');
  assert(states.length > before, 'metadatachange triggers a state re-report');
  assertEq(states[states.length - 1].o.title, 'Video Y', 'new title delivered on video switch');
  assert(states[states.length - 1].o.playing === true, 'still playing in the re-report');

  // same transition through a fresh play event (some sites re-fire play)
  e.ms.metadata = { title: 'Video Z' };
  v.fire('playing');
  const states2 = e.events.filter(x => x.name === 'state');
  assertEq(states2[states2.length - 1].o.title, 'Video Z', 're-fired play event carries new title');
}

console.log('== T10: artwork harvest ==');
{
  // mediaSession artwork: largest declared size wins
  const e = makeEnv({ ms: { metadata: { title: 'T',
    artwork: [{ src: 'small.jpg', sizes: '96x96' }, { src: 'big.jpg', sizes: '1280x720' }] } } });
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  const st = e.events.filter(x => x.name === 'state').pop();
  assertEq(st.o.art, 'big.jpg', 'largest artwork picked');

  // no artwork array -> video poster
  const e2 = makeEnv();
  const v2 = new e2.FakeMedia('video');
  v2.poster = 'poster.jpg';
  e2.knownMedia.push(v2);
  v2.play();
  const st2 = e2.events.filter(x => x.name === 'state').pop();
  assertEq(st2.o.art, 'poster.jpg', 'video.poster fallback');

  // nothing -> empty string (Java side keeps the previous thumbnail)
  const e3 = makeEnv({ noMs: true });
  const v3 = new e3.FakeMedia('video');
  e3.knownMedia.push(v3);
  v3.play();
  const st3 = e3.events.filter(x => x.name === 'state').pop();
  assertEq(st3.o.art, '', 'no artwork -> empty art field');
}

console.log('== T11: deterministic YouTube artwork from the watch URL ==');
{
  // on a YouTube watch page the URL's video id beats page-declared artwork:
  // it changes the instant the next queued video starts, immune to
  // metadata-plumbing lag (the never-updating notification thumbnail)
  const loc = { href: 'https://m.youtube.com/watch?v=dQw4w9WgXcQ',
    hostname: 'm.youtube.com', search: '?v=dQw4w9WgXcQ', pathname: '/watch' };
  const e = makeEnv({ loc, ms: { metadata: { title: 'T',
    artwork: [{ src: 'stale-player-art.jpg', sizes: '1280x720' }] } } });
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  const st = e.events.filter(x => x.name === 'state').pop();
  assertEq(st.o.art, 'https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg',
    'watch-URL art beats page-declared artwork');

  // the next queued video = a new URL with no new media element: any media
  // event re-report now carries the new video's art
  loc.search = '?v=abCDEfgHIjk'; loc.href = 'https://m.youtube.com/watch?v=abCDEfgHIjk';
  v.fire('playing');
  const st2 = e.events.filter(x => x.name === 'state').pop();
  assertEq(st2.o.art, 'https://i.ytimg.com/vi/abCDEfgHIjk/mqdefault.jpg',
    'URL change -> new thumbnail URL in the next report');

  // non-YouTube pages: unchanged priority chain (mediaSession > poster > og)
  const e2 = makeEnv({ ms: { metadata: { title: 'T',
    artwork: [{ src: 'big.jpg', sizes: '1280x720' }] } } });
  const v2 = new e2.FakeMedia('video');
  e2.knownMedia.push(v2);
  v2.play();
  const st3 = e2.events.filter(x => x.name === 'state').pop();
  assertEq(st3.o.art, 'big.jpg', 'non-YouTube page still uses page artwork');

  // shorts/embed/live path form
  const loc2 = { href: 'https://www.youtube.com/shorts/XYz12345678',
    hostname: 'www.youtube.com', search: '', pathname: '/shorts/XYz12345678' };
  const e3 = makeEnv({ loc: loc2 });
  const v3 = new e3.FakeMedia('video');
  e3.knownMedia.push(v3);
  v3.play();
  const st4 = e3.events.filter(x => x.name === 'state').pop();
  assertEq(st4.o.art, 'https://i.ytimg.com/vi/XYz12345678/mqdefault.jpg',
    'shorts URL form also yields a thumbnail');
}

setTimeout(() => {
  console.log('== T12: 1 Hz beat re-sends state when duration changes late ==');
  // The SPA race that left the notification showing the previous video's
  // seekbar total: loadedmetadata lands AFTER play, with no media event to
  // re-report — only the beat catches it (v1.3.28 widened the title-only
  // re-send check to art + duration).
  const e = makeEnv();
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  v.currentTime = 10;
  v.fire('timeupdate');               // beat 1: dur 600 recorded
  v.duration = 217;                   // next queued video loaded, dur landed late
  setTimeout(() => {
    v.currentTime = 12;
    v.fire('timeupdate');             // beat 2 (>900 ms later): dur moved
    const states = e.events.filter(x => x.name === 'state');
    const last = states[states.length - 1];
    assertEq(last.o.dur, 217, 'beat re-sends state with the new duration');
    assert(last.o.playing === true, 'still playing in the late-duration re-send');
    t13();
  }, 1050);
}, 60);

/* v1.3.29: controllable clock for the hidden-cadence tests (the hook's
 * throttles read Date.now(); the harness's clamped timers stay real-time).
 * ONLY usable for synchronous test bodies — async continuations run with the
 * real clock. */
function withClock(start, fn) {
  const real = Date.now;
  let t = start;
  Date.now = () => t;
  try { fn({ advance: (ms) => { t += ms; }, now: () => t }); }
  finally { Date.now = real; }
}

console.log('== T14: hidden time beats throttle to 10 s ==');
{
  withClock(1_000_000, (clock) => {
    const e = makeEnv();
    const v = new e.FakeMedia('video');
    e.knownMedia.push(v);
    v.play();
    e.setHidden(true);
    v.currentTime = 10;
    v.fire('timeupdate');                 // beat 1 (hidden cadence armed)
    assert(e.events.filter(x => x.name === 'time').length === 1, 'first hidden beat sent');
    clock.advance(5_000);
    v.fire('timeupdate');                 // 5 s < 10 s → suppressed
    assert(e.events.filter(x => x.name === 'time').length === 1,
      'hidden beat at +5 s suppressed (10 s cadence)');
    clock.advance(6_000);                 // 11 s since the last beat
    v.fire('timeupdate');                 // beat 2
    assert(e.events.filter(x => x.name === 'time').length === 2,
      'hidden beat at +11 s delivered');
  });
}

console.log('== T15: hidden metadata poll gated to 3 s (late duration still lands) ==');
{
  withClock(2_000_000, (clock) => {
    const e = makeEnv();
    const v = new e.FakeMedia('video');
    e.knownMedia.push(v);
    v.play();
    e.setHidden(true);
    v.currentTime = 5;
    v.fire('timeupdate');                 // beat 1 + poll 1 (dur 600)
    clock.advance(1_500);
    v.duration = 217;
    v.fire('timeupdate');                 // beat throttled → poll never runs
    assert(e.events.filter(x => x.name === 'state').every(x => x.o.dur !== 217),
      'no metadata re-send before the next poll window');
    clock.advance(9_000);                 // 10.5 s since beat 1 → beat 2 + poll
    v.fire('timeupdate');
    const states = e.events.filter(x => x.name === 'state');
    assert(states.length > 0 && states[states.length - 1].o.dur === 217,
      'late duration re-sent at the next hidden beat+poll');
  });
}

console.log('== T16: concealment sentinel auto-resumes a hidden non-user pause ==');
{
  const e = makeEnv();
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  e.setHidden(true);
  v.currentTime = 20;
  v.fire('timeupdate');                   // detects the flip → arms the sentinel
  const statesBefore = e.events.filter(x => x.name === 'state').length;
  v.pause();                              // non-user pause while REALLY hidden
  assert(e.events.filter(x => x.name === 'state').length === statesBefore,
    'pause not reported while the sentinel fights');
  setTimeout(() => {
    assert(!v.paused, 'sentinel resumed the element');
    const states = e.events.filter(x => x.name === 'state');
    assert(states.length > 0 && states[states.length - 1].o.playing === true,
      'playing re-reported after the auto-resume');
    assert(e.events.every(x => x.name !== 'concealfail'), 'no concealfail on a winnable fight');
    t17();
  }, 40);
}

function t17() {
  console.log('== T17: sentinel gives up → concealfail + pause reported ==');
  const e = makeEnv();
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  e.setHidden(true);
  v.currentTime = 20;
  v.fire('timeupdate');                   // arm
  v.play = () => { /* refuses: stays paused, fires nothing */ };
  v.pause();
  setTimeout(() => {
    assert(e.events.some(x => x.name === 'concealfail'),
      'concealfail posted after the bounded retries lose');
    const states = e.events.filter(x => x.name === 'state');
    assert(states.length > 0 && states[states.length - 1].o.playing === false,
      'pause reported once the fight is lost');
    t18();
  }, 60);
}

function t18() {
  console.log('== T18: diverted rAF ticks at 250 ms while hidden (not frame rate) ==');
  {
    const e = makeEnv();
    e.setHidden(true);
    const n0 = e.timers.length;
    e.window.requestAnimationFrame(() => {});
    assert(e.timers.length === n0 + 1 && e.timers[e.timers.length - 1] === 250,
      'hidden rAF diverted to a 250 ms timer');
    e.setHidden(false);
    const n1 = e.timers.length;
    e.window.requestAnimationFrame(() => {});
    assert(e.timers.length === n1, 'visible rAF goes to the native path (no timer)');
  }
  t19();
}

function t19() {
  console.log('== T19: scan cadence backs off while hidden ==');
  const e = makeEnv();
  e.setHidden(true);
  e.knownMedia.push(new e.FakeMedia('video'));
  setTimeout(() => {
    // v1.3.30: hidden-with-media also 8 s (event-driven detection covers new
    // elements; the scan loop is booster belt-and-braces only)
    assert(e.timers.includes(8000), 'hidden scan WITH media backs off to 8 s');
    const e2 = makeEnv();
    e2.setHidden(true);
    setTimeout(() => {
      assert(e2.timers.includes(8000), 'hidden scan with NO media backs off to 8 s');
      assert(e2.timers.includes(1000) || e2.timers.length > 0, 'visible scan cadence baseline exists');
      t13();
    }, 30);
  }, 30);
}

function t13() {
  console.log('== T13: SPA navigation triggers re-reports ==');
  // autoplay/queue switches are pushState navigations: no page load, the
  // video element is reused — YouTube fires yt-navigate-finish, every SPA
  // emits popstate/pushState (v1.3.28 watchers)
  const e = makeEnv({ ms: { metadata: { title: 'First' } } });
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);
  v.play();
  const before = e.events.filter(x => x.name === 'state').length;
  e.ms.metadata = { title: 'Second (autoplayed)' };
  e.dispatch('yt-navigate-finish', e.document);
  e.dispatch('popstate', e.window);
  setTimeout(() => {
    const states = e.events.filter(x => x.name === 'state');
    assert(states.length > before, 'yt-navigate-finish + popstate re-reported state');
    assertEq(states[states.length - 1].o.title, 'Second (autoplayed)',
      'new title delivered after SPA navigation');
    t20();
  }, 40);
}

/* v1.3.31: OTP field detection — the page side of the built-in authenticator.
 * A focused 2FA input (autocomplete=one-time-code etc.) must report
 * Bolt.otpField(true); blur reports false; __boltFillOtp writes the code into
 * the armed field with input+change events. */
function t20() {
  console.log('== T20: OTP field detection + fill (authenticator) ==');
  const e = makeEnv();
  const fired = [];
  const mkInput = (attrs) => ({
    tagName: 'INPUT',
    name: attrs.name || '', id: attrs.id || '', type: attrs.type || 'text',
    className: '', value: '',
    getAttribute: (n) => attrs[n] !== undefined ? String(attrs[n]) : null,
    dispatchEvent: (ev) => { fired.push(ev && ev.type); return true; },
    addEventListener() {}, removeEventListener() {}
  });
  const otp = mkInput({ autocomplete: 'one-time-code', name: 'otp' });
  const plain = mkInput({ type: 'email', name: 'email' });

  e.dispatch('focusin', otp);
  assert(e.otpCalls.length === 1 && e.otpCalls[0] === true,
    'focus on a one-time-code input reported otpField(true)');
  // heuristic channels: numeric inputmode with maxlength 6, and name=totp
  const numeric = mkInput({ inputmode: 'numeric', maxlength: 6, name: 'code2' });
  e.dispatch('focusin', numeric);
  assert(e.otpCalls.length === 1, 'second OTP-style focus does not re-report (still armed)');
  e.dispatch('focusin', plain);
  assert(e.otpCalls.length === 2 && e.otpCalls[1] === false,
    'focus on a normal input reports otpField(false)');

  // re-arm and fill through the exposed API
  e.dispatch('focusin', otp);
  assert(e.otpCalls.length === 3 && e.otpCalls[2] === true, 're-focus re-arms the report');
  assert(typeof e.window.__boltFillOtp === 'function', '__boltFillOtp exposed');
  const okFill = e.window.__boltFillOtp('123456');
  assert(okFill === true, '__boltFillOtp fills the armed field');
  assertEq(otp.value, '123456', 'the OTP input received the code');
  assert(fired.includes('input') && fired.includes('change'),
    'input + change events dispatched to the page');
  // v1.3.61: falls through to T12, which owns finish() now
}

console.log('== T12: v1.3.61 pseudo-fullscreen watcher ==');
{
  const e = makeEnv();
  const pseudoEvents = () => e.events.filter(x => x.name === 'pseudo').map(x => x.o);
  const v = new e.FakeMedia('video');
  e.knownMedia.push(v);

  // inline (non-covering) video playing: NO pseudo report
  v.play();
  e.dispatch('resize', e.window);
  assert(pseudoEvents().length === 0, 'inline video: no pseudo report');

  // the player CSS-maximizes (covers ~the whole viewport): pseudo ON,
  // exactly once (edge-triggered)
  v.rect = { left: 0, top: 0, width: 393, height: 800 };
  e.dispatch('resize', e.window);
  assert(pseudoEvents().length === 1 && pseudoEvents()[0] === 1,
    'covering video without document.fullscreenElement -> pseudo 1');
  e.dispatch('resize', e.window);
  assert(pseudoEvents().length === 1, 'pseudo is edge-triggered (no duplicate)');

  // the real Fullscreen API engages (fullscreenElement set): the watcher
  // must clear the pseudo state — the app path owns the sweep now
  e.document.fullscreenElement = { tagName: 'DIV' };
  e.dispatch('fullscreenchange', e.document);
  setTimeout(() => {
    assert(pseudoEvents().length === 2 && pseudoEvents()[1] === 0,
      'document.fullscreenElement set -> pseudo 0 (real fullscreen owns it)');

    // fullscreen exits and the page stays CSS-maximized: pseudo back ON
    e.document.fullscreenElement = null;
    e.dispatch('resize', e.window);
    assert(pseudoEvents().length === 3 && pseudoEvents()[2] === 1,
      'fullscreen exit with a still-covering video -> pseudo 1 again');

    // video paused / ended: no covering PLAYING video -> pseudo OFF
    v.pause();
    e.dispatch('resize', e.window);
    assert(pseudoEvents().length === 4 && pseudoEvents()[3] === 0,
      'paused video -> pseudo 0');

    // fullscreenerror evidence channel
    e.dispatch('fullscreenerror', e.document);
    assert(e.events.some(x => x.name === 'fserr'),
      'fullscreenerror reported (fserr evidence)');

    t13();
  }, 30);
  return;
}

// v1.3.63 — document-fullscreen overlay concealment (desktop YouTube
// fullscreens <html>: body-level userscript panels are INSIDE the fullscreen
// element and the top layer cannot cover them; the sweep hides ≥1e8-z
// fixed/absolute body children while document fullscreen is live)
function t13() {
  console.log('== T13: v1.3.63 document-fullscreen overlay concealment ==');
  const e = makeEnv();
  const mk = (tag, pos, z) => ({ tagName: tag, style: { visibility: '' },
      __cs: { position: pos, zIndex: String(z) } });
  const panel = mk('DIV', 'fixed', 2147483647);   // the userscript panel
  const lowz  = mk('DIV', 'fixed', 5000);         // site chrome — untouched
  const stat  = mk('DIV', 'static', 2147483647);  // not floating — untouched
  const scr   = mk('SCRIPT', 'fixed', 2147483647);// scripts — never touched
  e.document.body.children.push(panel, lowz, stat, scr);

  // ELEMENT fullscreen (the mobile player): nothing is hidden — mobile is
  // the control group, its overlays are covered by the top layer anyway
  e.document.fullscreenElement = { tagName: 'DIV' };
  e.dispatch('fullscreenchange', e.document);
  assert(panel.style.visibility === '',
    'element fullscreen (mobile player): panel untouched');

  // DOCUMENT fullscreen (desktop YT fullscreens <html>): the ≥1e8 fixed
  // overlay hides; low-z, static and script elements stay visible
  e.document.fullscreenElement = e.document.documentElement;
  e.dispatch('fullscreenchange', e.document);
  assert(panel.style.visibility === 'hidden',
    'document fullscreen: high-z fixed panel hidden');
  assert(lowz.style.visibility === '',
    'low-z site chrome untouched');
  assert(stat.style.visibility === '',
    'static (non-floating) element untouched');
  assert(scr.style.visibility === '',
    'script tags never swept');

  // the forced sweep (Java's wedged-enter rescue / in-PiP death path)
  // is idempotent — no double-hide, no double-push
  e.window.__boltFsSweep(1);
  assert(panel.style.visibility === 'hidden',
    'forced sweep is idempotent while already hidden');

  // exit fullscreen: exactly what was hidden is restored
  e.document.fullscreenElement = null;
  e.dispatch('fullscreenchange', e.document);
  assert(panel.style.visibility === '',
    'fullscreen exit: panel restored');

  // the FORCE path works with NO fullscreen at all (the wedge: the enter
  // never settled, fullscreenchange never fired) and the explicit clear
  // restores
  e.window.__boltFsSweep(1);
  assert(panel.style.visibility === 'hidden',
    'forced sweep with no fullscreen (the wedge) hides');
  e.window.__boltFsSweep(0);
  assert(panel.style.visibility === '',
    'forced clear restores');

  // webkit-prefixed changes sync too (old WebView)
  e.document.fullscreenElement = e.document.body;
  e.dispatch('webkitfullscreenchange', e.document);
  assert(panel.style.visibility === 'hidden',
    'webkitfullscreenchange syncs (body fullscreen)');
  e.document.fullscreenElement = null;
  e.dispatch('webkitfullscreenchange', e.document);
  assert(panel.style.visibility === '',
    'webkit exit restores');

  tPip();
}

// v1.3.66 — the PiP play sentinel (pipFight) + the player-API play (ytApi)
// v1.3.68 — THE PAUSE GATE: a page-side pause of the ACTIVE element while
// armed NEVER EXECUTES (swallowed at HTMLMediaElement.prototype.pause) and
// the player's own API is nudged back to playing; the event-only
// (renderer-side) pause class still meets pipFight
function tPip() {
  console.log('== T21: v1.3.68 THE PAUSE GATE (page-side pause never executes) ==');
  {
    const e = makeEnv();
    const v = new e.FakeMedia('video');
    e.knownMedia.push(v);
    let sawPauseEvent = 0;
    e.document.addEventListener('pause', () => { sawPauseEvent++; }, true);
    let apiPlays = 0;
    v.closest = () => ({ playVideo() { apiPlays++; }, pauseVideo() {} });
    v.play();
    // the PiP window is up: PipVideo.pinJs armed the flag
    e.window.__boltpip = 1;
    const statesBefore = e.events.filter(x => x.name === 'state').length;
    v.pause();                              // the mobile player's reversal (g.fZ.pause)
    assert(!v.paused, 'the page-side pause NEVER executed (swallowed at the method)');
    assert(sawPauseEvent === 0, 'no pause event ever fired for the swallow');
    assert(apiPlays === 1, 'the swallow nudged the player API back to playing (the sanctioned play)');
    assert(e.events.filter(x => x.name === 'state').length === statesBefore,
      'the pause is not reported (nothing actually happened)');
    setTimeout(() => {
      const gates = e.events.filter(x => x.name === 'pipgate');
      assert(gates.length > 0 && gates[0].o.js === 1
        && typeof gates[0].o.st === 'string' && gates[0].o.st.length > 0,
        'a pipgate diag event carries the decoder (js=1 st=... — fs pip gate evidence)');
      assert(e.events.every(x => x.name !== 'pipfight'),
        'no pipfight needed: the pause never landed');
      // the renderer-side class (event-only pause, no method call) still
      // meets the FIGHT — the gate only owns the page-method class
      const e2 = makeEnv();
      const v2 = new e2.FakeMedia('video');
      e2.knownMedia.push(v2);
      v2.play();
      e2.window.__boltpip = 1;
      v2.paused = true;                     // the renderer paused it — no method call
      v2.fire('pause');
      setTimeout(() => {
        assert(!v2.paused, 'the fight re-played the renderer-side pause');
        assert(e2.events.some(x => x.name === 'pipfight'),
          'the event-only (renderer-side) pause still meets pipFight');
        pip2();
      }, 40);
    }, 40);
  }
  function pip2() {
    console.log('== T22: a user pause inside PiP is respected (no fight, no gate) ==');
    const e = makeEnv();
    const v = new e.FakeMedia('video');
    e.knownMedia.push(v);
    v.play();
    e.window.__boltpip = 1;
    e.window.__boltCmd('pause');           // the RemoteAction / notification pause
    setTimeout(() => {
      assert(v.paused, 'the user pause stands');
      assert(e.events.every(x => x.name !== 'pipfight'),
        'no pipfight event for a user-commanded pause');
      assert(e.events.every(x => x.name !== 'pipgate'),
        'no pipgate event for a user-commanded pause (the __userPaused hatch)');
      pip3();
    }, 40);
  }
  function pip3() {
    console.log('== T23: the gate stands down when the window is gone ==');
    const e = makeEnv();
    const v = new e.FakeMedia('video');
    e.knownMedia.push(v);
    v.play();
    e.window.__boltpip = 1;
    v.pause();                              // the reversal, swallowed while armed
    assert(!v.paused, 'the armed session swallowed the reversal');
    e.window.__boltpip = 0;                 // the PiP window closed (unpin)
    v.pause();                              // a page pause now passes through
    assert(v.paused, 'after the disarm a page pause passes through');
    setTimeout(() => {
      assert(e.events.every(x => x.name !== 'pipfight'),
        'no pipfight event outside PiP');
      pip4();
    }, 40);
  }
  function pip4() {
    console.log('== T24: v1.3.66 player-API play (ytApi) drives the player ==');
    const e = makeEnv();
    const v = new e.FakeMedia('video');
    e.knownMedia.push(v);
    v.play();
    v.pause();
    let played = 0, pausedApi = 0;
    const fakePlayer = {
      playVideo() { played++; },
      pauseVideo() { pausedApi++; },
    };
    v.closest = (sel) => (sel && sel.indexOf('html5-video-player') >= 0
      ? fakePlayer : null);
    e.window.__boltCmd('play');
    assert(v.paused === false, 'element play ran');
    assert(played === 1, 'the player-API playVideo fired once (state-machine play)');
    e.window.__boltCmd('pause');
    assert(pausedApi === 1, 'the player-API pauseVideo fired once');
    // a page with NO known player: closest -> null -> pure element commands
    const e2 = makeEnv();
    const v2 = new e2.FakeMedia('video');
    e2.knownMedia.push(v2);
    v2.play();
    v2.pause();
    e2.window.__boltCmd('play');
    assert(v2.paused === false, 'no player API present: the element play still works');
    t25();
  }
}

// v1.3.67 — THE PiP SURVIVAL KIT: the exit gate, the reaction mutes, the
// pause decoder, the longer fight
function t25() {
  console.log('== T25: v1.3.68 THE EXIT GATE (whole session, gesture escape) ==');
  {
    const e = makeEnv();
    // arm the session the way PipVideo.pinJs does
    e.window.__boltpip = 1;
    e.window.__boltpipAt = Date.now();
    e.document.exitFullscreen();          // the mobile player's automatic exit
    assert(e.getExitCalls() === 0,
      'the armed in-window exitFullscreen was swallowed');
    assert(e.events.some(x => x.name === 'pipexit' && x.o && x.o.n === 1),
      'the swallow reported a pipexit diag event (fs pip exitgate evidence)');
    e.document.exitFullscreen();          // the player retries
    assert(e.getExitCalls() === 0 && e.events.some(x => x.name === 'pipexit' && x.o && x.o.n === 2),
      'a second swallow counts n=2');
    // v1.3.68: the time window is GONE — the v1.3.67 field retries at
    // +5.7 s/+15.2 s stay swallowed for the whole armed session
    e.window.__boltpipAt = Date.now() - 20000;
    e.document.exitFullscreen();
    assert(e.getExitCalls() === 0,
      'a LATE retry (far past the old 2.5 s window) is still swallowed — the gate covers the whole session');
    // the GESTURE escape: a real page touch/click/keydown re-opens the gate
    e.window.__boltPipG = 1;
    e.document.exitFullscreen();
    assert(e.getExitCalls() === 1,
      'a real page gesture re-opened the gate (user exits still work on interactive surfaces)');
    // disarmed (window gone): unchanged
    e.window.__boltpip = 0;
    e.window.__boltPipG = 0;
    e.window.__boltpipAt = Date.now();
    e.document.exitFullscreen();
    assert(e.getExitCalls() === 2,
      'a disarmed session (window gone) exits normally');
    // never armed at all: normal behaviour
    const e2 = makeEnv();
    e2.document.exitFullscreen();
    assert(e2.getExitCalls() === 1, 'an unarmed page exits fullscreen normally');
    t26();
  }
}

function t26() {
  console.log('== T26: v1.3.67 THE REACTION MUTES (fullscreenchange / resize delivery) ==');
  {
    const e = makeEnv();
    let fs = 0, rs = 0, ori = 0;
    e.document.addEventListener('fullscreenchange', () => { fs++; });
    e.window.addEventListener('resize', () => { rs++; });
    e.window.addEventListener('orientationchange', () => { ori++; });
    // armed session: fullscreenchange muted
    e.window.__boltpip = 1;
    e.dispatch('fullscreenchange', e.document);
    assert(fs === 0, 'fullscreenchange muted while the PiP session is armed');
    e.dispatch('webkitfullscreenchange', e.document);
    assert(fs === 0, 'webkitfullscreenchange muted too');
    // resize still delivered while the custom view lives (the desktop
    // configuration — desktop must be byte-for-byte untouched)
    e.dispatch('resize', e.window);
    assert(rs === 1, 'resize still delivered while the custom view is alive');
    // after the in-PiP death (Java sets __boltpipx): resize muted too
    e.window.__boltpipx = 1;
    e.dispatch('resize', e.window);
    e.dispatch('orientationchange', e.window);
    assert(rs === 1 && ori === 0,
      'resize + orientationchange muted after the in-PiP death (post-exit relayout reactions)');
    // disarm: everything flows again (the resync path)
    e.window.__boltpip = 0; e.window.__boltpipx = 0;
    e.dispatch('fullscreenchange', e.document);
    e.dispatch('resize', e.window);
    assert(fs === 1 && rs === 2, 'deliveries resume after the disarm (resync path)');
    // an unarmed page: normal deliveries
    const e2 = makeEnv();
    let fs2 = 0;
    e2.document.addEventListener('fullscreenchange', () => { fs2++; });
    e2.dispatch('fullscreenchange', e2.document);
    assert(fs2 === 1, 'an unarmed page sees fullscreenchange normally');
    t27();
  }
}

function t27() {
  console.log('== T27: v1.3.68 THE DECODER RIDES THE GATE (pipgate payload) ==');
  {
    const e = makeEnv();
    const v = new e.FakeMedia('video');
    e.knownMedia.push(v);
    v.play();
    e.window.__boltpip = 1;
    e.window.__boltpipAt = Date.now();
    v.pause();                            // a PAGE-side pause: swallowed + recorded
    assert(typeof v.__boltPPS === 'string' && v.__boltPPS.length > 0,
      'the armed pause recorded a JS stack on the element');
    setTimeout(() => {
      const gates = e.events.filter(x => x.name === 'pipgate');
      assert(gates.length > 0, 'the gate reported (pipgate)');
      assert(gates[0].o.js === 1 && typeof gates[0].o.st === 'string' && gates[0].o.st.length > 0,
        'the pipgate event carries the decoded stack (js=1 st=...)');
      assert(!v.paused, 'the element was never paused');
      // js=0: a pause with NO JS caller (the renderer-side class) — the
      // FIGHT reports that one (the gate only sees method calls)
      const e2 = makeEnv();
      const v2 = new e2.FakeMedia('video');
      e2.knownMedia.push(v2);
      v2.play();
      e2.window.__boltpip = 1;
      v2.paused = true;                   // the renderer paused it — no method call
      v2.fire('pause');
      setTimeout(() => {
        const f2 = e2.events.filter(x => x.name === 'pipfight');
        assert(f2.length > 0, 'the fight fired for the event-only pause');
        assert(f2[0].o.js === 0,
          'no recorded stack -> js=0 (the renderer-side pause class)');
        t28();
      }, 40);
    }, 40);
  }
}

function t28() {
  console.log('== T28: v1.3.68 the longer fight (8 tries, the new bound — renderer-class pauses) ==');
  {
    const e = makeEnv();
    const v = new e.FakeMedia('video');
    e.knownMedia.push(v);
    v.play();
    e.window.__boltpip = 1;
    e.window.__boltpipAt = Date.now();
    // an unreversable RENDERER-side pause (event-only — the page-method
    // class is the pause gate's now): the page re-pauses instantly after
    // every play (play fires the event but never un-pauses)
    v.play = () => { v.fire('play'); return Promise.resolve(); };
    v.paused = true;
    v.fire('pause');
    setTimeout(() => {
      const fights = e.events.filter(x => x.name === 'pipfight');
      assert(fights.length >= 8, 'eight bounded tries fired (was 5 in v1.3.66), got ' + fights.length);
      const firstBurst = fights.slice(0, 8).map(f => f.o.n);
      assert(JSON.stringify(firstBurst) === JSON.stringify([1, 2, 3, 4, 5, 6, 7, 8]),
        'the first burst counts n=1..8 in order, got ' + JSON.stringify(firstBurst));
      assert(v.paused, 'the sentinel still respects a pause that outlasts the bound');
      t29();
    }, 150);
  }
}

// v1.3.68 — THE PAUSE GATE's hatches: every escape is load-bearing
function t29() {
  console.log('== T29: v1.3.68 THE PAUSE GATE hatches (user/ended/other-element/gesture/rate) ==');
  {
    // (a) the user's own pause passes (__userPaused)
    const e = makeEnv();
    const v = new e.FakeMedia('video');
    e.knownMedia.push(v);
    v.play();
    e.window.__boltpip = 1;
    v.__userPaused = true;
    v.pause();
    assert(v.paused, 'the user pause passes through (__userPaused)');
    // (b) an ENDED element's pause passes (natural end / queue advance)
    const e2 = makeEnv();
    const v2 = new e2.FakeMedia('video');
    e2.knownMedia.push(v2);
    v2.play();
    e2.window.__boltpip = 1;
    v2.ended = true;
    v2.pause();
    assert(v2.paused, 'an ENDED element pause passes through');
    // (c) a NON-active element's pause passes (ad/element switches)
    const e3 = makeEnv();
    const a = new e3.FakeMedia('video');
    const b = new e3.FakeMedia('video');
    e3.knownMedia.push(a); e3.knownMedia.push(b);
    a.play();
    e3.window.__boltpip = 1;
    b.pause();
    assert(b.paused, "a pause on a NON-active element is not ours to gate");
    assert(!a.paused, 'the active element untouched');
    // (d) a recent REAL page gesture disarms the swallow (12+ expanded PiP)
    const e4 = makeEnv();
    const v4 = new e4.FakeMedia('video');
    e4.knownMedia.push(v4);
    v4.play();
    e4.window.__boltpip = 1;
    e4.dispatch('click', e4.document);   // onGesture: lastGesture=now
    v4.pause();
    assert(v4.paused, 'a page pause right after a REAL page gesture passes (interactive surfaces keep their buttons)');
    // (e) the rate limiter, v1.3.69 calibration: the player's anti-PiP
    // poll swallows at ~4 Hz and must NEVER trip (v1.3.68's 12-in-3s was
    // that exact cadence — the limiter manufactured the field stutter);
    // only a TRUE burst (24 inside 2 s) degrades to pass-through,
    // self-healing after the 3 s cooldown (T33 proves the heal)
    const e5 = makeEnv();
    const v5 = new e5.FakeMedia('video');
    e5.knownMedia.push(v5);
    v5.play();
    e5.window.__boltpip = 1;
    let gated = 0;
    for (let i = 0; i < 23; i++) {
      v5.pause();
      if (!v5.paused) gated++;           // swallowed
      else break;                          // cooldown tripped — stop counting
    }
    assert(gated === 23, 'a steady swallow cadence never trips: 23 rapid swallows all hold (v1.3.68 tripped at 12), got ' + gated);
    assert(!v5.paused, 'the element never paused through the whole burst');
    v5.pause();                            // the 24th inside 2 s = the burst
    assert(v5.paused, 'the 24th rapid swallow trips the limiter — pass-through (pipFight owns it)');
    t30();
  }
}

// v1.3.68 — THE BLUR MUTE: page-level blur not delivered while armed
function t30() {
  console.log('== T30: v1.3.68 THE BLUR MUTE (page-level blur while armed) ==');
  {
    const e = makeEnv();
    let winBlur = 0, elemBlur = 0;
    e.window.addEventListener('blur', () => { winBlur++; });
    e.document.addEventListener('blur', (ev) => { if (ev.target && ev.target.tagName === 'INPUT') elemBlur++; }, true);
    e.window.__boltpip = 1;                 // the PiP session is armed
    e.dispatch('blur', e.window);
    assert(winBlur === 0, 'page window blur muted while the PiP session is armed');
    e.dispatch('blur', e.document);
    assert(winBlur === 0, 'page document blur muted too');
    const input = { tagName: 'INPUT' };
    e.dispatch('blur', input);
    assertEq(elemBlur, 1, 'element-level blur NOT muted (form fields keep working)');
    e.window.__boltpip = 0;                 // expand-back: the disarm
    e.dispatch('blur', e.window);
    assertEq(winBlur, 1, 'window blur passes through when the session is disarmed');
    t31();
  }
}

// v1.3.69 — THE OUTER-SIZE SPOOF: the premise kill (gY's window-area
// PiP detection reads spoofed screen-sized outers while armed)
function t31() {
  console.log('== T31: v1.3.69 THE OUTER-SIZE SPOOF (armed lie, disarmed live) ==');
  {
    const e = makeEnv({ screen: { width: 720, height: 1600 }, outer: [280, 157] });
    assertEq(e.window.outerWidth, 280, 'disarmed before any PiP: the pre-PiP outer is reported (install delegates)');
    assertEq(e.window.outerHeight, 157, 'disarmed outerHeight likewise');
    e.window.__boltpip = 1;
    assertEq(e.window.outerWidth, 720, 'armed: outerWidth reports screen.width — the player\'s b/a < .33 test reads 1.0');
    assertEq(e.window.outerHeight, 1600, 'armed: outerHeight reports screen.height');
    e.window.__boltpip = 0;
    assertEq(e.window.outerWidth, 280, 'expand-back (disarm): live sizes again');
    e.window.__boltpip = 1;
    assertEq(e.window.outerWidth, 720, 're-armed in the same page load (enter/exit/enter cycles reuse the install)');
    e.window.__boltpip = 0;
  }
  {
    // no window.screen: the spoof stays inert (the gate + fight still hold)
    const e2 = makeEnv({ outer: [280, 157] });
    e2.window.__boltpip = 1;
    assertEq(e2.window.outerWidth, 280, 'no screen object: outer untouched (inert, no crash)');
    e2.window.__boltpip = 0;
    t32();
  }
}

// v1.3.69 — THE MARKED RESYNC: a __boltRS resize passes the armed mutes
function t32() {
  console.log('== T32: v1.3.69 the marked resync resize (pipMuted exemption) ==');
  {
    const e = makeEnv();
    let saw = 0, marked = 0;
    e.window.addEventListener('resize', (ev) => { saw++; if (ev && ev.__boltRS) marked++; });
    e.window.__boltpip = 1;
    e.window.__boltpipx = 1;              // armed + post custom-view death
    e.dispatch('resize', e.window);
    assertEq(saw, 0, 'a plain resize stays muted while armed + post-death');
    e.dispatch('resize', e.window, { __boltRS: 1 });
    assertEq(saw, 1, 'the MARKED resync resize passes the armed mute (gY re-evaluates)');
    assertEq(marked, 1, 'and it is the marked one that got through');
    e.dispatch('orientationchange', e.window, { __boltRS: 1 });
    assertEq(saw, 1, 'the exemption is type-scoped (orientationchange has no listener here)');
    e.window.__boltpipx = 0;
    e.dispatch('resize', e.window);
    assertEq(saw, 2, 'resize passes again once past the death phase (v1.3.67 behavior intact)');
    t33();
  }
}

// v1.3.69 — the cooldown heal: the tripped limiter re-arms 3 s later
function t33() {
  console.log('== T33: v1.3.69 the limiter cooldown heals (self-healing pass-through) ==');
  {
    const e = makeEnv();
    const v = new e.FakeMedia('video');
    e.knownMedia.push(v);
    v.play();
    e.window.__boltpip = 1;
    for (let i = 0; i < 24; i++) v.pause();   // the burst: 24-in-2s trips
    assert(v.paused, 'the burst tripped the limiter (pause landed)');
    setTimeout(() => {
      // 3 s cooldown has expired; the fight re-played the element along
      // the way (nothing re-pauses it here); a fresh pause is swallowed
      // again — the gate re-armed, the degradation is bounded
      v.pause();
      assert(!v.paused, '3 s later the gate re-armed (the cooldown heals, the war is bounded)');
      t34();
    }, 3300);
  }
}

// v1.3.69 — THE ENEMY, SIMULATED: the player's own gY/bY logic, de-
// minified straight from player build a5c2aafe (gY = base.js:5890, the
// window RESIZE handler; bY = base.js:5850, the onVideoProgress tick),
// run against the hook through the SAME registration channel a real page
// uses (the hooked addEventListener). Proves the whole chain: the shrunk
// outer latches pictureInPicture, the marked resync un-latches it against
// the spoofed size, the progress-tick poll dies with the premise, and the
// gate stays armed as the safety net.
function t34() {
  console.log('== T34: v1.3.69 THE ENEMY, SIMULATED (gY latch -> marked resync -> poll dead) ==');
  {
    const e = makeEnv({ screen: { width: 720, height: 1600 }, outer: [720, 1520], outerLive: true });
    const v = new e.FakeMedia('video');
    e.knownMedia.push(v);
    v.play();
    // the player, as read out of base.js (names per the stack: y2.bY, y2.gY)
    const player = {
      pip: false, yb: true, jn: true, backgroundable: false,
      visibility: { Hu(a) { player.pip = a; } },
      pauseVideo() { v.pause(); },
      gY() {
        const a = e.window.screen.width * e.window.screen.height;
        const b = e.window.outerHeight * e.window.outerWidth;
        if (b / a < .33 && player.yb && !player.backgroundable) {
          player.visibility.Hu(true); v.pause();
        } else player.visibility.Hu(false);
      },
      bY() { if (player.jn && player.pip && player.yb && !player.backgroundable) player.pauseVideo(); }
    };
    e.window.addEventListener('resize', function () { player.gY(); });
    // Android PiP: the window collapses; the resize reaches gY (pre-death)
    e.window.__setOuter(280, 157);
    e.dispatch('resize', e.window);
    assert(player.pip === true, 'the enemy latches: outer area 15% of screen -> pictureInPicture=true (gY)');
    assert(v.paused, 'and the direct element pause landed (pre-arm, ungated)');
    // the custom-view death, then the PIN arm (the spoof goes live)
    v.play();
    e.window.__boltpipx = 1;
    e.window.__boltpip = 1;
    e.dispatch('resize', e.window);
    assert(player.pip === true, 'a real resize now is MUTED — without the mark the premise persists (why the resync is load-bearing)');
    // PipVideo.pinJs's one marked resync
    e.dispatch('resize', e.window, { __boltRS: 1 });
    assert(player.pip === false, 'the MARKED resync re-runs gY against the SPOOFED size -> pictureInPicture=false');
    assertEq(e.window.outerWidth, 720, 'while armed outerWidth reports the screen (ratio 1.0)');
    assertEq(e.window.outerHeight, 1600, 'while armed outerHeight reports the screen');
    // the progress-tick poll: dead with the premise; the gate still armed
    for (let i = 0; i < 30; i++) { player.bY(); player.gY(); }
    assert(!v.paused, '30 progress ticks + 30 re-evaluations: NO pause lands (the poll is dead)');
    player.pauseVideo();
    assert(!v.paused, 'even a stray pauseVideo is swallowed (the gate stays as the safety net)');
    // expand-back: disarm -> live sizes again
    e.window.__boltpip = 0;
    e.window.__boltpipx = 0;
    e.window.__setOuter(720, 1520);
    assertEq(e.window.outerWidth, 720, 'disarmed: the live outer is reported again (orig accessor delegation)');
    v1379();
  }
}

// ================= v1.3.79: USERNAME-FIRST LOGIN + FIELD RECT =================
function v1379() {
  if (v1379ran) return;   // the harness re-invokes chains; run once
  v1379ran = true;
  console.log('== T35: username-first login detection ==');
  {
    const e = makeEnv();
    const form = { tagName: 'FORM', querySelector: (sel) => sel === 'input[type=password]' ? [{}] : [] };
    const userInput = { tagName: 'INPUT', type: 'text', form,
                        getAttribute: () => 'username', closest: () => form };
    const otherInput = { tagName: 'INPUT', type: 'text', getAttribute: () => '', closest: () => null, form: null };
    e.dispatch('focusin', userInput);
    assert(e.pwCalls.length === 1 && e.pwCalls[0] === true,
      'focusing a USERNAME field arms the pw chip (username-first flows)');
    e.dispatch('focusin', otherInput);
    assert(e.pwCalls.length === 2 && e.pwCalls[1] === false,
      'a plain text field disarms it');
    const pwInput = { tagName: 'INPUT', type: 'password',
                      getBoundingClientRect: () => ({ top: 200, bottom: 244 }) };
    e.dispatch('focusin', pwInput);
    assert(e.pwCalls.length === 3 && e.pwCalls[2] === true,
      'a password field still arms it (the classic path)');
    const r = e.window.__boltFieldRect();
    assert(r && r.t === 200 && r.b === 244,
      '__boltFieldRect reports the focused field viewport coords');
  }

  console.log('== T35b: v1.4.4 the remembered username + the click re-arm ==');
  {
    const e = makeEnv({ loc: { href: 'https://s.example.com/v', host: 's.example.com' } });
    // screen 1: the username field (focused -> remembered; autocomplete
    // names it, exactly like the real username-first screens do)
    const userInput = { tagName: 'INPUT', type: 'email', value: 'second@acct.com',
                        getAttribute: () => 'username', closest: () => null, form: null };
    e.dispatch('focusin', userInput);
    assert(e.pwCalls.length === 1 && e.pwCalls[0] === true,
      'focusing the username field arms the chip');
    // screen 2: a password-ONLY form (no username input in it) submits
    const pwOnly = { tagName: 'INPUT', type: 'password', value: 'pw2' };
    const form2 = { tagName: 'FORM',
                    querySelector: (sel) => sel === 'input[type=password]' ? pwOnly : null,
                    querySelectorAll: (sel) => sel === 'input' ? [pwOnly] : [] };
    e.dispatch('submit', form2);
    assert(e.pwSubmits.length === 1
        && e.pwSubmits[0][1] === 'second@acct.com' && e.pwSubmits[0][2] === 'pw2',
      'a password-only submit inherits the REMEMBERED username (two-step sign-ins)');
    // the same (user,pass) never reports twice
    e.dispatch('submit', form2);
    assert(e.pwSubmits.length === 1,
      'the once-per-(user,pass) reporter holds');
    // a form WITH its own user field wins over the memory
    const ownUser = { tagName: 'INPUT', type: 'text', value: 'inline@acct.com',
                      getAttribute: () => '' };
    const pwB = { tagName: 'INPUT', type: 'password', value: 'pw3' };
    const form3 = { tagName: 'FORM',
                    querySelector: (sel) => sel === 'input[type=password]' ? pwB : null,
                    querySelectorAll: (sel) => sel === 'input' ? [ownUser, pwB] : [] };
    e.dispatch('submit', form3);
    assert(e.pwSubmits.length === 2 && e.pwSubmits[1][1] === 'inline@acct.com',
      'the form\'s own username field takes precedence over the memory');
    // the click re-arm: a click on a login field re-asks for the chip
    const calls0 = e.pwCalls.length;
    e.dispatch('click', pwOnly);
    assert(e.pwCalls.length === calls0 + 1 && e.pwCalls[e.pwCalls.length - 1] === true,
      'a CLICK on a login field re-arms the fill chip (the 30 s retire corpse)');
    // a click on a non-login input does not
    e.dispatch('click', { tagName: 'INPUT', type: 'text', getAttribute: () => '', closest: () => null, form: null });
    assert(e.pwCalls.length === calls0 + 1,
      'a click on a plain field leaves the chip alone');
  }

  console.log('== T36: the skipper rate authority ==');
  {
    const e = makeEnv();
    const v = new e.FakeMedia('video'); e.knownMedia.push(v);
    v.play();
    e.window.__boltSetMedia(JSON.stringify({ vol: 100, vocal: false, speed: 1, bgPlay: true, booster: true, skip: false, noCookies: false }));
    e.window.__boltCmd('speed', 1);
    assertEq(v.playbackRate, 1, 'plain playback: base speed holds');
    v.__boltSk = 1;
    e.window.__boltCmd('speed', 1);
    assertEq(v.playbackRate, 3, 'skip engaged, kf unset: rate = speed x default 3 (v1.3.87)');
    e.window.__boltSetMedia(JSON.stringify({ vol: 100, vocal: false, speed: 1, bgPlay: true, booster: true, skip: true, kf: 8, noCookies: false }));
    e.window.__boltCmd('speed', 2);
    assertEq(v.playbackRate, 16, 'kf=8: rate = speed x 8 (the 1x-8x knob)');
    e.window.__boltSetMedia(JSON.stringify({ vol: 100, vocal: false, speed: 1, bgPlay: true, booster: true, skip: true, kf: 1, noCookies: false }));
    e.window.__boltCmd('speed', 2);
    assertEq(v.playbackRate, 2, 'kf=1: no speedup at all (the knob floor)');
    e.window.__boltSetMedia(JSON.stringify({ vol: 100, vocal: false, speed: 1, bgPlay: true, booster: true, skip: true, kf: 3, noCookies: false }));
    e.window.__boltCmd('speed', 1.5);
    assertEq(v.playbackRate, 4.5, '1.5x base skips at 4.5x (kf=3, the default)');
    v.__boltSk = 0;
    e.window.__boltCmd('speed', 2);
    assertEq(v.playbackRate, 2, 'skip released: the user speed alone');
  }

  console.log('== T37: silence engage/disengage through a mock audio graph ==');
  {
    let level = 0.0;
    const mkNode = () => ({ connect: () => {}, gain: { value: 0 }, frequency: { value: 0 }, Q: { value: 0 } });
    class FakeAC {
      constructor() { this.destination = mkNode(); this.state = 'running'; }
      resume() { return Promise.resolve(); }
      createMediaElementSource() { return mkNode(); }
      createBiquadFilter() { return mkNode(); }
      createGain() { return mkNode(); }
      createAnalyser() {
        return { fftSize: 1024, getFloatTimeDomainData(buf) { for (let i = 0; i < buf.length; i++) buf[i] = level; } };
      }
    }
    const e = makeEnv();
    e.window.AudioContext = FakeAC;
    const v = new e.FakeMedia('video'); e.knownMedia.push(v);
    v.src = 'blob:https://example.com/media';   // sameOrigin gate: blob passes
    v.play();                                    // paused=false + fires 'play'
    // chains are created lazily on media events with booster/skip live
    e.window.__boltSetMedia(JSON.stringify({ vol: 100, vocal: false, speed: 1, bgPlay: true, booster: true, skip: true, kf: 3, noCookies: false }));
    v.play();                                    // re-fire: the play path builds the chain
    setTimeout(() => {
      assert(v.__boltSk === 1, 'a quiet stretch engages the 3x relative skip (kf default)');
      assertEq(v.playbackRate, 3, 'engaged: the element rides at 3x');
      level = 0.2;   // audio returns
      setTimeout(() => {
        assert(!v.__boltSk, 'audio returns: skip releases');
        assertEq(v.playbackRate, 1, 'released: back to base speed');
        t38();
      }, 700);
    }, 1500);
  }

  // v1.3.87 — the hysteresis knobs: skb rewinds the fast-forwarded onset,
  // skm undoes skips that proved shorter than the ignore threshold (and
  // bans the region so it cannot loop).
  function t38() {
  console.log('== T38: the onset rewind and the short-silence undo ==');
  {
    let level = 0.0;
    const mkNode = () => ({ connect: () => {}, gain: { value: 0 }, frequency: { value: 0 }, Q: { value: 0 } });
    class FakeAC2 {
      constructor() { this.destination = mkNode(); this.state = 'running'; }
      resume() { return Promise.resolve(); }
      createMediaElementSource() { return mkNode(); }
      createBiquadFilter() { return mkNode(); }
      createGain() { return mkNode(); }
      createAnalyser() {
        return { fftSize: 1024, getFloatTimeDomainData(buf) { for (let i = 0; i < buf.length; i++) buf[i] = level; } };
      }
    }
    const e = makeEnv();
    e.window.AudioContext = FakeAC2;
    const v = new e.FakeMedia('video'); e.knownMedia.push(v);
    v.src = 'blob:https://example.com/media';
    v.currentTime = 10;                          // mid-video: rewind math is live
    v.play();
    e.window.__boltSetMedia(JSON.stringify({ vol: 100, vocal: false, speed: 1, bgPlay: true, booster: true, skip: true, kf: 3, ska: 200, skb: 150, skm: 400, noCookies: false }));
    v.play();
    setTimeout(() => {
      assert(v.__boltSk === 1, 'quiet engages (skb case)');
      assertEq(v.playbackRate, 3, 'riding at 3x');
      v.currentTime = 20;                        // 10 s of silence >= skm
      level = 0.2;                               // speech returns
      setTimeout(() => {
        assert(!v.__boltSk, 'released');
        assertEq(v.playbackRate, 1, 'back to base speed');
        assert(Math.abs(v.currentTime - 19.85) < 0.01,
          `skb=150: the onset is rewound 150 ms (got ${v.currentTime})`);
        // ---- the short-silence undo: quiet again, but only briefly ----
        level = 0.0;
        setTimeout(() => {
          assert(v.__boltSk === 1, 're-engaged for the second gap');
          const skT = v.currentTime;              // where this skip began
          v.currentTime = skT + 0.25;             // 250 ms of content-silence < skm=400
          level = 0.2;
          setTimeout(() => {
            assert(!v.__boltSk, 'released again');
            assert(Math.abs(v.currentTime - skT) < 0.01,
              `skm=400: a 250 ms gap undoes the whole skip (got ${v.currentTime}, began ${skT})`);
            assert(typeof v.__boltSkBan === 'number' && v.__boltSkBan > skT,
              'the short region is banned until playback passes its onset');
            finish();
          }, 400);
        }, 1400);
      }, 400);
    }, 1400);
  }
  }
}

function finish() {
  console.log(failures === 0 ? '\nALL TESTS PASSED' : `\n${failures} FAILURES`);
  process.exit(failures === 0 ? 0 : 1);
}

setTimeout(() => { console.log('TIMEOUT'); process.exit(1); }, 25000);   // v1.3.69: T33 waits out the real 3 s cooldown; v1.3.87: T38 adds the rewind/undo chains (~6 s)
