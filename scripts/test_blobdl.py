#!/usr/bin/env python3
"""v1.3.56 + v1.3.57 + v1.3.58 blob-download + copy regression test (THE
'Gemini generated images: cannot download this file type' + 'Gemini
replies take too long to copy, if they copy at all' field reports,
round 3).

BLOB DOWNLOADS. AI-chat image downloads (Gemini) hand the browser a
blob: URL: the bytes exist only inside the page that created them, and
Java cannot fetch a blob: URL — the pre-v1.3.56 enqueueDownload rejected
every one with 'Can't download this link type'. v1.3.56 added the
fetch → canvas chain; v1.3.57 added the own-img mint + shadow-piercing
hunts. The v1.3.57 FIELD REPORT still showed the download BUTTON failing
(dl offer + blobdl start, no gmSaveBlob) — and carried the fingerprint
that finally named the root cause: `dl offer blob:null/<uuid>`. A NULL
origin means the blob was minted inside an OPAQUE-ORIGIN frame (a
sandboxed iframe), verified by a live browser experiment: the parent can
NEVER read such bytes — fetch() rejects, XHR errors, even a minted
new Image() with src=blob:null fires onerror. Every main-frame stage was
structurally dead. v1.3.58 adds util/FrameHooks.kt: a DOCUMENT-START
script registered for every frame (WebViewCompat.
addDocumentStartJavaScript, origin rule '*' — Chromium's per-frame
RunScriptsAtDocumentStart matches opaque origins under the wildcard;
verified in js_communication.cc + origin_matcher.cc) that wraps
URL.createObjectURL and HOLDS the Blob object in the minting frame
(readable via FileReader even after revokeObjectURL), plus a postMessage
relay (the one channel that crosses opaque origins). BlobDownload.kt
chain v3 starts with the FRAME DANCE: hand back a held blob or broadcast
needBlob to all child frames; first blobData reply wins. The v1.3.57
stages (fetch → own-img → shadow hunt → anchor hunt → honest failure)
stay as fallbacks for frames that predate the hook.

IN-PAGE COPY BUTTONS (v1.3.57 → v1.3.58). The page's own Copy button
calls navigator.clipboard.writeText — structurally dead in Android
WebView (no permission grant path; web-verified: MDN
browser-compat-data #20867, flutter_inappwebview #1777, chromium
40205793). v1.3.57's util/ClipboardWrite.kt shimmed it in the MAIN
FRAME only — and had a premature-guard hole (the __boltClip guard was
set before the capability check, so a document whose navigator.clipboard
wasn't ready yet stayed poisoned for life even after re-injection), and
no answer for a button living inside a frame. v1.3.58 FOLDS the shim
into FrameHooks.HOOK_JS: it installs in EVERY frame, RETRIES (250ms x40)
until navigator.clipboard exists before setting the guard, routes
through Bolt.clipPage where the bridge exists (new bridge method: same
write + toast as Bolt.copy, plus the `clip page n=<chars> via=…`
field-evidence diag line) and through the copyText postMessage relay
from bridge-less frames; readText/read/execCommand stay native.

SELECTION COPY (v1.3.56, still guarded). Capture at menu-open AND at
every throttled onPrepareActionMode; 'Copying…' + forced capture on an
empty Copy tap; shadow-DOM-aware reads; JSONTokener decode.

This test fails if any of that machinery is removed, reordered (the
blob: branch MUST precede the !http rejection), or rot-gut. The bridge
surface pin is now 23 (v1.3.58 added Bolt.clipPage + Bolt.dlTrace
DELIBERATELY — field-evidence channels for both bugs; every other
bridge method unchanged).
"""
import pathlib, re, subprocess, sys, tempfile

BASE = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java/com/bolt/browser"
REPO = pathlib.Path(__file__).resolve().parent.parent
failures = []


def strip_comments(text):
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text


def rd(p):
    f = BASE / p
    return f.read_text(errors="ignore") if f.exists() else ""


# ---------------------------------------------------------------- JS payload
def unescape_kotlin(chunk):
    return chunk.replace('\\"', '"').replace('\\\\', '\\')


