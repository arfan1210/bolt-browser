/* v1.3.65 — Mock-DOM behavioral test for the imported PiP takeover engine
 * (util/PipVideo.kt). Loads the payloads extracted from the Kotlin source
 * by scripts/test_pipvideo.py and proves:
 *   T1  the PLAYER is pinned (not the bare video) when a container's box
 *       tracks the video's, and the code says so (1:aspect);
 *   T2  EVERY ancestor is neutralized (__boltpva) — the containing-block
 *       fix that decodes the v1.3.64 "top half of the video + page UI"
 *       field report — and a FIXED ancestor is demoted to absolute too;
 *   T3  html/body overflow layer + the style element + the two 1px edge
 *       strips all exist, and the video keeps PLAYING through the pin;
 *   T4  a bare video with no tracking ancestor is pinned itself (2:aspect);
 *   T5  the beat is FLICKER-FREE: a second pin while the live pin still
 *       holds the best video is a no-op (3:aspect, zero DOM writes);
 *   T6  player DOM re-creation (the best video no longer inside the pinned
 *       element) re-runs the full cycle onto the new player;
 *   T7  the two-tier PiP selector prefers a PLAYING video over a larger
 *       paused one; the strict (wedged-enter) selector refuses the paused
 *       page entirely (0:0);
 *   T8  unpin restores EVERYTHING (classes, html class, strips, style
 *       element) and also sweeps the legacy v1.3.64 __boltfsf pin;
 *   T9  a pinnable-video-less page answers 0:0 (the 'fs pip pin=0' class).
 *   T10 v1.3.66 — the KNOWN-PLAYER fast path: a mobile-YouTube-like tree
 *       whose container box does NOT track the video's (the walk's mobile
 *       failure, 'fs pip pin=1 player=0') still pins the PLAYER when it
 *       carries .html5-video-player / #movie_player / ytm/ytd-player;
 *   T11 v1.3.66 — the pin ARMS the in-page PiP sentinel flag
 *       (window.__boltpip=1) and the unpin DISARMS it;
 *   T12 v1.3.66 — a non-ancestor decoy carrying the player class cannot
 *       capture the pin (closest walks ANCESTORS only).
 *   T14 v1.3.69 — the first arm dispatches EXACTLY ONE resize marked
 *       __boltRS (the resync that makes the player's gY re-read the
 *       spoofed window size and un-latch its pictureInPicture premise);
 *       the beat and the re-pin cycle never re-deliver it, and the disarm
 *       resync stays UNmarked (v1.3.67 semantics).
 * Same pattern as scripts/test_media_hook.js for MediaHook.core(). */
'use strict';
const fs = require('fs');

let failures = 0;
function assert(cond, msg) {
  if (cond) { console.log('  PASS ' + msg); }
  else { failures++; console.log('  FAIL ' + msg); }
}

const PIN = fs.readFileSync('/tmp/pip_video_pin.js', 'utf8');
const PIN_STRICT = fs.readFileSync('/tmp/pip_video_pin_strict.js', 'utf8');
const UNPIN = fs.readFileSync('/tmp/pip_video_unpin.js', 'utf8');

// ------------------------------------------------------------ the mock DOM
function makeClassList(el) {
  return {
    add(...cs) { for (const c of cs) { if (!el.__cls.includes(c)) el.__cls.push(c); } },
    remove(...cs) { el.__cls = el.__cls.filter(x => !cs.includes(x)); },
    contains(c) { return el.__cls.includes(c); },
  };
}

function makeEl(tag, opts) {
  opts = opts || {};
  const el = {
    tagName: (tag || 'div').toUpperCase(),
    id: opts.id || '',
    __cls: opts.cls ? opts.cls.slice() : [],
    children: [],
    parentNode: null,
    offsetWidth: opts.offsetWidth || 0,
    offsetHeight: opts.offsetHeight || 0,
    videoWidth: opts.videoWidth || 0,
    videoHeight: opts.videoHeight || 0,
    paused: !!opts.paused,
    ended: !!opts.ended,
    style: {},
    textContent: '',
    __removed: false,
    __computed: { position: opts.position || 'static' },
    getBoundingClientRect() { return {}; },
    appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
    remove() {
      if (this.parentNode) {
        this.parentNode.children = this.parentNode.children.filter(x => x !== this);
        this.parentNode = null;
      }
      this.__removed = true;
    },
    contains(n) {
      let p = n;
      while (p) { if (p === this) return true; p = p.parentNode; }
      return false;
    },
    // v1.3.66 — the mock closest: comma-separated .class / #id / tag
    // selectors, walking ancestors only (the real Element.closest)
    closest(sel) {
      const parts = String(sel).split(',').map(s => s.trim()).filter(Boolean);
      let p = this;
      while (p) {
        for (const s of parts) {
          if (s.startsWith('.') && p.__cls.includes(s.slice(1))) return p;
          if (s.startsWith('#') && p.id === s.slice(1)) return p;
          if (!s.startsWith('.') && !s.startsWith('#')
              && p.tagName === s.toUpperCase()) return p;
        }
        p = p.parentNode;
      }
      return null;
    },
  };
  Object.defineProperty(el, 'classList', { get: () => makeClassList(el) });
  return el;
}

