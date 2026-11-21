#!/usr/bin/env python3
"""v1.3.99 gate — THE IDENTITY-AGREEMENT PASS (desktop site, round 5).

The v1.3.98 release closed the drag/swipe clash and the desktop-site
reversion corpses (the UA heal + the client-hints twin + the live viewport
guard) — but the parallel-session audit of the SHIPPED build found the
version mismatch still alive: the desktop preset UA string was frozen at
Chrome/124 (a mid-2024 string) while BOTH hint sources claimed the
DETECTED engine (156 on the field device):

  - UaIdentity.brands(engineMajor(c)) — the native Sec-CH-UA headers and
    navigator.userAgentData claimed Chrome 156;
  - webauthn_compat.js CONFIG.chromeMajor — the JS fallback spoof claimed
    156 too (and it SHADOWS the native JS surface on feature-capable
    WebViews, so both surfaces must agree with the string).

A navigator.userAgent claiming 124 while the hints claim 156 is a mismatch
no honest browser ever emits — exactly the pair a Cloudflare-tier gate
(grok.com answers with accept-ch + critical-ch, verified) reads as a bot
fingerprint, and v1.3.98's own commit flagged grok as "still open if it
persists": THIS is why it would persist. A 2.5-year-old version number
also fails feature gates on its own.

THE LAW (identity agreement): the UA string, the native client-hints
metadata and the JS fallback spoof must all claim the SAME Chrome major.
Three moves, one per surface:
  1. THE LIVING DESKTOP UA — the desktop preset's string is composed from
     the detected engine major (PasskeyCompat.desktopChromeUa, the
     chromeUa pattern; shape-matched in Tab.applyUa so the Firefox/Safari
     presets pass through). Steady state: string = hints = engine.
  2. THE NATIVE HINTS FOLLOW THE STRING — UaIdentity.brandMajor parses
     Chrome/(\\d+) from the live UA (engine fallback), and sameShape now
     compares the brand-head MAJOR VERSION (a string-major change with the
     same platform/mobile/brand shape rewrites the metadata — the frozen
     124 -> living 156 transition heals on the next write).
  3. THE JS FALLBACK FOLLOWS THE STRING — the spoof parses the major from
     the LIVE UA (UA_CHROME_MAJOR); the same engine keeps the true
     detected full build in the high-entropy hints (a reduction, not a
     contradiction — how real Chrome ships); a stale string honestly
     claims its own version everywhere.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TAB = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/core/Tab.java")
PKC = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/PasskeyCompat.java")
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

def slurp(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

tab = slurp(TAB)
pkc = slurp(PKC)
uai = slurp(UAI)
js = slurp(JS)
gradle = slurp(GR)

print("A — the living desktop UA (the frozen string dies)")
need(re.search(r"public static String desktopChromeUa\(\) \{[\s\S]*?Chrome/\" \+ chromeMajor \+ \"\.0\.0\.0 Safari/537\.36\";", pkc) is not None,
     "PasskeyCompat.desktopChromeUa composes the Windows UA from the DETECTED engine major")
need(re.search(r"if \(ua\.contains\(\"Windows NT\"\) && ua\.contains\(\"Chrome/\"\)\) \{\s*\n\s*ua = PasskeyCompat\.desktopChromeUa\(\);", tab) is not None,
     "Tab.applyUa shape-matches the Chrome desktop preset and substitutes the living UA")
need(re.search(r"if \(ua\.equals\(cur\)\) return;|if \(!ua\.equals\(cur\)\)", tab) is not None,
     "the v1.3.25 write-only-if-different law survives (no churn in steady state)")
need(re.search(r"Chrome/124\.0\.0\.0", open(os.path.join(ROOT, "app/src/main/res/values/arrays.xml"), encoding="utf-8").read()) is not None
     and re.search(r"if \(ua\.contains\(\"Windows NT\"\) && ua\.contains\(\"Chrome/\"\)\)", tab) is not None,
     "the arrays.xml template survives only as a SHAPE (Tab.applyUa substitutes the living UA before any use — pinned above)")

print("B — the native hints follow the string")
need(re.search(r"private fun brandMajor\(c: Context\?, ua: String\): Int \{[\s\S]*?Regex\(\"Chrome/\(\\\\d\+\)\"\)", uai) is not None,
     "UaIdentity.brandMajor parses Chrome/(\\d+) from the LIVE UA string")
need(re.search(r"return if \(parsed > 0\) parsed else engineMajor\(c\)", uai) is not None,
     "the detected engine is the FALLBACK only (string wins — hints follow the string, never the reverse)")
need(uai.count("brands(brandMajor)") == 4,
     "all four platform branches build their brand list from the string's major")
need(re.search(r"private fun sameShape[\s\S]{0,900}?val am = ab\.firstOrNull\(\)\?\.majorVersion[\s\S]{0,200}?return am == bm", uai) is not None,
     "sameShape compares the brand-head MAJOR VERSION (a major change rewrites the metadata)")

print("C — the JS fallback follows the string")
need(re.search(r"var UA_CHROME_MAJOR = CONFIG\.chromeMajor", js) is not None
     and re.search(r"/Chrome\\/\(\\d\+\)", js) is not None,
     "the spoof parses the major from the LIVE UA string")
need(re.search(r"function brandList \(fullVersion\) \{\s*\n\s*var v = fullVersion \? UA_CHROME_FULL : String\(UA_CHROME_MAJOR\)", js) is not None,
     "the brands carry the live-UA version (not the detected engine's)")
need(re.search(r"case 'uaFullVersion': values\.uaFullVersion = UA_CHROME_FULL; break", js) is not None
     and re.search(r"case 'version': values\.version = String\(UA_CHROME_MAJOR\); break", js) is not None,
     "the high-entropy hints carry the live-UA version")
need(re.search(r"if \(parsedMaj !== \(parseInt\(CONFIG\.chromeMajor, 10\) \|\| -1\)\)", js) is not None,
     "a stale string honestly claims its own full version; the same engine keeps the true detected build")
need("parseInt(CONFIG.chromeMajor, 10) || 0" not in js.split("// GREASE brands")[1].split("function greaseBrand () {")[1]
     if "// GREASE brands" in js else False,
     "the GREASE rotation keys on the live-UA major too")

print("D — the parse-gate law (substring pins are not enough)")
need(os.system("node --check " + JS + " > /dev/null 2>&1") == 0,
     "webauthn_compat.js parses (node --check)")
need(os.system("node " + os.path.join(ROOT, "scripts/test_v1399_js.js") + " > /dev/null 2>&1") == 0,
     "the functional mock-DOM probe passes (stale string -> hints agree with the string; live engine -> all three agree)")

print("V — version pin")
need("versionCode 140" in gradle and 'versionName "1.4.26"' in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "build.gradle pins 140 / 1.4.26")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print()
if fails:
    print(f"v1.3.99 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.3.99 gate: all green")
