#!/usr/bin/env python3
"""v1.4.22 release gate — the dark-icon round 4, the raster turn, the
screen-translate hybrid, the AiRender four-pack, and THE LOST-COMMIT
RECOVERY.

THE LAW (carried): a green gate proves code EXISTS, not that a bug is
CURED — every fix carries a compiled+run BEHAVIOR probe where a pure body
could be extracted (the v1.4.11 parse-gate law) and a Diag breadcrumb
where it could not. v1.4.22 is the RECONSTRUCTION gate: the original
65-file commit was lost in a sandbox recycle before signing, and this
release was rebuilt against the surviving unsigned APK — the dex
string-table diff for every constant, jadx class deltas for every change,
byte-exact PNG recovery for the engine marks. The pins below therefore
carry BOTH the mechanism and the reconstruction evidence (byte-length
pins where the shipped artifact's own numbers are known).

1. FORCED-DARK ROUND 4 — THE DEFAULT-FLIP (MediaHook.darkSmartJs). Rounds
   1-3 pinned .__boltIC (un-inverted) on anything icon-shaped, so a
   photo-less dark hamburger as an img/svg stayed dark-on-dark (the DIU
   Moodle corpse). Round 4 flips the default: a <=64px img/svg INVERTS
   WITH THE PAGE unless it is clearly content — photo-ish names inside
   article/main/p/figure ([photoLike]/[photoContent]) or a wide banner
   (aspect >= 2.5). The v1.4.21 uiCtx parent-walk heuristic is RETIRED.
   The rescue ladder for CSS-pinned dark glyphs: [raiseCss] (dark ->
   the 0.6 gray floor, the inverse of v1.4.19's clampCss), [fontRescue]
   (icon fonts), [pseudoRaise] (::before/::after). Then the
   [rescuePass] pixel measurement: any small media still dark AFTER the
   walk is sampled (SVG stroke/fill luminance; same-origin 24x24 canvas
   for imgs — cross-origin throws and is honestly skipped) and flipped;
   caps 60 rescues/160 looked. The census: the walk publishes
   window.__boltDarkCount ('small ic=N ni=N rescue=N') and
   BrowserActivity reads it back 2.5 s after the inject ([darkCountJs]
   -> the per-host 'dark icons ' Diag line — the next field report
   names the classifier's actual verdict, not a guess). RECONSTRUCTION
   EVIDENCE: the payload reassembles to the shipped build's 10,731
   chars byte-for-byte (check_dark_js syntax-verifies the extraction).

2. THE RASTER TURN (engine logos). The five official marks shipped as
   vectors, which every device rasterized slightly differently (warped /
   off-color marks on some OEM stacks). Now fixed 64x64 PNGs in
   drawable-nodpi/ — identical pixels on every device, resource IDs (and
   engineLogo()'s mapping) unchanged. The retired vectors live in
   scripts/engine_vectors/ with scripts/rasterize_engine_logos.py (the
   reproducible vector->SVG->cairosvg(4x)->LANCZOS pipeline; google/ddg/
   brave/startpage regenerate byte-exactly, bing content-exact through a
   different palette encoder — the committed PNGs are canonical, and the
   APK bytes of the shipped build are the reference). The home search
   pill now carries the active engine's mark (brand untinted, the generic
   magnifier keeps the tint), and BOTH engine-selection entry points
   (the omnibox picker and Settings -> Search engine) bump Fx.homeGen so
   the mark flips everywhere on a switch — the homeGen rebuild channel,
   never a recreate. The v1.4.21 binding breadcrumb stays.

3. THE SCREEN-TRANSLATE HYBRID (ScreenTranslate + AiBridge). The old
   vision path asked the model to place translations in box_2d — it
   invented positions and drifted terminology across tiles. Now: the
   on-device tesseract OCR reads text + EXACT boxes first, ONE
   AiBridge.textTranslate translates all numbered lines with full context
   (hybridPrompt/parseHybrid — a JSON-array reply of {"i":N,"t":"..."}),
   and the screenshot is only ever sent when OCR reads nothing at all
   (the 'no OCR text' door / the models-fail fallback). The vision path
   is demoted to a fallback: full-screen first, tiling ONLY on a genuine
   MAX_TOKENS truncation (shouldTile), and the tiles carry the
   full-screen transcript as context ([hybrid_ctx] -> scrCtx ->
   AiBridge.lastTranscript). thinkingLevel:low is tried FIRST and
   dropped cleanly on the 400 (the v1.4.21 field-rejection ladder's new
   top rung; the think: Boolean parameter of visionBody). Raw reply,
   merged-boxes count and coverage all ride to Diag.

4. THE AiRENDER FOUR-PACK (AiRender). HTML emphasis tags as real spans —
   mark/u/del/sub/sup/b/i/code/br/a-href, unknown tags render their inner
   text only (a single [htmlTag]/[applyTag] stack); nested quotes carry a
   QuoteSpan + LeadingMarginSpan per level; '- [ ]'/'- [x]' items render
   tappable [TaskToggleSpan] checkboxes that flip in place; footnotes
   render accent superscripts + a '──── Footnotes' section
   (inlineFootnotes on) — plus ins. Three BSD-2 commonmark extension
   deps (task-list-items, footnotes, ins — 0.24.0 matching the core).
   Long-press still copies the raw text.
"""
import os
import re
import struct
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
GRADLE = open(os.path.join(ROOT, "app/build.gradle"), encoding="utf8").read()
MH = open(os.path.join(ROOT, "app/src/main/java/com/bolt/browser/media/MediaHook.java"),
          encoding="utf8").read()
