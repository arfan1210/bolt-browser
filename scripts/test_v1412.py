#!/usr/bin/env python3
"""v1.4.12 release gate — the usability five-pack.

THE REPORT (2026-11-18): five defects/features, each pinned below:

1. THE AI TRANSLATE PAYLOAD. "encodeAi scales screenshots to 2200px at 90%
   JPEG quality, creating massive 2MB-4MB base64 payloads that cause API
   timeouts or HTTP 400/413 errors on mobile networks." Pinned: encodeAi
   rides encodeScaled(bmp, 1280, 75) (~200 KB base64) and the OCR encoder
   (encodeJpeg, 1600/90) is untouched — the diet is the AI path only.

2. THE GROUP FOLDER SHEET + SWITCHER CONTEXT. "When a tab group has >2
   tabs, the inline grid squishes them. Refactor the group expansion to
   open a vertically scrollable Bottom Sheet Dialog listing the group's
   tabs with titles and thumbnails." Pinned: the header click opens
   openGroupSheet (Ui.showSheet, the app's bottom sheet), one row per tab
   (favicon thumbnail + title + domain + the ⋯ member menu + tap-to-switch),
   the inline strip NEVER renders again (no item_tab_mini inflate, no
   wireMiniDrag call, cardsWrap forced GONE), and the switcher's
   auto-scroll now resolves the active tab's GROUP row when it lives
   inside a folder (groupAt + groupId match + scrollToPositionWithOffset).

3. THE INCOGNITO PROCESS FLAG. "TabsIncognitoActivity runs in the
   :incognito process but is missing the static { TabManager.INCOGNITO =
   true; } block, causing it to default to normal mode." Pinned: the
   static initializer exists in TabsIncognitoActivity exactly as
   IncognitoActivity carries it.

4. THE SYSTEM SEARCH ENTRIES. "Bolt is missing from the Android
   text-selection floating toolbar. Add ACTION_WEB_SEARCH, ACTION_SEND
   (text/plain), and ACTION_PROCESS_TEXT intent filters to the launcher
   aliases." Pinned: all FIVE launcher aliases carry all THREE filters
   (SEND and PROCESS_TEXT with mimeType text/plain), and handleIntent()
   extracts the query from SearchManager.QUERY / EXTRA_TEXT /
   EXTRA_PROCESS_TEXT and hands it to loadInTab() — posted, never run
   inline (onCreate calls handleIntent before the first tab exists) —
   with an early return so a search entry never falls through to the
   VIEW branch too.

5. THE OMNIBOX ENGINE + IMAGE DOORS. "Add a search engine icon with a
   dropdown arrow to the left of the omnibox EditText... and a
   Camera/Image Search icon to the right." Pinned: the layout carries the
   engine chip (omni_engine + iv_omni_engine_arrow + ic_drop_down) and
   btn_omni_cam (ic_camera) right of the field; showEnginePicker switches
   the engine pref and re-runs a live query through the new engine;
   showImageSearchPicker/launchVisualCamera/runVisualSearch + the 1021/
   1022/1023 result handlers wire the picture into VisualSearch, whose
   endpointFor mapping routes Google/Startpage via Google's search-by-
   image POST and Bing/DDG/Brave via Bing's visual search POST (the
   multipart POST never auto-follows its 3xx — Java's law — so the
   redirect is read manually; a 200 body is saved as a local page).

Why no compiled probe this time: the five changes are constants, layout
wiring, manifest filters and Android-lifecycle-coupled code paths (Bitmap,
FileProvider, tabs) — none extract into a pure static method the way
v1.4.11's inferType did. The pins below read CODE with comments and string
bodies stripped (the strip_comments_strings law), and the debug build in
the pipeline compiles every line they pin.
"""
import os
import re
import sys

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
man = open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"),
           encoding="utf8").read()
rel = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()
scr = open(os.path.join(SRC, "util/ScreenTranslate.kt"), encoding="utf8").read()
ba = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
ta = open(os.path.join(SRC, "ui/TabsActivity.java"), encoding="utf8").read()
tia = open(os.path.join(SRC, "ui/TabsIncognitoActivity.java"),
           encoding="utf8").read()
ia = open(os.path.join(SRC, "ui/IncognitoActivity.java"), encoding="utf8").read()
omni = open(os.path.join(RES, "layout/dialog_omnibox.xml"),
            encoding="utf8").read()
vs = open(os.path.join(SRC, "util/VisualSearch.kt"), encoding="utf8").read()


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
ba_code = strip_comments_strings(ba)
ta_code = strip_comments_strings(ta)
tia_code = strip_comments_strings(tia)
vs_code = strip_comments_strings(vs)

print("A — the version bump")
need(re.search(r"versionCode 133\b", gradle) is not None,  # # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)
     "versionCode 133 (Android only installs RISING codes)")  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)
