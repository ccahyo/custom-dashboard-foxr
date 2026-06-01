package com.votol.dashboard.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import com.votol.dashboard.core.DashboardRepository;
import com.votol.dashboard.debug.DebugLogger;
import org.json.JSONObject;
import java.util.Locale;

/** Android Auto dashboard screen using text rows and Unicode progress bars. */
public class VotolDashboardScreen extends Screen {
    public VotolDashboardScreen(@NonNull CarContext carContext) { super(carContext); DebugLogger.car(carContext,"VotolDashboardScreen.constructor()"); }
    @NonNull @Override public Template onGetTemplate() {
        DebugLogger.car(getCarContext(),"VotolDashboardScreen.onGetTemplate()");
        JSONObject data=DashboardRepository.getInstance().getSnapshot();
        String mode=data.optString("mode","DRIVE"); int soc=data.optInt("soc",0); double volts=data.optDouble("volts",0); double amps=data.optDouble("amps",0); double speed=data.optDouble("gpsSpeed",data.optDouble("speed",0)); int rpm=data.optInt("rpm",0);
        JSONObject temps=data.optJSONObject("temps"); int motor=temps!=null?temps.optInt("motor",0):0; int ctrl=temps!=null?temps.optInt("ctrl",0):0; int batt=temps!=null?temps.optInt("batt",0):0;
        Pane pane=new Pane.Builder()
                .addRow(row("Mode",mode))
                .addRow(row("Battery",bar(soc,100)+" "+soc+"%"))
                .addRow(row("Voltage",oneDecimal(volts)+" V"))
                .addRow(row("Current",bar(Math.abs(amps),60)+" "+oneDecimal(amps)+" A"))
                .addRow(row("Speed",bar(speed,120)+" "+Math.round(speed)+" km/h"))
                .addRow(row("RPM",rpm+" rpm"))
                .addRow(row("BLDC Temp",bar(motor,100)+" "+motor+"°C"))
                .addRow(row("CTRL Temp",bar(ctrl,100)+" "+ctrl+"°C"))
                .addRow(row("BATT Temp",bar(batt,100)+" "+batt+"°C"))
                .build();
        return new PaneTemplate.Builder(pane).setTitle("FOX-R Dashboard").setHeaderAction(Action.APP_ICON).build();
    }
    private Row row(String title,String value){ return new Row.Builder().setTitle(title).addText(value).build(); }
    private String bar(double value,double max){ int total=10; int filled=(int)Math.round(Math.max(0,Math.min(value,max))/max*total); StringBuilder sb=new StringBuilder(); for(int i=0;i<total;i++) sb.append(i<filled?'█':'░'); return sb.toString(); }
    private String oneDecimal(double value){ return String.format(Locale.US,"%.1f",value); }
}