BA = open(os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java"),
          encoding="utf8").read()
SI = open(os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/SettingsItemsActivity.java"),
          encoding="utf8").read()
ST = open(os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/ScreenTranslate.kt"),
          encoding="utf8").read()
AB = open(os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/AiBridge.kt"),
          encoding="utf8").read()
AR = open(os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/AiRender.kt"),
          encoding="utf8").read()
STRINGS = open(os.path.join(RES, "values/strings.xml"), encoding="utf8").read()

fails = 0
def need(cond, label):
    global fails
    print(("  ok  - " if cond else "  FAIL- ") + label)
    if not cond:
        fails += 1

print("V — the version pin (136/1.4.22, the sixteenth session)")
need(re.search(r"versionCode 136\b", GRADLE) is not None, "versionCode 136")
need('versionName "1.4.22"' in GRADLE, 'versionName "1.4.22"')
entries = re.findall(r"<item>([^<]+)</item>", STRINGS)
need(entries and entries[0].startswith("1.4.22|"),
     "the About changelog's first line is this release")
need("EVERY small menu icon visible" in entries[0]
     and "raster PNGs" in entries[0]
     and "ONE AI request translates the whole screen" in entries[0]
     and "task checkboxes you can tap" in entries[0]
     and "footnotes" in entries[0],
     "the 1.4.22 line names all four fixes (default-flip / raster / hybrid / four-pack)")
need(len(entries) >= 30, "the array grew to 30 (1.4.22 landed at [0])")

print("A — fix 1: the round-4 default-flip + the census")
need("function photoLike(el){" in MH and "function photoContent(el){" in MH,
     "the content line exists: photoLike/photoContent (photo-ish names inside "
     "article/main/p/figure keep their pixels)")
need("t==='ARTICLE'||t==='MAIN'||t==='P'||t==='FIGURE'" in MH,
     "photoContent walks article/main/p/figure (2 levels)")
need("Math.max(w,h)/Math.min(w,h)>=2.5" in MH,
     "the wide-banner exclusion (aspect >= 2.5 stays content)")
need("function uiCtx(el){" not in MH,
     "the v1.4.21 uiCtx parent-walk heuristic is RETIRED (round 4)")
need("function raiseCss(c){" in MH and "var f=153/Math.max(y,1);" in MH,
     "the RESCUE RAISE exists: a dark color raised to the 0.6 gray floor "
     "(the inverse of v1.4.19's clampCss)")
need("function fontRescue(el,cs){" in MH and "function pseudoRaise(el,cs){" in MH,
     "the font rescue + the pseudo raise (icon-font text and ::before/::after "
     "glyphs pinned dark get raised)")
need("function svgDark(el){" in MH and "function imgDark(el){" in MH
     and "cv.width=S;cv.height=S;" in MH,
     "the PIXEL MEASUREMENT exists (SVG stroke/fill luminance; same-origin "
     "24x24 canvas downsample for imgs)")
need("function rescuePass(){" in MH
     and "rcS<60" in MH and "looked<160" in MH,
     "the post-walk rescue pass (capped 60 rescues / 160 looked)")
