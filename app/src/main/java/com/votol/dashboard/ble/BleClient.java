package com.votol.dashboard.ble;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import com.votol.dashboard.core.DashboardRepository;
import com.votol.dashboard.debug.DebugLogger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** BLE client for Votol_BLE. Scans, connects, subscribes dashboard notify characteristic, and streams JSON. */
public class BleClient {
    private static final String TARGET_NAME="Votol_BLE";
    private static final String DASHBOARD_NOTIFY_UUID="beb5483e-36e1-4688-b7f5-ea07361b26a8";
    private static final String CCCD_UUID="00002902-0000-1000-8000-00805f9b34fb";
    private static final int BLE_REQUESTED_MTU=247;
    private static final long BLE_CONNECT_DELAY_MS=900, BLE_SCAN_TIMEOUT_MS=10000, BLE_RECONNECT_DELAY_MS=3000;

    private final Context context; private final Handler mainHandler; private final BlePacketAssembler assembler;
    private BluetoothLeScanner scanner; private BluetoothGatt gatt; private boolean scanning, connecting, connected, discoveryStarted; private BluetoothGattCharacteristic dashboardNotifyCharacteristic;

    public BleClient(Context context, Handler mainHandler, DashboardRepository repository) {
        this.context=context.getApplicationContext(); this.mainHandler=mainHandler;
        assembler = new BlePacketAssembler(json -> repository.updateFromEspJson(json, "ble"));
    }

