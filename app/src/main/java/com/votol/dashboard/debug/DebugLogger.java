package com.votol.dashboard.debug;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DebugLogger {

    /**
     * RELEASE SWITCH
     * true  = debug aktif
     * false = semua debug dimatikan
     */
    public static final boolean DEBUG_MODE = true;

    public static boolean ENABLE_RAW_JSON_LOG = DEBUG_MODE;

    private static final String TAG = "VOTOL_DEBUG";
    private static final String LOG_FILE_NAME = "debug.txt";
    private static final String LOG_RELATIVE_PATH =
            Environment.DIRECTORY_DOWNLOADS + "/logs/";

    private static Context appContext;
    private static BufferedWriter writer;
    private static Uri mediaStoreUri;

    private DebugLogger() {}

    public static synchronized void init(Context context) {

        if (!DEBUG_MODE) {
            return;
        }

        appContext = context.getApplicationContext();

        try {

            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                    ),
                    "logs"
            );

            if (!dir.exists()) {
                dir.mkdirs();
            }

            writer = new BufferedWriter(
                    new FileWriter(
                            new File(dir, LOG_FILE_NAME),
                            true
                    )
            );

        } catch (Exception ignored) {

            writer = null;

        }

        d("DEBUG LOGGER READY target=Download/logs/debug.txt");
    }

    public static synchronized void d(String text) {

        if (!DEBUG_MODE) {
            return;
        }

        String line = timestamp() + " " + text;

        Log.d(TAG, line);

        writeLine(line);
    }

    public static synchronized void rawEsp32Json(String source, String json) {

        if (!ENABLE_RAW_JSON_LOG) {
            return;
        }

        d("RAW_ESP32_JSON source=" + source + " data=" + json);
    }

    private static void writeLine(String line) {

        try {

            if (writer != null) {

                writer.write(line);
                writer.newLine();
                writer.flush();

                return;
            }

        } catch (Exception e) {

            writer = null;

        }

        writeLineWithMediaStore(line);
    }

    private static void writeLineWithMediaStore(String line) {

        if (appContext == null ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        try {

            Uri uri = getOrCreateDownloadLogUri();

            if (uri == null) {
                return;
            }

            OutputStream out =
                    appContext.getContentResolver()
                            .openOutputStream(uri, "wa");

            if (out == null) {
                return;
            }

            out.write((line + "\n").getBytes("UTF-8"));
            out.close();

        } catch (Exception ignored) {
        }
    }

    private static Uri getOrCreateDownloadLogUri() {

        try {

            if (mediaStoreUri != null) {
                return mediaStoreUri;
            }

            ContentResolver resolver =
                    appContext.getContentResolver();

            Uri collection =
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI;

            String[] projection =
                    new String[]{
                            MediaStore.Downloads._ID
                    };

            String selection =
                    MediaStore.Downloads.DISPLAY_NAME +
                            "=? AND " +
                            MediaStore.Downloads.RELATIVE_PATH +
                            "=?";

            String[] args =
                    new String[]{
                            LOG_FILE_NAME,
                            LOG_RELATIVE_PATH
                    };

            Cursor cursor =
                    resolver.query(
                            collection,
                            projection,
                            selection,
                            args,
                            null
                    );

            if (cursor != null) {

                try {

                    if (cursor.moveToFirst()) {

                        long id = cursor.getLong(0);

                        mediaStoreUri =
                                Uri.withAppendedPath(
                                        collection,
                                        String.valueOf(id)
                                );

                        return mediaStoreUri;
                    }

                } finally {

                    cursor.close();

                }
            }

            ContentValues values = new ContentValues();

            values.put(
                    MediaStore.Downloads.DISPLAY_NAME,
                    LOG_FILE_NAME
            );

            values.put(
                    MediaStore.Downloads.MIME_TYPE,
                    "text/plain"
            );

            values.put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    LOG_RELATIVE_PATH
            );

            mediaStoreUri =
                    resolver.insert(
                            collection,
                            values
                    );

            return mediaStoreUri;

        } catch (Exception ignored) {

            return null;

        }
    }

    public static synchronized void car(
            Context context,
            String text
    ) {

        if (!DEBUG_MODE) {
            return;
        }

        try {

            String line =
                    timestamp()
                            + " CAR "
                            + text;

            Log.d(
                    "VOTOL_CAR",
                    line
            );

            setCarDebugStatus(
                    context,
                    text
            );

            writeLine(line);

        } catch (Exception ignored) {
        }
    }

    public static void setCarDebugStatus(
            Context context,
            String text
    ) {

        if (!DEBUG_MODE) {
            return;
        }

        try {

            context.getSharedPreferences(
                            "car_debug",
                            Context.MODE_PRIVATE
                    )
                    .edit()
                    .putString(
                            "last_status",
                            timestamp() + " " + text
                    )
                    .apply();

        } catch (Exception ignored) {
        }
    }

    public static String getCarDebugStatus(
            Context context
    ) {

        if (!DEBUG_MODE) {
            return "DEBUG DISABLED";
        }

        try {

            return context.getSharedPreferences(
                            "car_debug",
                            Context.MODE_PRIVATE
                    )
                    .getString(
                            "last_status",
                            "CAR SERVICE NOT CALLED"
                    );

        } catch (Exception ignored) {

            return "CAR DEBUG ERROR";

        }
    }

    public static synchronized void close() {

        try {

            if (writer != null) {
                writer.close();
            }

        } catch (Exception ignored) {
        }

        writer = null;
        mediaStoreUri = null;
    }

    private static String timestamp() {

        return "[" +
                new SimpleDateFormat(
                        "HH:mm:ss.SSS",
                        Locale.US
                ).format(new Date())
                + "]";
    }
}
