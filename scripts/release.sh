#!/bin/bash
# Bolt Browser — full release flow (build → verify → publish), v1.4.10
# pipeline (2026-11-17): the old private repo is DEAD. The
# flow now lives entirely on the two public repos:
#   SOURCE   github.com/arfan1210/bolt-browser          <- tag v<ver> + main
#   RELEASES github.com/arfan1210/bolt-browser-releases <- release + APK +
#                                                         changelog body
# The releases repo is ALSO what the in-app update checker reads
# (AboutActivity RELEASES_API) — publisher and checker cannot drift apart.
#
# Run AFTER bumping versionCode/versionName in app/build.gradle and updating
# the About changelog + CHANGELOG.md. Everything below is automated; stop on
# any failure.
#
# Usage:  bash scripts/release.sh <ver> [notes-file]
#   ver        e.g. 1.4.10
#   notes-file optional markdown body for the release (default: CHANGELOG.md
#              section for that version is extracted)
#
# Assets uploaded:
#   Bolt-v<ver>.apk   the installable APK (the releases repo's convention
#                     since the first public release — APK only, the source
#                     lives on the tag in the source repo)
set -e
VER="${1:?usage: release.sh <ver> [notes-file]}"
NOTES="${2:-}"
BASE=/home/z/my-project
REPO_DIR=$BASE/bolt-browser
TOK="${GHTOKEN:-$(cat $BASE/.ghtoken)}"
AUTH="Authorization: Bearer $TOK"
ACC="Accept: application/vnd.github+json"
API="https://api.github.com/repos/arfan1210/bolt-browser-releases"
SRC_API="https://api.github.com/repos/arfan1210/bolt-browser"
PUSHURL="https://x-access-token:${TOK}@github.com/arfan1210/bolt-browser.git"
CERT_EXPECT="bcd57eb42ca770a340f78ad64b56999d6238f88ab194cc8c787ee46082938ae4"

cd $REPO_DIR
TAG="v$VER"

# ---- 0. preconditions ----
[ -z "$(git status --porcelain)" ] || { echo "ERROR: uncommitted changes"; exit 1; }
VNAME=$(grep -o 'versionName "[^"]*"' app/build.gradle | head -1 | cut -d'"' -f2)
[ "$VNAME" = "$VER" ] || { echo "ERROR: app/build.gradle says $VNAME, asked to release $VER"; exit 1; }

# ---- 0b. THE LIVE-VERSION GUARD (the v1.4.10 collision corpse: v1.4.9/123
# was already live from the private repo, so a same-version rebuild could
# never install — Android only accepts RISING versionCodes). Refuse to
# publish anything that does not compare GREATER than the live latest.
LIVE_VER=$(curl -s -H "$AUTH" -H "$ACC" "$API/releases/latest" | python3 -c "import json,sys;print(json.load(sys.stdin).get('tag_name','').lstrip('v'))" 2>/dev/null || true)
if [ -n "$LIVE_VER" ]; then
  python3 - "$LIVE_VER" "$VER" << 'PYEOF' || exit 1
import sys
live, ver = sys.argv[1], sys.argv[2]
key = lambda s: [int(x) for x in s.split(".")]
if key(live) >= key(ver):
    print(f"ERROR: releases repo already serves v{live} — v{ver} is not a RISING "
          "version and Android would reject the update (the v1.4.10 corpse). "
          "Bump versionName/versionCode first.")
    sys.exit(1)
print(f"live-version guard OK: live v{live} < releasing v{ver}")
PYEOF
fi

