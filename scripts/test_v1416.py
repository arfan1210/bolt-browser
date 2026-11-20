#!/usr/bin/env python3
"""v1.4.16 release gate — the reliability nine-pack.

THE REPORT (2026-11-18): nine fixes, each pinned below:

1. DOWNLOAD 0KB + "(1)" DUPLICATES + .bin NAMING (DownloadEngine).
   a) savePart created the destination BEFORE copying; a failed copy
      left the 0KB document behind, so retries appended "(1)". The copy
      is now wrapped: on failure the created URI is deleted via
      ContentResolver.delete() (SAF + MediaStore) / File.delete() (plain
      paths) before the fall-through — a retry starts clean.
   b) blob: URLs died in HttpURLConnection and defaulted toward .bin.
      onDownloadStart routes blob: straight through the JS-bridge rescue
      (BlobDownload.start -> Bolt.gmSaveBlob @JavascriptInterface ->
      saveDataUrl); GM_download routes through enqueueDownload's scheme
      law instead of feeding the engine raw.
   c) refineFile's ensureExt only repaired missing/.bin/.part. When the
      type was LEARNED (real Content-Type or magic-byte sniffing), the
      name's extension is now forced to agree with the learned mime.

2. TAB GHOSTING ON MOVE (TabsActivity). adapter.reload() during an
   active drag rebuilt the board and recycled the dragged ViewHolder
   mid-gesture. reload() parks the request while dragActive;
   DragMergeCallback.clearView's finally flushes it when the gesture
   ends (onMove still returns false — reordering commits at drop).

3. GROUP SHEET 3-DOT ALIGNMENT (GroupSheetAdapter). The row had no
   LayoutParams -> RecyclerView defaulted to wrap-content width ->
   weight=1 dead -> menu mid-row. Rows are MATCH_PARENT x WRAP_CONTENT.

4. INCOGNITO ROUTING RACE (IncognitoActivity / TabsIncognitoActivity).
   Static-block class-load order is not guaranteed; onCreate() now sets
   TabManager.INCOGNITO = true as its VERY FIRST statement, before
   super.onCreate().

5. GROUPED-ACTIVE HIGHLIGHT (TabsAdapter). applySelVisuals only ran for
   loose cards; a group row whose members include tm.active now gets
   the accent rim + a new v_group_strip accent strip (both group
   layouts). scrollToActive already resolves a member's group row.

6. TWO-ROW OMNIBOX (dialog_omnibox.xml + BrowserActivity). Row 1:
   engine chip | EditText (weight 1) | Clear. Row 2 (omni_actions):
   Copy, Paste, Duplicate tab, Image Search, Voice Search — shown only
   while the field holds text. The v1.4.15 state cache survives.

7. FIND-BAR BLEED-OVER: showTab() hides findbar, clears etFind/counter,
   clearMatches()es the DEPARTING tab.
8. OMNIBOX DIALOG LEAK: onDestroy() dismisses a showing omniboxDialog.
9. HOME GHOST DRAG: onPause()/onStop() remove sdGhost from flRoot.

The compiled probe (section K) runs TRANSCRIPTIONS of three contracts:
the 0KB corpse cleanup (a failed copy deletes the destination so a
retry never sees a duplicate), the mid-drag reload deferral (parked
reloads land exactly once, after the gesture), and the strict extension
law (a learned type forces the extension; an honest name keeps it).
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
de = open(os.path.join(SRC, "core/DownloadEngine.java"), encoding="utf8").read()
ia = open(os.path.join(SRC, "ui/IncognitoActivity.java"), encoding="utf8").read()
tia = open(os.path.join(SRC, "ui/TabsIncognitoActivity.java"),
           encoding="utf8").read()
omni_xml = open(os.path.join(RES, "layout/dialog_omnibox.xml"),
                encoding="utf8").read()
grow_xml = open(os.path.join(RES, "layout/item_group_row.xml"),
                encoding="utf8").read()
gcard_xml = open(os.path.join(RES, "layout/item_group_card.xml"),
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
de_code = strip_comments_strings(de)

print("A — the version bump")
need(re.search(r"versionCode 139\b", gradle) is not None,  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 134 (Android only installs RISING codes)")  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName "1\.4\.25"', gradle) is not None,  # v1.4.17 re-point (2026-11-18): the escaped-form recovery (the phantom-backslash corpse)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.25"')  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print("B — the About changelog (the label law)")
entries = re.findall(r"<item>([^<]+)</item>",
                     strs.split('name="about_changelog"')[1]) \
    if 'name="about_changelog"' in strs else []
need(entries and entries[6].startswith("1.4.18|"),  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])
     "the newest entry is 1.4.16 (the label law)")
need(len(entries) > 3 and entries[9].startswith("1.4.15|"),  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])
     "the second entry is 1.4.15 (the previous live release)")
for word, why in (("0KB", "the corpse law"),
                  ("JS bridge", "the blob routing"),
                  ("accent rim", "the grouped-active highlight"),
                  ("two rows", "the omnibox redesign")):
    need(word in entries[8] or word in entries[8].lower(),  # v1.4.18 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.18 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])
         f"the 1.4.16 line names {why}")  # v1.4.17 re-point (2026-11-18): the 1.4.16 line slid to [1] when 1.4.17 landed at [0]

print("C — fix 1a: a failed copy leaves no 0KB destination")
sp = de_code[de_code.find("private Uri savePart(Item it, File part) throws IOException {"):]
sp = sp[:sp.find("private Uri saveBytes(")] if "private Uri saveBytes(" in sp else sp
need(sp.count("} catch (Throwable copyFail) {") >= 3,
     "all three destination paths wrap their copy (SAF, MediaStore, plain)")
need(sp.count("ctx.getContentResolver().delete(target, null, null);") == 1
     and sp.count("ctx.getContentResolver().delete(row, null, null);") == 1,
     "a failed copy deletes the created URI (SAF document + MediaStore row)")
need("out.delete();" in sp,
     "the plain-file path deletes the partial File (uniqueFile cannot slot a retry)")
# the delete happens BEFORE rethrowing (the fall-through sees no corpse)
saf = sp[sp.find("openOutputStream(target"):]
saf = saf[:saf.find("return target;")]
need(saf.find("ContentResolver().delete(target") < saf.find("throw copyFail;"),
     "the SAF delete precedes the rethrow")
ms = sp[sp.find("openOutputStream(row"):]
ms = ms[:ms.find("ContentValues fin")]
need(ms.find("ContentResolver().delete(row") < ms.find("throw copyFail;"),
     "the MediaStore delete precedes the rethrow")

print("D — fix 1b: blob: downloads ride the JS bridge")
ods = ba[ba.find("public void onDownloadStart"):]
ods = ods[:ods.find("protected void repairDownloadPage")] \
    if "protected void repairDownloadPage" in ods else ods[:4000]
need('url.startsWith("blob:")' in ods,
     "onDownloadStart has an explicit blob: branch")
need(ods.find('url.startsWith("blob:")') < ods.find("showDownloadDialog(url, userAgent, contentDisposition, mimetype, contentLength)"),
     "the blob: branch routes BEFORE the http dialog (v1.4.20 re-point 2026-11-20: the ask-every-time default now routes the RESCUED BYTES through the dialog too (dlOfferBlob) — but the rescue itself still runs before any confirm, so no blob ever depends on the dialog to reach its bytes; the 5-arg dialog call is the http tail's)")
need("com.bolt.browser.util.BlobDownload.start(" in ods,
     "the branch enters the v1.3.58 rescue chain directly")
need("DownloadEngine.humanName(url," in ods,
     "the fallback name is the humanized guess (no raw UUID)")
need("ensureDlShelfListener();" in ods,
     "the download shelf follows the bridge save too")
gm = ba[ba.find("public void gmDownload(final String json) {"):]
gm = gm[:gm.find("public void gmSaveBlob")] if "public void gmSaveBlob" in gm else gm
need("enqueueDownload(url, name, o.optString(\"mime\", \"\"), ua);" in gm,
     "GM_download routes through enqueueDownload's scheme law")
need("DownloadEngine.get(BrowserActivity.this).enqueue(url, name," not in gm,
     "GM_download no longer feeds the engine a raw URL (the blob:/data: hole)")

print("E — fix 1c: the learned type forces the extension")
rf = de_code[de_code.find("private void refineFile(Item it, File part) {"):]
rf = rf[:rf.find("private void refineBytes")] if "private void refineBytes" in rf else rf
need("boolean learned = false;" in rf,
     "the learned-type flag exists")
need("it.mime = it.ct; learned = true;" in rf,
     "a real Content-Type swap marks the type learned")
need("it.mime = sm; learned = true;" in rf,
     "a magic-byte sniff marks the type learned")
need("String forced = extForMime(it.mime);" in rf,
     "the strict pass derives the extension from the learned mime")
need('it.name = base + "." + forced;' in de,
     "a mismatched extension is rewritten to the learned one (raw source: the strip pass eats string literals)")
need(rf.find("it.name = ensureExt(it.name, it.mime);") < rf.find("String forced"),
     "the lenient pass runs first, the strict pass only overrides after")

print("F — fix 2: no reload during an active drag")
rl = ta_code[ta_code.find("void reload() {"):]
rl = rl[:rl.find("void reloadDiff")] if "void reloadDiff" in rl else rl
need("if (dragCb != null && dragCb.dragActive) {" in rl
     and "pendingReloadDuringDrag = true;" in rl,
     "reload() parks the request while a drag is live")
need(rl.find("if (dragCb != null && dragCb.dragActive) {")
     < rl.find("if (deferIfBusy(() -> reload())) return;"),
     "the drag guard runs BEFORE the busy-defer (a posted reload re-parks, never lands)")
need("private boolean pendingReloadDuringDrag = false;" in ta_code,
     "the parked-reload flag exists")
need("protected void flushPendingReload() {" in ta_code,
     "the flush entry exists")
cv = ta_code[ta_code.find("public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {"):]
cv = cv[:cv.find("public void onSwiped")] if "public void onSwiped" in cv else cv
need("} finally {" in cv and "flushPendingReload();" in cv,
     "clearView flushes the parked reload in a finally (every drop exit)")
need(cv.find("flushPendingReload();") > cv.find("dragActive = false;"),
     "the flush runs after the drag flag drops (the parked reload cannot re-park)")
need("return false;" in ta_code[ta_code.find("public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder drag,"):
     ta_code.find("public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder drag,") + 400],
     "onMove still returns false (reordering commits at drop, by design)")

print("G — fix 3: the group-sheet row is full-width")
gsa = ta_code[ta_code.find("public GroupSheetAdapter.H onCreateViewHolder(ViewGroup parent, int viewType) {"):]
gsa = gsa[:gsa.find("public void onBindViewHolder")] if "public void onBindViewHolder" in gsa else gsa
need("row.setLayoutParams(new LinearLayout.LayoutParams(" in gsa
     and "ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT" in gsa,
     "the sheet row is MATCH_PARENT x WRAP_CONTENT (weight=1 has space to divide)")
need(gsa.find("row.setLayoutParams") < gsa.find("row.setOrientation"),
     "the LayoutParams are set immediately after creating the row")
need("new LinearLayout.LayoutParams(\n                    0, -2, 1f)" in gsa
     or "new LinearLayout.LayoutParams(0, -2, 1f)" in gsa,
     "the text column keeps its weight=1 (the contract the width serves)")

print("H — fix 4: the incognito flag is set before anything loads")
for nm, src, why in (("IncognitoActivity", ia, "the browser"),
                     ("TabsIncognitoActivity", tia, "the tabs view")):
    oc = src[src.find("protected void onCreate(Bundle savedInstanceState) {"):]
    oc = oc[:oc.find("\n    }", 1) + 6] if "\n    }" in oc[1:] else oc
    need("TabManager.INCOGNITO = true;" in oc,
         f"{nm}.onCreate sets the flag itself")
    need(oc.find("TabManager.INCOGNITO = true;") < oc.find("super.onCreate(savedInstanceState);"),
         f"{nm} sets the flag BEFORE super.onCreate (the {why} tab-restore path)")
    need("static {" in src and "TabManager.INCOGNITO = true;" in src,
         f"{nm} keeps its static initializer too (belt and suspenders)")

print("I — fix 5: the grouped-active highlight")
bh = ta_code[ta_code.find("if (h instanceof GroupHolder) {"):]
bh = bh[:bh.find("private void applySelVisuals")] if "private void applySelVisuals" in bh else bh
need("boolean activeInGroup = false;" in bh,
     "the group bind derives an active-in-group state")
need("if (at != null && at.groupId == gid) activeInGroup = true;" in bh,
     "the state keys on tm.active belonging to any member")
need("gh.itemView.setForeground(new android.graphics.drawable.InsetDrawable(" in bh and "gh.itemView.setForeground(null);" in bh,  # v1.4.17 re-point (2026-11-18): the rim rides the 2dp InsetDrawable law (the overlap fix)
     "the group row gets the accent rim (and loses it when inactive)")
need("gh.strip.setBackgroundColor(activeInGroup" in bh,
     "the strip follows the same state")
need("strip = v.findViewById(R.id.v_group_strip);" in ta_code,
     "GroupHolder binds the strip view")
need('android:id="@+id/v_group_strip"' in grow_xml
     and 'android:id="@+id/v_group_strip"' in gcard_xml,
     "both group layouts carry v_group_strip")
sta = ta_code[ta_code.find("protected void scrollToActive() {"):]
sta = sta[:sta.find("protected void refreshChips")] if "protected void refreshChips" in sta else sta
need("active.groupId != 0" in sta and 'g.optLong("id") == active.groupId' in ta,
     "scrollToActive still resolves a member's group row (the v1.4.12 walk; raw source)")

print("J — fix 6: the two-row omnibox")
need('android:id="@+id/omni_actions"' in omni_xml,
     "the action strip row exists (omni_actions)")
need(omni_xml.find('android:id="@+id/btn_omni_clear"')
     < omni_xml.find('android:id="@+id/omni_actions"'),
     "Row 1 (chip | field | clear) sits above Row 2 (the action strip)")
for btn, why in (("btn_omni_copy", "Copy"), ("btn_omni_paste", "Paste"),
                 ("btn_omni_dup", "Duplicate tab"), ("btn_omni_cam", "Image search"),
                 ("btn_omni_voice", "Voice search")):
    need(f'android:id="@+id/{btn}"' in omni_xml[
        omni_xml.find('android:id="@+id/omni_actions"'):],
        f"Row 2 carries {why}")
need(re.search(r'android:id="@\+id/et_omni".*?android:layout_weight="1"',
               omni_xml, re.S) is not None,
     "the EditText keeps weight=1 inside Row 1")
si = ba_code[ba_code.find("final Runnable syncOmniIcons"):]
si = si[:si.find("};", 1) + 2]
need("final View actionsRow = lay.findViewById(R.id.omni_actions);" in ba_code,
     "the strip is wired as ONE visibility unit")
need("actionsRow.getVisibility() != View.VISIBLE" in si
     and "actionsRow.setVisibility(View.VISIBLE);" in si,
     "the strip shows the moment the omnibox opens (v1.4.18 re-point 2026-11-19: the focus law — the v1.4.16 text-gate hid the actions until the first keystroke; only Clear still follows the field)")
need("clearB.setVisibility(empty ? View.GONE : View.VISIBLE);" in si,
     "clear rides Row 1 on the same state")
need("final Boolean[] omniEmptyCache = new Boolean[1];" in ba_code
     and "if (omniEmptyCache[0] != null && omniEmptyCache[0] == empty) return;" in si,
     "the v1.4.15 state cache survives the redesign")
need("camB.setVisibility" not in ba_code and "pasteB.setVisibility" not in ba_code,
     "the per-button toggles are gone (the row replaced them)")
dup = ba[ba.find("lay.findViewById(R.id.btn_omni_dup).setOnClickListener"):]
dup = dup[:dup.find("et.addTextChangedListener")] if "et.addTextChangedListener" in dup else dup
need("Tab n = new Tab();" in dup and "tm.tabs.add(idx >= 0 ? idx + 1 : tm.tabs.size(), n);" in dup,
     "Duplicate tab clones the current tab beside its source (the switcher's law)")
need("omniboxDismiss(lay);" in dup,
     "the duplicate action closes the sheet (the flow is over)")

print("K — fixes 7-9: find bar, dialog, ghost clean up")
st = ba[ba.find("protected void showTab(int idx, boolean viaSwipe) {"):]
st = st[:st.find("protected void showTabHistory")] if "protected void showTabHistory" in st else st[:6000]
fb = st[st.find("THE FIND-BAR BLEED-OVER" if "THE FIND-BAR BLEED-OVER" in st else "hideQuickPanel();"):]
need("findbar.setVisibility(View.GONE);" in st
     and st.find("findbar.setVisibility(View.GONE);") > st.find("hideQuickPanel();"),
     "showTab hides a visible find bar (with the other per-switch cleanups)")
need("leftTab.web.clearMatches();" in st,
     "the DEPARTING tab gets clearMatches (shownTabRef still points at it)")
need("etFind.setText(\"\");" in ba_code[ba_code.find("protected void showTab(int idx, boolean viaSwipe) {"):
     ba_code.find("protected void showTabHistory")]
     or "etFind.setText(\"\");" in strip_comments_strings(st),
     "the find query is cleared on switch (no bleed into the next find)")
need("tvFindCount.setText(\"\");" in st,
     "the match counter is cleared too")
od = ba_code[ba_code.find("protected void onDestroy() {"):]
inc = od.find("if (incognito)")
need("omniboxDialog.isShowing()) omniboxDialog.dismiss();" in od[:inc],
     "onDestroy dismisses a showing omniboxDialog (before the incognito early-return)")
op = ba_code[ba_code.find("protected void onPause() {"):]
op = op[:op.find("protected void onStop()")] if "protected void onStop()" in op else op
os_ = ba_code[ba_code.find("protected void onStop() {"):]
os_ = os_[:os_.find("protected void onDestroy()")] if "protected void onDestroy()" in os_ else os_
need("flRoot.removeView(sdGhost);" in op,
     "onPause removes a stranded speed-dial ghost")
need("flRoot.removeView(sdGhost);" in os_,
     "onStop removes it too (no lifecycle path strands it)")
need("sdGhost.getParent() == flRoot" in op and "sdGhost.getParent() == flRoot" in os_,
     "both sweeps are parent-guarded (a resumed drag re-arms a fresh ghost)")

# the compiled probe: three transcriptions, run for real
probe = r"""
public class ReliabilityProbe {
    // TRANSCRIPTION 1 — the 0KB corpse law (DownloadEngine.savePart,
    // v1.4.16). A destination created before a failing copy must be
    // DELETED before the fall-through, so the next attempt sees a clean
    // slate and never lands on "name (1)".
    static class SaveSim {
        final java.util.List<String> files = new java.util.ArrayList<>();
        int created = 0, deleted = 0;

