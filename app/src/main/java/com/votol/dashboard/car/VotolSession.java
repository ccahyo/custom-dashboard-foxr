package com.votol.dashboard.car;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import com.votol.dashboard.debug.DebugLogger;

/** Creates the first Android Auto screen. */
public class VotolSession extends Session {
    @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent) { DebugLogger.car(getCarContext(),"VotolSession.onCreateScreen()"); return new VotolDashboardScreen(getCarContext()); }
}
