#!/usr/bin/env python3
"""v1.3.93 gate — THE BUG-HUNT PASS (three fresh-eyes review rounds over the
shipped v1.3.92 six-pack). Static contract tests over the exact sources.

P1 THE VAULT DELETE CLOBBER (data loss): readAll() returns an EMPTY array
   while the vault is unreadable, so PasswordVault.delete() — reachable
   from the vault screen on any row — wrote an EMPTY vault over the corpse
   (save() has refused that clobber since v1.3.91; delete() never got the
   guard). Now: one fresh decrypt, refuse on a failure that survives it.

P2 THE DOWNLOADS ORPHAN ROWS (three files, one law): MediaStore insert
   landing but the stream/write failing left a 0-byte orphan row in
   Downloads AND (VectorPdf/AiExport) reported SUCCESS because the uri was
   non-null; PageSave.saveHtml was worse — a null stream skipped the
   ?.use silently and ok became true over a 0-byte file. Now: null stream
   = failure, the orphan row is deleted, success follows the WRITE.

P3 THE DARK ENGINE COMPOSITION (two defects the parity model missed):
   (a) media/.__boltNI inside a .__boltDK-preserved dark subtree stacked
       THREE inversions (html + DK + media) = ODD = X-RAY NEGATIVE photos
       inside preserved dark cards. Fix: the descendant rule
       .__boltDK <media> {filter:none!important} — the ancestor pair
       already cancels to identity.
   (b) the MutationObserver path started added nodes at parity 1 even
       inside a preserved subtree — late-mounted dark cards inside DK
       regions double-preserved = inverted patches. Fix: ancInv() derives
       the starting parity from the DK ancestors (each toggles once).

P4 CLEANUPS: dead VectorPdf.downloadsTarget removed (PageSave owns the
   live one), the endSdDrag no-op block and findSdInsertIndex's
   always-zero pageOff removed (behavior identical, verified by review).

P5 THE BACKUP SCREEN PASS (chunk 2, the first v1.3.92 field report):
   the Backup & export page could not scroll — its content column was a
   plain match_parent LinearLayout (the ONLY one of the 11 plain-add
   screens without a ScrollView; the content outgrew the screen in
   v1.3.39 when the ten component toggles landed). Also: AutoBackup was
   the FOURTH Downloads writer the P2 orphan-row sweep missed, and
   importBackup had a zip-slip (a hostile zip entry named ../x wrote
   outside Bolt's private storage).
"""
from pathlib import Path
import re, sys, subprocess, tempfile, os

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app/src/main/java/com/bolt/browser"

vault = (APP / "util/PasswordVault.kt").read_text(encoding="utf-8")
vpdf = (APP / "util/VectorPdf.kt").read_text(encoding="utf-8")
aie = (APP / "util/AiExport.kt").read_text(encoding="utf-8")
psave = (APP / "util/PageSave.kt").read_text(encoding="utf-8")
media = (APP / "media/MediaHook.java").read_text(encoding="utf-8")
act = (APP / "ui/BrowserActivity.java").read_text(encoding="utf-8")
bak = (APP / "ui/BackupActivity.java").read_text(encoding="utf-8")
abk = (APP / "util/AutoBackup.kt").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

fails = 0
def need(cond, msg):
    global fails
    if cond:
        print("  OK  " + msg)
    else:
        fails += 1
        print("  FAIL " + msg)

print("P1 — the vault delete clobber guard")
dm = re.search(r"fun delete\(c: Context, host: String, user: String\) \{(.*?)\n    \}", vault, re.S)
need(dm is not None, "delete() found")
if dm:
    body = dm.group(1)
    need("unreadable && encFile(c).exists()" in body,
         "delete() guards on the unreadable flag (the corpse is never clobbered)")
    need(body.count("readAll(c)") >= 2,
         "delete() re-attempts the decrypt once before refusing")
    need("if (unreadable) return" in body,
         "a failure that survives the retry refuses the write")
need("writeAll(c, arr)" in vault,
     "the happy path still writes (no behavior change when readable)")

print("P2 — the Downloads orphan rows")
need("stream null" in vpdf and "stream null" in aie,
     "a null stream is a failure in both PDF writers")
need("contentResolver.delete(uri, null, null)" in vpdf
     and "contentResolver.delete(uri, null, null)" in aie
     and "contentResolver.delete(uri, null, null)" in psave,
     "all three writers delete the orphan row when the write fails")
need("uri = null" in vpdf and "uri = null" in aie,
     "VectorPdf/AiExport null the uri so success follows the WRITE")
need(re.search(r"if \(os != null\) \{\s*os\.use", psave) is not None,
     "PageSave.saveHtml no longer counts a skipped ?.use as a write")

print("P3 — the dark engine composition")
need(".__boltDK img" in media and ".__boltDK .__boltNI" in media
     and "filter:none!important" in media,
     "media/NI inside .__boltDK contribute NOTHING (no third inversion)")
need("function ancInv(e)" in media and "parityEl(e,ancInv(e))" in media,
     "the observer path starts added nodes at the ANCESTOR parity")
need("parityEl(e,1);" not in media,
     "the hardcoded parity-1 observer start is gone")
need("contrast(1.111)" in media,
     "the exact DK cancel is untouched (v1.3.92 contract preserved)")

