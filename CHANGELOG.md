# Changelog

Notes for releases published from this repository. Versions before v1.4.10
were released from the maintainer's earlier private repository — their full
notes live on the [releases page](https://github.com/arfan1210/bolt-browser-releases/releases).

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
