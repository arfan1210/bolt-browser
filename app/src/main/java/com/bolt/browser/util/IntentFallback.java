package com.bolt.browser.util;

/**
 * v1.4.19 — THE INTENT FALLBACK LOADER (the offline-error corpse's second
 * half, root-caused from the user's captured URL: a Facebook intent://
 * link whose S.browser_fallback_url was NEVER loaded — the site's app
 * link tapped with no app installed produced "Can't reach this page"
 * over an intent:// address, and the error page then ATE the tab).
 *
 * The Android intent-URI contract (Chrome's, adopted platform-wide):
 * an intent:// link may carry
 *     S.browser_fallback_url=https%3A%2F%2Fexample.com%2F...
 * — the WEB page to open when NO app can service the intent. Bolt's
 * old handler only toasted "No app can open this" and left the tab on
 * the (unresolvable) intent URI; the fallback URL is the site's own
 * honest answer to "the app isn't here" and must be LOADED.
 *
 * PURE by design (the v1.4.11 parse-gate law): the extraction is a
 * string function with no Android dependency, probe-pinned by
 * scripts/test_v1419.py.
 */
public final class IntentFallback {

    private IntentFallback() {}

    private static final String MARKER = "S.browser_fallback_url=";

    /**
     * Extract + URL-decode the browser_fallback_url from an intent:// URI.
     * Returns null when the marker is absent, the value is empty, or the
     * decoded value is not an http(s) URL (the only kind a WebView tab can
     * load — a fallback to another intent:// or an app scheme would just
     * recurse into the same dead end).
     */
    public static String fallbackUrl(String intentUri) {
        if (intentUri == null) return null;
        int i = intentUri.indexOf(MARKER);
        if (i < 0) return null;
        int from = i + MARKER.length();
        int end = intentUri.indexOf(';', from);
        String raw = end < 0 ? intentUri.substring(from)
                : intentUri.substring(from, end);
        if (raw.isEmpty()) return null;
        String dec;
        try {
            dec = java.net.URLDecoder.decode(raw, "UTF-8");
        } catch (Throwable t) {
            dec = raw;
        }
        if (!dec.startsWith("http:") && !dec.startsWith("https:")) return null;
        return dec;
    }
}
