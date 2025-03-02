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

package com.android.internal.telephony.gsm;

import android.content.Context;
import com.android.internal.telephony.*;
import com.android.internal.telephony.IccCardApplicationStatus.AppState;

import android.os.*;
import android.telephony.PhoneNumberUtils;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.Log;

import static com.android.internal.telephony.CommandsInterface.*;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.mediatek.common.featureoption.FeatureOption;
import com.android.internal.telephony.gemini.GeminiPhone;


/**
 * The motto for this file is:
 *
 * "NOTE:    By using the # as a separator, most cases are expected to be unambiguous."
 *   -- TS 22.030 6.5.2
 *
 * {@hide}
 *
 */
public final class GsmMmiCode extends Handler implements MmiCode {
    static final String LOG_TAG = "GSM";

    //***** Constants

    // Max Size of the Short Code (aka Short String from TS 22.030 6.5.2)
    static final int MAX_LENGTH_SHORT_CODE = 2;

    // TS 22.030 6.5.2 Every Short String USSD command will end with #-key
    // (known as #-String)
    static final char END_OF_USSD_COMMAND = '#';

    // From TS 22.030 6.5.2
    static final String ACTION_ACTIVATE = "*";
    static final String ACTION_DEACTIVATE = "#";
    static final String ACTION_INTERROGATE = "*#";
    static final String ACTION_REGISTER = "**";
    static final String ACTION_ERASURE = "##";

    // Supp Service codes from TS 22.030 Annex B

    //Called line presentation
    static final String SC_CLIP    = "30";
    static final String SC_CLIR    = "31";

    // Call Forwarding
    static final String SC_CFU     = "21";
    static final String SC_CFB     = "67";
    static final String SC_CFNRy   = "61";
    static final String SC_CFNR    = "62";

    static final String SC_CF_All = "002";
    static final String SC_CF_All_Conditional = "004";

    // Call Waiting
    static final String SC_WAIT     = "43";

    // Call Barring
    static final String SC_BAOC         = "33";
    static final String SC_BAOIC        = "331";
    static final String SC_BAOICxH      = "332";
    static final String SC_BAIC         = "35";
    static final String SC_BAICr        = "351";

    static final String SC_BA_ALL       = "330";
    static final String SC_BA_MO        = "333";
    static final String SC_BA_MT        = "353";

    // Supp Service Password registration
    static final String SC_PWD          = "03";

    // PIN/PIN2/PUK/PUK2
    static final String SC_PIN          = "04";
    static final String SC_PIN2         = "042";
    static final String SC_PUK          = "05";
    static final String SC_PUK2         = "052";

    //***** Event Constants

    static final int EVENT_SET_COMPLETE         = 1;
    static final int EVENT_GET_CLIR_COMPLETE    = 2;
    static final int EVENT_QUERY_CF_COMPLETE    = 3;
    static final int EVENT_USSD_COMPLETE        = 4;
    static final int EVENT_QUERY_COMPLETE       = 5;
    static final int EVENT_SET_CFF_COMPLETE     = 6;
    static final int EVENT_USSD_CANCEL_COMPLETE = 7;

    /// M: [mtk04070][111125][ALPS00093395]MTK added. @{
    static final String PROPERTY_RIL_SIM_PIN1 =  "gsm.sim.retry.pin1";
    static final String PROPERTY_RIL_SIM_PUK1 =  "gsm.sim.retry.puk1";
    static final String PROPERTY_RIL_SIM_PIN2 =  "gsm.sim.retry.pin2";
    static final String PROPERTY_RIL_SIM_PUK2 =  "gsm.sim.retry.puk2";
    static final String PROPERTY_RIL_SIM2_PIN1 =  "gsm.sim.retry.pin1.2";
    static final String PROPERTY_RIL_SIM2_PUK1 =  "gsm.sim.retry.puk1.2";
    static final String PROPERTY_RIL_SIM2_PIN2 =  "gsm.sim.retry.pin2.2";
    static final String PROPERTY_RIL_SIM2_PUK2 =  "gsm.sim.retry.puk2.2";
    static final String RETRY_BLOCKED = "0";
    //Connected line presentation //mtk00732
    static final String SC_COLP    = "76";
    static final String SC_COLR    = "77";
    // mtk00732 add for COLP and COLR
    static final int EVENT_GET_COLR_COMPLETE    = 8;
    static final int EVENT_GET_COLP_COMPLETE    = 9;
    /// @}


    //***** Instance Variables

    GSMPhone phone;
    Context context;
    UiccCardApplication mUiccApplication;
    IccRecords mIccRecords;

    String action;              // One of ACTION_*
    String sc;                  // Service Code
    String sia, sib, sic;       // Service Info a,b,c
    String poundString;         // Entire MMI string up to and including #
    String dialingNumber;
    String pwd;                 // For password registration

    /** Set to true in processCode, not at newFromDialString time */
    private boolean isPendingUSSD;

    private boolean isUssdRequest;

    State state = State.PENDING;
    CharSequence message;

    //***** Class Variables


    // See TS 22.030 6.5.2 "Structure of the MMI"

    static Pattern sPatternSuppService = Pattern.compile(
        "((\\*|#|\\*#|\\*\\*|##)(\\d{2,3})(\\*([^*#]*)(\\*([^*#]*)(\\*([^*#]*)(\\*([^*#]*))?)?)?)?#)(.*)");
/*       1  2                    3          4  5       6   7         8    9     10  11             12

         1 = Full string up to and including #
         2 = action (activation/interrogation/registration/erasure)
         3 = service code
         5 = SIA
         7 = SIB
         9 = SIC
         10 = dialing number
*/

    static final int MATCH_GROUP_POUND_STRING = 1;

    static final int MATCH_GROUP_ACTION = 2;
                        //(activation/interrogation/registration/erasure)

    static final int MATCH_GROUP_SERVICE_CODE = 3;
    static final int MATCH_GROUP_SIA = 5;
    static final int MATCH_GROUP_SIB = 7;
    static final int MATCH_GROUP_SIC = 9;
    static final int MATCH_GROUP_PWD_CONFIRM = 11;
    static final int MATCH_GROUP_DIALING_NUMBER = 12;
    static private String[] sTwoDigitNumberPattern;

    //***** Public Class methods

    /**
     * Some dial strings in GSM are defined to do non-call setup
     * things, such as modify or query supplementary service settings (eg, call
     * forwarding). These are generally referred to as "MMI codes".
     * We look to see if the dial string contains a valid MMI code (potentially
     * with a dial string at the end as well) and return info here.
     *
     * If the dial string contains no MMI code, we return an instance with
     * only "dialingNumber" set
     *
     * Please see flow chart in TS 22.030 6.5.3.2
     */

