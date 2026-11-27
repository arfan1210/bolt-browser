#!/usr/bin/env python3
"""test_v1434.py — the v1.4.34 gate: THE SCANNER FIELD-OF-VIEW ROUND.

Six scanner fixes, no feature work, all probe-pinned:

A  version pins (148 / 1.4.34, the About changelog head, the CHANGELOG
   section)
B  QR — THE 4:3 STREAM LAW: pickStream serves BOTH streams, the
   compiled REAL isFourThree decision table, the transcribed pickStream
   probe (4:3 wins / the largest-<=cap fallback / the smallest
   fallback), the active-array stride fallback's 4:3 leg (16:9 first)
C  QR — THE LETTERBOX LAW: the min() scale, the fits assertion, the
   MISMATCH mark riding fits, the letterbox hull fixtures (equal bars,
   the aspect-match degenerate), the undo chain untouched
D  QR — THE DUAL-PRIOR ACCEPTANCE: the compiled REAL measuredVerdict
   decision table (the corpse configuration ACCEPTS via the cover
   prior; the contaminated fixtures still reject), priorDistance /
   nearerPrior probes, the verdict=/near= diag pins
E  QR — THE CALIBRATION CONVERGENCE ROUND: the compiled REAL
   stripeWidthFor (span/128 -> span/48), calibRetryEligible, the
   compiled REAL rotated-raster swap fixture (the standard scan null,
   the swapped scan solves), the retry/swap wiring pins, the one-shot
   budget pins
F  QR — THE QUARTER-SCALE CLOSING LEG: the compiled REAL dense-dot
   fixture — the pre-1.4.34 ladder (plain/invert, stretch, half,
   close-full, chroma) MISSES and the quarter leg DECODES — plus the
   v1.4.33 dot-corpse regression pin and the ladder-order pins
G  the pipeline (release.sh unchanged) + the README keeps the laws and
   the open read-backs + the dated re-points exist in the prior gates

House rules: every need() failure counts; any failure exits 1.
"""
import os
import shutil
import subprocess
import tempfile
import glob
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

fails = []


def need(cond, what):
    print(("  ok  " if cond else "  FAIL ") + what)
    if not cond:
        fails.append(what)


def read(p):
    with open(p, encoding="utf8") as f:
        return f.read()


def extract_method(src, sig):
    """the method body from its signature line to the matching closing
    brace (the house extractor — brace counting from the sig line)."""
    i = src.find(sig)
    if i < 0:
        return None
    j = src.find("{", i)
    depth = 0
    for k in range(j, len(src)):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[i:k + 1]
    return None


qsa = read("app/src/main/java/com/bolt/browser/ui/QrScanActivity.java")
qc = read("app/src/main/java/com/bolt/browser/util/QrCore.java")
gradle = read("app/build.gradle")
strings = read("app/src/main/res/values/strings.xml")
chlog = read("CHANGELOG.md")
rels = read("scripts/release.sh")
readme = read("README.md")
javac = shutil.which("javac")

print("A — version pins")
need("versionCode 148" in gradle and 'versionName "1.4.34"' in gradle,
     "build.gradle pins 148 / 1.4.34")
need('<item>1.4.34|' in strings,
     "the About changelog carries the 1.4.34 line at [0] (newest first)")
i34 = strings.find("<item>1.4.34|")
i33 = strings.find("<item>1.4.33|")
need(0 <= i34 < i33, "the About changelog order: 1.4.34 rides ABOVE 1.4.33")
need("## [1.4.34] — 2026-11-27" in chlog and
     chlog.find("## [1.4.34]") < chlog.find("## [1.4.33]"),
     "the CHANGELOG carries the 1.4.34 section above 1.4.33")

print()
print("B — QR: THE 4:3 STREAM LAW (more scene per decode)")
need("previewSize = pickStream(Arrays.asList(map.getOutputSizes(SurfaceTexture.class)), 1920);"
     in qsa and
     "Size yuv = pickStream(Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)), 1920);"
     in qsa,
     "BOTH streams ride pickStream (the preview buffer and the YUV "
     "analysis plane — the letterbox law then frames them identically)")
need("static boolean isFourThree(int w, int h)" in qsa,
     "isFourThree: the 0.02-tight landscape 4:3 test is in the activity")
m_43 = extract_method(qsa, "    static boolean isFourThree(int w, int h) {")
m_ps = extract_method(qsa, "    private Size pickStream(List<Size> sizes, int cap) {")
m_fb = extract_method(qsa, "    private Size pickLargest(List<Size> sizes, int cap) {")
need(m_43 and m_ps and m_fb, "the stream methods are extractable")
need(m_ps and "if (best43 != null) return best43;" in m_ps
     and "if (best != null) return best;" in m_ps,
     "pickStream's order: the 4:3 candidates first, the largest-<=cap "
     "fallback, the smallest last (a 4K-only exotic beats a crash)")
need(m_fb and "RETIRED from the live path by the 4:3 stream law" in qsa,
     "pickLargest is retired from the live path (kept for reference/tests)")
# the stride-alignment fallback: the 16:9 leg first, the 4:3 leg second
need("int nine = Math.round(previewSize.getWidth() * 9f / 16f);" in qsa
     and "int four = Math.round(previewSize.getWidth() * 3f / 4f);" in qsa
     and qsa.find("int nine = Math.round") < qsa.find("int four = Math.round"),
     "the stride fallback grew the 4:3 leg (the 16:9 leg rides first)")
need("activeH = four;" in qsa and "four >= 64" in qsa,
     "the 4:3 fallback lands the expected scene height (1440x1088 -> 1080)")

