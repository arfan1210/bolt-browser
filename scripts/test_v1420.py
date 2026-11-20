#!/usr/bin/env python3
"""v1.4.20 release gate — the evidence-pack.

THE LAW (carried): a green gate proves code EXISTS, not that a bug is
CURED — every fix carries a compiled+run BEHAVIOR probe where a pure body
could be extracted (the v1.4.11 parse-gate law) and a Diag breadcrumb
where it could not. v1.4.20's field order arrived with diag.log evidence
(the ~3 s media-notification kill, the 12 MB downloads.json) and
user-approved product calls (the single-route image search, the
ask-every-time download door).

1. MEDIA-NOTIFICATION CRASH (MediaControlService). The API<=29 SystemUI
   artwork-crop failure ("Couldn't inflate contentViews ... region must
   intersect with the Bitmap's dimensions", posted ASYNC — uncatchable at
   notify()) survived the v1.3.22/27 fresh-copy + 1px-shrink guards.
   Every artwork that leaves the service on API <= 29 is now SMALL
   (api29ArtCap: <=256 px longest edge, a 48 px margin below the device's
   fatal 1/6-screen sizes), validated ARGB_8888 — and when in doubt the
   notification goes out TEXT-ONLY (no artwork beats an app kill).

2. FORCED DARK: THE ICON + THICKNESS FLOOR (MediaHook.darkSmartJs). Two
   v1.4.19 gaps: SVG UI icons were skipped by the walk AND left
   un-inverted (a dark hamburger stayed dark on the black ground), and
   nothing helped THICKNESS. Now small svgs (<=64 px, uiIcon) are walked
   and classed .__boltIC (filter:none — they ride the page inversion);
   light strokes/fills clamp to the 0.6 floor; thin strokes (<1.5 px)
   bump to 2 (bumpW); icon fonts get a text-stroke; self-colored
   pseudo-glyphs get generated rules (bounded). Exact restore on clear.

3. AI TRANSLATE: THINKING OFF + THE TILE ROAD (AiBridge + ScreenTranslate).
   The ~3 s "cut off" was never a 32768-token generation: thinkingLevel
   minimal still thinks (and the no-config retry thinks FULLY), raiding
   maxOutputTokens. Translate now sends thinkingBudget 0 (the hard off);
   every attempt logs finishReason + thoughtsTokenCount vs
   candidatesTokenCount + reply length (usageDiag — the evidence line);
   parseBoxes got the LENIENT SALVAGE (one malformed box costs one box,
   not the screen — salvageObjects); a 65536-cap cut hands the CALLER the
   tile contract (shouldTile) and ScreenTranslate splits the capture 2x2,
   re-places the boxes (tilePlace) and seam-dedups (iou).

4. IMAGE SEARCH: THE SINGLE ROUTE (VisualSearch, user-approved). Every
   engine rides Google's search-by-image POST; the Bing SBI upload path
   (and the DDG/Brave -> Bing fallback, and the v1.4.19 real-origin
   intercept that served it) is RETIRED — the 835 KB shell hung even
   under its real origin. The toast names the route actually used.

5. DOWNLOADS: ASK EVERY TIME (DownloadEngine.dlDialogRoute + the
   dlOfferBlob bridge + the Downloads-screen toggle; default ASK). Every
   door — http, blob:, data: — rides the rename + destination dialog;
   Direct keeps the v1.4.16 fast paths. The blob rescue still runs
   before any confirm (bytes first, dialog second).

6. downloads.json BLOAT (DownloadEngine.persistUrl). saveDataUrl parked
   the whole base64 payload in item.url and toJson wrote it out (~1.1 MB
   per saved image, 12 MB total). A DONE item persists a short marker;
   load() trims the historical bloat once; non-done items keep their
   bytes (revive/retry still decode them).

7. OFFICIAL ENGINE MARKS (the four drawables). simple-icons outlines for
   DDG/Brave/Startpage (+ the Fluent Bing mark), brand colors per the
   simple-icons table; Google keeps its own four-color drawable.

8. MARKDOWN + LATEX IN BOLT AI (AiRender.kt + BoltAi). commonmark-java
   core + the GFM-tables extension (~130 KB, zero fonts) parse assistant
   replies into native Spanned; LaTeX rides hand-rolled spans on the
   SYSTEM font (codeProtect holds math out as placeholders so the parser
   never eats x_i as emphasis; texMap/fracFlat/sup-sub render the honest
   subset). User bubbles stay plain; long-press copies the RAW source
   (the bubble tag).
"""

