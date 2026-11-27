#!/usr/bin/env python3
"""test_v1432.py — the v1.4.32 gate: THE ORIENTATION ROUND.

Three QR fixes off the v1.4.31 field read-backs + a bounded quiet/perf
pack, all probe-pinned:

A  version pins (146 / 1.4.32, the About changelog head, the CHANGELOG
   section)
B  QR — THE BASE-ANGLE LAW: the immutable device base table
   (baseAngleFor) compiled from the REAL Java over all four displays;
   the base/offset separation pin (button writes never touch the base);
   the migration pin (old qrRotOffset.* keys never read); the theta
   formula carries base + userOffset; the qr open diag line + the
   preview line's base= field
C  QR — THE COMPOUND-ANISOTROPY LAW: the identity reset before the
   pattern post AND before getBitmap, the restore after the solve AND on
   the 900ms timeout path; the compiled REAL compoundAnisotropy (the
   v1.4.30 corpse configuration reads 3.784, every self-consistent leg
   1.000); the compiled REAL measuredAcceptable (in-window accept /
   off-window reject); the contamination fixtures — a synthetic raster
   captured under a non-identity start transform is REJECTED (rotation:
   the bijection refuses; scale: the acceptance window refuses) or
   CORRECTED (the known pre-transform divides out to the true implicit)
D  QR — THE CHROMA + DUAL-BINARIZER LADDER: the compiled REAL QrCore
   probes — an equal-luma blue-on-green QR is invisible to EVERY luma leg
   and decodes via chroma (both the ARGB and the live-YUV chroma forms);
   dot-module and wavy-edge fixtures decode through the ladder; the
   dual-binarizer order (Hybrid then Global) is pinned; the runtime-
   budget pin (the chroma leg runs only after the luma ladder fails, the
   live U/V extraction lives inside the failure branch)
E  THE QUIET/PERF PACK: the media core early-returns in non-top frames;
   the bootstrap retry bound (<=10 tries then parked ceremonies reject —
   node probe); the CacheGuard destroy door gated on isFinishing(); the
   finish-path diet (favicon + page-text ride the ~1200ms idle pass,
   cancelled by the next navigation); the trim-driven check rate-limit
   5 minutes
F  the pipeline (release.sh carries the v1.4.32 rung) + the README keeps
   the laws and the open read-backs

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


qsa = read("app/src/main/java/com/bolt/browser/ui/QrScanActivity.java")
ba = read("app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
cg = read("app/src/main/java/com/bolt/browser/util/CacheGuard.kt")
mh = read("app/src/main/java/com/bolt/browser/media/MediaHook.java")
qc = read("app/src/main/java/com/bolt/browser/util/QrCore.java")
gradle = read("app/build.gradle")
strings = read("app/src/main/res/values/strings.xml")
chlog = read("CHANGELOG.md")
rels = read("scripts/release.sh")
readme = read("README.md")
boot_js = read("app/src/main/assets/webauthn_bootstrap.js")

print("A — version pins")
need("versionCode 148" in gradle and 'versionName "1.4.34"' in gradle,
     "build.gradle pins 148 / 1.4.34")
need('<item>1.4.32|' in strings,
     "the About changelog carries the 1.4.32 line at [0] (newest first)")
i32 = strings.find("<item>1.4.32|")
i31 = strings.find("<item>1.4.31|")
need(0 <= i32 < i31, "the About changelog order: 1.4.32 rides ABOVE 1.4.31")
need("## [1.4.32] — 2026-11-26" in chlog and
     chlog.find("## [1.4.32]") < chlog.find("## [1.4.31]"),
     "CHANGELOG.md carries the [1.4.32] section above [1.4.31]")

print()
print("B — QR: THE BASE-ANGLE LAW (orientation must never need a tap)")
# the immutable device base table + the separated user offset
# v1.4.33 re-point (2026-11-27): THE PORTRAIT-ONLY LAW — the scanner
# activity is LOCKED portrait in the manifest; the table keeps ONLY the
# portrait entry (RMX2020 sensor=90 display=0 -> 270) and the v1.4.32
# (270 + display) % 360 family for display 90/180/270 is retired with the
# landscape path (diag display= is always 0 now). test_v1433.py pins the
# portrait table compiled from the REAL Java.
need("static int baseAngleFor" in qsa and
     '"RMX2020".equals(model) && sensor == 90 && display == 0' in qsa,
     "the device base: the PORTRAIT entry RMX2020 (sensor=90, display=0) "
     "-> 270, immutable by any button (the landscape rows retired — v1.4.33)")
need('return "qrUserOffset." + sensorOrientation + "." + degrees;' in qsa,
     "the user offset rides qrUserOffset.<sensor>.<display>")
# MIGRATION PIN: the old conflation is never read again (the string lives
# ONLY inside the dated migration comments)
need("rotOffsetKey" not in qsa and "seedRotOffsetFor" not in qsa,
     "the old qrRotOffset.* key builder + the seed are GONE (migration: "
     "the conflated angle is never read again)")
need(qsa.count("qrRotOffset.") == 2,
     "qrRotOffset. appears ONLY inside the two dated migration comments "
     "(" + str(qsa.count("qrRotOffset.")) + " occurrences, zero live reads)")
# base/offset separation: the button handlers write ONLY the user offset
tap = qsa[qsa.find("rot.setOnClickListener"):qsa.find("topBar.addView(rot,")]
need("userOffset = (userOffset + 90) % 360;" in tap and
     "saveUserOffset();" in tap and "baseAngleFor" not in tap,
     "the rotate tap cycles ONLY the user offset (never the base)")
lp = qsa[qsa.find("rot.setOnLongClickListener"):qsa.find("topBar.addView(rot,")]
need("userOffset = 0;" in lp and '"Preview offset reset"' in lp and
     "baseAngleFor" not in lp,
     "the long-press resets ONLY the user offset to 0 (base untouched)")
need("Prefs.get(this).i(userOffsetKey(degrees), 0)" in qsa,
     "the offset default is 0 — the upright-on-open angle rides the table")
# the theta formula carries base + userOffset (one additive pair)
need("(sensorOrientation - degrees - producerRot + base" in qsa and
     "+ userOffset + 720) % 360" in qsa,
     "theta = (sensor - display - producer + base + userOffset + 720) % 360")
# the diag: qr open line + the preview line's base=
need('"qr open model="' in qsa and '" base=" + baseAngleFor(' in qsa and
     '" offset=" + loadUserOffset(' in qsa and '" mode=" + undoMode' in qsa,
     "the scanner logs qr open model/sensor/display/base/offset/mode at open")
need('" base=" + base +' in qsa,
     "the qr preview diag line carries base=")

print("  -- the compiled REAL base table --")


def extract_method(src, anchor, end_anchor="\n    }"):
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

m_base = extract_method(qsa, '    static int baseAngleFor(String model, int sensor, int display) {')
m_undo = extract_method(qsa, '    static String undoModeFor(String model) {')
m_comp = extract_method(qsa, '    static float compoundAnisotropy(float impX, float impY, float undoX, float undoY) {')
m_acc = extract_method(qsa, '    static boolean measuredAcceptable(float ix, float iy, float priorX, float priorY) {')
need(m_base and m_undo and m_comp and m_acc and javac,
     "the REAL base/undo/compound/acceptance methods are extractable")

if m_base and m_undo and m_comp and m_acc and javac:
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        harness = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    public static void main(String[] args) {
        // ---- the base table over all four displays (field-proven) ----
        // v1.4.33 re-point (2026-11-27): the PORTRAIT-ONLY LAW — the
        // v1.4.32 display-scoped rows (90 -> 0, 180 -> 90, 270 -> 180)
        // are retired with the landscape path; every non-portrait answer
        // is 0 now.
        eq("base display 0", Probe.baseAngleFor("RMX2020", 90, 0) == 270);
        eq("base landscape rows retired",
                Probe.baseAngleFor("RMX2020", 90, 90) == 0
                && Probe.baseAngleFor("RMX2020", 90, 180) == 0
                && Probe.baseAngleFor("RMX2020", 90, 270) == 0);
        eq("base unknown model/sensor",
                Probe.baseAngleFor("Pixel 8", 90, 0) == 0
                && Probe.baseAngleFor(null, 90, 0) == 0
                && Probe.baseAngleFor("RMX2020", 180, 0) == 0);
        // the undo-mode table rides unchanged
        eq("table RMX2020", Probe.undoModeFor("RMX2020").equals("table"));
        eq("table exact-match only",
                Probe.undoModeFor("RMX2020N").equals("fill")
                && Probe.undoModeFor(null).equals("fill"));
        // ---- the COMPOUND anisotropy ----
        // the corpse: fill-undo (bw/vw, bh/vh) composed with the real
        // uniform-COVER implicit k on the v1.4.30 corpse dims
        double corpse = Probe.compoundAnisotropy(
                (float) Math.max(1080.0 / 1920.0, 2298.0 / 1080.0),
                (float) Math.max(1080.0 / 1920.0, 2298.0 / 1080.0),
                (float) (1920.0 / 1080.0), (float) (1080.0 / 2298.0));
        eq("corpse compound 3.784", Math.abs(corpse - 3.784) < 0.01);
        // every self-consistent leg reads 1.000
        double meas = Probe.compoundAnisotropy(2.13f, 2.13f, 1f / 2.13f, 1f / 2.13f);
        eq("measured leg 1.000", Math.abs(meas - 1.0) < 1e-4);
        double tbl = Probe.compoundAnisotropy(2.13f, 2.13f, 1f / 2.13f, 1f / 2.13f);
        eq("table leg 1.000", Math.abs(tbl - 1.0) < 1e-4);
        double fil = Probe.compoundAnisotropy((float) (1080.0 / 1920.0),
                (float) (2298.0 / 1080.0), (float) (1920.0 / 1080.0),
                (float) (1080.0 / 2298.0));
        eq("fill leg 1.000", Math.abs(fil - 1.0) < 1e-4);
        eq("degenerate guarded",
                Probe.compoundAnisotropy(0f, 1f, 1f, 1f) == 1f
                && Probe.compoundAnisotropy(-1f, 1f, 1f, 1f) == 1f);
        // ---- the measured-solve acceptance ----
        float k = Math.max(1080f / 1920f, 2298f / 1080f);
        eq("clean solve accepted (cover device, table prior)",
                Probe.measuredAcceptable(k, k, k, k));
        eq("clean solve accepted (fill device, fill prior)",
                Probe.measuredAcceptable(1080f / 1920f, 2298f / 1080f,
                        1080f / 1920f, 2298f / 1080f));
        eq("anisotropic contamination rejected",
                !Probe.measuredAcceptable(k * 1.9f, k * 0.6f, k, k));
        eq("boundary 1.01 accepted", Probe.measuredAcceptable(k * 1.01f, k, k, k));
        eq("boundary 1.03 rejected", !Probe.measuredAcceptable(k * 1.03f, k, k, k));
        eq("junk rejected",
                !Probe.measuredAcceptable(0f, k, k, k)
                && !Probe.measuredAcceptable(k, k, -1f, k));
        if (fails > 0) { System.out.println("PROBE FAILURES: " + fails); }
        else { System.out.println("all green"); }
    }
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Probe {\n"
                    + "    static final String[] COVER_UNDO_MODELS = "
                    "{ \"RMX2020\" };\n"
                    + m_base + "\n" + m_undo + "\n"
                    + m_comp + "\n" + m_acc + "\n" + harness + "\n}")
        r = subprocess.run([javac, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the base/compound probes compile: " + r.stderr[:300])
        else:
            r2 = subprocess.run(["java", "-cp", d, "Probe"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL base table (4 displays) + the compound anisotropy "
                 "(corpse 3.784, legs 1.000) + the acceptance window "
                 + ("" if r2.returncode == 0 else r2.stdout[:400]))

print()
print("C — THE COMPOUND-ANISOTROPY LAW: the calibration identity")
arm = extract_method(qsa, '    private boolean armCalibration() {')
fin = extract_method(qsa, '    private boolean finishCalibration() {')
need(arm and fin, "the calibration methods are extractable")
if arm:
    need("setTransform(new Matrix())" in arm and
         arm.find("setTransform(new Matrix())") < arm.find("lockCanvas"),
         "the view transform resets to IDENTITY before the pattern posts")
need(fin and "setTransform(new Matrix())" in fin and
     fin.find("setTransform(new Matrix())") < fin.find("getBitmap()"),
     "identity again right before the bitmap read")
# the restore: the ok path re-applies + the timeout path re-applies
ost = extract_method(qsa, "@Override public void onSurfaceTextureUpdated(SurfaceTexture st) {")
if ost is None:
    ost = extract_method(qsa, "public void onSurfaceTextureUpdated(SurfaceTexture st) {")
need(ost and ost.find("finishCalibration()") >= 0 and
     ost.find("reapplyTransform()") > ost.find("finishCalibration()") and
     ost.find("reapplyTransform()") < ost.find("startRepeating()"),
     "the ok path restores the REAL transform immediately after the solve")
timeout = arm[arm.find("postDelayed"):] if arm else ""
need(timeout and "reapplyTransform();" in timeout,
     "the 900ms timeout path restores the REAL transform too")
# the acceptance gate lives in finishCalibration
# v1.4.34 re-point (2026-11-27): THE DUAL-PRIOR VERDICT — the single-prior
# call (measuredAcceptable(sx[0], sy[0], priorX, priorY)) became
# measuredVerdict(sx[0], sy[0], fillX, fillY, coverK, coverK): the solve
# is accepted when it sits in-window against EITHER candidate implicit
# model (the honest cover-implicit solve on a non-table device no longer
# reads "off-window" against the fill prior and dies into the fill undo —
# the 3.784x corpse class); a contaminated capture still reads off-window
# against EVERY model and falls down the chain (measured -> table -> fill).
# measuredAcceptable itself is UNCHANGED (the window law, compiled probes
# below still pin it).
need(fin and "measuredVerdict(sx[0], sy[0]," in fin
     and "undoMode = chainMode;" in fin,
     "the solve is accepted ONLY through the dual-prior verdict — else it "
     "falls down the chain (measured -> table -> fill)")
# v1.4.34 re-point (2026-11-27): the chain prior implicit rides in the
# DUAL-PRIOR form — both candidate models compose on the ACTIVE (scene)
# dims via awPrior()/ahPrior() (the fill scales AND the uniform-cover k);
# the rejection still falls to chainMode (the buffer's stride padding is
# never scene — the v1.4.33 active-array law rides in both priors).
need("awPrior()" in fin and "ahPrior()" in fin
     and "Math.max(vw / (float) awPrior()," in fin,
     "the dual prior implicit: fill = anisotropic scales, cover = uniform "
     "k — both on the ACTIVE dims (v1.4.34 re-point)")
# the contamination fixture: a synthetic raster captured under a
# non-identity start transform — the solve must reject or correct
if m_base and javac and node is not None:
    pass  # the fixture probe rides the compiled QrCore probe in D below
if javac:
    m_scan = extract_method(qsa, '    static int[] scanRunsArgb(int[] argb, int w, int h, boolean alongX, int test) {')
    m_solve = extract_method(qsa, '    static float[] solveAxis(int[] runs, int span, int viewLen) {')
    m_cnt = extract_method(qsa, '    static int stripeCount() {')
    m_pos = extract_method(qsa, '    static int stripePos(int span, int j) {')
    m_acc2 = m_acc
    if m_scan and m_solve and m_cnt and m_pos and m_acc2:
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "Contam.java")
            fx = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    // the stripe raster under the mapping view = i*buf + t per axis
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
    // one calibration leg exactly like finishCalibration
    static float[] solveX(int[] px, int vw, int vh, int bw) {
        int[] rows = {7 * vh / 16, 9 * vh / 16, 8 * vh / 16};
        for (int ry : rows) {
            if (ry < 0 || ry >= vh) continue;
            int[] row = new int[vw];
            System.arraycopy(px, ry * vw, row, 0, vw);
            int[] r = Contam.scanRunsArgb(row, vw, 1, true, 1);
            if (r != null && r.length >= 2) return Contam.solveAxis(r, bw, vw);
        }
        return null;
    }
    static float[] solveY(int[] px, int vw, int vh, int bh) {
        int[] cols = {7 * vw / 16, 9 * vw / 16, 8 * vw / 16};
        for (int cx : cols) {
            if (cx < 0 || cx >= vw) continue;
            int[] col = new int[vh];
            for (int y = 0; y < vh; y++) col[y] = px[y * vw + cx];
            int[] g = Contam.scanRunsArgb(col, 1, vh, false, 2);
            if (g != null && g.length >= 2) return Contam.solveAxis(g, bh, vh);
        }
        return null;
    }
    public static void main(String[] args) {
        int vw = 1080, vh = 2298, bw = 1920, bh = 1080;
        // the TRUE implicit: a FILL device (the historical default the
        // chain falls to): view = (vw/bw, vh/bh) * buf, t = 0
        double ix = (double) vw / bw, iy = (double) vh / bh;
        double tx = 0.0, ty = 0.0;
        float priorX = (float) ix, priorY = (float) iy;
        // clean solve (identity capture): solves the true implicit and
        // the acceptance window ACCEPTS it against the fill prior
        float[] sx = solveX(raster(vw, vh, ix, tx, iy, ty, bw, bh), vw, vh, bw);
        float[] sy = solveY(raster(vw, vh, ix, tx, iy, ty, bw, bh), vw, vh, bh);
        eq("clean fill solve", sx != null && sy != null
                && Math.abs(sx[0] - ix) < 0.02 && Math.abs(sy[0] - iy) < 0.02);
        eq("clean solve ACCEPTED by the window",
                sx != null && sy != null
                && Contam.measuredAcceptable(sx[0], sy[0], priorX, priorY));
        // CONTAMINATION FIXTURE A — an anisotropic SCALE start transform
        // (a stale A_current left over the capture: the bitmap is
        // implicit∘A — affine per axis, so the bijection PASSES but the
        // solved undo is wrong by A; the acceptance window must REJECT it)
        double ax = 1.3, ay = 0.9;
        double cx2 = ix * ax, cy2 = iy * ay;
        double ctx = (vw - cx2 * bw) / 2.0, cty = (vh - cy2 * bh) / 2.0;
        float[] csx = solveX(raster(vw, vh, cx2, ctx, cy2, cty, bw, bh), vw, vh, bw);
        float[] csy = solveY(raster(vw, vh, cx2, ctx, cy2, cty, bw, bh), vw, vh, bh);
        eq("contaminated (scale) solve still passes the bijection",
                csx != null && csy != null);
        eq("contaminated (scale) solve REJECTED by the acceptance window",
                csx != null && csy != null
                && !Contam.measuredAcceptable(csx[0], csy[0], priorX, priorY));
        // CORRECTION leg: with the start transform KNOWN, dividing it out
        // recovers the true implicit exactly (and the window accepts it)
        if (csx != null && csy != null) {
            double rx = csx[0] / ax, ry = csy[0] / ay;
            eq("contaminated solve CORRECTED when the start transform is known",
                    Math.abs(rx - ix) < 0.02 && Math.abs(ry - iy) < 0.02
                    && Contam.measuredAcceptable((float) rx, (float) ry,
                            priorX, priorY));
        }
        // CONTAMINATION FIXTURE B — a quarter-turn start transform: the
        // stripes swap orientation, the per-axis scans find nothing —
        // the bijection REJECTS (a table-device matrix at theta=90).
        int[] rot = new int[vw * vh];
        int[] srcPx = raster(vw, vh, ix, tx, iy, ty, bw, bh);
        for (int y = 0; y < vh; y++) for (int x = 0; x < vw; x++) {
            int sxx = vh - 1 - y, syy = x;   // 90 deg rotation (cropped)
            if (sxx < vw && syy < vh) rot[y * vw + x] = srcPx[syy * vw + sxx];
        }
        float[] rsx = solveX(rot, vw, vh, bw);
        float[] rsy = solveY(rot, vw, vh, bh);
        eq("contaminated (rotation) capture REJECTED (solve refuses)",
                rsx == null && rsy == null);
        // the cover-device geometry too: the clean cover solve solves the
        // uniform k and the acceptance against the TABLE prior accepts it
        float k = Math.max((float) vw / bw, (float) vh / bh);
        double kx = k, ky = k;
        double ktx = (vw - kx * bw) / 2.0, kty = (vh - ky * bh) / 2.0;
        float[] ksx = solveX(raster(vw, vh, kx, ktx, ky, kty, bw, bh), vw, vh, bw);
        float[] ksy = solveY(raster(vw, vh, kx, ktx, ky, kty, bw, bh), vw, vh, bh);
        eq("clean cover solve (table prior)", ksx != null && ksy != null
                && Math.abs(ksx[0] - k) < 0.02 && Math.abs(ksy[0] - k) < 0.02
                && Contam.measuredAcceptable(ksx[0], ksy[0], k, k));
        if (fails > 0) { System.out.println("PROBE FAILURES: " + fails); }
        else { System.out.println("all green"); }
    }
"""
            with open(src, "w", encoding="utf-8") as f:
                f.write("public class Contam {\n" + m_scan + "\n" + m_solve + "\n"
                        + m_cnt + "\n" + m_pos + "\n" + m_acc2 + "\n" + fx + "\n}")
            r = subprocess.run([javac, src], capture_output=True, text=True)
            if r.returncode != 0:
                need(False, "the contamination fixture compiles: " + r.stderr[:300])
            else:
                r2 = subprocess.run(["java", "-cp", d, "Contam"],
                                    capture_output=True, text=True)
                need(r2.returncode == 0 and "all green" in r2.stdout,
                     "the contamination fixtures: scale contamination REJECTED "
                     "by the window, rotation contamination REJECTED by the "
                     "bijection, the known start transform CORRECTS to the "
                     "true implicit"
                     + ("" if r2.returncode == 0 else r2.stdout[:400]))
        # the compound net= diag rides the REAL compoundAnisotropy
        # v1.4.33 re-point (2026-11-27): THE HONEST NET — the v1.4.32 net=
        # composed each mode with its OWN undo (a tautology: the table and
        # fill legs read 1.000 by construction, and the padding corpse rode
        # in invisible at mode=table net=1.000). net= now appears ONLY on a
        # measured solve (compound(measured implicit, the APPLIED undo incl.
        # the padding compensation)); every other mode emits agree=unknown.
        need('compoundAnisotropy(calibIx, calibIy,\n                            undoSx * padFactor(bufW, activeW),\n                            undoSy * padFactor(bufH, activeH))' in qsa
             and '"agree=unknown"' in qsa,
             "net= became honest: measured -> the compound of the measured "
             "implicit vs the APPLIED undo (pad included); otherwise "
             "agree=unknown (never a fake 1.000)")

