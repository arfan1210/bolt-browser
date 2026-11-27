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
| v1.4.30 | 3,165,467 B | the field round (+6,968 B: the QR producer probe + the rotate button + the return leg + the AI web legs + popup hardening + the table re-fill); sha256 `2449f992…1784c`; release ids 456744685 (source) / 456744818 (releases); /latest serves 144/1.4.30 |
| v1.4.31 | 3,177,569 B | the performance-and-quiet round (+12,102 B: the stripe calibration + the device table/seed, the three-door exit sweep, the trim-storm table, the single-injection rework, the passkey bootstrap + lazy full layer, the glass diet, the perf diag lines); sha256 `2b205f34…72cde`; release ids 457274128 (source) / 457274328 (releases); /latest serves 145/1.4.31 |
| v1.4.32 | 3,180,301 B | the orientation round (+2,732 B: the base-angle table + the user-offset split, the compound-anisotropy acceptance, the chroma + dual-binarizer ladder, the quiet pack); sha256 `6418f6c5…36281`; release ids 459465030 (source) / 459465230 (releases); /latest serves 146/1.4.32 |
| v1.4.33 | 3,184,821 B | the field round (+4,520 B: the portrait lock + the active-array read/pad compensation + the dot-closing legs + the chooser-is-not-consent decision + the stale-backdrop pair + the door/deep matrix + the per-hook fallback diag); sha256 `acd51a8f…ff652`; release ids 459763340 (source) / 459763480 (releases); /latest serves 147/1.4.33 |

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
- THE MEASURED-IMPLICIT LAW (v1.4.31) — the QR preview's implicit
  buffer→view mapping is MEASURED once per scan session (a CPU stripe
  pattern through the session's own Surface, read back with
  getBitmap, solved per axis, whole-pattern-verified) and undone
  exactly; the device table (RMX2020 = cover-undo) and the fill-undo
  are the fallback chain; the RMX2020 (sensor=90, display=0) pair
  seeds the +270 rotation and the user's rotate button still wins.
- THE EXIT-SWEEP CADENCE LAW (v1.4.31) — CacheGuard.exit runs ONLY on
  true exits: onDestroy (door=destroy), a user request (door=user),
  or the 5-minute delayed onStop runnable (door=delayed, cancelled by
  onResume). Never at bare onStop.
- THE TRIM-STORM POLICY (v1.4.31) — a trim ≥60 sleeps ONLY background
  tabs idle >3 min; never the active/shown tab, never a media tab,
  never the two most recently used; thumbnails drop first.
- THE SINGLE-INJECTION LAW (v1.4.31) — one feature-detect per WebView:
  a successful document-start registration makes every
  evaluateJavascript twin a no-op; an unsupported WebView keeps ONE
  guarded fallback per document. Cosmetic CSS is cached per
  (host, list-generation) with commit as the injection moment and
  finish as the backstop; userscript phases share one executor.
- THE PASSKEY LAZY LAW (v1.4.31) — the ≤3 KB bootstrap rides document
  start (detection + parked ceremonies); the full ~55 KB layer loads
  once per WebView through Bolt.passkeyLoad on the first REAL
  credentials call and adopts the queue; popup children never load it
  (THE BRIDGE-FREE CHILD LAW, unchanged).
- THE WEB-CONTEXT LAW (v1.4.31) — the AI request always carries the
  standing MAX_TURNS history (Web ON or OFF); the retrieval query
  covers the conversation (current prompt + topic words from the
  previous two user turns + the last assistant reply); a bounded
  transcript (4 turns / 2k chars) rides above the SOURCES block; the
  web leg APPENDS, never replaces turns.
