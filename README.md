# Bolt Browser

A fast, private, Android-first WebView browser. No trackers, no bloat — the
whole app is a **~3.3 MB APK**.

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

**2026-11-24 (v1.4.30): THIS LOG WAS COMPRESSED.** The per-version
session narratives (v1.3.x through v1.4.29, ~2,400 lines) are summarized
into era histories below; the FULL mechanics of every historical round
live in its gate (`scripts/test_v<version>.py` — the dated re-point
notes inside those gates ARE the history) and in the per-release
CHANGELOG.md. Nothing load-bearing was dropped: every current law is
still named, every known-good number is still recorded, the
environment lessons and the wipe story survive.

**The workflow law.** Every behavioural change ships with its own gate
(`scripts/test_v<version>.py`) that pins it forever; older gates may be
re-pointed only with a dated note when a later change legitimately moves
their target. Before any release: ALL gates green (a sweep re-points
every prior gate to the current versionCode/versionName first — see
`scripts/repin_gates_<version>.py`), then the full Robolectric unit
suite incl. the launch smoke, then `release.sh <ver>` builds, checks
the APK size law (hard fail over 6,000,000 B), checks the signing cert
against the fingerprint baked into the script, and dual-publishes. The
signing key NEVER lives in this repo — credentials come from a
gitignored `local.properties` or `BOLT_*` env vars.

**THE TOKEN LAW.** The release pipeline's GitHub credential rides the
`GHTOKEN` environment variable (or a `.ghtoken` file) at RUN TIME ONLY:
never committed, never logged, never written to a tracked file, never
stored in a remote URL, never in a release body, never in a backup
bundle. Before every push: a zero-hit token scan over tracked files and
a clean-remote check. (The token rotates; chat exposure is accepted —
the only hard rule is that it never lands in either public repo.)

**The signing identity (pinned).** Keystore: PKCS12, alias `bolt`, DN
CN=Bolt Browser, OU=Bolt, O=Bolt, L=Dhaka, C=BD, valid 2026-10-26 →
2054-03-13. Certificate SHA-256
`BCD57EB42CA770A340F78AD64B56999D6238F88AB194CC8C787EE46082938AE4`
(the v1.4.8/v1.4.9 signer — the SAME cert verifies every release since;
`release.sh` hard-fails on any mismatch). Keystore file SHA-256
`0eadc7c2…edec3948`. Password history: the original leaked in private
chat and was rotated (v1.4.9 session, `keytool -storepasswd`; PKCS12
keys ride the store password); the current secret lives only in the
maintainer's workspace + offline backup.

**Known-good numbers (release builds, the size law budget 6,000,000 B):**

| Version | APK bytes | Notes |
| --- | --- | --- |
| v1.4.23 | 3,325,166 B | the critical rebuild; first release under the launch law |
| v1.4.24 | 3,037,761 B | the big size reclaim |
| v1.4.25 | 3,140,403 B | +102 KB = the CORE math font rebuy |
| v1.4.26 | 3,147,999 B | the four-fix round (buffer pin era) |
| v1.4.27 | 3,150,771 B | the four-fix round (CSS exemption + real grids) |
| v1.4.28 | 3,155,927 B | the security + visibility round |
| v1.4.29 | 3,158,499 B | the reachability round; sha256 `7f5e150c…3576f`; release ids 456367850 (source) / 456367966 (releases); /latest serves 143/1.4.29 |

Every release's signature verifies against the pinned cert above; every
release is md5/sha-verified on the download-back of BOTH repos and the
anonymous live downloads are re-checked before the release is declared.
Publish order is a LAW: the SOURCE repo first, the RELEASES repo last.

**The eras.**

*v1.3.x — the foundation.* Pure-Java app, zero Gradle dependencies for
years, then the deliberate adds (ZXing core for the authenticator, the
commonmark + JLatexMath pair for chat rendering). The standing UX
surface (tabs, workspaces, adblock, userscripts, translate, TOTP
authenticator, QR toolkit, offline page marks) and the first gate
files. The adblock engine's shape (Aho-Corasick literal scan + the
small regex remainder) was proven here with a 1,013,884-case
equivalence run and a 244 MB → 49 MB heap measurement. The v1.4.9
hardening four-pack (pattern diet, userscript leak delete, the
TabManager thread law, the keystore rotation) closed the era.

*v1.4.0–14 — the feature era.* Screen translate pipelines, the AI
bridge (Gemini + OpenAI-compatible), media controls/PIP, the download
UI, the data saver, visual search, the QR scanner (v1.3.37) and its
gallery engine, workspaces/sleep, backup/restore. The gates grew to
match; `scripts/run_gates.py` runs the whole ladder.

