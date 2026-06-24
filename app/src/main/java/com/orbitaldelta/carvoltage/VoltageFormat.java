package com.orbitaldelta.carvoltage;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class VoltageFormat {
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.0");

    private VoltageFormat() {}

    public static String volts(float volts) {
        if (Float.isNaN(volts)) return "--.- V";
        return ONE_DECIMAL.format(volts) + " V";
    }

    public static String age(long timeMillis) {
        if (timeMillis <= 0) return "No reading yet";
        long seconds = Math.max(0, (System.currentTimeMillis() - timeMillis) / 1000L);
        if (seconds < 5) return "just now";
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60L;
        if (minutes < 60) return minutes + "m ago";
        return (minutes / 60L) + "h ago";
    }

public static String clockTime(long timeMillis) {
    if (timeMillis <= 0L) {
        return "--:--";
    }
    return new SimpleDateFormat("h:mm a", Locale.US).format(new Date(timeMillis));
}

}
