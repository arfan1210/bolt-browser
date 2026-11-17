package com.bolt.browser.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

/**
 * v1.3.23 — Passkey compatibility layer, Java side (pairs with
 * assets/webauthn_compat.js, the JS side).
 *
 * WHY: relying parties (github.com, accounts.google.com) classify bolt's
 * WebView as an embedded in-app browser with "partial passkey support"
 * using three fingerprints, and this class removes two of them at the
 * source:
 *  1. the User-Agent: the default WebView UA carries "; wv)" and
 *     "Version/4.0" — replaced by a Chrome-on-Android shaped UA. Real
 *     Chrome REDUCES its UA to "Android 10; K" on every device (UA
 *     reduction), so keeping the Android 10 token is more Chrome-accurate
 *     than claiming "Android 14" in the UA string. The OS version sites
 *     actually gate on is navigator.userAgentData.platformVersion —
 *     reported "14.0.0" by the JS side. A custom UA chosen by the user
 *     (Settings / per-site) is left completely untouched.
 *  2. document-start injection: webauthn_compat.js must run BEFORE page
 *     scripts so it can complete the PublicKeyCredential surface
 *     (getClientCapabilities, parseCreationOptionsFromJSON, conditional
 *     mediation, ...). androidx.webkit's addDocumentStartJavaScript is
 *     used when the device WebView supports it; otherwise BoltClient.
 *     onPageStarted calls {@link #injectNow} as the fallback (the script
 *     is idempotent, so double injection is harmless).
 *
 * The third fingerprint (the X-Requested-With header) is NOT touched in
 * this build: stripping it requires re-fetching main documents through a
 * proxy pipeline that can destabilize browsing. Enable only if Google
 * sign-in is still blocked after the UA fix (see INTEGRATION notes in the
 * repo docs).
 *
 * All methods are null-safe and never throw — a failure here must never
 * take browsing down with it.
 */
public final class PasskeyCompat {

    private static final String TAG = "BoltPasskeyCompat";

    /** Chromium major of the installed WebView engine; sane default. */
    private static volatile int chromeMajor = 156;

    /** Full version of the installed WebView engine. */
    private static volatile String chromeFull = "156.0.8078.47";

    /** true once document-start injection is known to work on this device. */
    private static volatile boolean docStartOk = false;

    /** the prepared (placeholder-filled) compat script, cached for the process. */
    private static volatile String script = null;

    private PasskeyCompat() {}

