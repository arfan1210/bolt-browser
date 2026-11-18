package com.bolt.browser.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bolt.browser.R;
import com.bolt.browser.core.TabManager;
import com.bolt.browser.media.MediaControlService;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Prefs;
import com.bolt.browser.util.Ui;

import java.util.ArrayList;
import java.util.List;

/** Opera-like settings landing: profile, library, appearance, adblock, backup, exit + gear.
 *  v1.3.34: card icons get the accent-container treatment, the adblock
 *  switch is the modern one.
 *  v1.3.36: the bottom quick-settings pill bar is REMOVED (the root of the
 *  reported settings duplication) and the duplicate gear card follows the
 *  top-right gear as the single deep-settings entry. New: SETTINGS SEARCH —
 *  a field that filters a hand-rolled index of every setting row in the
 *  app and deep-links to the right screen (the Google Settings pattern). */
public class SettingsHomeActivity extends AppCompatActivity {

    private Prefs prefs;
    /** v1.3.40 — handles for the list-tier switches (the settings-search
     *  deep-links toggle them). */
    private Ui.ModernSwitch fsSwitch, adSwitch;
    private LinearLayout results;
    private android.view.ViewGroup contentCards;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Fx.theme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_home);
        prefs = Prefs.get(this);

        TextView prof = findViewById(R.id.tv_sh_profile);
        prof.setText(prefs.profileName().isEmpty() ? "Not set" : prefs.profileName());

        int dim = Fx.dim(this);
        ImageButton gear = findViewById(R.id.btn_sh_gear);
        ImageButton exit = findViewById(R.id.btn_sh_exit);
        gear.setColorFilter(dim);
        exit.setColorFilter(0xFFEF4444);

        // v1.3.34 — accent icon containers on every card (the Google
        // Settings / LibreTube signature look)
        int[] iconIds = {R.id.iv_sh_person, R.id.iv_sh_backup};
        for (int id : iconIds) {
            ImageView iv = findViewById(id);
            if (iv != null) Ui.iconify(iv, true);
        }
        // light skins: cards get a whisper of elevation
        if (Ui.wantShadows(this)) {
            int[] cardIds = {R.id.card_profile, R.id.card_backup};
            for (int id : cardIds) {
                android.view.View v = findViewById(id);
                if (v != null) v.setElevation(Fx.dp(this, 1));
            }
        }

        gear.setOnClickListener(v -> startActivity(new Intent(this, DeepSettingsActivity.class)));
        exit.setOnClickListener(v -> confirmExit());

        // v1.3.40 — THE HYBRID SURFACE (field task 11) + WORKSPACES (task
        // 15). Card tier: a horizontally scrolling destination strip. List
        // tier: fullscreen/adblock as switch rows + the backup row. The
        // identity card opens Workspaces — the Profiles + Sessions merge.
        findViewById(R.id.card_profile).setOnClickListener(v ->
                startActivity(new Intent(this,
                        com.bolt.browser.ui.WorkspacesActivity.class)));
        com.bolt.browser.util.MenuCards.explain(findViewById(R.id.card_profile), this,
                getString(R.string.workspaces),
                "Named spaces with their own tab sets — switch and the open tabs ride "
                        + "along. The old Sessions and the backup login name live here now.");
        refreshWorkspaceState();

        android.view.ViewGroup stripHost = findViewById(R.id.ll_sh_strip);
        List<com.bolt.browser.util.MenuCards.Card> cards = new ArrayList<>();
        // v1.3.75 — BOLT AI reaches Settings too (the user's ask: "accessible
        // anywhere inside the browser, like in settings, tabs, websites").
        // No page context here — the panel chats; its page chips explain
        // themselves; the key link copies to the clipboard.
        cards.add(com.bolt.browser.util.MenuCards.card(R.drawable.ic_fire,
                "Bolt AI", "The browser-wide AI assistant — chat, summarize pages, translate what you see.",
                () -> {
                    try {
                        com.bolt.browser.util.BoltAi.open(this, null, null);
                    } catch (Throwable t) {
                        Fx.toast(this, "Could not open Bolt AI");
                    }
                }));
        cards.add(com.bolt.browser.util.MenuCards.card(R.drawable.ic_bookmark,
                getString(R.string.bookmarks), "Saved pages with folders — tap a bookmark to open it.",
                () -> Ui.launchSatellite(this, BookmarksActivity.class)));
        cards.add(com.bolt.browser.util.MenuCards.card(R.drawable.ic_history,
                getString(R.string.history), "Recently visited pages; clear them from Privacy → Clear browsing data.",
                () -> Ui.launchSatellite(this, HistoryActivity.class)));
        cards.add(com.bolt.browser.util.MenuCards.card(R.drawable.ic_download,
                getString(R.string.downloads), "Bolt's own download engine: queue, pause/resume/retry.",
                () -> Ui.launchSatellite(this, DownloadsActivity.class)));
        cards.add(com.bolt.browser.util.MenuCards.card(R.drawable.ic_offline,
                getString(R.string.offline_pages), "Pages saved as files that open without network.",
                () -> Ui.launchSatellite(this, OfflinePagesActivity.class)));
        stripHost.addView(com.bolt.browser.util.MenuCards.cardStrip(this, cards));

        // the LIST TIER: the two toggles as compact rows (was two big cards)
        android.view.ViewGroup rowsHost = findViewById(R.id.ll_sh_rows);
        fsSwitch = toggleRow(rowsHost, R.drawable.ic_expand, getString(R.string.fullscreen_mode),
                com.bolt.browser.util.Fullscreen.on(this),
                "Fullscreen browsing: every Bolt bar and the Android status bar stay hidden "
                        + "for true edge-to-edge pages (notch included when the deep-settings "
                        + "toggle allows it). A moveable floating button brings the bars back; "
                        + "scrolling works as usual.",
                on -> {
                    prefs.set(com.bolt.browser.util.Fullscreen.PREF, on);
                    Fx.skinGen++;           // the browser re-applies on next resume
                });
        adSwitch = toggleRow(rowsHost, R.drawable.ic_filter, getString(R.string.adblock),
                prefs.adblockEnabled(),
                "Master switch for the ad and tracker blocker.",
                on -> prefs.setAdblock(on));

        // v1.4.2 — THE ABOUT ROW MOVED TO DEEP SETTINGS (the field report:
        // "move the about bolt to deep settings"). The surface keeps only
        // what changes daily; About is reference material. The row now
        // lives at the bottom of the DeepSettings category list (with the
        // live version chip), and BOTH settings searches still find it.

        findViewById(R.id.card_backup).setOnClickListener(v ->
                startActivity(new Intent(this, BackupActivity.class)));
        com.bolt.browser.util.MenuCards.explain(findViewById(R.id.card_backup), this,
                getString(R.string.backup_export), "Export/import everything as a zip — pick exactly what rides along.");

        setupSearch();
    }

    /** v1.3.40 — one LIST-TIER row: iconified icon + title + modern switch
     *  (the BackupActivity row pattern, now on the surface). */
    private Ui.ModernSwitch toggleRow(android.view.ViewGroup host, int icon, String title,
            boolean on, String hint, Ui.OnToggle cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackground(Fx.cardBg(this, 0));
        row.setPadding(Fx.dp(this, 14), Fx.dp(this, 12), Fx.dp(this, 14), Fx.dp(this, 12));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        Ui.iconify(iv, true);
        row.addView(iv, new LinearLayout.LayoutParams(Fx.dp(this, 40), Fx.dp(this, 40)));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(Ui.medium());
        t.setTextColor(Fx.txt(this));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.setMargins(Fx.dp(this, 14), 0, 0, 0);
        row.addView(t, tlp);
        Ui.ModernSwitch sw = Ui.modernSwitch(this, on, cb);
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(v -> sw.toggle(true));
        com.bolt.browser.util.MenuCards.explain(row, this, title, hint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Fx.dp(this, 6);
        host.addView(row, lp);
        return sw;
    }

    /** the Workspaces card subtitle: live space + its live tab count. */
    private void refreshWorkspaceState() {
        try {
            TextView sub = findViewById(R.id.tv_sh_ws_sub);
            if (sub == null) return;
            String act = com.bolt.browser.util.Workspaces.active(this);
            int n = com.bolt.browser.util.Workspaces.liveTabs(this).size();
            sub.setText(act + " · " + n + " tabs");
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // returning from Workspaces (switch/edit) refreshes both surfaces
        refreshWorkspaceState();
        TextView prof = findViewById(R.id.tv_sh_profile);
        if (prof != null) {
            String n = prefs.profileName();
            prof.setText(n.isEmpty() ? "Not set" : n);
        }
    }

    // ------------------------------------------------------------ v1.3.36 settings search

    /** one searchable settings entry: icon, title, category caption, and the
     *  screen that owns it. */
    private static class Entry {
        final int icon;
        final String title;
        final String cat;
        final Runnable open;
        Entry(int icon, String title, String cat, Runnable open) {
            this.icon = icon; this.title = title; this.cat = cat; this.open = open;
        }
    }

    private List<Entry> index;

    /** the full settings index — every row the app exposes, each pointing at
     *  the screen that owns it. Rows render from here for search results. */
    private List<Entry> buildIndex() {
        List<Entry> out = new ArrayList<>();
        // surface screens
        // v1.3.39: Appearance now lives in the DEEP tree — the entry
        // deep-links through the deep settings' new Appearance category.
        out.add(new Entry(R.drawable.ic_droplet, getString(R.string.appearance),
                getString(R.string.cat_appearance), () -> startActivity(new Intent(this,
                        DeepSettingsActivity.class))));
        // v1.4.0 — About + update checker, searchable like everything else
        out.add(new Entry(R.drawable.ic_info, getString(R.string.about_bolt),
                getString(R.string.settings), () -> startActivity(new Intent(this,
                        AboutActivity.class))));
        out.add(new Entry(R.drawable.ic_bookmark, getString(R.string.bookmarks),
                getString(R.string.settings), () -> Ui.launchSatellite(this, BookmarksActivity.class)));
        out.add(new Entry(R.drawable.ic_history, getString(R.string.history),
                getString(R.string.settings), () -> Ui.launchSatellite(this, HistoryActivity.class)));
        out.add(new Entry(R.drawable.ic_download, getString(R.string.downloads),
                getString(R.string.settings), () -> Ui.launchSatellite(this, DownloadsActivity.class)));
        out.add(new Entry(R.drawable.ic_offline, getString(R.string.offline_pages),
                getString(R.string.settings), () -> Ui.launchSatellite(this, OfflinePagesActivity.class)));
        out.add(new Entry(R.drawable.ic_backup, getString(R.string.backup_export),
                getString(R.string.settings), () -> startActivity(new Intent(this, BackupActivity.class))));
        out.add(new Entry(R.drawable.ic_person, getString(R.string.profile),
                getString(R.string.settings), () -> findViewById(R.id.card_profile).performClick()));
        out.add(new Entry(R.drawable.ic_filter, getString(R.string.adblock),
                getString(R.string.settings), () -> {
                    if (adSwitch != null) adSwitch.toggle(true);
                }));
        out.add(new Entry(R.drawable.ic_lock, getString(R.string.authenticator),
                getString(R.string.settings), () -> startActivity(new Intent(this,
                        com.bolt.browser.ui.AuthenticatorActivity.class))));
        // v1.3.38 new-feature entries
        out.add(new Entry(R.drawable.ic_bookmark, "Watch later",
                getString(R.string.settings), () -> startActivity(new Intent(this,
                        com.bolt.browser.ui.WatchLaterActivity.class))));
        out.add(new Entry(R.drawable.ic_lock, "Saved passwords",
                getString(R.string.settings), () -> startActivity(new Intent(this,
                        com.bolt.browser.ui.PasswordVaultActivity.class))));
        out.add(new Entry(R.drawable.ic_edit, getString(R.string.long_press_actions),
                getString(R.string.interaction), () -> startActivity(new Intent(this,
                        com.bolt.browser.ui.QuickActionsActivity.class))));
        out.add(new Entry(R.drawable.ic_droplet, "Adjust wallpaper",
                getString(R.string.cat_appearance), () -> startActivity(new Intent(this,
                        com.bolt.browser.ui.WallpaperAdjustActivity.class))));
        // v1.3.39 new-feature entries
        out.add(new Entry(R.drawable.ic_expand, getString(R.string.fullscreen_mode),
                getString(R.string.settings), () -> {
                    if (fsSwitch != null) fsSwitch.toggle(true);
                }));
        out.add(new Entry(R.drawable.ic_trash, "Recently closed tabs",
                getString(R.string.cat_tabs), () -> startActivity(new Intent(this,
                        com.bolt.browser.ui.TabTrashActivity.class))));
        out.add(new Entry(R.drawable.ic_reader, "Reader view",
                "Page menu", () -> Fx.toast(this, "Open any article → ⋮ → Reader view")));
        out.add(new Entry(R.drawable.ic_outline, "Page outline",
                "Page menu", () -> Fx.toast(this, "Open a long page → ⋮ → Page outline")));
        out.add(new Entry(R.drawable.ic_print, "Full-page screenshot",
                "Page menu", () -> Fx.toast(this, "Open a page → ⋮ → Shot")));
        out.add(new Entry(R.drawable.ic_calc, "Instant answers",
                "Address bar", () -> Fx.toast(this, "Type math or units in the address bar: 12*34, 100kg in lbs")));
        out.add(new Entry(R.drawable.ic_search, "Custom search engines",
                getString(R.string.cat_general), () -> startActivity(new Intent(this,
                        com.bolt.browser.ui.EnginesActivity.class))));
        out.add(new Entry(R.drawable.ic_moon, "Night screen",
                getString(R.string.cat_advanced), () -> deepLink("advanced")));
        out.add(new Entry(R.drawable.ic_sun, "Brightness",
                getString(R.string.cat_advanced), () -> deepLink("advanced")));
        out.add(new Entry(R.drawable.ic_download, "Data saver",
                getString(R.string.cat_advanced), () -> deepLink("advanced")));
        // v1.3.40 — tracker cleaner + per-site zoom (round-4 #4/#5) indexed
        out.add(new Entry(R.drawable.ic_filter, "Clean tracking parameters",
                getString(R.string.cat_privacy), () -> deepLink("privacy")));
        out.add(new Entry(R.drawable.ic_tune, "Site zoom",
                "Page menu", () -> Fx.toast(this, "Open a site → ⋮ → Site zoom")));
        out.add(new Entry(R.drawable.ic_person, "Address & card profiles",
                getString(R.string.cat_privacy), () -> startActivity(new Intent(this,
                        com.bolt.browser.ui.ProfilesActivity.class))));
        out.add(new Entry(R.drawable.ic_history, "Sessions",
                getString(R.string.cat_general), () -> deepLink("general")));
        // v1.3.40 — Workspaces (Profiles+Sessions merged) indexed here too
        out.add(new Entry(R.drawable.ic_person, "Workspaces",
                getString(R.string.cat_general), () -> startActivity(new Intent(this,
                        com.bolt.browser.ui.WorkspacesActivity.class))));
        // v1.3.40 — backup indexed here too (was reachable only via the
        // surface card before)
        out.add(new Entry(R.drawable.ic_backup, getString(R.string.backup_export),
                getString(R.string.cat_general), () -> startActivity(new Intent(this,
                        com.bolt.browser.ui.BackupActivity.class))));

        // appearance rows (the dedicated screen owns them)
        Runnable appearance = () -> startActivity(new Intent(this, AppearanceActivity.class));
        out.add(new Entry(R.drawable.ic_globe, "Suggested sites",
                getString(R.string.cat_appearance), appearance));
        out.add(new Entry(R.drawable.ic_mic, "Voice search",
                getString(R.string.interaction), appearance));
        for (String skin : new String[]{"Classic", "Aura", "Mono", "Origins", "Pulse"}) {
            out.add(new Entry(R.drawable.ic_droplet, skin + " theme", getString(R.string.cat_appearance), appearance));
        }
        out.add(new Entry(R.drawable.ic_moon, getString(R.string.mode), getString(R.string.cat_appearance), appearance));
        // v1.3.40 — per-site dark exceptions manager (round-4 #12)
        out.add(new Entry(R.drawable.ic_moon, "Per-site dark overrides",
                getString(R.string.cat_appearance), () ->
                        startActivity(new Intent(this, SiteDarkActivity.class))));
        // v1.3.40 — weekly auto-backup (round-4 #14) deep-links to the screen
        out.add(new Entry(R.drawable.ic_backup, "Weekly auto-backup",
                getString(R.string.backup_export), () ->
                        startActivity(new Intent(this, BackupActivity.class))));
        out.add(new Entry(R.drawable.ic_droplet, getString(R.string.accent_color), getString(R.string.cat_appearance), appearance));
        out.add(new Entry(R.drawable.ic_moon, getString(R.string.dark_web_pages), getString(R.string.cat_appearance), appearance));
        out.add(new Entry(R.drawable.ic_edit, getString(R.string.font_size), getString(R.string.cat_appearance), appearance));
        out.add(new Entry(R.drawable.ic_reload, getString(R.string.pull_to_refresh), getString(R.string.interaction), appearance));
        out.add(new Entry(R.drawable.ic_arrow_down, getString(R.string.url_bar_bottom), getString(R.string.interaction), appearance));
        out.add(new Entry(R.drawable.ic_back, getString(R.string.edge_swipe), getString(R.string.interaction), appearance));
        out.add(new Entry(R.drawable.ic_arrow_down, getString(R.string.hide_bars_scroll),
                getString(R.string.interaction), appearance));
        out.add(new Entry(R.drawable.ic_check, getString(R.string.haptics), getString(R.string.interaction), appearance));

        // deep categories
        Runnable g = () -> deepLink("general");
        out.add(new Entry(R.drawable.ic_search, getString(R.string.search_engine), getString(R.string.cat_general), g));
        out.add(new Entry(R.drawable.ic_home, getString(R.string.homepage), getString(R.string.cat_general), g));
        out.add(new Entry(R.drawable.ic_tabcount, getString(R.string.restore_tabs), getString(R.string.cat_general), g));
        out.add(new Entry(R.drawable.ic_close, getString(R.string.confirm_tab_close), getString(R.string.cat_general), g));
        out.add(new Entry(R.drawable.ic_plus, getString(R.string.new_tab_background), getString(R.string.cat_general), g));

        Runnable t = () -> deepLink("tabs");
        out.add(new Entry(R.drawable.ic_folder, getString(R.string.groups_position), getString(R.string.cat_tabs), t));
        out.add(new Entry(R.drawable.ic_tabcount, getString(R.string.tab_view), getString(R.string.cat_tabs), t));
        out.add(new Entry(R.drawable.ic_moon, "Sleep background tabs", getString(R.string.cat_tabs), t));

        Runnable p = () -> deepLink("privacy");
        out.add(new Entry(R.drawable.ic_filter, getString(R.string.adblock_master), getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_script, getString(R.string.filter_lists), getString(R.string.cat_privacy),
                () -> startActivity(new Intent(this, FilterListsActivity.class))));
        out.add(new Entry(R.drawable.ic_close, getString(R.string.popup_blocker), getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_shieldoff, getString(R.string.block_adult), getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_info, getString(R.string.do_not_track), getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_delete, getString(R.string.clear_browsing_data), getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_power, getString(R.string.clear_on_exit), getString(R.string.cat_privacy), p));
        out.add(new Entry(R.drawable.ic_lock, "App lock", getString(R.string.cat_privacy), p));

        Runnable m = () -> deepLink("media");
        out.add(new Entry(R.drawable.ic_volume, getString(R.string.background_play), getString(R.string.cat_media), m));
        out.add(new Entry(R.drawable.ic_volume, getString(R.string.media_booster), getString(R.string.cat_media), m));
        out.add(new Entry(R.drawable.ic_speak, getString(R.string.vocal_boost), getString(R.string.cat_media), m));
        out.add(new Entry(R.drawable.ic_power, getString(R.string.background_survival), getString(R.string.cat_media), m));
        out.add(new Entry(R.drawable.ic_volume, getString(R.string.max_volume), getString(R.string.cat_media), m));
        out.add(new Entry(R.drawable.ic_arrow_up, "Auto picture-in-picture", getString(R.string.cat_media), m));

        Runnable s = () -> deepLink("scripts");
        out.add(new Entry(R.drawable.ic_script, getString(R.string.scripts_enabled), getString(R.string.cat_scripts), s));
        out.add(new Entry(R.drawable.ic_code, getString(R.string.userscripts), getString(R.string.cat_scripts),
                () -> startActivity(new Intent(this, com.bolt.browser.scripts.UserscriptsActivity.class))));

        Runnable a = () -> deepLink("advanced");
        out.add(new Entry(R.drawable.ic_mobile, getString(R.string.global_ua), getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_sun, getString(R.string.keep_screen_on), getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_lock, "Passkey status", getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_lock, "Stored passkeys", getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_info, "Copy diagnostics", getString(R.string.cat_advanced), a));
        out.add(new Entry(R.drawable.ic_fire, "Repair web engine", getString(R.string.cat_advanced), a));
        return out;
    }

    private void deepLink(String cat) {
        Intent i = new Intent(this, SettingsItemsActivity.class);
        i.putExtra(DeepSettingsActivity.CAT, cat);
        startActivity(i);
    }

    /** wire the search field: typing filters the index into result rows and
     *  swaps the normal cards out; clearing swaps them back. */
    private void setupSearch() {
        results = findViewById(R.id.ll_sh_results);
        contentCards = findViewById(R.id.ll_sh_content);
        index = buildIndex();
        final EditText et = findViewById(R.id.et_sh_search);
        final ImageButton clear = findViewById(R.id.btn_sh_search_clear);
        ImageView si = findViewById(R.id.iv_sh_search);
        if (si != null) si.setColorFilter(Fx.dim(this));
        et.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable s) { renderSearch(s.toString()); }
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
        });
        clear.setOnClickListener(v -> et.setText(""));
    }

    /** rebuild the results column for a query (case-insensitive substring on
     *  title and category). */
    private void renderSearch(String q) {
        findViewById(R.id.btn_sh_search_clear).setVisibility(
                q == null || q.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        boolean active = q != null && !q.trim().isEmpty();
        contentCards.setVisibility(active ? android.view.View.GONE : android.view.View.VISIBLE);
        results.setVisibility(active ? android.view.View.VISIBLE : android.view.View.GONE);
        results.removeAllViews();
        if (!active) return;
        String ql = q.trim().toLowerCase();
        int tint = Fx.tint(this);
        int found = 0;
        for (Entry e : index) {
            if (!e.title.toLowerCase().contains(ql) && !e.cat.toLowerCase().contains(ql)) continue;
            found++;
            if (found > 12) break;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(Fx.dp(this, 12), Fx.dp(this, 10), Fx.dp(this, 12), Fx.dp(this, 10));
            row.setBackground(com.bolt.browser.util.Ui.rowBg(this));
            row.setClickable(true);
            row.setOnClickListener(v -> e.open.run());
            ImageView ic = new ImageView(this);
            ic.setImageResource(e.icon);
            ic.setColorFilter(tint);
            com.bolt.browser.util.Ui.iconify(ic, false);
            row.addView(ic, new LinearLayout.LayoutParams(Fx.dp(this, 30), Fx.dp(this, 30)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(this);
            title.setText(e.title);
            title.setTextSize(16);
            title.setTextColor(Fx.txt(this));
            col.addView(title, new LinearLayout.LayoutParams(-1, -2));
            TextView cat = new TextView(this);
            cat.setText(e.cat);
            cat.setTextSize(12);
            cat.setTextColor(Fx.dim(this));
            col.addView(cat, new LinearLayout.LayoutParams(-1, -2));
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.bottomMargin = Fx.dp(this, 8);
            results.addView(row, rlp);
        }
        if (found == 0) {
            results.addView(com.bolt.browser.util.Ui.emptyState(this, R.drawable.ic_search,
                    getString(R.string.no_settings_found), null));
        }
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.exit_warning)
                .setPositiveButton(R.string.yes, (d, w) -> exitNow())
                .setNegativeButton(R.string.no, null)
                .show();
    }

    private void exitNow() {
        // v1.3.13: THIS is the settings-side power button. The clear-on-exit
        // checklist runs through the ORDERED exit wipe (pages wiped -> media
        // stopped -> WebViews destroyed -> cache emptied synchronously ->
        // finish + process kill), which is what finally makes the App Settings
        // cache figure actually drop. Swipe-from-recents never comes through
        // here — wiping is bound to the button exactly as requested.
        if (TabManager.INCOGNITO) {
            // incognito process: wipe and die
            try { stopService(new Intent(this, MediaControlService.class)); } catch (Throwable ignored) {}
            try {
                android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                cm.removeAllCookies(null);
                cm.flush();
                android.webkit.WebStorage.getInstance().deleteAllData();
            } catch (Throwable ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
            return;
        }
        com.bolt.browser.util.DataWipe.performExitClear(this, prefs);
    }
}
