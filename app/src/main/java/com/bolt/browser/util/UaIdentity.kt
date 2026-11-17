package com.bolt.browser.util

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * v1.3.98 — THE CLIENT-HINTS TWIN of the UA string (the desktop-site fix,
 * round 4: "qwen coder sometimes falls back to mobile view and grok website
 * does not open in desktop site at all").
 *
 * WHY THIS EXISTS. Bolt ships a custom UA (PasskeyCompat.chromeUa, or the
 * desktop/chooser strings). A custom UA has a side effect nobody sees until
 * a site reads client hints: with the UA overridden, WebView generates NO
 * user-agent client hints — no `navigator.userAgentData` shape it controls
 * and no Sec-CH-UA* request headers. A hints-reading site then sees a
 * DESKTOP UA STRING with mobile-or-absent hints and serves the mobile layout
 * anyway (or its bot score eats the mismatch). androidx.webkit grew the
 * native fix: `WebSettingsCompat.setUserAgentMetadata` populates BOTH the
 * JS surface and the Sec-CH headers from data WE choose — the documented
 * companion for custom UA strings. This file keeps that metadata in
 * lockstep with the live UA, using the SAME grammar as the JS spoof in
 * assets/webauthn_compat.js (the two never disagree; the JS spoof stays as
 * the fallback on WebViews without the feature).
 *
 * THE LAW (the v1.3.25 single-write contract, extended): metadata is only
 * written when its SHAPE (platform + mobile + brand head) differs from the
 * live one — the steady state costs nothing, and a write rides the same
 * navigation moments the UA string does (never mid-load except where the
 * UA string itself is healing, where Chromium reloads the in-flight load
 * by design — crbug 40340203).
 *
 * Never throws; unsupported WebViews keep today's behavior (the JS spoof
 * alone, exactly as before this file existed).
 */
object UaIdentity {

    /** engine major of the installed WebView, read once per process. */
    @Volatile private var engineMajor = 0

    private fun engineMajor(c: Context?): Int {
        if (engineMajor > 0) return engineMajor
        try {
            val pi = c?.let { WebViewCompat.getCurrentWebViewPackage(it) }
            val v = pi?.versionName ?: ""
            val head = v.substringBefore('.').toIntOrNull() ?: 0
            if (head > 0) engineMajor = head
        } catch (_: Throwable) {}
        if (engineMajor <= 0) engineMajor = 156
        return engineMajor
    }

    /** GREASE brands rotate by Chromium version — same table as the JS side. */
    private fun greaseBrand(v: Int): String {
        val table = arrayOf(
            "Not(A:Brand", "Not-A.Brand", "Not/A)Brand",
            "Not;A=Brand", "Not A(Brand", "Not?A_Brand")
        return table[(v + 2) % table.size]
    }