- THE BASE-ANGLE LAW (v1.4.32) — the scanner's upright angle is TWO
  additive pieces: the device base (a pure immutable table — RMX2020
  sensor=90 → (270 + display) % 360) and the user offset (the rotate
  button's own key, default 0). No button ever writes the base; the
  conflation that let a tap-cycle wrap delete the device default is
  gone (old qrRotOffset.* keys are never read again).
- THE COMPOUND-ANISOTROPY LAW (v1.4.32) — the calibration capture runs
  under setTransform(identity) and restores the real transform after
  the solve AND on the timeout path; net= reports the COMPOUND linear
  anisotropy (assumed implicit ∘ undo — the recipe's uniform part is
  isometric; 1.000 = square pixels); a measured solve is accepted only
  when the whole-pattern bijection passes AND the compound ratio against
  the chain prior sits in [0.98, 1.02] — a contaminated capture falls
  down the chain (measured → table → fill), never applied.
- THE CHROMA LADDER (v1.4.32) — every QR leg tries HybridBinarizer then
  GlobalHistogramBinarizer; after the full luma ladder fails, a final
  leg decodes the chroma-spread plane (|U−128|+|V−128|, half res — the
  live U/V extraction happens ONLY on that failure leg, the 4 fps
  budget holds); the gallery path rides the same legs at full res.
- THE QUIET PACK (v1.4.32) — the media core early-returns in non-top
  frames (ad iframes never own the notification/wake-lock); the passkey
  bootstrap's retry loop is bounded (≤10 tries, then parked ceremonies
  reject; the asset stays ≤3 KB); CacheGuard's destroy door is gated on
  isFinishing() (a skin/locale recreate never sweeps); the favicon +
  page-text evaluates ride a ~1200 ms idle pass cancelled by the next
  navigation; the trim-driven cache check re-measures at most once per
  5 minutes.
- THE PORTRAIT-ONLY LAW (v1.4.33) — the QR scanner activity is LOCKED
  portrait in the manifest: QR decoding is orientation-independent and
  the landscape preview path was pure liability. The base table keeps
  ONE portrait entry (RMX2020 sensor=90 display=0 → 270); display= in
  every diag is always 0.
- THE ACTIVE-ARRAY PADDING LAW (v1.4.33) — the camera buffer is
  stride-aligned (buf 1920x1088 carries a 1920x1080 ACTIVE image): the
  cover/undo math rides the ACTIVE dims and the transform applies
  postScale(buf/active) after the undo ops; net= is HONEST (a measured
  solve composes the measured implicit against the APPLIED undo — any
  other mode emits agree=unknown, never a fake 1.000).
- THE DOT-CLOSING LEG (v1.4.33) — the QR ladder's final leg closes the
  plane (close3x3 = max then min, 3x3) before the dual-binarizer core:
  ~50%-duty dot modules that mis-bin under BOTH binarizers seal into
  solid blobs; the live failure leg may ride dilate-only at half res
  (the frame budget); a hue-flat chroma plane (max spread < 8) is
  skipped, never decoded.
- THE CHOOSER-IS-NOT-CONSENT LAW (v1.4.33) — the popup overlay child
  exists ONLY for consent-shaped flows (the OAuth URL shape or an IdP
  consent path — popupDestination is PURE); every other classified
  popup (the Google account chooser included) opens a NEW TAB; a stored
  popupNewTab equal to the old default is reset (dated migration), an
  explicit override survives.
- THE STALE-BACKDROP LAW (v1.4.33) — a scroll marks the glass blur
  backdrops STALE: they paint the SOLID theme bar colour until the next
  snapshot lands; no snapshot is taken mid-scroll; after idle the first
  snapshot runs on the ~120 ms cadence. Plus the WebView leg:
  OVER_SCROLL_NEVER + setOffscreenPreRaster(true) (API 26+) — the
  bottom strip never shows unpainted canvas.
- THE DOOR/DEEP MATRIX (v1.4.33) — the delayed cache exit door is the
  cache-subtree sweep ONLY (deep=0, the line says so) and waits 10
  minutes of backgrounding; the DEEP site-data diet rides
  destroy(isFinishing-gated)/user/update alone — merely switching apps
  can never wipe logins-adjacent stores. Trim spares the FOUR most
  recent background tabs and idles the sleep threshold at 10 minutes
  (policy=idle10m-recent4).
- THE REPEATED-INJECTION GUARD (v1.4.33) — every hook fallback that
  actually fires names itself (`hook fallback name=<core|fh|shr>
  armed= feature=`); the once-per-document guards inside each script
  carry the law names (window.__boltCoreArmed / __boltFhArmed /
  __boltShrArmed) so any stray evaluate is a cheap no-op; the
  finish-time core/hook re-evaluation is gone (the cosmetic CSS keeps
  its commit-marker backstop only).

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

**v1.4.34 — THE SCANNER FIELD-OF-VIEW ROUND** (the twenty-seventh
session, 2026-11-27). Six scanner fixes off the session summary's
field concepts (the detailed field reports rode the recycled sandbox;
the laws below are the recovered root causes, each re-derived against
the v1.4.33 source), no feature work:

