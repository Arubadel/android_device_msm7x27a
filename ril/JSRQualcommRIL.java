/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.telephony.SmsMessage;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.telephony.SignalStrength;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.gsm.SsData;
import com.android.internal.telephony.cdma.CdmaInformationRecords;

import com.android.internal.telephony.dataconnection.DataCallResponse;
import com.android.internal.telephony.dataconnection.DcFailCause;
import com.android.internal.telephony.dataconnection.DataProfile;

import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardStatus;
import com.android.internal.telephony.uicc.IccIoResult;
import java.util.ArrayList;

/**
 * Qualcomm RIL class for basebands that do not send the SIM status
 * piggybacked in RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED. Instead,
 * these radios will send radio state and we have to query for SIM
 * status separately.
 * Custom Qualcomm No SimReady RIL for JSR
 *
 * {@hide}
 */

public class JSRQualcommRIL extends RIL implements CommandsInterface {
    boolean RILJ_LOGV = true;
    boolean RILJ_LOGD = true;

    static final int RIL_REQUEST_GET_UICC_SUBSCRIPTION  = 10120;   // deprecated
    static final int RIL_REQUEST_GET_DATA_SUBSCRIPTION  = 10121;   // deprecated
    static final int RIL_REQUEST_SET_SUBSCRIPTION_MODE  = 10122;
    
    protected HandlerThread mIccThread;
    protected IccHandler mIccHandler;
    protected String mAid;
    protected boolean mUSIM = false;
    protected String[] mLastDataIface = new String[20];
    boolean skipCdmaSubcription = needsOldRilFeature("skipCdmaSubcription");
    
    private final int RIL_INT_RADIO_OFF         = 0;
    private final int RIL_INT_RADIO_UNAVAILABLE = 1;
    private final int RIL_INT_RADIO_ON          = 2;
    private final int RIL_INT_RADIO_ON_NG       = 10;
    private final int RIL_INT_RADIO_ON_HTC      = 13;
    private int mSetPreferredNetworkType	= -1;

    public JSRQualcommRIL(Context context, int networkMode, int cdmaSubscription) {        
        super(context, networkMode, cdmaSubscription);
        mSetPreferredNetworkType = -1;
        mQANElements = 5;
        Rlog.w(RILJ_LOG_TAG, "[JSR] Create JSRQualcommRIL");
    }

    public JSRQualcommRIL(Context context, int networkMode, int cdmaSubscription, Integer instanceId) {
        super(context, networkMode, cdmaSubscription, instanceId);
        mSetPreferredNetworkType = -1;
        mQANElements = 5;
        Rlog.w(RILJ_LOG_TAG, "[JSR] Create JSRQualcommRIL [" + instanceId + "]");
    }

// ------------------------------------------------------------------------------------
    
    @Override
    public void getCellInfoList(Message result) {
        if (RILJ_LOGD) riljLog("[JSR] > getCellInfoList [NOT SUPPORTED]");
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_CELL_INFO_LIST, result);
    }

    @Override
    public void setCellInfoListRate(int rateInMillis, Message response) {
        if (RILJ_LOGD) riljLog("[JSR] > setCellInfoListRate [NOT SUPPORTED]");
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE, result);
    }

    @Override
    public void setInitialAttachApn(String apn, String protocol, int authType, String username,
            String password, Message result) {
        if (RILJ_LOGD) riljLog("[JSR] > setInitialAttachApn [NOT SUPPORTED]");
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_INITIAL_ATTACH_APN, null);
    }
    