def kotlin_chunks(expr_text):
    out, i = [], 0
    while i < len(expr_text):
        q = expr_text.find('"', i)
        if q < 0:
            break
        j, buf = q + 1, []
        while j < len(expr_text):
            c = expr_text[j]
            if c == '\\' and j + 1 < len(expr_text):
                buf.append(c)
                buf.append(expr_text[j + 1])
                j += 2
            elif c == '"':
                break
            else:
                buf.append(c)
                j += 1
        out.append(unescape_kotlin(''.join(buf)))
        i = j + 1
    return ''.join(out)


def node_check(js, name, fragments):
    """syntax-check a reconstructed JS payload AND assert the fragments
    that carry its semantics (a corrupted escape can still be valid JS)"""
    bad = [f for f in fragments if f not in js]
    if bad:
        failures.append(f"{name}: JS payload lost fragments {bad}")
        return
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(js)
        path = f.name
    r = subprocess.run(["node", "--check", path], capture_output=True, text=True)
    if r.returncode != 0:
        failures.append(f"{name}: JS payload no longer parses: "
                        f"{r.stderr.strip()[:200]}")


# ---- 1. BlobDownload.kt: the rescue chain exists and is wired ----
blobkt = rd("util/BlobDownload.kt")

kt = (BASE / "util/SelectionActions.kt").read_text() if (BASE / "util/SelectionActions.kt").exists() else ""
a = kt.find("private const val CAPTURE_JS = ")
b = kt.find("interface Host")
if a < 0 or b <= a:
    failures.append("SelectionActions: CAPTURE_JS block not found")
else:
    node_check(kotlin_chunks(kt[a:b]), "SelectionActions.CAPTURE_JS",
               ["replace(/\\s+/g,'')", "getComposedRanges", "createRange",
                "setStart", "cloneContents", "shadowRoot", "sr.getSelection"])

a = blobkt.find('return "(function(){try{"')
tail = '}catch(x){}}})();"'
b = blobkt.find(tail)
if a < 0 or b <= a:
    failures.append("BlobDownload: chainJs return block not found")
else:
    expr = blobkt[a:b + len(tail)]
    dq = chr(34)
    for var, val in (("qU", "blob:x"), ("qN", "f"), ("qM", "image/png")):
        expr = expr.replace("+ " + var + " +",
                            "+ " + dq + "'" + val + "'" + dq + " +")
    node_check(kotlin_chunks(expr), "BlobDownload.chainJs",
               ["fetch(", "readAsDataURL", "Bolt.gmSaveBlob", "toDataURL",
                "image\\/(png|jpeg|webp)", "a[download]", "naturalWidth",
                "currentSrc", "nextElementSibling",
                "Bolt.notify('Download failed')",
                # v1.3.57 — the OWN-IMG stage (works for same-origin blob:
                # https URLs; blob:null dies by origin — stage 0 covers those)
                "new Image()", "im.onload", "im.onerror", "im.src=U",
                "ownStage",
                # v1.3.57 — the displayed-img hunt pierces open shadow roots
                "shadowRoot", "q.push(els[j].shadowRoot)", "allImgs()",
                "huntStage", "anchorStage", "function tail()",
                # v1.3.58 — THE FRAME DANCE (the blob:null fix): ask every
                # frame for the blob by URL through the FrameHooks relay
                "frameStage", "window.__boltBlobs", "needBlob", "blobData",
                "contentWindow.postMessage", "Bolt.dlTrace(s)",
                "readHeld", "if(!frameStage())fetchStage();"])

# the chain must RUN the stages in the rescue order: frame dance first
# (blob:null bytes live ONLY in the minting frame), then fetch (exact
# bytes where CSP permits), then own-img, then the hunts, then the
# honest failure
if blobkt.find("function ownStage()") < 0:
    failures.append("BlobDownload: own-img stage is gone (the v1.3.57 "
                    "fix for same-origin blob downloads)")
else:
    i_dance = blobkt.find("function frameStage()")
    i_fetch = blobkt.find('"fetch(U).then')
    i_own = blobkt.find("ownStage();")
    i_tail = blobkt.find("function tail()")
    i_no = blobkt.find("function no()")
    if not (0 <= i_fetch < i_own and 0 <= i_tail and 0 <= i_no):
        failures.append("BlobDownload: stage order broken (fetch → own "
                        "→ hunts → no)")
    if i_dance < 0:
        failures.append("BlobDownload: the v1.3.58 frame-dance stage is "
                        "gone (blob:null downloads — Gemini's download "
                        "button — die again)")
    if "if(!frameStage())fetchStage();" not in blobkt:
        failures.append("BlobDownload: the chain no longer STARTS with "
                        "the frame dance")
    for frag, why in (("window.__boltBlobs", "held-blob map lookup"),
                      ("t:'needBlob'", "cross-frame blob request"),
                      ("t!=='blobData'", "cross-frame blob reply listener"),
                      ("},1200);", "dance timeout falls through to fetch")):
        if frag not in blobkt:
            failures.append(f"BlobDownload: frame dance lost its {why} "
                            f"({frag})")

