package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.widget.Toast;
import android.widget.TextView;
import nodomain.freeyourgadget.gadgetbridge.BuildConfig;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Objects;

import java.io.Writer;
import java.io.StringWriter;
import java.io.PrintWriter;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class GemtecDebugActivity extends AbstractGBActivity implements GBActivity {

    private TextView GemtecView;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (Objects.requireNonNull(action)) {
                case GBApplication.ACTION_GEMTEC:
                    updateGemtecGUI(intent);                    
                    break;                            
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug_info);
        if (GBApplication.GEMTEC_FALL_COUNTER >= 0) {
            updateFallcounter(String.valueOf(GBApplication.GEMTEC_FALL_COUNTER));
        }
        IntentFilter filterLocal = new IntentFilter();
        filterLocal.addAction(GBApplication.ACTION_GEMTEC);
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, filterLocal);

    }

    public void updateGemtecGUI(Intent intent) {
        if (intent.getStringExtra("FallCounter") != null) {
            updateFallcounter(intent.getStringExtra("FallCounter"));
        }
        try {
            Bundle extras = intent.getExtras();

            GemtecView = findViewById(R.id.min_g_val);
            GemtecView.setText(extras.getString("ming"));

            GemtecView = findViewById(R.id.max_g_val);
            GemtecView.setText(extras.getString("maxg"));

            GemtecView = findViewById(R.id.min_g_last_val);
            GemtecView.setText(extras.getString("minglast"));

            GemtecView = findViewById(R.id.max_g_last_val);
            GemtecView.setText(extras.getString("maxglast"));

            GemtecView = findViewById(R.id.press_diff_val);
            GemtecView.setText(extras.getString("presslast"));

            GemtecView = findViewById(R.id.BLE_counter_val);
            GemtecView.setText(extras.getString("BLEcounter"));     

            GemtecView = findViewById(R.id.RSSI_val);
            GemtecView.setText(extras.getString("rssi"));   

            GemtecView = findViewById(R.id.RSSI_distance_val);
            GemtecView.setText(extras.getString("distance"));   

            GemtecView = findViewById(R.id.vibration_counter_val);
            GemtecView.setText(extras.getString("vibration")); 

            GemtecView = findViewById(R.id.beeper_counter_val);
            GemtecView.setText(extras.getString("beeper")); 

            GemtecView = findViewById(R.id.init_press_diff_val);
            GemtecView.setText(extras.getString("press_diff")); 

            GemtecView = findViewById(R.id.diff1_val);
            GemtecView.setText(extras.getString("diff1")); 

            GemtecView = findViewById(R.id.diff2_val);
            GemtecView.setText(extras.getString("diff2")); 

            GemtecView = findViewById(R.id.diff3_val);
            GemtecView.setText(extras.getString("diff3")); 

            GemtecView = findViewById(R.id.battery_voltage_val);
            GemtecView.setText(extras.getString("BatteryVoltage")); 
        }
        catch(Exception e) {
            Writer writer = new StringWriter();
            e.printStackTrace(new PrintWriter(writer));
            GB.toast(getBaseContext(), writer.toString(), Toast.LENGTH_LONG, GB.ERROR);
        }
    }

    public void updateFallcounter(String val) {
        GemtecView = findViewById(R.id.fall_counter);
        GemtecView.setText(val);
    }

}