    private fun brands(v: Int): List<UserAgentMetadata.BrandVersion> {
        val major = v.toString()
        return listOf(
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium").setMajorVersion(major).build(),
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand(greaseBrand(v)).setMajorVersion("99").build(),
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Google Chrome").setMajorVersion(major).build())
    }

    /**
     * v1.3.99 — THE IDENTITY-AGREEMENT LAW, native half. The brand list
     * claimed the DETECTED engine major regardless of what the UA STRING
     * says: with the v1.3.98 frozen desktop preset (string Chrome/124) the
     * Sec-CH-UA headers and navigator.userAgentData claimed 156 — a
     * string/hints version mismatch no honest browser emits, exactly what a
     * Cloudflare-tier gate (grok.com: accept-ch + critical-ch) reads as a
     * bot fingerprint. The native hints now claim whatever Chrome/N the
     * LIVE STRING claims (detected engine as the fallback when the string
     * carries no parsable Chrome token). Tab.applyUa composes the desktop
     * preset from the detected engine (PasskeyCompat.desktopChromeUa), so
     * in the steady state string and hints both say the true engine major
     * — and if a preset string ever diverges again, the hints follow the
     * STRING, never the other way around.
     */
    private fun brandMajor(c: Context?, ua: String): Int {
        val m = Regex("Chrome/(\\d+)").find(ua)
        val parsed = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (parsed > 0) parsed else engineMajor(c)
    }

    /**
     * Apply the metadata matching [ua]. Shapes mirror webauthn_compat.js:
     * Chromium on Windows/macOS/Android keeps the Chrome brand list with
     * the matching platform + mobile flag; Firefox/Safari get null (real
     * Firefox/Safari never shipped UA-CH — only the low-entropy hints
     * WebView derives from the string remain, which is the truthful shape).
     */
    @JvmStatic
    fun applyFor(c: Context?, s: WebSettings?, ua: String?) {
        if (s == null) return
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
            val u = ua ?: ""
            val win = u.contains("Windows NT")
            val mac = u.contains("Macintosh") ||
                    (u.contains("Mac OS X") && !u.contains("iPhone") && !u.contains("iPad"))
            val ios = u.contains("iPhone") || u.contains("iPad")
            val android = !win && !mac && !ios && u.contains("Android")
            val chromium = u.contains("Chrome") || u.contains("Chromium")
            val mobile = !win && !mac && u.contains("Mobile")

            val brandMajor = brandMajor(c, u)
            val target: UserAgentMetadata? = when {
                // real Firefox / Safari never implemented navigator.userAgentData
                !chromium -> null
                win -> UserAgentMetadata.Builder()
                    .setBrandVersionList(brands(brandMajor))
                    .setMobile(false).setPlatform("Windows")
                    .setPlatformVersion("15.0.0")
                    .setArchitecture("x86").setBitness(UserAgentMetadata.BITNESS_DEFAULT)
                    .setWow64(false).build()
                mac -> UserAgentMetadata.Builder()
                    .setBrandVersionList(brands(brandMajor))
                    .setMobile(false).setPlatform("macOS")
                    .setPlatformVersion("14.5.0")
                    .setArchitecture("x86").setBitness(UserAgentMetadata.BITNESS_DEFAULT)
                    .setWow64(false).build()
                ios -> UserAgentMetadata.Builder()
                    .setBrandVersionList(brands(brandMajor))
                    .setMobile(true).setPlatform("iOS")
                    .setPlatformVersion("17.4.0")
                    .setArchitecture("arm").setBitness(UserAgentMetadata.BITNESS_DEFAULT)
                    .setWow64(false).build()
                android -> UserAgentMetadata.Builder()
                    .setBrandVersionList(brands(brandMajor))
                    .setMobile(true).setPlatform("Android")
                    .setPlatformVersion(Build.VERSION.RELEASE ?: "14")
                    .setArchitecture("arm").setBitness(UserAgentMetadata.BITNESS_DEFAULT)
                    .setWow64(false)
                    .setModel(Build.MODEL ?: "").build()
                else -> null
            }

            // shape-level compare — write only on a real difference
            val cur = try { WebSettingsCompat.getUserAgentMetadata(s) } catch (_: Throwable) { null }
            if (sameShape(cur, target)) return
            WebSettingsCompat.setUserAgentMetadata(s, target)
            Diag.log(c, "ua metadata " + (target?.let {
                it.platform + (if (it.isMobile) " mobile" else " desktop")
            } ?: "cleared"))
        } catch (_: Throwable) {
            // never let identity plumbing take browsing down
        }
    }

    private fun sameShape(a: UserAgentMetadata?, b: UserAgentMetadata?): Boolean {
        if (a == null || b == null) return a === b   // null vs non-null = different
        if (a.isMobile != b.isMobile) return false
        if (a.platform != b.platform) return false
        val ab = a.brandVersionList ?: emptyList()
        val bb = b.brandVersionList ?: emptyList()
        val ah = ab.firstOrNull()?.brand
        val bh = bb.firstOrNull()?.brand
        if (ah != bh) return false
        // v1.3.99 — the identity-agreement law extends the compare to the
        // VERSION: a UA-string major change with the same platform/mobile/
        // brand-name shape (the frozen-124 -> living-156 transition) must
        // rewrite the metadata, or the hints keep claiming the old major.
        val am = ab.firstOrNull()?.majorVersion
        val bm = bb.firstOrNull()?.majorVersion
        return am == bm
    }
}
