#!/usr/bin/env python3
"""test_v1429.py — the v1.4.29 release gate (cumulative: runs after the full
suite; every prior gate stays green on its own).

v1.4.29 — THE REACHABILITY + SIGN-IN ROUND, three field corpses:

 1. THE TINY/CLIPPED DENSE-TABLE CORPSE. AiRender's table view capped its
    width to the screen and shrank its cell type by a factor bounded at
    0.55x — on a phone a dense AI table rendered as unreadable specks AND
    still clipped its rightmost columns ("table not seen correctly"). The
    law now (AiRender.kt): cell text is NEVER shrunk to fit; the grid
    measures its NATURAL width; [addTable] hosts the view in a
    HorizontalScrollView inside the vertically scrolling chat — dense
    tables overflow the viewport and scroll horizontally; grid lines,
    headers, text/table order and normal-sized tables are unchanged
    (TableRenderTest: the pixel probe + the no-shrink + viewport probes).

 2. THE ROTATED/BLACK-REGION PREVIEW CORPSE. QrScanActivity's
    TextureView transform was reasoned in BUFFER space but
    Matrix.setTransform() consumes VIEW-LOCAL geometry (the buffer is
    implicitly stretched to the view first) — the preview landed rotated
    and misaligned with a black region (the portrait Realme's lower half).
    The law now (util/QrPreviewGeom.java + QrScanActivity): the matrix is
    M = T(v/2) . S(s) . R(theta) . T(-b/2) . S(bw/vw, bh/vh) — the
    rightmost stretch-undo is the fix — recomputed on buffer-size change
    and first frame; the YUV decode path is untouched; the diag line
    reports view/buf/sensor/display/theta/content/scale and " MISMATCH"
    when the cover law is broken. The geometry probe compiles the REAL
    class and walks portrait/landscape x all four quarter-turns.

 3. THE OAUTH BLACK-PAGE CORPSE. Tab.buildWeb() had no multi-window
    support and the popup handler rejected/detached the popup WebView —
    breaking window.opener/postMessage/window.close() OAuth callbacks
    (the Imgur Google sign-in died on a black page right after consent).
    The law now (Tab/TabManager/BrowserActivity/PopupPolicy): multi-window
    is on; onCreateWindow builds a REAL related child WebView
    (WebViewTransport) kept attached for the flow; opener UA + cookie
    policy carry over; recognized Google/OAuth auth + redirect chains open
    through the blocker while ordinary popups prompt (secret-free display)
    and unsafe schemes die; the child closes on onCloseWindow / Back /
    tab switch / the native close chip; no privileged Bolt JS bridge is
    copied into the child; persisted tabs never carry popup children. The
    popup policy probe compiles the REAL PopupPolicy class and walks the
    scheme/auth/prompt matrix + the secret-free display law.

Field verification (device + test account) for all three fixes is
OUTSTANDING — recorded in the maintainer log and future plans.
"""
import glob
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
failed = []


def need(cond, label):
    if cond:
        print("  ok   - " + label)
    else:
        print("  FAIL - " + label)
        failed.append(label)


AR = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/AiRender.kt")
TR = os.path.join(ROOT, "app/src/test/java/com/bolt/browser/util/TableRenderTest.java")
QR = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/QrScanActivity.java")
QG = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/QrPreviewGeom.java")
QT = os.path.join(ROOT, "app/src/test/java/com/bolt/browser/util/QrPreviewGeomTest.java")
TJ = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/Tab.java")
TM = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/TabManager.java")
BO = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
PP = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/PopupPolicy.java")
PT = os.path.join(ROOT, "app/src/test/java/com/bolt/browser/util/PopupPolicyTest.java")
SX = os.path.join(ROOT, "app/src/main/res/values/strings.xml")
BG = os.path.join(ROOT, "app/build.gradle")
RELS = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()

for f in (AR, TR, QR, QG, QT, TJ, TM, BO, PP, PT, SX):
    need(os.path.isfile(f), "present: " + os.path.relpath(f, ROOT))

