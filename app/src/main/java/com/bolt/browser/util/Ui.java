package com.bolt.browser.util;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bolt.browser.R;

/**
 * v1.3.34 — THE MODERN COMPONENT KIT.
 *
 * Field report on v1.3.33: "the consistency does not flow to everywhere in the
 * app, like the tabs, deep settings, random pop up menues, they all lack
 * polish and look early 2000 era windows xp style."
 *
 * Root causes (what actually made it look XP-era — all fixed here):
 *  1. {@code android.widget.Switch} — the Holo-era control (thin track, tiny
 *     thumb, gray/green) was used everywhere. This kit's ModernSwitch is a
 *     hand-drawn Material-3-style switch: 44x26 pill track, thumb that grows
 *     16→22dp, animated, runtime-accent aware.
 *  2. Bare 22dp gray icons floating next to text. Every modern settings UI
 *     (Google Settings, LibreTube, MJ PDF) puts icons in soft tinted
 *     containers — {@link #iconContainer}.
 *  3. Values rendered as plain colored text → now pill chips
 *     ({@link #valueChip}).
 *  4. Choice dialogs = default AppCompat radio lists → now bottom sheets
 *     with grab handles and accent radio dots ({@link #choiceSheet}).
 *  5. Bold-everything typography → Google-style: sans-serif-medium titles,
 *     regular body, 22sp screen headers ({@link #medium()}).
 *
 * Everything resolves the SAME theme attrs the XML uses (via Fx), so all six
 * skins stay consistent — glass (Aura) gets translucent fills + hairline rims
 * automatically where it matters.
 */
public class Ui {

    private Ui() {}

    private static Typeface sMedium;

    /** The Google-apps look without bundling a font: the platform's
     *  medium-weight family (Roboto Medium / OEM equivalent). */
    public static Typeface medium() {
        if (sMedium == null) sMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return sMedium;
    }

    /** Light skins get elevation shadows (they read on light backgrounds);
     *  dark skins rely on surface contrast (shadows are invisible there),
     *  glass relies on its rim. */
    public static boolean wantShadows(Context c) {
        int s = Fx.skinStyle(c);
        if (s == 3) return true;                 // Origins (warm light)
        return s == 0 && !Prefs.get(c).isDark(); // Classic light
    }

    // ---------------------------------------------------------------- modern switch

    /** callback for ModernSwitch */
    public interface OnToggle { void onToggle(boolean on); }

    /**
     * A Material-3-style switch drawn entirely in code (no drawable sizing
     * pitfalls, runtime-accent aware, animated). 44x26dp pill track, thumb
     * grows 16→22dp when on, ~180ms settle animation.
     */
    public static class ModernSwitch extends View {
        private boolean checked;
        private float pos;              // 0 = off, 1 = on (animated)
        private ValueAnimator anim;
        private OnToggle listener;

        public ModernSwitch(Context c) {
            super(c);
            setClickable(true);
            setContentDescription("toggle");
            // v1.3.36: light haptic tick on every toggle (pref-gated in tap)
            setOnClickListener(v -> {
                tap(v);
                toggle(true);
            });
        }

        public void setOnToggleListener(OnToggle l) { listener = l; }

        /** programmatic set WITHOUT callback (bind/restore) */
        public ModernSwitch setCheckedIm(boolean on) {
            checked = on;
            pos = on ? 1f : 0f;
            anim = null;
            invalidate();
            return this;
        }

        public boolean isChecked() { return checked; }

        public void toggle(boolean animate) {
            setChecked(!checked, animate);
            if (listener != null) listener.onToggle(checked);
        }