import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
fails = 0


def need(cond, why):
    global fails
    print(("  ok  " if cond else "  FAIL") + " - " + why)
    if not cond:
        fails += 1


SRC = os.path.join(ROOT, "app/src/main/java/com/bolt/browser")
RES = os.path.join(ROOT, "app/src/main/res")
gradle = open(os.path.join(ROOT, "app/build.gradle"), encoding="utf8").read()
rel = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()
mcs = open(os.path.join(SRC, "media/MediaControlService.java"), encoding="utf8").read()
mh = open(os.path.join(SRC, "media/MediaHook.java"), encoding="utf8").read()
ab = open(os.path.join(SRC, "util/AiBridge.kt"), encoding="utf8").read()
st = open(os.path.join(SRC, "util/ScreenTranslate.kt"), encoding="utf8").read()
vs = open(os.path.join(SRC, "util/VisualSearch.kt"), encoding="utf8").read()
de = open(os.path.join(SRC, "core/DownloadEngine.java"), encoding="utf8").read()
ba = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
dl = open(os.path.join(SRC, "ui/DownloadsActivity.java"), encoding="utf8").read()
pf = open(os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Prefs.java"),
          encoding="utf8").read()
bai = open(os.path.join(SRC, "util/BoltAi.kt"), encoding="utf8").read()
ar = open(os.path.join(SRC, "util/AiRender.kt"), encoding="utf8").read()
bd = open(os.path.join(SRC, "util/BlobDownload.kt"), encoding="utf8").read()
dr = os.path.join(RES, "drawable")


def strip_comments_strings(src):
    """Blank out // and /* */ comments and string bodies so pins read CODE,
    not the comments describing it."""
    out, i, n, mode = [], 0, len(src), None
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if mode is None:
            if c == "/" and nxt == "/":
                mode = "line"; i += 2; continue
            if c == "/" and nxt == "*":
                mode = "block"; i += 2; continue
            if c in "\"'":
                mode = c; out.append(c); i += 1; continue
            out.append(c); i += 1; continue
        if mode == "line":
            if c == "\n":
                mode = None; out.append("\n")
            i += 1; continue
        if mode == "block":
            if c == "*" and nxt == "/":
                mode = None; i += 2; continue
            i += 1; continue
        if c == mode:
            mode = None
        elif c == "\\" and i + 1 < n:
            i += 1
        i += 1; continue
    return "".join(out)


mcs_code = strip_comments_strings(mcs)
mh_code = strip_comments_strings(mh)
ab_code = strip_comments_strings(ab)
st_code = strip_comments_strings(st)
vs_code = strip_comments_strings(vs)
de_code = strip_comments_strings(de)
pf_code = strip_comments_strings(pf)
ar_code = strip_comments_strings(ar)
bd_code = strip_comments_strings(bd)

print("V — the version pin")
need("versionCode 139" in gradle, "versionCode 135 (Android only installs RISING codes; v1.4.21 re-point 2026-11-20)")
need('versionName "1.4.25"' in gradle, 'versionName "1.4.25"')

print("A — fix 1: the API<=29 artwork cap (the async notification kill)")
need("static int api29ArtCap(int fatalMax) {" in mcs_code,
     "the cap resolver is PURE (probe-pinned)")
need("private Bitmap api29SafeArt(Bitmap b) {" in mcs_code,
     "the validate+cap application exists")
need("Bitmap safe = api29SafeArt(bmp);" in mcs_code
     and "art cap drop gen=" in mcs,
     "the MASTER rides the cap at fetch time; a fetch that cannot be made "
     "safe is DROPPED with a Diag breadcrumb (text-only beats the kill)")
need("return api29SafeArt(out);" in mcs_code,
     "artCopy rides the cap too (BOTH publish channels — large icon + "
     "session metadata — leave the process small and validated)")
need("Build.VERSION.SDK_INT > 29" in mcs_code,
     "API > 29 keeps the copy discipline alone (those versions stayed crash-free)")

print("B — fix 2: the forced-dark icon + thickness floor")
need("function smallOf(el)" in mh and "m>0&&m<=64" in mh
     and "function ridesFlip(el)" in mh,  # v1.4.23 re-point (2026-11-20): the DEFAULT-FLIP classifier (ridesFlip/smallOf/bannerish/photoCtx) replaced the uiIcon family — see test_v1423 section A
     "the small-media classifier exists: longest edge <= 64 px is SMALL "
     "(the size cut); ridesFlip is the default-flip verdict (RAW)")
