# Changelog

Notes for releases published from this repository. Versions before v1.4.10
were released from the maintainer's earlier private repository — their full
notes live on the [releases page](https://github.com/arfan1210/bolt-browser-releases/releases).

## [1.4.27] — 2026-11-22

**The picture-stack + merged-pipeline round: four field-rooted fixes —
thumbnails that read as x-ray negatives because the counter-invert stacked
three filters on picture-wrapped images, screen translate unified into ONE
content-aware pipeline (cluster cards, one translation channel, a manga
preprocessing variant and a host that re-arms instead of dying after a few
runs), AI tables drawn as REAL ruled grids, and a QR round that gives the
gallery picker the camera's full multi-pass kit plus a preview that
asserts it fills the screen from the first delivered frame. Root-caused
from the user's saved TBS DOM (the news-site fixture) and the field
report. The launch smoke rung stays in the pipeline; the v1.4.26 DIU
fixture still walks green.**

### 1. FORCED DARK: PICTURE STACKING — the saved-TBS root-cause
- Field shape: on tbsnews.net every story thumbnail rendered as an x-ray
  negative. The saved DOM's evidence: 65 `<picture
  class="ratio ratio__16x9">` blocks each wrapping an `<img
  class="ratio ratio__16x9 lazyloaded">` with NO `__bolt` class — the
  counter-invert applies to img, video, picture, canvas and svg TOGETHER,
  so picture>img stacked html(invert) x picture(invert) x img(invert) = 3
  filters = ODD = net inverted (a standalone img is 2 = correct).
- Root cause: the CSS never accounted for NESTED media — any media family
  member inside another one double-counter-inverts.
- Fix (CSS-only — the classifier and the DIU portal round are untouched on
  this leg): the NESTED-MEDIA EXEMPTION — `picture img, picture video,
  picture canvas, picture svg {filter:none!important;}` — media inside an
  already-counter-inverted `<picture>` contributes NO second
  counter-filter, so picture>img = html x picture = 2 = ORIGINAL. The DIU
  portal has no `<picture>` at all (the fixture walk proves the
  isolation).
- Belt-and-braces for plain-img thumbnails on other sites: a REMOTE-URL
  (http/https) IMG larger than 48 px with no icon-named ancestor is
  CONTENT, and the IMG banner-shape threshold drops to a 1.5 aspect so
  16:9 thumbs are content at any size. The DIU portal's icons are
  data:URI squares inside icon-named wrappers — untouched (probe-pinned).
- Probe: the parity arithmetic (3 = inverted corpse, 2 = original) + the
  TBS fixture walk (picture thumbnails stay media, remote/banner laws
  hold) + the DIU regression carrier rides the same walk.

### 2. TRANSLATE: THE ONE CONTENT-AWARE PIPELINE
- Field shape: the AI Page mode read accurately but placed cards on fixed
  grid cells; the Manga mode placed bubble cards through a SEPARATE
  whole-page OcrHost pass that failed after a few runs (a slow recognize
  stacked the next run behind a stuck worker, the overwriting orphaned
  the old callbacks and the stale 150 s timeout fired into the NEW job) —
  and its whole-page upscale read nothing unless the page was zoomed to
  1-2 blocks.
- Fix — ONE pipeline for EVERY mode (AI Page, Basic OCR, Manga, Manga HD):
  every mode rides the SAME per-tile native-resolution grid pass (small
  and zoomed-out text reads at native tile resolution); ALL collected
  line boxes CLUSTER into bubbles/paragraphs across tiles (bubblesOf) and
  ONE card places per CLUSTER region (the cluster's own extent, sampled
  local background, the original line height, line breaks preserved
  through the ' ~ ' markers) — never fixed grid cells, never model box_2d.
  Translation rides the ONE textTranslate the AI-Page path always used
  (with its 503/400 retry ladder, cap ladder and model chain) — the
  accuracy source AND the failure fix. Manga/Manga HD are a
  PREPROCESSING VARIANT of the same pipeline (the x2-nearest grayscale
  hard-5/95 PNG preprocess PER TILE + the jpn_vert/jpn_vertb models), not
  a separate placement or host path — the whole-page OcrHost run is
  RETIRED. The host lifecycle is the robust shared one: a run onto a busy
  page RESETS it first (window.__reset terminates the stuck worker), the
  timeout is sequence-guarded (an overtaking run's callbacks are never
  failed by a stale timer) and ALSO re-arms the page, and the host page
  itself fails fast ('busy') when a job is still pending.
- Diag: per-tile char yields, the cluster count and WHICH translate path
  ran (ai / gtx) ride one line.
- Probe: the cross-tile cluster table (line boxes on opposite sides of a
  tile seam merge into ONE region), the stuck-worker re-arm ladder + 5
  consecutive runs succeeding (the REAL page script under Node), and the
  source pins of the one-road dispatch.

### 3. BOLT AI TABLES: REAL LINES
- Field shape: GFM tables rendered as an aligned monospace text grid — no
  borders, no header band, no cell padding; a table read as a code slab.
- Fix: the TableSpan — a custom ReplacementSpan that draws the true grid
  on the canvas: the outer border rides the same rules as every
  row/column boundary (rows+1 horizontal + cols+1 vertical rules — a 3x3
  table draws 4 + 4), the BOLD header row sits on its filled band, cells
  pad (10dp/5dp), line/text colors are theme-aware, column widths come
  from the content (equal-split floor, one bounded type-shrink against the
  width budget), and getSize grows the line's metrics to the full table
  height so the TextView lays the grid out as one tall line. The raw
  markdown keeps riding the bubble's tag for long-press copy; the
  renderTable text grid stays the never-eat-a-reply fallback.
- Probe: the rule geometry (a 3x3 draws 4 + 4 rules, all 9 cells, header
  row bold) + the span/draw source pins.

### 4. QR: THE GALLERY LADDER + THE PREVIEW FILL ASSERTION
- (a) Field shape: picking a photo from the device never detected the QR
  — the gallery path was one ~2000 px downsample through the plain
  two-attempt decoder. Fix: FULL resolution first (the smallest inSampleSize
  holding the decode under the 8 MP memory law), each scale through
  decodeRobust — the camera's full multi-pass kit (plain + inverted ARGB,
  the luma contrast-stretch + half-scale passes, the 90/180/270
  rotations, and a ten-window crop ladder at two granularities for a code
  that occupies part of the frame) — then progressive downscales
  (maxDim/1200/800). Probe: a stylized 12%-contrast QR in the corner of a
  1600x1200 photo decodes (the old single pass cannot read it), the pure
  rotate/crop/luma passes pinned.
- (b) Field shape: the scanner's camera showed a bottom-half-black panel.
  Fix: the preview transform re-derives on the FIRST delivered frame
  (onSurfaceTextureUpdated) AND on every layout, computes against the
  ACTUAL pinned buffer dims + current view dims, ASSERTS the cover scale
  fills BOTH axes (scaled content >= view on width and height), re-pins
  the buffer once on mismatch, and Diags viewW/H, buffer dims, theta and
  the cover scale so a residual mismatch is readable in the field. The
  TextureView stays full-screen (MATCH_PARENT both axes). Probe: the
  fills law (portrait 720x1600 with 640x480 and 1280x720 buffers) + the
  one-shot re-pin ladder.

### Gates
- scripts/test_v1427.py: the four probes (the parity table + the TBS/DIU
  fixture walks, the re-arm + 5-consecutive-runs Node probe on the real
  page script, the table rule geometry, the zxing stylized-photo probe +
  the fills law) and the source pins. 45 prior gates re-pinned to
  141/1.4.27 (scripts/repin_gates_1427.py); dated re-points where the
  merge legitimately moved pinned targets. The Robolectric launch smoke
  rung stays before the build.

## [1.4.26] — 2026-11-21

**The black-on-black + bubble round: four field-rooted fixes — forced dark
that keeps designed colored buttons readable and never freezes an icon
verdict on an undecoded image, the bubble-aware manga mode restored, GFM
tables that actually render plus markdown sub/superscripts, and a QR
scanner whose preview is upright in every orientation with stylized-code
decoding. Root-caused from the user's saved DOM of the DIU student portal
(captured with the dark engine live). The launch smoke rung stays in the
pipeline.**

### 1. FORCED DARK: BLACK-ON-BLACK — the saved-DOM root-cause
- Field shape: on studentportal.diu.edu.bd the hamburger menu was invisible
  (black on the inverted black header) and the colored action buttons read
  as dark smudges — the exact evidence: the 24x24 data:URI `<img>` inside
  `div.sidebar_icon` carried NO `__boltIC` while the eight 48x48 sidebar
  icons did, and btn-success/info/warning/danger/teal + badges carried
  inline `rgb(153,153,153)` over inverted mid-luminance grounds
  (153→102 text reads 1.4–2.5:1).
- Root causes: (a) the icon walk classified an image ONCE — a 0-rect or
  not-yet-decoded first pass froze a "content" verdict forever; (b) the
  fixed v1.4.19 clamp (153) read unreadably on mid-luminance inverted
  grounds; (c) mid-luminance designed chips with white text were blindly
  inverted even though the btn-primary/dark/purple path proved preserving
  them reads perfectly.
- The fix, three surgical legs:
  - THE ANCESTOR ICON SIGNAL: `iconNamed` also walks the parent chain
    (≤ 6 levels) for the icon|menu|toggle|nav|… pattern — an unclassed
    `<img>` inside `div.sidebar_icon`/`div.line_icon` rides the inversion
    DETERMINISTICALLY, independent of decode timing.
  - THE ONE-SHOT RACE KILLER: any IMG/SVG that was 0-rect or undecoded at
    the first walk is DEFERRED — a one-shot load listener, delayed
    re-passes (~1 s, ~3 s) and resize/orientationchange (the d-lg-none
    responsive icons) re-run the classifier and apply the ride when the
    image is ready; data:URI images are icon assets; a recount guard
    keeps the census honest. A plain undecoded icon becomes IC after its
    load — never a frozen verdict again.
  - THE CONTRAST-AWARE PRESERVE + TARGET: a solid background in
    [0.18, 0.80] whose own/inherited text is light (> 0.7 luminance) is a
    DESIGNED chip and is PRESERVED exactly like the dark-button path;
    only near-white grounds (> 0.80) still invert. For the remaining
    inverted cases the fixed 153 clamp is replaced by a contrast-aware
    target — the walk tracks the rendered ground luminance and scales the
    text so it reads ≥ 0.75 after inversion at ≥ 4.5:1; when no target
    can reach 4.5:1 the element is PRESERVED instead of clamped (designed
    light text beats every gray the old law could write).
- Probes: the pure target/clamp arithmetic tables, the QR-style transform
  tables, and the transcribed saved-DOM fixture walk — the hamburger +
  mini toggle ride, the five colored buttons + badge are preserved with
  NO clamp write, the mid-ground light text is preserved, the census
  counts ic/late/keep.

### 2. MANGA TRANSLATION — the bubble-aware mode restored
- Field shape: v1.4.25's fixed 2x3 grid cards left the speech bubbles,
  covered the art, and flattened line breaks/style on manga pages.
- The fix: the mode picker gains Manga / Manga HD alongside AI Page (the
  grid, kept for general pages) and Basic OCR. The manga stage restores
  the v1.3.74 line: the manga preprocess (x2 nearest upscale capped
  2600 px/6.5 MP, grayscale, the hard 5/95 percentile stretch, PNG —
  line art hates JPEG ringing), vertical-Japanese OCR (jpn_vert /
  jpn_vertb models on demand, exact repo sizes, the model diet no longer
  deletes them), BUBBLE CLUSTERING of the OCR line boxes (stacked lines
  and side-by-side vertical columns merge; distinct bubbles never) — ONE
  matched card per bubble at the bubble's own extent (sampled bubble
  background, corner radius, the original's line height, line breaks
  preserved through the ' ~ ' marker), never a grid cell, never the art.
  The OCR-blind path (stylised art) detects paper-white bubble regions
  GEOMETRICALLY (interior bright blobs — border-connected page paper
  dropped) and crops each through the vision text-only pass; the model's
  box_2d is never trusted (the v1.4.25 grid law, kept).

### 3. BOLT AI: TABLES RENDERED EMPTY + sub/superscripts
- Root cause: commonmark's GFM AST wraps table rows in TableHead/TableBody
  blocks — the renderer's firstChild-only walk collected ZERO rows and
  every GFM table rendered empty. The walk now descends into both
  wrappers in document order.
- Plus the markdown delimiter forms: `~sub~` renders a subscript and
  `^sup^` a superscript (custom delimiter processors; the GFM
  strikethrough extension re-configured with its own documented
  requireTwoTildes knob so `~~strike~~` keeps working), matching the
  `<sub>`/`<sup>` HTML tags that already worked.
- Probe: a compile-and-run probe against the real commonmark 0.24.0 jars —
  a GFM table yields all header+body rows through the wrapper traversal
  (the old walk: ZERO), and all three inline forms render their nodes.

### 4. QR SCAN: half-screen/90° mismatch + stylized codes
- Root cause: the preview surface arrives before the camera opens, so the
  buffer is pinned to a 640x480 placeholder first; the later re-pin to
  the camera preview size never re-applied the rotate/cover-scale
  transform (a buffer-size change does not fire the view-size callback) —
  the landscape sensor buffer rode the portrait view untransformed.
- The fix: the buffer pin is tracked (bufW/bufH — what the SurfaceTexture
  buffer really is), the transform computes against the ACTUAL buffer
  dims and RE-APPLIES after every re-pin — upright, full-screen and
  aspect-true in every orientation.
- Decoding: after the plain + inverted attempts, a 2/98-percentile
  contrast stretch of the luma plane and a half-scale pass (the ZXing
  recommendation for big/blurry codes), each with its own inverted retry
  — stylized/tinted/low-contrast codes decode far more reliably.
  Probe: a 12.5%-contrast stylized QR decodes (v1.4.25 could not read it).

### Build / gates
- versionName 1.4.26, versionCode 140. The Robolectric LAUNCH SMOKE stays
  a release rung. The full gate suite runs green; the new v1.4.26 gate
  pins every fix with pure tables, two compile-and-run Java probes on the
  real dependency jars, and the transcribed saved-DOM fixture walk; 43
  prior gates re-pinned with dated notes.

## [1.4.25] — 2026-11-21

**The reliability + coverage round: five field-rooted fixes — math fonts that
work on metered mobile data, colored AI chat text, screen translate that
covers the whole page, the password fill that reaches cross-origin SSO
iframes and reports its outcome, and forced-dark font icons that read
clearly. The launch smoke rung stays in the pipeline (it caught the v1.4.22
corpse).**

### 1. MATH WORKS RELIABLY (fractions/matrices) — the on-demand cut backfired
- Field shape: `math dl fail dl base/jlm_cmex10.ttf` on Realme RMX2020 /
  Android 10, often on METERED mobile data — the pack never landed, so every
  formula rendered through the weak v1.4.20 LaTeX-subset spans.
- Root cause: `MathFonts.kt` (a) skipped metered connections entirely, (b)
  set `failedProc` after ONE failure (no retry until restart), (c) had no
  manual retry. The URL was valid; the policy was the corpse.
- The fix, reliability over the last ~100 KB:
  - THE CORE-FONT REBUY: the 7 CORE fonts (base/jlm_cmex10, base/jlm_cmmi10,
    base/jlm_cmmib10, latin/jlm_cmr10, maths/jlm_cmsy10, maths/jlm_msam10,
    maths/jlm_msbm10 — 166,720 B raw) ride the APK again via
    `scripts/thin_jlatexmath.py`'s CORE set. Fractions, matrices, Greek,
    integrals, sums and AMS symbols work OFFLINE and ON METERED data
    immediately. The engine loads typefaces LAZILY (FontInfo.getFont,
    verified against upstream v0.2.0), so the 23 missing optional fonts
    cannot break engine init.
  - The remaining 23 optional fonts (euler/fraktur, optional CM shapes,
    special/stmary/dsrom, rsfs — 289,048 B raw) stay ON-DEMAND for advanced
    TeX.
  - THE RETRY LAW: `failedProc` is dead — a failed attempt stamps a
    20-second back-off and the NEXT math render retries; the MANUAL door
    ("Download full math fonts" chip in the Bolt AI panel, with live status
    downloading/ready/failed-retry) forces an attempt that ignores both the
    back-off AND the metered skip. Core math never depends on the network.
  - APK lands at ~3.15 MB (still below v1.4.23's 3,325,166 B).

### 2. COLOURED TEXT in Bolt AI chat
- Root cause: AiRender's HTML handler ignored explicit color entirely.
- The fix: `<span style="color:…">` and `<font color="…">` paint their
  range through a ForegroundColorSpan — hex (#rgb/#rrggbb/#rrggbbaa),
  rgb()/rgba() (percent channels included) and the common named colors,
  parsed SAFELY (`AiRender.parseHtmlColor`, PURE: an unparseable colour
  colours nothing; translucent alpha below 0.4 declines rather than wash
  out). The code-block syntax colorizer (tokenizeCode/codeColors) is
  verified to emit distinct keyword/string/comment/number colors.

### 3. SCREEN TRANSLATE COVERS THE WHOLE PAGE (not a thin right strip)
- Field shape: `OCR chars=54 lines=5` -> vision fallback ->
  `vision ok lines=1` (one hallucinated box_2d) — two failures: OCR
  under-reads, vision trusts invented boxes.
- The fix, THE GRID LAW: the screen is cut into a 2x3 (portrait) / 3x2
  (landscape) grid; every cell is OCR'd at NATIVE resolution (the retired
  whole-screen ≤1600-px encode halved a modern 1440x3088 screenshot — the
  small-text under-read's other half), with a MILD 2/98 percentile contrast
  stretch (the old manga-era hard stretch hurt general text; PSM 3 auto
  layout stays). A cell that under-reads (< 20 chars) gets a per-cell
  VISION text-only pass OVERLAID ON THAT CELL — the fixed grid region
  places it, NEVER the model's box_2d. Merge: well-read cells ride the ONE
  numbered-lines textTranslate; under-read cells ride their vision cards;
  the whole-screen low-yield floor still guards the aggregate. The
  OCR-blind worst case is the same grid of text-only cards — full-page
  coverage is geometric, not hallucination-dependent. The source-language
  picker stays; per-tile char yield rides the Diag.

### 4. PASSWORD AUTOFILL does something on tap — and says so
- Field shape: `fillPwFromChip` called
  `window.__boltFillPw && window.__boltFillPw(u,p)` — the && guard failed
  SILENTLY when the function was absent or could not reach the form; the
  DIU login lives on auth1.diu.edu.bd, a cross-origin SSO iframe
  unreachable from the main frame (and `evalMedia` could even target a
  background media tab).
- The fix: `__boltFillPw` is injected into EVERY frame (FrameHooks'
  document-start script, the v1.3.58 relay), a login field in ANY frame
  arms the chip with ITS OWN host (the v1.3.91 SSO law, finally per-frame),
  and the fill is ROUTED: the tap runs `__boltFillPwAll` in the top frame,
  which fills locally and posts `fillPw` ONLY to the child frames that
  reported a login field (recorded by window identity — the password is
  never broadcast). The fill JS is robust: the native value setter + input/
  change events (React/Vue-controlled inputs notice), the username hunt by
  autocomplete/name/id/mail/phone. The outcome ALWAYS reports
  (Bolt.pwFillResult: toast + Diag `pw fill ok/fail host=…`); a relay that
  never answers falls to the visible 1.5 s timeout.

### 5. THE HAMBURGER / FONT-ICON reads clearly in forced dark
- Field shape: the Moodle hamburger is a Font Awesome glyph
  (`<i class="fa fa-bars">`) — not IMG/SVG, so the icon classifier skipped
  it and the census read mono-detected but ic=0; a mid-gray glyph on a
  preserved dark header sat FAINT (the old raise only caught lum < 0.25
  and lifted to a 153 mid-gray besides).
- The fix, THE RIDE + LIGHT LAW: icon-font glyph elements and their
  ::before/::after pseudos RIDE THE PAGE INVERSION like text (dark ->
  light) — the inv===1 clamp SKIPS them (never a faint gray); a glyph
  pinned dark OR MID (lum < 0.55) inside a preserved dark subtree lifts to
  the VISIBLE light (rgb(232,232,232)) — inline on the element
  (svSave-restorable) and via !important pseudo rules (sufficient
  specificity over any site-pinned glyph color). The census counts fi=N so
  the next field report reads the glyphs back.

### Gates
- The v1.4.25 round (scripts/test_v1425.py) pins all five fixes: the
  core-font AAR inspection + the retry/status decision tables, the
  colour-parser + code-colorizer probes, the grid geometry + per-tile
  decision + the mock-DOM fill relay, and the font-icon mock walk. Dated
  re-points: test_v1412 (the grid OCR encoder), test_v1420 (the box-mapping
  retirement), test_v1418 (the 4th chain walk site), test_v1423/v1424 (the
  census fi field, the visible-light lift, the grid hybrid), test_screen
  (the grid vision call), test_blobdl (the deliberate pwFillResult bridge
  addition); the standing sweep repinned every gate to 139/1.4.25
  (repin_gates_1425.py); the launch smoke stays the release-pipeline rung
  it became in v1.4.23.

## [1.4.24] — 2026-11-21

**The corpse-fix + size round: two field-rooted corpses (a stale navigation
snapshot resurrecting the OLD page over every new navigation, and a
manga-era hardcoded OCR language starving screen translate of text) plus
the twice-requested APK size reduction — the jlatexmath font pack moves to
an on-demand download. The launch smoke rung stays in the pipeline (it
caught the v1.4.22 corpse).**

### 1. THE STALE-SNAPSHOT NAVIGATION CORPSE (CRITICAL)
- Field shape: on facebook.com -> home -> search youtube -> Facebook
  returns; opening youtube.com directly ALSO returned Facebook; the diag
  log showed `tab hist restore idx=N warm` firing repeatedly for the same
  tab.
- Root cause: `Tab.sleepState` / `Tab.histBlob` (the v1.4.21 back-history
  snapshots, written by `saveHist()`) were never cleared. `loadInTab()`
  set `t.url` to the NEW url but left the stale snapshot, so the rebuilt
  WebView `restoreState()`d the OLD page and returned true — `loadUrl()`
  never ran, the old page showed, and the snapshot re-restored on every
  later rebuild.
- The fix, three guards: (a) `loadInTab` — the funnel every explicit
  navigation pours through (omnibox GO, home search, direct URL,
  suggestions, bangs) — now calls `t.clearHist()` BEFORE `showTab`/
  `loadUrl`: a new navigation retires the old snapshot; (b) `restoreHist`
  gained the URL guard — a restore whose current-item URL disagrees with
  `t.url` is stale and returns false, so the new url loads; (c) `showTab`
  consumes the warm Bundle after a successful restore (single-use), so a
  spent snapshot can never resurrect a page the tab moved away from.
- PRESERVED: the v1.4.21 feature itself — returning to a genuinely slept
  tab still restores its back/forward list (sleep never assigns a fresh
  `t.url`). Only an explicit new navigation overrides it.

### 2. THE HARDCODED OCR LANGUAGE (CRITICAL)
- Root cause: `ScreenTranslate.langsOf()` returned
  `chi_sim + jpn + eng` for every case (the manga-era default) and
  `LANG_OK` served nothing else. On any other source script tesseract
  extracted almost nothing — the user's diag: a full 720x1353 screen
  yielded `chars=50 lines=5` — so the OCR-hybrid translated a tiny
  fraction and screen translate "looked broken".
- THE SOURCE-LANGUAGE LAW: the OCR source language is now a user pick,
  separate from the translate TARGET, spanning the app's own language
  list (en, bn, hi, es, ar, fr, de, pt, ru, zh-CN, ja, ko, id, tr, ur, vi
  → their tesseract packs); the default is `eng` (Latin script), NOT
  chi_sim+jpn; the legacy trio stays available as "Auto" for the manga
  case. The model downloader + interceptor serve every pack (sizes read
  from the pinned tessdata_fast repo).
- THE LOW-YIELD VISION FALLBACK: a full screen that OCRs to fewer than
  ~120 characters was not read — the vision model then reads the WHOLE
  screenshot (language-agnostic) instead of translating the sparse
  fragment. The hybrid (OCR boxes + ONE text request) runs only when OCR
  read well. The diag line now names the yield AND the path taken
  (`-> hybrid` / `-> vision`).

### 3. THE SIZE ROUND (the user asked twice)
- v1.4.23 shipped 3,325,166 B; the jlatexmath-android bundled font set
  was 30 TTF binaries — 455,768 B raw, 286,982 B stored-compressed in
  the APK (the user's "~470 KB" was the raw-asset measure). v1.4.24
  lands at 3,037,761 B (2.90 MB — a 287,405 B reclaim, inside the
  ~2.8-2.9 MB target).
- THE FONT-PACK SPLIT: the engine code stays bundled; the 30 TTFs move
  OUT of the APK (`scripts/thin_jlatexmath.py` builds the thin AAR —
  `app/libs/jlatexmath-thin.aar`; the app's own source tree re-implements
  the `JLatexMathAndroid` seam class with doors that fall through to
  the downloaded pack) and download ONCE on the first math render into
  `filesDir/jlmfonts` — exactly the v1.3.73 tesseract pattern. Hosts are
  the upstream repo's own assets at the pinned tag (jsDelivr first,
  raw.githubusercontent fail-over), byte-identical to what the AAR
  bundled.
- Failure posture: a failed download (or a metered connection, which
  skips silently) renders formulas through the v1.4.20 LaTeX-subset
  spans — never a crash; bubbles that carried math re-render the moment
  the pack lands. Math renders fully after the one-time download.
- Also: `shrinkResources` verified ON and R8 full mode pinned explicitly
  (`android.enableR8.fullMode=true`); the asset audit found no other
  dead weight (every remaining asset serves a live feature — the next
  lever is the 1.99 MB + 1.51 MB EasyList/EasyPrivacy snapshots).
- The launch smoke ran BEFORE and AFTER the split — the law held.

### 4. FORCED-DARK OVER-KEEP (minor)
- The census diag read `dark icons auth.meta.com small=7 ic=0 ni=0
  rescue=0` — seven small media ALL classed content, none inverted; if
  they were UI glyphs they stayed dark on the dark page.
- THE MONOCHROME-GLYPH LOOSEN: a small element that paints only grays
  (SVG fill/stroke grayish or extreme luminance; an IMG with tiny
  intrinsic dimensions is an icon asset) now rides the page inversion
  BY DEFAULT even inside article/main/p/figure context, even
  banner-shaped. Large photos and banner-shaped COLORFUL images stay
  content. The census grew a `mono=N` field so the next field report
  reads the verdicts back.

### Pipeline
- Gates: the v1.4.24 round (scripts/test_v1424.py) pins all four fixes
  with probes (the navigation decision model, the source-language
  mapping + low-yield law, the font-pack split + fallback law, the
  monochrome classifier against a mock DOM); ~50 gate files re-pointed
  to 138/1.4.24 (repin_gates_1424.py); the launch smoke stays the
  release-pipeline rung it became in v1.4.23.

## [1.4.23] — 2026-11-20

**The critical rebuild: v1.4.22 CRASHED ON LAUNCH — reconstructed from
decompiled R8 bytecode, it passed every static gate and never opened on the
user's Realme RMX2020 / Android 10. This release re-implements the four
fixes as fresh hand-written code on the clean v1.4.21 tag and adds THE
LAUNCH LAW to the pipeline: the release now verifies the app actually
OPENS before publishing anything. The new Robolectric smoke drove
Application.onCreate -> the launcher activity -> buildHomeView and
immediately caught a launch-crashing autobox NPE in the home pill's
`setColorFilter(cond ? int : null)` ternary — the exact corpse class,
fixed before any user saw it.**

### THE LAUNCH LAW (new, pipeline-level)
- The lesson named forever: **static gates prove code EXISTS, not that the
  app OPENS.** A decompile-port can be syntactically clean, compile, pass
  72 probes — and still die in `onCreate` from bytecode-shape semantics
  (the autobox ternary above is the textbook case).
- `scripts/release.sh` now runs `testDebugUnitTest` (Robolectric:
  BoltApp.onCreate -> BrowserActivity through the launcher alias ->
  buildHomeView, plus the engine-mark pill binding assert) BEFORE the
  build signs or publishes. A FATAL EXCEPTION stops the release.
- The law pays for itself on day one: the smoke run caught the
  `Integer`-unboxing NPE in the pill binding (v1.4.21's own
  `applyEngineLogo` carries the same ternary but its catch swallowed it;
  the unguarded port put it on the launch path — that was the crash).
  Both sites are now explicit null-safe branches.

### Fixed — FORCED-DARK SMALL-ICON DEFAULT-FLIP (MediaHook.darkSmartJs, round 4)
- **DEFAULT-FLIP:** a small media element (longest edge <= 64 px, IMG or
  SVG) now rides the page inversion (gets the IC class) **by default** —
  it stays un-inverted only when it is clearly content: larger than 64 px,
  banner-shaped (aspect ratio >= 2.5), or photo-classed inside
  article/main/p/figure. The fragile uiCtx ancestor-guessing family is
  retired.
- **FONT-ICON GUARANTEE:** icon-font glyphs and their ::before/::after
  pseudos ride the inversion; a glyph pinned to a fixed dark color inside
  a preserved dark subtree is lifted by the new upward clamp (raiseCss)
  to a visible mid-gray.
- **CONTRAST RESCUE:** after the walk, a bounded pass over small
  un-inverted media that is dark-on-dark (SVG fill/stroke luminance;
  same-origin IMG via an 8x8 canvas sample — cross-origin keeps its
  pixels) adds the IC class so the glyph flips light. Caps: 240
  candidates / 80 rescues / 40 canvas samples.
- The v1.4.19/v1.4.20 floors survive the rewrite: the light-stroke clamp
  (0.6 floor), the < 1.5 px -> 2 px thickness bump, the pseudo rule
  sheet, the media family un-invert, the parity walk.
- **CENSUS:** the walk publishes `window.__boltDarkCount` (small/ic/ni/
  rescue) and BrowserActivity logs `dark icons <host> small=N ic=N ni=N
  rescue=N` after the late re-probe — the next dark-mode field report
  carries real numbers.

### Fixed — ENGINE LOGOS AS 64x64 RASTER PNGs + refresh everywhere
- The five engine marks ship as fixed 64x64 PNGs in `drawable-nodpi`
  (2.0-6.6 KB each) — every device draws the same pixels regardless of
  OEM vector rasterization differences. `scripts/rasterize_engine_logos.py`
  regenerates them offline from the archived vector sources
  (`scripts/engine_vectors/`, retired from `res/` — no duplicate resource
  names); `engineLogo(index)` mapping unchanged.
- The active engine mark binds on the omnibox chip, the home search pill,
  AND the engine picker rows (new `Ui.choiceSheetIcons`). `Fx.homeGen`
  bumps at BOTH engine-selection entry points (the omnibox picker + the
  Settings row) so the home pill follows a switch — never a recreate.
- Diag breadcrumbs log the drawable resource name actually set per engine
  (chip + pill).

### Fixed — SCREEN-TRANSLATE HYBRID (accuracy)
- The on-device OCR pass (tesseract) reads the text and its **exact box
  coordinates** first; then ONE pure-text `AiBridge.textTranslate` request
  carries every line, numbered, with the full screen as context — no
  hallucinated box_2d, consistent terminology, and the screenshot is not
  sent at all.
- The vision path is demoted to a fallback: it fires only when OCR cannot
  read the screen (stylised manga) or on a genuine MAX_TOKENS cut of the
  text reply; full-screen first, tiles only when too dense, each tile
  carrying the full-screen transcript as context; boxes merge by absolute
  coordinates with the iou seam dedup.
- `thinkingLevel: low` rides the first text try; any 400 drops the field
  cleanly (never thinkingBudget — that field caused the 429/400-era
  corpses). Coverage-gated delivery: only a genuine MAX_TOKENS cut under
  the 70% floor hands off; partial STOP replies deliver with the missing
  lines showing the original.
- The privacy confirm tells the truth about the fallback: the screenshot
  itself is sent only when OCR cannot read the screen at all.

### Fixed — AiRender FOUR-PACK (chat formatting)
- **HTML inline tags:** an HtmlInline/HtmlBlock visitor branch with an
  open/close span stack — `<mark>` highlight, `<u>`/`<ins>` underline,
  `<del>`/`<s>` strikethrough, `<sub>`/`<sup>` with 0.72 size, `<b>`/
  `<strong>` bold, `<i>`/`<em>` italic, `<code>` monospace + bg, `<br>`
  newline, `<a href>` URLSpan. Unknown tags render their inner text —
  never literal angle brackets. Tags still open at a paragraph/block end
  are force-closed (scope law).
- **Nested blockquotes:** every level carries its own QuoteSpan bar +
  indent, recursing (the flat italic is retired).
- **Task lists:** `commonmark-ext-task-list-items` 0.24.0 — a tappable
  checkbox (ClickableSpan flips the marker in the bubble, stays tappable).
- **Footnotes:** `commonmark-ext-footnotes` 0.24.0 (inlineFootnotes on) —
  superscript refs in first-citation order + an appended trailer;
  `commonmark-ext-ins` joins for `++underline++`. All three: BSD-2 pure
  Java, R8 tree-shaken.
- jlatexmath full TeX, syntax colors, styled tables, assistant-only
  rendering and long-press raw copy are untouched.

### Build / size
- versionCode 137 / versionName 1.4.23; shrinkResources + R8 full mode stay
  on; the APK lands at 3.33 MB (3,325,166 B — well under the 6 MB law; the three
  commonmark extensions cost ~25 KB of class files).
- Recorded as the NEXT size lever (not risked this round, per the
  release's whole point being launch stability): make jlatexmath
  on-demand (fetch the engine/font pack on first math use, like the OCR
  engine) to reclaim ~470 KB, or a lighter KaTeX-in-WebView path.

## [1.4.21] — 2026-11-20

**The corpse-map six-pack: every item arrived root-caused from the v1.4.20
source plus the field diag.log — the cures implement the named mechanisms.
A green gate proves code EXISTS, not that a bug is CURED: each fix carries
a compiled+run behavior probe where a pure body was extractable and a Diag
breadcrumb where it was not.**

### Fixed — MEDIA-NOTIFICATION KILL, the real corpse (critical, id=42)
- Root cause (from the v1.4.20 source): `publishSessionState` parked the
  SAME `artCopy()` instance into BOTH `METADATA_KEY_ART` and
  `METADATA_KEY_ALBUM_ART`. API-29 SystemUI recycles one processed bitmap;
  the twin key then holds a RECYCLED bitmap and the next notification
  inflation throws "The given region must intersect with the Bitmap's
  dimensions" — the async `RemoteServiceException` that killed the process.
  The v1.4.20 size-cap never touched the shared instance, so the crash
  survived it.
- The three-channel law: the large icon, `METADATA_KEY_ART` and
  `METADATA_KEY_ALBUM_ART` now each take their OWN fresh `artCopy()`
  (three independent instances per publish — a recycled channel can never
  poison another; probe-pinned).
- The guaranteed leg: on API <= 29 NO artwork leaves the service at all
  unless the user opts in — a new **Show media artwork** setting (default
  OFF on Android 10 and older, ON above; `artworkDefaultOn`,
  probe-pinned). Stability beats album art after three failed artwork
  fixes; a text-only notification cannot crash. Suppression logs one Diag
  line per artwork generation, and the toggle re-publishes immediately.

### Fixed — BACK HISTORY LOST AFTER TAB / APP SWITCH
- Root cause: `TabSleep.sleep()` saved only `sleepScrollY` before
  `destroy()`; the rebuilt WebView loaded `t.url` onto an EMPTY
  back/forward list, so Back fell out of the tab to the browser home. The
  device fires `trimMemory level=15` on every app switch, so background
  tabs slept constantly.
- Every destroy path (TabSleep sleep, the trimMemory sleep, the
  renderer-death path) now captures `WebView.saveState()` BEFORE the
  destroy; the rebuilt view `restoreState`s it first (the current item
  navigates itself) and falls back to `loadUrl(t.url)` exactly as before
  when nothing restores. The renderer-death path gains this too (the
  navigation history lives in the browser process — render death does not
  take it).
- COLD starts restore history as well: tabs.json persists each tab's
  capped parcel blob (`hist`, 128 KB cap — the downloads.json lesson),
  refreshed for awake tabs at every save (main thread only).
- The trim sleep trigger softened: the sleep pass now runs from
  `TRIM_MEMORY_MODERATE` (60) up — the level=15 app switches keep every
  background tab's renderer. The cheap thumbnail drop keeps its old gate.

### Fixed — AI SCREEN TRANSLATE "HTTP 400" (supersedes the thinking-budget idea)
- Root cause: `visionBody` sent `thinkingConfig{thinkingBudget:0}`, which
  `gemini-3.5-flash-lite` REJECTS outright; the v1.4.20 retry only fired
  when the 400 body named "thinking", so the request died with no
  recovery.
- Translate now omits `thinkingConfig` entirely (a field never sent
  cannot be rejected); on ANY 400 the RAW body rides to Diag (shortErr
  hid the exact rejection) and the cap steps DOWN the model's own output
  ceiling (`cap400Next`: 65536 → 32768 → 16384 → 8192 → give up;
  probe-pinned). MAX_TOKENS still ladders UP / tiles.
- Tiles now run PROACTIVELY: every AI screen translate goes out as a 2x2
  grid from the start (a dense screen on a low-ceiling lite model is the
  real cut-off case — the old shape burned two doomed full-screen
  requests first), and a quadrant that still exhausts the cap splits once
  more (one recursion level, bounded).
- A dedicated **translate model** field in the AI settings lets dense
  screens ride a higher-output model; empty = the shared chat model.

### Fixed — FORCED-DARK HAMBURGER STILL BLACK (the img/background half)
- Root cause: menus served as `<img src="menu.svg/.png">` are MEDIA —
  the media rule un-inverts them (double invert = the original black) and
  they vanish on the black ground; CSS background-image icons likewise.
- The classifier now covers small IMGs in UI CONTEXT (context first, size
  second): inside button / header / nav / label / summary / `[role=
  button]` / icon-named ancestors at <= 64 px, a bare link only at <= 48
  px (article thumbnails ride links too), icon-named elements anywhere.
  Small background-image icons on interactive elements join them. All are
  classed `.__boltIC` (filter:none — they ride the page inversion; a
  black menu glyph flips white). The v1.4.20 stroke/fill floor + thickness
  bump for inline SVG stay; probe-pinned on representative markup.

### Fixed — DUCKDUCKGO LOGO (the monochrome cutout)
- The v1.4.20 DDG drawable was the simple-icons MONOCHROME cutout — one
  orange path where the duck is negative space, so the duck showed the
  omnibox BACKGROUND (black on the dark theme). Rebuilt as the official
  two-color mark: the #DE5833 filled disc + the SAME CC0 duck geometry in
  WHITE (two paths — white on every background). Bing / Brave / Startpage
  verified to carry their own distinct official paths (the code mapping is
  correct); `applyEngineLogo` now logs the drawable resource name actually
  set per engine so the next "wrong logo" report is provable.

### Added — BOLT AI FORMATTING COMPLETED
- **Strikethrough**: the GFM `~~text~~` extension parses and renders
  (`commonmark-ext-gfm-strikethrough`, StrikethroughSpan).
- **Tables**: the aligned grid gains a BOLD accent header line (a real
  table reads with a header).
- **Syntax colors**: a bounded, theme-aware, hand-rolled colorizer
  (`tokenizeCode`, probe-pinned) colors comments / strings / keywords /
  numbers in code blocks for kotlin, java, python, js/ts, c/cpp, bash,
  sql, json, html and css — no highlighter dependency, per the size law.
- **Full LaTeX + MATRICES**: jlatexmath-android (GPL-2.0 + linking
  exception) renders complete TeX — `\begin{matrix}/pmatrix/bmatrix`,
  `cases`, `align`, fractions, integrals, Greek, accents — as a native
  drawable ImageSpan in the TextView chat. It initializes lazily (its
  auto-init provider is removed from the merged manifest; a browser start
  never pays for chat math), the v1.4.20 span subset remains the fallback,
  and the raw source still rides the bubble's tag for long-press copy.
- **Visitor completed**: images render as their alt text riding a link to
  the source (no network in a chat bubble); nested lists indent per
  level; `~~` joins the fast-path signal set.

**APK**: 3,294,655 B (budget 6,000,000 — the jlatexmath engine + fonts cost
~468 KB after R8 and asset compression; v1.4.20 was 2,826,337).

## [1.4.20] — 2026-11-20

**The evidence-pack: the media-notification kill and the 12 MB
downloads.json arrived with diag.log evidence; the single-route image
search and the ask-every-time download door were user-approved product
calls. Every fix carries a compiled+run behavior probe or a Diag
breadcrumb; the retired Bing upload path took its pins with it (dated
re-points, no green pins over dead doors).**

### Fixed — MEDIA-NOTIFICATION CRASH (root-caused from diag.log, critical)
- Evidence: `RemoteServiceException: Bad notification(tag=null, id=42)
  ... Couldn't inflate contentViews ... The given region must intersect
  with the Bitmap's dimensions` on the Realme RMX2020 / Android 10, firing
  when media autoplays (scrolling Google image/video results) and killing
  the whole process repeatedly. The exception is posted back by the system
  ASYNC — no try/catch around `notify()` can ever catch it; the bitmap
  itself must be made safe. The v1.3.22/27 guards (fresh ARGB_8888 copy +
  the 1px SysUI-size shrink) reduce but do not eliminate the API-29
  SystemUI artwork-crop failure.
- Every artwork that leaves `MediaControlService` on API <= 29 is now
  SMALL: `api29ArtCap` caps the longest edge at 256 px AND keeps a 48 px
  margin below the device's fatal 1/6-screen sizes (the 1px shrink proved
  insufficient — a near-fatal size is still fatal on some OEM crops),
  with a 64 px paranoid floor. The master bitmap is capped at fetch time
  and the per-publish copy re-validates: non-null, non-recycled, positive
  dimensions, ARGB_8888 — and the moment anything cannot be guaranteed,
  the artwork is DROPPED and the notification goes out text-only (no
  artwork beats an app kill). API > 29 keeps the fresh-copy discipline
  alone (those versions stayed crash-free).

