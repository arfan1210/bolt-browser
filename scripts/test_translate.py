#!/usr/bin/env python3
"""v1.3.70/71 — extract the JS built by util/PageTranslate.kt (the in-place
translation engine + the selection box) from the Kotlin source, validate its
syntax with node --check, and dump the assembled payloads to /tmp for the
behavioral mock-DOM suite (scripts/test_translate.js — same pattern as
test_pipvideo.py for PipVideo.kt and check_js.py for MediaHook.core())).

Extracts FOUR payloads:
  /tmp/translate_engine_bn.js   — Bengali target (script pre-filter live)
  /tmp/translate_engine_en.js   — English target (no script pre-filter)
  /tmp/translate_engine_caps.js — English target with SCALED-DOWN caps
                                  (INITCAP=5, WAVEBUD=3, NODCAP=20) so the
                                  backlog-pump / cap tests run in seconds
  /tmp/translate_sel.js         — the SELECTION box (__TL__=en, __TEXT__ a
                                  JSON string literal, exactly the way
                                  PageTranslate.select substitutes it)

Also proves the Kotlin-side contract that the JS suite cannot:
  K1  the SSRF guard exists (HOSTS whitelist + https-only + no redirects);
  K2  the relay is registered PER-WEB as "BoltTx" in Tab.buildWeb;
  K3  results are delivered through JSONObject.quote (JS-literal escaping);
  K4  the dead translate.google.com/translate proxy flow is GONE from
      BrowserActivity (the v1.3.69 bug: new tab, logged out);
  K5  the engine + selection literals carry no '$' (a Kotlin template would
      break the raw string) and no triple-quote sequence;
  K6  THE v1.3.71 COLLECTOR REBUILD: shadow-root piercing (nodeType 11
      container + element.shadowRoot descent), same-origin iframe piercing
      (contentDocument), and IFRAME REMOVED from the SKIP map (the v1.3.70
      blindness);
  K7  the relay has the SECOND channel (fetchSel -> __boltTxDone2);
  K8  the selection translate no longer opens translate.google.com — it
      calls PageTranslate.select (the v1.3.71 floating box);
  K9  BoltClient.onPageFinished calls PageTranslate.onPageLoaded (the
      sticky session — the restored-page race fix);
  K10 the selection box payload exists with both placeholders;
  K11 THE v1.3.74 LAZY CAPS: INITCAP=1500 / NODCAP=6000 / DEFMAX=4000 /
      SCROLLD=250 are the real ones, and the v1.3.71 WAVEBUD pump is GONE
      (the deferred tail waits for scroll — nothing translates until
      looked at);
  K12 THE v1.3.72 DISPLAY:CONTENTS CURE — rendered() consults
      getComputedStyle(el).display=='contents' and passes (chat.z.ai mounts
      its whole app in <div id=app style="display: contents">; empty client
      rects on that node hard-skipped the ENTIRE subtree in v1.3.71 — the
      "Nothing to translate" report);
  K13 THE v1.3.72 RESCAN LADDER — RD=[1000,2500,5000,9000] + S.rscan reset
      per epoch + the final RELAX pass (translate=no / not-rendered bow);
  K14 THE v1.3.72 CLOSED-SHADOW REGISTRY — walkDoc() reads __boltShr per
      document and the start diag carries dc= + cshr=;
  K15 SHADOW GATE — util/ShadowGate.kt exists, is armed in Tab.buildWeb
      next to FrameHooks.arm and re-injected at BOTH onPage moments;
  K16 THE SHARED gtx QUERY — @JvmStatic fun gtx with the HOSTS loop +
      dual-shape parser, the ONE translation channel for the screen
      translator too;
  K17 THE v1.3.74 VIEWPORT-LAZY ENGINE — S.defer registry, scanVisible,
      the capture-phase scroll hub (armScroll/armScrollOn reaching the
      live epoch through S.scan), attribute observation in OBS (a
      style/class reveal fires no childList mutation), 'done2' on later
      finishes (toast spam kill), and NO S.more backlog pump.
"""
import re
import subprocess
import sys
import pathlib

SRC = "app/src/main/java/com/bolt/browser/util/PageTranslate.kt"
TAB = "app/src/main/java/com/bolt/browser/core/Tab.java"
ACT = "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java"
SHADOW_GATE = "app/src/main/java/com/bolt/browser/util/ShadowGate.kt"


