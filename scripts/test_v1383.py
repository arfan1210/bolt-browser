#!/usr/bin/env python3
"""v1.3.83 gate — the manifest resurrection (one gate per contract).

Field report (v1.3.82): "App still not able to be opened or icon in the
launcher. After installing/updating, I only get option Done, not open."

WHY v1.3.82'S RECEIVER COULD NOT FIRE (the web-researched platform law,
pinned here so it is never forgotten): MY_PACKAGE_REPLACED "is sent
directly to the application, but only if the application was upgraded
while it was in a started state (NOT IN A STOPPED STATE)". The stranded
device's last launch was v1.3.80; un-openable through v1.3.81/82, likely
force-stopped while the user hunted for an Open button. No runtime heal
can run in the stopped state — the packaged manifest is the only channel
that does.

Contracts pinned here (F1-F8):
  F1 THE RESURRECTION ALIAS: .ui.IconDefault is declared in the manifest,
     enabled=true, exported=true, targets BrowserActivity, carries the
     default icon AND both the MAIN/LAUNCHER and http/https VIEW filters.
     A component name that never existed in any earlier APK carries NO
     persisted component-enabled state — it ships ENABLED at install: the
     icon reappears with ZERO code execution, even stopped.
  F2 THE ACTIVITY DEMOTED: BrowserActivity is a self-closing <activity/>
     with NO intent-filters — the v1.3.79-82 era left an explicit DISABLE
     persisted on that exact component on the field device; no manifest
     filter may ever hang on it again.
  F3 THE NEVER-AGAIN LAW: the face map's index 0 is ".ui.IconDefault"
     (not the real activity); no face maps to BrowserActivity.
  F4 THE FOSSIL RULE: heal recognizes the fossil arrival (exactly the new
     default on + the real activity carrying the old era's flip) and
     resets to the default face + pref — never honors a cross-era index
     (v1.3.80's index 1 was Midnight; v1.3.81+ index 1 is Pika).
  F5 THE ACTIVITY NORMALIZER: apply() pass-3 + resetRealActivity clear
     any persisted non-default state on the real activity; realActivityStray
     is the read-only era marker.
  F6 THE SYNCHRONOUS HEAL: the update receiver runs LauncherGuard.heal
     BEFORE spawning anything (inside the broadcast window — the bare
     Thread raced the OEM process reaper in v1.3.82), and the slow sweeps
     ride goAsync() with finish() in a finally-shaped tail.
  F7 THE STOPPED-STATE LAW: the platform quote lives in the source so
     every future session reads it where the code lives.
  F8 version pin: 96 / 1.3.83.
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


icons = (APP / "util/AppIconSwitch.kt").read_text(encoding="utf-8")
guard = (APP / "util/LauncherGuard.kt").read_text(encoding="utf-8")
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

print("F1 — the resurrection alias (the manifest IS the heal)")
m = re.search(r'<activity-alias\s+android:name="\.ui\.IconDefault".*?</activity-alias>',
              manifest, re.S)
need(m is not None, "the manifest must declare the .ui.IconDefault alias")
if m:
    seg = m.group(0)
    need('android:targetActivity=".ui.BrowserActivity"' in seg,
         "IconDefault must target BrowserActivity")
    need('android:enabled="true"' in seg,
         "IconDefault must ship ENABLED in the manifest — the install-time guarantee")
    need('android:exported="true"' in seg,
         "IconDefault must be exported (the launcher starts it)")
    need('android:icon="@mipmap/ic_launcher"' in seg,
         "IconDefault must carry the default bolt icon")
    need("android.intent.action.MAIN" in seg and
         "android.intent.category.LAUNCHER" in seg,
         "IconDefault must carry the MAIN/LAUNCHER filter")
    need("android.intent.action.VIEW" in seg and
         'android:scheme="http"' in seg and 'android:scheme="https"' in seg,
         "IconDefault must carry the http/https VIEW filter (link opening)")
need(manifest.index(".ui.IconDefault") < manifest.index(".ui.IconPika"),
     "IconDefault must be declared before the themed aliases")

print("F2 — the activity demoted (no filter may hang on the scarred component)")
act = re.search(r'<activity\b[^>]*android:name="\.ui\.BrowserActivity"[^>]*/>', manifest)
need(act is not None,
     "BrowserActivity must be a self-closing <activity/> — it carries NO intent-filter")
if act:
    seg = act.group(0)
    need("intent-filter" not in seg,
         "BrowserActivity must not carry any intent-filter (its persisted DISABLE "
         "survives every update; a filter here is a filter on a dead component)")
need("THE ACTIVITY IS DEMOTED" in manifest,
     "the demotion rationale must stay documented in the manifest")

print("F3 — the never-again law (faces are aliases, always)")
m2 = re.search(r"private val COMPONENTS = arrayOf\((.*?)\)", icons, re.S)
need(m2 is not None and '"%s"' % ".ui.IconDefault" in m2.group(1),
     "COMPONENTS[0] must be .ui.IconDefault")
if m2:
    seg = m2.group(1)
    need("null" not in seg,
         "no face may be null (null was the real-activity entry — the trap)")
    need("BrowserActivity" not in seg,
         "no face may map to the real activity")
need(re.search(r'"\.ui\.IconDefault"\s*,\s*//\s*0', icons) or
     re.search(r'"\.ui\.IconDefault".*?//\s*0', icons, re.S),
     "index 0 must be documented as the default face")

print("F4 — the fossil rule (cross-era prefs are never honored)")
need("fossil" in guard and "fossil-era->default" in guard,
     "heal must log the fossil-era branch")
need(re.search(r"count == 1 && enabled\[0\] && actStray", guard),
     "the fossil arrival is: exactly the new default on + the old era's flip on the activity")
need(guard.index("prefs.setAppIcon(0)") < guard.index("fossil-era->default"),
     "the fossil heal must reset the pref before logging")
need(re.search(r"count == 1 && enabled\[pref\] && !actStray", guard),
     "the healthy early-return must ALSO require a clean activity (the era marker)")

print("F5 — the activity normalizer")
need("resetRealActivity(pm, ctx.packageName)" in icons,
     "apply() pass-3 must normalize the real activity")
need("fun resetRealActivity(" in icons and "@JvmStatic" in icons,
     "resetRealActivity must exist, public + static for the guard")
need("fun realActivityStray(" in icons,
     "realActivityStray (the era marker) must exist")
need("resetRealActivity" in guard,
     "the guard's heals must clear the activity scar")
need(re.search(r"realActivityStray\(pm: PackageManager, pkg: String\): Boolean", icons),
     "the era marker must be read-only (a pm + pkg query)")

print("F6 — the synchronous heal + goAsync sweeps")
recv = guard[guard.index("class UpdateHealReceiver"):]
need(recv.index("LauncherGuard.heal(app)") < recv.index("Thread({"),
     "the launcher heal must run INSIDE onReceive, before any thread is spawned")
need("goAsync()" in recv,
     "the slow sweeps must ride goAsync() (the ~10s window keeps the process)")
need("pr.finish()" in recv,
     "the PendingResult must be finished when the sweeps end")
need("SYNCHRONOUSLY" in guard,
     "the delivery-hardening rationale must stay documented")

print("F7 — the stopped-state law pinned in source")
need("NOT IN A STOPPED STATE" in guard,
     "the platform quote (MY_PACKAGE_REPLACED skips stopped apps) must live in the source")
need("ONLY IF THE APPLICATION WAS UPGRADED WHILE IT WAS" in guard,
     "the quote must be complete enough to be findable")
need("brand-new" in manifest or "NEVER existed" in manifest,
     "the why-a-new-name-resurrects rationale must stay in the manifest")

print("F8 — version pin")
# v1.3.86: the pin re-pointed (the v1382 convention — the newest gate
# owns the current pin; the F1-F7 contracts above still hold: the alias,
# the demotion, the never-again law, the fossil rule, the normalizer,
# the synchronous heal, the stopped-state law).
need('versionName "1.4.25"' in gradle and "versionCode 139" in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "build.gradle must be bumped to 1.3.98 / 111 (test_v1386.py owns the pin)")

print()
if failures:
    print(f"v1.3.83 gate: {len(failures)} FAILURES")
    sys.exit(1)
print("OK: v1.3.83 contracts hold — the resurrection alias ships enabled in "
      "the manifest, the real activity is demoted filter-less, faces are "
      "aliases only, the fossil-era pref is reset, the activity scar is "
      "normalized, the heal is synchronous + goAsync, the stopped-state "
      "law is pinned, version bumped")