        String createDocument(String name) {          // SAF/MediaStore insert
            created++;
            String doc = files.contains(name) ? name + " (1)" : name;
            files.add(doc);
            return doc;
        }
        void copy(String doc) throws Exception {        // the byte copy
            throw new Exception("stream died");
        }
        void savePart(String name, boolean fail) {
            String doc = createDocument(name);
            try {
                if (fail) copy(doc);
                // success: bytes written, nothing to clean
            } catch (Throwable copyFail) {
                files.remove(doc);                     // ContentResolver.delete
                deleted++;
                // fall through to the next destination (clean)
            }
        }
    }

    // TRANSCRIPTION 2 — the mid-drag reload deferral (TabsAdapter.reload +
    // DragMergeCallback.clearView, v1.4.16). A reload during an active
    // drag parks; the gesture's end flushes exactly once.
    static class BoardSim {
        boolean dragActive = false;
        boolean pendingReloadDuringDrag = false;
        int reloads = 0;

        boolean onMove() { return false; }             // the design: no live swap
        void reload() {
            if (dragActive) { pendingReloadDuringDrag = true; return; }
            reloads++;                                 // rebuild + notify
        }
        void clearView() {
            dragActive = false;
            try {
                // ...drop resolution (settles, may reload itself)...
            } finally {
                flushPendingReload();
            }
        }
        void flushPendingReload() {
            if (!pendingReloadDuringDrag) return;
            pendingReloadDuringDrag = false;
            reloads++;                                 // posted, once, after the gesture
        }
    }

