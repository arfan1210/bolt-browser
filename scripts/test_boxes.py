#!/usr/bin/env python3
"""test_boxes.py — v1.3.77 gate: replay REAL box dialects through the
parseBoxes logic (a faithful Python port that MUST track AiBridge.kt's
parseBoxes; edit both together).

WHY THIS EXISTS (Lesson 61 — replay a REAL response shape before shipping):
v1.3.76 asked the vision model for [x,y,w,h] PIXEL boxes, the model answered
in its post-trained native dialect (box_2d = [ymin,xmin,ymax,xmax] normalized
0-1000), and the parser read those 0-1000 values as pixels of the 2200 px
sent image — semi-random placement that the field reported as "got worse".
This gate replays every dialect a real provider emits:

  B1  the NATIVE Gemini box_2d 0-1000 corner order (the primary path)
  B2  box_2d with a >1000 value → the model answered pixels, corner order
  B3  box_2d with all values ≤1.5 → 0-1 float fractions, corner order
  B4  box_2d corner swap (xmax<xmin) → tolerated, not dropped
  B5  th dialects: 0-1000 / fraction / pixels-of-sent-image
  B6  legacy "box":[x,y,w,h] 0-1000 grid (v1.3.74/75 OpenAI-compatible)
  B7  legacy "box":[x,y,w,h] 0-1 fractions
  B8  legacy "box":[x,y,w,h] raw pixels of the sent image
  B9  legacy flat x/y/w/h keys (the v1.3.74 shape)
  B10 junk/degenerate boxes are dropped, not crash
  B11 the v1.3.76 FAILURE SHAPE: a 0-1000 grid answer parsed under the
      OLD pixel contract lands in a wrong place — asserted as a warning
      regression note (documents why the prompt changed)
"""
import json
import sys

# the sent image is a 1080x2400 capture downscaled to the 2200 long edge
IW, IH = 990, 2200


def parse_boxes(txt, iw=IW, ih=IH):
    """faithful port of AiBridge.parseBoxes — returns list of dicts."""
    arr = json.loads(txt)
    # ---- response-level dialect detection ----
    b2_pixels = False
    b2_floats = False
    saw_b2 = False
    for o in arr:
        b2 = o.get("box_2d")
        if not isinstance(b2, list):
            continue
        saw_b2 = True
        mx = max(abs(float(b2[j])) for j in range(min(4, len(b2))))
        if mx > 1000.5:
            b2_pixels = True
            break
    if saw_b2 and not b2_pixels:
        all_tiny = True
        for o in arr:
            b2 = o.get("box_2d")
            if not isinstance(b2, list):
                continue
            for j in range(min(4, len(b2))):
                if abs(float(b2[j])) > 1.5:
                    all_tiny = False
                    break
            if not all_tiny:
                break
        b2_floats = all_tiny
    out = []
    for o in arr:
        orig = str(o.get("text", "")).strip()
        trans = str(o.get("translation", "")).strip()
        if not orig or not trans:
            continue
        th = 0.0
        vert = bool(o.get("vertical", False))
        b2 = o.get("box_2d")
        if isinstance(b2, list) and len(b2) >= 4:
            a, b, c, d = (float(b2[i]) for i in range(4))
            if a < 0 or b < 0 or c < 0 or d < 0:
                continue
            if b2_pixels and iw > 0 and ih > 0:
                y0, x0, y1, x1 = a / ih, b / iw, c / ih, d / iw
            elif b2_floats:
                y0, x0, y1, x1 = a, b, c, d
            else:
                y0, x0, y1, x1 = a / 1000, b / 1000, c / 1000, d / 1000
            if y1 < y0:
                y0, y1 = y1, y0
            if x1 < x0:
                x0, x1 = x1, x0
            if y1 <= y0 or x1 <= x0:
                continue
            x, y, w, h = x0, y0, x1 - x0, y1 - y0
            th = float(o.get("th", 0.0))
            if th <= 0:
                th = 0.0
            elif th <= 1.5:
                pass
            elif th <= 1000:
                th /= 1000.0
            elif ih > 0:
                th /= ih
            else:
                th = 0.0
        else:
            box = o.get("box")
            if isinstance(box, list) and len(box) >= 4:
                x, y, w, h = (float(box[i]) for i in range(4))
            else:
                x = float(o.get("x", -1))
                y = float(o.get("y", -1))
                w = float(o.get("w", -1))
                h = float(o.get("h", -1))
            if x < 0 or y < 0 or w <= 0 or h <= 0:
                continue
            if not (x <= 1.5 and y <= 1.5 and w <= 1.5 and h <= 1.5
                    and (x + w) <= 1.51 and (y + h) <= 1.51):
                if (x > 1000 or y > 1000 or w > 1000 or h > 1000) and iw > 0 and ih > 0:
                    x /= iw; w /= iw; y /= ih; h /= ih
                else:
                    x /= 1000.0; y /= 1000.0; w /= 1000.0; h /= 1000.0
        if x >= 1 or y >= 1:
            continue
        if w > 1:
            w = 1 - x
        if h > 1:
            h = 1 - y
        if w <= 0 or h <= 0:
            continue
        out.append({"x": round(x, 4), "y": round(y, 4), "w": round(w, 4),
                    "h": round(h, 4), "th": round(th, 4), "vert": vert,
                    "orig": orig, "trans": trans})
    return out


