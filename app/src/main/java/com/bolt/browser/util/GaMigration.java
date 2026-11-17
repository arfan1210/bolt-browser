package com.bolt.browser.util;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.3.32 — parser for Google Authenticator's account-EXPORT QR codes:
 *
 *   otpauth-migration://offline?data=<base64 protobuf>
 *
 * Google ships no sync API and no browser extension for Google
 * Authenticator (its Google-account cloud sync is end-to-end encrypted and
 * closed), so "Transfer accounts → Export accounts" QR codes are the only
 * sanctioned way OUT, and scanning an otpauth:// QR the only way IN. This
 * parser is the OUT half of that bridge — the IN half is the per-entry
 * "Show QR code" action built on {@link QrCore}.
 *
 * Wire format (hand-rolled protobuf — no dependency; schema reverse-
 * engineered by the community, cross-validated here against Aegis's
 * google_auth.proto AND five REAL captured payloads from the
 * scito/extract_otp_secret_keys example set):
 *
 *   MigrationPayload {                 OtpParameters {
 *     1 repeated OtpParameters           1 bytes  secret   ← RAW KEY BYTES
 *     2 int32  version                   2 string name            (not base32 text!)
 *     3 int32  batch_size                3 string issuer
 *     4 int32  batch_index               4 enum   algorithm (0~? 1 SHA1 2 SHA256 3 SHA512 4 MD5)
 *     5 int64  batch_id                  5 enum   digits    (0 unspec 1 six 2 eight)
 *   }                                    6 enum   type      (0 unspec 1 HOTP 2 TOTP)
 *                                        7 int64  counter (HOTP only)
 *   }
 *
 * THE critical trap: `bytes secret` holds the RAW KEY BYTES (field 1 = raw
 * bytes; you must base32-ENCODE them to get the text secret everyone else
 * uses). Some third-party migration generators emit base32 TEXT in that
 * field instead, so the parser sniffs: all-bytes-in-base32-alphabet → text,
 * otherwise raw → base32-encode. Collision odds of a real raw key landing
 * entirely in A-Z2-7: (1/8)^len ≈ 2^-128 for a 16-byte key.
 *
 * HOTP (counter-based) entries are collected but reported, not converted —
 * Bolt's engine is TOTP-only. MD5 entries are skipped for the same reason
 * (wrong codes are worse than no codes).
 */
public final class GaMigration {

    private GaMigration() {}

    /** one export QR, parsed. */
    public static final class Result {
        public List<Totp.OtpSecret> accounts = new ArrayList<>();
        public int skippedHotp = 0;      // counter-based entries (unsupported)
        public int skippedBad = 0;       // undecodable secret / MD5 algorithm
        public int version = 0;
        public int batchSize = 0;        // GA exports up to 10 accounts per QR,
        public int batchIndex = 0;       // so >10 accounts → several QRs
        public long batchId = 0;
        public boolean parseError = false;
    }

    /** Parse a full otpauth-migration:// URI (or a bare base64 blob). */
    public static Result parse(String uri) {
        Result r = new Result();
        try {
            if (uri == null) { r.parseError = true; return r; }
            String u = uri.trim();
            String b64 = u;
            int q = u.indexOf('?');
            if (u.toLowerCase(java.util.Locale.US).startsWith("otpauth-migration://") && q >= 0) {
                b64 = extractDataParam(u.substring(q + 1));
            }
            if (b64 == null || b64.isEmpty()) { r.parseError = true; return r; }
            byte[] payload = base64Decode(b64);
            if (payload == null || payload.length == 0) { r.parseError = true; return r; }
            parsePayload(payload, r);
            if (r.accounts.isEmpty() && r.skippedHotp == 0 && r.skippedBad == 0) {
                r.parseError = true;    // decoded fine but carries nothing
            }
            return r;
        } catch (Throwable t) {
            r.parseError = true;
            return r;
        }
    }

