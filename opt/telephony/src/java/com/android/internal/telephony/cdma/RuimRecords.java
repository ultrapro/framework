/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 *
 * MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 */

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

package com.android.internal.telephony.cdma;
import static android.Manifest.permission.READ_PHONE_STATE;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY_2;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC_2; 
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_DEFAULT_NAME;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_DEFAULT_NAME_2;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_TEST_CSIM;
import java.util.ArrayList;
import java.util.Locale;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.AdnRecord;
import com.android.internal.telephony.AdnRecordCache;
import com.android.internal.telephony.AdnRecordLoader;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.IccRefreshResponse;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.UiccCardApplication;

// can't be used since VoiceMailConstants is not public
//import com.android.internal.telephony.gsm.VoiceMailConstants;
import com.android.internal.telephony.IccException;
import com.android.internal.telephony.IccRecords;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.IccRecords.IccRecordLoaded;
import com.android.internal.telephony.cdma.sms.UserData;


import com.mediatek.common.featureoption.FeatureOption;
import android.app.ActivityManagerNative;
import com.android.internal.telephony.RIL;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Telephony.SIMInfo;
import android.provider.Telephony.SimInfo;
import android.content.ContentUris;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;

//via support start
import android.text.TextUtils;
import android.os.SystemProperties;
//via support end
/**
 * {@hide}
 */
public final class RuimRecords extends IccRecords {
    static final String LOG_TAG = "CDMA";

    private static final boolean DBG = true;
    private boolean  m_ota_commited=false;

    // ***** Instance Variables

    private String mMyMobileNumber;
    private String mMin2Min1;

    private String mPrlVersion;
    // From CSIM application
    private byte[] mEFpl = null;
    private byte[] mEFli = null;
    boolean mCsimSpnDisplayCondition = false;
    private String mMdn;
    private String mMin;
    private String mHomeSystemId;
    private String mHomeNetworkId;

    boolean bEccRequired = false;
    // ***** Event Constants

    private static final int EVENT_APP_READY = 1;
    private static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 2;
    private static final int EVENT_GET_IMSI_DONE = 3;
    private static final int EVENT_GET_DEVICE_IDENTITY_DONE = 4;
    private static final int EVENT_GET_ICCID_DONE = 5;
    private static final int EVENT_GET_CDMA_SUBSCRIPTION_DONE = 10;
    private static final int EVENT_UPDATE_DONE = 14;
    private static final int EVENT_GET_SST_DONE = 17;
    private static final int EVENT_GET_ALL_SMS_DONE = 18;
    private static final int EVENT_MARK_SMS_READ_DONE = 19;

    private static final int EVENT_SMS_ON_RUIM = 21;
    private static final int EVENT_GET_SMS_DONE = 22;

    private static final int EVENT_RUIM_REFRESH = 31;

    //via support start
    private static final int EVENT_RADIO_STATE_CHANGED = 101;
    //via support end

    // [mtk02772] start
    private static final int EVENT_PHB_READY = 102;

    static final String PROPERTY_RIL_PHB_READY = "gsm.sim.ril.phbready";
    static final String PROPERTY_RIL_PHB_READY_2 = "gsm.sim.ril.phbready.2";  
    private boolean mPhbReady = false;
    private boolean mSIMInfoReady = false;
    // [mtk02772] end

    private int mSimId;

    public RuimRecords(UiccCardApplication app, Context c, CommandsInterface ci) {
        super(app, c, ci);

        mSimId = app.getMySimId();

        adnCache = new AdnRecordCache(mFh);

        recordsRequested = false;  // No load request is made till SIM ready

        // recordsToLoad is set to 0 because no requests are made yet
        recordsToLoad = 0;

        mCi.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        // NOTE the EVENT_SMS_ON_RUIM is not registered
        mCi.registerForIccRefresh(this, EVENT_RUIM_REFRESH, null);

        // [mtk02772] start
        mCi.registerForPhbReady(this, EVENT_PHB_READY, null);
        
        IntentFilter phbFilter = new IntentFilter();
        phbFilter.addAction(TelephonyIntents.ACTION_SIM_INFO_UPDATE);
        phbFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        phbFilter.addAction("android.intent.action.ACTION_SHUTDOWN_IPO");
        mContext.registerReceiver(mHandlePhbReadyReceiver, phbFilter);
        // [mtk02772] end

        // Start off by setting empty state
        resetRecords();

        mParentApp.registerForReady(this, EVENT_APP_READY, null);
    }

