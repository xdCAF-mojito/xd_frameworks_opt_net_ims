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

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.telephony.ims.aidl.IRcsUceControllerCallback;

import com.android.ims.rcs.uce.UceController.UceControllerCallback;

import java.util.List;

/**
 * The interface of managing the capability request and the availability request.
 */
public class UceRequestTaskManager {

    private final Context mContext;
    private final int mSubId;
    private final Looper mLooper;

    public UceRequestTaskManager(Context context, int subId, Looper looper) {
        mContext = context;
        mSubId = subId;
        mLooper = looper;
    }

    /**
     * Trigger the capability request task.
     */
    public void triggerCapabilityRequestTask(UceControllerCallback controller, List<Uri> uriList,
            IRcsUceControllerCallback callback) {
        // TODO: Implement this method
    }
    /**
     * Trigger the availability request task.
     */
    public void triggerAvailabilityRequestTask(UceControllerCallback controller, Uri uri,
            IRcsUceControllerCallback callback) {
        // TODO: Implement this method
    }

    /**
     * Notify the task manager to destroy.
     */
    public void onDestroy() {
        // TODO: Implement this method
    }
}
