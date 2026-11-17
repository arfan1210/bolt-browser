package com.bolt.browser.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.bolt.browser.R;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.GaMigration;
import com.bolt.browser.util.Prefs;
import com.bolt.browser.util.Qr;
import com.bolt.browser.util.Totp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * v1.3.31 — Bolt's built-in authenticator; v1.3.32 — the Google Authenticator
 * TRANSFER bridge.
 *
 * Google ships no sync API for Google Authenticator (its Google-account
 * cloud sync is end-to-end encrypted and closed; the browser extensions
 * people know are from OTHER vendors — Authy/Microsoft/Bitwarden-class).
 * So the bridge here is the two sanctioned QR paths:
 *
 *   IN  (GA → Bolt):  Google Authenticator ⋮ → Transfer accounts → Export
 *                     accounts shows QR(s) — screenshot it, then here:
 *                     + → "Import from Google Authenticator" → pick the
 *                     screenshot. The otpauth-migration:// protobuf inside
 *                     is parsed by {@link GaMigration}; >10 accounts come
 *                     as several QRs, imported one by one (dedupe by
 *                     secret, so nothing doubles up).
 *
 *   OUT (Bolt → GA):  long-press an account → "Show QR code" renders its
 *                     otpauth:// URI as a scannable QR — Google
 *                     Authenticator's own + → "Scan a QR code" reads it.
 *
 * Rows: live code + countdown (1 s rebind while resumed). Tap = copy;
 * long-press = QR export / copy link / delete. The + button also still adds
 * by pasted setup key or otpauth:// link (otpauth:// links clicked in pages
 * are intercepted by BoltClient the same way).
 *
 * MAIN process only — never registered in the incognito task (identity
 * data, same policy as passkeys).
 */
public class AuthenticatorActivity extends BaseListActivity {