1. *QR — THE 4:3 STREAM LAW.* The corpse: the stream pick was the
   largest size under the 1920 cap with no aspect preference — the
   16:9 1920x1080 pair on real devices; a code held at chest height
   fell OUT of the decode plane entirely. pickStream prefers the
   aspect-4:3 candidates under the same cap for BOTH streams (the YUV
   analysis plane AND the preview buffer — the letterbox law then
   frames the preview exactly like the decode plane); isFourThree is
   the 0.02-tight landscape test; the active-array stride fallback
   grew the 4:3 leg (1440x1088 carries 1440x1080) with the 16:9 leg
   first. pickLargest is retired from the live path (kept for
   reference/tests, like pickSize).
2. *QR — THE LETTERBOX LAW.* The corpse: the recipe cover-scaled —
   the preview showed a CROP of the scene the decode plane fully
   carries (a code at the preview's edge could decode while
   invisible). The scale is min() + centered now: every pixel the
   sensor delivers, aspect-true, uniform bars on the overflow axis;
   the v1.4.27 fills assertion inverted to the fits assertion (the
   one-shot re-pin ladder + the MISMATCH mark ride); the undo chain
   (measured → table → fill) is untouched. On a buffer/view aspect
   match it degenerates to the exact cover numbers.
3. *QR — THE DUAL-PRIOR ACCEPTANCE.* The corpse: the v1.4.32 gate
   compared the measured solve against the CHAIN PRIOR's model only —
   an honest cover-implicit solve on a device outside the one-device
   table read off-window against the fill prior, was rejected as
   contaminated, and the stretch shipped via the fill undo (the 3.784x
   corpse class, still live). The gate is the DUAL-PRIOR VERDICT now:
   accept when the solve sits in-window against EITHER model (the fill
   scales or the uniform-cover k, both on the ACTIVE dims); a
   contaminated capture still reads off-window against EVERY model.
   Diag: calib ok … verdict=fill|cover; the fail line names the nearer
   prior (near=) — the table-growth evidence.
4. *QR — THE CALIBRATION CONVERGENCE RETRY.* The corpse: the stripe
   calibration was ONE shot — a single blurred/missed pattern frame
   killed the measured leg for the whole session. One bounded re-arm
   rides the failure path now (stripeWidthFor: span/128 → span/48, the
   2.7x wider pattern survives blur); the 900ms timeout still caps the
   round; the retry never loops (calibAttempts, reset per open).
5. *QR — THE SWAP LEG.* The corpse: on a HAL that bakes a rotation
   into the producer transform the pattern arrives TURNED — the
   standard row/column scans look on the wrong axis and the
   calibration can never converge, silently. One swapped-axis scan
   rides the failure path; a swapped solve is never APPLIED (the
   per-axis undo cannot express a cross-axis map) but it is LOGGED
   (`qr calib swap red=cols green=rows ix=… iy=…`) — the device
   nominates itself for the table with a rotation note.
6. *QR — THE QUARTER-SCALE CLOSING LEG.* The corpse (measured with a
   tuner grid this session): on a dot-module code under a whole-frame
   glare gradient + sensor noise, HybridBinarizer's 16px adaptive
   blocks split MODULE INTERIORS on the full and half planes (a 16px
   block sees one gradient slice of a big module) and the full-res
   closing cannot fix a gradient — every pre-1.4.34 leg misses. The
   final failure leg is the quarter-scale closed plane (halfLuma twice
   + close3x3 + the dual-binarizer core) in BOTH the live ladder
   (decodeLuma leg d) and the gallery ladder (decodeMulti's final
   block): the quarter plane aligns the blocks with the module scale
   (2 modules per block), averages the noise 4x, and the closing's 3x3
   reach spans 12 full-res pixels. Failure path only — plain QRs still
   exit on leg one; the v1.4.33 dot corpse still rides its closing
   family (the regression pin is the same || form the v1.4.33 gate
   pinned).

