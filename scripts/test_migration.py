#!/usr/bin/env python3
"""v1.3.32 — Google Authenticator migration-payload verification.

Validates util/GaMigration.java (compiled standalone with Totp.java) against:

 1. FIVE REAL captured export payloads (the scito/extract_otp_secret_keys
    example set — genuine otpauth-migration:// URIs incl. multi-account,
    missing-issuer, an HOTP entry and a unicode label). These pin the
    wire format as Google actually emits it.
 2. Synthetic payloads built here with an independent Python protobuf
    encoder (SHA256/8-digit, batch fields, url-safe base64 without padding,
    base32-TEXT secrets as some third-party generators emit, MD5 skip,
    fixed64 unknown-field skipping, garbage → parseError).
 3. End-to-end: the secret imported from a real payload must generate the
    SAME TOTP code as the Python reference for that key.
 4. toOtpAuthUri export encoding (percent-encoded label/issuer).

The one trap this suite exists for: the protobuf `bytes secret` field
carries RAW KEY BYTES (must be base32-ENCODED, not decoded-as-text) —
confirmed by real user data and the fixtures below.

Usage:  python3 scripts/test_migration.py   (needs javac + java on PATH)
"""
import base64
import hashlib
import hmac
import struct
import subprocess
import sys
import os
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "com", "bolt", "browser", "util")
WORK = "/tmp/bolt_migration_test"

fails = []


def check(cond, label):
    print(("  PASS " if cond else "  FAIL ") + label)
    if not cond:
        fails.append(label)


# ---------------------------------------------------------------- real fixtures
# From https://github.com/scito/extract_otp_secret_keys (example_export.txt).
# The first is a real export of the raspberrypi.org demo key.
REAL = [
    # (uri, [(issuer, account, secret)], skipped_hotp, skipped_bad)
    ("otpauth-migration://offline?data=CjUKEPqlBekzoNEukL7qlsjBCDYSDnBpQHJhc3BiZXJyeXBpGg"
     "tyYXNwYmVycnlwaSABKAEwAhABGAEgACjr4JKK%2B%2F%2F%2F%2F%2F8B",
     [("raspberrypi", "pi@raspberrypi", "7KSQL2JTUDIS5EF65KLMRQIIGY")], 0, 0),
    ("otpauth-migration://offline?data=CigKEPqlBekzoNEukL7qlsjBCDYSDnBpQHJhc3BiZXJyeXBpIAEoAT"
     "ACEAEYASAAKLzjp5n4%2F%2F%2F%2F%2FwE%3D",
     [("", "pi@raspberrypi", "7KSQL2JTUDIS5EF65KLMRQIIGY")], 0, 0),
    ("otpauth-migration://offline?data=CigKEPqlBekzoNEukL7qlsjBCDYSDnBpQHJhc3BiZXJyeXBpIAEoAT"
     "ACCjUKEPqlBekzoNEukL7qlsjBCDYSDnBpQHJhc3BiZXJyeXBpGgtyYXNwYmVycnlwaSABKAEwAhABGAEgACiQ"
     "7OOa%2Bf%2F%2F%2F%2F8B",
     [("", "pi@raspberrypi", "7KSQL2JTUDIS5EF65KLMRQIIGY"),
      ("raspberrypi", "pi@raspberrypi", "7KSQL2JTUDIS5EF65KLMRQIIGY")], 0, 0),
    # an HOTP (counter-based) account — must be counted+skipped, not imported
    ("otpauth-migration://offline?data=CiUKEPqlBekzoNEukL7qlsjBCDYSCWhvdHAgZGVtbyABKAEwATgEEAE"
     "YASAAKNuv15j6%2F%2F%2F%2F%2FwE%3D",
     [], 1, 0),
    # unicode label
    ("otpauth-migration://offline?data=CjYKEPqlBekzoNEukL7qlsjBCDYSHGVuY29kaW5nOiDCv8Okw4TDqcOJ"
     "PyAoZGVtbykgASgBMAIQARgBIAAorfCurv%2F%2F%2F%2F%2FAQ%3D%3D",
     [("", "encoding: ¿äÄéÉ? (demo)", "7KSQL2JTUDIS5EF65KLMRQIIGY")], 0, 0),
]

# ---------------------------------------------------------------- python protobuf encoder
def varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def ld(field, payload):     # length-delimited
    return varint((field << 3) | 2) + varint(len(payload)) + payload


