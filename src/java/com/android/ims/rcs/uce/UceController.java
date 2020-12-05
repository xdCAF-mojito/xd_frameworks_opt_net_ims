/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.ims.rcs.uce;

import android.annotation.NonNull;
import android.content.Context;
import android.net.Uri;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.RcsUceAdapter;
import android.telephony.ims.RcsUceAdapter.PublishState;
import android.telephony.ims.RcsUceAdapter.StackPublishTriggerType;
import android.telephony.ims.RcsUceAdapter.PublishStateCallback;
import android.telephony.ims.aidl.ICapabilityExchangeEventListener;
import android.telephony.ims.aidl.IOptionsRequestCallback;
import android.telephony.ims.aidl.IOptionsResponseCallback;
import android.telephony.ims.aidl.IRcsUceControllerCallback;
import android.telephony.ims.aidl.IRcsUcePublishStateCallback;
import android.util.Log;

import com.android.ims.RcsFeatureManager;
import com.android.ims.rcs.uce.eab.EabCapabilityResult;
import com.android.ims.rcs.uce.eab.EabController;
import com.android.ims.rcs.uce.eab.EabControllerImpl;
import com.android.ims.rcs.uce.options.OptionsController;
import com.android.ims.rcs.uce.options.OptionsControllerImpl;
import com.android.ims.rcs.uce.presence.publish.PublishController;
import com.android.ims.rcs.uce.presence.publish.PublishControllerImpl;
import com.android.ims.rcs.uce.presence.subscribe.SubscribeController;
import com.android.ims.rcs.uce.presence.subscribe.SubscribeControllerImpl;
import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * The UceController will manage the RCS UCE requests on a per subscription basis. When it receives
 * the UCE requests from the RCS applications and from the ImsService, it will coordinate the
 * cooperation between the publish/subscribe/options components to complete the requests.
 */
public class UceController {

    private static final String LOG_TAG = "UceController";

    /**
     * The callback interface is called by the internal controllers to receive information from
     * others controllers
     */
    public interface UceControllerCallback {
        /**
         * Retrieve the capabilities associated with the given uris from the cache.
         */
        EabCapabilityResult getCapabilitiesFromCache(@NonNull List<Uri> uris);

        /**
         * Retrieve the contact's capabilities from the availability cache.
         */
        EabCapabilityResult getAvailabilityFromCache(@NonNull Uri uri);

        /**
         * Store the given capabilities to the cache.
         */
        void saveCapabilities(List<RcsContactUceCapability> contactCapabilities);

        /**
         * Retrieve the device's capabilities.
         */
        RcsContactUceCapability getDeviceCapabilities();

        /**
         * Trigger the capabilities request with OPTIONS
         */
        void requestCapabilitiesByOptions(@NonNull Uri contactUri,
                @NonNull RcsContactUceCapability ownCapabilities,
                @NonNull IOptionsResponseCallback callback);

        /**
         * The method is called when the given contacts' capabilities are expired and need to be
         * refreshed.
         */
        void refreshCapabilities(@NonNull List<Uri> contactNumbers,
                @NonNull IRcsUceControllerCallback callback) throws RemoteException;

        /**
         * The method is called when the EabController and the PublishController want to receive
         * published state changes.
         */
        void registerPublishStateCallback(@NonNull IRcsUcePublishStateCallback c);

        /**
         * Remove the existing PublishStateCallback.
         */
        void unregisterPublishStateCallback(@NonNull IRcsUcePublishStateCallback c);
    }

    /**
     * Used to inject RequestTaskManger instances for testing.
     */
    @VisibleForTesting
    public interface RequestTaskManagerFactory {
        UceRequestTaskManager createTaskManager(Context context, int subId, Looper looper);
    }

    private RequestTaskManagerFactory mTaskManagerFactory = (context, subId, looper) ->
            new UceRequestTaskManager(context, subId, looper);

    /**
     * Used to inject Controller instances for testing.
     */
    @VisibleForTesting
    public interface ControllerFactory {
        /**
         * @return an {@link EabController} associated with the subscription id specified.
         */
        EabController createEabController(Context context, int subId, UceControllerCallback c,
                Looper looper);