    static GsmMmiCode
    newFromDialString(String dialString, GSMPhone phone, UiccCardApplication app) {
        Matcher m;
        GsmMmiCode ret = null;

        m = sPatternSuppService.matcher(dialString);

        // Is this formatted like a standard supplementary service code?
        if (m.matches()) {
            ret = new GsmMmiCode(phone, app);
            ret.poundString = makeEmptyNull(m.group(MATCH_GROUP_POUND_STRING));
            ret.action = makeEmptyNull(m.group(MATCH_GROUP_ACTION));
            ret.sc = makeEmptyNull(m.group(MATCH_GROUP_SERVICE_CODE));
            ret.sia = makeEmptyNull(m.group(MATCH_GROUP_SIA));
            ret.sib = makeEmptyNull(m.group(MATCH_GROUP_SIB));
            ret.sic = makeEmptyNull(m.group(MATCH_GROUP_SIC));
            ret.pwd = makeEmptyNull(m.group(MATCH_GROUP_PWD_CONFIRM));
            ret.dialingNumber = makeEmptyNull(m.group(MATCH_GROUP_DIALING_NUMBER));
            // According to TS 22.030 6.5.2 "Structure of the MMI",
            // the dialing number should not ending with #.
            // The dialing number ending # is treated as unique USSD,
            // eg, *400#16 digit number# to recharge the prepaid card
            // in India operator(Mumbai MTNL)
            if(ret.dialingNumber != null &&
                    ret.dialingNumber.endsWith("#") &&
                    dialString.endsWith("#")){
                ret = new GsmMmiCode(phone, app);
                ret.poundString = dialString;
            }

            /// M: [mtk04070][111125][ALPS00093395]MTK added log.
            Log.d(LOG_TAG,"poundString:" + ret.poundString + "\n"
                          + "action:" + ret.action + "\n"
                          + "sc:" + ret.sc + "\n"
                          + "sia:" + ret.sia + "\n" 
                          + "sib:" + ret.sib + "\n"
                          + "sic:" + ret.sic + "\n"
                          + "pwd:" + ret.pwd + "\n"
                          + "dialingNumber:" + ret.dialingNumber + "\n");

        } else if (dialString.endsWith("#")) {
            // TS 22.030 sec 6.5.3.2
            // "Entry of any characters defined in the 3GPP TS 23.038 [8] Default Alphabet
            // (up to the maximum defined in 3GPP TS 24.080 [10]), followed by #SEND".

            ret = new GsmMmiCode(phone, app);
            ret.poundString = dialString;
        } else if (isTwoDigitShortCode(phone.getContext(), dialString)) {
            //Is a country-specific exception to short codes as defined in TS 22.030, 6.5.3.2
            ret = null;
        } else if (isShortCode(dialString, phone)) {
            // this may be a short code, as defined in TS 22.030, 6.5.3.2
            ret = new GsmMmiCode(phone, app);
            ret.dialingNumber = dialString;
        }

        return ret;
    }

    static GsmMmiCode
    newNetworkInitiatedUssd (String ussdMessage,
                                boolean isUssdRequest, GSMPhone phone, UiccCardApplication app) {
        GsmMmiCode ret;

        ret = new GsmMmiCode(phone, app);

        ret.message = ussdMessage;
        ret.isUssdRequest = isUssdRequest;

        // If it's a request, set to PENDING so that it's cancelable.
        if (isUssdRequest) {
            ret.isPendingUSSD = true;
            ret.state = State.PENDING;
        } else {
            ret.state = State.COMPLETE;
        }

        return ret;
    }

    static GsmMmiCode newFromUssdUserInput(String ussdMessge,
                                           GSMPhone phone,
                                           UiccCardApplication app) {
        GsmMmiCode ret = new GsmMmiCode(phone, app);

        ret.message = ussdMessge;
        ret.state = State.PENDING;
        ret.isPendingUSSD = true;

        return ret;
    }

    //***** Private Class methods

    /** make empty strings be null.
     *  Regexp returns empty strings for empty groups
     */
    private static String
    makeEmptyNull (String s) {
        if (s != null && s.length() == 0) return null;

        return s;
    }

    /** returns true of the string is empty or null */
    private static boolean
    isEmptyOrNull(CharSequence s) {
        return s == null || (s.length() == 0);
    }


    private static int
    scToCallForwardReason(String sc) {
        if (sc == null) {
            throw new RuntimeException ("invalid call forward sc");
        }

        if (sc.equals(SC_CF_All)) {
           return CommandsInterface.CF_REASON_ALL;
        } else if (sc.equals(SC_CFU)) {
            return CommandsInterface.CF_REASON_UNCONDITIONAL;
        } else if (sc.equals(SC_CFB)) {
            return CommandsInterface.CF_REASON_BUSY;
        } else if (sc.equals(SC_CFNR)) {
            return CommandsInterface.CF_REASON_NOT_REACHABLE;
        } else if (sc.equals(SC_CFNRy)) {
            return CommandsInterface.CF_REASON_NO_REPLY;
        } else if (sc.equals(SC_CF_All_Conditional)) {
           return CommandsInterface.CF_REASON_ALL_CONDITIONAL;
        } else {
            throw new RuntimeException ("invalid call forward sc");
        }
    }

    private static int
    siToServiceClass(String si) {
        if (si == null || si.length() == 0) {
                return  SERVICE_CLASS_NONE;
        } else {
            // NumberFormatException should cause MMI fail
            int serviceCode = Integer.parseInt(si, 10);

            switch (serviceCode) {
                case 10: return SERVICE_CLASS_SMS + SERVICE_CLASS_FAX  + SERVICE_CLASS_VOICE;
                case 11: return SERVICE_CLASS_VOICE;
                case 12: return SERVICE_CLASS_SMS + SERVICE_CLASS_FAX;
                case 13: return SERVICE_CLASS_FAX;

                case 16: return SERVICE_CLASS_SMS;

                case 19: return SERVICE_CLASS_FAX + SERVICE_CLASS_VOICE;
/*
    Note for code 20:
     From TS 22.030 Annex C:
                "All GPRS bearer services" are not included in "All tele and bearer services"
                    and "All bearer services"."
....so SERVICE_CLASS_DATA, which (according to 27.007) includes GPRS
*/
                case 20: return SERVICE_CLASS_DATA_ASYNC + SERVICE_CLASS_DATA_SYNC;

                case 21: return SERVICE_CLASS_PAD + SERVICE_CLASS_DATA_ASYNC;
                case 22: return SERVICE_CLASS_PACKET + SERVICE_CLASS_DATA_SYNC;
                /// M: [mtk04070][111125][ALPS00093395]Add SERVICE_CLASS_VIDEO.
                case 24: return SERVICE_CLASS_DATA_SYNC + SERVICE_CLASS_VIDEO;
                case 25: return SERVICE_CLASS_DATA_ASYNC;
                case 26: return SERVICE_CLASS_DATA_SYNC + SERVICE_CLASS_VOICE;
                case 99: return SERVICE_CLASS_PACKET;

                default:
                    throw new RuntimeException("unsupported MMI service code " + si);
            }
        }
    }

    private static int
    siToTime (String si) {
        if (si == null || si.length() == 0) {
            return 0;
        } else {
            // NumberFormatException should cause MMI fail
            return Integer.parseInt(si, 10);
        }
    }

    static boolean
    isServiceCodeCallForwarding(String sc) {
        return sc != null &&
                (sc.equals(SC_CFU)
                || sc.equals(SC_CFB) || sc.equals(SC_CFNRy)
                || sc.equals(SC_CFNR) || sc.equals(SC_CF_All)
                || sc.equals(SC_CF_All_Conditional));
    }

    static boolean
    isServiceCodeCallBarring(String sc) {
        return sc != null &&
                (sc.equals(SC_BAOC)
                || sc.equals(SC_BAOIC)
                || sc.equals(SC_BAOICxH)
                || sc.equals(SC_BAIC)
                || sc.equals(SC_BAICr)
                || sc.equals(SC_BA_ALL)
                || sc.equals(SC_BA_MO)
                || sc.equals(SC_BA_MT));
    }

    static String
    scToBarringFacility(String sc) {
        if (sc == null) {
            throw new RuntimeException ("invalid call barring sc");
        }

        if (sc.equals(SC_BAOC)) {
            return CommandsInterface.CB_FACILITY_BAOC;
        } else if (sc.equals(SC_BAOIC)) {
            return CommandsInterface.CB_FACILITY_BAOIC;
        } else if (sc.equals(SC_BAOICxH)) {
            return CommandsInterface.CB_FACILITY_BAOICxH;
        } else if (sc.equals(SC_BAIC)) {
            return CommandsInterface.CB_FACILITY_BAIC;
        } else if (sc.equals(SC_BAICr)) {
            return CommandsInterface.CB_FACILITY_BAICr;
        } else if (sc.equals(SC_BA_ALL)) {
            return CommandsInterface.CB_FACILITY_BA_ALL;
        } else if (sc.equals(SC_BA_MO)) {
            return CommandsInterface.CB_FACILITY_BA_MO;
        } else if (sc.equals(SC_BA_MT)) {
            return CommandsInterface.CB_FACILITY_BA_MT;
        } else {
            throw new RuntimeException ("invalid call barring sc");
        }
    }

    //***** Constructor

