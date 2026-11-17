package com.bolt.browser.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.bolt.browser.R;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Bookmarks with folders: navigate in/out, add into any folder, move/rename/delete. */
public class BookmarksActivity extends BaseListActivity {

    /** "" = root */
    protected String curFolder = "";

    /** v1.3.91 — the sort labels every list screen shares (downloads adds
     *  the two size rows; the order of the first four is identical
     *  everywhere = the consistency ask). */
    protected static final String[] SORTS = {
            null, null, null, null };   // filled in onCreate (needs strings)

    @Override
    protected String title() { return getString(R.string.bookmarks); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SORTS[0] = getString(R.string.sort_newest_first);
        SORTS[1] = getString(R.string.sort_oldest_first);
        SORTS[2] = getString(R.string.sort_name_az);
        SORTS[3] = getString(R.string.sort_name_za);
        // v1.3.91 — the SORT pass: the same glyph + sheet as downloads /
        // history / saved pages; the choice persists per screen.
        addSortButton("sortBm", SORTS, this::refresh);
        // folder-aware back navigation (rewired after BaseListActivity's finish())
        findViewById(R.id.btn_g_back).setOnClickListener(v -> {
            if (!curFolder.isEmpty()) {
                curFolder = "";
                refresh();
            } else {
                finish();
            }
        });
    }

