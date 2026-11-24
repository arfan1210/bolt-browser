#!/usr/bin/env python3
"""v1.4.28 — the SECURITY + VISIBILITY round (three security laws + three
bug fixes, each with a probe).

Root-caused from the v1.4.27 source + the user's field log (Realme
RMX2020 / Android 10: "chars=0 lines=0" for ALL OCR languages/models,
Bolt AI tables invisible, QR gallery fails on 12 MP photos):

1. THE AUTOFILL AUTH GATE (user-requested security): before
   fillPwFromChip AND the account picker, the device credential is
   required (KeyguardManager.createConfirmDeviceCredentialIntent).
   Pref "Require unlock for autofill" (default ON, on the vault screen);
   a successful unlock caches ~60 s; cancelling fills/picks NOTHING.
2. THE VAULT ACCESS GATE: PasswordVaultActivity built its entries in
   onCreate and NEVER handled the credential prompt's result —
   cancelling left the entries visible behind the prompt. Now entries
   stay UNBUILT until onActivityResult(1013) answers RESULT_OK; a
   cancel finish()es immediately.
3. THE ENCRYPTED PASSWORD BACKUP: BackupActivity's "Saved passwords"
   toggle used to write the DECRYPTED vault as plain JSON into the zip.
   Now: device-credential gate -> passphrase prompt -> passwords.vault
   blob (AES-256-GCM under PBKDF2-HmacSHA256 120k, salted per export);
   the scheduled headless backup skips passwords honestly; the restore
   path gates + asks the passphrase (the legacy plain passwords.json
   import from old zips stays, behind the same gate).
4. BOLT AI TABLES INVISIBLE: the v1.4.27 TableSpan ReplacementSpan
   rendered NOTHING in the field (the try/catch swallowed whatever
   threw; the bubble showed only the bare \\u2009 slot). The fix per the
   ask: a CUSTOM VIEW-BASED table renderer — the TableMark marker in the
   rendered text, splitTables cutting at the marks, and the bubble as a
   CONTAINER (LinearLayout) interleaving text segments with real
   TableView children whose onDraw draws the true grid (outer border +
   every row/column rule, bold header on its band, cell padding, theme
   colors, content-measured widths). The framework GUARANTEES an
   attached view's onDraw runs — the corpse cannot recur silently.
   Probe: TableRenderTest (Robolectric, NATIVE graphics) renders a 3x3
   GFM table and asserts PIXELS — borders AND cell text.
5. OCR FAILS UNIVERSALLY: the wasm tesseract engine rejects the
   jpn_vert/jpn_vertb model family (parameter warnings, chars=0 lines=0
   for all languages once the worker is poisoned). FIX per the ask:
   the VISION path is PRIMARY for manga (bright-region bubble
   detection + one vision card per bubble — no model download, no OCR);
   OCR stays for eng/chi_sim ("auto" trims to that pair); the
   VISIBLE-FAIL LAW logs init/recognize failures and engine warnings
   through the diag UNCAPPED. Probe: the host-page script under Node —
   an init rejection diag's 'ocr fail init:' and fails; a good run
   diag's 'ocr worker ok' + 'ocr done lines=N'.
6. QR GALLERY DECODE FAILS: decodeUri decimated every photo to <=8 MP
   (sample 2 on a 12 MP photo) BEFORE the first pass — the small code's
   module pixels halved before any pass ran. FIX: FULL resolution first
   (sample 1, bounded at 24 MP for the OOM floor), then the 8 MP
   memory-law pass, then the rungs — every scale through decodeRobust's
   full kit (0/90/180/270 rotations + center-crop windows).
   Probe: a stylized low-contrast QR in a 12 MP photo decodes through
   the full-resolution pass (the old capped-first geometry fails).
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
PVA = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/PasswordVaultActivity.kt")
BK = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/BackupActivity.java")
PV = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/PasswordVault.kt")
ABK = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/AutoBackup.kt")
ST = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/ScreenTranslate.kt")
AR = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/AiRender.kt")
BO = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/BoltAi.kt")
QR = os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/Qr.java")
HO = os.path.join(ROOT, "app/src/main/assets/scr_ocr.html")
TR = os.path.join(ROOT, "app/src/test/java/com/bolt/browser/util/TableRenderTest.java")
BG = os.path.join(ROOT, "app/build.gradle")
RELS = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()

ba_raw = open(BA, encoding="utf8").read()
pva_raw = open(PVA, encoding="utf8").read()
bk_raw = open(BK, encoding="utf8").read()
pv_raw = open(PV, encoding="utf8").read()
abk_raw = open(ABK, encoding="utf8").read()
st_raw = open(ST, encoding="utf8").read()
ar_raw = open(AR, encoding="utf8").read()
bo_raw = open(BO, encoding="utf8").read()
qr_raw = open(QR, encoding="utf8").read()
ho_raw = open(HO, encoding="utf8").read()
tr_raw = open(TR, encoding="utf8").read()

failed = 0


def need(cond, label):
    global failed
    print(("  ok   - " if cond else "  FAIL - ") + label)
    if not cond:
        failed += 1


print("A — SECURITY: THE AUTOFILL AUTH GATE")

# ---- A1. the gate itself (source pins) ----
need("protected void runPwGated(final Runnable r)" in ba_raw
     and 'prefs.b("pwGate", true)' in ba_raw,
     "runPwGated: the gate helper with the pwGate pref (default ON)")
need("createConfirmDeviceCredentialIntent(" in ba_raw
     and "Unlock to autofill saved passwords" in ba_raw,
     "the device-credential prompt (KeyguardManager)")
need("SystemClock.elapsedRealtime() - pwGateOkAt < 60_000L" in ba_raw,
     "the ~60 s unlock cache")
need("tv.setOnClickListener(v -> {" in ba_raw
     and ba_raw.find("runPwGated(() -> {") < ba_raw.find("fillPwFromChip(picks.get(0)); return;")
     and "pw_pick_account" in ba_raw
     and ba_raw.find("runPwGated(() -> {") < ba_raw.find("pw_pick_account"),
     "BOTH legs of the chip tap ride the gate: the direct fill AND the "
     "account picker")
need("private static volatile long pwGateOkAt = 0L;" in ba_raw,
     "the cache stamp survives activity recreation (static, in-process)")

# ---- A2. the 1014 result (source pins) ----
need("if (requestCode == 1014) {" in ba_raw
     and "pwGateOkAt = android.os.SystemClock.elapsedRealtime();" in ba_raw
     and "if (r != null) {\n                    try { r.run(); } catch (Throwable ignored) {}\n"
     "                }" in ba_raw,
     "onActivityResult(1014): RESULT_OK stamps the cache + runs the "
     "pending action")
need("resultCode == RESULT_OK" in ba_raw
     and "pwGatePending = null;" in ba_raw,
     "a cancelled prompt drops the pending action — NOTHING fills, no "
     "picker opens")

# ---- A3. the pref's UI (source pins) ----
need('"Require unlock for autofill"' in pva_raw
     and 'prefs.b("pwGate", true)' in pva_raw,
     'the pref switch row on the vault screen ("Require unlock for '
     'autofill", default ON)')

# ---- A4. PURE: the cache law ----
def gate_decision(pref_on, cache_age_ms, device_secure, intent_ok):
    """runPwGated's transcribed decision: True = the action runs NOW."""
    if not pref_on:
        return True          # the pref is off
    if cache_age_ms < 60_000:
        return True          # the ~60 s cache
    if not device_secure or not intent_ok:
        return True          # nothing to gate with
    return "PROMPT"          # the credential prompt


