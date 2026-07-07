package com.votol.dashboard.wifi;

import com.votol.dashboard.core.DashboardRepository;
import com.votol.dashboard.debug.DebugLogger;
import com.votol.dashboard.web.WebSocketUtils;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** ESP32 AP-mode WebSocket client: ws://192.168.4.1:81/ws */
public class EspWifiClient {
    private ConnectionListener listener;
    private final DashboardRepository repository;
    private volatile boolean running = false;
    private Thread thread;
    private boolean halfRateEnabled = false;
    private boolean dropNextJson = false;
    private volatile boolean stopping = false;
    public EspWifiClient(DashboardRepository repository) {
        this.repository = repository;
    }
    public void start() {

        if (running)
            return;

        stopping = false;
        running = true;

        thread = new Thread(this::loop, "esp32-wifi-client");
        thread.start();
    }
    public void stop() {

        stopping = true;

        running = false;

        if (thread != null)
            thread.interrupt();
    }
    private void loop() {
        while (running) {
            try {
                connectAndRead();
            } catch (Exception ignored) {}
            if (running)
                WebSocketUtils.sleepQuietly(2000);
        }
    }
    private void connectAndRead() throws Exception {
        String host="192.168.4.1", path="/ws"; int port=81; Socket socket = new Socket(host, port); socket.setSoTimeout(10000);
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String key = Base64.getEncoder().encodeToString(("votol-" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String resp = WebSocketUtils.readHttpHeader(in);
            if (!resp.contains("101"))
                return;
            if(listener!=null)
                listener.onConnected();
            while (running && !socket.isClosed()) {
                String msg = WebSocketUtils.readWebSocketTextFrame(in);
                if (msg == null)
                    break;
                if (msg.length() > 0) {
                    if (halfRateEnabled) {
                        dropNextJson = !dropNextJson;
                        if (dropNextJson)
                            continue;
                    }
                    DebugLogger.rawEsp32Json("wifi", msg);
                    repository.updateFromEspJson(msg, "wifi");
                }
            }
        } finally {
            if (!stopping && listener != null)
                listener.onDisconnected();
            WebSocketUtils.closeQuietly(socket);
        }
    }
    
    public interface ConnectionListener{
        void onConnected();
        void onDisconnected();
    }

    public void setConnectionListener(ConnectionListener listener){
        this.listener = listener;
    }
}