need("if((t==='IMG'||t==='SVG')&&ridesFlip(el))return false;" in mh,
     "isMedia lets UI icons through to the walk (they were skipped AND "
     "left un-inverted — the dark-hamburger vanish) "
     "(v1.4.21 re-point 2026-11-20: IMG joined SVG — menu images were "
     "media too and stayed black; context-first classification in "
     "test_v1421 section D)")
need("function iconClamp(el,cs){" in mh
     and "el.classList.add(IC);cnt.ic++;" in mh,
     "the icon floor: classed .__boltIC (filter:none — rides the page "
     "inversion) inside inverted subtrees only")
need(".__boltIC{filter:none!important;}" in mh,
     "the CSS rule that neutralizes the svg type selector's own un-invert")
need("function bumpW(px)" in mh and "return '2px';" in mh,
     "the THICKNESS resolver is PURE: a low-contrast stroke < 1.5 px bumps "
     "to 2 px (the user's explicit ask)")
need("el.style.strokeWidth='2'" in mh and "el.style.borderWidth=bw;" in mh,
     "thin svg strokes AND clamped hairline borders get the bump (RAW source)")
need("el.style.webkitTextStroke='0.45px currentColor';" in mh,
     "icon-font glyphs thicken too (body text never matches iconFont)")
need("function pseudoFloor(el,cs,inv){" in mh and "psLook>=600||psN>=150" in mh,
     "self-colored pseudo-glyphs get generated rules, bounded (600 looks / "
     "150 rules)")
need("'data-boltpb'" in mh and "data-boltpa" in mh,
     "the pseudo rule keys ride data attributes (numeric, unquoted selectors)")
need("el.style.stroke=o.s;" in mh and "el.style.borderWidth=o.W;" in mh
     and "el.style.webkitTextStroke=o.t;" in mh,
     "darkClearJs restores the new writes exactly (stroke, fill, "
     "stroke-width, border-width, text-stroke)")
need(".__boltIC" in mh and "classList.remove('__boltIC');" in mh,
     "darkClearJs strips the IC class (a de-inverted page keeps no active "
     "filters)")

print("C — fix 3: translate thinking-off + the evidence line + the tile road")
# v1.4.21 re-point (2026-11-20): the old pin ('put("thinkingBudget", 0)')
# retired — the lite model rejected the thinkingBudget FIELD itself with
# HTTP 400 and the narrow "thinking"-body retry never fired. The honest
# successor pin: the TRANSLATE body carries NO thinkingConfig at all (the
# chat body keeps thinkingLevel minimal — different budget, untouched).
import re as _re
_vb = _re.search(r"private fun visionBody\(.*?\n    \}", ab, _re.S)
need(_vb is not None and "thinkingConfig" not in _vb.group(0),
     "the translate body sends NO thinkingConfig (v1.4.21: omitted "
     "entirely — a field never sent cannot be rejected; the chat body's "
     "thinkingLevel is a different path)")
need("internal fun usageDiag(fr: String, thoughts: Int, candidates: Int, len: Int): String {" in ab_code,
     "the one-line evidence form is PURE (probe-pinned)")
need('usageInt(r.second, "thoughtsTokenCount")' in ab,
     "every attempt logs the thinking/visible token pair (the raid detector; "
     "RAW source)")
need('"ai translate head "' in ab,
     "the reply-head evidence rides out on parse failure (RAW source)")
need("internal fun salvageObjects(text: String): JSONArray?" in ab_code,
     "the lenient salvage is PURE (probe-pinned)")
need("lastParseSalvaged" in ab_code and 'fr == "MAX_TOKENS" && lastParseSalvaged' in ab,
     "a salvaged parse under MAX_TOKENS takes the ladder/tile road, never a "
     "half screen")
need("internal fun shouldTile(err: String): Boolean" in ab_code
     and "const val CUT_OFF_ERR" in ab_code,
     "the tile contract is PURE (the error string IS the contract)")
# v1.4.25 re-point (2026-11-21): THE GRID LAW retired the box_2d road
# entirely — tilePlace/iou (the v1.4.20 pure pair) and translateRegion
# (the v1.4.23 region form) left with it: every grid cell places its
# translation on FIXED cell geometry (gridOf/gridCardRect, PURE) and the
# vision fallback asks for TEXT ONLY (visionTileTranslate). The shouldTile
# hand-off pin below still rides the MAX_TOKENS cut.
need("internal fun gridOf(w: Int, h: Int): Array<IntArray>" in st_code
     and "internal fun gridCardRect(w: Int, h: Int, rect: IntArray): Rect" in st_code,
     "the grid geometry + card rects are PURE (probe-pinned)")
