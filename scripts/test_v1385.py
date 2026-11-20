#!/usr/bin/env python3
"""v1.3.85 gate — the label, the churn kill, the 401 decoder.

Field report (v1.3.84): "The skip silence still says 2.5 times instead of
relative 4 times the current speed. Youtube back forward/previous work but
facebook just brings to home page. Zai 401 unauthorised still remains when
uploading anything."

Contracts pinned here (F1-F4):
  F1 THE LABEL TELLS THE TRUTH: the engine already multiplies the base
     speed by 4 (v1.3.84) but the TOAST and the settings/media-sheet
     description still said "(2.5x)" — the user reads the label. The toast
     is a string resource now (skip_silence_on_toast: "Skipping silence at
     4x your current speed") and skip_silence_desc explains the ratio
     (1x -> 4x, 1.5x -> 6x). No user-visible "2.5x" text survives.
  F2 THE CHURN KILL (HistoryNav v2 — why facebook back landed on home):
     the SPA bolts tracking params onto the CURRENT entry's URL via
     replaceState, every rewrite fires doUpdateVisitedHistory with a URL
     that matched neither target nor departure EXACTLY, the v1.3.84 audit
     retired (or drifted) instead of forcing, the browser-side index had
     already advanced one slot — and the SECOND back stepped that advanced
     index straight to the home page. v2: LOOSE PAGE EQUALITY (scheme +
     host + path + tracking-param-stripped query; discriminators like v=
     are never stripped, the force target is ALWAYS the raw entry URL),
     INDEX-AWARE noteNav (churn keeps the index put -> audit stays armed;
     a pushState/load moves it -> retire), stage-2 drift is final.
  F3 THE UPLOAD-401 DECODER: zai's bundle posts attachments with an
     UNCONDITIONAL Bearer of localStorage.token; the auths bootstrap hands
     every visitor a GUEST token the server accepts for chat and rejects
     for uploads (live-reproduced 401 "You do not have permission...").
     Bolt now READS the server's own verdict: onReceivedHttpError logs
     every 401/403 on a body-carrying request to diagnostics
     ("upload auth 401 POST <url>") and shows ONE hint per host per
     session ("you may need to sign in on this site and retry"). The
     body-carrier law stays untouched — Bolt observes, never intercepts.
  F4 version pin: 107 / 1.3.94.
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
strings = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

print("F1 — the label tells the truth (relative factor; v1.3.87 re-pointed: the factor is a SETTING now)")
# v1.3.87: the hardcoded 4 became SET.kf (Prefs skipFactor, default 3,
# range 1-8) — the LABEL-TRUTH law this gate defends still holds: the
# toast quotes the live factor via the %1$d format resource.
need("(SET.kf||3)" in media, "the engine reads the configurable relative factor (SET.kf, default 3)")
need('name="skip_silence_on_toast"' in strings
     and "%1$dx your current speed" in strings,
     "skip_silence_on_toast format resource with the relative wording")
need('name="skip_silence_desc"' in strings
     and "default 3x" in strings,
     "skip_silence_desc explains the default and the tuning surface")
need("(2.5x)" not in strings, "no user-visible (2.5x) survives in strings.xml")
need("R.string.skip_silence_on_toast" in act
     and "skip_silence_on_toast, prefs.skipFactor()" in act,
     "the media-sheet toggle toast formats the live factor")
need('Skipping silent stretches at 2.5' not in act,
     "the hardcoded 2.5x toast is gone from BrowserActivity")

print("F2 — the churn kill (HistoryNav v2)")
need("fun looseSameUrl" in nav, "loose page equality comparator exists")
need("canonQuery" in nav and "kept.sort()" in nav,
     "tracking-param-stripped, sorted query comparison")
for p in ("mibextid", "wtsid", "fbclid", "rdid", "_rdr"):
    need(f'"{p}"' in nav, f"the facebook churn param {p} is stripped for comparison")
for p in ("__cft__", "__tn__", "utm_"):
    need(f'"{p}"' in nav, f"the churn prefix {p} is stripped for comparison")
need('"v"' not in nav and '"id"' not in nav and '"p"' not in nav,
     "page-discriminating params (v/id/p) are never stripped")
need("val toIndex: Int" in nav, "Pending carries the target slot index")
need("Pending(myGen, from, to, targetUrl, fromUrl)" in nav,
     "step() arms the audit with both slots")
need("idx != p.fromIndex && idx != p.toIndex" in nav,
     "noteNav retires only when the index left BOTH slots (churn keeps it put)")
need("looseSameUrl(href, pd.targetUrl)" in nav,
     "the probe accepts a churned landing (post1?mibextid=)")
need("looseSameUrl(href, pd.fromUrl)" in nav,
     "the probe sees a churned departure page as the desync it is")
need("if (stage >= 2)" in nav and "nav drift" in nav,
     "stage-2 drift is final (the pending entry no longer lingers)")
need("w.loadUrl(p.targetUrl)" in nav,
     "the force always navigates to the RAW history-entry URL")
need("nav force" in nav and "nav step" in nav, "diag lines survive")

print("F3 — the upload-401 decoder")
need("onReceivedHttpError" in act, "BoltClient observes HTTP error verdicts")
need('code != 401 && code != 403' in act,
     "the decoder watches exactly 401/403")
need('"upload auth " + code + " " + method' in act,
     "the diagnostics evidence line (upload auth 401 POST <url>)")
need("uploadAuthHinted.add(host)" in act and "uploadAuthHinted" in act,
     "the hint fires once per host per app session")
need("R.string.upload_auth_hint" in act, "the hint uses the string resource")
need('name="upload_auth_hint"' in strings and "sign in" in strings,
     "the hint copy exists and says the honest thing")
need("!incognito" in act.split("public void onReceivedHttpError")[1][:2500],
     "no hint in incognito sessions")
need("if (carriesBody) return null" in act,
     "the v1.3.84 body-carrier law is untouched (observe, never intercept)")

print("F4 — version pin")
need(re.search(r"versionCode\s+137\b", gradle) is not None,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 137")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName\s+"1\.4\.23"', gradle) is not None,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): the escaped-form recovery (the phantom-backslash corpse)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.23"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print()
if failures:
    print(f"v1.3.85 gate: {len(failures)} FAILURE(S)")
    sys.exit(1)
print("v1.3.85 gate: ALL CONTRACTS HOLD")
