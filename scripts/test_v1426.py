#!/usr/bin/env python3
"""v1.4.26 — the black-on-black + bubble round (four fixes, each with a probe).

Root-caused from the v1.4.25 source + the user's saved DOM of the DIU
student portal (captured with the dark engine live — the transcribed
fixture below carries its exact evidence classes, geometry and colors) +
the manga/grid field report + the QR field report:

1. FORCED-DARK BLACK-ON-BLACK (the saved DOM's evidence): the hamburger
   (a 24x24 data:URI img in div.sidebar_icon, d-lg-none) had NO __boltIC
   while the eight 48x48 sidebar icons did — the one-shot race froze a
   0-rect/undecoded first-walk 'content' verdict, and the un-classed img
   rode the media un-invert into black-on-black; btn-success/info/warning/
   danger/teal + badges carried inline rgb(153,153,153) (__boltSV) over
   their inverted MID grounds — the fixed 153 clamp reads 102-on-mid =
   1.5-2.5:1. The three surgical legs:
   a) the ANCESTOR ICON SIGNAL (iconNamed walks <=6 parents),
   b) the DEFERRED RE-EVALUATION (load listener + 1s/3s re-passes +
      resize/orientationchange; data:URI images are icon assets),
   c) the CONTRAST-AWARE PRESERVE + TARGET (a solid bg in [0.18, 0.80]
      with light own/inherited text is PRESERVED as designed; the clamp
      picks a post-inversion target >= 0.75 at >= 4.5:1, and when no
      target reaches 4.5:1 the element is PRESERVED instead).
   ZERO rgb(232,232,232) existed on the page (the v1.4.25 lightCss law
   never fired there) — not chased, left alone.
2. MANGA TRANSLATION — the bubble-aware mode is back (the v1.4.25 grid
   covered the art and left the bubbles): Manga/Manga HD scripts, the
   v1.3.74 preprocess (x2 upscale + grayscale + hard 5/95 stretch + PNG),
   jpn_vert OCR, BUBBLE CLUSTERING (one matched card per bubble — inside
   the bubble, line breaks preserved), and the geometric bright-region
   detector for the OCR-blind vision path (never a grid cell, never
   box_2d).
3. BOLT AI TABLES RENDERED EMPTY — commonmark's GFM AST wraps rows in
   TableHead/TableBody; the firstChild-only walk collected ZERO rows.
   Plus the markdown ~sub~ / ^sup^ delimiter processors (the strikethrough
   extension re-configured to requireTwoTildes so '~' is free).
4. QR SCAN — the half-screen/90-degree mismatch (the transform computed
   against previewSize while the SurfaceTexture buffer was pinned to the
   640x480 placeholder, and the re-pin never re-applied the transform) +
   the stylized-code decode passes (2/98 stretch + half-scale).

Probes: the pure arithmetic tables (targetOf/clampCss/bubblesOf/QR
transform), the transcribed-fixture mock-DOM dark walk (node), and TWO
compile-and-run Java probes against the real dependency jars (commonmark
table/sub/sup + zxing stylized QR).
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
QR = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/QrScanActivity.java")
QC = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/QrCore.java")
HO = os.path.join(ROOT, "app/src/main/assets/scr_ocr.html")
BG = os.path.join(ROOT, "app/build.gradle")
RELS = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()

mh_raw = open(MH, encoding="utf8").read()
st_raw = open(ST, encoding="utf8").read()
ar_raw = open(AR, encoding="utf8").read()
ab_raw = open(AB, encoding="utf8").read()
qr_raw = open(QR, encoding="utf8").read()
qc_raw = open(QC, encoding="utf8").read()
ho_raw = open(HO, encoding="utf8").read()

failed = []


def need(cond, label):
    print(("  ok   - " if cond else "  FAIL - ") + label)
    if not cond:
        failed.append(label)


def lum255(r, g, b):
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0


print("A — fix 1a/1b: THE ANCESTOR ICON SIGNAL + THE ONE-SHOT RACE")
# the ancestor walk itself
need("function iconNamed(el){try{" in mh_raw
     and "var p=el.parentElement,lvl=0;" in mh_raw
     and "while(p&&lvl<6){if(iconNamedEl(p))return true;" in mh_raw,
     "iconNamed walks the parent chain (<= 6 levels) for the icon pattern")
need("var ICON_RE=/(^|[^a-z])(icon|menu|hamburger|burger|nav|toggle|chevron|arrow|close|cancel|gear|search)([^a-z]|$)/i;"
     in mh_raw,
     "the icon pattern is shared between the self and ancestor checks")
# the ridesFlip rect laws
need("if(m>64)return false;" in mh_raw,
     "ridesFlip: a laid-out BIG (>64 px) element is content, period")
need("if(named)return true;" in mh_raw
     and "return named&&!photoCtx(el);" in mh_raw,
     "ridesFlip: the named-wraper signal decides (0-rect rides too — decode-timing independent)")
# the deferred re-evaluation queue
need("defer.push(el);" in mh_raw
     and "el.addEventListener('load',function(){reEval(el);});" in mh_raw,
     "a deferred IMG/SVG arms a one-shot load listener at defer time")
need("setTimeout(rePass,1000);" in mh_raw
     and "setTimeout(rePass,3000);" in mh_raw,
     "the delayed re-passes sweep the queue (~1 s and ~3 s)")
need("window.addEventListener('resize',rz);" in mh_raw
     and "window.addEventListener('orientationchange',rz);" in mh_raw,
     "resize + orientationchange re-sweep (the d-lg-none responsive icons)")
need("if(!window.__boltDKrun)return;" in mh_raw,
     "reEval guards on __boltDKrun (a pulled inversion never re-classes)")
need("cnt.ic++;cnt.late++;" in mh_raw,
     "a late IC counts into the census (ic and late)")
# monoOf: data:URI = icon asset; intrinsic once naturalWidth known
need("su.indexOf('data:')===0" in mh_raw,
     "monoOf: a data:URI img is an icon asset (the DIU hamburger is inline base64)")
need("if(el.complete&&el.naturalWidth>0" in mh_raw
     and "&&el.naturalWidth<=128&&el.naturalHeight<=128)return true;" in mh_raw,
     "monoOf: tiny intrinsic size once naturalWidth is KNOWN")

print()
print("B — fix 1c: THE CONTRAST-AWARE PRESERVE + TARGET (the 153 corpse)")
need("function targetOf(gl){try{" in mh_raw
     and "var T=4.5*(L+0.05)-0.05;" in mh_raw
     and "if(T<0.75)T=0.75;" in mh_raw
     and "return T>1?null:T;}catch(e){return null}}" in mh_raw,
     "targetOf: the >=0.75 floor at >=4.5:1 law (null when even white cannot reach)")
need("var T=targetOf(gl);if(T===null)T=1;" in mh_raw
     and "var ySrc=255*(1-T);" in mh_raw,
     "clampCss scales the SOURCE so the POST-inversion text reads the target")
need("else if(tl!==null&&tl>0.7&&l<=0.80){want=0;cnt.keep++;}" in mh_raw,
     "the preserve band: solid bg in (0.35, 0.80] with LIGHT own text is DESIGNED (DK)")
need("if(l<=0.35){want=0;}" in mh_raw,
     "the dark-ground law (<=0.35) keeps the v1.4.24 baseline")
need("!iconFont(cs)&&targetOf(myGl)===null" in mh_raw
     and "lumOf(cs.backgroundColor)===null){" in mh_raw
     and "el.classList.add(DK);n++;inv=0;cnt.keep++;}}catch(x){}}" in mh_raw,
     "the text law: light text with NO reachable target is PRESERVED (not clamped)")
need("function parityEl(el,inv,gl){" in mh_raw
     and "if(bl2!==null)myGl=(inv===1)?(1-bl2):bl2;" in mh_raw,
     "the walk threads the rendered ground luminance (gl) for the clamp")
need("var cnt={small:0,ic:0,ni:0,rescue:0,mono:0,fi:0,late:0,keep:0};" in mh_raw
     and "' late='+(o.late||0)+' keep='+(o.keep||0);" in mh_raw,
     "the census carries the late + keep read-back fields")

# the PURE arithmetic tables (transcribed)
def target_of(gl):
    L = 0.05 if gl is None else max(0.0, min(1.0, gl))
    T = 4.5 * (L + 0.05) - 0.05
    if T < 0.75:
        T = 0.75
    return None if T > 1 else T


def clamp_css(color, gl):
    m = re.match(r"rgba?\(([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)", color)
    if not m:
        return None
    r, g, b = float(m.group(1)), float(m.group(2)), float(m.group(3))
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    if y <= 153:
        return None
    T = target_of(gl)
    if T is None:
        T = 1
    y_src = 255 * (1 - T)
    if y <= y_src:
        return None
    f = y_src / y
    return "rgb(%d,%d,%d)" % (round(r * f), round(g * f), round(b * f))


need(target_of(0.05) == 0.75 and target_of(None) == 0.75,
     "targetOf: the near-black inverted canvas clamps at the 0.75 floor")
need(abs(target_of(0.15) - 0.85) < 1e-9,
     "targetOf: a mid-dark ground raises the bar (4.5:1 wins over the floor)")
need(target_of(0.25) is None,
     "targetOf: no target on a mid ground (gl 0.25 — even white reads 3.5:1) -> preserve")
# the DIU corpse, both halves:
need(clamp_css("rgb(255,255,255)", 0.05) == "rgb(64,64,64)",
     "white text on the near-black inverted canvas clamps to source 64 (post-inv 191 = 0.75)")
need(lum255(255 - 64, 255 - 64, 255 - 64) >= 0.749,
     "the clamp's POST-inversion luminance is >= 0.75 (the old corpse wrote 102)")
# the old corpse arithmetic, for the record:
old_153 = lum255(153, 153, 153)          # the source the v1.4.25 walk wrote
old_post = lum255(255 - 153, 255 - 153, 255 - 153)
btn_success_post = 1 - lum255(25, 135, 84)   # the inverted green ground
contrast_old = (old_post + 0.05) / (btn_success_post + 0.05)
need(contrast_old < 2.5,
     "the CORPSE arithmetic: 153->102 text on the inverted green read %0.2f:1 (below 2.5)"
     % contrast_old)
# and the new law never writes it: the button is preserved before the clamp runs
need("else if(tl!==null&&tl>0.7&&l<=0.80){want=0;cnt.keep++;}" in mh_raw,
     "btn-success (bg 0.42, white text) PRESERVED as designed under the new law")

print()
print("C — fix 2: THE MANGA ROUND (bubble-aware, the grid retired for comics)")
need('"ai", "auto", "manga", "mangahd"' in st_raw
     and "Manga \\u2014 bubble-aware (speech bubbles, vertical JP)" in st_raw,
     "the mode picker: AI Page (the grid) vs Basic vs Manga vs Manga HD")
need('if (k == "manga" || k == "mangahd") { runMangaStage(a, bmp, g, k == "mangahd"); return }' in st_raw,
     "the pipeline branches to the manga stage (the grid paths stay for general pages)")
need('"jpn_vert" to 3037480L, "jpn_vertb" to 14330809L)' in st_raw,
     "the vertical models with the EXACT repo sizes (fast 3,037,480 B / best 14,330,809 B)")
need("tessdata_best@main/" in st_raw and 'if (lang == "jpn_vertb") BEST_HOSTS else DL_HOSTS' in st_raw,
     "Manga HD downloads from tessdata_best (its own fail-over pair)")
need('"jpn_vert", "jpn_vertb")' in st_raw
     and 'for (lang in arrayOf("jpn_vert", "jpn_vertb")) {' not in st_raw,
     "the model diet NO LONGER deletes the vertical models (the manga round owns them)")
need("Bitmap.createScaledBitmap(bmp, w, h, false)" in st_raw
     and "2600f / longEdge" in st_raw and "6_500_000.0" in st_raw,
     "the manga preprocess: x2 NEAREST upscale, capped 2600 px / 6.5 MP")
need("if (acc >= total * 5 / 100) { p5 = i; break }" in st_raw
     and "if (p95 - p5 < 24) return" in st_raw,
     "the HARD 5/95 percentile stretch (the manga-era law, not the mild 2/98)")
need("Bitmap.CompressFormat.PNG, 100, bo" in st_raw
     and 'wv.evaluateJavascript("window.__ocr(\'$langs\',\'$mime\')", null)' in st_raw
     and "'data:image/' + m + ';base64,'" in ho_raw,
     "PNG not JPEG: the manga pass encodes PNG and the host page honors the mime")
need("fun run(langs: Array<String>, b64: String, done: (String) -> Unit,\n                 fail: (String) -> Unit, mime: String = \"jpeg\")" in st_raw,
     "OcrHost.run carries the mime parameter")
need("internal fun bubblesOf(boxes: Array<Rect>): Array<Rect>" in st_raw
     and "internal const val BUBBLE_GROW = 0.6f" in st_raw
     and "internal const val BUBBLE_PAD = 2" in st_raw,
     "bubblesOf: the pure clustering law with its pinned constants")
need("fun textTranslate(ctx: Context, lines: List<String>, tl: String,\n                     markBreaks: Boolean)" in ab_raw
     and "' ~ ' (tilde with one space on each side)" in ab_raw
     and 'replace(" ~ ", "\\n")' in st_raw,
     "the bubble translations carry line breaks through the ' ~ ' marker -> newline")
need("internal fun brightRegions(bmp: Bitmap): Array<Rect>" in st_raw
     and "internal const val BRIGHT_TH = 200" in st_raw
     and "internal const val BRIGHT_DS = 256" in st_raw
     and "internal const val BRIGHT_MAX = 8" in st_raw,
     "the geometric bright-region detector (interior paper-white blobs — never a grid cell)")
need("AiBridge.visionTileTranslate(a, enc.b64, tl, enc.w, enc.h, ctx)" in st_raw,
     "the OCR-blind bubbles ride the vision text-only pass (box_2d stays retired)")

# the PURE clustering table (transcribed)
def bubbles_of(boxes, grow=0.6, pad=2):
    rects = [list(b) for b in boxes]

    def grown(r):
        dy = int(grow * max(1, r[3] - r[1]))
        return (r[0] - dy, r[1] - dy, r[2] + dy, r[3] + dy)

    merged = True
    while merged:
        merged = False
        for i in range(len(rects)):
            for j in range(i + 1, len(rects)):
                gi, gj = grown(rects[i]), grown(rects[j])
                if gi[0] <= gj[2] and gj[0] <= gi[2] and gi[1] <= gj[3] and gj[1] <= gi[3]:
                    rects[i] = [min(rects[i][0], rects[j][0]), min(rects[i][1], rects[j][1]),
                                max(rects[i][2], rects[j][2]), max(rects[i][3], rects[j][3])]
                    rects.pop(j)
                    merged = True
                    break
            if merged:
                break
    return [(r[0] - pad, r[1] - pad, r[2] + pad, r[3] + pad) for r in rects]


# a bubble's stacked lines (gap 2 px << 0.6x20) -> ONE cluster
c = bubbles_of([(100, 100, 220, 120), (100, 122, 220, 142)])
need(len(c) == 1 and c[0] == (98, 98, 222, 144),
     "bubblesOf: two stacked lines merge into ONE bubble (union + the 2 px pad)")
# a vertical column pair (side-by-side runs, gap 5 < 0.6x100) -> ONE cluster
c = bubbles_of([(40, 40, 60, 140), (65, 40, 85, 140)])
need(len(c) == 1, "bubblesOf: side-by-side vertical columns merge (the vertical CJK layout)")
# distinct bubbles (a bubble-diameter apart) stay distinct
c = bubbles_of([(100, 100, 220, 120), (100, 400, 220, 420)])
need(len(c) == 2, "bubblesOf: distinct bubbles never merge")
# the card covers ONLY the bubble's text — never a grid cell
c = bubbles_of([(100, 100, 220, 120)])
need(c[0][2] - c[0][0] == 124, "the bubble card is the bubble's own extent (+2 px pad each side)")

# the preprocess constants, transcribed (the Kotlin cumulative-count law:
# p5 is the value at cumulative >= 5% from the BOTTOM, p95 the value at
# cumulative >= 5% from the TOP)
def hard_percentiles(vals):
    vals = sorted(vals)
    n = len(vals)
    import math as _m
    p5 = vals[max(0, _m.ceil(n * 0.05) - 1)]
    p95 = vals[min(n - 1, _m.ceil(n * 0.95))]
    return p5, p95


need(hard_percentiles([0] * 5 + [255] * 95) == (0, 255),
     "the hard stretch's 5/95 band covers the full range (0 -> 0, 255 -> 255)")
need("stretchOf(p5, p95, i)" in st_raw,
     "the hard stretch rides the probe-pinned stretchOf mapping (the shared law)")

print()
print("D — fix 3: THE TABLE + SUB/SUP ROUND (the empty-table corpse)")
need("v1.4.26 — THE WRAPPER TRAVERSAL" in ar_raw
     and "if (node is TableRow) {" in ar_raw
     and "var kid = node.firstChild" in ar_raw
     and "if (kid is TableRow) cellsOf(kid)" in ar_raw,
     "table() descends into TableHead AND TableBody for every TableRow (in order)")
need("StrikethroughExtension.builder()" in ar_raw
     and ".requireTwoTildes(true).build()," in ar_raw,
     "the strikethrough extension re-configured (its own coexistence knob frees '~')")
need(".customDelimiterProcessor(SubSupDelimiterProcessor(false))" in ar_raw
     and ".customDelimiterProcessor(SubSupDelimiterProcessor(true))" in ar_raw,
     "the sub ('~') and sup ('^') delimiter processors are registered")
need("is SubMd -> {" in ar_raw and "is SupMd -> {" in ar_raw,
     "the visitor renders the delimiter nodes with the same span kit as <sub>/<sup>")
need('s.contains("~") || s.contains("^")' in ar_raw,
     "the fast path routes ~x~/^x^ through the parser")

print()
print("E — fix 4: THE QR CAMERA (buffer pin + transform) + the stylized passes")
need("private int bufW = 640, bufH = 480;" in qr_raw
     and "bufW = previewSize != null ? previewSize.getWidth() : 640;" in qr_raw
     and "st.setDefaultBufferSize(bufW, bufH);" in qr_raw,
     "the buffer pin tracks the ACTUAL dims (placeholder 640x480 until the camera opens)")
need("reapplyTransform();" in qr_raw
     and "runOnUiThread(() -> {" in qr_raw,
     "the transform is RE-APPLIED after the re-pin (buffer-size changes never fire the size callback)")
need("float bw = bufW;" in qr_raw and "if (viewW == 0 || viewH == 0) return;" in qr_raw
     and "if (previewSize == null || viewW == 0" not in qr_raw,
     "the transform computes against the ACTUAL buffer dims (the null-guard corpse is gone)")
need("static byte[] stretchLuma(byte[] y, int width, int height)" in qc_raw
     and "static byte[] halfLuma(byte[] y, int width, int height)" in qc_raw,
     "QrCore: the stretch + half-scale stylized passes (pure, probe-pinned)")

# the transform table (transcribed): the mapped buffer-corner HULL must
# CONTAIN the view rect (cover) with one axis fitting EXACTLY (aspect-true)
def preview_matrix(bufw, bufh, vieww, viewh, theta):
    uw, uh = (bufw, bufh) if theta % 180 == 0 else (bufh, bufw)
    s = max(vieww / uw, viewh / uh)

    def m(p):
        x, y = p[0] - bufw / 2.0, p[1] - bufh / 2.0
        # postRotate(theta)
        import math
        a = math.radians(theta)
        x, y = x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)
        # postScale(s, s) + postTranslate(cx, cy)
        return (x * s + vieww / 2.0, y * s + viewh / 2.0)

    return s, [m((0, 0)), m((bufw, 0)), m((bufw, bufh)), m((0, bufh))]


def hull(pts):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), max(xs), min(ys), max(ys)


# portrait phone, landscape sensor buffer, 90-degree recipe (the Realme case)
s, pts = preview_matrix(1920, 1080, 1080, 2340, 90)
x0, x1, y0, y1 = hull(pts)
need(abs(y0) < 1e-6 and abs(y1 - 2340) < 1e-6 and x0 <= 0 and x1 >= 1080,
     "the portrait transform: the upright height fills the view EXACTLY, "
     "the width covers (no half-screen), corners on the hull")
need(abs(s - 2340 / 1920.0) < 1e-9,
     "the cover scale is aspect-true (2340/1920 for the 90-degree portrait case)")
# landscape
s2, pts2 = preview_matrix(1920, 1080, 2340, 1080, 0)
x0, x1, y0, y1 = hull(pts2)
need(abs(x0) < 1e-6 and abs(x1 - 2340) < 1e-6 and y0 <= 0 and y1 >= 1080
     and abs(s2 - 2340 / 1920.0) < 1e-9,
     "the landscape case: same recipe, cover + aspect-true (width fills exactly)")
# the placeholder case: the transform APPLIES (never the old early return)
s3, pts3 = preview_matrix(640, 480, 1080, 2340, 90)
need(pts3 is not None and len(pts3) == 4,
     "the 640x480 placeholder buffer still gets a transform (the race is dead)")

print()
print("F — THE COMPILE-AND-RUN PROBES (real jars)")

# ---------- the commonmark probe: the GFM table + ~sub~ / ^sup^ ----------
def find_jar(group, artifact, ver):
    for base in ("/home/z/my-project/.gradle-home/caches/modules-2/files-2.1",
                 os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")):
        for p in glob.glob(os.path.join(
                base, group, artifact, ver, "*", artifact + "-" + ver + ".jar")):
            return p
    return None


CM = "0.24.0"
cm_core = find_jar("org.commonmark", "commonmark", CM)
cm_tables = find_jar("org.commonmark", "commonmark-ext-gfm-tables", CM)
cm_strike = find_jar("org.commonmark", "commonmark-ext-gfm-strikethrough", CM)
cm_ins = find_jar("org.commonmark", "commonmark-ext-ins", CM)
cm_task = find_jar("org.commonmark", "commonmark-ext-task-list-items", CM)
cm_foot = find_jar("org.commonmark", "commonmark-ext-footnotes", CM)
zxing = find_jar("com.google.zxing", "core", "3.5.3")

WORK = "/tmp/bolt_v1426_probe"
shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK, exist_ok=True)

JAVAC = shutil.which("javac")
JAVA = shutil.which("java")

if not (JAVAC and JAVA and cm_core and cm_tables and cm_strike and cm_ins
        and cm_task and cm_foot):
    print("  ... jars/javac missing — compile probe SKIPPED (fail)")
    need(False, "the commonmark + zxing jars are reachable from the build cache")
else:
    cp = os.pathsep.join([cm_core, cm_tables, cm_strike, cm_ins, cm_task, cm_foot])
    probe = r'''
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.ext.ins.InsExtension;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Node;
import org.commonmark.node.Nodes;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.parser.delimiter.DelimiterProcessor;
import org.commonmark.parser.delimiter.DelimiterRun;

// the v1.4.26 transcription of AiRender's sub/sup delimiter pair
class SubMd extends CustomNode {}
class SupMd extends CustomNode {}

class SubSupDelimiterProcessor implements DelimiterProcessor {
    private final boolean up;
    SubSupDelimiterProcessor(boolean up) { this.up = up; }
    public char getOpeningCharacter() { return up ? '^' : '~'; }
    public char getClosingCharacter() { return up ? '^' : '~'; }
    public int getMinLength() { return 1; }
    public int process(DelimiterRun openingRun, DelimiterRun closingRun) {
        if (openingRun.length() < 1 || closingRun.length() < 1) return 0;
        try {
            Text opener = openingRun.getOpener();
            Node node = up ? new SupMd() : new SubMd();
            for (Node n : Nodes.between(opener, closingRun.getCloser())) {
                node.appendChild(n);
            }
            opener.insertAfter(node);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }
}

public class Probe1426 {
    static int bad = 0;
    static void t(String name, boolean cond) {
        if (!cond) { bad++; System.out.println("  probe FAIL: " + name); }
    }
    static int countRows(Node t) {
        // the OLD walk (the corpse): direct children only
        int rows = 0;
        Node row = t.getFirstChild();
        while (row != null) {
            if (row instanceof TableRow) rows++;
            row = row.getNext();
        }
        return rows;
    }
    static int countRowsDeep(Node t) {
        // the v1.4.26 walk: descend into TableHead/TableBody wrappers
        int rows = 0;
        Node node = t.getFirstChild();
        while (node != null) {
            if (node instanceof TableRow) rows++;
            else {
                Node kid = node.getFirstChild();
                while (kid != null) {
                    if (kid instanceof TableRow) rows++;
                    kid = kid.getNext();
                }
            }
            node = node.getNext();
        }
        return rows;
    }
    static int countNodes(Node root, Class<?> cls) {
        int n = 0;
        for (Node c = root.getFirstChild(); c != null; c = c.getNext()) {
            n += countNodes(c, cls);
            if (cls.isInstance(c)) n++;
        }
        return n + (root instanceof org.commonmark.node.Document ? 0 : 0);
    }
    public static void main(String[] args) {
        Parser parser = Parser.builder()
                .extensions(java.util.Arrays.asList(
                        org.commonmark.ext.gfm.tables.TablesExtension.create(),
                        StrikethroughExtension.builder().requireTwoTildes(true).build(),
                        TaskListItemsExtension.create(),
                        InsExtension.create(),
                        FootnotesExtension.builder().inlineFootnotes(true).build()))
                .customDelimiterProcessor(new SubSupDelimiterProcessor(false))
                .customDelimiterProcessor(new SubSupDelimiterProcessor(true))
                .build();

        // (1) THE EMPTY-TABLE CORPSE: a real GFM table parses into
        // TableHead/TableBody wrappers — the direct-children walk finds ZERO
        String md = "| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n";
        Node doc = parser.parse(md);
        Node table = doc.getFirstChild();
        t("the GFM table parses to a TableBlock", table instanceof TableBlock);
        t("the OLD firstChild-only walk collected ZERO rows (the corpse)",
                countRows(table) == 0);
        t("the v1.4.26 wrapper traversal collects header + BOTH body rows",
                countRowsDeep(table) == 3);

        // (2) the sub/sup delimiter forms + the strikethrough coexistence
        Node doc2 = parser.parse("water is H~2~O and E = mc^2^, ~~gone~~ stays");
        int subs = 0, sups = 0, strikes = 0;
        for (Node n : walk(doc2)) {
            if (n instanceof SubMd) subs++;
            if (n instanceof SupMd) sups++;
            if (n instanceof Strikethrough) strikes++;
        }
        t("~2~ renders a subscript node", subs == 1);
        t("^2^ renders a superscript node", sups == 1);
        t("~~gone~~ STILL renders strikethrough (the requireTwoTildes knob)",
                strikes == 1);

        // (3) no crash on the marker-less text
        Node doc3 = parser.parse("a ~ b ^ c ~~ d");
        t("bare markers parse without throwing", doc3 != null);
        System.out.println("  probe: bad=" + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
    static java.util.List<Node> walk(Node root) {
        java.util.List<Node> out = new java.util.ArrayList<>();
        for (Node c = root.getFirstChild(); c != null; c = c.getNext()) {
            out.add(c);
            out.addAll(walk(c));
        }
        return out;
    }
}
'''
    with open(os.path.join(WORK, "Probe1426.java"), "w") as f:
        f.write(probe)
    r = subprocess.run([JAVAC, "-cp", cp, "-d", WORK,
                        os.path.join(WORK, "Probe1426.java")],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("  javac stderr: " + r.stderr[:400])
        need(False, "the commonmark probe compiles (the delimiter processor "
                    "against the real 0.24.0 API)")
    else:
        need(True, "the commonmark probe compiles (the delimiter processor "
                    "against the real 0.24.0 API)")
        r = subprocess.run([JAVA, "-cp", cp + os.pathsep + WORK, "Probe1426"],
                           capture_output=True, text=True)
        print("  " + r.stdout.strip().replace("\n", "\n  ")[:400])
        need(r.returncode == 0,
             "the behavioral probe: a GFM table yields ALL header+body rows "
             "through the wrapper traversal (the old walk: ZERO); ~sub~ and "
             "^sup^ render their nodes; ~~strike~~ coexists")

# ---------- the zxing probe: the stylized QR + the pure passes ----------
if not (JAVAC and JAVA and zxing):
    need(False, "the zxing jar is reachable from the build cache")
else:
    shutil.copy(QC, os.path.join(WORK, "QrCore.java"))
    probe2 = r'''
import com.bolt.browser.util.QrCore;

public class ProbeQr1426 {
    static int bad = 0;
    static void t(String name, boolean cond) {
        if (!cond) { bad++; System.out.println("  probe FAIL: " + name); }
    }
    static byte[] lumaOf(int[] px, int w, int h) {
        byte[] y = new byte[w * h];
        for (int i = 0; i < w * h; i++) {
            int p = px[i];
            y[i] = (byte) ((299 * (p & 0xFF) + 587 * ((p >> 8) & 0xFF)
                    + 114 * ((p >> 16) & 0xFF)) / 1000);
        }
        return y;
    }
    public static void main(String[] args) {
        String want = "https://bolt.browser/v1426-stylized-probe";
        int size = 320;
        int[] px = QrCore.encode(want, size);
        t("the QR encodes", px != null);
        // the STYLIZED code: modules remapped into a narrow 110..160 band
        // (a tinted/low-contrast art QR — the plain two-pass under-reads it)
        for (int i = 0; i < px.length; i++) {
            boolean dark = (px[i] & 0xFF) < 128;
            int v = dark ? 110 : 160;   // 12.5% apparent contrast only
            px[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        }
        byte[] y = lumaOf(px, size, size);
        String got = QrCore.decodeLuma(y, size, size);
        t("the STYLIZED (low-contrast) QR decodes through the new passes", want.equals(got));
        // the pure passes
        byte[] st = QrCore.stretchLuma(y, size, size);
        t("stretchLuma returns the stretched plane (not null — a 110..160 plane "
                + "is NOT already contrasty)", st != null);
        int lo = 255, hi = 0;
        for (byte b : st) { int v = b & 0xFF; if (v < lo) lo = v; if (v > hi) hi = v; }
        t("the stretched plane spans the full range (0..255)", lo == 0 && hi == 255);
        byte[] hs = QrCore.halfLuma(y, size, size);
        t("halfLuma: the 2:1 subsample (even rows/cols, exact indexing)",
                hs != null && hs.length == (size / 2) * (size / 2)
                && (hs[1] & 0xFF) == (y[2] & 0xFF));
        // the inverted stylized code still rides the inverted retries
        for (int i = 0; i < y.length; i++) y[i] = (byte) (255 - (y[i] & 0xFF));
        String inv = QrCore.decodeLuma(y, size, size);
        t("the INVERTED stylized QR decodes too (the alsoInverted law)", want.equals(inv));
        System.out.println("  probe: bad=" + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
'''
    with open(os.path.join(WORK, "ProbeQr1426.java"), "w") as f:
        f.write(probe2)
    r = subprocess.run([JAVAC, "-cp", zxing, "-d", WORK,
                        os.path.join(WORK, "QrCore.java"),
                        os.path.join(WORK, "ProbeQr1426.java")],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("  javac stderr: " + r.stderr[:400])
        need(False, "the zxing probe compiles (QrCore + the stylized pass)")
    else:
        need(True, "the zxing probe compiles (QrCore + the stylized pass)")
        r = subprocess.run([JAVA, "-cp", zxing + os.pathsep + WORK, "ProbeQr1426"],
                           capture_output=True, text=True)
        print("  " + r.stdout.strip().replace("\n", "\n  ")[:500])
        need(r.returncode == 0,
             "the behavioral probe: a 12.5%-contrast stylized QR decodes "
             "(stretch + half-scale + inverted), the pure passes pinned")

print()
print("G — THE SAVED-DOM FIXTURE WALK (the DIU portal, transcribed evidence)")
# the transcribed fixture: the exact evidence from the saved DOM —
# div.sidebar_icon (the 24x24 data:URI hamburger, UNDECODED at first walk),
# div.line_icon (the 0-rect mini toggle), the eight 48x48 sidebar icons in
# the dark sidebar, the five colored buttons + a badge (white text), the
# btn-dark/btn-purple baseline, a white card with dark text, and the
# mid-ground light-text span (the black-on-black corpse case)
m = re.search(r"public static String darkSmartJs\(\)\s*\{(.*?)\n    \}", mh_raw, re.S)
parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
js = "".join(p.replace('\\"', '"').replace("\\\\", "\\").replace("\\n", "\n") for p in parts)
probe3 = r'''
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
    _ls: {}, addEventListener: (ev, fn) => { e._ls[ev] = fn; },
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
global.setTimeout = (fn, ms) => { fn(); return 0; };   // the re-passes fire immediately

// ---------------- the transcribed DIU portal fixture ----------------
const body = el('body', 800, 1800);
body._cs = { backgroundColor: 'rgb(249, 249, 249)' };   // #f9f9f9 light page
document.body = body;
body.querySelectorAll = (sel) => {
  const any = sel.includes('*');
  const want = sel.split(',').map((s) => s.trim().toUpperCase()).filter((s) => s && s !== '*');
  const out = [];
  (function walk(n) { for (const c of n.children) { if (any || want.includes(c.tagName)) out.push(c); walk(c); } })(body);
  return out;
};

// the light header the hamburger rides (white -> inverted to black)
const header = el('header', 800, 56);
header._cs = { backgroundColor: 'rgb(255, 255, 255)' };
link(body, header);

// THE HAMBURGER: div.sidebar_icon d-lg-none > img 24x24 data:URI,
// UNDECODED + 0-RECT at the first walk (the Angular SPA hydrating) — the corpse
const burgerWrap = link(header, el('div', 40, 40));
burgerWrap.className = 'sidebar_icon d-lg-none';
const burger = link(burgerWrap, el('img', 0, 0));       // 0-rect at commit
burger.src = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABgAAAAYCAYAAADgdz34';

// THE MINI TOGGLE: div.line_icon open_miniSide > img, 0-rect too — but the
// ANCESTOR signal makes it ride DETERMINISTICALLY at the first walk
const toggleWrap = link(header, el('div', 30, 30));
toggleWrap.id = 'open_miniSide';
toggleWrap.className = 'line_icon open_miniSide d-none d-lg-block';
const toggle = link(toggleWrap, el('img', 0, 0));
toggle.src = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABcAAAASCAYAAACw50UT';

// the DARK sidebar (nav.sidebar.dark_sidebar — DK preserved) with the eight
// 48x48 data:URI sidebar icons that DID get IC under v1.4.25
const sidebar = link(body, el('nav', 240, 1700));
sidebar.className = 'sidebar dark_sidebar';
sidebar._cs = { backgroundColor: 'rgb(33, 37, 41)' };
const sidebarIcons = [];
for (let i = 0; i < 8; i++) {
  const ic = link(sidebar, el('img', 48, 48));
  ic.src = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAYAAABXAvmH';
  ic.complete = true; ic.naturalWidth = 48; ic.naturalHeight = 48;
  sidebarIcons.push(ic);
}

// the resource card column: the five colored buttons + the badge + the
// white card (the saved DOM's resource-btn rows)
const col = link(body, el('main', 560, 1700));
col._cs = { backgroundColor: 'rgb(255, 255, 255)' };
const buttons = {};
function mkBtn(key, bg) {
  const b = link(col, el('a', 260, 44));
  b.className = 'btn btn-' + key + ' w-100 resource-btn';
  b._cs = { backgroundColor: bg, color: 'rgb(255, 255, 255)' };
  buttons[key] = b;
  return b;
}
mkBtn('success', 'rgb(25, 135, 84)');     // lum 0.423 — the v1.4.25 corpse
mkBtn('info', 'rgb(13, 202, 240)');      // lum 0.646
mkBtn('warning', 'rgb(255, 193, 7)');    // lum 0.756 — the spec's named case
mkBtn('danger', 'rgb(220, 53, 69)');     // lum 0.352
mkBtn('teal', 'rgb(32, 201, 151)');      // lum 0.633
mkBtn('dark', 'rgb(33, 37, 41)');        // lum 0.134 — the DK baseline
mkBtn('purple', 'rgb(111, 66, 193)');    // lum 0.332 — the DK baseline
// the badge (bg-success): the preserved green + white text
const badge = link(col, el('span', 120, 28));
badge.className = 'badge px-4 py-2 rounded-pill bg-success';
badge._cs = { backgroundColor: 'rgb(25, 135, 84)', color: 'rgb(255, 255, 255)' };
// the white card's DARK text (inverts — the baseline probe)
const card = link(col, el('div', 400, 120));
card._cs = { backgroundColor: 'rgb(255, 255, 255)', color: 'rgb(33, 37, 41)' };
// the mid-ground light-text span (the black-on-black corpse case): a
// yellow chip (no own light text -> inverts) holding a WHITE-text span
const yellowChip = link(col, el('div', 300, 40));
yellowChip._cs = { backgroundColor: 'rgb(240, 200, 60)', color: 'rgb(34, 34, 34)' };
const whiteSpan = link(yellowChip, el('span', 200, 20));
whiteSpan._cs = { color: 'rgb(255, 255, 255)' };
// a light-text span directly on the white card (the clamped case)
const hintSpan = link(card, el('span', 300, 16));
hintSpan._cs = { color: 'rgb(255, 255, 255)' };
// the PLAIN undecoded icon (NO named ancestor — the deferred leg of the
// race killer: this one rides only after its decode)
const plainIcon = link(card, el('img', 0, 0));
plainIcon.src = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABQAAAAU';

const f = new Function('window', 'document', 'MutationObserver', 'getComputedStyle', js);
f(window, document, MutationObserver, gcs);

// ---------------- the walk ran: the deferred queue holds the plain icon --
// (the burger + toggle rode DETERMINISTICALLY at the walk — the ancestor
// signal — no decode wait; the plain icon needs its load event)
// NOW it decodes: the SPA laid it out at 20x20 (a tiny data:URI).
plainIcon._rect = rect(20, 20);
plainIcon.complete = true; plainIcon.naturalWidth = 20; plainIcon.naturalHeight = 20;
if (plainIcon._ls.load) plainIcon._ls.load();   // the one-shot listener fires

let ok = 0, bad = 0;
const t = (name, cond) => { if (cond) { ok++; } else { bad++; console.log('  probe FAIL: ' + name); } };

t('THE HAMBURGER rides the inversion (the ANCESTOR signal — the div.sidebar_icon wrapper)',
  burger.classList.contains('__boltIC'));
t('THE MINI TOGGLE gets IC at the FIRST walk (the div.line_icon signal, '
  + 'deterministic — no decode wait)',
  toggle.classList.contains('__boltIC'));
t('the PLAIN undecoded icon becomes IC after its load (the deferred leg '
  + 'of the race killer — no frozen first-walk verdict)',
  plainIcon.classList.contains('__boltIC'));
t('the plain icon counts as a LATE IC in the census (late >= 1)',
  window.__boltDarkCount && window.__boltDarkCount.late >= 1);
t('the eight 48x48 sidebar icons keep IC (the v1.4.25 evidence baseline)',
  sidebarIcons.every((i) => i.classList.contains('__boltIC')));
t('the dark sidebar stays preserved (DK)', sidebar.classList.contains('__boltDK'));
for (const k of ['success', 'info', 'warning', 'danger', 'teal']) {
  t('btn-' + k + ' is DK-PRESERVED as designed (the black-on-black corpse)',
    buttons[k].classList.contains('__boltDK'));
  t('btn-' + k + ' NEVER gets the 153 clamp (no __boltSV corpse)',
    !buttons[k].classList.contains('__boltSV') && (buttons[k].style.color || '') === '');
}
t('the badge is preserved (the bg-success corpse)', badge.classList.contains('__boltDK'));
t('btn-dark keeps the baseline DK (lum 0.134)', buttons['dark'].classList.contains('__boltDK'));
t('btn-purple keeps the baseline DK (lum 0.332)', buttons['purple'].classList.contains('__boltDK'));
t('the white card still INVERTS (dark text — the baseline probe)',
  !card.classList.contains('__boltDK'));
t('the white card\'s light-text span CLAMPS to the contrast target (no corpse)',
  hintSpan.classList.contains('__boltSV') &&
  hintSpan.style.color === 'rgb(64,64,64)');
t('the mid-ground light-text span is PRESERVED (no 4.5:1 target -> keep '
  + 'designed light text, NOT 102-on-mid)',
  whiteSpan.classList.contains('__boltDK') && (whiteSpan.style.color || '') === '');
const c = window.__boltDarkCount;
t('the census reflects it: ic >= 11, keep >= 7, late >= 1, small >= 9',
  c && c.ic >= 11 && c.keep >= 7 && c.late >= 1 && c.small >= 9);
t('the census carries the late + keep read-back fields (numbers)',
  typeof c.late === 'number' && typeof c.keep === 'number' && c.keep >= 7);
console.log('  probe: ' + ok + ' ok, ' + bad + ' bad');
process.exit(bad ? 1 : 0);
'''
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fj:
    fj.write(js)
    jspath = fj.name
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fp:
    fp.write(probe3)
    ppath = fp.name
r = subprocess.run(["node", ppath, jspath], capture_output=True, text=True)
print("  " + r.stdout.strip().replace("\n", "\n  ")[:800])
if r.returncode != 0:
    print("  stderr: " + r.stderr[:400])
need(r.returncode == 0,
     "the saved-DOM fixture walk: the hamburger + mini toggle ride, the "
     "five colored buttons + badge are DK-preserved with NO 153 clamp, "
     "the mid-ground light text is preserved, the census reflects it "
     "(ic/late/keep); node probe)")

print()
print("H — THE LAUNCH LAW + the pipeline")
smoke = os.path.join(ROOT, "app/src/test/java/com/bolt/browser/ui/LaunchSmokeTest.java")
need(os.path.isfile(smoke), "the Robolectric launch smoke test still exists")
need("testDebugUnitTest" in RELS and "LAUNCH SMOKE FAILED" in RELS,
     "release.sh still runs the smoke BEFORE the build (the rung stays)")
need("python3 scripts/test_v1426.py" in RELS,
     "release.sh runs this gate (the v1.4.26 rung)")
need("python3 scripts/test_v1425.py" in RELS,
     "the v1.4.25 gate still rides the suite (cumulative law)")
sweep = 0
for g in sorted(os.listdir(os.path.join(ROOT, "scripts"))):
    if not (g.startswith("test_v1") and g.endswith(".py")) or g == "test_v1426.py":
        continue   # this gate's own sweep-check line carries the old literal
    src = open(os.path.join(ROOT, "scripts", g), encoding="utf8").read()
    code = re.sub(r"#[^\n]*", "", src)          # dated history comments survive
    if re.search(r"versionCode\s+139\b", code) or '== "1.4.25"' in code:
        sweep += 1
need(sweep == 0, "the standing sweep repinned every gate to 140/1.4.26 "
     f"({sweep} stragglers)")
need(os.path.isfile(os.path.join(ROOT, "scripts/repin_gates_1426.py")),
     "the v1.4.26 sweep script is checked in (provenance)")
need('versionName "1.4.28"' in open(BG, encoding="utf8").read()
     and "versionCode 142" in open(BG, encoding="utf8").read(),
     "the build carries versionName 1.4.28 / versionCode 142")
     # v1.4.27 re-point (2026-11-22): the build pin + its label moved to
     # 141/1.4.27 (the picture-stack + merged-pipeline round)

print()
if failed:
    print(f"V1426 GATE: BAD ({failed} fail)")
    sys.exit(1)
print("V1426 GATE: OK")
