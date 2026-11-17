#!/usr/bin/env python3
"""v1.3.52 per-site wipe blast-radius regression test (THE 'clears all
websites' field report).

Field history: since v1.3.7 the site-settings 'Wipe' flow (wipeSiteData)
ended with three GLOBAL calls inside what is sold as a per-site operation:
  (a) WebViewDatabase.clearFormData()      -- global saved-form database
  (b) new WebView().clearCache(true)       -- the SHARED HTTP cache (all sites)
  (c) remains -> CookieManager.removeAllCookies() -- the WHOLE cookie jar
The last one was meant as a rare 'last resort' when per-site cookie expiry
verifiably failed, but the remains-check (`getCookie(...).contains("=")`)
counts empty-value expiry residue AND every cookie class the expiry cannot
delete (partitioned/CHIPS cookies etc.), so on modern WebViews it fired
routinely -- wiping ONE site logged the user out of EVERY site. (a) and (b)
fired unconditionally every time.

v1.3.52 removed all three; a per-site wipe now has a per-site blast radius
by construction. This test extracts the wipeSiteData + expireAndVerify
bodies from BrowserActivity.java and fails if ANY global-wipe call ever
reappears inside the per-site flow, while asserting the per-site machinery
(in-page wipe, per-origin WebStorage deletes, SSO-family cookie expiry with
__Host-/__Secure- handling, tab rebuild) is still intact.

v1.3.55 extension: the CHIPS gate. YouTube's signed-out identity cookies
(YSC, VISITOR_INFO1_LIVE, VISITOR_PRIVACY_METADATA, __Secure-YNID,
__Secure-ROLLOUT_TOKEN) are set with the Partitioned attribute; WebView
stores them PER PARTITION (keyed by the schemeful site of the top-level
page) and CookieManager.setCookie only writes into that partition when the
string itself carries the Partitioned attribute (chromium's
CanonicalCookie::Create drops the supplied partition key otherwise) — so
the plain expiry could NEVER delete them and they survived every pass
(the "siteWipe survivors" log). The gate asserts the CHIPS twin exists
(and stays https-only + Secure), that the host enumeration covers the
partition surface, and that the read-only CookiePartitionScan exists to
enumerate hosts the static list misses.
"""
import re, sys, pathlib

