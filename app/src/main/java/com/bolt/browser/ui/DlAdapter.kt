package com.bolt.browser.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bolt.browser.R
import com.bolt.browser.core.DownloadEngine
import com.bolt.browser.core.DownloadEngine.Item
import com.bolt.browser.core.DownloadEngine.ST_DONE
import com.bolt.browser.core.DownloadEngine.ST_FAILED
import com.bolt.browser.core.DownloadEngine.ST_PAUSED
import com.bolt.browser.core.DownloadEngine.ST_QUEUED
import com.bolt.browser.core.DownloadEngine.ST_RUNNING
import com.bolt.browser.util.Fx
import com.bolt.browser.util.Ui
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v1.3.59 — THE DOWNLOADS REDESIGN (Kotlin, per the standing language rule).
 *
 * Field report on v1.3.58's Downloads screen: "the downloaded files is
 * pretty monotonous in the downloads. No separation for the different days
 * of downloads, no icons (at least should use different icons for different
 * file types), no previews, no card mode. Even the current list mode seems
 * ineffective as it takes too much space for too little information.
 * Re-imagine the downloads to mimic a highly modern, aesthetic design while
 * still being functionality as primary focus."
 *
 * This file carries the four new pieces:
 *  - [DlTypes]   — the file-type vocabulary: category detection + a Material
 *                  glyph + a fixed brand hue per category (the "different
 *                  icons for different file types" ask), shared by the list,
 *                  the cards AND the browser's download shelf.
 *  - [DlPreview] — the async thumbnail engine (image decode / video frame /
 *                  APK icon, LruCache, recycled-view tag guard).
 *  - [DlAdapter] — the RecyclerView adapter: DAY-GROUPED sections (Active on
 *                  top, then Today / Yesterday / dates), a compact one-line
 *                  list row, and a square-preview grid card.
 *  - [DlSheet]   — the modern long-press action sheet (open / share / copy
 *                  link / pause / retry / remove…), replacing the AlertDialog.
 */
object DlTypes {

    /** fixed brand hues — chosen to read on light AND dark surfaces */
    private const val C_IMAGE = 0xFF0D9488.toInt()   // teal
    private const val C_VIDEO = 0xFF7C3AED.toInt()   // violet
    private const val C_AUDIO = 0xFFEA580C.toInt()   // orange
    private const val C_APK = 0xFF16A34A.toInt()     // green
    private const val C_ARCH = 0xFFB45309.toInt()    // amber-brown
    private const val C_DOC = 0xFF2563EB.toInt()     // blue
    private const val C_OTHER = 0xFF64748B.toInt()   // slate
    // v1.3.78 — the "docs" bucket split at the field's word ("currently
    // pdfs and docx have the same icon"): every family gets its own glyph
    // and its own hue
    private const val C_PDF = 0xFFDC2626.toInt()     // red
    private const val C_WORD = 0xFF2563EB.toInt()    // blue
    private const val C_SHEET = 0xFF059669.toInt()   // emerald
    private const val C_SLIDES = 0xFFD97706.toInt()  // amber
    private const val C_TEXT = 0xFF475569.toInt()    // slate-600
    private const val C_BOOK = 0xFFDB2777.toInt()    // pink

    /** bucket a download by MIME type / file extension. v1.3.78: the old
     *  one-glyph "docs" bucket is GONE — pdf/word/sheet/slides/text/book
     *  each get their own glyph + hue (the field report: "give different
     *  file types different icons, like currently pdfs and docx have the
     *  same icon"). */
    @JvmStatic
    fun catOf(mime: String?, name: String?): String {
        val m = (mime ?: "").lowercase()
        val n = (name ?: "").lowercase()
        val dot = n.lastIndexOf('.')
        val e = if (dot >= 0) n.substring(dot + 1) else ""
        fun ext(list: String): Boolean = e.isNotEmpty() && list.split(' ').contains(e)
        if (m.startsWith("image/") || ext("jpg jpeg png gif webp bmp svg heic heif avif")) return "images"
        if (m.startsWith("video/") || ext("mp4 mkv webm avi mov 3gp m4v ts")) return "video"
        if (m.startsWith("audio/") || ext("mp3 m4a aac flac wav ogg oga opus amr mid")) return "audio"
        if (m.contains("android.package-archive") || ext("apk xapk apks aab")) return "apk"
        if (m.contains("zip") || m.contains("compressed") || m.contains("tar")
                || ext("zip rar 7z tar gz bz2 xz iso zst")) return "arch"
        // word/excel/powerpoint BEFORE their generic "officedocument" mime
        if (m.contains("wordprocessing") || m.contains("msword")
                || ext("doc docx docm dotx rtf odt pages")) return "word"
        if (m.contains("spreadsheet") || m.contains("ms-excel") || m.contains("csv")
                || ext("xls xlsx xlsm ods")) return "sheet"
        if (m.contains("presentation") || m.contains("powerpoint")
                || ext("ppt pptx pps ppsx odp key")) return "slides"
        if (m.contains("pdf") || ext("pdf")) return "pdf"
        if (m.contains("epub") || ext("epub mobi azw3 fb2")) return "book"
        if (m.startsWith("text/") || ext("txt md log json xml yaml yml ini")) return "text"
        return "other"
    }

    /** the Material glyph for a category. */
    @JvmStatic
    fun iconOf(cat: String): Int = when (cat) {
        "images" -> R.drawable.ic_dl_image
        "video" -> R.drawable.ic_dl_video
        "audio" -> R.drawable.ic_dl_audio
        "apk" -> R.drawable.ic_dl_apk
        "arch" -> R.drawable.ic_dl_arch
        "docs" -> R.drawable.ic_dl_doc
        "pdf" -> R.drawable.ic_dl_pdf
        "word" -> R.drawable.ic_dl_word
        "sheet" -> R.drawable.ic_dl_sheet
        "slides" -> R.drawable.ic_dl_slides
        "text" -> R.drawable.ic_dl_text
        "book" -> R.drawable.ic_dl_book
        else -> R.drawable.ic_dl_file
    }

    /** the brand hue for a category (glyphs, filters, tints). */
    @JvmStatic
    fun colorOf(cat: String): Int = when (cat) {
        "images" -> C_IMAGE
        "video" -> C_VIDEO
        "audio" -> C_AUDIO
        "apk" -> C_APK
        "arch" -> C_ARCH
        "docs" -> C_DOC
        "pdf" -> C_PDF
        "word" -> C_WORD
        "sheet" -> C_SHEET
        "slides" -> C_SLIDES
        "text" -> C_TEXT
        "book" -> C_BOOK
        else -> C_OTHER
    }

    /** a category can carry a decoded preview (thumbnail / frame / icon). */
    internal fun previewable(cat: String): Boolean =
            cat == "images" || cat == "video" || cat == "apk"

    /** the soft container fill: the hue blended onto the live surface. */
    internal fun softOn(hue: Int, surface: Int): Int {
        val a = 0.14f
        val r = lerp(Color.red(surface), Color.red(hue), a)
        val g = lerp(Color.green(surface), Color.green(hue), a)
        val b = lerp(Color.blue(surface), Color.blue(hue), a)
        return Color.rgb(r, g, b)
    }

    private fun lerp(from: Int, to: Int, t: Float): Int =
            (from + (to - from) * t).toInt().coerceIn(0, 255)
}

/**
 * The async preview engine. Decodes off the main thread on ONE low-priority
 * thread, keeps an LruCache keyed by item-id+size, and applies results only
 * when the target ImageView still carries the tag minted for that load —
 * a recycled view can never receive a stale bitmap.
 */
object DlPreview {

    private val cache = object : android.util.LruCache<String, Bitmap>(48) {}
    private val pool: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bolt-dlthumbs").apply { priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())

    /** Load a preview for [it] into [iv] (hiding [glyph] when it lands).
     *  The caller must have cleared iv.tag beforehand (bind-time reset). */
    internal fun load(act: Activity, it: Item, sizeDp: Int, iv: ImageView, glyph: ImageView) {
        val uriStr = it.finalUri?.takeIf { s -> s.isNotEmpty() }
        if (uriStr == null || it.status != ST_DONE) { apply(iv, glyph, null); return }
        val size = (sizeDp * act.resources.displayMetrics.density).toInt().coerceAtLeast(64)
        val key = "${it.id}:$size"
        val hit = cache.get(key)
        if (hit != null) { apply(iv, glyph, hit); return }
        apply(iv, glyph, null)
        iv.tag = key
        pool.execute {
            val bmp = try { decode(act, uriStr, size, it) } catch (t: Throwable) { null }
            if (bmp != null) cache.put(key, bmp)
            main.post {
                if (iv.tag == key) apply(iv, glyph, bmp)
            }
        }
    }

    private fun apply(iv: ImageView, glyph: ImageView, bmp: Bitmap?) {
        if (bmp != null) {
            iv.setImageBitmap(bmp)
            iv.visibility = View.VISIBLE
            glyph.visibility = View.INVISIBLE
        } else {
            iv.setImageDrawable(null)
            glyph.visibility = View.VISIBLE
        }
    }

    /** reset a tile to its glyph state (no preview). */
    internal fun reset(iv: ImageView, glyph: ImageView) = apply(iv, glyph, null)

    private fun decode(c: Context, uriStr: String, size: Int, it: Item): Bitmap? {
        val uri = Uri.parse(uriStr)
        return when (DlTypes.catOf(it.mime, it.name)) {
            "images" -> decodeImage(c, uri, size)
            "video" -> decodeVideo(c, uri, size)
            "apk" -> decodeApk(c, uri)
            else -> null
        }
    }

    private fun openStream(c: Context, uri: Uri): java.io.InputStream? {
        return if ("file" == uri.scheme) {
            val p = uri.path ?: return null
            File(p).inputStream()
        } else c.contentResolver.openInputStream(uri)
    }

    private fun decodeImage(c: Context, uri: Uri, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(c, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var s = 1
        while (bounds.outWidth / (s * 2) >= size && bounds.outHeight / (s * 2) >= size) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        val raw = openStream(c, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
        return centerCrop(raw, size)
    }

    private fun decodeVideo(c: Context, uri: Uri, size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            return c.contentResolver.loadThumbnail(uri, android.util.Size(size, size), null)
        }
        // legacy path: only real file paths decode
        val p = uri.path ?: return null
        @Suppress("DEPRECATION")
        return ThumbnailUtils.createVideoThumbnail(p,
                android.provider.MediaStore.Images.Thumbnails.MINI_KIND)
    }

    private fun decodeApk(c: Context, uri: Uri): Bitmap? {
        val p = uri.path ?: return null
        val pm = c.packageManager
        val info = pm.getPackageArchiveInfo(p, 0) ?: return null
        info.applicationInfo?.sourceDir = p
        info.applicationInfo?.publicSourceDir = p
        val d = info.applicationInfo?.loadIcon(pm) ?: return null
        val out = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        d.setBounds(0, 0, 96, 96)
        d.draw(cv)
        return out
    }

    private fun centerCrop(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        if (side <= 0) return src
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val crop = Bitmap.createBitmap(src, x, y, side, side)
        if (side == size) return crop
        return Bitmap.createScaledBitmap(crop, size, size, true)
    }
}

/** a section header row (the Active strip or a day label + count). */
internal data class Hdr(val title: String, val count: Int)

/**
 * The Downloads list/grid adapter. Row models are either [Hdr] or
 * [Item]; card mode is a flag the activity flips (span lookup adapts).
 */
class DlAdapter(
        private val act: Activity,
        private val host: Host
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Host {
        /** tap — the state's primary action (open / pause / resume / retry) */
        fun onPrimary(it: Item)
        /** long-press — the action sheet */
        fun onOptions(it: Item)
    }

    var cards: Boolean = false
        set(v) { field = v; notifyDataSetChanged() }

    private val rows = ArrayList<Any>()
    private val fmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fmtDayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fmtDay = SimpleDateFormat("d MMMM", Locale.getDefault())
    private val fmtDayY = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())

    /** section headers span the full grid; also used by the span lookup. */
    fun isHeader(pos: Int): Boolean = pos >= 0 && pos < rows.size && rows[pos] is Hdr

    /** v1.3.91 — the ids of the last submit's ITEMS (headers excluded):
     * the reorder-diff key set for [submit]'s animated path. */
    private val lastIds = ArrayList<Long>()

    /** rebuild the grouped model: ACTIVE strip first, then history by day.
     *  v1.3.91 — [flat]/[flatLabel] (a name/size sort is live): the day
     *  sections are dropped for ONE "Sorted by…" header — day headers over
     *  reordered rows would lie about the grouping; [animate] (the same
     *  item set as the previous pass, i.e. a pure sort flip) emits
     *  notifyItemMoved per row so the cards glide (the item animator's
     *  move animation). */
    fun submit(list: List<Item>) { submit(list, false, null, false) }

    @JvmOverloads
    fun submit(list: List<Item>, flat: Boolean, flatLabel: String? = null,
            animate: Boolean = false) {
        val prevRows = ArrayList(rows)   // the old model, for the move diff
        val prevIds = ArrayList(lastIds)
        val prevSet = prevIds.toHashSet()
        val newSet = list.filter { !(it.status == ST_QUEUED || it.status == ST_RUNNING || it.status == ST_PAUSED) }
                .map { it.id }.toHashSet()
        rows.clear()
        lastIds.clear()
        val active = ArrayList<Item>()
        val hist = ArrayList<Item>()
        for (it in list) {
            if (it.status == ST_QUEUED || it.status == ST_RUNNING || it.status == ST_PAUSED)
                active.add(it) else hist.add(it)
        }
        if (active.isNotEmpty()) {
            rows.add(Hdr(act.getString(R.string.active_downloads), active.size))
            rows.addAll(active)
        }
        if (flat) {
            // one flat section: the sort is not time-order, so day headers
            // would mislabel the groups
            if (hist.isNotEmpty()) {
                rows.add(Hdr(flatLabel ?: act.getString(R.string.sorted_by_name), hist.size))
                rows.addAll(hist)
            }
        } else {
            var lastKey: String? = null
            var hdrIdx = -1
            var hdrCount = 0
            for (it in hist) {
                val key = fmtDayKey.format(Date(it.time))
                if (key != lastKey) {
                    lastKey = key
                    hdrCount = 1
                    rows.add(Hdr(dayLabel(it.time), 1))
                    hdrIdx = rows.size - 1
                } else {
                    hdrCount++
                    rows[hdrIdx] = Hdr((rows[hdrIdx] as Hdr).title, hdrCount)
                }
                rows.add(it)
            }
        }
        for (o in rows) if (o is Item) lastIds.add(o.id)
        val pureReorder = animate && prevRows.isNotEmpty()
                && prevSet == newSet && rows.size == prevRows.size
        if (!pureReorder) {
            notifyDataSetChanged()
            return
        }
        // THE MOVE DIFF (same shape as BaseListActivity.setRowsAnimated):
        // work left-to-right on a copy of the old model, bubbling each
        // wanted row into place with notifyItemMoved; converges on `rows`
        val working = ArrayList<Any>(prevRows)
        for (j in working.indices) {
            val wantKey = rowKeyOf(rows[j])
            var at = -1
            for (k in j until working.size) {
                if (rowKeyOf(working[k]) == wantKey) { at = k; break }
            }
            if (at > j) {
                val moved = working.removeAt(at)
                working.add(j, moved)
                notifyItemMoved(at, j)
            }
        }
    }

    /** the identity a pure reorder preserves: header title / item id. */
    private fun rowKeyOf(o: Any): String =
            if (o is Hdr) "h:" + o.title else "i:" + (o as Item).id

    /** Today / Yesterday / "d MMMM" / "d MMMM yyyy" (older years). */
    private fun dayLabel(t: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = t }
        val key = fmtDayKey.format(Date(t))
        val todayKey = fmtDayKey.format(Date(now.timeInMillis))
        now.add(Calendar.DAY_OF_YEAR, -1)
        val yKey = fmtDayKey.format(Date(now.timeInMillis))
        return when (key) {
            todayKey -> act.getString(R.string.today)
            yKey -> act.getString(R.string.yesterday)
            else ->
                if (then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR))
                    fmtDay.format(Date(t)) else fmtDayY.format(Date(t))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(pos: Int): Int {
        val o = rows[pos]
        return if (o is Hdr) TYPE_HDR else if (cards) TYPE_CARD else TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HDR -> HeaderV()
            TYPE_CARD -> {
                val v = act.layoutInflater.inflate(R.layout.item_dl_card, parent, false)
                skin(v)
                // square the preview frame to the measured CELL width
                // (precompute from the RecyclerView when it is already laid
                // out — the scrolling case — so no first-frame jump; the
                // post covers the initial pass)
                val media = v.findViewById<FrameLayout>(R.id.fl_dl_media)
                fun square() {
                    try {
                        val w = if (media.width > 0) media.width else {
                            val pw = parent.width - parent.paddingLeft - parent.paddingRight
                            if (pw > 0) (pw - 4 * Fx.dp(act, 3)) / 2 else 0
                        }
                        if (w > 0 && media.height != w) {
                            media.layoutParams.height = w
                            media.requestLayout()
                        }
                    } catch (ignored: Throwable) {}
                }
                square()
                v.post { square() }
                CardV(v)
            }
            else -> {
                val v = act.layoutInflater.inflate(R.layout.item_dl_list, parent, false)
                skin(v)
                RowV(v)
            }
        }
    }

    /** per-skin card surface + hairline + elevation, like every other list */
    private fun skin(v: View) {
        try {
            v.background = Ui.rowBg(act)
            v.elevation = if (Ui.wantShadows(act)) Fx.dp(act, 1).toFloat() else 0f
        } catch (ignored: Throwable) {}
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        val o = rows[pos]
        if (o is Hdr) { bindHeader(h as HeaderV, o); return }
        val it = o as Item
        if (h is CardV) bindCard(h, it) else bindRow(h as RowV, it)
    }

    // ---------------------------------------------------------------- header

    private fun bindHeader(h: HeaderV, hdr: Hdr) {
        h.label.text = "${hdr.title}  ·  ${hdr.count}"
        h.label.setTextColor(Fx.dim(act))
    }

    // ---------------------------------------------------------------- list row

    private fun bindRow(h: RowV, d: Item) {
        bindTile(h.frame, h.thumb, h.glyph, d, 46)
        h.title.text = d.name ?: ""
        h.sub.text = statusLine(d)
        h.sub.setTextColor(if (d.status == ST_FAILED) errColor() else Fx.dim(act))
        bindProgress(h.bar, d)
        bindAction(h.actBtn, d)
        h.itemView.setOnClickListener { _ -> host.onPrimary(d) }
        h.itemView.setOnLongClickListener { _ -> host.onOptions(d); true }
    }

    // ---------------------------------------------------------------- card

    private fun bindCard(h: CardV, d: Item) {
        // the media frame rounds only its TOP corners (the card's own bg
        // carries the bottom pair)
        try {
            val r = Fx.dp(act, Fx.radius(act)).toFloat()
            val g = GradientDrawable()
            g.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            val cat = DlTypes.catOf(d.mime, d.name)
            g.setColor(DlTypes.softOn(DlTypes.colorOf(cat), Fx.surface(act)))
            h.media.background = g
            h.media.clipToOutline = true
        } catch (ignored: Throwable) {}
        bindTile(h.media, h.thumb, h.glyph, d, 140)
        h.title.text = d.name ?: ""
        h.sub.text = statusLine(d)
        h.sub.setTextColor(if (d.status == ST_FAILED) errColor() else Fx.dim(act))
        bindProgress(h.bar, d)
        h.itemView.setOnClickListener { _ -> host.onPrimary(d) }
        h.itemView.setOnLongClickListener { _ -> host.onOptions(d); true }
    }

    // ---------------------------------------------------------------- shared pieces

    /** the preview tile: rounded soft-tinted square, glyph over it, async
     *  thumbnail when the category + state allow one. */
    private fun bindTile(frame: FrameLayout, thumb: ImageView, glyph: ImageView, d: Item, sizeDp: Int) {
        val cat = DlTypes.catOf(d.mime, d.name)
        val hue = DlTypes.colorOf(cat)
        try {
            val g = GradientDrawable()
            g.cornerRadius = Fx.dp(act, Fx.iconRadius(act).coerceAtLeast(14)).toFloat()
            g.setColor(DlTypes.softOn(hue, Fx.surface(act)))
            frame.background = g
            frame.clipToOutline = true
        } catch (ignored: Throwable) {}
        glyph.setImageResource(DlTypes.iconOf(cat))
        glyph.colorFilter = PorterDuffColorFilter(hue, PorterDuff.Mode.SRC_IN)
        thumb.tag = null   // invalidate any in-flight decode for this view
        if (d.status == ST_DONE && DlTypes.previewable(cat)) {
            DlPreview.load(act, d, sizeDp, thumb, glyph)
        } else {
            DlPreview.reset(thumb, glyph)
        }
    }

    private fun bindProgress(bar: ProgressBar, d: Item) {
        val active = d.status == ST_RUNNING || d.status == ST_QUEUED || d.status == ST_PAUSED
        bar.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) return
        if (d.total > 0) {
            bar.isIndeterminate = false
            bar.progress = (100L * d.done / d.total).toInt().coerceIn(0, 100)
        } else {
            bar.isIndeterminate = d.status == ST_RUNNING
            if (d.status != ST_RUNNING) bar.progress = 0
        }
    }

    private fun bindAction(btn: ImageButton, d: Item) {
        val eng = DownloadEngine.get(act)
        val dim = Fx.dim(act)
        when (d.status) {
            ST_RUNNING, ST_QUEUED -> {
                btn.setImageResource(R.drawable.ic_pause)
                btn.contentDescription = act.getString(R.string.pause_dl)
                btn.setOnClickListener { _ -> eng.pause(d.id) }
                btn.colorFilter = PorterDuffColorFilter(dim, PorterDuff.Mode.SRC_IN)
            }
            ST_PAUSED -> {
                btn.setImageResource(R.drawable.ic_play)
                btn.contentDescription = act.getString(R.string.resume_dl)
                btn.setOnClickListener { _ -> eng.resume(d.id) }
                btn.colorFilter = PorterDuffColorFilter(Fx.accent(act), PorterDuff.Mode.SRC_IN)
            }
            ST_FAILED -> {
                btn.setImageResource(R.drawable.ic_reload)
                btn.contentDescription = act.getString(R.string.retry)
                btn.setOnClickListener { _ -> eng.retry(d.id) }
                btn.colorFilter = PorterDuffColorFilter(Fx.accent(act), PorterDuff.Mode.SRC_IN)
            }
            else -> {
                btn.setImageResource(R.drawable.ic_folder)
                btn.contentDescription = act.getString(R.string.open_file)
                btn.setOnClickListener { _ -> host.onPrimary(d) }
                btn.colorFilter = PorterDuffColorFilter(dim, PorterDuff.Mode.SRC_IN)
            }
        }
    }

    /** the dense single status line. */
    private fun statusLine(it: Item): String {
        fun fmt(b: Long) = android.text.format.Formatter.formatShortFileSize(act, b)
        return when (it.status) {
            ST_RUNNING -> {
                val parts = ArrayList<String>()
                if (it.total > 0) parts.add("${100L * it.done / it.total}%")
                if (it.speed > 0) parts.add("${fmt(it.speed)}/s")
                if (parts.isEmpty()) parts.add(act.getString(R.string.downloading))
                parts.joinToString(" · ")
            }
            ST_QUEUED -> act.getString(R.string.download_queued)
            ST_PAUSED -> {
                val pct = if (it.total > 0) "${100L * it.done / it.total}% · " else ""
                pct + act.getString(R.string.download_paused)
            }
            ST_FAILED -> act.getString(R.string.download_failed) +
                    (if (it.error.isNotEmpty()) ": " + it.error else "")
            else -> (if (it.total > 0) fmt(it.total) + " · " else "") + fmtTime.format(Date(it.time))
        }
    }

    private fun errColor(): Int =
            if (com.bolt.browser.util.Prefs.get(act).isDark()) 0xFFF87171.toInt() else 0xFFDC2626.toInt()

    // ---------------------------------------------------------------- holders

    private inner class HeaderV : RecyclerView.ViewHolder(TextView(act).apply {
        textSize = 12f
        typeface = Ui.medium()
        setPadding(Fx.dp(act, 6), Fx.dp(act, 14), Fx.dp(act, 6), Fx.dp(act, 6))
        layoutParams = RecyclerView.LayoutParams(-1, -2)
    }) {
        val label: TextView = itemView as TextView
    }

    class RowV(v: View) : RecyclerView.ViewHolder(v) {
        val frame: FrameLayout = v.findViewById(R.id.fl_dl_icon)
        val thumb: ImageView = v.findViewById(R.id.iv_dl_thumb)
        val glyph: ImageView = v.findViewById(R.id.iv_dl_icon)
        val title: TextView = v.findViewById(R.id.tv_dl_title)
        val sub: TextView = v.findViewById(R.id.tv_dl_sub)
        val actBtn: ImageButton = v.findViewById(R.id.btn_dl_act)
        val bar: ProgressBar = v.findViewById(R.id.pb_dl)
    }

    class CardV(v: View) : RecyclerView.ViewHolder(v) {
        val media: FrameLayout = v.findViewById(R.id.fl_dl_media)
        val thumb: ImageView = v.findViewById(R.id.iv_dl_thumb)
        val glyph: ImageView = v.findViewById(R.id.iv_dl_icon)
        val title: TextView = v.findViewById(R.id.tv_dl_title)
        val sub: TextView = v.findViewById(R.id.tv_dl_sub)
        val bar: ProgressBar = v.findViewById(R.id.pb_dl)
    }

    companion object {
        private const val TYPE_HDR = 0
        private const val TYPE_ROW = 1
        private const val TYPE_CARD = 2
    }
}

