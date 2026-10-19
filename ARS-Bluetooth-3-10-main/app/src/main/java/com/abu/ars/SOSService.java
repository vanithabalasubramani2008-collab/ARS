package com.abu.ars;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground, opted-in phone relay plus durable server upload. */
public class SOSService extends Service {
    static final String ACTION_TRIGGER = "com.abu.ars.TRIGGER";
    static final String ACTION_RELAY = "com.abu.ars.RELAY";
    static final String ACTION_DISABLE = "com.abu.ars.DISABLE_RELAY";
    static final String ACTION_STATUS = "com.abu.ars.SOS_STATUS";
    static final String EXTRA_STATUS = "status";
    static final String STATUS_PREFS = "SosStatus";
    static final String PREFS = "RelaySettings";
    static final String ENABLED = "enabled";
    private static final String TAG = "SOS_SERVICE";
    private static final String CHANNEL_ID = "ARS_RELAY";
    private static final String DEFAULT_BACKEND_URL = "http://10.127.154.187:5000/sos";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SosStore store;
    private NearbyRelay nearby;
    private ConnectivityManager connectivity;
    private boolean uploading;
    private boolean networkCallbackRegistered;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { worker.execute(() -> uploadPending()); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                worker.execute(() -> uploadPending());
        }
    };

    private String getBackendUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getString("backend_url", DEFAULT_BACKEND_URL);
    }

    @Override public void onCreate() {
        super.onCreate();
        store = new SosStore(this);
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "ARS emergency relay", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        boolean canRelay = NearbyRelay.permitted(this);
        int type = canRelay ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE :
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        startForeground(1, notification(), type);
        if (!networkCallbackRegistered && connectivity != null) {
            connectivity.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        }
        String action = intent == null ? ACTION_RELAY : intent.getAction();
        if (ACTION_DISABLE.equals(action)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply();
            if (nearby != null && !store.hasOwnPending()) { nearby.stop(); nearby = null; }
            if (store.pending().isEmpty()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            worker.execute(() -> uploadPending());
            return START_STICKY;
        }
        boolean volunteer = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ENABLED, false);
        if (canRelay && (volunteer || store.hasOwnPending() || ACTION_TRIGGER.equals(action)) && nearby == null) {
            nearby = new NearbyRelay(this, store, new NearbyRelay.Listener() {
                @Override public boolean allowReceive() {
                    return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ENABLED, false);
                }
                @Override public void received(JSONObject envelope) {
                    status("SOS received from nearby phone; looking for delivery route");
                    if (nearby != null) nearby.forwardPending();
                    worker.execute(() -> uploadPending());
                }
                @Override public void acknowledged(String id) { status("SOS " + id + " reached a nearby phone"); }
            });
            nearby.start();
        }
        if (ACTION_TRIGGER.equals(action) && intent != null) {
            String full = intent.getStringExtra("full_message");
            String compressed = intent.getStringExtra("compressed_message");
            double lat = intent.getDoubleExtra("lat", 0);
            double lon = intent.getDoubleExtra("lon", 0);
            worker.execute(() -> enqueue(full, compressed, lat, lon));
        } else worker.execute(() -> uploadPending());
        return START_STICKY;
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.apple).setContentTitle("ARS emergency relay active")
                .setContentText("Nearby SOS forwarding and delivery retries")
                .setContentIntent(pending).setOngoing(true).build();
    }

    private String buildEmergencySmsText(double lat, double lon, int battery, String timestamp, String customDetails) {
        StringBuilder sb = new StringBuilder();
        sb.append("EMERGENCY SOS ALERT!\n\n");
        sb.append("An SOS has been triggered from the ARS application. Please contact the user immediately and provide assistance.\n\n");

        if (lat != 0.0 || lon != 0.0) {
            sb.append("Latitude: ").append(lat).append("\n");
            sb.append("Longitude: ").append(lon).append("\n");
        } else {
            sb.append("Latitude: Location Unavailable\n");
            sb.append("Longitude: Location Unavailable\n");
        }

        sb.append("Battery: ").append(battery).append("%\n");
        sb.append("Timestamp: ").append(timestamp).append("\n");

        if (lat != 0.0 || lon != 0.0) {
            sb.append("Location: https://maps.google.com/?q=").append(lat).append(",").append(lon);
        }

        return sb.toString();
    }

    private String normalizePhone(String phone) {
        if (phone == null) return "";
        String trimmed = phone.trim();
        if (trimmed.startsWith("+")) {
            return "+" + trimmed.replaceAll("\\D", "");
        }
        String digits = trimmed.replaceAll("\\D", "");
        if (digits.length() == 10) {
            return "+91" + digits;
        }
        return "+" + digits;
    }

    private void sendRecipientSpecificSms(String canonicalMessage) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                SmsManager smsManager = SmsManager.getDefault();

                // 1. Secondary recipient 9940541394 — READABLE EMERGENCY SMS
                String secondaryReadableRecipient = "+919940541394";
                sendSmsToRecipient(smsManager, secondaryReadableRecipient, canonicalMessage);
                Log.i(TAG, "Readable SMS dispatched to secondary recipient: " + secondaryReadableRecipient);

                // 2. Default recipient 9940333423 — ENCRYPTED EMERGENCY SMS (AES-GCM)
                String encryptedMsg = AesGcmEncryptionHelper.encrypt(canonicalMessage);
                String defaultEncryptedRecipient = "+919940333423";
                sendSmsToRecipient(smsManager, defaultEncryptedRecipient, encryptedMsg);
                Log.i(TAG, "Encrypted AES-GCM SMS dispatched to default recipient: " + defaultEncryptedRecipient);

                // 3. User-configured additional emergency number — READABLE SMS (with duplicate prevention)
                SharedPreferences prefs = getSharedPreferences("EmergencyContacts", MODE_PRIVATE);
                Set<String> userContacts = prefs.getStringSet("phone_numbers", new HashSet<>());
                for (String contact : userContacts) {
                    if (contact == null || contact.trim().isEmpty()) continue;
                    String normalizedContact = normalizePhone(contact);
                    if (!normalizedContact.equals(defaultEncryptedRecipient) && !normalizedContact.equals(secondaryReadableRecipient)) {
                        sendSmsToRecipient(smsManager, normalizedContact, canonicalMessage);
                        Log.i(TAG, "Readable SMS dispatched to user-configured contact: " + normalizedContact);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to send recipient-specific SMS", e);
            }
        }
    }

    private void sendSmsToRecipient(SmsManager smsManager, String recipient, String text) {
        ArrayList<String> parts = smsManager.divideMessage(text);
        if (parts.size() > 1) {
            smsManager.sendMultipartTextMessage(recipient, null, parts, null, null);
        } else {
            smsManager.sendTextMessage(recipient, null, text, null, null);
        }
    }

    private void enqueue(String full, String compressed, double lat, double lon) {
        try {
            BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int battery = batteryManager == null ? 0 : batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String timestamp = sdf.format(new Date());

            String completeSmsText = buildEmergencySmsText(lat, lon, battery, timestamp, full);
            sendRecipientSpecificSms(completeSmsText);

            JSONObject encrypted = new JSONObject()
                    .put("latitude", EncryptionHelper.encrypt(Double.toString(lat)))
                    .put("longitude", EncryptionHelper.encrypt(Double.toString(lon)))
                    .put("message", EncryptionHelper.encrypt(completeSmsText))
                    .put("battery", EncryptionHelper.encrypt(Integer.toString(battery)))
                    .put("timestamp", EncryptionHelper.encrypt(timestamp));

            JSONObject payload = new JSONObject()
                    .put("id", "SOS-" + UUID.randomUUID())
                    .put("latitude", lat)
                    .put("longitude", lon)
                    .put("message", completeSmsText)
                    .put("battery", battery)
                    .put("timestamp", timestamp)
                    .put("encryptedData", encrypted)
                    .put("metadata", new JSONObject().put("compression", "keyword-compression")
                            .put("encryption", "AES-256-CBC").put("version", "1.0"));

            JSONObject envelope = new JSONObject().put("id", payload.getString("id"))
                    .put("createdAt", System.currentTimeMillis()).put("hops", 0)
                    .put("own", true).put("payload", payload);

            if (store.put(envelope)) {
                status("SOS saved; uploading to server...");
                if (nearby != null) nearby.forwardPending();
                uploadPending();
            }
        } catch (Exception e) { Log.e(TAG, "Unable to save SOS", e); status("Unable to save SOS"); }
    }

    private boolean hasInternet() {
        if (connectivity == null) return false;
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void uploadPending() {
        if (uploading || !hasInternet()) return;
        uploading = true;
        String backendUrl = getBackendUrl();
        try {
            for (JSONObject envelope : store.pending()) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(backendUrl).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("ngrok-skip-browser-warning", "any");
                    conn.setDoOutput(true);
                    byte[] body = envelope.getJSONObject("payload").toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream stream = conn.getOutputStream()) { stream.write(body); }
                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        try (InputStream stream = conn.getInputStream()) { while (stream.read() != -1) { } }
                        store.delivered(envelope.getString("id"));
                        status("SOS " + envelope.getString("id") + " accepted by server");
                    } else {
                        Log.w(TAG, "SOS server returned HTTP " + code);
                        status("SOS queued (Server HTTP " + code + ")");
                        break;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "SOS queued for another attempt: " + e.getMessage());
                    status("SOS saved locally (Queued for upload)");
                    break;
                }
                finally { if (conn != null) conn.disconnect(); }
            }
        } finally { uploading = false; }
    }

    private void status(String message) {
        Log.i(TAG, message);
        getSharedPreferences(STATUS_PREFS, MODE_PRIVATE).edit().putString(EXTRA_STATUS, message).apply();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (nearby != null) nearby.stop();
        if (networkCallbackRegistered) connectivity.unregisterNetworkCallback(networkCallback);
        worker.shutdown();
        super.onDestroy();
    }
}