Gates: test_v1434.py pins all of the above (the compiled REAL
isFourThree + the transcribed pickStream probe + the fallback-leg
fixtures; the letterbox hull fixtures + the fits ladder; the compiled
REAL measuredVerdict decision table — the corpse configuration ACCEPTS
via the cover prior, the contaminated fixtures still reject; the
compiled REAL stripeWidthFor + calibRetryEligible + the rotated-raster
swap fixture (standard null, swapped solves); the compiled REAL
dense-dot fixture — the pre-1.4.34 ladder misses, the quarter leg
decodes — plus the v1.4.33 dot-corpse regression pin); the dated
re-points by hand: v1388's cover-scale pin (the letterbox law),
v1426's preview hull fixtures (the letterbox bars), v1427's fills pin
+ the D3 cover/ladder transcription (the fits law), v1429's residual
format pin (MISMATCH rides fits), v1430's diag pin + the ORDER pin
(undo → pad → center → rotate → letterbox → center), v1432's
acceptance-gate pins (the dual-prior verdict). DOCS: CHANGELOG
[1.4.34], About entries slide (1.4.34 at [0]), this narrative + the
open read-backs.

**v1.4.33 — THE FIELD ROUND** (the twenty-sixth session, 2026-11-27).
Seven fixes off the v1.4.32 field diagnostics, no feature work:

1. *QR — THE PORTRAIT-ONLY LAW.* The field: the v1.4.32 orientation
   round landed (portrait opens upright zero-tap) and the landscape
   path was exposed as pure liability — a second base-angle branch, a
   second offset key, a second failure surface, for a decode that is
   orientation-independent by construction. The scanner activity is
   LOCKED portrait in the manifest; baseAngleFor keeps ONLY the
   portrait entry (RMX2020 sensor=90 display=0 → 270); the landscape
   rows are deleted; diag display= is always 0.
2. *QR — THE ACTIVE-ARRAY PADDING LAW + the honest net=.* The field:
   a SLIGHT vertical stretch remained, with mode=table net=1.000 and
   buf=1920x1088 — the log said square pixels while the preview said
   otherwise. Two corpses: the buffer is stride-aligned (the ACTIVE
   image is 1920x1080 — cover/undo math on 1088 carries the 0.74%
   aspect error), and net= composed each mode with its OWN undo (a
   tautology). The fix reads SENSOR_INFO_ACTIVE_ARRAY_SIZE at
   openCamera, applies postScale(buf/active) after the undo ops, rides
   ACTIVE dims for uw/uh/cover/the chain prior, and net= became the
   honest measured-vs-applied agreement (agree=unknown otherwise).
   Diag: `qr active … active=… pad=…` + active=/pad= on the preview
   line.
3. *QR — THE DOT-CLOSING LEG.* The field: a chess.com QR (green DOT
   modules on black, solid finders, centre logo, screen glare) failed
   live AND gallery. Dot modules at ~50% duty mis-bin under BOTH
   binarizers; the ladder had no morphology leg. close3x3 (max then
   min) + dilate3x3 (max only) joined QrCore; decodeLuma gained the
   final closing leg, decodeMulti a final closing leg after the chroma
   leg (half-scale closed plane), decodeRobust rides both, the live
   failure leg rides dilate-only at half res, and a hue-flat chroma
   plane (max spread < 8) is skipped. Plain QRs exit on leg one —
   unchanged.
4. *POPUPS — THE CHOOSER-IS-NOT-CONSENT LAW.* The field: the Google
   account chooser opened as the overlay child with an X chip; the
   user wants the switched account in a NEW TAB. popupDestination
   (PURE): OAuth-shape or IdP-consent-path → overlay (the pref can
   still send it to a tab); everything else classified → NEW TAB.
   Migration: stored popupNewTab == old default (false) is removed
   (dated note); explicit overrides survive.
5. *SCROLL — THE BOTTOM-FLASH KILL.* The field: a temporary black/
   white strip at the bottom while scrolling. Leg (a):
   OVER_SCROLL_NEVER + setOffscreenPreRaster(true) (API 26+) on every
   tab WebView. Leg (b): the glass backdrop (perf blur w=180 h=17)
   showed STALE colour mid-scroll — on onScrollChanged the backdrops
   mark stale and paint the SOLID theme bar colour until the next
   snapshot; no snapshot mid-scroll; ~120 ms after idle the glass
   returns; the hidden-chrome skip is unchanged.
6. *CACHE DOORS + TRIM POLICY.* The field: "cache guard exit
   door=delayed … deep=1" fired after MERELY BACKGROUNDED to recents
   (1-2 minutes), wiping logins-adjacent stores. The door/deep matrix:
   the delayed door = cache-subtree sweep ONLY (deep=0, printed) after
   10 minutes; the deep diet rides destroy(isFinishing)/user/update
   alone. Trim: recency exemption 2 → 4 tabs, idle threshold 3 → 10 min
   (policy=idle10m-recent4) — the diag still showed cold restores on
   ordinary switches.
