package com.bolt.browser.ui;

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
 */
public class TabsIncognitoActivity extends TabsActivity {
    static {
        TabManager.INCOGNITO = true;
    }
}
