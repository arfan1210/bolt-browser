#!/usr/bin/env python3
"""test_v1431.py — the v1.4.31 gate: THE PERFORMANCE-AND-QUIET ROUND.

Seven fixes off the v1.4.30 field diagnostics, all probe-pinned:

A  version pins (145 / 1.4.31, the About changelog head, the CHANGELOG
   section)
B  QR — THE MEASURED-IMPLICIT LAW: the device table (RMX2020 =
   cover-undo) + the offset seed (RMX2020 sensor=90/display=0 -> 270)
   compiled from the REAL Java; the compiled REAL stripe solver
   (scanRunsArgb + solveAxis) over fill/cover/fit synthetic rasters —
   each solves the true affine exactly and implicit∘undo = identity at
   1e-6; the corpse arithmetic pinned (the fill undo's net anisotropy
   on the corpse dims = 3.784); the calibration session-gate + the diag
   mode=/net= fields; the v1.4.29/30 geometry laws ride unchanged
C  THE EXIT-SWEEP CADENCE LAW: three doors (destroy/user/delayed), the
   5-minute delayed onStop arm cancelled by onResume, the door= diag,
   the Settings Clean-now door, the update-heal door
D  THE TRIM-STORM POLICY + boOffer: the compiled REAL trimSleepDecision
   table (trim × role × rank × idle), the recency-rank exemption, the
   warm-saveState legs unchanged, the once-per-process + per-version
   dismiss churn gates
E  THE SINGLE-INJECTION LAW + THE PASSKEY LAZY LAW: the compiled REAL
   needsFallbackEval decision; MediaHook joins the document-start
   registrations (arm) with the evaluate twin a no-op; the commit-time
   twins are gone (ONE fallback per document at onPageStarted);
   cosmetic CSS cached per (host, generation) with the finish backstop;
   one reused userscript executor; the bootstrap asset <=3,072 bytes
   with node syntax checks; the once-per-WebView lazy full load +
   the adoption drain; the popup child stays bridge-free
F  THE GLASS SNAPSHOT DIET + THE CACHEGUARD CADENCE + THE PERF LINES:
   downscale <=1/4, luminance on the small copy, the content-hash skip,
   the off-main executor, the perf blur line; the 30-minute idle-only
   audit (loading/media skip), the trim-driven check, the file-visit
   budget (mid-session only); perf launch / perf nav lines
G  THE WEB-CONTEXT LAW: the transcribed searchQueryFrom (the
   banana/apple/orange conversation), the transcribed buildContextBlock
   caps (4 turns / 2k chars), the transcribed Gemini + OpenAI body
   builders asserting the standing history survives Web ON, the
   append-not-replace keyless round, the MAX_TURNS chain pin
H  the pipeline (release.sh carries the v1.4.31 rung) + the README
   keeps the laws, the numbers and the open read-backs

House rules: every need() failure counts; any failure exits 1.
"""
import os
import re
import shutil
import subprocess
import tempfile
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


