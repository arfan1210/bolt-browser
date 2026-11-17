package com.bolt.browser.scripts;

import android.content.Context;

import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full Tampermonkey/Greasemonkey-compatible userscript engine.
 *
 * Supports: ==UserScript== metadata (@name @namespace @version @description
 * @author @match @include @exclude @run-at @grant @require @resource @icon
 * @noframes @connect @homepageURL @updateURL @downloadURL @license @antifeature),
 * proper @match scheme/host/path semantics, @include/@exclude glob-regex,
 * per-script GM storage (GM_get/setValue etc., Java-side persisted),
 * GM_xmlhttpRequest with full event set + binary responses, GM_addStyle,
 * GM_download, GM_openInTab, GM_setClipboard, GM_notification, GM_info,
 * GM_registerMenuCommand (surfaced in the browser main menu), GM_getResourceText/URL,
 * unsafeWindow, @require bundling and @resource caching (fetched at install time).
 */
public class UserscriptEngine {

    // ---------------------------------------------------------------- model

    public static class Script {
        public String name = "Unnamed";
        public String namespace = "";
        public String version = "";
        public String description = "";
        public String author = "";
        public String uuid;             // stable identity for GM storage
        public String file;             // file name inside scriptsDir
        public String code = "";
        public List<String> matches = new ArrayList<>();   // @match patterns
        public List<String> includes = new ArrayList<>();  // @include globs
        public List<String> excludes = new ArrayList<>();  // @exclude (match or include style)
        public String runAt = "document-idle";             // document-start|document-end|document-idle
        public List<String> grants = new ArrayList<>();    // @grant list (informational)
        public List<String> requires = new ArrayList<>();  // @require urls
        public boolean noframes = false;
        public boolean enabled = true;
        /** @resource name -> {url, text, b64} loaded lazily from the res dir */
        public Map<String, String[]> resources = new HashMap<>();
    }

    private static final AtomicLong xhrIds = new AtomicLong(1);

    public interface XhrCallback { void deliver(String js); }

    // v1.4.9 — the static liveWebs WebView registry is GONE. It was written
    // on every JsBridge.attach() (BrowserActivity) but read by NOTHING —
    // engine -> page delivery always went through the bridge's own
    // `attached` field — so the map only held strong references to every
    // WebView ever attached, pinning each leaked tab's Activity context
    // against garbage collection. Dead code: removed outright, not
    // weakened (a WeakReference to something nobody reads is still dead).

    // ---------------------------------------------------------------- parsing

