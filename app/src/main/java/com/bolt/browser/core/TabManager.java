package com.bolt.browser.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Tab list + groups, per-process (incognito process has its own instance). */
public class TabManager {
    public static boolean INCOGNITO = false; // set per process

    private static TabManager instance;
    // v1.4.9 — THE THREAD LAW: this list is MUTATED on the UI thread
    // (open/close/drag) and READ from service threads (MediaControlService:
    // media ownership + the phoenix resurrection; Workspaces/TabSleep
    // utilities) — a plain ArrayList let a reader's iterator race a UI
    // mutation into ConcurrentModificationException, and the indexed
    // readers raced size() against get(). It is a synchronizedList now,
    // and every ITERATION — here and in the service readers — holds the
    // list's monitor: that is the documented contract of
    // Collections.synchronizedList (single calls are atomic; iteration
    // is safe ONLY under synchronized(list)).
    public final List<Tab> tabs = java.util.Collections.synchronizedList(new ArrayList<>());
    public int active = -1;
    private final Prefs prefs;
    private final android.content.Context appCtx;   // v1.3.39 (tab trash)

    // v1.3.18: debounced persistence. Structural mutations used to be the ONLY
    // save points (close/move/group), and BrowserActivity.onDestroy() was the
    // last resort — but onDestroy is NOT guaranteed when the user swipes the
    // app from recents or an OEM killer terminates the process, so anything
    // created since the last structural change (new tabs, navigation, title
    // updates) was silently lost: "open 2 new tabs, clear recents, reopen →
    // only the old tabs". Now every mutation schedules a ~600 ms debounced
    // write, and BrowserActivity.onStop() (which ALWAYS runs when the app goes
    // background, unlike onDestroy) flushes synchronously — the on-disk state
    // is at worst one second stale, no matter how the process dies.
    private final Handler saveH = new Handler(Looper.getMainLooper());
    private final Runnable saveRun = this::save;

    /** Debounced save — call on every tab-list/tab-content mutation. Multiple
     *  calls within 600 ms collapse into one disk write. */
    public void scheduleSave() {
        if (INCOGNITO) return;
        saveH.removeCallbacks(saveRun);
        saveH.postDelayed(saveRun, 600);
    }

    /** Immediate save — cancels any pending debounce and writes now. Call from
     *  activity onStop(): the process may be killed at any moment after the app
     *  leaves the foreground, and this is the last guaranteed hook. */
    public void flushSave() {
        if (INCOGNITO) return;
        saveH.removeCallbacks(saveRun);
        save();
    }

    private TabManager(Context c) {
        appCtx = c.getApplicationContext();
        prefs = Prefs.get(c);
        long maxTid = 0L;   // v1.4.8: lift the tid sequence past every
        if (!INCOGNITO && prefs.restoreTabs()) {
            JSONArray a = Fx.readJsonArray(File_(c, "tabs.json"));
            for (int i = 0; i < a.length(); i++) {   // restored id (Tab.bumpTidSeq)
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Tab t = new Tab();
                // v1.4.8: keep the persisted tab id (the anchor keys
                // reference it); legacy saves without one keep the
                // constructor's fresh assignment
                long rtid = o.optLong("tid", 0L);
                if (rtid > 0) t.tid = rtid;
                if (t.tid > maxTid) maxTid = t.tid;
                t.isHome = o.optBoolean("home", true);
                t.url = o.optString("url", "");
                t.title = o.optString("title", "");
                t.groupId = o.optLong("group", 0);
                // v1.3.31: sort keys ("ca" = created, "lv" = last visited);
                // legacy saves carry neither and keep 0 (list order breaks ties)
                t.createdAt = o.optLong("ca", 0L);
                t.lastVisitedAt = o.optLong("lv", 0L);
                // v1.3.88 — DOWNLOAD-STRAND HEAL: a tab persisted while
                // stranded on a download URL (pair serialized by Tab.toJson
                // exactly then) restores onto the real page it came from —
                // the rebuilt WebView would otherwise sit on the dead entry
                // with a single-item history and back would fall out of the
                // tab to the browser home (the round-2 field report).
                String dlu = o.optString("dlu", "");
                String dlf = o.optString("dlf", "");
                if (!dlu.isEmpty() && !dlf.isEmpty() && dlu.equals(t.url)) {
                    t.url = dlf;
                }
                // v1.4.21 — the cold-start half of BACK-HISTORY SURVIVAL:
                // the capped saveState blob ("hist") becomes the tab's
                // restore source when showTab rebuilds its WebView. Absent
                // (every legacy save) or oversize/fat = null and the tab
                // keeps today's loadUrl(t.url) behavior.
                String hb = o.optString("hist", null);
                if (hb != null && hb.length() <= 131072) t.histBlob = hb;
                tabs.add(t);
            }
            try {
                String ai = Fx.readText(File_(c, "tabs_active.json"));
                int aIdx = ai == null ? -1 : Integer.parseInt(ai.trim());
                if (aIdx >= 0 && aIdx < tabs.size()) active = aIdx;
            } catch (Throwable ignored) {}
        }
        if (tabs.isEmpty()) tabs.add(new Tab());
        if (active < 0) active = tabs.size() - 1;
        if (maxTid > 0) Tab.bumpTidSeq(maxTid);   // v1.4.8: identity continuity
    }

    private java.io.File File_(Context c, String n) {
        return new java.io.File(c.getFilesDir(), n);
    }

    public static synchronized TabManager get(Context c) {
        if (instance == null) instance = new TabManager(c.getApplicationContext());
        return instance;
    }

    public Tab current() {
        synchronized (tabs) {
            if (tabs.isEmpty()) {
                tabs.add(new Tab());
                active = 0;
            }
            if (active < 0 || active >= tabs.size()) active = tabs.size() - 1;
            return tabs.get(active);
        }
    }

    public Tab addTab(Tab t) {
        synchronized (tabs) {
            tabs.add(t);
            active = tabs.size() - 1;
        }
        scheduleSave();
        return t;
    }

    public Tab newTabAt(int pos) {
        Tab t = new Tab();
        synchronized (tabs) {
            tabs.add(Math.max(0, Math.min(tabs.size(), pos)), t);
        }
        scheduleSave();
        return t;
    }

