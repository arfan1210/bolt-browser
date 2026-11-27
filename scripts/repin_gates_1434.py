#!/usr/bin/env python3
"""repin_gates_1434.py — the v1.4.34 standing sweep (single-run, idempotent).

The workflow law: every prior version gate pins the CURRENT release's
versionCode/versionName (so any release runs the entire suite green) and the
About-changelog POSITION pins slide one slot down when a new version lands
at [0]. This script re-points them for 147/1.4.33 -> 148/1.4.34 with dated
notes (1.4.34 lands at [0]).

Mechanics copied from repin_gates_1433.py exactly: the version literals
replace to their final form; the slide bumps entries[N].startswith pins AND
bare entries[N] pins by +1 with a dated note on the SAME line (guarded — a
line already carrying a v1.4.34 re-point note is never touched again).

The dated CONTENT re-points of this round (the v1.4.33 cover-scale pin in
test_v1388, the preview hull fixtures in test_v1426, the fills pin + the D3
cover/ladder transcription in test_v1427, the residual-format pin in
test_v1429, the diag + ORDER pins in test_v1430, the acceptance-gate pins
in test_v1432) are NOT this sweep's mechanical version slides — they were
re-fed by hand in this session with dated notes (THE LETTERBOX LAW + THE
DUAL-PRIOR VERDICT).
"""
import glob
import re

NOTE = ("  # v1.4.34 re-point (2026-11-27): 147/1.4.33 -> 148/1.4.34 "
        "(the scanner field-of-view round)")
NOTE_SLIDE = ("  # v1.4.34 re-point (2026-11-27): every About-changelog pin "
              "slid one slot down (1.4.34 landed at [0])")

changed = {}


def bump_versions(src):
    """147/1.4.33 -> 148/1.4.34 in CODE literals only (the dated history
    comments mention 147/1.4.33 too and must survive untouched)."""
    out = src
    out = out.replace('"versionCode 147"', '"versionCode 148"')
    out = out.replace('versionCode 147\\b', 'versionCode 148\\b')
    out = out.replace("'versionName \"1.4.33\"'", "'versionName \"1.4.34\"'")
    out = out.replace('versionName "1\\.4\\.33"', 'versionName "1\\.4\\.34"')
    out = out.replace('pins 147 / 1.4.33', 'pins 148 / 1.4.34')
    out = out.replace('says 147/1.4.33', 'says 148/1.4.34')
    # the escaped-form pins (the v1.4.17 phantom-backslash corpse family)
    out = out.replace('versionCode\\s+147\\b', 'versionCode\\s+148\\b')
    out = out.replace('versionName\\s+"1\\.4\\.33"', 'versionName\\s+"1\\.4\\.34"')
    # single-quoted + == comparison forms
    out = out.replace("'versionCode 147'", "'versionCode 148'")
    out = out.replace('== "147"', '== "148"')
    out = out.replace('== "1.4.33"', '== "1.4.34"')
    out = out.replace("== '1.4.33'", "== '1.4.34'")
    # the check-format pins that embed the pair in a message
    out = out.replace('147/1.4.33', '148/1.4.34')
    return out


def slide_changelog_pins(src):
    """entries[N].startswith("1.4.X|") -> entries[N+1] with a dated note.
    GUARDED: lines already carrying the v1.4.34 note are left alone."""
    pat = re.compile(r'entries\[(\d+)\]\.startswith\("1\.4\.(\d+)\|"\)')
    out_lines = []
    for line in src.split("\n"):
        m = pat.search(line)
        if m and "v1.4.34 re-point" not in line:
            n = int(m.group(1))
            line2 = pat.sub(f'entries[{n + 1}].startswith("1.4.\\2|")', line)
            line2 = line2 + NOTE_SLIDE
            out_lines.append(line2)
        else:
            out_lines.append(line)
    return "\n".join(out_lines)


def bump_bare_entry_pins(src):
    """the standing sweep law, second half (mechanics copied from
    repin_gates_1433.py's slide_bare_entries_pins): NON-startswith
    entries[N] content checks slide too — every entries[N] on an
    un-noted, non-startswith line becomes entries[N+1]."""
    bare = re.compile(r'entries\[(\d+)\](?!\.startswith)')
    out_lines = []
    for line in src.split("\n"):
        if "v1.4.34 re-point" in line:
            out_lines.append(line)      # already slid by a prior run
            continue
        if ".startswith" in line:
            out_lines.append(line)      # the startswith pass owns those
            continue
        if not bare.search(line):
            out_lines.append(line)
            continue

        def bump(m):
            return f"entries[{int(m.group(1)) + 1}]"
        out_lines.append(bare.sub(bump, line).rstrip() + NOTE_SLIDE)
    return "\n".join(out_lines)


for path in sorted(glob.glob("scripts/test_*.py")):
    src = open(path, encoding="utf8").read()
    orig = src
    src = bump_versions(src)
    src = slide_changelog_pins(src)
    src = bump_bare_entry_pins(src)
    if src != orig:
        open(path, "w", encoding="utf8").write(src)
        changed[path] = True

# release.sh itself pins nothing about versions; app/build.gradle is the
# source of truth (already bumped by hand).
print(f"repin_gates_1434: {len(changed)} file(s) updated:")
for p in changed:
    print("  - " + p)