need(re.search(r'versionName "1\.4\.19"', gradle) is not None,  # # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): the escaped-form recovery (the phantom-backslash corpse)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)
     'versionName "1.4.19"')  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)

print("B — the About changelog (the label law)")
entries = re.findall(r"<item>([^<]+)</item>",
                     strs.split('name="about_changelog"')[1]) \
    if 'name="about_changelog"' in strs else []
need(entries and entries[1].startswith("1.4.18|"),   # v1.4.13 re-point (2026-11-18): 1.4.13 landed at [0]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])
     "the newest entry is 1.4.13 (the label law)")
need(len(entries) > 6 and entries[7].startswith("1.4.12|"),   # v1.4.13 re-point (2026-11-18): this gate's line slid to [1]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])
     "the second entry is 1.4.12 (the version this gate pins)")
need("200 KB" in entries[7] and "1280" in entries[7],   # v1.4.13 re-point: slid from [0]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])
     "the 1.4.12 line names the payload diet numbers")
need("bottom sheet" in entries[7],   # v1.4.13 re-point: slid from [0]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])
     "the 1.4.12 line names the group folder sheet")
need("incognito" in entries[7],   # v1.4.13 re-point: slid from [0]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])
     "the 1.4.12 line names the incognito tabs flag fix")
need("text-selection" in entries[7],   # v1.4.13 re-point: slid from [0]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])
     "the 1.4.12 line names the system search entries")
need("visual search" in entries[7],   # v1.4.13 re-point: slid from [0]  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])
     "the 1.4.12 line names the image-search door")

print("C — fix 1: the AI payload diet (1280 px / q75, OCR untouched)")
ai = re.search(r"private fun encodeAi\(bmp: Bitmap\): Enc\? \{\s*"
               r"return encodeScaled\(bmp, (\d+), (\d+)\)", scr_code)
need(ai is not None and ai.group(1) == "1280" and ai.group(2) == "75",
     "encodeAi rides encodeScaled(bmp, 1280, 75)")
jx = re.search(r"private fun encodeJpeg\(bmp: Bitmap\): Enc\? \{\s*"
               r"return encodeScaled\(bmp, (\d+), (\d+)\)", scr_code)
need(jx is not None and jx.group(1) == "1600" and jx.group(2) == "90",
     "encodeJpeg stays 1600/90 (the OCR path is untouched)")
need("2200" not in scr_code,
     "no 2200 px constant survives in code (the corpse is gone)")

print("D — fix 2: the group folder sheet + the switcher context walk")
need("openGroupSheet(g)" in ta_code and "protected void openGroupSheet" in ta_code,
     "the group header opens openGroupSheet (the sheet is the expansion)")
need(re.search(r"gh\.header\.setOnClickListener\(v -> \{\s*Ui\.tap\(v\);\s*"
               r"openGroupSheet\(g\);", ta_code) is not None,
     "the header click routes to the sheet (with haptic)")
need("Ui.showSheetView(this, body)" in ta_code,
     "the sheet rides Ui.showSheetView (the v1.4.14 recycler door: handle, "
     "rounded top, night veil — and NO ScrollView wrapper, so the drag "
     "owns the touch stream)")  # v1.4.14 re-point (2026-11-18): the member list became a RecyclerView (the drag restore) — a recycler sheet cannot ride the ScrollView wrapper, so the door moved to Ui.showSheetView
need(re.search(r"openGroupSheet[\s\S]{0,2200}FaviconStore\.avatar", ta_code)
     is not None,
     "sheet rows wear the favicon thumbnail")
need(re.search(r"GroupSheetAdapter[\s\S]{0,4500}tabOptions\(tab, group\.optLong\(", ta_code)
     is not None,
     "every sheet row carries the ⋯ member menu (tabOptions)")  # v1.4.14 re-point (2026-11-18): the rows live in GroupSheetAdapter now (the drag restore); the member menu is wired per-bind, keyed by the group's own id
need(re.search(r"GroupSheetAdapter[\s\S]{0,4600}tm\.active = idx;\s*"
               r"try \{ if \(groupSheet != null\) groupSheet\.dismiss\(\); \}\s*"
               r"catch \(Throwable ignored\) \{\}\s*finish\(\);", ta_code) is not None,
     "tapping a sheet row switches to that tab and leaves the switcher")  # v1.4.14 re-point (2026-11-18): the row listener moved into GroupSheetAdapter; the sheet reference is the groupSheet field (dRef died with the row loop)
need("R.layout.item_tab_mini" not in ta_code,
     "the inline mini strip is gone (no item_tab_mini inflate anywhere)")
need("wireMiniDrag(mini," not in ta_code,
     "the strip's drag wiring call is gone with it")
need("gh.cardsWrap.setVisibility(View.GONE)" in ta_code,
     "the strip wrapper stays GONE (never renders)")
