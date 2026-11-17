package com.bolt.browser.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bolt.browser.R;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.MenuCards;
import com.bolt.browser.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Deep settings: category chooser (Opera-like hierarchy).
 * v1.3.36 FIELD FIX: the APPEARANCE category is gone from the deep tree —
 * Appearance has its own dedicated screen surfaced as a card on the
 * settings home, and this list kept a stale duplicate. One setting, one
 * home.
 *
 * v1.3.39 — TWO FIELD CHANGES:
 *  1. "Move the appearance from surface setting to deep settings": the
 *     Appearance CARD is gone from the settings home; the category lives
 *     HERE again (first row, opens the modern AppearanceActivity editor).
 *  2. "Also make the search settings bar work in deep settings too": this
 *     screen gained its own search field — it filters an index of every
 *     row the deep tree owns (including the Appearance rows and the new
 *     v1.3.39 features) and deep-links straight to the owner screen, the
 *     same Google-Settings pattern the surface search uses.
 */
public class DeepSettingsActivity extends BaseListActivity {

    public static final String CAT = "cat";

    private LinearLayout results;
    private EditText search;
    /** v1.3.40 — the quick-destination card strip (hidden while searching). */
    private View quickStrip;

    @Override
    protected String title() { return getString(R.string.settings); }

    @Override
    protected void build() {
        List<Row> rows = new ArrayList<>();
        // v1.3.39: Appearance lives in the DEEP tree again (moved off the
        // settings home per the field report).
        rows.add(cat(R.drawable.ic_droplet, getString(R.string.appearance),
                getString(R.string.appearance_desc), "appearance"));
        rows.add(cat(R.drawable.ic_gear, getString(R.string.cat_general),
                getString(R.string.cat_general_desc), "general"));
        // v1.3.31 — tabs switcher preferences (sort, groups, cards/list);
        // mirrors the 3-dot menu on the tabs screen itself
        rows.add(cat(R.drawable.ic_tabcount, getString(R.string.cat_tabs),
                getString(R.string.cat_tabs_desc), "tabs"));
        rows.add(cat(R.drawable.ic_lock, getString(R.string.cat_privacy),
                getString(R.string.cat_privacy_desc), "privacy"));
        rows.add(cat(R.drawable.ic_volume, getString(R.string.cat_media),
                getString(R.string.cat_media_desc), "media"));
        rows.add(cat(R.drawable.ic_script, getString(R.string.cat_scripts),
                getString(R.string.cat_scripts_desc), "scripts"));
        rows.add(cat(R.drawable.ic_code, getString(R.string.cat_advanced),
                getString(R.string.cat_advanced_desc), "advanced"));
        // v1.4.2 — ABOUT BOLT LIVES IN THE DEEP TREE (the field report:
        // "move the about bolt to deep settings" — it sat on the settings
        // home since v1.4.0). Last row of the category list, carrying the
        // LIVE version chip (read from the package, never hardcoded — the
        // v1.3.95 label law) so the current version is visible without
        // opening the screen.
        try {
            String vn = "?";
            try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Throwable ignored) {}
            Row about = new Row();
            about.icon = R.drawable.ic_info;
            about.title = getString(R.string.about_bolt);
            about.value = "v" + vn;
            about.hint = "Small info, the live version, the engine, the "
                    + "recent updates list and the update checker against "
                    + "the public releases mirror.";
            about.click = v ->
                    startActivity(new Intent(this, AboutActivity.class));
            rows.add(about);
        } catch (Throwable ignored) {}
        setRows(rows);
    }

    private Row cat(int icon, String title, String sub, final String cat) {
        Row r = new Row();
        r.icon = icon;
        r.title = title;
        r.sub = sub;
        r.hint = sub;
        r.click = v -> {
            if ("appearance".equals(cat)) {
                startActivity(new Intent(this, AppearanceActivity.class));
                return;
            }
            Intent i = new Intent(this, SettingsItemsActivity.class);
            i.putExtra(CAT, cat);
            startActivity(i);
        };
        return r;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        insertSearch();
    }

    /** v1.3.39 — the deep-settings search: filters the full deep index
     *  (categories' rows + appearance rows) and deep-links to the owner.
     *
     *  v1.3.40 — PROMINENT FIELD (field report task: "the deep settings
     *  search bar looks like a menu row — make it bigger and visually
     *  separated"): the bare 14sp pill-on-a-card became a 52dp URL-bar-style
     *  container with a leading search glyph, a trailing clear button and
     *  true margins off both the header and the category rows — the same
     *  DNA as the surface settings search, so search reads as search. */
    private void insertSearch() {
        try {
            LinearLayout root = (LinearLayout) findViewById(R.id.rv_g).getParent();

            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundResource(R.drawable.bg_urlbar);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Fx.dp(this, 52));
            int m = Fx.dp(this, 16);
            blp.setMargins(m, Fx.dp(this, 8), m, Fx.dp(this, 12));
            bar.setPadding(Fx.dp(this, 14), 0, Fx.dp(this, 4), 0);

            ImageView glyph = new ImageView(this);
            glyph.setImageResource(R.drawable.ic_search);
            glyph.setColorFilter(Fx.dim(this));
            bar.addView(glyph, new LinearLayout.LayoutParams(Fx.dp(this, 22), Fx.dp(this, 22)));

            EditText et = new EditText(this);
            et.setHint(getString(R.string.search_settings));
            et.setTextSize(16f);
            et.setSingleLine(true);
            et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            et.setBackground(null);
            et.setTextColor(Fx.txt(this));
            et.setHintTextColor(Fx.dim(this));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, -2, 1f);
            elp.setMargins(Fx.dp(this, 10), 0, Fx.dp(this, 10), 0);
            bar.addView(et, elp);
            search = et;

            ImageButton clear = new ImageButton(this);
            clear.setImageResource(R.drawable.ic_close);
            clear.setBackgroundResource(R.drawable.bg_item_round);
            clear.setColorFilter(Fx.dim(this));
            clear.setVisibility(View.GONE);
            clear.setContentDescription(getString(R.string.clear));
            clear.setOnClickListener(v -> et.setText(""));
            bar.addView(clear, new LinearLayout.LayoutParams(Fx.dp(this, 40), Fx.dp(this, 40)));
            root.addView(bar, 1, blp);

            LinearLayout res = new LinearLayout(this);
            res.setOrientation(LinearLayout.VERTICAL);
            res.setVisibility(View.GONE);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0, 1f);
            root.addView(res, 2, rlp);
            results = res;

            // v1.3.40 — THE CARD TIER on the deep surface (field task 11:
            // "use this approach anywhere there is a lot of options"): a
            // horizontally scrolling strip of the most-used destinations
            // above the category LIST. Hidden while searching so results
            // own the screen.
            List<com.bolt.browser.util.MenuCards.Card> cards = new ArrayList<>();
            cards.add(com.bolt.browser.util.MenuCards.card(R.drawable.ic_droplet,
                    getString(R.string.appearance),
                    "Theme, accent color, dark mode, fonts and interaction switches.",
                    () -> startActivity(new Intent(this, AppearanceActivity.class))));
            cards.add(com.bolt.browser.util.MenuCards.card(R.drawable.ic_person,
                    getString(R.string.workspaces),
                    "Named spaces with their own tab sets — Study, Entertainment, Work.",
                    () -> startActivity(new Intent(this, WorkspacesActivity.class))));
            cards.add(com.bolt.browser.util.MenuCards.card(R.drawable.ic_backup,
                    getString(R.string.backup_export),
                    "Export, import and choose exactly what rides along.",
                    () -> startActivity(new Intent(this, BackupActivity.class))));
            cards.add(com.bolt.browser.util.MenuCards.card(R.drawable.ic_lock,
                    "Passwords",
                    "The encrypted login vault, behind the device credential.",
                    () -> startActivity(new Intent(this, PasswordVaultActivity.class))));
            cards.add(com.bolt.browser.util.MenuCards.card(R.drawable.ic_script,
                    getString(R.string.filter_lists),
                    "Which blocklists the adblock engine loads.",
                    () -> startActivity(new Intent(this, FilterListsActivity.class))));
            com.bolt.browser.util.MenuCards.Card[] cardArr =
                    cards.toArray(new com.bolt.browser.util.MenuCards.Card[0]);
            View strip = com.bolt.browser.util.MenuCards.cardStrip(this,
                    java.util.Arrays.asList(cardArr));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.setMargins(Fx.dp(this, 14), 0, Fx.dp(this, 14), Fx.dp(this, 10));
            root.addView(strip, 3, slp);
            quickStrip = strip;

            // search takes the space the list would use; typing swaps them
            findViewById(R.id.rv_g).setVisibility(View.VISIBLE);
            et.addTextChangedListener(new TextWatcher() {
                public void afterTextChanged(Editable s) {
                    clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                    render(s.toString());
                }
                public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
                public void onTextChanged(CharSequence a, int b, int c, int d) {}
            });
        } catch (Throwable ignored) {}
    }

    /** one searchable entry. */
    private static class Entry {
        final int icon;
        final String title, cat;
        final Runnable open;
        Entry(int icon, String title, String cat, Runnable open) {
            this.icon = icon; this.title = title; this.cat = cat; this.open = open;
        }
    }

    private List<Entry> deepIndex() {
        List<Entry> out = new ArrayList<>();
        Runnable appearance = () -> startActivity(new Intent(this, AppearanceActivity.class));
        out.add(new Entry(R.drawable.ic_droplet, getString(R.string.appearance),
                getString(R.string.cat_appearance), appearance));
        for (String skin : new String[]{"Classic", "Aura", "Mono", "Origins", "Pulse"}) {
            out.add(new Entry(R.drawable.ic_droplet, skin + " theme",
                    getString(R.string.cat_appearance), appearance));
        }
        out.add(new Entry(R.drawable.ic_moon, getString(R.string.mode),
                getString(R.string.cat_appearance), appearance));
        out.add(new Entry(R.drawable.ic_droplet, getString(R.string.accent_color),
                getString(R.string.cat_appearance), appearance));
        out.add(new Entry(R.drawable.ic_moon, getString(R.string.dark_web_pages),
                getString(R.string.cat_appearance), appearance));
        // v1.3.40 — auto-dark exceptions manager (round-4 #12) searchable
        out.add(new Entry(R.drawable.ic_moon, "Per-site dark overrides",
                getString(R.string.cat_appearance), () ->
                        startActivity(new Intent(this, SiteDarkActivity.class))));
        out.add(new Entry(R.drawable.ic_edit, getString(R.string.font_size),
                getString(R.string.cat_appearance), appearance));
        out.add(new Entry(R.drawable.ic_globe, "Suggested sites",
                getString(R.string.cat_appearance), appearance));
        out.add(new Entry(R.drawable.ic_home, "Wallpaper",
                getString(R.string.start_page), () ->
                        startActivity(new Intent(this, WallpaperAdjustActivity.class))));
        out.add(new Entry(R.drawable.ic_arrow_down, getString(R.string.url_bar_bottom),
                getString(R.string.interaction), appearance));
        out.add(new Entry(R.drawable.ic_back, getString(R.string.edge_swipe),
                getString(R.string.interaction), appearance));
        out.add(new Entry(R.drawable.ic_arrow_down, getString(R.string.hide_bars_scroll),
                getString(R.string.interaction), appearance));
        out.add(new Entry(R.drawable.ic_check, getString(R.string.haptics),
                getString(R.string.interaction), appearance));
        out.add(new Entry(R.drawable.ic_reload, getString(R.string.pull_to_refresh),
                getString(R.string.interaction), appearance));
        out.add(new Entry(R.drawable.ic_edit, getString(R.string.long_press_actions),
                getString(R.string.interaction), () ->
                        startActivity(new Intent(this, QuickActionsActivity.class))));

        Runnable g = deep("general");
        out.add(new Entry(R.drawable.ic_search, getString(R.string.search_engine),
                getString(R.string.cat_general), g));
        out.add(new Entry(R.drawable.ic_search, "Custom search engines",
                getString(R.string.cat_general), () ->
                        startActivity(new Intent(this, EnginesActivity.class))));
        // v1.4.2 — local-first suggestions (bookmarks + shortcuts above
        // history) searchable from the deep index
        out.add(new Entry(R.drawable.ic_bookmark, getString(R.string.suggest_local_first),
                getString(R.string.cat_general), g));
        out.add(new Entry(R.drawable.ic_home, getString(R.string.homepage),
                getString(R.string.cat_general), g));
        out.add(new Entry(R.drawable.ic_tabcount, getString(R.string.restore_tabs),
                getString(R.string.cat_general), g));
        // legacy alias: users looking for the old "Session manager" land on
        // its successor — same target, keeps "session" queries working
        out.add(new Entry(R.drawable.ic_history, "Sessions",
                getString(R.string.cat_general), () ->
                        startActivity(new Intent(this, WorkspacesActivity.class))));
        // v1.3.40 — Workspaces (the Profiles+Sessions merge) is searchable
        out.add(new Entry(R.drawable.ic_person, "Workspaces",
                getString(R.string.cat_general), () ->
                        startActivity(new Intent(this, WorkspacesActivity.class))));
        out.add(new Entry(R.drawable.ic_close, getString(R.string.confirm_tab_close),
                getString(R.string.cat_general), g));
        out.add(new Entry(R.drawable.ic_plus, getString(R.string.new_tab_background),
                getString(R.string.cat_general), g));
        // v1.3.40 — backup now reachable from BOTH settings searches too
        out.add(new Entry(R.drawable.ic_backup, getString(R.string.backup_export),
                getString(R.string.cat_general), () ->
                        startActivity(new Intent(this, BackupActivity.class))));
        // v1.3.40 — weekly auto-backup (round-4 #14) searchable
        out.add(new Entry(R.drawable.ic_backup, "Weekly auto-backup",
                getString(R.string.backup_export), () ->
                        startActivity(new Intent(this, BackupActivity.class))));

        Runnable t = deep("tabs");
        out.add(new Entry(R.drawable.ic_folder, getString(R.string.groups_position),
                getString(R.string.cat_tabs), t));
        out.add(new Entry(R.drawable.ic_tabcount, getString(R.string.tab_view),
                getString(R.string.cat_tabs), t));
        out.add(new Entry(R.drawable.ic_moon, "Sleep background tabs",
                getString(R.string.cat_tabs), t));
        out.add(new Entry(R.drawable.ic_trash, "Recently closed tabs",
                getString(R.string.cat_tabs), t));

        Runnable p = deep("privacy");
        out.add(new Entry(R.drawable.ic_filter, getString(R.string.adblock_master),
                getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_script, getString(R.string.filter_lists),
                getString(R.string.cat_privacy), () ->
                        startActivity(new Intent(this, FilterListsActivity.class))));
        // v1.3.40 — tracker cleaner (round-4 #4) is searchable: deep-links to
        // the Privacy row that owns the master switch
        out.add(new Entry(R.drawable.ic_filter, "Clean tracking parameters",
                getString(R.string.cat_privacy), p));
        // v1.3.40 — per-site zoom (round-4 #5): page-menu feature, indexed
        // so search finds it
        out.add(new Entry(R.drawable.ic_tune, "Site zoom",
                "Page menu", () -> Fx.toast(this, "Open a site → ⋮ → Site zoom")));
        out.add(new Entry(R.drawable.ic_close, getString(R.string.popup_blocker),
                getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_shieldoff, getString(R.string.block_adult),
                getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_info, getString(R.string.do_not_track),
                getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_delete, getString(R.string.clear_browsing_data),
                getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_power, getString(R.string.clear_on_exit),
                getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_lock, "App lock",
                getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_lock, "Saved passwords",
                getString(R.string.cat_privacy), () ->
                        startActivity(new Intent(this, PasswordVaultActivity.class))));
        out.add(new Entry(R.drawable.ic_person, "Address & card profiles",
                getString(R.string.cat_privacy), () ->
                        startActivity(new Intent(this, ProfilesActivity.class))));

        Runnable m = deep("media");
        out.add(new Entry(R.drawable.ic_volume, getString(R.string.background_play),
                getString(R.string.cat_media), m));
        out.add(new Entry(R.drawable.ic_volume, getString(R.string.media_booster),
                getString(R.string.cat_media), m));
        out.add(new Entry(R.drawable.ic_speak, getString(R.string.vocal_boost),
                getString(R.string.cat_media), m));
        out.add(new Entry(R.drawable.ic_power, getString(R.string.background_survival),
                getString(R.string.cat_media), m));
        out.add(new Entry(R.drawable.ic_volume, getString(R.string.max_volume),
                getString(R.string.cat_media), m));
        out.add(new Entry(R.drawable.ic_arrow_up, "Auto picture-in-picture",
                getString(R.string.cat_media), m));

        Runnable s = deep("scripts");
        out.add(new Entry(R.drawable.ic_script, getString(R.string.scripts_enabled),
                getString(R.string.cat_scripts), s));
        out.add(new Entry(R.drawable.ic_code, getString(R.string.userscripts),
                getString(R.string.cat_scripts), () ->
                        startActivity(new Intent(this,
                                com.bolt.browser.scripts.UserscriptsActivity.class))));

        Runnable a = deep("advanced");
        out.add(new Entry(R.drawable.ic_mobile, getString(R.string.global_ua),
                getString(R.string.cat_advanced), a));
        // v1.4.2 — About moved into the deep tree: searchable from here too
        out.add(new Entry(R.drawable.ic_info, getString(R.string.about_bolt),
                getString(R.string.cat_advanced), () ->
                        startActivity(new Intent(this, AboutActivity.class))));
        out.add(new Entry(R.drawable.ic_sun, getString(R.string.keep_screen_on),
                getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_expand, "Fullscreen uses notch area",
                getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_moon, "Night screen",
                getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_sun, "Brightness",
                getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_download, "Data saver",
                getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_lock, "Passkey status",
                getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_lock, "Stored passkeys",
                getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_info, "Copy diagnostics",
                getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_fire, "Repair web engine",
                getString(R.string.cat_advanced), a));
        return out;
    }

    private Runnable deep(final String cat) {
        return () -> {
            Intent i = new Intent(this, SettingsItemsActivity.class);
            i.putExtra(CAT, cat);
            startActivity(i);
        };
    }

    private void render(String q) {
        try {
            if (results == null) return;
            results.removeAllViews();
            String qq = q == null ? "" : q.trim().toLowerCase();
            boolean empty = qq.isEmpty();
            results.setVisibility(empty ? View.GONE : View.VISIBLE);
            findViewById(R.id.rv_g).setVisibility(empty ? View.VISIBLE : View.GONE);
            // v1.3.40: results own the screen — the quick strip steps aside
            if (quickStrip != null) quickStrip.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (empty) return;
            for (Entry e : deepIndex()) {
                if (!e.title.toLowerCase().contains(qq)
                        && !e.cat.toLowerCase().contains(qq)) continue;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(Fx.cardBg(this, 0));
                row.setPadding(Fx.dp(this, 14), Fx.dp(this, 12), Fx.dp(this, 14), Fx.dp(this, 12));
                ImageView iv = new ImageView(this);
                iv.setImageResource(e.icon);
                iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
                Ui.iconify(iv, true);
                android.widget.FrameLayout fl = new android.widget.FrameLayout(this);
                fl.addView(iv);
                row.addView(fl, new LinearLayout.LayoutParams(Fx.dp(this, 34), Fx.dp(this, 34)));
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView t = new TextView(this);
                t.setText(e.title);
                t.setTextSize(15f);
                t.setTextColor(Fx.txt(this));
                t.setTypeface(Ui.medium());
                col.addView(t, new LinearLayout.LayoutParams(-1, -2));
                TextView c = new TextView(this);
                c.setText(e.cat);
                c.setTextSize(12f);
                c.setTextColor(Fx.dim(this));
                col.addView(c, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
                clp.leftMargin = Fx.dp(this, 12);
                row.addView(col, clp);
                row.setOnClickListener(v -> {
                    Ui.tap(v);
                    e.open.run();
                });
                MenuCards.explain(row, this, e.title, "Lives in " + e.cat + ".");
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = Fx.dp(this, 5);
                results.addView(row, lp);
            }
            if (results.getChildCount() == 0) {
                TextView none = new TextView(this);
                none.setText(getString(R.string.search_settings) + ": "
                        + getString(R.string.empty));
                none.setTextSize(14f);
                none.setTextColor(Fx.dim(this));
                none.setPadding(Fx.dp(this, 8), Fx.dp(this, 16), Fx.dp(this, 8), 0);
                results.addView(none, new LinearLayout.LayoutParams(-1, -2));
            }
        } catch (Throwable ignored) {}
    }
}