print("  -- the compiled REAL isFourThree probe --")
if m_43 and javac:
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        harness = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    public static void main(String[] args) {
        // the sensor-native photo aspect family
        eq("1600x1200 is 4:3", Probe.isFourThree(1600, 1200));
        eq("1440x1080 is 4:3", Probe.isFourThree(1440, 1080));
        eq("1280x960 is 4:3", Probe.isFourThree(1280, 960));
        eq("640x480 is 4:3", Probe.isFourThree(640, 480));
        // portrait orientation-agnostic (the test is larger-over-smaller)
        eq("1200x1600 is 4:3 too", Probe.isFourThree(1200, 1600));
        // the 16:9 family misses
        eq("1920x1080 is NOT 4:3", !Probe.isFourThree(1920, 1080));
        eq("1280x720 is NOT 4:3", !Probe.isFourThree(1280, 720));
        // the 0.02 window: 1.3333 ± 0.02 = [1.3133, 1.3533] — 1440/1065
        // (1.3521) rides, 1440/1060 (1.3585) misses
        eq("the 0.02 window inner edge", Probe.isFourThree(1440, 1065));
        eq("the 0.02 window outer edge", !Probe.isFourThree(1440, 1060));
        // degenerate guards
        eq("zero dims guarded", !Probe.isFourThree(0, 0)
                && !Probe.isFourThree(100, 0) && !Probe.isFourThree(0, 100));
        if (Probe.fails == 0) System.out.println("PROBE OK");
        else System.out.println("PROBE FAILS=" + Probe.fails);
        System.exit(Probe.fails == 0 ? 0 : 1);
    }
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Probe {\n" +
                    m_43.replace("    static", "    static", 1) + "\n" +
                    harness + "}\n")
        r = subprocess.run([javac, src], capture_output=True, text=True)
        need(r.returncode == 0, "isFourThree compiles standalone: " +
             ("" if r.returncode == 0 else r.stderr[:300]))
        if r.returncode == 0:
            r = subprocess.run(["java", "-cp", d, "Probe"],
                               capture_output=True, text=True)
            need(r.returncode == 0 and "PROBE OK" in r.stdout,
                 "the compiled REAL isFourThree decision table: " +
                 (r.stdout.strip().splitlines()[-1] if r.stdout else r.stderr[:150]))

print("  -- the transcribed pickStream probe (Size is an android stub; the "
      "law rides the transcription) --")


def is43(w, h):
    if w <= 0 or h <= 0:
        return False
    lw, lh = max(w, h), min(w, h)
    return abs(lw / lh - 4.0 / 3.0) <= 0.02


def pick_stream(sizes, cap):
    best43 = best = None
    for (w, h) in sizes:
        le = max(w, h)
        if le > cap:
            continue
        if best is None or le > max(best[0], best[1]):
            best = (w, h)
        if is43(w, h) and (best43 is None or le > max(best43[0], best43[1])):
            best43 = (w, h)
    if best43 is not None:
        return best43
    if best is not None:
        return best
    return min(sizes, key=lambda s: max(s[0], s[1]))


# the field-shaped stream table (16:9 + 4:3 families, one 4K exotic)
STREAMS = [(1920, 1080), (1600, 1200), (1440, 1080), (1280, 720),
           (1280, 960), (960, 720), (640, 480), (3840, 2160)]
need(pick_stream(STREAMS, 1920) == (1600, 1200),
     "the 4:3 law: 1600x1200 wins over 1920x1080 (the largest 4:3 under "
     "the cap; ~33% more vertical scene)")
need(pick_stream([(1920, 1080), (1280, 720)], 1920) == (1920, 1080),
     "no 4:3 candidate -> the old largest-<=cap pick (exotic sensors)")
need(pick_stream([(3840, 2160), (4096, 3072)], 1920) == (3840, 2160),
     "nothing under the cap -> the smallest overall (a 4K-only exotic "
     "beats a crash)")
need(pick_stream(STREAMS, 1280) == (1280, 960),
     "a tighter cap keeps the law (1280x960 over 1280x720)")

print()
print("C — QR: THE LETTERBOX LAW (nothing cropped, centered)")
need("float s = Math.min(viewW / uw, viewH / uh);" in qsa,
     "the recipe's final scale is the LETTERBOX scale (min, fit + centered)")
need("boolean fits = uw * s <= viewW + 1f && uh * s <= viewH + 1f;" in qsa,
     "the fits-within-axes assertion (the v1.4.27 fills inverted)")
need("(fits ? \"\" : \" MISMATCH\")" in qsa,
     "the MISMATCH mark rides the fits assertion")
need(qsa.find("boolean fits =") < qsa.find("if (!fits && !pinRetried)") <
     qsa.find("applyPreviewTransform(viewW, viewH);",
              qsa.find("if (!fits && !pinRetried)")),
     "the one-shot re-pin ladder rides !fits (the bottom-half-black heal)")
# the undo chain is untouched: the three legs + the pad compensation order
need('"measured".equals(undoMode) && calibDone' in qsa
     and '"table".equals(undoMode)' in qsa and "undoSx = bw / viewW;" in qsa
     and "matrix.postScale(padX, padYf);" in qsa,
     "the undo chain rides untouched (measured -> table -> fill + the pad "
     "compensation)")
need(qsa.find("matrix.postScale(undoSx, undoSy);") >= 0
     and qsa.find("matrix.postScale(undoSx, undoSy);") <
     qsa.find("matrix.postTranslate(-aw / 2f, -ah / 2f);") <
     qsa.find("matrix.postRotate(theta);") <
     qsa.find("matrix.postScale(s, s);") <
     qsa.find("matrix.postTranslate(cx, cy);"),
     "the ORDER law: undo -> pad -> center -> rotate -> letterbox -> center")
# the letterbox hull fixtures (the v1426 transcription, letterbox form)
import math


def letterbox_hull(bufw, bufh, vieww, viewh, theta):
    uw, uh = (bufw, bufh) if theta % 180 == 0 else (bufh, bufw)
    s = min(vieww / uw, viewh / uh)

    def m(p):
        x, y = p[0] * bufw / vieww, p[1] * bufh / viewh
        x, y = x - bufw / 2.0, y - bufh / 2.0
        a = math.radians(theta)
        x, y = x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)
        return (x * s + vieww / 2.0, y * s + viewh / 2.0)

    pts = [m((0, 0)), m((vieww, 0)), m((vieww, viewh)), m((0, viewh))]
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return s, min(xs), max(xs), min(ys), max(ys)