def main():
    src = pathlib.Path(SRC).read_text(encoding="utf-8")

    m = re.search(r'const val ENGINE = """(.*?)"""', src, re.S)
    if not m:
        print("FAIL: ENGINE raw-string literal not found in PageTranslate.kt")
        return 1
    js = m.group(1)

    ms = re.search(r'const val SELECTION_JS = """(.*?)"""', src, re.S)
    if not ms:
        print("FAIL: SELECTION_JS raw-string literal not found")
        return 1
    sel = ms.group(1)

    # K5 — template/terminator hazards
    for name, body in (("ENGINE", js), ("SELECTION_JS", sel)):
        if "$" in body:
            print(f"FAIL: {name} contains a dollar sign (Kotlin template hazard)")
            return 1
        if '"""' in body:
            print(f"FAIL: {name} contains a triple-quote sequence")
            return 1
    if "__TL__" not in js:
        print("FAIL: ENGINE does not carry the __TL__ placeholder")
        return 1

    pathlib.Path("/tmp/translate_engine_bn.js").write_text(
        js.replace("__TL__", "bn"), encoding="utf-8")
    pathlib.Path("/tmp/translate_engine_en.js").write_text(
        js.replace("__TL__", "en"), encoding="utf-8")
    # scaled-down caps variant for the lazy-scroll/cap tests
    caps = (js.replace("__TL__", "en")
              .replace("INITCAP=1500", "INITCAP=5")
              .replace("DEFMAX=4000", "DEFMAX=60")
              .replace("NODCAP=6000", "NODCAP=20"))
    for probe in ("INITCAP=5", "DEFMAX=60", "NODCAP=20"):
        if probe not in caps:
            print(f"FAIL: caps variant substitution missed {probe}")
            return 1
    pathlib.Path("/tmp/translate_engine_caps.js").write_text(caps, encoding="utf-8")
    # v1.3.72: fast rescan-ladder variant (Bengali target) — RD shrunk from
    # 1s/2.5s/5s/9s to 80/120/160/200 ms so the zero-node ladder tests
    # (T14/T23/T24) run in under a second instead of 17.5 s
    fast = js.replace("__TL__", "bn").replace("RD=[1000,2500,5000,9000]", "RD=[80,120,160,200]")
    if "RD=[80,120,160,200]" not in fast:
        print("FAIL: fast variant substitution missed the RD ladder")
        return 1
    pathlib.Path("/tmp/translate_engine_fast.js").write_text(fast, encoding="utf-8")
    pathlib.Path("/tmp/translate_sel.js").write_text(
        sel.replace("__TL__", "en").replace("__TEXT__", '"mock selected text"'),
        encoding="utf-8")

    for v in ("bn", "en", "caps", "fast"):
        r = subprocess.run(["node", "--check", f"/tmp/translate_engine_{v}.js"],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(f"FAIL: engine ({v}) JS syntax error")
            print(r.stderr)
            return 1
    r = subprocess.run(["node", "--check", "/tmp/translate_sel.js"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("FAIL: SELECTION_JS syntax error")
        print(r.stderr)
        return 1
    print(f"OK: ENGINE extracted ({len(js)} bytes) + SELECTION_JS ({len(sel)} bytes), "
          "placeholders substituted, node --check passed for bn/en/caps/fast/sel")

    # K1 — the SSRF guard
    if not re.search(r'HOSTS\s*=\s*\n?\s*arrayOf\("translate\.googleapis\.com",\s*"translate\.google\.com"\)',
                     src):
        print("FAIL: HOSTS whitelist (googleapis + google) not found")
        return 1
    if 'u.protocol, ignoreCase = true' not in src or "HOSTS.none" not in src:
        print("FAIL: https-only / HOSTS.none guard not found")
        return 1
    if "instanceFollowRedirects = false" not in src:
        print("FAIL: redirects not disabled in the relay fetch")
        return 1
    print("OK: K1 SSRF guard present (HOSTS whitelist, https-only, no redirects)")

    # K2 — per-web registration
    tab = pathlib.Path(TAB).read_text(encoding="utf-8")
    if 'new com.bolt.browser.util.PageTranslate.Relay(w), "BoltTx"' not in tab:
        print('FAIL: Tab.buildWeb does not register PageTranslate.Relay as "BoltTx"')
        return 1
    print("OK: K2 Relay registered per-WebView as BoltTx in Tab.buildWeb")

    # K3 — escaping on the delivery path (both channels)
    if "JSONObject.quote(cb)" not in src or "JSONObject.quote(body)" not in src:
        print("FAIL: relay delivery does not JSONObject.quote both args")
        return 1
    print("OK: K3 relay delivery escapes via JSONObject.quote")

    # K4 — the dead proxy flow is gone
    act = pathlib.Path(ACT).read_text(encoding="utf-8")
    if "translate.google.com/translate?" in act:
        print("FAIL: the retired /translate proxy URL is still opened somewhere "
              "in BrowserActivity")
        return 1
    if "PageTranslate.begin(t.web, codes[w])" not in act:
        print("FAIL: translatePage() does not start PageTranslate in place")
        return 1
    print("OK: K4 the /translate proxy flow is gone; translatePage begins in place")

    # K6 — the v1.3.71 collector rebuild
    skip_m = re.search(r'var SKIP=\{([^}]*)\}', js)
    if not skip_m:
        print("FAIL: SKIP map not found in ENGINE")
        return 1
    if "IFRAME" in skip_m.group(1):
        print("FAIL: IFRAME still in the SKIP map — same-origin frames stay "
              "invisible (the v1.3.70 blindness)")
        return 1
    if "node.shadowRoot" not in js or "nodeType==11" not in js:
        print("FAIL: shadow-root piercing missing from the ENGINE")
        return 1
    if "contentDocument" not in js:
        print("FAIL: same-origin iframe piercing missing from the ENGINE")
        return 1
    if "dt=bd" not in sel:
        print("FAIL: the selection box does not request dictionary senses (dt=bd)")
        return 1
    print("OK: K6 collector v2 (shadow pierce + iframe pierce + IFRAME out of "
          "SKIP) and the selection box asks for meanings")

    # K7 — the second relay channel
    if "fun fetchSel" not in src or "__boltTxDone2" not in src:
        print("FAIL: the selection channel (fetchSel/__boltTxDone2) is missing")
        return 1
    print("OK: K7 the relay carries the selection channel (fetchSel -> __boltTxDone2)")

    # K8 — the selection translate is in place now
    if "openNewTab(\"https://translate.google.com/?sl=auto" in act:
        print("FAIL: the selection translate still opens translate.google.com")
        return 1
    if "PageTranslate.select(t.web, text)" not in act:
        print("FAIL: onTranslateSelection does not call PageTranslate.select")
        return 1
    print("OK: K8 the selection translate shows the floating box in place")

    # K9 — the sticky session hook
    if "PageTranslate.onPageLoaded(view)" not in act:
        print("FAIL: onPageFinished does not call PageTranslate.onPageLoaded")
        return 1
    if "fun onPageLoaded" not in src or "STICKY" not in src:
        print("FAIL: the sticky-session machinery is missing from PageTranslate")
        return 1
    print("OK: K9 the sticky session is wired (onPageFinished -> onPageLoaded)")

    # K10 — the selection payload markers
    if "__TEXT__" not in sel or "__TL__" not in sel:
        print("FAIL: SELECTION_JS lacks its placeholders")
        return 1
    print("OK: K10 SELECTION_JS carries both placeholders")

    # K11 — the v1.3.74 lazy caps
    for probe in ("INITCAP=1500", "NODCAP=6000", "DEFMAX=4000", "SCROLLD=250"):
        if probe not in js:
            print(f"FAIL: the v1.3.74 cap {probe} is missing from the ENGINE")
            return 1
    if "WAVEBUD" in js.replace("the old WAVEBUD pump", ""):
        print("FAIL: the v1.3.71 WAVEBUD pump is still live in the ENGINE")
        return 1
    if "NODCAP=900" in js:
        print("FAIL: the v1.3.70 NODCAP=900 is still present")
        return 1
    print("OK: K11 lazy caps (INITCAP=1500 / NODCAP=6000 / DEFMAX=4000 / "
          "SCROLLD=250; the WAVEBUD pump is gone)")

    # K12 — the v1.3.72 display:contents cure
    if "getComputedStyle" not in js or "'contents'" not in js:
        print("FAIL: rendered() never consults computed display:contents — "
              "the chat.z.ai whole-app kill is unfixed")
        return 1
    if "REJ.dc++" not in js or "dc='+REJ.dc" not in js:
        print("FAIL: the display:contents pass is not counted (dc= missing)")
        return 1
    print("OK: K12 display:contents elements pass through and count (dc=)")

    # K13 — the v1.3.72 rescan ladder
    if "RD=[1000,2500,5000,9000]" not in js:
        print("FAIL: the zero-node rescan ladder RD is missing")
        return 1
    if "S.rscan=0" not in js or "'rscan i='" not in js:
        print("FAIL: the rescan counter / diag is missing")
        return 1
    if "RELAX=S.rscan>=RD.length" not in js or "!RELAX&&attr(node,'translate')=='no'" not in js \
            or "!RELAX&&!rendered(node,t)" not in js:
        print("FAIL: the RELAX pass does not override translate=no / not-rendered")
        return 1
    print("OK: K13 the rescan ladder (1s/2.5s/5s/9s, final pass relaxed) is in")

    # K14 — the closed-shadow registry walk
    if "function walkDoc(doc,out,roots)" not in js or "__boltShr" not in js:
        print("FAIL: walkDoc/__boltShr registry walk missing from the ENGINE")
        return 1
    if "walkDoc(cd,out,roots)" not in js:
        print("FAIL: iframe documents do not read their own attachShadow registry")
        return 1
    if "cshr='+NREG" not in js:
        print("FAIL: the start diag does not carry cshr=")
        return 1
    if "function dedupe(arr)" not in js:
        print("FAIL: the node dedupe helper is missing (registry double-reach)")
        return 1
    print("OK: K14 the registry walk pierces closed roots (cshr= counted)")

    # K15 — the shadow gate wiring
    gate = pathlib.Path(SHADOW_GATE)
    if not gate.exists():
        print("FAIL: util/ShadowGate.kt does not exist")
        return 1
    gsrc = gate.read_text(encoding="utf-8")
    for probe in ("__boltShrArmed", "__boltAS", "attachShadow",
                  "addDocumentStartJavaScript"):
        if probe not in gsrc:
            print(f"FAIL: ShadowGate.kt lacks {probe}")
            return 1
    if "ShadowGate.arm(w)" not in tab:
        print("FAIL: Tab.buildWeb does not arm the ShadowGate")
        return 1
    if act.count("ShadowGate.injectNow(view)") < 2:
        print("FAIL: ShadowGate is not re-injected at onPageStarted AND "
              "onPageCommitVisible")
        return 1
    print("OK: K15 the shadow gate is registered document-start in every frame")

    # K16 — the shared gtx query
    if "@JvmStatic\n    fun gtx(" not in src:
        print("FAIL: the shared gtx query is missing from PageTranslate")
        return 1
    if "for (h in HOSTS)" not in src or "fun parseGtx(" not in src:
        print("FAIL: gtx does not loop the HOSTS whitelist / parse both shapes")
        return 1
    if "optJSONArray(\"sentences\")" not in src or "optJSONArray(0)" not in src:
        print("FAIL: parseGtx does not handle BOTH response shapes")
        return 1
    print("OK: K16 the shared gtx query rides the same guarded endpoint")

    # K17 — the v1.3.74 viewport-lazy engine
    for probe in ("S.defer=[]", "function scanVisible()", "S.scan=scanVisible",
                  "function armScroll()", "armScrollOn(document)",
                  "__boltTxScrollArmed", "'scan push='", "'done2 '",
                  "attributes:true,attributeFilter"):
        if probe not in js:
            print(f"FAIL: the v1.3.74 lazy marker {probe} is missing")
            return 1
    if "S.more" in js:
        print("FAIL: the S.more backlog pump survived — the deferred tail must "
              "belong to the scroll scanner only")
        return 1
    if "scroll for more" not in js:
        print("FAIL: the phase-2 pill never tells the user about the deferred tail")
        return 1
    print("OK: K17 viewport-lazy engine (defer registry + scroll hub + "
          "attribute observer + done2; no backlog pump)")

    # K18 — the v1.3.76 dispatch fix + the IO tail (the report: "still a bit
    # slower than expected" + the scroll-lag half of the toolbar report)
    for probe in ("CONC=6,STAGGER=45",
                  "while(busy<CONC){",
                  "var USEIO=('IntersectionObserver' in window)",
                  "function ioEnsure()",
                  "S.io.unobserve(n)",
                  "rootMargin:'140px 0px'",
                  "function stashCold(",
                  "drop+=stashCold(sh.h.slice(n))",
                  "'io push='"):
        if probe not in js:
            print(f"FAIL: the v1.3.76 dispatch/IO marker {probe} is missing")
            return 1
    # the one-request-per-tick limiter must be GONE (a 40-node viewport
    # waited N*90ms just to LEAVE — the "slower than expected" root cause)
    if re.search(r"var it=nextIt\(\);\s*\n\s*if\(it\)\{busy\+\+;run\(it\)\}", js):
        print("FAIL: step() still feeds one request per tick — CONC is a fiction")
        return 1
    if "CONC=4" in js or "STAGGER=90" in js:
        print("FAIL: the old CONC=4/STAGGER=90 constants survived")
        return 1
    print("OK: K18 the v1.3.76 dispatch fix (CONC=6 slot refill) + the IO "
          "tail (no rect-thrash scroll scanning when IO exists)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