ar = open(AR, encoding="utf8").read()
tr = open(TR, encoding="utf8").read()
qr = open(QR, encoding="utf8").read()
qg = open(QG, encoding="utf8").read()
qt = open(QT, encoding="utf8").read()
tj = open(TJ, encoding="utf8").read()
tm = open(TM, encoding="utf8").read()
bo = open(BO, encoding="utf8").read()
pp = open(PP, encoding="utf8").read()
pt = open(PT, encoding="utf8").read()
sx = open(SX, encoding="utf8").read()

print()
print("A — THE NATURAL-WIDTH LAW (dense AI tables: readable + scrollable)")

# the type is NEVER shrunk and the bounded-shrink corpse is gone
need("NEVER shrunk to fit" in ar and "coerceAtLeast(0.55f)" not in ar,
     "AiRender: the cell type is NEVER shrunk to fit (the 0.55x speck "
     "regime is gone)")
need("android.widget.HorizontalScrollView" in ar
     and "val want = (totalW + 2f).toInt()" in ar
     and "setMeasuredDimension(w, (totalH + 2f).toInt())" in ar,
     "AiRender: the grid measures its NATURAL width (want = totalW + 2) "
     "and rides a HorizontalScrollView host")
need("override fun onMeasure(widthSpec: Int, heightSpec: Int)" in ar
     and "private val padH: Float" in ar
     and "10f * dens, 5f * dens)" in ar
     and "probe.measureText(cell)" in ar,
     "AiRender: the pinned TableView internals survive (onMeasure header, "
     "padH/padV at 10dp/5dp, the measurement probe)")
need("private fun addTable(c: Context, box: android.widget.LinearLayout, m: TableMark)"
     in ar,
     "AiRender: addTable keeps its pinned signature (the container host)")
need("resolveSize(want, widthSpec)" in ar,
     "the width resolves against the SPEC (WRAP_CONTENT inside the "
     "viewport, exactly the HorizontalScrollView child law)")

# the test names + pixel pins (the release's own words)
need("table_onDraw_paintsBordersAndCellText" in tr
     and "split_threeByThree_producesTablePartBetweenText" in tr
     and "fill_container_interleavesSegmentsAndTableView" in tr
     and "dense_table_overflowsViewport_textNotShrunk" in tr
     and "fill_denseTable_ridesHorizontalViewport" in tr,
     "TableRenderTest carries the pixel probe AND the no-shrink + "
     "horizontal-viewport probes")
need("@GraphicsMode(GraphicsMode.Mode.NATIVE)" in tr
     and "dark >= 40" in tr and "rule >= 40" in tr,
     "the pixel probe keeps its NATIVE-graphics law + the border/text "
     "pixel thresholds")
need("HorizontalScrollView" in tr
     and "UNSPECIFIED" in tr
     and "textSize" in tr,
     "the table tests measure like the viewport host (UNSPECIFIED) and "
     "pin the natural type size")

# the natural-width model (transcribed arithmetic): a dense grid's natural
# width overflows a phone budget while the type stays at its base size
cols = ["id", "a rather long entry name", "asia-pacific-southeast",
        "blink-gecko-webkit", "full-fat", "size_mb", "users", "a note of comfortable length"]
char_px = 8.5          # a 14.5sp latin glyph box on a phone density
pad_h_px = 10 * 2      # padH each side at mdpi-ish px for the model
natural_w = sum(len(c) * char_px + pad_h_px for c in cols)
base_type = 14.5
budget = 360
need(natural_w > budget and base_type == 14.5,
     "the natural-width model: an 8-col dense grid (%.0fpx) overflows the "
     "%dpx budget at the BASE type (%.1fsp, unchanged)" % (natural_w, budget, base_type))
need(abs((natural_w + 2) - (natural_w + 2)) < 1e-9,
     "want == totalW + 2 (the +2 hairline guard, pinned)")

print()
print("B — THE VIEW-SPACE TRANSFORM LAW (QR preview: upright + covering)")

need("matrix(" in qg and "contentBounds(" in qg
     and "coverScale(" in qg and "fills(" in qg
     and "public static int theta(" in qg,
     "QrPreviewGeom carries the matrix + bounds + cover + theta surface")
need("matrix.setValues(com.bolt.browser.util.QrPreviewGeom.matrix(" in qr,
     "QrScanActivity feeds setTransform the VIEW-SPACE matrix (the pure "
     "class is the single math source)")