function makeDoc(tree) {
  const doc = {
    __domWrites: 0,
    __els: [],
    createElement(tag) {
      doc.__domWrites++;
      return makeEl(tag);
    },
    getElementById(id) {
      return doc.__els.filter(e => e.id === id && !e.__removed)[0] || null;
    },
    querySelectorAll(sel) {
      if (sel === 'video') {
        const out = [];
        (function walk(n) {
          if (!n) return;
          if (n.tagName === 'VIDEO') out.push(n);
          (n.children || []).forEach(walk);
        })(doc.documentElement);
        return out;
      }
      // class-based selectors used by the engine: '.xxx' (optionally
      // prefixed with an element name, e.g. 'video.__boltfsf')
      const m = /^([a-zA-Z]*)\.([A-Za-z0-9_]+)$/.exec(sel);
      if (m) {
        const out = [];
        (function walk(n) {
          if (!n) return;
          const tagOk = !m[1] || n.tagName === m[1].toUpperCase();
          if (tagOk && n.__cls.includes(m[2])) out.push(n);
          (n.children || []).forEach(walk);
        })(doc.documentElement);
        return out;
      }
      return [];
    },
    querySelector(sel) { return doc.querySelectorAll(sel)[0] || null; },
    getComputedStyle(el) { return el.__computed; },
    // v1.3.67 — recording dispatchEvent (unpinJs's synthetic resync events;
    // a no-op for every earlier payload — nothing else ever dispatches)
    dispatchEvent(ev) {
      (doc.__dispatched = doc.__dispatched || []).push(ev && ev.type);
      return true;
    },
  };
  doc.documentElement = makeEl('html');
  doc.body = makeEl('body');
  doc.head = makeEl('head');
  doc.documentElement.appendChild(doc.head);
  doc.documentElement.appendChild(doc.body);
  // attach the supplied tree under body
  for (const root of (tree || [])) doc.body.appendChild(root);
  // every appendChild through documentElement/body counts as a DOM write
  const origAppend = doc.documentElement.appendChild.bind(doc.documentElement);
  doc.documentElement.appendChild = (c) => { doc.__domWrites++; return origAppend(c); };
  // register every node so getElementById sees ids assigned later
  (function reg(n) {
    if (!n) return;
    doc.__els.push(n);
    (n.children || []).forEach(reg);
  })(doc.documentElement);
  const origCreate = doc.createElement.bind(doc);
  doc.createElement = (t) => { const e = origCreate(t); doc.__els.push(e); return e; };
  return doc;
}

function run(js, doc, win) {
  const fn = new Function('document', 'window', 'getComputedStyle',
    'return ' + js);
  return fn(doc, win || {}, (el) => (el && el.__computed) || { position: 'static' });
}

