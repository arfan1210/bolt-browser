package com.bolt.browser.core;

import android.content.Context;
import android.content.MutableContextWrapper;
import android.webkit.WebView;

/**
 * v1.4.13 — THE WEBVIEW PRE-WARM POOL (the report: "creating a new WebView
 * from scratch blocks the CPU for ~300 ms, causing a micro-stutter when
 * opening new tabs").
 *
 * THE MECHANIC: BoltApp.onCreate POSTS a spare WebView construction 250 ms
 * out (main thread — WebViews demand a Looper thread, and a background-
 * constructed one could never be attached from main). By then the first
 * activity has painted and the ~300 ms of provider load + AwContents
 * construction overlaps the user's eye settling on the omnibox instead of
 * stalling a tab switch. Tab.buildWeb TAKES the spare when it needs a
 * WebView and falls back to plain construction when the pool is empty —
 * the take is always non-blocking and never waits.
 *
 * THE CONTEXT LAW (why MutableContextWrapper): the spare must outlive the
 * activity it is built for (it is built before ANY activity exists), but a
 * WebView constructed on the APPLICATION context shows app-themed dialogs
 * and — the real corpse — an HTML <select multiple> AlertDialog cannot get
 * a window token from an application context (BadTokenException). So the
 * spare is constructed over a MutableContextWrapper carrying the app
 * context, and take() SWAPS the wrapper's base to the caller's real
 * context before handing it over: after the swap the WebView reads exactly
 * like one constructed with that context. (The phoenix path in
 * MediaControlService already builds WebViews on the application context
 * — the swap is a no-op shape there.)
 *
 * THE RULES that keep this safe:
 *  - main thread only (prewarm and take); the pool is one slot, process-
 *    local, and synchronized anyway for belt and braces;
 *  - the spare is NEVER attached to any view hierarchy while it waits;
 *  - ONE spare at a time — the pool is a stutter damper, not a tab cache
 *    (an idle, never-loaded WebView holds no renderer; its cost is the
 *    one-time provider warm, which is exactly the cost we want prepaid);
 *  - a take() DRAINS the slot: the next WebView builds the normal way
 *    until BoltApp pre-warms again (it pre-warms once per process — a
 *    second spare would compete with the first tab's memory for no gain).
 */
public final class WebPool {

    private static WebView spare;

    private WebPool() {}

    /** build the spare (main thread, once per process; idempotent). */
    public static synchronized void prewarm(Context app) {
        if (spare != null) return;
        try {
            spare = new com.bolt.browser.ui.BoltWeb(
                    new MutableContextWrapper(app.getApplicationContext()));
        } catch (Throwable t) {
            spare = null;   // a WebView that fails to construct must not poison the slot
        }
    }

    /**
     * Take the spare for a real owner (main thread). Returns null when the
     * pool is empty — the caller falls back to plain construction; NEVER
     * waits, NEVER constructs on behalf of the caller.
     */
    public static synchronized WebView take(Context real) {
        WebView w = spare;
        spare = null;
        if (w != null && real != null) {
            try {
                android.content.Context cur = w.getContext();
                if (cur instanceof MutableContextWrapper) {
                    ((MutableContextWrapper) cur).setBaseContext(real);
                }
            } catch (Throwable ignored) {}
        }
        return w;
    }
}
