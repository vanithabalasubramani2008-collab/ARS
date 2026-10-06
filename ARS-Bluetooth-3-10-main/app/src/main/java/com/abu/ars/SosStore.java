package com.abu.ars;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Durable, bounded queue of SOS envelopes. The id is retained after delivery to stop loops. */
final class SosStore {
    static final long LIFETIME_MS = 24L * 60 * 60 * 1000;
    static final int MAX_HOPS = 8;
    private static final int MAX_RECORDS = 200;
    private static final String KEY = "envelopes";
    private final SharedPreferences preferences;

    SosStore(Context context) {
        preferences = context.getSharedPreferences("SosQueue", Context.MODE_PRIVATE);
    }

    synchronized boolean put(JSONObject incoming) {
        try {
            String id = incoming.getString("id");
            long created = incoming.getLong("createdAt");
            int hops = incoming.getInt("hops");
            JSONObject payload = incoming.getJSONObject("payload");
            JSONObject encrypted = payload.getJSONObject("encryptedData");
            if (!id.equals(payload.optString("id")) || !encrypted.has("latitude") ||
                    !encrypted.has("longitude") || !encrypted.has("message") ||
                    !encrypted.has("battery") || !encrypted.has("timestamp") ||
                    id.length() > 80 || id.isEmpty() ||
                    created > System.currentTimeMillis() + 60_000 ||
                    created < System.currentTimeMillis() - LIFETIME_MS ||
                    hops < 0 || hops > MAX_HOPS) return false;
            JSONArray records = read();
            for (int i = 0; i < records.length(); i++) {
                if (id.equals(records.getJSONObject(i).optString("id"))) return false;
            }
            records.put(new JSONObject(incoming.toString()));
            save(records);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    synchronized List<JSONObject> pending() {
        List<JSONObject> items = new ArrayList<>();
        JSONArray records = read();
        for (int i = 0; i < records.length(); i++) {
            JSONObject item = records.optJSONObject(i);
            if (item != null && !item.optBoolean("delivered") &&
                    System.currentTimeMillis() - item.optLong("createdAt") < LIFETIME_MS) {
                items.add(item);
            }
        }
        return items;
    }

    synchronized boolean hasOwnPending() {
        for (JSONObject item : pending()) if (item.optBoolean("own")) return true;
        return false;
    }

    synchronized void delivered(String id) {
        JSONArray records = read();
        for (int i = 0; i < records.length(); i++) {
            JSONObject item = records.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) {
                try { item.put("delivered", true); } catch (JSONException ignored) { }
            }
        }
        save(records);
    }

    private JSONArray read() {
        try { return new JSONArray(preferences.getString(KEY, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    private void save(JSONArray records) {
        JSONArray bounded = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = Math.max(0, records.length() - MAX_RECORDS); i < records.length(); i++) {
            JSONObject item = records.optJSONObject(i);
            if (item != null && now - item.optLong("createdAt") < LIFETIME_MS) bounded.put(item);
        }
        preferences.edit().putString(KEY, bounded.toString()).commit();
    }
}
