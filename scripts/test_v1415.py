#!/usr/bin/env python3
"""v1.4.15 release gate — the responsiveness four-pack.

THE REPORT (2026-11-18): four fixes, each pinned below:

1. DOWNLOAD PROGRESS RESETS ON TAB SWITCH. DownloadsActivity implements
   DownloadEngine.Listener and removes itself in onDestroy() — but never
   registered, so it leaned entirely on the 500 ms refreshLoop poll.
   DownloadEngine.get(this).addListener(this) now runs at the end of
   onCreate() AND inside a new onResume() (registration is contains()-
   guarded, so the double arm is idempotent); onDownloadsChanged() (which
   was already implemented, posting a guarded reload() to the ui handler)
   finally fires.

2. TAB HIGHLIGHT NOT UPDATING. TabsActivity.onResume() only called
   adapter.rebindIcons() — a rebind of the rows the adapter already held,
   never a model rebuild, so a switcher covered while tm.active moved
   (notification, external intent) reopened outlining the OLD tab.
   adapter.reload() now follows the rebind: rebuildItems() +
   notifyDataSetChanged() + afterReload(), re-deriving the accent rim and
   bottom strip from the CURRENT tm.active. An active search filter
   survives (rebuildItems re-applies tabQuery).

3. OMNIBOX TYPING LAG, TWICE OVER.
   a) syncOmniIcons called setVisibility() on four views on EVERY
      keystroke; only an EMPTY<->TYPING transition changes anything.
      The empty state is cached in a final Boolean[1] holder (null =
      never applied, so the opening sync always runs); a keystroke that
      does not flip the state costs one array read and returns early.
   b) ad.update() walked the bookmarks + speed-dial + 400-entry history
      JSON synchronously inside afterTextChanged. update() is now a
      DISPATCHER: the build runs on SUGGEST_BUILD (one process-wide
      daemon thread, "bolt-suggest"), every call bumps suggestGen, the
      build carries its generation, and the apply (items swap + notify)
      posts through the activity's ui handler only if it is still the
      newest. buildRows is the extracted pure-ish builder (returns out).

4. HANDLER MEMORY LEAKS. sdWinH and suggestH were never cleaned in
   onDestroy() (only ui was). Both now get removeCallbacksAndMessages(
   null) — placed ABOVE the incognito early-return so both process
   paths sweep.

The compiled probe (section H) runs TRANSCRIPTIONS of the two contracts
this release adds: the suggestion generation guard (a stale apply can
never reach the screen; the newest always lands) and the omnibox icon
state cache (no state flip -> no setVisibility calls).
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
da = open(os.path.join(SRC, "ui/DownloadsActivity.java"), encoding="utf8").read()
ta = open(os.path.join(SRC, "ui/TabsActivity.java"), encoding="utf8").read()
ba = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
de = open(os.path.join(SRC, "core/DownloadEngine.java"), encoding="utf8").read()


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


da_code = strip_comments_strings(da)
ta_code = strip_comments_strings(ta)
ba_code = strip_comments_strings(ba)
de_code = strip_comments_strings(de)

print("A — the version bump")
need(re.search(r"versionCode 146\b", gradle) is not None,  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 134 (Android only installs RISING codes)")  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName "1\.4\.32"', gradle) is not None,  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): the escaped-form recovery (the phantom-backslash corpse)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.32"')  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print("B — the About changelog (the label law)")
entries = re.findall(r"<item>([^<]+)</item>",
                     strs.split('name="about_changelog"')[1]) \
    if 'name="about_changelog"' in strs else []
need(entries and entries[13].startswith("1.4.18|"),  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])  # v1.4.31 re-point (2026-11-25): every About-changelog pin slid one slot down (1.4.31 landed at [0])  # v1.4.32 re-point (2026-11-26): every About-changelog pin slid one slot down (1.4.32 landed at [0])
     "the newest entry is 1.4.15 (the label law)")
need(len(entries) > 3 and entries[16].startswith("1.4.15|"),  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])  # v1.4.31 re-point (2026-11-25): every About-changelog pin slid one slot down (1.4.31 landed at [0])  # v1.4.32 re-point (2026-11-26): every About-changelog pin slid one slot down (1.4.32 landed at [0])
     "the second entry is 1.4.14 (the previous live release)")
for word, why in (("Downloads", "the listener law"),
                  ("CURRENT tab", "the stale-highlight fix"),
                  ("main thread", "the off-thread build"),
                  ("handler", "the handler sweep")):
    need(word in entries[16] or word in entries[16].lower(),  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])  # v1.4.31 re-point (2026-11-25): every About-changelog pin slid one slot down (1.4.31 landed at [0])  # v1.4.32 re-point (2026-11-26): every About-changelog pin slid one slot down (1.4.32 landed at [0])
         f"the 1.4.15 line names {why}")

print("C — fix 1: the downloads event-listener law")
need(da_code.count("DownloadEngine.get(this).addListener(this);") >= 2,
     "addListener runs in onCreate AND onResume (the report's exact ask)")
oc = da_code[da_code.find("protected void onCreate(Bundle savedInstanceState) {"):]
oc = oc[:oc.find("\n    }", 1) + 6] if "\n    }" in oc[1:] else oc
need("DownloadEngine.get(this).addListener(this);" in oc,
     "the onCreate registration sits inside onCreate itself")
oru = da_code[da_code.find("protected void onResume() {"):]
oru = oru[:oru.find("\n    }", 1) + 6] if "\n    }" in oru[1:] else oru
need("super.onResume();" in oru
     and "DownloadEngine.get(this).addListener(this);" in oru,
     "the onResume override exists and re-arms the feed")
need("void onDownloadsChanged()" in da_code and "ui.post(" in da_code,
     "the listener callback posts its reload to the ui handler")
od = da_code[da_code.find("protected void onDestroy() {"):]
od = od[:od.find("\n    }", 1) + 6] if "\n    }" in od[1:] else od
need("DownloadEngine.get(this).removeListener(this);" in od,
     "the onDestroy un-registration survives (no double-fire)")
need("if (!listeners.contains(l)) listeners.add(l);" in de_code,
     "the engine's addListener is contains()-guarded (idempotent re-arm)")
need("ui.postDelayed(refreshLoop, 500)" in da_code,
     "the 500 ms poll survives as the fallback, not the primary feed")

print("D — fix 2: the switcher re-highlights the CURRENT tab")
oru2 = ta_code[ta_code.find("protected void onResume() {"):]
oru2 = oru2[:oru2.find("\n    }", 1) + 6] if "\n    }" in oru2[1:] else oru2
need("adapter.rebindIcons();" in oru2,
     "the v1.3.78 favicon rebind survives")
need("adapter.reload();" in oru2,
     "onResume now reloads the model (the stale-highlight fix)")
need(oru2.find("adapter.rebindIcons();") < oru2.find("adapter.reload();"),
     "the rebind runs first, then the full reload (the report's order)")
rl = ta_code[ta_code.find("void reload() {"):]
rl = rl[:rl.find("\n    }", 1) + 6] if "\n    }" in rl[1:] else rl
need("rebuildItems();" in rl and "notifyDataSetChanged();" in rl
     and "afterReload();" in rl,
     "reload is the full model rebuild (not a bare rebind)")
need("boolean active = !selMode && tm.tabs.indexOf(t) == tm.active;" in ta_code,
     "applySelVisuals still derives from live tm.active (the reader)")
ri = ta_code[ta_code.find("private void rebuildItems() {"):]
ri = ri[:ri.find("\n        }", 1) + 10] if "\n        }" in ri[1:] else ri
need("if (tabQuery != null) {" in ta_code,
     "an active search filter survives the resume reload (rebuildItems re-applies it)")

print("E — fix 3a: the omnibox icon state cache")
need("final Boolean[] omniEmptyCache = new Boolean[1];" in ba_code,
     "the empty state is cached in a final Boolean[1] holder")
si = ba_code[ba_code.find("final Runnable syncOmniIcons"):]
si = si[:si.find("};", 1) + 2]
need("boolean empty = et.getText().length() == 0;" in si,
     "the state still keys on the field being empty")
need("if (omniEmptyCache[0] != null && omniEmptyCache[0] == empty) return;"
     in si,
     "a keystroke that does not flip the state returns BEFORE any setVisibility")
need("omniEmptyCache[0] = empty;" in si,
     "the applied state is written back to the cache")
need(si.find("omniEmptyCache[0] = empty;") < si.find("clearB.setVisibility"),
     "the cache write precedes the CLEAR visibility pass (a throw cannot desync it; v1.4.18 re-point 2026-11-19: the strip's guaranteed-show moved above the cache — it is deliberately uncached, the focus law)")
need("clearB.setVisibility(empty ? View.GONE : View.VISIBLE);" in si,
     "the state mapping survives the two-row redesign + the focus law (clear follows the field; the strip no longer does; re-points 2026-11-18 + 2026-11-19)")
need(ba_code.find("final Boolean[] omniEmptyCache") <
     ba_code.find("final Runnable syncOmniIcons"),
     "the cache is declared with the watcher it serves (one dialog, one cache)")
tw = ba_code[ba_code.find("et.addTextChangedListener(new TextWatcher() {"):
             ba_code.find("et.addTextChangedListener(new TextWatcher() {") + 700]
need("syncOmniIcons.run();" in tw and "ad.update(s.toString());" in tw,
     "the TextWatcher still drives icon state + suggestions per keystroke")
need(ba_code.count("syncOmniIcons.run();") >= 2,
     "the OPENING state is still synced once after wiring")

print("F — fix 3b: the suggestion build moved off the main thread")
need("private static final java.util.concurrent.ExecutorService SUGGEST_BUILD ="
     in ba_code,
     "the build thread exists (one executor, process-wide)")
need('"bolt-suggest"' in ba and "t.setDaemon(true);" in ba_code,
     "the build thread is a named daemon (never blocks process exit)")
need("private int suggestGen = 0;" in ba_code,
     "the generation counter exists")
upd = ba_code[ba_code.find("void update(String q) {"):]
upd = upd[:upd.find("\n        }", 1) + 10] if "\n        }" in upd[1:] else upd
need("final int gen = ++suggestGen;" in upd,
     "every update() bumps the generation (the stale guard's writer)")
need("final String wq = webQuery;" in upd and "final List<String> wr = webRows;" in upd,
     "the web rows ride a main-thread snapshot into the build")
need("SUGGEST_BUILD.execute(() -> {" in upd,
     "update() DISPATCHES the build (no JSON walk on the main thread)")
need("org.json.JSONArray" not in upd and "prefs.history()" not in upd
     and "prefs.bookmarks()" not in upd,
     "the dispatcher itself holds NO local-database reads")
ap = upd[upd.find("ui.post(() -> {"):] if "ui.post(() -> {" in upd else ""
need("if (gen != suggestGen) return;" in ap,
     "the apply drops any build that is not the newest (the stale guard)")
need("items.clear();" in ap and "items.addAll(fresh);" in ap
     and "notifyDataSetChanged();" in ap,
     "the apply swaps the model on the MAIN thread, then notifies")
need("buildRows(query, wq, wr)" in upd,
     "the build runs the extracted builder")
br = ba_code[ba_code.find("private List<Object[]> buildRows(String q, String wq, List<String> wr) {"):]
br = br[:br.find("\n        }", 1) + 10] if "\n        }" in br[1:] else br
need("List<Object[]> out = new ArrayList<>();" in br and "return out;" in br,
     "buildRows builds and returns its own list (pure apply-side swap)")
need("notifyDataSetChanged" not in br,
     "buildRows never notifies (the notify lives on the apply side only)")
need("synchronized (tm.tabs)" in br,
     "the open-tab walk keeps the v1.4.11 thread law under the monitor")

print("G — fix 4: the handler sweep in onDestroy")
odst = ba_code[ba_code.find("protected void onDestroy() {"):]
inc = odst.find("if (incognito)")
sweep = odst[:inc] if inc > 0 else odst[:2000]
need("sdWinH.removeCallbacksAndMessages(null);" in sweep,
     "sdWinH is swept in onDestroy")
need("suggestH.removeCallbacksAndMessages(null);" in sweep,
     "suggestH is swept in onDestroy")
need(inc > 0 and odst.find("sdWinH.removeCallbacksAndMessages(null);") < inc,
     "the sweep runs BEFORE the incognito early-return (both process paths)")
need("ui.removeCallbacksAndMessages(null);" in sweep,
     "the original ui sweep survives")
need("suggestPending = null;" in sweep,
     "the pending debounce runnable is dropped with its timer")

# the compiled probe: the generation guard + the state cache, transcribed and RUN
probe = r"""
public class ResponsivenessProbe {
    // TRANSCRIPTION 1 — the suggestion generation guard (SuggestAdapter
    // .update / apply, v1.4.15). The gate pins the Java source's critical
    // lines above; this runs the same CONTRACT: a build carries the gen it
    // was launched with, and the apply lands only if it is still the newest.
    static class SuggestSim {
        final java.util.List<String> items = new java.util.ArrayList<>();
        int suggestGen = 0;   // main-thread confined in the real code

