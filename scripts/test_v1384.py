#!/usr/bin/env python3
"""v1.3.84 gate — the three-pack: relative skip, audited history, uploads.

Field report (v1.3.83): "A lot of things work fine, good job. Make the skip
silence relative (like 4 times of what speed I am watching, if normal speed
is 1x, silence wil be 4x, if normal is 1.5x, silence will be 6x and so on).
The back and forward of the browser does not work properly most of the time.
When I am in facebook post 1, I go to post 2, pressing the back in toolbar
keeps me in post 2 but the search bar shows url of post 1. The back seems to
work mostly if there is a domain change. Also the file uploads is maybe
broken, uploading files through zai gave 401 unauthorised error."

Contracts pinned here (S1-S5):
  S1 THE RELATIVE SKIP FACTOR: applyTo's ONE rate authority multiplies the
     base speed by 4 during silence (2.5 before) — silence always rides at
     base x 4 whatever the slider says (1x -> 4x, 1.5x -> 6x, 2x -> 8x);
     test_media_hook.js pins all three products.
  S2 THE HISTORY NAVIGATOR: util/HistoryNav.kt exists (new code = Kotlin,
     the standing language rule), carries the two-stage audit (1.1 s no-op
     check, 4 s hang check), asks the RENDERER its own location.href (the
     browser-side index alone lies — that is the bug), forces with a plain
     loadUrl on the dropped-step signature (index unmoved + nothing in
     flight, or renderer still on the departure URL), never forces on an
     ambiguous answer (drift logs instead), stands down when detached, and
     logs nav step / nav force / nav drift diag lines for the next report.
  S3 THE FIVE CALL SITES: toolbar back (goBack), toolbar forward, edge-swipe
     forward, system onBackPressed and the tab-history sheet rows ALL route
     through HistoryNav.step; the audit is retired by onPageStarted +
     doUpdateVisitedHistory noteNav when the page moves on without the step
     (a forced loadUrl must never yank a user's own navigation).
  S4 THE BODY-CARRIER LAW: shouldInterceptRequest reads the HTTP method and
     returns null for any method beyond GET/HEAD BEFORE both interception
     surfaces — the data saver never stubs an upload, the adblock network
     pass never blocks one. (zai's upload is a cross-origin POST with a
     conditionally-attached Bearer header; a stubbed link in its auth chain
     sends the real POST unauthenticated -> the reported 401.)
  S5 version pin: 107 / 1.3.94.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app/src/main/java/com/bolt/browser"

failures = []


def need(cond, msg):
    if not cond:
        failures.append(msg)
        print(f"  FAIL {msg}")


media = (APP / "media/MediaHook.java").read_text(encoding="utf-8")
nav = (APP / "util/HistoryNav.kt").read_text(encoding="utf-8")
act = (APP / "ui/BrowserActivity.java").read_text(encoding="utf-8")
mtest = (ROOT / "scripts/test_media_hook.js").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

print("S1 — the relative skip factor (v1.3.87 re-pointed: the factor is a SETTING now)")
need("(SET.kf||3)" in media and "?2.5:1" not in media.replace(
        "2.5 -> 4", ""), "applyTo's rate authority reads the configurable relative factor")
need("(v.__boltSk?(SET.kf||3):1)" in media,
     "the skip product reads (SET.speed||1)*(v.__boltSk?(SET.kf||3):1) — the v1.3.84 relative law, factor now SET.kf")
need("1.5x base skips at 4.5x" in mtest and "(the 1x-8x knob)" in mtest,
     "test_media_hook.js pins the factor-default and knob-floor products")

print("S2 — the history navigator exists and carries the audit")
need(len(nav) > 3000, "HistoryNav.kt exists with substance")
need("postDelayed" in nav and "1100L" in nav and "4000L" in nav,
     "the two-stage audit timings (1.1 s / 4 s)")
need("location.href" in nav, "the audit asks the renderer its own location")
need("loadUrl" in nav, "the force path is a plain loadUrl")
need("nav force" in nav and "nav drift" in nav and "nav step" in nav,
     "nav step/force/drift diag lines for the next field report")
need("isAttachedToWindow" in nav, "the audit stands down on a detached web")
need("WeakHashMap" in nav, "per-WebView state dies with the view")
need("goBackOrForward" in nav, "any offset step rides the same path")

print("S3 — every history call site routes through the audit")
steps = act.count("HistoryNav.step(")
need(steps >= 5, f"five HistoryNav.step call sites (got {steps})")
need("HistoryNav.noteNav(view, url)" in act and
     act.count("HistoryNav.noteNav(view, url)") >= 2,
     "onPageStarted + doUpdateVisitedHistory retire the audit")
# no raw history navigation left outside the helper (canGoBack gates stay).
# v1.3.87: ONE deliberate raw goBack exists — repairDownloadPage, the
# download-blank-page repair (not a user-facing history step; the audit
# law this gate defends governs the TOOLBAR's back).
raw_back = re.findall(r"\bt\.web\.goBack\(\)", act)
raw_fwd = re.findall(r"\bt\.web\.goForward\(\)", act)
raw_bf = re.findall(r"\bt\.web\.goBackOrForward\(", act)
need(len(raw_back) <= 1 and not raw_fwd and not raw_bf,
     f"no raw goBack/goForward/goBackOrForward on tabs beyond the download repair (found "
     f"{len(raw_back)}/{len(raw_fwd)}/{len(raw_bf)})")

print("S4 — the body-carrier law")
need("request.getMethod()" in act, "the interceptor reads the HTTP method")
need('!"GET".equals(method)' in act and '!"HEAD".equals(method)' in act,
     "the body-carrier definition (anything beyond GET/HEAD)")
need("if (carriesBody) return null" in act,
     "the law enforced: non-GET/HEAD passes untouched")
need("if (!carriesBody" in act,
     "the data saver gate: an upload is never stubbed with a 1x1 png")

print("S5 — version pin")
need(re.search(r"versionCode\s+130\b", gradle) is not None,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)
     "versionCode 130")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)
need(re.search(r'versionName\s+"1\.4\.16"', gradle) is not None,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)
     'versionName "1.4.16"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)

print()
if failures:
    print(f"v1.3.84 gate: {len(failures)} FAILURE(S)")
    sys.exit(1)
print("v1.3.84 gate: ALL CONTRACTS HOLD")
