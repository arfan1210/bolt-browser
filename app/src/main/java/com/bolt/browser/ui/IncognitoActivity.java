package com.bolt.browser.ui;

import android.os.Bundle;

import com.bolt.browser.core.TabManager;

/** Runs in the :incognito process with its own WebView data dir & cookie jar. */
public class IncognitoActivity extends BrowserActivity {
    static {
        TabManager.INCOGNITO = true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v1.4.16 — THE RACE-PROOF FLAG. The static block above sets
        // TabManager.INCOGNITO when THIS class loads — but class-load order
        // is not guaranteed across the multi-process split: if TabManager
        // (or anything that constructs it) is touched before this activity
        // class resolves, the flag read at TabManager construction was
        // still false and the "incognito" process routed tabs through the
        // NORMAL pipeline (normal persisted tabs.json, normal chip state).
        // The assignment now ALSO runs as the very first statement of
        // onCreate(), BEFORE super.onCreate() (whose tab-restore path
        // constructs the TabManager), making the flag deterministic no
        // matter which class the loader resolved first.
        TabManager.INCOGNITO = true;
        super.onCreate(savedInstanceState);
    }
}
