package com.bolt.browser.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import android.webkit.JavascriptInterface;

import com.bolt.browser.BoltApp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v1.3.24 — Bolt's on-device WebAuthn authenticator (the passkey backend).
 *
 * WHY THIS EXISTS: on Android <= 13, Chromium's WebView WebAuthn routes
 * ceremonies through Play services' FIDO2 Browser APIs, which Google serves
 * only to individually-verified browsers (Chromium source,
 * components/webauthn/android). A sideloaded browser can never pass that
 * gate, so navigator.credentials.create()/get() always fail — GitHub's
 * "partial passkey support" + "Authentication failed". This class makes
 * bolt itself the authenticator: passkeys are generated and stored on THIS
 * device (AndroidKeyStore ES256 keys, hardware-backed where the device
 * supports it), ceremonies are answered locally, and the webauthn_compat.js
 * layer (injected at document start) presents the results to pages with a
 * fully-shaped PublicKeyCredential surface.
 *
 * PROTOCOL (window.BoltWebAuthn, via addJavascriptInterface — present at
 * document start in every non-incognito document):
 *   isAvailable() -> boolean
 *   createPasskey(requestJson) -> responseJson
 *   getPasskey(requestJson)    -> responseJson
 *   cancel()                   -> void (aborts a pending consent dialog)
 * Response JSON: {"ok":true,"result":{...}} | {"ok":false,
 *                 "error":{"name":"NotAllowedError","message":"..."}}
 * The @JavascriptInterface methods run on the WebView's JavaBridge thread
 * and BLOCK there while the user answers the consent dialog — by design
 * (the page's ceremony promise stays pending meanwhile); it is a dedicated
 * thread, never the UI thread.
 *
 * HONEST LIMITS: user verification is the consent dialog (plus Android's
 * app sandbox / lockscreen protecting storage at rest) — the UV flag means
 * "the device user approved", not "a biometric ran". Keys never leave the
 * AndroidKeyStore; passkeys die with an uninstall (no cloud sync) — keep a
 * second sign-in method registered at each relying party, the same advice
 * that applies to any device-bound passkey.
 */
public final class BoltAuthenticator {

    private static final String TAG = "BoltPasskey";
    private static final String STORE = "passkeys.json";
    private static final String ALIAS_PREFIX = "bolt-passkey-";
    private static final Object IO_LOCK = new Object();
    private static final SecureRandom RNG = new SecureRandom();

    /** one ceremony at a time (a second concurrent call is rejected). */
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    /** released by cancel() / dialog buttons / timeout; null when idle. */
    private static volatile CountDownLatch consentLatch;

    /** cached credential records (loaded once, refreshed on every save). */
    private static volatile List<JSONObject> creds;
    private static volatile byte[] aaguid;

    private final Context ctx;

    public BoltAuthenticator(Context c) {
        this.ctx = c.getApplicationContext();
    }

    /** Internal ceremony error carrying the WebAuthn error name. */
    private static final class Cer extends RuntimeException {
        final String name;
        Cer(String name, String message) { super(message); this.name = name; }
    }

    // ================================================================ JS surface

    @JavascriptInterface
    public boolean isAvailable() {
        return true;
    }

    @JavascriptInterface
    public String createPasskey(String json) {
        try {
            JSONObject req = new JSONObject(json == null ? "{}" : json);
            return okJson(doCreate(req));
        } catch (Cer e) {
            return fail(e.name, e.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "createPasskey failed", t);
            return fail("NotAllowedError", "create failed: " + t);
        }
    }

    @JavascriptInterface
    public String getPasskey(String json) {
        try {
            JSONObject req = new JSONObject(json == null ? "{}" : json);
            return okJson(doGet(req));
        } catch (Cer e) {
            return fail(e.name, e.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "getPasskey failed", t);
            return fail("NotAllowedError", "get failed: " + t);
        }
    }

    @JavascriptInterface
    public void cancel() {
        CountDownLatch l = consentLatch;
        if (l != null) try { l.countDown(); } catch (Throwable ignored) { }
    }

    /** number of stored passkeys (settings row + status text). */
    public static int count(Context c) {
        synchronized (IO_LOCK) {
            List<JSONObject> all = load(c);
            return all == null ? 0 : all.size();
        }
    }

    /** wipe every stored passkey and its AndroidKeyStore key (settings). */
    public static int clearAll(Context c) {
        int n = 0;
        synchronized (IO_LOCK) {
            try {
                List<JSONObject> all = load(c);
                if (all != null) n = all.size();
            } catch (Throwable ignored) { }
            try {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                Enumeration<String> aliases = ks.aliases();
                List<String> mine = new ArrayList<>();
                while (aliases.hasMoreElements()) {
                    String a = aliases.nextElement();
                    if (a != null && a.startsWith(ALIAS_PREFIX)) mine.add(a);
                }
                for (String a : mine) {
                    try { ks.deleteEntry(a); } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }
            try { new File(c.getFilesDir(), STORE).delete(); } catch (Throwable ignored) { }
            creds = null;
            aaguid = null;
        }
        return n;
    }

    // ================================================================ create

    private JSONObject doCreate(JSONObject req) throws Exception {
        String origin = req.optString("origin", "");
        String rpId = req.optString("rpId", "");
        if (rpId.isEmpty()) rpId = hostOf(origin);
        validateRp(origin, rpId);

        String challenge = req.optString("challenge", "");
        if (challenge.isEmpty()) throw new Cer("TypeError", "missing challenge");
        JSONObject user = req.optJSONObject("user");
        if (user == null || user.optString("id", "").isEmpty()) {
            throw new Cer("TypeError", "missing user");
        }

        // excludeCredentials: spec InvalidStateError when this passkey is
        // already registered (sites rely on it for "passkey already exists").
        JSONArray excl = req.optJSONArray("excludeCredentials");
        if (excl != null) {
            for (int i = 0; i < excl.length(); i++) {
                String id = excl.optString(i, "");
                if (!id.isEmpty() && findByRpAndId(rpId, id) != null) {
                    throw new Cer("InvalidStateError",
                            "a passkey already exists for this site");
                }
            }
        }

        if (!BUSY.compareAndSet(false, true)) {
            throw new Cer("NotAllowedError", "a ceremony is already in progress");
        }
        try {
            String what = "Create a passkey for " + rpId
                    + (user.optString("name", "").isEmpty()
                        ? "" : ("\nAccount: " + user.optString("name", "")));
            if (!consent(what, req.optLong("timeout", 180000L))) {
                throw new Cer("NotAllowedError", "cancelled");
            }

            // fresh credential id + AndroidKeyStore ES256 key pair
            byte[] credId = new byte[32];
            RNG.nextBytes(credId);
            String idB64 = b64uStr(credId);
            String alias = ALIAS_PREFIX + hex(credId);
            ECPublicKey pub = generateKey(alias);

            // authData = rpIdHash | flags UP|UV|AT | signCount 0 | aaguid |
            //             credIdLen | credId | COSE key
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
            auth.write(0x45);                       // UP | UV | AT
            auth.write(new byte[]{0, 0, 0, 0});     // signCount at creation
            auth.write(aaguid());
            byte[] cose = coseKey(pub);
            auth.write(be16(credId.length));
            auth.write(credId);
            auth.write(cose);
            byte[] authData = auth.toByteArray();

            // clientDataJSON
            JSONObject cd = new JSONObject();
            cd.put("type", "webauthn.create");
            cd.put("challenge", challenge);
            cd.put("origin", origin);
            cd.put("crossOrigin", false);
            byte[] clientDataJSON = cd.toString().getBytes(StandardCharsets.UTF_8);

            // attestationObject (CBOR): {"fmt":"none","attStmt":{},"authData":bstr}
            byte[] attObj = cborAttestation(authData);

            // persist
            JSONObject rec = new JSONObject();
            rec.put("id", idB64);
            rec.put("alias", alias);
            rec.put("rpId", rpId);
            rec.put("userId", user.optString("id", ""));
            rec.put("userName", user.optString("name", ""));
            rec.put("userDisplayName", user.optString("displayName",
                    user.optString("name", "")));
            rec.put("createdAt", System.currentTimeMillis());
            rec.put("signCount", 0);
            synchronized (IO_LOCK) {
                List<JSONObject> all = load(ctx);
                all.add(rec);
                save(ctx, all);
            }
            try { Diag.log(ctx, "passkey create ok rp=" + rpId
                    + " user=" + user.optString("name", "")); } catch (Throwable ignored) { }

            JSONObject result = new JSONObject();
            result.put("id", idB64);
            result.put("rawId", idB64);
            result.put("type", "public-key");
            result.put("authenticatorAttachment", "platform");
            result.put("clientExtensionResults", new JSONObject());
            JSONObject resp = new JSONObject();
            resp.put("attestationObject", b64uStr(attObj));
            resp.put("clientDataJSON", b64uStr(clientDataJSON));
            resp.put("transports", new JSONArray().put("internal"));
            // v1.3.26: values backing the MODERN response methods
            // (getPublicKeyAlgorithm / getPublicKey / getAuthenticatorData) —
            // relying parties' client libraries (SimpleWebAuthn, newer
            // @github/webauthn-json consumers) read them via method calls;
            // webauthn_compat.js turns these fields into real methods.
            resp.put("publicKeyAlgorithm", -7);              // ES256 — the only alg generated
            resp.put("publicKey", b64uStr(cose));            // the COSE EC2 key bytes
            resp.put("authenticatorData", b64uStr(authData)); // authData bytes
            result.put("response", resp);
            return result;
        } finally {
            BUSY.set(false);
        }
    }

    // ================================================================ get

    private JSONObject doGet(JSONObject req) throws Exception {
        String origin = req.optString("origin", "");
        String rpId = req.optString("rpId", "");
        if (rpId.isEmpty()) rpId = hostOf(origin);
        validateRp(origin, rpId);
        String challenge = req.optString("challenge", "");
        if (challenge.isEmpty()) throw new Cer("TypeError", "missing challenge");

        // pick candidates (short lock — never hold it across the dialog)
        List<JSONObject> matches = new ArrayList<>();
        synchronized (IO_LOCK) {
            List<JSONObject> all = load(ctx);
            JSONArray allow = req.optJSONArray("allowCredentials");
            if (allow != null && allow.length() > 0) {
                for (JSONObject c : all) {
                    if (!rpId.equals(c.optString("rpId", ""))) continue;
                    String id = c.optString("id", "");
                    for (int j = 0; j < allow.length(); j++) {
                        if (id.equals(allow.optString(j, ""))) { matches.add(c); break; }
                    }
                }
            } else {
                // discoverable (resident) credentials: all for this rpId
                for (JSONObject c : all) {
                    if (rpId.equals(c.optString("rpId", ""))) matches.add(c);
                }
            }
        }
        if (matches.isEmpty()) {
            // v1.3.25: this is the "Authentication failed" the field saw. A
            // passkey sign-in asks THIS device's authenticator for a
            // registered credential; none exists yet -> the webauthn spec
            // says reject with NotAllowedError, and the relying party (GitHub)
            // renders that as a bare "Authentication failed" banner with zero
            // guidance. The ceremony is not broken — the user just has to
            // REGISTER a bolt passkey first (password sign-in -> the site's
            // security settings -> add a passkey). Surface that explanation
            // as a dialog at the exact moment it is needed, instead of a
            // silent failure; still reject afterwards so the page flow stays
            // spec-correct.
            try { Diag.log(ctx, "passkey get: no credential for rp=" + rpId
                    + " (not registered yet)"); } catch (Throwable ignored) { }
            if (!BUSY.compareAndSet(false, true)) {
                throw new Cer("NotAllowedError", "a ceremony is already in progress");
            }
            try {
                noCredentialsHelp(rpId);
            } finally {
                BUSY.set(false);
            }
            throw new Cer("NotAllowedError", "no passkey available for this site");
        }

        if (!BUSY.compareAndSet(false, true)) {
            throw new Cer("NotAllowedError", "a ceremony is already in progress");
        }
        try {
            // choose the credential (single -> confirm; several -> picker)
            String pickedId;
            if (matches.size() == 1) {
                JSONObject pick = matches.get(0);
                String what = "Sign in to " + rpId + " with a passkey"
                        + (pick.optString("userName", "").isEmpty()
                            ? "" : ("\nAccount: " + pick.optString("userName", "")));
                if (!consent(what, req.optLong("timeout", 180000L))) {
                    throw new Cer("NotAllowedError", "cancelled");
                }
                pickedId = pick.optString("id", "");
            } else {
                CharSequence[] labels = new CharSequence[matches.size()];
                for (int i = 0; i < matches.size(); i++) {
                    JSONObject c = matches.get(i);
                    labels[i] = c.optString("userName", "(unnamed)")
                            + "  —  " + c.optString("rpId", "");
                }
                int chosen = choose(labels, req.optLong("timeout", 180000L));
                if (chosen < 0 || chosen >= matches.size()) {
                    throw new Cer("NotAllowedError", "cancelled");
                }
                pickedId = matches.get(chosen).optString("id", "");
            }

            // build the assertion (short lock again — state may have changed
            // while the dialog was up)
            byte[] authData;
            byte[] clientDataJSON;
            byte[] sig;
            synchronized (IO_LOCK) {
                List<JSONObject> all = load(ctx);
                JSONObject pick = null;
                for (JSONObject c : all) {
                    if (pickedId.equals(c.optString("id", ""))) { pick = c; break; }
                }
                if (pick == null) {
                    throw new Cer("NotAllowedError", "passkey vanished mid-ceremony");
                }
                long nextCount = pick.optLong("signCount", 0) + 1;
                ByteArrayOutputStream auth = new ByteArrayOutputStream();
                auth.write(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
                auth.write(0x05);                   // UP | UV
                auth.write(int32((int) nextCount));
                authData = auth.toByteArray();

                JSONObject cd = new JSONObject();
                cd.put("type", "webauthn.get");
                cd.put("challenge", challenge);
                cd.put("origin", origin);
                cd.put("crossOrigin", false);
                clientDataJSON = cd.toString().getBytes(StandardCharsets.UTF_8);

                // signature = ECDSA-SHA256(authData || SHA256(clientDataJSON))
                ByteArrayOutputStream signed = new ByteArrayOutputStream();
                signed.write(authData);
                signed.write(sha256(clientDataJSON));
                sig = sign(pick.optString("alias", ""), signed.toByteArray());

                pick.put("signCount", nextCount);
                save(ctx, all);
            }
            try { Diag.log(ctx, "passkey get ok rp=" + rpId); } catch (Throwable ignored) { }

            // rebuild the record for the response (userHandle etc.)
            JSONObject pickInfo = findByRpAndId(rpId, pickedId);
            String userHandle = pickInfo == null ? "" : pickInfo.optString("userId", "");

            JSONObject result = new JSONObject();
            result.put("id", pickedId);
            result.put("rawId", pickedId);
            result.put("type", "public-key");
            result.put("authenticatorAttachment", "platform");
            result.put("clientExtensionResults", new JSONObject());
            JSONObject resp = new JSONObject();
            resp.put("authenticatorData", b64uStr(authData));
            resp.put("clientDataJSON", b64uStr(clientDataJSON));
            resp.put("signature", b64uStr(sig));
            // v1.3.25: userHandle only when non-empty. The spec requires it
            // for discoverable credentials (it is the user id the RP sent at
            // create time); an EMPTY string would be shipped to the page as a
            // zero-byte buffer and fail the RP's user lookup — "Authentication
            // failed" with a perfectly good signature. Store corruption or a
            // legacy record must degrade to null, never to empty bytes.
            if (userHandle != null && !userHandle.isEmpty()) {
                resp.put("userHandle", userHandle);
            } else {
                resp.put("userHandle", JSONObject.NULL);
            }
            result.put("response", resp);
            return result;
        } finally {
            BUSY.set(false);
        }
    }

    // ================================================================ consent UI

    /** true = user tapped Continue. Blocks the JavaBridge thread. */
    private boolean consent(String message, long timeoutMs) {
        return askDialog(message, null, timeoutMs) >= 0;
    }

    /** picker: selected index, -1 = cancelled. Blocks the JavaBridge thread. */
    private int choose(CharSequence[] labels, long timeoutMs) {
        return askDialog("Choose a passkey", labels, timeoutMs);
    }

    /**
     * v1.3.25 — informational dialog for the "sign in with a passkey, but no
     * passkey is registered on this device" case. Explains the two-step fix
     * (password sign-in once, then add a passkey in the site's security
     * settings) and the honest limit (passkeys from other browsers/devices
     * can't be used — bolt has no access to their credential stores, and a
     * cross-device QR ceremony is not possible inside a WebView). Blocks the
     * JavaBridge thread exactly like a consent dialog, so the page's ceremony
     * promise stays pending until the user reads and dismisses it.
     */
    private void noCredentialsHelp(String rpId) {
        final Activity a = BoltApp.currentActivity();
        if (a == null || a.isFinishing()
                || (Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) {
            return; // backgrounded / no UI — fall through to the plain rejection
        }
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            a.runOnUiThread(() -> {
                try {
                    new AlertDialog.Builder(a)
                            .setTitle("No passkey for " + rpId + " yet")
                            .setMessage("Bolt has no passkey for this site on this device.\n\n"
                                    + "To use passkeys here, register one first:\n"
                                    + "1. Sign in once with your password.\n"
                                    + "2. Open the site\u2019s security settings and add a passkey.\n\n"
                                    + "After that, passkey sign-in works in Bolt.\n"
                                    + "Passkeys saved in other browsers or on other devices "
                                    + "can\u2019t be used here.")
                            .setPositiveButton(android.R.string.ok,
                                    (d, w) -> latch.countDown())
                            .setOnCancelListener(d -> latch.countDown())
                            .show();
                } catch (Throwable t) {
                    Log.w(TAG, "no-credentials dialog failed", t);
                    latch.countDown();
                }
            });
            latch.await(60, TimeUnit.SECONDS); // bounded: never wedge the bridge thread
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int askDialog(final String message, final CharSequence[] choices,
                          long timeoutMs) {
        final Activity a = BoltApp.currentActivity();
        if (a == null || a.isFinishing()
                || (Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) {
            return -1;
        }
        final long timeout = Math.max(30_000L,
                Math.min(600_000L, timeoutMs <= 0 ? 180_000L : timeoutMs));
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger answer = new AtomicInteger(-2); // -2 = no button pressed
        final AlertDialog[] holder = new AlertDialog[1];
        consentLatch = latch;
        try {
            a.runOnUiThread(() -> {
                try {
                    AlertDialog.Builder b = new AlertDialog.Builder(a)
                            .setTitle("Bolt passkey")
                            .setPositiveButton("Continue", (d, w) -> {
                                answer.set(1);
                                latch.countDown();
                            })
                            .setNegativeButton("Cancel", (d, w) -> {
                                answer.set(-1);
                                latch.countDown();
                            })
                            .setOnCancelListener(d -> {
                                answer.set(-1);
                                latch.countDown();
                            });
                    if (choices != null) {
                        b.setSingleChoiceItems(choices, 0, null);
                    } else {
                        b.setMessage(message);
                    }
                    holder[0] = b.show();
                } catch (Throwable t) {
                    Log.w(TAG, "consent dialog failed", t);
                    latch.countDown();
                }
            });
            boolean pressed = latch.await(timeout, TimeUnit.MILLISECONDS);
            a.runOnUiThread(() -> {
                try {
                    if (holder[0] != null && holder[0].isShowing()) holder[0].dismiss();
                } catch (Throwable ignored) { }
            });
            if (!pressed) return -1;                  // timed out
            if (choices != null) {
                if (answer.get() != 1) return -1;     // cancelled
                try {
                    int sel = holder[0].getListView().getCheckedItemPosition();
                    return sel >= 0 ? sel : 0;
                } catch (Throwable t) {
                    return 0;
                }
            }
            return answer.get() == 1 ? 0 : -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            consentLatch = null;
        }
    }

    // ================================================================ crypto

    private ECPublicKey generateKey(String alias) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
        kpg.initialize(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        KeyPair kp = kpg.generateKeyPair();
        return (ECPublicKey) kp.getPublic();
    }

    private byte[] sign(String alias, byte[] data) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        PrivateKey pk = (PrivateKey) ks.getKey(alias, null);
        if (pk == null) throw new Cer("NotAllowedError", "key missing for stored passkey");
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(pk);
        s.update(data);
        return s.sign(); // DER Ecdsa-Sig-Value — what CTAP2 authenticators emit
    }

    /** COSE EC2 key map: {1:2, 3:-7, -1:1, -2:x, -3:y} (ES256 / P-256). */
    private static byte[] coseKey(ECPublicKey pub) {
        byte[] x = bigInt32(pub.getW().getAffineX());
        byte[] y = bigInt32(pub.getW().getAffineY());
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        cborMapHeader(o, 5);
        cborUint(o, 1); cborUint(o, 2);        // kty: EC2
        cborUint(o, 3); cborNegint(o, 7);      // alg: ES256 (-7)
        cborNegint(o, 1); cborUint(o, 1);      // crv: P-256 (1)
        cborNegint(o, 2); cborBstr(o, x);      // x
        cborNegint(o, 3); cborBstr(o, y);      // y
        return o.toByteArray();
    }

    /** attestationObject CBOR: {"fmt":"none","attStmt":{},"authData":bstr}. */
    private static byte[] cborAttestation(byte[] authData) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        cborMapHeader(o, 3);
        cborTstr(o, "fmt"); cborTstr(o, "none");
        cborTstr(o, "attStmt"); cborMapHeader(o, 0);
        cborTstr(o, "authData"); cborBstr(o, authData);
        return o.toByteArray();
    }

    // ---- minimal CBOR writer (major types 0-3, 5) ----
    private static void cborHead(ByteArrayOutputStream o, int major, long val) {
        int m = major << 5;
        if (val < 24) { o.write(m | (int) val); }
        else if (val <= 0xFF) { o.write(m | 24); o.write((int) val); }
        else if (val <= 0xFFFF) {
            o.write(m | 25); o.write((int) (val >>> 8)); o.write((int) (val & 0xFF));
        } else if (val <= 0xFFFFFFFFL) {
            o.write(m | 26);
            o.write((int) (val >>> 24)); o.write((int) (val >>> 16) & 0xFF);
            o.write((int) (val >>> 8) & 0xFF); o.write((int) (val & 0xFF));
        } else {
            o.write(m | 27);
            for (int i = 7; i >= 0; i--) o.write((int) ((val >>> (8 * i)) & 0xFF));
        }
    }
    private static void cborUint(ByteArrayOutputStream o, long v) { cborHead(o, 0, v); }
    private static void cborNegint(ByteArrayOutputStream o, long v) { cborHead(o, 1, v - 1); }
    private static void cborBstr(ByteArrayOutputStream o, byte[] b) {
        cborHead(o, 2, b.length);
        o.write(b, 0, b.length);
    }
    private static void cborTstr(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        cborHead(o, 3, b.length);
        o.write(b, 0, b.length);
    }
    private static void cborMapHeader(ByteArrayOutputStream o, int n) { cborHead(o, 5, n); }

    // ================================================================ storage

    /** per-install AAGUID (persisted in the store; regenerated after wipe). */
    private byte[] aaguid() {
        if (aaguid == null) {
            byte[] g = new byte[16];
            String hex = null;
            try {
                JSONObject root = readRoot(ctx);
                if (root != null) hex = root.optString("aaguid", "");
            } catch (Throwable ignored) { }
            boolean parsed = false;
            if (hex != null && hex.length() == 32) {
                try {
                    for (int i = 0; i < 16; i++) {
                        g[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                    }
                    parsed = true;
                } catch (Throwable ignored) { }
            }
            if (!parsed) RNG.nextBytes(g);
            aaguid = g;
        }
        return aaguid;
    }

    private static JSONObject readRoot(Context c) {
        try {
            File f = new File(c.getFilesDir(), STORE);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    /** caller holds IO_LOCK. */
    private static List<JSONObject> load(Context c) {
        if (creds != null) return creds;
        List<JSONObject> out = new ArrayList<>();
        JSONObject root = readRoot(c);
        if (root != null) {
            JSONArray a = root.optJSONArray("creds");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) out.add(o);
                }
            }
            String hex = root.optString("aaguid", "");
            if (hex.length() == 32 && aaguid == null) {
                try {
                    byte[] g = new byte[16];
                    for (int i = 0; i < 16; i++) {
                        g[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                    }
                    aaguid = g;
                } catch (Throwable ignored) { }
            }
        }
        creds = out;
        return out;
    }

    /** caller holds IO_LOCK. */
    private static void save(Context c, List<JSONObject> all) {
        try {
            if (aaguid == null) {
                byte[] g = new byte[16];
                RNG.nextBytes(g);
                aaguid = g;
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("aaguid", hex(aaguid));
            root.put("creds", new JSONArray(all));
            File f = new File(c.getFilesDir(), STORE);
            File tmp = new File(c.getFilesDir(), STORE + ".tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(root.toString().getBytes(StandardCharsets.UTF_8));
            fo.close();
            if (!tmp.renameTo(f)) {
                FileOutputStream fd = new FileOutputStream(f);
                fd.write(root.toString().getBytes(StandardCharsets.UTF_8));
                fd.close();
                tmp.delete();
            }
            creds = all;
        } catch (Throwable t) {
            Log.w(TAG, "passkey store write failed", t);
        }
    }

    private JSONObject findByRpAndId(String rpId, String id) {
        synchronized (IO_LOCK) {
            for (JSONObject c : load(ctx)) {
                if (rpId.equals(c.optString("rpId", "")) && id.equals(c.optString("id", ""))) {
                    return c;
                }
            }
        }
        return null;
    }

    // ================================================================ helpers

    private static String okJson(JSONObject result) {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("result", result);
            return out.toString();
        } catch (Throwable t) {
            return fail("NotAllowedError", "encode failed");
        }
    }

    private static String fail(String name, String message) {
        try {
            JSONObject e = new JSONObject();
            e.put("name", name);
            e.put("message", message);
            JSONObject out = new JSONObject();
            out.put("ok", false);
            out.put("error", e);
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":{\"name\":\"NotAllowedError\","
                    + "\"message\":\"failed\"}}";
        }
    }

    /** origin host must equal rpId or be a subdomain of it (spec rule). */
    private static void validateRp(String origin, String rpId) {
        if (origin == null || !origin.startsWith("https://")) {
            throw new Cer("NotAllowedError", "passkeys need a secure page origin");
        }
        String host = hostOf(origin);
        if (host.isEmpty() || rpId == null || rpId.isEmpty()) {
            throw new Cer("NotAllowedError", "invalid rpId");
        }
        if (!host.equals(rpId) && !host.endsWith("." + rpId)) {
            throw new Cer("NotAllowedError", "rpId does not match this page's origin");
        }
    }

    private static String hostOf(String origin) {
        if (origin == null) return "";
        try {
            java.net.URL u = new java.net.URL(origin);
            String h = u.getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Throwable t) {
            throw new Cer("NotAllowedError", "sha256 unavailable");
        }
    }

    private static byte[] bigInt32(BigInteger v) {
        byte[] raw = v.toByteArray();   // may carry a leading zero / be short
        byte[] out = new byte[32];
        if (raw.length >= 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] be16(int v) { return new byte[]{ (byte) (v >>> 8), (byte) v }; }
    private static byte[] int32(int v) {
        return new byte[]{ (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /** base64url, no padding (API-24-safe via android.util.Base64). */
    private static String b64uStr(byte[] in) {
        return android.util.Base64.encodeToString(in,
                android.util.Base64.URL_SAFE
                        | android.util.Base64.NO_PADDING
                        | android.util.Base64.NO_WRAP);
    }
}
