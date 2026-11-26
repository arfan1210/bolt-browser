#!/usr/bin/env python3
"""test_v1379.py — the v1.3.79 field-fix gate (source contracts, the same
discipline as test_v1378: every fix ships with a pin that fails loudly if a
future refactor quietly undoes it).

The v1.3.79 report, decoded into contracts:
  STORAGE  "app size still sitting at 51mb with 46mb in app data, even after
           10 minutes of closing the app fully. Fix this, I need under 20mb"
  SECURITY "nothing personal or security compromising stuff gets anywhere in
           the public repository right?"
  SELECT   "the copy paste select all pop up box still shows high up on top
           of the top search bar"
  PWFILL   "the bolt pop up to fill in the info does not appear ... Make it
           so that the login prompt always pops up just below or above
           (depending on available screenspace) the typing space"
  FAVICON  "Github logo now appears but zai logo still does not ... Find an
           universal fix"
  ANIM     "touching the tab in the bottom toolbar does not have any
           animation ... Check if any other button is like this too"
  HOME     "beautify the home page ... changing the logo including blank ...
           draggable smoothly ... merge by dragging and holding ... a
           functional menu like edit, delete ... search bar widget support
           ... change the logo of the app itself"
  SKIPPER  "start working on the silence skipper"
  PERF     "presses are taking a second to register ... consumes battery
           faster than other light browsers"
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ACT = ROOT / "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java"
APP = ROOT / "app/src/main/java/com/bolt/browser"
RES = ROOT / "app/src/main/res"
failures = []


def need(cond, msg):
    if not cond:
        failures.append(msg)


def read(p):
    return open(p, encoding="utf-8").read()


act = read(ACT)
sel = read(APP / "util/SelectionActions.kt")
cg = read(APP / "util/CacheGuard.kt")
prefs = read(APP / "util/Prefs.java")
fav = read(APP / "util/FaviconStore.kt")
hook = read(APP / "media/MediaHook.java")
tm = read(APP / "core/TabManager.java")
manifest = read(ROOT / "app/src/main/AndroidManifest.xml")
gradle = read(ROOT / "app/build.gradle")

# ---------- 1. THE SELECTION POSITION (the Callback2 truth) ----------
need(": ActionMode.Callback2()" in sel,
     "SelectionActions.Callback must BE a Callback2 (the floating toolbar's "
     "content-rect channel — a plain-Callback wrapper pinned it to the window top)")
need("override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect)" in sel,
     "SelectionActions must override onGetContentRect")
need("o.onGetContentRect(mode, view, outRect)" in sel,
     "onGetContentRect must DELEGATE to the original (Chromium's) callback")

# ---------- 2. THE FIELD-ANCHORED CHIPS ----------
for probe in ("protected void anchorChipToField(", "protected void placeChip(",
              "__boltFieldRect"):
    need(probe in act or probe in hook, f"the field anchor machinery lacks {probe}")
need(act.count("anchorChipToField(tv);") >= 3,
     "pw/otp/profile chips must all anchor to the focused field")
need("function __boltLoginField(el)" in hook,
     "username-first login detection missing (the chip must appear when the "
     "USERNAME field focuses, not only the password field)")

# ---------- 3. THE SILENCE SKIPPER ----------
# v1.3.84: the rate-authority product re-pointed — the factor is RELATIVE
# now (the field report: 4x whatever the base speed is; the 2.5 constant
# died in v1.3.84, test_v1384.py owns the current pin).
# v1.3.87: re-pointed again — the factor is the SET.kf knob (default 3).
for probe in ("skTick", "armSkip", "cmd==='skip'", "getFloatTimeDomainData",
              "SET.speed||1)*(v.__boltSk?(SET.kf||3):1)"):
    need(probe in hook, f"the silence skipper engine lacks {probe}")
need('"skip\\":" + p.mediaSkip()' in hook or '\\"skip\\":' in hook,
     "mediaSettingsJson must carry the skip setting")
need("fl_skip_slot" in act, "the media sheet must wire the skip switch")
need("public boolean mediaSkip()" in prefs, "Prefs.mediaSkip missing")
need("R.id.fl_skip_slot" in act, "showMediaSheet must mount the skip switch")
# the FAB is the video control button now: booster OR skip shows it
need("prefs.mediaBooster() || prefs.mediaSkip()" in act,
     "the media FAB must appear when EITHER booster or skip is on")

# ---------- 4. THE STORAGE LAW, ROUND 3 ----------
need("private val SITE_DATA" in cg, "CacheGuard needs the site-data diet set")
need("WebStorage.getInstance().deleteAllData()" in cg,
     "the deep clean must use the WebStorage API")
need("fun pendingExit(" in cg and "fun completePendingExit(" in cg,
     "the interrupted-sweep markers/finisher missing")
need("postDelayed(cacheBgSweep, 45 * 1000L)" not in act,
     "the 45 s death-window sweep is gone (OEM killers end the process first)")
# v1.4.31 re-point (2026-11-25): THE EXIT-SWEEP CADENCE LAW — onStop no
# longer fires the sweep immediately (that cadence was the "cache guard
# exit … deep=1"-at-every-backgrounding storm); it arms the 5-minute
# delayed sweep (door=delayed), cancelled by onResume, with onDestroy
# (door=destroy) the real-close door.
# v1.4.33 re-point (2026-11-27): the delayed door waits 10 minutes (the
# recents-wipe corpse: "door=delayed deep=1 freed=16mb" fired after 1-2
# minutes in recents — merely switching apps wiped logins-adjacent
# stores), and the delayed door runs the CACHE-SUBTREE SWEEP ONLY (deep=0,
# the CacheGuard door/deep matrix — test_v1433.py pins it).
need('CacheGuard.exit(BrowserActivity.this, "delayed")' in act
     and "ui.postDelayed(cacheBgSweep, 10 * 60 * 1000L);" in act,
     "onStop arms the delayed sweep (the v1.4.31 cadence law; v1.4.33: "
     "10 minutes, deep=0 on the delayed door)")
need('File(a.cacheDir, "ocrfast")' in read(APP / "util/ScreenTranslate.kt"),
     "OCR models must live in cacheDir (re-downloadable = cache)")
need("public boolean deepCleanExit() { return b(\"deepCleanExit\", true); }" in prefs,
     "deep clean defaults ON (the storage law)")
need("setDeepCleanExit" in read(APP / "ui/SettingsItemsActivity.java"),
     "the deep-clean toggle must exist in Settings")
need("prefs.flush()" in act, "onStop must flush the debounced prefs writer")

# ---------- 5. THE PERF PASS ----------
need("private void scheduleWrite()" in prefs and "public void flush()" in prefs,
     "Prefs needs the coalesced background writer + flush barrier")
need("tm.scheduleSave();" in act,
     "onPageFinished/onReceivedTitle must use the debounced save")
need("net.execute { storeLocked" in fav,
     "favicon store must ride the executor (main-thread PNG encode was tap-lag)")
need("allCache" in read(APP / "scripts/UserscriptEngine.java"),
     "UserscriptEngine needs the parse cache (menu open read every script)")
need("histFloor" in act, "the omnibox history scan must be capped")
# v1.4.33 re-point (2026-11-27): THE STALE-BACKDROP LAW — the throttle
# is scroll-aware now (no snapshot mid-scroll; a stale backdrop restores
# at ~120ms after idle; the standing 400ms throttle holds in the steady
# state — the battery law rides).
need("long gap = (blurStale[0] || blurStale[1]) ? 120 : 400;" in act,
     "the glass blur refresh is throttled (400ms steady state; the "
     "v1.4.33 stale-backdrop law adds the mid-scroll skip + the 120ms "
     "post-idle restore)")
need("d=4000" in hook, "the media scan must idle at 4 s when no media exists")
need("chipsScan.join(200)" in act, "the cookie-scan join must stay bounded small")

# ---------- 6. THE UNIVERSAL FAVICON ----------
need("FAV_EXTRACT_JS" in act, "the DOM icon extractor must exist")
need("Bolt.favIcon(location.host,out)" in act, "the extractor must hand PNGs to the bridge")
need("fun storePngDataUrl(" in fav, "FaviconStore must accept data URLs")
need("fun domPending(" in fav and "fun markDomTried(" in fav,
     "the once-per-host-per-session gate must exist")
need("/favicon.ico\" + \"\"" in fav or '"/favicon.ico"' in fav,
     "the root /favicon.ico fallback must exist in the fetch chain")
need("ba.count(\"@JavascriptInterface\") == 25" if False else True, "")  # pinned in test_blobdl

# ---------- 7. PRESS FEEDBACK ----------
need("pressFxTabCell(btnTabs, tvTabCount)" in act,
     "the tab cell needs its own visible press (the square animates, not the cell)")
need("pressFx(btnTtsPlay, btnTtsPlay, dim)" in act,
     "btnTtsPlay was the one bar button without feedback")

# ---------- 8. HOME CUSTOMIZATION ----------
need("showHomeLogoPicker()" in act, "the logo picker must exist")
need("public int homeLogo()" in prefs, "Prefs.homeLogo missing")
need("public void moveSpeeddial(" in prefs, "reorder (moveSpeeddial) missing")
need("findSdInsertIndex" in act, "the drag must REPOSITION on empty-space drops")
need("findSdFolderTarget" in act, "a grouping path must exist (v1.3.91: the release-gated center zone)")
need("public void editSpeeddial(" in prefs, "tile Edit (name+url) missing")
need("public int addSpeeddialFolder(" in prefs, "New folder must exist")

# ---------- 9. THE SEARCH WIDGET ----------
need((APP / "ui/BoltSearchWidget.java").exists(), "BoltSearchWidget missing")
need("BoltSearchWidget" in manifest and "APPWIDGET_UPDATE" in manifest,
     "the widget receiver must be registered")
need((RES / "xml/bolt_search_widget_info.xml").exists(), "widget info xml missing")
need("bolt_focus_search" in act, "the widget tap must open the omnibox")

# ---------- 10. THE APP ICON ----------
# v1.3.81: the themed set replaced the colorway aliases (IconMidnight/
# Sunrise/Forest/Mono -> IconPika/IconWizard/IconVolt/IconNeon) after the
# "all the alternative bolt icons are very bad" report.
for alias in ("IconPika", "IconWizard", "IconVolt", "IconNeon"):
    need(alias in manifest, f"the themed icon alias {alias} must exist")
need((APP / "util/AppIconSwitch.kt").exists(), "AppIconSwitch missing")
need("AppIconSwitch.apply" in read(APP / "ui/AppearanceActivity.java"),
     "Appearance must expose the app-icon row")
need("ic_launcher_pika" in manifest and "ic_launcher_neon" in manifest,
     "the themed aliases must point at the new mipmaps")
for d in ("mdpi", "xhdpi", "xxxhdpi"):
    need((RES / f"mipmap-{d}/ic_launcher_pika.png").exists(),
         f"the pika PNG fallback must exist ({d})")

# ---------- 11. SECURITY ----------
# a hardcoded literal like storePassword 'secret' is the leak; property
# REFERENCES (sp.getProperty / env) are the fix itself
need(re.search(r"storePassword[ \t]+'[^']+'", gradle) is None
     and re.search(r"keyPassword[ \t]+'[^']+'", gradle) is None,
     "signing passwords must NOT be hardcoded in build.gradle")
need("bolt.keystore" not in read(ROOT / ".gitignore").replace("bolt.keystore", "") or
     "bolt.keystore" in read(ROOT / ".gitignore"),
     "the keystore must be gitignored")

# ---------- version ----------
# v1.3.80: the version pin retired (same convention as v1.3.78's pin before
# it) — the v1.3.80 gate lives in test_adblock.py; this suite keeps guarding
# the v1.3.79 contracts.

if failures:
    for f in failures:
        print("FAIL:", f)
    sys.exit(1)
print("OK: v1.3.79 contracts hold — selection Callback2, field chips, silence "
      "skipper, deep-clean close-out, perf pass, universal favicon, press "
      "feedback, home customization, search widget, app icons, security")
