#!/usr/bin/env python3
"""repin_gates_1422.py — the v1.4.22 standing sweep (single-run, idempotent).

The workflow law: every prior version gate pins the CURRENT release's
versionCode/versionName (so any release runs the entire suite green) and the
About-changelog POSITION pins slide one slot down when a new version lands
at [0]. This script re-points them for 135/1.4.21 -> 136/1.4.22 with dated
notes, exactly the way the v1.4.21 sweep did (README maintainer log).

Idempotent: the version literals replace to their final form (a second pass
finds nothing), and the slide is guarded by the dated note — a line already
carrying 'v1.4.22 re-point' is never slid again.
"""
import glob
import re

NOTE = ("  # v1.4.22 re-point (2026-11-20): 135/1.4.21 -> 136/1.4.22 "
        "(the dark-icon round 4 + the raster turn + the hybrid + the four-pack)")
NOTE_SLIDE = ("  # v1.4.22 re-point (2026-11-20): every About-changelog pin "
              "slid one slot down (1.4.22 landed at [0])")

changed = {}

def bump_versions(src):
    """135/1.4.21 -> 136/1.4.22 in CODE literals only (the dated history
    comments mention 135/1.4.21 too and must survive untouched)."""
    out = src
    out = out.replace('"versionCode 135"', '"versionCode 136"')
    out = out.replace('versionCode 135\\b', 'versionCode 136\\b')
    out = out.replace("'versionName \"1.4.21\"'", "'versionName \"1.4.22\"'")
    out = out.replace('versionName "1\\.4\\.21"', 'versionName "1\\.4\\.22"')
    out = out.replace('pins 135 / 1.4.21', 'pins 136 / 1.4.22')
    out = out.replace('says 135/1.4.21', 'says 136/1.4.22')
    return out

def slide_changelog_pins(src):
    """entries[N].startswith("1.4.X|") -> entries[N+1] with a dated note.
    GUARDED: lines already carrying the v1.4.22 note are left alone."""
    pat = re.compile(r'entries\[(\d+)\]\.startswith\("1\.4\.(\d+)\|"\)')
    lines = src.split("\n")
    for i, ln in enumerate(lines):
        if "v1.4.22 re-point" in ln:
            continue                      # already slid by a prior run
        m = pat.search(ln)
        if not m:
            continue
        idx = int(m.group(1)) + 1
        ver = m.group(2)
        new = pat.sub(f'entries[{idx}].startswith("1.4.{ver}|")', ln, count=1)
        lines[i] = new.rstrip() + NOTE_SLIDE
    return "\n".join(lines)

for f in sorted(glob.glob("scripts/test_v14*.py") +
                glob.glob("scripts/test_v13*.py") +
                glob.glob("scripts/test_v1[0-3]*.py")):
    src = open(f, encoding="utf8").read()
    out = bump_versions(src)
    out = slide_changelog_pins(out)
    if out != src:
        open(f, "w", encoding="utf8").write(out)
        changed[f] = True

print("repin 136/1.4.22:", len(changed), "files touched")
for f in sorted(changed):
    print("  ", f)
