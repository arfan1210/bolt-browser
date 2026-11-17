package com.bolt.browser.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * v1.3.79 — APP ICON OPTIONS ("add option to change the logo of the app
 * itself with few options given or a custom image one").
 *
 * v1.3.81 — THE THEMED SET: the four v1.3.79 colorway aliases (Midnight /
 * Sunrise / Forest / Mono — the SAME white bolt on four flat backgrounds)
 * were replaced after the field report ("all the alternative bolt icons
 * are very bad, keep the default one, then add a few different style ones
 * following themes like popular franchises but with the bolt lighting").
 * The default (black-background bolt) stays; the alternatives are now
 * themed bolts: PIKA (Pokémon — chunky bolt whose base turns brown, on
 * Pikachu yellow), WIZARD (Harry-Potter-style gold scar bolt on night
 * navy), VOLT (the default bolt on a blue→violet gradient) and NEON
 * (synthwave cyan→magenta glow bolt on midnight).
 *
 * v1.3.82 — THE UPDATE TRAP, LEARNED THE HARD WAY. The v1.3.81 release
 * removed the colorway aliases; the REMOVED alias's setting was dropped
 * at install, but the MAIN ACTIVITY's explicit DISABLE survived — zero
 * launcher components, the invisible app. v1.3.82 answered with
 * [LauncherGuard] + a MY_PACKAGE_REPLACED receiver… and the field STILL
 * stranded ("app still not able to be opened… only get option Done, not
 * open").
 *
 * v1.3.83 — WHY v1.3.82'S HEAL COULD NOT FIRE, AND THE REAL LAW. The
 * platform docs say it plainly: MY_PACKAGE_REPLACED "is sent directly to
 * the application, but ONLY IF THE APPLICATION WAS UPGRADED WHILE IT WAS
 * IN A STARTED STATE (NOT IN A STOPPED STATE)". The stranded device's
 * last successful launch was v1.3.80 — the app was un-openable through
 * all of v1.3.81/82, and OEM killers / Settings force-stops push a
 * package into exactly the stopped state that blocks the broadcast. A
 * RUNTIME heal is dead on arrival for the one failure mode it exists
 * for. THE ONLY CHANNEL THAT RUNS EVERYWHERE — fresh install, stopped
 * state, aggressive OEM, no process at all — is THE PACKAGED MANIFEST
 * ITSELF. So v1.3.83 moves the launcher contract to a brand-new
 * component ([.ui.IconDefault], declared enabled=true in the manifest):
 * a component name that never existed in any earlier APK carries NO
 * persisted component-enabled state, so it comes back ENABLED at install
 * time, no code executed at all. THE STANDING LAWS from here on:
 *  1. the launcher contract lives on IconDefault (or its rotation) —
 *     NEVER on the real activity, whose persisted DISABLE from the
 *     v1.3.79-82 era can never be assumed cleared;
 *  2. IconDefault is never renamed/removed/disabled-by-default in any
 *     future release (rotating the name is the last-resort resurrection
 *     lever — it strands-escape by novelty, at the cost of every healthy
 *     user's home-screen placement);
 *  3. [apply] flips ONLY aliases — the real BrowserActivity is never a
 *     launch face again (an enabled alias launches its target regardless
 *     of the target's own enabled state — field-proven by v1.3.80
 *     running with the activity explicitly disabled under the Midnight
 *     alias), and every apply normalizes the activity back to its
 *     manifest default so the old scar cannot linger;
 *  4. themed aliases are never removed without LauncherGuard's heal
 *     knowing the full component set in the same release.
 *
 * THE MECHANISM: the manifest declares five `activity-alias` entries over
 * the SAME BrowserActivity (IconDefault / IconPika / IconWizard /
 * IconVolt / IconNeon), each with its own icon and the full launcher +
 * http/https VIEW intent filters. Exactly one is COMPONENT-ENABLED at a
 * time; [apply] flips them with
 * PackageManager.setComponentEnabledSetting (DONT_KILL_APP — the running
 * browser is untouched, and link opening keeps working because every
 * alias carries the VIEW filter too).
 *
 * THE HONEST LIMITS (why "or a custom image one" is NOT offered):
 *  - Android has no supported way to change the launcher icon to an
 *    arbitrary image at runtime — the icon is a compiled resource of the
 *    APK. The alias trick is the only system-blessed path, and it can only
 *    pick among icons SHIPPED INSIDE the APK. A "custom image" would mean
 *    shipping every possible image, which is not a thing.
 *  - API 24-25 render the alias PNG fallbacks (themed squares with the
 *    bolt glyph, rendered by scripts/render_icons.py); the adaptive
 *    variants are 26+.
 *  - Some launchers cache icons harder than others; the row's toast says
 *    "restart the launcher if it is slow to refresh".
 *  - v1.3.83 COST (one time): users on a THEMED face see two Bolt icons
 *    between the update and the first heal (IconDefault ships enabled
 *    while their themed alias is still enabled); opening the app once —
 *    or the update receiver, when it can fire — merges them back to one.
 */
object AppIconSwitch {

    /** The real activity behind every face — NEVER a launch component
     *  itself again (see the v1.3.83 laws above). */
    private const val REAL_ACTIVITY = "com.bolt.browser.ui.BrowserActivity"

    /** pref index -> component suffix */
    private val COMPONENTS = arrayOf(
            ".ui.IconDefault",         // 0: Bolt (default — the black-bg bolt)
            ".ui.IconPika",            // 1: Pokémon-style tail bolt
            ".ui.IconWizard",          // 2: gold scar bolt, night navy
            ".ui.IconVolt",            // 3: white bolt, blue→violet gradient
            ".ui.IconNeon")            // 4: synthwave glow bolt

    @JvmField val NAMES = arrayOf(
            "Bolt (default)",
            "Pika",
            "Wizard",
            "Volt",
            "Neon")

    /** how many faces exist — [LauncherGuard] walks these */
    @JvmField val COUNT = COMPONENTS.size

    /** pref index -> the launcher ComponentName (out-of-range index =
     *  null). v1.3.83: index 0 is the IconDefault ALIAS, not the real
     *  activity — the activity is never flipped again. */
    @JvmStatic
    fun component(ctx: Context, idx: Int): ComponentName? {
        if (idx < 0 || idx >= COMPONENTS.size) return null
        return ComponentName(ctx.packageName, ctx.packageName + COMPONENTS[idx])
    }

    /** flip the launcher face: enable exactly the chosen alias and
     *  disable every other. ENABLE-FIRST, in passes — the v1.3.79
     *  one-pass loop could crash between "default off" and "alias on",
     *  leaving ZERO launcher entries (the invisible-app trap). A brief
     *  two-icon moment is cosmetic and self-corrects; a zero-icon moment
     *  is fatal.
     *  Pass 3 (v1.3.83): normalize the REAL activity back to its manifest
     *  default (enabled, filter-less) — the v1.3.79-82 era left an
     *  explicit DISABLE persisted on it for every user who ever picked a
     *  face; that scar must not outlive the releases that created it. */
    @JvmStatic
    fun apply(ctx: Context, idx: Int) {
        try {
            val pm = ctx.packageManager
            // pass 1: the chosen face FIRST (idempotent if already on)
            val chosen = component(ctx, idx) ?: return
            pm.setComponentEnabledSetting(chosen,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP)
            // pass 2: every other face off
            for (i in 0 until COUNT) {
                if (i == idx) continue
                val cn = component(ctx, i) ?: continue
                pm.setComponentEnabledSetting(cn,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP)
            }
            // pass 3: the real activity is not a launch face — restore its
            // manifest default so no ancient flip lingers on it (it has no
            // launcher filter anymore, so enabled is its harmless default)
            resetRealActivity(pm, ctx.packageName)
        } catch (_: Throwable) {
            // a launcher-component flip must never crash the app
        }
    }

    /** v1.3.83 — clear any persisted non-default state on the REAL
     *  BrowserActivity (the v1.3.79-82 icon switcher is the only thing
     *  that ever flipped it). Conditional, so the common healthy path
     *  writes nothing. Public for [LauncherGuard]'s stranded heal. */
    @JvmStatic
    fun resetRealActivity(pm: PackageManager, pkg: String) {
        try {
            val cn = ComponentName(pkg, REAL_ACTIVITY)
            if (pm.getComponentEnabledSetting(cn) !=
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                pm.setComponentEnabledSetting(cn,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        PackageManager.DONT_KILL_APP)
            }
        } catch (_: Throwable) {
            // never fatal — the alias fronts the activity regardless
        }
    }

    /** v1.3.83 — is the REAL activity carrying a persisted non-default
     *  enabled state? This is the ERA MARKER [LauncherGuard] uses to
     *  recognize a device that walked out of the v1.3.79-82 era (their
     *  apply() flipped the real activity; every version since leaves it
     *  at DEFAULT). Read-only, never throws. */
    @JvmStatic
    fun realActivityStray(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getComponentEnabledSetting(ComponentName(pkg, REAL_ACTIVITY)) !=
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } catch (_: Throwable) {
            false
        }
    }
}