// ------------------------------------------------------------------------------------

    @Override public void
    supplyIccPin2(String pin, Message result) {
        supplyIccPin2ForApp(pin, mAid, result);
    }

    @Override public void
    changeIccPin2(String oldPin2, String newPin2, Message result) {
        changeIccPin2ForApp(oldPin2, newPin2, mAid, result);
    }

    @Override public void
    supplyIccPuk(String puk, String newPin, Message result) {
        supplyIccPukForApp(puk, newPin, mAid, result);
    }

    @Override public void
    supplyIccPuk2(String puk2, String newPin2, Message result) {
        supplyIccPuk2ForApp(puk2, newPin2, mAid, result);
    }

    @Override
    public void
    queryFacilityLock(String facility, String password, int serviceClass, Message response) {
        queryFacilityLockForApp(facility, password, serviceClass, mAid, response);
    }

    @Override
    public void
    setFacilityLock (String facility, boolean lockState, String password, int serviceClass, Message response) {
        setFacilityLockForApp(facility, lockState, password, serviceClass, mAid, response);
    }

    @Override
    public void
    getIMSI(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_IMSI, result);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeString(mAid);

        if (RILJ_LOGD) riljLog(rr.serialString() +
                              "> getIMSI:RIL_REQUEST_GET_IMSI " +
                              RIL_REQUEST_GET_IMSI +
                              " aid: " + mAid +
                              " " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    setNetworkSelectionModeManual(String operatorNumeric, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + operatorNumeric);

        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(operatorNumeric);
        rr.mParcel.writeString("NOCHANGE");

        send(rr);
    }
    
    @Override
    public void
    iccIO (int command, int fileid, String path, int p1, int p2, int p3,
            String data, String pin2, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SIM_IO, result);

        rr.mParcel.writeInt(command);
        rr.mParcel.writeInt(fileid);
        rr.mParcel.writeString(path);
        rr.mParcel.writeInt(p1);
        rr.mParcel.writeInt(p2);
        rr.mParcel.writeInt(p3);
        rr.mParcel.writeString(data);
        rr.mParcel.writeString(pin2);
        rr.mParcel.writeString(mAid);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> iccIO: "
                    + " aid: " + mAid + " "
                    + requestToString(rr.mRequest)
                    + " 0x" + Integer.toHexString(command)
                    + " 0x" + Integer.toHexString(fileid) + " "
                    + " path: " + path + ","
                    + p1 + "," + p2 + "," + p3);

        send(rr);
    }

    @Override
    protected Object
    responseIccCardStatus(Parcel p) {
        IccCardApplicationStatus appStatus;

        int cardState = p.readInt(); 
        // Standard stack doesn't recognize REMOVED and SIM_DETECT_INSERTED,
        // so convert them to ABSENT and PRESENT to trigger the hot-swapping check 
        if (cardState > 2)
            cardState -= 3;

        IccCardStatus cardStatus = new IccCardStatus();
        cardStatus.setCardState(cardState);
        cardStatus.setUniversalPinState(p.readInt());
        cardStatus.mGsmUmtsSubscriptionAppIndex = p.readInt();
        cardStatus.mCdmaSubscriptionAppIndex = p.readInt();
        cardStatus.mImsSubscriptionAppIndex = p.readInt();

        int numApplications = p.readInt();
        // limit to maximum allowed applications
        if (numApplications > IccCardStatus.CARD_MAX_APPS) {
            numApplications = IccCardStatus.CARD_MAX_APPS;
        }
        cardStatus.mApplications = new IccCardApplicationStatus[numApplications];

        for (int i = 0; i < numApplications; i++) {
            appStatus = new IccCardApplicationStatus();
            appStatus.app_type = appStatus.AppTypeFromRILInt(p.readInt());
            appStatus.app_state = appStatus.AppStateFromRILInt(p.readInt());
            appStatus.perso_substate = appStatus.PersoSubstateFromRILInt(p.readInt());
            appStatus.aid = p.readString();
            appStatus.app_label = p.readString();
            appStatus.pin1_replaced = p.readInt();
            appStatus.pin1 = appStatus.PinStateFromRILInt(p.readInt());
            if (!needsOldRilFeature("skippinpukcount")) {
                p.readInt(); //remaining_count_pin1
                p.readInt(); //remaining_count_puk1
            }
            appStatus.pin2 = appStatus.PinStateFromRILInt(p.readInt());
            if (!needsOldRilFeature("skippinpukcount")) {
                p.readInt(); //remaining_count_pin2
                p.readInt(); //remaining_count_puk2
            }
            cardStatus.mApplications[i] = appStatus;
        }
        int appIndex = -1;
        if (mPhoneType == RILConstants.CDMA_PHONE && !skipCdmaSubcription) {
            appIndex = cardStatus.mCdmaSubscriptionAppIndex;
            Rlog.w(RILJ_LOG_TAG, "This is a CDMA PHONE " + appIndex);
        } else {
            appIndex = cardStatus.mGsmUmtsSubscriptionAppIndex;
            Rlog.w(RILJ_LOG_TAG, "This is a GSM PHONE " + appIndex);
        }
        
        if (cardState == RILConstants.SIM_ABSENT)
            return cardStatus;

        if (appIndex >= 0 && numApplications > 0) {
            IccCardApplicationStatus application = cardStatus.mApplications[appIndex];
            mAid = application.aid;
            mUSIM = (application.app_type == IccCardApplicationStatus.AppType.APPTYPE_USIM);
            mSetPreferredNetworkType = mPreferredNetworkType;

            if (TextUtils.isEmpty(mAid))
               mAid = "";
            Rlog.w(RILJ_LOG_TAG, "mAid = '" + mAid + "'");
        }

        return cardStatus;
    }

