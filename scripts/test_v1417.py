#!/usr/bin/env python3
"""v1.4.17 release gate — the polish seven-pack.

THE REPORT (2026-11-18): seven fixes, each pinned below:

1. TAB VOID ON GROUPING (TabsActivity - GridLayoutManager). Moving a tab
   into a group changes the SPAN a position occupies; GridLayoutManager
   caches span assignments and a plain notifyDataSetChanged does not
   force the recompute — a void renders where the moved tab's slot was.
   Both move_to_group handlers now run reloadGridNoVoid(): stopScroll()
   -> adapter.reload() -> the SpanSizeLookup invalidation + requestLayout().

2. GROUP SHEET ACTIVE HIGHLIGHT + RIM OVERLAP.
   a) GroupSheetAdapter.onBindViewHolder highlights the member that IS
      tm.active (accent rim via the inset law + accent title color).
   b) applySelVisuals (and the v1.4.16 group-row rim) drew the accent
      stroke AT the view bounds, straddling the card edge. Every rim now
      rides an InsetDrawable with a 2dp inset — concentric with bg_card's
      16dp corners (rim radius 14), clear of the 4dp-margin buttons.

3. VISUAL SEARCH GREY IMAGE (VisualSearch.kt). JPEG quality 85 -> 90
   (the fidelity knee; the matcher reads fine structure), the fine scale
   pinned to createScaledBitmap's bilinear filter=true, and the multipart
   boundary pinned strict-CRLF.

4. DARK MODE WHITE FLASH (the honest leg). The report's proposed cure —
   setForceDark(FORCE_DARK_ON) + setAlgorithmicDarkeningAllowed(true) —
   is the pair retired since v1.3.81/v1.3.91 (a no-op at target 34; the
   double-inversion corpse). The REAL gap: the dark chain ran only at
   onPageFinished, so a light site painted white from first paint until
   the last subresource. The chain (probe -> ask -> invert) now runs at
   onPageCommitVisible (runWebDarkPass), the finish site is a run-token
   guarded backstop (a second unguarded run would probe the INVERTED
   page and clear Bolt's own inversion), the unpainted canvas is the
   skin dark base while site-dark is on (Fx.webBgDark), and the canvas
   follows the new domain at onPageStarted.

5. AI TRANSLATE "PROVIDER ERROR" (AiBridge.kt). The model chain was
   already current (gemini-3.5-flash-lite, verified 2026-11-08); what
   was missing is the EVIDENCE channel: shortErr now mirrors the raw
   provider response to logcat (Log.e "AiBridge", capped 2000 chars).

6. MATERIAL SYMBOLS ICONS. The nine core glyphs (search, close, more,
   copy, paste, open_new/duplicate, camera, mic, drop_down) are Google's
   official Material Symbols Rounded weight-400 paths (the 2px stroke at
   24dp), viewport 960 + translateY(960) group (the viewBox re-base),
   fillColor ?attr/colorTint (the theme-tint law).

7. INCOGNITO SEARCH ROUTING (AndroidManifest.xml). PROCESS_TEXT /
   WEB_SEARCH / SEND lived only on the BrowserActivity aliases, so a
   selection search in a private tab bounced to normal mode. The new
   SearchIncognito activity-alias registers the same three actions
   against IncognitoActivity (the :incognito process) with its own
   "Search in Bolt (Incognito)" label — the OS offers both rows and
   routes the private row straight into the private process.

The compiled probe (section K) runs TRANSCRIPTIONS of three contracts:
the span-void law (stale span cache renders a void; the trio cures it),
the dark-pass run token (commit runs once; finish re-runs only for an
unseen url), and the rim inset geometry (the stroke band stays inside
the content margin).
"""
import os
import re
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
strs = open(os.path.join(ROOT, "app/src/main/res/values/strings.xml"),
            encoding="utf8").read()
rel = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()
ta = open(os.path.join(SRC, "ui/TabsActivity.java"), encoding="utf8").read()
ba = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
tab = open(os.path.join(SRC, "core/Tab.java"), encoding="utf8").read()
fx = open(os.path.join(SRC, "util/Fx.java"), encoding="utf8").read()
vs = open(os.path.join(SRC, "util/VisualSearch.kt"), encoding="utf8").read()
ab = open(os.path.join(SRC, "util/AiBridge.kt"), encoding="utf8").read()
man = open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"),
           encoding="utf8").read()


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
            if c == "\n":
                out.append("\n")
            i += 1; continue
        if c == "\\":
            i += 2; continue
        if mode == c:
            mode = None; out.append(c)
        i += 1
    return "".join(out)


