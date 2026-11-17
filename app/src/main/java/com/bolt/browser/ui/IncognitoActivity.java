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
        super.onCreate(savedInstanceState);
    }
}
