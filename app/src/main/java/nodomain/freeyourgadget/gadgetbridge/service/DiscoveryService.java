package nodomain.freeyourgadget.gadgetbridge.service;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.location.Geocoder;
import android.location.Address;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import static nodomain.freeyourgadget.gadgetbridge.util.GB.NOTIFICATION_CHANNEL_ID;
import android.content.Intent;
import nodomain.freeyourgadget.gadgetbridge.activities.DebugActivity;
import android.app.PendingIntent;
import android.os.CountDownTimer;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Calendar;

import java.io.Writer;
import java.io.StringWriter;
import java.io.PrintWriter;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsActivity;
import nodomain.freeyourgadget.gadgetbridge.adapter.DeviceCandidateAdapter;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;
import nodomain.freeyourgadget.gadgetbridge.util.BondingInterface;
import nodomain.freeyourgadget.gadgetbridge.util.BondingUtil;
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

import static nodomain.freeyourgadget.gadgetbridge.util.GB.toast;
import nodomain.freeyourgadget.gadgetbridge.externalevents.NotificationListener;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.os.Process;

import nodomain.freeyourgadget.gadgetbridge.activities.DiscoveryActivity;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import static nodomain.freeyourgadget.gadgetbridge.util.GB.NOTIFICATION_CHANNEL_ID;
import android.telephony.SmsManager;
import android.media.AudioManager;
import android.net.Uri;  

import nodomain.freeyourgadget.gadgetbridge.devices.gemtec.LocationService;
import nodomain.freeyourgadget.gadgetbridge.devices.gemtec.FallTimerService;
import static nodomain.freeyourgadget.gadgetbridge.devices.gemtec.FallTimerService.EMERGENCY_CALLING;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.File;
import android.os.Environment;

