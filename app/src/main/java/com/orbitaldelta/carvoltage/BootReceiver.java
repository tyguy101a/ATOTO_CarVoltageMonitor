package com.orbitaldelta.carvoltage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "CarVoltage";

    @Override public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "BootReceiver received " + (intent == null ? "null" : intent.getAction()));
        AtotoKeepAliveService.start(context);
        CarVoltageWidgetProvider.updateAll(context);
    }
}