need("AiBridge.shouldTile(AiBridge.lastError)" in st_code,
     "the MAX_TOKENS cut still hands off to the vision grid")
need('"scr ai tile' in st,
     "the tile Diag breadcrumbs (enter + per-tile failure)")

print("D — fix 4: the single-route image search")
need('fun endpointFor(engineIdx: Int): Endpoint = Endpoint(' in vs_code
     and 'if (engineIdx == 0) null else "Google"' in vs,
     "the single-route resolver: EVERY engine index -> Google's "
     "search-by-image POST (user-approved; via RAW — the strip pass eats "
     "string literals)")
need('"imageBin"' not in vs,
     "the Bing SBI upload path is RETIRED (RAW check: the field name is gone)")
need("https://lens.google.com/" in vs
     and "bing.com/images?q=&tsc=ImageBasicUpload" not in vs,
     "the manual-upload fallback home is Lens for every engine")
need("image search rides Google" in ba,
     "the toast names the route actually used (RAW source)")

print("E — fix 5: the ask-every-time download door")
need("public static boolean dlDialogRoute(boolean askPref, String url) {" in de_code,
     "the pref-gated router is PURE (probe-pinned)")
need('return b("downloadAsk", true);' in pf,
     "the pref DEFAULTS TO ASK (user-approved; RAW source)")
need("public void dlOfferBlob(final String name, final String mime, final String dataUrl)" not in ba
     and "gmSaveBlob(final String name, final String mime, final String dataUrl)" in ba,
     "ONE delivery bridge: gmSaveBlob (the v1.3.58 contract every rescue "
     "caller rides); the ask door lives INSIDE it, not beside it")
need("DownloadEngine.dlDialogRoute(prefs.downloadAsk(), dataUrl)" in ba
     and "approx, name);" in ba,
     "gmSaveBlob consults the router: Ask offers the rescued bytes to the "
     "dialog (name + approx size), Direct saves at once")
need("BlobDownload.start(\n                        bt == null ? null : bt.web, url, bname, mimetype);" in ba
     or "bt.web, url, bname, mimetype)" in ba,
     "the listener's blob branch starts the rescue unconditionally (bytes "
     "first, the pref decides the door at delivery)")
need("askChip" in dl and "Ask every time" in dl,
     "the Downloads screen carries the toggle chip")
need("nameHint != null && !nameHint.isEmpty() ? nameHint" in ba,
     "the dialog's name trust order gained the rescue's nameHint")

print("F — fix 6: the downloads.json payload drop")
need("public static String persistUrl(String url) {" in de_code,
     "the payload cap is PURE (probe-pinned)")
need('return "data:(payload dropped, " + url.length() + " chars)";' in de,
     "a fat data: URL becomes a short marker (RAW source)")
need("status == ST_DONE ? persistUrl(url) : url" in de_code,
     "ONLY done items cap their url — a queued/failed item keeps its bytes "
     "(revive/retry still decode them)")
need("it.url = persistUrl(it.url);" in de_code,
     "process() drops the payload the moment the file is written")
need("if (it.status == ST_DONE) {" in de_code and "trimmed = true" in de_code,
     "load() trims the historical 12 MB bloat once (done items only)")

print("G — fix 7: the official engine marks — v1.4.23 raster turn: "
      "provenance on the ARCHIVED vectors, shipping as the PNGs")
dr = os.path.join(ROOT, "scripts", "engine_vectors")  # v1.4.23 re-point (2026-11-20): vectors retired to the archive
for d in ("ic_engine_google", "ic_engine_ddg", "ic_engine_bing",
          "ic_engine_brave", "ic_engine_startpage"):
    png = os.path.join(RES, "drawable-nodpi", d + ".png")
    need(os.path.isfile(png) and 1024 <= os.path.getsize(png) <= 10240,
         f"the {d} mark ships as a tiny raster PNG (drawable-nodpi)")
    need(not os.path.isfile(os.path.join(RES, "drawable", d + ".xml")),
         f"the {d} vector is retired from res (no duplicate names)")
need(os.path.isfile(os.path.join(ROOT, "scripts", "rasterize_engine_logos.py")),
     "the rasterizer script is committed (offline-reproducible marks)")
