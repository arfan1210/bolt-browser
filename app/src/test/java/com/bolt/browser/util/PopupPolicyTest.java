package com.bolt.browser.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v1.4.29 — THE POPUP POLICY PROBE (the OAuth black-page corpse). The
 * related child window (WebViewTransport) keeps the opener relationship
 * OAuth lives on; this policy gates every child navigation: recognized
 * Google/OAuth authorization and redirect chains open THROUGH the
 * blocker, ordinary popups prompt (secret-free URL display), unsafe
 * schemes die outright. scripts/test_v1429.py compiles + runs the same
 * PopupPolicy class outside Robolectric (the parse-gate law).
 */
public class PopupPolicyTest {

    @Test
    public void unsafeSchemes_areDenied() {
        assertFalse(PopupPolicy.isSafeScheme("javascript:alert(1)"));
        assertFalse(PopupPolicy.isSafeScheme("file:///etc/passwd"));
        assertFalse(PopupPolicy.isSafeScheme("content://com.evil/steal"));
        assertFalse(PopupPolicy.isSafeScheme("data:text/html,<script>x</script>"));
        assertFalse(PopupPolicy.isSafeScheme("intent://scan/#Intent;end"));
        assertFalse(PopupPolicy.isSafeScheme("blob:https://imgur.com/x"));
        assertFalse(PopupPolicy.isSafeScheme(null));
        assertFalse(PopupPolicy.isSafeScheme(""));
        assertEquals(PopupPolicy.DENY,
                PopupPolicy.decide("javascript:alert(1)", true, false));
    }

    @Test
    public void webSchemes_andTheBlankStarter_areSafe() {
        assertTrue(PopupPolicy.isSafeScheme("https://imgur.com/signin"));
        assertTrue(PopupPolicy.isSafeScheme("http://example.com/"));
        assertTrue(PopupPolicy.isSafeScheme("about:blank"));
    }

    @Test
    public void googleAuthorizeShape_opensThroughTheBlocker() {
        // the classic Google OAuth authorize URL (Imgur's sign-in target)
        String u = "https://accounts.google.com/o/oauth2/v2/auth?client_id="
                + "123-abc.apps.googleusercontent.com&redirect_uri="
                + "https://imgur.com/callback&response_type=code&scope=email";
        assertTrue(PopupPolicy.isAuthPopup(u));
        assertEquals(PopupPolicy.OPEN, PopupPolicy.decide(u, false, true));
    }

    @Test
    public void genericOAuthAuthorizeShape_opensThroughTheBlocker() {
        assertTrue(PopupPolicy.isAuthPopup(
                "https://github.com/login/oauth/authorize?client_id=abc&scope=user"));
        assertTrue(PopupPolicy.isAuthPopup(
                "https://idp.example.com/authorize?client_id=abc&redirect_uri=https://rp/cb"));
        assertTrue(PopupPolicy.isAuthPopup(
                "https://idp.example.com/sso?samlrequest=AAAA"));
    }

    @Test
    public void redirectResponseLandings_passThrough() {
        // the single-use callback shapes (query + fragment dialects)
        assertTrue(PopupPolicy.isAuthPopup(
                "https://imgur.com/callback?code=SECRET_ONE_USE&state=xyz"));
        assertTrue(PopupPolicy.isAuthPopup(
                "https://rp.example.com/cb#error=access_denied"));
        assertTrue(PopupPolicy.isAuthPopup(
                "https://rp.example.com/cb#id_token=eyJhbGci&token_type=Bearer"));
    }

    @Test
    public void idpEntryHosts_areRecognized() {
        assertTrue(PopupPolicy.isAuthPopup("https://accounts.google.com/"));
        assertTrue(PopupPolicy.isAuthPopup("https://appleid.apple.com/auth"));
        assertTrue(PopupPolicy.isAuthPopup("https://login.microsoftonline.com/common"));
        assertTrue(PopupPolicy.isAuthPopup("https://acme.okta.com/oauth2/authorize"));
        assertTrue(PopupPolicy.isAuthPopup("https://acme.auth0.com/authorize?x=1"));
    }

    @Test
    public void ordinaryPopups_promptButNeverAutoOpen() {
        String u = "https://ads.example.net/popunder?promo_code=SPRING";
        assertFalse(PopupPolicy.isAuthPopup(u));   // promo_code is NOT a code param
        assertEquals(PopupPolicy.PROMPT, PopupPolicy.decide(u, false, true));
        // an ordinary page on a login-capable HOST is still ordinary —
        // only the pinned entry paths qualify (github.com/anything is not
        // an authorize hop)
        assertEquals(PopupPolicy.PROMPT,
                PopupPolicy.decide("https://github.com/arfan1210/bolt-browser",
                        false, true));
    }

    @Test
    public void alwaysAllowAndDisabledBlocker_open() {
        String u = "https://ads.example.net/popunder";
        assertEquals(PopupPolicy.OPEN, PopupPolicy.decide(u, true, true));   // site pref
        assertEquals(PopupPolicy.OPEN, PopupPolicy.decide(u, false, false)); // blocker off
    }

    @Test
    public void displayTarget_stripsSecrets_queryFragmentUserinfo() {
        // THE SECRET-FREE LAW: no query, no fragment, no userinfo — the
        // authorization codes, state and tokens never surface in a prompt
        // or a log line
        String d = PopupPolicy.displayTarget(
                "https://user:pass@imgur.com/callback?code=SECRET&state=SECRET2#access_token=SECRET3");
        assertEquals("https://imgur.com/callback", d);
        assertFalse(d.contains("SECRET"));
        assertFalse(d.contains("?"));
        assertFalse(d.contains("#"));
        assertFalse(d.contains("@"));
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth",
                PopupPolicy.displayTarget(
                        "https://accounts.google.com/o/oauth2/v2/auth?client_id=abc"));
        assertEquals("about:blank", PopupPolicy.displayTarget("about:blank"));
        assertEquals("(link)", PopupPolicy.displayTarget(null));
        assertEquals("(link)", PopupPolicy.displayTarget("not a url"));
    }

    @Test
    public void exactParamNameMatching_noSubstringFalsePositives() {
        // promo_code= / ?error-page= must NOT trip the auth detectors
        assertFalse(PopupPolicy.isAuthPopup(
                "https://shop.example/sale?promo_code=SPRING"));
        assertFalse(PopupPolicy.isAuthPopup(
                "https://docs.example/error-page=intro"));
    }
}