    private static final int IMPORT_IMG_REQ = 92;

    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable tickRun = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                build();
                tick.postDelayed(this, 1000);   // the anonymous Runnable itself
            }
        }
    };

    @Override
    protected String title() { return getString(R.string.authenticator); }

    @Override
    protected void build() {
        // v1.3.35: the modern empty state (icon medallion + guidance line)
        emptyIcon = R.drawable.ic_lock;
        emptyTitleRes = R.string.empty_auth_title;
        emptySubRes = R.string.empty_auth_sub;
        // the + action button (shared generic-list header) adds an account
        actionBtn.setVisibility(View.VISIBLE);
        actionBtn.setOnClickListener(v -> showAddMenu());

        JSONArray entries = prefs.authEntries();
        java.util.List<Row> rows = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            String secret = e.optString("secret", "");
            if (secret.isEmpty()) continue;
            String algo = e.optString("algorithm", "SHA1");
            int digits = e.optInt("digits", 6);
            int period = e.optInt("period", 30);
            String code = Totp.codeFor(secret, algo, digits, period, now);
            int left = Totp.secondsLeft(period, now);
            String display = Totp.displayName(e.optString("issuer", ""), e.optString("account", ""));

            Row r = new Row();
            r.icon = R.drawable.ic_lock;
            r.title = display;
            r.sub = getString(R.string.auth_hint_seconds,
                    Totp.formatCode(code), left);
            // value shows the raw code too — bold accent, the thing you read
            r.value = code == null ? "······" : code;
            final JSONObject entry = e;
            final String codeF = code;
            r.click = v -> {
                if (codeF != null) copyText(codeF, R.string.auth_code_copied);
            };
            r.longClick = v -> {
                showEntryMenu(entry);
                return true;
            };
            rows.add(r);
        }
        setRows(rows);
    }

    @Override
    protected void onResume() {
        super.onResume();
        tick.removeCallbacks(tickRun);
        tick.postDelayed(tickRun, 1000);
    }

    @Override
    protected void onPause() {
        tick.removeCallbacks(tickRun);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        tick.removeCallbacks(tickRun);
        super.onDestroy();
    }

    // ------------------------------------------------------------ copy helper

    private void copyText(String text, int toastRes) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("bolt-totp", text));
            Fx.toast(this, getString(toastRes));
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ + button

    /** the + menu: paste a key, import the Google Authenticator export QR,
     *  or read how account transfer works at all. */
    private void showAddMenu() {
        String[] opts = {
                getString(R.string.auth_add_key),
                getString(R.string.auth_import_ga),
                getString(R.string.auth_help_title),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.auth_add)
                .setItems(opts, (d, w) -> {
                    if (w == 0) showAddDialog(null);
                    else if (w == 1) startImportPicker();
                    else showTransferHelp();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** the add-account dialog: paste a setup key OR a whole otpauth:// URI;
     *  issuer/account editable (prefilled from the URI when present).
     *  v1.3.32: secretArg prefills the secret box (QR-decoded text path). */
    private void showAddDialog(String secretArg) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 20);
        wrap.setPadding(p, p / 2, p, 0);

        final EditText etSecret = new EditText(this);
        etSecret.setHint(R.string.auth_secret);
        etSecret.setTypeface(android.graphics.Typeface.MONOSPACE);
        if (secretArg != null) etSecret.setText(secretArg);
        wrap.addView(etSecret, new LinearLayout.LayoutParams(-1, -2));

        final EditText etIssuer = new EditText(this);
        etIssuer.setHint(R.string.auth_issuer);
        wrap.addView(etIssuer, new LinearLayout.LayoutParams(-1, -2));

        final EditText etAccount = new EditText(this);
        etAccount.setHint(R.string.auth_account);
        etAccount.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        wrap.addView(etAccount, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle(R.string.auth_add)
                .setView(wrap)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    Totp.OtpSecret o = Totp.fromKey(etSecret.getText().toString(),
                            etIssuer.getText().toString(), etAccount.getText().toString());
                    if (o == null) {
                        Fx.toast(this, getString(R.string.auth_bad_secret));
                        return;
                    }
                    JSONObject saved = prefs.addAuth(o);
                    if (saved.length() > 0) {
                        Fx.toast(this, getString(R.string.auth_added));
                        build();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------ GA import

    /** open the system image picker on the (screenshot of the) export QR. */
    private void startImportPicker() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, getString(R.string.auth_import_pick)), IMPORT_IMG_REQ);
        } catch (Throwable e) {
            try {
                startActivityForResult(i, IMPORT_IMG_REQ);
            } catch (Throwable e2) {
                Fx.toast(this, getString(R.string.auth_no_picker));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_IMG_REQ) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        Fx.toast(this, getString(R.string.auth_reading_qr));
        // decode off the main thread — screenshots are a few MP
        new Thread(() -> {
            final String text = Qr.decodeUri(this, uri, 1600);
            runOnUiThread(() -> handleQrText(text));
        }).start();
    }

    /** dispatch the decoded QR text to the right flow. */
    private void handleQrText(String text) {
        if (text == null || text.trim().isEmpty()) {
            Fx.toast(this, getString(R.string.auth_no_qr));
            return;
        }
        String t = text.trim();
        if (t.toLowerCase(java.util.Locale.US).startsWith("otpauth-migration://")) {
            confirmMigrationImport(t);
        } else if (t.toLowerCase(java.util.Locale.US).startsWith("otpauth://")) {
            Totp.OtpSecret o = Totp.parseOtpAuth(t);
            if (o == null) { Fx.toast(this, getString(R.string.auth_bad_secret)); return; }
            confirmAddOne(o);
        } else if (Totp.looksLikeSecret(t)) {
            // a QR carrying the bare setup key
            showAddDialog(t);
        } else {
            Fx.toast(this, getString(R.string.auth_no_qr));
        }
    }

    /** multi-account confirm for a Google Authenticator export payload. */
    private void confirmMigrationImport(String uri) {
        final GaMigration.Result res = GaMigration.parse(uri);
        if (res.parseError) {
            Fx.toast(this, getString(R.string.auth_bad_migration));
            return;
        }

        // build the account list, marking duplicates
        StringBuilder sb = new StringBuilder();
        int fresh = 0, dup = 0;
        for (Totp.OtpSecret o : res.accounts) {
            boolean have = prefs.hasAuthSecret(o.secret);
            sb.append("• ").append(Totp.displayName(o.issuer, o.account));
            if (have) { sb.append("  (").append(getString(R.string.auth_import_exists)).append(')'); dup++; }
            else fresh++;
            sb.append('\n');
        }
        if (res.skippedHotp > 0)
            sb.append("\n").append(getString(R.string.auth_skipped_hotp, res.skippedHotp));
        if (res.skippedBad > 0)
            sb.append("\n").append(getString(R.string.auth_skipped_bad, res.skippedBad));

        String title = getString(R.string.auth_import_title);
        if (res.batchSize > 1)
            title += "  (" + (res.batchIndex + 1) + "/" + res.batchSize + ")";

        final int toAdd = fresh;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(sb.toString().trim())
                .setNegativeButton(R.string.cancel, null);
        if (toAdd > 0) {
            b.setPositiveButton(getString(R.string.auth_import_add, toAdd), (d, w) -> {
                int added = prefs.addAuthList(res.accounts);
                build();
                if (added > 0) Fx.toast(this, getString(R.string.auth_import_added, added));
                // >10 accounts export as several QRs — walk the user through
                if (res.batchSize > 1 && res.batchIndex + 1 < res.batchSize) {
                    new AlertDialog.Builder(this)
                            .setMessage(getString(R.string.auth_batch_more,
                                    res.batchIndex + 1, res.batchSize))
                            .setPositiveButton(R.string.ok, null)
                            .show();
                }
            });
        } else {
            b.setPositiveButton(R.string.ok, null);
            if (dup > 0) Fx.toast(this, getString(R.string.auth_import_none_new));
        }
        b.show();
    }

    /** single-account confirm (an otpauth:// QR — e.g. a site's own setup
     *  QR screenshotted straight from the page). */
    private void confirmAddOne(Totp.OtpSecret o) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.auth_add_q,
                        Totp.displayName(o.issuer, o.account)))
                .setPositiveButton(R.string.yes, (d, w) -> {
                    JSONObject saved = prefs.addAuth(o);
                    if (saved.length() > 0) {
                        Fx.toast(this, getString(R.string.auth_added));
                        build();
                    }
                })
                .setNegativeButton(R.string.no, null)
                .show();
    }

    // ------------------------------------------------------------ per-entry

    /** long-press menu: export the account (QR / link) or delete it. */
    private void showEntryMenu(final JSONObject e) {
        String[] opts = {
                getString(R.string.auth_show_qr),
                getString(R.string.auth_copy_link),
                getString(R.string.auth_delete),
        };
        new AlertDialog.Builder(this)
                .setTitle(Totp.displayName(e.optString("issuer", ""), e.optString("account", "")))
                .setItems(opts, (d, w) -> {
                    if (w == 0) showQrDialog(e);
                    else if (w == 1) copyText(Totp.toOtpAuthUri(fromEntry(e)), R.string.auth_link_copied);
                    else confirmDelete(e);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** stored entry → exportable secret */
    private static Totp.OtpSecret fromEntry(JSONObject e) {
        Totp.OtpSecret o = new Totp.OtpSecret();
        o.issuer = e.optString("issuer", "");
        o.account = e.optString("account", "");
        o.secret = e.optString("secret", "");
        o.algorithm = e.optString("algorithm", "SHA1");
        o.digits = e.optInt("digits", 6);
        o.period = e.optInt("period", 30);
        return o;
    }

    /** render the account as a scannable QR (Google Authenticator's own
     *  "Scan a QR code" reads this — that IS the add-to-GA path). */
    private void showQrDialog(JSONObject e) {
        Totp.OtpSecret o = fromEntry(e);
        final String uri = Totp.toOtpAuthUri(o);
        if (uri.isEmpty()) { Fx.toast(this, getString(R.string.auth_bad_secret)); return; }

        int size = Fx.dp(this, 260);
        Bitmap bmp = Qr.encodeBitmap(uri, size);
        if (bmp == null) { Fx.toast(this, getString(R.string.auth_bad_secret)); return; }

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 16);
        wrap.setPadding(p, p, p, p);

        FrameLayout card = new FrameLayout(this);
        card.setBackgroundColor(0xFFFFFFFF);            // scanners need the light bg
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        int pad = Fx.dp(this, 12);
        iv.setPadding(pad, pad, pad, pad);
        card.addView(iv, new FrameLayout.LayoutParams(-2, -2,
                android.view.Gravity.CENTER));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, size + 2 * pad);
        wrap.addView(card, clp);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.auth_qr_hint));
        hint.setTextSize(13);
        hint.setGravity(android.view.Gravity.CENTER);
        int hp = Fx.dp(this, 10);
        hint.setPadding(0, hp, 0, 0);
        wrap.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle(Totp.displayName(o.issuer, o.account))
                .setView(wrap)
                .setPositiveButton(R.string.auth_qr_link, (d, w) ->
                        copyText(uri, R.string.auth_link_copied))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDelete(final JSONObject e) {
        String name = Totp.displayName(e.optString("issuer", ""), e.optString("account", ""));
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.auth_delete_confirm, name))
                .setPositiveButton(R.string.yes, (d, w) -> {
                    prefs.removeAuth(e.optLong("id"));
                    build();
                })
                .setNegativeButton(R.string.no, null)
                .show();
    }

    // ------------------------------------------------------------ help

    /** the honest story: no live sync exists (Google keeps GA's cloud data
     *  closed), QR transfer is the sanctioned way in both directions. */
    private void showTransferHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.auth_help_title)
                .setMessage(getString(R.string.auth_help))
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
