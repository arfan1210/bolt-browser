#!/usr/bin/env python3
"""v1.3.80 adblock hardening regression gate (the "stronger built-in adblock"
queue item). Five real defects this pass fixes, each pinned here:

  A1  EXTENDED-CSS DEAD RULES — uBO's "domain#?#sel" lines (291 live in
      EasyList) never worked: indexOf("##") inside "#?##card-list" handed
      parseCosmetic the domain part "domain#?" — a host key that never
      matches. Fixed by normalizing "#?#"->"##" (and "#@?#"->"#@#") BEFORE
      routing.
  A2  COSMETIC EXCEPTIONS IGNORED — "#@#" lines (338 live) fell through to
      parseNetwork() as garbage token rules; the exceptions never fired and
      sites that un-hide selectors were over-hidden.
  A3  $elemhide/$generichide/$specifichide DISCARDED — the cosmetic opt-out
      options were skipRule'd wholesale; sites that asked for no generic
      cosmetic got generic selectors anyway (breakage).
  A4  PROCEDURAL :has-text() AS BROKEN CSS — injected into the stylesheet
      where :has-text() is invalid; ONE invalid selector in a group kills
      the whole rule in CSS. Now a bounded JS pass.
  A5  QUOTE-PARANOIA — contains("\\"") dropped every selector containing a
      double quote: 2,512 live EasyList selectors (10% of cosmetics!), all
      valid attribute selectors like [id="ad"]. cssInject embeds CSS in a
      SINGLE-quoted JS string — double quotes were always safe.

Plus: the ad-flash kill (cosmetic at onPageCommitVisible, not only
onPageFinished), the adblock-OFF cosmetic leak (list selectors applied even
with the adblock disabled), the customRules dead pref (editor written, never
read), and built-in list updates (frozen at build, two weeks stale on the
day this gate was written).
"""
import re, sys, os

ROOT = os.path.join(os.path.dirname(__file__), "..")
SRC = os.path.join(ROOT, "app/src/main/java/com/bolt/browser")
FE = open(os.path.join(SRC, "adblock/FilterEngine.java"), encoding="utf8").read()
BA = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
MH = open(os.path.join(SRC, "media/MediaHook.java"), encoding="utf8").read()
FLA = open(os.path.join(SRC, "ui/FilterListsActivity.java"), encoding="utf8").read()
REL = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()

fails = 0
def ok(cond, msg):
    global fails
    print(("  PASS " if cond else "  FAIL ") + msg)
    if not cond: fails += 1

print("A1 extended-CSS normalization (the 291 dead #?# rules)")
ok('line.indexOf("#@?#")' in FE and '"#@#" + line.substring(xq + 4)' in FE,
   "#@?# normalized to #@# before routing")
ok('int qm = line.indexOf("#?#")' in FE and '"##" + line.substring(qm + 3)' in FE,
   "#?# normalized to ## before routing")
ok(FE.index('int xq = line.indexOf("#@?#")') < FE.index('int exc = line.indexOf("#@#")'),
   "normalization happens BEFORE the exception/cosmetic routing")

print("A2 cosmetic exceptions (#@#, the 338 ignored lines)")
ok("parseCosmeticException(line, exc)" in FE and 'int exc = line.indexOf("#@#")' in FE,
   "#@# lines route to parseCosmeticException")
ok(FE.index('int exc = line.indexOf("#@#")') < FE.index('line.contains("##")'),
   "exception check precedes the ## cosmetic check")
ok("cosmeticExcGlobal" in FE and "cosmeticExcHost" in FE,
   "exception storage (global + per-host) exists")
ok("Set<String> exc = new HashSet<>(cosmeticExcGlobal);" in FE
   and "collect(cosmeticExcHost, host, exc)" in FE and "all.removeAll(exc)" in FE,
   "cssFor removes excepted selectors for the host")

print("A3 $elemhide family honored (the discarded opt-outs)")
for opt, fld in (("elemhide", "elemhideHosts"), ("generichide", "generichideHosts"),
                 ("specifichide", "specifichideHosts")):
    ok(f'case "{opt}"' in FE and f"{fld}.add(h)" in FE,
       f'${opt} captured into {fld} on allow rules')
ok("hostKeyOf(pattern)" in FE and "pattern.startsWith(\"||\")" in FE,
   "hostKeyOf extracts ||domain^ hosts for the opt-out sets")
ok("hostInSet(elemhideHosts, host)" in FE and "noGeneric" in FE and "noSpecific" in FE,
   "cssFor computes the hide flags from the opt-out sets")
ok("all.addAll(globalSelectors)" in FE and "noGeneric && listsOn" in FE,
   "generic selectors skipped on $generichide hosts")

print("A4 procedural :has-text() (was invalid CSS)")
ok('selector.indexOf(":has-text(")' in FE and "procGlobal.add(pair)" in FE
   and "procHost.computeIfAbsent" in FE,
   ":has-text rules stored procedurally, never as stylesheet CSS")
ok("PROC_UNSUPPORTED" in FE and "-abp-properties" in FE,
   "unsupported procedural extensions (style/remove/matches-*/-abp-*) skip the rule")
ok("matchingParen" in FE and "depth--" in FE,
   "depth-aware paren matcher for nested :has-text args")
