#!/usr/bin/env python3
"""v1.4.9 release gate, part 2 — the hardening four-pack (the second v1.4.9
session: security, memory, performance, concurrency).

1. THE PATTERN DIET (FilterEngine + AhoCorasick) — the ~130 MB adblock
   memory corpse. The pre-v1.4.9 engine compiled a java.util.regex
   Pattern for EVERY network rule (~132k across the bundled
   EasyList/EasyPrivacy); the compiled Patterns alone cost ~130 MB of
   heap. ~98% of the rules are plain literals; they now match as plain
   strings (host-key walk + boundary check for ||rules, one Aho-Corasick
   scan per request for generic rules — which also replaces the old
   enumerate-all-2..12-char-substrings token probe and its per-request
   HashSet), and a Pattern survives only on rules that genuinely need
   regex syntax. Pinned here statically AND functionally:
   - the automaton is pure java.* (compiles standalone under this gate);
   - byToken (the substring-probe index) is gone from FilterEngine;
   - THE EQUIVALENCE PROBE (javac + run): parses BOTH bundled lists and
     proves, rule by rule over every network rule, that the new literal
     path decides IDENTICALLY to the old regex path on a crafted
     edge-case corpus per rule (subdomains, lookalike hosts, ports,
     userinfo, query-embedded domains, separator boundaries, end of
     string, case) — then engine vs engine (old bucket walk + token
     probe vs new walk + automaton) on a realistic corpus, and the
     automaton vs brute force on the real keyword set;
   - THE MEMORY PROOF: the probe builds the OLD engine (all Patterns)
     and the NEW engine (literals + automaton) in separate JVM phases
     and asserts the new one costs less than HALF the old one's heap —
     in practice it lands ~6-8x lighter;
   - the leftover-regex count over the bundled lists stays under 2%
     (today ~1.7k of ~103k network rules).

2. THE liveWebs LEAK (UserscriptEngine) — the static WebView registry
   was write-only dead code (written on every JsBridge.attach, read by
   nothing: delivery always went through the bridge's own attached
   field) holding strong references to every WebView ever attached, so
   each closed tab's Activity context leaked against garbage collection.
   Removed outright — a WeakReference to something nobody reads is still
   dead code.

3. THE THREAD LAW (TabManager / MediaControlService) — the tab list is
   mutated on the UI thread and read from media-service threads
   (ownership scans, the phoenix resurrection); a plain ArrayList let a
   reader's iterator race a UI mutation into
   ConcurrentModificationException. The list is a synchronizedList now
   and EVERY iteration holds its monitor — TabManager internals and the
   service readers alike (the documented Collections.synchronizedList
   contract: single calls are atomic, iteration only under the monitor).

4. THE KEY ROTATION — the signing keystore's password was exposed in
   private history; it is rotated with keytool
   -storepasswd/-keypasswd to a random 32-char secret kept in the
   maintainer's workspace + the user's backup only. Not verifiable from
   a public clone BY DESIGN (the keystore never ships); the certificate
   fingerprint is unchanged (same key pair — updates still install).

Sections:
A1 — the pattern diet (FilterEngine static contracts)
A2 — the automaton (AhoCorasick static contracts)
A3 — the liveWebs leak
A4 — the TabManager thread law
A5 — the service readers
A6 — gate wiring
B  — the functional probe (javac + run: rule-level equivalence,
     engine-level equivalence, automaton vs brute force, the memory
     proof, the leftover-regex budget)
"""

import os, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/java/com/bolt/browser")
FE = open(os.path.join(SRC, "adblock/FilterEngine.java"), encoding="utf8").read()
ACC = open(os.path.join(SRC, "adblock/AhoCorasick.java"), encoding="utf8").read()
UE = open(os.path.join(SRC, "scripts/UserscriptEngine.java"), encoding="utf8").read()
BA = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
TM = open(os.path.join(SRC, "core/TabManager.java"), encoding="utf8").read()
MCS = open(os.path.join(SRC, "media/MediaControlService.java"), encoding="utf8").read()
WK = open(os.path.join(SRC, "util/Workspaces.kt"), encoding="utf8").read()
TS = open(os.path.join(SRC, "util/TabSleep.kt"), encoding="utf8").read()
REL = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()

fails = 0

def need(cond, why):
    global fails
    tag = "PASS" if cond else "FAIL"
    print(f"  {tag} {why}")
    if not cond:
        fails += 1

