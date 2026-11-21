#!/usr/bin/env python3
"""v1.4.23 release gate — the critical rebuild (fresh implementation on the
clean v1.4.21 source).

The story: the v1.4.22 release was reconstructed by DECOMPILING the shipped
APK and porting R8-obfuscated bytecode back to source. Every static gate
was green; the app CRASHED ON LAUNCH on the user's Realme RMX2020 /
Android 10 — no UI, no crash page, clean reinstall included. v1.4.23
re-implements the four fixes from scratch as hand-written code on the
v1.4.21 tag and adds THE LAUNCH LAW: the release now verifies the app
actually OPENS (Robolectric: Application.onCreate -> the launcher
activity -> buildHomeView) before anything publishes.

This gate pins the four fresh fixes + the launch law itself:
  A — the forced-dark DEFAULT-FLIP round (MediaHook.darkSmartJs): small
      media rides the page inversion BY DEFAULT (uiCtx guessing retired),
      the raiseCss font-icon lift, the bounded contrast rescue, the census.
  B — the engine-logo RASTER TURN: official 64x64 PNGs, the home pill, the
      icon-row picker, the homeGen follow at BOTH entry points.
  C — the screen-translate HYBRID: OCR-first for accurate text + boxes, ONE
      pure-text numbered-lines request with full context, vision demoted to
      the genuine-cut/OCR-blind fallback with the transcript as context.
  D — the AiRender FOUR-PACK: HTML span stack, nested quotes, tappable
      tasks, footnotes + ins.
  E — THE LAUNCH LAW: the Robolectric launch smoke exists, runs in
      release.sh BEFORE the build, and the autobox-ternary corpse class it
      caught is gone.

Behavioral probes: the dark walk runs against a mock DOM (node), the
numbered-lines parser runs transcribed, and the commonmark four-pack runs
COMPILED against the exact jars the app ships.
"""
import os
import re
import subprocess
import sys
import tempfile

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

mh_raw = open(os.path.join(SRC, "media/MediaHook.java"), encoding="utf8").read()
ba_raw = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
ba = ba_raw
ui = open(os.path.join(SRC, "util/Ui.java"), encoding="utf8").read()
sia = open(os.path.join(SRC, "ui/SettingsItemsActivity.java"), encoding="utf8").read()
ab = open(os.path.join(SRC, "util/AiBridge.kt"), encoding="utf8").read()
st = open(os.path.join(SRC, "util/ScreenTranslate.kt"), encoding="utf8").read()
ar = open(os.path.join(SRC, "util/AiRender.kt"), encoding="utf8").read()
gradle = open(os.path.join(ROOT, "app/build.gradle"), encoding="utf8").read()
rels = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()
strings = open(os.path.join(RES, "values/strings.xml"), encoding="utf8").read()


def strip(src):
    """blank comments and string bodies so pins read CODE, not prose"""
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    # line-bound: a quote must never pair across a newline (the greedy
    # span ate whole method bodies between unrelated literals)
    src = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', src)
    return src


mh = mh_raw   # RAW — the dark JS lives inside Java string literals; the
              # stripped form would blank every payload pin (the v1.4.20/21
              # gates read MediaHook raw for the same reason)
ba_code = strip(ba_raw)
ab_code = strip(ab)
st_code = strip(st)
ar_code = strip(ar)

print("V0 — version + changelog")
need(re.search(r"versionCode\s+140\b", gradle) is not None, "versionCode 140")
need(re.search(r'versionName\s+"1\.4\.26"', gradle) is not None,
     'versionName "1.4.26"')
need("<item>1.4.23|" in strings, "the 1.4.23 About-changelog entry landed")
need("the release now verifies the app OPENS" in strings,
     "the changelog tells the user the launch-verification law honestly")

print()
print("A — fix 1: the forced-dark DEFAULT-FLIP round (fresh darkSmartJs)")
need("function smallOf(el)" in mh and "function bannerish(el)" in mh
     and "function photoCtx(el)" in mh and "function ridesFlip(el)" in mh,
     "the fresh classifier family: smallOf/bannerish/photoCtx/ridesFlip")
need("function uiCtx(el){" not in mh and "function uiIcon(el){" not in mh,
     "the uiCtx/uiIcon context-guessing family is RETIRED")
