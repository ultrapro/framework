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

package com.android.internal.telephony;

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;


/**
 *
 * Used to load or store ADNs (Abbreviated Dialing Numbers).
 *
 * {@hide}
 *
 */
public class AdnRecord implements Parcelable {
    static final String LOG_TAG = "GSM";

    // ***** Instance Variables

    String alphaTag = null;
    String number = null;
    String additionalNumber = null;
    String grpIds;
    String[] emails;
    int extRecord = 0xff;
    int efid; // or 0 if none
    int recordNumber; // or 0 if none
    // The index of aas
    int aas = -1;
    String sne = null;

    // ***** Constants

    // In an ADN record, everything but the alpha identifier
    // is in a footer that's 14 bytes
    static final int FOOTER_SIZE_BYTES = 14;

    // Maximum size of the un-extended number field
    static final int MAX_NUMBER_SIZE_BYTES = 11;

    static final int EXT_RECORD_LENGTH_BYTES = 13;
    static final int EXT_RECORD_TYPE_ADDITIONAL_DATA = 2;
    static final int EXT_RECORD_TYPE_MASK = 3;
    static final int MAX_EXT_CALLED_PARTY_LENGTH = 0xa;

    // ADN offset
    static final int ADN_BCD_NUMBER_LENGTH = 0;
    static final int ADN_TON_AND_NPI = 1;
    static final int ADN_DIALING_NUMBER_START = 2;
    static final int ADN_DIALING_NUMBER_END = 11;
    static final int ADN_CAPABILITY_ID = 12;
    static final int ADN_EXTENSION_ID = 13;

    // ***** Static Methods

    public static final Parcelable.Creator<AdnRecord> CREATOR = new Parcelable.Creator<AdnRecord>() {
        public AdnRecord createFromParcel(Parcel source) {
            int efid;
            int recordNumber;
            String alphaTag;
            String number;
            String anr;
            String grpIds;
            String[] emails;

            efid = source.readInt();
            recordNumber = source.readInt();
            alphaTag = source.readString();
            number = source.readString();
            emails = source.readStringArray();
            anr = source.readString();
            grpIds = source.readString();
            int aas = source.readInt();
            String sne = source.readString();
            AdnRecord adn = new AdnRecord(efid, recordNumber, alphaTag, number, anr, emails, grpIds);
            adn.setAasIndex(aas);
            adn.setSne(sne);
            return adn;
        }

        public AdnRecord[] newArray(int size) {
            return new AdnRecord[size];
        }
    };

    // ***** Constructor
    public AdnRecord(byte[] record) {
        this(0, 0, record);
    }

    public AdnRecord(int efid, int recordNumber, byte[] record) {
        this.efid = efid;
        this.recordNumber = recordNumber;
        parseRecord(record);
    }

    public AdnRecord(String alphaTag, String number) {
        this(0, 0, alphaTag, number);
    }

    public AdnRecord(String alphaTag, String number, String anr) {
        this(0, 0, alphaTag, number, anr);
    }

    public AdnRecord(String alphaTag, String number, String[] emails) {
        this(0, 0, alphaTag, number, emails);
    }

    public AdnRecord(int efid, int recordNumber, String alphaTag, String number, String[] emails) {
        this.efid = efid;
        this.recordNumber = recordNumber;
        this.alphaTag = alphaTag;
        this.number = number;
        this.emails = emails;
        this.additionalNumber = "";
        this.grpIds = null;
    }

    public AdnRecord(int efid, int recordNumber, String alphaTag, String number) {
        this.efid = efid;
        this.recordNumber = recordNumber;
        this.alphaTag = alphaTag;
        this.number = number;
        this.emails = null;
        this.additionalNumber = "";
        this.grpIds = null;
    }

    public AdnRecord(int efid, int recordNumber, String alphaTag, String number, String anr) {
        this.efid = efid;
        this.recordNumber = recordNumber;
        this.alphaTag = alphaTag;
        this.number = number;
        this.emails = null;
        this.additionalNumber = anr;
        this.grpIds = null;
    }

    public AdnRecord(int efid, int recordNumber, String alphaTag, String number, String anr,
            String[] emails, String grps) {
        this.efid = efid;
        this.recordNumber = recordNumber;
        this.alphaTag = alphaTag;
        this.number = number;
        this.emails = emails;
        this.additionalNumber = anr;
        this.grpIds = grps;
    }

    // ***** Instance Methods
    public int getRecordIndex() {
        return recordNumber;
    }

    public String getAlphaTag() {
        return alphaTag;
    }

    public String getNumber() {
        return number;
    }

    public String getAdditionalNumber() {
        return additionalNumber;
    }

    public int getAasIndex() {
        return aas;
    }

    public String getSne() {
        return sne;
    }

    public String[] getEmails() {
        return emails;
    }

    public String getGrpIds() {
        return grpIds;
    }

    public void setAnr(String anr) {
        this.additionalNumber = anr;
    }

    public void setAasIndex(int aas) {
        this.aas = aas;
    }

    public void setSne(String sne) {
        this.sne = sne;
    }

    public void setGrpIds(String grps) {
        this.grpIds = grps;
    }

    public void setEmails(String[] emails) {
        this.emails = emails;
    }

    public void setRecordIndex(int nIndex) {
        this.recordNumber = nIndex;
    }

