package com.bolt.browser.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bolt.browser.R;
import com.bolt.browser.core.DownloadEngine;
import com.bolt.browser.util.Fx;

import java.util.ArrayList;
import java.util.List;

/**
 * Download manager UI backed by Bolt's own download engine.
 *
 * v1.3.59 — THE REDESIGN (see ui/DlAdapter.kt for the full field report):
 * the rendering moved into the Kotlin DlAdapter — day-grouped sections
 * (Active strip + Today / Yesterday / dates), a compact one-line list row
 * with a preview tile and a single primary button, and a square-preview
 * CARD GRID with a header toggle (persisted pref "dlAsCards"). Long-press
 * opens the modern action sheet (DlSheet: open / share / copy link /
 * pause / retry / remove) instead of the old AlertDialog.
 *
 * What stayed from the earlier eras: the engine listener + 500 ms refresh
 * loop while anything is active (v1.3.10), the category chips with live
 * counts + "Retry all" (v1.3.40), and clear-finished (action button). */
public class DownloadsActivity extends BaseListActivity implements DownloadEngine.Listener {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean refreshing = false;
    private List<DownloadEngine.Item> items = new ArrayList<>();

    // ---- v1.3.59: the Kotlin adapter + grid/list plumbing ----
    private DlAdapter dlAd;
    private GridLayoutManager glm;
    private ImageButton viewBtn;
    private boolean cards;

    // ---- v1.3.40 (round-4 #8): category tabs + retry-all ----
    private static final String[] CATS = {"all", "docs", "images", "video", "audio", "apk", "arch"};
    private static final String[] CAT_LABELS = {"All", "Docs", "Images", "Video", "Audio", "APK", "Archives"};
    private String cat = "all";
    private final java.util.HashMap<String, TextView> catChips = new java.util.HashMap<>();
    private TextView retryAllChip;
    /** v1.3.91 — the sort choice (0 newest / 1 oldest / 2 name A-Z /
     *  3 name Z-A / 4 largest / 5 smallest), persisted as "sortDl". */
    private int sortMode = 0;
    /** the sort labels (built once — reload() runs every 500 ms while
     *  anything is active; the array was rebuilt per tick). */
    private String[] sortLabels;

    @Override
    protected String title() { return getString(R.string.downloads); }

