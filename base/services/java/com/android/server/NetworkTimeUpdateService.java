/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.NtpTrustedTime;
import android.util.TrustedTime;

import com.android.internal.telephony.TelephonyIntents;

/// M: comment @{ add GPS Time Sync Service
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.widget.Toast;
//import com.android.internal.R;
import com.mediatek.internal.R;
import android.os.Bundle;
import android.provider.Settings.SettingNotFoundException;
/// @}

/// M: Configure default date/time to current year after restore reset @{ 
import android.text.format.Time;
import android.os.SystemProperties;
/// @}


/**
 * Monitors the network time and updates the system time if it is out of sync
 * and there hasn't been any NITZ update from the carrier recently.
 * If looking up the network time fails for some reason, it tries a few times with a short
 * interval and then resets to checking on longer intervals.
 * <p>
 * If the user enables AUTO_TIME, it will check immediately for the network time, if NITZ wasn't
 * available.
 * </p>
 */
public class NetworkTimeUpdateService {

    private static final String TAG = "NetworkTimeUpdateService";
    ///M: deubg logging
    private static final boolean DBG = true;

    private static final int EVENT_AUTO_TIME_CHANGED = 1;
    private static final int EVENT_POLL_NETWORK_TIME = 2;
    private static final int EVENT_NETWORK_CONNECTED = 3;
    /// M: comment @{ add GPS Time Sync Service
    private static final int EVENT_GPS_TIME_SYNC_CHANGED = 4;
    /// @}

    /// M: Support default system time @{
    private static final String DECRYPT_STATE = "trigger_restart_framework";
    private static final String BOOT_SYS_PROPERTY = "persist.sys.first_time_boot";
    private static final String ICS_BOOT_SYS_PROPERTY = "persist.sys.boot.count";
    private static int mDefaultYear = 2012;
    private static boolean mIsOverMobile = false;
    /// @}

    /** Normal polling frequency */
    private static final long POLLING_INTERVAL_MS = 24L * 60 * 60 * 1000; // 24 hrs
    /** Try-again polling interval, in case the network request failed */
    private static final long POLLING_INTERVAL_SHORTER_MS = 60 * 1000L; // 60 seconds
    /** Number of times to try again */
    private static final int TRY_AGAIN_TIMES_MAX = 3;
    /** If the time difference is greater than this threshold, then update the time. */
    private static final int TIME_ERROR_THRESHOLD_MS = 5 * 1000;

    private static final String ACTION_POLL =
            "com.android.server.NetworkTimeUpdateService.action.POLL";
    private static int POLL_REQUEST = 0;

    private static final long NOT_SET = -1;
    private long mNitzTimeSetTime = NOT_SET;
    // TODO: Have a way to look up the timezone we are in
    private long mNitzZoneSetTime = NOT_SET;

    private Context mContext;
    private TrustedTime mTime;

    // NTP lookup is done on this thread and handler
    private Handler mHandler;
    private HandlerThread mThread;
    /// M: comment @{ add GPS Time Sync Service
    private Handler mGpsHandler;
    private HandlerThread mGpsThread;
    /// @}
    private AlarmManager mAlarmManager;
    private PendingIntent mPendingPollIntent;
    private SettingsObserver mSettingsObserver;
    // The last time that we successfully fetched the NTP time.
    private long mLastNtpFetchTime = NOT_SET;
    // Keeps track of how many quick attempts were made to fetch NTP time.
    // During bootup, the network may not have been up yet, or it's taking time for the
    // connection to happen.
    private int mTryAgainCounter;

    public NetworkTimeUpdateService(Context context) {
        mContext = context;
        mTime = NtpTrustedTime.getInstance(context);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        Intent pollIntent = new Intent(ACTION_POLL, null);
        mPendingPollIntent = PendingIntent.getBroadcast(mContext, POLL_REQUEST, pollIntent, 0);
    }