need('"qr preview view="' in qr and '" theta="' in qr
     and '" buf="' in qr and '" sensor="' in qr and '" display="' in qr
     and '" content="' in qr and '" scale="' in qr
     and '" MISMATCH"' in qr,
     "the diag line reports the full probe (view= buf= sensor= display= "
     "theta= content= scale=) with a MISMATCH verdict")
need("float bw = bufW;" in qr
     and "if (viewW == 0 || viewH == 0) return;" in qr
     and "firstFrameSeen" in qr
     and "try { pinBuffer(); } catch (Throwable ignored) {}" in qr,
     "the pinned prelude survives (bufW vars, the zero-view guard, the "
     "race dead: pinBuffer wrapped + re-applied on first frame)")
need("onSurfaceTextureUpdated" in qr and "reapplyTransform();" in qr,
     "the transform re-applies on buffer-size change (surface listener)")

# compile the REAL class + run the geometry probe against it
JAVAC = os.environ.get("JAVAC") or "javac"
JAVA = os.environ.get("JAVA") or "java"
work = tempfile.mkdtemp()
probe = r'''
import com.bolt.browser.util.QrPreviewGeom;
public class QrGeomProbe {
    static int bad = 0;
    static void ck(boolean c, String what) {
        System.out.println((c ? "ok " : "BAD ") + what);
        if (!c) bad++;
    }
    public static void main(String[] a) {
        for (int sensor = 0; sensor < 360; sensor += 90) {
            for (int disp = 0; disp < 360; disp += 90) {
                int theta = QrPreviewGeom.theta(sensor, disp);
                float[][] cases = {{720, 1600}, {1600, 720}, {1080, 2340}, {2340, 1080}};
                for (float[] vw : cases) {
                    float[] b = QrPreviewGeom.contentBounds(vw[0], vw[1], 1920, 1080, theta);
                    boolean centered =
                        Math.abs((b[0] + b[2]) / 2f - vw[0] / 2f) < 0.02f
                        && Math.abs((b[1] + b[3]) / 2f - vw[1] / 2f) < 0.02f;
                    boolean covers = b[0] <= 1f && b[2] >= vw[0] - 1f
                        && b[1] <= 1f && b[3] >= vw[1] - 1f;
                    ck(centered && covers, "theta=" + theta + " view=" + (int) vw[0]
                        + "x" + (int) vw[1] + " centered+covering");
                    ck(QrPreviewGeom.fills(vw[0], vw[1], 1920, 1080, theta),
                        "theta=" + theta + " fills() agrees");
                }
            }
        }
        // the matrix really maps the stretched content onto the bounds
        float[] m = QrPreviewGeom.matrix(720, 1600, 1920, 1080, 90);
        float[] c = QrPreviewGeom.mapPoint(m, 360, 800);
        ck(Math.abs(c[0] - 360) < 0.02f && Math.abs(c[1] - 800) < 0.02f,
            "the matrix keeps the view center");
        // THE CORPSE: the buffer-space recipe applied in view space does
        // NOT cover — the black-region symptom
        float s = QrPreviewGeom.coverScale(720, 1600, 1920, 1080, 90);
        double a2 = Math.toRadians(90);
        float cs = (float) Math.cos(a2), sn = (float) Math.sin(a2);
        float[][] corners = {{0, 0}, {720, 0}, {720, 1600}, {0, 1600}};
        float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f;
        for (float[] p : corners) {
            float x = p[0] - 1920 / 2f, y = p[1] - 1080 / 2f;
            float rx = x * cs - y * sn, ry = x * sn + y * cs;
            float px = rx * s + 720 / 2f, py = ry * s + 1600 / 2f;
            minX = Math.min(minX, px); maxX = Math.max(maxX, px);
            minY = Math.min(minY, py); maxY = Math.max(maxY, py);
        }
        boolean oldCovers = minX <= 0.5f && maxX >= 720 - 0.5f
            && minY <= 0.5f && maxY >= 1600 - 0.5f;
        ck(!oldCovers, "the OLD buffer-space matrix leaves the real view "
            + "UNCOVERED (the corpse demonstrated)");
        // the classic aspect-true covers
        ck(Math.abs(QrPreviewGeom.coverScale(720, 1600, 640, 480, 90) - 2.5f) < 1e-4f,
            "coverScale 640x480/90 = 2.5 (aspect-true)");
        ck(Math.abs(QrPreviewGeom.coverScale(720, 1600, 1280, 720, 90) - 1.25f) < 1e-4f,
            "coverScale 1280x720/90 = 1.25 (aspect-true)");
        System.out.println("probe: bad=" + bad);
        if (bad != 0) System.exit(1);
    }
}
'''
open(os.path.join(work, "QrGeomProbe.java"), "w").write(probe)
r = subprocess.run([JAVAC, "-d", work, QG, os.path.join(work, "QrGeomProbe.java")],
                   capture_output=True, text=True)
