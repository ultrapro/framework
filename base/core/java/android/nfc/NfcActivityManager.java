/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.nfc;

import android.app.Activity;
import android.app.Application;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Manages NFC API's that are coupled to the life-cycle of an Activity.
 *
 * <p>Uses {@link Application#registerActivityLifecycleCallbacks} to hook
 * into activity life-cycle events such as onPause() and onResume().
 *
 * @hide
 */
public final class NfcActivityManager extends INdefPushCallback.Stub
        implements Application.ActivityLifecycleCallbacks {
    static final String TAG = NfcAdapter.TAG;
    static final Boolean DBG = false;

    final NfcAdapter mAdapter;
    final NfcEvent mDefaultEvent;  // cached NfcEvent (its currently always the same)

    // All objects in the lists are protected by this
    final List<NfcApplicationState> mApps;  // Application(s) that have NFC state. Usually one
    final List<NfcActivityState> mActivities;  // Activities that have NFC state

    /**
     * NFC State associated with an {@link Application}.
     */
    class NfcApplicationState {
        int refCount = 0;
        final Application app;
        public NfcApplicationState(Application app) {
            this.app = app;
        }
        public void register() {
            refCount++;
            if (refCount == 1) {
                this.app.registerActivityLifecycleCallbacks(NfcActivityManager.this);
            }
        }
        public void unregister() {
            refCount--;
            if (refCount == 0) {
                this.app.unregisterActivityLifecycleCallbacks(NfcActivityManager.this);
            } else if (refCount < 0) {
                Log.e(TAG, "-ve refcount for " + app);
            }
        }
    }

    NfcApplicationState findAppState(Application app) {
        for (NfcApplicationState appState : mApps) {
            if (appState.app == app) {
                return appState;
            }
        }
        return null;
    }

    void registerApplication(Application app) {
        NfcApplicationState appState = findAppState(app);
        if (appState == null) {
            appState = new NfcApplicationState(app);
            mApps.add(appState);
        }
        appState.register();
    }

    void unregisterApplication(Application app) {
        NfcApplicationState appState = findAppState(app);
        if (appState == null) {
            Log.e(TAG, "app was not registered " + app);
            return;
        }
        appState.unregister();
    }

    /**
     * NFC state associated with an {@link Activity}
     */
    class NfcActivityState {
        boolean resumed = false;
        Activity activity;
        NdefMessage ndefMessage = null;  // static NDEF message
        NfcAdapter.CreateNdefMessageCallback ndefMessageCallback = null;
        NfcAdapter.OnNdefPushCompleteCallback onNdefPushCompleteCallback = null;
        NfcAdapter.CreateBeamUrisCallback uriCallback = null;
        Uri[] uris = null;
        
        /// M: @ { 
        String mHandoverUseCase = null;
        /// }
		
        public NfcActivityState(Activity activity) {
            if (activity.getWindow().isDestroyed()) {
                throw new IllegalStateException("activity is already destroyed");
            }
            // Check if activity is resumed right now, as we will not
            // immediately get a callback for that.
            resumed = activity.isResumed();

            this.activity = activity;
            registerApplication(activity.getApplication());
        }
        public void destroy() {
            unregisterApplication(activity.getApplication());
            resumed = false;
            activity = null;
            ndefMessage = null;
            ndefMessageCallback = null;
            onNdefPushCompleteCallback = null;
            uriCallback = null;
            uris = null;
        }
        @Override
        public String toString() {
            StringBuilder s = new StringBuilder("[").append(" ");
            s.append(ndefMessage).append(" ").append(ndefMessageCallback).append(" ");
            s.append(uriCallback).append(" ");
            if (uris != null) {
                for (Uri uri : uris) {
                    s.append(onNdefPushCompleteCallback).append(" ").append(uri).append("]");
                }
            }
            return s.toString();
        }
    }

    /** find activity state from mActivities */
    synchronized NfcActivityState findActivityState(Activity activity) {
        for (NfcActivityState state : mActivities) {
            if (state.activity == activity) {
                return state;
            }
        }
        return null;
    }

    /** find or create activity state from mActivities */
    synchronized NfcActivityState getActivityState(Activity activity) {
        NfcActivityState state = findActivityState(activity);
        if (state == null) {
            state = new NfcActivityState(activity);
            mActivities.add(state);
        }
        return state;
    }

    synchronized NfcActivityState findResumedActivityState() {
        for (NfcActivityState state : mActivities) {
            if (state.resumed) {
                return state;
            }
        }
        return null;
    }

    synchronized void destroyActivityState(Activity activity) {
        NfcActivityState activityState = findActivityState(activity);
        if (activityState != null) {
            activityState.destroy();
            mActivities.remove(activityState);
        }
    }

    public NfcActivityManager(NfcAdapter adapter) {
        mAdapter = adapter;
        mActivities = new LinkedList<NfcActivityState>();
        mApps = new ArrayList<NfcApplicationState>(1);  // Android VM usually has 1 app
        mDefaultEvent = new NfcEvent(mAdapter);
    }

    public void setNdefPushContentUri(Activity activity, Uri[] uris) {
        boolean isResumed;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = getActivityState(activity);
            state.uris = uris;
            isResumed = state.resumed;
        }
        if (isResumed) {
            requestNfcServiceCallback(true);
        }
    }


    /// M: @ {     
    public void setMtkNdefPushContentUri(Activity activity, Uri[] uris,String useCase) {
        boolean isResumed;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = getActivityState(activity);
            state.uris = uris;
            state.mHandoverUseCase = useCase;
            isResumed = state.resumed;
        }
        if (isResumed) {
            requestNfcServiceCallback(true);
        }
    }
    /// }


    public void setNdefPushContentUriCallback(Activity activity,
            NfcAdapter.CreateBeamUrisCallback callback) {
        boolean isResumed;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = getActivityState(activity);
            state.uriCallback = callback;
            isResumed = state.resumed;
        }
        if (isResumed) {
            requestNfcServiceCallback(true);
        }
    }

    /// M: @ {
    public void setMtkNdefPushContentUriCallback(Activity activity,
            NfcAdapter.CreateBeamUrisCallback callback, String useCase) {
        boolean isResumed;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = getActivityState(activity);
            state.uriCallback = callback;
            state.mHandoverUseCase = useCase;
            isResumed = state.resumed;
        }
        if (isResumed) {
            requestNfcServiceCallback(true);
        }
    }
    /// }

    

    public void setNdefPushMessage(Activity activity, NdefMessage message) {
        boolean isResumed;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = getActivityState(activity);
            state.ndefMessage = message;
            isResumed = state.resumed;
        }
        if (isResumed) {
            requestNfcServiceCallback(true);
        }
    }

    public void setNdefPushMessageCallback(Activity activity,
            NfcAdapter.CreateNdefMessageCallback callback) {
        boolean isResumed;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = getActivityState(activity);
            state.ndefMessageCallback = callback;
            isResumed = state.resumed;
        }
        if (isResumed) {
            requestNfcServiceCallback(true);
        }
    }

    public void setOnNdefPushCompleteCallback(Activity activity,
            NfcAdapter.OnNdefPushCompleteCallback callback) {
        boolean isResumed;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = getActivityState(activity);
            state.onNdefPushCompleteCallback = callback;
            isResumed = state.resumed;
        }
        if (isResumed) {
            requestNfcServiceCallback(true);
        }
    }

    /**
     * Request or unrequest NFC service callbacks for NDEF push.
     * Makes IPC call - do not hold lock.
     * TODO: Do not do IPC on every onPause/onResume
     */
    void requestNfcServiceCallback(boolean request) {
        try {
            NfcAdapter.sService.setNdefPushCallback(request ? this : null);
        } catch (RemoteException e) {
            mAdapter.attemptDeadServiceRecovery(e);
        }
    }

    /** Callback from NFC service, usually on binder thread */
    @Override
    public NdefMessage createMessage() {
        NfcAdapter.CreateNdefMessageCallback callback;
        NdefMessage message;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = findResumedActivityState();
            if (state == null) return null;

            callback = state.ndefMessageCallback;
            message = state.ndefMessage;
        }

        // Make callback without lock
        if (callback != null) {
            return callback.createNdefMessage(mDefaultEvent);
        } else {
            return message;
        }
    }

    /** Callback from NFC service, usually on binder thread */
    @Override
    public Uri[] getUris() {
        Uri[] uris;
        NfcAdapter.CreateBeamUrisCallback callback;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = findResumedActivityState();
            if (state == null) return null;
            uris = state.uris;
            callback = state.uriCallback;
            
            /// M: @ {
            Log.d(TAG, "getUris() state.uris: "+uris);
            if(uris != null){
                for (Uri uri : uris)
                    Log.d(TAG, "  uri.toString: "+uri.toString());
            }
            Log.d(TAG, "state.uriCallback "+callback);
            /// }
            
        }
        if (callback != null) {
            uris = callback.createBeamUris(mDefaultEvent);
            if (uris != null) {
                for (Uri uri : uris) {
                    if (uri == null) {
                        Log.e(TAG, "Uri not allowed to be null.");
                        return null;
                    }
                    String scheme = uri.getScheme();
                    if (scheme == null || (!scheme.equalsIgnoreCase("file") &&
                            !scheme.equalsIgnoreCase("content"))) {
                        Log.e(TAG, "Uri needs to have " +
                                "either scheme file or scheme content");
                        return null;
                    }
                }
            }
            return uris;
        } else {
            return uris;
        }
    }

    /// M: @ {
    /** Callback from NFC service, usually on binder thread */
    public String getUseCase() {
        String mUseCase;
        ///NfcAdapter.CreateBeamUrisCallback callback;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = findResumedActivityState();
            if (state == null) return null;
            mUseCase = state.mHandoverUseCase;
        }
        
            return mUseCase;

    }
    /// }


    /** Callback from NFC service, usually on binder thread */
    @Override
    public void onNdefPushComplete() {
        NfcAdapter.OnNdefPushCompleteCallback callback;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = findResumedActivityState();
            if (state == null) return;

            callback = state.onNdefPushCompleteCallback;
        }

        // Make callback without lock
        if (callback != null) {
            callback.onNdefPushComplete(mDefaultEvent);
        }
    }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) { /* NO-OP */ }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityStarted(Activity activity) { /* NO-OP */ }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityResumed(Activity activity) {
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = findActivityState(activity);
            if (DBG) Log.d(TAG, "onResume() for " + activity + " " + state);
            if (state == null) return;
            state.resumed = true;
        }
        requestNfcServiceCallback(true);
    }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityPaused(Activity activity) {
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = findActivityState(activity);
            if (DBG) Log.d(TAG, "onPause() for " + activity + " " + state);
            if (state == null) return;
            state.resumed = false;
        }
        requestNfcServiceCallback(false);
    }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityStopped(Activity activity) { /* NO-OP */ }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) { /* NO-OP */ }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityDestroyed(Activity activity) {
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = findActivityState(activity);
            if (DBG) Log.d(TAG, "onDestroy() for " + activity + " " + state);
            if (state != null) {
                // release all associated references
                destroyActivityState(activity);
            }
        }
    }

}
