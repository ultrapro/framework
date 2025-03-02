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


import com.android.internal.telephony.DataCallState.SetupResult;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import android.app.PendingIntent;
import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.ProxyProperties;
import android.net.NetworkUtils;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.TimeUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@hide}
 *
 * DataConnection StateMachine.
 *
 * This is an abstract base class for representing a single data connection.
 * Instances of this class such as <code>CdmaDataConnection</code> and
 * <code>GsmDataConnection</code>, * represent a connection via the cellular network.
 * There may be multiple data connections and all of them are managed by the
 * <code>DataConnectionTracker</code>.
 *
 * Instances are asynchronous state machines and have two primary entry points
 * <code>connect()</code> and <code>disconnect</code>. The message a parameter will be returned
 * hen the operation completes. The <code>msg.obj</code> will contain an AsyncResult
 * object and <code>AsyncResult.userObj</code> is the original <code>msg.obj</code>. if successful
 * with the <code>AsyncResult.result == null</code> and <code>AsyncResult.exception == null</code>.
 * If an error <code>AsyncResult.result = FailCause</code> and
 * <code>AsyncResult.exception = new Exception()</code>.
 *
 * The other public methods are provided for debugging.
 */
public abstract class DataConnection extends StateMachine {
    protected static final boolean DBG = true;
    protected static final boolean VDBG = false;

    protected static AtomicInteger mCount = new AtomicInteger(0);
    protected AsyncChannel mAc;

    protected List<ApnContext> mApnList = null;
    PendingIntent mReconnectIntent = null;

    private DataConnectionTracker mDataConnectionTracker = null;

    /**
     * Used internally for saving connecting parameters.
     */
    protected static class ConnectionParams {
        public ConnectionParams(ApnSetting apn, Message onCompletedMsg) {
            this.apn = apn;
            this.onCompletedMsg = onCompletedMsg;
        }

        public int tag;
        public ApnSetting apn;
        public Message onCompletedMsg;
    }

    /**
     * Used internally for saving disconnecting parameters.
     */
    protected static class DisconnectParams {
        public DisconnectParams(String reason, Message onCompletedMsg) {
            this.reason = reason;
            this.onCompletedMsg = onCompletedMsg;
        }
        public int tag;
        public String reason;
        public Message onCompletedMsg;
    }

    /**
     * Returned as the reason for a connection failure as defined
     * by RIL_DataCallFailCause in ril.h and some local errors.
     */
    public enum FailCause {
        NONE(0),

        // This series of errors as specified by the standards
        // specified in ril.h
        OPERATOR_BARRED(0x08),
        MBMS_CAPABILITIES_INSUFFICIENT(0x18),
        LLC_SNDCP_FAILURE(0x19),        
        INSUFFICIENT_RESOURCES(0x1A),
        MISSING_UNKNOWN_APN(0x1B),
        UNKNOWN_PDP_ADDRESS_TYPE(0x1C),
        USER_AUTHENTICATION(0x1D),
        ACTIVATION_REJECT_GGSN(0x1E),
        ACTIVATION_REJECT_UNSPECIFIED(0x1F),
        SERVICE_OPTION_NOT_SUPPORTED(0x20),
        SERVICE_OPTION_NOT_SUBSCRIBED(0x21),
        SERVICE_OPTION_OUT_OF_ORDER(0x22),
        NSAPI_IN_USE(0x23),
        REGULAR_DEACTIVATION(0x24),
        QOS_NOT_ACCEPTED(0x25),
        NETWORK_FAILURE(0x26),
        REACTIVATION_REQUESTED(0x27),
        FEATURE_NOT_SUPPORTED(0x28),
        SEMANTIC_ERROR_IN_TFT(0x29),
        SYNTACTICAL_ERROR_IN_TFT(0x2A),
        UNKNOWN_PDP_CONTEXT(0x2B),
        SEMANTIC_ERROR_IN_PACKET_FILTER(0x2C),
        SYNTACTICAL_ERROR_IN_PACKET_FILTER(0x2D),
        PDP_CONTEXT_WITHOU_TFT_ALREADY_ACTIVATED(0x2E),
        MULTICAST_GROUP_MEMBERSHIP_TIMEOUT(0x2F),
        BCM_VIOLATION(0x30),
        ONLY_IPV4_ALLOWED(0x32),
        ONLY_IPV6_ALLOWED(0x33),
        ONLY_SINGLE_BEARER_ALLOWED(0x34),
        COLLISION_WITH_NW_INITIATED_REQUEST(0x38),
        BEARER_HANDLING_NOT_SUPPORT(0x3C),
        MAX_PDP_NUMBER_REACHED(0x41),
        APN_NOT_SUPPORT_IN_RAT_PLMN(0x42),
        INVALID_TRANSACTION_ID_VALUE(0x51),
        SEMENTICALLY_INCORRECT_MESSAGE(0x5F),
        INVALID_MANDATORY_INFO(0x60),
        MESSAGE_TYPE_NONEXIST_NOT_IMPLEMENTED(0x61),
        MESSAGE_TYPE_NOT_COMPATIBLE_WITH_PROTOCOL_STATE(0x62),
        INFO_ELEMENT_NONEXIST_NOT_IMPLEMENTED(0x63),
        CONDITIONAL_IE_ERROR(0x64),
        MESSAGE_NOT_COMPATIBLE_WITH_PROTOCOL_STATE(0x65),
        PROTOCOL_ERRORS(0x6F),
        PN_RESTRICTION_VALUE_INCOMPATIBLE_WITH_PDP_CONTEXT(0x70),