// ------------------------------------------------------------------------------------
    
    @Override
    protected void
    processUnsolicited (Parcel p) {
        Object ret;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        int response = p.readInt();

        // Assume devices needing the "datacall" GB-compatibility flag are
        // running GB RILs, so skip 1031-1034 for those
        if (needsOldRilFeature("datacall")) {
            switch(response) {
                 case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
                 case RIL_UNSOl_CDMA_PRL_CHANGED:
                 case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:
                 case RIL_UNSOL_RIL_CONNECTED:
                     if (RILJ_LOGD) riljLog("[JSR] processUnsolicited: SKIP req = " + responseToString(response) + " (" + response + ")");
                     ret = responseVoid(p);
                     return;
            }
        }

        switch(response) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED: ret =  responseVoid(p); break;
            case RIL_UNSOL_RIL_CONNECTED: ret = responseInts(p); break;
            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;

            default:
                // Rewind the Parcel
                p.setDataPosition(dataPosition);

                // Forward responses that we are not overriding to the super class
                super.processUnsolicited(p);
                return;
        }

        if (RILJ_LOGD) riljLog("[JSR] processUnsolicited: req = " + responseToString(response) + " (" + response + ")");
        
        switch(response) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
                int state = p.readInt();
                setRadioStateFromRILInt(state);
                break;

            case RIL_UNSOL_RIL_CONNECTED:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                // Initial conditions
                setRadioPower(false, null);
                setPreferredNetworkType(mPreferredNetworkType, null);
                setCdmaSubscriptionSource(mCdmaSubscription, null);
                notifyRegistrantsRilConnectionChanged(((int[])ret)[0]);
                break;

            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mExitEmergencyCallbackModeRegistrants != null)
                    mExitEmergencyCallbackModeRegistrants.notifyRegistrants(new AsyncResult (null, null, null));
                break;
        }
    }

    protected void
    setRadioStateFromRILInt (int stateCode) {
        CommandsInterface.RadioState radioState;
        HandlerThread handlerThread;
        Looper looper;
        IccHandler iccHandler;

        switch (stateCode) {
            case RIL_INT_RADIO_OFF:
                radioState = CommandsInterface.RadioState.RADIO_OFF;
                Rlog.w(RILJ_LOG_TAG, "[JSR] set RIL_INT_RADIO_OFF");
                if (mIccHandler != null) {
                    mIccThread = null;
                    mIccHandler = null;
                }
                break;
            case RIL_INT_RADIO_UNAVAILABLE:
                Rlog.w(RILJ_LOG_TAG, "[JSR] set RIL_INT_RADIO_UNAVAILABLE");
                radioState = CommandsInterface.RadioState.RADIO_UNAVAILABLE;
                break;
            case RIL_INT_RADIO_ON:
            case RIL_INT_RADIO_ON_NG:
            case RIL_INT_RADIO_ON_HTC:
                Rlog.w(RILJ_LOG_TAG, "[JSR] set RIL_INT_RADIO_ON");
                if (mIccHandler == null) {
                    handlerThread = new HandlerThread("IccHandler");
                    mIccThread = handlerThread;

                    mIccThread.start();

                    looper = mIccThread.getLooper();
                    mIccHandler = new IccHandler(this,looper);
                    mIccHandler.run();
                }
                radioState = CommandsInterface.RadioState.RADIO_ON;
                break;
            default:
                throw new RuntimeException("Unrecognized RIL_RadioState: " + stateCode);
        }

        setRadioState (radioState);
    }
    