if "fun start(web: WebView?" not in blobkt:
    failures.append("BlobDownload.start(web, url, name, mime) entry missing")
if 'url.startsWith("blob:")' not in strip_comments(blobkt):
    failures.append("BlobDownload.start no longer guards the blob: prefix")
if "web.post" not in blobkt:
    failures.append("BlobDownload.start must post to the WebView's UI thread")
if "JavascriptInterface" in strip_comments(blobkt):
    failures.append("BlobDownload adds a JS interface method — the bridge "
                    "surface must stay untouched (verify_bridge)")

# ---- 2. BrowserActivity: blob: routing BEFORE the !http rejection ----
ba = rd("ui/BrowserActivity.java")
ed = ba.find("protected void enqueueDownload(String url, String ua, String name, String mime)")
if ed < 0:
    failures.append("enqueueDownload signature changed (wiring lost?)")
else:
    body = ba[ed:ed + 4000]
    blob_branch = body.find('url.startsWith("blob:")')
    reject = body.find('!url.startsWith("http")')
    data_branch = body.find('url.startsWith("data:")')
    if blob_branch < 0:
        failures.append("enqueueDownload: blob: branch is gone (Gemini "
                        "image downloads die again)")
    if reject < 0:
        failures.append("enqueueDownload: the non-http rejection toast is "
                        "gone (unknown schemes would fall through)")
    if blob_branch >= 0 and reject >= 0 and blob_branch > reject:
        failures.append("enqueueDownload: blob: branch comes AFTER the "
                        "!http rejection — unreachable, downloads die")
    if data_branch < 0 or (blob_branch >= 0 and data_branch > blob_branch):
        failures.append("enqueueDownload: data: branch missing or misordered")
    if "BlobDownload.start(" not in body:
        failures.append("enqueueDownload: no BlobDownload.start call")
    if "Can't download this link type" not in body:
        failures.append("enqueueDownload: honest rejection for genuinely "
                        "unsupported schemes is gone")

# the blob branch must target the ACTIVE tab's WebView (evalMedia can
# point at a background media tab and swallow the injection into the
# wrong page — the exact Gemini+YouTube-music field scenario)
blob_call = body.find("BlobDownload.start(")
if blob_call < 0 or "activeTab()" not in body[:blob_call]:
    failures.append("enqueueDownload: blob: branch does not resolve the "
                    "active tab's WebView before calling the chain")

# ---- 3. downloadInPageImage: blob: routes through the chain, exact tab ----
dp = ba.find("protected void downloadInPageImage(final String pageUrl, String name)")
if dp < 0:
    failures.append("downloadInPageImage: method signature changed")
else:
    body = ba[dp:dp + 2500]
    blob_b = body.find('pageUrl.startsWith("blob:")')
    if blob_b < 0:
        failures.append("downloadInPageImage: blob: branch is gone (long-"
                        "press image save on strict-CSP sites dies again)")
    else:
        seg = body[:body.find("String js =") if "String js =" in body else len(body)]
        if "BlobDownload.start(t.web" not in seg:
            failures.append("downloadInPageImage: blob: branch does not "
                            "call BlobDownload.start with the long-pressed "
                            "tab's WebView (t.web)")
        if "evalMedia(js)" in seg:
            failures.append("downloadInPageImage: the blob: path still "
                            "injects via evalMedia (wrong-tab risk)")
    if 'pageUrl.startsWith("data:")' not in body:
        failures.append("downloadInPageImage: data: branch lost")
    if "saveDataUrl(pageUrl, nm, null)" not in body:
        failures.append("downloadInPageImage: data: branch no longer saves "
                        "via the engine")

