package com.bolt.browser.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/**
 * v1.3.39 — NIGHT SCREEN FILTER (new code → Kotlin; menu-3 item 9).
 * v1.3.48 REWORK (field: "The night screen is bad as now it is, it does not
 * apply uniformly throughout, like not in the tabs, not in the settings, no
 * live preview of the intensity when adjusting"):
 *  - the veil now attaches to EVERY activity's WINDOW DECOR (via BoltApp's
 *    lifecycle callbacks, registered in BOTH processes), not just the
 *    browser's fl_root — the tabs switcher, every settings screen, every
 *    other activity reads tinted. The ONE deliberate exception: the QR
 *    scanner (an amber tint over the live camera preview would hurt
 *    decode).
 *  - bottom sheets (Ui.showSheet) and the intensity dialog get their OWN
 *    veil in the dialog window, so open dialogs are tinted too.
 *  - LIVE PREVIEW: the intensity slider recolors every live veil as the
 *    thumb moves; while that dialog is open the veil previews the dragged
 *    value even when the schedule says OFF (you see what you are setting).
 *  - the quick-menu toggle applies IMMEDIATELY (no resume wait).
 *
 * The veil is a non-clickable full-window overlay whose warm amber strength
 * follows the intensity slider. Non-clickable means touches fall through to
 * the UI (README lesson 15) — it genuinely costs nothing until the sun
 * goes down.
 *
 * v1.3.49 — IN-APP BRIGHTNESS (field: "allow lowering overall in app
 * brightness throughout the app using a slider under the night screen"):
 * a SECOND per-decor veil, plain BLACK, whose alpha follows the new
 * "Brightness" slider (0..100%, default 50, 100 = no veil at all). It
 * rides the SAME per-decor machinery (BoltApp lifecycle apply/cleanup,
 * dialog attach, live preview) so every window in both processes dims
 * uniformly — tabs, settings, sheets, dialogs. Unlike the amber veil it
 * deliberately does NOT skip the QR scanner: a screen dim is a
 * display-only effect (decode reads the camera buffer, not the display),
 * and "throughout the app" means everywhere.
 *
 * v1.3.50 (field: "the brightness slider does not show live feedback;
 * the slide is not 50 at middle; when I turn night screen off or on I
 * want live instant change; the brightness slider is independent of night
 * mode, I want it to only activate when night mode is on"):
 *  - THE LIVE WINDOW REGISTRY: refreshAll used to iterate only the roots
 *    that ALREADY carried a veil, so a preview could only RECOLOR existing
 *    veils — with brightness at 100 (or the night screen off) both maps
 *    were EMPTY and sliding the thumb did literally NOTHING. Every live
 *    activity's decor (apply) and every shown dialog's decor (attachDialog)
 *    now registers here, and refreshAll re-evaluates the WHOLE registry —
 *    create, recolor or remove — in one beat. This is also what makes the
 *    settings TOGGLE instant (on AND off) instead of resume-gated.
 *  - the dim veil is now PART OF THE NIGHT SCREEN: it exists only while
 *    the night screen is active (or previewed through the intensity
 *    slider). Night off = no dim, period.
 *  - the slider is a plain 0..100 scale with the default (50) parked in
 *    the MIDDLE — the thumb rests where the eye expects it.
 *
 * AUTO: nightFrom/nightTo HH:MM prefs (crossing midnight supported);
 * re-evaluated on every resume + a 1-minute re-check while visible.
 */
object NightScreen {

    const val PREF_ON = "nightOn"           // master toggle (quick panel)
    const val PREF_AUTO = "nightAuto"       // follow the schedule
    const val PREF_FROM = "nightFrom"       // "22:00"
    const val PREF_TO = "nightTo"           // "06:00"
    const val PREF_INT = "nightIntensity"   // 0..100
    const val PREF_BRIGHT = "appBrightness" // 0..100 (default 50; 100 = full, no veil)

    /** one veil per window-decor root (activities + dialogs). Weak keys: a
     *  destroyed activity's decor must never be kept alive by the map, and
     *  a dismissed dialog's entry dies with its window. */
    private val veils: MutableMap<ViewGroup, View> =
            java.util.Collections.synchronizedMap(WeakHashMap())

    /** v1.3.49 — the BRIGHTNESS (black) veils, same per-decor contract. */
    private val dims: MutableMap<ViewGroup, View> =
            java.util.Collections.synchronizedMap(WeakHashMap())

    /** v1.3.50 — THE LIVE WINDOW REGISTRY: every live ACTIVITY's decor
     *  (keyed to its activity so the QR-scanner amber exception survives a
     *  refreshAll). Weak keys: a destroyed activity's decor must never be
     *  kept alive by the map. */
    private val liveActs: MutableMap<ViewGroup, Activity> =
            java.util.Collections.synchronizedMap(WeakHashMap())

