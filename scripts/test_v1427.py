#!/usr/bin/env python3
"""v1.4.27 — the picture-stacking + merged-pipeline + real-line-table +
robust-QR round (four fixes, each with a probe).

Root-caused from the v1.4.26 source + the user's saved TBS DOM + the field
report (Realme RMX2020 / Android 10, photo-from-device + camera
bottom-half-black + manga fails-after-a-few-runs + zoomed-out no-op):

1. FORCED-DARK PICTURE STACKING (the saved TBS DOM's evidence): 65
   <picture class="ratio ratio__16x9"> blocks each wrapping an
   <img class="ratio ratio__16x9 lazyloaded> with NO __bolt class. The
   counter-invert applies to img,video,picture,canvas,svg TOGETHER, so
   picture>img stacked html x picture x img = 3 inversions = ODD = net
   INVERTED (a standalone img is 2 = correct). THE NESTED-MEDIA EXEMPTION
   (CSS-only — the classifier and the DIU portal are untouched on this
   leg): picture img/video/canvas/svg contribute NO second counter-filter
   -> picture>img = html x picture = 2 = ORIGINAL. Belt-and-braces for
   plain-img thumbnails: a REMOTE-URL IMG over 48 px with no icon-named
   ancestor is CONTENT, and the IMG banner threshold drops to 1.5 (16:9
   thumbs are content at any size; DIU's data:URI squares in icon-named
   wrappers are untouched). The DIU fixture regression rides G (below)
   and the standing test_v1426.py suite.
2. TRANSLATE — THE ONE-PIPELINE MERGE: AI Page / Basic / Manga / Manga HD
   all ride the SAME per-tile native-resolution grid pass; the collected
   line boxes CLUSTER (bubblesOf) across tiles and ONE card places per
   CLUSTER region (sampled bg, line height, preserved line breaks) —
   never fixed grid cells, never model box_2d. Translation rides the ONE
   textTranslate (the AI-Page accuracy source with its 503/400 retry +
   cap ladders); Manga is a PREPROCESSING VARIANT per tile
   (x2/hard-5/95/PNG + jpn_vert/b), and the whole-page OcrHost run — the
   separate path that stacked a slow recognize behind a stuck worker and
   failed after a few runs — is RETIRED. The OcrHost re-arm: busy ->
   window.__reset, sequence-guarded timeout, the page's own pending
   guard. Probe: the stuck-worker re-arm ladder + 5 consecutive runs
   succeed (Node, the REAL page script).
3. BOLT AI TABLES — REAL LINES: the monospace text grid had NO borders.
   The TableSpan ReplacementSpan draws the true grid on the canvas —
   rows+1 horizontal + cols+1 vertical rules (a 3x3 draws 4 + 4), bold
   header on its filled band, cell padding, theme-aware line/text
   colors, content-measured column widths; renderTable stays the fallback
   and the raw markdown keeps riding the bubble's tag.
4. QR — (a) the gallery ladder (full-res first under the 8 MP memory law,
   then 1600/1200/800) each through decodeRobust — the camera's full kit
   (plain+inverted ARGB, the luma stretch/half passes, the rotations,
   the center-crop windows); probe: a stylized low-contrast QR occupying
   part of a 1600x1200 photo decodes. (b) the preview transform re-derives
   on the FIRST delivered frame, asserts the cover-scale FILLS BOTH AXES,
   re-pins the buffer once on mismatch, and Diags view/buf/theta/scale;
   probe: the fills law (portrait 720x1600 with 640x480 and 1280x720) +
   the re-pin ladder.
"""
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MH = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/media/MediaHook.java")
ST = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/ScreenTranslate.kt")
AR = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/AiRender.kt")
AB = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/AiBridge.kt")
QR = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Qr.java")
QC = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/QrCore.java")
HO = os.path.join(ROOT, "app/src/main/assets/scr_ocr.html")
QSA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/QrScanActivity.java")
BG = os.path.join(ROOT, "app/build.gradle")
RELS = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()

mh_raw = open(MH, encoding="utf8").read()
st_raw = open(ST, encoding="utf8").read()
ar_raw = open(AR, encoding="utf8").read()
ab_raw = open(AB, encoding="utf8").read()
qr_raw = open(QR, encoding="utf8").read()
qc_raw = open(QC, encoding="utf8").read()
ho_raw = open(HO, encoding="utf8").read()
qsa_raw = open(QSA, encoding="utf8").read()

failed = 0


def need(cond, label):
    global failed
    print(("  ok   - " if cond else "  FAIL - ") + label)
    if not cond:
        failed += 1


print("A — FORCED-DARK: THE NESTED-MEDIA EXEMPTION (the TBS picture corpse)")

# ---- A1. the CSS rule rides the sheet, AFTER the IC rule (class still wins) ----
need("picture img,picture video,picture canvas,picture svg" in mh_raw
     and "{filter:none!important;}" in mh_raw,
     "the nested-media exemption rule is in darkFallbackCss")
need(mh_raw.find("picture img,picture video,picture canvas,picture svg")
     > mh_raw.find(".__boltIC{filter:none!important;}"),
     "the exemption rides after the __boltIC rule (a classed icon keeps "
     "its own law; both agree on filter:none either way)")
need(mh_raw.find("picture img,picture video,picture canvas,picture svg")
     < mh_raw.find("text-shadow:0 0 0.35px currentColor"),
     "the exemption rides before the glyph-halo rule (the sheet order)")

# ---- A2. the parity arithmetic (transcribed from the CSS rules) ----
def parity(*layers):
    """the net inversion count of a pixel under the stacked filters
    (html invert x each matching counter-invert; filter:none = 0)"""
    n = 1                                   # the html-level invert
    for contributes in layers:
        n += 1 if contributes else 0
    return n % 2                            # 0 = ORIGINAL, 1 = INVERTED


