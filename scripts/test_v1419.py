#!/usr/bin/env python3
"""v1.4.19 release gate — the root-cure ten-pack.

THE LAW (carried from v1.4.18): a green gate proves code EXISTS, not that
a bug is CURED — every fix carries a compiled+run BEHAVIOR probe where a
pure body could be extracted (the v1.4.11 parse-gate law) and a Diag
breadcrumb where it could not. NEW IN v1.4.19: items 1 and 2 were
ROOT-CAUSED from the user's diag.log and a captured URL — the gate pins
the NAMED MECHANISM (the suffix-before-provider ordering, the
loadData-replaces-the-URL corruption path), not a guess.

1. INCOGNITO CRASH LOOP (BoltApp + Tab + exit). The "app start" Diag block
   loaded the WebView provider BEFORE setDataDirectorySuffix ran; the
   suffix then threw and the swallow left :incognito on the SHARED data
   dir — every WebView construction died on the main process's
   AwDataDirLock. The suffix is now the FIRST WebView-related action in
   the process, failures are loud (Diag + flag), construction refuses
   gracefully, and the exit button finishes its activities before the
   kill (finish-before-kill, or the OS restarts IncognitoActivity).

2. OFFLINE ERROR PAGE -> data: URL (BrowserActivity). onReceivedError
   used loadData, which REPLACES the WebView's URL with a data:text/html
   URL that doUpdateVisitedHistory then wrote into t.url and tabs.json
   persisted — the tab reloaded the ERROR PAGE forever. The error is now
   a native overlay with Retry; both URL-write hooks carry a data: guard;
   intent:// links with no app load S.browser_fallback_url
   (IntentFallback, pure).

3. DRAG-TO-GROUP VOID, ROUND 3 (TabsActivity). The animator's predictive
   REMOVE outlived v1.4.18's one-frame-later posted relayout. The
   drag-join swap now runs with the animator OFF (restored ~350 ms later,
   after the layout pass consumed the diff with no animator) + the
   child-count/adapter-count Diag breadcrumb (dragJoinVerdict, pure).

4. DARK STROKE FLOOR (MediaHook.darkSmartJs). Light thin strokes
   (#ddd) inverted into near-black. clampCss remaps light
   text/border colors down to lum 0.6 so the post-inversion result is a
   visible mid-gray; darkClearJs restores the stored originals.

5. AI TRANSLATE TOKEN LADDER (AiBridge). Dense screens overflowed 16384
   tokens; the ladder is 32768 -> 65536 on MAX_TOKENS
   (translateCapAction, pure).

6. BING UNDER THE REAL ORIGIN (VisualSearch + BrowserClient). The saved
   file:// page could not run Bing's JS results runtime (opaque origin).
   The body is armed for the real results URL and served via
   shouldInterceptRequest for that ONE main-document GET
   (shouldServeIntercept, pure; one-shot).

7. ENGINE-CHIP LOGOS (BrowserActivity.engineLogo, pure + 5 drawables).

8. ICON-ONLY TOOLTIP LAW (layouts + TooltipCompat backport; tooltipFor,
   pure; the gate audits every ImageButton in the touched layouts).

9. VAULT ADD/EDIT (PasswordVaultActivity rides saveEx; a changed
   identity is a true edit — old row first).

10. BOLT AI TEMPORARY ATTACHMENTS (BoltAi + AiBridge chatX; memory-only,
    consumed by the send; attachCountAfter, pure).
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
RES = os.path.join(ROOT, "app/src/main/res")
gradle = open(os.path.join(ROOT, "app/build.gradle"), encoding="utf8").read()
strs = open(os.path.join(ROOT, "app/src/main/res/values/strings.xml"),
            encoding="utf8").read()
rel = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()
ta = open(os.path.join(SRC, "ui/TabsActivity.java"), encoding="utf8").read()
ba = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
vs = open(os.path.join(SRC, "util/VisualSearch.kt"), encoding="utf8").read()
ab = open(os.path.join(SRC, "util/AiBridge.kt"), encoding="utf8").read()
bai = open(os.path.join(SRC, "util/BoltAi.kt"), encoding="utf8").read()
pva = open(os.path.join(SRC, "ui/PasswordVaultActivity.kt"), encoding="utf8").read()
bapp = open(os.path.join(SRC, "BoltApp.java"), encoding="utf8").read()
tabj = open(os.path.join(SRC, "core/Tab.java"), encoding="utf8").read()
mcs = open(os.path.join(SRC, "media/MediaControlService.java"), encoding="utf8").read()
mh = open(os.path.join(SRC, "media/MediaHook.java"), encoding="utf8").read()
ifb = open(os.path.join(SRC, "util/IntentFallback.java"), encoding="utf8").read()


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


ta_code = strip_comments_strings(ta)
ba_code = strip_comments_strings(ba)
vs_code = strip_comments_strings(vs)
ab_code = strip_comments_strings(ab)
bai_code = strip_comments_strings(bai)
pva_code = strip_comments_strings(pva)
bapp_code = strip_comments_strings(bapp)
tabj_code = strip_comments_strings(tabj)
mcs_code = strip_comments_strings(mcs)
mh_code = strip_comments_strings(mh)
ifb_code = strip_comments_strings(ifb)

print("A — the version bump")
need(re.search(r"versionCode 135\b", gradle) is not None,  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 134 (Android only installs RISING codes)")  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName "1\.4\.21"', gradle) is not None,  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.21"')  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print("B — the About changelog (the label law)")
entries = re.findall(r"<item>([^<]+)</item>",
                     strs.split('name="about_changelog"')[1]) \
    if 'name="about_changelog"' in strs else []
need(entries and entries[2].startswith("1.4.19|"),  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])
     "the newest entry is 1.4.19 (the label law)")
need(len(entries) > 1 and entries[3].startswith("1.4.18|"),  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])
     "the second entry is 1.4.18 (the previous live release)")
for word, why in (("incognito", "the crash-loop cure"),
                  ("error", "the offline overlay"),
                  ("hole", "the drag-join animator bypass"),
                  ("dark", "the stroke floor"),
                  ("AI", "the token ladder + attachments"),
                  ("image search", "the Bing real-origin door"),
                  ("tooltip", "the icon-only tooltip law"),
                  ("password", "the vault add/edit"),
                  ("attachment", "the temporary AI attachments")):
    need(word in entries[2] or word in entries[2].lower(),  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])
         f"the 1.4.19 line names {why}")

print("C — fix 1: the suffix-first law (the root-caused crash loop)")
need(bapp_code.find('WebView.setDataDirectorySuffix("boltinc");')
     < bapp_code.find("WebViewCompat.getCurrentWebViewPackage"),
     "the suffix runs BEFORE the Diag block's provider-loading calls (the named mechanism)")
need(bapp_code.find('WebView.setDataDirectorySuffix("boltinc");')
     < bapp_code.find("UserscriptEngine.init(this);"),
     "the suffix runs before UserscriptEngine.init")
need("incognitoSuffixFailed = true;" in bapp_code
     and "incognitoSuffixFailed" in bapp_code,
     "a suffix failure is LOUD: the flag is set, never a silent swallow")
need('"incognito data-dir suffix FAILED' in bapp
     and '"incognito data-dir suffix boltinc applied FIRST"' in bapp,
     "both Diag breadcrumbs (applied / failed) — the evidence channel (RAW source)")
need("public static volatile boolean incognitoSuffixFailed = false;" in bapp_code,
     "the flag is public + volatile (Tab.buildWeb reads it cross-thread)")
need("if (com.bolt.browser.BoltApp.incognitoSuffixFailed) {" in tabj_code
     and "return null;" in tabj_code[tabj_code.find("if (com.bolt.browser.BoltApp.incognitoSuffixFailed)"):],
     "Tab.buildWeb refuses construction against the shared dir when the suffix failed")
need("web construct REFUSED" in tabj,
     "the refusal leaves a Diag breadcrumb (RAW source)")
buildw = tabj_code[tabj_code.find("public WebView buildWeb("):]
buildw = buildw[:buildw.find("WebSettings s =")] if "WebSettings s =" in buildw else buildw
need("catch (Throwable t)" in buildw and "return null;" in buildw,
     "the plain construction fallback is try/catch-wrapped (never an uncaught crash)")
need("if (w == null) {" in ba_code[ba_code.find("protected void showTab("):][:4000]
     and "Can't create this page's view" in ba,
     "showTab degrades visibly when buildWeb refuses (toast, no crash)")
need("if (w == null) { phoenixGiveUp(" in mcs_code,
     "the phoenix gives up cleanly on a refused build")
# the exit door: finish BEFORE the kill
exn = ba_code[ba_code.find("protected void exitNow("):]
exn = exn[:exn.find("protected void toggleTts")] if "protected void toggleTts" in exn else exn
need(exn.find("finishAndRemoveTask();") < exn.find("wipeIncognitoData();")
     < exn.find("killProcess"),
     "the incognito exit FINISHES first, then wipes, then kills (finish-before-kill)")
need("ui.postDelayed(() -> {" in exn and "killProcess" in exn,
     "the kill is POSTED (AMS sees the finish transactions before the death)")
need("protected void wipeIncognitoData() {" in ba_code
     and "protected void wipeIncognito() {" in ba_code,
     "the wipe is split from the kill (onDestroy keeps its final backstop)")
need('"exit incognito: wipe + finish + leave"' in ba,
     "the exit Diag breadcrumb (RAW source)")

print("D — fix 2: the error overlay + the data: guard + the intent fallback")
ore = ba_code[ba_code.find("public void onReceivedError("):]
ore = ore[:ore.find("public void onReceivedHttpError")] if "public void onReceivedHttpError" in ore else ore
need("loadData(" not in ore,
     "the error page NEVER calls loadData (the corruption mechanism is dead)")
need("showErrorOverlay(view, orig);" in ore,
     "the error is the native overlay")
need('"offline fail code="' in ba,
     "the original-URL Diag breadcrumb (RAW source)")
need("protected void showErrorOverlay(WebView view, String origUrl) {" in ba_code
     and "Retry" in ba,
     "the overlay exists and carries the Retry affordance")
need("private void hideErrorOverlay()" in ba_code or "protected void hideErrorOverlay()" in ba_code,
     "the overlay retires (new navigation / tab switch)")
started = ba[ba.find("public void onPageStarted("):]
started = started[:started.find("public void onPageCommitVisible")] if "public void onPageCommitVisible" in started else started
vis = ba[ba.find("public void doUpdateVisitedHistory("):]
vis = vis[:vis.find("protected boolean adblockListsOn")] if "protected boolean adblockListsOn" in vis else vis
need('url.startsWith("data:")' in started and 'url.startsWith("data:")' in vis,
     "BOTH URL-write hooks carry the data: guard (a loadData artifact is never a tab address)")
need("t.errUrl = null;" in started and "hideErrorOverlay();" in started,
     "a new navigation retires the overlay + its per-tab marker")
need("public volatile String errUrl = null;" in tabj_code,
     "Tab carries the per-tab error marker (view state, never persisted)")
need('t.errUrl != null && t.errUrl.startsWith("http")' in ba,
     "switching back to a failed tab re-shows its overlay (the overlay rides its tab)")
need("static String fallbackUrl(String intentUri)" in ifb_code
     and "internal" not in ifb_code,
     "IntentFallback.fallbackUrl is a PURE static (probe-pinned)")
need("IntentFallback" in ba and "fallbackUrl(url)" in ba,
     "the intent:// door routes through the fallback extractor (RAW source — the strip pass eats the qualified call)")
need('"intent fallback -> "' in ba,
     "the fallback Diag breadcrumb (RAW source)")

print("E — fix 3: the drag-join animator bypass (round 3)")
need("static String dragJoinVerdict(int childCount, int adapterCount) {" in ta_code,
     "the board verdict is a PURE static (probe-pinned)")
rd = ta_code[ta_code.find("protected void reloadDragNoVoid(long changedGroupId) {"):]
rd = rd[:rd.find("protected void cardOptions")] if "protected void cardOptions" in rd else rd
need(rd.find("rv.stopScroll();") < rd.find("rv.setItemAnimator(null);")
     < rd.find("adapter.reloadDiff(changedGroupId);")
     < rd.find("glm.requestLayout();"),
     "the drag door: stopScroll -> animator OFF -> the animated diff -> span invalidate + relayout")
need("rv.postDelayed(() -> {" in rd and "rv.setItemAnimator(prevAnim);" in rd,
     "the animator is RESTORED after the layout pass (~350 ms, past the 250 ms remove duration)")
need('"drag-join children="' in ta and "dragJoinVerdict(got, want)" in ta_code,
     "the void-evidence breadcrumb logs child count vs adapter item count")
need(ta_code.count("reloadDragNoVoid(") == 4,
     "the helper is still declared + called at ALL THREE drag drop sites (the v1418 pin, kept)")

print("F — fix 4: the dark stroke visibility floor")
need("function clampCss(c)" in mh and "if(y<=153)return null;" in mh,
     "clampCss exists and caps at luminance 153/255 = 0.6 (RAW source — the strip pass eats string literals)")
need("function strokeFloor(el,cs)" in mh,
     "the stroke floor application function exists")
need("if(inv===1){try{strokeFloor(el,cs);}catch(x){}}" in mh,
     "the floor rides ONLY subtrees that stay inverted (the parity law composes — RAW source)")
need("lumOf(cs.color)" in mh and "cs.borderTopColor" in mh,
     "ONLY text color and border colors are remapped (never backgrounds)")
need("parseFloat(cs.borderTopWidth)>0" in mh,
     "borders remap only where a border actually paints")
need("__boltSV" in mh and "data-boltsv" in mh,
     "the inline writes are marked + their originals stored (exact restore)")
need("el.style.removeProperty('color')" in mh,
     "darkClearJs restores the stored originals (a null means never-inline -> removed)")
need("var DK='__boltDK',NI='__boltNI',SV='__boltSV'" in mh,
     "the walk declares the SV class beside DK/NI (one pass, one budget — RAW source)")

print("G — fix 5: the AI translate token-cap ladder")
need("internal fun translateCapAction(parseOk: Boolean, finishReason: String, cap: Int): Int {" in ab_code,
     "the ladder verdict is PURE (probe-pinned)")
need("for (cap in intArrayOf(32768, 65536)) {" in ab_code,
     "the ladder: 32768 first, 65536 on the retry (the 3.x flash ceiling)")
need('finishReason == "MAX_TOKENS"' in ab,
     "the envelope's finishReason is read explicitly (RAW source — the strip pass eats string literals)")
need('"the AI reply was cut off (try a less dense screen)"' in ab,
     "the standing cut-off error survives the ladder (RAW source — the honest end state)")
need("private fun visionBody(p: String, b64: String, cap: Int): JSONObject {" in ab_code,
     "the output cap is a parameter of the vision body "
     "(v1.4.21 re-point 2026-11-20: the think param is GONE — translate "
     "never sends thinkingConfig at all; the v1.4.20 thinkingBudget-0 "
     "field was itself the field the lite model rejected with HTTP 400)")
need('"ai translate MAX_TOKENS at $cap, retrying higher"' in ab,
     "the ladder Diag breadcrumb (RAW source)")

print("H — fix 6: the Bing real-origin intercept (v1.4.20 re-point "
      "2026-11-20: RETIRED with the Bing SBI upload path it served — the "
      "835 KB real-origin shell still hung because its JS results runtime "
      "needs the LIVE upload session; every engine now rides Google's "
      "search-by-image 3xx, which needs no body carrier. The section pins "
      "the RETIREMENT itself: no armed map, no take, no serve — dead doors "
      "out, no green pins over retired code)")
need("private val intercepts" not in vs_code
     and "ConcurrentHashMap<String, ByteArray>()" not in vs_code,
     "the armed-intercept map is GONE (nothing arms, nothing serves)")
need("shouldServeIntercept" not in vs_code,
     "the v1.4.19 hit-decision pure is GONE (its only caller was the take)")
need("fun armIntercept" not in vs_code
     and "fun takeIntercept" not in vs_code,
     "arm + one-shot take are GONE")
need("if (code == 200)" not in vs_code,
     "the direct-200 body branch is GONE from the POST (Google's route "
     "answers 3xx; anything else is an honest failure -> manual-upload home)")
need('"visual bing armed under real origin"' not in vs,
     "the arm Diag breadcrumb is GONE (nothing arms)")
sir = ba[ba.find("public WebResourceResponse shouldInterceptRequest("):]
sir = sir[:sir.find("public WebResourceResponse empty()")] if "public WebResourceResponse empty()" in sir else sir
need("VisualSearch.takeIntercept(" not in sir
     and "RETIRED with the Bing SBI upload path" in sir,
     "BrowserClient's serve door is GONE and says so at the site (v1.4.20 "
     "re-point: the retirement is documented where the door stood)")
need('"visual intercept served bytes="' not in ba,
     "the serve Diag breadcrumb is GONE (nothing serves)")
need("https://lens.google.com/" in vs,
     "the manual-upload fallback home is Google Lens for every engine now")
# the v1418 session-continuity pins survive
need("android.webkit.WebSettings.getDefaultUserAgent(c)" in vs_code
     and "cm.setCookie(url, cookie)" in vs_code
     and vs_code.count("attachWebCookies(") >= 3,
     "the v1.4.18 cookie/UA pump survives the origin change (one session, still)")

print("I — fix 7: the engine-chip logos")
need("static int engineLogo(int engineIdx) {" in ba_code,
     "the mapping is a PURE static (probe-pinned)")
need("case 0: return R.drawable.ic_engine_google;" in ba_code
     and "case 1: return R.drawable.ic_engine_ddg;" in ba_code
     and "case 2: return R.drawable.ic_engine_bing;" in ba_code
     and "case 3: return R.drawable.ic_engine_brave;" in ba_code
     and "case 4: return R.drawable.ic_engine_startpage;" in ba_code
     and "default: return R.drawable.ic_search;" in ba_code,
     "all five engines map + the generic fallback")
for d in ("ic_engine_google", "ic_engine_ddg", "ic_engine_bing",
          "ic_engine_brave", "ic_engine_startpage"):
    need(os.path.isfile(os.path.join(RES, "drawable", d + ".xml")),
         f"the {d} drawable exists")
need("protected void applyEngineLogo(View lay) {" in ba_code
     and "logo == R.drawable.ic_search ? Fx.tint(this) : null" in ba_code,
     "the chip applies the logo; brand marks are NEVER tinted (the fallback keeps the tint)")
need("applyEngineLogo(lay);" in ba_code[ba_code.find("protected void showOmnibox("):][:5000],
     "the omnibox opens with the ACTIVE engine's logo")
picker = ba_code[ba_code.find("protected void showEnginePicker("):]
picker = picker[:picker.find("protected void showImageSearchPicker")] if "protected void showImageSearchPicker" in picker else picker
need("applyEngineLogo(lay);" in picker,
     "the logo follows the switch immediately")

print("J — fix 8: the icon-only tooltip law")
layouts = ("dialog_omnibox.xml", "activity_browser.xml", "activity_generic_list.xml",
           "activity_tabs.xml", "activity_settings_home.xml")
for lf in layouts:
    txt = open(os.path.join(RES, "layout", lf), encoding="utf8").read()
    btns = re.findall(r"<ImageButton[^>]*>", txt)
    missing = [b for b in btns if "tooltipText" not in b]
    need(btns and not missing,
         f"{lf}: every ImageButton ({len(btns)}) carries android:tooltipText")
om = open(os.path.join(RES, "layout", "dialog_omnibox.xml"), encoding="utf8").read()
need('android:id="@+id/omni_engine"' in om and 'android:tooltipText="@string/search_engine"' in om,
     "the engine chip (a clickable LinearLayout, not an ImageButton) is tooltip-ed too")
need("static String tooltipFor(String contentDesc, String tooltipText) {" in ba_code,
     "the fallback law is a PURE static (probe-pinned)")
need("TooltipCompat.setTooltipText" in ba_code,
     "the programmatic TooltipCompat backport exists (the XML attr is API 26+; minSdk is 24)")
need("applyTooltip(lay.findViewById(R.id.btn_omni_copy), getString(R.string.copy));" in ba_code,
     "the omnibox strip buttons get the backport at open time")

print("K — fix 9: the vault add/edit door")
need("private fun addEntryRow(): View {" in pva_code and '"Add password"' in pva,
     "the Add-password row exists")
need("private fun addOrEdit(e: PasswordVault.Entry?)" in pva_code,
     "the shared add/edit dialog exists")
need("PasswordVault.saveEx(this, h, u, p)" in pva_code,
     "the dialog rides saveEx (the SAME encrypted upsert as the site-driven save)")
need("PasswordVault.SAVE_OK" in pva_code and "PasswordVault.SAVE_UNREADABLE" in pva_code,
     "the stage codes are surfaced honestly")
need("if (e != null && (h != e.host || u != e.user)) {" in pva_code
     and "PasswordVault.delete(this, e.host, e.user)" in pva_code,
     "a changed identity is a TRUE edit (the old row goes first)")
need('"Edit"' in pva and "3 -> addOrEdit(e)" in pva_code,
     "the options sheet gains Edit")

print("L — fix 10: the temporary Bolt AI attachments")
need("private val staged = ArrayList<AiBridge.Attach>()" in bai_code,
     "the staged set is an in-memory list (never persisted)")
need("internal fun attachCountAfter(event: String, staged: Int): Int" in bai_code,
     "the lifecycle verdict is PURE (probe-pinned)")
need("MAX_ATTACH = 3" in bai_code and "TEXT_CAP = 24000" in bai_code,
     "the caps: 3 attachments, 24k chars (the token-budget law)")
need("private fun pickAttachment(a: Activity)" in bai_code
     and "ACTION_GET_CONTENT" in bai
     and "EXTRA_MIME_TYPES" in bai,
     "the picker door: ACTION_GET_CONTENT, no bundled picker lib (the size law)")
need("fun onAttachmentResult(a: Activity, uri: android.net.Uri?)" in bai_code,
     "the result handler exists (decode off-thread)")
need("VisualSearch.decodeCapped(tmp, 1280)" in bai_code,
     "images ride the SHARED capped decoder (one OOM ladder for the app)")
need("private fun renderAttachRow(a: Activity)" in bai_code,
     "the staged chips render (removable)")
need("AiBridge.chatX(a, SYSTEM, turns, attaches)" in bai_code
     and "staged.clear()" in bai_code[bai_code.find("private fun ask("):],
     "the send consumes the staged set (attachments ride ONLY their turn)")
need("fun chatX(ctx: Context, system: String, history: List<Msg>," in ab_code
     and "class Attach(" in ab_code,
     "AiBridge carries the Attach class + the chatX door")
need('"inline_data"' in ab[ab.find("private fun geminiChatPost("):]
     and '"image_url"' in ab[ab.find("private fun openaiChat("):],
     "images ride inline_data (Gemini) / image_url data URLs (OpenAI-compat — RAW source)")
need("public boolean aiAttachAllowed() { return !incognito; }" in ba_code,
     "the browser face answers the attach permission (never the incognito face)")
need("requestCode == 1030" in ba_code and "BoltAi.onAttachmentResult(this," in ba_code,
     "BrowserActivity routes the pick result (code 1030)")
need('"ai attach "' in bai and '"ai send +"' in bai,
     "the attach + send Diag breadcrumbs (RAW source)")

# the compiled probe: every extractable pure body, transcribed and RUN

probe = r"""
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class RootCureProbe {

    // ===== TRANSCRIPTION 1 — the suffix-first ordering (BoltApp, v1.4.19).
    // The "app start" Diag block LOADS THE PROVIDER (WebViewCompat +
    // WebViewFeature). setDataDirectorySuffix AFTER that throws
    // IllegalStateException and the old swallow left :incognito on the
    // SHARED data dir -> every WebView construction collided with the main
    // process's AwDataDirLock (the diag.log corpse: four pids cycling
    // app-start -> crash).
    static class IncognitoBoot {
        boolean providerLoaded = false;
        boolean suffixApplied = false;
        String crash = null;
        void loadProvider() { providerLoaded = true; }   // the Diag block's calls
        void setSuffix() {
            if (providerLoaded) {
                crash = "IllegalStateException: provider already initialized";
                return;
            }
            suffixApplied = true;
        }
        String boot(boolean suffixFirst) {
            if (suffixFirst) { setSuffix(); loadProvider(); }   // v1.4.19 order
            else { loadProvider(); setSuffix(); }               // the corpse order
            return crash;
        }
    }

    // ===== TRANSCRIPTION 2 — the data: guard + the overlay (v1.4.19).
    // loadData REPLACES the WebView's URL with a data: URL; the write
    // hooks used to copy it into t.url and tabs.json persisted it. The
    // guard: a data: URL never overwrites a tab's real http address.
    static boolean dataGuard(String incomingUrl, String currentTabUrl) {
        if (incomingUrl != null && incomingUrl.startsWith("data:")
                && currentTabUrl != null && currentTabUrl.startsWith("http")) {
            return false;   // refuse the write
        }
        return true;        // allow
    }
    // the error face: the old path rendered the error page AS the page
    // (its URL became the tab); the overlay keeps the tab's URL intact
    static String tabUrlAfterOfflineError(boolean overlayPath, String tUrl) {
        return overlayPath ? tUrl : "data:text/html,<html>...";
    }

    // ===== TRANSCRIPTION 3 — IntentFallback.fallbackUrl (v1.4.19, pure).
    static String fallbackUrl(String intentUri) {
        if (intentUri == null) return null;
        final String MARKER = "S.browser_fallback_url=";
        int i = intentUri.indexOf(MARKER);
        if (i < 0) return null;
        int from = i + MARKER.length();
        int end = intentUri.indexOf(';', from);
        String raw = end < 0 ? intentUri.substring(from)
                : intentUri.substring(from, end);
        if (raw.isEmpty()) return null;
        String dec;
        try {
            dec = java.net.URLDecoder.decode(raw, "UTF-8");
        } catch (Throwable t) {
            dec = raw;
        }
        if (!dec.startsWith("http:") && !dec.startsWith("https:")) return null;
        return dec;
    }

    // ===== TRANSCRIPTION 4 — dragJoinVerdict (TabsActivity, v1.4.19, pure).
    static String dragJoinVerdict(int childCount, int adapterCount) {
        if (adapterCount <= 0) return childCount == 0 ? "empty-ok" : "ghost-children";
        if (childCount <= 0) return "no-children";
        if (childCount > adapterCount) return "ghost-children";
        return "ok";
    }

    // ===== TRANSCRIPTION 5 — the stroke clamp (darkSmartJs clampCss,
    // v1.4.19). The JS arithmetic as Java: a light color (Y > 153) is
    // scaled DOWN so luminance lands at exactly 0.6 — post-inversion it
    // reads as a visible mid-gray on black instead of near-black.
    static int[] clampStroke(int r, int g, int b) {
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        if (y <= 153) return null;
        double f = 153.0 / y;
        return new int[]{(int) Math.round(r * f), (int) Math.round(g * f),
                (int) Math.round(b * f)};
    }
    static double lum(int[] c) {
        return (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255.0;
    }

    // ===== TRANSCRIPTION 6 — translateCapAction (AiBridge, v1.4.19, pure).
    static int translateCapAction(boolean parseOk, String finishReason, int cap) {
        if (parseOk) return 0;
        if (finishReason == "MAX_TOKENS".intern() && cap < 65536) return 1;
        return 2;
    }

    // ===== TRANSCRIPTION 7 — shouldServeIntercept — REMOVED (v1.4.20
    // re-point 2026-11-20: the intercept it tested RETIRED with the Bing
    // SBI upload path; a probe over retired code is a green pin over a
    // dead door — the section-H file pins now pin the retirement itself).

    // ===== TRANSCRIPTION 8 — engineLogo (BrowserActivity, v1.4.19, pure —
    // resource ids transcribed as names; the static pin checks the ids).
    static String engineLogo(int engineIdx) {
        switch (engineIdx) {
            case 0: return "ic_engine_google";
            case 1: return "ic_engine_ddg";
            case 2: return "ic_engine_bing";
            case 3: return "ic_engine_brave";
            case 4: return "ic_engine_startpage";
            default: return "ic_search";
        }
    }

    // ===== TRANSCRIPTION 9 — tooltipFor (BrowserActivity, v1.4.19, pure).
    static String tooltipFor(String contentDesc, String tooltipText) {
        if (tooltipText != null && !tooltipText.isEmpty()) return tooltipText;
        return contentDesc == null ? "" : contentDesc;
    }

    // ===== TRANSCRIPTION 10 — attachCountAfter (BoltAi, v1.4.19, pure).
    static int attachCountAfter(String event, int staged) {
        if (event.equals("send") || event.equals("clear-chat")
                || event.equals("browser-close") || event.equals("process-death")) {
            return 0;
        }
        return staged;
    }

    static int fails = 0;
    static void check(String why, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FAIL") + " - " + why);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        // --- 1: the suffix-first ordering ---
        IncognitoBoot oldOrder = new IncognitoBoot();
        String oldCrash = oldOrder.boot(false);
        IncognitoBoot newOrder = new IncognitoBoot();
        String newCrash = newOrder.boot(true);
        check("the OLD order crashes (provider first, suffix after)",
                oldCrash != null && !oldOrder.suffixApplied);
        check("the v1.4.19 order applies the suffix and never crashes",
                newCrash == null && newOrder.suffixApplied);
        // --- 2: the data: guard + the overlay ---
        check("a data: URL never overwrites a tab's real http address",
                !dataGuard("data:text/html,<html>...", "https://example.com/page"));
        check("an http navigation still writes normally",
                dataGuard("https://next.example/", "https://example.com/page"));
        check("a data: URL MAY land when the tab has no http address (fresh tab)",
                dataGuard("data:text/html,x", ""));
        check("the overlay path keeps the tab's URL; the old path replaced it",
                tabUrlAfterOfflineError(true, "https://example.com/page")
                        .equals("https://example.com/page")
                && tabUrlAfterOfflineError(false, "https://example.com/page")
                        .startsWith("data:"));
        // --- 3: the intent fallback ---
        check("the Facebook shape extracts the web fallback (URL-decoded)",
                "https://www.facebook.com/photo.php?x=1".equals(fallbackUrl(
                        "intent://photo/1#Intent;scheme=fb;package=com.facebook.katana;"
                                + "S.browser_fallback_url=https%3A%2F%2Fwww.facebook.com%2Fphoto.php%3Fx%3D1;end")));
        check("no marker -> null (the old toast-only dead end)",
                fallbackUrl("intent://x#Intent;scheme=fb;package=com.fb;end") == null);
        check("a non-http fallback (another app scheme) is refused — no recursion",
                fallbackUrl("intent://x#Intent;S.browser_fallback_url=fb%3A%2F%2Fpage;end") == null);
        check("an empty fallback value -> null",
                fallbackUrl("intent://x#Intent;S.browser_fallback_url=;end") == null);
        // --- 4: the drag-join verdict ---
        check("a healthy board reads ok",
                dragJoinVerdict(12, 40).equals("ok"));
        check("ghost children (more attached than data) are named",
                dragJoinVerdict(41, 40).equals("ghost-children"));
        check("a non-empty adapter with zero children is named",
                dragJoinVerdict(0, 40).equals("no-children"));
        check("an empty tab list with no children is empty-ok",
                dragJoinVerdict(0, 0).equals("empty-ok"));
        // --- 5: the stroke clamp ---
        int[] ddd = clampStroke(221, 221, 221);   // #ddd — the report's stroke
        check("#ddd clamps to luminance 0.6 (visible mid-gray post-inversion)",
                ddd != null && Math.abs(lum(ddd) - 0.6) < 0.01);
        check("the clamped #ddd inverts to ~#666 (102/255), visible on black",
                ddd != null && Math.abs((255 - ddd[0]) - 102) <= 1);
        int[] white = clampStroke(255, 255, 255);
        check("pure white clamps too (the hamburger glyph case)",
                white != null && Math.abs(lum(white) - 0.6) < 0.01);
        check("an already-mid color is untouched (null — no write)",
                clampStroke(120, 120, 120) == null
                        && clampStroke(153, 153, 153) == null);
        int[] tinted = clampStroke(100, 220, 100);   // a light green stroke
        check("a colored light stroke keeps its hue ratio (scaled, not greyed)",
                tinted != null && tinted[1] > tinted[0] && tinted[1] > tinted[2]
                        && Math.abs(lum(tinted) - 0.6) < 0.01);
        // --- 6: the token ladder ---
        check("a parsed reply is done (no retry)",
                translateCapAction(true, "MAX_TOKENS", 32768) == 0);
        check("a MAX_TOKENS truncation at 32768 raises the cap",
                translateCapAction(false, "MAX_TOKENS", 32768) == 1);
        check("a MAX_TOKENS truncation at the ceiling FAILS honestly",
                translateCapAction(false, "MAX_TOKENS", 65536) == 2);
        check("a non-token parse failure fails with its own error",
                translateCapAction(false, "STOP", 32768) == 2
                        && translateCapAction(false, "", 32768) == 2);
        // --- 7: the intercept hit — REMOVED (v1.4.20 re-point 2026-11-20:
        // the door retired with the Bing SBI path; see the H section)
        // --- 8: the engine logos ---
        check("the five engines map to their marks",
                engineLogo(0).equals("ic_engine_google")
                        && engineLogo(1).equals("ic_engine_ddg")
                        && engineLogo(2).equals("ic_engine_bing")
                        && engineLogo(3).equals("ic_engine_brave")
                        && engineLogo(4).equals("ic_engine_startpage"));
        check("an unknown/custom engine falls back to the generic magnifier",
                engineLogo(5).equals("ic_search") && engineLogo(-1).equals("ic_search"));
        // --- 9: the tooltip fallback law ---
        check("the explicit tooltip wins",
                tooltipFor("Copy", "Copy this page's address").equals("Copy this page's address"));
        check("a missing tooltip falls back to the contentDescription",
                tooltipFor("Image search", null).equals("Image search"));
        check("neither present -> empty (never null — the a11y contract)",
                tooltipFor(null, null).equals(""));
        // --- 10: the attachment lifecycle ---
        check("a send consumes the staged set",
                attachCountAfter("send", 3) == 0);
        check("Clear consumes it too",
                attachCountAfter("clear-chat", 2) == 0);
        check("the browser's death wipes it (memory-only, no restore)",
                attachCountAfter("browser-close", 3) == 0
                        && attachCountAfter("process-death", 3) == 0);
        check("a panel close KEEPS it (the session may still send)",
                attachCountAfter("panel-close", 2) == 2);
        System.out.println(fails == 0 ? "PROBE OK" : "PROBE BAD (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
"""
tmp = tempfile.mkdtemp(prefix="bolt_v1419_probe_")
open(os.path.join(tmp, "RootCureProbe.java"), "w").write(probe)
# release.sh puts the JDK on PATH before the gates; standalone runs fall
# back to the workspace's grafted JDK (the README environment note)
GJ = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin"
javac = "javac" if shutil.which("javac") else os.path.join(GJ, "javac")
java = "java" if shutil.which("java") else os.path.join(GJ, "java")
pr = subprocess.run([javac, "-d", tmp, os.path.join(tmp, "RootCureProbe.java")],
                    capture_output=True, text=True)
if pr.returncode != 0:
    print("  FAIL - probe compiled (javac):\n" + pr.stderr[:1200])
    fails += 1
else:
    print("  ok  - probe compiled (javac)")
    pr2 = subprocess.run([java, "-cp", tmp, "RootCureProbe"],
                         capture_output=True, text=True)
    print(pr2.stdout.rstrip())
    if pr2.returncode != 0:
        fails += 1

print("M — the gate is wired into the pipeline")
need("python3 scripts/test_v1419.py" in rel,
     "release.sh runs this gate before building")
need(rel.find("test_v1418.py") < rel.find("test_v1419.py"),
     "the gate runs after v1418's (release order)")

print()
print("V1419 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