*v1.4.15–22 — the reliability era.* The token-budget and free-tier
patience laws in the AI path, the chain law (a custom model still
carries default + fallbacks), the markdown/LaTeX chat renderer, the
translate-model picker, the attachment lifecycle, the body-carrier law
(a request with a body is a user action — never stubbed, never
blocked), the raw-response evidence channel, the 503 ladder. This era
ended in the CRITICAL REBUILD: the maintainer's workspace was wiped
and v1.4.22 was reconstructed from the shipped APK by decompiling R8
output — the launch law (v1.4.23) exists so that can never happen
silently again.

*v1.4.23–29 — the law era (the current regime).* Each round named its
laws; the list below is the CURRENT law set (name → the one line):

- THE LAUNCH LAW — the Robolectric launch smoke runs the real
  Application.onCreate into the first home render before anything
  signs; a FATAL EXCEPTION fails the release before any user does.
- THE GATE LAW — every behavioural change ships pinned; sweeps re-point
  prior gates with dated notes; dated re-points are the only honest way
  a target moves.
- THE SIZE LAW — the release APK stays under 6,000,000 B, hard-failed
  in the pipeline.
- THE CERT PIN — `apksigner` output must equal the pinned SHA-256 above
  or the release dies.
- THE TOKEN LAW — see above.
- THE NO-SECRET-DIAG LAW — no URL, code, state, token, cookie, API key
  or query text ever rides a Diag line, toast, or release report (the
  popup lines name lifecycle events + base domains only).
- THE BODY-CARRIER LAW — requests that carry a body (uploads, presigned
  PUTs, CORS preflights) are never stubbed or adblock-blocked.
- THE IDENTITY-AGREEMENT LAW — a popup/child carries the opener's UA +
  client-hints metadata; the IdP must see the same browser in both.
- THE BRIDGE-FREE CHILD LAW — a consent popup child is a plain
  WebView: no Bolt bridge, no hooks, no page-surface privileges.
- THE NETWORK-PASS-ONLY LAW (v1.4.30) — that child may ride the
  adblock ENGINE's network judgment only (no JS injection, no cosmetic
  engine, no userscripts; body carriers untouched).
- THE AUTH-HOLD LAW (v1.3.100, v1.4.29 public) — the OAuth URL-shape
  classifiers (`Tab.isAuthRequestUrl/isAuthResponseUrl`) are one law
  shared by the UA hold and the popup allowlist; they never drift.
- THE NO-SHRINK LAW (v1.4.29) — AI table cell type never scales down;
  a wide table overflows honestly and scrolls horizontally.
- THE VIEW-SPACE TRANSFORM LAW (v1.4.29) — TextureView.setTransform
  consumes view-local geometry: undo the implicit stretch FIRST, then
  rotate/cover/center.
- THE PRODUCER-TRANSFORM LAW (v1.4.30) — the HAL can bake a rotation
  into the preview picture (the SurfaceTexture producer transform
  TextureView already applies): probe it on the first frame + after
  every re-pin and SUBTRACT it; the user's rotate button adds last and
  persists per (sensor, display) pair.
- THE RETURN-LEG LAW (v1.4.30) — an OAuth popup child that committed
  the RP callback is flowCompleting: a ~6s watchdog, any left-RP
  commit, and ANY close (its own window.close included) all close the
  child and refresh the opener; the idle retire is navigation-aware.
- THE SECOND-POPUP LAW (v1.4.30) — a flowCompleting child is never
  retired by a fresh window.open (refuse + toast); ordinary children
  keep retire-and-replace.
- THE WEB-LEG LAWS (v1.4.30) — the AI web search is opt-in (Web chip,
  default OFF), confirmed once per process (the question + public page
  text leave the device, metered included), NEVER in incognito; the
  grounding tools ride the CHAT body only; refusals fall through the
  honest ladder (keyless retrieval → an explicit unavailable error);
  the key and the query never appear in any diag/toast.
- THE IDEMPOTENT RE-FILL LAW (v1.4.30) — a chat bubble re-fill clears
  first and preserves each table scroller's scrollX by table index;
  the grid never claims the copy gesture.

**Environment lessons (merged — every one paid for itself once).**

- THE SANDBOX RECYCLES. The workspace (checkout, toolchain, keystore)
  can vanish between sessions. Hence THE BACKUP LAW: before any risky
  operation, a `git bundle` + `.patch` of the current state goes to the
  persistent `download/` directory. Restore is `git fetch <bundle>` +
  `merge --ff-only`, `scripts/setup_toolchain.sh` rebuilds the grafted
  JDK 21 + Gradle 8.9 + Android SDK 34, and the keystore is re-verified
  against the file SHA-256 + cert fingerprint above BEFORE any use.
- The toolchain is grafted, not system: JDK 21 at `../jdk21`, Gradle
  8.9 at `../gradle-8.9`, the SDK at `sdk/`; `GRADLE_USER_HOME` is the
  workspace-shared cache (a fresh `~/.gradle` re-resolves every
  dependency); the gates that run `javac` or read the build cache need
  `$JAVA_HOME/bin` on PATH and `GRADLE_USER_HOME` exported (a bare run
  fails environmentally, not semantically).
