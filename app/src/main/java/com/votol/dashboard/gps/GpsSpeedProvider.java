package com.votol.dashboard.gps;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import androidx.core.app.ActivityCompat;
import com.votol.dashboard.core.DashboardRepository;
import com.votol.dashboard.debug.DebugLogger;

/** Optional GPS speed provider. Writes gpsSpeed in km/h to repository. */
public class GpsSpeedProvider {
    private final Context context; private final DashboardRepository repository; private LocationManager locationManager;
    private final LocationListener listener = new LocationListener() { @Override public void onLocationChanged(Location location) { try { if (location==null) return; repository.updateGpsSpeed(location.hasSpeed()? location.getSpeed()*3.6:0); } catch(Exception ignored) {} } };
    public GpsSpeedProvider(Context context, DashboardRepository repository) { this.context=context; this.repository=repository; }
    public void start() {
        try { locationManager=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE); if (locationManager==null) return; if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return; locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,listener); DebugLogger.d("GPS SPEED STARTED"); }
        catch(Exception e) { DebugLogger.d("GPS SPEED ERROR "+e.getMessage()); }
    }
    public void stop() { try { if (locationManager!=null) locationManager.removeUpdates(listener); } catch(Exception ignored) {} }
}
