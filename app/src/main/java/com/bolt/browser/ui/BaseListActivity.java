package com.bolt.browser.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bolt.browser.R;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Prefs;
import com.bolt.browser.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Base for all list pages.
 *
 * v1.3.34 — THE MODERN LIST. Field report on v1.3.33: "deep settings …
 * lack polish and look early 2000 era windows xp style." The XP feel was
 * structural, not just colors:
 *  - bare 22dp gray icons floating next to text → now every row's icon sits
 *    in a 40dp ACCENT-TINTED rounded container (the Google Settings /
 *    LibreTube signature look; runtime accent applies);
 *  - rows were transparent ripple strips on the window background → now
 *    each row is a rounded surface CARD with a hairline outline, inset from
 *    the screen edge, separated by gaps (bg_row.xml + the spacing
 *    decoration below — the LibreTube settings grouping);
 *  - the Holo-era android.widget.Switch → Ui.ModernSwitch everywhere;
 *  - values were plain colored text → now accent pill chips;
 *  - bold-everything → sans-serif-medium titles, regular body.
 *
 * Rows can carry a {@code section} label (renders as a dim medium-weight
 * header above the row) so long pages group visually.
 */
public abstract class BaseListActivity extends AppCompatActivity {

    public static class Row {
        public int icon;
        public String title, sub, value;
        /** v1.3.39: long-press explainer (the "every button explains itself" pass) */
        public String hint;
        /** optional section label rendered above this row */
        public String section;
        public View.OnClickListener click;
        public View.OnLongClickListener longClick;
        public boolean switchRow;
        public boolean checked;
        public android.widget.CompoundButton.OnCheckedChangeListener switchChange;
        public boolean chevron = true;
    }

    protected Prefs prefs;
    protected RecyclerView rv;
    protected BaseAdapter adapter = new BaseAdapter();
    protected TextView emptyView;
    protected ImageButton actionBtn;
    private final List<Object> items = new ArrayList<>();

    // ---- v1.3.91 — the SORT pass (the field report: "add sorting option
    // for downloads, bookmarks, saved pages etc. Make the experience
    // full, not half and consistent throughout. Ensure to use
    // satisfactory animations and polishing") ----
    /** the header's sort button (built by [addSortButton]; the same glyph
     *  on every list screen — the consistency ask). */
    protected ImageButton sortBtn;
    /** the persisted sort index for THIS screen ("sortDl"/"sortBm"/…);
     *  -1 = this screen has no sorting. */
    protected int sortIdx = -1;

    /** v1.3.91 — wire the sort button into the header. [key] persists the
     *  choice, [labels] are the sheet's options (index 0 = the default —
     *  newest), [onSort] re-runs the screen's build with the new order
     *  (call [setRowsAnimated] inside it for the move animation). */
    protected void addSortButton(final String key, final String[] labels,
            final Runnable onSort) {
        try {
            LinearLayout header = (LinearLayout) actionBtn.getParent();
            sortBtn = new ImageButton(this);
            sortBtn.setBackgroundResource(R.drawable.bg_item_round);
            sortBtn.setImageResource(R.drawable.ic_sort);
            sortBtn.setColorFilter(Fx.tint(this));
            sortIdx = prefs.i(key, 0);
            sortBtn.setOnClickListener(v -> {
                com.bolt.browser.util.Ui.tap(v);
                androidx.appcompat.app.AlertDialog.Builder b =
                        new androidx.appcompat.app.AlertDialog.Builder(this);
                b.setTitle(R.string.sort_by_list);
                b.setSingleChoiceItems(labels, Math.max(0, sortIdx), (d, w) -> {
                    if (w != sortIdx) {
                        sortIdx = w;
                        prefs.set(key, w);
                        onSort.run();
                    }
                    d.dismiss();
                });
                b.setNegativeButton(R.string.cancel, null);
                b.show();
            });
            // keep the sort button right of the existing action/view buttons
            header.addView(sortBtn, Math.max(0, header.indexOfChild(actionBtn)));
        } catch (Throwable ignored) {}
    }

