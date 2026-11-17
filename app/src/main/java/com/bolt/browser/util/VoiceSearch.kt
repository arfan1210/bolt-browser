package com.bolt.browser.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * v1.3.38 — VOICE SEARCH (menu item 2 of the round-2 modernization menu; new
 * code → Kotlin per the README language rule).
 *
 * Uses the system speech recognizer via ACTION_RECOGNIZE_SPEECH (RECORD_AUDIO
 * has been in the manifest since the camera/mic site settings existed) — no
 * dependency, no network engine of our own. The mic button lives in the
 * omnibox (fills the field so the user can edit before going) and on the
 * start-page search pill.
 *
 * Failure paths are normal states, not errors: no recognizer installed, user
 * cancels, or empty result → toast + stay put.
 */
object VoiceSearch {

    /** request code the caller passes to launch(). */
    const val REQ = 1010

    @JvmStatic
    fun available(ctx: Context): Boolean = try {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.resolveActivity(ctx.packageManager) != null
    } catch (t: Throwable) {
        false
    }

    /** fire the recognizer overlay; returns false (with a toast) when the
     *  device has no speech input at all. */
    @JvmStatic
    fun launch(a: Activity, requestCode: Int = REQ): Boolean {
        return try {
            if (!available(a)) {
                Fx.toast(a, "No speech recognition on this device")
                return false
            }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a search or an address")
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            a.startActivityForResult(i, requestCode)
            true
        } catch (t: Throwable) {
            Fx.toast(a, "Speech input unavailable")
            false
        }
    }

    /** best transcript from the recognizer result, or null. */
    @JvmStatic
    fun result(data: android.content.Intent?): String? = try {
        val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (matches != null && matches.isNotEmpty() && !matches[0].isBlank()) {
            matches[0].trim()
        } else null
    } catch (t: Throwable) {
        null
    }
}
