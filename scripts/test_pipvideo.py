#!/usr/bin/env python3
"""v1.3.65 — extract the JS built by util/PipVideo.kt (the imported PiP
takeover engine) from the Kotlin source, validate its syntax with
node --check, and dump the assembled payloads to /tmp for the behavioral
mock-DOM suite (scripts/test_pipvideo.js — same pattern as check_js.py +
test_media_hook.js for MediaHook.core()).

Extracts THREE payloads:
  /tmp/pip_video_pin.js        — pinJs(pipMode=true)   (the PiP takeover)
  /tmp/pip_video_pin_strict.js — pinJs(pipMode=false)  (the wedged-enter
                                 fullscreen rescue: playing-only selector)
  /tmp/pip_video_unpin.js      — unpinJs()             (the full restore)
"""
import re
import subprocess
import sys
import pathlib

SRC = "app/src/main/java/com/bolt/browser/util/PipVideo.kt"


def strip_comments(text):
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text


def lits(fragment):
    """every double-quoted Kotlin string literal in order, unescaped."""
    out = re.findall(r'"((?:[^"\\]|\\.)*)"', fragment)
    return [c.replace('\\"', '"').replace("\\\\", "\\") for c in out]


def main():
    src = pathlib.Path(SRC).read_text(encoding="utf-8")
    src = strip_comments(src)

    i_pin = src.find("fun pinJs(")
    i_unpin = src.find("fun unpinJs(")
    i_body = src.find("private fun unpinBody()")
    i_params = src.find("fun params(")
    if i_pin < 0 or i_unpin < 0 or i_body < 0 or i_params < 0:
        print("FAIL: PipVideo.kt structure not found (pinJs/unpinJs/unpinBody/params)")
        return 1
    pin = src[i_pin:i_unpin]
    unpin = src[i_unpin:i_body]
    body = src[i_body:i_params]  # unpinBody ONLY (params/actions live after it)

    # the shared unpin body (inlined into the pin's re-pin cycle AND into
    # unpinJs itself — substitute the identifier in both places)
    unpin_body_js = "".join(lits(body))
    unpin = re.sub(r"\+\s*unpinBody\(\)", '+ "@@UNPIN@@" +', unpin)

    # the selector ternary: if (pipMode) { A } else { B }
    i_if = pin.find("if (pipMode) {")
    i_else = pin.find("} else {", i_if)
    i_ret = pin.find('return "(function', i_else)
    if i_if < 0 or i_else < 0 or i_ret < 0:
        print("FAIL: pinJs selector ternary / return not found")
        return 1
    sel_pip = "".join(lits(pin[i_if:i_else]))
    sel_strict = "".join(lits(pin[i_else:i_ret]))

    # the linear return expression; splice the selector + unpinBody at their
    # identifiers so the extraction cannot drift from the Kotlin
    retsrc = pin[i_ret:]
    retsrc = re.sub(r"\+\s*selector\b", '+ "@@SEL@@" +', retsrc)
    retsrc = re.sub(r"\+\s*unpinBody\(\)", '+ "@@UNPIN@@" +', retsrc)
    tail = "".join(lits(retsrc))

    js_pip = tail.replace("@@SEL@@", sel_pip).replace("@@UNPIN@@", unpin_body_js)
    js_strict = tail.replace("@@SEL@@", sel_strict).replace("@@UNPIN@@", unpin_body_js)
    js_unpin = "".join(lits(unpin)).replace("@@UNPIN@@", unpin_body_js)

    if not js_pip or not js_unpin or "100vw" not in js_pip:
        print("FAIL: assembled JS looks wrong (empty or missing the takeover CSS)")
        return 1

    pathlib.Path("/tmp/pip_video_pin.js").write_text(js_pip, encoding="utf-8")
    pathlib.Path("/tmp/pip_video_pin_strict.js").write_text(
        js_strict, encoding="utf-8")
    pathlib.Path("/tmp/pip_video_unpin.js").write_text(js_unpin, encoding="utf-8")

    rc = 0
    for name, path in (("pin(pip)", "/tmp/pip_video_pin.js"),
                       ("pin(strict)", "/tmp/pip_video_pin_strict.js"),
                       ("unpin", "/tmp/pip_video_unpin.js")):
        try:
            r = subprocess.run(["node", "--check", path],
                               capture_output=True, text=True)
            if r.returncode != 0:
                print("FAIL: %s has a syntax error:\n%s" % (name, r.stderr))
                rc = 1
            else:
                print("  OK  %s syntax (%d bytes)" % (name, len(pathlib.Path(path).read_text())))
        except FileNotFoundError:
            print("FAIL: node not available")
            return 1
    print("PASS" if rc == 0 else "FAIL")
    return rc


if __name__ == "__main__":
    sys.exit(main())
