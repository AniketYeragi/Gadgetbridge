/*  Copyright (C) 2015-2020 Andreas Shimokawa, boun, Carsten Pfeiffer, Daniel
    Dakhno, Daniele Gobbetti, JohnnySun, jonnsoft, Lem Dulfo, Taavi Eomäe,
    Uwe Hermann

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities;

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

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import android.preference.PreferenceManager;

import java.io.Writer;
import java.io.StringWriter;
import java.io.PrintWriter;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import static nodomain.freeyourgadget.gadgetbridge.util.GB.toast;


public class GemtecListenActivity extends AbstractGBActivity implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener, BondingInterface {
    private static final Logger LOG = LoggerFactory.getLogger(GemtecListenActivity.class);
    private static final long SCAN_DURATION = 3000; // 30s changed to 3s
    public static String scanRecorddata;
    public static String devicename;
    private final Handler handler = new Handler();
    private final ArrayList<GBDeviceCandidate> deviceCandidates = new ArrayList<>();
    private static SharedPreferences sharedPrefs;
    private static GBPrefs gbPrefs;
    private ScanCallback newBLEScanCallback = null;
    /**
     * Use old BLE scanning
     **/
    private boolean oldBleScanning = false;
    /**
     * If already bonded devices are to be ignored when scanning
     */
    private boolean ignoreBonded = true;
    // private ProgressBar bluetoothProgress;
    private ProgressBar bluetoothLEProgress;
    private DeviceCandidateAdapter deviceCandidateAdapter;
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
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (Objects.requireNonNull(intent.getAction())) {
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED: {
                    LOG.debug("ACTION_DISCOVERY_STARTED");
                    if (isScanning != Scanning.SCANNING_BLE) {
                        if (isScanning != Scanning.SCANNING_BT_NEXT_BLE) {
                            setIsScanning(Scanning.SCANNING_BT);
                        }
                    }
                    break;
                }
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED: {
                    LOG.debug("ACTION_DISCOVERY_FINISHED");
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            // Continue with LE scan, if available
                            if (isScanning == Scanning.SCANNING_BT || isScanning == Scanning.SCANNING_BT_NEXT_BLE) {
                                checkAndRequestLocationPermission();
                                stopDiscovery();
                                startDiscovery(Scanning.SCANNING_BLE);
                            }
                        }
                    });
                    break;
                }
                case BluetoothAdapter.ACTION_STATE_CHANGED: {
                    LOG.debug("ACTION_STATE_CHANGED ");
                    bluetoothStateChanged(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF));
                    break;
                }
                case BluetoothDevice.ACTION_FOUND: {
                    LOG.debug("ACTION_FOUND");
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    handleDeviceFound(device, intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, GBDevice.RSSI_UNKNOWN));
                    break;
                }
                case BluetoothDevice.ACTION_UUID: {
                    LOG.debug("ACTION_UUID");
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, GBDevice.RSSI_UNKNOWN);
                    Parcelable[] uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                    ParcelUuid[] uuids2 = AndroidUtils.toParcelUuids(uuids);
                    handleDeviceFound(device, rssi, uuids2);
                    break;
                }
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                    LOG.debug("ACTION_BOND_STATE_CHANGED");
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                        LOG.debug(String.format(Locale.ENGLISH, "Bond state: %d", bondState));

                        if (bondState == BluetoothDevice.BOND_BONDED) {
                            BondingUtil.handleDeviceBonded((BondingInterface) context, getCandidateFromMAC(device));
                        }
                    }
                    break;
                }
            }
        }
    };
    private BluetoothDevice bluetoothTarget;

    public void logMessageContent(byte[] value) {
        if (value != null) {
            LOG.warn("DATA: " + GB.hexdump(value, 0, value.length));
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        BondingUtil.handleActivityResult(this, requestCode, resultCode, data);
    }


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
                        //logMessageContent(scanRecord.getBytes());
                        devicename = result.getDevice().getName();
                        scanRecorddata = scanRecord.toString();
                        // toast(GemtecListenActivity.this, scanRecorddata, Toast.LENGTH_LONG, GB.WARN);
                        List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
                        if (serviceUuids != null) {
                            uuids = serviceUuids.toArray(new ParcelUuid[0]);
                        }
                    }
                    LOG.warn(result.getDevice().getName() + ": " +
                            ((scanRecord != null) ? scanRecord.getBytes().length : -1));
                    handleDeviceFound(result.getDevice(), (short) result.getRssi(), uuids);
                } catch (NullPointerException e) {
                    LOG.warn("Error handling scan result", e);
                }
            }
        };

        return newBLEScanCallback;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Prefs prefs = GBApplication.getPrefs();
        ignoreBonded = prefs.getBoolean("ignore_bonded_devices", true);

        oldBleScanning = prefs.getBoolean("disable_new_ble_scanning", false);
        if (oldBleScanning) {
            LOG.info("New BLE scanning disabled via settings, using old method");
        }

        setContentView(R.layout.activity_gemtec_listen);
        startButton = findViewById(R.id.discovery_start);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onStartButtonClick(startButton);
            }
        });

        // bluetoothProgress = findViewById(R.id.discovery_progressbar);
        // bluetoothProgress.setProgress(0);
        // bluetoothProgress.setIndeterminate(true);
        // bluetoothProgress.setVisibility(View.GONE);
        ListView deviceCandidatesView = findViewById(R.id.discovery_device_candidates_list);

        bluetoothLEProgress = findViewById(R.id.discovery_ble_progressbar);
        bluetoothLEProgress.setProgress(0);
        bluetoothLEProgress.setIndeterminate(true);
        bluetoothLEProgress.setVisibility(View.GONE);

        deviceCandidateAdapter = new DeviceCandidateAdapter(this, deviceCandidates);
        deviceCandidatesView.setAdapter(deviceCandidateAdapter);
        deviceCandidatesView.setOnItemClickListener(this);
        deviceCandidatesView.setOnItemLongClickListener(this);

        registerBroadcastReceivers();

        checkAndRequestLocationPermission();

        // startDiscovery(Scanning.SCANNING_BT_NEXT_BLE);
        startDiscovery(Scanning.SCANNING_BLE);
    }

    public void onStartButtonClick(View button) {
        LOG.debug("Start button clicked");
        if (isScanning()) {
            stopDiscovery();
        } else {
            if (GB.supportsBluetoothLE()) {
                // startDiscovery(Scanning.SCANNING_BT_NEXT_BLE);
                startDiscovery(Scanning.SCANNING_BLE);
            } else {
                startDiscovery(Scanning.SCANNING_BT);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList("deviceCandidates", deviceCandidates);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        ArrayList<Parcelable> restoredCandidates = savedInstanceState.getParcelableArrayList("deviceCandidates");
        if (restoredCandidates != null) {
            deviceCandidates.clear();
            for (Parcelable p : restoredCandidates) {
                deviceCandidates.add((GBDeviceCandidate) p);
            }
        }
    }

    @Override
    protected void onDestroy() {
        unregisterBroadcastReceivers();
        stopAllDiscovery();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        unregisterBroadcastReceivers();
        stopAllDiscovery();
        super.onStop();
    }

    @Override
    protected void onPause() {
        unregisterBroadcastReceivers();
        stopAllDiscovery();
        super.onPause();
    }

    @Override
    protected void onResume() {
        registerBroadcastReceivers();
        super.onResume();
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

    public void startDiscovery() {
        // toast(GemtecListenActivity.this, "Entered Activity", Toast.LENGTH_LONG, GB.WARN);
        // startDiscovery(Scanning.SCANNING_BLE);
    }

    private void startDiscovery(Scanning what) {
        if (isScanning()) {
            LOG.warn("Not starting discovery, because already scanning.");
            return;
        }

        LOG.info("Starting discovery: " + what);
        startButton.setText(getString(R.string.discovery_stop_scanning));
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
                toast(GemtecListenActivity.this, getString(R.string.discovery_enable_bluetooth), Toast.LENGTH_SHORT, GB.ERROR);
            }
        } else {
            discoveryFinished();
            toast(GemtecListenActivity.this, getString(R.string.discovery_enable_bluetooth), Toast.LENGTH_SHORT, GB.ERROR);
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
            bluetoothLEProgress.setVisibility(View.VISIBLE);
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
        LOG.info("Starting BLE discovery");

        handler.removeMessages(0, stopRunnable);
        handler.sendMessageDelayed(getPostMessage(stopRunnable), SCAN_DURATION);

        // Filters being non-null would be a very good idea with background scan, but in this case,
        // not really required.
        ArrayList<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
        .setDeviceName(GBApplication.GEMTEC_SCAN_FILTER)
        .build());
        filters.add(new ScanFilter.Builder()
        .setDeviceName("InfiniTime")
        .build());

        adapter.getBluetoothLeScanner().startScan(filters, getScanSettings(), getScanCallback());

        LOG.debug("Bluetooth LE discovery started successfully");
        bluetoothLEProgress.setVisibility(View.VISIBLE);
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
    }

    private void discoveryFinished() {
        if (isScanning != Scanning.SCANNING_OFF) {
            LOG.warn("Scan was not properly stopped: " + isScanning);
        }

        setIsScanning(Scanning.SCANNING_OFF);
    }

    private void setIsScanning(Scanning to) {
        this.isScanning = to;

        if (isScanning == Scanning.SCANNING_OFF) {
            startButton.setText(getString(R.string.discovery_start_scanning));
            // bluetoothProgress.setVisibility(View.GONE);
            bluetoothLEProgress.setVisibility(View.GONE);
        } else {
            startButton.setText(getString(R.string.discovery_stop_scanning));
        }
    }

    private void bluetoothStateChanged(int newState) {
        if (newState == BluetoothAdapter.STATE_ON) {
            this.adapter = BluetoothAdapter.getDefaultAdapter();
            startButton.setEnabled(true);
        } else {
            this.adapter = null;
            startButton.setEnabled(false);
            // bluetoothProgress.setVisibility(View.GONE);
            bluetoothLEProgress.setVisibility(View.GONE);
        }

        discoveryFinished();
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
        startButton.setEnabled(available);
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
            toast(GemtecListenActivity.this, getString(R.string.error_no_location_access), Toast.LENGTH_SHORT, GB.ERROR);
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 0);
        }
        if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            LOG.error("No permission to access fine location!");
            toast(GemtecListenActivity.this, getString(R.string.error_no_location_access), Toast.LENGTH_SHORT, GB.ERROR);
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                LOG.error("No permission to access background location!");
                toast(GemtecListenActivity.this, getString(R.string.error_no_location_access), Toast.LENGTH_SHORT, GB.ERROR);
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 0);
            }
        }

        LocationManager locationManager = (LocationManager) GemtecListenActivity.this.getSystemService(Context.LOCATION_SERVICE);
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                // Do nothing
                LOG.debug("Some location provider is enabled, assuming location is enabled");
            } else {
                toast(GemtecListenActivity.this, getString(R.string.require_location_provider), Toast.LENGTH_LONG, GB.ERROR);
                GemtecListenActivity.this.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                // We can't be sure location was enabled, cancel scan start and wait for new user action
                toast(GemtecListenActivity.this, getString(R.string.error_location_enabled_mandatory), Toast.LENGTH_SHORT, GB.ERROR);
                return;
            }
        } catch (Exception ex) {
            LOG.error("Exception when checking location status: ", ex);
        }
        LOG.error("Problem with permissions, returning");
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        GBDeviceCandidate deviceCandidate = deviceCandidates.get(position);
        if (deviceCandidate == null) {
            LOG.error("Device candidate clicked, but item not found");
            return;
        }

        stopDiscovery();

        if (!GBApplication.GEMTEC_MAC_ADDRESS.equals(deviceCandidate.getMacAddress())) {
            GBApplication.GEMTEC_MAC_ADDRESS = deviceCandidate.getMacAddress();
            
            sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor editor = sharedPrefs.edit();
            editor.putString(GBPrefs.MAC_ADDRESS, GBApplication.GEMTEC_MAC_ADDRESS);
            editor.apply();

            GBApplication.GEMTEC_FALL_COUNTER = -1;
            
            Intent intent = new Intent();
            intent.setAction(GBApplication.ACTION_GEMTEC);
            intent.putExtra("MAC", GBApplication.GEMTEC_MAC_ADDRESS);
            LocalBroadcastManager.getInstance(GemtecListenActivity.this).sendBroadcast(intent);
        }

        if (GBApplication.GEMTEC_PHONE_NUMBER.size() == 0) {
            startActivity(new Intent(this, ManageContactsActivity.class));
        }
        finish();
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> adapterView, View view, int position, long id) {
        GBDeviceCandidate deviceCandidate = deviceCandidates.get(position);
        if (deviceCandidate == null) {
            LOG.error("Device candidate clicked, but item not found");
            return true;
        }

        DeviceCoordinator coordinator = DeviceHelper.getInstance().getCoordinator(deviceCandidate);
        GBDevice device = DeviceHelper.getInstance().toSupportedDevice(deviceCandidate);
        if (coordinator.getSupportedDeviceSpecificSettings(device) == null) {
            return true;
        }

        Intent startIntent;
        startIntent = new Intent(this, DeviceSettingsActivity.class);
        startIntent.putExtra(GBDevice.EXTRA_DEVICE, device);
        startActivity(startIntent);
        return true;
    }

    public void onBondingComplete(boolean success) {
        finish();
    }

    public BluetoothDevice getCurrentTarget() {
        return this.bluetoothTarget;
    }

    public void unregisterBroadcastReceivers() {
        AndroidUtils.safeUnregisterBroadcastReceiver(this, bluetoothReceiver);
    }

    public void registerBroadcastReceivers() {
        IntentFilter bluetoothIntents = new IntentFilter();
        bluetoothIntents.addAction(BluetoothDevice.ACTION_FOUND);
        bluetoothIntents.addAction(BluetoothDevice.ACTION_UUID);
        bluetoothIntents.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);

        registerReceiver(bluetoothReceiver, bluetoothIntents);
    }

    @Override
    public Context getContext() {
        return this;
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
}