7. *PERF — THE REPEATED-INJECTION GUARD.* The field: `perf nav …
   inj=17 bytes=250441` on m.youtube.com — +2 injections (~32 KB) per
   SPA event, and nothing named the culprit. Every hook fallback now
   logs `hook fallback name=<core|fh|shr> armed= feature=`; the
   once-per-document guards inside each script carry the law names
   (__boltCoreArmed / __boltFhArmed / __boltShrArmed); the finish-time
   unconditional bolt-adcss evaluate (uncounted, invisible to the perf
   line) is REMOVED — the cosmetic CSS keeps its commit-marker backstop
   only.
8. *THE GATES.* test_v1433.py pins all of the above (the compiled REAL
   base table over the portrait entry + the landscape rows GONE; the
   compiled REAL padFactor + the 1088/1080 compound fixture — 1.007
   uncompensated, 1.000 compensated; the compiled REAL dot-closing
   probe — a 50%-duty dot fixture MISSES the pre-1.4.33 ladder and HITS
   the closing leg, the degenerate-chroma skip, plain QRs unchanged;
   the compiled REAL popupDestination decision table — the chooser rows
   newtab, the consent rows overlay; the compiled REAL backdropState +
   trimSleepDecision + padFactor probes; the per-hook diag format
   pins); repin_gates_1433.py sweeps the ladder to 147/1.4.33; the
   semantic re-points by hand with dated notes (the v1.4.32 base-table
   four-display pins, the v1.4.32 net= pin, the v1.4.31 trim pins).

**v1.4.32 — THE ORIENTATION ROUND** (the twenty-fifth session,
2026-11-26). Three QR fixes off the v1.4.31 field read-backs plus a
bounded quiet/perf pack, no feature work:

1. *QR — THE BASE-ANGLE LAW.* The field: the scanner opened wrong-side-up
   and needed a manual +90° tap EVERY session, portrait and landscape.
   Root cause: the v1.4.31 seed persisted its +270 into the SAME prefs key
   the rotate button writes — a tap-cycle wrap (270 → 0) or a reset
   destroyed the device default, and landscape was never seeded; the
   "default" was just the last tap. The fix splits the two angles: the
   DEVICE BASE is a pure immutable table (baseAngleFor: RMX2020 sensor=90
   → (270 + display) % 360 — display 0 → 270, 90 → 0, 180 → 90,
   270 → 180; unknown models → 0) and the USER OFFSET rides a new
   qrUserOffset.<sensor>.<display> key, default 0, additive on top. The
   button cycles ONLY the offset; long-press resets ONLY the offset;
   old qrRotOffset.* keys are never read again. Diag: `qr open model=…
   sensor=… display=… base=… offset=… mode=…` at open + `base=` on the
   preview line.
2. *QR — THE COMPOUND-ANISOTROPY LAW + the calibration identity.* The
   field: the preview rode a vertical stretch. Two corpses: the old net=
   measured only the undo's own x/y ratio (the log could not see the
   compound stretch), and the calibration arm posted the stripe pattern
   WITHOUT resetting the view transform — the solve measured
   implicit∘A_current, and the contaminated "measured" undo
   re-introduced the stretch it exists to undo. The fix: identity before
   the pattern post AND before getBitmap, restore after the solve AND on
   the 900 ms timeout path; net= became the COMPOUND linear anisotropy
   (assumed implicit ∘ undo — 1.000 = square pixels); the solve is
   accepted only when the bijection passes AND the compound ratio vs the
   chain prior sits in [0.98, 1.02] — else it falls down the chain
   (measured → table → fill), never applied.
3. *QR — THE CHROMA + DUAL-BINARIZER LADDER.* The field: stylized codes
   (chess.com-style dot/rounded modules, wavy lines, colour-on-colour)
   did not scan — the live path decoded ONLY the Y plane, so equal-luma
   colour modules were literally invisible, and every pass rode
   HybridBinarizer alone. Zero new deps: chromaOf builds the
   chroma-spread plane (|U−128|+|V−128|) at half resolution; the ladder
   gained a FINAL chroma leg (plain + invert) reached only after the
   whole luma ladder fails (the U/V extraction rides that failure leg —
   the 4 fps budget holds); every leg tries Hybrid THEN Global
   (GlobalHistogramBinarizer — dot/rounded modules binarize better
   globally); the gallery path gained the same legs at full res.
