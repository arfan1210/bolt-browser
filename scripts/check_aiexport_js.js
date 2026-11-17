#!/usr/bin/env node
/* v1.3.92 — quick parse gate for the rewritten AiExport JS corpus.
 * Extracts every String-returning JS builder from AiExport.kt the same
 * way test_aiexport.js does and node --check's each one. A parse failure
 * anywhere in a shipped blob = the whole injection is dead. */
const fs = require('fs'), path = require('path'), {execFileSync} = require('child_process');
const KT = path.join(__dirname, '..', 'app/src/main/java/com/bolt/browser/util/AiExport.kt');
const src = fs.readFileSync(KT, 'utf8');
const methods = [...src.matchAll(/fun (\w+)\(\): String =/g)].map(m => m[1]);
let bad = 0;
for (const meth of methods) {
  const re = new RegExp('fun ' + meth + '\\(\\): String =([\\s\\S]*?)(?=\\n    // |\\n    /\\*\\*|\\n})');
  const m = src.match(re);
  if (!m) { console.log('SKIP (no body match): ' + meth); continue; }
  const lits = m[1].match(/"((?:[^"\\]|\\.)*)"/g) || [];
  const js = lits.map(s => s.slice(1, -1)
      .replace(/\\\\/g, '\x00').replace(/\\"/g, '"').replace(/\x00/g, '\\')).join('');
  if (!js) { console.log('SKIP (empty): ' + meth); continue; }
  const f = '/tmp/aiex_' + meth + '.js';
  fs.writeFileSync(f, js);
  try {
    execFileSync('node', ['--check', f], {stdio: 'pipe'});
    console.log('ok: ' + meth + ' (' + js.length + ' bytes)');
  } catch (e) {
    bad++;
    console.log('FAIL: ' + meth + '\n' + String(e.stderr).slice(0, 900));
  }
}
process.exit(bad ? 1 : 0);
