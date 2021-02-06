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

package com.android.ims.rcs.uce.presence.publish;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.RcsUceAdapter;
import android.telephony.ims.RcsUceAdapter.PublishState;
import android.telephony.ims.aidl.IRcsUcePublishStateCallback;
import android.util.Log;

import com.android.ims.RcsFeatureManager;
import com.android.ims.rcs.uce.UceController.UceControllerCallback;
import com.android.ims.rcs.uce.util.UceUtils;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The implementation of PublishController.
 */
public class PublishControllerImpl implements PublishController {

    private static final String LOG_TAG = UceUtils.getLogPrefix() + "PublishController";

    /**
     * Used to inject PublishProcessor instances for testing.
     */
    @VisibleForTesting
    public interface PublishProcessorFactory {
        PublishProcessor createPublishProcessor(Context context, int subId,
                DeviceCapabilityInfo capabilityInfo, PublishControllerCallback callback);
    }

    /**
     * Used to inject DeviceCapabilityListener instances for testing.
     */
    @VisibleForTesting
    public interface DeviceCapListenerFactory {
        DeviceCapabilityListener createDeviceCapListener(Context context, int subId,
                DeviceCapabilityInfo capInfo, PublishControllerCallback callback, Looper looper);
    }

    private final int mSubId;
    private final Context mContext;
    private PublishHandler mPublishHandler;
    private volatile boolean mIsDestroyedFlag;
    private final UceControllerCallback mUceCtrlCallback;

    // The device publish state
    private @PublishState int mPublishState;
    // The timestamp of updating the publish state
    private Instant mPublishStateUpdatedTime = Instant.now();

    // The callbacks to notify publish state changed.
    private RemoteCallbackList<IRcsUcePublishStateCallback> mPublishStateCallbacks;

    private final Object mPublishStateLock = new Object();

    // The information of the device's capabilities.
    private DeviceCapabilityInfo mDeviceCapabilityInfo;

    // The processor of publishing device's capabilities.
    private PublishProcessor mPublishProcessor;
    private PublishProcessorFactory mPublishProcessorFactory = (context, subId, capInfo, callback)
            -> new PublishProcessor(context, subId, capInfo, callback);

    // The listener to listen to the device's capabilities changed.
    private DeviceCapabilityListener mDeviceCapListener;
    private DeviceCapListenerFactory mDeviceCapListenerFactory =
            (context, subId, capInfo, callback, looper)
                    -> new DeviceCapabilityListener(context, subId, capInfo, callback, looper);

    public PublishControllerImpl(Context context, int subId, UceControllerCallback callback,
            Looper looper) {
        mSubId = subId;
        mContext = context;
        mUceCtrlCallback = callback;
        logi("create");
        initPublishController(looper);
    }

    @VisibleForTesting
    public PublishControllerImpl(Context context, int subId, UceControllerCallback c,
            Looper looper, DeviceCapListenerFactory deviceCapFactory,
            PublishProcessorFactory processorFactory) {
        mSubId = subId;
        mContext = context;
        mUceCtrlCallback = c;
        mDeviceCapListenerFactory = deviceCapFactory;
        mPublishProcessorFactory = processorFactory;
        initPublishController(looper);
    }

    private void initPublishController(Looper looper) {
        mPublishState = RcsUceAdapter.PUBLISH_STATE_NOT_PUBLISHED;
        mPublishStateCallbacks = new RemoteCallbackList<>();

        mPublishHandler = new PublishHandler(this, looper);
        mDeviceCapabilityInfo = new DeviceCapabilityInfo(mSubId);

        initPublishProcessor();
        initDeviceCapabilitiesListener();

        // Turn on the listener to listen to the device changes.
        mDeviceCapListener.initialize();
    }

    private void initPublishProcessor() {
        mPublishProcessor = mPublishProcessorFactory.createPublishProcessor(mContext, mSubId,
                mDeviceCapabilityInfo, mPublishControllerCallback);
    }

    private void initDeviceCapabilitiesListener() {
        mDeviceCapListener = mDeviceCapListenerFactory.createDeviceCapListener(mContext, mSubId,
                mDeviceCapabilityInfo, mPublishControllerCallback, mPublishHandler.getLooper());
    }

    @Override
    public void onRcsConnected(RcsFeatureManager manager) {
        logd("onRcsConnected");
        mPublishProcessor.onRcsConnected(manager);
    }

    @Override
    public void onRcsDisconnected() {
        logd("onRcsDisconnected");
        mPublishProcessor.onRcsDisconnected();
    }

