/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.os.storage;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;
import com.mediatek.common.featureoption.FeatureOption;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StorageManager is the interface to the systems storage service. The storage
 * manager handles storage-related items such as Opaque Binary Blobs (OBBs).
 * <p>
 * OBBs contain a filesystem that maybe be encrypted on disk and mounted
 * on-demand from an application. OBBs are a good way of providing large amounts
 * of binary assets without packaging them into APKs as they may be multiple
 * gigabytes in size. However, due to their size, they're most likely stored in
 * a shared storage pool accessible from all programs. The system does not
 * guarantee the security of the OBB file itself: if any program modifies the
 * OBB, there is no guarantee that a read from that OBB will produce the
 * expected output.
 * <p>
 * Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String)} with an
 * argument of {@link android.content.Context#STORAGE_SERVICE}.
 */

public class StorageManager
{
    private static final String TAG = "StorageManager";
    /// M: @{
    private static final String PROP_SD_DEFAULT_PATH = "persist.sys.sd.defaultpath";
    private static final String PROP_SD_INTERNAL_PATH = "internal_sd_path";
    private static final String PROP_SD_EXTERNAL_PATH = "external_sd_path";
    private static final String ICS_STORAGE_PATH_SD1 = "/mnt/sdcard";
    private static final String ICS_STORAGE_PATH_SD2 = "/mnt/sdcard2";
    private static final String STORAGE_PATH_SD1 = "/storage/sdcard0";
    private static final String STORAGE_PATH_SD2 = "/storage/sdcard1";
    private File mMTKExternalCacheDir;
    /// @}

    /*
     * Our internal MountService binder reference
     */
    final private IMountService mMountService;

    /*
     * The looper target for callbacks
     */
    Looper mTgtLooper;

    /*
     * Target listener for binder callbacks
     */
    private MountServiceBinderListener mBinderListener;

    /*
     * List of our listeners
     */
    private List<ListenerDelegate> mListeners = new ArrayList<ListenerDelegate>();

    /*
     * Next available nonce
     */
    final private AtomicInteger mNextNonce = new AtomicInteger(0);

    private class MountServiceBinderListener extends IMountServiceListener.Stub {
        public void onUsbMassStorageConnectionChanged(boolean available) {
            final int size = mListeners.size();
            for (int i = 0; i < size; i++) {
                mListeners.get(i).sendShareAvailabilityChanged(available);
            }
        }

        public void onStorageStateChanged(String path, String oldState, String newState) {
            final int size = mListeners.size();
            for (int i = 0; i < size; i++) {
                mListeners.get(i).sendStorageStateChanged(path, oldState, newState);
            }
        }
    }

    /**
     * Binder listener for OBB action results.
     */
    private final ObbActionListener mObbActionListener = new ObbActionListener();

    private class ObbActionListener extends IObbActionListener.Stub {
        @SuppressWarnings("hiding")
        private SparseArray<ObbListenerDelegate> mListeners = new SparseArray<ObbListenerDelegate>();

        @Override
        public void onObbResult(String filename, int nonce, int status) {
            final ObbListenerDelegate delegate;
            synchronized (mListeners) {
                delegate = mListeners.get(nonce);
                if (delegate != null) {
                    mListeners.remove(nonce);
                }
            }

            if (delegate != null) {
                delegate.sendObbStateChanged(filename, status);
            }
        }

        public int addListener(OnObbStateChangeListener listener) {
            final ObbListenerDelegate delegate = new ObbListenerDelegate(listener);

            synchronized (mListeners) {
                mListeners.put(delegate.nonce, delegate);
            }

            return delegate.nonce;
        }
    }

    private int getNextNonce() {
        return mNextNonce.getAndIncrement();
    }

    /**
     * Private class containing sender and receiver code for StorageEvents.
     */
    private class ObbListenerDelegate {
        private final WeakReference<OnObbStateChangeListener> mObbEventListenerRef;
        private final Handler mHandler;

        private final int nonce;

        ObbListenerDelegate(OnObbStateChangeListener listener) {
            nonce = getNextNonce();
            mObbEventListenerRef = new WeakReference<OnObbStateChangeListener>(listener);
            mHandler = new Handler(mTgtLooper) {
                @Override
                public void handleMessage(Message msg) {
                    final OnObbStateChangeListener changeListener = getListener();
                    if (changeListener == null) {
                        return;
                    }

                    StorageEvent e = (StorageEvent) msg.obj;

                    if (msg.what == StorageEvent.EVENT_OBB_STATE_CHANGED) {
                        ObbStateChangedStorageEvent ev = (ObbStateChangedStorageEvent) e;
                        changeListener.onObbStateChange(ev.path, ev.state);
                    } else {
                        Log.e(TAG, "Unsupported event " + msg.what);
                    }
                }
            };
        }

        OnObbStateChangeListener getListener() {
            if (mObbEventListenerRef == null) {
                return null;
            }
            return mObbEventListenerRef.get();
        }

        void sendObbStateChanged(String path, int state) {
            ObbStateChangedStorageEvent e = new ObbStateChangedStorageEvent(path, state);
            mHandler.sendMessage(e.getMessage());
        }
    }

    /**
     * Message sent during an OBB status change event.
     */
    private class ObbStateChangedStorageEvent extends StorageEvent {
        public final String path;

        public final int state;

        public ObbStateChangedStorageEvent(String path, int state) {
            super(EVENT_OBB_STATE_CHANGED);
            this.path = path;
            this.state = state;
        }
    }

    /**
     * Private base class for messages sent between the callback thread
     * and the target looper handler.
     */
    private class StorageEvent {
        static final int EVENT_UMS_CONNECTION_CHANGED = 1;
        static final int EVENT_STORAGE_STATE_CHANGED = 2;
        static final int EVENT_OBB_STATE_CHANGED = 3;

        private Message mMessage;

        public StorageEvent(int what) {
            mMessage = Message.obtain();
            mMessage.what = what;
            mMessage.obj = this;
        }

        public Message getMessage() {
            return mMessage;
        }
    }

    /**
     * Message sent on a USB mass storage connection change.
     */
    private class UmsConnectionChangedStorageEvent extends StorageEvent {
        public boolean available;

        public UmsConnectionChangedStorageEvent(boolean a) {
            super(EVENT_UMS_CONNECTION_CHANGED);
            available = a;
        }
    }

    /**
     * Message sent on volume state change.
     */
    private class StorageStateChangedStorageEvent extends StorageEvent {
        public String path;
        public String oldState;
        public String newState;

        public StorageStateChangedStorageEvent(String p, String oldS, String newS) {
            super(EVENT_STORAGE_STATE_CHANGED);
            path = p;
            oldState = oldS;
            newState = newS;
        }
    }

    /**
     * Private class containing sender and receiver code for StorageEvents.
     */
    private class ListenerDelegate {
        final StorageEventListener mStorageEventListener;
        private final Handler mHandler;

        ListenerDelegate(StorageEventListener listener) {
            mStorageEventListener = listener;
            mHandler = new Handler(mTgtLooper) {
                @Override
                public void handleMessage(Message msg) {
                    StorageEvent e = (StorageEvent) msg.obj;

                    if (msg.what == StorageEvent.EVENT_UMS_CONNECTION_CHANGED) {
                        UmsConnectionChangedStorageEvent ev = (UmsConnectionChangedStorageEvent) e;
                        mStorageEventListener.onUsbMassStorageConnectionChanged(ev.available);
                    } else if (msg.what == StorageEvent.EVENT_STORAGE_STATE_CHANGED) {
                        StorageStateChangedStorageEvent ev = (StorageStateChangedStorageEvent) e;
                        mStorageEventListener.onStorageStateChanged(ev.path, ev.oldState, ev.newState);
                    } else {
                        Log.e(TAG, "Unsupported event " + msg.what);
                    }
                }
            };
        }

        StorageEventListener getListener() {
            return mStorageEventListener;
        }

        void sendShareAvailabilityChanged(boolean available) {
            UmsConnectionChangedStorageEvent e = new UmsConnectionChangedStorageEvent(available);
            mHandler.sendMessage(e.getMessage());
        }

        void sendStorageStateChanged(String path, String oldState, String newState) {
            StorageStateChangedStorageEvent e = new StorageStateChangedStorageEvent(path, oldState, newState);
            mHandler.sendMessage(e.getMessage());
        }
    }

    /** {@hide} */
    public static StorageManager from(Context context) {
        return (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
    }

    /**
     * Constructs a StorageManager object through which an application can
     * can communicate with the systems mount service.
     * 
     * @param tgtLooper The {@link android.os.Looper} which events will be received on.
     *
     * <p>Applications can get instance of this class by calling
     * {@link android.content.Context#getSystemService(java.lang.String)} with an argument
     * of {@link android.content.Context#STORAGE_SERVICE}.
     *
     * @hide
     */
    public StorageManager(Looper tgtLooper) throws RemoteException {
        mMountService = IMountService.Stub.asInterface(ServiceManager.getService("mount"));
        if (mMountService == null) {
            Log.e(TAG, "Unable to connect to mount service! - is it running yet?");
            return;
        }
        mTgtLooper = tgtLooper;
    }


    /**
     * Registers a {@link android.os.storage.StorageEventListener StorageEventListener}.
     *
     * @param listener A {@link android.os.storage.StorageEventListener StorageEventListener} object.
     *
     * @hide
     */
    public void registerListener(StorageEventListener listener) {
        if (listener == null) {
            return;
        }

        synchronized (mListeners) {
            if (mBinderListener == null ) {
                try {
                    mBinderListener = new MountServiceBinderListener();
                    mMountService.registerListener(mBinderListener);
                } catch (RemoteException rex) {
                    Log.e(TAG, "Register mBinderListener failed");
                    return;
                }
            }
            mListeners.add(new ListenerDelegate(listener));
        }
    }

    /**
     * Unregisters a {@link android.os.storage.StorageEventListener StorageEventListener}.
     *
     * @param listener A {@link android.os.storage.StorageEventListener StorageEventListener} object.
     *
     * @hide
     */
    public void unregisterListener(StorageEventListener listener) {
        if (listener == null) {
            return;
        }

        synchronized (mListeners) {
            final int size = mListeners.size();
            for (int i=0 ; i<size ; i++) {
                ListenerDelegate l = mListeners.get(i);
                if (l.getListener() == listener) {
                    mListeners.remove(i);
                    break;
                }
            }
            if (mListeners.size() == 0 && mBinderListener != null) {
                try {
                    mMountService.unregisterListener(mBinderListener);
                } catch (RemoteException rex) {
                    Log.e(TAG, "Unregister mBinderListener failed");
                    return;
                }
            }
       }
    }

    /**
     * Enables USB Mass Storage (UMS) on the device.
     *
     * @hide
     */
    public void enableUsbMassStorage() {
        try {
            mMountService.setUsbMassStorageEnabled(true);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to enable UMS", ex);
        }
    }

    /**
     * Disables USB Mass Storage (UMS) on the device.
     *
     * @hide
     */
    public void disableUsbMassStorage() {
        try {
            mMountService.setUsbMassStorageEnabled(false);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to disable UMS", ex);
        }
    }

    /**
     * Query if a USB Mass Storage (UMS) host is connected.
     * @return true if UMS host is connected.
     *
     * @hide
     */
    public boolean isUsbMassStorageConnected() {
        try {
            return mMountService.isUsbMassStorageConnected();
        } catch (Exception ex) {
            Log.e(TAG, "Failed to get UMS connection state", ex);
        }
        return false;
    }

    /**
     * Query if a USB Mass Storage (UMS) is enabled on the device.
     * @return true if UMS host is enabled.
     *
     * @hide
     */
    public boolean isUsbMassStorageEnabled() {
        try {
            return mMountService.isUsbMassStorageEnabled();
        } catch (RemoteException rex) {
            Log.e(TAG, "Failed to get UMS enable state", rex);
        }
        return false;
    }

    /**
     * Mount an Opaque Binary Blob (OBB) file. If a <code>key</code> is
     * specified, it is supplied to the mounting process to be used in any
     * encryption used in the OBB.
     * <p>
     * The OBB will remain mounted for as long as the StorageManager reference
     * is held by the application. As soon as this reference is lost, the OBBs
     * in use will be unmounted. The {@link OnObbStateChangeListener} registered
     * with this call will receive the success or failure of this operation.
     * <p>
     * <em>Note:</em> you can only mount OBB files for which the OBB tag on the
     * file matches a package ID that is owned by the calling program's UID.
     * That is, shared UID applications can attempt to mount any other
     * application's OBB that shares its UID.
     * 
     * @param rawPath the path to the OBB file
     * @param key secret used to encrypt the OBB; may be <code>null</code> if no
     *            encryption was used on the OBB.
     * @param listener will receive the success or failure of the operation
     * @return whether the mount call was successfully queued or not
     */
    public boolean mountObb(String rawPath, String key, OnObbStateChangeListener listener) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        Preconditions.checkNotNull(listener, "listener cannot be null");

        try {
            final String canonicalPath = new File(rawPath).getCanonicalPath();
            final int nonce = mObbActionListener.addListener(listener);
            mMountService.mountObb(rawPath, canonicalPath, key, mObbActionListener, nonce);
            return true;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve path: " + rawPath, e);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to mount OBB", e);
        }

        return false;
    }

    /**
     * Unmount an Opaque Binary Blob (OBB) file asynchronously. If the
     * <code>force</code> flag is true, it will kill any application needed to
     * unmount the given OBB (even the calling application).
     * <p>
     * The {@link OnObbStateChangeListener} registered with this call will
     * receive the success or failure of this operation.
     * <p>
     * <em>Note:</em> you can only mount OBB files for which the OBB tag on the
     * file matches a package ID that is owned by the calling program's UID.
     * That is, shared UID applications can obtain access to any other
     * application's OBB that shares its UID.
     * <p>
     * 
     * @param rawPath path to the OBB file
     * @param force whether to kill any programs using this in order to unmount
     *            it
     * @param listener will receive the success or failure of the operation
     * @return whether the unmount call was successfully queued or not
     */
    public boolean unmountObb(String rawPath, boolean force, OnObbStateChangeListener listener) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        Preconditions.checkNotNull(listener, "listener cannot be null");

        try {
            final int nonce = mObbActionListener.addListener(listener);
            mMountService.unmountObb(rawPath, force, mObbActionListener, nonce);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to mount OBB", e);
        }

        return false;
    }

    /**
     * Check whether an Opaque Binary Blob (OBB) is mounted or not.
     * 
     * @param rawPath path to OBB image
     * @return true if OBB is mounted; false if not mounted or on error
     */
    public boolean isObbMounted(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        try {
            return mMountService.isObbMounted(rawPath);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to check if OBB is mounted", e);
        }

        return false;
    }

    /**
     * Check the mounted path of an Opaque Binary Blob (OBB) file. This will
     * give you the path to where you can obtain access to the internals of the
     * OBB.
     * 
     * @param rawPath path to OBB image
     * @return absolute path to mounted OBB image data or <code>null</code> if
     *         not mounted or exception encountered trying to read status
     */
    public String getMountedObbPath(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        try {
            return mMountService.getMountedObbPath(rawPath);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to find mounted path for OBB", e);
        }

        return null;
    }

    /**
     * Gets the state of a volume via its mountpoint.
     * @hide
     */
    public String getVolumeState(String mountPoint) {
        if (mountPoint.equals(ICS_STORAGE_PATH_SD1)) {
            Log.d(TAG,"For backwards compatibility, replace " + mountPoint + 
                " to /storage/sdcard0");
		 	mountPoint = STORAGE_PATH_SD1;
        } else if (mountPoint.equals(ICS_STORAGE_PATH_SD2)) {
            Log.d(TAG,"For backwards compatibility, replace " + mountPoint + 
                " to /storage/sdcard1");
            mountPoint = STORAGE_PATH_SD2;
        }
        
        if (mMountService == null) return Environment.MEDIA_REMOVED;
        try {
            return mMountService.getVolumeState(mountPoint);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get volume state", e);
            return null;
        }
    }

    /**
     * Returns list of all mountable volumes.
     * @hide
     */
    public StorageVolume[] getVolumeList() {
        if (mMountService == null) return new StorageVolume[0];
        try {
            Parcelable[] list = mMountService.getVolumeList();
            if (list == null) return new StorageVolume[0];
            int length = list.length;
            StorageVolume[] result = new StorageVolume[length];
            for (int i = 0; i < length; i++) {
                result[i] = (StorageVolume)list[i];
            }
            return result;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get volume list", e);
            return null;
        }
    }

    /**
     * Returns list of paths for all mountable volumes.
     * @hide
     */
    public String[] getVolumePaths() {
        StorageVolume[] volumes = getVolumeList();
        if (volumes == null) return null;
        int count = volumes.length;
        String[] paths = new String[count];
        for (int i = 0; i < count; i++) {
            paths[i] = volumes[i].getPath();
        }
        return paths;
    }

    /** {@hide} */
    public StorageVolume getPrimaryVolume() {
        return getPrimaryVolume(getVolumeList());
    }

    /** {@hide} */
    public static StorageVolume getPrimaryVolume(StorageVolume[] volumes) {
        for (StorageVolume volume : volumes) {
            if (volume.isPrimary()) {
                return volume;
            }
        }
        Log.w(TAG, "No primary storage defined");
        return null;
    }

    /// M: New APIs @{
    /**
     * Returns default path for writing.
     * @hide
     */
    public static String getDefaultPath() {
        String path = STORAGE_PATH_SD1;
        
        try {
            path = SystemProperties.get(PROP_SD_DEFAULT_PATH, path);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "IllegalArgumentException when get default path:" + e);
        }

        // MOTA upgrade from ICS to JB, update DefaultPath to JB design
        if (path.equals(ICS_STORAGE_PATH_SD1)) {
            path = STORAGE_PATH_SD1;
            setDefaultPath(path);
        } else if (path.equals(ICS_STORAGE_PATH_SD2)) {
            path = STORAGE_PATH_SD2;
            setDefaultPath(path);
        }

        Log.i(TAG, "getDefaultPath path=" + path);
        return path;
    }

    /**
     * set default path for APP to storage data.
     * this ONLY can used by settings.
     * @hide
     */
    public static void setDefaultPath(String path) {
        Log.i(TAG, "setDefaultPath path=" + path);
        if (path == null) {
            Log.e(TAG, "setDefaultPath error! path=null");
            return;
        }

        try {
            SystemProperties.set(PROP_SD_DEFAULT_PATH, path);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "IllegalArgumentException when set default path:" + e);
        }
    }

    /**
     * Generates the path to Gallery.
     * @hide
     */
    public File getMTKExternalCacheDir(String packageName) {
        if (null == packageName) {
            Log.w(TAG, "packageName = null!");
            return null;
        }
        String path = getDefaultPath();
        if (path.equals(Environment.getExternalStorageDirectory().getPath())) {
            mMTKExternalCacheDir = Environment.getExternalStorageAppCacheDirectory(
                    packageName);
        if (!mMTKExternalCacheDir.exists()) {
                try {
                    (new File(Environment.getExternalStorageAndroidDataDir(),
                            ".nomedia")).createNewFile();
                } catch (IOException e) {
                    // do nothing
                }
                if (!mMTKExternalCacheDir.mkdirs()) {
                    Log.w(TAG, "Unable to create external cache directory");
                    return null;
                }
            }
        } else {
            mMTKExternalCacheDir = new File(new File(new File(new File(new File(path), 
                "Android"), "data"), packageName), "cache");
            if (!mMTKExternalCacheDir.exists()) {
                try {
                    (new File(new File(new File(new File(path),
                    "Android"), "data"), ".nomedia")).createNewFile();
                } catch (IOException e) {
                    // do nothing
                }
                if (!mMTKExternalCacheDir.mkdirs()) {
                    Log.w(TAG, "Unable to create external cache directory");
                    return null;
                }
            }
        }
        return mMTKExternalCacheDir;
    }

    /**
        * Returns external SD card path.
        * @hide
        */
    public String getExternalStoragePath() {
        String path = null;
        try {
            path = SystemProperties.get(PROP_SD_EXTERNAL_PATH);
            Log.i(TAG, "getExternalStoragePath path=" + path);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "IllegalArgumentException when getExternalStoragePath:" + e);
        }
        /*
        StorageVolume[] volumes = getVolumeList();
        for (int i = 0; i < volumes.length; i++) {
            if (volumes[i].isRemovable() && !volumes[i].getPath().equals("/mnt/usbotg")) {
                path = volumes[i].getPath();
                break;
            }
        }*/
        return path ;
    }

    /**
        * Returns internal Storage path.
        * @hide
        */
    public String getInternalStoragePath() {
        String path = null;
        try {
            path = SystemProperties.get(PROP_SD_INTERNAL_PATH);
            Log.i(TAG, "getInternalStoragePath from Property path=" + path);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "IllegalArgumentException when getInternalStoragePath:" + e);
        }
        if (FeatureOption.MTK_SHARED_SDCARD && STORAGE_PATH_SD1.equals(path)) {
            if (Process.myUid() == Process.SYSTEM_UID) {
                path = "/storage/emulated/"+Integer.toString(Process.SYSTEM_UID);
            } else {
                path = Environment.getExternalStorageDirectory().toString();
            }
        }
        Log.i(TAG, "getInternalStoragePath path=" + path);
        /*
        StorageVolume[] volumes = getVolumeList();
        for (int i = 0; i < volumes.length; i++) {
            if (!volumes[i].isRemovable()) {
                path = volumes[i].getPath();
                break ;
            }
        }*/
        return path ;
    }
    /// @}
}