        /**
         * @return an {@link PublishController} associated with the subscription id specified.
         */
        PublishController createPublishController(Context context, int subId, Looper looper);

        /**
         * @return an {@link SubscribeController} associated with the subscription id specified.
         */
        SubscribeController createSubscribeController(Context context, int subId,
                UceControllerCallback c, Looper looper);

        /**
         * @return an {@link OptionsController} associated with the subscription id specified.
         */
        OptionsController createOptionsController(Context context, int subId,
                UceControllerCallback c, Looper looper);
    }

    private ControllerFactory mControllerFactory = new ControllerFactory() {
        @Override
        public EabController createEabController(Context context, int subId,
                UceControllerCallback c, Looper looper) {
            return new EabControllerImpl(context, subId, c, looper);
        }

        @Override
        public PublishController createPublishController(Context context, int subId,
                Looper looper) {
            return new PublishControllerImpl(context, subId, looper);
        }

        @Override
        public SubscribeController createSubscribeController(Context context, int subId,
                UceControllerCallback c, Looper looper) {
            return new SubscribeControllerImpl(context, subId, c, looper);
        }

        @Override
        public OptionsController createOptionsController(Context context, int subId,
                UceControllerCallback c, Looper looper) {
            return new OptionsControllerImpl(context, subId, c, looper);
        }
    };

    private final int mSubId;
    private final Context mContext;
    private volatile boolean mIsRcsConnected;
    private volatile boolean mIsDestroyedFlag;
    private Looper mLooper;

    private EabController mEabController;
    private PublishController mPublishController;
    private SubscribeController mSubscribeController;
    private OptionsController mOptionsController;
    private UceRequestTaskManager mTaskManager;

    public UceController(Context context, int subId) {
        mSubId = subId;
        mContext = context;
        logi("create");

        initLooper();
        initRequestTaskManager();
        initControllers();
    }

    @VisibleForTesting
    public UceController(Context context, int subId, ControllerFactory controllerFactory,
            RequestTaskManagerFactory taskManagerFactory) {
        mSubId = subId;
        mContext = context;
        mControllerFactory = controllerFactory;
        mTaskManagerFactory = taskManagerFactory;
        initLooper();
        initRequestTaskManager();
        initControllers();
    }

    private void initLooper() {
        // Init the looper, it will be passed to each controller.
        HandlerThread handlerThread = new HandlerThread("UceControllerHandlerThread");
        handlerThread.start();
        mLooper = handlerThread.getLooper();
    }

    private void initRequestTaskManager() {
        mTaskManager = mTaskManagerFactory.createTaskManager(mContext, mSubId, mLooper);
    }

    private void initControllers() {
        mEabController = mControllerFactory.createEabController(mContext, mSubId, mCtrlCallback,
                mLooper);
        mPublishController = mControllerFactory.createPublishController(mContext, mSubId, mLooper);
        mSubscribeController = mControllerFactory.createSubscribeController(mContext, mSubId,
                mCtrlCallback, mLooper);
        mOptionsController = mControllerFactory.createOptionsController(mContext, mSubId,
                mCtrlCallback, mLooper);
    }

    /**
     * The RcsFeature has been connected to the framework. This method runs on main thread.
     */
    public void onRcsConnected(RcsFeatureManager manager) {
        logi("onRcsConnected");
        mIsRcsConnected = true;
        // Notify each controllers that RCS is connected.
        mEabController.onRcsConnected(manager);
        mPublishController.onRcsConnected(manager);
        mSubscribeController.onRcsConnected(manager);
        mOptionsController.onRcsConnected(manager);

        try {
            manager.setCapabilityExchangeEventListener(mCapabilityEventListener);
        } catch (RemoteException e) {
            logw("Set capability event listener error: " + e.getMessage());
        }
    }