    @Override
    public void onDestroy() {
        logi("onDestroy");
        mIsDestroyedFlag = true;
        mDeviceCapListener.onDestroy();   // It will turn off the listener automatically.
        mPublishHandler.onDestroy();
        mPublishProcessor.onDestroy();
        synchronized (mPublishStateLock) {
            clearPublishStateCallbacks();
        }
    }

    @Override
    public int getUcePublishState() {
        synchronized (mPublishStateLock) {
            return (!mIsDestroyedFlag) ? mPublishState : RcsUceAdapter.PUBLISH_STATE_OTHER_ERROR;
        }
    }

    /**
     * Register a {@link PublishStateCallback} to listen to the published state changed.
     */
    @Override
    public void registerPublishStateCallback(@NonNull IRcsUcePublishStateCallback c) {
        synchronized (mPublishStateLock) {
            if (mIsDestroyedFlag) return;
            mPublishStateCallbacks.register(c);
            logd("registerPublishStateCallback: size="
                    + mPublishStateCallbacks.getRegisteredCallbackCount());
        }
        // Notify the current publish state
        mPublishHandler.onNotifyCurrentPublishState(c);
    }

    /**
     * Removes an existing {@link PublishStateCallback}.
     */
    @Override
    public void unregisterPublishStateCallback(@NonNull IRcsUcePublishStateCallback c) {
        synchronized (mPublishStateLock) {
            if (mIsDestroyedFlag) return;
            mPublishStateCallbacks.unregister(c);
        }
    }

    // Clear the publish state callback since the publish controller instance is destroyed.
    private void clearPublishStateCallbacks() {
        synchronized (mPublishStateLock) {
            logd("clearPublishStateCallbacks");
            final int lastIndex = mPublishStateCallbacks.getRegisteredCallbackCount() - 1;
            for (int index = lastIndex; index >= 0; index--) {
                IRcsUcePublishStateCallback callback =
                        mPublishStateCallbacks.getRegisteredCallbackItem(index);
                mPublishStateCallbacks.unregister(callback);
            }
        }
    }

    /**
     * Notify that the device's capabilities has been unpublished from the network.
     */
    @Override
    public void onUnpublish() {
        logd("onUnpublish");
        if (mIsDestroyedFlag) return;
        mPublishHandler.onPublishStateChanged(RcsUceAdapter.PUBLISH_STATE_NOT_PUBLISHED,
                Instant.now());
    }

    @Override
    public RcsContactUceCapability getDeviceCapabilities() {
        return mDeviceCapabilityInfo.getDeviceCapabilities(mContext);
    }

    // The local publish request from the sub-components which interact with PublishController.
    private final PublishControllerCallback mPublishControllerCallback =
            new PublishControllerCallback() {
                @Override
                public void requestPublishFromInternal(@PublishTriggerType int type, long delay) {
                    logd("requestPublishFromInternal: type=" + type + ", delay=" + delay);
                    mPublishHandler.requestPublish(type, delay);
                }

                @Override
                public void onRequestCommandError(PublishRequestResponse requestResponse) {
                    logd("onRequestCommandError: taskId=" + requestResponse.getTaskId()
                            + ", time=" + requestResponse.getResponseTimestamp());
                    mPublishHandler.onRequestCommandError(requestResponse);
                }

                @Override
                public void onRequestNetworkResp(PublishRequestResponse requestResponse) {
                    logd("onRequestNetworkResp: taskId=" + requestResponse.getTaskId()
                            + ", time=" + requestResponse.getResponseTimestamp());
                    mPublishHandler.onRequestNetworkResponse(requestResponse);
                }

                @Override
                public void setupRequestCanceledTimer(long taskId, long delay) {
                    logd("setupRequestCanceledTimer: taskId=" + taskId + ", delay=" + delay);
                    mPublishHandler.setRequestCanceledTimer(taskId, delay);
                }

                @Override
                public void clearRequestCanceledTimer() {
                    logd("clearRequestCanceledTimer");
                    mPublishHandler.clearRequestCanceledTimer();
                }

                @Override
                public void updatePublishRequestResult(@PublishState int publishState,
                        Instant updatedTime) {
                    logd("updatePublishRequestResult: " + publishState + ", time=" + updatedTime);
                    mPublishHandler.onPublishStateChanged(publishState, updatedTime);
                }
            };

