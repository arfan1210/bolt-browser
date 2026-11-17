#!/usr/bin/env python3
"""v1.4.10 release gate — the version-collision corpse + the pipeline law.

THE REPORT (2026-11-17): "You built the APK as v1.4.9 (code 123), but v1.4.9
was already released yesterday from the private repo. Android will reject
this update. You stopped at the local build stage and did not push the
release to GitHub." TWO corpses in one:

1. THE VERSION-COLLISION CORPSE. v1.4.9/123 was already LIVE on the public
   releases repo (published from the private bolt-light-browser repo before
   the hardening four-pack existed). Building the hardening as another
   v1.4.9/123 could never install: Android refuses updates whose
   versionCode does not RISE, and the Play-side/"install in place" flow
   dies the same way. The hardening therefore ships as v1.4.10/124.
   Pinned here: the bump itself, the About changelog label law (the newest
   line is 1.4.10 and describes the four-pack), and the honesty trim (the
   1.4.9 line describes only what v1.4.9 ACTUALLY shipped — the "under the
   hood" sentence moved to the 1.4.10 line).

2. THE PIPELINE LAW (the second half of the report: "did not push"). The
   release flow now lives entirely on the two public repos:
   - SOURCE: github.com/arfan1210/bolt-browser — the tag v<ver> + main;
   - RELEASES: github.com/arfan1210/bolt-browser-releases — the GitHub
     release with the APK asset + the changelog body (this is also what
     About's update checker reads, so the publisher and the checker can
     never drift apart again).
   The private bolt-light-browser repo is DEAD (its checkout is an empty
   dir): release.sh must carry NO reference to it, and the gates that
   hardcoded its path read the public checkout instead (this session's
   standing sweep).
   Plus THE LIVE-VERSION GUARD: release.sh must refuse to publish a
   version that does not compare GREATER than the releases repo's current
   /releases/latest — the exact corpse of this report can then never
   recur silently.
"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
fails = 0


def need(cond, why):
    global fails
    print(("  ok  " if cond else "  FAIL") + " - " + why)
    if not cond:
        fails += 1


gradle = open(os.path.join(ROOT, "app/build.gradle"), encoding="utf8").read()
strs = open(os.path.join(ROOT, "app/src/main/res/values/strings.xml"),
            encoding="utf8").read()
about = open(os.path.join(ROOT, "app/src/main/java/com/bolt/browser/ui/AboutActivity.kt"),
             encoding="utf8").read()
rel = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()

print("A — the version bump (the collision corpse)")
need(re.search(r"versionCode 124\b", gradle) is not None,
     "versionCode 124 (v1.4.9/123 is LIVE — Android only installs RISING codes)")
need(re.search(r'versionName "1\.4\.10"', gradle) is not None,
     'versionName "1.4.10"')

print("B — the About changelog (the label law + the honesty trim)")
entries = re.findall(r"<item>([^<]+)</item>",
                     strs.split('name="about_changelog"')[1]) \
        if 'name="about_changelog"' in strs else []
need(entries and entries[0].startswith("1.4.10|"),
     "the newest entry is 1.4.10 (the label law)")
need(len(entries) > 1 and entries[1].startswith("1.4.9|"),
     "the second entry is 1.4.9 (the previous live release)")
need("Aho-Corasick" in entries[0] and "regex Patterns" in entries[0],
     "the 1.4.10 line names the pattern diet (the ~130 MB memory corpse)")
need("WebView registry" in entries[0],
     "the 1.4.10 line names the closed-tab WebView leak")
need("media service" in entries[0],
     "the 1.4.10 line names the tab-list race")
need("signing key" in entries[0] and "install in place" in entries[0],
     "the 1.4.10 line names the key rotation + the same-certificate promise")
need("Under the hood" not in entries[1],
     "the 1.4.9 line no longer claims the hardening (it shipped in 1.4.10, not 1.4.9)")

print("C — the pipeline law (release.sh targets the two public repos)")
need('REPO_DIR=$BASE/bolt-browser' in rel,
     "the build/tag repo is the PUBLIC source checkout (bolt-browser)")
need("arfan1210/bolt-browser-releases" in rel,
     "the release + APK + changelog publish to the releases repo")
need("bolt-light-browser" not in rel,
     "NO reference to the dead private repo anywhere in release.sh")
need("bcd57eb42ca770a340f78ad64b56999d6238f88ab194cc8c787ee46082938ae4" in rel,
     "the signing-cert fingerprint pin survives the key-password rotation (same cert)")
need("6000000" in rel,
     "the APK size law survives (hard fail over 6,000,000 B)")
need("application/vnd.android.package-archive" in rel,
     "the APK asset uploads with the android package-archive content type")
need("github.com/arfan1210/bolt-browser.git" in rel,
     "the tag push targets the source repo (bolt-browser)")

print("D — the live-version guard (this report's corpse, prevented)")
need("releases/latest" in rel,
     "release.sh reads the releases repo's /releases/latest before publishing")
need(re.search(r"LIVE_VER", rel) is not None and
     re.search(r"already live", rel) is not None,
     "release.sh REFUSES a version <= the live latest (the collision guard)")

print("E — the agreement law (publisher == update checker)")
need("https://api.github.com/repos/arfan1210/bolt-browser-releases/releases/latest" in about,
     "About's update checker reads the SAME repo release.sh publishes to")

print()
print("V1410 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
