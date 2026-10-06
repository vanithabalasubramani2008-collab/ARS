package com.abu.ars;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

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
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // UI Elements
    private TextView voiceText, tvLat, tvLon, tvBattery, tvTimestamp, tvStatus;
    private Button sosButton, relayButton, bluetoothHelpButton;
    private android.widget.ImageView pulseRing;

    // Animations
    private android.view.animation.Animation pulseScaleAnim, scanlineAnim;

    // Location & Device Info
    private FusedLocationProviderClient fusedLocationClient;
    private com.google.android.gms.location.LocationCallback locationCallback;
    private int batteryLevel;
    private String currentTimestamp;
    private double lastLat = 0.0, lastLon = 0.0;

    // Periodic Updates
    private final android.os.Handler uiHandler = new android.os.Handler();
    private final Runnable uiUpdater = new Runnable() {
        @Override
        public void run() {
            updateDeviceInfo();
            String delivery = getSharedPreferences(SOSService.STATUS_PREFS, MODE_PRIVATE)
                    .getString(SOSService.EXTRA_STATUS, "");
            if (!delivery.isEmpty()) tvStatus.setText(delivery);
            uiHandler.postDelayed(this, 1000); // Update time/battery every second
        }
    };

    // Vosk Voice Recognition
    private Model voskModel;
    private SpeechService speechService;
    private String spokenText = "";
    private String currentPartial = "";

    // Sound
    private MediaPlayer startSound, stopSound;

    // Constants & Networking
    private static final String TAG = "SOS_APP";
    private static final String PREFS_NAME = "EmergencyContacts";
    private static final String CONTACTS_KEY = "phone_numbers";
    private static final String APP_PREFS = "AppPrefs";
    private static final String IS_FIRST_TIME = "isFirstTime";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Volume Trigger Logic
    private int volumeClickCount = 0;
    private long lastVolumeClickTime = 0;

    // SOS Trigger Logic
    private long lastSPOSTriggerTime = 0;
    private boolean isSOSInProgress = false;
    private String lastSOSMessage = "SOS";
    private static final long SOS_COOLDOWN = 10000; // 10 seconds cooldown between triggers

    // ... existing fields ...

    // Bluetooth
    private static final int REQUEST_RELAY_PERMISSION = 103;
    private static final int REQUEST_DISCOVERABLE = 104;
    private static final String BLUETOOTH_HELP_PREFS = "BluetoothHelp";

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Show over lock screen
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        // 1. Initialize UI (Ensure these IDs exist in your activity_main.xml)
        voiceText = findViewById(R.id.voiceText);
        tvLat = findViewById(R.id.tvLat);
        tvLon = findViewById(R.id.tvLon);
        tvBattery = findViewById(R.id.tvBattery);
        tvTimestamp = findViewById(R.id.tvTimestamp);
        tvStatus = findViewById(R.id.tvStatus);
        sosButton = findViewById(R.id.sosButton);
        relayButton = findViewById(R.id.relayButton);
        bluetoothHelpButton = findViewById(R.id.bluetoothHelpButton);
        bluetoothHelpButton.setOnClickListener(v -> showBluetoothHelp());
        restoreBluetoothNameIfNeeded();
        updateRelayButton();
        relayButton.setOnClickListener(v -> toggleRelay());
        if (getSharedPreferences(SOSService.PREFS, MODE_PRIVATE).getBoolean(SOSService.ENABLED, false)
                && NearbyRelay.permitted(this)) startRelayService(SOSService.ACTION_RELAY);
        pulseRing = findViewById(R.id.pulseRing);

        // Load Animations
        pulseScaleAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse_scale);
        scanlineAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.scanline_anim);
        
        // Start animations
        pulseRing.startAnimation(pulseScaleAnim);
        findViewById(R.id.scanline).startAnimation(scanlineAnim);

        // 2. Initialize Location
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        setupLocationCallback();

        // 3. Request Permissions
        java.util.List<String> requested = new java.util.ArrayList<>();
        requested.add(Manifest.permission.ACCESS_FINE_LOCATION);
        requested.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        requested.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31) {
            requested.add(Manifest.permission.BLUETOOTH_SCAN);
            requested.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            requested.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requested.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            requested.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        ActivityCompat.requestPermissions(this, requested.toArray(new String[0]), 1);

        // 4. Initialize Sounds
        try {
            startSound = MediaPlayer.create(this, R.raw.start_sound);
            stopSound = MediaPlayer.create(this, R.raw.stop_sound);
        } catch (Exception e) {
            Log.e(TAG, "Error loading sounds", e);
        }

        // 5. Initialize Vosk Model
        initVosk();

        // 6. SOS Button Touch Logic (Hold for manual alert)
        sosButton.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (voskModel != null) {
                    if (startSound != null) startSound.start();
                    spokenText = ""; 
                    currentPartial = ""; // Reset both for fresh capture
                    voiceText.setText(R.string.manual_recording);
                    tvStatus.setText(R.string.status_manual_alert);
                } else {
                    Toast.makeText(this, R.string.model_loading, Toast.LENGTH_SHORT).show();
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (stopSound != null) stopSound.start();
                
                // Show "Processing..." in UI to stop flickering
                voiceText.setText("Processing Voice...");

                // 500ms delay to capture the final buffer from Vosk
                new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
                    String finalCaptured = !spokenText.isEmpty() ? spokenText : 
                                             (!currentPartial.isEmpty() ? currentPartial : "Manual Alert");

                    executor.execute(() -> {
                        String compressed = KeywordProcessor.compressMessage(finalCaptured);
                        String encryptedForUI = EncryptionHelper.encrypt(compressed);
                        
                        runOnUiThread(() -> {
                            voiceText.setText(getString(R.string.sos_display_format, finalCaptured, encryptedForUI));
                            triggerSOSWithMessages(finalCaptured, compressed);
                        });
                    });
                }, 700); // Increased delay slightly for better accuracy
            }
            return true;
        });

        // Initial device info update
        updateDeviceInfo();

        // Check if triggered from VolumeService - Moved to after UI init and check permissions
        if (getIntent() != null && getIntent().getBooleanExtra("triggerSOS", false)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                triggerSOSWithMessages("Accessibility Volume Trigger", "Accessibility Volume Trigger");
            } else {
                Toast.makeText(this, "Location permission required for SOS", Toast.LENGTH_LONG).show();
            }
        }

        // --- FIRST TIME SETUP CHECK ---
        checkFirstTimeSetup();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // Update activity intent
        if (intent.getBooleanExtra("triggerSOS", false)) {
            triggerSOSWithMessages(getString(R.string.trigger_volume_accessibility), getString(R.string.trigger_volume_accessibility));
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && event.getRepeatCount() == 0) {
            long currentTime = System.currentTimeMillis();
            // Increased window to 3 seconds for higher sensitivity
            if (currentTime - lastVolumeClickTime < 3000) {
                volumeClickCount++;
            } else {
                volumeClickCount = 1;
            }
            lastVolumeClickTime = currentTime;

            if (volumeClickCount >= 3) {
                triggerSOSWithMessages(getString(R.string.trigger_volume_triple), getString(R.string.trigger_volume_triple));
                volumeClickCount = 0; // Reset
            }
            return true; // Consume event to prevent default volume change while app is open
        }
        return super.onKeyDown(keyCode, event);
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // 1. Force an immediate fresh location fix (more reliable than getLastLocation)
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        updateLocationUI(location.getLatitude(), location.getLongitude());
                        tvStatus.setText(R.string.status_gps_synced);
                    }
                });

        // 2. High-Accuracy Continuous Updates
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
                .setMinUpdateIntervalMillis(1000)
                .setMaxUpdateDelayMillis(3000)
                .build();

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission error", e);
        }
    }

    private void updateLocationUI(double lat, double lon) {
        lastLat = lat;
        lastLon = lon;
        runOnUiThread(() -> {
            tvLat.setText(getString(R.string.lat_format, lat));
            tvLon.setText(getString(R.string.lon_format, lon));
        });
    }

    private void setupLocationCallback() {
        locationCallback = new com.google.android.gms.location.LocationCallback() {
            @Override
            public void onLocationResult(com.google.android.gms.location.LocationResult locationResult) {
                if (locationResult == null) return;
                for (android.location.Location location : locationResult.getLocations()) {
                    if (location != null) {
                        updateLocationUI(location.getLatitude(), location.getLongitude());
                    }
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.post(uiUpdater);
        
        // Reset UI to indicate searching if we don't have a location yet
        if (lastLat == 0.0) {
            tvLat.setText(R.string.lat_acquiring);
            tvLon.setText(R.string.lon_acquiring);
        }

        startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(uiUpdater);
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
             if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            }
            restoreBluetoothNameIfNeeded();
        } else if (requestCode == REQUEST_RELAY_PERMISSION) {
            if (NearbyRelay.permitted(this)) enableRelay();
            else Toast.makeText(this, "Nearby permissions are needed for phone relay", Toast.LENGTH_LONG).show();
        }
    }

    // ================= 📍 SOS CORE LOGIC =================

    private synchronized void triggerSOSWithMessages(String fullMsg, String compressedMsg) {
        long currentTime = System.currentTimeMillis();
        if (isSOSInProgress || (currentTime - lastSPOSTriggerTime < SOS_COOLDOWN)) {
            Log.d(TAG, "SOS Trigger blocked (In progress or Cooldown)");
            return;
        }
        isSOSInProgress = true;
        lastSOSMessage = fullMsg;
        lastSPOSTriggerTime = currentTime;

        stopSpeechService(); 
        runOnUiThread(() -> {
            tvStatus.setText(R.string.status_sos_triggered);
            Toast.makeText(this, R.string.status_sos_triggered, Toast.LENGTH_LONG).show();
        });
        updateDeviceInfo();

        double lat = lastLat;
        double lon = lastLon;

        Intent serviceIntent = new Intent(this, SOSService.class);
        serviceIntent.setAction(SOSService.ACTION_TRIGGER);
        serviceIntent.putExtra("full_message", fullMsg);
        serviceIntent.putExtra("compressed_message", compressedMsg);
        serviceIntent.putExtra("lat", lat);
        serviceIntent.putExtra("lon", lon);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        askSpokenHelp(fullMsg, lat, lon);
        runOnUiThread(() -> bluetoothHelpButton.setVisibility(android.view.View.VISIBLE));
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::startListening, 3000);
    }

    private void updateDeviceInfo() {
        // Battery Percentage
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm != null) {
            batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            runOnUiThread(() -> tvBattery.setText(getString(R.string.battery_format, batteryLevel)));
        }

        // Current Timestamp (ISO 8601 for consistency with Backend/SMS)
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        currentTimestamp = sdf.format(new Date());
        runOnUiThread(() -> tvTimestamp.setText(getString(R.string.timestamp_format, currentTimestamp)));
    }

    // ================= 🎤 VOSK VOICE TRIGGER =================

    private void initVosk() {
        runOnUiThread(() -> tvStatus.setText(R.string.status_loading_model));
        new Thread(() -> {
            try {
                File modelDir = new File(getExternalFilesDir(null), "model");
                if (!modelDir.exists()) copyAssets("model", modelDir);
                voskModel = new Model(modelDir.getAbsolutePath());
                runOnUiThread(() -> {
                    tvStatus.setText(R.string.status_active);
                    voiceText.setText(R.string.listening_keywords);
                    startListening(); // Auto-start background detection
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText(R.string.status_error_model));
                Log.e(TAG, "Vosk Model Init Error", e);
            }
        }).start();
    }

    private void startListening() {
        if (speechService != null) return; // Already listening
        isSOSInProgress = false; // Reset flag when starting to listen
        try {
            // Optimization for sensitivity: higher rate and recognizer config
            Recognizer recognizer = new Recognizer(voskModel, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(new RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) {
                    if (isSOSInProgress) return;
                    try {
                        JSONObject json = new JSONObject(hypothesis);
                        String partial = json.optString("partial", "").toLowerCase();
                        
                        if (!partial.isEmpty()) {
                            currentPartial = partial; // Update our internal memory
                            runOnUiThread(() -> {
                                if (!isSOSInProgress) {
                                    voiceText.setText(getString(R.string.captured_format, currentPartial.toUpperCase()));
                                }
                            });
                        }

                        // Passive Listening: Only trigger if we aren't already in an SOS
                        String[] triggerKeywords = {"help", "sos", "emergency", "danger", "police", "save", "attack", "stop", "fire"};
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastSPOSTriggerTime > SOS_COOLDOWN) {
                            for (String kw : triggerKeywords) {
                                if (currentPartial.contains(kw)) {
                                    final String capturedAtTrigger = currentPartial;
                                    triggerSOSWithMessages(capturedAtTrigger, kw);
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Partial Result Error", e);
                    }
                }

                @Override
                public void onResult(String hypothesis) {
                    if (isSOSInProgress) return;
                    try {
                        JSONObject json = new JSONObject(hypothesis);
                        spokenText = json.optString("text", "");
                        if (!spokenText.isEmpty()) {
                            currentPartial = spokenText; // Sync partial with final result
                        }
                        runOnUiThread(() -> {
                            if (!isSOSInProgress) {
                                voiceText.setText(spokenText);
                            }
                        });
                    } catch (Exception ignored) {}
                }

                @Override public void onFinalResult(String hypothesis) {}
                @Override public void onError(Exception e) { Log.e(TAG, "Vosk Error", e); }
                @Override public void onTimeout() {}
            });
        } catch (IOException e) {
            Log.e(TAG, "Listening Start Error", e);
        }
    }

    private void stopSpeechService() {
        if (speechService != null) {
            speechService.stop();
            speechService = null;
        }
    }

    // ================= 🛠 UTILITIES =================

    private void copyAssets(String assetPath, File outFile) throws IOException {
        String[] files = getAssets().list(assetPath);
        if (files == null || files.length == 0) {
            try (InputStream in = getAssets().open(assetPath);
                 OutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[1024];
                int nRead;
                while ((nRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, nRead);
                }
            }
            return;
        }
        if (!outFile.exists()) {
            if (!outFile.mkdirs()) {
                Log.e(TAG, "Failed to create directory: " + outFile.getAbsolutePath());
            }
        }
        for (String file : files) copyAssets(assetPath + "/" + file, new File(outFile, file));
    }

    private void toggleRelay() {
        boolean enabled = getSharedPreferences(SOSService.PREFS, MODE_PRIVATE)
                .getBoolean(SOSService.ENABLED, false);
        if (enabled) {
            getSharedPreferences(SOSService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(SOSService.ENABLED, false).apply();
            startRelayService(SOSService.ACTION_DISABLE);
            updateRelayButton();
        } else if (!NearbyRelay.permitted(this)) {
            java.util.List<String> missing = new java.util.ArrayList<>();
            if (Build.VERSION.SDK_INT >= 31) {
                for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT}) {
                    if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
                        missing.add(permission);
                }
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQUEST_RELAY_PERMISSION);
        } else enableRelay();
    }

    private void enableRelay() {
        getSharedPreferences(SOSService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(SOSService.ENABLED, true).apply();
        startRelayService(SOSService.ACTION_RELAY);
        updateRelayButton();
    }

    private void updateRelayButton() {
        boolean enabled = getSharedPreferences(SOSService.PREFS, MODE_PRIVATE)
                .getBoolean(SOSService.ENABLED, false);
        relayButton.setText(enabled ? R.string.relay_on : R.string.relay_off);
    }

    private void startRelayService(String action) {
        Intent intent = new Intent(this, SOSService.class).setAction(action);
        ContextCompat.startForegroundService(this, intent);
    }

    private void askSpokenHelp(String message, double lat, double lon) {
        runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Shout for help?")
                .setMessage("Play an emergency request aloud at high volume? SOS delivery continues either way.")
                .setPositiveButton("Shout for help", (dialog, which) -> new SpokenHelp(this, message, lat, lon))
                .setNegativeButton("Stay silent", (dialog, which) -> { })
                .show());
    }

    /** Optional visibility for bystanders scanning Bluetooth. This does not push a pairing alert. */
    private void showBluetoothHelp() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Turn on Bluetooth first", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_RELAY_PERMISSION);
            return;
        }
        String type = lastSOSMessage == null ? "" : lastSOSMessage.toLowerCase(Locale.US);
        String disaster = type.contains("fire") ? "FIRE" : type.contains("flood") ? "FLOOD" :
                type.contains("earthquake") ? "QUAKE" : type.contains("accident") ? "ACCIDENT" : "SOS";
        android.telephony.TelephonyManager telephony =
                (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        String emergencyNumber = telephony != null && "in".equalsIgnoreCase(telephony.getSimCountryIso())
                ? " CALL 112" : "";
        String helpName = (lastLat == 0 && lastLon == 0)
                ? "HELP " + disaster + emergencyNumber
                : String.format(Locale.US, "HELP %s %.3f,%.3f%s",
                        disaster, lastLat, lastLon, emergencyNumber);
        SharedPreferences prefs = getSharedPreferences(BLUETOOTH_HELP_PREFS, MODE_PRIVATE);
        if (!prefs.contains("previousName")) prefs.edit().putString("previousName", adapter.getName()).apply();
        if (!adapter.setName(helpName)) {
            Toast.makeText(this, "Could not change Bluetooth name", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putLong("restoreAt", System.currentTimeMillis() + 300_000).apply();
        Intent discoverable = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverable.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivityForResult(discoverable, REQUEST_DISCOVERABLE);
        uiHandler.postDelayed(this::restoreBluetoothNameIfNeeded, 300_000);
    }

    private void restoreBluetoothNameIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(BLUETOOTH_HELP_PREFS, MODE_PRIVATE);
        if (!prefs.contains("previousName") || System.currentTimeMillis() < prefs.getLong("restoreAt", 0)) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled() ||
                (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) return;
        if (adapter.setName(prefs.getString("previousName", "Phone"))) prefs.edit().clear().apply();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DISCOVERABLE && resultCode == RESULT_CANCELED) {
            getSharedPreferences(BLUETOOTH_HELP_PREFS, MODE_PRIVATE).edit().putLong("restoreAt", 0).apply();
            restoreBluetoothNameIfNeeded();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSpeechService();
        if (voskModel != null) voskModel.close();
        if (startSound != null) startSound.release();
        if (stopSound != null) stopSound.release();
        executor.shutdown();
    }

    // ================= 📋 EMERGENCY CONTACTS HELPERS =================

    /**
     * Call this method to save a new emergency contact.
     * Example: addEmergencyContact("1234567890");
     */
    private void addEmergencyContact(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // Must create a new HashSet because the returned set is immutable/linked to storage
        Set<String> contacts = new HashSet<>(prefs.getStringSet(CONTACTS_KEY, new HashSet<>()));
        
        if (contacts.add(phoneNumber)) {
            prefs.edit().putStringSet(CONTACTS_KEY, contacts).apply();
            Log.d(TAG, "CONTACT SAVED: " + phoneNumber);
            Log.d(TAG, "TOTAL CONTACTS: " + contacts.size());
        } else {
            Log.d(TAG, "CONTACT ALREADY EXISTS: " + phoneNumber);
        }
    }

    /**
     * Retrieves the current list of emergency contacts.
     */
    private Set<String> getEmergencyContacts() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> contacts = prefs.getStringSet(CONTACTS_KEY, new HashSet<>());
        Log.d(TAG, "RETRIEVED CONTACTS: " + contacts);
        return contacts;
    }

    // ================= 🚀 FIRST-TIME SETUP LOGIC =================

    private void checkFirstTimeSetup() {
        SharedPreferences appPrefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        boolean isFirstTime = appPrefs.getBoolean(IS_FIRST_TIME, true);

        if (isFirstTime) {
            showFirstTimeSetupDialog();
        }
    }

    private void showFirstTimeSetupDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("e.g. 9876543210");
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        
        // Simple padding/container for the programmatic view
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = 60;
        params.rightMargin = 60;
        params.topMargin = 20;
        input.setLayoutParams(params);
        container.addView(input);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Emergency Contact Setup")
                .setMessage("Welcome to ARS. Please enter your primary emergency contact number to get started:")
                .setView(container)
                .setCancelable(false) // Force setup on first run
                .setPositiveButton("Save & Continue", (dialog, which) -> {
                    String number = input.getText().toString().trim();
                    if (!number.isEmpty()) {
                        addEmergencyContact(number);
                        // Mark setup as complete
                        getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                                .edit().putBoolean(IS_FIRST_TIME, false).apply();
                        Toast.makeText(this, "Setup Complete!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "A contact number is required.", Toast.LENGTH_SHORT).show();
                        showFirstTimeSetupDialog(); // Re-show if empty
                    }
                })
                .show();
    }
}