    GsmMmiCode (GSMPhone phone, UiccCardApplication app) {
        // The telephony unit-test cases may create GsmMmiCode's
        // in secondary threads
        super(phone.getHandler().getLooper());
        this.phone = phone;
        this.context = phone.getContext();
        mUiccApplication = app;
        if (app != null) {
            mIccRecords = app.getIccRecords();
        }
    }

    //***** MmiCode implementation

    public State
    getState() {
        return state;
    }

    public CharSequence
    getMessage() {
        return message;
    }

    // inherited javadoc suffices
    public void
    cancel() {
        // Complete or failed cannot be cancelled
        if (state == State.COMPLETE || state == State.FAILED) {
            return;
        }

        state = State.CANCELLED;

        if (isPendingUSSD) {
            /*
             * There can only be one pending USSD session, so tell the radio to
             * cancel it.
             */
            phone.mCM.cancelPendingUssd(obtainMessage(EVENT_USSD_CANCEL_COMPLETE, this));

            /*
             * Don't call phone.onMMIDone here; wait for CANCEL_COMPLETE notice
             * from RIL.
             */
        } else {
            // TODO in cases other than USSD, it would be nice to cancel
            // the pending radio operation. This requires RIL cancellation
            // support, which does not presently exist.

            phone.onMMIDone (this);
        }

    }

    public boolean isCancelable() {
        /* Can only cancel pending USSD sessions. */
        return isPendingUSSD;
    }

    //***** Instance Methods

    /** Does this dial string contain a structured or unstructured MMI code? */
    boolean
    isMMI() {
        return poundString != null;
    }

    /* Is this a 1 or 2 digit "short code" as defined in TS 22.030 sec 6.5.3.2? */
    boolean
    isShortCode() {
        return poundString == null
                    && dialingNumber != null && dialingNumber.length() <= 2;

    }

    static private boolean
    isTwoDigitShortCode(Context context, String dialString) {
        Log.d(LOG_TAG, "isTwoDigitShortCode");

        if (dialString == null || dialString.length() != 2) return false;

        if (sTwoDigitNumberPattern == null) {
            sTwoDigitNumberPattern = context.getResources().getStringArray(
                    com.android.internal.R.array.config_twoDigitNumberPattern);
        }

        for (String dialnumber : sTwoDigitNumberPattern) {
            Log.d(LOG_TAG, "Two Digit Number Pattern " + dialnumber);
            if (dialString.equals(dialnumber)) {
                Log.d(LOG_TAG, "Two Digit Number Pattern -true");
                return true;
            }
        }
        Log.d(LOG_TAG, "Two Digit Number Pattern -false");
        return false;
    }

    /**
     * Helper function for newFromDialString. Returns true if dialString appears
     * to be a short code AND conditions are correct for it to be treated as
     * such.
     */
    static private boolean isShortCode(String dialString, GSMPhone phone) {
        // Refer to TS 22.030 Figure 3.5.3.2:
        if (dialString == null) {
            return false;
        }

        // Illegal dial string characters will give a ZERO length.
        // At this point we do not want to crash as any application with
        // call privileges may send a non dial string.
        // It return false as when the dialString is equal to NULL.
        if (dialString.length() == 0) {
            return false;
        }

        /// M: Replace isLocalEmergencyNumber with isEmergencyNumber to reduce dial speed, MTK04070, 2012.01.18.
        //if (PhoneNumberUtils.isLocalEmergencyNumber(dialString, phone.getContext())) {
        if (PhoneNumberUtils.isEmergencyNumber(dialString)) {
            return false;
        } else {
            return isShortCodeUSSD(dialString, phone);
        }
    }