need("window.__boltDarkCount='small ic='+icS+' ni='+niS+' rescue='+rcS;" in MH,
     "the census publish (the walk counts every decision)")
need("public static String darkCountJs() {" in MH
     and "window.__boltDarkCount||'none'" in MH,
     "the census READER exists (darkCountJs)")
need('MediaHook.darkCountJs(), res3 ->' in BA
     and '"dark icons " + host + " " + res3' in BA,
     "BrowserActivity reads the census 2.5 s after the inject and logs "
     "'dark icons <host> <result>' through Diag")
# the reconstruction evidence: the payload length is the shipped build's own
m = re.search(r"public static String darkSmartJs\(\) \{(.*?)\n    \}", MH, re.S)
body_lines = [l for l in m.group(1).split("\n") if not l.strip().startswith("//")]
parts = []
for l in body_lines:
    for pm in re.finditer(r'"((?:[^"\\\\]|\\\\.)*)"', l):
        parts.append(pm.group(1))
def _unesc(s):
    out = []; i = 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            out.append(s[i + 1]); i += 2
        else:
            out.append(s[i]); i += 1
    return "".join(out)
payload_len = len(_unesc("".join(parts)))
need(payload_len == 10731,
     f"RECONSTRUCTION EVIDENCE: the darkSmartJs payload reassembles to the "
     f"shipped build's 10,731 chars (got {payload_len})")
need(".__boltIC{filter:none!important;}" in MH,
     "the dark CSS still carries the __boltIC ride-the-page rule (the "
     "round-4 flip's contract: IC elements invert WITH the page)")

print("B — fix 2: the raster turn")
drn = os.path.join(RES, "drawable-nodpi")
for d in ("ic_engine_google", "ic_engine_ddg", "ic_engine_bing",
          "ic_engine_brave", "ic_engine_startpage"):
    fpng = os.path.join(drn, d + ".png")
    ok = os.path.isfile(fpng)
    if ok:
        raw = open(fpng, "rb").read()
        w, h = struct.unpack(">II", raw[16:24])
        ok = (raw[:8] == bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
              and w == 64 and h == 64 and 1500 <= len(raw) <= 8000)
    need(ok, f"the {d} PNG is a real 64x64 mark in drawable-nodpi "
             f"(fixed pixels on every device)")
need(not os.path.exists(os.path.join(RES, "drawable", "ic_engine_bing.xml")),
     "the vectors are retired from drawable/")
need(all(os.path.isfile(os.path.join(ROOT, "scripts/engine_vectors",
         f"ic_engine_{n}.xml")) for n in
         ("google", "ddg", "bing", "brave", "startpage")),
     "the retired vectors are preserved in scripts/engine_vectors/ "
     "(the reproducibility source)")
need(os.path.isfile(os.path.join(ROOT, "scripts/rasterize_engine_logos.py")),
     "the rasterizer pipeline script exists")
need("output_width=SIZE * SUPERSAMPLE" in open(
    os.path.join(ROOT, "scripts/rasterize_engine_logos.py"), encoding="utf8").read()
    or "SUPERSAMPLE" in open(os.path.join(ROOT, "scripts/rasterize_engine_logos.py"),
                             encoding="utf8").read(),
     "the rasterizer runs the 4x supersample -> LANCZOS downsample pipeline")
need("int plogo = engineLogo(prefs.searchEngine());" in BA
     and "si.setColorFilter(plogo == R.drawable.ic_search ? dim : null);" in BA,
     "the home search pill carries the ACTIVE engine's mark (brand untinted, "
     "the generic magnifier keeps the tint)")
need("engineLogo(prefs.searchEngine())" in BA or "engineLogo" in BA,
     "engineLogo()'s mapping is unchanged (resource IDs identical)")
need("Fx.homeGen++;" in BA[BA.find("protected void showEnginePicker("):][:2600]
     if "protected void showEnginePicker(" in BA else False,
     "the omnibox engine picker bumps Fx.homeGen (entry point 1)")
need("com.bolt.browser.util.Fx.homeGen++;" in SI,
     "Settings -> Search engine bumps Fx.homeGen (entry point 2)")
need('"engine logo set engine="' in BA,
     "the v1.4.21 binding Diag breadcrumb is kept (the on-device truth)")