s, x0, x1, y0, y1 = letterbox_hull(1920, 1080, 1080, 2340, 90)
need(abs(x0) < 1e-6 and abs(x1 - 1080) < 1e-6
     and abs(y0 - 210) < 1e-6 and abs(y1 - 2130) < 1e-6,
     "portrait 1080x2340 with a 1920x1080 buffer: the width fits EXACTLY, "
     "equal 210px bars top/bottom, nothing cropped")
need(abs(s - 1.0) < 1e-9, "the letterbox scale is aspect-true (1.0)")
s2, x0, x1, y0, y1 = letterbox_hull(1920, 1080, 2340, 1080, 0)
need(abs(x0 - 210) < 1e-6 and abs(x1 - 2130) < 1e-6 and abs(y0) < 1e-6
     and abs(y1 - 1080) < 1e-6,
     "the landscape case: the height fits exactly, the width letterboxed")
s3, x0, x1, y0, y1 = letterbox_hull(1080, 1920, 1080, 1920, 0)
need(abs(x0) < 1e-6 and abs(x1 - 1080) < 1e-6 and abs(y0) < 1e-6
     and abs(y1 - 1920) < 1e-6 and abs(s3 - 1.0) < 1e-9,
     "the aspect-match degenerate: letterbox == the exact cover numbers "
     "(no bars, no crop, scale 1.0)")
s4, x0, x1, y0, y1 = letterbox_hull(1600, 1200, 1080, 2400, 90)
need(abs(x0) < 1e-6 and abs(x1 - 1080) < 1e-6
     and abs(y0 - 480) < 1e-6 and abs(y1 - 1920) < 1e-6,
     "the 4:3 stream on a 20:9 view: 480px bars top/bottom, the width "
     "exact — the whole scene visible")

print()
print("D — QR: THE DUAL-PRIOR ACCEPTANCE (the implicit-map verdict)")
need("static String measuredVerdict(float ix, float iy, float fillX, float fillY,"
     in qsa and "static float priorDistance(float ix, float iy, float px, float py) {"
     in qsa and "static String nearerPrior(float ix, float iy, float fillX, float fillY, float coverK) {"
     in qsa and "static boolean calibRetryEligible(boolean solvable, String verdict) {"
     in qsa and "static int stripeWidthFor(int span, int attempt) {" in qsa,
     "the five v1.4.34 PURE methods are in the activity")
need("calibVerdict = measuredVerdict(sx[0], sy[0]," in qsa
     and "undoMode = chainMode;" in qsa,
     "the acceptance rides the dual-prior verdict; a rejection still falls "
     "down the chain")
m_acc = extract_method(qsa, "    static boolean measuredAcceptable(float ix, float iy, float priorX, float priorY) {")
m_cmp = extract_method(qsa, "    static float compoundAnisotropy(float impX, float impY, float undoX, float undoY) {")
m_ver = extract_method(qsa, "    static String measuredVerdict(float ix, float iy, float fillX, float fillY,\n            float coverX, float coverY) {")
m_pd = extract_method(qsa, "    static float priorDistance(float ix, float iy, float px, float py) {")
m_np = extract_method(qsa, "    static String nearerPrior(float ix, float iy, float fillX, float fillY, float coverK) {")
m_ce = extract_method(qsa, "    static boolean calibRetryEligible(boolean solvable, String verdict) {")
m_sw = extract_method(qsa, "    static int stripeWidthFor(int span, int attempt) {")
need(m_acc and m_cmp and m_ver and m_pd and m_np and m_ce and m_sw,
     "the v1.4.34 methods are extractable")
print("  -- the compiled REAL verdict probe --")
if javac and all([m_acc, m_cmp, m_ver, m_pd, m_np, m_ce, m_sw]):
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        harness = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    public static void main(String[] args) {
        // THE CORPSE CONFIGURATION (the honest cover-implicit solve on a
        // device the table does not name): view 1080x2400, active
        // 1920x1080. fill prior (0.5625, 2.2222); cover prior k = 2.2222.
        // The honest solve is ix = iy = k (uniform cover).
        float fillX = 1080f / 1920f, fillY = 2400f / 1080f;
        float k = Math.max(fillX, fillY);
        String v = Probe.measuredVerdict(k, k, fillX, fillY, k, k);
        eq("the corpse configuration ACCEPTS (via the cover prior)",
           v.equals("cover"));
        // the v1.4.32 behaviour (for the record): against the fill prior
        // ALONE the honest solve read off-window — the corpse. The old
        // gate rejected what the device actually does.
        eq("the corpse read off-window against the fill prior (the " +
           "v1.4.32 corpse, for the record)",
           !Probe.measuredAcceptable(k, k, fillX, fillY));
        // an honest FILL-implicit solve accepts via the fill prior
        String vf = Probe.measuredVerdict(fillX, fillY, fillX, fillY, k, k);
        eq("an honest fill solve accepts (verdict=fill)", vf.equals("fill"));
        // a measured solve that agrees with NEITHER model is contamination
        String vc = Probe.measuredVerdict(0.31f, 4.7f, fillX, fillY, k, k);
        eq("a contaminated capture still rejects", vc.equals("reject"));
        // the boundary windows ride measuredAcceptable unchanged
        eq("boundary 1.01 accepted", Probe.measuredAcceptable(k * 1.01f, k, k, k));
        eq("boundary 1.03 rejected", !Probe.measuredAcceptable(k * 1.03f, k, k, k));
        // the degenerate guards
        eq("degenerate guarded", Probe.measuredVerdict(0f, k, fillX, fillY, k, k)
                .equals("reject"));
        // priorDistance: 1.000 = agrees; the corpse solve sits at ~1.0
        // against the cover prior and ~3.95 against the fill prior
        float dc = Probe.priorDistance(k, k, k, k);
        float df = Probe.priorDistance(k, k, fillX, fillY);
        eq("priorDistance agrees at 1.000", Math.abs(dc - 1f) < 1e-4);
        eq("priorDistance names the distance (the corpse vs fill ~3.95)",
           df > 3.5f);
        // nearerPrior: the corpse-shaped solve names cover; a fill-shaped
        // solve names fill
        eq("nearerPrior cover", Probe.nearerPrior(k, k, fillX, fillY, k).equals("cover"));
        eq("nearerPrior fill", Probe.nearerPrior(fillX, fillY, fillX, fillY, k).equals("fill"));
        // calibRetryEligible: convergence-shaped failures retry; a missing
        // bitmap does not
        eq("no solve retries", Probe.calibRetryEligible(false, null));
        eq("a rejected solve retries", Probe.calibRetryEligible(true, "reject"));
        eq("an accepted solve does not retry", !Probe.calibRetryEligible(true, "fill"));
        if (Probe.fails == 0) System.out.println("PROBE OK");
        else System.out.println("PROBE FAILS=" + Probe.fails);
        System.exit(Probe.fails == 0 ? 0 : 1);
    }
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Probe {\n" + m_cmp + "\n" + m_acc + "\n" +
                    m_ver + "\n" + m_pd + "\n" + m_np + "\n" + m_ce + "\n" +
                    harness + "}\n")
        r = subprocess.run([javac, src], capture_output=True, text=True)
        need(r.returncode == 0, "the verdict probe compiles standalone: " +
             ("" if r.returncode == 0 else r.stderr[:300]))
        if r.returncode == 0:
            r = subprocess.run(["java", "-cp", d, "Probe"],
                               capture_output=True, text=True)
            need(r.returncode == 0 and "PROBE OK" in r.stdout,
                 "the compiled REAL measuredVerdict decision table: " +
                 (r.stdout.strip().splitlines()[-1] if r.stdout else r.stderr[:150]))