4. *THE QUIET/PERF PACK.* The media core early-returns when
   window !== window.top (the v1.4.31 "*" registration widened coverage
   by accident — ad iframes must not own the notification/wake-lock);
   webauthn_bootstrap's req() retry loop is bounded (≤10 tries, then
   parked ceremonies reject — incognito and bridge-free popup children
   stopped spinning every 300 ms forever; the asset stays ≤3,070 B);
   CacheGuard's exit door=destroy is gated on isFinishing() (a skin/
   locale recreate must not nuke the caches); the favicon extraction and
   the page-text capture moved to a postDelayed(~1200 ms) idle pass
   cancelled by the next navigation; the trim-driven CacheGuard check
   re-measures at most once per 5 minutes. The perf nav line is
   unchanged.
5. *THE GATES.* test_v1432.py pins all of the above (the compiled REAL
   baseAngleFor over the four displays; the compiled REAL
   compoundAnisotropy — the v1.4.30 corpse configuration reads 3.784,
   every self-consistent leg 1.000; the compiled REAL measuredAcceptable
   with the contamination fixtures — a non-identity start transform is
   rejected or corrected; the equal-luma colour QR decodes ONLY via
   chroma — every luma leg misses, both the ARGB and the live-YUV chroma
   forms hit; the dot/wavy fixtures decode through the ladder; the
   bootstrap retry bound under node); repin_gates_1432.py swept the
   ladder to 146/1.4.32; the v1.4.31 seed/net/theta pins re-pointed by
   hand with dated notes.

**v1.4.31 — THE PERFORMANCE-AND-QUIET ROUND** (the twenty-fourth session,
2026-11-25). Seven fixes, every one traced to a v1.4.30 field diag line:

1. *QR — THE MEASURED-IMPLICIT LAW.* The v1.4.30 diag proved producer=0
   constant and cover GOOD at offset=270 — yet the preview stretched
   ~3.8×. The net anisotropy of the v1.4.29-30 undo,
   (bw/vw)/(bh/vh) = 3.784, WAS the stretch: the undo assumed an
   anisotropic-FILL implicit mapping while the device renders uniform
   COVER. The fix measures instead of assuming: one stripe-pattern
   calibration per scan session (posted through the session's own
   Surface after it configures, before the repeating request — no camera
   frame can overwrite the pattern), the view bitmap's stripe runs
   solved per axis with a whole-pattern bijection check, the undo is
   the exact inverse affine. Fallback chain: measured → device table
   (RMX2020 cover-undo) → fill. The +270 offset seeds for the RMX2020
   (sensor=90, display=0) pair; the user's rotate button still wins and
   long-press resets to the device default. Diag grew mode= and net=.
2. *THE EXIT-SWEEP CADENCE LAW.* The immediate-onStop sweep (v1.3.79)
   ran the full close-out + deep diet on every backgrounding — the
   "cache guard exit … deep=1" storm. Doors now: destroy / user /
   delayed (5 min, cancelled by onResume); the door rides the diag.
3. *THE TRIM-STORM POLICY + boOffer CHURN.* The level-80 storm slept
   every background tab (the "tab hist restore … cold" lag); the sleep
   decision is now a PURE table (idle >3 min, never active/shown/media,
   never the two most recent). boOffer shows once per process, once per
   version between dismissals.
4. *THE SINGLE-INJECTION LAW + THE PASSKEY LAZY LAW.* One feature-detect
   per WebView; the media core joined the document-start registrations;
   the commit-time evaluate twins are gone; cosmetic CSS cached per
   (host, generation), commit-only with a finish backstop; one reused
   userscript executor. webauthn_compat.js (55 KB per frame) became a
   ≤3 KB bootstrap + a once-per-WebView lazy full load through
   Bolt.passkeyLoad (the adoption block drains the parked ceremonies).
   The perf nav / perf launch / perf blur diag lines carry the evidence.
5. *THE GLASS SNAPSHOT DIET + THE CACHEGUARD CADENCE.* Downscale ≤1/4 →
   luminance + content-hash → off-main StackBlur on the small bitmap;
   the 6-minute foreground walk became trim-driven checks + a 30-minute
   idle-only audit with a file-visit budget.
6. *THE WEB-CONTEXT LAW.* The keyless web leg appends (never replaces)
   a bounded context block (4-turn transcript + SOURCES) to the last
   user turn; the search query now covers the conversation (banana
   rides "add apple and orange to the table").