    @Override
    protected void build() {
        // v1.3.35 empty state
        emptyIcon = R.drawable.ic_bookmark;
        emptyTitleRes = R.string.empty_bookmarks_title;
        emptySubRes = R.string.empty_bookmarks_sub;
        List<Row> rows = new ArrayList<>();
        final JSONArray bm = prefs.bookmarks();

        if (curFolder.isEmpty()) {
            // folders first
            for (final String folder : prefs.bookmarkFolders()) {
                Row fr = new Row();
                fr.icon = R.drawable.ic_folder;
                fr.title = folder;
                fr.sub = prefs.bookmarkCountIn(folder) + " bookmarks";
                fr.click = v -> {
                    curFolder = folder;
                    refresh();
                };
                fr.longClick = v -> {
                    folderOptions(folder);
                    return true;
                };
                rows.add(fr);
            }
        } else {
            // ".. up" row
            Row up = new Row();
            up.icon = R.drawable.ic_arrow_up;
            up.title = curFolder;
            up.sub = getString(R.string.root_folder);
            up.click = v -> {
                curFolder = "";
                refresh();
            };
            rows.add(up);
        }

        for (int i = 0; i < bm.length(); i++) {
            JSONObject o = bm.optJSONObject(i);
            if (o == null) continue;
            final String url = o.optString("url");
            final String title = o.optString("title");
            if (!curFolder.isEmpty() && !curFolder.equals(o.optString("folder", ""))) continue;
            if (curFolder.isEmpty() && !o.optString("folder", "").isEmpty()) continue;
            Row r = new Row();
            r.icon = R.drawable.ic_bookmark;
            r.title = title == null || title.isEmpty() ? url : title;
            r.sub = url;
            r.click = v -> {
                Intent in = new Intent(this, BrowserActivity.class);
                in.setAction(Intent.ACTION_VIEW);
                in.setData(android.net.Uri.parse(url));
                startActivity(in);
                finish();
            };
            final String fTitle = title == null || title.isEmpty() ? url : title;
            r.longClick = v -> {
                bookmarkOptions(url, fTitle);
                return true;
            };
            rows.add(r);
        }
        // v1.3.91 — THE SORT: folders stay pinned above; the bookmark
        // rows themselves reorder (newest is the legacy default order —
        // addBookmark appends, so reversed = newest; name sorts are
        // case-insensitive). A pure reorder glides through
        // setRowsAnimated; any structural change (add/delete/navigate)
        // falls back to the plain rebuild.
        final List<Row> bmRows = rows.subList(curFolder.isEmpty()
                ? Math.min(prefs.bookmarkFolders().size(), rows.size())
                : 1, rows.size());
        java.util.Comparator<Row> cmp = null;
        if (sortIdx == 0) {
            cmp = (a, b) -> Long.compare(timeOfUrl(b.sub), timeOfUrl(a.sub));
        } else if (sortIdx == 1) {
            cmp = (a, b) -> Long.compare(timeOfUrl(a.sub), timeOfUrl(b.sub));
        }
        if (sortIdx == 2 || sortIdx == 3) {
            final boolean az = sortIdx == 2;
            cmp = (a, b) -> az
                    ? String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title)
                    : String.CASE_INSENSITIVE_ORDER.compare(b.title, a.title);
        }
        if (cmp != null) {
            try { java.util.Collections.sort(bmRows, cmp); } catch (Throwable ignored) {}
        }
        if (rows.isEmpty()) {
            Row r = new Row();
            r.icon = R.drawable.ic_bookmark;
            r.title = getString(R.string.empty);
            rows.add(r);
        }
        setRowsAnimated(rows);   // v1.3.91: a pure sort reorder glides; else full rebuild
        // keep the header in sync when navigating folders
        TextView tv = findViewById(R.id.tv_g_title);
        if (tv != null) {
            tv.setText(curFolder.isEmpty() ? getString(R.string.bookmarks) : curFolder);
            tv.setGravity(Gravity.CENTER_VERTICAL);
        }
        actionBtn.setVisibility(View.VISIBLE);
        actionBtn.setImageResource(R.drawable.ic_plus);
        actionBtn.setOnClickListener(v -> addBookmarkDialog("", "", ""));
    }

    /** v1.3.91 — the bookmark's saved time by url (0 when the entry
     *  predates the time field — sorts as "oldest", the honest slot). */
    private long timeOfUrl(String url) {
        try {
            JSONArray bm = prefs.bookmarks();
            for (int i = 0; i < bm.length(); i++) {
                JSONObject o = bm.optJSONObject(i);
                if (o != null && o.optString("url").equals(url)) {
                    return o.optLong("time", 0L);
                }
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    /** long-press menu for one bookmark */
    protected void bookmarkOptions(final String url, final String title) {
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.open_in_new_tab));
        opts.add(getString(R.string.move_to_folder));
        opts.add(getString(R.string.rename));
        opts.add(getString(R.string.delete));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        Intent in = new Intent(this, BrowserActivity.class);
                        in.setAction(Intent.ACTION_VIEW);
                        in.setData(android.net.Uri.parse(url));
                        startActivity(in);
                        finish();
                    } else if (w == 1) {
                        pickFolderFor(url);
                    } else if (w == 2) {
                        final EditText et = new EditText(this);
                        et.setText(title);
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.rename)
                                .setView(et)
                                .setPositiveButton(R.string.save, (d2, w2) -> {
                                    prefs.renameBookmark(url, et.getText().toString().trim());
                                    refresh();
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    } else if (w == 3) {
                        // v1.4.6 — DELETE + UNDO (the user's ask: an undo
                        // for "deleting anything, be it tab, shortcut,
                        // bookmark, saved pages etc."; the v1.3.91
                        // shortcut/folder pattern): the row is captured
                        // whole with its GLOBAL index, and Undo puts it
                        // back at the same slot inside the 3.5 s window.
                        org.json.JSONObject gone = null;
                        int goneAt = -1;
                        try {
                            JSONArray all = prefs.bookmarks();
                            for (int i = 0; i < all.length(); i++) {
                                org.json.JSONObject o = all.optJSONObject(i);
                                if (o != null && o.optString("url").equals(url)) {
                                    gone = o;
                                    goneAt = i;
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {}
                        prefs.removeBookmark(url);
                        refresh();
                        if (gone != null) {
                            final org.json.JSONObject back = gone;
                            final int at = goneAt;
                            Ui.snackbar(this, getString(R.string.bookmark_removed),
                                    getString(R.string.undo), () -> {
                                        try {
                                            prefs.insertBookmark(Math.min(at,
                                                    prefs.bookmarks().length()), back);
                                            refresh();
                                        } catch (Throwable ignored) {}
                                    }, Ui.UNDO_MS, null);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    protected void pickFolderFor(final String url) {
        final List<String> folders = prefs.bookmarkFolders();
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.root_folder));
        opts.addAll(folders);
        opts.add(getString(R.string.new_folder));
        new AlertDialog.Builder(this)
                .setTitle(R.string.move_to_folder)
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    if (w == opts.size() - 1) {
                        final EditText et = new EditText(this);
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.new_folder)
                                .setView(et)
                                .setPositiveButton(R.string.add, (d2, w2) -> {
                                    prefs.moveBookmark(url, et.getText().toString().trim());
                                    refresh();
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    } else if (w == 0) {
                        prefs.moveBookmark(url, "");
                        refresh();
                    } else {
                        prefs.moveBookmark(url, folders.get(w - 1));
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** long-press menu for a folder */
    protected void folderOptions(final String folder) {
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.rename));
        opts.add(getString(R.string.delete_folder));
        new AlertDialog.Builder(this)
                .setTitle(folder)
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        final EditText et = new EditText(this);
                        et.setText(folder);
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.rename)
                                .setView(et)
                                .setPositiveButton(R.string.save, (d2, w2) -> {
                                    prefs.renameFolder(folder, et.getText().toString().trim());
                                    refresh();
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    } else {
                        prefs.deleteFolder(folder);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** add dialog with optional folder choice; prefilled when launched from the browser */
    protected void addBookmarkDialog(String preTitle, String preUrl, String preFolder) {
        int txt = Fx.txt(this);
        int dim = Fx.dim(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 20);
        wrap.setPadding(p, p / 2, p, 0);
        final EditText etTitle = new EditText(this);
        etTitle.setHint(R.string.name);
        etTitle.setText(preTitle);
        etTitle.setTextColor(txt);
        wrap.addView(etTitle, new LinearLayout.LayoutParams(-1, -2));
        final EditText etUrl = new EditText(this);
        etUrl.setHint("https://…");
        etUrl.setText(preUrl);
        etUrl.setTextColor(txt);
        wrap.addView(etUrl, new LinearLayout.LayoutParams(-1, -2));
        // folder row
        LinearLayout frow = new LinearLayout(this);
        frow.setOrientation(LinearLayout.HORIZONTAL);
        frow.setGravity(Gravity.CENTER_VERTICAL);
        frow.setPadding(0, Fx.dp(this, 6), 0, 0);
        TextView fl = new TextView(this);
        fl.setText(R.string.folder);
        fl.setTextColor(dim);
        fl.setTextSize(14);
        frow.addView(fl, new LinearLayout.LayoutParams(-2, -2));
        final String[] chosen = {preFolder == null ? "" : preFolder};
        final TextView fname = new TextView(this);
        fname.setText(chosen[0].isEmpty() ? getString(R.string.root_folder) : chosen[0]);
        fname.setTextColor(Fx.accentText(this));
        fname.setTextSize(14);
        fname.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        fname.setPadding(Fx.dp(this, 8), 0, Fx.dp(this, 8), 0);
        frow.addView(fname, new LinearLayout.LayoutParams(0, -2, 1f));
        frow.setOnClickListener(v -> {
            final List<String> folders = prefs.bookmarkFolders();
            if (curFolder != null && !curFolder.isEmpty() && !folders.contains(curFolder)) {
                folders.add(curFolder);
            }
            List<String> opts = new ArrayList<>();
            opts.add(getString(R.string.root_folder));
            opts.addAll(folders);
            opts.add(getString(R.string.new_folder));
            new AlertDialog.Builder(BookmarksActivity.this)
                    .setTitle(R.string.folder)
                    .setItems(opts.toArray(new String[0]), (d2, w2) -> {
                        if (w2 == opts.size() - 1) {
                            final EditText et = new EditText(BookmarksActivity.this);
                            new AlertDialog.Builder(BookmarksActivity.this)
                                    .setTitle(R.string.new_folder)
                                    .setView(et)
                                    .setPositiveButton(R.string.add, (d3, w3) -> {
                                        chosen[0] = et.getText().toString().trim();
                                        fname.setText(chosen[0].isEmpty()
                                                ? getString(R.string.root_folder) : chosen[0]);
                                    })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                        } else if (w2 == 0) {
                            chosen[0] = "";
                            fname.setText(R.string.root_folder);
                        } else {
                            chosen[0] = folders.get(w2 - 1);
                            fname.setText(chosen[0]);
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        wrap.addView(frow, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_bookmark)
                .setView(wrap)
                .setPositiveButton(R.string.add, (d, w) -> {
                    String title = etTitle.getText().toString().trim();
                    String u = etUrl.getText().toString().trim();
                    if (title.isEmpty()) title = Fx.hostOf(u);
                    if (u.startsWith("http")) {
                        prefs.addBookmark(title, u, chosen[0]);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