# ---- 1. static checks (never skip) ----
python3 scripts/check_js.py
node scripts/test_media_hook.js
# v1.3.87: the dark-mode JS payloads (probe/scan/remove) + the AI-export
# picker behavior (mock-DOM) join the static gate
python3 scripts/check_dark_js.py
node scripts/test_aiexport.js
JDK_DIR=${BOLT_JDK:-/home/z/jdk21/usr/lib/jvm/java-21-openjdk-amd64}
export PATH=$JDK_DIR/bin:$PATH   # v1.3.31+: test_totp/test_migration/test_qr need javac/java; v1.3.80: the grafted JDK21 (setup_toolchain.sh)
python3 scripts/test_totp.py
python3 scripts/test_migration.py  # v1.3.32: GA export protobuf parser (real fixtures)
python3 scripts/test_qr.py          # v1.3.32: QR decode/encode round-trips (fetches zxing jar)
node scripts/test_webauthn_compat.js
# v1.3.46: the gesture math table tests are part of the gate now — they guard
# the drag-zone + swipe-walk index logic the field reports kept breaking
javac -d /tmp/simgest scripts/SimSnap.java scripts/SimSwipe.java
java -cp /tmp/simgest SimSnap
java -cp /tmp/simgest SimSwipe
# v1.3.49: the finger-true drag model joins the gate — it guards the exact
# "jumps around / disappears during auto-scroll" regression (raw deltas are
# viewport-stable in stock ItemTouchHelper; the snap offset is the only
# eased motion; the offset is suppressed while the grid scrolls).
javac -d /tmp/simgest scripts/SimDrag.java
java -cp /tmp/simgest SimDrag
# v1.3.52: the per-site wipe blast-radius test joins the gate — it guards the
# "clearing one site's data cleared ALL sites" regression (the v1.3.7 flow
# carried three global nukes: removeAllCookies jar wipe, shared clearCache(true),
# global clearFormData)
python3 scripts/test_sitewipe.py

# v1.3.53: the aggressive-reload gate joins the release checks — it guards
# the channel split (favicon/wallpaper/home-content changes bump Fx.homeGen
# and may only REBUILD the home view; skinGen stays the layout-level
# recreate channel, because a BrowserActivity recreate destroys every tab's
# WebView and reloads the page)
python3 scripts/test_reloadgate.py

# v1.3.56: the blob-download + selection-copy gate joins the release
# checks — it guards the blob: rescue chain (fetch -> same-origin canvas
# -> honest failure, wired BEFORE the !http rejection in enqueueDownload,
# targeting the active tab, never evalMedia's media tab) and the copy
# robustness (throttled re-capture, forced capture on the Copy tap with
# Copying… feedback, shadow-DOM-aware JS, JSONTokener decode, bridge
# surface untouched)
python3 scripts/test_blobdl.py

# v1.3.59: the Downloads-UI gate joins the release checks — it guards the
# shelf-dismissal fix (dialog dismiss + every open path retires the chip)
# and the redesign wiring (type icons shared with the shelf, async
# previews with the recycled-view tag guard, day-grouped sections,
# card/list toggle, the DlSheet action sheet)
python3 scripts/test_dlui.py

# v1.3.60: the fullscreen-covenant gate joins the release checks — it
# guards the phantom-fullscreen fix (the orphan exit at onDestroy + the
# healRendererFullscreen probe wired at showTab/onPageFinished, which
# together kill the "zoomed-in view instead of true fullscreen" +
# "floating overlay not hiding" + "PiP fully broken" triple from one
# root cause: a renderer stuck in element-fullscreen while the app has
# no customView) and pins the untouched PiP eligibility rules
python3 scripts/test_fs.py

# v1.3.65: the imported PiP engine gate — extracts the JS built by
# util/PipVideo.kt (the Maximize-Video-grade takeover) from the Kotlin
# source, syntax-checks both selector variants + the unpin, then the
# mock-DOM suite proves the containing-block fix (ancestor neutralizer),
# the player walk, the flicker-free beat, the two-tier/strict selectors,
# the tracked-pin re-cycle and the full restore
python3 scripts/test_pipvideo.py
node scripts/test_pipvideo.js

# v1.3.63: the notification seek-sync gate — guards the paused-seek fix
# (onSeekTo's optimistic adopt + forced publish, the paused-state position
# adopt, the unconditional 'time'-beat publish) that keeps the notification
# progress bar glued to the video through a pause + playhead adjustment
python3 scripts/test_notifsync.py

# v1.3.70: the in-place translation gate — extracts the ENGINE literal from
# util/PageTranslate.kt, syntax-checks both target-language variants, proves
# the Kotlin contract (SSRF host whitelist, per-web BoltTx registration,
# JSONObject.quote delivery, the dead /translate proxy flow gone) and runs
# the mock-DOM suite: collector skips, the script pre-filter, whitespace
# preservation, sentence splitting, BOTH response shapes, the detected==
# target keep, host fail-over + 429 backoff, the pill lifecycle with revert,
# the observer wave, the stale guard, the restart epoch, the URL guard
python3 scripts/test_translate.py
node scripts/test_translate.js