/**
 * The long-press action sheet — the modern bottom-sheet replacement for the
 * old AlertDialog itemOptions (grab handle, 52dp icon-chip rows, one action
 * each). Also owns the open/share/copy primitives the sheet and the
 * activity's primary-action path share.
 */
object DlSheet {

    /** show the per-state action sheet for [it]. */
    @JvmStatic
    fun show(act: Activity, it: Item) {
        val eng = DownloadEngine.get(act)
        val body = Ui.sheetBody(act)

        val title = TextView(act)
        title.text = it.name ?: ""
        title.textSize = 16f
        title.typeface = Ui.medium()
        title.setTextColor(Fx.txt(act))
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        body.addView(title, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Fx.dp(act, 10); bottomMargin = Fx.dp(act, 6)
        })

        data class Act(val icon: Int, val label: String, val run: () -> Unit)
        val acts = ArrayList<Act>()
        when (it.status) {
            ST_DONE -> {
                acts.add(Act(R.drawable.ic_folder, act.getString(R.string.open_file)) { openFile(act, it) })
                acts.add(Act(R.drawable.ic_share, act.getString(R.string.share_file)) { shareFile(act, it) })
                acts.add(Act(R.drawable.ic_copy, act.getString(R.string.copy_link)) { copyLink(act, it) })
                acts.add(Act(R.drawable.ic_delete, act.getString(R.string.delete_entry)) { eng.delete(it.id) })
            }
            ST_RUNNING, ST_QUEUED -> {
                acts.add(Act(R.drawable.ic_pause, act.getString(R.string.pause_dl)) { eng.pause(it.id) })
                acts.add(Act(R.drawable.ic_close, act.getString(R.string.cancel)) { eng.cancel(it.id) })
            }
            ST_PAUSED -> {
                acts.add(Act(R.drawable.ic_play, act.getString(R.string.resume_dl)) { eng.resume(it.id) })
                acts.add(Act(R.drawable.ic_close, act.getString(R.string.cancel)) { eng.cancel(it.id) })
            }
            ST_FAILED -> {
                acts.add(Act(R.drawable.ic_reload, act.getString(R.string.retry)) { eng.retry(it.id) })
                acts.add(Act(R.drawable.ic_copy, act.getString(R.string.copy_link)) { copyLink(act, it) })
                acts.add(Act(R.drawable.ic_delete, act.getString(R.string.delete_entry)) { eng.delete(it.id) })
            }
        }