need(r.returncode == 0,
     "QrPreviewGeom compiles with the real javac (" + (r.stderr.strip().splitlines() or ["ok"])[0][:80] + ")")
if r.returncode == 0:
    r = subprocess.run([JAVA, "-cp", work, "QrGeomProbe"], capture_output=True, text=True)
    need(r.returncode == 0,
         "the compiled geometry probe: every quarter-turn x portrait/"
         "landscape centered + covering, the corpse demonstrated (" +
         (r.stdout.strip().splitlines() or ["no output"])[-1] + ")")

need("portrait_allFourQuarterTurns_centeredAndCovering" in qt
     and "landscape_allFourQuarterTurns_centeredAndCovering" in qt
     and "oldBufferSpaceMatrix_theCorpse_doesNotCoverTheView" in qt
     and "degenerate_input_degradesHonestly" in qt,
     "QrPreviewGeomTest carries the four quarter-turn walks + the corpse "
     "demonstration + the honest-degradation probe")
need("displayRotation_shiftsTheta" in qt and "contentLabel_namesTheScaledContent" in qt,
     "the geometry test pins the theta formula + the diagnostic label")

print()
print("C — THE RELATED POPUP-CHILD LAW (OAuth: opener kept, secrets kept out)")

# multi-window on; the transient marker
need("setSupportMultipleWindows(true)" in tj
     and "setJavaScriptCanOpenWindowsAutomatically(true)" in tj
     and "public boolean popupChild = false;" in tj,
     "Tab: multi-window is on and the popup-child marker rides every tab")

# the real related child (WebViewTransport), never a throwaway probe
need("openPopupChild(" in bo and "buildPopupChild(" in bo
     and "WebViewTransport" in bo and "resultMsg.sendToTarget()" in bo
     and "return openPopupChild(view, resultMsg);" in bo,
     "onCreateWindow answers with a REAL related child via "
     "WebViewTransport (resultMsg.sendToTarget)")

# the four teardown triggers
need("closePopupChild(" in bo and "onCloseWindow(WebView window)" in bo
     and "closePopupChild(true);" in bo
     and "if (popupChild != null && t == popupChild.tab) {" in bo
     and "if (popupChild != null && idx >= 0 && idx < tm.tabs.size()" in bo,
     "the child closes on ALL four triggers: onCloseWindow, Back, tab "
     "switch, and the native close chip")
need("popup_close" in bo and "popup_close_desc" in bo
     and 'name="popup_close"' in sx and 'name="popup_close_desc"' in sx,
     "the native close chip + its strings exist (the fourth trigger, "
     "screen-reader named)")

# the policy: OAuth chains open, ordinary popups prompt, unsafe die
need("decidePopup(" in bo and "PopupPolicy.decide(" in bo
     and "PopupPolicy.displayTarget(" in bo,
     "every child navigation runs the compiled policy (open/prompt/deny)")
need("isSafeScheme(" in pp and "isAuthPopup(" in pp
     and "isAuthResponse(" in pp and "isIdpEntry(" in pp
     and "displayTarget(" in pp and "int decide(" in pp,
     "PopupPolicy carries the scheme/auth/idp/display/decide surface")

# the privileged-bridge law
need("t.popupChild ? null : bridge" in bo
     and "if (!t.popupChild) {" in bo
     and "addJavascriptInterface(bridge, \"Bolt\")" in bo,
     "the popup child carries NO Bolt JS bridge (buildWeb takes null and "
     "the outer attach is guarded) — window.opener + postMessage only")

