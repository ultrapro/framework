/*
 * Copyright (C) 2006, 2012 The Android Open Source Project
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

package com.android.internal.telephony;

import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccConstants;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.UiccCardApplication;

/**
 * {@hide}
 * This class should be used to access files in USIM ADF
 */
public final class UsimFileHandler extends IccFileHandler implements IccConstants {
    static final String LOG_TAG = "RIL_UsimFH";

    public UsimFileHandler(UiccCardApplication app, String aid, CommandsInterface ci) {
        super(app, aid, ci);
    }

    @Override
    protected String getEFPath(int efid) {
        return getEFPath(efid, false);
    }
    protected String getEFPath(int efid, boolean is7FFF) {
        // TODO(): DF_GSM can be 7F20 or 7F21 to handle backward compatibility.
        // Implement this after discussion with OEMs.
        String DF_APP = DF_GSM;

        if ((mParentApp != null) && (mParentApp.getType() == IccCardApplicationStatus.AppType.APPTYPE_USIM)) {
            DF_APP = DF_USIM;
        }
        switch(efid) {
        case EF_SMS:
        case EF_EXT6:
        case EF_MWIS:
        case EF_MBI:
        case EF_SPN:
        case EF_AD:
        case EF_MBDN:
        case EF_PNN:
        case EF_OPL:
        case EF_SPDI:
        case EF_SST:
        case EF_CFIS:
        case EF_MAILBOX_CPHS:
        case EF_VOICE_MAIL_INDICATOR_CPHS:
        case EF_CFF_CPHS:
        case EF_SPN_CPHS:
        case EF_SPN_SHORT_CPHS:
        case EF_FDN:
        case EF_MSISDN:
        case EF_EXT2:
        case EF_INFO_CPHS:
        case EF_CSP_CPHS:
            return /*MF_SIM +*/ DF_ADF;

        case EF_PBR:
            // we only support global phonebook.
            return /*MF_SIM +*/ DF_TELECOM + DF_PHONEBOOK;
        }
        String path = getCommonIccEFPath(efid);
        if (path == null) {
            // The EFids in USIM phone book entries are decided by the card manufacturer.
            // So if we don't match any of the cases above and if its a USIM return
            // the phone book path.
            return /*MF_SIM +*/ DF_TELECOM + DF_PHONEBOOK;
        }
        return path;
    }

    @Override
    protected void logd(String msg) {
        Log.d(LOG_TAG, msg);
    }

    @Override
    protected void loge(String msg) {
        Log.e(LOG_TAG, msg);
    }
}
