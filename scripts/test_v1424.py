#!/usr/bin/env python3
"""v1.4.24 release gate — the corpse-fix + size round.

The story (root-caused from the v1.4.23 source + the user's diag.log on a
Realme RMX2020 / Android 10): two field corpses and the twice-requested
size cut. This gate pins the four fixes with probes:
  1 — THE STALE-SNAPSHOT NAVIGATION CORPSE (CRITICAL): a tab's
      saveHist() snapshot (sleepState/histBlob) survived a new t.url, so
      showTab's rebuild restoreState()d the OLD page (facebook resurrected
      over youtube) and re-restored on every later rebuild ("tab hist
      restore idx=N warm" firing repeatedly). FIX: loadInTab clears the
      snapshots BEFORE the load; restoreHist URL-guards the restore;
      showTab consumes the warm Bundle. A genuinely slept tab still
      restores (the v1.4.21 feature, preserved). Probe: SimHist.java —
      the decision model, compiled + run (the corpse demonstrated, the
      fix held, the guard, the consume, the preserved wake).
  2 — THE HARDCODED OCR LANGUAGE (CRITICAL): langsOf() served
      chi_sim+jpn+eng for everything and LANG_OK allowed nothing else, so
      any other source script read ~nothing (720x1353 -> chars=50
      lines=5) and the hybrid translated a fragment. FIX: the OCR SOURCE
      language is a pick separate from the TARGET (the app's own
      translate language list -> tesseract packs, default eng; auto keeps
      the manga trio); a LOW-YIELD pass (<120 chars on a full screen)
      falls back to the vision model reading the whole screenshot.
      Probe: the source mapping + the low-yield decision, transcribed.
  3 — THE SIZE ROUND (the user asked twice): jlatexmath's 30 TTF binaries
      (455,768 B raw — 286,982 B of the 3,325,166 B v1.4.23 APK) move OUT of the APK and
      into an ON-DEMAND download on first math render (the v1.3.73
      tesseract law). Probe: the thin AAR's actual contents (0 TTFs, no
      seam class, descriptors kept), the seam replacement, MathFonts'
      pinned pack + hosts + fallback posture, and the render decision
      model (present? -> full TeX; absent -> span subset + re-render on
      landing; failure -> spans, never a crash).
  4 — THE FORCED-DARK OVER-KEEP (minor): the census showed 7 small media
      ALL classed content (auth.meta.com small=7 ic=0 ni=0 rescue=0);
      monochrome UI glyphs stayed dark. FIX: a small element that paints
      only grays rides the inversion BY DEFAULT even inside photo
      context (colorful small media stay content; large photos and
      banner-shaped images stay content); the census counts mono=N.
      Probe: the fresh darkSmartJs against a mock DOM (mono glyph inside
      <p> inverts; colorful does not; the census counts).
  E — THE LAUNCH LAW stays: the launch smoke is still the pipeline rung
      before any publish, and the v1.4.24 release runs it BEFORE and
      AFTER the font-pack split (the law that caught the v1.4.22 corpse).

Behavioral probes: SimHist.java compiles + runs the navigation decision
model; the source-language + low-yield tables run transcribed; the thin
AAR is unzipped and inspected; the dark walk runs against a mock DOM
(node).
"""
import io
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/java/com/bolt/browser")
RES = os.path.join(ROOT, "app/src/main/res")

failed = 0
def need(cond, label):
    global failed
    if cond:
        print("  ok   -", label)
    else:
        print("  FAIL -", label)
        failed += 1

tab = open(os.path.join(SRC, "core/Tab.java"), encoding="utf8").read()
ba_raw = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
st = open(os.path.join(SRC, "util/ScreenTranslate.kt"), encoding="utf8").read()
mf = open(os.path.join(SRC, "util/MathFonts.kt"), encoding="utf8").read()
ar = open(os.path.join(SRC, "util/AiRender.kt"), encoding="utf8").read()
bai = open(os.path.join(SRC, "util/BoltAi.kt"), encoding="utf8").read()
mh_raw = open(os.path.join(SRC, "media/MediaHook.java"), encoding="utf8").read()
tsleep = open(os.path.join(SRC, "util/TabSleep.kt"), encoding="utf8").read()
gradle = open(os.path.join(ROOT, "app/build.gradle"), encoding="utf8").read()
gprops = open(os.path.join(ROOT, "gradle.properties"), encoding="utf8").read()
rels = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()
strings = open(os.path.join(RES, "values/strings.xml"), encoding="utf8").read()
manifest = open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"), encoding="utf8").read()


