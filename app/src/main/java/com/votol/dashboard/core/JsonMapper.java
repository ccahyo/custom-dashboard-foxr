package com.votol.dashboard.core;

import org.json.JSONObject;

/** Converts compact ESP32 short-key JSON into readable dashboard keys. */
public final class JsonMapper {
    private JsonMapper() {}

    public static void mergeFastFields(JSONObject data, JSONObject out) throws Exception {
        copyIfExists(data, out, "s", "speed");
        copyIfExists(data, out, "r", "rpm");
        copyIfExists(data, out, "v", "volts");
        copyIfExists(data, out, "a", "amps");
        copyIfExists(data, out, "p", "power");
        copyIfExists(data, out, "sc", "soc");
        copyIfExists(data, out, "m", "mode");
        copyIfExists(data, out, "cr", "canrate");
        copyIfExists(data, out, "hb", "heartbeat");
    }

    public static void mergeTemperatureFields(JSONObject data, JSONObject out) throws Exception {
        JSONObject t = data.optJSONObject("t");
        if (t == null) return;
        JSONObject temps = new JSONObject();
        copyIfExists(t, temps, "c", "ctrl");
        copyIfExists(t, temps, "m", "motor");
        copyIfExists(t, temps, "b", "batt");
        if (temps.length() > 0) out.put("temps", temps);
    }

    public static void mergeExtendedFields(JSONObject data, JSONObject out) throws Exception {
        String type = data.optString("type", "fast");
        if ("full".equals(type)) {
            mergeHealth(data, out);
            mergeCellVoltStats(data, out);
            mergeTempStats(data, out);
            mergeBmsInfo(data, out);
            mergeCharger(data, out);
            mergeBalance(data, out);
        }
        copyIfExists(data, out, "cells", "cells");
        copyIfExists(data, out, "type", "type");
    }

    private static void mergeHealth(JSONObject data, JSONObject out) throws Exception {
        JSONObject h = data.optJSONObject("h");
        if (h == null) return;
        JSONObject health = new JSONObject();
        copyIfExists(h, health, "soh", "soh");
        copyIfExists(h, health, "cyc", "cycles");
        copyIfExists(h, health, "rc", "remcap");
        copyIfExists(h, health, "fc", "fullcap");
        if (health.length() > 0) out.put("health", health);
    }

    private static void mergeCellVoltStats(JSONObject data, JSONObject out) throws Exception {
        JSONObject cvs = data.optJSONObject("cvs");
        if (cvs == null) return;
        JSONObject stats = new JSONObject();
        copyIfExists(cvs, stats, "hi", "highest");
        copyIfExists(cvs, stats, "hiC", "highestcell");
        copyIfExists(cvs, stats, "lo", "lowest");
        copyIfExists(cvs, stats, "loC", "lowestcell");
        copyIfExists(cvs, stats, "av", "average");
        if (data.has("cd")) stats.put("delta", data.get("cd"));
        if (stats.length() > 0) out.put("cellvoltstats", stats);
    }

    private static void mergeTempStats(JSONObject data, JSONObject out) throws Exception {
        JSONObject ts = data.optJSONObject("ts");
        if (ts == null) return;
        JSONObject stats = new JSONObject();
        copyIfExists(ts, stats, "max", "max");
        copyIfExists(ts, stats, "maxC", "maxcell");
        copyIfExists(ts, stats, "min", "min");
        copyIfExists(ts, stats, "minC", "mincell");
        if (stats.length() > 0) out.put("tempstats", stats);
    }

    private static void mergeBmsInfo(JSONObject data, JSONObject out) throws Exception {
        JSONObject bms = data.optJSONObject("bms");
        if (bms == null) return;
        JSONObject info = new JSONObject();
        copyIfExists(bms, info, "hw", "hwver");
        copyIfExists(bms, info, "fw", "fwver");
        if (info.length() > 0) out.put("bmsinfo", info);
    }

    private static void mergeCharger(JSONObject data, JSONObject out) throws Exception {
        JSONObject chr = data.optJSONObject("chr");
        if (chr == null) return;
        JSONObject charger = new JSONObject();
        if (chr.has("on")) charger.put("on", chr.optInt("on", 0) == 1);
        copyIfExists(chr, charger, "v", "volts");
        copyIfExists(chr, charger, "a", "amps");
        if (chr.has("ori")) charger.put("original", chr.optInt("ori", 0) == 1);
        if (charger.length() > 0) out.put("charger", charger);
    }

    private static void mergeBalance(JSONObject data, JSONObject out) throws Exception {
        JSONObject b = data.optJSONObject("b");
        if (b == null) return;
        JSONObject balance = new JSONObject();
        copyIfExists(b, balance, "md", "mode");
        copyIfExists(b, balance, "st", "status");
        copyIfExists(b, balance, "cells", "cells");
        if (balance.length() > 0) out.put("balance", balance);
    }

    private static void copyIfExists(JSONObject from, JSONObject to, String sourceKey, String destinationKey) {
        try { if (from.has(sourceKey)) to.put(destinationKey, from.get(sourceKey)); }
        catch (Exception ignored) {}
    }
}