    /** v1.3.91 — THE ANIMATED REFRESH: like setRows, but a PURE REORDER of
     *  the same rows (the sort flip) animates through notifyItemMoved so
     *  the cards glide to their new homes (RecyclerView's built-in item
     *  animator); anything structural (adds/removes/section changes) falls
     *  back to the plain full refresh. */
    protected void setRowsAnimated(List<Row> list) {
        List<Object> prev = new ArrayList<>(items);
        // build the new model exactly like setRows (sections + rows)
        List<Object> next = new ArrayList<>();
        String lastSection = null;
        for (Row r : list) {
            if (r.section != null && !r.section.equals(lastSection)) {
                next.add(r.section);
                lastSection = r.section;
            }
            next.add(r);
        }
        boolean sameSet = !prev.isEmpty() && prev.size() == next.size();
        if (sameSet) {
            java.util.HashMap<Object, Integer> counts = new java.util.HashMap<>();
            for (Object o : prev) {
                Object k = rowKey(o);
                counts.put(k, counts.getOrDefault(k, 0) + 1);
            }
            for (Object o : next) {
                Object k = rowKey(o);
                Integer c = counts.get(k);
                if (c == null || c == 0) { sameSet = false; break; }
                counts.put(k, c - 1);
            }
        }
        if (sameSet) {
            // simulate on a WORKING copy of the old order: for each target
            // slot j, find where the wanted row sits now (>= j; everything
            // before j is already correct), move it up, record the move —
            // the working copy converges on `next` and every move animates
            List<Object> working = new ArrayList<>(prev);
            for (int j = 0; j < working.size(); j++) {
                Object wantKey = rowKey(next.get(j));
                int at = -1;
                for (int k = j; k < working.size(); k++) {
                    if (rowKey(working.get(k)).equals(wantKey)) { at = k; break; }
                }
                if (at > j) {
                    Object moved = working.remove(at);
                    working.add(j, moved);
                    adapter.notifyItemMoved(at, j);
                }
            }
            items.clear();
            items.addAll(working);
        } else {
            setRows(list);
        }
    }

    /** the identity a pure reorder preserves (title+sub — the same row
     *  object may be rebuilt with new listeners on every pass). */
    private Object rowKey(Object o) {
        if (o instanceof Row) {
            Row r = (Row) o;
            return "r:" + r.title + "\u0001" + r.sub + "\u0001" + r.value;
        }
        return "s:" + o;
    }