    public String toString() {
        return "ADN Record:" + recordNumber
                + ",alphaTag:" + alphaTag
                + ",number:" + number
                + ",anr:" + additionalNumber
                + ",aas:" + aas
                + ",emails:" + emails
                + ",grpIds:" + grpIds
                + ",sne:" + sne;
        // return "ADN Record '" + recordNumber + "' '" + alphaTag + "' '" +
        // number + "' '" + additionalNumber + "' '" + aas + "' '" + emails +
        // "' '" + grpIds + "' ";
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(alphaTag) && TextUtils.isEmpty(number)
                && TextUtils.isEmpty(additionalNumber) && emails == null;
    }

    public boolean hasExtendedRecord() {
        return extRecord != 0 && extRecord != 0xff;
    }

    /** Helper function for {@link #isEqual}. */
    private static boolean stringCompareNullEqualsEmpty(String s1, String s2) {
        if (s1 == s2) {
            return true;
        }
        if (s1 == null) {
            s1 = "";
        }
        if (s2 == null) {
            s2 = "";
        }
        return (s1.equals(s2));
    }

    public boolean isEqual(AdnRecord adn) {
        return (stringCompareNullEqualsEmpty(alphaTag, adn.alphaTag) && stringCompareNullEqualsEmpty(
                number, adn.number));
    }

    // ***** Parcelable Implementation

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(efid);
        dest.writeInt(recordNumber);
        dest.writeString(alphaTag);
        dest.writeString(number);
        dest.writeStringArray(emails);
        dest.writeString(additionalNumber);
        dest.writeString(grpIds);
        dest.writeInt(aas);
        dest.writeString(sne);
    }

    /**
     * Build adn hex byte array based on record size The format of byte array is
     * defined in 51.011 10.5.1
     * 
     * @param recordSize is the size X of EF record
     * @return hex byte[recordSize] to be written to EF record return null for
     *         wrong format of dialing number or tag
     */
    public byte[] buildAdnString(int recordSize) {
        byte[] bcdNumber;
        byte[] byteTag;
        byte[] adnString;
        int footerOffset = recordSize - FOOTER_SIZE_BYTES;

        // create an empty record
        adnString = new byte[recordSize];
        for (int i = 0; i < recordSize; i++) {
            adnString[i] = (byte) 0xFF;
        }

        if (TextUtils.isEmpty(number)) {
            Log.w(LOG_TAG, "[buildAdnString] Empty dialing number");
            return adnString; // return the empty record (for delete)
        } else if (number.length() > (ADN_DIALING_NUMBER_END - ADN_DIALING_NUMBER_START + 1) * 2) {
            Log.w(LOG_TAG,
                    "[buildAdnString] Max length of dialing number is 20");
            return null;
        } else if (alphaTag != null && alphaTag.length() > footerOffset) {
            Log.w(LOG_TAG,
                    "[buildAdnString] Max length of tag is " + footerOffset);
            return null;
        } else {
            bcdNumber = PhoneNumberUtils.numberToCalledPartyBCD(number);

            System.arraycopy(bcdNumber, 0, adnString,
                    footerOffset + ADN_TON_AND_NPI, bcdNumber.length);

            adnString[footerOffset + ADN_BCD_NUMBER_LENGTH] = (byte) (bcdNumber.length);
            adnString[footerOffset + ADN_CAPABILITY_ID] = (byte) 0xFF; // Capability
                                                                       // Id
            adnString[footerOffset + ADN_EXTENSION_ID] = (byte) 0xFF; // Extension
                                                                      // Record
                                                                      // Id

            if (!TextUtils.isEmpty(alphaTag)) {
                byteTag = GsmAlphabet.stringToGsm8BitPacked(alphaTag);
                System.arraycopy(byteTag, 0, adnString, 0, byteTag.length);
            }

            return adnString;
        }
    }

    /**
     * See TS 51.011 10.5.10
     */
    public void appendExtRecord(byte[] extRecord) {
        try {
            if (extRecord.length != EXT_RECORD_LENGTH_BYTES) {
                return;
            }

            if ((extRecord[0] & EXT_RECORD_TYPE_MASK) != EXT_RECORD_TYPE_ADDITIONAL_DATA) {
                return;
            }

            if ((0xff & extRecord[1]) > MAX_EXT_CALLED_PARTY_LENGTH) {
                // invalid or empty record
                return;
            }

            number += PhoneNumberUtils.calledPartyBCDFragmentToString(
                    extRecord, 2, 0xff & extRecord[1]);

            // We don't support ext record chaining.

        } catch (RuntimeException ex) {
            Log.w(LOG_TAG, "Error parsing AdnRecord ext record", ex);
        }
    }

    // ***** Private Methods

    /**
     * alphaTag and number are set to null on invalid format
     */
    private void parseRecord(byte[] record) {
        try {
            alphaTag = IccUtils.adnStringFieldToString(
                    record, 0, record.length - FOOTER_SIZE_BYTES);

            int footerOffset = record.length - FOOTER_SIZE_BYTES;

            int numberLength = 0xff & record[footerOffset];

            if (numberLength > MAX_NUMBER_SIZE_BYTES) {
                // Invalid number length
                number = "";
                return;
            }

            // Please note 51.011 10.5.1:
            //
            // "If the Dialling Number/SSC String does not contain
            // a dialling number, e.g. a control string deactivating
            // a service, the TON/NPI byte shall be set to 'FF' by
            // the ME (see note 2)."

            number = PhoneNumberUtils.calledPartyBCDToString(
                    record, footerOffset + 1, numberLength);

            extRecord = 0xff & record[record.length - 1];

            emails = null;
            additionalNumber = "";
            grpIds = null;

        } catch (RuntimeException ex) {
            Log.w(LOG_TAG, "Error parsing AdnRecord", ex);
            number = "";
            alphaTag = "";
            emails = null;
            additionalNumber = "";
            grpIds = null;
        }
    }
}