def vi(field, v):           # varint
    return varint((field << 3) | 0) + varint(v)


def otp_params(secret_raw, name, issuer, algo=1, digits=1, otype=2, counter=None):
    b = ld(1, secret_raw) + ld(2, name.encode()) + ld(3, issuer.encode()) \
        + vi(4, algo) + vi(5, digits) + vi(6, otype)
    if counter is not None:
        b += vi(7, counter)
    return b


def payload(params, version=1, batch_size=1, batch_index=0, batch_id=1234):
    b = b"".join(ld(1, p) for p in params)
    b += vi(2, version) + vi(3, batch_size) + vi(4, batch_index) + vi(5, batch_id)
    return b


def to_uri(raw, urlsafe=False, pad=True):
    b64 = base64.b64encode(raw).decode()
    if urlsafe:
        b64 = b64.replace("+", "-").replace("/", "_")
    if not pad:
        b64 = b64.rstrip("=")
    return "otpauth-migration://offline?data=" + urllib.parse.quote(b64, safe="")


# ---------------------------------------------------------------- java side

RUNNER = r"""
package com.bolt.browser.util;

public class MigrationRunner {
    public static void main(String[] a) {
        // payload URIs come before "--"; helpers after it
        int sep = -1;
        for (int i = 0; i < a.length; i++) if ("--".equals(a[i])) { sep = i; break; }
        int payloadEnd = (sep == -1) ? a.length : sep;
        for (int i = 0; i < payloadEnd; i++) {
            GaMigration.Result r = GaMigration.parse(a[i]);
            System.out.println("RES " + i + " " + (r.parseError ? "ERR" : "OK")
                    + " " + r.accounts.size() + " " + r.skippedHotp + " " + r.skippedBad
                    + " " + r.version + " " + r.batchSize + " " + r.batchIndex + " " + r.batchId);
            for (Totp.OtpSecret o : r.accounts) {
                System.out.println("ACC " + i + " " + o.issuer + "|" + o.account + "|"
                        + o.secret + "|" + o.algorithm + "|" + o.digits + "|" + o.period);
            }
        }
        for (int i = (sep == -1 ? a.length : sep + 1); i < a.length; i++) {
            String[] p = a[i].split("=", 2);
            if (p.length < 2) continue;
            if ("B32ENC".equals(p[0])) {                       // hex -> base32
                System.out.println("B32ENC " + Totp.base32Encode(hex(p[1])));
            } else if ("STEXT".equals(p[0])) {                 // hex -> text-secret decision
                System.out.println("STEXT " + GaMigration.secretToText(hex(p[1])));
            } else if ("CODE".equals(p[0])) {                  // secret|algo|digits|period|t_ms
                String[] f = p[1].split("\\|");
                System.out.println("CODE " + Totp.codeFor(f[0], f[1],
                        Integer.parseInt(f[2]), Integer.parseInt(f[3]), Long.parseLong(f[4])));
            } else if ("URI".equals(p[0])) {                   // issuer|account|secret|algo|digits|period
                String[] f = p[1].split("\\|", -1);
                Totp.OtpSecret o = new Totp.OtpSecret();
                o.issuer = f[0]; o.account = f[1]; o.secret = f[2];
                o.algorithm = f[3]; o.digits = Integer.parseInt(f[4]);
                o.period = Integer.parseInt(f[5]);
                System.out.println("URIOUT " + Totp.toOtpAuthUri(o));
            } else if ("BARE".equals(p[0])) {                  // bare base64 (no scheme)
                GaMigration.Result r = GaMigration.parse(p[1]);
                System.out.println("BARE " + (r.parseError ? "ERR" : "OK") + " " + r.accounts.size());
            }
        }
    }
    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
"""

os.makedirs(WORK, exist_ok=True)
with open(os.path.join(WORK, "MigrationRunner.java"), "w") as f:
    f.write(RUNNER)

r = subprocess.run(["javac", "-d", WORK,
                    os.path.join(SRC, "GaMigration.java"),
                    os.path.join(SRC, "Totp.java"),
                    os.path.join(WORK, "MigrationRunner.java")],
                   capture_output=True, text=True)
if r.returncode != 0:
    print("javac failed:\n" + r.stderr)
    sys.exit(1)
