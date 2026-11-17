package com.bolt.browser.util

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * v1.3.87 — SKIP SILENCE TUNING (new code → Kotlin, per the standing
 * language rule). The four hysteresis knobs the field report asked for,
 * as one bottom sheet shared by the two surfaces that own the skipper:
 * the media FAB's video-control sheet and Settings → Media.
 *
 *   1. Speed …………… skip factor, RELATIVE to the user's speed (1x–8x,
 *                        default 3 — the v1.3.84 hardcoded 4 was "a bit
 *                        too fast")
 *   2. Start after …… ms of quiet before the skip engages (50–2000,
 *                        default 200 — the "I hear a bit of silence" cure
 *                        together with the loop's 250 ms → 60 ms tick)
 *   3. Stop before …… ms rewound when audio returns (0–600, default 150 —
 *                        the "non-silent audio ends up fast-forwarded"
 *                        cure: the onset replays clean)
 *   4. Ignore under … ms: quiet stretches shorter than this, in CONTENT
 *                        time, have their whole skip undone (100–3000,
 *                        default 400 — short pauses stay natural)
 *
 * Units are MILLISECONDS throughout: every value here is sub-second, so
 * seconds would force decimal inputs (the user left the unit choice to
 * us: "I asked for ms because I don't know, maybe seconds better or
 * what, i don't know").
 *
 * Every change is written through [Prefs] immediately; [onApply] fires
 * once on dismiss if anything changed (the callers own the
 * `__boltSetMedia` broadcast to live tabs).
 */
object SkipTune {

    /** one labelled slider row; [apply] runs (value) on every user change. */
    private fun slider(c: Context, title: String, min: Int, max: Int, step: Int,
                       at: Int, fmt: (Int) -> String, apply: (Int) -> Unit): LinearLayout {
        val wrap = LinearLayout(c)
        wrap.orientation = LinearLayout.VERTICAL
        val pad = (c.resources.displayMetrics.density * 18).toInt()
        wrap.setPadding(pad, (pad * 0.55f).toInt(), pad, 0)

        val head = LinearLayout(c)
        head.orientation = LinearLayout.HORIZONTAL
        val label = TextView(c)
        label.text = title
        label.setTextColor(Fx.txt(c))
        label.typeface = Ui.medium()
        label.textSize = 15f
        head.addView(label, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(c)
        value.text = fmt(at)
        value.setTextColor(Fx.tint(c))
        value.typeface = Ui.medium()
        value.textSize = 15f
        head.addView(value)
        wrap.addView(head)

        val sb = SeekBar(c)
        sb.max = (max - min) / step
        sb.progress = (at - min) / step
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = min + p * step
                value.text = fmt(v)
                if (fromUser) apply(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        wrap.addView(sb, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    /**
     * Show the tuning sheet. [onApply] fires after ANY knob changed while
     * the sheet was open (once, on dismiss).
     */
    @JvmStatic
    fun sheet(a: Activity, p: Prefs, onApply: Runnable) {
        val body = Ui.sheetBody(a)
        val pad = (a.resources.displayMetrics.density * 20).toInt()

        val title = TextView(a)
        title.text = a.getString(com.bolt.browser.R.string.skip_tune_title)
        title.setTextColor(Fx.txt(a))
        title.typeface = Ui.medium()
        title.textSize = 18f
        title.setPadding(pad, pad, pad, (pad * 0.4f).toInt())
        body.addView(title)

        val sub = TextView(a)
        sub.text = a.getString(com.bolt.browser.R.string.skip_tune_desc)
        sub.setTextColor(Fx.dim(a))
        sub.textSize = 13f
        sub.setPadding(pad, 0, pad, 0)
        body.addView(sub)

        var changed = false

        body.addView(slider(a, a.getString(com.bolt.browser.R.string.skip_tune_speed),
                1, 8, 1, p.skipFactor(), { v -> "${v}x" }) { v ->
            p.setSkipFactor(v); changed = true })

        body.addView(slider(a, a.getString(com.bolt.browser.R.string.skip_tune_start),
                50, 2000, 50, p.skipAttack(), { v -> "$v ms" }) { v ->
            p.setSkipAttack(v); changed = true })

        body.addView(slider(a, a.getString(com.bolt.browser.R.string.skip_tune_stop),
                0, 600, 10, p.skipRelease(), { v ->
            if (v == 0) a.getString(com.bolt.browser.R.string.skip_tune_off) else "$v ms" }) { v ->
            p.setSkipRelease(v); changed = true })

        body.addView(slider(a, a.getString(com.bolt.browser.R.string.skip_tune_min),
                100, 3000, 50, p.skipMinSilence(), { v -> "$v ms" }) { v ->
            p.setSkipMinSilence(v); changed = true })

        val d = Ui.showSheet(a, body)
        d.setOnDismissListener { if (changed) onApply.run() }
    }
}