    public void startScan() {
        if (connecting || connected || scanning) return;
        try {
            BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager==null) { scheduleReconnect(BLE_RECONNECT_DELAY_MS); return; }
            BluetoothAdapter adapter=manager.getAdapter();
            if (adapter==null || !adapter.isEnabled()) { scheduleReconnect(BLE_RECONNECT_DELAY_MS); return; }
            scanner=adapter.getBluetoothLeScanner(); if (scanner==null) { scheduleReconnect(BLE_RECONNECT_DELAY_MS); return; }
            scanning=true; DebugLogger.d("BLE SCAN START"); scanner.startScan(scanCallback);
            mainHandler.postDelayed(() -> { if (!scanning || connecting || connected) return; stopScanOnly(); scheduleReconnect(BLE_RECONNECT_DELAY_MS); }, BLE_SCAN_TIMEOUT_MS);
        } catch(Exception ignored) { scanning=false; scheduleReconnect(BLE_RECONNECT_DELAY_MS); }
    }

    public void stop() { stopScanOnly(); closeGatt(); connecting=false; connected=false; discoveryStarted=false; dashboardNotifyCharacteristic=null; assembler.clear(); }
    private void scheduleReconnect(long delayMs) { mainHandler.postDelayed(() -> { if (!connecting && !connected) startScan(); }, delayMs); }
    private void stopScanOnly() { try { if (scanner!=null && scanning) scanner.stopScan(scanCallback); } catch(Exception ignored) {} scanning=false; }
    private void closeGatt() { try { if (gatt!=null) { gatt.disconnect(); gatt.close(); } } catch(Exception ignored) {} gatt=null; }
    private void startServiceDiscovery(BluetoothGatt g) { if (discoveryStarted || g==null) return; discoveryStarted=true; try { g.discoverServices(); } catch(Exception ignored) { handleDisconnectedGatt(g); } }
    private void handleDisconnectedGatt(BluetoothGatt dg) { DebugLogger.d("BLE DISCONNECTED"); connecting=false; connected=false; discoveryStarted=false; dashboardNotifyCharacteristic=null; assembler.clear(); try { if (dg!=null) dg.close(); } catch(Exception ignored) {} if (gatt==dg) gatt=null; scheduleReconnect(BLE_RECONNECT_DELAY_MS); }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            try {
                if (result.getDevice()==null || result.getDevice().getName()==null) return;
                if (!TARGET_NAME.equals(result.getDevice().getName())) return;
                if (connecting || connected) return;
                stopScanOnly(); connecting=true; discoveryStarted=false; closeGatt();
                mainHandler.postDelayed(() -> { if (!connecting || connected) return; try { DebugLogger.d("BLE CONNECT GATT"); gatt=result.getDevice().connectGatt(context,false,gattCallback); if (gatt==null) { connecting=false; scheduleReconnect(BLE_RECONNECT_DELAY_MS); } } catch(Exception ignored) { connecting=false; scheduleReconnect(BLE_RECONNECT_DELAY_MS); } }, BLE_CONNECT_DELAY_MS);
            } catch(Exception ignored) { connecting=false; scheduleReconnect(BLE_RECONNECT_DELAY_MS); }
        }
        @Override public void onScanFailed(int errorCode) { scanning=false; connecting=false; DebugLogger.d("BLE SCAN FAILED "+errorCode); scheduleReconnect(BLE_RECONNECT_DELAY_MS); }
    };

    private void enableDashboardNotify(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        try {
            boolean notifyOk=g.setCharacteristicNotification(ch,true); DebugLogger.d("BLE ENABLE DASHBOARD NOTIFY result="+notifyOk);
            if (!notifyOk) { handleDisconnectedGatt(g); return; }
            BluetoothGattDescriptor descriptor=ch.getDescriptor(UUID.fromString(CCCD_UUID));
            if (descriptor==null) { DebugLogger.d("BLE DASHBOARD CCCD NULL"); handleDisconnectedGatt(g); return; }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean writeOk=g.writeDescriptor(descriptor); DebugLogger.d("BLE DASHBOARD CCCD WRITE result="+writeOk);
            if (!writeOk) handleDisconnectedGatt(g);
        } catch(Exception ignored) { handleDisconnectedGatt(g); }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) { handleDisconnectedGatt(g); return; }
            if (newState==BluetoothProfile.STATE_CONNECTED) { connecting=false; connected=true; discoveryStarted=false; DebugLogger.d("BLE CONNECTED"); try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch(Exception ignored) {} mainHandler.postDelayed(() -> { if (connected) { DebugLogger.d("BLE DISCOVER SERVICES"); startServiceDiscovery(g); } }, 300); }
            else if (newState==BluetoothProfile.STATE_DISCONNECTED) handleDisconnectedGatt(g);
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { handleDisconnectedGatt(g); return; }
            dashboardNotifyCharacteristic=null;
            for (BluetoothGattService service:g.getServices()) for (BluetoothGattCharacteristic ch:service.getCharacteristics()) { String uuid=ch.getUuid().toString().toLowerCase(Locale.US); DebugLogger.d("BLE CHAR "+uuid); if (DASHBOARD_NOTIFY_UUID.equals(uuid)) dashboardNotifyCharacteristic=ch; }
            if (dashboardNotifyCharacteristic==null) { DebugLogger.d("BLE DASHBOARD NOTIFY CHAR NOT FOUND"); handleDisconnectedGatt(g); return; }
            enableDashboardNotify(g,dashboardNotifyCharacteristic);
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) { DebugLogger.d("BLE DESCRIPTOR WRITE status="+status+" uuid="+descriptor.getUuid()); mainHandler.postDelayed(() -> { try { if (connected && Build.VERSION.SDK_INT>=Build.VERSION_CODES.LOLLIPOP) DebugLogger.d("BLE requestMtu247="+g.requestMtu(BLE_REQUESTED_MTU)); } catch(Exception ignored) {} }, 300); }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) { DebugLogger.d("BLE MTU mtu="+mtu+" status="+status); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) { try { if (ch==null) return; String uuid=ch.getUuid().toString().toLowerCase(Locale.US); if (!DASHBOARD_NOTIFY_UUID.equals(uuid)) return; byte[] value=ch.getValue(); if (value==null || value.length==0) return; assembler.append(new String(value, StandardCharsets.UTF_8)); } catch(Exception ignored) {} }
    };
}
