package com.bolt.browser.util

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v1.3.39 — LINK PREVIEW CARDS (new code → Kotlin; menu-3 item 4).
 *
 * "long-press a link → a peek card (title/host/description, maybe
 * thumbnail) with open-new-tab / watch-later / copy actions, instead of
 * only the raw URL menu."
 *
 * The card shows the target's host avatar (the favicon store, falling
 * back to the site-color letter tile), a readable title derived from the
 * URL, the full host + path, and quick actions: Open here, New tab,
 * Watch later, Copy link, Share QR. Everything is LOCAL — the preview
 * never prefetches the network (privacy + a 3 GB device).
 */
object LinkPreview {

    /** show the peek card for [url]. */
    @JvmStatic
    fun show(a: Activity, urlRaw: String?) {
        try {
            val url = urlRaw ?: return
            if (!url.startsWith("http")) return
            val host = Fx.hostOf(url)
            val base = Fx.baseDomain(host)
            val path = Fx.pathOf(url).trim('/')
            val title = if (path.isEmpty()) base
                    else java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
                            .replace("[-_]+".toRegex(), " ").trim().ifEmpty { base }

            val list = Ui.sheetBody(a)
            val pad = Fx.dp(a, 16)

            // header: avatar + title + host
            val head = LinearLayout(a)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.setPadding(pad, pad, pad, Fx.dp(a, 6))
            val av = ImageView(a)
            av.setImageDrawable(FaviconStore.avatar(a, host, base, 40))
            head.addView(av, LinearLayout.LayoutParams(Fx.dp(a, 40), Fx.dp(a, 40)))
            val col = LinearLayout(a)
            col.orientation = LinearLayout.VERTICAL
            val tv = TextView(a)
            tv.text = title
            tv.textSize = 17f
            tv.typeface = Ui.medium()
            tv.setTextColor(Fx.txt(a))
            tv.maxLines = 2
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            col.addView(tv, LinearLayout.LayoutParams(-1, -2))
            val sub = TextView(a)
            sub.text = host + (if (path.isEmpty()) "" else "/" + path)
            sub.textSize = 13f
            sub.setTextColor(Fx.dim(a))
            sub.maxLines = 1
            sub.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            col.addView(sub, LinearLayout.LayoutParams(-1, -2))
            val clp = LinearLayout.LayoutParams(0, -2, 1f)
            clp.leftMargin = Fx.dp(a, 14)
            head.addView(col, clp)
            list.addView(head, LinearLayout.LayoutParams(-1, -2))

            // action cards
            val cards = ArrayList<MenuCards.Card>()
            cards.add(MenuCards.card(com.bolt.browser.R.drawable.ic_globe, "Open", "Load this link in the current tab.",
                    Runnable { open(a, url, false) }))
            cards.add(MenuCards.card(com.bolt.browser.R.drawable.ic_plus, "New tab", "Open the link in a background tab you can switch to later.",
                    Runnable { open(a, url, true) }))
            cards.add(MenuCards.card(com.bolt.browser.R.drawable.ic_bookmark, "Later", "Save the link to the watch-later queue (unread until you read it).",
                    Runnable {
                        try {
                            WatchLater.add(a, url, title)
                            Fx.toast(a, "Added to watch later")
                        } catch (t: Throwable) { }
                    }))
            cards.add(MenuCards.card(com.bolt.browser.R.drawable.ic_copy, "Copy", "Copy the link to the clipboard.",
                    Runnable {
                        try {
                            val cm = a.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Bolt", url))
                            Fx.toast(a, "Link copied")
                        } catch (t: Throwable) { }
                    }))
            cards.add(MenuCards.card(com.bolt.browser.R.drawable.ic_qr, "QR", "Show the link as a scannable QR code to beam it to another device.",
                    Runnable {
                        try { ShareQr.show(a, url, title) } catch (t: Throwable) { }
                    }))
            val strip = MenuCards.cardStrip(a, cards)
            strip.setPadding(0, Fx.dp(a, 4), 0, Fx.dp(a, 10))
            list.addView(strip, LinearLayout.LayoutParams(-1, -2))

            Ui.showSheet(a, list)
        } catch (t: Throwable) { }
    }

    /** dispatch open/current-tab through the browser (falls back to a new
     *  task view intent when the activity isn't the browser). */
    private fun open(a: Activity, url: String, newTab: Boolean) {
        try {
            if (a is com.bolt.browser.ui.BrowserActivity) {
                if (newTab) a.openUrlNewTab(url) else a.openUrlCurrentTab(url)
            } else {
                val i = android.content.Intent(a, com.bolt.browser.ui.BrowserActivity::class.java)
                i.data = android.net.Uri.parse(url)
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                a.startActivity(i)
            }
        } catch (t: Throwable) { }
    }
}