        /** programmatic set WITH callback */
        public void setChecked(boolean on, boolean animate) {
            checked = on;
            if (!animate || !isAttachedToWindow()) {
                pos = on ? 1f : 0f;
                invalidate();
                return;
            }
            if (anim != null) anim.cancel();
            float from = pos, to = on ? 1f : 0f;
            anim = ValueAnimator.ofFloat(from, to);
            anim.setDuration((long) (170 * (1 + Math.abs(to - from))));
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            anim.addUpdateListener(a -> {
                pos = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(Fx.dp(getContext(), 52), Fx.dp(getContext(), 30));
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight();
            float tw = Fx.dp(c, 44), th = Fx.dp(c, 26);
            float left = (w - tw) / 2f, top = (h - th) / 2f;
            RectF track = new RectF(left, top, left + tw, top + th);

            int accent = Fx.accent(c);
            int offTrack = blend(Fx.surface2(c), Fx.outline(c), 0.6f);
            int offThumb = Fx.tint(c);
            int trackCol = blend(offTrack, accent, pos);
            // v1.3.35: thumb contrast follows the ON track — a LIGHT accent
            // (Mono paper, pale wallpaper) gets a DARK thumb, or the switch
            // reads as one white pill
            int onThumb = androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.55
                    ? 0xFF101014 : Color.WHITE;
            int thumbCol = blend(offThumb, onThumb, pos);
            float thumbD = Fx.dp(c, 16) + pos * Fx.dp(c, 6);

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            // track
            p.setColor(trackCol);
            cv.drawRoundRect(track, th / 2f, th / 2f, p);
            // thumb (inset 2dp from track ends at each extreme)
            float inset = Fx.dp(c, 2);
            float x0 = left + inset, x1 = left + tw - inset - thumbD;
            float tx = x0 + (x1 - x0) * pos;
            float ty = top + (th - thumbD) / 2f;
            p.setColor(thumbCol);
            cv.drawRoundRect(new RectF(tx, ty, tx + thumbD, ty + thumbD),
                    thumbD / 2f, thumbD / 2f, p);
        }

        private static int blend(int a, int b, float t) {
            return androidx.core.graphics.ColorUtils.blendARGB(a, b, t);
        }
    }

    /** factory: a modern switch, optionally with listener + initial state */
    public static ModernSwitch modernSwitch(Context c, boolean checked, OnToggle l) {
        ModernSwitch s = new ModernSwitch(c);
        s.setCheckedIm(checked);
        s.setOnToggleListener(l);
        return s;
    }

    // ---------------------------------------------------------------- icon containers

    /**
     * v1.3.35 — the Mono "thicker monoline icons" trick: a LayerDrawable of
     * the SAME vector drawn twice, the second copy offset ~1dp down-right.
     * Where the copies overlap, the stroke doubles — an optical BOLD weight
     * with zero bundled icon assets. Applied automatically by iconify()/
     * iconContainer() on the flat skin; safe on any ImageView (the tint
     * filter applies to every layer).
     */
    public static void embolden(ImageView iv) {
        try {
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (d == null) return;
            android.graphics.drawable.Drawable base = d.mutate();
            android.graphics.drawable.Drawable copy =
                    base.getConstantState() != null ? base.getConstantState().newDrawable().mutate() : null;
            if (copy == null) return;
            android.graphics.drawable.LayerDrawable ld =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{base, copy});
            int off = Math.max(1, Fx.dp(iv.getContext(), 1));
            // layer 1 sits at (off, off), marginally smaller — all-POSITIVE
            // insets so no drawable-layering edge cases
            ld.setLayerInset(1, off, off, 0, 0);
            iv.setImageDrawable(ld);
        } catch (Throwable ignored) {}
    }

    /** the skin-aware icon treatment: embolden on the flat (Mono) skin. */
    public static void skinIcon(ImageView iv) {
        if (iv != null && Fx.isFlat(iv.getContext())) embolden(iv);
    }