print()
print("D — QR: THE CHROMA + DUAL-BINARIZER LADDER (stylized codes)")
need("public static byte[] chromaOf(byte[] u, byte[] v, int cw, int ch)" in qc,
     "chromaOf builds the |U-128|+|V-128| plane at half resolution")
need("public static String decodeChroma(byte[] chroma, int cw, int ch)" in qc,
     "decodeChroma is the ladder's FINAL chroma leg (plain+invert, "
     "dual-binarizer core)")
need("public static byte[] chromaOfArgb(int[] px, int width, int height)" in qc,
     "chromaOfArgb derives the spread from the RGB triple (the gallery leg)")
# the dual-binarizer ORDER (Hybrid then Global, every leg)
need("import com.google.zxing.common.GlobalHistogramBinarizer;" in qc,
     "GlobalHistogramBinarizer is in the core")
dual = extract_method(qc, '    private static String decodeDual(LuminanceSource src) {')
need(dual and dual.find("new HybridBinarizer(src)") >= 0 and
     dual.find("new GlobalHistogramBinarizer(src)") > dual.find("new HybridBinarizer(src)") and
     dual.find("new GlobalHistogramBinarizer(src.invert())") > dual.find("new HybridBinarizer(src.invert())"),
     "every leg tries HybridBinarizer THEN GlobalHistogramBinarizer "
     "(plain and inverted)")
