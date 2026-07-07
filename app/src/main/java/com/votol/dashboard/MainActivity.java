package com.votol.dashboard;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

//import androidx.car.app.connection.CarConnection;

import com.votol.dashboard.ble.BleClient;
import com.votol.dashboard.core.DashboardRepository;
import com.votol.dashboard.debug.DebugLogger;
import com.votol.dashboard.gps.GpsSpeedProvider;
import com.votol.dashboard.web.DashboardWebServer;
import com.votol.dashboard.wifi.EspWifiClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Main Android runtime for VOTOL Dashboard.
 *
 * This Activity intentionally only handles lifecycle and WebView setup.
 * BLE, WiFi, GPS, state management, logging, web server, and Android Auto
 * are split into dedicated classes for maintainability.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 100;
    private static final int LOCAL_PORT = 8080;
    private static final String LOCAL_URL = "http://127.0.0.1:" + LOCAL_PORT + "/";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebView dashboardView;
    private DashboardRepository repository;
    private DashboardWebServer dashboardServer;
    private EspWifiClient espWifiClient;
    private BleClient bleClient;
    private GpsSpeedProvider gpsSpeedProvider;
    private boolean isGPSEnabled = true;
    private boolean transportLocked = false;
    private boolean tryingBle = true;

    private final Runnable transportSwitcher = new Runnable() {
        @Override
        public void run() {
            if (transportLocked)
                return;

            if (tryingBle) {
                if (espWifiClient != null)
                    espWifiClient.stop();

                if (bleClient != null)
                    bleClient.startScan();
            } else {
                if (bleClient != null)
                    bleClient.stop();

                if (espWifiClient != null)
                    espWifiClient.start();
            }
            tryingBle = !tryingBle;

            mainHandler.postDelayed(this,10000);
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DebugLogger.init(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enableImmersiveFullscreen();

        repository = DashboardRepository.getInstance();
        repository.reset();

        initializeWebView();
        requestRuntimePermissions();
//        observeCarConnection();

//        Toast.makeText(this, DebugLogger.getCarDebugStatus(this), Toast.LENGTH_LONG).show();

        dashboardServer = new DashboardWebServer(this, repository, LOCAL_PORT);
        repository.setListener(() -> {
            if (dashboardServer != null) dashboardServer.broadcastLatestData();
        });
        dashboardServer.start();

        if ( isGPSEnabled ) {
            gpsSpeedProvider = new GpsSpeedProvider(this, repository);
            gpsSpeedProvider.start();
        }
/*
        bleClient = new BleClient(this, mainHandler, repository);
        mainHandler.postDelayed(() -> {
            if (bleClient != null) bleClient.startScan();
        }, 1000);

        espWifiClient = new EspWifiClient(repository);
        espWifiClient.start();
*/
        bleClient = new BleClient(this, mainHandler, repository);
        bleClient.setConnectionListener(new BleClient.ConnectionListener() {

            @Override
            public void onConnected() {
                transportLocked = true;
                if (espWifiClient != null)
                    espWifiClient.stop();
            }

            @Override
            public void onDisconnected() {
                transportLocked = false;
                mainHandler.removeCallbacks(transportSwitcher);
                mainHandler.post(transportSwitcher);
            }
        });

        espWifiClient = new EspWifiClient(repository);
        espWifiClient.setConnectionListener(new EspWifiClient.ConnectionListener() {

            @Override
            public void onConnected() {
                transportLocked = true;
                if (bleClient != null)
                    bleClient.stop();
            }

            @Override
            public void onDisconnected() {
                transportLocked = false;
                mainHandler.removeCallbacks(transportSwitcher);
                mainHandler.post(transportSwitcher);
            }
        });

        mainHandler.post(transportSwitcher);

        dashboardView.loadUrl(LOCAL_URL);
        dashboardView.setKeepScreenOn(true);
    }

    @Override
    protected void onResume() {
        super.onResume();

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        if (dashboardView != null) {
            dashboardView.setKeepScreenOn(true);
        }

        enableImmersiveFullscreen();
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

        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            mainHandler.removeCallbacks(transportSwitcher);
            mainHandler.post(transportSwitcher);
            if (isGPSEnabled && gpsSpeedProvider != null) gpsSpeedProvider.start();
        }
    }

    /** Android Auto screen reads dashboard data through this stable static method. */
    public static JSONObject getLatestDataSnapshot() {
        return DashboardRepository.getInstance().getSnapshot();
    }

    private void enableImmersiveFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            enableImmersiveFullscreen();
        }
    }

/*
    private void observeCarConnection() {
        try {
            new CarConnection(this)
                    .getType()
                    .observe(
                            this,
                            connectionType -> {
                                String message =
                                        connectionType >
                                                CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                                                ? "Connected to a car head unit"
                                                : "Not Connected to a car head unit";
                                android.widget.Toast.makeText(
                                        MainActivity.this,
                                        message,
                                        android.widget.Toast.LENGTH_LONG
                                ).show();
                                com.votol.dashboard.debug.DebugLogger.d(message);
                            }
                    );
        } catch (Exception e) {
            android.widget.Toast.makeText(
                    this,
                    "CarConnection error: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG
            ).show();
        }
    }
*/

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(transportSwitcher);

        if (dashboardServer != null) dashboardServer.stop();
        if (espWifiClient != null) espWifiClient.stop();
        if (bleClient != null) bleClient.stop();
        if (isGPSEnabled && gpsSpeedProvider != null) gpsSpeedProvider.stop();

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        DebugLogger.close();
        super.onDestroy();
    }
}
