# Bolt Light Browser — R8 rules (v1.3.41, field report bug 6: APK size)
#
# The 1.3.40 APK grew to 4.85 MB with classes.dex alone at 2.94 MB
# compressed (61%): the app shipped UNMINIFIED — no code shrinking at all
# since v1.0. R8 + resource shrinking is the single biggest lever
# (see the size table in README "APK size watch").
#
# Most reflective surfaces are already covered by the merged defaults
# (manifest components, enum values()/valueOf(), Parcelable CREATORs, and
# the SDK's own @android.webkit.JavascriptInterface member rule) — but the
# whole media-hook / passkey / userscript protocol dies SILENTLY if any of
# that changes underneath us, so the fragile surfaces are pinned here
# explicitly. Verify after every R8 bump (see "R8 verification" in README):
#   unzip -p app-release.apk classes.dex | strings | grep -c pwSubmit
#   (must be >= 1: the WebView finds bridge methods by NAME + annotation)

# The WebView JavaScript bridge: methods discovered by NAME + by the
# @JavascriptInterface ANNOTATION at runtime (Chromium reflects over the
# class). Losing either the names or the annotations kills background
# playback, the media notification, passkeys, OTP fill, greasemonkey
# storage — everything — with zero crash to trace it by.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*

# Crash report readability: keep line numbers in the stack traces and the
# source-file name attribute. Costs a few KB; saves whole-session debug
# rabbit holes on field reports ("line 4213 of what?").
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# zxing: only the QR reader/writer are used (QrCore). Everything else
# (1D format parsers, DataMatrix, PDF417, encoders for those formats) is
# unreferenced and tree-shakes away — no rules needed, the direct imports
# keep the used graph. Noted here so nobody adds a keep-all rule later.

# v1.4.21 — jlatexmath-android: the TeX parser resolves COMMAND NAMES to
# atom classes through XML-driven tables and some Class.forName lookups
# (TeXFormulaParser's CreateCommand/CreateTeXFormula paths), so an
# aggressive tree-shake can drop atoms the tables reference. The engine is
# kept whole (~325 KB of classes; the assets ride regardless) — the APK
# stays under the 6 MB law with margin, and a missing-atom crash in chat
# would cost more than the bytes.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
