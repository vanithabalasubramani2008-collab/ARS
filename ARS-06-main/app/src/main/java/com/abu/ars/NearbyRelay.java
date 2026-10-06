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

/** Nearby Connections P2P transport; backend delivery remains in SOSService. */
final class NearbyRelay {
    interface Listener {
        boolean allowReceive();
        void received(JSONObject envelope);
        void acknowledged(String sosId);
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
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false;
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) return false;
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        if (active) return;
        active = true;
        try {
            Log.i(TAG, "Starting P2P_CLUSTER advertising and discovery node=" + nodeName);
            client.startAdvertising(nodeName, SERVICE_ID, lifecycle,
                    new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                    .addOnFailureListener(e -> Log.e(TAG, "Advertising failed", e));
            client.startDiscovery(SERVICE_ID, discovery,
                    new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                    .addOnFailureListener(e -> Log.e(TAG, "Discovery failed", e));
        } catch (SecurityException e) {
            active = false;
            Log.e(TAG, "Nearby permissions missing", e);
        }
    }

    void stop() {
        active = false;
        client.stopAdvertising();
        client.stopDiscovery();
        client.stopAllEndpoints();
        peers.clear();
        connecting.clear();
        Log.i(TAG, "Nearby relay stopped");
    }

    void forwardPending() {
        for (String endpoint : new HashSet<>(peers)) {
            for (JSONObject envelope : store.pending()) send(endpoint, envelope);
        }
    }

    private void send(String endpoint, JSONObject envelope) {
        String sosId = envelope.optString("sosId");
        try {
            int hopCount = envelope.getInt("hopCount");
            if (hopCount >= SosStore.MAX_HOPS) {
                Log.i(TAG, "Hop limit reached; not forwarding id=" + sosId);
                return;
            }
            JSONObject outbound = new JSONObject(envelope.toString());
            outbound.put("hopCount", hopCount + 1);
            JSONObject packet = new JSONObject().put("type", "SOS").put("envelope", outbound);
            byte[] bytes = packet.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) {
                Log.w(TAG, "SOS exceeds Nearby packet limit id=" + sosId + " bytes=" + bytes.length);
                return;
            }
            client.sendPayload(endpoint, Payload.fromBytes(bytes))
                    .addOnSuccessListener(unused -> Log.i(TAG, "Forwarded SOS id=" + sosId + " endpoint=" + endpoint))
                    .addOnFailureListener(e -> Log.e(TAG, "Forward failed id=" + sosId + " endpoint=" + endpoint, e));
        } catch (Exception e) {
            Log.e(TAG, "Could not encode forwarded SOS id=" + sosId, e);
        }
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override public void onEndpointFound(String endpoint, DiscoveredEndpointInfo info) {
            Log.i(TAG, "Nearby endpoint discovered endpoint=" + endpoint);
            if (nodeName.compareTo(info.getEndpointName()) >= 0) return;
            if (!active || !connecting.add(endpoint)) return;
            client.requestConnection(nodeName, endpoint, lifecycle)
                    .addOnFailureListener(e -> {
                        connecting.remove(endpoint);
                        Log.e(TAG, "Connection request failed endpoint=" + endpoint, e);
                    });
        }

        @Override public void onEndpointLost(String endpoint) {
            connecting.remove(endpoint);
            Log.i(TAG, "Nearby endpoint lost endpoint=" + endpoint);
        }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override public void onConnectionInitiated(String endpoint, ConnectionInfo info) {
            Log.i(TAG, "Nearby connection initiated endpoint=" + endpoint);
            client.acceptConnection(endpoint, payloads)
                    .addOnFailureListener(e -> {
                        connecting.remove(endpoint);
                        Log.e(TAG, "Connection acceptance failed endpoint=" + endpoint, e);
                    });
        }

        @Override public void onConnectionResult(String endpoint, ConnectionResolution result) {
            connecting.remove(endpoint);
            if (result.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK) {
                peers.add(endpoint);
                Log.i(TAG, "Nearby connected endpoint=" + endpoint);
                for (JSONObject envelope : store.pending()) send(endpoint, envelope);
            } else {
                Log.w(TAG, "Nearby connection failed endpoint=" + endpoint + " status=" +
                        result.getStatus().getStatusCode());
            }
        }

        @Override public void onDisconnected(String endpoint) {
            peers.remove(endpoint);
            connecting.remove(endpoint);
            Log.w(TAG, "Nearby disconnected endpoint=" + endpoint);
        }
    };

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override public void onPayloadReceived(String endpoint, Payload payload) {
            if (payload.getType() != Payload.Type.BYTES || payload.asBytes() == null ||
                    payload.asBytes().length > MAX_BYTES) {
                Log.w(TAG, "Rejected unsupported/oversize Nearby payload endpoint=" + endpoint);
                return;
            }
            try {
                JSONObject packet = new JSONObject(new String(payload.asBytes(), StandardCharsets.UTF_8));
                if ("ACK".equals(packet.optString("type"))) {
                    String sosId = packet.optString("sosId");
                    if (sosId.isEmpty()) sosId = packet.optString("id");
                    Log.i(TAG, "Peer ACK received; immediate peer stored id=" + sosId);
                    listener.acknowledged(sosId);
                } else if ("SOS".equals(packet.optString("type"))) {
                    if (!listener.allowReceive()) {
                        Log.w(TAG, "SOS not accepted: relay receive is disabled");
                        return;
                    }
                    JSONObject envelope = packet.getJSONObject("envelope");
                    String sosId = envelope.optString("sosId");
                    envelope.put("isOwner", false);
                    boolean stored = store.put(envelope);
                    if (!stored && !store.contains(sosId)) {
                        Log.w(TAG, "Invalid SOS was not stored or acknowledged endpoint=" + endpoint);
                        return;
                    }
                    JSONObject ack = new JSONObject().put("type", "ACK").put("sosId", sosId);
                    client.sendPayload(endpoint, Payload.fromBytes(ack.toString().getBytes(StandardCharsets.UTF_8)))
                            .addOnSuccessListener(unused -> Log.i(TAG, "Peer ACK sent; local durable copy exists id=" + sosId))
                            .addOnFailureListener(e -> Log.e(TAG, "Peer ACK send failed id=" + sosId, e));
                    if (stored) listener.received(envelope);
                    else Log.i(TAG, "Duplicate SOS already stored; acknowledged id=" + sosId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Invalid Nearby packet endpoint=" + endpoint, e);
            }
        }

        @Override public void onPayloadTransferUpdate(String endpoint, PayloadTransferUpdate update) { }
    };
}