    /**
     * Publish the device's capabilities to the network. This method is triggered by ImsService.
     */
    @Override
    public void requestPublishCapabilitiesFromService(int triggerType) {
        logi("Receive the publish request from service: service trigger type=" + triggerType);
        mPublishHandler.requestPublish(PublishController.PUBLISH_TRIGGER_SERVICE);
    }

    private static class PublishHandler extends Handler {
        private static final int MSG_PUBLISH_STATE_CHANGED = 1;
        private static final int MSG_NOTIFY_CURRENT_PUBLISH_STATE = 2;
        private static final int MSG_REQUEST_PUBLISH = 3;
        private static final int MSG_REQUEST_CMD_ERROR = 4;
        private static final int MSG_REQUEST_NETWORK_RESPONSE = 5;
        private static final int MSG_REQUEST_CANCELED = 6;

        private final WeakReference<PublishControllerImpl> mPublishControllerRef;

        public PublishHandler(PublishControllerImpl publishController, Looper looper) {
            super(looper);
            mPublishControllerRef = new WeakReference<>(publishController);
        }

        @Override
        public void handleMessage(Message message) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) {
                return;
            }
            if (publishCtrl.mIsDestroyedFlag) return;
            publishCtrl.logd("handleMessage: " + EVENT_DESCRIPTION.get(message.what));
            switch (message.what) {
                case MSG_PUBLISH_STATE_CHANGED:
                    int newPublishState = message.arg1;
                    Instant updatedTimestamp = (Instant) message.obj;
                    publishCtrl.handlePublishStateChangedMessage(newPublishState, updatedTimestamp);
                    break;

                case MSG_NOTIFY_CURRENT_PUBLISH_STATE:
                    IRcsUcePublishStateCallback c = (IRcsUcePublishStateCallback) message.obj;
                    publishCtrl.handleNotifyCurrentPublishStateMessage(c);
                    break;

                case MSG_REQUEST_PUBLISH:
                    int type = (Integer) message.obj;
                    publishCtrl.handleRequestPublishMessage(type);
                    break;

                case MSG_REQUEST_CMD_ERROR:
                    PublishRequestResponse cmdErrorResponse = (PublishRequestResponse) message.obj;
                    publishCtrl.mPublishProcessor.onCommandError(cmdErrorResponse);
                    break;

                case MSG_REQUEST_NETWORK_RESPONSE:
                    PublishRequestResponse networkResponse = (PublishRequestResponse) message.obj;
                    publishCtrl.mPublishProcessor.onNetworkResponse(networkResponse);
                    break;

                case MSG_REQUEST_CANCELED:
                    long taskId = (Long) message.obj;
                    publishCtrl.handleRequestCanceledMessage(taskId);
                    break;
            }
        }

        /**
         * Remove all the messages from the handler.
         */
        public void onDestroy() {
            removeCallbacksAndMessages(null);
        }

        /**
         * Send the message to notify the publish state is changed.
         */
        public void onPublishStateChanged(@PublishState int publishState,
                @NonNull Instant updatedTimestamp) {
            Message message = obtainMessage();
            message.what = MSG_PUBLISH_STATE_CHANGED;
            message.arg1 = publishState;
            message.obj = updatedTimestamp;
            sendMessage(message);
        }

        public void onNotifyCurrentPublishState(IRcsUcePublishStateCallback callback) {
            Message message = obtainMessage();
            message.what = MSG_NOTIFY_CURRENT_PUBLISH_STATE;
            message.obj = callback;
            sendMessage(message);
        }

        /**
         * Send the request publish message without delay.
         */
        public void requestPublish(@PublishTriggerType int type) {
            requestPublish(type, 0L);
        }

        /**
         * Send the request publish message with the delay.
         */
        public void requestPublish(@PublishTriggerType int type, long delay) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) {
                return;
            }
            if (publishCtrl.mIsDestroyedFlag) return;
            publishCtrl.logd("requestPublish: " + type + ", delay=" + delay);

            // Don't send duplicated publish request because it always publish the latest device
            // capabilities.
            if (hasMessages(MSG_REQUEST_PUBLISH)) {
                publishCtrl.logd("requestPublish: Skip. there is already a request in the queue");
                return;
            }

            Message message = obtainMessage();
            message.what = MSG_REQUEST_PUBLISH;
            message.obj = (Integer) type;
            if (delay > 0) {
                sendMessageDelayed(message, delay);
            } else {
                sendMessage(message);
            }
        }

        public void onRequestCommandError(PublishRequestResponse requestResponse) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) {
                return;
            }
            if (publishCtrl.mIsDestroyedFlag) return;
            Message message = obtainMessage();
            message.what = MSG_REQUEST_CMD_ERROR;
            message.obj = requestResponse;
            sendMessage(message);
        }

        public void onRequestNetworkResponse(PublishRequestResponse requestResponse) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) {
                return;
            }
            if (publishCtrl.mIsDestroyedFlag) return;
            Message message = obtainMessage();
            message.what = MSG_REQUEST_NETWORK_RESPONSE;
            message.obj = requestResponse;
            sendMessage(message);
        }

        public void setRequestCanceledTimer(long taskId, long delay) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) {
                return;
            }
            if (publishCtrl.mIsDestroyedFlag) return;
            removeMessages(MSG_REQUEST_CANCELED, (Long) taskId);

            Message message = obtainMessage();
            message.what = MSG_REQUEST_CANCELED;
            message.obj = (Long) taskId;
            sendMessageDelayed(message, delay);
        }

        public void clearRequestCanceledTimer() {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) {
                return;
            }
            if (publishCtrl.mIsDestroyedFlag) return;
            removeMessages(MSG_REQUEST_CANCELED);
        }

        private static Map<Integer, String> EVENT_DESCRIPTION = new HashMap<>();
        static {
            EVENT_DESCRIPTION.put(MSG_PUBLISH_STATE_CHANGED, "PUBLISH_STATE_CHANGED");
            EVENT_DESCRIPTION.put(MSG_NOTIFY_CURRENT_PUBLISH_STATE, "NOTIFY_PUBLISH_STATE");
            EVENT_DESCRIPTION.put(MSG_REQUEST_PUBLISH, "REQUEST_PUBLISH");
            EVENT_DESCRIPTION.put(MSG_REQUEST_CMD_ERROR, "REQUEST_CMD_ERROR");
            EVENT_DESCRIPTION.put(MSG_REQUEST_NETWORK_RESPONSE, "REQUEST_NETWORK_RESPONSE");
            EVENT_DESCRIPTION.put(MSG_REQUEST_CANCELED, "REQUEST_CANCELED");
        }
    }

    /**
     * Update the publish state and notify the publish state callback if the new state is different
     * from original state.
     */
    private void handlePublishStateChangedMessage(@PublishState int newPublishState,
            Instant updatedTimestamp) {
        synchronized (mPublishStateLock) {
            if (mIsDestroyedFlag) return;
            // Check if the time of the given publish state is not earlier than existing time.
            if (updatedTimestamp == null || !updatedTimestamp.isAfter(mPublishStateUpdatedTime)) {
                logd("handlePublishStateChangedMessage: updatedTimestamp is not allowed: "
                        + mPublishStateUpdatedTime + " to " + updatedTimestamp
                        + ", publishState=" + newPublishState);
                return;
            }
            logd("publish state changes from " + mPublishState + " to " + newPublishState +
                    ", time=" + updatedTimestamp);
            if (mPublishState == newPublishState) return;
            mPublishState = newPublishState;
            mPublishStateUpdatedTime = updatedTimestamp;
        }

        // Trigger the publish state changed in handler thread since it may take time.
        logd("Notify publish state changed: " + mPublishState);
        mPublishStateCallbacks.broadcast(c -> {
            try {
                c.onPublishStateChanged(mPublishState);
            } catch (RemoteException e) {
                logw("Notify publish state changed error: " + e);
            }
        });
        logd("Notify publish state changed: completed");
    }

    private void handleNotifyCurrentPublishStateMessage(IRcsUcePublishStateCallback callback) {
        if (mIsDestroyedFlag || callback == null) return;
        try {
            callback.onPublishStateChanged(getUcePublishState());
        } catch (RemoteException e) {
            logw("handleCurrentPublishStateUpdateMessage exception: " + e);
        }
    }

    public void handleRequestPublishMessage(@PublishTriggerType int type) {
        if (mIsDestroyedFlag) return;
        if (mUceCtrlCallback.isRequestForbiddenByNetwork()) {
            logd("handleRequestPublishMessage: UCE request is forbidden by the network");
            return;
        }
        mPublishProcessor.doPublish(type);
    }

    public void handleRequestCanceledMessage(long taskId) {
        if (mIsDestroyedFlag) return;
        mPublishProcessor.cancelPublishRequest(taskId);
    }

    @VisibleForTesting
    public void setPublishStateCallback(RemoteCallbackList<IRcsUcePublishStateCallback> list) {
        mPublishStateCallbacks = list;
    }

    @VisibleForTesting
    public PublishHandler getPublishHandler() {
        return mPublishHandler;
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
