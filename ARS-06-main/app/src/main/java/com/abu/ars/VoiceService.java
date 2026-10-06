package com.abu.ars;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class VoiceService extends Service {
    private static final String TAG = "VoiceService";
    private static final String TAG_VOICE = "ARS_VOICE";
    private static final String CHANNEL_ID = "VoiceServiceChannel";
    public static final String ALERT_CHANNEL_ID = "EmergencyTriggerChannel";
    
    private Model voskModel;
    private SpeechService speechService;
    private String currentPartial = "";
    private String spokenText = "";
    private boolean isSOSInProgress = false;
    private long lastSPOSTriggerTime = 0;
    private static final long SOS_COOLDOWN = 10000;

    private boolean isInitialized = false;
    private boolean isInitializing = false;
    private boolean hasStartedSpeaking = false;
    private android.os.PowerManager.WakeLock wakeLock;

    private final IBinder binder = new VoiceBinder();

    public class VoiceBinder extends Binder {
        VoiceService getService() {
            return VoiceService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG_VOICE, "VOICE_SERVICE_CREATED");
        Log.d(TAG_VOICE, "Background voice service active");
        createNotificationChannels();
        
        // Acquire partial wakelock to keep CPU running when screen is off
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ARS:VoiceWakelock");
            wakeLock.acquire(24 * 60 * 60 * 1000L); // 24 hours safety timeout
        }

        // Start foreground with explicit microphone type for Android 14+ compatibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(2, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(2, createNotification());
        }
        
        // Initial attempt - may fail if permission not yet granted in Activity
        initVosk();

        // Heartbeat log to verify service is alive
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG_VOICE, "ARS_VOICE: Service Heartbeat - Active=" + isInitialized + ", Listening=" + (speechService != null));
                new android.os.Handler(getMainLooper()).postDelayed(this, 30000); // Every 30 seconds
            }
        }, 30000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("ARS_VOICE", "VOICE_SERVICE_ON_START_COMMAND");
        if (intent != null && intent.getBooleanExtra("start_listening", false)) {
            Log.d("ARS_VOICE", "Manual initialization trigger received");
            initVosk();
        }
        return START_STICKY;
    }

    public synchronized void initVosk() {
        if (isInitialized) {
            Log.d("ARS_VOICE", "Vosk already initialized");
            startListening(); // Ensure listening is active
            return;
        }

        if (isInitializing) {
            Log.d("ARS_VOICE", "Vosk is already initializing...");
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("ARS_VOICE", "Speech recognition error: Microphone permission not granted");
            return;
        }

        isInitializing = true;
        Log.d("ARS_VOICE", "Initializing voice recognition");

        new Thread(() -> {
            try {
                // Use internal storage for the model to ensure accessibility and reliability
                File modelDir = new File(getFilesDir(), "model");
                
                if (!modelDir.exists() || modelDir.list() == null || modelDir.list().length == 0) {
                    Log.d("ARS_VOICE", "Model not found in internal storage. Copying from assets...");
                    if (modelDir.exists()) deleteRecursive(modelDir);
                    copyAssets("model", modelDir);
                }

                Log.d("ARS_VOICE", "VOSK_MODEL_LOADING from: " + modelDir.getAbsolutePath());
                voskModel = new Model(modelDir.getAbsolutePath());
                Log.d("ARS_VOICE", "Microphone ready");
                
                isInitialized = true;
                isInitializing = false;
                startListening();
            } catch (Exception e) {
                Log.e("ARS_VOICE", "Speech recognition error: " + e.getMessage(), e);
                isInitializing = false;
            }
        }).start();
    }

    private void startListening() {
        if (speechService != null || voskModel == null) {
            Log.d(TAG_VOICE, "Skipping startListening: service running=" + (speechService != null));
            return;
        }
        
        Log.d(TAG_VOICE, "Starting microphone listening");
        Log.d(TAG_VOICE, "Microphone listening");

        try {
            Recognizer recognizer = new Recognizer(voskModel, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            
            speechService.startListening(new RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) {
                    try {
                        JSONObject json = new JSONObject(hypothesis);
                        String partial = json.optString("partial", "").toLowerCase().trim();
                        if (!partial.isEmpty()) {
                            if (!hasStartedSpeaking) {
                                Log.d(TAG_VOICE, "User started speaking");
                                Log.d(TAG_VOICE, "Speech detected");
                                hasStartedSpeaking = true;
                            }
                            currentPartial = partial;
                            broadcastResult(partial, true);
                            checkKeywords(partial);
                        }
                    } catch (Exception e) {
                        Log.e(TAG_VOICE, "Partial Result Error", e);
                    }
                }

                @Override
                public void onResult(String hypothesis) {
                    Log.d(TAG_VOICE, "User stopped speaking");
                    Log.d(TAG_VOICE, "Voice result received");
                    hasStartedSpeaking = false;
                    try {
                        JSONObject json = new JSONObject(hypothesis);
                        spokenText = json.optString("text", "").toLowerCase().trim();
                        if (!spokenText.isEmpty()) {
                            Log.d(TAG_VOICE, "Recognized text: " + spokenText);
                            currentPartial = spokenText;
                            broadcastResult(spokenText, false);
                            checkKeywords(spokenText);
                        }
                    } catch (Exception e) {
                        Log.e(TAG_VOICE, "Result Error", e);
                    }
                }

                @Override public void onFinalResult(String hypothesis) { }
                
                @Override public void onError(Exception e) { 
                    Log.e("ARS_VOICE", "Speech recognition error: " + e.getMessage(), e);
                    restartListeningDelayed();
                }
                
                @Override public void onTimeout() {
                    Log.d("ARS_VOICE", "VOSK_EVENT: onTimeout");
                    restartListeningDelayed();
                }
            });
            Log.d("ARS_VOICE", "LISTENING_STARTED");
        } catch (Exception e) {
            Log.e("ARS_VOICE", "Speech recognition error: " + e.getMessage(), e);
        }
    }

    private void checkKeywords(String text) {
        if (text == null || text.isEmpty()) return;
        
        String normalized = text.toLowerCase().trim().replaceAll("[^a-z0-9\\s]", "");
        String[] triggerKeywords = {"help", "sos", "emergency", "danger", "police", "save", "attack", "stop", "fire"};
        
        long currentTime = System.currentTimeMillis();
        if (!isSOSInProgress && (currentTime - lastSPOSTriggerTime > SOS_COOLDOWN)) {
            for (String kw : triggerKeywords) {
                if (normalized.contains(kw)) {
                    Log.d(TAG_VOICE, "Emergency keyword detected: " + kw);
                    Log.d(TAG_VOICE, "Recognized text: " + text);
                    Log.d(TAG_VOICE, "Triggering existing SOS");
                    triggerSOS(text, kw);
                    break;
                }
            }
        }
    }

    private void restartListeningDelayed() {
        if (speechService != null) {
            try {
                speechService.stop();
            } catch (Exception ignored) {}
            speechService = null;
        }
        new android.os.Handler(getMainLooper()).postDelayed(this::startListening, 1500);
    }

    private void triggerSOS(String fullMsg, String keyword) {
        isSOSInProgress = true;
        lastSPOSTriggerTime = System.currentTimeMillis();
        
        Log.d(TAG_VOICE, "ARS_VOICE: EMERGENCY_DETECTED -> keyword=" + keyword);
        vibrate(); // Provide immediate haptic feedback
        
        // Wake up screen to show countdown
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            android.os.PowerManager.WakeLock screenLock = pm.newWakeLock(android.os.PowerManager.FULL_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP | android.os.PowerManager.ON_AFTER_RELEASE, "ARS:VoiceEmergencyWake");
            screenLock.acquire(10000); // 10 seconds for countdown
        }

        // Use FullScreenIntent to break through lock screen
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("triggerSOS", true);
        intent.putExtra("trigger_type", "VOICE");
        intent.putExtra("full_message", "Voice Trigger: " + fullMsg);
        intent.putExtra("keyword", keyword);

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(this, 10,
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                        .setSmallIcon(R.mipmap.apple)
                        .setContentTitle("🚨 EMERGENCY DETECTED 🚨")
                        .setContentText("Voice trigger '" + keyword + "' detected.")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setFullScreenIntent(fullScreenPendingIntent, true)
                        .setOngoing(true)
                        .setAutoCancel(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(3, notificationBuilder.build());
        }

        // Also try direct start for extra reliability
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG_VOICE, "Direct startActivity failed", e);
        }

        // Reset flag after cooldown
        new android.os.Handler(getMainLooper()).postDelayed(() -> isSOSInProgress = false, SOS_COOLDOWN);
    }

    private void vibrate() {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(1000, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(1000);
            }
        }
    }

    private void broadcastResult(String text, boolean isPartial) {
        Intent intent = new Intent("VoskResult");
        intent.putExtra("text", text);
        intent.putExtra("isPartial", isPartial);
        sendBroadcast(intent);
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    private void copyAssets(String path, File outDir) throws IOException {
        String[] assets = getAssets().list(path);
        if (assets == null || assets.length == 0) {
            // It's a file
            if (!outDir.getParentFile().exists()) outDir.getParentFile().mkdirs();
            try (InputStream in = getAssets().open(path);
                 OutputStream out = new FileOutputStream(outDir)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                Log.d("ARS_VOICE", "Copied file: " + path + " (Size: " + outDir.length() + ")");
            }
        } else {
            // It's a directory
            if (!outDir.exists()) outDir.mkdirs();
            for (String asset : assets) {
                copyAssets(path + "/" + asset, new File(outDir, asset));
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG_VOICE, "VOICE_SERVICE_ON_DESTROY");
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (speechService != null) {
            speechService.stop();
            speechService = null;
        }
        if (voskModel != null) {
            voskModel.close();
            voskModel = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // Methods for MainActivity to call via Binder
    public String getCurrentPartial() { return currentPartial; }
    public String getSpokenText() { return spokenText; }
    public void resetManualStrings() {
        currentPartial = "";
        spokenText = "";
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                // Persistent Background Channel
                NotificationChannel serviceChannel = new NotificationChannel(
                        CHANNEL_ID,
                        "Voice Recognition Service Channel",
                        NotificationManager.IMPORTANCE_LOW
                );
                manager.createNotificationChannel(serviceChannel);

                // Emergency Alert Channel (High Importance)
                NotificationChannel alertChannel = new NotificationChannel(
                        ALERT_CHANNEL_ID,
                        "Emergency Trigger Alerts",
                        NotificationManager.IMPORTANCE_HIGH
                );
                alertChannel.setDescription("Notifications for voice/volume emergency triggers");
                alertChannel.enableVibration(true);
                alertChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                manager.createNotificationChannel(alertChannel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Voice Recognition Active")
                .setContentText("Monitoring for emergency triggers...")
                .setSmallIcon(R.mipmap.apple)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
}
