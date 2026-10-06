package com.abu.ars;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.content.Intent;
import android.os.VibrationEffect;
import android.os.Build;

public class VolumeService extends AccessibilityService {

    private static final String TAG = "ARS_VOLUME";
    private static final String TAG_BG = "ARS_BACKGROUND";
    int count = 0;
    long lastTime = 0;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "ARS_VOLUME: SERVICE_CONNECTED");
        Log.d(TAG_BG, "ARS_BACKGROUND: Volume Accessibility Service Active");
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = pm != null && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH ? pm.isInteractive() : pm.isScreenOn());

        if (event.getAction() == KeyEvent.ACTION_DOWN &&
                (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ||
                        event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN)) {

            Log.d(TAG_BG, "ARS_BACKGROUND: Volume event received (Action Down)");
            
            if (!isScreenOn) {
                if (pm != null) {
                    PowerManager.WakeLock tempLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ARS:VolumeEvent");
                    tempLock.acquire(1000);
                }
                vibrateTiny(); 
            }

            long current = System.currentTimeMillis();
            if (current - lastTime < 3000) count++;
            else count = 1;

            lastTime = current;
            Log.d(TAG, "ARS_VOLUME: PRESS_COUNT = " + count);

            if (count == 3) {
                count = 0;
                Log.d(TAG, "ARS_VOLUME: TRIPLE_PRESS_DETECTED");
                vibrate();

                try {
                    if (pm != null && !isScreenOn) {
                        PowerManager.WakeLock screenLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "ARS:EmergencyWake");
                        screenLock.acquire(10000);
                    }

                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    i.putExtra("triggerSOS", true);
                    i.putExtra("trigger_type", "ACCESSIBILITY");
                    i.putExtra("full_message", "Triple Volume Button Trigger");

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 11, i, 
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                        
                        androidx.core.app.NotificationCompat.Builder nb = new androidx.core.app.NotificationCompat.Builder(this, VoiceService.ALERT_CHANNEL_ID)
                                .setSmallIcon(R.mipmap.apple)
                                .setContentTitle("🚨 EMERGENCY DETECTED 🚨")
                                .setContentText("Triple Volume Press detected.")
                                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                                .setFullScreenIntent(pi, true)
                                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                                .setOngoing(true)
                                .setAutoCancel(true);
                        
                        if (nm != null) {
                            nm.notify(4, nb.build());
                        }
                    }

                    startActivity(i);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to trigger SOS", e);
                }
            }
            
            if (!isScreenOn) return true;
        }

        return super.onKeyEvent(event);
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 100, 200, 100, 500}, -1));
            } else {
                v.vibrate(new long[]{0, 200, 100, 200, 100, 500}, -1);
            }
        }
    }

    private void vibrateTiny() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(100);
            }
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
}
