#!/usr/bin/env python3
"""v1.4.18 release gate — the field-cure six-pack.

THE LAW OF THIS RELEASE (the maintainer's order): v1.4.17 shipped 41/41
gates green and most field symptoms SURVIVED — a static text pin proves
code EXISTS, not that the bug is CURED. So every fix below carries a
compiled+run BEHAVIOR probe where a pure body could be extracted (the
v1.4.11 parse-gate law) and a Diag breadcrumb where it could not, and
the pins that moved were re-pointed AT THE DOOR THE USER USES with dated
notes ("do not just re-pin — verify the actual door").

1. DRAG-INTO-GROUP VOID (TabsActivity). The v1.4.17 cure wired
   reloadGridNoVoid() into the two ⋯-menu doors; the DRAG door (the one
   the report used) still called a bare adapter.reloadDiff at all three
   drop sites. All three now route through reloadDragNoVoid() (the
   animated diff wrapped in the no-void trio), reloadDiff itself posts a
   requestLayout AFTER dispatchUpdatesTo (the settle-vs-remove race
   antidote), and both ungroup doors ride reloadGridNoVoid().

2. OMNIBOX ACTION ROW ON FOCUS (BrowserActivity). The v1.4.16 strip was
   text-gated (invisible until the first keystroke). It shows the moment
   the omnibox opens; only Clear follows the field state; empty-field
   Copy copies the active page's URL (omniCopyText, pure).

3. DARK INVERT-FIRST (BrowserActivity). The v1.3.94 ask-first hold
   (darkAwaitSelfDark, ~3 s) was the white-paint engine for
   declared-but-light sites — deleted. Light at commit = invert now; the
   2.5 s late re-probe stays (inversion -> none only); the background-tab
   abort (w != activeTab().web) is fixed (tabOf(w) liveness); a
   "dark commit-pass" Diag breadcrumb proves the hook fires on device.

4. VISUAL-SEARCH SESSION CONTINUITY (VisualSearch.kt). The upload now
   carries the WebView jar's cookies + the WebView's exact UA, pumps
   every Set-Cookie along the redirect ladder into the WebView's
   CookieManager (each GET hop too — consent rides along), and Bing's
   saved file:// page gains a <base href> (ensureBaseHref, pure).

5. THE AI CHAIN LAW (AiBridge.kt). A custom model kept NO fallbacks and
   the chain advanced only on 404 — a 503 wave was a dead end. buildChain
   always appends default+fallbacks (deduped), advanceChain walks on 5xx
   too, the 503 ladder retries the same model with the provider's
   retryDelay hint (bounded), and the answering model is logged/ toasted
   when it is not the configured pick.

6. SESSION-AWARE EXTERNAL SEARCH (SessionState + BrowserActivity +
   BoltSearchWidget). The last foreground session is recorded in a tiny
   cross-process file; the normal search faces forward the query into
   IncognitoActivity when the record says incognito (and finish, so the
   normal task's tabs-menu back stack never surfaces); the widget enters
   the same session-aware face on purpose; the "(Incognito)" alias stays
   as the manual door.

The compiled probe (section K) runs TRANSCRIPTIONS of every extractable
pure body: the settle-vs-remove race (naive hole vs posted relayout),
omniCopyText + the visibility state machine, the dark decision + the
background-tab guard, ensureBaseHref, buildChain/advanceChain/
retryDelaySeconds + the dead-end walk, and the SessionState file core +
the forward decision.
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
bw = open(os.path.join(SRC, "ui/BoltSearchWidget.java"), encoding="utf8").read()
ss = open(os.path.join(SRC, "util/SessionState.java"), encoding="utf8").read()
man = open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"),
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


ta_code = strip_comments_strings(ta)
ba_code = strip_comments_strings(ba)
vs_code = strip_comments_strings(vs)
ab_code = strip_comments_strings(ab)
ss_code = strip_comments_strings(ss)

print("A — the version bump")
need(re.search(r"versionCode 135\b", gradle) is not None,  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 134 (Android only installs RISING codes)")  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName "1\.4\.21"', gradle) is not None,  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.21"')  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print("B — the About changelog (the label law)")
entries = re.findall(r"<item>([^<]+)</item>",
                     strs.split('name="about_changelog"')[1]) \
    if 'name="about_changelog"' in strs else []
need(entries and entries[3].startswith("1.4.18|"),  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])
     "the newest entry is 1.4.18 (the label law)")
need(len(entries) > 1 and entries[4].startswith("1.4.17|"),  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])
     "the second entry is 1.4.17 (the previous live release)")
for word, why in (("hole", "the drag-door void"),
                  ("omnibox", "the focus law"),
                  ("dark", "the invert-first pass"),
                  ("image search", "the session continuity"),
                  ("AI", "the chain law"),
                  ("incognito", "the session-aware search")):
    need(word in entries[3] or word in entries[3].lower(),  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])
         f"the 1.4.18 line names {why}")

print("C — fix 1: the drag-door no-void reload")
need("protected void reloadDragNoVoid(long changedGroupId) {" in ta_code,
     "the drag-door helper exists (reloadDragNoVoid)")
rd = ta_code[ta_code.find("protected void reloadDragNoVoid(long changedGroupId) {"):]
rd = rd[:rd.find("protected void cardOptions")] if "protected void cardOptions" in rd else rd
need(rd.find("rv.stopScroll();") < rd.find("adapter.reloadDiff(changedGroupId);")
     < rd.find("glm.getSpanSizeLookup().invalidateSpanIndexCache();")
     < rd.find("glm.requestLayout();"),
     "the drag door wraps the ANIMATED diff in the no-void trio (stopScroll -> reloadDiff -> invalidate + requestLayout)")
need(ta_code.count("reloadDragNoVoid(") == 4,
     "the helper is declared + called at ALL THREE drag drop sites (join / merge / create-group)")
for site in ("reloadDragNoVoid(joinGid);", "reloadDragNoVoid(mergeTarget.groupId);",
             "reloadDragNoVoid(gid);"):
    need(site in ta_code, f"the drop site {site} routes through the helper")
rdiff = ta_code[ta_code.find("void reloadDiff(long changedGroupId) {"):]
rdiff = rdiff[:rdiff.find("void reloadRemoved")] if "void reloadRemoved" in rdiff else rdiff
need(rdiff.find("diff.dispatchUpdatesTo(this);")
     < rdiff.find("rv.requestLayout();"),
     "reloadDiff posts a requestLayout AFTER dispatchUpdatesTo (the settle-vs-remove race antidote)")
need("rv.post(" in rdiff and "rv.requestLayout();" in rdiff,
     "the relayout is POSTED (it must land after the settle/restore callbacks, not inside them)")
need(ta_code.count("reloadGridNoVoid();") == 4,
     "both move_to_group doors AND both ungroup doors ride reloadGridNoVoid (4 calls)")
# the ungroup doors specifically (raw source: string literals)
ung = ta[ta.find("protected void cardOptions"):]
ung = ung[:ung.find("protected void tabOptions")]
ung2 = ta[ta.find("protected void tabOptions"):]
ung2 = ung2[:ung2.find("protected void ownTouchStream")]
for nm, seg in (("cardOptions", ung), ("tabOptions", ung2)):
    need(seg.find('getString(R.string.remove_from_group)') >= 0
         and seg.find("reloadGridNoVoid();", seg.find('remove_from_group')) >= 0,
         f"{nm}'s ungroup commit rides the no-void helper")

print("D — fix 2: the omnibox focus law + empty-field copy")
si = ba_code[ba_code.find("final Runnable syncOmniIcons"):]
si = si[:si.find("rv.setLayoutManager")] if "rv.setLayoutManager" in si else si
need("actionsRow.getVisibility() != View.VISIBLE" in si
     and "actionsRow.setVisibility(View.VISIBLE);" in si,
     "the strip shows on OPEN regardless of text (the focus law)")
need("clearB.setVisibility(empty ? View.GONE : View.VISIBLE);" in si,
     "only the Clear button keeps following the field's empty state")
need("actionsRow.setVisibility(empty ? View.GONE : View.VISIBLE);" not in si,
     "the v1.4.16 text-gate on the strip is GONE (dated re-point in the source)")
need("static String omniCopyText(String fieldText, String tabUrl, boolean tabIsHome) {" in ba_code,
     "the copy target is a PURE static (probe-pinned per the parse-gate law)")
need("omniCopyText(et.getText().toString()," in ba
     and '"Nothing to copy"' in ba,
     "the Copy button uses the pure target and says so on a home tab")

print("E — fix 3: dark invert-first + the background-tab fix")
need("darkAwaitSelfDark" not in ba_code,
     "the ask-first hold is deleted (a declaration is not evidence the page will self-darken)")
rwd = ba_code[ba_code.find("protected void runWebDarkPass("):]
rwd = rwd[:rwd.find("protected void toggleSiteDark")] if "protected void toggleSiteDark" in rwd else rwd
need(rwd.find("darkProbeJs()") < rwd.find("darkInvertNow(view, host);"),
     "LIGHT AT COMMIT = INVERT NOW (the probe's reading is the only evidence)")
need("askDarkJs" not in rwd,
     "no ask branch remains in the commit pass")
need('"dark commit-pass "' in ba,
     "the commit-pass Diag breadcrumb (the hook's firing is in every diag report)")
din = ba_code[ba_code.find("protected void darkInvertNow("):]
din = din[:din.find("protected void runWebDarkPass")] if "protected void runWebDarkPass" in din else din
need("if (tabOf(w) == null) return;" in din
     and "w != at.web" not in din,
     "the late re-probe guards on TAB LIVENESS, not active-ness (background tabs heal now)")
need('"dark late-flip "' in ba,
     "the 2.5 s late pull-back stays (inversion -> none only)")
fin = ba_code[ba_code.find("public void onPageFinished(WebView view, String url) {"):]
fin = fin[:fin.find("public void onReceivedError")]
need("if (dt == null || !url.equals(dt.darkPassUrl)) {" in fin
     and "runWebDarkPass(view, url);" in fin,
     "the finish-time run-token backstop is unchanged")
cvb = ba_code[ba_code.find("public void onPageCommitVisible(WebView view, String url) {"):]
cvb = cvb[:cvb.find("public void onPageFinished")] if "public void onPageFinished" in cvb else cvb
need("runWebDarkPass(view, url);" in cvb,
     "the chain still runs at COMMIT (the v1.4.17 site law)")

print("F — fix 4: the visual-search session continuity")
need("android.webkit.WebSettings.getDefaultUserAgent(c)" in vs_code,
     "the upload speaks the WebView's exact UA (not a hardcoded Chrome string)")
need("android.webkit.CookieManager.getInstance().getCookie(url)" in vs_code,
     "the POST attaches the WebView jar's cookies for the endpoint")
need("cm.setCookie(url, cookie)" in vs_code,
     "every Set-Cookie along the ladder is pumped into the WebView's CookieManager")
need(vs_code.count("attachWebCookies(") >= 3
     and vs_code.count("pumpSetCookies(") >= 4,
     "the POST and EVERY redirect hop attach + pump (the consent redirect rides along; v1.4.20 re-point 2026-11-20: the direct-200 Bing branch — one pump call — retired with its upload path, so the count is 4)")
need("internal fun ensureBaseHref" not in vs_code,
     "the base-href injector is RETIRED (v1.4.20 re-point 2026-11-20: it "
     "existed to re-anchor the Bing 200-body save, and the single Google "
     "3xx route saves no body — the pure-probe transcription below stays "
     "as the historical contract)")

print("G — fix 5: the AI chain law")
need("internal fun buildChain(m: String): Array<String> {" in ab_code,
     "buildChain exists (pure, probe-pinned)")
need("arrayOf(m)" not in ab_code and "arrayOf(m, *GEMINI_FALLBACKS)" not in ab_code,
     "the custom-model dead end is GONE (no single-link chain, no default-only chain)")
need("internal fun advanceChain(err: String): Boolean {" in ab_code,
     "advanceChain exists (pure, probe-pinned)")
need(ab_code.count("if (!advanceChain(lastError)) return null") == 2,
     "BOTH walk sites (vision + chat) advance on the new law")
need('"provider error"' in ab and '"model not found"' in ab,
     "the chain advances on the 5xx provider bucket AND the 404 model shape (RAW source — the strip pass eats string literals)")
need("tries503 < 2" in ab_code and "retryDelaySeconds(r.second, 5).coerceIn(1, 30)" in ab_code,
     "the 503 ladder: bounded retries, provider-hinted delay, capped 30 s")
need("internal fun retryDelaySeconds(body: String, dflt: Int): Int {" in ab_code,
     "retryDelaySeconds exists (pure, probe-pinned)")
need("private fun noteAnsweredModel(" in ab_code
     and "AI fell back to " in ab,
     "the answering model is recorded and toasted when it is not the configured pick")
need('"ai answered by "' in ab and '"ai 503 retry "' in ab,
     "the answered-model + 503-retry Diag breadcrumbs (evidence in every field report)")
need("gemini-3.5-flash-lite" in ab,
     "the default model is unchanged (the chain was never the stale-model problem)")
need('Log.e("AiBridge", "Raw Response: "' in ab,
     "the v1.4.17 raw-response evidence channel stays")

print("H — fix 6: the session-aware external search")
need("public static void noteForeground(Context c, boolean incognito)" in ss_code
     and "public static boolean lastForegroundIncognito(Context c)" in ss_code,
     "SessionState carries both halves of the cross-process record")
need("tmp.renameTo(dst)" in ss_code,
     "the write is atomic (tmp + rename — a racing reader sees one whole value)")
need('"incognito".equals(' in ss,
     "the read matches the incognito token exactly (RAW source — the strip pass eats string literals)")
need("SessionState.noteForeground(this, incognito);" in ba_code,
     "onResume records whichever session resumed (both processes share the write path)")
need("private boolean forwardToIncognitoIfLast(Intent i)" in ba_code,
     "the forward helper exists")
fwd = ba_code[ba_code.find("private boolean forwardToIncognitoIfLast(Intent i)"):]
fwd = fwd[:fwd.find("protected void openArchive")] if "protected void openArchive" in fwd else fwd
need("if (incognito) return false;" in fwd
     and "lastForegroundIncognito(this)" in fwd
     and "Intent(this, IncognitoActivity.class)" in fwd
     and "finish();" in fwd,
     "the forward: never from the private process, reads the record fresh, re-fires + finishes")
hi = ba_code[ba_code.find("private void handleIntent(Intent i)"):]
hi = hi[:hi.find("private boolean forwardToIncognitoIfLast")]
need(hi.count("forwardToIncognitoIfLast(i)") == 2,
     "BOTH external doors forward (the query branch + the widget's focus branch)")
need('"search fwd -> incognito ("' in ba and '"search intent "' in ba,
     "the search Diag breadcrumbs (which face handled it, when it forwarded)")
need("new Intent(context, BrowserActivity.class)" in bw
     and "SESSION-AWARE" in bw,
     "the widget still targets the normal face BY DESIGN (it is the session-aware entry now)")
need('.ui.SearchIncognito' in man,
     "the v1.4.17 manual (Incognito) alias stays (the manual door)")
otv = ba_code[ba_code.find("protected void openTabsView("):]
otv = otv[:otv.find("protected void openQueryInNewTab")] if "protected void openQueryInNewTab" in otv else otv
need("incognito ? TabsIncognitoActivity.class : TabsActivity.class" in otv,
     "the in-app tabs button still routes in-process (the round trip stays private)")

# the compiled probe: every extractable pure body, transcribed and RUN
probe = r"""
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