// ------------------------------------------------------------ T1-T3: the
// desktop-YouTube-like tree: a transformed ancestor between body and the
// player (the v1.3.64 killer), a fixed-position ancestor too
console.log('T1-T3 player takeover on a transformed-ancestor tree:');
{
  const player = makeEl('div', { offsetWidth: 1280, offsetHeight: 720 });
  const video = makeEl('video', {
    offsetWidth: 1280, offsetHeight: 720,
    videoWidth: 1920, videoHeight: 1080,
  });
  player.appendChild(video);
  const transformed = makeEl('div', { offsetWidth: 1400, offsetHeight: 900, position: 'static' });
  transformed.appendChild(player);
  const fixedAnc = makeEl('div', { offsetWidth: 1400, offsetHeight: 900, position: 'fixed' });
  fixedAnc.appendChild(transformed);
  const doc = makeDoc([fixedAnc]);

  const r = run(PIN, doc);
  assert(r === '1:' + (1920 / 1080), 'answer is 1:<aspect> (player pinned), got ' + r);
  assert(player.__cls.includes('__boltpvp'), 'the PLAYER wears the pin class');
  assert(!video.__cls.includes('__boltpvp'), 'the bare video is not the pin target');
  assert(transformed.__cls.includes('__boltpva'), 'the transformed ancestor is neutralized');
  assert(fixedAnc.__cls.includes('__boltpva') && fixedAnc.__cls.includes('__boltpvaf'),
      'the fixed ancestor is neutralized AND demoted to absolute');
  assert(doc.documentElement.__cls.includes('__boltpvh'), 'html carries the overflow layer');
  const style = doc.getElementById('__boltpv');
  assert(!!style, 'the takeover style element exists');
  const css = style.textContent;
  for (const frag of ['overflow:hidden', 'transform:none', 'contain:none',
      'filter:none', 'z-index:auto', 'position:fixed', '100vw', '100vh',
      'object-fit:contain', 'z-index:2147483647', 'background-color:#000']) {
    assert(css.includes(frag), 'CSS layer carries ' + frag);
  }
  assert(css.includes('.__boltpvp video') && css.includes('width:100%'),
      'videos under the pinned player are forced to fill it');
  assert(!!doc.getElementById('__boltpvl') && !!doc.getElementById('__boltpvr'),
      'both 1px edge strips exist');
  assert(!video.paused, 'the video keeps playing through the pin');
}

// ------------------------------------------------------------ T4: bare video
console.log('T4 bare video (no tracking ancestor):');
{
  const video = makeEl('video', {
    offsetWidth: 600, offsetHeight: 340,
    videoWidth: 1280, videoHeight: 720,
  });
  const unrelated = makeEl('div', { offsetWidth: 4000, offsetHeight: 4000 });
  const doc = makeDoc([unrelated, video]);
  const r = run(PIN, doc);
  assert(r.startsWith('2:'), 'answer is 2:<aspect> (bare video pinned), got ' + r);
  assert(video.__cls.includes('__boltpvp'), 'the bare video wears the pin class');
}

// ------------------------------------------------------------ T5: flicker-
// free beat
console.log('T5 flicker-free beat:');
{
  const player = makeEl('div', { offsetWidth: 1280, offsetHeight: 720 });
  const video = makeEl('video', {
    offsetWidth: 1280, offsetHeight: 720,
    videoWidth: 1920, videoHeight: 1080,
  });
  player.appendChild(video);
  const doc = makeDoc([player]);
  const win = {};
  const r1 = run(PIN, doc, win);
  assert(r1.startsWith('1:'), 'first beat pins (1:), got ' + r1);
  const before = doc.__domWrites;
  const r2 = run(PIN, doc, win);
  assert(r2.startsWith('3:'), 'second beat is a no-op (3:), got ' + r2);
  assert(doc.__domWrites === before,
      'the no-op beat performs ZERO DOM writes (no relayout flicker)');
  assert(player.__cls.includes('__boltpvp'), 'the pin survives the no-op beat');
  assert(win.__boltpvpEl === player, 'the pin is tracked for detached cleanup');
}

// ------------------------------------------------------------ T6: player DOM
// re-creation (watchdog must re-pin onto the new player)
console.log('T6 player re-creation re-pins:');
{
  const player1 = makeEl('div', { offsetWidth: 1280, offsetHeight: 720 });
  const video1 = makeEl('video', {
    offsetWidth: 1280, offsetHeight: 720,
    videoWidth: 1920, videoHeight: 1080,
  });
  player1.appendChild(video1);
  const doc = makeDoc([player1]);
  const win = {};
  run(PIN, doc, win);
  assert(player1.__cls.includes('__boltpvp'), 'old player pinned first');
  // the site tears the old player down and builds a new one
  player1.remove();
  const player2 = makeEl('div', { offsetWidth: 1280, offsetHeight: 720 });
  const video2 = makeEl('video', {
    offsetWidth: 1280, offsetHeight: 720,
    videoWidth: 1920, videoHeight: 1080,
  });
  player2.appendChild(video2);
  doc.body.appendChild(player2);
  doc.__els.push(player2, video2);
  const r = run(PIN, doc, win);
  assert(r.startsWith('1:') || r.startsWith('2:'), 'the new video is pinned, got ' + r);
  assert(!player1.__cls.includes('__boltpvp'), 'the dead player is unpinned');
  const pinned = doc.querySelector('.__boltpvp');
  assert(pinned === player2 || pinned.contains(video2),
      'the live pin holds the NEW video');
}

