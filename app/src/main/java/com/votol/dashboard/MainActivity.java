package com.votol.dashboard;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Release-ready VOTOL dashboard bridge.
 *
 * Design notes:
 * - WebView only renders assets/dashboard.html.
 * - Android acts as a local backend compatible with the existing dashboard websocket flow.
 * - WiFi packets are read as WebSocket JSON from ESP32.
 * - BLE subscribes to firmware dashboard notify UUID, assembles JSON chunks, then processJSON().
 * - processJSON() is the single source of truth and keeps the latest merged state.
 */
public class MainActivity extends AppCompatActivity {

    private static final int LOCAL_PORT = 8080;
    private static final String LOCAL_URL = "http://127.0.0.1:" + LOCAL_PORT + "/";
    private static final int REQUEST_PERMISSIONS = 100;
    private static final int BLE_REQUESTED_MTU = 247;
    private static final long BLE_CONNECT_DELAY_MS = 900;
    private static final long BLE_SCAN_TIMEOUT_MS = 10000;
    private static final long BLE_RECONNECT_DELAY_MS = 3000;
    private static final long BLE_MTU_FALLBACK_MS = 1800;

    private WebView dashboardView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean appRunning = true;

    private final Object dataLock = new Object();
    private JSONObject latestData = new JSONObject();
    private String lastMergedSignature = "";

    private DashboardWebServer dashboardServer;
    private EspWifiClient espWifiClient;
    private BleClient bleClient;

    private BufferedWriter debugWriter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initDebugLogger();

        /*
         * Keep screen on like video playback/navigation mode.
         * This prevents Android from dimming or sleeping while dashboard is active.
         */
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        requestRuntimePermissions();
        initializeInitialState();
        initializeWebView();

        dashboardServer = new DashboardWebServer();
        dashboardServer.start();

        bleClient = new BleClient();

        /*
         * Runtime permission dialog is asynchronous.
         * Start BLE slightly later, then start again from onRequestPermissionsResult().
         * BleClient itself is idempotent, so duplicate start calls are safe.
         */
        mainHandler.postDelayed(() -> {
            if (appRunning && bleClient != null) {
                bleClient.startScan();
            }
        }, 1000);

        espWifiClient = new EspWifiClient();
        espWifiClient.start();

