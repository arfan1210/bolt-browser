package com.bolt.browser.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.bolt.browser.R;
import com.bolt.browser.adblock.FilterEngine;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Ui;

import java.util.ArrayList;
import java.util.List;

/** One categorized settings page (driven by "cat" extra).
 *  v1.3.34: choice rows open modern bottom sheets (Ui.choiceSheet — grab
 *  handle, accent radio dots) instead of XP-era radio dialogs; rows carry
 *  section labels so long pages group visually. */
public class SettingsItemsActivity extends BaseListActivity {

    private String cat;

    @Override
    protected String title() {
        String c = cat == null ? "" : cat;
        switch (c) {
            case "general": return getString(R.string.cat_general);
            case "tabs": return getString(R.string.cat_tabs);
            case "privacy": return getString(R.string.cat_privacy);
            case "media": return getString(R.string.cat_media);
            case "scripts": return getString(R.string.cat_scripts);
            default: return getString(R.string.cat_advanced);
        }
    }

    @Override
    protected void build() {
        cat = getIntent().getStringExtra(DeepSettingsActivity.CAT);
        if (cat == null) cat = "general";
        List<Row> rows = new ArrayList<>();
        switch (cat) {
            case "general": buildGeneral(rows); break;
            case "tabs": buildTabs(rows); break;
            case "privacy": buildPrivacy(rows); break;
            case "media": buildMedia(rows); break;
            case "scripts": buildScripts(rows); break;
            default: buildAdvanced(rows); break;
        }
        setRows(rows);
    }

    /** v1.3.31 — tabs switcher management (Settings > Tabs): the same
     *  preferences the tabs screen's 3-dot menu exposes, in settings form.
     *  v1.3.34 — the choosers are modern bottom sheets. v1.3.47 — the SORT
     *  row is removed (manual order only, see Prefs.tabsSort). */
    private void buildTabs(List<Row> rows) {
        final String[] groupPos = {getString(R.string.groups_on_top),
                getString(R.string.groups_in_order)};
        rows.add(choice(R.drawable.ic_folder, getString(R.string.groups_position),
                groupPos[prefs.groupsOnTop() ? 0 : 1], () ->
                Ui.choiceSheet(this, getString(R.string.groups_position), groupPos,
                        prefs.groupsOnTop() ? 0 : 1, w -> {
                    // v1.4.5 — the projection law, the settings door too:
                    // on-top -> follow-order MATERIALIZES the on-top visual
                    // into tm.tabs (folders stay at the top of the follow
                    // render; nothing jumps or reads as vanished); the
                    // reverse switch stays a pure view change. See
                    // TabsActivity.pickGroups for the law.
                    boolean wasOnTop = prefs.groupsOnTop();
                    if (wasOnTop && w == 1) {
                        try { com.bolt.browser.core.TabManager.get(this)
                                .materializeOnTopOrder(); } catch (Throwable ignored) {}
                    }
                    prefs.set("groupsOnTop", w == 0);
                    // v1.4.3 — one memory, two views: both placements derive
                    // the folder order from tm.tabs (first-member positions),
                    // so the toggle is a pure view change — no sync
                    refresh();
                })));
        final String[] views = {getString(R.string.view_cards), getString(R.string.view_list)};
        rows.add(choice(R.drawable.ic_tabcount, getString(R.string.tab_view),
                views[prefs.tabsAsCards() ? 0 : 1], () ->
                Ui.choiceSheet(this, getString(R.string.tab_view), views,
                        prefs.tabsAsCards() ? 0 : 1, w -> {
                    prefs.set("tabsAsCards", w == 0);
                    refresh();
                })));
        // v1.3.38 — TAB SLEEP (menu item 7): background tabs idle past this
        // many minutes lose their renderer (RAM back to the system); the tab
        // keeps url + scroll position + thumbnail and reloads when selected.
        final String[] sleeps = {"Off", "5 min", "15 min", "30 min", "1 h"};
        final int[] vals = {0, 5, 15, 30, 60};
        int cur = com.bolt.browser.util.TabSleep.minutes(this);
        int selS = cur <= 0 ? 0 : cur <= 5 ? 1 : cur <= 15 ? 2 : cur <= 30 ? 3 : 4;
        rows.add(choice(R.drawable.ic_moon, "Sleep background tabs",
                sleeps[selS], () ->
                Ui.choiceSheet(this, "Sleep background tabs after", sleeps, selS, w -> {
                    prefs.set("sleepAfter", vals[w]);
                    refresh();
                })));
        // v1.3.39 — TAB TRASH BIN (menu-3 #7, "default on, turn off-able in
        // deep settings"): closed tabs rest 7 days in the bin.
        Row trash = toggle(R.drawable.ic_trash, "Recently closed tabs",
                com.bolt.browser.util.TabTrash.enabled(this), (b, on) -> {
                    prefs.set("tabTrash", on);
                    refresh();
                });
        trash.sub = "Closed tabs wait 7 days in a trash list you can restore from";
        rows.add(trash);
        if (com.bolt.browser.util.TabTrash.enabled(this)) {
            rows.add(choice(R.drawable.ic_trash, "Open trash bin",
                    com.bolt.browser.util.TabTrash.list(this).length() + " in bin", () ->
                            startActivity(new Intent(this, com.bolt.browser.ui.TabTrashActivity.class))));
        }
    }

