package com.votol.dashboard.gps;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;

import com.votol.dashboard.core.DashboardRepository;
import com.votol.dashboard.debug.DebugLogger;

/**
 * GPS speed provider.
 */
public class GpsSpeedProvider {

    private final Context context;
    private final DashboardRepository repository;

    private LocationManager locationManager;

    private final LocationListener listener = new LocationListener() {

        @Override
        public void onLocationChanged(Location location) {

            try {

                if (location == null) {
                    return;
                }

                repository.updateGpsSpeed(
                        location.hasSpeed()
                                ? location.getSpeed() * 3.6
                                : 0
                );

            } catch (Exception e) {

                DebugLogger.d("GPS UPDATE ERROR " + e);

            }

        }

        @Override
        public void onProviderEnabled(String provider) {

            DebugLogger.d("GPS ENABLED");

        }

        @Override
        public void onProviderDisabled(String provider) {

            DebugLogger.d("GPS DISABLED");

            try {

                repository.setSpeedSource(
                        DashboardRepository.SPEED_SOURCE_VOTOL
                );

            } catch (Exception ignored) {
            }

        }

        @Override
        public void onStatusChanged(
                String provider,
                int status,
                Bundle extras
        ) {
        }

    };

    public GpsSpeedProvider(
            Context context,
            DashboardRepository repository
    ) {

        this.context = context;
        this.repository = repository;

    }

    public void start() {

        try {

            locationManager =
                    (LocationManager)
                            context.getSystemService(
                                    Context.LOCATION_SERVICE
                            );

            if (locationManager == null) {

                DebugLogger.d("GPS LocationManager NULL");

                return;

            }

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {

                DebugLogger.d("GPS Permission Denied");

                return;

            }

            boolean gpsEnabled =
                    locationManager.isProviderEnabled(
                            LocationManager.GPS_PROVIDER
                    );

            if (!gpsEnabled) {

                DebugLogger.d("GPS Provider Disabled");

                repository.setSpeedSource(
                        DashboardRepository.SPEED_SOURCE_VOTOL
                );

                return;

            }

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    100,
                    1,
                    listener
            );

            DebugLogger.d("GPS SPEED STARTED");

        }
        catch (SecurityException e) {

            DebugLogger.d("GPS SecurityException " + e);

        }
        catch (IllegalArgumentException e) {

            DebugLogger.d("GPS IllegalArgumentException " + e);

        }
        catch (Exception e) {

            DebugLogger.d("GPS ERROR " + e);

        }

    }

    public void stop() {

        try {

            if (locationManager != null) {

                locationManager.removeUpdates(listener);

            }

        } catch (Exception ignored) {
        }

    }

}