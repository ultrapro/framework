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

package com.android.internal.telephony;

import com.android.internal.telephony.SmsConstants;
import com.android.internal.telephony.SmsHeader;
import java.util.Arrays;

import android.provider.Telephony;
import android.telephony.SmsMessage.MessageClass;

import java.util.Arrays;

import android.util.Log;
/**
 * Base class declaring the specific methods and members for SmsMessage.
 * {@hide}
 */
public abstract class SmsMessageBase {
    private static final String LOG_TAG = "SMS";

    /** {@hide} The address of the SMSC. May be null */
    protected String scAddress;

    /** {@hide} The address of the sender */
    protected SmsAddress originatingAddress;

    /** {@hide} The message body as a string. May be null if the message isn't text */
    protected String messageBody;

    /** {@hide} */
    protected String pseudoSubject;

    /** {@hide} Non-null if this is an email gateway message */
    protected String emailFrom;

    /** {@hide} Non-null if this is an email gateway message */
    protected String emailBody;

    /** {@hide} */
    protected boolean isEmail;

    /** {@hide} */
    protected long scTimeMillis;

    /** {@hide} The raw PDU of the message */
    protected byte[] mPdu;

    /** {@hide} The raw bytes for the user data section of the message */
    protected byte[] userData;

    /** {@hide} */
    protected SmsHeader userDataHeader;

    // "Message Waiting Indication Group"
    // 23.038 Section 4
    /** {@hide} */
    protected boolean isMwi;

    /** {@hide} */
    protected boolean mwiSense;

    /** {@hide} */
    protected boolean mwiDontStore;

    /**
     * Indicates status for messages stored on the ICC.
     */
    protected int statusOnIcc = -1;

    /**
     * Record index of message in the EF.
     */
    protected int indexOnIcc = -1;

    /** TP-Message-Reference - Message Reference of sent message. @hide */
    public int messageRef;

    // MTK-START [ALPS000xxxxx] MTK code port to ICS added by mtk80589 in 2011.11.16
    /** {@hide} The address of the receiver */
    protected SmsAddress destinationAddress;

    /** {@hide} */
    protected int relativeValidityPeriod;
    /** {@hide} */
    protected int absoluteValidityPeriod;

    /** {@hide} */
    protected int mwiType = -1;
    /** {@hide} */
    protected int mwiCount = 0;
    // MTK-END [ALPS000xxxxx] MTK code port to ICS added by mtk80589 in 2011.11.16
    // MTK-START [ALPS000xxxxx] MTK code port to ICS added by mtk80589 in 2011.11.16
    // base class of submit/deliver pdu
    public static abstract class PduBase {
        public byte[] encodedScAddress; // Null if not applicable.
        public byte[] encodedMessage;

        abstract public String toString();
    }

    // TODO(): This class is duplicated in SmsMessage.java. Refactor accordingly.
    public static abstract class SubmitPduBase extends PduBase  {
        public String toString() {
            return "SubmitPdu: encodedScAddress = "
                    + Arrays.toString(encodedScAddress)
                    + ", encodedMessage = "
                    + Arrays.toString(encodedMessage);
        }
    }

    public static abstract class DeliverPduBase extends PduBase {
        public String toString() {
            return "DeliverPdu: encodedScAddress = "
                    + Arrays.toString(encodedScAddress)
                    + ", encodedMessage = "
                    + Arrays.toString(encodedMessage);
        }
    }
    // MTK-END [ALPS000xxxxx] MTK code port to ICS added by mtk80589 in 2011.11.16

    /**
     * Returns the address of the SMS service center that relayed this message
     * or null if there is none.
     */
    public String getServiceCenterAddress() {
        return scAddress;
    }

    /**
     * Returns the originating address (sender) of this SMS message in String
     * form or null if unavailable
     */
    public String getOriginatingAddress() {
        if (originatingAddress == null) {
            return null;
        }

        return originatingAddress.getAddressString();
    }

    /**
     * Returns the originating address, or email from address if this message
     * was from an email gateway. Returns null if originating address
     * unavailable.
     */
    public String getDisplayOriginatingAddress() {
        if (isEmail) {
            return emailFrom;
        } else {
            return getOriginatingAddress();
        }
    }

    /**
     * Returns the message body as a String, if it exists and is text based.
     * @return message body is there is one, otherwise null
     */
    public String getMessageBody() {
        return messageBody;
    }

    /**
     * Returns the class of this message.
     */
    public abstract SmsConstants.MessageClass getMessageClass();

    /**
     * Returns the message body, or email message body if this message was from
     * an email gateway. Returns null if message body unavailable.
     */
    public String getDisplayMessageBody() {
        if (isEmail) {
            return emailBody;
        } else {
            return getMessageBody();
        }
    }

    /**
     * Unofficial convention of a subject line enclosed in parens empty string
     * if not present
     */
    public String getPseudoSubject() {
        return pseudoSubject == null ? "" : pseudoSubject;
    }

    /**
     * Returns the service centre timestamp in currentTimeMillis() format
     */
    public long getTimestampMillis() {
        return scTimeMillis;
    }

    /**
     * Returns true if message is an email.
     *
     * @return true if this message came through an email gateway and email
     *         sender / subject / parsed body are available
     */
    public boolean isEmail() {
        return isEmail;
    }