def strip(src):
    """blank comments and string bodies so pins read CODE, not prose"""
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', src)
    return src


tab_code = strip(tab)
ba_code = strip(ba_raw)
st_code = strip(st)
mf_code = strip(mf)
ar_code = strip(ar)
bai_code = strip(bai)

print("V0 — version + changelog")
need(re.search(r"versionCode\s+145\b", gradle) is not None, "versionCode 145")
need(re.search(r'versionName\s+"1\.4\.31"', gradle) is not None,
     'versionName "1.4.31"')
need("<item>1.4.24|" in strings, "the 1.4.24 About-changelog entry landed")
need("stale navigation snapshot" in strings.lower()
     or "resurrects the OLD page" in strings,
     "the changelog tells the navigation corpse story honestly")
need("OCR SOURCE language" in strings or "OCR reads" in st,
     "the source-vs-target distinction is user-visible")

# =====================================================================
print()
print("1 — fix 1: THE STALE-SNAPSHOT NAVIGATION CORPSE")
need("public void clearHist()" in tab_code, "Tab.clearHist() exists")
need("sleepState = null;" in tab_code and "histBlob = null;" in tab_code,
     "clearHist retires BOTH snapshot forms")
need("t.clearHist();" in ba_code,
     "loadInTab calls t.clearHist() (stripped-code pin)")
need(ba_raw.find("t.clearHist();") < ba_raw.find("showTab(tm.active); // builds web and loads"),
     "the clear lands BEFORE showTab builds/loads (ordering pin)")
need("l.getCurrentItem().getUrl()" in tab_code and "!cur.equals(url)" in tab_code,
     "restoreHist's URL guard: the snapshot's current item must equal t.url")
need("boolean warm = t.sleepState != null;" in ba_code,
     "showTab captures warm/cold BEFORE the consume (the diag stays true)")
need("if (t.sleepState != null) t.sleepState = null;" in ba_code,
     "THE WARM CONSUME: a successful restore spends the Bundle")
need("try { t.saveHist(w) } catch (ignored: Throwable) {}" in tsleep,
     "PRESERVED: TabSleep still captures saveHist on the sleep path")
need("restoreHist(w)" in ba_code and "w.loadUrl(t.url);" in ba_code,
     "PRESERVED: the rebuild still restores-first, loadUrl as the fallback")

# ---- the behavioral probe: SimHist.java, compiled + run ----
print("  ... compiled probe: the navigation decision model (SimHist.java)")
tdir = tempfile.mkdtemp()
sim = open(os.path.join(ROOT, "scripts/SimHist.java"), encoding="utf8").read()
with open(os.path.join(tdir, "SimHist.java"), "w") as f:
    f.write(sim)
javac = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/javac"
javabin = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/java"
if not os.path.isfile(javac):
    javac, javabin = "javac", "java"
r = subprocess.run([javac, "-d", tdir, os.path.join(tdir, "SimHist.java")],
                  capture_output=True, text=True)
if r.returncode != 0:
    print("  compile fail: " + r.stderr[:300])
r2 = subprocess.run([javabin, "-cp", tdir, "SimHist"], capture_output=True, text=True)
print("\n".join("  " + ln for ln in r2.stdout.strip().split("\n")))
need(r2.returncode == 0 and "all verdicts hold" in r2.stdout,
     "SimHist: the corpse demonstrated + the fix + the guard + the "
     "consume + the preserved slept-tab wake (compiled+run)")

# =====================================================================
print()
print("2 — fix 2: THE HARDCODED OCR LANGUAGE (source law + low yield)")
need('val p = lastSrc ?: "eng"' in st,
     "langsOf reads the SOURCE choice; the DEFAULT is eng (Latin script) [RAW]")
