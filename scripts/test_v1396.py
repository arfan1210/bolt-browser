#!/usr/bin/env python3
"""v1.3.96 gate — the v1.3.95 field report: the home-drag crash, the
AI-saver corpse, the Save-as-PDF failures.

DRAG CRASH — "Dragging home screen icons to rearrange crashes the
   browser" + the stack: IllegalStateException "already recycled once"
   at android.view.ViewGroup$TouchTarget.recycle during
   nativeConsumeBatchedInputEvents (a BATCHED move consumed inside
   doFrame). ROOT CAUSE (AOSP-10 verified this session): sdDragEnd ran
   INSIDE onTouch (the cell feed sdCellTouch / the shield overlay) —
   endSdDrag() removed the overlay + dwell pill from flRoot and
   refreshHome() rebuilt the ENTIRE home tree while the framework's
   dispatch loops were still walking touch-target lists THROUGH that
   tree; API 29's detach paths (cancelAndClearTouchTargets /
   cancelTouchTarget) recycle targets the in-flight loops still hold,
   the TouchTarget pool corrupts, and a later batched event detonates.
   THE DISPATCH LAW (the launcher standard): a touch callback only
   RECORDS; every view-tree mutation is POSTED past the end of the
   dispatch. Fixes: sdDragEnd disarms synchronously and posts teardown +
   menu/reorder; sdDragMove no-ops a disarmed drag; startSdDwell's pill
   add is posted with a staleness guard; resetSdDwell's pill removal is
   posted (instance-captured); the toolbar-swipe's showTab (same
   mutation class, tab tree swap inside the UP dispatch) is posted too.

AI SAVER — "Ai response saver does nothing when pressed": pickerJs's
   JSON came back through evaluateJavascript as a QUOTED string; new
   JSONObject(quoted) THROWS ("A JSONObject text must begin with '{'"),
   the old catch swallowed it, and the native picker NEVER started — on
   any site, since v1.3.92 (the three «I don't see a way» reports were
   this corpse, not discoverability). Fixed with the JSONTokener
   double-parse (aiSyncOnce's pattern) + the failure is NAMED now.

SAVE-AS-PDF — "Save as pdf fails and does not cover the whole webpage
   to be saved", three corpses:
   (a) THE PARSE KILLER: textBlocksJs shipped a broken JS string split
       («' + .ad» — an invalid token): the whole IIFE failed to PARSE,
       every Text-PDF save returned «No readable text found». Fixed;
       the walk also gains tables/definition lists (td/th/dd/dt/
       figcaption) and doubled caps (2500 blocks, 12000-char pre).
   (b) THE HEIGHT LIE: contentHeight is viewport-clamped on
       inner-scroller SPAs — the stitch tiled one screen and «did not
       cover the whole webpage». The page's own max(scrollHeight,
       offsetHeight) across html/body/scrollingElement is measured in
       the DOM now (heightJs), and the generic page image path FLATTENS
       the big fixed-height inner scrollers first (reversible, CSSOM
       inline styles — the AI export's prepJs law) with a restore right
       after the capture.
   (c) THE TRUNCATION CEILING: the PDF stitch capped at 16000 px for no
       bitmap reason (each strip is blitted and recycled) — 32000 now.
   Plus the honest guard: a page STILL LOADING warns that the PDF may
   miss content (Chromium prints what is laid out).
   THE GATE-CLASS LESSON: v1.3.95's «F pdf x6» pins were SUBSTRING
   checks — a parse-dead JS blob passed them. Every PageSave JS builder
   is node --checked in THIS gate (the AiExport corpus already had
   check_aiexport_js.js; PageSave had nothing).
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
PS = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/PageSave.kt")
AE = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/AiExport.kt")
FS = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/FullShot.kt")
GR = os.path.join(ROOT, "app/build.gradle")

fails = 0

def need(cond, why):
    global fails
    tag = "PASS" if cond else "FAIL"
    print(f"  {tag} {why}")
    if not cond:
        fails += 1

def slurp(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

ba = slurp(BA)
ps = slurp(PS)
ae = slurp(AE)
fs = slurp(FS)
gradle = slurp(GR)

print("A — the dispatch contract (the drag crash)")
need("if (sdDragSrc < 0) return;   // no drag armed / already ending" in ba,
     "sdDragEnd disarms a drag it does not own (one-shot guard)")
need(re.search(r"protected void sdDragEnd\(float x, float y\) \{[\s\S]*?ui\.post\(\(\) -> \{[\s\S]*?endSdDrag\(\);", ba) is not None,
     "sdDragEnd POSTS the teardown (endSdDrag inside ui.post)")
need(re.search(r"protected void sdDragEnd\(float x, float y\) \{[\s\S]*?ui\.post\(\(\) -> \{[\s\S]*?refreshHomeQuiet\(\);", ba) is not None,
     "the reorder's rebuild (refreshHomeQuiet — v1.3.97: quiet + position-true) rides the same post (never mid-dispatch)")
need("if (sdDragSrc < 0) return;\n        if (sdGhost != null) {" in ba
     or re.search(r"protected void sdDragMove\(float x, float y\) \{\s*\n\s*// v1\.3\.96[\s\S]{0,220}?if \(sdDragSrc < 0\) return;", ba) is not None,
     "sdDragMove no-ops a disarmed drag")
need(re.search(r"protected void startSdDwell\(final int target\) \{\s*\n\s*if \(sdDragSrcIsGroup\) return;[^\n]*\n\s*ui\.post\(\(\) -> \{\s*\n\s*try \{\s*\n\s*if \(sdOverlay == null \|\| sdDragSrc < 0 \|\| sdFolderTarget != target\)", ae if False else ba) is not None,
     "startSdDwell is POSTED with the staleness guard (pill never born mid-dispatch)")
need(re.search(r"final TextView pill = sdDwellPill;\s*\n\s*sdDwellPill = null;[\s\S]{0,220}?ui\.post\(\(\) -> \{[\s\S]{0,140}?flRoot\.removeView\(pill\)", ba) is not None,
     "resetSdDwell POSTS the pill removal (instance-captured)")
need("final int swTarget = target;" in ba and "showTab(swTarget, true)" in ba,
     "the toolbar-swipe tab switch is posted past the UP dispatch")
# the contract holds BOTH ways: the immediate block must contain NO tree calls
m = re.search(r"protected void sdDragEnd\(float x, float y\) \{([\s\S]*?)ui\.post", ba)
if m:
    head = "\n".join(l for l in m.group(1).splitlines()
                     if not l.strip().startswith("//") and not l.strip().startswith("*"))
    need("endSdDrag()" not in head and "refreshHome()" not in head
         and "sdTileMenu(" not in head and "removeView(" not in head,
         "nothing destructive runs before the post (disarm only)")
else:
    need(False, "sdDragEnd structure found")

print("B — the AI-saver corpse (the quoted JSON)")
need(re.search(r"w\.evaluateJavascript\(com\.bolt\.browser\.util\.AiExport\.pickerJs\(\), res -> \{[\s\S]*?JSONTokener\(s\)\.nextValue\(\)", ba) is not None,
     "startAiExport unquotes through JSONTokener before the JSONObject")
need("if (v instanceof String) s = (String) v;\n                    org.json.JSONObject o = new org.json.JSONObject(s);" in ba,
     "the double-parse feeds the JSONObject (aiSyncOnce's pattern)")
need(re.search(r"\} catch \(Throwable t\) \{[\s\S]*?Could not start the response picker", ba) is not None
     and re.search(r"// v1\.3\.96 — never a silent corpse", ba) is not None,
     "a picker that cannot start SAYS so (no silent corpse)")

print("C — Save as PDF: the parse killer + the height lie + the ceiling")
need("'script,style,noscript,iframe,svg,form,button,aside,nav,footer,header,\" +\n            \".ad,.ads," in ps,
     "the v1.3.95 broken selector split is gone (one continuous JS string)")
need("'h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,td,th,dd,dt,figcaption'" in ps,
     "the text walk carries tables + definition lists")
need("out.length<2500" in ps and "t.length>12000" in ps,
     "the text walk caps double (2500 blocks / 12000-char pre)")
need("fun heightJs(): String" in ae and "scrollHeight" in ae,
     "AiExport.heightJs measures the page's own height")
need("fun flattenJs(): String" in ae and "__boltFlat" in ae and "fun flatRestoreJs(): String" in ae,
     "the reversible scroller flatten ships (flatten + restore)")
need("coerceIn(1, 32000)" in ae,
     "the PDF stitch ceiling doubles to 32000 (no master bitmap to bound)")
need(re.search(r"fun savePdf\(a: Activity[\s\S]{0,700}?AiExport\.flattenJs\(\)", ps) is not None,
     "the image-PDF path flattens before the capture")
need(re.search(r"fun savePdf\(a: Activity[\s\S]{0,900}?flatRestoreJs\(\)", ps) is not None,
     "and restores the page after it")
need("if (w.progress < 100)" in ps and "may miss content" in ps,
     "a still-loading page warns before printing partial content")
need(re.search(r"fun capture\(a: Activity, w: WebView, pdf: Boolean, prefix: String[\s\S]{0,900}?evaluateJavascript\(heightJs\(\)\)", ae) is not None,
     "AiExport.capture measures the true height before dispatching the stitch")
need("jsHeight: Int," in fs and "jsHeight.toFloat()" in fs,
     "FullShot honors the measured height (0 = the contentHeight fallback)")

print("D — the gate-class lesson: PageSave JS is PARSE-checked now")
node = None
for cand in ("node", "nodejs"):
    if subprocess.run(["which", cand], capture_output=True).stdout:
        node = cand
        break
if node:
    def extract(fn_name, src):
        m = re.search(r'fun ' + fn_name + r'\(\): String =\s*\n(.*?)(?=\n    (?:/\*\*|@JvmStatic|fun |private|internal)|\n\})', src, re.S)
        if not m:
            return None
        chunks = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
        js = "".join(chunks)
        return js.replace('\\"', '"').replace('\\\\', '\\').replace('\\n', '\n')
    for name, src, fns in (("PageSave", ps, ("textBlocksJs", "htmlJs")),
                           ("AiExport", ae, ("heightJs", "flattenJs", "flatRestoreJs"))):
        for fn in fns:
            js = extract(fn, src)
            ok = False
            if js:
                p = f"/tmp/v1396_gate_{fn}.js"
                with open(p, "w", encoding="utf-8") as f:
                    f.write(js)
                r = subprocess.run([node, "--check", p], capture_output=True, text=True)
                ok = r.returncode == 0
            need(ok, f"{name}.{fn} is parse-valid JS (node --check)")
else:
    print("  SKIP node not found — D probes skipped")

print("E — vector floors stay (the v1.3.95 contracts)")
need("fun savePdfVector" in ps and "com.android.printspooler" in ps,
     "the spooler probe + print path unchanged (the standard vector route)")
need("Save as PDF in the preview" in ba,
     "the print-success hint still names the tap")

print("V — version pin")
need("versionCode 124" in gradle and 'versionName "1.4.10"' in gradle,  # v1.4.10 re-point (2026-11-17): 123/1.4.9 -> 124/1.4.10 (the collision corpse: v1.4.9/123 was already live)
     "build.gradle pins 124 / 1.4.10")  # v1.4.10 re-point (2026-11-17): 123/1.4.9 -> 124/1.4.10 (the collision corpse: v1.4.9/123 was already live)

print()
if fails:
    print(f"v1.3.96 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.3.96 gate: all green")
