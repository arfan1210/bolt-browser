#!/usr/bin/env python3
"""test_v1433.py — the v1.4.33 gate: THE FIELD ROUND.

Seven fixes off the v1.4.32 field diagnostics, no feature work, all
probe-pinned:

A  version pins (147 / 1.4.33, the About changelog head, the CHANGELOG
   section)
B  QR — THE PORTRAIT-ONLY LAW: the manifest pin (screenOrientation),
   the compiled REAL base table (the portrait entry; the landscape rows
   GONE — a dated re-point of the v1.4.32 four-display pins), the offset
   key stays portrait-only
C  QR — THE ACTIVE-ARRAY PADDING LAW + the honest net=: the
   SENSOR_INFO_ACTIVE_ARRAY_SIZE read, the postScale(buf/active)
   compensation after the undo ops, ACTIVE dims for uw/uh/cover/the
   chain prior, the compiled REAL padFactor + the 1088/1080 compound
   fixture (1.007 uncompensated, 1.000 compensated), the honest
   agree=unknown pin
D  QR — THE DOT-CLOSING LEG: the compiled REAL QrCore probe — a 50%-duty
   dot-module QR (solid finders, centre logo) MISSES the pre-1.4.33
   ladder (plain/invert dual + stretch + half + chroma) and HITS the
   closing leg (the honest pin); the degenerate-chroma skip (max spread
   < 8); plain QRs unchanged; the budget order pins (closing after the
   luma ladder, the live dilate-only half-res leg)
E  POPUPS — THE CHOOSER-IS-NOT-CONSENT LAW: the compiled REAL
   popupDestination decision table (chooser/AddSession -> newtab, OAuth
   shape + IdP consent paths -> overlay, the pref override), the
   migration pin (stored old default removed, overrides survive), the
   belt-and-braces gate
F  SCROLL — THE BOTTOM-FLASH KILL: OVER_SCROLL_NEVER +
   setOffscreenPreRaster (API 26+) on every tab WebView, the compiled
   REAL backdropState state machine (stale -> solid, fresh -> glass),
   the stale-mark/clear wiring, the ~120ms post-idle cadence, the
   Robolectric scroll-state probe on disk
G  CACHE DOORS + TRIM POLICY: the door/deep matrix source pins (delayed
   -> deep=0 always, the diag says deep=0), the 10-minute cadence, the
   compiled REAL trimSleepDecision table (v1.4.31 rows re-pointed: rank
   0-3 exempt, idle > 10 min — dated note)
H  PERF — THE REPEATED-INJECTION GUARD: the per-hook fallback diag
   format pins (name=<core|fh|shr> armed= feature=), the
   __boltCoreArmed/__boltFhArmed/__boltShrArmed guard pins, the
   compiled REAL needsFallbackEval, the finish-path pin (the
   unconditional bolt-adcss evaluate is GONE, the commit-marker backstop
   stays)
I  the pipeline (release.sh carries the v1.4.33 rung) + the README keeps
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
tb = read("app/src/main/java/com/bolt/browser/core/Tab.java")
cg = read("app/src/main/java/com/bolt/browser/util/CacheGuard.kt")
mh = read("app/src/main/java/com/bolt/browser/media/MediaHook.java")
fhk = read("app/src/main/java/com/bolt/browser/util/FrameHooks.kt")
sgk = read("app/src/main/java/com/bolt/browser/util/ShadowGate.kt")
qc = read("app/src/main/java/com/bolt/browser/util/QrCore.java")
fx = read("app/src/main/java/com/bolt/browser/util/Fx.java")
pf = read("app/src/main/java/com/bolt/browser/util/Prefs.java")
gradle = read("app/build.gradle")
strings = read("app/src/main/res/values/strings.xml")
chlog = read("CHANGELOG.md")
rels = read("scripts/release.sh")
readme = read("README.md")
manifest = read("app/src/main/AndroidManifest.xml")

print("A — version pins")
need("versionCode 147" in gradle and 'versionName "1.4.33"' in gradle,
     "build.gradle pins 147 / 1.4.33")
need('<item>1.4.33|' in strings,
     "the About changelog carries the 1.4.33 line at [0] (newest first)")
i33 = strings.find("<item>1.4.33|")
i32 = strings.find("<item>1.4.32|")
need(0 <= i33 < i32, "the About changelog order: 1.4.33 rides ABOVE 1.4.32")
need("## [1.4.33] — 2026-11-27" in chlog and
     chlog.find("## [1.4.33]") < chlog.find("## [1.4.32]"),
     "CHANGELOG.md carries the [1.4.33] section above [1.4.32]")


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

print()
print("B — QR: THE PORTRAIT-ONLY LAW (remove the landscape liability)")
# the manifest pin
qsa_act = manifest[manifest.find(".ui.QrScanActivity"):]
qsa_act = qsa_act[:qsa_act.find("/>")]
need('android:name=".ui.QrScanActivity"' in manifest and
     'android:screenOrientation="portrait"' in qsa_act,
     "the scanner activity is LOCKED portrait in the manifest")
m_base = extract_method(qsa, '    static int baseAngleFor(String model, int sensor, int display) {')
# the base table: the portrait entry ONLY (the v1.4.32 four-display rows
# are retired — dated re-point of those pins)
need("static int baseAngleFor" in qsa and
     '"RMX2020".equals(model) && sensor == 90 && display == 0' in qsa
     and "return 270;" in qsa,
     "the device base keeps ONLY the portrait entry: RMX2020 sensor=90 "
     "display=0 -> 270")
if m_base:
    need("(270 + display) % 360" not in m_base,
         "the v1.4.32 (270 + display) % 360 family is GONE from the table "
         "(the landscape rows retired with the landscape path)")
need("qrUserOffset." in qsa and "rotOffsetKey" not in qsa
     and "seedRotOffsetFor" not in qsa,
     "the rotate offset key stays qrUserOffset.<sensor>.<display> "
     "(portrait-only under the lock); the old qrRotOffset.* keys are "
     "never read")
# the theta formula carries base + userOffset (unchanged by the lock)
need("(sensorOrientation - degrees - producerRot + base" in qsa and
     "+ userOffset + 720) % 360" in qsa,
     "theta = (sensor - display - producer + base + userOffset + 720) % 360")
# the diag carries base= (the v1.4.32 line rides) + the dated note
need('" base=" + baseAngleFor(' in qsa,
     "the qr open diag line carries base=")
need("v1.4.33" in qsa and "PORTRAIT-ONLY" in qsa,
     "the dated portrait-only note rides the base table")

print("  -- the compiled REAL base table (portrait entry; landscape rows gone) --")
m_undo = extract_method(qsa, '    static String undoModeFor(String model) {')
m_pad = extract_method(qsa, '    static float padFactor(int buf, int active) {')
m_comp = extract_method(qsa, '    static float compoundAnisotropy(float impX, float impY, float undoX, float undoY) {')
m_acc = extract_method(qsa, '    static boolean measuredAcceptable(float ix, float iy, float priorX, float priorY) {')
need(m_base and m_undo and m_pad and m_comp and m_acc and javac,
     "the REAL base/undo/pad/compound/acceptance methods are extractable")

if m_base and m_undo and m_pad and m_comp and m_acc and javac:
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        harness = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    public static void main(String[] args) {
        // ---- the base table: the PORTRAIT entry ----
        eq("base portrait 0", Probe.baseAngleFor("RMX2020", 90, 0) == 270);
        // ---- the landscape rows are GONE (v1.4.33 dated re-point of
        // the v1.4.32 four-display pins: display= is always 0 under the
        // portrait lock; any non-portrait answer is 0) ----
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
        // ---- the padding factor ----
        float pad = Probe.padFactor(1088, 1080);
        eq("pad 1088/1080 = 1.00741", Math.abs(pad - 1.00741f) < 0.0005f);
        eq("pad no-info no-ops",
                Probe.padFactor(1920, 0) == 1f && Probe.padFactor(0, 1080) == 1f
                && Probe.padFactor(1920, 1920) == 1f);
        eq("pad junk guarded", Probe.padFactor(1088, 2000) == 1f);
        // ---- THE 1088/1080 PADDING FIXTURE (the corpse arithmetic):
        // the ACTIVE scene shows 1:1 horizontally but the display maps
        // the 1080-row scene through the 1088-row buffer span — the
        // implicit reads (1.00741, 1.0); the UNCOMPENSATED undo (1,1)
        // composes to 1.007 (the reported slight stretch); the
        // COMPENSATED undo (1.0, 1088/1080) composes to 1.000.
        double un = Probe.compoundAnisotropy(
                (float) (1088.0 / 1080.0), 1f, 1f, 1f);
        eq("padding corpse UNCOMPENSATED reads 1.007",
                Math.abs(un - 1.0074) < 0.001f);
        double co = Probe.compoundAnisotropy(
                (float) (1088.0 / 1080.0), 1f, 1f, (float) (1088.0 / 1080.0));
        eq("padding COMPENSATED reads 1.000", Math.abs(co - 1.0) < 1e-4);
        // the standing v1.4.32 corpse rides unchanged
        double corpse = Probe.compoundAnisotropy(
                (float) Math.max(1080.0 / 1920.0, 2298.0 / 1080.0),
                (float) Math.max(1080.0 / 1920.0, 2298.0 / 1080.0),
                (float) (1920.0 / 1080.0), (float) (1080.0 / 2298.0));
        eq("v1.4.30 corpse compound 3.784", Math.abs(corpse - 3.784) < 0.01);
        // the measured leg stays 1.000
        double meas = Probe.compoundAnisotropy(2.13f, 2.13f, 1f / 2.13f, 1f / 2.13f);
        eq("measured leg 1.000", Math.abs(meas - 1.0) < 1e-4);
        // ---- the acceptance window: the padding factor (1.0074) sits
        // INSIDE the window — a padded capture is not contamination
        float k = Math.max(1080f / 1920f, 2298f / 1080f);
        eq("padding-sized drift accepted (1.0074 in window)",
                Probe.measuredAcceptable(k * 1.0074f, k, k, k));
        eq("clean solve accepted (cover device, table prior)",
                Probe.measuredAcceptable(k, k, k, k));
        eq("anisotropic contamination rejected",
                !Probe.measuredAcceptable(k * 1.9f, k * 0.6f, k, k));
        if (fails > 0) { System.out.println("PROBE FAILURES: " + fails); }
        else { System.out.println("all green"); }
    }
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Probe {\n"
                    + "    static final String[] COVER_UNDO_MODELS = "
                    "{ \"RMX2020\" };\n"
                    + m_base + "\n" + m_undo + "\n"
                    + m_pad + "\n" + m_comp + "\n" + m_acc + "\n" + harness + "\n}")
        r = subprocess.run([javac, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the base/pad probes compile: " + r.stderr[:300])
        else:
            r2 = subprocess.run(["java", "-cp", d, "Probe"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL base table (portrait entry, landscape rows "
                 "retired) + the padding factor + the 1088/1080 compound "
                 "fixture (1.007 uncompensated / 1.000 compensated) + the "
                 "acceptance window"
                 + ("" if r2.returncode == 0 else r2.stdout[:400]))

print()
print("C — QR: THE ACTIVE-ARRAY PADDING LAW + the honest net=")
# the active-array read at openCamera
need("SENSOR_INFO_ACTIVE_ARRAY_SIZE" in qsa,
     "openCamera reads SENSOR_INFO_ACTIVE_ARRAY_SIZE")
need("activeW = Math.min(aa.width(), previewSize.getWidth());" in qsa
     and "activeH = Math.min(aa.height(), previewSize.getHeight());" in qsa,
     "the active read is clamped to the stream dims")
need("activeW = 0; activeH = 0;   // junk guard: the law no-ops" in qsa,
     "a junk active read is guarded (the law no-ops)")
# the padding compensation rides AFTER the undo ops
m_tr = extract_method(qsa, '    private void applyPreviewTransform(int viewW, int viewH) {')
need(m_tr and "padFactor(bufW, activeW)" in m_tr and "padFactor(bufH, activeH)" in m_tr,
     "the transform applies postScale(buf/active) — the padding compensation")
if m_tr:
    i_undo = m_tr.find("matrix.postScale(undoSx, undoSy);")
    i_pad = m_tr.find("if (padX != 1f || padYf != 1f) matrix.postScale(padX, padYf);")
    need(i_undo >= 0 and i_pad > i_undo,
         "the compensation lands AFTER the undo ops (the active-array law)")
need("float aw = activeW > 0 ? activeW : bw;" in qsa
     and "float ah = activeH > 0 ? activeH : bh;" in qsa
     and "float uw = (theta % 180 == 0) ? aw : ah;" in qsa,
     "ACTIVE dims ride uw/uh/cover (the buffer overhang is never scene)")
need("Math.max(viewW / (float) aw, viewH / (float) ah)" in qsa,
     "the table cover-undo k rides the ACTIVE dims")
need("Math.max(vw / (float) awPrior(),\n                                vh / (float) ahPrior())" in qsa,
     "the chain prior rides the ACTIVE dims (the acceptance window too)")
# the honest net/agree
need('"agree=unknown"' in qsa and '"net=" + String.format(java.util.Locale.US, "%.3f", net)' in qsa,
     "net= became honest: measured -> the compound of the measured "
     "implicit vs the APPLIED undo (pad included); otherwise "
     "agree=unknown (never a fake 1.000)")
need('compoundAnisotropy(calibIx, calibIy,\n                            undoSx * padFactor(bufW, activeW),\n                            undoSy * padFactor(bufH, activeH))' in qsa,
     "the honest net composes the measured implicit against the APPLIED "
     "undo INCLUDING the padding compensation")
need('" active=" + Math.round(aw) + "x" + Math.round(ah) +' in qsa and
     '" pad=" + String.format(java.util.Locale.US, "%.3f",' in qsa,
     "the preview diag line grew active= + pad=")
need('"qr active "' in qsa and 'active=" + activeW + "x" + activeH' in qsa,
     "the open diag line grew the qr active active=/pad= fields")

print()
print("D — QR: THE DOT-CLOSING LEG (chess.com-style codes)")
need("public static byte[] close3x3(byte[] y, int width, int height)" in qc,
     "close3x3 (max-filter then min-filter) is in the core")
need("public static byte[] dilate3x3(byte[] y, int width, int height)" in qc,
     "dilate3x3 (the max pass alone) is in the core")
need("public static int chromaMaxSpread(byte[] chroma)" in qc,
     "chromaMaxSpread is the degeneracy check")
need("public static String decodeClosed(byte[] y, int width, int height)" in qc,
     "decodeClosed is the pre-closed plane's entry (the live failure leg)")
# the ladder structure: the closing legs ride the failure paths
mm = extract_method(qc, '    public static String decodeMulti(int[] pixels, int width, int height) {')
need(mm and mm.find("decodeLuma(") < mm.find("chromaOfArgb(") and
     mm.find("chromaOfArgb(") < mm.find("close3x3(hs, hw, hh)"),
     "decodeMulti: luma ladder -> chroma leg -> the FINAL closing leg "
     "after chroma (the budget order)")
dlm = extract_method(qc, '    public static String decodeLuma(byte[] y, int width, int height) {')
need(dlm and dlm.find("halfLuma(") < dlm.find("close3x3(y, width, height)"),
     "decodeLuma's closing leg is the FINAL leg (after plain/stretch/half)")
need("if (chromaMaxSpread(chroma) < 8) return null;" in qc,
     "the chroma leg SKIPS a degenerate plane (max spread < 8)")
# the live failure leg: dilate-only at half res, after the chroma leg
live = qsa[qsa.find("String text = QrCore.decodeLuma(y, im.getWidth(), im.getHeight());"):]
fail_branch = live[:live.find("if (text != null && !text.trim().isEmpty())")]
need('if (text == null || text.trim().isEmpty()) {' in fail_branch and
     "destridePlane(pu, uBuf, cw, ch);" in fail_branch and
     "QrCore.chromaOf(uBuf, vBuf, cw, ch);" in fail_branch and
     "QrCore.decodeChroma(chroma, cw, ch);" in fail_branch,
     "the live U/V extraction + the chroma decode live ONLY inside the "
     "failure branch (the 4 fps budget law)")
need("QrCore.dilate3x3(hs, hw, hh);" in fail_branch and
     "QrCore.decodeClosed(dl, hw, hh);" in fail_branch and
     "QrCore.halfLuma(y, im.getWidth(), im.getHeight());" in fail_branch,
     "the live DOT-CLOSING leg rides DILATE-ONLY at HALF res (the "
     "frame-budget permission), after the chroma leg")

print("  -- the compiled REAL dot-closing probe (the honest pin) --")
if javac:
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
    if jar:
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "QrCore.java")
            with open(src, "w", encoding="utf-8") as f:
                f.write(qc.replace("package com.bolt.browser.util;", ""))
            r = subprocess.run([javac, "-cp", jar, src], capture_output=True, text=True)
            need(r.returncode == 0, "QrCore compiles standalone: " +
                 ("" if r.returncode == 0 else r.stderr[:300]))
            if r.returncode == 0:
                src2 = os.path.join(d, "Closing.java")
                probe = r'''
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import java.util.*;

public class Closing {
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
    // the PRE-1.4.33 ladder, reconstructed from the public pieces: the
    // dual-binarizer plain+invert pair, the stretch plane, the half
    // plane, the chroma plane — exactly what v1.4.32 shipped.
    static String preLadder(byte[] y, int w, int h) {
        String r = hyb(y, w, h);
        if (r != null) return r;
        r = hybInv(y, w, h); if (r != null) return r;
        r = glob(y, w, h); if (r != null) return r;
        r = globInv(y, w, h); if (r != null) return r;
        byte[] st = QrCore.stretchLuma(y, w, h);
        if (st != null) {
            r = hyb(st, w, h); if (r != null) return r;
            r = hybInv(st, w, h); if (r != null) return r;
            r = glob(st, w, h); if (r != null) return r;
            r = globInv(st, w, h); if (r != null) return r;
        }
        int hw = w / 2, hh = h / 2;
        if (hw >= 64 && hh >= 64) {
            byte[] hs = QrCore.halfLuma(y, w, h);
            if (hs != null) {
                r = hyb(hs, hw, hh); if (r != null) return r;
                r = hybInv(hs, hw, hh); if (r != null) return r;
                r = glob(hs, hw, hh); if (r != null) return r;
                r = globInv(hs, hw, hh); if (r != null) return r;
            }
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        final String TEXT = "https://www.chess.com/game/live/123456789?username=grandmaster_bolt&tab=overview";
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
        int S = 8, Q = 4*S, W = (m+8)*S;

        // ---- 1. THE DOT CORPSE (the honest pin): the chess.com field
        // shape — LIGHT dot modules on BLACK (a dark-theme stylized code),
        // 50% duty cycle round dots with ANTIALIASED edges, SOLID finder
        // patterns, a centre logo block, then the photo reality: a
        // screen-glare wash, a box blur (the camera/screen smear) and
        // sensor noise. Measured in this session: the PRE-1.4.33 ladder
        // misses this code and the CLOSING leg decodes it.
        int lr0 = m/2 - 3, lr1 = m/2 + 3;
        double rad = S * 0.5 / 2.0;   // 50% duty cycle
        int[] dot = new int[W*W];
        java.util.Arrays.fill(dot, 0xFF141414);   // the dark field
        for (int r = 0; r < m; r++) for (int c = 0; c < m; c++) {
            int x0 = Q + c*S, y0 = Q + r*S;
            if (r >= lr0 && r <= lr1 && c >= lr0 && c <= lr1) {
                for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++)
                    dot[(y0+dy)*W + x0+dx] = 0xFFE8E8E8;   // the logo block
                continue;
            }
            if (solid[r][c]) {
                // the function patterns stay SOLID (the chess.com shape)
                for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++)
                    dot[(y0+dy)*W + x0+dx] = B[r][c] ? 0xFFE6E6E6 : 0xFF141414;
                continue;
            }
            if (!B[r][c]) continue;
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
        // the screen glare: a soft darkening wash over the top-left
        // quadrant (the field: "screen glare" — the highlight clips the
        // dots' contrast there)
        for (int y = 0; y < W/2; y++) for (int x = 0; x < W/2; x++) {
            int p = dot[y*W + x];
            int lum = ((p & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587
                    + ((p >> 16) & 0xFF) * 114) / 1000;
            double f = 1.0 - 0.5 * (1.0 - (double)(x + y) / (double)W);
            int v = (int) Math.max(0, Math.min(255, Math.round(lum * f)));
            dot[y*W + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        // the camera/screen blur + the sensor noise (the photo reality)
        int[] bl = new int[W*W];
        int BR = 3;
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
        java.util.Random rnd = new java.util.Random(7L);
        for (int i = 0; i < bl.length; i++) {
            int p = bl[i];
            int v = Math.max(0, Math.min(255,
                    ((p & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587
                     + ((p >> 16) & 0xFF) * 114) / 1000 + rnd.nextInt(31) - 15));
            bl[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        byte[] dl = QrCore.lumaOf(bl, W, W);
        eq("dot corpse: the PRE-1.4.33 ladder MISSES",
            Closing.preLadder(dl, W, W) == null);
        eq("dot corpse: the ARGB decode misses",
            QrCore.decode(bl, W, W) == null);
        // the fixture is hue-FLAT (gray dots on gray field): the chroma
        // plane is degenerate, the skip refuses it — so whatever decodes
        // below rides a CLOSING leg, not the chroma leg (the honest pin)
        byte[] dotChroma = QrCore.chromaOfArgb(bl, W, W);
        eq("dot corpse: the chroma plane is degenerate (skip fires; the "
                + "rescue below is the closing)",
            dotChroma == null || QrCore.chromaMaxSpread(dotChroma) < 8);
        eq("dot corpse: the closing leg DECODES (decodeLuma)",
            TEXT.equals(QrCore.decodeLuma(dl, W, W))
            || TEXT.equals(QrCore.decodeMulti(bl, W, W)));
        // and the gallery multi-pass carries it too
        eq("dot corpse: decodeMulti decodes",
            TEXT.equals(QrCore.decodeMulti(bl, W, W)));

        // ---- 2. THE DEGENERATE CHROMA SKIP: a hue-flat scene (gray
        // modules) builds an all-zero chroma plane — decodeChroma
        // refuses it without spending the binarizer runs
        int cw = (W+1)/2;
        byte[] u = new byte[cw*cw], v = new byte[cw*cw];
        java.util.Arrays.fill(u, (byte) 128);
        java.util.Arrays.fill(v, (byte) 128);
        byte[] flat = QrCore.chromaOf(u, v, cw, cw);
        eq("a flat chroma plane reads spread 0",
            QrCore.chromaMaxSpread(flat) == 0);
        eq("the degenerate skip: decodeChroma refuses a flat plane",
            QrCore.decodeChroma(flat, cw, cw) == null);

        // ---- 3. PLAIN regression: the common path is untouched
        byte[] pl = QrCore.lumaOf(plain, n, n);
        eq("plain QR still decodes (leg one)",
            TEXT.equals(QrCore.decodeLuma(pl, n, n)));
        eq("plain QR still decodes (ARGB)", TEXT.equals(QrCore.decode(plain, n, n)));
        eq("plain QR still decodes (decodeMulti)",
            TEXT.equals(QrCore.decodeMulti(plain, n, n)));

        // ---- 4. the v1.4.32 colour corpse still decodes (no chroma
        // regression from the skip — a REAL colour plane passes the
        // spread gate)
        int[] col = new int[W*W];
        java.util.Arrays.fill(col, 0xFFFFFFFF);
        for (int r = 0; r < m; r++) for (int c = 0; c < m; c++) {
            int x0 = Q + c*S, y0 = Q + r*S;
            int val = B[r][c] ? 0xFF0000FF : 0xFF003200;
            for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++)
                col[(y0+dy)*W + x0+dx] = val;
        }
        byte[] ch = QrCore.chromaOfArgb(col, W, W);
        eq("a REAL chroma plane passes the spread gate",
            QrCore.chromaMaxSpread(ch) >= 8);
        eq("the v1.4.32 colour corpse still decodes via chroma",
            TEXT.equals(QrCore.decodeChroma(ch, cw, cw)));

        if (fails > 0) { System.out.println("PROBE FAILURES: " + fails);
            System.exit(1); }
        System.out.println("all green");
    }
}
'''
                with open(src2, "w", encoding="utf-8") as f:
                    f.write(probe)
                r2 = subprocess.run([javac, "-cp", jar + ":" + d, src2],
                                    capture_output=True, text=True)
                if r2.returncode != 0:
                    need(False, "the closing probe compiles: " + r2.stderr[:400])
                else:
                    r3 = subprocess.run(["java", "-cp", jar + ":" + d, "Closing"],
                                        capture_output=True, text=True)
                    need(r3.returncode == 0 and "all green" in r3.stdout,
                         "the compiled REAL closing probe: the 50%-duty dot "
                         "fixture (solid finders, centre logo, glare) MISSES "
                         "the pre-1.4.33 ladder and HITS the closing leg; the "
                         "degenerate-chroma skip refuses a flat plane; plain "
                         "QRs unchanged; the v1.4.32 colour corpse still "
                         "decodes via chroma"
                         + ("" if r3.returncode == 0 else r3.stdout[:500]))

print()
print("E — POPUPS: THE CHOOSER-IS-NOT-CONSENT LAW (account switch -> new tab)")
need("static boolean isIdpConsentPath(String url)" in ba
     and "static String popupDestination(String popupUrl, boolean prefNewTab)" in ba,
     "the PURE decision pair lives in BrowserActivity (gate-compiled)")
need("Tab.isAuthRequestUrl(popupUrl)" in ba and
     "Tab.isAuthResponseUrl(popupUrl)" in ba and
     "isIdpConsentPath(popupUrl)" in ba,
     "the consent rows ride the SHARED auth-shape classifiers (the "
     "auth-hold law — they never drift)")
# the rewire: the classified branch decides via popupDestination
need('String dest = popupDestination(url,\n                        prefs.b("popupNewTab", false));' in ba and
     'if (dest.equals("newtab") && !popupChildVisible) {' in ba,
     "the classified first navigation decides via popupDestination "
     "(the new-tab default)")
need("openNewTab(pu);" in ba and
     ba.find('String dest = popupDestination(url') < ba.find("openNewTab(pu);\n                    return true;"),
     "a chooser-classified child retires into openNewTab")
# the belt-and-braces gate
need('popupDestination(url, prefs.b("popupNewTab", false))\n                                .equals("overlay")' in ba,
     "the belt-and-braces shows the child ONLY for an overlay destination")
# the migration: the stored old default is reset, overrides survive
need('if (prefs.has("popupNewTab") && !prefs.b("popupNewTab", false)) {' in ba and
     'prefs.remove("popupNewTab");' in ba and
     "CHOOSER-IS-NOT-CONSENT MIGRATION (dated 2026-11-27)" in ba,
     "the migration: a stored popupNewTab equal to the old default is "
     "REMOVED (dated note); an explicit override (true) survives")
need("public void remove(String k)" in pf and "global.remove(k);" in pf,
     "Prefs.remove drops a key (the migration mechanic)")
# the settings row still carries the override
need('prefs.set("popupNewTab", on)' in ba or 'popupNewTab' in read(
    "app/src/main/java/com/bolt/browser/ui/SettingsItemsActivity.java"),
     "the Settings override row rides (explicit user overrides survive)")

print("  -- the compiled REAL popupDestination decision table --")
m_idp = extract_method(ba, '    static boolean isIdpConsentPath(String url) {')
m_dest = extract_method(ba, '    static String popupDestination(String popupUrl, boolean prefNewTab) {')
m_ar = extract_method(tb.replace("\n    ", "\n        "), '    public static boolean isAuthRequestUrl(String url) {') or \
       extract_method(tb, '    public static boolean isAuthRequestUrl(String url) {')
need(m_idp and m_dest and m_ar and javac,
     "the REAL isIdpConsentPath + popupDestination + the auth-shape "
     "classifier are extractable")
if m_idp and m_dest and m_ar and javac:
    # the helper chain the two methods need, transcribed from the REAL
    # Tab.java + a faithful Fx domain trio
    tab_src = tb
    m_q = extract_method(tab_src, '    private static String urlQueryOf(String url) {')
    m_r = extract_method(tab_src, '    private static String urlRefOf(String url) {')
    m_h = extract_method(tab_src, '    private static boolean qHasAny(String q, String... names) {')
    m_resp = extract_method(tab_src, '    public static boolean isAuthResponseUrl(String url) {')
    fx_slice = fx[fx.find("    private static final Set<String> TWO_PART_TLDS"):]
    fx_slice = fx_slice[:fx_slice.find("    public static boolean sameSite")]
    fx_slice = fx_slice.replace("public static", "static").replace(
        "private static final", "static final")
    need(m_q and m_r and m_h and m_resp and fx_slice,
         "the helper chain (query/ref/param match + the domain trio) is "
         "extractable")
    if m_q and m_r and m_h and m_resp and fx_slice:
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "Dest.java")
            # the extracted methods carry the Tab./Fx. prefixes — strip
            # them for the standalone harness (the inlined helpers own
            # the same names)
            m_idp2 = m_idp.replace("Fx.", "")
            m_dest2 = m_dest.replace("Tab.isAuth", "isAuth").replace("Fx.", "")
            harness = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    public static void main(String[] args) {
        // ---- the chooser rows: NEW TAB (the corpse) ----
        eq("Gmail account chooser -> newtab",
            "newtab".equals(Dest.popupDestination(
                "https://accounts.google.com/AddSession?hl=en&continue="
                + "https://mail.google.com/mail/u/0", false)));
        eq("AccountChooser -> newtab",
            "newtab".equals(Dest.popupDestination(
                "https://accounts.google.com/AccountChooser?continue="
                + "https://accounts.google.com/b/0/AddSession", false)));
        // ---- the consent rows: OVERLAY (the pref off) ----
        eq("google oauth2 authorize -> overlay",
            "overlay".equals(Dest.popupDestination(
                "https://accounts.google.com/o/oauth2/auth?client_id=abc"
                + "&redirect_uri=https%3A%2F%2Fimgur.com&response_type=code",
                false)));
        eq("google Consent path -> overlay",
            "overlay".equals(Dest.popupDestination(
                "https://accounts.google.com/Consent?hl=en&continue=x", false)));
        eq("github authorize -> overlay",
            "overlay".equals(Dest.popupDestination(
                "https://github.com/login/oauth/authorize?client_id=abc", false)));
        eq("facebook dialog oauth -> overlay",
            "overlay".equals(Dest.popupDestination(
                "https://www.facebook.com/dialog/oauth?client_id=abc"
                + "&redirect_uri=x", false)));
        eq("apple authorize -> overlay",
            "overlay".equals(Dest.popupDestination(
                "https://appleid.apple.com/auth/authorize?client_id=abc"
                + "&redirect_uri=x", false)));
        // ---- the pref override: a user who prefers tabs sends consent
        // flows to a tab too ----
        eq("consent + prefNewTab -> newtab",
            "newtab".equals(Dest.popupDestination(
                "https://accounts.google.com/o/oauth2/auth?client_id=abc"
                + "&redirect_uri=x&response_type=code", true)));
        // ---- the junk rows: never overlay ----
        eq("null -> newtab",
            "newtab".equals(Dest.popupDestination(null, false)));
        eq("non-http -> newtab",
            "newtab".equals(Dest.popupDestination("intent://x", false)));
        eq("non-IdP same-base chain -> newtab",
            "newtab".equals(Dest.popupDestination(
                "https://imgur.com/signin?redirect=x", false)));
        // ---- isIdpConsentPath: the chooser path is NOT consent ----
        eq("AddSession is not a consent path",
            !Dest.isIdpConsentPath("https://accounts.google.com/AddSession?x=1"));
        eq("AccountChooser is not a consent path",
            !Dest.isIdpConsentPath("https://accounts.google.com/AccountChooser"));
        eq("/o/oauth2/auth IS a consent path",
            Dest.isIdpConsentPath("https://accounts.google.com/o/oauth2/auth?x=1"));
        eq("non-IdP host is never a consent path",
            !Dest.isIdpConsentPath("https://imgur.com/oauth/authorize?x=1"));
        if (fails > 0) { System.out.println("PROBE FAILURES: " + fails); }
        else { System.out.println("all green"); }
    }
"""
            with open(src, "w", encoding="utf-8") as f:
                f.write("import java.util.*;\n\npublic class Dest {\n"
                        + fx_slice + "\n"
                        + m_ar + "\n" + m_resp + "\n" + m_q + "\n" + m_r + "\n" + m_h + "\n"
                        + m_idp2 + "\n" + m_dest2 + "\n" + harness + "\n}")
            r = subprocess.run([javac, src], capture_output=True, text=True)
            if r.returncode != 0:
                need(False, "the destination probe compiles: " + r.stderr[:400])
            else:
                r2 = subprocess.run(["java", "-cp", d, "Dest"],
                                    capture_output=True, text=True)
                need(r2.returncode == 0 and "all green" in r2.stdout,
                     "the REAL popupDestination table: the chooser rows "
                     "(AddSession/AccountChooser) -> NEW TAB, the consent "
                     "rows (oauth2/auth, Consent, github/facebook/apple "
                     "authorize) -> OVERLAY, the pref override, the junk "
                     "rows; isIdpConsentPath refuses the chooser paths"
                     + ("" if r2.returncode == 0 else r2.stdout[:500]))