# ---- 4. bridge surface (v1.3.58: 23 — clipPage + dlTrace added
# DELIBERATELY as field-evidence channels; v1.3.79: 25 — favIcon(host,
# dataUrl) + favMiss(host) added DELIBERATELY as the universal favicon
# pass's landing; bump this pin deliberately, never as a side effect) ----
gm = ba.find("public void gmSaveBlob(final String name, final String mime, final String dataUrl)")
if gm < 0:
    failures.append("JsBridge.gmSaveBlob signature changed — the chain's "
                    "delivery path AND verify_bridge both break")
# v1.3.86: 25 -> 26 — Bolt.noteDownloadAnchor (the a[download] click
# capture's landing); v1.3.87: 26 -> 27 — Bolt.aiCapture (the AI response
# exporter's page->Java handoff; bump deliberately, never as a side effect);
# v1.3.92: 27 -> 28 — Bolt.aiSync (the native AI picker's scroll-sync:
# the page reports its scroll rect so the Android circle overlay follows)
# v1.4.20 note: the ask-every-time door deliberately did NOT add a bridge
# — gmSaveBlob itself consults the pref (one delivery contract for every
# rescue caller), so the count stays 28
# v1.4.25 (2026-11-21): 28 -> 29 — pwFillResult (the password fill
# outcome: toast + Diag, never silent — the deliberate bridge addition)
# v1.4.31 (2026-11-25): 29 -> 30 — Bolt.passkeyLoad (THE PASSKEY LAZY
# LAW's bridge door: the <=3KB bootstrap parks the first real
# credentials call and asks for the full ~55KB layer exactly once)
if ba.count("@JavascriptInterface") != 30:
    failures.append("BrowserActivity @JavascriptInterface count changed "
                    f"(expected 30, found {ba.count('@JavascriptInterface')}) — "
                    "the bridge surface must change only deliberately")
for sig, why in (
        ("public void noteDownloadAnchor(final String href, final String name)",
         "the a[download] click capture (v1.3.86 real download names)"),
        ("public void clipPage(final String text, final String via)",
         "the page-copy bridge (shim delivery + clip page diag line)"),
        ("public void dlTrace(final String s)",
         "the per-stage download trace bridge")):
    if sig not in ba:
        failures.append(f"JsBridge: {why} is gone")

# ---- 4b. FrameHooks.kt: the per-frame hook (v1.3.58 — THE fix for both
# round-3 bugs: blob capture inside opaque frames + clipboard in frames) ----
fhkt = rd("util/FrameHooks.kt")
if not fhkt:
    failures.append("FrameHooks.kt is gone (blob:null downloads + in-frame "
                    "copy buttons die again — the v1.3.58 fix)")
else:
    a = fhkt.find('HOOK_JS = "(function(){try{"')
    tail = '"}catch(e){}})();"'
    b = fhkt.find(tail)
    if a < 0 or b <= a:
        failures.append("FrameHooks: HOOK_JS block not found")
    else:
        node_check(kotlin_chunks(fhkt[a:b + len(tail)]),
                   "FrameHooks.HOOK_JS",
                   ["if(window.__boltFH)return;window.__boltFH=1;",
                    "window.__boltBlobs={}",
                    "URL.createObjectURL=function(b){",
                    # v1.3.86 — EVERY blob type is held (text/CSV/PDF/zip
                    # exports revoked in the same tick as their download
                    # click). v1.3.89 — the 48 MB TOTAL byte budget,
                    # oldest-first eviction, 6-entry cap (was: 32 MB per
                    # blob x 6 by key count = a 192 MB worst-case RAM pin)
                    "b.size<=50331648",
                    "window.__boltBlobBytes=0",
                    "while((bs+(b.size||0)>50331648)||Object.keys(m).length>=6)",
                    "if(bs+(b.size||0)<=50331648){",
                    "window.addEventListener('message',function(e){",
                    "t:'needBlob'",
                    "t:'blobData'",
                    "Bolt.clipPage(d.text,'relay')",
                    "t:'copyText'",
                    "t:'dlName'",
                    "Bolt.noteDownloadAnchor(d.u,d.n)",
                    "if(n<40)setTimeout(function(){armClip(n+1);},250)",
                    "if(!haveBridge&&!isFrame){window.__boltClip=1;return;}",
                    "Bolt.clipPage(t,'direct')",
                    "parent.postMessage({__bolt:1,t:'copyText',text:t},'*')",
                    "put(nc,'writeText',shim)",
                    "it.getType('text/plain')",
                    "window.__boltClip=1",
                    "r.readAsDataURL(b)",
                    # v1.3.86 — the a[download] click capture: CAPTURE phase
                    # so synthetic element.click() dispatches are seen too
                    "n.closest('a[download]')",
                    "Bolt.noteDownloadAnchor(h,d)",
                    "},true);"])
    for frag, why in (
            ("addDocumentStartJavaScript", "document-start registration"),
            ("DOCUMENT_START_SCRIPT", "feature gate"),
            ('Collections.singleton("*")', "wildcard rule (opaque-origin "
                                            "frames included)"),
            ("fun arm(", "arm entry"),
            ("fun injectNow(", "eval fallback entry"),
            ("WeakHashMap", "arm-once guard")):
        if frag not in fhkt:
            failures.append(f"FrameHooks: {why} missing ({frag})")
    if "JavascriptInterface" in strip_comments(fhkt):
        failures.append("FrameHooks adds a JS interface method — the bridge "
                        "lives in BrowserActivity (verify_bridge)")
    if "ClipboardWrite" in strip_comments(fhkt).replace("v1.3.57's ClipboardWrite", ""):
        # historical notes in comments are fine; live references are not
        if "ClipboardWrite.injectNow" in fhkt:
            failures.append("FrameHooks still delegates to the removed "
                            "ClipboardWrite object")