print("C — fix 3: the screen-translate hybrid")
need("fun textTranslate(" in AB and "fun hybridPrompt(" in AB
     and "fun parseHybrid(" in AB,
     "the pure-text translate door exists (textTranslate/hybridPrompt/parseHybrid)")
need('const val HYBRID_HEAD' in AB and 'const val HYBRID_TAIL' in AB
     and 'const val HYBRID_CTX' in AB,
     "the three hybrid prompt constants exist (head/tail/ctx)")
need("AiBridge.lastTranscript" in ST,
     "the full-screen transcript rides the tiles (lastTranscript as context)")
need('"scr hybrid ask prov="' in ST or 'scr hybrid ask prov=' in ST,
     "the hybrid ask Diag breadcrumb (provider + model + lines)")
need('"scr hybrid ok lines="' in ST or 'scr hybrid ok lines=' in ST,
     "the hybrid success breadcrumb (lines + coverage + ms)")
need('"scr hybrid text-fail "' in ST or 'scr hybrid text-fail ' in ST,
     "the hybrid text-failure door (-> vision)")
need('"scr hybrid models-fail -> vision"' in ST,
     "the models-fail door (no OCR models -> the vision fallback still translates)")
need('"no OCR text"' in AB or '"no OCR text"' in ST,
     "the no-OCR-text door (the screenshot is only sent when OCR reads nothing)")
need('"scr ai full cut -> tiles ctxlen="' in ST,
     "the genuine-MAX_TOKENS tiling breadcrumb (ctxlen = the transcript length)")
need('"scr ai merged boxes="' in ST,
     "the merged-boxes Diag (the box accounting rides to evidence)")
need('"Send the screen\'s text for AI translation?"' in ST,
     "the honest confirm dialog (text first, screenshot only as fallback)")
need('"Translate screen — AI (reading)"' in ST,
     "the hybrid progress title names the READING stage")
need('"text translate needs the Gemini provider"' in AB,
     "the provider requirement for the text door")
need("think: Boolean): JSONObject {" in AB,
     "thinkingLevel:low is the conditional top rung (visionBody's think flag)")
need('translateTiled(a, bmp, tl, g, AiBridge.lastTranscript)' in ST,
     "the tiles carry the transcript (the ctx parameter)")
need("runOcrStage" in ST and "runHybridStage" in ST,
     "the OCR-first pipeline stages exist (the hybrid hand-off)")

print("D — fix 4: the AiRender four-pack")
need("fun htmlTag(" in AR and "fun applyTag(" in AR,
     "the HTML tag stack exists (htmlTag parses, applyTag spans)")
need("class HtmlTag(" in AR or "class HtmlTag" in AR,
     "the tag model exists (start/name/href/closing)")
need("class TaskToggleSpan" in AR,
     "the tappable task checkbox span exists (TaskToggleSpan)")
need("QuoteSpan(" in AR and "LeadingMarginSpan.Standard(" in AR,
     "nested quotes carry a QuoteSpan + per-level indent")
need("──── Footnotes" in AR,
     "the footnotes definitions section heading")
need("FootnotesExtension" in AR and "inlineFootnotes(true)" in AR,
     "footnotes parse with inlineFootnotes on")
need("InsExtension" in AR and "TaskListItemsExtension" in AR,
     "the ins + task-list extensions are registered")
need("implementation 'org.commonmark:commonmark-ext-task-list-items:0.24.0'" in GRADLE
     and "implementation 'org.commonmark:commonmark-ext-footnotes:0.24.0'" in GRADLE
     and "implementation 'org.commonmark:commonmark-ext-ins:0.24.0'" in GRADLE,
     "the three BSD-2 commonmark extension deps ride the build")
need("UnderlineSpan()" in AR and "SuperscriptSpan()" in AR
     and "SubscriptSpan()" in AR,
     "u/sup/sub render as real spans")
need('"mark"' in AR or '"strong"' in AR,
     "the HTML tag names ride the applyTag table")

