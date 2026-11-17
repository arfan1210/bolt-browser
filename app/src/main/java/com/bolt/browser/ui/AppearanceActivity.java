package com.bolt.browser.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bolt.browser.R;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.HomeWallpaper;
import com.bolt.browser.util.Prefs;
import com.bolt.browser.util.AppIconSwitch;
import com.bolt.browser.util.QuickActions;
import com.bolt.browser.util.Ui;

/**
 * Appearance editor: skin (theme preset), light/dark MODE, accent palette,
 * interaction toggles, dark web, font size.
 *
 * v1.3.35 — the field report said "the different themes don't seem much
 * different and all of them don't have both a light and dark mode."
 * Everything here changed:
 *  - every skin has a LIGHT and a DARK variant; the Mode row (Light / Dark /
 *    Follow system) now applies to ALL skins, not just Classic
 *  - each skin has a SIGNATURE accent ("Auto" — the new default) so Aura
 *    reads violet-glass, Mono paper/ink, Origins sage, Pulse teal;
 *    "Wallpaper" pulls the real Material You color on API 31+
 *  - skin rows show a light+dark swatch pair so both variants are visible
 *    before switching
 *  - new Interaction section: pull-to-refresh + the settings bottom bar
 */
public class AppearanceActivity extends AppCompatActivity {

    private Prefs prefs;

    /** skin ids, names, one-line descriptions (strings in code: the app is
     * English-only by design — see translate_langs for in-page translation) */
    private static final String[][] SKINS = {
            {"Classic", "The original Bolt look — clean light, dark, or follow system"},
            {"Aura", "Violet glass — smoked indigo or frosted lilac, with real blur"},
            {"Mono", "Flat monochrome — paper & ink; sharp corners, bold icons"},
            {"Origins", "Warm and tactile — cream daylight or ember-brown dark"},
            {"Pulse", "Vibrant and expressive — electric teal on deep indigo"},
    };
    /** preview swatches per skin: {lightBg, lightSurface, darkBg, darkSurface, accentDark, accentLight} */
    private static final int[][] SWATCHES = {
            {0xFFFAFAFD, 0xFFFFFFFF, 0xFF0E0E13, 0xFF1A1A22, 0xFF3E8BFF, 0xFF3E8BFF},
            {0xFFF4F1FD, 0xFFF9F6FF, 0xFF0C0A1E, 0xFF161430, 0xFF9A7BFF, 0xFF7B5CFF},
            {0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000, 0xFF0A0A0A, 0xFFEDEDED, 0xFF111111},
            {0xFFF0E9DB, 0xFFFAF5EA, 0xFF1B150E, 0xFF281F15, 0xFFF0A05A, 0xFFC96F4A},
            {0xFFF1EFFE, 0xFFFFFFFF, 0xFF131033, 0xFF201B4E, 0xFF00E5C0, 0xFF00A878},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Fx.theme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_generic_list);
        prefs = Prefs.get(this);
        ((TextView) findViewById(R.id.tv_g_title)).setText(R.string.appearance);
        findViewById(R.id.btn_g_back).post(() ->
                ((android.widget.ImageButton) findViewById(R.id.btn_g_back)).setColorFilter(Fx.dim(this)));
        findViewById(R.id.btn_g_back).setOnClickListener(v -> finish());
        findViewById(R.id.rv_g).setVisibility(View.GONE);