    /**
     * The modern-settings icon treatment: a 40dp rounded-square container
     * (per-skin radius) with the 22dp icon inside. accentStyle=true → soft
     * accent fill + accent icon (Google Settings / LibreTube look; the
     * runtime accent applies); false → neutral surface2 fill + tint icon
     * (calmer, for menus). Glass skin gets translucent fills + hairline rim.
     * Mono gets the emboldened (thicker) icon.
     */
    public static FrameLayout iconContainer(Context c, int iconRes, boolean accentStyle) {
        int box = Fx.dp(c, 40), icon = Fx.dp(c, 22), radius = Fx.dp(c, Fx.iconRadius(c));
        FrameLayout fl = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        int fill, iconTint;
        if (accentStyle) {
            fill = Fx.accentSoft(c);
            iconTint = Fx.accentText(c);
        } else {
            fill = Fx.surface2(c);
            iconTint = Fx.tint(c);
        }
        g.setColor(fill);
        // v1.3.45: dark-glass shells carry a brighter rim (0x29 -> 0x40) so
        // the icon buttons stay outlined over the smoked glass (field:
        // "make the buttons more visible").
        if (Fx.isGlass(c))
            g.setStroke(Math.max(1, Fx.dp(c, 1)),
                    com.bolt.browser.util.Prefs.get(c).isDark() ? 0x40FFFFFF : 0x291B1B2E);
        fl.setBackground(g);
        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setColorFilter(iconTint);
        if (Fx.isFlat(c)) embolden(iv);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER);
        fl.addView(iv, lp);
        fl.setLayoutParams(new ViewGroup.LayoutParams(box, box));
        return fl;
    }