    /**
     * The framework has lost the binding to the RcsFeature. This method runs on main thread.
     */
    public void onRcsDisconnected() {
        logi("onRcsDisconnected");
        mIsRcsConnected = false;
        // Notify each controllers that RCS is disconnected.
        mEabController.onRcsDisconnected();
        mPublishController.onRcsDisconnected();
        mSubscribeController.onRcsDisconnected();
        mOptionsController.onRcsDisconnected();
    }

    /**
     * Notify to destroy this instance. This instance is unusable after destroyed.
     */
    public void onDestroy() {
        logi("onDestroy");
        mIsDestroyedFlag = true;
        mTaskManager.onDestroy();
        mEabController.onDestroy();
        mPublishController.onDestroy();
        mSubscribeController.onDestroy();
        mOptionsController.onDestroy();

        mLooper.quit();
    }

    /*
     * The implementation of the interface UceControllerCallback. These methods are called by other
     * controllers.
     */
    private UceControllerCallback mCtrlCallback = new UceControllerCallback() {
        @Override
        public EabCapabilityResult getCapabilitiesFromCache(List<Uri> uris) {
            return mEabController.getCapabilities(uris);
        }

        @Override
        public EabCapabilityResult getAvailabilityFromCache(Uri contactUri) {
            return mEabController.getAvailability(contactUri);
        }

        @Override
        public void saveCapabilities(List<RcsContactUceCapability> contactCapabilities) {
            mEabController.saveCapabilities(contactCapabilities);
        }

        @Override
        public RcsContactUceCapability getDeviceCapabilities() {
            return mPublishController.getDeviceCapabilities();
        }

        @Override
        public void requestCapabilitiesByOptions(@NonNull Uri uri,
                @NonNull RcsContactUceCapability ownCapabilities,
                @NonNull IOptionsResponseCallback callback) {
            mOptionsController.sendCapabilitiesRequest(uri, ownCapabilities, callback);
        }

        @Override
        public void refreshCapabilities(@NonNull List<Uri> contactNumbers,
                @NonNull IRcsUceControllerCallback callback) throws RemoteException{
            logd("refreshCapabilities: " + contactNumbers.size());
            UceController.this.requestCapabilities(contactNumbers, callback);
        }

        @Override
        public void registerPublishStateCallback(@NonNull IRcsUcePublishStateCallback c) {
            logd("UceControllerCallback: registerPublishStateCallback");
            UceController.this.registerPublishStateCallback(c);
        }

        @Override
        public void unregisterPublishStateCallback(@NonNull IRcsUcePublishStateCallback c) {
            logd("UceControllerCallback: unregisterPublishStateCallback");
            UceController.this.unregisterPublishStateCallback(c);
        }
    };

    @VisibleForTesting
    public void setUceControllerCallback(UceControllerCallback callback) {
        mCtrlCallback = callback;
    }

    /*
     * Setup the listener to listen to the requests and updates from ImsService.
     */
    private ICapabilityExchangeEventListener mCapabilityEventListener =
            new ICapabilityExchangeEventListener.Stub() {
                @Override
                public void onRequestPublishCapabilities(
                        @StackPublishTriggerType int publishTriggerType) {
                    onRequestPublishCapabilitiesFromService(publishTriggerType);
                }

                @Override
                public void onUnpublish() {
                    UceController.this.onUnpublish();
                }

                @Override
                public void onRemoteCapabilityRequest(Uri contactUri,
                        List<String> remoteCapabilities, IOptionsRequestCallback cb) {
                    retrieveOptionsCapabilitiesForRemote(contactUri, remoteCapabilities, cb);
                }
            };

    /**
     * Request to get the contacts' capabilities. This method will retrieve the capabilities from
     * the cache If the capabilities are out of date, it will trigger another request to get the
     * latest contact's capabilities from the carrier network.
     */
    public void requestCapabilities(@NonNull List<Uri> uriList,
            @NonNull IRcsUceControllerCallback c) throws RemoteException {
        if (uriList == null || c == null) {
            logw("requestCapabilities: parameter is empty");
            if (c != null) {
                c.onError(RcsUceAdapter.ERROR_GENERIC_FAILURE, 0L);
            }
            return;
        }

        if (isUnavailable()) {
            logw("requestCapabilities: controller is unavailable");
            c.onError(RcsUceAdapter.ERROR_GENERIC_FAILURE, 0L);
            return;
        }

        // Trigger the capabilities request task
        logd("requestCapabilities: " + uriList.size());
        mTaskManager.triggerCapabilityRequestTask(mCtrlCallback, uriList, c);
    }