print()
print("E — QR: THE CALIBRATION CONVERGENCE ROUND (retry + swap)")
need("private int calibAttempts = 0;" in qsa
     and "calibAttempts = 0;" in qsa.split("private void openCamera()")[1][:600],
     "the retry budget resets per open (a fresh measurement session)")
need("if (!ok && calibAttempts < 1 && calibRetryEligible(solvable, calibVerdict))"
     in qsa and "calibAttempts++;" in qsa and "armCalibration();" in qsa,
     "the convergence retry: ONE bounded re-arm on the failure path")
need('"qr calib retry attempt=' in qsa and
     '" width=" + stripeWidthFor(bufW, calibAttempts + 1)' in qsa,
     "the retry names itself (attempt= width=)")
need('" attempt=" + (calibAttempts + 1)' in qsa,
     "the arm diag grew attempt= (and the width=)")
need('"qr calib swap red=cols green=rows"' in qsa
     and "solveAxis(rx2, bufW, vh)" in qsa and "solveAxis(gy2, bufH, vw)" in qsa,
     "the swap leg: the rotated-raster evidence (a swapped solve is never "
     "applied — no undoMode write in the swap block)")
swapblk = qsa[qsa.find("if (rx == null || gy == null) {"):
              qsa.find("sx = rx != null ? solveAxis(rx, bufW, vw) : null;")]
need(swapblk and "undoMode" not in swapblk and "calibIx" not in swapblk
     and "calibDone" not in swapblk,
     "the swap block writes NOTHING (evidence only — the per-axis undo "
     "cannot express a cross-axis map)")
need('verdict=" + calibVerdict' in qsa and '" near=" + (solvable' in qsa
     and '"none"' in qsa,
     "the calib diag grew verdict= (ok) and near= (fail, none on no solve)")
# the stripe width probe
print("  -- the compiled REAL stripeWidthFor probe --")
if m_sw and javac:
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        harness = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    public static void main(String[] args) {
        eq("attempt 1 rides the v1.4.31 geometry", Probe.stripeWidthFor(1920, 1) == 15);
        eq("attempt 2 is the 2.7x wider pattern", Probe.stripeWidthFor(1920, 2) == 40);
        eq("attempt 3+ stays wide (no growth)", Probe.stripeWidthFor(1920, 7) == 40);
        eq("the small-span floor", Probe.stripeWidthFor(160, 1) == 2
                && Probe.stripeWidthFor(64, 2) == 2);
        if (Probe.fails == 0) System.out.println("PROBE OK");
        else System.out.println("PROBE FAILS=" + Probe.fails);
        System.exit(Probe.fails == 0 ? 0 : 1);
    }
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Probe {\n" + m_sw + "\n" + harness + "}\n")
        r = subprocess.run([javac, src], capture_output=True, text=True)
        need(r.returncode == 0, "stripeWidthFor compiles standalone: " +
             ("" if r.returncode == 0 else r.stderr[:300]))
        if r.returncode == 0:
            r = subprocess.run(["java", "-cp", d, "Probe"],
                               capture_output=True, text=True)
            need(r.returncode == 0 and "PROBE OK" in r.stdout,
                 "the compiled REAL stripeWidthFor table: " +
                 (r.stdout.strip().splitlines()[-1] if r.stdout else r.stderr[:150]))

