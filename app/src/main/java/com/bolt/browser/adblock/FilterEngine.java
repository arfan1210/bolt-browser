package com.bolt.browser.adblock;

import android.content.Context;

import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Prefs;

import java.util.ArrayList;
import java.io.File;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EasyList / EasyPrivacy engine with faithful rule parsing:
 * - ||domain^ anchors, | anchors, ^ separators, * wildcards
 * - $options: resource types (image/script/...), third-party, domain=
 * - @@ exception rules win over block rules
 * - ## element hiding with per-domain scoping
 * - main-frame navigations are never blocked by URL filters (fixes the
 *   "images disappearing" over-blocking bug: rules with type options only
 *   apply to matching resource types)
 */
public class FilterEngine {

    public enum Type { DOCUMENT, SCRIPT, IMAGE, STYLESHEET, XHR, SUBDOC, FONT, MEDIA, OTHER }

    static class NetRule {
        Pattern regex;
        String hostKey;          // for ||rules
        EnumSet<Type> types;     // empty = all types
        Boolean thirdParty;      // null = any
        String[] domAllow, domDeny; // domain= option
    }

    private static class Buckets {
        final Map<String, List<NetRule>> byHost = new HashMap<>();
        final Map<String, List<NetRule>> byToken = new HashMap<>();
        final List<NetRule> catchAll = new ArrayList<>();
        int count;
    }

    private static volatile FilterEngine instance;
    private final Buckets block = new Buckets();
    private final Buckets allow = new Buckets();
    private final Set<String> globalSelectors = new HashSet<>();
    private final Map<String, List<String>> hostSelectors = new HashMap<>();
    private final Map<String, List<String>> negSelectors = new HashMap<>();
    // v1.3.80 — cosmetic EXCEPTIONS ("#@#"): a selector the lists un-hide for a
    // host ("domain#@#.ad" = never hide .ad on domain). 338 live rules in
    // EasyList today; before v1.3.80 these lines fell into parseNetwork() as
    // garbage token rules and the exceptions never fired (over-hiding).
    private final Set<String> cosmeticExcGlobal = new HashSet<>();
    private final Map<String, List<String>> cosmeticExcHost = new HashMap<>();
    // v1.3.80 — $elemhide / $generichide / $specifichide exception hosts
    // ("@@||site^$generichide" = the site asked for NO generic cosmetic).
    // Before v1.3.80 these options were skipRule'd wholesale, so generic
    // selectors applied on sites that explicitly opted out (breakage).
    private final Set<String> elemhideHosts = new HashSet<>();
    private final Set<String> generichideHosts = new HashSet<>();
    private final Set<String> specifichideHosts = new HashSet<>();
    // v1.3.80 — PROCEDURAL :has-text() filters ("##.x:has-text(Sponsored)").
    // 288 live rules in EasyList; before v1.3.80 they were injected as plain
    // CSS where :has-text() is invalid — and one invalid selector in a GROUP
    // kills the whole rule in CSS. Now: base selector + literal needle, run
    // by proceduralJs() (a bounded JS pass), never in the stylesheet.
    private final List<String[]> procGlobal = new ArrayList<>();
    private final Map<String, List<String[]>> procHost = new HashMap<>();
    private final Set<String> adultDomains = new HashSet<>();
    public volatile boolean ready = false;
    private int ruleCount = 0;

    private FilterEngine() {
        seedAdult();
    }

    public static FilterEngine get() {
        if (instance == null) synchronized (FilterEngine.class) {
            if (instance == null) instance = new FilterEngine();
        }
        return instance;
    }