    /**
     * Helper function for isShortCode. Returns true if dialString appears to be
     * a short code and it is a USSD structure
     *
     * According to the 3PGG TS 22.030 specification Figure 3.5.3.2: A 1 or 2
     * digit "short code" is treated as USSD if it is entered while on a call or
     * does not satisfy the condition (exactly 2 digits && starts with '1'), there
     * are however exceptions to this rule (see below)
     *
     * Exception (1) to Call initiation is: If the user of the device is already in a call
     * and enters a Short String without any #-key at the end and the length of the Short String is
     * equal or less then the MAX_LENGTH_SHORT_CODE [constant that is equal to 2]
     *
     * The phone shall initiate a USSD/SS commands.
     *
     * Exception (2) to Call initiation is: If the user of the device enters one
     * Digit followed by the #-key. This rule defines this String as the
     * #-String which is a USSD/SS command.
     *
     * The phone shall initiate a USSD/SS command.
     */
    static private boolean isShortCodeUSSD(String dialString, GSMPhone phone) {
        if (dialString != null) {
            if (phone.isInCall()) {
                // The maximum length of a Short Code (aka Short String) is 2
                if (dialString.length() <= MAX_LENGTH_SHORT_CODE) {
                    return true;
                }
            }

            // The maximum length of a Short Code (aka Short String) is 2
            if (dialString.length() <= MAX_LENGTH_SHORT_CODE) {
                if (dialString.charAt(dialString.length() - 1) == END_OF_USSD_COMMAND) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return true if the Service Code is PIN/PIN2/PUK/PUK2-related
     */
    boolean isPinCommand() {
        return sc != null && (sc.equals(SC_PIN) || sc.equals(SC_PIN2)
                              || sc.equals(SC_PUK) || sc.equals(SC_PUK2));
     }

    /**
     * See TS 22.030 Annex B.
     * In temporary mode, to suppress CLIR for a single call, enter:
     *      " * 31 # [called number] SEND "
     *  In temporary mode, to invoke CLIR for a single call enter:
     *       " # 31 # [called number] SEND "
     */
    boolean
    isTemporaryModeCLIR() {
        return sc != null && sc.equals(SC_CLIR) && dialingNumber != null
                && (isActivate() || isDeactivate());
    }

    /**
     * returns CommandsInterface.CLIR_*
     * See also isTemporaryModeCLIR()
     */
    int
    getCLIRMode() {
        if (sc != null && sc.equals(SC_CLIR)) {
            if (isActivate()) {
                return CommandsInterface.CLIR_SUPPRESSION;
            } else if (isDeactivate()) {
                return CommandsInterface.CLIR_INVOCATION;
            }
        }

        return CommandsInterface.CLIR_DEFAULT;
    }

    boolean isActivate() {
        return action != null && action.equals(ACTION_ACTIVATE);
    }

    boolean isDeactivate() {
        return action != null && action.equals(ACTION_DEACTIVATE);
    }

    boolean isInterrogate() {
        return action != null && action.equals(ACTION_INTERROGATE);
    }

    boolean isRegister() {
        return action != null && action.equals(ACTION_REGISTER);
    }

    boolean isErasure() {
        return action != null && action.equals(ACTION_ERASURE);
    }

    /**
     * Returns true if this is a USSD code that's been submitted to the
     * network...eg, after processCode() is called
     */
    public boolean isPendingUSSD() {
        return isPendingUSSD;
    }

    public boolean isUssdRequest() {
        return isUssdRequest;
    }

    /** Process a MMI code or short code...anything that isn't a dialing number */
    void
    processCode () {
        try {
            if (isShortCode()) {
                Log.d(LOG_TAG, "isShortCode");
                // These just get treated as USSD.
                sendUssd(dialingNumber);
            } else if (dialingNumber != null) {
                // We should have no dialing numbers here
                /// M: [mtk04070][111125][ALPS00093395]MTK modified. @{
                Log.w(LOG_TAG, "Special USSD Support:" + poundString + dialingNumber);
                sendUssd(poundString+dialingNumber);
                //throw new RuntimeException ("Invalid or Unsupported MMI Code");
                /// @}
            } else if (sc != null && sc.equals(SC_CLIP)) {
                Log.d(LOG_TAG, "is CLIP");
                if (isInterrogate()) {
                    phone.mCM.queryCLIP(
                            obtainMessage(EVENT_QUERY_COMPLETE, this));
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
            } else if (sc != null && sc.equals(SC_CLIR)) {
                Log.d(LOG_TAG, "is CLIR");
                if (isActivate()) {
                    phone.mCM.setCLIR(CommandsInterface.CLIR_INVOCATION,
                        obtainMessage(EVENT_SET_COMPLETE, this));
                } else if (isDeactivate()) {
                    phone.mCM.setCLIR(CommandsInterface.CLIR_SUPPRESSION,
                        obtainMessage(EVENT_SET_COMPLETE, this));
                } else if (isInterrogate()) {
                    phone.mCM.getCLIR(
                        obtainMessage(EVENT_GET_CLIR_COMPLETE, this));
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
            /// M: [mtk04070][111125][ALPS00093395]MTK added for COLP and COLR. @{
            } else if (sc != null && sc.equals(SC_COLP)) {
                Log.d(LOG_TAG, "is COLP");
                if (isInterrogate()) {
                    phone.mCM.getCOLP(obtainMessage(EVENT_GET_COLP_COMPLETE, this));
                } 
                // SET COLP as *76# or #76# is not supported.
                //else if (isActivate()) {
                //    phone.mCM.setCOLP(true,obtainMessage(EVENT_SET_COMPLETE, this));
                //} else if (isDeactivate()) {
                //    phone.mCM.setCOLP(false,obtainMessage(EVENT_SET_COMPLETE, this));
                //} 
                else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
            } else if (sc != null && sc.equals(SC_COLR)) {
                Log.d(LOG_TAG, "is COLR");
                if (isInterrogate()) {
                    phone.mCM.getCOLR(obtainMessage(EVENT_GET_COLR_COMPLETE, this));
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
            /// @}
            } else if (isServiceCodeCallForwarding(sc)) {
                Log.d(LOG_TAG, "is CF");

                String dialingNumber = sia;
                int serviceClass = siToServiceClass(sib);
                int reason = scToCallForwardReason(sc);
                int time = siToTime(sic);

                if (isInterrogate()) {
                    phone.mCM.queryCallForwardStatus(
                            reason, serviceClass,  dialingNumber,
                                obtainMessage(EVENT_QUERY_CF_COMPLETE, this));
                } else {
                    int cfAction;

                    if (isActivate()) {
                        cfAction = CommandsInterface.CF_ACTION_ENABLE;
                    } else if (isDeactivate()) {
                        cfAction = CommandsInterface.CF_ACTION_DISABLE;
                    } else if (isRegister()) {
                        cfAction = CommandsInterface.CF_ACTION_REGISTRATION;
                    } else if (isErasure()) {
                        cfAction = CommandsInterface.CF_ACTION_ERASURE;
                    } else {
                        throw new RuntimeException ("invalid action");
                    }

                    int isSettingUnconditionalVoice =
                        (((reason == CommandsInterface.CF_REASON_UNCONDITIONAL) ||
                                (reason == CommandsInterface.CF_REASON_ALL)) &&
                                (((serviceClass & CommandsInterface.SERVICE_CLASS_VOICE) != 0) ||
                                 (serviceClass == CommandsInterface.SERVICE_CLASS_NONE))) ? 1 : 0;

                    int isEnableDesired =
                        ((cfAction == CommandsInterface.CF_ACTION_ENABLE) ||
                                (cfAction == CommandsInterface.CF_ACTION_REGISTRATION)) ? 1 : 0;

                    Log.d(LOG_TAG, "is CF setCallForward");
                    phone.mCM.setCallForward(cfAction, reason, serviceClass,
                            dialingNumber, time, obtainMessage(
                                    EVENT_SET_CFF_COMPLETE,
                                    isSettingUnconditionalVoice,
                                    isEnableDesired, this));
                }
            } else if (isServiceCodeCallBarring(sc)) {
                // sia = password
                // sib = basic service group

                String password = sia;
                int serviceClass = siToServiceClass(sib);
                String facility = scToBarringFacility(sc);

                /// M: [mtk04070][111125][ALPS00093395]MTK modified. @{
                if (isInterrogate()) {
                    if (password == null) {
                    phone.mCM.queryFacilityLock(facility, password,
                            serviceClass, obtainMessage(EVENT_QUERY_COMPLETE, this));
                    } else {
                        throw new RuntimeException ("Invalid or Unsupported MMI Code");
                    }
                } else if (isActivate() || isDeactivate()) {
                    if ((password != null) && (password.length() == 4)) {
                    phone.mCM.setFacilityLock(facility, isActivate(), password,
                            serviceClass, obtainMessage(EVENT_SET_COMPLETE, this));
                } else {
                        handlePasswordError(com.android.internal.R.string.passwordIncorrect);
                    }
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
                /// @}

            } else if (sc != null && sc.equals(SC_PWD)) {
                // sia = fac
                // sib = old pwd
                // sic = new pwd
                // pwd = new pwd
                String facility;
                String oldPwd = sib;
                String newPwd = sic;
                if (isActivate() || isRegister()) {
                    // Even though ACTIVATE is acceptable, this is really termed a REGISTER
                    action = ACTION_REGISTER;

                    if (sia == null) {
                        // If sc was not specified, treat it as BA_ALL.
                        facility = CommandsInterface.CB_FACILITY_BA_ALL;
                    } else {
                        facility = scToBarringFacility(sia);
                    }

                    /// M: Check password in network side. @{
                    if ((oldPwd != null) && (newPwd != null) && (pwd != null)) {
                        if ((pwd.length() != newPwd.length()) || (oldPwd.length() != 4) || (pwd.length() != 4)) {
                             handlePasswordError(com.android.internal.R.string.passwordIncorrect);
                        } else {
                            /* From test spec 51.010-1 31.8.1.2.3 we shall not compare pwd here. Let pwd check in NW side. */
                        phone.mCM.changeBarringPassword(facility, oldPwd,
                                    newPwd, pwd, obtainMessage(EVENT_SET_COMPLETE, this));
                        }
                    } else {
                        // password mismatch; return error
                        handlePasswordError(com.android.internal.R.string.passwordIncorrect);
                    }
                    /// @}
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }

            } else if (sc != null && sc.equals(SC_WAIT)) {
                // sia = basic service group
                int serviceClass = siToServiceClass(sia);

                if (isActivate() || isDeactivate()) {
                    phone.mCM.setCallWaiting(isActivate(), serviceClass,
                            obtainMessage(EVENT_SET_COMPLETE, this));
                } else if (isInterrogate()) {
                    phone.mCM.queryCallWaiting(serviceClass,
                            obtainMessage(EVENT_QUERY_COMPLETE, this));
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
            } else if (isPinCommand()) {
               /// M: [mtk04070][111125][ALPS00093395]MTK modified. @{
               Log.d(LOG_TAG, "is PIN command");

                // sia = old PIN or PUK
                // sib = new PIN
                // sic = new PIN
                String oldPinOrPuk = sia;
                String newPin = sib;
                // int pinLen = newPin.length();
                // int oldPinLen = oldPinOrPuk.length();
                String retryPin1;
                String retryPin2;
                String retryPuk1;
                String retryPuk2;

                if (phone.getMySimId() == PhoneConstants.GEMINI_SIM_2) {
                    retryPin1 = SystemProperties.get(PROPERTY_RIL_SIM2_PIN1);
                    retryPin2 = SystemProperties.get(PROPERTY_RIL_SIM2_PIN2);
                    retryPuk1 = SystemProperties.get(PROPERTY_RIL_SIM2_PUK1);
                    retryPuk2 = SystemProperties.get(PROPERTY_RIL_SIM2_PUK2);
                } else {
                    retryPin1 = SystemProperties.get(PROPERTY_RIL_SIM_PIN1);
                    retryPin2 = SystemProperties.get(PROPERTY_RIL_SIM_PIN2);
                    retryPuk1 = SystemProperties.get(PROPERTY_RIL_SIM_PUK1);
                    retryPuk2 = SystemProperties.get(PROPERTY_RIL_SIM_PUK2);
                }
                

                Log.d(LOG_TAG,"retryPin1:" + retryPin1 + "\n"
                                          + "retryPin2:" + retryPin2 + "\n"
                                          + "retryPuk1:" + retryPuk1 + "\n"
                                          + "retryPuk2:" + retryPuk2 + "\n");

                if (isRegister()) {
                    if (newPin == null || oldPinOrPuk == null) {
                        handlePasswordError(com.android.internal.R.string.mmiError);
                        return;
                    }

                int pinLen = newPin.length();
                    int oldPinLen = oldPinOrPuk.length();

                /// M: Solve [ALPS00437074][MT6589][JB2][in-house FTA][SIM] 27.14.2 FTA fail due to 
                ///    press "**04*" to change PIN code the ME popup invalid MMI code. @{
                Phone currentPhone;
                if (FeatureOption.MTK_GEMINI_SUPPORT) {
                   currentPhone = ((GeminiPhone)(PhoneFactory.getDefaultPhone())).getPhonebyId(phone.getMySimId());
                } else {
                   currentPhone = PhoneFactory.getDefaultPhone();
                }

                IccCard iccCard = currentPhone.getIccCard();
                /// @}

                    if (!iccCard.hasIccCard()){
                        handlePasswordError(com.android.internal.R.string.mmiError);
                    } else if (!newPin.equals(sic)) {
                        // password mismatch; return error
                        handlePasswordError(com.android.internal.R.string.mismatchPin);
                    } else if ((sc.equals(SC_PIN) || sc.equals(SC_PIN2))
                               && ((pinLen < 4) || (pinLen > 8) || (oldPinLen < 4) || (oldPinLen > 8))) {
                        // invalid length
                        handlePasswordError(com.mediatek.R.string.checkPwdLen);
                    } else if ((sc.equals(SC_PUK) || sc.equals(SC_PUK2))
                               && (pinLen < 4) || (pinLen > 8)) {
                        handlePasswordError(com.mediatek.R.string.checkPwdLen);
                    } else if (sc.equals(SC_PIN) &&
                               mUiccApplication != null &&
                               mUiccApplication.getState() == AppState.APPSTATE_PUK ) {
                        // Sim is puk-locked
                        handlePasswordError(com.android.internal.R.string.needPuk);
                    } else if (sc.equals(SC_PIN) &&
                               (false == iccCard.getIccLockEnabled())) {
                        // PIN is not enabled. PIN cannot be changed.       
                        handlePasswordError(com.mediatek.R.string.pinNotEnabled);
                    } else if (!isValidPin(newPin)) {
                        handlePasswordError(com.android.internal.R.string.mmiError);
                    } else {
                        // pre-checks OK
                        if (sc.equals(SC_PIN)) {
                            if (retryPin1.equals(RETRY_BLOCKED)) {
                                // PIN1 is in PUK state.
                                handlePasswordError(com.android.internal.R.string.needPuk);
                            } else {
                                //
                                //phone.mCM.changeIccPin(oldPinOrPuk, newPin,
                                //    obtainMessage(EVENT_SET_COMPLETE, this));  
                                // Use SimCard provided interfaces.
                                iccCard.changeIccLockPassword(oldPinOrPuk, newPin,
                                    obtainMessage(EVENT_SET_COMPLETE, this));
                            }
                        } else if (sc.equals(SC_PIN2)) {
                            if (retryPin2.equals(RETRY_BLOCKED)) {
                                // PIN2 is in PUK state.
                                handlePasswordError(com.android.internal.R.string.needPuk2);
                            } else {
                                //
                                //phone.mCM.changeIccPin2(oldPinOrPuk, newPin,
                                //    obtainMessage(EVENT_SET_COMPLETE, this));
                                // Use SimCard provided interfaces.                                
                                iccCard.changeIccFdnPassword(oldPinOrPuk,newPin,
                                    obtainMessage(EVENT_SET_COMPLETE, this));
                            }
                        } else if (sc.equals(SC_PUK)) {
                            if (retryPuk1.equals(RETRY_BLOCKED)) {
                                // PIN1 Dead
                                handlePasswordError(com.mediatek.R.string.puk1Blocked);
                            } else {
                                //
                                //phone.mCM.supplyIccPuk(oldPinOrPuk, newPin,
                                //    obtainMessage(EVENT_SET_COMPLETE, this));
                                // Use SimCard provided interfaces.  
                                if (oldPinOrPuk.length() == 8) {
                                    iccCard.supplyPuk(oldPinOrPuk,newPin,
                                    obtainMessage(EVENT_SET_COMPLETE, this));
                                } else {
                                    handlePasswordError(com.mediatek.R.string.invalidPuk);
                                }
                            }
                        } else if (sc.equals(SC_PUK2)) {
                            if (retryPuk2.equals(RETRY_BLOCKED)) {
                                // PIN2 Dead                                
                                handlePasswordError(com.mediatek.R.string.puk2Blocked);
                            } else {
                                //
                                //phone.mCM.supplyIccPuk2(oldPinOrPuk, newPin,
                                //    obtainMessage(EVENT_SET_COMPLETE, this));
                                if (oldPinOrPuk.length() == 8) {
                                    iccCard.supplyPuk2(oldPinOrPuk, newPin, 
                                    obtainMessage(EVENT_SET_COMPLETE, this));
                                } else {
                                    handlePasswordError(com.mediatek.R.string.invalidPuk);
                                }
                            }
                        }
                    }
                } else {
                    throw new RuntimeException ("Invalid or Unsupported MMI Code");
                }
               /// @}
            } else if (poundString != null) {
                sendUssd(poundString);
            } else {
                throw new RuntimeException ("Invalid or Unsupported MMI Code");
            }
        } catch (RuntimeException exc) {
            state = State.FAILED;
            message = context.getText(com.android.internal.R.string.mmiError);
            phone.onMMIDone(this);
        }
    }

    private void handlePasswordError(int res) {
        state = State.FAILED;
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");
        sb.append(context.getText(res));
        message = sb;
        phone.onMMIDone(this);
    }

    /**
     * Called from GSMPhone
     *
     * An unsolicited USSD NOTIFY or REQUEST has come in matching
     * up with this pending USSD request
     *
     * Note: If REQUEST, this exchange is complete, but the session remains
     *       active (ie, the network expects user input).
     */
    void
    onUssdFinished(String ussdMessage, boolean isUssdRequest) {
        if (state == State.PENDING) {
            /// M: [mtk04070][111125][ALPS00093395]Check the length of ussdMessage.
            if (ussdMessage == null || ussdMessage.length() == 0) {
                message = context.getText(com.android.internal.R.string.mmiComplete);
            } else {
                message = ussdMessage;
            }
            this.isUssdRequest = isUssdRequest;
            // If it's a request, leave it PENDING so that it's cancelable.
            if (!isUssdRequest) {
                state = State.COMPLETE;
            }

            phone.onMMIDone(this);
        }
    }

    /**
     * Called from GSMPhone
     *
     * The radio has reset, and this is still pending
     */

    void
    onUssdFinishedError() {
        if (state == State.PENDING) {
            state = State.FAILED;
            message = context.getText(com.android.internal.R.string.mmiError);

            phone.onMMIDone(this);
        }
    }

    void sendUssd(String ussdMessage) {
        // Treat this as a USSD string
        isPendingUSSD = true;

        // Note that unlike most everything else, the USSD complete
        // response does not complete this MMI code...we wait for
        // an unsolicited USSD "Notify" or "Request".
        // The matching up of this is done in GSMPhone.

        phone.mCM.sendUSSD(ussdMessage,
            obtainMessage(EVENT_USSD_COMPLETE, this));
    }

    /** Called from GSMPhone.handleMessage; not a Handler subclass */
    public void
    handleMessage (Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            case EVENT_SET_COMPLETE:
                ar = (AsyncResult) (msg.obj);

                onSetComplete(ar);
                break;

            case EVENT_SET_CFF_COMPLETE:
                ar = (AsyncResult) (msg.obj);

                /*
                * msg.arg1 = 1 means to set unconditional voice call forwarding
                * msg.arg2 = 1 means to enable voice call forwarding
                */
                if ((ar.exception == null) && (msg.arg1 == 1)) {
                    boolean cffEnabled = (msg.arg2 == 1);
                    if (mIccRecords != null) {
                        mIccRecords.setVoiceCallForwardingFlag(1, cffEnabled);
                    }
                }

                onSetComplete(ar);
                break;

            case EVENT_GET_CLIR_COMPLETE:
                ar = (AsyncResult) (msg.obj);
                onGetClirComplete(ar);
            break;

            /// M: [mtk04070][111125][ALPS00093395]MTK added for COLP and COLR. @{
            case EVENT_GET_COLP_COMPLETE:
                ar = (AsyncResult) (msg.obj);
                onGetColpComplete(ar);
            break;

            case EVENT_GET_COLR_COMPLETE:
                ar = (AsyncResult) (msg.obj);
                onGetColrComplete(ar);
            break;
            /// @}
            
            case EVENT_QUERY_CF_COMPLETE:
                ar = (AsyncResult) (msg.obj);
                onQueryCfComplete(ar);
            break;

            case EVENT_QUERY_COMPLETE:
                ar = (AsyncResult) (msg.obj);
                onQueryComplete(ar);
            break;

            case EVENT_USSD_COMPLETE:
                ar = (AsyncResult) (msg.obj);

                if (ar.exception != null) {
                    state = State.FAILED;
                    message = getErrorMessage(ar);

                    phone.onMMIDone(this);
                }

                // Note that unlike most everything else, the USSD complete
                // response does not complete this MMI code...we wait for
                // an unsolicited USSD "Notify" or "Request".
                // The matching up of this is done in GSMPhone.

            break;

            case EVENT_USSD_CANCEL_COMPLETE:
                phone.onMMIDone(this);
            break;
        }
    }
    //***** Private instance methods

    private CharSequence getErrorMessage(AsyncResult ar) {

        if (ar.exception instanceof CommandException) {
            CommandException.Error err = ((CommandException)(ar.exception)).getCommandError();
            if (err == CommandException.Error.FDN_CHECK_FAILURE) {
                Log.i(LOG_TAG, "FDN_CHECK_FAILURE");
                return context.getText(com.android.internal.R.string.mmiFdnError);
            }
        }

        return context.getText(com.android.internal.R.string.mmiError);
    }

    private CharSequence getScString() {
        if (sc != null) {
            if (isServiceCodeCallBarring(sc)) {
                return context.getText(com.android.internal.R.string.BaMmi);
            } else if (isServiceCodeCallForwarding(sc)) {
                return context.getText(com.android.internal.R.string.CfMmi);
            } else if (sc.equals(SC_CLIP)) {
                return context.getText(com.android.internal.R.string.ClipMmi);
            } else if (sc.equals(SC_CLIR)) {
                return context.getText(com.android.internal.R.string.ClirMmi);
            } else if (sc.equals(SC_PWD)) {
                return context.getText(com.android.internal.R.string.PwdMmi);
            } else if (sc.equals(SC_WAIT)) {
                return context.getText(com.android.internal.R.string.CwMmi);
            /// M: [mtk04070][111125][ALPS00093395]MTK added/modified. @{
            } else if (sc.equals(SC_PIN)){
                return context.getText(com.android.internal.R.string.PinMmi);
            } else if (sc.equals(SC_PIN2)){
            	return context.getText(com.mediatek.R.string.Pin2Mmi);
            } else if (sc.equals(SC_PUK)){
            	return context.getText(com.mediatek.R.string.PukMmi);
            } else if (sc.equals(SC_PUK2)){
            	return context.getText(com.mediatek.R.string.Puk2Mmi);
            }
            /// @}
        }

        return "";
    }

    private void
    onSetComplete(AsyncResult ar){
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            state = State.FAILED;
            if (ar.exception instanceof CommandException) {
                CommandException.Error err = ((CommandException)(ar.exception)).getCommandError();
                if (err == CommandException.Error.PASSWORD_INCORRECT) {
                    if (isPinCommand()) {
                        /// M: [mtk04070][111125][ALPS00093395]MTK modified. @{
                        // look specifically for the PUK commands and adjust
                        // the message accordingly.
                        if (sc.equals(SC_PUK)) {
                            sb.append(context.getText(
                                    com.android.internal.R.string.badPuk));
                        } else if(sc.equals(SC_PUK2)) {
                            sb.append(context.getText(
                                    com.mediatek.R.string.badPuk2));
                        } else if(sc.equals(SC_PIN)) {
                            sb.append(context.getText(
                                    com.android.internal.R.string.badPin));
                        } else if(sc.equals(SC_PIN2)) {
                            sb.append(context.getText(
                                    com.mediatek.R.string.badPin2));     
                        }
                        /// @}
                    } else {
                        sb.append(context.getText(
                                com.android.internal.R.string.passwordIncorrect));
                    }
                } else if (err == CommandException.Error.SIM_PUK2) {
                    /// M: [mtk04070][111125][ALPS00093395]MTK modified.
                    sb.append(context.getText(com.mediatek.R.string.badPin2));
                    sb.append("\n");
                    sb.append(context.getText(
                            com.android.internal.R.string.needPuk2));
                /// M: [mtk04070][111125][ALPS00093395]MTK added for call barred. @{
                } else if (err == CommandException.Error.CALL_BARRED) {
                    sb.append(context.getText(com.mediatek.R.string.callBarringFailMmi));
                /// @}
                } else if (err == CommandException.Error.FDN_CHECK_FAILURE) {
                    Log.i(LOG_TAG, "FDN_CHECK_FAILURE");
                    sb.append(context.getText(com.android.internal.R.string.mmiFdnError));
                } else {
                    sb.append(context.getText(
                            com.android.internal.R.string.mmiError));
                }
            } else {
                sb.append(context.getText(
                        com.android.internal.R.string.mmiError));
            }
        } else if (isActivate()) {
            state = State.COMPLETE;
            sb.append(context.getText(
                    com.android.internal.R.string.serviceEnabled));
            // Record CLIR setting
            if (sc.equals(SC_CLIR)) {
                phone.saveClirSetting(CommandsInterface.CLIR_INVOCATION);
            }
        } else if (isDeactivate()) {
            state = State.COMPLETE;
            sb.append(context.getText(
                    com.android.internal.R.string.serviceDisabled));
            // Record CLIR setting
            if (sc.equals(SC_CLIR)) {
                phone.saveClirSetting(CommandsInterface.CLIR_SUPPRESSION);
            }
        } else if (isRegister()) {
            state = State.COMPLETE;
            sb.append(context.getText(
                    com.android.internal.R.string.serviceRegistered));
        } else if (isErasure()) {
            state = State.COMPLETE;
            sb.append(context.getText(
                    com.android.internal.R.string.serviceErased));
        } else {
            state = State.FAILED;
            sb.append(context.getText(
                    com.android.internal.R.string.mmiError));
        }

        message = sb;
        phone.onMMIDone(this);
    }

    private void
    onGetClirComplete(AsyncResult ar) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            state = State.FAILED;
            /// M: [mtk04070][111125][ALPS00093395]MTK modified. @{
            //sb.append(getErrorMessage(ar));
            if (ar.exception instanceof CommandException) {
                CommandException.Error err = ((CommandException)(ar.exception)).getCommandError();
                if (err == CommandException.Error.CALL_BARRED) {
                    sb.append(context.getText(com.mediatek.R.string.callBarringFailMmi));
                } else if (err == CommandException.Error.FDN_CHECK_FAILURE) {
                    sb.append(context.getText(com.mediatek.R.string.fdnFailMmi));
                } else {
                    sb.append(context.getText(com.android.internal.R.string.mmiError));
                }
            } else {
                sb.append(context.getText(com.android.internal.R.string.mmiError));
            }
            /// @}
        } else {
            int clirArgs[];

            clirArgs = (int[])ar.result;

            // the 'm' parameter from TS 27.007 7.7
            switch (clirArgs[1]) {
                case 0: // CLIR not provisioned
                    sb.append(context.getText(
                                com.android.internal.R.string.serviceNotProvisioned));
                    state = State.COMPLETE;
                break;

                case 1: // CLIR provisioned in permanent mode
                    sb.append(context.getText(
                                com.android.internal.R.string.CLIRPermanent));
                    state = State.COMPLETE;
                break;

                case 2: // unknown (e.g. no network, etc.)
                    sb.append(context.getText(
                                com.android.internal.R.string.mmiError));
                    state = State.FAILED;
                break;

                case 3: // CLIR temporary mode presentation restricted

                    // the 'n' parameter from TS 27.007 7.7
                    switch (clirArgs[0]) {
                        default:
                        case 0: // Default
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOnNextCallOn));
                        break;
                        case 1: // CLIR invocation
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOnNextCallOn));
                        break;
                        case 2: // CLIR suppression
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOnNextCallOff));
                        break;
                    }
                    state = State.COMPLETE;
                break;

                case 4: // CLIR temporary mode presentation allowed
                    // the 'n' parameter from TS 27.007 7.7
                    switch (clirArgs[0]) {
                        default:
                        case 0: // Default
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOffNextCallOff));
                        break;
                        case 1: // CLIR invocation
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOffNextCallOn));
                        break;
                        case 2: // CLIR suppression
                            sb.append(context.getText(
                                    com.android.internal.R.string.CLIRDefaultOffNextCallOff));
                        break;
                    }

                    state = State.COMPLETE;
                break;
            }
        }

        message = sb;
        phone.onMMIDone(this);
    }

    /**
     * @param serviceClass 1 bit of the service class bit vectory
     * @return String to be used for call forward query MMI response text.
     *        Returns null if unrecognized
     */

    private CharSequence
    serviceClassToCFString (int serviceClass) {
        switch (serviceClass) {
            case SERVICE_CLASS_VOICE:
                return context.getText(com.android.internal.R.string.serviceClassVoice);
            case SERVICE_CLASS_DATA:
                return context.getText(com.android.internal.R.string.serviceClassData);
            case SERVICE_CLASS_FAX:
                return context.getText(com.android.internal.R.string.serviceClassFAX);
            case SERVICE_CLASS_SMS:
                return context.getText(com.android.internal.R.string.serviceClassSMS);
            case SERVICE_CLASS_DATA_SYNC:
                return context.getText(com.android.internal.R.string.serviceClassDataSync);
            case SERVICE_CLASS_DATA_ASYNC:
                return context.getText(com.android.internal.R.string.serviceClassDataAsync);
            case SERVICE_CLASS_PACKET:
                return context.getText(com.android.internal.R.string.serviceClassPacket);
            case SERVICE_CLASS_PAD:
                return context.getText(com.android.internal.R.string.serviceClassPAD);
            /// M: [mtk04070][111125][ALPS00093395]MTK added for line2 and video call. @{
            case SERVICE_CLASS_LINE2:
            case SERVICE_CLASS_VIDEO:
                return context.getText(com.mediatek.R.string.serviceClassVideo);
            /// @}
            default:
                return null;
        }
    }


    /** one CallForwardInfo + serviceClassMask -> one line of text */
    private CharSequence
    makeCFQueryResultMessage(CallForwardInfo info, int serviceClassMask) {
        CharSequence template;
        String sources[] = {"{0}", "{1}", "{2}"};
        CharSequence destinations[] = new CharSequence[3];
        boolean needTimeTemplate;

        // CF_REASON_NO_REPLY also has a time value associated with
        // it. All others don't.

        needTimeTemplate =
            (info.reason == CommandsInterface.CF_REASON_NO_REPLY);

        /// M: [mtk04070][111125][ALPS00093395]Also check if info.number is not empty or null. @{
        if ((info.status == 1) && !isEmptyOrNull(info.number)) {
            /* Number cannot be NULL when status is activated */
            if (needTimeTemplate) {
                template = context.getText(
                        com.android.internal.R.string.cfTemplateForwardedTime);
            } else {
                template = context.getText(
                        com.android.internal.R.string.cfTemplateForwarded);
            }
        } else if (isEmptyOrNull(info.number)) {
        /// @}
            template = context.getText(
                        com.android.internal.R.string.cfTemplateNotForwarded);
        } else { /* (info.status == 0) && !isEmptyOrNull(info.number) */
            // A call forward record that is not active but contains
            // a phone number is considered "registered"

            if (needTimeTemplate) {
                template = context.getText(
                        com.android.internal.R.string.cfTemplateRegisteredTime);
            } else {
                template = context.getText(
                        com.android.internal.R.string.cfTemplateRegistered);
            }
        }

        // In the template (from strings.xmls)
        //         {0} is one of "bearerServiceCode*"
        //        {1} is dialing number
        //      {2} is time in seconds

        destinations[0] = serviceClassToCFString(info.serviceClass & serviceClassMask);
        destinations[1] = PhoneNumberUtils.stringFromStringAndTOA(info.number, info.toa);
        destinations[2] = Integer.toString(info.timeSeconds);

        if (info.reason == CommandsInterface.CF_REASON_UNCONDITIONAL &&
                (info.serviceClass & serviceClassMask)
                        == CommandsInterface.SERVICE_CLASS_VOICE) {
            boolean cffEnabled = (info.status == 1);
            if (mIccRecords != null) {
                mIccRecords.setVoiceCallForwardingFlag(1, cffEnabled);
            }
        }

        return TextUtils.replace(template, sources, destinations);
    }


    private void
    onQueryCfComplete(AsyncResult ar) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            state = State.FAILED;
            /// M: [mtk04070][111125][ALPS00093395]MTK modified. @{
            //sb.append(getErrorMessage(ar));
            if (ar.exception instanceof CommandException) {
                CommandException.Error err = ((CommandException)(ar.exception)).getCommandError();
                if (err == CommandException.Error.CALL_BARRED) {
                    sb.append(context.getText(com.mediatek.R.string.callBarringFailMmi));
                } else if (err == CommandException.Error.FDN_CHECK_FAILURE) {
                    sb.append(context.getText(com.mediatek.R.string.fdnFailMmi));
                } else {
                    sb.append(context.getText(com.android.internal.R.string.mmiError));
                }
            } else {
                sb.append(context.getText(com.android.internal.R.string.mmiError));
            }
            /// @}
        } else {
            CallForwardInfo infos[];

            infos = (CallForwardInfo[]) ar.result;

            if (infos.length == 0) {
                // Assume the default is not active
                sb.append(context.getText(com.android.internal.R.string.serviceDisabled));

                // Set unconditional CFF in SIM to false
                if (mIccRecords != null) {
                    mIccRecords.setVoiceCallForwardingFlag(1, false);
                }
            } else {

                SpannableStringBuilder tb = new SpannableStringBuilder();

                // Each bit in the service class gets its own result line
                // The service classes may be split up over multiple
                // CallForwardInfos. So, for each service class, find out
                // which CallForwardInfo represents it and then build
                // the response text based on that
                /// M: [mtk04070][111125][ALPS00093395]MTK added. @{
                boolean isAllCfDisabled = false;
                for (int i = 0, s = infos.length; i < s ; i++) {
                    if (infos[i].serviceClass == SERVICE_CLASS_VOICE
                                               + SERVICE_CLASS_FAX
                                               + SERVICE_CLASS_SMS
                                               + SERVICE_CLASS_DATA_SYNC
                                               + SERVICE_CLASS_DATA_ASYNC) {
                        isAllCfDisabled = true;
                        break;
                    }
                }
                Log.d(LOG_TAG, "[GsmMmiCode] isAllCfDisabled = " + isAllCfDisabled);
                /// @}

                for (int serviceClassMask = 1
                            ; serviceClassMask <= SERVICE_CLASS_MAX
                            ; serviceClassMask <<= 1
                ) {
                    /// M: [mtk04070][111125][ALPS00093395]MTK added and modified. @{
                    if (serviceClassMask == SERVICE_CLASS_LINE2) continue;

                    if (isAllCfDisabled) {
                        if (serviceClassToCFString(serviceClassMask) != null) {
                            String getServiceName = serviceClassToCFString(serviceClassMask).toString();
                            if(getServiceName != null)
                            {
                                    sb.append(getServiceName);
                                    sb.append(" : ");
                                    sb.append(context.getText(com.mediatek.R.string.cfServiceNotForwarded));
                                    sb.append("\n");
                            }
                        } else {
                            Log.e(LOG_TAG, "[GsmMmiCode] " + serviceClassMask + " service returns null");
                        }
                    } else {
                    for (int i = 0, s = infos.length; i < s ; i++) {
                        if ((serviceClassMask & infos[i].serviceClass) != 0) {
                              if (infos[i].status == 1) {
                            tb.append(makeCFQueryResultMessage(infos[i],
                                            serviceClassMask));
                            tb.append("\n");
                              } else {
                                    String getServiceName1 = serviceClassToCFString(serviceClassMask).toString();
                                    if (getServiceName1 != null) {
                                       sb.append(getServiceName1);
                                       sb.append(" : ");
                                       sb.append(context.getText(com.mediatek.R.string.cfServiceNotForwarded));
                                       sb.append("\n");
                                    }
                              }
                        }
                    }
                }
                    /// @}
                }
                sb.append(tb);
            }

            state = State.COMPLETE;
        }

        message = sb;
        phone.onMMIDone(this);

    }

    private void
    onQueryComplete(AsyncResult ar) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            state = State.FAILED;
            /// M: [mtk04070][111125][ALPS00093395]MTK modified. @{
            //sb.append(getErrorMessage(ar));
            if (ar.exception instanceof CommandException) {
                CommandException.Error err = ((CommandException)(ar.exception)).getCommandError();
                if (err == CommandException.Error.CALL_BARRED) {
                    sb.append(context.getText(com.mediatek.R.string.callBarringFailMmi));
                } else if (err == CommandException.Error.FDN_CHECK_FAILURE) {
                    sb.append(context.getText(com.mediatek.R.string.fdnFailMmi));
                } else {
                    sb.append(context.getText(com.android.internal.R.string.mmiError));
                }
            } else {
                sb.append(context.getText(com.android.internal.R.string.mmiError));
            }
            /// @}
        } else {
            int[] ints = (int[])ar.result;

            if (ints.length != 0) {
                if (ints[0] == 0) {
                    sb.append(context.getText(com.android.internal.R.string.serviceDisabled));
                } else if (sc.equals(SC_WAIT)) {
                    // Call Waiting includes additional data in the response.
                    sb.append(createQueryCallWaitingResultMessage(ints[1]));
                } else if (isServiceCodeCallBarring(sc)) {
                    // ints[0] for Call Barring is a bit vector of services
                    sb.append(createQueryCallBarringResultMessage(ints[0]));
                } else if (ints[0] == 1) {
                    // for all other services, treat it as a boolean
                    sb.append(context.getText(com.android.internal.R.string.serviceEnabled));
                } else {
                    sb.append(context.getText(com.android.internal.R.string.mmiError));
                }
            } else {
                sb.append(context.getText(com.android.internal.R.string.mmiError));
            }
            state = State.COMPLETE;
        }

        message = sb;
        phone.onMMIDone(this);
    }

    private CharSequence
    createQueryCallWaitingResultMessage(int serviceClass) {
        StringBuilder sb =
                new StringBuilder(context.getText(com.android.internal.R.string.serviceEnabledFor));

        for (int classMask = 1
                    ; classMask <= SERVICE_CLASS_MAX
                    ; classMask <<= 1
        ) {
            if ((classMask & serviceClass) != 0) {
                sb.append("\n");
                sb.append(serviceClassToCFString(classMask & serviceClass));
            }
        }
        return sb;
    }
    private CharSequence
    createQueryCallBarringResultMessage(int serviceClass)
    {
        StringBuilder sb = new StringBuilder(context.getText(com.android.internal.R.string.serviceEnabledFor));

        for (int classMask = 1
                    ; classMask <= SERVICE_CLASS_MAX
                    ; classMask <<= 1
        ) {
            if ((classMask & serviceClass) != 0) {
                sb.append("\n");
                sb.append(serviceClassToCFString(classMask & serviceClass));
            }
        }
        return sb;
    }

    /***
     * TODO: It would be nice to have a method here that can take in a dialstring and
     * figure out if there is an MMI code embedded within it.  This code would replace
     * some of the string parsing functionality in the Phone App's
     * SpecialCharSequenceMgr class.
     */


    /// M: [mtk04070][111125][ALPS00093395]MTK proprietary methods. @{
    static GsmMmiCode
    newNetworkInitiatedUssdError (String ussdMessage,
                                boolean isUssdRequest, GSMPhone phone, UiccCardApplication app) {
        GsmMmiCode ret;

        ret = new GsmMmiCode(phone, app);

        ret.message = ret.context.getText(com.android.internal.R.string.mmiError);
        ret.isUssdRequest = isUssdRequest;

        ret.state = State.FAILED;

        return ret;
    }

    private boolean isValidPin(String address){
        for (int i = 0, count = address.length(); i < count; i++) {
            if (address.charAt(i) < '0' || address.charAt(i) > '9')
                return false;
        }
        return true;
    }
    
    private void
    onGetColrComplete(AsyncResult ar) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            state = State.FAILED;
            if (ar.exception instanceof CommandException) {
                CommandException.Error err = ((CommandException)(ar.exception)).getCommandError();
                if (err == CommandException.Error.CALL_BARRED) {
                    sb.append(context.getText(com.mediatek.R.string.callBarringFailMmi));
                } else if (err == CommandException.Error.FDN_CHECK_FAILURE) {
                    sb.append(context.getText(com.mediatek.R.string.fdnFailMmi));
                } else {
                    sb.append(context.getText(com.android.internal.R.string.mmiError));
    }
            } else {
                sb.append(context.getText(com.android.internal.R.string.mmiError));
            }
        } else {
            int colrArgs[];

            colrArgs = (int[])ar.result;

            // the 'm' parameter from mtk proprietary
            switch (colrArgs[0]) {
                case 0: // COLR not provisioned
                    sb.append(context.getText(
                                com.android.internal.R.string.serviceNotProvisioned));
                    state = State.COMPLETE;
                break;

                case 1: // COLR provisioned
                    sb.append(context.getText(
                                com.mediatek.R.string.serviceProvisioned));
                    state = State.COMPLETE;
                break;

                case 2: // unknown (e.g. no network, etc.)
                    sb.append(context.getText(
                                com.android.internal.R.string.mmiError));
                    state = State.FAILED;
                break;

            }
        }

        message = sb;
        phone.onMMIDone(this);
    }
    
    private void
    onGetColpComplete(AsyncResult ar) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            state = State.FAILED;
            if (ar.exception instanceof CommandException) {
                CommandException.Error err = ((CommandException)(ar.exception)).getCommandError();
                if (err == CommandException.Error.CALL_BARRED) {
                    sb.append(context.getText(com.mediatek.R.string.callBarringFailMmi));
                } else if (err == CommandException.Error.FDN_CHECK_FAILURE) {
                    sb.append(context.getText(com.mediatek.R.string.fdnFailMmi));
                } else {
                    sb.append(context.getText(com.android.internal.R.string.mmiError));
                }
            } else {
                sb.append(context.getText(com.android.internal.R.string.mmiError));
            }
        } else {
            int colpArgs[];

            colpArgs = (int[])ar.result;

            // the 'm' parameter from TS 27.007 7.8
            switch (colpArgs[1]) {
                case 0: // COLP not provisioned
                    sb.append(context.getText(
                                com.android.internal.R.string.serviceNotProvisioned));
                    state = State.COMPLETE;
                break;

                case 1: // COLP provisioned
                    sb.append(context.getText(
                                com.mediatek.R.string.serviceProvisioned));
                    state = State.COMPLETE;
                break;

                case 2: // unknown (e.g. no network, etc.)
                    sb.append(context.getText(
                                com.mediatek.R.string.serviceUnknown));
                    state = State.COMPLETE;
                break;
            }
        }

        message = sb;
        phone.onMMIDone(this);
    }
    
    /// @}
}
