package com.abu.ars;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Durable, bounded SOS outbox keyed by the canonical ARS sosId. */
final class SosStore {
    static final long LIFETIME_MS = 24L * 60 * 60 * 1000;
    static final int MAX_HOPS = 8;
    private static final int MAX_RECORDS = 200;
    private static final String KEY = "envelopes";
    private static final String TAG = "ARS_SOS_STORE";
    private final SharedPreferences preferences;

    SosStore(Context context) {
        preferences = context.getSharedPreferences("SosQueue", Context.MODE_PRIVATE);
    }

    synchronized boolean put(JSONObject incoming) {
        try {
            String sosId = incoming.getString("sosId");
            long createdAt = incoming.getLong("createdAt");
            int hopCount = incoming.getInt("hopCount");
            JSONObject payload = incoming.getJSONObject("payload");
            JSONObject encrypted = payload.getJSONObject("encryptedData");
            if (sosId.isEmpty() || sosId.length() > 100 ||
                    !sosId.equals(payload.optString("sosId")) ||
                    !encrypted.has("latitude") || !encrypted.has("longitude") ||
                    !encrypted.has("message") || !encrypted.has("battery") ||
                    !encrypted.has("timestamp") ||
                    createdAt > System.currentTimeMillis() + 60_000 ||
                    createdAt < System.currentTimeMillis() - LIFETIME_MS ||
                    hopCount < 0 || hopCount > MAX_HOPS) {
                Log.w(TAG, "Rejected malformed/expired SOS id=" + sosId);
                return false;
            }

            JSONArray records = read();
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.optJSONObject(i);
                if (record != null && sosId.equals(record.optString("sosId"))) {
                    Log.i(TAG, "Duplicate SOS id=" + sosId);
                    return false;
                }
            }
            records.put(new JSONObject(incoming.toString()));
            save(records);
            Log.i(TAG, "Stored SOS id=" + sosId);
            return true;
        } catch (JSONException e) {
            Log.e(TAG, "Rejected invalid SOS envelope", e);
            return false;
        }
    }

    synchronized boolean contains(String sosId) {
        JSONArray records = read();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record != null && sosId.equals(record.optString("sosId"))) return true;
        }
        return false;
    }

    synchronized List<JSONObject> pending() {
        List<JSONObject> items = new ArrayList<>();
        JSONArray records = read();
        long now = System.currentTimeMillis();
        for (int i = 0; i < records.length(); i++) {
            JSONObject item = records.optJSONObject(i);
            if (item != null && !"SERVER_RECEIVED".equals(item.optString("serverState")) &&
                    now - item.optLong("createdAt") < LIFETIME_MS) {
                items.add(item);
            }
        }
        return items;
    }

    synchronized boolean isServerReceived(String sosId) {
        JSONObject record = find(sosId);
        return record != null && "SERVER_RECEIVED".equals(record.optString("serverState"));
    }

    synchronized void markServerReceived(String sosId) {
        update(sosId, "serverState", "SERVER_RECEIVED");
    }

    synchronized void markPeerStored(String sosId) {
        update(sosId, "peerStored", true);
    }

    private JSONObject find(String sosId) {
        JSONArray records = read();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record != null && sosId.equals(record.optString("sosId"))) return record;
        }
        return null;
    }

    private void update(String sosId, String field, Object value) {
        JSONArray records = read();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record != null && sosId.equals(record.optString("sosId"))) {
                try {
                    record.put(field, value);
                } catch (JSONException e) {
                    Log.e(TAG, "Could not update SOS state id=" + sosId, e);
                }
            }
        }
        save(records);
    }

    private JSONArray read() {
        try {
            return new JSONArray(preferences.getString(KEY, "[]"));
        } catch (JSONException e) {
            Log.e(TAG, "Queue data was invalid; starting with an empty queue", e);
            return new JSONArray();
        }
    }

    private void save(JSONArray records) {
        JSONArray bounded = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = Math.max(0, records.length() - MAX_RECORDS); i < records.length(); i++) {
            JSONObject item = records.optJSONObject(i);
            if (item != null && now - item.optLong("createdAt") < LIFETIME_MS) bounded.put(item);
        }
        if (!preferences.edit().putString(KEY, bounded.toString()).commit()) {
            Log.e(TAG, "Failed to persist SOS queue");
        }
    }
}