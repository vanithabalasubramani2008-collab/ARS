package com.abu.ars;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("ARS_BOOT", "Boot completed, starting services...");
            
            // Start Voice Service
            Intent voiceIntent = new Intent(context, VoiceService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(voiceIntent);
            } else {
                context.startService(voiceIntent);
            }
        }
    }
}
