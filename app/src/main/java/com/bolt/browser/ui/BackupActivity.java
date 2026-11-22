package com.bolt.browser.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bolt.browser.R;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Prefs;
import com.bolt.browser.util.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Browser login name + backup/export & import (zip of all JSON data + scripts + filters). */
public class BackupActivity extends AppCompatActivity {

    private static final int IMPORT_REQ = 91;
    /** v1.4.28 — the device-credential gates: 1015 the password-inclusive
     *  EXPORT (passphrase prompt follows), 1016 the password RESTORE. */
    private static final int PW_EXPORT_GATE = 1015;
    private static final int PW_IMPORT_GATE = 1016;
    private Prefs prefs;
    private EditText nameField;
    /** the gate's continuation (runs on RESULT_OK; a cancel takes the
     *  no-passwords branch). */
    private Runnable pwGateNext;
    /** v1.4.28 — the import's staged password payloads: the legacy plain
     *  JSON (old zips) and/or the encrypted vault blob (passwords.vault). */
    private org.json.JSONArray pwPlainLegacy;
    private byte[] pwVaultBlob;
    private int importFileCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Fx.theme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_generic_list);
        prefs = Prefs.get(this);
        ((TextView) findViewById(R.id.tv_g_title)).setText(R.string.backup_export);
        int dim = Fx.dim(this);
        findViewById(R.id.btn_g_back).post(() ->
                ((android.widget.ImageButton) findViewById(R.id.btn_g_back)).setColorFilter(dim));
        findViewById(R.id.btn_g_back).setOnClickListener(v -> finish());
        findViewById(R.id.rv_g).setVisibility(View.GONE);

        // v1.3.93: the content outgrew the screen in v1.3.39 (ten component
        // toggles + the schedule section) but the container stayed a plain
        // match_parent LinearLayout — Export/Import sat clipped below the
        // fold with NO way to scroll (every sibling screen scrolls; this one
        // never had the ScrollView). Same shape as PasswordVaultActivity:
        // ScrollView (fillViewport, no glow) + wrap-height child + weight-1
        // slot below the 64dp header.
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 18);
        list.setPadding(p, p, p, p + Fx.dp(this, 12));
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.addView(list, new LinearLayout.LayoutParams(-1, -2));
        ((android.view.ViewGroup) findViewById(R.id.btn_g_back).getParent().getParent())
                .addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        // profile name
        nameField = new EditText(this);
        nameField.setText(prefs.profileName());
        nameField.setHint("Your name (used in backups)");
        list.addView(label("Browser login"));
        list.addView(nameField, new LinearLayout.LayoutParams(-1, -2));

        // v1.3.39 — CHOOSE WHAT RIDES ALONG (field report: "In the export,
        // give me a toggle to controll everything I want to choose to backup
        // or not"). One switch per backup component, all ON by default except
        // passwords (v1.4.28: still opt-in — and the zip copy is now
        // PASSPHRASE-ENCRYPTED ciphertext, never the old plain JSON).
        list.addView(label("Include in backup"));
        list.addView(itemToggle("bkSettings", "Settings", true,
                "All app settings (prefs.json) — skins, toggles, engine choice."));
        list.addView(itemToggle("bkSites", "Site settings", true,
                "Per-site overrides (sites.json) — adblock, UA, dark, permissions."));
        list.addView(itemToggle("bkBookmarks", "Bookmarks", true,
                "Bookmarks with their folders."));
        list.addView(itemToggle("bkHistory", "History", true,
                "The browsing history list."));
        list.addView(itemToggle("bkTabs", "Tab groups + open tabs", true,
                "groups.json + tabs.json + workspaces.json + sessions.json + the closed-tab bin — the session's tab set, workspaces and recovery."));
        list.addView(itemToggle("bkSpeeddial", "Home shortcuts", true,
                "The speed-dial grid (with folders)."));
        list.addView(itemToggle("bkWatchlater", "Watch-later queue", true,
                "The saved-for-later list."));
        list.addView(itemToggle("bkScripts", "Userscripts", true,
                "Every installed .user.js and its state."));
        list.addView(itemToggle("bkFilters", "Filter lists", true,
                "Adblock filter lists, including custom ones."));
        list.addView(itemToggle("bkAuth", "Authenticator (2FA keys)", true,
                "Plain JSON by design — the ONLY way a restore works on another device. Keep the zip safe."));
        list.addView(itemToggle("backupPw", "Saved passwords", false,
                "Encrypted with a passphrase you set at export (device unlock " +
                "asked first) — restorable on any device. Off by default."));

        // v1.3.40 — SCHEDULED AUTO-BACKUP (round-4 #14): opt-in weekly
        // export to Downloads with a stamped filename; the row right below
        // shows when the last backup (manual or scheduled) landed.
        list.addView(label("Schedule"));
        list.addView(itemToggle("autoBackup", "Weekly auto-backup", false,
                "A few seconds after you open Bolt, once a week: the same toggleable backup set lands in Downloads as BoltBackup-auto-<date>.zip."));
        TextView lastLine = new TextView(this);
        lastLine.setText("Last backup: " + com.bolt.browser.util.AutoBackup.lastBackupLine(this));
        lastLine.setTextSize(13);
        lastLine.setTextColor(Fx.dim(this));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = Fx.dp(this, 8);
        list.addView(lastLine, llp);

        // v1.3.34 — modern buttons (filled primary, quiet secondary)
        Button export = com.bolt.browser.util.Ui.filled(this, "Export backup (.zip)");
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, Fx.dp(this, 48));
        elp.topMargin = Fx.dp(this, 24);
        export.setOnClickListener(v -> doExport());
        list.addView(export, elp);

        Button imp = com.bolt.browser.util.Ui.quiet(this, "Import backup");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, Fx.dp(this, 48));
        ilp.topMargin = Fx.dp(this, 10);
        imp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("application/zip");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(Intent.createChooser(i, "Pick backup"), IMPORT_REQ);
            } catch (Throwable e) {
                Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
            }
        });
        list.addView(imp, ilp);

        TextView note = new TextView(this);
        note.setText("Only the switched-on parts are written to the zip. Not included "
                + "ever: downloads, offline pages, cookies/site data (they stay in "
                + "Bolt's private storage).");
        note.setTextSize(13);
        note.setTextColor(dim);
        note.setPadding(0, Fx.dp(this, 18), 0, 0);
        list.addView(note, new LinearLayout.LayoutParams(-1, -2));
    }

    /** one backup-component row with its toggle. */
    private View itemToggle(String key, String title, boolean def, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackground(Fx.cardBg(this, 0));
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 13), Fx.dp(this, 16), Fx.dp(this, 13));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(Fx.txt(this));
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Ui.ModernSwitch sw = Ui.modernSwitch(this, prefs.b(key, def), on -> prefs.set(key, on));
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(v -> sw.toggle(true));
        com.bolt.browser.util.MenuCards.explain(row, this, title, hint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Fx.dp(this, 5);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTypeface(com.bolt.browser.util.Ui.medium());
        t.setTextColor(Fx.dim(this));
        t.setPadding(0, Fx.dp(this, 16), 0, Fx.dp(this, 6));
        return t;
    }

    private void doExport() {
        if (nameField != null) prefs.set("profileName", nameField.getText().toString().trim());
        // v1.4.28 — THE PASSWORD EXPORT GATE (the security round's ask):
        // when the (default-off) Saved-passwords switch is ON, the export
        // first requires the DEVICE CREDENTIAL (a cancelled prompt exports
        // WITHOUT the passwords part — never silently, a toast says so),
        // then asks for the passphrase whose derived key re-encrypts the
        // vault into the zip. Without the switch the export runs as before
        // (v1.3.40: the zip builder lives in util/AutoBackup.kt — the manual
        // button and the weekly scheduler share one implementation).
        if (prefs.b("backupPw", false)) {
            if (gateDeviceCredential(PW_EXPORT_GATE,
                    () -> askExportPassphrase())) return;   // gate launched
            askExportPassphrase();
            return;
        }
        runExport(null);
    }

    /** v1.4.28 — launch the device-credential gate; false when the
     *  continuation already ran (nothing to gate with / the intent is
     *  unavailable). TRUE = the prompt is up and [onOk] rides RESULT_OK. */
    private boolean gateDeviceCredential(int req, Runnable onOk) {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    getSystemService(KEYGUARD_SERVICE);
            if (km == null || !km.isDeviceSecure()) { onOk.run(); return false; }
            android.content.Intent i = km.createConfirmDeviceCredentialIntent(
                    "Bolt is locked", "Unlock to continue with saved passwords");
            if (i == null) { onOk.run(); return false; }
            pwGateNext = onOk;
            startActivityForResult(i, req);
            return true;
        } catch (Throwable t) {
            onOk.run();
            return false;
        }
    }

    /** v1.4.28 — the passphrase prompt for the password-inclusive export:
     *  passphrase + confirm (both masked), min 4 chars. OK rides the
     *  export thread with the passphrase; Cancel exports WITHOUT the
     *  passwords (said aloud — never a silent hole). */
    private void askExportPassphrase() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Fx.dp(this, 8);
        col.setPadding(pad, pad, pad, 0);
        EditText p1 = new EditText(this);
        p1.setHint("Passphrase (min 4 characters)");
        p1.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        p1.setTextColor(Fx.txt(this));
        p1.setHintTextColor(Fx.dim(this));
        EditText p2 = new EditText(this);
        p2.setHint("Repeat passphrase");
        p2.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        p2.setTextColor(Fx.txt(this));
        p2.setHintTextColor(Fx.dim(this));
        col.addView(p1, new LinearLayout.LayoutParams(-1, -2));
        col.addView(p2, new LinearLayout.LayoutParams(-1, -2));
        TextView note = new TextView(this);
        note.setText("The saved-passwords part of the zip is encrypted with " +
                "this passphrase (AES-256-GCM). It is never stored — if you " +
                "forget it, that zip's passwords cannot be recovered.");
        note.setTextSize(12);
        note.setTextColor(Fx.dim(this));
        note.setPadding(0, Fx.dp(this, 8), 0, 0);
        col.addView(note, new LinearLayout.LayoutParams(-1, -2));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Password backup passphrase")
                .setView(col)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String a = p1.getText().toString();
                    String b = p2.getText().toString();
                    if (a.length() < 4) {
                        Toast.makeText(this, "Passphrase too short — passwords " +
                                "not included", Toast.LENGTH_LONG).show();
                        runExport(null);
                    } else if (!a.equals(b)) {
                        Toast.makeText(this, "Passphrases differ — passwords " +
                                "not included", Toast.LENGTH_LONG).show();
                        runExport(null);
                    } else {
                        runExport(a);
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    Toast.makeText(this, "Passwords not included — cancelled",
                            Toast.LENGTH_LONG).show();
                    runExport(null);
                })
                .show();
    }

    /** the export thread ([passphrase] null = without the passwords part). */
    private void runExport(final String passphrase) {
        new Thread(() -> {
            try {
                String fname = com.bolt.browser.util.AutoBackup.backupNow(this,
                        "BoltBackup", passphrase);
                runOnUiThread(() -> Toast.makeText(this,
                        "Exported to Downloads/" + fname
                                + (passphrase != null ? " (passwords encrypted)" : ""),
                        Toast.LENGTH_LONG).show());
            } catch (Throwable e) {
                runOnUiThread(() -> Toast.makeText(this, "Export failed: " + e,
                        Toast.LENGTH_LONG).show());
            }
        }, "export").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMPORT_REQ && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importBackup(data.getData());
            return;
        }
        // v1.4.28 — THE PASSWORD GATES' RESULTS. RESULT_OK runs the waiting
        // continuation (the passphrase prompt — export, or the merge —
        // import); a CANCEL takes the no-passwords branch, SAID ALOUD
        // (export without the passwords / import skips them and restarts).
        if (requestCode == PW_EXPORT_GATE || requestCode == PW_IMPORT_GATE) {
            Runnable r = pwGateNext;
            pwGateNext = null;
            if (resultCode == RESULT_OK && r != null) {
                try { r.run(); } catch (Throwable ignored) {}
            } else if (r != null) {
                if (requestCode == PW_EXPORT_GATE) {
                    Toast.makeText(this, "Passwords not included — unlock cancelled",
                            Toast.LENGTH_LONG).show();
                    runExport(null);
                } else {
                    Toast.makeText(this, "Saved passwords skipped — unlock cancelled",
                            Toast.LENGTH_LONG).show();
                    finishImport();
                }
            }
        }
    }

    /** v1.3.94 — STAGED IMPORT. The v1.3.93-and-older import wrote the
     *  zip's files STRAIGHT into filesDir while the live process kept
     *  running — and every live writer (Prefs.set rewriting prefs.json,
     *  TabManager.save on every tab event, the speed-dial store) flushed its
     *  STALE in-memory state back over the imported files within seconds:
     *  the v1.3.93 field report ("no tabs are imported, no settings or
     *  workspace are here; I do see the authenticator") is exactly this —
     *  the authenticator.json has no live writer, everything else did.
     *  Now the import STAGES the files into filesDir/.bolt-import/ (the
     *  live singletons never touch that tree), BoltApp applies the tree at
     *  the very top of the NEXT process start (attachBaseContext — before
     *  Prefs/TabManager construct), and this screen then relaunches the app
     *  (alarm + exit — never startActivity+finish, the v1.3.86 task-hop
     *  race lesson) so the clean load is immediate.
     *  v1.4.28 — THE PASSWORD MERGE IS GATED (the security round's ask):
     *  the zip's password payload — the legacy plain `passwords.json` (old
     *  zips) or the encrypted `passwords.vault` blob — is COLLECTED during
     *  the scan and merged ONLY after the device-credential gate answers
     *  RESULT_OK (the encrypted blob additionally asks the passphrase — a
     *  wrong passphrase fails the GCM tag and merges nothing). Cancelling
     *  the gate skips the passwords, says so, and restarts for the rest. */
    private void importBackup(final Uri uri) {
        new Thread(() -> {
            int files = 0;
            java.io.File staging = new java.io.File(prefs.filesDir(), ".bolt-import");
            deleteTree(staging);
            staging.mkdirs();
            pwPlainLegacy = null;
            pwVaultBlob = null;
            try (ZipInputStream z = new ZipInputStream(
                    getContentResolver().openInputStream(uri))) {
                ZipEntry e;
                byte[] b = new byte[8192];
                while ((e = z.getNextEntry()) != null) {
                    String name = e.getName();
                    if (name.endsWith("/")) continue;
                    // v1.4.28 — the password payloads are COLLECTED here and
                    // merged later behind the gate: the legacy DECRYPTED
                    // vault JSON (old zips — kept working) and the encrypted
                    // passwords.vault blob. Never a raw file copy — the
                    // merge rides the vault's own save (re-encrypts under
                    // THIS device's keystore key); the vault's plain index
                    // is derived, never imported.
                    if (name.equals("passwords.json")) {
                        try {
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            int n;
                            while ((n = z.read(b)) > 0) bo.write(b, 0, n);
                            pwPlainLegacy = new org.json.JSONArray(bo.toString("UTF-8"));
                        } catch (Throwable ignored) {}
                        files++;
                        continue;
                    }
                    if (name.equals("passwords.vault")) {
                        try {
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            int n;
                            while ((n = z.read(b)) > 0) bo.write(b, 0, n);
                            pwVaultBlob = bo.toByteArray();
                        } catch (Throwable ignored) {}
                        files++;
                        continue;
                    }
                    if (name.equals("passwords.dat") || name.equals("passwords.index.json")) {
                        continue;   // device-bound ciphertext / derived index
                    }
                    java.io.File dst = new java.io.File(staging, name);
                    // v1.3.93 — zip-slip guard (re-anchored at the STAGING
                    // base in v1.3.94): a hostile "backup" zip entry named
                    // ../x must not write outside the tree it belongs to.
                    if (!dst.getCanonicalPath()
                            .startsWith(staging.getCanonicalPath() + File.separator)) {
                        continue;
                    }
                    dst.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(dst)) {
                        int n;
                        while ((n = z.read(b)) > 0) out.write(b, 0, n);
                    }
                    files++;
                }
            } catch (Throwable ex) {
                // a half-staged tree must NEVER apply later — wipe it
                deleteTree(staging);
                runOnUiThread(() -> Toast.makeText(this, "Import failed",
                        Toast.LENGTH_LONG).show());
                return;
            }
            final int count = files;
            runOnUiThread(() -> {
                importFileCount = count;
                continueImport();
            });
        }, "import").start();
    }

    /** v1.4.28 — the staged scan finished: no password payload -> restart
     *  now; a payload -> the device-credential gate first (its cancel
     *  skips the passwords, says so, restarts for the rest of the backup). */
    private void continueImport() {
        if (pwPlainLegacy == null && pwVaultBlob == null) {
            finishImport();
            return;
        }
        if (gateDeviceCredential(PW_IMPORT_GATE, this::continueImportAfterGate)) return;
        continueImportAfterGate();
    }

    /** past the gate: the encrypted blob asks its passphrase; a legacy
     *  plain zip merges right away. */
    private void continueImportAfterGate() {
        if (pwVaultBlob != null) {
            askImportPassphrase();
            return;
        }
        mergePlainLegacy();
    }

    /** v1.4.28 — the passphrase prompt for the encrypted vault blob. OK
     *  decrypts + merges on a worker thread (PBKDF2 + the keystore writes
     *  never touch the UI thread); a wrong passphrase says so and costs
     *  only the passwords part. Cancel skips the passwords, said aloud. */
    private void askImportPassphrase() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Fx.dp(this, 8);
        col.setPadding(pad, pad, pad, 0);
        EditText p1 = new EditText(this);
        p1.setHint("Backup passphrase");
        p1.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        p1.setTextColor(Fx.txt(this));
        p1.setHintTextColor(Fx.dim(this));
        col.addView(p1, new LinearLayout.LayoutParams(-1, -2));
        TextView note = new TextView(this);
        note.setText("This backup carries encrypted saved passwords. Enter " +
                "the passphrase set at export to restore them on this device.");
        note.setTextSize(12);
        note.setTextColor(Fx.dim(this));
        note.setPadding(0, Fx.dp(this, 8), 0, 0);
        col.addView(note, new LinearLayout.LayoutParams(-1, -2));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Restore saved passwords")
                .setView(col)
                .setPositiveButton(R.string.save, (d, w) -> {
                    final String pass = p1.getText().toString();
                    final byte[] blob = pwVaultBlob;
                    new Thread(() -> {
                        int pws = -1;
                        try {
                            if (blob != null && pass.length() > 0) {
                                pws = com.bolt.browser.util.PasswordVault
                                        .importEncrypted(this, blob, pass);
                            }
                        } catch (Throwable ignored) {}
                        final int pwCount = pws;
                        runOnUiThread(() -> {
                            if (pwCount < 0) {
                                Toast.makeText(this, "Wrong passphrase — saved " +
                                        "passwords not restored", Toast.LENGTH_LONG).show();
                            }
                            finishImport(pwCount > 0 ? pwCount : 0);
                        });
                    }, "pw-import").start();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    Toast.makeText(this, "Saved passwords skipped",
                            Toast.LENGTH_LONG).show();
                    finishImport();
                })
                .show();
    }

    /** the legacy plain JSON merge (old zips — behind the same gate). */
    private void mergePlainLegacy() {
        final org.json.JSONArray arr = pwPlainLegacy;
        new Thread(() -> {
            int pws = 0;
            try {
                if (arr != null) pws = com.bolt.browser.util.PasswordVault
                        .importPlain(this, arr);
            } catch (Throwable ignored) {}
            final int pwCount = pws;
            runOnUiThread(() -> finishImport(pwCount));
        }, "pw-import").start();
    }

    /** the toast + the staged-tree restart (the staged files apply at the
     *  next process start; [pws] the merged login count, 0 when none). */
    private void finishImport(int pws) {
        Toast.makeText(this,
                "Imported " + importFileCount + " files" + (pws > 0
                        ? " + " + pws + " saved logins" : "")
                        + " — restarting to apply",
                Toast.LENGTH_LONG).show();
        restartApp();
    }

    private void finishImport() { finishImport(0); }
    /** best-effort recursive delete (staging cleanup). */
    private static void deleteTree(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] ch = f.listFiles();
        if (ch != null) for (java.io.File c : ch) deleteTree(c);
        try { f.delete(); } catch (Throwable ignored) {}
    }

    /** v1.3.94 — relaunch into a clean process (alarm + exit). The alarm
     * fires ~1.5 s after this process dies; the fresh start's first act
     * (BoltApp.attachBaseContext) applies the staged tree. */
    private void restartApp() {
        try {
            android.content.Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                        this, 0, i,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE);
                android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
                am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 1500, pi);
            }
        } catch (Throwable ignored) {}
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