    /**
     * @return if isEmail() is true, body of the email sent through the gateway.
     *         null otherwise
     */
    public String getEmailBody() {
        return emailBody;
    }

    /**
     * @return if isEmail() is true, email from address of email sent through
     *         the gateway. null otherwise
     */
    public String getEmailFrom() {
        return emailFrom;
    }

    /**
     * Get protocol identifier.
     */
    public abstract int getProtocolIdentifier();

    /**
     * See TS 23.040 9.2.3.9 returns true if this is a "replace short message"
     * SMS
     */
    public abstract boolean isReplace();

    /**
     * Returns true for CPHS MWI toggle message.
     *
     * @return true if this is a CPHS MWI toggle message See CPHS 4.2 section
     *         B.4.2
     */
    public abstract boolean isCphsMwiMessage();

    /**
     * returns true if this message is a CPHS voicemail / message waiting
     * indicator (MWI) clear message
     */
    public abstract boolean isMWIClearMessage();

    /**
     * returns true if this message is a CPHS voicemail / message waiting
     * indicator (MWI) set message
     */
    public abstract boolean isMWISetMessage();

    /**
     * returns true if this message is a "Message Waiting Indication Group:
     * Discard Message" notification and should not be stored.
     */
    public abstract boolean isMwiDontStore();

    /**
     * returns the user data section minus the user data header if one was
     * present.
     */
    public byte[] getUserData() {
        return userData;
    }

    /**
     * Returns an object representing the user data header
     *
     * {@hide}
     */
    public SmsHeader getUserDataHeader() {
        return userDataHeader;
    }

    /**
     * TODO(cleanup): The term PDU is used in a seemingly non-unique
     * manner -- for example, what is the difference between this byte
     * array and the contents of SubmitPdu objects.  Maybe a more
     * illustrative term would be appropriate.
     */

    /**
     * Returns the raw PDU for the message.
     */
    public byte[] getPdu() {
        return mPdu;
    }

    /**
     * For an SMS-STATUS-REPORT message, this returns the status field from
     * the status report.  This field indicates the status of a previously
     * submitted SMS, if requested.  See TS 23.040, 9.2.3.15 TP-Status for a
     * description of values.
     *
     * @return 0 indicates the previously sent message was received.
     *         See TS 23.040, 9.9.2.3.15 for a description of other possible
     *         values.
     */
    public abstract int getStatus();

    /**
     * Return true iff the message is a SMS-STATUS-REPORT message.
     */
    public abstract boolean isStatusReportMessage();

    /**
     * Returns true iff the <code>TP-Reply-Path</code> bit is set in
     * this message.
     */
    public abstract boolean isReplyPathPresent();

    /**
     * Returns the status of the message on the ICC (read, unread, sent, unsent).
     *
     * @return the status of the message on the ICC.  These are:
     *         SmsManager.STATUS_ON_ICC_FREE
     *         SmsManager.STATUS_ON_ICC_READ
     *         SmsManager.STATUS_ON_ICC_UNREAD
     *         SmsManager.STATUS_ON_ICC_SEND
     *         SmsManager.STATUS_ON_ICC_UNSENT
     */
    public int getStatusOnIcc() {
        return statusOnIcc;
    }

    /**
     * Returns the record index of the message on the ICC (1-based index).
     * @return the record index of the message on the ICC, or -1 if this
     *         SmsMessage was not created from a ICC SMS EF record.
     */
    public int getIndexOnIcc() {
        return indexOnIcc;
    }

    protected void parseMessageBody() {
        // originatingAddress could be null if this message is from a status
        // report.
        if (originatingAddress != null && 
                originatingAddress.couldBeEmailGateway() &&
                !isReplace() ) {
            extractEmailAddressFromMessageBody();
        }
    }

    /**
     * Try to parse this message as an email gateway message
     * There are two ways specified in TS 23.040 Section 3.8 :
     *  - SMS message "may have its TP-PID set for Internet electronic mail - MT
     * SMS format: [<from-address><space>]<message> - "Depending on the
     * nature of the gateway, the destination/origination address is either
     * derived from the content of the SMS TP-OA or TP-DA field, or the
     * TP-OA/TP-DA field contains a generic gateway address and the to/from
     * address is added at the beginning as shown above." (which is supported here)
     * - Multiple addresses separated by commas, no spaces, Subject field delimited
     * by '()' or '##' and '#' Section 9.2.3.24.11 (which are NOT supported here)
     */
    protected void extractEmailAddressFromMessageBody() {

        /* Some carriers may use " /" delimiter as below
         *
         * 1. [x@y][ ]/[subject][ ]/[body]
         * -or-
         * 2. [x@y][ ]/[body]
         */
         String[] parts = messageBody.split("( /)|( )", 2);
         if (parts.length < 2) return;
         emailFrom = parts[0];
         emailBody = parts[1];
         isEmail = Telephony.Mms.isEmailAddress(emailFrom);
    }

    // MTK-START [ALPS000xxxxx] MTK code port to ICS added by mtk80589 in 2011.11.16
    /**
     * Returns the destination address (receiver) of this SMS message in String
     * form or null if unavailable
     */
    public String getDestinationAddress() {
        if (destinationAddress == null) {
            return null;
        }

        return destinationAddress.getAddressString();
    }
    // MTK-END [ALPS000xxxxx] MTK code port to ICS added by mtk80589 in 2011.11.16
}