need("decodeLuma2" in qc and "return decodeDual(src);" in qc,
     "the luma core rides the dual-binarizer core")
# the budget order: chroma AFTER the luma ladder in decodeMulti
mm = extract_method(qc, '    public static String decodeMulti(int[] pixels, int width, int height) {')
need(mm and mm.find("decodeLuma(") < mm.find("chromaOfArgb("),
     "the gallery chroma leg runs ONLY after the luma ladder fails "
     "(the budget order)")
# the live path: U/V extracted ONLY inside the failure branch
live = qsa[qsa.find("String text = QrCore.decodeLuma(y, im.getWidth(), im.getHeight());"):]
fail_branch = live[:live.find("if (text != null && !text.trim().isEmpty())")]
need('if (text == null || text.trim().isEmpty()) {' in fail_branch and
     "destridePlane(pu, uBuf, cw, ch);" in fail_branch and
     "QrCore.chromaOf(uBuf, vBuf, cw, ch);" in fail_branch and
     "QrCore.decodeChroma(chroma, cw, ch);" in fail_branch,
     "the live U/V extraction + the chroma decode live ONLY inside the "
     "failure branch (the 4 fps budget law)")
need("private static byte[] uBuf = null, vBuf = null;" in qsa and
     "private static void destridePlane(Image.Plane p, byte[] out, int w, int h)" in qsa,
     "the tight half-res U/V planes persist across frames (the plane-reuse law)")
