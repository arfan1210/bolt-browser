#!/usr/bin/env python3
"""v1.3.90 gate — the four-front field fix (parallel downloads, the
name/extension rescue round, the background-play context self-heal, the
home-drag launcher contract). Static contract tests over the exact sources,
the repo's convention since v1.3.31.

D1 THE PARALLEL POOL — the engine's worker was a newSingleThreadExecutor
   with a serial loop(): one item at a time, always — a slow server or a
   mid-transfer retry-sleep starved every download behind it ("no parallel
   downloads"). The pool keeps 3 workers; pump() claims QUEUED items under
   the lock pause()/cancel() hold and each worker re-pumps on finish.
D2 THE NAME LAW, ROUND 2 — the Content-Disposition swap was gated on
   machineNamed() alone, so guessFileName's "getfile.bin"-class names
   (short, wordy, NOT machine-named) ignored servers that DID name the
   file. The server filename now wins when the name is machine-generated
   OR its extension is UNTRUSTED (missing/.bin/.part); a stale .bin is
   then repaired from the post-redirect URL path or the cd extension, and
   a still-generic mime is derived from the final name's extension.
D3 THE 416 RESTART — Range Not Satisfiable burned every retry against the
   same wall; the part is dropped and the transfer taken fresh.
D4 THE GARBAGE GUARD — an HTML page served where a binary was promised
   fails honestly (no retry); attempts 3 -> 4; redirect hops disconnect.
M1 THE CONTEXT SELF-HEAL — mkChain routes media through an AudioContext;
   a suspended context silences the element with no pause event ("bg play
   stops") and freezes the analyser (phantom skips). ctxHeal() re-resumes
   from skTick AND reportTime (timeupdate = a media event, immune to
   hidden-page timer throttling); a refused resume (ok:0) rolls
   concealment back for the session.
H1 THE LAUNCHER CONTRACT — a QUICK drop REORDERS (on a tile by its half,
   on empty space by the nearest slot); grouping is the HOLD's (550 ms);
   deletion only from the tile menu (the old drop-on-tile-merge read as
   "dragging too far left or right is DELETING the shortcut" and made
   ordering impossible in a dense grid).
H2 THE LIVE GAP — the tiles that would slide on drop animate half a slot
   toward the vacated position while the drag hovers ("the nice animation
   of the icons of the site themselves moving"), only when the insertion
   point changes.
H3 THE FINGER-TRUE GHOST — the composed tile listener records the finger
   on every event; the ghost lifts from UNDER the finger (no cell-to-
   finger snap after the long-press drift) and floats sdLiftDp above it.
V1 version pin 108 / 1.3.95.

Usage: python3 scripts/test_v1390.py
"""
import re
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")


def rd(p):
    with open(os.path.join(ROOT, p), encoding="utf-8") as f:
        return f.read()


def need(cond, msg):
    if not cond:
        print("  FAIL  " + msg)
        need.failed = True
    else:
        print("  PASS  " + msg)


need.failed = False

dl = rd("app/src/main/java/com/bolt/browser/core/DownloadEngine.java")
mhk = rd("app/src/main/java/com/bolt/browser/media/MediaHook.java")
mcs = rd("app/src/main/java/com/bolt/browser/media/MediaControlService.java")
act = rd("app/src/main/java/com/bolt/browser/ui/BrowserActivity.java")
gradle = rd("app/build.gradle")

print("D1 — the parallel download pool")
need("newFixedThreadPool(MAX_PARALLEL" in dl,
     "the pool is a fixed multi-worker executor (was single-thread)")
need("static final int MAX_PARALLEL = 3;" in dl,
     "three workers — parallel enough on a phone radio")
need("it.status = ST_RUNNING;   // claim" in dl,
     "pump() claims the item QUEUED->RUNNING under the lock (pause-safe)")
need("running.decrementAndGet();" in dl and "pump();" in dl,
     "each worker's finally re-pumps (a finishing slot admits the next item)")
need("newSingleThreadExecutor" not in dl and "private void loop()" not in dl,
     "the serial executor and its drain loop are gone")

print("D2 — the name law, round 2 (cdName + extension rescue + last-mile mime)")
need("(machineNamed(it.name) || extUntrusted(it.name))" in dl,
     "the server's Content-Disposition filename also wins over UNTRUSTED-extension names")
need("static boolean extUntrusted(String name)" in dl
     and '"bin".equals(cur) || "part".equals(cur)' in dl,
     "the untrusted-extension test exists (.bin/.part/missing)")
need("static String urlPathExt(String url)" in dl,
     "the URL-path extension reader exists")
need("urlPathExt(it.lastUrl.isEmpty() ? it.url : it.lastUrl)" in dl,
     "the rescue prefers the POST-REDIRECT url (it.lastUrl)")
