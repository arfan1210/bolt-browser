#!/usr/bin/env python3
"""test_v1429.py — the v1.4.29 gate: THE REACHABILITY ROUND.

Three fixes about content and flows that could not be reached:

A  version pins (143 / 1.4.29, the About changelog head, the CHANGELOG
   section)
B  BOLT AI TABLES — THE NATURAL-WIDTH SCROLLER: the cell type NEVER
   shrinks (the 0.55x corpse), the natural width is measured + exposed,
   the bubble wraps the table in a HorizontalScrollView sized to the
   natural width, the fallback stays; the Robolectric scroll probe pins
   the geometry laws
C  QR PREVIEW — THE VIEW-SPACE TRANSFORM: the matrix starts by UNDOING
   the implicit buffer->view stretch (TextureView.setTransform consumes
   view-local geometry), the diag carries sensor/display/content, the
   v1.4.27 fills law + first-frame re-derive ride unchanged; the PURE
   transcription probe asserts, for portrait + landscape + ALL FOUR
   quarter-turns, both-axes coverage / one-axis-exactly / centered crop —
   and DEMONSTRATES the old buffer-space corpse leaving black regions
D  OAUTH POPUPS — THE RELATED CHILD: multi-window support in buildWeb,
   the WebViewTransport child, the no-bridge child, the UA/cookie
   carry-over, the classifier allowlist, the scheme guard, the clean
   close doors, the no-URL-log law; the REAL classifier methods are
   extracted from Tab.java/BrowserActivity.java/Fx.java and compiled +
   run against a decision table (the parse-gate law)
E  the pipeline: release.sh carries the v1.4.29 rung

House rules: every need() failure counts; any failure exits 1.
"""
import os
import re
import glob
import shutil
import subprocess
import tempfile
import sys
import math

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