### Fixed — FORCED DARK: thin lines and hamburger icons (v1.4.19 pass was incomplete)
- Two verified gaps: (a) `isMedia()` included SVG, so SVG icons (most
  hamburger menus) were skipped by the parity walk AND left un-inverted —
  a dark-stroked icon stayed dark on the now-black page, invisible; (b)
  nothing helped THICKNESS — a 1 px hairline at mid-gray is still faint.
- Small monochrome UI-icon SVGs (longest edge <= 64 px, `uiIcon`) are now
  distinguished from content SVGs (charts/illustrations stay un-inverted
  media): UI icons are walked and classed `.__boltIC` (filter:none), so
  they ride the page inversion — a dark hamburger glyph inverts to light
  and reappears on the black ground. Light strokes/fills (a white glyph
  on an inverted-dark button) clamp to the 0.6 luminance floor BEFORE
  the flip.
- THE THICKNESS BUMP (`bumpW`, pure): a low-contrast UI stroke thinner
  than 1.5 px is bumped to 2 px — clamped hairline borders, thin SVG
  strokes, and icon-font glyphs (a 0.45 px text-stroke, never on body
  text). Self-colored `::before/::after` glyph icons get generated
  per-element rules (bounded: 600 lookups / 150 rules). Everything rides
  the existing 3500-element cap + MutationObserver; photos are never
  touched; darkClearJs restores every new write exactly (stroke, fill,
  stroke-width, border-width, text-stroke) and strips the IC class.