# the rotated-raster swap fixture: the standard scan null, the swapped
# scan solves — the "strip 校准不收敛" corpse class
print("  -- the compiled REAL rotated-raster swap fixture --")
m_scan = extract_method(qsa, "    static int[] scanRunsArgb(int[] argb, int w, int h, boolean alongX, int test) {")
m_rp = extract_method(qsa, "    private static int[] rowPixels(int[] px, int w, int h, int y) {")
m_cp = extract_method(qsa, "    private static int[] colPixels(int[] px, int w, int h, int x) {")
m_frr = extract_method(qsa, "    private static int[] findRunsRows(int[] px, int vw, int vh, int test) {")
m_frc = extract_method(qsa, "    private static int[] findRunsCols(int[] px, int vw, int vh, int test) {")
m_solve = extract_method(qsa, "    static float[] solveAxis(int[] runs, int span, int viewLen) {")
m_cnt = extract_method(qsa, "    static int stripeCount() { return 7; }")
m_pos = extract_method(qsa, "    static int stripePos(int span, int j) {")
if javac and all([m_scan, m_rp, m_cp, m_frr, m_frc, m_solve, m_cnt, m_pos]):
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        harness = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    // the ROTATED raster: the implicit map turned the pattern 90 degrees
    // (a producer-rotation HAL). The red verticals (buffer-x stripes) now
    // run HORIZONTAL in the view at view-y = ix*bufW*j/8 + tx; the green
    // horizontals run VERTICAL at view-x = iy*bufH*j/8 + ty.
    static int[] rasterRot(int vw, int vh, double ix, double tx, double iy,
            double ty, int bw, int bh) {
        int[] px = new int[vw * vh];
        for (int j = 1; j <= 7; j++) {
            int y = (int) Math.round(ix * bw * j / 8.0 + tx);
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= vh) continue;
                for (int x = 0; x < vw; x++) px[yy * vw + x] = 0xFFFF2222;
            }
            int x = (int) Math.round(iy * bh * j / 8.0 + ty);
            for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx;
                if (xx < 0 || xx >= vw) continue;
                for (int y2 = 0; y2 < vh; y2++) px[y2 * vw + xx] = 0xFF22DD22;
            }
        }
        return px;
    }
    public static void main(String[] args) {
        int vw = 1080, vh = 2340, bw = 1920, bh = 1080;
        // the ROTATED implicit: buffer-x maps to view-y at ~0.94 scale
        // (the fill-stretch on the rotated axis), buffer-y to view-x
        double ix = 2340.0 / 1920.0 / 2.0 * 2.0;  // 1.219 — a plausible stretch
        double iy = 1080.0 / 1080.0;
        int[] px = rasterRot(vw, vh, ix, 10.0, iy, 20.0, bw, bh);
        // THE STANDARD SCAN (the pre-1.4.34 behaviour): red on rows, green
        // on columns — the rotated pattern reads NOTHING.
        int[] rx = Probe.findRunsRows(px, vw, vh, 1);
        int[] gy = Probe.findRunsCols(px, vw, vh, 2);
        eq("the standard scan is BLIND on a rotated implicit",
           rx == null && gy == null);
        // THE SWAP LEG: red on columns, green on rows — the pattern solves.
        int[] rx2 = Probe.findRunsCols(px, vw, vh, 1);
        int[] gy2 = Probe.findRunsRows(px, vw, vh, 2);
        eq("the swapped scan finds BOTH stripe families",
           rx2 != null && rx2.length >= 2 && gy2 != null && gy2.length >= 2);
        float[] six = rx2 != null ? Probe.solveAxis(rx2, bw, vh) : null;
        float[] siy = gy2 != null ? Probe.solveAxis(gy2, bh, vw) : null;
        eq("the swapped solve converges (the evidence the diag logs)",
           six != null && siy != null);
        eq("the swapped solve reads the TRUE mapping",
           six != null && Math.abs(six[0] - ix) < 0.01
                && siy != null && Math.abs(siy[0] - iy) < 0.01);
        // the UNROTATED control: the standard scan still wins (no
        // regression on the honest fill/cover devices)
        int[] py = rasterStd(vw, vh, 1080.0 / 1920.0, 0.0, 2340.0 / 1080.0, 0.0, bw, bh);
        int[] rx3 = Probe.findRunsRows(py, vw, vh, 1);
        int[] gy3 = Probe.findRunsCols(py, vw, vh, 2);
        eq("the unrotated control: the standard scan still converges",
           rx3 != null && gy3 != null
                && Probe.solveAxis(rx3, bw, vw) != null
                && Probe.solveAxis(gy3, bh, vh) != null);
        if (Probe.fails == 0) System.out.println("PROBE OK");
        else System.out.println("PROBE FAILS=" + Probe.fails);
        System.exit(Probe.fails == 0 ? 0 : 1);
    }

    static int[] rasterStd(int vw, int vh, double ix, double tx, double iy,
            double ty, int bw, int bh) {
        int[] px = new int[vw * vh];
        for (int j = 1; j <= 7; j++) {
            int x = (int) Math.round(ix * bw * j / 8.0 + tx);
            for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx;
                if (xx < 0 || xx >= vw) continue;
                for (int y = 0; y < vh; y++) px[y * vw + xx] = 0xFFFF2222;
            }
            int y = (int) Math.round(iy * bh * j / 8.0 + ty);
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= vh) continue;
                for (int x2 = 0; x2 < vw; x2++) px[yy * vw + x2] = 0xFF22DD22;
            }
        }
        return px;
    }
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Probe {\n" + m_cnt + "\n" + m_pos + "\n" +
                    m_scan + "\n" + m_rp + "\n" + m_cp + "\n" + m_frr + "\n" +
                    m_frc + "\n" + m_solve + "\n" + harness + "}\n")
        r = subprocess.run([javac, src], capture_output=True, text=True)
        need(r.returncode == 0, "the swap fixture compiles standalone: " +
             ("" if r.returncode == 0 else r.stderr[:400]))
        if r.returncode == 0:
            r = subprocess.run(["java", "-cp", d, "Probe"],
                               capture_output=True, text=True)
            need(r.returncode == 0 and "PROBE OK" in r.stdout,
                 "the rotated-raster swap fixture (standard null, swapped "
                 "solves, the unrotated control): " +
                 (r.stdout.strip().splitlines()[-1] if r.stdout else r.stderr[:150]))
else:
    need(False, "the swap fixture methods are extractable")

print()
print("F — QR: THE QUARTER-SCALE CLOSING LEG (the densest dots)")
need(qc.find("// (d) v1.4.34 — THE QUARTER-SCALE CLOSING LEG") >
     qc.find("// (c) v1.4.33 — THE DOT-CLOSING LEG"),
     "decodeLuma's quarter leg is the FINAL leg (after plain/stretch/half/close)")
dlm = extract_method(qc, "    public static String decodeMulti(int[] pixels, int width, int height) {")
need(dlm and dlm.find("close3x3(hs, hw, hh)") < dlm.find("close3x3(qs, qw, qh)"),
     "decodeMulti's final block: the half-scale closed pass, THEN the "
     "quarter-scale closed pass")
need("int qw = hw / 2, qh = hh / 2;" in qc and "if (hw >= 128 && hh >= 128)" in qc,
     "the quarter leg guards (the quarter plane >= 64 — the detector floor)")
need("byte[] qs = halfLuma(hs, hw, hh);" in qc,
     "the quarter plane rides halfLuma twice (no new extraction)")

print("  -- the compiled REAL dense-dot probe (the honest pin) --")
jar = None
for p in glob.glob("/home/z/my-project/.gradle-home/caches/modules-2/files-2.1/"
                   "com.google.zxing/core/*/*/core-*.jar"):
    jar = p