    @Override
    protected void build() {
        // v1.3.35 empty state (rows are driven by reload() through the adapter)
        emptyIcon = R.drawable.ic_download;
        emptyTitleRes = R.string.empty_downloads_title;
        emptySubRes = R.string.empty_downloads_sub;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // v1.3.59: the redesigned list — DlAdapter (Kotlin) on a grid layout
        // manager that spans 2 for cards and collapses to 1 column for the
        // compact list (headers always span everything)
        cards = prefs.b("dlAsCards", true);
        dlAd = new DlAdapter(this, new DlAdapter.Host() {
            @Override
            public void onPrimary(DownloadEngine.Item it) { primary(it); }
            @Override
            public void onOptions(DownloadEngine.Item it) {
                DlSheet.INSTANCE.show(DownloadsActivity.this, it);
            }
        });
        glm = new GridLayoutManager(this, 2);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int pos) {
                return dlAd.isHeader(pos) || !dlAd.getCards() ? 2 : 1;
            }
        });
        rv.setLayoutManager(glm);
        rv.setAdapter(dlAd);

        actionBtn.setImageResource(R.drawable.ic_delete);
        actionBtn.setOnClickListener(v -> confirmClearFinished());
        buildViewToggle();
        buildCatRow();
        // v1.3.91 — the shared sort pass (the same glyph + sheet as
        // bookmarks / history / saved pages; downloads adds the size rows)
        sortLabels = new String[]{
                getString(R.string.sort_newest_first),
                getString(R.string.sort_oldest_first),
                getString(R.string.sort_name_az),
                getString(R.string.sort_name_za),
                getString(R.string.sort_largest_first),
                getString(R.string.sort_smallest_first)};
        sortMode = prefs.i("sortDl", 0);
        addSortButton("sortDl", sortLabels, () -> {
            sortMode = sortIdx;
            reload();
        });
        reload();
    }

    // ---------------------------------------------------------------- v1.3.59 view toggle

    /** the card/list segmented icon next to the clear-finished action —
     *  the same cards-vs-list vocabulary as the tab switcher, persisted. */
    private void buildViewToggle() {
        try {
            LinearLayout header = (LinearLayout) actionBtn.getParent();
            viewBtn = new ImageButton(this);
            viewBtn.setBackgroundResource(R.drawable.bg_item_round);
            viewBtn.setImageResource(cards ? R.drawable.ic_dl_rows : R.drawable.ic_dl_grid);
            viewBtn.setOnClickListener(v -> {
                cards = !cards;
                prefs.set("dlAsCards", cards);
                restyleViewToggle();
                dlAd.setCards(cards);
                com.bolt.browser.util.Ui.tap(v);
            });
            viewBtn.setColorFilter(Fx.tint(this));
            header.addView(viewBtn, Math.max(0, header.indexOfChild(actionBtn)));
        } catch (Throwable ignored) {}
    }

    private void restyleViewToggle() {
        try {
            viewBtn.setImageResource(cards ? R.drawable.ic_dl_rows : R.drawable.ic_dl_grid);
            viewBtn.setColorFilter(Fx.tint(this));
        } catch (Throwable ignored) {}
    }

    /** tap = the state's primary action (open / resume / retry / pause). */
    private void primary(DownloadEngine.Item it) {
        DownloadEngine eng = DownloadEngine.get(this);
        switch (it.status) {
            case DownloadEngine.ST_DONE: DlSheet.INSTANCE.openFile(this, it); break;
            case DownloadEngine.ST_PAUSED: eng.resume(it.id); break;
            case DownloadEngine.ST_FAILED: eng.retry(it.id); break;
            default: eng.pause(it.id); break;
        }
    }

    // ---------------------------------------------------------------- v1.3.40 category tabs

    /** a horizontally scrolling chip row under the header: All / Docs /
     *  Images / Video / Audio / APK / Archives (with counts), plus a
     *  "Retry all" chip that appears while anything sits FAILED. */
    private void buildCatRow() {
        try {
            LinearLayout root = (LinearLayout) rv.getParent();
            android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int h = Fx.dp(this, 4);
            row.setPadding(Fx.dp(this, 14), h, Fx.dp(this, 14), h);
            hsv.addView(row, new android.widget.HorizontalScrollView.LayoutParams(
                    -2, -2));
            for (int i = 0; i < CATS.length; i++) {
                final String key = CATS[i];
                TextView chip = new TextView(this);
                chip.setText(CAT_LABELS[i]);
                chip.setTextSize(13);
                chip.setTypeface(com.bolt.browser.util.Ui.medium());
                chip.setPadding(Fx.dp(this, 14), h, Fx.dp(this, 14), h);
                chip.setOnClickListener(v -> {
                    cat = key;
                    restyleChips();
                    reload();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = Fx.dp(this, 6);
                row.addView(chip, lp);
                catChips.put(key, chip);
            }
            retryAllChip = new TextView(this);
            retryAllChip.setText(R.string.retry);
            retryAllChip.setTextSize(13);
            retryAllChip.setTypeface(com.bolt.browser.util.Ui.medium());
            retryAllChip.setPadding(Fx.dp(this, 14), h, Fx.dp(this, 14), h);
            retryAllChip.setVisibility(View.GONE);
            retryAllChip.setOnClickListener(v -> {
                DownloadEngine eng = DownloadEngine.get(this);
                int n = 0;
                for (DownloadEngine.Item it : eng.list())
                    if (it.status == DownloadEngine.ST_FAILED) { eng.retry(it.id); n++; }
                if (n > 0) Fx.toast(this, "Retrying " + n + " download" + (n == 1 ? "" : "s"));
            });
            row.addView(retryAllChip, new LinearLayout.LayoutParams(-2, -2));
            root.addView(hsv, 1);   // between the header and the list
            restyleChips();
        } catch (Throwable ignored) {}
    }

    private void restyleChips() {
        try {
            for (java.util.Map.Entry<String, TextView> e : catChips.entrySet()) {
                boolean on = e.getKey().equals(cat);
                e.getValue().setBackgroundResource(on ? R.drawable.bg_chip_active : R.drawable.bg_chip);
                e.getValue().setTextColor(on ? Fx.accent(this) : Fx.dim(this));
            }
        } catch (Throwable ignored) {}
    }

    /** bucket an item by MIME type / file extension — v1.3.59: delegated to
     *  the Kotlin DlTypes so the screen, the shelf and the adapter share
     *  ONE vocabulary. */
    private String catOf(DownloadEngine.Item it) {
        return DlTypes.INSTANCE.catOf(it.mime, it.name);
    }

    // ---------------------------------------------------------------- data + refresh

    private void reload() {
        List<DownloadEngine.Item> all = DownloadEngine.get(this).list();
        // v1.3.40: apply the category filter + refresh chip counts
        items = new ArrayList<>();
        int[] counts = new int[CATS.length];
        int failed = 0;
        for (DownloadEngine.Item it : all) {
            String c = catOf(it);
            for (int i = 1; i < CATS.length; i++) if (CATS[i].equals(c)) counts[i]++;
            counts[0]++;
            if (it.status == DownloadEngine.ST_FAILED) failed++;
            if (cat.equals("all") || cat.equals(c)) items.add(it);
        }
        for (int i = 0; i < CATS.length; i++) {
            TextView chip = catChips.get(CATS[i]);
            if (chip != null) chip.setText(CAT_LABELS[i] + " " + counts[i]);
        }
        if (retryAllChip != null) {
            retryAllChip.setVisibility(failed > 0 ? View.VISIBLE : View.GONE);
            if (failed > 0) retryAllChip.setText(getString(R.string.retry) + " all (" + failed + ")");
        }
        // v1.3.91 — THE SORT: the day-grouped model only makes sense in
        // time order; a name/size sort renders ONE flat section ("Sorted
        // by …") instead of misleading day headers over reordered rows
        java.util.Collections.sort(items, (a, b) -> {
            switch (sortMode) {
                case 1: return Long.compare(a.time, b.time);
                case 2: return String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
                case 3: return String.CASE_INSENSITIVE_ORDER.compare(b.name, a.name);
                case 4: return Long.compare(b.total, a.total);
                case 5: return Long.compare(a.total, b.total);
                default: return Long.compare(b.time, a.time);
            }
        });
        // v1.3.59: the grouped model (Active strip + day sections) is built
        // inside the adapter; v1.3.91: a name/size sort renders ONE honest
        // flat header and the sort flip ANIMATES (notifyItemMoved glide)
        String flatLabel = sortMode > 0 && sortLabels != null
                && sortMode < sortLabels.length ? sortLabels[sortMode] : null;
        dlAd.submit(items, sortMode != 0, flatLabel, sortMode != 0);
        showEmpty(items.isEmpty());   // v1.3.35: modern empty state
        boolean hasFinished = false;
        for (DownloadEngine.Item it : items) {
            if (it.status == DownloadEngine.ST_DONE) { hasFinished = true; break; }
        }
        actionBtn.setVisibility(hasFinished ? View.VISIBLE : View.GONE);
        TextView t = findViewById(R.id.tv_g_title);
        if (t != null) t.setText(getString(R.string.downloads) + " ("
                + (cat.equals("all") ? items.size() : items.size() + "/" + all.size()) + ")");
        ui.removeCallbacks(refreshLoop);
        if (DownloadEngine.get(this).anyActive()) ui.postDelayed(refreshLoop, 500);
    }

    private final Runnable refreshLoop = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (refreshing) return;
            refreshing = true;
            reload();
            refreshing = false;
        }
    };

    @Override
    public void onDownloadsChanged() {
        ui.post(() -> {
            if (isFinishing() || isDestroyed() || refreshing) return;
            refreshing = true;
            reload();
            refreshing = false;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DownloadEngine.get(this).removeListener(this);
        ui.removeCallbacks(refreshLoop);
    }

    // ---------------------------------------------------------------- helpers

    private void confirmClearFinished() {
        int finished = 0;
        for (DownloadEngine.Item it : items) if (it.status == DownloadEngine.ST_DONE) finished++;
        if (finished == 0) return;
        final DownloadEngine eng = DownloadEngine.get(this);
        new android.app.AlertDialog.Builder(this)
                .setMessage(getString(R.string.clear_finished_q) + " (" + finished + ")")
                .setPositiveButton(R.string.yes, (d, w) -> {
                    for (DownloadEngine.Item it : eng.list()) {
                        if (it.status == DownloadEngine.ST_DONE) eng.delete(it.id);
                    }
                    reload();
                })
                .setNegativeButton(R.string.no, null)
                .show();
    }
}