        // v1.3.36 FIELD FIX ("I cannot scroll down in Appearance tab"): the
        // list used to be added straight into the root LinearLayout at
        // match_parent height — taller than the screen and unscrollable. It
        // now lives inside a fillViewport ScrollView carrying the remaining
        // space (the RecyclerView slot is GONE).
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 18);
        list.setPadding(p, p, p, p + Fx.dp(this, 12));
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.addView(list, new LinearLayout.LayoutParams(-1, -2));
        ((android.view.ViewGroup) findViewById(R.id.btn_g_back).getParent().getParent())
                .addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- Skin ----
        list.addView(section(getString(R.string.theme_style)));
        final View[] skinRows = new View[SKINS.length];
        for (int i = 0; i < SKINS.length; i++) {
            final int idx = i;
            skinRows[i] = skinRow(i);
            skinRows[i].setOnClickListener(v -> {
                if (prefs.skin() == idx) return;
                prefs.set("skin", idx);
                Fx.skinGen++;                  // long-lived screens recreate on resume
                recreate();                    // this screen re-themes immediately
            });
            list.addView(skinRows[i], rowLp());
        }

        // ---- Mode (applies to EVERY skin: light / dark / follow system) ----
        list.addView(section(getString(R.string.mode)));
        LinearLayout modes = new LinearLayout(this);
        String[] tn = getResources().getStringArray(R.array.theme_names);
        final TextView[] tchips = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            tchips[i] = new TextView(this);
            tchips[i].setText(tn[i]);
            tchips[i].setGravity(Gravity.CENTER);
            tchips[i].setTextSize(14);
            tchips[i].setTypeface(Ui.medium());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Fx.dp(this, 40), 1f);
            lp.rightMargin = Fx.dp(this, 6);
            tchips[i].setLayoutParams(lp);
            tchips[i].setOnClickListener(v -> {
                prefs.set("theme", idx);
                Fx.skinGen++;
                styleChips(tchips, idx);
                recreate();     // re-theme this screen with the new lightness
            });
            modes.addView(tchips[i]);
        }
        styleChips(tchips, prefs.i("theme", 1));
        list.addView(modes, new LinearLayout.LayoutParams(-1, -2));

        // ---- Accent: Auto (skin signature) / Wallpaper (31+) / fixed swatches ----
        list.addView(section(getString(R.string.accent_color)));
        LinearLayout acc = new LinearLayout(this);
        acc.setGravity(Gravity.CENTER_VERTICAL);

        // Auto dot — shows the CURRENT skin's signature
        acc.addView(accentDot(-1, Fx.skinAccent(prefs.skin(), prefs.isDark()),
                getString(R.string.accent_auto)), dotLp(0));
        // Wallpaper dot — real Material You on API 31+
        if (Build.VERSION.SDK_INT >= 31) {
            acc.addView(accentDot(-2, Fx.wallpaperAccent(this),
                    getString(R.string.accent_wallpaper)), dotLp(0));
        }
        // fixed swatches
        final int n = Fx.ACCENTS.length;
        for (int i = 0; i < n; i++) {
            acc.addView(accentDot(i, Fx.ACCENTS[i], null), dotLp(0));
        }
        list.addView(acc, new LinearLayout.LayoutParams(-1, -2));

        // ---- Interaction ----
        // v1.3.36: the "Quick settings bar" row is GONE — the bottom pill
        // bar was removed as the root of the settings duplication (it gave
        // Appearance three entry points and Tabs two). New rows: the bottom
        // address bar, edge-swipe navigation, and haptics.
        list.addView(section(getString(R.string.interaction)));
        list.addView(rowWithSwitch(getString(R.string.url_bar_bottom),
                getString(R.string.url_bar_bottom_desc), prefs.urlBarBottom(),
                on -> {
                    prefs.set("urlBarBottom", on);
                    Fx.skinGen++;   // the browser must recreate to re-layout its chrome
                }));
        list.addView(rowWithSwitch(getString(R.string.edge_swipe),
                getString(R.string.edge_swipe_desc), prefs.edgeNav(),
                on -> prefs.set("edgeNav", on)));
        list.addView(rowWithSwitch(getString(R.string.pull_to_refresh),
                getString(R.string.pull_to_refresh_desc), prefs.pullToRefresh(),
                on -> prefs.set("pullToRefresh", on)));
        list.addView(rowWithSwitch(getString(R.string.haptics),
                getString(R.string.haptics_desc), prefs.haptics(),
                on -> prefs.set("haptics", on)));

        // ---- v1.3.37: toolbar swipe + long-press quick actions ----
        list.addView(rowWithSwitch(getString(R.string.toolbar_swipe),
                getString(R.string.toolbar_swipe_desc), prefs.toolbarSwipe(),
                on -> prefs.set("toolbarSwipe", on)));
        // v1.3.48 — the scroll-hide KILL SWITCH (field: "I like the fast
        // disappearing toolbars, but some people might not"). Default ON =
        // the shipped v1.3.47 behavior (hide at 3-4dp of scroll, return on
        // the first upward flick, both modes). OFF = scrolling never hides
        // the bars; fullscreen browsing and the keyboard keep their own.
        list.addView(rowWithSwitch(getString(R.string.hide_bars_scroll),
                getString(R.string.hide_bars_scroll_desc), prefs.b("hideBarsScroll", true),
                on -> prefs.set("hideBarsScroll", on)));
        // v1.3.38: the remapping UI is now a REAL SCREEN (QuickActionsActivity,
        // Kotlin) — one row per button, current action as a value chip,
        // one-tap remap — instead of the two nested sheets.
        list.addView(rowWithChevron(getString(R.string.long_press_actions),
                getString(R.string.long_press_actions_desc),
                QuickActions.label(this, prefs.lpAction("back",
                        QuickActions.defaultFor("back"))) + "…",
                v -> startActivity(new Intent(this, QuickActionsActivity.class))));

        // ---- Start page (v1.3.37): the custom wallpaper ----
        list.addView(section(getString(R.string.start_page)));
        list.addView(rowWithChevron(getString(R.string.wallpaper),
                getString(R.string.wallpaper_desc),
                prefs.homeWallpaper() ? getString(R.string.wallpaper_adjust)
                        : getString(R.string.wallpaper_default),
                v -> showWallpaperSheet()));
        // v1.3.38 — the suggested-sites row moved OFF the home and lives here
        // (default off — the field report asked for it off the homepage;
        // on = the old behavior back).
        list.addView(rowWithSwitch("Suggested sites",
                "Chips of your most-visited sites under a sparse speed dial",
                prefs.b("suggestedSites", false),
                on -> {
                    prefs.set("suggestedSites", on);
                    Fx.homeGen++;   // v1.3.53: the home rebuilds — never a browser recreate (tab reloads)
                }));
        // v1.3.92 — THE HANDEDNESS OF THE PAGED GRID (the field spec:
        // "with option to customise right or left for left handed and
        // right handed person"). Off = the first page sits on the LEFT and
        // new pages extend rightward (the right-handed default); on = the
        // first page sits on the RIGHT and they extend leftward, so the
        // thumb that holds the phone reaches page one.
        list.addView(rowWithSwitch("Icon pages right-to-left",
                "Left-handed layout: the first page sits on the right and pages extend leftward",
                prefs.b("sdPagesRtl", false),
                on -> {
                    prefs.set("sdPagesRtl", on);
                    Fx.homeGen++;
                }));
        // v1.3.79 — THE CUSTOMIZABLE START LOGO: Bolt mark / blank / custom
        // image (long-pressing the logo on the home page opens the same
        // picker)
        list.addView(rowWithChevron(getString(R.string.home_logo),
                "The mark above the search bar — pick another look or go blank",
                new String[]{"Bolt mark", "Blank", "Custom image"}[Math.max(0, Math.min(2,
                        prefs.homeLogo() == 2 ? 2 : prefs.homeLogo()))],
                v -> showHomeLogoOptions()));

        // ---- App icon (v1.3.79) ----
        list.addView(section(getString(R.string.app_icon)));
        list.addView(rowWithChevron(getString(R.string.app_icon),
                "The launcher icon — a few colorways; links and the running app are untouched",
                AppIconSwitch.NAMES[Math.max(0, Math.min(AppIconSwitch.NAMES.length - 1,
                        prefs.appIcon()))],
                v -> showAppIconOptions()));

        // ---- Darken websites ----
        list.addView(section(getString(R.string.dark_web_pages)));
        LinearLayout dwRow = rowWithSwitch(getString(R.string.dark_web_pages),
                getString(R.string.dark_web_desc), prefs.webDark(),
                on -> prefs.set("webDark", on));
        list.addView(dwRow);
        // v1.3.40 — PER-SITE DARK EXCEPTIONS MANAGER (round-4 #12): the
        // page-menu chip's backlog, one screen with bulk toggle + reset.
        LinearLayout exRow = new LinearLayout(this);
        exRow.setOrientation(LinearLayout.HORIZONTAL);
        exRow.setGravity(Gravity.CENTER_VERTICAL);
        exRow.setBackground(Fx.cardBg(this, 0));
        exRow.setPadding(Fx.dp(this, 16), Fx.dp(this, 13), Fx.dp(this, 16), Fx.dp(this, 13));
        LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(-1, -2);
        exLp.topMargin = Fx.dp(this, 4);
        exRow.setLayoutParams(exLp);
        LinearLayout exCol = new LinearLayout(this);
        exCol.setOrientation(LinearLayout.VERTICAL);
        TextView exT = new TextView(this);
        exT.setText("Per-site dark overrides");
        exT.setTextSize(16);
        exT.setTextColor(Fx.txt(this));
        exCol.addView(exT, new LinearLayout.LayoutParams(-1, -2));
        TextView exS = new TextView(this);
        int exN = com.bolt.browser.ui.SiteDarkActivity.count(this);
        exS.setText(exN == 0 ? "Manage the sites you forced dark/light from the page menu"
                : exN + " site" + (exN == 1 ? "" : "s") + " with an override — bulk toggle & reset");
        exS.setTextSize(13);
        exS.setTextColor(Fx.dim(this));
        exCol.addView(exS, new LinearLayout.LayoutParams(-1, -2));
        exRow.addView(exCol, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView exBadge = new TextView(this);
        if (exN > 0) {
            exBadge.setText(String.valueOf(exN));
            exBadge.setTextColor(Fx.accentText(this));
            exBadge.setTextSize(13);
            exBadge.setTypeface(com.bolt.browser.util.Ui.medium());
            exRow.addView(exBadge, new LinearLayout.LayoutParams(-2, -2));
        }
        ImageView exChev = new ImageView(this);
        exChev.setImageResource(R.drawable.ic_chevron);
        exChev.setColorFilter(Fx.dim(this));
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(Fx.dp(this, 18), Fx.dp(this, 18));
        chLp.leftMargin = Fx.dp(this, 6);
        exRow.addView(exChev, chLp);
        exRow.setOnClickListener(v ->
                com.bolt.browser.ui.SiteDarkActivity.launch(this));
        list.addView(exRow);

        // ---- Font size ----
        list.addView(section(getString(R.string.font_size)));
        final TextView fsVal = new TextView(this);
        fsVal.setText(prefs.fontSize() + "%");
        fsVal.setTextColor(Fx.accentText(this));
        fsVal.setGravity(Gravity.CENTER);
        list.addView(fsVal, new LinearLayout.LayoutParams(-1, -2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(60);
        sb.setProgress(prefs.fontSize() - 80);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int pr, boolean fromUser) {
                fsVal.setText((80 + pr) + "%");
                prefs.set("fontSize", 80 + pr);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        list.addView(sb, new LinearLayout.LayoutParams(-1, -2));
    }

    // ---------------------------------------------------------------- accent dots

    /** one accent choice: a colored dot (+ optional tiny caption). value is
     *  -1 Auto / -2 Wallpaper / >=0 a fixed swatch; marks the active pick
     *  with a contrast-aware ring (the old white ring was invisible on light
     *  backgrounds). */
    private View accentDot(final int value, int color, String caption) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        View dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        boolean active = prefs.accentIndex() == value;
        if (active) g.setStroke(Fx.dp(this, 3), Fx.txt(this));
        else g.setStroke(Math.max(1, Fx.dp(this, 1)), 0x33888888);
        dot.setBackground(g);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Fx.dp(this, 40), Fx.dp(this, 40));
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(dot, dlp);

        if (caption != null) {
            TextView t = new TextView(this);
            t.setText(caption);
            t.setTextSize(11);
            t.setTextColor(Fx.dim(this));
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    Fx.dp(this, 58), -2);
            tlp.topMargin = Fx.dp(this, 3);
            col.addView(t, tlp);
        } else {
            // keep rows aligned with the captioned dots
            View sp = new View(this);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(1, Fx.dp(this, 17));
            col.addView(sp, slp);
        }

        col.setOnClickListener(v -> {
            prefs.set("accent", value);
            Fx.skinGen++;
            recreate();     // re-accent the whole screen
        });
        return col;
    }

    private LinearLayout.LayoutParams dotLp(int unused) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Fx.dp(this, 10);
        return lp;
    }

    // ---------------------------------------------------------------- skin rows

    /** one selectable skin preset row: name + description + light&dark
     *  preview swatches + the active marker */
    private View skinRow(int idx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = Fx.dp(this, 14), pv = Fx.dp(this, 12);
        row.setPadding(ph, pv, ph, pv);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(SKINS[idx][0]);
        t.setTextSize(16);
        t.setTypeface(Ui.medium());
        t.setTextColor(Fx.txt(this));
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = new TextView(this);
        s.setText(SKINS[idx][1]);
        s.setTextSize(13);
        s.setTextColor(Fx.dim(this));
        col.addView(s, new LinearLayout.LayoutParams(-1, -2));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        // preview swatches: light pair, divider, dark pair, accent dot —
        // both variants visible at a glance
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        int[] sc = SWATCHES[idx];
        int[] colors = {sc[0], sc[1], sc[4], sc[2], sc[3], sc[5]};
        for (int i = 0; i < colors.length; i++) {
            if (i == 3) {  // thin divider between the light and dark halves
                View d = new View(this);
                GradientDrawable dg = new GradientDrawable();
                dg.setColor(0x00000000);
                d.setBackground(dg);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        Fx.dp(this, 4), Fx.dp(this, 1));
                dlp.leftMargin = Fx.dp(this, 3);
                dlp.rightMargin = Fx.dp(this, 3);
                sw.addView(d, dlp);
                continue;
            }
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(colors[i]);
            g.setStroke(Math.max(1, Fx.dp(this, 1)), 0x33888888);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Fx.dp(this, 13), Fx.dp(this, 13));
            lp.leftMargin = Fx.dp(this, 3);
            sw.addView(dot, lp);
        }
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));

        // active marker
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_check);
        mark.setColorFilter(prefs.skin() == idx ? Fx.accentText(this) : 0x00000000);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Fx.dp(this, 20), Fx.dp(this, 20));
        mlp.leftMargin = Fx.dp(this, 10);
        row.addView(mark, mlp);

        styleSkinRow(row, idx);
        return row;
    }

    /** the card treatment + active state of a skin row */
    private void styleSkinRow(View row, int idx) {
        row.setBackground(Fx.cardBg(this, 0));   // v1.3.35: per-skin radius
        float active = prefs.skin() == idx ? 1f : 0.72f;
        row.setAlpha(active);
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Fx.dp(this, 6);
        return lp;
    }

    private void styleChips(TextView[] chips, int active) {
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == active;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Fx.dp(this, 999));
            if (on) {
                g.setColor(Fx.accent(this));
                chips[i].setTextColor(Fx.onAccent(this));
            } else {
                g.setColor(Fx.surface2(this));
                chips[i].setTextColor(Fx.txt(this));
            }
            chips[i].setBackground(g);
        }
    }

    /** v1.3.79 — start-logo options (mirrors BrowserActivity's picker). */
    private void showHomeLogoOptions() {
        Ui.choiceSheet(this, getString(R.string.home_logo),
                new String[]{getString(R.string.home_logo_default),
                        getString(R.string.home_logo_blank),
                        getString(R.string.home_logo_custom)},
                -1, idx -> {
                    if (idx == 2) {
                        try {
                            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("image/*");
                            startActivityForResult(Intent.createChooser(i,
                                    getString(R.string.home_logo)), 1013);
                            return;
                        } catch (Throwable t) {
                            Fx.toast(this, getString(R.string.wallpaper_pick_failed));
                        }
                    }
                    prefs.setHomeLogo(idx);
                    Fx.homeGen++;
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1013 && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            // decode + persist on a worker, then flip the pref + rebuild
            final android.net.Uri uri = data.getData();
            new Thread(() -> {
                boolean ok = false;
                try {
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(
                            getContentResolver().openInputStream(uri));
                    if (bmp != null) {
                        int longE = Math.max(bmp.getWidth(), bmp.getHeight());
                        if (longE > 512) {
                            float sc = 512f / longE;
                            android.graphics.Bitmap sm = android.graphics.Bitmap.createScaledBitmap(bmp,
                                    Math.round(bmp.getWidth() * sc), Math.round(bmp.getHeight() * sc), true);
                            if (sm != bmp) bmp.recycle();
                            bmp = sm;
                        }
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(prefs.homeLogoFile());
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 92, fo);
                        try { fo.close(); } catch (Throwable ignored) {}
                        bmp.recycle();
                        ok = prefs.homeLogoFile().length() > 0;
                    }
                } catch (Throwable ignored) {}
                final boolean saved = ok;
                runOnUiThread(() -> {
                    if (saved) { prefs.setHomeLogo(2); Fx.homeGen++; }
                    else Fx.toast(this, getString(R.string.wallpaper_pick_failed));
                });
            }, "bolt-logo").start();
        }
    
        if (requestCode == 1013 && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            // v1.3.79 — the custom home logo landed (showHomeLogoOptions)
            final android.net.Uri uri = data.getData();
            new Thread(() -> {
                boolean ok = false;
                try {
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(
                            getContentResolver().openInputStream(uri));
                    if (bmp != null) {
                        int longE = Math.max(bmp.getWidth(), bmp.getHeight());
                        if (longE > 512) {
                            float sc = 512f / longE;
                            android.graphics.Bitmap sm = android.graphics.Bitmap.createScaledBitmap(bmp,
                                    Math.round(bmp.getWidth() * sc), Math.round(bmp.getHeight() * sc), true);
                            if (sm != bmp) bmp.recycle();
                            bmp = sm;
                        }
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(prefs.homeLogoFile());
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 92, fo);
                        try { fo.close(); } catch (Throwable ignored) {}
                        bmp.recycle();
                        ok = prefs.homeLogoFile().length() > 0;
                    }
                } catch (Throwable ignored) {}
                final boolean saved = ok;
                runOnUiThread(() -> {
                    if (saved) { prefs.setHomeLogo(2); Fx.homeGen++; }
                    else Fx.toast(AppearanceActivity.this, getString(R.string.wallpaper_pick_failed));
                });
            }, "bolt-logo").start();
        }}

    /** v1.3.79 — app icon colorways (activity-alias swap; see
     *  util/AppIconSwitch.kt for the mechanism and its limits). */
    private void showAppIconOptions() {
        Ui.choiceSheet(this, getString(R.string.app_icon), AppIconSwitch.NAMES,
                prefs.appIcon(), idx -> {
                    if (idx == prefs.appIcon()) return;
                    AppIconSwitch.apply(this, idx);
                    prefs.setAppIcon(idx);
                    Fx.toast(this, "Icon changed — if the launcher is slow to refresh, restart it");
                });
    }

    private TextView section(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(14);
        tv.setTypeface(Ui.medium());
        tv.setTextColor(Fx.dim(this));
        tv.setPadding(0, Fx.dp(this, 22), 0, Fx.dp(this, 10));
        return tv;
    }

    private LinearLayout rowWithSwitch(String title, String sub, boolean checked,
            Ui.OnToggle change) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Fx.cardBg(this, 0));
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 14), Fx.dp(this, 16), Fx.dp(this, 14));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Fx.dp(this, 4);
        row.setLayoutParams(rlp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(Fx.txt(this));
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(13);
            s.setTextColor(Fx.dim(this));
            col.addView(s, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        Ui.ModernSwitch sw = Ui.modernSwitch(this, checked, change);
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    // ------------------------------------------------ v1.3.37 rows + sheets

    /** a tappable row with a trailing value chip + chevron (the sub-screen /
     *  sheet-opening pattern). */
    private LinearLayout rowWithChevron(String title, String sub, String value,
            View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Fx.cardBg(this, 0));
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 14), Fx.dp(this, 16), Fx.dp(this, 14));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Fx.dp(this, 4);
        row.setLayoutParams(rlp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(Fx.txt(this));
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(13);
            s.setTextColor(Fx.dim(this));
            col.addView(s, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null) {
            TextView v = new TextView(this);
            v.setText(value);
            v.setTextColor(Fx.accentText(this));
            v.setTextSize(13);
            v.setTypeface(Ui.medium());
            v.setMaxLines(1);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(v, new LinearLayout.LayoutParams(-2, -2));
        }
        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setColorFilter(Fx.dim(this));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Fx.dp(this, 18), Fx.dp(this, 18));
        clp.leftMargin = Fx.dp(this, 6);
        row.addView(chev, clp);
        row.setOnClickListener(click);
        return row;
    }

    /** Start page wallpaper: Default / Choose image / Adjust (v1.3.38 — the
     *  crop + blur + dim studio; picker result = request 1008). */
    private void showWallpaperSheet() {
        Ui.choiceSheet(this, getString(R.string.wallpaper),
                new String[]{ getString(R.string.wallpaper_default),
                        "Built-in wallpapers",
                        getString(R.string.wallpaper_choose),
                        getString(R.string.wallpaper_adjust) },
                HomeWallpaper.active(this) ? 3 : 0, idx -> {
                    if (idx == 0) {
                        HomeWallpaper.remove(this, null);
                        recreate();      // refresh this row's value chip
                    } else if (idx == 1) {
                        com.bolt.browser.util.DefaultWallpapers.showPicker(this);
                    } else if (idx == 3) {
                        startActivity(new Intent(this, WallpaperAdjustActivity.class));
                    } else {
                        HomeWallpaper.choose(this, 1008);
                    }
                });
    }
}
