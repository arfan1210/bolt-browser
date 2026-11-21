#!/usr/bin/env python3
"""repin_gates_1426.py — the v1.4.26 standing sweep (single-run, idempotent).

The workflow law: every prior version gate pins the CURRENT release's
versionCode/versionName (so any release runs the entire suite green) and the
About-changelog POSITION pins slide one slot down when a new version lands
at [0]. This script re-points them for 139/1.4.25 -> 140/1.4.26 with dated
notes (1.4.26 lands at [0]).

Idempotent: the version literals replace to their final form (a second pass
finds nothing), and the slide is guarded by the dated note — a line already
carrying 'v1.4.26 re-point' is never slid again.
"""
import glob
import re

NOTE = ("  # v1.4.26 re-point (2026-11-21): 139/1.4.25 -> 140/1.4.26 "
        "(the black-on-black + bubble round)")
NOTE_SLIDE = ("  # v1.4.26 re-point (2026-11-21): every About-changelog pin "
              "slid one slot down (1.4.26 landed at [0])")

changed = {}


def bump_versions(src):
    """139/1.4.25 -> 140/1.4.26 in CODE literals only (the dated history
    comments mention 139/1.4.25 too and must survive untouched)."""
    out = src
    out = out.replace('"versionCode 139"', '"versionCode 140"')
    out = out.replace('versionCode 139\\b', 'versionCode 140\\b')
    out = out.replace("'versionName \"1.4.25\"'", "'versionName \"1.4.26\"'")
    out = out.replace('versionName "1\\.4\\.25"', 'versionName "1\\.4\\.26"')
    out = out.replace('pins 139 / 1.4.25', 'pins 140 / 1.4.26')
    out = out.replace('says 139/1.4.25', 'says 140/1.4.26')
    # the escaped-form pins (the v1.4.17 phantom-backslash corpse family)
    out = out.replace('versionCode\\s+139\\b', 'versionCode\\s+140\\b')
    out = out.replace('versionName\\s+"1\\.4\\.25"', 'versionName\\s+"1\\.4\\.26"')
    # single-quoted + == comparison forms
    out = out.replace("'versionCode 139'", "'versionCode 140'")
    out = out.replace('== "139"', '== "140"')
    out = out.replace('== "1.4.25"', '== "1.4.26"')
    out = out.replace("== '1.4.25'", "== '1.4.26'")
    return out


def slide_changelog_pins(src):
    """About [0] pins slide one slot down (the new version lands at [0]).
    Only lines that do NOT already carry the dated slide note."""
    lines = src.split("\n")
    out = []
    for ln in lines:
        if "About-changelog pin" in ln or ("About" in ln and "changelog" in ln.lower()
                                          and "slot" in ln.lower()):
            continue   # the note lines themselves stay put
        if "v1.4.26 re-point" in ln:
            out.append(ln)
            continue
        m = re.search(r"\[0\]", ln)
        if m and ("About" in ln or "changelog" in ln.lower() or "about_txt" in ln
                  or "AboutActivity" in ln):
            out.append(NOTE_SLIDE)
            out.append(re.sub(r"\[0\]", "[1]", ln).replace("[1] [1]", "[1]"))
            continue
        m2 = re.search(r"\[1\]", ln)
        if m2 and ("About" in ln or "changelog" in ln.lower() or "about_txt" in ln
                   or "AboutActivity" in ln):
            out.append(NOTE_SLIDE)
            out.append(re.sub(r"\[1\]", "[2]", ln).replace("[2] [2]", "[2]"))
            continue
        out.append(ln)
    return "\n".join(out)


for path in sorted(glob.glob("scripts/test_v*.py")):
    if path.endswith("test_v1426.py"):
        continue   # this gate ships at 140/1.4.26 already
    src = open(path, encoding="utf8").read()
    out = bump_versions(src)
    if "v1.4.26 re-point" not in out:
        out = slide_changelog_pins(out)
    if out != src:
        open(path, "w", encoding="utf8").write(out)
        changed[path] = True

print("repin_gates_1426: re-pointed %d gate files to 140/1.4.26" % len(changed))
for p in sorted(changed):
    print("  " + p)