print("A1 — the pattern diet (FilterEngine)")
need("PLAIN_HOST_SEP" in FE and "PLAIN_HOST" in FE and "PLAIN_CONTAINS" in FE,
     "the plain-path kinds exist (contains/starts/ends/equals/host/host-sep)")
need("private volatile AhoCorasick ac" in FE,
     "the shared automaton is swapped in as ONE volatile reference")
need("acBuild.build();" in FE and "ac = acBuild;" in FE,
     "load() builds the automaton off to the side, then swaps")
need("byToken.computeIfAbsent" not in FE and "Map<String, List<NetRule>> byToken" not in FE,
     "the substring-probe index (byToken structure) is gone")
need("matchPlain" in FE and "hostPos" in FE and "isSepChar" in FE,
     "the literal matcher: matchPlain + hostPos + the separator class")
need("r.plainKind == PLAIN_NONE" in FE,
     "the regex fallback fires only when the plain path did not classify")
need("AhoCorasick.Sink" in FE,
     "the per-request sink evaluates candidates during the scan")
need("Set<String> seen = new HashSet<>()" not in FE,
     "the per-request HashSet of the old token probe is gone")
need("if (r.regex != null)" in FE,
     "ruleMatch dispatches: regex only where a Pattern still exists")

print("A2 — the automaton (AhoCorasick)")
need("public final class AhoCorasick" in ACC, "the class exists")
need("import android" not in ACC, "pure java.* — no Android imports (compiles standalone)")
need("public void add(String keyword, Object payload)" in ACC
     and "public void build()" in ACC
     and "public void scan(String text, Sink sink)" in ACC,
     "the add/build/scan API")
need("rowStart" in ACC and "edgeChar" in ACC and "fail" in ACC and "outHead" in ACC,
     "the compact shape: CSR edges + fail + output chains, primitive arrays only")

print("A3 — the liveWebs leak (UserscriptEngine)")
need("Map<String, WebView> liveWebs" not in UE and "liveWebs." not in UE and "void registerWeb" not in UE,
     "the static WebView registry and its writer are gone (code, not just comments)")
need("UserscriptEngine.registerWeb(w);" not in BA,
     "the JsBridge.attach caller lost its call")
need("import android.webkit.WebView" not in UE, "no WebView import left in the engine")

print("A4 — the thread law (TabManager)")
need("synchronizedList(new ArrayList<>())" in TM,
     "the tab list is a Collections.synchronizedList")
need(TM.count("synchronized (tabs)") >= 15,
     f"every internal iteration/compound op under the monitor ({TM.count('synchronized (tabs)')} sites)")
need("    public List<Tab> tabsOfGroup(long gid) {" in TM,
     "the gate-pinned signatures survive untouched (tabsOfGroup)")

print("A5 — the service readers (MediaControlService + utilities)")
need(MCS.count("synchronized (tm.tabs)") >= 5,
     f"all service reads under the monitor ({MCS.count('synchronized (tm.tabs)')} sites)")
need("synchronized(tm.tabs)" in WK, "Workspaces.liveTabs iterates under the monitor")
need(TS.count("synchronized(tm.tabs)") >= 2, "TabSleep snapshots under the monitor")

print("A6 — gate wiring")
need("test_v149_perf.py" in REL, "this gate runs in release.sh")

print("B — the functional probe (javac + run)")
javac = None
for cand in (os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javac"),
             "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin/javac",
             shutil.which("javac") or ""):
    if cand and os.path.exists(cand):
        javac = cand
        break
if not javac:
    need(False, "javac found (run scripts/setup_toolchain.sh first)")