public class DiscoveryService extends Service
{
    // private final DiscoveryActivity discoveryActivity = new DiscoveryActivity();
    private static NotificationManager notificationManager;
    public static final String CHANNEL_ID = "ForegroundServiceChannel";
    public static final int Fall_NOTIFICATION_ID = 2112;
    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryService.class);
    private static final long SCAN_DURATION = 30000; // 30s changed to 2s
    public static String scanRecorddata;
    public static String devicename;
    private final Handler handler = new Handler();
    private final ArrayList<GBDeviceCandidate> deviceCandidates = new ArrayList<>();
    private ScanCallback newBLEScanCallback = null;
    private SmsManager smsManager = SmsManager.getDefault();
    private LocationService locationService;
    private FallTimerService fallTimerService = new FallTimerService();
    private NotificationCompat.Builder builder;
    private NotificationManagerCompat notificationManagerCompat;
    CountDownTimer countDownTimer;
    private boolean isCountdownTimerRunning = false;
    List<Address> addresses;
    // private GetCurrentLocation getCurrentLocation = new GetCurrentLocation();
    private FusedLocationProviderClient fusedLocationClient;
    private static String batteryVoltage = "-";
    private static float impact_to_fall_time;
    private static float press_settle_time;

    /**
     * Use old BLE scanning
     **/
    private boolean oldBleScanning = false;
    /**
     * If already bonded devices are to be ignored when scanning
     */
    private boolean ignoreBonded = true;
    private ProgressBar bluetoothProgress;
    private ProgressBar bluetoothLEProgress;
    private DeviceCandidateAdapter deviceCandidateAdapter;   

    private BufferedWriter bWriter;
    private File sdCardFile = new File(Environment.getExternalStorageDirectory() + "/FallCounter.txt");

    private final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            //logMessageContent(scanRecord);
            handleDeviceFound(device, (short) rssi);
        }
    };
    private BluetoothAdapter adapter;
    private Button startButton;
    private Scanning isScanning = Scanning.SCANNING_OFF;

    private static final int NOTIFICATION_ID = 1;

    private final Runnable stopRunnable = new Runnable() {
        @Override
        public void run() {
            if (isScanning == Scanning.SCANNING_BT_NEXT_BLE) {
                // Start the next scan in the series
                stopDiscovery();
                startDiscovery(Scanning.SCANNING_BLE);
            } else {
                stopDiscovery();
            }
        }
    };
    private BluetoothDevice bluetoothTarget;

    public void logMessageContent(byte[] value) {
        if (value != null) {
            LOG.warn("DATA: " + GB.hexdump(value, 0, value.length));
        }
    }

    private final Handler BGScanHandler = new Handler();
    final Runnable BGDiscoveryLoop = new Runnable() {
        public void run() {
            BGDiscoveryLoop();
        }
    };   
    final Runnable BGDiscoveryStart = new Runnable() {
        public void run() {
            BGDiscoveryStart();
        }
    };   

    // @RequiresApi(Build.VERSION_CODES.O)
    // @Override
    // public void onActivityResult(int requestCode, int resultCode, Intent data) {
    //     super.onActivityResult(requestCode, resultCode, data);
    //     BondingUtil.handleActivityResult(this, requestCode, resultCode, data);
    // }


    private GBDeviceCandidate getCandidateFromMAC(BluetoothDevice device) {
        for (GBDeviceCandidate candidate : deviceCandidates) {
            if (candidate.getMacAddress().equals(device.getAddress())) {
                return candidate;
            }
        }
        LOG.warn(String.format("This shouldn't happen unless the list somehow emptied itself, device MAC: %1$s", device.getAddress()));
        return null;
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private ScanCallback getScanCallback() {
        if (newBLEScanCallback != null) {
            return newBLEScanCallback;
        }

        newBLEScanCallback = new ScanCallback() {
            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                try {
                    ScanRecord scanRecord = result.getScanRecord();
                    ParcelUuid[] uuids = null;
                    if (scanRecord != null) {
                        // logMessageContent(scanRecord.getBytes());
                        if(GBApplication.GEMTEC_FALL_COUNTER == -1 && !GBApplication.GEMTEC_MAC_ADDRESS.equals(result.getDevice().getAddress())){   //New device connected
                            stopDiscovery();
                            handler.removeCallbacksAndMessages(null);   //Stop background service from starting again
                            BGDiscoveryStart(); //Restart background service with new MAC filter
                        }
                        else {
                            devicename = result.getDevice().getName();
                            // scanRecorddata = scanRecord.toString();
                            byte[] scanAdvertisement = scanRecord.getBytes();
                            if(devicename.equals("InfiniTime")) {
                                byte[] manuData = scanRecord.getManufacturerSpecificData(89);
                                if(GBApplication.GEMTEC_FALL_COUNTER == -1) {
                                    GBApplication.GEMTEC_FALL_COUNTER = manuData[1];
                                    sendFallCounterToGui(GBApplication.GEMTEC_FALL_COUNTER);
                                }
                                else if(GBApplication.GEMTEC_FALL_COUNTER != manuData[1]){
                                    GBApplication.GEMTEC_FALL_COUNTER = manuData[1];
                                    toast(DiscoveryService.this, "Emergency activated", Toast.LENGTH_LONG, GB.WARN);
                                    
                                    if (GBApplication.getGBPrefs().getEnableSms())
                                        sendSMS(devicename, scanAdvertisement);

                                    if (GBApplication.getGBPrefs().getEnableCalls())                       
                                        makeEmergencyCall();
                                }
                            }
                            else if(devicename.equals("GEMTEC")) {
                                if (scanAdvertisement[7]==2 || scanAdvertisement[7]==3 || scanAdvertisement[7]==4)
                                {
                                    int fallData;
                                    if (GBApplication.getGBPrefs().getGemtecDebugMode())  {
                                        fallData = 22;      //location for fall count in the advertisement packet
                                        upadteDebugGUI(scanAdvertisement, result.getRssi());
                                    }
                                    else
                                        fallData = 14;

                                    int fallcounter = scanAdvertisement[fallData];
                                    if(GBApplication.GEMTEC_FALL_COUNTER == -1)
                                    {
                                        GBApplication.GEMTEC_FALL_COUNTER = fallcounter;
                                        sendFallCounterToGui(fallcounter);
                                    }
                                    else if(GBApplication.GEMTEC_FALL_COUNTER != fallcounter)
                                    {
                                        sendFallCounterToGui(fallcounter);
                                        if (fallcounter == 0)
                                        {
                                            GBApplication.GEMTEC_FALL_COUNTER = 0;
                                        }
                                        else
                                        {
                                            GBApplication.GEMTEC_FALL_COUNTER = fallcounter;
                                            toast(DiscoveryService.this, "Fall detected", Toast.LENGTH_LONG, GB.WARN);
                                            float minglast = scanAdvertisement[19];
                                            minglast = minglast/10;
                                            float maxglast = scanAdvertisement[20];
                                            maxglast = maxglast/10;
                                            float press_diff = scanAdvertisement[17];
                                            press_diff = press_diff/10;
                                            float diff1 = scanAdvertisement[14];
                                            diff1 = diff1/10;
                                            float diff2 = scanAdvertisement[15];
                                            diff2 = diff2/10;
                                            float diff3 = scanAdvertisement[16];
                                            diff3 = diff3/10;
                                            if (GBApplication.isFileLoggingEnabled()) {
                                                saveToFile("Fall #" + fallcounter + " at " + Calendar.getInstance().getTime());
                                                saveToFile("G-value Free fall: " + String.valueOf(minglast));
                                                saveToFile("G-value Impact: " + String.valueOf(maxglast));
                                                // saveToFile("G-value Impact2: " + String.valueOf(scanAdvertisement[18]));
                                                saveToFile("Pressure Difference: " + String.valueOf(scanAdvertisement[21]));
                                                saveToFile("Initial Pressure Difference: " + String.valueOf(press_diff));
                                                saveToFile("Diff1: " + String.valueOf(diff1));
                                                saveToFile("Diff2: " + String.valueOf(diff2));
                                                // saveToFile("Diff3: " + String.valueOf(diff3));
                                                saveToFile("pressure settle time:: " + String.valueOf(diff3));
                                                // saveToFile("impact - fall_time: " + String.valueOf(impact_to_fall_time));
                                                // saveToFile("pressure settle time: " + String.valueOf(press_settle_time));
                                            }
                                            // stopDiscovery();
                                            if (GBApplication.getGBPrefs().getEnableSms()) {
                                                sendSMS(devicename, scanAdvertisement);
                                            }
                                            
                                            if (GBApplication.getGBPrefs().getEnableCalls())
                                                createNotification();                       
                                                // makeEmergencyCall();
                                        }

                                    }
                                }
                                else if (scanAdvertisement[7]==1)
                                {
                                    float battery_voltage = Byte.toUnsignedInt(scanAdvertisement[16]) | Byte.toUnsignedInt(scanAdvertisement[15]) <<8;
                                    battery_voltage = battery_voltage/1000;
                                    batteryVoltage = String.valueOf(battery_voltage);
                                    // sendBatteryVoltageToGui(battery_voltage);
                                }
                                // else if (scanAdvertisement[7]==6)
                                // {
                                //     impact_to_fall_time = Byte.toUnsignedInt(scanAdvertisement[8]);
                                //     impact_to_fall_time = impact_to_fall_time/10;
                                //     press_settle_time = Byte.toUnsignedInt(scanAdvertisement[9]);
                                //     press_settle_time = press_settle_time/10;
                                //     toast(DiscoveryService.this, "Impact to Fall start time: " + String.valueOf(impact_to_fall_time)
                                //                                 + "\nPressure settling time: " + String.valueOf(press_settle_time)
                                //                                 + "\nImpact #: " + String.valueOf(scanAdvertisement[10])
                                //                                 + "\nPressure #: " + String.valueOf(scanAdvertisement[11])
                                //                                 , Toast.LENGTH_LONG, GB.WARN);
                                // }
                            }
                        }
                        // List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
                        // if (serviceUuids != null) {
                        //     uuids = serviceUuids.toArray(new ParcelUuid[0]);
                        // }
                    }
                    LOG.warn(result.getDevice().getName() + ": " +
                            ((scanRecord != null) ? scanRecord.getBytes().length : -1));
                    // handleDeviceFound(result.getDevice(), (short) result.getRssi(), uuids);
                } catch (NullPointerException e) {
                    LOG.warn("Error handling scan result", e);
                    Writer writer = new StringWriter();
                    e.printStackTrace(new PrintWriter(writer));
                    toast(DiscoveryService.this, writer.toString(), Toast.LENGTH_LONG, GB.WARN);
                }
            }
        };

        return newBLEScanCallback;
    }    

    private void createNotification() {

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "name", importance);
            channel.setDescription("description");
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        Intent notificationIntent = new Intent(this, FallTimerService.class);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, notificationIntent, 0);

        builder = new NotificationCompat.Builder(DiscoveryService.this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nc_systems)
            .setContentTitle("Fall detected")
            .setContentText("Call emergency contact number in...")
            .addAction(0, "Do not call", pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX);

        notificationManagerCompat = NotificationManagerCompat.from(DiscoveryService.this);
        // notificationId is a unique int for each notification that you must define
        notificationManagerCompat.notify(Fall_NOTIFICATION_ID, builder.build());

        // try{
        //     fallTimerService.start();
        // } catch (Exception e) {
        //     Writer writer = new StringWriter();
        //     e.printStackTrace(new PrintWriter(writer));
        //     toast(DiscoveryService.this, writer.toString(), Toast.LENGTH_LONG, GB.WARN);
        // }

        if (isCountdownTimerRunning) {
            countDownTimer.cancel();
            isCountdownTimerRunning = false;
        }
        
        countDownTimer = new CountDownTimer(30000, 1000) {

            public void onTick(long millisUntilFinished) {
                isCountdownTimerRunning = true;
                if(FallTimerService.EMERGENCY_CALLING == true) {
                    builder.setContentText("Calling emergency contact number in..." + String.valueOf(millisUntilFinished/1000));
                    notificationManagerCompat.notify(Fall_NOTIFICATION_ID, builder.build());
                } 
            }

            public void onFinish() {
                try {
                isCountdownTimerRunning = false;

                NotificationManager notifManager= (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                notifManager.cancel(Fall_NOTIFICATION_ID);

                if (GBApplication.getGBPrefs().getEnableCalls() && FallTimerService.EMERGENCY_CALLING == true)                       
                    makeEmergencyCall();
                } catch (Exception e) {
                    Writer writer = new StringWriter();
                    e.printStackTrace(new PrintWriter(writer));
                    toast(DiscoveryService.this, writer.toString(), Toast.LENGTH_LONG, GB.WARN);
                }
            }

        }.start();

        FallTimerService.EMERGENCY_CALLING = true;

    }


    private void sendSMS(String message, byte[] scanAdvertisement) {
        String myTextMessage = "";
        String linkToLocation = "https://www.google.com/maps/search/?api=1&query=" + Double.toString(locationService.myLat) + "," + Double.toString(locationService.myLon);
        Geocoder geocoder;
        geocoder = new Geocoder(DiscoveryService.this);
        String address;
        try {
            addresses = geocoder.getFromLocation(locationService.myLat, locationService.myLon, 1); // Here 1 represent max location result to returned, by documents it recommended 1 to 5
            address = addresses.get(0).getAddressLine(0); // If any additional address line present than only, check with max available address lines by getMaxAddressLineIndex()
        }
        catch(Exception e) {
            address = "Address unavailable";
            // Writer writer = new StringWriter();
            // e.printStackTrace(new PrintWriter(writer));
            // toast(DiscoveryService.this, writer.toString(), Toast.LENGTH_LONG, GB.WARN);
        }

        if (message.equals("InfiniTime")) {
            myTextMessage = "Emergency at " + address + 
            ". \n" + linkToLocation + 
            "\nTotal calls: " + GBApplication.GEMTEC_FALL_COUNTER;
        }
        else if(message.equals("GEMTEC")){            
            if (GBApplication.getGBPrefs().getGemtecDebugMode()) {
                // Debug values
                float min_g = scanAdvertisement[19];
                min_g = min_g / 10;
                float max_g = scanAdvertisement[20];
                max_g = max_g / 10;
                int pressure_difference = scanAdvertisement[21];
                float press_diff = scanAdvertisement[17];
                press_diff = press_diff/10;
                float diff1 = scanAdvertisement[14];
                diff1 = diff1/10;
                float diff2 = scanAdvertisement[15];
                diff2 = diff2/10;
                float diff3 = scanAdvertisement[16];
                diff3 = diff3/10;
                // float movement = scanAdvertisement[18];
                // movement = movement / 10;
                myTextMessage = "Fall detected near " + address + 
                // ".\nmin_g: " + min_g + "\nmax_g: " + max_g + "\nPressure: " + pressure_difference + " Pa" +
                // "\nInitial Pressure Difference: " + press_diff +
                // "\ndiff1: " + diff1 + 
                // "\ndiff2: " + diff2 + 
                // "\ndiff3: " + diff3 + 
                // "\nMovement: " + movement +
                "\nTotal falls: " + GBApplication.GEMTEC_FALL_COUNTER;
            }
            else {
                myTextMessage = "Fall detected near " + address + 
                    ". \n" + linkToLocation + 
                    "\nTotal falls: " + GBApplication.GEMTEC_FALL_COUNTER;
            }
        }


        for (int i=0; i < GBApplication.GEMTEC_PHONE_NUMBER.size(); i++) {
            smsManager.sendTextMessage(GBApplication.GEMTEC_PHONE_NUMBER.get(i), null, myTextMessage, null, null);
        }
    }

    private void makeEmergencyCall() {
        if (GBApplication.GEMTEC_PHONE_NUMBER.size()>0) {
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:" + GBApplication.GEMTEC_PHONE_NUMBER.get(0)));
            callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try{
            startActivity(callIntent);
            } catch (Exception e) {
                Writer writer = new StringWriter();
                e.printStackTrace(new PrintWriter(writer));
                toast(DiscoveryService.this, writer.toString(), Toast.LENGTH_LONG, GB.WARN);
            }
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    setSpeakeron();
                }
            }, 1000);            
        }
        else {
            toast(DiscoveryService.this, "Emergency contact list empty", Toast.LENGTH_LONG, GB.WARN);
        }
    }

    private void setSpeakeron() {
        try{
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_CALL);
        audioManager.setSpeakerphoneOn(true);
        } catch (Exception e) {
            Writer writer = new StringWriter();
            e.printStackTrace(new PrintWriter(writer));
            toast(DiscoveryService.this, writer.toString(), Toast.LENGTH_LONG, GB.WARN);            
        }
    }

    private void handleDeviceFound(BluetoothDevice device, short rssi) {
        if (device.getName() != null) {
            if (handleDeviceFound(device, rssi, null)) {
                LOG.info("found supported device " + device.getName() + " without scanning services, skipping service scan.");
                return;
            }
        }
        ParcelUuid[] uuids = device.getUuids();
        if (uuids == null) {
            if (device.fetchUuidsWithSdp()) {
                return;
            }
        }

        handleDeviceFound(device, rssi, uuids);
    }

    private boolean handleDeviceFound(BluetoothDevice device, short rssi, ParcelUuid[] uuids) {
        LOG.debug("found device: " + device.getName() + ", " + device.getAddress());
        if (LOG.isDebugEnabled()) {
            if (uuids != null && uuids.length > 0) {
                for (ParcelUuid uuid : uuids) {
                    LOG.debug("  supports uuid: " + uuid.toString());
                }
            }
        }

        if (device.getBondState() == BluetoothDevice.BOND_BONDED && ignoreBonded) {
            return true; // Ignore already bonded devices
        }

        GBDeviceCandidate candidate = new GBDeviceCandidate(device, rssi, uuids);
        DeviceType deviceType = DeviceHelper.getInstance().getSupportedType(candidate);
        if (deviceType.isSupported()) {
            candidate.setDeviceType(deviceType);
            LOG.info("Recognized supported device: " + candidate);
            int index = deviceCandidates.indexOf(candidate);
            if (index >= 0) {
                deviceCandidates.set(index, candidate); // replace
            } else {
                deviceCandidates.add(candidate);
            }
            deviceCandidateAdapter.notifyDataSetChanged();
            return true;
        }
        return false;
    }

    private void sendFallCounterToGui(int val) {
        Intent intent = new Intent();
        intent.setAction(GBApplication.ACTION_GEMTEC);
        intent.putExtra("FallCounter", String.valueOf(val));
        LocalBroadcastManager.getInstance(DiscoveryService.this).sendBroadcast(intent);
    }

    // private void sendBatteryVoltageToGui(float val) {
    //     Intent intent = new Intent();
    //     intent.setAction(GBApplication.ACTION_GEMTEC);
    //     intent.putExtra("BatteryVoltage", String.valueOf(val));
    //     LocalBroadcastManager.getInstance(DiscoveryService.this).sendBroadcast(intent);
    // }

    private void upadteDebugGUI(byte[] scanAdvertisement, int rssi) {
        Intent intent = new Intent();
        intent.setAction(GBApplication.ACTION_GEMTEC);
        Bundle bundle = new Bundle();
        float ming = scanAdvertisement[8];
        ming = ming/10;
        float maxg = scanAdvertisement[9];
        maxg = maxg/10;
        float movementlast = scanAdvertisement[18];
        // movementlast = movementlast/10;
        float minglast = scanAdvertisement[19];
        minglast = minglast/10;
        float maxglast = scanAdvertisement[20];
        maxglast = maxglast/10;
        float press_diff = scanAdvertisement[17];
        press_diff = press_diff/10;
        float diff1 = scanAdvertisement[14];
        diff1 = diff1/10;
        float diff2 = scanAdvertisement[15];
        diff2 = diff2/10;
        float diff3 = scanAdvertisement[16];
        diff3 = diff3/10;
        long BLEcounter = Byte.toUnsignedInt(scanAdvertisement[11]) | Byte.toUnsignedInt(scanAdvertisement[12]) <<8 | 
                            Byte.toUnsignedInt(scanAdvertisement[13]) <<16 
                            // | Byte.toUnsignedInt(scanAdvertisement[14]) <<24 | 
                            // Byte.toUnsignedInt(scanAdvertisement[15]) <<32 | Byte.toUnsignedInt(scanAdvertisement[16]) <<40
                            ;
        double rssi_distance = Math.pow(10,((-79-Float.valueOf(rssi))/10/4));
        
        bundle.putString("ming", String.valueOf(ming) + "  (<0.6)");
        bundle.putString("maxg", String.valueOf(maxg) + "  (>2.5)");
        bundle.putString("BLEcounter", String.valueOf(BLEcounter));
        bundle.putString("minglast", String.valueOf(minglast));
        bundle.putString("maxglast", String.valueOf(maxglast));
        bundle.putString("presslast", String.valueOf(scanAdvertisement[21] + "  (>4, <12)"));
        bundle.putString("rssi", String.valueOf(rssi));
        bundle.putString("distance", String.format("%.2f", rssi_distance));
        bundle.putString("vibration", String.valueOf(Byte.toUnsignedInt(scanAdvertisement[10])));
        bundle.putString("beeper", String.valueOf(Byte.toUnsignedInt(scanAdvertisement[18])));
        bundle.putString("press_diff", String.valueOf(press_diff) + "  (>3.5 Pa)");
        bundle.putString("diff1", String.valueOf(diff1) + "  (<2.5 Pa)");
        bundle.putString("diff2", String.valueOf(diff2) + "  (>1.5 Pa)");
        bundle.putString("diff3", String.valueOf(diff3) + "  (<7)");
        bundle.putString("BatteryVoltage", batteryVoltage);
        intent.putExtras(bundle);
        LocalBroadcastManager.getInstance(DiscoveryService.this).sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureCollectorRunning();
        try{
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
                } catch(Exception e){
            toast(DiscoveryService.this, "ensureBluetoothReady failed", Toast.LENGTH_LONG, GB.WARN);
        }
        // toast(DiscoveryService.this, "Created Service", Toast.LENGTH_LONG, GB.WARN);
        // startDiscovery(Scanning.SCANNING_BLE);
    }

    private void ensureCollectorRunning() {
        ComponentName collectorComponent = new ComponentName(this, NotificationListener.class);
        LOG.info("ensureCollectorRunning collectorComponent: " + collectorComponent);
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        boolean collectorRunning = false;
        List<ActivityManager.RunningServiceInfo> runningServices = manager.getRunningServices(Integer.MAX_VALUE);
        if (runningServices == null) {
            LOG.info("ensureCollectorRunning() runningServices is NULL");
            return;
        }
        for (ActivityManager.RunningServiceInfo service : runningServices) {
            if (service.service.equals(collectorComponent)) {
                LOG.warn("ensureCollectorRunning service - pid: " + service.pid + ", currentPID: " + Process.myPid() + ", clientPackage: " + service.clientPackage + ", clientCount: " + service.clientCount
                        + ", clientLabel: " + ((service.clientLabel == 0) ? "0" : "(" + getResources().getString(service.clientLabel) + ")"));

                if (service.pid == Process.myPid() /*&& service.clientCount > 0 && !TextUtils.isEmpty(service.clientPackage)*/) {
                    collectorRunning = true;
                }
            }
        }
        if (collectorRunning) {
            LOG.debug("ensureCollectorRunning: collector is running");
            return;
        }
        LOG.debug("ensureCollectorRunning: collector not running, reviving...");
        toggleNotificationListenerService();
    }

    private void toggleNotificationListenerService() {
        LOG.debug("toggleNotificationListenerService() called");
        ComponentName thisComponent = new ComponentName(this, NotificationListener.class);
        PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(thisComponent, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(thisComponent, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

    }    

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(GB.NOTIFICATION_ID, GB.createNotification(getString(R.string.gemtec_running), this));
        BGScanHandler.postDelayed(BGDiscoveryStart, 500);
        // toast(DiscoveryService.this, "Starting Discovery", Toast.LENGTH_LONG, GB.WARN);
        // startDiscovery(Scanning.SCANNING_BLE);
        startService(new Intent(this, FallTimerService.class));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
    
    public void BGDiscoveryLoop() {
        BGScanHandler.postDelayed(BGDiscoveryStart, 500);
    }

    public void BGDiscoveryStart() {
        if (GBApplication.GEMTEC_MAC_ADDRESS == "") {  // Mac address not set, keep toggling
            BGScanHandler.postDelayed(BGDiscoveryLoop, 500);                            
        }
        else {
            checkAndRequestLocationPermission();
            startDiscovery(Scanning.SCANNING_BLE);      //Start discovery for 30 sec
            BGScanHandler.postDelayed(BGDiscoveryLoop, 30000); // After 30 sec call toggle function
        }
    }

    private void stopAllDiscovery() {
        try {
            // stopBTDiscovery();
            if (oldBleScanning) {
                stopOldBLEDiscovery();
            } else {
                if (GBApplication.isRunningLollipopOrLater()) {
                    stopBLEDiscovery();
                }
            }
        } catch (Exception e) {
            LOG.warn("Error stopping discovery", e);
        }
    }

    private void startDiscovery(Scanning what) {
        if (isScanning()) {
            LOG.warn("Not starting discovery, because already scanning.");
            return;
        }

        LOG.info("Starting discovery: " + what);
        try {
        if (ensureBluetoothReady() && isScanning == Scanning.SCANNING_OFF) {
            if (what == Scanning.SCANNING_BT || what == Scanning.SCANNING_BT_NEXT_BLE) {
                // startBTDiscovery(what);
            } else if (what == Scanning.SCANNING_BLE && GB.supportsBluetoothLE()) {
                if (oldBleScanning || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    startOldBTLEDiscovery();
                } else {
                    startBTLEDiscovery();
                }
            } else {
                discoveryFinished();
            }
        } else {
            discoveryFinished();
        }
        } catch(Exception e){
            toast(DiscoveryService.this, "ensureBluetoothReady failed", Toast.LENGTH_LONG, GB.WARN);
        }
    }

    private void stopDiscovery() {
        LOG.info("Stopping discovery");
        if (isScanning()) {
            Scanning wasScanning = isScanning;
            if (wasScanning == Scanning.SCANNING_BT || wasScanning == Scanning.SCANNING_BT_NEXT_BLE) {
                // stopBTDiscovery();
            } else if (wasScanning == Scanning.SCANNING_BLE) {
                if (oldBleScanning || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    stopOldBLEDiscovery();
                } else {
                    stopBLEDiscovery();
                }
            }

            discoveryFinished();
            handler.removeMessages(0, stopRunnable);
        } else {
            discoveryFinished();
        }
    }

    private boolean isScanning() {
        return isScanning != Scanning.SCANNING_OFF;
    }

    private void startOldBTLEDiscovery() {
        LOG.info("Starting old BLE discovery");

        handler.removeMessages(0, stopRunnable);
        handler.sendMessageDelayed(getPostMessage(stopRunnable), SCAN_DURATION);
        if (adapter.startLeScan(leScanCallback)) {
            LOG.info("Old Bluetooth LE scan started successfully");
            setIsScanning(Scanning.SCANNING_BLE);
        } else {
            LOG.info("Old Bluetooth LE scan starting failed");
            setIsScanning(Scanning.SCANNING_OFF);
        }
    }

    private void stopOldBLEDiscovery() {
        if (adapter != null) {
            adapter.stopLeScan(leScanCallback);
            LOG.info("Stopped old BLE discovery");
        }

        setIsScanning(Scanning.SCANNING_OFF);
    }

    /* New BTLE Discovery uses startScan (List<ScanFilter> filters,
                                         ScanSettings settings,
                                         ScanCallback callback) */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private void startBTLEDiscovery() {
        // LOG.info("Starting BLE discovery");
        try{
        handler.removeMessages(0, stopRunnable);
        handler.sendMessageDelayed(getPostMessage(stopRunnable), SCAN_DURATION);
        } catch (Exception e) {
            toast(DiscoveryService.this, "handler failed", Toast.LENGTH_LONG, GB.WARN);
        }
        // Filters being non-null would be a very good idea with background scan, but in this case,
        // not really required.
        
        ArrayList<ScanFilter> filters = new ArrayList<>();
        try{
            filters.add(new ScanFilter.Builder()
            .setDeviceAddress(GBApplication.GEMTEC_MAC_ADDRESS)
            .build());
            // toast(DiscoveryService.this, GBApplication.GEMTEC_MAC_ADDRESS, Toast.LENGTH_LONG, GB.WARN);
        } catch (Exception e) {
            toast(DiscoveryService.this, "filter failed", Toast.LENGTH_LONG, GB.WARN);
        }
        // try{
        // filters.add(new ScanFilter.Builder()
        // .setDeviceName("GEMTEC")
        // .build());
        // } catch (Exception e) {
        //     toast(DiscoveryService.this, "filter failed", Toast.LENGTH_LONG, GB.WARN);
        // }
        try{
        adapter.getBluetoothLeScanner().startScan(filters, getScanSettings(), getScanCallback());
        } catch (Exception e) {
            toast(DiscoveryService.this, "getBluetoothLeScanner failed", Toast.LENGTH_LONG, GB.WARN);
        }
        LOG.debug("Bluetooth LE discovery started successfully");
        setIsScanning(Scanning.SCANNING_BLE);
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private void stopBLEDiscovery() {
        if (adapter == null) {
            return;
        }

        BluetoothLeScanner bluetoothLeScanner = adapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            LOG.warn("Could not get BluetoothLeScanner()!");
            return;
        }
        if (newBLEScanCallback == null) {
            LOG.warn("newLeScanCallback == null!");
            return;
        }
        try {
            bluetoothLeScanner.stopScan(newBLEScanCallback);
        } catch (NullPointerException e) {
            LOG.warn("Internal NullPointerException when stopping the scan!");
            return;
        }

        LOG.debug("Stopped BLE discovery");
        setIsScanning(Scanning.SCANNING_OFF);
        // toast(DiscoveryService.this, "Stopped discovery", Toast.LENGTH_LONG, GB.WARN);
    }

    private void discoveryFinished() {
        if (isScanning != Scanning.SCANNING_OFF) {
            LOG.warn("Scan was not properly stopped: " + isScanning);
        }

        setIsScanning(Scanning.SCANNING_OFF);
    }

    private void setIsScanning(Scanning to) {
        this.isScanning = to;
    }

    private void bluetoothStateChanged(int newState) {
        if (newState == BluetoothAdapter.STATE_ON) {
            this.adapter = BluetoothAdapter.getDefaultAdapter();
            // startButton.setEnabled(true);
        } else {
            this.adapter = null;
        }

    }

    private boolean checkBluetoothAvailable() {
        BluetoothManager bluetoothService = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bluetoothService == null) {
            LOG.warn("No bluetooth service available");
            this.adapter = null;
            return false;
        }
        BluetoothAdapter adapter = bluetoothService.getAdapter();
        if (adapter == null) {
            LOG.warn("No bluetooth adapter available");
            this.adapter = null;
            return false;
        }
        if (!adapter.isEnabled()) {
            LOG.warn("Bluetooth not enabled");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            this.adapter = null;
            return false;
        }
        this.adapter = adapter;
        return true;
    }

    private boolean ensureBluetoothReady() {
        boolean available = checkBluetoothAvailable();
        // startButton.setEnabled(available);
        if (available) {
            adapter.cancelDiscovery();
            // must not return the result of cancelDiscovery()
            // appears to return false when currently not scanning
            return true;
        }
        return false;
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private ScanSettings getScanSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new ScanSettings.Builder()
                    .setCallbackType(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setMatchMode(android.bluetooth.le.ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setPhy(android.bluetooth.le.ScanSettings.PHY_LE_ALL_SUPPORTED)
                    .setNumOfMatches(android.bluetooth.le.ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .build();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new ScanSettings.Builder()
                    .setCallbackType(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setMatchMode(android.bluetooth.le.ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(android.bluetooth.le.ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .build();
        } else {
            return new ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
        }
    }

    private List<ScanFilter> getScanFilters() {
        List<ScanFilter> allFilters = new ArrayList<>();
        for (DeviceCoordinator coordinator : DeviceHelper.getInstance().getAllCoordinators()) {
            allFilters.addAll(coordinator.createBLEScanFilters());
        }
        return allFilters;
    }

    private Message getPostMessage(Runnable runnable) {
        Message message = Message.obtain(handler, runnable);
        message.obj = runnable;
        return message;
    }

    private void checkAndRequestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            LOG.error("No permission to access coarse location!");
            toast(DiscoveryService.this, getString(R.string.error_no_location_access), Toast.LENGTH_SHORT, GB.ERROR);
            // ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 0);
        }
        if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            LOG.error("No permission to access fine location!");
            toast(DiscoveryService.this, getString(R.string.error_no_location_access), Toast.LENGTH_SHORT, GB.ERROR);
            // ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                LOG.error("No permission to access background location!");
                toast(DiscoveryService.this, getString(R.string.error_no_location_access), Toast.LENGTH_SHORT, GB.ERROR);
                // ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 0);
            }
        }
    }


    public BluetoothDevice getCurrentTarget() {
        return this.bluetoothTarget;
    }

    private enum Scanning {
        /**
         * Regular Bluetooth scan
         */
        SCANNING_BT,
        /**
         * Regular Bluetooth scan but when ends, start BLE scan
         */
        SCANNING_BT_NEXT_BLE,
        /**
         * Regular BLE scan
         */
        SCANNING_BLE,
        /**
         * Scanning has ended or hasn't been started
         */
        SCANNING_OFF
    }
    
    public void saveToFile(String text2save) {
        try{
            bWriter = new BufferedWriter(new FileWriter(sdCardFile, true));
            bWriter.write(text2save);
            bWriter.newLine();
            bWriter.flush();
            bWriter.close();
        }catch(Exception e){
            Writer writer = new StringWriter();
            e.printStackTrace(new PrintWriter(writer));
            toast(this, writer.toString(), Toast.LENGTH_LONG, GB.WARN);
        }
    }
}