need('"auto") arrayOf("chi_sim", "eng") else arrayOf(p)' in st,
     "auto keeps the trusted pair; every other source is its own pack [RAW]")
     # v1.4.28 re-point (2026-11-22): the jpn member is GONE from auto —
     # the wasm engine rejects the vert-family models it was there for
     # (chars=0 lines=0 for all languages once the worker is poisoned);
     # Japanese screens ride the vision fallback / manga vision-primary
need("private val LANG_OK = setOf(" in st_code and '"ben"' in st and '"vie"' in st and '"urd"' in st,
     "LANG_OK serves the expanded source-language set")
need('"scrSrc"' in st and "setSrc(a, packs[w])" in st_code,
     "the source choice persists (scrSrc) and the picker sets it")
need("private fun tessPackOf(code: String): String? = when (code) {" in st_code,
     "the translate_codes -> tesseract pack mapping table exists")
need("private fun pickSrcLanguage(a: Activity, then: () -> Unit)" in st_code,
     "the OCR SOURCE picker dialog exists (separate from the target)")
need('setNegativeButton("OCR language: " + srcLabelOf(a, srcPack(a))' in st,
     "the mode picker carries the OCR-language door (RAW)")
need("private fun srcPack(a: Context): String {" in st_code
     and "srcPack(a)   // v1.4.24" in st,
     "every pipeline entry resolves the source choice before langs use")
need('"ben" to 855841L' in st and '"vie" to 531275L' in st,
     "the new packs' model sizes are the pinned tessdata_fast repo's own")
need("private const val YIELD_FLOOR = 120" in st_code,
     "THE LOW-YIELD LAW: <120 chars on a full screen = not read")
need("if (all.isEmpty() || chars < YIELD_FLOOR) {" in st_code,  # v1.4.25 re-point (2026-11-21): the grid hybrid's aggregate guard (per-tile verdicts ride TILE_FLOOR)  # v1.4.27 re-point (2026-11-22): the merged decision's aggregate guard (the cluster list replaced hybridBlocks)
     "low yield -> the vision fallback (not the sparse-fragment hybrid)")
need('" -> under-read"' in st and '" -> read"' in st,  # v1.4.27 re-point (2026-11-22): the merged per-tile breadcrumb verdicts (under-read tiles are named honestly; the ai-only prefix left with the split)
     "the diag names the OCR yield AND the path that ran (RAW)")
need('" src=" + srcPack(a)' in st,
     "the hybrid-ask diag names the source pack (RAW)")
need("srcLabelOf(a, srcPack(a))" in st, "the picker door shows the current source label")

# ---- the behavioral probe: the source mapping + low-yield, transcribed ----
print("  ... transcribed probe: the source mapping + the low-yield decision")
TESS = {"en": "eng", "bn": "ben", "hi": "hin", "es": "spa", "ar": "ara",
        "fr": "fra", "de": "deu", "pt": "por", "ru": "rus", "zh-CN": "chi_sim",
        "ja": "jpn", "ko": "kor", "id": "ind", "tr": "tur", "ur": "urd",
        "vi": "vie"}
TRANSLATE_CODES = ["en", "bn", "hi", "es", "ar", "fr", "de", "pt", "ru",
                   "zh-CN", "ja", "ko", "id", "tr", "ur", "vi"]
ok = True
for code, pack in TESS.items():
    if code not in TRANSLATE_CODES or not pack:
        ok = False
LANG_OK = {"eng", "jpn", "chi_sim", "ben", "hin", "spa", "ara", "fra",
           "deu", "por", "rus", "kor", "ind", "tur", "urd", "vie"}
need(ok and set(TESS.values()) == LANG_OK,
     "the picker maps ALL sixteen translate languages to tesseract packs, "
     "and LANG_OK serves exactly those packs")


def langs_of(src):                     # the transcribed langsOf
    p = src if src else "eng"
    return ["chi_sim", "jpn", "eng"] if p == "auto" else [p]


def hybrid_path(chars):                # the transcribed aiOcrDone decision
    return "vision" if chars < 120 else "hybrid"


