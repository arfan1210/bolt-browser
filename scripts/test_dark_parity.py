#!/usr/bin/env python3
"""v1.3.92 gate — validate the JS strings MediaHook.darkSmartJs/darkClearJs
(and the AiExport JS when touched) actually PARSE. Run after any edit.

Extracts the concatenation from the Java source by evaluating the string
literals, then runs node --check on the result. A parse error anywhere in a
shipped JS blob means the whole injection is dead (no partial execution).
"""
import re, subprocess, sys, tempfile, os

SRC = "app/src/main/java/com/bolt/browser/media/MediaHook.java"
node = subprocess.run(["which", "node"], capture_output=True, text=True).stdout.strip()
if not node:
    print("SKIP: node not found"); sys.exit(0)

src = open(SRC, encoding="utf-8").read()

def extract(method):
    m = re.search(r'public static String ' + method + r'\(\)\s*\{(.*?)\n    \}', src, re.S)
    if not m:
        print(f"FAIL: {method} not found"); sys.exit(1)
    body = m.group(1)
    # collect the concatenated string literals in order
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', body)
    if not parts:
        print(f"FAIL: {method} has no string literals"); sys.exit(1)
    # java escapes inside literals: \" \\ \n \t etc. — unescape minimal set
    out = []
    for p in parts:
        p = p.replace('\\"', '"').replace('\\\\', '\\')
        p = p.replace('\\n', '\n').replace('\\t', '\t').replace('\\r', '\r')
        out.append(p)
    return "".join(out)

fails = 0
for meth in ("darkSmartJs", "darkClearJs", "darkProbeJs", "darkFallbackCss"):
    if meth == "darkFallbackCss":
        continue  # CSS, not JS
    js = extract(meth)
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(js); path = f.name
    r = subprocess.run([node, "--check", path], capture_output=True, text=True)
    os.unlink(path)
    if r.returncode != 0:
        print(f"FAIL: {meth} does not parse:\n{r.stderr[:800]}")
        fails += 1
    else:
        print(f"ok: {meth} parses ({len(js)} bytes)")

# parity-engine semantics probe in node: build a tiny DOM-free harness to
# verify the luminance math + parity decisions used by darkSmartJs
sem = r'''
function lumOf(c){try{if(!c||c==='transparent')return null;
var m=c.match(/rgba?\(([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,/\s]+([\d.]+))?\)/);
if(!m)return null;var a=m.length>4?parseFloat(m[4]):1;if(a<0.45)return null;
return (0.2126*parseFloat(m[1])+0.7152*parseFloat(m[2])+0.0722*parseFloat(m[3]))/255;}catch(e){return null;}}
const cases=[
 ["rgb(255, 255, 255)", 1.0, "white stays light -> inverted"],
 ["rgb(0, 0, 0)", 0.0, "black stays dark -> preserved"],
 ["rgb(51, 51, 51)", 51/255, "#333 dark grey -> preserved"],
 ["rgb(68, 68, 68)", 68/255, "#444 dark grey -> preserved"],
 ["rgb(85, 85, 85)", 85/255, "#555 dark grey -> preserved (<=0.35)"],
 ["rgb(102, 102, 102)", 102/255, "#666 mid grey -> inverted"],
 ["rgba(0, 0, 0, 0.2)", null, "near-transparent -> inherit"],
 ["transparent", null, "transparent -> inherit"],
];
let bad=0;
for(const [c,exp,note] of cases){
 const got=lumOf(c);
 const ok=(exp===null)?(got===null):(got!==null&&Math.abs(got-exp)<0.01);
 if(!ok){bad++;console.log("FAIL lum",c,got,"expected",exp);}
 else console.log("ok lum",c,(got===null?"null":got.toFixed(3)),"-",note);
}
process.exit(bad?1:0);
'''
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
    f.write(sem); path = f.name
r = subprocess.run([node, path], capture_output=True, text=True)
os.unlink(path)
print(r.stdout.strip())
if r.returncode != 0:
    fails += 1

sys.exit(1 if fails else 0)
