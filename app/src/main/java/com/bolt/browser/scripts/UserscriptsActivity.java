package com.bolt.browser.scripts;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import com.bolt.browser.ui.BaseListActivity;
import com.bolt.browser.R;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Prefs;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Manage userscripts: install from URL (Greasy Fork), create, edit, toggle, delete. */
public class UserscriptsActivity extends BaseListActivity {

    @Override
    protected String title() { return getString(R.string.userscripts); }

    @Override
    protected void build() {
        // v1.3.35 empty state
        emptyIcon = R.drawable.ic_script;
        emptyTitleRes = R.string.empty_userscripts_title;
        emptySubRes = R.string.empty_userscripts_sub;
        List<Row> rows = new ArrayList<>();
        List<UserscriptEngine.Script> scripts = UserscriptEngine.all(this);
        final Map<String, Boolean> state = UserscriptEngine.loadState(this);
        for (final UserscriptEngine.Script s : scripts) {
            Row r = new Row();
            r.icon = R.drawable.ic_script;
            r.title = s.name;
            r.sub = s.matches.size() > 1 ? s.matches.size() + " match rules"
                    : s.matches.get(0);
            r.switchRow = true;
            r.checked = s.enabled;
            r.switchChange = (b, on) -> {
                state.put(s.file, on);
                UserscriptEngine.saveState(this, state);
            };
            r.click = v -> editScript(s);
            rows.add(r);
        }
        if (rows.isEmpty()) {
            Row r = new Row();
            r.icon = R.drawable.ic_script;
            r.title = getString(R.string.empty);
            r.sub = "Install userscripts from Greasy Fork URLs";
            rows.add(r);
        }
        setRows(rows);
        actionBtn.setVisibility(View.VISIBLE);
        actionBtn.setImageResource(R.drawable.ic_plus);
        actionBtn.setOnClickListener(v -> addDialog());
    }

    private void addDialog() {
        final EditText et = new EditText(this);
        et.setHint("https://greasyfork.org/.../script.user.js");
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_script)
                .setView(et)
                .setPositiveButton(R.string.add, (d, w) -> {
                    String u = et.getText().toString().trim();
                    if (u.startsWith("http")) install(u);
                })
                .setNeutralButton(R.string.new_script, (d, w) -> newScript())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void install(final String url) {
        Fx.toast(this, "Downloading script…");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10) BoltBrowser/1.2");
                InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                String code = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                if (!code.contains("==UserScript==")) code =
                        "// ==UserScript==\n// @name Script\n// @match *\n// ==/UserScript==\n"
                                + code;
                String name = "script_" + System.currentTimeMillis() + ".user.js";
                File f = new File(Prefs.get(this).scriptsDir(), name);
                Fx.writeText(f, code);
                runOnUiThread(() -> {
                    Fx.toast(this, "Script installed");
                    refresh();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> Fx.toast(this, "Download failed"));
            }
        }, "scriptdl").start();
    }

    private void newScript() {
        String template = "// ==UserScript==\n// @name        My script\n"
                + "// @match      https://example.com/*\n// @grant       none\n"
                + "// ==/UserScript==\n\nconsole.log('Hello from Bolt userscript');\n";
        editRaw("script_" + System.currentTimeMillis() + ".user.js", template, true);
    }

    private void editScript(final UserscriptEngine.Script s) {
        final File f = new File(Prefs.get(this).scriptsDir(), s.file);
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setPositiveButton(R.string.edit, (d, w) ->
                        editRaw(s.file, Fx.readText(f), false))
                .setNeutralButton(R.string.delete, (d, w) -> {
                    f.delete();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void editRaw(final String file, String code, final boolean isNew) {
        final EditText et = new EditText(this);
        et.setText(code);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setTextSize(13);
        et.setMinLines(12);
        et.setGravity(android.view.Gravity.TOP);
        new AlertDialog.Builder(this)
                .setTitle(R.string.edit)
                .setView(et)
                .setPositiveButton(R.string.save, (d, w) -> {
                    File f = new File(Prefs.get(this).scriptsDir(), file);
                    Fx.writeText(f, et.getText().toString());
                    Fx.toast(this, getString(R.string.saved));
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
