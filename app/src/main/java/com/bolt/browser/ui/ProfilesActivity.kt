package com.bolt.browser.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bolt.browser.R
import com.bolt.browser.util.Fx
import com.bolt.browser.util.MenuCards
import com.bolt.browser.util.Prefs
import com.bolt.browser.util.Profiles
import com.bolt.browser.util.Ui

/**
 * v1.3.39 — the ADDRESS & CARD PROFILES editor (new code → Kotlin;
 * menu-3 item 13). The store is AndroidKeyStore-encrypted (Profiles.kt);
 * this screen manages it behind the device-credential gate like the
 * password vault. Forms detected on pages get a "Fill profile" chip.
 */
class ProfilesActivity : AppCompatActivity() {

    private var list: LinearLayout? = null
    private var emptyState: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Fx.theme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generic_list)
        findViewById<TextView>(R.id.tv_g_title).text = "Profiles"
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
        scroller.addView(root, LinearLayout.LayoutParams(-1, -2))
        (findViewById<View>(R.id.btn_g_back).parent.parent as android.view.ViewGroup)
                .addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        list = LinearLayout(this)
        (list as LinearLayout).orientation = LinearLayout.VERTICAL
        root.addView(list, LinearLayout.LayoutParams(-1, -2))

        emptyState = Ui.emptyState(this, R.drawable.ic_person,
                "No profiles", "Save a name/address/card profile to fill matching forms")
        root.addView(emptyState, LinearLayout.LayoutParams(-1, -2))

        val add = Ui.filled(this, "Add profile")
        add.setOnClickListener { Ui.tap(it); showEditor(null) }
        val alp = LinearLayout.LayoutParams(-1, Fx.dp(this, 48))
        alp.topMargin = Fx.dp(this, 14)
        root.addView(add, alp)
        rebuild()
    }

    private fun rebuild() {
        val host = list ?: return
        host.removeAllViews()
        val ps = Profiles.list(this)
        emptyState?.visibility = if (ps.isEmpty()) View.VISIBLE else View.GONE
        for (p in ps) host.addView(profileRow(p))
    }

    private fun profileRow(p: Profiles.Profile): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Fx.cardBg(this, 0)
        row.setPadding(Fx.dp(this, 16), Fx.dp(this, 13), Fx.dp(this, 16), Fx.dp(this, 13))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = p.label
        t.textSize = 16f
        t.typeface = Ui.medium()
        t.setTextColor(Fx.txt(this))
        col.addView(t, LinearLayout.LayoutParams(-1, -2))
        val s = TextView(this)
        s.text = listOfNotNull(p.name.ifEmpty { null }, p.email.ifEmpty { null },
                if (p.values["card"]?.isNotEmpty() == true) "•••• card" else null)
                .joinToString(" · ")
        s.textSize = 13f
        s.setTextColor(Fx.dim(this))
        s.maxLines = 1
        s.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(s, LinearLayout.LayoutParams(-1, -2))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        MenuCards.explain(row, this, p.label,
                "Encrypted profile (name, address, card) — offered as a one-tap fill on matching forms.")
        row.setOnClickListener { showEditor(p) }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Fx.dp(this, 6)
        row.layoutParams = lp
        return row
    }

    private fun showEditor(existing: Profiles.Profile?) {
        val labels = arrayOf("Profile name", "Full name", "Email", "Phone", "Organization",
                "Address", "Address line 2", "City", "Postal code", "Country",
                "Card number", "Expiry (MM/YY)", "CVC")
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = Fx.dp(this, 20)
        col.setPadding(pad, Fx.dp(this, 10), pad, Fx.dp(this, 4))
        val fields = ArrayList<EditText>()
        for (k in Profiles.FIELDS) {
            val et = EditText(this)
            et.hint = labels.getOrElse(Profiles.FIELDS.indexOf(k)) { k }
            if (existing != null) et.setText(existing.values[k] ?: "")
            fields.add(et)
            col.addView(et, LinearLayout.LayoutParams(-1, -2))
        }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(if (existing == null) "Add profile" else "Edit profile")
                .setView(col)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
        if (existing != null) {
            dlg.setNeutralButton(R.string.remove) { _, _ ->
                Profiles.delete(this, existing.label)
                rebuild()
            }
        }
        val d = dlg.create()
        d.show()
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = fields[0].text.toString().trim()
            if (name.isEmpty()) { Fx.toast(this, "Profile needs a name"); return@setOnClickListener }
            val m = LinkedHashMap<String, String>()
            for ((i, k) in Profiles.FIELDS.withIndex()) m[k] = fields[i].text.toString().trim()
            Profiles.save(this, Profiles.Profile(m))
            d.dismiss()
            rebuild()
        }
    }
}