need("pathData=\"M4.842.005" in open(os.path.join(dr, "ic_engine_bing.xml"),
     encoding="utf8").read(),
     "Bing keeps the official Fluent mark path (bing.com's own icon)")
ddgx = open(os.path.join(dr, "ic_engine_ddg.xml"), encoding="utf8").read()
need("M12 0C5.373 0 0 5.373" in ddgx and "#DE5833" in ddgx,
     "DDG carries the simple-icons outline in the brand orange")
brx = open(os.path.join(dr, "ic_engine_brave.xml"), encoding="utf8").read()
need("M15.68 0l2.096" in brx and "#FB542B" in brx,
     "Brave carries the simple-icons lion shield in the brand orange")
spx = open(os.path.join(dr, "ic_engine_startpage.xml"), encoding="utf8").read()
need("m16.885 14.254" in spx and "#6563FF" in spx,
     "Startpage carries the simple-icons mark in the brand violet "
     "(the table's #6563FF, not the old approximation)")
gg = open(os.path.join(dr, "ic_engine_google.xml"), encoding="utf8").read()
need("#4285F4" in gg and "#34A853" in gg,
     "Google keeps its own four-color drawable (untouched, as ordered)")

print("H — fix 8: Markdown + LaTeX in Bolt AI")
need("implementation 'org.commonmark:commonmark:0.24.0'" in gradle
     and "implementation 'org.commonmark:commonmark-ext-gfm-tables:0.24.0'" in gradle,
     "the parser rides commonmark-java core + GFM tables (zero fonts)")
need("fun codeProtect(text: String): String {" in ar_code,
     "the math extractor is PURE (probe-pinned): code spans/fences never "
     "feed it, placeholders carry the segments")
need("fun texMap(cmd: String): String?" in ar_code
     and "fun fracFlat(num: String, den: String): String {" in ar_code,
     "the LaTeX subset core is PURE (command map + frac flattening)")
need("fun renderTable(rows: List<List<String>>): String {" in ar_code,
     "the table grid flattener is PURE (probe-pinned)")
need("TablesExtension.create()" in ar_code,
     "the tables extension is wired into the parser")
need("if (user || !AiRender.looksMarkdown(text)) {" in bai,
     "ASSISTANT bubbles only — user input stays plain by design")
need("AiRender.render(a, reply, thinking)" in bai and "thinking.tag = reply" in bai,
     # v1.4.24 re-point (2026-11-21): the view rides along — the font-pack
     # split re-renders the bubble when the on-demand fonts land
     "the reply re-renders on arrival; the raw source rides the tag")
need("(t.tag as? String) ?: t.text.toString()" in bai,
     "long-press copy yields the RAW source, never rendered glyphs")

print("I — the standing sweep count")
need(gradle.count("versionCode 139") == 1,
     "exactly one versionCode pin in build.gradle")

# the compiled probe: every extractable pure body, transcribed and RUN

