/*
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
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

package com.android.mms.transaction;

import com.android.internal.telephony.Phone;
import com.android.mms.R;
import com.android.mms.MmsApp;
import com.android.mms.util.RateController;
import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.NotificationInd;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduParser;
import com.google.android.mms.pdu.PduPersister;

import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkConnectivityListener;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.provider.Telephony.Mms;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.MmsSms.PendingMessages;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;

/**
 * The TransactionService of the MMS Client is responsible for handling requests
 * to initiate client-transactions sent from:
 * <ul>
 * <li>The Proxy-Relay (Through Push messages)</li>
 * <li>The composer/viewer activities of the MMS Client (Through intents)</li>
 * </ul>
 * The TransactionService runs locally in the same process as the application.
 * It contains a HandlerThread to which messages are posted from the
 * intent-receivers of this application.
 * <p/>
 * <b>IMPORTANT</b>: This is currently the only instance in the system in
 * which simultaneous connectivity to both the mobile data network and
 * a Wi-Fi network is allowed. This makes the code for handling network
 * connectivity somewhat different than it is in other applications. In
 * particular, we want to be able to send or receive MMS messages when
 * a Wi-Fi connection is active (which implies that there is no connection
 * to the mobile data network). This has two main consequences:
 * <ul>
 * <li>Testing for current network connectivity ({@link android.net.NetworkInfo#isConnected()} is
 * not sufficient. Instead, the correct test is for network availability
 * ({@link android.net.NetworkInfo#isAvailable()}).</li>
 * <li>If the mobile data network is not in the connected state, but it is available,
 * we must initiate setup of the mobile data connection, and defer handling
 * the MMS transaction until the connection is established.</li>
 * </ul>
 */
public class TransactionService extends Service implements Observer {
    private static final String TAG = "TransactionService";

    /**
     * Used to identify notification intents broadcasted by the
     * TransactionService when a Transaction is completed.
     */
    public static final String TRANSACTION_COMPLETED_ACTION =
            "android.intent.action.TRANSACTION_COMPLETED_ACTION";

    /**
     * Action for the Intent which is sent by Alarm service to launch
     * TransactionService.
     */
    public static final String ACTION_ONALARM = "android.intent.action.ACTION_ONALARM";

    /**
     * Used as extra key in notification intents broadcasted by the TransactionService
     * when a Transaction is completed (TRANSACTION_COMPLETED_ACTION intents).
     * Allowed values for this key are: TransactionState.INITIALIZED,
     * TransactionState.SUCCESS, TransactionState.FAILED.
     */
    public static final String STATE = "state";

    /**
     * Used as extra key in notification intents broadcasted by the TransactionService
     * when a Transaction is completed (TRANSACTION_COMPLETED_ACTION intents).
     * Allowed values for this key are any valid content uri.
     */
    public static final String STATE_URI = "uri";

    private static final int EVENT_TRANSACTION_REQUEST = 1;
    private static final int EVENT_DATA_STATE_CHANGED = 2;
    private static final int EVENT_CONTINUE_MMS_CONNECTIVITY = 3;
    private static final int EVENT_HANDLE_NEXT_PENDING_TRANSACTION = 4;
    private static final int EVENT_QUIT = 100;
    
    private static final int TOAST_MSG_QUEUED = 1;
    private static final int TOAST_DOWNLOAD_LATER = 2;
    private static final int TOAST_NONE = -1;

    // How often to extend the use of the MMS APN while a transaction
    // is still being processed.
    private static final int APN_EXTENSION_WAIT = 30 * 1000;

    private ServiceHandler mServiceHandler;
    private Looper mServiceLooper;
    private final ArrayList<Transaction> mProcessing  = new ArrayList<Transaction>();
    private final ArrayList<Transaction> mPending  = new ArrayList<Transaction>();
    private ConnectivityManager mConnMgr;
    private NetworkConnectivityListener mConnectivityListener;
    private PowerManager.WakeLock mWakeLock;
    
    public Handler mToastHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            String str = null;

            if (msg.what == TOAST_MSG_QUEUED) {
                str = getString(R.string.message_queued);
            } else if (msg.what == TOAST_DOWNLOAD_LATER) {
                str = getString(R.string.download_later);
            }

