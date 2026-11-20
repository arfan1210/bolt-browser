#!/usr/bin/env python3
"""v1.4.24 — THE FONT-PACK SPLIT (the size law, take two; scripts/thin_jlatexmath.py).

The user has asked twice for a smaller APK. v1.4.21 brought jlatexmath-android
in with its whole bundled font set: 30 TTF binaries, 455,768 B raw, stored at
286,982 B compressed in the 3,325,166 B v1.4.23 APK (the user's "~470 KB" was
the raw-asset measure). The engine CODE stays bundled; the FONT PACK becomes
an on-demand download (the v1.3.73 tesseract.js pattern: fetch on first math
render, cache in filesDir, fall back to the v1.4.20 LaTeX-subset spans when
the download fails — never a crash).

WHAT THIS SCRIPT DOES (offline-reproducible, the rasterize_engine_logos.py
precedent for script-generated binary artifacts):
  1. downloads the pinned maven AAR
     ru.noties:jlatexmath-android:0.2.0 from repo1.maven.org;
  2. removes EVERY *.ttf under assets/org/scilab/forge/jlatexmath/fonts/
     (30 files, 455,768 B — the engine's XML descriptors/metrics STAY);
  3. removes ru/noties/jlatexmath/JLatexMathAndroid.class — the app ships its
     own same-FQCN replacement (app/src/main/java/ru/noties/jlatexmath/
     JLatexMathAndroid.java) whose two resource doors try the APK assets
     first, then the downloaded pack directory in filesDir. Same public API
     (init/getResourceAsStream/loadTypeface); the engine bytecode links
     unchanged.
  4. writes app/libs/jlatexmath-thin.aar + prints the sha256 for pinning.

The on-demand hosts are the upstream repo's own assets, pinned by tag, the
same pair tesseract uses (jsDelivr first, raw.githubusercontent fail-over):
  https://cdn.jsdelivr.net/gh/noties/jlatexmath-android@0.2.0/jlatexmath/src/main/assets/
  https://raw.githubusercontent.com/noties/jlatexmath-android/v0.2.0/jlatexmath/src/main/assets/
(verified byte-identical to the AAR's copies — md5 spot-checked on the build
machine; the CDN and the AAR are built from the same tag).

Run from the repo root:  python3 scripts/thin_jlatexmath.py
Idempotent: regenerates the same artifact (zips are stored deterministically
here by sorting entries; byte-stable only modulo zip timestamps, so the sha256
is printed for the maintainer to eyeball, not machine-pinned in a gate).
"""
import hashlib
import os
import shutil
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "libs", "jlatexmath-thin.aar")
PIN = "https://repo1.maven.org/maven2/ru/noties/jlatexmath-android/0.2.0/jlatexmath-android-0.2.0.aar"
SEAM_CLASS = "ru/noties/jlatexmath/JLatexMathAndroid.class"
FONTS_PREFIX = "assets/org/scilab/forge/jlatexmath/fonts/"


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
    seam_removed = False
    kept = []

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as out:
        for n in sorted(names):
            data = src.read(n)
            if n.startswith(FONTS_PREFIX) and n.endswith(".ttf"):
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

    if not (ttf_removed == 30 and seam_removed):
        print(f"ERROR: unexpected AAR shape: ttf_removed={ttf_removed} "
              f"seam_removed={seam_removed}")
        return 1

    size = os.path.getsize(OUT)
    print(f"thin AAR written: {OUT}")
    print(f"  {size} B  sha256={hashlib.sha256(open(OUT, 'rb').read()).hexdigest()}")
    print(f"  removed {ttf_removed} TTFs ({ttf_bytes} B) + the seam class "
          f"(re-implmented in app sources)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