        val dlg = Ui.showSheet(act, body)
        for (a in acts) {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val chip = Ui.iconContainer(act, a.icon, false)
            row.addView(chip, LinearLayout.LayoutParams(Fx.dp(act, 40), Fx.dp(act, 40)).apply {
                marginEnd = Fx.dp(act, 14)
            })
            val label = TextView(act)
            label.text = a.label
            label.textSize = 15f
            label.setTextColor(Fx.txt(act))
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            row.setPadding(0, Fx.dp(act, 5), 0, Fx.dp(act, 5))
            row.setBackgroundResource(R.drawable.bg_item)
            row.isClickable = true
            row.setOnClickListener { v ->
                Ui.tap(v)
                try { dlg.dismiss() } catch (ignored: Throwable) {}
                try { a.run() } catch (ignored: Throwable) {}
            }
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = Fx.dp(act, 4)
            })
        }
    }

    /** open a finished download (the shared primary action).
     *  v1.3.78 — THE APK PATH: Android 8+ only lets the package installer
     *  run for apps holding the "install unknown apps" grant; without it
     *  the ACTION_VIEW intent dies quietly ("apks downloaded don't open
     *  when I click open after downloading"). With the manifest permission
     *  requested, the first APK open deep-links the user to the ONE switch
     *  that fixes it forever, then they tap Open again. Any other file
     *  whose typed intent has no handler retries with a wildcard before
     *  giving up (a mis-mimed file still opens in a viewer). */
    @JvmStatic
    fun openFile(act: Activity, it: Item) {
        try {
            val eng = DownloadEngine.get(act)
            val name = (it.name ?: "").lowercase()
            val apkish = name.endsWith(".apk") || name.endsWith(".xapk")
                    || (it.mime ?: "").contains("android.package-archive")
            if (apkish && !eng.canInstallApks()) {
                android.app.AlertDialog.Builder(act)
                    .setTitle("Allow app installs?")
                    .setMessage("Android needs a one-time permission before Bolt can open installers. " +
                            "Flip \"Allow from this source\" for Bolt, then tap Open again.")
                    .setPositiveButton("Open setting") { d, _ ->
                        try {
                            val pi = eng.installPermissionIntent()
                            if (pi != null) act.startActivity(pi)
                        } catch (e: Throwable) {
                            Fx.toast(act, "Could not open the setting")
                        }
                        try { d.dismiss() } catch (_: Throwable) {}
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return
            }
            val i = eng.openFileIntent(it)
            if (i == null) { Fx.toast(act, "Can't open this file type"); return }
            try {
                act.startActivity(i)
            } catch (e: android.content.ActivityNotFoundException) {
                // typed mime found no handler — try the wildcard before
                // declaring defeat
                val wild = eng.openFileIntent(it, true)
                if (wild != null) {
                    try { act.startActivity(wild); return } catch (e2: Throwable) {}
                }
                Fx.toast(act, "No app can open this file")
            }
        } catch (e: Throwable) {
            Fx.toast(act, "No app can open this file")
        }
    }

    /** share the finished file itself (ACTION_SEND with the grant flag). */
    internal fun shareFile(act: Activity, it: Item) {
        try {
            val view = DownloadEngine.get(act).openFileIntent(it)
            if (view == null || view.data == null) {
                Fx.toast(act, "Can't share this file"); return
            }
            val send = Intent(Intent.ACTION_SEND)
            send.type = view.type
            send.putExtra(Intent.EXTRA_STREAM, view.data)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(Intent.createChooser(send, it.name))
        } catch (e: Throwable) {
            Fx.toast(act, "Can't share this file")
        }
    }

    /** copy the source URL to the clipboard. */
    internal fun copyLink(act: Activity, it: Item) {
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", it.url ?: ""))
            Fx.toast(act, act.getString(R.string.dl_link_copied))
        } catch (e: Throwable) {
            Fx.toast(act, "Can't copy link")
        }
    }
}