if jar is None:
    jar = os.path.join(tempfile.gettempdir(), "zxing-core-gate.jar")
    r = subprocess.run(["curl", "-sL", "--retry", "2", "-o", jar,
                        "https://maven.aliyun.com/repository/public/"
                        "com/google/zxing/core/3.5.3/core-3.5.3.jar"],
                       capture_output=True, text=True)
    with open(jar, "rb") as f:
        if f.read(2) != b"PK":
            jar = None
need(jar, "the ZXing core jar is available (gradle cache or aliyun)")
if jar and javac:
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "QrCore.java")
        with open(src, "w", encoding="utf-8") as f:
            f.write(qc.replace("package com.bolt.browser.util;", ""))
        r = subprocess.run([javac, "-cp", jar, src], capture_output=True, text=True)
        need(r.returncode == 0, "QrCore compiles standalone: " +
             ("" if r.returncode == 0 else r.stderr[:300]))
        if r.returncode == 0:
            src2 = os.path.join(d, "Quarter.java")
            probe = r'''
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import java.util.*;

public class Quarter {
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    static Map<DecodeHintType,Object> hints() {
        Map<DecodeHintType,Object> h = new EnumMap<>(DecodeHintType.class);
        h.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        h.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        return h;
    }
    static String hyb(byte[] y, int w, int h) {
        try { LuminanceSource s = new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false);
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(s)), hints()).getText();
        } catch (Throwable t) { return null; } }
    static String hybInv(byte[] y, int w, int h) {
        try { LuminanceSource s = new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false);
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(s.invert())), hints()).getText();
        } catch (Throwable t) { return null; } }
    static String glob(byte[] y, int w, int h) {
        try { LuminanceSource s = new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false);
            return new QRCodeReader().decode(new BinaryBitmap(new GlobalHistogramBinarizer(s)), hints()).getText();
        } catch (Throwable t) { return null; } }
    static String globInv(byte[] y, int w, int h) {
        try { LuminanceSource s = new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false);
            return new QRCodeReader().decode(new BinaryBitmap(new GlobalHistogramBinarizer(s.invert())), hints()).getText();
        } catch (Throwable t) { return null; } }
    static String dual(byte[] y, int w, int h) {
        String r = hyb(y, w, h); if (r != null) return r;
        r = hybInv(y, w, h); if (r != null) return r;
        r = glob(y, w, h); if (r != null) return r;
        r = globInv(y, w, h); if (r != null) return r;
        return null;
    }
    // THE PRE-1.4.34 LADDER: plain dual, the stretch plane, the half
    // plane, the FULL-RES closed plane, the chroma plane — exactly what
    // v1.4.33 shipped. The quarter leg is the NEW final geometry.
    static String pre1434(byte[] y, int w, int h) {
        String r = dual(y, w, h); if (r != null) return r;
        byte[] st = QrCore.stretchLuma(y, w, h);
        if (st != null) { r = dual(st, w, h); if (r != null) return r; }
        int hw = w / 2, hh = h / 2;
        if (hw >= 64 && hh >= 64) {
            byte[] hs = QrCore.halfLuma(y, w, h);
            if (hs != null) { r = dual(hs, hw, hh); if (r != null) return r; }
        }
        byte[] cl = QrCore.close3x3(y, w, h);
        if (cl != null) { r = dual(cl, w, h); if (r != null) return r; }
        // the chroma plane is degenerate on a gray fixture (the skip
        // fires) — mirrored here for the record
        return null;
    }
    public static void main(String[] args) throws Exception {
        // ---- 1. THE DENSE-DOT CORPSE (the honest pin): the chess.com
        // shape under the MEASURED corpse configuration — light dot
        // modules on black (28px module pitch, 50% duty, AA edges, solid
        // finders, a centre logo) under a whole-frame glare gradient and
        // sensor noise. Measured in this session (the tuner grid): the
        // adaptive 16px block grid of HybridBinarizer splits MODULE
        // INTERIORS on the full and half planes (a 16px block sees one
        // gradient slice of a 28px module), the full-res closing cannot
        // fix a gradient — and the QUARTER plane aligns the blocks with
        // the module scale (2 modules per block), averages the noise 4x,
        // and its closing reach bridges what remains. The pre-1.4.34
        // ladder MISSES; the quarter leg DECODES.
        final String TEXT = "https://chess.com/game/live/987654321?username=grandmaster_bolt&tab=moves&share=qr34&x=1";
        int n = 500;
        int[] plain = QrCore.encode(TEXT, n);
        int minX = n, minY = n, maxX = 0, maxY = 0;
        for (int yy = 0; yy < n; yy++) for (int xx = 0; xx < n; xx++)
            if ((plain[yy*n+xx]&0xFFFFFF) == 0) { minX=Math.min(minX,xx); maxX=Math.max(maxX,xx);
                minY=Math.min(minY,yy); maxY=Math.max(maxY,yy); }
        int run7 = 0;
        for (int xx = minX; xx < n && (plain[minY*n+xx]&0xFFFFFF) == 0; xx++) run7++;
        int mod = run7 / 7, m = (maxX-minX)/mod + 1;
        boolean[][] B = new boolean[m][m];
        for (int r2 = 0; r2 < m; r2++) for (int c = 0; c < m; c++)
            B[r2][c] = (plain[(minY+r2*mod+mod/2)*n + minX+c*mod+mod/2]&0xFFFFFF) == 0;
        boolean[][] solid = new boolean[m][m];
        for (int r2 = 0; r2 < m; r2++) for (int c = 0; c < m; c++)
            solid[r2][c] = (r2<8&&c<8)||(r2<8&&c>=m-8)||(r2>=m-8&&c<8)||r2==6||c==6
                    ||(r2>=m-9&&c>=m-9);
        int S = 28, Q = 4*S, W = (m+8)*S;
        int lr0 = m/2 - 3, lr1 = m/2 + 3;
        double rad = S * 0.5 / 2.0;   // 50% duty cycle
        int[] dot = new int[W*W];
        java.util.Arrays.fill(dot, 0xFF141414);
        for (int r2 = 0; r2 < m; r2++) for (int c = 0; c < m; c++) {
            int x0 = Q + c*S, y0 = Q + r2*S;
            if (r2 >= lr0 && r2 <= lr1 && c >= lr0 && c <= lr1) {
                for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++)
                    dot[(y0+dy)*W + x0+dx] = 0xFFE8E8E8;
                continue;
            }
            if (solid[r2][c]) {
                for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++)
                    dot[(y0+dy)*W + x0+dx] = B[r2][c] ? 0xFFE6E6E6 : 0xFF141414;
                continue;
            }
            if (!B[r2][c]) continue;
            for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++) {
                double dd = Math.sqrt((dx - (S-1)/2.0)*(dx - (S-1)/2.0)
                        + (dy - (S-1)/2.0)*(dy - (S-1)/2.0));
                int v;
                if (dd <= rad) v = 230;
                else if (dd <= rad + 1.5) {
                    double f = (dd - rad) / 1.5;
                    v = Math.max(16, (int) Math.round(230 * (1 - f)));
                } else v = 16;
                dot[(y0+dy)*W + x0+dx] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        for (int y = 0; y < W; y++) for (int x = 0; x < W; x++) {
            int p = dot[y*W + x];
            int lum = ((p & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587
                    + ((p >> 16) & 0xFF) * 114) / 1000;
            double f = 1.0 - 0.75 * (1.0 - (double)(x + y) / (2.0 * W));
            int v = (int) Math.max(0, Math.min(255, Math.round(lum * f)));
            dot[y*W + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        int[] bl = new int[W*W];
        int BR = 2;
        for (int y = 0; y < W; y++) for (int x = 0; x < W; x++) {
            int sum = 0, cnt = 0;
            for (int dy = -BR; dy <= BR; dy++) for (int dx = -BR; dx <= BR; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= W || yy >= W) continue;
                int p = dot[yy*W+xx];
                sum += ((p & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587
                        + ((p >> 16) & 0xFF) * 114) / 1000;
                cnt++;
            }
            int v = sum / cnt;
            bl[y*W+x] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        java.util.Random rnd = new java.util.Random(11L);
        for (int i = 0; i < bl.length; i++) {
            int p = bl[i];
            int v = Math.max(0, Math.min(255,
                    ((p & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587
                     + ((p >> 16) & 0xFF) * 114) / 1000 + rnd.nextInt(61) - 30));
            bl[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        byte[] dl = QrCore.lumaOf(bl, W, W);
        eq("dense-dot corpse: the PRE-1.4.34 ladder MISSES (m=" + m + " W=" + W + ")",
            Quarter.pre1434(dl, W, W) == null);
        eq("dense-dot corpse: the ARGB decode misses",
            QrCore.decode(bl, W, W) == null);
        eq("dense-dot corpse: the QUARTER leg DECODES (decodeLuma)",
            QrCore.decodeLuma(dl, W, W) != null);
        eq("dense-dot corpse: the GALLERY ladder decodes (decodeMulti)",
            QrCore.decodeMulti(bl, W, W) != null);
        // ---- 2. THE v1.4.33 REGRESSION PIN: the dot corpse of that
        // round still rides the FULL-res closing leg (the quarter leg
        // only fires on failure — the budget law).
        final String T2 = "https://www.chess.com/game/live/123456789?username=grandmaster_bolt&tab=overview";
        int n2 = 336;
        int[] p2 = QrCore.encode(T2, n2);
        int minX2 = n2, minY2 = n2, maxX2 = 0, maxY2 = 0;
        for (int yy = 0; yy < n2; yy++) for (int xx = 0; xx < n2; xx++)
            if ((p2[yy*n2+xx]&0xFFFFFF) == 0) { minX2=Math.min(minX2,xx); maxX2=Math.max(maxX2,xx);
                minY2=Math.min(minY2,yy); maxY2=Math.max(maxY2,yy); }
        int run72 = 0;
        for (int xx = minX2; xx < n2 && (p2[minY2*n2+xx]&0xFFFFFF) == 0; xx++) run72++;
        int mod2 = run72 / 7, m2 = (maxX2-minX2)/mod2 + 1;
        boolean[][] B2 = new boolean[m2][m2];
        for (int r2 = 0; r2 < m2; r2++) for (int c = 0; c < m2; c++)
            B2[r2][c] = (p2[(minY2+r2*mod2+mod2/2)*n2 + minX2+c*mod2+mod2/2]&0xFFFFFF) == 0;
        boolean[][] sol2 = new boolean[m2][m2];
        for (int r2 = 0; r2 < m2; r2++) for (int c = 0; c < m2; c++)
            sol2[r2][c] = (r2<8&&c<8)||(r2<8&&c>=m2-8)||(r2>=m2-8&&c<8)||r2==6||c==6
                    ||(r2>=m2-9&&c>=m2-9);
        int S2 = 8, Q2 = 4*S2, W2 = (m2+8)*S2;
        int lr02 = m2/2 - 3, lr12 = m2/2 + 3;
        double rad2 = S2 * 0.5 / 2.0;
        int[] dot2 = new int[W2*W2];
        java.util.Arrays.fill(dot2, 0xFF141414);
        for (int r2 = 0; r2 < m2; r2++) for (int c = 0; c < m2; c++) {
            int x0 = Q2 + c*S2, y0 = Q2 + r2*S2;
            if (r2 >= lr02 && r2 <= lr12 && c >= lr02 && c <= lr12) {
                for (int dy = 0; dy < S2; dy++) for (int dx = 0; dx < S2; dx++)
                    dot2[(y0+dy)*W2 + x0+dx] = 0xFFE8E8E8;
                continue;
            }
            if (sol2[r2][c]) {
                for (int dy = 0; dy < S2; dy++) for (int dx = 0; dx < S2; dx++)
                    dot2[(y0+dy)*W2 + x0+dx] = B2[r2][c] ? 0xFFE6E6E6 : 0xFF141414;
                continue;
            }
            if (!B2[r2][c]) continue;
            for (int dy = 0; dy < S2; dy++) for (int dx = 0; dx < S2; dx++) {
                double dd = Math.sqrt((dx - (S2-1)/2.0)*(dx - (S2-1)/2.0)
                        + (dy - (S2-1)/2.0)*(dy - (S2-1)/2.0));
                int v;
                if (dd <= rad2) v = 230;
                else if (dd <= rad2 + 1.5) {
                    double f = (dd - rad2) / 1.5;
                    v = Math.max(16, (int) Math.round(230 * (1 - f)));
                } else v = 16;
                dot2[(y0+dy)*W2 + x0+dx] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        for (int y = 0; y < W2/2; y++) for (int x = 0; x < W2/2; x++) {
            int p = dot2[y*W2 + x];
            int lum = ((p & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587
                    + ((p >> 16) & 0xFF) * 114) / 1000;
            double f = 1.0 - 0.5 * (1.0 - (double)(x + y) / (double)W2);
            int v = (int) Math.max(0, Math.min(255, Math.round(lum * f)));
            dot2[y*W2 + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        int[] bl2 = new int[W2*W2];
        int BR2 = 3;
        for (int y = 0; y < W2; y++) for (int x = 0; x < W2; x++) {
            int sum = 0, cnt = 0;
            for (int dy = -BR2; dy <= BR2; dy++) for (int dx = -BR2; dx <= BR2; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= W2 || yy >= W2) continue;
                int p = dot2[yy*W2+xx];
                sum += ((p & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587
                        + ((p >> 16) & 0xFF) * 114) / 1000;
                cnt++;
            }
            int v = sum / cnt;
            bl2[y*W2+x] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        java.util.Random rnd2 = new java.util.Random(7L);
        for (int i = 0; i < bl2.length; i++) {
            int p = bl2[i];
            int v = Math.max(0, Math.min(255,
                    ((p & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587
                     + ((p >> 16) & 0xFF) * 114) / 1000 + rnd2.nextInt(31) - 15));
            bl2[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        byte[] dl2 = QrCore.lumaOf(bl2, W2, W2);
        eq("v1.4.33 dot corpse: the pre-1.4.33 ladder STILL misses",
            Quarter.pre1434(dl2, W2, W2) == null);
        // v1.4.34 re-pin (measured): the rescue leg of the v1.4.33 corpse
        // is the closing FAMILY (the v1.4.33 gate's own pin is the same
        // || form — the full-res leg misses this fixture in the measured
        // run; the half-scale closed plane in decodeMulti carries it).
        // The quarter leg only ADDS a failure-path geometry behind those.
        eq("v1.4.33 dot corpse: the closing family STILL rescues it " +
           "(decodeLuma or decodeMulti)",
            QrCore.decodeLuma(dl2, W2, W2) != null
            || QrCore.decodeMulti(bl2, W2, W2) != null);
        // ---- 3. PLAIN QRs UNCHANGED (the budget law)
        int[] plain3 = QrCore.encode("https://bolt.browser/ok", 200);
        byte[] y3 = QrCore.lumaOf(plain3, 200, 200);
        eq("a plain QR exits on the FIRST leg (unchanged)",
            QrCore.decodeLuma(y3, 200, 200) != null);
        if (Quarter.fails == 0) System.out.println("PROBE OK");
        else System.out.println("PROBE FAILS=" + Quarter.fails);
        System.exit(Quarter.fails == 0 ? 0 : 1);
    }
}
'''
            with open(src2, "w", encoding="utf-8") as f:
                f.write(probe)
            r = subprocess.run([javac, "-cp", jar + ":" + d, src2],
                               capture_output=True, text=True)
            need(r.returncode == 0, "the quarter probe compiles: " +
                 ("" if r.returncode == 0 else r.stderr[:400]))
            if r.returncode == 0:
                r = subprocess.run(["java", "-cp", jar + ":" + d, "Quarter"],
                                   capture_output=True, text=True)
                need(r.returncode == 0 and "PROBE OK" in r.stdout,
                     "the compiled REAL dense-dot probe: " +
                     ((r.stdout.strip().splitlines()[-1] + " | " +
                       next((l for l in r.stdout.splitlines() if "MISS" in l), ""))
                      if r.stdout else r.stderr[:200]))