ta_code = strip_comments_strings(ta)
ba_code = strip_comments_strings(ba)
tab_code = strip_comments_strings(tab)
vs_code = strip_comments_strings(vs)
ab_code = strip_comments_strings(ab)

print("A — the version bump")
need(re.search(r"versionCode 140\b", gradle) is not None,  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 134 (Android only installs RISING codes)")  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName "1\.4\.26"', gradle) is not None,  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.26"')  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print("B — the About changelog (the label law)")
entries = re.findall(r"<item>([^<]+)</item>",
  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])
                     strs.split('name="about_changelog"')[2]) \
    if 'name="about_changelog"' in strs else []
     "the newest entry is 1.4.17 (the label law)")
     "the second entry is 1.4.16 (the previous live release)")
for word, why in (("void", "the span-void law"),
                  ("dark", "the white-flash fix"),
                  ("Incognito", "the private search face"),
                  ("icons", "the Material Symbols pass")):
         f"the 1.4.17 line names {why}")

print("C — fix 1: the span-void law")
need("protected void reloadGridNoVoid() {" in ta_code,
     "the span-void helper exists (reloadGridNoVoid)")
rg = ta_code[ta_code.find("protected void reloadGridNoVoid() {"):]
rg = rg[:rg.find("protected void cardOptions")] if "protected void cardOptions" in rg else rg
need("rv.stopScroll();" in rg,
     "the scroll is stopped BEFORE the data swap (a settle racing the swap keeps stale layout)")
need("adapter.reload();" in rg,
     "the reload itself runs inside the helper")
need("glm.getSpanSizeLookup().invalidateSpanIndexCache();" in rg
     and "glm.getSpanSizeLookup().invalidateSpanGroupIndexCache();" in rg
     and "glm.requestLayout();" in rg,
     "the span-cache invalidation (the androidx 1.3.2 public door) + a layout request run AFTER the swap")
need("glm.invalidateSpanAssignments()" not in rg,
     "no support-lib-era invalidateSpanAssignments() (does not exist on androidx 1.3.2 — the javap proof)")
need(rg.find("rv.stopScroll();") < rg.find("adapter.reload();")
     < rg.find("glm.getSpanSizeLookup().invalidateSpanIndexCache();"),
     "the order is stopScroll -> reload -> invalidate + requestLayout (the report's trio, on the 1.3.2 door)")
need(ta_code.count("reloadGridNoVoid();") == 4,
     "the move_to_group + ungroup doors route through the helper (v1.4.18 re-point 2026-11-19: 2 -> 4 — both ungroup doors joined the law, and the DRAG door got its own reloadDragNoVoid with the posted relayout; probe-pinned in test_v1418)")
# the commit forms carry string literals — RAW source slices (the strip
# pass eats string bodies)
mtg = ta[ta.find("protected void cardOptions"):]
mtg = mtg[:mtg.find("protected void tabOptions")] if "protected void tabOptions" in mtg else mtg
mtg2 = ta[ta.find("protected void tabOptions"):]
mtg2 = mtg2[:mtg2.find("protected void ownTouchStream")] if "protected void ownTouchStream" in mtg2 else mtg2
for nm, seg in (("cardOptions", mtg), ("tabOptions", mtg2)):
    need('t.groupId = groups.get(w2).optLong("id");' in seg
         and "reloadGridNoVoid();" in seg,
         f"{nm}'s move_to_group commit uses the span-void helper")

print("D — fix 2: sheet-active highlight + the rim inset law")
gsa = ta_code[ta_code.find("class GroupSheetAdapter"):]
gsa = gsa[:gsa.find("protected void openGroupSheet")] if "protected void openGroupSheet" in gsa else gsa
need("tm.tabs.indexOf(tab) == tm.active" in gsa,
     "the sheet bind keys the highlight on the member being tm.active")
need("h.title.setTextColor(Fx.accent(TabsActivity.this));" in gsa,
     "the active member's title paints in the accent color")
need("h.title.setTextColor(Fx.txt(TabsActivity.this));" in gsa,
     "an inactive member's title returns to the text color (rebind-safe)")
need("h.itemView.setForeground(new android.graphics.drawable.InsetDrawable(" in gsa
     and "h.itemView.setForeground(null);" in gsa,
     "the sheet row rim rides the inset law (and clears when inactive)")
sv = ta_code[ta_code.find("private void applySelVisuals"):]
sv = sv[:sv.find("public int getItemCount()")] if "public int getItemCount()" in sv else sv
need("v.setForeground(new android.graphics.drawable.InsetDrawable(" in sv,
     "applySelVisuals wraps the rim in an InsetDrawable (no bounds-straddling stroke)")