// ------------------------------------------------------------ T7: selectors
console.log('T7 two-tier vs strict selector:');
{
  const bigPaused = makeEl('video', {
    offsetWidth: 1920, offsetHeight: 1080,
    videoWidth: 3840, videoHeight: 2160, paused: true,
  });
  const smallPlaying = makeEl('video', {
    offsetWidth: 640, offsetHeight: 360,
    videoWidth: 1280, videoHeight: 720, paused: false,
  });
  const doc = makeDoc([bigPaused, smallPlaying]);
  const r = run(PIN, doc);
  assert(r.startsWith('2:1.75') || r.startsWith('2:1.777'),
      'PiP mode prefers the PLAYING video (1280/720), got ' + r);
  assert(smallPlaying.__cls.includes('__boltpvp'), 'the playing video wears the pin');
  assert(!bigPaused.__cls.includes('__boltpvp'), 'the paused giant is NOT pinned');
}
{
  const pausedOnly = makeEl('video', {
    offsetWidth: 1280, offsetHeight: 720,
    videoWidth: 1920, videoHeight: 1080, paused: true,
  });
  const doc = makeDoc([pausedOnly]);
  const r = run(PIN_STRICT, doc);
  assert(r === '0:0', 'strict mode refuses a paused page (0:0), got ' + r);
  const r2 = run(PIN, doc);
  assert(r2.startsWith('2:'), 'PiP mode still pins the paused-with-frame video');
}

// ------------------------------------------------------------ T8: unpin
console.log('T8 full restore:');
{
  const player = makeEl('div', { offsetWidth: 1280, offsetHeight: 720 });
  const video = makeEl('video', {
    offsetWidth: 1280, offsetHeight: 720,
    videoWidth: 1920, videoHeight: 1080,
  });
  player.appendChild(video);
  const anc = makeEl('div', { offsetWidth: 1300, offsetHeight: 740 });
  anc.appendChild(player);
  const doc = makeDoc([anc]);
  run(PIN, doc, {});
  // a legacy v1.3.64 pin is live too (upgrade path)
  video.__cls.push('__boltfsf');
  doc.__els.push({ id: '__boltfsf', tagName: 'STYLE', __removed: false, children: [], __cls: [] });
  run(UNPIN, doc, {});
  assert(!doc.documentElement.__cls.includes('__boltpvh'), 'html overflow layer removed');
  assert(!anc.__cls.includes('__boltpva'), 'ancestor neutralizer removed');
  assert(!player.__cls.includes('__boltpvp'), 'player pin removed');
  assert(!video.__cls.includes('__boltfsf'), 'legacy v1.3.64 pin swept too');
  assert(!doc.getElementById('__boltpv'), 'takeover style element removed');
  assert(!doc.getElementById('__boltpvl') && !doc.getElementById('__boltpvr'),
      'edge strips removed');
}

// ------------------------------------------------------------ T9: nothing
console.log('T9 nothing pinnable:');
{
  const doc = makeDoc([]);
  const r = run(PIN, doc);
  assert(r === '0:0', 'a video-less page answers 0:0 (fs pip pin=0), got ' + r);
}

// ------------------------------------------------------------ T10: the
// v1.3.66 mobile fast path — m.youtube.com-like: the player box does NOT
// track the video's (letterboxed video inside a taller player, exactly the
// geometry the ±15px walk fails on in the field: 'fs pip pin=1 player=0')
console.log('T10 known-player fast path (mobile, non-tracking box):');
{
  const player = makeEl('div', {
    cls: ['html5-video-player'],
    offsetWidth: 360, offsetHeight: 640,      // tall player, 16:9 video
  });
  const video = makeEl('video', {
    offsetWidth: 360, offsetHeight: 202,      // |640-202| >> 15: walk fails
    videoWidth: 1280, videoHeight: 720,
  });
  player.appendChild(video);
  const host = makeEl('ytm-video-player', { offsetWidth: 400, offsetHeight: 800 });
  host.appendChild(player);
  const doc = makeDoc([host]);
  const win = {};
  const r = run(PIN, doc, win);
  assert(r.startsWith('1:'), 'answer is 1:<aspect> (the PLAYER pinned via the fast path), got ' + r);
  assert(player.__cls.includes('__boltpvp'),
      'the .html5-video-player wears the pin (not the bare video)');
  assert(!video.__cls.includes('__boltpvp'), 'the bare video is not pinned');
  assert(host.__cls.includes('__boltpva'),
      'the host above the pinned player is neutralized');
  // the same tree WITHOUT the known class still falls to the walk -> bare
  const player2 = makeEl('div', { offsetWidth: 360, offsetHeight: 640 });
  const video2 = makeEl('video', {
    offsetWidth: 360, offsetHeight: 202,
    videoWidth: 1280, videoHeight: 720,
  });
  player2.appendChild(video2);
  const doc2 = makeDoc([player2]);
  const r2 = run(PIN, doc2, {});
  assert(r2.startsWith('2:'),
      'without the known player class the box-tracking walk still owns the pick, got ' + r2);
  assert(video2.__cls.includes('__boltpvp'), 'the generic page keeps the bare-video pin');
}