        // Local errors generated by Vendor RIL
        // specified in ril.h
        REGISTRATION_FAIL(-1),
        GPRS_REGISTRATION_FAIL(-2),
        SIGNAL_LOST(-3),
        PREF_RADIO_TECH_CHANGED(-4),
        RADIO_POWER_OFF(-5),
        TETHERED_CALL_ACTIVE(-6),
        ERROR_UNSPECIFIED(0xFFFF),

        // Errors generated by the Framework
        // specified here
        UNKNOWN(0x10000),
        RADIO_NOT_AVAILABLE(0x10001),
        UNACCEPTABLE_NETWORK_PARAMETER(0x10002),
        CONNECTION_TO_DATACONNECTIONAC_BROKEN(0x10003);

        private final int mErrorCode;
        private static final HashMap<Integer, FailCause> sErrorCodeToFailCauseMap;
        static {
            sErrorCodeToFailCauseMap = new HashMap<Integer, FailCause>();
            for (FailCause fc : values()) {
                sErrorCodeToFailCauseMap.put(fc.getErrorCode(), fc);
            }
        }

        FailCause(int errorCode) {
            mErrorCode = errorCode;
        }

        int getErrorCode() {
            return mErrorCode;
        }

        // To Fix CR ALPS00351317, remove PROTOCOL_ERRORS from permanent fail list.
        public boolean isPermanentFail() {
            // Retry with all fail cause by modem suggestion
            /*return (this == OPERATOR_BARRED) || (this == MISSING_UNKNOWN_APN) ||
                   (this == UNKNOWN_PDP_ADDRESS_TYPE) || (this == USER_AUTHENTICATION) ||
                   (this == SERVICE_OPTION_NOT_SUPPORTED) ||
                    (this == SERVICE_OPTION_NOT_SUBSCRIBED) || (this == NSAPI_IN_USE);*/
            return false;
        }

        public boolean isEventLoggable() {
            return (this == OPERATOR_BARRED) || (this == INSUFFICIENT_RESOURCES) ||
                    (this == UNKNOWN_PDP_ADDRESS_TYPE) || (this == USER_AUTHENTICATION) ||
                    (this == ACTIVATION_REJECT_GGSN) || (this == ACTIVATION_REJECT_UNSPECIFIED) ||
                    (this == SERVICE_OPTION_NOT_SUBSCRIBED) ||
                    (this == SERVICE_OPTION_NOT_SUPPORTED) ||
                    (this == SERVICE_OPTION_OUT_OF_ORDER) || (this == NSAPI_IN_USE) ||
                    (this == PROTOCOL_ERRORS) ||
                    (this == UNACCEPTABLE_NETWORK_PARAMETER);
        }

        public static FailCause fromInt(int errorCode) {
            FailCause fc = sErrorCodeToFailCauseMap.get(errorCode);
            if (fc == null) {
                fc = UNKNOWN;
            }
            return fc;
        }
    }

    public static class CallSetupException extends Exception {
        private int mRetryOverride = -1;

        CallSetupException (int retryOverride) {
            mRetryOverride = retryOverride;
        }

        public int getRetryOverride() {
            return mRetryOverride;
        }
    }

    // ***** Event codes for driving the state machine
    protected static final int BASE = Protocol.BASE_DATA_CONNECTION;
    protected static final int EVENT_CONNECT = BASE + 0;
    protected static final int EVENT_SETUP_DATA_CONNECTION_DONE = BASE + 1;
    protected static final int EVENT_GET_LAST_FAIL_DONE = BASE + 2;
    protected static final int EVENT_DEACTIVATE_DONE = BASE + 3;
    protected static final int EVENT_DISCONNECT = BASE + 4;
    protected static final int EVENT_RIL_CONNECTED = BASE + 5;
    protected static final int EVENT_DISCONNECT_ALL = BASE + 6;

    private static final int CMD_TO_STRING_COUNT = EVENT_DISCONNECT_ALL - BASE + 1;
    private static String[] sCmdToString = new String[CMD_TO_STRING_COUNT];
    static {
        sCmdToString[EVENT_CONNECT - BASE] = "EVENT_CONNECT";
        sCmdToString[EVENT_SETUP_DATA_CONNECTION_DONE - BASE] =
                "EVENT_SETUP_DATA_CONNECTION_DONE";
        sCmdToString[EVENT_GET_LAST_FAIL_DONE - BASE] = "EVENT_GET_LAST_FAIL_DONE";
        sCmdToString[EVENT_DEACTIVATE_DONE - BASE] = "EVENT_DEACTIVATE_DONE";
        sCmdToString[EVENT_DISCONNECT - BASE] = "EVENT_DISCONNECT";
        sCmdToString[EVENT_RIL_CONNECTED - BASE] = "EVENT_RIL_CONNECTED";
        sCmdToString[EVENT_DISCONNECT_ALL - BASE] = "EVENT_DISCONNECT_ALL";
    }
    protected static String cmdToString(int cmd) {
        cmd -= BASE;
        if ((cmd >= 0) && (cmd < sCmdToString.length)) {
            return sCmdToString[cmd];
        } else {
            return null;
        }
    }

