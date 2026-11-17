package com.bolt.browser.util

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * v1.3.20 — WebAuthn / passkey support for bolt's WebViews.
 *
 * Enables the WebView's NATIVE WebAuthn engine (androidx.webkit 1.12+): pages
 * calling navigator.credentials.create()/get() are routed by Chromium itself
 * to Android's Credential Manager — Google Password Manager or any
 * third-party passkey provider the user set as preferred. bolt adds no JS
 * bridge and no credential handling of its own; the browser just declares,
 * per WebView, that it acts as a browser for the sites it shows.
 *
 * Mode: WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER — the page's own origin is the
 * relying party, so passkeys work for ARBITRARY sites (github.com, webauthn.io,
 * ...). This is the mode real browsers must use; FOR_APP is for apps hosting
 * their own domain via Digital Asset Links and standard passkey providers
 * (Bitwarden etc.) reject its assertions.
 *
 * Call [enablePasskeys] once per WebView — wired centrally in Tab.buildWeb(),
 * which every tab (incl. restores and the incognito process) goes through.
 *
 * Platform caveats (inherent to WebView/Android, not bugs here):
 *  - ANDROID 13 AND BELOW (v1.3.22, verified): Chromium routes WebView
 *    WebAuthn through Play services' FIDO2 BROWSER APIs, which Google serves
 *    only to individually-verified browsers (a Play-services signature
 *    allowlist). A sideloaded browser can never pass that gate → pages see no
 *    platform authenticator → GitHub's "partial passkey support" +
 *    "Authentication failed". No app-side code can change it (same reason it
 *    fails in Via). USB/NFC security keys still work through the
 *    non-privileged path.
 *  - ANDROID 14+: Chromium routes through Credential Manager. Google Password
 *    Manager releases its saved passkeys to the DEFAULT browser — set bolt as
 *    default. Third-party providers (Bitwarden…) work through their own
 *    prompts either way.
 *  - conditional UI (passkey autofill on username fields) is unavailable in
 *    any WebView — sites may still say "partial support" on 14+ even when the
 *    button flow works;
 *  - devices whose Android System WebView predates the feature simply report
 *    unsupported — the call degrades to a no-op (WebView 131+ recommended).
 */
object WebAuthnHelper {

    private const val TAG = "WebAuthnHelper"

    /** one-shot: the first per-WebView enable result is persisted to diag.log
     *  so every field report carries the actual on-device passkey state. */
    @Volatile
    private var diagLogged = false

    /** Runtime capability probe — for settings rows / diagnostics. */
    @JvmStatic
    fun isPasskeySupportAvailable(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)