7. *THE GATES + PIPELINE.* test_v1431.py pins all of the above with
   compiled REAL probes (the stripe solver over fill/cover/fit
   synthetic bitmaps; implicit∘undo = identity at 1e-6 in all three
   undo modes; the trim decision table; the injection decision);
   repin_gates_1431.py swept the ladder to 145/1.4.31 with dated notes.

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

**v1.4.33 post-release: the known-good numbers.** APK 3,184,821 B
(+4,520 B over v1.4.32's 3,180,301 B — the portrait lock + the
active-array read and the pad compensation + the dot-closing legs +
the chooser-is-not-consent decision + the stale-backdrop pair + the
door/deep matrix + the per-hook fallback diag); the FULL verification
ran in this environment (the complete gates ladder incl. test_v1433 —
87/87 green via run_gates.py AND green again inside release.sh's own
rung, the full testDebugUnitTest Robolectric suite green — 13 tests incl.
the launch law; the grafted JDK21 + Gradle 8.9 + SDK 34 rebuilt via
setup_toolchain.sh after the sandbox recycle, and the stale
GRADLE_USER_HOME workerMain lock was the suite's corpse this time),
the signed build cert-verified against the pinned fingerprint
(`bcd57eb4…8AE4`), the 6,000,000 B size law held, dual-published in the
mandated order (SOURCE first release id 459763340, RELEASES last release
id 459763480), md5-verified on both download-backs AND anonymous
re-downloads on BOTH repos (sha256
`acd51a8f9440c0152b363feceef03f5195d13e40b655349e32c318f8613ff652`), and
both checkers' /latest endpoints serve 147/1.4.33. The field checks (the
portrait lock, the active=/pad= line, the chess.com scan, the chooser
new-tab, the scroll flash, the door matrix, the perf inj) remain open —
claimed nowhere.

**v1.4.32 post-release: the known-good numbers.** APK 3,180,301 B
(+2,732 B over v1.4.31's 3,177,569 B — the base-angle table + the user-
offset split + the compound-anisotropy acceptance + the chroma and dual-
binarizer ladder + the quiet pack); the FULL verification ran in this
environment (the complete gates ladder incl. test_v1432 — 86/86 green,
the full testDebugUnitTest Robolectric suite incl. the launch law — the
grafted JDK21 rebuilt via setup_toolchain.sh after the sandbox recycle),
the signed build cert-verified against the pinned fingerprint, the
6,000,000 B size law held, dual-published in the mandated order (SOURCE
first release id 459465030, RELEASES last release id 459465230), md5-
verified on both download-backs AND an anonymous re-download (sha256
`6418f6c5d8d54845…36281`), and both checkers' /latest endpoints serve
v1.4.32. The field checks (the zero-tap orientation in both
orientations, the tap-then-long-press return home, the compound net=
line, the stylized chess.com-style scan live and from gallery) remain
open — claimed nowhere.

**v1.4.31 post-release: the known-good numbers.** APK 3,177,569 B
(+12,102 B over v1.4.30's 3,165,467 B — the stripe calibration + the
device table/seed + the three-door exit sweep + the trim-storm table +
the single-injection rework + the passkey bootstrap + the glass diet +
the perf diag lines); the FULL verification ran in this environment
(the complete gates ladder incl. test_v1431 — 85/85 green, the full
testDebugUnitTest Robolectric suite incl. the launch law — after the
sandbox recycle re-fetched the android-all i6 jar into the machine-
local robolectric-deps cache from the aliyun mirror), the signed build
cert-verified against the pinned fingerprint, the 6,000,000 B size law
held, dual-published in the mandated order (SOURCE first release id
457274128, RELEASES last id 457274328), md5-verified on both
download-backs AND an anonymous re-download (sha256
`2b205f34c1a0e1e7a12a140f050b2707d4f8b3f055462a50caf897a3cfd72cde`),
and both checkers' /latest endpoints serve v1.4.31. The field checks
(the QR `mode=`/`net=` line, the diag door= cadence, the perf nav
inj/bytes drop, the real passkey ceremony through the lazy layer, the
banana-context conversation) remain open — claimed nowhere.