    /** Initialize the receivers and initiate the first NTP request */
    public void systemReady() {
        registerForTelephonyIntents();
        registerForAlarms();
        registerForConnectivityIntents();

        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new MyHandler(mThread.getLooper());
        // Check the network time on the new thread
        mHandler.obtainMessage(EVENT_POLL_NETWORK_TIME).sendToTarget();

        mSettingsObserver = new SettingsObserver(mHandler, EVENT_AUTO_TIME_CHANGED);
        mSettingsObserver.observe(mContext);
        
        /// M: comment @{ add GPS Time Sync Service
        Log.d(TAG, "add GPS time sync handler and looper");
        mGpsThread = new HandlerThread(TAG);
        mGpsThread.start();
        mGpsHandler = new MyHandler(mGpsThread.getLooper());
        
        mGpsTimeSyncObserver = new GpsTimeSyncObserver(mGpsHandler, EVENT_GPS_TIME_SYNC_CHANGED);
        mGpsTimeSyncObserver.observe(mContext);
        /// @}
        
        /// M: Configure default date/time to current year after factory reset or first bootup @{ 
        String icsString = SystemProperties.get(ICS_BOOT_SYS_PROPERTY, "");
        boolean isIcsVersion = (icsString != null && "".equals(icsString)) ? false : true;
        if(isIcsVersion){
           return;
        }
        
        mDefaultYear  = mContext.getResources().getInteger(com.mediatek.internal.R.integer.default_restore_year);
        mIsOverMobile = mContext.getResources().getBoolean(com.mediatek.internal.R.bool.config_ntp_by_mobile);
        
        String tempString = SystemProperties.get(BOOT_SYS_PROPERTY, "");
        boolean isFirstBoot = (tempString != null && "".equals(tempString)) ? true : false;
        if(isFirstBoot){
           tempString = SystemProperties.get("ro.kernel.qemu", "");
           boolean isEmulator = "1".equals(tempString) ? true : false;
           if(isEmulator){
              Log.d(TAG, "isEmulator:" + tempString);
              return;
           }

           String decryptState = SystemProperties.get("vold.decrypt", "");
           Log.d(TAG, "decryptState:" + decryptState);
           if("".equals(decryptState) || DECRYPT_STATE.equals(decryptState)){
              Time today = new Time(Time.getCurrentTimezone());
              today.setToNow();
              Log.d(TAG, "First boot:" + tempString + " with date:" + today);
              today.set(today.monthDay, today.month, mDefaultYear);
              Log.d(TAG, "Set the year to " + mDefaultYear);
                     SystemProperties.set(BOOT_SYS_PROPERTY, "false");
                 SystemClock.setCurrentTimeMillis(today.toMillis(false));
              }
           }
        ///@}
    }