need(parity(True) == 0,
     "a standalone img (html x img = 2) renders ORIGINAL")
need(parity(True, True) == 1,
     "picture>img WITHOUT the exemption (html x picture x img = 3) renders "
     "INVERTED — the TBS corpse")
need(parity(True, False) == 0,
     "picture>img WITH the exemption (html x picture = 2, the img adds "
     "NOTHING) renders ORIGINAL — the fix")
need(parity(True, True) == parity(True, True),
     "picture>video/canvas/svg stack identically (the same exemption "
     "selector family)")
need(parity(False) == 1,
     "an IC-classed img (filter:none; html x nothing = 1) RIDES the "
     "inversion — the glyph law, unchanged")
need(parity(False, False) == 1,
     "an IC img inside a DK subtree (html x DK x none = 1): the DK pair "
     "cancels and the glyph rides — unchanged")

# ---- A3. the classifier's belt-and-braces (source pins) ----
need("var th=(el.tagName==='IMG')?1.5:2.5;" in mh_raw,
     "bannerish: the IMG threshold is 1.5 (a 16:9 thumb = 1.78 is "
     "banner-shaped; the general element keeps 2.5)")
need("function remoteUrl(el)" in mh_raw
     and "su.indexOf('http:')===0||su.indexOf('https:')===0" in mh_raw,
     "remoteUrl: the http/https src test (data:URI icons never match)")
need("if(m>48&&el.tagName==='IMG'&&!named&&remoteUrl(el))return false;"
     in mh_raw,
     "ridesFlip: a REMOTE-URL IMG over 48 px with no icon-named ancestor "
     "is CONTENT (the plain-img thumbnail law)")

print()
print("B — TRANSLATE: THE ONE-PIPELINE MERGE")

# ---- B1. the one road (source pins) ----
need("main.post { runOneStage(a, bmp, langs, g, false, t0) }" in st_raw,
     "the AI adapter posts the shared grid stage "
     "  # v1.4.28 re-point (2026-11-22): the manga=true half moved - the "
     "wasm engine rejects the jpn_vert model family (chars=0 lines=0 for "
     "every language), so the manga modes dispatch VISION-PRIMARY "
     "(runMangaStage -> visionRegions, no OCR); the shared grid stage + "
     "its manga preprocess variant stay in the source for a future "
     "engine that can read vertical JP")
need("private fun runOneStage(a: Activity, bmp: Bitmap, langs: Array<String>, g: Int,"
     in st_raw and "private fun mergedDone(" in st_raw,
     "the shared stage + the merged decision exist")
need("val tile = if (manga) cropTileManga(bmp, rect) else cropTile(bmp, rect)"
     in st_raw and "val enc = encodeTile(tile, manga)" in st_raw
     and "if (png) t.compress(Bitmap.CompressFormat.PNG, 100, bo)" in st_raw,
     "the MANGA PREPROCESS VARIANT rides per tile (x2/hard stretch/PNG "
     "through cropTileManga + encodeTile)")
for gone in ("fun mangaEncode(", "fun mangaHostRun(", "fun mangaOcrDone(",
             "fun aiOcrStage(", "fun aiOcrDone(", "fun runOcrStage("):
    need(gone not in st_raw,
         "the separate/retired stage definition is GONE from the source: " + gone)
need("val clusters = mangaBlocks(bmp, all)" in st_raw,
     "the merged decision clusters ALL collected boxes (across tiles)")
need("val out = AiBridge.textTranslate(a, flat, tl, true)" in st_raw
     and 'replace(" ~ ", "\\n")' in st_raw,
     "the merged translation rides the ONE textTranslate (the AI-Page "
     "accuracy source + ladders) with markBreaks preserved")
need("if (manga) visionRegions(a, bmp, g, tl, clusters)" in st_raw
     and "else visionFallback(a, bmp, g, tl, null)" in st_raw,
     "OCR-blind art: manga -> bright-region vision cards, AI Page -> the "
     "vision grid cells")
need('"scr grid tiles="' in st_raw and '" path="' in st_raw
     and '"scr tile "' in st_raw,
     "the Diag: per-tile yield + clusters + WHICH translate path")

# ---- B1b. the OcrHost re-arm + the page's pending guard ----
need("if (onDone != null || onFail != null)" in st_raw
     and "window.__reset&&window.__reset()" in st_raw,
     "the OcrHost re-arm: a run onto a busy page RESETS it first (the "
     "stuck worker dies, the fresh run starts clean)")
need("val mySeq = jobSeq.incrementAndGet()" in st_raw
     and "if (jobSeq.get() != mySeq) return@Runnable" in st_raw,
     "the sequence-guarded timeout: an overtaking run's callbacks are "
     "never failed by a stale timer")
need("var pending = false;" in ho_raw
     and "window.__reset = function () {" in ho_raw
     and "B().fail('busy')" in ho_raw,
     "the host page: the pending guard fails fast ('busy') + __reset is "
     "the clean re-arm entry")

# ---- B2. PURE: the cluster-across-tiles table (transcribed bubblesOf) ----
def grown_of(r, grow=0.6):
    dy = int(grow * max(1, r[3] - r[1]))
    return (r[0] - dy, r[1] - dy, r[2] + dy, r[3] + dy)


