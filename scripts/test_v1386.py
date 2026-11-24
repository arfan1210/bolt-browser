#!/usr/bin/env python3
"""v1.3.86 gate — the four-pack: blob rescue for every type, the real
download names, the incognito hop that sticks, the login-preserving diet.

Field report (v1.3.85): "Download names are random string (zai files).
Text files does not download. Cannot switch from incognito to cognito,
button brings back to incognito, had to close the entire app. The zai
upload still does not work, I am surely logged in, i can see my chat
histories afterall (still 401 unauthorised)."

Contracts pinned here (G1-G5):
  G1 EVERY-TYPE BLOB RESCUE: zai's text/CSV/PDF exports mint a Blob, click
     a download anchor, and REVOKE the URL in the same tick (verified in
     the shipped bundle: anchor.click() then URL.revokeObjectURL(Z) with
     no timeout when the content is not itself a blob URL). After the
     revoke no fetch can read the URL; the FrameHooks-held OBJECT is the
     only survivor — but the v1.3.58 map held IMAGE blobs only, so text
     files fell to the fetch stage, hit the revoked URL, and every later
     stage is image-only: "text files does not download". The map now
     holds blobs under a 48 MB total byte budget (v1.3.89).
  G2 THE REAL NAMES: WebView's onDownloadStart NEVER receives the anchor's
     download attribute, so blob URLs guessed their UUID as the filename
     ("download names are random string"). The FrameHooks capture-phase
     click listener records a[download] href->name (synthetic clicks pass
     through capture) and lands it in Bolt.noteDownloadAnchor BEFORE
     onDownloadStart; the dialog offers that name. Fallbacks: the v1.3.78
     humanName de-randomizer now runs on the DIALOG path (it previously
     only ran for empty-name enqueues, which the dialog never sends), and
     the engine swaps in the server's Content-Disposition filename when
     the confirmed name still looks machine-generated.
  G3 THE HOP THAT STICKS: the old incognito->normal hop did
     startActivity(NEW_TASK|REORDER_TO_FRONT) + finish() in the same
     frame; the finish's resume-next transaction (IncognitoActivity in
     the incognito task) can land after the task move and re-front the
     incognito task ("button brings back to incognito"). Now CLEAR_TOP
     (the stale TabsActivity must not be the landing) and the finish
     rides a postDelayed so the switch settles first.
  G4 LOGIN PRESERVATION (the zai 401): zai keeps its auth JWT in
     localStorage.token; the v1.3.79 deep clean wiped Local Storage on
     every close (default ON), and the auths bootstrap hands cookieless
     visitors a GUEST token (live-reproduced: guest Bearer -> 401 "You do
     not have permission" on POST /api/v1/files/, while GET
     /api/v1/chats/list -> 200 [] — guests chat fine, the UI looks
     signed-in, uploads die). Local Storage now survives the diet below
     8 MB (IndexedDB/SW/File System/Session Storage/databases still go);
     WebStorage.deleteAllData() is skipped when keeping, and both diet
     sites (exit + the interrupted-sweep finisher) honor the budget.
  G5 version pin: 99 / 1.3.86.
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


fh = (APP / "util/FrameHooks.kt").read_text(encoding="utf-8")
act = (APP / "ui/BrowserActivity.java").read_text(encoding="utf-8")
eng = (APP / "core/DownloadEngine.java").read_text(encoding="utf-8")
tabs = (APP / "ui/TabsActivity.java").read_text(encoding="utf-8")
cg = (APP / "util/CacheGuard.kt").read_text(encoding="utf-8")
strings = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

print("G1 — every-type blob rescue (text files download again)")
need("b.size<=50331648" in fh,
     "held blobs cap at the 48 MB budget (v1.3.89: total bytes, not per-blob 32 MB)")
need("16777216" not in fh, "the old 16 MB image-era cap is gone")
need("indexOf('image/')" not in fh,
     "the image-only filter is gone from the capture")
need("while((bs+(b.size||0)>50331648)||Object.keys(m).length>=6){" in fh,
     "byte-budget eviction: oldest-first until the newcomer fits, 6-entry cap (v1.3.89)")
need("m[u]=b;" in fh and "window.__boltBlobs={}" in fh,
     "the map still holds the Blob OBJECT (revocation-proof)")

print("G2 — the real download names")
need("n.closest('a[download]')" in fh,
     "capture-phase click listener finds the download anchor")
need("},true);" in fh, "the listener is CAPTURE phase (synthetic clicks)")
need("Bolt.noteDownloadAnchor(h,d)" in fh,
     "the click lands in the bridge directly")
need("t:'dlName'" in fh and "Bolt.noteDownloadAnchor(d.u,d.n)" in fh,
     "bridge-less frames relay dlName upward (the copyText pattern)")
need("public void noteDownloadAnchor(final String href, final String name)" in act,
     "the bridge method exists")
need("dlAnchorNames" in act and "removeEldestEntry" in act,
     "the href->name LRU exists and is capped")
need("downloadAnchorName(url)" in act,
     "the dialog/blob paths look the anchor name up")
need("sanitizeDownloadName" in act,
     "download-attribute values are sanitized (basename, controls, cap)")
need("DownloadEngine.humanName(url," in act,
     "the dialog default runs the de-randomizer (v1.3.78 law, dialog path)")
need("public static String humanName" in eng,
     "humanName is public (the dialog calls it)")
need("public static boolean machineNamed" in eng,
     "the machine-token detector is split out and public")
need('conn.getHeaderField("Content-Disposition")' in eng,
     "the engine reads the response's Content-Disposition")
need("static String cdFilename" in eng and "filename\\\\*=" in eng,
     "RFC 5987 filename* parsing (the modern form)")
need("it.cdName" in eng and "machineNamed(it.name)" in eng,
     "the server filename swaps in only over machine-generated guesses")
need('"dl via anchor "' in act,
     "diag line when the anchor name was used")

print("G3 — the incognito hop that sticks")
need("hop normal" in tabs, "the hop writes its diag line")
need("FLAG_ACTIVITY_CLEAR_TOP" in tabs,
     "CLEAR_TOP: the hop lands in the browser, not the stale tabs view")
need("postDelayed(this::finish, 350)" in tabs,
     "the finish is posted (never same-frame with the task move)")
need(tabs.count("postDelayed(this::finish, 350)") == 2,
     "both cross-task hops (chipNormal + btn_new_incognito) post the finish")
need("IncognitoActivity.class" in tabs,
     "the incognito entries survive untouched in shape")

print("G4 — login preservation (the zai 401 root cause)")
need("LOCAL_STORAGE_KEEP_BYTES = 8L * 1024 * 1024" in cg,
     "the Local Storage keep-budget exists (8 MB)")
need("siteDataDirs(ctx, keepLs)" in cg,
     "the exit diet passes the keep flag")
need("siteDataDirs(ctx: Context, keepLocalStorage: Boolean = false)" in cg,
     "siteDataDirs gained the keepLocalStorage parameter")
need("localStorageDirs" in cg,
     "the budget measurer exists")
need("val keepLs = lsSize < LOCAL_STORAGE_KEEP_BYTES" in cg
     or "val keepLs = sizeOf(localStorageDirs(ctx)) < LOCAL_STORAGE_KEEP_BYTES" in cg,
     "both diet sites decide keep-vs-wipe by the budget")
need("WebStorage.getInstance().deleteAllData()" in cg,
     "the API wipe still exists for the over-budget path")
need(cg.find("keepLs = lsSize < LOCAL_STORAGE_KEEP_BYTES")
     < cg.find("WebStorage.getInstance().deleteAllData()"),
     "the budget is decided BEFORE the API wipe (and gates it)")
need('"ls-kept"' in cg or "ls-kept" in cg,
     "the keep is written to diagnostics (field evidence)")
need("login tokens (site storage under 8 MB)" in strings,
     "the deep-clean settings copy tells the truth about what survives")

print("G5 — version pin")
need(re.search(r"versionCode\s+145\b", gradle) is not None,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 145")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName\s+"1\.4\.31"', gradle) is not None,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): the escaped-form recovery (the phantom-backslash corpse)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.31"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print()
if failures:
    print(f"v1.3.86 gate: {len(failures)} FAILURE(S)")
    sys.exit(1)
print("v1.3.86 gate: ALL CONTRACTS HOLD")