# v1.4.10 re-point (2026-11-17): the private bolt-light-browser checkout is gone — gates read the PUBLIC source checkout
SRC = pathlib.Path("/home/z/my-project/bolt-browser"
                   "/app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
src = SRC.read_text(errors="ignore")


def method_body(signature_pattern):
    m = re.search(signature_pattern, src)
    if not m:
        return None
    start = src.index("{", m.start())
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[start:i]
    return None


# strip comments so commented-out mentions don't satisfy or trip checks
def strip_comments(text):
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text


wipe = method_body(r"protected void wipeSiteData\(String domain\) \{")
verify = method_body(r"private java\.util\.List<String> expireAndVerify\(")
expire = method_body(r"private void expireCookies\(")
related = method_body(r"private java\.util\.List<String> relatedDomains\(")

failures = []


def check(name, ok):
    print(("  OK   " if ok else "  FAIL ") + name)
    if not ok:
        failures.append(name)


if wipe is None:
    sys.exit("FAIL: wipeSiteData not found in BrowserActivity.java")
if verify is None:
    sys.exit("FAIL: expireAndVerify not found — v1.3.52 verification pass missing")
if expire is None:
    sys.exit("FAIL: expireCookies not found")
if related is None:
    sys.exit("FAIL: relatedDomains not found")

wipe_code = strip_comments(wipe)
verify_code = strip_comments(verify)
expire_code = strip_comments(expire)

print("== the per-site flow must contain NO global wipe call (the regression) ==")
for bad in ["removeAllCookies", "clearCache", "clearFormData", "deleteAllData",
            "removeSessionCookies", "clearHistory"]:
    check(f"wipeSiteData has no '{bad}'", bad not in wipe_code)
    check(f"expireAndVerify has no '{bad}'", bad not in verify_code)
    check(f"expireCookies has no '{bad}'", bad not in expire_code)

print("== the per-site machinery is intact ==")
check("in-page wipe still runs (MediaHook.wipePageData)",
      "MediaHook.wipePageData()" in wipe_code)
check("affected WebViews still destroyed before expiry",
      ".destroy()" in wipe_code)
check("per-origin WebStorage deletes still present",
      "deleteOrigin" in wipe_code)
check("verification is per-target (getCookie over targets)",
      "getCookie" in verify_code)
check("verification skips empty-value residue (v1.3.52)",
      "isEmpty()" in verify_code and "contains(\"=\")" not in verify_code)
check("survivors reported honestly, never nuked",
      "survived" in wipe_code and "removeAllCookies" not in wipe_code)
check("affected tabs rebuilt after the wipe",
      "showTab(tm.active)" in wipe_code)

print("== SSO-family expiry still complete (must not be 'fixed' away) ==")
check("SSO_FAMILY still defined",
      "SSO_FAMILY.put(\"youtube.com\"" in src)
check("expireCookies still writes Domain= variants",
      "Domain=\"" in expire_code or "Domain=" in expire_code)
check("expireCookies still handles __Host-/__Secure- prefixes",
      "__Host-" in expire and "__Secure-" in expire)
check("expireCookies still expiries both schemes",
      "\"https\"" in expire_code and "\"http\"" in expire_code)

# ---------------------------------------------------------------- v1.3.54
print("== v1.3.54: the FIRST-VISIT pass (IndexedDB actually dies) ==")
print("   (field report: 'clearing youtube's data clears the login but the")
print("    signed-out history remains — the in-page deleteDatabase is BLOCKED")
print("    by the page's own open connections and WebStorage.deleteOrigin's")
print("    contract excludes IndexedDB, so the IDB survived every wipe)")
KOTLIN = pathlib.Path("/home/z/my-project/bolt-browser"
                      "/app/src/main/java/com/bolt/browser/util/SiteStorageWipe.kt")
check("the physical sweep file exists (util/SiteStorageWipe.kt)",
      KOTLIN.exists())
if KOTLIN.exists():
    kcode = strip_comments(KOTLIN.read_text(errors="ignore"))
    sub = re.search(r"PER_ORIGIN_SUBTREES\s*=\s*listOf\((.*?)\)", kcode, re.S)
    check("sweep allowlist is exactly IndexedDB + File System + databases",
          sub is not None and '"IndexedDB"' in sub.group(1)
          and '"File System"' in sub.group(1) and '"databases"' in sub.group(1))
    check("sweep never touches the SHARED Local Storage LevelDB (v1.3.52 rule)",
          "Local Storage" not in kcode)
    check("sweep never touches the cookie jar file",
          '"Cookies"' not in kcode)
    check("sweep never touches Service Worker shared state",
          "Service Worker" not in kcode)
    check("sweep only deletes prefix-matched origin dirs (https_/http_ + trailing _)",
          "startsWith" in kcode and "https_${host}_" in kcode
          and "http_${host}_" in kcode)
    check("sweep walks every app_webview profile (normal + suffix dirs)",
          '"app_webview"' in kcode and "app_webview_" in kcode)
    check("sweep covers both on-disk layouts (Default/ + legacy flat)",
          '"Default"' in kcode)
    check("sweep runs on its own thread (never blocks the UI)",
          "Thread(" in kcode)
    check("sweep ALWAYS reports back (callback fires even on failure)",
          "onDone.onDone(r)" in kcode)
check("wipeSiteData wires the physical sweep in",
      "SiteStorageWipe.wipeOriginStorage" in wipe_code)
check("the rebuild is gated on the sweep completion (rebuilt latch)",
      "rebuilt" in wipe_code and "rebuild.run()" in wipe_code)
check("the rebuild has a stall guard (timeout fallback, file IO can't hang the UI)",
      "1500" in wipe_code)
m = re.search(r"\}, (\d+)\);\s*$", wipe.rstrip())
check("in-page wipe window before WebView destroy >= 500ms (was 260)",
      m is not None and int(m.group(1)) >= 500)
check("sweep result lands in the diagnostics log (siteWipe storage)",
      "siteWipe storage" in wipe_code)

# ---------------------------------------------------------------- v1.3.55
print("== v1.3.55: the CHIPS-partitioned cookie kill (the YouTube survivors) ==")
print("   (field report: the same five youtube.com cookies survived every")
print("    expiry pass on every host variant — they are Partitioned (CHIPS)")
print("    cookies: one copy per top-level host ever visited, and a plain")
print("    expiry string can never match a partitioned slot)")
check("expireCookies emits the Partitioned (CHIPS) expiry twin",
      '"; Partitioned"' in expire_code)
check("the CHIPS twin is https-only (a partitioned cookie must be Secure)",
      'if (scheme.equals("https"))' in expire_code
      and 'secAttr + "; Partitioned"' in expire_code)
check("BOTH twins stay: plain (non-partitioned) + CHIPS — 6 setCookie variants",
      expire_code.count("cm.setCookie(u, name") == 6)
check("host enumeration covers the partition surface (music./accounts. too)",
      '"music." + base' in expire_code
      and '"accounts." + base' in expire_code)
check("the affected tabs' exact hosts join the wipe targets",
      "Fx.hostOf" in wipe_code
      and "!targets.contains(th)) targets.add(th)" in wipe_code)
CHIPS = pathlib.Path("/home/z/my-project/bolt-browser"
                     "/app/src/main/java/com/bolt/browser/util/CookiePartitionScan.kt")
check("the read-only partition scanner exists (util/CookiePartitionScan.kt)",
      CHIPS.exists())
if CHIPS.exists():
    chips_code = strip_comments(CHIPS.read_text(errors="ignore"))
    check("scanner is wired into the wipe flow (CookiePartitionScan call)",
          "CookiePartitionScan" in wipe_code)
    check("scanner's result is consumed bounded (join, never hangs the UI)",
          "chipsScan.join(200)" in wipe_code   # v1.3.79: 400 -> 200 ms (main-thread jank diet)
          and "targets.addAll(chipsHosts)" in wipe_code)
    check("scanner opens the cookie jars READ-ONLY",
          "OPEN_READONLY" in chips_code)
    check("scanner never writes (rawQuery only, no execSQL/INSERT/UPDATE/DELETE)",
          "rawQuery" in chips_code
          and not re.search(r"execSQL|\bINSERT\b|\bUPDATE\b|DELETE FROM", chips_code))
    check("scanner reads first-party partitions only (kSameSite rows)",
          "has_cross_site_ancestor = 0" in chips_code)
    check("scanner reads the Cookies files of every profile root + both layouts",
          'File(profile, "Cookies")' in chips_code
          and '"Default"' in chips_code
          and '"app_webview"' in chips_code)

if failures:
    print(f"\nFAIL: {len(failures)} site-wipe check(s) failed")
    sys.exit(1)
print("\nSITE WIPE BLAST RADIUS OK — per-site flow contains no global wipe")
print("FIRST-VISIT PASS OK — IndexedDB dies on disk, blast radius intact")
print("CHIPS PASS OK — partitioned cookies die with their plain twins")
