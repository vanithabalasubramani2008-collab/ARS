package com.abu.ars;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SOSService extends Service {
    private static final String CHANNEL_ID = "SOS_SERVICE_CHANNEL";
    private static final String ALERT_CHANNEL_ID = "SOS_ALERT_CHANNEL";
    private static final String TAG = "SOS_SERVICE";
    private static final String TAG_SMS = "ARS_SMS_DEBUG";
    static final String ACTION_RELAY = "com.abu.ars.RELAY";
    private static final String STATUS_PREFS = "SosStatus";
    private static final String STATUS_KEY = "status";
    private static final String ACTION_SMS_SENT = "com.abu.ars.SMS_SENT";
    private static final String ACTION_SMS_DELIVERED = "com.abu.ars.SMS_DELIVERED";
    private static final String PRIMARY_CONTACT = "9940541394";
    private static final String ARS_EMERGENCY_SMS_NUMBER = "+919940333423";
    private static final String PREFS_NAME = "EmergencyContacts";
    private static final String CONTACTS_KEY = "phone_numbers";
    private static final String APP_PREFS = "AppPrefs";
    private static final String DEVICE_ID_KEY = "device_id";
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private FusedLocationProviderClient fusedLocationClient;
    private SosStore sosStore;
    private NearbyRelay nearbyRelay;
    private ConnectivityManager connectivityManager;
    private boolean networkCallbackRegistered;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            Log.i(TAG, "Network became available; retrying queued SOS uploads");
            executor.execute(() -> uploadPending(null));
        }

        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                Log.i(TAG, "Network validated; retrying queued SOS uploads");
                executor.execute(() -> uploadPending(null));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        sosStore = new SosStore(this);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        createNotificationChannel();
        registerSmsReceivers();
    }

    private void registerSmsReceivers() {
        android.content.BroadcastReceiver smsReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                String dest = intent.getStringExtra("destination");
                String type = intent.getStringExtra("type");
                int subId = intent.getIntExtra("subId", -1);
                
                if (type == null) {
                    type = ARS_EMERGENCY_SMS_NUMBER.equals(dest) ? "ARS_EMERGENCY" : 
                                 (dest != null && dest.contains("TEST") ? "DIAGNOSTIC_TEST" : "EMERGENCY_CONTACT");
                }
                
                if (ACTION_SMS_SENT.equals(action)) {
                    int resultCode = getResultCode();
                    String resultString = getSmsResultString(resultCode);
                    Log.d(TAG_SMS, "ARS_SMS_DEBUG: SMS_SENT");
                    Log.d(TAG_SMS, "ARS_SMS_DEBUG: TYPE = " + type);
                    Log.d(TAG_SMS, "ARS_SMS_DEBUG: DESTINATION = " + dest);
                    Log.d(TAG_SMS, "ARS_SMS_DEBUG: RESULT = " + resultString + " (" + resultCode + ")");
                    Log.d(TAG_SMS, "ARS_SMS_DEBUG: Subscription ID = " + subId);
                } else if (ACTION_SMS_DELIVERED.equals(action)) {
                    Log.d(TAG_SMS, "ARS_SMS_DEBUG: SMS_DELIVERED");
                    Log.d(TAG_SMS, "ARS_SMS_DEBUG: TYPE = " + type);
                    Log.d(TAG_SMS, "ARS_SMS_DEBUG: DESTINATION = " + dest);
                    Log.d(TAG_SMS, "ARS_SMS_DEBUG: RESULT = SUCCESS");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SMS_SENT);
        filter.addAction(ACTION_SMS_DELIVERED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(smsReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(smsReceiver, filter);
        }
    }

    @Override
    public void onDestroy() {
        if (nearbyRelay != null) nearbyRelay.stop();
        if (networkCallbackRegistered && connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        executor.shutdown();
        super.onDestroy();
        // Ideally unregister receiver here, but this is a debug task and service might stay up.
        // For production, we should handle this properly.
    }

    private String getOrCreateDeviceId() {
        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        String deviceId = prefs.getString(DEVICE_ID_KEY, null);
        if (deviceId == null) {
            deviceId = "DEV-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            prefs.edit().putString(DEVICE_ID_KEY, deviceId).apply();
        }
        return deviceId;
    }

    private String getSmsResultString(int resultCode) {
        switch (resultCode) {
            case android.app.Activity.RESULT_OK: return "RESULT_OK";
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE: return "GENERIC_FAILURE";
            case SmsManager.RESULT_ERROR_NO_SERVICE: return "NO_SERVICE";
            case SmsManager.RESULT_ERROR_NULL_PDU: return "NULL_PDU";
            case SmsManager.RESULT_ERROR_RADIO_OFF: return "RADIO_OFF";
            case 5: return "RESULT_ERROR_LIMIT_EXCEEDED"; // RESULT_ERROR_LIMIT_EXCEEDED (API 30+)
            case 7: return "RESULT_ERROR_SHORT_CODE_NOT_ALLOWED";
            case 8: return "RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED";
            default: return "UNKNOWN_ERROR_CODE_" + resultCode;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = createNotification();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (NearbyRelay.permitted(this)) {
                serviceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            }
            if (intent != null && intent.getBooleanExtra("trigger_sos", false)) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    serviceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                }
            }
            startForeground(1, notification, serviceTypes);
        } else {
            startForeground(1, notification);
        }

        registerNetworkCallback();
        startNearbyRelay();

        if (intent != null) {
            if (intent.getBooleanExtra("run_diagnostic_test", false)) {
                String testTarget = intent.getStringExtra("test_target");
                String localNumber = intent.getStringExtra("local_number");
                runCarrierDiagnostic(testTarget, localNumber);
            } else if (intent.getBooleanExtra("trigger_sos", false)) {
                final String fullMsg = intent.getStringExtra("full_message") != null ? 
                        intent.getStringExtra("full_message") : "Manual Trigger";
                final String compressedMsg = intent.getStringExtra("compressed_message") != null ? 
                        intent.getStringExtra("compressed_message") : "Manual Trigger";
                final String triggerType = intent.getStringExtra("trigger_type") != null ? 
                        intent.getStringExtra("trigger_type") : "BUTTON";
                double lat = intent.getDoubleExtra("lat", 0.0);
                double lon = intent.getDoubleExtra("lon", 0.0);
                
                if (lat == 0.0 && lon == 0.0) {
                    Log.d(TAG, "No location provided, fetching fresh coordinates...");
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                .addOnSuccessListener(location -> {
                                    if (location != null) {
                                        triggerDualChannelSOS(fullMsg, compressedMsg, location.getLatitude(), location.getLongitude(), triggerType);
                                    } else {
                                        // Fallback to last known if fresh fails
                                        fusedLocationClient.getLastLocation().addOnSuccessListener(lastLoc -> {
                                            double fLat = (lastLoc != null) ? lastLoc.getLatitude() : 0.0;
                                            double fLon = (lastLoc != null) ? lastLoc.getLongitude() : 0.0;
                                            triggerDualChannelSOS(fullMsg, compressedMsg, fLat, fLon, triggerType);
                                        });
                                    }
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "Failed to get fresh location", e);
                                    triggerDualChannelSOS(fullMsg, compressedMsg, 0.0, 0.0, triggerType);
                                });
                    } else {
                        Log.e(TAG, "Location permission missing in SOSService");
                        triggerDualChannelSOS(fullMsg, compressedMsg, 0.0, 0.0, triggerType);
                    }
                } else {
                    triggerDualChannelSOS(fullMsg, compressedMsg, lat, lon, triggerType);
                }
            }
        }

        return START_STICKY;
    }

    private void runCarrierDiagnostic(String target, String localNumber) {
        Log.d(TAG_SMS, "ARS_SMS_DEBUG: --- CARRIER DIAGNOSTIC MODE ---");
        
        if ("TWILIO".equals(target) || "ARS_EMERGENCY".equals(target)) {
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: ========================================");
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: ARS EMERGENCY DIAGNOSTIC TEST");
            
            // Safety Check
            if (!ARS_EMERGENCY_SMS_NUMBER.equals("+919940333423")) {
                Log.e(TAG_SMS, "ARS_SMS_DEBUG: ERROR: ARS EMERGENCY DESTINATION IS WRONG");
                return;
            }
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: ARS EMERGENCY DESTINATION VERIFIED = " + ARS_EMERGENCY_SMS_NUMBER);
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: Destination = " + ARS_EMERGENCY_SMS_NUMBER);
            
            sendSmsInternal(ARS_EMERGENCY_SMS_NUMBER, "ARS TEST - Diagnostic Message", "DIAGNOSTIC_ARS");
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: ========================================");
        } else {
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: LOCAL DIAGNOSTIC TEST");
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: Destination = " + localNumber);
            if (localNumber == null || localNumber.isEmpty()) {
                Log.e(TAG_SMS, "ARS_SMS_DEBUG: Diagnostic failed - No local number provided");
                return;
            }
            sendSmsInternal(localNumber, "ARS TEST - Local Diagnostic Message", "DIAGNOSTIC_LOCAL");
        }
    }

    private void sendSmsInternal(String destination, String text, String messageType) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG_SMS, "ARS_SMS_DEBUG: SEND_SMS permission = DENIED");
            return;
        }

        try {
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: SMS TYPE = " + messageType);
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: ACTUAL SMS DESTINATION = " + destination);
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: EXPECTED ARS EMERGENCY DESTINATION = " + ARS_EMERGENCY_SMS_NUMBER);

            // Hard Destination Guard for ARS Emergency
            if ("ARS_EMERGENCY".equals(messageType)) {
                final String requiredDestination = "+919940333423";
                if (!requiredDestination.equals(destination)) {
                    Log.e(TAG_SMS, "ARS_SMS_DEBUG: BLOCKED WRONG ARS EMERGENCY DESTINATION: " + destination);
                    return;
                }
            }

            Log.d(TAG_SMS, "ARS_SMS_DEBUG: Preparing to send SMS to " + destination);
            
            // Log Multi-SIM Diagnostics
            int defaultSubId = -1;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager sm = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        List<SubscriptionInfo> activeList = sm.getActiveSubscriptionInfoList();
                        int simCount = (activeList != null ? activeList.size() : 0);
                        Log.d(TAG_SMS, "ARS_SMS_DEBUG: Active SIM count = " + simCount);
                        
                        if (activeList != null) {
                            for (SubscriptionInfo info : activeList) {
                                Log.d(TAG_SMS, "ARS_SMS_DEBUG: SIM Slot " + info.getSimSlotIndex() + 
                                    ": ID=" + info.getSubscriptionId() + 
                                    ", Carrier=" + info.getDisplayName() + 
                                    ", Country=" + info.getCountryIso());
                            }
                        }
                        
                        defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId();
                        Log.d(TAG_SMS, "ARS_SMS_DEBUG: Default SMS subscription ID = " + defaultSubId);
                    } else {
                        Log.d(TAG_SMS, "ARS_SMS_DEBUG: READ_PHONE_STATE missing - skipping detailed SIM logs");
                    }
                }
            }

            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (defaultSubId != -1) {
                    smsManager = getSystemService(SmsManager.class).createForSubscriptionId(defaultSubId);
                } else {
                    smsManager = getSystemService(SmsManager.class);
                }
            } else {
                if (defaultSubId != -1) {
                    smsManager = SmsManager.getSmsManagerForSubscriptionId(defaultSubId);
                } else {
                    smsManager = SmsManager.getDefault();
                }
            }

            if (smsManager == null) {
                Log.e(TAG_SMS, "ARS_SMS_DEBUG: SMS failed (SmsManager is null)");
                return;
            }
            
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: SmsManager = " + smsManager.toString());

            int requestCode = (messageType + destination).hashCode();
            Intent sentIntent = new Intent(ACTION_SMS_SENT);
            sentIntent.putExtra("destination", destination);
            sentIntent.putExtra("type", messageType);
            sentIntent.putExtra("subId", defaultSubId);
            PendingIntent sentPI = PendingIntent.getBroadcast(this, requestCode, sentIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Intent deliveredIntent = new Intent(ACTION_SMS_DELIVERED);
            deliveredIntent.putExtra("destination", destination);
            deliveredIntent.putExtra("type", messageType);
            PendingIntent deliveredPI = PendingIntent.getBroadcast(this, requestCode, deliveredIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            ArrayList<String> parts = smsManager.divideMessage(text);
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: SMS request submitted to " + destination + " (Type: " + messageType + ")");
            
            if (parts.size() > 1) {
                ArrayList<PendingIntent> sentPIs = new ArrayList<>();
                ArrayList<PendingIntent> deliveredPIs = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    sentPIs.add(sentPI);
                    deliveredPIs.add(deliveredPI);
                }
                smsManager.sendMultipartTextMessage(destination, null, parts, sentPIs, deliveredPIs);
            } else {
                smsManager.sendTextMessage(destination, null, text, sentPI, deliveredPI);
            }
            
            Log.d(TAG_SMS, "ARS_SMS_DEBUG: Sending SMS now (System call successful)");

        } catch (Exception e) {
            Log.e(TAG_SMS, "ARS_SMS_DEBUG: SMS failed with exception: " + e.getMessage());
        }
    }

    private void triggerDualChannelSOS(String fullMessage, String compressedMessage, double lat, double lon, String triggerType) {
        showTriggerNotification(fullMessage);
        
        Log.d(TAG_SMS, "ARS_SMS_DEBUG: SOS triggered");
        
        final String sosId = "SOS-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID();
        final String deviceId = getOrCreateDeviceId();

        executor.execute(() -> sendSMSParallel(fullMessage, lat, lon));
        try {
            JSONObject payload = createBackendPayload(sosId, deviceId, compressedMessage, lat, lon, triggerType);
            JSONObject envelope = new JSONObject()
                    .put("sosId", sosId)
                    .put("createdAt", System.currentTimeMillis())
                    .put("hopCount", 0)
                    .put("isOwner", true)
                    .put("payload", payload)
                    .put("serverState", "PENDING");
            if (!sosStore.put(envelope)) {
                Log.e(TAG, "SOS creation failed: local queue rejected id=" + sosId);
                status("SOS_QUEUE_ERROR: " + sosId);
                executor.execute(() -> sendArsEmergencySms(sosId, deviceId, lat, lon,
                        compressedMessage, triggerType, "SMS_FALLBACK"));
                return;
            }
            Log.i(TAG, "SOS created and durably queued id=" + sosId);
            status("QUEUED: " + sosId);
            if (nearbyRelay != null) nearbyRelay.forwardPending();
            executor.execute(() -> {
                boolean serverReceived = uploadPending(sosId);
                if (serverReceived) {
                    Log.i(TAG, "SERVER_RECEIVED: " + sosId);
                    sendArsEmergencySms(sosId, deviceId, lat, lon, compressedMessage,
                            triggerType, "INTERNET_REPORTED");
                } else {
                    Log.w(TAG, "Initial backend attempt failed; preserving SMS fallback for " + sosId);
                    sendArsEmergencySms(sosId, deviceId, lat, lon, compressedMessage,
                            triggerType, "SMS_FALLBACK");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "SOS creation failed id=" + sosId, e);
            status("SOS_CREATE_ERROR: " + sosId);
            executor.execute(() -> sendArsEmergencySms(sosId, deviceId, lat, lon,
                    compressedMessage, triggerType, "SMS_FALLBACK"));
        }
    }

    private JSONObject createBackendPayload(String sosId, String deviceId, String compressedMessage,
                                            double lat, double lon, String triggerType) throws Exception {
        Log.i(TAG, "Building canonical ARS SOS payload id=" + sosId);
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 0;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String timestamp = sdf.format(new Date());

        JSONObject payload = new JSONObject();
        payload.put("sosId", sosId);
        payload.put("deviceId", deviceId);
        payload.put("latitude", lat);
        payload.put("longitude", lon);
        payload.put("battery", battery);
        payload.put("message", compressedMessage);
        payload.put("trigger", triggerType);
        payload.put("timestamp", timestamp);
        payload.put("communicationMethod", "INTERNET");
        payload.put("status", "ACTIVE");

        JSONObject encryptedData = new JSONObject();
        encryptedData.put("latitude", EncryptionHelper.encrypt(String.valueOf(lat)));
        encryptedData.put("longitude", EncryptionHelper.encrypt(String.valueOf(lon)));
        encryptedData.put("message", EncryptionHelper.encrypt(compressedMessage.replaceAll("[^\\p{ASCII}]", "")));
        encryptedData.put("battery", EncryptionHelper.encrypt(String.valueOf(battery)));
        encryptedData.put("timestamp", EncryptionHelper.encrypt(timestamp));
        payload.put("encryptedData", encryptedData);

        JSONObject metadata = new JSONObject();
        metadata.put("compression", "ollama-keyword-compression");
        metadata.put("encryption", "AES-256");
        metadata.put("version", "1.0");
        payload.put("metadata", metadata);
        return payload;
    }

    private synchronized boolean uploadPending(String requestedSosId) {
        if (requestedSosId != null && sosStore.isServerReceived(requestedSosId)) return true;
        if (!isNetworkAvailable()) {
            Log.i(TAG, "No validated internet; SOS remains queued for relay/retry");
            return false;
        }

        for (JSONObject envelope : sosStore.pending()) {
            String sosId = envelope.optString("sosId");
            HttpURLConnection conn = null;
            try {
                Log.i(TAG, "Backend upload attempt id=" + sosId);
                conn = (HttpURLConnection) new URL(BuildConfig.ARS_SOS_API_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("ngrok-skip-browser-warning", "any");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                byte[] body = envelope.getJSONObject("payload").toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    sosStore.markServerReceived(sosId);
                    Log.i(TAG, "SERVER_RECEIVED: " + sosId);
                    status("SERVER_RECEIVED: " + sosId);
                } else {
                    Log.w(TAG, "Backend upload rejected id=" + sosId + " HTTP=" + code);
                    status("BACKEND_RETRY: " + sosId + " HTTP " + code);
                    break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Backend upload failed; queued for reconnection id=" + sosId, e);
                status("BACKEND_RETRY: " + sosId);
                break;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return requestedSosId != null && sosStore.isServerReceived(requestedSosId);
    }

    private boolean isNetworkAvailable() {
        if (connectivityManager == null) return false;
        Network network = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void registerNetworkCallback() {
        if (networkCallbackRegistered || connectivityManager == null) return;
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not register network callback", e);
        }
    }

    private void startNearbyRelay() {
        if (!NearbyRelay.permitted(this)) {
            Log.w(TAG, "Nearby relay unavailable: required runtime permissions are missing");
            return;
        }
        if (nearbyRelay != null) return;
        nearbyRelay = new NearbyRelay(this, sosStore, new NearbyRelay.Listener() {
            @Override public boolean allowReceive() { return true; }

            @Override public void received(JSONObject envelope) {
                String sosId = envelope.optString("sosId");
                Log.i(TAG, "Peer SOS stored locally id=" + sosId);
                status("PEER_STORED: " + sosId);
                if (nearbyRelay != null) nearbyRelay.forwardPending();
                executor.execute(() -> uploadPending(null));
            }

            @Override public void acknowledged(String sosId) {
                sosStore.markPeerStored(sosId);
                Log.i(TAG, "PEER_STORED: immediate peer accepted SOS id=" + sosId);
                status("PEER_STORED: " + sosId);
            }
        });
        nearbyRelay.start();
    }

    private void status(String value) {
        getSharedPreferences(STATUS_PREFS, MODE_PRIVATE).edit().putString(STATUS_KEY, value).apply();
    }

    private void sendArsEmergencySms(String sosId, String deviceId, double lat, double lon, String message, String triggerType, String commMethod) {
        Log.d(TAG_SMS, "ARS_SMS_DEBUG: ========================================");
        Log.d(TAG_SMS, "ARS_SMS_DEBUG: ARS EMERGENCY SMS");
        
        // Safety Check
        if (!ARS_EMERGENCY_SMS_NUMBER.equals("+919940333423")) {
            Log.e(TAG_SMS, "ARS_SMS_DEBUG: ERROR: ARS EMERGENCY DESTINATION IS WRONG");
            return;
        }

        Log.d(TAG_SMS, "ARS_SMS_DEBUG: ARS EMERGENCY DESTINATION VERIFIED = " + ARS_EMERGENCY_SMS_NUMBER);
        
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 0;
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String localTime = sdf.format(new Date());

        String smsBody = "ARS SOS\n" +
                         "SOS ID: " + sosId + "\n" +
                         "Device: " + deviceId + "\n" +
                         "Location: " + String.format(Locale.US, "%.6f,%.6f", lat, lon) + "\n" +
                         "Battery: " + battery + "%\n" +
                         "Trigger: " + triggerType + "\n" +
                         "Message: " + message.replaceAll("[^\\p{ASCII}]", "") + "\n" +
                         "Time: " + localTime + "\n\n" +
                         "Google Maps location:\n" +
                         "https://www.google.com/maps?q=" + lat + "," + lon;

        Log.d(TAG_SMS, "ARS_SMS_DEBUG: Destination = " + ARS_EMERGENCY_SMS_NUMBER);
        Log.d(TAG_SMS, "ARS_SMS_DEBUG: Message = " + smsBody);
        
        sendSmsInternal(ARS_EMERGENCY_SMS_NUMBER, smsBody, "ARS_EMERGENCY");
        Log.d(TAG_SMS, "ARS_SMS_DEBUG: ========================================");
    }

    private void sendSMSParallel(String message, double lat, double lon) {
        Log.d(TAG_SMS, "ARS_SMS_DEBUG: SOS_SMS_STARTED (Personal Contacts)");
        try {
            // 1. Prepare Data
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int battery = (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 0;

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String timestamp = sdf.format(new Date());

            JSONObject json = new JSONObject();
            json.put("latitude", lat);
            json.put("longitude", lon);
            json.put("message", message.replaceAll("[^\\p{ASCII}]", ""));
            json.put("battery", battery);
            json.put("timestamp", timestamp);

            String smsBody = json.toString(2);

            // 4. Collect numbers
            Set<String> recipients = new HashSet<>();
            recipients.add("8778064671"); // Test Number
            recipients.add(PRIMARY_CONTACT); // "9940541394"
            
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            Set<String> savedContacts = prefs.getStringSet(CONTACTS_KEY, new HashSet<>());
            recipients.addAll(savedContacts);

            Log.d(TAG_SMS, "ARS_SMS_DEBUG: Emergency contact count = " + recipients.size());
            if (recipients.isEmpty()) {
                Log.d(TAG_SMS, "ARS_SMS_DEBUG: NO_EMERGENCY_CONTACTS");
                return;
            }

            // 5. Send to all
            for (String number : recipients) {
                if (number != null && !number.trim().isEmpty()) {
                    Log.d(TAG_SMS, "ARS_SMS_DEBUG: Emergency contact SMS destination = " + number);
                    sendSmsInternal(number, smsBody, "EMERGENCY_CONTACT");
                }
            }
        } catch (Exception e) {
            Log.e(TAG_SMS, "ARS_SMS_DEBUG: Emergency contact SMS failed (" + e.getMessage() + ")");
        }
    }

    private void showTriggerNotification(String source) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification alertNotification = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("🚨 SOS ALERT TRIGGERED 🚨")
                .setContentText("Emergency Signal Sent: " + source)
                .setSmallIcon(R.mipmap.apple)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setVibrate(new long[]{1000, 500, 1000, 500})
                .setFullScreenIntent(pendingIntent, true)
                .build();

        manager.notify(2, alertNotification);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        // Background Service Channel
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID, "ARS SOS Service Channel",
                NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(serviceChannel);

        // SOS Trigger Alert Channel (High Importance for Heads-up)
        NotificationChannel alertChannel = new NotificationChannel(
                ALERT_CHANNEL_ID, "SOS Trigger Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alertChannel.setDescription("High-priority alerts when SOS is triggered");
        alertChannel.enableVibration(true);
        alertChannel.setVibrationPattern(new long[]{1000, 500, 1000, 500});
        manager.createNotificationChannel(alertChannel);
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ARS Protection Active")
                .setContentText("Monitoring for emergency triggers...")
                .setSmallIcon(R.mipmap.apple)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

}