# v1.3.73: the translate-screen gate — guards the size-law rewrite (NO
# ML Kit / no native code / universal APK; capture through
# PixelSnap.viewShot only; every translation through PageTranslate.gtx;
# the only other network is the pinned tessdata_fast model download;
# the tesseract.js 5.1.1 host page + bolt.ocr local model serving; the
# overlay's luminance/auto-fit/tap/chips/save surface, the menu wiring,
# the no-new-permissions rule, and the enforced APK budget below)
python3 scripts/test_screen.py

# v1.3.77: the box-dialect replay gate — REAL provider answer shapes
# through the parseBoxes logic (native box_2d 0-1000 corner order, the
# pixel/float dialects, legacy [x,y,w,h], th dialects, the v1.3.76
# misparse shape). A faithful Python port that MUST track AiBridge.kt's
# parseBoxes — edit both together.
python3 scripts/test_boxes.py

# v1.3.76: the guard gate — the cache guard (allowlisted cache subtrees
# only, budgets, the two trim windows, the wiring) + the toolbar
# oscillation kill (settle guard + toggle cooldown in the scroll path)
python3 scripts/test_cacheguard.py

# v1.3.78: the field-fix gate — tab-cell touch target, favicon host pinning
# + contrast tiles + switcher rebind, download names/PK-sniff/icons/APK
# install path, the keyboard truce + keyboard-anchored chips, and the
# selection ActionMode fresh-rect guarantee
python3 scripts/test_v1378.py

# v1.3.79: the field-fix gate — the selection Callback2 position truth,
# field-anchored fill chips + username-first detection, the silence skipper
# (engine + sheet + FAB renovation + settings), the storage law round 3
# (immediate close-out + deep clean + cacheDir models + interrupted-sweep
# finisher), the perf pass (debounced writes, executor favicons, parse
# caches, capped scans, throttled blur), the universal favicon pass (DOM
# extraction + SVG rasterization + root-ico fallback), tab-cell press
# feedback, home customization (logo/reorder/hold-merge/edit/folders), the
# search widget, app icon aliases, and the security hardening pins
python3 scripts/test_v1379.py

# v1.3.80: the adblock hardening gate — extended-CSS "#?#" normalization
# (291 dead rules), cosmetic "#@#" exceptions (338 ignored), $elemhide/
# $generichide/$specifichide opt-outs, procedural :has-text() (was invalid
# CSS), quote-safety (2,512 dropped attribute selectors), commit-visible
# early injection, the adblock-off cosmetic leak, the customRules dead
# pref, and built-in list updates (size-guarded, easylist.to)
python3 scripts/test_adblock.py
node scripts/test_adblock_proc.js

# v1.3.81: the six-pack field-fix gate — force-dark matrix (isLightTheme +
# CSS fallback coverage), the ghost kill (instant insets, covered resize),
# themed launcher aliases, the home hold-menu, favicon full-host keying +
# the 700ms attribution race guard, and the form-resubmission back/forward
# kill
python3 scripts/test_v1381.py

# v1.3.82: the resurrection gate — the launcher guard (component-enabled
# state survives updates; MY_PACKAGE_REPLACED self-heal + enable-first
# flip), the update-time sweep (model diet + close-out at install), the
# allowlist extensions (File System/Session Storage site data, the
# Optimization Guide model cache), the startup heal, and the storage meter
python3 scripts/test_v1382.py

# v1.3.83: the manifest-resurrection gate — MY_PACKAGE_REPLACED is NOT
# delivered to stopped/never-started apps (the v1.3.82 field failure), so
# the IconDefault alias ships enabled IN THE MANIFEST (a never-before-used
# component name carries no persisted state — the install itself revives
# the icon), the real activity is demoted filter-less (its persisted
# DISABLE survives every update), faces are aliases only, the fossil-era
# pref resets, the activity scar normalizes, and the receiver heals
# synchronously inside the broadcast window with goAsync sweeps
python3 scripts/test_v1383.py

# ---- v1.3.84: the three-pack gate (relative skip factor, the audited
# history step, the body-carrier law for uploads) ----
python3 scripts/test_v1384.py

