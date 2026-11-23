package com.bolt.browser.util;

/**
 * v1.4.29 — PURE popup policy for the related-child window (probe-pinned:
 * scripts/test_v1429.py compiles + runs THIS file; PopupPolicyTest rides
 * the Robolectric suite).
 *
 * <p>THE CORPSE (the "Imgur Google sign-in returns to a black page" field
 * report): {@code Tab.buildWeb()} never enabled WebView multi-window
 * support, so {@code window.open()} had no real window to land in — the
 * popup handler either dropped the popup or opened a throwaway WebView /
 * a detached tab, destroying the related child browsing context. OAuth
 * flows (window.opener, postMessage, window.close()) then stranded: the
 * consent callback returned to a black page instead of the signed-in
 * site.
 *
 * <p>THE LAW (v1.4.29): every popup rides a REAL related child WebView
 * (WebView.WebViewTransport). This policy gates each child navigation:
 *
 * <ul>
 *   <li>recognized Google/OAuth authorization and redirect chains open
 *       THROUGH the blocker (no prompt — the user tapped "Sign in with
 *       Google" and the consent screen must simply appear);</li>
 *   <li>ordinary popups PROMPT (allow / always allow / block) — with the
 *       prompt text built by {@link #displayTarget}, which NEVER carries
 *       a query or fragment (authorization codes, state and tokens stay
 *       out of the UI and out of every log);</li>
 *   <li>unsafe schemes (javascript:, file:, content:, data:, intent:,
 *       anything that is not http/https) are DENIED outright;</li>
 *   <li>the "always allow" site pref and an off blocker open popups
 *       directly, same as before.</li>
 * </ul>
 *
 * <p>No Android types: this file must compile and run under a bare
 * javac/java for the release gates.
 */
public final class PopupPolicy {

    /** run the flow in the real child window (attach + load). */
    public static final int OPEN = 0;
    /** ordinary popup: ask the user first (secret-free prompt). */
    public static final int PROMPT = 1;
    /** unsafe scheme / nested popup: close the child, load nothing. */
    public static final int DENY = 2;

    private PopupPolicy() {}

    /** the only navigations a popup child may ever carry. http/https are
     *  the OAuth surface; about:blank is window.open's empty starter
     *  document (the flow then navigates or document.writes into it). */
    public static boolean isSafeScheme(String url) {
        if (url == null || url.isEmpty()) return false;
        String u = url.trim();
        if (u.equalsIgnoreCase("about:blank")) return true;
        return u.regionMatches(true, 0, "https://", 0, 8)
                || u.regionMatches(true, 0, "http://", 0, 7);
    }

    /** a recognized Google/OAuth AUTHORIZATION target (the hop that
     *  leaves the relying party for the identity provider): the classic
     *  authorize-query shape (client_id TOGETHER WITH redirect_uri or
     *  response_type — every OAuth2/OIDC authorize endpoint; or a SAML
     *  redirect-binding SAMLRequest) or a well-known IdP entry point.
     *  Exact param-NAME matching (lowercased) — never a substring. */
    public static boolean isAuthPopup(String url) {
        if (url == null || url.isEmpty()) return false;
        String q = urlQueryOf(url);
        if (qHasAny(q, "samlrequest")
                || (qHasAny(q, "client_id")
                    && (qHasAny(q, "redirect_uri") || qHasAny(q, "response_type")))) {
            return true;
        }
        // the single-use RESPONSE lands (the redirect chain's callback:
        // ?code=..., &error=..., #id_token=...) also pass through
        if (isAuthResponse(url)) return true;
        return isIdpEntry(url);
    }

    /** an auth RESPONSE / single-use landing: the query or the fragment
     *  carries OAuth2/OIDC response params or the SAML response pair.
     *  Bias is deliberate: a false positive skips ONE prompt (cosmetic),
     *  a false negative re-prompts mid-handshake (kills the sign-in). */
    public static boolean isAuthResponse(String url) {
        String[] names = {"code", "error", "error_code", "error_description",
                "id_token", "access_token", "token", "samlresponse",
                "relaystate", "nonce", "iss"};
        return qHasAny(urlQueryOf(url), names) || qHasAny(urlRefOf(url), names);
    }