// ------------------------------------------------------------ T11: the PiP
// sentinel flag arms on pin and disarms on unpin (MediaHook.pipFight's gate)
console.log('T11 PiP sentinel flag lifecycle:');
{
  const player = makeEl('div', { offsetWidth: 1280, offsetHeight: 720 });
  const video = makeEl('video', {
    offsetWidth: 1280, offsetHeight: 720,
    videoWidth: 1920, videoHeight: 1080,
  });
  player.appendChild(video);
  const doc = makeDoc([player]);
  const win = {};
  run(PIN, doc, win);
  assert(win.__boltpip === 1, 'pinJs(pipMode=true) arms window.__boltpip');
  // the no-op beat keeps it armed
  run(PIN, doc, win);
  assert(win.__boltpip === 1, 'the no-op beat keeps the sentinel armed');
  run(UNPIN, doc, win);
  assert(win.__boltpip === 0, 'unpinJs disarms the sentinel');
  // the STRICT pin (the wedged-enter rescue, not PiP) must NOT arm it
  const winS = {};
  run(PIN_STRICT, doc, winS);
  assert(winS.__boltpip === undefined,
      'the strict (non-PiP) pin never arms the PiP sentinel');
}

// ------------------------------------------------------------ T13 (v1.3.67):
// the arming STAMP (single, beat-proof) + the disarm RESYNC — the exit
// gate's entry window keys on __boltpipAt, so a per-beat restamp would
// keep the gate alive for the whole session; and an armed session's unpin
// must hand the page ONE synthetic resize + fullscreenchange (the muted
// deliveries' resync chance), while a strict (never-armed) unpin must not
// v1.3.68: the stamp moment ALSO resets the GESTURE escape (__boltPipG)
// once per session — a per-beat reset would eat a real tap on interactive
// surfaces — and the unpin clears it with the disarm
console.log('T13 (v1.3.67/68) arming stamp + gesture flag + disarm resync:');
{
  const player = makeEl('div', { offsetWidth: 1280, offsetHeight: 720 });
  const video = makeEl('video', {
    offsetWidth: 1280, offsetHeight: 720,
    videoWidth: 1920, videoHeight: 1080,
  });
  player.appendChild(video);
  const doc = makeDoc([player]);
  const fired = [];
  const win = { dispatchEvent(ev) { fired.push(ev && ev.type); return true; } };
  run(PIN, doc, win);
  assert(win.__boltpip === 1 && typeof win.__boltpipAt === 'number',
      'the pip pin arms __boltpip AND stamps __boltpipAt (the exit gate clock)');
  assert(win.__boltPipG === 0,
      'the first stamp also resets the gesture escape __boltPipG (once per session)');
  // the beat must NOT restamp: a preset stamp survives the no-op beat —
  // and a gesture flag set mid-session must survive it too
  win.__boltpipAt = 12345;
  win.__boltPipG = 1;
  run(PIN, doc, win);
  assert(win.__boltpipAt === 12345,
      'the beat keeps the FIRST stamp (a per-beat restamp would keep the exit gate alive forever)');
  assert(win.__boltPipG === 1,
      'the beat does NOT eat a real gesture (a per-beat reset would break interactive surfaces)');
  // a full re-pin cycle (player re-creation) must not restamp either
  const player2 = makeEl('div', { offsetWidth: 1280, offsetHeight: 720 });
  const video2 = makeEl('video', {
    offsetWidth: 1280, offsetHeight: 720,
    videoWidth: 1920, videoHeight: 1080,
  });
  player2.appendChild(video2);
  doc.body.appendChild(player2);
  run(PIN, doc, win);
  assert(win.__boltpipAt === 12345 && win.__boltpip === 1,
      'the re-pin cycle keeps the session stamp');
  assert(win.__boltPipG === 1,
      'the re-pin cycle keeps a real gesture too');
  // the death-path latch rides along and the disarm clears everything
  win.__boltpipx = 1;
  run(UNPIN, doc, win);
  assert(win.__boltpip === 0 && win.__boltpipx === 0 && win.__boltpipAt === 0,
      'unpin clears __boltpip, __boltpipx and __boltpipAt');
  assert(win.__boltPipG === 0,
      'unpin clears the gesture escape __boltPipG with the disarm');
  assert(fired.includes('resize') && (doc.__dispatched || []).includes('fullscreenchange'),
      'an ARMED session unpin dispatches the synthetic resize + fullscreenchange (the resync)');
  // a strict (never-armed) unpin must not fire anything
  const docS = makeDoc([player]);
  const firedS = [];
  const winS = { dispatchEvent(ev) { firedS.push(ev && ev.type); return true; } };
  run(PIN_STRICT, docS, winS);
  run(UNPIN, docS, winS);
  assert(firedS.length === 0 && (docS.__dispatched || []).length === 0,
      'a never-armed (strict) unpin dispatches nothing (a normal fullscreen exit sees no second event)');
}