# ---- v1.3.85: the label/churn/401 gate (the truthful 4x label, HistoryNav
# v2's tracking-churn tolerance for facebook's back-to-home, the upload-401
# decoder + sign-in hint) ----
python3 scripts/test_v1385.py

# ---- v1.3.86: the four-pack gate (every-type blob rescue, real download
# names via the a[download] click capture, the incognito hop that sticks,
# the login-preserving site-data diet = the zai 401's root cause) ----
python3 scripts/test_v1386.py

# ---- v1.3.87: the four-pack gate (the skipper hysteresis overhaul with
# its four settings knobs, the already-dark gate + real media exclusions,
# the download-page repair for the GitHub releases blank tab, THE AI
# RESPONSE EXPORTER) ----
python3 scripts/test_v1387.py

# ---- v1.3.88: the four-pack gate, round 2 (the download repair rework —
# late-commit arming + the doUpdateVisitedHistory hook + the serialized
# pair; the client-hints spoof that agrees with the live UA; the QR
# portrait preview transform; the home-drag in-flight touch feed) ----
python3 scripts/test_v1388.py

# ---- v1.3.89: the six-pack gate (the fullscreen IME pad + tap-steal
# strip; the QR quality pass — big hole, 1920 streams, continuous AF; the
# desktop VIEWPORT forcing; the home-drag clobber fix + real-icon ghost;
# the perf pass — 48 MB blob budget + adaptive skipper cadence) ----
python3 scripts/test_v1389.py

# ---- v1.3.90: the four-front gate (the PARALLEL download pool; the
# name/extension rescue round — cdName law v2 + url-path ext + last-mile
# mime + 416 + the HTML-garbage guard; the background-play context
# self-heal — ctxHeal on skTick + timeupdate, ctxheal handler with the
# session rollback; the home-drag LAUNCHER CONTRACT — drop=reorder,
# hold=merge, the live gap, the finger-true ghost) ----
python3 scripts/test_v1390.py

# ---- v1.3.91: the eight-pack gate (the RELEASE-GATED folder — the
# dwell-merge deleted, the center-zone candidate, the undo snackbar; the
# HONEST VAULT — the page host on the fill report, atomic write, the
# unreadable banner, bidirectional find; the Shorts ORIENTATION contract —
# aspect-driven, one kick; the tab-count COLOR LAW; the SORT pass with the
# animated reorder; the DARK round 3 — one engine, six-signal probe, the
# late re-probe, the bg-image observer; the SAVE-PAGE pass — PDF/print/
# HTML/MHT; the AI EXPORTER round 2 — manual mode, front card) ----
python3 scripts/test_v1391.py

# ---- 1b. v1.3.92 gate (the six-pack: the native AI picker + the vector
# PDF, the launcher home matrix, the long-hold copy, the transient-failure
# vault pass, the dark parity engine) ----
python3 scripts/test_v1392.py

# ---- v1.3.93 gate (the bug-hunt pass: vault delete clobber, orphan
# Downloads rows x4, dark composition round 3, the scrollable Backup
# screen, zip-slip) ----
python3 scripts/test_v1393.py

# ---- v1.3.94 gate (the crash-loop fix: ViewPager2 pager law; the staged
# backup import; the ask-first dark pipeline + the clarity pass) ----
python3 scripts/test_v1394.py

# ---- v1.3.95 gate (the favicon ownership law / the new-tab crash; the
# 1200ms hold-menu; the omnibox fill button + Engines.queryOf; the vault
# stage codes; the AI-response discoverability; the honest PDF fallback;
# the upload-auth OPTIONS filter) ----
python3 scripts/test_v1395.py

# ---- v1.3.96 gate (the dispatch law / the home-drag TouchTarget crash;
# the AI-saver quoted-JSON corpse; the Save-as-PDF parse killer + the
# height lie + the scroller flatten + the 32000 ceiling; PageSave JS
# joins the parse-gated corpus) ----
python3 scripts/test_v1396.py
node scripts/test_v1396_js.js

# ---- v1.3.97 gate (the phantom home refresh: pull-to-refresh is a
# web-page gesture, the no-op drop builds nothing, drag rebuilds are
# quiet + position-true; the 550ms hold-menu + sdDragNoMenu; the tile
# menu is the icon-row sheet with three new vectors) ----
python3 scripts/test_v1397.py

