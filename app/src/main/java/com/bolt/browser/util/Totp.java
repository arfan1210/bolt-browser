package com.bolt.browser.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * v1.3.31 — the TOTP engine behind Bolt's built-in authenticator
 * ("a Google Authenticator inside the browser, so 2FA codes are one tap
 * away right after logging in with Gmail in Bolt").
 *
 * RFC 6238 (TOTP) over RFC 4226 (HOTP):
 *   code = (HMAC(algo, secret, floor(unixTime / period)) mod 10^digits)
 *          truncated to the last `digits` digits, zero-padded.
 *
 * Secrets are stored/entered as RFC 4648 Base32 (A–Z, 2–7, padding ignored,
 * case-insensitive, embedded spaces/dashes tolerated — the way Google, banks
 * and every authenticator app prints them). The otpauth:// URI format used
 * by QR codes is parsed too, so a site's "can't scan?" link can be pasted
 * whole.
 *
 * Verified against the RFC 6238 appendix test vectors (SHA1/SHA256/SHA512,
 * see scripts/test_totp.py which cross-checks this exact class compiled
 * standalone AND the Python reference).
 */
public final class Totp {

    private Totp() {}

    // ---------------------------------------------------------------- base32

    /** RFC 4648 base32 decode (tolerant: spaces, dashes, lower case,
     *  missing/superfluous padding). Returns null when not decodable. */
    public static byte[] base32Decode(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '-' || c == '=' || c == '\n' || c == '\r' || c == '\t') continue;
            char u = Character.toUpperCase(c);
            if ((u < 'A' || u > 'Z') && (u < '2' || u > '7')) return null;  // not base32
            sb.append(u);
        }
        int len = sb.length();
        int outLen = len * 5 / 8;
        byte[] out = new byte[outLen];
        int buffer = 0, bits = 0, o = 0;
        for (int i = 0; i < len; i++) {
            int v = charValue(sb.charAt(i));
            if (v < 0) return null;
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                if (o < outLen) out[o++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        // a real base32 string is 40%+ payload; a garbage string usually decodes empty
        return o == outLen && outLen > 0 ? out : (outLen > 0 ? out : null);
    }

    private static int charValue(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= '2' && c <= '7') return c - '2' + 26;
        return -1;
    }

    /** RFC 4648 base32 encode, no padding (v1.3.32: the Google Authenticator
     *  export protobuf carries RAW key bytes — this makes them printable
     *  for storage/otpauth URIs). */
    public static String base32Encode(byte[] in) {
        if (in == null || in.length == 0) return "";
        StringBuilder sb = new StringBuilder((in.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte x : in) {
            buffer = (buffer << 8) | (x & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(B32_CHARS.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) {
            sb.append(B32_CHARS.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    private static final String B32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** heuristic: does this look like a base32 secret (>= 8 decodable chars)? */
    public static boolean looksLikeSecret(String s) {
        if (s == null || s.length() < 8) return false;
        return base32Decode(s) != null && base32Decode(s).length >= 5;
    }

    // ---------------------------------------------------------------- totp

    /** Generate the code for the given absolute time. */
    public static String code(byte[] secret, String algorithm, int digits, int periodSec,
            long atTimeMillis) {
        try {
            long counter = atTimeMillis / 1000L / Math.max(1, periodSec);
            return hotp(secret, algorithm, counter, digits);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Current code right now (period-aligned). */
    public static String now(byte[] secret, String algorithm, int digits, int periodSec) {
        return code(secret, algorithm, digits, periodSec, System.currentTimeMillis());
    }

    /** Seconds remaining before the current period rolls over. */
    public static int secondsLeft(int periodSec, long atTimeMillis) {
        int p = Math.max(1, periodSec);
        return (int) (p - (atTimeMillis / 1000L) % p);
    }

    /** RFC 4226 HOTP with dynamic truncation. */
    private static String hotp(byte[] secret, String algorithm, long counter, int digits) throws Exception {
        String algo = "Hmac" + (algorithm == null ? "SHA1" : algorithm.toUpperCase(Locale.US));
        // validate: only the three RFC algorithms — anything else throws
        if (!"HmacSHA1".equals(algo) && !"HmacSHA256".equals(algo) && !"HmacSHA512".equals(algo)) {
            throw new IllegalArgumentException("bad algorithm " + algorithm);
        }
        Mac mac = Mac.getInstance(algo);
        mac.init(new SecretKeySpec(secret, algo));
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xFF);
            counter >>>= 8;
        }
        byte[] hash = mac.doFinal(msg);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int mod = 1;
        for (int i = 0; i < digits; i++) mod *= 10;
        int otp = binary % mod;
        return String.format(Locale.US, "%0" + digits + "d", otp);
    }

    // ---------------------------------------------------------------- otpauth://

    /** A parsed otpauth:// URI (or manual entry). */
    public static final class OtpSecret {
        public String issuer = "";
        public String account = "";
        public String secret = "";      // base32 text, normalized (upper, no separators)
        public String algorithm = "SHA1";
        public int digits = 6;
        public int period = 30;
    }

    /**
     * Parse an otpauth://totp/... URI (the format behind every 2FA QR code):
     * otpauth://totp/Google:alice@gmail.com?secret=JBSW...&issuer=Google
     * &algorithm=SHA1&digits=6&period=30
     * Returns null when the URI is not a usable TOTP secret carrier.
     */
    public static OtpSecret parseOtpAuth(String uri) {
        try {
            if (uri == null) return null;
            String u = uri.trim();
            if (!u.toLowerCase(Locale.US).startsWith("otpauth://")) return null;
            int schemeEnd = u.indexOf('/', "otpauth://".length());
            if (schemeEnd < 0) return null;
            String type = u.substring("otpauth://".length(), schemeEnd).toLowerCase(Locale.US);
            if (!"totp".equals(type)) return null;           // hotp needs counters — unsupported
            String rest = u.substring(schemeEnd + 1);
            int q = rest.indexOf('?');
            String label = q >= 0 ? rest.substring(0, q) : rest;
            String query = q >= 0 ? rest.substring(q + 1) : "";
            OtpSecret o = new OtpSecret();
            // label: "Issuer:account" (URL-encoded) or bare "account"
            label = urlDecode(label);
            int colon = label.indexOf(':');
            if (colon > 0) {
                o.issuer = label.substring(0, colon).trim();
                o.account = label.substring(colon + 1).trim();
            } else {
                o.account = label.trim();
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = pair.substring(0, eq).toLowerCase(Locale.US);
                String v = urlDecode(pair.substring(eq + 1));
                if ("secret".equals(k)) o.secret = normalizeSecret(v);
                else if ("issuer".equals(k)) { if (!v.isEmpty()) o.issuer = v.trim(); }
                else if ("algorithm".equals(k)) o.algorithm = v.trim().toUpperCase(Locale.US);
                else if ("digits".equals(k)) o.digits = parseIntSafe(v, 6);
                else if ("period".equals(k)) o.period = parseIntSafe(v, 30);
            }
            if (!looksLikeSecret(o.secret)) return null;
            if (o.digits < 6 || o.digits > 8) o.digits = 6;
            if (o.period < 5 || o.period > 120) o.period = 30;
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Build an OtpSecret from a pasted bare setup key (+ optional issuer/
     *  account). Returns null for an undecodable secret. */
    public static OtpSecret fromKey(String key, String issuer, String account) {
        try {
            if (key == null) return null;
            String k = key.trim();
            OtpSecret o;
            if (k.toLowerCase(Locale.US).startsWith("otpauth://")) {
                o = parseOtpAuth(k);
                if (o == null) return null;
            } else {
                o = new OtpSecret();
                o.secret = normalizeSecret(k);
                if (!looksLikeSecret(o.secret)) return null;
            }
            if (issuer != null && !issuer.trim().isEmpty()) o.issuer = issuer.trim();
            if (account != null && !account.trim().isEmpty()) o.account = account.trim();
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** upper-case, strip spaces/dashes/padding — canonical storage form */
    public static String normalizeSecret(String s) {
        if (s == null) return "";
        return s.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.US);
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    /** pretty display: "123 456" / "1234 5678" */
    public static String formatCode(String code) {
        if (code == null) return "------";
        if (code.length() == 6) return code.substring(0, 3) + " " + code.substring(3);
        if (code.length() == 8) return code.substring(0, 4) + " " + code.substring(4);
        return code;
    }

    /** stable display name: "Google · alice@gmail.com" / "alice@gmail.com" */
    public static String displayName(String issuer, String account) {
        boolean hasI = issuer != null && !issuer.isEmpty();
        boolean hasA = account != null && !account.isEmpty();
        if (hasI && hasA) return issuer + " · " + account;
        if (hasI) return issuer;
        if (hasA) return account;
        return "Account";
    }

    /** bytes for the stored normalized base32 secret (null if bad) */
    public static byte[] secretBytes(String normalizedSecret) {
        return base32Decode(normalizedSecret == null ? "" : normalizedSecret);
    }

    /** generate the current code from a stored normalized secret. */
    public static String codeFor(String normalizedSecret, String algorithm, int digits,
            int periodSec, long atTimeMillis) {
        byte[] key = secretBytes(normalizedSecret);
        if (key == null) return null;
        return code(key, algorithm, digits, periodSec, atTimeMillis);
    }

    /** v1.3.32 — export URI (the "Show QR code" action scans this INTO
     *  Google Authenticator / any other app). Label and issuer are fully
     *  percent-encoded per the otpauth key-URI spec (GA's own scanner
     *  decodes them correctly; raw ':'/'@' in labels confuse some apps). */
    public static String toOtpAuthUri(OtpSecret o) {
        try {
            String iss = o.issuer == null ? "" : o.issuer;
            String acc = o.account == null ? "" : o.account;
            String label = (iss.isEmpty() ? "" : enc(iss) + ":") + enc(acc);
            StringBuilder sb = new StringBuilder("otpauth://totp/").append(label)
                    .append("?secret=").append(o.secret);
            if (!iss.isEmpty()) sb.append("&issuer=").append(enc(iss));
            sb.append("&algorithm=").append(o.algorithm == null ? "SHA1" : o.algorithm)
                    .append("&digits=").append(o.digits)
                    .append("&period=").append(o.period);
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s.replace(" ", "%20");
        }
    }

    @Override public String toString() { return "Totp"; }
}