    //***** Tag IDs for EventLog
    protected static final int EVENT_LOG_BAD_DNS_ADDRESS = 50100;

    //***** Member Variables
    protected ApnSetting mApn;
    protected int mTag;
    protected PhoneBase phone;
    protected int mRilVersion = -1;
    protected int cid;
    protected LinkProperties mLinkProperties = new LinkProperties();
    protected LinkCapabilities mCapabilities = new LinkCapabilities();
    protected long createTime;
    protected long lastFailTime;
    protected FailCause lastFailCause;
    protected int mRetryOverride = -1;
    protected static final String NULL_IP = "0.0.0.0";
    protected int mRefCount;
    Object userData;

    //***** Abstract methods
    @Override
    public abstract String toString();

    protected abstract void onConnect(ConnectionParams cp);

    protected abstract boolean isDnsOk(String[] domainNameServers);

    protected abstract void log(String s);

   //***** Constructor
    protected DataConnection(PhoneBase phone, String name, int id, RetryManager rm,
            DataConnectionTracker dct) {
        super(name);
        setLogRecSize(100);
        if (DBG) log("DataConnection constructor E");
        this.phone = phone;
        this.mDataConnectionTracker = dct;
        mId = id;
        mRetryMgr = rm;
        this.cid = -1;

        setDbg(false);
        addState(mDefaultState);
            addState(mInactiveState, mDefaultState);
            addState(mActivatingState, mDefaultState);
            addState(mActiveState, mDefaultState);
            addState(mDisconnectingState, mDefaultState);
            addState(mDisconnectingErrorCreatingConnection, mDefaultState);
        setInitialState(mInactiveState);

        mApnList = new ArrayList<ApnContext>();
        if (DBG) log("DataConnection constructor X");
    }

    /**
     * Shut down this instance and its state machine.
     */
    private void shutDown() {
        if (DBG) log("shutDown");

        if (mAc != null) {
            mAc.disconnected();
            mAc = null;
        }
        mApnList = null;
        mReconnectIntent = null;
        mDataConnectionTracker = null;
        mApn = null;
        phone = null;
        mLinkProperties = null;
        mCapabilities = null;
        lastFailCause = null;
        userData = null;
    }

    /**
     * TearDown the data connection.
     *
     * @param o will be returned in AsyncResult.userObj
     *          and is either a DisconnectParams or ConnectionParams.
     */
    private void tearDownData(Object o) {
        int discReason = RILConstants.DEACTIVATE_REASON_NONE;
        if ((o != null) && (o instanceof DisconnectParams)) {
            DisconnectParams dp = (DisconnectParams)o;
            Message m = dp.onCompletedMsg;
            if (TextUtils.equals(dp.reason, Phone.REASON_RADIO_TURNED_OFF)) {
                discReason = RILConstants.DEACTIVATE_REASON_RADIO_OFF;
            } else if (TextUtils.equals(dp.reason, Phone.REASON_PDP_RESET)) {
                discReason = RILConstants.DEACTIVATE_REASON_PDP_RESET;
            }
        }
        if (phone.mCM.getRadioState().isOn()) {
            if (DBG) log("tearDownData radio is on, call deactivateDataCall");
            phone.mCM.deactivateDataCall(cid, discReason, obtainMessage(EVENT_DEACTIVATE_DONE, o));
        } else {
            if (DBG) log("tearDownData radio is off sendMessage EVENT_DEACTIVATE_DONE immediately");
            AsyncResult ar = new AsyncResult(o, null, null);
            sendMessage(obtainMessage(EVENT_DEACTIVATE_DONE, ar));
        }
    }

    /**
     * Send the connectionCompletedMsg.
     *
     * @param cp is the ConnectionParams
     * @param cause
     */
    private void notifyConnectCompleted(ConnectionParams cp, FailCause cause) {
        Message connectionCompletedMsg = cp.onCompletedMsg;
        if (connectionCompletedMsg == null) {
            return;
        }

        long timeStamp = System.currentTimeMillis();
        connectionCompletedMsg.arg1 = cid;

        if (cause == FailCause.NONE) {
            createTime = timeStamp;
            AsyncResult.forMessage(connectionCompletedMsg);
        } else {
            lastFailCause = cause;
            lastFailTime = timeStamp;
            AsyncResult.forMessage(connectionCompletedMsg, cause,
                                   new CallSetupException(mRetryOverride));
        }
        if (DBG) log("notifyConnectionCompleted at " + timeStamp + " cause=" + cause);

        connectionCompletedMsg.sendToTarget();
    }

    /**
     * Send ar.userObj if its a message, which is should be back to originator.
     *
     * @param dp is the DisconnectParams.
     */
    private void notifyDisconnectCompleted(DisconnectParams dp, boolean sendAll) {
        if (VDBG) log("NotifyDisconnectCompleted");

        ApnContext alreadySent = null;
        String reason = null;

        if (dp.onCompletedMsg != null) {
            // Get ApnContext, but only valid on GSM devices this is a string on CDMA devices.
            Message msg = dp.onCompletedMsg;
            if (msg.obj instanceof ApnContext) {
                alreadySent = (ApnContext)msg.obj;
            }
            reason = dp.reason;
            if (VDBG) {
                log(String.format("msg=%s msg.obj=%s", msg.toString(),
                    ((msg.obj instanceof String) ? (String) msg.obj : "<no-reason>")));
            }
            AsyncResult.forMessage(msg);
            msg.sendToTarget();
        }
        if (sendAll) {
            for (ApnContext a : mApnList) {
                if (a == alreadySent) continue;
                if (reason != null) a.setReason(reason);
                Message msg = mDataConnectionTracker.obtainMessage(
                        DctConstants.EVENT_DISCONNECT_DONE, a);
                AsyncResult.forMessage(msg);
                msg.sendToTarget();
            }
        }

        if (DBG) log("NotifyDisconnectCompleted DisconnectParams=" + dp);
    }

