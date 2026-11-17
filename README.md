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
releases page.

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

**Environment notes** (the sandbox toolchain, for fresh sessions): the
build needs JDK 21 with `javac` (the sandbox's `java` is a JRE — extract
`openjdk-21-jdk-headless` and graft, see the pattern in
`scripts/setup_toolchain.sh`; a working copy lives at
`/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64` in the maintainer's
sandbox), the Android SDK 34 (`sdk/`), and Gradle 8.9 (`gradle-8.9/`) —
all OUTSIDE the repo. If Gradle fails with "Could not read workspace
metadata", delete `.gradle-home/caches/8.9/transforms` and re-resolve.
`local.properties` in the working clone carries `sdk.dir` + the signing
credentials; it is gitignored and must never be committed.

**Known-good numbers** (v1.4.10, release build): APK 2,747,009 B (budget
6,000,000); signature verifies (v2 scheme) against the pinned cert; suite:
65/65 commands green across 34 version gates + static checks + compiled
probes; filter rule census unchanged from v1.4.9 (103,087 network rules,
1,709 on the regex path — see the hardening note above).

**Plans / candidates for future versions:**
- v1.4.11 candidates: refresh the bundled EasyList/EasyPrivacy snapshots
  (they age; the in-app updater exists, but the APK baseline drifts);
  consider the same pattern-diet treatment for the cosmetic selector
  storage if its memory ever shows up in profiles.
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
