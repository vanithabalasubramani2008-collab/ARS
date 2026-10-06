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
import java.util.Date;
import java.util.Locale;
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
    private static final String BACKEND_URL = "https://defile-blip-snowbird.ngrok-free.dev/sos";
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

    @Override public void onCreate() {
        super.onCreate();
        store = new SosStore(this);
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "ARS emergency relay", NotificationManager.IMPORTANCE_LOW));
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

    private void enqueue(String full, String compressed, double lat, double lon) {
        try {
            BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int battery = batteryManager == null ? 0 : batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String timestamp = sdf.format(new Date());
            JSONObject encrypted = new JSONObject()
                    .put("latitude", EncryptionHelper.encrypt(Double.toString(lat)))
                    .put("longitude", EncryptionHelper.encrypt(Double.toString(lon)))
                    .put("message", EncryptionHelper.encrypt((compressed == null ? "Emergency" : compressed).replaceAll("[^\\p{ASCII}]", "")))
                    .put("battery", EncryptionHelper.encrypt(Integer.toString(battery)))
                    .put("timestamp", EncryptionHelper.encrypt(timestamp));
            JSONObject payload = new JSONObject()
                    .put("id", "SOS-" + UUID.randomUUID())
                    .put("encryptedData", encrypted)
                    .put("metadata", new JSONObject().put("compression", "keyword-compression")
                            .put("encryption", "AES-256-CBC").put("version", "1.0"));
            JSONObject envelope = new JSONObject().put("id", payload.getString("id"))
                    .put("createdAt", System.currentTimeMillis()).put("hops", 0)
                    .put("own", true).put("payload", payload);
            if (store.put(envelope)) {
                status("SOS saved; searching for a delivery route");
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
        try {
            for (JSONObject envelope : store.pending()) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(BACKEND_URL).openConnection();
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
                        break;
                    }
                } catch (Exception e) { Log.w(TAG, "SOS queued for another attempt", e); break; }
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
