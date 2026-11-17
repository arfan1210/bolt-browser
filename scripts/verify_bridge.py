#!/usr/bin/env python3
"""v1.3.45 R8 bridge verification (lesson 25/29-aware).
Extracts @JavascriptInterface method names from source, then checks each is
present in the shipped dex string pool. Suffix-merge aware (substring match,
not whole-line); counts extracted names before trusting the verdict."""
import re, subprocess, sys, pathlib

# v1.4.10 re-point (2026-11-17): the private bolt-light-browser checkout is gone — gates read the PUBLIC source checkout
SRC = pathlib.Path("/home/z/my-project/bolt-browser/app/src/main/java")
APK = "/home/z/my-project/bolt-browser/app/build/outputs/apk/release/app-release.apk"

names = set()
pat = re.compile(r"@JavascriptInterface\s+public\s+[A-Za-z0-9_<>\[\], .]+\s+(\w+)\s*\(")
for f in SRC.rglob("*.java"):
    for m in pat.finditer(f.read_text(errors="ignore")):
        names.add(m.group(1))

print(f"extracted {len(names)} @JavascriptInterface names from source")
if len(names) < 20:
    print("FAIL: name extraction collapsed — do not trust this run")
    sys.exit(1)

strings = subprocess.run(["unzip", "-p", APK, "classes.dex"],
                         capture_output=True).stdout
# crude dex string pool scan: any 4-byte-aligned run of ASCII is candidate;
# simplest robust check: raw bytes substring search over the whole dex
missing = [n for n in sorted(names) if n.encode() not in strings]
for n in sorted(names):
    print(("  OK   " if n.encode() in strings else "  MISS ") + n)
if missing:
    print(f"FAIL: {len(missing)} bridge names missing from dex")
    sys.exit(1)
print(f"ALL {len(names)} BRIDGE NAMES PRESENT IN SHIPPED DEX")