# ---- 4b-2. Tab.buildWeb arms the hooks BEFORE the first load ----
tab = rd("core/Tab.java")
if "com.bolt.browser.util.FrameHooks.arm(w)" not in tab:
    failures.append("Tab.buildWeb: FrameHooks.arm(w) call is gone (the "
                    "per-frame hook never registers — blob:null downloads "
                    "and in-frame copy buttons die again)")
else:
    i_arm = tab.find("com.bolt.browser.util.FrameHooks.arm(w)")
    i_clients = tab.find("w.setWebViewClient(vc)")
    if 0 < i_clients < i_arm:
        failures.append("Tab.buildWeb: FrameHooks.arm must run before the "
                        "WebView is wired/loaded (document-start scripts "
                        "only cover frames that load after registration)")

# ---- 4c. the hooks are injected at BOTH core-injection moments, ALWAYS
# (incognito included — the round-2 report was from the incognito browser) ----
for site, probe in (("onPageStarted", "public void onPageStarted(WebView view, String url, Bitmap favicon)"),
                    ("onPageCommitVisible", "public void onPageCommitVisible(WebView view, String url)")):
    i = ba.find(probe)
    if i < 0:
        failures.append(f"BrowserActivity: {site} hook disappeared")
    else:
        # v1.3.61: the window is the WHOLE method body (the fixed 2800-char
        # window broke whenever honest additions to these methods — e.g. the
        # fullscreen resets — pushed the injection call past it)
        i_end = ba.find("\n        @Override", i + len(probe))
        if i_end < 0:
            i_end = i + 4000
        seg = ba[i:i_end]
        # v1.4.31 re-point (2026-11-25): THE SINGLE-INJECTION LAW — the
        # commit-time evaluate twin is GONE BY LAW (the document-start
        # registration covers every frame of every navigation; the ONE
        # guarded fallback per document lives at onPageStarted, and the
        # __boltFH guard keeps re-runs harmless). Old-WebView coverage is
        # the onPageStarted leg — checked below, unchanged.
        if site == "onPageStarted" and "FrameHooks.injectNow(view)" not in seg:
            failures.append(f"BrowserActivity: {site} no longer injects "
                            "FrameHooks (the one fallback per document "
                            "lost — copy buttons die on WebViews without "
                            "DOCUMENT_START_SCRIPT)")
        if "ClipboardWrite.injectNow" in seg:
            failures.append(f"BrowserActivity: {site} still injects the "
                            "removed ClipboardWrite (dead reference)")
        if site == "onPageStarted" and "if (!incognito) injectUserscripts" in seg:
            # the fallback must NOT be inside the incognito-gated userscript
            # branch — find its actual line and confirm it sits before
            # the gate line
            i_shim = seg.find("FrameHooks.injectNow(view)")
            i_gate = seg.find("if (!incognito) injectUserscripts")
            if not (0 <= i_shim < i_gate):
                failures.append("BrowserActivity: the frame-hook fallback "
                                "is gated behind !incognito (the round-2 "
                                "report is FROM the incognito browser)")