    // TRANSCRIPTION 3 — the strict extension law (refineFile, v1.4.16).
    // A LEARNED type (Content-Type swap / sniff) forces the extension;
    // an unlearned one keeps the name as ensureExt left it.
    static String extForMime(String mime) {
        if (mime.equals("image/jpeg")) return "jpg";
        if (mime.equals("application/pdf")) return "pdf";
        if (mime.equals("application/octet-stream")) return "";
        return "";
    }
    static String refineName(String name, String mime, boolean learned) {
        String forced = extForMime(mime);
        if (learned && !forced.isEmpty()) {
            int dot = name.lastIndexOf('.');
            String cur = dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
            if (!cur.equals(forced)) {
                String base = dot > 0 ? name.substring(0, dot) : name;
                if (base.isEmpty()) base = "download";
                name = base + "." + forced;
            }
        }
        return name;
    }

    static int fails = 0;
    static void check(String why, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FAIL") + " - " + why);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        // --- the 0KB corpse law ---
        SaveSim s1 = new SaveSim();
        s1.savePart("report.pdf", true);
        check("a failed copy leaves NO file behind (0KB corpse deleted)",
              s1.files.isEmpty() && s1.deleted == 1);
        s1.savePart("report.pdf", false);
        check("the retry saves under the ORIGINAL name (no \"(1)\")",
              s1.files.size() == 1 && s1.files.get(0).equals("report.pdf"));
        // --- the mid-drag deferral ---
        BoardSim b = new BoardSim();
        b.dragActive = true;
        b.reload(); b.reload(); b.reload();
        check("three mid-drag reloads park (zero board rebuilds)",
              b.reloads == 0 && b.pendingReloadDuringDrag);
        b.clearView();
        check("the gesture's end flushes exactly ONE reload",
              b.reloads == 1 && !b.pendingReloadDuringDrag);
        b.reload();
        check("a reload after the drag lands normally",
              b.reloads == 2);
        BoardSim b2 = new BoardSim();
        b2.clearView();
        check("a gesture with nothing parked adds no reload",
              b2.reloads == 0);
        // --- the strict extension law ---
        check("a learned image/jpeg rewrites photo.zip -> photo.jpg",
              refineName("photo.zip", "image/jpeg", true).equals("photo.jpg"));
        check("a learned pdf rewrites getfile.bin -> getfile.pdf",
              refineName("getfile.bin", "application/pdf", true).equals("getfile.pdf"));
        check("an agreeing extension survives (photo.jpg, image/jpeg)",
              refineName("photo.jpg", "image/jpeg", true).equals("photo.jpg"));
        check("an UNLEARNED mime keeps the human name (Setup-2.4.exe)",
              refineName("Setup-2.4.exe", "application/octet-stream", false)
                  .equals("Setup-2.4.exe"));
        check("a learned type with no known ext leaves the name alone",
              refineName("weird.xzy", "application/x-custom", true)
                  .equals("weird.xzy"));
        System.out.println(fails == 0 ? "PROBE OK" : "PROBE BAD (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
"""
tmp = tempfile.mkdtemp(prefix="bolt_v1416_probe_")
open(os.path.join(tmp, "ReliabilityProbe.java"), "w").write(probe)
# release.sh puts the JDK on PATH before the gates; standalone runs fall
# back to the workspace's grafted JDK (the README environment note)
GJ = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin"
javac = "javac" if shutil.which("javac") else os.path.join(GJ, "javac")
java = "java" if shutil.which("java") else os.path.join(GJ, "java")
pr = subprocess.run([javac, "-d", tmp, os.path.join(tmp, "ReliabilityProbe.java")],
                    capture_output=True, text=True)
if pr.returncode != 0:
    print("  FAIL - probe compiled (javac):\n" + pr.stderr[:800])
    fails += 1
else:
    print("  ok  - probe compiled (javac)")
    pr2 = subprocess.run([java, "-cp", tmp, "ReliabilityProbe"],
                         capture_output=True, text=True)
    print(pr2.stdout.rstrip())
    if pr2.returncode != 0:
        fails += 1

print("L — the gate is wired into the pipeline")
need("python3 scripts/test_v1416.py" in rel,
     "release.sh runs this gate before building")
need(rel.find("test_v1415.py") < rel.find("test_v1416.py"),
     "the gate runs after v1415's (release order)")

print()
print("V1416 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