    /** v1.3.50 — every SHOWN DIALOG's decor (attachDialog). Entries die with
     *  the dialog window (weak keys, GC-collected). */
    private val liveDialogs: MutableSet<ViewGroup> =
            java.util.Collections.newSetFromMap(
                java.util.Collections.synchronizedMap(WeakHashMap<ViewGroup, Boolean>()))

    /** non-null while the intensity dialog is open: every live veil shows
     *  THIS value regardless of the stored schedule (the live preview). */
    private var previewInt: Int? = null

    /** v1.3.49 — non-null while the brightness dialog is open. */
    private var previewBright: Int? = null

    /** true when the schedule says the filter should currently be on. */
    @JvmStatic
    fun scheduled(c: android.content.Context): Boolean {
        if (!Prefs.get(c).b(PREF_AUTO, false)) return Prefs.get(c).b(PREF_ON, false)
        val now = minutesOf(java.time.LocalTime.now().withSecond(0))
        val from = parse(Prefs.get(c).s(PREF_FROM, "22:00"))
        val to = parse(Prefs.get(c).s(PREF_TO, "06:00"))
        return if (from == to) Prefs.get(c).b(PREF_ON, false)
        else if (from < to) now in from until to          // same-window schedule
        else now >= from || now < to                      // crosses midnight
    }

    private fun minutesOf(t: java.time.LocalTime): Int = t.hour * 60 + t.minute

    private fun parse(s: String?): Int {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(s ?: "") ?: return 22 * 60
        return (m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt())
                .coerceIn(0, 24 * 60 - 1)
    }

    /** format an int minute count back to HH:MM for the editor. */
    @JvmStatic
    fun fmt(min: Int): String = String.format(java.util.Locale.US, "%02d:%02d",
            min / 60, min % 60)

    /** the amber veil color for an intensity 0..100. */
    @JvmStatic
    fun veilColor(intensity: Int): Int {
        val i = intensity.coerceIn(0, 100)
        val alpha = (i * 1.35f).toInt().coerceAtMost(175)   // 0..~68% opacity
        return (alpha shl 24) or 0x00FF8A3C
    }

    /** v1.3.49 → v1.3.50 — the black veil color for a brightness 0..100
     * (100 never creates a veil at all). Linear in (100 - b): 0 -> alpha
     * 200 (~78% black, a strong but usable floor for an LCD at night),
     * 50 -> alpha 100 (~39%), the slider's centered default. */
    @JvmStatic
    fun dimColor(brightness: Int): Int {
        val b = brightness.coerceIn(0, 100)
        return (((100 - b) * 2.0f).toInt().coerceAtMost(200)) shl 24   // pure black + alpha
    }

    private fun intensity(c: Context): Int = Prefs.get(c).i(PREF_INT, 55)

    private fun brightness(c: Context): Int =
            Prefs.get(c).i(PREF_BRIGHT, 50).coerceIn(0, 100)

    /** should a veil exist right now for a window of this context? */
    private fun want(c: Context): Boolean =
            previewInt != null || (scheduled(c) && intensity(c) > 0)

    /** v1.3.50 — should a DIM veil exist right now? The brightness dim is
     * PART OF THE NIGHT SCREEN: it activates only while the night screen is
     * on (or previewed through the intensity slider) — the field report:
     * "the brightness slider is independent of night mode, I want it to
     * only activate when night mode is on". */
    private fun wantDim(c: Context): Boolean {
        val b = previewBright ?: brightness(c)
        if (b >= 100) return false
        return previewInt != null || scheduled(c)
    }

    /** (re)apply or remove the veil for this activity's window — called by
     *  BoltApp on every resume (ALL activities now, not just the browser)
     *  and by the quick-menu toggle. */
    @JvmStatic
    fun apply(a: Activity) {
        try {
            val decor = a.window?.decorView as? ViewGroup ?: return
            // v1.3.50 — register in the live window registry (refreshAll
            // re-evaluates EVERY live window, not just veiled ones)
            synchronized(liveActs) { liveActs[decor] = a }
            // v1.3.49: the brightness dim applies to EVERY activity — a
            // screen dim is display-only (decode reads the camera buffer),
            // so even the QR scanner stays consistent with the rest of the app.
            syncDim(decor)
            // the ONE exception for the AMBER veil: the live camera QR
            // scanner — the tint would sit between the lens and the decoder
            if (a is com.bolt.browser.ui.QrScanActivity) return
            sync(decor)
        } catch (t: Throwable) { }
    }

    /** attach a veil to a DIALOG's own window too (bottom sheets via
     *  Ui.showSheet, the intensity dialog) — a veil on the activity window
     *  cannot tint a separate dialog window. Call after show(). */
    @JvmStatic
    fun attachDialog(d: Dialog) {
        try {
            val decor = d.window?.decorView as? ViewGroup ?: return
            liveDialogs.add(decor)   // v1.3.50: previews must reach dialogs too
            syncDim(decor)
            sync(decor)
        } catch (t: Throwable) { }
    }

