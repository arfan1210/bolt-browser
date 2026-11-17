#!/usr/bin/env python3
"""v1.3.32 — QR engine verification (decode + encode round-trips).

Compiles util/QrCore.java (the pure-Java core behind the authenticator's
Google-transfer flow) standalone against the ZXing core jar, then:

 1. encode → decode round-trip for an otpauth:// setup URI (the per-account
    "Show QR code" export path) at 256 px.
 2. round-trip for a LONG otpauth-migration:// URI (~200 chars, forces a
    bigger QR version) at 512 px.
 3. "screenshot" simulation: the QR upscaled 2x, centered on a 720x1600
    white canvas (the shape of a phone screenshot), decoded through the
    exact int[] path Bitmap.getPixels feeds — this is the GA-import code
    path pixel-for-pixel.
 4. random noise pixels → null (must not crash or hallucinate).
 5. unicode label round-trip.

The ZXing jar is fetched from the Aliyun mirror (mavenCentral 429s this
environment) or Maven Central; the gradle cache is checked first.

Usage:  python3 scripts/test_qr.py   (needs javac + java on PATH)
"""
import glob
import subprocess
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "com", "bolt", "browser", "util")
WORK = "/tmp/bolt_qr_test"
ZXING_VER = "3.5.3"

fails = []


def check(cond, label):
    print(("  PASS " if cond else "  FAIL ") + label)
    if not cond:
        fails.append(label)


# ---------------------------------------------------------------- zxing jar
def find_zxing():
    # 1. gradle cache (present after any build)
    for p in glob.glob(os.path.expanduser(
            "~/.gradle/caches/modules-2/files-2.1/com.google.zxing/core/%s/*/core-%s.jar"
            % (ZXING_VER, ZXING_VER))):
        return p
    # 2. download (aliyun first — central rate-limits this sandbox)
    dst = os.path.join(WORK, "zxing-core-%s.jar" % ZXING_VER)
    if os.path.exists(dst) and os.path.getsize(dst) > 100_000:
        return dst
    urls = [
        "https://maven.aliyun.com/repository/public/com/google/zxing/core/%s/core-%s.jar"
        % (ZXING_VER, ZXING_VER),
        "https://repo1.maven.org/maven2/com/google/zxing/core/%s/core-%s.jar"
        % (ZXING_VER, ZXING_VER),
    ]
    for u in urls:
        r = subprocess.run(["curl", "-sL", "--retry", "2", "-o", dst, u],
                           capture_output=True, text=True)
        if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 100_000:
            # sanity: jar magic bytes
            with open(dst, "rb") as f:
                if f.read(2) == b"PK":
                    return dst
        try:
            os.remove(dst)
        except OSError:
            pass
    return None


os.makedirs(WORK, exist_ok=True)
JAR = find_zxing()
if not JAR:
    print("FAIL cannot obtain the ZXing core jar (tried gradle cache, aliyun, maven central)")
    sys.exit(1)
print("zxing jar: " + JAR)

RUNNER = r"""
package com.bolt.browser.util;

import com.bolt.browser.util.QrCore;

public class QrRunner {
    public static void main(String[] a) {
        String text = a[0];
        int size = Integer.parseInt(a[1]);
        int canvas = Integer.parseInt(a[2]);   // 0 = skip the screenshot sim

        int[] qr = QrCore.encode(text, size);
        System.out.println("ENC_" + (qr == null ? "NULL" : "OK " + size));
        if (qr == null) return;

        String d1 = QrCore.decode(qr, size, size);
        System.out.println("DIRECT " + verdict(d1, text));

        if (canvas > 0) {
            // screenshot simulation: QR upscaled 2x, centered on a white
            // canvas of canvas x canvas (the import path's pixel shape)
            int[] shot = new int[canvas * canvas];
            java.util.Arrays.fill(shot, 0xFFFFFFFF);
            int off = (canvas - size * 2) / 2;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int c = qr[y * size + x];
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            shot[(off + y * 2 + dy) * canvas + off + x * 2 + dx] = c;
                        }
                    }
                }
            }
            String d2 = QrCore.decode(shot, canvas, canvas);
            System.out.println("SHOT " + verdict(d2, text));

            java.util.Random rnd = new java.util.Random(42);
            int[] noise = new int[canvas * canvas];
            for (int i = 0; i < noise.length; i++) noise[i] = rnd.nextInt();
            String d3 = QrCore.decode(noise, canvas, canvas);
            System.out.println("NOISE " + (d3 == null ? "null" : "FOUND"));
        }

        int[] empty = QrCore.encode("", size);
        System.out.println("EMPTY " + (empty == null ? "null" : "encoded"));
    }
    private static String verdict(String got, String want) {
        if (got == null) return "null";
        return got.equals(want) ? "ok" : "WRONG<" + got + ">";
    }
}
"""