need("it.lastUrl = url;" in dl,
     "the final redirect url is captured on the response")
need("getMimeTypeFromExtension(" in dl,
     "the last-mile mime is derived from the final name's extension (SAF .bin kill)")

print("D3 — the 416 restart")
need("code == 416 && resume" in dl and "part.delete();" in dl,
     "Range Not Satisfiable drops the part and takes the transfer fresh")

print("D4 — the garbage guard + the retry round")
need("class GarbageException extends IOException" in dl
     and "throw new GarbageException();" in dl,
     "an HTML page where a binary was promised throws the no-retry failure")
need("site returned a web page, not the file" in dl,
     "the failure says what actually happened")
need("for (int attempt = 0; attempt < 4; attempt++)" in dl
     and "if (attempt >= 3) {" in dl,
     "4 attempts (was 3) — flaky-network moments get one more honest retry")

print("M1 — the background-play context self-heal")
need("function ctxHeal(){" in mhk
     and "c.ctx.state!=='running'" in mhk
     and "c.ctx.resume()" in mhk,
     "ctxHeal() re-resumes suspended AudioContexts (routed media = silenced media)")
need(mhk.count("try{ctxHeal();}catch(x){}") >= 2,
     "the heal rides skTick AND reportTime (timeupdate = throttling-proof)")
need("'ctxheal'" in mhk and '\\"ok\\"' in mhk,
     "the heal reports n + ok (a refused resume is actionable)")
need('"ctxheal".equals(name)' in mcs and "bg ctx-heal" in mcs,
     "Java logs the heal and can act on a refused one")
need("lastCtxHealLog" in mcs and "10_000" in mcs,
     "the diag line is rate-limited (the heal can fire on every timeupdate)")
need("concealLatched = true;   // session-only" in mcs,
     "a refused resume rolls concealment back for the session (always-lie = audio back)")

print("H1 — the launcher contract: drop = reorder, center-release = group")
need("prefs.moveSpeeddial(src, insert);" in act
     and act.count("prefs.mergeSpeeddial(src, target);") == 0,
     "sdDragEnd REORDERS on a quick drop (the drop-on-tile merge is gone)")
need("ui.postDelayed(sdHoldMerge, 550);" not in act
     and "armSdHoldMerge" not in act
     and "protected final Runnable sdHoldMerge" not in act,
     "v1.3.91: the 550 ms dwell-merge is GONE (slow drags no longer swallow tiles)")
need("findSdFolderTarget(x, y)" in act
     and "Math.min(r.width(), r.height()) * 0.38" in act,
     "the folder candidate is the CENTER ZONE (Launcher3 folderCreationRadius law)")
need("sdDwellProgress >= 1000" in act
     and "prefs.mergeSpeeddial(src, tgt);" in act,
     "grouping is DWELL-GATED (v1.3.92: the 600ms pill fills -> merge fires MID-HOLD, never at release)")
need("// REORDER everywhere: the hovered slot + its half decides" in act,
     "the reorder comment names the contract at the call site (v1.3.92 slot form)")

print("H2 — the live gap animation")
need("protected void showSdGap(int insert)" in act
     and "translationX(tx)" in act,
     "the gap preview slides the tiles that would move on drop")
need("if (ins != sdGapShown) showSdGap(ins);" in act,
     "the shift work runs only when the insertion point CHANGES")
need("translationX(0f)" in act,
     "the gap closes on drop/end")
need("sdGapShown = -2;" in act,
     "the gap state resets (no stale preview on the next drag)")

print("H3 — the finger-true ghost")
need("sdLastX = e.getRawX();" in act and "sdLastY = e.getRawY();" in act,
     "the composed tile listener records the finger on every event")
need("g.setX(sdLastX - half);" in act,
     "the ghost lifts from UNDER the finger (no cell-to-finger snap)")
need("sdLiftDp" in act and "- Fx.dp(this, sdLiftDp)" in act,
     "the ghost floats a constant lift above the finger (1:1 tracking)")
need("sdOriginX = sdLastX;" in act,
     "the drift test measures from the finger (menu-vs-drag contract)")

print("V1 — version pin")
need(re.search(r"versionCode 10[3456789]\b|versionCode 1[23][0-9]\b|versionCode 14[0-9]\b", gradle) is not None,  # v1.4.26 re-point (2026-11-21): the 140s band joined
     "versionCode 103-140 (v1.4.9: 123 in the class)")
need(re.search(r'versionName "1\.(3\.1|4\.[0123456789])', gradle) is not None,
     'versionName 1.3.1xx / 1.4.x (v1.4.9: 1.4.9 in the class)')

print()
if need.failed:
    print("v1.3.90 gate: FAILED")
    sys.exit(1)
print("v1.3.90 gate: all green")
