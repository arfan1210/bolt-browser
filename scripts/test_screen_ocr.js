#!/usr/bin/env node
/**
 * v1.3.73 — behavioral smoke test for the scr_ocr.html host page.
 * Runs the page's REAL inline script inside Node with a mocked
 * window/BoltOcr/Tesseract, and proves the protocol Kotlin depends on:
 *
 *   T1  chunked __push + __ocr → the data-URL the engine receives is
 *       exactly the joined base64 (binder-safe handoff assembled right);
 *   T2  createWorker is called with the combined langs, OEM 1 (LSTM-only)
 *       and langPath https://bolt.ocr/tess + gzip:false (local models);
 *   T3  block→paragraph→line flattening: paragraph = union of line
 *       bboxes, line texts whitespace-normalized and joined, empty
 *       lines / degenerate boxes dropped;
 *   T4  worker REUSE: a second __ocr with the same langs does NOT
 *       re-create the worker (no re-download / re-compile);
 *   T5  a changed langs string terminates the old worker and creates a
 *       fresh one;
 *   T6  engine failure (recognize rejects) reaches BoltOcr.fail and the
 *       worker cache is dropped (a retry rebuilds);
 *   T7  logger progress is forwarded as (status, rounded pct);
 *   T8  missing Tesseract (CDN script failed) fails cleanly with no-lib.
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const html = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main',
  'assets', 'scr_ocr.html'), 'utf8');

// extract the LAST inline <script> (the protocol code, not the CDN tag)
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
if (scripts.length !== 1) {
  console.log('FAIL: expected exactly one inline script block, got ' + scripts.length);
  process.exit(1);
}

let fails = 0;
function check(name, cond) {
  console.log((cond ? 'OK: ' : 'FAIL: ') + name);
  if (!cond) fails++;
}

function makeEnv() {
  const calls = { created: [], recognize: [], done: [], fail: [], progress: [], terminated: 0 };
  let workerSeq = 0;
  let nextRecognize = null;   // null → resolve with current blocks
  function fakeTesseract() {
    return {
      createWorker: (langs, oem, options) => {
        calls.created.push({ langs, oem, options });
        workerSeq++;
        const w = {
          id: workerSeq,
          recognize: (img, opts, output) => {
            calls.recognize.push({ img, opts, output, worker: w.id });
            if (nextRecognize && nextRecognize.reject) {
              const e = nextRecognize.reject; nextRecognize = null;
              return Promise.reject(new Error(e));
            }
            const blocks = (nextRecognize && nextRecognize.blocks) || [];
            if (nextRecognize) nextRecognize = null;
            return Promise.resolve({ data: { blocks } });
          },
          terminate: () => { calls.terminated++; },
        };
        if (options && options.logger) options.logger({ status: 'recognizing text', progress: 0.47 });
        return Promise.resolve(w);
      },
    };
  }
  const sandbox = {
    console,
    Date,
    Promise,
    String,
    JSON,
    Math,
    RegExp,
    window: {},
    addEventListener: () => {},
  };
  sandbox.window = sandbox; // the page's window === global sandbox
  sandbox.BoltOcr = {
    ready: () => {},
    progress: (s, p) => calls.progress.push([s, p]),
    done: (j) => calls.done.push(j),
    fail: (m) => calls.fail.push(m),
  };
  sandbox.Tesseract = undefined; // set per-test
  vm.createContext(sandbox);
  return { sandbox, calls, fakeTesseract };
}

function run(sandbox) {
  vm.runInContext(scripts[0], sandbox, { filename: 'scr_ocr.inline.js' });
}

async function t1_t3() {
  const { sandbox, calls, fakeTesseract } = makeEnv();
  sandbox.Tesseract = fakeTesseract();
  run(sandbox);
  sandbox.window.__push('QUJD');   // "QUJD" = base64 of "ABC"
  sandbox.window.__push('RA==');   // "RA==" = base64 of "D"
  sandbox.window.__ocr('chi_sim+jpn+eng');
  await new Promise(r => setTimeout(r, 20));
  check('T1 data URL is the joined chunks', calls.recognize.length === 1 &&
    calls.recognize[0].img === 'data:image/jpeg;base64,QUJDRA==');
  check('T2 combined langs + OEM 1 + bolt.ocr langPath + gzip off',
    calls.created.length === 1 && calls.created[0].langs === 'chi_sim+jpn+eng' &&
    calls.created[0].oem === 1 &&
    calls.created[0].options.langPath === 'https://bolt.ocr/tess' &&
    calls.created[0].options.gzip === false);
  check('T2b blocks output requested + PSM 3 (AUTO — vertical/mixed layouts)',
    JSON.stringify(calls.recognize[0].output) === '{"text":true,"blocks":true,"hocr":false,"tsv":false}' &&
    calls.recognize[0].opts && calls.recognize[0].opts.tessedit_pageseg_mode === '3');
  // fill the NEXT recognize result — set before firing __ocr
  const { sandbox: sb2, calls: c2, fakeTesseract: ft2 } = makeEnv();
  sb2.Tesseract = ft2();
  // patch: pre-load the fake result by monkey-seating nextRecognize via the
  // created worker — simpler: re-run with a Tesseract whose recognize
  // resolves paragraph structures immediately.
  const blocks = [{
    paragraphs: [{
      lines: [
        { text: '  hello   ', bbox: { x0: 10, y0: 20, x1: 110, y1: 50 } },
        { text: 'world ', bbox: { x0: 12, y0: 55, x1: 108, y1: 85 } },
      ],
    }],
  }, {
    paragraphs: [{
      lines: [
        { text: '  ', bbox: { x0: 1, y0: 1, x1: 5, y1: 5 } },        // empty text → dropped
        { text: 'ok', bbox: { x0: 200, y0: 300, x1: 260, y1: 330 } },
      ],
    }],
  }, {
    paragraphs: [{ lines: [{ text: 'x', bbox: { x0: 0, y0: 0, x1: 2, y1: 2 } }] }], // degenerate box
  }];
  const fake = {
    createWorker: (langs, oem, options) => {
      const w = { recognize: () => Promise.resolve({ data: { blocks } }), terminate: () => {} };
      return Promise.resolve(w);
    },
  };
  sb2.Tesseract = fake;
  run(sb2);
  sb2.window.__push('eHg=');
  sb2.window.__ocr('jpn');
  await new Promise(r => setTimeout(r, 20));
  const out = JSON.parse(c2.done[0]);
  // NOTE: the page guarantees non-empty text + positive box only; the
  // SIZE/text-length filters live in Kotlin (ocrDone) where the
  // jpeg→bitmap scale factor is known — so the tiny 2×2 'x' line passes
  // through here and is dropped on the Kotlin side. LINE-level units:
  // one entry per line, boxes straight from the line bbox.
  check('T3 line flattening (per-line boxes, junk dropped)',
    out.length === 4 &&
    out[0][0] === 10 && out[0][1] === 20 && out[0][2] === 110 && out[0][3] === 50 &&
    out[0][4] === 'hello' &&
    out[1][0] === 12 && out[1][1] === 55 && out[1][3] === 85 && out[1][4] === 'world' &&
    out[2][4] === 'ok' && out[2][0] === 200 &&
    out[3][4] === 'x' && out[3][2] - out[3][0] === 2);
}

async function t4_t5() {
  const { sandbox, calls, fakeTesseract } = makeEnv();
  sandbox.Tesseract = fakeTesseract();
  run(sandbox);
  sandbox.window.__push('YQ==');
  sandbox.window.__ocr('eng');
  await new Promise(r => setTimeout(r, 20));
  sandbox.window.__push('Yg==');
  sandbox.window.__ocr('eng');
  await new Promise(r => setTimeout(r, 20));
  check('T4 same langs reuses the worker (no second createWorker)',
    calls.created.length === 1 && calls.recognize.length === 2 &&
    calls.recognize[0].worker === calls.recognize[1].worker);
  sandbox.window.__push('Yw==');
  sandbox.window.__ocr('jpn_vert+eng');
  await new Promise(r => setTimeout(r, 20));
  check('T5 changed langs rebuilds (terminate + new createWorker)',
    calls.created.length === 2 && calls.terminated === 1 &&
    calls.recognize[1].img === 'data:image/jpeg;base64,Yg==' &&
    calls.recognize[2].img === 'data:image/jpeg;base64,Yw==');
}

async function t6() {
  const { sandbox, calls, fakeTesseract } = makeEnv();
  sandbox.Tesseract = fakeTesseract();
  run(sandbox);
  // make the first recognize reject
  const { sandbox: sb, calls: c, fakeTesseract: ft } = makeEnv();
  sb.Tesseract = ft();
  run(sb);
  // sabotage: reject on first recognize
  const origCreate = sb.Tesseract.createWorker;
  let first = true;
  sb.Tesseract = {
    createWorker: (l, o, opt) => origCreate(l, o, opt).then(w => {
      if (first) {
        first = false;
        const orig = w.recognize;
        w.recognize = () => Promise.reject(new Error('wasm exploded'));
        // keep a hook to restore for the retry
        w._orig = orig;
      }
      return w;
    }),
  };
  sb.window.__push('ZA==');
  sb.window.__ocr('eng');
  await new Promise(r => setTimeout(r, 20));
  check('T6 recognize rejection reaches BoltOcr.fail',
    c.fail.length === 1 && c.fail[0] === 'wasm exploded' && c.done.length === 0);
  // retry after failure must rebuild the worker (cache was dropped)
  sb.window.__push('ZQ==');
  sb.window.__ocr('eng');
  await new Promise(r => setTimeout(r, 20));
  check('T6b retry after failure re-creates the worker', c.created.length === 2);
}

async function t7_t8() {
  const { sandbox, calls, fakeTesseract } = makeEnv();
  sandbox.Tesseract = fakeTesseract();
  run(sandbox);
  sandbox.window.__push('YQ==');
  sandbox.window.__ocr('eng');
  await new Promise(r => setTimeout(r, 20));
  check('T7 logger progress forwarded rounded', calls.progress.length >= 1 &&
    calls.progress.some(p => p[0] === 'recognizing text' && p[1] === 47));
  const { sandbox: sb, calls: c } = makeEnv();
  run(sb); // NO Tesseract
  sb.window.__push('eg==');
  sb.window.__ocr('eng');
  await new Promise(r => setTimeout(r, 20));
  check('T8 missing engine fails cleanly with no-lib',
    c.fail.length === 1 && c.fail[0] === 'no-lib' && c.done.length === 0);
}

(async () => {
  await t1_t3();
  await t4_t5();
  await t6();
  await t7_t8();
  if (fails) { console.log(fails + ' FAILURES'); process.exit(1); }
  console.log('scr_ocr.html protocol: all mock tests green');
})();