ar = read("app/src/main/java/com/bolt/browser/util/AiRender.kt")
qsa = read("app/src/main/java/com/bolt/browser/ui/QrScanActivity.java")
tab = read("app/src/main/java/com/bolt/browser/core/Tab.java")
ba = read("app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
trs = read("app/src/test/java/com/bolt/browser/util/TableScrollTest.java")
trr = read("app/src/test/java/com/bolt/browser/util/TableRenderTest.java")
gradle = read("app/build.gradle")
strings = read("app/src/main/res/values/strings.xml")
chlog = read("CHANGELOG.md")
rels = read("scripts/release.sh")

print("A — version pins")
need("versionCode 146" in gradle and 'versionName "1.4.32"' in gradle,
     "build.gradle pins 146 / 1.4.32")
need('<item>1.4.29|' in strings,
     "the About changelog carries the 1.4.29 line at [0] (newest first)")
i29 = strings.find("<item>1.4.29|")
i28 = strings.find("<item>1.4.28|")
need(0 <= i29 < i28, "the About changelog order: 1.4.29 rides ABOVE 1.4.28")
need("## [1.4.29] — 2026-11-24" in chlog and
     chlog.find("## [1.4.29]") < chlog.find("## [1.4.28]"),
     "CHANGELOG.md carries the [1.4.29] section above [1.4.28]")

print()
print("B — TABLES: THE NATURAL-WIDTH SCROLLER (the no-shrink law)")
# the corpse is GONE: no bounded 0.55x shrink anywhere in the renderer
need("coerceAtLeast(0.55f)" not in ar and "base * f" not in ar,
     "the bounded 0.55x type-shrink is DELETED (the tiny-text corpse)")
need("measureAt(paint, paint.textSize)" in ar,
     "layout() measures at the FULL base size, always (the no-shrink law)")
# the natural width is measured AND exposed
need("fun naturalWidthPx(): Int" in ar
     and "Math.ceil((totalW + 2f).toDouble()).toInt()" in ar,
     "naturalWidthPx() measures + exposes the table's natural content width")
need("fun currentTextSizePx(): Float" in ar,
     "currentTextSizePx() exposes the live type size (the no-shrink probe "
     "surface)")
need("14.5f * resources.displayMetrics.scaledDensity" in ar
     and "private fun baseProbe(): Paint" in ar,
     "one shared base probe paint (14.5sp) feeds measure/draw/naturalWidth")
# the wrapper: natural-width child inside a horizontal viewport
need("private fun addTable(c: Context, box: android.widget.LinearLayout, m: TableMark)" in ar,
     "addTable keeps its v1.4.28 signature (the container interleave is "
     "untouched)")
need("android.widget.HorizontalScrollView(c)" in ar
     and "host.naturalWidthPx()" in ar
     and "android.widget.FrameLayout.LayoutParams(\n                        natural" in ar,
     "addTable wraps the table in a HorizontalScrollView whose child is "
     "sized to the NATURAL width (no clip inside the wrapper)")
need("hs.isVerticalScrollBarEnabled = false" in ar
     and "hs.isHorizontalScrollBarEnabled = true" in ar,
     "the wrapper scrolls horizontally only (vertical scrolling is the "
     "chat's job)")
need("var hostedInScroller: Boolean = false" in ar
     and "host.hostedInScroller = true" in ar
     and "val w = if (hostedInScroller) natural" in ar,
     "a scroller-hosted table MEASURES to its natural width (the wrapper "
     "owns the viewport; maxW caps only direct hosting)")
need("host = tableView(c, m.rows)" in ar
     and "box.addView(host, lp)" in ar,
     "the v1.4.28 direct add stays as the fallback (never eat a table)")
# the v1.4.28 grid laws ride unchanged
need("canvas.drawLine(x, top0 + r * rowH, x + totalW, top0 + r * rowH, line)" in ar
     and "canvas.drawRect(x, top0, x + totalW, top0 + rowH, band)" in ar,
     "the grid laws (rules + header band) ride unchanged")
need("fun splitTables(spanned: Spanned): List<Any> {" in ar
     and "fun fillMarkdown(c: Context, box: android.widget.LinearLayout, md: String)" in ar,
     "the split + container interleave laws ride unchanged (document order)")
# the Robolectric probe: the task's own verify words
need("dense_table_naturalWidthOverflows_andTextNeverShrinks" in trs
     and "assertEquals" in trs and "tv.currentTextSizePx()" in trs,
     "TableScrollTest: the dense table overflows the viewport and the cell "
     "type NEVER shrinks")
need("bubble_wrapsWideTableInHScroll_andScrollReachesLastColumn" in trs
     and "hs.scrollTo(maxScroll, 0)" in trs
     and "hs.getScrollX()" in trs,
     "TableScrollTest: the horizontal scroll REACHES the final columns")
need("normal_table_fitsWithoutScrolling" in trs,
     "TableScrollTest: a normal-sized table still fits without scrolling")
need("@GraphicsMode(GraphicsMode.Mode.NATIVE)" in trr
     and "table_onDraw_paintsBordersAndCellText" in trr,
     "the v1.4.28 pixel probe rides unchanged")

print()
print("C — QR: THE VIEW-SPACE PREVIEW TRANSFORM (v1.4.30 re-point, dated "
      "2026-11-24: the geometry law below is UNCHANGED — theta is now the "
      "COMPENSATED angle, (sensor - display - producer + 720) % 360 plus "
      "the persisted user offset, still always a quarter-turn; the "
      "producer probe + the rotate button are pinned by test_v1430.py)")
# the matrix: the stretch-undo is the FIRST op (view coords -> buffer coords)
# v1.4.31 re-point (2026-11-25): THE MEASURED-IMPLICIT LAW — the undo's
# FORM changed (three modes: measured inverse affine / table cover /
# fill) but the ORDER law is intact: the undo (in whichever mode) is
# still the FIRST ops, then T(-bufCenter) -> R(theta) -> S(cover) ->
# T(center). The measured/table modes pin their own order below.
need(qsa.find("matrix.postScale(undoSx, undoSy);") >= 0
     and qsa.find("matrix.postScale(undoSx, undoSy);") <
     qsa.find("matrix.postTranslate(-bw / 2f, -bh / 2f);") <
     qsa.find("matrix.postRotate(theta);") <
     qsa.find("matrix.postScale(s, s);") <
     qsa.find("matrix.postTranslate(cx, cy);"),
     "the matrix ORDER is the law: undo the implicit mapping (measured/"
     "table/fill) -> center -> rotate -> cover -> center (setTransform "
     "consumes view-local geometry)")
need("setTransform() transforms the" in qsa or
     "TextureView.setTransform() transforms" in qsa,
     "the corpse is named: the old matrix reasoned about raw buffer dims")
# the diag: the field-verify format with sensor/display/content
need('"qr preview view="' in qsa and '" sensor=" + sensorOrientation' in qsa
     and '" display=" + degrees' in qsa and '" theta=" + theta' in qsa
     and '" content=" + Math.round(uw * s) + "x" + Math.round(uh * s)' in qsa
     and '" scale="' in qsa and '(fills ? "" : " MISMATCH")' in qsa,
     "the readable residual: view= buf= sensor= display= theta= content= "
     "scale= (+ MISMATCH only on a real cover failure)")
# the v1.4.27 laws ride unchanged
need("fills = uw * s >= viewW - 1f && uh * s >= viewH - 1f" in qsa,
     "the fills-both-axes assertion rides unchanged (same computation, "
     "now actually honored by the applied matrix)")
need("firstFrameSeen" in qsa and "onSurfaceTextureUpdated" in qsa
     and "applyPreviewTransform(tvPreview.getWidth(), tvPreview.getHeight())" in qsa,
     "the first-delivered-frame re-derive rides unchanged")
need("pinRetried = true" in qsa
     and "try { pinBuffer(); } catch (Throwable ignored) {}" in qsa,
     "the one-shot re-pin ladder rides unchanged")
need("root.addView(tvPreview, new FrameLayout.LayoutParams(-1, -1))" in qsa,
     "the TextureView stays full-screen (MATCH_PARENT both axes)")
need("st.setDefaultBufferSize(bufW, bufH);" in qsa,
     "the buffer pin rides unchanged (the actual camera preview size)")

# ---- the PURE transcription probe (view-space; v1.4.30 dated re-point:
# the cases feed theta EXPLICITLY, and the compensated theta_eff is still
# one of the four quarter-turns — the table holds unchanged) ----
def view_space_matrix(bufw, bufh, vieww, viewh, theta):
    """the content in view space = M(view rect) where
    M = T(center) . S(cover) . R(theta) . T(-bufCenter) . S(bw/vw, bh/vh)
    — composed as the post* sequence applies to a point left to right.
    theta in {0,90,180,270} keeps the rotated rect axis-aligned, so the
    four mapped corners' hull IS the content bounds."""
    uw, uh = (bufw, bufh) if theta % 180 == 0 else (bufh, bufw)
    s = max(vieww / uw, viewh / uh)

    def m(p):
        x, y = p[0] * bufw / vieww, p[1] * bufh / viewh      # undo the stretch
        x, y = x - bufw / 2.0, y - bufh / 2.0                # buffer center
        a = math.radians(theta)
        x, y = x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)
        x, y = x * s + vieww / 2.0, y * s + viewh / 2.0      # cover + center
        return (x, y)

    return s, [m((0, 0)), m((vieww, 0)), m((vieww, viewh)), m((0, viewh))]


