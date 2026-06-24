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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VoltageLogService extends Service {
    public static final String TAG = "CarVoltage";
    public static final String ACTION_START = "com.orbitaldelta.carvoltage.START";
    public static final String ACTION_STOP = "com.orbitaldelta.carvoltage.STOP";
    public static final String ACTION_KICK = "com.orbitaldelta.carvoltage.KICK";
    public static final String ACTION_START_SCAN = "com.orbitaldelta.carvoltage.START_SCAN";
    public static final String ACTION_FORCE_RESCAN = "com.orbitaldelta.carvoltage.FORCE_RESCAN";
    public static final String ACTION_VOLTAGE_UPDATE = "com.orbitaldelta.carvoltage.VOLTAGE_UPDATE";
    public static final String EXTRA_RAW = "raw";
    public static final String EXTRA_VOLTS = "volts";
    public static final String EXTRA_MATCHES = "matches";

    private static final int NOTIFICATION_ID = 114;
    private static final String CHANNEL_ID = "car_voltage_monitor";
    private static final Pattern VOLTAGE_LINE = Pattern.compile("msg\\s*:\\s*0\\s+114\\s+ints:\\s*\\[(\\d+)\\]");
    private static final Pattern UPDATE_LINE = Pattern.compile("updateCode\\s*=\\s*114");

    private static final long HEARTBEAT_MS = 30_000L;
    private static final long STALE_RESTART_MS = 90_000L;
    private static final long MAX_LOGCAT_AGE_MS = 20 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile long readerStartedAt;
    private volatile long lastLineAt;
    private volatile long lastMatchAt;

    private Thread readerThread;
    private Process logcatProcess;
    private int matchCount;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!stopRequested) {
                heartbeat("scheduled");
                handler.postDelayed(this, HEARTBEAT_MS);
            }
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, VoltageLogService.class).setAction(ACTION_START);
        startIntent(context, intent);
    }

    public static void kick(Context context) {
        Intent intent = new Intent(context, VoltageLogService.class).setAction(ACTION_KICK);
        startIntent(context, intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, VoltageLogService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    private static void startIntent(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        VoltageStore.setServiceRunning(this, true);
        Log.d(TAG, "Service created.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            Log.d(TAG, "Stop requested.");
            stopRequested = true;
            handler.removeCallbacks(heartbeatRunnable);
            stopReader();
            stopForeground(true);
            stopSelf();
            VoltageWatchdogReceiver.cancel(this);
            return START_NOT_STICKY;
        }

        stopRequested = false;
        startForeground(NOTIFICATION_ID, buildNotification(VoltageStore.get(this)));
        VoltageStore.setServiceRunning(this, true);
        VoltageWatchdogReceiver.schedule(this);

        if (ACTION_KICK.equals(action) || ACTION_FORCE_RESCAN.equals(action)) {
            heartbeat("kick");
        } else {
            startReader();
            heartbeat("start");
        }

        handler.removeCallbacks(heartbeatRunnable);
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!stopRequested) {
            start(this);
            VoltageWatchdogReceiver.schedule(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(heartbeatRunnable);
        stopReader();
        VoltageStore.setServiceRunning(this, false);
        if (!stopRequested) VoltageWatchdogReceiver.schedule(this);
        CarVoltageWidgetProvider.updateAll(this);
        Log.d(TAG, "Service destroyed. stopRequested=" + stopRequested);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void heartbeat(String reason) {
        long now = System.currentTimeMillis();
        long lastMatchAge = lastMatchAt == 0 ? Long.MAX_VALUE : now - lastMatchAt;
        long lastLineAge = lastLineAt == 0 ? Long.MAX_VALUE : now - lastLineAt;
        long readerAge = readerStartedAt == 0 ? Long.MAX_VALUE : now - readerStartedAt;

        Log.d(TAG, "Heartbeat reason=" + reason
                + " running=" + running
                + " lastMatchAgeMs=" + printableAge(lastMatchAge)
                + " lastLineAgeMs=" + printableAge(lastLineAge)
                + " readerAgeMs=" + printableAge(readerAge)
                + " matches=" + matchCount);

        VoltageStore.setServiceRunning(this, true);
        CarVoltageWidgetProvider.updateAll(this);
        pokeStockVoltageRefresh();

        if (!running || readerThread == null || !readerThread.isAlive()) {
            Log.d(TAG, "Reader not alive. Restarting logcat reader.");
            restartReader();
            return;
        }

        if (lastMatchAge > STALE_RESTART_MS || readerAge > MAX_LOGCAT_AGE_MS) {
            Log.d(TAG, "Reader stale. Restarting logcat reader.");
            restartReader();
        }
    }

    private String printableAge(long age) {
        return age == Long.MAX_VALUE ? "never" : String.valueOf(age);
    }

    private void pokeStockVoltageRefresh() {
        try {
            Runtime.getRuntime().exec(new String[]{"setprop", "persist.fyt.voltage_visible", "1"});
            Runtime.getRuntime().exec(new String[]{"am", "broadcast", "-a", "com.syu.voltage.visible"});
            Log.d(TAG, "Poked stock voltage refresh.");
        } catch (Exception e) {
            Log.e(TAG, "Stock voltage refresh poke failed.", e);
        }
    }

    private synchronized void startReader() {
        if (running && readerThread != null && readerThread.isAlive()) return;
        running = true;
        readerStartedAt = System.currentTimeMillis();
        lastLineAt = 0L;
        readerThread = new Thread(this::readLoop, "car-voltage-logcat-reader");
        readerThread.start();
    }

    private synchronized void restartReader() {
        stopReader();
        startReader();
    }

    private synchronized void stopReader() {
        running = false;
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    private void readLoop() {
        String[][] commands = new String[][]{
                {"logcat", "-v", "time", "-T", "1", "remote:E", "hzq5:I", "*:S"},
                {"logcat", "-v", "time", "-T", "1", "*:E"},
                {"logcat", "-v", "time", "-T", "1"}
        };

        while (running) {
            boolean matchedThisPass = false;
            for (String[] command : commands) {
                if (!running) return;
                Log.d(TAG, "Trying logcat command: " + Arrays.toString(command));
                try {
                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.redirectErrorStream(true);
                    logcatProcess = pb.start();
                    readerStartedAt = System.currentTimeMillis();
                    lastLineAt = 0L;

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcatProcess.getInputStream()))) {
                        String line;
                        int linesSeen = 0;

                        while (running && (line = reader.readLine()) != null) {
                            linesSeen++;
                            lastLineAt = System.currentTimeMillis();

                            if (handleLine(line)) {
                                matchedThisPass = true;
                            }

                            if (linesSeen % 500 == 0) {
                                Log.d(TAG, "logcat alive: lines=" + linesSeen + " matches=" + matchCount);
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Unable to run logcat command " + Arrays.toString(command), e);
                    broadcastStatusOnly();
                } finally {
                    if (logcatProcess != null) {
                        logcatProcess.destroy();
                        logcatProcess = null;
                    }
                }

                if (matchedThisPass || !running) {
                    break;
                }
            }

            if (running) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private boolean handleLine(String line) {
        Matcher matcher = VOLTAGE_LINE.matcher(line);
        if (matcher.find()) {
            int raw = Integer.parseInt(matcher.group(1));
            float volts = raw / 10.0f;
            matchCount++;
            lastMatchAt = System.currentTimeMillis();
            VoltageStore.save(this, raw, volts, matchCount);
            Log.d(TAG, "Parsed voltage raw=" + raw + " volts=" + VoltageFormat.volts(volts) + " matches=" + matchCount);
            notifyVoltage(raw, volts);
            return true;
        }

        if (UPDATE_LINE.matcher(line).find()) {
            Log.d(TAG, "Saw voltage update marker: " + line);
        }
        return false;
    }

    private void notifyVoltage(int raw, float volts) {
        VoltageStore.Snapshot snap = VoltageStore.get(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(snap));

        CarVoltageWidgetProvider.updateAll(this);

        Intent intent = new Intent(ACTION_VOLTAGE_UPDATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_RAW, raw)
                .putExtra(EXTRA_VOLTS, volts)
                .putExtra(EXTRA_MATCHES, matchCount);
        sendBroadcast(intent);
    }

    private void broadcastStatusOnly() {
        Intent intent = new Intent(ACTION_VOLTAGE_UPDATE).setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private Notification buildNotification(VoltageStore.Snapshot snap) {
        String title = snap.hasVoltage() ? "Car voltage: " + VoltageFormat.volts(snap.volts) : "Car voltage monitor";
        String text = snap.hasVoltage()
                ? "Raw " + snap.raw + " • updated " + VoltageFormat.age(snap.time)
                : "Waiting for SYU voltage logcat entries";

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(R.drawable.ic_stat_voltage)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Car Voltage Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows voltage parsed from SYU/FYT logcat messages");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
