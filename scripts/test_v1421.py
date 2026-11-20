#!/usr/bin/env python3
"""v1.4.21 release gate — the corpse-map six-pack.

THE LAW (carried): a green gate proves code EXISTS, not that a bug is
CURED — every fix carries a compiled+run BEHAVIOR probe where a pure body
could be extracted (the v1.4.11 parse-gate law) and a Diag breadcrumb
where it could not. v1.4.21's field order arrived root-caused from the
v1.4.20 source + the user's diag.log; the cures implement the named
mechanisms, not new guesses.

1. MEDIA-NOTIFICATION KILL (MediaControlService, the id=42 async corpse).
   The v1.4.20 cap never touched the real killer: publishSessionState put
   the SAME artCopy() instance into BOTH METADATA_KEY_ART and
   METADATA_KEY_ALBUM_ART — API-29 SystemUI recycles one processed bitmap
   and the twin key then parcels a RECYCLED bitmap -> async
   RemoteServiceException -> process death. Every channel (largeIcon,
   ART, ALBUM_ART) now takes its OWN fresh copy, and on API <= 29 artwork
   leaves the service at all only when the user opts in (the Show media
   artwork setting, default OFF there — three artwork fixes failed;
   text-only cannot crash). Suppression logs per artwork generation.

2. BACK HISTORY LOST (TabSleep / BrowserActivity / TabManager). sleep()
   destroyed the WebView keeping only sleepScrollY; the rebuild loaded
   t.url onto an EMPTY back/forward list and Back fell out of the tab —
   and the device's trimMemory level=15 on every app switch made every
   background tab sleep constantly. Every destroy path now captures
   WebView.saveState() first (sleepState, warm) and tabs.json persists
   the capped parcel blob ("hist", cold); the rebuild restores it before
   falling back to loadUrl; the trim sleep pass now runs from level >= 60
   (MODERATE), so brief switches keep their renderers.

3. AI TRANSLATE "HTTP 400" (AiBridge + ScreenTranslate). The lite model
   REJECTS the thinkingBudget field itself; the v1.4.20 retry only fired
   when the 400 body named "thinking". Translate now never SENDS
   thinkingConfig, every 400 body rides RAW to Diag, the cap ladders DOWN
   (cap400Next: 65536->32768->16384->8192) to the model's own output
   ceiling, and the 2x2 tile road runs PROACTIVELY (a dense quadrant
   splits once more). A dedicated translate-model picker lets the user
   pick a higher-ceiling model.

4. FORCED-DARK HAMBURGER STILL BLACK (MediaHook). Menus served as IMG
   (menu.svg/.png) and CSS background icons are MEDIA — un-inverted and
   invisible on the black ground. The classifier now covers small IMGs in
   UI CONTEXT (iconNamed + uiCtx: button/header/nav/label/summary/
   role=button/icon-named strong, bare-link weak at <=48 px) and small
   background-image icons on interactive elements (bgUiIcon) — all classed
   .__boltIC to ride the page inversion.

5. ENGINE LOGOS (the DDG monochrome cutout). The simple-icons DDG path is
   a cutout — the duck showed the omnibox BACKGROUND (black), not white.
   Rebuilt as the official two-color mark: the #DE5833 disc + the SAME CC0
   duck geometry in WHITE (two paths). applyEngineLogo logs the drawable
   resource name actually set (the binding breadcrumb — the "Bing/Brave
   show the duck" report becomes provable next round).

6. AI FORMATTING COMPLETED (AiRender). GFM strikethrough extension +
   StrikethroughSpan; tables carry a BOLD accent header over the aligned
   grid; code blocks get a bounded theme-aware syntax colorizer
   (tokenizeCode: comments/strings/keywords/numbers, 9 language families);
   LaTeX upgrades to FULL TeX via jlatexmath-android (matrices, cases,
   align — ImageSpan on a native drawable, lazily initialized, its
   auto-init provider REMOVED from the merged manifest, span-subset
   fallback kept); images render as alt-text links; nested lists indent.
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
manifest = open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"),
                encoding="utf8").read()
proguard = open(os.path.join(ROOT, "app/proguard-rules.pro"),
                encoding="utf8").read()
thirdparty = open(os.path.join(ROOT, "THIRDPARTY.md"), encoding="utf8").read()
mcs = open(os.path.join(SRC, "media/MediaControlService.java"),
           encoding="utf8").read()
mh = open(os.path.join(SRC, "media/MediaHook.java"), encoding="utf8").read()
ab = open(os.path.join(SRC, "util/AiBridge.kt"), encoding="utf8").read()
st = open(os.path.join(SRC, "util/ScreenTranslate.kt"), encoding="utf8").read()
ar = open(os.path.join(SRC, "util/AiRender.kt"), encoding="utf8").read()
tab = open(os.path.join(SRC, "core/Tab.java"), encoding="utf8").read()
tmj = open(os.path.join(SRC, "core/TabManager.java"), encoding="utf8").read()
tsl = open(os.path.join(SRC, "util/TabSleep.kt"), encoding="utf8").read()
ba = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
pf = open(os.path.join(SRC, "util/Prefs.java"), encoding="utf8").read()
sia = open(os.path.join(SRC, "ui/SettingsItemsActivity.java"),
           encoding="utf8").read()
strs = open(os.path.join(RES, "values/strings.xml"), encoding="utf8").read()
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
        if mode == c:
            mode = None
        elif c == "\\" and i + 1 < n:
            i += 1
        i += 1; continue
    return "".join(out)


mcs_code = strip_comments_strings(mcs)
ab_code = strip_comments_strings(ab)
st_code = strip_comments_strings(st)
ar_code = strip_comments_strings(ar)
tab_code = strip_comments_strings(tab)
tmj_code = strip_comments_strings(tmj)
tsl_code = strip_comments_strings(tsl)
ba_code = strip_comments_strings(ba)

print("V — the version pin")
need("versionCode 137" in gradle, "versionCode 135 (Android only installs RISING codes)")
need('versionName "1.4.23"' in gradle, 'versionName "1.4.23"')

print("A — fix 1: the per-channel artwork copies + the API-29 off default")
need("Bitmap art = artCopy();\n                if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ART, art);\n"
     "                Bitmap art2 = artCopy();\n"
     "                if (art2 != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art2);" in mcs_code,
     "THE THREE-CHANNEL LAW: largeIcon, METADATA_KEY_ART and "
     "METADATA_KEY_ALBUM_ART each take their OWN artCopy() — the shared "
     "instance (recycled by API-29 SystemUI in one key, fatal in the twin) "
     "is gone")
need("public static boolean artworkDefaultOn(int sdkInt) { return sdkInt >= 30; }" in mcs_code,
     "the default resolver is PURE (probe-pinned): OFF on API <= 29, ON above")
need('return b("mediaArtwork"' in pf
     and "artworkDefaultOn(android.os.Build.VERSION.SDK_INT)" in pf,
     "the pref rides the resolver (same default, one source of truth)")
need("private boolean artworkAllowed()" in mcs_code
     and "if (!artworkAllowed()) {" in mcs_code,
     "artCopy consults the gate — on API <= 29 (default) NO bitmap leaves "
     "the process (text-only cannot crash)")
need('"art suppressed api<=29 gen="' in mcs,
     "the suppression Diag breadcrumb (once per artwork generation; RAW)")
need('getString(R.string.media_artwork)' in sia
     and "setMediaArtwork" in sia,
     "Settings > Media carries the Show-media-artwork toggle (user opt-in)")
need("public static void requestPublish(Context c)" in mcs_code
     and "s.lastNotifArtGen = -1;" in mcs_code,
     "the toggle re-publishes immediately (the artwork presence IS the "
     "visible change — the repost gate ages to fire)")

print("B — fix 2: back-history survival across sleep / trim / cold start")
need("public Bundle sleepState = null;" in tab_code
     and "public String histBlob = null;" in tab_code,
     "Tab carries the warm saveState Bundle + the cold Base64 blob")
need("public void saveHist(WebView w) {" in tab_code
     and "public boolean restoreHist(WebView w) {" in tab_code,
     "the capture/restore pair exists on Tab")
need("public static String histToBlob(Bundle st)" in tab_code
     and "public static Bundle blobToHist(String blob)" in tab_code
     and "HIST_BLOB_CAP" in tab_code,
     "the parcel<->blob codec is capped (the downloads.json lesson)")
need("try { t.saveHist(w) } catch (ignored: Throwable) {}" in tsl
     or "try { t.saveHist(w) } catch (ignored: Throwable) {}" in tsl.replace("  ", " "),
     "TabSleep.sleep captures state BEFORE destroy (RAW — Kotlin try)")
need("t.saveHist(t.web);" in ba_code,
     "the trim-path sleepTab captures state BEFORE the blank+destroy")
_sleepSlice = ba_code.split("protected void sleepTab(Tab t) {")[1].split("detachTabsForSwitch")[0]
need("t.sleeping = true;" in _sleepSlice,
     "the trim path sets the sleep flags (scroll + history restore on wake)")
need("boolean realPressure = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE;" in ba_code
     and "if (anyMedia && realPressure)" in ba_code,
     "THE SOFTENED TRIGGER: the sleep pass runs from level >= 60 — the "
     "device's level=15 app switches no longer destroy background tabs "
     "(the thumbnail drop keeps its cheap old gate)")
need("t.saveHist(view);" in ba_code.split("onRenderProcessGone")[1],
     "the renderer-death path captures state BEFORE destroy (the history "
     "lives in the browser process — render death does not take it)")
need("boolean restored = false;" in ba_code
     and "restored = t.restoreHist(w);" in ba_code
     and "if (!restored) {\n                        w.loadUrl(t.url);" in ba_code,
     "THE HISTORY-FIRST LAW: the rebuild restores before loadUrl, and "
     "falls back to exactly the old shape")
need('"tab hist restore idx="' in ba,
     "the restore Diag breadcrumb names the door (warm vs cold; RAW)")
need('o.put("hist", histBlob);' in tab,
     "toJson persists the blob (tabs.json carries history for cold starts; RAW)")
need('String hb = o.optString("hist", null);' in tmj,
     "the TabManager restore reads the blob (legacy saves keep the "
     "loadUrl behavior; RAW)")
need("boolean onMain = android.os.Looper.myLooper()" in tmj_code
     and "t.saveHist(t.web);" in tmj_code,
     "save() refreshes AWAKE tabs' captures on the main thread (a fresh "
     "history at every tabs.json write — never a stale restore)")

print("C — fix 3: the translate 400 ladder-down + proactive tiles")
need("private fun visionBody(p: String, b64: String, cap: Int): JSONObject {" in ab_code,
     "the translate body carries NO think param (thinkingConfig omitted "
     "entirely — a field never sent cannot be rejected)")
need("internal fun cap400Next(cur: Int): Int" in ab_code,
     "the 400 cap ladder is PURE (probe-pinned)")
need('"ai translate 400 raw cap=$c body="' in ab,
     "the RAW 400 body rides to Diag (shortErr hid the exact rejection; RAW)")
need("while (r.first == 400) {" in ab_code,
     "ANY 400 walks the ladder-down (not just a body naming thinking)")
need("AiBridge.textTranslate(a, lines, tl)" in st_code  # v1.4.23 re-point (2026-11-20): OCR-first hybrid — tiling demoted to the genuine-cut fallback (visionFallback); the fresh pins live in test_v1423 section B
     and "visionFallback(a, bmp, g, tl," in st_code,
     "OCR FIRST, ONE pure-text request, the vision path demoted to the "
     "genuine-cut / OCR-blind fallback (the hybrid; the old proactive-"
     "tiles pin retired)")
need("scr ai tile split" in st,
     "the per-tile escalation breadcrumb: a quadrant that still exhausts "
     "the cap splits once more (shouldTile stays live as its contract; RAW)")
need('getString("translateModel", "")' in ab,
     "the translate-model picker: a dedicated model for screen translate, "
     "the shared chat model otherwise (RAW)")
need('"Translate model (optional' in ab,
     "the picker's field rides the AI settings dialog (RAW)")

print("D — fix 4: the IMG + background-image icon classifier")
need("function iconNamed(el){" in mh
     and "(icon|menu|hamburger|burger|nav|toggle|chevron|arrow|close|cancel|gear|search)" in mh,
     "the icon-name matcher (class/id, SVG baseVal aware)")
need("function photoCtx(el){" in mh  # v1.4.23 re-point (2026-11-20): the uiCtx family is RETIRED — the DEFAULT-FLIP round (small media rides the inversion unless clearly content); the fresh pins live in test_v1423 section A
     and "t==='ARTICLE'||t==='MAIN'||t==='P'||t==='FIGURE'" in mh,
     "the CONTENT walk: photo-classed ancestors (article/main/p/figure) "
     "within 6 levels keep their media un-inverted")
need("function bannerish(el){" in mh and "(mx/mn)>=2.5" in mh,  # v1.4.23 re-point (2026-11-20): the uiCtx family is RETIRED — the DEFAULT-FLIP round; the fresh pins live in test_v1423 section A
     "the CONTENT markers: banner-shaped (aspect >= 2.5) small media "
     "stays un-inverted; everything else small RIDES the flip (the "
     "uiCtx link-guess retired)")
need("function bgUiIcon(el){" in mh  # v1.4.23 re-point (2026-11-20): the uiCtx family is RETIRED — the DEFAULT-FLIP round; the fresh pins live in test_v1423 section A
     and "if(bgUiIcon(e)){e.classList.add(IC);cnt.ic++;}" in mh,
     "small background-image icons on interactive elements ride the IC "
     "class too (the NI pin alone kept them black; the census counts)")
need("try{if(el.classList&&el.classList.contains(IC))return inv;}catch(x){}" in mh,
     "an IC-pinned element is a LEAF (the walk never stacks DK on top of "
     "the IC filter — one class, one meaning)")

print("E — fix 5: the DDG two-color mark + the binding breadcrumb")
dr = os.path.join(ROOT, "scripts", "engine_vectors")  # v1.4.23 re-point (2026-11-20): the vectors retired to the archive
ddgx = open(os.path.join(dr, "ic_engine_ddg.xml"), encoding="utf8").read()
need(ddgx.count("<path") == 2 and "#DE5833" in ddgx and "#FFFFFF" in ddgx,
     "DDG is the OFFICIAL two-color mark: the orange disc + a WHITE duck "
     "(two paths — the duck is white on every background)")
need("M22.219 12c0 4.805" in ddgx,
     "the duck is the same CC0 simple-icons geometry (official outline, "
     "recolored — never a hand approximation)")
need("m0 23C5.925 23" not in ddgx,
     "the monochrome cutout's ring subpath is gone (the disc replaces it)")
brx = open(os.path.join(dr, "ic_engine_brave.xml"), encoding="utf8").read()
bgx = open(os.path.join(dr, "ic_engine_bing.xml"), encoding="utf8").read()
spx = open(os.path.join(dr, "ic_engine_startpage.xml"), encoding="utf8").read()
need("M15.68 0l2.096" in brx and "#FB542B" in brx,
     "Brave keeps its own lion shield (distinct official path)")
need("M4.842.005" in bgx, "Bing keeps its own Fluent mark")
need("m16.885 14.254" in spx and "#6563FF" in spx,
     "Startpage keeps its own mark (distinct official path)")
need('"engine logo set engine="' in ba,
     "THE BINDING BREADCRUMB: applyEngineLogo logs the drawable resource "
     "name actually set per engine (the next 'wrong logo' report is "
     "provable; RAW)")

print("F — fix 6: AI formatting completed")
need("implementation 'org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0'" in gradle
     and "implementation 'ru.noties:jlatexmath-android:0.2.0'" in gradle,
     "the strikethrough extension + jlatexmath-android ride the build")
need("StrikethroughExtension.create()" in ar_code
     and "is Strikethrough -> {" in ar_code
     and "StrikethroughSpan()" in ar_code,
     "GFM strikethrough parses and renders (~~text~~)")
need("fun tokenizeCode(code: String, lang: String): List<Tok>" in ar_code,
     "the syntax colorizer is PURE (probe-pinned)")
need("tokenizeCode(code, info.trim().substringBefore(' ').lowercase())" in ar,
     "code blocks colorize by the fence's info string (RAW — the strip "
     "pass blanks the delimiter literal)")
need("fun mathDrawable(c: Context, tex: String, block: Boolean)" in ar_code
     and "JLatexMathDrawable.builder(tex)" in ar_code,
     "FULL TeX renders through jlatexmath (matrices, cases, align)")
need("ImageSpan.ALIGN_BOTTOM" in ar_code and "fun appendMath(tex: String, block: Boolean)" in ar_code,
     "the math ImageSpan rides the bubble, with the span-subset fallback")
need("ru.noties.jlatexmath.JLatexMathAndroid.init" in ar_code,
     "the library inits LAZILY (first math segment, not app start)")
need("tools:node=\"remove\"" in manifest
     and "JLatexMathInitProvider" in manifest,
     "the auto-init provider is REMOVED from the merged manifest (the "
     "startup law)")
need("-keep class org.scilab.forge.jlatexmath.**" in proguard,
     "the reflection-driven TeX tables keep their atoms (R8 keep rule)")
need("is Image -> {" in ar_code,
     "images render as alt-text links (no fetch in a chat bubble)")
need('"    ".repeat(listLevel - 1)' in ar,
     "nested lists indent per level (RAW — the pad literal)")
need("sb.setSpan(StyleSpan(Typeface.BOLD), start, nl, 0)" in ar_code
     and "val nl = sb.indexOf" in ar_code,
     "tables carry a BOLD accent header line over the aligned grid")
need('s.contains("~~")' in ar,
     "~~ joins the markdown signal set (the fast-path gate; RAW)")
need("jlatexmath-android" in thirdparty and "GPL-2.0" in thirdparty,
     "THIRDPARTY.md carries the jlatexmath-android license (GPL-2.0 + "
     "linking exception)")

print("G — the standing sweep count")
need(gradle.count("versionCode 137") == 1,
     "exactly one versionCode pin in build.gradle")

# the compiled probe: every extractable pure body, transcribed and RUN

probe = r"""
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CorpseMapProbe {

    static int fails = 0;
    static void check(String why, boolean ok) {
        System.out.println((ok ? "  ok  - " : "  FAIL - ") + why);
        if (!ok) fails++;
    }

    // ===== TRANSCRIPTION 1 — artworkDefaultOn (MediaControlService, v1.4.21).
    // The artwork master-switch default: OFF on the API-29-and-below
    // SystemUI recycle family, ON from API 30 up.
    static boolean artworkDefaultOn(int sdkInt) { return sdkInt >= 30; }

    // ===== TRANSCRIPTION 2 — cap400Next (AiBridge, v1.4.21). The HTTP-400
    // cap ladder: step the output cap DOWN toward the model's own ceiling
    // until the 8192 floor.
    static int cap400Next(int cur) {
        if (cur == 65536) return 32768;
        if (cur == 32768) return 16384;
        if (cur == 16384) return 8192;
        return -1;
    }

    // ===== TRANSCRIPTION 3 — the three-channel copy invariant (the v1.4.21
    // root cause as a RUN model): every artwork channel takes its OWN
    // artCopy() — a fresh object per call. SystemUI recycles ONE channel's
    // bitmap; the others must remain valid (never the same instance).
    static class Art { boolean recycled = false; }
    static int artCopies = 0;
    static Art artCopy() { artCopies++; return new Art(); }
    static boolean perChannelCopies() {
        artCopies = 0;
        Art large = artCopy();   // build().setLargeIcon(artCopy())
        Art art   = artCopy();   // METADATA_KEY_ART
        Art art2  = artCopy();   // METADATA_KEY_ALBUM_ART
        if (artCopies != 3) return false;
        if (large == art || art == art2 || large == art2) return false;
        // SystemUI recycles the ART channel's bitmap:
        art.recycled = true;
        // the ALBUM_ART channel must still hold a VALID instance:
        return !art2.recycled && !large.recycled;
    }

    // ===== TRANSCRIPTION 4 — the UI-icon classifier (MediaHook uiIcon +
    // iconNamed + uiCtx, v1.4.21). Context FIRST, size second: a small IMG
    // in strong chrome (button/header/nav/label/summary/role=button/
    // icon-named ancestor) is chrome at <=64; a bare-link IMG is chrome
    // only at <=48 (thumbnails ride links); SVG keeps the size cut alone.
    static class El {
        String tag; double w, h; String cls, id; El parent;
        El(String t, double w, double h, String cls, El p) {
            this.tag = t; this.w = w; this.h = h; this.cls = cls == null ? "" : cls;
            this.id = ""; this.parent = p;
        }
        String clsAndId() { return cls + " " + id; }
    }
    static boolean iconNamed(El el) {
        String s = el.clsAndId().toLowerCase();
        // word-ish boundary around each family name
        return s.matches(".*(^|[^a-z])(icon|menu|hamburger|burger|nav|toggle|chevron|arrow|close|cancel|gear|search)([^a-z]|$).*");
    }
    static int uiCtx(El el) {
        El p = el.parent; int lvl = 0, weak = 0;
        while (p != null && lvl < 6) {
            String t = p.tag;
            if (t.equals("BUTTON") || t.equals("HEADER") || t.equals("NAV")
                    || t.equals("LABEL") || t.equals("SUMMARY")) return 2;
            if (iconNamed(p)) return 2;
            if (t.equals("A")) weak = 1;
            p = p.parent; lvl++;
        }
        return weak;
    }
    static boolean uiIcon(El el) {
        String t = el.tag;
        if (!t.equals("SVG") && !t.equals("IMG")) return false;
        double m = Math.max(el.w, el.h);
        if (!(m > 0 && m <= 64)) return false;
        if (t.equals("SVG")) return true;
        if (iconNamed(el)) return true;
        int ctx = uiCtx(el);
        if (ctx == 2) return true;
        if (ctx == 1) return m <= 48;
        return false;
    }

    // ===== TRANSCRIPTION 5 — tokenizeCode (AiRender, v1.4.21). The bounded
    // one-pass colorizer: comments (line tail), strings (escape-aware),
    // keywords (word boundary per language), numbers.
    static final int TOK_PLAIN = 0, TOK_COMMENT = 1, TOK_STRING = 2,
            TOK_KEYWORD = 3, TOK_NUMBER = 4;
    static class Tok { int start, end, kind; }
    static List<Tok> tokenizeCode(String code, String lang) {
        List<Tok> out = new ArrayList<>();
        if (code == null || code.isEmpty() || code.length() > 20000) return out;
        String lc = null;
        if (lang.equals("kotlin") || lang.equals("java") || lang.equals("js")) lc = "//";
        if (lang.equals("python") || lang.equals("bash")) lc = "#";
        java.util.HashSet<String> kws = new java.util.HashSet<>();
        if (lang.equals("kotlin")) kws.addAll(Arrays.asList("fun","val","var","return","class"));
        if (lang.equals("python")) kws.addAll(Arrays.asList("def","import","lambda","None"));
        int i = 0, n = code.length(), tokCount = 0;
        while (i < n) {
            if (tokCount >= 4000) return new ArrayList<>();
            char c = code.charAt(i);
            if (lc != null && c == lc.charAt(0) && code.startsWith(lc, i)) {
                int e = code.indexOf('\n', i); if (e < 0) e = n;
                Tok t = new Tok(); t.start = i; t.end = e; t.kind = TOK_COMMENT;
                out.add(t); i = e;
            } else if (c == '"' || c == '\'' || c == '`') {
                int j = i + 1;
                while (j < n) {
                    if (code.charAt(j) == '\\') j += 2;
                    else if (code.charAt(j) == c || code.charAt(j) == '\n') break;
                    else j++;
                }
                int e = (j < n && code.charAt(j) == c) ? j + 1 : j;
                Tok t = new Tok(); t.start = i; t.end = e; t.kind = TOK_STRING;
                out.add(t); i = e; tokCount++;
            } else if (Character.isDigit(c)
                    && (i == 0 || !(Character.isLetterOrDigit(code.charAt(i-1))
                        || code.charAt(i-1) == '_'))) {
                int j = i;
                while (j < n && (Character.isDigit(code.charAt(j))
                        || code.charAt(j) == '.' || code.charAt(j) == '_')) j++;
                Tok t = new Tok(); t.start = i; t.end = j; t.kind = TOK_NUMBER;
                out.add(t); i = j; tokCount++;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(code.charAt(j))
                        || code.charAt(j) == '_')) j++;
                if (kws.contains(code.substring(i, j))) {
                    Tok t = new Tok(); t.start = i; t.end = j; t.kind = TOK_KEYWORD;
                    out.add(t); tokCount++;
                }
                i = j;
            } else i++;
        }
        return out;
    }

    // ===== TRANSCRIPTION 6 — the history-restore decision (Tab.restoreHist,
    // v1.4.21, as a pure table): the warm Bundle is preferred, the cold
    // blob rebuilds it, restoreState's verdict decides, loadUrl is last.
    static String restoreDecision(boolean hasWarm, boolean hasBlob,
                                  boolean restoreSucceeds) {
        if (hasWarm || hasBlob) {
            return restoreSucceeds ? "restored" : "loadUrl";
        }
        return "loadUrl";
    }

    public static void main(String[] a) {
        // --- 1: the artwork default ---
        check("API 29 (the dead device) defaults artwork OFF",
                !artworkDefaultOn(29) && !artworkDefaultOn(24));
        check("API 30+ defaults artwork ON",
                artworkDefaultOn(30) && artworkDefaultOn(34));
        // --- 2: the 400 ladder ---
        check("the ladder steps down 65536 -> 32768 -> 16384 -> 8192",
                cap400Next(65536) == 32768 && cap400Next(32768) == 16384
                        && cap400Next(16384) == 8192);
        check("the 8192 floor gives up (the raw-body Diag line names the rest)",
                cap400Next(8192) == -1 && cap400Next(0) == -1);
        // --- 3: the per-channel copy invariant ---
        check("three artCopy() calls, three DISTINCT instances; recycling "
                        + "one channel's bitmap never poisons another",
                perChannelCopies());
        // --- 4: the icon classifier ---
        El button = new El("BUTTON", 44, 44, "", null);
        El menuImg = new El("IMG", 20, 20, "", button);
        check("a 20px menu.png inside a button is CHROME (rides the inversion)",
                uiIcon(menuImg));
        El header = new El("HEADER", 0, 0, "", null);
        El navImg = new El("IMG", 64, 40, "", header);
        check("a 64px img inside a header is chrome", uiIcon(navImg));
        El link = new El("A", 0, 0, "article-link", null);
        El thumb = new El("IMG", 64, 36, "", link);
        check("a 64px thumbnail inside a bare link is CONTENT (not inverted)",
                !uiIcon(thumb));
        El tinyLink = new El("IMG", 40, 40, "", link);
        check("a TINY (<=48) img inside a bare link is chrome", uiIcon(tinyLink));
        El named = new El("IMG", 32, 32, "icon-close", null);
        check("an icon-named img is chrome anywhere", uiIcon(named));
        El orphan = new El("IMG", 30, 30, "", null);
        check("a small bare img with no context is content (conservative)",
                !uiIcon(orphan));
        El big = new El("IMG", 300, 200, "menu", null);
        check("a big img is content even when icon-named",
                !uiIcon(big));
        El svg = new El("SVG", 24, 24, "", null);
        El svgBig = new El("SVG", 400, 300, "", null);
        check("SVG keeps the size cut alone (the v1.4.20 law)",
                uiIcon(svg) && !uiIcon(svgBig));
        // --- 5: the colorizer ---
        List<Tok> kt = tokenizeCode(
                "fun main() { // hi\n  val s = \"a // b\"\n  val n = 42\n}", "kotlin");
        boolean hasComment = false, hasString = false, hasKw = false, hasNum = false;
        for (Tok t : kt) {
            String seg = "fun main() { // hi\n  val s = \"a // b\"\n  val n = 42\n}"
                    .substring(t.start, t.end);
            if (t.kind == TOK_COMMENT) hasComment = seg.startsWith("// hi");
            if (t.kind == TOK_STRING) hasString = seg.contains("a // b");
            if (t.kind == TOK_KEYWORD) hasKw = seg.equals("fun") || seg.equals("val");
            if (t.kind == TOK_NUMBER) hasNum = seg.equals("42");
        }
        check("kotlin: the line comment is the comment tail",
                hasComment && hasString);
        check("kotlin: the // inside a string never splits it",
                hasString);
        check("kotlin: fun/val are keywords and 42 is a number",
                hasKw && hasNum);
        List<Tok> py = tokenizeCode("def f(x):\n  return None  # note", "python");
        boolean pyKw = false, pyComment = false;
        for (Tok t : py) {
            String seg = "def f(x):\n  return None  # note".substring(t.start, t.end);
            if (t.kind == TOK_KEYWORD && (seg.equals("def") || seg.equals("return"))) pyKw = true;
            if (t.kind == TOK_COMMENT && seg.startsWith("# note")) pyComment = true;
        }
        check("python: # comments and def/return/None keywords",
                pyKw && pyComment);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 21000; i++) huge.append('x');
        check("code over the 20k cap returns empty (bounded, no jank)",
                tokenizeCode(huge.toString(), "kotlin").isEmpty());
        // --- 6: the restore decision table ---
        check("warm state + a good restore = restored (Back lives)",
                restoreDecision(true, true, true).equals("restored"));
        check("cold blob + a good restore = restored (a cold start keeps "
                        + "history too)",
                restoreDecision(false, true, true).equals("restored"));
        check("a stale blob (restore fails) = loadUrl, exactly the old shape",
                restoreDecision(false, true, false).equals("loadUrl"));
        check("nothing captured (every legacy tab) = loadUrl",
                restoreDecision(false, false, false).equals("loadUrl"));
        System.out.println(fails == 0 ? "PROBE OK" : "PROBE BAD (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
"""
tmp = tempfile.mkdtemp(prefix="bolt_v1421_probe_")
open(os.path.join(tmp, "CorpseMapProbe.java"), "w").write(probe)
GJ = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin"
javac = "javac" if shutil.which("javac") else os.path.join(GJ, "javac")
java = "java" if shutil.which("java") else os.path.join(GJ, "java")
pr = subprocess.run([javac, "-d", tmp, os.path.join(tmp, "CorpseMapProbe.java")],
                    capture_output=True, text=True)
if pr.returncode != 0:
    print("  FAIL - probe compiled (javac):\n" + pr.stderr[:1200])
    fails += 1
else:
    print("  ok  - probe compiled (javac)")
    pr2 = subprocess.run([java, "-cp", tmp, "CorpseMapProbe"],
                         capture_output=True, text=True)
    print(pr2.stdout.rstrip())
    if pr2.returncode != 0:
        print("  FAIL - probe RUN (a crashed probe is a red gate, not a silent one):\n"
              + pr2.stderr[:800])
        fails += 1

rel = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()
print("M — the gate is wired into the pipeline")
need("python3 scripts/test_v1421.py" in rel,
     "release.sh runs this gate before building")
need(rel.find("test_v1420.py") < rel.find("test_v1421.py"),
     "the gate runs after v1420's (release order)")

print()
print("V1421 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
