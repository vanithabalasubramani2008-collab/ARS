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
import android.os.IBinder;
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
    private Button sosButton;
    private android.widget.ImageView pulseRing;

    // Countdown UI
    private android.widget.LinearLayout countdownOverlay;
    private TextView tvCountdownTimer;
    private Button btnCancelSos;
    private android.os.CountDownTimer sosCountdownTimer;

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
            uiHandler.postDelayed(this, 1000); // Update time/battery every second
        }
    };

    private VoiceService voiceService;
    private boolean isBound = false;
    private final BroadcastReceiver voskReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String text = intent.getStringExtra("text");
            boolean isPartial = intent.getBooleanExtra("isPartial", false);
            if (text != null && !isSOSInProgress) {
                runOnUiThread(() -> {
                    if (isPartial) {
                        voiceText.setText(getString(R.string.captured_format, text.toUpperCase()));
                    } else {
                        voiceText.setText(text);
                    }
                });
            }
        }
    };

    private final android.content.ServiceConnection connection = new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name, IBinder service) {
            VoiceService.VoiceBinder binder = (VoiceService.VoiceBinder) service;
            voiceService = binder.getService();
            isBound = true;
            runOnUiThread(() -> tvStatus.setText(R.string.status_active));
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName name) {
            isBound = false;
        }
    };

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
    private static final long SOS_COOLDOWN = 10000; // 10 seconds cooldown between triggers

    // ... existing fields ...

    // Bluetooth
    private BluetoothAdapter bluetoothAdapter;
    private static final int REQUEST_BLUETOOTH_PERMISSION = 101;
    private static final int REQUEST_ENABLE_BT = 102;
    private static final int REQUEST_RELAY_PERMISSION = 103;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        // --- AUTO ENABLE BLUETOOTH ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = bluetoothManager.getAdapter();
        } else {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        if (bluetoothAdapter != null) {
            if (!bluetoothAdapter.isEnabled()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+ — need runtime permission first
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothAdapter.enable();
                        setupBluetoothAutoconnect();
                    } else {
                        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_PERMISSION);
                    }
                } else {
                    // Android 11 and below — direct enable works if declared in manifest
                    bluetoothAdapter.enable();
                    setupBluetoothAutoconnect();
                }
            } else {
                setupBluetoothAutoconnect();
            }
        }

        // --- AUTO ENABLE WIFI ---
        WifiManager wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        if (wifiManager != null && !wifiManager.isWifiEnabled()) {
            // Android 10+ — only option is system panel
            Intent wifiIntent = new Intent(Settings.Panel.ACTION_WIFI);
            startActivityForResult(wifiIntent, 200);
        }

        // 1. Initialize UI (Ensure these IDs exist in your activity_main.xml)
        voiceText = findViewById(R.id.voiceText);
        tvLat = findViewById(R.id.tvLat);
        tvLon = findViewById(R.id.tvLon);
        tvBattery = findViewById(R.id.tvBattery);
        tvTimestamp = findViewById(R.id.tvTimestamp);
        tvStatus = findViewById(R.id.tvStatus);
        sosButton = findViewById(R.id.sosButton);
        pulseRing = findViewById(R.id.pulseRing);

        // Countdown UI
        countdownOverlay = findViewById(R.id.countdownOverlay);
        tvCountdownTimer = findViewById(R.id.tvCountdownTimer);
        btnCancelSos = findViewById(R.id.btnCancelSos);

        btnCancelSos.setOnClickListener(v -> cancelSOSCountdown());

        // Load Animations
        pulseScaleAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse_scale);
        scanlineAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.scanline_anim);
        
        // Start animations
        pulseRing.startAnimation(pulseScaleAnim);
        findViewById(R.id.scanline).startAnimation(scanlineAnim);

        // --- DIAGNOSTIC TEST (HIDDEN TRIGGER) ---
        findViewById(R.id.tvLat).setOnLongClickListener(v -> {
            runDiagnostic("TWILIO");
            return true;
        });
        findViewById(R.id.tvLon).setOnLongClickListener(v -> {
            // Use a local number if known, or just prompt
            android.widget.EditText input = new android.widget.EditText(this);
            input.setHint("Enter local number");
            input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Local Diagnostic")
                .setView(input)
                .setPositiveButton("Send", (d, w) -> runDiagnostic(input.getText().toString()))
                .show();
            return true;
        });

        // 2. Initialize Location
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        setupLocationCallback();

        // 3. Request Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String[] permissions;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions = new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.POST_NOTIFICATIONS,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                };
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissions = new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT
                    };
            } else {
                permissions = new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.SEND_SMS
                };
            }
            ActivityCompat.requestPermissions(this, permissions, 1);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.SEND_SMS
            }, 1);
        }

        // --- CHECK ACCESSIBILITY & BATTERY & OVERLAY ---
        checkAccessibilityService();
        requestBatteryOptimizationExemption();
        checkOverlayPermission();

        // 4. Initialize Sounds
        try {
            startSound = MediaPlayer.create(this, R.raw.start_sound);
            stopSound = MediaPlayer.create(this, R.raw.stop_sound);
        } catch (Exception e) {
            Log.e(TAG, "Error loading sounds", e);
        }

        // 5. Start Voice Service
        Intent voiceIntent = new Intent(this, VoiceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(voiceIntent);
        } else {
            startService(voiceIntent);
        }
        bindService(voiceIntent, connection, Context.BIND_AUTO_CREATE);
        registerReceiver(voskReceiver, new IntentFilter("VoskResult"), Context.RECEIVER_EXPORTED);

        // 6. SOS Button Touch Logic (Hold for manual alert)
        sosButton.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (isBound) {
                    if (startSound != null) startSound.start();
                    voiceService.resetManualStrings();
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
                    String finalSpoken = isBound ? voiceService.getSpokenText() : "";
                    String finalPartial = isBound ? voiceService.getCurrentPartial() : "";
                    
                    String finalCaptured = !finalSpoken.isEmpty() ? finalSpoken : 
                                             (!finalPartial.isEmpty() ? finalPartial : "Manual Alert");

                    executor.execute(() -> {
                        String compressed = KeywordProcessor.compressMessage(finalCaptured);
                        String encryptedForUI = EncryptionHelper.encrypt(compressed);
                        
                        runOnUiThread(() -> {
                            voiceText.setText(getString(R.string.sos_display_format, finalCaptured, encryptedForUI));
                            triggerSOSWithMessages(finalCaptured, compressed, "BUTTON");
                        });
                    });
                }, 700); // Increased delay slightly for better accuracy
            }
            return true;
        });

        // Initial device info update
        updateDeviceInfo();

        // Check if triggered from VolumeService or VoiceService
        handleTriggerIntent(getIntent());

        // --- FIRST TIME SETUP CHECK ---
        checkFirstTimeSetup();
    }

    private void handleTriggerIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("triggerSOS", false)) {
            Log.d(TAG, "ARS_UI: Processing trigger intent");
            String fullMsg = intent.getStringExtra("full_message");
            String triggerType = intent.getStringExtra("trigger_type");
            if (triggerType == null) triggerType = "EXTERNAL";
            
            String msg = fullMsg != null ? fullMsg : getString(R.string.trigger_volume_accessibility);
            String compressed = KeywordProcessor.compressMessage(msg);
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                triggerSOSWithMessages(msg, compressed, triggerType);
            } else {
                Toast.makeText(this, "Location permission required for SOS", Toast.LENGTH_LONG).show();
                // Even without location, we should probably try to send something? 
                // But the app logic seems to rely on it.
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // Update activity intent
        Log.d(TAG, "ARS_UI: onNewIntent received");
        handleTriggerIntent(intent);
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
                triggerSOSWithMessages(getString(R.string.trigger_volume_triple), getString(R.string.trigger_volume_triple), "VOLUME");
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
        startRelayIfPermitted();
        
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

    private void startRelayIfPermitted() {
        if (!NearbyRelay.permitted(this)) {
            Log.i(TAG, "Nearby relay waiting for runtime permissions");
            return;
        }
        Intent relayIntent = new Intent(this, SOSService.class).setAction(SOSService.ACTION_RELAY);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, relayIntent);
            } else {
                startService(relayIntent);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start Nearby relay service", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
             if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
                
                // Re-initialize VoiceService now that we have RECORD_AUDIO
                Intent voiceIntent = new Intent(this, VoiceService.class);
                voiceIntent.putExtra("start_listening", true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(voiceIntent);
                } else {
                    startService(voiceIntent);
                }

                // Request Background Location separately for Android 10+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Background Location Access")
                                .setMessage("To ensure SOS works even when the screen is off, please select 'Allow all the time' in the next screen.")
                                .setPositiveButton("Grant", (dialog, which) -> {
                                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2);
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                }
            } else {
                // Check if SEND_SMS was denied specifically
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "ARS_SMS: SEND_SMS permission denied by user");
                }
            }
            startRelayIfPermitted();
        } else if (requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted — now enable bluetooth
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                    bluetoothAdapter = bluetoothManager.getAdapter();
                } else {
                    bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                }
                if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        // TODO: Consider calling
                        //    ActivityCompat#requestPermissions
                        // here to request the missing permissions, and then overriding
                        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                        //                                          int[] grantResults)
                        // to handle the case where the user grants the permission. See the documentation
                        // for ActivityCompat#requestPermissions for more details.
                        return;
                    }
                    bluetoothAdapter.enable();
                }
                setupBluetoothAutoconnect();
                startRelayIfPermitted();
            } else {
                // Permission denied — use Intent fallback
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode != RESULT_OK) {
                // User still didn't enable BT, open settings as nuclear option
                Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                startActivity(intent);
            }
        }
    }

    // ================= 📍 SOS CORE LOGIC =================

    private void triggerSOSWithMessages(String fullMsg, String compressedMsg, String triggerType) {
        long currentTime = System.currentTimeMillis();
        if (isSOSInProgress || (currentTime - lastSPOSTriggerTime < SOS_COOLDOWN)) {
            Log.d(TAG, "SOS Trigger blocked (In progress or Cooldown)");
            return;
        }
        
        startSOSCountdown(fullMsg, compressedMsg, triggerType);
    }

    private void startSOSCountdown(String fullMsg, String compressedMsg, String triggerType) {
        isSOSInProgress = true;
        
        runOnUiThread(() -> {
            countdownOverlay.setVisibility(android.view.View.VISIBLE);
            tvCountdownTimer.setText("5");
        });

        if (sosCountdownTimer != null) {
            sosCountdownTimer.cancel();
        }

        sosCountdownTimer = new android.os.CountDownTimer(5000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) (millisUntilFinished / 1000) + 1;
                runOnUiThread(() -> tvCountdownTimer.setText(String.valueOf(secondsRemaining)));
                // Optional: play a tick sound
            }

            @Override
            public void onFinish() {
                runOnUiThread(() -> {
                    countdownOverlay.setVisibility(android.view.View.GONE);
                    tvCountdownTimer.setText("0");
                    dispatchSOS(fullMsg, compressedMsg, triggerType);
                });
            }
        }.start();
    }

    private void cancelSOSCountdown() {
        if (sosCountdownTimer != null) {
            sosCountdownTimer.cancel();
        }
        isSOSInProgress = false;
        runOnUiThread(() -> {
            countdownOverlay.setVisibility(android.view.View.GONE);
            tvStatus.setText("SOS CANCELLED");
            Toast.makeText(this, "Emergency Signal Cancelled", Toast.LENGTH_SHORT).show();
        });
    }

    private void dispatchSOS(String fullMsg, String compressedMsg, String triggerType) {
        lastSPOSTriggerTime = System.currentTimeMillis();

        runOnUiThread(() -> {
            tvStatus.setText(R.string.status_sos_triggered);
            Toast.makeText(this, R.string.status_sos_triggered, Toast.LENGTH_LONG).show();
        });
        updateDeviceInfo();

        double lat = lastLat;
        double lon = lastLon;

        Intent serviceIntent = new Intent(this, SOSService.class);
        serviceIntent.putExtra("trigger_sos", true);
        serviceIntent.putExtra("full_message", fullMsg);
        serviceIntent.putExtra("compressed_message", compressedMsg);
        serviceIntent.putExtra("trigger_type", triggerType);
        serviceIntent.putExtra("lat", lat);
        serviceIntent.putExtra("lon", lon);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> isSOSInProgress = false, 3000);
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
        // Voice service handle initialization
    }

    private void startListening() {
    }

    private void stopSpeechService() {
    }

    // ================= 🛠 UTILITIES =================

    private void copyAssets(String assetPath, File outFile) throws IOException {
    }

    private void setupBluetoothAutoconnect() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            // Check for Android 12+ permission requirement
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && 
                androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            
            java.util.Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            if (pairedDevices != null && !pairedDevices.isEmpty()) {
                for (BluetoothDevice device : pairedDevices) {
                    connectToBluetoothDevice(device);
                }
            }
        }
    }

    private void connectToBluetoothDevice(BluetoothDevice device) {
        try {
            java.util.UUID uuid = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
            
            // Final check for name access (required for Android 12+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && 
                androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }

            final String deviceName = device.getName();
            final BluetoothSocket socket = device.createRfcommSocketToServiceRecord(uuid);

            new Thread(() -> {
                try {
                    if (bluetoothAdapter != null) {
                        bluetoothAdapter.cancelDiscovery();
                    }
                    socket.connect();
                    
                    // Alert the user of success
                    runOnUiThread(() -> {
                        android.widget.Toast.makeText(MainActivity.this, "Connected to: " + deviceName, android.widget.Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    android.util.Log.e("ARS_BT", "Silent fail: " + deviceName);
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }).start();
        } catch (Exception e) {
            android.util.Log.e("ARS_BT", "Socket error: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        unregisterReceiver(voskReceiver);
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

    private void checkAccessibilityService() {
        if (!isAccessibilityServiceEnabled()) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Accessibility Service Required")
                    .setMessage("To detect volume button triggers even when the screen is off, please enable the ARS Accessibility Service in Settings.")
                    .setPositiveButton("Go to Settings", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        startActivity(intent);
                    })
                    .setNegativeButton("Later", null)
                    .show();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        final String service = getPackageName() + "/" + VolumeService.class.getName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "Error finding setting: " + e.getMessage());
        }
        android.text.TextUtils.SimpleStringSplitter mStringColonSplitter = new android.text.TextUtils.SimpleStringSplitter(':');

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue);
                while (mStringColonSplitter.hasNext()) {
                    String accessibilityService = mStringColonSplitter.next();
                    if (accessibilityService.equalsIgnoreCase(service)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(android.net.Uri.parse("package:" + packageName));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Could not open battery optimization settings", e);
                    // Fallback to general battery settings
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                }
            }
        }
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Display Over Other Apps")
                        .setMessage("ARS needs permission to show the SOS countdown over the lock screen and other apps.")
                        .setPositiveButton("Grant", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
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

    private void runDiagnostic(String target) {
        Intent serviceIntent = new Intent(this, SOSService.class);
        serviceIntent.putExtra("run_diagnostic_test", true);
        if ("TWILIO".equals(target)) {
            serviceIntent.putExtra("test_target", "TWILIO");
        } else {
            serviceIntent.putExtra("test_target", "LOCAL");
            serviceIntent.putExtra("local_number", target);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "Diagnostic Started for " + target, Toast.LENGTH_SHORT).show();
    }
}