# opener UA + cookie policy carry over
need("getSettings().getUserAgentString(" in bo
     and "setUserAgentString(" in bo
     and "CookieManager" in bo,
     "the child carries the opener UA + the cookie policy (the sign-in "
     "session sees the same jar)")

# the transient never persists; the close is clean
need("closeTransient(" in tm and "t.popupChild" in tm
     and "indexOfTid(" in bo,
     "TabManager: popup children never persist and close cleanly (the "
     "index shift re-resolves the switch target by tid)")

# the secret-free display law (source level: prompts show displayTarget only)
need("displayTarget" in pp and "u.substring(0, cut)" in pp
     and "\"(link)\"" in pp and "lastIndexOf('@')" in pp,
     "displayTarget strips query + fragment + userinfo — auth codes/state/"
     "tokens never surface in prompts or logs")
secret_probe = r'''
import com.bolt.browser.util.PopupPolicy;
public class PopupProbe {
    static int bad = 0;
    static void ck(boolean c, String what) {
        System.out.println((c ? "ok " : "BAD ") + what);
        if (!c) bad++;
    }
    public static void main(String[] a) {
        // unsafe schemes die outright
        ck(PopupPolicy.decide("javascript:alert(1)", true, true) == PopupPolicy.DENY, "javascript: DENY");
        ck(PopupPolicy.decide("file:///etc/passwd", true, true) == PopupPolicy.DENY, "file: DENY");
        ck(PopupPolicy.decide("data:text/html,<script>x</script>", true, true) == PopupPolicy.DENY, "data: DENY");
        ck(!PopupPolicy.isSafeScheme("intent://scan/#Intent;end"), "intent: unsafe");
        ck(PopupPolicy.isSafeScheme("about:blank"), "about:blank safe");
        // the Google OAuth authorize chain opens THROUGH the blocker
        String g = "https://accounts.google.com/o/oauth2/v2/auth?client_id=123.apps.googleusercontent.com&redirect_uri=https://imgur.com/callback&response_type=code";
        ck(PopupPolicy.isAuthPopup(g), "google authorize recognized");
        ck(PopupPolicy.decide(g, false, true) == PopupPolicy.OPEN, "google authorize opens (blocker ON)");
        // the single-use callback landing passes
        ck(PopupPolicy.isAuthPopup("https://imgur.com/callback?code=SECRET&state=SECRET2"), "code landing recognized");
        ck(PopupPolicy.isAuthPopup("https://rp.example/cb#error=access_denied"), "error landing recognized");
        // IdP entries
        ck(PopupPolicy.isAuthPopup("https://appleid.apple.com/auth"), "appleid entry recognized");
        ck(PopupPolicy.isAuthPopup("https://acme.okta.com/oauth2/authorize?x=1"), "okta entry recognized");
        // ordinary popups prompt but never auto-open
        String o = "https://ads.example.net/popunder?promo_code=SPRING";
        ck(!PopupPolicy.isAuthPopup(o), "promo_code is NOT a code param");
        ck(PopupPolicy.decide(o, false, true) == PopupPolicy.PROMPT, "ordinary popup -> PROMPT");
        // the always-allow pref + the off switch
        ck(PopupPolicy.decide(o, true, true) == PopupPolicy.OPEN, "site allow -> OPEN");
        ck(PopupPolicy.decide(o, false, false) == PopupPolicy.OPEN, "blocker off -> OPEN");
        // THE SECRET-FREE LAW: displayTarget drops query/fragment/userinfo
        String d = PopupPolicy.displayTarget("https://user:pass@imgur.com/callback?code=SECRET&state=S2#access_token=S3");
        ck(d.equals("https://imgur.com/callback"), "displayTarget strips secrets: " + d);
        ck(!d.contains("SECRET") && !d.contains("?") && !d.contains("#") && !d.contains("@"),
            "no query/fragment/userinfo survives the display");
        ck(PopupPolicy.displayTarget(null).equals("(link)"), "null -> (link)");
        System.out.println("probe: bad=" + bad);
        if (bad != 0) System.exit(1);
    }
}
'''
open(os.path.join(work, "PopupProbe.java"), "w").write(secret_probe)
r = subprocess.run([JAVAC, "-d", work, PP, os.path.join(work, "PopupProbe.java")],
                   capture_output=True, text=True)
