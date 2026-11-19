# Bolt Browser

A fast, private, Android-first WebView browser. No trackers, no bloat — the
whole app is a **~2.6 MB APK**.

- **Package:** `com.bolt.browser`
- **Requires:** Android 7.0+ (API 24)
- **Engine:** the system WebView (the app itself contains no Chromium fork —
  that is the point: light, and it inherits the WebView you already trust)

## Highlights

- **Tabs that behave** — tab groups (folders) with drag-to-arrange,
  workspaces, sleep-other-tabs, per-tab swipe actions
- **Speed-dial home screen** — shortcut grid with folders, drag to rearrange,
  per-item edit menus
- **Built-in content blocking** — EasyList + EasyPrivacy filter lists
  (see [THIRDPARTY.md](THIRDPARTY.md)), per-site allowlist
- **Userscripts** — a Tampermonkey-compatible engine (`GM_*` API, `@run-at`,
  `@match`, …) with a script manager
- **Passkeys in WebView** — a WebAuthn compatibility layer so passkeys work
  on sites that expect Chrome
- **Built-in authenticator** — TOTP 2FA with QR import/export (Google
  Authenticator transfer format)
- **Password vault** — encrypted, per-site, with a save/fill chip
- **Media** — background audio playback with keep-alive, picture-in-picture,
  silence-skipper
- **Translate** — page translation plus on-device screen OCR translation
  (tesseract.js core downloaded on demand — nothing bundled)
- **Five themes × light & dark** — Classic, Aura (glass), Mono, Origins,
  Pulse; per-theme accents, corner radii and icon weights
- **Small extras** — QR share/scan, page outline, read-aloud, night screen,
  site zoom, desktop-site per host, Bolt Dash (an easter egg game — tap the
  version row in About seven times)

No accounts, no telemetry, no analytics, no push services. The app never
talks to any server except the sites you visit, the filter-list update
source, and the GitHub releases page when you tap "check for updates".

## Install

