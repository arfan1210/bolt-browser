#!/usr/bin/env python3
"""test_v1430.py — the v1.4.30 gate: THE FIELD ROUND.

Five fixes + the doc compression, all from the maintainer's field
read-backs on the Realme (v1.4.29):

A  version pins (144 / 1.4.30, the About changelog head, the CHANGELOG
   section)
B  QR PREVIEW — THE PRODUCER-TRANSFORM COMPENSATION + THE USER'S
   ROTATION: the producer probe (PURE, compiled from the REAL Java:
   four quarter-turns x both flip signs + identity + mirror + junk),
   the compensated theta (sensor - display - producer, + the persisted
   user offset), the rotate button (tap +90 persisted per sensor/display
   pair, long-press reset, the badge), the diag line with producer=/
   offset= (MISMATCH only on a real cover failure); the v1.4.29
   view-space matrix laws ride unchanged
C  OAUTH POPUPS — THE RETURN LEG: the flowCompleting mark (the RP
   callback on the opener's base), the ~6s return watchdog, the
   left-RP act, ANY close refreshes the opener (reload-when-RP /
   loadUrl-the-base), the navigation-aware idle retire, the no-URL diag
   law (base domains only); buildPopupChild keeps JS + DOM storage +
   third-party cookies; the PURE returnDecision + secondPopupPolicy
   decision tables compiled from the REAL Java
D  BOLT AI — THE WEB LEGS: the web pref default OFF + the panel Web
   chip, the L1 google_search tools riding the CHAT body ONLY (never
   vision/translate), the groundingSources/sourcesTail PURE probes
   (transcribed), the honest refusal ladder (groundRefusal), the L2
   keyless ladder (DDG html -> DDG lite -> Bing) with the PURE
   parseResults/htmlToText/stripTags/decodeEntities/unwrapRedirect/
   searchQueryFrom transcriptions, the once-per-process confirm, the
   never-incognito law (aiWebAllowed), the key/query-never-in-diag law
E  CHAT TABLES — SCROLL PRESERVATION + COPY-THROUGH: the idempotent
   re-fill (no duplicated children), the scrollX capture/restore by
   table index, the grid + wrapper never claim click/long-click; the
   TableScrollTest probes pin the geometry
F  POPUP HARDENING — the same-site destination pref (default OFF =
   overlay; new-tab fires at the FIRST classified navigation only),
   the NETWORK-PASS-ONLY intercept (no JS injection, body carriers
   untouched), the settings row
G  the pipeline (release.sh carries the v1.4.30 rung) + the README
   compression (<= 60 KB target — WARN-only cap; the laws, the
   known-good numbers, the open read-backs, the env lessons and the
   wipe story survive)

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


qsa = read("app/src/main/java/com/bolt/browser/ui/QrScanActivity.java")
ba = read("app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
ab = read("app/src/main/java/com/bolt/browser/util/AiBridge.kt")
bai = read("app/src/main/java/com/bolt/browser/util/BoltAi.kt")
ar = read("app/src/main/java/com/bolt/browser/util/AiRender.kt")
trs = read("app/src/test/java/com/bolt/browser/util/TableScrollTest.java")
tab = read("app/src/main/java/com/bolt/browser/core/Tab.java")
gradle = read("app/build.gradle")
strings = read("app/src/main/res/values/strings.xml")
chlog = read("CHANGELOG.md")
rels = read("scripts/release.sh")
settings = read("app/src/main/java/com/bolt/browser/ui/SettingsItemsActivity.java")
readme = read("README.md")

print("A — version pins")
need("versionCode 146" in gradle and 'versionName "1.4.32"' in gradle,
     "build.gradle pins 146 / 1.4.32")
need('<item>1.4.30|' in strings,
     "the About changelog carries the 1.4.30 line at [0] (newest first)")
i30 = strings.find("<item>1.4.30|")
i29 = strings.find("<item>1.4.29|")
need(0 <= i30 < i29, "the About changelog order: 1.4.30 rides ABOVE 1.4.29")
need("## [1.4.30] — 2026-11-24" in chlog and
     chlog.find("## [1.4.30]") < chlog.find("## [1.4.29]"),
     "CHANGELOG.md carries the [1.4.30] section above [1.4.29]")

print()
print("B — QR: THE PRODUCER-TRANSFORM COMPENSATION + THE USER'S ROTATION")
# the producer probe: reads the transform matrix TextureView already applies
need("st.getTransformMatrix(stMatrix);" in qsa
     and "extractProducerRotation(stMatrix);" in qsa,
     "the delivered frame probes the PRODUCER transform (the matrix "
     "TextureView already applies internally)")
need("static int extractProducerRotation(float[] m) {" in qsa
     and "Math.atan2((double) m[1], (double) m[0])" in qsa,
     "extractProducerRotation: atan2(m[1], m[0]) snapped to quarter-turns")
need("if (Double.isNaN(deg)) return 0;" in qsa,
     "the NaN guard (a singular matrix reads as 0, never crashes)")
# the compensated angle + the user override ADD LAST
# v1.4.32 re-point (2026-11-26): THE BASE-ANGLE LAW split the angle into
# the immutable device base (baseAngleFor) + the additive user offset
# (qrUserOffset.*, default 0) — the compensation (producer subtracted)
# and the user's-last-word law ride UNCHANGED inside the new formula; see
# scripts/test_v1432.py section B for the base table + the separation
# and migration pins.
need("(sensorOrientation - degrees - producerRot + base" in qsa
     and "+ userOffset + 720) % 360" in qsa,
     "theta is COMPENSATED: sensor - display - producer + base + "
     "userOffset (the producer's rotation is already on screen — "
     "subtract, never add again; the base is the device table's, the "
     "offset is the user's)")
need("+ userOffset + 720) % 360" in qsa,
     "the user override ADDS LAST (the user sees the real render — their "
     "tap wins over every heuristic)")
# the re-probe arms on the first frame and after every re-pin
need("probeProducer = true;" in qsa
     and qsa.count("probeProducer = true;") >= 2
     and "if (probeProducer) {" in qsa,
     "the probe arms on the first frame AND after every re-pin (a fresh "
     "buffer may carry a fresh producer)")
# the user override: persist per (sensor, display) pair
# v1.4.32 re-point (2026-11-26): the offset key moved to qrUserOffset.*
# (the old qrRotOffset.* held the CONFLATED angle — seed + taps — and is
# never read again; the device base rides the immutable table, the user
# offset defaults to 0).
need('return "qrUserOffset." + sensorOrientation + "." + degrees;' in qsa,
     "the offset persists per (sensor, display) pair "
     "(qrUserOffset.<sensor>.<degrees>, default 0 — the base is the "
     "table's and is never written)")
need("userOffset = (userOffset + 90) % 360;" in qsa
     and "userOffset = 0;" in qsa and "setOnLongClickListener" in qsa,
     "the rotate button: tap cycles +90 deg (the OFFSET only), long-press "
     "resets the offset to 0 (the device base is untouched)")
need("rotBadge.setText(\"+\" + userOffset + \"°\")" in qsa
     and "rotBadge.setVisibility(View.GONE)" in qsa,
     "the offset pill shows the live USER offset and hides at 0")
need("R.drawable.ic_reload" in qsa and '"Rotate preview"' in qsa,
     "the rotate button rides an existing drawable + a content description")
# the diag: producer= offset= joined the readable residual
need('" producer=" + producerRot + " offset=" + userOffset' in qsa
     and '" theta=" + theta' in qsa and '(fills ? "" : " MISMATCH")' in qsa,
     "the diag carries producer= offset= theta= (MISMATCH still marks ONLY "
     "a real cover failure)")
# the v1.4.29 view-space laws ride unchanged
# v1.4.31 re-point (2026-11-25): the v1.4.29 ORDER law rides, with the
# undo now the measured/table/fill three-mode form (the law: the undo
# is the FIRST ops in every mode; the recipe tail is byte-identical)
need(qsa.find("matrix.postScale(undoSx, undoSy);") >= 0
     and qsa.find("matrix.postScale(undoSx, undoSy);") <
     qsa.find("matrix.postTranslate(-bw / 2f, -bh / 2f);") <
     qsa.find("matrix.postRotate(theta);") <
     qsa.find("matrix.postScale(s, s);") <
     qsa.find("matrix.postTranslate(cx, cy);"),
     "the v1.4.29 view-space matrix ORDER rides unchanged (undo the "
     "implicit mapping -> center -> rotate -> cover -> center)")
need("fills = uw * s >= viewW - 1f && uh * s >= viewH - 1f" in qsa,
     "the v1.4.27 fills-both-axes assertion rides unchanged")
need("st.setDefaultBufferSize(bufW, bufH);" in qsa,
     "the v1.4.26 buffer pin rides unchanged")

print("  -- the compiled REAL probe (the parse-gate law: run the REAL "
      "method) --")
qr_start = qsa.find("    static int extractProducerRotation(float[] m) {")
qr_end = qsa.find("\n    }", qr_start)
javac = shutil.which("javac")
if qr_start < 0 or qr_end <= qr_start or not javac:
    need(False, "the producer-probe extraction anchor + javac are present")
else:
    method = qsa[qr_start:qr_end + len("\n    }")]
    harness = """
    public static void main(String[] args) {
        int bad = 0;
        // the producer transform (column-major 4x4): rotation r rides
        // m[0]=cos, m[1]=sin; the Y-FLIP rides m[5] (its sign must be
        // ignored — a flip is not a rotation)
        float[][] mk = new float[16][16];
        double[] angs = {0, 90, 180, 270, 359.9, 0.1, -90, -180};
        for (double a : angs) {
            for (int flip = -1; flip <= 1; flip += 2) {
                float[] m = new float[16];
                double rad = Math.toRadians(a);
                m[0] = (float) Math.cos(rad);
                m[1] = (float) Math.sin(rad);
                m[5] = flip;   // the y-flip sign varies by producer
                m[10] = 1f; m[15] = 1f;
                int got = Probe.extractProducerRotation(m);
                int want = (int) Math.round(a / 90.0);
                want = ((want % 4) + 4) % 4 * 90;
                if (got != want) {
                    System.out.println("ANGLE MISS a=" + a + " flip=" + flip
                            + " got=" + got + " want=" + want); bad++;
                }
            }
        }
        // identity: no transform -> 0
        if (Probe.extractProducerRotation(new float[16]) != 0) {
            System.out.println("IDENTITY MISS"); bad++; }
        // junk: short/null -> 0, never throws
        if (Probe.extractProducerRotation(new float[3]) != 0
                || Probe.extractProducerRotation(null) != 0) {
            System.out.println("JUNK MISS"); bad++; }
        // a mirror (m0<0, m1=0) reads as 180 — a flip of x, not y
        float[] mir = new float[16]; mir[0] = -1f; mir[5] = 1f; mir[15] = 1f;
        if (Probe.extractProducerRotation(mir) != 180) {
            System.out.println("MIRROR MISS got="
                    + Probe.extractProducerRotation(mir)); bad++; }
        if (bad > 0) { System.out.println("PROBE FAILURES: " + bad);
            System.exit(1); }
        System.out.println("probe: all green");
    }
}
"""
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Probe.java")
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Probe {\n" + method + "\n" + harness)
        r = subprocess.run([javac, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the extracted producer probe compiles: "
                        + r.stderr[:300])
        else:
            r2 = subprocess.run(["java", "-cp", d, "Probe"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL producer probe: 4 quarter-turns x both flips + "
                 "identity + junk + mirror all read correctly")

print()
print("C — OAUTH POPUPS: THE RETURN LEG (the Imgur come-home corpse)")
# the mark
need("Tab.isAuthResponseUrl(url)" in ba
     and "childBase.equals(openerBase)" in ba
     and "popupFlowCompleting = true;" in ba,
     "THE MARK: the RP callback (isAuthResponseUrl on the OPENER's base) "
     "flags flowCompleting")
# the watchdog
need("popupReturnWatchdogR" in ba and "ui.postDelayed(popupReturnWatchdogR, 6000)"
     in ba and "ui.removeCallbacks(popupReturnWatchdogR);" in ba,
     "the ~6s RETURN WATCHDOG arms at the mark and disarms at the teardown")
# the left-RP act + the decision
need('returnDecision(childBase, openerBase, true, false)' in ba
     and '"close-refresh".equals(' in ba,
     "THE ACT: a commit that leaves the RP base closes + refreshes "
     "(the decision table decides)")
# any close refreshes
need("boolean completing = popupFlowCompleting;" in ba
     and "if (completing) {" in ba and "refreshOpenerAfterReturn(rp);" in ba,
     "ANY close of a completing child (window.close, chip, Back, watchdog, "
     "left-RP) refreshes the opener — the one teardown carries it")
need("t.web.reload();" in ba and 't.web.loadUrl("https://" + rp);' in ba,
     "the opener refresh: reload when the tab still IS the RP, else "
     "loadUrl the RP base")
need("isFinishing() || isDestroyed()" in ba,
     "the refresh guards a dying activity")
# the navigation-aware idle retire
need("popupNavigated = true;" in ba
     and "ui.removeCallbacks(popupTimeoutR);" in ba
     and "!popupChildVisible && !popupNavigated)" in ba,
     "the idle retire went NAVIGATION-AWARE: a real navigation cancels the "
     "bounded wait; only a truly idle about:blank child retires")
# the no-secret law: base domains only, NEVER a URL/code/state
need('"popup return door=mark rp="' in ba
     and '"popup return door=left-rp rp="' in ba
     and '"popup return door=watchdog rp="' in ba
     and '"popup return door=close rp="' in ba,
     "the return diag lines name the door + the RP base")
ret_diag = re.findall(r'Diag\.log\([^,]+,\s*\n?\s*"popup return[^"]*"', ba)
need(all(not re.search(r'(url|code|state|token)', m) for m in ret_diag),
     "no return diag line ever carries a URL/code/state/token")
need("popupOpenerUrl == null ? \"\" : popupOpenerUrl" in ba,
     "the opener base derives from the captured opener URL (never logged)")
# buildPopupChild: the consent machinery rides unchanged (verified + pinned)
bpc_start = ba.find("    private WebView buildPopupChild(WebView opener) {")
bpc_end = ba.find("    /** the child's navigation policy + classifier. */")
bpc = ba[bpc_start:bpc_end] if 0 < bpc_start < bpc_end else ""
need("s.setJavaScriptEnabled(true);" in bpc
     and "s.setDomStorageEnabled(true);" in bpc
     and "cm.setAcceptThirdPartyCookies(w, true);" in bpc,
     "buildPopupChild keeps JS + DOM storage + third-party cookies (the "
     "consent chain's machinery, verified)")
need("addJavascriptInterface" not in bpc,
     "the child stays bridge-free")
# the child's network-pass-only intercept
need("shouldInterceptRequest(WebView v,\n                WebResourceRequest request)"
     in ba.replace("public WebResourceResponse shouldInterceptRequest(WebView v,\n                WebResourceRequest request)",
                   "shouldInterceptRequest(WebView v,\n                WebResourceRequest request)")
     or "shouldInterceptRequest(WebView v," in ba,
     "the child intercepts requests (the network pass)")
need("if (request.isForMainFrame()) return null;" in ba
     and "!\"GET\".equals(method)\n                        && !\"HEAD\".equals(method)" in ba,
     "the network pass never touches the main frame or a body carrier "
     "(the v1.3.84 law)")
need("FilterEngine.get().blocked(url, pageHost,\n                        FilterEngine.Type.OTHER, null)" in ba,
     "the network pass rides the ENGINE's judgment, OTHER type, the child's "
     "own host only — no JS injection, no cosmetic engine")

print("  -- the compiled REAL decision tables --")
rd_start = ba.find("    static String returnDecision(String childBase, String openerBase,")
rd_end = ba.find("\n    }", rd_start)
sp_start = ba.find("    static String secondPopupPolicy(boolean flowCompleting, boolean haveChild) {")
sp_end = ba.find("\n    }", sp_start)
if rd_start < 0 or rd_end <= rd_start or sp_start < 0 or sp_end <= sp_start or not javac:
    need(False, "the decision-table extraction anchors + javac are present")
else:
    m1 = ba[rd_start:rd_end + len("\n    }")]
    m2 = ba[sp_start:sp_end + len("\n    }")]
    harness = """
    public static void main(String[] args) {
        int bad = 0;
        // returnDecision: watch until completing; leave-RP acts; same-RP
        // waits for the watchdog; blank bases never act; closed-in-time
        // belongs to the close path
        String[][] watchNotCompleting = {
            {"imgur.com", "imgur.com"},          // still ON the RP
            {"", "imgur.com"},                   // blank child base
            {"imgur.com", ""},                   // blank opener base
            {null, "imgur.com"},
            {"imgur.com", null},
            {"accounts.google.com", "imgur.com"} // pre-mark hops watch
        };
        for (String[] w : watchNotCompleting)
            if (!"watch".equals(Dec.returnDecision(w[0], w[1], false, false))) {
                System.out.println("WATCH MISS " + w[0]); bad++; }
        if (!"watch".equals(Dec.returnDecision("imgur.com", "imgur.com", true, false))) {
            System.out.println("SAME-RP MISS"); bad++; }
        if (!"watch".equals(Dec.returnDecision("imgur.com", "imgur.com", true, true))) {
            System.out.println("CLOSED-IN-TIME MISS"); bad++; }
        if (!"close-refresh".equals(Dec.returnDecision(
                "accounts.google.com", "imgur.com", true, false))) {
            System.out.println("LEFT-RP MISS"); bad++; }
        if (!"close-refresh".equals(Dec.returnDecision(
                "google.com", "imgur.com", true, false))) {
            System.out.println("LEFT-RP MISS 2"); bad++; }
        // secondPopupPolicy: build / replace / refuse
        if (!"build".equals(Dec.secondPopupPolicy(false, false))
                || !"build".equals(Dec.secondPopupPolicy(true, false))) {
            System.out.println("BUILD MISS"); bad++; }
        if (!"replace".equals(Dec.secondPopupPolicy(false, true))) {
            System.out.println("REPLACE MISS"); bad++; }
        if (!"refuse".equals(Dec.secondPopupPolicy(true, true))) {
            System.out.println("REFUSE MISS"); bad++; }
        if (bad > 0) { System.out.println("PROBE FAILURES: " + bad);
            System.exit(1); }
        System.out.println("probe: all green");
    }
}
"""
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "Dec.java")
        with open(src, "w", encoding="utf-8") as f:
            f.write("public class Dec {\n" + m1 + "\n" + m2 + "\n" + harness)
        r = subprocess.run([javac, src], capture_output=True, text=True)
        if r.returncode != 0:
            need(False, "the decision tables compile: " + r.stderr[:300])
        else:
            r2 = subprocess.run(["java", "-cp", d, "Dec"],
                                capture_output=True, text=True)
            need(r2.returncode == 0 and "all green" in r2.stdout,
                 "the REAL returnDecision + secondPopupPolicy pass their "
                 "decision tables")

print()
print("D — BOLT AI: THE WEB LEGS")
# the pref + the chip
need('fun webEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("web", false)'
     in ab and "fun setWebEnabled(ctx: Context, on: Boolean)" in ab,
     "the web pref lives on bolt_ai, DEFAULT OFF")
need('chip(webLabel(a))' in bai and '"Web: On" else "Web: Off"' in bai
     and "AiBridge.setWebEnabled(a, now)" in bai,
     "the panel Web chip toggles + relabels")
# the L1 tools ride the CHAT body only
need('body.put("tools", JSONArray().put(\n                    JSONObject().put("google_search", JSONObject())))' in ab,
     "L1: the chat body carries google_search grounding")
need(ab.count("google_search") == 3,
     "google_search appears exactly three times (the doc, the refusal "
     "detector, the body) — the vision/translate bodies NEVER grow tools")
need("geminiChatPost(ctx, mdl, system, history, true, attaches, web)" in ab,
     "the tools ride geminiChatPost only (the chat builder — vision/"
     "translate never reach it)")
# the grounding parse + tail (PURE, transcribed probes below)
need("internal fun groundingSources(body: String): List<Pair<String, String>>" in ab
     and "internal fun sourcesTail(srcs: List<Pair<String, String>>): String" in ab,
     "groundingSources + sourcesTail exist (PURE, probe-pinned)")
need('return out + sourcesTail(srcs)' in ab
     and '"ai web grounded n=" + srcs.size' in ab,
     "a grounded reply carries the numbered Sources tail (+ the diag count)")
# the honest refusal ladder
need("internal fun groundRefusal(err: String = lastError,\n            body: String = lastWebBody): Boolean" in ab,
     "groundRefusal exists (the refusal detector)")
need('"web search unavailable on this key/model"' in ab,
     "the honest unavailable error (never silent, never a crash)")
need('"ai web grounding refused — keyless leg"' in ab,
     "the refusal falls to the keyless leg (the dated diag)")
# the L2 ladder
need('"https://html.duckduckgo.com/html/?q=" + enc' in ab
     and '"https://lite.duckduckgo.com/lite/?q=" + enc' in ab
     and '"https://www.bing.com/search?q=" + enc' in ab,
     "L2: the keyless ladder DDG html -> DDG lite -> Bing")
need("internal fun parseResults(html: String): List<WebSource>" in ab
     and "internal fun unwrapRedirect(href: String): String" in ab
     and "internal fun stripTags(s: String): String" in ab
     and "internal fun decodeEntities(s: String): String" in ab
     and "internal fun htmlToText(html: String): String" in ab,
     "the L2 PUREs exist (parseResults/unwrapRedirect/stripTags/"
     "decodeEntities/htmlToText)")
need("fetchText(w.url, 200_000, 15_000)" in ab
     and "fetchText(u, 300_000, 15_000)" in ab,
     "the fetch caps: 15 s timeouts, 200 KB pages, 300 KB search pages")
need('c.setRequestProperty("User-Agent", searchUa())' in ab
     and "PasskeyCompat.chromeUa()" in ab,
     "the ladder rides the house mobile UA")
need("Authorization" not in ab.split("internal fun fetchText")[1].split("}")[0]
     if "internal fun fetchText" in ab else False,
     "the ladder carries NO Authorization header (the key stays out)")
need("internal fun searchQueryFrom(history: List<Msg>): String" in ab,
     "searchQueryFrom exists (the last non-assistant turn, block-stripped)")
need("internal fun buildSourcesBlock(sources: List<WebSource>): String" in ab,
     "buildSourcesBlock exists (the numbered SOURCES block)")
# the threading: typed chat only; summarize/explain never web
need("AiBridge.chatX(a, SYSTEM, turns, attaches, true)" in bai
     and "ask(a, msg, null, false)" in bai
     and bai.count("ask(a, msg, null, false)") == 2,
     "the web flag rides TYPED chat only; summarize + explain pass false")
# the confirm + never-incognito
need("@Volatile private var webConfirmed = false" in bai
     and '"ai confirm web"' in bai
     and "setPositiveButton(\"Search the web\")" in bai,
     "the once-per-process web confirm (names what leaves)")
need("PUBLIC web pages are sent over the " in bai
     and "mobile data included" in bai,
     "the confirm names the metered reach too")
need("aiWebAllowed" in bai and "public boolean aiWebAllowed() { return !incognito; }" in ba,
     "the never-incognito law: aiWebAllowed gates the web leg")
# the key/query-never-in-diag law
web_diags = re.findall(r'Diag\.log\((?:ctx|a),\s*"ai web[^"]*"', ab + bai)
need(all(not re.search(r'\bkey\b|q=|query', m) for m in web_diags),
     "no ai-web diag line carries the key or the query text")

print("  -- the transcribed PURE probes (the parse-gate law) --")


def strip_tags(s):
    t = re.sub(r"<[^>]*>", " ", s)
    t = decode_entities(t)
    return re.sub(r"[ \t\r\n]+", " ", t).strip()


def decode_entities(s):
    repl = {"&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"',
            "&#39;": "'", "&#x27;": "'", "&apos;": "'", "&nbsp;": " "}
    for k, v in repl.items():
        s = s.replace(k, v)
    return re.sub(r"&#(\d+);",
                  lambda m: chr(int(m.group(1)))
                  if int(m.group(1)) < 0x110000 else m.group(0), s)


def unwrap_redirect(href):
    i = href.find("uddg=")
    if i >= 0:
        v = href[i + 5:]
        amp = v.find("&")
        if amp >= 0:
            v = v[:amp]
        from urllib.parse import unquote
        dec = unquote(v)
        if dec.startswith("http"):
            return dec
    return href


def parse_results(html):
    out, seen = [], set()
    for m in re.finditer(r'<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>', html,
                         re.I | re.S):
        href = unwrap_redirect(decode_entities(m.group(1)))
        if not href.startswith("http"):
            continue
        host = href.split("/")[2] if "//" in href else ""
        if (not host or host.endswith("duckduckgo.com")
                or host == "bing.com" or host.endswith("bing.com")
                or host.endswith("microsoft.com")):
            continue
        title = strip_tags(m.group(2)).strip()
        if not title or href in seen:
            continue
        seen.add(href)
        tail = html[m.end():m.end() + 1200]
        sm = re.search(
            r'<(?:td|div|a)[^>]+class="[^"]*(?:result-snippet|result__snippet|b_caption|b_caption p)[^"]*"[^>]*>(.*?)</(?:td|div|a)>',
            tail, re.I | re.S)
        snip = strip_tags(sm.group(1)).strip()[:300] if sm else ""
        out.append((title[:200], snip, href))
        if len(out) >= 5:
            break
    return out


ddg_html = ('<div class="result"><a rel="nofollow" class="result__a" '
            'href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Farticle&amp;rut=1">'
            'The &amp; Sign Guide</a>'
            '<a class="result__snippet" href="#">A guide to &amp; signs '
            '&lt;everywhere&gt;</a></div>'
            '<div class="result"><a rel="nofollow" class="result__a" '
            'href="https://direct.example.net/page">Direct Result</a></div>'
            '<a href="https://duckduckgo.com/duck">internal</a>')
rs = parse_results(ddg_html)
need(len(rs) == 2, "DDG html: 2 external results (the redirect wrapper "
                   "unwrapped, the internal link skipped)")
need(rs and rs[0][0] == "The & Sign Guide", "the title tag-strips + "
     "entity-decodes ('The & Sign Guide')")
need(rs and rs[0][2] == "https://example.org/article",
     "the uddg redirect unwraps to the real target")
need(rs and rs[0][1].startswith("A guide to & signs <everywhere>"),
     "the snippet strips + decodes")
lite_html = ('<tr><td><a rel="nofollow" href="https://lite.example.com/x">'
             'Lite Hit</a></td></tr>'
             '<tr><td class="result-snippet">lite &amp; snippet</td></tr>')
rs2 = parse_results(lite_html)
need(len(rs2) == 1 and rs2[0][0] == "Lite Hit"
     and rs2[0][1] == "lite & snippet",
     "DDG lite: the table-row shape parses (title + snippet)")
bing_html = ('<li class="b_algo"><h2><a href="https://bing.example.de/y">'
             'Bing Treffer</a></h2>'
             '<div class="b_caption"><p class="b_lineclamp4">bing snip</p>'
             '</div></li>')
rs3 = parse_results(bing_html)
need(len(rs3) == 1 and rs3[0][0] == "Bing Treffer",
     "Bing: the b_algo shape parses")
# htmlToText (transcribed)
def html_to_text(html):
    t = re.sub(r"(?is)<(script|style|noscript|svg|nav|header|footer|aside)"
               r"[^>]*>.*?</\1>", " ", html)
    t = re.sub(r"<[^>]*>", " ", t)
    t = decode_entities(t)
    t = re.sub(r"[ \t\r]+", " ", t)
    t = re.sub(r"\n{3,}", "\n\n", t)
    return t.strip()[:6000]

page = ("<html><head><title>t</title><style>.x{color:red}</style>"
        "<script>var a='<div>not content</div>';</script></head>"
        "<body><nav>menu menu</nav><h1>Real &amp; Content</h1>"
        "<p>body text here</p><footer>copyright</footer></body></html>")
ht = html_to_text(page)
need("Real & Content" in ht and "body text here" in ht,
     "htmlToText keeps the content")
need("not content" not in ht and "menu menu" not in ht
     and "copyright" not in ht and ".x{color:red}" not in ht,
     "htmlToText drops script/style/nav/footer chrome WHOLE")
need(html_to_text("x" * 9000) == "x" * 6000, "htmlToText caps at 6000 chars")
# searchQueryFrom (transcribed)
block = ("WEB SOURCES (retrieved just now for this question):\n[1] t — u\n"
         "Use these sources to answer; write [n] after facts you took from "
         "source n. If they do not cover the question, say so plainly."
         "\n\nwho invented the ballpoint pen?")
qf = block[block.rfind("say so plainly.") + len("say so plainly."):]
need(qf.replace("\n", " ").strip() == "who invented the ballpoint pen?",
     "searchQueryFrom: a previous SOURCES block strips (web turns never "
     "compound)")
# groundingSources + sourcesTail (transcribed)
import json as _json
body = _json.dumps({"candidates": [{"content": {"parts": [{"text": "ans"}]},
    "groundingMetadata": {"groundingChunks": [
        {"web": {"uri": "https://a.example/1", "title": "A"}},
        {"web": {"uri": "https://a.example/1", "title": "A dup"}},
        {"web": {"uri": "https://b.example/2", "title": ""}},
        {"web": {"uri": "ftp://nope", "title": "Nope"}}]}}]})
gs, seen = [], set()
cands = _json.loads(body)["candidates"]
chunks = cands[0]["groundingMetadata"]["groundingChunks"]
for ch in chunks:
    w = ch.get("web", {})
    uri = w.get("uri", "")
    title = w.get("title", "") or uri
    if not uri.startswith("http") or uri in seen:
        continue
    seen.add(uri)
    gs.append((title, uri))
need(len(gs) == 2 and gs[1][0] == "https://b.example/2",
     "groundingSources: http(s) only, deduped by uri, blank title falls "
     "to the uri (transcribed)")
tail = "\n\nSources:"
for i, p in enumerate(gs):
    tail += "\n[" + str(i + 1) + "] " + p[0][:120] + " — " + p[1]
need("[1] A — https://a.example/1" in tail and "[2]" in tail,
     "sourcesTail: numbered, title — uri (the markdown machinery renders "
     "the links clickable)")
# groundRefusal (transcribed)
def ground_refusal(err, body_text):
    if not body_text:
        return False
    shape = ("HTTP 400" in err or "HTTP 404" in err or "HTTP 429" in err
             or "rate limited" in err or "quota" in err
             or "model not found" in err)
    if not shape:
        return False
    hay = (err + " " + body_text).lower()
    return ("grounding" in hay or "google_search" in hay
            or "tool" in hay or "quota" in hay)

need(ground_refusal("HTTP 400", "google_search tool is not supported") is True,
     "groundRefusal: 400 + grounding talk -> True")
need(ground_refusal("HTTP 400", "API key not valid") is False,
     "groundRefusal: a plain auth failure -> False")
need(ground_refusal("invalid API key", "") is False,
     "groundRefusal: no body -> False")
need(ground_refusal("HTTP 200", "tools") is False,
     "groundRefusal: a non-error shape -> False")

print()
print("E — CHAT TABLES: SCROLL PRESERVATION + COPY-THROUGH")
need("THE IDEMPOTENT RE-FILL + SCROLL PRESERVATION" in ar
     and "box.removeAllViews()" in ar
     and "val savedScroll = ArrayList<Int>()" in ar,
     "fillMarkdown is an idempotent re-fill (capture -> clear -> restore)")
need("if (ch is android.widget.HorizontalScrollView) savedScroll.add(ch.scrollX)"
     in ar and "last.scrollTo(x, 0)" in ar,
     "the scrollX is captured BY TABLE INDEX and restored (the post leg "
     "for the attached world)")
need("!last.isAttachedToWindow" in ar and "last.measure(" in ar,
     "the DETACHED probe leg (measure at the bubble width -> layout -> "
     "scroll: the clamp runs against the real range)")
need("THE COPY-THROUGH LAW" in ar and "isClickable = false" in ar
     and "isLongClickable = false" in ar
     and ar.count("isClickable = false") >= 2,
     "the grid AND its wrapper never claim click/long-click (the raw-source "
     "copy wins through the table)")
need("refill_preservesTableScrollX_andDoesNotDuplicateChildren" in trs
     and "the re-fill is IDEMPOTENT" in trs
     and "table_andWrapper_neverClaimClick_orLongClick" in trs,
     "TableScrollTest pins the re-fill + copy-through laws")

print()
print("F — POPUP HARDENING: THE DESTINATION + THE SETTINGS ROW")
need('prefs.b("popupNewTab", false) && !popupChildVisible' in ba,
     "the new-tab destination fires at the FIRST classified navigation "
     "only (a visible child always rides as v1.4.29)")
need("closePopupChild();\n                    openNewTab(pu);" in ba,
     "the new-tab path retires the child + openNewTab (the shared cookie "
     "jar rides)")
need('"refuse".equals(secondPopupPolicy(\n                        popupFlowCompleting, popupChild != null))' in ba
     and '"popup second window refused during return"' in ba
     and '"Sign-in window is finishing — popup blocked"' in ba,
     "a flowCompleting child is never killed by a second window.open "
     "(refused + toasted)")
need('"Same-site popups open in a new tab"' in settings
     and 'prefs.set("popupNewTab", on)' in settings,
     "the Settings row exists (default OFF = the overlay child)")

print()
print("G — THE PIPELINE + THE README COMPRESSION")
need("test_v1430.py" in rels, "release.sh carries the v1.4.30 rung")
need("testDebugUnitTest" in rels and "LAUNCH SMOKE FAILED" in rels,
     "the launch law rides (the whole unit suite runs before any build)")
need(len(readme) <= 62_000,
     f"the README carries the compression ({len(readme)} bytes; the 60 KB "
     "target + the gate's own slack)")
need("v1.4.30" in readme and "producer" in readme.lower()
     and "return leg" in readme.lower(),
     "the README log carries the v1.4.30 session (QR producer, the return "
     "leg)")
need("3,158,499" in readme and "BCD57EB4" in readme.upper(),
     "the README keeps the known-good numbers (the v1.4.29 APK bytes + the "
     "pinned cert fingerprint)")
need(readme.upper().count("SHA") >= 1 and "keystore" in readme.lower(),
     "the README keeps the signing identity story")
need("wipe" in readme.lower() and "bundle" in readme.lower(),
     "the README keeps the wipe-recovery story")

print()
if fails:
    print(f"v1.4.30 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.4.30 gate: all green")
