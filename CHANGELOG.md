# Changelog

Notes for releases published from this repository. Versions before v1.4.10
were released from the maintainer's earlier private repository — their full
notes live on the [releases page](https://github.com/arfan1210/bolt-browser-releases/releases).

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