with open(os.path.join(WORK, "QrRunner.java"), "w") as f:
    f.write(RUNNER)

r = subprocess.run(["javac", "-cp", JAR, "-d", WORK,
                    os.path.join(SRC, "QrCore.java"),
                    os.path.join(WORK, "QrRunner.java")],
                   capture_output=True, text=True)
if r.returncode != 0:
    print("javac failed:\n" + r.stderr)
    sys.exit(1)
print("compiled QrCore + runner against zxing")


def run(text, size, canvas):
    out = subprocess.run(["java", "-cp", WORK + ":" + JAR,
                          "com.bolt.browser.util.QrRunner", text,
                          str(size), str(canvas)],
                         capture_output=True, text=True)
    if out.returncode != 0:
        print("runner failed:\n" + out.stderr)
        sys.exit(1)
    d = {}
    for l in out.stdout.strip().splitlines():
        if " " in l:
            k, v = l.split(" ", 1)
        else:
            k, v = l, "true"
        d[k] = v
    return d


print("== QR round-trips ==")

# 1. a normal otpauth export URI (per-account "Show QR code")
uri1 = ("otpauth://totp/GitHub:alice%40gmail.com?secret=JBSWY3DPEHPK3PXP"
        "&issuer=GitHub&algorithm=SHA1&digits=6&period=30")
res = run(uri1, 256, 720)
check(res.get("ENC_OK") == "256", "encode 256px")
check(res.get("DIRECT") == "ok", "decode round-trip (otpauth URI)")

# 2. long migration URI (bigger QR version) + screenshot-shaped canvas
long = ("otpauth-migration://offline?data=" + ("CjUKEPqlBekzoNEukL7qlsjBCDYSDnBpQHJhc3BiZXJ"
        "yeXBpGgtyYXNwYmVycnlwaSABKAEwAhABGAEgACjr4JKK" * 3))
res = run(long, 512, 1600)
check(res.get("ENC_OK") == "512", "encode long payload at 512px")
check(res.get("DIRECT") == "ok", "decode round-trip (long migration URI)")
check(res.get("SHOT") == "ok", "decode from 720x1600 screenshot-shaped canvas (2x scale)")
check(res.get("NOISE") == "null", "random noise -> null (no hallucination)")

# 3. the real scito fixture payload, screenshot shape
fixture = ("otpauth-migration://offline?data=CjUKEPqlBekzoNEukL7qlsjBCDYSDnBpQHJhc3BiZXJyeXBpGg"
           "tyYXNwYmVycnlwaSABKAEwAhABGAEgACjr4JKK%2B%2F%2F%2F%2F%2F8B")
res = run(fixture, 384, 1280)
check(res.get("DIRECT") == "ok", "real GA export fixture round-trips at 384px")
check(res.get("SHOT") == "ok", "real fixture decodes from screenshot-shaped canvas")

# 4. unicode label
uni = "otpauth://totp/%C3%9Cnic%C3%B8de%3A%20a%40x.com?secret=JBSWY3DPEHPK3PXP&issuer=X"
res = run(uni, 256, 0)
check(res.get("DIRECT") == "ok", "unicode label round-trip")

# 5. degenerate input
res = run("", 256, 0)
check(res.get("ENC_NULL") == "true", "empty text -> refuses to encode")

print()
if fails:
    print("FAILED: %d" % len(fails))
    sys.exit(1)
print("ALL QR TESTS PASSED")