// ------------------------------------------------------------ T14 (v1.3.69):
// the MARKED RESYNC RESIZE — the player's gY (its resize handler) latched
// pictureInPicture from the shrunk outer size BEFORE the arm; the spoof
// alone cannot un-latch it (no further resize is delivered — the armed
// mutes swallow the real ones), so the FIRST arm hands the page ONE
// resize marked __boltRS — the only event pipMuted lets through. The
// beat and the re-pin cycle must never re-deliver it, and the disarm's
// own resync resize (v1.3.67) stays UNmarked.
console.log('T14 (v1.3.69) the marked RESYNC resize at first arm:');
{
  const player = makeEl('div', { offsetWidth: 1280, offsetHeight: 720 });
  const video = makeEl('video', {
    offsetWidth: 1280, offsetHeight: 720,
    videoWidth: 1920, videoHeight: 1080,
  });
  player.appendChild(video);
  const doc = makeDoc([player]);
  const sent = [];
  const win = { dispatchEvent(ev) { sent.push({ type: ev && ev.type, rs: !!(ev && ev.__boltRS) }); return true; } };
  run(PIN, doc, win);
  assert(sent.length === 1 && sent[0].type === 'resize' && sent[0].rs === true,
      'the FIRST arm dispatches exactly ONE resize, marked __boltRS (gY re-evaluates the spoofed size)');
  assert(win.__boltpip === 1, 'the arm itself is unchanged (the sentinel flag)');
  run(PIN, doc, win);
  assert(sent.length === 1,
      'the 900 ms beat does NOT re-deliver resizes (the __boltpipAt guard holds)');
  // a full re-pin cycle (player re-creation) must not re-dispatch either
  const player2 = makeEl('div', { offsetWidth: 1280, offsetHeight: 720 });
  const video2 = makeEl('video', {
    offsetWidth: 1280, offsetHeight: 720,
    videoWidth: 1920, videoHeight: 1080,
  });
  player2.appendChild(video2);
  doc.body.appendChild(player2);
  run(PIN, doc, win);
  assert(sent.length === 1,
      'the re-pin cycle keeps the session single-resync');
  run(UNPIN, doc, win);
  const un = sent[sent.length - 1];
  assert(sent.length === 2 && un.type === 'resize' && un.rs === false,
      'the disarm resync resize is UNmarked (it runs disarmed — the v1.3.67 semantics)');
}

// ------------------------------------------------------------ T12: a decoy
// player class on a NON-ancestor must not capture the pin
console.log('T12 decoy player class (non-ancestor):');
{
  const decoy = makeEl('div', {
    cls: ['html5-video-player'],
    offsetWidth: 100, offsetHeight: 100,
  });
  const video = makeEl('video', {
    offsetWidth: 600, offsetHeight: 340,
    videoWidth: 1280, videoHeight: 720,
  });
  const doc = makeDoc([decoy, video]);
  const r = run(PIN, doc, {});
  assert(r.startsWith('2:'), 'the decoy cannot capture the pin (closest walks ancestors), got ' + r);
  assert(!decoy.__cls.includes('__boltpvp'), 'the decoy is NOT pinned');
  assert(video.__cls.includes('__boltpvp'), 'the bare video keeps the pin');
}

console.log(failures === 0 ? 'ALL PASS' : failures + ' FAILURES');
process.exit(failures === 0 ? 0 : 1);