    protected int getRilRadioTechnology(int defaultRilRadioTechnology) {
        int rilRadioTechnology;
        if (mRilVersion < 6) {
            rilRadioTechnology = defaultRilRadioTechnology;
        } else {
            rilRadioTechnology = phone.getServiceState().getRilRadioTechnology() + 2;
        }
        return rilRadioTechnology;
    }

    /*
     * **************************************************************************
     * Begin Members and methods owned by DataConnectionTracker but stored
     * in a DataConnection because there is one per connection.
     * **************************************************************************
     */

    /*
     * The id is owned by DataConnectionTracker.
     */
    protected int mId;

    /**
     * Get the DataConnection ID
     */
    public int getDataConnectionId() {
        return mId;
    }

    /*
     * The retry manager is currently owned by the DataConnectionTracker but is stored
     * in the DataConnection because there is one per connection. These methods
     * should only be used by the DataConnectionTracker although someday the retrying
     * maybe managed by the DataConnection itself and these methods could disappear.
     */
    private RetryManager mRetryMgr;

    /**
     * @return retry manager retryCount
     */
    public int getRetryCount() {
        return mRetryMgr.getRetryCount();
    }

    /**
     * set retry manager retryCount
     */
    public void setRetryCount(int retryCount) {
        if (DBG) log("setRetryCount: " + retryCount);
        mRetryMgr.setRetryCount(retryCount);
    }

    /**
     * @return retry manager retryTimer
     */
    public int getRetryTimer() {
        return mRetryMgr.getRetryTimer();
    }

    /**
     * increaseRetryCount of retry manager
     */
    public void increaseRetryCount() {
        mRetryMgr.increaseRetryCount();
    }

    /**
     * @return retry manager isRetryNeeded
     */
    public boolean isRetryNeeded() {
        return mRetryMgr.isRetryNeeded();
    }

    /**
     * resetRetryCount of retry manager
     */
    public void resetRetryCount() {
        mRetryMgr.resetRetryCount();
    }

    /**
     * set retryForeverUsingLasttimeout of retry manager
     */
    public void retryForeverUsingLastTimeout() {
        mRetryMgr.retryForeverUsingLastTimeout();
    }

    /**
     * @return retry manager isRetryForever
     */
    public boolean isRetryForever() {
        return mRetryMgr.isRetryForever();
    }

    /**
     * @return whether the retry config is set successfully or not
     */
    public boolean configureRetry(int maxRetryCount, int retryTime, int randomizationTime) {
        return mRetryMgr.configure(maxRetryCount, retryTime, randomizationTime);
    }

    /**
     * @return whether the retry config is set successfully or not
     */
    public boolean configureRetry(String configStr) {
        return mRetryMgr.configure(configStr);
    }

    /*
     * **************************************************************************
     * End members owned by DataConnectionTracker
     * **************************************************************************
     */

    /**
     * Clear all settings called when entering mInactiveState.
     */
    protected void clearSettings() {
        if (DBG) log("clearSettings");

        createTime = -1;
        lastFailTime = -1;
        lastFailCause = FailCause.NONE;
        mRetryOverride = -1;
        mRefCount = 0;
        cid = -1;

        mLinkProperties = new LinkProperties();
        mApn = null;
    }

    /**
     * Process setup completion.
     *
     * @param ar is the result
     * @return SetupResult.
     */
    private DataCallState.SetupResult onSetupConnectionCompleted(AsyncResult ar) {
        DataCallState response = (DataCallState) ar.result;
        ConnectionParams cp = (ConnectionParams) ar.userObj;
        DataCallState.SetupResult result;

        if (ar.exception != null) {
            if (DBG) {
                log("onSetupConnectionCompleted failed, ar.exception=" + ar.exception +
                    " response=" + response);
            }

            if (ar.exception instanceof CommandException
                    && ((CommandException) (ar.exception)).getCommandError()
                    == CommandException.Error.RADIO_NOT_AVAILABLE) {
                result = DataCallState.SetupResult.ERR_BadCommand;
                result.mFailCause = FailCause.RADIO_NOT_AVAILABLE;
            } else if ((response == null) || (response.version < 4)) {
                result = DataCallState.SetupResult.ERR_GetLastErrorFromRil;
            } else {
                result = DataCallState.SetupResult.ERR_RilError;
                result.mFailCause = FailCause.fromInt(response.status);
            }
        } else if (cp.tag != mTag) {
            if (DBG) {
                log("BUG: onSetupConnectionCompleted is stale cp.tag=" + cp.tag + ", mtag=" + mTag);
            }
            result = DataCallState.SetupResult.ERR_Stale;
        } else if (response.status != 0) {
            result = DataCallState.SetupResult.ERR_RilError;
            result.mFailCause = FailCause.fromInt(response.status);
        } else {
            if (DBG) log("onSetupConnectionCompleted received DataCallState: " + response);
            cid = response.cid;
            result = updateLinkProperty(response).setupResult;
        }

        return result;
    }