need("tm.tabsOfGroup(gid)" in ta_code,
     "the member list rides tabsOfGroup (internally synchronized)")
# the auto-scroll walk: a member resolves to its GROUP row
sa = ta_code[ta_code.find("protected void scrollToActive"):]
if "\n    }" in sa[1:]:
    sa = sa[:sa.find("\n    }", 1) + 6]
need("adapter.groupAt(i)" in sa and "active.groupId" in sa
     and 'g.optLong("id") == active.groupId' in ta[ta.find("protected void scrollToActive"):][:2000],
     "scrollToActive resolves a member's GROUP row by groupId"
     " (string-carrying pin reads raw, per the v1411 convention)")
need("glm.scrollToPositionWithOffset(target," in sa,
     "the scroll still lands via scrollToPositionWithOffset (the ask)")
need("adapter.tabAt(i) == active" in sa,
     "loose tabs still match directly (flat search lists included)")

print("E — fix 3: the incognito tabs process flag")
need(re.search(r"static \{\s*TabManager\.INCOGNITO = true;\s*\}", tia_code)
     is not None,
     "TabsIncognitoActivity carries the static process flag")
need(re.search(r"static \{\s*TabManager\.INCOGNITO = true;\s*\}",
               strip_comments_strings(ia)) is not None,
     "...byte-identical to IncognitoActivity's block")
need("import com.bolt.browser.core.TabManager;" in tia,
     "the import exists (the file compiles)")

print("F — fix 4: the system search entries (manifest + handleIntent)")
for action in ("android.intent.action.WEB_SEARCH",
               "android.intent.action.SEND",
               "android.intent.action.PROCESS_TEXT"):
    need(man.count(action) == 6,
         f"{action.split('.')[-1]} filter on all 6 faces (5 launcher + the incognito search face)")  # v1.4.17 re-point (2026-11-18): SearchIncognito (the incognito PROCESS_TEXT/WEB_SEARCH/SEND face) — counts 5->6, mimes 10->12
aliases = man.count("<activity-alias")
need(aliases == 6, "the manifest declares 6 aliases (5 launcher + SearchIncognito)")  # v1.4.17 re-point (2026-11-18): SearchIncognito (the incognito PROCESS_TEXT/WEB_SEARCH/SEND face) — counts 5->6, mimes 10->12
need(man.count('android:mimeType="text/plain"') == 12,  # v1.4.17 re-point (2026-11-18): SearchIncognito (the incognito PROCESS_TEXT/WEB_SEARCH/SEND face) — counts 5->6, mimes 10->12
     "SEND and PROCESS_TEXT each carry the text/plain mime (2 per alias)")
# handleIntent: the query ladder + the loadInTab handoff
hi = ba_code[ba_code.find("private void handleIntent(Intent i) {"):]
if "\n    }" in hi[1:]:
    hi = hi[:hi.find("\n    }", 1) + 6]
need("Intent.ACTION_WEB_SEARCH.equals(act)" in hi
     and "SearchManager.QUERY" in hi,
     "WEB_SEARCH reads SearchManager.QUERY")
need('Intent.ACTION_SEND.equals(act)' in hi
     and '"text/plain".equals(i.getType())' in ba
     and "Intent.EXTRA_TEXT" in hi,
     "SEND text/plain reads EXTRA_TEXT (and only text/plain — the mime "
     "check reads raw, per the v1411 convention)")
need("Intent.ACTION_PROCESS_TEXT.equals(act)" in hi
     and "Intent.EXTRA_PROCESS_TEXT" in hi,
     "PROCESS_TEXT reads EXTRA_PROCESS_TEXT")
need("openQueryInNewTab(q.trim());" in hi and "loadInTab(query)" not in hi,  # v1.4.14 re-point (2026-11-18): the external-search NEW-TAB law — the query now routes through openQueryInNewTab() (the SHARED resolveInput + its own tab), inline like the VIEW branch; the posted loadInTab handoff is gone
     "the query opens in its OWN tab (openQueryInNewTab — v1.4.14)")
need(hi.find("return;") < hi.find("if (i == null || i.getData() == null) return;"),
     "a handled search returns — never falls through to the VIEW branch")
need("import android.app.SearchManager;" in ba,
     "the SearchManager import exists")

print("G — fix 5: the omnibox engine chip + the image-search door")
need('android:id="@+id/omni_engine"' in omni
     and 'android:id="@+id/iv_omni_engine_arrow"' in omni
     and "@drawable/ic_drop_down" in omni,
     "the omnibox layout carries the engine chip + drop arrow")
need(omni.find("@+id/omni_engine") < omni.find("@+id/et_omni")
     < omni.find("@+id/btn_omni_cam"),
     "the chip sits LEFT of the field, the camera RIGHT of it")
