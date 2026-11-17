package com.bolt.browser.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;

/**
 * v1.3.17 — the content pane that hosts the attached tab's WebView.
 *
 * Background playback up to v1.3.16 was REACTIVE: leaving the app hides the
 * activity's window, Chromium flips the renderer to hidden, document.hidden
 * becomes true, the page (YouTube et al.) pauses itself — and our media
 * service then resurrected it hundreds of milliseconds to seconds later.
 * That resurrection delay is the audible rhythm-breaking gap when the app
 * goes to the background, and when the resurrection missed (bounded guard),
 * playback simply never started. Worse, while the page sits in the hidden
 * state a later notification PLAY is a programmatic play() that hidden-state
 * players ignore — the "tap play in the notification, nothing happens, even
 * repeatedly" bug. Both bugs are one root cause: the page learning it is
 * hidden at all.
 *
 * This pane makes the fix PREVENTIVE. While the spoof is engaged (set by
 * BrowserActivity.onPause() when background play is on and any tab holds
 * loaded media), window-visibility changes are re-dispatched to this subtree
 * as VISIBLE: Chromium never learns the window went away, document stays
 * 'visible', the page never pauses — the in-app → background handoff is
 * seamless (no gap at all, nothing to resurrect), and a later notification
 * PLAY lands on a fully-live, fully-"visible" page that accepts it.
 *
 * Technique proven by alexsch01/YTWebView (r/androiddev "SOLVED" thread):
 * intercept dispatchWindowVisibilityChanged() on the WebView's PARENT —
 * overriding onWindowVisibilityChanged() on the WebView itself does not
 * reliably reach the renderer, intercepting the parent's dispatch does.
 *
 * Scope notes:
 *  - engaged ONLY while leaving the foreground with loaded media + bgPlay on;
 *    released at onResume(). A no-media backgrounding reports GONE normally
 *    (renderer throttles, battery saved).
 *  - views OUTSIDE this pane (toolbars etc.) still get the real visibility —
 *    nothing else in the UI is lied to.
 *  - the in-page MediaHook spoof keys off the REAL document.hidden, which now
 *    stays false — the JS layer sleeps as a pure backstop. No conflict.
 *  - zombie playback (app closed, WebViews detached) is a different path and
 *    is untouched.
 *
 * v1.3.29 — CONCEALMENT. The always-lie above is the reason background
 * playback cost so much: a renderer Chromium believes is visible gets NONE
 * of Chromium's background machinery — video decode runs at full rate for an
 * invisible surface, page JS timers never throttle, the renderer's caches
 * never purge (the exact RAM that keeps a 3 GB device thrashing and lmkd
 * shooting perceptible apps). 15 s after the app backgrounded (or the screen
 * went off) with media playing, MediaControlService calls concealNow(): the
 * pane stops lying for ONE dispatch and tells the subtree the truth (GONE)
 * exactly once. Chromium re-engages decode suspension / timer throttling /
 * cache purge, while the in-page JS spoof (the v1.3.17 "backstop") takes
 * over the page-level lie so the player never pauses. A bounded JS sentinel
 * auto-resumes any non-user pause the flip provokes; if it loses, the page
 * posts 'concealfail' and the service un-conceals + latches concealment off
 * for the session (exactly the v1.3.28 always-lie behavior restored).
 * unconcealNow() also runs on a user pause while backgrounded (the
 * notification PLAY must land on a live "visible" page — v1.3.17 Bug B) and
 * on foreground return (where the system's own VISIBLE dispatch takes over
 * anyway; the extra dispatch is idempotent).
 */