print()
print("F — SCROLL: THE BOTTOM-FLASH KILL")
# leg a: the WebView settings
need("w.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);" in tb,
     "every tab WebView rides OVER_SCROLL_NEVER (the unpainted-canvas leg)")
need('.getMethod("setOffscreenPreRaster", boolean.class);' in tb and
     "if (android.os.Build.VERSION.SDK_INT >= 26)" in tb,
     "setOffscreenPreRaster(true) rides the API-26 gate via reflection "
     "(public API; the stripped SDK platform does not carry it at "
     "compile time — the reflective invoke never throws)")
# leg b: the stale backdrop
need("private final boolean[] blurStale = new boolean[2];" in ba and
     "protected void markBackdropStale()" in ba and
     "protected void clearBackdropStale(int edge)" in ba,
     "the stale-backdrop state + the mark/clear pair live in the activity")
need("static String backdropState(boolean scrollActive, boolean stale,\n            boolean freshSnapshot)" in ba,
     "the PURE backdropState state machine is extractable")
need("lastScrollAt = android.os.SystemClock.uptimeMillis();" in ba and
     "if (barsShown) markBackdropStale();" in ba,
     "onContentScrolled marks the backdrops stale FIRST (the corpse leg)")
need("boolean scrollActive = now - lastScrollAt < 160;" in ba and
     "long gap = (blurStale[0] || blurStale[1]) ? 120 : 400;" in ba,
     "no snapshot mid-scroll; the post-idle cadence is ~120ms, the "
     "standing throttle 400ms")
