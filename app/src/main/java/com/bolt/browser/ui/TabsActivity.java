package com.bolt.browser.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bolt.browser.R;
import com.bolt.browser.core.Tab;
import com.bolt.browser.core.TabManager;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Prefs;
import com.bolt.browser.util.Ui;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Opera-style tab switcher: grid + named colored groups + drag reorder +
 *  drop-on-tab-to-group + multi-select + swipe to close. */
public class TabsActivity extends AppCompatActivity {

    protected Prefs prefs;
    protected TabManager tm;
    protected RecyclerView rv;
    protected TabsAdapter adapter;
    protected GridLayoutManager glm;
    protected boolean incognitoProcess;

    /** v1.3.46 — the RENDERED-ARRANGEMENT snapshot: the loose tab tiles in
     *  EXACTLY the order the switcher last laid them out (top-left ->
     *  bottom-right), refreshed by TabsAdapter.afterReload() on every rebuild.
     *  BrowserActivity's toolbar swipe walks THIS list (falling back to the
     *  derived sortedForDisplay walk before the switcher has ever opened),
     *  so "swipe left = the tile I saw on the left" holds BY CONSTRUCTION in
     *  every sort/filter/layout — three field rounds of "the swipe is
     *  opposite" could not be reproduced against the derived walk, so the
     *  walk now copies the pixels instead of recomputing the math. Static
     *  per PROCESS: the incognito process gets its own through
     *  TabsIncognitoActivity. */
    private static volatile List<Tab> sRenderedWalk = null;

    /** the rendered arrangement for the swipe walk (null before the switcher
     *  has ever opened in this process; entries may be stale — consumers
     *  filter to live tabs). */
    public static List<Tab> renderedWalk() { return sRenderedWalk; }

    // ---- multi-select state ----
    protected boolean selMode = false;
    protected final HashSet<Tab> selected = new HashSet<>();

    // ---- v1.4.4 — THE IN-GROUP MINI DRAG (reorder members inside the
    // folder row, the report: "make the tabs inside the tab group already to
    // be rearranged inside the group too by dragging"). The launcher-grid
    // law (v1.3.88): a view KEEPS its touch stream after the long-press, so
    // each mini owns its whole gesture once the hold lifts — tap = open,
    // hold-still = the member menu, hold+move = the in-group reorder. The
    // ItemTouchHelper is gated off while a mini drag is armed
    // (getMovementFlags returns 0) so the group row never also lifts. ----
    /** the member being dragged (null = no mini drag armed). */
    protected Tab miniDragTab;
    protected long miniDragGid;
    protected int miniDragFrom = -1, miniDragTo = -1;
    protected boolean miniDragMoved;
    protected View miniDragView;
    protected ViewGroup miniDragStrip;
    /** the strip children's BASE centers (window coords, captured at arm —
     *  translations must never feed the target math). */
    protected float[] miniBaseX = new float[0];
    /** px between adjacent member slots (the shift pitch). */
    protected int miniPitch;
    /** raw coords at DOWN (tap vs hold), tracked live on MOVE (the lift
     *  origin), and at LIFT (drag origin). */
    protected float miniDownX, miniDownY, miniLastX, miniLastY, miniLiftX, miniLiftY;
    /** the hold timer that arms the drag (~380 ms, before the ITH's 500). */
    protected final android.os.Handler miniH = new android.os.Handler();
    protected Runnable miniLiftRun;
    protected View miniLiftCandidate;
    protected boolean miniHeld;

    // ---- v1.3.41 (field bug 3) drag-affordance overlay ----
    /** frame that now WRAPS rv (rv + a transparent overlay layer above it).
     *  The switcher's XML root is a vertical LinearLayout, so a floating
     *  child has no layout room; onCreate swaps rv into this frame in
     *  place, keeping rv's original LinearLayout params on the frame. */
    protected FrameLayout dragOverlayHost, dragOverlay;
    /** v1.3.46: the drop-slot ghost and the live GAP are both RETIRED (the
     *  field report called the gap engine's full-slot slides "ugly, very
     *  ugly"); the drag now moves ONLY the held card (snap zones), and the
     *  reorder slide happens at the DROP (settleDropSlide). The overlay
     *  layer carries only the action chip. */
    /** floating chip naming the armed action (Create group / Join group /
     *  Add to group) over the card that would be grouped with. */
    protected TextView dragBadge;
    protected ValueAnimator badgePulse;
    /** window-position scratch for the per-frame overlay placement. */
    protected final int[] winA = new int[2], winB = new int[2];

    /** v1.3.42 (v1.3.41 field crash) — the switcher's XML root.
     *  The v1.3.41 overlay wrap re-parents rv INTO dragOverlayHost, so
     *  rv.getParent() is now a FrameLayout; two pre-1.3.41 paths
     *  (empty-state slot, tab-search row) still cast that to LinearLayout
     *  and CRASHED the switcher on open (ClassCastException, R8-inlined
     *  into onCreate). Everything that used to be rv's SIBLING must be
     *  added to THIS root; when the wrap failed this is rv's parent. */
    protected LinearLayout switcherRoot() {
        ViewGroup p = rv == null ? null : (ViewGroup) rv.getParent();
        if (p != null && p == dragOverlayHost && p.getParent() instanceof ViewGroup)
            p = (ViewGroup) p.getParent();
        return p instanceof LinearLayout ? (LinearLayout) p : null;
    }

    /** v1.3.47 CRASH FIX (field report: "here is a crash report when trying
     *  to sort tabs" — IllegalStateException: Cannot call this method while
     *  RecyclerView is computing a layout or scrolling, from a drop/settle
     *  path): an adapter notify fired while the RV is mid-layout OR mid-glide
     *  (item-animator / ItemTouchHelper recover callbacks run INSIDE the
     *  layout pass; a dialog tap can land mid-scroll) throws. Every adapter
     *  mutation entry (and the drop-settle) now defers itself to the next
     *  frame when the RV is busy. Returns true when the call was deferred. */
    protected boolean deferIfBusy(Runnable op) {
        try {
            if (rv != null && (rv.isComputingLayout()
                    || rv.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)) {
                rv.post(op);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** the tabs grid's drag callback (v1.3.47: kept as a field so the RV's
     *  scroll listener can feed it scroll-compensation during a drag). */
    private DragMergeCallback dragCb;

    /** v1.4.16 — THE MID-DRAG RELOAD DEFERRAL (the "tab ghosting on move"
     * corpse): any adapter.reload() that fires while the grid's drag is
     * live (a dialog confirm racing the gesture, a tab-list mutation from
     * an intent, a settle posted from an EARLIER drop) rebuilds the items
     * and notifyDataSetChanged-es the board mid-gesture — the RecyclerView
     * recycles the dragged ViewHolder under ItemTouchHelper's feet and the
     * card goes ghost (invisible while "there", or duplicated at its old
     * slot). reload() now parks the request here instead and clearView
     * flushes it when the gesture is over (see flushPendingReload). */
    private boolean pendingReloadDuringDrag = false;

    /** v1.4.16 — run a reload that was parked mid-drag. Called from
     *  DragMergeCallback.clearView's finally, i.e. after every drag ends
     *  (settle animations included). Posted, not inline: a deferred
     *  settleDropSlide may still be queued from THIS drop, and the posted
     *  reload lands after it in FIFO order — a second reload of identical
     *  data is a visual no-op, so sequencing is all that matters. */
    protected void flushPendingReload() {
        if (!pendingReloadDuringDrag) return;
        pendingReloadDuringDrag = false;
        if (rv != null) rv.post(() -> {
            try { if (adapter != null) adapter.reload(); } catch (Throwable ignored) {}
        });
    }

    // v1.3.78 — the favicon-generation poller: icons land asynchronously
    // (DuckDuckGo network fallback, late onReceivedIcon) AFTER the switcher
    // already bound its letter tiles, and homeGen only rebuilds the home
    // view — the switcher never heard about it ("the tabs icons sometimes
    // does not update properly"). A 700 ms poll of FaviconStore.favGen while
    // resumed rebinds the rows the moment a landing bumps it.
    private final android.os.Handler favPoll = new android.os.Handler();
    private int favGenSeen = -1;
    private final Runnable favPollTick = new Runnable() {
        @Override public void run() {
            try {
                int g = com.bolt.browser.util.FaviconStore.gen();
                if (favGenSeen < 0) favGenSeen = g;
                else if (g != favGenSeen) {
                    favGenSeen = g;
                    if (adapter != null) adapter.rebindIcons();
                }
            } catch (Throwable ignored) {}
            favPoll.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Fx.theme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tabs);
        favPoll.postDelayed(favPollTick, 700);
        prefs = Prefs.get(this);
        tm = TabManager.get(this);
        incognitoProcess = TabManager.INCOGNITO;
        // v1.4.0 — THE ORPHAN HEAL, once per switcher open: tabs whose group
        // was destroyed by the pre-v1.4.0 moveGroup corpse come BACK (the
        // "I don't see the folders, but the count did not go down" state).
        // Toasted so the recovery is never silent.
        try {
            int healed = tm.healGroups();
            if (healed > 0) {
                Fx.toast(this, getString(R.string.group_orphans_healed, healed));
            }
        } catch (Throwable ignored) {}

        rv = findViewById(R.id.rv_tabs);
        glm = new GridLayoutManager(this, 2) {
            @Override
            public boolean canScrollVertically() { return true; }
            // v1.3.48 — "the tab disappears once the screen starts scrolling"
            // (the v1.3.47 edge auto-scroll field report). LinearLayoutManager
            // recycles by LAYOUT bounds: the recycle check transforms a child
            // only by its view MATRIX (getTransformedBoundingBox) — TRANSLATION
            // is invisible to it. The dragged card is glued to the finger by
            // translation alone, so once the auto-scroll pushed its HOME SLOT
            // out of the viewport, the view was recycled → ItemTouchHelper saw
            // the detach (onChildViewDetachedFromWindow → select(null)) and
            // CANCELLED the drag: the card went invisible for the rest of the
            // gesture while clearView() still resolved the drop at the last
            // finger point — exactly "it is there where my finger is but not
            // visible on screen". THE FIX: extra layout space on the far side
            // of the scroll direction feeds LayoutState.mNoRecycleSpace
            // (updateLayoutState: scrolling toward END takes extraForStart as
            // its no-recycle budget), pushing the recycle limit past the
            // dragged item — its home slot stays ATTACHED however far the grid
            // scrolls, and the card keeps drawing at the finger (v1.3.49: the
            // stock deltas are viewport-stable on their own — the card stays
            // under the finger with zero extra math). The side that needs
            // no protection only gets a one-viewport lookahead, so the fill
            // cost stays bounded. Outside a drag this returns super → 0 extra.
            @Override
            public void calculateExtraLayoutSpace(
                    @NonNull RecyclerView.State state, @NonNull int[] extra) {
                super.calculateExtraLayoutSpace(state, extra);
                try {
                    View dv = dragCb == null ? null : dragCb.draggedItemView();
                    if (dv == null || getHeight() <= 0) return;
                    int vh = getHeight();
                    int above = -dv.getTop();          // px of home slot scrolled above the viewport top
                    int below = dv.getBottom() - vh;   // px of home slot below the viewport bottom
                    extra[0] = Math.max(extra[0], above > 0 ? above + 2 * vh : vh);
                    extra[1] = Math.max(extra[1], below > 0 ? below + 2 * vh : vh);
                } catch (Throwable ignored) {}
            }
        };
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                // v1.3.39: in CARD view groups are CARDS TOO ("make the
                // folder tab a card also with a folder logo in card view")
                // — one grid span, folder-icon card. In LIST view the group
                // row still spans the full width.
                if (adapter != null && adapter.isGroup(position)) {
                    return prefs.tabsAsCards() ? 1 : Math.max(1, glm.getSpanCount());
                }
                return 1;
            }
        });
        rv.setLayoutManager(glm);
        adapter = new TabsAdapter();
        rv.setAdapter(adapter);

