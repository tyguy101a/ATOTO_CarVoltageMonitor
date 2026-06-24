package com.orbitaldelta.carvoltage;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class AtotoKeepAliveService extends Service {
    public static final String ACTION_START = "com.orbitaldelta.carvoltage.keepAlive";
    public static final String ACTION_STOP = "com.orbitaldelta.carvoltage.STOP_KEEPALIVE";

    private static final String TAG = "CarVoltage";
    private static final int NOTIFICATION_ID = 115;
    private static final String CHANNEL_ID = "car_voltage_keepalive";
    private static final long KICK_INTERVAL_MS = 30000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean stoppedByUser = false;

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override public void run() {
            if (!stoppedByUser) {
                Log.d(TAG, "AtotoKeepAliveService tick - starting VoltageLogService");
                VoltageLogService.kick(getApplicationContext());
                CarVoltageWidgetProvider.updateAll(getApplicationContext());
                handler.postDelayed(this, KICK_INTERVAL_MS);
            }
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, AtotoKeepAliveService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, AtotoKeepAliveService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Log.d(TAG, "AtotoKeepAliveService created");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stoppedByUser = true;
            handler.removeCallbacks(keepAliveRunnable);
            stopForeground(true);
            stopSelf();
            Log.d(TAG, "AtotoKeepAliveService stopped by user");
            return START_NOT_STICKY;
        }

        stoppedByUser = false;
        startForeground(NOTIFICATION_ID, buildNotification());
        VoltageLogService.start(this);
        VoltageLogService.kick(this);
        CarVoltageWidgetProvider.updateAll(this);

        handler.removeCallbacks(keepAliveRunnable);
        handler.postDelayed(keepAliveRunnable, KICK_INTERVAL_MS);

        Log.d(TAG, "AtotoKeepAliveService running");
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!stoppedByUser) {
            Log.d(TAG, "AtotoKeepAliveService task removed - restarting self and reader");
            start(this);
            VoltageLogService.start(this);
            VoltageLogService.kick(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(keepAliveRunnable);
        if (!stoppedByUser) {
            Log.d(TAG, "AtotoKeepAliveService destroyed unexpectedly - restarting self");
            try {
                start(this);
            } catch (Exception e) {
                Log.e(TAG, "AtotoKeepAliveService restart failed", e);
            }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setSmallIcon(R.drawable.ic_stat_voltage)
                .setContentTitle("Car voltage active")
                .setContentText("Keeping voltage widget running")
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Car Voltage Keepalive",
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Keeps the car voltage widget running");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
