# Third-party components

Bolt's own code is original work. The following third-party components are
bundled or used:

## Bundled filter lists

| File | What | License | Upstream |
|---|---|---|---|
| `app/src/main/assets/filters/easylist.txt` | EasyList ad-blocking filter list (unmodified snapshot, `Version: 202610260831`) | Dual-licensed **GPL-3.0-or-later** / **CC BY-SA 3.0-or-later** | https://easylist.to/ · https://github.com/easylist/easylist |
| `app/src/main/assets/filters/easyprivacy.txt` | EasyPrivacy tracker-blocking filter list (unmodified snapshot, `Version: 202610260831`) | Dual-licensed **GPL-3.0-or-later** / **CC BY-SA 3.0-or-later** | https://easylist.to/ · https://github.com/easylist/easylist |

Both lists are © The EasyList authors, redistributed unmodified with their
original headers intact. The license texts:
[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.txt) ·
[CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/legalcode.txt).

## Dependencies (Apache-2.0)

| Library | Version | License | Notes |
|---|---|---|---|
| `androidx.appcompat` | 1.7.0 | Apache-2.0 | UI compatibility |
| `androidx.core` / `androidx.annotation` | 1.13.1 / 1.8.0 | Apache-2.0 | core utilities |
| `androidx.recyclerview` | 1.3.2 | Apache-2.0 | lists & grids |
| `androidx.viewpager2` | 1.1.0 | Apache-2.0 | speed-dial pager |
| `androidx.media` | 1.7.0 | Apache-2.0 | background playback |
| `androidx.webkit` | 1.14.0 | Apache-2.0 | WebAuthn/WebView bridge APIs |
| `com.google.zxing:core` | 3.5.3 | Apache-2.0 | QR encode/decode (pure-Java module) |
| `org.jetbrains.kotlin:kotlin-stdlib` | 1.9.24 | Apache-2.0 | Kotlin support |

## Dependencies (BSD-2-Clause)

| Library | Version | License | Notes |
|---|---|---|---|
| `org.commonmark:commonmark` | 0.24.0 | BSD-2-Clause | Markdown parser for Bolt AI chat rendering (pure Java, zero resources) |
| `org.commonmark:commonmark-ext-gfm-tables` | 0.24.0 | BSD-2-Clause | GFM pipe-table extension for the same renderer |
| `org.commonmark:commonmark-ext-gfm-strikethrough` | 0.24.0 | BSD-2-Clause | GFM `~~strikethrough~~` extension (v1.4.21) |

## Dependencies (GPL-2.0 with linking exception)

| Library | Version | License | Notes |
|---|---|---|---|
| jlatexmath-android (thin AAR) | 0.2.0 | **GPL-2.0 + linking exception** | Full TeX rendering (matrices, cases, align) for Bolt AI math — a fork of [JLatexMath](https://github.com/opencollab/jlatexmath) packaged for Android by noties; the linking exception (added upstream after community discussion) permits use in GPL-3.0 Bolt. v1.4.24 ships it as a THIN AAR (`app/libs/jlatexmath-thin.aar`, reproducibly built by `scripts/thin_jlatexmath.py` from the pinned upstream artifact): the engine classes and XML descriptors ride the APK, the 30 bundled TTF font binaries (455,768 B — ~470 KB) were stripped and download on demand on first math use from the upstream repo's own tagged assets (jsDelivr + raw.githubusercontent, `util/MathFonts.kt`), and the `JLatexMathAndroid` resource-loader seam class is re-implemented in the app's source tree (same package, same public API) to fall through from APK assets to the downloaded pack. The font pack files are byte-identical to the upstream AAR's (same tag); the licences directory of the original AAR stays bundled. Initialized lazily; its auto-init provider is removed from the merged manifest. Upstream: https://github.com/noties/jlatexmath-android |

The engine logo drawables for DuckDuckGo, Brave and Startpage are rendered
from the [simple-icons](https://simpleicons.org/) CC0 outlines with each
brand's table color; Bing's mark is bing.com's own Fluent icon shape.
Trademark rights in those marks belong to their respective owners.

## Downloaded at runtime (not bundled)

- **tesseract.js** (Apache-2.0) + `tessdata_fast` models — fetched once on
  demand from jsDelivr for on-device screen OCR; nothing ships in the APK.

## Platform

- Built against the Android SDK / AndroidX, runs on the system WebView
  (Chromium). Not affiliated with Google, EasyList, or any other project
  named above.
