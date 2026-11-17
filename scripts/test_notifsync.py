#!/usr/bin/env python3
"""test_notifsync.py — v1.3.63 gate: the notification seekbar stays in sync
through a PAUSED seek (the v1.3.62 field report: "the notification video
progress bar loses sync to the video if I pause and adjust the playhead
from notification").

THE BUG (verified in MediaControlService.java @ v1.3.62):
  onSeekTo ONLY dispatched seekTo to the page. The paused-side handlers
  never adopted the new position ('state' reports from an already-paused
  page updated posMs only on the playing->paused TRANSITION) and 'time'
  beats published only `if (playing)`. So after a paused seek the last
  published PlaybackState stayed frozen at the PAUSE-TIME position:
  SystemUI snapped the dragged playhead back and the bar sat desynced
  from the actual video until the next PLAY. (Same desync class as
  Chromium issue 40926662 — media session position diverging from the
  real player position without a re-sync on the state change.)

THE FIX (pinned here as static pins + a behavioral mirror of the publish
gates):
  1. onSeekTo ADOPTS the seek target (posMs/posAt) and FORCES a publish
     BEFORE dispatching to the page (optimistic — exactly what the user
     dragged; the page beats correct it within ~1 s).
  2. A 'state' report from an already-paused page adopts pos and lets
     the jump gate republish.
  3. 'time' beats publish unconditionally (the internal gates keep the
     IPC discipline: no state change, no jump, <60 s -> no publish).
  4. publishSessionState(force) overload so (1) can bypass the gates.
"""
import re, sys, pathlib

BASE = pathlib.Path("/home/z/my-project/bolt-light-browser"
                   "/app/src/main/java/com/bolt/browser")
failures = []


def rd(p):
    return (BASE / p).read_text(errors="ignore")


def strip_comments(text):
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text


def body_of(src, start_marker, end_marker):
    i = src.find(start_marker)
    if i < 0:
        return ""
    j = src.find(end_marker, i)
    return src[i:j] if j > i else src[i:]


svc = strip_comments(rd("media/MediaControlService.java"))

# ---- 1. onSeekTo: adopt + forced publish BEFORE the dispatch ----
seek = body_of(svc, "public void onSeekTo(long pos)",
                    "}, ui);")
if not seek:
    failures.append("onSeekTo not found")
else:
    for frag in ("posMs = Math.max(0, pos)", "publishSessionState(true)",
                 "sendCmd(\"seekTo\""):
        if frag not in seek:
            failures.append("onSeekTo missing '" + frag + "' (the optimistic "
                            "adopt + forced publish + the page dispatch)")
    if seek.find("publishSessionState(true)") > seek.find("sendCmd(\"seekTo\""):
        failures.append("onSeekTo publishes AFTER dispatching to the page -- "
                        "the forced publish must land first or the bar still "
                        "snaps back for the round-trip window")

# ---- 2. publishSessionState(force): the overload + the gate ----
if "private void publishSessionState() { publishSessionState(false); }" not in svc:
    failures.append("publishSessionState lost its no-arg bridge to the force "
                    "overload (every existing caller keeps working)")
if "private void publishSessionState(boolean force)" not in svc:
    failures.append("publishSessionState(boolean force) overload missing")
if "if (force || republishState || jumped || (now - lastPublishAt) >= 60_000)" not in svc:
    failures.append("the force flag does not bypass the publish gates -- the "
                    "optimistic seek publish would be swallowed (no state "
                    "flip, no jump vs the frozen lastPosPublished, <60 s)")

# ---- 3. paused 'state' reports adopt the position ----
state = body_of(svc, 'if ("state".equals(name))', '} else if ("concealfail"')
if not state:
    failures.append("the 'state' handler not found")
else:
    if "publishSessionState(false)" not in state:
        failures.append("an already-paused 'state' report no longer offers a "
                        "publish -- the paused-seek position stayed invisible "
                        "to the seekbar")
    # the paused-adopt block: find the pos read (unique to the adopt), then
    # require the stamp + publish inside it (the state handler contains
    # OTHER `if (!playing)` blocks -- the transition-to-playing one)
    k = state.find('long p = (long) (o.optDouble("pos", 0) * 1000)')
    if k < 0:
        failures.append("the paused 'state' adopt never reads the report's "
                        "position -- posMs stayed at the pause-time value")
    else:
        seg = state[max(0, k - 200):k + 400]
        for frag in ("if (!playing)", "posMs = p",
                     "posAt = SystemClock.elapsedRealtime()",
                     "publishSessionState(false)"):
            if frag not in seg:
                failures.append("the paused 'state' adopt is missing '" + frag
                                + "'")

# ---- 4. 'time' beats publish unconditionally ----
timeh = body_of(svc, '} else if ("time".equals(name))', '} catch (Throwable ignored) {')
if not timeh:
    failures.append("the 'time' handler not found")
else:
    if "if (playing) publishSessionState()" in timeh:
        failures.append("'time' beats still gate the publish on playing -- a "
                        "paused seek fires timeupdate once and SystemUI never "
                        "learns the new position (the exact v1.3.62 desync)")
    if "publishSessionState(false)" not in timeh:
        failures.append("'time' beats no longer publish")

# ---- 5. the page-side seekTo still dispatches seconds (ms/1000.0) ----
if "sendCmd(\"seekTo\", pos / 1000.0)" not in svc:
    failures.append("the seekTo dispatch lost its ms->s conversion")

# ============================================================
# PART 2 — behavioral mirror of the publish gates (v1.3.62 vs v1.3.63)
# ============================================================
# Mirrors the service fields (posMs, posAt, rate, playing,
# lastPosPublished, lastPublishAt) and the publish decision, then walks
# THE field-report sequence: play -> pause -> PAUSED seek from the
# notification -> (a) nothing else, (b) a paused 'time' beat, (c) play.