    /**
     * One-call setup for one WebView: detect the engine version, register the
     * compat script at document start (all frames), and shape the UA.
     * Called from Tab.buildWeb() right after WebAuthnHelper.enablePasskeys().
     */
    public static void apply(Context c, WebView w) {
        if (c == null || w == null) return;
        detectEngine(c);
        String js = prepareScript(c);
        if (js != null) {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(w, js, Collections.singleton("*"));
                    docStartOk = true;
                    Log.i(TAG, "passkey compat applied (Chrome/" + chromeMajor
                            + ", document-start)");
                } else {
                    Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported on this WebView; "
                            + "onPageStarted fallback active");
                }
            } catch (Throwable t) {
                Log.w(TAG, "document-start injection unavailable; "
                        + "onPageStarted fallback active", t);
            }
        }
        fixUa(w.getSettings());
    }

    /**
     * Chrome-on-Android (reduced) UA — no "wv", no "Version/4.0". The engine
     * major is the DETECTED WebView version so sec-ch style parsing of the UA
     * stays consistent with the real engine features.
     */
    public static String chromeUa() {
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + chromeMajor + ".0.0.0 Mobile Safari/537.36";
    }

    /**
     * v1.3.99 — Chrome-on-Windows (desktop) UA, DETECTED-engine version.
     * THE IDENTITY-AGREEMENT LAW: the UA string, the native client-hints
     * metadata (UaIdentity) and the JS fallback spoof must all claim the
     * SAME Chrome major. The v1.3.98 desktop preset was frozen at
     * Chrome/124 (a mid-2024 string) while UaIdentity's brands reported the
     * DETECTED engine (156 on the field device) — a navigator.userAgent
     * claiming 124 while navigator.userAgentData and the Sec-CH-UA headers
     * claim 156 is a mismatch no honest browser ever emits, and exactly the
     * pair a Cloudflare-tier gate (grok.com answers with accept-ch +
     * critical-ch listing every hint — verified) reads as a bot
     * fingerprint; a 2.5-year-old version number fails modern feature
     * gates on its own. The desktop toggle's UA is composed from the same
     * detected major the hints use (the chromeUa pattern), so string,
     * native hints and JS fallback agree in every direction and are never
     * years stale.
     */
    public static String desktopChromeUa() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + chromeMajor + ".0.0.0 Safari/537.36";
    }

    /**
     * Replace the UA ONLY when it is the default WebView one (null, contains
     * "; wv)" or "Version/4.0"). User-chosen custom/desktop UAs pass through.
     *
     * v1.3.25: NEVER call this on a WebView that has a page loaded —
     * setUserAgentString() with a CHANGED value makes Chromium reload the
     * current page (issue 40340203), which preempts any pending navigation
     * (the v1.3.23/24 "every link click reloads the page" regression).
     * Tab.applyUa() now computes the final UA itself and writes it once,
     * only when different; this method remains for the build-time call in
     * {@link #apply} (no page loaded yet there, a change is reload-free).
     */
    public static void fixUa(WebSettings s) {
        if (s == null) return;
        try {
            String ua = s.getUserAgentString();
            if (ua != null && !ua.contains("; wv)") && !ua.contains("Version/4.0")) {
                return; // custom UA — respect it
            }
            s.setUserAgentString(chromeUa());
        } catch (Throwable ignored) {}
    }

    /**
     * Fallback injection for WebViews where document-start registration is
     * unavailable: called from BoltClient.onPageStarted(), which fires at
     * navigation commit — before page scripts execute.
     */
    public static void injectNow(WebView w) {
        if (docStartOk || w == null || script == null) return;
        try {
            w.evaluateJavascript(script, null);
        } catch (Throwable ignored) {}
    }

    /** Read the installed WebView package version once per process. */
    private static void detectEngine(Context c) {
        try {
            PackageInfo pi = WebViewCompat.getCurrentWebViewPackage(c);
            if (pi == null || pi.versionName == null || pi.versionName.isEmpty()) return;
            chromeFull = pi.versionName;
            String head = pi.versionName;
            int dot = head.indexOf('.');
            if (dot > 0) head = head.substring(0, dot);
            int major = Integer.parseInt(head);
            if (major > 0) chromeMajor = major;
        } catch (Throwable ignored) {}
    }

    /**
     * Read assets/webauthn_compat.js, fill the engine/model placeholders,
     * cache. Runs once per process; safe to call from any thread.
     */
    private static String prepareScript(Context c) {
        String cached = script;
        if (cached != null) return cached;
        try {
            InputStream in = c.getAssets().open("webauthn_compat.js");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String js = new String(bos.toByteArray(), StandardCharsets.UTF_8)
                    .replace("__CHROME_MAJOR__", String.valueOf(chromeMajor))
                    .replace("__CHROME_FULL__", chromeFull)
                    // the OS version sites gate on (client hints), not the UA
                    // token: real Chrome reports the true platformVersion here,
                    // we claim 14 so Google's passkey UX floor reads as passed.
                    .replace("__PLATFORM_VERSION__", "14.0.0")
                    .replace("__DEVICE_MODEL__", Build.MODEL == null ? "" : Build.MODEL);
            script = js;
            return js;
        } catch (Throwable t) {
            Log.w(TAG, "prepareScript failed", t);
            return null;
        }
    }
}
