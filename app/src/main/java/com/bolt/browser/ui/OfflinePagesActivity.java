package com.bolt.browser.ui;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import com.bolt.browser.R;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Ui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Saved offline pages list — reads BOTH save locations and merges them:
 *  the in-app offline dir (default) and the user's dedicated saved-pages
 *  SAF folder (v1.3.9 independent picker). Tapping opens the archive;
 *  long-press deletes it from wherever it lives. */
public class OfflinePagesActivity extends BaseListActivity {

    /** one row of the merged list */
    private static class Entry {
        String title;      // display name (no .mht)
        String sub;        // date + size
        long when;         // last modified, for sorting
        long size;         // bytes, for the size sort (v1.3.91)
        boolean saf;       // SAF document (content://) vs in-app file (file://)
        Uri uri;           // document uri (SAF) or file uri (in-app)
    }

    @Override
    protected String title() { return getString(R.string.offline_pages); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v1.3.91 — the shared sort pass (saved pages: time/name/size)
        addSortButton("sortOff", new String[]{
                getString(R.string.sort_newest_first),
                getString(R.string.sort_oldest_first),
                getString(R.string.sort_name_az),
                getString(R.string.sort_name_za),
                getString(R.string.sort_largest_first),
                getString(R.string.sort_smallest_first)}, this::refresh);
    }

