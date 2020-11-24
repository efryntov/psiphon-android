/*
 * Copyright (c) 2020, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.TunnelState;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.disposables.Disposable;

public class TunnelServiceInteractor {
    private static final String SERVICE_STARTING_BROADCAST_INTENT = "SERVICE_STARTING_BROADCAST_INTENT";
    private static final long MAX_BINDING_TIMEOUT_MILLIS = 2500;
    private static long lastTimeoutCheckTimestampMillis;

    private final BroadcastReceiver broadcastReceiver;
    private final Relay<TunnelState> tunnelStateRelay = BehaviorRelay.<TunnelState>create().toSerialized();
    private final Relay<Boolean> dataStatsRelay = PublishRelay.<Boolean>create().toSerialized();

    private final Messenger incomingMessenger = new Messenger(new IncomingMessageHandler(this));

    private Rx2ServiceBindingFactory serviceBindingFactory;
    private boolean isStopped = true;
    private boolean shouldRegisterAsActivity = false;
    private Disposable serviceMessengerDisposable;

    public TunnelServiceInteractor(Context context, boolean registerAsActivity) {
        this.shouldRegisterAsActivity = registerAsActivity;
        // Listen to SERVICE_STARTING_BROADCAST_INTENT broadcast that may be sent by another instance
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SERVICE_STARTING_BROADCAST_INTENT);
        this.broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    if (action.equals(SERVICE_STARTING_BROADCAST_INTENT) && !isStopped) {
                        bindTunnelService(context, intent);
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(context).registerReceiver(broadcastReceiver, intentFilter);
    }

    private static long getBindingTimeout() {
        long minBindTimeoutMillis = 1000;
        // Adjust binding timeout based on the device's SDK version.
        // The (not necessarily correct) idea is that a faster device runs a higher Android version.
        if (Build.VERSION.SDK_INT >= 26) {
            minBindTimeoutMillis = 600;
        } else if (Build.VERSION.SDK_INT >= 22) {
            minBindTimeoutMillis = 800;
        }
        // If previously recorded lastTimeoutCheckTimestampMillis then use it to determine optimal
        // binding timeout value
        if (lastTimeoutCheckTimestampMillis > 0) {
            long elapsed = SystemClock.elapsedRealtime() - lastTimeoutCheckTimestampMillis;
            // Calculate binding timeout based on the API level
            return Math.max(MAX_BINDING_TIMEOUT_MILLIS - elapsed, minBindTimeoutMillis);
        } else {
            // Otherwise return MAX_BINDING_TIMEOUT_MILLIS and record lastTimeoutCheckTimestampMillis
            // for the next time
            lastTimeoutCheckTimestampMillis = SystemClock.elapsedRealtime();
            return MAX_BINDING_TIMEOUT_MILLIS;
        }
    }

    public void onStart(Context context) {
        isStopped = false;
        tunnelStateRelay.accept(TunnelState.unknown());
        bindTunnelService(context, new Intent(context, TunnelVpnService.class));
    }

    public void onStop(Context context) {
        isStopped = true;
        tunnelStateRelay.accept(TunnelState.unknown());
        if (serviceMessengerDisposable != null && !serviceMessengerDisposable.isDisposed()) {
            sendServiceMessage(TunnelManager.ClientToServiceMessage.UNREGISTER.ordinal(), null);
            serviceMessengerDisposable.dispose();
        }
    }

    public void onDestroy(Context context) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(broadcastReceiver);
    }

    public void startTunnelService(Context context) {
        tunnelStateRelay.accept(TunnelState.unknown());
        Intent intent = new Intent(context, TunnelVpnService.class);
        try {
            // Starting with API 26 use startForegroundService
            //
            // From the docs:
            // Similar to startService(android.content.Intent), but with an implicit promise that the
            // Service will call startForeground(int, android.app.Notification) once it begins running.
            // The service is given an amount of time comparable to the ANR interval to do this, otherwise
            // the system will automatically stop the service and declare the app ANR.
            //
            // Unlike the ordinary startService(android.content.Intent), this method can be used at
            // any time, regardless of whether the app hosting the service is in a foreground state.
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                // Pre-O behavior.
                context.startService(intent);
            }
            // Send tunnel starting service broadcast to all instances so they all bind
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent.setAction(SERVICE_STARTING_BROADCAST_INTENT));

            // Record starting tunnel time to determine optimal binding timeout later
            lastTimeoutCheckTimestampMillis = SystemClock.elapsedRealtime();

        } catch (SecurityException | IllegalStateException e) {
            Utils.MyLog.g("startTunnelService failed with error: " + e);
            tunnelStateRelay.accept(TunnelState.stopped());
        }
    }

    public void stopTunnelService() {
        tunnelStateRelay.accept(TunnelState.unknown());
        sendServiceMessage(TunnelManager.ClientToServiceMessage.STOP_SERVICE.ordinal(), null);
    }

    public void scheduleRunningTunnelServiceRestart() {
        tunnelStateFlowable()
                .filter(tunnelState -> !tunnelState.isUnknown())
                .firstOrError()
                .timeout(1000, TimeUnit.MILLISECONDS)
                .toMaybe()
                .onErrorResumeNext(Maybe.empty())
                .doOnSuccess(tunnelState -> {
                    // If the service is not running do not do anything.
                    if (tunnelState.isRunning()) {
                            commandTunnelRestart();
                    }
                })
                .subscribe();
    }

    public void sendLocaleChangedMessage() {
        sendServiceMessage(TunnelManager.ClientToServiceMessage.CHANGED_LOCALE.ordinal(), null);
    }

    public Flowable<TunnelState> tunnelStateFlowable() {
        return tunnelStateRelay
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public Flowable<Boolean> dataStatsFlowable() {
        return dataStatsRelay
                .toFlowable(BackpressureStrategy.LATEST);
    }

    private void commandTunnelRestart() {
        sendServiceMessage(TunnelManager.ClientToServiceMessage.RESTART_SERVICE.ordinal(), null);
    }

    private void bindTunnelService(Context context, Intent intent) {
        if (serviceBindingFactory == null) {
            serviceBindingFactory = new Rx2ServiceBindingFactory(context, intent);
        }
        if (serviceMessengerDisposable == null || serviceMessengerDisposable.isDisposed()) {
            serviceMessengerDisposable = serviceBindingFactory.getMessengerObservable(getBindingTimeout())
                    .doOnComplete(() -> tunnelStateRelay.accept(TunnelState.stopped()))
                    .doOnComplete(() -> dataStatsRelay.accept(Boolean.FALSE))
                    .subscribe();
        }

        Bundle data = new Bundle();
        data.putBoolean(TunnelManager.IS_CLIENT_AN_ACTIVITY, shouldRegisterAsActivity);
        sendServiceMessage(TunnelManager.ClientToServiceMessage.REGISTER.ordinal(), data);
    }

    private void sendServiceMessage(int what, Bundle data) {
        if (serviceMessengerDisposable == null || serviceMessengerDisposable.isDisposed()) {
            return;
        }
        serviceBindingFactory.getMessengerObservable(getBindingTimeout())
                .take(1)
                .doOnNext(messenger -> {
                    try {
                        Message msg = Message.obtain(null, what);
                        msg.replyTo = incomingMessenger;
                        if (data != null) {
                            msg.setData(data);
                        }
                        messenger.send(msg);
                    } catch (RemoteException e) {
                        Utils.MyLog.g(String.format("sendServiceMessage failed: %s", e.getMessage()));
                    }
                })
                .subscribe();
    }

    private static TunnelManager.State getTunnelStateFromBundle(Bundle data) {
        TunnelManager.State tunnelState = new TunnelManager.State();
        if (data == null) {
            return tunnelState;
        }
        tunnelState.isRunning = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_RUNNING);
        tunnelState.isConnected = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_CONNECTED);
        tunnelState.listeningLocalSocksProxyPort = data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_SOCKS_PROXY_PORT);
        tunnelState.listeningLocalHttpProxyPort = data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT);
        tunnelState.clientRegion = data.getString(TunnelManager.DATA_TUNNEL_STATE_CLIENT_REGION);
        tunnelState.sponsorId = data.getString(TunnelManager.DATA_TUNNEL_STATE_SPONSOR_ID);
        ArrayList<String> homePages = data.getStringArrayList(TunnelManager.DATA_TUNNEL_STATE_HOME_PAGES);
        if (homePages != null && tunnelState.isConnected) {
            tunnelState.homePages = homePages;
        }
        return tunnelState;
    }

    private static void getDataTransferStatsFromBundle(Bundle data) {
        if (data == null) {
            return;
        }
        data.setClassLoader(DataTransferStats.DataTransferStatsBase.Bucket.class.getClassLoader());
        DataTransferStats.getDataTransferStatsForUI().m_connectedTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_CONNECTED_TIME);
        DataTransferStats.getDataTransferStatsForUI().m_totalBytesSent = data.getLong(TunnelManager.DATA_TRANSFER_STATS_TOTAL_BYTES_SENT);
        DataTransferStats.getDataTransferStatsForUI().m_totalBytesReceived = data.getLong(TunnelManager.DATA_TRANSFER_STATS_TOTAL_BYTES_RECEIVED);
        DataTransferStats.getDataTransferStatsForUI().m_slowBuckets = data.getParcelableArrayList(TunnelManager.DATA_TRANSFER_STATS_SLOW_BUCKETS);
        DataTransferStats.getDataTransferStatsForUI().m_slowBucketsLastStartTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_SLOW_BUCKETS_LAST_START_TIME);
        DataTransferStats.getDataTransferStatsForUI().m_fastBuckets = data.getParcelableArrayList(TunnelManager.DATA_TRANSFER_STATS_FAST_BUCKETS);
        DataTransferStats.getDataTransferStatsForUI().m_fastBucketsLastStartTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_FAST_BUCKETS_LAST_START_TIME);
    }

    private static class IncomingMessageHandler extends Handler {
        private final WeakReference<TunnelServiceInteractor> weakServiceInteractor;
        private final TunnelManager.ServiceToClientMessage[] scm = TunnelManager.ServiceToClientMessage.values();
        private TunnelManager.State state;


        IncomingMessageHandler(TunnelServiceInteractor serviceInteractor) {
            super(Looper.getMainLooper());
            this.weakServiceInteractor = new WeakReference<>(serviceInteractor);
        }

        @Override
        public void handleMessage(Message msg) {
            TunnelServiceInteractor tunnelServiceInteractor = weakServiceInteractor.get();
            if (tunnelServiceInteractor == null) {
                return;
            }
            if (msg.what > scm.length) {
                super.handleMessage(msg);
                return;
            }
            Bundle data = msg.getData();
            switch (scm[msg.what]) {
                case TUNNEL_CONNECTION_STATE:
                    state = getTunnelStateFromBundle(data);
                    TunnelState tunnelState;
                    if (state.isRunning) {
                        TunnelState.ConnectionData connectionData = TunnelState.ConnectionData.builder()
                                .setIsConnected(state.isConnected)
                                .setClientRegion(state.clientRegion)
                                .setClientVersion(EmbeddedValues.CLIENT_VERSION)
                                .setPropagationChannelId(EmbeddedValues.PROPAGATION_CHANNEL_ID)
                                .setSponsorId(state.sponsorId)
                                .setHttpPort(state.listeningLocalHttpProxyPort)
                                .setHomePages(state.homePages)
                                .build();
                        tunnelState = TunnelState.running(connectionData);
                    } else {
                        tunnelState = TunnelState.stopped();
                    }
                    tunnelServiceInteractor.tunnelStateRelay.accept(tunnelState);
                    break;
                case DATA_TRANSFER_STATS:
                    getDataTransferStatsFromBundle(data);
                    tunnelServiceInteractor.dataStatsRelay.accept(state.isConnected);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private static class Rx2ServiceBindingFactory {
        private final Observable<Messenger> messengerObservable;
        private ServiceConnection serviceConnection;

        Rx2ServiceBindingFactory(Context context, Intent intent) {
            this.messengerObservable = Observable.using(Connection::new,
                    (final Connection<Messenger> con) -> {
                        serviceConnection = con;
                        context.bindService(intent, con, 0);
                        return Observable.create(con);
                    },
                    __ -> unbind(context))
                    .replay(1)
                    .refCount();
        }

        Observable<Messenger> getMessengerObservable(long timeout) {
            // A wrapper for the messenger observable that just completes if the first value is not
            // emitted within {timeout} milliseconds
            return messengerObservable
                    .timeout(
                            Observable.timer(timeout, TimeUnit.MILLISECONDS),
                            ignored -> Observable.never()
                    )
                    .onErrorResumeNext(Observable.empty());
        }

        void unbind(Context context) {
            if (serviceConnection != null) {
                try {
                    context.unbindService(serviceConnection);
                    serviceConnection = null;
                } catch (java.lang.IllegalArgumentException e) {
                    // Ignore
                    // "java.lang.IllegalArgumentException: Service not registered"
                }
            }
        }

        private static class Connection<B extends Messenger> implements ServiceConnection, ObservableOnSubscribe<B> {
            private ObservableEmitter<? super B> subscriber;

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (subscriber != null && !subscriber.isDisposed() && service != null) {
                    //noinspection unchecked - we trust this one
                    subscriber.onNext((B) new Messenger(service));
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (subscriber != null && !subscriber.isDisposed()) {
                    subscriber.onComplete();
                }
            }

            @Override
            public void subscribe(ObservableEmitter<B> observableEmitter) throws Exception {
                this.subscriber = observableEmitter;
            }
        }
    }
}