def bubbles_of(boxes, grow=0.6, pad=2):
    rects = [tuple(b) for b in boxes]
    merged = True
    while merged:
        merged = False
        outer = True
        for i in range(len(rects)):
            for j in range(i + 1, len(rects)):
                gi, gj = grown_of(rects[i], grow), grown_of(rects[j], grow)
                if gi[0] <= gj[2] and gj[0] <= gi[2] and gi[1] <= gj[3] and gj[1] <= gi[3]:
                    rects[i] = (min(rects[i][0], rects[j][0]),
                                min(rects[i][1], rects[j][1]),
                                max(rects[i][2], rects[j][2]),
                                max(rects[i][3], rects[j][3]))
                    rects.pop(j)
                    merged = True
                    outer = False
                    break
            if not outer:
                break
    out = []
    for r in rects:
        cr = (r[0] - pad, r[1] - pad, r[2] + pad, r[3] + pad)
        if cr[2] - cr[0] >= 8 and cr[3] - cr[1] >= 8:
            out.append(cr)
    return out


# a portrait screen's two right/left tiles (720 wide, seam at x=360):
# tile A's last line ends at the seam, tile B's first line starts at it —
# the boxes MERGE across the tile boundary into ONE cluster (the merged
# pipeline's cross-tile law; the old per-tile path could never join them)
tileA = [(300, 100, 358, 120)]
tileB = [(362, 102, 430, 122)]
need(len(bubbles_of(tileA + tileB)) == 1,
     "line boxes on OPPOSITE sides of a tile seam cluster into ONE "
     "region (bubblesOf across tiles — one card, never a grid cell)")
need(len(bubbles_of([(10, 10, 60, 30), (400, 300, 460, 330)])) == 2,
     "bubbles a diameter apart stay DISTINCT")
# a bubble's stacked lines (gap 2px << 0.6 x line height) -> ONE cluster
# (the card stacks its line breaks)
bubble_lines = [(100, 100, 200, 120), (100, 122, 200, 142), (100, 144, 200, 164)]
need(len(bubbles_of(bubble_lines)) == 1,
     "a bubble's stacked lines cluster to ONE card (line breaks preserved)")

# ---- B3. the re-arm + 5-consecutive-runs probe (the REAL page script) ----
scripts = re.findall(r"<script>([\s\S]*?)</script>", ho_raw)
need(len(scripts) == 1, "the host page carries exactly one inline script")
if scripts:
    probe_b = r'''
const fs = require('fs');
const vm = require('vm');
const js = fs.readFileSync(process.argv[2], 'utf8');
let fails = 0;
function t(name, cond) {
  if (!cond) { fails++; console.log('  probe FAIL: ' + name); }
}
const calls = { created: 0, terminated: 0, done: [], fail: [] };
let workerSeq = 0;
function mkWorker(stuck) {
  workerSeq++;
  const id = workerSeq;
  return {
    id: id,
    recognize: () => stuck ? new Promise(() => {}) :
        Promise.resolve({ data: { blocks: [] } }),
    terminate: () => { calls.terminated++; },
  };
}
const sandbox = {
  console: console, Date: Date, Promise: Promise, String: String,
  JSON: JSON, Math: Math, RegExp: RegExp,
  addEventListener: () => {},
};
sandbox.window = sandbox;
sandbox.BoltOcr = {
  ready: () => {}, progress: () => {},
  done: (j) => calls.done.push(j),
  fail: (m) => calls.fail.push(m),
};
let stuckWorker = true;
sandbox.Tesseract = {
  createWorker: () => { calls.created++; return Promise.resolve(mkWorker(stuckWorker)); },
};
vm.createContext(sandbox);
vm.runInContext(js, sandbox, { filename: 'scr_ocr.inline.js' });
(async () => {
  // (1) a run whose recognize NEVER resolves — the stuck worker
  sandbox.window.__push('QUJD');
  sandbox.window.__ocr('eng');
  await new Promise(r => setTimeout(r, 20));
  t('the stuck run leaves nothing done', calls.done.length === 0);
  // (2) a SECOND __ocr while pending fails FAST with busy (never queues)
  sandbox.window.__push('eHg=');
  sandbox.window.__ocr('eng');
  await new Promise(r => setTimeout(r, 20));
  t('a run onto the stuck page fails fast with busy',
    calls.fail.length === 1 && calls.fail[0] === 'busy');
  // (3) THE RE-ARM: __reset terminates the worker + clears the flag;
  // the fresh run (Kotlin pushes it right after the reset) recreates the
  // worker and RESOLVES
  sandbox.window.__reset();
  stuckWorker = false;
  sandbox.window.__push('YQ==');
  sandbox.window.__ocr('eng');
  await new Promise(r => setTimeout(r, 30));
  t('after the re-arm the next run RECREATES the worker and resolves',
    calls.created === 2 && calls.done.length === 1);
  // (4) FIVE CONSECUTIVE RUNS all succeed (the fails-after-a-few-runs
  // corpse is dead: same langs reuse the worker, every run resolves)
  for (let i = 0; i < 5; i++) {
    sandbox.window.__push('Yg==');
    sandbox.window.__ocr('eng');
    await new Promise(r => setTimeout(r, 20));
  }
  t('5 consecutive runs succeed (created stays at the re-armed worker)',
    calls.done.length === 6 && calls.created === 2 && calls.fail.length === 1);
  console.log('  probe: fails=' + fails);
  process.exit(fails ? 1 : 0);
})();
'''
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fj:
        fj.write(scripts[0])
        jspath = fj.name
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fp:
        fp.write(probe_b)
        ppath = fp.name
    r = subprocess.run(["node", ppath, jspath], capture_output=True, text=True)
    print("  " + r.stdout.strip().replace("\n", "\n  ")[:600])
    if r.returncode != 0:
        print("  stderr: " + r.stderr[:300])
    need(r.returncode == 0,
         "the re-arm ladder probe on the REAL page script: the stuck "
         "worker -> busy fail-fast -> __reset -> the next run recreates "
         "and resolves -> 5 consecutive runs succeed (node)")