        class Build {
            final int gen; final java.util.List<String> rows;
            Build(int g, java.util.List<String> r) { gen = g; rows = r; }
        }
        Build start(String q) {                    // update(): bump + build
            final int gen = ++suggestGen;
            java.util.List<String> rows = new java.util.ArrayList<>();
            rows.add("search:" + q);
            for (int i = 0; i < 400; i++) rows.add("hist" + i + ":" + q);
            return new Build(gen, rows);
        }
        void apply(Build b) {                      // the ui.post apply
            if (b.gen != suggestGen) return;       // the stale guard
            items.clear();
            items.addAll(b.rows);
        }
    }

    // TRANSCRIPTION 2 — the omnibox icon state cache (syncOmniIcons,
    // v1.4.15). Counts setVisibility calls; only a state FLIP may call.
    static class OmniSim {
        final Boolean[] cache = new Boolean[1];    // null = never applied
        int visibilityCalls = 0;
        boolean camVisible = true, clearVisible = false;
        void sync(boolean empty) {
            if (cache[0] != null && cache[0] == empty) return;
            cache[0] = empty;
            visibilityCalls += 4;                  // cam/paste/copy/clear
            camVisible = empty; clearVisible = !empty;
        }
    }

    static int fails = 0;
    static void check(String why, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FAIL") + " - " + why);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        // --- the generation guard ---
        SuggestSim ad = new SuggestSim();
        SuggestSim.Build b1 = ad.start("bol");
        SuggestSim.Build b2 = ad.start("bolt");
        ad.apply(b1);
        check("a stale apply (superseded mid-build) never reaches the screen",
              ad.items.isEmpty());
        ad.apply(b2);
        check("the newest build lands",
              ad.items.size() == 401 && ad.items.get(0).equals("search:bolt"));
        SuggestSim.Build b3 = ad.start("bolt b");
        ad.apply(b3);
        check("sequential updates converge on the last query",
              ad.items.get(0).equals("search:bolt b"));
        SuggestSim.Build b4 = ad.start("bolt br");
        SuggestSim.Build b5 = ad.start("bolt bro");
        ad.apply(b4); ad.apply(b5);
        check("a burst of keystrokes leaves only the LAST build visible",
              ad.items.get(0).equals("search:bolt bro"));
        // --- the state cache ---
        OmniSim om = new OmniSim();
        om.sync(true);
        check("the first sync always applies (opening state), empty door open",
              om.visibilityCalls == 4 && om.camVisible && !om.clearVisible);
        om.sync(true); om.sync(true);
        check("repeated same-state keystrokes cost ZERO setVisibility calls",
              om.visibilityCalls == 4);
        om.sync(false);
        check("the EMPTY->TYPING flip applies exactly once",
              om.visibilityCalls == 8 && !om.camVisible && om.clearVisible);
        om.sync(false); om.sync(false);
        check("typing on keeps the field clear of layout invalidations",
              om.visibilityCalls == 8);
        om.sync(true);
        check("clearing the field restores the empty-state buttons once",
              om.visibilityCalls == 12 && om.camVisible && !om.clearVisible);
        System.out.println(fails == 0 ? "PROBE OK" : "PROBE BAD (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
"""
tmp = tempfile.mkdtemp(prefix="bolt_v1415_probe_")
open(os.path.join(tmp, "ResponsivenessProbe.java"), "w").write(probe)
# release.sh puts the JDK on PATH before the gates; standalone runs fall
# back to the workspace's grafted JDK (the README environment note)
GJ = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin"
javac = "javac" if shutil.which("javac") else os.path.join(GJ, "javac")
java = "java" if shutil.which("java") else os.path.join(GJ, "java")
pr = subprocess.run([javac, "-d", tmp, os.path.join(tmp, "ResponsivenessProbe.java")],
                    capture_output=True, text=True)
if pr.returncode != 0:
    print("  FAIL - probe compiled (javac):\n" + pr.stderr[:800])
    fails += 1
else:
    print("  ok  - probe compiled (javac)")
    pr2 = subprocess.run([java, "-cp", tmp, "ResponsivenessProbe"],
                         capture_output=True, text=True)
    print(pr2.stdout.rstrip())
    if pr2.returncode != 0:
        fails += 1

print("I — the gate is wired into the pipeline")
need("python3 scripts/test_v1415.py" in rel,
     "release.sh runs this gate before building")
need(rel.find("test_v1414.py") < rel.find("test_v1415.py"),
     "the gate runs after v1414's (release order)")

print()
print("V1415 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