# ---- v1.3.98 gate (the pager clash: the disallow flag rises from the
# CELL through the pager's inner RecyclerView + setUserInputEnabled kill;
# the desktop site: the UA heal at start/commit, the native client-hints
# twin UaIdentity.kt, the live viewport guard) ----
python3 scripts/test_v1398.py
node scripts/test_v1398_js.js

# ---- v1.3.99 gate (THE IDENTITY-AGREEMENT PASS: the UA string, the native
# client-hints metadata and the JS fallback spoof must all claim the SAME
# Chrome major — the frozen Chrome/124 preset vs 156 hints mismatch was the
# bot fingerprint keeping grok's Cloudflare gate suspicious; the desktop UA
# is composed from the detected engine and every hints source follows the
# STRING) ----
python3 scripts/test_v1399.py
node scripts/test_v1399_js.js

# ---- v1.3.100 gate (THE UNHURRIED DWELL + THE AUTH-CHAIN UA HOLD: the
# grouping dwell widens 600->1000 ms; an OAuth/SSO round-trip rides ONE
# stable UA — the authorize request arms a per-tab hold over the IdP + RP
# base domains, single-use response landings are never reloaded (the
# qwen->github->qwen callback corpse: the commit heal's reload re-sent the
# one-time code and killed the connect), and third-domain or 10-minute
# exits disarm; the classifier itself is compiled + run as a functional
# probe, per the parse-gate law) ----
python3 scripts/test_v13100.py

# ---- v1.4.0 gate (the put-as-insert corpse — moveGroup/moveSpeeddial rebuilt
# lossless, proven by a compiled+run probe; the orphan heal; the draggable tab
# groups; the launcher folder window + reorder-only folder drags; About + the
# update checker against the public releases repo) ----
python3 scripts/test_v140.py

# ---- v1.4.1 gate (the folder-window shared-LayoutParams corpse — the icons
# stacked in one cell because GridLayout WRITES the resolved cell back into
# the child's LayoutParams; About's simplified changelog + the 7-tap easter
# egg (Bolt Dash, a tiny offline RNG game); one ordering memory for both
# group placements (v1.4.3 re-point: the sync is GONE — both renders derive
# from tm.tabs and the toggle is a pure view change) + the group drag as a
# block move; proven by a compiled+run probe of the real methods) ----
python3 scripts/test_v141.py

# ---- v1.4.2 gate (the egg's FROZEN FRONTIER — maybeSpawn read the spawn
# frontier into a val once, so the first layout pass looped forever: the
# black screen + the whole-app lag + the dead taps, ONE corpse; the
# one-live countdown toast; About moved into deep settings; the
# MEMBERLESS-FOLDER PARITY — a folder whose tabs were all closed vanished
# in follow-tab-order but stayed in always-on-top (the two placements derive
# rows from different sources); local-first suggestions (bookmarks +
# shortcuts above history, deep-settings toggle); proven by a compiled+run
# probe: the corpse shape trips the cap, the fix terminates + monotone +
# fills the horizon + tops up after a scroll; v1.4.3 re-point: the placement
# parity now rides the ONE-MEMORY render — the toggle is a pure view change) ----
python3 scripts/test_v142.py

# ---- v1.4.3 gate (the egg's RETRY CORPSE — resetRun left py=-1, a
# first-run-only sentinel, so every retry tap died instantly off-screen and
# the death screen read as frozen; ONE MEMORY TWO VIEWS — the two group
# placements read two orderings (array vs tm.tabs) and the v1.4.1 sync
# destroyed the interleaved arrangement in the round trip, so the on-top
# render now derives folder order from first-member positions, the drag
# block-moves members in both placements and the sync is deleted; THE
# VAULT'S CALLER-IV CORPSE — ENCRYPT init passed a caller IV for an
# AndroidKeyStore key (prohibited by the documented contract), so no
# password ever saved since v1.3.38; the keystore now generates the IV and
# every vault refusal logs its throwable class+message; proven by a
# compiled+run probe: the placement laws + the cipher round-trip with the
# transcribed init/iv sequence) ----
python3 scripts/test_v143.py

# ---- v1.4.4 gate (the egg's pacing + the follow-order rearrangement:
# the dead Move up/down re-pointed at the rendered neighbour, the
# loose-drop-beside-folder law, the in-group + folder-window reorders,
# the multi-account vault; compiled+run probes: moveTabBesideGroup,
# reorderGroupMember, moveSpeeddialItem, findAll) ----
python3 scripts/test_v144.py

