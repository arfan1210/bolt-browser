#!/usr/bin/env python3
"""v1.3.94 gate — the v1.3.93 field report: the home-screen crash loop,
the broken backup import, and the dark-mode pipeline rework.

CRASH — "the browser refuses to open a new tab or switch to any other
   tab" + the pasted stack: IllegalStateException "Pages must fill the
   whole ViewPager2 (use match_parent)" from the home grid pager, at
   EVERY process start. ROOT CAUSE (decompiled from the AAR):
   ViewPager2's attach listener requires every page's
   RecyclerView.LayoutParams to be width=-1 AND height=-1; a bare-created
   page gets LinearLayoutManager.generateDefaultLayoutParams() =
   (-2,-2) = WRAP_CONTENT — the FIRST page attach always throws. The home
   renders lazily (only when the current tab IS the home), so the crash
   read as "refuses to open a new tab"; the v1.3.94 import restored a
   home-current state and turned it into a startup crash loop.

IMPORT — "no tabs are imported, no settings or workspace are here; I do
   see the authenticator, it imported": the import wrote files STRAIGHT
   into filesDir under the LIVE process, whose singletons (Prefs,
   TabManager, speed-dial) flushed stale in-memory state back over the
   imported files within seconds (authenticator.json had no live writer
   — it survived). Also the backup set had NEVER carried workspaces.json
   / sessions.json / tabtrash.json. Now: STAGED import
   (filesDir/.bolt-import) + BoltApp applies it in attachBaseContext
   (before any singleton constructs) + alarm-and-exit relaunch; the set
   carries workspaces/sessions/trash.

DARK — "make bolt ASK the website to turn on the websites designed dark
   mode it self… then fall back to manual smart inversion": the dark
   skins already report prefers-color-scheme: dark (isLightTheme=false);
   the NEW order is probe (already dark → keep) → ASK (declares a dark
   mode → HOLD the inversion, wait bounded for its own dark; the v1.3.94
   log showed github/vscode inverted-then-pulled = a white flash + flip
   on every page view) → no declaration → invert NOW (the smart parity
   engine). webDark now FOLLOWS THE SKIN by default ("if the browser is
   in dark mode, all sites will open by default in dark mode"). The
   clarity pass: contrast 0.9 → 1.05 (DK compensation 1.111 → 0.9524 =
   1/1.05 exact cancel) + a 0.35px currentColor text halo ("keep
   contrast high and thicken up thin elements a bit")."""
from pathlib import Path
import re, sys, subprocess, tempfile, os

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app/src/main/java/com/bolt/browser"

act = (APP / "ui/BrowserActivity.java").read_text(encoding="utf-8")
media = (APP / "media/MediaHook.java").read_text(encoding="utf-8")
bak = (APP / "ui/BackupActivity.java").read_text(encoding="utf-8")
app = (APP / "BoltApp.java").read_text(encoding="utf-8")
abk = (APP / "util/AutoBackup.kt").read_text(encoding="utf-8")
prefs = (APP / "util/Prefs.java").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

fails = 0
def need(cond, msg):
    global fails
    if cond:
        print("  OK  " + msg)
    else:
        fails += 1
        print("  FAIL " + msg)

print("C — the pager law (the home crash loop)")
need("g.setLayoutParams(new androidx.recyclerview.widget.RecyclerView"
     "\n                        .LayoutParams(-1, -1))" in act.replace("\r", ""),
     "every SdGridLayout page carries RecyclerView.LayoutParams(-1,-1)")
need(re.search(r"onCreateViewHolder[\s\S]{0,1600}LayoutParams\(-1, -1\)", act) is not None,
     "the params are set in onCreateViewHolder (before any attach)")
# the bare-view corpse must be gone
need("new androidx.recyclerview.widget.RecyclerView.ViewHolder(\n"
     "                        new SdGridLayout(BrowserActivity.this))" not in act,
     "no page is created bare anymore (LinearLayoutManager default = WRAP_CONTENT)")

