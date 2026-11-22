package com.bolt.browser.util

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v1.3.38 — the PASSWORD VAULT (menu item 6 of the round-2 modernization
 * menu; new code → Kotlin per the README language rule).
 *
 * Model:
 *  - SECRETS live in filesDir/passwords.dat — the JSON array encrypted with
 *    an AndroidKeyStore AES-256-GCM key ("boltVaultKey", no
 *    setUserAuthenticationRequired so the vault also works via the plain
 *    KeyguardManager gate on devices without biometrics — the gate is a UI
 *    layer concern, exactly as the plan specified, no androidx dependency).
 *  - THE INDEX (host + user + addedAt, no secrets) lives beside it as plain
 *    passwords.index.json so the vault screen and the host lookup for the
 *    fill chip never need to touch the key. The password itself is only
 *    decrypted for fill / copy / reveal / backup.
 *
 * Backup policy (user-requested): the backup zip may include the DECRYPTED
 * vault — opt-in checkbox in BackupActivity, with a clear "plain JSON"
 * warning, mirroring the authenticator's portability rationale: a KeyStore
 * blob is useless on another device.
 */
object PasswordVault {

    private const val KS_ALIAS = "boltVaultKey"
    private const val ENC_FILE = "passwords.dat"
    private const val IDX_FILE = "passwords.index.json"

    // v1.3.95 — SAVE STAGE CODES (the field keeps reporting "could not
    // save - vault write failed" without a stage; the next report must
    // name WHICH stage refused). saveEx returns one of these; save() is
    // the boolean skin for old callers.
    const val SAVE_OK = 0
    const val SAVE_UNREADABLE = 1   // decrypt of the existing file failed twice
    const val SAVE_WRITE = 2        // the atomic write failed twice (keystore/io)
    const val SAVE_VERIFY = 3       // write landed but the read-back mismatched
    const val SAVE_INPUT = 4        // blank host/pass (caller bug, never in the field)

    class Entry(@JvmField val host: String, @JvmField val user: String,
                @JvmField val pass: String?, @JvmField val addedAt: Long) {
        val hasSecret: Boolean get() = pass != null
    }

    // ---------------------------------------------------------------- key

