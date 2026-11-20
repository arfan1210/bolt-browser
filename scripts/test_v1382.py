#!/usr/bin/env python3
"""v1.3.82 gate — the resurrection release (one gate per contract).

Field report (v1.3.81):
  1. "The app hid itself from launcher and does not show any option to
     open it from settings either."
  2. "Also the size is still 50mb, it still did not reduce after
     updating it."

Contracts pinned here (E1-E6):
  E1 THE LAUNCHER GUARD: component-enabled state PERSISTS ACROSS UPDATES
     (the v1.3.81 colorway-alias removal dropped the alias's setting but
     the main activity's explicit DISABLE survived → zero launcher
     components → invisible app, null launch intent, no Settings Open
     button). The manifest declares UpdateHealReceiver for
     MY_PACKAGE_REPLACED (the ONLY code that runs while un-openable) and
     LauncherGuard.heal resets the default face when stranded.
  E2 ENABLE-FIRST FLIP: AppIconSwitch.apply enables the chosen face
     BEFORE disabling the others (two passes) — the one-pass loop could
     crash between "main disabled" and "alias enabled", stranding the app.
  E3 THE UPDATE-TIME SWEEP: the receiver runs the OCR model diet and
     CacheGuard.exit — an APK install previously ran NO Bolt code, so an
     OEM-killed session's unswept caches sat through the update
     ("did not reduce after updating").
  E4 ALLOWLIST EXTENSIONS: SITE_DATA gains "File System" (Chromium's
     per-site file store — a real grower, site data so it rides the deep
     toggle) and "Session Storage"; SUBTREES gains "Optimization Guide"
     (the provider's prediction-model cache). The STORE/CACHE discipline
     holds: File System must NOT be in SUBTREES (test_cacheguard pins
     that too).
  E5 STARTUP HEAL: BoltApp spawns the launcher-guard thread in the main
     process (crash-mid-flip and missed-broadcast repair paths).
  E6 THE STORAGE METER: Settings → Privacy carries the Storage row with
     the live report (CacheGuard.report) + "Clean now" (CacheGuard.exit)
     — the storage saga stops being blind from the field side.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app/src/main/java/com/bolt/browser"

failures = []


def need(cond, msg):
    if not cond:
        failures.append(msg)
        print(f"  FAIL {msg}")


guard = (APP / "util/LauncherGuard.kt").read_text(encoding="utf-8")
icons = (APP / "util/AppIconSwitch.kt").read_text(encoding="utf-8")
cg = (APP / "util/CacheGuard.kt").read_text(encoding="utf-8")
appj = (ROOT / "app/src/main/java/com/bolt/browser/BoltApp.java").read_text(encoding="utf-8")
items = (APP / "ui/SettingsItemsActivity.java").read_text(encoding="utf-8")
st = (APP / "util/ScreenTranslate.kt").read_text(encoding="utf-8")
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

print("E1 — the launcher guard (the resurrection path)")
need(".util.UpdateHealReceiver" in manifest and "MY_PACKAGE_REPLACED" in manifest,
     "the manifest must declare UpdateHealReceiver for MY_PACKAGE_REPLACED")
need("class UpdateHealReceiver : BroadcastReceiver()" in guard,
     "UpdateHealReceiver must be a BroadcastReceiver")
need("LauncherGuard.heal(app)" in guard,
     "the receiver must run LauncherGuard.heal")
need(re.search(r"COMPONENT_ENABLED_STATE_DEFAULT", guard) and
     "stranded->default" in guard,
     "heal must reset the stranded state to the DEFAULT (manifest) face")
need("prefs.setAppIcon(0)" in guard,
     "the stranded heal must reset the pref to the default face")
need(re.search(r"count == 1 && enabled\[pref\]", guard),
     "the healthy state (exactly the pref's face on) must early-return")
need("@Synchronized" in guard,
     "heal must be synchronized (startup thread + update receiver race)")

print("E2 — the enable-first flip")
apply_body = icons[icons.index("fun apply"):]
need(apply_body.index("COMPONENT_ENABLED_STATE_ENABLED") <
     apply_body.index("COMPONENT_ENABLED_STATE_DISABLED"),
     "apply must ENABLE the chosen face BEFORE disabling any other")
need(re.search(r"for \(i in 0 until COUNT\) \{\s*if \(i == idx\) continue", icons),
     "the disable pass must skip the chosen face (two-pass flip)")
need("@JvmStatic\n    fun component(ctx: Context, idx: Int): ComponentName?" in icons,
     "the component resolver must exist for the guard's walk")

print("E3 — the update-time sweep")
need("ScreenTranslate.modelDiet(app)" in guard and "CacheGuard.exit(app)" in guard,
     "the receiver must run the model diet AND the close-out sweep")
need(re.search(r"@JvmStatic\s*\n\s*fun modelDiet\(a: Context\)", st),
     "modelDiet must be public + @JvmStatic for the receiver")

print("E4 — the allowlist extensions")
m = re.search(r"private val SITE_DATA = arrayOf\((.*?)\)", cg, re.S)
need(m and '"File System"' in m.group(1) and '"Session Storage"' in m.group(1),
     "SITE_DATA must carry File System + Session Storage (deep-clean set)")
m2 = re.search(r"private val SUBTREES = arrayOf\((.*?)\)", cg, re.S)
need(m2 and '"Optimization Guide"' in m2.group(1),
     "SUBTREES must carry Optimization Guide (the model cache)")
need(not (m2 and '"File System"' in m2.group(1)),
     "File System is a STORE — it must never be a plain cache subtree")
mid = re.search(r"private val MID_SET = arrayOf\((.*?)\)", cg, re.S)
need(not (mid and ('"File System"' in mid.group(1) or
                   '"Optimization Guide"' in mid.group(1))),
     "the new subtrees are exit/startup-only — never mid-session")

print("E5 — the startup heal")
need("LauncherGuard.heal(BoltApp.this)" in appj,
     "BoltApp must run the launcher guard at process start")
seg = appj[appj.index("bolt-purge"):]
need("bolt-launcher-guard" in seg,
     "the startup heal rides its own thread")
need(appj.index("if (!pn.endsWith(\":incognito\"))") < appj.index("LauncherGuard.heal"),
     "the heal must live in the main-process branch")

print("E6 — the storage meter")
need("showStorageReport" in items and "Storage" in items,
     "the Privacy screen must carry the Storage row")
need("CacheGuard.report(this)" in items and "CacheGuard.exit(this)" in items,
     "the dialog must show the live report and Clean now must sweep")
need(re.search(r"@JvmStatic\s*\n\s*fun report\(ctx: Context\): String", cg),
     "CacheGuard.report must be the public blocking measurer")

print("version pin")
# v1.3.86: the version pin re-pointed again (the newest gate owns the
# current pin — test_v1384.py; the E1-E6 contracts above still hold: the
# guard, the enable-first flip, the sweep wiring, the allowlists, the
# startup heal, the storage meter).
need('versionName "1.4.23"' in gradle and "versionCode 137" in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "build.gradle must be bumped to 1.3.98 / 111 (test_v1386.py owns the pin)")

print()
if failures:
    print(f"v1.3.82 gate: {len(failures)} FAILURES")
    sys.exit(1)
print("OK: v1.3.82 contracts hold — launcher guard + update self-heal, "
      "enable-first flip, update-time sweep, allowlist extensions, "
      "startup heal, the storage meter")
