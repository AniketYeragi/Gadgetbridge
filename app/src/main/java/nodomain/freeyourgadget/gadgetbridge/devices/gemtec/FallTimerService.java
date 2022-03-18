package nodomain.freeyourgadget.gadgetbridge.devices.gemtec;

import android.app.Service;
import android.os.CountDownTimer;
import android.os.Bundle;
import android.os.IBinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import android.widget.Toast;
import static nodomain.freeyourgadget.gadgetbridge.util.GB.toast;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.Writer;
import java.io.StringWriter;
import java.io.PrintWriter;

import nodomain.freeyourgadget.gadgetbridge.service.DiscoveryService;

public class FallTimerService extends Service {
    int time = 30 * 1000; // 30 seconds
    int interval = 1000; // 1 second
    public static boolean EMERGENCY_CALLING;

    CountDownTimer countDownTimer = new CountDownTimer(time, interval) {
            public void onTick(long millisUntilFinished) {
                toast(FallTimerService.this, "Tik", Toast.LENGTH_LONG, GB.WARN);
                // NotificationCompat.Builder builder = new NotificationCompat.Builder(FallTimerService.this, "ForegroundServiceChannel")
                //     .setContentText("Call emergency contact number in..." + String.valueOf(millisUntilFinished));
                // NotificationManagerCompat notificationManager = NotificationManagerCompat.from(FallTimerService.this);
                // // notificationId is a unique int for each notification that you must define
                // notificationManager.notify(2112, builder.build());
            }
            public void onFinish() {
            }
        };


    @Override
    public void onCreate() {
        super.onCreate();

        // countDownTimer 
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        clearNotification();

        return START_NOT_STICKY;
    }

    public void clearNotification() {

        EMERGENCY_CALLING = false;

        NotificationManager notifManager= (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        notifManager.cancel(DiscoveryService.Fall_NOTIFICATION_ID);

        Intent it = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        this.sendBroadcast(it);
    }
    
    public void start() {
        countDownTimer.start();
    }

    public void stop() {
        countDownTimer.cancel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}