# ---- v1.4.5 gate (the projection materialize: the on-top -> follow switch
# writes the on-top VISUAL into tm.tabs so the follow render draws the
# identical grid — the v1.4.3 "pure view change" anchored folders at their
# first members mid-list and the arrangement jumped; the INTERCEPTION LAW:
# both v1.4.4 hold-to-drag surfaces (the folder-row minis, the
# folder-window cells) lived inside scrollable parents and died as
# ACTION_CANCEL on the first move — the lift now raises the disallow flag
# through the parent chain; the DISAPPEAR HARDENING: duplicate-id folder
# heal, atomic temp-file+rename writes, the dirty-write law (the incognito
# process can no longer rewrite groups/speeddial from its stale copy),
# and the ACTIVE-TAB law (every reorder re-resolves the positional
# index); compiled+run probes: materializeOnTopOrder + render parity,
# dedupeGroups, the atomic write round-trip) ----
python3 scripts/test_v145.py

# ---- v1.4.6 gate (the clobber round two: the folder window's cells were
# wired wireSdWinCell then tilePressFx — the second setOnTouchListener
# SILENTLY REPLACED the first, so the v1.4.4 drag (and the v1.4.5
# interception arms inside it) never ran on device; the feed is now a
# PARAMETER of tilePressFx and the window composes ONE listener; the
# EMPTY-FOLDER ANCHOR: never-emitted folders land right after the last
# folder row in follow-order (the top when none) so the on-top -> follow
# switch keeps empty groups at the top like the membered ones; THE UNDO
# FAMILY: one 3.5 s window for every delete — bookmarks, saved pages
# (soft delete + expiry finalizer), watch-later, folder-window
# remove-from-folder (a dead-since-v1.4.0 no-op corpse found on the way:
# it read sdWinFolderIdx AFTER the teardown zeroed it); compiled+run
# probes: the empty-folder render parity, insertBookmark, the .trash
# round-trip) ----
python3 scripts/test_v146.py

# ---- v1.4.7 gate (the sticky empty tab groups: the membered-slot merge
# in groupsInFirstMemberOrder + the follow-walk slot placement + the
# empty-folder drop path + the last-member swipe capture + the
# materialize slot re-pin; the rotation triple: the MediaHook
# background-rotation mute + BrowserActivity's orientation resync +
# MediaControlService's DisplayListener rotation guard with the
# focus-loss veto; add-shortcut-directly-to-folder: the pill picker +
# Prefs.addSpeeddialItemTo; compiled+run probes: the merge law, the slot
# law with the stick demonstrated on the old walk, the slotless
# convention, the folder add) ----
python3 scripts/test_v147.py

# ---- v1.4.8 gate (the anchor law: empty tab groups settle exactly where
# left — a neighbour-anchored position memory replaces the absolute row
# index; the beside-an-empty landings for tabs and folders; the departure
# refreshes at close/join/move/delete; the normalization sweep honouring
# legacy slots; the folder chooser AFTER "Add to browser home"; the folder
# window's + tile and the edit sheet's Add shortcut row; the chunked site
# settings; compiled+run probe: the settle, the insert-drift corpse
# DEMONSTRATED, the stick-free move, the departure refresh, the chain,
# the quiet upgrade, the top pin; v1.4.9 re-point: the beside-an-empty
# writes moved to the render-neighbour law — see test_v149) ----
python3 scripts/test_v148.py

# ---- v1.4.9 gate (the render-neighbour law: the (2,2) teleport corpse —
# a beside-an-empty landing resolved through the empty's possibly-stale
# stored anchor pair, whose null-fallback was the LIST EDGE, sending the
# dropped group to the very bottom; the inverted re-anchor write that
# rendered the landed row on the opposite side of the empty; the
# follow-the-drag departure refresh — all three fixed by resolving through
# the empty's VISIBLE render neighbours, walking past the departing row,
# with the empty-chain boundary for an empty beside the gap; the chunked
# MAIN menu (eight labelled sections); the visible in-group item menus —
# every tab mini and every folder-window cell wears a ⋯ opening Edit /
# de-group / Delete; compiled+run probe: the teleport, the inversion, the
# chain, the tab landing, the stick, the edge — both old shapes
# DEMONSTRATED) ----
python3 scripts/test_v149.py

