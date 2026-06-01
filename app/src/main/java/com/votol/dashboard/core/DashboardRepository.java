package com.votol.dashboard.core;

import org.json.JSONObject;

/**
 * Single source of truth for dashboard state.
 * BLE, WiFi and GPS update this repository; WebView and Android Auto read it.
 */
public class DashboardRepository {

    public interface Listener { void onDashboardDataChanged(); }

    private static final DashboardRepository INSTANCE = new DashboardRepository();
    private final Object lock = new Object();
    private JSONObject latestData = new JSONObject();
    private String lastSignature = "";
    private Listener listener;

    private DashboardRepository() { reset(); }

    public static DashboardRepository getInstance() { return INSTANCE; }

    public void reset() {
        synchronized (lock) {
            latestData = new JSONObject();
            try { latestData.put("connType", "DISCONNECTED"); } catch (Exception ignored) {}
            lastSignature = latestData.toString();
        }
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void updateFromEspJson(String json, String sourceType) {
        try {
            JSONObject raw = new JSONObject(json);
            JSONObject data = raw;
            if (raw.has("type") && raw.has("data")) data = raw.getJSONObject("data");

            JSONObject next;
            synchronized (lock) { next = new JSONObject(latestData.toString()); }
            next.put("connType", sourceType);
            JsonMapper.mergeFastFields(data, next);
            JsonMapper.mergeTemperatureFields(data, next);
            JsonMapper.mergeExtendedFields(data, next);
            applySnapshot(next);
        } catch (Exception ignored) {}
    }

    public void updateGpsSpeed(double speedKmh) {
        try {
            JSONObject next;
            synchronized (lock) { next = new JSONObject(latestData.toString()); }
            next.put("gpsSpeed", Math.round(speedKmh * 10.0) / 10.0);
            applySnapshot(next);
        } catch (Exception ignored) {}
    }

    public JSONObject getSnapshot() {
        synchronized (lock) {
            try { return new JSONObject(latestData.toString()); }
            catch (Exception ignored) { return new JSONObject(); }
        }
    }

    public JSONObject buildDashboardMessage() throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("type", "dashboard_data");
        msg.put("data", getSnapshot());
        return msg;
    }

    private void applySnapshot(JSONObject next) {
        try {
            String signature = next.toString();
            synchronized (lock) {
                if (signature.equals(lastSignature)) return;
                latestData = new JSONObject(signature);
                lastSignature = signature;
            }
            if (listener != null) listener.onDashboardDataChanged();
        } catch (Exception ignored) {}
    }
}