- `settings.gradle` orders the aliyun mirrors above mavenCentral (the
  documented 2026-11 health-flip adaptation: repo.maven.apache.org 429'd
  every HEAD from here).
- Robolectric: the android-all jar is vendored under `robolectric-deps`;
  table geometry probes run detached (no window) — a view's post queue
  never drains there, and the shadow scroll APIs differ (the
  TableScrollTest documents the honest detached leg).
- APK size levers, in the order they were pulled: font subsetting, PNG
  → vector engine logos, the math-font core/split (the optional pack
  downloads on demand, ~289 KB). The EasyList/EasyPrivacy snapshot
  refresh remains the next lever.
- The 30-minute rule for AI sessions: split the work, save, stop, let
  the maintainer say continue — a timed-out session saves nothing.

**The wipe story (one paragraph).** The v1.4.22 release was rebuilt
from the shipped APK: the workspace died mid-session and the round was
reconstructed by decompiling R8-obfuscated bytecode back to source; it
looked fine in every static gate and crashed on launch on the user's
device. That corpse produced the launch law, the backup law (bundles +
patches in `download/` BEFORE risky ops), and the keystore discipline
(re-verify the file hash + cert fingerprint after any restore, before
any use). The v1.4.29 session then proved the recovery path end to
end: a fresh sandbox restored losslessly from the bundle + keystore
backup, rebuilt the toolchain with one script, and re-ran the FULL
verification (gates + suite + signed build + cert check + size law)
before publishing.

**v1.4.30 — THE FIELD ROUND** (the twenty-third session, 2026-11-24).
Six fixes from the maintainer's Realme read-backs on v1.4.29:

1. *QR — THE PRODUCER-TRANSFORM COMPENSATION + THE USER'S ROTATION.*
   The v1.4.29 field state was "cover GOOD, no black bars, rotation
   still ~90 deg off": the geometry was right, so the ANGLE input had
   to be lying. Ranked suspects: (a) the HAL encodes a display rotation
   into the SurfaceTexture PRODUCER transform — TextureView ALREADY
   applies it internally when it draws, so our theta added a rotation
   the picture already had; (b) the producer overrode the pinned
   buffer size; (c) SENSOR_ORIENTATION mis-report. The fix is three
   legs: L1 — the first delivered frame and every frame after a re-pin
   read `getTransformMatrix` and `extractProducerRotation` (PURE:
   atan2(m[1], m[0]) snapped to quarter-turns; the y-flip sign
   deliberately ignored — a flip is not a rotation; NaN/junk → 0) and
   the angle is SUBTRACTED: `theta_eff = (sensor - display - producer
   + 720) % 360`; L2 — the scanner's top bar grew a rotate button
   (tap = +90, PERSISTED per sensor/display pair under
   `qrRotOffset.<sensor>.<degrees>`, long-press = reset, a live badge
   pill); L3 — the diag line grew `producer=` and `offset=` (MISMATCH
   still marks ONLY a real cover failure). The compiled REAL probe
   runs four quarter-turns x both flip signs + identity + junk +
   mirror. Probe: `scripts/test_v1430.py` section B.
2. *OAUTH — THE RETURN LEG (the Imgur come-home).* The field state:
   Google consent SUCCEEDED in the popup child, then the browser fell
   back on google.com instead of imgur — nothing watched for the
   callback. Now: a main-frame commit satisfying
   `Tab.isAuthResponseUrl` on the OPENER's base domain flags
   flowCompleting and arms a ~6s return watchdog; a commit that leaves
   the RP base (a fresh IdP leg) or the watchdog firing closes the
   child and refreshes the opener (reload when the tab still IS the
   RP, else loadUrl the RP base); ANY close of a completing child (its
   own window.close, the chip, Back, the watchdog) refreshes too —
   the one teardown carries it. The unclassified-child timeout went
   navigation-aware (a real navigation cancels it — only a truly idle
   about:blank child retires). Diag: `popup return door=… rp=…` —
   base domains only, NEVER a URL/code/state. The PURE decision tables
   (`returnDecision`, `secondPopupPolicy`) compile from the REAL Java
   and run in the gate.