def hull(pts):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), max(xs), min(ys), max(ys)


EPS = 1e-6
print("  -- the geometry table: portrait + landscape + ALL FOUR turns --")
cases = [
    ("portrait, sensor 90 / display 0", 1920, 1080, 1080, 2340, 90),
    ("portrait, sensor 90 / display 90", 1920, 1080, 1080, 2340, 0),
    ("portrait, sensor 90 / display 180", 1920, 1080, 1080, 2340, 270),
    ("portrait, sensor 90 / display 270", 1920, 1080, 1080, 2340, 180),
    ("landscape, sensor 90 / display 90", 1920, 1080, 2340, 1080, 0),
    ("landscape, sensor 0 / display 0", 1920, 1080, 2340, 1080, 0),
    ("the 640x480 placeholder (pre-open)", 640, 480, 1080, 2340, 90),
]
for name, bw, bh, vw, vh, theta in cases:
    s, pts = view_space_matrix(bw, bh, vw, vh, theta)
    x0, x1, y0, y1 = hull(pts)
    uw, uh = (bw, bh) if theta % 180 == 0 else (bh, bw)
    covers = x0 <= EPS and x1 >= vw - EPS and y0 <= EPS and y1 >= vh - EPS
    exact_x = abs(x0) < EPS and abs(x1 - vw) < EPS
    exact_y = abs(y0) < EPS and abs(y1 - vh) < EPS
    centered = (abs(abs(x0) - abs(x1 - vw)) < 1e-4
                and abs(abs(y0) - abs(y1 - vh)) < 1e-4)
    aspect = abs((x1 - x0) / (y1 - y0) - uw / uh) < 1e-6
    need(covers, f"{name}: the applied content covers BOTH axes")
    need(exact_x or exact_y,
         f"{name}: one axis fits EXACTLY (aspect-true cover, never squish)")
    need(centered, f"{name}: the crop is CENTERED (equal margins on the "
                   f"overflow axis)")
    need(aspect, f"{name}: the content keeps the upright buffer aspect")