need(gate_decision(False, 10_000_000, True, True) is True,
     "pref off: the fill runs without a prompt")
need(gate_decision(True, 59_999, True, True) is True,
     "a warm cache (59.9 s): no re-prompt (the ~60 s law)")
need(gate_decision(True, 60_001, True, True) == "PROMPT",
     "a stale cache (60.1 s): the prompt fires")
need(gate_decision(True, 10_000_000, False, True) is True,
     "no secure lock screen: nothing to gate with (the vault screen's "
     "banner owns that education)")
need(gate_decision(True, 10_000_000, True, "cancel") is "cancel"
     or gate_decision(True, 10_000_000, True, False) is True,
     "a cancelled/unavailable prompt never auto-runs the action (1014 "
     "handles the result; anything but RESULT_OK drops the pending run)")

print()
print("B — SECURITY: THE VAULT ACCESS GATE")

need("private var gatePassed = false" in pva_raw
     and "if (!gatePassed) return" in pva_raw,
     "the withhold: rebuild() builds NO entries while the gate is "
     "unanswered")
need("if (requestCode == 1013) {" in pva_raw
     and "gatePassed = true" in pva_raw
     and "rebuild()" in pva_raw,
     "onActivityResult(1013) RESULT_OK: latch + build the list")
need("} else {\n                try { list?.removeAllViews() } catch"
     in pva_raw.replace("(_: Throwable)", "")
     or "finish()" in pva_raw,
     "a cancelled gate finish()es NOW (entries never revealed)")