ok("PROC_JS_TEMPLATE" in FE and "__RULES__" in FE and "__boltProc" in FE
   and "querySelectorAll" in FE and "3000" in FE and "60000" in FE,
   "procedural JS template: guard, rule substitution, bounds")
ok("rules.size() > 80" in FE, "procedural rules capped at 80 per page")
ok("public String proceduralJs(String host, List<String> siteFilters, boolean listsOn)"
   in FE,
   "proceduralJs signature carries the adblock on/off decision")

print("A5 quote-safety (the 2,512 dropped attribute selectors)")
# the relaxed non-procedural path: backslash-only rejection on its own line;
# the old combo rejected quotes too. (The :has-text() branch above it KEEPS
# its strict validation — that is deliberate.)
ok('if (selector.contains("\\\\")) return;' in FE,
   "the relaxed parseCosmetic line (backslash-only rejection)")
ok('selector.contains("\\\\") || selector.contains("\\\"")' not in FE,
   "the old quote-rejecting combo is gone")
ok('!sel.isEmpty() && !sel.contains("\\\\")' in FE,
   "user site-rule selectors: same relaxation in cssFor")

print("A6 the ad-flash kill + the adblock-OFF cosmetic leak")
commit = BA[BA.index("onPageCommitVisible(WebView view, String url)"):
           BA.index("SPA navigation tracking")]
ok("injectAdblockCosmetic(view, url);" in commit,
   "cosmetic pass runs at onPageCommitVisible (earliest reliable new document)")
ok('cssInjectOnce("bolt-adcss", css)' in BA,
   "the shared bolt-adcss style element (replaced, never stacked)")
ok("adblockListsOn(String base)" in BA and "prefs.adblockEnabled()" in BA
   and 'prefs.siteBool(base, "noAds", false)' in BA,
   "adblockListsOn mirrors the network interceptor's on/off decision")
# v1.4.31 re-point (2026-11-25): the payload rides the (host, list-
# generation) cache — the gating still comes from adblockListsOn(base),
# now feeding both the cache key and the on argument
ok("FilterEngine.get().cssFor(host," in BA
   and "prefs.siteFilters(base), prefs.siteZaps(base), on)" in BA
   and "boolean on = adblockListsOn(base);" in BA,
   "cssFor payload gated on the adblock actually being on (cached)")

print("A7 idempotent injection")
ok("cssInjectOnce(String id, String css)" in MH and "getElementById('" in MH
   and "s.textContent='" in MH,
   "MediaHook.cssInjectOnce: one style per id, textContent replaced")

print("A8 the customRules dead pref (written by the editor, read by nothing)")
ok('parse(p.s("customRules", ""), "Custom")' in FE,
   "global custom rules parsed into the engine")

print("A9 built-in list updates (frozen-at-build fix)")
ok("builtinList(Context c, Prefs p, String key)" in FE and "100_000L" in FE,
   "downloaded builtin preferred over the bundled asset, with the 100 KB guard")
ok('return "https://easylist.to/easylist/" + key + ".txt";' in FE,
   "update URL is the canonical easylist.to home")
ok('FilterEngine.builtinUpdateFile(prefs, key)' in FLA
   and 'txt.length() > 100_000' in FLA and 'prefs.set("listTs_" + key' in FLA,
   "Update-all refreshes built-ins with the same size guard + a timestamp")
ok('prefs.l("listTs_" + key, 0)' in FLA and "updated " in FLA,
   "the list row shows the last update date")

print("A10 gate wiring")
ok("test_adblock.py" in REL, "this gate runs in release.sh")
ok("test_adblock_proc.js" in REL, "the procedural mock test runs in release.sh")

print("A11 live-list sanity (real lines from the bundled asset)")
ASSET = open(os.path.join(ROOT, "app/src/main/assets/filters/easylist.txt"),
             encoding="utf8", errors="replace").read()
exc_lines = [l for l in ASSET.split("\n") if "#@#" in l and not l.startswith("!")]
ok(len(exc_lines) > 50, f"the bundled asset carries #@# exception lines ({len(exc_lines)})")
ht_lines = [l for l in ASSET.split("\n") if ":has-text(" in l and not l.startswith("!")]
ok(len(ht_lines) > 3, f"the bundled asset carries :has-text rules ({len(ht_lines)})")
# mirror of the normalization step only (three character-level replaces —
# safe to mirror because they are the whole logic)
def normalize(line):
    xq = line.find("#@?#")
    if xq >= 0: line = line[:xq] + "#@#" + line[xq+4:]
    qm = line.find("#?#")
    if qm >= 0: line = line[:qm] + "##" + line[qm+3:]
    return line
sample = next(l for l in ht_lines if "#?#" in l)
norm = normalize(sample)
ok("##" in norm and "#?#" not in norm and norm.split("##")[0].endswith(
       tuple("abcdefghijklmnopqrstuvwxyz0123456789")),
   "normalization leaves a clean domain##selector split (was domain#?)")

print()
if fails:
    print(f"FAIL: {fails} adblock contract(s) broken")
    sys.exit(1)
print("OK: v1.3.80 adblock contracts hold — #?#/#@#/$elemhide/:has-text/"
      "quote-safety/early-inject/off-gate/customRules/builtin-updates")