print("  -- the corpse, DEMONSTRATED: the old buffer-space recipe left "
      "black regions --")
def old_buffer_space_content(bufw, bufh, vieww, viewh, theta):
    """the OLD matrix (v1.4.28): no stretch-undo; applied to the VIEW
    rect (what setTransform actually transforms), the content shrank to
    part of the view."""
    uw, uh = (bufw, bufh) if theta % 180 == 0 else (bufh, bufw)
    s = max(vieww / uw, viewh / uh)

    def m(p):
        x, y = p[0] - vieww / 2.0, p[1] - viewh / 2.0
        a = math.radians(theta)
        x, y = x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)
        x, y = x * s + vieww / 2.0, y * s + viewh / 2.0
        return (x, y)

    return [m((0, 0)), m((vieww, 0)), m((vieww, viewh)), m((0, viewh))]


x0, x1, y0, y1 = hull(old_buffer_space_content(1920, 1080, 1080, 2340, 90))
need(y1 < 2340 - 1 or y0 > 1,
     f"the old recipe DEMONSTRATED: portrait 1080x2340 theta=90 leaves "
     f"y [{y0:.0f}..{y1:.0f}] — a black region of "
     f"{min(y0, 2340 - y1):.0f}px+ (the reported corpse shape)")

print()
print("D — OAUTH POPUPS: THE RELATED CHILD (the Imgur Google sign-in corpse)")
# buildWeb: multi-window ON, automatic JS popups still blocked
need("s.setJavaScriptCanOpenWindowsAutomatically(false);" in tab
     and "s.setSupportMultipleWindows(true);" in tab
     and tab.find("s.setJavaScriptCanOpenWindowsAutomatically(false);") <
     tab.find("s.setSupportMultipleWindows(true);") <
     tab.find("s.setAllowFileAccess(true);"),
     "buildWeb enables setSupportMultipleWindows(true) — right after the "
     "still-blocked automatic-JS-popup flag (onCreateWindow is LIVE now)")
# the transport: a REAL related child, not a throwaway probe
need("WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;" in ba
     and "transport.setWebView(child);" in ba
     and "resultMsg.sendToTarget();" in ba,
     "onCreateWindow hands a REAL related child WebView to Chromium via "
     "WebViewTransport (window.opener/postMessage/window.close survive)")
need("WebView probe = new WebView" not in ba,
     "the throwaway-probe popup path is retired")
# the child: NO privileged bridge, minimal build
bpc_start = ba.find("    private WebView buildPopupChild(WebView opener) {")
bpc_end = ba.find("    /** the child's navigation policy + classifier. */")
need(0 < bpc_start < bpc_end,
     "buildPopupChild is where the gate expects it")
bpc = ba[bpc_start:bpc_end]
need("addJavascriptInterface" not in bpc and "BoltWeb" not in bpc
     and "FrameHooks" not in bpc and "ShadowGate" not in bpc
     and "PasskeyCompat" not in bpc,
     "the child is a PLAIN WebView: NO Bolt bridge, no BoltWeb subclass, "
     "no document-start hook injection rides into it")
need("setUserAgentString(ua);" in bpc
     and "UaIdentity.applyFor(this, s, ua);" in bpc,
     "the child carries the opener's UA + matching client-hints metadata "
     "(the identity-agreement law)")
need("cm.setAcceptThirdPartyCookies(w, true);" in bpc,
     "the child rides the shared cookie jar with third-party cookies "
     "accepted (the consent chain is cross-origin by definition)")
need("new PopupChildClient()" in bpc and "new PopupChildChrome()" in bpc,
     "the child wears its own client pair (classifier + close door)")
# the classifier: the SAME shape law as the v1.3.100 auth-chain hold
need("Tab.isAuthRequestUrl(popupUrl) || Tab.isAuthResponseUrl(popupUrl)" in ba,
     "the popup allow-shape reuses Tab's OAuth classifiers (one law, no "
     "drift between the UA hold and the popup allowlist)")
need("public static boolean isAuthRequestUrl(String url) {" in tab
     and "public static boolean isAuthResponseUrl(String url) {" in tab,
     "Tab's auth classifiers are public static now (the popup classifier "
     "reaches them)")
# the policy: OAuth rides, ordinary prompts, unsafe schemes refused
need('url.startsWith("about:"))) {' in ba
     and '"popup child scheme blocked"' in ba,
     "the scheme guard: only http(s)/about: run in the child (intent:/ "
     "javascript:/ data:/ file: refused — the log names NO URL)")
