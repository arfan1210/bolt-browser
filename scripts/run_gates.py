#!/usr/bin/env python3
"""run_gates.py — run the FULL release-script gate suite in order, exactly
the commands scripts/release.sh's gates section runs (extracted live from
release.sh so the runner never drifts), minus the Robolectric smoke (that
ran separately via gradle testDebugUnitTest) and minus everything after the
build phase (this session stops before signing per policy).
"""
import re
import subprocess
import sys

rels = open("scripts/release.sh", encoding="utf8").read()
gates = rels.split("if have gates;", 1)[1].split("mark gates", 1)[0]
cmds = []
for ln in gates.split("\n"):
    s = ln.strip()
    if not s or s.startswith("#"):
        continue
    # the JDK exports ride the release script env; the runner sets them too
    if s.startswith("JDK_DIR") or s.startswith("export"):
        continue
    if re.match(r"^(python3|node|javac|java) ", s):
        cmds.append(s)

# slice mode: run_gates.py <from> <to>  (1-indexed, inclusive) — the sandbox
# reaps processes between tool calls, so the suite runs in resumable chunks
# with the results marker scripts/.gate-results.txt
lo, hi = (int(sys.argv[1]), int(sys.argv[2])) if len(sys.argv) > 2 else (1, len(cmds))
print(f"{len(cmds)} gate commands; running [{lo}..{hi}]")
env_jdk = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64"
import os
os.environ["PATH"] = env_jdk + "/bin:" + os.environ["PATH"]
os.environ["JAVA_HOME"] = env_jdk

bad = 0
for i, c in enumerate(cmds, 1):
    if i < lo or i > hi:
        continue
    print(f"[{i}/{len(cmds)}] {c}")
    r = subprocess.run(c, shell=True, capture_output=True, text=True)
    tail = "\n".join((r.stdout + r.stderr).strip().split("\n")[-3:])
    if r.returncode != 0:
        bad += 1
        print(f"    FAILED (rc={r.returncode}): {tail}")
    else:
        ok_line = [l for l in (r.stdout + r.stderr).split("\n") if "green" in l.lower() or "OK" in l]
        print(f"    ok  {ok_line[-1].strip()[:110] if ok_line else tail[-110:]}")
    with open("scripts/.gate-results.txt", "a", encoding="utf8") as f:
        f.write(("PASS" if r.returncode == 0 else "FAIL") + f"\t{i}\t{c}\n")
print()
if bad:
    print(f"GATE SLICE [{lo}..{hi}]: {bad} FAILURE(S)")
    sys.exit(1)
print(f"GATE SLICE [{lo}..{hi}]: ALL GREEN")
