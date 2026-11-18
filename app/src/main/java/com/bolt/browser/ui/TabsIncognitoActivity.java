package com.bolt.browser.ui;

import android.os.Bundle;

import com.bolt.browser.core.TabManager;

/**
 * Tabs view that runs inside the :incognito process (manages incognito tabs).
 * v1.4.12 — THE PROCESS FLAG: this activity lives in the :incognito process
 * (see the manifest) but never set TabManager.INCOGNITO the way its browser
 * sibling IncognitoActivity does, so it booted in NORMAL mode inside the
 * incognito process: it listed the normal process's persisted tabs.json
 * (TabsActivity's default reader), rendered the normal chip state, and its
 * tab taps routed to the NORMAL BrowserActivity — the whole incognito tabs
 * surface was a normal tabs view wearing an incognito name. The static
 * initializer now runs in THIS process before any instance exists, exactly
 * as IncognitoActivity.java has carried it since the split (the flag is read
 * once per process at TabManager construction; an instance method would be
 * too late for the list load in onCreate).
 *
 * v1.4.16 — THE RACE-PROOF FLAG, round 2 (the IncognitoActivity lesson
 * applied here too): the static block only runs when THIS class resolves —
 * a multi-process class-loading order that constructs TabManager first left
 * INCOGNITO false and the whole surface normal again. onCreate() now sets
 * the flag as its VERY FIRST statement, before super.onCreate() (whose
 * TabsActivity.onCreate reads tm = TabManager.get(this) and loads the list),
 * so the flag is deterministic regardless of loader order.
 */
public class TabsIncognitoActivity extends TabsActivity {
    static {
        TabManager.INCOGNITO = true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        TabManager.INCOGNITO = true;
        super.onCreate(savedInstanceState);
    }
}