    @Override
    public void dispose() {
        if (DBG) log("Disposing RuimRecords " + this);
        //Unregister for all events
        mCi.unregisterForOffOrNotAvailable( this);
        mCi.unregisterForIccRefresh(this);
        mContext.unregisterReceiver(mHandlePhbReadyReceiver);
        mParentApp.unregisterForReady(this);
        resetRecords();
        super.dispose();
    }

    @Override
    protected void finalize() {
        if(DBG) log("RuimRecords finalized");
    }

    protected void resetRecords() {
        countVoiceMessages = 0;
        mncLength = UNINITIALIZED;
        iccid = null;

        adnCache.reset();

        // Don't clean up PROPERTY_ICC_OPERATOR_ISO_COUNTRY and
        // PROPERTY_ICC_OPERATOR_NUMERIC here. Since not all CDMA
        // devices have RUIM, these properties should keep the original
        // values, e.g. build time settings, when there is no RUIM but
        // set new values when RUIM is available and loaded.

        // recordsRequested is set to false indicating that the SIM
        // read requests made so far are not valid. This is set to
        // true only when fresh set of read requests are made.
        recordsRequested = false;
    }

    @Override
    public String getIMSI() {
        return mImsi;
    }

    public String getMdnNumber() {
        return mMyMobileNumber;
    }

    public String getCdmaMin() {
         return mMin2Min1;
    }

