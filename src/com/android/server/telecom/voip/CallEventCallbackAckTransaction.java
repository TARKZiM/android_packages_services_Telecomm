/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.telecom.voip;

import static android.telecom.TelecomManager.TELECOM_TRANSACTION_SUCCESS;
import static android.telecom.CallException.CODE_OPERATION_TIMED_OUT;

import android.os.Bundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;

import com.android.internal.telecom.ICallEventCallback;
import com.android.server.telecom.TransactionalServiceWrapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SRP: using the ICallEventCallback binder, reach out to the client for the pending call event and
 * get an acknowledgement that the call event can be completed.
 */
public class CallEventCallbackAckTransaction extends VoipCallTransaction {
    private static final String TAG = CallEventCallbackAckTransaction.class.getSimpleName();
    private final ICallEventCallback mICallEventCallback;
    private final String mAction;
    private final String mCallId;
    private final int mVideoState;
    private final VoipCallTransactionResult TRANSACTION_FAILED = new VoipCallTransactionResult(
            CODE_OPERATION_TIMED_OUT, "failed to complete the operation before timeout");

    private static class AckResultReceiver extends ResultReceiver {
        CountDownLatch mCountDownLatch;

        public AckResultReceiver(CountDownLatch latch) {
            super(null);
            mCountDownLatch = latch;
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (resultCode == TELECOM_TRANSACTION_SUCCESS) {
                mCountDownLatch.countDown();
            }
        }
    }

    public CallEventCallbackAckTransaction(ICallEventCallback service, String action, String callId,
            int videoState) {
        mICallEventCallback = service;
        mAction = action;
        mCallId = callId;
        mVideoState = videoState;
    }


    @Override
    public CompletionStage<VoipCallTransactionResult> processTransaction(Void v) {
        Log.d(TAG, "processTransaction");
        CountDownLatch latch = new CountDownLatch(1);
        ResultReceiver receiver = new AckResultReceiver(latch);

        try {
            switch (mAction) {
                case TransactionalServiceWrapper.ON_SET_INACTIVE:
                    mICallEventCallback.onSetInactive(mCallId, receiver);
                    break;
                case TransactionalServiceWrapper.ON_DISCONNECT:
                    mICallEventCallback.onDisconnect(mCallId, receiver);
                    break;
                case TransactionalServiceWrapper.ON_REJECT:
                    mICallEventCallback.onReject(mCallId, receiver);
                    break;
                case TransactionalServiceWrapper.ON_SET_ACTIVE:
                    mICallEventCallback.onSetActive(mCallId, receiver);
                    break;
                case TransactionalServiceWrapper.ON_ANSWER:
                    mICallEventCallback.onAnswer(mCallId, mVideoState, receiver);
                    break;
            }
        } catch (RemoteException remoteException) {
            return CompletableFuture.completedFuture(TRANSACTION_FAILED);
        }

        try {
            // wait for the client to ack that CallEventCallback
            boolean success = latch.await(VoipCallTransaction.TIMEOUT_LIMIT, TimeUnit.MILLISECONDS);
            if (!success) {
                // client send onError and failed to complete transaction
                return CompletableFuture.completedFuture(TRANSACTION_FAILED);
            } else {
                // success
                return CompletableFuture.completedFuture(
                        new VoipCallTransactionResult(VoipCallTransactionResult.RESULT_SUCCEED,
                                "success"));
            }
        } catch (InterruptedException ie) {
            return CompletableFuture.completedFuture(TRANSACTION_FAILED);
        }
    }
}