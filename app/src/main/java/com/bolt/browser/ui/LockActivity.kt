package com.bolt.browser.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bolt.browser.util.AppLock
import com.bolt.browser.util.Fx
import com.bolt.browser.util.Ui

/**
 * v1.3.38 — the APP LOCK gate (see AppLock; new code → Kotlin per the
 * README language rule). A translucent scrim with the lock message; the
 * device-credential sheet rides on top of it. Success unlocks for the
 * session and finishes; cancel/back re-prompts (backing out of the sheet
 * must not leak the browser behind the scrim). Devices with no secure lock
 * screen simply pass through (the setting advises setting one).
 */
class LockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Fx.theme(this)
        super.onCreate(savedInstanceState)
        // OPAQUE scrim — the browser must never be readable behind the gate
        // (translucency + dim alone still leaks page outlines)
        val dim = 0xF50E0E13.toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(dim)

        val ic = android.widget.ImageView(this)
        ic.setImageResource(com.bolt.browser.R.drawable.ic_lock)
        ic.setColorFilter(0xFFFFFFFF.toInt())
        root.addView(ic, LinearLayout.LayoutParams(Fx.dp(this, 34), Fx.dp(this, 34)))

        val t = TextView(this)
        t.text = "Bolt is locked"
        t.textSize = 19f
        t.typeface = Ui.medium()
        t.setTextColor(0xFFFFFFFF.toInt())
        t.gravity = Gravity.CENTER
        t.setPadding(0, Fx.dp(this, 12), 0, 0)
        root.addView(t, LinearLayout.LayoutParams(-1, -2))

        val s = TextView(this)
        s.text = "Unlock with your screen lock to continue"
        s.textSize = 14f
        s.setTextColor(0xCCFFFFFF.toInt())
        s.gravity = Gravity.CENTER
        s.setPadding(0, Fx.dp(this, 4), 0, 0)
        root.addView(s, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)

        val w = window
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val par = w.attributes
        par.dimAmount = 0.6f
        par.flags = par.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        w.attributes = par

        prompt()
    }

    private fun prompt() {
        try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isDeviceSecure) {
                // nothing to gate with — pass through
                AppLock.markUnlocked()
                finish()
                return
            }
            val i = km.createConfirmDeviceCredentialIntent("Bolt is locked",
                    "Unlock to continue")
            if (i == null) {
                AppLock.markUnlocked()
                finish()
                return
            }
            startActivityForResult(i, 1014)
        } catch (t: Throwable) {
            AppLock.markUnlocked()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1014) {
            if (resultCode == Activity.RESULT_OK) {
                AppLock.markUnlocked()
                finish()
            } else {
                // cancelled → re-prompt (never fall through to the browser)
                prompt()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // no way past the gate; leave the app instead of revealing content
        moveTaskToBack(true)
    }

    companion object {
        const val EXTRA_STALE = "stale"
    }
}