    /** Build lists on a background thread. */
    public static void warmAsync(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> get().load(app), "filters").start();
    }

    public synchronized void load(Context c) {
        block.byHost.clear(); block.byToken.clear(); block.catchAll.clear(); block.count = 0;
        allow.byHost.clear(); allow.byToken.clear(); allow.catchAll.clear(); allow.count = 0;
        globalSelectors.clear(); hostSelectors.clear(); negSelectors.clear();
        cosmeticExcGlobal.clear(); cosmeticExcHost.clear();
        elemhideHosts.clear(); generichideHosts.clear(); specifichideHosts.clear();
        procGlobal.clear(); procHost.clear();
        Prefs p = Prefs.get(c);

        // v1.3.80 — the user's own global custom rules (the "Custom rules"
        // editor in Filter Lists) were WRITTEN but never READ: the pref fed
        // nothing. They now ride the same parser (network + cosmetic).
        parse(p.s("customRules", ""), "Custom");

        {
            if (p.listEnabled("easylist"))
                parse(builtinList(c, p, "easylist"), "EasyList");
            if (p.listEnabled("easyprivacy"))
                parse(builtinList(c, p, "easyprivacy"), "EasyPrivacy");
            // custom downloaded lists
            try {
                org.json.JSONArray custom = p.customLists();
                for (int i = 0; i < custom.length(); i++) {
                    org.json.JSONObject o = custom.optJSONObject(i);
                    if (o == null || !o.optBoolean("enabled", true)) continue;
                    java.io.File f = new File(p.filtersDir(), o.optString("file"));
                    if (f.exists()) parse(Fx.readText(f), o.optString("name"));
                }
            } catch (Throwable ignored) {}
        }
        ruleCount = block.count + allow.count;
        ready = true;
    }

    /** v1.3.80 — the text for a built-in list: the DOWNLOADED update (a
     *  previous "Update all" in Filter Lists) if it passes the size guard,
     *  else the APK's bundled copy. Guard: a real list is megabytes; a CDN
     *  error page is kilobytes — anything under 100 KB is rejected and the
     *  bundled asset wins (an error page must never blank the adblock). */
    private static String builtinList(Context c, Prefs p, String key) {
        try {
            java.io.File upd = new File(p.filtersDir(), "builtin_" + key + ".txt");
            if (upd.exists() && upd.length() > 100_000L) return Fx.readText(upd);
        } catch (Throwable ignored) {}
        return Fx.readAsset(c, "filters/" + key + ".txt");
    }

    /** the URL a built-in list updates from (easylist.to is the canonical
     *  home of both lists; served from a CDN, ~0.1 s for 2 MB here). */
    public static String builtinUpdateUrl(String key) {
        return "https://easylist.to/easylist/" + key + ".txt";
    }

    /** where a built-in list update lands (filtersDir/builtin_<key>.txt). */
    public static File builtinUpdateFile(Prefs p, String key) {
        return new File(p.filtersDir(), "builtin_" + key + ".txt");
    }

    public int ruleCount() { return ruleCount; }

    // ------------------------------------------------------------------ parse

    private void parse(String text, String source) {
        if (text == null) return;
        for (String line : text.split("\n")) {
            try {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '!' || line.charAt(0) == '[') continue;
                // v1.3.80 — EXTENDED-CSS separators normalized BEFORE routing:
                // uBO's "domain#?#sel" (extended, procedural allowed) and the
                // extended exception "domain#@?#sel" became "domain##sel" /
                // "domain#@#sel" here. 291 live EasyList rules ride "#?#" —
                // and indexOf("##") inside "#?##card-list" (the selector
                // starts with #) handed parseCosmetic the domain part
                // "domain#?" — a host key that NEVER matches, so every one of
                // those rules was silently DEAD.
                int xq = line.indexOf("#@?#");
                if (xq >= 0) line = line.substring(0, xq) + "#@#" + line.substring(xq + 4);
                int qm = line.indexOf("#?#");
                if (qm >= 0) line = line.substring(0, qm) + "##" + line.substring(qm + 3);
                // v1.3.80 — cosmetic EXCEPTION lines ("domain#@#sel") first:
                // "#@#" does not contain "##", so before this check they fell
                // through to parseNetwork() as garbage. Also the old
                // startsWith("@@") guard never matched them (they start with
                // a domain) — the @@ prefix check was dead code for cosmetics.
                int exc = line.indexOf("#@#");
                if (exc >= 0) {
                    parseCosmeticException(line, exc);
                } else if (line.contains("##")) {
                    parseCosmetic(line);
                } else {
                    parseNetwork(line);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** v1.3.80 — "domain,domain#@#selector": never hide selector on those
     *  domains. Negated (~domain) exception lines are skipped — they mean
     *  "everywhere except that domain", which a global exception over-applies
     *  (rarer than rare; safe direction is to skip). */
    private void parseCosmeticException(String line, int idx) {
        String domPart = line.substring(0, idx);
        String selector = line.substring(idx + 3).trim();
        // quote-safe since v1.3.80: cssInject() embeds CSS in a SINGLE-quoted
        // JS string and escapes \, ' and \n itself — double quotes are valid
        // CSS (the old contains(\"\") check silently dropped ~10% of EasyList
        // cosmetics, 2,512 live selectors, all attribute selectors)
        if (selector.isEmpty() || selector.contains("\\") || selector.length() > 400) return;
        String[] doms = domPart.split(",");
        if (doms.length == 1 && doms[0].isEmpty()) {
            cosmeticExcGlobal.add(selector);
            return;
        }
        for (String d : doms) {
            d = d.trim().toLowerCase();
            if (d.isEmpty() || d.startsWith("~")) continue;
            cosmeticExcHost.computeIfAbsent(d, k -> new ArrayList<>()).add(selector);
        }
    }

    private void parseCosmetic(String line) {
        int idx = indexOfSeparatorHash(line);
        if (idx <= 0) return;
        String domPart = line.substring(0, idx);
        String selector = line.substring(idx + 2).trim();
        if (selector.isEmpty() || selector.length() > 400) return;
        // v1.3.80 — PROCEDURAL filters never enter the stylesheet:
        // :has-text(arg) becomes a bounded JS pass (see proceduralJs()); the
        // other procedural extensions (:style(, :remove(, :matches-attr(, …)
        // are unsupported here and a group rule with one invalid selector is
        // dropped by CSS wholesale — so any of those markers skips the line
        // instead of shipping a rule that silently kills its group.
        int ht = selector.indexOf(":has-text(");
        if (ht >= 0) {
            String base = selector.substring(0, ht).trim();
            // v1.3.80 — PAREN-MATCHED arg: args nest ("(Ad|Anúncio)") and
            // rules chain (:has-text(...):has(...)); lastIndexOf(')')
            // overshot and the validation ate the rule. Scanner below finds
            // the paren that actually closes the :has-text( group.
            int open = ht + 9;                    // position of '('
            int close = matchingParen(selector, open);
            if (close < 0) return;
            String arg = selector.substring(open + 1, close);
            // uBO allows /regex/ or literal; the literal approximation keeps
            // this tiny — strip the slashes, substring match, case-insensitive
            if (arg.startsWith("/") && arg.endsWith("/") && arg.length() > 1)
                arg = arg.substring(1, arg.length() - 1);
            if (base.isEmpty() || arg.isEmpty() || arg.length() > 200
                    || base.contains("\\") || base.contains("\"") || base.contains("'")
                    || arg.contains("\"") || arg.contains("'") || arg.contains("(")) return;
            String[] pair = { base, arg };
            String[] doms = domPart.split(",");
            if (doms.length == 1 && doms[0].isEmpty()) {
                procGlobal.add(pair);
                return;
            }
            for (String d : doms) {
                d = d.trim().toLowerCase();
                if (d.isEmpty() || d.startsWith("~")) continue;
                procHost.computeIfAbsent(d, k -> new ArrayList<>()).add(pair);
            }
            return;
        }
        if (PROC_UNSUPPORTED.matcher(selector).find()) return;
        // quote-safe since v1.3.80 (see parseCosmeticException): double quotes
        // are valid CSS and cssInject() escapes the rest — only a raw
        // backslash is unrecoverable
        if (selector.contains("\\")) return;
        String[] doms = domPart.split(",");
        if (doms.length == 1 && doms[0].isEmpty()) {
            globalSelectors.add(selector);
            return;
        }
        for (String d : doms) {
            d = d.trim().toLowerCase();
            if (d.isEmpty()) continue;
            if (d.startsWith("~")) {
                negSelectors.computeIfAbsent(d.substring(1), k -> new ArrayList<>()).add(selector);
            } else {
                hostSelectors.computeIfAbsent(d, k -> new ArrayList<>()).add(selector);
            }
        }
    }

    /** v1.3.80 — procedural extensions this build does not run. Any hit
     *  skips the rule (it would be invalid CSS and kill its whole group).
     *  -abp-properties/-abp-has are ABP's legacy equivalents. */
    private static final Pattern PROC_UNSUPPORTED = Pattern.compile(
            ":(?:style|remove|remove-attr|remove-class|matches-attr|matches-prop|"
            + "matches-css|matches-path|min-text-length|others|upward|watch-attr|"
            + "subject|xpath|path|shadow|inject-script|-abp-properties|-abp-has)\\(");

    /** v1.3.80 — index of the ')' closing the '(' at open (depth-aware,
     *  backslash-escape aware), or -1. */
    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') { i++; continue; }
            if (ch == '(') depth++;
            else if (ch == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** find "##" but ignore "###sel" start (id selector) - actually domain##sel: the ## is followed by selector starting with # often. Use first "##" occurrence. */
    private int indexOfSeparatorHash(String line) {
        // "domain,domain##sel" - find first "##"
        return line.indexOf("##");
    }

    private void parseNetwork(String line) {
        boolean isAllow = line.startsWith("@@");
        if (isAllow) line = line.substring(2);

        // options
        EnumSet<Type> types = EnumSet.noneOf(Type.class);
        Boolean thirdParty = null;
        List<String> domAllow = null, domDeny = null;
        boolean skipRule = false;
        int dollar = line.indexOf('$');
        String pattern = line;
        if (dollar > 0 && dollar < line.length() - 1) {
            pattern = line.substring(0, dollar);
            String opts = line.substring(dollar + 1).toLowerCase();
            for (String o : opts.split(",")) {
                o = o.trim();
                if (o.isEmpty()) continue;
                switch (o) {
                    case "script": case "worker": case "service-worker": case "websocket":
                        types.add(Type.SCRIPT); break;
                    case "image": types.add(Type.IMAGE); break;
                    case "stylesheet": types.add(Type.STYLESHEET); break;
                    case "xmlhttprequest": case "fetch": types.add(Type.XHR); break;
                    case "subdocument": case "object-subrequest":
                        types.add(Type.SUBDOC); break;
                    case "font": types.add(Type.FONT); break;
                    case "media": types.add(Type.MEDIA); break;
                    case "ping": case "beacon": types.add(Type.XHR); break;
                    case "other": case "object": types.add(Type.OTHER); break;
                    case "document": types.add(Type.DOCUMENT); break;
                    case "third-party": thirdParty = true; break;
                    case "first-party": thirdParty = false; break;
                    case "popup": case "match-case":
                        skipRule = true; break; // handled elsewhere / not applicable
                    // v1.3.80 — the cosmetic-opt-out options. These ride
                    // ALLOW rules ("@@||site^$generichide"): the site asked
                    // for no (generic) cosmetic filtering. Before v1.3.80 they
                    // were skipRule'd wholesale — the ask was ignored and
                    // generic selectors applied anyway (breakage on sites
                    // that opt out). The host is captured now (168 live rules
                    // in EasyList) and cssFor() honors it; the rule still
                    // never blocks network traffic (skipRule stays true).
                    case "elemhide":
                        if (isAllow) { String h = hostKeyOf(pattern);
                            if (h != null) elemhideHosts.add(h); }
                        skipRule = true; break;
                    case "generichide": case "ghide":
                        if (isAllow) { String h = hostKeyOf(pattern);
                            if (h != null) generichideHosts.add(h); }
                        skipRule = true; break;
                    case "specifichide": case "shide":
                        if (isAllow) { String h = hostKeyOf(pattern);
                            if (h != null) specifichideHosts.add(h); }
                        skipRule = true; break;
                    default:
                        if (o.startsWith("domain=")) {
                            domAllow = new ArrayList<>(); domDeny = new ArrayList<>();
                            for (String d : o.substring(7).split("\\|")) {
                                if (d.startsWith("~")) domDeny.add(d.substring(1));
                                else if (!d.isEmpty()) domAllow.add(d);
                            }
                        }
                        // unknown option: ignore, keep rule
                }
            }
        }
        if (skipRule || pattern.isEmpty()) return;
        if (pattern.length() > 300) return;

        NetRule r = new NetRule();
        r.types = types.isEmpty() ? null : types;
        r.thirdParty = thirdParty;
        if (domAllow != null) r.domAllow = domAllow.toArray(new String[0]);
        if (domDeny != null) r.domDeny = domDeny.toArray(new String[0]);

        String hostKey = null;
        String p = pattern;
        boolean anchoredStart = false, anchoredEnd = false;
        if (p.startsWith("||")) {
            p = p.substring(2);
            // domain token up to separator
            int i = 0;
            StringBuilder hb = new StringBuilder();
            while (i < p.length() && isHostChar(p.charAt(i))) { hb.append(p.charAt(i)); i++; }
            if (hb.length() >= 2) hostKey = hb.toString().toLowerCase();
            anchoredStart = true; // host anchor
        } else if (p.startsWith("|")) {
            p = p.substring(1);
            anchoredStart = true;
        }
        if (p.endsWith("|")) {
            p = p.substring(0, p.length() - 1);
            anchoredEnd = true;
        }

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
        try {
            r.regex = Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE);
        } catch (Throwable e) {
            return;
        }
        r.hostKey = hostKey;

        Buckets b = isAllow ? allow : block;
        b.count++;
        if (hostKey != null) {
            b.byHost.computeIfAbsent(hostKey, k -> new ArrayList<>()).add(r);
        } else {
            String token = firstLiteralToken(p);
            if (token != null) {
                b.byToken.computeIfAbsent(token, k -> new ArrayList<>()).add(r);
            } else {
                b.catchAll.add(r);
            }
        }
    }

    private static boolean isHostChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '-';
    }

    /** v1.3.80 — the host of a "||host^" pattern (for $elemhide-family
     *  exception hosts), or null when the pattern is not host-anchored. */
    private static String hostKeyOf(String pattern) {
        if (pattern == null || !pattern.startsWith("||")) return null;
        StringBuilder hb = new StringBuilder();
        int i = 2;
        while (i < pattern.length() && isHostChar(pattern.charAt(i))) {
            hb.append(pattern.charAt(i)); i++;
        }
        return hb.length() >= 2 ? hb.toString().toLowerCase() : null;
    }

    /** first literal run >= 2 chars usable as lookup token */
    private static String firstLiteralToken(String p) {
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

    // ------------------------------------------------------------------ match

    /** convert android resource type string */
    public static Type typeOf(String rt) {
        if (rt == null) return Type.OTHER;
        switch (rt) {
            case "MAIN_FRAME": return Type.DOCUMENT;
            case "SUB_FRAME": case "IFRAME": return Type.SUBDOC;
            case "STYLESHEET": return Type.STYLESHEET;
            case "SCRIPT": return Type.SCRIPT;
            case "IMAGE": case "FAVICON": return Type.IMAGE;
            case "XMLHTTPREQUEST": return Type.XHR;
            case "FONT": return Type.FONT;
            case "MEDIA": case "AUDIO": case "VIDEO": return Type.MEDIA;
            case "SERVICE_WORKER": case "WORKER": case "SHARED_WORKER": return Type.SCRIPT;
            default: return Type.OTHER;
        }
    }

    /** Main entry: should this request be blocked? */
    public boolean blocked(String url, String pageHost, Type type, List<String> extraSiteRules) {
        if (!ready || url == null) return false;
        String u = url.toLowerCase();
        if (!u.startsWith("http:") && !u.startsWith("https:")) return false;
        if (type == Type.DOCUMENT) return false; // never block navigations here

        // per-site custom rules take priority
        if (extraSiteRules != null) {
            for (String rule : extraSiteRules) {
                if (rule == null || rule.isEmpty()) continue;
                boolean isAllow = rule.startsWith("@@");
                String pat = isAllow ? rule.substring(2) : rule;
                if (matchSimple(pat, u)) return !isAllow;
            }
        }

        String reqHost = Fx.hostOf(u);
        boolean third = pageHost != null && !pageHost.isEmpty()
                && !Fx.baseDomain(reqHost).equals(Fx.baseDomain(pageHost));

        if (matchBucket(allow, u, reqHost, pageHost, type, third, null)) return false;
        return matchBucket(block, u, reqHost, pageHost, type, third, null);
    }

    /** simple substring/wildcard match for user rules */
    private boolean matchSimple(String pat, String url) {
        if (pat.startsWith("||")) {
            String d = pat.substring(2);
            int cut = 0;
            while (cut < d.length() && isHostChar(d.charAt(cut))) cut++;
            String host = d.substring(0, cut);
            String rest = d.substring(cut).replace("^", "/");
            String h = Fx.hostOf(url);
            return (h.equals(host) || h.endsWith("." + host))
                    && (rest.isEmpty() || url.contains(host + rest));
        }
        String p = pat;
        boolean endA = p.endsWith("|");
        if (endA) p = p.substring(0, p.length() - 1);
        boolean startA = p.startsWith("|");
        if (startA) p = p.substring(1);
        p = p.replace("^", "/");
        if (startA && endA) return url.equals(p);
        if (startA) return url.startsWith(p);
        if (endA) return url.endsWith(p);
        return url.contains(p);
    }

    private boolean matchBucket(Buckets b, String url, String reqHost, String pageHost,
                                Type type, boolean third, Object unused) {
        // 1. host-anchored rules
        if (b.byHost.size() > 0 && !reqHost.isEmpty()) {
            String h = reqHost;
            while (h != null && !h.isEmpty()) {
                List<NetRule> rs = b.byHost.get(h);
                if (rs != null) {
                    for (NetRule r : rs) {
                        if (ruleMatch(r, url, pageHost, type, third)) return true;
                    }
                }
                int dot = h.indexOf('.');
                h = dot > 0 ? h.substring(dot + 1) : null;
            }
        }
        // 2. token-indexed generic rules
        if (b.byToken.size() > 0) {
            Set<String> seen = new HashSet<>();
            int ul = url.length();
            for (int i = 0; i < ul - 1; i++) {
                for (int len = 2; len <= 12 && i + len <= ul; len++) {
                    String tok = url.substring(i, i + len);
                    if (seen.contains(tok)) continue;
                    List<NetRule> rs = b.byToken.get(tok);
                    if (rs != null) {
                        seen.add(tok);
                        for (NetRule r : rs) {
                            if (ruleMatch(r, url, pageHost, type, third)) return true;
                        }
                    }
                }
                if (!seen.isEmpty() && i > 200) break; // safety: don't scan huge URLs forever
            }
        }
        // 3. catch-all
        for (NetRule r : b.catchAll) {
            if (ruleMatch(r, url, pageHost, type, third)) return true;
        }
        return false;
    }

    private boolean ruleMatch(NetRule r, String url, String pageHost, Type type, boolean third) {
        if (r.types != null) {
            if (type == Type.DOCUMENT) {
                if (!r.types.contains(Type.DOCUMENT)) return false;
            } else if (type != Type.OTHER && !r.types.contains(type)) return false;
        }
        if (r.thirdParty != null && r.thirdParty != third) return false;
        if (r.domAllow != null || r.domDeny != null) {
            if (pageHost == null || pageHost.isEmpty()) return false;
            boolean ok = r.domAllow == null || r.domAllow.length == 0;
            if (r.domAllow != null) {
                for (String d : r.domAllow) {
                    if (pageHost.equals(d) || pageHost.endsWith("." + d)) { ok = true; break; }
                }
            }
            if (ok && r.domDeny != null) {
                for (String d : r.domDeny) {
                    if (pageHost.equals(d) || pageHost.endsWith("." + d)) return false;
                }
            }
            if (!ok) return false;
        }
        Matcher m = r.regex.matcher(url);
        return m.find();
    }

    // ------------------------------------------------------------------ cosmetic

    /** CSS to inject for the given page host. listsOn: whether LIST-derived
     *  selectors run (the adblock on/off decision — see BrowserActivity
     *  adblockListsOn; the user's own site rules + zaps always run). */
    public String cssFor(String host, List<String> siteFilters, List<String> zaps,
                         boolean listsOn) {
        if (!ready) return "";
        // v1.3.80 — the list-side cosmetic opt-outs, honored (see the $elemhide
        // family note in parseNetwork). The USER's own rules (siteFilters +
        // zaps) always survive: a site opting out of LIST cosmetic filtering
        // never overrides the user's explicit per-site rules.
        boolean elemhideAll = listsOn && host != null && hostInSet(elemhideHosts, host);
        boolean noGeneric = elemhideAll || (listsOn && host != null && hostInSet(generichideHosts, host));
        boolean noSpecific = elemhideAll || (listsOn && host != null && hostInSet(specifichideHosts, host));
        StringBuilder sb = new StringBuilder();
        Set<String> all = new HashSet<>();
        if (!noSpecific && listsOn && host != null && !host.isEmpty()) {
            collect(hostSelectors, host, all);
            Set<String> negs = new HashSet<>();
            collect(negSelectors, host, negs);
            all.removeAll(negs);
        }
        if (!noGeneric && listsOn) all.addAll(globalSelectors);
        // v1.3.80 — cosmetic exceptions (#@#) retire list-derived selectors
        // for this host; global exceptions retire them everywhere. Applied
        // AFTER the negSelectors pass so both carve the same set.
        if (!all.isEmpty()) {
            Set<String> exc = new HashSet<>(cosmeticExcGlobal);
            if (host != null && !host.isEmpty()) collect(cosmeticExcHost, host, exc);
            all.removeAll(exc);
        }
        // per-site custom cosmetic rules (## selector) from site filters —
        // quote-safe since v1.3.80 (double quotes are valid CSS; cssInject
        // escapes the string)
        if (siteFilters != null) {
            for (String f : siteFilters) {
                if (f != null && f.contains("##")) {
                    String sel = f.substring(f.indexOf("##") + 2).trim();
                    if (!sel.isEmpty() && !sel.contains("\\")) all.add(sel);
                }
            }
        }
        if (zaps != null) for (String z : zaps) if (z != null && !z.isEmpty()) all.add(z);
        for (String s : all) {
            sb.append(s).append("{display:none!important;visibility:hidden!important;}\n");
        }
        return sb.toString();
    }

    /** v1.3.80 — does this host have ANY cosmetic filtering to run (CSS or
     *  procedural)? Saves the caller from injecting empty payloads. */
    public boolean anyCosmetic(String host) {
        return !cssFor(host, null, null, true).isEmpty()
                || !proceduralJs(host, null, true).isEmpty();
    }

    /** v1.3.80 — the bounded JS pass for PROCEDURAL :has-text() rules
     *  ("##sel:has-text(Sponsored)"): querySelectorAll(sel) per rule, hide
     *  (class bolt-ph) every element whose text contains the literal needle.
     *  Bounds: 80 rules, 3000 elements and 50 ms per pass, a 400 ms-debounced
     *  MutationObserver for 60 s. Empty string when nothing applies.
     *  listsOn: LIST-derived rules only when the adblock is on; the user's
     *  own site rules always run. */
    public String proceduralJs(String host, List<String> siteFilters, boolean listsOn) {
        if (!ready) return "";
        List<String[]> rules = new ArrayList<>();
        if (listsOn && host != null && !host.isEmpty()) collectPairs(procHost, host, rules);
        // user site rules first (explicit intent), then globals
        if (siteFilters != null) {
            for (String f : siteFilters) {
                String sel = f == null ? null : (f.contains("##")
                        ? f.substring(f.indexOf("##") + 2).trim() : null);
                if (sel == null) continue;
                int ht = sel.indexOf(":has-text(");
                if (ht < 0) continue;
                String base = sel.substring(0, ht).trim();
                int open = ht + 9;
                int close = matchingParen(sel, open);
                if (close < 0) continue;
                String arg = sel.substring(open + 1, close);
                if (arg.startsWith("/") && arg.endsWith("/") && arg.length() > 1)
                    arg = arg.substring(1, arg.length() - 1);
                if (base.isEmpty() || arg.isEmpty() || arg.length() > 200
                        || base.contains("\\") || base.contains("\"") || base.contains("'")
                        || arg.contains("\"") || arg.contains("'") || arg.contains("(")) continue;
                rules.add(new String[]{ base, arg });
            }
        }
        if (listsOn) rules.addAll(procGlobal);
        if (rules.isEmpty()) return "";
        if (rules.size() > 80) rules = new ArrayList<>(rules.subList(0, 80));
        StringBuilder arr = new StringBuilder("[");
        for (String[] r : rules) {
            arr.append("['").append(jsq(r[0])).append("','").append(jsq(r[1])).append("'],");
        }
        arr.setLength(arr.length() - 1);
        arr.append("]");
        return PROC_JS_TEMPLATE.replace("__RULES__", arr.toString());
    }

    /** JS-string-escape a selector/needle for the single-quoted array. */
    private static String jsq(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
    }

    private static void collectPairs(Map<String, List<String[]>> map, String host, List<String[]> out) {
        String h = host;
        while (h != null && !h.isEmpty()) {
            List<String[]> l = map.get(h);
            if (l != null) out.addAll(l);
            int dot = h.indexOf('.');
            h = dot > 0 ? h.substring(dot + 1) : null;
        }
    }

    /** does host (or any parent domain) appear in the set? */
    private static boolean hostInSet(Set<String> set, String host) {
        String h = host;
        while (h != null && !h.isEmpty()) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            h = dot > 0 ? h.substring(dot + 1) : null;
        }
        return false;
    }

    /** v1.3.80 — the procedural pass template. __RULES__ becomes
     *  [['base','needle'],…]. The __boltProc guard makes re-injection at
     *  onPageCommitVisible + onPageFinished a no-op (first wins). */
    private static final String PROC_JS_TEMPLATE = """
(function(){try{
if(window.__boltProc)return;window.__boltProc=1;
var R=__RULES__;
var S=0;
function style(){if(S)return;S=1;var s=document.createElement('style');s.textContent='.bolt-ph{display:none!important;visibility:hidden!important;}';(document.head||document.documentElement).appendChild(s);}
function run(){
 var t0=Date.now();
 try{style();}catch(e){}
 var n=0;
 try{
  for(var i=0;i<R.length&&n<3000&&Date.now()-t0<50;i++){
   var els;try{els=document.querySelectorAll(R[i][0]);}catch(e){continue;}
   var nd=R[i][1].toLowerCase();
   for(var k=0;k<els.length&&n<3000;k++){
    var el=els[k];
    try{
     if(!el.classList.contains('bolt-ph')&&(el.textContent||'').toLowerCase().indexOf(nd)>=0){el.classList.add('bolt-ph');n++;}
    }catch(e){}
   }
  }
 }catch(e){}
}
run();
var tm=null;
try{
 var mo=new MutationObserver(function(){if(tm)clearTimeout(tm);tm=setTimeout(run,400);});
 mo.observe(document.documentElement,{childList:true,subtree:true});
 setTimeout(function(){try{mo.disconnect();}catch(e){}},60000);
}catch(e){}
}catch(e){}})();
""";

    private void collect(Map<String, List<String>> map, String host, Set<String> out) {
        String h = host;
        while (h != null && !h.isEmpty()) {
            List<String> l = map.get(h);
            if (l != null) out.addAll(l);
            int dot = h.indexOf('.');
            h = dot > 0 ? h.substring(dot + 1) : null;
        }
    }

    // ------------------------------------------------------------------ adult

    public boolean isAdultHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String base = Fx.baseDomain(host);
        return adultDomains.contains(base);
    }

    private void seedAdult() {
        String[] d = {
                "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
                "youporn.com", "tube8.com", "brazzers.com", "playboy.com", "penthouse.com",
                "onlyfans.com", "stripchat.com", "chaturbate.com", "livejasmin.com",
                "camsoda.com", "myfreecams.com", "bongacams.com", "cam4.com", "camster.com",
                "porn.com", "pornhd.com", "porntrex.com", "pornone.com", "porner300.com",
                "eporner.com", "spankbang.com", "txxx.com", "hbrowse.com", "hentaicity.com",
                "hanime.tv", "hentai2read.com", "nhentai.net", "hentaihaven.com",
                "adultsite.com", "adultswim.com", "4chan.org", "8chan.moe", "xchan.com",
                "motherless.com", "efukt.com", "dailymotion.com/adult", "imgur.com/r/nsfw",
                "reddit.com/r/nsfw", "tumblr.com/tagged/nsfw", "twitter.com/search?q=nsfw",
                "liveleak.com", "bestgore.com", "xyyp isolated", "blacked.com", "vixen.com",
                "tushy.com", "babes.com", "twistys.com", "digitalplayground.com",
                "realitykings.com", "bangbros.com", "naughtyamerica.com", "wicked.com",
                "deeper.com", "milf.com", "nudespuri.com", "nude-gals.com", "alsangels.com",
                "femjoy.com", "metart.com", "domai.com", "hegre.com", "watch4beauty.com",
                "eroticbeauty.com", "sexart.com", "the-life-erotic.com", "rylskyart.com",
                "watchersweb.com", "voyeurweb.com", "homeclips.com", "redclouds.com",
                "amateurgirls.com", "abbywinters.com", "girlsoutwest.com", "alscan.com",
                "ftvmembers.com", "ftvgirls.com", "nubiles.net", "littlecaprice.com",
                "pornhubpremium.com", "xtapes.to", "pornvibe.org", "porn00.org", "pornv.im",
                "pornleech.is", "sxyprn.com", "yespornplease.com", "pron.tv", "porndig.com",
                "yourporn.sexy", "cpmlink.net/adult", "1337x.to/adult", "thepiratebay.org/adult",
                "rarbg.to/adult", "torrentz2.eu/adult", "limetorrents.info/adult",
                "katcr.co/adult", "eztv.ag/adult", "yts.lt/adult", "openload.co/adult",
                "mp4mania.adult", "klwap.adult", "mallumv.adult", "tamilgun.adult",
                "tamilrockers.adult", "isaidub.adult", "moviesda.adult", "kuttymovies.adult",
                "jiorockers.adult", "todaypk.adult", "movierulz.adult", "filmywap.adult",
                "9xmovies.adult", "worldfree4u.adult", "desirockers.adult", "apnetv.adult",
                "watchseries.adult", "putlocker.adult", "gomovies.adult", "123movies.adult",
                "fmovies.adult", "sflix.adult", "hurawatch.adult", "soap2day.adult",
                "myflixer.adult", "vumoo.adult", "lookmovie.adult", "primewire.adult"
        };
        for (String x : d) if (!x.contains("/") && !x.contains(" ")) adultDomains.add(x);
    }
}
