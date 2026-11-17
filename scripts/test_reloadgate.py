#!/usr/bin/env python3
"""v1.3.53 aggressive-reload regression test (THE 'reloads on resume /
reloads when logging in with saved passwords' field report).

Field history: since v1.3.39 a landed favicon bumped Fx.skinGen
(FaviconStore.bump, 'so the browser rebuilds its home'), but skinGen is
BrowserActivity's RECREATE channel (onResume: skinGen != seenGen ->
recreate()). A recreate destroys EVERY tab's WebView in the new onCreate
(destroyStaleWebViews) and reloads the active page. Favicons land
constantly while browsing (onReceivedIcon) AND from the DuckDuckGo
network fallback on a background thread, so ANY pause+resume -- returning
from settings, switching apps, peeking recents, and the Google
password-manager sheet opening over a login page -- reloaded the page
almost every time; a login page's own favicon armed the recreate that the
autofill sheet's pause+resume fired MID-LOGIN. Two more polluters rode
the same channel: WallpaperAdjustActivity bumped skinGen UNCONDITIONALLY
in onStop (a mere VISIT recreated the browser on return), and the
home-scope changes (wallpaper set/remove, built-in wallpapers, the
suggested-sites toggle) used the recreate channel where a home REBUILD
was the right scope.

v1.3.53 splits the channels: Fx.homeGen is the HOME-CONTENT channel
(favicons, wallpaper style, suggested sites) whose readers only ever
REBUILD the home view (showTab on a stale homeShownGen; onResume while
the home is the visible tab), while skinGen stays reserved for genuine
LAYOUT-level changes (urlBarBottom, fullscreen/notch, skin switches).
This test fails if the pollution ever returns or the readers rot.
"""
import re, sys, pathlib

BASE = pathlib.Path("/home/z/my-project/bolt-light-browser"
                   "/app/src/main/java/com/bolt/browser")
failures = []


def rd(p):
    return (BASE / p).read_text(errors="ignore")


def strip_comments(text):
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text


# ---- 1. FaviconStore: NO recreate-channel contact, homeGen bump intact ----
fav = strip_comments(rd("util/FaviconStore.kt"))
if "skinGen" in rd("util/FaviconStore.kt").replace(
        "v1.3.39–v1.3.52 bumped", ""):
    # the word may legitimately appear inside the explanation comment; the
    # CODE (comment-stripped) must not touch it
    pass
if re.search(r"\bskinGen\b", fav):
    failures.append("FaviconStore.kt code touches skinGen (the recreate "
                    "channel) -- favicons must bump Fx.homeGen only")
if "Fx.homeGen++" not in fav:
    failures.append("FaviconStore.bump no longer bumps Fx.homeGen (the "
                    "home-rebuild signal would be lost)")
if "faviconGen" in fav:
    failures.append("FaviconStore still writes the dead 'faviconGen' pref "
                    "(write-only state removed in v1.3.53)")

# ---- 2. wallpaper surfaces: home scope, never the recreate channel ----
hw = strip_comments(rd("util/HomeWallpaper.java"))
if "Fx.skinGen++" in hw:
    failures.append("HomeWallpaper still bumps skinGen -- a wallpaper "
                    "change is HOME scope (Fx.homeGen), a recreate "
                    "reloads every tab for a background swap")
if "Fx.homeGen++" not in hw:
    failures.append("HomeWallpaper no longer bumps Fx.homeGen (the home "
                    "would keep the stale wallpaper bitmap)")

dw = strip_comments(rd("util/DefaultWallpapers.kt"))
if "Fx.skinGen++" in dw:
    failures.append("DefaultWallpapers still bumps skinGen (home scope -> "
                    "Fx.homeGen)")
if "Fx.homeGen++" not in dw:
    failures.append("DefaultWallpapers no longer bumps Fx.homeGen")

wa = strip_comments(rd("ui/WallpaperAdjustActivity.kt"))
if "Fx.skinGen++" in wa:
    failures.append("WallpaperAdjustActivity still bumps skinGen")
m = re.search(r"onStop\(\)\s*\{[^}]*\}", wa, re.S)
if not m:
    failures.append("WallpaperAdjustActivity.onStop not found")
elif "if (dirty) Fx.homeGen++" not in m.group(0):
    failures.append("WallpaperAdjustActivity.onStop must bump homeGen "
                    "ONLY behind the dirty flag (an unconditional bump "
                    "recreated the browser after a mere VISIT)")

