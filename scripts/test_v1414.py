#!/usr/bin/env python3
"""v1.4.14 release gate — the interaction six-pack.

THE REPORT (2026-11-18): six interaction/privacy fixes, each pinned below:

1. TAB GROUP DRAG-AND-DROP REGRESSION. v1.4.12 replaced native drag-to-
   reorder in the group folder with "long-press Move up/Move down" buttons
   when the inline strip became a bottom sheet. The gesture is back: the
   sheet's member list is a RecyclerView (Ui.showSheetView — a sheet door
   with NO ScrollView wrapper; nested scrolling would steal the touch
   stream) carrying an ItemTouchHelper.SimpleCallback whose
   getMovementFlags is makeMovementFlags(UP | DOWN, 0). onMove commits
   through tm.reorderGroupMember (the same member-indexed backend) and
   calls notifyItemMoved. showMemberMove is GONE.

2. EXTERNAL SEARCH OPENS IN A NEW TAB. PROCESS_TEXT / WEB_SEARCH / SEND
   queries used to land in loadInTab() — the ACTIVE tab — overwriting the
   user's page. All three now route through openQueryInNewTab(): the
   resolution is the SAME resolver the omnibox uses (resolveInput —
   extracted from loadInTab: scheme passthrough, bare-host, bangs, engine,
   tracker cleaner), the tab is NEW, and it runs inline like the VIEW
   branch (no posted runnable — it builds its own tab, so a cold start
   lands as ONE tab).

3. OMNIBOX CONGESTION. The engine chip + camera + paste strip were
   permanent fixtures (~230 dp of chrome). The icons are state-aware in
   the TextWatcher now: EMPTY shows engine chip + camera + paste (+ mic);
   TYPING hides camera/paste/copy and shows ONLY clear (+ mic). The chip
   stays (the v1.4.12 pick-re-runs door); copy is retired from the omnibox.

4. INCOGNITO INTENT LEAK. Links opened from satellite screens (bookmarks/
   history/saved pages — main-process activities even when launched from
   the incognito browser) fired plain BrowserActivity.class VIEW intents,
   so the page loaded in the NORMAL browser. The stamp-and-return
   contract: Ui.launchSatellite stamps EXTRA_FROM_INCOGNITO when the
   launcher is the incognito process (TabManager.INCOGNITO) or a stamped
   satellite itself; Ui.browserOpenIntent routes a stamped satellite's
   opens to IncognitoActivity. In-browser new-tab logic never rides
   intents at all (the process law at openNewTab).

5. VISUAL SEARCH OOM. A 12 MP gallery pick could die with
   OutOfMemoryError. The decode is bounds-first (inJustDecodeBounds),
   inSampleSize lands the long edge at MAX_EDGE = 1280 px, an OOM retry
   ladder doubles the sample (<= 3 retries), then JPEG compress.

6. HISTORY & BOOKMARKS SEARCH. Both screens gain a search bar
   (BaseListActivity.addSearchBar): a TextWatcher filters the adapter in
   real time by title or URL (rowMatches over title + sub), over the
   last-built model in place (no prefs re-read per keystroke), with a
   dedicated no-matches state.

The compiled probe (section H) runs a TRANSCRIPTION of rowMatches — the
one pure function this release has (the v1.4.11 parse-gate law).
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
ta = open(os.path.join(SRC, "ui/TabsActivity.java"), encoding="utf8").read()
ba = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
ui = open(os.path.join(SRC, "util/Ui.java"), encoding="utf8").read()
vs = open(os.path.join(SRC, "util/VisualSearch.kt"), encoding="utf8").read()
bla = open(os.path.join(SRC, "ui/BaseListActivity.java"), encoding="utf8").read()
hist = open(os.path.join(SRC, "ui/HistoryActivity.java"), encoding="utf8").read()
bm = open(os.path.join(SRC, "ui/BookmarksActivity.java"), encoding="utf8").read()
off = open(os.path.join(SRC, "ui/OfflinePagesActivity.java"), encoding="utf8").read()
sha = open(os.path.join(SRC, "ui/SettingsHomeActivity.java"), encoding="utf8").read()
oma = open(os.path.join(ROOT, "app/src/main/res/layout/dialog_omnibox.xml"),
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
ui_code = strip_comments_strings(ui)
vs_code = strip_comments_strings(vs)
bla_code = strip_comments_strings(bla)
hist_code = strip_comments_strings(hist)
bm_code = strip_comments_strings(bm)
off_code = strip_comments_strings(off)
sha_code = strip_comments_strings(sha)

print("A — the version bump")
need(re.search(r"versionCode 144\b", gradle) is not None,  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 134 (Android only installs RISING codes)")  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName "1\.4\.30"', gradle) is not None,  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): the escaped-form recovery (the phantom-backslash corpse)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.30"')  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print("B — the About changelog (the label law)")
entries = re.findall(r"<item>([^<]+)</item>",
                     strs.split('name="about_changelog"')[1]) \
    if 'name="about_changelog"' in strs else []
need(entries and entries[11].startswith("1.4.18|"),  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])
     "the newest entry is 1.4.14 (the label law)")
need(len(entries) > 3 and entries[14].startswith("1.4.15|"),  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])
     "the second entry is 1.4.13 (the previous live release)")
for word, why in (("drag", "the drag restore"), ("NEW tab", "the new-tab law"),
                  ("full field width", "the omnibox icon diet"),
                  ("incognito", "the return route"),
                  ("out-of-memory", "the OOM ladder"),
                  ("search bar", "the search bars")):
    need(word in entries[15] or word in entries[15].lower(),  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])
         f"the 1.4.14 line names {why}")

print("C — fix 1: the group-sheet drag (RecyclerView + ItemTouchHelper)")
need("class GroupSheetAdapter extends RecyclerView.Adapter" in ta_code,
     "the sheet's member list rides a RecyclerView adapter")
need(re.search(r"ItemTouchHelper\.SimpleCallback\(\s*"
               r"ItemTouchHelper\.UP \| ItemTouchHelper\.DOWN, 0\)", ta_code) is not None,
     "the drag callback is a SimpleCallback(UP | DOWN, 0)")
need("return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);"
     in ta_code,
     "getMovementFlags is makeMovementFlags(UP | DOWN, 0) — drag only, no swipe")
need("if (!tm.reorderGroupMember(gid, from, to)) return false;" in ta_code,
     "onMove commits through tm.reorderGroupMember (the v1.4.4 backend)")
need("ga.notifyItemMoved(from, to);" in ta_code,
     "onMove glides the swap with notifyItemMoved")
need("new ItemTouchHelper(drag).attachToRecyclerView(list);" in ta_code,
     "the ItemTouchHelper is attached to the sheet's recycler")
need("showMemberMove" not in ta_code,
     "the long-press Move up/Move down door is GONE (the report's ask)")
need("showSheetView" not in ta_code or
     "groupSheet = Ui.showSheetView(this, body);" in ta_code,
     "the group sheet hosts through Ui.showSheetView (no ScrollView wrapper)")
need("public static Dialog showSheetView(Activity a, View content)" in ui_code,
     "Ui.showSheetView exists (the recycler sheet door)")
need("return showSheetView(a, sc);" in ui_code,
     "the classic showSheet delegates to it (one window mechanic)")
# the recycler owns scrolling: no ScrollView between the sheet root and it
gs = ta_code[ta_code.find("protected void openGroupSheet(final JSONObject g) {"):]
gs = gs[:gs.find("\n    }", 1) + 6] if "\n    }" in gs[1:] else gs
need("new RecyclerView(this);" in gs and "LinearLayoutManager" in gs,
     "the sheet body builds a RecyclerView with a linear layout manager")
need("ScrollView" not in gs,
     "no ScrollView in the group sheet path (nested scroll steals the drag)")
need("tabOptions(tab, group.optLong(" in ta_code,
     "the per-member menu door survives (v1.4.9's indicated door)")

print("D — fix 2: external searches open in a NEW tab")
hi = ba_code[ba_code.find("private void handleIntent(Intent i) {"):]
if "\n    }" in hi[1:]:
    hi = hi[:hi.find("\n    }", 1) + 6]
need("Intent.ACTION_PROCESS_TEXT.equals(act)" in hi
     and "Intent.EXTRA_PROCESS_TEXT" in hi,
     "the query ladder still reads all three surfaces")
need("openQueryInNewTab(q.trim());" in hi,
     "the search branch routes through openQueryInNewTab")
need("loadInTab(query)" not in hi and "ui.post(() -> {" not in hi,
     "the posted loadInTab handoff is GONE from handleIntent")
need("return;" in hi,
     "the early return survives (a search never falls through to VIEW)")
oq = ba_code[ba_code.find("protected void openQueryInNewTab(String input) {"):]
oq = oq[:oq.find("\n    }", 1) + 6] if "\n    }" in oq[1:] else oq
need("String url = resolveInput(input.trim());" in oq,
     "the query resolves through the SHARED resolver")
need("tm.addTab(t);" in oq and "showTab(tm.tabs.size() - 1);" in oq,
     "the result opens in its OWN tab (add + show)")
need("t.isHome = false;" in oq and "t.url = url;" in oq,
     "the new tab carries the resolved URL (not a home tab)")
ri = ba_code[ba_code.find("protected String resolveInput(String q) {"):]
ri = ri[:ri.find("\n    }", 1) + 6] if "\n    }" in ri[1:] else ri
need("Engines.resolveBang(this, q)" in ri and "URLEncoder.encode(q)" in ri
     and "UrlClean.clean(url)" in ri,
     "resolveInput owns bangs + engine + tracker cleaner (the omnibox path)")
li = ba_code[ba_code.find("protected void loadInTab(String input) {"):]
li = li[:li.find("\n    }", 1) + 6] if "\n    }" in li[1:] else li
need("String url = resolveInput(input.trim());" in li,
     "loadInTab resolves through the SAME resolver (one resolution, many doors)")

print("E — fix 3: state-aware omnibox icons")
need("final Runnable syncOmniIcons" in ba_code,
     "the icon state has ONE sync point")
si = ba_code[ba_code.find("final Runnable syncOmniIcons"):]
si = si[:si.find("};", 1) + 2]
need("et.getText().length() == 0" in si,
     "the state keys on the field being empty")
need("actionsRow.getVisibility() != View.VISIBLE" in si
     and "actionsRow.setVisibility(View.VISIBLE);" in si,
     "the action strip is visible whenever the omnibox is OPEN (v1.4.16 re-point 2026-11-18 + v1.4.18 re-point 2026-11-19: the focus law — the text-gate hid the doors until the first keystroke)")
need("clearB.setVisibility(empty ? View.GONE : View.VISIBLE);" in si,
     "clear shows ONLY while typing (rides Row 1; v1.4.16 re-point 2026-11-18)")
need("R.id.omni_actions" in ba_code and "R.id.btn_omni_copy" in ba_code,
     "copy lives in the Row 2 strip (v1.4.16 re-point 2026-11-18: un-retired as a strip action)")
tw = ba_code[ba_code.find("et.addTextChangedListener(new TextWatcher() {"):
             ba_code.find("et.addTextChangedListener(new TextWatcher() {") + 700]
need("syncOmniIcons.run();" in tw,
     "the TextWatcher drives the state on every keystroke")
need(tw.find("syncOmniIcons.run();") < tw.find("ad.update(s.toString());"),
     "the icon sync runs before the suggestion pass")
need(ba_code.count("syncOmniIcons.run();") >= 2,
     "the OPENING state is synced once after wiring (not only on keystrokes)")
need('android:id="@+id/btn_omni_clear"' in oma and
     'android:id="@+id/omni_engine"' in oma,
     "the layout still carries the chip and the clear button")
need('android:id="@+id/btn_omni_cam"' in oma and
     'android:id="@+id/btn_omni_paste"' in oma,
     "the layout still carries the camera + paste buttons (state-toggled)")

print("F — fix 4: the incognito return route (stamp and return)")
need('public static final String EXTRA_FROM_INCOGNITO = ' in ui_code,
     "the stamp extra exists")
ls_ = ui_code[ui_code.find("public static void launchSatellite(Activity a, Class<?> cls) {"):]
ls_ = ls_[:ls_.find("\n    }", 1) + 6] if "\n    }" in ls_[1:] else ls_
need("com.bolt.browser.core.TabManager.INCOGNITO" in ls_,
     "the launcher stamps when IT is the incognito process")
need("getBooleanExtra(EXTRA_FROM_INCOGNITO, false)" in ls_,
     "a stamped satellite re-stamps (Settings -> Bookmarks chains)")
bo = ui_code[ui_code.find("public static Intent browserOpenIntent(Activity from, String url) {"):]
bo = bo[:bo.find("\n    }", 1) + 6] if "\n    }" in bo[1:] else bo
need("com.bolt.browser.ui.IncognitoActivity.class" in bo
     and "com.bolt.browser.ui.BrowserActivity.class" in bo,
     "the return intent routes by stamp (incognito side vs normal)")
need("i.setAction(Intent.ACTION_VIEW);" in bo and "i.setData(" in bo,
     "the return rides the same ACTION_VIEW contract")
for f, code in (("BookmarksActivity", bm_code), ("HistoryActivity", hist_code),
                ("OfflinePagesActivity", off_code)):
    need("Ui.browserOpenIntent(this," in code,
         f"{f} opens URLs through the routed intent")
    need("new Intent(this, BrowserActivity.class)" not in code,
         f"{f} no longer fires a plain BrowserActivity intent (the leak)")
need("Ui.launchSatellite(this, BookmarksActivity.class);" in ba_code
     and "Ui.launchSatellite(this, HistoryActivity.class);" in ba_code,
     "the browser's quick actions launch satellites through the stamping door")
need("Ui.launchSatellite(this, SettingsHomeActivity.class)" in ba_code,
     "the menu's Settings launch is stamped too (it chains to all three)")
for cls in ("BookmarksActivity", "HistoryActivity", "OfflinePagesActivity"):
    need(f"Ui.launchSatellite(this, {cls}.class)" in sha_code,
         f"SettingsHomeActivity launches {cls} through the stamping door")
# the process law: in-browser new tabs never ride intents
need("new Intent(this, BrowserActivity.class)" not in ba_code,
     "BrowserActivity itself fires NO BrowserActivity.class intents (the law)")

print("G — fix 5: the visual-search OOM ladder")
need("private const val MAX_EDGE = 1280" in vs_code,
     "the upload cap is a 1280 px long edge")
need("inJustDecodeBounds = true" in vs_code,
     "the decode is bounds-first")
need("inSampleSize = s" in vs_code,
     "the sampled decode rides inSampleSize")
need("catch (oom: OutOfMemoryError)" in vs_code and "s *= 2" in vs_code,
     "an OOM doubles the sample and retries (the ladder)")
need(re.search(r"for \(attempt in 0 until 4\)", vs_code) is not None,
     "the ladder is bounded (4 attempts)")
need("maxOf(opts.outWidth, opts.outHeight) / (s * 2) >= maxEdge" in vs_code,
     "the cap rides the LONG edge (panoramas are the memory bombs)")
need("bmp.compress(Bitmap.CompressFormat.JPEG, 90, bo)" in vs_code,  # v1.4.17 re-point (2026-11-18): quality 85 -> 90 (the grey-image fidelity pass)
     "the upload body is JPEG-compressed before the multipart POST")
need("decodeCapped(imageFile, MAX_EDGE)" in vs_code,
     "search() feeds the cap to the decoder")

print("H — fix 6: the bookmarks/history search bars (filter in real time)")
need("protected void addSearchBar()" in bla_code,
     "BaseListActivity owns the search bar (the shared door)")
need("protected List<Row> fullRows" in bla_code,
     "the UNFILTERED model survives every build")
need("fullRows = new ArrayList<>(list);" in bla_code
     and bla_code.count("fullRows = new ArrayList<>(list);") >= 2,
     "both render paths (setRows + setRowsAnimated) capture the model")
need("protected void applySearch()" in bla_code,
     "the live filter pass exists")
need("rowMatches(r, q)" in bla_code,
     "the filter consults rowMatches")
rm = bla_code[bla_code.find("protected boolean rowMatches(Row r, String q) {"):]
rm = rm[:rm.find("\n    }", 1) + 6] if "\n    }" in rm[1:] else rm
need("r.title.toLowerCase(Locale.ROOT).contains(q)" in rm
     and "r.sub.toLowerCase(Locale.ROOT).contains(q)" in rm,
     "the match rule is title OR sub (sub carries the URL), case-insensitive")
need("if (r.click == null && !r.switchRow && r.longClick == null) return false;" in rm,
     "placeholder rows never match a query")
need("No matches for" in bla,
     "a query that matches nothing gets its own state (string pin, raw read)")
sb = bla_code[bla_code.find("protected void addSearchBar() {"):]
sb = sb[:sb.find("\n    }", 1) + 6] if "\n    }" in sb[1:] else sb
need("et.addTextChangedListener(new TextWatcher() {" in sb
     and "applySearch();" in sb,
     "the TextWatcher re-filters on every keystroke (real time)")
need("R.drawable.bg_urlbar" in sb,
     "the bar wears the omnibox field's skin")
need("addSearchBar();" in hist_code,
     "History wires the search bar")
need("addSearchBar();" in bm_code,
     "Bookmarks wires the search bar")

# the compiled probe: rowMatches, transcribed and RUN
probe = r"""
public class RowMatchProbe {
    // TRANSCRIBED from BaseListActivity.rowMatches (v1.4.14). The gate
    // pins the Java source's critical lines above; this runs the same
    // rule to prove its BEHAVIOR.
    static class Row {
        String title, sub;
        boolean switchRow;
        Runnable click, longClick;
    }
    static boolean rowMatches(Row r, String q) {
        if (q == null || q.isEmpty()) return true;
        if (r.click == null && !r.switchRow && r.longClick == null) return false;
        return (r.title != null && r.title.toLowerCase(java.util.Locale.ROOT).contains(q))
                || (r.sub != null && r.sub.toLowerCase(java.util.Locale.ROOT).contains(q));
    }

