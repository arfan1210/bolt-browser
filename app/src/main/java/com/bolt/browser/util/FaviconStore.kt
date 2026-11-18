package com.bolt.browser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * v1.3.39 — the on-disk FAVICON CACHE (new code → Kotlin).
 * v1.3.40 — the REAL-LOGO overhaul (field report: "a lot of the website
 * icons are not being detected or applied properly … facebook logo does
 * not appear in a shortcut for it, only F as a text … I want the official
 * logo to be detected and applied"). Three changes:
 *
 *  1. NETWORK FALLBACK: browsing captures (onReceivedIcon) only ever see
 *     sites you VISIT — a shortcut added for facebook.com without visiting
 *     fell back to the letter tile. [fetchAsync] now pulls the official
 *     icon from DuckDuckGo's icon service (ip3/<host>.ico) into the same
 *     on-disk cache, with a miss-marker so a dead host is not re-hit on
 *     every home rebuild (7-day backoff).
 *  2. SIZE UPGRADES: "first seen wins" froze the first (often 16×16)
 *     capture forever. A significantly larger capture now REPLACES the
 *     stored icon.
 *  3. ASPECT-TRUE BRAND TILES: the old avatar stretched any favicon into
 *     a square over a white chip ("a jpg slapped on top"). Icons are now
 *     drawn aspect-preserved, centered on a tile filled with the icon's
 *     OWN average color (the Facebook f reads as the real blue mark;
 *     pale/outline icons get a neutral themed tile), corners follow the
 *     skin radius.
 *
 * v1.3.81 - FULL-HOST KEYING + THE RACE GUARD (the field report: "zai's
 *  logo shows google, google forms logo shows google logo in the tabs").
 *  KEYING: captures used to be filed under baseDomain, so every
 *  *.google.com property (docs/forms/drive/mail - each with its OWN brand
 *  mark) fought over ONE "google.com" slot and the first capture (usually
 *  the plain G) won forever. The key is now the FULL host with the
 *  baseDomain file as a legacy fallback read + fetch candidate.
 *  RACE: the outgoing page's in-flight icon request can land just after
 *  the NEXT page starts, and the v1.3.78 pin then files it under the NEW
 *  host (google's G under z.ai - and domPending() then sees "an icon
 *  exists" so the DOM extractor never fixes it). A host CHANGE now opens a
 *  700ms drop window in [inRaceWindow]; onReceivedIcon checks it first.
 */
object FaviconStore {

    private const val MAX = 64          // px — crisper on tab cards

    // v1.4.13 — THE DISK BUDGET (the report: "the favicon cache downloads
    // and saves icons but never evicts old ones, leading to infinite
    // storage growth over time"). Two caps, EITHER one triggers eviction:
    // at most 1000 icon files, at most 50 MB of icons on disk. Eviction is
    // LRU by lastModified — and lastModified is a USE stamp: load() touches
    // every file it serves (once per host per process, before the mem LRU
    // takes over), a fresh store() write stamps the just-saved file, so
    // "oldest" genuinely means "least recently used", surviving restarts.
    const val MAX_ICONS = 1000
    const val MAX_BYTES = 50L * 1024 * 1024

    // v1.3.79 — THE MEMORY LAYER. store() now rides the background executor
    // (the v1.3.79 perf pass: onReceivedIcon used to PNG-encode + write on
    // the MAIN thread, 10–50 ms per icon landing), so surfaces need a fast
    // read path: an LRU of decoded bitmaps (access-ordered) + an LRU of the
    // composed TILE drawables avatar() makes (tab cards rebind constantly
    // and every bind used to re-composite a fresh bitmap). Disk decode now
    // happens at most once per host per process; after that it is memory.
    private val mem = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 96
            })
    private val tileCache = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Drawable>(48, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable>): Boolean = size > 64
            })

    /** hosts whose DOM icon extraction already ran this session (once per
     * host per process — the v1.3.79 universal favicon pass; see
     * [storePngDataUrl]). */
    private val domTriedHosts = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** true when [markDomTried] hasn't been called for this host yet (the
     * onPageFinished favicon extractor asks before injecting its JS). */
    @JvmStatic
    fun domPending(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = try { normalize(host) } catch (t: Throwable) { return false }
        return h.isNotEmpty() && !domTriedHosts.contains(h) &&
                !File(dir(CTX ?: return false), name(h)).exists()
    }

    /** v1.3.81 - the store key: the FULL lowercased host (v1.3.39-v1.3.80
     *  used baseDomain - that collapsed every google subdomain onto ONE
     *  "google.com" slot, so a Google Forms tab showed the plain G; see
     *  the class doc). */
    private fun normalize(host: String): String = host.trim().lowercase()

    /** the DOM extraction ran for this host — do not inject again this
     * session (a landing real icon clears it from the equation by existing). */
    @JvmStatic
    fun markDomTried(host: String?) {
        if (host.isNullOrEmpty()) return
        try {
            val h = normalize(host)
            if (h.isNotEmpty()) domTriedHosts.add(h)
        } catch (ignored: Throwable) {}
    }

    /** v1.3.78 — THE HOST PIN. onReceivedIcon events can land AFTER a
     *  navigation commits (favicon requests are late, async, and per-frame),
     *  so reading view.getUrl() at callback time attributed the OLD page's
     *  bitmap to the NEW page's host — google's icon filed under
     *  youtube.com and then shown on every youtube tile ("the tabs icons
     *  shows the opened one for several near tabs"). The host is pinned at
     *  onPageStarted (the page that STARTED owns every icon that lands on
     *  its view) and the pin dies with the view (WeakHashMap). */
    private val viewHosts = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<android.webkit.WebView, String>())

    /** v1.3.81 - per-view "icons in this window belong to the page that
     *  just LEFT" deadline (see pinViewHost's race guard). */
    private val viewRaceUntil = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<android.webkit.WebView, Long>())

    /** BrowserClient.onPageStarted calls this — the committed host of the
     *  page that now owns this view's icon stream. */
    @JvmStatic
    fun pinViewHost(view: android.webkit.WebView?, url: String?) {
        if (view == null) return
        val h = try { Fx.hostOf(url ?: "") } catch (t: Throwable) { return }
        synchronized(viewHosts) {
            // v1.3.81 - THE RACE GUARD. The v1.3.78 pin fixed icons landing
            // after a navigation COMMITS, but not the mirror race: the
            // OUTGOING page's own icon request (still in flight when the
            // user clicked away) landing just after the NEW page's start is
            // stamped with the NEW pin and poisons that host's slot
            // (google's G filed under z.ai - and then domPending() sees "an
            // icon exists" so the DOM extractor never runs to fix it). A
            // host CHANGE opens a 700 ms drop window; the new page's icon
            // arrives later (or via the DOM extractor at finish).
            val old = viewHosts[view]
            if (old != null && old.isNotEmpty() && h.isNotEmpty() && old != h) {
                viewRaceUntil[view] = android.os.SystemClock.uptimeMillis() + 700L
            }
            if (h.isEmpty()) viewHosts.remove(view) else viewHosts[view] = h
        }
    }

    /** v1.3.81 - true while an icon landing on this view is presumed to
     *  belong to the OUTGOING page (inside the race window after a host
     *  change). onReceivedIcon checks this before storing. */
    @JvmStatic
    fun inRaceWindow(view: android.webkit.WebView?): Boolean {
        if (view == null) return false
        val until = viewRaceUntil[view] ?: return false
        return android.os.SystemClock.uptimeMillis() < until
    }

    /** the pinned host for a view ("" when unknown) — the attribution
     *  source for onReceivedIcon. */
    @JvmStatic
    fun pinnedHost(view: android.webkit.WebView?): String =
            if (view == null) "" else viewHosts[view] ?: ""

    /** v1.3.78 — the generation every icon landing bumps; surfaces that
     *  show favicons poll it (TabsActivity) so a background fetch rebinds
     *  the open switcher — homeGen rebuilds the home view only. */
    @Volatile var favGen = 0
        private set

    /** the Java-facing reader (TabsActivity's poller) — an @JvmStatic
     *  getter for [favGen]. */
    @JvmStatic fun gen(): Int = favGen

    /** v1.3.53 — THE AGGRESSIVE-RELOAD FIX. A landed favicon means the
     *  home view should rebuild when it is next SHOWN (or on resume while
     *  the home is the visible tab) — that is all. v1.3.39–v1.3.52 bumped
     *  Fx.skinGen here "so the browser rebuilds its home", but skinGen is
     *  the BrowserActivity RECREATE channel: every favicon capture armed a
     *  full activity recreate on the next pause+resume — and a recreate
     *  destroys every tab's WebView (onCreate → destroyStaleWebViews) and
     *  reloads the page. Favicons land CONSTANTLY while browsing (and the
     *  DuckDuckGo network fallback lands them from a background thread at
     *  any time), so returning from settings, switching apps or peeking
     *  recents reloaded the page almost every time — and a login page's
     *  OWN favicon armed the recreate that the password-manager sheet's
     *  pause+resume then fired MID-LOGIN ("reloads when I try to login
     *  using saved passwords"). homeGen can never recreate anything. */
    @JvmStatic
    fun bump(c: Context) {
        Fx.homeGen++
        favGen++
    }

    private fun dir(c: Context): File = File(Prefs.get(c).filesDir(), "favicons")

    /** app context for the LRU read path (set at first store/load; the
     * executor + caches outlive any one activity). */
    @Volatile private var CTX: Context? = null

    private fun name(host: String): String =
            android.util.Base64.encodeToString(host.toByteArray(),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) + ".png"

    private fun missName(host: String): String = name(host) + ".miss"

    /** persist a page icon (called from onReceivedIcon; background-safe).
     *  v1.3.79: EVERYTHING file-shaped now rides the background executor —
     *  onReceivedIcon lands on the MAIN thread and used to decode the old
     *  icon + PNG-compress + write right there (10–50 ms per icon, several
     *  icons per page = the "presses take a second to register" family). */
    @JvmStatic
    fun store(c: Context, host: String?, icon: Bitmap?) {
        if (host.isNullOrEmpty() || icon == null) return
        try {
            val appc = c.applicationContext
            CTX = appc
            // v1.3.81: FULL-HOST key (docs.google.com keeps its own forms
            // mark instead of fighting www.google.com over one slot).
            val h = normalize(host)
            if (h.isEmpty()) return
            net.execute { storeLocked(appc, h, icon) }
        } catch (t: Throwable) { }
    }

    /** the synchronous body (executor-only). [icon] is already safe to read
     *  from this thread (Chromium hands over a frozen bitmap).
     *  v1.3.95 — OWNERSHIP CONTRACT: returns the bitmap the mem LRU now
     *  holds for [h] (the [icon] itself when it was stored as-is), or
     *  null when nothing was kept. The caller must recycle ONLY what
     *  this did NOT keep — the v1.3.94 network fallback recycled the very
     *  object it had just cached (mem[h] === icon for every icon <= MAX
     *  px), and the next avatar() drew the corpse: the v1.3.94 field
     *  crash "Canvas: trying to use a recycled bitmap" on home builds
     *  (opening new tabs) until the LRU evicted the host. */
    private fun storeLocked(c: Context, h: String, icon: Bitmap): Bitmap? {
        try {
            val f = File(dir(c), name(h))
            if (f.exists()) {
                // v1.3.40: upgrade when the new capture is significantly
                // bigger (2× in area) — no more first-seen-wins-forever.
                if (icon.width < 32 || icon.width * icon.height <= 32 * 32) return null
                val old = BitmapFactory.decodeFile(f.path) ?: return null
                if (icon.width * icon.height <= old.width * old.height * 2) {
                    try { old.recycle() } catch (ignored: Throwable) {}
                    return null
                }
                try { old.recycle() } catch (ignored: Throwable) {}
            }
            // v1.3.95: aspect-true downscale (the old MAX×MAX stretch
            // distorted every tall/wide icon on its way to disk)
            val bmp = if (icon.width <= MAX && icon.height <= MAX) icon
                else {
                    val s = minOf(MAX.toFloat() / icon.width, MAX.toFloat() / icon.height)
                    Bitmap.createScaledBitmap(icon,
                            (icon.width * s).toInt().coerceAtLeast(1),
                            (icon.height * s).toInt().coerceAtLeast(1), true)
                }
            dir(c).mkdirs()
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir(c), missName(h)).delete()   // a real icon clears the miss marker
            mem[h] = bmp
            // v1.4.13 — the LRU sweep rides the same executor thread as the
            // write: every save re-examines the budget, so an over-cap dir
            // (years of growth predating this law, or a restore) is trimmed
            // on the FIRST store after it — no timer, no cold-start pass.
            try { evictOverBudgetLocked(dir(c), f) } catch (ignored: Throwable) {}
            // v1.3.95 — THE COMPOUND-KEY FIX: tileCache keys are
            // "host|sizeDp|radiusDp|mode"; remove(h) never matched one, so
            // an icon landing from the network fallback never refreshed
            // the letter tiles composed before it existed.
            try {
                synchronized(tileCache) {
                    val it = tileCache.entries.iterator()
                    while (it.hasNext()) if (it.next().key.startsWith("$h|")) it.remove()
                }
            } catch (ignored: Throwable) {}
            bump(c)
            return bmp
        } catch (t: Throwable) { return null }
    }

    // ---------------- v1.4.13: the LRU eviction law ----------------

    /**
     * PURE selection (no Android, no filesystem mutation — the gate
     * transcribes + runs this exact body): given the icon files with their
     * sizes, decide which to DELETE so the survivors fit both caps.
     * Rules:
     *  - [keep] (the file this store just wrote) is NEVER a candidate — it
     *    carries the NEWEST stamp by construction anyway, but the law is
     *    explicit, not incidental;
     *  - candidates sort oldest-stamp FIRST (LRU: lastModified is the use
     *    stamp — load() refreshes it, store() refreshes it);
     *  - delete in that order until the count is <= maxIcons AND the total
     *    size is <= maxBytes — BOTH caps must hold when the sweep stops;
     *  - an already-under-budget dir selects nothing.
     */
    fun selectEvictions(
        files: List<Pair<Long, Long>>,   // (lastModified, size) per file
        keepIdx: Int,
        maxIcons: Int,
        maxBytes: Long
    ): List<Int> {
        var count = 0
        var total = 0L
        for (i in files.indices) {
            count++
            total += files[i].second
        }
        if (count <= maxIcons && total <= maxBytes) return emptyList()
        val order = files.indices
            .filter { it != keepIdx }
            .sortedBy { files[it].first }
        val out = ArrayList<Int>()
        for (i in order) {
            if (count <= maxIcons && total <= maxBytes) break
            out.add(i)
            count--
            total -= files[i].second
        }
        return out
    }

    /** the disk-side sweep (executor thread only, hence Locked): list the
     *  icon .png files (miss markers are neither counted nor evicted — they
     *  are 0-byte backoff flags, not icons), select, delete, and drop the
     *  memory entries of everything deleted so avatar() never serves a
     *  corpse. */
    private fun evictOverBudgetLocked(dir: File, keep: File) {
        val fs = dir.listFiles() ?: return
        val icons = fs.filter { it.isFile && it.name.endsWith(".png") && !it.name.endsWith(".miss") }
        if (icons.isEmpty()) return
        val stamps = icons.map { Pair(try { it.lastModified() } catch (t: Throwable) { 0L },
                try { it.length() } catch (t: Throwable) { 0L }) }
        val keepIdx = icons.indexOfFirst { it.path == keep.path }
        for (idx in selectEvictions(stamps, keepIdx, MAX_ICONS, MAX_BYTES)) {
            val victim = icons[idx]
            try { victim.delete() } catch (ignored: Throwable) {}
            val host = hostOf(victim)
            if (host != null) {
                try { mem.remove(host) } catch (ignored: Throwable) {}
                try {
                    synchronized(tileCache) {
                        val it = tileCache.entries.iterator()
                        while (it.hasNext()) if (it.next().key.startsWith("$host|")) it.remove()
                    }
                } catch (ignored: Throwable) {}
            }
        }
    }

    /** decode an icon file's name back to its host key (base64url, no
     * padding — see [name]); null when the name is not one of ours. */
    private fun hostOf(f: File): String? = try {
        val n = f.name
        if (!n.endsWith(".png")) null
        else {
            val b = android.util.Base64.decode(
                    n.removeSuffix(".png"),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                            or android.util.Base64.NO_PADDING)
            String(b, Charsets.UTF_8)
        }
    } catch (t: Throwable) { null }

    /** v1.3.79 — THE DOM-EXTRACTION LANDING (the universal favicon fix).
     *  The page itself rasterizes its own <link rel=icon> (SVG included —
     *  the browser's own SVG renderer draws it to a canvas, which no
     *  BitmapFactory can do; that was z.ai: its ONLY icon is logo.svg on a
     *  CDN subdomain, so every existing path came back empty) and hands us
     *  a PNG data URL. Runs on the bridge thread → straight to the executor. */
    @JvmStatic
    fun storePngDataUrl(c: Context, host: String?, dataUrl: String?) {
        if (host.isNullOrEmpty() || dataUrl.isNullOrEmpty()) return
        try {
            val i = dataUrl.indexOf("base64,")
            if (i < 0) return
            val b64 = dataUrl.substring(i + 7)
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null && bmp.width >= 16) store(c, host, bmp)
        } catch (t: Throwable) { }
    }

    /** the stored icon for a base domain, or null. v1.3.79: the LRU serves
     *  every hit after the first (the disk decode happens once per host per
     *  process now); a miss still schedules the network fallback.
     *  v1.4.13: a disk HIT refreshes the file's lastModified — the LRU use
     *  stamp. Once per host per process (the mem LRU takes over after), so
     *  the metadata write is bounded; without it, eviction order would be
     *  "oldest SAVED", deleting the site you visit every day. */
    @JvmStatic
    fun load(c: Context, host: String?): Bitmap? {
        if (host.isNullOrEmpty()) return null
        return try {
            CTX = c.applicationContext
            val h = normalize(host)
            if (h.isEmpty()) null
            else {
                // v1.3.95 — SELF-HEAL: never hand out a recycled entry (any
                // future poisoning path degrades to a disk re-decode
                // instead of a Canvas crash)
                mem[h]?.let { if (!it.isRecycled) return it
                    try { mem.remove(h) } catch (_: Throwable) {} }
                val f = File(dir(c), name(h))
                val b = if (!f.exists()) null else BitmapFactory.decodeFile(f.path)
                if (b != null) {
                    // v1.4.13 — the LRU use stamp: this disk hit (the first
                    // for this host this process) marks the file freshly
                    // USED, so the eviction sweep measures recency of READS,
                    // not recency of captures
                    try { f.setLastModified(System.currentTimeMillis()) } catch (ignored: Throwable) {}
                    mem[h] = b; b
                }
                else {
                    // v1.3.81: the baseDomain file is the legacy fallback
                    // (pre-v1.3.81 captures were keyed that way) before the
                    // network is asked anything.
                    val base = try { Fx.baseDomain(h) } catch (t: Throwable) { "" }
                    if (!base.isEmpty() && base != h) {
                        mem[base]?.let { if (!it.isRecycled) return it
                            try { mem.remove(base) } catch (_: Throwable) {} }
                        val fb = File(dir(c), name(base))
                        if (fb.exists()) {
                            val bb = BitmapFactory.decodeFile(fb.path)
                            if (bb != null) { mem[h] = bb; return bb }
                        }
                    }
                    fetchAsync(c, h)
                    null
                }
            }
        } catch (t: Throwable) { null }
    }

    /** favicon as a plain drawable (null when absent). v1.3.95: a recycled
     *  cache entry reads as absent (self-heal at the reader too). */
    @JvmStatic
    fun dr(c: Context, host: String?): Drawable? {
        val b = load(c, host)?.takeIf { !it.isRecycled } ?: return null
        return BitmapDrawable(c.resources, b)
    }

    // ---------------- v1.3.40: the network fallback ----------------

    private val net = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bolt-favicons").apply { isDaemon = true }
    }
    private val queued = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * Pull the official icon for a host. v1.3.79 chain: DuckDuckGo's service
     * first (proxies the real logos — Facebook's f, Google's G — at
     * zero-config), then the site's own /favicon.ico root file (plenty of
     * smaller sites serve one that the aggregators never indexed — this is
     * the GitHub-class fallback). Idempotent: one in-flight request per host;
     * a miss marker with a 7-day backoff keeps dead hosts off the hot path.
     * Results bump the generation so the home/tabs rebuild with the real logo.
     */
    @JvmStatic
    fun fetchAsync(c: Context, host: String?) {
        if (host.isNullOrEmpty()) return
        try {
            val h = normalize(host)
            if (h.isEmpty()) return
            if (File(dir(c), name(h)).exists()) return          // have it (exact host)
            // v1.3.81: a legacy baseDomain file also counts as "have it"
            // (load() already served it as the fallback - no network hit)
            val base = try { Fx.baseDomain(h) } catch (t: Throwable) { "" }
            if (!base.isEmpty() && base != h && File(dir(c), name(base)).exists()) return
            val miss = File(dir(c), missName(h))
            if (miss.exists()) {
                val at = try { miss.lastModified() } catch (t: Throwable) { 0L }
                if (System.currentTimeMillis() - at < 7L * 24 * 3600 * 1000) return
            }
            if (!queued.add(h)) return                          // in flight
            val appc = c.applicationContext
            net.execute {
                try {
                    // v1.3.81: exact host first (subdomain brand marks),
                    // then the base domain (the plain site mark). Each
                    // candidate stores under the host it was FETCHED FOR -
                    // the base-domain icon lands in the base slot where
                    // every legacy reader still finds it.
                    val hosts = if (!base.isEmpty() && base != h) arrayOf(h, base) else arrayOf(h)
                    val candidates = ArrayList<Pair<String, String>>() // url -> store key
                    for (hh in hosts) {
                        candidates.add(Pair("https://icons.duckduckgo.com/ip3/" + hh + ".ico", hh))
                        candidates.add(Pair("https://" + hh + "/favicon.ico", hh))
                    }
                    var got = false
                    for (cand in candidates) {
                        if (got) break
                        try {
                            val url = URL(cand.first)
                            val con = url.openConnection() as HttpURLConnection
                            con.connectTimeout = 6000
                            con.readTimeout = 6000
                            con.instanceFollowRedirects = true
                            try {
                                val bytes = con.inputStream.use { s -> s.readBytes() }
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null && bmp.width >= 16) {
                                    val kept = storeLocked(appc, cand.second, bmp)
                                    // v1.3.95 — THE OWNERSHIP LAW: storeLocked
                                    // may have cached THIS object in the mem LRU;
                                    // recycling it here poisoned the cache and
                                    // crashed the next home build (v1.3.94 field:
                                    // "Canvas: trying to use a recycled bitmap"
                                    // opening new tabs). Recycle only what the
                                    // store did NOT keep.
                                    if (kept !== bmp) {
                                        try { bmp.recycle() } catch (ignored: Throwable) { }
                                    }
                                    got = true
                                }
                            } finally {
                                con.disconnect()
                            }
                        } catch (t: Throwable) { /* next candidate */ }
                    }
                    if (!got) markMiss(miss)
                } catch (t: Throwable) {
                    try { markMiss(miss) } catch (ignored: Throwable) { }
                } finally {
                    queued.remove(h)
                }
            }
        } catch (t: Throwable) { }
    }

    /** create/refresh a host's miss marker (now = start of the 7-day
     *  backoff window). */
    private fun markMiss(f: File) {
        try {
            f.parentFile?.mkdirs()
            if (!f.exists()) f.createNewFile()
            f.setLastModified(System.currentTimeMillis())
        } catch (t: Throwable) { }
    }

    // ---------------- v1.3.40: aspect-true brand tiles ----------------

    /** average color + luminance of a favicon's OPAQUE pixels. */
    private class Stats(val color: Int, val lum: Float, val transparent: Boolean)

    private fun stats(b: Bitmap): Stats {
        try {
            if (b.isRecycled) return Stats(0xFF3E3E4A.toInt(), 0.3f, true)
            val w = b.width; val h = b.height
            var r = 0L; var g = 0L; var bl = 0L; var n = 0
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            for (p in px) {
                if (android.graphics.Color.alpha(p) < 40) continue
                r += android.graphics.Color.red(p)
                g += android.graphics.Color.green(p)
                bl += android.graphics.Color.blue(p)
                n++
            }
            if (n == 0) return Stats(0xFF3E3E4A.toInt(), 0.3f, true)
            val rc = (r / n).toInt(); val gc = (g / n).toInt(); val bc = (bl / n).toInt()
            val lum = (0.299f * rc + 0.587f * gc + 0.114f * bc) / 255f
            return Stats(android.graphics.Color.argb(255, rc, gc, bc), lum,
                    n < px.size * 3 / 10)      // <30% opaque → outline icon
        } catch (t: Throwable) {
            return Stats(0xFF3E3E4A.toInt(), 0.3f, false)
        }
    }

    /** blend a color toward black [toBlack] or white. */
    private fun shade(color: Int, toBlack: Boolean): Int {
        val f = if (toBlack) 0.78f else 1.22f
        val r = Math.min(255, Math.max(0, (android.graphics.Color.red(color) * f).toInt()))
        val g = Math.min(255, Math.max(0, (android.graphics.Color.green(color) * f).toInt()))
        val b = Math.min(255, Math.max(0, (android.graphics.Color.blue(color) * f).toInt()))
        return android.graphics.Color.argb(255, r, g, b)
    }

    /**
     * The TILE avatar for a host: its REAL favicon, aspect-preserved and
     * centered on a brand-colored rounded tile (the icon's own average
     * color, shaded for the current mode), or the gradient letter tile
     * when nothing is stored. Used by home shortcuts + tab cards + link
     * previews + the trash so every surface agrees on what a "site logo"
     * looks like.
     */
    /** v1.3.44: radiusDp overrides the skin's card radius — the home
     *  shortcut tiles sit inside a 48dp shell now and want a
     *  proportionate (smaller) corner, not the full card radius. */
    @JvmStatic
    @JvmOverloads
    fun avatar(c: Context, host: String?, fallbackText: String, sizeDp: Int, radiusDp: Int = -1): Drawable {
        // v1.3.79 — the composed-tile LRU: tab cards rebind on every poller
        // tick and every bind used to re-composite a fresh bitmap; the cache
        // key carries everything the composition depends on.
        val key = (host ?: "?") + "|" + sizeDp + "|" + radiusDp + "|" +
                (if (Prefs.get(c).isDark()) "d" else "l")
        tileCache[key]?.let { return it }
        val size = Fx.dp(c, sizeDp)
        // v1.3.95: never composite a corpse (belt over load()'s self-heal
        // — stats() swallowed the getPixels throw and then drawBitmap
        // crashed the home grid)
        val bmp = load(c, host)?.takeIf { !it.isRecycled }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val r = Fx.dp(c, if (radiusDp > 0) radiusDp else Fx.radius(c)).toFloat()
        if (bmp != null) {
            // ---- the real logo on its brand tile ----
            val st = stats(bmp)
            val dark = Prefs.get(c).isDark()
            val bg = Paint(Paint.ANTI_ALIAS_FLAG)
            // v1.3.78 — CONTRAST FIRST: an extreme-luminance icon on a tile
            // shaded from its OWN average is invisible (the GitHub report:
            // its favicon is a black circle, the tile came out black, and
            // the user saw "just black circle"). Very dark icons ride a
            // LIGHT tile, very light ones a DARK tile, in BOTH modes — the
            // launcher trick. Mid-luminance icons keep the brand shading.
            bg.color = when {
                st.transparent -> if (dark) 0xFF1E1E28.toInt() else 0xFFF2F2F6.toInt()
                st.lum < 0.16f -> if (dark) 0xFFE1E1EA.toInt() else 0xFFF4F4F8.toInt()
                st.lum > 0.85f -> if (dark) 0xFF23232E.toInt() else 0xFF23232E.toInt()
                else -> shade(st.color, dark)
            }
            cv.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, bg)
            // aspect-preserved fit into the inner 74% box — no stretching
            val box = size * 0.74f
            val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
            val s = Math.min(box / bw, box / bh)
            val left = (size - bw * s) / 2f
            val top = (size - bh * s) / 2f
            cv.drawBitmap(bmp, null,
                    RectF(left, top, left + bw * s, top + bh * s),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            // ---- gradient letter tile (the fallback of last resort) ----
            val hue = Math.abs((host ?: fallbackText ?: "").hashCode()) % 360
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = RadialGradient(size / 2f, size / 3f, size * 0.9f,
                    Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.50f, 0.58f)),
                    Color.HSVToColor(floatArrayOf(((hue + 22) % 360).toFloat(), 0.55f, 0.42f)),
                    Shader.TileMode.CLAMP)
            cv.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, paint)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG)
            tp.color = Color.WHITE
            tp.textSize = size * 0.44f
            tp.typeface = Ui.medium()
            tp.textAlign = Paint.Align.CENTER
            val base = Fx.dp(c, sizeDp) / 2f
            val fm = tp.fontMetrics
            cv.drawText(letterOf(fallbackText), base,
                    base - (fm.ascent + fm.descent) / 2f, tp)
        }
        val dr = BitmapDrawable(c.resources, out)
        try { tileCache[key] = dr } catch (ignored: Throwable) {}
        return dr
    }

    /** first letter (or '?') — mirrors BrowserActivity's letterOf. */
    private fun letterOf(s: String?): String {
        val t = (s ?: "").trim()
        if (t.isEmpty()) return "?"
        return t.substring(0, 1).uppercase()
    }

    /** a tiny circular badge shell for favicons pinned over thumbnails. */
    @JvmStatic
    fun badgeBg(c: Context): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(0xF2161622.toInt())
        g.setStroke(Math.max(1, Fx.dp(c, 1)), 0x33FFFFFF.toInt())
        return g
    }
}