### Fixed — AI SCREEN TRANSLATE "the AI reply was cut off"
- New evidence: the failure ~3 s after "scr ai ask" — far too fast to be
  a 32768-token generation. The v1.4.19 cap ladder was not the cure:
  `thinkingLevel "minimal"` still THINKS (and on models that reject the
  field, the no-config retry thinks FULLY) — either way the thinking
  tokens raid `maxOutputTokens` and the visible JSON truncates
  mid-array. Chat image questions worked because their replies are short.
- Translate now sends `thinkingConfig: thinkingBudget 0` — the HARD off;
  the whole output cap goes to the visible JSON. (The chat body keeps
  its own shape untouched.)
- The evidence channel: every attempt logs `finishReason` +
  `thoughtsTokenCount` vs `candidatesTokenCount` + reply length
  (`usageDiag`, pure) — a real cap cut reads MAX_TOKENS with cand at the
  cap; a thinking raid reads STOP with th large and cand small; a shape
  mismatch reads all-small with the parse still failing (the reply head
  rides out right after it).
- `parseBoxes` hardening — the LENIENT SALVAGE (`salvageObjects`, pure):
  a strict JSONArray parse used to burn the WHOLE reply as "cut off"
  when ONE object was malformed (a shape mismatch mislabeled as
  truncation). Complete `{...}` objects (balanced braces, string-aware)
  are now recovered and parsed; one bad box costs one box, not the
  screen. A salvaged parse under MAX_TOKENS still takes the ladder/tile
  road — never a half screen.
