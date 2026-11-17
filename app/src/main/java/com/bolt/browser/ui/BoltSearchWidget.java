package com.bolt.browser.ui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.bolt.browser.R;

/**
 * v1.3.79 — THE HOME-SCREEN SEARCH WIDGET ("add a search bar widget
 * support as well that I will add to homescreen later"). Long-press the
 * Android home screen -> Widgets -> Bolt search; one tap opens Bolt with
 * the omnibox open and the keyboard up (BrowserActivity reads the
 * bolt_focus_search extra in onCreate/onNewIntent — the activity is
 * singleTask, so a running Bolt is reused, never duplicated).
 *
 * Deliberately DUMB: no update period, no config activity, no list — a
 * light browser's widget is a launch affordance, not an app. The whole
 * provider exists so the launcher has something to instantiate; every
 * widget gets the same click PendingIntent.
 */
public class BoltSearchWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        try {
            Intent tap = new Intent(context, BrowserActivity.class);
            tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            tap.putExtra("bolt_focus_search", true);
            // requestCode unique per widget so each gets its own PendingIntent
            PendingIntent pi = PendingIntent.getActivity(
                    context, 0, tap,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_search);
            rv.setOnClickPendingIntent(R.id.widget_search_root, pi);
            if (ids != null && ids.length > 0) {
                manager.updateAppWidget(ids, rv);
            } else {
                manager.updateAppWidget(new ComponentName(context, BoltSearchWidget.class), rv);
            }
        } catch (Throwable ignored) {}
    }
}