    /** value of the data= query parameter (percent-decoded). */
    private static String extractDataParam(String query) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq);
            if ("data".equals(k)) return percentDecode(pair.substring(eq + 1));
        }
        return null;
    }

    // ------------------------------------------------------------- protobuf

    private static void parsePayload(byte[] b, Result r) {
        int i = 0;
        while (i < b.length) {
            long[] tag = readVarint(b, i);
            if (tag == null) return;
            i = (int) tag[1];
            int field = (int) (tag[0] >>> 3);
            int wire = (int) (tag[0] & 7);
            if (wire == 2) {
                long[] len = readVarint(b, i);
                if (len == null || (int) len[1] + (int) len[0] > b.length) return;
                i = (int) len[1];
                int n = (int) len[0];
                byte[] chunk = new byte[n];
                System.arraycopy(b, i, chunk, 0, n);
                i += n;
                if (field == 1) parseParams(chunk, r);
                // unknown length-delimited fields are skipped (forward compat)
            } else if (wire == 0) {
                long[] v = readVarint(b, i);
                if (v == null) return;
                i = (int) v[1];
                if (field == 2) r.version = (int) v[0];
                else if (field == 3) r.batchSize = (int) v[0];
                else if (field == 4) r.batchIndex = (int) v[0];
                else if (field == 5) r.batchId = v[0];
            } else if (wire == 1) {
                i += 8;                 // fixed64 — skip, stay forward-compatible
            } else if (wire == 5) {
                i += 4;                 // fixed32
            } else {
                return;                 // groups (3/4) never appear here
            }
            if (i < 0 || i > b.length) return;
        }
    }

    private static void parseParams(byte[] b, Result r) {
        byte[] secret = null;
        String name = "", issuer = "";
        int algorithm = 0, digits = 0, type = 0;
        int i = 0;
        while (i < b.length) {
            long[] tag = readVarint(b, i);
            if (tag == null) return;
            i = (int) tag[1];
            int field = (int) (tag[0] >>> 3);
            int wire = (int) (tag[0] & 7);
            if (wire == 2) {
                long[] len = readVarint(b, i);
                if (len == null || (int) len[1] + (int) len[0] > b.length) return;
                i = (int) len[1];
                int n = (int) len[0];
                if (field == 1) {
                    secret = new byte[n];
                    System.arraycopy(b, i, secret, 0, n);
                } else if (field == 2) {
                    name = new String(b, i, n, java.nio.charset.StandardCharsets.UTF_8);
                } else if (field == 3) {
                    issuer = new String(b, i, n, java.nio.charset.StandardCharsets.UTF_8);
                }
                i += n;
            } else if (wire == 0) {
                long[] v = readVarint(b, i);
                if (v == null) return;
                i = (int) v[1];
                if (field == 4) algorithm = (int) v[0];
                else if (field == 5) digits = (int) v[0];
                else if (field == 6) type = (int) v[0];
                // field 7 (counter) only matters for HOTP — unsupported
            } else if (wire == 1) {
                i += 8;
            } else if (wire == 5) {
                i += 4;
            } else {
                return;
            }
            if (i < 0 || i > b.length) return;
        }

        if (type == 1) { r.skippedHotp++; return; }          // counter-based

        String algo = "SHA1";
        if (algorithm == 2) algo = "SHA256";
        else if (algorithm == 3) algo = "SHA512";
        else if (algorithm == 4) { r.skippedBad++; return; } // MD5 — not faked

        int digs;
        if (digits == 2 || digits == 8) digs = 8;
        else if (digits == 6 || digits == 7) digs = digits;
        else digs = 6;                                        // 0/1/unspec → six

        String text = secretToText(secret);
        if (text == null || !Totp.looksLikeSecret(text)) { r.skippedBad++; return; }

        Totp.OtpSecret o = new Totp.OtpSecret();
        o.secret = Totp.normalizeSecret(text);
        o.issuer = issuer == null ? "" : issuer.trim();
        o.account = name == null ? "" : name.trim();
        o.algorithm = algo;
        o.digits = digs;
        o.period = 30;                                        // GA never exports period
        r.accounts.add(o);
    }

    /**
     * The raw bytes → text-secret decision. Real Google exports carry RAW
     * KEY BYTES (base32-encode them); some third-party generators carry the
     * base32 TEXT directly. A byte string consisting exclusively of base32
     * alphabet chars is text — every other byte pattern is a raw key.
     */
    static String secretToText(byte[] secret) {
        if (secret == null || secret.length == 0) return null;
        boolean allBase32 = secret.length >= 8;
        for (byte x : secret) {
            char c = (char) (x & 0xFF);
            if (c >= 0x80) { allBase32 = false; break; }
            char u = Character.toUpperCase(c);
            if ((u >= 'A' && u <= 'Z') || (u >= '2' && u <= '7')) continue;
            allBase32 = false;
            break;
        }
        // a byte string that is entirely base32 alphabet chars IS text
        // (third-party generators); anything else is a raw key → base32 it
        return allBase32
                ? new String(secret, java.nio.charset.StandardCharsets.US_ASCII)
                : Totp.base32Encode(secret);
    }

    // ------------------------------------------------------------- helpers

    /** tolerant varint reader: returns [value, nextIndex] or null. */
    private static long[] readVarint(byte[] b, int i) {
        long v = 0;
        int shift = 0;
        while (i < b.length && shift < 64) {
            byte x = b[i++];
            v |= ((long) (x & 0x7F)) << shift;
            if ((x & 0x80) == 0) return new long[]{v, i};
            shift += 7;
        }
        return null;
    }

    /** percent-decode (%XX) without touching '+'. */
    static String percentDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    int hi = Character.digit(s.charAt(i + 1), 16);
                    int lo = Character.digit(s.charAt(i + 2), 16);
                    if (hi >= 0 && lo >= 0) {
                        out.write((hi << 4) | lo);
                        i += 2;
                        continue;
                    }
                } catch (Throwable ignored) {}
            }
            out.write((byte) c);
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * base64 decode tolerant of: standard AND url-safe alphabets, missing
     * padding, embedded whitespace/newlines. (java.util.Base64 needs API 26;
     * minSdk is 24 — and this way the class also compiles standalone for
     * scripts/test_migration.py.)
     */
    static byte[] base64Decode(String s) {
        if (s == null) return null;
        int acc = 0, bits = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 3 / 4 + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t' || c == '=') continue;
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+' || c == '-') v = 62;
            else if (c == '/' || c == '_') v = 63;
            else return null;
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xFF);
            }
        }
        // up to 2 leftover bits are padding slop — real payloads are 8-bit aligned
        byte[] data = out.toByteArray();
        return data.length == 0 ? null : data;
    }
}