- THE TILE ROAD: when the 65536 rung is exhausted on MAX_TOKENS
  (`shouldTile`, pure — the error string IS the contract),
  ScreenTranslate splits the capture into a 2x2 grid and sends each
  region as its own (half-as-dense) request; boxes re-place onto the
  full image (`tilePlace`, pure) and seam duplicates merge away
  (`iou > 0.6`, pure). A failed tile costs its region, not the screen.

### Changed — IMAGE SEARCH: ALL ENGINES THROUGH GOOGLE (user-approved)
- Evidence: Bing still showed a black loading screen even though the
  v1.4.19 real-origin intercept fired and served the full 835 KB body —
  the saved HTML shell's JS results runtime needs the LIVE upload
  session the static shell lacks. Bing mobile visual search is a dead
  end that way; Google search-by-image works reliably.
- Every engine's image search now rides Google's search-by-image POST
  (the cookie/UA-continuity path), with a toast naming the route
  actually used. The Bing SBI upload path, the DDG/Brave -> Bing
  fallback, AND the v1.4.19 real-origin intercept that served it are
  RETIRED — no dead doors, no green pins over retired code. The camera
  and gallery doors are unchanged; upload failure falls back to
  https://lens.google.com/ for manual upload.

### Added — DOWNLOAD "ASK EVERY TIME" TOGGLE (user-approved, default ASK)
- The rename + choose-destination dialog existed for http downloads, but
  since v1.4.16 blob: downloads (Google images, AI chat output — every
  download in the field logs) routed straight through the JS bridge
  before the dialog: direct save + toast, no dialog.
- A toggle on the Downloads screen — "Ask every time" (default) vs
  "Direct download" — gates every door. When Ask is on, blob downloads
  route through the rescue chain FIRST (the bytes are read out of the
  page exactly as before) and are then OFFERED to the dialog
  (`Bolt.dlOfferBlob`): rename, size, destination. data: anchors ride
  the dialog too; http keeps it. When Direct, everything saves at once.
  More options, not fewer.

### Fixed — downloads.json BLOAT (root-caused: 12 MB in every cache-guard line)
- `DownloadEngine.saveDataUrl` parked the FULL base64 payload in
  `item.url` and `toJson()` wrote it out — every ~1.1 MB saved image
  permanently stored its entire base64 string in downloads.json.
- After the bytes are written, a DONE item's url is replaced by a short
  marker (`persistUrl`, pure); toJson caps DONE items only — a queued/
  failed item keeps its bytes (the revive/retry path decodes them); load
  trims the historical bloat once.