    private int getSuggestedRetryTime(AsyncResult ar) {
        int retry = -1;
        if (ar.exception == null) {
            DataCallState response = (DataCallState) ar.result;
            retry =  response.suggestedRetryTime;
        }
        return retry;
    }

    private DataCallState.SetupResult setLinkProperties(DataCallState response,
            LinkProperties lp) {
        // Check if system property dns usable
        boolean okToUseSystemPropertyDns = false;
        String propertyPrefix = "net." + response.ifname + ".";
        String dnsServers[] = new String[2];
        dnsServers[0] = SystemProperties.get(propertyPrefix + "dns1");
        dnsServers[1] = SystemProperties.get(propertyPrefix + "dns2");
        okToUseSystemPropertyDns = isDnsOk(dnsServers);

        // set link properties based on data call response
        return response.setLinkProperties(lp, okToUseSystemPropertyDns);
    }

    public static class UpdateLinkPropertyResult {
        public DataCallState.SetupResult setupResult = DataCallState.SetupResult.SUCCESS;
        public LinkProperties oldLp;
        public LinkProperties newLp;
        public UpdateLinkPropertyResult(LinkProperties curLp) {
            oldLp = curLp;
            newLp = curLp;
        }
    }

    private UpdateLinkPropertyResult updateLinkProperty(DataCallState newState) {
        UpdateLinkPropertyResult result = new UpdateLinkPropertyResult(mLinkProperties);

        if (newState == null) return result;

        DataCallState.SetupResult setupResult;
        result.newLp = new LinkProperties();

        // set link properties based on data call response
        result.setupResult = setLinkProperties(newState, result.newLp);
        if (result.setupResult != DataCallState.SetupResult.SUCCESS) {
            if (DBG) log("updateLinkProperty failed : " + result.setupResult);
            return result;
        }
        // copy HTTP proxy as it is not part DataCallState.
        result.newLp.setHttpProxy(mLinkProperties.getHttpProxy());

        if (DBG && (! result.oldLp.equals(result.newLp))) {
            log("updateLinkProperty old LP=" + result.oldLp);
            log("updateLinkProperty new LP=" + result.newLp);
        }
        mLinkProperties = result.newLp;

        return result;
    }

