package com.bolt.browser.core;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

/**
 * Lightweight foreground holder so downloads keep running when the browser
 * goes to the background. The engine drives the notification content.
 */
public class DownloadService extends Service {

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = DownloadEngine.get(this).buildSummary(this);
        ServiceCompat.startForeground(this, 1001, n,
                Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
        if (DownloadEngine.get(this).anyActive()) {
            return START_STICKY;
        }
        stopSelf();
        return START_NOT_STICKY;
    }
}