# ---- 4d. field-evidence diag lines (v1.3.57 + v1.3.58): the next
# 'still fails' report must show WHICH step died ----
for frag, why in (("dl offer ", "download-offer evidence"),
                  ("blobdl start name=", "blob-chain entry evidence"),
                  ("gmSaveBlob ", "bytes-returned evidence"),
                  ("clip page n=", "page-copy-button evidence (v1.3.58)"),
                  ('"blobdl " +', "per-stage download trace (v1.3.58)")):
    if frag not in ba:
        failures.append(f"BrowserActivity: {why} diag line ({frag.strip()}) "
                        "is gone")

# ---- 5. SelectionActions: the copy robustness machinery ----
sa_code = strip_comments(rd("util/SelectionActions.kt"))
if "JSONTokener" not in sa_code:
    failures.append("SelectionActions: JSON decode of evaluateJavascript "
                    "results is gone (multi-line copies get literal \\n)")
if "removeSurrounding" in sa_code:
    failures.append("SelectionActions: the old broken unquote "
                    "(removeSurrounding) is back")
if "RECAPTURE_MS" not in sa_code:
    failures.append("SelectionActions: the capture throttle is gone "
                    "(every selection edit would hammer the renderer)")
prep = sa_code.find("override fun onPrepareActionMode")
if prep < 0 or "capture()" not in sa_code[prep:prep + 600]:
    failures.append("SelectionActions: onPrepareActionMode no longer re-"
                    "captures (stale selection text at Copy time)")
if "capturePending" not in sa_code:
    failures.append("SelectionActions: pending-capture guard is gone")
copy_at = sa_code.find("ID_COPY -> {")
if copy_at < 0:
    failures.append("SelectionActions: Copy action handler missing")
else:
    seg = sa_code[copy_at:copy_at + 2500]
    if "R.string.copying" not in seg:
        failures.append("SelectionActions: Copy slow path lost its "
                        "'Copying…' feedback (silent-wait regression)")
    if "capture(force = true)" not in seg:
        failures.append("SelectionActions: Copy slow path no longer forces "
                        "a fresh capture")
    if "R.string.copy_slow_hint" not in seg:
        failures.append("SelectionActions: Copy failure no longer tells the "
                        "user to try the system Copy row")
    if "mode.finish()" not in seg:
        failures.append("SelectionActions: Copy never finishes the "
                        "ActionMode (menu would linger)")
# fast path must finish; slow path must finish only inside the capture
# callback (finishing early clears the selection being read)
fast = sa_code.find("if (!text.isNullOrBlank()) {")
if fast < 0 or "mode.finish()" not in sa_code[fast:fast + 300]:
    failures.append("SelectionActions: Copy fast path no longer finishes "
                    "the ActionMode")
for handler in ("ID_SEARCH ->", "ID_TRANSLATE ->", "ID_QR ->"):
    h = sa_code.find(handler)
    if h < 0 or "mode.finish(); return true" not in sa_code[h:h + 300]:
        failures.append(f"SelectionActions: {handler[:-3]} handler changed "
                        "shape (must finish + return true)")
if "orig.onActionItemClicked(mode, item)" not in sa_code:
    failures.append("SelectionActions: delegation to the original callback "
                    "is gone (system menu items would die)")
if "orig.onCreateActionMode(mode, menu)" not in sa_code:
    failures.append("SelectionActions: original menu population is gone "
                    "(system Cut/Copy/Share rows would disappear)")
if "onDestroyActionMode" not in sa_code or "selection = null" not in sa_code:
    failures.append("SelectionActions: teardown no longer clears state")

# ---- 6. strings ----
strings = (REPO / "app/src/main/res/values/strings.xml").read_text()
for s in ('name="copying"', 'name="copy_slow_hint"'):
    if s not in strings:
        failures.append(f"strings.xml: {s} missing")

# ---------------------------------------------------------------- report
if failures:
    print(f"test_blobdl: {len(failures)} FAILURE(S)")
    for f in failures:
        print("  FAIL:", f)
    sys.exit(1)
print("test_blobdl: all assertions green "
      "(frame-dance chain wired + ordered, FrameHooks payload parses and "
      "registers per-frame, selection-copy robustness intact, bridge "
      "surface at the deliberate v1.3.58 pin)")