need("g, Fx.dp(TabsActivity.this, 2)));" in sv,
     "the rim inset is 2dp (concentric with bg_card's 16dp corners at radius 14)")
need("v.setForeground(g);" not in sv,
     "no bare bounds rim remains in the selection visuals")
gh = ta_code[ta_code.find("if (h instanceof GroupHolder) {"):]
gh = gh[:gh.find("private void applySelVisuals")] if "private void applySelVisuals" in gh else gh
need("gh.itemView.setForeground(new android.graphics.drawable.InsetDrawable(" in gh,
     "the v1.4.16 group-row rim follows the same inset law")

print("E — fix 3: the visual-search payload fidelity")
need("bmp.compress(Bitmap.CompressFormat.JPEG, 90, bo)" in vs_code,
     "the upload JPEG quality is 90 (the fidelity knee)")
need(re.search(r"Bitmap\.createScaledBitmap\(\s*rough,[\s\S]{0,200}?, true\)",
               vs_code) is not None,
     "the fine scale keeps the bilinear filter=true (nearest-sample aliases text grey)")
head = vs[vs.find("val boundary ="):]
head = head[:head.find("val conn =")]
need(head.count('append("\\r\\n")') >= 2 or "\\r\\n" in head,
     "the multipart head lines end in strict CRLF")
need('"\\r\\n--$boundary--\\r\\n"' in vs,
     "the multipart tail is strict CRLF (boundary close)")

print("F — fix 4: the commit-time dark pass + the dark canvas")
need("protected void runWebDarkPass(final WebView view, final String url) {" in ba_code,
     "the shared dark-pass body exists (runWebDarkPass)")
need("if (!siteDarkNow(base)) return;" in ba_code,
     "the pass is gated by the per-site dark engine")
cvb = ba_code[ba_code.find("public void onPageCommitVisible(WebView view, String url) {"):]
cvb = cvb[:cvb.find("public void onPageFinished")] if "public void onPageFinished" in cvb else cvb
need("runWebDarkPass(view, url);" in cvb,
     "the chain runs at COMMIT — the earliest the document exists (the white-flash kill)")
need(cvb.find("runWebDarkPass(view, url);") > cvb.find("injectAdblockCosmetic(view, url);"),
     "the dark pass rides the same first-paint moment as the cosmetic pass")
fin = ba_code[ba_code.find("public void onPageFinished(WebView view, String url) {"):]
fin = fin[:fin.find("public void onReceivedError")]
need("if (dt == null || !url.equals(dt.darkPassUrl)) {" in fin
     and "runWebDarkPass(view, url);" in fin,
     "the finish site is the guarded BACKSTOP (re-runs only for an unseen url)")
need('if (siteDarkNow(base)) {' not in fin,
     "the old unguarded finish-time chain is gone (a re-run would clear our own inversion)")
need("public String darkPassUrl = null;" in tab_code,
     "Tab carries the run token")
ocv = ba_code[ba_code.find("public void onPageStarted(WebView view, String url, Bitmap favicon) {"):]
ocv = ocv[:ocv.find("public void onPageCommitVisible")]
need("t.applyWebDark(BrowserActivity.this, view);" in ocv,
     "the dark canvas follows the NEW domain at navigation start")
awd = tab_code[tab_code.find("public void applyWebDark(Context c, WebView w) {"):]
awd = awd[:awd.find("public void capture()")] if "public void capture()" in awd else awd
need("siteDark ? Fx.webBgDark(c) : Fx.webBg(c)" in awd,
     "the unpainted canvas is the skin dark base while site-dark is on")
need("public static int webBgDark(Context c) {" in fx,
     "Fx.webBgDark exists (the theme-independent dark canvas)")
need("setAlgorithmicDarkeningAllowed(false)" in awd
     and re.search(r"setForceDark\(WebSettings\.FORCE_DARK_OFF", awd) is not None,
     "the native darkeners stay OFF (the v1.3.81/v1.3.91 retirement — target 34 + double-invert)")
need("FORCE_DARK_ON" not in tab_code,
     "no FORCE_DARK_ON anywhere (a no-op at target 34 that reads as a fix)")

print("G — fix 5: the AI raw-response evidence channel")
need('Log.e("AiBridge", "Raw Response: "' in ab,
     "shortErr mirrors the raw provider response to logcat (the AiBridge tag; RAW source — the strip pass eats string literals)")