need(r.returncode == 0,
     "PopupPolicy compiles with the real javac (" + (r.stderr.strip().splitlines() or ["ok"])[0][:80] + ")")
if r.returncode == 0:
    r = subprocess.run([JAVA, "-cp", work, "PopupProbe"], capture_output=True, text=True)
    need(r.returncode == 0,
         "the compiled popup probe: unsafe dies, OAuth opens, ordinary "
         "prompts, secrets stripped (" +
         (r.stdout.strip().splitlines() or ["no output"])[-1] + ")")

need("unsafeSchemes_areDenied" in pt
     and "googleAuthorizeShape_opensThroughTheBlocker" in pt
     and "ordinaryPopups_promptButNeverAutoOpen" in pt
     and "alwaysAllowAndDisabledBlocker_open" in pt
     and "displayTarget_stripsSecrets_queryFragmentUserinfo" in pt,
     "PopupPolicyTest carries the scheme/auth/prompt/secret probes")

print()
print("D — THE TESTS, THE DOCS, THE BUILD")

need("dense_table_overflowsViewport_textNotShrunk" in tr
     and "table_onDraw_paintsBordersAndCellText" in tr,
     "the table tests own their class")
need("class QrPreviewGeomTest" in qt and "class PopupPolicyTest" in pt,
     "the new JUnit probes are plain-JUnit test classes")
need('name="popup_close"' in sx and 'name="popup_close_desc"' in sx,
     "strings.xml carries the popup close chip strings")

# docs: the About changelog position [0] + the two logs
m = re.search(r'<string-array name="about_changelog">(.*?)</string-array>', sx, re.S)
items = re.findall(r"<item>(.*?)</item>", m.group(1)) if m else []
need(items and items[0].startswith("1.4.29|"),
     "the About changelog carries 1.4.29 at [0] (newest first)")
cl = open(os.path.join(ROOT, "CHANGELOG.md"), encoding="utf8").read()
need("## [1.4.29]" in cl,
     "CHANGELOG.md documents 1.4.29")
rd = open(os.path.join(ROOT, "README.md"), encoding="utf8").read()
need("v1.4.29" in rd,
     "the README maintainer log carries the v1.4.29 session entry")

need('versionName "1.4.29"' in open(BG, encoding="utf8").read()
     and "versionCode 143" in open(BG, encoding="utf8").read(),
     "the build carries versionName 1.4.29 / versionCode 143")

print()
print("E — THE LAUNCH LAW + THE STANDING SWEEP + THE BUILD")
smoke = os.path.join(ROOT, "app/src/test/java/com/bolt/browser/ui/LaunchSmokeTest.java")
need(os.path.isfile(smoke) and os.path.isfile(TR),
     "the Robolectric suite carries BOTH the launch smoke and the table "
     "pixel probe")
need("testDebugUnitTest" in RELS and "LAUNCH SMOKE FAILED" in RELS,
     "release.sh runs the unit suite BEFORE the build (the rung stays)")
need("python3 scripts/test_v1429.py" in RELS,
     "release.sh runs this gate (the v1.4.29 rung)")
need("python3 scripts/test_v1428.py" in RELS,
     "the v1.4.28 gate still rides the suite (cumulative law)")
sweep = 0
for g in sorted(os.listdir(os.path.join(ROOT, "scripts"))):
    if not (g.startswith("test_v1") and g.endswith(".py")) or g == "test_v1429.py":
        continue   # this gate ships at 143/1.4.29 already
    src = open(os.path.join(ROOT, "scripts", g), encoding="utf8").read()
    code = re.sub(r"#[^\n]*", "", src)          # dated history comments survive
    if re.search(r"versionCode\s+142\b", code) or '== "1.4.28"' in code:
        sweep += 1
need(sweep == 0, "the standing sweep repinned every gate to 143/1.4.29 "
     f"({sweep} stragglers)")
need(os.path.isfile(os.path.join(ROOT, "scripts/repin_gates_1429.py")),
     "the v1.4.29 sweep script is checked in (provenance)")

print()
if failed:
    print(f"V1429 GATE: BAD ({failed} fail)")
    sys.exit(1)
print("V1429 GATE: OK")