    /** restyle an XML-declared ImageView in place as an icon container
     *  (bg + padding + tint), keeping the layout around it untouched. */
    public static void iconify(ImageView iv, boolean accentStyle) {
        Context c = iv.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Fx.dp(c, Fx.iconRadius(c)));
        g.setColor(accentStyle ? Fx.accentSoft(c) : Fx.surface2(c));
        if (Fx.isGlass(c))
            g.setStroke(Math.max(1, Fx.dp(c, 1)),
                    com.bolt.browser.util.Prefs.get(c).isDark() ? 0x40FFFFFF : 0x291B1B2E);
        iv.setBackground(g);
        int pad = (int) ((Fx.dp(c, 42) - Fx.dp(c, 22)) / 2f);
        iv.setPadding(pad, pad, pad, pad);
        iv.setColorFilter(accentStyle ? Fx.accentText(c) : Fx.tint(c));
        if (Fx.isFlat(c)) embolden(iv);
    }

    // ---------------------------------------------------------------- chips & labels

    /** a value shown as a modern pill chip (soft accent bg, accent text) */
    public static TextView valueChip(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextColor(Fx.accentText(c));
        v.setTextSize(13);
        v.setTypeface(medium());
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Fx.accentSoft(c));
        g.setCornerRadius(Fx.dp(c, 999));
        v.setBackground(g);
        int h = Fx.dp(c, 10), vv = Fx.dp(c, 4);
        v.setPadding(h, vv, h, vv);
        v.setMaxWidth(Fx.dp(c, 150));
        return v;
    }

    // ---------------------------------------------------------------- v1.3.35 rows + empty states

    /**
     * The modern card row background, per-skin: ripple over a surface card
     * with a hairline outline and the SKIN'S SIGNATURE corner radius
     * (Mono sharp 8dp, Origins soft 24dp, …). Replaces the fixed-16dp
     * bg_row.xml wherever rows are styled in code.
     */
    public static RippleDrawable rowBg(Context c) {
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(Fx.dp(c, Fx.radius(c)));
        if (Fx.isGlass(c)) {
            boolean dark = Prefs.get(c).isDark();
            card.setColor(dark ? 0xE6141420 : 0xE6F7F8FC);
            card.setStroke(Math.max(1, Fx.dp(c, 1)), dark ? 0x29FFFFFF : 0x291B1B2E);
        } else {
            card.setColor(Fx.surface(c));
            card.setStroke(Math.max(1, Fx.dp(c, 1)), Fx.outline(c));
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(Fx.dp(c, Fx.radius(c)));
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(0x33888888), card, mask);
    }

    /**
     * The modern EMPTY STATE (v1.3.35's "empty-state art pass"): a big soft
     * icon medallion, a medium title, and a dim one-liner — the Google-apps
     * "nothing here yet" moment instead of a lone gray line of text.
     */
    public static LinearLayout emptyState(Context c, int iconRes, String title, String sub) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Fx.dp(c, 24), Fx.dp(c, 46), Fx.dp(c, 24), Fx.dp(c, 46));

        // 64dp medallion: soft accent circle + accent icon
        int med = Fx.dp(c, 64);
        FrameLayout fl = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Fx.accentSoft(c));
        fl.setBackground(g);
        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setColorFilter(Fx.accentText(c));
        if (Fx.isFlat(c)) embolden(iv);
        fl.addView(iv, new FrameLayout.LayoutParams(Fx.dp(c, 30), Fx.dp(c, 30), Gravity.CENTER));
        col.addView(fl, new LinearLayout.LayoutParams(med, med));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(17);
        t.setTypeface(medium());
        t.setTextColor(Fx.txt(c));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Fx.dp(c, 16);
        col.addView(t, tlp);

        if (sub != null) {
            TextView s = new TextView(c);
            s.setText(sub);
            s.setTextSize(14);
            s.setTextColor(Fx.dim(c));
            s.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = Fx.dp(c, 6);
            col.addView(s, slp);
        }
        return col;
    }

    /** grouped-settings section label (dim, medium, roomy) */
    public static TextView sectionLabel(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(14);
        v.setTypeface(medium());
        v.setTextColor(Fx.dim(c));
        v.setPadding(Fx.dp(c, 6), Fx.dp(c, 18), Fx.dp(c, 6), Fx.dp(c, 8));
        return v;
    }

    // ---------------------------------------------------------------- v1.3.36 additions

    /**
     * v1.3.36 — a light haptic tick on key interactions (switches, tab
     * closes, menu picks, the FAB). performHapticFeedback respects the
     * system-wide "touch feedback" setting, so this never buzzes for users
     * who disabled haptics globally; the in-app toggle is the second gate.
     */
    public static void tap(View v) {
        try {
            if (v == null || v.getContext() == null) return;
            if (!Prefs.get(v.getContext()).haptics()) return;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Throwable ignored) {}
    }

    /**
     * v1.3.36 — the TOP-ANCHORED sheet background (the omnibox): the mirror
     * of sheetBg — rounded BOTTOM corners, hangs from the top edge like a
     * dropdown instead of a bottom sheet.
     */
    public static GradientDrawable sheetTopBg(Context c) {
        GradientDrawable g = new GradientDrawable();
        int r = Fx.dp(c, Math.min(28, Fx.radius(c) + 4));
        g.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        if (Fx.isGlass(c)) {
            boolean dark = Prefs.get(c).isDark();
            g.setColor(dark ? 0xF2141420 : 0xF2F7F8FC);
            g.setStroke(Math.max(1, Fx.dp(c, 1)), dark ? 0x29FFFFFF : 0x291B1B2E);
        } else {
            g.setColor(Fx.surface(c));
            if (wantShadows(c)) g.setStroke(Math.max(1, Fx.dp(c, 1)), Fx.outline(c));
        }
        return g;
    }

    /** handle on a shown snackbar; dismiss() removes it early. */
    public interface Snack {
        void dismiss();
    }

    /** v1.4.6 — THE UNDO WINDOW (the user's ask: "add an undo toast for
     *  like 3-4 seconds of deleting anything"). Every undo snackbar across
     *  the app carries this one duration — tabs, shortcuts, folders,
     *  bookmarks, saved pages, watch-later — so the escape hatch feels
     *  like ONE feature, not per-surface accidents. */
    public static final long UNDO_MS = 3500L;

    /** the plain info snackbar (5 s, no expiry hook). */
    public static Snack snackbar(final Activity a,
            String message, String action, final Runnable onAction) {
        return snackbar(a, message, action, onAction, 5000L, null);
    }

    /**
     * v1.4.6 — the DURATION + EXPIRY overload: the bar leaves after [ms]
     *  on its own, and when it leaves WITHOUT the action being tapped,
     *  [onExpire] runs — the hook a soft delete needs (the trash file is
     *  only really deleted once the undo window closes). Every undo
     *  surface passes Ui.UNDO_MS.
     */
    public static Snack snackbar(final Activity a,
            String message, String action, final Runnable onAction,
            long ms, final Runnable onExpire) {
        final ViewGroup parent = (ViewGroup) a.findViewById(android.R.id.content);
        final LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int ph = Fx.dp(a, 18), pv = Fx.dp(a, 12);
        bar.setPadding(ph, pv, ph, pv);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Fx.dp(a, 999));
        // dark ink pill on light skins, elevated surface on dark — reads on
        // any content behind it either way
        if (Prefs.get(a).isDark()) {
            g.setColor(Fx.surface(a));
            g.setStroke(Math.max(1, Fx.dp(a, 1)), Fx.outline(a));
        } else {
            g.setColor(0xFF23242B);
        }
        bar.setBackground(g);
        if (wantShadows(a)) bar.setElevation(Fx.dp(a, 6));

        TextView msg = new TextView(a);
        msg.setText(message);
        msg.setTextSize(15);
        msg.setTextColor(Prefs.get(a).isDark() ? Fx.txt(a) : 0xFFEDEFF2);
        bar.addView(msg, new LinearLayout.LayoutParams(0, -2, 1f));

        final Runnable[] kill = new Runnable[1];
        final boolean[] acted = {false};   // v1.4.6: expiry only fires untapped
        if (action != null) {
            TextView act = new TextView(a);
            act.setText(action);
            act.setTextSize(15);
            act.setTypeface(medium());
            act.setTextColor(Fx.accentText(a));
            act.setPadding(Fx.dp(a, 12), 0, 0, 0);
            act.setOnClickListener(v -> {
                Ui.tap(v);
                acted[0] = true;
                if (kill[0] != null) kill[0].run();
                try { onAction.run(); } catch (Throwable ignored) {}
            });
            bar.addView(act, new LinearLayout.LayoutParams(-2, -2));
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        lp.gravity = Gravity.BOTTOM;
        lp.leftMargin = Fx.dp(a, 16);
        lp.rightMargin = Fx.dp(a, 16);
        lp.bottomMargin = Fx.dp(a, 76);   // clears the bottom bar / sheet
        bar.setTranslationY(Fx.dp(a, 40));
        bar.setAlpha(0f);
        parent.addView(bar, lp);
        bar.animate().translationY(0f).alpha(1f).setDuration(200L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        final boolean[] gone = {false};
        final Runnable remove = () -> {
            if (gone[0]) return;
            gone[0] = true;
            // v1.4.6 — THE EXPIRY HOOK: the bar leaves without the action
            // tapped = the undo window closed (or dismiss() was called):
            // the soft delete may finalize now
            if (!acted[0] && onExpire != null) {
                try { onExpire.run(); } catch (Throwable ignored) {}
            }
            bar.animate().translationY(Fx.dp(a, 40)).alpha(0f).setDuration(180L)
                    .withEndAction(() -> {
                        try { parent.removeView(bar); } catch (Throwable ignored) {}
                    }).start();
        };
        kill[0] = remove;
        bar.postDelayed(remove, ms);
        return remove::run;
    }

    /** screen-title TextView (22sp medium — the Google apps header scale) */
    public static TextView screenTitle(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(22);
        v.setTypeface(medium());
        v.setTextColor(Fx.txt(c));
        return v;
    }

    // ---------------------------------------------------------------- incognito route

    /**
     * v1.4.14 — THE INCOGNITO RETURN ROUTE (the intent-leak fix).
     *
     * The satellite screens (bookmarks, history, saved pages, downloads,
     * settings…) run in the MAIN process even when they are opened from
     * the incognito browser — the manifest gives them no android:process,
     * and the process is a property of the ACTIVITY, not of who launched
     * it. Their row taps used to fire plain
     * {@code new Intent(this, BrowserActivity.class)} VIEW intents, so a
     * bookmark opened from the incognito side loaded in the NORMAL
     * browser: normal cookie jar, normal history, zero privacy — the
     * "sometimes spawns in the normal BrowserActivity" field report.
     *
     * The fix is a stamp-and-return contract, all through these two doors:
     *  - {@link #launchSatellite}: the launcher stamps the satellite's
     *    intent with EXTRA_FROM_INCOGNITO when it itself is the incognito
     *    process (TabManager.INCOGNITO — the per-process flag) or when it
     *    is a satellite that was itself stamped (Settings → Bookmarks
     *    chains keep the stamp);
     *  - {@link #browserOpenIntent}: a stamped satellite routes its URL
     *    opens to IncognitoActivity — the same ACTION_VIEW contract
     *    BrowserActivity answers, in the :incognito process with the
     *    private data dir — instead of BrowserActivity.class.
     *
     * New tabs created INSIDE a browser process never ride intents at all
     * (BrowserActivity.openNewTab's process law); the only intents here
     * are the satellite returns, and they now land on the right side.
     */
    public static final String EXTRA_FROM_INCOGNITO = "bolt_from_incognito";

    /** launch a satellite screen, carrying this side's privacy stamp. */
    public static void launchSatellite(Activity a, Class<?> cls) {
        try {
            Intent i = new Intent(a, cls);
            boolean inc = com.bolt.browser.core.TabManager.INCOGNITO
                    || (a.getIntent() != null
                        && a.getIntent().getBooleanExtra(EXTRA_FROM_INCOGNITO, false));
            if (inc) i.putExtra(EXTRA_FROM_INCOGNITO, true);
            a.startActivity(i);
        } catch (Throwable ignored) {}
    }

    /** the VIEW intent that opens [url] — routed to the side the current
     *  screen was launched from (stamped satellites return to incognito). */
    public static Intent browserOpenIntent(Activity from, String url) {
        boolean inc = from.getIntent() != null
                && from.getIntent().getBooleanExtra(EXTRA_FROM_INCOGNITO, false);
        Intent i = new Intent(from, inc
                ? com.bolt.browser.ui.IncognitoActivity.class
                : com.bolt.browser.ui.BrowserActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.setData(Uri.parse(url));
        return i;
    }

    // ---------------------------------------------------------------- buttons

    private static final int BTN_H = 48;

    /** filled accent button (pill, contrast-aware label, ripple) — a LIGHT
     *  accent (Mono paper) flips the label to near-black. */
    public static Button filled(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Fx.onAccent(c));
        b.setTextSize(16);
        b.setTypeface(medium());
        b.setMinHeight(Fx.dp(c, BTN_H));
        b.setBackground(ripple(pill(Fx.accent(c))));
        b.setPadding(Fx.dp(c, 22), 0, Fx.dp(c, 22), 0);
        return b;
    }

    /** tonal button (soft accent fill, accent label) — the M3 secondary */
    public static Button tonal(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Fx.accentText(c));
        b.setTextSize(16);
        b.setTypeface(medium());
        b.setMinHeight(Fx.dp(c, BTN_H));
        b.setBackground(ripple(pill(Fx.accentSoft(c))));
        b.setPadding(Fx.dp(c, 22), 0, Fx.dp(c, 22), 0);
        return b;
    }

    /** quiet button (surface fill, outline hairline, text label) */
    public static Button quiet(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Fx.txt(c));
        b.setTextSize(16);
        b.setTypeface(medium());
        b.setMinHeight(Fx.dp(c, BTN_H));
        GradientDrawable g = pill(Fx.surface(c));
        g.setStroke(Math.max(1, Fx.dp(c, 1)), Fx.outline(c));
        b.setBackground(ripple(g));
        b.setPadding(Fx.dp(c, 22), 0, Fx.dp(c, 22), 0);
        return b;
    }

    private static GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(999); // px — clamps to a full pill at control sizes
        return g;
    }

    private static RippleDrawable ripple(android.graphics.drawable.Drawable bg) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(999);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(0x33888888), bg, mask);
    }

    // ---------------------------------------------------------------- bottom sheets

    /**
     * The modern sheet body: rounded 24dp top corners (skin-aware: glass gets
     * the translucent fill + hairline rim), a grab-handle pill at the top,
     * and padded column to add rows into. Returns the LinearLayout to fill.
     */
    public static LinearLayout sheetBody(Context c) {
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        int ph = Fx.dp(c, 18);
        list.setPadding(ph, Fx.dp(c, 8), ph, ph);
        list.addView(grabHandle(c), new LinearLayout.LayoutParams(
                Fx.dp(c, 36), Fx.dp(c, 4), Gravity.CENTER_HORIZONTAL));
        return list;
    }

    private static View grabHandle(Context c) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Fx.dp(c, 2));
        g.setColor((Fx.dim(c) & 0x00FFFFFF) | 0x55000000);
        v.setBackground(g);
        return v;
    }

    /** skin-aware rounded-top sheet background (per-skin radius: sheets run
     *  a notch rounder than cards) */
    public static GradientDrawable sheetBg(Context c) {
        GradientDrawable g = new GradientDrawable();
        int r = Fx.dp(c, Math.min(28, Fx.radius(c) + 4));
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        if (Fx.isGlass(c)) {
            boolean dark = Prefs.get(c).isDark();
            g.setColor(dark ? 0xF2141420 : 0xF2F7F8FC);
            g.setStroke(Math.max(1, Fx.dp(c, 1)), dark ? 0x29FFFFFF : 0x291B1B2E);
        } else {
            g.setColor(Fx.surface(c));
            if (wantShadows(c)) g.setStroke(Math.max(1, Fx.dp(c, 1)), Fx.outline(c));
        }
        return g;
    }

    /**
     * Wrap a sheet body in a bottom-anchored Dialog and show it. Keeps the
     * v1.3.33 mechanics (transparent window + bottom gravity + dim) that
     * make the glass fill read as real glass.
     */
    public static Dialog showSheet(Activity a, LinearLayout body) {
        ScrollView sc = new ScrollView(a);
        sc.addView(body);
        sc.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return showSheetView(a, sc);
    }

    /**
     * v1.4.14 — the RECYCLER sheet door. showSheet() wraps every body in a
     * ScrollView, which is right for static row stacks but WRONG for a
     * RecyclerView: nested scrolling steals the vertical touch stream and
     * an ItemTouchHelper drag inside can never run. This variant takes the
     * content as the dialog's ROOT directly (no ScrollView wrapper) — the
     * caller's RecyclerView owns scrolling AND dragging. Same window
     * mechanics, same night veil, same rounded sheet background.
     */
    public static Dialog showSheetView(Activity a, View content) {
        content.setBackground(sheetBg(a));
        Dialog d = new Dialog(a);
        d.setContentView(content);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout(-1, -2);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
        // v1.3.48 — the night veil covers open dialogs too (a veil on the
        // activity window cannot tint a separate dialog window; the sheet
        // tints itself). All sheets go through here.
        try { com.bolt.browser.util.NightScreen.attachDialog(d); } catch (Throwable ignored) {}
        return d;
    }

    // ---------------------------------------------------------------- choice sheet

    /** callback for choiceSheet */
    public interface Pick { void pick(int index); }

    /**
     * v1.3.35 — the tab-card "open" animation: newly inserted cards scale
     * 0.86→1 with a fade instead of DefaultItemAnimator's plain fade (the
     * "animated tab-card open/close" item). Removals/moves keep the default
     * behavior so the collapse stays buttery.
     * NOTE: the override point is the public animateAdd(ViewHolder) —
     * DefaultItemAnimator's own animateAddImpl is package-private (a
     * different package cannot override it).
     */
    public static class PopAnimator extends androidx.recyclerview.widget.DefaultItemAnimator {
        @Override
        public boolean animateAdd(
                final androidx.recyclerview.widget.RecyclerView.ViewHolder holder) {
            try {
                final View v = holder.itemView;
                v.setAlpha(0f);
                v.setScaleX(0.86f);
                v.setScaleY(0.86f);
                v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(220L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                        .setListener(new android.animation.Animator.AnimatorListener() {
                            @Override public void onAnimationStart(android.animation.Animator a) {}
                            @Override public void onAnimationRepeat(android.animation.Animator a) {}
                            @Override public void onAnimationCancel(android.animation.Animator a) {
                                v.setAlpha(1f); v.setScaleX(1f); v.setScaleY(1f);
                                dispatchAddFinished(holder);
                            }
                            @Override public void onAnimationEnd(android.animation.Animator a) {
                                v.setAlpha(1f); v.setScaleX(1f); v.setScaleY(1f);
                                dispatchAddFinished(holder);
                            }
                        }).start();
            } catch (Throwable ignored) {
                dispatchAddFinished(holder);
            }
            return true;   // we own the animation
        }
    }

    /**
     * The modern replacement for AlertDialog.setSingleChoiceItems: a bottom
     * sheet with a grab handle, title, and radio rows (accent dot indicator,
     * 52dp touch targets). Dismisses on pick.
     */
    public static void choiceSheet(final Activity a, String title, String[] options,
            int selected, final Pick onPick) {
        choiceSheetIcons(a, title, options, null, selected, onPick);
    }

    /**
     * v1.4.23 — THE ICON-ROW SHEET: choiceSheet with a per-row drawable (an
     * int res id, 0 = no icon). Built for the engine picker: every row binds
     * its engine's official 64x64 raster mark (drawable-nodpi PNG — fixed
     * pixels on every device) beside the name; a 0 icon renders the plain
     * text row exactly as before. Brand marks are NEVER tinted — a row that
     * wants the theme tint passes its own tinted drawable. Rows stay
     * 52dp/52dp touch targets and dismiss on pick, same as the text sheet.
     */
    public static void choiceSheetIcons(final Activity a, String title, String[] options,
            int[] icons, int selected, final Pick onPick) {
        LinearLayout body = sheetBody(a);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(18);
        t.setTypeface(medium());
        t.setTextColor(Fx.txt(a));
        t.setPadding(Fx.dp(a, 4), Fx.dp(a, 12), Fx.dp(a, 4), Fx.dp(a, 6));
        body.addView(t, new LinearLayout.LayoutParams(-1, -2));
        // captured dialog holder: assigned by showSheet AFTER the rows are
        // built, so the row listeners reach it through a final array
        final Dialog[] dRef = new Dialog[1];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Fx.dp(a, 52));
            int ph = Fx.dp(a, 12), pv = Fx.dp(a, 8);
            row.setPadding(ph, pv, ph, pv);
            row.setBackground(a.getDrawable(R.drawable.bg_item));
            // v1.4.23 — the row's mark: a 22dp slot, brand colors as baked
            // (never tinted); rows without a drawable (0 / null) skip the
            // slot so the text sheet's geometry is unchanged
            if (icons != null && i < icons.length && icons[i] != 0) {
                try {
                    ImageView iv = new ImageView(a);
                    iv.setImageResource(icons[i]);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                            Fx.dp(a, 22), Fx.dp(a, 22));
                    ilp.rightMargin = Fx.dp(a, 12);
                    row.addView(iv, ilp);
                } catch (Throwable ignored) {}
            }
            TextView label = new TextView(a);
            label.setText(options[i]);
            label.setTextSize(16);
            label.setTextColor(Fx.txt(a));
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(radioDot(a, i == selected), new LinearLayout.LayoutParams(-2, -2));
            row.setOnClickListener(v -> {
                tap(v);                                  // v1.3.36: haptic on pick
                try { dRef[0].dismiss(); } catch (Throwable ignored) {}
                onPick.pick(idx);
            });
            body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        dRef[0] = showSheet(a, body);
    }

    /** M3 radio: 20dp ring + 10dp inner dot when selected */
    private static FrameLayout radioDot(Context c, boolean on) {
        int size = Fx.dp(c, 20);
        FrameLayout fl = new FrameLayout(c);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Fx.dp(c, 2), on ? Fx.accent(c) : Fx.dim(c));
        fl.setBackground(ring);
        if (on) {
            View dot = new View(c);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Fx.accent(c));
            dot.setBackground(g);
            int d = Fx.dp(c, 10), off = (size - d) / 2;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(d, d);
            lp.leftMargin = off;
            lp.topMargin = off;
            fl.addView(dot, lp);
        }
        fl.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        return fl;
    }
}