# the compiled REAL QrCore probes (the zxing jar rides the gradle cache)
if javac:
    jar = None
    for p in glob.glob("/home/z/my-project/.gradle-home/caches/modules-2/files-2.1/"
                       "com.google.zxing/core/*/*/core-*.jar"):
        jar = p
    if jar is None:
        # aliyun fallback (the test_qr convention)
        jar = os.path.join(tempfile.gettempdir(), "zxing-core-gate.jar")
        r = subprocess.run(["curl", "-sL", "--retry", "2", "-o", jar,
                            "https://maven.aliyun.com/repository/public/"
                            "com/google/zxing/core/3.5.3/core-3.5.3.jar"],
                           capture_output=True, text=True)
        with open(jar, "rb") as f:
            if f.read(2) != b"PK":
                jar = None
    need(jar, "the ZXing core jar is available (gradle cache or aliyun)")
    if jar:
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "QrCore.java")
            with open(src, "w", encoding="utf-8") as f:
                f.write(qc.replace("package com.bolt.browser.util;", ""))
            r = subprocess.run([javac, "-cp", jar, src], capture_output=True, text=True)
            need(r.returncode == 0, "QrCore compiles standalone: " +
                 ("" if r.returncode == 0 else r.stderr[:300]))
            if r.returncode == 0:
                src2 = os.path.join(d, "Ladder.java")
                probe = r'''
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import java.util.*;

public class Ladder {
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
    public static void main(String[] args) throws Exception {
        final String TEXT = "https://chess.example/game/12345";
        int n = 336;
        int[] plain = QrCore.encode(TEXT, n);
        int minX = n, minY = n, maxX = 0, maxY = 0;
        for (int yy = 0; yy < n; yy++) for (int xx = 0; xx < n; xx++)
            if ((plain[yy*n+xx]&0xFFFFFF) == 0) { minX=Math.min(minX,xx); maxX=Math.max(maxX,xx);
                minY=Math.min(minY,yy); maxY=Math.max(maxY,yy); }
        int run7 = 0;
        for (int xx = minX; xx < n && (plain[minY*n+xx]&0xFFFFFF) == 0; xx++) run7++;
        int mod = run7 / 7, m = (maxX-minX)/mod + 1;
        boolean[][] B = new boolean[m][m];
        for (int r = 0; r < m; r++) for (int c = 0; c < m; c++)
            B[r][c] = (plain[(minY+r*mod+mod/2)*n + minX+c*mod+mod/2]&0xFFFFFF) == 0;
        boolean[][] solid = new boolean[m][m];
        for (int r = 0; r < m; r++) for (int c = 0; c < m; c++)
            solid[r][c] = (r<8&&c<8)||(r<8&&c>=m-8)||(r>=m-8&&c<8)||r==6||c==6
                    ||(r>=m-9&&c>=m-9);
        int S = 10, Q = 4*S, W = (m+8)*S;

        // ---- 1. THE EQUAL-LUMA COLOUR CORPSE: blue modules on a green
        // field (luma 29 vs 29 — invisible to EVERY luma leg), white quiet
        int[] col = new int[W*W];
        java.util.Arrays.fill(col, 0xFFFFFFFF);
        for (int r = 0; r < m; r++) for (int c = 0; c < m; c++) {
            int x0 = Q + c*S, y0 = Q + r*S;
            int v = B[r][c] ? 0xFF0000FF : 0xFF003200;
            for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++)
                col[(y0+dy)*W + x0+dx] = v;
        }
        byte[] cl = QrCore.lumaOf(col, W, W);
        eq("colour corpse: hybrid leg misses", hyb(cl, W, W) == null);
        eq("colour corpse: hybrid invert misses", hybInv(cl, W, W) == null);
        eq("colour corpse: global leg misses", glob(cl, W, W) == null);
        eq("colour corpse: global invert misses", globInv(cl, W, W) == null);
        eq("colour corpse: the FULL luma ladder misses",
            QrCore.decodeLuma(cl, W, W) == null);
        byte[] ch = QrCore.chromaOfArgb(col, W, W);
        int cw = (W+1)/2;
        eq("colour corpse: the ARGB chroma leg DECODES",
            TEXT.equals(QrCore.decodeChroma(ch, cw, cw)));
        // the live-YUV chroma form: synth U/V planes
        int chh = (W+1)/2;
        byte[] u = new byte[chh*chh], v = new byte[chh*chh];
        for (int r = 0; r < chh; r++) for (int c = 0; c < chh; c++) {
            int sr = Math.min(2*r, W-1), sc = Math.min(2*c, W-1);
            int p = col[sr*W + sc];
            boolean blue = (p & 0xFF) == 0xFF && ((p >> 8) & 0xFF) == 0x00;
            boolean white = (p & 0xFFFFFF) == 0xFFFFFF;
            u[r*chh+c] = (byte) (blue ? (byte) 255 : white ? (byte) 128 : (byte) 111);
            v[r*chh+c] = (byte) (blue ? (byte) 107 : (byte) 128);
        }
        byte[] clive = QrCore.chromaOf(u, v, chh, chh);
        eq("colour corpse: the live-YUV chroma leg DECODES",
            TEXT.equals(QrCore.decodeChroma(clive, chh, chh)));
        eq("chromaOf output dims are half-res", cw == chh);

        // ---- 2. DOT fixture (rounded/dot data modules, solid function
        // patterns) decodes through the ladder
        int[] dot = new int[W*W];
        java.util.Arrays.fill(dot, 0xFFFFFFFF);
        for (int r = 0; r < m; r++) for (int c = 0; c < m; c++) {
            int x0 = Q + c*S, y0 = Q + r*S;
            if (solid[r][c]) {
                for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++)
                    dot[(y0+dy)*W + x0+dx] = B[r][c] ? 0xFF000000 : 0xFFFFFFFF;
                continue;
            }
            if (!B[r][c]) continue;
            int rad = (int) (S * 0.40);
            for (int dy = -rad; dy <= rad; dy++) for (int dx = -rad; dx <= rad; dx++)
                if (dx*dx + dy*dy <= rad*rad) dot[(y0+S/2+dy)*W + x0+S/2+dx] = 0xFF000000;
        }
        byte[] dl = QrCore.lumaOf(dot, W, W);
        eq("dot fixture decodes through the ladder",
            TEXT.equals(QrCore.decodeLuma(dl, W, W)));

        // ---- 3. WAVY fixture (data modules displaced on a sine) decodes
        int[] wav = new int[W*W];
        java.util.Arrays.fill(wav, 0xFFFFFFFF);
        for (int r = 0; r < m; r++) for (int c = 0; c < m; c++) {
            int x0 = Q + c*S, y0 = Q + r*S;
            if (solid[r][c]) {
                for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++)
                    wav[(y0+dy)*W + x0+dx] = B[r][c] ? 0xFF000000 : 0xFFFFFFFF;
                continue;
            }
            if (!B[r][c]) continue;
            int ox = (int) Math.round((S/4.0) * Math.sin(r * 0.9));
            int oy = (int) Math.round((S/4.0) * Math.cos(c * 0.9));
            for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++) {
                int x = x0 + dx + ox, y = y0 + dy + oy;
                if (x>=0 && x<W && y>=0 && y<W) wav[y*W+x] = 0xFF000000;
            }
        }
        byte[] wl = QrCore.lumaOf(wav, W, W);
        eq("wavy fixture decodes through the ladder",
            TEXT.equals(QrCore.decodeLuma(wl, W, W)));
        byte[] wh = QrCore.halfLuma(wl, W, W);
        eq("the half-scale leg still decodes the wavy fixture",
            TEXT.equals(QrCore.decodeLuma(wh, W/2, W/2)));

        // ---- 4. PLAIN regression: the common path is untouched
        byte[] pl = QrCore.lumaOf(plain, n, n);
        eq("plain QR still decodes", TEXT.equals(QrCore.decodeLuma(pl, n, n)));
        eq("plain QR still decodes (ARGB)", TEXT.equals(QrCore.decode(plain, n, n)));

        // ---- 5. the gallery multi-pass rides chroma
        eq("decodeMulti decodes the colour corpse",
            TEXT.equals(QrCore.decodeMulti(col, W, W)));
        eq("decodeMulti still decodes plain", TEXT.equals(QrCore.decodeMulti(plain, n, n)));

        if (fails > 0) { System.out.println("PROBE FAILURES: " + fails); System.exit(1); }
        System.out.println("all green");
    }
}
'''
                with open(src2, "w", encoding="utf-8") as f:
                    f.write(probe)
                r2 = subprocess.run([javac, "-cp", jar + ":" + d, src2],
                                    capture_output=True, text=True)
                if r2.returncode != 0:
                    need(False, "the ladder probe compiles: " + r2.stderr[:400])
                else:
                    r3 = subprocess.run(["java", "-cp", jar + ":" + d, "Ladder"],
                                        capture_output=True, text=True)
                    need(r3.returncode == 0 and "all green" in r3.stdout,
                         "the compiled REAL ladder: the equal-luma colour QR "
                         "decodes ONLY via chroma (every luma leg misses; the "
                         "ARGB + live-YUV chroma forms hit), dot + wavy "
                         "fixtures decode, plain QRs unchanged, decodeMulti "
                         "carries chroma"
                         + ("" if r3.returncode == 0 else r3.stdout[:500]))