print("I1 — the staged import (no live clobber)")
need('" .bolt-import"' in bak or '".bolt-import"' in bak,
     "the import stages into filesDir/.bolt-import")
need("deleteTree(staging);\n                runOnUiThread" in bak or
     re.search(r"catch \(Throwable ex\) \{\s*// a half-staged tree must NEVER apply later\s*— wipe it\s*deleteTree\(staging\)", bak) is not None,
     "a failed import wipes the staging tree (half-staged never applies)")
need("startsWith(staging.getCanonicalPath() + File.separator)" in bak,
     "the zip-slip guard is re-anchored at the staging base")
need("passwords.json" in bak and "importPlain" in bak,
     "passwords still merge live through the vault (no raw copy)")
need("ELAPSED_REALTIME_WAKEUP" in bak and "killProcess" in bak,
     "the relaunch is alarm + exit (never startActivity+finish — the race lesson)")
need("imported \" + count + \" files\"" in bak.replace("Toast", "") or "restarting to apply" in bak,
     "the toast says the app restarts to apply")

print("I2 — the apply side (BoltApp, before any singleton)")
need("protected void attachBaseContext(android.content.Context base)" in app,
     "the apply lives in attachBaseContext (before providers/onCreate)")
need(re.search(r"attachBaseContext[\s\S]{0,700}applyStagedImport\(\)", app) is not None,
     "applyStagedImport runs first in attachBaseContext")
need('":incognito"' in app and "processName().endsWith" in app,
     "the incognito process never applies (shared filesDir, no import UI)")
need("Files.move" in app and "REPLACE_EXISTING" in app,
     "files move into place atomically-overwriting")
need("deleteTree(staging)" in app,
     "the staging tree is removed after the apply")
need("import applied" in app,
     "the apply leaves a diag line")

print("I3 — the backup set finally carries workspaces/sessions/trash")
need('"workspaces.json"' in abk and '"sessions.json"' in abk and '"tabtrash.json"' in abk,
     "workspaces.json + sessions.json + tabtrash.json ride bkTabs")
need("workspaces.json + sessions.json + the closed-tab bin" in bak,
     "the toggle row tells the user what rides")

print("D1 — probe first, invert now (v1.4.18 re-point 2026-11-19: ask-first retired)")
need("askDarkJs" in media, "MediaHook.askDarkJs exists")
need(re.search(r"askDarkJs\(\) \{[\s\S]{0,2400}prefers-color-scheme", media) is not None,
     "the ask probe checks stylesheet media rules (the page ANSWERS the scheme hint)")
need("color-scheme" in media and "data-color-mode" in media,
     "the ask probe checks the meta/computed color-scheme + theme machinery")
need("darkInvertNow" in act and "darkAwaitSelfDark(" not in act,
     "the invert path stays; the ask-first hold is RETIRED (v1.4.18 re-point 2026-11-19: a site that merely DECLARES dark held WHITE ~3 s under a fresh cookie jar — light-at-commit inverts NOW; the compiled decision probe lives in test_v1418)")
need(re.search(r"darkProbeJs\(\), res -> \{[\s\S]{0,900}darkInvertNow", act) is not None,
     "LIGHT AT COMMIT = INVERT NOW (v1.4.18 re-point 2026-11-19: the probe's painted-light reading is the only evidence that matters; the declaration is not)")
need('"dark commit-pass "' in act,
     "the commit hook carries its own diag breadcrumb (v1.4.18: proof the hook fires on device — a static pin proves the code exists, not that the door opens)")
need("dark late-flip" in act, "the late pull stays on the invert path (only ever inversion->none)")

print("D2 — follow the skin + the clarity pass")
need('return has("webDark") ? b("webDark", isDark()) : isDark();' in prefs,
     "webDark follows the skin when unset (explicit toggles still win)")