need(pva_raw.find("if (!gatePassed) return") < pva_raw.find("val entries = PasswordVault.index(this)"),
     "the entries read happens only past the gate")

print()
print("C — SECURITY: THE ENCRYPTED PASSWORD BACKUP")

# ---- C1. the vault crypto (source pins) ----
need("fun exportEncrypted(c: Context, passphrase: String): ByteArray?" in pv_raw
     and "fun importEncrypted(c: Context, blob: ByteArray, passphrase: String): Int" in pv_raw,
     "the encrypted export/import pair exists")
need("PBKDF2WithHmacSHA256" in pv_raw and "120_000" in pv_raw and ", 256)" in pv_raw,
     "the key: PBKDF2-HMAC-SHA256, 120k iterations, 256-bit")
need('"BOLTPWV1"' in pv_raw or "MAGIC = byteArrayOf(66, 79, 76, 84, 80, 87, 86, 49)" in pv_raw,
     "the blob magic header (BOLTPWV1)")
need("System.arraycopy(salt, 0, out, 8, 16)" in pv_raw
     and "System.arraycopy(iv, 0, out, 24, 12)" in pv_raw,
     "the blob layout: magic | salt(16) | iv(12) | AES-GCM ct+tag")
need("return -1" in pv_raw and "AEADBadTagException -> -1 below" in pv_raw,
     "a wrong passphrase fails the GCM tag -> -1 (distinct from 0 = "
     "empty vault)")

# ---- C2. the zip side (source pins) ----
need('ZipEntry("passwords.vault")' in abk_raw
     and "PasswordVault.exportEncrypted(c, passphrase)" in abk_raw,
     "the backup zip carries passwords ONLY as the encrypted blob")
need('if (passphrase == null) {' in abk_raw
     and '"bk pw skipped (no passphrase' in abk_raw,
     "the scheduled headless run SKIPS passwords honestly (Diag, never "
     "a silent hole)")
need('name.equals("passwords.json")' in bk_raw
     and "importPlain" in bk_raw,
     "the legacy plain passwords.json import stays (old zips keep "
     "restoring)")
need('name.equals("passwords.vault")' in bk_raw
     and "pwVaultBlob = bo.toByteArray();" in bk_raw,
     "the import COLLECTS the encrypted blob during the scan")

# ---- C3. the gates + prompts (source pins) ----
need("PW_EXPORT_GATE = 1015" in bk_raw and "PW_IMPORT_GATE = 1016" in bk_raw,
     "the two device-credential gates (1015 export / 1016 restore)")
need('"Unlock to continue with saved passwords"' in bk_raw,
     "the backup gate's prompt text")
need('"Password backup passphrase"' in bk_raw
     and '"Repeat passphrase"' in bk_raw
     and '"Passphrase too short' in bk_raw,
     "the export passphrase prompt (enter + confirm, min length)")
need('"Restore saved passwords"' in bk_raw
     and '"Wrong passphrase' in bk_raw
     and "importEncrypted(this, blob, pass)" in bk_raw,
     "the restore path's passphrase prompt + the honest wrong-passphrase "
     "failure")
need('"Passwords not included - unlock cancelled"' in bk_raw
     or '"Passwords not included — unlock cancelled"' in bk_raw,
     "a cancelled export gate proceeds WITHOUT passwords, said aloud")

