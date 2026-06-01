package com.votol.dashboard.web;

import android.content.Context;
import com.votol.dashboard.core.DashboardRepository;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Serves assets/dashboard.html and the local /ws endpoint. */
public class DashboardWebServer {
    private final Context context; private final DashboardRepository repository; private final int port;
    private final List<WsClient> clients = new ArrayList<>();
    private volatile boolean running = false; private ServerSocket serverSocket; private Thread serverThread;
    private String lastBroadcast = ""; private long lastBroadcastMs = 0L;

    public DashboardWebServer(Context context, DashboardRepository repository, int port) {
        this.context = context.getApplicationContext(); this.repository = repository; this.port = port;
    }

    public void start() {
        if (running) return; running = true;
        serverThread = new Thread(this::runServer, "local-dashboard-server"); serverThread.start();
    }
    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch(Exception ignored) {}
        synchronized (clients) { for (WsClient c: clients) c.close(); clients.clear(); }
    }

    public void broadcastLatestData() {
        try {
            String text = repository.buildDashboardMessage().toString(); long now = System.currentTimeMillis();
            if (text.equals(lastBroadcast)) return;
            if (now - lastBroadcastMs < 120) return;
            lastBroadcast = text; lastBroadcastMs = now;
            synchronized (clients) { Iterator<WsClient> it = clients.iterator(); while (it.hasNext()) if (!it.next().send(text)) it.remove(); }
        } catch (Exception ignored) {}
    }

    private void runServer() {
        try { serverSocket = new ServerSocket(port); while (running) { Socket s = serverSocket.accept(); new Thread(() -> handleClient(s), "local-dashboard-client").start(); } }
        catch (Exception ignored) {}
    }

    private void handleClient(Socket socket) {
        try {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            String request = WebSocketUtils.readHttpHeader(in);
            if (request == null || request.length() == 0) { socket.close(); return; }
            if (request.startsWith("GET /ws")) { handleWebSocket(socket, request, in, out); return; }
            serveDashboardHtml(socket, out);
        } catch (Exception ignored) { WebSocketUtils.closeQuietly(socket); }
    }

    private void serveDashboardHtml(Socket socket, OutputStream out) {
        try {
            byte[] html = WebSocketUtils.readAssetBytes(context, "dashboard.html");
            String header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-cache\r\nContent-Length: " + html.length + "\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8)); out.write(html); out.flush();
        } catch (Exception ignored) {} finally { WebSocketUtils.closeQuietly(socket); }
    }

    private void handleWebSocket(Socket socket, String header, InputStream in, OutputStream out) {
        try {
            String key = WebSocketUtils.extractWebSocketKey(header); if (key == null) { WebSocketUtils.closeQuietly(socket); return; }
            String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + WebSocketUtils.websocketAccept(key) + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8)); out.flush();
            WsClient client = new WsClient(socket, out); synchronized (clients) { clients.add(client); }
            client.send(repository.buildDashboardMessage().toString());
            while (running && !socket.isClosed()) { String incoming = WebSocketUtils.readWebSocketTextFrame(in); if (incoming == null) break; }
        } catch (Exception ignored) {} finally { WebSocketUtils.closeQuietly(socket); removeDeadClients(); }
    }
    private void removeDeadClients() { synchronized (clients) { Iterator<WsClient> it = clients.iterator(); while (it.hasNext()) if (it.next().isClosed()) it.remove(); } }
}