def near(a, b, eps=0.01):
    return abs(a - b) <= eps


def main():
    # B1 — the native Gemini dialect: box_2d [ymin,xmin,ymax,xmax] 0-1000
    r = parse_boxes(json.dumps([
        {"box_2d": [100, 200, 300, 700], "th": 40, "vertical": False,
         "text": "こんにちは", "translation": "Hello"}]))
    assert len(r) == 1, f"B1: expected 1 box, got {len(r)}"
    b = r[0]
    assert near(b["x"], 0.2) and near(b["y"], 0.1) and near(b["w"], 0.5) and near(b["h"], 0.2), \
        f"B1: native 0-1000 grid misread: {b}"
    assert near(b["th"], 0.04), f"B1: th 0-1000 misread: {b}"
    print("OK: B1 native box_2d 0-1000 corner order (ymin,xmin,ymax,xmax)")

    # B2 — a value >1000 in box_2d → the model answered PIXELS, corner order
    r = parse_boxes(json.dumps([
        {"box_2d": [600, 400, 1500, 900], "th": 50, "vertical": False,
         "text": "縦", "translation": "vertical"}]))
    b = r[0]
    assert near(b["y"], 600 / 2200) and near(b["x"], 400 / 990) and \
        near(b["h"], 900 / 2200) and near(b["w"], 500 / 990), f"B2: pixel dialect misread: {b}"
    print("OK: B2 box_2d >1000 → pixels of the sent image, corner order")

    # B3 — all box_2d values ≤1.5 → 0-1 float fractions
    r = parse_boxes(json.dumps([
        {"box_2d": [0.1, 0.2, 0.5, 0.8], "th": 0.03, "vertical": False,
         "text": "hi", "translation": "ho"}]))
    b = r[0]
    assert near(b["x"], 0.2) and near(b["y"], 0.1) and near(b["w"], 0.6) and near(b["h"], 0.4), \
        f"B3: float dialect misread: {b}"
    assert near(b["th"], 0.03), f"B3: th fraction misread: {b}"
    print("OK: B3 box_2d 0-1 float fractions")

    # B4 — a swapped corner pair is tolerated, not dropped
    r = parse_boxes(json.dumps([
        {"box_2d": [300, 700, 100, 200], "th": 40, "vertical": False,
         "text": "ab", "translation": "cd"}]))
    assert len(r) == 1 and near(r[0]["x"], 0.2) and near(r[0]["y"], 0.1), f"B4: {r}"
    print("OK: B4 swapped corner pair tolerated")

    # B5 — th dialects: fraction / 0-1000 / pixels-of-sent-image
    r = parse_boxes(json.dumps([
        {"box_2d": [0, 0, 100, 100], "th": 0.02, "text": "a", "translation": "b"},
        {"box_2d": [0, 0, 100, 100], "th": 30, "text": "c", "translation": "d"},
        {"box_2d": [0, 0, 100, 100], "th": 44, "text": "e", "translation": "f"},
        {"box_2d": [0, 0, 100, 100], "th": 1500, "text": "g", "translation": "h"}]))
    ths = [b["th"] for b in r]
    assert near(ths[0], 0.02) and near(ths[1], 0.03) and near(ths[2], 0.044) and \
        near(ths[3], 1500 / 2200), f"B5: th dialects misread: {ths}"
    print("OK: B5 th dialects (fraction / 0-1000 / pixels)")

    # B6 — legacy "box":[x,y,w,h] on the 0-1000 grid
    r = parse_boxes(json.dumps([
        {"box": [200, 100, 500, 200], "th": 40, "text": "x", "translation": "y"}]))
    b = r[0]
    assert near(b["x"], 0.2) and near(b["y"], 0.1) and near(b["w"], 0.5) and near(b["h"], 0.2), \
        f"B6: legacy grid misread: {b}"
    print("OK: B6 legacy box [x,y,w,h] 0-1000 grid")

    # B7 — legacy 0-1 fractions
    r = parse_boxes(json.dumps([
        {"box": [0.2, 0.1, 0.5, 0.2], "text": "x", "translation": "y"}]))
    b = r[0]
    assert near(b["x"], 0.2) and near(b["y"], 0.1) and near(b["w"], 0.5) and near(b["h"], 0.2), \
        f"B7: legacy fractions misread: {b}"
    print("OK: B7 legacy box 0-1 fractions")

    # B8 — legacy raw pixels of the sent image (any value > 1000 → pixels)
    r = parse_boxes(json.dumps([
        {"box": [400, 1100, 300, 440], "text": "x", "translation": "y"}]))
    b = r[0]
    assert near(b["x"], 400 / 990) and near(b["y"], 1100 / 2200) and \
        near(b["w"], 300 / 990) and near(b["h"], 440 / 2200), f"B8: pixel legacy misread: {b}"
    # out-of-range pixel x is dropped cleanly, not clamped into nonsense
    r = parse_boxes(json.dumps([
        {"box": [2000, 1100, 300, 440], "text": "x", "translation": "y"}]))
    assert len(r) == 0, f"B8: out-of-range pixel box must drop: {r}"
    print("OK: B8 legacy box raw pixels of the sent image")

    # B9 — legacy flat x/y/w/h keys (the v1.3.74 shape)
    r = parse_boxes(json.dumps([
        {"x": 200, "y": 100, "w": 500, "h": 200, "text": "x", "translation": "y"}]))
    b = r[0]
    assert near(b["x"], 0.2) and near(b["y"], 0.1) and near(b["w"], 0.5) and near(b["h"], 0.2), \
        f"B9: flat keys misread: {b}"
    print("OK: B9 legacy flat x/y/w/h keys")

    # B10 — junk/degenerate boxes drop cleanly
    r = parse_boxes(json.dumps([
        {"box_2d": [0, 0, 0, 0], "text": "a", "translation": "b"},
        {"box_2d": [-5, 10, 100, 100], "text": "c", "translation": "d"},
        {"box_2d": [100, 100, 100, 500], "text": "e", "translation": "f"},
        {"text": "no box", "translation": "nothing"},
        {"box_2d": [0, 0, 100, 100], "text": "", "translation": "empty orig"},
        {"box_2d": [0, 0, 100, 100], "text": "ok", "translation": "good"}]))
    assert len(r) == 1 and r[0]["orig"] == "ok", f"B10: junk handling broke: {r}"
    print("OK: B10 degenerate boxes dropped, no crash")

    # B11 — the v1.3.76 FAILURE SHAPE, documented: the same native answer
    # parsed under the old pixel contract ([x,y,w,h] pixels of iw×ih) would
    # land at x=0.1*990... i.e. SEMI-RANDOM. The new parser reads it right.
    native = [100, 200, 300, 700]  # the model's native 0-1000 corners
    old_style = {"x": native[0] / IW, "y": native[1] / IH,
                 "w": native[2] / IW, "h": native[3] / IH}
    new_style = parse_boxes(json.dumps([
        {"box_2d": native, "text": "t", "translation": "u"}]))[0]
    assert not (near(new_style["x"], old_style["x"]) and near(new_style["y"], old_style["y"])), \
        "B11: the v1.3.76 failure shape was not actually fixed"
    assert near(new_style["x"], 0.2) and near(new_style["y"], 0.1)
    print("OK: B11 the v1.3.76 misparse shape is fixed (native answer now lands correctly)")

    print("test_boxes.py: all B1-B11 green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