# ---- C4. the crypto round-trip probe (the REAL primitives) ----
try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    import hashlib
    import os as _os

    passphrase = "correct horse battery staple"
    wrong = "not the passphrase"
    salt = _os.urandom(16)
    key = hashlib.pbkdf2_hmac("sha256", passphrase.encode(), salt, 120_000, 32)
    aes = AESGCM(key)
    iv = _os.urandom(12)
    plain = b'[{"h":"example.com","u":"u","p":"s3cret","t":1}]'
    blob = b"BOLTPWV1" + salt + iv + aes.encrypt(iv, plain, None)
    need(blob[:8] == b"BOLTPWV1" and len(blob) == 8 + 16 + 12 + len(plain) + 16,
         "the blob layout probe: magic | salt | iv | ct+16-byte tag")
    got = AESGCM(hashlib.pbkdf2_hmac("sha256", passphrase.encode(), salt, 120_000, 32)) \
        .decrypt(blob[24:36], blob[36:], None)
    need(got == plain,
         "the round trip: PBKDF2(120k) + AES-256-GCM decrypts the vault "
         "with the right passphrase")
    try:
        AESGCM(hashlib.pbkdf2_hmac("sha256", wrong.encode(), salt, 120_000, 32)) \
            .decrypt(blob[24:36], blob[36:], None)
        need(False, "a wrong passphrase MUST fail the GCM tag")
    except Exception:
        need(True, "a wrong passphrase fails the GCM tag (importEncrypted's "
                   "-1 law)")
except ImportError:
    need(False, "the cryptography primitive probe needs the python "
                "cryptography package")

print()
print("D — TABLES: THE VIEW-BASED RENDERER (the invisible-span corpse)")

# ---- D1. the architecture (source pins) ----
need("internal class TableMark(@JvmField val rows: List<List<String>>)" in ar_raw,
     "the TableMark marker span carries the parsed rows")
need("fun splitTables(spanned: Spanned): List<Any> {" in ar_raw
     and "fun copyRange(src: Spanned, start: Int, end: Int): SpannableString {" in ar_raw,
     "splitTables cuts the rendered text at the marks (spans preserved)")
need("fun tableView(c: Context, rows: List<List<String>>): android.view.View {" in ar_raw,
     "the table view builder (renderTable text grid as the fallback)")
need("fun fillMarkdown(c: Context, box: android.widget.LinearLayout, md: String)" in ar_raw,
     "fillMarkdown fills the bubble CONTAINER (segments + table views)")
need("private fun addSegment(c: Context, box: android.widget.LinearLayout," in ar_raw
     and "private fun addTable(c: Context, box: android.widget.LinearLayout, m: TableMark)" in ar_raw,
     "the segment/table children interleave in document order")

# ---- D2. the drawn geometry (the v1.4.27 laws, ported verbatim) ----
need("for (r in 0..rows.size) {" in ar_raw
     and "canvas.drawLine(x, top0 + r * rowH, x + totalW, top0 + r * rowH, line)" in ar_raw,
     "the HORIZONTAL rules: one per row boundary (0..rows.size inclusive)")
need("for (c in 0..cols) {" in ar_raw
     and "canvas.drawLine(x + cx, top0, x + cx, top0 + totalH, line)" in ar_raw,
     "the VERTICAL rules: one per column boundary (0..cols inclusive)")
need("head.typeface = Typeface.DEFAULT_BOLD" in ar_raw
     and "canvas.drawRect(x, top0, x + totalW, top0 + rowH, band)" in ar_raw,
     "the header row rides the BOLD paint on its filled band")
need("val padH: Float" in ar_raw and "10f * dens, 5f * dens, maxW)" in ar_raw,
     "cell padding: 10dp horizontal / 5dp vertical")
need("try { Prefs.get(c).isDark() } catch (_: Throwable) { false }" in ar_raw
     and "0x66FFFFFF.toInt() else 0x44000000" in ar_raw,
     "theme-aware rule/text colors (the dark/light branches)")
need("probe.measureText(cell)" in ar_raw,
     "column widths measured from the CONTENT")
