package com.bolt.browser.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * v1.3.82 — THE LAUNCHER GUARD + THE UPDATE SELF-HEAL (new code → Kotlin;
 * the standing language rule).
 *
 * v1.3.83 — THE GUARD LEARNS WHY v1.3.82 FAILED IN THE FIELD. The
 * MY_PACKAGE_REPLACED receiver was the resurrection plan; the platform
 * docs quietly kill it: the broadcast "is sent directly to the
 * application, but ONLY IF THE APPLICATION WAS UPGRADED WHILE IT WAS IN
 * A STARTED STATE (NOT IN A STOPPED STATE)". The stranded device's last
 * launch was v1.3.80 — un-openable through v1.3.81 and v1.3.82, likely
 * force-stopped while the user hunted Settings for an Open button, an
 * OEM "kill from recents" away from the stopped state at any moment.
 * The report said it exactly: "app still not able to be opened… after
 * installing/updating, I only get option Done, not open". THE LESSON
 * (README lesson 68): a runtime heal is dead on arrival for precisely
 * the failure mode it exists for — THE PACKAGED MANIFEST IS THE ONLY
 * CHANNEL THAT RUNS IN THE STOPPED STATE. v1.3.83 therefore moved the
 * launcher contract to the brand-new, manifest-enabled
 * [.ui.IconDefault] alias (see AppIconSwitch's v1.3.83 laws); the
 * install itself is now the resurrection. This guard remains as the
 * second layer — it repairs DRIFT (two faces on, pref disagrees with
 * reality) and normalizes the real activity's ancient persisted flips
 * the moment any code runs.
 *
 * THE POLICY (three shapes):
 *  - HEALTHY: exactly the pref's face is on and the real activity
 *    carries no stale flip → nothing to do (the every-boot common case
 *    must stay read-only);
 *  - STRANDED (zero faces on): default face + pref reset + activity
 *    reset — the v1.3.81/82 trap shape;
 *  - THE FOSSIL ARRIVAL (v1.3.83's new shape): exactly the NEW default
 *    alias is on, but the real activity still carries the v1.3.79-82
 *    era's explicit DISABLE, or the saved pref predates the alias-set
 *    change (v1.3.80's index 1 was Midnight; v1.3.81+ index 1 is Pika —
 *    same number, different icon). Both scars prove the pref was written
 *    by a different era's component set → heal to the DEFAULT face and
 *    reset the pref; never honor a cross-era index.
 */
object LauncherGuard {

    /**
     * Verify the launcher face and repair stranded/drifted/fossil state.
     * Idempotent, thread-safe (synchronized — BoltApp's startup thread
     * and the update receiver can race on a just-updated first launch),
     * never throws, never touches anything but launcher components + the
     * appIcon pref.
     */
    @JvmStatic
    @Synchronized
    fun heal(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val pm = app.packageManager
            val prefs = Prefs.get(app)

            // live state: DEFAULT follows the manifest (IconDefault on,
            // themed aliases off) — anything else is what somebody set.
            val enabled = BooleanArray(AppIconSwitch.COUNT)
            var count = 0
            for (i in 0 until AppIconSwitch.COUNT) {
                val cn = AppIconSwitch.component(app, i) ?: continue
                val st = pm.getComponentEnabledSetting(cn)
                val on = st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                        (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && i == 0)
                enabled[i] = on
                if (on) count++
            }

            var pref = prefs.appIcon()
            if (pref < 0 || pref >= AppIconSwitch.COUNT) pref = 0

            // the real activity's state is the era marker: v1.3.79-82's
            // apply() left an explicit flip on it; a device that never
            // ran those versions (or was healed) sits at DEFAULT.
            val actStray = AppIconSwitch.realActivityStray(pm, app.packageName)

            // healthy: exactly the pref's face is on AND the activity is
            // clean — nothing to do
            if (count == 1 && enabled[pref] && !actStray) return

            if (count == 0) {
                // THE STRANDED STATE: no launcher entry at all. Reset the
                // default face (manifest state = enabled) and clear the
                // pref — see the policy note above for why the saved
                // index is NOT trusted here.
                prefs.setAppIcon(0)
                pm.setComponentEnabledSetting(
                        AppIconSwitch.component(app, 0)!!,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        PackageManager.DONT_KILL_APP)
                for (i in 1 until AppIconSwitch.COUNT) {
                    val cn = AppIconSwitch.component(app, i) ?: continue
                    pm.setComponentEnabledSetting(cn,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP)
                }
                AppIconSwitch.resetRealActivity(pm, app.packageName)
                try {
                    Diag.log(app, "launcher heal stranded->default pref=$pref")
                } catch (_: Throwable) {}
                return
            }

            if (count == 1 && enabled[0] && actStray) {
                // THE FOSSIL ARRIVAL (v1.3.83): the brand-new IconDefault
                // is the only face on (it shipped enabled), but the real
                // activity still carries the old era's explicit flip —
                // this device walked here from v1.3.79-82, and its pref
                // was written by that era's component set (a colorway
                // index that now names a themed bolt it never chose).
                // Heal to the default face, reset the pref, clear the scar.
                prefs.setAppIcon(0)
                AppIconSwitch.apply(app, 0)
                try {
                    Diag.log(app, "launcher heal fossil-era->default pref=$pref")
                } catch (_: Throwable) {}
                return
            }

            // drifted (2+ faces on, or a different face than the pref):
            // normalize to the pref — the UI shows the pref, the launcher
            // shows reality; make them agree. (A themed user updating to
            // v1.3.83 lands here: IconDefault shipped enabled next to
            // their still-enabled themed alias — the two-icon moment —
            // and this merge is what ends it.)
            AppIconSwitch.apply(app, pref)
            try {
                Diag.log(app, "launcher heal drift n=$count -> pref=$pref")
            } catch (_: Throwable) {}
        } catch (_: Throwable) {
            // a heal must never crash anything
        }
    }
}

/**
 * v1.3.82 — runs at MY_PACKAGE_REPLACED: the update moment is (when the
 * platform deigns to deliver it — see the stopped-state note above) a
 * chance to heal + sweep while the app may be un-openable, and the
 * moment the field's OTHER report complained about — "the size is still
 * 50mb, it still did not reduce after updating it": an APK install runs
 * no Bolt code, so the previous session's unswept caches and site data
 * (an OEM-killed process never reaches onStop's exit sweep) sat
 * untouched through the update. This receiver makes the UPDATE itself
 * the heal + sweep moment:
 *  1. [LauncherGuard.heal] — the launcher face merges (synchronously —
 *     see below);
 *  2. ScreenTranslate.modelDiet — the orphaned legacy OCR model tree
 *     (filesDir/ocrfast, up to ~17 MB) only used to die when the user
 *     next opened Translate screen; a stranded/un-opened app kept it
 *     forever. Now it dies at update time;
 *  3. CacheGuard.exit — the full close-out sweep (cache subtrees always;
 *     the site-data diet behind its toggle, default on) — the Settings
 *     storage number drops right after the APK installs, no launch
 *     needed.
 *
 * v1.3.83 — THE DELIVERY HARDENING, after the v1.3.82 field failure:
 *  - the LAUNCHER HEAL now runs SYNCHRONOUSLY inside onReceive, before
 *    anything is spawned. The v1.3.82 code returned immediately after
 *    starting a bare Thread — once onReceive returns, the process is
 *    idle and fair game for the OEM reaper, so the heal raced process
 *    death on exactly the aggressive devices that strand apps. The heal
 *    is a handful of binder calls (milliseconds); the broadcast window
 *    guarantees the process while onReceive runs, so the flip is done
 *    before the reaper can act;
 *  - the SLOW sweeps (model diet + close-out) ride goAsync(): the
 *    PendingResult keeps the process alive for the disk work (~10 s
 *    window) and is finished() come what may.
 * If the app is stopped, none of this fires — that is why the v1.3.83
 * resurrection is a MANIFEST property (the IconDefault alias), not this
 * receiver. This receiver is the belt; the manifest is the braces.
 */
class UpdateHealReceiver : BroadcastReceiver() {

    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = c.applicationContext
        // THE FLIP FIRST, SYNCHRONOUSLY, INSIDE THE BROADCAST WINDOW —
        // the launcher merge must not race the process reaper (above).
        try { LauncherGuard.heal(app) } catch (_: Throwable) {}
        // the slow sweeps ride goAsync() — the ~10 s window keeps the
        // process alive while the disk work runs
        val pr = goAsync()
        Thread({
            try {
                val vn = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
                } catch (_: Throwable) { "?" }
                Diag.log(app, "update heal v$vn (launcher + model diet + sweep)")
            } catch (_: Throwable) {}
            try { ScreenTranslate.modelDiet(app) } catch (_: Throwable) {}
            // v1.4.31 — door=update: the package-replaced sweep names its
            // own door in the diag (not one of the three exit doors, but
            // an honest label for the field)
            try { CacheGuard.exit(app, "update") } catch (_: Throwable) {}
            try { pr.finish() } catch (_: Throwable) {}
        }, "bolt-update-heal").start()
    }
}