// ------------------------------------------------------------------------------------
    
    class IccHandler extends Handler implements Runnable {
        private static final int EVENT_RADIO_ON = 1;
        private static final int EVENT_ICC_STATUS_CHANGED = 2;
        private static final int EVENT_GET_ICC_STATUS_DONE = 3;
        private static final int EVENT_RADIO_OFF_OR_UNAVAILABLE = 4;

        private RIL mRil;
        private boolean mRadioOn = false;

        public IccHandler (RIL ril, Looper looper) {
            super (looper);
            mRil = ril;
        }

        public void handleMessage (Message paramMessage) {
            switch (paramMessage.what) {
                case EVENT_RADIO_ON:
                    mRadioOn = true;
                    Log.d(RILJ_LOG_TAG, "[JSR] Radio on -> Forcing sim status update");
                    sendMessage(obtainMessage(EVENT_ICC_STATUS_CHANGED));
                    break;

                case EVENT_ICC_STATUS_CHANGED:
                    if (mRadioOn) {
                        Log.d(RILJ_LOG_TAG, "[JSR] Received EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus");
                        mRil.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE, paramMessage.obj));
                    } else {
                        Log.d(RILJ_LOG_TAG, "[JSR] Received EVENT_ICC_STATUS_CHANGED while radio is not ON. Ignoring");
                    }
                    break;
                    
                case EVENT_GET_ICC_STATUS_DONE:
                    Rlog.w(RILJ_LOG_TAG, "[JSR] EVENT_GET_ICC_STATUS_DONE");
                    AsyncResult asyncResult = (AsyncResult) paramMessage.obj;
                    if (asyncResult.exception != null) {
                        Log.e (RILJ_LOG_TAG, "[JSR] IccCardStatusDone shouldn't return exceptions!", asyncResult.exception);
                        break;
                    }
                    IccCardStatus status = (IccCardStatus) asyncResult.result;
                    if (status.mApplications == null || status.mApplications.length == 0) {
                        if (!mRil.getRadioState().isOn()) {
                            break;
                        }
                        mRil.setRadioState(CommandsInterface.RadioState.RADIO_ON);
                    } else {
                        int appIndex = -1;
                        if (mPhoneType == RILConstants.CDMA_PHONE && status.mCdmaSubscriptionAppIndex >= 0) {
                            appIndex = status.mCdmaSubscriptionAppIndex;
                            Log.d(RILJ_LOG_TAG, "[JSR] This is a CDMA PHONE: " + appIndex);
                        } else {
                            appIndex = status.mGsmUmtsSubscriptionAppIndex;
                            Log.d(RILJ_LOG_TAG, "[JSR] This is a GSM PHONE: " + appIndex);
                            if (appIndex < 0) appIndex = 0;  // fixme
                        }

                        IccCardApplicationStatus application = status.mApplications[appIndex];
                        IccCardApplicationStatus.AppState app_state = application.app_state;
                        IccCardApplicationStatus.AppType app_type = application.app_type;

                        switch (app_state) {
                            case APPSTATE_PIN:
                            case APPSTATE_PUK:
                                switch (app_type) {
                                    case APPTYPE_SIM:
                                    case APPTYPE_USIM:
                                    case APPTYPE_RUIM:
                                        mRil.setRadioState(CommandsInterface.RadioState.RADIO_ON);
                                        break;
                                    default:
                                        Log.e(RILJ_LOG_TAG, "[JSR] Currently we don't handle SIMs of type: " + app_type);
                                        return;
                                }
                                break;
                            case APPSTATE_READY:
                                switch (app_type) {
                                    case APPTYPE_SIM:
                                    case APPTYPE_USIM:
                                    case APPTYPE_RUIM:
                                        mRil.setRadioState(CommandsInterface.RadioState.RADIO_ON);
                                        break;
                                    default:
                                        Log.e(RILJ_LOG_TAG, "[JSR] Currently we don't handle SIMs of type: " + app_type);
                                        return;
                                }
                                break;
                            default:
                                return;
                        }
                    }
                    break;
                    
                case EVENT_RADIO_OFF_OR_UNAVAILABLE:
                    Rlog.w(RILJ_LOG_TAG, "[JSR] EVENT_RADIO_OFF_OR_UNAVAILABLE");
                    mRadioOn = false;
                    // disposeCards(); // to be verified;
                    break;
                    
                default:
                    Log.e(RILJ_LOG_TAG, "[JSR] Unknown Event " + paramMessage.what);
                    break;
            }
        }

        public void run () {
            mRil.registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, null);
            Message msg = obtainMessage(EVENT_RADIO_ON);
            mRil.getIccCardStatus(msg);
        }
    }
 
}

