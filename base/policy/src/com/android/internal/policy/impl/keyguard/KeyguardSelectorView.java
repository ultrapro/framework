/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.internal.policy.impl.keyguard;

import android.animation.ObjectAnimator;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.R;

import com.mediatek.common.MediatekClassFactory;
import com.mediatek.common.hdmi.IHDMINative;

public class KeyguardSelectorView extends LockScreenLayout implements KeyguardSecurityView {
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final String TAG = "SecuritySelectorView";
    private static final String ASSIST_ICON_METADATA_NAME =
        "com.android.systemui.action_assist_icon";

    private KeyguardSecurityCallback mCallback;
    private MediatekGlowPadView mGlowPadView;
    private ObjectAnimator mAnim;
    private View mFadeView;
    private boolean mIsBouncing;
    private boolean mCameraDisabled;
    private boolean mSearchDisabled;
    private LockPatternUtils mLockPatternUtils;
    private SecurityMessageDisplay mSecurityMessageDisplay;
    private Drawable mBouncerFrame;

    MediatekGlowPadView.OnTriggerListener mOnTriggerListener = new MediatekGlowPadView.OnTriggerListener() {

        public void onTrigger(View v, int target) {
            final int resId = mGlowPadView.getResourceIdForTarget(target);
            switch (resId) {
                case com.android.internal.R.drawable.ic_action_assist_generic:
                    Intent assistIntent =
                            ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                            .getAssistIntent(mContext, UserHandle.USER_CURRENT);
                    if (assistIntent != null) {
                        mActivityLauncher.launchActivity(assistIntent, false, true, null, null);
                    } else {
                        Log.w(TAG, "Failed to get intent for assist activity");
                    }
                    mCallback.userActivity(0);
                    break;

                case com.android.internal.R.drawable.ic_lockscreen_camera:
                    mActivityLauncher.launchCamera(null, null);
                    mCallback.userActivity(0);
                    break;

                case com.android.internal.R.drawable.ic_lockscreen_unlock_phantom:
                case com.android.internal.R.drawable.ic_lockscreen_unlock:
                    mCallback.userActivity(0);
                    mCallback.dismiss(false);
                    
                /// M: Add a special case for incoming indicator feature, when indicator view launches activity, go to unlockscreen
                /// only if necessary, or you may see homescreen before activity is launched
                case -1:
                    mCallback.userActivity(0);
                    if (isSecure()) {
                        mCallback.dismiss(false);
                    }
                break;
            }
        }

        public void onReleased(View v, int handle) {
            if (!mIsBouncing) {
                doTransition(mFadeView, 1.0f);
            }
        }

        public void onGrabbed(View v, int handle) {
            mCallback.userActivity(0);
            doTransition(mFadeView, 0.0f);
        }

        public void onGrabbedStateChange(View v, int handle) {

        }

        public void onFinishFinalAnimation() {

        }

    };

    KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onDevicePolicyManagerStateChanged() {
            updateTargets();
        }

