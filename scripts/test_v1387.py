#!/usr/bin/env python3
"""v1.3.87 gate — the four-pack: the skipper hysteresis overhaul, the
already-dark gate + real media exclusions, the download-page repair, and
THE AI RESPONSE EXPORTER.

Field report (v1.3.86): "add feature like the pc extensions that can
download gemini, gpt, zai, qwen etc ai's response as pdf or image (like it
also lets me select which responses I want to keep and maintains the
formatting). The silence skipper has some activation and deactivation
issue, like it takes a while to activate but also takes a while to
deactivate. As a result I end up hearing a bit of the silence (not too
long so manageable) and a portion of the non-silent audio ends up being
fast forward as well. Also I think relative 4x is a bit too fast, maybe
relative 3x would be better. Even better, give control options for all
these settings like relative 1x-8x speed for the silence, start after x
ms (give a reasonable range) of silence appearing, stop before x ms (give
reasonable range) of non-silence part, also add the unique feature like
ignore silences for less than x ms (I asked for ms because I don't know,
maybe seconds better or what, i don't know). My end goal is smooth
speedup and normal like how Libretube handles their skip silence, I
prefered it over newpipe's. Also the dark site is not properly detecting
if the website is already dark and ends up inversing on the dark website
making it white. Also it inverts images which causes problem and makes
ugly. Also, there is a issue when I do a download from github, after
downloading and reclosing bolt, the download tab sits in the blank
download only page and I cannot go back to the releases page by going
back, it just takes me to browser home."

Contracts pinned here (H1-H5):
  H1 THE SKIPPER HYSTERESIS OVERHAUL: the loop ticked every 250 ms —
     release latency was one tick = 250 ms wall = ~1 s of speech consumed
     at 4x (the garbled onsets); engagement was tick + 350 ms hold. Now:
     60 ms cadence while any element owns a chain (400 ms idle), and the
     four knobs are settings — kf (1x-8x relative factor, default 3),
     ska (start-after ms, 50-2000, default 200), skb (rewind-on-speech ms,
     0-600, default 150), skm (ignore-silences-under ms, 100-3000,
     default 400, CONTENT time; a shorter stretch UNDOES the whole skip
     and bans the region until playback passes its onset — no loop).
     currentTime is SECONDS, knobs are MS — converted at the boundary.
  H2 THE ALREADY-DARK GATE + MEDIA EXCLUSIONS: dark CSS lives in its OWN
     bolt-darkcss style and is injected only after the in-page luminance
     probe (html/body computed bg, alpha-gated; color-scheme first token
     dark; dark hex-parsed meta theme-color) says the page is NOT already
     dark; class-set background images are un-inverted via the computed-
     style scan pinning .__boltNI.
  H3 THE DOWNLOAD-PAGE REPAIR: a download that COMMITTED its URL into the
     WebView (GitHub releases pattern) leaves a blank page; the restore
     path then rebuilds a single-entry history so back falls out of the
     tab. repairDownloadPage steps the WebView back and arms the
     dlUrl/dlFromUrl pair; TabManager.resumeUrl swaps the persisted copy.
  H4 THE AI RESPONSE EXPORTER: picker + capture, PDF and PNG, formatting
     preserved by rendering from the LIVE page (keep/path/hide + opened
     scrollers), per-site selectors + generic fallback, the page restored
     after every path.
  H5 version pin: 107 / 1.3.94.
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
prefs = (APP / "util/Prefs.java").read_text(encoding="utf-8")
sktune = (APP / "util/SkipTune.kt").read_text(encoding="utf-8")
ai = (APP / "util/AiExport.kt").read_text(encoding="utf-8")
act = (APP / "ui/BrowserActivity.java").read_text(encoding="utf-8")
tab = (APP / "core/Tab.java").read_text(encoding="utf-8")
tm = (APP / "core/TabManager.java").read_text(encoding="utf-8")
settings = (APP / "ui/SettingsItemsActivity.java").read_text(encoding="utf-8")
strings = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
sheet_media = (ROOT / "app/src/main/res/layout/sheet_media.xml").read_text(encoding="utf-8")
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

print("H1 — the skipper hysteresis overhaul")
need("setTimeout(skTick,live&&!hid?60:live?250:skEls.size?1000:400);}" in media,
     "adaptive cadence (v1.3.89): 60 ms playing+visible, 250 ms playing hidden, 1 s idle — the flat 60 ms burned battery in the background")
need("(SET.ska||200)" in media, "engagement reads the start-after knob (default 200 ms)")
need("now-skQuiet.get(v)>350" not in media, "the hardcoded 350 ms hold is gone")
need("(SET.kf||3)" in media, "the skip factor reads SET.kf (default 3)")
need("(SET.skb||0)/1000" in media,
     "the rewind-on-speech knob converts ms to seconds at the boundary")
need("(v.currentTime-v.__boltSilT)*1000" in media,
     "the silence length is measured in CONTENT ms (seconds x 1000)")
need("v.__boltSkBan=v.currentTime+0.05" in media,
     "a too-short silence bans its region until playback passes the onset")
need("public int skipFactor()" in prefs and 'i("skipFactor", 3)' in prefs,
     "Prefs.skipFactor default 3")
need("Math.min(8," in prefs and "Math.max(1," in prefs, "the factor is clamped 1-8")
need('i("skipAttack", 200)' in prefs and 'i("skipRelease", 150)' in prefs
     and 'i("skipMinSilence", 400)' in prefs,
     "the three timing knobs persist with their defaults (200/150/400 ms)")
need("p.skipFactor()" in media and "p.skipAttack()" in media
     and "p.skipRelease()" in media and "p.skipMinSilence()" in media,
     "mediaSettingsJson ships all four knobs to the page")
need("fun sheet(" in sktune and "skip_tune_speed" in sktune,
     "SkipTune.kt builds the shared tuning sheet")
need("tv_skip_tune" in sheet_media and "SkipTune.sheet" in act,
     "the media FAB sheet carries the tuning row")
need("SkipTune.sheet" in settings,
     "Settings -> Media carries the tuning row too")
need("%1$dx your current speed" in strings,
     "the enable toast is a format resource (quotes the live factor)")

print("H2 — the already-dark gate + the real media exclusions")
need("public static String darkProbeJs()" in media, "the luminance probe exists")
need("colorScheme" in media, "the probe reads color-scheme")
need("theme-color" in media, "the probe reads the meta theme-color")
need("parseInt(h.slice(0,2),16)" in media, "hex colors are parsed")
need(".__boltNI" in media and "darkSmartJs" in media,
     "the smart pass pins the un-invert class (v1.3.92: darkSmartJs replaced darkBgImgScanJs)")
need("embed,object,[role=\\\"img\\\"]" in media,
     "the static un-invert list covers embed/object/[role=img]")
need('cssInjectOnce(\n                                        "bolt-darkcss"' in act
     or '"bolt-darkcss"' in act,
     "the dark CSS lives in its own bolt-darkcss style")
need("darkProbeJs(), res ->" in act,
     "the injection is gated on the probe's async verdict")
need('"dark native-site "' in act,
     "the skip is written to diagnostics (field evidence)")
need("styleRemoveJs" in media, "a stale inversion can be removed")

print("H3 — the download-page repair")
need("protected void repairDownloadPage(final String dlUrl)" in act,
     "the repair runs from onDownloadStart")
need("repairDownloadPage(url);" in act, "the repair is wired before the dialog")
need('t.dlUrl = dlUrl;' in act and "t.dlFromUrl = pre;" in act,
     "the transient pair is armed synchronously (save-race safe)")
need("t.web.goBack();" in act and '"dl repair "' in act,
     "the WebView steps back and the repair is logged")
need("public String dlUrl;" in tab and "public String dlFromUrl;" in tab,
     "Tab carries the transient pair (never serialized)")
need("t.dlUrl.equals(u)" in tm and "t.dlFromUrl" in tm,
     "resumeUrl swaps the persisted copy off the dead download entry")

print("H4 — the AI response exporter")
need("fun pickerJs()" in ai, "the picker JS builder exists (Kotlin, language rule)")
need("model-response" in ai, "gemini selector")
need('data-message-author-role' in ai, "chatgpt selector")
need("markdown" in ai and "prose" in ai, "the generic fallback family")
need("Bolt.aiCapture" in ai, "the page->Java handoff")
need("__boltAiRestore" in ai and "fun restoreJs" in ai,
     "the restore contract is shipped both ways")
need("__boltAiOpen" in ai and "overflow:visible" in ai,
     "fixed-height chat scrollers are opened for the capture")
need("__boltAiHide" in ai, "non-selected content is hidden")
# v1.3.92: the surgery is INLINE CSS now (the classes alone were never
# styled in v1.3.87-91 — the hide/open had no effect at all)
need("data-bolt-ai-d" in ai and "style.display='none'" in ai,
     "the hide lands as inline CSS (CSP-proof, actually effective)")
need("PdfDocument" in ai and "stripsPerPage" in ai,
     "the PDF pipeline tiles strips into pages")
need('"Bolt-AI-export-"' in ai, "the PNG path names its files")
need('namePrefix: String' in (ROOT / "app/src/main/java/com/bolt/browser/util/FullShot.kt").read_text(encoding="utf-8"),
     "FullShot gained the named-capture overload")
need("fun startAiExport()" in act or "protected void startAiExport()" in act,
     "the menu entry exists")
need("public void aiCapture(final String mode, final int count)" in act,
     "the JsBridge method exists")
need('"ai export "' in act, "the export is logged to diagnostics")

print("H5 — version pin")
need(re.search(r"versionCode\s+136\b", gradle) is not None,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     "versionCode 136")  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
need(re.search(r'versionName\s+"1\.4\.22"', gradle) is not None,  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): the escaped-form recovery (the phantom-backslash corpse)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)
     'versionName "1.4.22"')  # v1.4.11 re-point (2026-11-18): 124/1.4.10 -> 125/1.4.11 (the stability four-pack)  # v1.4.12 re-point (2026-11-18): 125/1.4.11 -> 126/1.4.12 (the usability five-pack)  # v1.4.13 re-point (2026-11-18): 126/1.4.12 -> 127/1.4.13 (the performance five-pack)  # v1.4.14 re-point (2026-11-18): 127/1.4.13 -> 128/1.4.14 (the interaction six-pack)  # v1.4.15 re-point (2026-11-18): 128/1.4.14 -> 129/1.4.15 (the responsiveness four-pack)  # v1.4.16 re-point (2026-11-18): 129/1.4.15 -> 130/1.4.16 (the reliability nine-pack)  # v1.4.17 re-point (2026-11-18): 130/1.4.16 -> 131/1.4.17 (the polish seven-pack)  # v1.4.18 re-point (2026-11-19): 131/1.4.17 -> 132/1.4.18 (the field-cure six-pack)  # v1.4.19 re-point (2026-11-19): 132/1.4.18 -> 133/1.4.19 (the root-cure ten-pack)  # v1.4.20 re-point (2026-11-20): 133/1.4.19 -> 134/1.4.20 (the evidence-pack)

print()
if failures:
    print(f"v1.3.87 gate: {len(failures)} FAILURE(S)")
    sys.exit(1)
print("v1.3.87 gate: ALL CONTRACTS HOLD")