print("compiled GaMigration + Totp + runner")


def run(args):
    out = subprocess.run(["java", "-cp", WORK, "com.bolt.browser.util.MigrationRunner"] + args,
                         capture_output=True, text=True)
    if out.returncode != 0:
        print("runner failed:\n" + out.stderr)
        sys.exit(1)
    return out.stdout.strip().splitlines()


# ---------------------------------------------------------------- 1. real fixtures
print("== REAL captured export payloads ==")
for uri, want_accounts, want_hotp, want_bad in REAL:
    lines = run([uri])
    res = [l for l in lines if l.startswith("RES ")][0].split()
    accs = [l.split(" ", 2)[2] for l in lines if l.startswith("ACC ")]
    idx = int(res[1])
    ok = (res[2] == "OK"
          and int(res[3]) == len(want_accounts)
          and int(res[4]) == want_hotp
          and int(res[5]) == want_bad)
    parsed = []
    for l in accs:
        f = l.split("|")
        parsed.append((f[0], f[1], f[2]))
        if f[3] != "SHA1" or f[4] != "6" or f[5] != "30":
            ok = False
    if parsed != want_accounts:
        ok = False
        print("    got:", parsed)
        print("    want:", want_accounts)
    check(ok, "fixture %d: %d account(s), hotp-skip=%d bad=%d, secret raw->base32" %
          (idx, len(want_accounts), want_hotp, want_bad))

# ---------------------------------------------------------------- 2. synthetic payloads
print("== synthetic payloads ==")
S1 = bytes.fromhex("faa505e933a0d12e90beea96c8c10836")            # 16 raw bytes
S2 = bytes.fromhex("00112233445566778899aabbccddeeff0011")         # 20 raw bytes

syn = []
syn.append(("SHA256/8-digit + batch fields",
            to_uri(payload([otp_params(S2, "alice@example.com", "GitHub",
                                       algo=2, digits=2)],
                           batch_size=3, batch_index=1, batch_id=987654321)),
            [("GitHub", "alice@example.com",
              base64.b32encode(S2).decode().rstrip("="), "SHA256", "8")],
            "1 3 1 987654321"))
syn.append(("multi-account (2 keys)",
            to_uri(payload([otp_params(S1, "a@x.com", "GitHub"),
                            otp_params(S2, "b@y.com", "Dropbox")])),
            [("GitHub", "a@x.com", base64.b32encode(S1).decode().rstrip("="), "SHA1", "6"),
             ("Dropbox", "b@y.com", base64.b32encode(S2).decode().rstrip("="), "SHA1", "6")],
            None))
syn.append(("url-safe base64, no padding",
            to_uri(payload([otp_params(S1, "a@x.com", "GitHub")]), urlsafe=True, pad=False),
            [("GitHub", "a@x.com", base64.b32encode(S1).decode().rstrip("="), "SHA1", "6")],
            None))
syn.append(("base32 TEXT secret (third-party generator style)",
            to_uri(payload([ld(1, b"JBSWY3DPEHPK3PXP") + ld(2, b"a@x.com")
                           + ld(3, b"GitHub") + vi(4, 1) + vi(5, 1) + vi(6, 2)])),
            [("GitHub", "a@x.com", "JBSWY3DPEHPK3PXP", "SHA1", "6")],
            None))
syn.append(("MD5 algorithm entry skipped",
            to_uri(payload([otp_params(S1, "a@x.com", "X", algo=4)])),
            [], None))
syn.append(("unknown fixed64 field 9 skipped cleanly",
            to_uri(payload([otp_params(S1, "a@x.com", "GitHub")]) + varint((9 << 3) | 1)
                   + b"\x01\x02\x03\x04\x05\x06\x07\x08"),
            [("GitHub", "a@x.com", base64.b32encode(S1).decode().rstrip("="), "SHA1", "6")],
            None))

for label, uri, want, batchinfo in syn:
    lines = run([uri])
    res = [l for l in lines if l.startswith("RES ")][0].split()
    accs = [l.split(" ", 2)[2] for l in lines if l.startswith("ACC ")]
    ok = res[2] == "OK"
    got = []
    for l in accs:
        f = l.split("|")
        got.append((f[0], f[1], f[2], f[3], f[4]))
    if got != want:
        ok = False
        print("    got:", got)
        print("    want:", want)
    if batchinfo:
        want_vi = batchinfo.split()
        got_vi = " ".join(res[6:9])
        if got_vi != " ".join(want_vi[:3]):
            ok = False
            print("    batch got:", got_vi, "want:", batchinfo)
    check(ok, label)