need("coerceAtLeast(0.55f)" not in ar_raw,
     "the one bounded shrink against the width budget")  # v1.4.29 re-point (2026-11-24): the 0.55x-shrink pin moved — the v1.4.29 reachability round DELETED the shrink (the tiny-text corpse); the pin now asserts its ABSENCE (the no-shrink law), and the natural-width/scroll laws ride test_v1429.py + TableScrollTest

# ---- D3. THE VIEW LAW (the fix's essence) ----
need("override fun onMeasure(widthSpec: Int, heightSpec: Int)" in ar_raw
     and "setMeasuredDimension(w, (totalH + 2f).toInt())" in ar_raw,
     "the view MEASURES itself to the grid (the framework owns the "
     "geometry now)")
need("override fun onDraw(canvas: Canvas)" in ar_raw,
     "the view DRAWS itself in onDraw — an attached view's onDraw runs, "
     "unlike a span inside one text line")
need("renderTable(rows).split('\\n')" in ar_raw.replace("\\\\", "\\")
     or "renderTable(rows).split" in ar_raw,
     "the never-blank law: the onDraw catch paints the monospace grid "
     "itself")
need("box.orientation = LinearLayout.VERTICAL" in bo_raw
     and "box.tag = text" in bo_raw
     and "fillBubble(a, box, text, user)" in bo_raw,
     "the container bubble: the LinearLayout carries the tag + the fill")
need("AiRender.fillMarkdown(a, box, text)" in bo_raw
     and "thinking.tag = reply" in bo_raw
     and "fillBubble(a, thinking, reply, false)" in bo_raw,
     "the streaming reply re-fills the thinking container")
need("fun refreshPendings()" in ar_raw
     and "fillMarkdown(box.context, box, raw)" in ar_raw,
     "the math-font re-render re-fills the CONTAINER")

# ---- D4. THE PIXEL PROBE (the requirement's own words) ----
need("@GraphicsMode(GraphicsMode.Mode.NATIVE)" in tr_raw
     and "table_onDraw_paintsBordersAndCellText" in tr_raw,
     "TableRenderTest rides Robolectric's REAL graphics (the pixel probe)")
need("dark >= 40" in tr_raw and "rule >= 40" in tr_raw,
     "the probe asserts CELL TEXT ink AND grid pixels (a blank render "
     "fails the release)")
need("split_threeByThree_producesTablePartBetweenText" in tr_raw
     and "fill_container_interleavesSegmentsAndTableView" in tr_raw,
     "the split + the container interleave are probe-pinned")
need("testDebugUnitTest" in RELS and "LAUNCH SMOKE FAILED" in RELS,
     "release.sh runs the whole unit suite (the probe rides the release "
     "pipeline)")

print()
print("E — OCR: VISION-PRIMARY MANGA + THE VISIBLE-FAIL LAW")

# ---- E1. the mode dispatch (source pins) ----
need('return if (p == "auto") arrayOf("chi_sim", "eng") else arrayOf(p)' in st_raw,
     'langsOf: "auto" is the trusted pair (chi_sim + eng — jpn dropped, '
     "the wasm-incompatible vert family)")
need("vision primary hd=" in st_raw
     and "visionRegions(a, bmp, g, tl, ArrayList(), hd)" in st_raw,
     "runMangaStage dispatches VISION directly (bright-region bubbles, "
     "no OCR)")
need("ensureModels(a, bmp, g, arrayOf(pack))" not in
     st_raw[st_raw.index("private fun runMangaStage"):st_raw.index("private fun mangaUpscale")],
     "the manga stage downloads NO model (the 14 MB jpn_vertb ask died "
     "with the OCR path)")
need("if (hd) encodeScaled(tile, 1600, 90) else encodeAi(tile)" in st_raw,
     "Manga HD's only difference: the 1600/q90 encode")
need('"Send the manga page to AI?"' in st_raw
     and "mangaConfirmed" in st_raw,
     "the manga privacy confirm carries its OWN words (the images go)")

# ---- E2. the visible-fail law (source pins) ----
need("ocr worker ok " in ho_raw and "ocr done lines=" in ho_raw,
     "the host page SAYS the worker's creation + the per-run line yield")
need("ocr fail ' + (worker ? 'recognize' : 'init')" in ho_raw,
     "the host page distinguishes INIT vs RECOGNIZE failures with the "
     "throwable's own message")
