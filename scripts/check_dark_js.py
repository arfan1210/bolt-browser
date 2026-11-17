#!/usr/bin/env python3
"""v1.3.87 — syntax-check the new dark-mode JS payloads in MediaHook.java
(darkProbeJs / darkSmartJs / darkClearJs / styleRemoveJs) with node --check.
v1.3.92 — the scan became darkSmartJs (NI pin + the parity walk) and the
pull path became darkClearJs (style + class strip)."""
import re, subprocess, sys
from pathlib import Path

src = Path(__file__).resolve().parent.parent / "app/src/main/java/com/bolt/browser/media/MediaHook.java"
text = src.read_text(encoding="utf-8")

def extract(method):
    m = re.search(r"public static String " + method + r"\([^)]*\)\s*\{(.*?)\n    \}", text, re.S)
    if not m:
        print(f"FAIL: {method} not found"); sys.exit(1)
    body = m.group(1)
    # join Java string concatenation: pull every "..." literal
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', body)
    js = "".join(p for p in parts)
    # unescape Java string escapes
    js = js.replace('\\"', '"').replace("\\\\", "\\").replace("\\n", "\n")
    return js

ok = True
for name in ("darkProbeJs", "darkSmartJs", "darkClearJs", "styleRemoveJs"):
    js = extract(name)
    Path(f"/tmp/{name}.js").write_text(js, encoding="utf-8")
    r = subprocess.run(["node", "--check", f"/tmp/{name}.js"], capture_output=True, text=True)
    status = "OK" if r.returncode == 0 else "SYNTAX ERROR\n" + r.stderr
    print(f"{name}: {len(js)} bytes -> {status}")
    if r.returncode != 0:
        ok = False

# behavioral smoke: the probe against light and dark fake styles
probe = extract("darkProbeJs")
node = f"""
const fs = require('fs');
const probe = fs.readFileSync('/tmp/darkProbeJs.js', 'utf8');
function run(bgHtml, bgBody, scheme, themeColor) {{
  const styleMap = new Map();
  const docEl = {{ }};
  const body = {{ }};
  const gcs = (el) => ({{
    get backgroundColor() {{ return el === docEl ? bgHtml : bgBody; }},
    get colorScheme() {{ return scheme || ''; }}
  }});
  let meta = null;
  if (themeColor) meta = {{ getAttribute: () => themeColor }};
  const document = {{
    documentElement: docEl, body,
    querySelector: (s) => s.includes('theme-color') ? meta : null
  }};
  return eval('(' + probe.slice(probe.indexOf('{')+1, probe.lastIndexOf('}')) + ')');
}}
// note: probe is an IIFE expression; run via Function
const f = new Function('document', 'getComputedStyle', 'return ' + probe);
console.log('light body  ->', f({{documentElement:{{}},body:{{}}}}, (el)=>({{backgroundColor:'rgb(255, 255, 255)'}})));
console.log('dark body   ->', f({{documentElement:{{}},body:{{}}}}, (el)=>({{backgroundColor:'rgb(13, 17, 23)'}})));
console.log('transparent ->', f({{documentElement:{{}},body:{{}}}}, (el)=>({{backgroundColor:'rgba(0, 0, 0, 0)'}})));
console.log('scheme dark ->', f({{documentElement:{{}},body:{{}}}}, (el)=>({{backgroundColor:'rgba(0, 0, 0, 0)', colorScheme:'dark light'}})));
console.log('theme dark  ->', f({{documentElement:{{}},body:{{}}}}, (el)=>({{backgroundColor:'rgba(0, 0, 0, 0)'}})));
"""
# the theme-color case needs a document with querySelector; simplify: skip via env mismatch is fine — just run the pure cases
node_simple = f"""
const fs = require('fs');
const probe = fs.readFileSync('/tmp/darkProbeJs.js', 'utf8');
const f = new Function('document', 'getComputedStyle', 'return ' + probe);
const mkDoc = (meta) => ({{
  documentElement: {{}}, body: {{}},
  querySelector: (s) => meta
}});
console.log('light body  ->', f(mkDoc(null), (el)=>({{backgroundColor:'rgb(255, 255, 255)'}})));
console.log('dark body   ->', f(mkDoc(null), (el)=>({{backgroundColor:'rgb(13, 17, 23)'}})));
console.log('transparent+nosig ->', f(mkDoc(null), (el)=>({{backgroundColor:'rgba(0, 0, 0, 0)'}})));
console.log('scheme dark ->', f(mkDoc(null), (el)=>({{backgroundColor:'rgba(0, 0, 0, 0)', colorScheme:'dark light'}})));
console.log('theme dark  ->', f(mkDoc({{getAttribute:()=> '#0d1117'}}), (el)=>({{backgroundColor:'rgba(0, 0, 0, 0)'}})));
console.log('theme light ->', f(mkDoc({{getAttribute:()=> '#ffffff'}}), (el)=>({{backgroundColor:'rgba(0, 0, 0, 0)'}})));
"""
Path("/tmp/probe_smoke.js").write_text(node_simple, encoding="utf-8")
r = subprocess.run(["node", "/tmp/probe_smoke.js"], capture_output=True, text=True)
print(r.stdout, r.stderr)
if r.returncode != 0:
    ok = False

sys.exit(0 if ok else 1)