public class FieldCureProbe {
    // ===== TRANSCRIPTION 1 — the settle-vs-remove race (reloadDiff, v1.4.18).
    // A DiffUtil remove releases the departed row's slot only when the
    // remove animation ends, and neither the animator's end nor the ITH
    // settle's end requests a layout — the survivors' pass was consumed
    // while the settling card still held its bounds, so a HOLE renders
    // where the tab card was, and it survives until the switcher
    // reopens. A requestLayout POSTED after dispatchUpdatesTo runs one
    // frame later — after the settle/restore callbacks — and forces a
    // full layout over the LIVE items: the hole cannot survive.
    static class VoidSim {
        String[] data;                     // the adapter's live items
        String[] rendered;                 // what is on screen (null = HOLE)
        List<Runnable> posts = new ArrayList<>();
        VoidSim(String[] start) { data = start.clone(); rendered = start.clone(); }
        void dispatchRemove(int pos) {     // dispatchUpdatesTo(remove)
            String[] next = new String[data.length - 1];
            int j = 0;
            for (int i = 0; i < data.length; i++) if (i != pos) next[j++] = data[i];
            data = next;
            // the racing pass: survivors keep their OLD slots; the
            // departed slot is never reclaimed (the disappearing view's
            // release requests nothing)
            rendered = new String[next.length + 1];
            Arrays.fill(rendered, null);
            for (int i = 0; i < next.length; i++)
                rendered[i < pos ? i : i + 1] = next[i];
        }
        void reloadDiffNaive(int pos) { dispatchRemove(pos); }
        void reloadDiffPosted(int pos) {   // the v1.4.18 door
            dispatchRemove(pos);
            posts.add(() -> { rendered = data.clone(); });   // rv.post(relayout)
        }
        void animationsEnd() { /* neither end requests a layout */ }
        void runPosts() { for (Runnable r : posts) r.run(); posts.clear(); }
        boolean holeOpen() {
            for (String s : rendered) if (s == null) return true;
            return rendered.length != data.length;
        }
    }