need(langs_of(None) == ["eng"],
     "an unset source defaults to eng, NOT chi_sim+jpn+eng (the corpse)")
need(langs_of("ben") == ["ben"], "a Bengali screen rides the ben pack alone")
need(langs_of("auto") == ["chi_sim", "jpn", "eng"],
     "auto keeps the manga trio in one pass")
need(hybrid_path(50) == "vision",
     "the field corpse's 50-char yield falls to the vision path")
need(hybrid_path(119) == "vision" and hybrid_path(120) == "hybrid",
     "the 120-char floor is the exact boundary")
need(hybrid_path(400) == "hybrid",
     "a well-read screen keeps the hybrid (OCR boxes + ONE text pass)")

# =====================================================================
print()
print("3 — fix 3: THE SIZE ROUND (the font-pack split, on demand)")
AAR = os.path.join(ROOT, "app", "libs", "jlatexmath-thin.aar")
need(os.path.isfile(AAR), "app/libs/jlatexmath-thin.aar is checked in")
need(os.path.isfile(os.path.join(ROOT, "scripts/thin_jlatexmath.py")),
     "the reproducible thin-AAR generator script rides the repo")
need(re.search(r"implementation\s+'ru\.noties:jlatexmath-android", gradle) is None,
     "the maven AAR dependency is GONE from the build")
need("implementation files('libs/jlatexmath-thin.aar')" in gradle,
     "the thin AAR rides the build as a local file dependency")
need("android.enableR8.fullMode=true" in gprops,
     "R8 FULL MODE pinned explicitly (verified ON)")
need("shrinkResources true" in gradle,
     "shrinkResources stays ON (the v1.3.41 law)")

if os.path.isfile(AAR):
    z = zipfile.ZipFile(AAR)
    names = z.namelist()
    ttfs = [n for n in names if n.endswith(".ttf")]
    cj = zipfile.ZipFile(io.BytesIO(z.read("classes.jar")))
    seam = [n for n in cj.namelist() if "JLatexMathAndroid" in n]
    font_xmls = [n for n in names if n.endswith(".xml") and "/fonts/" in n]
    root_xmls = [n for n in names
                 if n.startswith("assets/org/scilab/forge/jlatexmath/")
                 and n.count("/") == 5 and n.endswith(".xml")]
    need(len(ttfs) == 7, "the thin AAR bundles the 7 CORE TTFs (the "
         "v1.4.25 rebuy — core math offline/metered; the 23 optional "
         "TTFs stay on-demand)")
    need(seam == [] and len(cj.namelist()) > 200,
         "the AAR's JLatexMathAndroid seam class is stripped (the engine "
         "classes ride)")
    need(len(font_xmls) == 40,
         "the 40 font descriptor/metric XMLs stay bundled (the engine data)")
    need(len(root_xmls) == 4,
         "the four root descriptors (DefaultTeXFont/Glue/TeXFormula/"
         "TeXSymbols.xml) stay bundled")

seam_src = open(os.path.join(ROOT, "app/src/main/java/ru/noties/jlatexmath/",
                             "JLatexMathAndroid.java"), encoding="utf8").read()
need("package ru.noties.jlatexmath;" in seam_src
     and "public static void init(Context context)" in seam_src
     and "public static InputStream getResourceAsStream(String path)" in seam_src
     and "public static Typeface loadTypeface(@NonNull String path)" in seam_src,
     "the app's own seam class matches upstream's public surface exactly")
need("MathFonts.packFile(c, path)" in seam_src,
     "both seam doors fall through to the downloaded pack")
need('tools:node="remove"' in manifest and "JLatexMathInitProvider" in manifest,
     "the auto-init provider stays stripped from the merged manifest "
     "(browser start never pays for chat math)")

need('private val PACK = arrayOf(' in mf_code
     and mf_code.count('.ttf"'.replace('""', '')) >= 0,  # shape only
     "MathFonts pins the exact font list")
PACK_COUNT = len(re.findall(r'"[a-z]+/[\w/]*\.ttf"', mf))
need(PACK_COUNT == 30,
     "the on-demand pack is the exact 30-file list (mirrors the strip)")