    /**
     * Request to get the contact's capabilities. It will check the availability cache first. If
     * the capability in the availability cache is expired then it will retrieve the capability
     * from the carrier network.
     */
    public void requestAvailability(@NonNull Uri uri, @NonNull IRcsUceControllerCallback c)
            throws RemoteException{
        if (uri == null || c == null) {
            logw("requestCapabilities: parameter is empty");
            if (c != null) {
                c.onError(RcsUceAdapter.ERROR_GENERIC_FAILURE, 0L);
            }
            return;
        }

        if (isUnavailable()) {
            logw("requestAvailability: controller is unavailable");
            c.onError(RcsUceAdapter.ERROR_GENERIC_FAILURE, 0L);
            return;
        }

        // Trigger the availability request task
        logd("requestAvailability");
        mTaskManager.triggerAvailabilityRequestTask(mCtrlCallback, uri, c);
    }

    /**
     * Publish the device's capabilities. This request is triggered from the ImsService.
     */
    public void onRequestPublishCapabilitiesFromService(@StackPublishTriggerType int triggerType) {
        logd("onRequestPublishCapabilitiesFromService: " + triggerType);
        mPublishController.requestPublishCapabilitiesFromService(triggerType);
    }

    /**
     * This method is triggered by the ImsService to notify framework that the device's
     * capabilities has been unpublished from the network.
     */
    public void onUnpublish() {
        logd("onUnpublish");
        mPublishController.onUnpublish();
    }

    /**
     * Request publish the device's capabilities. This request is from the ImsService to send the
     * capabilities to the remote side.
     */
    public void retrieveOptionsCapabilitiesForRemote(@NonNull Uri contactUri,
            @NonNull List<String> remoteCapabilities, @NonNull IOptionsRequestCallback c) {
        logd("retrieveOptionsCapabilitiesForRemote");
        mOptionsController.retrieveCapabilitiesForRemote(contactUri, remoteCapabilities, c);
    }

    /**
     * Register a {@link PublishStateCallback} to receive the published state changed.
     */
    public void registerPublishStateCallback(@NonNull IRcsUcePublishStateCallback c) {
        mPublishController.registerPublishStateCallback(c);
    }

    /**
     * Removes an existing {@link PublishStateCallback}.
     */
    public void unregisterPublishStateCallback(@NonNull IRcsUcePublishStateCallback c) {
        mPublishController.unregisterPublishStateCallback(c);
    }

    /**
     * Get the UCE publish state if the PUBLISH is supported by the carrier.
     */
    public @PublishState int getUcePublishState() {
        return mPublishController.getUcePublishState();
    }

    @VisibleForTesting
    public void setRequestTaskManagerFactory(RequestTaskManagerFactory factory) {
        mTaskManagerFactory = factory;
    }

    @VisibleForTesting
    public void setControllerFactory(ControllerFactory factory) {
        mControllerFactory = factory;
    }

    public int getSubId() {
        return mSubId;
    }

    public boolean isUnavailable() {
        if (!mIsRcsConnected || mIsDestroyedFlag) {
            return true;
        }
        return false;
    }

    private void logd(String log) {
        Log.d(LOG_TAG, getLogPrefix().append(log).toString());
    }

    private void logi(String log) {
        Log.i(LOG_TAG, getLogPrefix().append(log).toString());
    }

    private void logw(String log) {
        Log.w(LOG_TAG, getLogPrefix().append(log).toString());
    }

    private StringBuilder getLogPrefix() {
        StringBuilder builder = new StringBuilder("[");
        builder.append(mSubId);
        builder.append("] ");
        return builder;
    }
}