    /** Returns null if RUIM is not yet ready */
    public String getPrlVersion() {
        return mPrlVersion;
    }

    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceNumber, Message onComplete){
        // In CDMA this is Operator/OEM dependent
        AsyncResult.forMessage((onComplete)).exception =
                new IccException("setVoiceMailNumber not implemented");
        onComplete.sendToTarget();
        loge("method setVoiceMailNumber is not implemented");
    }

    /**
     * Called by CCAT Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    @Override
    public void onRefresh(boolean fileChanged, int[] fileList) {
        if (fileChanged) {
            // A future optimization would be to inspect fileList and
            // only reload those files that we care about.  For now,
            // just re-fetch all RUIM records that we cache.
            fetchRuimRecords();
        }
    }

    private int adjstMinDigits (int digits) {
        // Per C.S0005 section 2.3.1.
        digits += 111;
        digits = (digits % 10 == 0)?(digits - 10):digits;
        digits = ((digits / 10) % 10 == 0)?(digits - 100):digits;
        digits = ((digits / 100) % 10 == 0)?(digits - 1000):digits;
        return digits;
    }

    /**
     * Returns the 5 or 6 digit MCC/MNC of the operator that
     *  provided the RUIM card. Returns null of RUIM is not yet ready
     */
    public String getRUIMOperatorNumeric() {
        if (mImsi == null) {
            return null;
        }

        if (mncLength != UNINITIALIZED && mncLength != UNKNOWN) {
            // Length = length of MCC + length of MNC
            // length of mcc = 3 (3GPP2 C.S0005 - Section 2.3)
            return mImsi.substring(0, 3 + mncLength);
        }

        // Guess the MNC length based on the MCC if we don't
        // have a valid value in ef[ad]

        int mcc = Integer.parseInt(mImsi.substring(0,3));
        return mImsi.substring(0, 3 + MccTable.smallestDigitsMccForMnc(mcc));
    }

    // Refer to ETSI TS 102.221
    private class EfPlLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_PL";
        }

        public void onRecordLoaded(AsyncResult ar) {
            mEFpl = (byte[]) ar.result;
            if (DBG) log("EF_PL=" + IccUtils.bytesToHexString(mEFpl));
        }
    }

    // Refer to C.S0065 5.2.26
    private class EfCsimLiLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_LI";
        }

        public void onRecordLoaded(AsyncResult ar) {
            mEFli = (byte[]) ar.result;
            // convert csim efli data to iso 639 format
            for (int i = 0; i < mEFli.length; i+=2) {
                switch(mEFli[i+1]) {
                case 0x01: mEFli[i] = 'e'; mEFli[i+1] = 'n';break;
                case 0x02: mEFli[i] = 'f'; mEFli[i+1] = 'r';break;
                case 0x03: mEFli[i] = 'e'; mEFli[i+1] = 's';break;
                case 0x04: mEFli[i] = 'j'; mEFli[i+1] = 'a';break;
                case 0x05: mEFli[i] = 'k'; mEFli[i+1] = 'o';break;
                case 0x06: mEFli[i] = 'z'; mEFli[i+1] = 'h';break;
                case 0x07: mEFli[i] = 'h'; mEFli[i+1] = 'e';break;
                default: mEFli[i] = ' '; mEFli[i+1] = ' ';
                }
            }

            if (DBG) log("EF_LI=" + IccUtils.bytesToHexString(mEFli));
        }
    }

    // Refer to C.S0065 5.2.32
    private class EfCsimSpnLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_SPN";
        }

        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            if (DBG) log("CSIM_SPN=" +
                         IccUtils.bytesToHexString(data));

            // C.S0065 for EF_SPN decoding
            mCsimSpnDisplayCondition = ((0x01 & data[0]) != 0);

            int encoding = data[1];
            int language = data[2];
            byte[] spnData = new byte[32];
            int len = ((data.length - 3) < 32) ? (data.length - 3) : 32;
            System.arraycopy(data, 3, spnData, 0, len);

            int numBytes;
            for (numBytes = 0; numBytes < spnData.length; numBytes++) {
                if ((spnData[numBytes] & 0xFF) == 0xFF) break;
            }

            if (numBytes == 0) {
                spn = "";
                return;
            }
            try {
                switch (encoding) {
                case UserData.ENCODING_OCTET:
                case UserData.ENCODING_LATIN:
                    spn = new String(spnData, 0, numBytes, "ISO-8859-1");
                    break;
                case UserData.ENCODING_IA5:
                case UserData.ENCODING_GSM_7BIT_ALPHABET:
                case UserData.ENCODING_7BIT_ASCII:
                    spn = GsmAlphabet.gsm7BitPackedToString(spnData, 0, (numBytes*8)/7);
                    break;
                case UserData.ENCODING_UNICODE_16:
                    spn =  new String(spnData, 0, numBytes, "utf-16");
                    break;
                default:
                    log("SPN encoding not supported");
                }
            } catch(Exception e) {
                log("spn decode error: " + e);
            }
            if (DBG) log("spn=" + spn);
            if (DBG) log("spnCondition=" + mCsimSpnDisplayCondition);
            SystemProperties.set(PROPERTY_ICC_OPERATOR_ALPHA, spn);
        }
    }

    private class EfCsimMdnLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_MDN";
        }

        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            if (DBG) log("CSIM_MDN=" + IccUtils.bytesToHexString(data));
            // Refer to C.S0065 5.2.35
            int mdnDigitsNum = 0x0F & data[0];
            mMdn = IccUtils.cdmaBcdToString(data, 1, mdnDigitsNum);
            if (DBG) log("CSIM MDN=" + mMdn);
        }
    }

    private class EfCsimImsimLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_IMSIM";
        }

        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            if (DBG) log("CSIM_IMSIM=" + IccUtils.bytesToHexString(data));
            // C.S0065 section 5.2.2 for IMSI_M encoding
            // C.S0005 section 2.3.1 for MIN encoding in IMSI_M.
            boolean provisioned = ((data[7] & 0x80) == 0x80);

            if (provisioned) {
                int first3digits = ((0x03 & data[2]) << 8) + (0xFF & data[1]);
                int second3digits = (((0xFF & data[5]) << 8) | (0xFF & data[4])) >> 6;
                int digit7 = 0x0F & (data[4] >> 2);
                if (digit7 > 0x09) digit7 = 0;
                int last3digits = ((0x03 & data[4]) << 8) | (0xFF & data[3]);
                first3digits = adjstMinDigits(first3digits);
                second3digits = adjstMinDigits(second3digits);
                last3digits = adjstMinDigits(last3digits);

                StringBuilder builder = new StringBuilder();
                builder.append(String.format(Locale.US, "%03d", first3digits));
                builder.append(String.format(Locale.US, "%03d", second3digits));
                builder.append(String.format(Locale.US, "%d", digit7));
                builder.append(String.format(Locale.US, "%03d", last3digits));
                mMin = builder.toString();
                if (DBG) log("min present=" + mMin);
            } else {
                if (DBG) log("min not present");
            }
        }
    }

    private class EfCsimCdmaHomeLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_CDMAHOME";
        }

        public void onRecordLoaded(AsyncResult ar) {
            // Per C.S0065 section 5.2.8
            ArrayList<byte[]> dataList = (ArrayList<byte[]>) ar.result;
            if (DBG) log("CSIM_CDMAHOME data size=" + dataList.size());
            if (dataList.isEmpty()) {
                return;
            }
            StringBuilder sidBuf = new StringBuilder();
            StringBuilder nidBuf = new StringBuilder();

            for (byte[] data : dataList) {
                if (data.length == 5) {
                    int sid = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
                    int nid = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);
                    sidBuf.append(sid).append(',');
                    nidBuf.append(nid).append(',');
                }
            }
            // remove trailing ","
            sidBuf.setLength(sidBuf.length()-1);
            nidBuf.setLength(nidBuf.length()-1);

            mHomeSystemId = sidBuf.toString();
            mHomeNetworkId = nidBuf.toString();
        }
    }

    private class EfCsimEprlLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_EPRL";
        }
        public void onRecordLoaded(AsyncResult ar) {
            onGetCSimEprlDone(ar);
        }
    }

    private void onGetCSimEprlDone(AsyncResult ar) {
        // C.S0065 section 5.2.57 for EFeprl encoding
        // C.S0016 section 3.5.5 for PRL format.
        byte[] data = (byte[]) ar.result;
        if (DBG) log("CSIM_EPRL=" + IccUtils.bytesToHexString(data));

        // Only need the first 4 bytes of record
        if (data.length > 3) {
            int prlId = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            mPrlVersion = Integer.toString(prlId);
        }
        if (DBG) log("CSIM PRL version=" + mPrlVersion);
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        byte data[];

        boolean isRecordLoadResponse = false;

        if (mDestroyed.get()) {
            loge("Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }

        try { switch (msg.what) {
            case EVENT_APP_READY:
                onReady();
                break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                resetRecords();
            break;

            case EVENT_GET_DEVICE_IDENTITY_DONE:
                log("Event EVENT_GET_DEVICE_IDENTITY_DONE Received");
            break;

            /* IO events */
            case EVENT_GET_IMSI_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    loge("Exception querying IMSI, Exception:" + ar.exception);
                    break;
                }

                mImsi = (String) ar.result;

                // IMSI (MCC+MNC+MSIN) is at least 6 digits, but not more
                // than 15 (and usually 15).
                if (mImsi != null && (mImsi.length() < 6 || mImsi.length() > 15)) {
                    loge("invalid IMSI " + mImsi);
                    mImsi = null;
                }

                log("IMSI: " + mImsi.substring(0, 6) + "xxxxxxxxx");

                String operatorNumeric = getRUIMOperatorNumeric();
                if (operatorNumeric != null) {
                    if(operatorNumeric.length() <= 6){
                        MccTable.updateMccMncConfiguration(mContext, operatorNumeric);
                    }
                }
            break;

            case EVENT_GET_CDMA_SUBSCRIPTION_DONE:
                ar = (AsyncResult)msg.obj;
                String localTemp[] = (String[])ar.result;
                if (ar.exception != null) {
                    break;
                }

                mMyMobileNumber = localTemp[0];
                mMin2Min1 = localTemp[3];
                mPrlVersion = localTemp[4];

                log("MDN: " + mMyMobileNumber + " MIN: " + mMin2Min1);

            break;

            case EVENT_GET_ICCID_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                iccid = IccUtils.bcdToString(data, 0, data.length);

                log("iccid: " + iccid);

            break;

            case EVENT_UPDATE_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    Log.i(LOG_TAG, "RuimRecords update failed", ar.exception);
                }
            break;

            case EVENT_GET_ALL_SMS_DONE:
            case EVENT_MARK_SMS_READ_DONE:
            case EVENT_SMS_ON_RUIM:
            case EVENT_GET_SMS_DONE:
                Log.w(LOG_TAG, "Event not supported: " + msg.what);
                break;

            // TODO: probably EF_CST should be read instead
            case EVENT_GET_SST_DONE:
                log("Event EVENT_GET_SST_DONE Received");
            break;

            case EVENT_RUIM_REFRESH:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleRuimRefresh((IccRefreshResponse)ar.result);
                }
                break;

            case EVENT_PHB_READY:
                if (DBG) log("handleMessage (EVENT_PHB_READY)");
                mPhbReady = true;
                //No need to update system property because it has been updated in rill.
                broadcastPhbStateChangedIntent(mPhbReady);
                break;

            default:
                super.handleMessage(msg);   // IccRecords handles generic record load responses

        }}catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            Log.w(LOG_TAG, "Exception parsing RUIM record", exc);
        } finally {
            // Count up record load responses even if they are fails
            if (isRecordLoadResponse) {
                onRecordLoaded();
            }
        }
    }

    private String findBestLanguage(byte[] languages) {
        String bestMatch = null;
        String[] locales = mContext.getAssets().getLocales();

        if ((languages == null) || (locales == null)) return null;

        // Each 2-bytes consists of one language
        for (int i = 0; (i + 1) < languages.length; i += 2) {
            try {
                String lang = new String(languages, i, 2, "ISO-8859-1");
                for (int j = 0; j < locales.length; j++) {
                    if (locales[j] != null && locales[j].length() >= 2 &&
                        locales[j].substring(0, 2).equals(lang)) {
                        return lang;
                    }
                }
                if (bestMatch != null) break;
            } catch(java.io.UnsupportedEncodingException e) {
                log ("Failed to parse SIM language records");
            }
        }
        // no match found. return null
        return null;
    }

    private void setLocaleFromCsim() {
        String prefLang = null;
        // check EFli then EFpl
        prefLang = findBestLanguage(mEFli);

        if (prefLang == null) {
            prefLang = findBestLanguage(mEFpl);
        }

        if (prefLang != null) {
            // check country code from SIM
            String imsi = getIMSI();
            String country = null;
            if (imsi != null) {
                country = MccTable.countryCodeForMcc(
                                    Integer.parseInt(imsi.substring(0,3)));
            }
            log("Setting locale to " + prefLang + "_" + country);
            MccTable.setSystemLocale(mContext, prefLang, country);
        } else {
            log ("No suitable CSIM selected locale");
        }
    }

    @Override
    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        recordsToLoad -= 1;
        if (DBG) log("onRecordLoaded " + recordsToLoad + " requested: " + recordsRequested);

        if (recordsToLoad == 0 && recordsRequested == true) {
            onAllRecordsLoaded();
        } else if (recordsToLoad < 0) {
            loge("recordsToLoad <0, programmer error suspected");
            recordsToLoad = 0;
        }
    }

    //via support start
    private void fetchEccList() {
        if (DBG) log("fetchEccList()"); 
        bEccRequired = true;
        String numbers = SystemProperties.get("ro.ril.ecclist.cdma");
	if (DBG) log("fetchEccList from ro.ril.cdma.ecclist" + numbers); 
        if (!TextUtils.isEmpty(numbers)) { 
            if (PhoneConstants.GEMINI_SIM_2 == mSimId) {
                SystemProperties.set("ril.ecclist2", numbers);
            } else {
                SystemProperties.set("ril.ecclist", numbers);
            }
	}

	numbers = SystemProperties.get("ril.ecclist2");
	if (DBG) log("fetchEccList from ro.ril.cdma.ecclist after write " + numbers); 
    }
    //via support end

    @Override
    protected void onAllRecordsLoaded() {
        if (DBG) log("record load complete");

        // Further records that can be inserted are Operator/OEM dependent

        String operator = getRUIMOperatorNumeric();
        log("RuimRecords: onAllRecordsLoaded set 'gsm.sim.operator.numeric' to operator='" +
                operator + "'");
        SystemProperties.set(PROPERTY_ICC_OPERATOR_NUMERIC, operator);

        if (mImsi != null) {
            SystemProperties.set(PROPERTY_ICC_OPERATOR_ISO_COUNTRY,
                    MccTable.countryCodeForMcc(Integer.parseInt(mImsi.substring(0,3))));
        }

        setLocaleFromCsim();
        recordsLoadedRegistrants.notifyRegistrants(
            new AsyncResult(null, null, null));
    }

    @Override
    public void onReady() {
        fetchRuimRecords();

        mCi.getCDMASubscription(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_DONE));
    }


    private void fetchRuimRecords() {
        recordsRequested = true;

        if (DBG) log("fetchRuimRecords " + recordsToLoad);

        mCi.getIMSIForApp(mParentApp.getAid(), obtainMessage(EVENT_GET_IMSI_DONE));
        recordsToLoad++;

        mFh.loadEFTransparent(EF_ICCID,
                obtainMessage(EVENT_GET_ICCID_DONE));
        recordsToLoad++;

        mFh.loadEFTransparent(EF_PL,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfPlLoaded()));
        recordsToLoad++;

        mFh.loadEFTransparent(EF_CSIM_LI,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimLiLoaded()));
        recordsToLoad++;

        mFh.loadEFTransparent(EF_CSIM_SPN,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimSpnLoaded()));
        recordsToLoad++;

        mFh.loadEFLinearFixed(EF_CSIM_MDN, 1,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimMdnLoaded()));
        recordsToLoad++;

        mFh.loadEFTransparent(EF_CSIM_IMSIM,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimImsimLoaded()));
        recordsToLoad++;

        mFh.loadEFLinearFixedAll(EF_CSIM_CDMAHOME,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimCdmaHomeLoaded()));
        recordsToLoad++;

        // Entire PRL could be huge. We are only interested in
        // the first 4 bytes of the record.
        mFh.loadEFTransparent(EF_CSIM_EPRL, 4,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimEprlLoaded()));
        recordsToLoad++;

        if (DBG) log("fetchRuimRecords " + recordsToLoad + " requested: " + recordsRequested);
        // Further records that can be inserted are Operator/OEM dependent
    }

    /**
     * {@inheritDoc}
     *
     * No Display rule for RUIMs yet.
     */
    @Override
    public int getDisplayRule(String plmn) {
        // TODO together with spn
        return 0;
    }

    @Override
    public boolean isProvisioned() {
        // If UICC card has CSIM app, look for MDN and MIN field
        // to determine if the SIM is provisioned.  Otherwise,
        // consider the SIM is provisioned. (for case of ordinal
        // USIM only UICC.)
        // If PROPERTY_TEST_CSIM is defined, bypess provision check
        // and consider the SIM is provisioned.
        if (SystemProperties.getBoolean(PROPERTY_TEST_CSIM, false)) {
            return true;
        }

        if (mParentApp == null) {
            return false;
        }

        if (mParentApp.getType() == AppType.APPTYPE_CSIM &&
            ((mMdn == null) || (mMin == null))) {
            return false;
        }
        return true;
    }

    @Override
    public void setVoiceMessageWaiting(int line, int countWaiting) {
        if (line != 1) {
            // only profile 1 is supported
            return;
        }

        // range check
        if (countWaiting < 0) {
            countWaiting = -1;
        } else if (countWaiting > 0xff) {
            // C.S0015-B v2, 4.5.12
            // range: 0-99
            countWaiting = 0xff;
        }
        countVoiceMessages = countWaiting;

        mRecordsEventsRegistrants.notifyResult(EVENT_MWI);
    }

    private void handleRuimRefresh(IccRefreshResponse refreshResponse) {
        if (refreshResponse == null) {
            if (DBG) log("handleRuimRefresh received without input");
            return;
        }

        if (refreshResponse.aid != null &&
                !refreshResponse.aid.equals(mParentApp.getAid())) {
            // This is for different app. Ignore.
            return;
        }

        switch (refreshResponse.refreshResult) {
            case IccRefreshResponse.REFRESH_RESULT_FILE_UPDATE:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_FILE_UPDATED");
                adnCache.reset();
                fetchRuimRecords();
                break;
            case IccRefreshResponse.REFRESH_RESULT_INIT:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_INIT");
                // need to reload all files (that we care about)
                fetchRuimRecords();
                break;
            case IccRefreshResponse.REFRESH_RESULT_RESET:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_RESET");
                mCi.setRadioPower(false, null);
                /* Note: no need to call setRadioPower(true).  Assuming the desired
                * radio power state is still ON (as tracked by ServiceStateTracker),
                * ServiceStateTracker will call setRadioPower when it receives the
                * RADIO_STATE_CHANGED notification for the power off.  And if the
                * desired power state has changed in the interim, we don't want to
                * override it with an unconditional power on.
                */
                break;
            default:
                // unknown refresh operation
                if (DBG) log("handleRuimRefresh with unknown operation");
                break;
        }
    }

    public String getMdn() {
        return mMdn;
    }

    public String getMin() {
        return mMin;
    }

    public String getSid() {
        return mHomeSystemId;
    }

    public String getNid() {
        return mHomeNetworkId;
    }

    public boolean getCsimSpnDisplayCondition() {
        return mCsimSpnDisplayCondition;
    }

    public void broadcastPhbStateChangedIntent(boolean isReady) {
        log("broadcastPhbStateChangedIntent, mPhbReady " + mPhbReady + ", mSIMInfoReady " + mSIMInfoReady);
        if (mPhbReady && mSIMInfoReady) {
            Intent intent = new Intent(TelephonyIntents.ACTION_PHB_STATE_CHANGED);
            intent.putExtra("ready", isReady);
            intent.putExtra(PhoneConstants.GEMINI_SIM_ID_KEY, mParentApp.getMySimId());
            if (DBG) log("Broadcasting intent ACTION_PHB_STATE_CHANGED " + isReady
                        + " sim id " + mParentApp.getMySimId());
            mContext.sendBroadcast(intent);
        }
    }

    public boolean isPhbReady() {
        if (DBG) log("isPhbReady(): cached mPhbReady = " + (mPhbReady ? "true" : "false"));
        String strPhbReady = null;
        if (PhoneConstants.GEMINI_SIM_2 == mParentApp.getMySimId()) {
            strPhbReady = SystemProperties.get(PROPERTY_RIL_PHB_READY_2, "false");
        } else {
            strPhbReady = SystemProperties.get(PROPERTY_RIL_PHB_READY, "false");
        }   
        
        if (strPhbReady.equals("true")){
            mPhbReady = true;
        } else {
            mPhbReady = false;
        }
        if (DBG) log("isPhbReady(): mPhbReady = " + (mPhbReady ? "true" : "false"));
        return mPhbReady;
    }

    private final BroadcastReceiver mHandlePhbReadyReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            log("Receive action " + action);
            if (action.equals(TelephonyIntents.ACTION_SIM_INFO_UPDATE)) {
                mContext.unregisterReceiver(mHandlePhbReadyReceiver);
                mSIMInfoReady = true;
                broadcastPhbStateChangedIntent(true);

                IntentFilter phbFilter = new IntentFilter();
                phbFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                phbFilter.addAction("android.intent.action.ACTION_SHUTDOWN_IPO");
                mContext.registerReceiver(mHandlePhbReadyReceiver, phbFilter);
            }
            
            if (FeatureOption.MTK_FLIGHT_MODE_POWER_OFF_MD) {
                if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                    boolean enabled = intent.getBooleanExtra("state", false);
                    log("mHandlePhbReadyReceiver MTK_FLIGHT_MODE_POWER_OFF_MD flightmode = " + enabled);
                    if (enabled) {
                        if (PhoneConstants.GEMINI_SIM_2 == mParentApp.getMySimId()) {
                            SystemProperties.set(PROPERTY_RIL_PHB_READY_2, "false");
                        } else {
                            SystemProperties.set(PROPERTY_RIL_PHB_READY, "false");
                        }   
                        mPhbReady = false;
                    }
                }
            }

            if(action.equals("android.intent.action.ACTION_SHUTDOWN_IPO")) {
                log("ACTION_SHUTDOWN_IPO: clear PHB_READY systemproperties");
                if (PhoneConstants.GEMINI_SIM_2 == mParentApp.getMySimId()) {
                    SystemProperties.set(PROPERTY_RIL_PHB_READY_2, "false");
                } else {
                    SystemProperties.set(PROPERTY_RIL_PHB_READY, "false");
                }   
                mPhbReady = false;
            }   
        }
    };

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[RuimRecords] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[RuimRecords] " + s);
    }
}