3. *BOLT AI — THE WEB LEGS.* The standing read-back: "Bolt AI has no
   network — it only knows its training data." Zero new dependencies:
   the panel grew a Web chip (default OFF; pref `bolt_ai`/`web`), and
   a typed question grounds two ways. L1 (Gemini keys): the CHAT body
   carries `tools=[{"google_search":{}}]` — never the vision/translate
   bodies — and `groundingMetadata.groundingChunks` ride the reply as
   a numbered "Sources:" tail the markdown machinery renders as
   tappable links; a 400/404/429 whose body blames
   grounding/tools/quota retries bare, falls to L2, and only then
   says the honest "web search unavailable on this key/model". L2
   (everything else incl. OpenAI-compatible): the keyless ladder
   html.duckduckgo.com → lite.duckduckgo.com → bing.com (mobile UA),
   PURE `parseResults` (redirect-unwrapping, entity-decoding, top 5),
   the top 2 pages fetched (15 s / 200 KB caps) through PURE
   `htmlToText` (script/style/nav chrome dropped whole, 6k chars),
   a numbered SOURCES block prepended with a cite-[n] instruction.
   Privacy: one confirm per process names exactly what leaves (the
   question + public page text, metered included); NEVER in incognito
   (`BrowserActivity.aiWebAllowed`); the API key and the query text
   never ride a diag line or toast.
4. *POPUP LIFECYCLE HARDENING.* A flowCompleting child is never
   retired by a second window.open (refused + toasted — the return is
   the flow's most fragile moment). New Settings row "Same-site
   popups open in a new tab" (default OFF — the overlay child, which
   postMessage/window.opener flows NEED; the new-tab path fires at
   the FIRST classified navigation only). The child may now ride the
   adblock engine's NETWORK pass only (a minimal
   `shouldInterceptRequest` against the child's own host: main frames
   and body carriers never touched — the posture is pinned in the
   gate and stated here).
5. *CHAT TABLES — SCROLL + COPY.* `fillMarkdown` is an idempotent
   re-fill (stale children no longer stack below the fresh render)
   and each table scroller's scrollX is captured BY TABLE INDEX
   before the clear and restored after (attached: the view's post, so
   the clamp runs after the layout pass; detached probe: an explicit
   measure-at-bubble-width → layout → scroll leg). The grid and its
   wrapper are pinned non-clickable / non-long-clickable — a
   long-press that lands ON a cell copies the bubble's raw source.
   TableScrollTest grew the two probes.
6. *THE README COMPRESSION.* This file: ~159 KB → ≤60 KB, era
   summaries instead of per-version narratives; the laws, numbers,
   lessons, wipe story and open read-backs kept; the mechanics live
   in the gates.

Field state going INTO v1.4.30 (Realme, v1.4.29): AI tables VERIFIED
GOOD; QR cover GOOD, rotation still off (the input this round fixes);
Imgur Google login succeeded but landed on google.com (the leg this
round fixes); Bolt AI had no network (the legs this round add). None
of v1.4.30's fixes are field-verified yet — the maintainer's device
read-backs below are the acceptance test, and nothing here claims
them.

**Open field read-backs (the acceptance list).**
- The QR rotation line: on the reported device, `qr preview view=… buf=…
  sensor=… display=… producer=… offset=… theta=… content=… scale=…` —
  upright, full-cover, NO MISMATCH; the rotate button produces a
  persisted, reproducible nudge if a device still misbehaves.
- The Imgur return leg: the real Google consent on a test account ends
  ON imgur (opener reloaded), the child closes itself or via the
  watchdog, `popup return door=…` shows the path. Never paste cookies,
  account details, codes, states or tokens into any report.
- The Web citations: with the Web chip ON, a current-events question
  returns a cited answer (Gemini: the Sources tail; other providers:
  the SOURCES-block [n] markers), and the confirm appears exactly once
  per run; incognito never asks.
- (carried) The wide-table read-back on a phone; the popup prompt and
  always-allow pref exercises.

**Plans / candidates for future versions (v1.4.30 edition):**
- THE LAUNCH LAW stays forever.
- The producer-transform law wants more device read-backs: does the
  producer rotation actually explain the Realme's 90° (the diag's
  `producer=` value tells us instantly), or was it the sensor
  mis-report (the user offset then carries it — and the read-back
  tells us which)?
- The grounding quota behavior wants one deliberate read-back: a free
  Gemini key driven past its grounding quota — confirm the bare-retry
  → keyless → honest-error ladder fires exactly as gated.
- The popup return watchdog wants its timing read-back on a slow
  network (6 s is a guess that must meet real consent pages; the door
  lines will say whether the watchdog fires before the RP's own
  window.close).
- (carried) The jlatexmath optional pack lands at ~289 KB one-time;
  the EasyList/EasyPrivacy snapshots remain the next APK-size lever.
- (carried) The engine-chip logo could still mirror on the toolbar
  URL-bar glyph and the home search widget.
- (carried) ACTION_VIEW links from other apps still resolve into
  whichever face the OS picked.
- (carried) The bundled EasyList/EasyPrivacy snapshot refresh stays a
  candidate.


## License

GPL-3.0 — see [LICENSE](LICENSE).

Third-party components and their licenses: [THIRDPARTY.md](THIRDPARTY.md).
