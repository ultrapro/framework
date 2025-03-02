/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import android.Manifest;
import android.animation.LayoutTransition;
import android.app.ActivityManagerNative;
import android.content.ClipDescription;
import android.content.ComponentCallbacks;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.LatencyTimer;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.text.format.DateFormat;
import android.util.AndroidRuntimeException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View.AttachInfo;
import android.view.View.MeasureSpec;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Scroller;

import com.android.internal.R;
import com.android.internal.os.SomeArgs;
import com.android.internal.policy.PolicyManager;
import com.android.internal.view.BaseSurfaceHolder;
import com.android.internal.view.RootViewSurfaceTaker;

import com.mediatek.xlog.Xlog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;

/**
 * The top of a view hierarchy, implementing the needed protocol between View
 * and the WindowManager.  This is for the most part an internal implementation
 * detail of {@link WindowManagerGlobal}.
 *
 * {@hide}
 */
@SuppressWarnings({"EmptyCatchBlock", "PointlessBooleanExpression"})
public final class ViewRootImpl implements ViewParent,
        View.AttachInfo.Callbacks, HardwareRenderer.HardwareDrawCallbacks {
    private static final String TAG = "ViewRootImpl";
    /** M: Let DEBUG flags writable and add input event debug flag. @{ */
    private static boolean DBG = false;
    private static boolean LOCAL_LOGV = false;
    /** @noinspection PointlessBooleanExpression*/
    private static boolean DEBUG_DRAW = false || LOCAL_LOGV;
    private static boolean DEBUG_LAYOUT = false || LOCAL_LOGV;
    private static boolean DEBUG_DIALOG = false || LOCAL_LOGV;
    private static boolean DEBUG_INPUT_RESIZE = false || LOCAL_LOGV;
    private static boolean DEBUG_ORIENTATION = false || LOCAL_LOGV;
    private static boolean DEBUG_TRACKBALL = false || LOCAL_LOGV;
    private static boolean DEBUG_IMF = false || LOCAL_LOGV;
    private static boolean DEBUG_CONFIGURATION = false || LOCAL_LOGV;
    private static boolean DEBUG_FPS = false;
    private static boolean DEBUG_HWUI = false;
    private static boolean DEBUG_INPUT = false;
    private static boolean DEBUG_KEY = false || DEBUG_INPUT;
    private static boolean DEBUG_MOTION = false || DEBUG_INPUT;
    // For debugging IME caused key dispatch timeout ANR.
    private static boolean DEBUG_IME_ANR = false;    
    private static boolean DEBUG_LIFECYCLE = false || LOCAL_LOGV;
    /** @} */

    /** M: Add debug flags to partial enable logs. @{ */
    private static final String LOG_PROPERTY_NAME = "debug.viewroot.enable";
    
    private static final int DEBUG_ENABLE_ALL_FLAG = 0x00000001;
    private static final int DEBUG_DRAW_FLAG = 0x00000002;
    private static final int DEBUG_LAYOUT_FLAG = 0x00000004;
    private static final int DEBUG_DIALOG_FLAG = 0x00000008;
    private static final int DEBUG_INPUT_RESIZE_FLAG = 0x00000010;
    private static final int DEBUG_ORIENTATION_FLAG = 0x00000020;
    private static final int DEBUG_TRACKBALL_FLAG = 0x00000040;
    private static final int DEBUG_IMF_FLAG = 0x00000080;
    private static final int DEBUG_CONFIGURATION_FLAG = 0x00000100;
    private static final int DEBUG_DEFAULT_FLAG = 0x00000200;
    private static final int DEBUG_FPS_FLAG = 0x00000400;
    private static final int DEBUG_HWUI_FLAG = 0x00000800;
    private static final int DEBUG_INPUT_FLAG = 0x00001000;
    private static final int DEBUG_KEY_FLAG = 0x00002000;
    private static final int DEBUG_MOTION_FLAG = 0x00004000;
    private static final int DEBUG_IME_ANR_FLAG = 0x00008000;
    private static final int DEBUG_LIFECYCLE_FLAG = 0x00010000;
    /** @} */

    private static final boolean USE_RENDER_THREAD = false;

    /**
     * Set this system property to true to force the view hierarchy to render
     * at 60 Hz. This can be used to measure the potential framerate.
     */
    private static final String PROPERTY_PROFILE_RENDERING = "viewancestor.profile_rendering";    
    
    private static final boolean MEASURE_LATENCY = false;
    private static LatencyTimer lt;

    /**
     * Maximum time we allow the user to roll the trackball enough to generate
     * a key event, before resetting the counters.
     */
    static final int MAX_TRACKBALL_DELAY = 250;

    static final ThreadLocal<RunQueue> sRunQueues = new ThreadLocal<RunQueue>();

    static final ArrayList<Runnable> sFirstDrawHandlers = new ArrayList<Runnable>();
    static boolean sFirstDrawComplete = false;
    
    static final ArrayList<ComponentCallbacks> sConfigCallbacks
            = new ArrayList<ComponentCallbacks>();

    private static boolean sUseRenderThread = false;
    private static boolean sRenderThreadQueried = false;
    private static final Object[] sRenderThreadQueryLock = new Object[0];

    final IWindowSession mWindowSession;
    final Display mDisplay;

    long mLastTrackballTime = 0;
    final TrackballAxis mTrackballAxisX = new TrackballAxis();
    final TrackballAxis mTrackballAxisY = new TrackballAxis();

    int mLastJoystickXDirection;
    int mLastJoystickYDirection;
    int mLastJoystickXKeyCode;
    int mLastJoystickYKeyCode;

    final int[] mTmpLocation = new int[2];

    final TypedValue mTmpValue = new TypedValue();
    
    final InputMethodCallback mInputMethodCallback;
    final Thread mThread;

    final WindowLeaked mLocation;

    final WindowManager.LayoutParams mWindowAttributes = new WindowManager.LayoutParams();

    final W mWindow;

    final int mTargetSdkVersion;

    int mSeq;

    View mView;
    View mFocusedView;
    View mRealFocusedView;  // this is not set to null in touch mode
    View mOldFocusedView;

    View mAccessibilityFocusedHost;
    AccessibilityNodeInfo mAccessibilityFocusedVirtualView;

    int mViewVisibility;
    boolean mAppVisible = true;
    int mOrigWindowType = -1;

    // Set to true if the owner of this window is in the stopped state,
    // so the window should no longer be active.
    boolean mStopped = false;
    
    boolean mLastInCompatMode = false;

    SurfaceHolder.Callback2 mSurfaceHolderCallback;
    BaseSurfaceHolder mSurfaceHolder;
    boolean mIsCreating;
    boolean mDrawingAllowed;
    
    final Region mTransparentRegion;
    final Region mPreviousTransparentRegion;

    int mWidth;
    int mHeight;
    Rect mDirty;
    final Rect mCurrentDirty = new Rect();
    final Rect mPreviousDirty = new Rect();
    boolean mIsAnimating;

    CompatibilityInfo.Translator mTranslator;

    final View.AttachInfo mAttachInfo;
    InputChannel mInputChannel;
    InputQueue.Callback mInputQueueCallback;
    InputQueue mInputQueue;
    FallbackEventHandler mFallbackEventHandler;
    Choreographer mChoreographer;
    
    final Rect mTempRect; // used in the transaction to not thrash the heap.
    final Rect mVisRect; // used to retrieve visible rect of focused view.

    boolean mTraversalScheduled;
    int mTraversalBarrier;
    boolean mWillDrawSoon;
    /** Set to true while in performTraversals for detecting when die(true) is called from internal
     * callbacks such as onMeasure, onPreDraw, onDraw and deferring doDie() until later. */
    boolean mIsInTraversal;
    boolean mFitSystemWindowsRequested;
    boolean mLayoutRequested;
    boolean mFirst;
    boolean mReportNextDraw;
    boolean mFullRedrawNeeded;
    boolean mNewSurfaceNeeded;
    boolean mHasHadWindowFocus;
    boolean mLastWasImTarget;
    boolean mWindowsAnimating;
    boolean mIsDrawing;
    int mLastSystemUiVisibility;
    int mClientWindowLayoutFlags;

    // Pool of queued input events.
    private static final int MAX_QUEUED_INPUT_EVENT_POOL_SIZE = 10;
    private QueuedInputEvent mQueuedInputEventPool;
    private int mQueuedInputEventPoolSize;

    // Input event queue.
    QueuedInputEvent mFirstPendingInputEvent;
    QueuedInputEvent mCurrentInputEvent;
    boolean mProcessInputEventsScheduled;

    boolean mWindowAttributesChanged = false;
    int mWindowAttributesChangesFlag = 0;

    // These can be accessed by any thread, must be protected with a lock.
    // Surface can never be reassigned or cleared (use Surface.clear()).
    private final Surface mSurface = new Surface();

    boolean mAdded;
    boolean mAddedTouchMode;

    final CompatibilityInfoHolder mCompatibilityInfo;

    // These are accessed by multiple threads.
    final Rect mWinFrame; // frame given by window manager.

    final Rect mPendingVisibleInsets = new Rect();
    final Rect mPendingContentInsets = new Rect();
    final ViewTreeObserver.InternalInsetsInfo mLastGivenInsets
            = new ViewTreeObserver.InternalInsetsInfo();

    final Rect mFitSystemWindowsInsets = new Rect();

    final Configuration mLastConfiguration = new Configuration();
    final Configuration mPendingConfiguration = new Configuration();

    boolean mScrollMayChange;
    int mSoftInputMode;
    View mLastScrolledFocus;
    int mScrollY;
    int mCurScrollY;
    Scroller mScroller;
    HardwareLayer mResizeBuffer;
    long mResizeBufferStartTime;
    int mResizeBufferDuration;
    static final Interpolator mResizeInterpolator = new AccelerateDecelerateInterpolator();
    private ArrayList<LayoutTransition> mPendingTransitions;

    final ViewConfiguration mViewConfiguration;

    /* Drag/drop */
    ClipDescription mDragDescription;
    View mCurrentDragView;
    volatile Object mLocalDragState;
    final PointF mDragPoint = new PointF();
    final PointF mLastTouchPoint = new PointF();
    
    private boolean mProfileRendering;    
    private Thread mRenderProfiler;
    private volatile boolean mRenderProfilingEnabled;

    // Variables to track frames per second, enabled via DEBUG_FPS flag
    private long mFpsStartTime = -1;
    private long mFpsPrevTime = -1;
    private int mFpsNumFrames;

    private final ArrayList<DisplayList> mDisplayLists = new ArrayList<DisplayList>(24);
    
    /**
     * see {@link #playSoundEffect(int)}
     */
    AudioManager mAudioManager;

    final AccessibilityManager mAccessibilityManager;

    AccessibilityInteractionController mAccessibilityInteractionController;

    AccessibilityInteractionConnectionManager mAccessibilityInteractionConnectionManager;

    SendWindowContentChangedAccessibilityEvent mSendWindowContentChangedAccessibilityEvent;

    HashSet<View> mTempHashSet;

    private final int mDensity;
    private final int mNoncompatDensity;

    private int mViewLayoutDirectionInitial;

    /**
     * Consistency verifier for debugging purposes.
     */
    protected final InputEventConsistencyVerifier mInputEventConsistencyVerifier =
            InputEventConsistencyVerifier.isInstrumentationEnabled() ?
                    new InputEventConsistencyVerifier(this, 0) : null;

    static final class SystemUiVisibilityInfo {
        int seq;
        int globalVisibility;
        int localValue;
        int localChanges;
    }

    /// M: Notified by IMM if soft input may changed.
    private boolean mSoftInputMayChanged;

    /// M: add constant value for log property.
    private static final int LOG_DISABLED = 0;
    private static final int LOG_ENABLED = 1;

    /// M: instance id used to identify each ViewRootImpl instance.
    private static long sIdent = 0;
    private long mIdent;

    /// M: dump file path, name and format of the dump image.
    private static final String DUMP_IMAGE_PTAH = "/data/dump/";
    private static final String DATE_FORMAT_STRING = "yyyyMMdd_hhmmss";
    private static final String DUMP_IMAGE_FORMAT = ".png";
    
    public ViewRootImpl(Context context, Display display) {
        super();

        if (MEASURE_LATENCY) {
            if (lt == null) {
                lt = new LatencyTimer(100, 1000);
            }
        }

        // Initialize the statics when this class is first instantiated. This is
        // done here instead of in the static block because Zygote does not
        // allow the spawning of threads.
        mWindowSession = WindowManagerGlobal.getWindowSession(context.getMainLooper());
        mDisplay = display;

        CompatibilityInfoHolder cih = display.getCompatibilityInfo();
        mCompatibilityInfo = cih != null ? cih : new CompatibilityInfoHolder();

        mThread = Thread.currentThread();
        mLocation = new WindowLeaked(null);
        mLocation.fillInStackTrace();
        mWidth = -1;
        mHeight = -1;
        mDirty = new Rect();
        mTempRect = new Rect();
        mVisRect = new Rect();
        mWinFrame = new Rect();
        mWindow = new W(this);
        mTargetSdkVersion = context.getApplicationInfo().targetSdkVersion;
        mInputMethodCallback = new InputMethodCallback(this);
        mViewVisibility = View.GONE;
        mTransparentRegion = new Region();
        mPreviousTransparentRegion = new Region();
        mFirst = true; // true for the first time the view is added
        mAdded = false;
        mAccessibilityManager = AccessibilityManager.getInstance(context);
        mAccessibilityInteractionConnectionManager =
            new AccessibilityInteractionConnectionManager();
        mAccessibilityManager.addAccessibilityStateChangeListener(
                mAccessibilityInteractionConnectionManager);
        mAttachInfo = new View.AttachInfo(mWindowSession, mWindow, display, this, mHandler, this);
        mViewConfiguration = ViewConfiguration.get(context);
        mDensity = context.getResources().getDisplayMetrics().densityDpi;
        mNoncompatDensity = context.getResources().getDisplayMetrics().noncompatDensityDpi;
        mFallbackEventHandler = PolicyManager.makeNewFallbackEventHandler(context);
        mProfileRendering = Boolean.parseBoolean(
                SystemProperties.get(PROPERTY_PROFILE_RENDERING, "false"));
        mChoreographer = Choreographer.getInstance();

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mAttachInfo.mScreenOn = powerManager.isScreenOn();
        loadSystemProperties();

        /**
         * M: increase instance count and check log property to determine
         * whether to enable/disable log system. @{
         */
        mIdent = sIdent++;
        checkViewRootImplLogProperty();
        if (LOCAL_LOGV) {
            enableLog(true);
        }

        if (DEBUG_LIFECYCLE) {
            Xlog.v(TAG, "ViewRootImpl construct: context = " + context + ", mThread = " + mThread
                    + ", mChoreographer = " + mChoreographer + ", mTraversalRunnable = "
                    + mTraversalRunnable + ", this = " + this);
        }    
        /** @} */
    }

    /**
     * @return True if the application requests the use of a separate render thread,
     *         false otherwise
     */
    private static boolean isRenderThreadRequested(Context context) {
        if (USE_RENDER_THREAD) {
            synchronized (sRenderThreadQueryLock) {
                if (!sRenderThreadQueried) {
                    final PackageManager packageManager = context.getPackageManager();
                    final String packageName = context.getApplicationInfo().packageName;
                    try {
                        ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName,
                                PackageManager.GET_META_DATA);
                        if (applicationInfo.metaData != null) {
                            sUseRenderThread = applicationInfo.metaData.getBoolean(
                                    "android.graphics.renderThread", false);
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                    } finally {
                        sRenderThreadQueried = true;
                    }
                }
                return sUseRenderThread;
            }
        } else {
            return false;
        }
    }

    public static void addFirstDrawHandler(Runnable callback) {
        synchronized (sFirstDrawHandlers) {
            if (!sFirstDrawComplete) {
                sFirstDrawHandlers.add(callback);
            }
        }
    }
    
    public static void addConfigCallback(ComponentCallbacks callback) {
        synchronized (sConfigCallbacks) {
            sConfigCallbacks.add(callback);
        }
    }
    
    // FIXME for perf testing only
    private boolean mProfile = false;

    /**
     * Call this to profile the next traversal call.
     * FIXME for perf testing only. Remove eventually
     */
    public void profile() {
        mProfile = true;
    }

    /**
     * Indicates whether we are in touch mode. Calling this method triggers an IPC
     * call and should be avoided whenever possible.
     *
     * @return True, if the device is in touch mode, false otherwise.
     *
     * @hide
     */
    static boolean isInTouchMode() {
        IWindowSession windowSession = WindowManagerGlobal.peekWindowSession();
        if (windowSession != null) {
            try {
                return windowSession.getInTouchMode();
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    /**
     * We have one child
     */
    public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView) {
        synchronized (this) {
            if (mView == null) {
                mView = view;
                mViewLayoutDirectionInitial = mView.getRawLayoutDirection();
                mFallbackEventHandler.setView(view);
                mWindowAttributes.copyFrom(attrs);
                attrs = mWindowAttributes;
                // Keep track of the actual window flags supplied by the client.
                mClientWindowLayoutFlags = attrs.flags;

                setAccessibilityFocus(null, null);

                if (view instanceof RootViewSurfaceTaker) {
                    mSurfaceHolderCallback =
                            ((RootViewSurfaceTaker)view).willYouTakeTheSurface();
                    if (mSurfaceHolderCallback != null) {
                        mSurfaceHolder = new TakenSurfaceHolder();
                        mSurfaceHolder.setFormat(PixelFormat.UNKNOWN);
                    }
                }

                CompatibilityInfo compatibilityInfo = mCompatibilityInfo.get();
                mTranslator = compatibilityInfo.getTranslator();

                // If the application owns the surface, don't enable hardware acceleration
                if (mSurfaceHolder == null) {
                    enableHardwareAcceleration(mView.getContext(), attrs);
                }

                boolean restore = false;
                if (mTranslator != null) {
                    mSurface.setCompatibilityTranslator(mTranslator);
                    restore = true;
                    attrs.backup();
                    mTranslator.translateWindowLayout(attrs);
                }
                if (DEBUG_LAYOUT || DEBUG_LIFECYCLE) {
                    Xlog.d(TAG, "WindowLayout in setView:" + attrs + ",mView = " + mView
                            + ",compatibilityInfo = " + compatibilityInfo + ", this = " + this);
                }

                if (!compatibilityInfo.supportsScreen()) {
                    attrs.flags |= WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
                    mLastInCompatMode = true;
                }

                mSoftInputMode = attrs.softInputMode;
                mWindowAttributesChanged = true;
                mWindowAttributesChangesFlag = WindowManager.LayoutParams.EVERYTHING_CHANGED;
                mAttachInfo.mRootView = view;
                mAttachInfo.mScalingRequired = mTranslator != null;
                mAttachInfo.mApplicationScale =
                        mTranslator == null ? 1.0f : mTranslator.applicationScale;
                if (panelParentView != null) {
                    mAttachInfo.mPanelParentWindowToken
                            = panelParentView.getApplicationWindowToken();
                }
                mAdded = true;
                int res; /* = WindowManagerImpl.ADD_OKAY; */

                // Schedule the first layout -before- adding to the window
                // manager, to make sure we do the relayout before receiving
                // any other events from the system.
                requestLayout();
                if ((mWindowAttributes.inputFeatures
                        & WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0) {
                    mInputChannel = new InputChannel();
                }
                try {
                    mOrigWindowType = mWindowAttributes.type;
                    mAttachInfo.mRecomputeGlobalAttributes = true;
                    collectViewAttributes();
                    res = mWindowSession.addToDisplay(mWindow, mSeq, mWindowAttributes,
                            getHostVisibility(), mDisplay.getDisplayId(),
                            mAttachInfo.mContentInsets, mInputChannel);
                } catch (RemoteException e) {
                    mAdded = false;
                    mView = null;
                    mAttachInfo.mRootView = null;
                    mInputChannel = null;
                    mFallbackEventHandler.setView(null);
                    unscheduleTraversals();
                    setAccessibilityFocus(null, null);
                    throw new RuntimeException("Adding window failed", e);
                } finally {
                    if (restore) {
                        attrs.restore();
                    }
                }
                
                if (mTranslator != null) {
                    mTranslator.translateRectInScreenToAppWindow(mAttachInfo.mContentInsets);
                }
                mPendingContentInsets.set(mAttachInfo.mContentInsets);
                mPendingVisibleInsets.set(0, 0, 0, 0);
                if (DEBUG_LAYOUT) {
                    Xlog.v(TAG, "Added window " + mWindow + ", mPendingContentInsets = "
                            + mPendingContentInsets + ", this = " + this);
                }

                if (res < WindowManagerGlobal.ADD_OKAY) {
                    mAttachInfo.mRootView = null;
                    mAdded = false;
                    mFallbackEventHandler.setView(null);
                    unscheduleTraversals();
                    setAccessibilityFocus(null, null);
                    switch (res) {
                        case WindowManagerGlobal.ADD_BAD_APP_TOKEN:
                        case WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN:
                            throw new WindowManager.BadTokenException(
                                "Unable to add window -- token " + attrs.token
                                + " is not valid; is your activity running?");
                        case WindowManagerGlobal.ADD_NOT_APP_TOKEN:
                            throw new WindowManager.BadTokenException(
                                "Unable to add window -- token " + attrs.token
                                + " is not for an application");
                        case WindowManagerGlobal.ADD_APP_EXITING:
                            throw new WindowManager.BadTokenException(
                                "Unable to add window -- app for token " + attrs.token
                                + " is exiting");
                        case WindowManagerGlobal.ADD_DUPLICATE_ADD:
                            throw new WindowManager.BadTokenException(
                                "Unable to add window -- window " + mWindow
                                + " has already been added");
                        case WindowManagerGlobal.ADD_STARTING_NOT_NEEDED:
                            // Silently ignore -- we would have just removed it
                            // right away, anyway.
                            return;
                        case WindowManagerGlobal.ADD_MULTIPLE_SINGLETON:
                            throw new WindowManager.BadTokenException(
                                "Unable to add window " + mWindow +
                                " -- another window of this type already exists");
                        case WindowManagerGlobal.ADD_PERMISSION_DENIED:
                            throw new WindowManager.BadTokenException(
                                "Unable to add window " + mWindow +
                                " -- permission denied for this window type");
                        case WindowManagerGlobal.ADD_INVALID_DISPLAY:
                            throw new WindowManager.InvalidDisplayException(
                                "Unable to add window " + mWindow +
                                " -- the specified display can not be found");
                        /** M: [ALPS00044207] @{ */
                        case WindowManagerGlobal.ADD_INPUTCHANNEL_NOT_ALLOWED:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window " + mWindow +
                                " -- input channel is not allowed");
                        /** @} */
                    }
                    throw new RuntimeException(
                        "Unable to add window -- unknown error code " + res);
                }

                if (view instanceof RootViewSurfaceTaker) {
                    mInputQueueCallback =
                        ((RootViewSurfaceTaker)view).willYouTakeTheInputQueue();
                }
                if (mInputChannel != null) {
                    if (mInputQueueCallback != null) {
                        mInputQueue = new InputQueue(mInputChannel);
                        mInputQueueCallback.onInputQueueCreated(mInputQueue);
                    } else {
                        mInputEventReceiver = new WindowInputEventReceiver(mInputChannel,
                                Looper.myLooper());
                    }
                }

                view.assignParent(this);
                mAddedTouchMode = (res & WindowManagerGlobal.ADD_FLAG_IN_TOUCH_MODE) != 0;
                mAppVisible = (res & WindowManagerGlobal.ADD_FLAG_APP_VISIBLE) != 0;

                if (mAccessibilityManager.isEnabled()) {
                    mAccessibilityInteractionConnectionManager.ensureConnection();
                }

                if (view.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
                    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                }
            }
        }
    }

    void destroyHardwareResources() {
        if (DEBUG_HWUI && isTargetPackage()) {
            Xlog.v(TAG, "destroyHardwareResources: mAttachInfo.mHardwareRenderer = " + mAttachInfo.mHardwareRenderer
                    + ",mView = " + mView + ",this = " + this, new Throwable("destroyHardwareResources"));
        }

        if (mAttachInfo.mHardwareRenderer != null) {
            if (mAttachInfo.mHardwareRenderer.isEnabled()) {
                mAttachInfo.mHardwareRenderer.destroyLayers(mView);
            }
            mAttachInfo.mHardwareRenderer.destroy(false);
        }
    }

    void terminateHardwareResources() {
        if (DEBUG_HWUI && isTargetPackage()) {
            final boolean isEnabled = mAttachInfo.mHardwareRenderer.isEnabled();
            Xlog.v(TAG, "terminateHardwareResources: mAttachInfo.mHardwareRenderer = " + mAttachInfo.mHardwareRenderer
                    + ",mView = " + mView + ",isEnabled = " + isEnabled + ",this = " + this, new Throwable("terminateHardwareResources"));
        }

        if (mAttachInfo.mHardwareRenderer != null) {
            /// M: make all view hardware layers can be destroyed.
            mAttachInfo.mHardwareRenderer.setForceDestroyLayers(true);
            mAttachInfo.mHardwareRenderer.destroyHardwareResources(mView);
            mAttachInfo.mHardwareRenderer.setForceDestroyLayers(false);

            mAttachInfo.mHardwareRenderer.destroy(false);
        }
    }

    void destroyHardwareLayers() {
        if (DEBUG_HWUI && isTargetPackage()) {
            Xlog.v(TAG, "destroyHardwareLayers enter: mAttachInfo.mHardwareRenderer = " + mAttachInfo.mHardwareRenderer
                    + ",mView = " + mView + ",mThread = " + mThread + ",current = " + Thread.currentThread() + ",enable = "
                    + (mAttachInfo.mHardwareRenderer != null ? mAttachInfo.mHardwareRenderer.isEnabled() : "null")
                    + ",this = " + this, new Throwable("destroyHardwareLayers"));
        }

        if (mThread != Thread.currentThread()) {
            if (mAttachInfo.mHardwareRenderer != null &&
                    mAttachInfo.mHardwareRenderer.isEnabled()) {
                HardwareRenderer.trimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE);
            }
        } else {
            if (mAttachInfo.mHardwareRenderer != null &&
                    mAttachInfo.mHardwareRenderer.isEnabled()) {
                mAttachInfo.mHardwareRenderer.destroyLayers(mView);
            }
        }
    }

    void pushHardwareLayerUpdate(HardwareLayer layer) {
        if (mAttachInfo.mHardwareRenderer != null && mAttachInfo.mHardwareRenderer.isEnabled()) {
            mAttachInfo.mHardwareRenderer.pushLayerUpdate(layer);
        }
    }

    public boolean attachFunctor(int functor) {
        //noinspection SimplifiableIfStatement
        if (mAttachInfo.mHardwareRenderer != null && mAttachInfo.mHardwareRenderer.isEnabled()) {
            return mAttachInfo.mHardwareRenderer.attachFunctor(mAttachInfo, functor);
        }
        return false;
    }

    public void detachFunctor(int functor) {
        if (mAttachInfo.mHardwareRenderer != null) {
            mAttachInfo.mHardwareRenderer.detachFunctor(functor);
        }
    }

    private void enableHardwareAcceleration(Context context, WindowManager.LayoutParams attrs) {
        mAttachInfo.mHardwareAccelerated = false;
        mAttachInfo.mHardwareAccelerationRequested = false;

        // Don't enable hardware acceleration when the application is in compatibility mode
        if (mTranslator != null) return;

        // Try to enable hardware acceleration if requested
        final boolean hardwareAccelerated = 
                (attrs.flags & WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0;

        if (hardwareAccelerated) {
            if (!HardwareRenderer.isAvailable()) {
                return;
            }

            // Persistent processes (including the system) should not do
            // accelerated rendering on low-end devices.  In that case,
            // sRendererDisabled will be set.  In addition, the system process
            // itself should never do accelerated rendering.  In that case, both
            // sRendererDisabled and sSystemRendererDisabled are set.  When
            // sSystemRendererDisabled is set, PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED
            // can be used by code on the system process to escape that and enable
            // HW accelerated drawing.  (This is basically for the lock screen.)

            final boolean fakeHwAccelerated = (attrs.privateFlags &
                    WindowManager.LayoutParams.PRIVATE_FLAG_FAKE_HARDWARE_ACCELERATED) != 0;
            final boolean forceHwAccelerated = (attrs.privateFlags &
                    WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED) != 0;

            if (!HardwareRenderer.sRendererDisabled || (HardwareRenderer.sSystemRendererDisabled
                    && forceHwAccelerated)) {
                // Don't enable hardware acceleration when we're not on the main thread
                if (!HardwareRenderer.sSystemRendererDisabled &&
                        Looper.getMainLooper() != Looper.myLooper()) {
                    Log.w(HardwareRenderer.LOG_TAG, "Attempting to initialize hardware " 
                            + "acceleration outside of the main thread, aborting");
                    return;
                }

                final boolean renderThread = isRenderThreadRequested(context);
                if (renderThread) {
                    Log.i(HardwareRenderer.LOG_TAG, "Render threat initiated");
                }

                if (mAttachInfo.mHardwareRenderer != null) {
                    mAttachInfo.mHardwareRenderer.destroy(true);
                }

                final boolean translucent = attrs.format != PixelFormat.OPAQUE;
                mAttachInfo.mHardwareRenderer = HardwareRenderer.createGlRenderer(2, translucent);
                mAttachInfo.mHardwareAccelerated = mAttachInfo.mHardwareAccelerationRequested
                        = mAttachInfo.mHardwareRenderer != null;

            } else if (fakeHwAccelerated) {
                // The window had wanted to use hardware acceleration, but this
                // is not allowed in its process.  By setting this flag, it can
                // still render as if it was accelerated.  This is basically for
                // the preview windows the window manager shows for launching
                // applications, so they will look more like the app being launched.
                mAttachInfo.mHardwareAccelerationRequested = true;
            }
        }
    }

    public View getView() {
        return mView;
    }

    final WindowLeaked getLocation() {
        return mLocation;
    }

    void setLayoutParams(WindowManager.LayoutParams attrs, boolean newView) {
        synchronized (this) {
            int oldSoftInputMode = mWindowAttributes.softInputMode;
            // Keep track of the actual window flags supplied by the client.
            mClientWindowLayoutFlags = attrs.flags;
            // preserve compatible window flag if exists.
            int compatibleWindowFlag =
                mWindowAttributes.flags & WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
            // transfer over system UI visibility values as they carry current state.
            attrs.systemUiVisibility = mWindowAttributes.systemUiVisibility;
            attrs.subtreeSystemUiVisibility = mWindowAttributes.subtreeSystemUiVisibility;
            mWindowAttributesChangesFlag = mWindowAttributes.copyFrom(attrs);
            mWindowAttributes.flags |= compatibleWindowFlag;

            applyKeepScreenOnFlag(mWindowAttributes);

            if (newView) {
                mSoftInputMode = attrs.softInputMode;
                requestLayout();
            }
            // Don't lose the mode we last auto-computed.
            if ((attrs.softInputMode&WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                    == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
                mWindowAttributes.softInputMode = (mWindowAttributes.softInputMode
                        & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                        | (oldSoftInputMode
                                & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            }
            mWindowAttributesChanged = true;
            scheduleTraversals();
        }
        if (DEBUG_IMF) {
            Xlog.d(TAG, "setLayoutParams: attrs = " + attrs + ", mSoftInputMode = " + mSoftInputMode
                    + ", mWindowAttributes = " + mWindowAttributes + ", this = " + this);
        }
    }

    void handleAppVisibility(boolean visible) {
        if (DEBUG_DRAW || DEBUG_LAYOUT) {
            Xlog.d(TAG, "handleAppVisibility: visible=" + visible + ", mAppVisible=" + mAppVisible
                    + ", this = " + this);
        }

        if (mAppVisible != visible) {
            mAppVisible = visible;
            scheduleTraversals();
        }
    }

    void handleGetNewSurface() {
        mNewSurfaceNeeded = true;
        mFullRedrawNeeded = true;
        scheduleTraversals();
    }

    void handleScreenStateChange(boolean on) {
        if (on != mAttachInfo.mScreenOn) {
            mAttachInfo.mScreenOn = on;
            if (mView != null) {
                mView.dispatchScreenStateChanged(on ? View.SCREEN_STATE_ON : View.SCREEN_STATE_OFF);
            }
            if (on) {
                mFullRedrawNeeded = true;
                scheduleTraversals();
            }
        }
    }

    @Override
    public void requestFitSystemWindows() {
        checkThread();
        mFitSystemWindowsRequested = true;
        scheduleTraversals();
    }

    @Override
    public void requestLayout() {
        if (DEBUG_LAYOUT) {
            Xlog.d(TAG, "requestLayout: mView = " + mView + ", this = " + this);
        }

        checkThread();
        mLayoutRequested = true;
        scheduleTraversals();
    }

    @Override
    public boolean isLayoutRequested() {
        return mLayoutRequested;
    }

    void invalidate() {
        mDirty.set(0, 0, mWidth, mHeight);
        scheduleTraversals();
    }

    void invalidateWorld(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                invalidateWorld(parent.getChildAt(i));
            }
        }
    }

    @Override
    public void invalidateChild(View child, Rect dirty) {
        invalidateChildInParent(null, dirty);
    }

    public ViewParent invalidateChildInParent(int[] location, Rect dirty) {
        checkThread();
        if (DEBUG_DRAW) {
            Log.v(TAG, "Invalidate child: " + dirty);
        }

        if (dirty == null) {
            invalidate();
            return null;
        } else if (dirty.isEmpty() && !mIsAnimating) {
            return null;
        }

        if (mCurScrollY != 0 || mTranslator != null) {
            mTempRect.set(dirty);
            dirty = mTempRect;
            if (mCurScrollY != 0) {
                dirty.offset(0, -mCurScrollY);
            }
            if (mTranslator != null) {
                mTranslator.translateRectInAppWindowToScreen(dirty);
            }
            if (mAttachInfo.mScalingRequired) {
                dirty.inset(-1, -1);
            }
        }

        final Rect localDirty = mDirty;
        if (!localDirty.isEmpty() && !localDirty.contains(dirty)) {
            mAttachInfo.mSetIgnoreDirtyState = true;
            mAttachInfo.mIgnoreDirtyState = true;
        }

        // Add the new dirty rect to the current one
        localDirty.union(dirty.left, dirty.top, dirty.right, dirty.bottom);
        // Intersect with the bounds of the window to skip
        // updates that lie outside of the visible region
        final float appScale = mAttachInfo.mApplicationScale;
        final boolean intersected = localDirty.intersect(0, 0,
                (int) (mWidth * appScale + 0.5f), (int) (mHeight * appScale + 0.5f));
        if (!intersected) {
            localDirty.setEmpty();
        }
        if (!mWillDrawSoon && (intersected || mIsAnimating)) {
            scheduleTraversals();
        }

        return null;
    }

    void setStopped(boolean stopped) {
        if (DEBUG_LAYOUT) {
            Xlog.v(TAG, "setStopped: stopped = " + stopped + ", this = " + this);
        }

        if (mStopped != stopped) {
            mStopped = stopped;
            if (!stopped) {
                scheduleTraversals();
            }
        }
    }

    public ViewParent getParent() {
        return null;
    }

    public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) {
        if (child != mView) {
            throw new RuntimeException("child is not mine, honest!");
        }

        if (DEBUG_DRAW) {
            Xlog.v(TAG, "getChildVisibleRect: child = " + child + ",r = " + r + ", this = " + this);
        }

        // Note: don't apply scroll offset, because we want to know its
        // visibility in the virtual canvas being given to the view hierarchy.
        return r.intersect(0, 0, mWidth, mHeight);
    }

    public void bringChildToFront(View child) {
    }

    int getHostVisibility() {
        return mAppVisible ? mView.getVisibility() : View.GONE;
    }

    void disposeResizeBuffer() {
        if (DEBUG_DRAW || DEBUG_INPUT_RESIZE) {
            Xlog.v(TAG, "disposeResizeBuffer: mResizeBuffer = " + mResizeBuffer
                    + ",mResizeAlpha = " + mResizeAlpha + ",this = " + this);
        }
		
        if (mResizeBuffer != null) {
            mResizeBuffer.destroy();
            mResizeBuffer = null;
        }
    }

    /**
     * Add LayoutTransition to the list of transitions to be started in the next traversal.
     * This list will be cleared after the transitions on the list are start()'ed. These
     * transitionsa re added by LayoutTransition itself when it sets up animations. The setup
     * happens during the layout phase of traversal, which we want to complete before any of the
     * animations are started (because those animations may side-effect properties that layout
     * depends upon, like the bounding rectangles of the affected views). So we add the transition
     * to the list and it is started just prior to starting the drawing phase of traversal.
     *
     * @param transition The LayoutTransition to be started on the next traversal.
     *
     * @hide
     */
    public void requestTransitionStart(LayoutTransition transition) {
        if (mPendingTransitions == null || !mPendingTransitions.contains(transition)) {
            if (mPendingTransitions == null) {
                 mPendingTransitions = new ArrayList<LayoutTransition>();
            }
            mPendingTransitions.add(transition);
        }
    }

    void scheduleTraversals() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            mTraversalBarrier = mHandler.getLooper().postSyncBarrier();
            if (DEBUG_LIFECYCLE) {
                Xlog.v(TAG, "scheduleTraversals: mTraversalBarrier = " + mTraversalBarrier
                        + ",this = " + this);
            }
            mChoreographer.postCallback(
                    Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
            scheduleConsumeBatchedInput();
        }
    }

    void unscheduleTraversals() {
        if (DEBUG_LIFECYCLE) {
            Xlog.v(TAG, "unscheduleTraversals: mTraversalScheduled = " + mTraversalScheduled
                    + ",mTraversalBarrier = " + mTraversalBarrier + ",this = " + this);
        }

        if (mTraversalScheduled) {
            mTraversalScheduled = false;
            mHandler.getLooper().removeSyncBarrier(mTraversalBarrier);
            mChoreographer.removeCallbacks(
                    Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
        }
    }

    void doTraversal() {
        if (DEBUG_LIFECYCLE) {
            Xlog.v(TAG, "doTraversal: mTraversalScheduled = " + mTraversalScheduled
                    + ",mTraversalBarrier = " + mTraversalBarrier + ",this = " + this);
        }

        if (mTraversalScheduled) {
            mTraversalScheduled = false;
            mHandler.getLooper().removeSyncBarrier(mTraversalBarrier);

            if (mProfile) {
                Debug.startMethodTracing("ViewAncestor");
            }

            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "performTraversals");
            try {
                performTraversals();
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }

            if (mProfile) {
                Debug.stopMethodTracing();
                mProfile = false;
            }
        }
    }

    private void applyKeepScreenOnFlag(WindowManager.LayoutParams params) {
        // Update window's global keep screen on flag: if a view has requested
        // that the screen be kept on, then it is always set; otherwise, it is
        // set to whatever the client last requested for the global state.
        if (mAttachInfo.mKeepScreenOn) {
            params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        } else {
            params.flags = (params.flags&~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    | (mClientWindowLayoutFlags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private boolean collectViewAttributes() {
        final View.AttachInfo attachInfo = mAttachInfo;
        if (attachInfo.mRecomputeGlobalAttributes) {
            //Log.i(TAG, "Computing view hierarchy attributes!");
            attachInfo.mRecomputeGlobalAttributes = false;
            boolean oldScreenOn = attachInfo.mKeepScreenOn;
            attachInfo.mKeepScreenOn = false;
            attachInfo.mSystemUiVisibility = 0;
            attachInfo.mHasSystemUiListeners = false;
            mView.dispatchCollectViewAttributes(attachInfo, 0);
            attachInfo.mSystemUiVisibility &= ~attachInfo.mDisabledSystemUiVisibility;
            WindowManager.LayoutParams params = mWindowAttributes;
            if (attachInfo.mKeepScreenOn != oldScreenOn
                    || attachInfo.mSystemUiVisibility != params.subtreeSystemUiVisibility
                    || attachInfo.mHasSystemUiListeners != params.hasSystemUiListeners) {
                applyKeepScreenOnFlag(params);
                params.subtreeSystemUiVisibility = attachInfo.mSystemUiVisibility;
                params.hasSystemUiListeners = attachInfo.mHasSystemUiListeners;
                mView.dispatchWindowSystemUiVisiblityChanged(attachInfo.mSystemUiVisibility);
                return true;
            }
        }
        return false;
    }

    private boolean measureHierarchy(final View host, final WindowManager.LayoutParams lp,
            final Resources res, final int desiredWindowWidth, final int desiredWindowHeight) {
        int childWidthMeasureSpec;
        int childHeightMeasureSpec;
        boolean windowSizeMayChange = false;

        if (DEBUG_ORIENTATION || DEBUG_LAYOUT) {
            Xlog.v(TAG, "ViewRoot measure+ " + host + " in display " + desiredWindowWidth + "x"
                    + desiredWindowHeight + ", lp = " + lp + ", this = " + this);
        }             

        boolean goodMeasure = false;
        if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
            // On large screens, we don't want to allow dialogs to just
            // stretch to fill the entire width of the screen to display
            // one line of text.  First try doing the layout at a smaller
            // size to see if it will fit.
            final DisplayMetrics packageMetrics = res.getDisplayMetrics();
            res.getValue(com.android.internal.R.dimen.config_prefDialogWidth, mTmpValue, true);
            int baseSize = 0;
            if (mTmpValue.type == TypedValue.TYPE_DIMENSION) {
                baseSize = (int)mTmpValue.getDimension(packageMetrics);
            }
            if (DEBUG_DIALOG) {
                Xlog.v(TAG, "Window " + mView + ": baseSize=" + baseSize + ", this = " + this);
            }
            if (baseSize != 0 && desiredWindowWidth > baseSize) {
                childWidthMeasureSpec = getRootMeasureSpec(baseSize, lp.width);
                childHeightMeasureSpec = getRootMeasureSpec(desiredWindowHeight, lp.height);
                performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                if (DEBUG_DIALOG) {
                    Xlog.v(TAG, "Window " + mView + ": measured (" + host.getMeasuredWidth() + ","
                            + host.getMeasuredHeight() + ") " + this);
                }
                if ((host.getMeasuredWidthAndState()&View.MEASURED_STATE_TOO_SMALL) == 0) {
                    goodMeasure = true;
                } else {
                    // Didn't fit in that size... try expanding a bit.
                    baseSize = (baseSize+desiredWindowWidth)/2;
                    if (DEBUG_DIALOG) {
                        Xlog.v(TAG, "Window " + mView + ": next baseSize=" + baseSize + " " + this);
                    }
                    childWidthMeasureSpec = getRootMeasureSpec(baseSize, lp.width);
                    performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                    if (DEBUG_DIALOG) {
                        Xlog.v(TAG, "Window " + mView + ": measured (" + host.getMeasuredWidth()
                                + "," + host.getMeasuredHeight() + ") " + this);
                    }
                    if ((host.getMeasuredWidthAndState()&View.MEASURED_STATE_TOO_SMALL) == 0) {
                        if (DEBUG_DIALOG) {
                            Xlog.v(TAG, "Good!");
                        }
                        goodMeasure = true;
                    }
                }
            }
        }

        if (!goodMeasure) {
            childWidthMeasureSpec = getRootMeasureSpec(desiredWindowWidth, lp.width);
            childHeightMeasureSpec = getRootMeasureSpec(desiredWindowHeight, lp.height);
            performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
            if (mWidth != host.getMeasuredWidth() || mHeight != host.getMeasuredHeight()) {
                windowSizeMayChange = true;
            }
        }

        if (DBG) {
            System.out.println("======================================");
            System.out.println("performTraversals -- after measure");
            if (host != null) {
                //host.debug();
            }
        }
        if (DEBUG_ORIENTATION || DEBUG_LAYOUT) {
            Xlog.v(TAG, "ViewRoot measure-: host measured size = (" + host.getMeasuredWidth()
                    + "x" + host.getMeasuredHeight() + "), windowSizeMayChange = "
                    + windowSizeMayChange + ", this = " + this);
        }
        return windowSizeMayChange;
    }

    private void performTraversals() {
        // cache mView since it is used so much below...
        final View host = mView;
        if (DEBUG_LIFECYCLE) {
            Xlog.v(TAG, "ViewRoot performTraversals+ : mFirst = " + mFirst + ", this = " + this);
        }

        if (DBG) {
            System.out.println("======================================");
            System.out.println("performTraversals");
            if (host != null) {
                //host.debug();
            }
        }

        if (host == null || !mAdded)
            return;

        mIsInTraversal = true;
        mWillDrawSoon = true;
        boolean windowSizeMayChange = false;
        boolean newSurface = false;
        boolean surfaceChanged = false;
        WindowManager.LayoutParams lp = mWindowAttributes;

        int desiredWindowWidth;
        int desiredWindowHeight;

        final View.AttachInfo attachInfo = mAttachInfo;

        final int viewVisibility = getHostVisibility();
        boolean viewVisibilityChanged = mViewVisibility != viewVisibility
                || mNewSurfaceNeeded;

        WindowManager.LayoutParams params = null;
        if (mWindowAttributesChanged) {
            mWindowAttributesChanged = false;
            surfaceChanged = true;
            params = lp;
        }
        CompatibilityInfo compatibilityInfo = mCompatibilityInfo.get();
        if (compatibilityInfo.supportsScreen() == mLastInCompatMode) {
            params = lp;
            mFullRedrawNeeded = true;
            mLayoutRequested = true;
            if (mLastInCompatMode) {
                params.flags &= ~WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
                mLastInCompatMode = false;
            } else {
                params.flags |= WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
                mLastInCompatMode = true;
            }
        }
        
        mWindowAttributesChangesFlag = 0;
        
        Rect frame = mWinFrame;
        if (mFirst) {
            mFullRedrawNeeded = true;
            mLayoutRequested = true;

            if (lp.type == WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL) {
                // NOTE -- system code, won't try to do compat mode.
                Point size = new Point();
                mDisplay.getRealSize(size);
                desiredWindowWidth = size.x;
                desiredWindowHeight = size.y;
            } else {
                DisplayMetrics packageMetrics =
                    mView.getContext().getResources().getDisplayMetrics();
                desiredWindowWidth = packageMetrics.widthPixels;
                desiredWindowHeight = packageMetrics.heightPixels;
            }

            // For the very first time, tell the view hierarchy that it
            // is attached to the window.  Note that at this point the surface
            // object is not initialized to its backing store, but soon it
            // will be (assuming the window is visible).
            attachInfo.mSurface = mSurface;
            // We used to use the following condition to choose 32 bits drawing caches:
            // PixelFormat.hasAlpha(lp.format) || lp.format == PixelFormat.RGBX_8888
            // However, windows are now always 32 bits by default, so choose 32 bits
            attachInfo.mUse32BitDrawingCache = true;
            attachInfo.mHasWindowFocus = false;
            attachInfo.mWindowVisibility = viewVisibility;
            attachInfo.mRecomputeGlobalAttributes = false;
            viewVisibilityChanged = false;
            mLastConfiguration.setTo(host.getResources().getConfiguration());
            mLastSystemUiVisibility = mAttachInfo.mSystemUiVisibility;
            // Set the layout direction if it has not been set before (inherit is the default)
            if (mViewLayoutDirectionInitial == View.LAYOUT_DIRECTION_INHERIT) {
                host.setLayoutDirection(mLastConfiguration.getLayoutDirection());
            }
            host.dispatchAttachedToWindow(attachInfo, 0);
            mFitSystemWindowsInsets.set(mAttachInfo.mContentInsets);
            host.fitSystemWindows(mFitSystemWindowsInsets);
            //Log.i(TAG, "Screen on initialized: " + attachInfo.mKeepScreenOn);

        } else {
            desiredWindowWidth = frame.width();
            desiredWindowHeight = frame.height();
            if (desiredWindowWidth != mWidth || desiredWindowHeight != mHeight) {
                if (DEBUG_ORIENTATION || DEBUG_LAYOUT) {
                    Xlog.v(TAG, "View " + host + " resized to: " + frame + ",this = " + this);
                }                        
                mFullRedrawNeeded = true;
                mLayoutRequested = true;
                windowSizeMayChange = true;
            }
        }

        if (viewVisibilityChanged) {
            attachInfo.mWindowVisibility = viewVisibility;
            host.dispatchWindowVisibilityChanged(viewVisibility);
            if (viewVisibility != View.VISIBLE || mNewSurfaceNeeded) {
                destroyHardwareResources();
            }
            if (viewVisibility == View.GONE) {
                // After making a window gone, we will count it as being
                // shown for the first time the next time it gets focus.
                mHasHadWindowFocus = false;
            }
        }

        // Execute enqueued actions on every traversal in case a detached view enqueued an action
        getRunQueue().executeActions(attachInfo.mHandler);

        boolean insetsChanged = false;

        boolean layoutRequested = mLayoutRequested && !mStopped;
        if (layoutRequested) {

            final Resources res = mView.getContext().getResources();

            if (mFirst) {
                // make sure touch mode code executes by setting cached value
                // to opposite of the added touch mode.
                mAttachInfo.mInTouchMode = !mAddedTouchMode;
                ensureTouchModeLocally(mAddedTouchMode);
            } else {
                if (!mPendingContentInsets.equals(mAttachInfo.mContentInsets)) {
                    if (DEBUG_LAYOUT) {
                        Xlog.v(TAG, "Content insets changing from " + mPendingContentInsets
                                + " to: " + mAttachInfo.mContentInsets + ", this = " + this);
                    }
                    insetsChanged = true;
                }
                if (!mPendingVisibleInsets.equals(mAttachInfo.mVisibleInsets)) {
                    mAttachInfo.mVisibleInsets.set(mPendingVisibleInsets);
                    if (DEBUG_LAYOUT) {
                        Xlog.v(TAG, "Visible insets changing to: " + mAttachInfo.mVisibleInsets
                                + ", mWinFrame = " + mWinFrame + ", this = " + this);
                    }                            
                }
                if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
                        || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                    windowSizeMayChange = true;

                    if (lp.type == WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL) {
                        // NOTE -- system code, won't try to do compat mode.
                        Point size = new Point();
                        mDisplay.getRealSize(size);
                        desiredWindowWidth = size.x;
                        desiredWindowHeight = size.y;
                    } else {
                        DisplayMetrics packageMetrics = res.getDisplayMetrics();
                        desiredWindowWidth = packageMetrics.widthPixels;
                        desiredWindowHeight = packageMetrics.heightPixels;
                    }
                }
            }

            // Ask host how big it wants to be
            windowSizeMayChange |= measureHierarchy(host, lp, res,
                    desiredWindowWidth, desiredWindowHeight);
        }

        if (collectViewAttributes()) {
            params = lp;
        }
        if (attachInfo.mForceReportNewAttributes) {
            attachInfo.mForceReportNewAttributes = false;
            params = lp;
        }

        if (mFirst || attachInfo.mViewVisibilityChanged) {
            attachInfo.mViewVisibilityChanged = false;
            int resizeMode = mSoftInputMode &
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
            if (DEBUG_INPUT_RESIZE) {
                Xlog.v(TAG, "Handle input resize mode start+ : lp.softInputMode = #"
                        + Integer.toHexString(lp.softInputMode) + ", resizeMode = #"
                        + Integer.toHexString(resizeMode) + ", this = " + this);
            }
            // If we are in auto resize mode, then we need to determine
            // what mode to use now.
            if (resizeMode == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
                final int N = attachInfo.mScrollContainers.size();
                for (int i=0; i<N; i++) {
                    if (attachInfo.mScrollContainers.get(i).isShown()) {
                        resizeMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
                    }
                }
                if (resizeMode == 0) {
                    resizeMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
                }
                if ((lp.softInputMode &
                        WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) != resizeMode) {
                    lp.softInputMode = (lp.softInputMode &
                            ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) |
                            resizeMode;
                    params = lp;
                }
            }
            if (DEBUG_INPUT_RESIZE) {
                Xlog.v(TAG, "Handle input resize mode end- : lp.softInputMode = #"
                        + Integer.toHexString(lp.softInputMode) + ", resizeMode = #"
                        + Integer.toHexString(resizeMode) + ", this = " + this);
            }
        }

        if (params != null && (host.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) != 0) {
            if (!PixelFormat.formatHasAlpha(params.format)) {
                params.format = PixelFormat.TRANSLUCENT;
            }
        }

        if (mFitSystemWindowsRequested) {
            mFitSystemWindowsRequested = false;
            mFitSystemWindowsInsets.set(mAttachInfo.mContentInsets);
            host.fitSystemWindows(mFitSystemWindowsInsets);
            if (mLayoutRequested) {
                // Short-circuit catching a new layout request here, so
                // we don't need to go through two layout passes when things
                // change due to fitting system windows, which can happen a lot.
                windowSizeMayChange |= measureHierarchy(host, lp,
                        mView.getContext().getResources(),
                        desiredWindowWidth, desiredWindowHeight);
            }
        }

        if (layoutRequested) {
            // Clear this now, so that if anything requests a layout in the
            // rest of this function we will catch it and re-run a full
            // layout pass.
            mLayoutRequested = false;
        }

        /// M: soft input window size changed, we need to update window size.
        if (mSoftInputMayChanged) {
            insetsChanged = true;
            mSoftInputMayChanged = false;
        }

        boolean windowShouldResize = layoutRequested && windowSizeMayChange
            && ((mWidth != host.getMeasuredWidth() || mHeight != host.getMeasuredHeight())
                || (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT &&
                        frame.width() < desiredWindowWidth && frame.width() != mWidth)
                || (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
                        frame.height() < desiredWindowHeight && frame.height() != mHeight));

        final boolean computesInternalInsets =
                attachInfo.mTreeObserver.hasComputeInternalInsetsListeners();

        boolean insetsPending = false;
        int relayoutResult = 0;

        if (DEBUG_LAYOUT) {
            Xlog.v(TAG, "ViewRoot adjustSize+ : mFirst=" + mFirst
                    + ", windowShouldResize=" + windowShouldResize + ", insetsChanged="
                    + insetsChanged + ", viewVisibilityChanged=" + viewVisibilityChanged
                    + ", params=" + params + ", this = " + this);
        }
        if (mFirst || windowShouldResize || insetsChanged ||
                viewVisibilityChanged || params != null) {

            if (viewVisibility == View.VISIBLE) {
                // If this window is giving internal insets to the window
                // manager, and it is being added or changing its visibility,
                // then we want to first give the window manager "fake"
                // insets to cause it to effectively ignore the content of
                // the window during layout.  This avoids it briefly causing
                // other windows to resize/move based on the raw frame of the
                // window, waiting until we can finish laying out this window
                // and get back to the window manager with the ultimately
                // computed insets.
                insetsPending = computesInternalInsets && (mFirst || viewVisibilityChanged);
            }

            if (mSurfaceHolder != null) {
                mSurfaceHolder.mSurfaceLock.lock();
                mDrawingAllowed = true;
            }

            boolean hwInitialized = false;
            boolean contentInsetsChanged = false;
            boolean visibleInsetsChanged;
            boolean hadSurface = mSurface.isValid();

            try {
                if (DEBUG_LAYOUT) {
                    Xlog.i(TAG, "host=w:" + host.getMeasuredWidth() + ", h:"
                            + host.getMeasuredHeight() + ", params=" + params 
                            + " surface=" + mSurface + ",hadSurface = " + hadSurface + ", this = " + this);
                }

                final int surfaceGenerationId = mSurface.getGenerationId();
                relayoutResult = relayoutWindow(params, viewVisibility, insetsPending);

                if (DEBUG_LAYOUT) {
                    Xlog.v(TAG, "relayout: frame=" + frame.toShortString() + " content="
                            + mPendingContentInsets.toShortString() + " visible=" + mPendingVisibleInsets.toShortString()
                            + " surface=" + mSurface + ",valid = " + mSurface.isValid() + ",surfaceGenerationId = "
                            + surfaceGenerationId + ", relayoutResult = " + relayoutResult + ", this = " + this);
                }

                if (mPendingConfiguration.seq != 0) {
                    if (DEBUG_CONFIGURATION) {
                        Xlog.v(TAG, "Visible with new config: " + mPendingConfiguration + ", this = "
                                + this);
                    }                           
                    updateConfiguration(mPendingConfiguration, !mFirst);
                    mPendingConfiguration.seq = 0;
                }

                contentInsetsChanged = !mPendingContentInsets.equals(
                        mAttachInfo.mContentInsets);
                visibleInsetsChanged = !mPendingVisibleInsets.equals(
                        mAttachInfo.mVisibleInsets);
                if (contentInsetsChanged) {
                    if (mWidth > 0 && mHeight > 0 && lp != null &&
                            ((lp.systemUiVisibility|lp.subtreeSystemUiVisibility)
                                    & View.SYSTEM_UI_LAYOUT_FLAGS) == 0 &&
                            mSurface != null && mSurface.isValid() &&
                            !mAttachInfo.mTurnOffWindowResizeAnim &&
                            mAttachInfo.mHardwareRenderer != null &&
                            mAttachInfo.mHardwareRenderer.isEnabled() &&
                            mAttachInfo.mHardwareRenderer.validate() &&
                            lp != null && !PixelFormat.formatHasAlpha(lp.format)) {

                        disposeResizeBuffer();

                        boolean completed = false;
                        HardwareCanvas hwRendererCanvas = mAttachInfo.mHardwareRenderer.getCanvas();
                        HardwareCanvas layerCanvas = null;
                        try {
                            if (mResizeBuffer == null) {
                                mResizeBuffer = mAttachInfo.mHardwareRenderer.createHardwareLayer(
                                        mWidth, mHeight, false);
                            } else if (mResizeBuffer.getWidth() != mWidth ||
                                    mResizeBuffer.getHeight() != mHeight) {
                                mResizeBuffer.resize(mWidth, mHeight);
                            }
                            // TODO: should handle create/resize failure
                            layerCanvas = mResizeBuffer.start(hwRendererCanvas);
                            layerCanvas.setViewport(mWidth, mHeight);
                            layerCanvas.onPreDraw(null);
                            final int restoreCount = layerCanvas.save();

                            int yoff;
                            final boolean scrolling = mScroller != null
                                    && mScroller.computeScrollOffset();
                            if (scrolling) {
                                yoff = mScroller.getCurrY();
                                mScroller.abortAnimation();
                            } else {
                                yoff = mScrollY;
                            }

                            layerCanvas.translate(0, -yoff);
                            if (mTranslator != null) {
                                mTranslator.translateCanvas(layerCanvas);
                            }

                            DisplayList displayList = mView.mDisplayList;
                            if (displayList != null) {
                                layerCanvas.drawDisplayList(displayList, null,
                                        DisplayList.FLAG_CLIP_CHILDREN);
                            } else {
                                mView.draw(layerCanvas);
                            }

                            drawAccessibilityFocusedDrawableIfNeeded(layerCanvas);

                            mResizeBufferStartTime = SystemClock.uptimeMillis();
                            mResizeBufferDuration = mView.getResources().getInteger(
                                    com.android.internal.R.integer.config_mediumAnimTime);
                            completed = true;

                            layerCanvas.restoreToCount(restoreCount);
                        } catch (OutOfMemoryError e) {
                            Log.w(TAG, "Not enough memory for content change anim buffer", e);
                        } finally {
                            if (layerCanvas != null) {
                                layerCanvas.onPostDraw();
                            }
                            if (mResizeBuffer != null) {
                                mResizeBuffer.end(hwRendererCanvas);
                                if (!completed) {
                                    mResizeBuffer.destroy();
                                    mResizeBuffer = null;
                                }
                            }
                        }
                    }
                    mAttachInfo.mContentInsets.set(mPendingContentInsets);
                    if (DEBUG_LAYOUT) {
                        Xlog.v(TAG, "Content insets changing to: " + mAttachInfo.mContentInsets
                                + ", mResizeBuffer = " + mResizeBuffer + ", mResizeAlpha = " 
                                + mResizeAlpha + ", this = " + this);
                    }
                }
                if (contentInsetsChanged || mLastSystemUiVisibility !=
                        mAttachInfo.mSystemUiVisibility || mFitSystemWindowsRequested) {
                    mLastSystemUiVisibility = mAttachInfo.mSystemUiVisibility;
                    mFitSystemWindowsRequested = false;
                    mFitSystemWindowsInsets.set(mAttachInfo.mContentInsets);
                    host.fitSystemWindows(mFitSystemWindowsInsets);
                }
                if (visibleInsetsChanged) {
                    mAttachInfo.mVisibleInsets.set(mPendingVisibleInsets);
                    if (DEBUG_LAYOUT) {
                        Xlog.v(TAG, "Visible insets changing to: " + mAttachInfo.mVisibleInsets
                                + ", this = " + this);
                    }
                }

                if (!hadSurface) {
                    if (mSurface.isValid()) {
                        if (DEBUG_DRAW) {
                            Xlog.v(TAG, "Create new surface: " + mSurface + ",surfaceGenerationId = " + surfaceGenerationId
                                    + ", mSurface.getGenerationId() = " + mSurface.getGenerationId()
                                    + ",mPreviousTransparentRegion = " + mPreviousTransparentRegion + ",this = " + this);
                        }
                        // If we are creating a new surface, then we need to
                        // completely redraw it.  Also, when we get to the
                        // point of drawing it we will hold off and schedule
                        // a new traversal instead.  This is so we can tell the
                        // window manager about all of the windows being displayed
                        // before actually drawing them, so it can display then
                        // all at once.
                        newSurface = true;
                        mFullRedrawNeeded = true;
                        mPreviousTransparentRegion.setEmpty();

                        if (mAttachInfo.mHardwareRenderer != null) {
                            try {
                                hwInitialized = mAttachInfo.mHardwareRenderer.initialize(
                                        mHolder.getSurface());
                            } catch (Surface.OutOfResourcesException e) {
                                Log.e(TAG, "OutOfResourcesException initializing HW surface", e);
                                try {
                                    if (!mWindowSession.outOfMemory(mWindow) &&
                                            Process.myUid() != Process.SYSTEM_UID) {
                                        Slog.w(TAG, "No processes killed for memory; killing self");
                                        Process.killProcess(Process.myPid());
                                    }
                                } catch (RemoteException ex) {
                                    Xlog.e(TAG, "RemoteException when call outOfMemory.", e);
                                }
                                mLayoutRequested = true;    // ask wm for a new surface next time.
                                return;
                            }
                        }
                    }
                } else if (!mSurface.isValid()) {
                    // If the surface has been removed, then reset the scroll
                    // positions.
                    mLastScrolledFocus = null;
                    if (DEBUG_DRAW) {
                        Xlog.v(TAG, "Surface invalid: " + mSurface + ",surfaceGenerationId = " + surfaceGenerationId
                                + ", mAttachInfo.mHardwareRenderer = " + mAttachInfo.mHardwareRenderer + ",this = " + this);
                    }
                    mScrollY = mCurScrollY = 0;
                    if (mScroller != null) {
                        mScroller.abortAnimation();
                    }
                    disposeResizeBuffer();
                    // Our surface is gone
                    if (mAttachInfo.mHardwareRenderer != null &&
                            mAttachInfo.mHardwareRenderer.isEnabled()) {
                        mAttachInfo.mHardwareRenderer.destroy(true);
                    }
                } else if (surfaceGenerationId != mSurface.getGenerationId() &&
                        mSurfaceHolder == null && mAttachInfo.mHardwareRenderer != null) {
                    mFullRedrawNeeded = true;
                    try {
                        mAttachInfo.mHardwareRenderer.updateSurface(mHolder.getSurface());
                    } catch (Surface.OutOfResourcesException e) {
                        Log.e(TAG, "OutOfResourcesException updating HW surface", e);
                        try {
                            if (!mWindowSession.outOfMemory(mWindow)) {
                                Slog.w(TAG, "No processes killed for memory; killing self");
                                Process.killProcess(Process.myPid());
                            }
                        } catch (RemoteException ex) {
                            Xlog.e(TAG, "RemoteException when call outOfMemory.", ex);
                        }
                        mLayoutRequested = true;    // ask wm for a new surface next time.
                        return;
                    }
                }
            } catch (RemoteException e) {
                Xlog.e(TAG, "RemoteException happens in " + this, e);
            }

            if (DEBUG_ORIENTATION || DEBUG_LAYOUT) {
                Xlog.v(TAG, "Relayout returned: frame=" + frame + ", surface=" + mSurface
                        + ", this = " + this);
            }

            attachInfo.mWindowLeft = frame.left;
            attachInfo.mWindowTop = frame.top;

            // !!FIXME!! This next section handles the case where we did not get the
            // window size we asked for. We should avoid this by getting a maximum size from
            // the window session beforehand.
            if (mWidth != frame.width() || mHeight != frame.height()) {
                mWidth = frame.width();
                mHeight = frame.height();
            }

            if (mSurfaceHolder != null) {
                // The app owns the surface; tell it about what is going on.
                if (mSurface.isValid()) {
                    // XXX .copyFrom() doesn't work!
                    //mSurfaceHolder.mSurface.copyFrom(mSurface);
                    mSurfaceHolder.mSurface = mSurface;
                }
                mSurfaceHolder.setSurfaceFrameSize(mWidth, mHeight);
                mSurfaceHolder.mSurfaceLock.unlock();
                if (mSurface.isValid()) {
                    if (!hadSurface) {
                        mSurfaceHolder.ungetCallbacks();

                        mIsCreating = true;
                        mSurfaceHolderCallback.surfaceCreated(mSurfaceHolder);
                        SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                        if (callbacks != null) {
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceCreated(mSurfaceHolder);
                            }
                        }
                        surfaceChanged = true;
                    }
                    if (surfaceChanged) {
                        mSurfaceHolderCallback.surfaceChanged(mSurfaceHolder,
                                lp.format, mWidth, mHeight);
                        SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                        if (callbacks != null) {
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceChanged(mSurfaceHolder, lp.format,
                                        mWidth, mHeight);
                            }
                        }
                    }
                    mIsCreating = false;
                } else if (hadSurface) {
                    mSurfaceHolder.ungetCallbacks();
                    SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                    mSurfaceHolderCallback.surfaceDestroyed(mSurfaceHolder);
                    if (callbacks != null) {
                        for (SurfaceHolder.Callback c : callbacks) {
                            c.surfaceDestroyed(mSurfaceHolder);
                        }
                    }
                    mSurfaceHolder.mSurfaceLock.lock();
                    try {
                        mSurfaceHolder.mSurface = new Surface();
                    } finally {
                        mSurfaceHolder.mSurfaceLock.unlock();
                    }
                }
            }

            if (mAttachInfo.mHardwareRenderer != null &&
                    mAttachInfo.mHardwareRenderer.isEnabled()) {
                if (hwInitialized || windowShouldResize ||
                        mWidth != mAttachInfo.mHardwareRenderer.getWidth() ||
                        mHeight != mAttachInfo.mHardwareRenderer.getHeight()) {
                    mAttachInfo.mHardwareRenderer.setup(mWidth, mHeight);
                    if (!hwInitialized) {
                        mAttachInfo.mHardwareRenderer.invalidate(mHolder.getSurface());
                        mFullRedrawNeeded = true;
                    }
                }
            }

            if (!mStopped) {
                boolean focusChangedDueToTouchMode = ensureTouchModeLocally(
                        (relayoutResult&WindowManagerGlobal.RELAYOUT_RES_IN_TOUCH_MODE) != 0);
                if (focusChangedDueToTouchMode || mWidth != host.getMeasuredWidth()
                        || mHeight != host.getMeasuredHeight() || contentInsetsChanged) {
                    int childWidthMeasureSpec = getRootMeasureSpec(mWidth, lp.width);
                    int childHeightMeasureSpec = getRootMeasureSpec(mHeight, lp.height);
    
                    if (DEBUG_LAYOUT) {
                        Xlog.v(TAG, "Ooops, something changed! mWidth=" + mWidth
                                + " measuredWidth=" + host.getMeasuredWidth() + " mHeight="
                                + mHeight + " measuredHeight=" + host.getMeasuredHeight()
                                + " contentInsetsChanged=" + contentInsetsChanged                                
                                + " this = " + this);
                    }
    
                     // Ask host how big it wants to be
                    performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
    
                    // Implementation of weights from WindowManager.LayoutParams
                    // We just grow the dimensions as needed and re-measure if
                    // needs be
                    int width = host.getMeasuredWidth();
                    int height = host.getMeasuredHeight();
                    boolean measureAgain = false;
    
                    if (lp.horizontalWeight > 0.0f) {
                        width += (int) ((mWidth - width) * lp.horizontalWeight);
                        childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(width,
                                MeasureSpec.EXACTLY);
                        measureAgain = true;
                    }
                    if (lp.verticalWeight > 0.0f) {
                        height += (int) ((mHeight - height) * lp.verticalWeight);
                        childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(height,
                                MeasureSpec.EXACTLY);
                        measureAgain = true;
                    }
    
                    if (measureAgain) {
                        if (DEBUG_LAYOUT) {
                            Xlog.v(TAG, "And hey let's measure once more: width=" + width
                                    + " height=" + height + " this = " + this);
                        }
                        performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                    }
    
                    layoutRequested = true;
                }
            }
        } else {
            // Not the first pass and no window/insets/visibility change but the window
            // may have moved and we need check that and if so to update the left and right
            // in the attach info. We translate only the window frame since on window move
            // the window manager tells us only for the new frame but the insets are the
            // same and we do not want to translate them more than once.

            // TODO: Well, we are checking whether the frame has changed similarly
            // to how this is done for the insets. This is however incorrect since
            // the insets and the frame are translated. For example, the old frame
            // was (1, 1 - 1, 1) and was translated to say (2, 2 - 2, 2), now the new
            // reported frame is (2, 2 - 2, 2) which implies no change but this is not
            // true since we are comparing a not translated value to a translated one.
            // This scenario is rare but we may want to fix that.

            final boolean windowMoved = (attachInfo.mWindowLeft != frame.left
                    || attachInfo.mWindowTop != frame.top);
            if (windowMoved) {
                if (mTranslator != null) {
                    mTranslator.translateRectInScreenToAppWinFrame(frame);
                }
                attachInfo.mWindowLeft = frame.left;
                attachInfo.mWindowTop = frame.top;
            }
        }
        if (DEBUG_LAYOUT) {
            Xlog.v(TAG, "ViewRoot adjustSize- " + ", frame = " + frame
                    + ", host.getMeasuredWidth() = " + host.getMeasuredWidth()
                    + ", host.getMeasuredHeight() = " + host.getMeasuredHeight() + ", this = " + this);
        }

        final boolean didLayout = layoutRequested && !mStopped;
        boolean triggerGlobalLayoutListener = didLayout
                || attachInfo.mRecomputeGlobalAttributes;
        if (DEBUG_LAYOUT) {
            Xlog.v(TAG, "ViewRoot layout+ : layoutRequested = " + layoutRequested
                    + ", mStopped = " + mStopped + ", this = " + this);
        }
        if (didLayout) {
            performLayout();

            // By this point all views have been sized and positionned
            // We can compute the transparent area

            if ((host.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) != 0) {
                // start out transparent
                // TODO: AVOID THAT CALL BY CACHING THE RESULT?
                host.getLocationInWindow(mTmpLocation);
                mTransparentRegion.set(mTmpLocation[0], mTmpLocation[1],
                        mTmpLocation[0] + host.mRight - host.mLeft,
                        mTmpLocation[1] + host.mBottom - host.mTop);

                if (DEBUG_DRAW) {
                    Xlog.v(TAG, "ViewRoot gatherTransparentRegion+ : mTransparentRegion = "
                            + mTransparentRegion + ", mTmpLocation = " + mTmpLocation
                            + ",host = " + host + ", this = " + this);
                }
                
                host.gatherTransparentRegion(mTransparentRegion);
                if (mTranslator != null) {
                    mTranslator.translateRegionInWindowToScreen(mTransparentRegion);
                }

                if (DEBUG_DRAW) {
                    Xlog.v(TAG, "ViewRoot gatherTransparentRegion- : mTransparentRegion = "
                            + mTransparentRegion + ",mWindow = " + mWindow
                            + ", mPreviousTransparentRegion = " + mPreviousTransparentRegion
                            + ", this = " + this);
                }

                if (!mTransparentRegion.equals(mPreviousTransparentRegion)) {
                    mPreviousTransparentRegion.set(mTransparentRegion);
                    // reconfigure window manager
                    try {
                        mWindowSession.setTransparentRegion(mWindow, mTransparentRegion);
                    } catch (RemoteException e) {
                        Xlog.e(TAG, "Exception in " + this + " when set transparent region.", e);
                    }
                }
            }

            if (DBG) {
                System.out.println("======================================");
                System.out.println("performTraversals -- after setFrame");
                if (host != null) {
                    host.debug();
                }
            }
        }

        if (triggerGlobalLayoutListener) {
            attachInfo.mRecomputeGlobalAttributes = false;
            attachInfo.mTreeObserver.dispatchOnGlobalLayout();

            if (AccessibilityManager.getInstance(host.mContext).isEnabled()) {
                postSendWindowContentChangedCallback(mView);
            }
        }
        if (DEBUG_LAYOUT) {
            View content = null;
            if (host instanceof ViewGroup) {
                content = ((ViewGroup) host).getChildAt(0);
            }
            String logString = "ViewRoot layout- : host.mLeft=" + host.mLeft + ", host.mRight="
                    + host.mRight + ", host.mTop=" + host.mTop + ", host.mBottom=" + host.mBottom
                    + ", computesInternalInsets = " + computesInternalInsets + ",content = "
                    + content + ", this = " + this;

            if (content != null) {
                logString += ",content padding = (" + content.mPaddingLeft + ","
                        + content.mPaddingTop + "," + content.mPaddingRight + ","
                        + content.mPaddingBottom + ")";
            }
            Xlog.v(TAG, logString);
        }

        if (computesInternalInsets) {
            // Clear the original insets.
            final ViewTreeObserver.InternalInsetsInfo insets = attachInfo.mGivenInternalInsets;
            insets.reset();

            // Compute new insets in place.
            attachInfo.mTreeObserver.dispatchOnComputeInternalInsets(insets);

            // Tell the window manager.
            if (insetsPending || !mLastGivenInsets.equals(insets)) {
                mLastGivenInsets.set(insets);

                // Translate insets to screen coordinates if needed.
                final Rect contentInsets;
                final Rect visibleInsets;
                final Region touchableRegion;
                if (mTranslator != null) {
                    contentInsets = mTranslator.getTranslatedContentInsets(insets.contentInsets);
                    visibleInsets = mTranslator.getTranslatedVisibleInsets(insets.visibleInsets);
                    touchableRegion = mTranslator.getTranslatedTouchableArea(insets.touchableRegion);
                } else {
                    contentInsets = insets.contentInsets;
                    visibleInsets = insets.visibleInsets;
                    touchableRegion = insets.touchableRegion;
                }

                try {
                    mWindowSession.setInsets(mWindow, insets.mTouchableInsets,
                            contentInsets, visibleInsets, touchableRegion);
                } catch (RemoteException e) {
                    Xlog.e(TAG, "RemoteException happens when setInsets, mWindow = " + mWindow
                            + ", contentInsets = " + contentInsets + ", visibleInsets = "
                            + visibleInsets + ", touchableRegion = " + touchableRegion
                            + ", this = " + this, e);
                }
            }
        }

        boolean skipDraw = false;

        if (mFirst) {
            // handle first focus request
            if (DEBUG_INPUT_RESIZE) {
                Xlog.v(TAG, "First: mView.hasFocus()=" + (mView != null ? mView.hasFocus() : "null")
                        + ", relayoutResult = " + relayoutResult + ", this = " + this);
            }

            if (mView != null) {
                if (!mView.hasFocus()) {
                    mView.requestFocus(View.FOCUS_FORWARD);
                    mFocusedView = mRealFocusedView = mView.findFocus();
                    if (DEBUG_INPUT_RESIZE) {
                        Xlog.v(TAG, "First: requested focused view=" + mFocusedView + ", this = " + this);
                    }   
                } else {
                    mRealFocusedView = mView.findFocus();
                    if (DEBUG_INPUT_RESIZE) {
                        Xlog.v(TAG, "First: existing focused view=" + mRealFocusedView + ", this = " + this);
                    }
                }
            }
            if ((relayoutResult & WindowManagerGlobal.RELAYOUT_RES_ANIMATING) != 0) {
                // The first time we relayout the window, if the system is
                // doing window animations, we want to hold of on any future
                // draws until the animation is done.
                if (DEBUG_DRAW) {
                    Xlog.v(TAG, "Set mWindowsAnimating to true, this = " + this);
                }
                mWindowsAnimating = true;
            }
        } else if (mWindowsAnimating) {
            skipDraw = true;
        }

        mFirst = false;
        mWillDrawSoon = false;
        mNewSurfaceNeeded = false;
        mViewVisibility = viewVisibility;

        if (mAttachInfo.mHasWindowFocus) {
            final boolean imTarget = WindowManager.LayoutParams
                    .mayUseInputMethod(mWindowAttributes.flags);
            if (imTarget != mLastWasImTarget) {
                mLastWasImTarget = imTarget;
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null && imTarget) {
                    imm.startGettingWindowFocus(mView);
                    imm.onWindowFocus(mView, mView.findFocus(),
                            mWindowAttributes.softInputMode,
                            !mHasHadWindowFocus, mWindowAttributes.flags);
                }
            }
        }

        // Remember if we must report the next draw.
        if ((relayoutResult & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0) {
            mReportNextDraw = true;
        }

        boolean cancelDraw = attachInfo.mTreeObserver.dispatchOnPreDraw() ||
                viewVisibility != View.VISIBLE;
        if (DEBUG_DRAW) {
            Xlog.v(TAG, "ViewRoot draw+ " + ", cancelDraw=" + cancelDraw
                    + ", viewVisibility=" + viewVisibility + ", newSurface=" + newSurface
                    + ", mFullRedrawNeeded = " + mFullRedrawNeeded + ", mReportNextDraw="
                    + mReportNextDraw + ", skipDraw = " + skipDraw + ", mWindowsAnimating = "
                    + mWindowsAnimating + ", this = " + this);
        }

        if (!cancelDraw && !newSurface) {
            if (!skipDraw || mReportNextDraw) {
                if (mPendingTransitions != null && mPendingTransitions.size() > 0) {
                    for (int i = 0; i < mPendingTransitions.size(); ++i) {
                        mPendingTransitions.get(i).startChangingAnimations();
                    }
                    mPendingTransitions.clear();
                }
    
                performDraw();
            }
        } else {
            if (viewVisibility == View.VISIBLE) {
                // Try again
                scheduleTraversals();
            } else if (mPendingTransitions != null && mPendingTransitions.size() > 0) {
                for (int i = 0; i < mPendingTransitions.size(); ++i) {
                    mPendingTransitions.get(i).endChangingAnimations();
                }
                mPendingTransitions.clear();
            }
        }
        if (DEBUG_DRAW || DEBUG_LIFECYCLE) {
            Xlog.v(TAG, "ViewRoot performTraversals and draw- : mResizeBuffer = " + mResizeBuffer
                    + ", mResizeAlpha = " + mResizeAlpha + ", this = " + this);
        }
        mIsInTraversal = false;
    }

    private void performMeasure(int childWidthMeasureSpec, int childHeightMeasureSpec) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "measure");
        try {
            mView.measure(childWidthMeasureSpec, childHeightMeasureSpec);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    private void performLayout() {
        mLayoutRequested = false;
        mScrollMayChange = true;

        final View host = mView;
        if (DEBUG_ORIENTATION || DEBUG_LAYOUT) {
            Xlog.v(TAG, "Laying out " + host + " in " + this + " to (" +
                    host.getMeasuredWidth() + ", " + host.getMeasuredHeight() + ")");
        }

        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "layout");
        try {
            host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    public void requestTransparentRegion(View child) {
        // the test below should not fail unless someone is messing with us
        checkThread();
        if (mView == child) {
            mView.mPrivateFlags |= View.PFLAG_REQUEST_TRANSPARENT_REGIONS;
            // Need to make sure we re-evaluate the window attributes next
            // time around, to ensure the window has the correct format.
            mWindowAttributesChanged = true;
            mWindowAttributesChangesFlag = 0;
            requestLayout();
        }
    }

    /**
     * Figures out the measure spec for the root view in a window based on it's
     * layout params.
     *
     * @param windowSize
     *            The available width or height of the window
     *
     * @param rootDimension
     *            The layout params for one dimension (width or height) of the
     *            window.
     *
     * @return The measure spec to use to measure the root view.
     */
    private static int getRootMeasureSpec(int windowSize, int rootDimension) {
        int measureSpec;
        switch (rootDimension) {

        case ViewGroup.LayoutParams.MATCH_PARENT:
            // Window can't resize. Force root view to be windowSize.
            measureSpec = MeasureSpec.makeMeasureSpec(windowSize, MeasureSpec.EXACTLY);
            break;
        case ViewGroup.LayoutParams.WRAP_CONTENT:
            // Window can resize. Set max size for root view.
            measureSpec = MeasureSpec.makeMeasureSpec(windowSize, MeasureSpec.AT_MOST);
            break;
        default:
            // Window wants to be an exact size. Force root view to be that size.
            measureSpec = MeasureSpec.makeMeasureSpec(rootDimension, MeasureSpec.EXACTLY);
            break;
        }
        return measureSpec;
    }

    int mHardwareYOffset;
    int mResizeAlpha;
    final Paint mResizePaint = new Paint();

    public void onHardwarePreDraw(HardwareCanvas canvas) {
        canvas.translate(0, -mHardwareYOffset);
    }

    public void onHardwarePostDraw(HardwareCanvas canvas) {
        if (DEBUG_DRAW) {
            Xlog.v(TAG, "onHardwarePostDraw: mResizeBuffer = " + mResizeBuffer + ", mResizeAlpha = "
                    + mResizeAlpha + ", this = " + this);
        }
        if (mResizeBuffer != null) {
            mResizePaint.setAlpha(mResizeAlpha);
            canvas.drawHardwareLayer(mResizeBuffer, 0.0f, mHardwareYOffset, mResizePaint);
        }
        drawAccessibilityFocusedDrawableIfNeeded(canvas);
    }

    /**
     * @hide
     */
    void outputDisplayList(View view) {
        if (mAttachInfo != null && mAttachInfo.mHardwareCanvas != null) {
            DisplayList displayList = view.getDisplayList();
            if (displayList != null) {
                mAttachInfo.mHardwareCanvas.outputDisplayList(displayList);
            }
        }
    }

    /**
     * @see #PROPERTY_PROFILE_RENDERING
     */
    private void profileRendering(boolean enabled) {
        if (mProfileRendering) {
            mRenderProfilingEnabled = enabled;
            if (mRenderProfiler == null) {
                mRenderProfiler = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "Starting profiling thread");
                        while (mRenderProfilingEnabled) {
                            mAttachInfo.mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mDirty.set(0, 0, mWidth, mHeight);
                                    scheduleTraversals();
                                }
                            });
                            try {
                                // TODO: This should use vsync when we get an API
                                Thread.sleep(15);
                            } catch (InterruptedException e) {
                                Log.d(TAG, "Exiting profiling thread");
                            }                            
                        }
                    }
                }, "Rendering Profiler");
                mRenderProfiler.start();
            } else {
                mRenderProfiler.interrupt();
                mRenderProfiler = null;
            }
        }
    }

    /**
     * Called from draw() when DEBUG_FPS is enabled
     */
    private void trackFPS() {
        // Tracks frames per second drawn. First value in a series of draws may be bogus
        // because it down not account for the intervening idle time
        long nowTime = System.currentTimeMillis();
        if (mFpsStartTime < 0) {
            mFpsStartTime = mFpsPrevTime = nowTime;
            mFpsNumFrames = 0;
        } else {
            ++mFpsNumFrames;
            String thisHash = Integer.toHexString(System.identityHashCode(this));
            long frameTime = nowTime - mFpsPrevTime;
            long totalTime = nowTime - mFpsStartTime;
            Log.v(TAG, "0x" + thisHash + "\tFrame time:\t" + frameTime);
            mFpsPrevTime = nowTime;
            if (totalTime > 1000) {
                float fps = (float) mFpsNumFrames * 1000 / totalTime;
                Log.v(TAG, "0x" + thisHash + "\tFPS:\t" + fps);
                mFpsStartTime = nowTime;
                mFpsNumFrames = 0;
            }
        }
    }

    private void performDraw() {
        if (!mAttachInfo.mScreenOn && !mReportNextDraw) {
            return;
        }

        final boolean fullRedrawNeeded = mFullRedrawNeeded;
        mFullRedrawNeeded = false;

        mIsDrawing = true;
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "draw");
        try {
            draw(fullRedrawNeeded);
        } finally {
            /** M: after drawing finished, we trigger post draw listener to log finish time. @{ */
            final View.AttachInfo attachInfo = mAttachInfo;
            if (attachInfo != null) {
                attachInfo.mTreeObserver.dispatchOnPostDraw();
            }
            /** @} */
            
            mIsDrawing = false;
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }

        if (mReportNextDraw) {
            mReportNextDraw = false;

            if (DEBUG_DRAW) {
                Xlog.v(TAG, "FINISHED DRAWING: " + mWindowAttributes.getTitle() + ", this = " + this);
            }
            if (mSurfaceHolder != null && mSurface.isValid()) {
                mSurfaceHolderCallback.surfaceRedrawNeeded(mSurfaceHolder);
                SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                if (callbacks != null) {
                    for (SurfaceHolder.Callback c : callbacks) {
                        if (c instanceof SurfaceHolder.Callback2) {
                            ((SurfaceHolder.Callback2)c).surfaceRedrawNeeded(
                                    mSurfaceHolder);
                        }
                    }
                }
            }
            try {
                mWindowSession.finishDrawing(mWindow);
            } catch (RemoteException e) {
                Xlog.e(TAG, "Exception when finish draw window " + mWindow + " in " + this, e);
            }
        }
    }

    private void draw(boolean fullRedrawNeeded) {
        Surface surface = mSurface;
        if (surface == null || !surface.isValid()) {
            return;
        }

        if (DEBUG_FPS) {
            trackFPS();
        }

        if (!sFirstDrawComplete) {
            synchronized (sFirstDrawHandlers) {
                sFirstDrawComplete = true;
                final int count = sFirstDrawHandlers.size();
                for (int i = 0; i< count; i++) {
                    mHandler.post(sFirstDrawHandlers.get(i));
                }
            }
        }

        scrollToRectOrFocus(null, false);

        final AttachInfo attachInfo = mAttachInfo;
        if (attachInfo.mViewScrollChanged) {
            attachInfo.mViewScrollChanged = false;
            attachInfo.mTreeObserver.dispatchOnScrollChanged();
        }

        int yoff;
        boolean animating = mScroller != null && mScroller.computeScrollOffset();
        if (animating) {
            yoff = mScroller.getCurrY();
        } else {
            yoff = mScrollY;
        }
        if (mCurScrollY != yoff) {
            mCurScrollY = yoff;
            fullRedrawNeeded = true;
        }

        final float appScale = attachInfo.mApplicationScale;
        final boolean scalingRequired = attachInfo.mScalingRequired;

        int resizeAlpha = 0;
        if (mResizeBuffer != null) {
            long deltaTime = SystemClock.uptimeMillis() - mResizeBufferStartTime;
            if (deltaTime < mResizeBufferDuration) {
                float amt = deltaTime/(float) mResizeBufferDuration;
                amt = mResizeInterpolator.getInterpolation(amt);
                animating = true;
                resizeAlpha = 255 - (int)(amt*255);
            } else {
                disposeResizeBuffer();
            }
        }

        final Rect dirty = mDirty;
        if (mSurfaceHolder != null) {
            // The app owns the surface, we won't draw.
            dirty.setEmpty();
            if (animating) {
                if (mScroller != null) {
                    mScroller.abortAnimation();
                }
                disposeResizeBuffer();
            }
            return;
        }

        if (fullRedrawNeeded) {
            attachInfo.mIgnoreDirtyState = true;
            dirty.set(0, 0, (int) (mWidth * appScale + 0.5f), (int) (mHeight * appScale + 0.5f));
        }

        if (DEBUG_ORIENTATION || DEBUG_DRAW) {
            Xlog.v(TAG, "Draw " + mView + "/"
                    + mWindowAttributes.getTitle()
                    + ": dirty={" + dirty.left + "," + dirty.top
                    + "," + dirty.right + "," + dirty.bottom + "} surface="
                    + surface + " surface.isValid()=" + surface.isValid() 
                    + ", appScale = " + appScale + ", width=" + mWidth
                    + ", height=" + mHeight + ", mScrollY = " + mScrollY
                    + ", mCurScrollY = " + mCurScrollY + ", animating = " 
                    + animating + ", mIsAnimating = " + mIsAnimating 
                    + ", this = " + this);
        }

        attachInfo.mTreeObserver.dispatchOnDraw();

        if (!dirty.isEmpty() || mIsAnimating) {
            if (attachInfo.mHardwareRenderer != null && attachInfo.mHardwareRenderer.isEnabled()) {
                // Draw with hardware renderer.
                mIsAnimating = false;
                mHardwareYOffset = yoff;
                mResizeAlpha = resizeAlpha;

                mCurrentDirty.set(dirty);
                mCurrentDirty.union(mPreviousDirty);
                mPreviousDirty.set(dirty);
                dirty.setEmpty();

                if (attachInfo.mHardwareRenderer.draw(mView, attachInfo, this,
                        animating ? null : mCurrentDirty)) {
                    mPreviousDirty.set(0, 0, mWidth, mHeight);
                }
            } else if (!drawSoftware(surface, attachInfo, yoff, scalingRequired, dirty)) {
                if (DEBUG_DRAW) {
                    Xlog.v(TAG, "drawSoftware return: this = " + this);
                }
                return;
            }
        }

        if (animating) {
            mFullRedrawNeeded = true;
            scheduleTraversals();
        }
    }

    /**
     * @return true if drawing was succesfull, false if an error occurred
     */
    private boolean drawSoftware(Surface surface, AttachInfo attachInfo, int yoff,
            boolean scalingRequired, Rect dirty) {

        // If we get here with a disabled & requested hardware renderer, something went
        // wrong (an invalidate posted right before we destroyed the hardware surface
        // for instance) so we should just bail out. Locking the surface with software
        // rendering at this point would lock it forever and prevent hardware renderer
        // from doing its job when it comes back.
        if (attachInfo.mHardwareRenderer != null && !attachInfo.mHardwareRenderer.isEnabled() &&
                attachInfo.mHardwareRenderer.isRequested()) {
            mFullRedrawNeeded = true;
            scheduleTraversals();
            return false;
        }

        // Draw with software renderer.
        Canvas canvas;
        try {
            int left = dirty.left;
            int top = dirty.top;
            int right = dirty.right;
            int bottom = dirty.bottom;

            canvas = mSurface.lockCanvas(dirty);

            if (left != dirty.left || top != dirty.top || right != dirty.right ||
                    bottom != dirty.bottom) {
                attachInfo.mIgnoreDirtyState = true;
            }

            // TODO: Do this in native
            canvas.setDensity(mDensity);
        } catch (Surface.OutOfResourcesException e) {
            Log.e(TAG, "OutOfResourcesException locking surface", e);
            try {
                if (!mWindowSession.outOfMemory(mWindow)) {
                    Slog.w(TAG, "No processes killed for memory; killing self");
                    Process.killProcess(Process.myPid());
                }
            } catch (RemoteException ex) {
                Xlog.e(TAG, "RemoteException when call outOfMemory.", ex);
            }
            mLayoutRequested = true;    // ask wm for a new surface next time.
            return false;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Could not lock surface", e);
            // Don't assume this is due to out of memory, it could be
            // something else, and if it is something else then we could
            // kill stuff (or ourself) for no reason.
            mLayoutRequested = true;    // ask wm for a new surface next time.
            return false;
        }

        try {
            if (DEBUG_ORIENTATION || DEBUG_DRAW) {
                Xlog.v(TAG, "Surface " + surface + " drawing to bitmap w="
                        + canvas.getWidth() + ", h=" + canvas.getHeight() + ", this = " + this);
                //canvas.drawARGB(255, 255, 0, 0);
            }

            // If this bitmap's format includes an alpha channel, we
            // need to clear it before drawing so that the child will
            // properly re-composite its drawing on a transparent
            // background. This automatically respects the clip/dirty region
            // or
            // If we are applying an offset, we need to clear the area
            // where the offset doesn't appear to avoid having garbage
            // left in the blank areas.
            if (!canvas.isOpaque() || yoff != 0) {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            }

            dirty.setEmpty();
            mIsAnimating = false;
            attachInfo.mDrawingTime = SystemClock.uptimeMillis();
            mView.mPrivateFlags |= View.PFLAG_DRAWN;

            if (DEBUG_DRAW) {
                Context cxt = mView.getContext();
                Xlog.i(TAG, "Drawing view start+ : package:" + cxt.getPackageName() +
                        ", metrics=" + cxt.getResources().getDisplayMetrics() +
                        ", compatibilityInfo=" + cxt.getResources().getCompatibilityInfo() + 
                        ", this = " + this);
            }
            try {
                canvas.translate(0, -yoff);
                if (mTranslator != null) {
                    mTranslator.translateCanvas(canvas);
                }
                canvas.setScreenDensity(scalingRequired ? mNoncompatDensity : 0);
                attachInfo.mSetIgnoreDirtyState = false;

                mView.draw(canvas);

                drawAccessibilityFocusedDrawableIfNeeded(canvas);
            } finally {
                if (!attachInfo.mSetIgnoreDirtyState) {
                    // Only clear the flag if it was not set during the mView.draw() call
                    attachInfo.mIgnoreDirtyState = false;
                }
            }
            if (DEBUG_DRAW) {
                Xlog.v(TAG, "Drawing view end- : mView = " + mView + ", this = " + this);
            }
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas);
            } catch (IllegalArgumentException e) {
                Xlog.e(TAG, "Could not unlock surface, surface = " + surface + ", canvas = " + canvas + ", this = " + this, e);
                mLayoutRequested = true;    // ask wm for a new surface next time.
                //noinspection ReturnInsideFinallyBlock
                return false;
            }

            if (DEBUG_DRAW) {
                Xlog.v(TAG, "Surface " + surface + " unlockCanvasAndPost, this = " + this);
            }
        }
        return true;
    }

    /**
     * We want to draw a highlight around the current accessibility focused.
     * Since adding a style for all possible view is not a viable option we
     * have this specialized drawing method.
     *
     * Note: We are doing this here to be able to draw the highlight for
     *       virtual views in addition to real ones.
     *
     * @param canvas The canvas on which to draw.
     */
    private void drawAccessibilityFocusedDrawableIfNeeded(Canvas canvas) {
        AccessibilityManager manager = AccessibilityManager.getInstance(mView.mContext);
        if (!manager.isEnabled() || !manager.isTouchExplorationEnabled()) {
            return;
        }
        if (mAccessibilityFocusedHost == null || mAccessibilityFocusedHost.mAttachInfo == null) {
            return;
        }
        Drawable drawable = getAccessibilityFocusedDrawable();
        if (drawable == null) {
            return;
        }
        AccessibilityNodeProvider provider =
            mAccessibilityFocusedHost.getAccessibilityNodeProvider();
        Rect bounds = mView.mAttachInfo.mTmpInvalRect;
        if (provider == null) {
            mAccessibilityFocusedHost.getBoundsOnScreen(bounds);
        } else {
            if (mAccessibilityFocusedVirtualView == null) {
                return;
            }
            mAccessibilityFocusedVirtualView.getBoundsInScreen(bounds);
        }
        bounds.offset(-mAttachInfo.mWindowLeft, -mAttachInfo.mWindowTop);
        bounds.intersect(0, 0, mAttachInfo.mViewRootImpl.mWidth, mAttachInfo.mViewRootImpl.mHeight);
        drawable.setBounds(bounds);
        drawable.draw(canvas);
    }

    private Drawable getAccessibilityFocusedDrawable() {
        if (mAttachInfo != null) {
            // Lazily load the accessibility focus drawable.
            if (mAttachInfo.mAccessibilityFocusDrawable == null) {
                TypedValue value = new TypedValue();
                final boolean resolved = mView.mContext.getTheme().resolveAttribute(
                        R.attr.accessibilityFocusedDrawable, value, true);
                if (resolved) {
                    mAttachInfo.mAccessibilityFocusDrawable =
                        mView.mContext.getResources().getDrawable(value.resourceId);
                }
            }
            return mAttachInfo.mAccessibilityFocusDrawable;
        }
        return null;
    }

    void invalidateDisplayLists() {
        final ArrayList<DisplayList> displayLists = mDisplayLists;
        final int count = displayLists.size();

        for (int i = 0; i < count; i++) {
            final DisplayList displayList = displayLists.get(i);
            displayList.invalidate();
            displayList.clear();
        }

        displayLists.clear();
    }

    boolean scrollToRectOrFocus(Rect rectangle, boolean immediate) {
        final View.AttachInfo attachInfo = mAttachInfo;
        final Rect ci = attachInfo.mContentInsets;
        final Rect vi = attachInfo.mVisibleInsets;
        int scrollY = 0;
        boolean handled = false;

        if (vi.left > ci.left || vi.top > ci.top
                || vi.right > ci.right || vi.bottom > ci.bottom) {
            // We'll assume that we aren't going to change the scroll
            // offset, since we want to avoid that unless it is actually
            // going to make the focus visible...  otherwise we scroll
            // all over the place.
            scrollY = mScrollY;
            // We can be called for two different situations: during a draw,
            // to update the scroll position if the focus has changed (in which
            // case 'rectangle' is null), or in response to a
            // requestChildRectangleOnScreen() call (in which case 'rectangle'
            // is non-null and we just want to scroll to whatever that
            // rectangle is).
            View focus = mRealFocusedView;

            // When in touch mode, focus points to the previously focused view,
            // which may have been removed from the view hierarchy. The following
            // line checks whether the view is still in our hierarchy.
            if (focus == null || focus.mAttachInfo != mAttachInfo) {
                mRealFocusedView = null;
                return false;
            }

            if (focus != mLastScrolledFocus) {
                // If the focus has changed, then ignore any requests to scroll
                // to a rectangle; first we want to make sure the entire focus
                // view is visible.
                rectangle = null;
            }
            if (DEBUG_INPUT_RESIZE) {
                Xlog.v(TAG, "Eval scroll: focus=" + focus + " rectangle=" + rectangle + " ci=" + ci
                        + " vi=" + vi + " mScrollMayChange = " + mScrollMayChange +
                        " mLastScrolledFocus = " + mLastScrolledFocus + " this = " + this);
            }
            if (focus == mLastScrolledFocus && !mScrollMayChange
                    && rectangle == null) {
                // Optimization: if the focus hasn't changed since last
                // time, and no layout has happened, then just leave things
                // as they are.
                if (DEBUG_INPUT_RESIZE) {
                    Xlog.v(TAG, "Keeping scroll y=" + mScrollY + " vi=" + vi.toShortString()
                            + " this = " + this);
                }
            } else if (focus != null) {
                // We need to determine if the currently focused view is
                // within the visible part of the window and, if not, apply
                // a pan so it can be seen.
                mLastScrolledFocus = focus;
                mScrollMayChange = false;
                if (DEBUG_INPUT_RESIZE) {
                    Xlog.v(TAG, "Need to scroll? mVisRect = " + mVisRect + " this = " + this);
                }
                // Try to find the rectangle from the focus view.
                if (focus.getGlobalVisibleRect(mVisRect, null)) {
                    if (DEBUG_INPUT_RESIZE) {
                        Xlog.v(TAG, "Root w=" + mView.getWidth() + " h=" + mView.getHeight()
                                + " ci=" + ci.toShortString() + " vi=" + vi.toShortString()
                                + " this = " + this);
                    }
                    if (rectangle == null) {
                        focus.getFocusedRect(mTempRect);
                        if (DEBUG_INPUT_RESIZE) {
                            Xlog.v(TAG, "Focus " + focus + ": focusRect="
                                    + mTempRect.toShortString() + " this = " + this);
                        }
                        if (mView instanceof ViewGroup) {
                            ((ViewGroup) mView).offsetDescendantRectToMyCoords(
                                    focus, mTempRect);
                        }
                        if (DEBUG_INPUT_RESIZE) {
                            Xlog.v(TAG, "Focus in window: focusRect=" + mTempRect.toShortString()
                                    + " visRect=" + mVisRect.toShortString() + " this = " + this);
                        }
                    } else {
                        mTempRect.set(rectangle);
                        if (DEBUG_INPUT_RESIZE) {
                            Xlog.v(TAG, "Request scroll to rect: " + mTempRect.toShortString()
                                    + " visRect=" + mVisRect.toShortString() + " this = " + this);
                        }
                    }
                    if (mTempRect.intersect(mVisRect)) {
                        if (DEBUG_INPUT_RESIZE) {
                            Xlog.v(TAG, "Focus window visible rect: " + mTempRect.toShortString()
                                    + " this = " + this);
                        }
                        if (mTempRect.height() >
                                (mView.getHeight()-vi.top-vi.bottom)) {
                            // If the focus simply is not going to fit, then
                            // best is probably just to leave things as-is.
                            if (DEBUG_INPUT_RESIZE) {
                                Xlog.v(TAG, "Too tall; leaving scrollY=" + scrollY + " this = " + this);
                            }                                
                        } else if ((mTempRect.top-scrollY) < vi.top) {
                            scrollY -= vi.top - (mTempRect.top-scrollY);
                            if (DEBUG_INPUT_RESIZE) {
                                Xlog.v(TAG, "Top covered; scrollY=" + scrollY + " this = " + this);
                            }
                        } else if ((mTempRect.bottom-scrollY)
                                > (mView.getHeight()-vi.bottom)) {
                            scrollY += (mTempRect.bottom-scrollY)
                                    - (mView.getHeight()-vi.bottom);
                            if (DEBUG_INPUT_RESIZE) {
                                Xlog.v(TAG, "Bottom covered; scrollY=" + scrollY + " this = " + this);
                            }
                        }
                        handled = true;
                    }
                }
            }
        }

        if (scrollY != mScrollY) {
            if (DEBUG_INPUT_RESIZE) {
                Xlog.v(TAG, "Pan scroll changed: old=" + mScrollY + ", new=" + scrollY
                        + ", this = " + this);
            }
            if (!immediate && mResizeBuffer == null) {
                if (mScroller == null) {
                    mScroller = new Scroller(mView.getContext());
                }
                mScroller.startScroll(0, mScrollY, 0, scrollY-mScrollY);
            } else if (mScroller != null) {
                mScroller.abortAnimation();
            }
            mScrollY = scrollY;
        }

        return handled;
    }

    /**
     * @hide
     */
    public View getAccessibilityFocusedHost() {
        return mAccessibilityFocusedHost;
    }

    /**
     * @hide
     */
    public AccessibilityNodeInfo getAccessibilityFocusedVirtualView() {
        return mAccessibilityFocusedVirtualView;
    }

    void setAccessibilityFocus(View view, AccessibilityNodeInfo node) {
        // If we have a virtual view with accessibility focus we need
        // to clear the focus and invalidate the virtual view bounds.
        if (mAccessibilityFocusedVirtualView != null) {

            AccessibilityNodeInfo focusNode = mAccessibilityFocusedVirtualView;
            View focusHost = mAccessibilityFocusedHost;
            focusHost.clearAccessibilityFocusNoCallbacks();

            // Wipe the state of the current accessibility focus since
            // the call into the provider to clear accessibility focus
            // will fire an accessibility event which will end up calling
            // this method and we want to have clean state when this
            // invocation happens.
            mAccessibilityFocusedHost = null;
            mAccessibilityFocusedVirtualView = null;

            AccessibilityNodeProvider provider = focusHost.getAccessibilityNodeProvider();
            if (provider != null) {
                // Invalidate the area of the cleared accessibility focus.
                focusNode.getBoundsInParent(mTempRect);
                focusHost.invalidate(mTempRect);
                // Clear accessibility focus in the virtual node.
                final int virtualNodeId = AccessibilityNodeInfo.getVirtualDescendantId(
                        focusNode.getSourceNodeId());
                provider.performAction(virtualNodeId,
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
            }
            focusNode.recycle();
        }
        if (mAccessibilityFocusedHost != null) {
            // Clear accessibility focus in the view.
            mAccessibilityFocusedHost.clearAccessibilityFocusNoCallbacks();
        }

        // Set the new focus host and node.
        mAccessibilityFocusedHost = view;
        mAccessibilityFocusedVirtualView = node;
    }

    public void requestChildFocus(View child, View focused) {
        checkThread();

        if (DEBUG_INPUT_RESIZE) {
            Xlog.v(TAG, "Request child " + child + " focus: focus now " + focused + " in " + this);
        }

        mAttachInfo.mTreeObserver.dispatchOnGlobalFocusChange(mOldFocusedView, focused);
        scheduleTraversals();

        mFocusedView = mRealFocusedView = focused;
    }

    public void clearChildFocus(View child) {
        checkThread();

        if (DEBUG_INPUT_RESIZE) {
            Xlog.v(TAG, "Clearing child focus: child = " + child + ", mFocusedView = "
                    + mFocusedView + ",mOldFocusedView = " + mOldFocusedView + ", this = " + this);
        }

        mOldFocusedView = mFocusedView;

        // Invoke the listener only if there is no view to take focus
        if (focusSearch(null, View.FOCUS_FORWARD) == null) {
            mAttachInfo.mTreeObserver.dispatchOnGlobalFocusChange(mOldFocusedView, null);
        }

        mFocusedView = mRealFocusedView = null;
    }

    @Override
    public ViewParent getParentForAccessibility() {
        return null;
    }

    public void focusableViewAvailable(View v) {
        checkThread();
        if (mView != null) {
            if (!mView.hasFocus()) {
                v.requestFocus();
            } else {
                // the one case where will transfer focus away from the current one
                // is if the current view is a view group that prefers to give focus
                // to its children first AND the view is a descendant of it.
                mFocusedView = mView.findFocus();
                boolean descendantsHaveDibsOnFocus =
                        (mFocusedView instanceof ViewGroup) &&
                            (((ViewGroup) mFocusedView).getDescendantFocusability() ==
                                    ViewGroup.FOCUS_AFTER_DESCENDANTS);
                if (descendantsHaveDibsOnFocus && isViewDescendantOf(v, mFocusedView)) {
                    // If a view gets the focus, the listener will be invoked from requestChildFocus()
                    v.requestFocus();
                }
            }
        }
    }

    public void recomputeViewAttributes(View child) {
        checkThread();
        if (mView == child) {
            mAttachInfo.mRecomputeGlobalAttributes = true;
            if (!mWillDrawSoon) {
                scheduleTraversals();
            }
        }
    }

    void dispatchDetachedFromWindow() {
        if (mView != null && mView.mAttachInfo != null) {
            if (mAttachInfo.mHardwareRenderer != null &&
                    mAttachInfo.mHardwareRenderer.isEnabled()) {
                mAttachInfo.mHardwareRenderer.validate();
            }
            mView.dispatchDetachedFromWindow();
        }

        mAccessibilityInteractionConnectionManager.ensureNoConnection();
        mAccessibilityManager.removeAccessibilityStateChangeListener(
                mAccessibilityInteractionConnectionManager);
        removeSendWindowContentChangedCallback();

        destroyHardwareRenderer();

        setAccessibilityFocus(null, null);

        mView = null;
        mAttachInfo.mRootView = null;
        mAttachInfo.mSurface = null;

        mSurface.release();

        if (mInputQueueCallback != null && mInputQueue != null) {
            mInputQueueCallback.onInputQueueDestroyed(mInputQueue);
            mInputQueueCallback = null;
            mInputQueue = null;
        } else if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        try {
            mWindowSession.remove(mWindow);
        } catch (RemoteException e) {
            Xlog.e(TAG, "RemoteException remove window " + mWindow + " in " + this, e);
        }
        
        // Dispose the input channel after removing the window so the Window Manager
        // doesn't interpret the input channel being closed as an abnormal termination.
        if (mInputChannel != null) {
            mInputChannel.dispose();
            mInputChannel = null;
        }

        unscheduleTraversals();
    }

    void updateConfiguration(Configuration config, boolean force) {
        if (DEBUG_CONFIGURATION) {
            Xlog.v(TAG, "Applying new config to window " + mWindowAttributes.getTitle() + ": "
                    + config + ",force = " + force + ", this = " + this);
        }

        CompatibilityInfo ci = mCompatibilityInfo.getIfNeeded();
        if (ci != null) {
            config = new Configuration(config);
            ci.applyToConfiguration(mNoncompatDensity, config);
        }

        synchronized (sConfigCallbacks) {
            for (int i=sConfigCallbacks.size()-1; i>=0; i--) {
                sConfigCallbacks.get(i).onConfigurationChanged(config);
            }
        }
        if (mView != null) {
            // At this point the resources have been updated to
            // have the most recent config, whatever that is.  Use
            // the one in them which may be newer.
            config = mView.getResources().getConfiguration();
            if (force || mLastConfiguration.diff(config) != 0) {
                final int lastLayoutDirection = mLastConfiguration.getLayoutDirection();
                final int currentLayoutDirection = config.getLayoutDirection();
                mLastConfiguration.setTo(config);
                if (lastLayoutDirection != currentLayoutDirection &&
                        mViewLayoutDirectionInitial == View.LAYOUT_DIRECTION_INHERIT) {
                    mView.setLayoutDirection(currentLayoutDirection);
                }
                mView.dispatchConfigurationChanged(config);
            }
        }
    }
    
    /**
     * Return true if child is an ancestor of parent, (or equal to the parent).
     */
    public static boolean isViewDescendantOf(View child, View parent) {
        if (child == parent) {
            return true;
        }

        final ViewParent theParent = child.getParent();
        return (theParent instanceof ViewGroup) && isViewDescendantOf((View) theParent, parent);
    }

    private static void forceLayout(View view) {
        view.forceLayout();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            final int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                forceLayout(group.getChildAt(i));
            }
        }
    }

    private final static int MSG_INVALIDATE = 1;
    private final static int MSG_INVALIDATE_RECT = 2;
    private final static int MSG_DIE = 3;
    private final static int MSG_RESIZED = 4;
    private final static int MSG_RESIZED_REPORT = 5;
    private final static int MSG_WINDOW_FOCUS_CHANGED = 6;
    private final static int MSG_DISPATCH_KEY = 7;
    private final static int MSG_DISPATCH_APP_VISIBILITY = 8;
    private final static int MSG_DISPATCH_GET_NEW_SURFACE = 9;
    private final static int MSG_IME_FINISHED_EVENT = 10;
    private final static int MSG_DISPATCH_KEY_FROM_IME = 11;
    private final static int MSG_FINISH_INPUT_CONNECTION = 12;
    private final static int MSG_CHECK_FOCUS = 13;
    private final static int MSG_CLOSE_SYSTEM_DIALOGS = 14;
    private final static int MSG_DISPATCH_DRAG_EVENT = 15;
    private final static int MSG_DISPATCH_DRAG_LOCATION_EVENT = 16;
    private final static int MSG_DISPATCH_SYSTEM_UI_VISIBILITY = 17;
    private final static int MSG_UPDATE_CONFIGURATION = 18;
    private final static int MSG_PROCESS_INPUT_EVENTS = 19;
    private final static int MSG_DISPATCH_SCREEN_STATE = 20;
    private final static int MSG_INVALIDATE_DISPLAY_LIST = 21;
    private final static int MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST = 22;
    private final static int MSG_DISPATCH_DONE_ANIMATING = 23;
    private final static int MSG_INVALIDATE_WORLD = 24;
    private final static int MSG_WINDOW_MOVED = 25;

    final class ViewRootHandler extends Handler {
        @Override
        public String getMessageName(Message message) {
            switch (message.what) {
                case MSG_INVALIDATE:
                    return "MSG_INVALIDATE";
                case MSG_INVALIDATE_RECT:
                    return "MSG_INVALIDATE_RECT";
                case MSG_DIE:
                    return "MSG_DIE";
                case MSG_RESIZED:
                    return "MSG_RESIZED";
                case MSG_RESIZED_REPORT:
                    return "MSG_RESIZED_REPORT";
                case MSG_WINDOW_FOCUS_CHANGED:
                    return "MSG_WINDOW_FOCUS_CHANGED";
                case MSG_DISPATCH_KEY:
                    return "MSG_DISPATCH_KEY";
                case MSG_DISPATCH_APP_VISIBILITY:
                    return "MSG_DISPATCH_APP_VISIBILITY";
                case MSG_DISPATCH_GET_NEW_SURFACE:
                    return "MSG_DISPATCH_GET_NEW_SURFACE";
                case MSG_IME_FINISHED_EVENT:
                    return "MSG_IME_FINISHED_EVENT";
                case MSG_DISPATCH_KEY_FROM_IME:
                    return "MSG_DISPATCH_KEY_FROM_IME";
                case MSG_FINISH_INPUT_CONNECTION:
                    return "MSG_FINISH_INPUT_CONNECTION";
                case MSG_CHECK_FOCUS:
                    return "MSG_CHECK_FOCUS";
                case MSG_CLOSE_SYSTEM_DIALOGS:
                    return "MSG_CLOSE_SYSTEM_DIALOGS";
                case MSG_DISPATCH_DRAG_EVENT:
                    return "MSG_DISPATCH_DRAG_EVENT";
                case MSG_DISPATCH_DRAG_LOCATION_EVENT:
                    return "MSG_DISPATCH_DRAG_LOCATION_EVENT";
                case MSG_DISPATCH_SYSTEM_UI_VISIBILITY:
                    return "MSG_DISPATCH_SYSTEM_UI_VISIBILITY";
                case MSG_UPDATE_CONFIGURATION:
                    return "MSG_UPDATE_CONFIGURATION";
                case MSG_PROCESS_INPUT_EVENTS:
                    return "MSG_PROCESS_INPUT_EVENTS";
                case MSG_DISPATCH_SCREEN_STATE:
                    return "MSG_DISPATCH_SCREEN_STATE";
                case MSG_INVALIDATE_DISPLAY_LIST:
                    return "MSG_INVALIDATE_DISPLAY_LIST";
                case MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST:
                    return "MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST";
                case MSG_DISPATCH_DONE_ANIMATING:
                    return "MSG_DISPATCH_DONE_ANIMATING";
                case MSG_WINDOW_MOVED:
                    return "MSG_WINDOW_MOVED";
            }
            return super.getMessageName(message);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_INVALIDATE:
                ((View) msg.obj).invalidate();
                break;
            case MSG_INVALIDATE_RECT:
                final View.AttachInfo.InvalidateInfo info = (View.AttachInfo.InvalidateInfo) msg.obj;
                info.target.invalidate(info.left, info.top, info.right, info.bottom);
                info.release();
                break;
            case MSG_IME_FINISHED_EVENT:
                handleImeFinishedEvent(msg.arg1, msg.arg2 != 0);
                break;
            case MSG_PROCESS_INPUT_EVENTS:
                mProcessInputEventsScheduled = false;
                doProcessInputEvents();
                break;
            case MSG_DISPATCH_APP_VISIBILITY:
                handleAppVisibility(msg.arg1 != 0);
                break;
            case MSG_DISPATCH_GET_NEW_SURFACE:
                handleGetNewSurface();
                break;
            case MSG_RESIZED: {
                // Recycled in the fall through...
                SomeArgs args = (SomeArgs) msg.obj;
                if (mWinFrame.equals(args.arg1)
                        && mPendingContentInsets.equals(args.arg2)
                        && mPendingVisibleInsets.equals(args.arg3)
                        && args.arg4 == null) {
                    break;
                }
                } // fall through...
            case MSG_RESIZED_REPORT:
                if (mAdded) {
                    SomeArgs args = (SomeArgs) msg.obj;

                    Configuration config = (Configuration) args.arg4;
                    if (config != null) {
                        updateConfiguration(config, false);
                    }
                    if (DEBUG_LAYOUT) {
                        Xlog.d(TAG, "Handle RESIZE: message = " + msg.what + " ,this = "
                                + ViewRootImpl.this);
                    }

                    mWinFrame.set((Rect) args.arg1);
                    mPendingContentInsets.set((Rect) args.arg2);
                    mPendingVisibleInsets.set((Rect) args.arg3);

                    args.recycle();

                    if (msg.what == MSG_RESIZED_REPORT) {
                        mReportNextDraw = true;
                    }

                    if (mView != null) {
                        forceLayout(mView);
                    }

                    requestLayout();
                }
                break;
            case MSG_WINDOW_MOVED:
                if (mAdded) {
                    final int w = mWinFrame.width();
                    final int h = mWinFrame.height();
                    final int l = msg.arg1;
                    final int t = msg.arg2;
                    mWinFrame.left = l;
                    mWinFrame.right = l + w;
                    mWinFrame.top = t;
                    mWinFrame.bottom = t + h;

                    if (mView != null) {
                        forceLayout(mView);
                    }
                    requestLayout();
                }
                break;
            case MSG_WINDOW_FOCUS_CHANGED: {
                if (mAdded) {
                    boolean hasWindowFocus = msg.arg1 != 0;
                    mAttachInfo.mHasWindowFocus = hasWindowFocus;

                    profileRendering(hasWindowFocus);

                    if (hasWindowFocus) {
                        boolean inTouchMode = msg.arg2 != 0;
                        ensureTouchModeLocally(inTouchMode);

                        if (mAttachInfo.mHardwareRenderer != null &&
                                mSurface != null && mSurface.isValid()) {
                            mFullRedrawNeeded = true;
                            try {
                                if (mAttachInfo.mHardwareRenderer.initializeIfNeeded(
                                        mWidth, mHeight, mHolder.getSurface())) {
                                    mFullRedrawNeeded = true;
                                }
                            } catch (Surface.OutOfResourcesException e) {
                                Log.e(TAG, "OutOfResourcesException locking surface", e);
                                try {
                                    if (!mWindowSession.outOfMemory(mWindow)) {
                                        Slog.w(TAG, "No processes killed for memory; killing self");
                                        Process.killProcess(Process.myPid());
                                    }
                                } catch (RemoteException ex) {
                                    Xlog.e(TAG, "RemoteException when call outOfMemory.", e);
                                }
                                // Retry in a bit.
                                sendMessageDelayed(obtainMessage(msg.what, msg.arg1, msg.arg2), 500);
                                return;
                            }
                        }
                    }

                    mLastWasImTarget = WindowManager.LayoutParams
                            .mayUseInputMethod(mWindowAttributes.flags);

                    InputMethodManager imm = InputMethodManager.peekInstance();
                    if (mView != null) {
                        if (hasWindowFocus && imm != null && mLastWasImTarget) {
                            imm.startGettingWindowFocus(mView);
                        }
                        mAttachInfo.mKeyDispatchState.reset();
                        mView.dispatchWindowFocusChanged(hasWindowFocus);
                    }

                    if (DEBUG_IMF) {
                        Xlog.v(TAG, "Handle MSG_WINDOW_FOCUS_CHANGED: hasWindowFocus = "
                                + hasWindowFocus + ", mLastWasImTarget = " + mLastWasImTarget
                                + ", softInputMode = #" + Integer.toHexString(mWindowAttributes.softInputMode)
                                + ", window flags = #" + Integer.toHexString(mWindowAttributes.flags)
                                + ", mView = " + mView + ", this = " + this);
                    }

                    // Note: must be done after the focus change callbacks,
                    // so all of the view state is set up correctly.
                    if (hasWindowFocus) {
                        ///M : Ignore check on mLastWasImTarget, this action allows viewroot
                        //     to tell InputMethodManager on a new focus gained without regard
                        //     to whether IME is needed on this window. InputMethodManger will
                        //     handle the following check. @{


                        if (imm != null) {
                            // Only Full Screen Application Window can be covered by IME keyboard, thus if it`s NOT
                            // application window, just let it go through on the original way
                            boolean fullScreen = false;
                            fullScreen = mWindowAttributes.width != WindowManager.LayoutParams.WRAP_CONTENT
                                      && mWindowAttributes.height != WindowManager.LayoutParams.WRAP_CONTENT
                                      /*&& !PixelFormat.formatHasAlpha(mWindowAttributes.format)*/;

                            if ((mWindowAttributes.type >= WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                                 && mWindowAttributes.type <= WindowManager.LayoutParams.LAST_APPLICATION_WINDOW
                                 && fullScreen)
                                 || mLastWasImTarget) {
                                if (DEBUG_IMF)
                                    Xlog.v(TAG, "Application Window may not use IME, just notify imm on window gained focus");
                             /// @}
                                imm.onWindowFocus(mView, mView.findFocus(),
                                        mWindowAttributes.softInputMode,
                                        !mHasHadWindowFocus, mWindowAttributes.flags);
                            }
                        }
                        // Clear the forward bit.  We can just do this directly, since
                        // the window manager doesn't care about it.
                        mWindowAttributes.softInputMode &=
                                ~WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
                        ((WindowManager.LayoutParams)mView.getLayoutParams())
                                .softInputMode &=
                                    ~WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
                        mHasHadWindowFocus = true;
                    }

                    setAccessibilityFocus(null, null);

                    if (mView != null && mAccessibilityManager.isEnabled()) {
                        if (hasWindowFocus) {
                            mView.sendAccessibilityEvent(
                                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                        }
                    }
                }
            } break;
            case MSG_DIE:
                doDie();
                break;
            case MSG_DISPATCH_KEY: {
                KeyEvent event = (KeyEvent)msg.obj;
                enqueueInputEvent(event, null, 0, true);
            } break;
            case MSG_DISPATCH_KEY_FROM_IME: {
                if (LOCAL_LOGV || DEBUG_KEY) {
                    Xlog.v(TAG, "Dispatching key " + msg.obj + " from IME to " + mView + " in " + this);
                }

                KeyEvent event = (KeyEvent)msg.obj;
                if ((event.getFlags()&KeyEvent.FLAG_FROM_SYSTEM) != 0) {
                    // The IME is trying to say this event is from the
                    // system!  Bad bad bad!
                    //noinspection UnusedAssignment
                    event = KeyEvent.changeFlags(event, event.getFlags() & ~KeyEvent.FLAG_FROM_SYSTEM);
                }
                enqueueInputEvent(event, null, QueuedInputEvent.FLAG_DELIVER_POST_IME, true);
            } break;
            case MSG_FINISH_INPUT_CONNECTION: {
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null) {
                    imm.reportFinishInputConnection((InputConnection)msg.obj);
                }
            } break;
            case MSG_CHECK_FOCUS: {
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null) {
                    imm.checkFocus();
                }
            } break;
            case MSG_CLOSE_SYSTEM_DIALOGS: {
                if (mView != null) {
                    mView.onCloseSystemDialogs((String)msg.obj);
                }
            } break;
            case MSG_DISPATCH_DRAG_EVENT:
            case MSG_DISPATCH_DRAG_LOCATION_EVENT: {
                DragEvent event = (DragEvent)msg.obj;
                event.mLocalState = mLocalDragState;    // only present when this app called startDrag()
                handleDragEvent(event);
            } break;
            case MSG_DISPATCH_SYSTEM_UI_VISIBILITY: {
                handleDispatchSystemUiVisibilityChanged((SystemUiVisibilityInfo)msg.obj);
            } break;
            case MSG_UPDATE_CONFIGURATION: {
                Configuration config = (Configuration)msg.obj;
                if (config.isOtherSeqNewer(mLastConfiguration)) {
                    config = mLastConfiguration;
                }
                updateConfiguration(config, false);
            } break;
            case MSG_DISPATCH_SCREEN_STATE: {
                if (mView != null) {
                    handleScreenStateChange(msg.arg1 == 1);
                }
            } break;
            case MSG_INVALIDATE_DISPLAY_LIST: {
                invalidateDisplayLists();
            } break;
            case MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST: {
                setAccessibilityFocus(null, null);
            } break;
            case MSG_DISPATCH_DONE_ANIMATING: {
                handleDispatchDoneAnimating();
            } break;
            case MSG_INVALIDATE_WORLD: {
                if (mView != null) {
                    invalidateWorld(mView);
                }
            } break;
            }
        }
    }

    final ViewRootHandler mHandler = new ViewRootHandler();

    /**
     * Something in the current window tells us we need to change the touch mode.  For
     * example, we are not in touch mode, and the user touches the screen.
     *
     * If the touch mode has changed, tell the window manager, and handle it locally.
     *
     * @param inTouchMode Whether we want to be in touch mode.
     * @return True if the touch mode changed and focus changed was changed as a result
     */
    boolean ensureTouchMode(boolean inTouchMode) {
        if (DBG) {
            Xlog.d("touchmode", "ensureTouchMode(" + inTouchMode + "), current " + "touch mode is "
                    + mAttachInfo.mInTouchMode + ", this = " + this);
        }
        if (mAttachInfo.mInTouchMode == inTouchMode) return false;

        // tell the window manager
        try {
            mWindowSession.setInTouchMode(inTouchMode);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        // handle the change
        return ensureTouchModeLocally(inTouchMode);
    }

    /**
     * Ensure that the touch mode for this window is set, and if it is changing,
     * take the appropriate action.
     * @param inTouchMode Whether we want to be in touch mode.
     * @return True if the touch mode changed and focus changed was changed as a result
     */
    private boolean ensureTouchModeLocally(boolean inTouchMode) {
        if (DBG) {
            Xlog.d("touchmode", "ensureTouchModeLocally(" + inTouchMode + "), current " + "touch mode is "
                    + mAttachInfo.mInTouchMode + ", this = " + this);
        }

        if (mAttachInfo.mInTouchMode == inTouchMode) return false;

        mAttachInfo.mInTouchMode = inTouchMode;
        mAttachInfo.mTreeObserver.dispatchOnTouchModeChanged(inTouchMode);

        return (inTouchMode) ? enterTouchMode() : leaveTouchMode();
    }

    private boolean enterTouchMode() {
        if (mView != null) {
            if (mView.hasFocus()) {
                // note: not relying on mFocusedView here because this could
                // be when the window is first being added, and mFocused isn't
                // set yet.
                final View focused = mView.findFocus();
                if (focused != null && !focused.isFocusableInTouchMode()) {

                    final ViewGroup ancestorToTakeFocus =
                            findAncestorToTakeFocusInTouchMode(focused);
                    if (ancestorToTakeFocus != null) {
                        // there is an ancestor that wants focus after its descendants that
                        // is focusable in touch mode.. give it focus
                        return ancestorToTakeFocus.requestFocus();
                    } else {
                        // nothing appropriate to have focus in touch mode, clear it out
                        mView.unFocus();
                        mAttachInfo.mTreeObserver.dispatchOnGlobalFocusChange(focused, null);
                        mFocusedView = null;
                        mOldFocusedView = null;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Find an ancestor of focused that wants focus after its descendants and is
     * focusable in touch mode.
     * @param focused The currently focused view.
     * @return An appropriate view, or null if no such view exists.
     */
    private static ViewGroup findAncestorToTakeFocusInTouchMode(View focused) {
        ViewParent parent = focused.getParent();
        while (parent instanceof ViewGroup) {
            final ViewGroup vgParent = (ViewGroup) parent;
            if (vgParent.getDescendantFocusability() == ViewGroup.FOCUS_AFTER_DESCENDANTS
                    && vgParent.isFocusableInTouchMode()) {
                return vgParent;
            }
            if (vgParent.isRootNamespace()) {
                return null;
            } else {
                parent = vgParent.getParent();
            }
        }
        return null;
    }

    private boolean leaveTouchMode() {
        if (mView != null) {
            if (mView.hasFocus()) {
                // i learned the hard way to not trust mFocusedView :)
                mFocusedView = mView.findFocus();
                if (!(mFocusedView instanceof ViewGroup)) {
                    // some view has focus, let it keep it
                    return false;
                } else if (((ViewGroup)mFocusedView).getDescendantFocusability() !=
                        ViewGroup.FOCUS_AFTER_DESCENDANTS) {
                    // some view group has focus, and doesn't prefer its children
                    // over itself for focus, so let them keep it.
                    return false;
                }
            }

            // find the best view to give focus to in this brave new non-touch-mode
            // world
            final View focused = focusSearch(null, View.FOCUS_DOWN);
            if (focused != null) {
                return focused.requestFocus(View.FOCUS_DOWN);
            }
        }
        return false;
    }

    private void deliverInputEvent(QueuedInputEvent q) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "deliverInputEvent");
        try {
            if (q.mEvent instanceof KeyEvent) {
                mKeyEventStatus = INPUT_DISPATCH_STATE_DELIVER_EVENT;
                deliverKeyEvent(q);
            } else {
                final int source = q.mEvent.getSource();
                mMotionEventStatus = INPUT_DISPATCH_STATE_DELIVER_EVENT;
                if ((source & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                    deliverPointerEvent(q);
                } else if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    deliverTrackballEvent(q);
                } else {
                    deliverGenericMotionEvent(q);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    private void deliverPointerEvent(QueuedInputEvent q) {
        /** M:record MotionEventStatus to dump inputEvent status for ANR analysis. @{*/
        mMotionEventStatus = MOTION_DISPATCH_STATE_DELIEVER_POINTER;
        /** @} */
        
        final MotionEvent event = (MotionEvent)q.mEvent;
        final boolean isTouchEvent = event.isTouchEvent();
        if (DEBUG_INPUT) {
            Xlog.v(TAG, "deliverPointerEvent: event = " + event + ", isTouchEvent = "
                    + isTouchEvent + ",mView = " + mView + ",mAdded = " + mAdded + ", this = "
                    + this);
        }
        
        if (mInputEventConsistencyVerifier != null) {
            if (isTouchEvent) {
                mInputEventConsistencyVerifier.onTouchEvent(event, 0);
            } else {
                mInputEventConsistencyVerifier.onGenericMotionEvent(event, 0);
            }
        }

        // If there is no view, then the event will not be handled.
        if (mView == null || !mAdded) {
            finishInputEvent(q, false);
            return;
        }

        // Translate the pointer event for compatibility, if needed.
        if (mTranslator != null) {
            mTranslator.translateEventInScreenToAppWindow(event);
        }

        // Enter touch mode on down or scroll.
        final int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_SCROLL) {
            ensureTouchMode(true);
        }

        // Offset the scroll position.
        if (mCurScrollY != 0) {
            event.offsetLocation(0, mCurScrollY);
        }
        if (MEASURE_LATENCY) {
            lt.sample("A Dispatching PointerEvents", System.nanoTime() - event.getEventTimeNano());
        }

        // Remember the touch position for possible drag-initiation.
        if (isTouchEvent) {
            mLastTouchPoint.x = event.getRawX();
            mLastTouchPoint.y = event.getRawY();
        }

        /** M: when touch down event is to be dispatched to view hierarchy, log the time. @{ */
        if (action == MotionEvent.ACTION_DOWN) {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "action_down event coming");
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
        /** @} */

        // Dispatch touch to view hierarchy.
        boolean handled = mView.dispatchPointerEvent(event);
        if (MEASURE_LATENCY) {
            lt.sample("B Dispatched PointerEvents ", System.nanoTime() - event.getEventTimeNano());
        }
        if (handled) {
            finishInputEvent(q, true);
            return;
        }

        // Pointer event was unhandled.
        finishInputEvent(q, false);
    }

    private void deliverTrackballEvent(QueuedInputEvent q) {
        /** M: record inputEvent status for ANR analysis@{ */
        mMotionEventStatus = MOTION_DISPATCH_STATE_DELIEVER_TRACKBALL;
        /** @} */

        final MotionEvent event = (MotionEvent)q.mEvent;
        if (DEBUG_INPUT) {
            Xlog.v(TAG, "deliverTrackballEvent: event = " + event + ",mAdded = " + mAdded
                    + ",q.mFlags = " + q.mFlags + ",mLastWasImTarget = " + mLastWasImTarget
                    + ", this = " + this);
        }
        if (mInputEventConsistencyVerifier != null) {
            mInputEventConsistencyVerifier.onTrackballEvent(event, 0);
        }

        if (mView != null && mAdded && (q.mFlags & QueuedInputEvent.FLAG_DELIVER_POST_IME) == 0) {
            // Dispatch to the IME before propagating down the view hierarchy.
            // The IME will eventually call back into handleImeFinishedEvent.
            if (mLastWasImTarget) {
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null) {
                    final int seq = event.getSequenceNumber();
                    if (DEBUG_INPUT || DEBUG_IMF || DEBUG_IME_ANR) {
                        Xlog.v(TAG, "Sending trackball event to IME: seq="
                                + seq + " event=" + event + " this = " + this);
                    }
                    imm.dispatchTrackballEvent(mView.getContext(), seq, event,
                            mInputMethodCallback);
                    return;
                }
            }
        }

        // Not dispatching to IME, continue with post IME actions.
        deliverTrackballEventPostIme(q);
    }

    private void deliverTrackballEventPostIme(QueuedInputEvent q) {
        final MotionEvent event = (MotionEvent) q.mEvent;

        // If there is no view, then the event will not be handled.
        if (mView == null || !mAdded) {
            finishInputEvent(q, false);
            return;
        }

        // Deliver the trackball event to the view.
        if (mView.dispatchTrackballEvent(event)) {
            // If we reach this, we delivered a trackball event to mView and
            // mView consumed it. Because we will not translate the trackball
            // event into a key event, touch mode will not exit, so we exit
            // touch mode here.
            ensureTouchMode(false);

            finishInputEvent(q, true);
            mLastTrackballTime = Integer.MIN_VALUE;
            return;
        }

        // Translate the trackball event into DPAD keys and try to deliver those.
        final TrackballAxis x = mTrackballAxisX;
        final TrackballAxis y = mTrackballAxisY;

        long curTime = SystemClock.uptimeMillis();
        if ((mLastTrackballTime + MAX_TRACKBALL_DELAY) < curTime) {
            // It has been too long since the last movement,
            // so restart at the beginning.
            x.reset(0);
            y.reset(0);
            mLastTrackballTime = curTime;
        }

        final int action = event.getAction();
        final int metaState = event.getMetaState();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                x.reset(2);
                y.reset(2);
                enqueueInputEvent(new KeyEvent(curTime, curTime,
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                        InputDevice.SOURCE_KEYBOARD));
                break;
            case MotionEvent.ACTION_UP:
                x.reset(2);
                y.reset(2);
                enqueueInputEvent(new KeyEvent(curTime, curTime,
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                        InputDevice.SOURCE_KEYBOARD));
                break;
        }

        if (DEBUG_TRACKBALL) Log.v(TAG, "TB X=" + x.position + " step="
                + x.step + " dir=" + x.dir + " acc=" + x.acceleration
                + " move=" + event.getX()
                + " / Y=" + y.position + " step="
                + y.step + " dir=" + y.dir + " acc=" + y.acceleration
                + " move=" + event.getY());
        final float xOff = x.collect(event.getX(), event.getEventTime(), "X");
        final float yOff = y.collect(event.getY(), event.getEventTime(), "Y");

        // Generate DPAD events based on the trackball movement.
        // We pick the axis that has moved the most as the direction of
        // the DPAD.  When we generate DPAD events for one axis, then the
        // other axis is reset -- we don't want to perform DPAD jumps due
        // to slight movements in the trackball when making major movements
        // along the other axis.
        int keycode = 0;
        int movement = 0;
        float accel = 1;
        if (xOff > yOff) {
            movement = x.generate((2/event.getXPrecision()));
            if (movement != 0) {
                keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT
                        : KeyEvent.KEYCODE_DPAD_LEFT;
                accel = x.acceleration;
                y.reset(2);
            }
        } else if (yOff > 0) {
            movement = y.generate((2/event.getYPrecision()));
            if (movement != 0) {
                keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_DOWN
                        : KeyEvent.KEYCODE_DPAD_UP;
                accel = y.acceleration;
                x.reset(2);
            }
        }

        if (keycode != 0) {
            if (movement < 0) movement = -movement;
            int accelMovement = (int)(movement * accel);
            if (DEBUG_TRACKBALL) Log.v(TAG, "Move: movement=" + movement
                    + " accelMovement=" + accelMovement
                    + " accel=" + accel);
            if (accelMovement > movement) {
                if (DEBUG_TRACKBALL) Log.v("foo", "Delivering fake DPAD: "
                        + keycode);
                movement--;
                int repeatCount = accelMovement - movement;
                enqueueInputEvent(new KeyEvent(curTime, curTime,
                        KeyEvent.ACTION_MULTIPLE, keycode, repeatCount, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                        InputDevice.SOURCE_KEYBOARD));
            }
            while (movement > 0) {
                if (DEBUG_TRACKBALL) Log.v("foo", "Delivering fake DPAD: "
                        + keycode);
                movement--;
                curTime = SystemClock.uptimeMillis();
                enqueueInputEvent(new KeyEvent(curTime, curTime,
                        KeyEvent.ACTION_DOWN, keycode, 0, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                        InputDevice.SOURCE_KEYBOARD));
                enqueueInputEvent(new KeyEvent(curTime, curTime,
                        KeyEvent.ACTION_UP, keycode, 0, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                        InputDevice.SOURCE_KEYBOARD));
            }
            mLastTrackballTime = curTime;
        }

        // Unfortunately we can't tell whether the application consumed the keys, so
        // we always consider the trackball event handled.
        finishInputEvent(q, true);
    }

    private void deliverGenericMotionEvent(QueuedInputEvent q) {
        /** M: record InputEvent status for ANR analysis. @{ */
        mMotionEventStatus = MOTION_DISPATCH_STATE_DELIEVER_GENERIC;
        /** @} */

        final MotionEvent event = (MotionEvent)q.mEvent;
        if (mInputEventConsistencyVerifier != null) {
            mInputEventConsistencyVerifier.onGenericMotionEvent(event, 0);
        }

        if (DEBUG_INPUT) {
            Xlog.v(TAG, "deliverGenericMotionEvent: event = " + event + ", mAdded = " + mAdded
                    + ", mView = " + mView + ",mLastWasImTarget = " + mLastWasImTarget
                    + ", this = " + this);
        }

        if (mView != null && mAdded && (q.mFlags & QueuedInputEvent.FLAG_DELIVER_POST_IME) == 0) {
            // Dispatch to the IME before propagating down the view hierarchy.
            // The IME will eventually call back into handleImeFinishedEvent.
            if (mLastWasImTarget) {
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null) {
                    final int seq = event.getSequenceNumber();
                    if (DEBUG_INPUT || DEBUG_IMF) {
                        Xlog.v(TAG, "Sending generic motion event to IME: seq="
                                + seq + " event=" + event + " this = " + this);
                    }
                    imm.dispatchGenericMotionEvent(mView.getContext(), seq, event,
                            mInputMethodCallback);
                    return;
                }
            }
        }

        // Not dispatching to IME, continue with post IME actions.
        deliverGenericMotionEventPostIme(q);
    }

    private void deliverGenericMotionEventPostIme(QueuedInputEvent q) {
        final MotionEvent event = (MotionEvent) q.mEvent;
        final boolean isJoystick = (event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0;

        // If there is no view, then the event will not be handled.
        if (mView == null || !mAdded) {
            if (isJoystick) {
                updateJoystickDirection(event, false);
            }
            finishInputEvent(q, false);
            return;
        }

        // Deliver the event to the view.
        if (mView.dispatchGenericMotionEvent(event)) {
            if (isJoystick) {
                updateJoystickDirection(event, false);
            }
            finishInputEvent(q, true);
            return;
        }

        if (isJoystick) {
            // Translate the joystick event into DPAD keys and try to deliver
            // those.
            updateJoystickDirection(event, true);
            finishInputEvent(q, true);
        } else {
            finishInputEvent(q, false);
        }
    }

    private void updateJoystickDirection(MotionEvent event, boolean synthesizeNewKeys) {
        final long time = event.getEventTime();
        final int metaState = event.getMetaState();
        final int deviceId = event.getDeviceId();
        final int source = event.getSource();

        int xDirection = joystickAxisValueToDirection(event.getAxisValue(MotionEvent.AXIS_HAT_X));
        if (xDirection == 0) {
            xDirection = joystickAxisValueToDirection(event.getX());
        }

        int yDirection = joystickAxisValueToDirection(event.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (yDirection == 0) {
            yDirection = joystickAxisValueToDirection(event.getY());
        }

        if (xDirection != mLastJoystickXDirection) {
            if (mLastJoystickXKeyCode != 0) {
                enqueueInputEvent(new KeyEvent(time, time,
                        KeyEvent.ACTION_UP, mLastJoystickXKeyCode, 0, metaState,
                        deviceId, 0, KeyEvent.FLAG_FALLBACK, source));
                mLastJoystickXKeyCode = 0;
            }

            mLastJoystickXDirection = xDirection;

            if (xDirection != 0 && synthesizeNewKeys) {
                mLastJoystickXKeyCode = xDirection > 0
                        ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
                enqueueInputEvent(new KeyEvent(time, time,
                        KeyEvent.ACTION_DOWN, mLastJoystickXKeyCode, 0, metaState,
                        deviceId, 0, KeyEvent.FLAG_FALLBACK, source));
            }
        }

        if (yDirection != mLastJoystickYDirection) {
            if (mLastJoystickYKeyCode != 0) {
                enqueueInputEvent(new KeyEvent(time, time,
                        KeyEvent.ACTION_UP, mLastJoystickYKeyCode, 0, metaState,
                        deviceId, 0, KeyEvent.FLAG_FALLBACK, source));
                mLastJoystickYKeyCode = 0;
            }

            mLastJoystickYDirection = yDirection;

            if (yDirection != 0 && synthesizeNewKeys) {
                mLastJoystickYKeyCode = yDirection > 0
                        ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
                enqueueInputEvent(new KeyEvent(time, time,
                        KeyEvent.ACTION_DOWN, mLastJoystickYKeyCode, 0, metaState,
                        deviceId, 0, KeyEvent.FLAG_FALLBACK, source));
            }
        }
    }

    private static int joystickAxisValueToDirection(float value) {
        if (value >= 0.5f) {
            return 1;
        } else if (value <= -0.5f) {
            return -1;
        } else {
            return 0;
        }
    }

    /**
     * Returns true if the key is used for keyboard navigation.
     * @param keyEvent The key event.
     * @return True if the key is used for keyboard navigation.
     */
    private static boolean isNavigationKey(KeyEvent keyEvent) {
        switch (keyEvent.getKeyCode()) {
        case KeyEvent.KEYCODE_DPAD_LEFT:
        case KeyEvent.KEYCODE_DPAD_RIGHT:
        case KeyEvent.KEYCODE_DPAD_UP:
        case KeyEvent.KEYCODE_DPAD_DOWN:
        case KeyEvent.KEYCODE_DPAD_CENTER:
        case KeyEvent.KEYCODE_PAGE_UP:
        case KeyEvent.KEYCODE_PAGE_DOWN:
        case KeyEvent.KEYCODE_MOVE_HOME:
        case KeyEvent.KEYCODE_MOVE_END:
        case KeyEvent.KEYCODE_TAB:
        case KeyEvent.KEYCODE_SPACE:
        case KeyEvent.KEYCODE_ENTER:
            return true;
        }
        return false;
    }

    /**
     * Returns true if the key is used for typing.
     * @param keyEvent The key event.
     * @return True if the key is used for typing.
     */
    private static boolean isTypingKey(KeyEvent keyEvent) {
        return keyEvent.getUnicodeChar() > 0;
    }

    /**
     * See if the key event means we should leave touch mode (and leave touch mode if so).
     * @param event The key event.
     * @return Whether this key event should be consumed (meaning the act of
     *   leaving touch mode alone is considered the event).
     */
    private boolean checkForLeavingTouchModeAndConsume(KeyEvent event) {
        // Only relevant in touch mode.
        if (!mAttachInfo.mInTouchMode) {
            return false;
        }

        // Only consider leaving touch mode on DOWN or MULTIPLE actions, never on UP.
        final int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_MULTIPLE) {
            return false;
        }

        // Don't leave touch mode if the IME told us not to.
        if ((event.getFlags() & KeyEvent.FLAG_KEEP_TOUCH_MODE) != 0) {
            return false;
        }

        // If the key can be used for keyboard navigation then leave touch mode
        // and select a focused view if needed (in ensureTouchMode).
        // When a new focused view is selected, we consume the navigation key because
        // navigation doesn't make much sense unless a view already has focus so
        // the key's purpose is to set focus.
        if (isNavigationKey(event)) {
            return ensureTouchMode(false);
        }

        // If the key can be used for typing then leave touch mode
        // and select a focused view if needed (in ensureTouchMode).
        // Always allow the view to process the typing key.
        if (isTypingKey(event)) {
            ensureTouchMode(false);
            return false;
        }

        return false;
    }

    private void deliverKeyEvent(QueuedInputEvent q) {
        final KeyEvent event = (KeyEvent)q.mEvent;
        if (DEBUG_KEY || DEBUG_INPUT) {
            Xlog.v(TAG, "deliverKeyEvent: event = " + event + " to " + mView + " in " + this
                    + ",mAdded = " + mAdded + ",q.mFlags = " + q.mFlags);
        }

        if (mInputEventConsistencyVerifier != null) {
            mInputEventConsistencyVerifier.onKeyEvent(event, 0);
        }

        if (mView != null && mAdded && (q.mFlags & QueuedInputEvent.FLAG_DELIVER_POST_IME) == 0) {
            /** M: record InputEvent status for ANR analysis. @{ */
            mKeyEventStatus = KEY_DISPATCH_STATE_BEGIN_DELIEVER;
            /** @} */

            // Perform predispatching before the IME.
            if (mView.dispatchKeyEventPreIme(event)) {
                finishInputEvent(q, true);
                return;
            }

            // Dispatch to the IME before propagating down the view hierarchy.
            // The IME will eventually call back into handleImeFinishedEvent.
            if (mLastWasImTarget) {
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null) {
                    /** M: record InputEvent status for ANR analysis. @{ */
                    mKeyEventStatus = KEY_DISPATCH_STATE_DELIEVER_TO_IMM;
                    /** @} */
                    final int seq = event.getSequenceNumber();
                    if (DEBUG_IME_ANR || DEBUG_IMF || DEBUG_KEY || DEBUG_INPUT) {
                        Xlog.v(TAG, "Sending key event to IME: seq=" + seq + ", event=" + event
                                + ", this = " + this);
                    }                           
                    imm.dispatchKeyEvent(mView.getContext(), seq, event, mInputMethodCallback);
                    return;
                }
            }
        }

        // Not dispatching to IME, continue with post IME actions.
        deliverKeyEventPostIme(q);
    }

    void handleImeFinishedEvent(int seq, boolean handled) {
        final QueuedInputEvent q = mCurrentInputEvent;
        if (q != null && q.mEvent.getSequenceNumber() == seq) {
            if (DEBUG_IMF) {
                Log.v(TAG, "IME finished event: seq=" + seq
                        + " handled=" + handled + " event=" + q);
            }
            if (handled) {
                finishInputEvent(q, true);
            } else {
                if (q.mEvent instanceof KeyEvent) {
                    KeyEvent event = (KeyEvent)q.mEvent;
                    if (event.getAction() != KeyEvent.ACTION_UP) {
                        // If the window doesn't currently have input focus, then drop
                        // this event.  This could be an event that came back from the
                        // IME dispatch but the window has lost focus in the meantime.
                        if (!mAttachInfo.mHasWindowFocus) {
                            Slog.w(TAG, "Dropping event due to no window focus: " + event);
                            finishInputEvent(q, true);
                            return;
                        }
                    }
                    deliverKeyEventPostIme(q);
                } else {
                    MotionEvent event = (MotionEvent)q.mEvent;
                    if (event.getAction() != MotionEvent.ACTION_CANCEL
                            && event.getAction() != MotionEvent.ACTION_UP) {
                        // If the window doesn't currently have input focus, then drop
                        // this event.  This could be an event that came back from the
                        // IME dispatch but the window has lost focus in the meantime.
                        if (!mAttachInfo.mHasWindowFocus) {
                            Slog.w(TAG, "Dropping event due to no window focus: " + event);
                            finishInputEvent(q, true);
                            return;
                        }
                    }
                    final int source = q.mEvent.getSource();
                    if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                        deliverTrackballEventPostIme(q);
                    } else {
                        deliverGenericMotionEventPostIme(q);
                    }
                }
            }
        } else {
            if (DEBUG_IMF || DEBUG_KEY || DEBUG_INPUT) {
                Xlog.v(TAG, "IME finished event: seq=" + seq + " handled=" + handled
                        + ", event not found!");
            }
        }
    }

    private void deliverKeyEventPostIme(QueuedInputEvent q) {
        final KeyEvent event = (KeyEvent)q.mEvent;
        if (DEBUG_KEY || DEBUG_INPUT) {
            Xlog.v(TAG, "deliverKeyEventPostIme: event = " + event + ", mView = " + mView
                    + ", mAdded = " + mAdded + ", this = " + this);
        }
        /** M: record InputEvent status for ANR analysis. @{ */
        mKeyEventStatus = KEY_DISPATCH_STATE_DELIEVER_TO_VIEW;
        /** @} */

        // If the view went away, then the event will not be handled.
        if (mView == null || !mAdded) {
            finishInputEvent(q, false);
            return;
        }

        // If the key's purpose is to exit touch mode then we consume it and consider it handled.
        if (checkForLeavingTouchModeAndConsume(event)) {
            finishInputEvent(q, true);
            return;
        }

        // Make sure the fallback event policy sees all keys that will be delivered to the
        // view hierarchy.
        mFallbackEventHandler.preDispatchKeyEvent(event);

        // Deliver the key to the view hierarchy.
        if (mView.dispatchKeyEvent(event)) {
            finishInputEvent(q, true);
            return;
        }

        // If the Control modifier is held, try to interpret the key as a shortcut.
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.isCtrlPressed()
                && event.getRepeatCount() == 0
                && !KeyEvent.isModifierKey(event.getKeyCode())) {
            if (mView.dispatchKeyShortcutEvent(event)) {
                finishInputEvent(q, true);
                return;
            }
        }

        // Apply the fallback event policy.
        if (mFallbackEventHandler.dispatchKeyEvent(event)) {
            finishInputEvent(q, true);
            return;
        }

        // Handle automatic focus changes.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int direction = 0;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (event.hasNoModifiers()) {
                        direction = View.FOCUS_LEFT;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (event.hasNoModifiers()) {
                        direction = View.FOCUS_RIGHT;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (event.hasNoModifiers()) {
                        direction = View.FOCUS_UP;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (event.hasNoModifiers()) {
                        direction = View.FOCUS_DOWN;
                    }
                    break;
                case KeyEvent.KEYCODE_TAB:
                    if (event.hasNoModifiers()) {
                        direction = View.FOCUS_FORWARD;
                    } else if (event.hasModifiers(KeyEvent.META_SHIFT_ON)) {
                        direction = View.FOCUS_BACKWARD;
                    }
                    break;
            }
            if (direction != 0) {
                View focused = mView.findFocus();
                if (focused != null) {
                    View v = focused.focusSearch(direction);
                    if (v != null && v != focused) {
                        // do the math the get the interesting rect
                        // of previous focused into the coord system of
                        // newly focused view
                        focused.getFocusedRect(mTempRect);
                        if (mView instanceof ViewGroup) {
                            ((ViewGroup) mView).offsetDescendantRectToMyCoords(
                                    focused, mTempRect);
                            ((ViewGroup) mView).offsetRectIntoDescendantCoords(
                                    v, mTempRect);
                        }
                        if (v.requestFocus(direction, mTempRect)) {
                            playSoundEffect(SoundEffectConstants
                                    .getContantForFocusDirection(direction));
                            finishInputEvent(q, true);
                            return;
                        }
                    }

                    // Give the focused view a last chance to handle the dpad key.
                    if (mView.dispatchUnhandledMove(focused, direction)) {
                        finishInputEvent(q, true);
                        return;
                    }
                }
            }
        }

        // Key was unhandled.
        finishInputEvent(q, false);
    }

    /* drag/drop */
    void setLocalDragState(Object obj) {
        mLocalDragState = obj;
    }

    private void handleDragEvent(DragEvent event) {
        if (DEBUG_INPUT) {
            Xlog.v(TAG, "handleDragEvent: event = " + event + ", mView = " + mView + ", mAdded = "
                    + mAdded + ", this = " + this);
        }
        
        // From the root, only drag start/end/location are dispatched.  entered/exited
        // are determined and dispatched by the viewgroup hierarchy, who then report
        // that back here for ultimate reporting back to the framework.
        if (mView != null && mAdded) {
            final int what = event.mAction;

            if (what == DragEvent.ACTION_DRAG_EXITED) {
                // A direct EXITED event means that the window manager knows we've just crossed
                // a window boundary, so the current drag target within this one must have
                // just been exited.  Send it the usual notifications and then we're done
                // for now.
                mView.dispatchDragEvent(event);
            } else {
                // Cache the drag description when the operation starts, then fill it in
                // on subsequent calls as a convenience
                if (what == DragEvent.ACTION_DRAG_STARTED) {
                    mCurrentDragView = null;    // Start the current-recipient tracking
                    mDragDescription = event.mClipDescription;
                } else {
                    event.mClipDescription = mDragDescription;
                }

                // For events with a [screen] location, translate into window coordinates
                if ((what == DragEvent.ACTION_DRAG_LOCATION) || (what == DragEvent.ACTION_DROP)) {
                    mDragPoint.set(event.mX, event.mY);
                    if (mTranslator != null) {
                        mTranslator.translatePointInScreenToAppWindow(mDragPoint);
                    }

                    if (mCurScrollY != 0) {
                        mDragPoint.offset(0, mCurScrollY);
                    }

                    event.mX = mDragPoint.x;
                    event.mY = mDragPoint.y;
                }

                // Remember who the current drag target is pre-dispatch
                final View prevDragView = mCurrentDragView;

                // Now dispatch the drag/drop event
                boolean result = mView.dispatchDragEvent(event);

                // If we changed apparent drag target, tell the OS about it
                if (prevDragView != mCurrentDragView) {
                    try {
                        if (prevDragView != null) {
                            mWindowSession.dragRecipientExited(mWindow);
                        }
                        if (mCurrentDragView != null) {
                            mWindowSession.dragRecipientEntered(mWindow);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to note drag target change");
                    }
                }

                // Report the drop result when we're done
                if (what == DragEvent.ACTION_DROP) {
                    mDragDescription = null;
                    try {
                        Log.i(TAG, "Reporting drop result: " + result);
                        mWindowSession.reportDropResult(mWindow, result);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to report drop result");
                    }
                }

                // When the drag operation ends, release any local state object
                // that may have been in use
                if (what == DragEvent.ACTION_DRAG_ENDED) {
                    setLocalDragState(null);
                }
            }
        }
        event.recycle();
    }

    public void handleDispatchSystemUiVisibilityChanged(SystemUiVisibilityInfo args) {
        if (mSeq != args.seq) {
            // The sequence has changed, so we need to update our value and make
            // sure to do a traversal afterward so the window manager is given our
            // most recent data.
            mSeq = args.seq;
            mAttachInfo.mForceReportNewAttributes = true;
            scheduleTraversals();            
        }
        if (mView == null) return;
        if (args.localChanges != 0) {
            mView.updateLocalSystemUiVisibility(args.localValue, args.localChanges);
        }
        if (mAttachInfo != null) {
            int visibility = args.globalVisibility&View.SYSTEM_UI_CLEARABLE_FLAGS;
            if (visibility != mAttachInfo.mGlobalSystemUiVisibility) {
                mAttachInfo.mGlobalSystemUiVisibility = visibility;
                mView.dispatchSystemUiVisibilityChanged(visibility);
            }
        }
    }

    public void handleDispatchDoneAnimating() {
        if (DEBUG_DRAW) {
            Xlog.v(TAG, "handleDispatchDoneAnimating: mDirty = " + mDirty + ", mWindowsAnimating = "
                    + mWindowsAnimating + ", mIsAnimating = " + mIsAnimating + ", this = " + this);
        }
        if (mWindowsAnimating) {
            mWindowsAnimating = false;
            if (!mDirty.isEmpty() || mIsAnimating)  {
                scheduleTraversals();
            }
        }
    }

    public void getLastTouchPoint(Point outLocation) {
        outLocation.x = (int) mLastTouchPoint.x;
        outLocation.y = (int) mLastTouchPoint.y;
    }

    public void setDragFocus(View newDragTarget) {
        if (mCurrentDragView != newDragTarget) {
            mCurrentDragView = newDragTarget;
        }
    }

    private AudioManager getAudioManager() {
        if (mView == null) {
            throw new IllegalStateException("getAudioManager called when there is no mView");
        }
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) mView.getContext().getSystemService(Context.AUDIO_SERVICE);
        }
        return mAudioManager;
    }

    public AccessibilityInteractionController getAccessibilityInteractionController() {
        if (mView == null) {
            throw new IllegalStateException("getAccessibilityInteractionController"
                    + " called when there is no mView");
        }
        if (mAccessibilityInteractionController == null) {
            mAccessibilityInteractionController = new AccessibilityInteractionController(this);
        }
        return mAccessibilityInteractionController;
    }

    private int relayoutWindow(WindowManager.LayoutParams params, int viewVisibility,
            boolean insetsPending) throws RemoteException {

        float appScale = mAttachInfo.mApplicationScale;
        boolean restore = false;
        if (params != null && mTranslator != null) {
            restore = true;
            params.backup();
            mTranslator.translateWindowLayout(params);
        }
        mPendingConfiguration.seq = 0;
        if (DEBUG_LAYOUT) {
            Xlog.d(TAG, ">>>>>> CALLING relayoutW+ " + mWindow + ", params = " + params
                    + ",viewVisibility = " + viewVisibility + ", insetsPending = " + insetsPending
                    + ", appScale = " + appScale + ", mWinFrame = " + mWinFrame + ", mSeq = "
                    + mSeq + ", mPendingContentInsets = " + mPendingContentInsets
                    + ", mPendingVisibleInsets = " + mPendingVisibleInsets
                    + ", mPendingConfiguration = " + mPendingConfiguration + ", mSurface = "
                    + mSurface + ",valid = " + mSurface.isValid() + ", mOrigWindowType = " + mOrigWindowType + ",this = " + this);
        }

        if (params != null && mOrigWindowType != params.type) {
            // For compatibility with old apps, don't crash here.
            if (mTargetSdkVersion < android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                Slog.w(TAG, "Window type can not be changed after "
                        + "the window is added; ignoring change of " + mView);
                params.type = mOrigWindowType;
            }
        }
        int relayoutResult = mWindowSession.relayout(
                mWindow, mSeq, params,
                (int) (mView.getMeasuredWidth() * appScale + 0.5f),
                (int) (mView.getMeasuredHeight() * appScale + 0.5f),
                viewVisibility, insetsPending ? WindowManagerGlobal.RELAYOUT_INSETS_PENDING : 0,
                mWinFrame, mPendingContentInsets, mPendingVisibleInsets,
                mPendingConfiguration, mSurface);
        if (DEBUG_LAYOUT) {
            Xlog.d(TAG, "<<<<<< BACK FROM relayoutW- : res = " + relayoutResult + ", mWinFrame = "
                    + mWinFrame + ", mPendingContentInsets = " + mPendingContentInsets
                    + ", mPendingVisibleInsets = " + mPendingVisibleInsets
                    + ", mPendingConfiguration = " + mPendingConfiguration + ", mSurface = "
                    + mSurface + ",valid = " + mSurface.isValid() + ",params = " + params + ", this = " + this);
        }

        if (restore) {
            params.restore();
        }
        
        if (mTranslator != null) {
            mTranslator.translateRectInScreenToAppWinFrame(mWinFrame);
            mTranslator.translateRectInScreenToAppWindow(mPendingContentInsets);
            mTranslator.translateRectInScreenToAppWindow(mPendingVisibleInsets);
        }
        return relayoutResult;
    }

    /**
     * {@inheritDoc}
     */
    public void playSoundEffect(int effectId) {
        checkThread();

        try {
            final AudioManager audioManager = getAudioManager();

            switch (effectId) {
                case SoundEffectConstants.CLICK:
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
                    return;
                case SoundEffectConstants.NAVIGATION_DOWN:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_DOWN);
                    return;
                case SoundEffectConstants.NAVIGATION_LEFT:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_LEFT);
                    return;
                case SoundEffectConstants.NAVIGATION_RIGHT:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_RIGHT);
                    return;
                case SoundEffectConstants.NAVIGATION_UP:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_UP);
                    return;
                default:
                    throw new IllegalArgumentException("unknown effect id " + effectId +
                            " not defined in " + SoundEffectConstants.class.getCanonicalName());
            }
        } catch (IllegalStateException e) {
            // Exception thrown by getAudioManager() when mView is null
            Log.e(TAG, "FATAL EXCEPTION when attempting to play sound effect: " + e);
            e.printStackTrace();
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean performHapticFeedback(int effectId, boolean always) {
        try {
            return mWindowSession.performHapticFeedback(mWindow, effectId, always);
        } catch (RemoteException e) {
            Xlog.e(TAG, "performHapticFeedback RemoteException happens in " + this, e);
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    public View focusSearch(View focused, int direction) {
        checkThread();
        if (!(mView instanceof ViewGroup)) {
            return null;
        }
        return FocusFinder.getInstance().findNextFocus((ViewGroup) mView, focused, direction);
    }

    public void debug() {
        mView.debug();
    }
    
    public void dumpGfxInfo(int[] info) {
        info[0] = info[1] = 0;
        if (mView != null) {
            getGfxInfo(mView, info);
        }
    }

    private static void getGfxInfo(View view, int[] info) {
        DisplayList displayList = view.mDisplayList;
        info[0]++;
        if (displayList != null) {
            info[1] += displayList.getSize();
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                getGfxInfo(group.getChildAt(i), info);
            }
        }
    }

    public void die(boolean immediate) {
        if (DEBUG_LIFECYCLE) {
            Xlog.v(TAG, "die: immediate = " + immediate + ", mIsInTraversal = " + mIsInTraversal
                    + ",mIsDrawing = " + mIsDrawing + ",this = " + this);
        }
        
        // Make sure we do execute immediately if we are in the middle of a traversal or the damage
        // done by dispatchDetachedFromWindow will cause havoc on return.
        if (immediate && !mIsInTraversal) {
            doDie();
        } else {
            if (!mIsDrawing) {
                destroyHardwareRenderer();
            } else {
                Log.e(TAG, "Attempting to destroy the window while drawing!\n" +
                        "  window=" + this + ", title=" + mWindowAttributes.getTitle());
            }
            mHandler.sendEmptyMessage(MSG_DIE);
        }
    }

    void doDie() {
        checkThread();
        if (DEBUG_LIFECYCLE) {
            Xlog.v(TAG, "DIE in " + this + " of " + mSurface + ",mAdded = " + mAdded + ",mFirst = " + mFirst);
        }
        synchronized (this) {
            if (mAdded) {
                dispatchDetachedFromWindow();
            }

            if (mAdded && !mFirst) {
                destroyHardwareRenderer();

                if (mView != null) {
                    int viewVisibility = mView.getVisibility();
                    boolean viewVisibilityChanged = mViewVisibility != viewVisibility;
                    if (mWindowAttributesChanged || viewVisibilityChanged) {
                        // If layout params have been changed, first give them
                        // to the window manager to make sure it has the correct
                        // animation info.
                        try {
                            if ((relayoutWindow(mWindowAttributes, viewVisibility, false)
                                    & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0) {
                                mWindowSession.finishDrawing(mWindow);
                            }
                        } catch (RemoteException e) {
                            Xlog.e(TAG, "RemoteException when finish draw window " + mWindow
                                    + " in " + this, e);
                        }
                    }
    
                    mSurface.release();
                }
            }

            mAdded = false;
        }
    }

    public void requestUpdateConfiguration(Configuration config) {
        Message msg = mHandler.obtainMessage(MSG_UPDATE_CONFIGURATION, config);
        mHandler.sendMessage(msg);
    }

    public void loadSystemProperties() {
        boolean layout = SystemProperties.getBoolean(
                View.DEBUG_LAYOUT_PROPERTY, false);
        if (layout != mAttachInfo.mDebugLayout) {
            mAttachInfo.mDebugLayout = layout;
            if (!mHandler.hasMessages(MSG_INVALIDATE_WORLD)) {
                mHandler.sendEmptyMessageDelayed(MSG_INVALIDATE_WORLD, 200);
            }
        }
    }

    private void destroyHardwareRenderer() {
        AttachInfo attachInfo = mAttachInfo;
        HardwareRenderer hardwareRenderer = attachInfo.mHardwareRenderer;

        if (DEBUG_HWUI && isTargetPackage()) {
            Xlog.v(TAG, "destroyHardwareRenderer: hardwareRenderer = " + hardwareRenderer + ",mView = " + mView + ",this = "
                    + this);
        }

        if (hardwareRenderer != null) {
            if (mView != null) {
                hardwareRenderer.destroyHardwareResources(mView);
            }
            hardwareRenderer.destroy(true);
            hardwareRenderer.setRequested(false);

            attachInfo.mHardwareRenderer = null;
            attachInfo.mHardwareAccelerated = false;
        }
    }

    void dispatchImeFinishedEvent(int seq, boolean handled) {
        Message msg = mHandler.obtainMessage(MSG_IME_FINISHED_EVENT);
        msg.arg1 = seq;
        msg.arg2 = handled ? 1 : 0;
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    public void dispatchFinishInputConnection(InputConnection connection) {
        Message msg = mHandler.obtainMessage(MSG_FINISH_INPUT_CONNECTION, connection);
        mHandler.sendMessage(msg);
    }

    public void dispatchResized(Rect frame, Rect contentInsets,
            Rect visibleInsets, boolean reportDraw, Configuration newConfig) {
        if (DEBUG_LAYOUT) {
            Xlog.v(TAG, "Resizing " + this + ": frame=" + frame.toShortString()
                    + " contentInsets=" + contentInsets.toShortString()
                    + " visibleInsets=" + visibleInsets.toShortString()
                    + " reportDraw=" + reportDraw);
        }
        Message msg = mHandler.obtainMessage(reportDraw ? MSG_RESIZED_REPORT : MSG_RESIZED);
        if (mTranslator != null) {
            mTranslator.translateRectInScreenToAppWindow(frame);
            mTranslator.translateRectInScreenToAppWindow(contentInsets);
            mTranslator.translateRectInScreenToAppWindow(visibleInsets);
        }
        SomeArgs args = SomeArgs.obtain();
        final boolean sameProcessCall = (Binder.getCallingPid() == android.os.Process.myPid());
        args.arg1 = sameProcessCall ? new Rect(frame) : frame;
        args.arg2 = sameProcessCall ? new Rect(contentInsets) : contentInsets;
        args.arg3 = sameProcessCall ? new Rect(visibleInsets) : visibleInsets;
        args.arg4 = sameProcessCall && newConfig != null ? new Configuration(newConfig) : newConfig;
        msg.obj = args;
        mHandler.sendMessage(msg);
    }

    public void dispatchMoved(int newX, int newY) {
        if (DEBUG_LAYOUT) {
            Xlog.v(TAG, "Window moved " + this + ": newX=" + newX + " newY=" + newY);
        }

        if (mTranslator != null) {
            PointF point = new PointF(newX, newY);
            mTranslator.translatePointInScreenToAppWindow(point);
            newX = (int) (point.x + 0.5);
            newY = (int) (point.y + 0.5);
        }
        Message msg = mHandler.obtainMessage(MSG_WINDOW_MOVED, newX, newY);
        mHandler.sendMessage(msg);
    }

    /**
     * Represents a pending input event that is waiting in a queue.
     *
     * Input events are processed in serial order by the timestamp specified by
     * {@link InputEvent#getEventTimeNano()}.  In general, the input dispatcher delivers
     * one input event to the application at a time and waits for the application
     * to finish handling it before delivering the next one.
     *
     * However, because the application or IME can synthesize and inject multiple
     * key events at a time without going through the input dispatcher, we end up
     * needing a queue on the application's side.
     */
    private static final class QueuedInputEvent {
        public static final int FLAG_DELIVER_POST_IME = 1;

        public QueuedInputEvent mNext;

        public InputEvent mEvent;
        public InputEventReceiver mReceiver;
        public int mFlags;
    }

    private QueuedInputEvent obtainQueuedInputEvent(InputEvent event,
            InputEventReceiver receiver, int flags) {
        QueuedInputEvent q = mQueuedInputEventPool;
        if (q != null) {
            mQueuedInputEventPoolSize -= 1;
            mQueuedInputEventPool = q.mNext;
            q.mNext = null;
        } else {
            q = new QueuedInputEvent();
        }

        q.mEvent = event;
        q.mReceiver = receiver;
        q.mFlags = flags;
        return q;
    }

    private void recycleQueuedInputEvent(QueuedInputEvent q) {
        q.mEvent = null;
        q.mReceiver = null;

        if (mQueuedInputEventPoolSize < MAX_QUEUED_INPUT_EVENT_POOL_SIZE) {
            mQueuedInputEventPoolSize += 1;
            q.mNext = mQueuedInputEventPool;
            mQueuedInputEventPool = q;
        }
    }

    void enqueueInputEvent(InputEvent event) {
        enqueueInputEvent(event, null, 0, false);
    }

    void enqueueInputEvent(InputEvent event,
            InputEventReceiver receiver, int flags, boolean processImmediately) {
        QueuedInputEvent q = obtainQueuedInputEvent(event, receiver, flags);

        // Always enqueue the input event in order, regardless of its time stamp.
        // We do this because the application or the IME may inject key events
        // in response to touch events and we want to ensure that the injected keys
        // are processed in the order they were received and we cannot trust that
        // the time stamp of injected events are monotonic.
        QueuedInputEvent last = mFirstPendingInputEvent;
        if (last == null) {
            mFirstPendingInputEvent = q;
        } else {
            while (last.mNext != null) {
                last = last.mNext;
            }
            last.mNext = q;
        }

        /** M: record current key event and motion event to dump input event info for ANR analysis. @{ */
        if (event instanceof KeyEvent) {
            mKeyEventStatus = INPUT_DISPATCH_STATE_ENQUEUE_EVENT;
        } else {
            mMotionEventStatus = INPUT_DISPATCH_STATE_ENQUEUE_EVENT;
        }
        /** @} */
        if (DEBUG_INPUT || DEBUG_KEY || DEBUG_MOTION) {
            Xlog.v(TAG, "enqueueInputEvent: event = " + event + ",processImmediately = "
                    + processImmediately + ",mProcessInputEventsScheduled = "
                    + mProcessInputEventsScheduled + ", this = " + this);
        }

        if (processImmediately) {
            doProcessInputEvents();
        } else {
            scheduleProcessInputEvents();
        }
    }

    private void scheduleProcessInputEvents() {
        if (DEBUG_INPUT || DEBUG_KEY || DEBUG_MOTION) {
            Xlog.v(TAG, "scheduleProcessInputEvents: mFirstPendingInputEvent = "
                    + (mFirstPendingInputEvent != null ? mFirstPendingInputEvent.mEvent : "")
                    + ",mProcessInputEventsScheduled = " + mProcessInputEventsScheduled
                    + ", this = " + this);
        }
        if (!mProcessInputEventsScheduled) {
            mKeyEventStatus = INPUT_DISPATCH_STATE_SCHEDULE_EVENT;
            mProcessInputEventsScheduled = true;
            Message msg = mHandler.obtainMessage(MSG_PROCESS_INPUT_EVENTS);
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    void doProcessInputEvents() {
        while (mCurrentInputEvent == null && mFirstPendingInputEvent != null) {
            QueuedInputEvent q = mFirstPendingInputEvent;
            mFirstPendingInputEvent = q.mNext;
            q.mNext = null;
            mCurrentInputEvent = q;

            /** M: record current key event and motion event to dump input event info for ANR analysis. @{ */
            if (q.mEvent instanceof KeyEvent) {
                mKeyEventStatus = INPUT_DISPATCH_STATE_PROCESS_EVENT;
            } else {
                mMotionEventStatus = INPUT_DISPATCH_STATE_PROCESS_EVENT;
            }
            /** @} */
            if (DEBUG_INPUT || DEBUG_KEY || DEBUG_MOTION) {
                Xlog.v(TAG, "doProcessInputEvents: mCurrentInputEvent = " + mCurrentInputEvent.mEvent
                        + ", this = " + this);
            }
            deliverInputEvent(q);
        }

        // We are done processing all input events that we can process right now
        // so we can clear the pending flag immediately.
        if (mProcessInputEventsScheduled) {
            mProcessInputEventsScheduled = false;
            mHandler.removeMessages(MSG_PROCESS_INPUT_EVENTS);
        }
    }

    private void finishInputEvent(QueuedInputEvent q, boolean handled) {
        if (DEBUG_INPUT || DEBUG_KEY || DEBUG_MOTION) {
            Xlog.v(TAG, "finishInputEvent: handled = " + handled + ",event = " + q.mEvent
                    + ", this = " + this);
        }
        
        /** M: record current key event and motion event to dump input event info for ANR analysis. @{ */
        if (q.mEvent instanceof KeyEvent) {
            mCurrentKeyEvent = null;
            mKeyEventStatus = INPUT_DISPATCH_STATE_FINISHED;
        } else {
            mCurrentMotion = null;
            mMotionEventStatus = INPUT_DISPATCH_STATE_FINISHED;
        }
        /** @} */
        
        if (q != mCurrentInputEvent) {
            throw new IllegalStateException("finished input event out of order");
        }

        if (q.mReceiver != null) {
            q.mReceiver.finishInputEvent(q.mEvent, handled);
        } else {
            q.mEvent.recycleIfNeededAfterDispatch();
        }

        recycleQueuedInputEvent(q);

        mCurrentInputEvent = null;
        if (mFirstPendingInputEvent != null) {
            scheduleProcessInputEvents();
        }
    }

    void scheduleConsumeBatchedInput() {
        if (DEBUG_INPUT || DEBUG_KEY || DEBUG_MOTION) {
            Xlog.v(TAG, "scheduleConsumeBatchedInput: mConsumeBatchedInputScheduled = "
                    + mConsumeBatchedInputScheduled + ",mFirstPendingInputEvent = "
                    + mFirstPendingInputEvent + ", this = " + this);
        }
        if (!mConsumeBatchedInputScheduled) {
            mConsumeBatchedInputScheduled = true;
            mChoreographer.postCallback(Choreographer.CALLBACK_INPUT,
                    mConsumedBatchedInputRunnable, null);
        }
    }

    void unscheduleConsumeBatchedInput() {
        if (DEBUG_INPUT || DEBUG_KEY || DEBUG_MOTION) {
            Xlog.v(TAG, "unscheduleConsumeBatchedInput: mConsumeBatchedInputScheduled = "
                    + mConsumeBatchedInputScheduled + ", this = " + this);
        }
        if (mConsumeBatchedInputScheduled) {
            mConsumeBatchedInputScheduled = false;
            mChoreographer.removeCallbacks(Choreographer.CALLBACK_INPUT,
                    mConsumedBatchedInputRunnable, null);
        }
    }

    void doConsumeBatchedInput(long frameTimeNanos) {
        if ((mFirstPendingInputEvent != null) && (DEBUG_INPUT || DEBUG_KEY || DEBUG_MOTION)) {
            Xlog.v(TAG, "doConsumeBatchedInput: frameTimeNanos = " + frameTimeNanos
                    + ",mConsumeBatchedInputScheduled = " + mConsumeBatchedInputScheduled
                    + ",mFirstPendingInputEvent = " + mFirstPendingInputEvent.mEvent + ", this = "
                    + this);
        }
        if (mConsumeBatchedInputScheduled) {
            mConsumeBatchedInputScheduled = false;
            if (mInputEventReceiver != null) {
                mInputEventReceiver.consumeBatchedInputEvents(frameTimeNanos);
            }
            doProcessInputEvents();
        }
    }

    final class TraversalRunnable implements Runnable {
        @Override
        public void run() {
            doTraversal();
        }
    }
    final TraversalRunnable mTraversalRunnable = new TraversalRunnable();

    final class WindowInputEventReceiver extends InputEventReceiver {
        public WindowInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            /** M: record current key event and motion event to dump input event info for ANR analysis. @{ */
            if (event instanceof KeyEvent) {
                mCurrentKeyEvent = (KeyEvent) event;
                mKeyEventStartTime = System.currentTimeMillis();
                mKeyEventStatus = INPUT_DISPATCH_STATE_STARTED;
            } else {
                mCurrentMotion = (MotionEvent) event;
                mMotionEventStartTime = System.currentTimeMillis();
                mMotionEventStatus = INPUT_DISPATCH_STATE_STARTED;
            }
            /** @} */
            enqueueInputEvent(event, this, 0, true);
        }

        @Override
        public void onBatchedInputEventPending() {
            if (DEBUG_INPUT || DEBUG_KEY || DEBUG_MOTION) {
                Xlog.v(TAG, "onBatchedInputEventPending: this = " + this);
            }
            scheduleConsumeBatchedInput();
        }

        @Override
        public void dispose() {
            unscheduleConsumeBatchedInput();
            super.dispose();
        }
    }
    WindowInputEventReceiver mInputEventReceiver;

    final class ConsumeBatchedInputRunnable implements Runnable {
        @Override
        public void run() {
            doConsumeBatchedInput(mChoreographer.getFrameTimeNanos());
        }
    }
    final ConsumeBatchedInputRunnable mConsumedBatchedInputRunnable =
            new ConsumeBatchedInputRunnable();
    boolean mConsumeBatchedInputScheduled;

    final class InvalidateOnAnimationRunnable implements Runnable {
        private boolean mPosted;
        private ArrayList<View> mViews = new ArrayList<View>();
        private ArrayList<AttachInfo.InvalidateInfo> mViewRects =
                new ArrayList<AttachInfo.InvalidateInfo>();
        private View[] mTempViews;
        private AttachInfo.InvalidateInfo[] mTempViewRects;

        public void addView(View view) {
            synchronized (this) {
                mViews.add(view);
                postIfNeededLocked();
            }
        }

        public void addViewRect(AttachInfo.InvalidateInfo info) {
            synchronized (this) {
                mViewRects.add(info);
                postIfNeededLocked();
            }
        }

        public void removeView(View view) {
            synchronized (this) {
                mViews.remove(view);

                for (int i = mViewRects.size(); i-- > 0; ) {
                    AttachInfo.InvalidateInfo info = mViewRects.get(i);
                    if (info.target == view) {
                        mViewRects.remove(i);
                        info.release();
                    }
                }

                if (mPosted && mViews.isEmpty() && mViewRects.isEmpty()) {
                    mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION, this, null);
                    mPosted = false;
                }
            }
        }

        @Override
        public void run() {
            final int viewCount;
            final int viewRectCount;
            synchronized (this) {
                mPosted = false;

                viewCount = mViews.size();
                if (viewCount != 0) {
                    mTempViews = mViews.toArray(mTempViews != null
                            ? mTempViews : new View[viewCount]);
                    mViews.clear();
                }

                viewRectCount = mViewRects.size();
                if (viewRectCount != 0) {
                    mTempViewRects = mViewRects.toArray(mTempViewRects != null
                            ? mTempViewRects : new AttachInfo.InvalidateInfo[viewRectCount]);
                    mViewRects.clear();
                }
            }

            for (int i = 0; i < viewCount; i++) {
                mTempViews[i].invalidate();
                mTempViews[i] = null;
            }

            for (int i = 0; i < viewRectCount; i++) {
                final View.AttachInfo.InvalidateInfo info = mTempViewRects[i];
                info.target.invalidate(info.left, info.top, info.right, info.bottom);
                info.release();
            }
        }

        private void postIfNeededLocked() {
            if (!mPosted) {
                mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION, this, null);
                mPosted = true;
            }
        }
    }
    final InvalidateOnAnimationRunnable mInvalidateOnAnimationRunnable =
            new InvalidateOnAnimationRunnable();

    public void dispatchInvalidateDelayed(View view, long delayMilliseconds) {
        Message msg = mHandler.obtainMessage(MSG_INVALIDATE, view);
        mHandler.sendMessageDelayed(msg, delayMilliseconds);
    }

    public void dispatchInvalidateRectDelayed(AttachInfo.InvalidateInfo info,
            long delayMilliseconds) {
        final Message msg = mHandler.obtainMessage(MSG_INVALIDATE_RECT, info);
        mHandler.sendMessageDelayed(msg, delayMilliseconds);
    }

    public void dispatchInvalidateOnAnimation(View view) {
        mInvalidateOnAnimationRunnable.addView(view);
    }

    public void dispatchInvalidateRectOnAnimation(AttachInfo.InvalidateInfo info) {
        mInvalidateOnAnimationRunnable.addViewRect(info);
    }

    public void enqueueDisplayList(DisplayList displayList) {
        mDisplayLists.add(displayList);

        mHandler.removeMessages(MSG_INVALIDATE_DISPLAY_LIST);
        Message msg = mHandler.obtainMessage(MSG_INVALIDATE_DISPLAY_LIST);
        mHandler.sendMessage(msg);
    }

    public void dequeueDisplayList(DisplayList displayList) {
        if (mDisplayLists.remove(displayList)) {
            displayList.invalidate();
            if (mDisplayLists.size() == 0) {
                mHandler.removeMessages(MSG_INVALIDATE_DISPLAY_LIST);
            }
        }
    }

    public void cancelInvalidate(View view) {
        mHandler.removeMessages(MSG_INVALIDATE, view);
        // fixme: might leak the AttachInfo.InvalidateInfo objects instead of returning
        // them to the pool
        mHandler.removeMessages(MSG_INVALIDATE_RECT, view);
        mInvalidateOnAnimationRunnable.removeView(view);
    }

    public void dispatchKey(KeyEvent event) {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_KEY, event);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    public void dispatchKeyFromIme(KeyEvent event) {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_KEY_FROM_IME, event);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    public void dispatchUnhandledKey(KeyEvent event) {
        if ((event.getFlags() & KeyEvent.FLAG_FALLBACK) == 0) {
            final KeyCharacterMap kcm = event.getKeyCharacterMap();
            final int keyCode = event.getKeyCode();
            final int metaState = event.getMetaState();

            // Check for fallback actions specified by the key character map.
            KeyCharacterMap.FallbackAction fallbackAction =
                    kcm.getFallbackAction(keyCode, metaState);
            if (fallbackAction != null) {
                final int flags = event.getFlags() | KeyEvent.FLAG_FALLBACK;
                KeyEvent fallbackEvent = KeyEvent.obtain(
                        event.getDownTime(), event.getEventTime(),
                        event.getAction(), fallbackAction.keyCode,
                        event.getRepeatCount(), fallbackAction.metaState,
                        event.getDeviceId(), event.getScanCode(),
                        flags, event.getSource(), null);
                fallbackAction.recycle();

                dispatchKey(fallbackEvent);
            }
        }
    }

    public void dispatchAppVisibility(boolean visible) {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_APP_VISIBILITY);
        msg.arg1 = visible ? 1 : 0;
        mHandler.sendMessage(msg);
    }

    public void dispatchScreenStateChange(boolean on) {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_SCREEN_STATE);
        msg.arg1 = on ? 1 : 0;
        mHandler.sendMessage(msg);
    }

    public void dispatchGetNewSurface() {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_GET_NEW_SURFACE);
        mHandler.sendMessage(msg);
    }

    public void windowFocusChanged(boolean hasFocus, boolean inTouchMode) {
        Message msg = Message.obtain();
        msg.what = MSG_WINDOW_FOCUS_CHANGED;
        msg.arg1 = hasFocus ? 1 : 0;
        msg.arg2 = inTouchMode ? 1 : 0;
        mHandler.sendMessage(msg);
    }

    public void dispatchCloseSystemDialogs(String reason) {
        Message msg = Message.obtain();
        msg.what = MSG_CLOSE_SYSTEM_DIALOGS;
        msg.obj = reason;
        mHandler.sendMessage(msg);
    }

    public void dispatchDragEvent(DragEvent event) {
        final int what;
        if (event.getAction() == DragEvent.ACTION_DRAG_LOCATION) {
            what = MSG_DISPATCH_DRAG_LOCATION_EVENT;
            mHandler.removeMessages(what);
        } else {
            what = MSG_DISPATCH_DRAG_EVENT;
        }
        Message msg = mHandler.obtainMessage(what, event);
        mHandler.sendMessage(msg);
    }

    public void dispatchSystemUiVisibilityChanged(int seq, int globalVisibility,
            int localValue, int localChanges) {
        SystemUiVisibilityInfo args = new SystemUiVisibilityInfo();
        args.seq = seq;
        args.globalVisibility = globalVisibility;
        args.localValue = localValue;
        args.localChanges = localChanges;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_DISPATCH_SYSTEM_UI_VISIBILITY, args));
    }

    public void dispatchDoneAnimating() {
        mHandler.sendEmptyMessage(MSG_DISPATCH_DONE_ANIMATING);
    }

    public void dispatchCheckFocus() {
        if (!mHandler.hasMessages(MSG_CHECK_FOCUS)) {
            // This will result in a call to checkFocus() below.
            mHandler.sendEmptyMessage(MSG_CHECK_FOCUS);
        }
    }

    /**
     * Post a callback to send a
     * {@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED} event.
     * This event is send at most once every
     * {@link ViewConfiguration#getSendRecurringAccessibilityEventsInterval()}.
     */
    private void postSendWindowContentChangedCallback(View source) {
        if (mSendWindowContentChangedAccessibilityEvent == null) {
            mSendWindowContentChangedAccessibilityEvent =
                new SendWindowContentChangedAccessibilityEvent();
        }
        View oldSource = mSendWindowContentChangedAccessibilityEvent.mSource;
        if (oldSource == null) {
            mSendWindowContentChangedAccessibilityEvent.mSource = source;
            mHandler.postDelayed(mSendWindowContentChangedAccessibilityEvent,
                    ViewConfiguration.getSendRecurringAccessibilityEventsInterval());
        } else {
            mSendWindowContentChangedAccessibilityEvent.mSource =
                    getCommonPredecessor(oldSource, source);
        }
    }

    /**
     * Remove a posted callback to send a
     * {@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED} event.
     */
    private void removeSendWindowContentChangedCallback() {
        if (mSendWindowContentChangedAccessibilityEvent != null) {
            mHandler.removeCallbacks(mSendWindowContentChangedAccessibilityEvent);
        }
    }

    public boolean showContextMenuForChild(View originalView) {
        return false;
    }

    public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback) {
        return null;
    }

    public void createContextMenu(ContextMenu menu) {
    }

    public void childDrawableStateChanged(View child) {
    }

    public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (mView == null) {
            return false;
        }
        // Intercept accessibility focus events fired by virtual nodes to keep
        // track of accessibility focus position in such nodes.
        final int eventType = event.getEventType();
        switch (eventType) {
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                final long sourceNodeId = event.getSourceNodeId();
                final int accessibilityViewId = AccessibilityNodeInfo.getAccessibilityViewId(
                        sourceNodeId);
                View source = mView.findViewByAccessibilityId(accessibilityViewId);
                if (source != null) {
                    AccessibilityNodeProvider provider = source.getAccessibilityNodeProvider();
                    if (provider != null) {
                        AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(
                                AccessibilityNodeInfo.getVirtualDescendantId(sourceNodeId));
                        setAccessibilityFocus(source, node);
                    }
                }
            } break;
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                final long sourceNodeId = event.getSourceNodeId();
                final int accessibilityViewId = AccessibilityNodeInfo.getAccessibilityViewId(
                        sourceNodeId);
                View source = mView.findViewByAccessibilityId(accessibilityViewId);
                if (source != null) {
                    AccessibilityNodeProvider provider = source.getAccessibilityNodeProvider();
                    if (provider != null) {
                        setAccessibilityFocus(null, null);
                    }
                }
            } break;
        }
        mAccessibilityManager.sendAccessibilityEvent(event);
        return true;
    }

    @Override
    public void childAccessibilityStateChanged(View child) {
        postSendWindowContentChangedCallback(child);
    }

    private View getCommonPredecessor(View first, View second) {
        if (mAttachInfo != null) {
            if (mTempHashSet == null) {
                mTempHashSet = new HashSet<View>();
            }
            HashSet<View> seen = mTempHashSet;
            seen.clear();
            View firstCurrent = first;
            while (firstCurrent != null) {
                seen.add(firstCurrent);
                ViewParent firstCurrentParent = firstCurrent.mParent;
                if (firstCurrentParent instanceof View) {
                    firstCurrent = (View) firstCurrentParent;
                } else {
                    firstCurrent = null;
                }
            }
            View secondCurrent = second;
            while (secondCurrent != null) {
                if (seen.contains(secondCurrent)) {
                    seen.clear();
                    return secondCurrent;
                }
                ViewParent secondCurrentParent = secondCurrent.mParent;
                if (secondCurrentParent instanceof View) {
                    secondCurrent = (View) secondCurrentParent;
                } else {
                    secondCurrent = null;
                }
            }
            seen.clear();
        }
        return null;
    }

    void checkThread() {
        if (mThread != Thread.currentThread()) {
            throw new CalledFromWrongThreadException(
                    "Only the original thread that created a view hierarchy can touch its views.");
        }
    }

    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // ViewAncestor never intercepts touch event, so this can be a no-op
    }

    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        final boolean scrolled = scrollToRectOrFocus(rectangle, immediate);
        if (rectangle != null) {
            mTempRect.set(rectangle);
            mTempRect.offset(0, -mCurScrollY);
            mTempRect.offset(mAttachInfo.mWindowLeft, mAttachInfo.mWindowTop);
            try {
                mWindowSession.onRectangleOnScreenRequested(mWindow, mTempRect, immediate);
            } catch (RemoteException re) {
                /* ignore */
            }
        }
        return scrolled;
    }

    public void childHasTransientStateChanged(View child, boolean hasTransientState) {
        // Do nothing.
    }

    class TakenSurfaceHolder extends BaseSurfaceHolder {
        @Override
        public boolean onAllowLockCanvas() {
            return mDrawingAllowed;
        }

        @Override
        public void onRelayoutContainer() {
            // Not currently interesting -- from changing between fixed and layout size.
        }

        public void setFormat(int format) {
            ((RootViewSurfaceTaker)mView).setSurfaceFormat(format);
        }

        public void setType(int type) {
            ((RootViewSurfaceTaker)mView).setSurfaceType(type);
        }
        
        @Override
        public void onUpdateSurface() {
            // We take care of format and type changes on our own.
            throw new IllegalStateException("Shouldn't be here");
        }

        public boolean isCreating() {
            return mIsCreating;
        }

        @Override
        public void setFixedSize(int width, int height) {
            throw new UnsupportedOperationException(
                    "Currently only support sizing from layout");
        }
        
        public void setKeepScreenOn(boolean screenOn) {
            ((RootViewSurfaceTaker)mView).setSurfaceKeepScreenOn(screenOn);
        }
    }
    
    static final class InputMethodCallback implements InputMethodManager.FinishedEventCallback {
        private WeakReference<ViewRootImpl> mViewAncestor;

        public InputMethodCallback(ViewRootImpl viewAncestor) {
            mViewAncestor = new WeakReference<ViewRootImpl>(viewAncestor);
        }

        @Override
        public void finishedEvent(int seq, boolean handled) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (DEBUG_IME_ANR || DEBUG_IMF || DEBUG_KEY || DEBUG_INPUT) {
                Xlog.d(TAG, "IME finishedEvent: seq = " + seq + ",handled = " + handled
                        + ",viewAncestor = " + viewAncestor);
            }
            if (viewAncestor != null) {
                viewAncestor.dispatchImeFinishedEvent(seq, handled);
            }
        }
    }

    static class W extends IWindow.Stub {
        private final WeakReference<ViewRootImpl> mViewAncestor;
        private final IWindowSession mWindowSession;

        W(ViewRootImpl viewAncestor) {
            mViewAncestor = new WeakReference<ViewRootImpl>(viewAncestor);
            mWindowSession = viewAncestor.mWindowSession;
        }

        public void resized(Rect frame, Rect contentInsets,
                Rect visibleInsets, boolean reportDraw, Configuration newConfig) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchResized(frame, contentInsets,
                        visibleInsets, reportDraw, newConfig);
            }
        }

        @Override
        public void moved(int newX, int newY) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchMoved(newX, newY);
            }
        }

        public void dispatchAppVisibility(boolean visible) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (DEBUG_LIFECYCLE) {
                Xlog.v(TAG, "dispatchAppVisibility: visible = " + visible + ", viewAncestor = " + viewAncestor);
            }
            if (viewAncestor != null) {
                viewAncestor.dispatchAppVisibility(visible);
            }
        }

        public void dispatchScreenState(boolean on) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (DEBUG_LIFECYCLE) {
                Xlog.v(TAG, "dispatchScreenState: on = " + on + ", viewAncestor = " + viewAncestor);
            }
            if (viewAncestor != null) {
                viewAncestor.dispatchScreenStateChange(on);
            }
        }

        public void dispatchGetNewSurface() {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchGetNewSurface();
            }
        }

        public void windowFocusChanged(boolean hasFocus, boolean inTouchMode) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (DEBUG_IMF) {
                Xlog.v(TAG, "W windowFocusChanged: hasFocus = " + hasFocus + ", inTouchMode = "
                        + inTouchMode + ", viewAncestor = " + viewAncestor + ", this = " + this);
            }
            if (viewAncestor != null) {
                viewAncestor.windowFocusChanged(hasFocus, inTouchMode);
            }
        }

        private static int checkCallingPermission(String permission) {
            try {
                return ActivityManagerNative.getDefault().checkPermission(
                        permission, Binder.getCallingPid(), Binder.getCallingUid());
            } catch (RemoteException e) {
                return PackageManager.PERMISSION_DENIED;
            }
        }

        public void executeCommand(String command, String parameters, ParcelFileDescriptor out) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                final View view = viewAncestor.mView;
                if (view != null) {
                    if (checkCallingPermission(Manifest.permission.DUMP) !=
                            PackageManager.PERMISSION_GRANTED) {
                        throw new SecurityException("Insufficient permissions to invoke"
                                + " executeCommand() from pid=" + Binder.getCallingPid()
                                + ", uid=" + Binder.getCallingUid());
                    }

                    OutputStream clientStream = null;
                    try {
                        clientStream = new ParcelFileDescriptor.AutoCloseOutputStream(out);
                        ViewDebug.dispatchCommand(view, command, parameters, clientStream);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (clientStream != null) {
                            try {
                                clientStream.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }
        
        public void closeSystemDialogs(String reason) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (LOCAL_LOGV) {
                Xlog.v(TAG, "Close system dialogs in " + viewAncestor + " for " + reason);
            }
            if (viewAncestor != null) {
                viewAncestor.dispatchCloseSystemDialogs(reason);
            }
        }
        
        public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep,
                boolean sync) {
            if (sync) {
                try {
                    mWindowSession.wallpaperOffsetsComplete(asBinder());
                } catch (RemoteException e) {
                    Xlog.e(TAG, "RemoteException happens when dispatchWallpaperOffsets.", e);
                }
            }
        }

        public void dispatchWallpaperCommand(String action, int x, int y,
                int z, Bundle extras, boolean sync) {
            if (sync) {
                try {
                    mWindowSession.wallpaperCommandComplete(asBinder(), null);
                } catch (RemoteException e) {
                    Xlog.e(TAG, "RemoteException happens when dispatchWallpaperCommand.", e);
                }
            }
        }

        /* Drag/drop */
        public void dispatchDragEvent(DragEvent event) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (LOCAL_LOGV || DEBUG_INPUT) {
                Xlog.v(TAG, "Dispatch drag event " + event + " in " + viewAncestor);
            }
            if (viewAncestor != null) {
                viewAncestor.dispatchDragEvent(event);
            }
        }

        public void dispatchSystemUiVisibilityChanged(int seq, int globalVisibility,
                int localValue, int localChanges) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (LOCAL_LOGV) {
                Xlog.v(TAG, "dispatchSystemUiVisibilityChanged: seq = " + seq
                        + ", globalVisibility = " + globalVisibility + ", localValue = "
                        + localValue + ", localChanges = " + localChanges + ", viewAncestor"
                        + viewAncestor);
            }
            if (viewAncestor != null) {
                viewAncestor.dispatchSystemUiVisibilityChanged(seq, globalVisibility,
                        localValue, localChanges);
            }
        }

        public void doneAnimating() {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (DEBUG_DRAW) {
                Xlog.v(TAG, "doneAnimating: viewAncestor" + viewAncestor);
            }
            if (viewAncestor != null) {
                viewAncestor.dispatchDoneAnimating();
            }
        }

        /** M: [ALPS00110138]*/
        public void dispatchPositionChanged(int left, int top) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (DEBUG_LAYOUT) {
                Xlog.v(TAG, "dispatchPositionChanged: left = " + left + ", top = " + top
                        + ", viewAncestor" + viewAncestor);
            }
            if (viewAncestor != null) {
                // Need to scale x,y when set mWindowTop if the mTranslator is not null.
                if (viewAncestor.mTranslator == null) {
                    viewAncestor.mAttachInfo.mWindowLeft = left;
                    viewAncestor.mAttachInfo.mWindowTop = top;
                } else {
                    viewAncestor.mAttachInfo.mWindowLeft = (int) (left
                            * viewAncestor.mTranslator.applicationInvertedScale + 0.5f);
                    viewAncestor.mAttachInfo.mWindowTop = (int) (top
                            * viewAncestor.mTranslator.applicationInvertedScale + 0.5f);
                }
            }
        }
        /** @} */

        /** M: API used to switch on/off debug flags. @{ */
        public void enableLog(boolean enable) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.enableLog(enable);
            }
        }
        /** @} */
        
        /** M: dump InputEvent status for ANR analysis. @{ */
        public void dumpInputDispatchingStatus() {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dumpInputDispatchingStatus();
            }
        }
        /** @} */
    }

    /**
     * Maintains state information for a single trackball axis, generating
     * discrete (DPAD) movements based on raw trackball motion.
     */
    static final class TrackballAxis {
        /**
         * The maximum amount of acceleration we will apply.
         */
        static final float MAX_ACCELERATION = 20;

        /**
         * The maximum amount of time (in milliseconds) between events in order
         * for us to consider the user to be doing fast trackball movements,
         * and thus apply an acceleration.
         */
        static final long FAST_MOVE_TIME = 150;

        /**
         * Scaling factor to the time (in milliseconds) between events to how
         * much to multiple/divide the current acceleration.  When movement
         * is < FAST_MOVE_TIME this multiplies the acceleration; when >
         * FAST_MOVE_TIME it divides it.
         */
        static final float ACCEL_MOVE_SCALING_FACTOR = (1.0f/40);

        float position;
        float absPosition;
        float acceleration = 1;
        long lastMoveTime = 0;
        int step;
        int dir;
        int nonAccelMovement;

        void reset(int _step) {
            position = 0;
            acceleration = 1;
            lastMoveTime = 0;
            step = _step;
            dir = 0;
        }

        /**
         * Add trackball movement into the state.  If the direction of movement
         * has been reversed, the state is reset before adding the
         * movement (so that you don't have to compensate for any previously
         * collected movement before see the result of the movement in the
         * new direction).
         *
         * @return Returns the absolute value of the amount of movement
         * collected so far.
         */
        float collect(float off, long time, String axis) {
            long normTime;
            if (off > 0) {
                normTime = (long)(off * FAST_MOVE_TIME);
                if (dir < 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to positive!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                    lastMoveTime = 0;
                }
                dir = 1;
            } else if (off < 0) {
                normTime = (long)((-off) * FAST_MOVE_TIME);
                if (dir > 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to negative!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                    lastMoveTime = 0;
                }
                dir = -1;
            } else {
                normTime = 0;
            }

            // The number of milliseconds between each movement that is
            // considered "normal" and will not result in any acceleration
            // or deceleration, scaled by the offset we have here.
            if (normTime > 0) {
                long delta = time - lastMoveTime;
                lastMoveTime = time;
                float acc = acceleration;
                if (delta < normTime) {
                    // The user is scrolling rapidly, so increase acceleration.
                    float scale = (normTime-delta) * ACCEL_MOVE_SCALING_FACTOR;
                    if (scale > 1) acc *= scale;
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " accelerate: off="
                            + off + " normTime=" + normTime + " delta=" + delta
                            + " scale=" + scale + " acc=" + acc);
                    acceleration = acc < MAX_ACCELERATION ? acc : MAX_ACCELERATION;
                } else {
                    // The user is scrolling slowly, so decrease acceleration.
                    float scale = (delta-normTime) * ACCEL_MOVE_SCALING_FACTOR;
                    if (scale > 1) acc /= scale;
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " deccelerate: off="
                            + off + " normTime=" + normTime + " delta=" + delta
                            + " scale=" + scale + " acc=" + acc);
                    acceleration = acc > 1 ? acc : 1;
                }
            }
            position += off;
            return (absPosition = Math.abs(position));
        }

        /**
         * Generate the number of discrete movement events appropriate for
         * the currently collected trackball movement.
         *
         * @param precision The minimum movement required to generate the
         * first discrete movement.
         *
         * @return Returns the number of discrete movements, either positive
         * or negative, or 0 if there is not enough trackball movement yet
         * for a discrete movement.
         */
        int generate(float precision) {
            int movement = 0;
            nonAccelMovement = 0;
            do {
                final int dir = position >= 0 ? 1 : -1;
                switch (step) {
                    // If we are going to execute the first step, then we want
                    // to do this as soon as possible instead of waiting for
                    // a full movement, in order to make things look responsive.
                    case 0:
                        if (absPosition < precision) {
                            return movement;
                        }
                        movement += dir;
                        nonAccelMovement += dir;
                        step = 1;
                        break;
                    // If we have generated the first movement, then we need
                    // to wait for the second complete trackball motion before
                    // generating the second discrete movement.
                    case 1:
                        if (absPosition < 2) {
                            return movement;
                        }
                        movement += dir;
                        nonAccelMovement += dir;
                        position += dir > 0 ? -2 : 2;
                        absPosition = Math.abs(position);
                        step = 2;
                        break;
                    // After the first two, we generate discrete movements
                    // consistently with the trackball, applying an acceleration
                    // if the trackball is moving quickly.  This is a simple
                    // acceleration on top of what we already compute based
                    // on how quickly the wheel is being turned, to apply
                    // a longer increasing acceleration to continuous movement
                    // in one direction.
                    default:
                        if (absPosition < 1) {
                            return movement;
                        }
                        movement += dir;
                        position += dir >= 0 ? -1 : 1;
                        absPosition = Math.abs(position);
                        float acc = acceleration;
                        acc *= 1.1f;
                        acceleration = acc < MAX_ACCELERATION ? acc : acceleration;
                        break;
                }
            } while (true);
        }
    }

    public static final class CalledFromWrongThreadException extends AndroidRuntimeException {
        public CalledFromWrongThreadException(String msg) {
            super(msg);
        }
    }

    private SurfaceHolder mHolder = new SurfaceHolder() {
        // we only need a SurfaceHolder for opengl. it would be nice
        // to implement everything else though, especially the callback
        // support (opengl doesn't make use of it right now, but eventually
        // will).
        public Surface getSurface() {
            return mSurface;
        }

        public boolean isCreating() {
            return false;
        }

        public void addCallback(Callback callback) {
        }

        public void removeCallback(Callback callback) {
        }

        public void setFixedSize(int width, int height) {
        }

        public void setSizeFromLayout() {
        }

        public void setFormat(int format) {
        }

        public void setType(int type) {
        }

        public void setKeepScreenOn(boolean screenOn) {
        }

        public Canvas lockCanvas() {
            return null;
        }

        public Canvas lockCanvas(Rect dirty) {
            return null;
        }

        public void unlockCanvasAndPost(Canvas canvas) {
        }
        public Rect getSurfaceFrame() {
            return null;
        }
    };

    static RunQueue getRunQueue() {
        RunQueue rq = sRunQueues.get();
        if (rq != null) {
            return rq;
        }
        rq = new RunQueue();
        sRunQueues.set(rq);
        return rq;
    }

    /**
     * The run queue is used to enqueue pending work from Views when no Handler is
     * attached.  The work is executed during the next call to performTraversals on
     * the thread.
     * @hide
     */
    static final class RunQueue {
        private final ArrayList<HandlerAction> mActions = new ArrayList<HandlerAction>();

        void post(Runnable action) {
            postDelayed(action, 0);
        }

        void postDelayed(Runnable action, long delayMillis) {
            HandlerAction handlerAction = new HandlerAction();
            handlerAction.action = action;
            handlerAction.delay = delayMillis;

            synchronized (mActions) {
                mActions.add(handlerAction);
            }
        }

        void removeCallbacks(Runnable action) {
            final HandlerAction handlerAction = new HandlerAction();
            handlerAction.action = action;

            synchronized (mActions) {
                final ArrayList<HandlerAction> actions = mActions;

                while (actions.remove(handlerAction)) {
                    // Keep going
                }
            }
        }

        void executeActions(Handler handler) {
            synchronized (mActions) {
                final ArrayList<HandlerAction> actions = mActions;
                final int count = actions.size();

                for (int i = 0; i < count; i++) {
                    final HandlerAction handlerAction = actions.get(i);
                    handler.postDelayed(handlerAction.action, handlerAction.delay);
                }

                actions.clear();
            }
        }

        private static class HandlerAction {
            Runnable action;
            long delay;

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                HandlerAction that = (HandlerAction) o;
                return !(action != null ? !action.equals(that.action) : that.action != null);

            }

            @Override
            public int hashCode() {
                int result = action != null ? action.hashCode() : 0;
                result = 31 * result + (int) (delay ^ (delay >>> 32));
                return result;
            }
        }
    }

    /**
     * Class for managing the accessibility interaction connection
     * based on the global accessibility state.
     */
    final class AccessibilityInteractionConnectionManager
            implements AccessibilityStateChangeListener {
        public void onAccessibilityStateChanged(boolean enabled) {
            if (enabled) {
                ensureConnection();
                if (mAttachInfo != null && mAttachInfo.mHasWindowFocus) {
                    mView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                    View focusedView = mView.findFocus();
                    if (focusedView != null && focusedView != mView) {
                        focusedView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
                    }
                }
            } else {
                ensureNoConnection();
                mHandler.obtainMessage(MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST).sendToTarget();
            }
        }

        public void ensureConnection() {
            if (mAttachInfo != null) {
                final boolean registered =
                    mAttachInfo.mAccessibilityWindowId != AccessibilityNodeInfo.UNDEFINED;
                if (!registered) {
                    mAttachInfo.mAccessibilityWindowId =
                        mAccessibilityManager.addAccessibilityInteractionConnection(mWindow,
                                new AccessibilityInteractionConnection(ViewRootImpl.this));
                }
            }
        }

        public void ensureNoConnection() {
            final boolean registered =
                mAttachInfo.mAccessibilityWindowId != AccessibilityNodeInfo.UNDEFINED;
            if (registered) {
                mAttachInfo.mAccessibilityWindowId = AccessibilityNodeInfo.UNDEFINED;
                mAccessibilityManager.removeAccessibilityInteractionConnection(mWindow);
            }
        }
    }

    /**
     * This class is an interface this ViewAncestor provides to the
     * AccessibilityManagerService to the latter can interact with
     * the view hierarchy in this ViewAncestor.
     */
    static final class AccessibilityInteractionConnection
            extends IAccessibilityInteractionConnection.Stub {
        private final WeakReference<ViewRootImpl> mViewRootImpl;

        AccessibilityInteractionConnection(ViewRootImpl viewRootImpl) {
            mViewRootImpl = new WeakReference<ViewRootImpl>(viewRootImpl);
        }

        @Override
        public void findAccessibilityNodeInfoByAccessibilityId(long accessibilityNodeId,
                int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .findAccessibilityNodeInfoByAccessibilityIdClientThread(accessibilityNodeId,
                            interactionId, callback, flags, interrogatingPid, interrogatingTid);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfosResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void performAccessibilityAction(long accessibilityNodeId, int action,
                Bundle arguments, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                int interogatingPid, long interrogatingTid) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .performAccessibilityActionClientThread(accessibilityNodeId, action, arguments,
                            interactionId, callback, flags, interogatingPid, interrogatingTid);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setPerformAccessibilityActionResult(false, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void findAccessibilityNodeInfoByViewId(long accessibilityNodeId, int viewId,
                int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .findAccessibilityNodeInfoByViewIdClientThread(accessibilityNodeId, viewId,
                            interactionId, callback, flags, interrogatingPid, interrogatingTid);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfoResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void findAccessibilityNodeInfosByText(long accessibilityNodeId, String text,
                int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .findAccessibilityNodeInfosByTextClientThread(accessibilityNodeId, text,
                            interactionId, callback, flags, interrogatingPid, interrogatingTid);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfosResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void findFocus(long accessibilityNodeId, int focusType, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .findFocusClientThread(accessibilityNodeId, focusType, interactionId, callback,
                            flags, interrogatingPid, interrogatingTid);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfoResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void focusSearch(long accessibilityNodeId, int direction, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .focusSearchClientThread(accessibilityNodeId, direction, interactionId,
                            callback, flags, interrogatingPid, interrogatingTid);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfoResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }
    }

    private class SendWindowContentChangedAccessibilityEvent implements Runnable {
        public View mSource;

        public void run() {
            if (mSource != null) {
                mSource.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                mSource.resetAccessibilityStateChanged();
                mSource = null;
            }
        }
    }

    /**
     * M: API used for IMM to notify soft input changed .
     *
     *
     * @hide
     */
    public void requestLayoutBySoftInputChanged() {
        mSoftInputMayChanged = true;
    }

    /**
     * M: API used to switch on/off debug flags.
     * 
     * @param enable enable or disable log.
     * 
     * @hide
     */
    public void enableLog(boolean enable) {
        Xlog.v(TAG, "enableLog: enable = " + enable);
//        DBG = enable;
        LOCAL_LOGV = enable;
        DEBUG_DRAW = enable;
        DEBUG_LAYOUT = enable;
        DEBUG_DIALOG = enable;
        DEBUG_INPUT_RESIZE = enable;
        DEBUG_ORIENTATION = enable;
        DEBUG_TRACKBALL = enable;
        DEBUG_IMF = enable;
        DEBUG_CONFIGURATION = enable;
        DEBUG_FPS = enable;
        DEBUG_INPUT = enable;
        DEBUG_IME_ANR = enable;
        DEBUG_LIFECYCLE = enable;

        HardwareRenderer.setHWUIDebugLog(enable);
    }

    @Override
    public String toString() {
        String ret = "ViewRoot{" + Integer.toHexString(System.identityHashCode(this)) + " "
                + mWindowAttributes.getTitle() + ",ident = " + mIdent + "}";
        return ret;
    }
    
    /**
     * M: Get ViewRoot logs property and partial enable logs if debug flags is 
     * set, you must recreate the activity to put it into effect.
     * 
     * For example, you can use this function like
     * "adb shell setprop debug.viewroot.enable 1" to enable all logs, you can
     * also use "adb shell setprop debug.viewroot.enable 0" to disable all logs.
     */
    private static void checkViewRootImplLogProperty() {
        final String propString = SystemProperties.get(LOG_PROPERTY_NAME);
        if (propString != null && propString.length() > 0) {
            int logFilter = 0;
            try {
                logFilter = Integer.parseInt(propString, 16);
            } catch (NumberFormatException e) {
                Xlog.w(TAG, "Invalid format of propery string: " + propString);
            }

            Xlog.d(TAG, "checkViewRootImplLogProperty: propString = " + propString
                    + ",logFilter = #" + Integer.toHexString(logFilter));
            LOCAL_LOGV = (logFilter & DEBUG_ENABLE_ALL_FLAG) == DEBUG_ENABLE_ALL_FLAG;
            DEBUG_DRAW = (logFilter & DEBUG_DRAW_FLAG) == DEBUG_DRAW_FLAG;
            DEBUG_LAYOUT = (logFilter & DEBUG_LAYOUT_FLAG) == DEBUG_LAYOUT_FLAG;
            DEBUG_DIALOG = (logFilter & DEBUG_DIALOG_FLAG) == DEBUG_DIALOG_FLAG;
            DEBUG_INPUT_RESIZE = (logFilter & DEBUG_INPUT_RESIZE_FLAG) == DEBUG_INPUT_RESIZE_FLAG;
            DEBUG_ORIENTATION = (logFilter & DEBUG_ORIENTATION_FLAG) == DEBUG_ORIENTATION_FLAG;
            DEBUG_TRACKBALL = (logFilter & DEBUG_TRACKBALL_FLAG) == DEBUG_TRACKBALL_FLAG;
            DEBUG_IMF = (logFilter & DEBUG_IMF_FLAG) == DEBUG_IMF_FLAG;
            DEBUG_CONFIGURATION = (logFilter & DEBUG_CONFIGURATION_FLAG) == DEBUG_CONFIGURATION_FLAG;
            DBG = (logFilter & DEBUG_DEFAULT_FLAG) == DEBUG_DEFAULT_FLAG;
            DEBUG_FPS = (logFilter & DEBUG_FPS_FLAG) == DEBUG_FPS_FLAG;
            DEBUG_HWUI = (logFilter & DEBUG_HWUI_FLAG) == DEBUG_HWUI_FLAG;
            DEBUG_INPUT = (logFilter & DEBUG_INPUT_FLAG) == DEBUG_INPUT_FLAG;
            DEBUG_KEY = DEBUG_INPUT || (logFilter & DEBUG_KEY_FLAG) == DEBUG_KEY_FLAG;
            DEBUG_MOTION = DEBUG_INPUT || (logFilter & DEBUG_MOTION_FLAG) == DEBUG_MOTION_FLAG;
            DEBUG_IME_ANR = (logFilter & DEBUG_IME_ANR_FLAG) == DEBUG_IME_ANR_FLAG;
            DEBUG_LIFECYCLE = (logFilter & DEBUG_LIFECYCLE_FLAG) == DEBUG_LIFECYCLE_FLAG;
        }
    }

    /**
     * Dump the given view as a bitmap and store it to data area. The dump image
     * will be stored in folder /data/dump, if you want to use this function, you
     * must make sure there is a /data/dump folder, or else you need to create
     * it. The file name will be the current time such as "20120728_120000.png".
     * 
     * For example, you can use it like dumpViewAsBitmap(mView, "com.android.launcher").
     * 
     * @param view the view to be dumped.
     * @param dumpPkgName the package name of the view.
     */
    private static void dumpViewAsBitmap(final View view, final String dumpPkgName) {
        if (view == null || dumpPkgName == null) {
            return;
        }

        final String pkgName = view.getContext().getPackageName();
        if (pkgName.equals(dumpPkgName)) {
            final Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                    Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bitmap);

            // Draw the given view to bitmap.
            view.dispatchDraw(c);
            try {
                final String fileName = DUMP_IMAGE_PTAH
                        + DateFormat.format(DATE_FORMAT_STRING, System.currentTimeMillis()).toString() 
                        + DUMP_IMAGE_FORMAT;
                Xlog.d(TAG, "dumpViewInfo: fileName = " + fileName);
                File file = new File(fileName);
                Xlog.d(TAG, "dumpViewInfo: file = " + file);
                FileOutputStream fos = new FileOutputStream(file);
                if (fos != null) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
                    fos.flush();
                    fos.close();
                    bitmap.recycle();
                    Xlog.d(TAG, "Bitmap dump successfully.");
                } else {
                    Xlog.d(TAG, "Bitmap dump failed.");
                }
            } catch (IOException ioe) {
                Xlog.d(TAG, "Bitmap dump failed with exception " + ioe);
            }
        }
    }

    /**
     * Debug option, whether the view is in target package, used to reduce useless logs.
     * 
     * @return
     */
    private boolean isTargetPackage() {
        if (mView == null) {
            return false;
        }
        final String pkgName = mView.getContext().getPackageName();
        return ("com.android.launcher".equals(pkgName));
    }
        
    /**
     * Dump the current key and motion event processing state, for ANR analysis.
     * 
     * @{
     * @hide
     */
    public void dumpInputDispatchingStatus() {
        final SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        final Date date = new Date(mKeyEventStartTime);
        long dispatchTime = System.currentTimeMillis() - mKeyEventStartTime;
        if (mCurrentKeyEvent == null) {
            Xlog.i(TAG, "No key event currently.");
        } else {
            Xlog.i(TAG, "Dispatch " + mCurrentKeyEvent + " status is " + mKeyEventStatus + ",start at "
                    + formatter.format(date) + ", spent " + dispatchTime + "ms.");
        }
        
        if (mCurrentMotion == null) {
            Xlog.i(TAG, "No motion event currently.");
        } else {
            date.setTime(mMotionEventStartTime);
            dispatchTime = System.currentTimeMillis() - mMotionEventStartTime;
            Xlog.i(TAG, "Dispatch " + mCurrentMotion + " status is " + mMotionEventStatus
                    + ",start at " + formatter.format(date) + ", spent " + dispatchTime + "ms.");
        }
       
        Xlog.i(TAG, "The current processed event of VRI is "
                + (mCurrentInputEvent != null ? mCurrentInputEvent.mEvent : "none"));
        /// M: Nofity IMS to dump callstack @{
        InputMethodManager imm = InputMethodManager.peekInstance(); 
        if (imm != null) 
        {
            Xlog.i(TAG, "notify IMS Dump");
            imm.notifyImsDump(true);
        }
        /// Nofity IMS to dump callstack @} 
    }
    
    private static final String INPUT_DISPATCH_STATE_FINISHED = "0: Finish handle input event";
    private static final String INPUT_DISPATCH_STATE_STARTED = "1: Start event from input";
    private static final String INPUT_DISPATCH_STATE_ENQUEUE_EVENT = "2: Enqueue input event";
    private static final String INPUT_DISPATCH_STATE_PROCESS_EVENT = "3 1: Process input event";
    private static final String INPUT_DISPATCH_STATE_SCHEDULE_EVENT = "3 2: Schedule process input event";
    private static final String INPUT_DISPATCH_STATE_DELIVER_EVENT = "4: Deliver input event";
    
    private static final String KEY_DISPATCH_STATE_BEGIN_DELIEVER = "5: Begin deliver key event";
    private static final String KEY_DISPATCH_STATE_DELIEVER_TO_IMM = "6: Deliver key to imm";
    private static final String KEY_DISPATCH_STATE_DELIEVER_TO_VIEW = "7: Deliver key to view hierarchy";
    private static final String KEY_DISPATCH_STATE_FINISHED = "8: Finish handle key event";
    
    private static final String MOTION_DISPATCH_STATE_DELIEVER_POINTER = "5 0: Deliver pointer event";
    private static final String MOTION_DISPATCH_STATE_DELIEVER_TRACKBALL = "5 1: Deliver pointer event";
    private static final String MOTION_DISPATCH_STATE_DELIEVER_GENERIC = "5 2: Deliver pointer event";   
    private static final String MOTION_DISPATCH_STATE_FINISHED = "6: Finish handle motion event";
    
    private String mKeyEventStatus = INPUT_DISPATCH_STATE_FINISHED;
    private String mMotionEventStatus = INPUT_DISPATCH_STATE_FINISHED;

    private KeyEvent mCurrentKeyEvent;
    private MotionEvent mCurrentMotion;
    
    private long mKeyEventStartTime;
    private long mMotionEventStartTime;
    
    /** @} */
}