### Changed — OFFICIAL SEARCH-ENGINE LOGOS
- The v1.4.19 Bing/DuckDuckGo/Brave/Startpage marks were hand-authored
  approximations. They are replaced with the official brand outlines:
  simple-icons (CC0) for DuckDuckGo (#DE5833), Brave (#FB542B) and
  Startpage (#6563FF per the simple-icons brand table), and bing.com's
  own Fluent mark for Bing (#008373 brand teal). Google keeps its
  four-color drawable unchanged; the chip + picker wiring is untouched.

### Added — MARKDOWN + LATEX RENDERING IN BOLT AI CHAT
- Assistant replies now render with Markdown (headings, bullet/numbered
  lists, bold/italic, links, tables as aligned grids, code blocks with
  monospace + background) and LaTeX math (inline `$...$` / `\(...\)` and
  block `$$...$$` / `\[...\]` — superscripts, subscripts, greek letters,
  operators, \frac flattening, \sqrt, \text).
- Native rendering: commonmark-java core + the GFM-tables extension
  (~130 KB, zero fonts) parse the reply into Android Spanned — the chat
  stays TextViews, never a WebView. LaTeX rides hand-rolled spans on the
  SYSTEM font: the 6 MB law's "no heavy bundled font packs" clause rules
  out jlatexmath-android's ~1.5 MB Latin Modern pack for a chat nicety;
  unrecognized LaTeX renders verbatim (the honest subset).
- Math is held out of the parser as placeholder chars (`codeProtect`,
  pure) so `x_i * y` never becomes italic; code spans and fences are
  masked first (a `$...$` inside backticks is literal). Assistant
  messages only — user input stays plain; long-press copy yields the RAW
  source (the bubble's tag), never the rendered glyphs; the reply
  re-renders the moment it replaces the thinking bubble.

## [1.4.19] — 2026-11-19

**The root-cure ten-pack: items 1 and 2 were ROOT-CAUSED from the user's
diag.log and a captured URL — the named mechanism was fixed, not re-guessed.
Every fix carries a compiled+run behavior probe or a Diag breadcrumb; moved
pins were re-pointed with dated notes.**

### Fixed — INCOGNITO CRASH LOOP + EXIT BUTTON (root-caused from diag.log)
- Evidence: `RuntimeException: Using WebView from more than one process at
  once with the same data directory` on a Realme RMX2020 / Android 10
  (SDK 29), the `:incognito` process cycling app-start → crash through four
  pids, each crash falling back to the normal tabs menu.
- Root cause: in `BoltApp.onCreate` the "app start" Diag block ran FIRST
  and called `WebViewCompat.getCurrentWebViewPackage` +
  `WebAuthnHelper.isPasskeySupportAvailable` — both LOAD THE WEBVIEW
  PROVIDER. `setDataDirectorySuffix("boltinc")` ran only AFTER, threw
  `IllegalStateException` (provider already loaded), and the
  `catch (Throwable ignored)` swallowed it: incognito kept the DEFAULT
  data dir and every WebView construction collided with the main process's
  `AwDataDirLock`.
- The suffix is now the FIRST WebView-related action in the `:incognito`
  process (before any WebViewFeature/WebViewCompat/WebAuthnHelper call,
  before UserscriptEngine.init, before the WebPool prewarm); a failure is
  never silent (Diag line + the flag `Tab.buildWeb` reads to refuse
  construction against the shared dir — graceful degradation instead of a
  crash loop; the buildWeb fallback is try/catch-wrapped and both callers
  null-check).
- The EXIT button: `wipeIncognito()` ended with `killProcess` WITHOUT
  finishing the activities — the OS simply restarted IncognitoActivity
  (incognito home; each restart used to crash into the normal task). Exit
  now finishes the activities and removes the task FIRST, wipes, and posts
  the kill ~300 ms later so AMS has seen the finish transactions before
  the death (onDestroy's wipe stays as the final backstop).

### Fixed — OFFLINE ERROR PAGE CORRUPTED THE TAB URL (root-caused)
- Evidence: a tab whose address bar literally read
  `data:text/html,<html><body style='background:#1A1A22;…` with the
  original intent:// Facebook URL embedded as text.
- Root cause: `onReceivedError` built that HTML around `failingUrl` and
  called `view.loadData(...)` — `loadData` REPLACES the WebView's URL with
  a `data:text/html,…` URL, `doUpdateVisitedHistory` then wrote THAT into
  `t.url`, and tabs.json / the phoenix restore persisted it: the tab
  reloaded the ERROR PAGE forever, never the site.
- The error is now a native OVERLAY on flContent with a Retry button; the
  tab's URL, history entry and persisted url all keep the ORIGINAL page.
  Both URL-write hooks (`onPageStarted`, `doUpdateVisitedHistory`) carry a
  data: guard so no loadData artifact (the render-crash notice included)
  can ever become a tab address again. A Diag breadcrumb records the URL
  that must survive.
- intent:// links: when no app services the intent,
  `S.browser_fallback_url` is extracted (pure, probe-pinned) and LOADED in
  the tab — Facebook-style app links open the web fallback instead of
  dead-ending on "No app can open this".

### Fixed — TAB VOID ON DRAG-TO-GROUP (third report, card view, long list)
- v1.4.18's posted requestLayout lost the race on a ~40-tab grid: the
  DefaultItemAnimator's predictive REMOVE (~250 ms) outlived the
  one-frame-later relayout while the animator still pinned the departed
  row's stale slot. For the drag-join swap the animator is now taken OFF
  before the diff and restored ~350 ms later (after the layout pass
  consumed the diff with no animator — cards snap straight to their final
  slots, a hole cannot form). The drag door loses its slide-away
  animation by design: correctness over choreography. A Diag breadcrumb
  logs post-drop child count vs adapter item count (the count/layout
  invariant evidence; `dragJoinVerdict`, pure, probe-pinned).

### Fixed — FORCED DARK MADE THIN LINES / HAMBURGER ICONS TOO FAINT
- `invert(1)` maps a light-gray stroke (#ddd, lum ~0.87) to ~#222 on the
  inverted dark ground — near-zero contrast. The parity walk now carries
  a STROKE VISIBILITY FLOOR: for elements that stay inverted, a computed
  color or border color lighter than lum 0.8 is remapped DOWN to lum 0.6
  so the post-inversion result is a visible mid-gray. Only text/border
  colors (never backgrounds, never the media family); rides the existing
  walk (same single getComputedStyle, same 3500 cap, same
  MutationObserver — no extra budget); the inline writes store their
  originals and `darkClearJs` restores them exactly when the inversion
  lifts. The clamp arithmetic is the gate's probe-pinned transcription.

### Fixed — AI SCREEN TRANSLATE "CUT OFF" ON DENSE SCREENS
- A dense screen's JSON array of boxes overflowed the 16384
  maxOutputTokens — the reply truncated mid-array and the parser said
  "cut off". The output cap now rides a ladder: 32768 first, and when the
  envelope's finishReason says MAX_TOKENS the SAME model is retried once
  at 65536 (the 3.x flash ceiling) before the honest error. The decision
  (`translateCapAction`) is pure, probe-pinned.

### Fixed — BING IMAGE SEARCH STILL FAILED (Google route now works)
- Bing's 200 results body was saved as a file:// page with a `<base
  href>` — static resources loaded, but Bing's results are JS-DRIVEN:
  scripts XHR/fetch and read document.cookie from a file:// (opaque)
  origin, CORS blocks them, the results runtime never runs.
- The body is now served UNDER THE REAL bing.com ORIGIN: the WebView
  loads the real results URL and `shouldInterceptRequest` answers that
  ONE main-document GET with the cached body (one-shot — any later visit
  goes to the network). Origin, cookies (the v1.4.18 pump) and XHR all
  match; subresources load from the network. The hit decision
  (`shouldServeIntercept`) is pure, probe-pinned.

### Added — THE ACTIVE ENGINE'S LOGO ON THE SEARCH BAR CHIP
- The omnibox engine chip's left glyph is the active engine's brand mark
  (Google G, DuckDuckGo roundel, Bing b, Brave shield, Startpage tile —
  simplified vectors, brand colors never theme-tinted; a custom/unknown
  engine falls back to the generic magnifier with tint). Updates the
  moment you switch engines. `engineLogo`, pure, probe-pinned.

### Fixed — LONG-PRESS TOOLTIPS MISSING ON SOME BUTTONS (regression)
- Every icon-only button in the omnibox strip, the browser toolbar, the
  tab switcher, the generic list header and the settings home now carries
  `android:tooltipText` (plus the contentDescriptions the find-bar and
  read-aloud buttons were missing entirely). The omnibox strip also gets
  the programmatic `TooltipCompat` backport — the XML attribute only
  inflates on API 26+ and Bolt's minSdk is 24. The toolbar's long-press
  IS its quick action (those listeners legitimately consume — audited,
  documented). The fallback law (`tooltipFor`) is pure, probe-pinned; the
  gate audits every ImageButton in the touched layouts.

### Added — MANUALLY ADD / EDIT SAVED PASSWORDS
- The vault screen gains an "Add password" row and every entry's options
  sheet gains Edit (host / username / password), riding `saveEx` — the
  same encrypted upsert the site-driven save offer uses, so a hand-made
  entry is indistinguishable from a captured one. A changed identity is a
  true edit (the old row goes first). Main process only, by construction.

### Added — BOLT AI IMAGE / FILE ATTACHMENTS (temporary)
- A paperclip button in the Bolt AI input row (browser faces, main
  process only): images ride the turn as inline_data (the shared capped
  1280-px decoder, q85 JPEG), text files as labeled text parts
  (24k-char cap, 3 attachments max, ACTION_GET_CONTENT — no bundled
  picker lib, the size law). Attachments are MEMORY-ONLY: consumed by the
  send, wiped by Clear, gone on process death — never persisted, no
  restore path. The lifecycle verdict (`attachCountAfter`) is pure,
  probe-pinned.

## [1.4.18] — 2026-11-19

**The field-cure six-pack: v1.4.17 shipped 41/41 gates green but most field
symptoms survived — static pins prove code EXISTS, not that the bug is
CURED. Every fix below carries a compiled+run behavior probe or a Diag
breadcrumb, and the moved pins were re-pointed at the door the user
actually uses.**

### Fixed — DRAGGING A TAB ONTO A GROUP LEAVES A HOLE (the door was wrong)
- v1.4.17's `reloadGridNoVoid()` was wired into the TWO ⋯-menu
  move-to-group doors only. The DRAG door — the one the report's user
  actually used — still called a bare `adapter.reloadDiff(joinGid)` at all
  three drop sites (join, merge-into-existing, merge-creates-new): no
  `stopScroll`, no span invalidation, no `requestLayout`. And in CARD view
  `getSpanSize()` returns 1 for groups too, so the span-cache theory could
  not explain a card-view hole at all — the real mechanism is
  ItemTouchHelper's drop-recovery animation racing the DiffUtil remove
  pass, leaving the stale slot geometry pinned under the settling card
  until the switcher was closed and reopened.
- All three drag sites now route through `reloadDragNoVoid()` (new): the
  animated DiffUtil reload wrapped in the full no-void trio — `stopScroll`
  before the swap, span-cache invalidation + `requestLayout` after — and
  `reloadDiff` itself posts one `requestLayout` AFTER
  `dispatchUpdatesTo()`, the frame-later relayout that runs after the
  settle/restore callbacks and so cannot be consumed by the stale-layout
  pass. Ungroup (`remove_from_group`, both the card menu and the sheet
  menu) rides `reloadGridNoVoid()` too — the span-change class the v1.4.17
  plans section already predicted, swept without waiting for its own
  field report.

### Fixed — OMNIBOX ACTION ROW INVISIBLE UNTIL THE FIRST KEYSTROKE
- The v1.4.16 two-row omnibox gated Row 2 (copy, paste, duplicate, image
  search, voice) on the field HOLDING TEXT — tapping the search box showed
  a bare field until the first keystroke landed. The strip is shown the
  moment the omnibox opens now (the focus law); only the CLEAR button
  keeps following the empty/typing state, and the v1.4.15
  one-setVisibility-per-flip cache survives.
- Copy on an EMPTY field used to be a silent no-op; it now copies the
  active page's address (the field was cleared, the address is still what
  you want), and a home tab says "Nothing to copy" instead of doing
  nothing silently.

### Fixed — DARK MODE PAINTS LIGHT SITES WHITE UNTIL FULLY LOADED
- The v1.3.94 ask-first hold was the engine of the white-paint: a site
  that merely DECLARES dark support (a color-scheme meta, any
  prefers-color-scheme rule, a data-theme attribute — very common) but
  renders light under a fresh cookie jar held WHITE for the whole hold
  (~1.2 s + ~1.8 s) before the fallback inversion landed. The commit-time
  pass now inverts the moment the luminance probe reads light — a
  declaration is not evidence the page WILL self-darken, the probe's
  painted-light reading is evidence it has NOT. `darkAwaitSelfDark` is
  deleted; the 2.5 s late re-probe stays as the pull-back for a site that
  genuinely darkens on its own (it only ever clears bolt-inversion →
  none), the run-token guard and the finish-time backstop are unchanged,
  and the accepted trade is a brief double-dark on the rare async-dark
  class in exchange for zero white hold on every merely-declaring site.
- The late re-probe aborted for every BACKGROUND tab (`w != activeTab()
  .web → return`) while the finish backstop skips urls the commit pass
  already spoke for — a background-loaded tab could keep Bolt's inversion
  forever after its site self-darkened. The guard now checks the tab is
  still alive; fullscreen video shields only the active tab it belongs
  to. A commit-pass Diag breadcrumb (`dark commit-pass <host>`) proves
  the hook fires on device.

### Fixed — IMAGE SEARCH RESULTS SHOW GREY/BROKEN IMAGES (session split)
- The upload rode a bare `HttpURLConnection` with a hand-written
  User-Agent and NO cookies, then handed the result URL to the WEBVIEW —
  a different cookie jar. Google could not associate the results page
  with the upload session and answered with the error-image placeholder;
  v1.4.17's q90/bilinear/CRLF fidelity legs never touched this gap. The
  upload session is now CONTINUOUS with the browser: the POST carries the
  WebView jar's cookies for the endpoint, speaks the WebView's exact
  User-Agent, and every Set-Cookie minted along the redirect ladder is
  pumped into the WebView's `CookieManager` before the result URL loads
  (each GET hop carries and pumps too — Google's consent redirect
  resolves when the consent cookie rides along).
- Bing's 200-body results page is saved as a local `file://` page whose
  HTML carries relative resource URLs — against a file:// base every
  image died. A `<base href="https://www.bing.com/">` is injected into
  the saved body (pure function, probe-pinned).

### Fixed — AI TRANSLATE "PROVIDER ERROR" ON A CUSTOM MODEL (dead end)
- `chain = if (m == GEMINI_DEFAULT) arrayOf(m, *FALLBACKS) else
  arrayOf(m)` — a CUSTOM model disabled every fallback, and the chain
  only advanced on 404 model-not-found. A custom model meeting a 503
  "model overloaded" wave (gemini-3.8-flash had documented ones in
  2026-11) had no retry, no fallback, and the user ate a bare "provider
  error" — a robustness gap, not an outdated model name. The chain now
  ALWAYS carries the default + fallbacks after the custom pick (deduped,
  `buildChain`, probe-pinned), advances on the 5xx bucket too
  (`advanceChain`, probe-pinned), and the same model retries a 503 with
  a bounded backoff that honors the provider's `retryInfo.retryDelay`
  hint when present (else 5 s, capped 30 s, ≤2 retries; the 429 free-tier
  patience is unchanged). The model that actually answers is logged and
  Diag-recorded always, and toasted when it is NOT the configured pick —
  a silent fallback reads as "my model works" while it is down.

### Fixed — EXTERNAL SEARCH BOUNCES AN INCOGNITO SESSION TO NORMAL MODE
- The system search doors resolve into the MAIN process's faces: the
  launcher aliases' WEB_SEARCH/SEND/PROCESS_TEXT filters, and the
  home-screen widget's hardcoded `new Intent(context,
  BrowserActivity.class)`. v1.4.17's SearchIncognito alias only ADDED a
  second chooser row (frequently collapsed into the toolbar overflow),
  so an incognito user's search surfaced the NORMAL task — whose back
  stack still held the normal tabs menu, the exact bounce the report
  named. The faces are now SESSION-AWARE: whichever BrowserActivity
  variant resumes records the session (incognito/normal) into a tiny
  cross-process file (atomic tmp+rename; both processes share filesDir),
  and the normal search faces read it FRESH on every intent — an
  external search or widget tap forwards into `IncognitoActivity` and
  finishes the normal face so the normal task never surfaces. The
  forward happens before onResume so the record is never overwritten by
  the face that just read it; a missing record reads normal (the feature
  cannot hijack a normal search into private mode); the in-app incognito
  omnibox → tabs-button round trip stays in-process and private as
  always; the explicit "(Incognito)" alias remains the manual door. Diag
  breadcrumbs name the face and the forward on every search intent.

## [1.4.17] — 2026-11-18

**The polish seven-pack: the move-to-group span-void law, the group-sheet
active highlight + the rim inset law, the visual-search fidelity pass, the
commit-time dark pass + dark canvas (the white-flash fix), the AI
raw-response evidence channel, the Material Symbols pass, and the
incognito search face.**

### Fixed — MOVING A TAB TO A GROUP LEAVES NO GRID VOID
- A move-into-group changes the SPAN a position occupies (a loose span-1
  card becomes part of a full-width group row), and the layout pass after
  the data swap can lay the OLD span shapes onto the NEW positions — a
  void renders where the moved tab's slot was, exactly when a settle
  animation races the swap. Both `move_to_group` handlers (the card 3-dot
  menu and the group-sheet member menu) now run `reloadGridNoVoid()`:
  `stopScroll()` (the settle is the shape that keeps the stale layout
  alive) → `adapter.reload()` → the span-cache invalidation +
  `requestLayout()`. One API-name note: the report named
  `glm.invalidateSpanAssignments()` — a support-lib-era method that does
  NOT exist on androidx recyclerview 1.3.2 (the compiler proof); the
  public invalidation door on this dependency is
  `SpanSizeLookup.invalidateSpanIndexCache()` (+ the group-index twin),
  which is what ships.

### Fixed — THE GROUP SHEET KNOWS YOUR ACTIVE TAB; RIMS STOP EATING CONTENT
- The group sheet listed every member identically — the tab you were
  actually reading was indistinguishable from its sleeping siblings. The
  member that is `tm.active` now wears the accent rim and an accent
  title, exactly like a loose card in the switcher (rebind-safe: an
  inactive row returns to the plain text color).
- Every accent rim (loose-card selection, the v1.4.16 group-row rim, and
  the new sheet rim) used `setForeground()` at the view bounds, so the
  2dp stroke straddled the card edge and ate into the corner geometry.
  All three now ride an `InsetDrawable` with a 2dp inset: the outline is
  concentric with `bg_card`'s 16dp corners (rim radius 14), the ⋯/close
  buttons (4dp margins) stay clear, and the bottom strip keeps its own
  band.

### Fixed — VISUAL SEARCH SENDS A FAITHFUL IMAGE
- The "grey / broken image on Google visual search" report named the
  payload path. The upload JPEG quality rises 85 → 90 (the fidelity
  knee; the matcher reads fine structure), the fine scale is pinned to
  `createScaledBitmap`'s bilinear `filter=true` (nearest-sample aliasing
  is what smears text into grey blocks), and the multipart boundary stays
  strict-CRLF — all three legs of the report's diagnosis, pinned.

### Fixed — DARK MODE DOES NOT FLASH WHITE
- The report's proposed cure — `setForceDark(FORCE_DARK_ON)` +
  `setAlgorithmicDarkeningAllowed(true)` — is the exact pair this codebase
  retired in v1.3.81/v1.3.91: `setForceDark` is ignored outright for apps
  targeting API 33+ (Bolt targets 34), and the algorithmic darkener has
  no already-dark detection (it double-darkens sites with their own dark
  mode and inverts images — the documented field corpses). The white
  flash was real, though, and the honest legs are:
  - **The dark decision chain (probe → ask → invert) moved to
    `onPageCommitVisible`** — the earliest moment the new document exists
    with its render-blocking CSS parsed. A light site under the dark-web
    engine used to paint white from first paint until the last
    subresource landed; now the inversion lands at first paint.
  - **The finish-time pass is a guarded backstop**: a per-tab run token
    (`Tab.darkPassUrl`) records that the chain has spoken for this URL —
    an unguarded second run would probe the ALREADY-INVERTED page, read
    it as painted-dark, and clear Bolt's own inversion.
  - **The unpainted canvas is dark while the dark engine is on**: a new
    `Fx.webBgDark` (the skin dark base, independent of the app theme)
    colors the blank loading surface whenever the per-site/global dark
    decision is on for the tab's domain, and `onPageStarted` re-applies
    it for each new domain (a light-themed app with one dark-flagged site
    used to open that site on a white canvas).

### Fixed — AI TRANSLATE ERRORS LEAVE EVIDENCE
- "Provider error" is the 5xx bucket of `shortErr`, but the toast-safe
    message hid the exact HTTP shape. Every AI error path now mirrors the
  raw provider response to logcat (`Log.e("AiBridge", "Raw Response: …")`,
  capped at 2000 chars) — the only evidence that separates "model shut
  down" from "key invalid" from "quota" in a field report. The model
  chain itself was already current (gemini-3.5-flash-lite + fallbacks,
  verified against aistudio 2026-11-08); the missing piece was the
  evidence channel, not a newer model name.

### Changed — MATERIAL SYMBOLS ICONS
- The nine core glyphs (search, close, more, copy, paste, open-in-new /
  duplicate, camera, mic, the engine drop-down) are now Google's official
  **Material Symbols Rounded**, weight 400 (the 2px stroke at 24dp):
  consistent 24dp geometry (16dp for the drop-down), the published path
  data kept byte-identical inside a `translateY(960)` group (the SVG
  viewBox re-base), and `?attr/colorTint` fills so light/dark themes tint
  every glyph (the search widget applies its own explicit tint, as
  before).

### Added — SEARCH IN BOLT (INCOGNITO)
- The `PROCESS_TEXT` / `WEB_SEARCH` / `SEND` filters existed only on the
  `BrowserActivity` launcher aliases, so selecting text in an incognito
  tab and tapping Search routed the query into the MAIN process — the
  user landed in normal mode with the normal cookie jar. A new
  `SearchIncognito` activity-alias registers the same three actions
  against `IncognitoActivity` (which runs in `:incognito`): the OS now
  offers BOTH rows in the floating toolbar, and the "(Incognito)" row
  runs the query through the same `handleIntent` → `openQueryInNewTab`
  path, in the private process. The alias deliberately carries no
  `android:process` — process placement belongs to the target activity.

## [1.4.16] — 2026-11-18

**The reliability nine-pack: the 0KB/duplicate/.bin download law, the
mid-drag reload deferral, the group-sheet row width, the race-proof
incognito flag, the grouped-active highlight, the two-row omnibox, plus
the find-bar bleed-over, the omnibox dialog sweep, and the ghost sweep.**

### Fixed — FAILED SAVES LEAVE NO 0KB "NAME (1)" CORPSES
- `savePart` creates the destination (SAF `createDocument` /
  `MediaStore` insert) BEFORE copying the bytes; if the copy failed, the
  0KB document stayed behind, so every retry hit the duplicate-name wall
  and collected `name`, `name (1)`, `name (2)` corpses. A failed copy now
  deletes the created URI via `ContentResolver.delete()` (and the partial
  `File` on the plain-file paths) before falling through — a retry starts
  clean.

### Fixed — BLOB: DOWNLOADS RIDE THE JS BRIDGE
- `blob:` URLs die in `HttpURLConnection` (the bytes exist only inside
  the page that minted them) and the name guess degraded toward a UUID
  with a `.bin` fallback. `onDownloadStart` now routes `blob:` URLs
  straight through the v1.3.58 rescue chain (`BlobDownload.kt` — frame
  dance → fetch → own-img → hunts), which reads the blob JS-side as a
  Base64 data URL and hands it to the `@JavascriptInterface`
  `Bolt.gmSaveBlob` → `DownloadEngine.saveDataUrl`. `GM_download`
  (userscripts) went through the same hole and now routes through
  `enqueueDownload`'s scheme law (`data:` → `saveDataUrl`, `blob:` → the
  bridge, `http(s)` → the engine).

### Fixed — THE EXTENSION OBEYS THE LEARNED TYPE
- `refineFile`'s `ensureExt` only repaired a missing/`.bin`/`.part`
  extension; a URL-guessed name could keep a plainly WRONG one. When
  refinement actually LEARNS the type (the final response's real
  Content-Type, or magic-byte sniffing), the name's extension is now
  forced to agree with it — and any `.bin` fallback is overridden before
  the destination call.

### Fixed — NO GHOST TABS WHILE DRAGGING
- Any `adapter.reload()` firing while the switcher's drag was live (a
  dialog confirm racing the gesture, a tab-list mutation from an intent,
  a settle posted from an earlier drop) rebuilt the board mid-gesture and
  recycled the dragged ViewHolder under ItemTouchHelper's feet — the card
  went invisible-while-"there" or duplicated at its old slot.
  `reload()` now parks the request while `dragActive` and
  `DragMergeCallback.clearView` flushes it when the gesture ends
  (the drag itself still never reloads: `onMove` returns false, the
  reorder commits at drop time).

### Fixed — GROUP SHEET MENU DOCKS AT THE EDGE
- The sheet rows were created without LayoutParams, so RecyclerView
  defaulted them to wrap-content width and the text column's `weight=1`
  had no leftover space to divide — the 3-dot menu sat mid-row. Rows are
  now `MATCH_PARENT` × `WRAP_CONTENT`.

### Fixed — INCOGNITO IS SET BEFORE ANYTHING LOADS
- The process flag lived only in static blocks, whose class-load order
  is not guaranteed across the multi-process split: if `TabManager`
  loaded first, the "incognito" process booted in normal mode (normal
  tabs.json, normal chip state, taps routing to the normal browser).
  `IncognitoActivity.onCreate()` and `TabsIncognitoActivity.onCreate()`
  now set `TabManager.INCOGNITO = true` as their very first statement,
  before `super.onCreate()`.

### Fixed — THE FOLDER ROW OF THE TAB YOU ARE READING LIGHTS UP
- `applySelVisuals` only highlighted loose cards, so a grouped active
  tab was invisible in the switcher. A group row whose members include
  `tm.active` now gets the same accent rim (and a new bottom accent
  strip, `v_group_strip`, in both group layouts); `scrollToActive()`
  already resolves a member's group row (the v1.4.12 walk), so the
  highlight and the scroll finally agree.

### Changed — THE OMNIBOX IS TWO ROWS NOW
- The single row's icon congestion is structural history: Row 1 is the
  URL field proper (engine chip | EditText weight-1 | Clear), and Row 2
  (`omni_actions`) is a full-width action strip — Copy, Paste,
  Duplicate tab, Image Search, Voice Search — that shows only while the
  field holds text (hidden when empty). The v1.4.15 empty-state cache
  survives: only an EMPTY↔TYPING flip re-layouts anything, and a
  keystroke inside a state costs one array read.

### Fixed — FIND BAR, OMNIBOX SHEET, DRAG GHOST CLEAN UP AFTER THEMSELVES
- Switching tabs (switcher tap, notification, external intent) now hides
  the find-in-page bar, clears its query/count, and `clearMatches()`es
  the DEPARTING tab — the bar used to bleed onto the next page with the
  old page's matches. `onDestroy()` dismisses a showing `omniboxDialog`
  (a destroy mid-typing leaked the window). `onPause()`/`onStop()`
  remove a stranded `sdGhost` from `flRoot` (a mid-drag backgrounding
  used to leave the speed-dial lift ghost frozen over the home screen).

## [1.4.15] — 2026-11-18

**The responsiveness four-pack: the downloads event-listener law, the
tab-switcher stale-highlight fix, the omnibox state cache plus off-thread
suggestion build, and the handler sweep.**

### Fixed — DOWNLOAD PROGRESS NOW ARRIVES BY EVENT, NOT POLL
- `DownloadsActivity` implements `DownloadEngine.Listener` and correctly
  removed itself in `onDestroy()` — but never registered, so the UI was
  deaf to real-time events and leaned entirely on the 500 ms `refreshLoop`
  poll (progress up to half a second late; switching screens felt
  disconnected, laggy, or "reset"). `DownloadEngine.get(this).addListener(this)`
  now runs at the end of `onCreate()` and inside a new `onResume()` —
  `onDownloadsChanged()` (which was already implemented, posting a guarded
  `reload()` to the UI handler) finally fires. Registration is
  contains()-guarded (idempotent), so the double arm is free.

### Fixed — TAB SWITCHER RE-HIGHLIGHTS THE CURRENT TAB
- `TabsActivity.onResume()` only called `adapter.rebindIcons()` — a rebind
  of the rows the adapter ALREADY held, never a model rebuild. If the
  active tab moved while the switcher was covered (a notification or
  external intent routed through the browser, a satellite screen tapping
  a row), reopening the switcher outlined the OLD tab:
  `applySelVisuals()` reads live `tm.active` but nothing re-triggered it
  against a rebuilt model. `adapter.reload()` now follows the rebind —
  `rebuildItems()` + `notifyDataSetChanged()` + `afterReload()` — so the
  accent rim and bottom strip derive from the CURRENT active tab, and an
  active search filter survives the reload (rebuildItems re-applies it).

### Fixed — OMNIBOX TYPING LAG (STATE CACHE + OFF-THREAD BUILD)
- The v1.4.14 state-aware icon strip called `setVisibility()` on four
  views on every keystroke. Only an EMPTY↔TYPING transition ever changes
  what is on screen: the empty state is now cached in a final
  `Boolean[1]` holder (null = never applied, so the opening sync always
  runs) and a keystroke that does not flip the state costs one array
  read and zero layout invalidations.
- The suggestion pass (`ad.update()`) walked the bookmarks, speed-dial
  and 400-entry history JSON arrays synchronously inside
  `afterTextChanged` — per keystroke, on the main thread. The build now
  runs on a single process-wide daemon thread (`SUGGEST_BUILD`): every
  `update()` bumps a generation counter, the build carries its
  generation, and the apply (items swap + `notifyDataSetChanged()`)
  posts back through the activity's `ui` handler only if it is still the
  newest — stale builds can never reach the screen. The previous rows
  stay up until the fresh pass lands (no flash of emptiness mid-word),
  and the activity's existing `ui` cleanup kills any still-queued apply
  at destroy.

### Fixed — HANDLER MEMORY LEAKS ON DESTROY
- `sdWinH` (the speed-dial hold-to-lift timer) and `suggestH` (the
  omnibox network debounce) were created as fields but only `ui` was
  cleaned in `onDestroy()` — destroying the activity with callbacks
  pending (a held speed-dial item, a 300 ms suggest timer mid-pause)
  kept the whole dying Activity reachable from the handler queues.
  Both are now swept with `removeCallbacksAndMessages(null)` — placed
  above the incognito early-return so both process paths clean up.

## [1.4.14] — 2026-11-18

**The interaction six-pack: the group-sheet drag restore, the external-search
new-tab law, the omnibox icon diet, the incognito return route, the
visual-search OOM ladder, and the bookmarks/history search bars.**

### Fixed — TAB GROUP DRAG-AND-DROP IN THE BOTTOM SHEET
- v1.4.12 replaced the inline group strip's hold-drag with long-press
  "Move up / Move down" buttons when the folder became a bottom sheet. The
  gesture is back: the sheet's member list is a **RecyclerView** with an
  **ItemTouchHelper.SimpleCallback** (`makeMovementFlags(UP | DOWN, 0)` —
  drag only, no swipe). Long-press a row and drag; every crossed
  neighbour commits through `tm.reorderGroupMember` (the same
  member-indexed backend the buttons drove) and `notifyItemMoved` glides
  the swap. The sheet needed a new door for this:
  `Ui.showSheetView()` hosts a RecyclerView as the sheet root without the
  ScrollView wrapper (nested scrolling would steal the touch stream and
  the drag could never run). The Move up/Move down buttons are removed.

### Fixed — EXTERNAL SEARCHES OPEN IN A NEW TAB
- Selecting text in another app and tapping "Search in Bolt"
  (`ACTION_PROCESS_TEXT` / `ACTION_WEB_SEARCH` / `ACTION_SEND`) used to
  land via `loadInTab()`, which loads into the **active** tab — the search
  overwrote the page the user was reading. All three externally-originated
  entries now route through **`openQueryInNewTab()`**: the query resolves
  through the same resolver the omnibox uses (URL-vs-query, engine, bangs,
  tracker cleaner — extracted into `resolveInput()`, one resolution shared
  by both doors) and opens in a **new tab**; the user's current tab is
  untouched. It runs inline exactly like the VIEW branch, so a cold-start
  search lands as one tab (no spare home tab beside it).

### Fixed — OMNIBOX CONGESTION WHILE TYPING
- The engine chip (left) and the camera icon (right) added in v1.4.12 were
  permanent fixtures — over 230 dp of chrome hugging the field, leaving
  very little width to see the typed URL. The icons are now **state-aware
  in the TextWatcher**, Chrome-style: when the field is **empty** it shows
  the engine chip + camera + paste (+ mic); the moment the user **types**,
  the camera, paste and copy icons hide and only the **clear** button (+
  mic) remains, giving the typed text the full field width; clearing the
  field restores the full set. The engine chip stays in both states (it is
  the engine indicator for the live query and the pick-re-runs door); the
  copy button is retired from the omnibox (the field's own text-selection
  handles cover it).

### Fixed — INCOGNITO INTENT LEAK
- Opening a link in a "new tab" from inside an Incognito tab sometimes
  spawned in the normal `BrowserActivity`. The root cause: the satellite
  screens (bookmarks, history, saved pages — and Settings, which chains to
  all three) run in the **main process** even when launched from the
  incognito browser, and their row taps fired plain
  `BrowserActivity.class` VIEW intents — the page then loaded with the
  normal cookie jar and normal history. The fix is a **stamp-and-return
  contract**: `Ui.launchSatellite()` stamps every satellite launch with
  `EXTRA_FROM_INCOGNITO` when the launcher is the incognito process
  (`TabManager.INCOGNITO`) or a stamped satellite itself (Settings →
  Bookmarks chains keep the stamp), and `Ui.browserOpenIntent()` routes a
  stamped satellite's URL opens to **IncognitoActivity** — the same
  ACTION_VIEW contract in the `:incognito` process — never
  `BrowserActivity.class`. Inside a browser process, new tabs are created
  in the current process's `TabManager` directly (the process law, now
  documented at `openNewTab`); the only cross-process hop left is the
  tabs view's explicit "switch to normal" chip.

### Fixed — VISUAL SEARCH OOM CRASH
- Picking a high-resolution photo (e.g. 12 MP, ~48 MB as full ARGB) for
  visual search could die with an `OutOfMemoryError` on memory-constrained
  devices. The decode is now **bounds-first** (`inJustDecodeBounds`), picks
  an `inSampleSize` that lands the long edge at a **1280 px cap** (halving
  while the longest halved dimension still clears the cap — extreme aspect
  ratios are the memory bombs, so the cap rides the long edge), then
  fine-scales and compresses to JPEG for the multipart POST. An
  **OutOfMemoryError retry ladder** doubles the sample size (up to 3
  retries): memory pressure degrades the resolution, it never crashes the
  process.

### Added — HISTORY & BOOKMARKS SEARCH
- There was no way to search through saved bookmarks or browsing history.
- Both screens (and every `BaseListActivity` that opts in) gain a **search
  bar** under the header — the same `bg_urlbar` skin the omnibox field
  wears, with a leading search glyph and a clear (X) affordance. A
  **TextWatcher filters the RecyclerView in real time by URL or title**
  (case-insensitive substring over the row's title and sub — history's sub
  carries the date and URL, bookmarks' the URL; folder rows match by name
  too). The filter runs over the screen's last-built model in place — no
  re-read of prefs per keystroke, so a long history stays keystroke-fast —
  and clearing the box restores the full list. A query that matches
  nothing shows a dedicated "No matches for …" state.

## [1.4.13] — 2026-11-18

**The performance five-pack: the text-selection toolbar label, the omnibox
network debounce, the favicon LRU eviction, the WebView pre-warm pool, and
the If-Range resume contract.**

### Fixed — "SEARCH IN BOLT" IN THE TEXT-SELECTION TOOLBAR
- The `ACTION_PROCESS_TEXT` filters shipped in v1.4.12 carry the
  `text/plain` MIME type (without it Android's
  `setTypeAndNormalize("text/plain")` query never resolves an activity, and
  the app is invisible in the floating toolbar). This release makes the
  toolbar entry legible as an ACTION, not just an app name: every one of the
  five launcher aliases' PROCESS_TEXT filters now carries **its own label —
  "Search in Bolt"** — because the toolbar renders the resolved filter's
  label, which otherwise falls back to the bare app label.

### Fixed — THE OMNIBOX NETWORK DEBOUNCE (one request per typing pause, not per keystroke)
- The omnibox's engine suggestion pass is born debounced: a keystroke parks
  a **300 ms Handler/Runnable timer** (`removeCallbacks` before
  `postDelayed` — a new keystroke kills the pending request instead of
  stacking behind it), so only a typing **pause** becomes a network request.
  Local rows (open tabs, bookmarks, shortcuts, history, instant answers)
  still re-filter on every keystroke — they are memory-fast; only the
  network is throttled. The rows arrive from the selected engine's
  OpenSearch-style `osjson` endpoint (Google / DuckDuckGo / Bing / Brave /
  Startpage), are capped at five, carry a stale-response guard (an answer
  only applies while the field still holds the exact query it answers), and
  a rising generation counter drops late answers outright. **Never in
  incognito**, and a Settings toggle ("Search suggestions (from the
  engine)") is the privacy escape hatch.

### Fixed — THE FAVICON CACHE LRU EVICTION (infinite growth, capped)
- The favicon cache downloaded and saved icons but **never evicted old
  ones**. The disk store is now bounded by two caps — **1000 icon files or
  50 MB, whichever trips first** — and the sweep runs on the store
  executor after every save. Eviction order is **least-recently-used by
  the file's lastModified**, and lastModified is a genuine USE stamp:
  serving an icon from disk refreshes it (once per host per process),
  so "oldest" means "least recently served", not "least recently
  captured". Deleted victims also leave the memory caches, and the
  just-written file is never a candidate.

### Fixed — THE WEBVIEW PRE-WARM POOL (the ~300 ms new-tab stutter)
- Constructing a WebView from scratch blocks the CPU for ~300 ms (provider
  load + AwContents) — the micro-stutter when opening a new tab. BoltApp
  now **pre-warms one spare, never-attached WebView 250 ms after process
  start** (posted, so the cold start's first frame never pays for it), and
  `Tab.buildWeb` takes the spare when it needs one, falling back to plain
  construction when the pool is empty. The spare is built over a
  `MutableContextWrapper` carrying the app context; `take()` swaps the
  base to the real owner's context before handoff, so the WebView behaves
  exactly like one constructed with it (HTML `<select multiple>`'s dialog
  needs a themed, token-bearing context — the swap is what keeps that
  safe).

### Fixed — THE IF-RANGE RESUME CONTRACT (no more corrupt splices)
- Interrupted downloads already resume from the exact byte in the `.part`
  file (`Range: bytes=N-`, 206 append / 200 restart / 416 fresh-start).
  This release closes the classic hole in that design: a **server-side
  file change** between the interruption and the resume would still answer
  206, silently appending a DIFFERENT body onto the old part. The engine
  now captures every final response's **ETag**, persists it with the
  queue, and sends it back as **`If-Range`** on the resume request — an
  unchanged file answers 206 (append, as before), a changed file answers
  200 (the existing restart path replaces the part cleanly). No ETag from
  the server → the old behavior, server's discretion.

## [1.4.12] — 2026-11-18

**The usability five-pack: the AI translate payload diet, the group folder
sheet, the incognito tabs process flag, the system search entries, and the
omnibox engine/image doors.**

### Fixed — THE AI TRANSLATE PAYLOAD (the 2–4 MB screenshot nobody could upload)
- `ScreenTranslate.encodeAi` scaled screenshots to 2200 px at 90% JPEG
  quality — a modern phone screenshot base64s to **2–4 MB** at that size,
  which mobile networks time out on and strict API gateways reject with
  HTTP 400/413 before the request ever reaches the model. The AI path now
  downscales to **1280 px long edge at 75% quality (~200 KB base64)** —
  still comfortably legible for vision-model text extraction (a 1080p
  screenshot's text survives at 1:1 scale) and near-instant to upload even
  on a weak cell link. The OCR path (1600 px q90) is untouched.

### Fixed — THE GROUP FOLDER SHEET (the squished inline strip) + switcher context
- Opening a tab group used to unroll its members into a horizontal
  mini-strip inside the grid row — past two tabs the strip **squished them
  unreadable**. A group now opens a **vertically scrollable bottom sheet**:
  one full-width row per tab with thumbnail, title, domain, and the visible
  ⋯ member menu (Edit tab / Remove from group / Move to group / Close
  tab). Tap a row to switch to that tab; the inline strip never renders
  again. The collapsed/expanded toggle state and its persistence are
  retired with it (the chevron now always reads "tap opens the folder").
- The switcher's auto-scroll never found the active tab when it lived
  **inside a group** (a member never renders as its own grid item, so the
  walk fell through to a scroll-to-end and the user's current context
  started off-screen). The walk now resolves the active member's GROUP row
  by `groupId` and scrolls there with `scrollToPositionWithOffset`; loose
  tabs keep matching directly (search-filtered flat lists included).

### Fixed — THE INCOGNITO TABS PROCESS FLAG (a normal tabs view in a private process)
- `TabsIncognitoActivity` runs in the `:incognito` process but never set
  `TabManager.INCOGNITO = true` the way its browser sibling
  `IncognitoActivity` does — so the incognito tabs view booted in NORMAL
  mode: it listed the normal `tabs.json`, rendered the normal chip state,
  and its tab taps routed to the normal `BrowserActivity`. The static
  initializer block now runs in the incognito process exactly as in
  `IncognitoActivity`.

### Added — THE SYSTEM SEARCH ENTRIES (Bolt joins the text-selection toolbar)
- Bolt was missing from Android's text-selection floating toolbar and the
  system "Web search" surfaces. Every launcher alias now carries
  `ACTION_WEB_SEARCH`, `ACTION_SEND` (text/plain) and `ACTION_PROCESS_TEXT`
  filters, and `BrowserActivity.handleIntent()` extracts the query from
  `SearchManager.QUERY`, `Intent.EXTRA_TEXT` or
  `Intent.EXTRA_PROCESS_TEXT` respectively and routes it through
  `loadInTab()` — the same URL-vs-query resolution, engine choice and
  tracker stripping as typing into the omnibox.

### Added — THE OMNIBOX ENGINE + IMAGE DOORS (search the way you meant to)
- The omnibox's left glyph is now an **engine chip with a dropdown arrow**:
  tapping it lists the built-in engines (Google, DuckDuckGo, Bing, Brave,
  Startpage); picking one switches the default engine AND — when the field
  holds a live query rather than the prefilled URL — re-runs that query
  through the newly picked engine immediately.
- A **camera/image-search icon** now sits at the omnibox's right edge.
  Picking a picture (camera with a runtime permission flow, or gallery)
  uploads it to the selected engine's visual search: Google and Startpage
  via Google's search-by-image endpoint, Bing directly, and DuckDuckGo and
  Brave via Bing (they publish no upload endpoint of their own — DDG's
  image index is Bing-sourced; a toast says which route ran). On upload
  failure the engine's manual image-search page opens instead.

## [1.4.11] — 2026-11-18

**The stability four-pack: one memory leak, one crash family, one adblock
blindspot, one silent-failure class — the first full release to exercise the
dual-publish pipeline end-to-end (both repos carry the APK + these notes).**

### Fixed — THE OCR ROTATION LEAK (the singleton that pinned a dead screen)
- `ScreenTranslate`'s singleton held an `OcrHost` whose headless OCR WebView
  was built with the **Activity** context (`WebView(a)`) and attached to the
  activity's decor view. The singleton outlives every activity, so each
  screen rotation leaked the entire destroyed Activity — view hierarchy,
  bitmaps and all — for as long as the translate overlay stayed armed. The
  WebView is now built with `a.applicationContext` and never attached (a
  headless WebView loads its page and runs JS/wasm without a window), and
  every other context use inside the host (asset read, model files, diag
  log) rides the same app context so no Activity reference survives
  construction.

### Fixed — THE UI-SIDE TAB-LIST RACES (the v1.4.9 law, finished)
- v1.4.9 made `TabManager.tabs` a `Collections.synchronizedList` and put
  every iteration in TabManager and the background readers under the list's
  monitor — but `Collections.synchronizedList`'s documented contract demands
  manual `synchronized(list)` for iteration, and dozens of raw
  `for (Tab t : tm.tabs)` loops in **BrowserActivity** and **TabsActivity**
  were still unprotected: a background-service mutation (the media scans,
  the phoenix resurrection) mid-iteration meant
  `ConcurrentModificationException` and a crash on rotate/switch/pause.
  Every remaining iteration — 22 enhanced-for walks and 8 indexed
  size()+get() walks — now holds the monitor, and the three loops that
  destroy WebViews inside (`destroyStaleWebViews`, `wipeIncognito`, the
  tab-sleep path of `onTrimMemory`) follow the snapshot-first law: snapshot
  under the monitor, tear down outside it, exactly like TabManager's own
  phoenix.

### Fixed — THE ADBLOCK EXTENSION-LESS BLINDSPOT (the Accept-header signal)
- `BrowserActivity.inferType` guessed every resource's type purely from the
  URL path extension, so an extension-less endpoint typed as OTHER could
  never match a `$script` rule — the modern tracker shape (`/collect`,
  `/beacon`, extension-less API tag loaders) walked straight past the
  script rules. The fall-through now inspects the request's `Accept`
  header: a fetch asking for `image/*` first is an IMAGE, `text/css` first
  a STYLESHEET, and a JavaScript MIME first a SCRIPT. Only the FIRST
  (preferred) MIME is trusted — jQuery-style XHRs list
  `text/javascript` second and must not be typed SCRIPT (that would
  over-block JSON APIs) — and an explicit file extension in the path still
  wins, keeping every shipped extension decision byte-identical.

### Fixed — THE COOKIE-JAR LOCK CONTENTION (the silently skipped scan)
- `CookiePartitionScan` opens the `Cookies` SQLite file read-only straight
  from disk — while Chromium's cookie backend may be mid-write, and a
  `SQLiteDatabaseLockedException` was swallowed as a generic best-effort
  failure. The scan therefore silently returned nothing exactly when the
  user was actively browsing (the busiest the jar ever gets), and the
  per-site wipe degraded to the static host approximation precisely when
  the on-disk partition data mattered most. A lock is transient, not
  fatal: each profile jar is now retried with a bounded backoff
  (60/120/240 ms — inside the wipe flow's head-start window) before
  degrading; every other failure (schema drift, unreadable WAL) stays
  one-shot best-effort. The scan remains strictly read-only.

### Gates
- **test_v1411.py** — the new gate: the version bump (125/1.4.11), the
  About changelog label law, the four fixes (the app-context OCR host with
  no Activity reference and no decor attach, zero unprotected `tm.tabs`
  iterations in the two UI files, the Accept-header fall-through with the
  first-MIME-only law and the extension-priority law, the lock retry
  backoff with the read-only law intact).
- All earlier gates run unmodified and green — the four fixes change no
  pinned behaviour of any prior release.

## [1.4.10] — 2026-11-17

**The v1.4.9 hardening four-pack ships as v1.4.10: security, memory and
concurrency work done in the second v1.4.9 session — published under a new
version because v1.4.9 (code 123) was already live, and Android only
installs updates whose versionCode rises.** The v1.4.10 report: "You built
the APK as v1.4.9 (code 123), but v1.4.9 was already released yesterday
from the private repo. Android will reject this update."

### Fixed — THE ADBLOCK MEMORY DIET (a ~130 MB corpse)
- The old filter engine compiled a `java.util.regex.Pattern` for every one
  of ~132k network rules; the compiled Patterns alone cost ~130 MB of heap.
  103,087 network rules were measured on the bundled EasyList/EasyPrivacy:
  90,534 `||host^rest`, 6,880 `||host/path` and 3,964 generic literals are
  now plain strings, leaving 1,709 rules (1.7%) that genuinely need regex.
  Generic rules are found by one Aho-Corasick scan per request
  (`adblock/AhoCorasick.java`, pure Java, compact CSR arrays), which also
  replaced the old enumerate-all-substrings token probe. Equivalence is
  proven by the release gate over 1,013,884 crafted rule-by-rule checks;
  the JVM heap proof measured 244 MB -> 49 MB for the same lists.

### Fixed — THE CLOSED-TAB LEAK (the write-only WebView registry)
- `UserscriptEngine` kept a static `liveWebs` map of every WebView ever
  attached — write-only dead code that pinned each closed tab's Activity
  context against garbage collection. Deleted outright, with its call site.

### Fixed — THE TAB-LIST RACE (the ConcurrentModificationException corpse)
- `TabManager.tabs` is now a `Collections.synchronizedList` and every
  iteration holds its monitor — in TabManager itself and in the background
  readers (`MediaControlService` media scans + phoenix resurrection,
  `Workspaces.liveTabs`, `TabSleep`). Long WebView teardowns happen outside
  the monitor (snapshot first, then destroy).

### Security — SIGNING-KEY PASSWORD ROTATION
- The release keystore's password was exposed in private git history. It
  was rotated to a random 32-character secret (kept offline by the
  maintainer only). The certificate is UNCHANGED — same key pair, verified
  against the fingerprint pinned in `scripts/release.sh` — so updates
  install in place as always.

### Changed — THE RELEASE PIPELINE (this repository)
- Source and releases now live on two public repositories: tags on
  [bolt-browser](https://github.com/arfan1210/bolt-browser), installable
  APKs + these notes on
  [bolt-browser-releases](https://github.com/arfan1210/bolt-browser-releases).
  `scripts/release.sh` publishes directly there (no private-repo mirror
  hop) and refuses to publish any version that does not compare greater
  than the live `/releases/latest` — the same-version collision of this
  release can never recur silently.

### Gates
- **test_v1410.py** — the new gate: the version-bump law (124/1.4.10), the
  About changelog label law (the newest line names this release's fixes;
  the 1.4.9 line is trimmed back to what v1.4.9 actually shipped), the
  pipeline law (release.sh targets the two public repos, carries the
  live-version guard, no dead private-repo references), and the agreement
  law (the publisher and the in-app update checker read the same repo).
- The v1.4.9 hardening is pinned by **test_v149_perf.py** (the pattern
  diet's equivalence + memory probes, the leak deletion, the thread law).
- All 28 prior version gates re-pinned 124/1.4.10 (the standing sweep;
  v1389's captured-group pin form included), and the gates that hardcoded
  the dead private checkout's path now read the public source checkout.
