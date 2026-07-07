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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serves dashboard.html, assets and local /ws endpoint. */
public class DashboardWebServer {

private final Context context;
private final DashboardRepository repository;
private final int port;

private final List<WsClient> clients = new ArrayList<>();

private final ExecutorService executor = Executors.newCachedThreadPool();

private volatile boolean running = false;
private ServerSocket serverSocket;
private Thread serverThread;

private String lastBroadcast = "";
private long lastBroadcastMs = 0L;

public DashboardWebServer(
        Context context,
        DashboardRepository repository,
        int port
) {
    this.context = context.getApplicationContext();
    this.repository = repository;
    this.port = port;
}

public void start() {
    if (running) return;

    running = true;

    serverThread =
            new Thread(
                    this::runServer,
                    "local-dashboard-server"
            );

    serverThread.start();
}

public void stop() {
    executor.shutdownNow();
    running = false;

    try {
        if (serverSocket != null) {
            serverSocket.close();
        }
    } catch (Exception ignored) {
    }

    synchronized (clients) {
        for (WsClient c : clients) {
            c.close();
        }
        clients.clear();
    }
}

public void broadcastLatestData() {
    try {

        String text =
                repository
                        .buildDashboardMessage();

        if (text == null || text.isEmpty()) {
            return;
        }

        final long now = System.currentTimeMillis();

        if (clients.isEmpty()) {
            return;
        }

        if (text.equals(lastBroadcast)) {
            return;
        }

        if (now - lastBroadcastMs < 120) {
            return;
        }

        lastBroadcast = text;
        lastBroadcastMs = now;

        WsClient[] snapshot;

        synchronized (clients) {
            snapshot = clients.toArray(new WsClient[0]);
        }

        for (WsClient client : snapshot) {

            if (!client.send(text)) {

                synchronized (clients) {
                    clients.remove(client);
                }

            }

        }

    } catch (Exception ignored) {
    }
}

private void runServer() {
    try {

        serverSocket =
                new ServerSocket(port);

        while (running) {

            Socket socket =
                    serverSocket.accept();

            executor.execute(() -> handleClient(socket));
        }

    } catch (Exception ignored) {
    }
}

private void handleClient(Socket socket) {

    try {

        BufferedInputStream in =
                new BufferedInputStream(
                        socket.getInputStream()
                );

        OutputStream out =
                new BufferedOutputStream(
                        socket.getOutputStream()
                );

        String request =
                WebSocketUtils.readHttpHeader(in);

        if (request == null || request.isEmpty()) {
            socket.close();
            return;
        }

        if (request.startsWith("GET /ws")) {
            handleWebSocket(
                    socket,
                    request,
                    in,
                    out
            );
            return;
        }

        String path = extractPath(request);

        if ("/".equals(path)
                || path.length() == 0) {

            serveDashboardHtml(
                    socket,
                    out
            );

            return;
        }

        serveAsset(
                socket,
                out,
                path
        );

    } catch (Exception ignored) {

        WebSocketUtils.closeQuietly(socket);
    }
}

private String extractPath(String request) {

    try {

        String[] parts =
                request.split(" ");

        if (parts.length < 2) {
            return "/";
        }

        return parts[1];

    } catch (Exception e) {

        return "/";
    }
}

private void serveDashboardHtml(
        Socket socket,
        OutputStream out
) {

    try {

        byte[] html =
                WebSocketUtils.readAssetBytes(
                        context,
                        "dashboard.html"
                );

        String header =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Content-Length: " +
                html.length +
                "\r\n\r\n";

        out.write(
                header.getBytes(
                        StandardCharsets.UTF_8
                )
        );

        out.write(html);
        out.flush();

    } catch (Exception ignored) {

    } finally {

        WebSocketUtils.closeQuietly(socket);
    }
}

private void serveAsset(
        Socket socket,
        OutputStream out,
        String path
) {

    try {

        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        byte[] data =
                WebSocketUtils.readAssetBytes(
                        context,
                        path
                );

        String contentType =
                "application/octet-stream";

        if (path.endsWith(".ttf")) {
            contentType = "font/ttf";
        } else if (path.endsWith(".woff")) {
            contentType = "font/woff";
        } else if (path.endsWith(".woff2")) {
            contentType = "font/woff2";
        } else if (path.endsWith(".css")) {
            contentType = "text/css";
        } else if (path.endsWith(".js")) {
            contentType = "application/javascript";
        } else if (path.endsWith(".png")) {
            contentType = "image/png";
        } else if (path.endsWith(".jpg")
                || path.endsWith(".jpeg")) {
            contentType = "image/jpeg";
        } else if (path.endsWith(".svg")) {
            contentType = "image/svg+xml";
        }

        String header =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: " +
                contentType +
                "\r\n" +
                "Content-Length: " +
                data.length +
                "\r\n" +
                "Cache-Control: public,max-age=31536000\r\n" +
                "\r\n";

        out.write(
                header.getBytes(
                        StandardCharsets.UTF_8
                )
        );

        out.write(data);
        out.flush();

    } catch (Exception ignored) {

    } finally {

        WebSocketUtils.closeQuietly(socket);
    }
}

private void handleWebSocket(
        Socket socket,
        String header,
        InputStream in,
        OutputStream out
) {

    try {

        String key =
                WebSocketUtils
                        .extractWebSocketKey(header);

        if (key == null) {

            WebSocketUtils.closeQuietly(socket);
            return;
        }

        String response =
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " +
                WebSocketUtils.websocketAccept(key) +
                "\r\n\r\n";

        out.write(
                response.getBytes(
                        StandardCharsets.UTF_8
                )
        );

        out.flush();

        WsClient client =
                new WsClient(socket, out);

        synchronized (clients) {
            clients.add(client);
        }

        String snapshot =
                repository.buildDashboardMessage();

        client.send(snapshot);

        while (running && !socket.isClosed()) {

            String incoming =
                    WebSocketUtils
                            .readWebSocketTextFrame(in);

            if (incoming == null) {
                break;
            }
        }

    } catch (Exception ignored) {

    } finally {

        WebSocketUtils.closeQuietly(socket);
        removeDeadClients();
    }
}

private void removeDeadClients() {

    synchronized (clients) {

        Iterator<WsClient> it =
                clients.iterator();

        while (it.hasNext()) {

            if (it.next().isClosed()) {
                it.remove();
            }
        }
    }
}

}
