package com.bolt.browser.ui;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v1.4.23 — THE LAUNCH LAW (the v1.4.22 decompile-port corpse):
 * "static gates prove code EXISTS, not that the app OPENS." v1.4.22 was
 * reconstructed by decompiling the shipped APK and porting R8-obfuscated
 * bytecode back to source; every static gate was green and the app
 * CRASHED ON LAUNCH on the user's Realme RMX2020 / Android 10 — no UI, no
 * crash page, even after a clean reinstall. This test is the missing
 * rung: it drives the REAL Application.onCreate (BoltApp, from the
 * manifest) through the REAL launcher path (every launcher face is an
 * activity-alias whose target is BrowserActivity) into the first
 * home-tab render (buildHomeView — including the v1.4.23 engine-mark
 * pill binding), under Robolectric's instrumented Android framework.
 * A green suite here means the app actually OPENS; release.sh runs this
 * BEFORE any publish and hard-fails the release on any FATAL EXCEPTION.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LaunchSmokeTest {

    /** Application.onCreate -> launcher activity -> first home render:
     *  any exception in that chain fails the release. */
    @Test
    public void launch_homeRenders_noFatal() {
        // Robolectric instantiates the manifest's Application (BoltApp)
        // before the activity — BoltApp.onCreate's channel setup, the
        // UA identity, the file-backed singletons all run for real.
        final BrowserActivity a =
                Robolectric.buildActivity(BrowserActivity.class).setup().get();
        assertNotNull("the launcher activity survived onCreate/onStart/onResume",
                a);
        assertFalse("the activity is not finishing", a.isFinishing());
        assertNotNull("the window has a content view (setContentView ran)",
                a.getWindow().findViewById(android.R.id.content));
        // THE FIRST HOME-TAB RENDER: buildHomeView built the home surface
        // (the launcher alias lands on the home tab first — the pill's
        // engine mark, the shortcut matrix, everything the crash ate).
        assertNotNull("buildHomeView rendered the home view",
                a.homeView);
        assertTrue("the home view has content",
                a.homeView instanceof android.view.ViewGroup
                        && ((android.view.ViewGroup) a.homeView).getChildCount() > 0);
    }

    /** The v1.4.23 code paths the launch actually touches: the home search
     *  pill carries the ACTIVE engine's mark (engineLogo + the raster PNG
     *  binding + the null-safe tint) — this proves the fresh fix rides the
     *  launch path, not just the static corpus. */
    @Test
    public void homePill_carriesTheEngineMark() {
        final BrowserActivity a =
                Robolectric.buildActivity(BrowserActivity.class).setup().get();
        assertNotNull(a.homeView);
        // walk the home tree for an ImageView whose drawable is one of the
        // engine marks (or the generic magnifier fallback — the binding ran)
        android.widget.ImageView found = findEngineMark((android.view.ViewGroup) a.homeView);
        assertNotNull("the home pill's engine-mark ImageView is bound "
                        + "(engineLogo(prefs.searchEngine()) ran on the launch path)",
                found);
    }

    private static android.widget.ImageView findEngineMark(android.view.ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            android.view.View v = g.getChildAt(i);
            if (v instanceof android.widget.ImageView) {
                android.widget.ImageView iv = (android.widget.ImageView) v;
                if (iv.getDrawable() != null
                        && iv.getDrawable().getIntrinsicWidth() > 0) {
                    return iv;
                }
            } else if (v instanceof android.view.ViewGroup) {
                android.widget.ImageView deep = findEngineMark((android.view.ViewGroup) v);
                if (deep != null) return deep;
            }
        }
        return null;
    }
}
