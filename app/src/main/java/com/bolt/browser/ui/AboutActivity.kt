package com.bolt.browser.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import com.bolt.browser.R
import com.bolt.browser.util.Fx
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * v1.4.0 — ABOUT (the version-milestone ask: "it is time to move to v1.4.0.
 * To celebrate, add an about section of the app in the browser settings with
 * small info, version, and update checker").
 * v1.4.1 — the page stops being empty: the simplified CHANGELOG (one line
 * per recent release, the string-array carries it) and the Android-style
 * EASTER EGG — tap the version row SEVEN times (a countdown toast after the
 * third, exactly like the developer-options door) to unlock Bolt Dash, a
 * tiny offline infinite-RNG game (BoltEggActivity, pure Canvas, no assets,
 * no sounds — a few KB in the dex, the APK budget law holds).
 *
 * A BaseListActivity screen (the app's standard row family):
 *  - the app name + tagline row, the version chip read LIVE from the
 *    package (never hardcoded — the v1.3.95 label law: what the user reads
 *    must be what shipped);
 *  - the ENGINE row (the real WebView/Chromium package version — a browser's
 *    honest "small info" is its engine);
 *  - THE UPDATE CHECKER: taps compare the installed version against the
 *    PUBLIC mirror's latest release (api.github.com anonymous — the private
 *    repo is unreachable from the field, the mirror is the download source
 *    of record), version-true (component-wise tag math, never string
 *    compare: 1.4.0 > 1.3.100), and on a find the row hands the APK's
 *    download URL to Bolt ITSELF (ACTION_VIEW — Bolt's own download engine
 *    fetches the update, the flow never leaves the app);
 *  - THE CHANGELOG section: "Recent updates" — the newest 9 releases, one
 *    line each (the full notes stay on the releases page row).
 */
class AboutActivity : BaseListActivity() {

    companion object {
        /** the PUBLIC mirror (anonymous downloads; the private repo stays
         *  private — releases mirror here per the README's pipeline) */
        const val RELEASES_API =
            "https://api.github.com/repos/arfan1210/bolt-browser-releases/releases/latest"
        const val RELEASES_PAGE =
            "https://github.com/arfan1210/bolt-browser-releases/releases"

        /** "v1.4.0" -> [1,4,0]; null when unparseable (never crashes on a
         *  malformed tag — the check just reports "cannot compare"). */
        fun tagParts(tag: String?): IntArray? {
            if (tag == null) return null
            var s = tag.trim()
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            val parts = s.split("\\.".toRegex())
            if (parts.isEmpty()) return null
            val out = IntArray(parts.size)
            for (i in parts.indices) {
                val p = parts[i].toIntOrNull() ?: return null
                out[i] = p
            }
            return out
        }

        /** component-wise newer-ness: [1,4,0] > [1,3,100] (a string compare
         *  would say "1.4.0" < "1.3.100" — the exact corpse class the label
         *  law exists for). */
        fun isNewer(candidate: IntArray?, current: IntArray?): Boolean {
            if (candidate == null || current == null) return false
            val n = maxOf(candidate.size, current.size)
            for (i in 0 until n) {
                val c = if (i < candidate.size) candidate[i] else 0
                val k = if (i < current.size) current[i] else 0
                if (c != k) return c > k
            }
            return false
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    /** the update checker's live state: null = idle/never checked */
    private var checkState: String? = null
    /** the .apk download URL when an update was found (null otherwise) */
    private var apkUrl: String? = null

    /** v1.4.1 — THE EASTER EGG TAP COUNTER (the Android developer-options
     * door, verbatim): the version row counts taps; after the third a toast
     * counts down the remainder; the seventh opens the game. Taps more than
     * 4 s apart reset the count (an egg unlocked by accident spread over an
     * hour is a bug, not an egg). */
    private var eggTaps = 0
    private var eggLastTap = 0L
    /** v1.4.2 — ONE live countdown toast: every tap used to enqueue a NEW
     *  2-second toast, so a quick 3-4-5-6 flurry played back as a stale
     *  countdown QUEUE for eight seconds after the taps — "quick tap does
     *  not register as individual taps". Android's own dev-options door
     *  updates its countdown in place; cancel() + show() on one instance
     *  does the same here: each tap repaints the count NOW. */
    private var eggToast: android.widget.Toast? = null

    override fun title(): String = getString(R.string.about_bolt)

    override fun build() {
        val rows = ArrayList<Row>()

        // ---- the app row: name, tagline, the LIVE version chip ----
        var versionName = "?"
        var versionCode: Long = -1
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            versionName = pi.versionName ?: "?"
            versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (ignored: Throwable) {}
        val app = Row()
        app.icon = R.drawable.ic_bars
        app.title = getString(R.string.app_name)
        app.sub = getString(R.string.about_tagline)
        app.value = versionName + if (versionCode > 0) " ($versionCode)" else ""
        app.chevron = false
        app.hint = "What you are running, read live from the installed " +
                "package — the version the update checker compares against."
        // v1.4.1 — the version row is ALSO the easter-egg door: 7 taps
        // unlock Bolt Dash (Android taps its version row for the dev-options
        // egg — the lineage is the point). The row shows no chevron and no
        // other affordance: an egg is found, not announced.
        app.click = View.OnClickListener { eggTap() }
        rows.add(app)

        // ---- the engine row: the real WebView/Chromium version ----
        var engine = "Android WebView"
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val pkg = android.webkit.WebView.getCurrentWebViewPackage()
                if (pkg != null) engine = "Chromium " + pkg.versionName
            }
        } catch (ignored: Throwable) {}
        val eng = Row()
        eng.icon = R.drawable.ic_globe
        eng.title = getString(R.string.engine_version)
        eng.value = engine
        eng.chevron = false
        eng.hint = "The system WebView's own version — the engine that " +
                "renders every page. Updates arrive with the WebView, " +
                "not with Bolt."
        rows.add(eng)

        // ---- the update checker row ----
        val upd = Row()
        upd.icon = R.drawable.ic_reload
        upd.title = getString(R.string.check_for_updates)
        upd.sub = checkState ?: ("Compares with the latest release on the " +
                "public mirror and downloads the APK in Bolt when one is found.")
        upd.hint = "Reads the public releases mirror anonymously and " +
                "compares version numbers component-wise (1.4.0 > 1.3.100). " +
                "Nothing is sent; no account, no token."
        if (apkUrl != null) {
            upd.title = getString(R.string.update_available, checkState ?: "")
            upd.click = View.OnClickListener { openInBolt(apkUrl!!) }
        } else {
            upd.click = View.OnClickListener { checkForUpdates() }
        }
        rows.add(upd)

        // ---- v1.4.1 — the simplified changelog ("the about page seems a bit
        // too empty, show a simplified update/change log there too"): one row
        // per recent release from the about_changelog string-array, newest
        // first, "version|summary" parsed on the pipe. The first row carries
        // the section label; the full history stays one row below.
        try {
            val entries = resources.getStringArray(R.array.about_changelog)
            var first = true
            for (e in entries) {
                val cut = e.indexOf('|')
                if (cut <= 0) continue
                val r = Row()
                r.icon = 0
                r.title = "v" + e.substring(0, cut)
                r.sub = e.substring(cut + 1)
                r.chevron = false
                r.click = null
                if (first) {
                    r.section = getString(R.string.recent_updates)
                    r.hint = getString(R.string.changelog_hint)
                    first = false
                }
                rows.add(r)
            }
        } catch (ignored: Throwable) {}

        // ---- the releases page row ----
        val src = Row()
        src.icon = R.drawable.ic_download
        src.title = getString(R.string.get_update)
        src.sub = "github.com/arfan1210/bolt-browser-releases"
        src.click = View.OnClickListener { openInBolt(RELEASES_PAGE) }
        src.hint = "The public release notes and every past APK — opens in Bolt."
        rows.add(src)

        setRows(rows)
    }

    private fun openInBolt(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            Fx.toast(this, getString(R.string.update_check_failed))
        }
    }

    /** v1.4.1 — the easter-egg door: count the version-row taps; toast the
     * countdown from the third; the seventh opens Bolt Dash.
     * v1.4.2 — the countdown is ONE toast, cancelled and reshown per tap
     * (the countdown tracks the finger, never a queue of stale toasts). */
    private fun eggTap() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - eggLastTap > 4000) eggTaps = 0
        eggLastTap = now
        eggTaps++
        if (eggTaps >= 7) {
            eggTaps = 0
            try {
                eggToast?.cancel()
                eggToast = null
                Fx.toast(this, getString(R.string.egg_found))
                startActivity(Intent(this, BoltEggActivity::class.java))
            } catch (t: Throwable) {
                Fx.toast(this, getString(R.string.update_check_failed))
            }
        } else if (eggTaps >= 3) {
            try {
                eggToast?.cancel()
                eggToast = android.widget.Toast.makeText(applicationContext,
                        getString(R.string.egg_taps_left, 7 - eggTaps),
                        android.widget.Toast.LENGTH_SHORT)
                eggToast?.show()
            } catch (ignored: Throwable) {}
        }
    }

    /** the anonymous check against the public mirror; runs on the io
     *  executor, repaints the row on the UI thread. */
    private fun checkForUpdates() {
        if (checkState == getString(R.string.checking)) return
        checkState = getString(R.string.checking)
        apkUrl = null
        build()
        io.execute {
            var found: String? = null
            var apk: String? = null
            var fail = false
            var conn: HttpURLConnection? = null
            try {
                val u = URL(RELEASES_API)
                conn = u.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val rel = JSONObject(body)
                    val tag = rel.optString("tag_name", "")
                    found = tag
                    val assets = rel.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.optJSONObject(i) ?: continue
                            val n = a.optString("name", "")
                            if (n.endsWith(".apk")) {
                                apk = a.optString("browser_download_url", "")
                                break
                            }
                        }
                    }
                } else fail = true
            } catch (t: Throwable) {
                fail = true
            } finally {
                try { conn?.disconnect() } catch (ignored: Throwable) {}
            }
            val current = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (ignored: Throwable) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (found == null) {
                    checkState = null
                    if (fail) Fx.toast(this, getString(R.string.update_check_failed))
                } else if (isNewer(tagParts(found), tagParts(current))) {
                    checkState = found
                    apkUrl = if (apk.isNullOrEmpty()) null else apk
                    Fx.toast(this, getString(R.string.update_available, found!!))
                } else {
                    checkState = null
                    apkUrl = null
                    Fx.toast(this, getString(R.string.up_to_date))
                }
                build()
            }
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