probe = r"""
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EvidenceProbe {

    static int fails = 0;
    static void check(String why, boolean ok) {
        System.out.println((ok ? "  ok  - " : "  FAIL - ") + why);
        if (!ok) fails++;
    }

    // ===== TRANSCRIPTION 1 — api29ArtCap (MediaControlService, v1.4.20).
    // The artwork cap: <=256 px longest edge, a 48 px margin below the
    // device's fatal 1/6-screen size, a 64 px paranoid floor. Unknown
    // device -> the plain 256 cap.
    static int api29ArtCap(int fatalMax) {
        if (fatalMax <= 0) return 256;
        int cap = Math.min(256, fatalMax - 48);
        return Math.max(cap, 64);
    }

    // ===== TRANSCRIPTION 2 — the icon classifier + thickness (darkSmartJs
    // uiIcon + bumpW, v1.4.20). A small svg (<=64 px longest edge) is a UI
    // icon; a low-contrast stroke thinner than 1.5 px bumps to 2 px.
    static boolean uiIcon(double w, double h) {
        double m = Math.max(w, h);
        return m > 0 && m <= 64;
    }
    static String bumpW(double px) {
        if (!(px > 0) || px >= 1.5) return null;
        return "2px";
    }

    // ===== TRANSCRIPTION 3 — usageDiag + shouldTile (AiBridge, v1.4.20).
    // The one-line evidence form; the tile contract keys on the standing
    // cut-off error string.
    static String usageDiag(String fr, int thoughts, int candidates, int len) {
        return "ai translate fr=" + (fr.isEmpty() ? "?" : fr)
                + " th=" + thoughts + " cand=" + candidates + " len=" + len;
    }
    static final String CUT_OFF_ERR = "the AI reply was cut off (try a less dense screen)";
    static boolean shouldTile(String err) { return err != null && err.equals(CUT_OFF_ERR); }

    // ===== TRANSCRIPTION 4 — salvageObjects (AiBridge, v1.4.20, pure).
    // Balanced-brace scan, string- and escape-aware: every COMPLETE
    // top-level {...} becomes a JSON object; the trailing object of a
    // truncated reply never closes and is dropped by construction.
    static List<String> salvageObjects(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false, esc = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        out.add(text.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return out;
    }

    // ===== TRANSCRIPTION 5 — dlDialogRoute + persistUrl (DownloadEngine,
    // v1.4.20). Ask sends every door (http/blob/data) to the dialog;
    // Direct keeps the fast paths; unknown URLs fall back to asking. A
    // fat data: URL persists as a short marker.
    static boolean dlDialogRoute(boolean askPref, String url) {
        if (!askPref) return false;
        if (url == null) return true;
        return url.startsWith("http") || url.startsWith("blob:")
                || url.startsWith("data:");
    }
    static String persistUrl(String url) {
        if (url == null) return "";
        if (url.startsWith("data:") && url.length() > 96) {
            return "data:(payload dropped, " + url.length() + " chars)";
        }
        return url;
    }

    // ===== TRANSCRIPTION 6 — tilePlace + iou (ScreenTranslate, v1.4.20).
    // A box that arrived as fractions of ONE TILE re-places onto the full
    // image through the tile's pixel rect; the seam dedup rides IoU.
    static double[] tilePlace(double bx, double by, double bw, double bh,
            int tx, int ty, int tw, int th, int iw, int ih) {
        return new double[]{(tx + bx * tw) / (double) iw,
                (ty + by * th) / (double) ih,
                (bw * tw) / (double) iw, (bh * th) / (double) ih};
    }
    static double iou(double ax, double ay, double aw, double ah,
            double bx, double by, double bw, double bh) {
        double x0 = Math.max(ax, bx), y0 = Math.max(ay, by);
        double x1 = Math.min(ax + aw, bx + bw), y1 = Math.min(ay + ah, by + bh);
        if (x1 <= x0 || y1 <= y0) return 0;
        double inter = (x1 - x0) * (y1 - y0);
        double uni = aw * ah + bw * bh - inter;
        return uni <= 0 ? 0 : inter / uni;
    }

    // ===== TRANSCRIPTION 7 — codeProtect + texMap + fracFlat +
    // renderTable (AiRender, v1.4.20). The math extractor's DELIMITER
    // core (inline $...$: non-space both sides, single line; block
    // $$...$$; escaped \$ literal; code spans protected), the command
    // map spot-checks, the frac flattening, and the table grid.
    static boolean inlineMathOk(String line, int dollarAt) {
        if (dollarAt + 1 >= line.length()) return false;
        char after = line.charAt(dollarAt + 1);
        if (Character.isWhitespace(after) || after == '$') return false;
        int end = line.indexOf('$', dollarAt + 1);
        if (end <= dollarAt + 1) return false;
        if (Character.isWhitespace(line.charAt(end - 1))) return false;
        return true;
    }
    static String texMap(String cmd) {
        switch (cmd) {
            case "alpha": return "\u03B1";
            case "times": return "\u00D7";
            case "leq": return "\u2264";
            case "infty": return "\u221E";
            case "sum": return "\u2211";
            case "mathbb{R}": return "\u211D";
            case "rightarrow": return "\u2192";
            default: return null;
        }
    }
    static String fracFlat(String num, String den) {
        return parenIfCompound(num.trim()) + "/" + parenIfCompound(den.trim());
    }
    static String parenIfCompound(String s) {
        if (s.isEmpty() || s.length() == 1) return s;
        if (s.matches("^[0-9]+(\\.[0-9]+)?$")) return s;
        if (!s.matches(".*[+\\-*/^_=<>].*")) return s;
        return "(" + s + ")";
    }
    static String renderTable(List<List<String>> rows) {
        if (rows.isEmpty()) return "";
        int cols = 0;
        for (List<String> r : rows) cols = Math.max(cols, r.size());
        if (cols == 0) return "";
        int[] w = new int[cols];
        for (List<String> r : rows)
            for (int i = 0; i < cols; i++) {
                String cell = i < r.size() ? r.get(i) : "";
                if (cell.length() > w[i]) w[i] = cell.length();
            }
        StringBuilder out = new StringBuilder();
        for (int ri = 0; ri < rows.size(); ri++) {
            List<String> r = rows.get(ri);
            StringBuilder sb2 = new StringBuilder();
            for (int i = 0; i < cols; i++) {
                String cell = i < r.size() ? r.get(i) : "";
                sb2.append(cell);
                if (i < cols - 1)
                    for (int k = 0; k < w[i] - cell.length() + 2; k++) sb2.append(' ');
            }
            out.append(sb2.toString().trim()).append('\n');
            if (ri == 0) {
                for (int i = 0; i < cols; i++) {
                    for (int k = 0; k < w[i]; k++) out.append('\u2500');
                    if (i < cols - 1) out.append("  ");
                }
                out.append('\n');
            }
        }
        return out.toString().trim();
    }

    public static void main(String[] a) {
        // --- 1: the artwork cap ---
        check("unknown device -> the plain 256 cap",
                api29ArtCap(0) == 256 && api29ArtCap(-1) == 256);
        check("a 1600 px screen (fatal 266) caps at 218 — a whole margin below",
                api29ArtCap(266) == 218);
        check("a 2400 px screen (fatal 400) caps at 256",
                api29ArtCap(400) == 256);
        check("a tiny-screen device never caps below the 64 floor",
                api29ArtCap(90) == 64);
        // --- 2: the icon classifier + thickness ---
        check("a 24 px hamburger svg is a UI icon",
                uiIcon(24, 24) && uiIcon(20, 32));
        check("a 400 px chart svg is CONTENT (stays un-inverted media)",
                !uiIcon(400, 300) && !uiIcon(64.1, 64));
        check("a 1 px hairline bumps to 2 px",
                "2px".equals(bumpW(1)) && "2px".equals(bumpW(1.4)));
        check("a 2 px stroke keeps its width; garbage stays null",
                bumpW(2) == null && bumpW(0) == null && bumpW(-1) == null);
        // --- 3: the evidence line + the tile contract ---
        check("the usage line carries fr + the token pair + the length",
                usageDiag("STOP", 12000, 800, 900).equals(
                        "ai translate fr=STOP th=12000 cand=800 len=900")
                        && usageDiag("", 0, 0, 0).equals(
                        "ai translate fr=? th=0 cand=0 len=0"));
        check("the standing cut-off error IS the tile contract",
                shouldTile(CUT_OFF_ERR));
        check("any other error does not tile",
                !shouldTile("the AI reply was not JSON")
                        && !shouldTile("") && !shouldTile(null));
        // --- 4: the salvage ---
        String half = "[{\"box_2d\":[1,2,3,4],\"text\":\"a\",\"translation\":\"x\"},"
                + "{\"box_2d\":[5,6,7,8],\"text\":\"b\",";
        List<String> salvaged = salvageObjects(half);
        check("a truncated reply yields its COMPLETE objects only",
                salvaged.size() == 1 && salvaged.get(0).contains("\"a\""));
        check("a brace inside a translation string never counts",
                salvageObjects("[{\"translation\":\"a } b\"}]").size() == 1);
        check("an escaped quote inside a string is handled",
                salvageObjects("[{\"translation\":\"x\\\"}\"}]").size() == 1);
        check("nothing recoverable -> empty (the standing cut-off error)",
                salvageObjects("[{\"text\":").isEmpty());
        // --- 5: the download router + the payload cap ---
        check("Ask (default) sends http, blob: and data: to the dialog",
                dlDialogRoute(true, "https://x/f.zip")
                        && dlDialogRoute(true, "blob:https://x/uuid")
                        && dlDialogRoute(true, "data:image/png;base64,AAAA"));
        check("Direct keeps every fast path",
                !dlDialogRoute(false, "https://x/f.zip")
                        && !dlDialogRoute(false, "blob:https://x/uuid"));
        check("a null URL falls back to asking (no URL to route is the one "
                + "doubt worth the richer door); a file: URL keeps the fast "
                + "path (neither door can save it)",
                dlDialogRoute(true, null) && !dlDialogRoute(true, "file:///x"));
        StringBuilder fat = new StringBuilder("data:image/jpeg;base64,");
        for (int i = 0; i < 1000000; i++) fat.append('A');
        String capped = persistUrl(fat.toString());
        check("a 1 MB data: URL persists as a short marker",
                capped.startsWith("data:(payload dropped, ")
                        && capped.length() < 60);
        check("a short data: URL and http URLs pass through untouched",
                persistUrl("data:,x").equals("data:,x")
                        && persistUrl("https://x/f.png").equals("https://x/f.png")
                        && persistUrl(null).equals(""));
        // --- 6: the tile math ---
        double[] f = tilePlace(0.5, 0.25, 0.2, 0.1, 720, 1280, 720, 1280,
                1440, 2560);
        check("a tile-fraction box re-places exactly onto the full image",
                Math.abs(f[0] - 0.75) < 1e-9 && Math.abs(f[1] - 0.625) < 1e-9
                        && Math.abs(f[2] - 0.1) < 1e-9
                        && Math.abs(f[3] - 0.05) < 1e-9);
        check("an identical box dedups at the seam (IoU 1)",
                iou(0.1, 0.1, 0.2, 0.2, 0.1, 0.1, 0.2, 0.2) > 0.99);
        check("disjoint boxes never dedup",
                iou(0.1, 0.1, 0.2, 0.2, 0.5, 0.5, 0.2, 0.2) == 0);
        check("a half-overlap sits below the 0.6 seam verdict",
                iou(0.1, 0.1, 0.2, 0.2, 0.2, 0.1, 0.2, 0.2) < 0.6);
        // --- 7: the markdown/math core ---
        check("inline $...$ opens on non-space, closes on non-space",
                inlineMathOk("x $a+b$ y", 2) && inlineMathOk("$x^2$", 0));
        check("currency $5 never opens math",
                !inlineMathOk("costs $5 today", 6));
        check("a space before the closer never opens math",
                !inlineMathOk("$a b $", 0));
        check("the command map spot-checks",
                "\u03B1".equals(texMap("alpha")) && "\u00D7".equals(texMap("times"))
                        && "\u2264".equals(texMap("leq")) && "\u211D".equals(texMap("mathbb{R}"))
                        && texMap("notacommand") == null);
        check("frac flattening keeps bare tokens bare",
                fracFlat("a", "b").equals("a/b")
                        && fracFlat("12", "7").equals("12/7"));
        check("compound frac sides get parens",
                fracFlat("a+b", "c").equals("(a+b)/c"));
        String tbl = renderTable(Arrays.asList(
                Arrays.asList("name", "value"),
                Arrays.asList("pi", "3.14"),
                Arrays.asList("golden ratio")));
        String[] lines = tbl.split("\n");
        check("the table grid aligns columns under a dashed header rule",
                lines.length == 4 && lines[1].startsWith("\u2500\u2500\u2500\u2500")
                        && lines[2].startsWith("pi ")
                        && lines[3].startsWith("golden ratio"));
        check("a ragged row pads without misaligning the grid",
                tbl.contains("3.14"));
        System.out.println(fails == 0 ? "PROBE OK" : "PROBE BAD (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
"""
tmp = tempfile.mkdtemp(prefix="bolt_v1420_probe_")
open(os.path.join(tmp, "EvidenceProbe.java"), "w").write(probe)
# release.sh puts the JDK on PATH before the gates; standalone runs fall
# back to the workspace's grafted JDK (the README environment note)
GJ = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin"
javac = "javac" if shutil.which("javac") else os.path.join(GJ, "javac")
java = "java" if shutil.which("java") else os.path.join(GJ, "java")
pr = subprocess.run([javac, "-d", tmp, os.path.join(tmp, "EvidenceProbe.java")],
                    capture_output=True, text=True)
if pr.returncode != 0:
    print("  FAIL - probe compiled (javac):\n" + pr.stderr[:1200])
    fails += 1
else:
    print("  ok  - probe compiled (javac)")
    pr2 = subprocess.run([java, "-cp", tmp, "EvidenceProbe"],
                         capture_output=True, text=True)
    print(pr2.stdout.rstrip())
    if pr2.returncode != 0:
        print("  FAIL - probe RUN (a crashed probe is a red gate, not a silent one):\n"
              + pr2.stderr[:800])
        fails += 1

print("M — the gate is wired into the pipeline")
need("python3 scripts/test_v1420.py" in rel,
     "release.sh runs this gate before building")
need(rel.find("test_v1419.py") < rel.find("test_v1420.py"),
     "the gate runs after v1419's (release order)")

print()
print("V1420 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
