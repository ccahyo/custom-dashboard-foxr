package com.votol.dashboard.web;

import java.io.OutputStream;
import java.net.Socket;

class WsClient {
    private final Socket socket;
    private final OutputStream out;
    WsClient(Socket socket, OutputStream out) { this.socket = socket; this.out = out; }
    synchronized boolean send(String text) {
        try { if (socket.isClosed()) return false; WebSocketUtils.writeWebSocketTextFrame(out, text); return true; }
        catch (Exception ignored) { close(); return false; }
    }
    boolean isClosed() { return socket.isClosed(); }
    void close() { WebSocketUtils.closeQuietly(socket); }
}
