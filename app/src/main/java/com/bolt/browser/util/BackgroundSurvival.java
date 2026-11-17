package com.bolt.browser.util;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;

/**
 * v1.3.27 — the OEM-killer countermeasure kit ("background survival").
 *
 * FIELD CONTEXT (v1.3.26 report): music playing in a backgrounded Bolt died
 * while the user read a PDF in another app — notification gone, no crash log,
 * no rendererGone entry. That fingerprint (nothing in diag, service + tabs all
 * gone at once) is a WHOLE-PROCESS kill. On stock Android a process running a
 * mediaPlayback foreground service with live audio sits at perceptible
 * importance and is killed only under extreme pressure — but Xiaomi/Redmi/
 * POCO (MIUI/HyperOS), OPPO/Realme/OnePlus (ColorOS), vivo/iQOO, Huawei/Honor
 * and friends add their own "battery savers" that force-stop such apps the
 * moment the user opens something else (dontkillmyapp.com documents exactly
 * this pattern; the ride-hailing industry hit it mid-trip).
 *
 * The standard, field-proven cure is two user switches:
 *  1. Battery-optimization exemption (Settings.ActionRequestIgnoreBattery-
 *     Optimizations — a system dialog; Bolt is sideloaded from GitHub, so the
 *     Play-policy restrictions on REQUEST_IGNORE_BATTERY_OPTIMIZATIONS do not
 *     apply to it).
 *  2. The OEM's own autostart / background setting (deep-linked per
 *     manufacturer, best-effort — component names vary per ROM build; every
 *     attempt falls back to the app-details page).
 *
 * Everything here is static, never-throwing and UI-optional: the browser core
 * never depends on it.
 */
public final class BackgroundSurvival {

    private BackgroundSurvival() {}

    // ---------------------------------------------------------------- battery

