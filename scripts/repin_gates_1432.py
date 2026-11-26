#!/usr/bin/env python3
"""repin_gates_1432.py — the v1.4.32 standing sweep (single-run, idempotent).

The workflow law: every prior version gate pins the CURRENT release's
versionCode/versionName (so any release runs the entire suite green) and the
About-changelog POSITION pins slide one slot down when a new version lands
at [0]. This script re-points them for 145/1.4.31 -> 146/1.4.32 with dated
notes (1.4.32 lands at [0]).

Mechanics copied from repin_gates_1431.py exactly (which copied
repin_gates_1430.py): the version literals replace to their final form; the
slide bumps entries[N].startswith pins AND bare entries[N] pins by +1 with a
dated note on the SAME line (guarded — a line already carrying a v1.4.32
re-point note is never touched again).

The dated CONTENT re-points of this round (the v1.4.31 seed/net/theta pins
re-fed by hand with dated notes where the base-angle law, the compound-
anisotropy law and the chroma ladder moved them) are NOT this sweep's
mechanical version slides.
"""
import glob
import re

NOTE = ("  # v1.4.32 re-point (2026-11-26): 145/1.4.31 -> 146/1.4.32 "
        "(the base-angle + chroma-ladder round)")
NOTE_SLIDE = ("  # v1.4.32 re-point (2026-11-26): every About-changelog pin "
              "slid one slot down (1.4.32 landed at [0])")

changed = {}


def bump_versions(src):
    """145/1.4.31 -> 146/1.4.32 in CODE literals only (the dated history
    comments mention 145/1.4.31 too and must survive untouched)."""
    out = src
    out = out.replace('"versionCode 145"', '"versionCode 146"')
    out = out.replace('versionCode 145\\b', 'versionCode 146\\b')
    out = out.replace("'versionName \"1.4.31\"'", "'versionName \"1.4.32\"'")
    out = out.replace('versionName "1\\.4\\.31"', 'versionName "1\\.4\\.32"')
    out = out.replace('pins 145 / 1.4.31', 'pins 146 / 1.4.32')
    out = out.replace('says 145/1.4.31', 'says 146/1.4.32')
    # the escaped-form pins (the v1.4.17 phantom-backslash corpse family)
    out = out.replace('versionCode\\s+145\\b', 'versionCode\\s+146\\b')
    out = out.replace('versionName\\s+"1\\.4\\.31"', 'versionName\\s+"1\\.4\\.32"')
    # single-quoted + == comparison forms
    out = out.replace("'versionCode 145'", "'versionCode 146'")
    out = out.replace('== "145"', '== "146"')
    out = out.replace('== "1.4.31"', '== "1.4.32"')
    out = out.replace("== '1.4.31'", "== '1.4.32'")
    # the check-format pins that embed the pair in a message
    out = out.replace('145/1.4.31', '146/1.4.32')
    return out


def slide_changelog_pins(src):
    """entries[N].startswith("1.4.X|") -> entries[N+1] with a dated note.
    GUARDED: lines already carrying the v1.4.32 note are left alone."""
    pat = re.compile(r'entries\[(\d+)\]\.startswith\("1\.4\.(\d+)\|"\)')
    lines = src.split("\n")
    for i, ln in enumerate(lines):
        if "v1.4.32 re-point" in ln:
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
    """the standing sweep law, second half: NON-startswith entries[N]
    content checks slide too — every entries[N] on an un-noted,
    non-startswith line becomes entries[N+1]."""
    bare = re.compile(r'entries\[(\d+)\](?!\.startswith)')
    lines = src.split("\n")
    for i, ln in enumerate(lines):
        if "v1.4.32 re-point" in ln:
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
    if f.endswith("test_v1432.py"):
        continue   # this gate ships at 146/1.4.32 already
    src = open(f, encoding="utf8").read()
    out = bump_versions(src)
    out = slide_changelog_pins(out)
    out = slide_bare_entries_pins(out)
    if out != src:
        open(f, "w", encoding="utf8").write(out)
        changed[f] = True
        print("repinned", f)
print(len(changed), "gate files repinned to 146/1.4.32")
