package com.votol.dashboard.debug;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** File + Logcat logger used by BLE/WiFi/GPS/Android Auto. */
public final class DebugLogger {
    private static BufferedWriter writer;
    private DebugLogger() {}

    public static synchronized void init(Context context) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "logs");
            if (!dir.exists()) dir.mkdirs();
            writer = new BufferedWriter(new FileWriter(new File(dir, "debug.txt"), true));
            d("DEBUG LOGGER READY");
        } catch (Exception ignored) {}
    }

    public static synchronized void d(String text) {
        try {
            String line = timestamp() + " " + text;
            Log.d("VOTOL_DEBUG", line);
            if (writer != null) { writer.write(line); writer.newLine(); writer.flush(); }
        } catch (Exception ignored) {}
    }

    public static synchronized void car(Context context, String text) {
        try {
            String line = timestamp() + " CAR " + text;
            Log.d("VOTOL_CAR", line);
            setCarDebugStatus(context, text);
            File baseDir = context.getExternalFilesDir(null);
            if (baseDir == null) return;
            File dir = new File(baseDir, "logs");
            if (!dir.exists()) dir.mkdirs();
            BufferedWriter w = new BufferedWriter(new FileWriter(new File(dir, "car_debug.txt"), true));
            w.write(line); w.newLine(); w.close();
        } catch (Exception ignored) {}
    }

    public static void setCarDebugStatus(Context context, String text) {
        try {
            context.getSharedPreferences("car_debug", Context.MODE_PRIVATE)
                    .edit().putString("last_status", timestamp() + " " + text).apply();
        } catch (Exception ignored) {}
    }

    public static String getCarDebugStatus(Context context) {
        try {
            return context.getSharedPreferences("car_debug", Context.MODE_PRIVATE)
                    .getString("last_status", "CAR SERVICE NOT CALLED");
        } catch (Exception ignored) { return "CAR DEBUG ERROR"; }
    }

    public static synchronized void close() {
        try { if (writer != null) writer.close(); writer = null; }
        catch (Exception ignored) {}
    }

    private static String timestamp() {
        return "[" + new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + "]";
    }
}
