package com.abu.ars;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Nearby Connections transport. Forwarding and server delivery belong to SOSService. */
final class NearbyRelay {
    interface Listener {
        boolean allowReceive();
        void received(JSONObject envelope);
        void acknowledged(String id);
    }

    private static final String TAG = "ARS_RELAY";
    private static final String SERVICE_ID = "com.abu.ars.sos.v1";
    private static final int MAX_BYTES = 8 * 1024;
    private final ConnectionsClient client;
    private final SosStore store;
    private final Listener listener;
    private final String nodeName = UUID.randomUUID().toString();
    private final Set<String> peers = ConcurrentHashMap.newKeySet();
    private final Set<String> connecting = ConcurrentHashMap.newKeySet();
    private volatile boolean active;

    NearbyRelay(Context context, SosStore store, Listener listener) {
        this.client = Nearby.getConnectionsClient(context);
        this.store = store;
        this.listener = listener;
    }

    static boolean permitted(Context context) {
        if (Build.VERSION.SDK_INT < 31) return true;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        if (active) return;
        active = true;
        try {
            client.startAdvertising(nodeName, SERVICE_ID, lifecycle,
                    new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                    .addOnFailureListener(e -> Log.w(TAG, "Advertising unavailable", e));
            client.startDiscovery(SERVICE_ID, discovery,
                    new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                    .addOnFailureListener(e -> Log.w(TAG, "Discovery unavailable", e));
        } catch (SecurityException e) {
            active = false;
            Log.w(TAG, "Nearby permissions missing", e);
        }
    }

    void stop() {
        active = false;
        client.stopAdvertising();
        client.stopDiscovery();
        client.stopAllEndpoints();
        peers.clear();
        connecting.clear();
    }

    void forwardPending() {
        for (String endpoint : new HashSet<>(peers)) {
            for (JSONObject envelope : store.pending()) send(endpoint, envelope);
        }
    }

    private void send(String endpoint, JSONObject envelope) {
        try {
            int hops = envelope.getInt("hops");
            if (hops >= SosStore.MAX_HOPS) return;
            JSONObject outbound = new JSONObject(envelope.toString());
            outbound.put("hops", hops + 1);
            JSONObject packet = new JSONObject().put("type", "SOS").put("envelope", outbound);
            byte[] bytes = packet.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= MAX_BYTES) client.sendPayload(endpoint, Payload.fromBytes(bytes));
        } catch (Exception e) { Log.w(TAG, "Could not forward SOS", e); }
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override public void onEndpointFound(String endpoint, DiscoveredEndpointInfo info) {
            // One side initiates, avoiding two simultaneous requests for the same pair.
            if (nodeName.compareTo(info.getEndpointName()) >= 0) return;
            if (!active || !connecting.add(endpoint)) return;
            client.requestConnection(nodeName, endpoint, lifecycle)
                    .addOnFailureListener(e -> connecting.remove(endpoint));
        }
        @Override public void onEndpointLost(String endpoint) { connecting.remove(endpoint); }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override public void onConnectionInitiated(String endpoint, ConnectionInfo info) {
            // Prototype auto-accepts for unattended relay. Enroll/authenticate peers before public use.
            client.acceptConnection(endpoint, payloads)
                    .addOnFailureListener(e -> connecting.remove(endpoint));
        }
        @Override public void onConnectionResult(String endpoint, ConnectionResolution result) {
            connecting.remove(endpoint);
            if (result.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK) {
                peers.add(endpoint);
                for (JSONObject envelope : store.pending()) send(endpoint, envelope);
            }
        }
        @Override public void onDisconnected(String endpoint) {
            peers.remove(endpoint);
            connecting.remove(endpoint);
        }
    };

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override public void onPayloadReceived(String endpoint, Payload payload) {
            if (payload.getType() != Payload.Type.BYTES || payload.asBytes() == null ||
                    payload.asBytes().length > MAX_BYTES) return;
            try {
                JSONObject packet = new JSONObject(new String(payload.asBytes(), StandardCharsets.UTF_8));
                if ("ACK".equals(packet.optString("type"))) {
                    listener.acknowledged(packet.optString("id"));
                } else if ("SOS".equals(packet.optString("type"))) {
                    if (!listener.allowReceive()) return;
                    JSONObject envelope = packet.getJSONObject("envelope");
                    envelope.put("own", false);
                    if (store.put(envelope)) {
                        JSONObject ack = new JSONObject().put("type", "ACK").put("id", envelope.getString("id"));
                        client.sendPayload(endpoint, Payload.fromBytes(ack.toString().getBytes(StandardCharsets.UTF_8)));
                        listener.received(envelope);
                    }
                }
            } catch (Exception e) { Log.w(TAG, "Invalid peer packet", e); }
        }
        @Override public void onPayloadTransferUpdate(String endpoint, PayloadTransferUpdate update) { }
    };
}