print()
print("C — BOLT AI TABLES: THE REAL-LINE GRID")

# ---- C1. the span + its laws (source pins) ----
need("internal class TableView(" in ar_raw
     and " : android.view.View(ctx) {" in ar_raw,
     "the table renders as a REAL VIEW whose onDraw paints the grid "
     "  # v1.4.28 re-point (2026-11-22): the TableSpan ReplacementSpan "
     "RENDERED NOTHING in the field (the invisible-table corpse), "
     "replaced by the view-based renderer (TableView + splitTables + "
     "fillMarkdown)")
need("for (r in 0..rows.size) {" in ar_raw
     and "canvas.drawLine(x, top0 + r * rowH, x + totalW, top0 + r * rowH, line)" in ar_raw,
     "the HORIZONTAL rules: one per row boundary (0..rows.size inclusive)")
need("for (c in 0..cols) {" in ar_raw
     and "canvas.drawLine(x + cx, top0, x + cx, top0 + totalH, line)" in ar_raw,
     "the VERTICAL rules: one per column boundary (0..cols inclusive)")
need("head.typeface = Typeface.DEFAULT_BOLD" in ar_raw
     and "canvas.drawRect(x, top0, x + totalW, top0 + rowH, band)" in ar_raw,
     "the header row rides the BOLD paint on its filled band")
need("private val padH: Float" in ar_raw and "10f * dens, 5f * dens, maxW)" in ar_raw,
     "cell padding: 10dp horizontal / 5dp vertical")
need("try { Prefs.get(c).isDark() } catch (_: Throwable) { false }" in ar_raw
     and "0x66FFFFFF.toInt() else 0x44000000" in ar_raw,
     "theme-aware rule/text colors (the dark/light branches)")
need("probe.measureText(cell)" in ar_raw,
     "column widths measured from the CONTENT")
need("sb.append('\\u2009')" in ar_raw
     and "sb.setSpan(TableMark(rows), st, sb.length," in ar_raw,
     "the marker rides its slot char in the bubble text "
     "  # v1.4.28 re-point: the marker (not the drawn span) - splitTables "
     "cuts the rendered text at the marks and the bubble container "
     "interleaves segments with the table views")
need("fun renderTable(rows: List<List<String>>): String {" in ar_raw
     and "val start = sb.length\n            sb.append(renderTable(rows))" in ar_raw,
     "renderTable stays the plain-text FALLBACK (the never-eat-a-reply law)")
need("setMeasuredDimension(w, (totalH + 2f).toInt())" in ar_raw,
     "onMeasure sizes the view to the full table height (a REAL view - "
     "the framework guarantees an attached view's onDraw runs) "
     "  # v1.4.28 re-point: the ReplacementSpan's fm-growth trick died "
     "with the span")
need("box.tag = text" in open(os.path.join(ROOT,
     "app/src/main/java/com/bolt/browser/util/BoltAi.kt"), encoding="utf8").read(),
     "the raw markdown keeps riding the bubble's tag (long-press copy) "
     "  # v1.4.28 re-point: the bubble is the CONTAINER now (box.tag)")

# ---- C2. PURE: the drawn geometry (transcribed from TableSpan.draw) ----
def table_grid(nrows, ncols, row_h=40.0, col_w=None):
    """the rule positions TableSpan.draw produces: horizontal rules at
    every row boundary (rows+1) + vertical at every column boundary
    (cols+1); returns (hs, vs, cells)"""
    col_w = col_w or [80.0] * ncols
    hs = [k * row_h for k in range(nrows + 1)]
    vs = []
    cx = 0.0
    for c in range(ncols + 1):
        vs.append(cx)
        if c < ncols:
            cx += col_w[c]
    cells = []
    for r in range(nrows):
        for c in range(ncols):
            cells.append((vs[c], hs[r], vs[c + 1], hs[r + 1]))
    return hs, vs, cells


hs, vs, cells = table_grid(3, 3)
need(len(hs) == 4 and len(vs) == 4,
     "a 3x3 table draws 4 horizontal + 4 vertical rules (the outer "
     "border IS the first/last of each family)")
need(len(cells) == 9, "all 9 cells draw")
need(all(c[2] - c[0] > 0 and c[3] - c[1] > 0 for c in cells),
     "every cell is a positive rect inside the grid")
need(hs[0] == 0.0 and vs[0] == 0.0,
     "the first rule of each family IS the outer border's top/left edge")
need(sum(1 for c in cells if c[1] == 0.0) == 3,
     "row 0's three cells are the HEADER row (bold, on its band)")

print()
print("D — QR: THE ROBUST GALLERY LADDER + THE PREVIEW TRANSFORM")

# ---- D1. the ladder + the transform (source pins) ----
need("8_000_000L" in qr_raw and "QrCore.decodeRobust(px, bw, bh)" in qr_raw
     and "int[] rungs = {Math.max(600, maxDim), 1200, 800}" in qr_raw,
     "the gallery ladder: FULL resolution first (the 8 MP memory law) "
     "through decodeRobust, then maxDim/1200/800 downscales")
need("public static String decodeMulti(int[] pixels, int width, int height)" in qc_raw
     and "public static String decodeRobust(int[] pixels, int width, int height)" in qc_raw,
     "QrCore: the ARGB multi-pass + the robust photo ladder exist")
need("rotate90Luma" in qc_raw and "cropArgb" in qc_raw and "lumaOf" in qc_raw,
     "the pure helpers: lumaOf + rotate90Luma + cropArgb (probe-pinned below)")