        // v1.3.47 — feed every grid scroll into the live drag: the callback
        // keeps the finger bookkeeping and the free-space card glued to the
        // finger while the grid scrolls under it (edge auto-scroll).
        dragCb = new DragMergeCallback();
        new ItemTouchHelper(dragCb).attachToRecyclerView(rv);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (dragCb != null && dy != 0) dragCb.onDragScroll(dy);
            }
        });

        // v1.3.41 (field bug 3) — wrap rv in a frame that carries a
        // transparent overlay layer on top of it. The drag callback parks
        // the group-action chip in that layer (v1.3.45: the slot preview
        // is the REAL GAP the tiles open, no ghost view needed); the chip
        // is not clickable, so touches pass straight through.
        try {
            ViewGroup rvParent = (ViewGroup) rv.getParent();
            int rvIdx = rvParent.indexOfChild(rv);
            ViewGroup.LayoutParams rvLp = rv.getLayoutParams();
            rvParent.removeView(rv);
            dragOverlayHost = new FrameLayout(this);
            dragOverlayHost.addView(rv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            dragOverlay = new FrameLayout(this);
            dragOverlay.setClickable(false);
            dragOverlay.setVisibility(View.GONE);
            dragOverlayHost.addView(dragOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            rvParent.addView(dragOverlayHost, rvIdx, rvLp);
            buildDragOverlays();
        } catch (Throwable ignored) { /* overlay is a luxury, never fatal */ }

        findViewById(R.id.btn_t_back).setOnClickListener(v -> {
            if (selMode) { exitSelMode(); return; }
            finish();
        });
        // v1.3.40 — "select" and "close all" left the header (six 44dp
        // buttons in one row was the congestion complaint, and close-all's
        // bin icon was pixel-identical to the trash bin). Both actions now
        // live in the 3-dot menu — showTabsOptionsMenu() — with the select
        // mode keeping its own contextual entries there.
        TextView chipNormal = findViewById(R.id.chip_normal);
        TextView chipIncognito = findViewById(R.id.chip_incognito);
        chipNormal.setOnClickListener(v -> {
            if (incognitoProcess) {
                // v1.3.86 — THE HOP THAT STICKS. The v1.3.85 field report:
                // "cannot switch from incognito to cognito, button brings
                // back to incognito". The old hop did startActivity(NEW_TASK
                // | REORDER_TO_FRONT) + finish() in the SAME frame — the
                // finish's resume-next transaction (IncognitoActivity in
                // THIS task) can land after the task move and re-front the
                // incognito task, especially when the main task must cold
                // start (main process reclaimed while the user browsed
                // incognito): the user watches the switch bounce back.
                // Two changes: (a) CLEAR_TOP — the main task's top is
                // usually the STALE TabsActivity the user left when they
                // went incognito; the hop must land in the BROWSER, not in
                // yesterday's tabs view; (b) the finish rides a short post
                // so the task switch settles first — destroying a
                // backgrounded task's top can never re-front it.
                try {
                    com.bolt.browser.util.Diag.log(this, "hop normal");
                } catch (Throwable ignored) {}
                Intent i = new Intent(this, BrowserActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(this::finish, 350);
            } else {
                refreshChips(chipNormal, chipIncognito);
            }
        });
        chipIncognito.setOnClickListener(v -> {
            if (!incognitoProcess) {
                Intent i = new Intent(this, IncognitoActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
            refreshChips(chipNormal, chipIncognito);
        });
        // v1.3.34 — the new-tab FAB: white icon on the accent circle + a
        // whisper of elevation on light skins (the icon's ?attr/colorTint
        // would be a dark gray on the accent fill in Light mode)
        // v1.3.35 — moved into onCreate with a CONTRAST-AWARE icon color
        // (a paper-white Mono accent needs a dark icon, not white).
        findViewById(R.id.btn_new_tab).setOnClickListener(v -> {
            Ui.tap(v);
            tm.addTab(new Tab());
            finish();
        });
        findViewById(R.id.btn_new_incognito).setOnClickListener(v -> {
            if (incognitoProcess) {
                tm.addTab(new Tab());
                finish();
            } else {
                Intent i = new Intent(this, IncognitoActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                // v1.3.86 — same-frame finish raced the cross-task start the
                // same way as the chip hop (see chipNormal); post it
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(this::finish, 350);
            }
        });
        findViewById(R.id.btn_new_group).setOnClickListener(v -> {
            if (selMode) { groupSelected(); return; }
            createGroup();
        });
        // v1.3.31 — the tabs 3-dot menu: sorting, group placement and card/list
        // view live HERE (and mirrored in Settings > Tabs) so the toolbar
        // stays clean while every switcher preference stays one tap away.
        findViewById(R.id.btn_t_menu).setOnClickListener(v -> showTabsOptionsMenu());
        // v1.3.39 — the trash bin entry (deep-settings toggle gates it)
        try {
            android.view.View trash = findViewById(R.id.btn_t_trash);
            trash.setVisibility(com.bolt.browser.util.TabTrash.enabled(this)
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            trash.setOnClickListener(v -> {
                Ui.tap(v);
                startActivity(new Intent(this, com.bolt.browser.ui.TabTrashActivity.class));
            });
        } catch (Throwable ignored) {}

        // (v1.3.47: the ItemTouchHelper now attaches in onCreate's setup
        //  block above — dragCb is a field so the scroll listener can feed
        //  it scroll compensation during a drag.)
        // v1.3.35: the animated tab-card pass — new cards scale/pop in,
        // removals animate the collapse (see PopAnimator)
        rv.setItemAnimator(new Ui.PopAnimator());
        applyViewMode();
        setupTabsEmptyState();
        // v1.3.36 FIELD FIX ("half of the tab menu is blank… like a white
        // overlay hiding half the bottom screen"): the empty-state slot used
        // to be created AFTER this reload ran — a freshly added, never-hidden
        // weight-1 FrameLayout sitting next to the weight-1 RecyclerView, so
        // the two split the screen 50/50 and the blank lower half was that
        // empty slot. It now starts GONE and the state is applied BEFORE the
        // first reload.
        adapter.reload();
        setupTabSearch();
        scrollToActive();
        updateSelUi();
        // v1.3.35: contrast-aware FAB icon (Mono's paper accent = dark icon;
        // every other accent keeps white)
        fab = findViewById(R.id.btn_new_tab);
        fab.setColorFilter(Fx.onAccent(this));
        if (com.bolt.browser.util.Ui.wantShadows(this)) fab.setElevation(Fx.dp(this, 4));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v1.3.78: an icon may have landed while another activity was in
        // front — rebind once, then keep the poller armed
        if (adapter != null) adapter.rebindIcons();
        // v1.4.15 — THE STALE-HIGHLIGHT FIX. rebindIcons() only re-binds the
        // rows the adapter ALREADY holds; it never rebuilds the model. If the
        // active tab moved while the switcher was covered (a notification or
        // external intent routed through BrowserActivity, a group sheet
        // action, a satellite screen tapping a row), the switcher reopened
        // highlighting the OLD tm.active — applySelVisuals() reads live state
        // but nothing re-triggered it against a rebuilt model. reload() is
        // rebuildItems() + notifyDataSetChanged() + afterReload(): the accent
        // rim and the bottom strip re-derive from the CURRENT tm.active, and
        // an active search filter survives (rebuildItems re-applies it).
        if (adapter != null) adapter.reload();
        favPoll.removeCallbacks(favPollTick);
        favPoll.postDelayed(favPollTick, 700);
    }

    @Override
    protected void onPause() {
        favPoll.removeCallbacks(favPollTick);
        super.onPause();
    }

    /** v1.3.35 — the modern empty state for an all-closed switcher.
     *  v1.3.36: the slot is created GONE (it only ever shows when there is
     *  nothing to show) and the state is refreshed right here so it can
     *  never be left half-owning the screen. */
    protected void setupTabsEmptyState() {
        android.widget.FrameLayout slot = new android.widget.FrameLayout(this);
        LinearLayout root = switcherRoot();
        if (root == null) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slot.setVisibility(View.GONE);
        // v1.3.42: the slot goes under the LIST AREA — that is the overlay
        // host once the v1.3.41 wrap happened (rv sits one level deeper
        // inside it; the old indexOfChild(rv) would return -1 and drop the
        // slot at the TOP of the screen even without the cast crash).
        View anchor = (dragOverlayHost != null && dragOverlayHost.getParent() == root)
                ? dragOverlayHost : rv;
        int idx = root.indexOfChild(anchor) + 1;
        root.addView(slot, idx, lp);
        emptySlot = slot;
        updateTabsEmptyState();
    }

    protected void updateTabsEmptyState() {
        boolean empty = searching()
                ? adapter.getItemCount() == 0
                : (tm.tabs.isEmpty() && tm.groups().isEmpty());
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
        // v1.3.42: the host owns the weight while rv lives inside it — a
        // VISIBLE host would keep claiming half the screen next to the
        // weight-1 empty slot (the v1.3.36 half-blank bug, resurrected).
        if (dragOverlayHost != null)
            dragOverlayHost.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (emptySlot == null) return;
        emptySlot.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptySlot.removeAllViews();
        if (empty) {
            View art = com.bolt.browser.util.Ui.emptyState(this, R.drawable.ic_tabcount,
                    searching() ? getString(R.string.no_tabs_found)
                            : getString(R.string.empty_tabs_title),
                    searching() ? getString(R.string.no_tabs_found_sub)
                            : getString(R.string.empty_tabs_sub));
            android.widget.FrameLayout.LayoutParams alp =
                    new android.widget.FrameLayout.LayoutParams(-1, -2);
            alp.gravity = Gravity.CENTER;
            emptySlot.addView(art, alp);
        }
    }

    protected android.widget.FrameLayout emptySlot;
    protected ImageButton fab;

    @Override
    public void onBackPressed() {
        if (searching()) { exitTabSearch(); return; }
        if (selMode) { exitSelMode(); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ v1.3.36 tab search

    /** the live filter query (null = not searching). */
    protected String tabQuery;
    protected EditText tabSearchField;

    protected boolean searching() { return tabQuery != null; }

    /** a magnifier in the header toggles a search row under the segmented
     *  control; typing filters the grid flat (groups unroll) by title/url —
     *  the "find that tab" moment for heavy tab loads. */
    protected void setupTabSearch() {
        final ImageButton btn = findViewById(R.id.btn_t_search);
        if (btn == null) return;
        btn.setColorFilter(Fx.dim(this));
        btn.setOnClickListener(v -> {
            if (searching()) exitTabSearch();
            else enterTabSearch();
        });
    }

    protected void enterTabSearch() {
        if (tabSearchField != null && tabSearchField.getParent() != null) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(getDrawable(R.drawable.bg_urlbar));
        int ph = Fx.dp(this, 14), pv = Fx.dp(this, 5);
        row.setPadding(ph, pv, Fx.dp(this, 6), pv);
        LinearLayout root = switcherRoot();
        if (root == null) return;
        // insert directly under the segmented control (its row is the padded
        // LinearLayout that wraps chip_normal). v1.3.42: chip lookup goes
        // through the ACTIVITY view tree (root.findViewById would now also
        // work, but this is robust however the wrap landed) with a safe
        // fallback to just above the list area.
        View chip = findViewById(R.id.chip_normal);
        View segRow = (chip != null && chip.getParent() != null
                && chip.getParent().getParent() != null)
                ? (View) chip.getParent().getParent() : null;
        int at = segRow != null ? root.indexOfChild(segRow) : -1;
        if (at < 0) at = root.indexOfChild(dragOverlayHost != null
                && dragOverlayHost.getParent() == root ? dragOverlayHost : rv);
        if (at < 0) at = root.getChildCount() - 1;
        at += 1;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        int m = Fx.dp(this, 16);
        lp.leftMargin = m;
        lp.rightMargin = m;
        lp.bottomMargin = Fx.dp(this, 4);
        tabSearchField = new EditText(this);
        tabSearchField.setSingleLine(true);
        tabSearchField.setTextSize(15);
        tabSearchField.setHint(R.string.search_tabs_hint);
        tabSearchField.setHintTextColor(Fx.dim(this));
        tabSearchField.setTextColor(Fx.txt(this));
        tabSearchField.setBackground(null);
        tabSearchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        row.addView(tabSearchField, new LinearLayout.LayoutParams(0, Fx.dp(this, 40), 1f));
        ImageButton clear = new ImageButton(this);
        clear.setImageResource(R.drawable.ic_close);
        clear.setColorFilter(Fx.dim(this));
        clear.setBackground(getDrawable(R.drawable.bg_item_round));
        clear.setOnClickListener(v -> {
            if (tabSearchField != null) tabSearchField.setText("");
        });
        row.addView(clear, new LinearLayout.LayoutParams(Fx.dp(this, 36), Fx.dp(this, 36)));
        tabSearchField.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable s) {
                String q = s.toString().trim();
                tabQuery = q.isEmpty() ? "" : q.toLowerCase();
                adapter.reload();
            }
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
        });
        root.addView(row, at, lp);
        ImageButton btn = findViewById(R.id.btn_t_search);
        if (btn != null) btn.setColorFilter(Fx.accent(this));
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(tabSearchField, 0);
        } catch (Throwable ignored) {}
        tabSearchField.requestFocus();
    }

    protected void exitTabSearch() {
        tabQuery = null;
        if (tabSearchField != null && tabSearchField.getParent() instanceof ViewGroup) {
            try {
                ((ViewGroup) tabSearchField.getParent().getParent())
                        .removeView((View) tabSearchField.getParent());
            } catch (Throwable ignored) {}
        }
        tabSearchField = null;
        ImageButton btn = findViewById(R.id.btn_t_search);
        if (btn != null) btn.setColorFilter(Fx.dim(this));
        hideKeyboard();
        adapter.reload();
        scrollToActive();
    }

    protected void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null)
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ v1.3.36 undo close

    /** the undo snackbar after any single-tab close in this screen: the
     *  closed Tab is remembered by the TabManager; UNDO re-inserts it at its
     *  old position and rebuilds the grid. v1.4.6: every undo in the app
     *  shares the one 3.5 s window (Ui.UNDO_MS). */
    protected void showUndoSnack() {
        try {
            if (!tm.undoAvailable()) return;
            String name = tm.lastClosed.isHome ? getString(R.string.home)
                    : (tm.lastClosed.title == null || tm.lastClosed.title.isEmpty()
                    ? tm.lastClosed.url : tm.lastClosed.title);
            Ui.snackbar(this, getString(R.string.tab_closed) + " · " + name,
                    getString(R.string.undo), () -> {
                        Tab back = tm.reopenLast();
                        if (back == null) return;
                        adapter.reloadInsertedTab(back);
                    }, Ui.UNDO_MS, null);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ select mode

    protected void enterSelMode() {
        selMode = true;
        selected.clear();
        adapter.reload();
        updateSelUi();
        Fx.toast(this, getString(R.string.select_hint));
    }

    protected void exitSelMode() {
        selMode = false;
        selected.clear();
        adapter.reload();
        updateSelUi();
    }

    protected void updateSelUi() {
        TextView title = findViewById(R.id.tv_t_title);
        TextView hint = findViewById(R.id.tv_drag_hint);
        TextView groupBtn = findViewById(R.id.btn_new_group);
        if (title == null || hint == null || groupBtn == null) return;
        if (selMode) {
            title.setText(getString(R.string.selected_count, selected.size()));
            boolean all = !tm.tabs.isEmpty() && selected.size() == tm.tabs.size();
            hint.setText(all ? getString(R.string.deselect_all)
                    : getString(R.string.select_all, tm.tabs.size()));
            hint.setOnClickListener(v -> {
                if (selected.size() == tm.tabs.size()) selected.clear();
                else selected.addAll(tm.tabs);
                adapter.reload();
                updateSelUi();
            });
            groupBtn.setText(getString(R.string.group_selected, selected.size()));
        } else {
            title.setText(getString(R.string.tabs) + " (" + tm.tabs.size() + ")");
            // v1.3.41 (field bug 3): the hint stops promising manual reorder
            // in the sorted modes — where a plain drop is a no-op now.
            hint.setText(R.string.drag_hint);   // v1.3.47: manual-only — always draggable
            hint.setOnClickListener(null);
            groupBtn.setText(R.string.new_group);
        }
    }

    /** toggle one tab's selection and refresh */
    protected void toggleSel(Tab t) {
        if (selected.contains(t)) selected.remove(t);
        else selected.add(t);
        adapter.reload();
        updateSelUi();
    }

    /** create a group from the currently selected tabs. v1.3.40 (round-4
     *  #11): the button now opens a CHOOSER — “New group…” or merge the
     *  selection into an EXISTING group (rename/recolor live on the group
     *  card's own menu; this adds the missing merge path). */
    protected void groupSelected() {
        if (selected.isEmpty()) {
            Fx.toast(this, getString(R.string.select_hint));
            return;
        }
        final List<Tab> picks = new ArrayList<>(selected);
        List<JSONObject> groups = new ArrayList<>();
        try {
            groups.addAll(tm.groups());
        } catch (Throwable ignored) {}
        final List<JSONObject> fGroups = groups;
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.new_group));
        for (JSONObject g : groups) {
            int n = 0;
            synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
                for (Tab t : tm.tabs) if (t.groupId == g.optLong("id")) n++;
            }
            opts.add(g.optString("name") + "  ·  " + n);
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.group_selected, picks.size()))
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        promptGroupName(name -> {
                            String gname = name;
                            if (gname == null || gname.trim().isEmpty()) {
                                // derive from the most common domain, else "Group"
                                gname = Fx.baseDomain(Fx.hostOf(picks.get(0).url == null ? "" : picks.get(0).url));
                                if (gname == null || gname.isEmpty()) gname = "Group";
                            }
                            gname = gname.trim();
                            JSONObject g = prefs.addGroup(gname,
                                    (int) (System.currentTimeMillis() % Fx.GROUP_COLORS.length));
                            long gid = g.optLong("id");
                            if (gid != 0) {
                                for (Tab t : picks) t.groupId = gid;
                                tm.save();
                                exitSelMode();
                                Fx.toast(this, getString(R.string.group_created) + ": " + gname);
                            }
                        });
                    } else if (which - 1 < fGroups.size()) {
                        // merge the selection into an existing group
                        long gid = fGroups.get(which - 1).optLong("id");
                        String gname = fGroups.get(which - 1).optString("name");
                        if (gid != 0) {
                            for (Tab t : picks) t.groupId = gid;
                            tm.save();
                            exitSelMode();
                            adapter.reload();
                            Fx.toast(this, picks.size() + " → " + gname);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** name prompt used by group creation (groupSelected) */
    private interface NameCb { void run(String name); }

    protected void promptGroupName(final NameCb cb) {
        final EditText et = new EditText(this);
        et.setHint(R.string.group_name);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 20);
        wrap.setPadding(p, p / 2, p, 0);
        wrap.addView(et, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_group)
                .setView(wrap)
                .setPositiveButton(R.string.ok, (d, w) -> cb.run(et.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    protected void closeSelected() {
        if (selected.isEmpty()) return;
        final List<Tab> picks = new ArrayList<>(selected);
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.close_selected, picks.size()) + "?")
                .setPositiveButton(R.string.yes, (d, w) -> {
                    for (Tab t : picks) {
                        int idx = tm.tabs.indexOf(t);
                        if (idx >= 0) tm.close(idx);
                    }
                    exitSelMode();
                })
                .setNegativeButton(R.string.no, null).show();
    }

    // ------------------------------------------- v1.3.41 (field bug 3) drag overlays

    /** v1.3.43 — the action chip's two faces: the solid accent pill for
     *  an ARMED action, and a track+clip pill whose fill level shows the
     *  dwell progress ("keep holding = group") before anything arms. */
    protected GradientDrawable badgeSolidBg;
    protected LayerDrawable badgeFillBg;
    protected ClipDrawable badgeClip;

    /** v1.3.46 — the ghost is gone and so is the live GAP (the field
     *  report: "when I hold a tab, the immediate right tab just vanish and
     *  move off screen"). The overlay layer now carries only the action
     *  chip (dwell progress + armed pill); all lookups stay null-safe so
     *  the drag logic works (without decoration) even if the wrap failed. */
    protected void buildDragOverlays() {
        int accent = Fx.accent(this);
        dragBadge = new TextView(this);
        dragBadge.setMaxLines(1);
        dragBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        dragBadge.setTextColor(0xFFFFFFFF);
        dragBadge.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        dragBadge.setPadding(Fx.dp(this, 12), Fx.dp(this, 5), Fx.dp(this, 12), Fx.dp(this, 5));
        // v1.3.43: armed pill + progress pill (dim track, accent fill that
        // clips left-to-right as the dwell accumulates)
        badgeSolidBg = new GradientDrawable();
        badgeSolidBg.setCornerRadius(Fx.dp(this, 999));
        badgeSolidBg.setColor(accent);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(Fx.dp(this, 999));
        track.setColor(0x66333333);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(Fx.dp(this, 999));
        fill.setColor(accent);
        badgeClip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
        badgeFillBg = new LayerDrawable(new android.graphics.drawable.Drawable[]{track, badgeClip});
        dragBadge.setBackground(badgeSolidBg);
        dragBadge.setVisibility(View.GONE);
        dragBadge.setElevation(Fx.dp(this, 6));
        dragOverlay.addView(dragBadge, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        badgePulse = ValueAnimator.ofFloat(0f, 1f);
        badgePulse.setDuration(700);
        badgePulse.setRepeatMode(ValueAnimator.REVERSE);
        badgePulse.setRepeatCount(ValueAnimator.INFINITE);
        badgePulse.addUpdateListener(a -> {
            if (dragBadge != null)
                dragBadge.setAlpha(0.7f + 0.3f * (float) a.getAnimatedValue());
        });
    }

    /** v1.3.46 — retired with the ghost AND the gap (no call sites remain;
     *  kept one release as a no-op, now deleted for good). */

    /** parks the action chip centered on the given card's top edge (the
     *  measuring/positioning half, shared by the armed + progress faces). */
    protected void positionDragBadge(View anchor) {
        int h = Fx.dp(this, 30);
        dragBadge.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        anchor.getLocationInWindow(winA);
        dragOverlay.getLocationInWindow(winB);
        dragBadge.setTranslationX(winA[0] - winB[0] + (anchor.getWidth()
                - dragBadge.getMeasuredWidth()) / 2f);
        dragBadge.setTranslationY(winA[1] - winB[1] - h / 2f);
    }

    /** shows the action chip centered on the given card's top edge. */
    protected void showDragBadge(View anchor, CharSequence text) {
        if (dragBadge == null || dragOverlay == null || anchor == null) return;
        dragOverlay.setVisibility(View.VISIBLE);
        if (dragBadge.getBackground() != badgeSolidBg) dragBadge.setBackground(badgeSolidBg);
        if (!text.equals(dragBadge.getText())) dragBadge.setText(text);
        positionDragBadge(anchor);
        if (dragBadge.getVisibility() != View.VISIBLE) {
            dragBadge.setAlpha(1f);
            dragBadge.setScaleX(0.6f);
            dragBadge.setScaleY(0.6f);
            dragBadge.setVisibility(View.VISIBLE);
            // v1.3.43: the armed chip lands with an overshoot pop — the
            // plain 140ms scale-up was easy to miss mid-drag (field: "the
            // animation for suggesting group or rearrange is not helpful
            // enough").
            dragBadge.animate().scaleX(1f).scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(new OvershootInterpolator(1.6f))
                    .start();
            try { badgePulse.start(); } catch (Throwable ignored) {}
        }
    }

    /** v1.3.43 — the dwell face: a dim pill with an accent FILL that grows
     *  with the hold progress, naming what the hold will do. This is the
     *  "keep holding = group" teacher: the user sees the gesture charging
     *  and can bail out at any moment before it fires. */
    protected void showDragProgress(View anchor, CharSequence text, float f) {
        if (dragBadge == null || dragOverlay == null || anchor == null) return;
        dragOverlay.setVisibility(View.VISIBLE);
        if (dragBadge.getBackground() != badgeFillBg) dragBadge.setBackground(badgeFillBg);
        if (!text.equals(dragBadge.getText())) dragBadge.setText(text);
        setBadgeProgress(f);
        positionDragBadge(anchor);
        if (dragBadge.getVisibility() != View.VISIBLE) {
            dragBadge.setScaleX(1f);
            dragBadge.setScaleY(1f);
            dragBadge.setAlpha(0.95f);
            dragBadge.setVisibility(View.VISIBLE);
        }
    }

    /** levels the progress pill's accent fill (0..1). */
    protected void setBadgeProgress(float f) {
        if (badgeClip != null) badgeClip.setLevel((int) (Math.max(0f, Math.min(1f, f)) * 10000));
    }

    /** hides the action chip and resets its face (progress level 0) so the
     *  next show starts from a clean state. */
    protected void hideDragBadge() {
        if (badgePulse != null) badgePulse.cancel();
        setBadgeProgress(0f);
        if (dragBadge != null && dragBadge.getVisibility() != View.GONE) {
            dragBadge.animate().cancel();
            dragBadge.setScaleX(1f);
            dragBadge.setScaleY(1f);
            dragBadge.setVisibility(View.GONE);
        }
        updateOverlayVisibility();
    }

    /** the overlay layer itself stays GONE unless something is parked in it
     *  (it is not clickable either way, but a gone parent also skips draw). */
    protected void updateOverlayVisibility() {
        if (dragOverlay == null) return;
        boolean used = dragBadge != null
                && dragBadge.getVisibility() == View.VISIBLE;
        if (!used) dragOverlay.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        if (badgePulse != null) badgePulse.cancel();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ drag & merge

    /**
     * v1.3.10 model: "hover-to-merge, drop-to-place".
     *
     * The v1.3.8 design kept ItemTouchHelper's live swapping AND tried to arm a
     * dwell-merge on the card under the finger. Two structural bugs made that
     * impossible on device:
     *  1. onMove() called tm.move() + adapter.notifyItemMoved() but NEVER
     *     updated the adapter's own items list — after the FIRST swap the
     *     RecyclerView positions no longer matched the adapter data, so every
     *     hit-test (tabAt/isGroup/groupAt) resolved the WRONG tab and merges
     *     silently died ("dragging and holding a tab on another is not
     *     creating groups").
     *  2. the swap displaced the target card the moment the finger crossed
     *     the threshold mid-flight, so the dwell never had a target that
     *     stayed put under the finger long enough to arm.
     *
     * Now onMove() always returns false — cards never shuffle mid-drag, so
     * adapter positions stay valid and deterministic for the whole gesture:
     *  - hover over a GROUP row  -> "Hold to add" chip fills for 450ms of
     *                               STILL finger, THEN the join arms (lift +
     *                               accent ring + haptic + "Add to group")
     *  - hover over a tab card   -> "Hold to group" chip fills for 600ms of
     *                               still finger, THEN the merge arms (lift +
     *                               ring + haptic + Create/Join group)
     *  - moving the finger at any time drains the charge — reorder intent
     *  - an armed action survives ONLY while the finger stays on its card
     *  - release without an arm  -> the tab is INSERTED at the position of
     *                               the tab card under the finger (drag
     *                               reorder); a group row is a NO-OP unarmed
     *
     * v1.3.43 (field: "the margin between rearranging and making group is
     * too small and ends up making groups most of the time" + "the
     * animation for suggesting group or rearrange is not helpful enough"):
     * the group gesture became a CHARGED dwell — a progress-fill chip that
     * names the pending action while it charges, a lift-with-overshoot arm
     * (the old dim-to-0.55 read as "deleting"), 8dp grace zones (was 20/26),
     * no stale arms while roaming other cards, and no silent join on an
     * unarmed drop over a folder row.
     *
     * v1.3.41 (field bug 3, "manual tabs rearrange is a mess"): the gesture
     * finally SHOWS itself end to end — (1) a pulsing drop-slot ghost + a
     * make-room nudge on the card that will be displaced preview EVERY plain
     * drop (before/after by the finger's half of the card); (2) the armed
     * merge/group state names its action in a floating chip (Create group /
     * Join group / Add to group) and now ANIMATES in and out; (3) the drop
     * settles through DiffUtil so the arrangement ANIMATES (cards slide,
     * group joins fade into the folder) instead of the hard reload teleport.
     * Plain reorder is manual-sort-only now — sorted modes derive their
     * order, and an invisible insert was half the "mess" feel.
     *
     * v1.3.46 (field: "the animation is ugly, very ugly — when I hold a tab,
     * the immediate right tab just vanish and move off screen. Copy this
     * exact behaviour: I hold a tab, it hovers and pops up a bit, all the
     * other tabs stay in their place; I move the held tab, it can snap at
     * 3 places on the tab below it (left side still on the other tab,
     * center on the other tab, right side still on the other tab); hold in
     * the CENTER of another tab, prompt make group; place to the LEFT side
     * of a tab, the held tab lands left of it and the rest slide one spot;
     * place on the RIGHT side, same slide the other way; all tabs stay
     * onscreen, no offscreen animations"): the live GAP engine is GONE (a
     * full-slot translation applied while the board was statically laid
     * out is exactly what threw the right neighbour off screen / into the
     * next row's void). The new model is the user's spec, literally:
     *  - GRAB: the held card pops (scale 1.06 + elevation) — nothing else
     *    moves, at any point in the drag (no preview, ever);
     *  - SNAP: over another tile the held card magnetically takes one of
     *    three positions (near-left / centered / near-right — list rows:
     *    near-top / centered / near-bottom), eased per draw frame; the
     *    target wears an accent rim on the insertion zones;
     *  - CENTER is the GROUP zone: only a CENTER hold charges the dwell
     *    (the v1.3.43 charged-dwell pill, arm, Create/Join group); sliding
     *    to a side zone disarms/drains; an unarmed CENTER drop is a NO-OP;
     *  - DROP on LEFT: insert immediately BEFORE the target; RIGHT:
     *    immediately AFTER (empty space resolves to the nearest tile, side
     *    by which half the finger sits on). The slide happens AT the drop:
     *    the data moves, the board relayouts, and every displaced tile
     *    GLIDES from its old slot to its new one (~240ms) while the held
     *    card glides from the DROP POINT into its slot — all animations
     *    run between real layout slots, so nothing can leave the screen.
     *
     * v1.3.49 (field: "the tab behaviour did not fix, it still jumps around
     * all the places, I want the held tab to strictly follow my finger and
     * never move anywhere on its own except the slight alignments during
     * snapping, and the held tab must not disappear out of screen — check
     * how other top projects manage tabs"): decompiled AndroidX
     * ItemTouchHelper 1.3.2 to settle the model. THE PROOF: stock
     * getSelectedDxDy does NOT hand onChildDraw raw finger deltas — it
     * returns mSelectedStart + mD - itemView.getCurrent left/top, i.e. the
     * deltas are already VIEWPORT-STABLE: however the grid scrolls, the
     * numbers keep pointing at the same on-screen spot (that is exactly how
     * stock drag stays glued during ITS OWN auto-scroll, and how Chromium's
     * tab grid works — the floating card lives in viewport coordinates, the
     * grid reflows underneath). v1.3.47's dragScrollComp was a SECOND
     * compensation stacked on top of the stock one — a double correction
     * that grew with every scrolled pixel (and, being a vertical quantity,
     * it was added to the X axis too), so during the edge auto-scroll the
     * perceived finger AND the card's free-space target ran away
     * diagonally — "jumps around all the places" and "disappears out of
     * screen", the exact field signature. THE v1.3.49 MODEL:
     *  - RAW: the drawn position is dX/dY + an eased SNAP OFFSET, so in
     *    free space the card sits EXACTLY under the finger (1:1, zero
     *    lag — no elastic chase, "strictly follow my finger");
     *  - SNAP: over another tile (grid still) the offset eases toward the
     *    zone anchor — the ONLY motion not made by the finger is that
     *    eased alignment glide ("except the slight alignments during
     *    snapping");
     *  - SCROLL: while the grid auto-scrolls (RV scroll listener stamps
     *    lastGridScrollAt) the snap is SUPPRESSED and the offset eases to
     *    zero — the card rides the finger through the whole scroll instead
     *    of chasing anchors on cards that are themselves flying past;
     *  - ONSCREEN: the card is anchored at the grab point, which is under
     *    the finger, which is on the screen — it cannot leave (plus the
     *    v1.3.48 calculateExtraLayoutSpace keeps the home slot attached so
     *    the recycler can never cancel the drag mid-flight).
     *
     * v1.3.50 (field: "The tabs behaviour is much better, just needs one more
     * change. When I have placed the held tab somewhere, I want it to sit
     * there directly from my finger, currently it seems the held tab flies
     * back to its old position and appears in the position I chose after a
     * delay... also I want the tab held to follow the finger more closely
     * and strictly") — THE DROP FLY-BACK, settled against the decompiled
     * recyclerview-1.3.2 source (select() + RecoverAnimation):
     *  - on release, stock select(null) runs a RecoverAnimation from the
     *    release deltas to (0,0) — the card VISIBLY flies back to its OLD
     *    slot for getMoveDuration() (~250ms), and ONLY ITS onAnimationEnd
     *    calls clearView — so the settle (and the reorder) started a
     *    quarter-second late, from a card sitting at the OLD slot. That
     *    IS "flies back to its old position and appears in the position I
     *    chose after a delay";
     *  - THE FIX: getAnimationDuration returns 0 for ANIMATION_TYPE_DRAG
     *    (the recover is over within a frame), onChildDraw IGNORES drag
     *    recover passes (the stock flight never touches the translation),
     *    and clearView captures the drop point from the LAST DRAWN smX/smY
     *    (the view's getTranslationX is unusable — anything may have zeroed
     *    it by then) and re-pins the card there right after super — so the
     *    card sits at the finger until OUR settle glide (drop point -> the
     *    chosen slot) starts. No old-slot visit, ever;
     *  - STRICTER FOLLOW: the snap-offset glide locks faster (0.60/frame)
     *    and the offset is CAPPED at a quarter of the card per axis — the
     *    alignment is always SLIGHT (the group-row center anchor used to
     *    yank the card by up to half a screen width).
     */
    private class DragMergeCallback extends ItemTouchHelper.Callback {
        private long pendingGroupJoin = -1;
        private View pendingGroupJoinView = null;
        private View hoverView = null;
        private Tab pendingMergeTarget = null;
        /** v1.4.0 — the dragged GROUP row (null for tab drags). A group
         *  drag is REORDER-ONLY: no dwell charges, no merge arming, no snap
         *  anchors, no zone rims on tab cards — the row rides the finger
         *  and the drop resolves before/after on the group row under it. */
        private JSONObject draggingGroup = null;

        // finger tracking (RecyclerView coordinates, from onChildDraw samples)
        private float fingerX = -1, fingerY = -1;
        private boolean dragActive = false;  // guards clearView() against swipe settles
        private final static int SAMPLES = 12;
        private final long[] sampleT = new long[SAMPLES];
        private final float[] sampleX = new float[SAMPLES], sampleY = new float[SAMPLES];
        private int sampleCount = 0;

        // v1.3.43 (field: "the margin between rearranging and making group
        // is too small — ends up making groups most of the time"): the
        // group gesture is now a CHARGED dwell, not a touch-over accident:
        //  - a TAB card needs 600ms of STATIONARY hover before the merge
        //    arms (was 320ms);
        //  - a GROUP row needs 450ms too (v1.3.41 armed the join the
        //    INSTANT the finger crossed the folder — vertical drags across
        //    a full-width folder row were accidental joins);
        //  - the chip FILLS while the hold charges ("Hold to group"), so
        //    the two intents look different from the first frame;
        //  - an armed action survives only while the finger stays ON its
        //    card (the v1.3.41 code kept a tab-arm alive while the finger
        //    roamed over OTHER cards — releasing on the aim target grouped
        //    with the STALE one); and
        //  - an UNARMED drop on a group row no longer silently joins it.
        private final static int DWELL_TAB_MS = 600;
        private final static int DWELL_GROUP_MS = 450;
        private int dwellProgress = 0;
        private boolean dwellIsGroup = false;

        // v1.3.46 (field: "copy this exact behaviour: I hold a tab, it
        // hovers and pops up a bit, all the other tabs stay in their place;
        // it can snap at 3 places on the tab below it; hold in center,
        // prompt make group; place left/right, the rest slide one spot;
        // all tabs must stay onscreen") — the SNAP-ZONE model. Zones are
        // named by the CARD grid's reading axis (LEFT/RIGHT); in list view
        // they map to the vertical axis (before/after). ZONE_LEFT drops
        // insert the held tab immediately BEFORE the target, ZONE_RIGHT
        // immediately AFTER, ZONE_CENTER is the group-dwell zone (unarmed
        // center drop = no-op). NO tile ever translates during the drag —
        // the only moving parts are the held card (snapped, eased) and the
        // drop-time settle glide.
        private static final int ZONE_NONE = 0, ZONE_LEFT = 1, ZONE_CENTER = 2, ZONE_RIGHT = 3;
        /** the zone the finger is in RIGHT NOW (over the tab card under it). */
        private int zone = ZONE_NONE;
        /** the held card's eased SNAP OFFSET on top of the finger-true raw
         *  position — onChildDraw runs every draw frame while a drag is
         *  live, so a per-frame lerp toward the snap-offset target gives
         *  the magnetic feel without any animator bookkeeping. The raw
         *  part is NOT eased: free-space tracking is 1:1 with the finger
         *  (v1.3.49 — "strictly follow my finger"). smX/smY keep holding
         *  the last drawn translation for clearView's settle capture —
         *  v1.3.50: clearView reads THESE, not getTranslationX/Y, because
         *  the stock recover path zeroes the view translation BEFORE
         *  clearView runs. */
        private float smX = 0f, smY = 0f;
        private float snapOX = 0f, snapOY = 0f;
        /** the held card's pre-drag elevation (restored at settle). */
        private float baseElev = 0f;
        /** the tile currently wearing the insertion-zone accent rim (only
         *  ONE card is ever decorated; identity-memoized so per-frame calls
         *  only mutate on change). */
        private View stripeView = null;

        // v1.3.47 (field: "when I have like 30 tabs open, I want to move the
        // oldest tab to the bottom most place, I want the held tab to scroll
        // when I am holding it near the end of the screen — scroll down when
        // I hold near the bottom, scroll up near the top, a bit fast
        // scrolling") — the EDGE AUTO-SCROLL driver: a self-posting 16ms
        // loop that scrolls the grid while the drag is live. Runs on the
        // shared gesture Handler, lives exactly as long as the drag.
        // v1.3.51 (field: "the area where the scrolling activates is small.
        // Make the scrolling activate whenever the held tab reaches or moves
        // past either the top edge or bottom of viewable screen"): the
        // TRIGGER is the held CARD'S OWN EDGES against the viewable grid's
        // edges (see the loop body); the speed curve is the approved
        // v1.3.47/v1.3.50 one, unchanged.
        private View draggedView = null;
        /** v1.3.49 — uptime of the last grid scroll that happened while this
         *  drag is live (stamped by the activity's RV scroll listener via
         *  onDragScroll). While the grid is moving the magnetic snap is
         *  suppressed so the card rides the finger exactly; the deltas
         *  themselves need NO compensation — stock getSelectedDxDy already
         *  returns viewport-stable values (mSelectedStart + mD - current
         *  left/top; decompiled recyclerview-1.3.2, see the class comment). */
        private long lastGridScrollAt = 0L;
        private boolean autoScrollOn = false;
        private final Runnable dragAutoScroll = new Runnable() {
            @Override public void run() {
                try {
                    if (!dragActive || fingerY < 0 || rv == null || draggedView == null) { autoScrollOn = false; return; }
                    // an ARMED merge/group is a commitment — scrolling the
                    // grid would slide the target out from under the finger
                    // and sabotage the gesture. Hold position instead.
                    if (pendingMergeTarget == null && pendingGroupJoinView == null) {
                    int hgt = rv.getHeight();
                    // v1.3.51 (field: "The scrolling speed is fine... but the
                    // area where the scrolling activates is small. Make the
                    // scrolling activate whenever the held tab reaches or
                    // moves past either the top edge or bottom of viewable
                    // screen"): the TRIGGER is the CARD'S OWN EDGES now, not
                    // an invisible finger band — the auto-scroll arms the
                    // moment the held card's TOP edge reaches the top edge of
                    // the viewable grid (or its BOTTOM edge reaches the
                    // bottom) and keeps going as the card moves PAST. The
                    // SPEED curve is untouched: depth is still measured from
                    // the card's centre exactly as v1.3.50 measured it, so
                    // every card position scrolls at the speed the field
                    // already approved ("the scrolling speed is fine") — the
                    // card now arrives at the edge at WORKING speed instead
                    // of the band's crawl. Drawn bounds: getTop() moves with
                    // the grid, getTranslationY() is the finger-true drawn
                    // offset — their sum is the card's on-screen top
                    // (viewport-stable, lesson 34), stable however far the
                    // grid has already auto-scrolled.
                    float cardTop = draggedView.getTop() + draggedView.getTranslationY();
                    float cardBot = cardTop + draggedView.getHeight();
                    float half = draggedView.getHeight() * 0.5f;
                    // the band keeps its role as the SPEED ramp distance only
                    float band = Math.max(Fx.dp(TabsActivity.this, 80),
                            Math.min(Fx.dp(TabsActivity.this, 150), hgt * 0.20f));
                    float mag = 0f;   // 0..1 — the v1.3.50 depth curve
                    int dir = 0;
                    if (cardTop <= 0f) {
                        dir = -1;
                        mag = Math.min(1f, (half - cardTop) / band);
                    } else if (cardBot >= hgt) {
                        dir = 1;
                        mag = Math.min(1f, (cardBot - hgt + half) / band);
                    }
                    if (dir != 0) {
                        // the v1.3.47 speed curve, unchanged: 4dp/frame at
                        // the rim ramping to 18dp/frame deep past the edge
                        // (at 60fps: 240-1080 dp/s).
                        float dpFrame = 4f + Math.min(1f, mag) * 14f;
                        int px = Math.round(Fx.dp(TabsActivity.this, 1) * dpFrame);
                        if (px > 0) rv.scrollBy(0, dir * px);  // clamps at the ends
                    }
                    }
                } catch (Throwable ignored) {}
                if (dragActive) h.postDelayed(this, 16);
                else autoScrollOn = false;
            }
        };

        private void startAutoScroll() {
            if (!autoScrollOn) {
                autoScrollOn = true;
                h.postDelayed(dragAutoScroll, 16);
            }
        }

        private void stopAutoScroll() {
            autoScrollOn = false;
            h.removeCallbacks(dragAutoScroll);
            draggedView = null;
        }

        /** v1.3.48 — the layout manager's drag-protection hook: the dragged
         *  card's itemView while the gesture is LIVE (null otherwise), so the
         *  glm override can keep its home slot out of the recycler (see the
         *  calculateExtraLayoutSpace comment in onCreate). */
        View draggedItemView() {
            return dragActive ? draggedView : null;
        }

        /** called from the activity's RV scroll listener while a drag is
         *  live (v1.3.49: its ONLY job now is the snap-suppression stamp —
         *  the deltas need no fixing, and the drawn position is rebuilt
         *  from scratch every draw frame anyway; snap targets recompute
         *  from live tile positions and need no help). */
        void onDragScroll(int dy) {
            if (!dragActive || dy == 0) return;
            lastGridScrollAt = SystemClock.uptimeMillis();
        }

        // ONE handler for the whole gesture — Handler.removeCallbacks() only
        // removes messages posted THROUGH THAT HANDLER, so the old pattern of
        // building a fresh Handler per ui() call silently failed to cancel
        // pending dwell checks.
        private final android.os.Handler h =
                new android.os.Handler(android.os.Looper.getMainLooper());

        private final Runnable dwellCheck = new Runnable() {
            @Override
            public void run() {
                if (hoverView == null || pendingMergeTarget != null
                        || pendingGroupJoinView != null) return;
                if (!fingerInside(hoverView)) return; // finger left: onChildDraw cleans up
                // v1.3.46: the CENTER zone is the ONLY group gesture on a
                // tab card — the side zones are pure reorder space and never
                // charge. Sliding out of center mid-charge drains the pill.
                if (!dwellIsGroup && zone != ZONE_CENTER) {
                    dwellProgress = 0;
                    setBadgeProgress(0f);
                    hideDragBadge();
                    h.postDelayed(this, 40);
                    return;
                }
                if (!fingerStationary()) {
                    // finger still moving: the charge drains — moving is the
                    // reorder intent; only a still finger charges the group
                    // gesture. The chip visibly empties, teaching the rule.
                    dwellProgress = 0;
                    setBadgeProgress(0f);
                    h.postDelayed(this, 40);
                    return;
                }
                dwellProgress += 40;
                final int need = dwellIsGroup ? DWELL_GROUP_MS : DWELL_TAB_MS;
                if (dwellProgress < need) {
                    showDragProgress(hoverView, dwellIsGroup
                                    ? getString(R.string.hold_to_add_hint)
                                    : getString(R.string.hold_to_group_hint),
                            dwellProgress / (float) need);
                    h.postDelayed(this, 40);
                    return;
                }
                dwellProgress = 0;
                if (dwellIsGroup) {
                    int pos = rv.getChildAdapterPosition(hoverView);
                    JSONObject g = pos >= 0 ? adapter.groupAt(pos) : null;
                    if (g == null) return;
                    pendingGroupJoinView = hoverView;
                    pendingGroupJoin = g.optLong("id");
                    setMergeHint(hoverView, true);
                    showDragBadge(hoverView, getString(R.string.add_to_group_hint));
                } else {
                    int pos = rv.getChildAdapterPosition(hoverView);
                    Tab t = pos >= 0 ? adapter.tabAt(pos) : null;
                    if (t == null) return;
                    pendingMergeTarget = t;
                    setMergeHint(hoverView, true);
                    // SAY what the drop will do — a pill over the target
                    // naming the armed action.
                    showDragBadge(hoverView, t.groupId == 0
                            ? getString(R.string.create_group_hint)
                            : getString(R.string.join_group_hint));
                }
                // v1.3.46: no ghost, no gap — the only preview decoration the
                // arm must clear is the insertion-zone rim (the merge-hint
                // ring + armed pill own the card from here).
                clearZoneStripe();
                try {
                    hoverView.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS);
                } catch (Throwable ignored) {}
            }
        };

        private void pushSample(long t, float x, float y) {
            // shift ring buffer (small enough that shift is fine)
            if (sampleCount == SAMPLES) {
                System.arraycopy(sampleT, 1, sampleT, 0, SAMPLES - 1);
                System.arraycopy(sampleX, 1, sampleX, 0, SAMPLES - 1);
                System.arraycopy(sampleY, 1, sampleY, 0, SAMPLES - 1);
                sampleCount--;
            }
            sampleT[sampleCount] = t;
            sampleX[sampleCount] = x;
            sampleY[sampleCount] = y;
            sampleCount++;
        }

        /** near-zero finger movement over the last ~250ms of samples — the
         *  finger has stopped (that is the merge-hold gesture). */
        private boolean fingerStationary() {
            if (sampleCount < 2) return true;
            long now = sampleT[sampleCount - 1];
            int idx = -1;
            for (int i = sampleCount - 1; i >= 0; i--) {
                if (now - sampleT[i] > 250) break;
                idx = i;
            }
            if (idx < 0) idx = 0;
            float tx = 0, ty = 0;
            for (int i = idx + 1; i < sampleCount; i++) {
                tx += Math.abs(sampleX[i] - sampleX[i - 1]);
                ty += Math.abs(sampleY[i] - sampleY[i - 1]);
            }
            return (tx + ty) < Fx.dp(TabsActivity.this, 6);
        }

        private boolean fingerInside(View card) {
            if (card == null || fingerX < 0) return false;
            return fingerX >= card.getLeft() && fingerX <= card.getRight()
                    && fingerY >= card.getTop() && fingerY <= card.getBottom();
        }

        private boolean fingerNear(View card, float marginDp) {
            if (card == null || fingerX < 0) return false;
            float m = Fx.dp(TabsActivity.this, (int) marginDp);
            return fingerX >= card.getLeft() - m && fingerX <= card.getRight() + m
                    && fingerY >= card.getTop() - m && fingerY <= card.getBottom() + m;
        }

        // ------------------------------------------------ v1.3.46 snap zones

        /** the zone the finger occupies over the given card. Card view splits
         *  by the grid's reading axis (left / center / right thirds); list
         *  rows split by the vertical axis (top / center / bottom thirds —
         *  "left" maps to BEFORE, "right" to AFTER). The fraction is clamped,
         *  so a finger hovering in the empty space PAST a card still resolves
         *  to that card's nearest side (empty-space drops reuse this). */
        private int zoneOf(View card) {
            float f;
            if (prefs.tabsAsCards()) {
                f = (fingerX - card.getLeft()) / Math.max(1f, card.getWidth());
            } else {
                f = (fingerY - card.getTop()) / Math.max(1f, card.getHeight());
            }
            f = Math.max(0f, Math.min(1f, f));
            return f < 0.34f ? ZONE_LEFT : f < 0.66f ? ZONE_CENTER : ZONE_RIGHT;
        }

        /** the insertion-zone rim: the tile the held card will displace wears
         *  a thin accent rim on the side zones (the held card's snapped
         *  position names WHICH side); the CENTER zone shows no rim — the
         *  dwell pill owns the center feedback. One card is ever decorated;
         *  identity-memoized so the per-frame call only mutates on change. */
        private void updateZoneStripe(View under, int newZone) {
            boolean want = under != null
                    && (newZone == ZONE_LEFT || newZone == ZONE_RIGHT)
                    && pendingMergeTarget == null && pendingGroupJoinView == null
                    && tabQuery == null;   // v1.3.47: manual-only — no sort guard needed
            if (want && stripeView == under) return;      // already wearing it
            if (stripeView != null && stripeView != under) {
                stripeView.setForeground(null);
                stripeView = null;
            }
            if (!want) {
                if (stripeView != null) { stripeView.setForeground(null); stripeView = null; }
                return;
            }
            try {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(Fx.dp(TabsActivity.this, 14));
                g.setStroke(Math.max(2, Fx.dp(TabsActivity.this, 2)),
                        Fx.accent(TabsActivity.this));
                under.setForeground(g);
                stripeView = under;
            } catch (Throwable ignored) {}
        }

        /** drop every zone decoration (drop / disarm / gesture end). */
        private void clearZoneStripe() {
            if (stripeView != null) {
                try { stripeView.setForeground(null); } catch (Throwable ignored) {}
                stripeView = null;
            }
        }

        /** v1.4.0 — the group-drag drop rim: while a GROUP is dragged, any
         *  OTHER group row under the finger wears the accent rim (both
         *  halves are valid drop sides — before by one half, after by the
         *  other; see the drop in clearView). Tab cards never light up in
         *  the ON-TOP placement: a group drag cannot merge, join or reorder
         *  TABS there. v1.4.1 — in the GROUPS-IN-ORDER placement a LOOSE tab
         *  card is ALSO a valid block-move anchor (the folder's members land
         *  before/after it), so it wears the same rim there. Reuses the
         *  stripeView bookkeeping so only one row is ever decorated. */
        private void updateGroupDropStripe(View under) {
            View want = null;
            if (under != null) {
                int p = rv == null ? -1 : rv.getChildAdapterPosition(under);
                JSONObject g = p >= 0 ? adapter.groupAt(p) : null;
                if (g != null && draggingGroup != null
                        && g.optLong("id") != draggingGroup.optLong("id")) {
                    want = under;
                } else if (g == null && p >= 0 && draggingGroup != null
                        && !prefs.groupsOnTop()) {
                    // v1.4.1 — the in-order placement: loose tab cards are
                    // drop anchors too (the group's members land beside them)
                    Tab t = adapter.tabAt(p);
                    if (t != null && t.groupId != draggingGroup.optLong("id")) want = under;
                }
            }
            if (want == stripeView) return;
            if (stripeView != null) {
                try { stripeView.setForeground(null); } catch (Throwable ignored) {}
                stripeView = null;
            }
            if (want != null) {
                try {
                    GradientDrawable g2 = new GradientDrawable();
                    g2.setCornerRadius(Fx.dp(TabsActivity.this, 14));
                    g2.setStroke(Math.max(2, Fx.dp(TabsActivity.this, 2)),
                            Fx.accent(TabsActivity.this));
                    want.setForeground(g2);
                    stripeView = want;
                } catch (Throwable ignored) {}
            }
        }

        /** nearest non-dragged, non-group card to the finger — for a finger
         *  floating over empty grid space (past the end of the list). */
        private View nearestTabCard(RecyclerView r, View exclude) {
            View best = null;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < r.getChildCount(); i++) {
                View c = r.getChildAt(i);
                if (c == exclude) continue;
                if (adapter.isGroup(r.getChildAdapterPosition(c))) continue;
                double dx = (c.getLeft() + c.getWidth() / 2f) - fingerX;
                double dy = (c.getTop() + c.getHeight() / 2f) - fingerY;
                double d = dx * dx + dy * dy;
                if (d < bestD) { bestD = d; best = c; }
            }
            return best;
        }

        /** v1.4.5 — nearest non-dragged row of ANY kind (loose tab OR folder
         *  row) to the finger. In the follow-order placement folders
         *  interleave the grid, so a drop into empty space (past the list's
         *  end, the gap under the last row) must be able to anchor against
         *  a FOLDER row too — the old fallback (nearestTabCard) skipped
         *  them, so a drop near the bottom of an all-folders region silently
         *  glided home: "tabs are not still manually adjustable". */
        private View nearestRow(RecyclerView r, View exclude) {
            View best = null;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < r.getChildCount(); i++) {
                View c = r.getChildAt(i);
                if (c == exclude) continue;
                double dx = (c.getLeft() + c.getWidth() / 2f) - fingerX;
                double dy = (c.getTop() + c.getHeight() / 2f) - fingerY;
                double d = dx * dx + dy * dy;
                if (d < bestD) { bestD = d; best = c; }
            }
            return best;
        }

        // (v1.3.45 applyGap/clearGap/updateSlotPreview: REMOVED in v1.3.46 — see the snap-zone model above)

        private void setMergeHint(View v, boolean on) {
            try {
                // v1.3.43 (field: "the animation for suggesting group or
                // rearrange is not helpful enough"): the armed target now
                // LIFTS — scale up with an overshoot + elevation + accent
                // ring — instead of dimming to 0.55 (a dim read as
                // "deleting" and was easy to miss mid-drag). Disarm settles
                // the card flat again.
                if (on) {
                    v.animate().scaleX(1.04f).scaleY(1.04f).alpha(1f)
                            .setDuration(180)
                            .setInterpolator(new OvershootInterpolator(1.15f))
                            .start();
                    v.setElevation(Fx.dp(TabsActivity.this, 8));
                    GradientDrawable g = new GradientDrawable();
                    g.setCornerRadius(Fx.dp(TabsActivity.this, 14));
                    g.setStroke(Math.max(3, Fx.dp(TabsActivity.this, 2)),
                            Fx.accent(TabsActivity.this));
                    v.setForeground(g);
                } else {
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(120).start();
                    v.setElevation(0f);
                    v.setForeground(null);
                }
            } catch (Throwable ignored) {}
        }

        private void resetMergeState() {
            h.removeCallbacks(dwellCheck);
            pendingMergeTarget = null;
            pendingGroupJoin = -1;
            if (pendingGroupJoinView != null) {
                setMergeHint(pendingGroupJoinView, false);
                pendingGroupJoinView = null;
            }
            if (hoverView != null) {
                setMergeHint(hoverView, false);
                hoverView = null;
            }
            // v1.3.41 (field bug 3): the whole decoration set belongs to the
            // gesture — a merge/group state change also clears every other
            // hint, so only ONE is ever on screen.
            hideDragBadge();
            // v1.3.46: the insertion rim lives with the zone logic; an armed
            // state ending means the rim's suppress condition is gone, so the
            // next onChildDraw re-evaluates it — but a disarm between frames
            // must not leave a stale rim over the merge ring.
            clearZoneStripe();
            dwellProgress = 0;
        }

        /** top-level row (never the dragged one) under the given finger point */
        private View rowUnder(RecyclerView r, View exclude, float cx, float cy) {
            for (int i = 0; i < r.getChildCount(); i++) {
                View ch = r.getChildAt(i);
                if (ch == exclude) continue;
                if (cx >= ch.getLeft() && cx <= ch.getRight()
                        && cy >= ch.getTop() && cy <= ch.getBottom()) return ch;
            }
            return null;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder vh, int actionState) {
            // v1.3.46: capture the resting elevation BEFORE super (the default
            // implementation may add its own drag lift — restoring a polluted
            // value would leave the card permanently raised)
            float preElev = 0f;
            try {
                if (vh != null && vh.itemView != null) preElev = vh.itemView.getElevation();
            } catch (Throwable ignored) {}
            super.onSelectedChanged(vh, actionState);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                // a NEW drag begins: clear state from the previous gesture.
                // NOTE: do NOT reset when a drag ENDS (vh == null / IDLE) —
                // onSelectedChanged(IDLE) fires BEFORE clearView(), which needs
                // the armed merge / group-join to execute the drop. Resetting
                // here was one more reason drag-to-group never fired on device.
                resetMergeState();
                clearZoneStripe();
                zone = ZONE_NONE;
                smX = smY = 0f;
                sampleCount = 0;
                fingerX = fingerY = -1;
                dragActive = true;
                // v1.4.0 — a GROUP drag remembers its group (reorder-only;
                // the whole tab-merge machinery is skipped while it is set)
                int gp = vh.getBindingAdapterPosition();
                draggingGroup = gp >= 0 ? adapter.groupAt(gp) : null;
                stopAutoScroll();          // fresh gesture, fresh bookkeeping
                draggedView = vh.itemView;
                lastGridScrollAt = 0L;
                snapOX = snapOY = 0f;
                startAutoScroll();         // v1.3.47: edge auto-scroll runs for the whole drag
                // v1.3.46 "it hovers and pops up a bit": the held card lifts
                // (scale 1.06 + elevation) and NOTHING else moves — the other
                // tiles keep their exact slots until the drop.
                try {
                    View v = vh.itemView;
                    baseElev = preElev;
                    v.setElevation(Math.max(baseElev, Fx.dp(TabsActivity.this, 8)));
                    v.animate().cancel();
                    v.animate().scaleX(1.06f).scaleY(1.06f)
                            .setDuration(200)
                            .setInterpolator(new OvershootInterpolator(1.2f))
                            .start();
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public boolean canDropOver(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder drag,
                @NonNull RecyclerView.ViewHolder target) {
            // allow card-over-group-row so the finger-in-bounds logic can arm joins
            return true;
        }

        @Override
        public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView r,
                @NonNull RecyclerView.ViewHolder vh, float dX, float dY,
                int actionState, boolean isCurrentlyActive) {
            // v1.3.35 — the modern swipe-to-close feel: the card SHRINKS and
            // fades as it slides (Chrome-style) instead of a rigid slide.
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && vh.itemView != null) {
                super.onChildDraw(c, r, vh, dX, dY, actionState, isCurrentlyActive);
                try {
                    float f = Math.min(1f, Math.abs(dX)
                            / Math.max(1f, vh.itemView.getWidth()));
                    vh.itemView.setAlpha(1f - 0.6f * f);
                    vh.itemView.setScaleX(1f - 0.3f * f);
                    vh.itemView.setScaleY(1f - 0.3f * f);
                    vh.itemView.setRotation(f * 3f * (dX < 0 ? -1f : 1f));
                } catch (Throwable ignored) {}
                return;
            }
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || !isCurrentlyActive) {
                // v1.3.50 — a DRAG recover pass is the (now 0-duration, see
                // getAnimationDuration) stock flight of the card back to its
                // home slot. We OWN every pixel of the drop (clearView's
                // settle): do NOT let it touch the translation — the card
                // stays exactly where the finger left it until our glide
                // starts. Swipe recovers keep the stock glide-back.
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) return;
                super.onChildDraw(c, r, vh, dX, dY, actionState, isCurrentlyActive);
                return;
            }
            View item = vh.itemView;
            // v1.3.49 — dX/dY are ALREADY viewport-stable (decompiled stock
            // getSelectedDxDy: mSelectedStart + mD - current left/top), so
            // these are the card's TRUE on-screen center under the finger —
            // constant however far the grid auto-scrolls under a still
            // finger. The v1.3.47 dragScrollComp that used to sit here was
            // a second compensation on top of the stock one (and polluted
            // the X axis with a vertical quantity) — the "jumps around all
            // the places / disappears out of screen" field report.
            float cx = item.getLeft() + item.getWidth() / 2f + dX;
            float cy = item.getTop() + item.getHeight() / 2f + dY;
            pushSample(SystemClock.uptimeMillis(), cx, cy);
            fingerX = cx;
            fingerY = cy;

            // v1.4.0 — THE GROUP DRAG: reorder-only, no merge machinery.
            // The row rides the finger 1:1 (raw dX/dY — the snap anchors
            // and the capped alignment are tab-merge UX, meaningless for a
            // folder), every group row it passes wears the accent rim (a
            // drop on it reorders the dragged group to that side), and the
            // dwell/zone/merge paths below are unreachable. smX/smY keep
            // the raw values so clearView's settle capture stays true.
            if (draggingGroup != null) {
                View gunder = rowUnder(r, item, cx, cy);
                updateGroupDropStripe(gunder);
                smX = dX;
                smY = dY;
                super.onChildDraw(c, r, vh, dX, dY, actionState, isCurrentlyActive);
                return;
            }

            View under = rowUnder(r, item, cx, cy);
            // v1.3.49 — GRID-MOVING flag: true for ~120ms after any grid
            // scroll during this drag (stamped by onDragScroll). While the
            // grid flies, the zone model is suspended entirely — no snap
            // anchors, no accent rims, no dwell charges: cards are moving
            // targets, and chasing them was the "jumps around" hop. The
            // card just rides the finger (see the snap block below).
            boolean gridMoving = lastGridScrollAt > 0L
                    && SystemClock.uptimeMillis() - lastGridScrollAt < 120L;
            // v1.3.46 — the SNAP ZONE over the card under the finger. onChildDraw
            // fires on EVERY draw frame while the drag is live (ItemTouchHelper
            // is an ItemDecoration), so the eased position below animates on its
            // own; no animator bookkeeping, nothing to cancel.
            boolean overGroup = under != null
                    && adapter.isGroup(r.getChildAdapterPosition(under));
            int newZone = ZONE_NONE;
            // v1.4.4 — a FOLDER ROW carries zones too when a LOOSE tab is
            // dragged in the FOLLOW-TAB-ORDER placement: its sides are
            // reorder space (drop lands beside the folder's block), its
            // center keeps the add-to-group dwell. On-top (and every folder
            // drag) keeps the old contract: folder rows are center-only.
            if (under != null && !gridMoving
                    && (!overGroup || (!prefs.groupsOnTop() && draggingGroup == null)))
                newZone = zoneOf(under);
            if (newZone != zone) {
                zone = newZone;
                // sliding onto a SIDE zone disarms a charged/arm state — the
                // sides are reorder space. A finger merely leaving every card
                // (under == null) keeps the v1.3.43 8dp grace: the arm survives
                // while the finger stays NEAR its card (see below).
                if (under != null && zone != ZONE_CENTER && pendingMergeTarget != null)
                    resetMergeState();
                dwellProgress = 0;
            }
            if (under == null) {
                // finger left every row: cancel a pending dwell; keep an ARMED
                // merge while the finger stays NEAR the target card (v1.3.43:
                // 20/26dp -> 8dp — the wide grace zones made "drag away to
                // bail out" nearly impossible inside a dense grid)
                if (pendingGroupJoinView != null && !fingerNear(pendingGroupJoinView, 8)) {
                    resetMergeState();
                } else if (pendingMergeTarget != null && hoverView != null
                        && !fingerNear(hoverView, 8)) {
                    resetMergeState();
                } else if (pendingMergeTarget == null && pendingGroupJoinView == null
                        && hoverView != null) {
                    resetMergeState();
                }
            } else if (overGroup) {
                // v1.3.43: group rows DWELL now — the v1.3.41 instant arm
                // made "drag past a folder" fire Add-to-group on touch-over
                // (the "ends up making groups most of the time" report).
                // Crossing a folder is harmless; only a deliberate hold
                // ("Hold to add" chip filling, 450ms) arms the join.
                // v1.4.4 — but only on the folder's CENTER: its side zones
                // are reorder space now (the in-order placement), so a
                // finger on a side disarms any charged join and never
                // starts one (the accent rim owns the side feedback).
                if (newZone == ZONE_LEFT || newZone == ZONE_RIGHT) {
                    if (pendingGroupJoinView != null || hoverView != null) resetMergeState();
                } else if (pendingGroupJoinView != under && hoverView != under) {
                    resetMergeState();
                    hoverView = under;
                    dwellIsGroup = true;
                    dwellProgress = 0;
                    h.postDelayed(dwellCheck, 40);
                }
            } else if (under != hoverView || pendingGroupJoinView != null) {
                // finger entered a different tab card — OR left an armed
                // group row. v1.3.43: discard ANY armed state first: an
                // armed action now survives only while the finger stays ON
                // its own card. The v1.3.41 code kept a tab-card arm alive
                // while the finger roamed over OTHER cards, so releasing on
                // the aim target grouped with the STALE one — the single
                // biggest "accidental group" source.
                resetMergeState();
                hoverView = under;
                dwellIsGroup = false;
                dwellProgress = 0;
                h.postDelayed(dwellCheck, 40);
            }
            updateZoneStripe(under, newZone);
            maintainDragDecorations(r, item, under);

            // ---- v1.3.49 the finger-true model (was: v1.3.46 magnetic snap) ----
            // RAW (dX, dY) is the finger, exactly (viewport-stable — see
            // cx/cy above). Over another tile — and ONLY while the grid is
            // still — the held card takes ONE OF THREE positions on that
            // tile (near-left / centered / near-right; list rows: near-top /
            // centered / near-bottom), expressed as an eased OFFSET on top
            // of the raw position: free space = zero offset = the card sits
            // 1:1 under the finger, entering a zone eases the offset in,
            // leaving (or the grid starting to scroll) eases it back out.
            // So the ONLY motion the card ever makes on its own is the
            // eased alignment glide — "the slight alignments during
            // snapping". While the grid auto-scrolls the snap is suppressed
            // entirely: anchors sit on cards that are themselves flying
            // past, and chasing them was the "jumps around" hop.
            float tOX = 0f, tOY = 0f;
            if (under != null && !gridMoving) {
                float wx, wy;
                boolean cards = prefs.tabsAsCards();
                // v1.4.4 — a folder row's SIDE zones (loose drag, in-order)
                // anchor like a tab card's sides — the drop lands beside the
                // folder's block, so the card eases to that side too
                if ((overGroup && newZone != ZONE_LEFT && newZone != ZONE_RIGHT)
                        || newZone == ZONE_CENTER) {
                    // a GROUP target has no sides — the held card centers on it
                    wx = (under.getLeft() + under.getRight()) / 2f;
                    wy = (under.getTop() + under.getBottom()) / 2f;
                } else if (cards) {
                    float off = under.getWidth() * 0.30f;   // still ON the other tab
                    wx = newZone == ZONE_LEFT
                            ? under.getLeft() + off : under.getRight() - off;
                    wy = (under.getTop() + under.getBottom()) / 2f;
                } else {
                    float off = under.getHeight() * 0.30f;
                    wx = (under.getLeft() + under.getRight()) / 2f;
                    wy = newZone == ZONE_LEFT
                            ? under.getTop() + off : under.getBottom() - off;
                }
                tOX = wx - (item.getLeft() + item.getWidth() / 2f) - dX;
                tOY = wy - (item.getTop() + item.getHeight() / 2f) - dY;
            }
            // v1.3.50 ("follow the finger more closely and strictly"):
            // CAP the alignment at a QUARTER of the card per axis — the
            // offset can never grow beyond "slight" no matter how far the
            // anchor sits (the group-row center used to pull the card by
            // up to half a screen width). The lerp between two in-cap
            // values stays in-cap, so the card lives inside a ±0.25-card
            // envelope around the finger whenever a zone is engaged — and
            // exactly ON it in free space.
            float capX = item.getWidth() * 0.25f, capY = item.getHeight() * 0.25f;
            if (tOX > capX) tOX = capX; else if (tOX < -capX) tOX = -capX;
            if (tOY > capY) tOY = capY; else if (tOY < -capY) tOY = -capY;
            // 0.60/frame (was 0.45): the glide onto the anchor locks faster
            // — less time spent off the finger while still a visible ease.
            snapOX += (tOX - snapOX) * 0.60f;
            snapOY += (tOY - snapOY) * 0.60f;
            smX = dX + snapOX;
            smY = dY + snapOY;
            // draw the card at raw + eased snap offset (the super call
            // applies the translation; everything else it does is idempotent)
            super.onChildDraw(c, r, vh, smX, smY, actionState, isCurrentlyActive);
        }

        /** per-frame decoration maintenance at the END of every drag draw:
         *  keeps the armed action chip glued to its card (auto-scroll during
         *  a long drag must not strand it). v1.3.46: nothing else — the zone
         *  rim is updateZoneStripe's job and there is NO gap preview. */
        private void maintainDragDecorations(RecyclerView r, View item, View under) {
            if (pendingGroupJoinView != null) {
                showDragBadge(pendingGroupJoinView, getString(R.string.add_to_group_hint));
                return;
            }
            if (pendingMergeTarget != null && hoverView != null) {
                showDragBadge(hoverView, pendingMergeTarget.groupId == 0
                        ? getString(R.string.create_group_hint)
                        : getString(R.string.join_group_hint));
            }
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            if (selMode) return 0; // no drag while multi-selecting
            // v1.4.4 — an armed IN-GROUP mini drag owns the gesture: the
            // ITH's own 500 ms long-press on the same row (the mini lives
            // INSIDE the group row) must not also lift the folder — the
            // mini's hold fired at ~380 ms, so by the time the ITH asks,
            // the flag well is dry.
            if (miniDragTab != null) return 0;
            if (adapter.isGroup(vh.getBindingAdapterPosition())) {
                // v1.4.0 — GROUP ROWS ARE DRAGGABLE (the report: "tab groups
                // are not draggable"). Drag flags only, NO swipe: a folder
                // is never swiped away. The drop path (clearView) resolves
                // before/after on the row under the finger and block-moves
                // the group's MEMBERS via tm.moveGroupRelative (v1.4.3: one
                // drag law, both placements) — a group drag can only
                // REORDER, never merge into one ("only draggable to
                // rearrange order, nothing else" — the home-folder law,
                // applied to the switcher's folders too).
                return ItemTouchHelper.Callback.makeMovementFlags(
                        ItemTouchHelper.UP | ItemTouchHelper.DOWN
                                | ItemTouchHelper.START | ItemTouchHelper.END,
                        0);
            }
            return ItemTouchHelper.Callback.makeMovementFlags(
                    ItemTouchHelper.UP | ItemTouchHelper.DOWN
                            | ItemTouchHelper.START | ItemTouchHelper.END,
                    ItemTouchHelper.START | ItemTouchHelper.END);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder drag,
                @NonNull RecyclerView.ViewHolder target) {
            // NO live swapping (see the class comment) — that is what broke
            // the adapter<->RecyclerView position contract and displaced the
            // merge targets. Reordering happens at drop time in clearView().
            return false;
        }

        @Override
        public long getAnimationDuration(@NonNull RecyclerView recyclerView, int animationType,
                float animateDx, float animateDy) {
            // v1.3.50 — THE DROP FLY-BACK KILL. Stock drag end (decompiled
            // recyclerview-1.3.2 select()): a RecoverAnimation runs from the
            // release deltas to (0,0) — the card VISIBLY flies home to its
            // OLD slot for getMoveDuration() (~250ms) and only its
            // onAnimationEnd calls clearView — the v1.3.50 field report
            // ("the held tab flies back to its old position and appears in
            // the position I chose after a delay"). Zero the DRAG recover so
            // clearView fires within a frame of the release; the settle
            // animations (settleDropSlide / settleFromDrop) own every pixel
            // of the motion from there. Swipe durations stay stock.
            if (animationType == ItemTouchHelper.ANIMATION_TYPE_DRAG) return 0L;
            return super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy);
        }

        @Override
        public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            // v1.3.46: capture the card's VISUAL position BEFORE super resets
            // its translation — the settle animations start from the drop
            // point, never from the home slot (no teleports).
            // v1.3.50: capture from the LAST DRAWN smX/smY, NOT from
            // getTranslationX/Y — the stock recover path may have zeroed the
            // view's translation before clearView runs (and with the
            // 0-duration recover our frozen pass keeps it at smX anyway —
            // either way the DRAWN position is the truth the user saw under
            // the finger).
            View itemView = vh.itemView;
            float dropL = itemView.getLeft() + smX;
            float dropT = itemView.getTop() + smY;
            boolean wasDrag = dragActive;
            super.clearView(rv, vh);
            // v1.3.50 — re-pin the card at the DROP POINT the instant super
            // zeroed the translation: even if the settle defers a frame
            // (deferIfBusy) the card must never draw at the home slot. It
            // sits "there directly from my finger" until the glide starts.
            if (wasDrag) {
                try {
                    itemView.setTranslationX(dropL - itemView.getLeft());
                    itemView.setTranslationY(dropT - itemView.getTop());
                } catch (Throwable ignored) {}
            }
            // v1.3.35 + v1.3.46: undo the swipe shrink (animated, not a snap)
            try {
                vh.itemView.setAlpha(1f);
                vh.itemView.setRotation(0f);
                vh.itemView.animate().scaleX(1f).scaleY(1f)
                        .setDuration(160).start();
                vh.itemView.setElevation(baseElev);
            } catch (Throwable ignored) {}
            zone = ZONE_NONE;
            setMergeHint(vh.itemView, false);
            clearZoneStripe();
            if (!wasDrag) { // clearView also fires after SWIPE settles — no drag state then
                resetMergeState();
                fingerX = fingerY = -1;
                return;
            }
            dragActive = false;
            stopAutoScroll();          // v1.3.47: the edge auto-scroll loop dies with the gesture
            // v1.4.16 — the drop resolution runs inside try/finally so a
            // mid-drag parked reload ALWAYS flushes when the gesture ends,
            // whichever exit the drop takes (settle, merge, join, no-op).
            try {
            // resolve the dragged tab BEFORE any reload changes positions —
            // positions are guaranteed valid because onMove never swaps
            int pos = vh.getBindingAdapterPosition();
            Tab dragged = pos >= 0 ? adapter.tabAt(pos) : null;

            // v1.4.0 — THE GROUP DROP (reorder-only). The dragged row was a
            // GROUP: resolve the group row under the finger; the finger's
            // half of that row decides BEFORE/AFTER (list rows split
            // vertically, cards horizontally — the zoneOf axis); the target
            // wears a rim during the drag (updateGroupDropStripe). A TAB card
            // anchors in the follow-order placement (loose tabs are block-
            // move anchors there); empty space resolves to the NEAREST row
            // in that placement (v1.4.5 — folders interleave the grid, a
            // drop past the last row must still land). On-top keeps its
            // contract: only another folder anchors.
            final JSONObject gdrag = draggingGroup;
            draggingGroup = null;
            if (gdrag != null) {
                resetMergeState();
                long gid = gdrag.optLong("id");
                View gunder = null;
                if (fingerX >= 0) gunder = rowUnder(rv, vh.itemView, fingerX, fingerY);
                // v1.4.5 — the empty-space fallback (follow-order only):
                // the finger past the last row anchors against the nearest
                // row instead of a silent glide home
                if (gunder == null && fingerX >= 0 && !prefs.groupsOnTop()) {
                    gunder = nearestRow(rv, vh.itemView);
                }
                JSONObject gtarget = null;
                if (gunder != null) {
                    int gp = rv.getChildAdapterPosition(gunder);
                    gtarget = gp >= 0 ? adapter.groupAt(gp) : null;
                    if (gtarget != null && gtarget.optLong("id") == gid) gtarget = null;
                }
                // the finger's half of the row under it: BEFORE or AFTER
                // (cards split horizontally, list rows vertically)
                float f = gunder == null ? 0.5f : (prefs.tabsAsCards()
                        ? (fingerX - gunder.getLeft()) / Math.max(1f, gunder.getWidth())
                        : (fingerY - gunder.getTop()) / Math.max(1f, gunder.getHeight()));
                boolean gafter = f >= 0.5f;
                boolean gdone = false;
                // v1.4.3 — ONE DRAG LAW: BOTH placements block-move the
                // group's MEMBER TABS in tm.tabs (moveGroupRelative) — the
                // on-top render now derives folder order from first-member
                // positions (see rebuildItems), so an array-only reorder
                // would be visually inert there too, exactly as it was in
                // the in-order placement before v1.4.1. The anchor: a
                // FOLDER row clamps to that folder's outer member (both
                // placements — the never-between-members law); a LOOSE tab
                // card anchors directly (in-order only: loose cards never
                // light up as drop targets while folders are pinned).
                // v1.4.7 — THE EMPTY-FOLDER DROP: dragging an EMPTY folder
                // was a silent no-op (moveGroupRelative needs members) —
                // "fix so that only the one I hold moves" includes moving
                // THE ONE I HOLD. v1.4.8 — THE ANCHOR WRITE: the follow
                // placement writes the PAIR OF ROWS the drop landed
                // between (any row is a valid anchor — folder or loose
                // tab; past the end = the bottom edge), NOT a row index —
                // the v1.4.7 index was read from a render that still
                // contained the dragged empty at its OLD row (no live
                // shuffle), so the settle landed one position off whenever
                // the empty sat above the target, and every later
                // insert/close above it shifted it again. The on-top
                // placement still reorders the groups ARRAY beside the
                // target folder row (moveEmptyGroupBeside); the follow
                // anchors re-pin at the next materialize. On-top drops
                // anchor ONLY on folders.
                boolean gEmpty = tm.tabsOfGroup(gid).isEmpty();
                if (gEmpty) {
                    if (gtarget != null) {
                        if (prefs.groupsOnTop()) {
                            gdone = tm.moveEmptyGroupBeside(gid, gtarget.optLong("id"), gafter);
                        } else {
                            int gp2 = rv.getChildAdapterPosition(gunder);
                            if (gp2 >= 0) {
                                dropAnchorEmptyGroup(gid, gp2, gafter,
                                        vh.getBindingAdapterPosition());
                                gdone = true;
                            }
                        }
                    } else if (gunder != null && !prefs.groupsOnTop()) {
                        // a loose tab row (or nearest-row fallback) anchors
                        // the empty folder exactly beside it
                        int gp2 = rv.getChildAdapterPosition(gunder);
                        if (gp2 >= 0 && adapter.tabAt(gp2) != null) {
                            dropAnchorEmptyGroup(gid, gp2, gafter,
                                    vh.getBindingAdapterPosition());
                            gdone = true;
                        }
                    } else if (gunder == null && fingerX >= 0 && !prefs.groupsOnTop()) {
                        // nothing under the finger at all (the dragged row
                        // was the only one): the empty keeps the top edge
                        prefs.setGroupAnchor(gid, "", "");
                        gdone = true;
                    }
                } else {
                    Tab ganchor = null;
                    JSONObject emptyTarget = null;   // v1.4.8: a drop beside an EMPTY folder's row
                    if (gtarget != null) {
                        List<Tab> host = tm.tabsOfGroup(gtarget.optLong("id"));
                        if (!host.isEmpty())
                            ganchor = gafter ? host.get(host.size() - 1) : host.get(0);
                        else emptyTarget = gtarget;   // memberless: no member to anchor on
                    } else if (gunder != null && !prefs.groupsOnTop()) {
                        int gp = rv.getChildAdapterPosition(gunder);
                        Tab t = gp >= 0 ? adapter.tabAt(gp) : null;
                        if (t != null && t.groupId != gid) ganchor = t;
                    }
                    if (emptyTarget != null && !prefs.groupsOnTop()) {
                        // v1.4.8 — THE OTHER "does not let other tabs sit
                        // right next to it" HALF: dropping a MEMBERED
                        // folder beside an EMPTY folder's row was a silent
                        // no-op. The block lands beside the empty by
                        // anchoring through the EMPTY's own anchor pair.
                        // v1.4.9 — THE RENDER-NEIGHBOUR LAW (the (2,2)
                        // teleport corpse + the inverted write): the
                        // landing now resolves through the EMPTY's VISIBLE
                        // NEIGHBOURS in the follow render, never through
                        // the stored "an"/"ap" — a stored key can be stale
                        // (a bare "" from when the empty WAS the last row,
                        // a dangling key) while the empty renders mid-list
                        // through its other side, and the v1.4.8
                        // stale-key null-fallback was the LIST EDGE, so a
                        // group dropped right of the "2,2th" empty
                        // teleported to the very bottom (dragging the
                        // empty down with it). The re-anchor write also
                        // flips to the CORRECT side: gafter lands the group
                        // as the empty's row-BELOW ("an"), not its
                        // row-above — the v1.4.8 write rendered the group
                        // on the OPPOSITE side of the empty from the finger
                        // (the "does not follow the rule" one-position-off).
                        long egid = emptyTarget.optLong("id");
                        String ean = emptyTarget.optString("an", "");
                        String eap = emptyTarget.optString("ap", "");
                        boolean gdone2 = false;
                        // the dragged group's own row departs: empties
                        // anchored to it keep their spots (pre-change order)
                        try { tm.refreshAnchorsAround("g" + gid); } catch (Throwable ignored) {}
                        if (gafter) {
                            String aboveK = tm.renderNeighbourKeyOfEmpty(egid, false, "g" + gid);
                            String belowK = tm.renderNeighbourKeyOfEmpty(egid, true, "g" + gid);
                            Tab a = belowK == null ? anchorKeyTab(ean, false)
                                    : chainBoundaryTab(belowK, "g" + gid, true, false);
                            if (a != null) {
                                gdone2 = tm.moveGroupRelative(gid, a, false);
                            } else {
                                a = lastRowAnchorTab();   // the render's last row (or the empty IS it)
                                gdone2 = a != null && tm.moveGroupRelative(gid, a, true);
                            }
                            if (gdone2) prefs.setGroupAnchor(egid,
                                    aboveK == null ? eap : aboveK, "g" + gid);
                        } else {
                            String aboveK = tm.renderNeighbourKeyOfEmpty(egid, false, "g" + gid);
                            String belowK = tm.renderNeighbourKeyOfEmpty(egid, true, "g" + gid);
                            Tab a = aboveK == null ? anchorKeyTab(eap, true)
                                    : chainBoundaryTab(aboveK, "g" + gid, false, true);
                            if (a != null) {
                                gdone2 = tm.moveGroupRelative(gid, a, true);
                            } else {
                                a = firstRowAnchorTab();  // the render's first row (or the empty IS it)
                                gdone2 = a != null && tm.moveGroupRelative(gid, a, false);
                            }
                            if (gdone2) prefs.setGroupAnchor(egid, "g" + gid,
                                    belowK == null ? ean : belowK);
                        }
                        gdone = gdone2;
                    } else if (emptyTarget == null) {
                        // v1.4.9 — the refresh must read the PRE-change row
                        // order (the v1.4.8 call ran AFTER the move, so an
                        // empty anchored to the dragged folder re-anchored
                        // to the folder's NEW neighbours and FOLLOWED the
                        // drag — "only the one I hold moves" broken for
                        // exactly the empties pinned to the dragged row)
                        try { tm.refreshAnchorsAround("g" + gid); } catch (Throwable ignored) {}
                        gdone = tm.moveGroupRelative(gid, ganchor, gafter);
                    }
                    if (gdone && emptyTarget == null) {
                        // v1.4.8 — the folder ROW moved: an empty sitting at
                        // the landing gap re-anchors to face the landed row
                        if (gunder != null) {
                            int gp3 = rv.getChildAdapterPosition(gunder);
                            if (gp3 >= 0) retargetBoundaryEmpty(gp3, gafter, "g" + gid,
                                    vh.getBindingAdapterPosition());
                        }
                    }
                }
                if (gdone) {
                    // the same animation the tab reorder rides: every tile
                    // glides between real layout slots, the held folder row
                    // from the DROP POINT (v1.3.47 law: defer when the RV
                    // is mid-layout/mid-glide)
                    if (deferIfBusy(() -> settleDropSlide(vh, gdrag, dropL, dropT))) return;
                    settleDropSlide(vh, gdrag, dropL, dropT);
                } else {
                    settleFromDrop(vh, dropL, dropT);
                }
                return;
            }

            // capture the drop intents BEFORE resetting
            long joinGid = pendingGroupJoin;
            Tab mergeTarget = pendingMergeTarget;

            // 1) dropped onto a group row (armed, or released straight over
            //    it before the arm) -> join that group. v1.3.41 (field bug 3):
            //    reloadDiff animates the card AWAY into the group (remove)
            //    and crossfades the folder's count (change), instead of the
            //    hard notifyDataSetChanged teleport.
            if (joinGid != -1 && dragged != null) {
                resetMergeState();
                // v1.4.8 — the dragged tab's ROW departs into the folder:
                // anchored empties around it re-anchor to its neighbours,
                // and a group losing its LAST member keeps its row spot
                // (the capture) — both BEFORE the groupId write
                try {
                    if (dragged.groupId != 0 && tm.tabsOfGroup(dragged.groupId).size() == 1)
                        tm.captureGroupRowAnchors(dragged.groupId);
                    tm.refreshAnchorsAround("t" + dragged.tid);
                } catch (Throwable ignored) {}
                dragged.groupId = joinGid;
                tm.save();
                adapter.reloadDiff(joinGid);
                Fx.toast(TabsActivity.this,
                        getString(R.string.moved_to_group) + ": " + groupName(joinGid));
                return;
            }

            // 2) armed merge on another tab card -> group them (animated)
            if (mergeTarget != null && dragged != null && dragged != mergeTarget) {
                resetMergeState();
                // v1.4.8 — both rows depart into the (new) folder: capture
                // a group losing its last member, then refresh around both
                try {
                    if (dragged.groupId != 0 && tm.tabsOfGroup(dragged.groupId).size() == 1)
                        tm.captureGroupRowAnchors(dragged.groupId);
                    if (mergeTarget.groupId != 0 && mergeTarget.groupId != dragged.groupId
                            && tm.tabsOfGroup(mergeTarget.groupId).size() == 1)
                        tm.captureGroupRowAnchors(mergeTarget.groupId);
                    tm.refreshAnchorsAround("t" + dragged.tid);
                    tm.refreshAnchorsAround("t" + mergeTarget.tid);
                } catch (Throwable ignored) {}
                if (mergeTarget.groupId != 0) {
                    dragged.groupId = mergeTarget.groupId;
                    tm.save();
                    adapter.reloadDiff(mergeTarget.groupId);
                    Fx.toast(TabsActivity.this,
                            getString(R.string.moved_to_group) + ": " + groupName(mergeTarget.groupId));
                } else {
                    String gname = Fx.baseDomain(Fx.hostOf(mergeTarget.url == null ? "" : mergeTarget.url));
                    if (gname == null || gname.isEmpty()) gname = "Group";
                    JSONObject g = prefs.addGroup(gname,
                            (int) (System.currentTimeMillis() % Fx.GROUP_COLORS.length));
                    long gid = g.optLong("id");
                    if (gid != 0) {
                        mergeTarget.groupId = gid;
                        dragged.groupId = gid;
                        tm.save();
                        adapter.reloadDiff(gid);
                        Fx.toast(TabsActivity.this, getString(R.string.group_created)
                                + ": " + gname);
                    }
                }
                return;
            }

            // 3) v1.3.46 plain drop — the SNAP-ZONE rule. LEFT of a tab:
            //    the held tab lands immediately BEFORE it (everything from
            //    the insertion point onward slides one slot); RIGHT: land
            //    immediately AFTER it (same slide, space opens past it).
            //    CENTER unarmed is the group zone with no intent — a NO-OP
            //    glide home. Empty space resolves to the NEAREST tile with
            //    the same zone rule (the fraction clamps past the card's
            //    edges); the home slot and group rows also glide home.
            //    v1.3.47: sort modes are gone (manual-only) — the reorder
            //    ban now applies to SEARCH only.
            //    v1.4.4 — A FOLDER ROW NOW ANCHORS A LOOSE DRAG in the
            //    FOLLOW-TAB-ORDER placement (the report: "in the follow
            //    tabs order, tabs are not still manually adjustable" —
            //    folders interleave that grid, and every drop beside one
            //    was a silent no-op). LEFT of a folder row: the tab lands
            //    immediately BEFORE the folder's first member; RIGHT:
            //    immediately AFTER its last member — the never-between-
            //    members law, the same boundary a folder drag lands on
            //    (tm.moveTabBesideGroup). On-top keeps its contract: a
            //    folder row there still only glides the tab home (the
            //    loose section starts below the pinned folders).
            resetMergeState();
            if (dragged == null || fingerX < 0 || tabQuery != null) {
                settleFromDrop(vh, dropL, dropT);
                return;
            }
            View under = rowUnder(rv, vh.itemView, fingerX, fingerY);
            View anchor = null;
            int z = ZONE_NONE;
            Tab b = null;                 // the anchor TAB (loose, or a folder's outer member)
            boolean folderAnchor = false; // b came from the folder-row path
            JSONObject emptyRowTarget = null;   // v1.4.8: the anchor row is an EMPTY folder's row
            if (under != null
                    && !adapter.isGroup(rv.getChildAdapterPosition(under))) {
                anchor = under;
                z = zoneOf(under);
            } else if (under != null && !prefs.groupsOnTop()) {
                // v1.4.4 — the in-order placement: a folder row's SIDE zones
                // anchor the drop beside the folder's block
                JSONObject gu = adapter.groupAt(rv.getChildAdapterPosition(under));
                z = zoneOf(under);
                if (gu != null) {
                    List<Tab> host = tm.tabsOfGroup(gu.optLong("id"));
                    if (!host.isEmpty()) {
                        b = z == ZONE_RIGHT ? host.get(host.size() - 1) : host.get(0);
                        anchor = under;
                        folderAnchor = true;
                    } else if (gu.optLong("id") != dragged.groupId) {
                        // v1.4.8 — an EMPTY folder's row is a real drop
                        // target (was a silent no-op: no member to anchor
                        // on — "does not let other tabs sit right next to
                        // it"). The landing resolves through the empty's
                        // own anchor pair below.
                        emptyRowTarget = gu;
                        anchor = under;
                    }
                }
            } else if (under == null && !fingerInside(vh.itemView)) {
                // v1.4.5 — the empty-space fallback now resolves the NEAREST
                // ROW OF ANY KIND in the follow-order placement (folders
                // interleave that grid; a drop past the last row of an
                // all-folders region used to glide silently home), and a
                // folder-row anchor resolves beside the folder's block
                // exactly like a direct folder-row drop. On-top keeps the
                // old loose-tabs-only fallback (the pinned folders are not
                // drop anchors there).
                View near = !prefs.groupsOnTop()
                        ? nearestRow(rv, vh.itemView) : nearestTabCard(rv, vh.itemView);
                if (near != null) {
                    anchor = near;
                    z = zoneOf(anchor);   // clamped: picks the nearest side
                    if (!prefs.groupsOnTop()
                            && adapter.isGroup(rv.getChildAdapterPosition(near))) {
                        JSONObject gu = adapter.groupAt(rv.getChildAdapterPosition(near));
                        if (gu != null && gu.optLong("id") != dragged.groupId) {
                            List<Tab> host = tm.tabsOfGroup(gu.optLong("id"));
                            if (!host.isEmpty()) {
                                b = z == ZONE_RIGHT ? host.get(host.size() - 1) : host.get(0);
                                folderAnchor = true;
                            } else {
                                emptyRowTarget = gu;   // v1.4.8: an empty row anchors here too
                            }
                        }
                    }
                }
            }
            if (anchor == null || z == ZONE_CENTER) {   // home slot, unarmed center, folder row on-top
                settleFromDrop(vh, dropL, dropT);
                return;
            }
            boolean after = (z == ZONE_RIGHT);
            if (emptyRowTarget != null && b == null) {
                // v1.4.8 — THE BESIDE-AN-EMPTY LANDING: the tab inserts
                // into tm.tabs beside the empty's row, and the empty
                // re-anchors to FACE the new tab — the two finally sit
                // right next to each other.
                // v1.4.9 — THE RENDER-NEIGHBOUR LAW (the (2,2) corpse +
                // the inverted write): the insertion resolves through the
                // empty's VISIBLE neighbours in the render — never the
                // stored "an"/"ap", which can be stale (a bare "" from
                // when the empty was the last row, a dangling key) while
                // the empty renders mid-list, sending the tab to the very
                // end/top of the list; and the write now puts the tab on
                // the finger's side of the empty (after → the tab is the
                // empty's row-BELOW), where v1.4.8 rendered it on the
                // OPPOSITE side.
                try {
                    long egid = emptyRowTarget.optLong("id");
                    String ean = emptyRowTarget.optString("an", "");
                    String eap = emptyRowTarget.optString("ap", "");
                    tm.refreshAnchorsAround("t" + dragged.tid);   // the dragged tab's old row departs
                    tm.tabs.remove(dragged);
                    String aboveK = tm.renderNeighbourKeyOfEmpty(egid, false, "t" + dragged.tid);
                    String belowK = tm.renderNeighbourKeyOfEmpty(egid, true, "t" + dragged.tid);
                    int k;
                    if (after) {
                        Tab a = belowK == null ? anchorKeyTab(ean, false)
                                : chainBoundaryTab(belowK, "t" + dragged.tid, true, false);
                        k = a != null ? tm.tabs.indexOf(a) : tm.tabs.size();
                    } else {
                        Tab a = aboveK == null ? anchorKeyTab(eap, true)
                                : chainBoundaryTab(aboveK, "t" + dragged.tid, false, true);
                        k = a != null ? tm.tabs.indexOf(a) + 1 : 0;
                    }
                    if (k < 0) k = tm.tabs.size();
                    tm.tabs.add(k, dragged);
                    prefs.setGroupAnchor(egid,
                            after ? (aboveK == null ? eap : aboveK) : "t" + dragged.tid,
                            after ? "t" + dragged.tid : (belowK == null ? ean : belowK));
                    tm.save();
                } catch (Throwable ignored) {}
                if (deferIfBusy(() -> settleDropSlide(vh, dragged, dropL, dropT))) return;
                settleDropSlide(vh, dragged, dropL, dropT);
                return;
            }
            if (b == null) b = adapter.tabAt(rv.getChildAdapterPosition(anchor));
            if (b == null || b == dragged
                    || (b.groupId != 0 && !folderAnchor)) {
                settleFromDrop(vh, dropL, dropT);
                return;
            }
            if (folderAnchor) {
                // land beside the folder's BLOCK (never inside its members)
                long og = b.groupId;
                // v1.4.8 — the dragged tab's row departs its old spot:
                // empties anchored to it re-anchor to its OLD neighbours,
                // and an empty sitting at the landing gap re-anchors to
                // face the landed tab
                try {
                    tm.refreshAnchorsAround("t" + dragged.tid);
                    int ap0 = rv.getChildAdapterPosition(anchor);
                    if (ap0 >= 0) retargetBoundaryEmpty(ap0, after, "t" + dragged.tid,
                            vh.getBindingAdapterPosition());
                } catch (Throwable ignored) {}
                if (tm.moveTabBesideGroup(dragged, og, after)) {
                    if (deferIfBusy(() -> settleDropSlide(vh, dragged, dropL, dropT))) return;
                    settleDropSlide(vh, dragged, dropL, dropT);
                } else {
                    settleFromDrop(vh, dropL, dropT);
                }
                return;
            }
            // v1.4.8 — same refreshes for the plain beside-a-tab landing
            try {
                tm.refreshAnchorsAround("t" + dragged.tid);
                int ap0 = rv.getChildAdapterPosition(anchor);
                if (ap0 >= 0) retargetBoundaryEmpty(ap0, after, "t" + dragged.tid,
                        vh.getBindingAdapterPosition());
            } catch (Throwable ignored) {}
            tm.tabs.remove(dragged);
            int bi = tm.tabs.indexOf(b);
            if (bi < 0) {                               // anchor vanished mid-gesture: abort cleanly
                tm.tabs.add(dragged);
                settleFromDrop(vh, dropL, dropT);
                return;
            }
            int k = after ? bi + 1 : bi;                // slot in the list WITHOUT the held tab
            tm.tabs.add(k, dragged);
            tm.save();
            // v1.3.47 crash fix: clearView can fire from an item-animator
            // callback INSIDE a layout pass — the reload inside the settle
            // would throw (the v1.3.46 "crash when trying to sort tabs").
            // Defer the whole settle to the next frame when the RV is busy.
            if (deferIfBusy(() -> settleDropSlide(vh, dragged, dropL, dropT))) return;
            settleDropSlide(vh, dragged, dropL, dropT);
            } finally {
                flushPendingReload();   // v1.4.16: the parked reload (if any) runs now
            }
        }

        /** v1.3.46 — glide the held card from its DROP POINT back to (its
         *  current) slot: after super.clearView the translation is already 0,
         *  so the offset is re-applied and animated away. Used by every
         *  no-reorder outcome (home slot, unarmed center, sorted modes) and
         *  ahead of the group-merge diffs. */
        private void settleFromDrop(RecyclerView.ViewHolder vh, float dropL, float dropT) {
            try {
                View v = vh.itemView;
                float dx = dropL - v.getLeft(), dy = dropT - v.getTop();
                if (Math.abs(dx) < 1f && Math.abs(dy) < 1f) return;
                v.animate().cancel();
                v.setTranslationX(dx);
                v.setTranslationY(dy);
                v.animate().translationX(0f).translationY(0f)
                        .setDuration(200)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                        .start();
            } catch (Throwable ignored) {}
        }

        /** identity key for the settle capture: Tabs by instance, group rows
         *  by their id (rebuildItems re-fetches fresh JSONObjects, so identity
         *  would never match across a reload). */
        private Object itemKey(Object o) {
            if (o instanceof Tab) return o;
            if (o instanceof JSONObject) return "g" + ((JSONObject) o).optLong("id");
            return null;
        }

        /** v1.3.46 — THE smooth slide, hand-animated so nothing can leave
         *  the screen: capture every attached tile's current rect, apply the
         *  reorder with a hard reload (ItemAnimator suppressed for this one
         *  pass), then — after the layout pass, before the first draw of it
         *  — set each tile's translation to (oldRect - newRect) and glide it
         *  to zero. The held card glides from the DROP POINT into its new
         *  slot. Every animation endpoint is a real layout slot, so all
         *  tiles stay onscreen by construction; nothing is ever translated
         *  off the RecyclerView's bounds.
         *  v1.4.0 — takes the dragged ITEM (a Tab OR a group JSONObject —
         *  the group-row drop path rides the same animation); identity
         *  resolves through itemKey either way. */
        private void settleDropSlide(RecyclerView.ViewHolder vh, final Object dragged,
                final float dropL, final float dropT) {
            try {
                final RecyclerView r = rv;
                // 1) the pre-change rects of every attached tile, keyed by
                //    adapter item (Tabs are identity-stable; holders may swap)
                final java.util.HashMap<Object, int[]> oldRects = new java.util.HashMap<>();
                for (int i = 0; i < r.getChildCount(); i++) {
                    View c = r.getChildAt(i);
                    int p = r.getChildAdapterPosition(c);
                    Object key = (p >= 0 && p < adapter.items.size())
                            ? itemKey(adapter.items.get(p)) : null;
                    if (key != null) oldRects.put(key, new int[]{c.getLeft(), c.getTop()});
                }
                // 2) the hard reload (no ItemAnimator fights, no DiffUtil
                //    move-animations from stale slots)
                final RecyclerView.ItemAnimator ia = r.getItemAnimator();
                r.setItemAnimator(null);
                adapter.reload();
                // 3) after the layout lands (pre-draw), glide every tile home
                final ViewTreeObserver vto = r.getViewTreeObserver();
                vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        try {
                            r.getViewTreeObserver().removeOnPreDrawListener(this);
                        } catch (Throwable ignored) {}
                        try { r.setItemAnimator(ia); } catch (Throwable ignored) {}
                        try {
                            for (int i = 0; i < r.getChildCount(); i++) {
                                View c = r.getChildAt(i);
                                int p = r.getChildAdapterPosition(c);
                                Object key = (p >= 0 && p < adapter.items.size())
                                        ? itemKey(adapter.items.get(p)) : null;
                                float fx, fy;
                                // v1.4.0: identity via itemKey — a group row
                                // (fresh JSONObject instances across the
                                // rebuild) glides from the drop point too
                                if (key != null && key.equals(itemKey(dragged))) {
                                    fx = dropL; fy = dropT;      // held card: from the DROP POINT
                                } else {
                                    int[] old = key != null ? oldRects.get(key) : null;
                                    if (old == null) continue;   // newly attached: no history
                                    fx = old[0]; fy = old[1];
                                }
                                float dx = fx - c.getLeft(), dy = fy - c.getTop();
                                if (Math.abs(dx) < 1f && Math.abs(dy) < 1f) continue;
                                c.animate().cancel();
                                c.setTranslationX(dx);
                                c.setTranslationY(dy);
                                c.animate().translationX(0f).translationY(0f)
                                        .setDuration(240)
                                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f))
                                        .start();
                            }
                        } catch (Throwable ignored) {}
                        return true;
                    }
                });
            } catch (Throwable ignored) {
                adapter.reload();   // never leave the board stale
            }
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
            dragActive = false;
            stopAutoScroll();          // v1.3.47: a swipe end also kills the auto-scroll loop
            fingerX = fingerY = -1;
            int pos = vh.getBindingAdapterPosition();
            Tab t = adapter.tabAt(pos);
            if (t != null) {
                // v1.4.7 → v1.4.8 — THE DEPARTURE REFRESH + LAST-MEMBER
                // CAPTURE: anchored empties around this row re-anchor to
                // its neighbours, and when the swipe empties the tab's
                // group, the FOLDER ROW's current neighbours become that
                // (now empty) group's anchors — so the folder row keeps
                // its exact place. Both BEFORE tm.close removes the row
                // (afterwards the position is gone with it).
                try {
                    if (t.groupId != 0 && tm.tabsOfGroup(t.groupId).size() == 1)
                        tm.captureGroupRowAnchors(t.groupId);   // v1.4.8: the anchor pair (was the absolute slot)
                    tm.refreshAnchorsAround("t" + t.tid);
                } catch (Throwable ignored) {}
                int idx = tm.tabs.indexOf(t);
                if (idx >= 0) tm.close(idx);
                adapter.reloadRemoved(pos);   // v1.3.35: animated collapse
                Ui.tap(rv);                   // v1.3.36: haptic + undo snackbar
                showUndoSnack();
            }
        }

        @Override
        public boolean isLongPressDragEnabled() { return !selMode; }
        @Override
        public boolean isItemViewSwipeEnabled() { return !selMode; }
    }

    // ------------------------------------------------------------------ v1.3.31 switcher options

    /** 3-dot menu of the tabs screen: group placement, card/list view.
     *  Every choice is a single-tick dialog, applied instantly.
     *
     *  v1.3.40 — the menu also owns SELECT and CLOSE (both relocated from
     *  the congested six-button header; close-all's bin icon was identical
     *  to the trash bin — the field report's "tab delete vs trash" mixup).
     *  Select mode swaps the pair for its own contextual entries: close the
     *  selected tabs, or exit selection.
     *
     *  v1.3.47 FIELD — THE SORT OPTIONS ARE GONE ("remove the different
     *  sorting options for tabs, just keep the manual one, remove the sort
     *  button fully"): tabs render in MANUAL order only, forever
     *  (Prefs.tabsSort() is pinned to 0, so legacy saved sorts 1/2 also
     *  collapse to manual on upgrade). */
    protected void showTabsOptionsMenu() {
        java.util.List<String> optList = new java.util.ArrayList<>();
        optList.add(getString(R.string.groups_position) + ": "
                + (prefs.groupsOnTop() ? getString(R.string.groups_on_top)
                : getString(R.string.groups_in_order)));
        optList.add(getString(R.string.tab_view) + ": "
                + (prefs.tabsAsCards() ? getString(R.string.view_cards)
                : getString(R.string.view_list)));
        final int selectIdx;
        if (selMode) {
            selectIdx = -1;
            if (!selected.isEmpty()) optList.add(getString(R.string.close_selected, selected.size()));
            optList.add(getString(R.string.exit_selection));
        } else {
            selectIdx = optList.size();
            optList.add(getString(R.string.select_tabs));
            optList.add(getString(R.string.close_all_tabs));
        }
        final String[] opts = optList.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.tabs_options)
                .setItems(opts, (d, w) -> {
                    if (w == 0) pickGroups();
                    else if (w == 1) pickView();
                    else if (w == selectIdx) enterSelMode();
                    else if (w == selectIdx + 1) confirmCloseAll();
                    else if (selMode && !selected.isEmpty()
                            && getString(R.string.close_selected, selected.size()).equals(opts[w])) {
                        closeSelected();
                    } else if (selMode && opts[w].equals(getString(R.string.exit_selection))) {
                        exitSelMode();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** v1.4.8 — write an EMPTY folder's drop as an anchor pair: the empty
     *  lands between two rows. [underPos] is the adapter position of the
     *  row under the finger, [after] which half of it, [selfPos] the
     *  dragged row's own adapter position — still in the list (no live
     *  shuffle: onMove returns false), so every position above it reads
     *  ONE TOO HIGH in the without-self render the anchors must be
     *  computed against. THE V1.4.7 SETTLE CORPSE: the slot write ignored
     *  this and the empty landed one position off whenever it sat above
     *  the target. Also frees any empties anchored to THIS empty's old
     *  row (they keep its old neighbours — only the one held moves). */
    private void dropAnchorEmptyGroup(long gid, int underPos, boolean after, int selfPos) {
        try {
            List<Object> rows = new ArrayList<>(adapter.items.size());
            for (int i = 0; i < adapter.items.size(); i++)
                if (i != selfPos) rows.add(adapter.items.get(i));
            int virt = underPos > selfPos ? underPos - 1 : underPos;   // position in the without-self list
            int b0 = after ? virt : virt - 1;   // the row above the landing gap
            String pk = b0 >= 0 && b0 < rows.size()
                    ? com.bolt.browser.core.TabManager.rowKeyOf(rows.get(b0)) : "";
            String nk = b0 + 1 >= 0 && b0 + 1 < rows.size()
                    ? com.bolt.browser.core.TabManager.rowKeyOf(rows.get(b0 + 1)) : "";
            try { tm.refreshAnchorsAround("g" + gid); } catch (Throwable ignored) {}
            prefs.setGroupAnchor(gid, pk, nk);
        } catch (Throwable ignored) {}
    }

    /** v1.4.8 — when a drop lands in a gap and the row on the FAR side of
     *  the gap is an EMPTY folder's row, that empty re-anchors to FACE the
     *  newly landed row — otherwise it stays anchored to the row beyond
     *  and the two read as pushing each other one position off ("it does
     *  not let other tabs sit right next to it"). [underPos] the anchor
     *  row's adapter position, [after] the drop's side, [newKey] the
     *  landed row's key, [selfPos] the dragged row's own position (its
     *  own row is never its neighbour).
     *  v1.4.9 — the KEPT side is render-derived too (the stored key can be
     *  stale — the (2,2) corpse family): the far side walks outward past
     *  the departing row (newKey), so the empty pins against a row that
     *  SURVIVES the landing. */
    private void retargetBoundaryEmpty(int underPos, boolean after, String newKey, int selfPos) {
        try {
            int farPos = after ? underPos + 1 : underPos - 1;
            if (farPos < 0 || farPos >= adapter.items.size() || farPos == selfPos) return;
            JSONObject eg = adapter.groupAt(farPos);
            if (eg == null || !tm.tabsOfGroup(eg.optLong("id")).isEmpty()) return;   // not an empty's row
            long egid = eg.optLong("id");
            String ap = eg.optString("ap", ""), an = eg.optString("an", "");
            String kept = tm.renderNeighbourKeyOfEmpty(egid, !after, newKey);
            if (after) prefs.setGroupAnchor(egid, newKey,
                    kept == null ? an : kept);   // the empty now follows the landed row
            else prefs.setGroupAnchor(egid,
                    kept == null ? ap : kept, newKey);   // ...now precedes it
        } catch (Throwable ignored) {}
    }

    /** v1.4.9 — THE EMPTY-CHAIN BOUNDARY: the render neighbour on the
     *  landing side can itself be an EMPTY folder's row — an empty has no
     *  tm.tabs slot, so the members cannot anchor on it, and the v1.4.8
     *  shape fell to lastRowAnchorTab and teleported the drop to the very
     *  bottom (the second (2,2) teleport vector: it fires when the empty
     *  target has ANOTHER empty directly beside it). The chain's NEAR-END
     *  empty (the one directly beside the landing gap) re-anchors to FACE
     *  the landed row — the chain behind it follows through its own keys,
     *  so the whole chain slides one slot and the landed row takes the
     *  gap — and the members anchor on the first MATERIAL row past the
     *  chain (a loose tab, or a membered folder's outer member).
     *  [key] the render key beside the gap, [landedKey] the landing row's
     *  key, [down] the walk direction (true = below the gap), [after]
     *  which side of the resolved boundary tab the members land on.
     *  Returns the boundary tab, or null at the list edge (a genuine
     *  edge landing — the caller falls to lastRowAnchorTab / the top). */
    private Tab chainBoundaryTab(String key, String landedKey, boolean down, boolean after) {
        try {
            // pass 1 — walk the chain with PURE READS (no write may move an
            // empty mid-walk: the walk reads the render, and a re-anchored
            // empty renders somewhere else on the next pass). The near-end
            // empty's far side is captured on the way (its kept anchor).
            long faceGid = 0;
            String faceKept = "";
            String material = key;
            int guard = 64;
            while (material != null && material.length() >= 2
                    && material.charAt(0) == 'g' && guard-- > 0) {
                long e2 = Long.parseLong(material.substring(1));
                if (!tm.tabsOfGroup(e2).isEmpty()) break;   // a membered row: material
                String next = tm.renderNeighbourKeyOfEmpty(e2, down, landedKey);
                if (faceGid == 0) {                          // the near-end empty
                    faceGid = e2;
                    faceKept = next == null ? "" : next;     // its far side, past the departing row
                }
                material = next;
            }
            // pass 2 — the near-end empty re-anchors to FACE the landed row
            // (its far side pins against the row it already had there, so
            // the chain behind it follows through its own keys)
            if (faceGid != 0) {
                prefs.setGroupAnchor(faceGid,
                        down ? landedKey : faceKept,
                        down ? faceKept : landedKey);
            }
            if (material == null || material.length() < 2) return null;   // the render's edge
            return anchorKeyTab(material, after);
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.8 — resolve an anchor key to the TAB moveGroupRelative /
     *  insertions anchor on: "t<tid>" the tab itself when loose, its
     *  folder's OUTER member on the landing side when membered; "g<gid>"
     *  that folder's outer member; null when the key is bare ("") or
     *  dangling. [landingAfter] = which side of the resolved tab the
     *  landing happens on. */
    private Tab anchorKeyTab(String key, boolean landingAfter) {
        try {
            if (key == null || key.length() < 2) return null;
            if (key.charAt(0) == 't') {
                Tab t = tm.tabByTid(Long.parseLong(key.substring(1)));
                if (t == null) return null;
                if (t.groupId == 0) return t;
                List<Tab> host = tm.tabsOfGroup(t.groupId);
                if (host.isEmpty()) return null;
                return landingAfter ? host.get(host.size() - 1) : host.get(0);
            }
            if (key.charAt(0) == 'g') {
                List<Tab> host = tm.tabsOfGroup(Long.parseLong(key.substring(1)));
                if (host.isEmpty()) return null;
                return landingAfter ? host.get(host.size() - 1) : host.get(0);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** v1.4.8 — the anchor tab of the render's LAST row (a loose tab, or
     *  the last member of the last membered folder) — the landing for a
     *  bottom-edge drop. Memberless folders are skipped (they are not
     *  tm.tabs rows). */
    private Tab lastRowAnchorTab() {
        try {
            List<Object> rows = tm.followRowOrder();
            for (int i = rows.size() - 1; i >= 0; i--) {
                Object o = rows.get(i);
                if (o instanceof Tab) return (Tab) o;
                List<Tab> host = tm.tabsOfGroup(((JSONObject) o).optLong("id"));
                if (!host.isEmpty()) return host.get(host.size() - 1);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** v1.4.8 — the anchor tab of the render's FIRST row — the landing for
     *  a top-edge drop. */
    private Tab firstRowAnchorTab() {
        try {
            for (Object o : tm.followRowOrder()) {
                if (o instanceof Tab) return (Tab) o;
                List<Tab> host = tm.tabsOfGroup(((JSONObject) o).optLong("id"));
                if (!host.isEmpty()) return host.get(0);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** v1.3.40 — "Close all tabs" moved here from the header (was the
     *  bin-icon twin of the trash button). Same confirm flow as before. */
    private void confirmCloseAll() {
        if (tm.tabs.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.close_all) + "?")
                .setPositiveButton(R.string.yes, (d, w) -> {
                    tm.closeAll();
                    adapter.reload();
                    finish();
                })
                .setNegativeButton(R.string.no, null).show();
    }

    private void pickGroups() {
        final String[] names = {getString(R.string.groups_on_top), getString(R.string.groups_in_order)};
        com.bolt.browser.util.Ui.choiceSheet(this, getString(R.string.groups_position), names,
                prefs.groupsOnTop() ? 0 : 1, w -> {
                    // v1.4.5 — THE PROJECTION LAW: switching on-top ->
                    // follow-order MATERIALIZES the on-top visual into
                    // tm.tabs (tm.materializeOnTopOrder) so the follow
                    // render — which anchors each folder row at its first
                    // member — draws the IDENTICAL arrangement the user was
                    // looking at: folders stay at the top, nothing jumps,
                    // nothing reads as vanished ("Tab groups don't stay in
                    // the top when I switch … I want the tabs and the
                    // groups stay in position as it is in the groups on
                    // top positions, and then let me drag everything").
                    // From there every drag (loose tabs beside folders,
                    // folders anywhere, members inside folders) reorders
                    // the one memory. The REVERSE switch (follow -> on-top)
                    // stays a PURE VIEW CHANGE: folders pin to the top,
                    // data untouched ("the always top does what it does
                    // now").
                    boolean wasOnTop = prefs.groupsOnTop();
                    if (wasOnTop && w == 1) {
                        try { tm.materializeOnTopOrder(); } catch (Throwable ignored) {}
                    }
                    // v1.4.8 — THE REVERSE SYNC: follow -> on-top now
                    // writes the follow render's folder-row order into the
                    // groups ARRAY (the on-top memory) first, so the
                    // pinned view opens on the SAME arrangement — an empty
                    // dragged in the follow view finally survives the
                    // switch (the projection law ran one way only; the
                    // empties' follow positions never reached the array).
                    // On-top -> follow still MATERIALIZES (above) — the
                    // follow view re-pins every empty's anchors to the
                    // on-top folder-block neighbours.
                    if (!wasOnTop && w == 0) {
                        try { tm.syncGroupsArrayToFollowOrder(); } catch (Throwable ignored) {}
                    }
                    prefs.set("groupsOnTop", w == 0);
                    // v1.4.3 — NO SYNC NEEDED: both placements derive the
                    // folder order from tm.tabs (first-member positions),
                    // so the toggle is a PURE VIEW CHANGE — the arrangement
                    // round-trips losslessly (the v1.4.1 sync used to
                    // rebuild one ordering from the other and destroyed
                    // the interleaved arrangement in the round trip).
                    adapter.reload();
                });
    }

    private void pickView() {
        final String[] names = {getString(R.string.view_cards), getString(R.string.view_list)};
        com.bolt.browser.util.Ui.choiceSheet(this, getString(R.string.tab_view), names,
                prefs.tabsAsCards() ? 0 : 1, w -> {
                    prefs.set("tabsAsCards", w == 0);
                    applyViewMode();
                    adapter.reload();
                });
    }

    /** cards = 2-column grid, list = 1-column compact rows (v1.3.31). */
    protected void applyViewMode() {
        try { glm.setSpanCount(prefs.tabsAsCards() ? 2 : 1); } catch (Throwable ignored) {}
    }

    /** v1.3.31 — open the switcher where the action is: the ACTIVE tab
     *  ("show tabs from the bottom" — with the default manual sort the active
     *  tab is usually the newest, at the bottom of the grid). v1.4.12: a
     *  member tab resolves to its GROUP row (see the walk below); the
     *  end-of-list fallback now only covers a vanished/unknown active. */
    protected void scrollToActive() {
        rv.post(() -> {
            try {
                // v1.4.12 — the group-member case: the ACTIVE tab may live
                // INSIDE a folder, and a member never renders as its own
                // grid item — its row IS the group row. The old walk only
                // matched loose tab items, so every group-member start
                // fell through to the scroll-to-end fallback and the
                // user's current context was off-screen. The walk now
                // resolves the group row for a member (by groupId) and
                // still matches loose tabs directly (search-filtered lists
                // are flat, so the direct match keeps covering them).
                Tab active = null;
                if (tm.active >= 0 && tm.active < tm.tabs.size()) {
                    active = tm.tabs.get(tm.active);   // single atomic read
                }
                int target = -1;
                if (active != null) {
                    for (int i = 0; i < adapter.getItemCount(); i++) {
                        if (adapter.isGroup(i)) {
                            if (active.groupId != 0) {
                                JSONObject g = adapter.groupAt(i);
                                if (g != null && g.optLong("id") == active.groupId) {
                                    target = i; break;
                                }
                            }
                        } else if (adapter.tabAt(i) == active) {
                            target = i; break;
                        }
                    }
                }
                if (target < 0) {
                    // v1.3.47: sort options removed — the active tab is
                    // always near the list's end in manual order
                    rv.scrollToPosition(Math.max(0, adapter.getItemCount() - 1));
                    return;
                }
                glm.scrollToPositionWithOffset(target, Fx.dp(this, 60));
            } catch (Throwable ignored) {}
        });
    }

    // ------------------------------------------------------------------ misc ui

    protected void refreshChips(TextView normal, TextView inc) {
        // v1.3.34 — SEGMENTED CONTROL: the chips sit inside a pill container
        // (bg_segment); the ACTIVE segment is an accent pill with white
        // label, the inactive one stays transparent with dim label — the
        // modern M3 segmented-button look (was: two plain square chips)
        boolean incognitoMode = incognitoProcess;
        styleSegment(normal, !incognitoMode);
        styleSegment(inc, incognitoMode);
    }

    private void styleSegment(TextView seg, boolean active) {
        if (active) {
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setColor(Fx.accent(this));
            g.setCornerRadius(Fx.dp(this, 999));
            seg.setBackground(g);
            // v1.3.35: contrast-aware label (light accents flip to dark text)
            seg.setTextColor(Fx.onAccent(this));
        } else {
            seg.setBackground(null);
            seg.setTextColor(Fx.dim(this));
        }
    }

    protected void createGroup() {
        final EditText et = new EditText(this);
        et.setHint(R.string.group_name);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Fx.dp(this, 20);
        wrap.setPadding(p, p / 2, p, 0);
        wrap.addView(et, new LinearLayout.LayoutParams(-1, -2));
        // color picker row
        final int[] picked = {0};
        LinearLayout colors = new LinearLayout(this);
        final int n = Fx.GROUP_COLORS.length;
        final ImageView[] dots = new ImageView[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            ImageView iv = new ImageView(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Fx.GROUP_COLORS[i]);
            iv.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Fx.dp(this, 34), Fx.dp(this, 34));
            lp.rightMargin = Fx.dp(this, 10);
            iv.setLayoutParams(lp);
            iv.setOnClickListener(v -> {
                picked[0] = idx;
                for (ImageView d : dots) d.setAlpha(0.4f);
                iv.setAlpha(1f);
            });
            iv.setAlpha(i == 0 ? 1f : 0.4f);
            dots[i] = iv;
            colors.addView(iv);
        }
        wrap.addView(colors, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_group)
                .setView(wrap)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) name = "Group";
                    prefs.addGroup(name, picked[0]);
                    adapter.reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ adapter

    protected class TabsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        static final int TYPE_GROUP = 1, TYPE_CARD = 2;
        // v1.3.39 hardening: the holder POOL is keyed by view type — sharing
        // one TYPE_CARD between the card and list layouts meant a mode
        // switch (pickView → notifyDataSetChanged) recycled pooled CARD
        // holders into LIST rows (stale layouts on screen). Two layouts =
        // two types, always.
        static final int TYPE_GROUP_CARD = 3, TYPE_TAB_LIST = 4;
        private final List<Object> items = new ArrayList<>();

        boolean isGroup(int pos) {
            return pos >= 0 && pos < items.size() && items.get(pos) instanceof JSONObject;
        }

        JSONObject groupAt(int pos) {
            return pos >= 0 && pos < items.size() && items.get(pos) instanceof JSONObject
                    ? (JSONObject) items.get(pos) : null;
        }

        Tab tabAt(int pos) {
            return pos >= 0 && pos < items.size() && items.get(pos) instanceof Tab
                    ? (Tab) items.get(pos) : null;
        }

        /** v1.3.36 — the DISPLAY position of a tab (for animated removals:
         *  the adapter list is sorted, so the tm index is NOT this). */
        int posOf(Tab t) {
            return items.indexOf(t);
        }

        /** v1.3.78 — a favicon landed in the background (DuckDuckGo fetch,
         *  late onReceivedIcon): rebind ONLY the rows, cheap and guarded the
         *  same way every notify here is. Called from the favGen poller. */
        void rebindIcons() {
            // v1.4.4 — never rebind mid-MINI-DRAG: a notifyDataSetChanged
            // rebuilds the strip and detaches the dragged card's view while
            // the gesture still feeds it (the icon poller fires on any
            // favicon landing, which can happen mid-hold)
            if (miniDragTab != null) return;
            if (deferIfBusy(this::notifyDataSetChanged)) return;
            try { notifyDataSetChanged(); } catch (Throwable ignored) {}
        }

        void reload() {
            // v1.4.16 — THE MID-DRAG RELOAD LAW ("tab ghosting on move"):
            // never rebuild the board while ItemTouchHelper holds a card.
            // Park the request; DragMergeCallback.clearView runs it when
            // the gesture ends. (The drag itself never needed a reload:
            // onMove returns false by design — reordering commits at DROP
            // time — so the only mid-drag reloads were EXTERNAL mutations
            // racing the gesture, and those are exactly what deferring is
            // for.)
            if (dragCb != null && dragCb.dragActive) {
                pendingReloadDuringDrag = true;
                return;
            }
            if (deferIfBusy(() -> reload())) return;   // v1.3.47 crash fix
            rebuildItems();
            notifyDataSetChanged();
            afterReload();
        }

        /** v1.3.41 (field bug 3) — ANIMATED reload for the drag-drop outcomes:
         *  DiffUtil turns the items change into precise move/remove/insert
         *  notifications, so the DefaultItemAnimator slides every card into
         *  its new place — replacing the notifyDataSetChanged hard reset
         *  that made the dropped tab TELEPORT ("no animation for tab manual
         *  arrangement"). Identity: Tabs by instance (they are), group rows
         *  by group id. changedGroupId (0 = none) forces a change-pass on the
         *  one group row whose member count just moved, so its count badge
         *  crossfades instead of going stale. */
        void reloadDiff(long changedGroupId) {
            if (deferIfBusy(() -> reloadDiff(changedGroupId))) return;   // v1.3.47 crash fix
            final List<Object> old = new ArrayList<>(items);
            rebuildItems();
            final List<Object> now = new ArrayList<>(items);
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return old.size(); }
                @Override public int getNewListSize() { return now.size(); }
                @Override public boolean areItemsTheSame(int a, int b) {
                    Object x = old.get(a), y = now.get(b);
                    if (x instanceof Tab && y instanceof Tab) return x == y;
                    if (x instanceof JSONObject && y instanceof JSONObject)
                        return ((JSONObject) x).optLong("id") == ((JSONObject) y).optLong("id");
                    return false;
                }
                @Override public boolean areContentsTheSame(int a, int b) { return true; }
            }, true);
            diff.dispatchUpdatesTo(this);
            if (changedGroupId != 0) {
                for (int i = 0; i < items.size(); i++) {
                    JSONObject g = groupAt(i);
                    if (g != null && g.optLong("id") == changedGroupId) {
                        notifyItemChanged(i);
                        break;
                    }
                }
            }
            afterReload();
        }

        /** v1.3.35 — removal with the ANIMATED collapse: rebuilds quietly,
         * then notifies just the removed position so the RecyclerView slides
         * the survivors together (the "animated tab-card close" item). */
        void reloadRemoved(int pos) {
            if (deferIfBusy(() -> reloadRemoved(pos))) return;   // v1.3.47 crash fix
            int before = items.size();
            rebuildItems();
            if (pos >= 0 && pos < before) notifyItemRemoved(pos);
            else notifyDataSetChanged();
            afterReload();
        }

        /** v1.3.35 — insertion with a scale/pop-in (PopAnimator). */
        void reloadInserted(int pos) {
            if (deferIfBusy(() -> reloadInserted(pos))) return;   // v1.3.47 crash fix
            rebuildItems();
            if (pos >= 0 && pos < items.size()) notifyItemInserted(pos);
            else notifyDataSetChanged();
            afterReload();
        }

        /** v1.3.35 — insertion animation for a specific new Tab (duplicate). */
        void reloadInsertedTab(Tab t) {
            if (deferIfBusy(() -> reloadInsertedTab(t))) return;   // v1.3.47 crash fix
            rebuildItems();
            int pos = items.indexOf(t);
            if (pos >= 0) notifyItemInserted(pos);
            else notifyDataSetChanged();
            afterReload();
        }

        /** shared tail of every reload variant. */
        private void afterReload() {
            publishRenderedWalk();   // v1.3.46: the swipe walks what was RENDERED
            updateTabsEmptyState();   // v1.3.35: empty-state toggle
            if (!selMode) {
                TextView title = findViewById(R.id.tv_t_title);
                if (title != null) {
                    title.setText(getString(R.string.tabs) + " (" + tm.tabs.size() + ")");
                }
            }
            refreshChips((TextView) findViewById(R.id.chip_normal),
                    (TextView) findViewById(R.id.chip_incognito));
        }

        /** v1.3.46 — snapshot the RENDERED arrangement for the swipe walk:
         *  the loose tiles (groupId==0) in items order, which the grid lays
         *  out top-left -> bottom-right row by row. NOT published while a
         *  search filter is flattening the grid — a filtered subset is not
         *  the arrangement the user browses. */
        private void publishRenderedWalk() {
            try {
                if (tabQuery != null) return;
                List<Tab> walk = new ArrayList<>();
                for (Object o : items) if (o instanceof Tab && ((Tab) o).groupId == 0)
                    walk.add((Tab) o);
                sRenderedWalk = walk;
            } catch (Throwable ignored) {}
        }

        /** the items-list builder (the old reload() body). v1.3.36: while a
         *  search query is active the grid goes FLAT — matching tabs only,
         *  groups unrolled — so the filter is about finding, not browsing. */
        private void rebuildItems() {
            items.clear();
            // v1.3.36 — the live search filter. v1.3.40 (round-4 #7): it
            // also digs into the remembered PAGE TEXT, so a tab matches by
            // what is ON it, not just title/url (pageText is never captured
            // in incognito, so private tabs stay title/url-only).
            if (tabQuery != null) {
                String q = tabQuery;
                for (Tab t : tm.sortedForDisplay(prefs.tabsSort())) {
                    String title = t.isHome ? getString(R.string.home)
                            : (t.title == null ? "" : t.title);
                    String url = t.url == null ? "" : t.url;
                    String body = t.pageText == null ? "" : t.pageText;
                    if (title.toLowerCase().contains(q) || url.toLowerCase().contains(q)
                            || body.toLowerCase().contains(q)) {
                        items.add(t);
                    }
                }
                return;
            }
            // v1.3.31 — display order: sortedForDisplay() honors the switcher
            // sort (manual = list order; newest / last-visited = DESC).
            List<Tab> sorted = tm.sortedForDisplay(prefs.tabsSort());
            if (prefs.groupsOnTop()) {
                // v1.4.3 — ONE MEMORY, TWO VIEWS: the pinned rows render in
                // FIRST-MEMBER order (derived from tm.tabs), NOT groups-array
                // order. The v1.4.1 sync tried to reconcile two ordering
                // memories (the array for on-top, tm.tabs for in-order) at
                // every toggle — and rebuilding one from the other DESTROYED
                // the interleaved arrangement (the v1.4.3 report: "the tab
                // folders move itself by changing modes"; probe L3: an
                // in-order [T1,A,T2,B,T3] round-tripped through on-top as
                // [A,B,T1,T2,T3]). With both placements deriving the folder
                // order from tm.tabs, a toggle is a PURE VIEW CHANGE — the
                // arrangement round-trips losslessly and no sync exists.
                for (JSONObject g : tm.groupsInFirstMemberOrder()) items.add(g);
                for (Tab t : sorted) if (t.groupId == 0) items.add(t);
            } else {
                // v1.3.31 — groups IN ORDER (file-manager style): each group
                // row appears at the position of its FIRST member in the
                // current sort, instead of being pinned to the top.
                // v1.4.8 — THE ROW MODEL MOVED INTO TABMANAGER
                // (followRowOrder): the walk, the empty-folder anchor
                // resolution and the pass loop are one model now — every
                // reader (the render, the anchor refreshes, the captures,
                // the reverse sync) derives the SAME order from the same
                // code, so no two surfaces can disagree about where a row
                // sits (the v1.4.7 slot corpse: the render indexed rows
                // one way while drops wrote indices another way).
                for (Object o : tm.followRowOrder()) items.add(o);
                // the normalization sweep: every memberless group gets an
                // explicit anchor pair at its CURRENT spot (a legacy
                // v1.4.7 slot is honoured first — the quiet upgrade), so
                // from this render on NOTHING derives its position. Also
                // the "disappeared groups from an earlier version"
                // interference heal: stale membered position memories are
                // cleared. Idempotent; a steady state writes nothing.
                try { tm.normalizeEmptyAnchors(); } catch (Throwable ignored) {}
            }
        }

        @Override
        public int getItemViewType(int position) {
            boolean cards = prefs.tabsAsCards();
            if (isGroup(position)) return cards ? TYPE_GROUP_CARD : TYPE_GROUP;
            return cards ? TYPE_CARD : TYPE_TAB_LIST;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == TYPE_GROUP || type == TYPE_GROUP_CARD) {
                // v1.3.39: groups render as folder cards in card view
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(type == TYPE_GROUP_CARD
                                ? R.layout.item_group_card : R.layout.item_group_row,
                                parent, false);
                // v1.3.34: card elevation on light skins (dark skins rely on
                // surface contrast — shadows are invisible there)
                v.setElevation(com.bolt.browser.util.Ui.wantShadows(TabsActivity.this)
                        ? Fx.dp(TabsActivity.this, 2) : 0);
                // v1.3.35: per-skin corner radius
                try { v.setBackground(Fx.cardBg(TabsActivity.this, 0)); } catch (Throwable ignored) {}
                return new GroupHolder(v);
            }
            // v1.3.31 — cards (thumbnail grid) or compact list rows
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(type == TYPE_CARD ? R.layout.item_tab_card : R.layout.item_tab_row,
                            parent, false);
            v.setElevation(com.bolt.browser.util.Ui.wantShadows(TabsActivity.this)
                    ? Fx.dp(TabsActivity.this, 2) : 0);
            // v1.3.35: per-skin corner radius
            try { v.setBackground(Fx.cardBg(TabsActivity.this, 0)); } catch (Throwable ignored) {}
            return new CardHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            if (h instanceof GroupHolder) {
                final JSONObject g = (JSONObject) items.get(pos);
                GroupHolder gh = (GroupHolder) h;
                String name = g.optString("name", "Group");
                gh.name.setText(name);
                final long gid = g.optLong("id");
                List<Tab> groupTabs = tm.tabsOfGroup(gid);
                gh.count.setText(String.valueOf(groupTabs.size()));
                // v1.3.39: card view's slot is the FOLDER icon (tinted with
                // the group color); list view keeps the plain color dot.
                if (gh.dot instanceof android.widget.ImageView) {
                    ((android.widget.ImageView) gh.dot).setColorFilter(Fx.GROUP_COLORS[
                            Math.max(0, Math.min(Fx.GROUP_COLORS.length - 1, g.optInt("color", 0)))]);
                } else {
                    gh.dot.setBackgroundColor(Fx.GROUP_COLORS[
                            Math.max(0, Math.min(Fx.GROUP_COLORS.length - 1, g.optInt("color", 0)))]);
                }
                // v1.4.12 — THE FOLDER SHEET replaces the inline strip. The
                // old expansion (v1.3.9) unrolled the group's minis into a
                // horizontal strip inside the grid row; past two tabs the
                // strip squished them unreadable. The header now opens a
                // VERTICALLY SCROLLABLE bottom sheet (openGroupSheet) — one
                // full-width row per tab, thumbnail + title + the ⋯ member
                // menu. The collapsed flag is no longer toggled from here
                // (the chevron always reads ∨ = "tap opens the folder") and
                // the inline strip never renders again; drag-to-group still
                // works — the row remains a drop target exactly as before.
                if (gh.cardsWrap != null) gh.cardsWrap.setVisibility(View.GONE);
                if (gh.chevron != null) {
                    gh.chevron.setRotation(90f); // ∨ — the folder opens the sheet
                    gh.chevron.setAlpha(0.6f);
                }
                if (gh.header != null) {
                    gh.header.setOnClickListener(v -> {
                        Ui.tap(v);
                        openGroupSheet(g);
                    });
                }
                gh.menu.setOnClickListener(v -> groupOptions(g));
                // v1.4.16 — THE GROUPED-ACTIVE HIGHLIGHT: applySelVisuals
                // only ever ran for LOOSE tab cards (tm.tabs.indexOf(t) ==
                // tm.active), so the tab you are reading inside a folder was
                // invisible in the switcher — no rim, no strip, nothing to
                // tell it apart from its sleeping siblings. The group row
                // now carries the same accent law when tm.active belongs to
                // ANY of its members: the accent rim (same stroke the
                // multi-select uses) on the row and an accent strip along
                // its bottom edge, exactly like the loose card's strip.
                // scrollToActive() already resolves a member's group row
                // (the v1.4.12 walk), so the highlight and the scroll
                // finally agree.
                boolean activeInGroup = false;
                if (!selMode) {
                    Tab at = (tm.active >= 0 && tm.active < tm.tabs.size())
                            ? tm.tabs.get(tm.active) : null;
                    if (at != null && at.groupId == gid) activeInGroup = true;
                }
                try {
                    if (activeInGroup) {
                        GradientDrawable rim = new GradientDrawable();
                        rim.setCornerRadius(Fx.dp(TabsActivity.this, 14));
                        rim.setStroke(Math.max(2, Fx.dp(TabsActivity.this, 2)),
                                Fx.accent(TabsActivity.this));
                        gh.itemView.setForeground(rim);
                    } else {
                        gh.itemView.setForeground(null);
                    }
                } catch (Throwable ignored) {}
                if (gh.strip != null) {
                    gh.strip.setBackgroundColor(activeInGroup
                            ? Fx.accent(TabsActivity.this) : 0x00000000);
                }
            } else {
                final Tab t = (Tab) items.get(pos);
                CardHolder ch = (CardHolder) h;
                // v1.3.40 — LOGO-TILE card: the main visual IS the site's
                // real logo (field report: "I don't like that all the tabs
                // are showing the home icon in card view thumbnail …
                // replace the thumbnail with website icon entirely — proper
                // icon, not just first letter"). Page snapshots are cache-
                // mortal; the favicon store (+ its network fallback) is not.
                try {
                    if (t.isHome) {
                        ch.thumb.setImageResource(R.drawable.ic_home);
                    } else {
                        String lh = Fx.hostOf(t.url);
                        ch.thumb.setImageDrawable(com.bolt.browser.util.FaviconStore.avatar(
                                TabsActivity.this, lh,
                                t.title == null || t.title.isEmpty() ? lh : t.title, 36));
                    }
                    ch.thumb.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    ch.thumb.setBackgroundColor(0x00000000);
                } catch (Throwable ignored) {}
                ch.title.setText((t.isHome ? getString(R.string.home)
                        : (t.title == null || t.title.isEmpty() ? t.url : t.title))
                        + (t.sleeping ? "  ·  asleep" : ""));
                ch.domain.setText(t.isHome ? "" : Fx.hostOf(t.url));
                // v1.3.38 TAB SLEEP: a slept tab shows its kept thumbnail at
                // reduced presence — tap wakes it (the normal switch path
                // rebuilds + reloads + restores the reading position).
                ch.itemView.setAlpha(t.sleeping && !selMode ? 0.55f : 1f);
                try {
                    if (t.sleeping) ch.thumb.setColorFilter(0x66808080);
                    else ch.thumb.setColorFilter(null);
                } catch (Throwable ignored) {}
                ch.close.setOnClickListener(v -> closeCardAnimated(t, ch.itemView));
                ch.itemView.setOnClickListener(v -> {
                    if (selMode) { toggleSel(t); return; }
                    int idx = tm.tabs.indexOf(t);
                    if (idx >= 0) {
                        tm.active = idx;
                        finish();
                    }
                });
                ch.menu.setOnClickListener(v -> cardOptions(t));
                applySelVisuals(ch.itemView, t);
                // the sleeping dimming must not fight the selection dimming
                if (selMode) ch.itemView.setAlpha(1f);
                View strip = ch.itemView.findViewById(R.id.v_strip);
                if (strip != null) {
                    // v1.3.38: the CURRENT tab's strip is accent too — the
                    // bottom edge of the card you are reading lights up.
                    boolean activeTab = !selMode && tm.tabs.indexOf(t) == tm.active;
                    strip.setBackgroundColor(selMode && selected.contains(t)
                            ? Fx.accent(TabsActivity.this)
                            : (activeTab ? Fx.accent(TabsActivity.this)
                            : (t.groupId != 0 ? Fx.GROUP_COLORS[0] : 0x00000000)));
                }
            }
        }

        private void applySelVisuals(View v, Tab t) {
            try {
                boolean sel = selMode && selected.contains(t);
                // v1.3.38 FIELD FIX ("Highlight the current open tab"): the
                // tab you are looking at gets an accent rim in the switcher —
                // same accent stroke the multi-select uses (the two states
                // never share the screen: entering selMode dims everything
                // that is not selected), plus the strip below turns accent.
                boolean active = !selMode && tm.tabs.indexOf(t) == tm.active;
                v.setAlpha(selMode ? (sel ? 1f : 0.45f) : 1f);
                if (sel || active) {
                    GradientDrawable g = new GradientDrawable();
                    g.setCornerRadius(Fx.dp(TabsActivity.this, 14));
                    g.setStroke(Math.max(2, Fx.dp(TabsActivity.this, 2)),
                            Fx.accent(TabsActivity.this));
                    v.setForeground(g);
                } else {
                    v.setForeground(null);
                }
            } catch (Throwable ignored) {}
        }

        @Override
        public int getItemCount() { return items.size(); }
    }

    // ------------------------------------------------------------------ v1.3.35 animated close

    /** animated card close: shrink + fade the card, then close the tab and
     *  collapse the gap with the animated removal (the swipe path uses the
     *  same reloadRemoved). */
    protected void closeCardAnimated(final Tab t, final View card) {
        if (card == null) return;
        Ui.tap(card);
        try {
            card.animate().scaleX(0.86f).scaleY(0.86f).alpha(0f).setDuration(150L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                    .withEndAction(() -> {
                        int idx = tm.tabs.indexOf(t);
                        if (idx >= 0) tm.close(idx);
                        int pos = rv.getChildAdapterPosition(card);
                        adapter.reloadRemoved(pos >= 0 ? pos : 0);
                        showUndoSnack();     // v1.3.36
                    }).start();
        } catch (Throwable ignored) {
            int idx = tm.tabs.indexOf(t);
            if (idx >= 0) tm.close(idx);
            adapter.reload();
        }
    }

    /** per-card 3-dot menu: duplicate tab, move to group, ungroup, close */
    protected void cardOptions(final Tab t) {
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.duplicate_tab));
        opts.add(getString(R.string.new_tab));
        opts.add(getString(R.string.move_to_group));
        if (t.groupId != 0) opts.add(getString(R.string.remove_from_group));
        // v1.3.38 — sleep THIS tab (focus-mode sibling; the card options is
        // the natural per-tab home for it)
        final boolean canSleepHere = com.bolt.browser.util.TabSleep.canSleep(t)
                && tm.tabs.indexOf(t) != tm.active;
        if (canSleepHere) opts.add("Sleep tab");
        opts.add(getString(R.string.close_tab));
        new AlertDialog.Builder(this)
                .setTitle(t.title == null || t.title.isEmpty() ? t.url : t.title)
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        // duplicate: same URL in a fresh tab
                        Tab n = new Tab();
                        n.isHome = t.isHome;
                        n.url = t.url;
                        n.title = t.title;
                        int idx = tm.tabs.indexOf(t);
                        tm.tabs.add(idx >= 0 ? idx + 1 : tm.tabs.size(), n);
                        tm.save();
                        adapter.reloadInsertedTab(n);   // v1.3.35: pop-in animation
                    } else if (w == 1) {
                        tm.addTab(new Tab());
                        adapter.reload();
                        finish();
                    } else {
                        String picked = opts.get(w);
                        if (picked.equals("Sleep tab") && canSleepHere) {
                            if (com.bolt.browser.util.TabSleep.sleep(t)) {
                                tm.scheduleSave();
                                adapter.reload();
                                Fx.toast(this, "Tab asleep — tap it to wake");
                            }
                        } else if (picked.equals(getString(R.string.remove_from_group))) {
                            t.groupId = 0;
                            tm.save();
                            adapter.reload();
                        } else if (picked.equals(getString(R.string.close_tab))) {
                            int pos = adapter.posOf(t);
                            int idx = tm.tabs.indexOf(t);
                            if (idx >= 0) {
                                tm.close(idx);
                                adapter.reloadRemoved(Math.max(0, pos));
                                showUndoSnack();
                            }
                        } else if (picked.equals(getString(R.string.move_to_group))) {
                            List<JSONObject> groups = tm.groups();
                            if (groups.isEmpty()) {
                                Fx.toast(this, "No groups yet");
                                return;
                            }
                            String[] names = new String[groups.size()];
                            for (int i = 0; i < groups.size(); i++)
                                names[i] = groups.get(i).optString("name");
                            new AlertDialog.Builder(this)
                                    .setTitle(R.string.move_to_group)
                                    .setItems(names, (d2, w2) -> {
                                        t.groupId = groups.get(w2).optLong("id");
                                        tm.save();
                                        adapter.reload();
                                    })
                                    .show();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** the tab-group member menu — v1.4.9: gains EDIT TAB (name + link —
     *  the report: "edit name, link, de-group, delete etc. to all") and a
     *  ⋯ button on every mini as the visible door (the hold-still gesture
     *  stays). Edit link navigates the tab (a live web loads the new URL;
     *  a sleeping tab restores into it). */
    protected void tabOptions(final Tab t, long curGroup) {
        List<JSONObject> groups = tm.groups();
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.edit_tab));
        opts.add(getString(R.string.remove_from_group));
        opts.add(getString(R.string.move_to_group));
        opts.add(getString(R.string.close_tab));
        new AlertDialog.Builder(this)
                .setTitle(t.title == null || t.title.isEmpty() ? t.url : t.title)
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        // v1.4.9 — EDIT TAB: the name and the link, the same
                        // two-field dialog the shortcut editors use. The
                        // name is a best-effort override (the site can re-take
                        // it on its next title event); the link NAVIGATES.
                        LinearLayout box = new LinearLayout(TabsActivity.this);
                        box.setOrientation(LinearLayout.VERTICAL);
                        final EditText etName = new EditText(TabsActivity.this);
                        etName.setHint(R.string.title_label);
                        etName.setText(t.title == null ? "" : t.title);
                        final EditText etUrl = new EditText(TabsActivity.this);
                        etUrl.setHint(R.string.url_label);
                        etUrl.setText(t.url == null ? "" : t.url);
                        etUrl.setSingleLine(true);
                        int pad = Fx.dp(TabsActivity.this, 8);
                        box.setPadding(pad, pad, pad, 0);
                        box.addView(etName);
                        box.addView(etUrl);
                        new AlertDialog.Builder(TabsActivity.this)
                                .setTitle(R.string.edit_tab)
                                .setView(box)
                                .setPositiveButton(R.string.save, (d2, w2) -> {
                                    String name = etName.getText().toString().trim();
                                    String url = etUrl.getText().toString().trim();
                                    if (!name.isEmpty()) t.title = name;
                                    if (url.startsWith("http") && !url.equals(t.url)) {
                                        t.url = url;
                                        if (t.web != null) {
                                            try { t.web.loadUrl(url); } catch (Throwable ignored) {}
                                        }
                                    }
                                    tm.save();
                                    adapter.reload();
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    } else if (w == 1) {
                        t.groupId = 0;
                        tm.save();
                        adapter.reload();
                    } else if (w == 2) {
                        if (groups.isEmpty()) {
                            Fx.toast(this, "No groups yet");
                            return;
                        }
                        String[] names = new String[groups.size()];
                        for (int i = 0; i < groups.size(); i++)
                            names[i] = groups.get(i).optString("name");
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.move_to_group)
                                .setItems(names, (d2, w2) -> {
                                    t.groupId = groups.get(w2).optLong("id");
                                    tm.save();
                                    adapter.reload();
                                })
                                .show();
                    } else if (w == 3) {
                        int pos = adapter.posOf(t);
                        int idx = tm.tabs.indexOf(t);
                        if (idx >= 0) {
                            tm.close(idx);
                            adapter.reloadRemoved(Math.max(0, pos));
                            showUndoSnack();
                        }
                    }
                })
                .show();
    }

    // ------------------------------------------------- v1.4.4 the in-group mini drag

    /** v1.4.5 — THE INTERCEPTION LAW: a hold-to-drag that lives INSIDE a
     *  scrollable parent (the mini strip rides a HorizontalScrollView,
     *  which rides the RecyclerView; the window cells ride a ScrollView)
     *  is DEAD ON DEVICE unless the lift RAISES the disallow flag through
     *  the parent chain — the first MOVE past the parent's slop is
     *  intercepted (RV steals vertical, HSV/ScrollView steal their axis),
     *  the child receives ACTION_CANCEL, and the drag ends before a single
     *  slot moved: exactly "the tabs inside the tab group are still not
     *  draggable" while every static gate stayed green (the v1.4.4 corps:
     *  the data layer was probe-proven, the GESTURE was never device-true).
     *  Raising the flag from the dragged view's PARENT arms that parent
     *  plus every ANCESTOR (lesson 83's direction law). Cleared at UP. */
    protected void ownTouchStream(View v, boolean own) {
        try {
            if (v == null) return;
            android.view.ViewParent p = v.getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(own);
        } catch (Throwable ignored) {}
    }

    /** arm the drag: capture the strip, the member's index, the siblings'
     *  base centers and the shift pitch. Called from the mini's hold timer
     *  (~380 ms); the same mini's touch stream then feeds
     *  [miniDragMove]/[miniDragEnd]. */
    protected void armMiniDrag(View mini, ViewGroup strip, Tab t, long gid, int memberIdx) {
        try {
            miniDragTab = t;
            miniDragGid = gid;
            miniDragFrom = memberIdx;
            miniDragTo = memberIdx;
            miniDragMoved = false;
            miniDragView = mini;
            miniDragStrip = strip;
            // v1.4.5 — the lift OWNS the touch stream from here: without
            // the flag the HSV/RecyclerView intercept the first move and
            // the drag dies as ACTION_CANCEL (the interception law above)
            ownTouchStream(mini, true);
            int n = strip.getChildCount();
            miniBaseX = new float[n];
            int[] loc = new int[2];
            for (int i = 0; i < n; i++) {
                View c = strip.getChildAt(i);
                c.getLocationInWindow(loc);
                miniBaseX[i] = loc[0] + c.getWidth() / 2f;
            }
            miniPitch = n >= 2 ? Math.max(1, strip.getChildAt(1).getLeft()
                    - strip.getChildAt(0).getLeft()) : mini.getWidth() + Fx.dp(this, 8);
            // the lift: visible, haptic, still cancelable by a plain release
            try {
                com.bolt.browser.util.Ui.tap(mini);
                mini.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start();
                mini.setElevation(Fx.dp(this, 6));
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** a MOVE while a mini drag is armed: past the slop the card rides the
     *  finger (1:1 from the lift point) and the siblings glide to make
     *  the gap where the finger is; before it nothing moves (a hesitant
     *  hold stays a menu hold). The insertion target counts the siblings
     *  whose BASE center sits before the finger — live translations never
     *  feed the math (feedback loops). */
    protected void miniDragMove(float rawX, float rawY) {
        if (miniDragTab == null || miniDragView == null) return;
        if (!miniDragMoved) {
            if (Math.abs(rawX - miniLiftX) < Fx.dp(this, 8)
                    && Math.abs(rawY - miniLiftY) < Fx.dp(this, 8)) return;
            miniDragMoved = true;
        }
        miniDragView.setTranslationX(rawX - miniLiftX);
        miniDragView.setTranslationY((rawY - miniLiftY) * 0.35f);   // mostly horizontal
        // the insertion target: how many siblings' base centers the finger passed
        int t = 0;
        for (int i = 0; i < miniDragStrip.getChildCount(); i++) {
            if (i == miniDragFrom) continue;
            if (rawX > miniBaseX[i]) t++;
        }
        if (t != miniDragTo) {
            miniDragTo = t;
            // siblings glide into their would-be slots
            for (int i = 0; i < miniDragStrip.getChildCount(); i++) {
                View c = miniDragStrip.getChildAt(i);
                if (i == miniDragFrom) continue;
                int oj = i < miniDragFrom ? i : i - 1;         // without-dragged index
                int wj = oj < t ? oj : oj + 1;                 // after inserting at t
                c.animate().translationX((wj - i) * (float) miniPitch)
                        .setDuration(120).start();
            }
        }
        // edge auto-scroll: the strip can hold more members than the row shows
        try {
            android.view.ViewParent hp = miniDragStrip.getParent();
            if (hp instanceof android.widget.HorizontalScrollView) {
                View hsv = (View) hp;
                int[] hl = new int[2];
                hsv.getLocationInWindow(hl);
                int edge = Fx.dp(this, 28);
                if (rawX < hl[0] + edge) hsv.scrollBy(-Fx.dp(this, 10), 0);
                else if (rawX > hl[0] + hsv.getWidth() - edge) hsv.scrollBy(Fx.dp(this, 10), 0);
            }
        } catch (Throwable ignored) {}
    }

    /** the drop. Never moved → the member menu (the old long-click). Moved
     *  → tm.reorderGroupMember(gid, from, to) and the row re-renders in the
     *  new order; a no-op drop (to == from) just settles everything back. */
    protected void miniDragEnd() {
        final Tab t = miniDragTab;
        final long gid = miniDragGid;
        final int from = miniDragFrom, to = miniDragTo;
        final boolean moved = miniDragMoved;
        final View v = miniDragView;
        final ViewGroup strip = miniDragStrip;
        // disarm FIRST — the reload below rebinds rows, and any late-queued
        // event must find a dead drag
        miniDragTab = null;
        miniDragView = null;
        miniDragStrip = null;
        miniDragFrom = miniDragTo = -1;
        miniH.removeCallbacksAndMessages(null);
        // v1.4.5 — the gesture ends: hand the touch stream back to the
        // parents (the interception law; the strip must scroll again)
        ownTouchStream(v, false);
        try {
            if (v != null) {
                v.animate().translationX(0).translationY(0)
                        .scaleX(1f).scaleY(1f).setDuration(moved ? 160 : 80).start();
                v.setElevation(0f);
            }
            if (strip != null) {
                for (int i = 0; i < strip.getChildCount(); i++)
                    strip.getChildAt(i).animate().translationX(0).setDuration(160).start();
            }
        } catch (Throwable ignored) {}
        if (t == null) return;
        if (!moved) {
            // the hold-and-release: the member menu (the old long-click door)
            runOnUiThread(() -> { try { tabOptions(t, gid); } catch (Throwable ignored) {} });
            return;
        }
        if (to != from && to >= 0) {
            if (tm.reorderGroupMember(gid, from, to)) {
                if (deferIfBusy(() -> adapter.reload())) return;
                adapter.reload();
            }
        }
    }

    /** wire ONE mini card for the in-group drag: the touch listener owns the
     *  whole gesture (tap / hold-menu / hold-drag — the mini's old long-click
     *  listener is retired, its click listener stays for taps; the close
     *  button keeps its own). THE LIFT-ORIGIN LAW: the drag starts from the
     *  finger's position AT LIFT TIME (tracked in miniLastX/Y on every MOVE)
     *  — capturing the DOWN coords would fling the card by the hold drift. */
    protected void wireMiniDrag(View mini, ViewGroup strip, Tab t, long gid, int memberIdx) {
        mini.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (selMode || tm.tabsOfGroup(gid).size() < 2) return false; // plain click path
                    miniDownX = miniLastX = e.getRawX();
                    miniDownY = miniLastY = e.getRawY();
                    miniHeld = false;
                    miniLiftCandidate = v;
                    miniH.removeCallbacksAndMessages(null);
                    final ViewGroup s = strip;
                    final Tab tt = t;
                    final long g = gid;
                    final int mi = memberIdx;
                    miniLiftRun = () -> {
                        if (miniLiftCandidate == v && miniDragTab == null) {
                            miniHeld = true;
                            miniLiftX = miniLastX;
                            miniLiftY = miniLastY;
                            armMiniDrag(v, s, tt, g, mi);
                        }
                    };
                    miniH.postDelayed(miniLiftRun, 380);
                    return false;      // let the click machinery run until the lift
                case android.view.MotionEvent.ACTION_MOVE:
                    miniLastX = e.getRawX();
                    miniLastY = e.getRawY();
                    if (miniDragTab != null && v == miniDragView) {
                        miniDragMove(e.getRawX(), e.getRawY());
                        return true;
                    }
                    if (!miniHeld && (Math.abs(e.getRawX() - miniDownX) > Fx.dp(this, 8)
                            || Math.abs(e.getRawY() - miniDownY) > Fx.dp(this, 8))) {
                        miniH.removeCallbacksAndMessages(null);   // a scroll/fling, not a hold
                    }
                    return false;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    miniH.removeCallbacksAndMessages(null);
                    miniLiftCandidate = null;
                    if (miniDragTab != null && v == miniDragView) {
                        miniDragEnd();
                        return true;
                    }
                    if (miniHeld) {     // lifted but never moved: the member menu
                        miniHeld = false;
                        // v1.4.5 — the lift armed the stream (a hold that
                        // ends in the menu must hand it back too)
                        ownTouchStream(v, false);
                        try {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                        } catch (Throwable ignored) {}
                        runOnUiThread(() -> { try { tabOptions(t, gid); } catch (Throwable ignored) {} });
                        return true;    // consume: this was a hold, not a tap
                    }
                    return false;
                default:
                    return false;
            }
        });
    }

    /** v1.4.4 — THE MOVE-UP/DOWN ANCHOR (the dead-menu corpse): the old
     *  items reordered the GROUPS ARRAY, but since v1.4.3 both placements
     *  render folder order from tm.tabs first-member positions — an
     *  array-only move was VISUALLY INERT (the v1.4.4 report read as "tabs
     *  are not manually adjustable"). The move now resolves the folder's
     *  RENDERED neighbour row and block-moves the members against it
     *  (tm.moveGroupRelative — the same law the drag rides): in FOLLOW-TAB-
     *  ORDER the neighbour is whatever row sits above/below (folder OR
     *  loose tab); in ALWAYS-ON-TOP only another FOLDER row can anchor
     *  (that placement's contract: folders pinned above the loose tabs).
     *  Returns null when no anchor exists (the menu hides the item). */
    protected Tab groupMoveAnchor(long gid, boolean up) {
        try {
            int rowIdx = -1;
            for (int i = 0; i < adapter.items.size(); i++) {
                Object o = adapter.items.get(i);
                if (o instanceof JSONObject && ((JSONObject) o).optLong("id") == gid) {
                    rowIdx = i;
                    break;
                }
            }
            if (rowIdx < 0) return null;
            for (int p = rowIdx + (up ? -1 : 1); p >= 0 && p < adapter.items.size();
                    p += up ? -1 : 1) {
                Object o = adapter.items.get(p);
                if (o instanceof JSONObject) {
                    long og = ((JSONObject) o).optLong("id");
                    if (og == gid) continue;
                    List<Tab> host = tm.tabsOfGroup(og);
                    if (host.isEmpty()) continue;      // memberless folder can't anchor
                    return up ? host.get(0) : host.get(host.size() - 1);
                } else if (o instanceof Tab && !prefs.groupsOnTop()) {
                    Tab t = (Tab) o;
                    if (t.groupId != 0 || t.groupId == gid) continue;
                    return t;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * v1.4.12 — THE GROUP FOLDER SHEET. The v1.3.9 inline expansion unrolled
     * a group's members into a horizontal mini-strip inside the grid row;
     * past two tabs that strip squished the minis unreadable. Opening the
     * folder now shows a VERTICALLY SCROLLABLE bottom sheet (Ui.showSheet —
     * the app's standard sheet, grab handle + rounded top + night veil):
     * one full-width row per member tab — thumbnail (the same logo tile the
     * cards and old minis wear), title, domain, and the visible ⋯ member
     * menu (tabOptions: Edit tab / Remove from group / Move to group /
     * Close tab — v1.4.9's indicated door survives). Tap a row = switch to
     * that tab and leave the switcher, exactly like tapping a card.
     *
     * v1.4.14 — THE DRAG IS BACK. v1.4.12 replaced the old inline strip's
     * hold-drag with a long-press "Move up / Move down" chooser (the sheet
     * was a ScrollView of rows, so an ItemTouchHelper drag could not live
     * there). The member list is now a RecyclerView inside the sheet
     * (Ui.showSheetView — no ScrollView wrapper: nested scrolling would
     * steal the touch stream) with an ItemTouchHelper.SimpleCallback:
     * long-press a row, drag; every crossed neighbour commits through
     * tm.reorderGroupMember (the v1.4.4 in-group reorder law, same
     * member-indexed backend the buttons used) and notifyItemMoved glides
     * the swap. The "Move up / Move down" buttons are gone — the drag is
     * the door again.
     */
    /** v1.4.12 — the sheet reference so a member move can rebuild it. */
    protected Dialog groupSheet;

    /** v1.4.14 — the group sheet's member adapter (drag reorders this list
     *  in lockstep with tm.reorderGroupMember). */
    protected class GroupSheetAdapter extends RecyclerView.Adapter<GroupSheetAdapter.H> {
        final List<Tab> members;
        final JSONObject group;
        GroupSheetAdapter(List<Tab> members, JSONObject group) {
            this.members = members;
            this.group = group;
        }
        @Override public int getItemCount() { return members.size(); }
        @Override public GroupSheetAdapter.H onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            // v1.4.16 — THE ROW WIDTH LAW (the "3-dot menu sits in the
            // middle" corpse): the row was created with NO LayoutParams,
            // so RecyclerView's generateDefaultLayoutParams handed it
            // WRAP_CONTENT width — and a wrap-width row makes the text
            // column's weight=1 meaningless (there is no leftover space to
            // divide), leaving the ⋯ menu glued to the short text instead
            // of docked at the row's end. MATCH_PARENT width + WRAP height
            // is the contract the weight needs; RecyclerView converts the
            // LinearLayout.LayoutParams on addView, preserving the sizes.
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Fx.dp(TabsActivity.this, 56));
            int ph = Fx.dp(TabsActivity.this, 12), pv = Fx.dp(TabsActivity.this, 8);
            row.setPadding(ph, pv, ph, pv);
            row.setBackground(androidx.core.content.ContextCompat.getDrawable(
                    TabsActivity.this, R.drawable.bg_item));
            // thumbnail (the same logo tile the cards wear)
            ImageView thumb = new ImageView(parent.getContext());
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    Fx.dp(TabsActivity.this, 40), Fx.dp(TabsActivity.this, 40));
            tlp.rightMargin = Fx.dp(TabsActivity.this, 14);
            row.addView(thumb, tlp);
            LinearLayout col = new LinearLayout(parent.getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(parent.getContext());
            title.setTextSize(16);
            title.setTextColor(Fx.txt(TabsActivity.this));
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            col.addView(title, new LinearLayout.LayoutParams(-1, -2));
            TextView dom = new TextView(parent.getContext());
            dom.setTextSize(12);
            dom.setTextColor(Fx.dim(TabsActivity.this));
            dom.setSingleLine(true);
            col.addView(dom, new LinearLayout.LayoutParams(-1, -2));
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            // the ⋯ member menu (v1.4.9's indicated door — survives the drag)
            ImageButton more = new ImageButton(parent.getContext());
            more.setImageResource(R.drawable.ic_more);
            more.setBackground(androidx.core.content.ContextCompat.getDrawable(
                    TabsActivity.this, R.drawable.bg_tab_btn));
            more.setPadding(Fx.dp(TabsActivity.this, 6), Fx.dp(TabsActivity.this, 6),
                    Fx.dp(TabsActivity.this, 6), Fx.dp(TabsActivity.this, 6));
            more.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            more.setColorFilter(Fx.dim(TabsActivity.this));
            row.addView(more, new LinearLayout.LayoutParams(
                    Fx.dp(TabsActivity.this, 34), Fx.dp(TabsActivity.this, 34)));
            return new H(row, thumb, title, dom, more);
        }
        @Override public void onBindViewHolder(GroupSheetAdapter.H h, int position) {
            final Tab tab = members.get(position);
            try {
                if (tab.isHome) h.thumb.setImageResource(R.drawable.ic_home);
                else {
                    String mh = Fx.hostOf(tab.url);
                    h.thumb.setImageDrawable(com.bolt.browser.util.FaviconStore.avatar(
                            TabsActivity.this, mh,
                            tab.title == null || tab.title.isEmpty() ? mh : tab.title, 36));
                }
            } catch (Throwable ignored) {}
            h.title.setText(tab.isHome ? getString(R.string.home)
                    : (tab.title == null || tab.title.isEmpty() ? tab.url : tab.title));
            h.dom.setText(tab.isHome ? "" : Fx.hostOf(tab.url));
            h.more.setOnClickListener(v -> {
                Ui.tap(v);
                try { if (groupSheet != null) groupSheet.dismiss(); }
                catch (Throwable ignored) {}
                tabOptions(tab, group.optLong("id"));
            });
            h.itemView.setOnClickListener(v -> {
                Ui.tap(v);
                if (selMode) { toggleSel(tab); return; }
                int idx = tm.tabs.indexOf(tab);
                if (idx >= 0) {
                    tm.active = idx;
                    try { if (groupSheet != null) groupSheet.dismiss(); }
                    catch (Throwable ignored) {}
                    finish();
                }
            });
        }
        class H extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView title, dom;
            final ImageButton more;
            H(View v, ImageView thumb, TextView title, TextView dom, ImageButton more) {
                super(v);
                this.thumb = thumb;
                this.title = title;
                this.dom = dom;
                this.more = more;
            }
        }
    }

    protected void openGroupSheet(final JSONObject g) {
        final long gid = g.optLong("id");
        String name = g.optString("name", "Group");
        // tabsOfGroup returns a fresh list and holds the monitor internally
        // (TabManager, the v1.4.9 law) — no extra wrap needed here
        List<Tab> groupTabs = new ArrayList<>(tm.tabsOfGroup(gid));
        if (groupTabs.isEmpty()) { Fx.toast(this, getString(R.string.group_name)); return; }
        LinearLayout body = Ui.sheetBody(this);
        TextView t = new TextView(this);
        t.setText(name + "  ·  " + groupTabs.size());
        t.setTextSize(18);
        t.setTypeface(Ui.medium());
        t.setTextColor(Fx.txt(this));
        t.setPadding(Fx.dp(this, 4), Fx.dp(this, 12), Fx.dp(this, 4), Fx.dp(this, 6));
        body.addView(t, new LinearLayout.LayoutParams(-1, -2));
        // v1.4.14 — the member list is a RecyclerView now (the drag door).
        // Height is bounded so a big folder cannot grow the sheet past the
        // screen: rows are ~64dp; anything taller than 62% of the screen
        // scrolls inside the recycler instead.
        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final GroupSheetAdapter ga = new GroupSheetAdapter(groupTabs, g);
        list.setAdapter(ga);
        int cap = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        int want = Fx.dp(this, 64) * groupTabs.size() + Fx.dp(this, 24);
        body.addView(list, new LinearLayout.LayoutParams(-1, Math.min(want, cap)));
        // THE DRAG (v1.4.14): SimpleCallback with drag-only movement flags —
        // makeMovementFlags(UP | DOWN, 0), no swipe. Every crossed neighbour
        // commits immediately through tm.reorderGroupMember (member-indexed,
        // exactly the backend the Move up/down buttons drove) and
        // notifyItemMoved glides the row; committing per swap (not at drop)
        // means even an interrupted drag leaves a consistent order behind.
        ItemTouchHelper.SimpleCallback drag = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }
            @Override
            public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder a,
                    RecyclerView.ViewHolder b) {
                int from = a.getBindingAdapterPosition(), to = b.getBindingAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION
                        || from == to) return false;
                if (!tm.reorderGroupMember(gid, from, to)) return false;
                groupTabs.add(to, groupTabs.remove(from));
                ga.notifyItemMoved(from, to);
                return true;
            }
            @Override
            public void onSwiped(RecyclerView.ViewHolder vh, int dir) { /* no swipe */ }
            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder vh, int actionState) {
                super.onSelectedChanged(vh, actionState);
                // the held row lifts: elevation + a dim, so the drag reads
                if (vh != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    vh.itemView.setElevation(Fx.dp(TabsActivity.this, 6));
                    vh.itemView.setAlpha(0.92f);
                }
            }
            @Override
            public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setElevation(0f);
                vh.itemView.setAlpha(1f);
                // the switcher grid behind re-renders the folder's member
                // order (the v1.3.47 law: adapter mutations defer when the
                // RV is busy — adapter.reload() is already that shape)
                try { adapter.reload(); } catch (Throwable ignored) {}
            }
        };
        new ItemTouchHelper(drag).attachToRecyclerView(list);
        groupSheet = Ui.showSheetView(this, body);
    }

    protected void groupOptions(final JSONObject g) {
        final long gid = g.optLong("id");
        // v1.3.40 (round-4 #11): groups can be REORDERED from their own
        // menu (Move up / Move down — hidden at the list edges).
        // v1.4.4: the move targets the RENDERED neighbour (see
        // groupMoveAnchor) — the old array-only move did nothing since the
        // v1.4.3 one-memory law made both placements derive from tm.tabs.
        final Tab upAnchor = groupMoveAnchor(gid, true);
        final Tab downAnchor = groupMoveAnchor(gid, false);
        List<String> opts = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        opts.add(getString(R.string.rename_group));
        acts.add(() -> { });
        opts.add(getString(R.string.group_color));
        acts.add(() -> { });
        opts.add(getString(R.string.delete_group));
        acts.add(() -> { });
        if (upAnchor != null) {
            opts.add("Move up");
            acts.add(() -> {
                try { tm.refreshAnchorsAround("g" + gid); } catch (Throwable ignored) {}   // v1.4.8: the row moves — empties never follow
                if (tm.moveGroupRelative(gid, upAnchor, false)) adapter.reload();
            });
        }
        if (downAnchor != null) {
            opts.add("Move down");
            acts.add(() -> {
                try { tm.refreshAnchorsAround("g" + gid); } catch (Throwable ignored) {}   // v1.4.8
                if (tm.moveGroupRelative(gid, downAnchor, true)) adapter.reload();
            });
        }
        new AlertDialog.Builder(this)
                .setTitle(g.optString("name"))
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        final EditText et = new EditText(TabsActivity.this);
                        et.setText(g.optString("name"));
                        new AlertDialog.Builder(TabsActivity.this)
                                .setTitle(R.string.rename_group)
                                .setView(et)
                                .setPositiveButton(R.string.ok, (d2, w2) -> {
                                    prefs.renameGroup(gid, et.getText().toString().trim());
                                    adapter.reload();
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    } else if (w == 1) {
                        String[] names = getResources().getStringArray(R.array.group_color_names);
                        new AlertDialog.Builder(TabsActivity.this)
                                .setTitle(R.string.group_color)
                                .setItems(names, (d2, w2) -> {
                                    prefs.setGroupColor(gid, w2);
                                    adapter.reload();
                                })
                                .show();
                    } else if (w == 2) {
                        // v1.4.8 — the folder's ROW departs (members go
                        // loose, the folder entry is removed): empties
                        // anchored to it re-anchor to its old neighbours
                        try { tm.refreshAnchorsAround("g" + gid); } catch (Throwable ignored) {}
                        synchronized (tm.tabs) {   // v1.4.11: the thread law — iteration under the monitor
                            for (Tab t : tm.tabs) if (t.groupId == gid) t.groupId = 0;
                        }
                        prefs.removeGroup(gid);
                        tm.save();
                        adapter.reload();
                    } else {
                        acts.get(w).run();
                    }
                })
                .show();
    }

    protected String groupName(long gid) {
        try {
            for (int i = 0; i < prefs.groups().length(); i++) {
                JSONObject g = prefs.groups().optJSONObject(i);
                if (g != null && g.optLong("id") == gid) return g.optString("name");
            }
        } catch (Throwable ignored) {}
        return "Group";
    }

    protected static class CardHolder extends RecyclerView.ViewHolder {
        ImageView thumb;
        TextView title, domain;
        ImageButton close, menu;
        CardHolder(View v) {
            super(v);
            thumb = v.findViewById(R.id.iv_thumb);
            title = v.findViewById(R.id.tv_tab_title);
            domain = v.findViewById(R.id.tv_tab_domain);
            close = v.findViewById(R.id.btn_tab_close);
            menu = v.findViewById(R.id.btn_tab_menu) != null
                    ? (ImageButton) v.findViewById(R.id.btn_tab_menu) : null;
        }
    }

    protected static class GroupHolder extends RecyclerView.ViewHolder {
        View dot;
        TextView name, count;
        ImageButton menu;
        LinearLayout cards;
        View chevron, cardsWrap, header;
        View strip;   // v1.4.16: the grouped-active accent strip (v_group_strip)
        GroupHolder(View v) {
            super(v);
            dot = v.findViewById(R.id.v_group_dot);
            name = v.findViewById(R.id.tv_group_name);
            count = v.findViewById(R.id.tv_group_count);
            menu = v.findViewById(R.id.btn_group_menu);
            cards = v.findViewById(R.id.ll_group_cards);
            cardsWrap = v.findViewById(R.id.hv_group_cards) != null
                    ? v.findViewById(R.id.hv_group_cards) : (View) cards.getParent();
            chevron = v.findViewById(R.id.iv_group_chevron);
            header = v.findViewById(R.id.ll_group_header);
            strip = v.findViewById(R.id.v_group_strip);
        }
    }
}
