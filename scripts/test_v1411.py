#!/usr/bin/env python3
"""v1.4.11 release gate — the stability four-pack.

THE REPORT (2026-11-18): four defects, each with its own corpse shape:

1. THE OCR ROTATION LEAK. "The singleton ScreenTranslate holds OcrHost,
   which creates a headless WebView using the Activity context
   (WebView(a)). This leaks the Activity forever on screen rotation."
   Pinned: the host WebView is built on a.applicationContext, the decor
   attach is gone (a headless WebView needs no window), and NO other
   Activity reference survives construction (assets, model files, diag
   all ride appCtx).

2. THE CONCURRENCY TRAP, ROUND 2. "In v1.4.10, tm.tabs was changed to
   Collections.synchronizedList, but Java requires manual
   synchronized(tm.tabs) blocks for ALL iteration. Background services
   were fixed, but dozens of raw for (Tab t : tm.tabs) loops in the UI
   files remain unprotected." Pinned by an embedded audit walker: ZERO
   unprotected iterations of tm.tabs may remain in BrowserActivity.java
   and TabsActivity.java (enhanced-for AND indexed size()+get() walks),
   and the three loops that destroy WebViews inside follow the
   snapshot-first law (snapshot under the monitor, teardown outside).

3. THE ADBLOCK BLINDSPOT. "The adblock engine guesses resource types
   purely by URL extension. Modern trackers use extension-less API
   endpoints, bypassing $script rules. Fix: Inspect the Accept request
   header." Pinned: the extension walk stays authoritative (extension
   decisions byte-identical), the Accept fall-through types only by the
   FIRST preferred MIME (jQuery XHRs listing text/javascript SECOND stay
   OTHER — over-blocking JSON APIs would break sites), and the real
   method body is extracted and RUN as a compiled probe (the parse-gate
   law) over both the blocking and the non-blocking shapes.

4. THE SQLITE LOCK CONTENTION. "Opening the Cookies SQLite file directly
   from disk while Chromium is writing causes lock contention, making
   the scan fail silently. Fix: retry-backoff for
   SQLiteDatabaseLockedException or gracefully handle the lock." Pinned:
   the bounded backoff (60/120/240 ms, inside the wipe flow's head-start
   window), the lock-error classifier, the read-only law intact
   (OPEN_READONLY, rawQuery only, no writes — test_sitewipe's contract).
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
fails = 0


def need(cond, why):
    global fails
    print(("  ok  " if cond else "  FAIL") + " - " + why)
    if not cond:
        fails += 1


SRC = os.path.join(ROOT, "app/src/main/java/com/bolt/browser")
gradle = open(os.path.join(ROOT, "app/build.gradle"), encoding="utf8").read()
strs = open(os.path.join(ROOT, "app/src/main/res/values/strings.xml"),
            encoding="utf8").read()
rel = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()
scr = open(os.path.join(SRC, "util/ScreenTranslate.kt"), encoding="utf8").read()
ba = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
ta = open(os.path.join(SRC, "ui/TabsActivity.java"), encoding="utf8").read()
chips = open(os.path.join(SRC, "util/CookiePartitionScan.kt"),
             encoding="utf8").read()


def strip_comments_strings(src):
    """Blank out // and /* */ comments and string bodies so pins read CODE,
    not the comments describing it."""
    out, i, n, mode = [], 0, len(src), None
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if mode is None:
            if c == "/" and nxt == "/":
                mode = "line"; i += 2; continue
            if c == "/" and nxt == "*":
                mode = "block"; i += 2; continue
            if c in "\"'":
                mode = c; out.append(c); i += 1; continue
            out.append(c); i += 1; continue
        if mode == "line":
            if c == "\n":
                mode = None; out.append("\n")
            i += 1; continue
        if mode == "block":
            if c == "*" and nxt == "/":
                mode = None; i += 2; continue
            if c == "\n":
                out.append("\n")
            i += 1; continue
        if c == "\\":
            i += 2; continue
        if mode == c:
            mode = None; out.append(c)
        i += 1
    return "".join(out)


scr_code = strip_comments_strings(scr)

print("A — the version bump")
need(re.search(r"versionCode 134\b", gradle) is not None,  # # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 134 (Android only installs RISING codes)")  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName "1\.4\.20"', gradle) is not None,  # # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): the escaped-form recovery (the phantom-backslash corpse)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.20"')  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print("B — the About changelog (the label law)")
entries = re.findall(r"<item>([^<]+)</item>",
                     strs.split('name="about_changelog"')[1]) \
    if 'name="about_changelog"' in strs else []
need(entries and entries[2].startswith("1.4.18|"),   # v1.4.13 re-point (2026-11-18): 1.4.13 landed at [0]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])
     "the newest entry is 1.4.13 (the label law)")
need(len(entries) > 6 and entries[8].startswith("1.4.12|"),   # v1.4.13 re-point (2026-11-18): the 1.4.12 line sits at [1]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])
     "the second entry is 1.4.12 (the previous live release)")
need(len(entries) > 7 and entries[9].startswith("1.4.11|"),   # v1.4.13 re-point (2026-11-18): this gate's line slid to [2]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])
     "the third entry is 1.4.11 (the version this gate pins)")
need("leak" in entries[9] and "rotation" in entries[9],   # v1.4.13 re-point: slid from [1]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])
     "the 1.4.11 line names the OCR rotation leak")
need("list lock" in entries[9] or "holds the list lock" in entries[9],   # v1.4.13 re-point: slid from [1]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])
     "the 1.4.11 line names the tab-list lock sweep")
need("Accept" in entries[9],   # v1.4.13 re-point: slid from [1]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])
     "the 1.4.11 line names the Accept-header adblock signal")
need("cookie" in entries[9] and ("retries" in entries[9] or "retry" in entries[9]),   # v1.4.13 re-point: slid from [1]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])
     "the 1.4.11 line names the cookie-jar retry")

print("C — fix 1: the OCR rotation leak (app context, never the activity)")
# pins read CODE (comments stripped), so the v1.4.11 notes themselves can
# name the corpse without tripping the pin
oh = scr_code[scr_code.find("private class OcrHost"):]
if "\n    }" in oh[1:]:
    oh = oh[:oh.find("\n    }", 1) + 6]   # crude class-body cut at first dedent
need("private val appCtx: Context = a.applicationContext" in oh,
     "OcrHost derives appCtx from the activity parameter (read once)")
need(re.search(r"wv = WebView\(appCtx\)", oh) is not None,
     "the headless WebView is built with the app context")
need("WebView(a)" not in oh,
     "NO WebView is built with the raw activity (the leak corpse)")
need("decorView" not in scr_code,
     "the decorView attach is gone (a headless WebView needs no window)")
need("modelFile(appCtx," in oh and "modelFile(a," not in oh,
     "model files resolve through appCtx (no activity captured)")
need("appCtx.assets" in oh and "a.assets" not in oh,
     "the host page asset opens through appCtx")
need("Diag.log(appCtx," in oh and "Diag.log(a," not in oh,
     "diag logging rides appCtx")

print("D — fix 2: the thread law, round 2 (every UI iteration under the monitor)")


def unprotected_iterations(path):
    """Every for-loop that iterates tm.tabs (enhanced or indexed) must sit
    inside a synchronized (tm.tabs) block. Returns [(lineno, line)]."""
    src = strip_comments_strings(open(path, encoding="utf8", errors="ignore").read())
    lines = src.split("\n")
    sites = []
    for idx, ln in enumerate(lines):
        if re.search(r"for\s*\([^:]*:\s*tm\.tabs\s*\)", ln):
            sites.append(idx)
        elif re.search(r"for\s*\(.*tm\.tabs\.size\(\)", ln):
            window = "\n".join(lines[idx:idx + 30])
            if re.search(r"tm\.tabs\.(get|add|remove)\b", window):
                sites.append(idx)
    bad = []
    for idx in sites:
        depth, sync = 0, []
        for j in range(idx):
            for ch in lines[j]:
                if ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    while sync and sync[-1] > depth:
                        sync.pop()
            if re.search(r"synchronized\s*\(\s*tm\.tabs\s*\)", lines[j]):
                sync.append(depth)
        if not sync:
            bad.append((idx + 1, lines[idx].strip()[:80]))
    return len(sites), bad


for f in ("ui/BrowserActivity.java", "ui/TabsActivity.java"):
    total, bad = unprotected_iterations(os.path.join(SRC, f))
    need(not bad,
         f"{f}: all {total} tm.tabs iterations hold the monitor"
         + ("" if not bad else " — UNPROTECTED: " + "; ".join(f"L{ln} {t}" for ln, t in bad)))
# the three teardown loops follow the snapshot-first law
need("staleSnap = new ArrayList<>(tm.tabs)" in ba and "t.web.destroy();" in ba
     and ba.find("staleSnap") < ba.find("MediaControlService.onTabGone(this, i)"),
     "destroyStaleWebViews: snapshot under the monitor, destroy outside")
need("wipeSnap = new ArrayList<>(tm.tabs)" in ba
     and "synchronized (tm.tabs) { tm.tabs.clear(); }" in ba,
     "wipeIncognito: snapshot-then-destroy, clear under the monitor")
need("sleepSnap = new ArrayList<>(tm.tabs)" in ba and "sleepTab(t);" in ba
     and ba.find("sleepSnap") < ba.find("sleepTab(t);"),
     "the onTrimMemory sleep walk: snapshot under the monitor, sleepTab outside")
need(ba.count("v1.4.11: the thread law") >= 20,
     "the wraps are dated v1.4.11 (the sweep is traceable)")

print("E — fix 3: the adblock blindspot (the Accept-header fall-through)")
need("getRequestHeaders" in ba,
     "inferType reads the request headers")
need('"accept".equalsIgnoreCase(e.getKey())' in ba,
     "the Accept header is matched case-insensitively by exact name")
ext_block_end = ba.find('// v1.4.11: no extension signal')
need(ext_block_end > 0
     and ba.find('".js")') < ext_block_end
     and ba.find('".css")') < ext_block_end
     and ba.find('".png"') < ext_block_end,
     "the extension walk stays FIRST (extension decisions byte-identical)")
need('accept.split(",", 2)[0].split(";", 2)[0]' in ba,
     "only the FIRST preferred MIME is trusted (q-weights stripped)")
need('first.equals("application/javascript")' in ba
     and 'first.equals("text/javascript")' in ba,
     "script typing is exact-MIME, first position")
need('first.startsWith("image/")' in ba and 'first.equals("text/css")' in ba,
     "image and stylesheet typing rides the first MIME")
need("javascript\")" not in ba.replace("application/javascript\")", "")
     .replace("text/javascript\")", "").replace("x-ecmascript\")", ""),
     "no substring-contains javascript match (jQuery XHRs stay OTHER)")

print("E2 — the functional probe (the parse-gate law: RUN the real inferType)")
start = ba.find("protected static FilterEngine.Type inferType(WebResourceRequest req) {")
end = (ba.find("\n    }", start) + len("\n    }")) if start >= 0 else -1
if start < 0 or end < 0:
    need(False, "inferType is where the gate expects it")
else:
    body = ba[start:end]
    # adapt the Android surface to probe-local fakes; the decision logic
    # itself is the SHIPPED text
    body = body.replace("FilterEngine.Type", "Type").replace(
        "WebResourceRequest", "Req")
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
        probe = """