    /** well-known identity-provider entry points (host- or path-pinned —
     *  a bare base domain never qualifies: github.com/anything is NOT an
     *  authorize hop, github.com/login/oauth/authorize is). */
    public static boolean isIdpEntry(String url) {
        String rest = schemeless(url);
        if (rest.isEmpty()) return false;
        String hostPort = rest;
        String path = "/";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            hostPort = rest.substring(0, slash);
            path = rest.substring(slash);
        }
        String host = hostPort.toLowerCase();
        int at = host.lastIndexOf('@');          // userinfo never matches
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        // host-exact IdP consoles (the whole host IS the login surface)
        if (host.equals("accounts.google.com")
                || host.equals("appleid.apple.com")
                || host.equals("login.microsoftonline.com")
                || host.equals("login.live.com")
                || host.equals("accounts.youtube.com")) {
            return true;
        }
        // vendor login-cloud suffixes
        if ((host.endsWith(".auth0.com") && host.length() > ".auth0.com".length())
                || (host.endsWith(".okta.com") && host.length() > ".okta.com".length())) {
            return true;
        }
        // path-pinned OAuth entry points on shared hosts
        String p = path.toLowerCase();
        return (host.equals("github.com") && p.startsWith("/login/oauth"))
                || (host.equals("gitlab.com") && p.startsWith("/oauth"))
                || (host.equals("bitbucket.org") && p.startsWith("/site/oauth"))
                || ((host.equals("twitter.com") || host.equals("api.twitter.com"))
                    && p.startsWith("/oauth"))
                || ((host.equals("facebook.com") || host.equals("www.facebook.com"))
                    && p.startsWith("/dialog/oauth"))
                || (host.equals("www.linkedin.com") && p.startsWith("/oauth"))
                || (host.equals("slack.com") && p.startsWith("/oauth"));
    }

    /**
     * THE SECRET-FREE DISPLAY FORM: {@code scheme://host[:port]/path} —
     * the query and the fragment (authorization codes, state, tokens,
     * SAML payloads) NEVER surface in a prompt or a log line, and any
     * userinfo is stripped (a spoof vector). Unparseable input collapses
     * to a constant, never to the raw string.
     */
    public static String displayTarget(String url) {
        if (url == null || url.isEmpty()) return "(link)";
        String u = url.trim();
        if (u.equalsIgnoreCase("about:blank")) return "about:blank";
        int cut = u.length();
        int q = u.indexOf('?');
        if (q >= 0) cut = q;
        int h = u.indexOf('#');
        if (h >= 0 && h < cut) cut = h;
        String base = u.substring(0, cut);
        int scheme = base.indexOf("://");
        if (scheme < 0) return "(link)";
        int auth = base.indexOf('/', scheme + 3);
        String hostPort = auth < 0 ? base.substring(scheme + 3)
                                  : base.substring(scheme + 3, auth);
        String path = auth < 0 ? "" : base.substring(auth);
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) hostPort = hostPort.substring(at + 1);
        if (hostPort.isEmpty()) return "(link)";
        return base.substring(0, scheme + 3) + hostPort + path;
    }

    /**
     * the gate. Order: unsafe schemes die first; a disabled blocker or the
     * site's "always allow" pref opens popups directly; recognized
     * auth/authorization chains open THROUGH the blocker; everything else
     * prompts. (A prompt is never auto-answered — ordinary automatic
     * popups cannot force content on the user.)
     */
    public static int decide(String url, boolean siteAlwaysAllow, boolean blockEnabled) {
        if (!isSafeScheme(url)) return DENY;
        if (!blockEnabled) return OPEN;
        if (siteAlwaysAllow) return OPEN;
        if (isAuthPopup(url)) return OPEN;
        return PROMPT;
    }

    // ------------------------------------------------------------ parsing

    /** the query string (between '?' and '#'), "" when absent. */
    static String urlQueryOf(String url) {
        try {
            if (url == null) return "";
            int q = url.indexOf('?');
            if (q < 0) return "";
            int h = url.indexOf('#', q + 1);
            return h > q ? url.substring(q + 1, h) : url.substring(q + 1);
        } catch (Throwable t) { return ""; }
    }

    /** the fragment (after '#'), "" when absent. */
    static String urlRefOf(String url) {
        try {
            if (url == null) return "";
            int h = url.indexOf('#');
            return h >= 0 && h < url.length() - 1 ? url.substring(h + 1) : "";
        } catch (Throwable t) { return ""; }
    }

    /** exact param-NAME match (lowercased) against a query or fragment —
     *  never a substring: promo_code= or ?error-page= must not trip the
     *  auth detectors. */
    static boolean qHasAny(String q, String... names) {
        if (q == null || q.isEmpty() || names == null || names.length == 0) return false;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String n = (eq >= 0 ? pair.substring(0, eq) : pair).trim().toLowerCase();
            for (String want : names) if (n.equals(want)) return true;
        }
        return false;
    }

    /** everything after scheme:// ("" for schemeless input). */
    private static String schemeless(String url) {
        if (url == null) return "";
        int s = url.indexOf("://");
        return s >= 0 ? url.substring(s + 3) : "";
    }
}