    /**
     * Enables WebAuthn for one WebView. Never throws; returns false when the
     * device lacks the feature or the WebView downgraded the requested mode.
     */
    @JvmStatic
    fun enablePasskeys(webView: WebView): Boolean {
        if (!isPasskeySupportAvailable()) {
            Log.i(TAG, "device WebView has no WebAuthn; passkeys off")
            logOnce(webView.context, "webauthn enable: feature NOT supported by this WebView")
            return false
        }
        return try {
            WebSettingsCompat.setWebAuthenticationSupport(
                webView.settings,
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
            )
            // the WebView may downgrade the requested mode — verify what took effect
            val effective = WebSettingsCompat.getWebAuthenticationSupport(webView.settings)
            if (effective == WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER) {
                Log.i(TAG, "WebAuthn enabled (browser mode)")
                logOnce(webView.context,
                    "webauthn enable ok (browser mode, webView=${webViewVersion(webView.context)}); " +
                        (if (Build.VERSION.SDK_INT < 34)
                            "platform passkeys GATED: Android <14 uses GMS FIDO2 browser APIs (Google-verified browsers only)"
                        else
                            "platform passkeys available via Credential Manager"))
                true
            } else {
                Log.w(TAG, "WebAuthn mode downgraded to $effective")
                logOnce(webView.context, "webauthn enable DOWNGRADED to $effective")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to enable WebAuthn", t)
            logOnce(webView.context, "webauthn enable FAILED: $t")
            false
        }
    }

    // ------------------------------------------------ v1.3.21 status reporting

    /** Android System WebView package version ("" unknown). */
    @JvmStatic
    fun webViewVersion(ctx: Context): String {
        return try {
            val p = WebViewCompat.getCurrentWebViewPackage(ctx)
            p?.versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * Is bolt the device's default browser? Google Password Manager only
     * releases saved passkeys through WebView's WebAuthn to the default
     * browser; "yes"/"no"/"unknown" (API < 29 can't ask RoleManager).
     */
    @JvmStatic
    fun defaultBrowserState(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 29) return "unknown"
        return try {
            val rm = ctx.getSystemService(RoleManager::class.java) ?: return "unknown"
            if (rm.isRoleHeld(RoleManager.ROLE_BROWSER)) "yes" else "no"
        } catch (t: Throwable) {
            "unknown"
        }
    }

    /** Short status value for the settings row. */
    @JvmStatic
    fun shortStatus(ctx: Context): String {
        return when {
            !isPasskeySupportAvailable() -> "Update web view"
            // v1.3.24: bolt now carries its own on-device authenticator
            // (BoltAuthenticator + the webauthn_compat layer), so passkeys
            // WORK on every Android version — the GMS FIDO2 gate no longer
            // decides anything. Stored count shown for quick reference.
            else -> {
                val n = try { BoltAuthenticator.count(ctx) } catch (t: Throwable) { 0 }
                if (n > 0) "On — $n passkey${if (n == 1) "" else "s"} stored" else "On"
            }
        }
    }

    /**
     * Full multi-line status for the settings dialog + the diag log.
     *
     * v1.3.24 — passkeys now WORK on every Android version: bolt ships its
     * own on-device authenticator (BoltAuthenticator — AndroidKeyStore ES256
     * keys, stored per site+account, consent dialog per ceremony), presented
     * to sites by the webauthn_compat layer as a fully-capable Chrome
     * platform authenticator. The old "Blocked by Android <14" verdict
     * (GMS FIDO2 gate) applied to the NATIVE WebView path only; that path is
     * now a fallback on 14+ devices, not the story.
     */
    @JvmStatic
    fun statusReport(ctx: Context): String {
        val wv = webViewVersion(ctx)
        val supported = isPasskeySupportAvailable()
        val def = defaultBrowserState(ctx)
        val stored = try { BoltAuthenticator.count(ctx) } catch (t: Throwable) { 0 }
        val sb = StringBuilder()
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Android System WebView: ").append(if (wv.isEmpty()) "unknown" else wv).append('\n')
        sb.append("WebAuthn engine: ").append(
            if (supported) "available (browser mode)" else "not supported by this WebView").append('\n')
        sb.append("Bolt authenticator: active — ").append(stored)
                .append(" passkey").append(if (stored == 1) "" else "s").append(" stored\n")
        if (!supported) {
            sb.append("\nThe compat layer still provides the full passkey surface")
            sb.append("\neven without the native engine; updating WebView is still")
            sb.append("\nrecommended (Play Store > Android System WebView).")
        }
        sb.append("\nHow it works: every site that offers passkeys (GitHub,")
        sb.append("\nGoogle, webauthn.io...) sees a fully capable Chrome platform")
        sb.append("\nauthenticator. Creating a passkey stores it on THIS device")
        sb.append("\n(AndroidKeyStore, hardware-backed where available); signing in")
        sb.append("\nasks you to confirm with a dialog. Works on every Android")
        sb.append("\nversion bolt supports.")
        sb.append("\n\nHonest limits:")
        sb.append("\n- Passkeys are device-bound: no cloud sync, and uninstalling")
        sb.append("\n  Bolt removes them — keep a second sign-in method registered")
        sb.append("\n  at each site (standard advice for device-bound passkeys).")
        sb.append("\n- User verification is the confirmation dialog (no biometric)")
        sb.append("\n- Passkey autofill in username fields is still not offered")
        sb.append("\n  (no WebView can); use the site's passkey button.")
        if (Build.VERSION.SDK_INT >= 34 && def == "no") {
            sb.append("\n\nNote: on Android 14+, Google Password Manager passkeys")
            sb.append("\n(synchronized across devices) additionally require Bolt to")
            sb.append("\nbe the default browser — but Bolt's own stored passkeys")
            sb.append("\nabove work regardless.")
        }
        sb.append("\n\nTest: open webauthn.io in Bolt and try Register / Sign in.")
        return sb.toString()
    }

    private fun logOnce(ctx: Context, line: String) {
        if (diagLogged) return
        diagLogged = true
        try {
            Diag.log(ctx.applicationContext, line)
        } catch (t: Throwable) {
            // diagnostics must never break the browser
        }
    }
}
