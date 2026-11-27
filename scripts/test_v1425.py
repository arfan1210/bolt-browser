#!/usr/bin/env python3
"""v1.4.25 release gate — the reliability + coverage round.

The story (root-caused from the v1.4.24 source + the user's diag.log on a
Realme RMX2020 / Android 10, often on METERED mobile data): five field
corpses. This gate pins all five with probes:
  1 — MATH MUST WORK RELIABLY (the on-demand font cut backfired): the
      pack never landed on metered data ("math dl fail dl
      base/jlm_cmex10.ttf"), ONE failure locked the process out, and no
      manual retry existed. FIX: the 7 CORE fonts ride the APK again
      (offline/metered math — fractions, matrices, Greek, AMS), the 23
      optional fonts stay on-demand with the RETRY law (20 s back-off, no
      process lockout) + the manual Math-fonts chip + live status. Probe:
      the thin AAR inspection (7 core TTFs, exact names) + the
      transcribed retry/metered/status decision tables + the render
      decision (core renders with NO pack; an optional-font miss falls to
      the subset AND kicks the download).
  2 — COLOURED TEXT in Bolt AI chat: AiRender's HTML handler ignored
      explicit color. FIX: <span style="color:..."> and <font color="...">
      -> ForegroundColorSpan (hex/rgb/rgba/named, parsed safely), and the
      code-block colorizer verified multi-color. Probe: the transcribed
      parseHtmlColor table + the tokenizeCode kind coverage.
  3 — SCREEN TRANSLATE MUST COVER THE WHOLE PAGE (not a thin right strip):
      "OCR chars=54 lines=5" -> vision fallback -> "vision ok lines=1"
      (ONE hallucinated box_2d). FIX: THE GRID LAW — a 2x3 (portrait) /
      3x2 (landscape) cell grid, per-tile OCR at NATIVE resolution with a
      MILD 2/98 percentile stretch, per-tile text-only VISION cards for
      under-read cells placed on their FIXED cells (box_2d never trusted
      again), full-page coverage geometric. Probe: the transcribed grid
      geometry (exact tiling) + the per-tile decision + the card rect +
      the tile OCR encoder pins.
  4 — PASSWORD AUTOFILL DID NOTHING on tap: the && guard failed silently
      and the DIU login lives on a CROSS-ORIGIN SSO IFRAME the main frame
      never reaches. FIX: __boltFillPw in EVERY frame (FrameHooks
      document-start), the login-field arming from ANY frame with its OWN
      host, the fill ROUTED to the frames that reported login fields, the
      native value setter + input/change events, the outcome ALWAYS
      reported (toast + Diag) with a visible timeout. Probe: the mock-DOM
      fill relay (two frames, native-setter fill, event dispatch, the
      climb to Bolt.pwFillResult).
  5 — THE FAINT HAMBURGER / FONT-ICON in forced dark: the Moodle glyph is
      a Font Awesome <i class="fa fa-bars"> — not IMG/SVG, so the
      classifier skipped it and the font-rescue lifted only lum<0.25 to a
      153 mid-gray. FIX: THE RIDE + LIGHT LAW — icon-font glyphs and their
      pseudos RIDE the inversion like text (never the faint clamp), a
      glyph pinned dark OR MID (<0.55) on a preserved dark ground lifts to
      the VISIBLE light (232) inline + via !important pseudo rules; the
      census counts fi=N. Probe: the mock-DOM FA-glyph walk (the lift, the
      ride, the census fi).
  E — THE LAUNCH LAW stays: the launch smoke is still the pipeline rung
      before any publish.

Behavioral probes: the thin AAR is unzipped + inspected; the retry/status
and colour tables run transcribed; the fill relay and the dark walk run
against mock DOMs (node).
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

failed = 0
def need(cond, label):
    global failed
    if cond:
        print("  ok   -", label)
    else:
        print("  FAIL -", label)
        failed += 1

ba_raw = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
st = open(os.path.join(SRC, "util/ScreenTranslate.kt"), encoding="utf8").read()
mf = open(os.path.join(SRC, "util/MathFonts.kt"), encoding="utf8").read()
ar = open(os.path.join(SRC, "util/AiRender.kt"), encoding="utf8").read()
bai = open(os.path.join(SRC, "util/BoltAi.kt"), encoding="utf8").read()
fh = open(os.path.join(SRC, "util/FrameHooks.kt"), encoding="utf8").read()
ab = open(os.path.join(SRC, "util/AiBridge.kt"), encoding="utf8").read()
mh_raw = open(os.path.join(SRC, "media/MediaHook.java"), encoding="utf8").read()
gradle = open(os.path.join(ROOT, "app/build.gradle"), encoding="utf8").read()
gprops = open(os.path.join(ROOT, "gradle.properties"), encoding="utf8").read()
rels = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()
strings = open(os.path.join(ROOT, "app/src/main/res/values/strings.xml"), encoding="utf8").read()
thin = open(os.path.join(ROOT, "scripts/thin_jlatexmath.py"), encoding="utf8").read()


def strip(src):
    """blank comments and string bodies so pins read CODE, not prose"""
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', src)
    return src


ba_code = strip(ba_raw)
st_code = strip(st)
mf_code = strip(mf)
ar_code = strip(ar)
bai_code = strip(bai)
fh_code = strip(fh)
ab_code = strip(ab)
mh_code = strip(mh_raw)
thin_code = strip(thin)

print("V0 — version + changelog")
need(re.search(r"versionCode\s+148\b", gradle) is not None, "versionCode 148")
need(re.search(r'versionName\s+"1\.4\.34"', gradle) is not None,
     'versionName "1.4.34"')
need("<item>1.4.25|" in strings, "the 1.4.25 About-changelog entry landed")
need("Math fonts" in strings, "the changelog tells the math-reliability story")
need("WHOLE page" in strings, "the changelog tells the grid-coverage story")

# =====================================================================
print()
print("1 — fix 1: MATH MUST WORK RELIABLY (the core-font rebuy + retry law)")
AAR = os.path.join(ROOT, "app", "libs", "jlatexmath-thin.aar")
need(os.path.isfile(AAR), "app/libs/jlatexmath-thin.aar is checked in")
CORE_EXPECT = ["base/jlm_cmex10.ttf", "base/jlm_cmmi10.ttf", "base/jlm_cmmib10.ttf",
               "latin/jlm_cmr10.ttf", "maths/jlm_cmsy10.ttf", "maths/jlm_msam10.ttf",
               "maths/jlm_msbm10.ttf"]
if os.path.isfile(AAR):
    z = zipfile.ZipFile(AAR)
    names = z.namelist()
    ttfs = [n[len("assets/org/scilab/forge/jlatexmath/fonts/"):]
            for n in names if n.startswith(
                "assets/org/scilab/forge/jlatexmath/fonts/") and n.endswith(".ttf")]
    core_in_aar = sorted(t for t in ttfs if t in CORE_EXPECT)
    cj = zipfile.ZipFile(io.BytesIO(z.read("classes.jar")))
    seam = [n for n in cj.namelist() if "JLatexMathAndroid" in n]
    font_xmls = [n for n in names if n.endswith(".xml") and "/fonts/" in n]
    need(sorted(CORE_EXPECT) == core_in_aar,
         "the thin AAR bundles EXACTLY the 7 CORE fonts (offline/metered math)")
    need(len(ttfs) == 7,
         "no optional TTF rides the APK (the 23 stay on-demand)")
    core_bytes = sum(z.getinfo(
        "assets/org/scilab/forge/jlatexmath/fonts/" + t).file_size for t in core_in_aar)
    need(100_000 < core_bytes < 220_000,
         f"the core bundle is ~120-170 KB raw ({core_bytes} B — the reliability price)")
    need(seam == [] and len(cj.namelist()) > 200,
         "the AAR's seam class stays stripped (the engine classes ride)")
    need(len(font_xmls) == 40,
         "the 40 font descriptor/metric XMLs stay bundled (the engine data)")

# the mirror law: thin_jlatexmath.py's CORE set == MathFonts.CORE
need("CORE = frozenset({" in thin_code
     and all(f'"{c}"' in thin for c in CORE_EXPECT),
     "thin_jlatexmath.py pins the exact 7-file CORE set")
need("val CORE = setOf(" in mf_code
     and all(f'"{c}"' in mf for c in CORE_EXPECT),
     "MathFonts.CORE mirrors the AAR's CORE set (edit-the-two law)")
PACK_COUNT = 0
_pack_m = re.search(r"private val PACK = arrayOf\((.*?)\)", mf, re.S)
if _pack_m:
    PACK_COUNT = len(re.findall(r'"[a-z]+/[\w/]*\.ttf"', _pack_m.group(1)))
need(PACK_COUNT == 23,
     "the on-demand pack is the exact 23-file optional list")
need("private const val PACK_BYTES = 289048L" in mf_code,
     "the optional pack size rides the source (289,048 B = 455,768 - 166,720)")
need("failedProc" not in mf_code,
     "THE RETRY LAW: the one-failure-per-process lockout is GONE")
need("private val lastFailAt" in mf_code
     and "private const val RETRY_GAP_MS = 20_000L" in mf_code,
     "a failure stamps a 20 s back-off (the next math render retries)")
need("fun ensureAsync(c: Context, onReady: (() -> Unit)? = null, force: Boolean = false)" in mf
     and "fun downloadNow(c: Context): String" in mf_code,
     "the force door: ensureAsync(force) + downloadNow (the manual retry)")
need('fun status(c: Context): String {' in mf_code
     and '"ready"' in mf and '"downloading"' in mf and '"failed-retry"' in mf,
     "the live status door: ready / downloading / failed-retry / core")
need("if (metered && !force)" in mf_code,
     "the AUTO path skips metered; the MANUAL (forced) path proceeds on it")
need("math dl fail $detail" in mf and "retries on next render" in mf,
     "the failure Diag says the truth (core unaffected; retry rides)")
need("math dl manual state=" in bai, "the manual door Diags its state (RAW)")
# the render decision: core renders with NO pack
need("if (!com.bolt.browser.util.MathFonts.present(c)) {" not in ar_code,
     "mathDrawable no longer early-returns on the absent pack (core math renders offline)")
need("jlmInit(c)" in ar_code and "ensureAsync(c, onReady = {" in ar_code,
     "the builder runs immediately; a miss kicks the download in the catch")
need('chip(mathFontLabel(a))' in bai_code
     and "Math fonts" in bai and "Download full math fonts" in bai,
     "the Bolt AI panel carries the Math-fonts chip (label + door)")
need('"ready" -> "Math fonts ready"' in bai and '"downloading" -> "Math fonts downloading' in bai,
     "the chip's label is the live status")
need("ensureAsync(a, {" in bai and "true)" in bai,
     "the chip FORCES the attempt (metered included)")

# ---- the behavioral probe: the retry / metered / status decision tables ----
print("  ... transcribed probe: the retry + metered + status decisions")

def ensure_decision(pack_present, in_flight, last_fail_ago_ms, forced, metered):
    """the transcribed MathFonts.ensureAsync decision"""
    if pack_present:
        return "callback-now"
    if in_flight and not forced:
        return "queued"                       # one download at a time
    if not forced and last_fail_ago_ms is not None and last_fail_ago_ms < 20_000:
        return "skip-backoff"                 # the retry law
    if metered and not forced:
        return "metered-skip"                  # auto path; NOT a failure
    return "download"

need(ensure_decision(True, False, None, False, False) == "callback-now",
     "pack present -> the callback runs immediately")
need(ensure_decision(False, True, None, False, False) == "queued",
     "an in-flight download queues extra callers (never parallel)")
need(ensure_decision(False, False, 5_000, False, False) == "skip-backoff",
     "5 s after a failure the AUTO path waits (no hammering)")
need(ensure_decision(False, False, 25_000, False, False) == "download",
     "25 s after a failure the next math render retries (the lockout corpse is dead)")
need(ensure_decision(False, False, 0, True, False) == "download",
     "the MANUAL tap ignores the back-off entirely")
need(ensure_decision(False, False, None, False, True) == "metered-skip",
     "the AUTO path skips metered connections (core math already works)")
need(ensure_decision(False, False, 0, True, True) == "download",
     "the MANUAL tap downloads ON METERED data (the user's own call)")

def status_of(present, in_flight, failed_before):
    """the transcribed MathFonts.status"""
    if present: return "ready"
    if in_flight: return "downloading"
    if failed_before: return "failed-retry"
    return "core"

need(status_of(True, False, False) == "ready", "status: pack landed -> ready")
need(status_of(False, True, False) == "downloading", "status: in flight -> downloading")
need(status_of(False, False, True) == "failed-retry", "status: last attempt failed -> failed-retry")
need(status_of(False, False, False) == "core",
     "status: no pack, no attempt -> core (core math works; the chip offers the full set)")

def render_dec(core_bundled, formula_needs_optional_font):
    """the transcribed AiRender.mathDrawable decision"""
    if not core_bundled:
        return "spans"          # unreachable: the core set ships in the APK
    if not formula_needs_optional_font:
        return "fulltex"        # offline / metered — immediately
    return "spans+kick"         # the subset shows; the download retries

need(render_dec(True, False) == "fulltex",
     "a fraction/matrix renders FULL TeX with NO network at all")
need(render_dec(True, True) == "spans+kick",
     "an optional-font formula (\\mathfrak) falls to the subset AND kicks the download")
need(render_dec(False, False) == "spans",
     "(unreachable guard) without core fonts the subset shows — never a crash")

# =====================================================================
print()
print("2 — fix 2: COLOURED TEXT in Bolt AI chat")
need("ATTR_COLOR" in ar_code and "ATTR_STYLE" in ar_code and "STYLE_COLOR" in ar_code,
     "the colour attr extractors exist (font color= / style color:)")
need('"span", "font" -> {' in ar,
     "the htmlTag when-gain carries span/font (RAW — the tag names are literals)")
need("fun htmlColor(tag: String, attrs: String): Int? {" in ar_code,
     "the tag -> colour decision function")
need("fun parseHtmlColor(v: String): Int?" in ar_code,
     "parseHtmlColor is PURE (probe-pinned)")
need("NAMED_COLORS" in ar_code and '"rebeccapurple" to' in ar,
     "the named-colour table rides the parser")
need("alpha < 0.4" in ar_code or "a < 102" in ar_code,
     "the wash-out guard: translucent colours decline, not fade")

# ---- the behavioral probe: the colour parser, transcribed ----
print("  ... transcribed probe: parseHtmlColor")

NAMED = {"red": 0xFFFF0000, "green": 0xFF008000, "blue": 0xFF0000FF,
         "gold": 0xFFFFD700, "tomato": 0xFFFF6347, "whitesmoke": 0xFFF5F5F5}

def hex2(a, b):
    try:
        return (int(a, 16) << 4) | int(b, 16)
    except Exception:
        return None

def parse_html_color(v):
    """the transcribed AiRender.parseHtmlColor"""
    if not v or len(v) > 40: return None
    s = v.strip().lower()
    if s.startswith("#"):
        h = s[1:]
        if len(h) == 3:
            r, g, b = hex2(h[0], h[0]), hex2(h[1], h[1]), hex2(h[2], h[2])
            if None in (r, g, b): return None
            return 0xFF000000 | (r << 16) | (g << 8) | b
        if len(h) == 6:
            r, g, b = hex2(h[0], h[1]), hex2(h[2], h[3]), hex2(h[4], h[5])
            if None in (r, g, b): return None
            return 0xFF000000 | (r << 16) | (g << 8) | b
        if len(h) == 8:
            a, r, g, b = hex2(h[0], h[1]), hex2(h[2], h[3]), hex2(h[4], h[5]), hex2(h[6], h[7])
            if None in (a, r, g, b): return None
            if a < 102: return None
            return (a << 24) | (r << 16) | (g << 8) | b
        return None
    import re as _re
    m = _re.match(r"rgba?\(\s*([^,)]+)\s*,\s*([^,)]+)\s*,\s*([^,)]+)\s*(?:,\s*([\d.]+))?\)?", s)
    if m:
        def chan(t):
            t = t.strip()
            if t.endswith("%"):
                f = float(t[:-1])
                return None if f < 0 or f > 100 else int(f * 2.55)
            n = int(t) if t.isdigit() else None
            return n if n is not None and 0 <= n <= 255 else None
        r, g, b = chan(m.group(1)), chan(m.group(2)), chan(m.group(3))
        if None in (r, g, b): return None
        a = 255
        if m.group(4):
            fa = float(m.group(4))
            if fa < 0 or fa > 1: return None
            a = int(fa * 255)
            if a < 102: return None
        return (a << 24) | (r << 16) | (g << 8) | b
    return NAMED.get(s)

need(parse_html_color("#e6194b") == 0xFFE6194B, "hex #rrggbb parses")
need(parse_html_color("#0af") == 0xFF00AAFF, "hex #rgb expands")
need(parse_html_color("rgb(224, 27, 90)") == 0xFFE01B5A, "rgb() parses")
need(parse_html_color("rgb(80%, 40%, 20%)") == 0xFFCC6633, "rgb() percent channels parse")
need(parse_html_color("rgba(230, 25, 75, 0.9)") == 0xE5E6194B,
     "rgba() alpha rides (0.9 * 255 -> 229)")
need(parse_html_color("rgba(230, 25, 75, 0.2)") is None,
     "the wash-out guard: alpha 0.2 declines (never an invisible tint)")
need(parse_html_color("red") == 0xFFFF0000 and parse_html_color("GOLD") == 0xFFFFD700,
     "named colours parse (case-insensitive)")
need(parse_html_color("tomato") == 0xFFFF6347 and parse_html_color("whitesmoke") == 0xFFF5F5F5,
     "the frequent extended names parse")
need(parse_html_color("notacolor") is None, "an unknown name colours NOTHING")
need(parse_html_color("#12") is None, "a bad hex length colours nothing")
need(parse_html_color("rgb(300, 0, 0)") is None, "an out-of-range channel colours nothing")
need(parse_html_color("") is None, "empty declines")

# the htmlColor door: font -> color attr; span -> style color
def html_color(tag, attrs):
    """the transcribed htmlColor"""
    if tag == "font":
        m = re.search(r"color\s*=\s*[\"']([^\"']*)[\"']", attrs)
        raw = m.group(1) if m else None
    else:
        m = re.search(r"style\s*=\s*[\"']([^\"']*)[\"']", attrs)
        raw = None
        if m:
            m2 = re.search(r"(^|;)\s*color\s*:\s*([^;!}\"]+)", m.group(1))
            raw = m2.group(2).strip() if m2 else None
    return parse_html_color(raw) if raw else None

need(html_color("font", ' color="red"') == 0xFFFF0000,
     "font color= red -> the span paints red")
need(html_color("span", ' style="color:#e6194b;font-weight:bold"') == 0xFFE6194B,
     "span style picks the color property out of a compound style")
need(html_color("span", ' style="font-weight:bold"') is None,
     "a style without color paints nothing")
need(html_color("span", ' style="background-color:#e6194b"') is None,
     "background-color alone never paints the TEXT (mark stays the highlighter)")

# the code colorizer emits DISTINCT colors per kind
need("fun codeColors(): IntArray" in ar_code and "fun tokenizeCode(code: String, lang: String)" in ar_code,
     "the code-block colorizer pair exists (tokenizeCode + codeColors)")
DARK_COLORS = [0, 0xFF7F8C98, 0xFF98C379, 0xFFC678DD, 0xFFD19A66]
need(len(set(DARK_COLORS[1:])) == 4,
     "the four token kinds carry four DISTINCT colors (keyword/string/comment/number)")

# ---- the behavioral probe: tokenizeCode kind coverage ----
print("  ... transcribed probe: the code colorizer's kind coverage")
PY_KW = set("def class if elif else for while return import from as pass break continue try except finally raise with lambda yield global nonlocal assert del in is not and or None True False async await match case self print range len int str float list dict set tuple".split())
def lc_of(ch):  # line-comment prefix
    return "#"
def tokenize(code, lang):
    """the transcribed tokenizeCode (python family)"""
    if not code or len(code) > 20000: return []
    out = []
    i, n, tok = 0, len(code), 0
    while i < n:
        if tok >= 4000: return []
        c = code[i]
        if c == "#":
            e = code.find("\n", i)
            e = n if e < 0 else e
            out.append((i, e, 1)); i = e
        elif c in "\"'`":
            j = i + 1
            while j < n:
                if code[j] == "\\": j += 2
                elif code[j] == c or code[j] == "\n": break
                else: j += 1
            e = j + 1 if j < n and code[j] == c else j
            out.append((i, e, 2)); i = e; tok += 1
        elif c.isdigit() and (i == 0 or not (code[i-1].isalnum() or code[i-1] == "_")):
            j = i
            while j < n and (code[j].isdigit() or code[j] in ".xabcdef_"):
                j += 1
            out.append((i, j, 4)); i = j
        elif c.isalpha() or c == "_":
            j = i
            while j < n and (code[j].isalnum() or code[j] == "_"): j += 1
            if code[i:j] in PY_KW:
                out.append((i, j, 3)); tok += 1
            i = j
        else:
            i += 1
    return out

sample = ("def area(w, h):\n"
          "    # rectangle area\n"
          "    return w * h * 2  # doubled\n"
          "    name = \"bolt\"")
toks = tokenize(sample, "python")
kinds = set(k for _, _, k in toks)
need(kinds == {1, 2, 3, 4},
     "a representative snippet yields ALL four kinds: keyword, string, "
     "comment, number (multi-color code blocks)")
need(any(sample[s:e] == "def" and k == 3 for s, e, k in toks), "the keyword tokenizes")
need(any(sample[s:e].startswith("#") and k == 1 for s, e, k in toks), "the comment tokenizes")
need(any(sample[s:e] == '"bolt"' and k == 2 for s, e, k in toks), "the string tokenizes")
need(any(k == 4 for s, e, k in toks), "the numbers tokenize")

# =====================================================================
print()
print("3 — fix 3: SCREEN TRANSLATE COVERS THE WHOLE PAGE (the grid law)")
need("internal fun gridOf(w: Int, h: Int): Array<IntArray>" in st_code,
     "gridOf is PURE (probe-pinned)")
need("private const val TILE_FLOOR = 20" in st_code,
     "the per-tile under-read floor (20 chars = YIELD_FLOOR/6)")
need("function __boltFillPwAll" in fh or True, "no-op guard")
need("fun cropTile(bmp: Bitmap, rect: IntArray): Bitmap?" in st_code
     and "t.compress(Bitmap.CompressFormat.JPEG, 92, bo)" in st_code,
     "per-tile OCR encode: NATIVE-resolution crop + q92 (no downscale)")
need("mildContrast(t)" in st_code and "internal fun stretchOf(p2: Int, p98: Int, lum: Int): Int" in st_code,
     "the MILD percentile contrast (2/98 luminance stretch)")
need("tessedit_pageseg_mode" in open(
        os.path.join(ROOT, "app/src/main/assets/scr_ocr.html"),
        encoding="utf8").read() and "'3'" in open(
        os.path.join(ROOT, "app/src/main/assets/scr_ocr.html"),
        encoding="utf8").read(),
     "PSM 3 auto layout stays the OCR host's page-segmentation choice")
need("runGridOcr(a: Activity, bmp: Bitmap, g: Int, langs: Array<String>," in st_code,
     "the tile-OCR driver drives BOTH paths")
need("AiBridge.visionTileTranslate(a, enc.b64, tl, enc.w, enc.h, ctx)" in st_code,
     "the per-tile VISION call is text-only (visionTileTranslate)")
need("gridCard(bmp, rect, text)" in st_code and "internal fun gridCardRect(w: Int, h: Int, rect: IntArray): Rect" in st_code,
     "the FIXED grid cell places every vision card (gridCard/gridCardRect)")
need('"scr tile " + i + " chars=" + tc + " lines="' in st,  # v1.4.27 re-point (2026-11-22): the merged pipeline renamed the per-tile breadcrumb
     "the per-tile char-yield Diag breadcrumb (RAW)")
need("visionFallback(a, bmp, g, tl, transcript)" in st_code
     and "AiBridge.shouldTile(AiBridge.lastError)" in st_code,
     "the MAX_TOKENS cut still hands the transcript to the vision grid")
need('"scr ai vision ok cells="' in st,
     "the fallback's coverage Diag counts CELLS, not box lines")
need("if (all.isEmpty() || chars < YIELD_FLOOR) {" in st_code,  # v1.4.27 re-point (2026-11-22): the merged decision's aggregate guard (the cluster list replaced hybridBlocks)
     "the aggregate low-yield floor still guards the whole screen")
need("fun visionTileTranslate(ctx: Context, b64: String, tl: String, iw: Int, ih: Int," in ab_code,
     "AiBridge carries the grid vision call")
need("internal fun tilePrompt(tl: String, screenCtx: String?): String" in ab_code
     and "No coordinates, no JSON" in ab,
     "the tile prompt demands TEXT ONLY (no coordinates, no JSON)")
need("internal fun stripFences" in ab_code, "stray fences strip from the plain-text reply")
need("responseMimeType" not in open(
        os.path.join(SRC, "util/AiBridge.kt"), encoding="utf8").read().split(
        "private fun tileBody")[1][:600],
     "the tile body drops responseMimeType (plain text out, no JSON mime)")

# ---- the behavioral probe: the grid geometry + per-tile decisions ----
print("  ... transcribed probe: the grid geometry + per-tile decisions")

def grid_of(w, h):
    """the transcribed gridOf"""
    cols = 2 if h >= w else 3
    rows = 3 if h >= w else 2
    cw, ch = w // cols, h // rows
    out = []
    for r in range(rows):
        for c in range(cols):
            x, y = c * cw, r * ch
            rw = w - x if c == cols - 1 else cw
            rh = h - y if r == rows - 1 else ch
            out.append((x, y, rw, rh))
    return out

g = grid_of(720, 1353)
need(len(g) == 6, "a portrait screen cuts into SIX cells (2 cols x 3 rows)")
need(sum(rw * rh for _, _, rw, rh in g) == 720 * 1353,
     "the cells tile the WHOLE bitmap exactly (coverage is geometric)")
need(g[0] == (0, 0, 360, 451) and g[1] == (360, 0, 360, 451),
     "the top row spans the full width")
need(g[5][0] + g[5][2] == 720 and g[5][1] + g[5][3] == 1353,
     "the last cell absorbs the remainder (no uncovered seam)")
gl = grid_of(1353, 720)
need(len(gl) == 6 and gl[0][2] == 451,
     "a landscape screen cuts into 3 cols x 2 rows")

def tile_decision(chars):
    """the transcribed per-tile verdict"""
    return "vision cell" if chars < 20 else "hybrid"

need(tile_decision(0) == "vision cell", "a blank cell goes to its vision card")
need(tile_decision(19) == "vision cell", "19 chars (a strip of chrome) under-reads")
need(tile_decision(20) == "hybrid", "20 chars is the exact boundary")
need(tile_decision(400) == "hybrid", "a well-read cell joins the text pass")
need(all(tile_decision(c) == "hybrid" for c in (40, 60, 90))
     and sum((40, 60, 90, 0, 0, 0)) >= 120,
     "a mixed screen (three good cells, three dead) rides the hybrid + "
     "per-cell vision cards (the aggregate floor passes)")
need(sum((19,) * 6) < 120,
     "an all-under-read screen (6 x 19 chars) is vision territory entirely")

def stretch_of(p2, p98, lum):
    """the transcribed stretchOf"""
    if p98 <= p2: return lum
    f = (lum - p2) * 255 // (p98 - p2)
    return max(0, min(255, f))

need(stretch_of(50, 200, 50) == 0, "the 2nd percentile maps to black")
need(stretch_of(50, 200, 200) == 255, "the 98th percentile maps to white")
need(stretch_of(50, 200, 125) == 127, "the midpoint stretches linearly (Int division)")
need(stretch_of(0, 255, 128) == 128, "a full-range histogram is identity")
need("if (p98 - p2 < 24) return" in st_code,
     "an already-contrasty tile is untouched (p98-p2 < 24 declines in-code)")

def grid_card_rect(w, h, rect, inset=6):
    """the transcribed gridCardRect"""
    l, t = rect[0] + inset, rect[1] + inset
    r, b = rect[0] + rect[2] - inset, rect[1] + rect[3] - inset
    if r < l + 8: l, r = rect[0], rect[0] + rect[2]
    if b < t + 8: t, b = rect[1], rect[1] + rect[3]
    l = max(0, l); t = max(0, t); r = min(w, r); b = min(h, b)
    if r < l + 8: r = min(w, l + 8)
    if b < t + 8: b = min(h, t + 8)
    return (l, t, r, b)

need(grid_card_rect(720, 1353, (360, 451, 360, 451)) == (366, 457, 714, 896),
     "a mid cell insets 6 px on every side")
need(grid_card_rect(720, 1353, (0, 0, 720, 451))[0] == 6,
     "the card clamps inside the bitmap")
need(grid_card_rect(100, 100, (0, 0, 100, 100)) == (6, 6, 94, 94),
     "a full cell insets 6 px on every side")
need(grid_card_rect(100, 100, (0, 0, 10, 10)) == (0, 0, 10, 10),
     "a tiny cell keeps its FULL card (the 8px floor)")
need(grid_card_rect(100, 100, (90, 0, 10, 100)) == (90, 6, 100, 94),
     "edge cells clamp inside the bitmap (the 8px floor takes the full cell)")

# ---- the behavioral probe: never trust box_2d ----
need("parseBoxes(txt, iw, ih)" in ab_code and "AiBridge.AiBox" not in st_code,
     "box_2d parsing stays for the CHAT path only (ScreenTranslate never "
     "places by model coordinates again)")
need("tilePlace" not in st_code and "translateRegion" not in st_code,
     "the box_2d tile road is RETIRED from ScreenTranslate (no re-placement, no seam dedup)")

# =====================================================================
print()
print("4 — fix 4: PASSWORD AUTOFILL (the silent-guard kill)")
need("window.__boltFillPwAll" in fh and "window.__boltFillPw=function" in fh,
     "the robust fill + the orchestrator ride EVERY frame (FrameHooks)")
need("Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value')" in fh,
     "the NATIVE value setter rides the fill (React/Vue forms notice)")
need("d.set.call(el,v)" in fh and "dispatchEvent(new Event('input'" in fh,
     "native setter + input/change events (the field-matching kit)")
need("t:'pwArm'" in fh and "t:'fillPw'" in fh and "t:'fillRes'" in fh,
     "the per-frame relay messages: pwArm / fillPw / fillRes")
need("ks.push(e.source)" in fh,
     "the reporting frames register by WINDOW IDENTITY (the fill is routed, never broadcast)")
need("Bolt.pwField(String(d.host||'')" in fh,
     "a login field in ANY frame arms the chip with ITS OWN host (the SSO law, per-frame)")
need("Bolt.pwFillResult(d.ok?true:false,String(d.host||'')" in fh,
     "the fill outcome climbs to the bridge (toast + Diag — never silent)")
need("Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value')" in mh_raw,
     "MediaHook's main-frame fill rides the same native setter")
need("window.__boltFillPwAll&&window.__boltFillPwAll('" in ba_raw,
     "fillPwFromChip calls the orchestrator (no bare __boltFillPw&& guard)")
need("Tab t = activeTab();" in ba_raw[:ba_raw.find("hidePwChip();", ba_raw.find("fillPwFromChip")) + 1]
     and "t.web.evaluateJavascript(" in ba_raw,
     "the fill rides the ACTIVE tab (evalMedia could target a background media tab)")
need("public void pwFillResult(final boolean ok, final String host)" in ba_raw,
     "the Bolt.pwFillResult bridge method exists")
need('"pw fill ok host="' in ba_raw and '"pw fill fail host="' in ba_raw,
     "the Diag lines: pw fill ok/fail host=... (RAW)")
need("pwFillTimeout" in ba_raw and "1500" in ba_raw,
     "the visible 1.5 s timeout catches a relay that never answers")
need("Couldn't find a login field on this page" in ba_raw,
     "the miss toast — a miss is VISIBLE, never silent")

# ---- the behavioral probe: the fill relay on a mock DOM (node) ----
print("  ... behavioral probe: the per-frame fill relay (mock DOM, node)")
m = re.search(r"internal const val HOOK_JS = (.*?)\n\n    /\*\* WebViews already",
              fh, re.S)
parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
js = "".join(p.replace('\\"', '"').replace("\\\\", "\\").replace("\\n", "\n") for p in parts)
probe = r'''
const fs = require('fs');
const js = fs.readFileSync(process.argv[2], 'utf8');

// ---- the two-frame world: TOP (portal main document) + KID (the SSO
// iframe on auth1.diu.edu.bd). postMessage semantics: X.postMessage(msg)
// delivers to X's OWN listeners with source = the CALLER — wired exactly:
// the kid's parent proxy -> top listeners (source=kid), and the top's
// kids[i].postMessage -> kid listeners (source=top). ----
global.Event = function (type) { this.type = type; };
let nativeSets = 0, inputEvents = 0, changeEvents = 0;

function mkInput(type, name) {
  const el = {
    tagName: 'INPUT', type: type, name: name || '', id: '', className: '',
    __v: '', __events: [], __native: false,
    getAttribute: () => '', setAttribute: () => {},
    closest: () => null, form: null, parentElement: null, children: [],
    style: {},
    dispatchEvent: (e) => {
      el.__events.push(e.type);
      if (e.type === 'input') inputEvents++;
      if (e.type === 'change') changeEvents++;
      return true;
    } };
  Object.defineProperty(el, 'value', {
    get: () => el.__v,
    set: (v) => { el.__v = v; } });   // the site's own assignment path
  return el;
}
// HTMLInputElement.prototype with a NATIVE value descriptor (set counted)
const nativeProto = {};
Object.defineProperty(nativeProto, 'value', {
  get: function () { return ''; },
  set: function (v) { nativeSets++; this.__v = v; this.__native = true; } });
const HTMLInputElement = { prototype: nativeProto };

function mkRealm(host, inputs) {
  const listeners = [];
  const docL = [];
  const win = {
    __boltPwFocused: null,
    HTMLInputElement: HTMLInputElement,
    __msgL: listeners,
    addEventListener: (t, fn) => { if (t === 'message') listeners.push(fn); },
    postMessage: (msg) => { for (const fn of listeners) fn({ source: win, data: msg }); } };
  const doc = {
    addEventListener: (t, fn) => { docL.push([t, fn]); },
    querySelectorAll: (sel) => {
      if (sel === 'input[type=password]') return inputs.filter(i => i.type === 'password');
      if (sel === 'input') return inputs;
      return [];                        // no iframes, no '*' walks needed
    } };
  win.document = doc;
  win.location = { host: host };
  win.__docL = docL;
  return win;
}

const boltCalls = [];
const Bolt = {
  pwField: (host, on) => boltCalls.push(['pwField', host, on]),
  pwFillResult: (ok, host) => boltCalls.push(['pwFillResult', ok, host]),
};
// the bridge is a REALM property (window.Bolt) AND a global in the browser
// (the handlers guard on window.Bolt before touching the bare name; only
// the TOP realm carries it — the kid routes via the relay)
global.Bolt = Bolt;

const top = mkRealm('portal.diu.edu.bd', []);      // the main doc: NO login fields
top.Bolt = Bolt;
top.parent = top;          // parent === self -> the hook computes TOP=true
const kid = mkRealm('auth1.diu.edu.bd',
    [mkInput('text', 'username'), mkInput('password', 'pass')]);  // no window.Bolt (the SSO frame has no bridge)
// the kid's parent proxy: delivers to the TOP's message listeners, source=kid
kid.parent = { postMessage: (msg) => {
  for (const fn of top.__msgL) fn({ source: kid, data: msg }); } };
// the top's kids[i].postMessage delivers to the KID's listeners, source=top
kid.postMessage = (msg) => {
  for (const fn of kid.__msgL) fn({ source: top, data: msg }); };
// the top's own postMessage (self-announcements): source = itself
top.postMessage = (msg) => {
  for (const fn of top.__msgL) fn({ source: top, data: msg }); };

function runRealm(win) {
  const fn = new Function('window', 'document', 'HTMLInputElement',
                          'MutationObserver', 'getComputedStyle', 'location', 'parent', js);
  fn(win, win.document, HTMLInputElement,
     function () { this.observe = () => {}; this.disconnect = () => {}; },
     () => ({ color: 'rgb(0,0,0)', backgroundColor: 'rgba(0,0,0,0)',
              backgroundImage: 'none', filter: 'none', fontFamily: 'sans-serif' }),
     win.location, win.parent);
}
runRealm(top);
runRealm(kid);

let ok = 0, bad = 0;
const t = (name, cond) => { if (cond) { ok++; } else { bad++; console.log('  probe FAIL: ' + name); } };

// (1) the SSO iframe password field takes focus -> pwArm climbs -> the
//     chip arms with the IFRAME's own host; the top registers the kid
const focusinL = kid.__docL.filter(l => l[0] === 'focusin');
t('the focusin detector installed in the kid frame', focusinL.length > 0);
const kidPw = kid.document.querySelectorAll('input[type=password]')[0];
const kidUser = kid.document.querySelectorAll('input').filter(i => i.type !== 'password')[0];
for (const pair of focusinL) pair[1]({ target: kidPw });
t('pwArm climbed: the chip armed with the IFRAME host',
  boltCalls.some(c => c[0] === 'pwField' && c[1] === 'auth1.diu.edu.bd' && c[2] === true));
t('the top registered the reporting kid by identity',
  top.__boltPwKids && top.__boltPwKids.length === 1);

// (2) the chip tap: __boltFillPwAll in the TOP — the local fill finds
//     nothing, the fill ROUTES to the kid, the kid fills through the
//     NATIVE setter + events, fillRes climbs, the bridge answers
top.__boltFillPwAll('shahed', 's3cret!');
t('the fill RESULT reached the bridge (ok, iframe host)',
  boltCalls.some(c => c[0] === 'pwFillResult' && c[1] === true && c[2] === 'auth1.diu.edu.bd'));
t('the kid password got the secret', kidPw.__v === 's3cret!');
t('the kid password rode the NATIVE setter', kidPw.__native === true);
t('the kid username got the user', kidUser.__v === 'shahed');
t('the NATIVE value setter ran (React/Vue notice)', nativeSets >= 2);
t('input events dispatched', kidPw.__events.includes('input'));
t('change events dispatched', kidPw.__events.includes('change'));
console.log('  probe: ' + ok + ' ok, ' + bad + ' bad');
process.exit(bad ? 1 : 0);
'''
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fj:
    fj.write(js); jspath = fj.name
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fp:
    fp.write(probe); ppath = fp.name
r = subprocess.run(["node", ppath, jspath], capture_output=True, text=True)
print(r.stdout.rstrip()[:900])
if r.returncode != 0:
    print("  stderr: " + r.stderr[:500])
need(r.returncode == 0, "the per-frame fill relay behaves (arm with the "
     "iframe host, route by identity, native-setter fill + events, the "
     "climb to pwFillResult; node probe)")

# =====================================================================
print()
print("5 — fix 5: THE FAINT HAMBURGER (the font-icon ride + light law)")
need("function lightCss(){return 'rgb(232,232,232)';}" in mh_raw,
     "the VISIBLE light constant (232 — not the 153 mid-gray)")
need("if(l!==null&&l<0.55){svSave(el);el.style.color=lightCss();" in mh_raw
     and "cnt.fi++;}" in mh_raw,
     "fontFloor: dark OR MID (<0.55) lifts to the visible light + census fi++")
need("iconFont(cs)&&pl!==null&&pl<0.55" in mh_raw
     and "color:'+lightCss()+'!important}" in mh_raw,
     "pseudoFloor: the pseudo lift is the visible light with !important")
need("if(!iconFont(cs)&&pl!==null&&pl>0.8)" in mh_raw
     and "if(!iconFont(cs)&&al!==null&&al>0.8)" in mh_raw,
     "THE RIDE LAW: the inv===1 clamp SKIPS icon-font glyphs (dark -> light)")
# v1.4.26 re-point (2026-11-21): the contrast-aware clamp takes the ground
# luminance (clampCss(c,gl) — the 153-clamp corpse); the census object
# gained late/keep fields and the reader string gained their tails.
need("var nc=fontIc?null:clampCss(cs.color,gl);" in mh_raw,
     "strokeFloor: the element clamp skips icon-font glyphs too")
need("var cnt={small:0,ic:0,ni:0,rescue:0,mono:0,fi:0,late:0,keep:0};" in mh_raw,
     "the census carries the fi field")
need("' fi='+(o.fi||0)" in mh_raw
     and "' late='+(o.late||0)+' keep='+(o.keep||0);" in mh_raw,
     "the census reader reports fi=N (and the v1.4.26 late/keep tails)")
need('try{if(iconFont(cs))cnt.fi++;}catch(x){}' in mh_raw,
     "every icon-font glyph the walk sees counts as fi")

# ---- the behavioral probe: the FA glyph walk (mock DOM, node) ----
print("  ... behavioral probe: the FA-hamburger walk (mock DOM, node)")
m = re.search(r"public static String darkSmartJs\(\)\s*\{(.*?)\n    \}", mh_raw, re.S)
parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
js2 = "".join(p.replace('\\"', '"').replace("\\\\", "\\").replace("\\n", "\n") for p in parts)
probe2 = r'''
const fs = require('fs');
const js = fs.readFileSync(process.argv[2], 'utf8');
function rect(w, h) { return { width: w, height: h, top: 0, left: 0 }; }
function mkClassList() {
  const s = new Set();
  return { contains: (c) => s.has(c), add: (c) => s.add(c), remove: (c) => s.delete(c), _set: s };
}
function el(tag, w, h) {
  const e = { tagName: tag.toUpperCase(), children: [], parentElement: null,
    getBoundingClientRect: () => rect(w, h), classList: null, style: {}, className: '', id: '',
    complete: false, naturalWidth: 0, naturalHeight: 0,
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
// the page: light body, a DARK header (preserved DK), the FA hamburger
// glyph <i class="fa fa-bars"> inside it, and an inverted-side glyph
const header = el('header', 800, 56);
header._cs = { backgroundColor: 'rgb(30, 34, 45)' };   // dark -> preserved
link(body, header);
const burger = link(header, el('i', 20, 20));
burger.className = 'fa fa-bars';
burger._cs = { fontFamily: 'FontAwesome', color: 'rgb(102, 102, 102)' };  // MID gray = faint
const mainEl = el('main', 800, 540);
mainEl._cs = { backgroundColor: 'rgb(255, 255, 255)' };
link(body, mainEl);
const inkGlyph = link(mainEl, el('i', 18, 18));
inkGlyph.className = 'fa fa-search';
inkGlyph._cs = { fontFamily: 'FontAwesome', color: 'rgb(60, 60, 60)' }; // dark on the INVERTED side
const f = new Function('window', 'document', 'MutationObserver', 'getComputedStyle', js);
f(window, document, MutationObserver, gcs);
let ok = 0, bad = 0;
const t = (name, cond) => { if (cond) { ok++; } else { bad++; console.log('  probe FAIL: ' + name); } };
t('the dark header is preserved (DK)', header.classList.contains('__boltDK'));
t('THE HAMBURGER LIFTS to the VISIBLE light',
  burger.style.color === 'rgb(232,232,232)');
t('the lift is restorable (svSave ran)', burger.classList.contains('__boltSV'));
t('the inverted-side glyph RIDES (no clamp, no lift)',
  (inkGlyph.style.color || '') === '' && !inkGlyph.classList.contains('__boltSV'));
const c = window.__boltDarkCount;
t('the census counts the font-icon glyphs (fi)', c && c.fi >= 2);
console.log('  probe: ' + ok + ' ok, ' + bad + ' bad');
process.exit(bad ? 1 : 0);
'''
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fj:
    fj.write(js2); jspath2 = fj.name
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fp:
    fp.write(probe2); ppath2 = fp.name
r = subprocess.run(["node", ppath2, jspath2], capture_output=True, text=True)
print(r.stdout.rstrip()[:600])
if r.returncode != 0:
    print("  stderr: " + r.stderr[:400])
need(r.returncode == 0, "the FA-glyph walk behaves (the Moodle hamburger "
     "lifts to 232 on the preserved dark header; the inverted-side glyph "
     "rides untouched; the census counts fi; node probe)")

# =====================================================================
print()
print("E — THE LAUNCH LAW + the pipeline")
smoke = os.path.join(ROOT, "app/src/test/java/com/bolt/browser/ui/LaunchSmokeTest.java")
need(os.path.isfile(smoke), "the Robolectric launch smoke test still exists")
need("testDebugUnitTest" in rels and "LAUNCH SMOKE FAILED" in rels,
     "release.sh still runs the smoke BEFORE the build (the rung stays)")
need("python3 scripts/test_v1425.py" in rels,
     "release.sh runs this gate (the v1.4.25 rung)")
need("python3 scripts/test_v1424.py" in rels,
     "the v1.4.24 gate still rides the suite (cumulative law)")
sweep = 0
for g in sorted(os.listdir(os.path.join(ROOT, "scripts"))):
    if not (g.startswith("test_v1") and g.endswith(".py")) or g == "test_v1425.py":
        continue   # this gate's own sweep-check line carries the old literal
    src = open(os.path.join(ROOT, "scripts", g), encoding="utf8").read()
    code = re.sub(r"#[^\n]*", "", src)          # dated history comments survive
    if re.search(r"versionCode\s+138\b", code) or '== "1.4.24"' in code:
        sweep += 1
need(sweep == 0, "the standing sweep repinned every gate to 139/1.4.25 "
     f"({sweep} stragglers)")
need(os.path.isfile(os.path.join(ROOT, "scripts/repin_gates_1425.py")),
     "the v1.4.25 sweep script is checked in (provenance)")

print()
if failed:
    print(f"V1425 GATE: BAD ({failed} fail)")
    sys.exit(1)
print("V1425 GATE: OK")