need("t==='ARTICLE'||t==='MAIN'||t==='P'||t==='FIGURE'" in mh,
     "photo-classed content: article/main/p/figure ancestors within 6 levels")
need("(mx/mn)>=2.5" in mh, "banner-shaped content: aspect ratio >= 2.5 stays un-inverted")
need("function raiseCss(c)" in mh and "if(y>=102)return null;" in mh,
     "the UPWARD clamp exists: a pinned-dark glyph lifts to the 153 mid-gray")
# v1.4.25 re-point (2026-11-21): the faint-glyph corpse — the raise is
# now the VISIBLE LIGHT (232), the net widened to lum < 0.55 (a MID gray
# on a dark header was the Moodle hamburger's faint shape)
need("function fontFloor(el,cs)" in mh and "l<0.55" in mh
     and "el.style.color=lightCss()" in mh,
     "the font-icon guarantee: a dark/mid icon-font glyph inside a "
     "preserved dark subtree lifts to the VISIBLE light (232)")
# v1.4.25 re-point (2026-11-21): the pseudo lift goes to the visible
# light with !important specificity (never the 153 mid-gray); the
# inv===1 clamp SKIPS icon-font glyphs (the RIDE law)
need("function pseudoFloor(el,cs,inv,gl){" in mh   # v1.4.26 re-point (2026-11-21): the clamp kit takes the ground luminance (the contrast-aware target)
     and "iconFont(cs)&&pl!==null&&pl<0.55" in mh
     and "color:'+lightCss()+'!important}" in mh,
     "the pseudo raise half: dark/mid ::before/::after glyphs on "
     "icon-font elements get generated VISIBLE-LIGHT !important rules")
need("function rescuePass(){" in mh and "function darkImgEl(el)" in mh
     and "function darkSvgEl(el)" in mh,
     "the bounded contrast rescue: SVG luminance + same-origin IMG canvas sample")
need("rCand<240&&rSave<80" in mh and "rCanvas>=40" in mh,
     "the rescue is bounded (240 candidates / 80 rescues / 40 canvas samples)")
need("window.__boltDarkCount=cnt" in mh
     and "var cnt={small:0,ic:0,ni:0,rescue:0,mono:0,fi:0,late:0,keep:0};" in mh,  # v1.4.24 re-point (2026-11-21): the census grew the mono field (the monochrome-glyph law)  # v1.4.25 re-point (2026-11-21): +fi (the font-icon glyph count — the FA-hamburger read-back)  # v1.4.26 re-point (2026-11-21): +late/keep (the deferred-IC and contrast-preserve read-backs)
     "the census publishes window.__boltDarkCount")
need("function clampCss(c,gl){" in mh   # v1.4.26 re-point (2026-11-21): the 153 corpse replaced by the contrast-aware target (the ySrc scale against the rendered ground)
     and "if(y<=153)return null;" in mh
     and "var ySrc=255*(1-T);" in mh,
     "the v1.4.19 downward clamp survived the rewrite (light clamps — now "
     "to the >=0.75 post-inversion target, never the fixed 153 corpse)")
need("el.style.strokeWidth='2'" in mh and "function bumpW(px)" in mh,
     "the stroke/fill floor + the <1.5px -> 2px thickness bump survived")
need("try{delete window.__boltDarkCount;}catch(e){}" in mh,
     "darkClearJs wipes the census with the run flag")
need('"dark icons " + host + " " + cnt' in ba_raw
     and "MediaHook.darkCountJs()" in ba_raw,
     "BrowserActivity reads the census back and logs dark icons <host> "
     "small=N ic=N ni=N rescue=N")