    /**
     * The parent state for all other states.
     */
    private class DcDefaultState extends State {
        @Override
        public void enter() {
            phone.mCM.registerForRilConnected(getHandler(), EVENT_RIL_CONNECTED, null);
        }
        @Override
        public void exit() {
            phone.mCM.unregisterForRilConnected(getHandler());
            shutDown();
        }
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal = HANDLED;
            AsyncResult ar;

            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    if (mAc != null) {
                        if (VDBG) log("Disconnecting to previous connection mAc=" + mAc);
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                    } else {
                        mAc = new AsyncChannel();
                        mAc.connected(null, getHandler(), msg.replyTo);
                        if (VDBG) log("DcDefaultState: FULL_CONNECTION reply connected");
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL, mId, "hi");
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECTED");
                    quit();
                    break;
                }
                case DataConnectionAc.REQ_IS_INACTIVE: {
                    boolean val = getCurrentState() == mInactiveState;
                    if (VDBG) log("REQ_IS_INACTIVE  isInactive=" + val);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_IS_INACTIVE, val ? 1 : 0);
                    break;
                }
                case DataConnectionAc.REQ_GET_CID: {
                    if (VDBG) log("REQ_GET_CID  cid=" + cid);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_CID, cid);
                    break;
                }
                case DataConnectionAc.REQ_GET_APNSETTING: {
                    if (VDBG) log("REQ_GET_APNSETTING  apnSetting=" + mApn);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_APNSETTING, mApn);
                    break;
                }
                case DataConnectionAc.REQ_GET_LINK_PROPERTIES: {
                    LinkProperties lp = new LinkProperties(mLinkProperties);
                    if (VDBG) log("REQ_GET_LINK_PROPERTIES linkProperties" + lp);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_LINK_PROPERTIES, lp);
                    break;
                }
                case DataConnectionAc.REQ_SET_LINK_PROPERTIES_HTTP_PROXY: {
                    ProxyProperties proxy = (ProxyProperties) msg.obj;
                    if (VDBG) log("REQ_SET_LINK_PROPERTIES_HTTP_PROXY proxy=" + proxy);
                    mLinkProperties.setHttpProxy(proxy);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_SET_LINK_PROPERTIES_HTTP_PROXY);
                    break;
                }
                case DataConnectionAc.REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE: {
                    DataCallState newState = (DataCallState) msg.obj;
                    UpdateLinkPropertyResult result =
                                             updateLinkProperty(newState);
                    if (VDBG) {
                        log("REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE result="
                            + result + " newState=" + newState);
                    }
                    mAc.replyToMessage(msg,
                                   DataConnectionAc.RSP_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE,
                                   result);
                    break;
                }
                case DataConnectionAc.REQ_GET_LINK_CAPABILITIES: {
                    LinkCapabilities lc = new LinkCapabilities(mCapabilities);
                    if (VDBG) log("REQ_GET_LINK_CAPABILITIES linkCapabilities" + lc);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_LINK_CAPABILITIES, lc);
                    break;
                }
                case DataConnectionAc.REQ_RESET:
                    if (VDBG) log("DcDefaultState: msg.what=REQ_RESET");
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_RESET);
                    transitionTo(mInactiveState);
                    break;
                case DataConnectionAc.REQ_GET_REFCOUNT: {
                    if (VDBG) log("REQ_GET_REFCOUNT  refCount=" + mRefCount);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_REFCOUNT, mRefCount);
                    break;
                }
                case DataConnectionAc.REQ_ADD_APNCONTEXT: {
                    ApnContext apnContext = (ApnContext) msg.obj;
                    if (VDBG) log("REQ_ADD_APNCONTEXT apn=" + apnContext.getApnType());
                    if (!mApnList.contains(apnContext)) {
                        mApnList.add(apnContext);
                    }
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_ADD_APNCONTEXT);
                    break;
                }
                case DataConnectionAc.REQ_REMOVE_APNCONTEXT: {
                    ApnContext apnContext = (ApnContext) msg.obj;
                    if (VDBG) log("REQ_REMOVE_APNCONTEXT apn=" + apnContext.getApnType());
                    mApnList.remove(apnContext);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_REMOVE_APNCONTEXT);
                    break;
                }
                case DataConnectionAc.REQ_GET_APNCONTEXT_LIST: {
                    if (VDBG) log("REQ_GET_APNCONTEXT_LIST num in list=" + mApnList.size());
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_APNCONTEXT_LIST,
                                       new ArrayList<ApnContext>(mApnList));
                    break;
                }
                case DataConnectionAc.REQ_SET_RECONNECT_INTENT: {
                    PendingIntent intent = (PendingIntent) msg.obj;
                    if (VDBG) log("REQ_SET_RECONNECT_INTENT");
                    mReconnectIntent = intent;
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_SET_RECONNECT_INTENT);
                    break;
                }
                case DataConnectionAc.REQ_GET_RECONNECT_INTENT: {
                    if (VDBG) log("REQ_GET_RECONNECT_INTENT");
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_RECONNECT_INTENT,
                                       mReconnectIntent);
                    break;
                }
                case EVENT_CONNECT:
                    if (DBG) log("DcDefaultState: msg.what=EVENT_CONNECT, fail not expected");
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    notifyConnectCompleted(cp, FailCause.UNKNOWN);
                    break;

                case EVENT_DISCONNECT:
                    if (DBG) {
                        log("DcDefaultState deferring msg.what=EVENT_DISCONNECT" + mRefCount);
                    }
                    deferMessage(msg);
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) {
                        log("DcDefaultState deferring msg.what=EVENT_DISCONNECT_ALL" + mRefCount);
                    }
                    deferMessage(msg);
                    break;

                case EVENT_RIL_CONNECTED:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        mRilVersion = (Integer)ar.result;
                        if (DBG) {
                            log("DcDefaultState: msg.what=EVENT_RIL_CONNECTED mRilVersion=" +
                                mRilVersion);
                        }
                    } else {
                        log("Unexpected exception on EVENT_RIL_CONNECTED");
                        mRilVersion = -1;
                    }
                    break;

                default:
                    if (DBG) {
                        log("DcDefaultState: shouldn't happen but ignore msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    break;
            }

            return retVal;
        }
    }
    private DcDefaultState mDefaultState = new DcDefaultState();

    /**
     * The state machine is inactive and expects a EVENT_CONNECT.
     */
    private class DcInactiveState extends State {
        private ConnectionParams mConnectionParams = null;
        private FailCause mFailCause = null;
        private DisconnectParams mDisconnectParams = null;

        public void setEnterNotificationParams(ConnectionParams cp, FailCause cause,
                                               int retryOverride) {
            if (VDBG) log("DcInactiveState: setEnterNoticationParams cp,cause");
            mConnectionParams = cp;
            mFailCause = cause;
            mRetryOverride = retryOverride;
        }

        public void setEnterNotificationParams(DisconnectParams dp) {
            if (VDBG) log("DcInactiveState: setEnterNoticationParams dp");
            mDisconnectParams = dp;
        }

        @Override
        public void enter() {
            mTag += 1;

            /**
             * Now that we've transitioned to Inactive state we
             * can send notifications. Previously we sent the
             * notifications in the processMessage handler but
             * that caused a race condition because the synchronous
             * call to isInactive.
             */
            if ((mConnectionParams != null) && (mFailCause != null)) {
                if (VDBG) log("DcInactiveState: enter notifyConnectCompleted");
                notifyConnectCompleted(mConnectionParams, mFailCause);
            }
            if (mDisconnectParams != null) {
                if (VDBG) log("DcInactiveState: enter notifyDisconnectCompleted");
                notifyDisconnectCompleted(mDisconnectParams, true);
            }
            clearSettings();
        }

        @Override
        public void exit() {
            // clear notifications
            mConnectionParams = null;
            mFailCause = null;
            mDisconnectParams = null;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DataConnectionAc.REQ_RESET:
                    if (DBG) {
                        log("DcInactiveState: msg.what=RSP_RESET, ignore we're already reset");
                    }
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_RESET);
                    retVal = HANDLED;
                    break;

                case EVENT_CONNECT:
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    cp.tag = mTag;
                    if (DBG) {
                        log("DcInactiveState msg.what=EVENT_CONNECT." + "RefCount = "
                                + mRefCount);
                    }
                    mRefCount = 1;
                    onConnect(cp);
                    transitionTo(mActivatingState);
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT");
                    notifyDisconnectCompleted((DisconnectParams)msg.obj, false);
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT_ALL");
                    notifyDisconnectCompleted((DisconnectParams)msg.obj, false);
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcInactiveState nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcInactiveState mInactiveState = new DcInactiveState();

    /**
     * The state machine is activating a connection.
     */
    private class DcActivatingState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            AsyncResult ar;
            ConnectionParams cp;

            switch (msg.what) {
                case EVENT_CONNECT:
                    if (DBG) log("DcActivatingState deferring msg.what=EVENT_CONNECT refCount = "
                            + mRefCount);
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_SETUP_DATA_CONNECTION_DONE:
                    if (DBG) log("DcActivatingState msg.what=EVENT_SETUP_DATA_CONNECTION_DONE");

                    ar = (AsyncResult) msg.obj;
                    cp = (ConnectionParams) ar.userObj;

                    DataCallState.SetupResult result = onSetupConnectionCompleted(ar);
                    if (DBG) log("DcActivatingState onSetupConnectionCompleted result=" + result);
                    switch (result) {
                        case SUCCESS:
                            // All is well
                            mActiveState.setEnterNotificationParams(cp, FailCause.NONE);
                            transitionTo(mActiveState);
                            break;
                        case ERR_BadCommand:
                            // Vendor ril rejected the command and didn't connect.
                            // Transition to inactive but send notifications after
                            // we've entered the mInactive state.
                            mInactiveState.setEnterNotificationParams(cp, result.mFailCause, -1);
                            transitionTo(mInactiveState);
                            break;
                        case ERR_UnacceptableParameter:
                            // The addresses given from the RIL are bad
                            tearDownData(cp);
                            transitionTo(mDisconnectingErrorCreatingConnection);
                            break;
                        case ERR_GetLastErrorFromRil:
                            // Request failed and this is an old RIL
                            phone.mCM.getLastDataCallFailCause(
                                    obtainMessage(EVENT_GET_LAST_FAIL_DONE, cp));
                            break;
                        case ERR_RilError:
                            // Request failed and mFailCause has the reason
                            mInactiveState.setEnterNotificationParams(cp, result.mFailCause,
                                                                      getSuggestedRetryTime(ar));
                            transitionTo(mInactiveState);
                            break;
                        case ERR_Stale:
                            // Request is stale, ignore.
                            break;
                        default:
                            throw new RuntimeException("Unknown SetupResult, should not happen");
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_GET_LAST_FAIL_DONE:
                    ar = (AsyncResult) msg.obj;
                    cp = (ConnectionParams) ar.userObj;
                    FailCause cause = FailCause.UNKNOWN;

                    if (cp.tag == mTag) {
                        if (DBG) log("DcActivatingState msg.what=EVENT_GET_LAST_FAIL_DONE");
                        if (ar.exception == null) {
                            int rilFailCause = ((int[]) (ar.result))[0];
                            cause = FailCause.fromInt(rilFailCause);
                        }
                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams(cp, cause, -1);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) {
                            log("DcActivatingState EVENT_GET_LAST_FAIL_DONE is stale cp.tag="
                                + cp.tag + ", mTag=" + mTag);
                        }
                    }

                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcActivatingState not handled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcActivatingState mActivatingState = new DcActivatingState();

    /**
     * The state machine is connected, expecting an EVENT_DISCONNECT.
     */
    private class DcActiveState extends State {
        private ConnectionParams mConnectionParams = null;
        private FailCause mFailCause = null;

        public void setEnterNotificationParams(ConnectionParams cp, FailCause cause) {
            if (VDBG) log("DcInactiveState: setEnterNoticationParams cp,cause");
            mConnectionParams = cp;
            mFailCause = cause;
        }

        @Override public void enter() {
            /**
             * Now that we've transitioned to Active state we
             * can send notifications. Previously we sent the
             * notifications in the processMessage handler but
             * that caused a race condition because the synchronous
             * call to isActive.
             */
            if ((mConnectionParams != null) && (mFailCause != null)) {
                if (VDBG) log("DcActiveState: enter notifyConnectCompleted");
                notifyConnectCompleted(mConnectionParams, mFailCause);
            }
        }

        @Override
        public void exit() {
            // clear notifications
            mConnectionParams = null;
            mFailCause = null;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_CONNECT:
                    mRefCount++;
                    if (DBG) log("DcActiveState msg.what=EVENT_CONNECT RefCount=" + mRefCount);
                    if (msg.obj != null) {
                        notifyConnectCompleted((ConnectionParams) msg.obj, FailCause.NONE);
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_DISCONNECT:
                    mRefCount--;
                    if (DBG) log("DcActiveState msg.what=EVENT_DISCONNECT RefCount=" + mRefCount);
                    if (mRefCount == 0)
                    {
                        DisconnectParams dp = (DisconnectParams) msg.obj;
                        dp.tag = mTag;
                        tearDownData(dp);
                        transitionTo(mDisconnectingState);
                    } else {
                        if (msg.obj != null) {
                            notifyDisconnectCompleted((DisconnectParams) msg.obj, false);
                        }
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) {
                        log("DcActiveState msg.what=EVENT_DISCONNECT_ALL RefCount=" + mRefCount);
                    }
                    mRefCount = 0;
                    DisconnectParams dp = (DisconnectParams) msg.obj;
                    dp.tag = mTag;
                    tearDownData(dp);
                    transitionTo(mDisconnectingState);
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcActiveState not handled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcActiveState mActiveState = new DcActiveState();

    /**
     * The state machine is disconnecting.
     */
    private class DcDisconnectingState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_CONNECT:
                    if (DBG) log("DcDisconnectingState msg.what=EVENT_CONNECT. Defer. RefCount = "
                            + mRefCount);
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_DEACTIVATE_DONE:
                    if (DBG) log("DcDisconnectingState msg.what=EVENT_DEACTIVATE_DONE");
                    AsyncResult ar = (AsyncResult) msg.obj;
                    DisconnectParams dp = (DisconnectParams) ar.userObj;
                    if (dp.tag == mTag) {
                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams((DisconnectParams) ar.userObj);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) log("DcDisconnectState EVENT_DEACTIVATE_DONE stale dp.tag="
                                + dp.tag + " mTag=" + mTag);
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectingState not handled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectingState mDisconnectingState = new DcDisconnectingState();

    /**
     * The state machine is disconnecting after an creating a connection.
     */
    private class DcDisconnectionErrorCreatingConnection extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_DEACTIVATE_DONE:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    ConnectionParams cp = (ConnectionParams) ar.userObj;
                    if (cp.tag == mTag) {
                        if (DBG) {
                            log("DcDisconnectionErrorCreatingConnection" +
                                " msg.what=EVENT_DEACTIVATE_DONE");
                        }

                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams(cp,
                                FailCause.UNACCEPTABLE_NETWORK_PARAMETER, -1);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) {
                            log("DcDisconnectionErrorCreatingConnection EVENT_DEACTIVATE_DONE" +
                                    " stale dp.tag=" + cp.tag + ", mTag=" + mTag);
                        }
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectionErrorCreatingConnection not handled msg.what=0x"
                                + Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectionErrorCreatingConnection mDisconnectingErrorCreatingConnection =
                new DcDisconnectionErrorCreatingConnection();

    // ******* public interface

    /**
     * Bring up a connection to the apn and return an AsyncResult in onCompletedMsg.
     * Used for cellular networks that use Acesss Point Names (APN) such
     * as GSM networks.
     *
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *        With AsyncResult.userObj set to the original msg.obj,
     *        AsyncResult.result = FailCause and AsyncResult.exception = Exception().
     * @param apn is the Access Point Name to bring up a connection to
     */
    public void bringUp(Message onCompletedMsg, ApnSetting apn) {
        sendMessage(obtainMessage(EVENT_CONNECT, new ConnectionParams(apn, onCompletedMsg)));
    }

    /**
     * Tear down the connection through the apn on the network.
     *
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *        With AsyncResult.userObj set to the original msg.obj.
     */
    public void tearDown(String reason, Message onCompletedMsg) {
        sendMessage(obtainMessage(EVENT_DISCONNECT, new DisconnectParams(reason, onCompletedMsg)));
    }

    /**
     * Tear down the connection through the apn on the network.  Ignores refcount and
     * and always tears down.
     *
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *        With AsyncResult.userObj set to the original msg.obj.
     */
    public void tearDownAll(String reason, Message onCompletedMsg) {
        sendMessage(obtainMessage(EVENT_DISCONNECT_ALL,
                new DisconnectParams(reason, onCompletedMsg)));
    }

    /**
     * @return the string for msg.what as our info.
     */
    @Override
    protected String getWhatToString(int what) {
        String info = null;
        info = cmdToString(what);
        if (info == null) {
            info = DataConnectionAc.cmdToString(what);
        }
        return info;
    }

    /**
     * Dump the current state.
     *
     * @param fd
     * @param pw
     * @param args
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("DataConnection ");
        super.dump(fd, pw, args);
        pw.println(" mApnList=" + mApnList);
        pw.flush();
        pw.println(" mDataConnectionTracker=" + mDataConnectionTracker);
        pw.println(" mApn=" + mApn);
        pw.println(" mTag=" + mTag);
        pw.flush();
        pw.println(" phone=" + phone);
        pw.println(" mRilVersion=" + mRilVersion);
        pw.println(" cid=" + cid);
        pw.flush();
        pw.println(" mLinkProperties=" + mLinkProperties);
        pw.flush();
        pw.println(" mCapabilities=" + mCapabilities);
        pw.println(" createTime=" + TimeUtils.logTimeOfDay(createTime));
        pw.println(" lastFailTime=" + TimeUtils.logTimeOfDay(lastFailTime));
        pw.println(" lastFailCause=" + lastFailCause);
        pw.flush();
        pw.println(" mRetryOverride=" + mRetryOverride);
        pw.println(" mRefCount=" + mRefCount);
        pw.println(" userData=" + userData);
        if (mRetryMgr != null) pw.println(" " + mRetryMgr);
        pw.flush();
    }

    // MTK-Start
    public void removeConnectMsg() {
        log("removeConnectMsg()");
        removeMessages(EVENT_CONNECT);
    }
    // MTK-End	
}