need("promptOrdinaryPopup(pu);" in ba and "prefs.popupBlock()" in ba,
     "ordinary popups under the blocker keep the v1.4.28 prompt (Allow / "
     "Always allow / Block)")
need("ensurePopupChildVisible();" in ba
     and ba.count("ensurePopupChildVisible();") >= 3,
     "classified OAuth chains ride the VISIBLE child (attach on "
     "classification, incl. the belt-and-braces onPageStarted leg)")
# the close doors
need("private void closePopupChild() {" in ba
     and "c.destroy();" in ba,
     "ONE teardown: detach + stopLoading + destroy, disarms the timer")
need(ba.count("closePopupChild();") >= 6,
     "every close door lands in the one teardown: onCreateWindow retire, "
     "onCloseWindow, popup onCloseWindow, Back, tab switch, close chip, "
     "onDestroy, the timeout")
obp = ba.find("    public void onBackPressed() {")
back_at = ba.find("if (popupChild != null) {", obp)
goback_at = ba.find("Tab t = activeTab();", obp)
need(obp >= 0 and 0 < back_at < goback_at,
     "Back is the CHILD's first: a history step inside the flow, else the "
     "clean close (the opener beneath is visible again)")
need("try { closePopupChild(); } catch (Throwable ignored) {}" in ba
     and ba.count("try { closePopupChild(); } catch (Throwable ignored) {}") >= 3,
     "tab switch + onDestroy + the timeout guard close the child too")
# the close chip: the accessible native close action
need("chip.setContentDescription(getString(R.string.close));" in ba
     and "chip.setOnClickListener(v -> closePopupChild());" in ba,
     "the accessible native close action (a content-described X chip on "
     "top of the child)")
# the no-secret law: no URL/code/state/token ever rides a log line
popup_diag_urls = re.findall(
    r'Diag\.log\((?:BrowserActivity\.this|this),\s*"popup[^"]*"', ba)
need(all(not re.search(r'(url|pu|code|state|token)\b', m.split(",")[1])
         for m in popup_diag_urls),
     "the popup Diag lines name lifecycle events only — NO url/code/state/"
     "token ever rides a log")
need("Diag.log" in ba and not re.search(
    r'Diag\.log\([^)]*\+\s*(pu|url)\b', ba),
    "no Diag line concatenates a popup URL variable")

print("  -- the functional probe (the parse-gate law: run the REAL "
      "classifier) --")
# extract the REAL methods exactly as shipped
t_start = tab.find("    public static boolean isAuthRequestUrl(String url) {")
t_end = tab.find("    /** v1.3.40 — PER-SITE PAGE ZOOM")
b_start = ba.find("    static boolean isPopupOAuthUrl(String openerUrl, String popupUrl) {")
b_end = ba.find("    /** the child WebView: minimal settings")
javac = shutil.which("javac")
if (t_start < 0 or t_end <= t_start or b_start < 0 or b_end <= b_start
        or not javac):
    need(False, "the classifier extraction anchors + javac are present")
