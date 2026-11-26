#!/usr/bin/env python3
"""repin_gates_1433.py — the v1.4.33 standing sweep (single-run, idempotent).

The workflow law: every prior version gate pins the CURRENT release's
versionCode/versionName (so any release runs the entire suite green) and the
About-changelog POSITION pins slide one slot down when a new version lands
at [0]. This script re-points them for 146/1.4.32 -> 147/1.4.33 with dated
notes (1.4.33 lands at [0]).

Mechanics copied from repin_gates_1432.py exactly (which copied
repin_gates_1431.py): the version literals replace to their final form; the
slide bumps entries[N].startswith pins AND bare entries[N] pins by +1 with a
dated note on the SAME line (guarded — a line already carrying a v1.4.33
re-point note is never touched again).

The dated CONTENT re-points of this round (the v1.4.32 base-table
four-display pins, the v1.4.32 net= compose pins and the calibration-prior
pin, the v1.4.31 trim rows and net= pin, the v1.4.30 popupNewTab pin, the
v1.4.31 exit-cadence 5-minute pins, the blobdl HOOK_JS fragment) are
NOT this sweep's mechanical version slides — they were re-fed by hand in
this session with dated notes.
"""
import glob
import re

NOTE = ("  # v1.4.33 re-point (2026-11-27): 146/1.4.32 -> 147/1.4.33 "
        "(the portrait-only + active-array + dot-closing field round)")
NOTE_SLIDE = ("  # v1.4.33 re-point (2026-11-27): every About-changelog pin "
              "slid one slot down (1.4.33 landed at [0])")

changed = {}


def bump_versions(src):
    """146/1.4.32 -> 147/1.4.33 in CODE literals only (the dated history
    comments mention 146/1.4.32 too and must survive untouched)."""
    out = src
    out = out.replace('"versionCode 146"', '"versionCode 147"')
    out = out.replace('versionCode 146\\b', 'versionCode 147\\b')
    out = out.replace("'versionName \"1.4.32\"'", "'versionName \"1.4.33\"'")
    out = out.replace('versionName "1\\.4\\.32"', 'versionName "1\\.4\\.33"')
    out = out.replace('pins 146 / 1.4.32', 'pins 147 / 1.4.33')
    out = out.replace('says 146/1.4.32', 'says 147/1.4.33')
    # the escaped-form pins (the v1.4.17 phantom-backslash corpse family)
    out = out.replace('versionCode\\s+146\\b', 'versionCode\\s+147\\b')
    out = out.replace('versionName\\s+"1\\.4\\.32"', 'versionName\\s+"1\\.4\\.33"')
    # single-quoted + == comparison forms
    out = out.replace("'versionCode 146'", "'versionCode 147'")
    out = out.replace('== "146"', '== "147"')
    out = out.replace('== "1.4.32"', '== "1.4.33"')
    out = out.replace("== '1.4.32'", "== '1.4.33'")
    # the check-format pins that embed the pair in a message
    out = out.replace('146/1.4.32', '147/1.4.33')
    return out


def slide_changelog_pins(src):
    """entries[N].startswith("1.4.X|") -> entries[N+1] with a dated note.
    GUARDED: lines already carrying the v1.4.33 note are left alone."""
    pat = re.compile(r'entries\[(\d+)\]\.startswith\("1\.4\.(\d+)\|"\)')
    lines = src.split("\n")
    for i, ln in enumerate(lines):
        if "v1.4.33 re-point" in ln:
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
        if "v1.4.33 re-point" in ln:
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
    if f.endswith("test_v1433.py"):
        continue   # this gate ships at 147/1.4.33 already
    src = open(f, encoding="utf8").read()
    out = bump_versions(src)
    out = slide_changelog_pins(out)
    out = slide_bare_entries_pins(out)
    if out != src:
        open(f, "w", encoding="utf8").write(out)
        changed[f] = True
        print("repinned", f)
print(len(changed), "gate files repinned to 147/1.4.33")
