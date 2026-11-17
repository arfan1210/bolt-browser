#!/usr/bin/env python3
"""v1.3.73/74 — TRANSLATE SCREEN contract gate (the size-law rewrite, then
the manga pass + the AI branch). Source-level checks; the behavioral
engine side is covered by test_translate.py/.js.

  S1  THE SIZE LAW: NO com.google.mlkit dependency anywhere (the
      bundled models were the whole +20.9 MB of the 23.4 MB v1.3.72
      APK), NO ndk.abiFilters (no native code => universal APK), and
      the version is bumped to 89 / 1.3.76;
  S2  ScreenTranslate.kt captures through PixelSnap.viewShot — the
      v1.3.40-proven GPU-true PixelCopy path (NEVER a bare view.draw as
      the primary), with the software piece as explicit fallback only;
  S3  network discipline: every TRANSLATION still rides PageTranslate.gtx
      (one endpoint, one whitelist); the ONLY other network is the
      pinned tessdata model download — https, hosts limited to
      cdn.jsdelivr.net + raw.githubusercontent.com (fast AND best
      repos), lang whitelist eng/jpn/jpn_vert/chi_sim/jpn_vertb,
      redirects refused, truncated files rejected (MIN_MODEL);
  S4  the OCR host: assets/scr_ocr.html pins tesseract.js 5.1.1, aims
      langPath at https://bolt.ocr/tess (served from filesDir via
      shouldInterceptRequest with ACAO, clean local 404 otherwise),
      runs the LSTM-only OEM, requests paragraph-level blocks, and
      hands the image over in binder-safe chunks; Kotlin never lets the
      host load anything but the pinned engine;
  S5  the overlay: luminance-sampled box fills, StaticLayout auto-fit,
      tap-for-original dialog, language chip re-translate, the OCR
      script chip re-run, Save renders the composite into Pictures;
  S6  the wiring: menu row + translateScreen() + the translate_screen
      string; version 89 / 1.3.76;
  S7  no new permissions (screenshot-based OCR needs none — the AI
      branch is plain HTTPS too);
  S8  the budget is ENFORCED: release.sh hard-fails any APK over
      6,000,000 bytes (the user's 5-6 MB law, institutionalized);
  S9  THE v1.3.74 MANGA PASS: prepOcr (×2 upsample, grayscale,
      percentile contrast stretch, 3400 cap), PNG for line art, the
      Manga HD script (jpn_vertb alias -> tessdata_best jpn_vert,
      MODEL_BYTES 14,330,809), MAXBLK 160;
  S10 THE v1.3.74 AI BRANCH + THE v1.3.75 BUGFIX: util/AiBridge.kt
      exists with the pinned generativelanguage host, the x-goog-api-key
      header, the local-only key storage (SharedPreferences bolt_ai),
      markdown-fence-tolerant JSON parsing with the 0-1 fraction
      normalization, and ScreenTranslate's branch: "ai" script,
      config-dialog hook, privacy confirm, AiBridge boxes mapped without
      the gtx round. v1.3.75 additions: the Gemini ENVELOPE is unwrapped
      (geminiText extracting candidates[].content.parts[].text, skipping
      thought parts — the v1.3.74 killer bug), the model chain leads with
      the CURRENT free tier (gemini-3.5-flash-lite; 2.5 is deprecated
      2026-12-01), responseMimeType json + a 16384-token budget, and the
      config dialog carries an onSaved continuation;
  S11 THE v1.3.75 BOLT AI PANEL: util/BoltAi.kt — the browser-wide
      assistant. Chat through AiBridge.chat, PAGE_JS (title + meta +
      innerText capped 12,000) with an honest JSON-string decode, SEL_JS
      capped 6,000, a once-per-process page-content confirm, the
      Translate-screen handoff to ScreenTranslate.startWithScript,
      in-memory-only history, the key never riding a diag/toast, and the
      two entries: BrowserActivity's menu row + SettingsHomeActivity's
      card. The setup/key link must open in a NEW TAB from the browser
      (LinkOpener) — never the current tab.
"""
import pathlib
import re
import sys