need("contrast(1.05)" in media, "the html inversion contrast is 1.05 (was 0.9)")
need("contrast(0.9524)" in media, "the DK compensation is the exact inverse (1/1.05)")
need("text-shadow:0 0 0.35px currentColor" in media,
     "the hairline halo thickens thin text (site-set shadows still win: no !important)")

print("V — version pin")
need("versionCode 147" in gradle and 'versionName "1.4.33"' in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "build.gradle pins 147 / 1.4.33")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

# ---- semantic probes (node) ----
node = subprocess.run(["which", "node"], capture_output=True, text=True).stdout.strip()
if node:
    # (1) contrast pairing must compose to identity (contrast is affine
    # around the 0.5 pivot; applications multiply on the slope)
    js1 = """
function c(x){ // contrast(1.05) then contrast(0.9524) on value x
  function contrast(v, s){ return (v - 0.5) * s + 0.5; }
  return contrast(contrast(x, 1.05), 0.9524);
}
var worst = 0;
for (var i = 0; i <= 100; i++) { var x = i / 100; var d = Math.abs(c(x) - x); if (d > worst) worst = d; }
if (worst > 0.001) throw 'pairing not identity, worst delta ' + worst;
console.log('contrast pairing identity ok');
"""
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(js1); p1 = f.name
    r1 = subprocess.run([node, p1], capture_output=True, text=True)
    os.unlink(p1)
    if r1.returncode == 0 and "identity ok" in r1.stdout:
        print("  OK  contrast 1.05 x 0.9524 composes to identity (node-proven)")
    else:
        fails += 1
        print("  FAIL contrast pairing: " + (r1.stdout + r1.stderr)[:300])

    # (2) the askDarkJs semantics against a fake DOM
    m = re.search(r'public static String askDarkJs\(\)\s*\{(.*?)\n    \}', media, re.S)
    js = ""
    for p in re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1)):
        js += p.replace('\\"', '"').replace('\\\\', '\\')
    harness = """
// minimal fake DOM: a document whose sheets/styles can be configured
var __ask = {
  metaColorScheme: null, computedColorScheme: '', sheets: [],
  attrs: {}, htmlClass: ''
};
var document = {
  querySelector: function(sel){ return __ask.metaColorScheme === null ? null
      : { getAttribute: function(){ return __ask.metaColorScheme; } }; },
  documentElement: {
    get colorScheme(){ return __ask.computedColorScheme; },
    getAttribute: function(a){ return __ask.attrs[a] || null; },
    get className(){ return __ask.htmlClass; },
    hasAttribute: function(a){ return a in __ask.attrs; }
  },
  styleSheets: { get length(){ return __ask.sheets.length; },
                 item: function(i){ return __ask.sheets[i]; } }
};
// array-like index access for styleSheets
document.styleSheets[0] = null;
Object.defineProperty(document.styleSheets, '0', { get: function(){ return __ask.sheets[0]; } });
Object.defineProperty(document.styleSheets, '1', { get: function(){ return __ask.sheets[1]; } });
var getComputedStyle = function(el){ return { get colorScheme(){ return __ask.computedColorScheme; } }; };
function sheetOf(rulesText){ return { cssRules: { length: rulesText.length,
    item: function(i){ return rulesText[i]; } } }; }
function ruleOf(mediaText){ return { media: { mediaText: mediaText } }; }
""" + js + """
// case 1: nothing declared -> '0'
if (askDark !== undefined) {}
__ask.sheets = [];
var r = eval('(function(){try{' +
  "try{var m=document.querySelector('meta[name=\"color-scheme\"]');" +
  "if(m&&/dark/i.test(m.getAttribute('content')||''))return '1';}catch(e){}" +
  "try{var s=getComputedStyle(document.documentElement).colorScheme||'';" +
  "if(/dark/i.test(s))return '1';}catch(e){}" +
  '0;}catch(e){return "0"}})()');
"""
    # simpler: run the extracted js directly with configured fakes per case
    cases = """
var results = [];
function runCase(name, setup, expected) {
  __ask.sheets = []; __ask.attrs = {}; __ask.htmlClass = '';
  __ask.metaColorScheme = null; __ask.computedColorScheme = '';
  setup();
  var got = (function(){try{
""" + js + """
    return 'NOT_REACHED';
  }catch(e){return 'ERR:'+e;}})();
  // js above returns before NOT_REACHED via its own returns; but it is an
  // expression, so re-evaluate:
}
"""
    # NOTE: the extracted js is a full IIFE string; evaluate it directly.
    harness2 = """
var __ask = { metaColorScheme: null, computedColorScheme: '', sheets: [],
              attrs: {}, htmlClass: '' };
var document = {
  querySelector: function(sel){ return __ask.metaColorScheme === null ? null
      : { getAttribute: function(){ return __ask.metaColorScheme; } }; },
  documentElement: {
    getAttribute: function(a){ return (a in __ask.attrs) ? __ask.attrs[a] : null; },
    get className(){ return __ask.htmlClass; }
  },
  get styleSheets(){ return __sheets; }
};
// ONE stable sheets object (index getters bound to the live fake state)
var __sheets = { get length(){ return __ask.sheets.length; } };
Object.defineProperty(__sheets, '0', { get: function(){ return __ask.sheets[0]; } });
Object.defineProperty(__sheets, '1', { get: function(){ return __ask.sheets[1]; } });
Object.defineProperty(__sheets, '2', { get: function(){ return __ask.sheets[2]; } });
var getComputedStyle = function(){ return { colorScheme: __ask.computedColorScheme }; };
var ASK = function(){ return """ + js + """; };
function check(name, expected, setup) {
  __ask.sheets = []; __ask.attrs = {}; __ask.htmlClass = '';
  __ask.metaColorScheme = null; __ask.computedColorScheme = '';
  setup();
  var got = ASK();
  if (got !== expected) throw name + ': expected ' + expected + ' got ' + got;
}
check('plain light page', '0', function(){});
check('meta color-scheme dark', '1', function(){ __ask.metaColorScheme = 'dark light'; });
check('computed color-scheme dark', '1', function(){ __ask.computedColorScheme = 'dark'; });
check('prefers-color-scheme media rule', '1', function(){
  __ask.sheets = [{ cssRules: [ { media: { mediaText: '(prefers-color-scheme: dark)' } } ] }];
});
check('plain media rule is not the hint', '0', function(){
  __ask.sheets = [{ cssRules: [ { media: { mediaText: '(max-width: 600px)' } } ] }];
});
check('cross-origin sheet skipped, other signals still work', '1', function(){
  __ask.sheets = [{ /* no cssRules getter -> throws inside */ }];
  __ask.attrs['data-theme'] = 'light';
});
check('dark class on html', '1', function(){ __ask.htmlClass = 'x dark-mode y'; });
check('data-theme machinery', '1', function(){ __ask.attrs['data-theme'] = 'light'; });
check('no machinery at all', '0', function(){ __ask.htmlClass = 'root notdark'; });
console.log('askDarkJs semantics ok');
"""
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(harness2); p2 = f.name
    r2 = subprocess.run([node, p2], capture_output=True, text=True)
    os.unlink(p2)
    if r2.returncode == 0 and "askDarkJs semantics ok" in r2.stdout:
        print("  OK  askDarkJs: meta/computed scheme, media rules, cross-origin skip, "
              "theme machinery, dark class (node-proven)")
    else:
        fails += 1
        print("  FAIL askDarkJs semantics: " + (r2.stdout + r2.stderr)[:400])
else:
    print("  SKIP node probes (node not found)")

print()
if fails:
    print(f"v1.3.94 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.3.94 gate: all green")
