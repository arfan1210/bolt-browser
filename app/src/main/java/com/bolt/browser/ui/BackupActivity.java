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
    private Prefs prefs;
    private EditText nameField;

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
        // passwords (still opt-in: the zip copy is plain JSON).
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
                "DECRYPTED, unencrypted JSON in the zip — keep the file safe and delete it after importing."));

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
        // v1.3.40: the zip builder lives in util/AutoBackup.kt now — the
        // manual button and the weekly scheduler share one implementation
        // (same component toggles, same Downloads destination).
        new Thread(() -> {
            try {
                String fname = com.bolt.browser.util.AutoBackup.backupNow(this, "BoltBackup");
                runOnUiThread(() -> Toast.makeText(this,
                        "Exported to Downloads/" + fname, Toast.LENGTH_LONG).show());
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
        }
    }

    /** v1.3.94 — STAGED IMPORT. The v1.3.93-and-older import wrote the
     * zip's files STRAIGHT into filesDir while the live process kept
     * running — and every live writer (Prefs.set rewriting prefs.json,
     * TabManager.save on every tab event, the speed-dial store) flushed its
     * STALE in-memory state back over the imported files within seconds:
     * the v1.3.93 field report ("no tabs are imported, no settings or
     * workspace are here; I do see the authenticator") is exactly this —
     * the authenticator.json has no live writer, everything else did.
     * Now the import STAGES the files into filesDir/.bolt-import/ (the
     * live singletons never touch that tree), BoltApp applies the tree at
     * the very top of the NEXT process start (attachBaseContext — before
     * Prefs/TabManager construct), and this screen then relaunches the app
     * (alarm + exit — never startActivity+finish, the v1.3.86 task-hop
     * race lesson) so the clean load is immediate. */
    private void importBackup(final Uri uri) {
        new Thread(() -> {
            int files = 0;
            int pws = 0;
            java.io.File staging = new java.io.File(prefs.filesDir(), ".bolt-import");
            deleteTree(staging);
            staging.mkdirs();
            try (ZipInputStream z = new ZipInputStream(
                    getContentResolver().openInputStream(uri))) {
                ZipEntry e;
                byte[] b = new byte[8192];
                while ((e = z.getNextEntry()) != null) {
                    String name = e.getName();
                    if (name.endsWith("/")) continue;
                    // v1.3.38: the password entry is the DECRYPTED vault —
                    // merge it through the vault (it re-encrypts on save),
                    // never a raw file copy. The vault's plain index is
                    // derived, never imported. (The vault merge is safe to
                    // run live: it writes through the vault's own save.)
                    if (name.equals("passwords.json")) {
                        try {
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            int n;
                            while ((n = z.read(b)) > 0) bo.write(b, 0, n);
                            org.json.JSONArray arr =
                                    new org.json.JSONArray(bo.toString("UTF-8"));
                            pws = com.bolt.browser.util.PasswordVault.importPlain(this, arr);
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
            final int pwCount = pws;
            runOnUiThread(() -> {
                Toast.makeText(this,
                        "Imported " + count + " files" + (pwCount > 0
                                ? " + " + pwCount + " saved logins" : "")
                                + " — restarting to apply",
                        Toast.LENGTH_LONG).show();
                restartApp();
            });
        }, "import").start();
    }

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