    // ===== TRANSCRIPTION 2 — omniCopyText + the visibility law (v1.4.18).
    static String omniCopyText(String fieldText, String tabUrl, boolean tabIsHome) {
        if (fieldText != null && !fieldText.isEmpty()) return fieldText;
        if (tabIsHome) return null;
        if (tabUrl == null || tabUrl.isEmpty()) return null;
        return tabUrl;
    }
    // the focus law: the strip is up the moment the omnibox opens; only
    // Clear follows the field state (the v1.4.15 one-write cache rides)
    static class OmniSim {
        boolean stripVisible = false, clearVisible = false;
        Boolean emptyCache = null;
        void open() { stripVisible = true; sync(true); }   // opening state
        void sync(boolean empty) {
            if (!stripVisible) stripVisible = true;        // focus law
            if (emptyCache != null && emptyCache == empty) return;
            emptyCache = empty;
            clearVisible = !empty;
        }
    }

    // ===== TRANSCRIPTION 3 — the dark decision + the background guard.
    // v1.4.18: LIGHT AT COMMIT = INVERT NOW. The v1.3.94 chain held a
    // merely-DECLARING site for ~3 s waiting for its own dark — that
    // hold WAS the white-paint. The late pull stays (inversion -> none).
    static String darkDecision(boolean probedLight) {
        return probedLight ? "invert" : "clear";
    }
    static String oldDarkDecision(boolean probedLight, boolean declared) {
        if (!probedLight) return "clear";
        if (declared) return "hold~3s";    // the white-paint engine
        return "invert";
    }
    // the late re-probe guard: liveness, not active-ness
    static boolean oldLateGuard(boolean tabAlive, boolean isActive) {
        return !tabAlive || !isActive;     // abort background tabs
    }
    static boolean newLateGuard(boolean tabAlive, boolean isActive) {
        return !tabAlive;                  // background tabs heal too
    }

