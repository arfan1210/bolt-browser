package com.bolt.browser.media;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.bolt.browser.BoltApp;
import com.bolt.browser.R;
import com.bolt.browser.ui.BrowserActivity;

/**
 * v1.3.28 — the alarm-watchdog receiver: the second half of the
 * whole-process-kill countermeasure.
 *
 * WHY THIS EXISTS: the v1.3.27 field diagnostics proved the kill with hard
 * numbers — the process sat at oom_score_adj=200 (perceptible, the exact
 * ceiling AOSP's android-10 OomAdjuster assigns a backgrounded
 * mediaPlayback foreground service), battery-optimization whitelisted,
 * wake lock held, no Java crash — and was still killed ~80 s after a second
 * heavy app opened. lmkd's own source states the policy: "do not kill
 * perceptible apps UNLESS thrashing at critical levels" — and a 3 GB realme
 * running a PDF viewer plus a browser plus a messenger IS thrashing at
 * critical levels (the diag log's constant trim level=15/80 storms). The
 * OEM's RAM cleaner sits on top and kills past the whitelist too. No legal
 * in-app lever moves adj below 200, so a kill will land sometimes on this
 * hardware — what Bolt can do is make it cost ONE TAP instead of minutes of
 * silent dead phone.
 *
 * HOW: MediaControlService re-arms a 95 s setExactAndAllowWhileIdle alarm
 * (v1.3.29: was 45 s, which fired once between every pair of the 60 s
 * heartbeats while healthy — 60 exact wakeups an hour for nothing; above the
 * beat period it can never fire while healthy) on every playback heartbeat.
 * Alarms live in system_server and SURVIVE the
 * app process (only a force-stop wipes them). When the alarm fires:
 *  - process alive and playing -> re-arm, nothing else (the common case);
 *  - process alive but idle -> cancel the alarms (playback ended cleanly);
 *  - process was KILLED -> this receiver runs in a fresh process, where
 *    BoltApp.onCreate has already turned the leftover heartbeat mark into
 *    PROC_KILL evidence (MediaControlService.lastDeathEvidence /
 *    lastDeathAt). If that evidence is fresh, post the recovery
 *    notification: "Playback interrupted — tap to resume where it left
 *    off." The content intent opens BrowserActivity, whose restore path
 *    reopens the watch URL with the &t= position (the v1.3.27 machinery the
 *    user confirmed "resumes near where it crashed perfectly").
 */
public class MediaWatchdog extends BroadcastReceiver {

    private static final int RECOVERY_NOTIF_ID = 43;
    /** evidence older than this no longer gets a notification (the user has
     *  moved on, or already came back to Bolt by other means). */
    private static final long FRESH_EVIDENCE_MS = 120_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (MediaControlService.isPlaying()) {
                // alive and playing: keep watching (also re-arms the alarm
                // even if a beat-driven re-arm was somehow missed)
                MediaControlService.armWatchdog(context);
                return;
            }
            if (MediaControlService.isRunning()) {
                // alive but not playing: playback ended cleanly while the
                // process lives — the service clears its own alarms, and this
                // catches the paused/stopped cases too. Stop watching.
                // (v1.3.30: a phoenix attempt in flight is also "running but
                // not playing" — its own timers own the outcome.)
                MediaControlService.cancelWatchdog(context);
                return;
            }
            // no service: either nothing ever played, or the process died.
            String ev = MediaControlService.lastDeathEvidence;
            long at = MediaControlService.lastDeathAt;
            if (ev == null || ev.isEmpty() || at <= 0
                    || System.currentTimeMillis() - at > FRESH_EVIDENCE_MS) {
                MediaControlService.cancelWatchdog(context);
                return;
            }
            // died while playing, evidence fresh, nobody back yet.
            // v1.3.30 — PHOENIX FIRST: hand the resume to the service (it
            // rebuilds the media tab headlessly and the music comes back on
            // its own, rate/memory gated). The exact-alarm broadcast is an
            // exempted state for a background foreground-service start, and
            // on the user's Android 10 there is no restriction at all. Only
            // when the service cannot be started does the one-tap recovery
            // notification fire directly from here.
            try {
                Intent i = new Intent(context.getApplicationContext(),
                        MediaControlService.class);
                i.setAction("com.bolt.browser.media.PHOENIX");
                context.startForegroundService(i);
                com.bolt.browser.util.Diag.logSync(context,
                        "watchdog → phoenix (service start requested)");
                return;
            } catch (Throwable t) {
                // fall through to the notification
            }
            postRecoveryNotification(context, ev);
        } catch (Throwable t) {
            try { MediaControlService.cancelWatchdog(context); } catch (Throwable ignored) { }
        }
    }

    /** v1.3.30 — public static: the phoenix's give-up path posts the same
     *  one-tap recovery notification from inside the service. */
    static void postRecoveryNotification(Context c, String evidence) {
        postRecovery(c, evidence);
    }

    private static void postRecovery(Context c, String evidence) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            // notifications disabled for the app (or not granted on 13+):
            // nothing we can surface — the evidence still reaches the user
            // via the survival dialog on the next launch
            try {
                if (!androidx.core.app.NotificationManagerCompat
                        .from(c.getApplicationContext()).areNotificationsEnabled()) return;
            } catch (Throwable ignored) { }
            Intent open = new Intent(c, BrowserActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int oflags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent content = PendingIntent.getActivity(c, 11, open, oflags);

            Notification n = new NotificationCompat.Builder(c, BoltApp.CH_ALERT)
                    .setSmallIcon(R.drawable.ic_notify_media)
                    .setContentTitle(c.getString(R.string.media_recovery_title))
                    .setContentText(c.getString(R.string.media_recovery_text))
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(evidence + " "
                                    + c.getString(R.string.media_recovery_text)))
                    .setContentIntent(content)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build();
            nm.notify(RECOVERY_NOTIF_ID, n);
            com.bolt.browser.util.Diag.logSync(c,
                    "watchdog recovered — one-tap resume notification posted");
        } catch (Throwable t) {
            try {
                com.bolt.browser.util.Diag.log(c,
                        "watchdog recovery notification failed: " + t);
            } catch (Throwable ignored) { }
        }
    }
}