    public void close(int idx) {
        Tab t;
        synchronized (tabs) {
            if (idx < 0 || idx >= tabs.size()) return;
            t = tabs.remove(idx);
        }
        // v1.3.39 TAB TRASH (menu-3 #7, default on): the closed tab drops
        // into the 7-day bin before its WebView dies.
        try { com.bolt.browser.util.TabTrash.onClose(appCtx, t); } catch (Throwable ignored) {}
        // v1.3.36 — remember the closed tab for UNDO (the snackbar's
        // action): the WebView is destroyed below, so reopening re-builds it
        // from the tab's url (exactly like a process-death restore).
        lastClosed = t;
        lastClosedAt = idx;
        lastClosedTime = android.os.SystemClock.elapsedRealtime();
        if (t.web != null) {
            // the media service must drop this tab as its playback target
            // before the WebView dies, or its notification would linger in
            // "playing" state with nothing left to control (v1.3.13)
            try { com.bolt.browser.media.MediaControlService.onTabGone(null, idx); }
            catch (Throwable ignored) {}
            t.web.stopLoading();
            t.web.loadUrl("about:blank");
            // v1.3.19: background tabs are detached (and crashed tabs may have
            // no parent at all) — the unguarded cast+call NPE'd exactly there
            if (t.web.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) t.web.getParent()).removeView(t.web);
            t.web.destroy();
        }
        synchronized (tabs) {
            if (active >= tabs.size()) active = tabs.size() - 1;
            if (active < 0) active = 0;
        }
        save();
    }

    // ---------------- v1.3.36 undo-close ----------------

    /** the most recently closed tab (kept for the undo snackbar; the
     *  WebView is already destroyed — reopen rebuilds it from t.url). */
    public Tab lastClosed;
    /** the list index the closed tab had (reinsertion point). */
    public int lastClosedAt = -1;
    /** elapsed-realtime of the close; undo is offered for ~8 s only. */
    public long lastClosedTime;

    /** true when the undo snackbar still has something valid to restore. */
    public boolean undoAvailable() {
        return lastClosed != null && !tabs.contains(lastClosed)
                && android.os.SystemClock.elapsedRealtime() - lastClosedTime < 8000L;
    }

    /** re-insert the last closed tab at its old position and return it
     *  (null when nothing undoable is pending). */
    public Tab reopenLast() {
        if (!undoAvailable()) return null;
        Tab t = lastClosed;
        // the destroyed WebView must never be reused — null it so showTab
        // builds a fresh one and reloads the page
        t.web = null;
        t.homeView = null;
        int at;
        synchronized (tabs) {
            at = Math.max(0, Math.min(tabs.size(), lastClosedAt));
            tabs.add(at, t);
            active = at;
        }
        lastClosed = null;
        lastClosedAt = -1;
        scheduleSave();
        return t;
    }

    public void move(int from, int to) {
        synchronized (tabs) {
            if (from < 0 || from >= tabs.size() || to < 0 || to >= tabs.size() || from == to) return;
            Tab wasActive = (active >= 0 && active < tabs.size()) ? tabs.get(active) : null;
            Tab t = tabs.remove(from);
            tabs.add(to, t);
            reResolveActive(wasActive);   // v1.4.5: same tab stays active
        }
        save();
    }

    public void save() {
        if (INCOGNITO) return; // never persist incognito tabs
        try {
            JSONArray a = new JSONArray();
            synchronized (tabs) {
                // v1.4.21 — the main-thread guard for the hist refresh below:
                // save() normally rides the main-looper handler (saveH), but
                // flushSave() calls it inline from its caller — WebView
                // methods demand the UI thread, so a background flush keeps
                // each tab's last captured blob instead of touching webs.
                boolean onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
                for (Tab t : tabs) {
                // v1.3.18: persist EVERY tab — including pure home/new-tab pages.
                // The old filter (skip isHome tabs with empty url) is exactly how
                // "open 2 new tabs, close via recents, reopen → tabs gone" lost
                // them: a freshly opened tab IS a home tab, and one that hasn't
                // navigated yet never becomes anything else on disk. Restore
                // always handled home tabs; the placeholder auto-created at
                // launch only appears when the list is empty, so removing the
                // filter cannot accumulate stray tabs either.
                // v1.4.21 — BACK-HISTORY SURVIVAL: an AWAKE tab refreshes its
                // saveState capture HERE (a sleeping tab's was captured at
                // its sleep) so tabs.json always carries a FRESH history —
                // a cold start then restores the back/forward list (the
                // "hist" blob), not just the current url.
                if (onMain && t.web != null && !t.isHome) {
                    try { t.saveHist(t.web); } catch (Throwable ignored) {}
                }
                JSONObject o = t.toJson();
                // v1.3.27: playing-media tabs persist a YouTube resume point
                try { o.put("url", resumeUrl(t)); } catch (Throwable ignored) {}
                a.put(o);
                }
            }
            Fx.writeJsonArray(new java.io.File(prefs.filesDir(), "tabs.json"), a);
            Fx.writeText(new java.io.File(prefs.filesDir(), "tabs_active.json"),
                    String.valueOf(active));
        } catch (Throwable ignored) {}
    }

    /** v1.3.31 — the tabs the SWITCHER shows, in the user's chosen sort.
     *  tm.tabs order stays the real (manual/drag) order — the sort is purely
     *  presentational, so dragging still edits the underlying list.
     *  0 = manual (list order), 1 = newest opened first, 2 = last visited
     *  first. Stable sort with the list index as the final tiebreaker so
     *  legacy tabs (timestamp 0) keep their relative order. */
    public List<Tab> sortedForDisplay(int sortMode) {
        synchronized (tabs) {
        List<Tab> out = new ArrayList<>(tabs);
        if (sortMode == 1 || sortMode == 2) {
            final boolean byVisit = sortMode == 2;
            java.util.Collections.sort(out, (a, b) -> {
                long ta = byVisit ? a.lastVisitedAt : a.createdAt;
                long tb = byVisit ? b.lastVisitedAt : b.createdAt;
                if (ta != tb) return ta > tb ? -1 : 1;      // DESC: newest first
                return Integer.compare(tabs.indexOf(a), tabs.indexOf(b));
            });
        }
        return out;
        }
    }

    /** v1.3.27 — the URL a tab persists: for a tab holding media (playing OR
     *  paused) on a YouTube watch URL with a known position, the last
     *  reported position is folded in as &t=<sec>s (replacing any existing
     *  t=/start= param). A process or renderer death mid-video then restores
     *  the tab to approximately the moment playback stopped instead of
     *  restarting the video — and instead of the SPA-stale home page that
     *  doUpdateVisitedHistory already fixed. Harmless everywhere else: the
     *  live t.url is never modified, only the persisted copy.
     *  v1.3.30: made public static — the media service writes the same
     *  resume URL into the playback heartbeat mark so the phoenix
     *  (auto-resume after a process kill) can reload the exact same spot. */
    public static String resumeUrl(Tab t) {
        String u = t.url;
        if (u == null) return "";
        try {
            // v1.3.87 — THE DOWNLOAD-PAGE SWAP: a tab left stranded on a
            // download URL (the field report: "after downloading and
            // reclosing bolt, the download tab sits in the blank download
            // only page and I cannot go back to the releases page") must
            // never RESTORE onto that dead entry — the rebuilt WebView has
            // a single-item history, so back falls out of the tab to the
            // browser home. The in-session repair usually fixes the live
            // tab before any save; this swap covers the race where the
            // process died first.
            if (t.dlUrl != null && t.dlUrl.equals(u)
                    && t.dlFromUrl != null && !t.dlFromUrl.isEmpty()) {
                return t.dlFromUrl;
            }
            if ((t.mediaPlaying || t.holdsMedia) && t.mediaPosSec > 3
                    && u.contains("youtube.com/watch")) {
                String stripped = java.util.regex.Pattern
                        .compile("([?&])(t|start)=([^&#]*)").matcher(u)
                        .replaceAll("");
                if (stripped.endsWith("?") || stripped.endsWith("&")) {
                    stripped = stripped.substring(0, stripped.length() - 1);
                }
                int sec = (int) t.mediaPosSec;
                return stripped + (stripped.contains("?") ? "&" : "?")
                        + "t=" + sec + "s";
            }
        } catch (Throwable ignored) {}
        return u;
    }

    public void closeAll() {
        // v1.4.9: one consistent snapshot under the list monitor, then the
        // teardown runs OUTSIDE it (WebView.destroy() must never hold the
        // lock a media reader needs on its own thread)
        List<Tab> snapshot;
        int n;
        synchronized (tabs) {
            snapshot = new ArrayList<>(tabs);
            n = tabs.size();
        }
        // v1.3.39: everything the sweep closes lands in the trash bin too.
        for (Tab t : snapshot) {
            try { com.bolt.browser.util.TabTrash.onClose(appCtx, t); } catch (Throwable ignored) {}
        }
        for (int i = n - 1; i >= 0; i--) {
            try { com.bolt.browser.media.MediaControlService.onTabGone(null, i); }
            catch (Throwable ignored) {}
        }
        for (Tab t : snapshot) {
            if (t.web != null) {
                try {
                    t.web.stopLoading();
                    ((android.view.ViewGroup) t.web.getParent()).removeView(t.web);
                    t.web.destroy();
                } catch (Throwable ignored) {}
            }
        }
        synchronized (tabs) {
            tabs.clear();
            active = -1;
        }
        save();
    }

    // ---- groups ----
    public List<JSONObject> groups() {
        List<JSONObject> out = new ArrayList<>();
        JSONArray g = prefs.groups();
        for (int i = 0; i < g.length(); i++) {
            JSONObject o = g.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    public void assignGroup(int tabIndex, long groupId) {
        synchronized (tabs) {
            if (tabIndex >= 0 && tabIndex < tabs.size()) {
                tabs.get(tabIndex).groupId = groupId;
            } else {
                return;
            }
        }
        save();
    }

    public List<Tab> tabsOfGroup(long gid) {
        List<Tab> out = new ArrayList<>();
        synchronized (tabs) {
            for (Tab t : tabs) if (t.groupId == gid) out.add(t);
        }
        return out;
    }

    /** v1.4.0 — THE ORPHAN HEAL: any tab whose groupId points at a group
     *  that no longer exists is INVISIBLE in the switcher (a grouped tab
     *  renders only under its group row) while the toolbar count still
     *  includes it — the exact state the v1.3.100 report described ("I
     *  don't see the folders, but the folder count in the toolbar did not
     *  go down"): the pre-v1.4.0 moveGroup REPLACED a neighbour group
     *  (Android's JSONArray.put(int,·) sets, never inserts), orphaning its
     *  tabs. This sweep resets such tabs to ungrouped so they come back,
     *  and runs once per switcher open. Returns the healed count (logged,
     *  and toasted when > 0 so the recovery is never silent). */
    public int healGroups() {
        try {
            // v1.4.5 — first the DUPLICATE-ID heal: a folder id appearing
            // twice renders on-top (the array lists both rows) but
            // VANISHES in follow-order (the walk emits the first match
            // only) — the "a group disappears when I change modes" shape.
            // The dedupe keeps the first row per id; members are shared.
            int healed = prefs.dedupeGroups();
            JSONArray g = prefs.groups();
            java.util.HashSet<Long> live = new java.util.HashSet<>();
            for (int i = 0; i < g.length(); i++) {
                JSONObject o = g.optJSONObject(i);
                if (o != null) live.add(o.optLong("id"));
            }
            synchronized (tabs) {
                for (Tab t : tabs) {
                    if (t.groupId != 0 && !live.contains(t.groupId)) {
                        t.groupId = 0;
                        healed++;
                    }
                }
            }
            if (healed > 0) save();
            return healed;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- v1.4.3 — ONE MEMORY, TWO VIEWS (the placement law, rewritten) ----
    // The v1.4.0 report asked for "no separate memory" between the two group
    // placements; v1.4.1 answered with syncGroupPlacement() — a reconciliation
    // that rebuilt one ordering from the other at every toggle. That kept
    // each SINGLE switch visually continuous but was still TWO memories
    // (the groups array for on-top, tm.tabs for in-order), and the v1.4.3
    // probe proved the corpse: the round trip in-order -> on-top -> in-order
    // rebuilt tm.tabs as [group blocks]+[loose], DESTROYING the interleaved
    // arrangement — "the tab folders move itself by changing modes".
    //
    // THE LAW NOW: tm.tabs is the ONE ordering memory. Both placements are
    // pure VIEWS of it — "on top" renders the folder rows (in first-member
    // order) above the loose tabs; "in order" anchors each folder row at its
    // first member's slot. The group drag block-moves members
    // (moveGroupRelative) in BOTH placements, and re-sorts the groups array
    // to first-member order only so any other reader of the array agrees
    // with the rendered truth.
    //
    // v1.4.5 — THE PROJECTION LAW (the v1.4.4 report: "Tab groups don't
    // stay in the top when I switch from tab groups on top to follow tab
    // order. I want the behavior to be that the tabs and the groups stay in
    // position as it is in the groups on top positions and then let me drag
    // everything manually"). The on-top render is a PROJECTION of tm.tabs
    // (folders lifted to the top, loose tabs below), so a pure view change
    // onto the raw memory lands each folder at its FIRST MEMBER's slot —
    // mid-list, not at the top: the arrangement the user was LOOKING at
    // jumped, and folders that anchored far down read as "disappeared".
    // THE FIX: switching on-top -> follow MATERIALIZES the on-top visual
    // into tm.tabs (materializeOnTopOrder) — the follow render then draws
    // the IDENTICAL arrangement, and every rearrangement from there is a
    // plain drag on the one memory. Follow -> on-top stays a pure view
    // change (the user's law: "always top does what it does now" — folders
    // pin to the top, data untouched).

    /** v1.4.5 — keep the ACTIVE TAB the same OBJECT across a reorder of
     *  tm.tabs. `active` is a POSITIONAL index into the list; every
     *  block-move that rewrites the list must re-resolve it, or the tab
     *  the user was browsing silently becomes a different one the moment
     *  the switcher closes without a tap (showTab(tm.active) in
     *  syncFromTabManager). Call with the tab object captured BEFORE the
     *  list is rewritten. */
    private void reResolveActive(Tab was) {
        if (was == null) return;
        synchronized (tabs) {
            int at = tabs.indexOf(was);
            if (at >= 0) active = at;
        }
    }

    /** the groups reordered by their FIRST MEMBER's position in tm.tabs
     *  (the order BOTH placements render their folder rows in).
     *  v1.4.7 — THE MEMBERED-SLOT MERGE (the "moving one group moves
     *  another empty one" report): the old body sorted with MAX_VALUE for
     *  memberless folders, stomping every EMPTY folder to the array's END
     *  on every membered move — in the on-top render an empty folder
     *  could never keep its place between two membered ones. Each empty
     *  folder now carries its own position: p(E) = the number of MEMBERED
     *  folders before it in the CURRENT array (its inter-folder slot).
     *  The merge emits: empties with p=0 (array order), M'_1, empties
     *  with p=1, M'_2, ..., M'_k, empties with p=k — membered folders
     *  re-sorted by first member as always, but every empty folder stays
     *  between the SAME membered neighbours it sat between, so only the
     *  folder the user actually holds ever moves. (The follow-order
     *  render additionally honours each empty's explicit "slot" field —
     *  see rebuildItems; this merge is the array/on-top memory.) */
    public List<JSONObject> groupsInFirstMemberOrder() {
        synchronized (tabs) {
        List<JSONObject> all = groups();
        java.util.HashMap<Long, Integer> firstAt = new java.util.HashMap<>();
        for (int i = 0; i < tabs.size(); i++) {
            long gid = tabs.get(i).groupId;
            if (gid != 0 && !firstAt.containsKey(gid)) firstAt.put(gid, i);
        }
        // membered (has members right now), re-sorted by first member — stable
        List<JSONObject> membered = new ArrayList<>();
        List<JSONObject> empties = new ArrayList<>();
        for (JSONObject g : all) {
            if (g != null && firstAt.containsKey(g.optLong("id"))) membered.add(g);
            else if (g != null) empties.add(g);
        }
        final java.util.HashMap<Long, Integer> fa = firstAt;
        java.util.Collections.sort(membered, (a, b) ->
                Integer.compare(fa.getOrDefault(a.optLong("id"), Integer.MAX_VALUE),
                        fa.getOrDefault(b.optLong("id"), Integer.MAX_VALUE)));
        if (empties.isEmpty()) return membered;   // the old fast path
        // p(E) = # membered folders before E in the OLD array
        int[] p = new int[empties.size()];
        for (int i = 0, m = 0; i < all.size(); i++) {
            JSONObject g = all.get(i);
            if (g == null) continue;
            if (firstAt.containsKey(g.optLong("id"))) { m++; continue; }
            int e = empties.indexOf(g);
            if (e >= 0) p[e] = m;
        }
        // emit: p=0 empties, M'_1, p=1 empties, M'_2, ..., M'_k, p=k empties
        List<JSONObject> out = new ArrayList<>(all.size());
        for (int j = 0; j <= membered.size(); j++) {
            for (int e = 0; e < empties.size(); e++)
                if (p[e] == j) out.add(empties.get(e));
            if (j < membered.size()) out.add(membered.get(j));
        }
        return out;
        }
    }

    /** v1.4.5 — THE PLACEMENT MATERIALIZE (the projection law's writer):
     *  rewrite tm.tabs into exactly the arrangement the ON-TOP placement
     *  was showing — every folder's member BLOCK (in first-member order,
     *  members in their current order), then the loose tabs (in their
     *  current order). Called when the user switches groups-on-top ->
     *  follow-tab-order, so the follow render (which anchors each folder
     *  row at its first member) draws the IDENTICAL grid the user just
     *  saw: folders stay at the top, nothing jumps, nothing reads as
     *  vanished — and from there every drag reorders the one memory.
     *  Lossless by construction: same multiset of tabs (size + identity
     *  check), the active tab survives as the same OBJECT, and the
     *  memberless folders (whose blocks are empty) stay in the groups
     *  array untouched — the follow render lands them right after the
     *  last folder row (v1.4.6), so even they keep their on-top spot.
     *  v1.4.7 — THE SLOT RE-PIN: an empty folder's follow-order position
     *  is its anchor pair (the two rows it sits between — see
     *  followRowOrder, v1.4.8); the switch must re-pin every empty's
     *  anchors to its neighbours in the ON-TOP folder block (folder rows
     *  render 0..m-1 there), so the post-materialize follow render
     *  reproduces the on-top visual for the empties too — and a later
     *  membered drag can never drag them along (only the one the user
     *  holds ever moves). */
    public boolean materializeOnTopOrder() {
        try {
            synchronized (tabs) {
            Tab wasActive = (active >= 0 && active < tabs.size()) ? tabs.get(active) : null;
            List<JSONObject> order = groupsInFirstMemberOrder();
            List<Tab> out = new ArrayList<>(tabs.size());
            for (JSONObject g : order) {
                out.addAll(tabsOfGroup(g.optLong("id")));
            }
            for (Tab t : tabs) if (t.groupId == 0) out.add(t);
            if (out.size() != tabs.size()) return false;   // never a partial rewrite
            java.util.HashSet<Tab> before = new java.util.HashSet<>(tabs);
            for (Tab t : out) if (!before.remove(t)) return false;  // a stranger appeared
            if (!before.isEmpty()) return false;           // a tab went missing
            tabs.clear();
            tabs.addAll(out);
            reResolveActive(wasActive);
            // v1.4.8 — re-pin every empty folder's ANCHOR pair to its
            // on-top folder-block neighbours (membered folders keep
            // deriving from tm.tabs; the anchor pair is the empty-folder
            // memory — it replaces the v1.4.7 absolute "slot", which any
            // later insert/close above it silently shifted)
            List<Object> topRows = new ArrayList<>(order.size());
            for (JSONObject g : order) topRows.add(g);
            for (Tab t : tabs) if (t.groupId == 0) topRows.add(t);
            for (int i = 0; i < topRows.size(); i++) {
                Object o = topRows.get(i);
                if (!(o instanceof JSONObject)) continue;
                JSONObject g = (JSONObject) o;
                if (!tabsOfGroup(g.optLong("id")).isEmpty()) continue;   // membered
                prefs.setGroupAnchor(g.optLong("id"),
                        i > 0 ? rowKeyOf(topRows.get(i - 1)) : "",
                        i < topRows.size() - 1 ? rowKeyOf(topRows.get(i + 1)) : "");
            }
            }
            save();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // v1.4.8 — THE ANCHOR LAW (the follow-order empty-folder position
    // memory). The v1.4.7 report: "one empty tab group does not settle
    // where I leave it — it settles one tab before or after the position
    // I left it at; it also does not let other tabs sit right next to it
    // and pushes the incoming tabs one position off; the groups feel like
    // they follow a vertical preference." THE CORPSE: the v1.4.7 "slot"
    // was an ABSOLUTE ROW INDEX — (a) written from an adapter position
    // that still contained the dragged empty at its OLD row (no live
    // shuffle: onMove returns false), so the drop landed one off whenever
    // the empty sat above the target; (b) never adjusted when rows were
    // inserted/removed above it, so a tab dropped right before an empty
    // did not shift the empty's slot and the two read as pushing each
    // other off; (c) tabs dropped BESIDE an empty folder's row were a
    // silent no-op (tabsOfGroup was empty, so no anchor tab resolved).
    // THE LAW: a row's position memory must be RELATIVE — the pair of
    // rows it sits BETWEEN ("ap" the row above, "an" the row below;
    // keys "t<tid>" a loose tab row, "g<gid>" a folder row, "" the list
    // edge). Rows can come and go, be dragged anywhere: the empty stays
    // between whatever ends up around it, and only ITS OWN drop (or the
    // placement materialize / last-member capture / the refreshes below)
    // ever rewrites it.
    // ------------------------------------------------------------------

    /** the row key of a follow-order row: "t<tid>" a tab, "g<gid>" a
     *  folder. Anchors are written with THESE keys (a membered tab's key
     *  resolves to its FOLDER row — see rowKeyIndex). */
    public static String rowKeyOf(Object row) {
        if (row instanceof Tab) return "t" + ((Tab) row).tid;
        if (row instanceof JSONObject) return "g" + ((JSONObject) row).optLong("id");
        return "";
    }

    /** the live tab with the given tid (anchor resolution), or null. */
    public Tab tabByTid(long tid) {
        synchronized (tabs) {
            for (Tab t : tabs) if (t.tid == tid) return t;
        }
        return null;
    }

    /** the row index a key points at in [rows], or -1: "t<tid>" — the
     *  tab's own row when loose, its FOLDER row when it is a member (the
     *  folder row is where that tab renders); "g<gid>" — that folder's
     *  row (membered or an already-placed empty). */
    private int rowKeyIndex(List<Object> rows, String key) {
        if (key == null || key.length() < 2) return -1;
        try {
            if (key.charAt(0) == 't') {
                Tab t = tabByTid(Long.parseLong(key.substring(1)));
                if (t == null) return -1;
                if (t.groupId != 0) return folderRowIndex(rows, t.groupId);
                for (int i = 0; i < rows.size(); i++) if (rows.get(i) == t) return i;
                return -1;
            }
            if (key.charAt(0) == 'g') {
                return folderRowIndex(rows, Long.parseLong(key.substring(1)));
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private int folderRowIndex(List<Object> rows, long gid) {
        for (int i = 0; i < rows.size(); i++) {
            Object o = rows.get(i);
            if (o instanceof JSONObject && ((JSONObject) o).optLong("id") == gid) return i;
        }
        return -1;
    }

    /** where an anchored empty [g] inserts into [rows]: before its "an"
     *  row when that resolves, else after its "ap" row; both sides empty
     *  ("","") = pinned to the very top; -1 when neither side resolves
     *  (dangling keys — the row they pointed at is gone) OR when a side
     *  references a REAL empty folder whose row is not placed yet — the
     *  pass loop must land THAT one first, or two empties anchored before
     *  the same row land in REVERSE (the chain-order corpse: E1 pinned
     *  after E2's row rendered BEFORE E2 because E1's own "an" resolved
     *  first and the ap dependency was never consulted). */
    private int anchorRowIn(List<Object> rows, JSONObject g) {
        try {
            String an = g.optString("an", ""), ap = g.optString("ap", "");
            if (g.has("ap") && g.has("an") && an.isEmpty() && ap.isEmpty()) return 0;
            if (pendingEmptyKey(rows, an) || pendingEmptyKey(rows, ap)) return -1;
            if (!an.isEmpty()) {
                int r = rowKeyIndex(rows, an);
                if (r >= 0) return r;                     // before the row below
            }
            if (!ap.isEmpty()) {
                int r = rowKeyIndex(rows, ap);
                if (r >= 0) return r + 1;                 // after the row above
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /** the key references a group that EXISTS but has no row in [rows]
     *  yet — a pending empty this pass loop will place later (a membered
     *  folder always has its row already; a dead id is simply dangling and
     *  never blocks its partner side). */
    private boolean pendingEmptyKey(List<Object> rows, String key) {
        try {
            if (key == null || key.length() < 2 || key.charAt(0) != 'g') return false;
            long gid = Long.parseLong(key.substring(1));
            if (gid == 0 || folderRowIndex(rows, gid) >= 0) return false;
            for (JSONObject gg : groups())
                if (gg != null && gg.optLong("id") == gid)
                    return tabsOfGroup(gid).isEmpty();   // pending empty: wait
        } catch (Throwable ignored) {}
        return false;
    }

    /** v1.4.8 — THE FOLLOW-ORDER ROW MODEL: the one true order of the
     *  follow placement — membered folder rows at their first member,
     *  loose tabs in tm.tabs order, then the memberless folders at their
     *  own anchor-pinned spots (a chain of empties anchored to other
     *  empties resolves in passes; anchorless empties keep the v1.4.6
     *  convention — right after the last folder row, the top when none —
     *  unless [includeAnchorless] is false, which serves the
     *  normalization sweep placing them per their legacy slot). */
    public List<Object> followRowOrder() { return followRowOrder(true); }

    public List<Object> followRowOrder(boolean includeAnchorless) {
        synchronized (tabs) {
        List<Object> out = new ArrayList<>(tabs.size() + 4);
        java.util.HashSet<Long> emitted = new java.util.HashSet<>();
        List<JSONObject> grp = groups();
        for (Tab t : tabs) {
            if (t.groupId != 0 && !emitted.contains(t.groupId)) {
                for (JSONObject g : grp) {
                    if (g != null && g.optLong("id") == t.groupId) { out.add(g); break; }
                }
                emitted.add(t.groupId);
            }
            if (t.groupId == 0) out.add(t);
        }
        List<JSONObject> anchored = new ArrayList<>();
        List<JSONObject> leftover = new ArrayList<>();
        for (JSONObject g : grp) {
            if (g == null || emitted.contains(g.optLong("id"))) continue;
            if (g.has("ap") || g.has("an")) anchored.add(g);
            else leftover.add(g);
        }
        if (!anchored.isEmpty()) {
            // passes: an empty anchored to another empty's row resolves
            // once that one lands (deterministic: guard = empties + 1)
            List<JSONObject> pending = new ArrayList<>(anchored);
            boolean progress = true;
            int guard = pending.size() + 1;
            while (!pending.isEmpty() && progress && guard-- > 0) {
                progress = false;
                for (java.util.Iterator<JSONObject> it = pending.iterator(); it.hasNext(); ) {
                    JSONObject g = it.next();
                    int at = anchorRowIn(out, g);
                    if (at >= 0) {
                        out.add(Math.min(at, out.size()), g);
                        it.remove();
                        progress = true;
                    }
                }
            }
            leftover.addAll(pending);   // dangling keys -> the convention below
        }
        if (includeAnchorless && !leftover.isEmpty()) {
            int lastFolderRow = -1;   // v1.4.6 convention for anchorless empties
            for (int i = 0; i < out.size(); i++)
                if (out.get(i) instanceof JSONObject) lastFolderRow = i;
            int putAt = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;
            for (JSONObject g : leftover) out.add(putAt++, g);
        }
        return out;
        }
    }

    /** v1.4.8 — THE ANCHOR REFRESH: when a row an empty is anchored to
     *  CLOSES, JOINS a folder, or is DRAGGED elsewhere ("only the one I
     *  hold moves" — the empty must never travel with it), re-anchor that
     *  side to the row's PRE-CHANGE neighbour, so the empty keeps its
     *  spot and slides only as its immediate neighbours vanish. Call
     *  BEFORE the mutation (it reads the pre-change row order). */
    public void refreshAnchorsAround(String movedKey) {
        try {
            if (movedKey == null || movedKey.length() < 2) return;
            List<Object> rows = followRowOrder();
            int at = rowKeyIndex(rows, movedKey);
            if (at < 0) return;
            String prevK = at > 0 ? rowKeyOf(rows.get(at - 1)) : "";
            String nextK = at < rows.size() - 1 ? rowKeyOf(rows.get(at + 1)) : "";
            for (JSONObject g : groups()) {
                if (g == null) continue;
                long gid = g.optLong("id");
                if (gid == 0 || !tabsOfGroup(gid).isEmpty()) continue;   // membered
                if (!g.has("ap") && !g.has("an")) continue;               // anchorless
                String ap = g.optString("ap", ""), an = g.optString("an", "");
                if (movedKey.equals(ap) || movedKey.equals(an)) {
                    prefs.setGroupAnchor(gid,
                            movedKey.equals(ap) ? prevK : ap,
                            movedKey.equals(an) ? nextK : an);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** v1.4.8 — THE LAST-MEMBER CAPTURE: call BEFORE the close that
     *  empties group [gid] — pins the folder row's CURRENT neighbours as
     *  its anchors, so the about-to-be-empty folder keeps the exact spot
     *  its row occupied instead of falling to the anchorless convention. */
    public void captureGroupRowAnchors(long gid) {
        try {
            if (gid == 0) return;
            List<Tab> members = tabsOfGroup(gid);
            if (members.size() != 1) return;   // not about to go empty
            List<Object> rows = followRowOrder();
            int at = folderRowIndex(rows, gid);
            if (at < 0) return;
            prefs.setGroupAnchor(gid,
                    at > 0 ? rowKeyOf(rows.get(at - 1)) : "",
                    at < rows.size() - 1 ? rowKeyOf(rows.get(at + 1)) : "");
        } catch (Throwable ignored) {}
    }

    /** v1.4.9 — THE RENDER-NEIGHBOUR LAW: the empty's VISIBLE neighbour in
     *  the follow render, as a row key — the row a beside-the-empty landing
     *  resolves through. THE (2,2) TELEPORT CORPSE: the empty's stored
     *  "an"/"ap" can be STALE while the empty renders mid-list through its
     *  other side — a bare "" written back when the empty WAS the last row
     *  (new tabs opening below never refresh it), or a dangling key whose
     *  row closed through an unrefreshed path; the v1.4.8 landing resolved
     *  through the STORED key and its null-fallback was the LIST EDGE
     *  (lastRowAnchorTab / firstRowAnchorTab), so a group dropped to the
     *  right of such an empty teleported to the very bottom of the tabs
     *  list (the field report's "2,2th element"), dragging the empty down
     *  with it. The RENDER is the truth the user sees — the landing AND the
     *  re-anchor write both resolve through it, so a stale key can never
     *  send a drop to the wrong end of the list.
     *  [skipKey] the row key of a DEPARTING row (the dragged group's row,
     *  still in tm.tabs at call time) — walked past, never returned: the
     *  kept-side neighbour must be a row that SURVIVES the landing. Returns
     *  "" at the render's edge (a genuine first/last-row empty), null when
     *  the empty itself is not in the render. */
    public String renderNeighbourKeyOfEmpty(long gid, boolean below, String skipKey) {
        try {
            if (gid == 0) return null;
            List<Object> rows = followRowOrder();
            int at = folderRowIndex(rows, gid);
            if (at < 0) return null;
            int n = at + (below ? 1 : -1);
            while (n >= 0 && n < rows.size()) {
                String k = rowKeyOf(rows.get(n));
                if (skipKey == null || skipKey.length() < 2 || !k.equals(skipKey)) return k;
                n += below ? 1 : -1;   // the departing row: keep walking outward
            }
            return "";                 // walked off the end: the render's edge
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.8 — THE NORMALIZATION SWEEP (the "maybe disappeared tab groups
     *  in some earlier version is causing interference?" heal): every
     *  memberless group gets an explicit anchor pair pinned at its CURRENT
     *  follow-order position — a v1.4.7 "slot" is honoured (the quiet
     *  upgrade: nothing moves on the day of the update), a slotless empty
     *  pins at the v1.4.6 convention spot — and membered groups drop any
     *  stale position memory. Idempotent: a steady state writes nothing,
     *  so it runs after every render for free. From that moment on,
     *  NOTHING derives its position — no invisible row can push another. */
    public void normalizeEmptyAnchors() {
        try {
            // membered groups never read anchors; clear any stale ones
            List<JSONObject> grp = groups();
            for (JSONObject g : grp) {
                if (g == null) continue;
                long gid = g.optLong("id");
                if (gid == 0) continue;
                if (!tabsOfGroup(gid).isEmpty()) {
                    if (g.has("ap") || g.has("an") || g.has("slot")) prefs.clearGroupPos(gid);
                }
            }
            List<JSONObject> naked = new ArrayList<>();   // anchorless empties
            for (JSONObject g : grp) {
                if (g == null) continue;
                long gid = g.optLong("id");
                if (gid == 0 || !tabsOfGroup(gid).isEmpty()) continue;
                if (!g.has("ap") && !g.has("an")) naked.add(g);
            }
            if (naked.isEmpty()) return;
            // place them exactly as the v1.4.7 render did (legacy slots
            // first: DESC slot, DESC array pos — the old tie law; then the
            // slotless convention), pinning each at its landing spot
            List<Object> rows = followRowOrder(false);
            final java.util.HashMap<JSONObject, Integer> ai = new java.util.HashMap<>();
            for (int i = 0; i < grp.size(); i++) ai.put(grp.get(i), i);
            java.util.Collections.sort(naked, (a, b) -> {
                int sa = a.optInt("slot", -1), sb = b.optInt("slot", -1);
                if (sa != sb) return Integer.compare(sb, sa);   // slot DESC
                return Integer.compare(ai.getOrDefault(b, 0), ai.getOrDefault(a, 0));
            });
            int lastFolderRow = -1;
            for (int i = 0; i < rows.size(); i++)
                if (rows.get(i) instanceof JSONObject) lastFolderRow = i;
            int conv = lastFolderRow >= 0 ? lastFolderRow + 1 : 0;
            for (JSONObject g : naked) {
                int slot = g.optInt("slot", -1);
                int at = slot >= 0 ? Math.min(slot, rows.size()) : conv;
                rows.add(at, g);
                prefs.setGroupAnchor(g.optLong("id"),
                        at > 0 ? rowKeyOf(rows.get(at - 1)) : "",
                        at < rows.size() - 1 ? rowKeyOf(rows.get(at + 1)) : "");
            }
        } catch (Throwable ignored) {}
    }

    /** v1.4.8 — THE REVERSE SYNC: switching follow -> on-top writes the
     *  follow render's folder-row order into the groups ARRAY (the on-top
     *  memory), so the pinned view opens on the SAME arrangement — the
     *  two-memory counterpart of materializeOnTopOrder. (v1.4.5's
     *  projection law ran one way only; an empty dragged in the follow
     *  view never reached the array, so the on-top view could open on a
     *  different arrangement than the one the user just left.) */
    public boolean syncGroupsArrayToFollowOrder() {
        try {
            List<Object> rows = followRowOrder();
            List<JSONObject> order = new ArrayList<>();
            for (Object o : rows) if (o instanceof JSONObject) order.add((JSONObject) o);
            if (order.size() != groups().size()) return false;   // never partial
            prefs.reorderGroups(order);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.4.7 — MOVE AN EMPTY FOLDER (the "fix so that only the one I hold
     *  moves" half: dragging an empty folder was a silent no-op —
     *  moveGroupRelative needs members — and membered drags re-derived
     *  the empties' positions, so they rode along). The dragged folder's
     *  new position lands relative to [anchorId] (a folder row) in the
     *  GROUPS ARRAY (the on-top memory: the membered-slot merge keeps an
     *  empty between the membered neighbours its array position encodes).
     *  Also writes the follow-order "slot" hint so a placement switch
     * carries the drop. Returns true when the array changed. */
    public boolean moveEmptyGroupBeside(long gid, long anchorId, boolean after) {
        try {
            if (gid == 0 || anchorId == 0 || gid == anchorId) return false;
            List<JSONObject> all = groups();
            JSONObject drag = null, anchor = null;
            for (JSONObject g : all) {
                if (g == null) continue;
                long id = g.optLong("id");
                if (id == gid) drag = g;
                else if (id == anchorId) anchor = g;
            }
            if (drag == null || anchor == null) return false;
            if (!tabsOfGroup(gid).isEmpty()) return false;   // membered folders use moveGroupRelative
            // rebuild without the dragged folder, then re-insert it
            // before/after the anchor (a TRUE java-List insert — the AOSP
            // JSONArray.put(int,·) replaces, never inserts)
            java.util.ArrayList<JSONObject> list = new java.util.ArrayList<>(all.size());
            for (JSONObject g : all) if (g != null && g != drag) list.add(g);
            int at = list.indexOf(anchor);
            if (at < 0) return false;
            list.add(after ? at + 1 : at, drag);
            prefs.reorderGroups(list);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.4.1 — THE GROUP DRAG UNDER BOTH PLACEMENTS (v1.4.3: now the only
     *  group-move path): block-move group [gid]'s MEMBER TABS so the folder
     *  row lands before/after the anchor. The anchor BOUNDARY rule (the
     *  "never between members" law): when the anchor tab belongs to another
     *  group, the block lands before that group's FIRST member or after its
     *  LAST member — a folder row can never be dropped INTO the middle of
     *  another folder's members; beside a loose tab, the block lands
     *  directly before/after that tab. The groups array is then re-sorted
     *  to first-member order so every reader of the array agrees with
     *  tm.tabs (the one memory). Returns true when anything moved. */
    public boolean moveGroupRelative(long gid, Tab anchor, boolean after) {
        try {
            synchronized (tabs) {
            if (anchor == null || gid == 0 || anchor.groupId == gid) return false;
            List<Tab> members = tabsOfGroup(gid);
            if (members.isEmpty()) return false;
            Tab wasActive = (active >= 0 && active < tabs.size()) ? tabs.get(active) : null;
            // resolve the boundary tab: a grouped anchor clamps to its
            // group's outer member on the side we're landing
            Tab boundary = anchor;
            if (anchor.groupId != 0) {
                List<Tab> host = tabsOfGroup(anchor.groupId);
                if (host.isEmpty()) return false;
                boundary = after ? host.get(host.size() - 1) : host.get(0);
            }
            java.util.HashSet<Tab> moving = new java.util.HashSet<>(members);
            List<Tab> rest = new ArrayList<>(tabs.size() - members.size());
            for (Tab t : tabs) if (!moving.contains(t)) rest.add(t);
            int at = rest.indexOf(boundary);
            if (at < 0) return false;
            int insert = after ? at + 1 : at;
            rest.addAll(insert, members);
            tabs.clear();
            tabs.addAll(rest);
            reResolveActive(wasActive);   // v1.4.5: same tab stays active
            // keep the groups array in first-member order: every reader of
            // the array sees the SAME arrangement tm.tabs renders
            prefs.reorderGroups(groupsInFirstMemberOrder());
            }
            save();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.4.4 — THE IN-GROUP REORDER (the report: "make the tabs inside the
     *  tab group already to be rearranged inside the group too by
     *  dragging"). Reorders group [gid]'s members among THEMSELVES: member
     *  [from] moves to member position [to] (both are indices in the
     *  member list, tm.tabs order — [to] doubles as the insertion index in
     *  the without-dragged list, the moveSpeeddial convention). The block
     *  lands back at the row's RENDER ANCHOR (the original FIRST member's
     *  slot — the position both placements draw the folder row at), so the
     *  reorder is invisible outside the folder: loose tabs and other
     *  folders keep their positions, and members that had drifted apart
     *  (a join never compacts) naturally re-gather into the block. The
     *  one-memory law holds: tm.tabs is the only thing touched (plus the
     *  groups-array re-sort every writer maintains). */
    public boolean reorderGroupMember(long gid, int from, int to) {
        try {
            synchronized (tabs) {
            List<Tab> members = tabsOfGroup(gid);
            int n = members.size();
            if (gid == 0 || n < 2 || from < 0 || from >= n
                    || to < 0 || to >= n || from == to) return false;
            int anchor = tabs.indexOf(members.get(0));   // the row's render slot
            if (anchor < 0) return false;
            Tab wasActive = (active >= 0 && active < tabs.size()) ? tabs.get(active) : null;
            List<Tab> perm = new ArrayList<>(members);
            perm.add(to, perm.remove(from));
            java.util.HashSet<Tab> moving = new java.util.HashSet<>(members);
            List<Tab> rest = new ArrayList<>(tabs.size() - n);
            for (Tab t : tabs) if (!moving.contains(t)) rest.add(t);
            rest.addAll(Math.min(anchor, rest.size()), perm);
            tabs.clear();
            tabs.addAll(rest);
            reResolveActive(wasActive);   // v1.4.5: same tab stays active
            prefs.reorderGroups(groupsInFirstMemberOrder());
            }
            save();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.4.4 — LAND A LOOSE TAB BESIDE A FOLDER BLOCK (the report: "in the
     *  follow tabs order, tabs are not still manually adjustable" — a drop
     *  next to a folder row was a silent no-op, and in that placement
     *  folders interleave the grid, so HALF the drop targets did nothing).
     *  The dragged LOOSE tab lands immediately before the folder's first
     *  member or after its last member — the never-between-members law,
     *  exactly like a folder drag's boundary. Returns true when it moved. */
    public boolean moveTabBesideGroup(Tab dragged, long gid, boolean after) {
        try {
            synchronized (tabs) {
            if (dragged == null || gid == 0 || dragged.groupId != 0) return false;
            List<Tab> host = tabsOfGroup(gid);
            if (host.isEmpty()) return false;
            Tab boundary = after ? host.get(host.size() - 1) : host.get(0);
            Tab wasActive = (active >= 0 && active < tabs.size()) ? tabs.get(active) : null;
            tabs.remove(dragged);
            int bi = tabs.indexOf(boundary);
            if (bi < 0) {                     // boundary vanished mid-gesture
                tabs.add(dragged);            // abort: re-append, never lose the tab
                return false;
            }
            tabs.add(after ? bi + 1 : bi, dragged);
            reResolveActive(wasActive);   // v1.4.5: same tab stays active
            }
            save();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