    // ---- v1.3.35 empty-state hooks (subclasses set these in build()) ----
    protected int emptyIcon = R.drawable.ic_globe;
    protected int emptyTitleRes = R.string.empty;
    protected int emptySubRes = 0;
    private FrameLayout emptySlot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Fx.theme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_generic_list);
        prefs = Prefs.get(this);
        rv = findViewById(R.id.rv_g);
        emptyView = findViewById(R.id.tv_g_empty);
        actionBtn = findViewById(R.id.btn_g_action);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        // v1.3.34: the gap between card rows (cards are separate surfaces —
        // LibreTube/Google settings grouping, no dividers)
        rv.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull android.graphics.Rect outRect,
                    @NonNull View view, @NonNull RecyclerView parent,
                    @NonNull RecyclerView.State state) {
                // v1.3.40: gap 4→6dp — the density pass overshot into the
                // "everything feels cramped" field report; one notch back.
                int gap = Fx.dp(BaseListActivity.this, 6);
                int pos = parent.getChildAdapterPosition(view);
                outRect.top = pos == 0 ? 0 : gap;
            }
        });
        findViewById(R.id.btn_g_back).setOnClickListener(v -> finish());
        // v1.3.35 — the modern empty state (icon medallion + title + sub)
        // replaces the lone gray "empty" line; the old TextView stays hidden.
        // emptySlot is a stable container so rebuilds on refresh are trivial.
        emptyView.setVisibility(View.GONE);
        emptySlot = new FrameLayout(this);
        LinearLayout root = (LinearLayout) emptyView.getParent();
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(emptySlot, elp);
        emptySlot.setVisibility(View.GONE);
        build();
        // NOTE: build() MUST run before title(): subclasses (e.g. SettingsItemsActivity)
        // read their intent extras inside build() and title() depends on that state.
        ((TextView) findViewById(R.id.tv_g_title)).setText(title());
        int tint = Fx.tint(this);
        findViewById(R.id.btn_g_back).post(() -> {
            ImageButton b = findViewById(R.id.btn_g_back);
            b.setColorFilter(tint);
        });
        actionBtn.setColorFilter(tint);
    }

    protected abstract String title();
    protected abstract void build();

    protected void setRows(List<Row> list) {
        items.clear();
        String lastSection = null;
        for (Row r : list) {
            if (r.section != null && !r.section.equals(lastSection)) {
                items.add(r.section);
                lastSection = r.section;
            }
            items.add(r);
        }
        adapter.notifyDataSetChanged();
        boolean empty = items.isEmpty();
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptySlot.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptySlot.removeAllViews();
            View art = Ui.emptyState(this, emptyIcon,
                    getString(emptyTitleRes),
                    emptySubRes != 0 ? getString(emptySubRes) : null);
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(-1, -2);
            alp.gravity = android.view.Gravity.CENTER;
            emptySlot.addView(art, alp);
        }
    }

    /** v1.3.35: toggle the modern empty state from screens that manage
     *  their own adapters (Downloads). */
    protected void showEmpty(boolean empty) {
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptySlot.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptySlot.removeAllViews();
            View art = Ui.emptyState(this, emptyIcon,
                    getString(emptyTitleRes),
                    emptySubRes != 0 ? getString(emptySubRes) : null);
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(-1, -2);
            alp.gravity = android.view.Gravity.CENTER;
            emptySlot.addView(art, alp);
        }
    }

    protected void refresh() { build(); }

    protected class BaseAdapter extends RecyclerView.Adapter<BaseAdapter.H> {

        private static final int TYPE_ROW = 0;
        private static final int TYPE_SECTION = 1;

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int lay = viewType == TYPE_SECTION
                    ? R.layout.item_section_label : R.layout.item_list_row;
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(lay, parent, false);
            if (viewType == TYPE_ROW) {
                // v1.3.35: per-skin corner radius (Mono sharp, Origins round)
                // — a programmatic rowBg instead of the fixed-16dp XML one
                try { v.setBackground(Ui.rowBg(BaseListActivity.this)); } catch (Throwable ignored) {}
            }
            return new H(v);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int pos) {
            Object o = items.get(pos);
            if (o instanceof String) {
                h.section.setText((String) o);
                return;
            }
            final Row r = (Row) o;
            // icon: fill the 40dp frame and give it the accent container
            // treatment (soft accent fill + accent icon — the modern
            // settings look; glass skin gets the translucent fill + rim)
            if (r.icon != 0) {
                h.icon.setImageResource(r.icon);
                h.icon.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
                Ui.iconify(h.icon, true);
                h.icon.setVisibility(View.VISIBLE);
            } else {
                h.icon.setVisibility(View.INVISIBLE);
            }
            h.title.setText(r.title == null ? "" : r.title);
            // v1.3.39: long-press → the explainer popup
            if (r.hint != null) {
                com.bolt.browser.util.MenuCards.explainRow(h.itemView,
                        BaseListActivity.this, r.title, r.hint);
            }
            if (r.sub != null) {
                h.sub.setText(r.sub);
                h.sub.setVisibility(View.VISIBLE);
            } else h.sub.setVisibility(View.GONE);
            h.end.removeAllViews();
            if (r.switchRow) {
                Ui.ModernSwitch sw = Ui.modernSwitch(BaseListActivity.this, r.checked, on -> {
                    if (r.switchChange != null) r.switchChange.onCheckedChanged(null, on);
                });
                h.end.addView(sw, new LinearLayout.LayoutParams(-2, -2));
                h.itemView.setOnClickListener(v -> sw.toggle(true));
            } else if (r.value != null) {
                TextView v = Ui.valueChip(BaseListActivity.this, r.value);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = Fx.dp(BaseListActivity.this, 6);
                h.end.addView(v, lp);
                addChevron(h);
            } else if (r.click != null) {
                addChevron(h);
            }
            if (!r.switchRow && r.click != null) {
                h.itemView.setOnClickListener(r.click);
            } else if (!r.switchRow) {
                h.itemView.setOnClickListener(null);
            }
            h.itemView.setOnLongClickListener(v -> {
                if (r.longClick != null) return r.longClick.onLongClick(v);
                if (r.click != null) { r.click.onClick(v); return true; }
                return false;
            });
            // light skins get a whisper of elevation on the row cards
            h.itemView.setElevation(Ui.wantShadows(BaseListActivity.this)
                    ? Fx.dp(BaseListActivity.this, 1) : 0);
        }

        private void addChevron(H h) {
            ImageView chev = new ImageView(BaseListActivity.this);
            chev.setImageResource(R.drawable.ic_chevron);
            chev.setColorFilter(Fx.dim(BaseListActivity.this));
            h.end.addView(chev, new LinearLayout.LayoutParams(
                    Fx.dp(BaseListActivity.this, 18), Fx.dp(BaseListActivity.this, 18)));
        }

        @Override
        public int getItemCount() { return items.size(); }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof String ? TYPE_SECTION : TYPE_ROW;
        }

        class H extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView title, sub, section;
            LinearLayout end;
            H(View v) {
                super(v);
                icon = v.findViewById(R.id.iv_row_icon);
                title = v.findViewById(R.id.tv_row_title);
                sub = v.findViewById(R.id.tv_row_sub);
                section = v.findViewById(R.id.tv_row_section);
                // section-label rows have none of the row internals — every
                // access below must tolerate a missing view
                View chev = v.findViewById(R.id.iv_row_chevron);
                if (chev != null) chev.setVisibility(View.GONE);
                ViewGroup fl = (ViewGroup) v.findViewById(R.id.fl_row_end);
                if (fl != null) {
                    end = new LinearLayout(BaseListActivity.this);
                    end.setOrientation(LinearLayout.HORIZONTAL);
                    end.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    fl.addView(end, new ViewGroup.LayoutParams(-2, -2));
                }
            }
        }
    }
}
