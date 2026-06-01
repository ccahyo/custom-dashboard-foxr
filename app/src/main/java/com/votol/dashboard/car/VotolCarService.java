package com.votol.dashboard.car;

import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;
import com.votol.dashboard.debug.DebugLogger;

/** Android Auto entry point. If this is never called, the issue is app discovery, not dashboard rendering. */
public class VotolCarService extends CarAppService {
    @NonNull
    @Override
    public Session onCreateSession() {
        /*
        Toast.makeText(this,"VOTOL CarService onCreateSession",Toast.LENGTH_LONG).show();
        DebugLogger.car(this,"VotolCarService.onCreateSession()");
         */
        return new VotolSession();
    }
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        /*
        Toast.makeText(this,"VOTOL CarService createHostValidator",Toast.LENGTH_LONG).show();
        DebugLogger.car(this,"VotolCarService.createHostValidator()");
         */
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }
}
