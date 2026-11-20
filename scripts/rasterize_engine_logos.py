#!/usr/bin/env python3
"""v1.4.22 — THE ENGINE-LOGO RASTERIZER (the raster turn).

The five official engine marks shipped as vector drawables drew
differently on every device (vector rasterization differences across
OEM GPU/driver stacks left users reporting warped or off-color logos).
v1.4.22 retires the vectors and ships fixed 64x64 PNGs in
res/drawable-nodpi/ so every device draws the identical mark.

This script regenerates those PNGs from the retired vector sources kept
in scripts/engine_vectors/ (extracted verbatim from the v1.4.21 tree —
the "official marks" are the simplified public-domain-style renditions
already in the repo). The pipeline: Android vector XML -> SVG ->
cairosvg at 4x supersampling (256x256) -> PIL LANCZOS downsample to
64x64 -> optimized PNG (the flat two-color Bing mark additionally goes
palette-mode, as shipped).

BYTE-FIDELITY NOTE: the committed res/drawable-nodpi/*.png are the
byte-exact artifacts of the original v1.4.22 rasterization run. This
pipeline reproduces google/ddg/brave/startpage byte-exactly (verified
0-byte drift, sha-equal); the bing mark reproduces content-exactly
(0 differing pixels, same 241-color palette) but through a slightly
different palette encoder, so its bytes can drift on regeneration —
treat the committed PNGs as canonical (re-run only when a mark's
vector definition changes, then commit the new bytes wholesale).

Usage:  python3 scripts/rasterize_engine_logos.py            # regenerate
        python3 scripts/rasterize_engine_logos.py --check    # report drift
"""
import os
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
VECTORS = os.path.join(HERE, 'engine_vectors')
OUTDIR_DEFAULT = os.path.join(HERE, '..', 'app', 'src', 'main', 'res', 'drawable-nodpi')
ENGINES = ['google', 'ddg', 'bing', 'brave', 'startpage']
# the flat two-color bing mark ships palette-mode; the rest ship RGBA
PALETTE_MODE = {'bing'}
SIZE = 64
SUPERSAMPLE = 4

ANDROID_NS = '{http://schemas.android.com/apk/res/android}'


def attr(el, name):
    return el.get(ANDROID_NS + name)


def vector_to_svg(vxml):
    root = ET.fromstring(vxml)
    if root.tag != 'vector':
        raise ValueError('not an Android vector: ' + root.tag)
    vw = float(attr(root, 'viewportWidth') or 24)
    vh = float(attr(root, 'viewportHeight') or 24)
    paths = []
    for p in root.iter('path'):
        d = attr(p, 'pathData')
        if not d:
            continue
        fill = attr(p, 'fillColor') or '#000000'
        alpha = attr(p, 'fillAlpha')
        if alpha is not None:
            a = float(alpha)
            if a < 1.0:
                fill += f'{int(round(a * 255)):02X}'
        stroke = attr(p, 'strokeColor')
        sw = attr(p, 'strokeWidth') or '1'
        strokexml = (f' stroke="{stroke}" stroke-width="{sw}"'
                     if stroke else '')
        paths.append(f'<path d="{d}" fill="{fill}"{strokexml}/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}"'
            f' width="{SIZE * SUPERSAMPLE}" height="{SIZE * SUPERSAMPLE}">' + ''.join(paths) + '</svg>')


def render(vxml):
    """Vector XML text -> (PIL Image, PNG bytes) at 64x64, as shipped."""
    import cairosvg
    from PIL import Image
    import io
    svg = vector_to_svg(vxml)
    big = cairosvg.svg2png(bytestring=svg.encode('utf-8'),
                           output_width=SIZE * SUPERSAMPLE,
                           output_height=SIZE * SUPERSAMPLE)
    im = Image.open(io.BytesIO(big)).convert('RGBA')
    im = im.resize((SIZE, SIZE), Image.LANCZOS)
    if os.path.basename(vxml if isinstance(vxml, str) else '') or True:
        pass  # mode decided by caller via name
    return im


def encode(im, palette_mode):
    from PIL import Image
    import io
    if palette_mode:
        # EXACT palette (no quantization loss): one entry per distinct
        # RGBA color actually present; alpha rides the tRNS chunk, so the
        # decoded pixels are bit-identical to the RGBA source.
        colors = sorted(set(im.getdata()))
        if len(colors) <= 256:
            lookup = {c: i for i, c in enumerate(colors)}
            p = Image.new('P', im.size)
            p.putpalette([c for rgb in colors for c in rgb[:3]])
            p.putdata([lookup[px] for px in im.getdata()])
            p.info['transparency'] = bytes(c[3] for c in colors)
            im = p
    buf = io.BytesIO()
    im.save(buf, 'PNG', optimize=True)
    return buf.getvalue()


def main():
    check_only = '--check' in sys.argv
    outdir = OUTDIR_DEFAULT
    if not check_only and not os.path.isdir(outdir):
        os.makedirs(outdir, exist_ok=True)
    drift = []
    for name in ENGINES:
        src = os.path.join(VECTORS, f'ic_engine_{name}.xml')
        dst = os.path.join(outdir, f'ic_engine_{name}.png')
        im = render(open(src, encoding='utf-8').read())
        png = encode(im, name in PALETTE_MODE)
        if check_only:
            if not os.path.exists(dst):
                drift.append(f'{name}: MISSING {dst}')
            else:
                shipped = open(dst, 'rb').read()
                if shipped == png:
                    print(f'{name}: byte-exact')
                    continue
                # content comparison (pixel-exact regeneration is still OK)
                from PIL import Image
                import io as _io
                a = Image.open(_io.BytesIO(png)).convert('RGBA')
                b = Image.open(dst).convert('RGBA')
                if a.tobytes() == b.tobytes():
                    drift.append(f'{name}: bytes differ, CONTENT identical')
                else:
                    drift.append(f'{name}: bytes AND content differ')
        else:
            open(dst, 'wb').write(png)
            print(f'wrote {dst} ({len(png)} bytes)')
    if check_only and drift:
        print('DRIFT REPORT (regenerated vs committed):')
        for d in drift:
            print('  ' + d)
    elif check_only:
        print('all five committed PNGs match the regeneration byte-exactly')


if __name__ == '__main__':
    main()