    /** true when the app is on the system's battery-optimization whitelist. */
    public static boolean isIgnoring(Context c) {
        try {
            PowerManager pm = c.getSystemService(PowerManager.class);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** System dialog: "allow <app> to ignore battery optimizations?". Falls
     *  back to the global whitelist screen, then to the app-details page. */
    public static void requestExemption(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName())));
            Diag.log(a, "boExempt dialog requested");
            return;
        } catch (Throwable ignored) {}
        try {
            a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            Diag.log(a, "boExempt list opened");
            return;
        } catch (Throwable ignored) {}
        openAppDetails(a);
    }

    private static void openAppDetails(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- OEM detection

    /** normalized manufacturer id, "" when stock-ish. */
    public static String oem() {
        try {
            String m = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER).toLowerCase().trim();
            if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) return "xiaomi";
            if (m.contains("oppo")) return "oppo";
            if (m.contains("realme")) return "realme";
            if (m.contains("oneplus")) return "oneplus";
            if (m.contains("vivo")) return "vivo";
            if (m.contains("iqoo")) return "vivo";
            if (m.contains("huawei")) return "huawei";
            if (m.contains("honor") || m.contains("hihonor")) return "honor";
            if (m.contains("samsung")) return "samsung";
            if (m.contains("meizu")) return "meizu";
            if (m.contains("letv") || m.contains("leeco")) return "letv";
        } catch (Throwable ignored) {}
        return "";
    }

    /** the phone maker's marketing name for the diag log + settings row. */
    public static String oemName() {
        switch (oem()) {
            case "xiaomi": return "Xiaomi/Redmi/POCO (MIUI/HyperOS)";
            case "oppo": return "OPPO (ColorOS)";
            case "realme": return "Realme (Realme UI/ColorOS)";
            case "oneplus": return "OnePlus (OxygenOS/ColorOS)";
            case "vivo": return "vivo/iQOO (Funtouch/OriginOS)";
            case "huawei": return "Huawei (EMUI)";
            case "honor": return "Honor (MagicOS)";
            case "samsung": return "Samsung (One UI)";
            case "meizu": return "Meizu (Flyme)";
            case "letv": return "Letv (eUI)";
            default: return "";
        }
    }

    // ---------------------------------------------------------------- OEM deep links

    /**
     * Best-effort launch of the OEM's autostart / background power manager.
     * Component names are the ones documented across dontkillmyapp.com and
     * every keep-alive library in the wild — they drift per ROM build, so
     * every candidate is tried in turn and the whole thing degrades to the
     * app-details page. Never throws.
     */
    public static void openOemPower(Activity a) {
        String oem = oem();
        ComponentName[] cands = new ComponentName[0];
        switch (oem) {
            case "xiaomi":
                // autostart list + the battery-saver ("no restrictions") page
                cands = new ComponentName[]{
                        cn("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                        cn("com.miui.securitycenter", "com.miui.powerkeeper.activity.HiddenAppsConfigActivity"),
                        cn("com.miui.securitycenter", "com.miui.powerkeeper.PowerSettingsActivity")
                };
                break;
            case "oppo":
            case "realme":
                cands = new ComponentName[]{
                        cn("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                        cn("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                        cn("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                        cn("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")
                };
                break;
            case "oneplus":
                cands = new ComponentName[]{
                        cn("com.oneplus.security", "com.oneplus.security.chainlaunch.ChainLaunchListActivity"),
                        cn("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                        cn("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
                };
                break;
            case "vivo":
                cands = new ComponentName[]{
                        cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                        cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                        cn("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity")
                };
                break;
            case "huawei":
            case "honor":
                cands = new ComponentName[]{
                        cn("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                        cn("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                        cn("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                };
                break;
            case "meizu":
                cands = new ComponentName[]{
                        cn("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
                        cn("com.meizu.safe", "com.meizu.safe.security.SHARE_APP_SECURITY")
                };
                break;
            default:
                // Samsung/stock: no autostart screen exists — the battery
                // whitelist + app-details are the real switches.
                break;
        }
        for (ComponentName cn : cands) {
            if (cn == null) continue;
            try {
                Intent i = new Intent();
                i.setComponent(cn);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
                Diag.log(a, "oemPower opened " + cn.getClassName());
                return;
            } catch (Throwable ignored) {}
        }
        // last resort: our own app-details page (battery / battery-usage is
        // one tap away there on every ROM)
        Diag.log(a, "oemPower fell back to app details (oem=" + (oem.isEmpty() ? "none" : oem) + ")");
        openAppDetails(a);
    }

    private static ComponentName cn(String pkg, String cls) {
        try {
            return new ComponentName(pkg, cls);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- text

    /** the OEM-specific instructions shown in the survival dialog/row. */
    public static String oemInstructions() {
        switch (oem()) {
            case "xiaomi":
                return "MIUI/HyperOS: turn ON Autostart for Bolt (Settings > Apps > Bolt), "
                        + "set Battery saver to 'No restrictions', and turn OFF 'Pause app "
                        + "activity if unused'. Lock the Bolt card in Recents (long-press it).";
            case "oppo":
            case "realme":
                // v1.3.28: rewritten from the v1.3.27 field evidence — the
                // battery-optimization whitelist was granted and the kill
                // STILL landed (dontkillmyapp.com/realme: ColorOS layers its
                // own RAM/power manager on top; these are the switches that
                // actually matter on Realme UI)
                return "Realme UI/ColorOS: open Settings \u2192 Battery \u2192 Power "
                        + "saving settings \u2192 App battery management \u2192 Bolt, and "
                        + "allow Auto-launch, Foreground activity and Background "
                        + "activity. Then turn OFF 'Sleep stand-by optimization' "
                        + "(Settings \u2192 Battery \u2192 Power saver options), keep Bolt OUT "
                        + "of 'App quick freeze' (Phone Manager), and avoid Smart/"
                        + "Performance-improvement battery modes \u2014 use 'No "
                        + "performance improvement'.";
            case "oneplus":
                return "OxygenOS/ColorOS: allow Bolt to 'Run in background' in Settings > "
                        + "Battery > App battery management, and enable auto-launch.";
            case "vivo":
                return "Funtouch/OriginOS: enable 'Allow background activity' and autostart "
                        + "for Bolt in the phone manager (i Manager > App manager).";
            case "huawei":
            case "honor":
                return "EMUI/MagicOS: open Phone Manager > Startup apps and allow Bolt; "
                        + "also set Bolt to 'Manual launch' in Protected apps.";
            case "samsung":
                return "One UI: remove Bolt from 'Sleeping apps' (Settings > Battery > "
                        + "Background usage limits) and add it to 'Never sleeping apps'.";
            case "meizu":
                return "Flyme: allow Bolt's background run in Security > Permissions, "
                        + "and enable 'Smart background' for it.";
            default:
                return "Most phones also have a per-app battery setting — choose "
                        + "'Unrestricted' for Bolt if background playback still stops.";
        }
    }

    /** one-line status for the Settings row value. */
    public static String statusLine(Context c) {
        String bo = isIgnoring(c) ? "battery whitelisted" : "battery optimization ON";
        String oem = oem();
        if (oem.isEmpty()) return bo;
        return bo + " · " + oemName();
    }

    // ---------------------------------------------------------------- the offer dialog

    /**
     * One-time-per-install dialog offering the two switches. Shown either
     * from Settings (any time) or contextually after Bolt was killed while
     * playing (BrowserActivity onResume). The prefs gate ("askedBo") makes
     * the automatic offer fire at most once; the Settings row always works.
     */
    public static void showOffer(final Activity a, String evidenceLine) {
        try {
            StringBuilder msg = new StringBuilder();
            if (evidenceLine != null && !evidenceLine.isEmpty()) {
                msg.append(evidenceLine).append("\n\n");
            }
            msg.append("When music in Bolt stops while you use other apps, the phone "
                    + "itself is usually closing Bolt to save memory or battery — "
                    + "manufacturers do this even to apps that are actively playing "
                    + "audio.\n\nTwo switches make background playback rock solid:\n\n");
            msg.append("1. Battery optimization — allow Bolt to ignore it (a system "
                    + "dialog opens, tap Allow).\n\n");
            String oem = oem();
            if (!oem.isEmpty()) {
                msg.append("2. ").append(oemInstructions()).append("\n\n");
            } else {
                msg.append("2. Your phone's per-app battery/background setting, if it "
                        + "has one.\n\n");
            }
            new AlertDialog.Builder(a)
                    .setTitle("Keep background playback alive")
                    .setMessage(msg.toString())
                    .setPositiveButton("Allow", (d, w) -> requestExemption(a))
                    .setNeutralButton("Phone settings", (d, w) -> openOemPower(a))
                    .setNegativeButton("Not now", null)
                    .show();
            Diag.log(a, "boOffer shown (whitelisted=" + isIgnoring(a)
                    + " oem=" + (oem.isEmpty() ? "none" : oem) + ")");
        } catch (Throwable ignored) {}
    }

    /** the automatic one-time offer (returns true when it showed a dialog).
     *  v1.3.28: two phases. Phase 1 ("askedBo") is the battery-optimization
     *  whitelist request \u2014 skipped when already whitelisted. Phase 2
     *  ("askedOem") fires ONCE for known-OEM phones even when the whitelist
     *  is already granted: the v1.3.27 field report proved the kill lands
     *  WITH the whitelist on realme (adj=200, bo=whitelisted in the
     *  heartbeat log), and dontkillmyapp.com's realme page lists the
     *  manufacturer-layer switches (app battery management, sleep stand-by
     *  optimization, app quick freeze) that actually close that gap. */
    public static boolean maybeOfferOnce(Activity a, Prefs prefs, String evidenceLine) {
        try {
            if (!prefs.b("askedBo", false)) {
                prefs.set("askedBo", true);
                if (!isIgnoring(a)) {
                    showOffer(a, evidenceLine);
                    return true;
                }
                // already whitelisted: fall through to the OEM phase below
            }
            String oem = oem();
            if (!oem.isEmpty() && !prefs.b("askedOem", false)) {
                prefs.set("askedOem", true);
                showOemOffer(a, evidenceLine);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.3.28 phase-2 dialog: the whitelist is not enough on this
     *  manufacturer \u2014 offer the OEM's own background-management switches
     *  with a deep link. */
    public static void showOemOffer(final Activity a, String evidenceLine) {
        try {
            String oem = oem();
            String name = oemName();
            StringBuilder msg = new StringBuilder();
            if (evidenceLine != null && !evidenceLine.isEmpty()) {
                msg.append(evidenceLine).append("\n\n");
            }
            msg.append("Battery optimization is already allowed for Bolt \u2014 but ")
                    .append(name).append(" phones have their own memory/battery "
                            + "manager that can still stop Bolt even when it is "
                            + "playing music and whitelisted.\n\n");
            msg.append("The switch that closes this gap on your phone:\n\n")
                    .append(oemInstructions()).append("\n\n");
            msg.append("Bolt also recovers on its own now: after the system stops "
                    + "it during playback, a \u2018Playback interrupted \u2014 tap to "
                    + "resume\u2019 notification appears within about a minute, and "
                    + "reopening Bolt continues the video near where it stopped.");
            new AlertDialog.Builder(a)
                    .setTitle("One more switch on your " + name)
                    .setMessage(msg.toString())
                    .setPositiveButton("Open phone settings", (d, w) -> openOemPower(a))
                    .setNegativeButton("Later", null)
                    .show();
            Diag.log(a, "oemOffer shown (whitelisted=" + isIgnoring(a)
                    + " oem=" + (oem.isEmpty() ? "none" : oem) + ")");
        } catch (Throwable ignored) {}
    }
}