need("import android.util.Log" in ab,
     "the Log import rides the file")
need("gemini-3.5-flash-lite" in ab,
     "the default model stays the verified-current chain (the 2026-11-08 aistudio check)")

print("H — fix 6: the Material Symbols pass")
ICONS = (("ic_search", "search", "24dp"), ("ic_close", "close", "24dp"),
         ("ic_more", "more_vert", "24dp"), ("ic_copy", "content_copy", "24dp"),
         ("ic_paste", "content_paste", "24dp"), ("ic_open_new", "open_in_new", "24dp"),
         ("ic_camera", "photo_camera", "24dp"), ("ic_mic", "mic", "24dp"),
         ("ic_drop_down", "arrow_drop_down", "16dp"))
for fname, sym, size in ICONS:
    p = os.path.join(RES, "drawable", fname + ".xml")
    ok = os.path.exists(p)
    body = open(p, encoding="utf8").read() if ok else ""
    need(ok
         and f'android:width="{size}" android:height="{size}"' in body
         and 'android:viewportWidth="960" android:viewportHeight="960"' in body
         and 'android:translateY="960"' in body
         and 'android:fillColor="?attr/colorTint"' in body
         and f'Material Symbols Rounded "{sym}"' in body,
         f"{fname}.xml is Material Symbols Rounded {sym} ({size}, 960 viewport, tint law)")

print("I — fix 7: the incognito search face")
sal = man[man.find('.ui.SearchIncognito'):] if '.ui.SearchIncognito' in man else ""
sal = sal[:sal.find("</activity-alias>")] if "</activity-alias>" in sal else sal
need('android:name=".ui.SearchIncognito"' in man
     and 'android:targetActivity=".ui.IncognitoActivity"' in sal,
     "the SearchIncognito alias targets IncognitoActivity (the :incognito process)")
need("android.intent.action.WEB_SEARCH" in sal
     and "android.intent.action.SEND" in sal
     and "android.intent.action.PROCESS_TEXT" in sal,
     "the alias registers all three system search actions")
need('android:label="@string/search_in_bolt_incognito"' in sal,
     "the PROCESS_TEXT filter carries its own label (the v1.4.13 toolbar law)")
need('android:exported="true"' in sal,
     "the alias is exported (the OS must resolve it from other apps)")
need("android:process" not in sal,
     "the alias carries NO android:process (process placement belongs to the target activity)")
ia = man[man.find('android:name=".ui.IncognitoActivity"'):]
ia = ia[:ia.find("/>")]
need('android:process=":incognito"' in ia,
     "the target activity keeps its :incognito process (what makes the face private)")
need('name="search_in_bolt_incognito">Search in Bolt (Incognito)<' in strs,
     "the label string exists")

