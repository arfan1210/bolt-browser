package com.bolt.browser.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * v1.3.92 — THE VECTOR PDF (the field: "saving a pdf means I want to save
 * as a vector file, not just a screenshot wrapped in pdf file. The save as
 * pdf still does that"). New code → Kotlin per the language rule.
 *
 * WHAT THIS IS: a real PDF text engine — android PdfDocument pages painted
 * with StaticLayout + TextPaint, so every glyph is a VECTOR: selectable,
 * searchable, copy-pasteable, ~30x smaller than the strip-stitch raster
 * output, and it reflows to any zoom crisply.
 *
 * BLOCK MODEL (what [AiExport.blocksJs] extracts from the page):
 *   h1..h6 — headings (bold, scaled)      p — paragraph
 *   li — list item (bulleted, indented)   pre — code (monospace, boxed)
 *   blockquote — quote (indented, bar)    td/th — table cell (compact)
 *   dt/dd/figcaption — treated as p
 *
 * LAYOUT LAW: A4 portrait 595x842pt, 48pt side margins, 56pt top/bottom
 * (page 1 carries a 22pt title header), 16.5pt leading on 11pt body.
 * A block that does not fit the remaining band starts the next page; a
 * block TALLER than a full page is SPLIT across pages by drawing clipped
 * bands of its StaticLayout (clip + negative translate = a window onto
 * the layout — the same trick AiExport.capturePdf uses for strips).
 */
object VectorPdf {

    class Blk(@JvmField val kind: String, @JvmField val text: String)

    // A4 at 72dpi, in PostScript points.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN_X = 48f
    private const val MARGIN_TOP = 56f
    private const val MARGIN_BOT = 56f
    private const val LEADING = 1.5f

    // ---------------------------------------------------------------- api

    /** completion callback (fun interface so Java callers can use lambdas). */
    fun interface SaveCb { fun onDone(ok: Boolean, uri: android.net.Uri?, msg: String) }

    /** Render [blocks] to a vector PDF and write it to public Downloads as
     *  [prefix]<timestamp>.pdf; [onDone] runs on the MAIN thread. */
    @JvmStatic
    fun save(a: Activity, blocks: List<Blk>, prefix: String, header: String,
            onDone: SaveCb) {
        Thread({
            try {
                val doc = render(blocks, header)
                var uri: android.net.Uri? = null
                var msg = "Saved to Downloads"
                try {
                    val name = prefix + System.currentTimeMillis() + ".pdf"
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val cv = android.content.ContentValues()
                        cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                        cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                        uri = a.contentResolver.insert(
                                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                        if (uri == null) throw IllegalStateException("insert failed")
                        try {
                            val os = a.contentResolver.openOutputStream(uri)
                            // v1.3.93 — a null stream is a FAILURE, not a
                            // silent skip: the old ?.use left uri non-null, so
                            // the callback reported success over a 0-byte row
                            if (os == null) throw IllegalStateException("stream null")
                            os.use { doc.writeTo(it) }
                        } catch (t2: Throwable) {
                            // the insert landed but the write did not — no
                            // 0-byte orphan row stays in Downloads
                            try { a.contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
                            uri = null
                            throw t2
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS)
                        if (dir != null && !dir.exists()) dir.mkdirs()
                        val f = java.io.File(dir, name)
                        java.io.FileOutputStream(f).use { doc.writeTo(it) }
                        uri = android.net.Uri.fromFile(f)
                    }
                } catch (t: Throwable) {
                    msg = "Save failed"
                } finally {
                    try { doc.close() } catch (_: Throwable) {}
                }
                val fUri = uri
                val fMsg = msg
                a.runOnUiThread { onDone.onDone(fUri != null, fUri, fMsg) }
            } catch (t: Throwable) {
                a.runOnUiThread { onDone.onDone(false, null, "Render failed") }
            }
        }, "bolt-vecpdf").start()
    }

    /** the renderer itself (synchronous; called on the worker thread). */
    private fun render(blocks: List<Blk>, header: String): PdfDocument {
        val doc = PdfDocument()
        val contentW = (PAGE_W - 2 * MARGIN_X).toInt()
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        var canvas = page.canvas
        var pageNo = 1
        var y = MARGIN_TOP

        // page-1 header: the source line + a hairline rule
        val headPaint = TextPaint().apply {
            color = Color.rgb(120, 120, 132); textSize = 9f
            typeface = Typeface.SANS_SERIF
            isAntiAlias = true
        }
        if (header.isNotEmpty()) {
            val hl = StaticLayout.Builder.obtain(header, 0, header.length, headPaint,
                    contentW).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            canvas.save()
            canvas.translate(MARGIN_X, 24f)
            hl.draw(canvas)
            canvas.restore()
            val lp = android.graphics.Paint().apply {
                color = Color.rgb(220, 220, 226); strokeWidth = 0.8f
            }
            canvas.drawLine(MARGIN_X, 24f + hl.height + 10f,
                    PAGE_W - MARGIN_X, 24f + hl.height + 10f, lp)
        }

        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            val layout = layoutFor(b, contentW)
            val spaceNeeded = padAbove(b.kind) + layout.height
            val pageBottom = PAGE_H - MARGIN_BOT - 16f   // 16pt reserved for the footer

            if (y + spaceNeeded <= pageBottom) {
                drawBlock(canvas, b, layout, y)
                y += spaceNeeded + padBelow(b.kind)
            } else if (layout.height <= (pageBottom - MARGIN_TOP - padAbove(b.kind))) {
                // fits a fresh page whole
                drawFooter(canvas, header, pageNo)
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                canvas = page.canvas
                y = MARGIN_TOP
                drawBlock(canvas, b, layout, y)
                y += spaceNeeded + padBelow(b.kind)
            } else {
                // taller than a page: draw it in clipped bands
                var drawn = 0f
                var first = true
                var bandBottom = y
                while (drawn < layout.height) {
                    val top = if (first) y + padAbove(b.kind) else MARGIN_TOP
                    var bandH = (pageBottom - top).coerceAtLeast(0f)
                    if (bandH <= 0f) {
                        drawFooter(canvas, header, pageNo)
                        doc.finishPage(page)
                        pageNo++
                        page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                        canvas = page.canvas
                        continue
                    }
                    bandH = minOf(bandH, layout.height - drawn)
                    if (b.kind == "pre") drawPreBg(canvas, top, bandH, contentW)
                    canvas.save()
                    canvas.translate(MARGIN_X + indentFor(b.kind) + if (b.kind == "pre") 8f else 0f,
                            top - drawn)
                    canvas.clipRect(0f, drawn, contentW.toFloat(), drawn + bandH)
                    layout.draw(canvas)
                    canvas.restore()
                    drawn += bandH
                    bandBottom = top + bandH
                    first = false
                    if (drawn < layout.height) {
                        drawFooter(canvas, header, pageNo)
                        doc.finishPage(page)
                        pageNo++
                        page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                        canvas = page.canvas
                    }
                }
                y = bandBottom + padBelow(b.kind)
            }
            i++
        }
        drawFooter(canvas, header, pageNo)
        doc.finishPage(page)
        return doc
    }

    // -------------------------------------------------------------- styles

    private fun paintFor(kind: String): TextPaint {
        val p = TextPaint()
        p.isAntiAlias = true
        when (kind) {
            "h1" -> { p.textSize = 17f; p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); p.color = Color.rgb(17, 17, 24) }
            "h2" -> { p.textSize = 15f; p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); p.color = Color.rgb(17, 17, 24) }
            "h3" -> { p.textSize = 13.5f; p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); p.color = Color.rgb(28, 28, 38) }
            "h4", "h5", "h6" -> { p.textSize = 12f; p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); p.color = Color.rgb(28, 28, 38) }
            "pre" -> { p.textSize = 9f; p.typeface = Typeface.MONOSPACE; p.color = Color.rgb(40, 40, 48) }
            "li" -> { p.textSize = 11f; p.typeface = Typeface.SANS_SERIF; p.color = Color.rgb(26, 26, 34) }
            "blockquote" -> { p.textSize = 11f; p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC); p.color = Color.rgb(70, 70, 82) }
            "td", "th" -> { p.textSize = 10.5f; p.typeface = if (kind == "th") Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF; p.color = Color.rgb(26, 26, 34) }
            else -> { p.textSize = 11f; p.typeface = Typeface.SANS_SERIF; p.color = Color.rgb(26, 26, 34) }
        }
        return p
    }

    private fun indentFor(kind: String): Float = when (kind) {
        "li" -> 16f
        "blockquote" -> 18f
        else -> 0f
    }

    /** extra space ABOVE a block (pre carries its box padding). */
    private fun padAbove(kind: String): Float = when (kind) {
        "h1" -> 14f; "h2" -> 12f; "h3" -> 10f; "h4", "h5", "h6" -> 8f
        "pre" -> 6f; "blockquote" -> 6f; "li" -> 2f; "td", "th" -> 1f
        else -> 5f
    }

    /** extra space BELOW a block. */
    private fun padBelow(kind: String): Float = when (kind) {
        "h1", "h2", "h3" -> 4f
        "pre" -> 8f; "blockquote" -> 6f; "td", "th" -> 1f
        else -> 5f
    }

    private fun layoutFor(b: Blk, contentW: Int): StaticLayout {
        val paint = paintFor(b.kind)
        val text = when (b.kind) {
            "li" -> "\u2022  " + b.text       // the bullet (vector glyph)
            "td" -> b.text
            else -> b.text
        }
        val w = (contentW - indentFor(b.kind) - if (b.kind == "pre") 16f else 0f).toInt()
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint,
                w.coerceAtLeast(50))
        builder.setAlignment(if (b.kind == "blockquote") Layout.Alignment.ALIGN_NORMAL
                else Layout.Alignment.ALIGN_NORMAL)
        builder.setLineSpacing(0f, LEADING)
        builder.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        return builder.build()
    }

    private fun drawBlock(canvas: android.graphics.Canvas, b: Blk,
            layout: StaticLayout, y: Float) {
        val top = y + padAbove(b.kind)
        if (b.kind == "pre") {
            drawPreBg(canvas, top, layout.height.toFloat(),
                    (PAGE_W - 2 * MARGIN_X).toInt())
        }
        if (b.kind == "blockquote") {
            val bar = android.graphics.Paint().apply {
                color = Color.rgb(124, 77, 255); strokeWidth = 2.5f
            }
            canvas.drawLine(MARGIN_X + 4f, top, MARGIN_X + 4f, top + layout.height, bar)
        }
        canvas.save()
        canvas.translate(MARGIN_X + indentFor(b.kind) + if (b.kind == "pre") 8f else 0f, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawPreBg(canvas: android.graphics.Canvas, top: Float, h: Float, w: Int) {
        val bg = android.graphics.Paint().apply { color = Color.rgb(242, 242, 246) }
        val r = android.graphics.RectF(MARGIN_X, top, MARGIN_X + w, top + h)
        canvas.drawRoundRect(r, 6f, 6f, bg)
    }

    private fun drawFooter(canvas: android.graphics.Canvas, header: String, pageNo: Int) {
        try {
            val p = TextPaint().apply {
                color = Color.rgb(150, 150, 160); textSize = 8f
                typeface = Typeface.SANS_SERIF; isAntiAlias = true
            }
            val pn = pageNo.toString()
            canvas.drawText(pn, PAGE_W / 2f - p.measureText(pn) / 2f, PAGE_H - 28f, p)
            val src = if (header.length > 60) header.substring(0, 60) else header
            canvas.drawText(src, MARGIN_X, PAGE_H - 28f, p)
            canvas.drawText("Bolt", PAGE_W - MARGIN_X - 20f, PAGE_H - 28f, p)
        } catch (_: Throwable) {}
    }

    /** parse the JSON array the page produced ([AiExport.blocksJs]). */
    @JvmStatic
    fun parseBlocks(json: String): ArrayList<Blk> {
        val out = ArrayList<Blk>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("k", "p")
                val t = o.optString("t", "").trim()
                if (t.isEmpty()) continue
                out.add(Blk(if (k.isEmpty()) "p" else k, t))
            }
        } catch (_: Throwable) {}
        return out
    }
}