need("clearBackdropStale(top ? 0 : 1);" in ba,
     "a fresh snapshot clears the stale mark (glass returns)")
need("blurStale[0] = false;\n            blurStale[1] = false;" in ba,
     "a teardown/rebuild starts fresh (no inherited stale mark)")
need("public static int barSolidColor(Context c)" in fx and
     "0xF00E0E1A" in fx and "0xE6F4F5FB" in fx,
     "Fx.barSolidColor: the OPAQUE end of the bar treatment (glass dark/"
     "light, the flat surface otherwise)")
# the Robolectric scroll-state probe on disk
rt = "app/src/test/java/com/bolt/browser/ui/BackdropStaleTest.java"
need(os.path.exists(rt) and "stale -> solid" in read(rt) and
     "snapshot" in read(rt),
     "the Robolectric scroll-state probe exists (stale -> solid, idle -> "
     "snapshot)")

print("  -- the compiled REAL backdropState table --")
m_bd = extract_method(ba, '    static String backdropState(boolean scrollActive, boolean stale,\n            boolean freshSnapshot) {')
if m_bd is None:
    # single-line anchor fallback
    s = ba.find('    static String backdropState(boolean scrollActive, boolean stale,')
    if s >= 0:
        depth = 0
        started = False
        for i in range(s, len(ba)):
            c = ba[i]
            if c == '{':
                depth += 1
                started = True
            elif c == '}':
                depth -= 1
                if started and depth == 0:
                    m_bd = ba[s:i + 1]
                    break
