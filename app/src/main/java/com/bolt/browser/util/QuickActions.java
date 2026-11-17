package com.bolt.browser.util;

import android.content.Context;

import com.bolt.browser.R;

/**
 * v1.3.37 — the vocabulary of LONG-PRESS QUICK ACTIONS for the toolbar
 * buttons. The browser dispatches a code through
 * BrowserActivity.runQuickAction(code); Appearance → Interaction →
 * "Long-press toolbar buttons" remaps each button.
 *
 * Per-button ALLOWED lists (not one global list) keep every choice menu
 * short and sensible — the forward button has no "close tab", the settings
 * button has no "find in page". Defaults follow the familiar patterns:
 * hold BACK/FORWARD → the tab's own history (Chrome/Firefox), hold TABS →
 * new tab, hold HOME → bookmarks, hold RELOAD → hard reload (the
 * pre-existing behavior).
 */
public final class QuickActions {

    private QuickActions() {}

    // action codes
    public static final String NONE = "none";
    public static final String NEW_TAB = "new_tab";
    public static final String CLOSE_TAB = "close_tab";
    public static final String TAB_HISTORY = "tab_history";
    public static final String TABS = "tabs";
    public static final String HOME = "home";
    public static final String BOOKMARKS = "bookmarks";
    public static final String HISTORY = "history";
    public static final String DOWNLOADS = "downloads";
    public static final String APPEARANCE = "appearance";
    public static final String SHARE = "share";
    public static final String FIND = "find";
    public static final String HARD_RELOAD = "hard_reload";
    public static final String QR = "qr";
    // v1.3.38 vocabulary additions (new features → their toolbar shortcuts)
    public static final String WATCH_LATER = "watch_later";
    public static final String VOICE = "voice";
    public static final String SHARE_QR = "share_qr";
    public static final String SLEEP = "sleep_others";
    public static final String PIP = "pip";
    // v1.3.39 vocabulary additions (round-3 menu features)
    public static final String READER = "reader";
    public static final String OUTLINE = "outline";
    public static final String FULLSHOT = "fullshot";
    public static final String FULLSCREEN = "fullscreen";
    public static final String NIGHT = "night";
    public static final String TRASH = "trash";

    /** toolbar button keys (also the pref suffix: prefs "lp_" + key) */
    public static final String[] BUTTONS = {
            "back", "forward", "home", "tabs", "menu", "more", "reload"
    };

    public static String[] actionsFor(String button) {
        switch (button) {
            case "back":     return new String[]{ TAB_HISTORY, CLOSE_TAB, NEW_TAB, TABS, HISTORY, WATCH_LATER, READER, NIGHT, NONE };
            case "forward":  return new String[]{ TAB_HISTORY, NEW_TAB, TABS, HISTORY, WATCH_LATER, READER, FULLSHOT, NONE };
            case "home":     return new String[]{ BOOKMARKS, NEW_TAB, TABS, DOWNLOADS, WATCH_LATER, TRASH, NIGHT, NONE };
            case "tabs":     return new String[]{ NEW_TAB, CLOSE_TAB, DOWNLOADS, HISTORY, SLEEP, TRASH, OUTLINE, NONE };
            case "menu":     return new String[]{ APPEARANCE, BOOKMARKS, DOWNLOADS, HISTORY, VOICE, FULLSCREEN, NIGHT, NONE };
            case "more":     return new String[]{ NEW_TAB, SHARE, FIND, QR, SHARE_QR, WATCH_LATER, VOICE, PIP, READER, OUTLINE, FULLSHOT, NONE };
            case "reload":   return new String[]{ HARD_RELOAD, QR, SHARE, SHARE_QR, READER, FULLSHOT, NONE };
            default:         return new String[]{ NONE };
        }
    }

    public static String defaultFor(String button) {
        switch (button) {
            case "back": case "forward": return TAB_HISTORY;
            case "home":   return BOOKMARKS;
            case "tabs": case "more": return NEW_TAB;
            case "menu":   return APPEARANCE;
            case "reload": return HARD_RELOAD;
            default:       return NONE;
        }
    }

    /** localized label for an action code ("" when unknown). */
    public static String label(Context c, String code) {
        if (c == null || code == null) return "";
        switch (code) {
            case NONE:       return c.getString(R.string.qa_none);
            case NEW_TAB:    return c.getString(R.string.qa_new_tab);
            case CLOSE_TAB:  return c.getString(R.string.qa_close_tab);
            case TAB_HISTORY:return c.getString(R.string.qa_tab_history);
            case TABS:       return c.getString(R.string.qa_tabs);
            case HOME:       return c.getString(R.string.qa_home);
            case BOOKMARKS:  return c.getString(R.string.qa_bookmarks);
            case HISTORY:    return c.getString(R.string.qa_history);
            case DOWNLOADS:  return c.getString(R.string.qa_downloads);
            case APPEARANCE: return c.getString(R.string.qa_appearance);
            case SHARE:      return c.getString(R.string.qa_share);
            case FIND:       return c.getString(R.string.qa_find);
            case HARD_RELOAD:return c.getString(R.string.qa_hard_reload);
            case QR:         return c.getString(R.string.qa_qr);
            case WATCH_LATER: return c.getString(R.string.qa_watch_later);
            case VOICE:       return c.getString(R.string.qa_voice);
            case SHARE_QR:    return c.getString(R.string.qa_share_qr);
            case SLEEP:       return c.getString(R.string.qa_sleep);
            case PIP:         return c.getString(R.string.qa_pip);
            case READER:      return "Reader view";
            case OUTLINE:     return "Page outline";
            case FULLSHOT:    return "Full-page screenshot";
            case FULLSCREEN:  return "Fullscreen on/off";
            case NIGHT:       return "Night screen on/off";
            case TRASH:       return "Recently closed";
            default:         return code;
        }
    }

    /** localized label for a toolbar button key ("" when unknown). */
    public static String buttonLabel(Context c, String button) {
        if (c == null || button == null) return "";
        switch (button) {
            case "back":    return c.getString(R.string.lp_back);
            case "forward": return c.getString(R.string.lp_forward);
            case "home":    return c.getString(R.string.lp_home);
            case "tabs":    return c.getString(R.string.lp_tabs);
            case "menu":    return c.getString(R.string.lp_menu);
            case "more":    return c.getString(R.string.lp_more);
            case "reload":  return c.getString(R.string.lp_reload);
            default:        return button;
        }
    }
}
