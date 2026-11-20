#!/usr/bin/env python3
"""repin_gates_1424.py — the v1.4.24 standing sweep (single-run, idempotent).

The workflow law: every prior version gate pins the CURRENT release's
versionCode/versionName (so any release runs the entire suite green) and the
About-changelog POSITION pins slide one slot down when a new version lands
at [0]. This script re-points them for 137/1.4.23 -> 138/1.4.24 with dated
notes (1.4.24 lands at [0]).

Idempotent: the version literals replace to their final form (a second pass
finds nothing), and the slide is guarded by the dated note — a line already
carrying 'v1.4.24 re-point' is never slid again.
"""
import glob
import re

NOTE = ("  # v1.4.24 re-point (2026-11-21): 137/1.4.23 -> 138/1.4.24 "
        "(the corpse-fix + size round)")
NOTE_SLIDE = ("  # v1.4.24 re-point (2026-11-21): every About-changelog pin "
              "slid one slot down (1.4.24 landed at [0])")

changed = {}

def bump_versions(src):
    """137/1.4.23 -> 138/1.4.24 in CODE literals only (the dated history
    comments mention 137/1.4.23 too and must survive untouched)."""
    out = src
    out = out.replace('"versionCode 137"', '"versionCode 138"')
    out = out.replace('versionCode 137\\b', 'versionCode 138\\b')
    out = out.replace("'versionName \"1.4.23\"'", "'versionName \"1.4.24\"'")
    out = out.replace('versionName "1\\.4\\.23"', 'versionName "1\\.4\\.24"')
    out = out.replace('pins 137 / 1.4.23', 'pins 138 / 1.4.24')
    out = out.replace('says 137/1.4.23', 'says 138/1.4.24')
    # the escaped-form pins (the v1.4.17 phantom-backslash corpse family)
    out = out.replace('versionCode\\s+137\\b', 'versionCode\\s+138\\b')
    out = out.replace('versionName\\s+"1\\.4\\.23"', 'versionName\\s+"1\\.4\\.24"')
    # single-quoted + == comparison forms
    out = out.replace("'versionCode 137'", "'versionCode 138'")
    out = out.replace('== "137"', '== "138"')
    out = out.replace('== "1.4.23"', '== "1.4.24"')
    return out

def slide_changelog_pins(src):
    """entries[N].startswith("1.4.X|") -> entries[N+1] with a dated note.
    GUARDED: lines already carrying the v1.4.24 note are left alone."""
    pat = re.compile(r'entries\[(\d+)\]\.startswith\("1\.4\.(\d+)\|"\)')
    lines = src.split("\n")
    for i, ln in enumerate(lines):
        if "v1.4.24 re-point" in ln:
            continue                      # already slid by a prior run
        m = pat.search(ln)
        if not m:
            continue
        idx = int(m.group(1)) + 1
        ver = m.group(2)
        new = pat.sub(f'entries[{idx}].startswith("1.4.{ver}|")', ln, count=1)
        lines[i] = new.rstrip() + NOTE_SLIDE
    return "\n".join(lines)

def slide_bare_entries_pins(src):
    """v1.4.23 sweep law, second half: NON-startswith entries[N] content
    checks (the v1.4.12/v1.4.19 families) slide too — every entries[N]
    on an un-noted, non-startswith line becomes entries[N+1]."""
    bare = re.compile(r'entries\[(\d+)\](?!\.startswith)')
    lines = src.split("\n")
    for i, ln in enumerate(lines):
        if "v1.4.24 re-point" in ln:
            continue                      # already slid by a prior run
        if ".startswith" in ln:
            continue                      # the startswith pass owns those lines
        if not bare.search(ln):
            continue
        def bump(m):
            return f"entries[{int(m.group(1)) + 1}]"
        lines[i] = bare.sub(bump, ln).rstrip() + NOTE_SLIDE
    return "\n".join(lines)

for f in sorted(glob.glob("scripts/test_v14*.py") +
                glob.glob("scripts/test_v13*.py") +
                glob.glob("scripts/test_v1[0-3]*.py")):
    src = open(f, encoding="utf8").read()
    out = bump_versions(src)
    out = slide_changelog_pins(out)
    out = slide_bare_entries_pins(out)
    if out != src:
        open(f, "w", encoding="utf8").write(out)
        changed[f] = True
        print("repinned", f)
print(len(changed), "gate files repinned to 138/1.4.24")