class Svc:
    def __init__(self, v1363):
        self.v1363 = v1363
        self.playing = False
        self.posMs = 0
        self.posAt = 0
        self.rate = 1.0
        self.lastPosPublished = -1
        self.lastPublishAt = 0
        self.now = 0            # elapsed-realtime clock (ms)
        self.published = []     # (clock, position, playing) history

    def clock(self, ms):
        self.now += ms

    def state_paused_report(self, pos_ms):
        # a 'state' report from an already-paused page
        if self.playing:
            raise AssertionError("transition case not modeled here")
        if self.v1363:
            if pos_ms > 0:
                self.posMs = pos_ms
                self.posAt = self.now
            self.publish(False)

    def time_beat(self, pos_ms):
        self.posMs = pos_ms
        self.posAt = self.now
        if self.v1363 or self.playing:
            self.publish(False)
        # v1.3.62: paused beats publish NOTHING

    def on_seek_to(self, pos_ms):
        if self.v1363:
            self.posMs = max(0, pos_ms)
            self.posAt = self.now
            self.publish(True)
        # both versions dispatch to the page

    def publish(self, force):
        republishState = (self.lastPosPublished < 0) or \
            (self.published and self.published[-1][2] != self.playing)
        pos = self.posMs + int((self.now - self.posAt) * self.rate) \
            if self.playing else self.posMs
        jumped = self.lastPosPublished >= 0 and (
            abs(pos - (self.lastPosPublished
                       + (int((self.now - self.lastPublishAt) * self.rate)
                          if self.playing else 0))) > 2500
            if self.playing else abs(pos - self.lastPosPublished) > 2500)
        if force or republishState or jumped or \
                (self.now - self.lastPublishAt) >= 60_000:
            self.lastPublishAt = self.now
            self.lastPosPublished = pos
            self.published.append((self.now, pos, self.playing))

    def displayed(self):
        """what SystemUI renders right now (last publish + interpolation)"""
        if not self.published:
            return None
        _, pos, was_playing = self.published[-1]
        return pos + int((self.now - self.lastPublishAt) * self.rate) \
            if was_playing else pos


def walk(v1363):
    s = Svc(v1363)
    # playing at 100s into the video; a publish happened at t=0
    s.playing = True
    s.posMs, s.posAt, s.rate = 100_000, 0, 1.0
    s.publish(False)
    # the user pauses from the notification at t=2s (page reports pos 102s)
    s.clock(2_000)
    s.playing = False
    s.posMs, s.posAt = 102_000, s.now
    s.publish(False)
    # the user drags the playhead to 300s at t=5s
    s.clock(3_000)
    s.on_seek_to(300_000)
    # (a) nothing else happens for a while: what does the bar show at t=8s?
    s.clock(3_000)
    frozen = s.displayed()
    # (b) the page's own reports land: a paused 'state' (report(true) after
    #     the seekTo command) and a paused 'time' beat (timeupdate on seek)
    s.state_paused_report(300_000)
    s.time_beat(300_000)
    beat = s.displayed()
    # (c) how many publishes total? (IPC discipline while paused)
    count_after_seek = len([p for p in s.published if p[0] >= 5_000])
    return frozen, beat, count_after_seek


for v1363, tag in ((True, "v1.3.63"), (False, "v1.3.62")):
    frozen, beat, pubs = walk(v1363)
    if v1363:
        if frozen != 300_000:
            failures.append("BEHAVIOR: %s leaves the paused-seek bar at %s "
                            "(expected 300000) -- the optimistic adopt/force "
                            "is not working" % (tag, frozen))
        if beat != 300_000:
            failures.append("BEHAVIOR: %s beat-correction shows %s (expected "
                            "300000)" % (tag, beat))
        if pubs < 1 or pubs > 2:
            failures.append("BEHAVIOR: %s published %d times for the paused "
                            "seek (expected 1-2: the forced one + at most the "
                            "beat)" % (tag, pubs))
    else:
        # the NEGATIVE CONTROL: v1.3.62 rules MUST desync (this is the
        # field bug) -- if they don't, the mirror is wrong
        if frozen == 300_000 or beat == 300_000:
            failures.append("BEHAVIOR: the v1.3.62 negative control SYNCS "
                            "(bar=%s beat=%s) -- the mirror no longer "
                            "reproduces the field bug; fix the mirror"
                            % (frozen, beat))

# a playing seek still republishes through the jump gate (both versions)
for v1363 in (True, False):
    s = Svc(v1363)
    s.playing = True
    s.posMs, s.posAt, s.rate = 100_000, 0, 1.0
    s.publish(False)
    s.clock(2_000)
    s.time_beat(300_000)          # the page seeked while playing
    if s.displayed() != 300_000:
        failures.append("BEHAVIOR: a PLAYING seek no longer republishes "
                        "(v1.3.63=%s)" % v1363)

# paused idle beats must NOT spam (the gates hold with the new rules)
s = Svc(True)
s.playing = False
s.posMs, s.posAt = 102_000, 0
s.publish(False)
for _ in range(5):
    s.clock(1_000)
    s.time_beat(102_000)
if len(s.published) != 1:
    failures.append("BEHAVIOR: paused idle beats republish (%d times in 5 s) "
                    "-- the IPC discipline regressed" % (len(s.published) - 1))

print(("FAIL: %d problem(s)" % len(failures)) if failures else
      "test_notifsync: OK (static pins + behavioral mirror)")
for f in failures:
    print("  - " + f)
sys.exit(1 if failures else 0)
