package com.votol.dashboard.core;

import org.json.JSONObject;
import java.util.Iterator;

/**
 * Single source of truth for dashboard state.
 * BLE, WiFi and GPS update this repository; WebView and Android Auto read it.
 */
public class DashboardRepository {

    public interface Listener { void onDashboardDataChanged(); }

    private static final DashboardRepository INSTANCE = new DashboardRepository();
    private final Object lock = new Object();
    private JSONObject latestData = new JSONObject();
    private volatile String cachedSnapshot = "{}";
    private String lastSignature = "";
    private Listener listener;

    private boolean halfRateEnabled = false;
    private boolean dropNextJson = false;

    private int speedSource = SPEED_SOURCE_VOTOL;
    public static final int SPEED_SOURCE_VOTOL = 0;
    public static final int SPEED_SOURCE_GPS = 1;

    private DashboardRepository() { reset(); }

    public static DashboardRepository getInstance() { return INSTANCE; }

    public void reset() {

        synchronized (lock) {

            latestData = new JSONObject();

            try {

                latestData.put("connType", "DISCONNECTED");
                latestData.put("speedSource", SPEED_SOURCE_VOTOL);

            } catch (Exception ignored) {
            }

            cachedSnapshot =
                    "{\"type\":\"dashboard_data\",\"data\":"
                            + latestData.toString()
                            + "}";

            lastSignature = latestData.toString();

        }

    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void updateFromEspJson(String json, String sourceType) {
        try {
            if (halfRateEnabled) {
                dropNextJson = !dropNextJson;
                if (dropNextJson)
                    return;
            }

            JSONObject raw = new JSONObject(json);
            JSONObject data = raw;

            if (raw.has("type") && raw.has("data"))
                data = raw.getJSONObject("data");

            JSONObject next;

            synchronized (lock) {
                next = cloneJson(latestData);
            }

            next.put("connType", sourceType);

            JsonMapper.mergeFastFields(data, next);
            JsonMapper.mergeTemperatureFields(data, next);
            JsonMapper.mergeExtendedFields(data, next);

            next.put("speedSource", speedSource);

            applySnapshot(next);
        }
        catch (Exception ignored) {
        }

    }

    public void updateGpsSpeed(double speedKmh) {

        try {

            JSONObject next;

            synchronized (lock) {
                next = cloneJson(latestData);
            }

            next.put("gpsSpeed", Math.round(speedKmh * 10.0) / 10.0);

            speedSource = SPEED_SOURCE_GPS;
            next.put("speedSource", speedSource);

            applySnapshot(next);

        } catch (Exception ignored) {
        }

    }

    public void setSpeedSource(int source) {

        try {

            JSONObject next;

            synchronized (lock) {
                next = cloneJson(latestData);
            }

            speedSource = source;

            next.put("speedSource", source);

            applySnapshot(next);

        } catch (Exception ignored) {
        }

    }

    public JSONObject getSnapshot() {
        synchronized (lock) {
            return latestData;
        }
    }

    public String buildDashboardMessage() {
        return cachedSnapshot;
    }

    private void applySnapshot(JSONObject next) {
        try {
            String signature = next.toString();
            synchronized (lock) {
                if (signature.equals(lastSignature)) return;
                latestData = next;
                cachedSnapshot =
                        "{\"type\":\"dashboard_data\",\"data\":"
                                + signature
                                + "}";
                lastSignature = signature;
            }
            Listener l = listener;

            if (l != null) {
                l.onDashboardDataChanged();
            }
        } catch (Exception ignored) {}
    }

    private JSONObject cloneJson(JSONObject source) {

        JSONObject copy = new JSONObject();

        Iterator<String> keys = source.keys();

        while (keys.hasNext()) {

            String key = keys.next();

            try {
                copy.put(key, source.get(key));
            } catch (Exception ignored) {
            }

        }

        return copy;

    }
}