print("P — the compiled probe (pure bodies, transcribed and RUN)")
probe = r"""
public class Probe {
    static int fails = 0;
    static void need(boolean c, String m) {
        if (!c) { System.out.println("  probe FAIL: " + m); fails++; }
    }
    // [1] THE ROUND-4 CLASSIFIER (transcription of the payload's pure core):
    // a <=64px img/svg is an icon UNLESS wide banner or clear content.
    static boolean photoLike(String cn) {
        return cn != null && (cn.contains("photo") || cn.contains("thumb")
                || cn.contains("avatar") || cn.contains("banner")
                || cn.contains("image") || cn.contains("pic"));
    }
    static boolean photoContent(String cn, String parentChain) {
        if (!photoLike(cn)) return false;
        return parentChain.contains("ARTICLE") || parentChain.contains("MAIN")
                || parentChain.contains("P") || parentChain.contains("FIGURE");
    }
    static boolean uiIcon(String tag, int w, int h, String cn, String parentChain) {
        if (!tag.equals("SVG") && !tag.equals("IMG")) return false;
        int m = Math.max(w, h);
        if (!(m > 0 && m <= 64)) return false;
        if (cn != null && (cn.contains("icon") || cn.contains("menu")
                || cn.contains("burger"))) return true;
        if (w > 0 && h > 0 && (double) Math.max(w, h) / Math.min(w, h) >= 2.5) return false;
        if (photoContent(cn, parentChain)) return false;
        return true;
    }
    // [2] THE RESCUE RAISE (transcription of raiseCss's arithmetic):
    // a dark color (lum <= 0.4) raised to the 0.6 gray floor.
    static double raiseCss(double r, double g, double b) {
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        if (y >= 102) return -1;            // null: not dark enough to raise
        double f = 153 / Math.max(y, 1);
        return Math.min(255, Math.round(r * f)) * 0.2126
             + Math.min(255, Math.round(g * f)) * 0.7152
             + Math.min(255, Math.round(b * f)) * 0.0722;
    }
    // [3] THE HYBRID HAND-OFF (transcription of the numbered-lines protocol):
    // the reply is a JSON array of {"i":N,"t":"..."} — i is 1-based.
    static int[] parseHybrid(String reply) {
        // returns {usable, unusable} - mirrors AiBridge.parseHybrid: the
        // fence is sliced, n >= 1 && non-empty t survive into the map, and
        // a duplicate i OVERWRITES (last wins, the real dedupe law)
        int bad = 0;
        String body = reply.trim();
        if (body.startsWith("```")) { body = body.substring(body.indexOf('\n') + 1); }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{\"i\":(\\d+),\"t\":\"((?:[^\"\\\\]|\\\\.)*)\"\\}")
                .matcher(body);
        java.util.HashMap<Integer, String> map = new java.util.HashMap<>();
        while (m.find()) {
            int i = Integer.parseInt(m.group(1));
            String t = m.group(2).trim();
            if (i >= 1 && !t.isEmpty()) map.put(i, t); else bad++;
        }
        return new int[]{map.size(), bad};
    }
    // [4] THE TILE-MERGE MATH (transcription of the box accounting):
    // coverage = fraction of OCR lines whose box was reclaimed by a
    // translated reply box (an IoU>0 match wins; duplicates fold).
    static double coverage(int[][] ocr, int[][] reply) {
        if (ocr.length == 0) return 0;
        int hit = 0;
        java.util.HashSet<Integer> used = new java.util.HashSet<>();
        for (int[] o : ocr) {
            for (int k = 0; k < reply.length; k++) {
                if (used.contains(k)) continue;
                if (iou(o, reply[k]) > 0.5) { used.add(k); hit++; break; }
            }
        }
        return (double) hit / ocr.length;
    }
    static double iou(int[] a, int[] b) {
        int x1 = Math.max(a[0], b[0]), y1 = Math.max(a[1], b[1]);
        int x2 = Math.min(a[2], b[2]), y2 = Math.min(a[3], b[3]);
        if (x2 <= x1 || y2 <= y1) return 0;
        double inter = (x2 - x1) * (double) (y2 - y1);
        double areaA = (a[2] - a[0]) * (double) (a[3] - a[1]);
        double areaB = (b[2] - b[0]) * (double) (b[3] - b[1]);
        return inter / (areaA + areaB - inter);
    }
    // [5] THE TAG STACK (transcription of htmlTag push/pop):
    // an unknown tag renders its inner text only; a close pops the NEAREST
    // matching open; unbalanced opens close at the block end.
    static String tagStack(String[] events) {
        java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
        StringBuilder out = new StringBuilder();
        for (String ev : events) {
            if (ev.startsWith("</")) {
                String name = ev.substring(2, ev.length() - 1);
                if (open.contains(name)) {
                    while (!open.isEmpty() && !open.pop().equals(name)) {}
                }
            } else if (ev.startsWith("<")) {
                open.push(ev.substring(1, ev.length() - 1));
            } else {
                out.append(ev);
            }
        }
        return out.toString();
    }
    public static void main(String[] a) {
        // [1] the classifier: the bare hamburger case
        need(uiIcon("IMG", 24, 24, "menu-toggle", "BODY>DIV") == true,
             "a 24px menu img is an icon (inverts with the page)");
        need(uiIcon("IMG", 24, 24, null, "BODY>DIV") == true,
             "a 24px UNKNOWN img is an icon by default (the round-4 flip)");
        need(uiIcon("IMG", 320, 48, null, "BODY>DIV") == false,
             "a wide banner (aspect 6.7) stays content");
        need(uiIcon("IMG", 24, 24, "thumb", "ARTICLE>P") == false,
             "a photo-ish name inside article content stays content");
        need(uiIcon("IMG", 320, 320, null, "BODY>DIV") == false,
             "a big image is content (size cut)");
        // [2] the raise: dark -> 0.6 floor; light -> untouched (null)
        need(raiseCss(40, 40, 40) >= 152 && raiseCss(40, 40, 40) <= 154,
             "a dark gray raises to the 0.6 floor");
        need(raiseCss(255, 255, 255) == -1,
             "a light color is not raised (clampCss owns that side)");
        // [3] the hybrid reply protocol
        int[] r1 = parseHybrid("[{\"i\":1,\"t\":\"Hola\"},{\"i\":2,\"t\":\"Mundo\"}]");
        need(r1[0] == 2 && r1[1] == 0, "a clean 2-line reply parses 2 lines");
        int[] r2 = parseHybrid("```json\n[{\"i\":1,\"t\":\"\"},{\"i\":1,\"t\":\"dup\"},{\"i\":2,\"t\":\"ok\"}]\n```");
        need(r2[0] == 2 && r2[1] == 1,
             "a fenced reply: the empty entry is dropped (bad), the dup "
             + "OVERWRITES (last wins) — 2 usable lines, the real dedupe law");
        // [4] the tile-merge math
        int[][] ocr = {{0, 0, 100, 20}, {0, 30, 100, 50}, {0, 60, 100, 80}};
        int[][] rep = {{0, 0, 100, 20}, {0, 30, 100, 50}, {0, 0, 100, 20}};
        need(coverage(ocr, rep) > 0.66 && coverage(ocr, rep) < 0.67,
             "3 OCR lines vs 2 unique boxes = 2/3 coverage (dup folds)");
        need(coverage(new int[][]{{0, 0, 10, 10}}, new int[][]{{90, 90, 99, 99}}) == 0,
             "a disjoint box never matches");
        // [5] the tag stack
        need(tagStack(new String[]{"<b>", "hello ", "<unknown>", "world", "</unknown>", "</b>"}).equals("hello world"),
             "an unknown tag renders its inner text only");
        need(tagStack(new String[]{"<b>", "<i>", "x", "</b>", "y"}).equals("xy"),
             "a mismatched close pops to the NEAREST open (b closes over i)");
        System.out.println(fails == 0 ? "PROBE OK" : "PROBE BAD (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
"""
tmp = tempfile.mkdtemp(prefix="bolt_v1422_probe_")
open(os.path.join(tmp, "Probe.java"), "w", encoding="utf8").write(probe)
env = dict(os.environ)
jdk = os.environ.get("BOLT_JDK", "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64")
if os.path.isdir(jdk):
    env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH", "")
r = subprocess.run(["javac", "-d", tmp, os.path.join(tmp, "Probe.java")],
                   capture_output=True, text=True, env=env)
if r.returncode != 0:
    print("  FAIL- the probe compiles (javac)")
    print(r.stderr[:800])
    fails += 1
else:
    r = subprocess.run(["java", "-cp", tmp, "Probe"], capture_output=True, text=True, env=env)
    print(r.stdout.strip())
    if r.returncode != 0:
        fails += 1

print()
print("V1422 GATE: " + ("OK" if fails == 0 else f"BAD ({fails} fail)"))
sys.exit(0 if fails == 0 else 1)