    private void buildGeneral(List<Row> rows) {
        String[] names = getResources().getStringArray(R.array.search_engine_names);
        Row se = choice(R.drawable.ic_search, getString(R.string.search_engine),
                names[Math.max(0, Math.min(names.length - 1, prefs.searchEngine()))], () ->
                Ui.choiceSheet(this, getString(R.string.search_engine), names,
                        prefs.searchEngine(), w -> {
                    prefs.set("searchEngine", w);
                    refresh();
                }));
        se.section = getString(R.string.cat_general);
        rows.add(se);
        rows.add(action(R.drawable.ic_home, getString(R.string.homepage),
                prefs.homepage().equals("bolt:home") ? "Default (Bolt home)" : prefs.homepage(), () -> {
                    final EditText et = new EditText(this);
                    et.setText(prefs.homepage());
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.homepage)
                            .setView(et)
                            .setPositiveButton(R.string.save, (d, w) -> {
                                prefs.set("homepage", et.getText().toString().trim().isEmpty()
                                        ? "bolt:home" : et.getText().toString().trim());
                                refresh();
                            })
                            .setNegativeButton(R.string.cancel, null).show();
                }));
        Row rt = toggle(R.drawable.ic_tabcount, getString(R.string.restore_tabs),
                prefs.restoreTabs(), (b, on) -> prefs.set("restoreTabs", on));
        rt.section = getString(R.string.tabs);
        rows.add(rt);
        rows.add(toggle(R.drawable.ic_close, getString(R.string.confirm_tab_close),
                prefs.b("confirmClose", false), (b, on) -> prefs.set("confirmClose", on)));
        rows.add(toggle(R.drawable.ic_plus, getString(R.string.new_tab_background),
                prefs.b("newTabBg", false), (b, on) -> prefs.set("newTabBg", on)));
        // v1.3.39 — CUSTOM SEARCH ENGINES + BANGS (menu-3 #6, "in deep
        // settings only"): add engines ("!w cats" routes through them).
        Row ce = choice(R.drawable.ic_search, "Custom search engines",
                com.bolt.browser.util.Engines.list(this).size() + " added · !bangs", () ->
                        startActivity(new Intent(this, com.bolt.browser.ui.EnginesActivity.class)));
        ce.sub = "Add your own engines and type their !bang in the address bar";
        rows.add(ce);
        // v1.4.2 — LOCAL-FIRST SUGGESTIONS (the field ask: "when searching,
        // keep the options from bookmarks and shortcuts as a higher priority
        // recommendation when typing to search. Make a toggle to turn it on
        // or off in the deep settings"). ON: bookmarks + home-screen
        // shortcuts rank above history in the omnibox; OFF: the classic
        // order (history first, no shortcut rows).
        Row lf = toggle(R.drawable.ic_bookmark, getString(R.string.suggest_local_first),
                prefs.suggestLocalFirst(), (b, on) -> prefs.set("suggestLocalFirst", on));
        lf.sub = "While typing in the address bar, your bookmarks and home-screen shortcuts rank above history";
        rows.add(lf);
        // v1.3.40 — WORKSPACES (field task 15): the session manager + the
        // backup "profile" merged into ONE Chrome-style concept — named
        // spaces with their own tab sets. Old sessions.json migrates on
        // first use; the live session stays single and last-tab recovery is
        // untouched (see util/Workspaces.kt).
        Row ws = toggle(R.drawable.ic_person, "Workspaces",
                com.bolt.browser.util.Workspaces.enabled(this), (b, on) -> {
                    prefs.set("workspaces", on);
                    refresh();
                });
        ws.sub = "Named spaces for your tab sets — Study, Entertainment, Work…";
        rows.add(ws);
        if (com.bolt.browser.util.Workspaces.enabled(this)) {
            int live = com.bolt.browser.util.Workspaces.list(this).size();
            rows.add(choice(R.drawable.ic_person, "Open workspaces",
                    live + " spaces · live: " + com.bolt.browser.util.Workspaces.active(this), () ->
                            startActivity(new Intent(this, com.bolt.browser.ui.WorkspacesActivity.class))));
        }
        // v1.3.40 (chunk 3) — BACKUP DISCOVERABILITY: also a row in deep
        // General AND indexed by both settings searches.
        Row bk = choice(R.drawable.ic_backup, getString(R.string.backup_export),
                "Choose what to back up", () ->
                        startActivity(new Intent(this, com.bolt.browser.ui.BackupActivity.class)));
        bk.sub = "Pick what a backup includes — bookmarks, history, tabs, passwords…";
        rows.add(bk);
    }

    private void buildPrivacy(List<Row> rows) {
        Row master = toggle(R.drawable.ic_filter, getString(R.string.adblock_master),
                prefs.adblockEnabled(), (b, on) -> {
                    prefs.setAdblock(on);
                    FilterEngine.warmAsync(this);
                });
        master.section = getString(R.string.cat_privacy);
        rows.add(master);
        rows.add(choice(R.drawable.ic_script, getString(R.string.filter_lists),
                getString(R.string.filter_lists_desc),
                () -> startActivity(new Intent(this, FilterListsActivity.class))));
        rows.add(toggle(R.drawable.ic_close, getString(R.string.popup_blocker),
                prefs.popupBlock(), (b, on) -> prefs.set("popupBlock", on)));
        // v1.3.40 — URL TRACKER-PARAMETER CLEANER (round-4 #4): global master
        // switch (default ON). The per-site override lives on the page menu.
        rows.add(toggle(R.drawable.ic_filter, "Clean tracking parameters",
                prefs.cleanTrackers(), (b, on) -> prefs.set("cleanTrackers", on)));
        rows.add(toggle(R.drawable.ic_shieldoff, getString(R.string.block_adult),
                prefs.blockAdult(), (b, on) -> prefs.set("blockAdult", on)));
        rows.add(toggle(R.drawable.ic_info, getString(R.string.do_not_track),
                prefs.dnt(), (b, on) -> prefs.set("dnt", on)));
        Row cd = action(R.drawable.ic_delete, getString(R.string.clear_browsing_data),
                null, this::clearDataDialog);
        cd.section = getString(R.string.clear_browsing_data);
        rows.add(cd);
        rows.add(action(R.drawable.ic_power, getString(R.string.clear_on_exit),
                null, this::exitChecklist));
        // v1.3.79 — THE STORAGE LAW'S NEW LEVER: "Deep clean on close"
        // (DEFAULT ON): every close path (swipe-away included — not just the
        // power-menu exit) clears Local Storage + IndexedDB + service
        // workers; cookies stay, so most sign-ins survive. This is what
        // pulls "app data after closing" from 46 MB toward the 20 MB law.
        Row deep = toggle(R.drawable.ic_trash, getString(R.string.deep_clean_exit),
                prefs.deepCleanExit(), (b, on) -> prefs.setDeepCleanExit(on));
        deep.sub = getString(R.string.deep_clean_exit_desc);
        rows.add(deep);
        // v1.3.82 — THE STORAGE METER: the storage-law saga ran blind from
        // the field side ("the size is still 50mb" arrived with no diag
        // line attached, and every round-trip cost a release). This row
        // shows the on-disk truth wherever the user is — data total,
        // cache portion, APK, and the biggest WebView/filesDir growers —
        // and "Clean now" runs the same close-out sweep an app close runs.
        Row st = action(R.drawable.ic_dl_arch, "Storage", null, this::showStorageReport);
        st.sub = "See what uses space · clean temporary data now";
        rows.add(st);
        // v1.3.38 — APP LOCK (menu item 8, opt-in as requested): the device
        // credential gates launching the browser; the re-lock row only
        // matters when the lock is on.
        Row lock = toggle(R.drawable.ic_lock, "App lock",
                prefs.b("appLock", false), (b, on) -> {
                    if (on) {
                        android.app.KeyguardManager km = (android.app.KeyguardManager)
                                getSystemService(KEYGUARD_SERVICE);
                        if (km != null && !km.isDeviceSecure()) {
                            Fx.toast(this, "Set a screen lock (PIN / pattern / biometrics) first");
                            refresh();
                            return;
                        }
                    }
                    prefs.set("appLock", on);
                    refresh();
                });
        lock.sub = "Ask the device credential when Bolt starts";
        rows.add(lock);
        if (prefs.b("appLock", false)) {
            rows.add(toggle(R.drawable.ic_moon, "Lock when backgrounded",
                    com.bolt.browser.util.AppLock.relockInBackground(this), (b, on) ->
                            prefs.set("appLockBg", on)));
        }
        // v1.3.39 — vault discoverability (field report: "no password vault
        // either"): the vault gets its deep-settings home next to the lock.
        Row vault = choice(R.drawable.ic_lock, "Saved passwords",
                com.bolt.browser.util.PasswordVault.count(this) + " logins · encrypted", () ->
                        startActivity(new Intent(this, com.bolt.browser.ui.PasswordVaultActivity.class)));
        vault.sub = "The login vault behind the device credential";
        rows.add(vault);
        // v1.3.39 — PROFILES (menu-3 #13).
        Row prof = choice(R.drawable.ic_person, "Address & card profiles",
                com.bolt.browser.util.Profiles.count(this) + " profiles · encrypted", () ->
                        startActivity(new Intent(this, com.bolt.browser.ui.ProfilesActivity.class)));
        prof.sub = "Encrypted autofill profiles offered on matching forms";
        rows.add(prof);
    }

    /** v1.3.82 — the Storage row's dialog: measures on a worker thread
     *  (a full data-dir walk is blocking IO), fills the live report in,
     *  and "Clean now" runs the close-out sweep (CacheGuard.exit — cache
     *  subtrees always, the site-data diet behind its toggle). */
    private void showStorageReport() {
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Storage")
                .setMessage("Measuring…")
                .setPositiveButton("Clean now", null)
                .setNegativeButton(R.string.cancel, null)
                .show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            com.bolt.browser.util.CacheGuard.exit(this);
            Fx.toast(this, "Cleaning temporary data…");
            d.dismiss();
        });
        new Thread(() -> {
            final String rep;
            try {
                rep = com.bolt.browser.util.CacheGuard.report(this);
            } catch (Throwable t) {
                return;
            }
            runOnUiThread(() -> {
                try {
                    if (d.isShowing()) d.setMessage(rep);
                } catch (Throwable ignored) {}
            });
        }, "bolt-storage-report").start();
    }

    private void clearDataDialog() {
        final boolean[] checks = {false, false, false, false, false};
        String[] names = {getString(R.string.clear_cache), getString(R.string.clear_history),
                getString(R.string.clear_cookies), getString(R.string.clear_storage),
                getString(R.string.clear_passwords)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_browsing_data)
                .setMultiChoiceItems(names, checks, (d, w, on) -> checks[w] = on)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    // real wipe (same process as the browser activity: the live
                    // WebViews are reachable through the shared TabManager).
                    // v1.3.13: the physical cache delete now reports how many
                    // bytes it actually freed — visible proof the app cache
                    // really is being emptied.
                    Fx.toast(this, getString(R.string.cleared));
                    com.bolt.browser.util.DataWipe.wipeAll(this,
                            checks[0], checks[1], checks[2], checks[3], checks[4],
                            freed -> runOnUiThread(() -> {
                                if (freed > 0) {
                                    Fx.toast(this, getString(R.string.cleared)
                                            + " · " + (freed / (1024 * 1024)) + " MB");
                                }
                            }));
                    // live pages must reload, otherwise they re-persist their
                    // in-memory login state right after the wipe
                    if (checks[2] || checks[3]) {
                        new android.os.Handler(getMainLooper()).postDelayed(
                                () -> com.bolt.browser.util.DataWipe.reloadAllTabs(this), 450);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void exitChecklist() {
        final boolean[] checks = {prefs.clearExit("cache"), prefs.clearExit("history"),
                prefs.clearExit("cookies"), prefs.clearExit("storage"), prefs.clearExit("passwords")};
        String[] names = {getString(R.string.clear_cache), getString(R.string.clear_history),
                getString(R.string.clear_cookies), getString(R.string.clear_storage),
                getString(R.string.clear_passwords)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_on_exit)
                .setMultiChoiceItems(names, checks, (d, w, on) -> checks[w] = on)
                .setPositiveButton(R.string.save, (d, w) -> {
                    prefs.set("exit_cache", checks[0]);
                    prefs.set("exit_history", checks[1]);
                    prefs.set("exit_cookies", checks[2]);
                    prefs.set("exit_storage", checks[3]);
                    prefs.set("exit_passwords", checks[4]);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void buildMedia(List<Row> rows) {
        rows.add(toggle(R.drawable.ic_volume, getString(R.string.background_play),
                prefs.bgPlay(), (b, on) -> {
                    prefs.set("bgPlay", on);
                    // v1.3.11: push into live tabs IMMEDIATELY. __boltSetMedia now
                    // installs (or fully removes) the background spoof on
                    // already-open pages — previously the toggle only wrote the
                    // preference, so a YouTube tab opened before enabling the
                    // setting had NO spoof protection until a manual reload.
                    String js = "window.__boltSetMedia && window.__boltSetMedia('"
                            + com.bolt.browser.media.MediaHook.mediaSettingsJson(prefs)
                                    .replace("'", "\\'") + "')";
                    com.bolt.browser.core.TabManager tmx =
                            com.bolt.browser.core.TabManager.get(this);
                    for (com.bolt.browser.core.Tab t : tmx.tabs) {
                        if (t.web != null) {
                            try { t.web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                        }
                    }
                }));
        // v1.3.13: the "media notification" toggle is gone — the media
        // notification is now intrinsic to playback (it IS the background
        // playback engine and its control surface, Opera-style: it appears
        // when media starts, stays while paused until swiped, and controls
        // playback in-app / backgrounded / closed). A toggle that lied about
        // turning that off was worse than no toggle.
        Row booster = toggle(R.drawable.ic_volume, getString(R.string.media_booster),
                prefs.mediaBooster(), (b, on) -> {
                    prefs.setMediaBooster(on);
                    // push the new settings into live tabs immediately
                    String js = "window.__boltSetMedia && window.__boltSetMedia('"
                            + com.bolt.browser.media.MediaHook.mediaSettingsJson(prefs)
                                    .replace("'", "\\'") + "')";
                    com.bolt.browser.core.TabManager tmx =
                            com.bolt.browser.core.TabManager.get(this);
                    for (com.bolt.browser.core.Tab t : tmx.tabs) {
                        if (t.web != null) {
                            try { t.web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                        }
                    }
                });
        booster.sub = getString(R.string.media_booster_desc);
        rows.add(booster);
        // v1.3.79 — THE SILENCE SKIPPER (Settings default; the media FAB's
        // video-control sheet carries the in-session switch)
        Row skip = toggle(R.drawable.ic_next, getString(R.string.skip_silence),
                prefs.mediaSkip(), (b, on) -> {
                    prefs.setMediaSkip(on);
                    String js = "window.__boltSetMedia && window.__boltSetMedia('"
                            + com.bolt.browser.media.MediaHook.mediaSettingsJson(prefs)
                                    .replace("'", "\'") + "')";
                    com.bolt.browser.core.TabManager tmx =
                            com.bolt.browser.core.TabManager.get(this);
                    for (com.bolt.browser.core.Tab t : tmx.tabs) {
                        if (t.web != null) {
                            try { t.web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                        }
                    }
                });
        skip.sub = getString(R.string.skip_silence_desc);
        rows.add(skip);
        // v1.3.87 — THE SKIP TUNING (the field report: "give control options
        // for all these settings like relative 1x-8x speed for the silence,
        // start after x ms of silence appearing, stop before x ms of
        // non-silence part, also add the unique feature like ignore
        // silences for less than x ms"). One sheet (SkipTune.kt), shared
        // with the media FAB's video-control sheet; knobs apply live.
        rows.add(action(R.drawable.ic_tune, getString(R.string.skip_tune_row),
                prefs.skipFactor() + "x · start " + prefs.skipAttack() + " ms · rewind "
                        + prefs.skipRelease() + " ms · ignore <" + prefs.skipMinSilence() + " ms",
                () -> com.bolt.browser.util.SkipTune.sheet(this, prefs, () -> {
                    String js = "window.__boltSetMedia && window.__boltSetMedia('"
                            + com.bolt.browser.media.MediaHook.mediaSettingsJson(prefs)
                                    .replace("'", "\\'") + "')";
                    com.bolt.browser.core.TabManager tmx =
                            com.bolt.browser.core.TabManager.get(this);
                    for (com.bolt.browser.core.Tab t : tmx.tabs) {
                        if (t.web != null) {
                            try { t.web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                        }
                    }
                    refresh();
                })));
        rows.add(toggle(R.drawable.ic_speak, getString(R.string.vocal_boost),
                prefs.vocalOn(), (b, on) -> prefs.setVocalOn(on)));
        // v1.3.27 — background survival kit: the battery-optimization
        // whitelist + the OEM killer countermeasures (autostart deep links,
        // per-manufacturer instructions), one tap away. Also auto-offered
        // once, right after a session was killed while playing (the
        // heartbeat-evidence dialog from BrowserActivity.onResume).
        rows.add(action(R.drawable.ic_power, getString(R.string.background_survival),
                com.bolt.browser.util.BackgroundSurvival.statusLine(this), () ->
                        com.bolt.browser.util.BackgroundSurvival.showOffer(this, null)));
        String[] vols = {"200%", "300%", "400%"};
        int mv = prefs.i("maxVolume", 400);
        int sel = mv <= 200 ? 0 : mv <= 300 ? 1 : 2;
        rows.add(choice(R.drawable.ic_volume, getString(R.string.max_volume), mv + "%", () ->
                Ui.choiceSheet(this, getString(R.string.max_volume), vols, sel, w -> {
                    prefs.set("maxVolume", new int[]{200, 300, 400}[w]);
                    refresh();
                })));
        // v1.3.38 — AUTO PICTURE-IN-PICTURE (menu item 9): leaving via home
        // with a FULLSCREEN VIDEO up floats the video. Manual entry always
        // lives in the page menu.
        // v1.3.41: video-only — plain media never shrinks the whole site into
        // the window any more (v1.3.40 field report); it keeps playing as
        // background audio instead.
        Row ap = toggle(R.drawable.ic_arrow_up, "Auto picture-in-picture",
                prefs.b("autoPip", true), (b, on) -> prefs.set("autoPip", on));
        ap.sub = "Swiping home during a fullscreen video floats the video. Other media keeps playing as background audio.";
        rows.add(ap);
    }

    private void buildScripts(List<Row> rows) {
        rows.add(toggle(R.drawable.ic_script, getString(R.string.scripts_enabled),
                prefs.b("userscripts", true), (b, on) -> prefs.set("userscripts", on)));
        rows.add(choice(R.drawable.ic_code, getString(R.string.userscripts),
                "Manage userscripts", () ->
                        startActivity(new Intent(this, com.bolt.browser.scripts.UserscriptsActivity.class))));
    }

    private void buildAdvanced(List<Row> rows) {
        String[] names = getResources().getStringArray(R.array.ua_names);
        Row ua = choice(R.drawable.ic_mobile, getString(R.string.global_ua),
                names[Math.max(0, Math.min(names.length - 1, prefs.uaIndex()))], () ->
                Ui.choiceSheet(this, getString(R.string.global_ua), names, prefs.uaIndex(), w -> {
                    prefs.set("ua", w);
                    refresh();
                }));
        ua.section = getString(R.string.cat_advanced);
        rows.add(ua);
        rows.add(toggle(R.drawable.ic_sun, getString(R.string.keep_screen_on),
                prefs.b("keepScreenOn", false), (b, on) -> prefs.set("keepScreenOn", on)));
        // v1.3.21 — self-service debugging surface. The three rows that turn
        // "it crashed" / "passkeys don't work" field reports into diagnoses:
        // live passkey capability probe (+ actionable guidance), one-tap
        // diagnostics export, and the web-engine cache repair.
        rows.add(choice(R.drawable.ic_lock, "Passkey status",
                com.bolt.browser.util.WebAuthnHelper.shortStatus(this), () ->
                new AlertDialog.Builder(this)
                        .setTitle("Passkey status")
                        .setMessage(com.bolt.browser.util.WebAuthnHelper.statusReport(this))
                        .setPositiveButton(R.string.ok, null)
                        .show()));
        // v1.3.24: stored passkeys (BoltAuthenticator) — visibility + local wipe.
        // Sites manage their own side; this removes the DEVICE copy (and its
        // AndroidKeyStore keys) when a passkey was created by mistake or a
        // site-side removal left a stale discoverable credential behind.
        int pkCount = com.bolt.browser.util.BoltAuthenticator.count(this);
        rows.add(action(R.drawable.ic_lock, "Stored passkeys",
                pkCount == 0 ? "None stored yet — create one from a site's passkey option"
                        : (pkCount + " on this device — tap to remove them all"), () -> {
                    if (pkCount == 0) {
                        Fx.toast(this, "No stored passkeys");
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Remove stored passkeys?")
                            .setMessage("Removes all " + pkCount + " passkey"
                                    + (pkCount == 1 ? "" : "s")
                                    + " stored on this device (site accounts are not"
                                    + " affected — sign in with password/2FA and remove"
                                    + " the passkey there too if needed).")
                            .setPositiveButton(R.string.ok, (d, w) -> {
                                new Thread(() -> {
                                    int n = com.bolt.browser.util.BoltAuthenticator.clearAll(this);
                                    runOnUiThread(() -> {
                                        Fx.toast(this, n + " passkey"
                                                + (n == 1 ? "" : "s") + " removed");
                                        refresh();
                                    });
                                }, "bolt-pkwipe").start();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                }));
        rows.add(action(R.drawable.ic_info, "Copy diagnostics",
                "Renderer restarts, errors and web view version — paste it in chat with crash reports", () -> {
                    try {
                        String log = com.bolt.browser.util.Diag.tail(this, 20000);
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("bolt-diag",
                                log.isEmpty() ? "(no diagnostics recorded)" : log));
                        Fx.toast(this, "Diagnostics copied to clipboard");
                    } catch (Throwable t) {
                        try { Fx.toast(this, "Copy failed"); } catch (Throwable ignored) {}
                    }
                }));
        rows.add(action(R.drawable.ic_fire, "Repair web engine",
                "Clears cached page code — fixes pages that keep crashing", () ->
                new Thread(() -> {
                    long freed = 0;
                    try { freed = com.bolt.browser.util.DataWipe.wipeRendererCaches(this); }
                    catch (Throwable ignored) {}
                    final long f = freed;
                    runOnUiThread(() -> Fx.toast(this,
                            f > 1024 * 1024 ? "Web engine caches cleared ("
                                    + (f / 1024 / 1024) + " MB)"
                                    : "Web engine caches cleared"));
                }, "bolt-repair").start()));
        // v1.3.39 — FULLSCREEN NOTCH toggle (the fullscreen card itself is on
        // the surface settings home).
        Row notch = toggle(R.drawable.ic_expand, "Fullscreen uses notch area",
                com.bolt.browser.util.Fullscreen.notch(this), (b, on) -> {
                    prefs.set("fullscreenNotch", on);
                    Fx.skinGen++;     // the browser re-applies on next resume
                });
        notch.sub = "Draw the page into the camera cutout for true edge-to-edge";
        notch.section = "Display";
        rows.add(notch);
        // v1.3.39 — NIGHT SCREEN FILTER (menu-3 #9): intensity + auto
        // schedule ("configure automatic on and off with set up times").
        Row nOn = toggle(R.drawable.ic_moon, "Night screen",
                com.bolt.browser.util.NightScreen.scheduled(this), (b, on) -> {
                    prefs.set("nightOn", on);
                    prefs.set("nightAuto", false);
                    // v1.3.50 (field: "when I turn off or on night screen, I
                    // want live instant change without switching menus"):
                    // re-apply to every live window NOW — the settings toggle
                    // used to wait for the next resume/menu switch.
                    try { com.bolt.browser.util.NightScreen.recheck(this); }
                    catch (Throwable ignored) {}
                    refresh();
                });
        nOn.sub = "A warm amber veil over the page for night reading";
        rows.add(nOn);
        Row nAuto = toggle(R.drawable.ic_moon, "Auto on/off schedule",
                prefs.b("nightAuto", false), (b, on) -> {
                    prefs.set("nightAuto", on);
                    if (on) prefs.set("nightOn", true);
                    // v1.3.50: the schedule flip changes what scheduled()
                    // resolves to RIGHT NOW — apply it live, not on resume.
                    try { com.bolt.browser.util.NightScreen.recheck(this); }
                    catch (Throwable ignored) {}
                    refresh();
                });
        nAuto.sub = "Turns the filter on between the times below";
        rows.add(nAuto);
        if (prefs.b("nightAuto", false)) {
            rows.add(choice(R.drawable.ic_moon, "Turn on at",
                    com.bolt.browser.util.NightScreen.fmt(
                            prefs.i("nightFromM", 22 * 60)), () -> pickTime(true)));
            rows.add(choice(R.drawable.ic_moon, "Turn off at",
                    com.bolt.browser.util.NightScreen.fmt(
                            prefs.i("nightToM", 6 * 60)), () -> pickTime(false)));
        }
        rows.add(choice(R.drawable.ic_moon, "Night intensity",
                prefs.i("nightIntensity", 55) + "%", () -> {
                    final LinearLayout lay = new LinearLayout(this);
                    lay.setOrientation(LinearLayout.VERTICAL);
                    final TextView val = new TextView(this);
                    val.setText(prefs.i("nightIntensity", 55) + "%");
                    val.setGravity(Gravity.CENTER);
                    lay.addView(val, new LinearLayout.LayoutParams(-1, -2));
                    SeekBar sb = new SeekBar(this);
                    sb.setMax(100);
                    sb.setProgress(prefs.i("nightIntensity", 55));
                    sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(SeekBar s, int pr, boolean fu) {
                            val.setText(pr + "%");
                            prefs.set("nightIntensity", pr);
                            // v1.3.48 — LIVE PREVIEW: every live veil (this
                            // dialog's included) recolors as the thumb moves,
                            // even when the schedule says OFF.
                            com.bolt.browser.util.NightScreen.preview(pr);
                        }
                        public void onStartTrackingTouch(SeekBar s) {}
                        public void onStopTrackingTouch(SeekBar s) {}
                    });
                    lay.addView(sb, new LinearLayout.LayoutParams(-1, -2));
                    // v1.3.48 — the dialog tints itself (uniform night screen)
                    // and reverts the preview when it closes.
                    AlertDialog ad = new AlertDialog.Builder(this).setTitle("Night intensity")
                            .setView(lay)
                            .setPositiveButton(R.string.ok, null)
                            .setOnDismissListener(d ->
                                    com.bolt.browser.util.NightScreen.endPreview())
                            .show();
                    com.bolt.browser.util.NightScreen.attachDialog(ad);
                }));
        // v1.3.49 → v1.3.50 — IN-APP BRIGHTNESS (field: "allow lowering overall
        // in app brightness throughout the app using a slider under the night
        // screen"; v1.3.50: "the slide is not 50 at middle, so looks
        // ununiform and ugly" + "only activate when night mode is on"):
        // a black veil on EVERY window (the same per-decor system the night
        // screen uses), a plain 0..100 scale with the default 50 resting in
        // the MIDDLE, live preview while the thumb moves — and the veil only
        // exists while the night screen is on (zero cost / zero effect
        // otherwise).
        Row bright = choice(R.drawable.ic_sun, "Brightness",
                prefs.i("appBrightness", 50) + "%", () -> {
                    final LinearLayout lay = new LinearLayout(this);
                    lay.setOrientation(LinearLayout.VERTICAL);
                    final TextView val = new TextView(this);
                    val.setText(prefs.i("appBrightness", 50) + "%");
                    val.setGravity(Gravity.CENTER);
                    lay.addView(val, new LinearLayout.LayoutParams(-1, -2));
                    SeekBar sb = new SeekBar(this);
                    sb.setMax(100);   // v1.3.50: plain 0..100 — 50 sits in the middle
                    sb.setProgress(prefs.i("appBrightness", 50));
                    sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(SeekBar s, int pr, boolean fu) {
                            val.setText(pr + "%");
                            prefs.set("appBrightness", pr);
                            // LIVE PREVIEW: v1.3.50's registry-backed
                            // refreshAll creates/recolors/removes the dim veil
                            // on EVERY live window as the thumb moves (the
                            // v1.3.49 map-only iteration had nothing to
                            // recolor while no veil existed — "no live
                            // feedback"). Night-gated: shows while the night
                            // screen is on, matching the stored effect.
                            com.bolt.browser.util.NightScreen.previewBrightness(pr);
                        }
                        public void onStartTrackingTouch(SeekBar s) {}
                        public void onStopTrackingTouch(SeekBar s) {}
                    });
                    lay.addView(sb, new LinearLayout.LayoutParams(-1, -2));
                    AlertDialog ad = new AlertDialog.Builder(this).setTitle("Brightness")
                            .setView(lay)
                            .setPositiveButton(R.string.ok, null)
                            .setOnDismissListener(d ->
                                    com.bolt.browser.util.NightScreen.endBrightnessPreview())
                            .show();
                    com.bolt.browser.util.NightScreen.attachDialog(ad);
                });
        bright.sub = "Dims the whole app — only while Night screen is on";
        rows.add(bright);
        // v1.3.39 — DATA SAVER (menu-3 #10, default off).
        Row ds = toggle(R.drawable.ic_download, "Data saver",
                com.bolt.browser.util.DataSaver.enabled(this), (b, on) -> {
                    prefs.set("dataSaver", on);
                    if (!on) com.bolt.browser.util.DataSaver.reset(this);
                    refresh();
                });
        ds.sub = "Block images while on a metered connection";
        rows.add(ds);
        if (com.bolt.browser.util.DataSaver.enabled(this)) {
            rows.add(toggle(R.drawable.ic_download, "Also on unmetered (Wi-Fi)",
                    prefs.b("dataSaverForce", false), (b, on) -> prefs.set("dataSaverForce", on)));
            Row st = action(R.drawable.ic_download, "Blocked so far",
                    com.bolt.browser.util.DataSaver.stats(this), () ->
                            Fx.toast(this, "Requests blocked since the toggle went on: "
                                    + com.bolt.browser.util.DataSaver.stats(this)));
            st.sub = "Tap to read the counter again (resets when the saver turns off)";
            rows.add(st);
        }
        Row info = new Row();
        info.icon = R.drawable.ic_info;
        info.title = getString(R.string.data_storage);
        info.sub = "User data: JSON files in Android/data/" + getPackageName() + "/files\n"
                + "Temp data: app cache directory";
        rows.add(info);
    }

    /** v1.3.39: HH:MM picker for the night-screen schedule (no dependency —
     *  two number pickers in a dialog). */
    private void pickTime(final boolean isFrom) {
        int def = isFrom ? prefs.i("nightFromM", 22 * 60) : prefs.i("nightToM", 6 * 60);
        final LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        final android.widget.NumberPicker h = new android.widget.NumberPicker(this);
        final android.widget.NumberPicker m = new android.widget.NumberPicker(this);
        h.setMinValue(0); h.setMaxValue(23); h.setValue(def / 60);
        m.setMinValue(0); m.setMaxValue(59); m.setValue(def % 60);
        m.setWrapSelectorWheel(true);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(h, new LinearLayout.LayoutParams(-2, -2));
        row.addView(m, new LinearLayout.LayoutParams(-2, -2));
        lay.addView(row, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(isFrom ? "Night screen turns ON at" : "Night screen turns OFF at")
                .setView(lay)
                .setPositiveButton(R.string.save, (d, w) -> {
                    int min = h.getValue() * 60 + m.getValue();
                    prefs.set(isFrom ? "nightFromM" : "nightToM", min);
                    prefs.set("nightFrom", com.bolt.browser.util.NightScreen.fmt(min));
                    prefs.set("nightTo", com.bolt.browser.util.NightScreen.fmt(
                            prefs.i("nightToM", 6 * 60)));
                    // v1.3.50: a new schedule boundary can flip the night
                    // state THIS MINUTE — apply it live.
                    try { com.bolt.browser.util.NightScreen.recheck(this); }
                    catch (Throwable ignored) {}
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // helpers
    private Row toggle(int icon, String title, boolean checked,
            android.widget.CompoundButton.OnCheckedChangeListener change) {
        Row r = new Row();
        r.icon = icon;
        r.title = title;
        r.switchRow = true;
        r.checked = checked;
        r.switchChange = change;
        return r;
    }

    private Row choice(int icon, String title, String value, Runnable run) {
        Row r = new Row();
        r.icon = icon;
        r.title = title;
        r.value = value;
        r.click = v -> run.run();
        return r;
    }

    private Row action(int icon, String title, String sub, Runnable run) {
        Row r = new Row();
        r.icon = icon;
        r.title = title;
        r.sub = sub;
        r.click = v -> run.run();
        return r;
    }
}