else:
    tab_methods = tab[t_start:t_end]
    popup_method = ba[b_start:b_end]
    popup_method = (popup_method
                    .replace("Tab.isAuthRequestUrl(", "isAuthRequestUrl(")
                    .replace("Tab.isAuthResponseUrl(", "isAuthResponseUrl(")
                    .replace("Fx.baseDomain(", "baseDomain(")
                    .replace("Fx.hostOf(", "hostOf("))
    fx = read("app/src/main/java/com/bolt/browser/util/Fx.java")
    fx_start = fx.find("    public static String baseDomain(String host) {")
    fx_mid = fx.find("    public static String hostOf(String url) {")
    fx_end = fx.find("\n}", fx_mid)
    if fx_start < 0 or fx_mid < 0 or fx_end <= fx_mid:
        need(False, "the Fx helpers are where the gate expects them")
    else:
        # baseDomain needs the TWO_PART_TLDS set — the probe URLs exercise
        # single-part TLDs only, so a minimal inert stub stands in
        helpers_stub = (
            "    static final java.util.Set<String> TWO_PART_TLDS = "
            "new java.util.HashSet<>(java.util.Arrays.asList(\n"
            '            "co.uk", "com.au", "co.jp", "co.in", "com.br"));\n\n')
        fx_methods = helpers_stub + fx[fx_start:fx_end]
        harness = """
    public static void main(String[] args) {
        int bad = 0;
        // ---- recognized OAuth chains: ride the child even under the blocker
        String[][] ride = {
            {"https://imgur.com/signin",
             "https://accounts.google.com/o/oauth2/v2/auth?client_id=x&redirect_uri=https%3A%2F%2Fapi.imgur.com%2Foauth2%2Fauthorize&response_type=code"},
            {"https://imgur.com/signin",
             "https://api.imgur.com/oauth2/authorize?code=abc123&state=s1"},
            {"https://imgur.com/signin",
             "https://accounts.google.com/o/oauth2/auth"},
            {"https://imgur.com/signin",
             "https://api.imgur.com/oauth2/authorize#access_token=ey42"},
            {"https://github.com/settings",
             "https://github.com/login/oauth/authorize?client_id=c&redirect_uri=r&response_type=code"},
            {"https://example.com/app",
             "https://appleid.apple.com/auth?client_id=c&response_type=code"},
            {"https://forum.example.org/login",
             "https://forum.example.org/oauth/callback?session=1"},
            {"https://example.com/start",
             "https://sso.example.com/saml?SAMLRequest=fZLL"}};
        // ---- ordinary popups: the prompt path (blocker ON)
        String[][] prompt = {
            {"https://news.example.com/a",
             "https://ads.tracker.io/popup?x=1"},
            {"https://shop.example.com/p",
             "https://other.example.net/win"},
            {"https://example.com/start",
             "https://okta-tenant.example.net/authorize?foo=bar"}};
        // ---- unsafe: never rides, never prompts-rides
        String[][] unsafe = {
            {"https://imgur.com/signin", "intent://foo#Intent;end"},
            {"https://imgur.com/signin", "javascript:opener.postMessage(1)"},
            {"https://imgur.com/signin", "file:///sdcard/x.html"},
            {"https://imgur.com/signin", null},
            {"https://imgur.com/signin", ""}};
        for (String[] c : ride)
            if (!isPopupOAuthUrl(c[0], c[1])) {
                System.out.println("RIDE MISS " + c[1]); bad++; }
        for (String[] c : prompt)
            if (isPopupOAuthUrl(c[0], c[1])) {
                System.out.println("PROMPT FALSE-RIDE " + c[1]); bad++; }
        for (String[] c : unsafe)
            if (c[1] != null && isPopupOAuthUrl(c[0], c[1])) {
                System.out.println("UNSAFE FALSE-RIDE " + c[1]); bad++; }
        // the shape law is HOST-FREE: an unknown IdP with the authorize
        // shape still rides (client_id + redirect_uri/response_type)
        if (!isPopupOAuthUrl("https://example.com",
                "https://idp.unknown.net/authorize?client_id=c&redirect_uri=r&response_type=code")) {
            System.out.println("SHAPE MISS"); bad++; }
        // the first-party law: the opener's OWN base rides
        if (!isPopupOAuthUrl("https://blog.example.co.uk/post",
                "https://blog.example.co.uk/signin?x=1")) {
            System.out.println("FIRST-PARTY MISS"); bad++; }
        if (bad > 0) { System.out.println("PROBE FAILURES: " + bad);
            System.exit(1); }
        System.out.println("probe: all green");
    }
}
"""
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "Probe.java")
            with open(src, "w", encoding="utf-8") as f:
                f.write("import java.util.Arrays;\n"
                        + "public class Probe {\n"
                        + tab_methods + "\n"
                        + fx_methods + "\n"
                        + popup_method + harness)
            r = subprocess.run([javac, src], capture_output=True, text=True)
            if r.returncode != 0:
                need(False, "the extracted popup classifier compiles: "
                            + r.stderr[:400])
            else:
                r2 = subprocess.run(["java", "-cp", d, "Probe"],
                                    capture_output=True, text=True)
                need(r2.returncode == 0 and "all green" in r2.stdout,
                     "the REAL classifier passes the decision table "
                     "(OAuth shapes + IdP hosts + first-party ride; "
                     "ordinary popups prompt; unsafe schemes never ride)")

print()
print("E — the pipeline")
need("test_v1429.py" in rels,
     "release.sh carries the v1.4.29 rung")
need("testDebugUnitTest" in rels and "LAUNCH SMOKE FAILED" in rels,
     "the launch law rides (the whole unit suite incl. the Robolectric "
     "probes runs before any build)")

print()
if fails:
    print(f"v1.4.29 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.4.29 gate: all green")
