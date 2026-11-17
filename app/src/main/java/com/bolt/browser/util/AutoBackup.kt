package com.bolt.browser.util

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v1.3.40 — SCHEDULED AUTO-BACKUP (round-4 menu item 14).
 *
 * Opt-in weekly export of the (toggleable) backup set to the public
 * Downloads folder with a stamped/versioned filename, plus the
 * "last backup" record the settings screen shows.
 *
 * The zip contents are EXACTLY what BackupActivity exports (the same
 * component toggles, the same plain-JSON-by-design rule) — this object
 * is now the single implementation; the screen and the scheduler both
 * call backupNow().
 *
 * Scheduling model: no WorkManager/alarm (battery-hostile on the OEM
 * killers this browser fights). BrowserActivity.onStart pings maybeBackup()
 * a few seconds after launch; when 7+ days passed since the last backup a
 * fresh zip lands in Downloads. Missed weeks simply back up at the next
 * launch.
 */
object AutoBackup {

    private const val DAY_MS = 24L * 3600_000L
    private const val WEEK_MS = 7L * DAY_MS

    @JvmStatic
    fun enabled(c: Context): Boolean = Prefs.get(c).b("autoBackup", false)

    /** epoch ms of the last successful backup (manual or scheduled). */
    @JvmStatic
    fun lastAt(c: Context): Long = Prefs.get(c).l("lastBackupAt", 0L)

    /** true when the weekly toggle is on and a week has elapsed. */
    @JvmStatic
    fun due(c: Context): Boolean {
        if (!enabled(c)) return false
        val last = lastAt(c)
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= WEEK_MS
    }

    /** the "Last backup" line for the settings row. */
    @JvmStatic
    fun lastBackupLine(c: Context): String {
        val last = lastAt(c)
        if (last <= 0L) return "Never"
        val name = Prefs.get(c).s("lastBackupName", "")
        val days = (System.currentTimeMillis() - last) / DAY_MS
        val whenText = when {
            days <= 0L -> "today"
            days == 1L -> "yesterday"
            else -> "$days days ago"
        }
        return if (name.isEmpty()) whenText else "$name · $whenText"
    }

    /**
     * Scheduler entry point (cheap on the calling thread): when due, run
     * the export on a worker thread. Returns true when a backup was started.
     */
    @JvmStatic
    fun maybeBackup(c: Context): Boolean {
        if (!due(c)) return false
        val app = c.applicationContext
        Thread({
            try {
                val name = backupNow(app, "BoltBackup-auto")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        Fx.toast(app, "Weekly backup saved: Downloads/$name")
                    } catch (ignored: Throwable) {}
                }
            } catch (ignored: Throwable) {}
        }, "auto-backup").start()
        return true
    }

    /**
     * Build + write the backup zip to the public Downloads folder
     * (MediaStore on API 29+, legacy path below). BLOCKING — call from a
     * worker thread. Returns the filename on success, throws on failure.
     * The component toggles ("bkSettings"…, "backupPw") decide what rides.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun backupNow(c: Context, prefix: String): String {
        val prefs = Prefs.get(c)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm",
                java.util.Locale.US).format(java.util.Date())
        val fname = "$prefix-$stamp.zip"

        var target: java.io.OutputStream? = null
        var uri: android.net.Uri? = null
        var legacyFile: File? = null
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues()
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname)
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                uri = c.contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                        ?: throw IllegalStateException("media store insert failed")
                target = c.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("media store open failed")
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                if (dir != null && !dir.exists()) dir.mkdirs()
                legacyFile = File(dir, fname)
                target = FileOutputStream(legacyFile)
            }

            ZipOutputStream(target).use { z ->
                // the same component set BackupActivity has shipped since
                // v1.3.39 ("choose what's backed up"). v1.3.94: bkTabs gains
                // workspaces.json + sessions.json + tabtrash.json — the
                // v1.3.93 field report ("no settings or workspace are here")
                // found the set had NEVER carried workspaces (or the session
                // snapshots / the closed-tab bin); they ride the tabs toggle.
                val parts = linkedMapOf(
                        "bkSettings" to arrayOf("prefs.json", "scriptState.json"),
                        "bkSites" to arrayOf("sites.json"),
                        "bkBookmarks" to arrayOf("bookmarks.json"),
                        "bkHistory" to arrayOf("history.json"),
                        "bkTabs" to arrayOf("groups.json", "tabs.json", "workspaces.json",
                                "sessions.json", "tabtrash.json"),
                        "bkSpeeddial" to arrayOf("speeddial.json"),
                        "bkWatchlater" to arrayOf("watchlater.json"),
                        "bkAuth" to arrayOf("authenticator.json"))
                for ((key, files) in parts) {
                    if (!prefs.b(key, true)) continue
                    for (f in files) {
                        val src = File(prefs.filesDir(), f)
                        if (src.exists()) addEntry(z, f, FileInputStream(src))
                    }
                }
                // passwords: only when the (default-off) opt-in switch is on;
                // plain JSON on purpose — portability beats encryption here
                if (prefs.b("backupPw", false)) {
                    try {
                        val pw = PasswordVault.exportPlain(c)
                        if (pw.length() > 0) {
                            val b = pw.toString().toByteArray(Charsets.UTF_8)
                            z.putNextEntry(ZipEntry("passwords.json"))
                            z.write(b)
                            z.closeEntry()
                        }
                    } catch (ignored: Throwable) {}
                }
                if (prefs.b("bkScripts", true)) zipDir(z, prefs.scriptsDir(), "scripts/")
                if (prefs.b("bkFilters", true)) zipDir(z, prefs.filtersDir(), "filters/")
            }

            // record the success (manual exports ride the same clock)
            prefs.set("lastBackupAt", System.currentTimeMillis())
            prefs.set("lastBackupName", fname)
            return fname
        } catch (t: Throwable) {
            // v1.3.93 — the orphan-row law (the P2 fix covered the other
            // three Downloads writers; this one was missed): a failed write
            // must leave NOTHING behind. Close, delete the MediaStore row /
            // the partial legacy file, then rethrow — success follows the
            // WRITE, never the insert.
            try { target?.close() } catch (_: Throwable) {}
            if (uri != null) {
                try { c.contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
            }
            if (legacyFile != null && legacyFile.exists()) {
                try { legacyFile.delete() } catch (_: Throwable) {}
            }
            throw t
        } finally {
            try { target?.close() } catch (ignored: Throwable) {}
        }
    }

    private fun zipDir(z: ZipOutputStream, dir: File, prefix: String) {
        val fs = dir.listFiles() ?: return
        for (f in fs) {
            if (f.isFile()) addEntry(z, prefix + f.name, FileInputStream(f))
        }
    }

    private fun addEntry(z: ZipOutputStream, name: String, ins: InputStream) {
        z.putNextEntry(ZipEntry(name))
        // v1.3.93: use{} closes the source even when the zip write throws
        // (the old shape leaked the FileInputStream on any failure path)
        ins.use {
            val b = ByteArray(8192)
            var n = it.read(b)
            while (n > 0) {
                z.write(b, 0, n)
                n = it.read(b)
            }
        }
        z.closeEntry()
    }
}