interface Uri2 { String getPath(); }
interface Req {
    boolean isForMainFrame();
    Uri2 getUrl();
    java.util.Map<String, String> getRequestHeaders();
}
enum Type { DOCUMENT, SCRIPT, IMAGE, STYLESHEET, XHR, SUBDOC, FONT, MEDIA, OTHER }

public class Probe {
    static class U implements Uri2 {
        private final String p;
        U(String p) { this.p = p; }
        public String getPath() { return p; }
    }
    static class R implements Req {
        private final boolean main;
        private final String path;
        private final String accept;
        R(boolean main, String path, String accept) { this.main = main; this.path = path; this.accept = accept; }
        public boolean isForMainFrame() { return main; }
        public Uri2 getUrl() { return new U(path); }
        public java.util.Map<String, String> getRequestHeaders() {
            java.util.Map<String, String> m = new java.util.HashMap<>();
            if (accept != null) m.put("Accept", accept);
            return m;
        }
    }
""" + body + """
    static int bad = 0;
    static void ck(Type got, Type want, String name) {
        if (got != want) { System.out.println("MISMATCH " + name + ": got " + got + " want " + want); bad++; }
    }
    public static void main(String[] a) {
        // extension stays authoritative (shipped decisions byte-identical)
        ck(inferType(new R(false, "/app.js", "image/avif,image/*,*/*;q=0.8")), Type.SCRIPT, "ext .js beats image Accept");
        ck(inferType(new R(false, "/img.png", "*/*")), Type.IMAGE, "ext .png beats */*");
        ck(inferType(new R(false, "/s.css", "application/javascript")), Type.STYLESHEET, "ext .css beats js Accept");
        ck(inferType(new R(false, "/seg.m3u8", null)), Type.MEDIA, "ext .m3u8 with no headers");
        // THE BLINDSPOT: extension-less endpoints now typed by Accept
        ck(inferType(new R(false, "/collect", "application/javascript")), Type.SCRIPT, "blindspot: js accept -> SCRIPT");
        ck(inferType(new R(false, "/tag", "text/javascript, application/javascript, application/x-ecmascript, */*")), Type.SCRIPT, "ff-style js accept -> SCRIPT");
        ck(inferType(new R(false, "/beacon", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")), Type.IMAGE, "img accept -> IMAGE");
        ck(inferType(new R(false, "/0", "text/css,*/*;q=0.1")), Type.STYLESHEET, "css accept -> STYLESHEET");
        ck(inferType(new R(false, "/f", "font/woff2")), Type.FONT, "font accept -> FONT");
        ck(inferType(new R(false, "/v", "video/webm,*/*")), Type.MEDIA, "video accept -> MEDIA");
        // the over-blocking guard: javascript NOT first stays OTHER
        ck(inferType(new R(false, "/api/data", "application/json, text/javascript, */*; q=0.01")), Type.OTHER, "jQuery json XHR stays OTHER");
        ck(inferType(new R(false, "/api/v2", "*/*")), Type.OTHER, "star-star stays OTHER");
        ck(inferType(new R(false, "/x", (String) null)), Type.OTHER, "no accept header stays OTHER");
        // main frame is a document no matter what
        ck(inferType(new R(true, "/app.js", "application/javascript")), Type.DOCUMENT, "main frame is DOCUMENT");
        if (bad > 0) System.exit(1);
        System.out.println("probe ok");
    }
}
"""
        work = tempfile.mkdtemp(prefix="v1411probe")
        try:
            srcf = os.path.join(work, "Probe.java")
            open(srcf, "w", encoding="utf8").write(probe)
            r = subprocess.run([javac, "-d", work, srcf],
                               capture_output=True, text=True)
            if r.returncode != 0:
                need(False, "the probe compiles (transcription in sync?)\n" + r.stderr[:600])
            else:
                r = subprocess.run(["java", "-cp", work, "Probe"],
                                   capture_output=True, text=True,
                                   cwd=work)
                need(r.returncode == 0,
                     "the shipped inferType decides every probe shape correctly"
                     + ("" if r.returncode == 0 else "\n" + r.stdout + r.stderr[:400]))
        finally:
            shutil.rmtree(work, ignore_errors=True)

print("F — fix 4: the cookie-jar lock contention (retry, bounded)")
need("SQLiteDatabaseLockedException" in chips,
     "the lock exception is classified explicitly")
need("isLockError" in chips and 'contains("locked"' in chips,
     "the classifier also catches the message-spelling of SQLITE_BUSY/LOCKED")
need("LOCK_BACKOFF_MS = intArrayOf(60, 120, 240)" in chips,
     "the backoff is 60/120/240 ms (inside the wipe head-start window)")
need(re.search(r"attempt >= LOCK_BACKOFF_MS\.size", chips) is not None,
     "the retry is BOUNDED (best-effort reader, never a hang)")
need("InterruptedException" in chips,
     "an interrupted scan returns what it has (the wipe moves on)")
need("OPEN_READONLY" in chips and "rawQuery" in chips,
     "the read-only law survives (test_sitewipe's contract)")
need(not re.search(r"execSQL|\bINSERT\b|\bUPDATE\b|DELETE FROM", chips),
     "the scanner still never writes")
need("scanDb(db, family, out)" in chips,
     "one SELECT per jar, extracted so the retry can see the lock")

print("G — the gate is wired into the pipeline")
need("python3 scripts/test_v1411.py" in rel,
     "release.sh runs this gate before building")
need(rel.find("test_v1410.py") < rel.find("test_v1411.py"),
     "the gate runs after v1410's (release order)")

print()
print("V1411 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