public class MediaAwareContent extends FrameLayout
        implements androidx.core.view.NestedScrollingParent3 {
    private static volatile boolean spoof = false;
    /** v1.3.29: while true, GONE dispatches pass through to the subtree. */
    private static volatile boolean concealed = false;
    /** the most recently attached pane — concealment targets it. */
    private static volatile MediaAwareContent livePane = null;

    /** BrowserActivity.onPause(): call BEFORE super — the window can be
     *  reported hidden as soon as pausing completes. */
    public static void engageSpoof() { spoof = true; }

    /** BrowserActivity.onResume(): a real VISIBLE dispatch passes through the
     *  filter unchanged, so releasing before/after super is equally safe. */
    public static void releaseSpoof() { spoof = false; }

    public static boolean spoofEngaged() { return spoof; }

    public static boolean concealed() { return concealed; }

    /** v1.3.29 — stop lying, once: the next visibility dispatch reaches the
     *  renderer with the truth, so Chromium's background optimizations
     *  (video-decode suspension, timer throttling, memory purge) engage
     *  while the JS spoof keeps the page itself believing it is visible.
     *  Must be called on the UI thread (it dispatches into the view tree).
     *  Returns false when no live pane exists (zombie playback / activity
     *  destroyed) — the caller treats that as "nothing to conceal". */
    public static boolean concealNow() {
        MediaAwareContent p = livePane;
        if (p == null) return false;
        concealed = true;
        try { p.dispatchWindowVisibilityChanged(View.GONE); } catch (Throwable ignored) { }
        return true;
    }

    /** v1.3.29 — restore the lie (or let the real VISIBLE through). Called on
     *  rollback ('concealfail'), user pause while backgrounded, foreground
     *  return and playback retirement. Idempotent; safe without a pane. */
    public static void unconcealNow() {
        if (!concealed) return;
        concealed = false;
        MediaAwareContent p = livePane;
        if (p == null) return;
        try { p.dispatchWindowVisibilityChanged(View.VISIBLE); } catch (Throwable ignored) { }
    }

    public MediaAwareContent(Context c) { super(c); initPull(); }
    public MediaAwareContent(Context c, AttributeSet a) { super(c, a); initPull(); }
    public MediaAwareContent(Context c, AttributeSet a, int defStyle) { super(c, a, defStyle); initPull(); }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        livePane = this;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (livePane == this) livePane = null;
        super.onDetachedFromWindow();
    }

    @Override
    public void dispatchWindowVisibilityChanged(int visibility) {
        if (spoof && !concealed && visibility != View.VISIBLE) {
            // lie to this subtree only: the WebView (and thus Chromium) keeps
            // reporting "visible"; the page never sees a visibilitychange
            super.dispatchWindowVisibilityChanged(View.VISIBLE);
        } else {
            super.dispatchWindowVisibilityChanged(visibility);
        }
    }

    // ================================================================ v1.3.35 PULL-TO-REFRESH
    //
    // Hand-rolled on the NestedScrollingParent API (the way SwipeRefreshLayout
    // does it) so it stays correct next to inner scrollers: the WebView
    // dispatches nested scroll events while its Chromium-side scrolling runs;
    // when it is ALREADY AT THE TOP and the finger keeps pulling down, the
    // unconsumed negative dy lands here and becomes the pull. No dependency,
    // no touch-event guessing, no interference with page gestures (horizontal
    // swipes, pinch zoom, inner lists — they consume first and never reach
    // the pull accumulator).
    //
    // The spinner is drawn in dispatchDraw (an arc that fills with the pull
    // and spins while refreshing) — no extra child views to manage.

    /** pull distance at which releasing triggers a refresh */
    private float pullTrigger;
    /** current pull distance in px (>= 0) */
    private float pull;
    /** a refresh is running (spinner spins; setRefreshing(false) ends it) */
    private boolean refreshing;
    /** gate (Settings → Appearance → Interaction) */
    private boolean pullEnabled = true;
    private Runnable onRefresh;
    /** continuous spin animator while refreshing */
    private ValueAnimator spin;
    /** spinner rotation in degrees */
    private float spinAngle;
    private final Paint spinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void initPull() {
        pullTrigger = dp(76);
        spinPaint.setStyle(Paint.Style.STROKE);
        spinPaint.setStrokeWidth(dp(3));
        spinPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private float dp(int v) {
        return v * getResources().getDisplayMetrics().density + 0.5f;
    }

    public void setPullEnabled(boolean on) { pullEnabled = on; }

    public void setOnRefreshListener(Runnable r) { onRefresh = r; }

    /** call when the refresh completes (typically onPageFinished). */
    public void setRefreshing(boolean on) {
        if (refreshing == on) return;
        refreshing = on;
        if (on) {
            if (spin != null) spin.cancel();
            spin = ValueAnimator.ofFloat(0f, 360f);
            spin.setDuration(900);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setRepeatMode(ValueAnimator.RESTART);
            spin.setInterpolator(new android.view.animation.LinearInterpolator());
            spin.addUpdateListener(a -> {
                spinAngle = (float) a.getAnimatedValue();
                invalidate();
            });
            spin.start();
        } else {
            if (spin != null) { spin.cancel(); spin = null; }
            animatePullTo(0f);
        }
    }

    private void animatePullTo(float target) {
        ValueAnimator a = ValueAnimator.ofFloat(pull, target);
        a.setDuration(180);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        a.addUpdateListener(an -> {
            pull = (float) an.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    // ---------------- nested scrolling contract ----------------
    // (NestedScrollingParent3 requires the full Parent1 family too — the
    // legacy entry points just delegate to the typed ones / no-op.)

    /** legacy Parent1: dispatch through the typed variant. */
    @Override
    public boolean onStartNestedScroll(View child, View target, int axes) {
        return onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH);
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int axes) {}

    @Override
    public void onStopNestedScroll(View target) {
        onStopNestedScroll(target, ViewCompat.TYPE_TOUCH);
    }

    /** legacy Parent1 scroll dispatch (touch only). */
    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed) {
        handlePullScroll(dyConsumed, dyUnconsumed);
    }

    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH);
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
        return false;
    }

    @Override
    public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public int getNestedScrollAxes() { return 0; }

    // ---- Parent2/3 typed variants (what a modern WebView dispatches) ----

    @Override
    public boolean onStartNestedScroll(View child, View target, int axes, int type) {
        return pullEnabled && (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int axes, int type) {}

    @Override
    public void onStopNestedScroll(View target, int type) {
        if (refreshing) return;
        if (pull >= pullTrigger) {
            setRefreshing(true);
            pull = pullTrigger;
            invalidate();
            if (onRefresh != null) onRefresh.run();
        } else if (pull > 0) {
            animatePullTo(0f);
        }
    }

    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed, int type) {
        // scrolling back UP shrinks a visible pull first
        if (dy > 0 && pull > 0 && !refreshing) {
            float eat = Math.min(pull, dy);
            pull -= eat;
            consumed[1] = (int) eat;
            invalidate();
        }
    }

    /** Parent2's 6-arg onNestedScroll. */
    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed, int type) {
        handlePullScroll(dyConsumed, dyUnconsumed);
    }

    /** Parent3's 7-arg onNestedScroll — what Chromium WebView dispatches. */
    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed, int type, int[] consumed) {
        handlePullScroll(dyConsumed, dyUnconsumed);
    }

    /** the pull signal: at the top, pulling DOWN = unconsumed negative dy. */
    private void handlePullScroll(int dyConsumed, int dyUnconsumed) {
        if (!refreshing && dyConsumed == 0 && dyUnconsumed < 0) {
            pull = Math.min(pullTrigger * 2f, pull - dyUnconsumed);
            invalidate();
        }
    }

    // ---------------- spinner drawing ----------------

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (pull <= 0 && !refreshing) return;
        try {
            float w = getWidth();
            int accent = com.bolt.browser.util.Fx.accent(getContext());
            spinPaint.setColor(accent);
            float r = dp(11);
            // reveal: the spinner slides out from UNDER the floating top bar
            // (the bar overlays this pane — a spinner at y<58dp would be hidden)
            float ratio = Math.min(1f, pull / pullTrigger);
            float cy = refreshing ? dp(92) : dp(58) + ratio * dp(36);
            float cx = w / 2f;
            android.graphics.RectF oval = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
            // track
            spinPaint.setAlpha(48);
            canvas.drawArc(oval, 0, 360, false, spinPaint);
            // progress arc: sweep grows with the pull; full + rotating while refreshing
            spinPaint.setAlpha(255);
            if (refreshing) {
                canvas.drawArc(oval, spinAngle, 300, false, spinPaint);
            } else {
                float sweep = 30 + 300 * ratio;
                canvas.drawArc(oval, -90 + 360 * ratio, sweep, false, spinPaint);
            }
        } catch (Throwable ignored) {}
    }
}
