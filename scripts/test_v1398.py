#!/usr/bin/env python3
"""v1.3.98 gate — the v1.3.97 field report, two fronts: the pager clash and
the desktop site.

PAGER CLASH — "the dragging right on home screen icon and the swiping
   right to scroll the home screen icons are interfering. Fix this
   clash." DECOMPILED ViewPager2 1.1.0 (the AAR in the gradle cache):
   ViewPager2 itself overrides NO touch method — the paging interception
   lives in its INNER RecyclerViewImpl.onInterceptTouchEvent/onTouchEvent,
   a CHILD of the ViewPager2. requestDisallowInterceptTouchEvent propagates
   UP from the view it is called on, so the v1.3.92 call on the pager
   armed the pager + its ancestors but never the inner RecyclerView below
   it: an armed drag moving horizontally past slop was intercepted, the
   cell stream CANCELLED (drag folded), and the page swiped — the exact
   reported clash (vertical reordering never tripped it, which is why
   rearranging "worked"). Two locks now: the flag raised on the CELL (it
   walks the real chain SdGridLayout -> inner RecyclerView -> pager ->
   homeScroll) and setUserInputEnabled(false) — the documented input kill
   (RecyclerViewImpl's touch methods both gate on it). endSdDrag restores
   both; unarmed swipes still page (they only lose to an ARMED drag).

DESKTOP SITE — "works temporarily and [falls back] maybe when the link
   changes like within the same website... qwen coder sometimes falls
   back to mobile view and grok website does not open in desktop site at
   all." Three corpses, all verified against the live sites' bundles:
   (a) THE UA REVERT: applyUa ran only from shouldOverrideUrlLoading —
       which fires for NEITHER back/forward nor server redirects. A
       desktop-forced tab that followed an external link (UA -> mobile)
       and pressed BACK re-requested the desktop site WITH THE MOBILE UA:
       mobile HTML, toggle looks broken. The heal: Tab.applyUaFor(url)
       at onPageStarted (a genuine change rides Chromium's reload-the-
       in-flight-load, crbug 40340203) and at onPageCommitVisible (the
       redirect corpse — the final URL is known only there; a change
       reloads ONCE, terminating).
   (b) THE CLIENT-HINTS TWIN: a custom UA generates NO UA-CH — a
       hints-reading site sees a desktop string with mobile-or-absent
       hints and serves mobile anyway (grok's family of SSR gates).
       androidx.webkit 1.14 (already a dependency) grew the native fix:
       WebSettingsCompat.setUserAgentMetadata — new util/UaIdentity.kt
       keeps it in lockstep with the live UA (feature-gated, shape-level
       write-only-if-different, set at build time so first loads never
       double-load).
   (c) THE DYING VIEWPORT GUARD: the v1.3.89 force missed both ways an
       SPA re-collapses the viewport — frameworks REWRITE the existing
       meta's content attribute on route change (an ATTRIBUTE mutation
       the observer never subscribed to) and the observer disconnected
       4 s after DOMContentLoaded, so every later soft navigation landed
       unanswered (qwen's store: window.innerWidth < 768 ->
       setMobile(true)). The observer now watches attributes too and
       never rests; a missing meta gets one INJECTED (WebView's meta-less
       fallback is 980 px — below the >=1024 breakpoints); pageshow
       re-sweeps bfcache restores.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
TAB = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/Tab.java")
UAI = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/UaIdentity.kt")
JS = os.path.join(ROOT, "app/src/main/assets/webauthn_compat.js")
GR = os.path.join(ROOT, "app/build.gradle")

fails = 0

def need(cond, why):
    global fails
    tag = "PASS" if cond else "FAIL"
    print(f"  {tag} {why}")
    if not cond:
        fails += 1

def rd(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

ba, tab, uai, js, gradle = rd(BA), rd(TAB), rd(UAI), rd(JS), rd(GR)

print("A — the pager clash (the inner RecyclerView was the thief)")

need("protected View sdDragCell;" in ba,
     "the armed cell is tracked (sdDragCell) for the flag's round trip")
need(re.search(r"android\.view\.ViewParent cp = cell\.getParent\(\);[\s\S]{0,120}?cp\.requestDisallowInterceptTouchEvent\(true\);", ba) is not None
     and re.search(r"sdDragCell = cell;", ba) is not None,
     "beginSdDrag raises the no-intercept flag from the CELL'S PARENT CHAIN (up THROUGH the inner RecyclerView)")
need(re.search(r"sdPager\.setUserInputEnabled\(false\)", ba) is not None
     and re.search(r"beginSdDrag[\s\S]{0,9800}?setUserInputEnabled\(false\)", ba) is not None,
     "beginSdDrag kills the pager's user input (the documented ViewPager2 switch)")
need(re.search(r"endSdDrag\(\) \{[\s\S]{0,4200}?setUserInputEnabled\(true\)", ba) is not None,
     "endSdDrag restores the pager's user input (unarmed swipes page again)")
need(re.search(r"endSdDrag\(\) \{[\s\S]{0,4200}?sdDragCell\.getParent\(\)\.requestDisallowInterceptTouchEvent\(false\)", ba) is not None
     and re.search(r"endSdDrag\(\) \{[\s\S]{0,4200}?sdDragCell = null;", ba) is not None,
     "endSdDrag clears the parent-chain flag and the cell reference")
need("setCurrentItem" in ba and "sdEdgeTurn" in ba,
     "the edge turn stays programmatic (setCurrentItem — unaffected by the input gate)")
need("RecyclerViewImpl" in ba,
     "the decompiled truth is written where the next reader will look (the comment carries it)")

print("B — the UA heal (back/forward + redirects finally re-apply the per-site UA)")

need("public boolean applyUaFor(Context c, WebView w, String url, String reason)" in tab,
     "Tab.applyUaFor takes the URL from the CALLER (not t.url)")
need("public void applyUa(Context c, WebView w) {\n        applyUaFor(c, w, url, null);\n    }" in tab,
     "the legacy applyUa delegates silently (every existing call site unchanged)")
need(re.search(r"onPageStarted\(WebView view, String url, Bitmap favicon\) \{[\s\S]{0,4600}?applyUaFor\(BrowserActivity\.this, view, url, \"start\"\)", ba) is not None,  # v1.4.19 re-point (2026-11-19): the data:-guard + the offline-overlay retire added ~15 lines to onPageStarted's head — the window widens 3600 -> 4600; the UA heal itself is unchanged
     "onPageStarted heals the UA from the STARTING url (the back/forward path)")
need(re.search(r"onPageCommitVisible\(WebView view, String url\) \{[\s\S]{0,1400}?applyUaFor\(BrowserActivity\.this, view, url, \"commit\"\)", ba) is not None,
     "onPageCommitVisible heals from the COMMITTED url (the redirect path)")
need(re.search(r"onPageCommitVisible\(WebView view, String url\) \{[\s\S]{0,1400}?if \(ct != null && ct\.applyUaFor\(BrowserActivity\.this, view, url, \"commit\"\)\) \{\s*\n\s*view\.reload\(\);", ba) is not None,
     "a commit-time change reloads ONCE (the page was loaded with the wrong UA)")
need(re.search(r"if \(!ua\.equals\(cur\)\) \{\s*\n\s*s\.setUserAgentString\(ua\);", tab) is not None,
     "the v1.3.25 write-only-if-different law survives the heal")
need('url.startsWith("http://") || url.startsWith("https://")' in tab,
     "the heal needs an http(s) origin (about:blank/data:/blob: keep the live UA — no reload can wipe opener content)")
need("ua heal " in tab,
     "the heal writes a diag line (field reports name the corpse next time)")

print("C — the client-hints twin (util/UaIdentity.kt)")

need(os.path.exists(UAI), "util/UaIdentity.kt exists (new file is Kotlin — the language rule)")
need("WebViewFeature.USER_AGENT_METADATA" in uai,
     "the metadata write is feature-gated (old WebViews keep today's behavior)")
need("WebSettingsCompat.setUserAgentMetadata" in uai,
     "the native setter is used (JS surface AND Sec-CH headers)")
need(re.search(r"win ->[\s\S]{0,300}?setMobile\(false\)\.setPlatform\(\"Windows\"\)", uai) is not None,
     "a Windows desktop UA reports mobile=false / platform Windows")
need(re.search(r"android ->[\s\S]{0,300}?setMobile\(true\)\.setPlatform\(\"Android\"\)", uai) is not None,
     "the chromeUa keeps the Android mobile shape")
need("!chromium -> null" in uai.replace("val chromium", "val chromium").replace("\n", " ") or "!chromium -> null" in uai,
     "Firefox/Safari UAs carry NO metadata (they never shipped UA-CH — the truthful shape)")
need("sameShape" in uai and "getUserAgentMetadata" in uai,
     "the write fires only on a real shape difference (the single-write law extends)")
need(re.search(r"PasskeyCompat\.apply\(c, w\);[\s\S]{0,900}?UaIdentity\.applyFor\(", tab) is not None,
     "buildWeb sets the metadata at BUILD time (first loads never double-load)")
need(re.search(r"applyUaFor[\s\S]{0,8800}?UaIdentity\.applyFor\(c, s, ua\);", tab) is not None,
     # v1.3.99 re-point: applyUaFor gained the living-desktop-UA substitution
     # block (the identity-agreement law), so the window widens 5800 -> 8800 (v1.3.100: the auth-chain hold rides inside);
     # the LAW (the metadata ride-along on every UA write) is unchanged.
     "applyUaFor keeps string and metadata in lockstep on every write")

print("D — the live viewport guard (webauthn_compat.js)")

need("if ((UA_WIN || UA_MAC) && !UA_MOBILE) {" in js,
     "the force still keys on the LIVE desktop UA shape (mobile pages untouched)")
need("var DW_W = 1280" in js and "m.setAttribute('content', 'width=' + DW_W)" in js,
     "metas are still forced to width=1280 (the >=1024 breakpoint family)")
need("if (w && parseFloat(w[1]) >= 1024) return" in js,
     "already-desktop-wide pages are still left as authored (idempotency)")
need("attributes: true, attributeFilter: ['content', 'name']" in js,
     "the observer subscribes to ATTRIBUTE rewrites (the SPA route-change corpse)")
need("dwObs.disconnect" not in js,
     "the guard never rests (the 4 s disconnect is gone)")
need("window.addEventListener('pageshow', function () { dwSweep() })" in js,
     "bfcache restores re-sweep (pageshow)")
need(re.search(r"if \(!ms\.length\) \{[\s\S]{0,400}?appendChild\(m\)", js) is not None,
     "a page with NO viewport meta gets one injected (the 980 px fallback corpse)")

print("V — version pin")
need("versionCode 137" in gradle and 'versionName "1.4.23"' in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "build.gradle pins 137 / 1.4.23")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print()
if fails:
    print(f"v1.3.98 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.3.98 gate: all green")