need(m_bd and javac, "the REAL backdropState is extractable")
if m_bd and javac:
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Back.java")
        harness = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    public static void main(String[] args) {
        // scroll active: ALWAYS solid (never the stale snapshot)
        eq("scrollActive -> solid",
            "solid".equals(Back.backdropState(true, false, false))
            && "solid".equals(Back.backdropState(true, true, true)));
        // a fresh snapshot always restores the glass
        eq("fresh snapshot -> glass",
            "glass".equals(Back.backdropState(false, true, true))
            && "glass".equals(Back.backdropState(false, false, true)));
        // stale + nothing fresh: solid (the corpse leg)
        eq("stale, no snapshot -> solid",
            "solid".equals(Back.backdropState(false, true, false)));
        // idle + fresh (never marked): glass
        eq("idle clean -> glass",
            "glass".equals(Back.backdropState(false, false, false)));
        if (fails > 0) { System.out.println("PROBE FAILURES: " + fails); }
        else { System.out.println("all green"); }
    }
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Back {\n" + m_bd + "\n" + harness + "\n}")
        r = subprocess.run([javac, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the backdrop probe compiles: " + r.stderr[:300])
        else:
            r2 = subprocess.run(["java", "-cp", d, "Back"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL backdropState: scrollActive -> solid, stale -> "
                 "solid, a fresh snapshot -> glass"
                 + ("" if r2.returncode == 0 else r2.stdout[:400]))

print()
print("G — CACHE DOORS + TRIM POLICY (the recents-wipe corpse)")
# the door/deep matrix in CacheGuard.exit
need('if (door == "delayed") {\n                        deep = false\n                    } else if (Prefs.get(ctx).deepCleanExit()) {' in cg,
     "the delayed door is the cache-subtree sweep ONLY (deep=0 always); "
     "the deep diet rides destroy/user/update alone")
need('(if (deep) " deep=1"\n                             else if (door == "delayed") " deep=0" else "")' in cg,
     "the exit diag line SAYS deep=0 on the delayed door (the matrix is "
     "readable in the field)")
need("ui.postDelayed(cacheBgSweep, 10 * 60 * 1000L);" in ba and
     "ui.postDelayed(cacheBgSweep, 5 * 60 * 1000L);" not in ba,
     "the delayed exit door waits 10 minutes of backgrounding (dated "
     "re-point of the v1.4.31 5-minute cadence)")
# the startup sweep + the pending-exit finisher unchanged
need("CacheGuard.check(this, true);" in ba and
     "CacheGuard.completePendingExit(this);" in ba,
     "the startup sweep + the pending-exit finisher are unchanged (the "
     "~20 MB promise holds at true close)")
need('if (isFinishing()) {\n            try { com.bolt.browser.util.CacheGuard.exit(this, "destroy");' in ba,
     "the destroy door stays isFinishing-gated (the v1.4.32 real-close gate)")
# the trim table: the v1.4.31 rows re-pointed (rank 2 -> 4, idle 3 -> 10)
need('if (rank >= 0 && rank < 4) return false;' in ba and
     'return idleMs > 10L * 60L * 1000L;' in ba,
     "the trim table: rank 0-3 exempt, idle > 10 min (policy=idle10m-recent4)")
need('" policy=idle10m-recent4"' in ba,
     "the tabSleep diag names the new policy")

print("  -- the compiled REAL trimSleepDecision table (v1.4.31 rows re-pointed) --")
m_dec = extract_method(ba, '    static boolean trimSleepDecision(int level, String role, int rank, long idleMs) {')
need(m_dec and javac, "the REAL trimSleepDecision is extractable")
if m_dec and javac:
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Trim.java")
        body = m_dec.replace(
            "android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE", "60")
        harness = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    public static void main(String[] args) {
        int MOD = 60, COMP = 80;
        // level < 60: NEVER sleep
        eq("trim<60 keeps a 4h-idle bg tab",
           !Trim.trimSleepDecision(15, "bg", 5, 4 * 3600_000L)
           && !Trim.trimSleepDecision(59, "bg", 5, 4 * 3600_000L));
        // role: only bg sleeps
        eq("active/shown/media NEVER (any level, any idle)",
           !Trim.trimSleepDecision(COMP, "active", -1, 4 * 3600_000L)
           && !Trim.trimSleepDecision(COMP, "shown", -1, 4 * 3600_000L)
           && !Trim.trimSleepDecision(COMP, "media", 5, 4 * 3600_000L));
        // the recent FOUR are exempt (v1.4.33: rank 0-3 — the dated
        // re-point of the v1.4.31 recent-two rows)
        eq("the recent four are exempt",
           !Trim.trimSleepDecision(COMP, "bg", 0, 4 * 3600_000L)
           && !Trim.trimSleepDecision(COMP, "bg", 1, 4 * 3600_000L)
           && !Trim.trimSleepDecision(COMP, "bg", 2, 4 * 3600_000L)
           && !Trim.trimSleepDecision(COMP, "bg", 3, 4 * 3600_000L));
        // idle > 10 min only (v1.4.33: 3 -> 10 — the dated re-point)
        eq("10m01s bg rank5 sleeps", Trim.trimSleepDecision(COMP, "bg", 5, 10 * 60_000L + 1));
        eq("exactly 10min keeps", !Trim.trimSleepDecision(COMP, "bg", 5, 10 * 60_000L));
        eq("9m59s keeps", !Trim.trimSleepDecision(COMP, "bg", 5, 599_000L));
        eq("3m01s keeps now (the v1.4.31 row re-pointed)",
           !Trim.trimSleepDecision(COMP, "bg", 5, 181_000L));
        eq("fresh tab (idle 0) keeps", !Trim.trimSleepDecision(MOD, "bg", 5, 0));
        eq("the corpse case sleeps (old idle bg tab under a storm)",
           Trim.trimSleepDecision(COMP, "bg", 4, 40 * 60_000L));
        if (fails > 0) { System.out.println("PROBE FAILURES: " + fails); }
        else { System.out.println("all green"); }
    }
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Trim {\n" + body + "\n" + harness + "\n}")
        r = subprocess.run([javac, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the trim probe compiles: " + r.stderr[:300])
        else:
            r2 = subprocess.run(["java", "-cp", d, "Trim"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL trim table: rank 0-3 exempt, idle > 10 min, the "
                 "v1.4.31 rows re-pointed with dated notes"
                 + ("" if r2.returncode == 0 else r2.stdout[:400]))

print()
print("H — PERF: THE REPEATED-INJECTION GUARD (inj=17 bytes=250441)")
# (a) the per-hook fallback diag format
need('"hook fallback name=core armed=" + armed + " feature="' in mh,
     "the media core's fallback names itself (name=core armed= feature=)")
need('"hook fallback name=fh armed=" + docStartArmed(web)' in fhk,
     "FrameHooks' fallback names itself (name=fh armed= feature=)")
need('"hook fallback name=shr armed=" + ARMED.containsKey(web)' in sgk,
     "ShadowGate's fallback names itself (name=shr armed= feature=)")
# (b) the once-per-document guards carry the law names
need("window.__boltCoreArmed" in mh and
     "if(window.__boltCoreArmed||window.__boltCore)return;" in mh,
     "the media core's guard is __boltCoreArmed (any stray evaluate is a "
     "cheap no-op)")
need("window.__boltFhArmed" in fhk and
     "if(window.__boltFhArmed||window.__boltFH)return;" in fhk,
     "FrameHooks' guard is __boltFhArmed")
need("window.__boltShrArmed" in sgk and
     "if(window.__boltShrArmed)return;" in sgk,
     "ShadowGate's guard is __boltShrArmed")
# (c) the finish-path re-evaluation is GONE; the commit-marker backstop stays
need("view.evaluateJavascript(MediaHook.cssInjectOnce" not in ba,
     "the finish-time unconditional bolt-adcss evaluate is REMOVED (the "
     "commit-marker backstop is the only finish leg)")
need('if (!committed && !css.isEmpty())\n                    evalInj(view, MediaHook.cssInjectOnce("bolt-adcss", css));' in ba,
     "the cosmetic CSS keeps its commit-marker backstop (the gated "
     "evalInj form)")

print("  -- the compiled REAL needsFallbackEval decision --")
m_nfe = extract_method(mh, '    static boolean needsFallbackEval(boolean docStartArmed) {')
need(m_nfe and javac, "the REAL needsFallbackEval is extractable")
if m_nfe and javac:
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Inj.java")
        harness = """
    static int fails = 0;
    static void eq(String what, boolean ok) {
        if (!ok) { System.out.println("MISS: " + what); fails++; }
    }
    public static void main(String[] args) {
        eq("an armed WebView never re-evaluates",
           !Inj.needsFallbackEval(true));
        eq("an unarmed WebView keeps ONE fallback per document",
           Inj.needsFallbackEval(false));
        if (fails > 0) { System.out.println("PROBE FAILURES: " + fails); }
        else { System.out.println("all green"); }
    }
"""
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Inj {\n" + m_nfe + "\n" + harness + "\n}")
        r = subprocess.run([javac, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the injection probe compiles: " + r.stderr[:300])
        else:
            r2 = subprocess.run(["java", "-cp", d, "Inj"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL needsFallbackEval: armed never re-evaluates; "
                 "unarmed keeps one fallback"
                 + ("" if r2.returncode == 0 else r2.stdout[:400]))
# the perf nav line rides unchanged (the evidence channel)
need("perf nav commit=" in ba,
     "the perf nav evidence line rides unchanged")

print()
print("I — THE PIPELINE + THE README")
need("python3 scripts/test_v1433.py" in rels and
     rels.find("python3 scripts/test_v1433.py") > rels.find("python3 scripts/test_v1432.py"),
     "release.sh carries the v1.4.33 rung (after the v1.4.32 rung)")
need("THE PORTRAIT-ONLY LAW" in readme and "THE ACTIVE-ARRAY PADDING LAW" in readme
     and "THE DOT-CLOSING LEG" in readme and "THE CHOOSER-IS-NOT-CONSENT LAW" in readme
     and "THE STALE-BACKDROP LAW" in readme and "THE DOOR/DEEP MATRIX" in readme
     and "THE REPEATED-INJECTION GUARD" in readme,
     "the README names the v1.4.33 laws")
need("twenty-sixth session" in readme,
     "the README carries the v1.4.33 session narrative")
need("The portrait-lock read-back (v1.4.33)" in readme and
     "The active-array read-back (v1.4.33)" in readme and
     "The dot-closing read-back (v1.4.33)" in readme and
     "The chooser read-back (v1.4.33)" in readme and
     "The scroll read-back (v1.4.33)" in readme and
     "The door-matrix read-back (v1.4.33)" in readme and
     "The perf read-back (v1.4.33)" in readme,
     "the README carries the v1.4.33 open field read-backs")

print()
if fails:
    print("v1.4.33 gate: " + str(len(fails)) + " FAILURE(S)")
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("v1.4.33 gate: all green")