    /** create / recolor / remove the veil on one window root. */
    private fun sync(root: ViewGroup) {
        val c = root.context
        val on = want(c)
        val cur = veils[root]
        if (!on) {
            if (cur != null) {
                veils.remove(root)
                try { root.removeView(cur) } catch (t: Throwable) { }
            }
            return
        }
        val color = veilColor(previewInt ?: intensity(c))
        if (cur != null && cur.parent === root) {
            cur.setBackgroundColor(color)
            return
        }
        // fresh attach (or re-attach after the window rebuilt its content)
        val v = View(c)
        v.setBackgroundColor(color)
        // NOT clickable: UI touches must pass through (lesson 15)
        v.isClickable = false
        v.isFocusable = false
        root.addView(v, ViewGroup.LayoutParams(-1, -1))
        veils[root] = v
    }

    /** v1.3.49 — create / recolor / remove the BRIGHTNESS (black) veil on
     *  one window root — the same contract as sync(). */
    private fun syncDim(root: ViewGroup) {
        try {
            val c = root.context
            val cur = dims[root]
            if (!wantDim(c)) {
                if (cur != null) {
                    dims.remove(root)
                    try { root.removeView(cur) } catch (t: Throwable) { }
                }
                return
            }
            val color = dimColor(previewBright ?: brightness(c))
            if (cur != null && cur.parent === root) {
                cur.setBackgroundColor(color)
                return
            }
            val v = View(c)
            v.setBackgroundColor(color)
            v.isClickable = false
            v.isFocusable = false
            root.addView(v, ViewGroup.LayoutParams(-1, -1))
            dims[root] = v
        } catch (t: Throwable) { }
    }

    /** live refresh of every live window (slider drag, toggle, schedule
     *  tick) — v1.3.50: re-evaluates the WHOLE registry (live activity
     *  decors + shown dialog decors + any root that already carries a
     *  veil), so a preview can CREATE veils on windows that have none yet
     *  — the old map-only iteration was why the brightness slider showed
     *  no feedback at all (no dim veil existed to recolor). */
    @JvmStatic
    fun refreshAll() {
        val roots = java.util.LinkedHashSet<ViewGroup>()
        synchronized(liveActs) { roots.addAll(liveActs.keys) }
        synchronized(liveDialogs) { roots.addAll(liveDialogs) }
        synchronized(veils) { roots.addAll(veils.keys) }
        synchronized(dims) { roots.addAll(dims.keys) }
        for (root in roots) {
            try {
                syncDim(root)
                val act: Activity? = synchronized(liveActs) { liveActs[root] }
                if (act == null || act !is com.bolt.browser.ui.QrScanActivity) sync(root)
            } catch (t: Throwable) { }
        }
    }

    /** v1.3.48 — LIVE PREVIEW: while the intensity dialog is open, every
     *  veil shows this value regardless of the stored schedule. */
    @JvmStatic
    fun preview(intensity: Int) {
        previewInt = intensity.coerceIn(0, 100)
        refreshAll()
    }

    /** the intensity dialog closed — veils revert to the scheduled state. */
    @JvmStatic
    fun endPreview() {
        previewInt = null
        refreshAll()
    }

    /** v1.3.49 — LIVE PREVIEW for the brightness slider: while its dialog
     *  is open, every dim veil shows this value (v1.3.50: the dim itself is
     *  still night-gated — previewing only shows while the night screen is
     *  on, matching the stored effect). */
    @JvmStatic
    fun previewBrightness(brightness: Int) {
        previewBright = brightness.coerceIn(0, 100)
        refreshAll()
    }

    /** the brightness dialog closed — veils revert to the stored value. */
    @JvmStatic
    fun endBrightnessPreview() {
        previewBright = null
        refreshAll()
    }

    /** drop this activity's veil (lifecycle destroy — explicit, no GC wait). */
    @JvmStatic
    fun cleanup(a: Activity) {
        try {
            val decor = a.window?.decorView as? ViewGroup ?: return
            synchronized(liveActs) { liveActs.remove(decor) }
            veils.remove(decor)?.let { v -> try { decor.removeView(v) } catch (t: Throwable) { } }
            dims.remove(decor)?.let { v -> try { decor.removeView(v) } catch (t: Throwable) { } }
        } catch (t: Throwable) { }
    }

    /** quick toggle from the page menu / quick actions — applies NOW. */
    @JvmStatic
    fun toggle(c: android.content.Context) {
        val p = Prefs.get(c)
        val now = !p.b(PREF_ON, false)
        p.set(PREF_ON, now)
        if (now) p.set(PREF_AUTO, false)      // manual override wins over schedule
        val a = (c as? Activity) ?: com.bolt.browser.BoltApp.currentActivity()
        if (a != null) { try { apply(a) } catch (t: Throwable) { } }
        refreshAll()
        Fx.toast(c, if (now) "Night screen on" else "Night screen off")
    }

    /** the 1-minute re-check used while the browser is resumed. */
    @JvmStatic
    fun recheck(a: Activity) {
        apply(a)
        refreshAll()
    }
}
