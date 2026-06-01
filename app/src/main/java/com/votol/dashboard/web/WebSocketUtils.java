package com.votol.dashboard.web;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/** Minimal HTTP/WebSocket helpers used by the local dashboard server and ESP32 WiFi client. */
public final class WebSocketUtils {
    private WebSocketUtils() {}

    public static String readHttpHeader(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int last4 = 0, b;
        while ((b = in.read()) != -1) {
            baos.write(b);
            last4 = ((last4 << 8) | b) & 0xffffffff;
            if (last4 == 0x0d0a0d0a) break;
            if (baos.size() > 8192) break;
        }
        return baos.toString("UTF-8");
    }

    public static byte[] readAssetBytes(Context context, String name) throws Exception {
        InputStream is = context.getAssets().open(name);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = is.read(buffer)) > 0) baos.write(buffer, 0, n);
            return baos.toByteArray();
        } finally { is.close(); }
    }

    public static String extractWebSocketKey(String header) {
        for (String line : header.split("\r\n")) {
            if (line.toLowerCase(Locale.US).startsWith("sec-websocket-key:")) {
                return line.substring(line.indexOf(":") + 1).trim();
            }
        }
        return null;
    }

    public static String websocketAccept(String key) throws Exception {
        String magic = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        byte[] hash = MessageDigest.getInstance("SHA-1").digest(magic.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    public static void writeWebSocketTextFrame(OutputStream out, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        out.write(0x81);
        if (payload.length <= 125) out.write(payload.length);
        else if (payload.length <= 65535) { out.write(126); out.write((payload.length >> 8) & 0xff); out.write(payload.length & 0xff); }
        else { out.write(127); long len = payload.length; for (int i = 7; i >= 0; i--) out.write((int)((len >> (8 * i)) & 0xff)); }
        out.write(payload); out.flush();
    }

    public static String readWebSocketTextFrame(InputStream in) throws Exception {
        int b1 = in.read(); if (b1 < 0) return null;
        int b2 = in.read(); if (b2 < 0) return null;
        int opcode = b1 & 0x0f; boolean masked = (b2 & 0x80) != 0; long len = b2 & 0x7f;
        if (len == 126) len = ((long)in.read() << 8) | in.read();
        else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
        byte[] mask = new byte[4];
        if (masked && in.read(mask) != 4) return null;
        byte[] payload = new byte[(int)len]; int offset = 0;
        while (offset < payload.length) { int r = in.read(payload, offset, payload.length - offset); if (r < 0) return null; offset += r; }
        if (masked) for (int i = 0; i < payload.length; i++) payload[i] = (byte)(payload[i] ^ mask[i % 4]);
        if (opcode == 0x8) return null;
        if (opcode != 0x1) return "";
        return new String(payload, StandardCharsets.UTF_8);
    }

    public static void closeQuietly(Socket socket) { try { if (socket != null) socket.close(); } catch(Exception ignored) {} }
    public static void sleepQuietly(long millis) { try { Thread.sleep(millis); } catch(Exception ignored) {} }
}
