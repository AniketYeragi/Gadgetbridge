/*  Copyright (C) 2017-2020 Carsten Pfeiffer, Daniele Gobbetti, Felix
    Konstantin Maurer

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
package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.PeriodicExporter;
import nodomain.freeyourgadget.gadgetbridge.service.DiscoveryService;

import android.widget.Toast;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import static nodomain.freeyourgadget.gadgetbridge.util.GB.toast;

public class AutoStartReceiver extends BroadcastReceiver {
    private static final String TAG = AutoStartReceiver.class.getName();
    private static boolean device_booted = false;
    private static boolean device_unlocked = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        toast(context, "onReceive", Toast.LENGTH_LONG, GB.WARN);
        switch (intent.getAction()) {
            case Intent.ACTION_BOOT_COMPLETED:
            toast(context, "ACTION_BOOT_COMPLETED", Toast.LENGTH_LONG, GB.WARN);
            device_unlocked = true;
            break;
            case Intent.ACTION_LOCKED_BOOT_COMPLETED:
            toast(context, "ACTION_BOOT_COMPLETED", Toast.LENGTH_LONG, GB.WARN);
            device_unlocked = true;
            break;
            case Intent.ACTION_USER_UNLOCKED:
            toast(context, "ACTION_USER_UNLOCKED", Toast.LENGTH_LONG, GB.WARN);
            if(!GBApplication.isMyServiceRunning(context, DiscoveryService.class)) {
                device_unlocked = true;
            }
            case Intent.ACTION_USER_PRESENT:
            toast(context, "ACTION_USER_PRESENT", Toast.LENGTH_LONG, GB.WARN);
            if(!GBApplication.isMyServiceRunning(context, DiscoveryService.class)) {
                device_unlocked = true;
            }
            default: // fall out
        }
        // if (GBApplication.getGBPrefs().getAutoStart() && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
        if (device_unlocked) {
            device_unlocked = false;
            Log.i(TAG, "Boot completed, starting Gadgetbridge");
            if (GBApplication.getPrefs().getBoolean("general_autoconnectonbluetooth", false)) {
                Log.i(TAG, "Autoconnect is enabled, attempting to connect");
                GBApplication.deviceService().connect();
            } else {
                GBApplication.deviceService().start();
            }

            PeriodicExporter.enablePeriodicExport(context);
        }
    }
}