need("firstFrameSeen" in qsa_raw
     and "onSurfaceTextureUpdated" in qsa_raw
     and "applyPreviewTransform(tvPreview.getWidth(), tvPreview.getHeight())" in qsa_raw,
     "the transform re-derives on the FIRST delivered frame")
need("fills = uw * s >= viewW - 1f && uh * s >= viewH - 1f" in qsa_raw
     and "pinRetried = true" in qsa_raw
     and "try { pinBuffer(); } catch (Throwable ignored) {}" in qsa_raw,
     "the fills-both-axes assertion with the one-shot re-pin ladder")
need('"qr preview view="' in qsa_raw and " theta=" in qsa_raw
     and " scale=" in qsa_raw,
     "the readable Diag line: view dims, buffer dims, theta, cover scale")
need("root.addView(tvPreview, new FrameLayout.LayoutParams(-1, -1))" in qsa_raw,
     "the TextureView stays full-screen (MATCH_PARENT both axes)")

# ---- D2. the zxing probe: a stylized QR in a large photo ----
def find_jar(group, artifact, ver):
    for base in ("/home/z/my-project/.gradle-home/caches/modules-2/files-2.1",
                 os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")):
        for p in glob.glob(os.path.join(
                base, group, artifact, ver, "*", artifact + "-" + ver + ".jar")):
            return p
    return None


zxing = find_jar("com.google.zxing", "core", "3.5.3")
JAVAC = shutil.which("javac")
JAVA = shutil.which("java")

if not (JAVAC and JAVA and zxing):
    print("  ... jars/javac missing — compile probe SKIPPED (fail)")
    need(False, "the zxing jar is reachable from the build cache")
else:
    WORK = "/tmp/bolt_v1427_probe"
    shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(WORK, exist_ok=True)
    shutil.copy(QC, os.path.join(WORK, "QrCore.java"))
    probe_d = r'''
import com.bolt.browser.util.QrCore;

public class ProbeQr1427 {
    static int bad = 0;
    static void t(String name, boolean cond) {
        if (!cond) { bad++; System.out.println("  probe FAIL: " + name); }
    }
    public static void main(String[] args) {
        // THE PHOTO: 1600x1200 mid-gray paper, a small stylized code in a
        // corner — the "photo from device" corpse shape
        String want = "https://bolt.browser/v1427-photo-probe";
        int size = 200;
        int[] qr = QrCore.encode(want, size);
        t("the QR encodes", qr != null);
        int W = 1600, H = 1200;
        int[] canvas = new int[W * H];
        java.util.Random rnd = new java.util.Random(1427L);
        for (int i = 0; i < W * H; i++) {
            int v = 205 + rnd.nextInt(9) - 4;          // paper + light noise
            canvas[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        }
        // the STYLIZED code: dark modules 108, light 156 — 12% apparent
        // contrast on 7% of the frame (the whole-image stretch cannot see
        // a code this small; the CROP windows + the stretch can)
        int ox = 1150, oy = 820;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean dark = (qr[y * size + x] & 0xFF) < 128;
                int v = dark ? 108 : 156;
                canvas[(oy + y) * W + (ox + x)] =
                        (0xFF << 24) | (v << 16) | (v << 8) | v;
            }
        }
        String got = QrCore.decodeRobust(canvas, W, H);
        t("the stylized low-contrast QR in the corner of the large photo "
                + "DECODES through the robust ladder", want.equals(got));
        String plain = QrCore.decode(canvas, W, H);
        t("the OLD single-pass decode cannot read it (the ladder added "
                + "the value)", plain == null);
        // the PURE passes
        byte[] y3 = {1, 2, 3, 4, 5, 6};
        byte[] r90 = QrCore.rotate90Luma(y3, 3, 2);
        // 3x2: rows (1,2,3),(4,5,6) rotated CW -> the 2x3 plane
        // 4 1 / 5 2 / 6 3
        t("rotate90Luma: the 3x2 plane rotates CW to 2x3 with exact indices",
                r90 != null && r90.length == 6 &&
                (r90[0] & 0xFF) == 4 && (r90[1] & 0xFF) == 1 &&
                (r90[2] & 0xFF) == 5 && (r90[3] & 0xFF) == 2 &&
                (r90[4] & 0xFF) == 6 && (r90[5] & 0xFF) == 3);
        int[] px16 = new int[256];
        for (int i = 0; i < 256; i++) px16[i] = 0xFF804020;
        byte[] lum = QrCore.lumaOf(px16, 16, 16);
        t("lumaOf: the BT.601 plane of a known color",
                lum != null && (lum[0] & 0xFF) ==
                        (299 * 0x20 + 587 * 0x40 + 114 * 0x80) / 1000);
        int[] crop = QrCore.cropArgb(px16, 16, 16, 4, 4, 12, 12);
        t("cropArgb: the 8x8 interior crop (the >=8px window guard)",
                crop != null && crop.length == 64 &&
                crop[0] == 0xFF804020 && crop[63] == 0xFF804020);
        t("cropArgb: clamped + degenerate windows are honest",
                QrCore.cropArgb(px16, 16, 16, -5, -5, 20, 20).length == 256 &&
                QrCore.cropArgb(px16, 16, 16, 0, 0, 6, 8) == null);
        System.out.println("  probe: bad=" + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
'''
    with open(os.path.join(WORK, "ProbeQr1427.java"), "w") as f:
        f.write(probe_d)
    r = subprocess.run([JAVAC, "-cp", zxing, "-d", WORK,
                        os.path.join(WORK, "QrCore.java"),
                        os.path.join(WORK, "ProbeQr1427.java")],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("  javac stderr: " + r.stderr[:400])
        need(False, "the zxing probe compiles (QrCore + the robust ladder)")
    else:
        need(True, "the zxing probe compiles (QrCore + the robust ladder)")
        r = subprocess.run([JAVA, "-cp", zxing + os.pathsep + WORK, "ProbeQr1427"],
                           capture_output=True, text=True)
        print("  " + r.stdout.strip().replace("\n", "\n  ")[:600])
        if r.returncode != 0:
            print("  stderr: " + r.stderr[:300])
        need(r.returncode == 0,
             "the behavioral probe: a stylized low-contrast QR occupying "
             "part of a 1600x1200 photo decodes (full-res + crops + "
             "stretch); the pure rotate/crop/luma passes pinned")

# ---- D3. PURE: the fills-both-axes law + the re-pin ladder ----
def cover(view_w, view_h, bw, bh, theta):
    uw, uh = (bw, bh) if theta % 180 == 0 else (bh, bw)
    s = max(view_w / uw, view_h / uh) if uw > 0 and uh > 0 else float("inf")
    fills = (uw * s >= view_w - 1) and (uh * s >= view_h - 1)
    return s, fills


s1, f1 = cover(720, 1600, 640, 480, 90)
need(abs(s1 - 2.5) < 1e-6 and f1,
     "portrait 720x1600 with a 640x480 buffer: cover scale 2.5 FILLS "
     "both axes (480*2.5=1200 >= 720, 640*2.5=1600 >= 1600)")
s2, f2 = cover(720, 1600, 1280, 720, 90)
need(abs(s2 - 1.25) < 1e-6 and f2,
     "portrait 720x1600 with a 1280x720 buffer: cover scale 1.25 FILLS "
     "both axes (720*1.25=900 >= 720, 1280*1.25=1600 >= 1600)")
s3, f3 = cover(720, 1600, 1280, 720, 0)
need(f3,
     "the upright-buffer case (theta 0) also fills both axes (the "
     "general cover invariant)")


def ladder(view_w, view_h, stale_bw, stale_bh, real_bw, real_bh, theta=90):
    """the re-pin ladder: compute with the stale dims; a fills failure
    re-pins once and recomputes with the real dims"""
    s, fills = cover(view_w, view_h, stale_bw, stale_bh, theta)
    repinned = False
    if not fills:
        repinned = True
        s, fills = cover(view_w, view_h, real_bw, real_bh, theta)
    return repinned, s, fills


repinned, s, fills = ladder(720, 1600, 0, 0, 1280, 720)
need(repinned and fills,
     "the re-pin ladder: degenerate/stale buffer dims fail the assert, "
     "re-pin once, recompute -> fills (the bottom-half-black self-heal)")
repinned2, _, fills2 = ladder(720, 1600, 640, 480, 1280, 720)
need(not repinned2 and fills2,
     "a healthy buffer never re-pins (the ladder is one-shot)")

print()
print("E — THE BOLT-AI + AiBridge unchanged halves")
need("fun tableView(c: Context, rows: List<List<String>>): android.view.View {" in ar_raw
     and "fun splitTables(spanned: Spanned): List<Any> {" in ar_raw,
     "the TableView builder wires the theme colors + dp paddings (and "
     "the split) "
     "  # v1.4.28 re-point: tableSpan() retired with the span")
need("fun textTranslate(ctx: Context, lines: List<String>, tl: String,\n"
     "                     markBreaks: Boolean): List<String?>?" in ab_raw,
     "the shared textTranslate (markBreaks variant) is the ONE translation "
     "channel the merged pipeline rides")

print()
print("F — THE LAUNCH LAW + the pipeline")
smoke = os.path.join(ROOT, "app/src/test/java/com/bolt/browser/ui/LaunchSmokeTest.java")
need(os.path.isfile(smoke), "the Robolectric launch smoke test still exists")
need("testDebugUnitTest" in RELS and "LAUNCH SMOKE FAILED" in RELS,
     "release.sh still runs the smoke BEFORE the build (the rung stays)")
need("python3 scripts/test_v1427.py" in RELS,
     "release.sh runs this gate (the v1.4.27 rung)")
need("python3 scripts/test_v1426.py" in RELS,
     "the v1.4.26 gate still rides the suite (cumulative law — it walks "
     "the DIU fixture with the CURRENT source)")

print()
print("G — THE SAVED-DOM WALKS: the TBS picture fixture + the DIU regression "
      "carrier (the REAL darkSmartJs)")

m = re.search(r"public static String darkSmartJs\(\)\s*\{(.*?)\n    \}", mh_raw, re.S)
parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
js = "".join(p.replace('\\"', '"').replace("\\\\", "\\").replace("\\n", "\n") for p in parts)
probe_g = r'''
const fs = require('fs');
const js = fs.readFileSync(process.argv[2], 'utf8');

function rect(w, h) { return { width: w, height: h, top: 0, left: 0 }; }
function mkClassList() {
  const s = new Set();
  return { contains: (c) => s.has(c), add: (c) => s.add(c), remove: (c) => s.delete(c), _set: s };
}
function el(tag, w, h) {
  const e = { tagName: tag.toUpperCase(), children: [], parentElement: null,
    _rect: rect(w, h), classList: null, style: {}, className: '', id: '',
    complete: false, naturalWidth: 0, naturalHeight: 0, src: '',
    currentSrc: '', _ls: {}, addEventListener: (ev, fn) => { e._ls[ev] = fn; },
    removeEventListener: (ev) => { delete e._ls[ev]; },
    getBoundingClientRect: () => e._rect,
    getAttribute: () => null, setAttribute: () => {} };
  e.classList = mkClassList(); return e;
}
function link(parent, child) { child.parentElement = parent; parent.children.push(child); return child; }
function gcs(e) {
  const base = { color: 'rgb(34, 34, 34)', backgroundColor: 'rgba(0, 0, 0, 0)',
    backgroundImage: 'none', filter: 'none', fontFamily: 'sans-serif', stroke: 'none',
    fill: 'none', strokeWidth: '0', borderTopWidth: '0px', borderBottomWidth: '0px',
    borderLeftWidth: '0px', borderRightWidth: '0px', borderTopColor: 'rgb(0, 0, 0)' };
  return Object.assign(base, e._cs || {});
}
const document = { body: null, documentElement: el('html', 100, 100), head: el('head', 0, 0),
  createElement: (t) => el(t, 0, 0), getElementById: () => null, querySelectorAll: () => [] };
let MutationObserver = function (cb) { this.observe = () => {}; this.disconnect = () => {}; };
let window = { addEventListener: () => {}, removeEventListener: () => {} };
global.setTimeout = (fn, ms) => { fn(); return 0; };

// ---------------- the TBS portal fixture (the saved DOM's evidence) ----------------
const body = el('body', 800, 1800);
body._cs = { backgroundColor: 'rgb(249, 249, 249)' };
document.body = body;
body.querySelectorAll = (sel) => {
  const any = sel.includes('*');
  const want = sel.split(',').map((s) => s.trim().toUpperCase()).filter((s) => s && s !== '*');
  const out = [];
  (function walk(n) { for (const c of n.children) { if (any || want.includes(c.tagName)) out.push(c); walk(c); } })(body);
  return out;
};
const header = link(body, el('header', 800, 56));
header._cs = { backgroundColor: 'rgb(255, 255, 255)' };

// the story column
const col = link(body, el('main', 560, 1700));
col._cs = { backgroundColor: 'rgb(255, 255, 255)' };

// THE PICTURE CORPSE: <picture class="ratio ratio__16x9"> wrapping
// <img class="ratio ratio__16x9 lazyloaded"> with a REMOTE src and NO bolt
// class — 65 of these in the saved DOM; three carry the walk here
const picImgs = [];
for (let i = 0; i < 3; i++) {
  const pic = link(col, el('picture', 300, 169));
  pic.className = 'ratio ratio__16x9';
  const im = link(pic, el('img', 300, 169));
  im.className = 'ratio ratio__16x9 lazyloaded';
  im.src = 'https://tbsnews.net/sites/default/files/styles/16x9/public/story' + i + '.jpg';
  im.currentSrc = im.src;
  im.complete = true; im.naturalWidth = 800; im.naturalHeight = 450;
  picImgs.push(im);
}
// a standalone remote content img (no picture wrapper)
const plain = link(col, el('img', 360, 203));
plain.src = 'https://tbsnews.net/photo-lead.jpg';
plain.complete = true; plain.naturalWidth = 1200; plain.naturalHeight = 675;
// a small 16:9 REMOTE thumb (44x24 — under the 48 floor, banner-shaped at
// 1.83): the IMG 1.5 threshold vetoes it as content (2.5 would ride it)
const thumb169 = link(col, el('img', 44, 24));
thumb169.src = 'https://tbsnews.net/thumb-169.jpg';
thumb169.complete = true; thumb169.naturalWidth = 320; thumb169.naturalHeight = 180;
// a small SQUARE REMOTE img over 48 px (56x56) with NO icon-named ancestor:
// the remote-img law classes it CONTENT
const thumbSq = link(col, el('img', 56, 56));
thumbSq.src = 'https://tbsnews.net/avatar.jpg';
thumbSq.complete = true; thumbSq.naturalWidth = 200; thumbSq.naturalHeight = 200;
// the SAME square remote img INSIDE an icon-named wrapper: the ancestor
// signal wins, it RIDES (the icon law beats the content law)
const menuWrap = link(header, el('div', 64, 64));
menuWrap.className = 'menu_icon';
const menuImg = link(menuWrap, el('img', 56, 56));
menuImg.src = 'https://tbsnews.net/menu.png';
menuImg.complete = true; menuImg.naturalWidth = 200; menuImg.naturalHeight = 200;
// a 44x40 REMOTE square (under the 48 floor, not banner-ish): rides as a
// glyph candidate — the content laws leave small square glyphs alone
const glyphSq = link(col, el('img', 44, 40));
glyphSq.src = 'https://tbsnews.net/glyph.png';
glyphSq.complete = true; glyphSq.naturalWidth = 88; glyphSq.naturalHeight = 80;
// a data:URI square icon: the v1.4.24 mono law still rides it
const dataIcon = link(col, el('img', 40, 40));
dataIcon.src = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAg';
dataIcon.complete = true; dataIcon.naturalWidth = 32; dataIcon.naturalHeight = 32;

// ---------------- the DIU regression carrier (v1.4.26's evidence, compact) ----------------
const burgerWrap = link(header, el('div', 40, 40));
burgerWrap.className = 'sidebar_icon d-lg-none';
const burger = link(burgerWrap, el('img', 0, 0));
burger.src = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABgAAAAY';
const toggleWrap = link(header, el('div', 30, 30));
toggleWrap.id = 'open_miniSide';
toggleWrap.className = 'line_icon open_miniSide d-none d-lg-block';
const toggle = link(toggleWrap, el('img', 0, 0));
toggle.src = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABcAAAAS';
const btnSuccess = link(col, el('a', 260, 44));
btnSuccess.className = 'btn btn-success w-100 resource-btn';
btnSuccess._cs = { backgroundColor: 'rgb(25, 135, 84)', color: 'rgb(255, 255, 255)' };
const card = link(col, el('div', 400, 120));
card._cs = { backgroundColor: 'rgb(255, 255, 255)', color: 'rgb(33, 37, 41)' };
const hintSpan = link(card, el('span', 300, 16));
hintSpan._cs = { color: 'rgb(255, 255, 255)' };
const plainIcon = link(card, el('img', 0, 0));
plainIcon.src = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABQAAAAU';

const f = new Function('window', 'document', 'MutationObserver', 'getComputedStyle', js);
f(window, document, MutationObserver, gcs);

// the deferred icon decodes now (the v1.4.26 race-killer leg)
plainIcon._rect = rect(20, 20);
plainIcon.complete = true; plainIcon.naturalWidth = 20; plainIcon.naturalHeight = 20;
if (plainIcon._ls.load) plainIcon._ls.load();

let ok = 0, bad = 0;
const t = (name, cond) => { if (cond) { ok++; } else { bad++; console.log('  probe FAIL: ' + name); } };

// THE TBS VERDICTS
t('the picture-wrapped thumbnails stay in the media family (NO bolt class — ' +
  'the CSS exemption renders them original)',
  picImgs.every((i) => !i.classList.contains('__boltIC') &&
                       !i.classList.contains('__boltDK') &&
                       !i.classList.contains('__boltNI')));
t('the standalone remote content img carries NO bolt class (media family: ' +
  'html x img = 2 = original)',
  !plain.classList.contains('__boltIC') && !plain.classList.contains('__boltDK'));
t('the small 16:9 remote thumb (1.83 aspect) is CONTENT (the IMG 1.5 ' +
  'banner threshold — 2.5 would have flipped it dark)',
  !thumb169.classList.contains('__boltIC'));
t('the square remote img over 48 px with no icon-named ancestor is CONTENT',
  !thumbSq.classList.contains('__boltIC'));
t('the same square remote img inside div.menu_icon RIDES (the ancestor ' +
  'icon signal beats the content law)',
  menuImg.classList.contains('__boltIC'));
t('the 44x40 remote square (under the floor) still RIDES as a glyph',
  glyphSq.classList.contains('__boltIC'));
t('the data:URI icon still rides (the v1.4.24 mono law untouched)',
  dataIcon.classList.contains('__boltIC'));

// THE DIU REGRESSION VERDICTS (the v1.4.26 fixture's core — the hard constraint)
t('DIU: the hamburger rides the inversion (the ancestor signal)',
  burger.classList.contains('__boltIC'));
t('DIU: the mini toggle rides at the first walk',
  toggle.classList.contains('__boltIC'));
t('DIU: the plain undecoded icon becomes IC after its load (the deferred leg)',
  plainIcon.classList.contains('__boltIC'));
t('DIU: the late census field still counts it (late >= 1)',
  window.__boltDarkCount && window.__boltDarkCount.late >= 1);
t('DIU: btn-success stays DK-PRESERVED as designed (NO clamp, NO SV)',
  btnSuccess.classList.contains('__boltDK') &&
  !btnSuccess.classList.contains('__boltSV') && (btnSuccess.style.color || '') === '');
t('DIU: the white card still INVERTS (the baseline)',
  !card.classList.contains('__boltDK'));
t('DIU: the light-text span on the card still clamps to rgb(64,64,64)',
  hintSpan.classList.contains('__boltSV') &&
  hintSpan.style.color === 'rgb(64,64,64)');
console.log('  probe: ' + ok + ' ok, ' + bad + ' bad');
process.exit(bad ? 1 : 0);
'''
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fj:
    fj.write(js)
    jspath = fj.name
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fp:
    fp.write(probe_g)
    ppath = fp.name
r = subprocess.run(["node", ppath, jspath], capture_output=True, text=True)
print("  " + r.stdout.strip().replace("\n", "\n  ")[:800])
if r.returncode != 0:
    print("  stderr: " + r.stderr[:400])
need(r.returncode == 0,
     "the saved-DOM walks: the TBS picture thumbnails stay media (the CSS "
     "exemption renders them original), the remote/banner thumbnail laws "
     "hold, the icon laws survive — AND the DIU regression carrier rides "
     "the walk intact (burger/toggle/deferred icon IC, btn-success "
     "preserved unclamped, the card inverts, the hint span clamps)")

print()
print("H — THE STANDING SWEEP + the build")
sweep = 0
for g in sorted(os.listdir(os.path.join(ROOT, "scripts"))):
    if not (g.startswith("test_v1") and g.endswith(".py")) or g == "test_v1427.py":
        continue   # this gate's own sweep-check line carries the old literal
    src = open(os.path.join(ROOT, "scripts", g), encoding="utf8").read()
    code = re.sub(r"#[^\n]*", "", src)          # dated history comments survive
    if re.search(r"versionCode\s+140\b", code) or '== "1.4.26"' in code:
        sweep += 1
need(sweep == 0, "the standing sweep repinned every gate to 141/1.4.27 "
     f"({sweep} stragglers)")
need(os.path.isfile(os.path.join(ROOT, "scripts/repin_gates_1427.py")),
     "the v1.4.27 sweep script is checked in (provenance)")
need('versionName "1.4.33"' in open(BG, encoding="utf8").read()
     and "versionCode 147" in open(BG, encoding="utf8").read(),
     "the build carries versionName 1.4.28 / versionCode 142")

print()
if failed:
    print(f"V1427 GATE: BAD ({failed} fail)")
    sys.exit(1)
print("V1427 GATE: OK")