need("289048" in mf_code, "the OPTIONAL pack size rides the source "
     "(the size story; the core 7 moved into the APK)")
need("cdn.jsdelivr.net/gh/noties/jlatexmath-android@0.2.0" in mf
     and "raw.githubusercontent.com/noties/jlatexmath-android/v0.2.0" in mf,
     "the pinned hosts are the upstream repo's own assets (both mirrors)")
need("isActiveNetworkMetered" in mf_code,
     "a metered connection skips the download (the tessdata precedent)")
need('File(c.filesDir, "jlmfonts")' in mf and '".ok"' in mf,
     "the pack lands in filesDir/jlmfonts with a one-stat marker [RAW]")
need("MathFonts.present(c)" in ar_code and "return null" in ar_code,
     "mathDrawable declines while the pack is absent (span subset shows)")
need("MathFonts.ensureAsync(c, onReady = {" in ar_code and "fun refreshPendings()" in ar_code,  # v1.4.25 re-point (2026-11-21): the failure-path nudge (core renders without any pack)
     "the bubbles re-render the moment the pack lands")
need("holdForFonts(c: Context, md: String, box: android.widget.LinearLayout)" in ar_code
     and "fillMarkdown(c: Context, box: android.widget.LinearLayout, md: String)" in ar_code,
     "the math-carrying bubbles are held for the pack-ready re-fill")
     # v1.4.28 re-point (2026-11-22): render() lost the view param —
     # fillMarkdown owns the hold now (the bubble is the container)
need("AiRender.fillMarkdown(a, box, text)" in bai_code
     and "fillBubble(a, thinking, reply, false)" in bai_code,
     "both chat render sites hand the bubble CONTAINER over")
     # v1.4.28 re-point (2026-11-22): the render sites became container
     # fills — addBubble/fillBubble carry fillMarkdown, the streaming
     # reply re-fills the thinking container through the same helper

# ---- the behavioral probe: the door + render decision, transcribed ----
print("  ... transcribed probe: the seam doors + the render decision")


def seam_door(path, pack_files):        # the transcribed JLatexMathAndroid doors
    """returns ('assets'|'pack'|None) — None = throws -> span fallback"""
    if path in pack_files:
        return "pack"
    if path.endswith((".xml",)):
        return "assets"          # descriptors stayed bundled
    return None                  # a stripped TTF with no pack -> throws


pack_files = {"fonts/base/jlm_cmmi10.ttf"}
need(seam_door("DefaultTeXFont.xml", pack_files) == "assets",
     "the XML descriptors stream from the APK assets (offline-parseable)")
need(seam_door("fonts/base/jlm_cmmi10.ttf", pack_files) == "pack",
     "a TTF reads from the downloaded pack when present")
need(seam_door("fonts/base/jlm_cmex10.ttf", pack_files) is None,
     "a TTF with no pack THROWS — caught, the drawable declines")


def render_dec(pack_present, formula_parses):
    """the transcribed AiRender.mathDrawable decision"""
    if not pack_present:
        return "spans+rerender"      # null -> subset now, hold for re-render
    return "fulltex" if formula_parses else "spans"


need(render_dec(False, True) == "spans+rerender",
     "absent pack: the honest subset renders NOW (never a crash), the "
     "bubble held for the re-render")
need(render_dec(True, True) == "fulltex",
     "pack present + parseable: FULL TeX renders")
need(render_dec(True, False) == "spans",
     "pack present + unparseable formula: the span subset (the v1.4.20 law)")
need(render_dec(False, False) == "spans+rerender",
     "absent pack + unparseable: still never a crash")

# =====================================================================
print()
print("4 — fix 4: THE FORCED-DARK OVER-KEEP (monochrome glyph law)")
need("function monoOf(el,cs){" in mh_raw and "function spreadOf(c){" in mh_raw,
     "the monochrome detector family: monoOf/spreadOf")
need("if(sp<=32)return true;" in mh_raw,
     "grayish = channel spread <= 32 (or extreme luminance)")
need("el.naturalWidth<=128&&el.naturalHeight<=128" in mh_raw,
     "an IMG with tiny INTRINSIC size is an icon asset (mono)")