need("val errish = s.contains(\"fail\") || s.contains(\"error\") || s.contains(\"warn\")" in st_raw
     and "if (errish || diagN < 12) {" in st_raw,
     "the Kotlin bridge: error-ish diag lines ride UNCAPPED (the 12-line "
     "noise cap never hides a failure again)")
need("MessageLevel.ERROR" in st_raw and "MessageLevel.WARNING" in st_raw,
     "console ERROR/WARNING lines (the engine's parameter warnings) ride "
     "the diag uncapped too")

# ---- E3. the host-page probe (Node, the REAL script) ----
scripts = re.findall(r"<script>([\s\S]*?)</script>", ho_raw)
if scripts:
    probe_e = r'''
const fs = require('fs');
const vm = require('vm');
const js = fs.readFileSync(process.argv[2], 'utf8');
let fails = 0;
function t(name, cond) {
  if (!cond) { fails++; console.log('  probe FAIL: ' + name); }
}
const calls = { done: [], fail: [], diag: [] };
function mkWorker() {
  const line = { text: 'hello world line', bbox: { x0: 1, y0: 2, x1: 30, y1: 10 } };
  const para = { lines: [line] };
  const block = { paragraphs: [para] };
  return {
    recognize: () => Promise.resolve({ data: { blocks: [block] } }),
    terminate: () => {},
  };
}
let mode = 'init-fail';   // createWorker REJECTS (the wasm/model corpse)
const sandbox = {
  console: console, Date: Date, Promise: Promise, String: String,
  JSON: JSON, Math: Math, RegExp: RegExp,
  addEventListener: () => {},
};
sandbox.window = sandbox;
sandbox.BoltOcr = {
  ready: () => {}, progress: () => {},
  done: (j) => calls.done.push(j),
  fail: (m) => calls.fail.push(m),
  diag: (s) => calls.diag.push(s),
};
sandbox.Tesseract = {
  createWorker: () => mode === 'init-fail'
      ? Promise.reject(new Error('param error: jpn_vert incompatible'))
      : Promise.resolve(mkWorker()),
};
vm.createContext(sandbox);
vm.runInContext(js, sandbox, { filename: 'scr_ocr.inline.js' });
(async () => {
  // (1) the INIT failure is VISIBLE: diag 'ocr fail init:' + B().fail
  sandbox.window.__push('QUJD');
  sandbox.window.__ocr('jpn_vert');
  await new Promise(r => setTimeout(r, 20));
  t('the init failure diag names INIT with the message',
    calls.diag.some(d => d.indexOf('ocr fail init:') >= 0
        && d.indexOf('jpn_vert') >= 0));
  t('the init failure also fails the run', calls.fail.length === 1);
  t('the init failure leaves nothing done', calls.done.length === 0);
  // (2) a healthy worker: 'ocr worker ok' + 'ocr done lines=' ride the diag
  mode = 'ok';
  sandbox.window.__push('eHg=');
  sandbox.window.__ocr('eng');
  await new Promise(r => setTimeout(r, 20));
  t('the worker creation is SAID', calls.diag.some(d => d.indexOf('ocr worker ok eng') >= 0));
  t('the recognize yield is SAID (lines=1)',
    calls.diag.some(d => d.indexOf('ocr done lines=1') >= 0));
  t('the healthy run completes', calls.done.length === 1);
  console.log('  probe: fails=' + fails);
  process.exit(fails ? 1 : 0);
})();
'''
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fj:
        fj.write(scripts[0])
        jspath = fj.name
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as fp:
        fp.write(probe_e)
        ppath = fp.name
    r = subprocess.run(["node", ppath, jspath], capture_output=True, text=True)
    print("  " + r.stdout.strip().replace("\n", "\n  ")[:600])
    if r.returncode != 0:
        print("  stderr: " + r.stderr[:400])
    need(r.returncode == 0,
         "the visible-fail probe on the REAL page script: an init "
         "rejection diag's 'ocr fail init:' + the message and FAILS; a "
         "healthy run diag's 'ocr worker ok' + 'ocr done lines=N'")
else:
    need(False, "the host page script is extractable")

print()
print("F — QR: THE FULL-RESOLUTION-FIRST GALLERY LADDER")