# ---- v1.4.9 gate, part 2 (the hardening four-pack: the adblock PATTERN
# DIET — ~130 MB of compiled regex replaced by plain literals + one
# Aho-Corasick scan per request, equivalence-proven against the old
# engine over the full bundled lists; the dead write-only liveWebs
# WebView registry leak; the TabManager thread law vs the media service
# readers; compiled+run probes: automaton vs brute force, old-engine vs
# new-engine decision equivalence) ----
python3 scripts/test_v149_perf.py

# ---- v1.4.10 gate (the version-collision corpse + the pipeline law: the
# bump to 124/1.4.10 because v1.4.9/123 was ALREADY live from the private
# repo; the About changelog label + honesty trim; release.sh retargeted to
# the two public repos with the live-version guard; the dead private repo
# must stay out of the pipeline) ----
python3 scripts/test_v1410.py

# ---- 2. build ----
export JAVA_HOME=$JDK_DIR ANDROID_HOME=$BASE/sdk
# GRADLE_USER_HOME: the workspace-shared cache (see README environment
# notes — a fresh ~/.gradle would re-resolve every dependency)
export GRADLE_USER_HOME=$BASE/.gradle-home
$BASE/gradle-8.9/bin/gradle --no-daemon -q -p $REPO_DIR assembleRelease
APK=$REPO_DIR/app/build/outputs/apk/release/app-release.apk
ls -la "$APK"

# ---- 2b. THE APK SIZE LAW (v1.3.73, user-set) -------------------------
# The base APK must stay super light: "I cannot afford over 5-6mb for the
# base apk" — the v1.3.72 ML Kit bundle hit 23,426,101 B and broke the
# product's whole premise. 6,000,000 B is the hard ceiling (a little
# under the user's stated 5-6 MB range); anything heavier needs an
# on-demand architecture, not a bigger APK.
SIZE=$(stat -c%s "$APK")
if [ "$SIZE" -gt 6000000 ]; then
  echo "ERROR: APK is $SIZE bytes — over the 6,000,000 B budget (README 'APK size watch')."
  echo "       Move the weight out of the APK (see the v1.3.73 tesseract.js pattern) instead of raising this gate."
  exit 1
fi
echo "APK size OK: $SIZE B (budget 6,000,000 B)"

# ---- 3. signing check (same key as every release) ----
CERT=$($ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs "$APK" 2>/dev/null | grep -o 'SHA-256 digest: [a-f0-9]*' | cut -d' ' -f3)
[ "$CERT" = "$CERT_EXPECT" ] || { echo "ERROR: cert mismatch: $CERT"; exit 1; }
echo "cert OK: $CERT"

# ---- 4. commit, tag, push (SOURCE repo: bolt-browser) ----
# The push URL carries the token explicitly — the remote stays clean
# https with no credentials persisted in .git/config.
git push -q "$PUSHURL" main
git tag -f "$TAG"
git push -qf "$PUSHURL" "$TAG"

# ---- 5. local assets ----
STAGE=$BASE/download; mkdir -p $STAGE
cp "$APK" "$STAGE/Bolt-$TAG.apk"
rm -f "$STAGE/Bolt-$TAG.zip"
(cd $STAGE && zip -q -9 "Bolt-$TAG.zip" "Bolt-$TAG.apk")

# ---- 6. release body ----
if [ -n "$NOTES" ]; then
  BODY=$(python3 -c "import json,sys;print(json.dumps(open('$NOTES').read()))")
else
  BODY=$(python3 - "$VER" << 'PYEOF'
import json, sys, re
ver = sys.argv[1]
txt = open('CHANGELOG.md').read()
m = re.search(r'^## \[?v?' + re.escape(ver) + r'\]?\s*.*?(?=^## |\Z)', txt, re.M | re.S)
print(json.dumps(m.group(0).strip() if m else 'Release ' + ver))
PYEOF
)
fi

