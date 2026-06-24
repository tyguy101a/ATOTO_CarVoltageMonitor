package com.orbitaldelta.carvoltage;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private TextView voltageText;
    private TextView rawText;
    private TextView statusText;
    private TextView logText;

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateFromStore();
            if (intent != null && intent.hasExtra(VoltageLogService.EXTRA_RAW)) {
                int raw = intent.getIntExtra(VoltageLogService.EXTRA_RAW, -1);
                float volts = intent.getFloatExtra(VoltageLogService.EXTRA_VOLTS, Float.NaN);
                int matches = intent.getIntExtra(VoltageLogService.EXTRA_MATCHES, 0);
                append("Parsed " + VoltageFormat.volts(volts) + " raw=" + raw + " matches=" + matches);
            }
        }
    };

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateFromStore();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        AtotoKeepAliveService.start(this);
        VoltageLogService.kick(this);
        VoltageWatchdogReceiver.schedule(this);
        append("Started foreground voltage service and watchdog.");
        updateFromStore();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(updateReceiver, new IntentFilter(VoltageLogService.ACTION_VOLTAGE_UPDATE));
        handler.post(refreshRunnable);
        updateFromStore();
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(updateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Car Voltage");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 18);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        voltageText = new TextView(this);
        voltageText.setText("--.- V");
        voltageText.setTextSize(52);
        voltageText.setTypeface(Typeface.DEFAULT_BOLD);
        voltageText.setGravity(Gravity.CENTER);
        voltageText.setPadding(0, 4, 0, 4);
        root.addView(voltageText, new LinearLayout.LayoutParams(-1, -2));

        rawText = new TextView(this);
        rawText.setText("Raw: none yet");
        rawText.setTextSize(16);
        rawText.setGravity(Gravity.CENTER);
        root.addView(rawText, new LinearLayout.LayoutParams(-1, -2));

        statusText = new TextView(this);
        statusText.setText("Starting…");
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 8, 0, 18);
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        Button startButton = new Button(this);
        startButton.setText("Start service");
        startButton.setOnClickListener(v -> {
            AtotoKeepAliveService.start(this);
            VoltageLogService.kick(this);
            VoltageWatchdogReceiver.schedule(this);
            append("Start/kick/watchdog requested.");
            updateFromStore();
        });
        buttons.addView(startButton, new LinearLayout.LayoutParams(0, -2, 1));

        Button stopButton = new Button(this);
        stopButton.setText("Stop service");
        stopButton.setOnClickListener(v -> {
            VoltageLogService.stop(this);
            AtotoKeepAliveService.stop(this);
            VoltageWatchdogReceiver.cancel(this);
            append("Stop requested and watchdog canceled.");
            updateFromStore();
        });
        buttons.addView(stopButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        Button refreshButton = new Button(this);
        refreshButton.setText("Trigger stock voltage refresh");
        refreshButton.setOnClickListener(v -> {
            try {
                Runtime.getRuntime().exec(new String[]{"setprop", "persist.fyt.voltage_visible", "1"});
                Runtime.getRuntime().exec(new String[]{"am", "broadcast", "-a", "com.syu.voltage.visible"});
                append("Requested com.syu.voltage.visible refresh.");
            } catch (Exception e) {
                append("Refresh request failed: " + e.getMessage());
            }
        });
        root.addView(refreshButton, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("Requires ADB-granted READ_LOGS. The foreground service keeps updating when the app is closed. A watchdog restarts it on car wake/resume, and tapping the widget restarts it if needed. Widget updates from this app's foreground service.");
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, 12, 0, 8);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setMovementMethod(new ScrollingMovementMethod());
        root.addView(logText, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private void updateFromStore() {
        VoltageStore.Snapshot snap = VoltageStore.get(this);
        if (snap.hasVoltage()) {
            voltageText.setText(VoltageFormat.volts(snap.volts));
            rawText.setText("Raw: " + snap.raw + "  •  Matches: " + snap.matches);
            statusText.setText("Last read " + VoltageFormat.clockTime(snap.time) + " • " + VoltageFormat.age(snap.time) + (snap.serviceRunning ? " • service running" : " • service stopped"));
        } else {
            voltageText.setText("--.- V");
            rawText.setText("Raw: none yet");
            statusText.setText(snap.serviceRunning ? "Waiting for voltage logcat entry…" : "Service stopped");
        }
    }

    private void append(String message) {
        String line = timeFormat.format(new Date()) + "  " + message + "\n";
        String existing = logText.getText().toString();
        String next = existing + line;
        if (next.length() > 9000) {
            next = next.substring(next.length() - 9000);
        }
        logText.setText(next);
        int scrollAmount = logText.getLayout() == null ? 0 : logText.getLayout().getLineTop(logText.getLineCount()) - logText.getHeight();
        logText.scrollTo(0, Math.max(scrollAmount, 0));
    }
}