    /** v1.4.3 — the key fetch/generate, with the EVIDENCE the v1.3.91–95
     *  fixes lacked (every prior vault failure was diagnosed from stage
     *  codes alone — the throwable itself was swallowed): every refusal
     *  logs its stage + throwable class + message, so the next field
     *  report names the CAUSE, not just the neighborhood. Belt-and-braces
     *  for OEM keystore quirks: the modern getEntry() fetch falls back to
     *  the older getKey(); generation falls back 256 -> 192 -> 128 bits
     *  (some keymaster HALs refuse 256-bit AES — an OEM keystone quirk we
     *  cannot probe remotely, but can survive and name). */
    private fun key(c: Context): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val existing = ks.getEntry(KS_ALIAS, null) as? java.security.KeyStore.SecretKeyEntry
            if (existing != null) return existing.secretKey
        } catch (t: Throwable) {
            logKeyFail(c, "getEntry", t)
        }
        try {
            (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        } catch (t: Throwable) {
            logKeyFail(c, "getKey", t)
        }
        var last: Throwable? = null
        for (bits in intArrayOf(256, 192, 128)) {
            try {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(KeyGenParameterSpec.Builder(KS_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(bits)
                        .build())
                return gen.generateKey()
            } catch (t: Throwable) {
                last = t
                logKeyFail(c, "gen$bits", t)
            }
        }
        throw last ?: java.security.GeneralSecurityException("vault key unavailable")
    }

    private fun logKeyFail(c: Context, stage: String, t: Throwable) {
        try {
            Diag.log(c, "pw key $stage fail cls=" + t.javaClass.name.take(60)
                    + " msg=" + (t.message ?: "").take(80))
        } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- io

    private fun encFile(c: Context) = File(Prefs.get(c).filesDir(), ENC_FILE)
    private fun idxFile(c: Context) = File(Prefs.get(c).filesDir(), IDX_FILE)

    // v1.3.79 — decrypted-state cache (mtime-keyed): readAll decrypts with
    // an AndroidKeyStore key on EVERY call, and both callers live on the
    // main thread (the fill chip's find(), the menu's count()). The vault
    // file only changes through writeAll, which bumps its mtime.
    @Volatile private var decCache: JSONArray? = null
    @Volatile private var decCacheAt = 0L

    private fun readAll(c: Context): JSONArray {
        val f = encFile(c)
        if (!f.exists()) return JSONArray()
        try {
            val hit = decCache
            if (hit != null && f.lastModified() == decCacheAt) return hit
        } catch (_: Throwable) {}
        return try {
            val blob = f.readBytes()
            if (blob.size <= 12) return JSONArray()
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(c), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ct)
            val arr = JSONArray(String(plain, Charsets.UTF_8))
            try { decCache = arr; decCacheAt = f.lastModified() } catch (_: Throwable) {}
            // v1.3.92 — A SUCCESSFUL DECRYPT CLEARS THE FLAG. The v1.3.91
            // flag was one-way (set on any failure, cleared only by a
            // writeAll the refusal itself prevented): ONE transient
            // keystore hiccup — a slow KeyStore daemon under load, the
            // fill chip's find() racing a page load — permanently refused
            // every save ("could not save - vault write failed") until the
            // process restarted. The success path is the proof the vault
            // is readable; the flag must follow it.
            try { unreadable = false } catch (_: Throwable) {}
            arr
        } catch (t: Throwable) {
            // v1.4.3 — the decrypt failure names its CAUSE (class + message):
            // the v1.3.91 unreadable flag made the state observable, but the
            // throwable stayed swallowed — a stale key and a truncated file
            // read identically in the diag.
            try {
                Diag.log(c, "pw read fail cls=" + t.javaClass.name.take(60)
                        + " msg=" + (t.message ?: "").take(80))
            } catch (_: Throwable) {}
            // v1.3.91 — SURFACE the corpse instead of silently pretending
            // the vault is empty: a decrypt failure (key lost to a device
            // restore, a truncated file from a mid-write kill) used to
            // return an empty array, so the vault SCREEN showed the plain
            // index ("N passwords") while find() found nothing — the fill
            // chip never appeared and save() would OVERWRITE the whole
            // file with a single-entry array on the next login. The flag
            // makes the failure observable (diag + the vault screen row).
            try { unreadable = true } catch (_: Throwable) {}
            JSONArray()
        }
    }

    /** v1.3.91 — true when passwords.dat exists but cannot be decrypted
     *  (stale key / truncated file). The vault UI shows a warning row and
     *  save() refuses to clobber the file in this state. */
    @Volatile var unreadable: Boolean = false
        private set

    private fun writeAll(c: Context, arr: JSONArray): Boolean {
        // v1.3.91 — ATOMIC WRITE + honest result. The old body caught every
        // throwable silently ("never lose the old file because a new write
        // failed") but still let save() return TRUE — the user was told
        // "Login saved" while the vault write had thrown (a keystore
        // failure, a full disk). Now: the payload lands in a TEMP file
        // first and an atomic rename swaps it in (a mid-write kill can no
        // longer truncate the vault — the decrypt-corpse path above), and
        // the boolean tells the caller whether it actually stuck.
        // v1.4.3 — the throwable itself is LOGGED (cls + msg): the field
        // diag carried stage codes but never the cause.
        try { decCache = null; decCacheAt = 0L } catch (_: Throwable) {}
        return try {
            val plain = arr.toString().toByteArray(Charsets.UTF_8)
            // v1.4.3 — THE CALLER-IV CORPSE (the "vault write failed" that
            // shipped with the vault itself): the key is generated with
            // setRandomizedEncryptionRequired at its default (true), and
            // the AndroidKeyStore contract PROHIBITS caller-provided IVs in
            // ENCRYPT mode for such keys — the old
            // init(ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) threw
            // InvalidAlgorithmParameterException ("Caller-provided IV not
            // permitted") on real hardware, twice per save, so NO PASSWORD
            // EVER SAVED anywhere in the field (every diag said size=1 =
            // the vault was always empty; "save fails and autofill has
            // nothing" was ONE corpse since v1.3.38 — the v1.3.91/92/95
            // fixes hardened the verify/retry logic AROUND a write that
            // could never land, each verified by static gates that never
            // ran the cipher). THE LAW: for a keystore key, let the KEYSTORE
            // generate the IV (init with the key alone), then read cipher.iv
            // and prepend THAT. DECRYPT is the mirror image — the caller
            // MUST supply the stored IV — and that path was always correct.
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(c))
            val iv = cipher.iv                  // keystore-generated; 12 bytes for GCM
            if (iv.size != 12)
                throw IllegalStateException("unexpected keystore IV length ${iv.size}")
            val ct = cipher.doFinal(plain)
            val out = iv + ct
            val dir = encFile(c).parentFile
            val tmp = File(dir, ENC_FILE + ".tmp")
            FileOutputStream(tmp).use {
                it.write(out)
                try { it.fd.sync() } catch (_: Throwable) {}   // survive a mid-write kill
            }
            val target = encFile(c).toPath()
            try {
                java.nio.file.Files.move(tmp.toPath(), target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            unreadable = false
            writeIndex(c, arr)
            true
        } catch (t: Throwable) {
            try {
                Diag.log(c, "pw write fail cls=" + t.javaClass.name.take(60)
                        + " msg=" + (t.message ?: "").take(80))
            } catch (_: Throwable) {}
            false
        }
    }

    private fun writeIndex(c: Context, arr: JSONArray) {
        try {
            val idx = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val io = JSONObject()
                io.put("h", o.optString("h"))
                io.put("u", o.optString("u"))
                io.put("t", o.optLong("t", 0))
                idx.put(io)
            }
            FileOutputStream(idxFile(c)).use { it.write(idx.toString().toByteArray(Charsets.UTF_8)) }
        } catch (ignored: Throwable) {
        }
    }

    // ---------------------------------------------------------------- api

    /** v1.3.92 — exact-identity lookup (host AND user) for the save()
     *  round-trip verify. find(host) alone returns the FIRST row for a
     *  host — on a site with two saved accounts (or a blank-vs-detected
     *  username across logins) the verify compared against the OTHER
     *  account's password, false-failed, and toasted "vault write failed"
     *  over a write that actually landed. */
    private fun passFor(c: Context, host: String, user: String): String? {
        val arr = readAll(c)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("h") == host && o.optString("u") == user) return o.optString("p")
        }
        return null
    }

    /** host+user is the identity — an upsert, never a duplicate row.
     *  v1.3.91 — honest: false when the vault is unreadable (a stale
     *  keystore key would otherwise CLOBBER the file with one entry) or
     *  the verified write failed (see writeAll).
     *  v1.3.92 — TRANSIENT-FAILURE PROOFING (the field report:
     *  "some sites give 'could not save - vault write failed'"):
     *  (a) a set [unreadable] flag gets ONE fresh re-attempt (readAll
     *  never caches failures, and a SUCCESS now clears the flag — one
     *  transient keymaster hiccup no longer vetoes every later save);
     *  (b) the write and the round-trip verify each get one retry (a
     *  keystore/IO hiccup at either moment reported a save as failed
     *  when it had landed, or failed when one retry would have landed);
     *  (c) the verify is BY (host, user) — see [passFor].
     *  Only a failure that survives the retries is reported. */
    @JvmStatic
    fun save(c: Context, host: String, user: String, pass: String): Boolean =
            saveEx(c, host, user, pass) == SAVE_OK

    /** v1.3.95 — [save] with the refusal stage surfaced. Every refusing
     *  branch leaves a diag line ("pw save refused <stage> host=…") so
     *  the next field report names the stage instead of the umbrella
     *  toast; the retries now BACK OFF 150 ms (a keystore-daemon hiccup
     *  can outlive a 0 ms retry — the v1.3.92 fix retried instantly).
     *  Safe to call from any thread; the callers that matter now run it
     *  off the main thread (BrowserActivity.maybeOfferSavePw). */
    @JvmStatic
    fun saveEx(c: Context, host: String, user: String, pass: String): Int {
        if (host.isBlank() || pass.isEmpty()) {
            try { Diag.log(c, "pw save refused input host=${host.take(40)}") } catch (_: Throwable) {}
            return SAVE_INPUT
        }
        var arr = readAll(c)   // also refreshes `unreadable` for the guard below
        if (unreadable && encFile(c).exists()) {
            // one fresh re-attempt — readAll never caches failures, so this
            // is a REAL re-attempt, not a replay of the same corpse
            arr = readAll(c)
            if (unreadable) {
                // genuinely unreadable — never clobber. This is the only
                // stage with a USER recovery path: the vault screen's
                // reset (the toast points there now).
                try { Diag.log(c, "pw save refused unreadable host=${host.take(40)}" +
                        " size=${encFile(c).length()}") } catch (_: Throwable) {}
                return SAVE_UNREADABLE
            }
        }
        val now = System.currentTimeMillis()
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("h") == host && o.optString("u") == user) {
                o.put("p", pass)
                o.put("t", now)
                found = true
            }
        }
        if (!found) {
            val o = JSONObject()
            o.put("h", host)
            o.put("u", user)
            o.put("p", pass)
            o.put("t", now)
            arr.put(o)
        }
        if (!writeAll(c, arr)) {
            android.os.SystemClock.sleep(150)   // keystore hiccups outlive 0 ms retries
            if (!writeAll(c, arr)) {
                try { Diag.log(c, "pw save refused write host=${host.take(40)}" +
                        " size=${arr.length()}") } catch (_: Throwable) {}
                return SAVE_WRITE
            }
        }
        // round-trip verify (retried with backoff — a transient decrypt
        // failure here used to false-report a landed write as "could not
        // save"). v1.3.92b: BY (host, user), not find(host) — find returns
        // the FIRST row for a host, so a second account verified against
        // the OTHER password and false-failed over a write that landed.
        var ok = passFor(c, host, user) == pass
        if (!ok) {
            android.os.SystemClock.sleep(150)
            ok = passFor(c, host, user) == pass
        }
        if (!ok) {
            try { Diag.log(c, "pw save refused verify host=${host.take(40)}") } catch (_: Throwable) {}
            return SAVE_VERIFY
        }
        return SAVE_OK
    }

    /** the plain index (no secrets decrypted) — newest first. */
    @JvmStatic
    fun index(c: Context): List<Entry> {
        val f = idxFile(c)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(String(FileInputStream(f).use { it.readBytes() }, Charsets.UTF_8))
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Entry(o.optString("h"), o.optString("u"), null, o.optLong("t", 0)))
            }
            out.sortByDescending { it.addedAt }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    @JvmStatic
    fun count(c: Context): Int = index(c).size

    /** v1.3.91 — force a decrypt pass so [unreadable] is fresh (the
     *  index alone never touches the key). Cheap: one cached read. */
    @JvmStatic
    fun checkHealth(c: Context) {
        try { readAll(c) } catch (_: Throwable) {}
    }

    /** full entry (password decrypted) for a host — exact match first,
     *  then any saved login whose host shares the base domain.
     *  v1.3.91 — the fallback is now BIDIRECTIONAL base-domain equality
     *  (was one-sided endsWith: a login saved on "example.com" never
     *  matched a return visit to "login.example.com" because
     *  baseDomain("login.example.com") ends with "example.com" — but the
     *  mirror case, saved on "accounts.example.com" while visiting
     *  "example.com", silently failed the fill).
     *  v1.4.4 — ONE lookup, ALL rows: [find] now delegates to [findAll]
     *  (exact-host rows first, then base-domain rows, newest first inside
     *  each class) so a site with SEVERAL saved accounts fills the one the
     *  user last touched instead of whichever row happened to serialize
     *  first (the multi-account report: "it is unable to save multiple
     *  passwords for multiple accounts on the same site" — the saves were
     *  landing; the FILL only ever offered the first row). */
    @JvmStatic
    fun find(c: Context, host: String): Entry? = findAll(c, host).firstOrNull()

    /** v1.4.4 — EVERY login that matches [host]: exact-host rows first,
     *  then base-domain matches, newest first inside each class. This is
     *  the fill chip's source (the chip offers a picker when this returns
     *  more than one row) and the identity check's source (below). */
    @JvmStatic
    fun findAll(c: Context, host: String): List<Entry> {
        if (host.isBlank()) return emptyList()
        val arr = readAll(c)
        val exact = ArrayList<Entry>()
        val base = ArrayList<Entry>()
        val b = try { Fx.baseDomain(host) } catch (t: Throwable) { "" }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val h = o.optString("h")
            if (h == host) {
                exact.add(Entry(h, o.optString("u"), o.optString("p"), o.optLong("t", 0)))
            } else if (b.isNotEmpty()) {
                val hb = try { Fx.baseDomain(h) } catch (t: Throwable) { "" }
                if (hb == b || h.endsWith(".$b")) {
                    base.add(Entry(h, o.optString("u"), o.optString("p"), o.optLong("t", 0)))
                }
            }
        }
        exact.sortByDescending { it.addedAt }
        base.sortByDescending { it.addedAt }
        exact.addAll(base)
        return exact
    }

    /** v1.4.4 — TRUE ALREADY-SAVED CHECK for the save offer (the report:
     *  re-offering to save an account that is already stored, while a
     *  SECOND account for the same site must still be offered). The old
     *  guard compared against find(host) — the FIRST row for the host —
     *  so logging back into saved account #2 (or #1, whichever did not
     *  serialize first) re-offered on EVERY sign-in. The offer now skips
     *  only when the EXACT (host, user, pass) triple is already stored:
     *  every other combination is a genuinely new or updated login. */
    @JvmStatic
    fun savedAlready(c: Context, host: String, user: String, pass: String): Boolean {
        if (host.isBlank() || pass.isEmpty()) return false
        val arr = readAll(c)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("h") == host && o.optString("u") == user
                    && o.optString("p") == pass) return true
        }
        return false
    }

    @JvmStatic
    fun delete(c: Context, host: String, user: String) {
        var arr = readAll(c)
        // v1.3.93 — THE CLOBBER GUARD (the bug-hunt pass): readAll returns
        // an EMPTY array when the vault is unreadable, so deleting any row
        // while the flag was set wrote an EMPTY vault over the corpse —
        // total data loss from one delete tap on a vault whose only fault
        // was a transient keystore hiccup (the same clobber save() has
        // refused since v1.3.91; delete() never got the guard). One fresh
        // decrypt first (readAll never caches failures — a SUCCESS clears
        // the flag and the delete proceeds on the real rows); a failure
        // that survives the retry refuses the write, never clobbers.
        if (unreadable && encFile(c).exists()) {
            arr = readAll(c)
            if (unreadable) return
        }
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("h") == host && o.optString("u") == user) arr.remove(i)
        }
        writeAll(c, arr)
    }

    @JvmStatic
    fun deleteAll(c: Context) {
        try { unreadable = false } catch (_: Throwable) {}
        try { encFile(c).delete() } catch (ignored: Throwable) {}
        try { idxFile(c).delete() } catch (ignored: Throwable) {}
    }

    /** decrypted export for the legacy PLAIN backup zip entry (old zips
     *  only; new exports ride [exportEncrypted] — see the v1.4.28 law). */
    @JvmStatic
    fun exportPlain(c: Context): JSONArray = readAll(c)

    /** restore path: merge a decrypted backup array; returns how many
     *  logins were (re)saved. */
    @JvmStatic
    fun importPlain(c: Context, src: JSONArray): Int {
        if (src.length() == 0) return 0
        var n = 0
        for (i in 0 until src.length()) {
            val o = src.optJSONObject(i) ?: continue
            val h = o.optString("h")
            val u = o.optString("u")
            val p = o.optString("p")
            if (h.isBlank() || p.isEmpty()) continue
            if (save(c, h, u, p)) n++
        }
        return n
    }

    // ---------------- v1.4.28 — THE ENCRYPTED BACKUP EXPORT ----------------

    /** The v1.4.28 export format (the plaintext-zip corpse: the old
     *  "include saved passwords" toggle wrote the DECRYPTED vault as plain
     *  JSON into the zip — any file manager read every password). The
     *  passphrase export re-encrypts the vault with a key derived FROM THE
     *  PASSPHRASE THE USER TYPES AT EXPORT (never stored — not in prefs,
     *  not on disk), so the zip is portable to any device while staying
     *  ciphertext at rest:
     *      "BOLTPWV1" (8 bytes) | salt (16) | iv (12) | AES-256-GCM ct+tag
     *  key = PBKDF2-HMAC-SHA256(passphrase, salt, 120000 iters, 256-bit).
     *  Returns null when the vault is empty/unreadable or the crypto
     *  fails (never throws — the caller skips the passwords part). */
    @JvmStatic
    fun exportEncrypted(c: Context, passphrase: String): ByteArray? {
        return try {
            if (passphrase.length < 4) return null
            val plain = try {
                val arr = readAll(c)
                if (arr.length() == 0 || unreadable) return null
                arr.toString().toByteArray(Charsets.UTF_8)
            } catch (_: Throwable) { return null }
            val rnd = java.security.SecureRandom()
            val salt = ByteArray(16).also { rnd.nextBytes(it) }
            val spec = javax.crypto.spec.PBEKeySpec(
                    passphrase.toCharArray(), salt, 120_000, 256)
            val kf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val key = kf.generateSecret(spec)
            val aesKey = SecretKeySpec(key.encoded, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey)
            val iv = cipher.iv                       // fresh, 12 bytes for GCM
            if (iv.size != 12) return null
            val ct = cipher.doFinal(plain)
            val out = ByteArray(8 + 16 + 12 + ct.size)
            System.arraycopy(MAGIC, 0, out, 0, 8)
            System.arraycopy(salt, 0, out, 8, 16)
            System.arraycopy(iv, 0, out, 24, 12)
            System.arraycopy(ct, 0, out, 36, ct.size)
            out
        } catch (_: Throwable) { null }
    }

    /** The restore path for [exportEncrypted]'s blob: prompts are the
     *  caller's concern (BackupActivity gates the device credential + asks
     *  the passphrase). A wrong passphrase fails the GCM tag and returns
     *  -1 ("nothing decrypted" — distinguishable from 0 = empty vault).
     *  A successful decrypt merges through [importPlain] (every row
     *  re-encrypts under THIS device's keystore key). Never throws. */
    @JvmStatic
    fun importEncrypted(c: Context, blob: ByteArray, passphrase: String): Int {
        return try {
            if (blob.size < 36 + 16) return -1
            val magic = blob.copyOfRange(0, 8)
            if (!magic.contentEquals(MAGIC)) return -1
            val salt = blob.copyOfRange(8, 24)
            val iv = blob.copyOfRange(24, 36)
            val ct = blob.copyOfRange(36, blob.size)
            val spec = javax.crypto.spec.PBEKeySpec(
                    passphrase.toCharArray(), salt, 120_000, 256)
            val kf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val key = kf.generateSecret(spec)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.encoded, "AES"),
                    GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ct)   // AEADBadTagException -> -1 below
            importPlain(c, JSONArray(String(plain, Charsets.UTF_8)))
        } catch (_: Throwable) { -1 }
    }

    /** the export blob's magic header ("Bolt password vault v1"). */
    private val MAGIC = byteArrayOf(66, 79, 76, 84, 80, 87, 86, 49)
}