print()
print("E — THE QUIET/PERF PACK (four small cuts)")
# (a) MediaHook main-frame-only
core_src = mh[mh.find("public static String core(Prefs p)"):mh.find("public static String cssInjectOnce")]
need('if(window!==window.top)return;' in core_src and
     # v1.4.33 re-point (2026-11-27): the boot mark grew the LAW name —
     # window.__boltCoreArmed (the repeated-injection guard's
     # once-per-document flag; __boltCore rides for the standing refs)
     core_src.find('if(window!==window.top)return;') < core_src.find('if(window.__boltCoreArmed'),
     "the media core early-returns in any non-top frame (before the boot "
     "mark — ad iframes never own the notification/wake-lock)")
# (b) the bootstrap retry bound
need("tries>=10" in boot_js and "dead=true" in boot_js and
     "q.pop().reject(rb())" in boot_js and "if(dead){return Promise.reject(rb())}" in boot_js,
     "the bootstrap req() loop is bounded (<=10 tries, then the parked "
     "ceremonies reject; a dead bridge rejects immediately)")
need(len(boot_js.encode("utf-8")) <= 3072,
     "the bootstrap asset is <=3,072 bytes (" + str(len(boot_js.encode('utf-8'))) + " B)")
need("__BOLT_WEBAUTHN_PENDING" in boot_js and "passkeyLoad" in boot_js
     and "parseCreationOptionsFromJSON" in boot_js,
     "the bootstrap surface rides unchanged (queue + lazy door + parse pair)")
