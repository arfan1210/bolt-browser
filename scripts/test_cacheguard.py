#!/usr/bin/env python3
"""test_cacheguard.py — v1.3.76 release gates for the two new guards.

G1-G5: CACHE GUARD (the field report: "watching videos on facebook and
        youtube starts accumulating storage in the browser data, maybe the
        cache based storage for temporary data architecture is not holding
        on effectively").
G6-G8: THE OSCILLATION KILL (the same report's "sometimes scrolling lags
        and the toolbars appear-disappear quickly and won't let me scroll,
        making me stuck in a like oscillating state").

Every probe is a static source contract on the shipped code — the same
discipline as test_sitewipe.py: blast radius is verified BY CONSTRUCTION
(what the code may touch), not by what it usually touches.
"""
import pathlib
import re
import sys


def main():
    root = pathlib.Path(".")

    # ---------- G1: the guard exists and is Kotlin (the language rule) ----------
    cg_path = root / "app/src/main/java/com/bolt/browser/util/CacheGuard.kt"
    if not cg_path.exists():
        print("FAIL: util/CacheGuard.kt does not exist")
        return 1
    cg = cg_path.read_text(encoding="utf-8")
    if "object CacheGuard" not in cg:
        print("FAIL: CacheGuard is not a Kotlin object")
        return 1

    # ---------- G2: the allowlist discipline (lesson 37/39: blast radius by
    # construction) — ONLY cache-by-construction subtrees, never a store ----------
    m = re.search(r"private val SUBTREES = arrayOf\((.*?)\)", cg, re.S)
    if not m:
        print("FAIL: CacheGuard has no SUBTREES allowlist")
        return 1
    subs = m.group(1)
    for must in ('"Cache"', '"Code Cache"', '"GPUCache"',
                 '"Service Worker" + File.separator + "CacheStorage"',
                 # v1.3.78: the invisible growers the v1.3.77 sweep never saw
                 '"blob_storage"', '"Crashpad"', '"GrShaderCache"', '"ShaderCache"'):
        if must not in subs:
            print(f"FAIL: SUBTREES lacks {must}")
            return 1
    for forbidden in ("IndexedDB", "Local Storage", "Cookies", "Web Data",
                      "databases", "File System", "Sessions"):
        if forbidden in subs:
            print(f"FAIL: SUBTREES touches {forbidden} — that is a STORE, "
                  "not a cache; the guard must never delete it")
            return 1

    # ---------- G3: the budgets + the rate limit (v1.3.77: sized from the
    # user's storage law — "within 50 MB total, ideally under 40 while
    # using, around 20 after closing, now sitting at 60" — NOT from a
    # device-size guess; the v1.3.76 150/300 MB budgets never fired) ----------
    if "24L * 1024 * 1024" not in cg:
        print("FAIL: the SOFT budget (24 MB) is missing")
        return 1
    if "40L * 1024 * 1024" not in cg:
        print("FAIL: the HARD budget (40 MB) is missing")
        return 1
    if "MIN_INTERVAL_MS = 2 * 60 * 1000L" not in cg:
        print("FAIL: the mid-session audit rate limit (2 min) is missing")
        return 1

    # ---------- G4: the windows (v1.3.77 policy) ----------
    # mid-session must use the API path (safe with LIVE renderers).
    # clearCache is an INSTANCE method with an app-global effect (SDK 34)
    if "web?.clearCache(true)" not in cg:
        print("FAIL: the mid-session trim must ride a WebView instance clearCache(true)")
        return 1
    # and it must be posted to the main thread (clearCache is a UI-thread API)
    if not re.search(r"main\.post\s*\{[^}]*web\?\.clearCache\(true\)", cg, re.S):
        print("FAIL: clearCache(true) must be posted to the main thread")
        return 1
    # the mid-session SOFT set sweeps the renderer-Tolerant caches only:
    # SW trees + GPUCache + Media Cache — never the bare HTTP cache (the
    # API owns that) and never Code Cache (that is the HARD-set price)
    m2 = re.search(r"private val MID_SET = arrayOf\((.*?)\)", cg, re.S)
    if not m2:
        print("FAIL: CacheGuard has no MID_SET")
        return 1
    if '"Cache"' in m2.group(1) or '"Code Cache"' in m2.group(1):
        print("FAIL: MID_SET must not delete the HTTP cache / Code Cache "
              "at SOFT (API path / HARD set own those)")
        return 1
    # v1.3.78: blob URLs are PAGE STATE while the page lives — deleting a
    # not-yet-read blob file under a live page breaks it; startup/exit only
    if '"blob_storage"' in m2.group(1):
        print("FAIL: blob_storage must NEVER join MID_SET (a live page's "
              "blob file is not renderer-tolerant to lose)")
        return 1
    for must in ('"Service Worker" + File.separator + "CacheStorage"',
                 '"GPUCache"', '"Media Cache"'):
        if must not in m2.group(1):
            print(f"FAIL: MID_SET must sweep {must} at SOFT (v1.3.77)")
            return 1
    # the HARD set adds Code Cache only, and only over 40 MB
    m3 = re.search(r"private val HARD_SET = arrayOf\((.*?)\)", cg, re.S)
    if not m3 or '"Code Cache"' not in m3.group(1):
        print("FAIL: HARD_SET must be exactly the Code Cache (the JS-compile grower)")
        return 1
    if "before >= HARD" not in cg:
        print("FAIL: the Code Cache sweep must be gated on before >= HARD")
        return 1

    # ---------- G5: the wiring (startup sweep + onResume audit + the
    # v1.3.77 close-out arms: foreground audit loop, delayed background
    # sweep, onDestroy exit) ----------
    act_path = root / "app/src/main/java/com/bolt/browser/ui/BrowserActivity.java"
    if not act_path.exists():
        print("FAIL: BrowserActivity.java does not exist")
        return 1
    act = act_path.read_text(encoding="utf-8")
    if "CacheGuard.check(this, true)" not in act:
        print("FAIL: the startup sweep is not wired (CacheGuard.check(this, true))")
        return 1
    if "CacheGuard.check(this, false, cw)" not in act and \
            "CacheGuard.check(BrowserActivity.this, false, cw)" not in act:
        print("FAIL: the mid-session audit is not wired")
        return 1
    # the startup call must sit right after destroyStaleWebViews (no renderer
    # alive yet — the only moment every cache file is a dead file)
    if not re.search(r"destroyStaleWebViews\(\);"
                     r"[^}]*?CacheGuard\.check\(this, true\)", act, re.S):
        print("FAIL: the startup sweep must run right after destroyStaleWebViews()")
        return 1
    # v1.3.77 — the close-out arms. v1.4.31 re-point (2026-11-25): the
    # audit loop runs every 30 MINUTES and skips loading/media sessions
    # (the trim-driven check covers the pressure moments); the onStop arm
    # is the 5-MINUTE DELAYED sweep (door=delayed, cancelled by onResume —
    # the exit-sweep cadence law; the immediate-onStop sweep of v1.3.79
    # was the tab-hist storm corpse). The exit doors name themselves:
    # destroy (onDestroy) / user (Clean now) / delayed (the runnable).
    for probe in ("protected final Runnable cacheAuditTick",
                  "protected final Runnable cacheBgSweep",
                  "ui.postDelayed(cacheAuditTick, 30 * 60 * 1000L)",
                  'CacheGuard.exit(BrowserActivity.this, "delayed")',
                  "ui.postDelayed(cacheBgSweep, 5 * 60 * 1000L)",
                  "ui.removeCallbacks(cacheBgSweep)",
                  'CacheGuard.exit(this, "destroy")'):
        if probe not in act:
            print(f"FAIL: the v1.3.77 close-out wiring lacks {probe}")
            return 1
    # diag honesty: the trims must land in the diag log
    for probe in ("cache guard start ", "cache guard trim ", "cache guard exit "):
        if probe not in cg:
            print(f"FAIL: CacheGuard must diag-log '{probe}…' lines")
            return 1
    # v1.3.78: the evidence channel — the exit sweep names the biggest
    # remaining subtrees so a still-over-target report is self-diagnosing;
    # and the external cache dir joins the exit sweep
    for probe in ("private fun breakdown(ctx: Context)",
                  "externalCacheDir",
                  "files:ocrfast="):
        if probe not in cg:
            print(f"FAIL: the v1.3.78 evidence/external-cache exit lacks {probe}")
            return 1
    print("OK: G1-G5 cache guard (allowlisted subtrees, 24/40 MB budgets from "
          "the user's storage law, API + renderer-tolerant mid-session, startup "
          "+ onResume + 3-min foreground loop + 5-min background + onDestroy "
          "exit sweep, diag lines)")

    # ---------- G6: THE OSCILLATION KILL — the settle guard exists ----------
    for probe in ("protected long insetSettleUntil = 0L;",
                  "protected long lastBarToggleAt = 0L;",
                  "insetSettleUntil = android.os.SystemClock.uptimeMillis() + 90L + 320L;",
                  "nowMs - lastBarToggleAt < 350L",
                  "scrollDeltaSinceBar = 0;"):
        if probe not in act:
            print(f"FAIL: the oscillation kill lacks {probe}")
            return 1
    # the guard must actually sit in the scroll-delta path BEFORE any hide
    # decision (swallow the echo whole, reset the accumulator)
    if not re.search(r"if \(nowMs < insetSettleUntil \|\| nowMs - lastBarToggleAt < 350L\) \{\s*"
                     r"scrollDeltaSinceBar = 0;\s*return;\s*\}", act):
        print("FAIL: the settle guard must swallow scroll deltas whole "
              "(reset + return) before any bar decision")
        return 1

    # ---------- G7: BOTH toggles stamp the cooldown ----------
    n_stamp = act.count("lastBarToggleAt = android.os.SystemClock.uptimeMillis();")
    if n_stamp < 2:
        print("FAIL: showBarsNow AND hideBars must both stamp lastBarToggleAt "
              f"(found {n_stamp})")
        return 1

    # ---------- G8 (v1.3.81): the ghost kill + the settle window ----------
    # the per-frame inset ANIMATOR is gone (it resized the WebView ~12x per
    # toggle -> the "black rectangle ghost"); the ONE instant set arms the
    # settle window (90 + 320 ms) because a single resize still echoes once.
    if re.search(r"ValueAnimator\.ofFloat\(0f, 1f\)", act) and "insetAnim = va" in act:
        print("FAIL: the per-frame inset animator is back (the ghost-killer regression)")
        return 1
    if not re.search(r"insetSettleUntil = android\.os\.SystemClock\.uptimeMillis\(\) \+ 90L \+ 320L;",
                     act):
        print("FAIL: the settle window must be armed by the (single) inset change")
        return 1
    if not re.search(r"flContent\.setPadding\(0, tp, 0, bp\);", act):
        print("FAIL: the inset change must be ONE instant setPadding")
        return 1
    print("OK: G6-G8 the oscillation kill (settle guard + 350 ms toggle "
          "cooldown + accumulator reset, both toggles stamped) + the v1.3.81 "
          "ghost kill (one instant set, animator dead)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
