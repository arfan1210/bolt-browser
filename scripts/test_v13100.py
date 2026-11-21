#!/usr/bin/env python3
"""v1.3.100 gate — THE UNHURRIED DWELL + THE AUTH-CHAIN UA HOLD.

Field report (v1.3.99): "Home looks good, just now make the grouping time a
bit longer, it is too fast to appreciate the animation now." and "in qwen
coder website, the browser looks like refreshes when I try to connect to my
github. I am unable to connect my github to qwen coder. Qwen coder only
works in desktop site, maybe the browser drops the desktop act for a moment
when I tap connect causing qwen coder to go off as bolt becomes mobile for
an instant and then desktop again and qwen coder just refreshes."

PART 1 — THE DWELL WIDENING. The grouping dwell (the progress pill that
fills while the finger rests on a tile's center) merged at 600 ms — too
fast to watch the fill. 1000 ms now (the launcher cadence); the pill's
level math follows; any drift out of the center zone still cancels
instantly, so the wider window costs nothing in safety.

PART 2 — THE AUTH-CHAIN UA HOLD (the user's theory was RIGHT, and the
mechanism is now exact). The qwen GitHub connect is an OAuth round-trip:
chat.qwen.ai -> github.com/login/oauth/authorize?client_id=... (the
authorize REQUEST) -> github login/consent pages -> 302 back to
qwen.ai/callback?code=...&state=... (the single-use RESPONSE). v1.3.98's
heal ran the per-site UA decision at every hop:
  - the authorize hop left qwen.ai, whose site pref says desktop, for
    github.com, which has NO desktop pref -> the UA flipped to MOBILE
    ("bolt becomes mobile for an instant" — the user's words);
  - the callback committed on qwen.ai -> the commit heal flipped the UA
    back to DESKTOP and reload()ed the just-committed page
    ("then desktop again and qwen coder just refreshes");
  - the reload re-requested /callback?code=... — an OAuth code is
    SINGLE-USE, the second exchange died, the connect never completed
    ("I am unable to connect my github to qwen coder").
Chrome never reloads here because it evaluates the per-site UA PER
REQUEST; a WebView has ONE UA per view and a mid-flight write forces
Chromium's reload. THE LAW (the auth-chain hold): an OAuth/SSO round-trip
rides ONE stable UA — the one the relying party's page already carried
when the flow left it (which IS that site's pref, the page was committed
under it). Concretely, in Tab.applyUaFor, BEFORE any pref read or write:
  1. an auth RESPONSE url (code=/error=/id_token=/access_token=/token=/
     SAMLResponse=/RelayState=/nonce=/iss= in the QUERY OR the fragment)
     returns false UNCONDITIONALLY — a single-use landing is never
     reloaded, hold or no hold — and disarms the hold (round-trip over);
  2. an authorize REQUEST url (client_id + redirect_uri/response_type, or
     SAMLRequest) arms the hold {idp base, rp base (= the WebView's live
     outgoing page), 10-minute TTL} and returns false — no write, the
     chain leaves with the UA it has; a same-domain re-arm keeps the
     earlier rp (the IdP's login bounce-back must not clobber it);
  3. while held, urls on the idp/rp bases return false (the IdP's login
     pages never flip mid-chain); a THIRD-domain url disarms and heals
     normally (the user left the flow).
The bias is deliberate and one-sided: a false positive skips ONE heal (a
page renders with the chain's UA until the next navigation — cosmetic,
self-healing); a false negative reloads a single-use page (fatal to the
sign-in). Everything v1.3.98 built stays: back/forward healing, the
redirect-into-desktop-site reload-once (bit.ly -> a desktop-flagged site
carries no auth params), the client-hints twin, the viewport guard.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
TAB = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/Tab.java")
GR = os.path.join(ROOT, "app/build.gradle")

fails = 0

def need(cond, why):
    global fails
    tag = "PASS" if cond else "FAIL"
    print(f"  {tag} {why}")
    if not cond:
        fails += 1

def slurp(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

ba = slurp(BA)
tab = slurp(TAB)
gradle = slurp(GR)

print("A — the unhurried dwell (600 ms -> 1000 ms)")
need("sdDwellProgress >= 1000" in ba and "sdDwellProgress >= 600" not in ba,
     "the merge fires when the pill is FULL at 1000 ms (no 600 residue)")
need("sdDwellProgress * 10000L / 1000L" in ba and "sdDwellProgress * 10000L / 600L" not in ba,
     "the pill's fill level spans the full 1000 ms")
need(re.search(r"1000 ms to full — v1\.3\.100 widened it from\s*\n\s*\*?\s*600", ba) is not None,
     "the timing comment records the widening (future sessions read the why)")
need("ui.postDelayed(sdDwellTick, 40)" in ba or "ui.postDelayed(this, 40)" in ba,
     "the 40 ms tick cadence is unchanged (the fill stays smooth)")

print("B — the auth-chain hold, source shape (Tab.java)")
need("public String authHoldIdp" in tab and "public String authHoldRp" in tab
     and "public long authHoldAt" in tab,
     "the per-tab hold state exists (idp base / rp base / armed-at)")
need("static final long AUTH_HOLD_MS = 10L * 60L * 1000L;" in tab,
     "the hold TTL is 10 minutes (an OAuth dance takes under two; ten covers a slow login)")
# the guard block runs BEFORE any pref read or UA write, and its order is the law
need(re.search(r"isAuthResponseUrl\(url\)\) \{[\s\S]{0,700}?return false;", tab) is not None,
     "an auth RESPONSE returns false before any write (a single-use landing is never reloaded)")
need(re.search(r"isAuthRequestUrl\(url\)\) \{[\s\S]{0,2600}?return false;", tab) is not None,
     "an authorize REQUEST arms the hold and returns false (the chain leaves with its UA)")
need(re.search(r"if \(held && \(authBase\.equals\(authHoldIdp\)[\s\S]{0,600}?return false;", tab) is not None,
     "a held idp/rp url returns false (the IdP's login pages never flip mid-chain)")
guard_at = tab.find("THE AUTH-CHAIN HOLD (see the field docs above)")
prefs_at = tab.find("Prefs p = Prefs.get(c);", tab.find("public boolean applyUaFor"))
need(0 < guard_at < prefs_at,
     "the whole guard sits BEFORE the per-site pref computation (no read, no write)")
need(re.search(r"if \(held\) \{\s*\n\s*authHoldIdp = null;[^\n]*\n\s*authHoldRp = null;", tab) is not None,
     "a third-domain url disarms the hold and heals normally (the user left the flow)")
need(re.search(r"if \(rp == null && authHoldRp != null && authHoldRp\.length\(\) > 0[\s\S]{0,200}?rp = authHoldRp;", tab) is not None,
     "a same-domain re-arm keeps the earlier rp (the IdP login bounce-back must not clobber it)")
need("ua auth arm idp=" in tab and "ua auth rsp-skip" in tab and "ua auth hold-skip" in tab,
     "the diag vocabulary names every hold decision (ua auth arm / rsp-skip / hold-skip)")
# the detectors' grammar
need(re.search(r"qHasAny\(q, \"client_id\"\)\s*\n?\s*&& \(qHasAny\(q, \"redirect_uri\"\) \|\| qHasAny\(q, \"response_type\"\)\)\)", tab) is not None,
     "authorize detection = client_id WITH redirect_uri/response_type (the OAuth2 grammar)")
need("qHasAny(q, \"samlrequest\")" in tab,
     "SAML redirect-binding requests arm the hold too")
for tok in ("\"code\"", "\"error\"", "\"id_token\"", "\"access_token\"", "\"samlresponse\"", "\"relaystate\""):
    need(tok in tab, f"response detection carries {tok}")
need("qHasAny(urlQueryOf(url), names) || qHasAny(urlRefOf(url), names)" in tab,
     "response detection reads the QUERY and the FRAGMENT (implicit flows deliver in the fragment)")
need(re.search(r"String n = \(eq >= 0 \? pair\.substring\(0, eq\) : pair\)\.trim\(\)\.toLowerCase\(\);", tab) is not None,
     "param matching is exact-NAME, never substring (promo_code= / ?error-page= cannot trip it)")

print("C — the functional probe (the parse-gate law: run the REAL classifier)")
# extract the five static methods exactly as shipped and compile them standalone
start = tab.find("    static boolean isAuthRequestUrl(String url) {")
end = tab.find("    /** v1.3.40 — PER-SITE PAGE ZOOM")
if start < 0 or end < 0 or end <= start:
    need(False, "the classifier methods are where the gate expects them")
else:
    methods = tab[start:end]
    javac = None
    for cand in (os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javac"),
                 "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/javac",
                 shutil.which("javac") or ""):
        if cand and os.path.exists(cand):
            javac = cand
            break
    if not javac:
        need(False, "javac found (run scripts/setup_toolchain.sh first)")
    else:
        harness = """
    public static void main(String[] args) {
        int bad = 0;
        // ---- authorize REQUESTS (must arm) ----
        String[] req = {
            "https://github.com/login/oauth/authorize?client_id=Iv1 abc&redirect_uri=https%3A%2F%2Fchat.qwen.ai%2Fcb&state=x",
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=xyz&response_type=code&scope=openid",
            "https://sso.example.com/saml?SAMLRequest=fZLLTsMwEEVPy1SS",
            "https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize?client_id=c&redirect_uri=r&response_type=code"};
        // ---- single-use RESPONSES (must skip) ----
        String[] rsp = {
            "https://chat.qwen.ai/api/oauth/callback?code=gho_16C7e&state=abc",
            "https://chat.qwen.ai/callback?error=access_denied&error_description=the+user+said+no",
            "https://app.example.com/cb#access_token=ey42&token_type=bearer",
            "https://example.com/acs?SAMLResponse=PHNhbWxw&RelayState=cookie",
            "https://example.com/oidc/cb?code=X&state=Y&nonce=Z",
            "https://example.com/token#id_token=ey9f"};
        // ---- ordinary urls (must NOT match either) ----
        String[] plain = {
            "https://chat.qwen.ai/chat/new",
            "https://shop.example.com/checkout?promo_code=SAVE20",
            "https://news.example.com/story?error-page=404",
            "https://api.example.com/v1/map?key=client_id_lookalike",
            "https://example.com/docs?section=code&lang=java",
            "https://example.com/saml-intro"};
        for (String u : req)
            if (!isAuthRequestUrl(u)) { System.out.println("REQ MISS " + u); bad++; }
        for (String u : req)
            if (isAuthResponseUrl(u)) { System.out.println("REQ FALSE-RSP " + u); bad++; }
        for (String u : rsp)
            if (!isAuthResponseUrl(u)) { System.out.println("RSP MISS " + u); bad++; }
        for (String u : rsp)
            if (isAuthRequestUrl(u)) { System.out.println("RSP FALSE-REQ " + u); bad++; }
        for (String u : plain) {
            if (isAuthRequestUrl(u)) { System.out.println("PLAIN FALSE-REQ " + u); bad++; }
            if (isAuthResponseUrl(u)) { System.out.println("PLAIN FALSE-RSP " + u); bad++; }
        }
        // ---- the callback-in-fragment corpse (qwen's shape) ----
        if (!isAuthResponseUrl("https://chat.qwen.ai/callback?code=a&state=b#/app"))
            { System.out.println("QUERY+FRAGMENT MISS"); bad++; }
        if (bad > 0) { System.out.println("PROBE FAILURES: " + bad); System.exit(1); }
        System.out.println("probe: all green");
    }
}
"""
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "Probe.java")
            with open(src, "w", encoding="utf-8") as f:
                f.write("public class Probe {\n" + methods + harness)
            r = subprocess.run([javac, src], capture_output=True, text=True)
            if r.returncode != 0:
                need(False, "the extracted classifier compiles: " + r.stderr[:300])
            else:
                r2 = subprocess.run(["java", "-cp", d, "Probe"], capture_output=True, text=True)
                need(r2.returncode == 0 and "all green" in r2.stdout,
                     "the REAL classifier passes the 25-case table "
                     "(authorize arms / single-use skips / promo_code and error-page never trip)")

print("V — version pin")
need("versionCode 140" in gradle and 'versionName "1.4.26"' in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "build.gradle pins 140 / 1.4.26")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print()
if fails:
    print(f"v1.3.100 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.3.100 gate: all green")