print()
print("G — the pipeline + docs + the dated re-points")
need("CERT_EXPECT" in rels and "pubsrc" in rels and "pubrel" in rels
     and "bolt-rel-stages" in rels,
     "release.sh keeps the mandated shape (the staged gates -> smoke -> "
     "build -> SOURCE first, RELEASES last)")
need("v1.4.34 — THE SCANNER FIELD-OF-VIEW ROUND" in readme
     and "THE 4:3 STREAM LAW" in readme and "THE LETTERBOX LAW" in readme
     and "THE DUAL-PRIOR ACCEPTANCE" in readme
     and "THE CALIBRATION CONVERGENCE RETRY" in readme
     and "THE SWAP LEG" in readme and "THE QUARTER-SCALE CLOSING LEG" in readme,
     "the README carries the six v1.4.34 laws")
need("The 4:3 stream read-back (v1.4.34)" in readme
     and "The letterbox read-back (v1.4.34)" in readme
     and "The dual-prior read-back (v1.4.34)" in readme
     and "The convergence read-back (v1.4.34)" in readme
     and "The swap read-back (v1.4.34)" in readme
     and "The quarter-leg read-back (v1.4.34)" in readme,
     "the README's open field read-backs grew the six v1.4.34 entries")
need("v1.4.34 re-point (2026-11-27)" in read("scripts/test_v1388.py")
     and "v1.4.34 re-point (2026-11-27)" in read("scripts/test_v1426.py")
     and "v1.4.34 re-point (2026-11-27)" in read("scripts/test_v1427.py")
     and "v1.4.34 re-point (2026-11-27)" in read("scripts/test_v1429.py")
     and "v1.4.34 re-point (2026-11-27)" in read("scripts/test_v1430.py")
     and "v1.4.34 re-point (2026-11-27)" in read("scripts/test_v1432.py"),
     "the dated re-points exist in the prior gates (v1388/v1426/v1427/"
     "v1429/v1430/v1432)")

print()
if fails:
    print(f"test_v1434: {len(fails)} FAILURE(S)")
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("test_v1434: ALL GREEN")
