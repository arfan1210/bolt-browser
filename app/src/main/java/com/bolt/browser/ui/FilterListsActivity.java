package com.bolt.browser.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.bolt.browser.R;
import com.bolt.browser.adblock.FilterEngine;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Prefs;

import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Manage filter lists: enable/disable, add custom by URL, update, edit global custom rules. */
public class FilterListsActivity extends BaseListActivity {

    @Override
    protected String title() { return getString(R.string.filter_lists); }

    @Override
    protected void build() {
        // v1.3.35 empty state (only when no lists at all)
        emptyIcon = R.drawable.ic_filter;
        emptyTitleRes = R.string.empty_filters_title;
        emptySubRes = R.string.empty_filters_sub;
        List<Row> rows = new ArrayList<>();
        rows.add(builtIn("easylist", "EasyList", "Ads — easylist.to"));
        rows.add(builtIn("easyprivacy", "EasyPrivacy", "Trackers — easylist.to"));
        try {
            for (int i = 0; i < prefs.customLists().length(); i++) {
                JSONObject o = prefs.customLists().optJSONObject(i);
                if (o != null) rows.add(customRow(o));
            }
        } catch (Throwable ignored) {}

        Row add = new Row();
        add.icon = R.drawable.ic_plus;
        add.title = getString(R.string.add);
        add.sub = "Add a list by URL (e.g. from filterlists.com)";
        add.click = v -> addListDialog();
        rows.add(add);

        Row upd = new Row();
        upd.icon = R.drawable.ic_reload;
        upd.title = getString(R.string.update_all);
        upd.sub = "Update built-ins (easylist.to) + custom lists";
        upd.click = v -> updateAll();
        rows.add(upd);

        Row rules = new Row();
        rules.icon = R.drawable.ic_edit;
        rules.title = "Custom rules";
        rules.sub = "One rule per line (EasyList syntax)";
        rules.click = v -> editCustomRules();
        rows.add(rules);

        setRows(rows);
    }

    private Row builtIn(final String key, String name, String sub) {
        Row r = new Row();
        r.icon = R.drawable.ic_filter;
        // v1.3.80 — built-ins are no longer frozen at build time: "Update
        // all" (below) fetches the live list from easylist.to into
        // filtersDir/builtin_<key>.txt and load() prefers it over the bundled
        // asset (with a 100 KB size guard — an error page must never blank
        // the adblock). Show the update date when one is present.
        long ts = prefs.l("listTs_" + key, 0);
        String upd = ts > 0 ? " — updated " + java.text.DateFormat.getDateInstance(
                java.text.DateFormat.SHORT).format(new java.util.Date(ts)) : "";
        r.title = name;
        r.sub = sub + " — " + (prefs.listEnabled(key) ? "enabled" : "disabled") + upd;
        r.switchRow = true;
        r.checked = prefs.listEnabled(key);
        r.switchChange = (b, on) -> {
            prefs.setListEnabled(key, on);
            FilterEngine.warmAsync(this);
            refresh();
        };
        return r;
    }

    private Row customRow(final JSONObject o) {
        final String file = o.optString("file");
        Row r = new Row();
        r.icon = R.drawable.ic_filter;
        r.title = o.optString("name", file);
        r.sub = o.optString("url") + " — " + (o.optBoolean("enabled", true) ? "enabled" : "disabled");
        r.switchRow = true;
        r.checked = o.optBoolean("enabled", true);
        r.switchChange = (b, on) -> {
            try { o.put("enabled", on); prefs.saveCustomLists(); } catch (Throwable ignored) {}
            FilterEngine.warmAsync(this);
            refresh();
        };
        return r;
    }

    private void addListDialog() {
        final EditText et = new EditText(this);
        et.setHint("https://example.com/list.txt");
        new AlertDialog.Builder(this)
                .setTitle(R.string.add)
                .setView(et)
                .setPositiveButton(R.string.add, (d, w) -> {
                    String u = et.getText().toString().trim();
                    if (u.startsWith("http")) downloadList(u);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void downloadList(final String url) {
        Fx.toast(this, "Downloading list…");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                String txt = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                String name = url;
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                String file = "custom_" + System.currentTimeMillis() + "_" + name;
                File f = new File(prefs.filtersDir(), file);
                Fx.writeText(f, txt);
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("url", url);
                o.put("file", file);
                o.put("enabled", true);
                prefs.customLists().put(o);
                prefs.saveCustomLists();
                runOnUiThread(() -> {
                    Fx.toast(this, "List added (" + txt.split("\n").length + " lines)");
                    FilterEngine.warmAsync(this);
                    refresh();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> Fx.toast(this, "Download failed"));
            }
        }, "listdl").start();
    }

    private void updateAll() {
        Fx.toast(this, "Updating…");
        new Thread(() -> {
            try {
                // v1.3.80 — the BUILT-IN lists update too (they were frozen
                // at build time; the bundled copies ship two weeks stale).
                // Same discipline as the OCR model downloads: https, no
                // redirects followed blindly, and a size guard on the result
                // (a real list is megabytes; an error page is kilobytes).
                for (String key : new String[]{ "easylist", "easyprivacy" }) {
                    try {
                        File f = FilterEngine.builtinUpdateFile(prefs, key);
                        String txt = downloadText(FilterEngine.builtinUpdateUrl(key));
                        if (txt != null && txt.length() > 100_000) {
                            Fx.writeText(f, txt);
                            prefs.set("listTs_" + key, System.currentTimeMillis());
                        }
                    } catch (Throwable ignored) {}
                }
                for (int i = 0; i < prefs.customLists().length(); i++) {
                    org.json.JSONObject o = prefs.customLists().optJSONObject(i);
                    if (o == null || !o.optBoolean("enabled", true)) continue;
                    try {
                        String txt = downloadText(o.optString("url"));
                        if (txt != null && !txt.isEmpty()) {
                            Fx.writeText(new File(prefs.filtersDir(), o.optString("file")), txt);
                        }
                    } catch (Throwable ignored) {}
                }
                runOnUiThread(() -> {
                    FilterEngine.warmAsync(this);
                    Fx.toast(this, "Lists updated");
                    refresh();
                });
            } catch (Throwable ignored) {}
        }, "listupd").start();
    }

    /** v1.3.80 — one HTTPS GET as text with sane timeouts, or null. */
    private static String downloadText(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private void editCustomRules() {
        final EditText et = new EditText(this);
        StringBuilder sb = new StringBuilder();
        org.json.JSONArray a = prefs.customLists(); // stored under prefs key customRules
        String cur = prefs.s("customRules", "");
        et.setText(cur);
        et.setMinLines(8);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setTextSize(14);
        new AlertDialog.Builder(this)
                .setTitle("Custom rules")
                .setView(et)
                .setPositiveButton(R.string.save, (d, w) -> {
                    prefs.set("customRules", et.getText().toString());
                    Fx.toast(this, "Saved");
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
