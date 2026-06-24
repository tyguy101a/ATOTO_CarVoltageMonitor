package com.orbitaldelta.carvoltage;

import android.content.Context;
import android.content.SharedPreferences;

public final class VoltageStore {
    private static final String PREFS = "car_voltage_prefs";
    private static final String KEY_RAW = "raw";
    private static final String KEY_VOLTS = "volts";
    private static final String KEY_TIME = "time";
    private static final String KEY_SERVICE_RUNNING = "service_running";
    private static final String KEY_MATCHES = "matches";

    private VoltageStore() {}

    public static void save(Context context, int raw, float volts, int matches) {
        prefs(context).edit()
                .putInt(KEY_RAW, raw)
                .putFloat(KEY_VOLTS, volts)
                .putLong(KEY_TIME, System.currentTimeMillis())
                .putInt(KEY_MATCHES, matches)
                .apply();
    }

    public static void setServiceRunning(Context context, boolean running) {
        prefs(context).edit().putBoolean(KEY_SERVICE_RUNNING, running).apply();
    }

    public static Snapshot get(Context context) {
        SharedPreferences p = prefs(context);
        return new Snapshot(
                p.getInt(KEY_RAW, -1),
                p.getFloat(KEY_VOLTS, Float.NaN),
                p.getLong(KEY_TIME, 0L),
                p.getBoolean(KEY_SERVICE_RUNNING, false),
                p.getInt(KEY_MATCHES, 0)
        );
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static final class Snapshot {
        public final int raw;
        public final float volts;
        public final long time;
        public final boolean serviceRunning;
        public final int matches;

        Snapshot(int raw, float volts, long time, boolean serviceRunning, int matches) {
            this.raw = raw;
            this.volts = volts;
            this.time = time;
            this.serviceRunning = serviceRunning;
            this.matches = matches;
        }

        public boolean hasVoltage() {
            return raw >= 0 && !Float.isNaN(volts);
        }
    }
}