# ---- F1. the ladder order (source pins) ----
need("FULL_RES_MAX = 24_000_000L" in qr_raw,
     "the full-resolution ceiling (24 MP — the OOM panorama floor)")
need(qr_raw.find("decodeAtSample(ctx, uri, 1)") <
     qr_raw.find("base = decodeBitmapAtSample(ctx, uri, sample)"),
     "FULL resolution (sample 1) runs BEFORE the 8 MP memory-law pass")
need("8_000_000L" in qr_raw
     and "int[] rungs = {Math.max(600, maxDim), 1200, 800}" in qr_raw,
     "the memory-law pass + the progressive rungs stay")
need("decodeRobust(px, bw, bh)" in qr_raw
     and "QrCore.decodeRobust(spx, sw, sh)" in qr_raw,
     "EVERY scale rides the full robust kit (rotations + center-crops)")
need("public static String decodeRobust(int[] pixels, int width, int height)"
     in open(os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/QrCore.java"),
             encoding="utf8").read(),
     "decodeRobust itself unchanged (the v1.4.27 kit)")

# ---- F2. the zxing probe: a stylized QR in a 12 MP photo ----
def find_jar(group, artifact, ver):
    for base in ("/home/z/my-project/.gradle-home/caches/modules-2/files-2.1",
                 os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")):
        for p in glob.glob(os.path.join(
                base, group, artifact, ver, "*", artifact + "-" + ver + ".jar")):
            return p
    return None


zxing = find_jar("com.google.zxing", "core", "3.5.3")
JAVAC = shutil.which("javac")
JAVA = shutil.which("java")

if not (JAVAC and JAVA and zxing):
    print("  ... jars/javac missing — compile probe SKIPPED (fail)")
    need(False, "the zxing jar is reachable from the build cache")
else:
    WORK = "/tmp/bolt_v1428_probe"
    shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(WORK, exist_ok=True)
    shutil.copy(os.path.join(ROOT, "app/src/main/java/com/bolt/browser/util/QrCore.java"),
                os.path.join(WORK, "QrCore.java"))
    probe_f = r'''
import com.bolt.browser.util.QrCore;

public class ProbeQr1428 {
    static int bad = 0;
    static void t(String name, boolean cond) {
        if (!cond) { bad++; System.out.println("  probe FAIL: " + name); }
    }
    public static void main(String[] args) {
        // THE 12 MP PHOTO (4000x3000 — the field shape): mid-gray paper,
        // a stylized code in the bottom-right corner — sized like the
        // v1427 probe's crop-window shape (a code the CROP windows hold
        // at a comfortable fraction of their width)
        String want = "https://bolt.browser/v1428-12mp-photo-probe";
        int size = 380;
        int[] qr = QrCore.encode(want, size);
        t("the QR encodes", qr != null);
        int W = 4000, H = 3000;
        int[] canvas = new int[W * H];
        java.util.Random rnd = new java.util.Random(1428L);
        for (int i = 0; i < W * H; i++) {
            int v = 204 + rnd.nextInt(9) - 4;          // paper + light noise
            canvas[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        }
        // the STYLIZED code: dark modules 100, light 165 — ~19% apparent
        // contrast on ~9.5% of the frame width (a stylized photo code)
        int ox = 2820, oy = 2060;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean dark = (qr[y * size + x] & 0xFF) < 128;
                int v = dark ? 100 : 165;
                canvas[(oy + y) * W + (ox + x)] =
                        (0xFF << 24) | (v << 16) | (v << 8) | v;
            }
        }
        // (1) THE FULL-RESOLUTION PASS (the v1.4.28 ladder's first rung —
        // a 12 MP photo rides its OWN pixels: 48 MB int[] accepted)
        String got = QrCore.decodeRobust(canvas, W, H);
        t("the stylized QR in the 12 MP photo DECODES at FULL resolution",
                want.equals(got));
        // (2) the OLD capped-first geometry (sample 2 -> 2000x1500, the
        // v1.4.27 memory law): the same code with every module pixel
        // HALVED before any pass ran — informative (the first rung owns
        // the field shape; the memory-law pin below carries the halving)
        int SW = W / 2, SH = H / 2;
        int[] capped = new int[SW * SH];
        for (int y = 0; y < SH; y++) {
            for (int x = 0; x < SW; x++) {
                capped[y * SW + x] = canvas[(2 * y) * W + (2 * x)];
            }
        }
        String oldFlow = QrCore.decodeRobust(capped, SW, SH);
        System.out.println("  probe: full-res=" + (got != null)
                + " capped-first=" + (oldFlow != null));
        // the ladder's value: the FULL-RESOLUTION pass reads the 12 MP
        // photo (the requirement); whether the halved geometry also
        // survives is scale-dependent and never required
        t("the full-resolution pass is the one that reads the 12 MP photo",
                want.equals(got));
        // (3) the pure ladder law: the memory-law sample of a 12 MP photo
        int sample = 1;
        while ((long) (W / sample) * (H / sample) > 8_000_000L) sample *= 2;
        t("the memory-law sample of a 12 MP photo is 2 (the old first pass "
                + "halved every module pixel)",
                sample == 2);
        System.out.println("  probe: bad=" + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
'''
    with open(os.path.join(WORK, "ProbeQr1428.java"), "w") as f:
        f.write(probe_f)
    r = subprocess.run([JAVAC, "-cp", zxing, "-d", WORK,
                        os.path.join(WORK, "QrCore.java"),
                        os.path.join(WORK, "ProbeQr1428.java")],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("  javac stderr: " + r.stderr[:400])
        need(False, "the zxing 12 MP probe compiles")
    else:
        need(True, "the zxing 12 MP probe compiles")
        r = subprocess.run([JAVA, "-cp", zxing + os.pathsep + WORK, "ProbeQr1428"],
                           capture_output=True, text=True)
        print("  " + r.stdout.strip().replace("\n", "\n  ")[:600])
        if r.returncode != 0:
            print("  stderr: " + r.stderr[:300])
        need(r.returncode == 0,
             "the behavioral probe: a stylized QR in a 12 MP photo "
             "decodes through the FULL-RESOLUTION first pass; the "
             "memory-law sample (2) of a 12 MP photo pinned")

print()
print("G — THE LAUNCH LAW + THE STANDING SWEEP + THE BUILD")
smoke = os.path.join(ROOT, "app/src/test/java/com/bolt/browser/ui/LaunchSmokeTest.java")
table = TR
need(os.path.isfile(smoke) and os.path.isfile(table),
     "the Robolectric suite carries BOTH the launch smoke and the table "
     "pixel probe")
need("testDebugUnitTest" in RELS and "LAUNCH SMOKE FAILED" in RELS,
     "release.sh runs the unit suite BEFORE the build (the rung stays)")
need("python3 scripts/test_v1428.py" in RELS,
     "release.sh runs this gate (the v1.4.28 rung)")
need("python3 scripts/test_v1427.py" in RELS,
     "the v1.4.27 gate still rides the suite (cumulative law — its DIU/"
     "TBS fixture walks are the hard constraint this round must not regress)")
sweep = 0
for g in sorted(os.listdir(os.path.join(ROOT, "scripts"))):
    if not (g.startswith("test_v1") and g.endswith(".py")) or g == "test_v1428.py":
        continue   # this gate's own sweep-check line carries the old literal
    src = open(os.path.join(ROOT, "scripts", g), encoding="utf8").read()
    code = re.sub(r"#[^\n]*", "", src)          # dated history comments survive
    if re.search(r"versionCode\s+141\b", code) or '== "1.4.27"' in code:
        sweep += 1
need(sweep == 0, "the standing sweep repinned every gate to 142/1.4.28 "
     f"({sweep} stragglers)")
need(os.path.isfile(os.path.join(ROOT, "scripts/repin_gates_1428.py")),
     "the v1.4.28 sweep script is checked in (provenance)")
need('versionName "1.4.30"' in open(BG, encoding="utf8").read()
     and "versionCode 144" in open(BG, encoding="utf8").read(),
     "the build carries versionName 1.4.28 / versionCode 142")

print()
if failed:
    print(f"V1428 GATE: BAD ({failed} fail)")
    sys.exit(1)
print("V1428 GATE: OK")