# ---- 3. Appearance: the suggested-sites toggle is home scope ----
ap = rd("ui/AppearanceActivity.java")
m = re.search(r'prefs\.set\("suggestedSites",\s*on\);\s*\n\s*'
              r'(Fx\.\w+\+\+)', ap)
if not m:
    failures.append("suggested-sites toggle: bump call not found")
elif m.group(1) != "Fx.homeGen++":
    failures.append("suggested-sites toggle bumps %s -- home scope must "
                    "bump Fx.homeGen++" % m.group(1))

# ---- 4. BrowserActivity: the homeGen readers + the surviving recreate ----
ba = rd("ui/BrowserActivity.java")
bas = strip_comments(ba)

# 4a. field: homeShownGen tracked
if "protected int homeShownGen" not in ba:
    failures.append("BrowserActivity: homeShownGen field missing (the "
                    "homeGen stamp the current homeView was built at)")

# 4b. showTab rebuilds a stale home instead of only when null
m = re.search(r"if \(t\.isHome\) \{(.*?)\n        \} else \{", ba, re.S)
if not m:
    failures.append("showTab isHome branch not found")
else:
    branch = m.group(1)
    if not re.search(r"if \(homeView == null \|\| Fx\.homeGen != homeShownGen\)",
                     branch):
        failures.append("showTab's isHome branch no longer rebuilds on a "
                        "stale homeGen (favicon/wallpaper changes would "
                        "never appear)")

# 4c. buildHomeView stamps the generation it was built at
m = re.search(r"homeView = root;(.*?)\n    \}", ba, re.S)
if not m:
    failures.append("buildHomeView tail not found")
elif "homeShownGen = Fx.homeGen" not in m.group(1):
    failures.append("buildHomeView does not stamp homeShownGen (readers "
                    "would see a permanently stale/fresh stamp)")

# 4d. onResume: home refresh while the home tab is visible
m = re.search(r"syncFromTabManager\(\);\s*\n(.*?)\n\s*try \{", ba, re.S)
if not m:
    failures.append("onResume: homeGen reader block not found after "
                    "syncFromTabManager")
else:
    blk = m.group(1)
    if "Fx.homeGen != homeShownGen" not in blk:
        failures.append("onResume no longer checks homeGen (sitting on the "
                        "home tab would keep stale content after a "
                        "favicon/wallpaper change)")
    if "isHome" not in blk or "refreshHome()" not in blk:
        failures.append("onResume homeGen reader must refreshHome() only "
                        "when the active tab isHome")

# 4e. the legitimate recreate channel itself survives intact
m = re.search(r"if \(nowHash != themeHash \|\| Fx\.skinGen != seenGen\) \{"
              r"\s*seenGen = Fx\.skinGen;\s*recreate\(\);", bas)
if not m:
    failures.append("the layout-change recreate check (themeHash/skinGen) "
                    "was lost -- urlBarBottom & fullscreen need it")

# ---- 5. BoltApp: the browser family stays exempt from themeStamp recreates ----
app = rd("BoltApp.java")
if "instanceof com.bolt.browser.ui.BrowserActivity" not in app:
    failures.append("BoltApp no longer exempts BrowserActivity from the "
                    "themeStamp recreate (double recreate / reload risk)")

# ---- 6. skinGen++ sites are all user-initiated layout-level changes ----
allowed = {
    "ui/AppearanceActivity.java": 4,      # skin / mode / urlBarBottom / accent
    "ui/BrowserActivity.java": 1,         # fullscreen toggle (applies in-window)
    "ui/SettingsItemsActivity.java": 1,   # fullscreen notch toggle
    "ui/SettingsHomeActivity.java": 1,    # fullscreen toggle
}
for p in sorted(BASE.glob("**/*.java")) + sorted(BASE.glob("**/*.kt")):
    rel = str(p.relative_to(BASE))
    n = len(re.findall(r"skinGen\+\+", strip_comments(rd(rel))))
    limit = allowed.get(rel, 0)
    if n > limit:
        failures.append("%s bumps skinGen %d times (allowed %d) -- new "
                        "bumps must justify a full browser recreate or "
                        "use Fx.homeGen" % (rel, n, limit))

if failures:
    print("RELOAD-GATE FAIL:")
    for f in failures:
        print("  -", f)
    sys.exit(1)
print("reload-gate: all checks passed "
      "(favicon/wallpaper -> homeGen only; homeGen readers intact; "
      "layout-level skinGen sites accounted)")
