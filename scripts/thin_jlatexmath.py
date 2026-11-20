#!/usr/bin/env python3
"""v1.4.24/v1.4.25 — THE FONT-PACK SPLIT + THE CORE BUNDLE
(scripts/thin_jlatexmath.py).

v1.4.24 (the size law, take two): the user asked twice for a smaller APK.
v1.4.21 brought jlatexmath-android in with its whole bundled font set: 30
TTF binaries, 455,768 B raw, stored at 286,982 B compressed in the
3,325,166 B v1.4.23 APK. The engine CODE stayed bundled; the FONT PACK moved
OUT of the APK into an on-demand download on first math render.

v1.4.25 — THE CORE-FONT REBUY (the reliability law; the field corpse:
"math dl fail dl base/jlm_cmex10.ttf" on a metered phone — the pack never
landed, so EVERY fraction rendered through the weak v1.4.20 subset). The
7 CORE fonts go BACK INTO the APK: cmex10 (big operators/extensible
delimiters), cmmi10 (math italic — variables), cmmib10 (bold math italic),
cmr10 (roman — digits/parentheses), cmsy10 (symbols, \frac bars, greek
variants), msam10 + msbm10 (AMS symbols, blackboard bold). ~120 KB raw
buys fractions, matrices, Greek, integrals, sums and AMS symbols OFFLINE
and ON METERED DATA — core math never depends on the network again. The
remaining 23 (euler/fraktur, optional CM shapes, special/stmary/dsrom,
rsfs script) stay ON-DEMAND for advanced TeX.

WHAT THIS SCRIPT DOES (offline-reproducible, the rasterize_engine_logos.py
precedent for script-generated binary artifacts):
  1. downloads the pinned maven AAR
     ru.noties:jlatexmath-android:0.2.0 from repo1.maven.org;
  2. removes every *.ttf under assets/org/scilab/forge/jlatexmath/fonts/
     EXCEPT the 7 CORE files (23 removed, ~336 KB raw; the engine's XML
     descriptors/metrics STAY);
  3. removes ru/noties/jlatexmath/JLatexMathAndroid.class — the app ships its
     own same-FQCN replacement (app/src/main/java/ru/noties/jlatexmath/
     JLatexMathAndroid.java) whose two resource doors try the APK assets
     first (core TTFs + descriptors), then the downloaded pack directory in
     filesDir. Same public API (init/getResourceAsStream/loadTypeface); the
     engine bytecode links unchanged.
  4. writes app/libs/jlatexmath-thin.aar + prints the sha256 for pinning.

The on-demand hosts are the upstream repo's own assets, pinned by tag, the
same pair tesseract uses (jsDelivr first, raw.githubusercontent fail-over):
  https://cdn.jsdelivr.net/gh/noties/jlatexmath-android@0.2.0/jlatexmath/src/main/assets/
  https://raw.githubusercontent.com/noties/jlatexmath-android/v0.2.0/jlatexmath/src/main/assets/
(verified byte-identical to the AAR's copies — md5 spot-checked on the build
machine; the CDN and the AAR are built from the same tag). The CORE list
must mirror util/MathFonts.kt's CORE set — edit the two together.

Run from the repo root:  python3 scripts/thin_jlatexmath.py
Idempotent: regenerates the same artifact (zips are stored deterministically
here by sorting entries; byte-stable only modulo zip timestamps, so the sha256
is printed for the maintainer to eyeball, not machine-pinned in a gate).
"""
import hashlib
import os
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "libs", "jlatexmath-thin.aar")
PIN = "https://repo1.maven.org/maven2/ru/noties/jlatexmath-android/0.2.0/jlatexmath-android-0.2.0.aar"
SEAM_CLASS = "ru/noties/jlatexmath/JLatexMathAndroid.class"
FONTS_PREFIX = "assets/org/scilab/forge/jlatexmath/fonts/"

# v1.4.25 — the 7 CORE fonts bundled in the APK (offline/metered math:
# fractions, matrices, greek, integrals, sums, AMS symbols). Must mirror
# util/MathFonts.kt's CORE set exactly.
CORE = frozenset({
    "base/jlm_cmex10.ttf", "base/jlm_cmmi10.ttf", "base/jlm_cmmib10.ttf",
    "latin/jlm_cmr10.ttf", "maths/jlm_cmsy10.ttf", "maths/jlm_msam10.ttf",
    "maths/jlm_msbm10.ttf",})


def main() -> int:
    tmp_aar = "/tmp/jlatexmath-android-0.2.0.aar"
    if not os.path.exists(tmp_aar):
        print("downloading the pinned AAR ...")
        urllib.request.urlretrieve(PIN, tmp_aar)
    raw = open(tmp_aar, "rb").read()
    print(f"source AAR: {len(raw)} B  sha256={hashlib.sha256(raw).hexdigest()[:16]}...")

    src = zipfile.ZipFile(tmp_aar)
    names = src.namelist()

    ttf_removed = 0
    ttf_bytes = 0
    core_kept = 0
    core_bytes = 0
    seam_removed = False

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as out:
        for n in sorted(names):
            data = src.read(n)
            if n.startswith(FONTS_PREFIX) and n.endswith(".ttf"):
                rel = n[len(FONTS_PREFIX):]
                if rel in CORE:
                    core_kept += 1
                    core_bytes += len(data)
                    out.writestr(n, data)     # v1.4.25: core rides the APK
                    continue
                ttf_removed += 1
                ttf_bytes += len(data)
                continue
            # classes.jar: strip the seam class INSIDE it (that is where it
            # lives in an AAR — the app source tree provides the replacement)
            if n == "classes.jar":
                buf_in = __import__("io").BytesIO(data)
                buf_out = __import__("io").BytesIO()
                with zipfile.ZipFile(buf_in) as cj, \
                        zipfile.ZipFile(buf_out, "w", zipfile.ZIP_DEFLATED) as cjo:
                    for cn in sorted(cj.namelist()):
                        cd = cj.read(cn)
                        if cn == SEAM_CLASS:
                            seam_removed = True
                            continue
                        cjo.writestr(cn, cd)
                data = buf_out.getvalue()
                out.writestr(n, data)
                continue
            out.writestr(n, data)

    if not (ttf_removed == 23 and core_kept == 7 and seam_removed):
        print(f"ERROR: unexpected AAR shape: ttf_removed={ttf_removed} "
              f"core_kept={core_kept} seam_removed={seam_removed}")
        return 1

    size = os.path.getsize(OUT)
    print(f"thin AAR written: {OUT}")
    print(f"  {size} B  sha256={hashlib.sha256(open(OUT, 'rb').read()).hexdigest()}")
    print(f"  CORE kept in APK: {core_kept} TTFs ({core_bytes} B raw) — offline math")
    print(f"  removed {ttf_removed} optional TTFs ({ttf_bytes} B raw; on-demand) "
          f"+ the seam class (re-implemented in app sources)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
