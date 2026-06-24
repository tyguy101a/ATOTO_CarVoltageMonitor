package com.orbitaldelta.carvoltage;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

public class CarVoltageWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_WIDGET_TAP = "com.orbitaldelta.carvoltage.WIDGET_TAP";

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && ACTION_WIDGET_TAP.equals(intent.getAction())) {
            AtotoKeepAliveService.start(context);
            VoltageLogService.kick(context);
            updateAll(context);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateWidgets(context, appWidgetManager, appWidgetIds);
        AtotoKeepAliveService.start(context);
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName name = new ComponentName(context, CarVoltageWidgetProvider.class);
        updateWidgets(context, manager, manager.getAppWidgetIds(name));
    }

    private static void updateWidgets(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        VoltageStore.Snapshot snap = VoltageStore.get(context);
        for (int id : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.car_voltage_widget);
            views.setTextViewText(R.id.widget_voltage, snap.hasVoltage() ? VoltageFormat.volts(snap.volts) : "--.- V");

            String status;
            if (snap.hasVoltage()) {
                status = "Last read " + VoltageFormat.clockTime(snap.time);
            } else {
                status = snap.serviceRunning ? "Waiting for voltage" : "Tap to restart service";
            }
            views.setTextViewText(R.id.widget_status, status);

            Intent tapIntent = new Intent(context, AtotoKeepAliveService.class)
                    .setAction("com.orbitaldelta.carvoltage.WIDGET_RESTART");
            PendingIntent pendingIntent = PendingIntent.getService(
                    context,
                    id,
                    tapIntent,
                    (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0) | PendingIntent.FLAG_UPDATE_CURRENT
            );
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);
            manager.updateAppWidget(id, views);
        }
    }
}
