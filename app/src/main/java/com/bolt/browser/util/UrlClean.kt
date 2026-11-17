package com.bolt.browser.util

import android.content.Context

/**
 * v1.3.40 — URL TRACKER-PARAMETER CLEANER (round-4 menu item 4).
 *
 * Strips analytics/campaign parameters (utm_*, fbclid, gclid, msclkid, …)
 * from URLs when they are OPENED, SHARED or COPIED:
 *
 *  - global toggle: Settings → Privacy → "Clean tracking parameters"
 *    (Prefs "cleanTrackers", default ON)
 *  - per-site off:  page menu → "Clean trackers" row (sites.json "clean",
 *    tri-state like every other site pref: unset follows the global)
 *  - a small toast fires when parameters were actually removed (throttled
 *    to at most one per 4 s so redirect chains never spam)
 *
 * The transform is IDEMPOTENT by construction (a cleaned URL passes through
 * unchanged), so re-running it on every shouldOverrideUrlLoading pass can
 * never loop a navigation.
 */
object UrlClean {

    /** well-known non-utm tracking/campaign parameters (lowercase). */
    private val KNOWN = setOf(
            "fbclid",              // Meta
            "gclid", "gbraid", "wbraid", "dclid",   // Google Ads / Display
            "msclkid",             // Microsoft Ads
            "yclid",               // Yandex Direct
            "ttclid", "twclid", "li_fat_id",        // TikTok / X / LinkedIn
            "igshid", "igsh",      // Instagram share links
            "mc_cid", "mc_eid",    // Mailchimp
            "mkt_tok",             // Marketo
            "_ga", "_gl", "_ke",   // Google Analytics links / KlientBoost
            "_hsenc", "_hsmi",     // HubSpot
            "cmpid", "ocid", "elqTrackId",
            "si",                  // YouTube share attribution
            "spm", "scm", "vd_tc"  // AliExpress / Alibaba family
    )

    fun isTrackerKey(k: String?): Boolean {
        if (k == null || k.isEmpty()) return false
        val key = k.lowercase()
        // utm_* (any) + the raw "utm" used by some redirectors
        return key.startsWith("utm_") || key == "utm" || KNOWN.contains(key)
    }

    /** the cleaned URL (identical object semantics: returns the same string
     *  when nothing was stripped). Only http/https URLs are touched;
     *  path, fragment and parameter ORDER of the survivors are preserved. */
    @JvmStatic
    fun clean(url: String?): String {
        if (url == null) return ""
        val m = Regex("^(https?://[^?#]*)([?][^#]*)?(#.*)?$",
                RegexOption.IGNORE_CASE).find(url) ?: return url
        val base = m.groupValues[1]
        val query = m.groupValues[2]
        val frag = m.groupValues[3]
        if (query.isEmpty()) return url
        val kept = query.substring(1).split('&')
                .filter { it.isNotEmpty() && !isTrackerKey(it.substringBefore('=')) }
        val out = when {
            kept.isEmpty() -> base + frag
            else -> base + "?" + kept.joinToString("&") + frag
        }
        return out
    }

    /** how many parameters a clean() pass would strip from this URL. */
    @JvmStatic
    fun count(url: String?): Int {
        if (url == null) return 0
        val m = Regex("^[^?#]*[?]([^#]*)(#.*)?$", RegexOption.IGNORE_CASE).find(url)
                ?: return 0
        return m.groupValues[1].split('&')
                .count { it.isNotEmpty() && isTrackerKey(it.substringBefore('=')) }
    }

    /** effective switch for a URL: global toggle AND the site's own override
     *  (site pref "clean"; unset = inherit the global value). */
    @JvmStatic
    fun enabled(ctx: Context, url: String?): Boolean {
        return try {
            val p = Prefs.get(ctx)
            val host = Fx.hostOf(url ?: return p.cleanTrackers())
            val base = Fx.baseDomain(host)
            p.siteBool(base, "clean", p.cleanTrackers())
        } catch (t: Throwable) {
            true
        }
    }

    // ---- the toast (throttled; one per 4 s at most) ----
    private var lastToast = 0L

    @JvmStatic
    fun toastStripped(ctx: Context, n: Int) {
        if (n <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastToast < 4000L) return
        lastToast = now
        try {
            Fx.toast(ctx.applicationContext,
                    "Stripped $n tracking parameter" + (if (n == 1) "" else "s"))
        } catch (ignored: Throwable) {}
    }
}