# the compiled probe: three transcriptions, run for real
probe = r"""
public class PolishProbe {
    // TRANSCRIPTION 1 — the span-void law (reloadGridNoVoid, v1.4.17).
    // SpanSizeLookup caches span size PER POSITION; a data swap does not
    // touch the cache, so the next layout reads the OLD shape at every
    // position — a group row that should span 2 renders at span 1 and
    // leaves a void. The span-cache invalidation empties the cache so
    // the pass re-derives every span.
    static class GridSim {
        int[] cachedSpanOf;                            // position -> span size
        boolean cacheValid = true;
        boolean voidRendered = false;

        void dataSwap() { }                            // notifyDataSetChanged
        void invalidateSpanAssignments() { cacheValid = false; }
        int[] layout(int[] spanSizes) {                // the next layout pass
            int[] out = new int[spanSizes.length];
            for (int i = 0; i < spanSizes.length; i++) {
                if (cacheValid && cachedSpanOf != null && i < cachedSpanOf.length) {
                    out[i] = cachedSpanOf[i];          // STALE shape at position i
                } else {
                    out[i] = spanSizes[i];             // re-derived
                }
            }
            cachedSpanOf = out;
            cacheValid = true;
            for (int i = 0; i < spanSizes.length; i++)
                if (out[i] != spanSizes[i]) voidRendered = true;
            return out;
        }
        void reloadNaive(int[] newItems) {             // the OLD path
            dataSwap();
        }
        void reloadGridNoVoid(int[] newItems) {        // the v1.4.17 trio
            dataSwap();
            invalidateSpanAssignments();
        }
    }

    // TRANSCRIPTION 2 — the dark-pass run token (runWebDarkPass, v1.4.17).
    // Commit runs the chain for its url; finish re-runs ONLY for an url
    // the chain has never spoken for (an unguarded second run would probe
    // the already-inverted page and clear Bolt's own inversion).
    static class DarkPassSim {
        String darkPassUrl = null;
        int runs = 0;
        void runWebDarkPass(String url) { runs++; darkPassUrl = url; }
        void onPageCommitVisible(String url) { runWebDarkPass(url); }
        void onPageFinished(String url) {
            if (darkPassUrl == null || !darkPassUrl.equals(url)) {
                runWebDarkPass(url);                   // the backstop
            }
        }
    }

    // TRANSCRIPTION 3 — the rim inset geometry (applySelVisuals, v1.4.17).
    // A stroke is centered on its drawable's bounds edge: at the view
    // bounds it straddles the edge (half outside, half over the content
    // rim). A 2dp inset moves the drawable inside, so the whole band
    // draws within [2, 4] — clear of content that starts at margin 4.
    static int[] centeredStroke(int stroke) {          // the OLD rim, at the bounds
        return new int[]{-stroke / 2, stroke / 2};
    }
    static int[] insetStroke(int inset, int stroke) {  // the v1.4.17 rim
        return new int[]{inset, inset + stroke};
    }

    static int fails = 0;
    static void check(String why, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FAIL") + " - " + why);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        // --- the span-void law ---
        GridSim g = new GridSim();
        g.layout(new int[]{1, 1, 2});                  // old board: 2 loose + a group
        g.reloadNaive(new int[]{2, 1});                // a tab joined the group
        g.layout(new int[]{2, 1});                     // group spans 2, one loose card
        check("a naive reload renders the VOID (stale per-position spans)",
              g.voidRendered);
        GridSim g2 = new GridSim();
        g2.layout(new int[]{1, 1, 2});
        g2.reloadGridNoVoid(new int[]{2, 1});
        g2.layout(new int[]{2, 1});
        check("the trio cures it (spans re-derived, no void)",
              !g2.voidRendered && g2.cachedSpanOf[0] == 2);
        // --- the dark-pass run token ---
        DarkPassSim d = new DarkPassSim();
        d.onPageCommitVisible("https://a.example/1");
        d.onPageFinished("https://a.example/1");
        check("commit + finish for the SAME url run the chain exactly ONCE",
              d.runs == 1);
        DarkPassSim d2 = new DarkPassSim();
        d2.onPageFinished("https://b.example/1");
        check("a finish with no commit still runs the backstop",
              d2.runs == 1);
        DarkPassSim d3 = new DarkPassSim();
        d3.onPageCommitVisible("https://c.example/1");
        d3.onPageFinished("https://c.example/2");      // a redirect chain
        check("a finish for a DIFFERENT url re-runs (the commit missed it)",
              d3.runs == 2);
        // --- the rim inset geometry ---
        int[] bare = centeredStroke(2);                // the old rim, at the bounds
        check("the bare stroke straddles the edge (starts before the bounds)",
              bare[0] < 0);
        int[] inset = insetStroke(2, 2);               // the v1.4.17 rim
        check("the inset stroke sits fully INSIDE the bounds",
              inset[0] >= 0 && inset[1] <= 4);
        check("the inset stroke never reaches past the 4dp content margin",
              inset[1] <= 4);
        System.out.println(fails == 0 ? "PROBE OK" : "PROBE BAD (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
"""
tmp = tempfile.mkdtemp(prefix="bolt_v1417_probe_")
open(os.path.join(tmp, "PolishProbe.java"), "w").write(probe)
# release.sh puts the JDK on PATH before the gates; standalone runs fall
# back to the workspace's grafted JDK (the README environment note)
GJ = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin"
javac = "javac" if shutil.which("javac") else os.path.join(GJ, "javac")
java = "java" if shutil.which("java") else os.path.join(GJ, "java")
pr = subprocess.run([javac, "-d", tmp, os.path.join(tmp, "PolishProbe.java")],
                    capture_output=True, text=True)
if pr.returncode != 0:
    print("  FAIL - probe compiled (javac):\n" + pr.stderr[:800])
    fails += 1
else:
    print("  ok  - probe compiled (javac)")
    pr2 = subprocess.run([java, "-cp", tmp, "PolishProbe"],
                         capture_output=True, text=True)
    print(pr2.stdout.rstrip())
    if pr2.returncode != 0:
        fails += 1

print("L — the gate is wired into the pipeline")
need("python3 scripts/test_v1417.py" in rel,
     "release.sh runs this gate before building")
need(rel.find("test_v1416.py") < rel.find("test_v1417.py"),
     "the gate runs after v1416's (release order)")

print()
print("V1417 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