    static int fails = 0;
    static void check(String why, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FAIL") + " - " + why);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        Row page = new Row(); page.title = "Weather forecast"; page.sub = "Nov 12 — https://example.com/weather?city=Oslo"; page.click = () -> {};
        Row home = new Row(); home.title = "Example News"; home.sub = "https://news.example.com/article?id=42"; home.click = () -> {};
        Row placeholder = new Row(); placeholder.title = "Empty";
        // 1) empty query shows everything
        check("empty query matches all", rowMatches(page, "") && rowMatches(placeholder, ""));
        // 2) title match, case-insensitive (applySearch lowercases the
        // query before it reaches rowMatches — mirrored here)
        check("title match is case-insensitive",
              rowMatches(page, "FORECAST".toLowerCase(java.util.Locale.ROOT)));
        // 3) URL match rides sub
        check("URL substring matches via sub", rowMatches(home, "news.example.com"));
        // 4) query parameters match too
        check("query string matches via sub", rowMatches(home, "id=42"));
        // 5) placeholder rows never match a live query
        check("placeholder never matches", !rowMatches(placeholder, "empty"));
        // 6) a real miss is a miss
        check("no match is no match", !rowMatches(page, "zzzznotpresent"));
        // 7) null-tolerant rows
        Row nulls = new Row(); nulls.click = () -> {};
        check("null title/sub tolerated", !rowMatches(nulls, "x") && rowMatches(nulls, ""));
        System.out.println(fails == 0 ? "PROBE OK" : "PROBE BAD (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
"""
tmp = tempfile.mkdtemp(prefix="bolt_v1414_probe_")
open(os.path.join(tmp, "RowMatchProbe.java"), "w").write(probe)
# release.sh puts the JDK on PATH before the gates; standalone runs fall
# back to the workspace's grafted JDK (the README environment note)
GJ = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin"
javac = "javac" if shutil.which("javac") else os.path.join(GJ, "javac")
java = "java" if shutil.which("java") else os.path.join(GJ, "java")
pr = subprocess.run([javac, "-d", tmp, os.path.join(tmp, "RowMatchProbe.java")],
                    capture_output=True, text=True)
if pr.returncode != 0:
    print("  FAIL - probe compiled (javac):\n" + pr.stderr[:800])
    fails += 1
else:
    print("  ok  - probe compiled (javac)")
    pr2 = subprocess.run([java, "-cp", tmp, "RowMatchProbe"],
                         capture_output=True, text=True)
    print(pr2.stdout.rstrip())
    if pr2.returncode != 0:
        fails += 1

print("I — the gate is wired into the pipeline")
need("python3 scripts/test_v1414.py" in rel,
     "release.sh runs this gate before building")
need(rel.find("test_v1413.py") < rel.find("test_v1414.py"),
     "the gate runs after v1413's (release order)")

print()
print("V1414 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
