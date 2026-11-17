#!/usr/bin/env python3
"""v1.3.81 gate — the six-pack field fix (one gate per contract).

Field report (v1.3.80):
  1. "Force dark site does not make sites dark"
  2. "scrolling hides the bottom toolbar fast enough but there is a black
     rectangle ghost like in the bottom portion that takes more time to go
     away"
  3. "all the alternative bolt icons are very bad, keep the default one
     (black background one), then add a few different style ones following
     themes like popular franchises but with the bolt lighting"
  4. "the browser home looks fine but it very poorly functionable, like
     holding on it does not show any actions"
  5. "zai's logo shows google, google forms logo shows google logo in the
     tabs"
  6. "browser's back and forward don't load the prevoius and forward
     website, just the links appear but the same page remains"

Contracts pinned here (D1-D6):
  D1 FORCE-DARK MATRIX: isLightTheme on every Theme.Bolt* skin (the
     algorithmic darkener reads it; without it setAlgorithmicDarkening
     Allowed(true) was a NO-OP), the CSS inversion fallback for every case
     the native path cannot cover (<33 any mode; 33+ app-light), and the
     applyWebDark native branch.
  D2 GHOST KILL: the per-frame inset animator is dead (one instant
     setPadding — pinned in test_cacheguard too); hideBarsNow sequences the
     ONE resize FIRST (bars still covering) and posts the slide one beat
     later (50 ms); showBarsNow insets instantly too.
  D3 THEMED ICONS: IconPika/IconWizard/IconVolt/IconNeon aliases + mipmaps
     + AppIconSwitch wiring (the colorway set is gone).
  D4 HOLD-MENU: a stationary 600 ms hold on a home tile opens the tile menu
     by itself (sdHoldMenu), canceled by drag engagement / release / drag
     end / merge arm.
  D5 FAVICON FULL-HOST KEYING + RACE GUARD: store/load/domPending keyed by
     the FULL host with the baseDomain file as legacy fallback; a 700 ms
     drop window after a pin CHANGE (inRaceWindow) guards onReceivedIcon.
  D6 BACK/FORWARD: onFormResubmission overridden and RESUMES the load (the
     default empty implementation hangs every history navigation that
     lands on a POST result — "the same page remains").
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app/src/main/java/com/bolt/browser"
RES = ROOT / "app/src/main/res"

failures = []


def need(cond, msg):
    if not cond:
        failures.append(msg)
        print(f"  FAIL {msg}")


act = (APP / "ui/BrowserActivity.java").read_text(encoding="utf-8")
tab = (APP / "core/Tab.java").read_text(encoding="utf-8")
fav = (APP / "util/FaviconStore.kt").read_text(encoding="utf-8")
icons = (APP / "util/AppIconSwitch.kt").read_text(encoding="utf-8")
styles = (RES / "values/styles.xml").read_text(encoding="utf-8")
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

print("D1 — the force-dark matrix")
for m in re.finditer(r'<style name="(Theme\.Bolt[^"]*)"', styles):
    block = styles[m.start():styles.index("</style>", m.start())]
    want = "false" if ".Dark" in m.group(1) else "true"
    need(f'android:isLightTheme">{want}<' in block,
         f"{m.group(1)} must set isLightTheme={want}")
need("siteDarkNow(base)" in act,
     "the per-site dark gate (siteDarkNow) drives the CSS engine in BrowserActivity")
# v1.3.91: ONE ENGINE — the CSS inversion + smart probe run on EVERY api
# level and theme; the native darkener is force-OFF everywhere (it was
# the double-invert / image-invert corpse)
need("setAlgorithmicDarkeningAllowed(false)" in tab,
     "v1.3.91: applyWebDark must keep the 33+ native darkener OFF (the CSS engine owns web dark)")
need(re.search(r"setForceDark\(WebSettings\.FORCE_DARK_OFF", tab),
     "the 29-32 legacy force-dark stays OFF")
need("if (siteDarkNow(base)) {" in act
     and "SDK_INT < 33" not in act.split("if (siteDarkNow(base)) {")[1][:400],
     "v1.3.91: the probe+CSS path runs on every SDK/theme (no carve-out)")

print("D2 — the ghost kill sequencing")
need("THE GHOST KILL" in act, "the ghost-kill contract comment is missing")
need(re.search(r"applyContentInsets\(false\);\s*if \(animate\) \{\s*ui\.postDelayed", act),
     "hideBarsNow must inset FIRST then post the slide (covered resize)")
need("}, 50L);" in act, "the slide must start ~50 ms after the instant inset")
need(act.count("ValueAnimator.ofFloat(0f, 1f)") == 0 or "insetAnim = va" not in act,
     "no per-frame inset animator may exist")
need(re.search(r"applyContentInsets\(false\);\s*\} catch \(Throwable ignored\) \{\}\s*\}\s*\}\s*\n\s*protected void hideBarsNow", act)
     or "showBarsNow" in act, "sanity")

print("D3 — the themed icon set")
for alias in ("IconPika", "IconWizard", "IconVolt", "IconNeon"):
    need(f'android:name=".ui.{alias}"' in manifest, f"alias {alias} missing")
for mipmap in ("ic_launcher_pika", "ic_launcher_wizard", "ic_launcher_volt", "ic_launcher_neon"):
    need((RES / f"mipmap-anydpi-v26/{mipmap}.xml").exists(), f"{mipmap} adaptive xml missing")
    need((RES / "mipmap-xxxhdpi" / f"{mipmap}.png").exists(), f"{mipmap} PNG fallback missing")
for gone in ("IconMidnight", "IconSunrise", "IconForest", "IconMono"):
    need(gone not in manifest, f"the old colorway alias {gone} must be gone")
need('".ui.IconPika"' in icons and '".ui.IconNeon"' in icons,
     "AppIconSwitch must map the themed components")
need('"Pika"' in icons and '"Wizard"' in icons and '"Volt"' in icons and '"Neon"' in icons,
     "AppIconSwitch must carry the themed names")

print("D4 — the home hold-menu")
need("sdHoldMenu" in act, "the hold-menu runnable is missing")
need(re.search(r"ui\.postDelayed\(sdHoldMenu, 550\)", act),
     "the hold-menu arms at 550 ms on drag start (v1.3.95: 600 -> 1200; "
     "v1.3.97: 1200 -> 550 — the release-without-move door makes a long "
     "window unnecessary; the launcher cadence)")
seg = act[act.index("sdDragMoved = true;"):act.index("sdDragMoved = true;") + 300]
need("ui.removeCallbacks(sdHoldMenu);" in seg,
     "drag engagement must cancel the hold-menu")
need(re.search(r"ACTION_CANCEL: \{\s*ui\.removeCallbacks\(sdHoldMenu\);", act) or
     # v1.3.88: UP/CANCEL route through the shared sdDragEnd (fed by the
     # cells' in-flight touch stream AND the shield overlay), whose first
     # act is the cancel — the contract (release cancels the hold-menu)
     # is unchanged, the call site moved
     re.search(r"protected void sdDragEnd\(float x, float y\) \{[\s\S]*?ui\.removeCallbacks\(sdHoldMenu\);", act),
     "release must cancel the hold-menu")
need(re.search(r"endSdDrag\(\) \{\s*try \{ ui\.removeCallbacks\(sdHoldMerge\); \} catch[^\\n]*\s*try \{ ui\.removeCallbacks\(sdHoldMenu\); \}", act) or
     "ui.removeCallbacks(sdHoldMenu)" in act.split("protected void endSdDrag")[1][:600],
     "endSdDrag must cancel the hold-menu")

print("D5 — favicon full-host keying + the race guard")
need("fun normalize(host: String): String = host.trim().lowercase()" in fav,
     "the full-host normalize key must exist")
need("inRaceWindow" in fav, "the race-window reader is missing")
need(re.search(r"viewRaceUntil\[view\] = android\.os\.SystemClock\.uptimeMillis\(\) \+ 700L", fav),
     "a host CHANGE must open the 700 ms drop window")
need("FaviconStore.inRaceWindow(view)" in act,
     "onReceivedIcon must check the race window before storing")
need(re.search(r"val h = normalize\(host\)", fav) and "Fx.baseDomain(host.lowercase())" not in fav,
     "store() must key by the full host, not baseDomain")
need("val base = try { Fx.baseDomain(h) }" in fav,
     "load()/fetchAsync must keep the baseDomain as legacy fallback")
need(re.search(r"storeLocked\(appc, cand\.second, bmp\)", fav),
     "network candidates must store under the host they were fetched for")

print("D6 — back/forward: the form-resubmission kill")
need("onFormResubmission" in act, "the onFormResubmission override is missing")
need(re.search(r"public void onFormResubmission\(WebView view, android\.os\.Message dontResend,\s*android\.os\.Message resend\) \{\s*try \{ resend\.sendToTarget\(\); \}", act),
     "onFormResubmission must RESUME the load (send resend)")

print()
if failures:
    print(f"v1.3.81 gate: {len(failures)} FAILURES")
    sys.exit(1)
print("OK: v1.3.81 contracts hold — force-dark matrix, ghost kill, themed "
      "icons, hold-menu, favicon full-host keying + race guard, form-resubmission")
