package com.bolt.browser.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bolt.browser.R
import com.bolt.browser.util.Fx
import com.bolt.browser.util.PasswordVault
import com.bolt.browser.util.Prefs
import com.bolt.browser.util.Ui

/**
 * v1.3.38 — the PASSWORD VAULT screen (menu item 6 of the round-2
 * modernization menu; new code → Kotlin per the README language rule).
 *
 * Gated by the device credential (KeyguardManager.confirmDeviceCredential —
 * no androidx.biometric dependency, exactly as planned; on devices with no
 * secure lock screen the gate is skipped and a warning banner shows). The
 * list renders from the plain index (host + user); the password itself is
 * decrypted only per action: fill chip, copy, or reveal.
 *
 * Entries: main menu → "Saved passwords" (main process only — identity
 * data never crosses into the incognito process) + the settings search index.
 */
class PasswordVaultActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var list: LinearLayout? = null
    private var emptyState: LinearLayout? = null
    private var gateShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Fx.theme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generic_list)
        prefs = Prefs.get(this)
        findViewById<TextView>(R.id.tv_g_title).text = "Saved passwords"
        findViewById<View>(R.id.rv_g).visibility = View.GONE
        findViewById<View>(R.id.btn_g_back).post {
            findViewById<android.widget.ImageButton>(R.id.btn_g_back).setColorFilter(Fx.dim(this))
        }
        findViewById<View>(R.id.btn_g_back).setOnClickListener { finish() }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = Fx.dp(this, 18)
        root.setPadding(pad, pad, pad, pad + Fx.dp(this, 12))
        val scroller = android.widget.ScrollView(this)
        scroller.setFillViewport(true)
        scroller.overScrollMode = View.OVER_SCROLL_NEVER
        scroller.addView(root, LinearLayout.LayoutParams(-1, -2))
        (findViewById<View>(R.id.btn_g_back).parent.parent as android.view.ViewGroup)
                .addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- master toggle: the save prompt ----
        root.addView(switchRow("Offer to save passwords",
                "Ask when you sign in to a site",
                prefs.b("pwSave", true)) { on -> prefs.set("pwSave", on) })

        // ---- device-credential note when there is no secure lock ----
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            val warn = TextView(this)
            warn.text = "No screen lock is set — set one (PIN / pattern / " +
                    "biometrics) to gate this vault behind the device credential."
            warn.textSize = 13f
            warn.setTextColor(0xFFB3261E.toInt())
            warn.setPadding(0, Fx.dp(this, 10), 0, 0)
            root.addView(warn, LinearLayout.LayoutParams(-1, -2))
        }

        list = LinearLayout(this)
        (list as LinearLayout).orientation = LinearLayout.VERTICAL
        root.addView(list, LinearLayout.LayoutParams(-1, -2))

        emptyState = Ui.emptyState(this, R.drawable.ic_lock,
                "No saved passwords", "Sign in to a site and accept the save prompt")
        root.addView(emptyState, LinearLayout.LayoutParams(-1, -2))

        rebuild()
    }

    override fun onResume() {
        super.onResume()
        if (!gateShown) showGate()
    }

    /** device-credential gate (once per visit; the fill chip in the browser
     *  is not re-gated — the user is mid-session there). */
    private fun showGate() {
        try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isDeviceSecure) return          // nothing to gate with
            gateShown = true
            val i = km.createConfirmDeviceCredentialIntent("Bolt is locked",
                    "Unlock to view saved passwords")
            if (i != null) startActivityForResult(i, 1013)
        } catch (ignored: Throwable) {
        }
    }

    private fun switchRow(title: String, sub: String, checked: Boolean,
            change: (Boolean) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Fx.cardBg(this, 0)
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 14), Fx.dp(this, 16), Fx.dp(this, 14))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Fx.dp(this, 4)
        row.layoutParams = lp
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = title
        t.textSize = 16f
        t.setTextColor(Fx.txt(this))
        col.addView(t, LinearLayout.LayoutParams(-1, -2))
        val s = TextView(this)
        s.text = sub
        s.textSize = 13f
        s.setTextColor(Fx.dim(this))
        col.addView(s, LinearLayout.LayoutParams(-1, -2))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Ui.modernSwitch(this, checked, Ui.OnToggle { on -> change(on) })
        row.addView(sw, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    private fun rebuild() {
        val host = list ?: return
        host.removeAllViews()
        PasswordVault.checkHealth(this)   // v1.3.91: refresh `unreadable`
        val entries = PasswordVault.index(this)
        // v1.3.91 — the unreadable-vault banner: the index says N but the
        // encrypted file cannot be decrypted (stale keystore key after a
        // device restore, or a truncated file from an old mid-write kill).
        // This is exactly the "asks to save, never fills" corpse — surfaced
        // here with the reset action instead of an empty-looking vault.
        // v1.3.95 — the reset now shows whenever the vault is unreadable,
        // EVEN WITH AN EMPTY INDEX (the old `entries.isNotEmpty()` gate hid
        // the only recovery path for a corpse whose derived index was empty
        // — saves refused forever with no way out).
        if (PasswordVault.unreadable) {
            val warn = TextView(this)
            warn.text = "The vault file can't be read with this device's key " +
                    "(often after restoring backups onto a new phone). Saved logins " +
                    "can't be filled until the vault is reset."
            warn.textSize = 13f
            warn.setTextColor(0xFFB3261E.toInt())
            warn.setPadding(0, Fx.dp(this, 10), 0, 0)
            val wp = LinearLayout.LayoutParams(-1, -2)
            host.addView(warn, wp)
            val reset = TextView(this)
            reset.text = "Reset vault (delete the unreadable file)"
            reset.textSize = 14f
            reset.typeface = android.graphics.Typeface.DEFAULT_BOLD
            reset.setTextColor(Fx.accentText(this))
            reset.setPadding(0, Fx.dp(this, 6), 0, Fx.dp(this, 6))
            reset.setOnClickListener {
                AlertDialog.Builder(this)
                        .setMessage("Delete the unreadable vault? The listed " +
                                "logins cannot be recovered on this device.")
                        .setPositiveButton(R.string.yes) { _, _ ->
                            PasswordVault.deleteAll(this)
                            rebuild()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
            }
            host.addView(reset, LinearLayout.LayoutParams(-1, -2))
        }
        emptyState?.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        for (e in entries) {
            host.addView(entryRow(e))
        }
    }

    private fun entryRow(e: PasswordVault.Entry): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Fx.cardBg(this, 0)
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 14), Fx.dp(this, 16), Fx.dp(this, 14))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Fx.dp(this, 4)
        row.layoutParams = lp

        val ic = android.widget.ImageView(this)
        ic.setImageResource(R.drawable.ic_lock)
        ic.setColorFilter(Fx.tint(this))
        Ui.iconify(ic, true)
        row.addView(ic, LinearLayout.LayoutParams(Fx.dp(this, 40), Fx.dp(this, 40)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = e.host
        t.textSize = 16f
        t.typeface = Ui.medium()
        t.setTextColor(Fx.txt(this))
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(t, LinearLayout.LayoutParams(-1, -2))
        val s = TextView(this)
        s.text = e.user.ifBlank { "(no username)" }
        s.textSize = 13f
        s.setTextColor(Fx.dim(this))
        s.maxLines = 1
        s.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(s, LinearLayout.LayoutParams(-1, -2))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        val chev = android.widget.ImageView(this)
        chev.setImageResource(R.drawable.ic_chevron)
        chev.setColorFilter(Fx.dim(this))
        row.addView(chev, LinearLayout.LayoutParams(Fx.dp(this, 18), Fx.dp(this, 18)))

        row.setOnClickListener { v ->
            Ui.tap(v)
            options(e)
        }
        return row
    }

    private fun options(e: PasswordVault.Entry) {
        val opts = arrayOf("Copy username", "Copy password",
                "Reveal password", "Delete")
        Ui.choiceSheet(this, e.host, opts, -1) { which ->
            when (which) {
                0 -> copy("Bolt username", e.user)
                1 -> {
                    val full = PasswordVault.find(this, e.host)
                    val pass = full?.pass
                    if (pass != null) copy("Bolt password", pass) else Fx.toast(this, "Not found")
                }
                2 -> reveal(e)
                3 -> confirmDelete(e)
            }
        }
    }

    private fun copy(label: String, text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            Fx.toast(this, getString(R.string.copied))
        } catch (ignored: Throwable) {
        }
    }

    private fun reveal(e: PasswordVault.Entry) {
        val full = PasswordVault.find(this, e.host)
        val pass = full?.pass
        if (pass == null) {
            Fx.toast(this, "Not found")
            return
        }
        val tv = TextView(this)
        tv.text = pass
        tv.textSize = 16f
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setTextColor(Fx.txt(this))
        val pad = Fx.dp(this, 22)
        tv.setPadding(pad, pad / 2, pad, 0)
        tv.setTextIsSelectable(true)
        AlertDialog.Builder(this)
                .setTitle(e.host)
                .setView(tv)
                .setPositiveButton(R.string.copy) { _, _ -> copy("Bolt password", pass) }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun confirmDelete(e: PasswordVault.Entry) {
        AlertDialog.Builder(this)
                .setMessage("Delete the saved login for " + e.host + "?")
                .setPositiveButton(R.string.delete) { _, _ ->
                    PasswordVault.delete(this, e.host, e.user)
                    rebuild()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    companion object {
        /** shared launch helper so future callers stay one-liners. */
        fun open(a: Activity) {
            a.startActivity(android.content.Intent(a, PasswordVaultActivity::class.java))
        }
    }
}