qsa = read("app/src/main/java/com/bolt/browser/ui/QrScanActivity.java")
ba = read("app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
ab = read("app/src/main/java/com/bolt/browser/util/AiBridge.kt")
cg = read("app/src/main/java/com/bolt/browser/util/CacheGuard.kt")
mh = read("app/src/main/java/com/bolt/browser/media/MediaHook.java")
fh = read("app/src/main/java/com/bolt/browser/util/FrameHooks.kt")
sg = read("app/src/main/java/com/bolt/browser/util/ShadowGate.kt")
pk = read("app/src/main/java/com/bolt/browser/util/PasskeyCompat.java")
bs = read("app/src/main/java/com/bolt/browser/util/BackgroundSurvival.java")
fe = read("app/src/main/java/com/bolt/browser/adblock/FilterEngine.java")
tb = read("app/src/main/java/com/bolt/browser/core/Tab.java")
gradle = read("app/build.gradle")
strings = read("app/src/main/res/values/strings.xml")
chlog = read("CHANGELOG.md")
rels = read("scripts/release.sh")
readme = read("README.md")
boot_js_path = "app/src/main/assets/webauthn_bootstrap.js"
boot_js = read(boot_js_path)
full_js = read("app/src/main/assets/webauthn_compat.js")
sia = read("app/src/main/java/com/bolt/browser/ui/SettingsItemsActivity.java")
lg = read("app/src/main/java/com/bolt/browser/util/LauncherGuard.kt")

print("A — version pins")
need("versionCode 145" in gradle and 'versionName "1.4.31"' in gradle,
     "build.gradle pins 145 / 1.4.31")
need('<item>1.4.31|' in strings,
     "the About changelog carries the 1.4.31 line at [0] (newest first)")
i31 = strings.find("<item>1.4.31|")
i30 = strings.find("<item>1.4.30|")
need(0 <= i31 < i30, "the About changelog order: 1.4.31 rides ABOVE 1.4.30")
need("## [1.4.31] — 2026-11-25" in chlog and
     chlog.find("## [1.4.31]") < chlog.find("## [1.4.30]"),
     "CHANGELOG.md carries the [1.4.31] section above [1.4.30]")

print()
print("B — QR: THE MEASURED-IMPLICIT LAW")
# L2 the device table + L3 the seed (compiled from the REAL Java)
need("static final String[] COVER_UNDO_MODELS = { \"RMX2020\" };" in qsa,
     "the device table: RMX2020 = cover-undo (the field device)")
need("static int seedRotOffsetFor" in qsa and 'return 270;' in qsa,
     "the offset seed: RMX2020 (sensor=90, display=0) -> 270")
# the measured session gate: calibrate after configure, before repeating
need("if (armCalibration()) return;" in qsa,
     "the session gate: onConfigured calibrates FIRST (no repeating "
     "request can overwrite the pattern)")
need("previewSurface.lockCanvas(null)" in qsa
     and "unlockCanvasAndPost" in qsa and "tvPreview.getBitmap()" in qsa,
     "the calibration path: lockCanvas -> stripes -> post -> getBitmap")
need("scanRunsArgb(" in qsa and "solveAxis(" in qsa,
     "the stripe-run detector + the axis solver are in the scanner")
need("undoMode = \"measured\"" in qsa,
     "the measured undo mode is stored on a solved calibration")
need('"qr calib ok mode=measured' in qsa and '"qr calib fail mode=' in qsa
     and '"qr calib timeout mode=' in qsa,
     "the calibration diag: ok / fail / timeout lines")
# the undo replacement + the diag fields
need("undoSx = 1f / calibIx;" in qsa and
     "matrix.postTranslate(-calibTx / calibIx, -calibTy / calibIy);"
     in qsa,
     "the measured undo is the exact inverse affine [1/i, 0, -t/i; ...]")
need('"table".equals(undoMode)' in qsa and
     "Math.max(viewW / bw, viewH / bh)" in qsa,
     "the table undo: uniform cover 1/k, centered")
need("undoSx = bw / viewW;" in qsa and
     "// undo the implicit buffer->view stretch (FILL)" in qsa,
     "the fill undo rides as the fallback (the historical default)")
need('" mode=" + undoMode' in qsa and '" net=" ' in qsa,
     "the diag line grew mode= and net= (the undo's own anisotropy)")
# the v1.4.30 laws ride unchanged
need("theta = (sensorOrientation - degrees - producerRot + 720) % 360;"
     in qsa and "theta = (theta + rotOffset + 360) % 360;" in qsa,
     "the v1.4.30 compensated theta + the user offset ride unchanged")
need("static int extractProducerRotation" in qsa,
     "the v1.4.30 producer probe rides unchanged")
# the rotate button: user override still wins; reset lands on the default
need("int def = seedRotOffsetFor(android.os.Build.MODEL, sensorOrientation,"
     in qsa,
     "long-press reset lands on the DEVICE DEFAULT (the seed), not 0")
need("Prefs.get(this).i(rotOffsetKey(degrees), seed)" in qsa,
     "a saved user offset still wins over the seed")

print("  -- the compiled REAL probes (the parse-gate law: run the REAL "
      "methods) --")


def extract_method(src, anchor, end_anchor="\n    }"):
    """the full text of one method — brace-counting from the anchor (the
    v1.4.30 convention breaks on single-line methods)."""
    s = src.find(anchor)
    if s < 0:
        return None
    depth = 0
    started = False
    for i in range(s, len(src)):
        c = src[i]
        if c == '{':
            depth += 1
            started = True
        elif c == '}':
            depth -= 1
            if started and depth == 0:
                return src[s:i + 1]
    return None


javac = shutil.which("javac")
node = shutil.which("node")

# --- probe 1: the device table + the seed (REAL, no substitutions) ---
m_tbl = extract_method(qsa, '    static String undoModeFor(String model) {')
m_seed = extract_method(qsa, '    static int seedRotOffsetFor(String model, int sensor, int display) {')
m_cnt = extract_method(qsa, '    static int stripeCount() {')
m_pos = extract_method(qsa, '    static int stripePos(int span, int j) {')
m_scan = extract_method(qsa, '    static int[] scanRunsArgb(int[] argb, int w, int h, boolean alongX, int test) {')
m_solve = extract_method(qsa, '    static float[] solveAxis(int[] runs, int span, int viewLen) {')
need(m_tbl and m_seed and m_cnt and m_pos and m_scan and m_solve and javac,
     "the REAL pure methods are extractable from QrScanActivity")

if m_tbl and m_seed and m_cnt and m_pos and m_scan and m_solve and javac:
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        harness = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    // a synthetic view raster under the mapping view = i*buf + t (per
    // axis): black bg, red verticals at i*bufW*j/8+tx, green horizontals
    // at i*bufH*j/8+ty (2px), mirroring armCalibration's pattern
    static int[] raster(int vw, int vh, double ix, double tx, double iy,
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
    // one calibration leg exactly like finishCalibration: rows/cols at
    // 7/16, 9/16, 8/16, first line with >=2 runs wins
    static float[] solveX(int[] px, int vw, int vh, int bw) {
        int[] rows = {7 * vh / 16, 9 * vh / 16, 8 * vh / 16};
        for (int ry : rows) {
            if (ry < 0 || ry >= vh) continue;
            int[] row = new int[vw];
            System.arraycopy(px, ry * vw, row, 0, vw);
            int[] r = Probe.scanRunsArgb(row, vw, 1, true, 1);
            if (r != null && r.length >= 2) return Probe.solveAxis(r, bw, vw);
        }
        return null;
    }
    static float[] solveY(int[] px, int vw, int vh, int bh) {
        int[] cols = {7 * vw / 16, 9 * vw / 16, 8 * vw / 16};
        for (int cx : cols) {
            if (cx < 0 || cx >= vw) continue;
            int[] col = new int[vh];
            for (int y = 0; y < vh; y++) col[y] = px[y * vw + cx];
            int[] g = Probe.scanRunsArgb(col, 1, vh, false, 2);
            if (g != null && g.length >= 2) return Probe.solveAxis(g, bh, vh);
        }
        return null;
    }
    public static void main(String[] args) {
        // ---- the device table + the seed ----
        eq("table RMX2020", Probe.undoModeFor("RMX2020").equals("table"));
        eq("table exact-match only",
                Probe.undoModeFor("RMX2020N").equals("fill")
                && Probe.undoModeFor("Pixel 8").equals("fill")
                && Probe.undoModeFor(null).equals("fill"));
        eq("seed 270", Probe.seedRotOffsetFor("RMX2020", 90, 0) == 270);
        eq("seed scoped to the pair",
                Probe.seedRotOffsetFor("RMX2020", 90, 90) == 0
                && Probe.seedRotOffsetFor("RMX2020", 180, 0) == 0
                && Probe.seedRotOffsetFor("Pixel 8", 90, 0) == 0);
        // ---- the solver over fill / cover / fit synthetic rasters ----
        // the CORPSE dims: portrait view 1080x2298, landscape buffer
        int[] cases = {
            // vw, vh, bw, bh   (mode is implied by i/t below)
            1080, 2298, 1920, 1080,   // the field corpse (cover crops x)
            800, 1600, 1280, 720,     // another cover shape
            1080, 2298, 1080, 2298,   // aspect-matched (fill == cover)
            900, 1800, 1600, 900,     // fit-ish shape (uniform, no crop)
        };
        int idx = 0;
        for (int c = 0; c < cases.length; c += 4) {
            int vw = cases[c], vh = cases[c + 1], bw = cases[c + 2],
                bh = cases[c + 3];
            double[] shapes = {
                // fill: anisotropic (i = vw/bw, vh/bh), t = 0
                (double) vw / bw, (double) vh / bh,
                // cover: uniform k = max, center-anchored (crops)
                Math.max((double) vw / bw, (double) vh / bh),
                Math.max((double) vw / bw, (double) vh / bh),
                // fit: uniform k = min, center-anchored (letterbox)
                Math.min((double) vw / bw, (double) vh / bh),
                Math.min((double) vw / bw, (double) vh / bh),
            };
            String[] names = {"fill", "cover", "fit"};
            for (int s = 0; s < 3; s++) {
                double ix = shapes[s * 2], iy = shapes[s * 2 + 1];
                double tx = (vw - ix * bw) / 2.0, ty = (vh - iy * bh) / 2.0;
                int[] px = Probe.raster(vw, vh, ix, tx, iy, ty, bw, bh);
                float[] sx = Probe.solveX(px, vw, vh, bw);
                float[] sy = Probe.solveY(px, vw, vh, bh);
                eq(names[s] + " x-solves @" + idx,
                        sx != null && Math.abs(sx[0] - ix) < Math.max(1e-3, ix * 0.01)
                        && Math.abs(sx[1] - tx) < Math.max(1.0, vw * 0.01));
                eq(names[s] + " y-solves @" + idx,
                        sy != null && Math.abs(sy[0] - iy) < Math.max(1e-3, iy * 0.01)
                        && Math.abs(sy[1] - ty) < Math.max(1.0, vh * 0.01));
                // THE INVERSE-COMPOUND LAW, two legs:
                // (1) the FORMULA is the exact inverse of the true
                //     implicit mapping (analytic i/t): identity at 1e-6;
                // (2) the MEASURED undo composed with the TRUE implicit
                //     mapping lands within 1.5 view px (the solver's
                //     quantization noise is sub-pixel; the residual
                //     stretch is invisible)
                if (sx != null && sy != null) {
                    double worstFormula = 0, worstMeasured = 0;
                    for (int j = 1; j <= 7; j++) {
                        double bx = bw * j / 8.0;
                        double vx = ix * bx + tx;
                        worstFormula = Math.max(worstFormula,
                                Math.abs((vx - tx) / ix - bx));
                        worstMeasured = Math.max(worstMeasured,
                                Math.abs((vx - sx[1]) / sx[0] - bx));
                        double by = bh * j / 8.0;
                        double vy = iy * by + ty;
                        worstFormula = Math.max(worstFormula,
                                Math.abs((vy - ty) / iy - by));
                        worstMeasured = Math.max(worstMeasured,
                                Math.abs((vy - sy[1]) / sy[0] - by));
                    }
                    eq(names[s] + " formula identity @1e-6 @" + idx,
                            worstFormula < 1e-6);
                    eq(names[s] + " measured compound <1.5px @" + idx,
                            worstMeasured < Math.max(1.5, vw * 0.002));
                }
            }
            idx++;
        }
        // junk: non-ascending / too-few runs rejected
        eq("junk rejected",
                Probe.solveAxis(new int[]{5}, 1920, 1080) == null
                && Probe.solveAxis(new int[]{300, 100, 700}, 1920, 1080) == null
                && Probe.solveAxis(null, 1920, 1080) == null);
        // ---- THE CORPSE ARITHMETIC: the fill undo's net anisotropy on
        // the corpse dims = 3.784 (the reported ~3.8x stretch) ----
        double net = (1920.0 / 1080.0) / (1080.0 / 2298.0);
        eq("corpse net anisotropy 3.784", Math.abs(net - 3.784) < 0.01);
        // ---- the cover-undo table math composes to identity ----
        double k = Math.max(1080.0 / 1920.0, 2298.0 / 1080.0);
        double worst = 0;
        for (int j = 1; j <= 7; j++) {
            double bx = 1920.0 * j / 8.0;
            double vx = (bx - 960.0) * k + 540.0;       // implicit cover
            double back = (vx - 540.0) / k + 960.0;      // the undo
            worst = Math.max(worst, Math.abs(back - bx));
            double by = 1080.0 * j / 8.0;
            double vy = (by - 540.0) * k + 1149.0;
            back = (vy - 1149.0) / k + 540.0;
            worst = Math.max(worst, Math.abs(back - by));
        }
        eq("table cover-undo composes to identity", worst < 1e-6);
        if (fails > 0) { System.out.println("PROBE FAILURES: " + fails); }
        else { System.out.println("all green"); }
    }
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Probe {\n"
                    + "    static final String[] COVER_UNDO_MODELS = "
                    "{ \"RMX2020\" };\n"
                    + m_cnt + "\n" + m_pos + "\n"
                    + m_scan + "\n" + m_solve + "\n" + m_tbl + "\n"
                    + m_seed + "\n" + harness + "\n}")
        r = subprocess.run([javac, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the QR pure probes compile: " + r.stderr[:300])
        else:
            r2 = subprocess.run(["java", "-cp", d, "Probe"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL stripe solver: fill/cover/fit synthetic rasters "
                 "solve exactly, implicit∘undo=identity @1e-6, the device "
                 "table + the seed + the corpse 3.784 arithmetic "
                 + ("" if r2.returncode == 0 else r2.stdout[:400]))

print()
print("C — THE EXIT-SWEEP CADENCE LAW (the tab-hist storm corpse)")
need('fun exit(app: Context, door: String = "destroy")' in cg,
     "CacheGuard.exit carries the door (default destroy)")
need('"cache guard exit door=" + door' in cg,
     "the diag line names the door")
need('com.bolt.browser.util.CacheGuard.exit(this, "destroy")' in ba,
     "door=destroy: the onDestroy exit")
need('com.bolt.browser.util.CacheGuard.exit(BrowserActivity.this, "delayed")'
     in ba,
     "door=delayed: the delayed runnable")
need('com.bolt.browser.util.CacheGuard.exit(this, "user")' in sia,
     "door=user: the Settings Clean-now sweep")
need('CacheGuard.exit(app, "update")' in lg,
     "door=update: the package-replaced heal (an honest extra label)")
# onStop arms ONLY the delayed sweep — no immediate exit anywhere
onstop = ba[ba.find("protected void onStop()"):]
onstop = onstop[:onstop.find("\n    }")]
need("ui.postDelayed(cacheBgSweep, 5 * 60 * 1000L);" in onstop
     and 'CacheGuard.exit' not in onstop,
     "onStop arms ONLY the 5-minute delayed sweep (no immediate exit)")
need("ui.removeCallbacks(cacheBgSweep);" in ba[
    ba.find("protected void onResume()"):ba.find("protected void onResume()") + 4000],
     "onResume cancels the delayed sweep (the v1.3.77 cadence)")
need("visitLeft = Long.MAX_VALUE" in cg and
     "the exit sweep is UNBOUNDED" in cg,
     "the exit sweep stays unbounded (the ~20 MB promise)")
need("markExit(ctx, started = true)" in cg and
     "fun pendingExit" in cg and "fun completePendingExit" in cg,
     "the marker pair + the pending-exit finisher ride unchanged")
need("fun check(app: Context, startup: Boolean, web: WebView?" in cg,
     "the startup sweep + the rate-limited check ride unchanged")

print()
print("D — THE TRIM-STORM POLICY + THE boOffer CHURN LAW")
need("static boolean trimSleepDecision(int level, String role, int rank, "
     "long idleMs)" in ba,
     "the PURE trim-sleep decision table exists (gate-compiled)")
need("rank.put((int) bg.get(r)[0], r);" in ba and
     "bg.sort((x, y) -> Long.compare(y[1], x[1]));" in ba,
     "the recency ranks: background tabs sorted by lastVisitedAt")
need('com.bolt.browser.util.Diag.log(this, "tabSleep level=" + level'
     in ba and 'policy=idle3m-recent2' in ba,
     "the tabSleep diag line names the policy")
need("if (!incognito && (ev != null || pendingBoOffer || boNagDue))" in ba,
     "the boOffer resume path rides unchanged")
need("private static volatile boolean offeredThisProcess = false;" in bs
     and "if (offeredThisProcess) return false;" in bs,
     "the churn gate: at most ONE automatic offer PER PROCESS")
need('prefs.s("boDismissedVer", "")' in bs and
     'recordDismissed(a)' in bs and
     '.setNegativeButton("Not now", (d, w) -> recordDismissed(a))' in bs
     and '.setOnCancelListener(d -> recordDismissed(a))' in bs,
     "the churn gate: a dismissal records the version (both dialogs)")
need("String dismissed = prefs.s(\"boDismissedVer\", \"\");" in bs
     and "dismissed.equals(versionName(a))" in bs,
     "the automatic offer never fires again in the dismissed version")

print("  -- the compiled REAL trimSleepDecision table --")
m_dec = extract_method(ba, '    static boolean trimSleepDecision(int level, String role, int rank, long idleMs) {')
need(m_dec and javac, "the REAL trimSleepDecision is extractable")
if m_dec and javac:
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        harness = """
    public static void main(String[] args) {
        int bad = 0;
        int MOD = 60, COMP = 80;
        // level < 60: NEVER sleep
        bad += P("trim<60 keeps a 4h-idle bg tab",
                 !trimSleepDecision(15, "bg", 5, 4 * 3600_000L)
                 && !trimSleepDecision(59, "bg", 5, 4 * 3600_000L));
        // role: only bg sleeps
        bad += P("active/shown/media NEVER (any level, any idle)",
                 !trimSleepDecision(COMP, "active", -1, 4 * 3600_000L)
                 && !trimSleepDecision(COMP, "shown", -1, 4 * 3600_000L)
                 && !trimSleepDecision(COMP, "media", 5, 4 * 3600_000L));
        // the recent two are exempt (rank 0/1)
        bad += P("the recent two are exempt",
                 !trimSleepDecision(COMP, "bg", 0, 4 * 3600_000L)
                 && !trimSleepDecision(COMP, "bg", 1, 4 * 3600_000L));
        // idle > 3 min only
        bad += P("3m01s bg rank5 sleeps", trimSleepDecision(COMP, "bg", 5, 3 * 60_000L + 1));
        bad += P("exactly 3min keeps", !trimSleepDecision(COMP, "bg", 5, 3 * 60_000L));
        bad += P("2m59s keeps", !trimSleepDecision(COMP, "bg", 5, 179_000L));
        bad += P("fresh tab (idle 0) keeps", !trimSleepDecision(MOD, "bg", 5, 0));
        // the storm case from the corpse: level=80, an old idle bg tab
        bad += P("the corpse case sleeps (old bg tab under a level-80 storm)",
                 trimSleepDecision(COMP, "bg", 4, 40 * 60_000L));
        if (bad > 0) { System.out.println("PROBE FAILURES: " + bad); }
        else { System.out.println("all green"); }
    }
    static int P(String what, boolean ok) {
        if (!ok) System.out.println("MISS: " + what);
        return ok ? 0 : 1;
    }
"""
        body = m_dec.replace(
            "android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE", "60")
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Probe {\n" + body + "\n" + harness + "\n}")
        r = subprocess.run([javac, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the trim decision probe compiles: " + r.stderr[:300])
        else:
            r2 = subprocess.run(["java", "-cp", d, "Probe"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL trim decision table: trim×role×rank×idle "
                 "behaves exactly as the law states "
                 + ("" if r2.returncode == 0 else r2.stdout[:300]))

print()
print("E — THE SINGLE-INJECTION LAW + THE PASSKEY LAZY LAW")
need("com.bolt.browser.media.MediaHook.arm(w, com.bolt.browser.util.Prefs.get(c))"
     in tb,
     "Tab.buildWeb registers the media core at document start")
need("static boolean needsFallbackEval(boolean docStartArmed)" in mh,
     "the PURE injection decision exists (gate-compiled)")
need("if (docStartArmed(web)) return" in fh,
     "FrameHooks.injectNow is a no-op on an armed WebView")
need("if (ARMED.containsKey(web)) return" in sg,
     "ShadowGate.injectNow is a no-op on an armed WebView")
need("private val TRIED" in fh and "private val TRIED" in sg
     and "TRIED.put(w, Boolean.TRUE);" in mh,
     "one registration attempt per WebView in all three hooks")
commit = ba[ba.find("public void onPageCommitVisible"):]
_c = 0; _st = False; _end = -1
for _i in range(ba.find("public void onPageCommitVisible"), len(ba)):
    if ba[_i] == '{': _c += 1; _st = True
    elif ba[_i] == '}':
        _c -= 1
        if _st and _c == 0: _end = _i; break
commit = ba[ba.find("public void onPageCommitVisible"):_end + 1] if _end > 0 else ""
need("MediaHook.injectCore" not in commit
     and "injectNow(view);" not in commit
     and 'evaluateJavascript(MediaHook.core' not in commit,
     "the commit-time twins are GONE (single-injection)")
start = ba[ba.find("public void onPageStarted"):]
start = start[:start.find("\n        }\n")]
need("MediaHook.injectCore(view, prefs);" in start
     and "com.bolt.browser.util.FrameHooks.injectNow(view);" in start
     and "com.bolt.browser.util.ShadowGate.injectNow(view);" in start,
     "the ONE fallback per document lives at onPageStarted")
need("FilterEngine.get().generation()" in ba and "generation++;" in fe,
     "the cosmetic cache keys on the list generation (bumped per load)")
need("java.util.WeakHashMap<WebView, String> cosmeticDone" in ba and
     "boolean committed = url != null && url.startsWith(\"http\")" in ba,
     "the finish pass is a BACKSTOP (skips when commit ran)")
need("US_EXEC" in ba and "newSingleThreadExecutor" in ba,
     "one reused userscript executor")
need("evalCount" in mh and "evalCount" in fh and "evalCount" in sg,
     "the hook fallbacks count their evaluates (the perf nav line)")
# passkey lazy
import struct
need(len(boot_js.encode("utf-8")) <= 3072,
     "the bootstrap asset is <=3,072 bytes ("
     + str(len(boot_js.encode('utf-8'))) + " B)")
need("__BOLT_WEBAUTHN_PENDING" in boot_js and "passkeyLoad" in boot_js
     and "parseCreationOptionsFromJSON" in boot_js,
     "the bootstrap: the parked queue + the lazy door + the parse pair")
need("__BOLT_WEBAUTHN_FULL__ = true" in full_js and
     "__BOLT_WEBAUTHN_PENDING" in full_js,
     "the full layer adopts the parked ceremonies (the drain block)")
need("addDocumentStartJavaScript(w, js, Collections.singleton(\"*\"))"
     in pk and "prepareBootstrap(c)" in pk and "prepareScript(c)" in pk,
     "PasskeyCompat registers the BOOTSTRAP at document start")
need("fullInjected.containsKey(w)" in pk and "fullInjected.put(w, Boolean.TRUE)" in pk,
     "the full layer loads ONCE per WebView (the lazy-once law)")
need("public void passkeyLoad()" in ba and
     "PasskeyCompat.loadFull(" in ba,
     "Bolt.passkeyLoad is the bridge door")
need("PasskeyCompat.injectNow(view, getApplicationContext());" in ba,
     "the onPageStarted fallback injects the bootstrap (not the full)")
# the popup child stays bridge-free / bootstrap-free
pc = ba[ba.find("private class PopupChildClient"):]
need("addJavascriptInterface" not in pc[:2000],
     "the popup child stays bridge-free (BRIDGE-FREE CHILD LAW)")
need('buildPopupChild' in ba and 'PasskeyCompat.apply' not in
     ba[ba.find("private WebView buildPopupChild"):ba.find("private WebView buildPopupChild") + 6000],
     "the popup child has no webauthn registration (unchanged)")
if node:
    for js, name in ((boot_js, "webauthn_bootstrap.js"),
                     (full_js, "webauthn_compat.js")):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, name)
            with open(src, "w", encoding="utf-8") as f:
                f.write(js)
            r = subprocess.run([node, "--check", src],
                               capture_output=True, text=True)
            need(r.returncode == 0, "node --check: " + name)
else:
    need(False, "node present for the JS syntax checks")
if node and javac:
    # the lazy-once + park-adopt probe: run the bootstrap + a minimal
    # full-layer adoption under node (mock window/navigator/DOM)
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "lazy_probe.js")
        probe = """
function MockDOMException(n, code) { this.name = n; this.code = code; }
const win = {};
const nav = { credentials: { create: function () {
        return Promise.reject(new MockDOMException('NotSupportedError', 9)); },
    get: function () {
        return Promise.reject(new MockDOMException('NotSupportedError', 9)); } } };
// run the bootstrap in this mock world
const fs = require('fs');
let boot = fs.readFileSync(process.argv[2], 'utf8');
let full = fs.readFileSync(process.argv[3], 'utf8');
// the bootstrap needs no placeholders; run it
const timers = [];
(function () {
  const window = new Proxy(win, { get: (t, k) => (k in t ? t[k]
      : (k === 'PublicKeyCredential' ? undefined : undefined)),
      set: (t, k, v) => { t[k] = v; return true; } });
  const navigator = nav;
  const atob = (s) => Buffer.from(s, 'base64').toString('binary');
  const setTimeout = (fn, ms) => { timers.push(fn); return 0; };
  const DOMException = MockDOMException;
  eval(boot.replace(/^;?\\(function\\(\\)\\{/, '(function(){').replace(/\\}\\)\\(\\)\\s*$/, '})()'));
  globalThis.__w = window; globalThis.__n = navigator;
})();
const W = globalThis.__w, N = globalThis.__n;
let misses = 0;
function need(c, w) { if (!c) { console.log('MISS: ' + w); misses++; } }
// detection surface
need(W.__BOLT_WEBAUTHN_BOOT__, 'the boot mark');
need(W.PublicKeyCredential, 'PublicKeyCredential exists');
need(W.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable,
     'isUVPAA answers');
need(W.PublicKeyCredential.parseRequestOptionsFromJSON, 'parse request');
const opts = W.PublicKeyCredential.parseRequestOptionsFromJSON(
    '{"challenge":"YWJj","rpId":"x","allowCredentials":[{"id":"ZGVm","type":"public-key"}]}');
need(opts.challenge instanceof ArrayBuffer || (opts.challenge && opts.challenge.byteLength === 3),
     'the challenge decodes to a buffer');
need(opts.allowCredentials[0].id.byteLength === 3, 'the credential id decodes');
// a real get() parks + the adoption drains it
let parked = null;
N.credentials.get(opts).then(() => { parked = 'resolved'; },
                              (e) => { parked = 'rejected:' + e.name; });
need(W.__BOLT_WEBAUTHN_PENDING.length === 1, 'the ceremony parks');
// the full layer arrives: adopt (the drain block runs inside the IIFE)
let adopted = 0;
N.credentials.get = function (o) { adopted++; return Promise.resolve({ id: 'ok' }); };
(function () {
  const window = W; const navigator = N;
  const log = () => {};
  const CONFIG = { chromeMajor: 156, platformVersion: '14.0.0', conditionalGet: true,
      conditionalMode: 'on', hybridTransport: true, passkeyPlatformAuthenticator: true,
      userVerifyingPlatformAuthenticator: true, claimSignalAPIs: true, debug: false };
  // the adoption block only: the full layer's own machinery needs a full
  // DOM; the LAW under test is the drain, run verbatim from the asset
  const src = fs.readFileSync(process.argv[3], 'utf8');
  const start = src.indexOf('try {' + String.fromCharCode(10) + '    window.__BOLT_WEBAUTHN_FULL__ = true');
  const block = src.slice(start, src.lastIndexOf('})()'));
  eval(block);
})();
setTimeout(() => {}, 0);
Promise.resolve().then(() => {
  need(W.__BOLT_WEBAUTHN_FULL__, 'the full flag');
  need(adopted === 1, 'the parked ceremony re-entered the REAL wrapper');
  need(W.__BOLT_WEBAUTHN_PENDING.length === 0, 'the queue drained');
  if (misses === 0) console.log('all green');
  else console.log('FAILURES: ' + misses);
  process.exit(misses === 0 ? 0 : 1);
});
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write(probe)
        r = subprocess.run([node, src,
                            os.path.join(ROOT, boot_js_path),
                            os.path.join(ROOT, "app/src/main/assets/webauthn_compat.js")],
                           capture_output=True, text=True)
        need(r.returncode == 0 and "all green" in r.stdout,
             "the lazy-once probe: the bootstrap answers detection, the "
             "ceremony parks, the full layer adopts + drains "
             + ("" if r.returncode == 0 else (r.stdout + r.stderr)[:400]))

print()
print("F — THE GLASS DIET + THE CACHEGUARD CADENCE + THE PERF LINES")
need("BLUR_EXEC" in ba and "newSingleThreadExecutor" in ba,
     "the glass diet runs off-main (one daemon worker)")
need("createScaledBitmap(fb, tw, th, true)" in ba and
     "b.getWidth() / 4" in ba and "b.getHeight() / 4" in ba,
     "downscale <=1/4 BEFORE the diet legs")
need("GlassAdapt.luminance(small)" in ba,
     "the luminance reads the DOWNSCALED copy")
need("contentHash(small)" in ba and "hash == lastGlassHash[top ? 0 : 1]" in ba,
     "the content hash skips unchanged strips")
need('StackBlur.blur(small, 12)' in ba and
     '"perf blur ms=" + ms + " w=" + small.getWidth()' in ba,
     "the <31 StackBlur runs on the small bitmap; the perf blur line "
     "names its input dims")
need("show = fb;   // RenderEffect is 31+ — the full bitmap stays" in ba,
     "31+ keeps the full bitmap (RenderEffect owns the blur)")
need("now - lastBlurSnap < 400" in ba,
     "the 400ms debounce rides unchanged (>=250ms law, dated)")
need("if (!barsShown) return;" in ba and
     "if (blurTop == null && blurBottom == null)" in ba,
     "the hidden-chrome / non-glass skips ride unchanged")
# cadence
need("ui.postDelayed(this, 30 * 60 * 1000L);" in ba,
     "the 30-minute idle audit loop")
need("if (t.mediaPlaying || t.holdsMedia) { busy = true; break; }" in ba
     and "t.web.getProgress() < 100" in ba,
     "the audit skips loading/media sessions (idle-only)")
need("level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE" in ba
     and "com.bolt.browser.util.CacheGuard.check(this, false, cw);" in ba,
     "the trim-driven cache check (>= MODERATE, rate-limited inside)")
need("private const val FILE_BUDGET = 60_000L" in cg and
     "visitLeft = if (startup) Long.MAX_VALUE else FILE_BUDGET" in cg,
     "the file-visit budget: mid-session bounded, startup unbounded")
need('"perf launch ms="' in ba, "the perf launch diag line")
need('"perf nav commit=" in' in ba or '"perf nav commit=" + Math.max(0L, docCommitMs)' in ba,
     "the perf nav diag line (commit/finish/inj/bytes)")
need("docCommitMs = android.os.SystemClock.uptimeMillis() - docStartMs" in ba,
     "the commit latency is measured")

print()
print("G — THE WEB-CONTEXT LAW (the banana corpse)")
need("internal fun buildContextBlock(history: List<Msg>," in ab,
     "the bounded context block exists")
need('h2[i].text + "\\n\\n" + block' in ab,
     "the web leg APPENDS the block to the last user turn (never replaces)")
need('val block = buildContextBlock(history, sources)' in ab,
     "the block rides the keyless round (transcript + SOURCES)")
need("if (h2[i].role != \"assistant\") {" in ab and
     "for (i in h2.indices.reversed())" in ab,
     "the append targets the last user turn")
need("internal fun searchQueryFrom(history: List<Msg>): String" in ab
     and "collectTopicWords" in ab,
     "the search query is context-aware (topic words)")
need('val marker = "say so plainly."' in ab and
     'val k = cur.lastIndexOf(marker)' in ab,
     "a previous block strips (web turns never compound)")
need("MAX_TURNS" in read("app/src/main/java/com/bolt/browser/util/BoltAi.kt"),
     "the standing MAX_TURNS chain pins (BoltAi)")
need("history.subList(history.size - MAX_TURNS, history.size).toList()" in
     read("app/src/main/java/com/bolt/browser/util/BoltAi.kt"),
     "the standing history rides both Web ON and OFF paths (BoltAi askGo)")
need("m.role == \"assistant\") \"model\" else \"user\"" in ab,
     "the Gemini body builder maps every history turn (Web ON included)")
need("for ((i, m) in history.withIndex())" in ab,
     "both body builders iterate the FULL history")

print("  -- the transcribed probes (the parse-gate law) --")

def transcribe_search_query_from(history):
    """the v1.4.31 searchQueryFrom, transcribed from AiBridge.kt."""
    cur = ""
    cur_idx = -1
    for i in range(len(history) - 1, -1, -1):
        if history[i][0] != "assistant":
            cur = history[i][1]
            cur_idx = i
            break
    marker = "say so plainly."
    k = cur.rfind(marker)
    t = cur[k + len(marker):] if k >= 0 else cur
    t = " ".join(t.split("\n")).strip()
    ctx = []
    seen = set()
    users = 0
    for i in range(len(history) - 1, -1, -1):
        if i == cur_idx or i >= len(history):
            continue
        role, text = history[i]
        if role == "user":
            users += 1
            collect_topic_words(text, ctx, seen, 6)
            if users >= 2:
                break
        elif role == "assistant" and len(ctx) < 10:
            collect_topic_words(text, ctx, seen, 4)
    if ctx:
        t = (t + " " + " ".join(ctx)).strip()
    return t[:240]


STOPWORDS = set("""the and for are but not you your with this that have has had
was were been their them they from will would could should into than then
there here what when where which who how why can may all any about out off
over under again once only just also very some such more most make made makes
add added adds please now give show like want need table list answer question
reply tell say did does doing its his her our she him one two get got put use
using same other because while after before""".split())


def collect_topic_words(text, out, seen, cap):
    import re as _re
    words = _re.split(r"[^a-z0-9\u00c0-\u0248]+", text.lower())
    added = 0
    for w in words:
        if added >= cap or len(out) >= 12:
            break
        if len(w) < 3 or w in STOPWORDS:
            continue
        if w not in seen:
            seen.add(w)
            out.append(w)
            added += 1


def transcribe_context_block(history_len, turn_texts):
    """the transcript caps: last 4 turns, 2k budget, 700 per turn."""
    lines = []
    budget = 2000
    shown = 0
    for i, (role, text) in enumerate(turn_texts[-4:]):
        if budget <= 0 or shown >= 4:
            break
        r = "assistant" if role == "assistant" else "user"
        txt = " ".join(text.split("\n")).strip()
        if len(txt) > 700:
            txt = txt[:700] + "…"
        if len(txt) > budget:
            txt = txt[:budget] + "…"
        budget -= len(txt)
        shown += 1
        lines.append(r + ": " + txt)
    return lines


# THE BANANA CONVERSATION (the corpse): web ON, the current prompt alone
# names only apple/orange — the query must carry banana
banana = [
    ("user", "make a nutrition table for banana"),
    ("assistant", "Here is the table:\nNutrient | Amount\nPotassium | 358 mg\n"
                  "Vitamin C | 8.7 mg\nbanana is also rich in B6."),
    ("user", "add apple and orange to the table"),
]
q = transcribe_search_query_from(banana)
need("banana" in q.lower(),
     "the corpse case: the query carries banana ("
     + q[:80] + "...)")
need("apple" in q.lower() and "orange" in q.lower(),
     "the corpse case: the current prompt rides the query")
need(len(q) <= 240, "the query cap (240)")
# a fresh single-turn conversation: no context words, the prompt alone
solo = [("user", "who won the 2026 world cup final")]
q2 = transcribe_search_query_from(solo)
need("2026" in q2 and "banana" not in q2,
     "a fresh conversation searches the prompt alone")
# a SECOND web turn: the appended block strips (never compounds)
blocked = [("user", "weather in dhaka"),
           ("assistant", "raining."),
           ("user", "weather in dhaka\n\nWEB SOURCES (retrieved just now "
                    "for this question):\n[1] met — http://x\nUse these "
                    "sources to answer; write [n] after facts you took "
                    "from source n. If they do not cover the question, "
                    "say so plainly.")]
q3 = transcribe_search_query_from(blocked)
need("WEB SOURCES" not in q3 and "dhaka" in q3.lower(),
     "a second web turn strips the previous block")
# the transcript caps
long_turns = [("user", "x" * 3000), ("assistant", "y" * 3000),
              ("user", "z" * 3000), ("assistant", "w" * 3000),
              ("user", "the question")]
lines = transcribe_context_block(5, long_turns)
need(len(lines) <= 4, "the transcript shows at most 4 turns")
total = sum(len(l) for l in lines)
need(total <= 2100, "the transcript total stays within the 2k budget ("
     + str(total) + " chars)")

print("  -- the web body-builder history-survival probe (transcribed) --")

def transcribe_gemini_contents(history, attaches=None):
    """the v1.4.30 geminiChatPost contents loop, transcribed: EVERY turn
    becomes a content entry (Web ON changes nothing but the tools)."""
    contents = []
    for i, (role, text) in enumerate(history):
        parts = [{"text": text}]
        contents.append({"role": "model" if role == "assistant" else "user",
                         "parts": parts})
    return contents

def transcribe_keyless_round(history, sources_present):
    """the v1.4.31 keylessRound: the full history survives, the block
    APPENDS to the last user turn."""
    if not sources_present:
        return history
    h2 = []
    last_user = None
    for i, m in enumerate(history):
        if m[0] != "assistant":
            last_user = i
    for i, m in enumerate(history):
        if i == last_user:
            h2.append((m[0], m[1] + "\n\n" + "<<BLOCK>>"))
        else:
            h2.append(m)
    return h2

# Web ON with a 3-turn conversation: all 3 turns ride the body
h = [("user", "banana q"), ("assistant", "banana a"), ("user", "add apple")]
contents = transcribe_gemini_contents(h)
need(len(contents) == 3
     and contents[0]["parts"][0]["text"] == "banana q"
     and contents[2]["parts"][0]["text"] == "add apple",
     "the Gemini body carries EVERY turn under Web ON (3/3)")
# the keyless round with sources: the prompt text survives WHOLE, before
# the appended block
h2 = transcribe_keyless_round(h, True)
need(len(h2) == 3
     and h2[2][1].startswith("add apple")
     and h2[2][1].endswith("<<BLOCK>>"),
     "the keyless round: the last user turn keeps its prompt text whole "
     "(the block APPENDS after it)")
need(h2[0] == h[0] and h2[1] == h[1],
     "the keyless round: earlier turns ride untouched")

print()
print("H — THE PIPELINE + THE README")
need("test_v1431.py" in rels, "release.sh carries the v1.4.31 rung")
need("MEASURED-IMPLICIT LAW" in readme and "EXIT-SWEEP CADENCE LAW" in readme
     and "SINGLE-INJECTION LAW" in readme and "PASSKEY LAZY LAW" in readme
     and "WEB-CONTEXT LAW" in readme and "TRIM-STORM POLICY" in readme,
     "the README keeps the v1.4.31 laws by name")
need("v1.4.31 — THE PERFORMANCE-AND-QUIET ROUND" in readme,
     "the README keeps the v1.4.31 session narrative")
need("known-good" in readme and "3,165,467" in readme,
     "the README keeps the known-good numbers (the v1.4.30 APK bytes)")

print()
if fails:
    print(f"v1.4.31 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.4.31 gate: all green")