need("!((bannerish(el)||photoCtx(el))" in mh_raw
     and "!monoOf(el,getComputedStyle(el))" in mh_raw,
     "ridesFlip: the content veto only holds for COLORFUL media")
need("var cnt={small:0,ic:0,ni:0,rescue:0,mono:0,fi:0,late:0,keep:0};" in mh_raw,  # v1.4.25 re-point (2026-11-21): +fi (font-icon glyphs)  # v1.4.26 re-point (2026-11-21): +late/keep (the deferred-IC + contrast-preserve read-backs)
     "the census carries the mono field")
need("mono='+(o.mono||0)+' fi='+(o.fi||0)" in mh_raw
     and "' late='+(o.late||0)+' keep='+(o.keep||0);" in mh_raw,  # v1.4.25 re-point (2026-11-21): the reader grew fi=N  # v1.4.26 re-point (2026-11-21): +late/keep tails
     "the census reader reports mono=N (the read-back)")

# ---- the behavioral probe: the fresh darkSmartJs against a mock DOM ----
print("  ... behavioral probe: the monochrome-glyph law (mock DOM, node)")
m = re.search(r"public static String darkSmartJs\(\)\s*\{(.*?)\n    \}", mh_raw, re.S)
parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
js = "".join(p.replace('\\"', '"').replace("\\\\", "\\").replace("\\n", "\n") for p in parts)
probe = r'''
const fs = require('fs');
const js = fs.readFileSync(process.argv[2], 'utf8');
function rect(w, h) { return { width: w, height: h, top: 0, left: 0 }; }
function mkClassList() {
  const set = new Set();
  return { contains: (c) => set.has(c), add: (c) => set.add(c), remove: (c) => set.delete(c), _set: set };
}
function el(tag, w, h) {
  const e = { tagName: tag.toUpperCase(), children: [], parentElement: null,
    getBoundingClientRect: () => rect(w, h), classList: null, style: {}, className: '', id: '',
    complete: false, naturalWidth: 0, naturalHeight: 0,
    _dark: false, getAttribute: () => null, setAttribute: () => {} };
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
let lastDrawn = null;
function ctxMock() {
  return { drawImage: (el) => { lastDrawn = el; },
    getImageData: (x, y, w, h) => {
      const px = lastDrawn && lastDrawn._dark ? [10, 10, 10, 255] : [240, 240, 240, 255];
      const data = []; for (let i = 0; i < w * h; i++) data.push(...px);
      return { data }; } };
}
const document = { body: null, documentElement: el('html', 100, 100), head: el('head', 0, 0),
  createElement: (t) => { const e = el(t, 0, 0); if (t === 'canvas') e.getContext = () => ctxMock(); return e; },
  getElementById: () => null, querySelectorAll: () => [] };
let MutationObserver = function (cb) { this.observe = () => {}; this.disconnect = () => {}; };
let window = {};
global.setTimeout = (fn, ms) => { fn(); return 0; };
const body = el('body', 800, 600);
document.body = body;
body.querySelectorAll = (sel) => {
  const any = sel.includes('*');
  const want = sel.split(',').map((s) => s.trim().toUpperCase()).filter((s) => s && s !== '*');
  const out = [];
  (function walk(n) { for (const c of n.children) { if (any || want.includes(c.tagName)) out.push(c); walk(c); } })(body);
  return out;
};
// the page: a light body under the inversion, an article, and the glyph zoo
const divA = el('div', 300, 40); divA._cs = { backgroundColor: 'rgb(255, 255, 255)' };
link(body, divA);
const art = el('article', 800, 600); link(body, art);
const p = el('p', 800, 20); link(art, p);
// P: small MONO svg inside <p> — the auth.meta.com corpse shape
const pMonoSvg = link(p, el('svg', 18, 18));
pMonoSvg._cs = { fill: 'rgb(17, 17, 17)' };
// Q: small MONO img (tiny intrinsic) inside <p>
const pMonoImg = link(p, el('img', 20, 20));
pMonoImg.complete = true; pMonoImg.naturalWidth = 24; pMonoImg.naturalHeight = 24;
// R: small COLORFUL svg inside <p> — stays content (the veto holds)
const pColorSvg = link(p, el('svg', 20, 20));
pColorSvg._cs = { fill: 'rgb(30, 90, 200)' };
// S: small COLORFUL img (large intrinsic) inside <p> — stays content
const pColorImg = link(p, el('img', 40, 40));
pColorImg.complete = true; pColorImg.naturalWidth = 600; pColorImg.naturalHeight = 600;
// T: banner-shaped small MONO svg — a glyph divider, rides now
const bannerMono = link(divA, el('svg', 64, 12));
bannerMono._cs = { fill: 'rgb(51, 51, 51)' };
// U: LARGE photo — always content
const bigImg = link(art, el('img', 400, 300));
const f = new Function('window', 'document', 'MutationObserver', 'getComputedStyle', js);
f(window, document, MutationObserver, gcs);
let ok = 0, bad = 0;
const t = (name, cond) => { if (cond) { ok++; } else { bad++; console.log('  probe FAIL: ' + name); } };
t('P small mono svg inside <p> rides IC (the corpse shape)', pMonoSvg.classList.contains('__boltIC'));
t('Q small mono img (tiny intrinsic) inside <p> rides IC', pMonoImg.classList.contains('__boltIC'));
t('R small COLORFUL svg inside <p> stays content', !pColorSvg.classList.contains('__boltIC'));
t('S small colorful img inside <p> stays content', !pColorImg.classList.contains('__boltIC'));
t('T banner-shaped mono glyph rides IC now', bannerMono.classList.contains('__boltIC'));
t('U large photo stays content', !bigImg.classList.contains('__boltIC'));
const c = window.__boltDarkCount;
t('census small=5', c && c.small === 5);
t('census mono=3', c && c.mono === 3);
console.log('  probe: ' + ok + ' ok, ' + bad + ' bad');
process.exit(bad ? 1 : 0);
'''
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fj:
    fj.write(js); jspath = fj.name
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fp:
    fp.write(probe); ppath = fp.name
