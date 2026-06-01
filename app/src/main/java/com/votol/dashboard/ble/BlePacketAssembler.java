package com.votol.dashboard.ble;

/** Reassembles chunked BLE strings into complete JSON objects. */
public class BlePacketAssembler {
    public interface Callback { void onJson(String json); }
    private final Callback callback; private final StringBuilder buffer = new StringBuilder();
    public BlePacketAssembler(Callback callback) { this.callback = callback; }
    public synchronized void append(String chunk) {
        if (chunk == null || chunk.length() == 0) return;
        buffer.append(chunk);
        if (buffer.length() > 4096) buffer.delete(0, buffer.length() - 2048);
        while (true) {
            String text = buffer.toString(); int start = text.indexOf("{"); int end = findCompleteJsonEnd(text, start);
            if (start < 0) { buffer.setLength(0); return; }
            if (end < 0) { if (start > 0) buffer.delete(0, start); return; }
            String json = text.substring(start, end + 1); buffer.delete(0, end + 1); callback.onJson(json);
        }
    }
    public synchronized void clear() { buffer.setLength(0); }
    private int findCompleteJsonEnd(String text, int start) {
        if (start < 0) return -1; int depth = 0; boolean inString = false, escaped = false;
        for (int i=start;i<text.length();i++) { char c=text.charAt(i); if (escaped) { escaped=false; continue; } if (c=='\\') { escaped=true; continue; } if (c=='\"') { inString=!inString; continue; } if (inString) continue; if (c=='{') depth++; else if (c=='}') { depth--; if (depth==0) return i; } }
        return -1;
    }
}