            if (str != null) {
            Toast.makeText(TransactionService.this, str,
                        Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override
    public void onCreate() {
        if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
            Log.v(TAG, "Creating TransactionService");
        }

        // Start up the thread running the service.  Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block.
        HandlerThread thread = new HandlerThread("TransactionService");
        thread.start();

        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);

        mConnectivityListener = new NetworkConnectivityListener();
        mConnectivityListener.registerHandler(mServiceHandler, EVENT_DATA_STATE_CHANGED);
        mConnectivityListener.startListening(this);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
            Log.v(TAG, "onStart: #" + startId + ": " + intent.getExtras());
        }

        mConnMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean noNetwork = !isNetworkAvailable();

        if (ACTION_ONALARM.equals(intent.getAction()) || (intent.getExtras() == null)) {
            // Scan database to find all pending operations.
            Cursor cursor = PduPersister.getPduPersister(this).getPendingMessages(
                    System.currentTimeMillis());
            if (cursor != null) {
                try {
                    if (cursor.getCount() == 0) {
                        if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                            Log.v(TAG, "onStart: " +
                                    "No pending messages. Stopping service.");
                        }
                        RetryScheduler.setRetryAlarm(this);
                        stopSelfIfIdle(startId);
                        return;
                    }

                    int columnIndexOfMsgId = cursor.getColumnIndexOrThrow(
                            PendingMessages.MSG_ID);
                    int columnIndexOfMsgType = cursor.getColumnIndexOrThrow(
                            PendingMessages.MSG_TYPE);
                    
                    while (cursor.moveToNext()) {
                        int msgType = cursor.getInt(columnIndexOfMsgType);
                        int transactionType = getTransactionType(msgType);
                        if (noNetwork) {
                            onNetworkUnavailable(startId, transactionType);
                            return;
                        }
                        switch (transactionType) {
                            case -1:
                                break;
                            case Transaction.RETRIEVE_TRANSACTION:
                                // If it's a transiently failed transaction,
                                // we should retry it in spite of current
                                // downloading mode.
                                int failureType = cursor.getInt(
                                        cursor.getColumnIndexOrThrow(
                                                PendingMessages.ERROR_TYPE));
                                if (!isTransientFailure(failureType)) {
                                    break;
                                }
                                // fall-through
                            default:
                                Uri uri = ContentUris.withAppendedId(
                                        Mms.CONTENT_URI,
                                        cursor.getLong(columnIndexOfMsgId));
                                TransactionBundle args = new TransactionBundle(
                                        transactionType, uri.toString());
                                // FIXME: We use the same startId for all MMs.
                                launchTransaction(startId, args, false);
                                break;
                        }
                    }
                } finally {
                    cursor.close();
                }
            } else {
                if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                    Log.v(TAG, "onStart: " +
                            "No pending messages. Stopping service.");
                }
                RetryScheduler.setRetryAlarm(this);
                stopSelfIfIdle(startId);
            }
        } else {
            if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                Log.v(TAG, "onStart: launch transaction...");
            }
            // For launching NotificationTransaction and test purpose.
            TransactionBundle args = new TransactionBundle(intent.getExtras());
            launchTransaction(startId, args, noNetwork);
        }
    }

    private void stopSelfIfIdle(int startId) {
        synchronized (mProcessing) {
            if (mProcessing.isEmpty() && mPending.isEmpty()) {
                if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                    Log.v(TAG, "stopSelfIfIdle: STOP!");
                }
                stopSelf(startId);
            }
        }
    }

    private static boolean isTransientFailure(int type) {
        return (type < MmsSms.ERR_TYPE_GENERIC_PERMANENT) && (type > MmsSms.NO_ERROR);
    }

    private boolean isNetworkAvailable() {
        NetworkInfo networkInfo = mConnMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        return networkInfo.isAvailable();
    }
    
    private int getTransactionType(int msgType) {
        switch (msgType) {
            case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND:
                return Transaction.RETRIEVE_TRANSACTION;
            case PduHeaders.MESSAGE_TYPE_READ_REC_IND:
                return Transaction.READREC_TRANSACTION;
            case PduHeaders.MESSAGE_TYPE_SEND_REQ:
                return Transaction.SEND_TRANSACTION;
            default:
                Log.w(TAG, "Unrecognized MESSAGE_TYPE: " + msgType);
                return -1;
        }
    }

    private void launchTransaction(int serviceId, TransactionBundle txnBundle, boolean noNetwork) {
        if (noNetwork) {
            Log.w(TAG, "launchTransaction: no network error!");
            onNetworkUnavailable(serviceId, txnBundle.getTransactionType());
            return;
        }
        Message msg = mServiceHandler.obtainMessage(EVENT_TRANSACTION_REQUEST);
        msg.arg1 = serviceId;
        msg.obj = txnBundle;

        if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
            Log.v(TAG, "Sending: " + msg);
        }
        mServiceHandler.sendMessage(msg);
    }

    private void onNetworkUnavailable(int serviceId, int transactionType) {
        int toastType = TOAST_NONE;
        if (transactionType == Transaction.RETRIEVE_TRANSACTION) {
            toastType = TOAST_DOWNLOAD_LATER;
        } else if (transactionType == Transaction.SEND_TRANSACTION) {
            toastType = TOAST_MSG_QUEUED;
        }
        if (toastType != TOAST_NONE) {
            mToastHandler.sendEmptyMessage(toastType);
        }
        stopSelf(serviceId);
    }

    @Override
    public void onDestroy() {
        if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
            Log.v(TAG, "Destroying TransactionService");
        }
        if (!mPending.isEmpty()) {
            Log.w(TAG, "TransactionService exiting with transaction still pending");
        }

        releaseWakeLock();
        
        mConnectivityListener.unregisterHandler(mServiceHandler);
        mConnectivityListener.stopListening();
        mConnectivityListener = null;

        mServiceHandler.sendEmptyMessage(EVENT_QUIT);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Handle status change of Transaction (The Observable).
     */
    public void update(Observable observable) {
        Transaction transaction = (Transaction) observable;
        int serviceId = transaction.getServiceId();
        try {
            synchronized (mProcessing) {
                mProcessing.remove(transaction);
                if (mPending.size() > 0) {
                    Message msg = mServiceHandler.obtainMessage(
                            EVENT_HANDLE_NEXT_PENDING_TRANSACTION,
                            transaction.getConnectionSettings());
                    mServiceHandler.sendMessage(msg);
                }
                else {
                    endMmsConnectivity();
                }
            }

            Intent intent = new Intent(TRANSACTION_COMPLETED_ACTION);
            TransactionState state = transaction.getState();
            int result = state.getState();
            intent.putExtra(STATE, result);

            switch (result) {
                case TransactionState.SUCCESS:
                    if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Transaction complete: " + serviceId);
                    }

                    intent.putExtra(STATE_URI, state.getContentUri());

                    // Notify user in the system-wide notification area.
                    switch (transaction.getType()) {
                        case Transaction.NOTIFICATION_TRANSACTION:
                        case Transaction.RETRIEVE_TRANSACTION:
                            MessagingNotification.updateNewMessageIndicator(this, true);
                            MessagingNotification.updateDownloadFailedNotification(this);
                            break;
                        case Transaction.SEND_TRANSACTION:
                            RateController.getInstance().update();
                            break;
                    }
                    break;
                case TransactionState.FAILED:
                    if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Transaction failed: " + serviceId);
                    }
                    break;
                default:
                    if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Transaction state unknown: " +
                                serviceId + " " + result);
                    }
                    break;
            }

            // Broadcast the result of the transaction.
            sendBroadcast(intent);
        } finally {
            transaction.detach(this);
            stopSelf(serviceId);
        }
    }

    private synchronized void createWakeLock() {
        // Create a new wake lock if we haven't made one yet.
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMS Connectivity");
            mWakeLock.setReferenceCounted(false);
        }
    }
    

    private void acquireWakeLock() {
        // It's okay to double-acquire this because we are not using it
        // in reference-counted mode.
        mWakeLock.acquire();
    }
    
    private void releaseWakeLock() {
        // Don't release the wake lock if it hasn't been created and acquired.
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    protected int beginMmsConnectivity() throws IOException {
        // Take a wake lock so we don't fall asleep before the message is downloaded.
        createWakeLock();
        
        int result = mConnMgr.startUsingNetworkFeature(
                ConnectivityManager.TYPE_MOBILE, Phone.FEATURE_ENABLE_MMS);

        switch (result) {
        case Phone.APN_ALREADY_ACTIVE:
        case Phone.APN_REQUEST_STARTED:
            acquireWakeLock();
            return result;
        }

        throw new IOException("Cannot establish MMS connectivity");
    }

    protected void endMmsConnectivity() {
        try {
            // cancel timer for renewal of lease
            mServiceHandler.removeMessages(EVENT_CONTINUE_MMS_CONNECTIVITY);
            if (mConnMgr != null) {
                mConnMgr.stopUsingNetworkFeature(
                        ConnectivityManager.TYPE_MOBILE, Phone.FEATURE_ENABLE_MMS);
            }
        } finally {
            releaseWakeLock();
        }
    }

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        /**
         * Handle incoming transaction requests.
         * The incoming requests are initiated by the MMSC Server or by the
         * MMS Client itself.
         */
        @Override
        public void handleMessage(Message msg) {
            if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                Log.v(TAG, "Handling incoming message: " + msg);
            }

            Transaction transaction = null;
            switch (msg.what) {
                case EVENT_QUIT:
                    getLooper().quit();
                    return;
                    
                case EVENT_CONTINUE_MMS_CONNECTIVITY:
                    synchronized (mProcessing) {
                        if (mProcessing.isEmpty()) {
                            return;
                        }
                    }

                    if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Extending MMS connectivity - still processing txn");
                    }

                    try {
                        int result = beginMmsConnectivity();
                        if (result != Phone.APN_ALREADY_ACTIVE) {
                            Log.i(TAG, "Extending MMS connectivity returned " + result +
                                    " instead of APN_ALREADY_ACTIVE");
                            // Just wait for connectivity startup without
                            // any new request of APN switch.
                            return;
                        }
                    } catch (IOException e) {
                        Log.w(TAG, "Attempt to extend use of MMS connectivity failed");
                        return;
                    }

                    // Restart timer
                    sendMessageDelayed(obtainMessage(EVENT_CONTINUE_MMS_CONNECTIVITY),
                                       APN_EXTENSION_WAIT);
                    return;

                case EVENT_DATA_STATE_CHANGED:
                    /*
                     * If we are being informed that connectivity has been established
                     * to allow MMS traffic, then proceed with processing the pending
                     * transaction, if any.
                     */
                    if (mConnectivityListener == null) {
                        return;
                    }

                    NetworkInfo info = mConnectivityListener.getNetworkInfo();
                    if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Got DATA_STATE_CHANGED event: " + info);
                    }

                    // Check availability of the mobile network.
                    if ((info == null) || (info.getType() != ConnectivityManager.TYPE_MOBILE)) {
                        return;
                    }

                    if (!info.isConnected()) {
                        return;
                    }

                    TransactionSettings settings = new TransactionSettings(
                            TransactionService.this, info.getExtraInfo());

                    // If this APN doesn't have an MMSC, wait for one that does.
                    if (TextUtils.isEmpty(settings.getMmscUrl())) {
                        return;
                    }

                    // Set a timer to keep renewing our "lease" on the MMS connection
                    sendMessageDelayed(obtainMessage(EVENT_CONTINUE_MMS_CONNECTIVITY),
                                       APN_EXTENSION_WAIT);

                    processPendingTransaction(transaction, settings);
                    return;
                
                case EVENT_TRANSACTION_REQUEST:
                    int serviceId = msg.arg1;
                    try {
                        TransactionBundle args = (TransactionBundle) msg.obj;
                        TransactionSettings transactionSettings;

                        // Set the connection settings for this transaction.
                        // If these have not been set in args, load the default settings.
                        String mmsc = args.getMmscUrl();
                        if (mmsc != null) {
                            transactionSettings = new TransactionSettings(
                                    mmsc, args.getProxyAddress(), args.getProxyPort());
                        } else {
                            transactionSettings = new TransactionSettings(
                                                    TransactionService.this, null);
                        }

                        // Create appropriate transaction
                        switch (args.getTransactionType()) {
                            case Transaction.NOTIFICATION_TRANSACTION:
                                String uri = args.getUri();
                                if (uri != null) {
                                    transaction = new NotificationTransaction(
                                            TransactionService.this, serviceId,
                                            transactionSettings, uri);
                                } else {
                                    // Now it's only used for test purpose.
                                    byte[] pushData = args.getPushData();
                                    PduParser parser = new PduParser(pushData);
                                    GenericPdu ind = parser.parse();

                                    int type = PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND;
                                    if ((ind != null) && (ind.getMessageType() == type)) {
                                        transaction = new NotificationTransaction(
                                                TransactionService.this, serviceId,
                                                transactionSettings, (NotificationInd) ind);
                                    } else {
                                        Log.e(TAG, "Invalid PUSH data.");
                                        transaction = null;
                                        return;
                                    }
                                }
                                break;
                            case Transaction.RETRIEVE_TRANSACTION:
                                transaction = new RetrieveTransaction(
                                        TransactionService.this, serviceId,
                                        transactionSettings, args.getUri());
                                break;
                            case Transaction.SEND_TRANSACTION:
                                transaction = new SendTransaction(
                                        TransactionService.this, serviceId,
                                        transactionSettings, args.getUri());
                                break;
                            case Transaction.READREC_TRANSACTION:
                                transaction = new ReadRecTransaction(
                                        TransactionService.this, serviceId,
                                        transactionSettings, args.getUri());
                                break;
                            default:
                                Log.w(TAG, "Invalid transaction type: " + serviceId);
                                transaction = null;
                                return;
                        }

                        if (!processTransaction(transaction)) {
                            transaction = null;
                            return;
                        }

                        if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                            Log.v(TAG, "Started processing of incoming message: " + msg);
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "Exception occurred while handling message: " + msg, ex);

                        if (transaction != null) {
                            try {
                                transaction.detach(TransactionService.this);
                                if (mProcessing.contains(transaction)) {
                                    synchronized (mProcessing) {
                                        mProcessing.remove(transaction);
                                    }
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "Unexpected Throwable.", t);
                            } finally {
                                // Set transaction to null to allow stopping the
                                // transaction service.
                                transaction = null;
                            }
                        }
                    } finally {
                        if (transaction == null) {
                            if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                                Log.v(TAG, "Transaction was null. Stopping self: " + serviceId);
                            }
                            endMmsConnectivity();
                            stopSelf(serviceId);
                        }
                    }
                    return;
                case EVENT_HANDLE_NEXT_PENDING_TRANSACTION:
                    processPendingTransaction(transaction, (TransactionSettings) msg.obj);
                    return;
                default:
                    Log.w(TAG, "what=" + msg.what);
                    return;
            }
        }

        private void processPendingTransaction(Transaction transaction, TransactionSettings settings) {
            int numProcessTransaction = 0;
            synchronized (mProcessing) {
                if (mPending.size() != 0) {
                    transaction = mPending.remove(0);
                }
                numProcessTransaction = mProcessing.size();
            }

            if (transaction != null) {
                if (settings != null) {
                    transaction.setConnectionSettings(settings);
                }
   
                /*
                 * Process deferred transaction
                 */
                try {
                    int serviceId = transaction.getServiceId();
                    if (processTransaction(transaction)) {
                        if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                            Log.v(TAG, "Started deferred processing of transaction: "
                                    + transaction);
                        }
                    } else {
                        transaction = null;
                        stopSelf(serviceId);
                    }
                } catch (IOException e) {
                    Log.w(TAG, e.getMessage(), e);
                }
            }
            else {
                if (numProcessTransaction == 0) {
                    endMmsConnectivity();
                }
            }
        }

        /**
         * Internal method to begin processing a transaction.
         * @param transaction the transaction. Must not be {@code null}.
         * @return {@code true} if process has begun or will begin. {@code false}
         * if the transaction should be discarded.
         * @throws IOException if connectivity for MMS traffic could not be
         * established.
         */
        private boolean processTransaction(Transaction transaction) throws IOException {
            // Check if transaction already processing
            synchronized (mProcessing) {
                for (Transaction t : mPending) {
                    if (t.isEquivalent(transaction)) {
                        if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                            Log.v(TAG, "Transaction already pending: " +
                                    transaction.getServiceId());
                        }
                        return true;
                    }
                }
                for (Transaction t : mProcessing) {
                    if (t.isEquivalent(transaction)) {
                        if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                            Log.v(TAG, "Duplicated transaction: " + transaction.getServiceId());
                        }
                        return true;
                    }
                }

                /*
                * Make sure that the network connectivity necessary
                * for MMS traffic is enabled. If it is not, we need
                * to defer processing the transaction until
                * connectivity is established.
                */
                int connectivityResult = beginMmsConnectivity();
                if (connectivityResult == Phone.APN_REQUEST_STARTED) {
                    mPending.add(transaction);
                    if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Defer txn processing pending MMS connectivity");
                    }
                    return true;
                }

                if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Adding transaction to list: " + transaction);
                }
                mProcessing.add(transaction);
            }

            if (Log.isLoggable(MmsApp.LOG_TAG, Log.VERBOSE)) {
                Log.v(TAG, "Starting transaction: " + transaction);
            }

            // Set a timer to keep renewing our "lease" on the MMS connection
            sendMessageDelayed(obtainMessage(EVENT_CONTINUE_MMS_CONNECTIVITY),
                               APN_EXTENSION_WAIT);

            // Attach to transaction and process it
            transaction.attach(TransactionService.this);
            transaction.process();
            return true;
        }
    }
}