# ---- the behavioral mock-DOM probe (node; the dark walk end to end) ----
print("  ... behavioral probe: the fresh darkSmartJs against a mock DOM")
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
function qsa(root, sel) {
  const any = sel.includes('*');
  const want = sel.split(',').map((s) => s.trim().toUpperCase()).filter((s) => s && s !== '*');
  const out = [];
  (function walk(n) { for (const c of n.children) { if (any || want.includes(c.tagName)) out.push(c); walk(c); } })(root);
  return out;
}
body.querySelectorAll = (sel) => qsa(body, sel);
const divA = el('div', 300, 40); divA._cs = { backgroundColor: 'rgb(255, 255, 255)' };
link(body, divA);
const imgMenu = el('img', 24, 24); link(divA, imgMenu);          // A: bare small menu IMG
const imgBig = el('img', 400, 300); link(body, imgBig);          // B: big content photo
const imgBanner = el('img', 60, 14); imgBanner._dark = false; link(body, imgBanner); // C: light banner
const mainD = el('main', 800, 600); link(body, mainD);
const imgPhotoLight = el('img', 48, 48); imgPhotoLight._dark = false; link(mainD, imgPhotoLight); // D
const imgPhotoDark = el('img', 48, 48); imgPhotoDark._dark = true; link(mainD, imgPhotoDark);   // D2
const spanE = el('span', 30, 30); link(body, spanE);
const svgE = el('svg', 20, 20); svgE._cs = { fill: 'rgb(17, 17, 17)' }; link(spanE, svgE); // E
const cardF = el('div', 400, 200); cardF._cs = { backgroundColor: 'rgb(45, 45, 45)' }; link(body, cardF);
const glyphF = el('i', 18, 18); glyphF._cs = { fontFamily: 'Font Awesome 5 Free', color: 'rgb(26, 26, 26)' };
link(cardF, glyphF);
const f = new Function('window', 'document', 'MutationObserver', 'getComputedStyle', js);
f(window, document, MutationObserver, gcs);
let ok = 0, bad = 0;
const t = (name, cond) => { if (cond) { ok++; } else { bad++; console.log('  probe FAIL: ' + name); } };
t('A small menu IMG rides IC', imgMenu.classList.contains('__boltIC'));
t('B big photo stays media', !imgBig.classList.contains('__boltIC'));
t('C light banner stays content', !imgBanner.classList.contains('__boltIC'));
t('D light photo in main stays un-inverted', !imgPhotoLight.classList.contains('__boltIC'));
t('D2 dark photo rescued to IC', imgPhotoDark.classList.contains('__boltIC'));
t('E small dark svg rides IC', svgE.classList.contains('__boltIC'));
t('F dark card preserved DK', cardF.classList.contains('__boltDK'));
// v1.4.25 re-point (2026-11-21): the lift is the VISIBLE light (232,232,232) — the 153 mid-gray read faint
t('F pinned-dark glyph raised', /rgb\(232, ?232, ?232\)/.test(String(glyphF.style.color)));
const c = window.__boltDarkCount;
t('census small=5', c && c.small === 5);
t('census ic=3', c && c.ic === 3);
t('census rescue=1', c && c.rescue === 1);
console.log('  probe: ' + ok + ' ok, ' + bad + ' bad');
process.exit(bad ? 1 : 0);
'''
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fj:
    fj.write(js); jspath = fj.name
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fp:
    fp.write(probe); ppath = fp.name
r = subprocess.run(["node", ppath, jspath], capture_output=True, text=True)
print(r.stdout.rstrip())
need(r.returncode == 0, "the fresh dark walk behaves (11 mock-DOM verdicts "
     "+ the census counts; node probe)")

print()
print("B — fix 2: the engine-logo RASTER TURN")
marks = ("ic_engine_google", "ic_engine_ddg", "ic_engine_bing",
         "ic_engine_brave", "ic_engine_startpage")
for d in marks:
    png = os.path.join(RES, "drawable-nodpi", d + ".png")
    okf = os.path.isfile(png)
    size = os.path.getsize(png) if okf else 0
    need(okf and 1024 <= size <= 10240,
         f"the {d} mark ships as a tiny raster PNG ({size} B, drawable-nodpi)")
    if okf:
        with open(png, "rb") as fh:
            head = fh.read(33)
        w = int.from_bytes(head[16:20], "big"); h = int.from_bytes(head[20:24], "big")
        need(w == 64 and h == 64, f"the {d} PNG is exactly 64x64 ({w}x{h})")
    need(not os.path.isfile(os.path.join(RES, "drawable", d + ".xml")),
         f"the {d} vector is retired from res (no duplicate resource names)")
need(os.path.isdir(os.path.join(ROOT, "scripts/engine_vectors")),
     "the retired vectors are archived (scripts/engine_vectors)")
need(os.path.isfile(os.path.join(ROOT, "scripts/rasterize_engine_logos.py")),
     "the rasterizer is committed (offline-reproducible marks)")
need("case 0: return R.drawable.ic_engine_google;" in ba_code
     and "default: return R.drawable.ic_search;" in ba_code,
     "engineLogo's mapping is unchanged (0 Google, 1 DDG, 2 Bing, 3 Brave, 4 Startpage)")
need("int plogo = engineLogo(prefs.searchEngine());" in ba_code
     and '"engine pill set engine="' in ba_raw,
     "the home search pill carries the active mark (binding + breadcrumb)")
homegen_picker = ba_code[ba_code.find("protected void showEnginePicker("):]
homegen_picker = homegen_picker[:homegen_picker.find("protected void showImageSearchPicker")]
need("Fx.homeGen++;" in homegen_picker,
     "the omnibox picker bumps Fx.homeGen (the pill follows the switch)")
need("com.bolt.browser.util.Fx.homeGen++;" in sia,
     "the Settings row bumps Fx.homeGen too (BOTH entry points)")
need("Ui.choiceSheetIcons(this, getString(R.string.search_engine), names, marks," in ba_raw,
     "the engine picker rows carry their marks (Ui.choiceSheetIcons)")
need("public static void choiceSheetIcons(final Activity a, String title, String[] options," in ui,
     "the icon-row sheet exists in Ui")
need("engine pill set engine=" not in mh and "iv.setImageResource(icons[i]);" in ui,
     "the picker rows bind untinted brand drawables (never tinted)")

print()
print("C — fix 3: the screen-translate HYBRID (OCR first, ONE text pass)")
need("fun textTranslate(ctx: Context, lines: List<String>, tl: String): List<String?>?" in ab_code,
     "the pure-text translate stage exists (BLOCKING, worker-only)")
need("internal fun textLinesPrompt(tl: String, lines: List<String>, markBreaks: Boolean = false): String" in ab_code,  # v1.4.26 re-point (2026-11-21): the prompt grew the manga markBreaks flag (the ' ~ ' line-break marker)
     "the numbered-lines protocol prompt is PURE (probe-pinned)")
need("internal fun parseNumberedReply(txt: String, want: Int): Array<String?> {" in ab_code,
     "the numbered-reply parser is PURE (probe-pinned)")
need("Reply with ONLY the translated lines, one per input line" in ab,
     "the protocol: one reply line per input line, same numbers")
need("Use consistent terminology" in ab or "terminology consistent" in ab,
     "the protocol demands consistent terminology across the screen")
need('gc.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))' in ab,
     "thinkingLevel LOW rides the first try (quality; never thinkingBudget)")
need('if (r.first == 400) {' in ab_code and "textBody(p, c, false)" in ab_code,
     "ANY 400 drops the thinking field cleanly and retries (the v1.4.20 lesson)")
need("fun visionTranslateX(ctx: Context, b64: String, tl: String, iw: Int, ih: Int," in ab_code
     and "screenCtx.take(4000)" in ab_code,
     "the tile-context variant: the full-screen transcript rides tile requests")
need("fun ocrBlocks(json: String, bmp: Bitmap, enc: Enc)" in st_code,
     "the shared OCR block parser (BASIC + AI hybrid, one parse law)")
need("AiBridge.textTranslate(a, lines, tl)" in st_code,
     "the hybrid: OCR reads the text, ONE text request translates it")
need("private fun visionFallback(a: Activity, bmp: Bitmap, g: Int, tl: String," in st_code
     and "AiBridge.shouldTile(AiBridge.lastError)" in st_code,
     "the vision path is DEMOTED: fallback only, full-screen first, tiles on "
     "a genuine cut")
need("val transcript = lines.joinToString(\"\\n\")" in st
     and "visionFallback(a, bmp, g, tl, transcript)" in st_code,
     "the MAX_TOKENS cut hands the OCR transcript to the fallback as context")
need('"scr ai hybrid ok lines="' in st, "the hybrid delivery Diag line (RAW)")
need("If OCR cannot read the screen at all" in st and "sent instead" in st,
     "the privacy confirm tells the truth: the screenshot only rides the "
     "OCR-blind fallback (the sentence spans two literals — both pinned)")
need("val r = geminiTextTranslate(ctx, name, lines, tl, markBreaks)" in ab_code  # v1.4.26 re-point (2026-11-21): the text pass grew the manga markBreaks flag
     and "parseNumberedReply(txt, lines.size)" in ab_code,
     "the gemini text pass parses the numbered reply")
need('lastError = CUT_OFF_ERR' in ab_code and "got * 10 >= lines.size * 7" in ab_code,
     "coverage-gated delivery: only a genuine MAX_TOKENS cut under the 70% "
     "floor hands off (CUT_OFF_ERR)")

# ---- the numbered-reply parser probe (transcribed) ----
print("  ... behavioral probe: parseNumberedReply (transcribed)")
def parse_numbered(txt, want):
    out = [None] * want
    for raw in txt.split("\n"):
        line = raw.strip()
        if not line:
            continue
        m = re.match(r"^(\d{1,4})\s*\|\s?(.*)$", line)
        if not m:
            continue
        n = int(m.group(1))
        if n < 1 or n > want:
            continue
        t = m.group(2).strip()
        if not t:
            continue
        if out[n - 1] is None:
            out[n - 1] = t
    return out
out = parse_numbered("1|hello\n2|world\n7|stray prose ignored\n99|out of range\n\n3|third", 4)
need(out == ["hello", "world", "third", None],
     "the numbered parser: order, stray lines, range, repeats (probe)")

print()
print("D — fix 4: the AiRender FOUR-PACK")
need("implementation 'org.commonmark:commonmark-ext-task-list-items:0.24.0'" in gradle
     and "implementation 'org.commonmark:commonmark-ext-footnotes:0.24.0'" in gradle
     and "implementation 'org.commonmark:commonmark-ext-ins:0.24.0'" in gradle,
     "the three BSD-2 extension deps ride the build")
need("TaskListItemsExtension.create()," in ar
     and "InsExtension.create()," in ar
     and "FootnotesExtension.builder()" in ar and ".inlineFootnotes(true)" in ar,
     "all three extensions register (inlineFootnotes ON)")
need("is HtmlInline -> htmlTag(n.literal)" in ar_code
     and "is HtmlBlock -> htmlBlockLit(n.literal)" in ar_code,
     "the HtmlInline/HtmlBlock visitor branches")
need("fun htmlTag(lit: String) {" in ar_code
     and "fun closeTag(name: String) {" in ar_code
     and "fun closeAllTags() {" in ar_code,
     "the open/close span stack (htmlTag/closeTag/closeAllTags)")
span_kit = ('"mark" -> spans.add(BackgroundColorSpan(markBg()))',
            '"u", "ins" -> spans.add(UnderlineSpan())',
            '"del", "s", "strike" -> spans.add(StrikethroughSpan())',
            '"sub" -> { spans.add(SubscriptSpan()); spans.add(RelativeSizeSpan(0.72f)) }',
            '"sup" -> { spans.add(SuperscriptSpan()); spans.add(RelativeSizeSpan(0.72f)) }',
            '"b", "strong" -> spans.add(StyleSpan(Typeface.BOLD))',
            '"i", "em" -> spans.add(StyleSpan(Typeface.ITALIC))',
            '"code" -> { spans.add(TypefaceSpan("monospace"))',
            '"a" -> {')
need(all(s in ar for s in span_kit),
     "the span kit: mark/u/ins/del/s/sub/sup/b/strong/i/em/code/a")
need('ATTR_HREF.find(m.groupValues[2])' in ar_code,
     "href parses out of the opening tag literal (URLSpan)")
need('if (name == "br") { sb.append(\'\\n\'); return }' in ar,
     "<br> renders a newline (RAW source — no literal angle brackets anywhere)")
need("is TaskListItemMarker -> {" in ar_code
     and "class TaskSpan(val on: Boolean, val accent: Int) : ClickableSpan() {" in ar_code,
     "the task checkbox: a tappable ClickableSpan toggles the marker in the bubble")
need('"\\u2611" else "\\u2610"' in ar or "\\u2611" in ar,
     "the checkbox glyphs ride the render (checked/empty)")
need("is FootnoteReference -> {" in ar_code
     and "is FootnoteDefinition -> {" in ar_code
     and "fun footnotes() {" in ar_code,
     "footnotes: superscript refs + the appended trailer")
need("fnNum.getOrPut(n.label) { fnNum.size + 1 }" in ar_code,
     "the numbering is first-citation order")
need("val lvl = quoteLevel + 1" in ar_code
     and "QuoteSpan(Fx.dim(c))" in ar_code
     and "LeadingMarginSpan.Standard(" in ar_code,
     "nested quotes: per-level bar + indent, recursing")
need("is Ins -> {" in ar_code and "sb.setSpan(UnderlineSpan(), start, sb.length, 0)" in ar_code,
     "ins == underline (the delimiter form ++text++)")
need("[-*] \\[\\s*[xX]\\s*\\] " in ar or "Regex(\"(^|\\\\n)\\\\s*[-*] \\\\[" in ar,
     "looksMarkdown triggers on task lists")
need("</?[a-zA-Z][^>]*>" in ar, "looksMarkdown triggers on HTML tags")
need("\\\[\\\^" in ar,
     "looksMarkdown triggers on footnote refs (the [^ literal; RAW)")

# ---- the compiled+run commonmark probe (the exact jars the app ships) ----
print("  ... compiled probe: the four-pack parses (real commonmark jars)")
JARS = []
GRADLE_CACHE = os.path.expanduser("~/.gradle-home/caches/modules-2/files-2.1")
for grp, art in (("org.commonmark", "commonmark"),
                ("org.commonmark", "commonmark-ext-gfm-tables"),
                ("org.commonmark", "commonmark-ext-gfm-strikethrough"),
                ("org.commonmark", "commonmark-ext-task-list-items"),
                ("org.commonmark", "commonmark-ext-footnotes"),
                ("org.commonmark", "commonmark-ext-ins")):
    found = None
    base = os.path.join(GRADLE_CACHE, grp, art)
    if os.path.isdir(base):
        for dirpath, _, files in os.walk(base):
            for f in files:
                if f.endswith(".jar") and "sources" not in f:
                    found = os.path.join(dirpath, f)
    JARS.append(found)
tdl = tempfile.mkdtemp()
tdl = tempfile.mkdtemp()
ARTS = ("commonmark", "commonmark-ext-gfm-tables",
        "commonmark-ext-gfm-strikethrough", "commonmark-ext-task-list-items",
        "commonmark-ext-footnotes", "commonmark-ext-ins")
for i, art in enumerate(ARTS):
    if JARS[i] is None:
        url = ("https://maven.aliyun.com/repository/central/org/commonmark/"
               + art + "/0.24.0/" + art + "-0.24.0.jar")
        dst = os.path.join(tdl, art + ".jar")
        if subprocess.run(["curl", "-sfL", "-o", dst, url]).returncode == 0:
            JARS[i] = dst
need(all(JARS), "the six commonmark jars resolve (gradle cache or the pinned mirror)")
four = r'''
import org.commonmark.parser.Parser;
import org.commonmark.node.*;
import org.commonmark.ext.footnotes.*;
import org.commonmark.ext.task.list.items.*;
import org.commonmark.ext.ins.*;
public class FourPack {
    public static void main(String[] a) {
        Parser p = Parser.builder().extensions(java.util.Arrays.asList(
            org.commonmark.ext.gfm.tables.TablesExtension.create(),
            org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
            InsExtension.create(),
            FootnotesExtension.builder().inlineFootnotes(true).build())).build();
        Node doc = p.parse("- [x] done\n- [ ] todo\n\nA ref[^1] and ++ins++ and "
            + "<mark>marked</mark> <u>u</u> <del>d</del> <sub>sb</sub><sup>sp</sup> "
            + "<b>b</b> <code>cd</code> <a href=\"https://x.y\">link</a> <br> end\n\n"
            + "[^1]: the first footnote.\n\n> q1\n> > q2 inside\n");
        int task = 0, taskOn = 0, fnRef = 0, fnDef = 0, ins = 0, html = 0, quote = 0;
        java.util.List<String> lits = new java.util.ArrayList<>();
        java.util.Deque<Node> q = new java.util.ArrayDeque<>();
        q.add(doc);
        while (!q.isEmpty()) {
            Node x = q.poll();
            for (Node k = x.getFirstChild(); k != null; k = k.getNext()) {
                q.add(k);
                if (k instanceof TaskListItemMarker) { task++; if (((TaskListItemMarker)k).isChecked()) taskOn++; }
                if (k instanceof FootnoteReference) fnRef++;
                if (k instanceof FootnoteDefinition) fnDef++;
                if (k instanceof Ins) ins++;
                if (k instanceof HtmlInline) { html++; lits.add(((HtmlInline)k).getLiteral()); }
                if (k instanceof BlockQuote) quote++;
            }
        }
        boolean hasA = lits.stream().anyMatch(l -> l.startsWith("<a href=\"https://x.y"));
        boolean hasBr = lits.stream().anyMatch(l -> l.trim().equals("<br>"));
        boolean ok = task == 2 && taskOn == 1 && fnRef == 1 && fnDef == 1
            && ins == 1 && html >= 8 && quote == 2 && hasA && hasBr;
        System.out.println("task=" + task + " on=" + taskOn + " fnRef=" + fnRef
            + " fnDef=" + fnDef + " ins=" + ins + " html=" + html + " quote=" + quote
            + " a=" + hasA + " br=" + hasBr);
        System.out.println(ok ? "FOURPACK OK" : "FOURPACK BAD");
        System.exit(ok ? 0 : 1);
    }
}
'''
tdir = tempfile.mkdtemp()
with open(os.path.join(tdir, "FourPack.java"), "w") as fjs:
    fjs.write(four)
javac = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/javac"
javabin = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/java"
if not os.path.isfile(javac):
    javac, javabin = "javac", "java"
r = subprocess.run([javac, "-cp", ":".join(JARS), "-d", tdir,
                    os.path.join(tdir, "FourPack.java")], capture_output=True, text=True)
if r.returncode != 0:
    print("  compile fail: " + r.stderr[:400])
r2 = subprocess.run([javabin, "-cp", tdir + ":" + ":".join(JARS), "FourPack"],
                    capture_output=True, text=True)
print("  " + r2.stdout.strip())
need(r2.returncode == 0 and "FOURPACK OK" in r2.stdout,
     "the four-pack parses with the SHIPPED jars: 2 task markers (1 on), "
     "the footnote ref + definition, the ins node, 8+ HTML inline tokens "
     "(incl. the a-href + br), 2 nested quotes")

print()
print("E — THE LAUNCH LAW (the v1.4.22 lesson)")
smoke = os.path.join(ROOT, "app/src/test/java/com/bolt/browser/ui/LaunchSmokeTest.java")
need(os.path.isfile(smoke), "the Robolectric launch smoke test exists")
smoke_src = open(smoke, encoding="utf8").read()
need("Robolectric.buildActivity(BrowserActivity.class).setup().get()" in smoke_src,
     "it drives the REAL launcher activity through onCreate/onStart/onResume")
need("a.homeView" in smoke_src and "buildHomeView rendered the home view" in smoke_src,
     "it asserts the first home-tab render (buildHomeView)")
need("testDebugUnitTest" in rels and "LAUNCH SMOKE FAILED" in rels,
     "release.sh runs the smoke BEFORE the build and hard-fails on it")
need(re.search(r"setColorFilter\([a-zA-Z.]+ == R\.drawable\.ic_search \? .+ : null\)", ba_raw) is None,
     "the autobox corpse class is GONE: no (cond ? int : null) ternary feeds "
     "setColorFilter anywhere (the exact NPE the smoke caught on the "
     "launch path)")
need("else si.setColorFilter((android.graphics.ColorFilter) null);" in ba_raw
     and "if (plogo == R.drawable.ic_search) si.setColorFilter(dim);" in ba_raw,
     "the pill binding is null-safe (explicit branches, the ColorFilter overload)")
need("testImplementation 'org.robolectric:robolectric:4.13'" in gradle
     and "includeAndroidResources = true" in gradle,
     "the launch-law runtime rides the build (Robolectric + android resources)")
need("jlatexmath" in gradle and "shrinkResources true" in gradle,
     "the size levers are ON: shrinkResources + R8 (full mode default); "
     "jlatexmath on-demand recorded as the NEXT candidate, not risked")

print()
if failed:
    print(f"V1423 GATE: BAD ({failed} fail)")
    sys.exit(1)
print("V1423 GATE: OK")