Grab the latest signed APK from the
[releases page](https://github.com/arfan1210/bolt-browser-releases/releases)
and install it (enable "install unknown apps" for your file manager when
Android asks). Updates install in place — Android only allows this because
every release is signed with the same key; only install APKs from that
releases page. Since v1.4.10 every release (APK + changelog) is published
on BOTH repos' releases pages — this one and
[bolt-browser](https://github.com/arfan1210/bolt-browser/releases) — but
the releases repo above stays the canonical download endpoint (it is what
the in-app update checker reads).

## Build from source

Requirements: JDK 17+ (21 tested), Android SDK 34, Gradle 8.9.

```
gradle assembleDebug
```

That is all — a debug build needs **no signing setup of any kind** (it uses
the standard Android debug key; a fresh clone builds with zero secret files
present). Output: `app/build/outputs/apk/debug/app-debug.apk`.

For a **release** build you need your own keystore; credentials are read
from a gitignored `local.properties` (template in `app/build.gradle`) or
`BOLT_STORE_FILE` / `BOLT_STORE_PASSWORD` / `BOLT_KEY_ALIAS` /
`BOLT_KEY_PASSWORD` environment variables. Without them `assembleRelease`
produces an unsigned APK on purpose. The project's official releases are
built and signed by the maintainer; the signing key is never in this repo.

## Project layout

```
app/src/main/java/com/bolt/browser/   single module, Java + Kotlin
app/src/main/java/com/bolt/browser/adblock/   FilterEngine + AhoCorasick (v1.4.9)
app/src/main/assets/filters/          EasyList / EasyPrivacy snapshots
app/src/main/assets/                  WebAuthn compat layer, OCR host page
scripts/                              test gates + release tooling
```

The `scripts/test_v*.py` files are version gates: each release must pass its
gate plus all earlier ones (`scripts/release.sh` runs them in order before
building). They are plain Python + static analysis — no device required
except where a gate spawns the emulator probes. Several gates compile and
RUN transcribed Java probes (the B sections) — they need `javac` on PATH
(see `scripts/setup_toolchain.sh`).

## Development notes (maintainer log)

This section is the running context for anyone (or any AI session) picking
the project up cold. Read top to bottom; the newest learnings are last.

**The workflow law.** Every behavioural change ships with its own gate
(`scripts/test_v<version>.py`) that pins it forever; older gates may be
re-pointed only with a dated note when a later change legitimately moves
their target (see the `v1.4.9 re-point` in `test_v141.py`). Before any
release: all gates green, then `release.sh <ver>` builds, checks the APK
size law (hard fail over 6,000,000 B), checks the signing cert against the
fingerprint baked into the script, and publishes. The signing key NEVER
lives in this repo — release credentials come from a gitignored
`local.properties` or `BOLT_*` env vars (template in `app/build.gradle`).

**v1.4.9 hardening four-pack** (the second v1.4.9 session):

1. *Adblock memory — the pattern diet.* The old engine compiled a
   `java.util.regex.Pattern` for every one of ~132k network rules
   (~130 MB of heap on device). Measured on the bundled lists:
   103,087 network rules — 90,534 `||host^rest`, 6,880 `||host/path`,
   3,964 generic literals — are now plain strings (no Pattern), leaving
   1,709 rules (1.7%) that genuinely need regex. Generic rules are found
   by one Aho-Corasick scan per request (`adblock/AhoCorasick.java`,
   pure-Java, compact CSR arrays) which also replaced the old
   enumerate-all-substrings token probe (and its per-request HashSet).
   Equivalence is proven by `scripts/test_v149_perf.py`: the old regex
   engine and the new literal engine decide identically over 1,013,884
   crafted rule-by-rule checks and a corpus of realistic URLs, and the
   JVM heap proof measured 244 MB -> 49 MB for the same lists.
   Two semantic subtleties worth remembering: EasyList `^` is ONE
   separator character, not a regex; and `||host` rules may only match
   at the URL's real host (label boundaries — userinfo URLs and no-dot
   lookalikes were never matched by the old prefix and must stay
   unmatched).
2. *Userscript leak.* `UserscriptEngine` kept a static `liveWebs` map of
   every WebView ever attached — write-only dead code (delivery always
   went through `JsBridge.attached`), so it only pinned each closed tab's
   Activity context against GC. Deleted outright, with its call site.
3. *Tab list thread law.* `TabManager.tabs` is now a
   `Collections.synchronizedList` and EVERY iteration holds its monitor —
   in TabManager itself (22 sites) and in the background readers
   (`MediaControlService` media scans + phoenix resurrection,
   `Workspaces.liveTabs`, `TabSleep`). Long WebView teardowns happen
   OUTSIDE the monitor (snapshot first, then destroy).
4. *Signing-key rotation.* The release keystore's password was exposed
   in private history; it was rotated (`keytool -storepasswd`; PKCS12
   keys ride the store password — `-keypasswd` is not applicable) to a
   random 32-char secret kept in the maintainer's workspace + offline
   backup only. The certificate is unchanged (same key pair), verified
   by building a release APK and matching `apksigner` output against the
   fingerprint in `release.sh`.

**v1.4.10 — the version-collision corpse + the pipeline law** (the third
session of this relay, 2026-11-17):

1. *The collision corpse.* The v1.4.9 hardening was built and gated as a
   second v1.4.9 (code 123) — but v1.4.9/123 was ALREADY live on the
   releases repo, published from the old private repo before the
   hardening existed (public v1.4.9 APK: 2,744,241 B, pre-hardening;
   the hardening build: 2,746,825 B). Android only installs updates
   whose versionCode RISES, so a same-version rebuild could never
   reach a single device. The hardening therefore ships as **v1.4.10 /
   code 124**. `release.sh` now carries THE LIVE-VERSION GUARD: it reads
   the releases repo's `/releases/latest` and refuses to publish any
   version that does not compare greater — pinned by `test_v1410.py`.
2. *The pipeline law.* The private repo is dead (its checkout is an
   empty dir). Everything lives on the two public repos now: the tag +
   main on `bolt-browser`, the GitHub release + APK + changelog body on
   `bolt-browser-releases` — the SAME endpoint the in-app update checker
   reads, so publisher and checker cannot drift apart. `release.sh` was
   retargeted end-to-end (authenticated push URL, direct release create,
   APK-only asset per the releases repo's convention, anonymous-download
   live verification at the end); the dead mirror tooling
   (`mirror_release.sh`, the workflow txt, `refresh_mirror_secret.py`)
   is deleted; `ensure_releases_repo.sh` lost its private-repo section.
   `CHANGELOG.md` now exists in this repo — release bodies are extracted
   from it (the About changelog stays the one-line-per-version summary).
3. *The standing sweep.* All 28 prior version gates re-pinned
   124/1.4.10 with dated notes (including v1389's captured-group pin
   form; v1390's digit-class pin already accepts 124). The gates that
   HARDCODED the dead private checkout's path (`test_sitewipe`,
   `test_reloadgate`, `test_notifsync`, `test_fs`, `verify_bridge`) now
   read the public checkout — a fresh session must know: **the gates
   read files by absolute path, and the source of truth is
   `/home/z/my-project/bolt-browser`**.
4. *The honesty trim.* The About changelog's 1.4.9 line claimed the
   hardening ("Under the hood: …") which v1.4.9 never contained; it is
   trimmed back to what v1.4.9 actually shipped, and the hardening text
   lives on the 1.4.10 line (the label law: what the user reads is what
   shipped).
5. *Toolchain note.* `release.sh` now exports
   `GRADLE_USER_HOME=$BASE/.gradle-home` itself — a fresh `~/.gradle`
   would re-resolve every dependency and trip the metadata warnings.

**v1.4.10 pipeline law v2 — the dual-publish** (the fourth session of
this relay, 2026-11-18):

1. *The report.* "The pipeline currently only uploads the compiled APK to
   the release repo. The source repo only receives a Git tag, but no
   GitHub Release with the APK attached." True: bolt-browser's releases
   page was empty — a bare tag auto-generates only "Source code" zips.
   The APK + changelog body now publish to BOTH repos simultaneously.
2. *The law.* `release.sh` carries THE DUAL-PUBLISH LAW: `publish_release()`
   runs against both `$SRC_API` (bolt-browser) and `$API`
   (bolt-browser-releases). Order matters: SOURCE first, RELEASES last —
   the releases repo is the endpoint the in-app update checker reads, so
   it goes live only after the source side is already up; a mid-flight
   failure leaves the checker-visible endpoint untouched. Each repo gets
   its own download-back verify, and step 10 anonymously downloads BOTH
   repos' assets.
3. *The repair mode.* `release.sh --republish <ver>` backfills an
   ALREADY-LIVE version onto both repos: no build, no gates, no
   tag/main movement; the staged APK (`download/Bolt-v<ver>.apk`) must be
   md5-identical to the live releases asset (the pin download rides the
   authenticated asset API — see the CDN note below). Used today to
   backfill v1.4.10 onto bolt-browser: release id 448965075, marked
   Latest, full changelog body, APK sha256
   cb9b83c859af675a6024fd545f9b16d346df29a8ea5bfcc2c030d9daea5a3ffe.
   The releases-repo asset already matched by its `digest` field — no
   re-upload, no service interruption (the digest check keeps the
   checker-visible endpoint continuously served).
4. *Two CDN learnings.* The anonymous asset CDN 403s transiently on rate
   limits — retry, don't fail (step 10 retries 3x). The anonymous REST
   API limit (60/h, shared per IP) is exhausted in this sandbox almost
   immediately: metadata checks AUTHENTICATE, only the ASSET DOWNLOAD
   itself is the anonymous release-health check.
5. *Gate.* `test_v1410.py` grew section C2 (dated re-point): SRC_API
   pinned, both `publish_release` calls pinned, the SOURCE-first order
   pinned, the republish mode pinned, the dual anonymous verification
   pinned. Suite after the change: 65/65 green.

**v1.4.11 — the stability four-pack** (the fifth session of this relay,
2026-11-18):

1. *The OCR rotation leak.* `ScreenTranslate`'s singleton held an `OcrHost`
   whose headless OCR WebView was built with the ACTIVITY context
   (`WebView(a)`) and attached to the activity's decor view — the singleton
   outlives every activity, so each rotation leaked the whole destroyed
   Activity. The WebView now rides `a.applicationContext` (read once at
   construction, never stored), is never attached (a headless WebView runs
   its page + JS/wasm unattached), and every other context use inside the
   host (asset read, model files, diag) rides the same appCtx. Learning:
   a singleton that touches a Context must derive appCtx on day one — an
   Activity context inside a process-lifetime object is a leak by
   construction.
2. *The thread law, round 2.* v1.4.9 synchronized `TabManager.tabs` and the
   background readers, but 30 raw `tm.tabs` iterations remained in the UI
   files (22 enhanced-for + 8 indexed size()+get() walks in
   BrowserActivity/TabsActivity) — `Collections.synchronizedList` demands
   manual `synchronized(list)` for iteration, so each was a
   ConcurrentModificationException waiting for a background mutation. All
   now hold the monitor; the three loops that destroy WebViews inside
   (`destroyStaleWebViews`, `wipeIncognito`, onTrimMemory's sleep walk)
   follow the snapshot-first law (snapshot under the monitor, teardown
   outside — the phoenix pattern). The audit is a GATE now: test_v1411
   embeds a bracket-depth walker that fails on any unprotected iteration.
3. *The adblock extension-less blindspot.* `inferType` guessed resource
   types purely by URL extension, so extension-less tracker endpoints
   (`/collect`, `/beacon`) typed OTHER and bypassed `$script` rules. The
   fall-through now reads the request's `Accept` header, typed by the FIRST
   preferred MIME only — jQuery XHRs list text/javascript SECOND and stay
   OTHER (over-blocking JSON APIs would break sites); an explicit extension
   still wins, keeping every shipped decision byte-identical. Pinned by a
   compiled+run probe of the extracted method (the parse-gate law).
4. *The cookie-jar lock contention.* `CookiePartitionScan` opened the
   Cookies SQLite file read-only while Chromium's cookie backend could be
   mid-write; a lock was swallowed as generic best-effort, so the scan
   silently returned nothing exactly when the user was actively browsing.
   Locks are transient: each jar now retries with a bounded backoff
   (60/120/240 ms — inside the wipe flow's head-start window); the
   read-only law (OPEN_READONLY, rawQuery only) is untouched.
5. *The standing sweep, v1.4.11 edition.* All 28 older version gates
   re-pinned 125/1.4.11 with dated notes; test_v1410 got surgical edits
   (its About-changelog checks shifted an index: the 1.4.10 four-pack line
   slid to entries[1], the trimmed 1.4.9 line to entries[2]). v1390's
   digit-class pin already accepted 125. Suite: 66/66 green (was 65; the
   new gate adds one command).
6. *A Java comment corpse worth remembering.* The first build died with
   "unclosed string literal" at a JAVADOC line — the comment quoted the
   Accept glob `*/*`, and that `*/` sequence CLOSES a Java block comment.
   Never write `*/*` inside a Java block comment (Kotlin block comments
   nest, Java's do not); say "star-slash-star" if the glob itself matters.

**v1.4.12 — the usability five-pack** (the sixth session of this relay,
2026-11-18):

1. *The AI translate payload diet.* `encodeAi` sent 2200 px q90 screenshots
   (2–4 MB base64) — mobile networks timed out and strict gateways answered
   400/413 before the request landed. Now 1280 px q75 (~200 KB), still
   legible for vision-model text extraction; the OCR encoder (1600/90) is
   untouched. Learning: v1.3.75's "more pixels" fix conflated the AI
   resolution with the OCR FALLBACK's accuracy complaint — the report was
   about the fallback, and the resolution bump only bought upload failures.
   When a fix and a symptom arrive together, reread which one the field
   report actually named.
2. *The group folder sheet.* The v1.3.9 inline mini-strip squished groups
   past two tabs. A group header now opens a vertically scrollable bottom
   sheet (`openGroupSheet`, riding `Ui.showSheet` — handle, rounded top,
   night veil) with one full-width row per member: favicon thumbnail,
   title, domain, the ⋯ member menu, tap-to-switch. The collapsed flag and
   its persistence retired with the strip; `wireMiniDrag`'s hold-drag died
   with it too, so the v1.4.4 reorder law survives through a NEW indicated
   door: long-press a row → Move up/Move down (`showMemberMove` →
   `reorderGroupMember`, member-indexed as before) and the sheet rebuilds
   in the new order. Two gates needed dated re-points (test_v149's ⋯ pin,
   test_v144's drag pin) — the features live, their surfaces moved.
3. *The incognito tabs process flag.* `TabsIncognitoActivity` ran in
   `:incognito` but never set `TabManager.INCOGNITO` — a normal tabs view
   in a private process (normal tabs.json, normal chip state, taps routing
   to the normal browser). The static block now matches
   `IncognitoActivity`'s byte for byte. Learning: when a class is split
   into a second process, EVERY entry point in that process needs the
   process's own invariants re-declared — an Activity with no code looks
   like it inherits everything, but static initializers do not inherit.
4. *The system search entries.* All five launcher aliases now carry
   WEB_SEARCH, SEND (text/plain) and PROCESS_TEXT filters, and
   `handleIntent()` funnels the query — `SearchManager.QUERY`,
   `EXTRA_TEXT` or `EXTRA_PROCESS_TEXT` — into `loadInTab()`, posted
   (onCreate runs handleIntent before the first tab exists) with an early
   return so a search entry never also falls through to the VIEW branch.
   Filters went on the ALIASES, not the demoted BrowserActivity: exactly
   one face is enabled at a time, so every face must answer.
5. *The omnibox engine + image doors.* The left glyph became an engine chip
   (glyph + drop arrow → `Ui.choiceSheet` picker; a pick sets the engine
   pref and re-runs a live query through the new engine; a prefilled URL
   only switches). The right gained a camera icon: camera (runtime
   permission 1023 → FileProvider EXTRA_OUTPUT capture 1021) or gallery
   (ACTION_GET_CONTENT 1022), then `VisualSearch.kt` multipart-POSTs the
   picture (≤1024 px re-encode) to the selected engine's visual search —
   Google/Startpage via Google's search-by-image, Bing/DDG/Brave via
   Bing's SBI (DDG and Brave publish no upload endpoint; a toast says
   which route ran). Learning, twice over: Kotlin's `(String?) -> Unit`
   function type boxes its return, so a Java lambda at the call site fails
   to compile — use a `fun interface` SAM for cross-language callbacks;
   and HttpURLConnection never auto-follows a POST's 3xx, so the Location
   header is read and re-GET manually (a 200 body — Bing's shape — is
   saved as a local page).
6. *The standing sweep, v1.4.12 edition.* All 30 prior version gates
   re-pinned 126/1.4.12 with dated notes (test_v1410 and test_v1411 got
   surgical entry-index edits — every About-changelog pin slid one slot
   down when the 1.4.12 line landed at entries[0]); test_screen's
   2200/90 pin re-pointed to the diet constants; the repin script lives in
   the workspace (`scripts/repin_gates_1412.py` pattern). Suite: 62/62
   release-pipeline check commands green (53 python gates + 9 node
   checks) across 36 version gates.
7. *Environment learning (the credential restore).* The signing backup's
   credential lines are `key = value` with an `=` separator — a parser
   that takes "the first whitespace-delimited token after the key name"
   grabs the `=` itself and fails the keystore check. The workspace
   `scripts/restore_credentials.py` (rebuilt this session) now skips the
   `=` before taking the token, verifies the cert fingerprint, and never
   prints a secret. The 2026-11-18 recycle again wiped jdk21/, sdk/,
   gradle-8.9/ and local.properties; full toolchain back via
   `scripts/setup_toolchain.sh` in ~4 min.

**v1.4.13 — the performance five-pack** (the seventh session of this relay,
2026-11-18):

1. *The toolbar label.* The report said the PROCESS_TEXT filter "was
   missing the MIME type" — v1.4.12 had already shipped it (all five
   aliases, pinned by its own gate). The REAL gap was the label: the
   text-selection toolbar renders the RESOLVED FILTER's label, and a
   filter without one falls back to the bare app name. Every alias's
   PROCESS_TEXT filter now carries its own `android:label`
   ("Search in Bolt"). Verified in the BUILT apk (aapt on the binary
   manifest: filter label resource + action + DEFAULT + text/plain all
   present), because a source-level pin alone never proves the MERGED
   artifact. Learning: when a report's diagnosis is already fixed in
   HEAD, reread what the USER actually sees — the visible symptom
   ("shows as just Bolt" vs "Search in Bolt") is the real ask.
2. *The omnibox debounce.* The report described a per-keystroke network
   suggestion pass THAT DID NOT EXIST — the suggest adapter was purely
   local (tabs/bookmarks/history/instant answers). The fix therefore
   SHIPPED the feature with its discipline on day one: engine
   completions (osjson: Google client=firefox, DDG type=list, Bing
   osjson, Brave, Startpage — dead endpoints degrade to no rows, never
   an error) behind a 300 ms Handler/Runnable in the TextWatcher
   (removeCallbacks BEFORE postDelayed — a new keystroke kills the
   pending request), one background thread, a rising generation counter
   + a field-still-holds-the-query guard against late answers, a
   five-row cap, NEVER in incognito, and a Settings off-switch
   ("Search suggestions (from the engine)", default ON). Learning:
   when the field report and the code disagree, the honest move is to
   make the report TRUE the right way — born debounced, not retrofitted.
3. *The favicon LRU.* The disk cache grew forever. Two caps now bound
   it — 1000 icons / 50 MB, either trips the sweep — and eviction is
   least-recently-used by lastModified, which is now a USE stamp:
   load() touches every file it serves from disk (once per host per
   process, before the mem LRU takes over), so "oldest" means "least
   recently served", surviving restarts. The selection is a PURE
   function (selectEvictions) proven by a compiled+run transcription
   probe (the v1.4.11 parse-gate law applied to the one extractable
   pure body). Learning, twice: an eviction order without read-touches
   measures CAPTURE age, not use — it deletes the site you visit every
   day; and the probe's own first draft had a wrong expectation
   (size-optimization instead of pure LRU) — the probe caught the
   TESTER, which is exactly what probes are for.
4. *The WebView pre-warm pool.* A cold `new WebView()` costs ~300 ms of
   main-thread CPU (provider load + AwContents). BoltApp now posts a
   spare, never-attached BoltWeb 250 ms after process start (both
   processes — the incognito data-dir suffix is set earlier in the same
   onCreate, synchronously), and Tab.buildWeb TAKES it with a
   plain-construction fallback. The context law: the spare is built
   over a MutableContextWrapper(appContext) and take() swaps the base
   to the real owner before handoff — a bare app-context WebView breaks
   themed dialogs (HTML <select multiple>'s AlertDialog cannot get a
   window token: BadTokenException), so the swap is what makes a
   pre-built instance safe to attach to an activity. Learning: never
   construct a WebView you intend to attach on anything but a swappable
   wrapper — the constructor's context outlives your knowledge of the
   owner.
5. *The If-Range contract.* The Range/.part resume itself predates this
   release (Range: bytes=N-, 206 append / 200 restart / 416 fresh-start)
   — re-pinned so it cannot silently regress. The hole this release
   closes: a server that STILL honors Range after changing the file
   answers 206 and the old code silently SPLICED two different bodies
   into one corrupt download. The engine now captures every final
   response's ETag, persists it with the queue (survives the pause AND
   the process death), and sends it back as If-Range on resume:
   unchanged → 206 append as before, changed → 200 and the existing
   restart path replaces the part. Both restart paths clear the dead
   validator (no part, no etag). Learning: resume without a validator
   is not a feature, it is a corruption generator with extra steps.
6. *The standing sweep, v1.4.13 edition.* All 30 prior version gates
   re-pinned 127/1.4.13 with dated notes (the repin script lives in the
   workspace: `scripts/repin_gates_1413.py`); test_v1410/test_v1411/
   test_v1412 got surgical entry-index edits (every About-changelog pin
   slid one slot down when the 1.4.13 line landed at entries[0] — v1410
   also gained its missing [2]=1.4.11 check and a fixed stale
   versionName message label). Suite: 68/68 release-pipeline check
   commands green (54 python gates + 9 node checks + 2 javac + 3 java
   sim probes) across 37 version gates.
7. *Environment learning (the half-installed platform).* The
   2026-11-18 recycle wiped the toolchain again; setup_toolchain.sh
   restored it — but `sdk/platforms/android-34/` survived HALF-INSTALLED
   (package.xml + framework.aidl present, android.jar MISSING), so
   sdkmanager believed the package installed and no-op'd, and Gradle
   answered "Cannot query the value of this provider because it has no
   value available" (its generic form for an unresolvable bootclasspath).
   The fix: `rm -rf` the platform dir and reinstall it. Also: the first
   background setup run died silently (a retry worked) — always re-check
   the toolchain log AND the binary it claims to have built. The
   workspace `scripts/restore_credentials.py` was rebuilt (the = skip
   law from v1.4.12, now a script again after the recycle).

**v1.4.14 — the interaction six-pack** (the eighth session of this relay,
2026-11-18):

1. *The drag is back.* v1.4.12's group sheet replaced the inline strip's
   hold-drag with long-press "Move up / Move down" buttons (the sheet was
   a ScrollView of rows — an ItemTouchHelper drag could not live there).
   The member list is now a RecyclerView inside the sheet with an
   ItemTouchHelper.SimpleCallback (makeMovementFlags(UP | DOWN, 0), drag
   only), every crossed neighbour committing through the same
   tm.reorderGroupMember backend the buttons drove, notifyItemMoved
   gliding the swap. THE NEW SHEET DOOR: Ui.showSheetView() hosts a
   RecyclerView as the sheet root WITHOUT the ScrollView wrapper — nested
   scrolling steals the vertical touch stream and the drag can never arm;
   showSheet() now delegates to it (one window mechanic, two bodies).
   Learning: a sheet body that needs GESTURES must own its touch stream;
   a ScrollView wrapper is for static row stacks only.
2. *The external-search new-tab law.* PROCESS_TEXT / WEB_SEARCH / SEND
   queries landed in loadInTab() — the ACTIVE tab — so "Search in Bolt"
   from another app overwrote the page the user was reading. All three
   now route through openQueryInNewTab(): the resolution moved into
   resolveInput() (ONE resolver — scheme passthrough, bare-host, bangs,
   engine, tracker cleaner — shared by the omnibox flow and the side
   door), the tab is NEW, and it runs INLINE like the VIEW branch, not
   posted: it builds its own tab so it has no dependency on the active
   tab onCreate has not created yet, and a cold-start search lands as
   ONE tab (no spare home tab) — the same shape a cold-start VIEW link
   produces. Learning: "posted because loadInTab needs the active tab"
   was a loadInTab property, not a handleIntent property — when the
   callee stops needing the precondition, the posting ritual should die
   with it.
3. *The omnibox icon diet.* The engine chip + camera + paste strip were
   permanent fixtures (~230 dp hugging the field). State-aware now, in
   the TextWatcher: EMPTY shows chip + camera + paste (+ mic); TYPING
   shows clear (+ mic) only. The chip STAYS in both states (the engine
   indicator + the v1.4.12 pick-re-runs door — hiding it would break
   engine-switch-mid-query); copy is RETIRED from the omnibox (the
   field's own text-selection handles cover it — the spec's "only the
   clear button (plus mic)" leaves no state for it). Learning: when a
   spec enumerates two states exhaustively and a control belongs to
   neither, the spec is RETIRING it — read the omission as a decision,
   and say so in the changelog.
4. *The incognito return route.* The leak: satellite screens (bookmarks,
   history, saved pages — and Settings, which chains to all three) run
   in the MAIN process even when launched from the incognito browser,
   because android:process is a property of the ACTIVITY, not of who
   launched it; their row taps fired plain BrowserActivity.class VIEW
   intents, so the page loaded with the normal cookie jar and history.
   The fix is a stamp-and-return contract: Ui.launchSatellite() stamps
   EXTRA_FROM_INCOGNITO when the launcher is the incognito process
   (TabManager.INCOGNITO — true exactly in :incognito) or a stamped
   satellite itself (chains keep the stamp), and Ui.browserOpenIntent()
   routes a stamped satellite's opens to IncognitoActivity — the same
   ACTION_VIEW contract in the private process. In-browser new-tab logic
   never rides intents at all (the process law, documented at
   openNewTab). Learning: a two-process app must ask of EVERY screen
   "which process am I in, and which process do my launches RETURN
   to?" — the manifest decides the first, the intents decide the second,
   and nothing keeps them in sync by default.
5. *The visual-search OOM ladder.* The report described "encoding it
   directly to Base64" — code that does not exist anywhere in the flow
   (the v1.4.13 pattern again: the report's diagnosis and HEAD disagree;
   the honest move is to make the report TRUE the right way). HEAD
   already decoded bounds-first at a 1024 px cap; what it LACKED was
   crash-proofing: the cap is now a named MAX_EDGE = 1280 (the spec's
   number), and an OutOfMemoryError retry ladder doubles the sample
   size (<= 3 retries) — memory pressure degrades resolution, it never
   kills the process. The cap keeps riding the LONG edge (a panorama's
   long edge is the memory bomb; a both-edges rule would fully decode
   an 8000x1000 pick).
6. *The bookmarks/history search bars.* BaseListActivity gained the
   opt-in addSearchBar(): a TextWatcher filters the rows in real time
   by title or sub (sub carries the URL — one rule covers "by URL or
   title"), over the LAST-BUILT model captured in fullRows (setRows and
   setRowsAnimated both capture; the filter pass re-renders through the
   extracted renderRows without re-capturing) — no prefs re-read per
   keystroke, so a long history stays keystroke-fast. rowMatches is the
   release's one pure function, proven by a compiled+run transcription
   probe (the v1.4.11 parse-gate law). A query that matches nothing
   gets its own "No matches" state, distinct from the screen's
   nothing-saved art.
7. *The standing sweep, v1.4.14 edition.* 32 prior version gates
   re-pinned 128/1.4.14 with dated notes (the repin script lives in
   the workspace: `scripts/repin_gates_1414.py`); test_v1410/1411/1412/
   1413 got surgical entry-index slides (every About-changelog pin slid
   one slot down when the 1.4.14 line landed at entries[0]); v144's
   in-group reorder pin, v1412's handleIntent handoff pin and
   visual-search cap pin, and v1413's handoff pin got dated behavioral
   re-points (the drag door, the new-tab law, the MAX_EDGE constant).
   Suite: 69/69 release-pipeline check commands green (55 python gates
   + node checks + javac/java sim probes) across 38 version gates.
   ONE REPIN BUG worth remembering: the surgical edit that renamed
   v1413's [1] pin from "1.4.12|" to "1.4.13|" was immediately
   re-matched by the GENERIC 1.4.13->1.4.14 pair and double-applied —
   order your repin so generic pairs run FIRST and surgical results are
   inert to everything that runs after (or verify the [1] pin by hand,
   which is how this one was caught).
8. *Environment learnings (the fourth recycle).* The toolchain died
   again (half-installed android-34 platform — package.xml present,
   android.jar missing: rm -rf the platform dir and let setup_toolchain
   reinstall it); `scripts/restore_credentials.py` was rebuilt with two
   new laws: the backup's credential keys carry the `bolt.` prefix
   (accept both spellings), and the cert-fingerprint check must
   NORMALIZE (keytool prints colon-separated UPPERCASE hex; the pin is
   lowercase — a substring compare always fails and had silently
   degraded into a skip in earlier sessions). Also: a python heredoc
   writing Android string resources EATS a backslash escape — `\'` in
   the heredoc becomes `\'` in the file only if doubled; an unescaped
   apostrophe in a <string> item kills the whole resource merge with a
   bare NPE deep in AAPT (the error names no line — validate the XML
   and grep for `[A-Za-z]'[A-Za-z]` before building).

**v1.4.15 — the responsiveness four-pack** (the ninth session of this
relay, 2026-11-18):

1. *The deaf listener.* DownloadsActivity implemented
   DownloadEngine.Listener and removed itself in onDestroy() — but never
   REGISTERED, so the whole screen leaned on the 500 ms refreshLoop poll
   (progress up to half a second late; "switch tabs and it looks reset").
   addListener now runs at the end of onCreate() AND in a new onResume()
   (the engine's addListener is contains()-guarded, so the double arm is
   a no-op); onDownloadsChanged() was ALREADY implemented, posting a
   guarded reload() to the ui handler — the callback machinery existed,
   only the registration was missing. Learning: a Listener interface with
   a remove but no add is a HALF-WIRED observer — the poll fallback hides
   the deafness (it works, just late), so nothing crashes and the bug
   reads as "laggy", not "broken".
2. *The stale highlight.* TabsActivity.onResume() called only
   adapter.rebindIcons() — a rebind of the rows the adapter ALREADY held,
   never a model rebuild; a switcher covered while tm.active moved
   (notification, external intent) reopened outlining the OLD tab.
   adapter.reload() now follows (rebuildItems + notifyDataSetChanged +
   afterReload); the active search filter survives (rebuildItems
   re-applies tabQuery). Learning: rebind and reload are different
   contracts — a rebind refreshes what the rows SHOW, a reload refreshes
   WHICH rows exist; resume-time freshness needs the second one.
3. *The omnibox double diet.* (a) syncOmniIcons called setVisibility() on
   four views per keystroke; the empty state is now cached in a
   Boolean[1] (null sentinel = never applied, so the opening sync always
   runs) and a keystroke that does not flip EMPTY<->TYPING costs one
   array read. (b) ad.update() walked the bookmarks + speed-dial +
   400-entry history JSON synchronously in afterTextChanged; update() is
   now a DISPATCHER: the build runs on SUGGEST_BUILD (one process-wide
   daemon thread, "bolt-suggest"), every call bumps suggestGen, and the
   apply (items swap + notify) posts through the ACTIVITY's ui handler
   only if its generation is still the newest — stale builds can never
   reach the screen, and onDestroy's existing ui sweep kills any
   still-queued apply. The previous rows stay up until the fresh pass
   lands (no clear-first flash of emptiness). Learning: v1.4.13 debounced
   the NETWORK and left the LOCAL walk synchronous because it was
   "memory-fast" — memory-fast is still main-thread work; the right cut
   is thread ownership, not just request rate. And a generation counter
   beats a string compare for staleness: it is monotonic where strings
   can repeat.
4. *The handler sweep.* sdWinH (speed-dial hold-to-lift) and suggestH
   (the omnibox debounce) were created as fields but only ui was ever
   cleaned in onDestroy(); both now get removeCallbacksAndMessages(null),
   placed ABOVE the incognito early-return so BOTH process paths sweep
   (a cleanup below an early-return is a half cleanup — the branch that
   returns early never reaches it).
5. *The standing sweep, v1.4.15 edition.* 33 prior version gates
   re-pinned 129/1.4.15 with dated notes
   (scripts/repin_gates_1415.py in the workspace); test_v1410/1411/1412/
   1413/1414 got surgical entry-index slides (every About-changelog line
   slid one slot down when 1.4.15 landed at entries[0]; the [2] pin's
   stale '> 1' len guard — two slides behind its index — was repaired to
   '> 3'); v1413's exact-query guard pin got a dated behavioral re-point
   (equalsIgnoreCase(webQuery) -> equalsIgnoreCase(wq): the guard moved
   into buildRows and reads the main-thread snapshot). TWO REPIN LAWS
   learned the hard way: (a) the old script's r'' "regex-looking" pairs
   were LITERAL-substring matches against the backslash text inside the
   gates' re.search() patterns — dropping them updated the pin LABELS but
   not the pinned REGEXES, and five gates (v1384-v1387, v1391) failed
   with a label that said 129 and a regex that still hunted 128; keep
   BOTH form families in every repin. (b) The repin script is
   SINGLE-RUN by design — a second run double-slides every surgical
   index pin; running it twice was caught and recovered by
   git-checkout-and-rerun-once. The v1.4.14 order law (generic
   1.4.14->1.4.15 pairs BEFORE the 1.4.13->1.4.14 previous-live bump)
   held — no double application.
6. *Suite shape.* All 56 release-pipeline gate commands green (the new
   v1415 gate among them — its compiled probe runs transcriptions of
   BOTH new contracts: the generation guard and the icon state cache).
   The standalone gate run needs the grafted JDK on PATH (release.sh
   exports it; three older gates shell out to bare 'javac' and fail
   standalone without it — pre-existing, not a gate regression).

**Environment notes** (the sandbox toolchain, for fresh sessions): the
build needs JDK 21 with `javac` (the sandbox's `java` is a JRE — extract
`openjdk-21-jdk-headless` and graft, see the pattern in
`scripts/setup_toolchain.sh`; a working copy lives at
`/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64` in the maintainer's
sandbox), the Android SDK 34 (`sdk/`), and Gradle 8.9 (`gradle-8.9/`) —
all OUTSIDE the repo. The 2026-11-18 recycle wiped jdk21/, sdk/,
gradle-8.9/ and local.properties again: the full toolchain restored in
~4 min via `scripts/setup_toolchain.sh` (the JDK alone is faster via the
workspace's `scripts/restore_jdk.sh` when no build is needed). If Gradle
fails with "Could not read workspace metadata", delete
`.gradle-home/caches/8.9/transforms` and re-resolve. `local.properties`
in the working clone carries `sdk.dir` + the signing credentials; it is
gitignored, must never be committed, and DOES NOT SURVIVE a sandbox
recycle — a fresh clone has none: re-create it from the signing backup
that lives in the maintainer's workspace `download/` folder
(`bolt-signing-backup.txt` + `bolt.keystore`; the workspace-side
`scripts/restore_credentials.py` rebuilds local.properties from the
backup WITHOUT printing the password — the credential lines are
`key = value`, so skip the `=` and take the first token after it; the
keyPassword value's trailing "(PKCS12…)" note is dropped by the
first-token rule).

**v1.4.16 — the reliability nine-pack** (the tenth session of this
relay, 2026-11-18):

1. *The 0KB corpse law.* savePart created the destination document
   BEFORE copying the bytes; a failed copy fell through to the next
   destination leaving the 0KB file behind — and since SAF/MediaStore
   name-collide with "(1)", every retry minted a fresh corpse ("name",
   "name (1)", "name (2)"). All three destination paths (SAF
   createDocument, MediaStore insert with IS_PENDING, plain
   uniqueFile) now delete the created URI / partial File on copy
   failure BEFORE the fall-through. Learning: a create-then-copy
   sequence is a TWO-RESOURCE transaction — the cleanup must live in
   the catch, not in the caller's retry logic, because the fall-through
   design means the caller never learns the first destination existed.
2. *The blob routing hole.* The v1.3.58 rescue chain (BlobDownload →
   gmSaveBlob @JavascriptInterface → saveDataUrl) existed and the
   download DIALOG path used it — but GM_download fed every URL
   straight to DownloadEngine.enqueue, so a userscript handing over a
   blob:/data: URL died in HttpURLConnection (blob: is not a URL the
   URL constructor knows: MalformedURLException x4 retries ≈ 7 s of
   sleeps, then a failed row). onDownloadStart now routes blob:
   THROUGH the bridge before the dialog (no blob depends on a confirm
   anymore), and gmDownload routes through enqueueDownload's scheme
   law — one routing table, every caller. Learning: when a rescue
   exists but only SOME doors use it, the fix is not a second rescue;
   it is routing every door through the one table that already knows
   the schemes.
3. *The strict extension law.* ensureExt only repaired
   missing/.bin/.part extensions; a query-string URL could guess
   "photo.zip" while the bytes sniff image/jpeg. refineFile now tracks
   whether the type was LEARNED this call (real Content-Type swap or
   magic-byte sniff) and forces the name's extension to extForMime of
   the learned mime — a wrong extension is repaired, an honest
   human-typed name with an honest mime keeps its spelling (the flag
   only fires on learning). Learning: lenient repair and strict
   enforcement are different contracts; the discriminator is whether
   this call ACQUIRED new knowledge.
4. *The mid-drag reload deferral.* The v1.3.10 drag model already
   returns false from onMove (no live swapping; reorder commits at
   drop) — but any EXTERNAL adapter.reload() racing the gesture (a
   dialog confirm, an intent-driven tab mutation, a settle posted from
   an earlier drop) rebuilt the board under ItemTouchHelper and
   recycled the dragged ViewHolder mid-flight ("tab ghosting on
   move"). reload() now parks the request in pendingReloadDuringDrag
   while dragActive and DragMergeCallback.clearView flushes it in a
   finally (posted, so a deferred settleDropSlide lands first; a
   same-data second reload is a visual no-op). Learning: the v1.3.47
   deferIfBusy law covered the RV's OWN layout state but not the
   ITH's gesture state — "busy" has more dimensions than
   isComputingLayout.
5. *The wrap-width row.* GroupSheetAdapter's rows were created with
   no LayoutParams, so RecyclerView's generateDefaultLayoutParams
   handed them WRAP_CONTENT width — and weight=1 divides only the
   LEFTOVER of a match-parent row, so the text column collapsed and
   the 3-dot menu sat mid-row. One setLayoutParams(MATCH_PARENT,
   WRAP_CONTENT) after creation. Learning: a RecyclerView child with
   no explicit params is NOT match-parent — the generated default is
   wrap/wrap, and every weight inside it silently dies.
6. *The race-proof process flag.* The incognito flag lived in static
   blocks — which run when the CLASS resolves, and class-load order
   across the multi-process split is not guaranteed: TabManager could
   construct before IncognitoActivity's initializer ran, booting the
   "incognito" process in normal mode (normal persisted tabs, normal
   chip, taps routing to the normal browser). Both incognito
   activities now ALSO set TabManager.INCOGNITO = true as the very
   first statement of onCreate(), before super.onCreate() (whose
   tab-restore constructs the TabManager). Learning: a static
   initializer is a load-order bet; a lifecycle line before super is
   a guarantee. Belt and suspenders — the static stays (it covers
   class-reference paths that never build an activity).
7. *The grouped-active highlight.* applySelVisuals only ever ran for
   LOOSE cards, so the tab you were reading inside a folder was
   invisible in the switcher (v1.4.12 even taught scrollToActive to
   find the member's GROUP ROW — the scroll knew, the highlight
   didn't). The group bind now derives activeInGroup (tm.active's
   groupId == the row's gid) and applies the same accent rim plus a
   new v_group_strip bottom strip (added to BOTH group layouts —
   the tab card's v_strip law, extended). Learning: when one consumer
   of a state (scrollToActive) learned the group-membership rule and
   another (the bind) didn't, the screen argues with itself.
8. *The two-row omnibox.* The single row's icon congestion was
   structural: v1.4.12's engine chip and v1.4.14's state-aware strip
   shared the field's row, and at the worst moment five 40dp buttons
   trailed the EditText. The sheet is now two rows: Row 1 = engine
   chip | EditText (weight 1) | Clear; Row 2 (omni_actions) = Copy,
   Paste, Duplicate tab (the switcher's per-card clone, promoted),
   Image Search, Voice Search — evenly weighted 44dp slots, shown
   ONLY while the field holds text (hidden when empty, per the
   product spec). The v1.4.15 Boolean[1] state cache survives: one
   EMPTY<->TYPING flip = one setVisibility pair (row + clear); a
   keystroke inside a state costs one array read. Learning: when a
   row must host both text and actions, the actions lose — give them
   their own row and the visibility question becomes one unit
   instead of N buttons.
9. *The three cleanups.* (a) showTab hides a visible find bar, clears
   etFind/tvFindCount, and clearMatches()es the DEPARTING tab
   (shownTabRef still points at it below the re-pin) — the bar used
   to bleed onto the next page with the old page's matches. (b)
   onDestroy dismisses a showing omniboxDialog ABOVE the incognito
   early-return (dismissAllOverlays is NOT on the destroy path).
   (c) onPause AND onStop remove a stranded sdGhost from flRoot,
   parent-guarded (a mid-drag backgrounding froze the lift ghost
   over the home screen; onPause can be skipped while onStop still
   fires). Learning: per-screen UI state (find bar, dialog, ghost)
   belongs to the SCREEN — every exit path from that screen must
   sweep it, and "exit path" includes the lifecycle, not just the
   close button.
10. *The standing sweep, v1.4.16 edition.* 34 prior gates re-pinned
    130/1.4.16 with dated notes
    (workspace scripts/repin_gates_1416.py — SINGLE-RUN, both pin
    form families, generic 1.4.15->1.4.16 pairs BEFORE the
    1.4.14->1.4.15 previous-live bump); the About-changelog slides
    (+1 on every index pin, guards descending so no len-guard is
    double-bumped); THREE behavioral re-points in test_v1414/v1415
    where the two-row omnibox legitimately moved the pinned
    camB/pasteB/copyB forms to the omni_actions strip law; ONE
    near-miss: test_v147 pins the orientation capture inside a
    5200-char window of onPause — the ghost sweep initially landed
    ABOVE it and pushed the capture out of the window; fixed by
    moving the sweep BELOW the capture (a zero-impact reorder that
    keeps the old gate green without touching it — prefer moving
    YOUR code over re-pointing an old gate when both are free).
11. *The apostrophe NPE.* The new About entry crashed
    mergeReleaseResources with a bare javax.xml.stream
    NullPointerException — no file, no line. Cause: three UNESCAPED
    apostrophes in the string-array item ("type's", "switcher's",
    "sheet's"); through the merger pipeline the classic "Apostrophe
    not preceded by \" diagnostic never surfaces, it is this NPE
    instead. The 1.4.14 entry's \'s were the tell. Learning: in
    Android values resources the apostrophe is ALWAYS a two-way
    trap — a StAX NPE from the merger means "scan for unescaped ' ".
    (A bisect against the clean tree isolated it in two builds: the
    resource NPE followed the strings.xml edit, not the layouts.)
12. *Suite shape.* All 40 release-pipeline gate commands green (the
    new v1416 gate among them — its compiled probe runs
    transcriptions of the 0KB corpse cleanup, the mid-drag reload
    deferral, and the strict extension law).

**v1.4.17 — the polish seven-pack** (the eleventh session of this relay,
2026-11-18):

1. *The span-void law.* Moving a tab into a group via either move-to-group
   door left a void in the grid: the move changes the SPAN a position
   occupies, and the layout pass after a plain notifyDataSetChanged can
   lay the OLD span shapes onto the NEW positions — worst exactly when a
   settle animation races the swap. Both handlers now run
   reloadGridNoVoid(): stopScroll() BEFORE the data swap, then the
   span-cache invalidation + requestLayout() after. ONE API-NAME LAW
   worth the memory: the report named
   glm.invalidateSpanAssignments() — a support-lib-era method that DOES
   NOT EXIST on androidx recyclerview 1.3.2 (the first build failed on
   it; javap on the cached AAR is the proof tool). The public
   invalidation door on this dependency is
   SpanSizeLookup.invalidateSpanIndexCache() (+ invalidateSpanGroupIndex
   Cache()); with the lookup's cache disabled (this app never enables
   it) the invalidation is belt-and-suspenders and the operative cures
   are the scroll stop + the forced relayout. Learning: a report that
   names an API is still a hypothesis — the compiler and javap are the
   truth, and a named-but-absent method is a diagnosis of MECHANISM,
   not of spelling.
2. *The sheet-active highlight + the rim inset law.* The group sheet
   bound every member identically (the v1.4.16 grouped-ACTIVE fix taught
   the switcher's group ROW, but the sheet never learned). The member
   that is tm.active now wears the accent rim + accent title. And every
   accent rim (loose card, group row, sheet row) drew its 2dp stroke AT
   the view bounds — setForeground straddles the edge, half the stroke
   eating the card's own corner geometry. All three rims now ride an
   InsetDrawable with a 2dp inset: concentric with bg_card's 16dp corners
   (rim radius 14 = 16 - 2, the original author's intent completed),
   clear of the 4dp-margin buttons. Learning: a foreground stroke is
   centered on the BOUNDS by definition — the fix is never "move the
   content", it is "inset the drawable", because the drawable is the
   only thing whose geometry you own.
3. *The fidelity pass.* The visual-search grey-image report named three
   legs; HEAD already carried two of them (bilinear filter=true, strict
   CRLF multipart) and the third was real (q85 → q90, the knee where
   fine text stops smearing). The pins now say so explicitly —
   FilterQuality in the report's vocabulary is the SDK's filter boolean,
   and naming the mapping in the code keeps the next report honest.
4. *The white-flash fix (the honest leg).* The report asked for
   setForceDark(FORCE_DARK_ON) + setAlgorithmicDarkeningAllowed(true) —
   the exact pair retired in v1.3.81/v1.3.91 (a no-op at target 34; the
   double-invert corpse). The flash was real: the dark chain ran only at
   onPageFinished, so a light site painted white from first paint until
   the last subresource. Three honest legs shipped: the chain
   (probe → ask → invert) moved to onPageCommitVisible (render-blocking
   CSS is parsed there, so askDarkJs is honest that early); the
   finish-time pass became a run-token-guarded BACKSTOP — a second
   unguarded run would probe the ALREADY-INVERTED page, read it as
   painted-dark, and clear Bolt's own inversion (the probe cannot tell
   site-dark from bolt-dark — an idempotence trap worth remembering:
   "run the same code twice" is only safe when the code cannot observe
   its own effects); and the unpainted canvas is the skin dark base
   (Fx.webBgDark) whenever the site-dark engine is on for the tab's
   domain, re-applied at onPageStarted for each new domain. Learning:
   when a report's prescription contradicts documented field history,
   cure the SYMPTOM with the architecture that already exists — the
   commit hook (v1.3.80's cosmetic pass) was sitting right there.
5. *The evidence channel.* "Provider error" is the 5xx bucket, but the
   toast hid the exact HTTP shape. shortErr now mirrors the raw provider
   response to logcat (Log.e "AiBridge", capped 2000 chars). The model
   chain was already current (gemini-3.5-flash-lite, verified
   2026-11-08) — the report's gemini-1.0 diagnosis predates the v1.3.75
   chain move. Learning (the v1.4.13 law, again): when the diagnosis
   and HEAD disagree, ship the missing OBSERVABILITY, not the named
   code change — the next field report arrives with its own evidence
   attached.
6. *The Material Symbols pass.* The nine core glyphs (search, close,
   more, copy, paste, open-new/duplicate, camera, mic, drop-down) are
   now Google's official Material Symbols Rounded weight-400 paths (the
   2px stroke at 24dp). Conversion law: the published SVG viewBox is
   "0 -960 960 960" — keep the path data byte-identical and re-base it
   with a <group android:translateY="960"> inside a 960x960 viewport
   (provenance beats hand-massaging coordinates), fillColor stays
   ?attr/colorTint (the theme-tint law; the widget ImageView applies its
   own explicit tint over it, which keeps working). The official SVGs
   are fetched from fonts.gstatic.com (materialsymbolsrounded/<name>/
   default/24px.svg) — the fetch script lives in the workspace
   (scripts/material_symbols_1417.py).
7. *The incognito search face.* PROCESS_TEXT/WEB_SEARCH/SEND lived only
   on the BrowserActivity launcher aliases, so a selection search in a
   private tab routed through the MAIN process — the user landed in
   normal mode with the normal cookie jar. A new SearchIncognito
   activity-alias registers the same three actions against
   IncognitoActivity (the :incognito process), with its own "Search in
   Bolt (Incognito)" label so the OS offers both rows. TWO manifest
   laws: the alias carries NO android:process (process placement is a
   property of the TARGET activity — an alias cannot override it, and
   the target's placement is exactly what makes the face private); and
   the alias must be declared AFTER the activity it targets. Learning:
   a filter set that exists on only one process's faces is a routing
   hole by construction — every PROCESS entry point needs its own face,
   and "the OS shows both" is the feature, not the clutter.
8. *The standing sweep, v1.4.17 edition.* 35 prior gates re-pinned
   131/1.4.17 with dated notes (workspace scripts/repin_gates_1417.py —
   SINGLE-RUN, both pin form families, generic pairs BEFORE surgical
   slides, slides DESCENDING so a slid pin can never re-match); the
   About-changelog index slides (+1 on every index pin ≥1, len-guards
   riding the same descending order); FIVE behavioral re-points where
   this release legitimately moved pinned forms: v1381 (the dark chain
   moved into runWebDarkPass), v1412+v1413 (the manifest grew a sixth
   alias — action counts 5→6, text/plain mimes 10→12; the
   search_in_bolt LABEL count stays 5 because the incognito face rides
   its own _incognito string), v1414 (JPEG 85→90), v1416 (the rim
   InsetDrawable law). THE v1.4.15 ESCAPED-FORM LAW REPEATED ITSELF on
   the first pass: the regex-form pairs were written r"...\"1\.4\.16\""
   — in a RAW string \" is backslash+quote, so the pairs hunted text
   that does not exist while the plain-form pairs moved the LABELS;
   14 gates failed with labels saying 131 and regexes still hunting
   130. The recovery (scripts/repin_1417_recovery.py) spells the
   escaped pairs as single-quoted raw strings — and the law hardens:
   every escaped-form pair must be TESTED for a hit before the repin
   is believed (a pair that matches nothing is a bug, not a no-op).
   TWO more recovery re-points the first pass missed: v1397's vector
   parse (ic_open_new is a 960-viewport Material Symbols glyph now),
   and v1416's own-line word loop (entries[0] slid to [1] — a gate
   written when its release was NEWEST checks its own line at [0],
   and every later release must slide it). Suite after the recovery:
   41/41 version gates green.

**v1.4.18 — the field-cure six-pack** (the twelfth session of this relay,
2026-11-19):

1. *The law of this release.* v1.4.17 shipped 41/41 gates green and most
   field symptoms SURVIVED — the gates were static text pins, which prove
   code EXISTS, not that the bug is CURED. The maintainer's order for
   v1.4.18: every fix carries a compiled+run BEHAVIOR probe where a pure
   body could be extracted (the v1.4.11 parse-gate law) or a Diag
   breadcrumb where it could not, moved pins are re-pointed at the door
   the user actually uses with dated notes, and "do not just re-pin —
   verify the actual door the user uses" is now the standing rule. The
   new gate (test_v1418) runs six compiled transcriptions: the
   settle-vs-remove race (naive hole vs posted relayout), omniCopyText +
   the visibility state machine, the dark decision + the background-tab
   guard, ensureBaseHref, buildChain/advanceChain/retryDelaySeconds +
   the dead-end walk, and the SessionState file core + the forward
   decision.
2. *The drag-door void (the wrong-door corpse).* v1.4.17's span-void cure
   wired reloadGridNoVoid() into the TWO ⋯-menu move-to-group doors —
   the DRAG door (the one the report's user used) still called a bare
   adapter.reloadDiff at all three drop sites. And in CARD view
   getSpanSize() returns 1 for groups too, so the span-cache theory
   could not explain a card-view hole at all: the real mechanism is
   ItemTouchHelper's drop-recovery animation racing the DiffUtil remove
   pass, the stale slot geometry surviving until the switcher reopens.
   The cure is three-legged: reloadDragNoVoid() wraps the ANIMATED diff
   in the no-void trio (the drag keeps its animation), reloadDiff posts
   ONE requestLayout after dispatchUpdatesTo (the frame-later relayout
   lands after the settle/restore callbacks — the race antidote), and
   both ungroup doors ride reloadGridNoVoid (the plans-section
   prediction, swept without waiting for its own report). Learning: a
   fix wired to the WRONG door reads as "the fix doesn't work" in the
   field while every gate stays green — when a symptom survives a cure,
   first question: WHICH door did the user walk through?
3. *The focus law (the omnibox strip).* v1.4.16's two-row omnibox gated
   Row 2 on the field HOLDING TEXT — image search, paste, duplicate and
   voice were invisible until the first keystroke, exactly when they are
   most useful (before typing). The strip now shows the moment the
   omnibox opens; only Clear follows the field state (the v1.4.15
   one-write cache survives, scoped to the clear button); Copy on an
   empty field copies the ACTIVE PAGE'S URL (omniCopyText, pure,
   probe-pinned) and a home tab toasts instead of silently doing
   nothing. Learning: a visibility spec keyed on input STATE hides the
   actions exactly when the user is deciding what to do — "the actions
   exist for the empty field" is the product truth Chrome's omnibox
   already encodes.
4. *The ask-first hold WAS the white-paint (dark engine).* The v1.3.94
   chain probed, then ASKED whether the page DECLARES dark, then held
   ~3 s for its own dark — and a site that merely DECLARES (a
   color-scheme meta, any prefers-color-scheme rule — very common) but
   renders light under a fresh cookie jar sat WHITE for the whole hold.
   A declaration is not evidence the page WILL self-darken; the probe's
   painted-light reading is evidence it has NOT. Light at commit now
   inverts IMMEDIATELY; darkAwaitSelfDark is deleted; the 2.5 s late
   re-probe stays as the pull-back (inversion → none only); the
   background-tab abort (w != activeTab().web) became a liveness check
   (tabOf(w) == null) — a background-loaded tab's site could self-darken
   and Bolt's inversion stayed forever, because the finish backstop
   skips urls the commit pass already spoke for. The trade is accepted
   knowingly: a brief double-dark on the rare async-dark class in
   exchange for zero white hold on every merely-declaring site. A
   "dark commit-pass" Diag breadcrumb proves the hook fires on device.
   Learning, twice: evidence of INTENT (a declaration) is not evidence
   of BEHAVIOR (painting dark) — gate on the behavior; and a guard
   keyed on ACTIVE-NESS silently skips every background tab when the
   fallback also skips them — the two skips compose into a forever-bug.
5. *The session-continuity law (visual search).* The upload rode a bare
   HttpURLConnection (its own UA, NO cookies) and handed the result URL
   to the WEBVIEW — a different cookie jar; Google could not associate
   the two requests and answered with the error-image placeholder (the
   grey-image report). The upload now carries the WebView jar's cookies
   for the endpoint, speaks the WebView's EXACT UA
   (WebSettings.getDefaultUserAgent), and every Set-Cookie minted along
   the redirect ladder is pumped into the WebView's CookieManager BEFORE
   the result loads — each GET hop attaches and pumps too, which is what
   makes Google's consent redirect resolve. Bing's saved file:// page
   gained a <base href> (ensureBaseHref, pure, probe-pinned) so its
   relative resource URLs stop dying against the local directory.
   Learning: HttpURLConnection and a WebView are two different clients
   until you explicitly share the cookie jar AND the UA AND the
   Set-Cookie pump — any one of the three missing and the session
   splits.
6. *The AI chain law.* A CUSTOM model disabled every fallback
   (arrayOf(m) alone) and the chain advanced only on 404 — a custom
   model meeting a 503 "model overloaded" wave (gemini-3.8-flash had
   documented ones in 2026-11) had no retry, no fallback, and the user
   ate a bare "provider error". buildChain (pure, probe-pinned) now
   always appends the default + fallbacks after the custom pick
   (deduped); advanceChain walks on the 5xx bucket too; the 503 ladder
   retries the SAME model with the provider's retryInfo.retryDelay hint
   (plain-scan parsed, capped 30 s, ≤2 retries; the 429 patience is
   unchanged); the model that actually answers is logged + Diag-recorded
   always and toasted when it is NOT the configured pick. Learning: a
   fallback chain that turns off for custom configurations converts
   every transient outage into a permanent user-facing error — custom
   picks need the safety net MORE than defaults, not less.
7. *The session-aware external search.* The system search doors resolve
   into the MAIN process's faces (the launcher aliases, the widget's
   hardcoded BrowserActivity) — v1.4.17's SearchIncognito alias only
   added a second chooser row, frequently collapsed into the toolbar
   overflow, so an incognito user's search surfaced the NORMAL task
   (whose back stack still held the normal TABS MENU — the bounce the
   report named). SessionState (new, probe-pinned): whichever
   BrowserActivity variant resumes writes one tiny record into filesDir
   (shared by both processes; atomic tmp+rename; read FRESH — the two
   processes' Prefs copies cannot see each other's writes); the normal
   search faces forward into IncognitoActivity and finish, so the
   normal task never surfaces. The forward runs BEFORE onResume (the
   reading face never overwrites the record it just read); a missing
   record reads normal (the feature cannot hijack a normal search);
   the widget keeps targeting BrowserActivity ON PURPOSE (it is the
   session-aware entry — hardcoding the incognito face would break the
   other half); the "(Incognito)" alias stays as the manual door; the
   in-app omnibox → tabs-button round trip stays in-process and
   private. Diag breadcrumbs name the face and the forward on every
   search intent. Learning: an OS-level entry point cannot know which
   of your processes the user "is in" — you have to record it yourself,
   cross-process, and the record must be written by the RESUME path,
   not the launch path (launches are ambiguous, resumes are facts).
8. *The standing sweep, v1.4.18 edition.* 36 prior version gates
   re-pinned 132/1.4.18 with dated notes (workspace
   scripts/repin_gates_1418.py — SINGLE-RUN, both pin form families, a
   catch-all tail pair for message labels, test_v1418 slide-exempt);
   FIVE behavioral re-points where this release legitimately moved
   pinned forms: v1394 (the whole ask-first dark section — the hold is
   dead, invert-now + the commit breadcrumb pin it), v1414 + v1415 +
   v1416 (the omnibox strip law — the text-gate pins became focus-law
   pins; the v1.4.15 cache-order pin re-scoped to the clear button),
   v1417 (the reloadGridNoVoid count 2 → 4 — the ungroup doors joined;
   plus its 1.4.17-line word check slid to entries[1] by hand). TWO
   REPIN CORPSES caught and recovered: the generic startswith pair hit
   the NEW gate's own previous-release pin (test_v1418 checks
   entries[1] = 1.4.17 — the pair rewrote it to 1.4.18; recovered
   surgically; the law: exempt the new gate's pins from EVERY walk,
   not just the slides), and the must-hit accounting counted hits
   AFTER earlier pairs had consumed them per line (the combined pairs
   subsume the single forms — count PRE-RUN hits, or a perfectly good
   repin reports itself unbelievable). Suite: 73/73 release-pipeline
   check commands green (42 version gates + node checks + sim probes).

**v1.4.19 — the root-cure ten-pack** (the thirteenth session of this
relay, 2026-11-19):

1. *The root-cause law.* Items 1 and 2 arrived with FIELD EVIDENCE — a
   user diag.log and a captured URL — and both were root-caused before
   a line changed: the fix targets the NAMED MECHANISM, not a guess.
   The meta-law from v1.4.18 (a green gate proves code EXISTS, not that
   a bug is CURED) gains its second half: when the evidence names the
   mechanism, pin THE MECHANISM (the ordering, the corruption path),
   and the field report becomes the regression test.
2. *The suffix-first corpse (incognito crash loop).* The "app start"
   Diag block called getCurrentWebViewPackage +
   isPasskeySupportAvailable — BOTH load the WebView provider — and
   setDataDirectorySuffix ran only AFTER, threw, and was SWALLOWED:
   :incognito kept the shared data dir and every WebView construction
   died on the main process's AwDataDirLock (four pids cycling
   app-start → crash, each falling back to the normal tabs menu). The
   suffix is now the FIRST WebView-related action in the process; a
   failure is loud (Diag + a flag Tab.buildWeb reads to refuse
   construction); the buildWeb fallback is guarded; and the EXIT
   button finishes its activities BEFORE the kill (a kill without a
   finish leaves the task record alive — the OS just restarts
   IncognitoActivity into incognito home). Learnings, three: (a) a
   "harmless diagnostics" call can be a landmine if it initializes
   what a later guard depends on — ORDER IS THE CONTRACT; (b) a
   swallowed exception around a security boundary is a silent
   misconfiguration, not a resilience win; (c) killProcess is not
   "exit" — exit is finish-first, kill-after.
3. *The loadData URL corpse (offline error page).* onReceivedError
   built the sad-page HTML and called view.loadData — loadData
   REPLACES the WebView's URL with a data:text/html,… URL,
   doUpdateVisitedHistory wrote it into t.url, tabs.json persisted it,
   and the tab reloaded the ERROR PAGE forever (the captured URL was
   literally the data: page). The error is now a native overlay with
   Retry; BOTH URL-write hooks carry a data: guard (the render-crash
   notice rode the same hole); and intent:// links with no app load
   S.browser_fallback_url (pure extractor, probe-pinned) — the
   Facebook app-link case that produced the error in the first place.
   Learning: a display mechanism that MUTATES the identity of the
   thing it displays (loadData rewrites the URL) can never be the
   error path — render errors beside the content, never into it.
4. *The animator bypass (drag-join void, round 3).* v1.4.18's posted
   requestLayout lost on a ~40-tab grid: the DefaultItemAnimator's
   predictive REMOVE (~250 ms) outlived the one-frame-later relayout
   while the animator still pinned the departed row's stale slot. The
   drag-join swap now runs with the animator OFF, restored ~350 ms
   later (a same-tick restore re-arms it before the traversal); the
   drag door loses its slide-away animation BY DESIGN (correctness
   over choreography), and a child-count/adapter-count Diag breadcrumb
   (dragJoinVerdict, pure) records the board's health for the next
   report. Learning: when a race survives a "post it later" cure, the
   cure is fighting the racy subsystem itself — take the subsystem
   out of the path for the critical window instead of scheduling
   around it.
5. *The stroke visibility floor (forced dark).* invert(1) maps #ddd
   (lum ~0.87) to ~#222 on the inverted ground — near-zero contrast
   for thin lines, dividers and hamburger glyphs. The parity walk now
   clamps light TEXT/BORDER colors down to lum 0.6 (never backgrounds,
   never the media family; the same single getComputedStyle, the same
   budgets); the inline writes store their originals in data-boltsv
   and darkClearJs restores them exactly. Learning: an inversion
   filter needs a FLOOR as well as a parity walk — the walk decides
   WHO inverts, the floor decides what SURVIVES inversion.
   (Corollary: no double quotes in MediaHook body comments —
   check_dark_js extracts every quoted literal as JS.)
6. *The token-cap ladder (AI translate).* Dense screens overflowed
   maxOutputTokens 16384 mid-array — parseBoxes read the truncation as
   "cut off". The cap now ladders 32768 → 65536 on an explicit
   MAX_TOKENS finishReason (translateCapAction, pure); the standing
   error survives as the honest end state. Learning: read the
   provider's OWN truncation signal before inventing a heuristic —
   finishReason was always in the envelope.
7. *The real-origin intercept (Bing image search).* The saved file://
   page could not run Bing's JS results runtime (opaque origin: XHR,
   fetch and document.cookie all die). The body is armed for the REAL
   results URL and shouldInterceptRequest answers that ONE main-frame
   GET with the cached bytes — origin, cookies (the v1.4.18 pump) and
   XHR all match; the take is one-shot so a later visit hits the
   network. Learning: you cannot fake an origin with <base href> —
   scripts trust document.origin, not the base; serve the bytes UNDER
   the origin they were minted for.
8. *The engine-chip logos.* The omnibox chip now shows the active
   engine's brand mark (five new vectors, brand colors never
   theme-tinted; the generic magnifier stays as the custom/unknown
   fallback), re-applied on every switch (engineLogo, pure). The
   toolbar URL-bar glyph and the home widget are the documented
   follow-up candidates.
9. *The icon-only tooltip law.* Every ImageButton in the five touched
   layouts carries android:tooltipText (the find-bar and read-aloud
   buttons were missing contentDescription entirely); the omnibox
   strip rides the appcompat TooltipCompat backport because the XML
   attribute only inflates on API 26+ (minSdk 24) — NOTE:
   androidx.appcompat.widget.TooltipCompat, the core one was removed
   from the artifact. The toolbar's long-press IS its quick action
   (audited: those listeners legitimately consume). tooltipFor
   (tooltip defaults to the contentDescription) is the pure pin.
10. *The vault add/edit door.* "Add password" row + per-entry Edit,
    riding saveEx — a hand-made entry is indistinguishable from a
    captured one; a changed identity deletes the old row first (a
    true edit, not a fork). Main process only, by construction.
11. *The temporary AI attachments.* A paperclip in the Bolt AI input
    row (browser faces, main process only): images as inline_data
    through the SHARED capped decoder (one OOM ladder for the app),
    text files as labeled parts, 3 attachments / 24k chars. MEMORY
    ONLY — consumed by the send, wiped by Clear, dead with the
    process; no restore path exists (attachCountAfter, pure). The
    attachments ride ONLY their turn (re-sending an image every
    follow-up would burn the budget).
12. *The standing sweep, v1.4.19 edition.* 38 prior version gates
    re-pinned 133/1.4.19 with dated notes (workspace
    scripts/repin_gates_1419.py — SINGLE-RUN, idempotent); every
    About-changelog position pin slid one slot down (1.4.19 landed at
    [0]); SIX hand re-points where this release legitimately moved
    pinned forms: test_screen (parseBoxes onto the ladder's per-rung
    pair, the cap parameter, chatX), test_v1398 (the onPageStarted
    window widened 3600 → 4600 — the data:-guard added lines), 
    test_v1412 (the file:// Bing pin re-pointed at the intercept),
    test_v1413 (the buildWeb fallback pin re-pointed at the guarded
    form). TWO SWEEP CORPSES caught and recovered: the first draft
    was not idempotent and a re-run DOUBLE-SLID 53 changelog pins
    (recovered by git-restoring the gates and re-running the fixed
    walk — the law: a re-point tool must skip lines already carrying
    its own dated note, and its second run must report ZERO); and the
    version-pin families number THREE, not two (plain, escaped-regex,
    and extracted-comparison `== "132"` — the v1.4.18 "both families"
    law undercounted). Suite: 69/69 release-pipeline check commands
    green (43 version gates + node checks + sim probes).

**Known-good numbers** (v1.4.19, release build): APK
2,780,582 B (budget 6,000,000; v1.4.18 was 2,769,465 — the ten-pack
cost 11,117 B, mostly the five engine-logo vectors); signature
verifies (v2 scheme) against the pinned cert; suite: all 69
release-pipeline gate commands green (the new v1419 gate among them —
its compiled probe runs transcriptions of the suffix-first ordering,
the data: guard, the intent fallback, dragJoinVerdict, the stroke
clamp, translateCapAction, shouldServeIntercept, engineLogo,
tooltipFor and attachCountAfter);
filter rule census unchanged from v1.4.9
(103,087 network rules, 1,709 on the regex path — see the hardening note
above).

**Plans / candidates for future versions:**
- The engine-chip logo (v1.4.19) could mirror on the toolbar URL-bar
  glyph and the home search widget — both are one-setImageUrl calls
  away from engineLogo(); the widget rebuild is the bigger half.
- The Startpage chip mark is a simplified blue magnifier tile — the
  exact brand vector is ONE drawable (ic_engine_startpage) to re-point
  the day a faithful path is verified offline.
- If a field report ever names a translate screen dense enough to
  overflow even the 65536 rung, the next rung is TILING (split the
  capture into regions, merge the boxes) — the finishReason→action
  function is the single place to grow it.
- The AI attachment door is browser-faces only by design (the pick
  result routes through BrowserActivity.onActivityResult); a settings
  surface could host its own ActivityResultLauncher if a report asks.
- (carried) ACTION_VIEW links from other apps still resolve into
  whichever face the OS picked (only the SEARCH doors are
  session-aware); the forward in `forwardToIncognitoIfLast` can grow a
  VIEW branch — the session record already knows the answer.
- (carried) The AI chain's fallback toast ("AI fell back to…") may
  prove noisy; a Settings toggle is the honest cut — the logcat/Diag
  channel stays regardless (it is the evidence channel).
- (carried) v1.4.13's NetSuggest endpoints are best-knowledge URLs;
  a dead endpoint degrades to no rows by design, and the map is ONE
  function (NetSuggest.endpointFor) to re-point.
- (carried) The bundled EasyList/EasyPrivacy snapshot refresh stays a
  candidate (deferred since v1.4.11; the in-app updater exists, but the
  APK baseline drifts).
- (carried) v1.4.12's VisualSearch rides Google's classic
  search-by-image POST and Bing's SBI — undocumented endpoints that
  can move; the fallback ladder (engine home page) keeps the door
  usable, and endpointFor is ONE function to re-point.
- (carried) The compound single-call sequences on tm.tabs remain
  outside the monitor law (individually-atomic reads; sweep only if a
  field report ever names one).

## License

GPL-3.0 — see [LICENSE](LICENSE).

Third-party components and their licenses: [THIRDPARTY.md](THIRDPARTY.md).