print("P4 — cleanups")
need("downloadsTarget" not in vpdf,
     "the dead VectorPdf.downloadsTarget is gone (PageSave owns the live one)")
need("/* already gone */" not in act,
     "the endSdDrag no-op block is gone")
need("pageOff" not in act,
     "findSdInsertIndex's always-zero pageOff is gone")
need("cur * SdGridLayout.PER_PAGE" in act,
     "the insert math still lands on the current page (behavior identical)")

print("P5 — the backup screen pass (chunk 2, the v1.3.92 field report:\n"
      "   'cannot scroll in the backup and export page')")
need("android.widget.ScrollView scroller" in bak and "setFillViewport(true)" in bak,
     "BackupActivity content sits in a ScrollView with fillViewport")
need("addView(list, new LinearLayout.LayoutParams(-1, -2))" in bak,
     "the content column is WRAP-height inside the scroller (measures past the fold)")
need("addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f))" in bak,
     "the scroller takes the weight-1 slot below the 64dp header")
need("addView(list, new LinearLayout.LayoutParams(-1, -1))" not in bak,
     "the old match_parent no-scroll container is gone")
# family invariant: every screen that hides rv_g and mounts its own column
# must mount it inside a ScrollView (Backup was the sole exception; a new
# screen must not regress the family)
plain_adds = ["WatchLaterActivity.kt", "BackupActivity.java", "WallpaperAdjustActivity.kt",
              "QuickActionsActivity.kt", "PasswordVaultActivity.kt", "ProfilesActivity.kt",
              "WorkspacesActivity.kt", "SessionsActivity.kt", "AppearanceActivity.java",
              "EnginesActivity.kt", "TabTrashActivity.kt"]
missing = [f for f in plain_adds
           if "ScrollView" not in (APP / "ui" / f).read_text(encoding="utf-8")]
need(not missing, "all 11 plain-add screens scroll (missing: %s)" % (missing or "none"))
# v1.3.94 re-point: the guard is now anchored at the STAGING base (the
# import stages into .bolt-import; the law is unchanged)
need("getCanonicalPath()" in bak and "startsWith(staging.getCanonicalPath()" in bak,
     "import zip-slip guard: canonical path must stay inside the intended base")

print("P5b — AutoBackup joins the orphan-row law (the P2 sweep missed the 4th writer)")
need("c.contentResolver.delete(uri, null, null)" in abk,
     "a failed MediaStore write deletes its orphan row")
need("legacyFile.delete()" in abk,
     "a failed legacy write deletes its partial file")
need("throw t" in abk,
     "the failure rethrows (success follows the WRITE, never the insert)")
need("ins.use {" in abk,
     "addEntry closes the source stream even when the zip write throws")

print("V — version pin")
need("versionCode 137" in gradle and 'versionName "1.4.23"' in gradle,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "build.gradle pins 137 / 1.4.23")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

# ---- the ancInv semantic probe (node): each DK ancestor toggles parity ----
node = subprocess.run(["which", "node"], capture_output=True, text=True).stdout.strip()
if node:
    m = re.search(r'public static String darkSmartJs\(\)\s*\{(.*?)\n    \}', media, re.S)
    js = ""
    for p in re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1)):
        js += p.replace('\\"', '"').replace('\\\\', '\\')
    # harness: fake the classList/parentElement walk ancInv needs.
    # NOTE the semantics under test: ancInv counts only ANCESTOR classes
    # (the element's own DK is parityEl's decision to make, not context),
    # and the walk stops at document.body exactly like the real code.
    harness = """
function mk(cls, parent){return {classList:{contains:function(c){return cls.indexOf(c)>=0}},
  parentElement: parent||null};}
var DK='__boltDK',NI='__boltNI';
var document={body:null};
""" + re.search(r"function ancInv\(e\)\{var inv=1;.*?return inv;\}", js, re.S).group(0) + """
var body={parentElement:null,classList:{contains:function(){return false}}};
document.body=body;
var dk=mk('__boltDK',body);
var plain=mk('',body);
if(ancInv(plain)!==1) throw 'plain under global must be 1';
if(ancInv(mk('',dk))!==0) throw 'child inside one DK ancestor is 0';
if(ancInv(mk('',mk('__boltDK',mk('__boltDK',body))))!==1) throw 'two DK ancestors toggle back to 1';
if(ancInv(mk('',mk('__boltDK',mk('',body))))!==0) throw 'a plain gap between DKs does not toggle';
if(ancInv(dk)!==1) throw 'the element OWN class is context-excluded (parityEl owns it)';
console.log('ancInv semantics ok');
"""
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(harness); path = f.name
    r = subprocess.run([node, path], capture_output=True, text=True)
    os.unlink(path)
    if r.returncode == 0 and "ancInv semantics ok" in r.stdout:
        print("  OK  ancInv: plain=1, one-DK=0, two-DK=1, child-inherits (node-proven)")
    else:
        fails += 1
        print("  FAIL ancInv semantics: " + (r.stdout + r.stderr)[:400])
else:
    print("  SKIP ancInv probe (node not found)")

print()
if fails:
    print(f"v1.3.93 gate: {fails} FAILURE(S)")
    sys.exit(1)
print("v1.3.93 gate: all green")
