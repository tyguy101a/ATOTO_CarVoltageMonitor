package com.orbitaldelta.carvoltage;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

public class VoltageWatchdogReceiver extends BroadcastReceiver {
    public static final String ACTION_WATCHDOG = "com.orbitaldelta.carvoltage.WATCHDOG";
    private static final String TAG = "CarVoltage";
    private static final int REQUEST_CODE = 220114;
    private static final long INTERVAL_MS = 60_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Watchdog received: " + (intent == null ? "null" : intent.getAction()));
        VoltageLogService.kick(context);
        schedule(context);
        CarVoltageWidgetProvider.updateAll(context);
    }

    public static void schedule(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        PendingIntent pi = pendingIntent(context);
        long triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS;

        if (Build.VERSION.SDK_INT >= 23) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        }

        Log.d(TAG, "Watchdog scheduled.");
    }

    public static void cancel(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent(context));
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, VoltageWatchdogReceiver.class).setAction(ACTION_WATCHDOG);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
