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

**Known-good numbers** (v1.4.12, release build): APK 2,754,345 B (budget
6,000,000); signature verifies (v2 scheme) against the pinned cert; suite:
62/62 release-pipeline check commands green (53 python gates + 9 node
checks) across 36 version gates; filter rule census unchanged from
v1.4.9 (103,087 network rules, 1,709 on the regex path — see the
hardening note above).

**Plans / candidates for future versions:**
- The bundled EasyList/EasyPrivacy snapshot refresh stays a candidate (it
  was on the v1.4.11 list and was DEFERRED — the stability four-pack took
  the release; the in-app updater exists, but the APK baseline drifts).
- v1.4.12's VisualSearch rides Google's classic search-by-image POST and
  Bing's SBI — both are undocumented endpoints that can move. If a field
  report names a dead route, the fallback ladder (engine home page) keeps
  the door usable, and the endpoint map is ONE function (endpointFor) to
  re-point; consider a Lens `uploadbyurl` path for images that already
  have a URL (long-press an image → search by image) as a follow-up.
- The engine chip re-runs a live query on switch, but a prefilled URL
  only switches silently — a small "engine: NAME" toast on every switch
  (not just the URL case) would make the state change louder.
- The group sheet's long-press Move up/Move down replaced the drag; if a
  field report misses the gesture, a RecyclerView-backed sheet could
  restore ItemTouchHelper reordering — the door (showMemberMove +
  reorderGroupMember) is already the wired backend for it.
- The compound single-call sequences on tm.tabs (indexOf-then-add in the
  TabsActivity drag paths, cached size()+get() in the swipe walk) remain
  outside the monitor law — they are individually-atomic reads whose
  worst case is a one-off cosmetic drift or a caught IOOBE, not the
  iterator corpse; sweep them only if a field report ever names one.
- `inferType` could grow a `Sec-Fetch-Dest` check the day WebView's
  `getRequestHeaders()` starts exposing it (it is the authoritative
  signal; Accept is the best available today).
- The `byHost` maps are cleared+rebuilt in place during a filter-list
  reload while match threads may be mid-read (pre-existing since the
  first release; the automaton is already swapped atomically as one
  volatile reference — the maps could ride the same pattern if it ever
  bites).
- On-device memory measurement of the pattern diet (the gate proves the
  JVM ratio; a heap dump on a real device would put a hard number in the
  release notes).

## License

GPL-3.0 — see [LICENSE](LICENSE).

Third-party components and their licenses: [THIRDPARTY.md](THIRDPARTY.md).
