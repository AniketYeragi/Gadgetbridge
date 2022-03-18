/*  Copyright (C) 2015-2020 0nse, Andreas Shimokawa, Carsten Pfeiffer,
    Daniel Dakhno, Daniele Gobbetti, Felix Konstantin Maurer, José Rebelo,
    Martin, Normano64, Pavel Elagin, Sebastian Kranz, vanous

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
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.ChartsPreferencesActivity;
import nodomain.freeyourgadget.gadgetbridge.database.PeriodicExporter;
// import nodomain.freeyourgadget.gadgetbridge.devices.DeviceManager;
// import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandPreferencesActivity;
// import nodomain.freeyourgadget.gadgetbridge.devices.qhybrid.ConfigActivity;
// import nodomain.freeyourgadget.gadgetbridge.devices.zetime.ZeTimePreferenceActivity;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.Writer;
import java.io.StringWriter;
import java.io.PrintWriter;

import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.database.Cursor;
import android.app.AlertDialog;
import android.content.DialogInterface;
// import android.widget.ArrayAdapter;
// import android.widget.ListView;
import android.provider.ContactsContract;
import android.content.ContentResolver;

import android.content.SharedPreferences;
import nodomain.freeyourgadget.gadgetbridge.util.ObjectSerializer;


public class ManageContactsActivity extends AbstractSettingsActivity {

    private static final int CONTACT_PICKER_RESULT = 1001;
    private static SharedPreferences sharedPrefs;
    private static GBPrefs gbPrefs;
    // private ArrayAdapter ContactNumberAdapter, ContactNameAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences_contact_list);

        // ContactNumberAdapter = new ArrayAdapter<String>(this, 
        //     R.layout.list_view_numbers, 
        //     GBApplication.GEMTEC_PHONE_NUMBER);
        // ListView listView = (ListView) findViewById(R.id.mobile_number_list);
        // listView.setAdapter(ContactNumberAdapter);

        // ContactNameAdapter = new ArrayAdapter<String>(this, 
        //     R.layout.list_view_numbers, 
        //     GBApplication.GEMTEC_PHONE_NAME);
        // ListView listView2 = (ListView) findViewById(R.id.mobile_name_list);
        // listView2.setAdapter(ContactNameAdapter);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Prefs prefs = GBApplication.getPrefs();

        Preference pref;
        
        pref = findPreference("add_contact");
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                Intent contactPickerIntent = new Intent(Intent.ACTION_PICK,
                    Contacts.CONTENT_URI);
                startActivityForResult(contactPickerIntent, CONTACT_PICKER_RESULT);
                return true;
            }
        });

        pref = findPreference("clear_contact");
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                GBApplication.GEMTEC_PHONE_NUMBER.clear();
                GBApplication.GEMTEC_PHONE_NAME.clear();
                updateGemtecContact();
                kill_activity();
                return true;
            }
        });

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {

        if (requestCode == CONTACT_PICKER_RESULT) {
            Cursor cursor = null;
            String phone = "";
            ContentResolver cr = getContentResolver();
            try {
                String id = intent.getData().getLastPathSegment();
                Uri uri = intent.getData();
                cursor = cr.query(uri, null, null, null, null);
                cursor.moveToFirst();
                final String name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                // query for everything Phone
                cursor = getContentResolver().query(Phone.CONTENT_URI,
                        null, Phone.CONTACT_ID + "=?", new String[] { id },
                        null);
                int phoneIdx = cursor.getColumnIndex(Phone.DATA);                
                if(cursor.getCount() > 1) {
                    int i=0;
                    String[] phoneNum = new String[cursor.getCount()];
                    while (cursor.moveToNext()) {
                        // store the numbers in an array
                        phoneIdx = cursor.getColumnIndex(Phone.DATA);
                        phoneNum[i] = cursor.getString(phoneIdx).replaceAll("\\s","");  // Remove whitespaces
                        i++;
                    }
                    phoneNum = new HashSet<String>(Arrays.asList(phoneNum)).toArray(new String[0]); // Remove duplicates

                    final String[] phoneNumList = phoneNum;
                    // list the phoneNum array using radiobuttons & give the choice to select one number      
                    AlertDialog.Builder alt_bld = new AlertDialog.Builder(ManageContactsActivity.this);
                    //alt_bld.setIcon(R.drawable.icon);
                    alt_bld.setTitle(getString(R.string.contact_picker));
                    alt_bld.setSingleChoiceItems(phoneNumList, -1, new DialogInterface
                            .OnClickListener() {
                        public void onClick(DialogInterface dialog, int item) {
                            String selectedPhoneNum = phoneNumList[item];
                            if (!GBApplication.GEMTEC_PHONE_NUMBER.contains(selectedPhoneNum)) {
                                GBApplication.GEMTEC_PHONE_NUMBER.add(selectedPhoneNum);
                                GBApplication.GEMTEC_PHONE_NAME.add(name);
                                updateGemtecContact();
                            }
                            else {
                                GB.toast(ManageContactsActivity.this, "Contact already exists", Toast.LENGTH_LONG, GB.ERROR);
                            }
                            // Toast.makeText(getApplicationContext(),
                            //         "Group Name = "+phoneNumList[item], Toast.LENGTH_SHORT).show();
                            dialog.dismiss();// dismiss the alertbox after chose option
                            kill_activity();
                        }
                    });
                    AlertDialog alert = alt_bld.create();
                    alert.show();

                } else {
                    if (cursor.moveToFirst()) {
                        phone = cursor.getString(phoneIdx);
                        phone = phone.replaceAll("\\s","");   // Remove whitespaces
                        if (!GBApplication.GEMTEC_PHONE_NUMBER.contains(phone)) {
                            GBApplication.GEMTEC_PHONE_NUMBER.add(phone);
                            GBApplication.GEMTEC_PHONE_NAME.add(name);
                            updateGemtecContact();
                        }
                        else {
                            GB.toast(ManageContactsActivity.this, "Contact already exists", Toast.LENGTH_LONG, GB.ERROR);
                        }
                        // GB.toast(this, phone, Toast.LENGTH_LONG, GB.ERROR);
                    } else {
                        // GB.toast(this, "No result", Toast.LENGTH_LONG, GB.ERROR);
                    }
                    kill_activity();
                }

            } catch (Exception e) {
                // Writer writer = new StringWriter();
                // e.printStackTrace(new PrintWriter(writer));
                // GB.toast(ManageContactsActivity.this, writer.toString(), Toast.LENGTH_LONG, GB.WARN);            
            }
        }
    }

    private void updateGemtecContact() {
        Intent intent = new Intent();
        intent.setAction(GBApplication.ACTION_CONTACTS_CHANGED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPrefs.edit();
        try {
            editor.putString(GBPrefs.CONTACT_NUMBER_LIST, ObjectSerializer.serialize(GBApplication.GEMTEC_PHONE_NUMBER));
            editor.putString(GBPrefs.CONTACT_NAME_LIST, ObjectSerializer.serialize(GBApplication.GEMTEC_PHONE_NAME));
        } catch (Exception e) {
            Writer writer = new StringWriter();
            e.printStackTrace(new PrintWriter(writer));
            GB.toast(ManageContactsActivity.this, writer.toString(), Toast.LENGTH_LONG, GB.WARN);            
        }
        editor.commit();
    }

    void kill_activity() {
        finish();
    }
}