    private void registerForTelephonyIntents() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TelephonyIntents.ACTION_NETWORK_SET_TIME);
        intentFilter.addAction(TelephonyIntents.ACTION_NETWORK_SET_TIMEZONE);
        mContext.registerReceiver(mNitzReceiver, intentFilter);
    }

    private void registerForAlarms() {
        mContext.registerReceiver(
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mHandler.obtainMessage(EVENT_POLL_NETWORK_TIME).sendToTarget();
                }
            }, new IntentFilter(ACTION_POLL));
    }

    private void registerForConnectivityIntents() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(mConnectivityReceiver, intentFilter);
    }

    private void onPollNetworkTime(int event) {
        // If Automatic time is not set, don't bother.
        if (!isAutomaticTimeRequested()) return;

        final long refTime = SystemClock.elapsedRealtime();
        // If NITZ time was received less than POLLING_INTERVAL_MS time ago,
        // no need to sync to NTP.
        if (mNitzTimeSetTime != NOT_SET && refTime - mNitzTimeSetTime < POLLING_INTERVAL_MS) {
            resetAlarm(POLLING_INTERVAL_MS);
            return;
        }
        final long currentTime = System.currentTimeMillis();
        if (DBG) Log.d(TAG, "System time = " + currentTime);
        // Get the NTP time
        if (mLastNtpFetchTime == NOT_SET || refTime >= mLastNtpFetchTime + POLLING_INTERVAL_MS
                || event == EVENT_AUTO_TIME_CHANGED) {
            if (DBG) Log.d(TAG, "Before Ntp fetch");

            // force refresh NTP cache when outdated
            if (mTime.getCacheAge() >= POLLING_INTERVAL_MS) {
                mTime.forceRefresh();
            }

            // only update when NTP time is fresh
            if (mTime.getCacheAge() < POLLING_INTERVAL_MS) {
                final long ntp = mTime.currentTimeMillis();
                mTryAgainCounter = 0;
                // If the clock is more than N seconds off or this is the first time it's been
                // fetched since boot, set the current time.
                if (Math.abs(ntp - currentTime) > TIME_ERROR_THRESHOLD_MS
                        || mLastNtpFetchTime == NOT_SET) {
                    // Set the system time
                    if (DBG && mLastNtpFetchTime == NOT_SET
                            && Math.abs(ntp - currentTime) <= TIME_ERROR_THRESHOLD_MS) {
                        Log.d(TAG, "For initial setup, rtc = " + currentTime);
                    }
                    if (DBG) Log.d(TAG, "Ntp time to be set = " + ntp);
                    // Make sure we don't overflow, since it's going to be converted to an int
                    if (ntp / 1000 < Integer.MAX_VALUE) {
                        SystemClock.setCurrentTimeMillis(ntp);
                    }
                } else {
                    if (DBG) Log.d(TAG, "Ntp time is close enough = " + ntp);
                }
                mLastNtpFetchTime = SystemClock.elapsedRealtime();
            } else {
                // Try again shortly
                mTryAgainCounter++;
                if (mTryAgainCounter <= TRY_AGAIN_TIMES_MAX) {
                    resetAlarm(POLLING_INTERVAL_SHORTER_MS);
                } else {
                    // Try much later
                    mTryAgainCounter = 0;
                    resetAlarm(POLLING_INTERVAL_MS);
                }
                return;
            }
        }
        resetAlarm(POLLING_INTERVAL_MS);
    }

    /**
     * Cancel old alarm and starts a new one for the specified interval.
     *
     * @param interval when to trigger the alarm, starting from now.
     */
    private void resetAlarm(long interval) {
        mAlarmManager.cancel(mPendingPollIntent);
        long now = SystemClock.elapsedRealtime();
        long next = now + interval;
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, next, mPendingPollIntent);
    }

    /**
     * Checks if the user prefers to automatically set the time.
     */
    private boolean isAutomaticTimeRequested() {
        return Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.AUTO_TIME, 0) != 0;
    }

    /** Receiver for Nitz time events */
    private BroadcastReceiver mNitzReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.ACTION_NETWORK_SET_TIME.equals(action)) {
                mNitzTimeSetTime = SystemClock.elapsedRealtime();
            } else if (TelephonyIntents.ACTION_NETWORK_SET_TIMEZONE.equals(action)) {
                mNitzZoneSetTime = SystemClock.elapsedRealtime();
            }
        }
    };

    /** Receiver for ConnectivityManager events */
    private BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                // There is connectivity
                final ConnectivityManager connManager = (ConnectivityManager) context
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
                final NetworkInfo netInfo = connManager.getActiveNetworkInfo();
                if (netInfo != null) {
                    // Verify that it's a WIFI connection
                    if (netInfo.getState() == NetworkInfo.State.CONNECTED &&
                            (netInfo.getType() == ConnectivityManager.TYPE_WIFI ||
                                netInfo.getType() == ConnectivityManager.TYPE_ETHERNET) ) {
                        mHandler.obtainMessage(EVENT_NETWORK_CONNECTED).sendToTarget();
                    }
                    ///M: Support for NTP over mobile data connection @{
                    if (mIsOverMobile && netInfo.getState() == NetworkInfo.State.CONNECTED &&
                            (netInfo.getType() == ConnectivityManager.TYPE_MOBILE) ) {
                        Log.d(TAG, "Ntp over mobile data connection");
                        mHandler.obtainMessage(EVENT_NETWORK_CONNECTED).sendToTarget();
                    }
                    ///@}
                }
            }
        }
    };

    /** Handler to do the network accesses on */
    private class MyHandler extends Handler {

        public MyHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_AUTO_TIME_CHANGED:
                case EVENT_POLL_NETWORK_TIME:
                case EVENT_NETWORK_CONNECTED:
                    onPollNetworkTime(msg.what);
                    break;

                /// M: comment @{ add GPS Time Sync Service
                case EVENT_GPS_TIME_SYNC_CHANGED:
                    boolean gpsTimeSyncStatus = getGpsTimeSyncState();;
                    Log.d(TAG, "GPS Time sync is changed to " + gpsTimeSyncStatus);
                    onGpsTimeChanged(gpsTimeSyncStatus);
                    break;
                /// @}
            }
        }
    }

    /** Observer to watch for changes to the AUTO_TIME setting */
    private static class SettingsObserver extends ContentObserver {

        private int mMsg;
        private Handler mHandler;

        SettingsObserver(Handler handler, int msg) {
            super(handler);
            mHandler = handler;
            mMsg = msg;
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.AUTO_TIME),
                    false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mHandler.obtainMessage(mMsg).sendToTarget();
        }
    }

    /// M: comment @{ add GPS Time Sync Service
    /** ================ Gps Time Sync part ================*/
    
    private Thread          mGpsTimerThread; //for interrupt
    private LocationManager mLocationManager;
    private boolean         mIsGpsTimeSyncRunning = false;
    private GpsTimeSyncObserver   mGpsTimeSyncObserver;

    private boolean getGpsTimeSyncState() {
        try {
            return Settings.System.getInt(mContext.getContentResolver(), Settings.System.AUTO_TIME_GPS) > 0;
        } catch (SettingNotFoundException snfe) {
            return false;
        }
    }
    
    private static class GpsTimeSyncObserver extends ContentObserver {

        private int mMsg;
        private Handler mHandler;

        GpsTimeSyncObserver(Handler handler, int msg) {
            super(handler);
            mHandler = handler;
            mMsg = msg;
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.AUTO_TIME_GPS),
                    false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mHandler.obtainMessage(mMsg).sendToTarget();
        }
    }

    public void onGpsTimeChanged(boolean enable) {
        if(enable) {
            startUsingGpsWithTimeout(180000,
                mContext.getString(R.string.gps_time_sync_fail_str));
        } else {
            if(mGpsTimerThread != null) {
                mGpsTimerThread.interrupt();
            }
        }
    }
    
    public void startUsingGpsWithTimeout(final int milliseconds, final String timeoutMsg) {
    
        if(mIsGpsTimeSyncRunning == true) {
            Log.d(TAG, "WARNING: Gps Time Sync is already run");
            return;
        } else {
            mIsGpsTimeSyncRunning = true;
        }
        
        Log.d(TAG, "start using GPS for GPS time sync timeout=" + milliseconds + " timeoutMsg=" + timeoutMsg);
        mLocationManager = (LocationManager)mContext.getSystemService(mContext.LOCATION_SERVICE);
        mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, mLocationListener);
        mGpsTimerThread = new Thread() {
            public void run() {
                boolean isTimeout = false;
                try {
                    Thread.sleep(milliseconds);
                    isTimeout = true;
                } catch (InterruptedException e) {
                }
                Log.d(TAG, "isTimeout=" + isTimeout);
                if(isTimeout == true) {
                    Message m = new Message();
                    m.obj = timeoutMsg;
                    mGpsToastHandler.sendMessage(m);
                }
                mLocationManager.removeUpdates(mLocationListener);
                mIsGpsTimeSyncRunning = false;
            }
        };
        mGpsTimerThread.start();
    }

    
    private Handler mGpsToastHandler = new Handler() {
        public void handleMessage(Message msg) {
            String timeoutMsg = (String)msg.obj;
            Toast.makeText(mContext, timeoutMsg, Toast.LENGTH_LONG).show();
        }
    };
    
    private LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            mGpsTimerThread.interrupt();
        }
        @Override
        public void onProviderDisabled(String provider) {
        }
        @Override
        public void onProviderEnabled(String provider) {
        }
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };
    /// @}
 
}