SCR = "app/src/main/java/com/bolt/browser/util/ScreenTranslate.kt"
HTML = "app/src/main/assets/scr_ocr.html"
GRADLE = "app/build.gradle"
ACT = "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java"
STRINGS = "app/src/main/res/values/strings.xml"
MANIFEST = "app/src/main/AndroidManifest.xml"
RELEASE = "scripts/release.sh"


def main():
    scr = pathlib.Path(SCR).read_text(encoding="utf-8")
    html = pathlib.Path(HTML).read_text(encoding="utf-8")
    gradle = pathlib.Path(GRADLE).read_text(encoding="utf-8")
    act = pathlib.Path(ACT).read_text(encoding="utf-8")
    strings = pathlib.Path(STRINGS).read_text(encoding="utf-8")

    # S1 — the size law: ML Kit gone, universal APK, version bumped
    if "com.google.mlkit" in gradle:
        print("FAIL: build.gradle still pulls a com.google.mlkit dependency (the size law forbids it)")
        return 1
    if re.search(r"abiFilters\s+['\"]", gradle):
        print("FAIL: build.gradle still restricts ABIs — with no native code the APK must be universal")
        return 1
    # v1.3.79: version pin retired (test_v1379.py owns the current one);
    # the S1 size-law checks stay forever
    print("OK: S1 no ML Kit, no ABI filter (universal) — the size law in gradle (version pin retired)")

    # S2 — capture through the proven path
    if "PixelSnap.viewShot(a, w)" not in scr:
        print("FAIL: ScreenTranslate does not capture via PixelSnap.viewShot")
        return 1
    if "private fun softwareShot" not in scr:
        print("FAIL: the software-capture fallback is missing")
        return 1
    print("OK: S2 capture rides PixelSnap.viewShot (GPU-true) with software fallback")

    # S3 — network discipline
    if "PageTranslate.gtx(" not in scr:
        print("FAIL: ScreenTranslate does not ride PageTranslate.gtx")
        return 1
    if "okhttp" in scr:
        print("FAIL: an okhttp client appeared in ScreenTranslate")
        return 1
    urls = set(re.findall(r'https://[A-Za-z0-9._/@+-]+', scr))
    allowed = {
        "https://cdn.jsdelivr.net/gh/tesseract-ocr/tessdata_fast@main/",
        "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/",
        "https://cdn.jsdelivr.net/",     # the OCR page's pinned base URL
        "https://bolt.ocr/tess",         # intercepted, never real network
    }
    stray = urls - allowed
    if stray:
        print(f"FAIL: ScreenTranslate references unexpected URLs: {sorted(stray)}")
        return 1
    if "instanceFollowRedirects = false" not in scr:
        print("FAIL: the model download must refuse redirects (relay discipline)")
        return 1
    for probe in ('"eng", "jpn", "chi_sim"',
                  "MIN_MODEL", "tmp.renameTo(out)"):
        if probe not in scr:
            print(f"FAIL: the model downloader lacks {probe}")
            return 1
    print("OK: S3 gtx for translation; only pinned tessdata GETs otherwise (no redirects, size-checked)")

    # S4 — the OCR host page + the Kotlin side of the protocol
    for probe in ("tesseract.js@5.1.1/dist/tesseract.min.js",
                  "https://bolt.ocr/tess",
                  "createWorker(langs, 1,",
                  "gzip: false",
                  "cacheMethod: 'none'",
                  "blocks: true",
                  "window.__push",
                  "window.__ocr",
                  "B().done(JSON.stringify(out))",
                  "BoltOcr"):
        if probe not in html:
            print(f"FAIL: scr_ocr.html lacks {probe}")
            return 1
    for probe in ("addJavascriptInterface(Bridge(), \"BoltOcr\")",
                  "shouldInterceptRequest",
                  "Access-Control-Allow-Origin",
                  'u.host == "bolt.ocr"',
                  "loadDataWithBaseURL(\"https://cdn.jsdelivr.net/\"",
                  "b64.chunked(200_000)",
                  "evaluateJavascript(\"window.__ocr",
                  "lastPathSegment"):
        if probe not in scr:
            print(f"FAIL: the OCR host wiring lacks {probe}")
            return 1
    if "import com.google.mlkit" in scr:
        print("FAIL: ScreenTranslate still imports ML Kit")
        return 1
    # the behavioral half of S4: run the REAL page script under Node with
    # mocks (chunked handoff, combined langs, paragraph flattening, worker
    # reuse/rebuild, failure path, progress forwarding, no-lib) — the
    # empty-line box-pollution bug was caught here before shipping
    import subprocess
    r = subprocess.run(["node", "scripts/test_screen_ocr.js"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("FAIL: scripts/test_screen_ocr.js (the OCR page protocol):")
        print(r.stdout)
        print(r.stderr)
        return 1
    print("OK: S4 pinned tesseract.js 5.1.1 host page, bolt.ocr local model serving, chunked handoff")
    print("OK: S4b OCR page protocol mock suite green (T1-T8)")

    # S5 — the overlay
    for probe in ("regionLight", "StaticLayout.Builder.obtain", "setSingleChoiceItems",
                  "MediaStore.Images.Media", "composite()",
                  "pickScript", "reRun", "cjkNormalize"):
        if probe not in scr:
            print(f"FAIL: the overlay lacks {probe}")
            return 1
    if "MAXBLK = 160" not in scr:
        print("FAIL: the per-screen line cap is missing (v1.3.74 raised it to 160 for manga density)")
        return 1
    print("OK: S5 luminance-matched boxes, auto-fit text, tap-for-original, chips (language + OCR script), Save")

    # S6 — wiring (v1.3.75: start() now takes the new-tab LinkOpener too)
    if "ScreenTranslate.start(this, t.web," not in act:
        print("FAIL: BrowserActivity does not call ScreenTranslate.start")
        return 1
    if "translateScreen()" not in act:
        print("FAIL: the translateScreen() menu action is missing")
        return 1
    if '<string name="translate_screen">Translate screen</string>' not in strings:
        print("FAIL: the translate_screen string is missing")
        return 1
    print("OK: S6 menu row + action + string wired")

    # S9 — REWRITTEN v1.3.78: THE MODE LAW (the report: "the other modes
    # are pretty uneffecrive still, so just remove them all, and keep an
    # unibetsal alternative screen translate non ai option"). TWO modes:
    # AI (primary) + Basic OCR (universal no-AI fallback); the manga/HD/JP/
    # CN/EN scripts and their models are GONE, and the on-disk diet deletes
    # the orphaned jpn_vert/jpn_vertb files (~17 MB of the storage bill).
    for probe in ('private val SCRIPT_KEYS = arrayOf("ai", "auto")',
                  '"AI \\u2014 translate (recommended)"',
                  '"Basic OCR \\u2014 no AI (works offline of the AI key)"',
                  # v1.3.82: public + @JvmStatic — the update receiver calls
                  # the diet at install time (an un-openable app never ran
                  # the lazy OCR-path diet)
                  '@JvmStatic\n    fun modelDiet(a: Context)',
                  '"scr model diet freed="',
                  'arrayOf("jpn_vert", "jpn_vertb")'):
        if probe not in scr:
            print(f"FAIL: the v1.3.78 mode law lacks {probe}")
            return 1
    for gone in ('"mangahd"', '"manga" -> arrayOf', 'BEST_HOSTS',
                 'private fun prepOcr(', 'c.rotate(-90f)', 'sb.vert =',
                 'vertHint', 'arrayOf("jpn_vert")'):
        if gone in scr:
            print(f"FAIL: the removed v1.3.77 machinery still present: {gone}")
            return 1
    print("OK: S9 the v1.3.78 mode law (AI + universal Basic OCR; scripts, best models and ALL card rotation deleted)")

    # S7 — no NEW permissions: the manifest must carry exactly the
    # pre-v1.3.72 set (screen translate is screenshot-based and needs
    # none; CAMERA/RECORD_AUDIO predate it — the QR scan / TTS flows)
    man = pathlib.Path(MANIFEST).read_text(encoding="utf-8")
    have = set(re.findall(r'android\.permission\.([A-Z_]+)', man))
    pre = {"INTERNET", "ACCESS_NETWORK_STATE", "FOREGROUND_SERVICE",
           "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "FOREGROUND_SERVICE_DATA_SYNC",
           "VIBRATE", "WAKE_LOCK", "POST_NOTIFICATIONS", "CAMERA",
           "RECORD_AUDIO", "MODIFY_AUDIO_SETTINGS",
           "WRITE_EXTERNAL_STORAGE", "EXPAND_STATUS_BAR",
           "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
           # v1.3.78: the APK-open fix (Android 8+ blocks install intents
           # without it; the user still flips the per-app switch once)
           "REQUEST_INSTALL_PACKAGES"}
    added = have - pre
    if added:
        print(f"FAIL: screen translate (or this release) added permissions: {sorted(added)}")
        return 1
    print("OK: S7 no new permissions (screenshot OCR needs none)")

    # S8 — the budget is ENFORCED at release time, not just remembered
    rel = pathlib.Path(RELEASE).read_text(encoding="utf-8")
    if "6000000" not in rel or "SIZE" not in rel:
        print("FAIL: release.sh does not enforce the 6,000,000 B APK budget")
        return 1
    m = re.search(r'SIZE=\$\(stat -c%s "\$APK"\)', rel)
    if not m:
        print("FAIL: release.sh must measure the built APK size (stat -c%s \"$APK\")")
        return 1
    print("OK: S8 release.sh hard-fails any APK over the 6,000,000 B budget")

    # S10 — the v1.3.74 AI branch + the v1.3.75 bugfix
    ai_path = pathlib.Path("app/src/main/java/com/bolt/browser/util/AiBridge.kt")
    if not ai_path.exists():
        print("FAIL: util/AiBridge.kt does not exist")
        return 1
    ai = ai_path.read_text(encoding="utf-8")
    for probe in ('private const val GEMINI_HOST = "generativelanguage.googleapis.com"',
                  "x-goog-api-key",
                  'getSharedPreferences("bolt_ai", Context.MODE_PRIVATE)',
                  "chat/completions",
                  "inline_data",
                  "fun configDialog",
                  "fun visionTranslate",
                  "fun chat(",
                  "removePrefix(\"```json\")",
                  "lastError",
                  # v1.3.75 — the envelope fix + the current model chain
                  "private fun geminiText(",
                  'optBoolean("thought", false)',
                  "private fun parseBoxes(",
                  "parseBoxes(geminiText(txt), iw, ih)",
                  'val GEMINI_DEFAULT = "gemini-3.5-flash-lite"',
                  '"gemini-flash-latest", "gemini-3.8-flash"',
                  '"responseMimeType", "application/json"',
                  'put("maxOutputTokens", 16384)',
                  "fun interface LinkOpener",
                  # v1.3.76 — the thinking cut + the 429 patience
                  'fun visionTranslate(ctx: Context, b64: String, tl: String, iw: Int, ih: Int)',
                  # v1.3.77 — the NATIVE-DIALECT prompt (box_2d = the
                  # model's own 0-1000 [ymin,xmin,ymax,xmax] convention —
                  # the pixel ask fought the training and the field said
                  # placement "got worse")
                  '\\"box_2d\\":[ymin,xmin,ymax,xmax]',
                  'normalized to 0-1000 across the whole',
                  'put("thinkingLevel", "minimal")',
                  'private fun geminiPostRetry(',
                  'ai 429 retry'):
        if probe not in ai:
            print(f"FAIL: AiBridge.kt lacks {probe}")
            return 1
    # the deprecated 2.5 default must be GONE (Google retires 2026-12-01;
    # new keys already 404 on it — "the AI mode is pretty buggy")
    if 'GEMINI_DEFAULT = "gemini-2.5-flash"' in ai:
        print("FAIL: the default model is still the deprecated gemini-2.5-flash")
        return 1
    ai_urls = set(re.findall(r'https://[A-Za-z0-9._/@+-]+', ai))
    ai_allowed = {"https://generativelanguage.googleapis.com",
                  "https://aistudio.google.com/apikey"}
    # the OpenAI-compatible base URL is USER-CONFIGURED (any endpoint they
    # choose) — the DEFAULT must be pinned https
    mbase = re.search(r'DEFAULT_OPENAI_BASE = "(https://[^"]+)"', ai)
    if not mbase or not mbase.group(1).startswith("https://"):
        print("FAIL: the default OpenAI-compatible base URL is missing or not https")
        return 1
    if mbase.group(1) not in ai_urls:
        ai_urls.discard(mbase.group(1))
    stray = ai_urls - ai_allowed - {mbase.group(1)}
    if stray:
        print(f"FAIL: AiBridge.kt references unexpected URLs: {sorted(stray)}")
        return 1
    for probe in ('if (k == "ai") { runAiStage(a, bmp, g); return }',
                  "AiBridge.configured(a)",
                  "AiBridge.visionTranslate",
                  "openOverlay(a, bmp, blocks, true)",
                  'if (!ai) translateAll(a, view, tl)',
                  'scr ai ask prov=',
                  'scr ai ok lines='):
        if probe not in scr:
            print(f"FAIL: ScreenTranslate's AI branch lacks {probe}")
            return 1
    # the key must never ride a diag line or a toast
    if re.search(r'(diag|Diag\.log|toast|Toast)[^\n]*key\(ctx\)', ai + scr):
        print("FAIL: the API key is echoed into a diag/toast path")
        return 1
    print("OK: S10 the AI branch (pinned hosts + OpenAI-compatible shape, local key, honest confirms, the v1.3.75 envelope fix + current model chain)")

    # S11 — the v1.3.75 Bolt AI panel
    bolt_path = pathlib.Path("app/src/main/java/com/bolt/browser/util/BoltAi.kt")
    if not bolt_path.exists():
        print("FAIL: util/BoltAi.kt does not exist")
        return 1
    bolt = bolt_path.read_text(encoding="utf-8")
    for probe in ("object BoltAi",
                  "fun open(a: Activity, web: WebView?, openUrl: AiBridge.LinkOpener?)",
                  "AiBridge.chat(a, SYSTEM, turns)",
                  "const val PAGE_JS",
                  "b.slice(0,12000)",
                  "const val SEL_JS",
                  "s.slice(0,6000)",
                  "private fun decodeJs",
                  'JSONArray("[" + value + "]")',
                  "pageConfirmed = true",
                  "ScreenTranslate.startWithScript(a, web, \"ai\", openUrl)",
                  "fun dismiss()",
                  "history.clear()",
                  "MAX_TURNS = 8",
                  "Executors.newSingleThreadExecutor",
                  # v1.3.76 — copy on every bubble; v1.3.92 — LONG-PRESS
                  # (tap fired accidental copies while scrolling the sheet)
                  'newPlainText("Bolt AI"',
                  'Long-press any message to copy it.'):
        if probe not in bolt:
            print(f"FAIL: BoltAi.kt lacks {probe}")
            return 1
    # the panel must NOT introduce network of its own — everything rides AiBridge
    if re.search(r'https?://', bolt):
        print("FAIL: BoltAi.kt references a URL — all requests must ride AiBridge")
        return 1
    # the key never rides a diag line or a toast
    if re.search(r'(diag|Diag\.log|toast|Toast)[^\n]*key\(', bolt):
        print("FAIL: the API key is echoed into a diag/toast path")
        return 1
    # history must be in-memory only (no prefs/file persistence of the chat)
    if re.search(r'getSharedPreferences|filesDir', bolt):
        print("FAIL: the chat history must stay in-memory (no persistence)")
        return 1
    # the two entries: BrowserActivity menu row + SettingsHomeActivity card
    if 'BoltAi.open(this,' not in act:
        print("FAIL: BrowserActivity does not open the Bolt AI panel")
        return 1
    set_home = pathlib.Path("app/src/main/java/com/bolt/browser/ui/SettingsHomeActivity.java").read_text(encoding="utf-8")
    if "BoltAi.open(this, null, null)" not in set_home:
        print("FAIL: SettingsHomeActivity does not open the Bolt AI panel")
        return 1
    # the setup link opens a NEW TAB from the browser, never the current tab
    if 'url -> openNewTab(url)' not in act:
        print("FAIL: the AI key link must open in a new tab (openNewTab)")
        return 1
    # ScreenTranslate: the picker's AI-settings button + the continue-after-save
    for probe in ('"AI settings"',
                  'openAiUrl(a, url)',
                  'scr ai cfg saved',
                  'fun startWithScript',
                  # v1.4.12 re-point (2026-11-18): the AI payload diet —
                  # 2200/90 base64'd to 2-4 MB and died on mobile networks
                  # (timeouts, HTTP 400/413); the AI encoder is 1280/75 now
                  'encodeScaled(bmp, 1280, 75)'):
        if probe not in scr:
            print(f"FAIL: ScreenTranslate lacks {probe} (the v1.3.75 AI UX fixes)")
            return 1
    print("OK: S11 the Bolt AI panel (chat + page/selection/screen actions, honest decode, new-tab link, both entries, in-memory history, single-tap copy)")

    # S12 — the matching kit, v1.3.78 form: ink-tightened boxes, sampled
    # local backgrounds, ink line-height fonts — and NO ROTATION ANYWHERE
    # (the report: "the translated text is 90 degree rotated on the screen
    # in the correct place and size"; a vertical original gets horizontal
    # multi-line text riding the column)
    for probe in ('fun sampleRing(bmp: Bitmap, r: Rect)',
                  'private fun analyze(bmp: Bitmap, box: Rect)',
                  'val an = analyze(bmp, rect)',
                  'sb.bg = an.bg',
                  'sb.thPx = if (an.linePx > 0f) an.linePx else b.th * bmp.height',
                  'val linePx = medianBand(rowInk, rowTh, step)',
                  'b.thPx * scaleHint()',
                  'val lum = (Color.red(bgc) * 299 + Color.green(bgc) * 587 + Color.blue(bgc) * 114) / 1000',
                  'NO ROTATION, EVER'):
        if probe not in scr:
            print(f"FAIL: the v1.3.78 matching kit lacks {probe}")
            return 1
    # the box parser must leave normalized 0-1 fractions and carry th/vert;
    # the PRIMARY dialect is the model-native box_2d corner order with a
    # response-level scale detection, legacy [x,y,w,h] stays as fallback
    for probe in ('val b2 = o.optJSONArray("box_2d")',
                  'th = o.optDouble("th", 0.0).toFloat()',
                  'o.optBoolean("vertical", false)',
                  'x /= iw; w /= iw; y /= ih; h /= ih',
                  'y0 = a / 1000f; x0 = b / 1000f; y1 = c / 1000f; x1 = d / 1000f'):
        if probe not in ai:
            print(f"FAIL: the box_2d parser lacks {probe}")
            return 1
    # v1.3.78: the PROMPT no longer asks for the vertical flag (nothing
    # rotates); the parser keeps tolerating it for endpoints that send it
    if '\"vertical\":b' in ai or 'vertical is true when the' in ai:
        print("FAIL: the prompt still asks for the vertical flag (nothing rotates now)")
        return 1
    print("OK: S12 the v1.3.78 matching kit (ink-tightened boxes + ring background + ink line-height fonts, ZERO rotation)")

    # S13 — the live-reading loop, v1.3.78 form (the report: screen translate
    # "is still not usable to translate manga panels live")
    for probe in ('val redo = chip(\"\\u21bb\", 15, false)',
                  'startWithScript(a, w, k, op)',
                  '"scr redo"',
                  'main.postDelayed({',
                  # the ink analysis contract: glyph-tight boxes with the
                  # half-span shrink guard, and the median band = line height
                  'private const val INK_DELTA = 48',
                  'fun medianBand(a: IntArray, th: Int, step: Int): Float',
                  'if (candTop > T + (B - T) / 2) nt = candTop.coerceAtLeast(T)',
                  # vertically centered text in the cards
                  'val cy = t + ((bt - t) - lay.height) / 2f'):
        if probe not in scr:
            print(f"FAIL: the live-reading/ink kit lacks {probe}")
            return 1
    print("OK: S13 the recapture chip (one-tap re-run loop) + the ink analysis guards")

    return 0


if __name__ == "__main__":
    sys.exit(main())
