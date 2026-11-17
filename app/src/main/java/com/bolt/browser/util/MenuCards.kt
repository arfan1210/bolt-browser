package com.bolt.browser.util

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v1.3.39 — HYBRID MENUS (new code → Kotlin).
 *
 * Field request: "long lists are pretty exhausting to look at, make a few
 * of the settings (specially the site settings and top right 3 dot
 * settings) a mixture of horizontally scrolling cards and vertically
 * scrolling lists in the same menu (use this approach anywhere there is a
 * lot of options). Also since cards cannot hold the full title, for every
 * button (except the ones with a long press shortcut) make long pressing
 * them make a popup explaining its function and feature."
 *
 * [cardStrip] renders a row of fixed-width tappable cards (icon container
 * + short label) inside a horizontal scroller — the "quick actions" tier
 * of a hybrid menu. [explain] attaches the long-press explainer popup to
 * ANY view (cards, menu rows, settings cards) — the user asked for it on
 * every button that doesn't already own its long-press.
 */
object MenuCards {

    class Card(
            @JvmField val icon: Int,
            @JvmField val label: String,
            @JvmField val hint: String?,
            @JvmField val onClick: Runnable)

    /**
     * The horizontal card tier. Cards: ~80dp wide with a REAL 6dp gap
     * between them (v1.3.40 de-congestion: cards used to touch edge to
     * edge, which read as one crowded band), icon container + a 12sp
     * single-line label (uniform — the field report's "keep all the
     * shortcuts uniform" instinct, applied to menus), card background per
     * skin, ripple, haptics. Long-press = the explainer popup.
     */
    @JvmStatic
    fun cardStrip(c: Context, cards: List<Card>): View {
        val hs = HorizontalScrollView(c)
        hs.setHorizontalScrollBarEnabled(false)
        val strip = LinearLayout(c)
        strip.orientation = LinearLayout.HORIZONTAL
        val pad = Fx.dp(c, 6)
        strip.setPadding(pad, Fx.dp(c, 2), pad, Fx.dp(c, 2))
        for (card in cards) {
            val cv = cardView(c, card)
            val lp = cv.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-2, -2)
            lp.marginStart = Fx.dp(c, 3)
            lp.marginEnd = Fx.dp(c, 3)
            cv.layoutParams = lp
            strip.addView(cv)
        }
        hs.addView(strip)
        return hs
    }

    private fun cardView(c: Context, card: Card): View {
        val v = LinearLayout(c)
        v.orientation = LinearLayout.VERTICAL
        v.gravity = Gravity.CENTER_HORIZONTAL
        val padH = Fx.dp(c, 10)
        val padV = Fx.dp(c, 12)
        v.setPadding(padH, padV, padH, Fx.dp(c, 10))
        v.setBackgroundResource(com.bolt.browser.R.drawable.bg_card)

        val icon = ImageView(c)
        icon.setImageResource(card.icon)
        icon.layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
        Ui.iconify(icon, true)
        val fl = android.widget.FrameLayout(c)
        val side = Fx.dp(c, 40)
        fl.layoutParams = LinearLayout.LayoutParams(side, side)
        fl.addView(icon)
        v.addView(fl)

        val label = TextView(c)
        label.text = card.label
        label.setTextSize(12f)
        label.typeface = Ui.medium()
        label.setTextColor(Fx.txt(c))
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        // v1.3.40: 64→76dp label width — the bump to 12sp labels made the
        // old width clip; wider labels also chip away at the "cards cannot
        // hold the full title" complaint.
        val llp = LinearLayout.LayoutParams(Fx.dp(c, 76), -2)
        llp.topMargin = Fx.dp(c, 7)
        v.addView(label, llp)

        v.setOnClickListener {
            Ui.tap(it)
            card.onClick.run()
        }
        explain(v, c, card.label, card.hint ?: card.label)
        // press feedback
        pressScale(v)
        return v
    }

    private fun pressScale(v: View) {
        try {
            v.setOnTouchListener(object : View.OnTouchListener {
                var down = false
                override fun onTouch(x: View, e: android.view.MotionEvent): Boolean {
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            down = true
                            x.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90L).start()
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            if (down) {
                                down = false
                                x.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                            }
                        }
                    }
                    return false
                }
            })
        } catch (t: Throwable) { }
    }

    /**
     * The explainer: long-press [v] → a small dialog carrying the button's
     * title + what it does. Applied everywhere a control has no long-press
     * of its own (toolbar buttons keep their quick actions).
     */
    @JvmStatic
    fun explain(v: View, c: Context, title: String, text: String) {
        try {
            v.setOnLongClickListener {
                Ui.tap(it)
                androidx.appcompat.app.AlertDialog.Builder(c)
                        .setTitle(title)
                        .setMessage(text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                true
            }
        } catch (t: Throwable) { }
    }

    /** Java-friendly overload for BrowserActivity's sheet rows. */
    @JvmStatic
    fun explainRow(v: View, c: Context, title: String, text: String) = explain(v, c, title, text)

    /** shortcut: build a [Card]. */
    @JvmStatic
    fun card(icon: Int, label: String, hint: String, onClick: Runnable): Card =
            Card(icon, label, hint, onClick)
}
