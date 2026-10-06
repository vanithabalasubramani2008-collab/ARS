package com.abu.ars;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.content.Intent;
import android.os.VibrationEffect;
import android.os.Build;

public class VolumeService extends AccessibilityService {

    int count = 0;
    long lastTime = 0;

    @Override
    public boolean onKeyEvent(KeyEvent event) {

        if (event.getAction() == KeyEvent.ACTION_UP &&
                (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ||
                        event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN)) {

            long current = System.currentTimeMillis();

            if (current - lastTime < 1000) count++;
            else count = 1;

            lastTime = current;

            if (count == 3) {
                count = 0;
                vibrate();

                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                i.putExtra("triggerSOS", true);
                startActivity(i);
            }
        }

        return super.onKeyEvent(event);
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(500);
            }
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
}