if node:
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "boot.js")
        with open(src, "w", encoding="utf-8") as f:
            f.write(boot_js)
        r = subprocess.run([node, "--check", src], capture_output=True, text=True)
        need(r.returncode == 0, "node --check: webauthn_bootstrap.js")
if node:
    # the retry-bound node probe: a bridge-free world (no window.Bolt) —
    # the shortest-timer-first runner proves the bound rejects the parked
    # ceremony BEFORE the 20s timeout would fire
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "retry_probe.js")
        boot_copy = os.path.join(d, "boot.js")
        with open(boot_copy, "w", encoding="utf-8") as f:
            f.write(boot_js)
        probe = r"""
// the retry-bound probe: run the bootstrap in a mock world with NO Bolt
// bridge (incognito / a bridge-free popup child); drive the scheduler
// shortest-timer-first (the req timers are 300ms, the ceremony timeout
// 20000ms) — the parked ceremony must reject with bridge-gone at the
// 10th req attempt, BEFORE the 20s timer ever fires.
async function main() {
function MockDOMException(msg, name) { this.message = msg || 'NotAllowedError'; this.name = name || 'NotAllowedError'; }
const win = {};
const nav = { credentials: {} };
const fs = require('fs');
const boot = fs.readFileSync(process.argv[2], 'utf8');
const timers = [];
const setTimeout = (fn, ms) => { timers.push({ fn, ms }); return timers.length; };
(function () {
  const window = new Proxy(win, { get: (t, k) => t[k], set: (t, k, v) => { t[k] = v; return true; } });
  const navigator = nav;
  const atob = (s) => Buffer.from(s, 'base64').toString('binary');
  const DOMException = MockDOMException;
  eval(boot);
  globalThis.__w = window; globalThis.__n = navigator;
})();
const W = globalThis.__w, N = globalThis.__n;
let misses = 0;
function need(c, w) { if (!c) { console.log('MISS: ' + w); misses++; } }
need(W.__BOLT_WEBAUTHN_BOOT__, 'the boot mark');
need(typeof N.credentials.get === 'function', 'credentials.get parked');
let settled = null;
const p = N.credentials.get({ challenge: 'YWJj' });
p.then(() => { settled = 'resolved'; }, (e) => { settled = e; });
need(W.__BOLT_WEBAUTHN_PENDING && W.__BOLT_WEBAUTHN_PENDING.length === 1,
     'the ceremony parked in the queue');
// shortest-timer-first scheduler (await between ticks flushes the
// promise microtasks so the reject handler lands)
let fired20k = 0, fired300 = 0, guard = 0;
while (settled === null && guard++ < 200 && timers.length) {
  timers.sort((a, b) => a.ms - b.ms);
  const t = timers.shift();
  if (t.ms === 20000) fired20k++;
  if (t.ms === 300) fired300++;
  t.fn();
  await null;
}
need(settled !== null, 'the ceremony settled');
need(settled && settled.name === 'NotAllowedError', 'rejected NotAllowedError');
need(settled && settled.message === 'bridge-gone',
     'rejected by the RETRY BOUND (bridge-gone), not the 20s timeout');
need(fired20k === 0, 'the 20s timeout never fired');
need(fired300 >= 9 && fired300 <= 10,
     'the req loop stopped at the bound (10 invocations = the direct ask + '
     + fired300 + ' scheduled legs)');
// a live bridge world still parks and loads exactly once
const win2 = {};
const nav2 = { credentials: {} };
let loads = 0;
const timers2 = [];
const setTimeout2 = (fn, ms) => { timers2.push({ fn, ms }); return timers2.length; };
(function () {
  const window = new Proxy(win2, { get: (t, k) => t[k], set: (t, k, v) => { t[k] = v; return true; } });
  const navigator = nav2;
  const atob = (s) => Buffer.from(s, 'base64').toString('binary');
  const DOMException = MockDOMException;
  const setTimeout = setTimeout2;
  eval(boot);
  globalThis.__w2 = window; globalThis.__n2 = navigator;
})();
const W2 = globalThis.__w2, N2 = globalThis.__n2;
// the bridge rides the GLOBAL scope on device (a bare `Bolt` resolves
// through it) — the mock mirrors that
globalThis.Bolt = { passkeyLoad: () => { loads++; } };
W2.Bolt = globalThis.Bolt;
const p2 = N2.credentials.get({ challenge: 'YWJj' });
await null;
need(loads === 1, 'a live bridge calls passkeyLoad exactly once (the lazy door)');
need(win2.__BOLT_WEBAUTHN_PENDING.length === 1, 'the ceremony stays parked for adoption');
if (misses > 0) { console.log('PROBE FAILURES: ' + misses); process.exit(1); }
console.log('all green');
}
main().catch((e) => { console.log('PROBE ERROR: ' + e); process.exit(1); });
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write(probe)
        r = subprocess.run([node, src, boot_copy],
                           capture_output=True, text=True)
        need(r.returncode == 0 and "all green" in r.stdout,
             "the node retry-bound probe: a bridge-free world rejects the "
             "parked ceremony at the 10th try (bridge-gone, the 20s timer "
             "never fires); a live bridge calls passkeyLoad exactly once"
             + ("" if r.returncode == 0 else r.stdout[:400] + r.stderr[:200]))
else:
    need(False, "node present for the bootstrap probes")
# (c) the destroy door gated on isFinishing
need("if (isFinishing()) {\n            try { com.bolt.browser.util.CacheGuard.exit(this, \"destroy\");"
     in ba,
     "the exit door=destroy runs ONLY on a true close (isFinishing) — a "
     "skin/locale recreate never sweeps")
# (d) the finish-path diet
need("private Runnable finishIdlePass;" in ba and
     "ui.postDelayed(finishIdlePass, 1200L);" in ba and
     "cancelFinishIdlePass();" in ba,
     "the favicon + page-text evaluates ride the ~1200ms idle pass")
pfs = ba[ba.find("public void onPageStarted"):]
pfs = pfs[:pfs.find("\n        }\n")]
need("cancelFinishIdlePass();" in pfs,
     "the idle pass is cancelled by the next navigation (onPageStarted)")
need('view.evaluateJavascript(FAV_EXTRACT_JS, null);' not in pfs,
     "onPageStarted carries no favicon evaluate (the diet)")
pff = ba[ba.find("public void onPageFinished"):]
pff = pff[:pff.find("public void onPageCommitVisible")]
first_fav = pff.find("evaluateJavascript(FAV_EXTRACT_JS")
first_pass = pff.find("finishIdlePass = () ->")
need(first_fav > first_pass and first_fav >= 0,
     "the favicon evaluate lives INSIDE the idle pass (never on the "
     "synchronous finish path)")
# (d2) the trim-check rate limit
need("private const val MIN_INTERVAL_MS = 5 * 60 * 1000L" in cg,
     "the trim-driven CacheGuard check re-measures at most once per 5 minutes")

print()
print("F — THE PIPELINE + THE README")
need("python3 scripts/test_v1432.py" in rels and
     rels.find("python3 scripts/test_v1432.py") > rels.find("python3 scripts/test_v1431.py"),
     "release.sh carries the v1.4.32 rung (after the v1.4.31 rung)")
need("THE BASE-ANGLE LAW" in readme and "THE COMPOUND-ANISOTROPY LAW" in readme
     and "THE CHROMA LADDER" in readme and "THE QUIET PACK" in readme,
     "the README names the v1.4.32 laws")
need("twenty-fifth session" in readme,
     "the README carries the v1.4.32 session narrative")
# v1.4.33 re-point (2026-11-27): the README's open-read-back list is
# ALWAYS CURRENT — the v1.4.33 list replaced it, carrying the still-open
# v1.4.32 legs (the tap-then-long-press return home; the calib line) as
# carried entries. The v1.4.33 read-backs are pinned in test_v1433.py.
need("(carried, v1.4.32) The tap-then-long-press return home" in readme
     and "The portrait-lock read-back (v1.4.33)" in readme,
     "the README carries the open field read-backs (the v1.4.32 legs "
     "ride as carried entries under the v1.4.33 list)")

print()
if fails:
    print("v1.4.32 gate: " + str(len(fails)) + " FAILURE(S)")
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("v1.4.32 gate: all green")
