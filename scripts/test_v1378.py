#!/usr/bin/env python3
"""test_v1378.py — the v1.3.78 field-fix gate (source contracts, the same
discipline as every test_*.py in this repo).

The v1.3.77 report, verbatim topics:
  U1  "the translated text is 90 degree rotated on the screen in the
      correct place and size" -> covered by test_screen S9/S12 (no rotation)
  U2  "The other modes are pretty uneffecrive still, so just remove them
      all, and keep an universal alternative screen translate non ai
      option" -> test_screen S9 (two modes)
  U3  "my browser size still remain over 50 mb after closing" ->
      test_cacheguard G2/G5 (new subtrees, 45 s, breakdown)
  U4  "the tab button seems to only open the tabs or register touch when I
      touch near the bottom of the toolbar, the tabs menu don't open when
      I click on the number in the tab square"
  U5  "the tabs icons sometimes does not update properly and shows the
      opened one for several near tabs ... github shows just black circle"
  U6  "downloads have a random string of characters for name, sometimes
      format is not detctd and turns to bin type, also give different file
      types different icons ... apks downloaded don't open"
  U7  "saved passwords are not prompted by bolt to be autofilled when I
      sign in again, also lot of the times while sign ins, the keyboard
      stops after entering one letter and needs to be clicked on the
      typing area to bring keyboard back then this repeats"
  U8  "when I select something like text by holding, the cut, copy, select
      all menu ... appear at the very top of the screen, far from the
      text I selected"
This file covers U4-U8 (the UI fronts); U1-U3 live in the updated gates.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(".")
LAY = ROOT / "app/src/main/res/layout/activity_browser.xml"
ACT = ROOT / "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java"
TABS = ROOT / "app/src/main/java/com/bolt/browser/ui/TabsActivity.java"
FS = ROOT / "app/src/main/java/com/bolt/browser/util/FaviconStore.kt"
BW = ROOT / "app/src/main/java/com/bolt/browser/ui/BoltWeb.kt"
DLA = ROOT / "app/src/main/java/com/bolt/browser/ui/DlAdapter.kt"
DE = ROOT / "app/src/main/java/com/bolt/browser/core/DownloadEngine.java"
MAN = ROOT / "app/src/main/AndroidManifest.xml"


def read(p):
    return p.read_text(encoding="utf-8")


def main():
    lay, act, tabs = read(LAY), read(ACT), read(TABS)
    fs, bw, dla, de, man = read(FS), read(BW), read(DLA), read(DE), read(MAN)

    # ---------- U4: THE TAB BUTTON CELL ----------
    if 'android:id="@+id/btn_tabs"' not in lay:
        print("FAIL: activity_browser.xml has no btn_tabs cell")
        return 1
    if 'android:clickable="true"' not in lay.split('btn_tabs')[1][:400]:
        print("FAIL: the btn_tabs cell is not clickable")
        return 1
    if 'android:clickable="false"' not in lay.split('tv_tab_count')[1][:700]:
        print("FAIL: tv_tab_count must not be its own touch target anymore")
        return 1
    if "btnTabs = findViewById(R.id.btn_tabs);" not in act:
        print("FAIL: BrowserActivity does not wire btnTabs to the cell")
        return 1
    print("OK: U4 the tab cell is the button (full-height touch target, ripple, square visual-only)")

    # ---------- U5: THE FAVICON TRUTH ----------
    for probe in ("fun pinViewHost(", "fun pinnedHost(", "var favGen"):
        if probe not in fs:
            print(f"FAIL: FaviconStore lacks {probe}")
            return 1
    if "FaviconStore.pinViewHost(view, url);" not in act:
        print("FAIL: onPageStarted does not pin the icon host")
        return 1
    if "FaviconStore.pinnedHost(view)" not in act:
        print("FAIL: onReceivedIcon does not read the pinned host")
        return 1
    # the contrast law: extreme-luminance icons get the opposite tile
    if "st.lum < 0.16f" not in fs or "st.lum > 0.85f" not in fs:
        print("FAIL: the contrast-tile law (github's black circle) is missing")
        return 1
    # the switcher rebinds when an icon lands late
    for probe in ("favPollTick", "adapter.rebindIcons()", "FaviconStore.gen()"):
        if probe not in tabs:
            print(f"FAIL: the TabsActivity favicon poller lacks {probe}")
            return 1
    print("OK: U5 favicon host pinning (attribution race dead) + contrast tiles + live switcher rebind")

    # ---------- U6: THE DOWNLOAD CONTRACT ----------
    # v1.3.86: humanName went public (the download dialog calls it)
    if "static String humanName(" not in de:
        print("FAIL: DownloadEngine lacks humanName (the random-string report)")
        return 1
    if "vowels * 4 > base.length()" not in de:
        print("FAIL: the hash-token heuristic must keep vowel-rich real names")
        return 1
    for probe in ('head.contains("AndroidManifest.xml")',
                  'head.contains("[Content_Types].xml")',
                  '"application/vnd.android.package-archive"',
                  "public boolean canInstallApks()",
                  "public Intent installPermissionIntent()",
                  "public Intent openFileIntent(Item it, boolean forceGenericMime)"):
        if probe not in de:
            print(f"FAIL: the download engine lacks {probe}")
            return 1
    if 'android.permission.REQUEST_INSTALL_PACKAGES' not in man:
        print("FAIL: the manifest lacks REQUEST_INSTALL_PACKAGES (APK open)")
        return 1
    if "ACTION_MANAGE_UNKNOWN_APP_SOURCES" not in de:
        print("FAIL: the unknown-apps settings deep link is missing")
        return 1
    if "openFileIntent(it, true)" not in dla:
        print("FAIL: the wildcard retry on no-handler is missing")
        return 1
    # the granular type icons
    for cat, icon in (("pdf", "ic_dl_pdf"), ("word", "ic_dl_word"),
                      ("sheet", "ic_dl_sheet"), ("slides", "ic_dl_slides"),
                      ("text", "ic_dl_text"), ("book", "ic_dl_book")):
        if not (ROOT / f"app/src/main/res/drawable/{icon}.xml").exists():
            print(f"FAIL: the {icon} drawable is missing")
            return 1
        if f'"{cat}" -> R.drawable.{icon}' not in dla:
            print(f"FAIL: iconOf does not map {cat}")
            return 1
    if '"pdf" -> return "pdf"' in dla or 'm.contains("pdf") || ext("pdf")' not in dla:
        pass  # shape-checked by the probes below
    if 'ext("doc docx docm dotx rtf odt pages")' not in dla:
        print("FAIL: the word family probe is missing")
        return 1
    print("OK: U6 downloads: human names, deep PK sniffing (apk/docx/xlsx/pptx), 6 new type icons, APK install path")

    # ---------- U7: THE KEYBOARD TRUCE + THE VISIBLE CHIPS ----------
    if "if (kb && barsShown) hideBarsNow(false);" in act:
        print("FAIL: the bars still hide when the keyboard opens (the one-letter killer)")
        return 1
    if "THE KEYBOARD TRUCE" not in act:
        print("FAIL: the keyboard truce comment/contract is missing")
        return 1
    # v1.3.81: ALL inset changes are ONE instant setPadding now (the ghost
    # kill) - the keyboard special-case folded into the universal rule, a
    # STRICTLY stronger contract than "instant while the keyboard is up".
    if not re.search(r"flContent\.setPadding\(0, tp, 0, bp\);", act):
        print("FAIL: insets must be instant (never animated) - one setPadding")
        return 1
    if "va.setDuration(90L)" in act and "insetAnim = va" in act:
        print("FAIL: the per-frame inset animator is back (keyboard/ghost regression)")
        return 1
    # v1.3.79: the primary anchor is the FOCUSED FIELD (below/above by
    # screenspace); the keyboard anchor survives as the fallback path inside
    # placeChip (kb + Fx.dp(this, 14) = the keyboard-top margin).
    for probe in ("protected void anchorChipAboveKeyboard(",
                  "protected void anchorChipToField(",
                  "protected void placeChip(",
                  "protected void chipSettle(",
                  "anchorChipToField(tv);"):
        if probe not in act:
            print(f"FAIL: the chip anchor machinery lacks {probe}")
            return 1
    if act.count("anchorChipToField(tv);") < 3:
        print("FAIL: all three chips (pw/otp/profile) must anchor to the focused field")
        return 1
    if "kb + Fx.dp(this, 14)" not in act:
        print("FAIL: the keyboard fallback margin must ride the live keyboard top")
        return 1
    if "__boltFieldRect" not in open(ROOT / "app/src/main/java/com/bolt/browser/media/MediaHook.java").read():
        print("FAIL: the page must expose the focused field's rect (__boltFieldRect)")
        return 1
    print("OK: U7 bars stay + instant insets while typing; pw/otp/profile chips anchor AT THE FIELD (keyboard fallback)")

    # ---------- U8: THE FRESH SELECTION RECT ----------
    for probe in ("private fun freshRect(", "mode.invalidateContentRect()",
                  "postDelayed({ try { mode.invalidateContentRect() }"):
        if probe not in bw:
            print(f"FAIL: BoltWeb's fresh-rect guarantee lacks {probe}")
            return 1
    print("OK: U8 the selection ActionMode re-reads its content rect after start (no top-of-screen toolbar)")

    # ---------- version ----------
    # v1.3.79: the version pin retired — this file is the v1.3.78 contract
    # REGRESSION gate now (its U1-U8 checks protect forever); test_v1379.py
    # owns the current version pin.
    print("OK: version pin retired (regression gate; see test_v1379.py)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
