package com.bolt.browser.util

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.bolt.browser.R
import java.io.File
import java.io.FileOutputStream

/**
 * v1.3.38 — SHARE PAGE AS QR (menu item 3 of the round-2 modernization menu;
 * new code → Kotlin per the README language rule).
 *
 * Renders the current URL as a scannable QR (the encode path already shipped
 * for the authenticator — Qr.encodeBitmap) in a rounded card with three
 * actions: Share (PNG through the FileProvider), Save to Downloads
 * (MediaStore), and Copy URL. Beaming a page to another phone's camera is
 * the "no account, no nearby share" path.
 */
object ShareQr {

    @JvmStatic
    fun show(a: Activity, url: String?, title: String?) {
        val link = url ?: return
        if (link.isBlank()) return
        Thread {
            val size = Fx.dp(a, 260)
            val qr: Bitmap? = try { Qr.encodeBitmap(link, size) } catch (t: Throwable) { null }
            a.runOnUiThread {
                if (qr == null) {
                    Fx.toast(a, "Link too long for a QR code")
                    return@runOnUiThread
                }
                showDialog(a, link, title ?: link, qr)
            }
        }.start()
    }

    private fun showDialog(a: Activity, url: String, title: String, qr: Bitmap) {
        val pad = Fx.dp(a, 22)
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.setPadding(pad, pad, pad, Fx.dp(a, 16))

        // QR on a white tile (scanners require the light module background)
        val tile = ImageView(a)
        tile.setImageBitmap(qr)
        val bg = GradientDrawable()
        bg.setCornerRadius(Fx.dp(a, 16).toFloat())
        bg.setColor(0xFFFFFFFF.toInt())
        tile.background = bg
        val tlp = LinearLayout.LayoutParams(Fx.dp(a, 260), Fx.dp(a, 260))
        tile.setPadding(Fx.dp(a, 10), Fx.dp(a, 10), Fx.dp(a, 10), Fx.dp(a, 10))
        tile.clipToOutline = true
        body.addView(tile, tlp)

        val t = TextView(a)
        t.text = title
        t.textSize = 15f
        t.typeface = Ui.medium()
        t.setTextColor(Fx.txt(a))
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        t.setPadding(0, Fx.dp(a, 14), 0, 0)
        body.addView(t, LinearLayout.LayoutParams(-1, -2))

        val u = TextView(a)
        u.text = url
        u.textSize = 13f
        u.setTextColor(Fx.dim(a))
        u.gravity = Gravity.CENTER
        u.maxLines = 2
        u.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        u.setPadding(0, Fx.dp(a, 2), 0, 0)
        body.addView(u, LinearLayout.LayoutParams(-1, -2))

        // action buttons
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val rlp = LinearLayout.LayoutParams(-1, -2)
        rlp.topMargin = Fx.dp(a, 18)
        row.layoutParams = rlp

        fun act(label: String, primary: Boolean, click: (View) -> Unit): TextView {
            val b = TextView(a)
            b.text = label
            b.textSize = 14f
            b.typeface = Ui.medium()
            b.gravity = Gravity.CENTER
            val g = GradientDrawable()
            g.setCornerRadius(Fx.dp(a, 999).toFloat())
            g.setColor(if (primary) Fx.accent(a) else Fx.surface2(a))
            b.setTextColor(if (primary) Fx.onAccent(a) else Fx.txt(a))
            b.background = g
            val hpad = Fx.dp(a, 14)
            b.setPadding(hpad, Fx.dp(a, 11), hpad, Fx.dp(a, 11))
            b.setOnClickListener { v -> Ui.tap(v); click(v) }
            return b
        }

        val share = act("Share", true) { sharePng(a, qr, url, title) }
        row.addView(share, LinearLayout.LayoutParams(0, -2, 1f))
        val save = act("Save", false) { v -> savePng(a, qr) }
        val slp = LinearLayout.LayoutParams(0, -2, 1f)
        slp.leftMargin = Fx.dp(a, 8)
        row.addView(save, slp)
        val copy = act("Copy", false) { copyUrl(a, url) }
        val clp = LinearLayout.LayoutParams(0, -2, 1f)
        clp.leftMargin = Fx.dp(a, 8)
        row.addView(copy, clp)
        body.addView(row, LinearLayout.LayoutParams(-1, -2))

        val d = Ui.showSheet(a, body)
        d.setOnDismissListener {
            try { qr.recycle() } catch (ignored: Throwable) {}
        }
    }

    private fun copyUrl(a: Activity, url: String) {
        try {
            val cm = a.getSystemService(Activity.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Bolt", url))
            Fx.toast(a, a.getString(R.string.copied))
        } catch (ignored: Throwable) {
        }
    }

    private fun savePng(a: Activity, qr: Bitmap) {
        try {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                    java.util.Locale.US).format(java.util.Date())
            val name = "BoltQR-$stamp.png"
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues()
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                val uri = a.contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri == null) {
                    Fx.toast(a, "Save failed")
                    return
                }
                a.contentResolver.openOutputStream(uri)?.use { out ->
                    qr.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Fx.toast(a, "Saved to Downloads/$name")
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                if (dir != null && !dir.exists()) dir.mkdirs()
                val f = File(dir, name)
                FileOutputStream(f).use { out -> qr.compress(Bitmap.CompressFormat.PNG, 100, out) }
                Fx.toast(a, "Saved to Downloads/$name")
            }
        } catch (t: Throwable) {
            Fx.toast(a, "Save failed")
        }
    }

    private fun sharePng(a: Activity, qr: Bitmap, url: String, title: String) {
        try {
            // filesDir/shared is a declared FileProvider path (cache/ is not)
            val dir = File(a.filesDir, "shared")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "bolt-qr-share.png")
            FileOutputStream(f).use { out -> qr.compress(Bitmap.CompressFormat.PNG, 100, out) }
            val uri: Uri = FileProvider.getUriForFile(a, "com.bolt.browser.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND)
            i.type = "image/png"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.putExtra(Intent.EXTRA_TEXT, url)
            i.putExtra(Intent.EXTRA_SUBJECT, title)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(Intent.createChooser(i, "Share QR"))
        } catch (t: Throwable) {
            Fx.toast(a, "Share failed")
        }
    }
}