    /** Parse the ==UserScript== header + body. */
    public static Script parse(String code, String fileName) {
        Script s = new Script();
        s.code = code == null ? "" : code;
        s.file = fileName == null ? "script.user.js" : fileName;
        s.name = stripExt(s.file);
        try {
            int a = s.code.indexOf("==UserScript==");
            int b = s.code.indexOf("==/UserScript==");
            if (a >= 0 && b > a) {
                String header = s.code.substring(a, b);
                for (String rawLine : header.split("\n")) {
                    String line = rawLine.trim();
                    if (line.startsWith("//")) line = line.substring(2).trim();
                    if (!line.startsWith("@")) continue;
                    String key = line.substring(1).trim();
                    int sp = key.indexOf(' ');
                    String val = sp > 0 ? key.substring(sp + 1).trim() : "";
                    key = sp > 0 ? key.substring(0, sp) : key;
                    switch (key) {
                        case "name": if (!val.isEmpty()) s.name = val; break;
                        case "namespace": s.namespace = val; break;
                        case "version": s.version = val; break;
                        case "description": s.description = val; break;
                        case "author": s.author = val; break;
                        case "match": if (!val.isEmpty()) s.matches.add(val); break;
                        case "include": if (!val.isEmpty()) s.includes.add(val); break;
                        case "exclude": if (!val.isEmpty()) s.excludes.add(val); break;
                        case "exclude-match": if (!val.isEmpty()) s.excludes.add(val); break;
                        case "run-at":
                            if (!val.isEmpty()) s.runAt = val.toLowerCase();
                            break;
                        case "grant":
                            if (!val.isEmpty() && !s.grants.contains(val)) s.grants.add(val);
                            break;
                        case "require":
                            if (!val.isEmpty()) s.requires.add(val);
                            break;
                        case "noframes": s.noframes = true; break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (s.runAt.isEmpty()) s.runAt = "document-idle";
        if (s.runAt.startsWith("document-start")) s.runAt = "document-start";
        else if (s.runAt.startsWith("document-end")) s.runAt = "document-end";
        else if (s.runAt.startsWith("document-body")) s.runAt = "document-end";
        else if (s.runAt.startsWith("document-idle") || s.runAt.equals("context-menu")) s.runAt = "document-idle";
        else s.runAt = "document-idle";
        s.uuid = uuidOf(s.name, s.namespace);
        return s;
    }

    private static String stripExt(String f) {
        if (f == null) return "Unnamed";
        int d = f.lastIndexOf('.');
        return d > 0 ? f.substring(0, d) : f;
    }

    /** stable per-script identity (same as TM's script id concept) */
    public static String uuidOf(String name, String namespace) {
        String src = (name == null ? "" : name) + "/" + (namespace == null ? "" : namespace);
        return UUID.nameUUIDFromBytes(src.getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ---------------------------------------------------------------- matching

    /** Tampermonkey @match semantics: scheme://host/path with * wildcards. */
    static boolean matchRule(String pattern, String url) {
        if (pattern == null || pattern.isEmpty()) return false;
        // bare host or path-only patterns are applied leniently
        if (pattern.equals("*")) return true;
        try {
            String p = pattern;
            String pScheme = "", pHost = "", pPath = "";
            int sep = p.indexOf("://");
            if (sep >= 0) {
                pScheme = p.substring(0, sep);
                p = p.substring(sep + 3);
            } else if (p.startsWith("//")) {
                p = p.substring(2);
            } else if (p.startsWith("/")) {
                pPath = p;                       // path-only rule
            } else {
                // "example.com/path" (no scheme) -> treat host+path
                int slash = p.indexOf('/');
                if (slash >= 0) { pHost = p.substring(0, slash); pPath = p.substring(slash); }
                else { pHost = p; pPath = "/"; }
            }
            if (pScheme.isEmpty() && pHost.isEmpty() && !pPath.isEmpty()) {
                // path-only: any host, compare path
                return globToRegex(pPath).matcher(pathOf(url)).matches();
            }
            if (pHost.isEmpty() && !pScheme.isEmpty()) {
                int slash = p.indexOf('/');
                if (slash >= 0) { pHost = p.substring(0, slash); pPath = p.substring(slash); }
                else { pHost = p; pPath = "/"; }
            }
            java.net.URL u;
            try {
                u = new java.net.URL(url);
            } catch (Throwable e) {
                return false;
            }
            String us = u.getProtocol();
            String uh = u.getHost() == null ? "" : u.getHost().toLowerCase();
            String up = (u.getPath() == null || u.getPath().isEmpty()) ? "/" : u.getPath();
            if (u.getQuery() != null) up += "?" + u.getQuery();
            // scheme
            if (!pScheme.equals("*") && !pScheme.equalsIgnoreCase(us)) {
                // http/https cross-allow like TM
                boolean httpish = (pScheme.equalsIgnoreCase("http") || pScheme.equalsIgnoreCase("https"))
                        && (us.equalsIgnoreCase("http") || us.equalsIgnoreCase("https"));
                if (!httpish) return false;
            }
            // host
            if (!hostMatches(pHost.toLowerCase(), uh)) return false;
            // path
            return globToRegex(pPath.isEmpty() ? "/" : pPath).matcher(up).matches();
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean hostMatches(String patternHost, String host) {
        if (patternHost.equals("*")) return true;
        if (patternHost.startsWith("*.")) {
            String base = patternHost.substring(2);
            return host.equals(base) || host.endsWith("." + base);
        }
        if (patternHost.contains("*")) {
            return globToRegex(patternHost).matcher(host).matches();
        }
        return host.equals(patternHost);
    }

    private static String pathOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String p = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
            if (u.getQuery() != null) p += "?" + u.getQuery();
            return p;
        } catch (Throwable e) {
            return "/";
        }
    }

    /** Greasemonkey @include glob -> regex (whole-match). */
    static Pattern globToRegex(String glob) {
        if (glob == null) glob = "*";
        StringBuilder rx = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*': rx.append(".*"); break;
                case '.': case '\\': case '(': case ')': case '[': case ']':
                case '{': case '}': case '^': case '$': case '|': case '?': case '+':
                    rx.append('\\').append(c); break;
                default: rx.append(c);
            }
        }
        try {
            return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE);
        } catch (Throwable e) {
            return Pattern.compile(".*");
        }
    }

    /** @include can also be a /regex/ literal */
    static boolean includeRule(String pattern, String url) {
        if (pattern == null || pattern.isEmpty()) return false;
        if (pattern.startsWith("/") && pattern.length() > 2 && pattern.endsWith("/")) {
            try {
                return Pattern.compile(pattern.substring(1, pattern.length() - 1))
                        .matcher(url).find();
            } catch (Throwable e) {
                return false;
            }
        }
        return globToRegex(pattern).matcher(url).matches();
    }

    public static boolean matches(Script s, String url) {
        return matches(s, url, false);
    }

    public static boolean matches(Script s, String url, boolean topLevelOnly) {
        if (!s.enabled || url == null || url.isEmpty()) return false;
        if (!url.startsWith("http") && !url.startsWith("file:")) return false;
        // noframes: only top-level documents (approximated: we always run in the
        // main frame of each WebView, so noframes scripts still run)
        for (String e : s.excludes) {
            if (e.startsWith("/") && e.length() > 2 && e.endsWith("/")) {
                try {
                    if (Pattern.compile(e.substring(1, e.length() - 1)).matcher(url).find())
                        return false;
                } catch (Throwable ignored) {}
            } else if (matchRule(e, url) || includeRule(e, url)) {
                return false;
            }
        }
        boolean ok = false;
        for (String m : s.matches) if (matchRule(m, url)) { ok = true; break; }
        if (!ok) for (String m : s.includes) if (includeRule(m, url)) { ok = true; break; }
        if (!ok && s.matches.isEmpty() && s.includes.isEmpty()) ok = true; // no rules = run everywhere
        return ok;
    }

    // ---------------------------------------------------------------- listing

    /** all scripts that should run on this url at the given phase ("start"|"end"|"idle") */
    public static List<Script> forUrl(Context c, String url) {
        return forUrl(c, url, null);
    }

    public static List<Script> forUrl(Context c, String url, String phase) {
        List<Script> out = new ArrayList<>();
        Prefs p = Prefs.get(c);
        if (!p.b("userscripts", true)) return out;
        String host = Fx.hostOf(url);
        if (host != null && !host.isEmpty()
                && p.siteBool(Fx.baseDomain(host), "noScripts", false)) return out;
        for (Script s : all(c)) {
            if (!matches(s, url)) continue;
            if (phase != null && !phaseFor(s).equals(phase)) continue;
            out.add(s);
        }
        return out;
    }

    static String phaseFor(Script s) {
        if ("document-start".equals(s.runAt)) return "start";
        if ("document-end".equals(s.runAt)) return "end";
        return "idle";
    }

    public static Map<String, Boolean> loadState(Context c) {
        Map<String, Boolean> m = new HashMap<>();
        try {
            JSONArray a = Fx.readJsonArray(new File(Prefs.get(c).filesDir(), "scriptState.json"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) m.put(o.optString("f"), o.optBoolean("on", true));
            }
        } catch (Throwable ignored) {}
        return m;
    }

    public static void saveState(Context c, Map<String, Boolean> m) {
        try {
            JSONArray a = new JSONArray();
            for (Map.Entry<String, Boolean> e : m.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("f", e.getKey());
                o.put("on", e.getValue());
                a.put(o);
            }
            Fx.writeJsonArray(new File(Prefs.get(c).filesDir(), "scriptState.json"), a);
        } catch (Throwable ignored) {}
    }

    /** v1.3.79 — THE PARSE CACHE. all() used to list + READ + PARSE every
     *  .user.js on every call, and the 3-dot menu calls it on the MAIN
     *  thread at open ("presses take a second to register" — a few hundred
     *  KB of userscripts is 100-500 ms). Scripts only change through
     *  UserscriptsActivity (file writes bump mtime) or the enable/disable
     *  toggle (scriptState.json mtime), so a directory-signature cache is
     *  exact: name+mtime of every file + the state file's mtime. Callers
     *  treat the list as read-only (forUrl/matches never mutate). */
    private static volatile List<Script> allCache;
    private static volatile String allSig = "\u0000";

    public static List<Script> all(Context c) {
        try {
            File dir = Prefs.get(c).scriptsDir();
            File[] files = dir.listFiles();
            StringBuilder sb = new StringBuilder();
            if (files != null) {
                java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                for (File f : files) {
                    if (!f.getName().endsWith(".user.js")) continue;
                    sb.append(f.getName()).append(':').append(f.lastModified()).append(';');
                }
            }
            File st = new File(Prefs.get(c).filesDir(), "scriptState.json");
            sb.append("state:").append(st.exists() ? st.lastModified() : 0L);
            String sig = sb.toString();
            List<Script> hit = allCache;
            if (hit != null && sig.equals(allSig)) return hit;
            List<Script> out = new ArrayList<>();
            Map<String, Boolean> state = loadState(c);
            if (files != null) {
                for (File f : files) {
                    if (!f.getName().endsWith(".user.js")) continue;
                    Script s = parse(Fx.readText(f), f.getName());
                    Boolean stt = state.get(f.getName());
                    s.enabled = stt == null || stt;
                    out.add(s);
                }
            }
            allCache = out;
            allSig = sig;
            return out;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** force the next all() to re-read (called after UserscriptsActivity
     *  writes scripts through paths that keep mtime granularity coarse). */
    public static void invalidateCache() { allSig = "\u0000"; }

    // ---------------------------------------------------------------- GM storage

    private static File gmStoreFile(Context c) {
        return new File(Prefs.get(c).filesDir(), "gmstore.json");
    }

    private static synchronized JSONObject gmStore(Context c) {
        return Fx.readJson(gmStoreFile(c));
    }

    private static synchronized void saveGmStore(Context c, JSONObject o) {
        Fx.writeJson(gmStoreFile(c), o);
    }

    /** whole per-script storage map as JSON (sync return to JS) */
    public static String gmValues(String uuid) {
        Context c = appCtx;
        if (c == null) return "{}";
        JSONObject store = gmStore(c);
        JSONObject mine = store.optJSONObject(uuid);
        return mine == null ? "{}" : myValuesOrNew(mine).toString();
    }

    private static JSONObject myValuesOrNew(JSONObject mine) {
        // migrate: values may be stored raw or under "v"
        JSONObject v = mine.optJSONObject("v");
        return v == null ? mine : v;
    }

    public static void gmSet(Context c, String uuid, String key, String valueJson) {
        synchronized (UserscriptEngine.class) {
            try {
                JSONObject store = gmStore(c);
                JSONObject mine = store.optJSONObject(uuid);
                if (mine == null) mine = new JSONObject();
                JSONObject vals = myValuesOrNew(mine);
                if ("__map__".equals(key)) {
                    // whole-map write from __gmPersist(): replace the value map
                    JSONObject newVals = new JSONObject(valueJson);
                    JSONObject wrap = new JSONObject();
                    wrap.put("v", newVals);
                    store.put(uuid, wrap);
                } else {
                    Object v;
                    try {
                        v = new org.json.JSONTokener(valueJson).nextValue();
                    } catch (Throwable notJson) {
                        v = valueJson; // store as plain string
                    }
                    vals.put(key, v);
                    JSONObject wrap = new JSONObject();
                    wrap.put("v", vals);
                    store.put(uuid, wrap);
                }
                saveGmStore(c, store);
            } catch (Throwable ignored) {}
        }
    }

    public static void gmDelete(Context c, String uuid, String key) {
        synchronized (UserscriptEngine.class) {
            try {
                JSONObject store = gmStore(c);
                JSONObject mine = store.optJSONObject(uuid);
                if (mine != null) {
                    JSONObject vals = myValuesOrNew(mine);
                    vals.remove(key);
                    JSONObject wrap = new JSONObject();
                    wrap.put("v", vals);
                    store.put(uuid, wrap);
                    saveGmStore(c, store);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** app context captured at first use (engine is process-wide) */
    private static Context appCtx;

    public static void init(Context c) {
        if (appCtx == null) appCtx = c.getApplicationContext();
    }

    /** gmSet as called from the JS bridge (app context captured at init). */
    public static void gmSet(String uuid, String key, String valueJson) {
        Context c = appCtx;
        if (c == null) return;
        gmSet(c, uuid, key, valueJson);
    }

    /** gmDelete as called from the JS bridge (app context captured at init). */
    public static void gmDelete(String uuid, String key) {
        Context c = appCtx;
        if (c == null) return;
        gmDelete(c, uuid, key);
    }

    // ---------------------------------------------------------------- resources / requires

    /** directory holding cached @require js + @resource payloads per script */
    static File depsDir(Context c) {
        File d = new File(Prefs.get(c).scriptsDir(), "deps");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String depFile(Context c, Script s, String tag) {
        return new File(depsDir(c), s.uuid + "." + tag).getAbsolutePath();
    }

    /** download all @require + @resource entries for a freshly installed script */
    public static void fetchDeps(Context c, Script s) {
        for (int i = 0; i < s.requires.size(); i++) {
            String u = s.requires.get(i);
            String body = httpGet(u);
            if (body != null) Fx.writeText(new File(depFile(c, s, "req" + i)), body);
        }
        Pattern resPat = Pattern.compile("//\\s*@resource\\s+(\\S+)\\s+(\\S+)");
        Matcher m = resPat.matcher(s.code);
        while (m.find()) {
            String name = m.group(1);
            String u = m.group(2);
            byte[] bin = httpGetBytes(u);
            if (bin != null) {
                Fx.writeText(new File(depFile(c, s, "res." + name)),
                        android.util.Base64.encodeToString(bin, android.util.Base64.NO_WRAP));
            }
        }
    }

    /** @resource text (raw if textual, base64 otherwise) — sync bridge call */
    public static String resourceText(String uuid, String name) {
        Context c = appCtx;
        if (c == null) return null;
        // find script by uuid
        for (Script s : all(c)) {
            if (s.uuid.equals(uuid)) {
                String b64 = Fx.readText(new File(depFile(c, s, "res." + name)));
                if (b64 == null) return null;
                // try decode as text only if it was stored with a text marker;
                // we keep it simple: resourceText returns the base64 unless a
                // text variant exists. Sites mostly use GM_getResourceURL.
                return b64;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- wrapping

    /** Build the injected wrapper: full GM_* shim + requires + user code. */
    public static String wrap(Script s) {
        Context c = appCtx;
        StringBuilder req = new StringBuilder();
        if (c != null) {
            for (int i = 0; i < s.requires.size(); i++) {
                String body = Fx.readText(new File(depFile(c, s, "req" + i)));
                if (body != null) {
                    req.append("\n/* @require #").append(i).append(" */\n").append(body).append("\n;");
                }
            }
        }
        String body = stripHeader(s.code);

        JSONObject meta = new JSONObject();
        try {
            JSONObject sj = new JSONObject();
            sj.put("name", s.name);
            sj.put("namespace", s.namespace);
            sj.put("version", s.version);
            sj.put("description", s.description);
            sj.put("author", s.author);
            sj.put("uuid", s.uuid);
            sj.put("runAt", s.runAt);
            meta.put("script", sj);
            meta.put("scriptHandler", "Bolt");
            meta.put("version", "1.3.7");
        } catch (Throwable ignored) {}

        JSONObject res = new JSONObject();
        if (c != null) {
            for (String name : resourceNamesOf(s)) {
                String b64 = Fx.readText(new File(depFile(c, s, "res." + name)));
                if (b64 != null) {
                    try { res.put(name, b64); } catch (Throwable ignored) {}
                }
            }
        }

        String storageInit = "{}";
        if (c != null) {
            JSONObject store = gmStore(c);
            JSONObject mine = store.optJSONObject(s.uuid);
            if (mine != null) storageInit = myValuesOrNew(mine).toString();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("(function(){\n");
        sb.append("'use strict';\n");
        // per-document injection guard: a userscript must run exactly ONCE per
        // document (Tampermonkey semantics). Injection happens per phase
        // (start/commit/end/idle) and WebView fires onPageFinished AGAIN for
        // SPA/pushState navigations — without the guard a SECOND instance of
        // the same script runs in the same document. For audio scripts that is
        // fatal: the second instance's createMediaElementSource() steals the
        // <video> audio route from the first (or throws), and the visible
        // floating panel stops applying changes until a real page reload.
        sb.append("try{var __BOLT_GUARD=");
        sb.append(JSONObject.quote(s.uuid));
        sb.append(";\n");
        sb.append("if(window.__BOLT_US&&window.__BOLT_US[__BOLT_GUARD])return;\n");
        sb.append("(window.__BOLT_US=window.__BOLT_US||{})[__BOLT_GUARD]=1;}catch(e){}\n");
        sb.append("var __BOLT_UUID=").append(JSONObject.quote(s.uuid)).append(";\n");
        sb.append("var __GM_STORAGE=").append(storageInit).append(";\n");
        sb.append("var __GM_RES=").append(res.toString()).append(";\n");
        sb.append("var __MENU_CMDS=[];\n");
        // ---- xhr plumbing ----
        sb.append("var __cbSeq=1,__cbs={};\n");
        sb.append("window.__boltXhrCallback=function(id,json){try{var f=__cbs[id];if(f){delete __cbs[id];"
                + "var r=(typeof json==='string')?JSON.parse(json):json;"
                + "if(r&&r.responseB64!==undefined){try{"
                + "var bin=atob(r.responseB64),n=bin.length,u8=new Uint8Array(n);"
                + "for(var i=0;i<n;i++)u8[i]=bin.charCodeAt(i);"
                + "r.response=(r.responseType==='blob')?new Blob([u8]):u8.buffer;}catch(x){}}"
                + "if(r&&r.error&&r.status===0){f('error',r);}else{f('load',r);}}}"
                + "catch(e){try{console.log('[GM xhr] '+e);}catch(x){}}};\n");
        // ---- storage write-behind ----
        sb.append("function __gmPersist(){try{Bolt.gmSet(__BOLT_UUID,'__map__',JSON.stringify(__GM_STORAGE));}catch(e){}}\n");
        // ---- GM4 namespace ----
        sb.append("var GM={\n");
        sb.append("info:").append(meta.toString()).append(",\n");
        sb.append("getValue:function(k,d){var v=__GM_STORAGE[k];return v===undefined?d:v;},\n");
        sb.append("setValue:function(k,v){__GM_STORAGE[k]=v;__gmPersist();return v;},\n");
        sb.append("deleteValue:function(k){var had=(k in __GM_STORAGE);delete __GM_STORAGE[k];__gmPersist();return had;},\n");
        sb.append("listValues:function(){return Object.keys(__GM_STORAGE);},\n");
        sb.append("log:function(){try{console.log.apply(console,arguments);}catch(e){}},\n");
        sb.append("addStyle:function(css){try{var st=document.createElement('style');st.textContent=String(css);"
                + "(document.head||document.documentElement).appendChild(st);return st;}catch(e){return null;}},\n");
        sb.append("getResourceText:function(n){var b=__GM_RES[n];if(b===undefined)return undefined;"
                + "try{return atob(b);}catch(e){return undefined;}},\n");
        sb.append("getResourceURL:function(n){var b=__GM_RES[n];if(b===undefined)return undefined;"
                + "return 'data:;base64,'+b;},\n");
        sb.append("notification:function(t,o){try{Bolt.notify(typeof t==='string'?t:(t&&t.text)||'');}catch(e){}},\n");
        sb.append("setClipboard:function(t){try{Bolt.copy(String(t));}catch(e){}},\n");
        sb.append("openInTab:function(u){try{Bolt.openTab(String(u));}catch(e){}},\n");
        sb.append("download:function(o){try{var j=(typeof o==='string')?{url:o}:o;"
                + "Bolt.gmDownload(JSON.stringify(j));}catch(e){}},\n");
        sb.append("registerMenuCommand:function(n,f){try{__MENU_CMDS.push(f);"
                + "Bolt.gmMenuCmd(String(n));}catch(e){}},\n");
        sb.append("xmlHttpRequest:function(o){return __gmXhr(o);}\n");
        sb.append("};\n");
        // ---- legacy global aliases ----
        sb.append("function GM_getValue(k,d){return GM.getValue(k,d);}\n");
        sb.append("function GM_setValue(k,v){return GM.setValue(k,v);}\n");
        sb.append("function GM_deleteValue(k){return GM.deleteValue(k);}\n");
        sb.append("function GM_listValues(){return GM.listValues();}\n");
        sb.append("function GM_addStyle(css){return GM.addStyle(css);}\n");
        sb.append("function GM_getResourceText(n){return GM.getResourceText(n);}\n");
        sb.append("function GM_getResourceURL(n){return GM.getResourceURL(n);}\n");
        sb.append("function GM_getValueDefault(k,d){return GM.getValue(k,d);}\n");
        sb.append("var GM_info=GM.info;\n");
        sb.append("function GM_xmlhttpRequest(o){return __gmXhr(o);}\n");
        sb.append("function GM_registerMenuCommand(n,f){GM.registerMenuCommand(n,f);}\n");
        sb.append("function GM_notification(t,o){GM.notification(t,o);}\n");
        sb.append("function GM_setClipboard(t,m){GM.setClipboard(t);}\n");
        sb.append("function GM_openInTab(u,l){GM.openInTab(u);}\n");
        sb.append("function GM_download(o,c){GM.download((typeof o==='string')?{url:o,name:c}:o);}\n");
        sb.append("function GM_log(){GM.log.apply(GM,arguments);}\n");
        sb.append("var unsafeWindow=window;\n");
        // ---- GM_xmlhttpRequest implementation ----
        sb.append("function __gmXhr(o){try{\n");
        sb.append("if(!o||!o.url)throw new Error('no url');\n");
        sb.append("var id=String(__cbSeq++);\n");
        sb.append("var ev=function(type,extra){try{var r={responseText:'',response:'',status:0,"
                + "statusText:'',readyState:4,responseHeaders:'',finalUrl:o.url};"
                + "for(var k in extra)r[k]=extra[k];"
                + "var fn=o['on'+type]||o['onreadystatechange'];"
                + "if(type==='load'&&o.onload)fn=o.onload;"
                + "if(fn)try{fn(r);}catch(e){try{console.log('[GM xhr handler] '+e);}catch(x){}}}catch(e){}};\n");
        sb.append("__cbs[id]=ev;\n");
        sb.append("var j={id:id,url:o.url,method:o.method||'GET',headers:o.headers||{},"
                + "data:(o.data==null?null:String(o.data)),timeout:o.timeout||0,"
                + "responseType:o.responseType||'',user:o.user||null,password:o.password||null};\n");
        sb.append("Bolt.gmXhr(JSON.stringify(j));\n");
        sb.append("return {abort:function(){try{Bolt.gmXhrAbort(id);}catch(e){}}};\n");
        sb.append("}catch(e){try{if(o.onerror)o.onerror({error:String(e)});}catch(x){}return {abort:function(){}};}}\n");
        // ---- menu command runner ----
        sb.append("window.__boltRunMenuCmd=function(i){try{var f=__MENU_CMDS[i];if(f)f();}catch(e){"
                + "try{console.log('[GM menu] '+e);}catch(x){}}};\n");
        // ---- module shims so UMD headers don't crash ----
        sb.append("var module={exports:{}};var exports=module.exports;"
                + "var require=function(n){return {};};var global=window;\n");
        // ---- @require bundles ----
        sb.append(req);
        sb.append("\ntry{\n");
        sb.append(body);
        sb.append("\n}catch(__e){try{console.log('[Bolt script error] '+(__e&&__e.stack||__e));}catch(x){}}\n");
        sb.append("})();");
        return sb.toString();
    }

    static List<String> resourceNamesOf(Script s) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("//\\s*@resource\\s+(\\S+)\\s+(\\S+)").matcher(s.code);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static String stripHeader(String code) {
        if (code == null) return "";
        int b = code.indexOf("==/UserScript==");
        if (b < 0) return code;
        int nl = code.indexOf('\n', b);
        return nl > 0 ? code.substring(nl + 1) : "";
    }

    // ---------------------------------------------------------------- GM_xmlhttpRequest (Java side)

    private static final java.util.Set<String> aborted = java.util.Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public static void gmXhrAbort(String id) {
        aborted.add(id);
    }

    /** Perform GM_xmlhttpRequest on a Java thread and deliver back into the page. */
    public static void performXhr(final Context c, final String json, final XhrCallback deliver) {
        init(c);
        new Thread(() -> {
            String id = "0";
            try {
                JSONObject o = new JSONObject(json);
                id = o.optString("id");
                String url = o.optString("url");
                String method = o.optString("method", "GET").toUpperCase();
                JSONObject headers = o.optJSONObject("headers");
                String data = o.isNull("data") ? null : o.optString("data");
                int timeout = o.optInt("timeout", 0);
                String responseType = o.optString("responseType", "");
                String user = o.isNull("user") ? null : o.optString("user");
                String password = o.isNull("password") ? null : o.optString("password");

                if (url == null || !(url.startsWith("http") || url.startsWith("https"))) {
                    deliverError(deliver, id, "disallowed url");
                    return;
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod(method.equals("CONNECT") ? "GET" : method);
                conn.setConnectTimeout(timeout > 0 ? Math.min(timeout, 60000) : 15000);
                conn.setReadTimeout(timeout > 0 ? Math.min(timeout, 120000) : 20000);
                conn.setInstanceFollowRedirects(true);
                if (headers != null) {
                    Iterator<String> it = headers.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        try {
                            String key = k == null ? "" : k.trim();
                            if (key.isEmpty()) continue;
                            if (key.equalsIgnoreCase("content-length")) continue;
                            conn.setRequestProperty(key, headers.optString(k));
                        } catch (Throwable ignored) {}
                    }
                }
                // cookies from the WebView jar for seamless session requests
                try {
                    String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                    if (cookies != null && !cookies.isEmpty()
                            && conn.getRequestProperty("Cookie") == null) {
                        conn.setRequestProperty("Cookie", cookies);
                    }
                } catch (Throwable ignored) {}
                if (conn.getRequestProperty("User-Agent") == null) {
                    conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                                    + "Chrome/124 Mobile Safari/537.36");
                }
                if (user != null && !user.isEmpty()) {
                    String basic = android.util.Base64.encodeToString(
                            (user + ":" + (password == null ? "" : password))
                                    .getBytes(StandardCharsets.UTF_8),
                            android.util.Base64.NO_WRAP);
                    conn.setRequestProperty("Authorization", "Basic " + basic);
                }
                if (data != null && !method.equals("GET") && !method.equals("HEAD")) {
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(data.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                if (is != null) {
                    byte[] buf = new byte[16384];
                    int n;
                    long total = 0;
                    while ((n = is.read(buf)) > 0) {
                        if (aborted.contains(id)) { is.close(); conn.disconnect(); return; }
                        bo.write(buf, 0, n);
                        total += n;
                        if (timeout > 0 && total > 64L * 1024 * 1024) break;
                    }
                    is.close();
                }
                byte[] bytes = bo.toByteArray();
                JSONObject res = new JSONObject();
                res.put("status", code);
                String statusMsg = "";
                try { statusMsg = conn.getResponseMessage() == null ? "" : conn.getResponseMessage(); }
                catch (Throwable ignored) {}
                conn.disconnect();
                if (aborted.contains(id)) { aborted.remove(id); return; }
                res.put("statusText", statusMsg);
                res.put("finalUrl", conn.getURL().toString());
                StringBuilder rh = new StringBuilder();
                try {
                    Iterator<Map.Entry<String, List<String>>> hit = conn.getHeaderFields().entrySet().iterator();
                    while (hit.hasNext()) {
                        Map.Entry<String, List<String>> e = hit.next();
                        String k = e.getKey();
                        if (k == null) continue;
                        for (String v : e.getValue()) rh.append(k).append(": ").append(v).append("\r\n");
                    }
                } catch (Throwable ignored) {}
                res.put("responseHeaders", rh.toString());
                boolean binary = responseType != null && (responseType.contains("arraybuffer")
                        || responseType.contains("blob"));
                if (binary) {
                    String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                    res.put("responseB64", b64);
                    res.put("responseText", "");
                } else {
                    res.put("responseText", new String(bytes, StandardCharsets.UTF_8));
                }
                res.put("responseType", responseType == null ? "" : responseType);
                String js = "window.__boltXhrCallback&&window.__boltXhrCallback("
                        + JSONObject.quote(id) + "," + JSONObject.quote(res.toString()) + ");";
                deliver.deliver(js);
            } catch (Throwable e) {
                deliverError(deliver, id, String.valueOf(e));
            } finally {
                aborted.remove(id);
            }
        }, "gmxhr").start();
    }

    private static void deliverError(XhrCallback deliver, String id, String msg) {
        try {
            JSONObject res = new JSONObject();
            res.put("status", 0);
            res.put("error", msg);
            res.put("responseText", "");
            res.put("responseHeaders", "");
            deliver.deliver("window.__boltXhrCallback&&window.__boltXhrCallback("
                    + JSONObject.quote(id) + "," + JSONObject.quote(res.toString()) + ");");
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- http helpers

    static String httpGet(String url) {
        byte[] b = httpGetBytes(url);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    static byte[] httpGetBytes(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                            + "Chrome/124 Mobile Safari/537.36");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) { c.disconnect(); return null; }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            c.disconnect();
            return bo.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }
}
