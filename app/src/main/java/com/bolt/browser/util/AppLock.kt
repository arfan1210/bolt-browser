package com.bolt.browser.util

import android.content.Context

/**
 * v1.3.38 — APP LOCK (menu item 8, implemented as an OPT-IN toggle exactly
 * as the user asked — "optional as per wish, not mandatory for accessing
 * app"; new code → Kotlin per the README language rule).
 *
 * Privacy → "App lock": when on, a translucent LockActivity gates the
 * browser behind the device credential (KeyguardManager — no
 * androidx.biometric dependency, works with PIN/pattern/passphrase and any
 * biometric the system already handles). "Lock when backgrounded" (default
 * on) re-arms the gate after 60 s away; with it off only cold starts gate.
 *
 * Process-static timestamps (both processes own one — the incognito process
 * locks itself too): unlockedAt survives screen switches inside the app
 * because onStart/onStop only move the counter across the process boundary.
 */
object AppLock {

    const val LOCK_AFTER_MS = 60_000L

    @Volatile var unlockedAt = 0L
    @Volatile var backgroundedAt = 0L
    @Volatile var visible = 0

    @JvmStatic
    fun enabled(c: Context): Boolean = Prefs.get(c).b("appLock", false)

    /** re-lock after 60 s in background (pref appLockBg, default on). */
    @JvmStatic
    fun relockInBackground(c: Context): Boolean = Prefs.get(c).b("appLockBg", true)

    /** call from every gated activity's onStart — true → show LockActivity. */
    @JvmStatic
    fun onActivityStart(c: Context): Boolean {
        val cold = visible == 0
        visible++
        if (!enabled(c)) return false
        if (unlockedAt == 0L) return true                       // cold start
        if (relockInBackground(c) && backgroundedAt > 0
                && System.currentTimeMillis() - backgroundedAt > LOCK_AFTER_MS) {
            return true                                          // away too long
        }
        return false
    }

    /** call from every gated activity's onStop. */
    @JvmStatic
    fun onActivityStop() {
        if (visible > 0) visible--
        if (visible == 0) backgroundedAt = System.currentTimeMillis()
    }

    @JvmStatic
    fun markUnlocked() {
        unlockedAt = System.currentTimeMillis()
    }
}