# ---- 7. create release (or reuse) ----
curl -s -H "$AUTH" -H "$ACC" "$API/releases/tags/$TAG" > /tmp/rel.json
if ! python3 -c "import json,sys;sys.exit(0 if 'id' in json.load(open('/tmp/rel.json')) else 1)" 2>/dev/null; then
  curl -s -X POST -H "$AUTH" -H "$ACC" "$API/releases" \
    -d "{\"tag_name\":\"$TAG\",\"name\":\"Bolt $VER\",\"body\":$BODY}" > /tmp/rel.json
  python3 -c "import json;d=json.load(open('/tmp/rel.json'));assert 'id' in d, d.get('message')"
else
  echo "release $TAG exists — refreshing notes + replacing assets"
  RELID2=$(python3 -c "import json;print(json.load(open('/tmp/rel.json'))['id'])")
  curl -s -X PATCH -H "$AUTH" -H "$ACC" "$API/releases/$RELID2" -d "{\"name\":\"Bolt $VER\",\"body\":$BODY}" > /tmp/rel.json
fi
echo "release id: $(python3 -c "import json;print(json.load(open('/tmp/rel.json'))['id'])")"
RELID=$(python3 -c "import json;print(json.load(open('/tmp/rel.json'))['id'])")
UPURL=$(python3 -c "import json;print(json.load(open('/tmp/rel.json'))['upload_url'].replace('{?name,label}',''))")

# ---- 8. upload the APK asset (replace any existing) ----
for A in "Bolt-$TAG.apk"; do
  for AID in $(curl -s -H "$AUTH" -H "$ACC" "$API/releases/$RELID/assets" | python3 -c "import json,sys;print(' '.join(str(a['id']) for a in json.load(sys.stdin) if a['name']=='$A'))"); do
    curl -s -X DELETE -H "$AUTH" "$API/releases/assets/$AID" || true
  done
  CT="application/vnd.android.package-archive"
  curl -s -X POST -H "$AUTH" -H "Content-Type: $CT" --data-binary "@$STAGE/$A" "$UPURL?name=$A" \
    | python3 -c "import json,sys;d=json.load(sys.stdin);print(' uploaded:',d.get('name'),d.get('size'),'bytes')"
done

# ---- 9. verify ritual: download back, md5 + cert must match ----
sleep 2
AID=$(curl -s -H "$AUTH" -H "$ACC" "$API/releases/tags/$TAG" | python3 -c "import json,sys;print(next(a['id'] for a in json.load(sys.stdin)['assets'] if a['name'].endswith('.apk')))")
curl -sL -H "$AUTH" -H "Accept: application/octet-stream" "$API/releases/assets/$AID" -o /tmp/verify.apk
md5sum "$APK" /tmp/verify.apk
[ "$(md5sum "$APK" | cut -d' ' -f1)" = "$(md5sum /tmp/verify.apk | cut -d' ' -f1)" ] || { echo "ERROR: uploaded APK differs"; exit 1; }

# ---- 10. LIVE verification (the v1.4.10 report: "verify the release is
# live on GitHub" — no mirror hop anymore, the release IS on the public
# releases repo): the /releases/latest endpoint the in-app update checker
# reads must return THIS version, with the APK asset downloadable
# ANONYMOUSLY and byte-identical.
echo "verifying the live release..."
python3 - "$VER" "$API" << 'PYEOF'
import json, sys, urllib.request
ver, api = sys.argv[1], sys.argv[2]
r = json.load(urllib.request.urlopen(api + "/releases/latest"))
assert r["tag_name"] == "v" + ver, f"latest is {r['tag_name']}, expected v{ver}"
apk = next((a for a in r["assets"] if a["name"].endswith(".apk")), None)
assert apk, "no .apk asset on the live release"
data = urllib.request.urlopen(apk["browser_download_url"]).read()   # anonymous
local = open(f"/home/z/my-project/download/Bolt-v{ver}.apk", "rb").read()
assert len(data) == len(local), f"size mismatch: live {len(data)} vs local {len(local)}"
import hashlib
assert hashlib.md5(data).hexdigest() == hashlib.md5(local).hexdigest(), "md5 mismatch"
print(f"LIVE OK: {r['html_url']}")
print(f"  asset {apk['name']} {apk['size']} B, anonymous download verified, md5 match")
PYEOF

echo
echo "SOURCE  : https://github.com/arfan1210/bolt-browser/tree/$TAG"
echo "RELEASE : https://github.com/arfan1210/bolt-browser-releases/releases/tag/$TAG"