    @Override
    protected void build() {
        // v1.3.35 empty state
        emptyIcon = R.drawable.ic_offline;
        emptyTitleRes = R.string.empty_offline_title;
        emptySubRes = R.string.empty_offline_sub;
        List<Entry> entries = new ArrayList<>();

        // 1) in-app offline dir
        File dir = prefs.offlineDir();
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().toLowerCase(Locale.US).endsWith(".mht")) continue;
                Entry e = new Entry();
                e.title = f.getName().replaceAll("(?i)\\.mht$", "");
                e.when = f.lastModified();
                e.size = f.length();   // v1.3.91: the size sort
                e.saf = false;
                e.uri = Uri.parse("file://" + f.getAbsolutePath());
                entries.add(e);
            }
        }

        // 2) saved-pages SAF folder (independent from the downloads folder)
        String spd = prefs.savedPagesDir();
        if (spd != null && !spd.isEmpty()) {
            try {
                Uri tree = Uri.parse(spd);
                String docId = DocumentsContract.getTreeDocumentId(tree);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
                try (Cursor c = getContentResolver().query(children,
                        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                                DocumentsContract.Document.COLUMN_SIZE},
                        null, null, null)) {
                    if (c != null) {
                        while (c.moveToNext()) {
                            String id = c.getString(0);
                            String name = c.getString(1);
                            if (name == null || !name.toLowerCase(Locale.US).endsWith(".mht"))
                                continue;
                            Entry e = new Entry();
                            e.title = name.replaceAll("(?i)\\.mht$", "");
                            e.when = c.isNull(2) ? 0 : c.getLong(2);
                            e.size = c.isNull(3) ? 0 : c.getLong(3);   // v1.3.91: the size sort
                            e.saf = true;
                            e.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                            e.sub = null;
                            eSize(e, c.isNull(3) ? 0 : c.getLong(3));
                            entries.add(e);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // v1.3.91 — THE SORT (the legacy default stays newest-first; name
        // and size sorts cover the "saved pages etc" half of the ask)
        if (sortIdx == 1) {
            java.util.Collections.sort(entries, (a, b) -> Long.compare(a.when, b.when));
        } else if (sortIdx == 2 || sortIdx == 3) {
            final boolean az = sortIdx == 2;
            java.util.Collections.sort(entries, (a, b) -> az
                    ? String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title)
                    : String.CASE_INSENSITIVE_ORDER.compare(b.title, a.title));
        } else if (sortIdx == 4) {
            java.util.Collections.sort(entries, (a, b) -> Long.compare(b.size, a.size));
        } else if (sortIdx == 5) {
            java.util.Collections.sort(entries, (a, b) -> Long.compare(a.size, b.size));
        } else {
            java.util.Collections.sort(entries, (a, b) -> Long.compare(b.when, a.when));
        }

        SimpleDateFormat f = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());
        List<Row> rows = new ArrayList<>();
        for (final Entry e : entries) {
            if (e.sub == null) {
                long kb = 0;
                try { kb = sizeOf(e); } catch (Throwable ignored) {}
                eSize(e, kb);
            }
            Row r = new Row();
            r.icon = R.drawable.ic_offline;
            r.title = e.title;
            r.sub = (e.sub == null ? "" : e.sub + " — ") + (e.saf ? "folder" : "in app");
            r.click = v -> {
                // v1.4.14 — the incognito return route (Ui.browserOpenIntent)
                startActivity(Ui.browserOpenIntent(this, e.uri.toString()));
                finish();
            };
            r.longClick = v -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setMessage(getString(R.string.delete) + " \"" + e.title + "\"?")
                        .setPositiveButton(R.string.yes, (d, w) -> {
                            // v1.4.6 — DELETE + UNDO (the user's ask: an
                            // undo for "deleting anything, be it tab,
                            // shortcut, bookmark, saved pages etc."): the
                            // delete is SOFT — in-app files rename to a
                            // .trash sibling, SAF documents are copied to
                            // the cache — and the 3.5 s Undo window can
                            // put the page back exactly; only when the
                            // window closes untapped does the copy really
                            // die (Ui.snackbar's onExpire hook).
                            java.util.concurrent.atomic.AtomicReference<Runnable>
                                    finalizer = new java.util.concurrent.atomic.AtomicReference<>();
                            Runnable restore = softDeleteEntry(e, finalizer);
                            refresh();
                            if (restore != null) {
                                Ui.snackbar(this,
                                        getString(R.string.page_deleted) + " · " + e.title,
                                        getString(R.string.undo), restore,
                                        Ui.UNDO_MS, () -> {
                                            Runnable fin = finalizer.get();
                                            if (fin != null) try { fin.run(); } catch (Throwable ignored) {}
                                        });
                            }
                        })
                        .setNegativeButton(R.string.no, null)
                        .show();
                return true;
            };
            rows.add(r);
        }
        if (rows.isEmpty()) {
            Row r = new Row();
            r.icon = R.drawable.ic_offline;
            r.title = getString(R.string.empty);
            r.sub = "Use ⋮ > Save page offline in the browser";
            rows.add(r);
        }
        setRowsAnimated(rows);   // v1.3.91: the sort flip glides
    }

    private void eSize(Entry e, long bytes) {
        e.sub = bytes > 0 ? (bytes / 1024) + " KB" : "";
    }

    private long sizeOf(Entry e) {
        if (!e.saf && "file".equals(e.uri.getScheme())) {
            try { return new File(e.uri.getPath()).length(); } catch (Throwable ignored) {}
        }
        return 0;
    }

    /** v1.4.6 — the SOFT delete (the undo family): captures the page so
     *  the 3.5 s Undo window can restore it, then removes it from view.
     *  In-app file: renamed to a ".trash" sibling (same filesystem, atomic,
     *  free). SAF document: bytes copied into the cache dir first (capped —
     *  a page too big to hold is deleted hard, no undo offered). Returns
     *  the RESTORER runnable, or null when the capture failed (the entry
     *  was deleted the old hard way). [finalizer] receives the cleanup that
     *  runs when the window closes untapped (the real delete). */
    private Runnable softDeleteEntry(Entry e,
            java.util.concurrent.atomic.AtomicReference<Runnable> finalizer) {
        try {
            if (!e.saf && "file".equals(e.uri.getScheme())) {
                final File f = new File(e.uri.getPath());
                final File trash = new File(f.getParentFile(), f.getName() + ".trash");
                if (!f.renameTo(trash)) {          // capture failed: hard delete
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    return null;
                }
                finalizer.set(() -> { try { //noinspection ResultOfMethodCallIgnored
                        trash.delete(); } catch (Throwable ignored) {} });
                return () -> {
                    try {
                        if (!f.exists()) { //noinspection ResultOfMethodCallIgnored
                            trash.renameTo(f); }
                        else { try {                                        // a same-named page
                            java.io.FileInputStream in = new java.io.FileInputStream(trash);
                            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                            byte[] buf = new byte[16384];
                            int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            in.close(); out.close();
                            //noinspection ResultOfMethodCallIgnored
                            trash.delete();
                        } catch (Throwable ignored) {} }
                    } catch (Throwable ignored) {}
                    refresh();
                };
            } else if (e.saf) {
                final long CAP = 32L * 1024 * 1024;   // too big to hold = no undo
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = getContentResolver().openInputStream(e.uri)) {
                    if (in == null) throw new Exception("null stream");
                    byte[] buf = new byte[16384];
                    int n; long tot = 0;
                    while ((n = in.read(buf)) > 0) {
                        tot += n;
                        if (tot > CAP) throw new Exception("over cap");
                        bos.write(buf, 0, n);
                    }
                }
                final byte[] blob = bos.toByteArray();
                if (blob.length == 0) throw new Exception("empty");
                //noinspection ResultOfMethodCallIgnored
                getContentResolver().delete(e.uri, null, null);
                finalizer.set(() -> {});             // nothing on disk to clean
                return () -> {
                    try {
                        // rebuild the tree root from the document uri and
                        // re-create the .mht inside it
                        String treeDocId = DocumentsContract.getTreeDocumentId(e.uri);
                        Uri treeUri = DocumentsContract.buildTreeDocumentUri(
                                e.uri.getAuthority(), treeDocId);
                        Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
                        Uri newDoc = DocumentsContract.createDocument(
                                getContentResolver(), parentDoc, "message/rfc822", e.title + ".mht");
                        try (java.io.OutputStream out = getContentResolver().openOutputStream(newDoc)) {
                            out.write(blob);
                        }
                    } catch (Throwable t) {
                        Fx.toast(this, getString(R.string.restore_failed));
                    }
                    refresh();
                };
            }
        } catch (Throwable ignored) {}
        deleteEntry(e);                               // fallback: the old hard delete
        return null;
    }

    private void deleteEntry(Entry e) {
        try {
            if (e.saf) {
                getContentResolver().delete(e.uri, null, null);
            } else if ("file".equals(e.uri.getScheme())) {
                //noinspection ResultOfMethodCallIgnored
                new File(e.uri.getPath()).delete();
            }
        } catch (Throwable ignored) {}
    }
}