        dashboardView.loadUrl(LOCAL_URL);
    }

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        ActivityCompat.requestPermissions(
                this,
                permissions.toArray(new String[0]),
                REQUEST_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS && bleClient != null) {
            mainHandler.postDelayed(() -> {
                if (appRunning) {
                    bleClient.startScan();
                }
            }, 500);
        }
    }

    private void initializeInitialState() {
        try {
            latestData.put("connType", "DISCONNECTED");
        } catch (Exception ignored) {
        }
    }

    private void initializeWebView() {
        WebView.setWebContentsDebuggingEnabled(false);

        dashboardView = new WebView(this);
        dashboardView.setKeepScreenOn(true);

        WebSettings settings = dashboardView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        setContentView(dashboardView);
    }

    /**
     * Main state merger. This mirrors main.go behavior:
     * partial BLE packets update only fields they contain, while existing state is preserved.
     */
    private void processJSON(String jsonStr, String sourceType) {
        try {
            JSONObject raw = new JSONObject(jsonStr);
            JSONObject data = raw;

            if (raw.has("type") && raw.has("data")) {
                data = raw.getJSONObject("data");
            }

            JSONObject next;

            synchronized (dataLock) {
                next = new JSONObject(latestData.toString());
            }

            next.put("connType", sourceType);

            mergeFastFields(data, next);
            mergeTemperatureFields(data, next);
            mergeExtendedFields(data, next);

            String signature = next.toString();

            if (signature.equals(lastMergedSignature)) {
                return;
            }

            lastMergedSignature = signature;

            synchronized (dataLock) {
                latestData = new JSONObject(signature);
            }

            if (dashboardServer != null) {
                dashboardServer.broadcastLatestData();
            }

        } catch (Exception ignored) {
        }
    }

    private void mergeFastFields(JSONObject data, JSONObject out) throws Exception {
        /*
         * ESP32 protocol uses compact short keys.
         * Output keys are dashboard-friendly names.
         */
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

    private void mergeTemperatureFields(JSONObject data, JSONObject out) throws Exception {
        if (!data.has("t")) {
            return;
        }

        JSONObject t = data.optJSONObject("t");

        if (t == null) {
            return;
        }

        JSONObject temps = new JSONObject();

        copyIfExists(t, temps, "c", "ctrl");
        copyIfExists(t, temps, "m", "motor");
        copyIfExists(t, temps, "b", "batt");

        if (temps.length() > 0) {
            out.put("temps", temps);
        }
    }

    private void mergeExtendedFields(JSONObject data, JSONObject out) throws Exception {
        /*
         * Only full packets should update extended information.
         * "type" is read directly from the ESP32 JSON.
         */
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

    private void mergeHealth(JSONObject data, JSONObject out) throws Exception {
        JSONObject h = data.optJSONObject("h");

        if (h == null) {
            return;
        }

        JSONObject health = new JSONObject();

        copyIfExists(h, health, "soh", "soh");
        copyIfExists(h, health, "cyc", "cycles");
        copyIfExists(h, health, "rc", "remcap");
        copyIfExists(h, health, "fc", "fullcap");

        if (health.length() > 0) {
            out.put("health", health);
        }
    }

    private void mergeCellVoltStats(JSONObject data, JSONObject out) throws Exception {
        JSONObject cvs = data.optJSONObject("cvs");

        if (cvs == null) {
            return;
        }

        JSONObject cellVoltStats = new JSONObject();

        copyIfExists(cvs, cellVoltStats, "hi", "highest");
        copyIfExists(cvs, cellVoltStats, "hiC", "highestcell");
        copyIfExists(cvs, cellVoltStats, "lo", "lowest");
        copyIfExists(cvs, cellVoltStats, "loC", "lowestcell");
        copyIfExists(cvs, cellVoltStats, "av", "average");

        if (cellVoltStats.length() > 0) {
            out.put("cellvoltstats", cellVoltStats);
        }
    }

    private void mergeTempStats(JSONObject data, JSONObject out) throws Exception {
        JSONObject ts = data.optJSONObject("ts");

        if (ts == null) {
            return;
        }

        JSONObject tempStats = new JSONObject();

        copyIfExists(ts, tempStats, "max", "max");
        copyIfExists(ts, tempStats, "maxC", "maxcell");
        copyIfExists(ts, tempStats, "min", "min");
        copyIfExists(ts, tempStats, "minC", "mincell");

        if (tempStats.length() > 0) {
            out.put("tempstats", tempStats);
        }
    }

    private void mergeBmsInfo(JSONObject data, JSONObject out) throws Exception {
        JSONObject bms = data.optJSONObject("bms");

        if (bms == null) {
            return;
        }

        JSONObject bmsInfo = new JSONObject();

        copyIfExists(bms, bmsInfo, "hw", "hwver");
        copyIfExists(bms, bmsInfo, "fw", "fwver");

        if (bmsInfo.length() > 0) {
            out.put("bmsinfo", bmsInfo);
        }
    }

    private void mergeCharger(JSONObject data, JSONObject out) throws Exception {
        JSONObject chr = data.optJSONObject("chr");

        if (chr == null) {
            return;
        }

        JSONObject charger = new JSONObject();

        if (chr.has("on")) {
            charger.put("on", chr.optInt("on", 0) == 1);
        }

        copyIfExists(chr, charger, "v", "volts");
        copyIfExists(chr, charger, "a", "amps");

        if (chr.has("ori")) {
            charger.put("original", chr.optInt("ori", 0) == 1);
        }

        if (charger.length() > 0) {
            out.put("charger", charger);
        }
    }

    private void mergeBalance(JSONObject data, JSONObject out) throws Exception {
        JSONObject b = data.optJSONObject("b");

        if (b == null) {
            return;
        }

        JSONObject balance = new JSONObject();

        copyIfExists(b, balance, "md", "mode");
        copyIfExists(b, balance, "st", "status");
        copyIfExists(b, balance, "cells", "cells");

        if (balance.length() > 0) {
            out.put("balance", balance);
        }
    }

    /**
     * Copies a value when source and destination key names are equal.
     */
    private void copyIfExists(JSONObject from, JSONObject to, String key) {
        copyIfExists(from, to, key, key);
    }

    /**
     * Copies a value from keysource to keydestiny only when keysource exists.
     *
     * This keeps protocol translation short and explicit:
     * ESP32 source key -> dashboard destination key.
     */
    private void copyIfExists(
            JSONObject from,
            JSONObject to,
            String keysource,
            String keydestiny
    ) {
        try {
            if (from.has(keysource)) {
                to.put(keydestiny, from.get(keysource));
            }
        } catch (Exception ignored) {
        }
    }

    private JSONObject buildDashboardMessage() throws Exception {
        JSONObject msg = new JSONObject();

        synchronized (dataLock) {
            msg.put("type", "dashboard_data");
            msg.put("data", new JSONObject(latestData.toString()));
        }

        return msg;
    }

    /** Serves dashboard.html and the local websocket endpoint consumed by dashboard.html. */
    private class DashboardWebServer {
        private ServerSocket serverSocket;
        private Thread serverThread;
        private final List<WsClient> clients = new ArrayList<>();
        private String lastBroadcast = "";
        private long lastBroadcastMs = 0L;

        void start() {
            serverThread = new Thread(() -> {
                try {
                    serverSocket = new ServerSocket(LOCAL_PORT);

                    while (appRunning) {
                        Socket socket = serverSocket.accept();
                        new Thread(() -> handleClient(socket), "local-dashboard-client").start();
                    }
                } catch (Exception ignored) {
                }
            }, "local-dashboard-server");

            serverThread.start();
        }

        void stop() {
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (Exception ignored) {
            }

            synchronized (clients) {
                for (WsClient client : clients) {
                    client.close();
                }

                clients.clear();
            }
        }

        void broadcastLatestData() {
            try {
                String text = buildDashboardMessage().toString();
                long now = System.currentTimeMillis();

                if (text.equals(lastBroadcast)) {
                    return;
                }

                if (now - lastBroadcastMs < 120) {
                    return;
                }

                lastBroadcast = text;
                lastBroadcastMs = now;

                synchronized (clients) {
                    Iterator<WsClient> it = clients.iterator();

                    while (it.hasNext()) {
                        WsClient client = it.next();

                        if (!client.send(text)) {
                            it.remove();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private void handleClient(Socket socket) {
            try {
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                String request = readHttpHeader(in);

                if (request == null || request.length() == 0) {
                    socket.close();
                    return;
                }

                if (request.startsWith("GET /ws")) {
                    handleWebSocket(socket, request, in, out);
                    return;
                }

                serveDashboardHtml(socket, out);

            } catch (Exception ignored) {
                closeQuietly(socket);
            }
        }

        private void serveDashboardHtml(Socket socket, OutputStream out) {
            try {
                byte[] html = readAssetBytes("dashboard.html");
                String header =
                        "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Cache-Control: no-cache\r\n" +
                                "Content-Length: " + html.length + "\r\n\r\n";

                out.write(header.getBytes(StandardCharsets.UTF_8));
                out.write(html);
                out.flush();
            } catch (Exception ignored) {
            } finally {
                closeQuietly(socket);
            }
        }

        private void handleWebSocket(Socket socket, String header, InputStream in, OutputStream out) {
            try {
                String key = extractWebSocketKey(header);

                if (key == null) {
                    closeQuietly(socket);
                    return;
                }

                String response =
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: " + websocketAccept(key) + "\r\n\r\n";

                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();

                WsClient client = new WsClient(socket, out);

                synchronized (clients) {
                    clients.add(client);
                }

                client.send(buildDashboardMessage().toString());

                while (appRunning && !socket.isClosed()) {
                    String incoming = readWebSocketTextFrame(in);

                    if (incoming == null) {
                        break;
                    }
                }

            } catch (Exception ignored) {
            } finally {
                closeQuietly(socket);
                removeDeadClients();
            }
        }

        private void removeDeadClients() {
            synchronized (clients) {
                Iterator<WsClient> it = clients.iterator();

                while (it.hasNext()) {
                    WsClient client = it.next();

                    if (client.isClosed()) {
                        it.remove();
                    }
                }
            }
        }
    }

    private class WsClient {
        private final Socket socket;
        private final OutputStream out;

        WsClient(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        synchronized boolean send(String text) {
            try {
                if (socket.isClosed()) {
                    return false;
                }

                writeWebSocketTextFrame(out, text);
                return true;

            } catch (Exception ignored) {
                close();
                return false;
            }
        }

        boolean isClosed() {
            return socket.isClosed();
        }

        void close() {
            closeQuietly(socket);
        }
    }

    /** WiFi client for ESP32 AP mode. WiFi normally provides complete JSON packets. */
    private class EspWifiClient {
        private Thread thread;

        void start() {
            thread = new Thread(() -> {
                while (appRunning) {
                    try {
                        connectAndRead();
                    } catch (Exception ignored) {
                    }

                    sleepQuietly(2000);
                }
            }, "esp32-wifi-client");

            thread.start();
        }

        void stop() {
            if (thread != null) {
                thread.interrupt();
            }
        }

        private void connectAndRead() throws Exception {
            String host = "192.168.4.1";
            int port = 81;
            String path = "/ws";

            Socket socket = new Socket(host, port);
            socket.setSoTimeout(10000);

            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                String key = Base64.getEncoder().encodeToString(
                        ("votol-" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)
                );

                String request =
                        "GET " + path + " HTTP/1.1\r\n" +
                                "Host: " + host + ":" + port + "\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Key: " + key + "\r\n" +
                                "Sec-WebSocket-Version: 13\r\n\r\n";

                out.write(request.getBytes(StandardCharsets.UTF_8));
                out.flush();

                String response = readHttpHeader(in);

                if (!response.contains("101")) {
                    return;
                }

                while (appRunning && !socket.isClosed()) {
                    String msg = readWebSocketTextFrame(in);

                    if (msg == null) {
                        break;
                    }

                    if (msg.length() > 0) {
                        //debug("WIFI JSON=" + msg);
                        processJSON(msg, "wifi");
                    }
                }
            } finally {
                closeQuietly(socket);
            }
        }
    }

    /**
     * BLE client for Votol_BLE.
     *
     * Behavior is intentionally service-like:
     * - scan retries automatically when target is not found
     * - connect is delayed after scan result to avoid busy Android/ESP32 BLE stack
     * - service discovery runs before notify and MTU
     * - service discovery has a fallback if onMtuChanged() never arrives
     * - disconnect always schedules reconnect
     */
    private class BleClient {
        private static final String TARGET_NAME = "Votol_BLE";

        /*
         * Firmware votol_ble_dualcore.ino uses this characteristic for
         * dashboard BLE stream:
         * CHARACTERISTIC_UUID = beb5483e-36e1-4688-b7f5-ea07361b26a8
         * Properties: READ | NOTIFY | WRITE + BLE2902 CCCD.
         */
        private static final String DASHBOARD_NOTIFY_UUID =
                "beb5483e-36e1-4688-b7f5-ea07361b26a8";

        private static final String CCCD_UUID =
                "00002902-0000-1000-8000-00805f9b34fb";

        private BluetoothLeScanner scanner;
        private BluetoothGatt gatt;

        private boolean scanning = false;
        private boolean connecting = false;
        private boolean connected = false;
        private boolean discoveryStarted = false;

        private BluetoothGattCharacteristic dashboardNotifyCharacteristic;
        private final StringBuilder bleJsonBuffer = new StringBuilder();

        void startScan() {
            if (!appRunning) {
                return;
            }

            if (connecting || connected || scanning) {
                return;
            }

            try {
                BluetoothManager manager =
                        (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);

                if (manager == null) {
                    scheduleReconnect(BLE_RECONNECT_DELAY_MS);
                    return;
                }

                BluetoothAdapter adapter = manager.getAdapter();

                if (adapter == null || !adapter.isEnabled()) {
                    scheduleReconnect(BLE_RECONNECT_DELAY_MS);
                    return;
                }

                scanner = adapter.getBluetoothLeScanner();

                if (scanner == null) {
                    scheduleReconnect(BLE_RECONNECT_DELAY_MS);
                    return;
                }

                scanning = true;
                //debug("BLE SCAN START");
                scanner.startScan(scanCallback);

                /*
                 * If no target is found, restart the scan.
                 * This prevents the app from getting stuck in a silent scan state.
                 */
                mainHandler.postDelayed(() -> {
                    if (!appRunning || !scanning || connecting || connected) {
                        return;
                    }

                    stopScanOnly();
                    scheduleReconnect(BLE_RECONNECT_DELAY_MS);
                }, BLE_SCAN_TIMEOUT_MS);

            } catch (Exception ignored) {
                scanning = false;
                scheduleReconnect(BLE_RECONNECT_DELAY_MS);
            }
        }

        void stop() {
            stopScanOnly();
            closeGatt();

            connecting = false;
            connected = false;
            discoveryStarted = false;
            dashboardNotifyCharacteristic = null;

            synchronized (bleJsonBuffer) {
                bleJsonBuffer.setLength(0);
            }
            dashboardNotifyCharacteristic = null;

            synchronized (bleJsonBuffer) {
                bleJsonBuffer.setLength(0);
            }
        }

        private void scheduleReconnect(long delayMs) {
            if (!appRunning) {
                return;
            }

            mainHandler.postDelayed(() -> {
                if (appRunning && !connecting && !connected) {
                    startScan();
                }
            }, delayMs);
        }

        private void stopScanOnly() {
            try {
                if (scanner != null && scanning) {
                    scanner.stopScan(scanCallback);
                }
            } catch (Exception ignored) {
            }

            scanning = false;
        }

        private void closeGatt() {
            try {
                if (gatt != null) {
                    gatt.disconnect();
                    gatt.close();
                }
            } catch (Exception ignored) {
            }

            gatt = null;
        }

        private void startServiceDiscovery(BluetoothGatt gatt) {
            if (discoveryStarted || gatt == null) {
                return;
            }

            discoveryStarted = true;

            try {
                gatt.discoverServices();
            } catch (Exception ignored) {
                handleDisconnectedGatt(gatt);
            }
        }

        private void handleDisconnectedGatt(BluetoothGatt gatt) {
            connecting = false;
            connected = false;
            discoveryStarted = false;

            try {
                if (gatt != null) {
                    gatt.close();
                }
            } catch (Exception ignored) {
            }

            if (this.gatt == gatt) {
                this.gatt = null;
            }

            scheduleReconnect(BLE_RECONNECT_DELAY_MS);
        }

        private final ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                try {
                    if (result.getDevice() == null ||
                            result.getDevice().getName() == null) {
                        return;
                    }

                    if (!TARGET_NAME.equals(result.getDevice().getName())) {
                        return;
                    }

                    if (connecting || connected) {
                        return;
                    }

                    stopScanOnly();

                    connecting = true;
                    discoveryStarted = false;

                    closeGatt();

                    /*
                     * Small delay mirrors the stability you observed manually:
                     * scan stop -> short idle -> connectGatt.
                     * This reduces status=62 / status=8 failures on ESP32 BLE.
                     */
                    mainHandler.postDelayed(() -> {
                        if (!appRunning || !connecting || connected) {
                            return;
                        }

                        try {
                            gatt = result.getDevice().connectGatt(
                                    MainActivity.this,
                                    false,
                                    gattCallback
                            );

                            if (gatt == null) {
                                connecting = false;
                                scheduleReconnect(BLE_RECONNECT_DELAY_MS);
                            }

                        } catch (Exception ignored) {
                            connecting = false;
                            scheduleReconnect(BLE_RECONNECT_DELAY_MS);
                        }
                    }, BLE_CONNECT_DELAY_MS);

                } catch (Exception ignored) {
                    connecting = false;
                    scheduleReconnect(BLE_RECONNECT_DELAY_MS);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanning = false;
                connecting = false;
                scheduleReconnect(BLE_RECONNECT_DELAY_MS);
            }
        };


        private void enableDashboardNotify(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic
        ) {
            try {
                boolean notifyOk =
                        gatt.setCharacteristicNotification(
                                characteristic,
                                true
                        );

                /*
                //debug(
                        "BLE ENABLE DASHBOARD NOTIFY "
                                + characteristic.getUuid()
                                + " result="
                                + notifyOk
                );
                */

                if (!notifyOk) {
                    handleDisconnectedGatt(gatt);
                    return;
                }

                BluetoothGattDescriptor descriptor =
                        characteristic.getDescriptor(
                                UUID.fromString(CCCD_UUID)
                        );

                if (descriptor == null) {
                    //debug("BLE DASHBOARD CCCD NULL");
                    handleDisconnectedGatt(gatt);
                    return;
                }

                descriptor.setValue(
                        BluetoothGattDescriptor
                                .ENABLE_NOTIFICATION_VALUE
                );

                boolean writeOk =
                        gatt.writeDescriptor(descriptor);

                //debug("BLE DASHBOARD CCCD WRITE result=" + writeOk);

                if (!writeOk) {
                    handleDisconnectedGatt(gatt);
                }

            } catch (Exception ignored) {
                handleDisconnectedGatt(gatt);
            }
        }

        private void handleBleChunk(String chunk) {
            if (chunk == null || chunk.length() == 0) {
                return;
            }

            synchronized (bleJsonBuffer) {
                bleJsonBuffer.append(chunk);

                if (bleJsonBuffer.length() > 4096) {
                    bleJsonBuffer.delete(
                            0,
                            bleJsonBuffer.length() - 2048
                    );
                }

                while (true) {
                    String buffer = bleJsonBuffer.toString();
                    int start = buffer.indexOf("{");
                    int end = findCompleteJsonEnd(buffer, start);

                    if (start < 0) {
                        bleJsonBuffer.setLength(0);
                        return;
                    }

                    if (end < 0) {
                        if (start > 0) {
                            bleJsonBuffer.delete(0, start);
                        }
                        return;
                    }

                    String json = buffer.substring(start, end + 1);
                    bleJsonBuffer.delete(0, end + 1);

                    //debug("BLE JSON=" + json);
                    processJSON(json, "ble");
                }
            }
        }

        private int findCompleteJsonEnd(String text, int start) {
            if (start < 0) {
                return -1;
            }

            int depth = 0;
            boolean inString = false;
            boolean escaped = false;

            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);

                if (escaped) {
                    escaped = false;
                    continue;
                }

                if (c == '\\') {
                    escaped = true;
                    continue;
                }

                if (c == '"') {
                    inString = !inString;
                    continue;
                }

                if (inString) {
                    continue;
                }

                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;

                    if (depth == 0) {
                        return i;
                    }
                }
            }

            return -1;
        }

        private final BluetoothGattCallback gattCallback =
                new BluetoothGattCallback() {

                    @Override
                    public void onConnectionStateChange(
                            BluetoothGatt gatt,
                            int status,
                            int newState
                    ) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            handleDisconnectedGatt(gatt);
                            return;
                        }

                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            connecting = false;
                            connected = true;
                            discoveryStarted = false;

                            /*
                             * Ask Android/ESP32 for a faster connection interval.
                             * It is safe if the stack ignores the request.
                             */
                            try {
                                gatt.requestConnectionPriority(
                                        BluetoothGatt.CONNECTION_PRIORITY_HIGH
                                );
                            } catch (Exception ignored) {
                            }

                            //debug("BLE CONNECTED");

                            /*
                             * ESP32 BLE is much more stable with:
                             * CONNECT
                             * -> DISCOVER SERVICES
                             * -> ENABLE NOTIFY
                             * -> REQUEST MTU
                             *
                             * Requesting MTU too early often causes
                             * Android to stay in "configuring".
                             */
                            mainHandler.postDelayed(() -> {
                                if (appRunning && connected) {
                                    //debug("BLE discoverServices");
                                    startServiceDiscovery(gatt);
                                }
                            }, 300);

                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            handleDisconnectedGatt(gatt);
                        }
                    }

                    @Override
                    public void onMtuChanged(
                            BluetoothGatt gatt,
                            int mtu,
                            int status
                    ) {
                        //debug("BLE MTU mtu=" + mtu + " status=" + status);
                    }

                    @Override
                    public void onServicesDiscovered(
                            BluetoothGatt gatt,
                            int status
                    ) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            handleDisconnectedGatt(gatt);
                            return;
                        }

                        dashboardNotifyCharacteristic = null;

                        for (BluetoothGattService service : gatt.getServices()) {
                            for (BluetoothGattCharacteristic characteristic :
                                    service.getCharacteristics()) {

                                String uuid =
                                        characteristic.getUuid()
                                                .toString()
                                                .toLowerCase(Locale.US);

                                //debug("BLE CHAR " + uuid);

                                if (DASHBOARD_NOTIFY_UUID.equals(uuid)) {
                                    dashboardNotifyCharacteristic = characteristic;
                                }
                            }
                        }

                        if (dashboardNotifyCharacteristic == null) {
                            //debug("BLE DASHBOARD NOTIFY CHAR NOT FOUND");
                            handleDisconnectedGatt(gatt);
                            return;
                        }

                        enableDashboardNotify(
                                gatt,
                                dashboardNotifyCharacteristic
                        );
                    }

                    @Override
                    public void onDescriptorWrite(
                            BluetoothGatt gatt,
                            BluetoothGattDescriptor descriptor,
                            int status
                    ) {
                        /*
                        //debug(
                                "BLE DESCRIPTOR WRITE status="
                                        + status
                                        + " uuid="
                                        + descriptor.getUuid()
                        );
                        */

                        mainHandler.postDelayed(() -> {
                            try {
                                if (!connected) {
                                    return;
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    boolean mtuResult =
                                            gatt.requestMtu(BLE_REQUESTED_MTU);

                                    /*
                                    //debug(
                                            "BLE requestMtu247 after CCCD="
                                                    + mtuResult
                                    );
                                    */
                                }
                            } catch (Exception ignored) {
                            }
                        }, 300);
                    }

                    @Override
                    public void onCharacteristicChanged(
                            BluetoothGatt gatt,
                            BluetoothGattCharacteristic characteristic
                    ) {
                        try {
                            if (characteristic == null ||
                                    !DASHBOARD_NOTIFY_UUID.equals(
                                            characteristic.getUuid()
                                                    .toString()
                                                    .toLowerCase(Locale.US)
                                    )) {
                                return;
                            }

                            byte[] value = characteristic.getValue();

                            if (value == null || value.length == 0) {
                                return;
                            }

                            String chunk =
                                    new String(value, StandardCharsets.UTF_8);

                            //debug("BLE CHUNK=" + chunk);
                            handleBleChunk(chunk);

                        } catch (Exception ignored) {
                        }
                    }
                };
    }

    /**
     * Reads HTTP headers until CRLF CRLF.
     */
    private String readHttpHeader(InputStream in)
            throws Exception {

        ByteArrayOutputStream baos =
                new ByteArrayOutputStream();

        int last4 = 0;
        int b;

        while ((b = in.read()) != -1) {

            baos.write(b);

            last4 =
                    ((last4 << 8) | b) &
                            0xffffffff;

            if (last4 == 0x0d0a0d0a) {
                break;
            }

            if (baos.size() > 8192) {
                break;
            }
        }

        return baos.toString("UTF-8");
    }

    /**
     * Reads asset file bytes.
     */
    private byte[] readAssetBytes(String name)
            throws Exception {

        InputStream is =
                getAssets().open(name);

        ByteArrayOutputStream baos =
                new ByteArrayOutputStream();

        byte[] buffer =
                new byte[4096];

        int n;

        while ((n = is.read(buffer)) > 0) {
            baos.write(buffer, 0, n);
        }

        is.close();

        return baos.toByteArray();
    }

    /**
     * Extract websocket key from HTTP upgrade header.
     */
    private String extractWebSocketKey(
            String header
    ) {

        String[] lines =
                header.split("\r\n");

        for (String line : lines) {

            String lower =
                    line.toLowerCase(
                            Locale.US
                    );

            if (lower.startsWith(
                    "sec-websocket-key:"
            )) {

                return line.substring(
                        line.indexOf(":") + 1
                ).trim();
            }
        }

        return null;
    }

    /**
     * Builds websocket accept response.
     */
    private String websocketAccept(
            String key
    ) throws Exception {

        String magic =
                key +
                        "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        MessageDigest sha1 =
                MessageDigest.getInstance(
                        "SHA-1"
                );

        byte[] hash =
                sha1.digest(
                        magic.getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        return Base64.getEncoder()
                .encodeToString(hash);
    }

    /**
     * Sends websocket text frame.
     */
    private void writeWebSocketTextFrame(
            OutputStream out,
            String text
    ) throws Exception {

        byte[] payload =
                text.getBytes(
                        StandardCharsets.UTF_8
                );

        out.write(0x81);

        if (payload.length <= 125) {

            out.write(payload.length);

        } else if (payload.length <= 65535) {

            out.write(126);

            out.write(
                    (payload.length >> 8) &
                            0xff
            );

            out.write(
                    payload.length &
                            0xff
            );

        } else {

            out.write(127);

            long len =
                    payload.length;

            for (int i = 7; i >= 0; i--) {

                out.write(
                        (int)
                                ((len >> (8 * i)) & 0xff)
                );
            }
        }

        out.write(payload);
        out.flush();
    }

    /**
     * Reads websocket text frame.
     */
    private String readWebSocketTextFrame(
            InputStream in
    ) throws Exception {

        int b1 = in.read();

        if (b1 < 0) {
            return null;
        }

        int b2 = in.read();

        if (b2 < 0) {
            return null;
        }

        int opcode =
                b1 & 0x0f;

        boolean masked =
                (b2 & 0x80) != 0;

        long len =
                b2 & 0x7f;

        if (len == 126) {

            len =
                    ((long) in.read() << 8) |
                            in.read();

        } else if (len == 127) {

            len = 0;

            for (int i = 0; i < 8; i++) {

                len =
                        (len << 8) |
                                in.read();
            }
        }

        byte[] mask =
                new byte[4];

        if (masked) {

            int read =
                    in.read(mask);

            if (read != 4) {
                return null;
            }
        }

        byte[] payload =
                new byte[(int) len];

        int offset = 0;

        while (offset < payload.length) {

            int read =
                    in.read(
                            payload,
                            offset,
                            payload.length - offset
                    );

            if (read < 0) {
                return null;
            }

            offset += read;
        }

        if (masked) {

            for (int i = 0; i < payload.length; i++) {

                payload[i] =
                        (byte)
                                (payload[i] ^
                                        mask[i % 4]);
            }
        }

        if (opcode == 0x8) {
            return null;
        }

        if (opcode != 0x1) {
            return "";
        }

        return new String(
                payload,
                StandardCharsets.UTF_8
        );
    }

    /**
     * Quiet socket close helper.
     */
    private void closeQuietly(
            Socket socket
    ) {

        try {

            if (socket != null) {
                socket.close();
            }

        } catch (Exception ignored) {
        }
    }

    /**
     * Quiet sleep helper.
     */
    private void sleepQuietly(
            long millis
    ) {

        try {
            Thread.sleep(millis);
        } catch (Exception ignored) {
        }
    }

    /**
     * Debug helper for Logcat.
     *
     * Filter Logcat by tag:
     * VOTOL_DEBUG
     */
    /*
    private synchronized void debug(String text) {
        try {
            String time =
                    new SimpleDateFormat(
                            "HH:mm:ss.SSS",
                            Locale.US
                    ).format(new Date());

            String line =
                    "[" + time + "] " + text;

            Log.d("VOTOL_DEBUG", line);

            if (debugWriter != null) {

                debugWriter.write(line);

                debugWriter.newLine();

                debugWriter.flush();
            }

        } catch (Exception ignored) {
        }
    }
    */

    private void initDebugLogger() {
        try {
            File downloadDir =
                    android.os.Environment
                            .getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS
                            );

            File logDir =
                    new File(downloadDir, "logs");

            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            File debugFile =
                    new File(logDir, "debug.txt");

            debugWriter =
                    new BufferedWriter(
                            new FileWriter(debugFile, true)
                    );

        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        appRunning = false;

        if (dashboardServer != null) {
            dashboardServer.stop();
        }

        if (espWifiClient != null) {
            espWifiClient.stop();
        }

        if (bleClient != null) {
            bleClient.stop();
        }

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            if (debugWriter != null) {
                debugWriter.close();
            }
        } catch (Exception ignored) {
        }

        super.onDestroy();
    }
}
