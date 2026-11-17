package com.bolt.browser.ui;

import android.content.Intent;
import android.os.Bundle;

import com.bolt.browser.R;
import com.bolt.browser.util.Fx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends BaseListActivity {

    @Override
    protected String title() { return getString(R.string.history); }

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        // v1.3.91 — the shared sort pass (newest/oldest/name)
        addSortButton("sortHist", new String[]{
                getString(R.string.sort_newest_first),
                getString(R.string.sort_oldest_first),
                getString(R.string.sort_name_az),
                getString(R.string.sort_name_za)}, this::refresh);
    }

    @Override
    protected void build() {
        // v1.3.35 empty state
        emptyIcon = R.drawable.ic_history;
        emptyTitleRes = R.string.empty_history_title;
        emptySubRes = R.string.empty_history_sub;
        List<Row> rows = new ArrayList<>();
        JSONArray h = prefs.history();
        // v1.3.91 — collect first, sort, then render (the legacy order was
        // newest-first by construction; the sort pass makes it a choice)
        List<JSONObject> es = new ArrayList<>();
        for (int i = 0; i < h.length(); i++) {
            JSONObject o = h.optJSONObject(i);
            if (o != null) es.add(o);
        }
        if (sortIdx == 1) {
            java.util.Collections.sort(es, (a, b) ->
                    Long.compare(a.optLong("time"), b.optLong("time")));
        } else if (sortIdx == 2 || sortIdx == 3) {
            final boolean az = sortIdx == 2;
            java.util.Collections.sort(es, (a, b) -> az
                    ? String.CASE_INSENSITIVE_ORDER.compare(
                            titleOf(a), titleOf(b))
                    : String.CASE_INSENSITIVE_ORDER.compare(
                            titleOf(b), titleOf(a)));
        } else {
            java.util.Collections.sort(es, (a, b) ->
                    Long.compare(b.optLong("time"), a.optLong("time")));
        }
        SimpleDateFormat f = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());
        for (JSONObject o : es) {
            final String url = o.optString("url");
            String title = o.optString("title");
            String when = f.format(new Date(o.optLong("time")));
            Row r = new Row();
            r.icon = R.drawable.ic_history;
            r.title = title == null || title.isEmpty() ? url : title;
            r.sub = when + " — " + url;
            r.click = v -> {
                Intent in = new Intent(this, BrowserActivity.class);
                in.setAction(Intent.ACTION_VIEW);
                in.setData(android.net.Uri.parse(url));
                startActivity(in);
                finish();
            };
            rows.add(r);
        }
        if (rows.isEmpty()) {
            Row r = new Row();
            r.icon = R.drawable.ic_history;
            r.title = getString(R.string.empty);
            rows.add(r);
        }
        setRowsAnimated(rows);   // v1.3.91: the sort flip glides
        actionBtn.setVisibility(android.view.View.VISIBLE);
        actionBtn.setImageResource(R.drawable.ic_delete);
        actionBtn.setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setMessage("Clear all history?")
                        .setPositiveButton(R.string.delete, (d, w) -> {
                            prefs.clearHistory();
                            refresh();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show());
    }

    private String titleOf(JSONObject o) {
        String t = o.optString("title");
        String u = o.optString("url");
        return t == null || t.isEmpty() ? (u == null ? "" : u) : t;
    }
}
