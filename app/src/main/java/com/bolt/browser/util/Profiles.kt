package com.bolt.browser.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v1.3.39 — ADDRESS & CARD PROFILES (new code → Kotlin; menu-3 item 13).
 *
 * "autofill beyond passwords: name/address/card profiles (same vault
 * encryption + credential gate) offered on matching forms."
 *
 * Mirrors PasswordVault's exact storage model: AndroidKeyStore AES-256-GCM
 * (own alias boltProfileKey), whole store in profiles.dat; the fill flow
 * is a CHIP on detected forms (name/email/tel/address/cc-* autocomplete
 * or name heuristics) → picker → the MediaHook-side filler writes each
 * field with input/change events.
 */
object Profiles {

    private const val KS_ALIAS = "boltProfileKey"
    private const val ENC_FILE = "profiles.dat"

    /** the editable field set (order = the editor form order). */
    @JvmStatic
    val FIELDS = arrayOf("label", "name", "email", "phone", "org",
            "address", "address2", "city", "zip", "country",
            "card", "exp", "cvv")

    class Profile(val values: Map<String, String>) {
        val label: String get() = values["label"] ?: ""
        val name: String get() = values["name"] ?: ""
        val email: String get() = values["email"] ?: ""
        fun json(): JSONObject {
            val o = JSONObject()
            for (k in FIELDS) o.put(k, values[k] ?: "")
            return o
        }
    }

    // ---------------------------------------------------------------- key

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(KS_ALIAS, null) as? java.security.KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        return gen.generateKey()
    }

    private fun encFile(c: Context) = File(Prefs.get(c).filesDir(), ENC_FILE)

    private fun readAll(c: Context): JSONArray {
        val f = encFile(c)
        if (!f.exists()) return JSONArray()
        return try {
            val blob = f.readBytes()
            if (blob.size <= 12) return JSONArray()
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            JSONArray(String(cipher.doFinal(ct), Charsets.UTF_8))
        } catch (t: Throwable) { JSONArray() }
    }

    private fun writeAll(c: Context, arr: JSONArray) {
        try {
            val plain = arr.toString().toByteArray(Charsets.UTF_8)
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
            FileOutputStream(encFile(c)).use { it.write(iv + cipher.doFinal(plain)) }
        } catch (ignored: Throwable) { }
    }

    // ---------------------------------------------------------------- api

    // v1.3.79 — mtime cache (the 3-dot menu reads this on the main thread)
    @Volatile private var cache: List<Profile>? = null
    @Volatile private var cacheAt = 0L

    @JvmStatic
    fun list(c: Context): List<Profile> {
        try {
            val f = encFile(c)
            if (!f.exists()) { cache = null; return emptyList() }
            val hit = cache
            if (hit != null && f.lastModified() == cacheAt) return hit
        } catch (_: Throwable) {}
        val arr = readAll(c)
        val out = ArrayList<Profile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val m = LinkedHashMap<String, String>()
            for (k in FIELDS) m[k] = o.optString(k)
            out.add(Profile(m))
        }
        try { cache = out; cacheAt = encFile(c).lastModified() } catch (_: Throwable) {}
        return out
    }

    @JvmStatic
    fun count(c: Context): Int = list(c).size

    /** upsert by label. */
    @JvmStatic
    fun save(c: Context, p: Profile) {
        if (p.label.isBlank()) return
        val arr = readAll(c)
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("label") == p.label) {
                arr.put(i, p.json()); found = true
            }
        }
        if (!found) arr.put(p.json())
        writeAll(c, arr)
    }

    @JvmStatic
    fun delete(c: Context, label: String) {
        val arr = readAll(c)
        for (i in arr.length() - 1 downTo 0) {
            if (arr.optJSONObject(i)?.optString("label") == label) arr.remove(i)
        }
        writeAll(c, arr)
    }

    /** JS object literal of a profile for the page-side filler
     *  ({field:'value',…}) — the cvv is included: it sits in the same
     *  encrypted store, behind the same credential gate. */
    @JvmStatic
    fun fillJson(p: Profile): String {
        val sb = StringBuilder("{")
        for ((k, v) in p.values) {
            if (v.isBlank()) continue
            if (sb.length > 1) sb.append(',')
            sb.append(k).append(':').append(JSONObject.quote(v))
        }
        return sb.append('}').toString()
    }
}