Field state going INTO v1.4.30 (Realme, v1.4.29): AI tables VERIFIED
GOOD; QR cover GOOD, rotation still off (the input this round fixes);
Imgur Google login succeeded but landed on google.com (the leg this
round fixes); Bolt AI had no network (the legs this round add). None
of v1.4.30's fixes are field-verified yet — the maintainer's device
read-backs below are the acceptance test, and nothing here claims
them. v1.4.31 builds on the same diagnostics; none of its fixes are
field-verified yet either.

**Open field read-backs (the acceptance list).**
- The 4:3 stream read-back (v1.4.34): `qr active 1600x1200 …` at open
  (the 4:3 pick; pad=1.000 when the 4:3 dims are stride-clean), the
  preview line's buf= riding the same aspect, and a code held at chest
  height decoding without re-aiming (the 16:9 corpse).
- The letterbox read-back (v1.4.34): the scanner shows the WHOLE
  camera scene, aspect-true, centered — bars top/bottom on a tall
  view, nothing cropped; the preview line's content= never exceeds the
  view and MISMATCH stays absent on a healthy open.
- The dual-prior read-back (v1.4.34): on a cover-style device outside
  the table, `qr calib ok mode=measured … verdict=cover` (the honest
  solve accepted); on the Realme the portrait entry rides
  (verdict=fill|cover per the measured shape); a contaminated capture
  still reads `qr calib fail … near=fill|cover|none`.
- The convergence read-back (v1.4.34): a first-fail session logs
  `qr calib retry attempt=2 width=…` and either lands
  `qr calib ok mode=measured` on the wider pattern or falls down the
  chain after the bounded timeout — never a silent one-shot death.
- The swap read-back (v1.4.34): a rotated-implicit device (if one
  exists) logs `qr calib swap red=cols green=rows …` once — the
  nomination line; send it in and the table grows.
- The quarter-leg read-back (v1.4.34): the densest dot-module codes
  (chess.com-style at arm's length) decode where the half-scale
  closing still speckled; plain QR latency unchanged.
- The portrait-lock read-back (v1.4.33): rotating the phone while the
  scanner is open NEVER changes the preview; portrait opens upright
  zero-tap (the v1.4.32 base-angle law rides); diag display= reads 0.
- The active-array read-back (v1.4.33): `qr active … active=1920x1080
  pad=1.007` at open, the preview line's active=/pad= fields, and the
  honest net/agree leg (net=1.000 on a measured solve, agree=unknown on
  table/fill); square pixels verified against a known square target.
- The dot-closing read-back (v1.4.33): the attached chess.com photo (green
  dot modules on black, solid finders, centre logo, glare) scans from the
  GALLERY and LIVE; plain QR latency unchanged.
- The chooser read-back (v1.4.33): switching Gmail accounts opens the
  chosen account in a NEW TAB (never the overlay X chip); the Imgur
  Google sign-in still ends ON imgur via the overlay return leg.
- The scroll read-back (v1.4.33): no black/white bottom strip while
  scrolling on light AND dark sites, glass and non-glass skins.
- The door-matrix read-back (v1.4.33): switch to recents and back after
  1-2 min — no exit line, warm back/forward; a real close still lands
  ~20 MB (door=destroy deep=1), and a delayed line (if the app really
  sat 10+ minutes) reads deep=0.
- The perf read-back (v1.4.33): `perf nav inj=` stays <= 2 and bytes
  <= ~2 KB on a YouTube watch session; a stray fallback names itself
  (`hook fallback name=… armed=… feature=…`).
- (carried, v1.4.32) The tap-then-long-press return home; the v1.4.31
  mode= legs ride (the compound net= line now reads the honest agree
  leg); the `qr calib ok/fail/timeout` line names the leg.
- (carried, v1.4.31) A session of tab switches produces NO `cache guard
  exit … deep=1` lines while the browser stays in use; the line appears
  once per true close (door=destroy) — and the trim storm no longer
  sleeps the four most-recent tabs (the `tabSleep … policy=idle10m-
  recent4` line names the policy).
- (carried, v1.4.31) The real passkey ceremony: a live passkey login
  still works end to end through the ≤3 KB bootstrap; a bridge-free
  context (incognito / a popup child) settles fast instead of spinning.
- (carried, v1.4.31) The Web context: with the Web chip ON, "add apple
  and orange to the table" AFTER a banana question answers with a
  banana+apple+orange table and cited [n] sources.
- (carried) The Web citations: with the Web chip ON, a current-events
  question returns a cited answer, and the confirm appears exactly once
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