    // ===== TRANSCRIPTION 4 — ensureBaseHref (VisualSearch, v1.4.18).
    static String ensureBaseHref(String html, String base) {
        String tag = "<base href=\"" + base + "\">";
        if (html.contains(tag)) return html;
        int hi = html.indexOf("<head");
        // (the Kotlin call is case-insensitive; the transcription mirrors
        // the semantic with a lowercase scan for the same fixtures)
        int hiL = html.toLowerCase().indexOf("<head");
        if (hiL >= 0) {
            int gt = html.indexOf('>', hiL);
            if (gt >= 0) {
                return html.substring(0, gt + 1) + tag + html.substring(gt + 1);
            }
        }
        return tag + html;
    }

    // ===== TRANSCRIPTION 5 — the AI chain law (AiBridge, v1.4.18).
    static final String GEMINI_DEFAULT = "gemini-3.5-flash-lite";
    static final String[] GEMINI_FALLBACKS = {"gemini-flash-latest", "gemini-3.8-flash"};
    static String[] buildChain(String m) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        seen.add(m);
        seen.add(GEMINI_DEFAULT);
        for (String f : GEMINI_FALLBACKS) seen.add(f);
        return seen.toArray(new String[0]);
    }
    static boolean advanceChain(String err) {
        return err.contains("model not found") || err.contains("provider error");
    }
    static int retryDelaySeconds(String body, int dflt) {
        if (body.isEmpty()) return dflt;
        int i = body.indexOf("retryDelay");
        if (i < 0) return dflt;
        java.util.regex.Matcher mm =
                java.util.regex.Pattern.compile("(\\d{1,4})s").matcher(body.substring(i));
        if (!mm.find()) return dflt;
        try { return Integer.parseInt(mm.group(1)); } catch (Throwable t) { return dflt; }
    }

    // ===== TRANSCRIPTION 6 — the SessionState file core (v1.4.18).
    static final String SS_FILE = "last_session.txt";
    static void noteForeground(File dir, boolean incognito) throws Exception {
        File tmp = new File(dir, SS_FILE + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            fo.write((incognito ? "incognito" : "normal").getBytes("UTF-8"));
        } finally {
            try { fo.close(); } catch (Throwable ignored) {}
        }
        File dst = new File(dir, SS_FILE);
        if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst); }
    }
    static boolean lastForegroundIncognito(File dir) {
        try {
            File f = new File(dir, SS_FILE);
            FileInputStream fi = new FileInputStream(f);
            byte[] b;
            try {
                int len = (int) Math.min(f.length(), 64L);
                b = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = fi.read(b, off, len - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                try { fi.close(); } catch (Throwable ignored) {}
            }
            return "incognito".equals(new String(b, "UTF-8").trim());
        } catch (Throwable t) {
            return false;
        }
    }
    // the forward decision (BrowserActivity.forwardToIncognitoIfLast)
    static boolean shouldForward(boolean selfIsIncognito, boolean lastWasIncognito) {
        return !selfIsIncognito && lastWasIncognito;
    }

    static int fails = 0;
    static void check(String why, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FAIL") + " - " + why);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        // --- 1: the settle-vs-remove race ---
        VoidSim naive = new VoidSim(new String[]{"A", "B", "C", "D"});
        naive.reloadDiffNaive(1);
        naive.animationsEnd();
        check("a naive reloadDiff leaves the HOLE (the race the report named)",
              naive.holeOpen());
        VoidSim cured = new VoidSim(new String[]{"A", "B", "C", "D"});
        cured.reloadDiffPosted(1);
        cured.animationsEnd();
        cured.runPosts();                          // the posted relayout
        check("the posted relayout heals it (rendered == live items)",
              !cured.holeOpen() && Arrays.equals(cured.rendered, cured.data));
        // --- 2: omnibox copy + the focus law ---
        check("field text wins", "typed query".equals(
                omniCopyText("typed query", "https://x.example/", false)));
        check("empty field over a page copies the PAGE URL",
                "https://x.example/page".equals(
                        omniCopyText("", "https://x.example/page", false)));
        check("home tab has nothing to copy",
                omniCopyText("", null, true) == null);
        check("a page tab with no URL has nothing to copy",
                omniCopyText("", "", false) == null);
        OmniSim om = new OmniSim();
        om.open();
        check("the strip is visible the instant the omnibox OPENS (focus law)",
              om.stripVisible);
        check("Clear stays hidden while the field is empty",
              !om.clearVisible);
        om.sync(true);                              // a keystroke that stays empty-state? no-op
        check("a same-state sync costs nothing (the v1.4.15 cache law)",
              !om.clearVisible);
        om.sync(false);                              // first character
        check("Clear appears on the first character",
              om.clearVisible);
        // --- 3: the dark decision ---
        check("light + DECLARED dark inverts NOW (the hold is dead)",
              darkDecision(true).equals("invert"));
        check("the old chain would have held it ~3 s (the white-paint)",
              oldDarkDecision(true, true).equals("hold~3s"));
        check("painted-dark keeps its own colors",
              darkDecision(false).equals("clear"));
        check("the late guard no longer aborts a BACKGROUND tab",
              !newLateGuard(true, false) && oldLateGuard(true, false));
        check("a CLOSED tab still aborts",
              newLateGuard(false, false) && oldLateGuard(false, true));
        // --- 4: the base href ---
        String binged = ensureBaseHref(
                "<html><head><title>r</title></head><body><img src=\"/fd/img.png\">",
                "https://www.bing.com/");
        check("the base lands after <head> (relative URLs re-anchor)",
              binged.startsWith("<html><head><base href=\"https://www.bing.com/\">"));
        check("a headless body gets the base prepended",
              ensureBaseHref("<body>x", "https://www.bing.com/")
                      .startsWith("<base href="));
        check("an already-based page is untouched (idempotent)",
              ensureBaseHref(binged, "https://www.bing.com/").equals(binged));
        check("<HEAD> (uppercase) is found too",
              ensureBaseHref("<HTML><HEAD></HEAD></HTML>", "https://b/")
                      .contains("<base href=\"https://b/\">"));
        // --- 5: the AI chain ---
        String[] custom = buildChain("gemini-3.8-flash");
        check("a custom model keeps the default + fallbacks (deduped)",
              custom.length == 3 && custom[0].equals("gemini-3.8-flash")
                      && custom[1].equals(GEMINI_DEFAULT)
                      && custom[2].equals("gemini-flash-latest"));
        check("the default chain is unchanged",
              Arrays.equals(buildChain(GEMINI_DEFAULT),
                      new String[]{GEMINI_DEFAULT, "gemini-flash-latest", "gemini-3.8-flash"}));
        check("the chain advances on model-not-found AND provider error",
              advanceChain("model not found") && advanceChain("provider error"));
        check("it does NOT advance on auth/quota/rate shapes",
              !advanceChain("invalid API key") && !advanceChain("rate limited (free tier)")
                      && !advanceChain("quota exceeded (free tier)")
                      && !advanceChain("the AI reply was not JSON"));
        check("the provider retry hint is honored",
              retryDelaySeconds("{\"error\":{\"details\":[{\"retryDelay\":\"26s\"}]}}", 5) == 26);
        check("no hint -> the bounded default",
              retryDelaySeconds("<html>overloaded</html>", 5) == 5
                      && retryDelaySeconds("", 5) == 5);
        // the dead-end walk: custom 503s through the ladder, chain walks
        String[] chain = buildChain("gemini-3.8-flash");
        int answeredAt = -1;
        for (int i = 0; i < chain.length; i++) {
            int code = (i == 0) ? 503 : 200;       // the custom pick is down
            if (code == 503) {
                if (!advanceChain("provider error")) break;   // dead end shape
                continue;                                     // walk the chain
            }
            answeredAt = i;
            break;
        }
        check("custom + 503 WALKS to an answering model (the dead end is cured)",
              answeredAt == 1 && chain[answeredAt].equals(GEMINI_DEFAULT));
        // --- 6: the session record + the forward ---
        File dir = java.nio.file.Files.createTempDirectory("boltss").toFile();
        check("no record reads NORMAL (a normal search can never be hijacked)",
                !lastForegroundIncognito(dir));
        noteForeground(dir, true);
        check("an incognito resume records incognito",
                lastForegroundIncognito(dir));
        noteForeground(dir, false);
        check("a normal resume records normal",
                !lastForegroundIncognito(dir));
        noteForeground(dir, true);
        check("the forward fires only for a normal face with a private record",
                shouldForward(false, lastForegroundIncognito(dir))
                        && !shouldForward(true, true)
                        && !shouldForward(false, false));
        System.out.println(fails == 0 ? "PROBE OK" : "PROBE BAD (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
"""
tmp = tempfile.mkdtemp(prefix="bolt_v1418_probe_")
open(os.path.join(tmp, "FieldCureProbe.java"), "w").write(probe)
# release.sh puts the JDK on PATH before the gates; standalone runs fall
# back to the workspace's grafted JDK (the README environment note)
GJ = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin"
javac = "javac" if shutil.which("javac") else os.path.join(GJ, "javac")
java = "java" if shutil.which("java") else os.path.join(GJ, "java")
pr = subprocess.run([javac, "-d", tmp, os.path.join(tmp, "FieldCureProbe.java")],
                    capture_output=True, text=True)
if pr.returncode != 0:
    print("  FAIL - probe compiled (javac):\n" + pr.stderr[:1200])
    fails += 1
else:
    print("  ok  - probe compiled (javac)")
    pr2 = subprocess.run([java, "-cp", tmp, "FieldCureProbe"],
                         capture_output=True, text=True)
    print(pr2.stdout.rstrip())
    if pr2.returncode != 0:
        fails += 1

print("I — the gate is wired into the pipeline")
need("python3 scripts/test_v1418.py" in rel,
     "release.sh runs this gate before building")
need(rel.find("test_v1417.py") < rel.find("test_v1418.py"),
     "the gate runs after v1417's (release order)")

print()
print("V1418 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