# garbage / non-payload inputs must be parse errors, not crashes
for label, bad in [("garbage base64", "otpauth-migration://offline?data=!!!not-base64!!!"),
                   ("valid b64, no accounts", "otpauth-migration://offline?data=AAAA"),
                   ("empty data", "otpauth-migration://offline?data="),
                   ("bare non-base64", "zzz")]:
    lines = run([bad])
    res = [l for l in lines if l.startswith("RES ")][0].split()
    check(res[2] == "ERR", "%s -> parseError" % label)

# bare base64 blob (no scheme) parses too
raw = payload([otp_params(S1, "a@x.com", "GitHub")])
lines = run(["--", "BARE=" + base64.b64encode(raw).decode()])
check(lines[0] == "BARE OK 1", "bare base64 (no otpauth-migration:// prefix)")

# ---------------------------------------------------------------- 3. end-to-end code
print("== end-to-end: imported secret generates correct codes ==")
B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"


def b32decode(s):
    bits = val = 0
    out = bytearray()
    for c in s:
        val = (val << 5) | B32.index(c)
        bits += 5
        if bits >= 8:
            bits -= 8
            out.append((val >> bits) & 0xFF)
    return bytes(out)


def totp_ref(secret, algo, digits, period, t_ms):
    counter = (t_ms // 1000) // period
    h = hmac.new(secret, struct.pack(">Q", counter), algo).digest()
    off = h[-1] & 0x0F
    binary = ((h[off] & 0x7F) << 24) | (h[off + 1] << 16) | (h[off + 2] << 8) | h[off + 3]
    return str(binary % (10 ** digits)).zfill(digits)


uri = REAL[0][0]
lines = run([uri])
secret = [l.split(" ", 2)[2].split("|")[2] for l in lines if l.startswith("ACC ")][0]
key = b32decode(secret)
ok = True
for t in [59_000, 1_000_000, 1_730_000_000_000, 2000000000_000]:
    out = run(["--", "CODE=%s|SHA1|6|30|%d" % (secret, t)])[0].split(" ", 1)[1]
    want = totp_ref(key, hashlib.sha1, 6, 30, t)
    if out != want:
        ok = False
        print("    t=%d java=%s py=%s" % (t, out, want))
check(ok, "codes from imported GA secret == python reference (4 times)")

# ---------------------------------------------------------------- 4. helpers
print("== export encoding + base32 ==")
lines = run(["--", "B32ENC=" + "faa505e933a0d12e90beea96c8c10836",
             "STEXT=" + "faa505e933a0d12e90beea96c8c10836",
             "STEXT=" + bytes(b"JBSWY3DPEHPK3PXP").hex(),
             "URI=GitHub|alice@gmail.com|JBSWY3DPEHPK3PXP|SHA1|6|30",
             "URI=|alice@gmail.com|JBSWY3DPEHPK3PXP|SHA256|8|60"])
b32 = lines[0].split(" ", 1)[1]
check(b32 == "7KSQL2JTUDIS5EF65KLMRQIIGY", "base32Encode(raw) == the fixture's known key text")
check(lines[1].split(" ", 1)[1] == "7KSQL2JTUDIS5EF65KLMRQIIGY", "raw bytes -> base32 text")
check(lines[2].split(" ", 1)[1] == "JBSWY3DPEHPK3PXP", "base32-text bytes stay text")
uri_out = lines[3].split(" ", 1)[1]
check(uri_out == "otpauth://totp/GitHub:alice%40gmail.com?secret=JBSWY3DPEHPK3PXP"
      "&issuer=GitHub&algorithm=SHA1&digits=6&period=30",
      "toOtpAuthUri fully percent-encodes label/issuer")
uri_out2 = lines[4].split(" ", 1)[1]
check(uri_out2 == "otpauth://totp/alice%40gmail.com?secret=JBSWY3DPEHPK3PXP"
      "&algorithm=SHA256&digits=8&period=60",
      "toOtpAuthUri without issuer")

print()
if fails:
    print("FAILED: %d" % len(fails))
    sys.exit(1)
print("ALL MIGRATION TESTS PASSED")