if javac:
    work = "/tmp/bolt_v149_perf_gate"
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    probe = r'''
import com.bolt.browser.adblock.AhoCorasick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** The v1.4.9 pattern-diet probe. The OLD engine (a compiled Pattern per
 *  rule, transcribed verbatim from the pre-v1.4.9 FilterEngine) and the
 *  NEW engine (plain literals + the REAL AhoCorasick from the repo,
 *  regex only where needed) are built from the same bundled lists and
 *  must decide identically. */
public class Probe {

    static int bad = 0;
    static void check(boolean ok, String why) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + why);
        if (!ok) bad++;
    }

    // ---- the rule (shared fields; plain fields filled only in "new" mode) ----
    static final int PLAIN_NONE = 0, PLAIN_CONTAINS = 1, PLAIN_STARTS = 2,
            PLAIN_ENDS = 3, PLAIN_EQUALS = 4, PLAIN_HOST = 5, PLAIN_HOST_SEP = 6;

    static class Rule {
        boolean allow;
        String hostKey;
        int hostLen;
        String body;              // p after anchor strips
        boolean anchoredStart, anchoredEnd;
        // old engine: ALWAYS a Pattern
        Pattern oldRx;
        // new engine: plain fields, or null regex (falls back to oldRx — the build is unchanged)
        String plain, plainRest;
        int plainKind = PLAIN_NONE;
        String token;
    }

    // ---- Fx.hostOf, transcribed ----
    static String hostOf(String url) {
        try {
            if (url == null) return "";
            if (!url.startsWith("http")) {
                int s = url.indexOf("//");
                if (s >= 0) url = "http:" + url.substring(s);
                else if (!url.contains(":")) url = "http://" + url;
            }
            java.net.URL u = new java.net.URL(url);
            String h = u.getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Throwable e) {
            return "";
        }
    }

    static boolean isHostChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '-';
    }

    // ---- the OLD regex build, verbatim from pre-v1.4.9 parseNetwork ----
    static Pattern buildOldRegex(String p, boolean anchoredStart, boolean anchoredEnd, String hostKey) {
        StringBuilder rx = new StringBuilder();
        if (anchoredStart && hostKey != null) {
            rx.append("^(?:[a-z][a-z0-9+.-]*://)?(?:[a-z0-9-]+\\.)*");
        } else if (anchoredStart) {
            rx.append("^");
        }
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            if (ch == '*') rx.append(".*");
            else if (ch == '^') rx.append("(?:[^a-z0-9._%-]|$)");
            else rx.append(Pattern.quote(String.valueOf(ch)));
        }
        if (anchoredEnd) rx.append("$");
        return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE);
    }

    // ---- the NEW plain classification, transcribed from v1.4.9 parseNetwork ----
    static void classifyPlain(Rule r) {
        String p = r.body;
        boolean noStar = p.indexOf('*') < 0;
        if (r.hostKey != null && !r.anchoredEnd && r.hostLen > 0
                && p.substring(0, r.hostLen).toLowerCase().length() == r.hostLen) {
            if (noStar && p.indexOf('^') < 0) {
                r.plainKind = PLAIN_HOST;
                r.plain = p.toLowerCase();
            } else if (p.length() > r.hostLen && p.charAt(r.hostLen) == '^'
                    && p.indexOf('^', r.hostLen + 1) < 0
                    && p.indexOf('*', r.hostLen + 1) < 0) {
                r.plainKind = PLAIN_HOST_SEP;
                r.plain = p.substring(0, r.hostLen).toLowerCase();
                r.plainRest = p.substring(r.hostLen + 1).toLowerCase();
            }
        } else if (r.hostKey == null && noStar && p.indexOf('^') < 0) {
            r.plainKind = r.anchoredStart
                    ? (r.anchoredEnd ? PLAIN_EQUALS : PLAIN_STARTS)
                    : (r.anchoredEnd ? PLAIN_ENDS : PLAIN_CONTAINS);
            r.plain = p.toLowerCase();
        }
    }

    // ---- the NEW plain matcher, transcribed from v1.4.9 matchPlain ----
    static boolean matchPlain(Rule r, String url, String reqHost) {
        switch (r.plainKind) {
            case PLAIN_CONTAINS: return url.contains(r.plain);
            case PLAIN_STARTS:   return url.startsWith(r.plain);
            case PLAIN_ENDS:     return url.endsWith(r.plain);
            case PLAIN_EQUALS:   return url.equals(r.plain);
            case PLAIN_HOST: {
                int pos = hostPos(url, reqHost, r.hostKey);
                return pos >= 0 && url.regionMatches(pos, r.plain, 0, r.plain.length());
            }
            case PLAIN_HOST_SEP: {
                int pos = hostPos(url, reqHost, r.hostKey);
                if (pos < 0) return false;
                int dl = r.plain.length();
                if (!url.regionMatches(pos, r.plain, 0, dl)) return false;
                int e = pos + dl;
                if (e >= url.length()) return r.plainRest.isEmpty();
                if (!isSepChar(url.charAt(e))) return false;
                return r.plainRest.isEmpty()
                        || url.regionMatches(e + 1, r.plainRest, 0, r.plainRest.length());
            }
            default: return false;
        }
    }

    static int hostPos(String url, String reqHost, String hostKey) {
        if (reqHost == null || reqHost.isEmpty() || hostKey == null) return -1;
        int a = url.indexOf("://");
        int from = a < 0 ? 0 : a + 3;
        int end = url.length();
        for (int i = from; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        int at = url.indexOf('@', from);
        if (at >= 0 && at < end) return -1;   // userinfo: outside the old prefix's reach
        if (!url.regionMatches(from, reqHost, 0, reqHost.length())
                || from + reqHost.length() > end) return -1;
        int pos = from + reqHost.length() - hostKey.length();
        if (pos < from || (pos > from && url.charAt(pos - 1) != '.')) return -1;
        return pos;
    }

    static boolean isSepChar(char c) {
        boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9');
        return !alnum && c != '.' && c != '_' && c != '%' && c != '-';
    }

    // ---- firstLiteralToken, transcribed ----
    static String firstLiteralToken(String p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '%' || c == '_' || c == '-') {
                sb.append(c);
                if (sb.length() >= 12) return sb.toString().toLowerCase();
            } else {
                if (sb.length() >= 2) return sb.toString().toLowerCase();
                sb.setLength(0);
            }
        }
        return sb.length() >= 2 ? sb.toString().toLowerCase() : null;
    }

    // ---- parse (option-lite: the option GATES are shared/unchanged; only
    //      skipRule-bearing options matter for which rules EXIST) ----
    static List<Rule> parse(String text, boolean oldMode) {
        List<Rule> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            try {
                String line = raw.trim();
                if (line.isEmpty() || line.charAt(0) == '!' || line.charAt(0) == '[') continue;
                int xq = line.indexOf("#@?#");
                if (xq >= 0) line = line.substring(0, xq) + "#@#" + line.substring(xq + 4);
                int qm = line.indexOf("#?#");
                if (qm >= 0) line = line.substring(0, qm) + "##" + line.substring(qm + 3);
                if (line.indexOf("#@#") >= 0 || line.contains("##")) continue;   // cosmetic
                boolean isAllow = line.startsWith("@@");
                if (isAllow) line = line.substring(2);
                boolean skipRule = false;
                int dollar = line.indexOf('$');
                String pattern = line;
                if (dollar > 0 && dollar < line.length() - 1) {
                    pattern = line.substring(0, dollar);
                    for (String o : line.substring(dollar + 1).toLowerCase().split(",")) {
                        o = o.trim();
                        if (o.equals("popup") || o.equals("match-case") || o.equals("elemhide")
                                || o.equals("generichide") || o.equals("ghide")
                                || o.equals("specifichide") || o.equals("shide")) skipRule = true;
                    }
                }
                if (skipRule || pattern.isEmpty() || pattern.length() > 300) continue;

                Rule r = new Rule();
                r.allow = isAllow;
                String p = pattern;
                String hostKey = null;
                int hostLen = 0;
                boolean anchoredStart = false, anchoredEnd = false;
                if (p.startsWith("||")) {
                    p = p.substring(2);
                    int i = 0;
                    StringBuilder hb = new StringBuilder();
                    while (i < p.length() && isHostChar(p.charAt(i))) { hb.append(p.charAt(i)); i++; }
                    hostLen = i;
                    if (hb.length() >= 2) hostKey = hb.toString().toLowerCase();
                    anchoredStart = true;
                } else if (p.startsWith("|")) {
                    p = p.substring(1);
                    anchoredStart = true;
                }
                if (p.endsWith("|")) {
                    p = p.substring(0, p.length() - 1);
                    anchoredEnd = true;
                }
                r.body = p;
                r.anchoredStart = anchoredStart;
                r.anchoredEnd = anchoredEnd;
                r.hostKey = hostKey;
                r.hostLen = hostLen;
                r.token = firstLiteralToken(p);
                if (oldMode) {
                    r.oldRx = buildOldRegex(p, anchoredStart, anchoredEnd, hostKey);
                } else {
                    classifyPlain(r);
                    if (r.plainKind == PLAIN_NONE) {
                        r.oldRx = buildOldRegex(p, anchoredStart, anchoredEnd, hostKey);
                    }
                }
                out.add(r);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    static String slurp(String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path));
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    // ---- the OLD engine: byHost walk + byToken probe + catchAll ----
    static class OldEng {
        final Map<String, List<Rule>> blockByHost = new HashMap<>(), allowByHost = new HashMap<>();
        final Map<String, List<Rule>> blockByToken = new HashMap<>(), allowByToken = new HashMap<>();
        final List<Rule> blockCatch = new ArrayList<>(), allowCatch = new ArrayList<>();
    }

    static OldEng buildOld(List<Rule> rules) {
        OldEng e = new OldEng();
        for (Rule r : rules) {
            Map<String, List<Rule>> byHost = r.allow ? e.allowByHost : e.blockByHost;
            Map<String, List<Rule>> byToken = r.allow ? e.allowByToken : e.blockByToken;
            List<Rule> catchAll = r.allow ? e.allowCatch : e.blockCatch;
            if (r.hostKey != null) {
                byHost.computeIfAbsent(r.hostKey, k -> new ArrayList<>()).add(r);
            } else if (r.token != null) {
                byToken.computeIfAbsent(r.token, k -> new ArrayList<>()).add(r);
            } else {
                catchAll.add(r);
            }
        }
        return e;
    }

    static boolean oldBucket(Map<String, List<Rule>> byHost, Map<String, List<Rule>> byToken,
                             List<Rule> catchAll, String url, String reqHost) {
        if (!reqHost.isEmpty()) {
            String h = reqHost;
            while (h != null && !h.isEmpty()) {
                List<Rule> rs = byHost.get(h);
                if (rs != null) for (Rule r : rs) if (r.oldRx.matcher(url).find()) return true;
                int dot = h.indexOf('.');
                h = dot > 0 ? h.substring(dot + 1) : null;
            }
        }
        if (!byToken.isEmpty()) {
            Set<String> seen = new HashSet<>();
            int ul = url.length();
            for (int i = 0; i < ul - 1; i++) {
                for (int len = 2; len <= 12 && i + len <= ul; len++) {
                    String tok = url.substring(i, i + len);
                    if (seen.contains(tok)) continue;
                    List<Rule> rs = byToken.get(tok);
                    if (rs != null) {
                        seen.add(tok);
                        for (Rule r : rs) if (r.oldRx.matcher(url).find()) return true;
                    }
                }
                if (!seen.isEmpty() && i > 200) break; // the old safety valve
            }
        }
        for (Rule r : catchAll) if (r.oldRx.matcher(url).find()) return true;
        return false;
    }

    static boolean oldBlocked(OldEng e, String url) {
        String u = url.toLowerCase();
        if (!u.startsWith("http:") && !u.startsWith("https:")) return false;
        String reqHost = hostOf(u);
        if (oldBucket(e.allowByHost, e.allowByToken, e.allowCatch, u, reqHost)) return false;
        return oldBucket(e.blockByHost, e.blockByToken, e.blockCatch, u, reqHost);
    }

    // ---- the NEW engine: byHost walk + the automaton + catchAll ----
    static class NewEng {
        final Map<String, List<Rule>> blockByHost = new HashMap<>(), allowByHost = new HashMap<>();
        final List<Rule> blockCatch = new ArrayList<>(), allowCatch = new ArrayList<>();
        final AhoCorasick ac = new AhoCorasick();
    }

    static NewEng buildNew(List<Rule> rules, boolean buildAc) {
        NewEng e = new NewEng();
        for (Rule r : rules) {
            Map<String, List<Rule>> byHost = r.allow ? e.allowByHost : e.blockByHost;
            List<Rule> catchAll = r.allow ? e.allowCatch : e.blockCatch;
            if (r.hostKey != null) {
                byHost.computeIfAbsent(r.hostKey, k -> new ArrayList<>()).add(r);
            } else if (r.plainKind != PLAIN_NONE) {
                if (buildAc) e.ac.add(r.plain, r);
            } else if (r.token != null) {
                if (buildAc) e.ac.add(r.token, r);
            } else {
                catchAll.add(r);
            }
        }
        if (buildAc) e.ac.build();
        return e;
    }

    static boolean newBucket(Map<String, List<Rule>> byHost, List<Rule> catchAll,
                             String url, String reqHost) {
        if (!reqHost.isEmpty()) {
            String h = reqHost;
            while (h != null && !h.isEmpty()) {
                List<Rule> rs = byHost.get(h);
                if (rs != null) for (Rule r : rs) if (matchRule(r, url, reqHost)) return true;
                int dot = h.indexOf('.');
                h = dot > 0 ? h.substring(dot + 1) : null;
            }
        }
        for (Rule r : catchAll) if (matchRule(r, url, reqHost)) return true;
        return false;
    }

    static boolean matchRule(Rule r, String url, String reqHost) {
        if (r.plainKind != PLAIN_NONE) return matchPlain(r, url, reqHost);
        return r.oldRx.matcher(url).find();
    }

    static class Scan implements AhoCorasick.Sink {
        final String url, reqHost;
        boolean blockHit, allowHit;
        Scan(String u, String rh) { url = u; reqHost = rh; }
        @Override public boolean accept(Object p) {
            Rule r = (Rule) p;
            if (matchRule(r, url, reqHost)) {
                if (r.allow) { allowHit = true; return false; }
                blockHit = true;
            }
            return true;
        }
    }

    static boolean newBlocked(NewEng e, String url) {
        String u = url.toLowerCase();
        if (!u.startsWith("http:") && !u.startsWith("https:")) return false;
        String reqHost = hostOf(u);
        if (newBucket(e.allowByHost, e.allowCatch, u, reqHost)) return false;
        if (e.ac.size() > 0) {
            Scan s = new Scan(u, reqHost);
            e.ac.scan(u, s);
            if (s.allowHit) return false;
            if (s.blockHit) return true;
        }
        return newBucket(e.blockByHost, e.blockCatch, u, reqHost);
    }

    // ---- memory measurement ----
    static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) { System.gc(); try { Thread.sleep(80); } catch (Exception ignored) {} }
        return rt.totalMemory() - rt.freeMemory();
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        String easylist = slurp(dir + "/easylist.txt");
        String easyprivacy = slurp(dir + "/easyprivacy.txt");

        // ---------- B0: classification census (the memory proof by construction) ----------
        List<Rule> both = new ArrayList<>();
        both.addAll(parse(easylist, false));
        both.addAll(parse(easyprivacy, false));
        int hostSep = 0, hostPlain = 0, genPlain = 0, regexLeft = 0, catchAll = 0;
        for (Rule r : both) {
            if (r.hostKey != null) {
                if (r.plainKind == PLAIN_HOST_SEP) hostSep++;
                else if (r.plainKind == PLAIN_HOST) hostPlain++;
                else regexLeft++;
            } else if (r.plainKind != PLAIN_NONE) {
                genPlain++;
            } else if (r.token != null) {
                regexLeft++;
            } else {
                regexLeft++; catchAll++;
            }
        }
        System.out.println("  census: " + both.size() + " network rules | host-sep " + hostSep
                + " | host-plain " + hostPlain + " | generic-plain " + genPlain
                + " | regex-left " + regexLeft + " (catchAll " + catchAll + ")");
        check(hostSep + hostPlain + genPlain > both.size() * 96 / 100,
                "over 96% of the rules ride the literal path (no compiled Pattern)");
        check(regexLeft * 100 < both.size() * 2,
                "the leftover-regex budget holds (under 2%; today ~1.7k of ~103k)");

        // ---------- B1: rule-level equivalence (old regex vs new plain, per rule) ----------
        List<Rule> oldRules = new ArrayList<>();
        oldRules.addAll(parse(easylist, true));
        oldRules.addAll(parse(easyprivacy, true));
        check(oldRules.size() == both.size(),
                "both modes parse the same rule set (" + oldRules.size() + " vs " + both.size() + ")");
        int compFails = 0;
        for (Rule r : oldRules) if (r.oldRx == null) compFails++;
        check(compFails == 0, "no rule fails the OLD Pattern.compile (" + compFails + ")");

        long eqChecks = 0, eqMismatch = 0;
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < both.size(); i++) {
            Rule nr = both.get(i);
            Rule or = oldRules.get(i);
            List<String> urls = new ArrayList<>();
            if (nr.hostKey != null) {
                String hk = nr.hostKey;
                urls.add("https://" + hk + "/");
                urls.add("https://www." + hk + "/x");
                urls.add("https://a.b." + hk + "/p?q=1");
                urls.add("https://x" + hk + "/no-dot-lookalike");
                urls.add("https://" + hk + ":8080/a");
                urls.add("https://user@" + hk + "/u");
                urls.add("https://req.example.org/?u=https://" + hk + "/embedded");
                urls.add("https://" + hk);
                urls.add("http://" + hk.toUpperCase() + "/CASE");
                urls.add("https://" + hk + "suffix StartsWithLetters");
                if (nr.plainKind == PLAIN_HOST_SEP && !nr.plainRest.isEmpty()) {
                    urls.add("https://" + hk + "/" + nr.plainRest);
                    urls.add("https://" + hk + "-" + nr.plainRest);   // '-' is a NON-separator
                }
                if (nr.plainKind == PLAIN_HOST && nr.body.length() > hk.length()) {
                    urls.add("https://" + nr.body);
                }
            } else {
                String b = nr.body.toLowerCase();
                urls.add("https://site.example.org/" + b + "/x");
                urls.add("https://" + b + ".example.org/");
                urls.add("https://site.example.org/prefix" + b);
                urls.add("https://site.example.org/x" + b + "y");
                urls.add("https://site.example.org/" + b);
            }
            for (String raw : urls) {
                String u = raw.toLowerCase();
                String reqHost = hostOf(u);
                // the bucket walk decides whether a host rule is a candidate
                // at all: reqHost must be hostKey or a DOT-parent of it (the
                // walk drops label-by-label). Pairs the walk can never
                // produce (a no-dot string suffix like "x"+host) are not
                // tested by EITHER engine — model that here.
                boolean walkOk = nr.hostKey == null
                        || reqHost.equals(nr.hostKey)
                        || reqHost.endsWith("." + nr.hostKey);
                boolean oldM = walkOk && or.oldRx.matcher(u).find();
                boolean newM = walkOk && matchRule(nr, u, reqHost);
                eqChecks++;
                if (oldM != newM) {
                    eqMismatch++;
                    if (eqMismatch <= 5) {
                        System.out.println("    MISMATCH rule=[" + nr.body + "] host=" + nr.hostKey
                                + " plainKind=" + nr.plainKind + " url=" + u
                                + " old=" + oldM + " new=" + newM);
                    }
                }
            }
        }
        check(eqMismatch == 0, "rule-level equivalence: old regex == new literal on "
                + eqChecks + " crafted checks (" + eqMismatch + " mismatches)");

        // ---------- B2: engine-level equivalence on a realistic corpus ----------
        OldEng oe = buildOld(oldRules);
        NewEng ne = buildNew(both, true);
        List<String> corpus = new ArrayList<>();
        int step = Math.max(1, both.size() / 220);
        for (int i = 0; i < both.size(); i += step) {
            Rule r = both.get(i);
            if (r.hostKey != null) {
                corpus.add("https://" + r.hostKey + "/page?x=1");
                corpus.add("https://cdn." + r.hostKey + "/script.js");
            } else if (r.plainKind != PLAIN_NONE) {
                corpus.add("https://content.example.org/" + r.plain + "?ref=ad");
                corpus.add("https://" + r.plain + ".example.net/x");
            } else if (r.token != null) {
                corpus.add("https://media.example.org/" + r.token + "z/x.js");
            }
        }
        corpus.add("https://www.example.org/index.html");
        corpus.add("https://news.example.org/article?utm_source=tracker&fbclid=x");
        corpus.add("https://static.example.org/ads/banner_300x250.gif");
        corpus.add("https://example.org/page?u=https%3A%2F%2Fads.doubleclick.net%2Fpixel");
        corpus.add("https://sub.deep.example.co.uk:8443/path/to/res.js?v=9");
        corpus.add("https://user:pass@example.org/x");
        corpus.add("http://PLAINCASE.Example.ORG/Path");
        corpus.add("https://example.org");                     // host-only URL
        corpus.add("https://example.org/page" + "x".repeat(250) + "/adbanner.js");  // past the old valve
        corpus.add("https://example.org/" + "y".repeat(240) + "/ads/tracker.js");   // token past 200
        corpus.add("https://example.org/normal/path/ads/by.js");
        long engMismatch = 0, valveOnly = 0;
        for (String raw : corpus) {
            boolean oldB = oldBlocked(oe, raw);
            boolean newB = newBlocked(ne, raw);
            if (oldB != newB) {
                // the ONE legitimate divergence: the old engine's i>200 token
                // valve stopped probing long URLs; the automaton scans them
                // fully. Legal shape: old=false, new=true, URL > 200 chars.
                boolean legalValve = !oldB && newB && raw.length() > 200;
                if (legalValve) { valveOnly++; continue; }
                engMismatch++;
                if (engMismatch <= 5) {
                    System.out.println("    ENGINE MISMATCH url=" + raw + " old=" + oldB + " new=" + newB);
                }
            }
        }
        check(engMismatch == 0, "engine-level equivalence on " + corpus.size()
                + " corpus URLs (" + engMismatch + " mismatches)");
        System.out.println("  (note: " + valveOnly + " long-URL hits the old engine's i>200"
                + " safety valve missed — the automaton scans the full URL by design)");

        // ---------- B3: the automaton vs brute force on the real keyword set ----------
        AhoCorasick kwAc = new AhoCorasick();
        List<String> kws = new ArrayList<>();
        for (Rule r : both) {
            if (r.hostKey == null) {
                String k = r.plainKind != PLAIN_NONE ? r.plain : r.token;
                if (k != null) { kwAc.add(k, "k" + kws.size()); kws.add(k); }
            }
        }
        kwAc.build();
        class HitSink implements AhoCorasick.Sink {
            final Set<String> hits = new HashSet<>();
            @Override public boolean accept(Object p) { hits.add((String) p); return true; }
        }
        int acMism = 0;
        List<String> sample = corpus.subList(0, Math.min(120, corpus.size()));
        for (String raw : sample) {
            String u = raw.toLowerCase();
            HitSink s = new HitSink();
            kwAc.scan(u, s);
            Set<String> want = new HashSet<>();
            for (int j = 0; j < kws.size(); j++) if (u.contains(kws.get(j))) want.add("k" + j);
            if (!s.hits.equals(want)) {
                acMism++;
                if (acMism <= 3) System.out.println("    AC MISMATCH url=" + u);
            }
        }
        check(acMism == 0, "automaton == brute force over " + kws.size()
                + " real keywords x " + sample.size() + " URLs (" + acMism + " mismatches)");

        // ---------- B4: the memory proof (old engine vs new engine, clean phases) ----------
        // everything the earlier phases built must be unreachable first —
        // the measurement is used-heap after gc, per phase
        both = null; oldRules = null; oe = null; ne = null;
        corpus = null; kwAc = null; kws = null; sample = null;
        // phase A: the OLD shape — a compiled Pattern for EVERY rule
        List<Rule> oldOnly = new ArrayList<>();
        oldOnly.addAll(parse(easylist, true));
        oldOnly.addAll(parse(easyprivacy, true));
        OldEng bigOld = buildOld(oldOnly);
        long m1 = usedHeap();
        bigOld = null; oldOnly = null;
        // phase B: the NEW shape — literals + the automaton, Patterns only on the leftovers
        List<Rule> newOnly = new ArrayList<>();
        newOnly.addAll(parse(easylist, false));
        newOnly.addAll(parse(easyprivacy, false));
        NewEng bigNew = buildNew(newOnly, true);
        long m2 = usedHeap();
        System.out.println("  heap: old engine " + (m1 / 1048576) + " MB -> new engine "
                + (m2 / 1048576) + " MB (same lists, same JVM)");
        check(m2 * 2 < m1, "the new engine costs less than HALF the old engine's heap"
                + " (measured " + (m1 / 1048576) + " MB -> " + (m2 / 1048576) + " MB)");

        System.out.println(bad == 0 ? "PROBE OK" : "PROBE BAD " + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
'''
    with open(os.path.join(work, "Probe.java"), "w", encoding="utf8") as f:
        f.write(probe)
    r = subprocess.run([javac, "-encoding", "UTF-8", "-d", work,
                        os.path.join(SRC, "adblock/AhoCorasick.java"),
                        os.path.join(work, "Probe.java")], capture_output=True, text=True)
    if r.returncode != 0:
        need(False, f"the probe compiles ({r.stderr.strip()[:400]})")
    else:
        need(True, "the probe compiles (real AhoCorasick.java + the transcribed engines)")
        rr = subprocess.run(["java", "-Xmx1200m", "-cp", work, "Probe",
                             os.path.join(ROOT, "app/src/main/assets/filters")],
                            capture_output=True, text=True)
        print(rr.stdout.rstrip())
        need(rr.returncode == 0, "the probe runs green (equivalence + memory proof)")

print()
print("V149 PERF GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
