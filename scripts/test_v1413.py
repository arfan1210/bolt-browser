#!/usr/bin/env python3
"""v1.4.13 release gate — the performance five-pack.

THE REPORT (2026-11-18): five performance problems, each pinned below:

1. "SEARCH IN BOLT" TEXT SELECTION. "The ACTION_PROCESS_TEXT intent filter
   was missing the MIME type, so Bolt does not show up in the Android
   floating text-selection toolbar." The v1.4.12 filters ALREADY carried
   text/plain (pinned here again — the regression would be silent); the
   field-report's visible gap this release closes is the LABEL: every
   PROCESS_TEXT filter now carries its OWN label ("Search in Bolt"),
   because the toolbar renders the resolved filter's label, not the app
   name. Pinned: 5 aliases x (WEB_SEARCH + SEND mime + PROCESS_TEXT mime
   + PROCESS_TEXT label), the label string, and handleIntent's
   EXTRA_PROCESS_TEXT path (re-pinned from v1412 — the door must stay
   wired).

2. THE OMNIBOX NETWORK DEBOUNCE. "The omnibox fires a network request for
   search suggestions on every single keystroke, causing network flooding
   and UI stutter." The suggestion network is BORN DEBOUNCED: a 300 ms
   Handler/Runnable in the TextWatcher (removeCallbacks BEFORE
   postDelayed — the debounce law), one background thread, a stale-answer
   guard, a rising generation counter, five-row cap, NEVER in incognito,
   a Settings off-switch. Pinned below + the osjson endpoints.

3. THE FAVICON LRU EVICTION. "The favicon cache downloads and saves icons
   but never evicts old ones, leading to infinite storage growth." Caps:
   1000 icons / 50 MB; eviction order lastModified ascending (LRU —
   load() refreshes the stamp as a USE stamp); the just-written file is
   never a candidate; victims leave the memory caches. The selection
   algorithm is PURE (selectEvictions) and is proven by a COMPILED+RUN
   TRANSCRIPTION (section E's probe — the v1.4.11 parse-gate law, applied
   to the one extractable pure function this release has).

4. THE WEBVIEW PRE-WARM POOL. "Creating a new WebView from scratch blocks
   the CPU for ~300 ms, causing a micro-stutter when opening new tabs."
   BoltApp posts a spare-WebView construction 250 ms out (never on the
   cold-start critical path, both processes, after the incognito suffix);
   Tab.buildWeb takes it with a plain-construction fallback. The context
   law: the spare rides a MutableContextWrapper and take() swaps the base
   to the real owner — pinned.

5. THE DOWNLOAD IF-RANGE CONTRACT. "If a download is interrupted, it
   currently restarts from 0 bytes." The Range/.part resume itself shipped
   earlier and is re-pinned here (Range: bytes=N-, 206 append, 200
   restart, 416 fresh-start); this release adds the SAFETY it lacked: the
   ETag is captured from every final response, persisted with the queue,
   and sent back as If-Range on resume, so a server-side file change
   answers 200 (clean restart) instead of a 206 that splices two different
   bodies into one corrupt file.

Why the only compiled probe is the eviction: the other four are
Android-lifecycle-coupled (Handler/TextWatcher, WebView construction,
HttpURLConnection) — none extracts into a pure static method. The pins
read CODE with comments and string bodies stripped (the strip law); the
debug build in the pipeline compiles every line they pin.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
fails = 0


def need(cond, why):
    global fails
    print(("  ok  " if cond else "  FAIL") + " - " + why)
    if not cond:
        fails += 1


SRC = os.path.join(ROOT, "app/src/main/java/com/bolt/browser")
RES = os.path.join(ROOT, "app/src/main/res")
gradle = open(os.path.join(ROOT, "app/build.gradle"), encoding="utf8").read()
strs = open(os.path.join(ROOT, "app/src/main/res/values/strings.xml"),
            encoding="utf8").read()
man = open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"),
           encoding="utf8").read()
rel = open(os.path.join(ROOT, "scripts/release.sh"), encoding="utf8").read()
ba = open(os.path.join(SRC, "ui/BrowserActivity.java"), encoding="utf8").read()
ns = open(os.path.join(SRC, "util/NetSuggest.kt"), encoding="utf8").read()
fs = open(os.path.join(SRC, "util/FaviconStore.kt"), encoding="utf8").read()
prefs = open(os.path.join(SRC, "util/Prefs.java"), encoding="utf8").read()
sia = open(os.path.join(SRC, "ui/SettingsItemsActivity.java"),
           encoding="utf8").read()
appj = open(os.path.join(SRC, "BoltApp.java"), encoding="utf8").read()
tabj = open(os.path.join(SRC, "core/Tab.java"), encoding="utf8").read()
dl = open(os.path.join(SRC, "core/DownloadEngine.java"), encoding="utf8").read()
wpool_path = os.path.join(SRC, "core/WebPool.java")
wpool = open(wpool_path, encoding="utf8").read() if os.path.exists(wpool_path) else ""


def strip_comments_strings(src):
    """Blank out // and /* */ comments and string bodies so pins read CODE,
    not the comments describing it."""
    out, i, n, mode = [], 0, len(src), None
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if mode is None:
            if c == "/" and nxt == "/":
                mode = "line"; i += 2; continue
            if c == "/" and nxt == "*":
                mode = "block"; i += 2; continue
            if c in "\"'":
                mode = c; out.append(c); i += 1; continue
            out.append(c); i += 1; continue
        if mode == "line":
            if c == "\n":
                mode = None; out.append("\n")
            i += 1; continue
        if mode == "block":
            if c == "*" and nxt == "/":
                mode = None; i += 2; continue
            if c == "\n":
                out.append("\n")
            i += 1; continue
        if c == "\\":
            i += 2; continue
        if mode == c:
            mode = None; out.append(c)
        i += 1
    return "".join(out)


ba_code = strip_comments_strings(ba)
ns_code = strip_comments_strings(ns)
fs_code = strip_comments_strings(fs)
prefs_code = strip_comments_strings(prefs)
sia_code = strip_comments_strings(sia)
appj_code = strip_comments_strings(appj)
tabj_code = strip_comments_strings(tabj)
dl_code = strip_comments_strings(dl)
wp_code = strip_comments_strings(wpool)

print("A — the version bump")
need(re.search(r"versionCode 145\b", gradle) is not None,  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 134 (Android only installs RISING codes)")  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName "1\.4\.31"', gradle) is not None,  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): the escaped-form recovery (the phantom-backslash corpse)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.31"')  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print("B — the About changelog (the label law)")
entries = re.findall(r"<item>([^<]+)</item>",
                     strs.split('name="about_changelog"')[1]) \
    if 'name="about_changelog"' in strs else []
need(entries and entries[12].startswith("1.4.18|"),  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])  # v1.4.31 re-point (2026-11-25): every About-changelog pin slid one slot down (1.4.31 landed at [0])
     "the newest entry is 1.4.13 (the label law)")
need(len(entries) > 3 and entries[15].startswith("1.4.15|"),  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack); the [1] pin names the PREVIOUS live release (1.4.13)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])  # v1.4.31 re-point (2026-11-25): every About-changelog pin slid one slot down (1.4.31 landed at [0])
     "the second entry is 1.4.12 (the previous live release)")
need("300 ms" in entries[17] and "pause" in entries[17],  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])  # v1.4.31 re-point (2026-11-25): every About-changelog pin slid one slot down (1.4.31 landed at [0])
     "the 1.4.13 line names the debounce")
need("1000 icons" in entries[17] and "50 MB" in entries[17],  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])  # v1.4.31 re-point (2026-11-25): every About-changelog pin slid one slot down (1.4.31 landed at [0])
     "the 1.4.13 line names the favicon caps")
need("pre-warmed" in entries[17],  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])  # v1.4.31 re-point (2026-11-25): every About-changelog pin slid one slot down (1.4.31 landed at [0])
     "the 1.4.13 line names the WebView pre-warm")
need("If-Range" in entries[17],  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])  # v1.4.31 re-point (2026-11-25): every About-changelog pin slid one slot down (1.4.31 landed at [0])
     "the 1.4.13 line names the resume validator")
need("Search in Bolt" in entries[17],  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): every About-changelog pin slid one slot down (1.4.17 landed at [0])  # v1.4.19 re-point (2026-11-19): every About-changelog pin slid one slot down (1.4.19 landed at [0])  # v1.4.20 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.20 landed at [0])  # v1.4.21 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.21 landed at [0])  # v1.4.23 re-point (2026-11-20): every About-changelog pin slid one slot down (1.4.23 landed at [0])  # v1.4.24 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.24 landed at [0])  # v1.4.25 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.25 landed at [0])  # v1.4.26 re-point (2026-11-21): every About-changelog pin slid one slot down (1.4.26 landed at [0])  # v1.4.27 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.27 landed at [0])  # v1.4.28 re-point (2026-11-22): every About-changelog pin slid one slot down (1.4.28 landed at [0])  # v1.4.29 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.29 landed at [0])  # v1.4.30 re-point (2026-11-24): every About-changelog pin slid one slot down (1.4.30 landed at [0])  # v1.4.31 re-point (2026-11-25): every About-changelog pin slid one slot down (1.4.31 landed at [0])
     "the 1.4.13 line names the toolbar label")

print("C — fix 1: the PROCESS_TEXT toolbar entry (mime + label, all faces)")
for action in ("android.intent.action.WEB_SEARCH",
               "android.intent.action.SEND",
               "android.intent.action.PROCESS_TEXT"):
    need(man.count(action) == 6,
         f"{action.split('.')[-1]} filter on all 6 faces (5 launcher + the incognito search face)")  # v1.4.17 re-point (2026-11-18): SearchIncognito — counts 5->6, mimes 10->12 (its label rides the _incognito string, so the search_in_bolt label count stays 5)
need(man.count("<activity-alias") == 6,
     "the manifest declares 6 aliases (5 launcher + SearchIncognito)")  # v1.4.17 re-point (2026-11-18): SearchIncognito — counts 5->6, mimes 10->12 (its label rides the _incognito string, so the search_in_bolt label count stays 5)
need(man.count('android:mimeType="text/plain"') == 12,  # v1.4.17 re-point (2026-11-18): SearchIncognito — counts 5->6, mimes 10->12 (its label rides the _incognito string, so the search_in_bolt label count stays 5)
     "SEND and PROCESS_TEXT each carry the text/plain mime (2 per alias)")
need(len(re.findall(
        r'<intent-filter android:label="@string/search_in_bolt">\s*'
        r'<action android:name="android\.intent\.action\.PROCESS_TEXT" />', man)) == 5,
     "all 5 PROCESS_TEXT filters carry their OWN label (the toolbar entry)")
need('name="search_in_bolt">Search in Bolt<' in strs,
     'the label string exists ("Search in Bolt")')
# the door stays wired (v1412 re-pin): the query ladder still funnels to
# loadInTab, posted, with the early return
hi = ba_code[ba_code.find("private void handleIntent(Intent i) {"):]
if "\n    }" in hi[1:]:
    hi = hi[:hi.find("\n    }", 1) + 6]
need("Intent.ACTION_PROCESS_TEXT.equals(act)" in hi
     and "Intent.EXTRA_PROCESS_TEXT" in hi,
     "PROCESS_TEXT still reads EXTRA_PROCESS_TEXT (the door stays wired)")
need("openQueryInNewTab(q.trim());" in hi,  # v1.4.14 re-point (2026-11-18): the new-tab law moved the door (see test_v1414 section D)
     "the search entry routes through openQueryInNewTab (v1.4.14)")

print("D — fix 2: the omnibox network debounce (300 ms, one request per pause)")
tw = ba_code[ba_code.find("et.addTextChangedListener(new TextWatcher() {"):
             ba_code.find("et.addTextChangedListener(new TextWatcher() {") + 700]
need("ad.update(s.toString());" in tw and "scheduleNetSuggest(ad, s.toString());" in tw,
     "every keystroke re-filters LOCALLY and parks the network pass")
need(re.search(r"if \(suggestPending != null\) suggestH\.removeCallbacks\(suggestPending\);",
               ba_code) is not None,
     "a new keystroke KILLS the pending request (removeCallbacks first)")
need(re.search(r"suggestH\.postDelayed\(suggestPending, 300L\);", ba_code) is not None,
     "the pause is 300 ms (postDelayed after the kill)")
need(re.search(r"private final Handler suggestH = new Handler\(Looper\.getMainLooper\(\)\);",
               ba_code) is not None,
     "the debounce rides the main-thread Handler")
fn = ba_code[ba_code.find("private void fireNetSuggest(SuggestAdapter ad, String q) {"):]
if "\n    }" in fn[1:]:
    fn = fn[:fn.find("\n    }", 1) + 6]
need("if (incognito) return;" in fn,
     "NEVER in incognito (the privacy law)")
need("if (!prefs.suggestNetwork()) return;" in fn,
     "the settings off-switch is honored")
need("NetSuggest.fetch(prefs.searchEngine(), query," in fn,
     "the request rides the SELECTED engine")
need("ad.setWebSuggestions(query, rows);" in fn,
     "the answer lands through the guarded setter")
od = ba_code[ba_code.find("protected void omniboxDismiss(View lay) {"):]
od = od[:od.find("\n    }", 1) + 6]
need("suggestH.removeCallbacks(suggestPending);" in od,
     "a dismissed omnibox cancels its pending request")
sa = ba_code[ba_code.find("protected class SuggestAdapter"):]
need("private String webQuery = \"\";" in sa and "private List<String> webRows" in sa,
     "the adapter carries the rows AND the query they answer")
need("q.trim().equalsIgnoreCase(wq)" in sa,   # v1.4.15 re-point (2026-11-18): the exact-query guard moved into buildRows(q, wq, wr) — the off-thread suggestion build snapshots webQuery/webRows on the main thread and the build carries the snapshot
     "rows render only while the field still holds their exact query")
need("void setWebSuggestions(String query, List<String> rows)" in sa,
     "the guarded setter exists")
# the request side
need("fun interface Callback" in ns_code,
     "the answer handoff is a SAM (the v1.4.12 cross-language law)")
need("Executors.newSingleThreadExecutor" in ns_code,
     "one background thread (requests serialize behind the debounce)")
need("gen.incrementAndGet()" in ns_code and "gen.get() == myGen" in ns_code,
     "a rising generation drops late answers")
need("connectTimeout = 4000" in ns_code and "readTimeout = 4000" in ns_code,
     "the request times out in 4 s (typing must never hang the list)")
for host in ("suggestqueries.google.com", "ac.duckduckgo.com",
             "api.bing.com", "search.brave.com", "startpage.com"):
    need(host in ns,
         f"the {host.split('.')[0] if 'duckduckgo' not in host else 'duckduckgo'} endpoint is mapped")
need("if (out.size >= 5) break" in ns_code,
     "at most five suggestion rows")
need('fun endpointFor(engineIdx: Int): String?' in ns_code
     and 'else -> null' in ns_code,
     "custom engines get no network pass (null endpoint)")
need('public boolean suggestNetwork() { return b("suggestNetwork", true); }' in prefs,
     "the pref exists, default ON (string-carrying pin reads raw)")
need("R.string.suggest_network" in sia_code and 'prefs.set("suggestNetwork", on)' in sia,
     "the Settings toggle is wired (string-carrying pin reads raw)")
need('name="suggest_network">Search suggestions' in strs,
     "the toggle string exists")

print("E — fix 3: the favicon LRU eviction (1000 icons / 50 MB)")
need("const val MAX_ICONS = 1000" in fs_code,
     "the count cap is 1000 icons")
need("const val MAX_BYTES = 50L * 1024 * 1024" in fs_code,
     "the size cap is 50 MB")
need("evictOverBudgetLocked(dir(c), f)" in fs_code,
     "every store() sweeps the budget (on the store executor)")
need(re.search(r"fun selectEvictions\(", fs_code) is not None
     and "if (count <= maxIcons && total <= maxBytes) return emptyList()" in fs_code
     and ".filter { it != keepIdx }" in fs_code
     and ".sortedBy { files[it].first }" in fs_code
     and "if (count <= maxIcons && total <= maxBytes) break" in fs_code,
     "the pure selection: both caps checked, keep excluded, oldest-first order")
need(re.search(r"try \{ f\.setLastModified\(System\.currentTimeMillis\(\)\) \}"
               r" catch \(ignored: Throwable\) \{\}", fs_code) is not None
     and fs_code.find("setLastModified") < fs_code.find("mem[h] = b; b"),
     "a disk HIT refreshes the use stamp (LRU measures READS, not captures)")
need("val host = hostOf(victim)" in fs_code and "mem.remove(host)" in fs_code,
     "evicted victims leave the memory cache (no corpse serving)")
need('it.name.endsWith(".png") && !it.name.endsWith(".miss")' in fs,
     "miss markers are neither counted nor evicted (string-carrying pin reads raw)")

# the compiled probe: the pure selection, transcribed and RUN
probe = r"""
import java.util.*;

