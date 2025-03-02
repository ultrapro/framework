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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.Configuration;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.text.method.TextKeyListener;
import android.util.AttributeSet;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.TextView.OnEditorActionListener;

import com.android.internal.R;
import com.android.internal.widget.PasswordEntryKeyboardHelper;
import com.android.internal.widget.PasswordEntryKeyboardView;

import java.util.List;
///LEWA BEGIN
import android.view.inputmethod.InputMethodManager;
import android.provider.Settings;
///LEWA END

/**
 * Displays an alphanumeric (latin-1) key entry for the user to enter
 * an unlock password
 */

public class KeyguardPasswordView extends KeyguardAbsKeyInputView
        implements KeyguardSecurityView, OnEditorActionListener, TextWatcher {
    
    private static final String TAG = "KeyguardPasswordView";

    private final boolean mShowImeAtScreenOn;

    InputMethodManager mImm;
///LEWA BEGIN
    private final String DEFAULT_IME = "com.android.inputmethod.latin/.LatinIME";
    private String mIme = null;
///LEWA END 

    public KeyguardPasswordView(Context context) {
        this(context, null);
    }

    public KeyguardPasswordView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mShowImeAtScreenOn = context.getResources().
                getBoolean(R.bool.kg_show_ime_at_screen_on);
    }

    protected void resetState() {
        mSecurityMessageDisplay.setMessage(R.string.kg_password_instructions, false);
        mPasswordEntry.setEnabled(true);
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.passwordEntry;
    }

    @Override
    public boolean needsInput() {
        return true;
    }

    @Override
    public void onResume(int reason) {
        super.onResume(reason);
        mPasswordEntry.requestFocus();
        /// M: Check if mPasswordEntry is visible on screen, if not, then do not show IME @{
        mPasswordEntry.getLocationOnScreen(mTempLocation);
        final boolean passwordEntryVisible =
                (mTempLocation[1] >= 0) &&
                (mTempLocation[1] <= this.mContext.getResources().getDisplayMetrics().heightPixels);
        KeyguardUtils.xlogD(TAG, "reason=" + reason +
                ", mShowImeAtScreenOn=" + mShowImeAtScreenOn +
                ", passwordEntryVisible=" + passwordEntryVisible);
        /// @}
        if (reason != KeyguardSecurityView.SCREEN_ON || (mShowImeAtScreenOn && passwordEntryVisible)) {
            mImm.showSoftInput(mPasswordEntry, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mImm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        boolean imeOrDeleteButtonVisible = false;

        mImm = (InputMethodManager) getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);

        mPasswordEntry.setKeyListener(TextKeyListener.getInstance());
        mPasswordEntry.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // Poke the wakelock any time the text is selected or modified
        mPasswordEntry.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mCallback.userActivity(0); // TODO: customize timeout for text?
            }
        });

        mPasswordEntry.addTextChangedListener(new TextWatcher() {
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void afterTextChanged(Editable s) {
                if (mCallback != null) {
                    mCallback.userActivity(0);
                }
            }
        });

        mPasswordEntry.requestFocus();

        // If there's more than one IME, enable the IME switcher button
///LEWA BEGIN
        /*View switchImeButton = findViewById(R.id.switch_ime_button);
        if (switchImeButton != null && hasMultipleEnabledIMEsOrSubtypes(mImm, false)) {
            switchImeButton.setVisibility(View.VISIBLE);
            imeOrDeleteButtonVisible = true;
            switchImeButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    mCallback.userActivity(0); // Leave the screen on a bit longer
                    mImm.showInputMethodPicker();
                }
            });
        }*/
        final List<InputMethodInfo> enabledImis = mImm.getEnabledInputMethodList();
            for (InputMethodInfo info : enabledImis) {
                if (info.getId().equals(DEFAULT_IME)) {
                    mIme = Settings.Secure.getString(mContext.getContentResolver(),
                        Settings.Secure.DEFAULT_INPUT_METHOD);
                    Settings.Secure.putString(mContext.getContentResolver(),
                        Settings.Secure.DEFAULT_INPUT_METHOD,DEFAULT_IME);
                    break;
                }
            }
///LEWA END

        // If no icon is visible, reset the start margin on the password field so the text is
        // still centered.
        if (!imeOrDeleteButtonVisible) {
            android.view.ViewGroup.LayoutParams params = mPasswordEntry.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                final MarginLayoutParams mlp = (MarginLayoutParams) params;
                mlp.setMarginStart(0);
                mPasswordEntry.setLayoutParams(params);
            }
        }
    }

    /**
     * Method adapted from com.android.inputmethod.latin.Utils
     *
     * @param imm The input method manager
     * @param shouldIncludeAuxiliarySubtypes
     * @return true if we have multiple IMEs to choose from
     */
    private boolean hasMultipleEnabledIMEsOrSubtypes(InputMethodManager imm,
            final boolean shouldIncludeAuxiliarySubtypes) {
        final List<InputMethodInfo> enabledImis = imm.getEnabledInputMethodList();

        // Number of the filtered IMEs
        int filteredImisCount = 0;

        for (InputMethodInfo imi : enabledImis) {
            // We can return true immediately after we find two or more filtered IMEs.
            if (filteredImisCount > 1) return true;
            final List<InputMethodSubtype> subtypes =
                    imm.getEnabledInputMethodSubtypeList(imi, true);
            // IMEs that have no subtypes should be counted.
            if (subtypes.isEmpty()) {
                ++filteredImisCount;
                continue;
            }

            int auxCount = 0;
            for (InputMethodSubtype subtype : subtypes) {
                if (subtype.isAuxiliary()) {
                    ++auxCount;
                }
            }
            final int nonAuxCount = subtypes.size() - auxCount;

            // IMEs that have one or more non-auxiliary subtypes should be counted.
            // If shouldIncludeAuxiliarySubtypes is true, IMEs that have two or more auxiliary
            // subtypes should be counted as well.
            if (nonAuxCount > 0 || (shouldIncludeAuxiliarySubtypes && auxCount > 1)) {
                ++filteredImisCount;
                continue;
            }
        }

        return filteredImisCount > 1
        // imm.getEnabledInputMethodSubtypeList(null, false) will return the current IME's enabled
        // input method subtype (The current IME should be LatinIME.)
                || imm.getEnabledInputMethodSubtypeList(null, false).size() > 1;
    }

    @Override
    public void showUsabilityHint() {
    }

    @Override
    public int getWrongPasswordStringId() {
        return R.string.kg_wrong_password;
    }

///LEWA BEGIN
    protected void verifyPasswordAndUnlock() {
        String entry = mPasswordEntry.getText().toString();
        if (mLockPatternUtils.checkPassword(entry)) {
            if(mIme != null){
                Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,mIme);
            }
        }
        super.verifyPasswordAndUnlock();
    }
///LEWA END 
    /// M: Temp rect used to compute Password entry's location on screen
    private int[] mTempLocation = new int[2];
}
