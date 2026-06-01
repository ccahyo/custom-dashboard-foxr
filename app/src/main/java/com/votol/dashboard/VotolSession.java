package com.votol.dashboard;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

import com.votol.dashboard.debug.DebugLogger;

public class VotolSession extends Session {

    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        /*
        DebugLogger.car(
                getCarContext(),
                "VotolSession.onCreateScreen()"
        );
         */

        return new VotolDashboardScreen(getCarContext());
    }
}
