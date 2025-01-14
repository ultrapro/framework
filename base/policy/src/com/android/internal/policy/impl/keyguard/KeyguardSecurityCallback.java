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

import com.android.internal.policy.impl.keyguard.KeyguardHostView.OnDismissAction;

public interface KeyguardSecurityCallback {

    /**
     * Dismiss the given security screen.
     * @param securityVerified true if the user correctly entered credentials for the given screen.
     */
    void dismiss(boolean securityVerified);

    /**
     * Manually report user activity to keep the device awake. If timeout is 0,
     * uses user-defined timeout.
     * @param timeout
     */
    void userActivity(long timeout);

    /**
     * Checks if keyguard is in "verify credentials" mode.
     * @return true if user has been asked to verify security.
     */
    boolean isVerifyUnlockOnly();

    /**
     * Call when user correctly enters their credentials
     */
    void reportSuccessfulUnlockAttempt();

    /**
     * Call when the user incorrectly enters their credentials
     */
    void reportFailedUnlockAttempt();

    /**
     * Gets the number of attempts thus far as reported by {@link #reportFailedUnlockAttempt()}
     * @return number of failed attempts
     */
    int getFailedAttempts();

    /**
     * Shows the backup security for the current method.  If none available, this call is a no-op.
     */
    void showBackupSecurity();

    /**
     * Sets an action to perform after the user successfully enters their credentials.
     * @param action
     */
    void setOnDismissAction(OnDismissAction action);
    
    /**
     * M: Mediatek add for voice unlock.
     * @return whether has DismissAction
     */
    boolean hasOnDismissAction();
    
    /**
     * M: Mediatek add to update KeyguardLayer visibility.
     * @param action
     */
    void updateKeyguardLayerVisibility(boolean visible);
    
    /**
     * M: KeyguardSelectorView contains MediatekGlowPadView, who enables user to drag NewEventView
     * around the whole screen, so we need to give it a chance to avoid clipped by KeyguardSelectorView
     * and KeyguardSelectorView's parent and grand parent.
     * 
     * Limitation: If clipChildren is disabled, view scroll or resize may abnormal when interacting with IME,
     * so we must only disable clipChildren when KeyguardSelectorView is show, and should always enable clipChildren
     * in other KeyguardSecurityView
     *
     * @param clipChildren Wheather clip Children or not
     */
    void updateClipChildren(boolean clipChildren);

    ///LEWA BEGIN
    void setPendingIntent(android.content.Intent intent);
    ///LEWA END
}