need('android:id="@+id/btn_omni_cam"' in omni and "@drawable/ic_camera" in omni,
     "the camera/image-search button exists with the camera glyph")
need(os.path.exists(os.path.join(RES, "drawable/ic_camera.xml"))
     and os.path.exists(os.path.join(RES, "drawable/ic_drop_down.xml")),
     "the two new drawables exist")
# engine picker
ep = ba_code[ba_code.find("protected void showEnginePicker"):]
if "\n    }" in ep[1:]:
    ep = ep[:ep.find("\n    }", 1) + 6]
need('prefs.set("searchEngine", idx)' in ba,
     "a pick sets the engine pref (Settings' own pref — sticks everywhere; "
     "string-carrying pin reads raw)")
need("R.array.search_engine_names" in ep,
     "the picker lists the built-in engines")
need("loadInTab(q);" in ep and "urlish" in ep,
     "a live query re-runs through the new engine; a URL does not")
# image-search flow
isp = ba_code[ba_code.find("protected void showImageSearchPicker"):]
if "\n    }" in isp[1:]:
    isp = isp[:isp.find("\n    }", 1) + 6]
need('Intent.ACTION_GET_CONTENT' in isp and 'gi.setType("image/*")' in ba,
     "the gallery door opens ACTION_GET_CONTENT image/*")
need("Manifest.permission.CAMERA" in isp and "1023" in isp,
     "the camera door asks the runtime CAMERA permission (code 1023)")
lc = ba_code[ba_code.find("protected void launchVisualCamera"):]
if "\n    }" in lc[1:]:
    lc = lc[:lc.find("\n    }", 1) + 6]
need("MediaStore.ACTION_IMAGE_CAPTURE" in lc
     and "MediaStore.EXTRA_OUTPUT" in lc
     and "getUriForFile" in lc,
     "capture rides EXTRA_OUTPUT through the FileProvider (full-res)")
rv = ba_code[ba_code.find("protected void runVisualSearch"):]
if "\n    }" in rv[1:]:
    rv = rv[:rv.find("\n    }", 1) + 6]
need("VisualSearch.endpointFor(ei)" in rv and "VisualSearch.search(this, ei,"
     in rv, "the picture routes through VisualSearch with the live engine")
need("requestCode == 1021" in ba_code and "requestCode == 1022" in ba_code,
     "the capture (1021) and gallery (1022) results are handled")
need("requestCode == 1023" in ba_code,
     "the permission grant (1023) finally launches the camera")
# the VisualSearch engine map (value pins read the RAW file — the URLs
# and field names are request constants, and the string-stripped view
# blanks them; structure pins below read the stripped code)
need('"https://www.google.com/searchbyimage/upload"' in vs,
     "Google + Startpage ride Google's search-by-image POST")
need('"imageBin"' in vs
     and '"https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIVPV"' in vs,
     "Bing + DDG + Brave ride Bing's visual search POST (imageBin)")
need('0, 4 -> Endpoint(' in vs_code
     and 'engineIdx == 4' in vs_code,
     "the when-map routes engine 0 and 4 to the Google endpoint (Startpage "
     "via its results provider)")
need('else -> Endpoint(' in vs_code and 'DDG (1) and Brave (3)' in vs,
     "DDG and Brave land on the Bing endpoint (the else arm) — marked")
need("instanceFollowRedirects = false" in vs_code,
     "the POST never auto-follows its 3xx (Java's POST-redirect law)")
need('getHeaderField("Location")' in vs,
     "the redirect target is read manually")
need("return armIntercept(ep.postUrl," in vs_code and "fun takeIntercept(" in vs_code,
     "a direct 200 body is served UNDER ITS REAL ORIGIN (v1.4.19 re-point 2026-11-19: the file:// saved page could not run Bing's JS results runtime from an opaque origin — the body is now armed for the real results URL and served via shouldInterceptRequest; the v1.4.18 'file://' shape is dead)")  # v1.4.19 re-point (2026-11-19): the real-origin intercept (see test_v1419 section H)
need("decodeCapped(imageFile, MAX_EDGE)" in vs_code and "private const val MAX_EDGE = 1280" in vs_code,
     "the upload decodes capped (MAX_EDGE, 1280 px since v1.4.14 — a 12 MP photo uploads no slower, no OOM)")  # v1.4.14 re-point (2026-11-18): the visual-search OOM ladder moved the cap to a named MAX_EDGE = 1280 with an OutOfMemoryError retry (see test_v1414 section G); the decodeCapped discipline itself is unchanged
need("ep.home" in rv,
     "upload failure falls back to the engine's manual image-search page")

print("H — the gate is wired into the pipeline")
need("python3 scripts/test_v1412.py" in rel,
     "release.sh runs this gate before building")
need(rel.find("test_v1411.py") < rel.find("test_v1412.py"),
     "the gate runs after v1411's (release order)")

print()
print("V1412 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
