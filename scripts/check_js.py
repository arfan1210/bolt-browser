#!/usr/bin/env python3
"""Extract the JS built by MediaHook.core() from the Java source and validate
its syntax with node --check. Also dumps the JS to a file for inspection."""
import re
import subprocess
import sys

SRC = "app/src/main/java/com/bolt/browser/media/MediaHook.java"

def main():
    java = open(SRC, encoding="utf-8").read()
    # locate the core() method body
    m = re.search(r'public static String core\(Prefs p\) \{(.*?)\n    \}', java, re.S)
    if not m:
        print("FAIL: core() method not found")
        return 1
    body = m.group(1)
    # substitute non-literal concatenations (e.g. + settings +) with a dummy
    # object literal so the extracted JS stays syntactically valid
    body = re.sub(r'\+\s*[a-zA-Z_][\w.]*\s*\+\s*"', '+ "{}" + "', body)
    # collect every string literal chunk: the leading `return "..."` plus
    # every subsequent `+ "..."` (the leading literal has no + before it)
    chunks = re.findall(r'(?:return|\+)\s*"((?:[^"\\]|\\.)*)"', body)
    if not chunks:
        print("FAIL: no string chunks found")
        return 1
    js = "".join(chunks)
    # unescape Java string escapes: \" -> ", \\ -> \, \n -> newline (Java source
    # level escapes only; the JS itself contains no \\n sequences in core())
    js = js.replace('\\"', '"').replace('\\\\', '\\')
    # handle remaining \n literals that are line separators in the JS
    js = js.replace('\\n', '\n')
    out = "/tmp/media_hook_core.js"
    with open(out, "w", encoding="utf-8") as f:
        f.write(js)
    r = subprocess.run(["node", "--check", out], capture_output=True, text=True)
    if r.returncode != 0:
        print("FAIL: JS syntax error")
        print(r.stderr)
        return 1
    print(f"OK: core() JS extracted ({len(js)} bytes) -> {out}, node --check passed")
    return 0

if __name__ == "__main__":
    sys.exit(main())