public class EvictProbe {
    // TRANSCRIBED from FaviconStore.selectEvictions (v1.4.13). The gate
    // pins the Kotlin source's critical lines above; this runs the same
    // algorithm to prove its BEHAVIOR.
    static List<Integer> selectEvictions(List<long[]> files, int keepIdx,
                                         int maxIcons, long maxBytes) {
        int count = 0;
        long total = 0L;
        for (int i = 0; i < files.size(); i++) { count++; total += files.get(i)[1]; }
        if (count <= maxIcons && total <= maxBytes) return Collections.emptyList();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) if (i != keepIdx) order.add(i);
        order.sort(Comparator.comparingLong(i -> files.get((int) i)[0]));
        List<Integer> out = new ArrayList<>();
        for (int i : order) {
            if (count <= maxIcons && total <= maxBytes) break;
            out.add(i);
            count--;
            total -= files.get(i)[1];
        }
        return out;
    }

    static int fails = 0;
    static void check(String why, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FAIL") + " - " + why);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        // 1) under both caps: nothing is selected
        check("under caps selects nothing",
              selectEvictions(Arrays.asList(new long[]{1, 10}, new long[]{2, 10}), 0, 10, 1000L).isEmpty());
        // 2) count cap: oldest first until the count fits
        List<Integer> r2 = selectEvictions(Arrays.asList(
                new long[]{100, 1}, new long[]{200, 1}, new long[]{300, 1},
                new long[]{400, 1}, new long[]{500, 1}), 4, 3, 1000L);
        check("count cap evicts oldest-first (100,200)", r2.equals(Arrays.asList(0, 1)));
        // 3) bytes cap: the sweep continues until BOTH caps hold
        List<Integer> r3 = selectEvictions(Arrays.asList(
                new long[]{100, 40_000_000}, new long[]{200, 40_000_000},
                new long[]{300, 40_000_000}, new long[]{400, 40_000_000}), 3, 10, 50_000_000L);
        check("bytes cap sweeps until under (f0,f1,f2)", r3.equals(Arrays.asList(0, 1, 2)));
        // 4) the keep file is NEVER a candidate — even when it is the oldest
        List<Integer> r4 = selectEvictions(Arrays.asList(
                new long[]{100, 40_000_000}, new long[]{200, 20_000_000},
                new long[]{300, 1_000_000}), 0, 10, 50_000_000L);
        check("keep (oldest, 40MB) survives; the next-oldest goes",
              r4.equals(Arrays.asList(1)));
        // 5) pure LRU, not size-optimization: the tiny OLDEST goes first,
        // then the fat one — the sweep walks recency order until the caps
        // hold (the user's law: "delete the oldest files on disk")
        List<Integer> r5 = selectEvictions(Arrays.asList(
                new long[]{100, 1}, new long[]{200, 60_000_000},
                new long[]{300, 1}), 2, 10, 50_000_000L);
        check("LRU order rules: tiny-oldest then the fat file, until the cap holds",
              r5.equals(Arrays.asList(0, 1)));
        System.out.println(fails == 0 ? "PROBE OK" : "PROBE BAD (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
"""
tmp = tempfile.mkdtemp(prefix="bolt_v1413_probe_")
open(os.path.join(tmp, "EvictProbe.java"), "w").write(probe)
# release.sh puts the JDK on PATH before the gates; standalone runs fall
# back to the workspace's grafted JDK (the README environment note)
GJ = "/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64/bin"
javac = "javac" if shutil.which("javac") else os.path.join(GJ, "javac")
java = "java" if shutil.which("java") else os.path.join(GJ, "java")
pr = subprocess.run([javac, "-d", tmp, os.path.join(tmp, "EvictProbe.java")],
                    capture_output=True, text=True)
if pr.returncode != 0:
    print("  FAIL - probe compiled (javac):\n" + pr.stderr[:800])
    fails += 1
else:
    print("  ok  - probe compiled (javac)")
    pr2 = subprocess.run([java, "-cp", tmp, "EvictProbe"],
                         capture_output=True, text=True)
    print(pr2.stdout.rstrip())
    if pr2.returncode != 0:
        fails += 1

print("F — fix 4: the WebView pre-warm pool")
need(os.path.exists(wpool_path), "core/WebPool.java exists")
need("new MutableContextWrapper(app.getApplicationContext())" in wp_code,
     "the spare rides a MutableContextWrapper over the app context")
need("((MutableContextWrapper) cur).setBaseContext(real);" in wp_code,
     "take() swaps the base to the real owner (the context law)")
need("public static synchronized void prewarm(Context app)" in wp_code
     and "public static synchronized WebView take(Context real)" in wp_code,
     "prewarm + take, one slot, synchronized")
need("new com.bolt.browser.ui.BoltWeb(" in wp_code,
     "the spare is a BoltWeb (the selection-ActionMode class)")
need("WebPool.prewarm(BoltApp.this)" in appj_code,
     "BoltApp pre-warms at process start")
need(re.search(r"appH\.postDelayed\(\(\) -> \{\s*try \{ com\.bolt\.browser\.core\.WebPool"
               r"\.prewarm\(BoltApp\.this\); \}", appj_code) is not None
     and "250L);" in appj,
     "the pre-warm is POSTED 250 ms out (never on the cold-start path)")
need(appj_code.find("WebPool.prewarm") > appj_code.find("UserscriptEngine.init(this);"),
     "the pre-warm is posted after the incognito suffix is set (same onCreate)")
need("WebView w = com.bolt.browser.core.WebPool.take(c);" in tabj_code
     and "w = new com.bolt.browser.ui.BoltWeb(c);" in tabj_code,
     "Tab.buildWeb takes the spare, falls back to plain construction")  # v1.4.19 re-point (2026-11-19): the construction guard wrapped the fallback in try/catch + the incognito-suffix refusal (the crash-loop hardening — see test_v1419 section C); the take-then-fallback shape is unchanged

print("G — fix 5: the download resume contract (Range + If-Range)")
need('conn.setRequestProperty("Range", "bytes=" + part.length() + "-");' in dl,
     "the .part length rides the Range header (string-carrying pin reads raw)")
need('boolean resume = part.exists() && part.length() > 0;' in dl_code,
     "a .part on disk means resume")
need('if (it.etag != null && !it.etag.isEmpty())' in dl_code
     and 'conn.setRequestProperty("If-Range", it.etag);' in dl,
     "the captured ETag rides If-Range on resume (string-carrying pin reads raw)")
need('String et = conn.getHeaderField("ETag");' in dl,
     "every final response's ETag is captured (raw pin)")
need('o.put("etag", etag == null ? "" : etag);' in dl,
     "the ETag is persisted with the queue (raw pin)")
need('it.etag = o.optString("etag");' in dl,
     "the ETag survives the load (raw pin)")
need(dl_code.count('it.etag = "";') >= 2,
     "both restart paths (200-with-part, 416) clear the dead validator")
need("if (code == 206) {" in dl_code and 'code == 200 || code == 201 || code == 204' in dl_code
     and "code == 416 && resume" in dl_code,
     "the 206 append / 200 restart / 416 fresh-start ladder is intact")

print("H — the gate is wired into the pipeline")
need("python3 scripts/test_v1413.py" in rel,
     "release.sh runs this gate before building")
need(rel.find("test_v1412.py") < rel.find("test_v1413.py"),
     "the gate runs after v1412's (release order)")

print()
print("V1413 GATE:", "OK" if fails == 0 else f"BAD ({fails} fail)")
sys.exit(0 if fails == 0 else 1)