        @Override
        public void onSimStateChanged(State simState) {
            updateTargets();
        }
    };

    private final KeyguardActivityLauncher mActivityLauncher = new KeyguardActivityLauncher() {

        @Override
        KeyguardSecurityCallback getCallback() {
            return mCallback;
        }

        @Override
        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
        }

        @Override
        Context getContext() {
            return mContext;
        }};

    public KeyguardSelectorView(Context context) {
        this(context, null);
    }

    public KeyguardSelectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(getContext());
        
        ///M: Initilise the hdmi interface @{
        try {
            if (mHDMI == null) {
                mHDMI = MediatekClassFactory.createInstance(IHDMINative.class);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        ///@}
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mGlowPadView = (MediatekGlowPadView) findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(mOnTriggerListener);
        updateTargets();

        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
        View bouncerFrameView = findViewById(R.id.keyguard_selector_view_frame);
        mBouncerFrame = bouncerFrameView.getBackground();
        
        /// M: If dm lock is on, we should also hide widget @{
        boolean dmLocked = KeyguardUpdateMonitor.getInstance(getContext()).dmIsLocked();
        mGlowPadView.setVisibility(dmLocked ? View.INVISIBLE : View.VISIBLE);
        /// @}
        
        /// M: Init medaitke newevent feature related views
        if (Settings.System.getInt(getContext().getContentResolver(), INCOMING_INDICATOR_ON_LOCKSCREEN, 1) == 1) {
            Log.d(TAG, "constructor infalte newevent feature related views");
            mGlowPadView.setLockScreenView(this);
            ViewGroup unLockPanel = (ViewGroup)findViewById(R.id.keyguard_unlock_panel);
            final LayoutInflater inflater = LayoutInflater.from(mContext);
            inflater.inflate(com.mediatek.internal.R.layout.keyguard_unread_event_view, unLockPanel, true);
            UnReadEventView unReadEventView = (UnReadEventView)findViewById(com.mediatek.internal.R.id.unread_event_view);
            /// M: Incoming Indicator for Keyguard Rotation @{
            unReadEventView.setVisibility(dmLocked ? View.INVISIBLE : View.VISIBLE);
            unReadEventView.updateQueryBaseTimeAndRefreshUnReadNumber(KeyguardUpdateMonitor.getInstance(mContext).getQueryBaseTime());
            /// @}
            setUnReadEventView(unReadEventView);
            mGlowPadView.syncUnReadEventView(unReadEventView);
        }
    }

    public void setCarrierArea(View carrierArea) {
        mFadeView = carrierArea;
    }

    public boolean isTargetPresent(int resId) {
        return mGlowPadView.getTargetPosition(resId) != -1;
    }

    @Override
    public void showUsabilityHint() {
        mGlowPadView.ping();
    }

    private void updateTargets() {
        int currentUserHandle = mLockPatternUtils.getCurrentUser();
        DevicePolicyManager dpm = mLockPatternUtils.getDevicePolicyManager();
        int disabledFeatures = dpm.getKeyguardDisabledFeatures(null, currentUserHandle);
        boolean secureCameraDisabled = mLockPatternUtils.isSecure()
                && (disabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0;
        boolean cameraDisabledByAdmin = dpm.getCameraDisabled(null, currentUserHandle)
                || secureCameraDisabled;
        final KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(getContext());
        boolean disabledBySimState = monitor.isSimLocked();
        boolean cameraTargetPresent =
            isTargetPresent(com.android.internal.R.drawable.ic_lockscreen_camera);
        boolean searchTargetPresent =
            isTargetPresent(com.android.internal.R.drawable.ic_action_assist_generic);

        if (cameraDisabledByAdmin) {
            Log.v(TAG, "Camera disabled by Device Policy");
        } else if (disabledBySimState) {
            Log.v(TAG, "Camera disabled by Sim State");
        }
        boolean currentUserSetup = 0 != Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE,
                0 /*default */,
                currentUserHandle);
        boolean searchActionAvailable =
                ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, UserHandle.USER_CURRENT) != null;
        mCameraDisabled = cameraDisabledByAdmin || disabledBySimState || !cameraTargetPresent
                || !currentUserSetup;
        mSearchDisabled = disabledBySimState || !searchActionAvailable || !searchTargetPresent
                || !currentUserSetup;
        updateResources();
    }

    public void updateResources() {
        // Update the search icon with drawable from the search .apk
        if (!mSearchDisabled) {
            Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                    .getAssistIntent(mContext, UserHandle.USER_CURRENT);
            if (intent != null) {
                // XXX Hack. We need to substitute the icon here but haven't formalized
                // the public API. The "_google" metadata will be going away, so
                // DON'T USE IT!
                ComponentName component = intent.getComponent();
                boolean replaced = mGlowPadView.replaceTargetDrawablesIfPresent(component,
                        ASSIST_ICON_METADATA_NAME + "_google",
                        com.android.internal.R.drawable.ic_action_assist_generic);

                if (!replaced && !mGlowPadView.replaceTargetDrawablesIfPresent(component,
                            ASSIST_ICON_METADATA_NAME,
                            com.android.internal.R.drawable.ic_action_assist_generic)) {
                        Slog.w(TAG, "Couldn't grab icon from package " + component);
                }
            }
        }

        mGlowPadView.setEnableTarget(com.android.internal.R.drawable
                .ic_lockscreen_camera, !mCameraDisabled);
        mGlowPadView.setEnableTarget(com.android.internal.R.drawable
                .ic_action_assist_generic, !mSearchDisabled);
    }

    void doTransition(View view, float to) {
        if (mAnim != null) {
            mAnim.cancel();
        }
        mAnim = ObjectAnimator.ofFloat(view, "alpha", to);
        mAnim.start();
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
        /// M: update visibility immediately after set callback
        mCallback.updateKeyguardLayerVisibility(true);
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    public void reset() {
        mGlowPadView.reset(false);
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
        KeyguardUpdateMonitor.getInstance(getContext()).removeCallback(mInfoCallback);
        KeyguardUtils.xlogD(TAG, "onPause this=" + this + ", mInfoCallback=" + mInfoCallback);
        ///M: add hdmi function @{
        if (mHDMI != null) {
            mHDMI.hdmiPortraitEnable(false); 
        }
        KeyguardUtils.xlogD(TAG, "onPause this=" + this + ", hdmiPortraitEnable disable done");
        ///@}
        
        /// Disable clipChildren when paused, for NewEventView no longer visible
        if (mCallback != null) {
            mCallback.updateClipChildren(true);
        }
    }

    @Override
    public void onResume(int reason) {
        KeyguardUpdateMonitor.getInstance(getContext()).registerCallback(mInfoCallback);
        KeyguardUtils.xlogD(TAG, "onResume this=" + this + ", mInfoCallback=" + mInfoCallback);
        ///M: add hdmi function @{
        if (mHDMI != null) {
            mHDMI.hdmiPortraitEnable(true); 
        }
        KeyguardUtils.xlogD(TAG, "onResume this=" + this + ", hdmiPortraitEnable enable done");
        ///@}
        
        /// M: Disable clicpChildren for NewEventView drag
        if (mCallback != null && !KeyguardUpdateMonitor.getInstance(getContext()).dmIsLocked()) {
            mCallback.updateClipChildren(false);
        }
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showBouncer(int duration) {
        mIsBouncing = true;
        KeyguardSecurityViewHelper.
                showBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }

    @Override
    public void hideBouncer(int duration) {
        mIsBouncing = false;
        KeyguardSecurityViewHelper.
                hideBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }
    
    /// M: Release trigger listener hold by GlowPadView to avoid memory leak
    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mGlowPadView.setOnTriggerListener(null);
    }
    
    public MediatekGlowPadView getGlowPadView() {
        return mGlowPadView;
    }
    
    public void onVisibilityChanged(View changedView, int visibility) {
        /// M: check null pointer before update keyguard layer
        if(mCallback != null) {
            mCallback.updateKeyguardLayerVisibility(visibility == View.VISIBLE ? true : false);
        }
    }
    
    /// M: Add for lockscreen newevent feature @{
    private static final String INCOMING_INDICATOR_ON_LOCKSCREEN = "incoming_indicator_on_lockscreen";

    private IHDMINative mHDMI = null;
    
    /// M: Check if Keyguard is secure
    public boolean isSecure() {
        return mLockPatternUtils.isSecure()
            || KeyguardUpdateMonitor.getInstance(mContext).isSimPinSecure();
    }
}