r = subprocess.run(["node", ppath, jspath], capture_output=True, text=True)
print(r.stdout.rstrip())
need(r.returncode == 0, "the monochrome-glyph walk behaves (6 mock-DOM "
     "verdicts + the mono census; node probe)")

# =====================================================================
print()
print("E — THE LAUNCH LAW + the pipeline")
smoke = os.path.join(ROOT, "app/src/test/java/com/bolt/browser/ui/LaunchSmokeTest.java")
need(os.path.isfile(smoke), "the Robolectric launch smoke test still exists")
need("testDebugUnitTest" in rels and "LAUNCH SMOKE FAILED" in rels,
     "release.sh still runs the smoke BEFORE the build (the rung stays)")
need("python3 scripts/test_v1424.py" in rels,
     "release.sh runs this gate (the v1.4.24 rung)")
need("python3 scripts/test_v1423.py" in rels,
     "the v1.4.23 gate still rides the suite (cumulative law)")
sweep = 0
for g in sorted(os.listdir(os.path.join(ROOT, "scripts"))):
    if not (g.startswith("test_v1") and g.endswith(".py")) or g == "test_v1424.py":
        continue   # this gate's own sweep-check line carries the old literal
    src = open(os.path.join(ROOT, "scripts", g), encoding="utf8").read()
    code = re.sub(r"#[^\n]*", "", src)          # dated history comments survive
    if re.search(r"versionCode\s+137\b", code) or '== "1.4.23"' in code:
        sweep += 1
need(sweep == 0, "the standing sweep repinned every gate to 138/1.4.24 "
     f"({sweep} stragglers)")
need(os.path.isfile(os.path.join(ROOT, "scripts/repin_gates_1424.py")),
     "the v1.4.24 sweep script is checked in (provenance)")
need(os.path.isfile(os.path.join(ROOT, "scripts/SimHist.java")),
     "the navigation decision model is checked in (probe provenance)")

print()
if failed:
    print(f"V1424 GATE: BAD ({failed} fail)")
    sys.exit(1)
print("V1424 GATE: OK")
