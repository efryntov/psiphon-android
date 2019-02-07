/*
 * Copyright (c) 2016, Psiphon Inc.
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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.VpnService;
import android.net.VpnService.Builder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v4.app.NotificationCompat;
import android.support.v4.util.Pair;
import android.text.TextUtils;

import com.psiphon3.PurchaseVerificationNetworkHelper;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.psiphon.PsiphonTunnel;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.observers.DisposableObserver;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.ReplaySubject;

import static android.os.Build.VERSION_CODES.LOLLIPOP;

public class TunnelManager implements PsiphonTunnel.HostService, MyLog.ILogger {
    // Android IPC messages

    // Client -> Service
    public static final int MSG_UNREGISTER = 1;
    public static final int MSG_STOP_SERVICE = 2;

    // Service -> Client
    public static final int MSG_REGISTER_RESPONSE = 3;
    public static final int MSG_KNOWN_SERVER_REGIONS = 4;
    public static final int MSG_TUNNEL_STARTING = 5;
    public static final int MSG_TUNNEL_STOPPING = 6;
    public static final int MSG_TUNNEL_CONNECTION_STATE = 7;
    public static final int MSG_DATA_TRANSFER_STATS = 8;

    public static final String INTENT_ACTION_HANDSHAKE = "com.psiphon3.psiphonlibrary.TunnelManager.HANDSHAKE";
    public static final String INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE = "com.psiphon3.psiphonlibrary.TunnelManager.SELECTED_REGION_NOT_AVAILABLE";
    public static final String INTENT_ACTION_VPN_REVOKED = "com.psiphon3.psiphonlibrary.TunnelManager.INTENT_ACTION_VPN_REVOKED";

    // Service -> Client bundle parameter names
    public static final String DATA_TUNNEL_STATE_IS_CONNECTED = "isConnected";
    public static final String DATA_TUNNEL_STATE_LISTENING_LOCAL_SOCKS_PROXY_PORT = "listeningLocalSocksProxyPort";
    public static final String DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT = "listeningLocalHttpProxyPort";
    public static final String DATA_TUNNEL_STATE_CLIENT_REGION = "clientRegion";
    public static final String DATA_TUNNEL_STATE_HOME_PAGES = "homePages";
    public static final String DATA_TRANSFER_STATS_CONNECTED_TIME = "dataTransferStatsConnectedTime";
    public static final String DATA_TRANSFER_STATS_TOTAL_BYTES_SENT = "dataTransferStatsTotalBytesSent";
    public static final String DATA_TRANSFER_STATS_TOTAL_BYTES_RECEIVED = "dataTransferStatsTotalBytesReceived";
    public static final String DATA_TRANSFER_STATS_SLOW_BUCKETS = "dataTransferStatsSlowBuckets";
    public static final String DATA_TRANSFER_STATS_SLOW_BUCKETS_LAST_START_TIME = "dataTransferStatsSlowBucketsLastStartTime";
    public static final String DATA_TRANSFER_STATS_FAST_BUCKETS = "dataTransferStatsFastBuckets";
    public static final String DATA_TRANSFER_STATS_FAST_BUCKETS_LAST_START_TIME = "dataTransferStatsFastBucketsLastStartTime";

    // Extras in handshake intent
    public static final String DATA_HANDSHAKE_IS_RECONNECT = "isReconnect";

    // Extras in start service intent (Client -> Service)
    public static final String DATA_TUNNEL_CONFIG_HANDSHAKE_PENDING_INTENT = "tunnelConfigHandshakePendingIntent";
    public static final String DATA_TUNNEL_CONFIG_NOTIFICATION_PENDING_INTENT = "tunnelConfigNotificationPendingIntent";
    public static final String DATA_TUNNEL_CONFIG_REGION_NOT_AVAILABLE_PENDING_INTENT = "tunnelConfigRegionNotAvailablePendingIntent";
    public static final String DATA_TUNNEL_CONFIG_WHOLE_DEVICE = "tunnelConfigWholeDevice";
    public static final String DATA_TUNNEL_CONFIG_VPN_REVOKED_PENDING_INTENT = "tunnelConfigVpnRevokedPendingIntent";
    public static final String DATA_TUNNEL_CONFIG_EGRESS_REGION = "tunnelConfigEgressRegion";
    public static final String DATA_TUNNEL_CONFIG_DISABLE_TIMEOUTS = "tunnelConfigDisableTimeouts";
    public static final String CLIENT_MESSENGER = "incomingClientMessenger";
    public static final String DATA_PURCHASE_ID = "purchaseId";
    public static final String DATA_PURCHASE_TOKEN = "purchaseToken";
    public static final String DATA_PURCHASE_IS_SUBSCRIPTION = "purchaseIsSubscription";
    static final String PREFERENCE_PURCHASE_AUTHORIZATION_ID = "preferencePurchaseAuthorization";
    static final String PREFERENCE_PURCHASE_TOKEN = "preferencePurchaseToken";

    // a snapshot of all authorizations pulled by getPsiphonConfig
    private static List<Authorization> tunnelConfigAuthorizations;


    // Tunnel config, received from the client.
    public static class Config {
        PendingIntent handshakePendingIntent = null;
        PendingIntent notificationPendingIntent = null;
        PendingIntent regionNotAvailablePendingIntent = null;
        PendingIntent vpnRevokedPendingIntent = null;
        boolean wholeDevice = false;
        String egressRegion = PsiphonConstants.REGION_CODE_ANY;
        boolean disableTimeouts = false;
        String sponsorId = EmbeddedValues.SPONSOR_ID;
    }

    private Config m_tunnelConfig = new Config();

    // Shared tunnel state, sent to the client in the HANDSHAKE
    // intent and various state-related Messages.
    public static class State {
        boolean isConnected = false;
        int listeningLocalSocksProxyPort = 0;
        int listeningLocalHttpProxyPort = 0;
        String clientRegion;
        ArrayList<String> homePages = new ArrayList<>();
    }

    private State m_tunnelState = new State();

    private NotificationManager mNotificationManager = null;
    private NotificationCompat.Builder mNotificationBuilder = null;
    private Service m_parentService = null;
    private boolean m_serviceDestroyed = false;
    private boolean m_firstStart = true;
    private CountDownLatch m_tunnelThreadStopSignal;
    private Thread m_tunnelThread;
    private AtomicBoolean m_isReconnect;
    private AtomicBoolean m_isStopping;
    private PsiphonTunnel m_tunnel = null;
    private String m_lastUpstreamProxyErrorMessage;
    private Handler m_Handler = new Handler();

    public enum PurchaseAuthorizationStatus {
        EMPTY,
        ACTIVE,
        REJECTED
    }

    public enum PurchaseVerificationAction {
        NO_ACTION,
        RESTART_AS_NON_SUBSCRIBER,
        RESTART_AS_SUBSCRIBER
    }

    private class Purchase {
        String id;
        String token;
        boolean isSubscription;

        public Purchase(String id, String token, boolean isSubscription) {
            this.id = id;
            this.token = token;
            this.isSubscription = isSubscription;
        }
    }

    private ReplaySubject<PurchaseAuthorizationStatus> m_activeAuthorizationSubject;
    private ReplaySubject<Boolean> m_tunnelConnectedSubject;
    private ReplaySubject<Purchase> m_purchaseSubject;
    private CompositeDisposable m_compositeDisposable;
    private String m_expiredPurchaseToken;


    public TunnelManager(Service parentService) {
        m_parentService = parentService;
        m_isReconnect = new AtomicBoolean(false);
        m_isStopping = new AtomicBoolean(false);
        m_tunnel = PsiphonTunnel.newPsiphonTunnel(this);
        m_tunnelConnectedSubject = ReplaySubject.createWithSize(1);
        m_activeAuthorizationSubject = ReplaySubject.createWithSize(1);
        m_purchaseSubject = ReplaySubject.createWithSize(1);
        m_compositeDisposable = new CompositeDisposable();
    }

    // Implementation of android.app.Service.onStartCommand
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String NOTIFICATION_CHANNEL_ID = "psiphon_notification_channel";

        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager) m_parentService.getSystemService(Context.NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel notificationChannel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID, m_parentService.getText(R.string.psiphon_service_notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                mNotificationManager.createNotificationChannel(notificationChannel);
            }
        }

        if (mNotificationBuilder == null) {
            mNotificationBuilder = new NotificationCompat.Builder(m_parentService, NOTIFICATION_CHANNEL_ID);
        }

        if (intent.hasExtra(TunnelManager.DATA_PURCHASE_ID)) {
            m_tunnelConfig.sponsorId = BuildConfig.SUBSCRIPTION_SPONSOR_ID;

            String purchaseId = intent.getStringExtra(TunnelManager.DATA_PURCHASE_ID);
            String purchaseToken = intent.getStringExtra(TunnelManager.DATA_PURCHASE_TOKEN);
            boolean isSubscription = intent.getBooleanExtra(TunnelManager.DATA_PURCHASE_IS_SUBSCRIPTION, false);
            Purchase purchase = new Purchase(purchaseId, purchaseToken, isSubscription);
            m_purchaseSubject.onNext(purchase);
        }

        if (m_firstStart && intent != null) {
            getTunnelConfig(intent);

            // Bootstrap the connection observable
            m_tunnelConnectedSubject.onNext(Boolean.FALSE);

            m_parentService.startForeground(R.string.psiphon_service_notification_id, this.createNotification(false));

            MyLog.v(R.string.client_version, MyLog.Sensitivity.NOT_SENSITIVE, EmbeddedValues.CLIENT_VERSION);
            m_firstStart = false;
            m_tunnelThreadStopSignal = new CountDownLatch(1);
            m_tunnelThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runTunnel();
                }
            });
            m_tunnelThread.start();
        }

        if (intent != null) {
            m_outgoingMessenger = (Messenger) intent.getParcelableExtra(CLIENT_MESSENGER);
            sendClientMessage(MSG_REGISTER_RESPONSE, getTunnelStateBundle());
        }

        return Service.START_REDELIVER_INTENT;
    }

    public void onCreate() {
        // This service runs as a separate process, so it needs to initialize embedded values
        EmbeddedValues.initialize(this.getContext());

        MyLog.setLogger(this);
        startPurchaseCheckFlow();
    }

    // Implementation of android.app.Service.onDestroy
    public void onDestroy() {
        m_serviceDestroyed = true;

        stopAndWaitForTunnel();

        MyLog.unsetLogger();

        m_compositeDisposable.dispose();
    }

    public void onRevoke() {
        MyLog.w(R.string.vpn_service_revoked, MyLog.Sensitivity.NOT_SENSITIVE);

        stopAndWaitForTunnel();

        // Foreground client activity with the vpnRevokedPendingIntent in order to notify user.
        try {
            m_tunnelConfig.vpnRevokedPendingIntent.send(
                    m_parentService, 0, null);
        } catch (PendingIntent.CanceledException e) {
            MyLog.g(String.format("vpnRevokedPendingIntent failed: %s", e.getMessage()));
        }

    }

    private void stopAndWaitForTunnel() {
        if (m_tunnelThread == null) {
            return;
        }

        // signalStopService could have been called, but in case is was not, call here.
        // If signalStopService was not already called, the join may block the calling
        // thread for some time.
        signalStopService();

        try {
            m_tunnelThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        m_tunnelThreadStopSignal = null;
        m_tunnelThread = null;
    }

    // signalStopService signals the runTunnel thread to stop. The thread will
    // self-stop the service. This is the preferred method for stopping the
    // Psiphon tunnel service:
    // 1. VpnService doesn't respond to stopService calls
    // 2. The UI will not block while waiting for stopService to return
    public void signalStopService() {
        if (m_tunnelThreadStopSignal != null) {
            m_tunnelThreadStopSignal.countDown();
        }
    }

    private void getTunnelConfig(Intent intent) {
        m_tunnelConfig.handshakePendingIntent = intent.getParcelableExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_HANDSHAKE_PENDING_INTENT);

        m_tunnelConfig.notificationPendingIntent = intent.getParcelableExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_NOTIFICATION_PENDING_INTENT);

        m_tunnelConfig.regionNotAvailablePendingIntent = intent.getParcelableExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_REGION_NOT_AVAILABLE_PENDING_INTENT);

        m_tunnelConfig.vpnRevokedPendingIntent = intent.getParcelableExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_VPN_REVOKED_PENDING_INTENT);

        m_tunnelConfig.wholeDevice = intent.getBooleanExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_WHOLE_DEVICE, false);

        m_tunnelConfig.egressRegion = intent.getStringExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_EGRESS_REGION);

        m_tunnelConfig.disableTimeouts = intent.getBooleanExtra(
                TunnelManager.DATA_TUNNEL_CONFIG_DISABLE_TIMEOUTS, false);
    }

    private Notification createNotification(boolean alert) {
        int contentTextID;
        int iconID;
        CharSequence ticker = null;

        if (m_tunnelState.isConnected) {
            if (m_tunnelConfig.wholeDevice) {
                contentTextID = R.string.psiphon_running_whole_device;
            } else {
                contentTextID = R.string.psiphon_running_browser_only;
            }
            iconID = R.drawable.notification_icon_connected;
        } else {
            contentTextID = R.string.psiphon_service_notification_message_connecting;
            ticker = m_parentService.getText(R.string.psiphon_service_notification_message_connecting);
            iconID = R.drawable.notification_icon_connecting_animation;
        }

        String notificationTitle = m_parentService.getText(R.string.app_name_psiphon_pro).toString();
        String notificationText = m_parentService.getText(contentTextID).toString();

        mNotificationBuilder
                .setSmallIcon(iconID)
                .setContentTitle(notificationTitle)
                .setContentText(notificationText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(notificationText))
                .setTicker(ticker)
                .setContentIntent(m_tunnelConfig.notificationPendingIntent);

        Notification notification = mNotificationBuilder.build();

        if (alert) {
            final AppPreferences multiProcessPreferences = new AppPreferences(getContext());

            if (multiProcessPreferences.getBoolean(
                    m_parentService.getString(R.string.preferenceNotificationsWithSound), false)) {
                notification.defaults |= Notification.DEFAULT_SOUND;
            }
            if (multiProcessPreferences.getBoolean(
                    m_parentService.getString(R.string.preferenceNotificationsWithVibrate), false)) {
                notification.defaults |= Notification.DEFAULT_VIBRATE;
            }
        }

        return notification;
    }

    private void setIsConnected(boolean isConnected) {
        boolean alert = (isConnected != m_tunnelState.isConnected);

        m_tunnelState.isConnected = isConnected;

        doNotify(alert);
    }

    private synchronized void doNotify(boolean alert) {
        // Don't update notification to CONNECTING, etc., when a stop was commanded.
        if (!m_serviceDestroyed && !m_isStopping.get()) {
            if (mNotificationManager != null) {
                mNotificationManager.notify(
                        R.string.psiphon_service_notification_id,
                        createNotification(alert));
            }
        }
    }

    private boolean isSelectedEgressRegionAvailable(List<String> availableRegions) {
        String selectedEgressRegion = m_tunnelConfig.egressRegion;
        if (selectedEgressRegion == null || selectedEgressRegion.equals(PsiphonConstants.REGION_CODE_ANY)) {
            // User region is either not set or set to 'Best Performance', do nothing
            return true;
        }

        for (String regionCode : availableRegions) {
            if (selectedEgressRegion.equals(regionCode)) {
                return true;
            }
        }
        return false;
    }

    public IBinder onBind(Intent intent) {
        return m_incomingMessenger.getBinder();
    }

    private final Messenger m_incomingMessenger = new Messenger(
            new IncomingMessageHandler(this));
    private Messenger m_outgoingMessenger = null;

    private static class IncomingMessageHandler extends Handler {
        private final WeakReference<TunnelManager> mTunnelManager;

        IncomingMessageHandler(TunnelManager manager) {
            mTunnelManager = new WeakReference<>(manager);
        }

        @Override
        public void handleMessage(Message msg) {
            TunnelManager manager = mTunnelManager.get();
            switch (msg.what) {
                case TunnelManager.MSG_UNREGISTER:
                    if (manager != null) {
                        manager.m_outgoingMessenger = null;
                    }
                    break;

                case TunnelManager.MSG_STOP_SERVICE:
                    if (manager != null) {
                        manager.signalStopService();
                    }
                    break;

                default:
                    super.handleMessage(msg);
            }
        }
    }

    private void sendClientMessage(int what, Bundle data) {
        if (m_incomingMessenger == null || m_outgoingMessenger == null) {
            return;
        }
        try {
            Message msg = Message.obtain(null, what);
            if (data != null) {
                msg.setData(data);
            }
            m_outgoingMessenger.send(msg);
        } catch (RemoteException e) {
            // The receiver is dead, do not try to send more messages
            m_outgoingMessenger = null;
        }
    }

    private void sendHandshakeIntent(boolean isReconnect) {
        // Only send this intent if the StatusActivity is
        // in the foreground, or if this is an initial connection
        // so we can show the home tab.
        // If it isn't and we sent the intent, the activity will
        // interrupt the user in some other app.
        // It's too late to do this check in StatusActivity
        // onNewIntent.

        final AppPreferences multiProcessPreferences = new AppPreferences(getContext());
        if (multiProcessPreferences.getBoolean(m_parentService.getString(R.string.status_activity_foreground), false) ||
                !isReconnect) {
            Intent fillInExtras = new Intent();
            fillInExtras.putExtra(DATA_HANDSHAKE_IS_RECONNECT, isReconnect);
            fillInExtras.putExtras(getTunnelStateBundle());
            try {
                m_tunnelConfig.handshakePendingIntent.send(
                        m_parentService, 0, fillInExtras);
            } catch (PendingIntent.CanceledException e) {
                MyLog.g(String.format("sendHandshakeIntent failed: %s", e.getMessage()));
            }
        }
    }

    private Bundle getTunnelStateBundle() {
        Bundle data = new Bundle();
        data.putBoolean(DATA_TUNNEL_STATE_IS_CONNECTED, m_tunnelState.isConnected);
        data.putInt(DATA_TUNNEL_STATE_LISTENING_LOCAL_SOCKS_PROXY_PORT, m_tunnelState.listeningLocalSocksProxyPort);
        data.putInt(DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT, m_tunnelState.listeningLocalHttpProxyPort);
        data.putString(DATA_TUNNEL_STATE_CLIENT_REGION, m_tunnelState.clientRegion);
        data.putStringArrayList(DATA_TUNNEL_STATE_HOME_PAGES, m_tunnelState.homePages);
        return data;
    }

    private Bundle getDataTransferStatsBundle() {
        Bundle data = new Bundle();
        data.putLong(DATA_TRANSFER_STATS_CONNECTED_TIME, DataTransferStats.getDataTransferStatsForService().m_connectedTime);
        data.putLong(DATA_TRANSFER_STATS_TOTAL_BYTES_SENT, DataTransferStats.getDataTransferStatsForService().m_totalBytesSent);
        data.putLong(DATA_TRANSFER_STATS_TOTAL_BYTES_RECEIVED, DataTransferStats.getDataTransferStatsForService().m_totalBytesReceived);
        data.putParcelableArrayList(DATA_TRANSFER_STATS_SLOW_BUCKETS, DataTransferStats.getDataTransferStatsForService().m_slowBuckets);
        data.putLong(DATA_TRANSFER_STATS_SLOW_BUCKETS_LAST_START_TIME, DataTransferStats.getDataTransferStatsForService().m_slowBucketsLastStartTime);
        data.putParcelableArrayList(DATA_TRANSFER_STATS_FAST_BUCKETS, DataTransferStats.getDataTransferStatsForService().m_fastBuckets);
        data.putLong(DATA_TRANSFER_STATS_FAST_BUCKETS_LAST_START_TIME, DataTransferStats.getDataTransferStatsForService().m_fastBucketsLastStartTime);
        return data;
    }

    private final static String LEGACY_SERVER_ENTRY_FILENAME = "psiphon_server_entries.json";
    private final static int MAX_LEGACY_SERVER_ENTRIES = 100;

    public static String getServerEntries(Context context) {
        StringBuilder list = new StringBuilder();

        for (String encodedServerEntry : EmbeddedValues.EMBEDDED_SERVER_LIST) {
            list.append(encodedServerEntry);
            list.append("\n");
        }

        // Import legacy server entries
        try {
            FileInputStream file = context.openFileInput(LEGACY_SERVER_ENTRY_FILENAME);
            BufferedReader reader = new BufferedReader(new InputStreamReader(file));
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            file.close();
            JSONObject obj = new JSONObject(json.toString());
            JSONArray jsonServerEntries = obj.getJSONArray("serverEntries");

            // MAX_LEGACY_SERVER_ENTRIES ensures the list we pass through to tunnel-core
            // is unlikely to trigger an OutOfMemoryError
            for (int i = 0; i < jsonServerEntries.length() && i < MAX_LEGACY_SERVER_ENTRIES; i++) {
                list.append(jsonServerEntries.getString(i));
                list.append("\n");
            }

            // Don't need to repeat the import again
            context.deleteFile(LEGACY_SERVER_ENTRY_FILENAME);
        } catch (FileNotFoundException e) {
            // pass
        } catch (IOException | JSONException | OutOfMemoryError e) {
            MyLog.g(String.format("prepareServerEntries failed: %s", e.getMessage()));
        }

        return list.toString();
    }

    private Handler sendDataTransferStatsHandler = new Handler();
    private final long sendDataTransferStatsIntervalMs = 1000;
    private Runnable sendDataTransferStats = new Runnable() {
        @Override
        public void run() {
            sendClientMessage(MSG_DATA_TRANSFER_STATS, getDataTransferStatsBundle());
            sendDataTransferStatsHandler.postDelayed(this, sendDataTransferStatsIntervalMs);
        }
    };

    private Handler periodicMaintenanceHandler = new Handler();
    private final long periodicMaintenanceIntervalMs = 12 * 60 * 60 * 1000;
    private final Runnable periodicMaintenance = new Runnable() {
        @Override
        public void run() {
            LoggingProvider.LogDatabaseHelper.truncateLogs(getContext(), false);
            periodicMaintenanceHandler.postDelayed(this, periodicMaintenanceIntervalMs);
        }
    };

    private void runTunnel() {
        Utils.initializeSecureRandom();

        m_isStopping.set(false);
        m_isReconnect.set(false);

        // Notify if an upgrade has already been downloaded and is waiting for install
        UpgradeManager.UpgradeInstaller.notifyUpgrade(m_parentService);

        sendClientMessage(MSG_TUNNEL_STARTING, null);

        MyLog.v(R.string.current_network_type, MyLog.Sensitivity.NOT_SENSITIVE, Utils.getNetworkTypeName(m_parentService));

        MyLog.v(R.string.starting_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

        m_tunnelState.homePages.clear();

        DataTransferStats.getDataTransferStatsForService().startSession();
        sendDataTransferStatsHandler.postDelayed(sendDataTransferStats, sendDataTransferStatsIntervalMs);
        periodicMaintenanceHandler.postDelayed(periodicMaintenance, periodicMaintenanceIntervalMs);

        boolean runVpn =
                m_tunnelConfig.wholeDevice &&
                        Utils.hasVpnService() &&
                        // Guard against trying to start WDM mode when the global option flips while starting a TunnelService
                        (m_parentService instanceof TunnelVpnService);

        try {
            if (runVpn) {
                if (!m_tunnel.startRouting()) {
                    throw new PsiphonTunnel.Exception("application is not prepared or revoked");
                }
                MyLog.v(R.string.vpn_service_running, MyLog.Sensitivity.NOT_SENSITIVE);
            }

            m_tunnel.startTunneling(getServerEntries(m_parentService));

            try {
                m_tunnelThreadStopSignal.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            m_isStopping.set(true);

        } catch (PsiphonTunnel.Exception e) {
            MyLog.e(R.string.start_tunnel_failed, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } finally {

            MyLog.v(R.string.stopping_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

            sendClientMessage(MSG_TUNNEL_STOPPING, null);

            // If a client registers with the service at this point, it should be given a tunnel
            // state bundle (specifically DATA_TUNNEL_STATE_IS_CONNECTED) that is consistent with
            // the MSG_TUNNEL_STOPPING message it just received
            setIsConnected(false);
            m_tunnelConnectedSubject.onNext(Boolean.FALSE);

            m_tunnel.stop();

            periodicMaintenanceHandler.removeCallbacks(periodicMaintenance);
            sendDataTransferStatsHandler.removeCallbacks(sendDataTransferStats);
            DataTransferStats.getDataTransferStatsForService().stop();

            MyLog.v(R.string.stopped_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

            // Stop service
            m_parentService.stopForeground(true);
            m_parentService.stopSelf();
        }
    }

    private void restartTunnel() {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                m_isReconnect.set(false);
                try {
                    m_tunnel.restartPsiphon();
                } catch (PsiphonTunnel.Exception e) {
                    MyLog.e(R.string.start_tunnel_failed, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                }
            }
        });
    }

    @Override
    public String getAppName() {
        return m_parentService.getString(R.string.app_name_psiphon_pro);
    }

    @Override
    public Context getContext() {
        return m_parentService;
    }

    @Override
    public VpnService getVpnService() {
        return ((TunnelVpnService) m_parentService);
    }

    @Override
    public Builder newVpnServiceBuilder() {
        Builder vpnBuilder = ((TunnelVpnService) m_parentService).newBuilder();
        if (Build.VERSION.SDK_INT >= LOLLIPOP) {
            final AppPreferences multiProcessPreferences = new AppPreferences(getContext());
            Resources res = getContext().getResources();


            // Check for individual apps to exclude
            String excludedAppsFromPreference = multiProcessPreferences.getString(res.getString(R.string.preferenceExcludeAppsFromVpnString), "");
            List<String> excludedApps;
            if (excludedAppsFromPreference.isEmpty()) {
                excludedApps = Collections.emptyList();
                MyLog.v(R.string.no_apps_excluded, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS);
            } else {
                excludedApps = Arrays.asList(excludedAppsFromPreference.split(","));
            }
            ;

            if (excludedApps.size() > 0) {
                for (String packageId : excludedApps) {
                    try {
                        vpnBuilder.addDisallowedApplication(packageId);
                        MyLog.v(R.string.individual_app_excluded, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, packageId);
                    } catch (PackageManager.NameNotFoundException e) {
                        // Because the list that is passed in to this builder was created by
                        // a PackageManager instance, this exception should never be thrown
                    }
                }
            }
        }

        return vpnBuilder;
    }

    /**
     * Create a tunnel-core config suitable for different tunnel types (i.e., the main Psiphon app
     * tunnel and the UpgradeChecker temp tunnel).
     *
     * @param context
     * @param tunnelConfig         Config values to be set in the tunnel core config.
     * @param tempTunnelName       null if not a temporary tunnel. If set, must be a valid to use in file path.
     * @param clientPlatformPrefix null if not applicable (i.e., for main Psiphon app); should be provided
     *                             for temp tunnels. Will be prepended to standard client platform value.
     * @return JSON string of config. null on error.
     */
    public static String buildTunnelCoreConfig(
            Context context,
            PsiphonTunnel tunnel,
            Config tunnelConfig,
            String tempTunnelName,
            String clientPlatformPrefix) {
        boolean temporaryTunnel = tempTunnelName != null && !tempTunnelName.isEmpty();

        JSONObject json = new JSONObject();

        try {
            String prefix = "";
            if (clientPlatformPrefix != null && !clientPlatformPrefix.isEmpty()) {
                prefix = clientPlatformPrefix;
            }

            String suffix = "";

            // Detect if device is rooted and append to the client_platform string
            if (Utils.isRooted()) {
                suffix += PsiphonConstants.ROOTED;
            }

            // Detect if this is a Play Store build
            if (EmbeddedValues.IS_PLAY_STORE_BUILD) {
                suffix += PsiphonConstants.PLAY_STORE_BUILD;
            }

            tunnel.setClientPlatformAffixes(prefix, suffix);

            json.put("ClientVersion", EmbeddedValues.CLIENT_VERSION);

            tunnelConfigAuthorizations = Authorization.geAllPersistedAuthorizations(context);

            if (tunnelConfigAuthorizations != null && tunnelConfigAuthorizations.size() > 0) {
                JSONArray jsonArray = new JSONArray();
                for (Authorization a : tunnelConfigAuthorizations) {
                    jsonArray.put(a.base64EncodedAuthorization());
                }
                json.put("Authorizations", jsonArray);
            }


            if (UpgradeChecker.upgradeCheckNeeded(context)) {

                json.put("UpgradeDownloadURLs", new JSONArray(EmbeddedValues.UPGRADE_URLS_JSON));

                json.put("UpgradeDownloadClientVersionHeader", "x-amz-meta-psiphon-client-version");

                json.put("UpgradeDownloadFilename",
                        new UpgradeManager.DownloadedUpgradeFile(context).getFullPath());
            }

            json.put("PropagationChannelId", EmbeddedValues.PROPAGATION_CHANNEL_ID);

            json.put("SponsorId", tunnelConfig.sponsorId);

            json.put("RemoteServerListURLs", new JSONArray(EmbeddedValues.REMOTE_SERVER_LIST_URLS_JSON));

            json.put("ObfuscatedServerListRootURLs", new JSONArray(EmbeddedValues.OBFUSCATED_SERVER_LIST_ROOT_URLS_JSON));

            json.put("RemoteServerListSignaturePublicKey", EmbeddedValues.REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY);

            if (UpstreamProxySettings.getUseHTTPProxy(context)) {
                if (UpstreamProxySettings.getProxySettings(context) != null) {
                    json.put("UpstreamProxyUrl", UpstreamProxySettings.getUpstreamProxyUrl(context));
                }
                json.put("UpstreamProxyCustomHeaders", UpstreamProxySettings.getUpstreamProxyCustomHeaders(context));
            }

            json.put("EmitDiagnosticNotices", true);

            // If this is a temporary tunnel (like for UpgradeChecker) we need to override some of
            // the implicit config values.
            if (temporaryTunnel) {
                File tempTunnelDir = new File(context.getFilesDir(), tempTunnelName);
                if (!tempTunnelDir.exists()
                        && !tempTunnelDir.mkdirs()) {
                    // Failed to create DB directory
                    return null;
                }

                // On Android, these directories must be set to the app private storage area.
                // The Psiphon library won't be able to use its current working directory
                // and the standard temporary directories do not exist.
                json.put("DataStoreDirectory", tempTunnelDir.getAbsolutePath());

                File remoteServerListDownload = new File(tempTunnelDir, "remote_server_list");
                json.put("RemoteServerListDownloadFilename", remoteServerListDownload.getAbsolutePath());

                File oslDownloadDir = new File(tempTunnelDir, "osl");
                if (!oslDownloadDir.exists()
                        && !oslDownloadDir.mkdirs()) {
                    // Failed to create osl directory
                    // TODO: proceed anyway?
                    return null;
                }
                json.put("ObfuscatedServerListDownloadDirectory", oslDownloadDir.getAbsolutePath());

                // This number is an arbitrary guess at what might be the "best" balance between
                // wake-lock-battery-burning and successful upgrade downloading.
                // Note that the fall-back untunneled upgrade download doesn't start for 30 secs,
                // so we should be waiting longer than that.
                json.put("EstablishTunnelTimeoutSeconds", 300);

                json.put("TunnelWholeDevice", 0);
                json.put("EgressRegion", "");
            } else {
                String egressRegion = tunnelConfig.egressRegion;
                MyLog.g("EgressRegion", "regionCode", egressRegion);
                json.put("EgressRegion", egressRegion);
            }

            if (tunnelConfig.disableTimeouts) {
                //disable timeouts
                MyLog.g("DisableTimeouts", "disableTimeouts", true);
                json.put("NetworkLatencyMultiplier", 3.0);
            } else {
                // TEMP: The default value is too aggressive, it will be adjusted in a future release
                json.put("TunnelPortForwardTimeoutSeconds", 30);
            }

            return json.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    // Creates an observable from ReplaySubject of size(1) that holds the last connection state
    // value. The result is additionally filtered to output only distinct consecutive values.
    // Emits its current value to every new subscriber.
    private Observable<Boolean> connectionObservable() {
        return m_tunnelConnectedSubject
                .hide()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged();
    }

    // Creates an observable from ReplaySubject of size(1) that holds the last authorization status
    // value. The result is additionally filtered to output only distinct consecutive values.
    // Emits its current value to every new subscriber.
    private Observable<PurchaseAuthorizationStatus> authorizationStatusObservable() {
        return m_activeAuthorizationSubject
                .hide()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged();
    }

    // Creates an observable from ReplaySubject of size(1) that holds the last purchase data
    // value. The result is additionally filtered to output only distinct consecutive values.
    // Emits its current value to every new subscriber.
    private Observable<Purchase> purchaseObservable() {
        return m_purchaseSubject
                .hide()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged((purchase, purchase2) -> purchase.token.equals(purchase2.token));
    }

    private void startPurchaseCheckFlow() {
        m_compositeDisposable.clear();
        m_compositeDisposable.add(
                purchaseObservable()
//                        .doOnNext(purchase -> Log.d("PurchaseCheckFlow", "got new purchase: " + purchase.id + " " + purchase.token))
                        .switchMap(purchase ->
                                connectionObservable().map(isConnected -> new Pair(isConnected, purchase))
                        )
                        .doOnNext(pair -> {
                            Purchase purchase = (Purchase) pair.second;
                            if (!hasAuthorizationIdForPurchase(purchase)) {
                                persistPurchaseTokenAndAuthorizationId(purchase.token, "");
                                m_activeAuthorizationSubject.onNext(PurchaseAuthorizationStatus.EMPTY);
                            }
                        })
//                        .doOnNext(pair -> Log.d("PurchaseCheckFlow", "got new connection status : " + pair.first))
                        .switchMap(pair -> {
                                    Boolean isConnected = (Boolean) pair.first;
                                    Purchase purchase = (Purchase) pair.second;
                                    Boolean isExpiredPurchase = TextUtils.equals(m_expiredPurchaseToken, purchase.token);

                                    Observable<PurchaseAuthorizationStatus> observable = isConnected && !isExpiredPurchase ?
                                            authorizationStatusObservable() :
                                            Observable.empty();

                                    return observable.map(status -> new Pair(status, purchase));
                                }
                        )
//                        .doOnNext(pair -> Log.d("PurchaseCheckFlow", "got new PurchaseAuthorizationStatus status: " + pair.first))
                        .switchMap(pair -> {
                            PurchaseAuthorizationStatus status = (PurchaseAuthorizationStatus) pair.first;
                            Purchase purchase = (Purchase) pair.second;
                            if (status == PurchaseAuthorizationStatus.EMPTY || status == PurchaseAuthorizationStatus.REJECTED) {
                                MyLog.g("TunnelManager::startPurchaseCheckFlow: will fetch new authorization");

                                PurchaseVerificationNetworkHelper purchaseVerificationNetworkHelper =
                                        new PurchaseVerificationNetworkHelper.Builder(getContext())
                                                .withProductId(purchase.id)
                                                .withIsSubscription(purchase.isSubscription)
                                                .withPurchaseToken(purchase.token)
                                                .withHttpProxyPort(m_parentService instanceof TunnelService ? m_tunnelState.listeningLocalHttpProxyPort : 0)
                                                .build();

                                return purchaseVerificationNetworkHelper.fetchAuthorizationObservable()
                                        .map(json -> {
                                            String encodedAuth = new JSONObject(json).getString("signed_authorization");
                                                    Authorization authorization = Authorization.fromBase64Encoded(encodedAuth);
                                                    if (authorization == null) {
                                                        persistPurchaseTokenAndAuthorizationId(purchase.token, "");
                                                        // Mark the purchase token as expired which means
                                                        // no action will be taken next time we receive the same token
                                                        // from main activity
                                                        m_expiredPurchaseToken = purchase.token;
                                                        return PurchaseVerificationAction.RESTART_AS_NON_SUBSCRIBER;
                                                    } else {
                                                        persistPurchaseTokenAndAuthorizationId(purchase.token, authorization.Id());
                                                        Authorization.storeAuthorization(getContext(), authorization);
                                                        return PurchaseVerificationAction.RESTART_AS_SUBSCRIBER;
                                                    }
                                                }
                                        )
                                        .doOnError(e -> MyLog.g(String.format("PurchaseVerificationNetworkHelper::fetchAuthorizationObservable: failed with error: %s",
                                                e.getMessage())))
                                        .onErrorResumeNext(Observable.just(PurchaseVerificationAction.NO_ACTION));
                            } else {
                                return Observable.just(PurchaseVerificationAction.NO_ACTION);
                            }
                        })
//                        .doOnNext(action -> Log.d("PurchaseCheckFlow", "got new PurchaseVerificationAction : " + action))
                        .subscribeWith(new DisposableObserver<PurchaseVerificationAction>() {
                            @Override
                            public void onNext(PurchaseVerificationAction action) {
                                if (action == PurchaseVerificationAction.NO_ACTION) {
                                    return;
                                }

                                if (action == PurchaseVerificationAction.RESTART_AS_NON_SUBSCRIBER) {
                                    MyLog.g("TunnelManager::startPurchaseCheckFlow: will restart as a non subscriber");
                                    m_tunnelConfig.sponsorId = EmbeddedValues.SPONSOR_ID;
                                } else if (action == PurchaseVerificationAction.RESTART_AS_SUBSCRIBER) {
                                    MyLog.g("TunnelManager::startPurchaseCheckFlow: will restart as a subscriber");
                                    m_tunnelConfig.sponsorId = BuildConfig.SUBSCRIPTION_SPONSOR_ID;
                                }
                                restartTunnel();
                            }

                            @Override
                            public void onError(Throwable e) {
                                MyLog.g(String.format("TunnelManager::startPurchaseCheckFlow: received unhandled subscription error: %s, with message: %s",
                                        e.getClass().getCanonicalName(), e.getMessage()));
                            }

                            @Override
                            public void onComplete() {
                            }
                        }));
    }

    private boolean hasAuthorizationIdForPurchase(Purchase purchase) {
        final AppPreferences mp = new AppPreferences(getContext());
        String authorizationId = mp.getString(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");
        String purchaseToken = mp.getString(PREFERENCE_PURCHASE_TOKEN, "");
        if (!TextUtils.isEmpty(authorizationId)
                && purchase.token.equals(purchaseToken)) {
            return true;
        }
        return false;
    }

    private static String getPersistedPurchaseAuthorizationId(Context context) {
        final AppPreferences mp = new AppPreferences(context);
        String authorizationId = mp.getString(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");
        return authorizationId;
    }

    private void persistPurchaseTokenAndAuthorizationId(String purchaseToken, String authorizationId) {
        final AppPreferences mp = new AppPreferences(getContext());
        mp.put(PREFERENCE_PURCHASE_TOKEN, purchaseToken);
        mp.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, authorizationId);
    }

    @Override
    public String getPsiphonConfig() {
        String config = buildTunnelCoreConfig(m_parentService, m_tunnel, m_tunnelConfig, null, null);
        return config == null ? "" : config;
    }

    @Override
    public void onDiagnosticMessage(final String message) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.g(message, "msg", message);
            }
        });
    }

    @Override
    public void onAvailableEgressRegions(final List<String> regions) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                // regions are already sorted alphabetically by tunnel core
                new AppPreferences(getContext()).put(RegionAdapter.KNOWN_REGIONS_PREFERENCE, TextUtils.join(",", regions));

                if (!isSelectedEgressRegionAvailable(regions)) {
                    // command service stop
                    signalStopService();

                    // Send REGION_NOT_AVAILABLE intent,
                    // Activity intent handler will show "Region not available" toast and populate
                    // the region selector with new available regions
                    try {
                        m_tunnelConfig.regionNotAvailablePendingIntent.send(
                                m_parentService, 0, null);
                    } catch (PendingIntent.CanceledException e) {
                        MyLog.g(String.format("regionNotAvailablePendingIntent failed: %s", e.getMessage()));
                    }

                }
                // Notify activity so it has a chance to update region selector values
                sendClientMessage(MSG_KNOWN_SERVER_REGIONS, null);
            }
        });
    }

    @Override
    public void onSocksProxyPortInUse(final int port) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.e(R.string.socks_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, port);
                signalStopService();
            }
        });
    }

    @Override
    public void onHttpProxyPortInUse(final int port) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.e(R.string.http_proxy_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, port);
                signalStopService();
            }
        });
    }

    @Override
    public void onListeningSocksProxyPort(final int port) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.v(R.string.socks_running, MyLog.Sensitivity.NOT_SENSITIVE, port);
                m_tunnelState.listeningLocalSocksProxyPort = port;
            }
        });
    }

    @Override
    public void onListeningHttpProxyPort(final int port) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.v(R.string.http_proxy_running, MyLog.Sensitivity.NOT_SENSITIVE, port);
                m_tunnelState.listeningLocalHttpProxyPort = port;

                final AppPreferences multiProcessPreferences = new AppPreferences(getContext());
                multiProcessPreferences.put(
                        m_parentService.getString(R.string.current_local_http_proxy_port),
                        port);
            }
        });
    }

    @Override
    public void onUpstreamProxyError(final String message) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                // Display the error message only once, and continue trying to connect in
                // case the issue is temporary.
                if (m_lastUpstreamProxyErrorMessage == null || !m_lastUpstreamProxyErrorMessage.equals(message)) {
                    MyLog.v(R.string.upstream_proxy_error, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, message);
                    m_lastUpstreamProxyErrorMessage = message;
                }
            }
        });
    }

    @Override
    public void onConnecting() {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                DataTransferStats.getDataTransferStatsForService().stop();
                m_tunnelConnectedSubject.onNext(Boolean.FALSE);

                if (!m_isStopping.get()) {
                    MyLog.v(R.string.tunnel_connecting, MyLog.Sensitivity.NOT_SENSITIVE);
                }

                setIsConnected(false);
                m_tunnelState.homePages.clear();
                Bundle data = new Bundle();
                data.putBoolean(DATA_TUNNEL_STATE_IS_CONNECTED, false);
                sendClientMessage(MSG_TUNNEL_CONNECTION_STATE, data);
            }
        });
    }

    @Override
    public void onConnected() {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                m_tunnelConnectedSubject.onNext(Boolean.TRUE);
                DataTransferStats.getDataTransferStatsForService().startConnected();

                MyLog.v(R.string.tunnel_connected, MyLog.Sensitivity.NOT_SENSITIVE);

                sendHandshakeIntent(m_isReconnect.get());
                // Any subsequent onConnecting after this first onConnect will be a reconnect.
                m_isReconnect.set(true);

                setIsConnected(true);
                Bundle data = new Bundle();
                data.putBoolean(DATA_TUNNEL_STATE_IS_CONNECTED, true);
                sendClientMessage(MSG_TUNNEL_CONNECTION_STATE, data);
            }
        });
    }

    @Override
    public void onHomepage(final String url) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                for (String homePage : m_tunnelState.homePages) {
                    if (homePage.equals(url)) {
                        return;
                    }
                }
                m_tunnelState.homePages.add(url);

                boolean showAds = false;
                for (String homePage : m_tunnelState.homePages) {
                    if (homePage.contains("psiphon_show_ads")) {
                        showAds = true;
                    }
                }
                final AppPreferences multiProcessPreferences = new AppPreferences(getContext());
                multiProcessPreferences.put(
                        m_parentService.getString(R.string.persistent_show_ads_setting),
                        showAds);
            }
        });
    }

    @Override
    public void onClientRegion(final String region) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                m_tunnelState.clientRegion = region;
            }
        });
    }

    @Override
    public void onClientUpgradeDownloaded(String filename) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                UpgradeManager.UpgradeInstaller.notifyUpgrade(m_parentService);
            }
        });
    }

    @Override
    public void onClientIsLatestVersion() {
    }

    @Override
    public void onSplitTunnelRegion(final String region) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.v(R.string.split_tunnel_region, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, region);
            }
        });
    }

    @Override
    public void onUntunneledAddress(final String address) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.v(R.string.untunneled_address, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, address);
            }
        });
    }

    @Override
    public void onBytesTransferred(final long sent, final long received) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                DataTransferStats.DataTransferStatsForService stats = DataTransferStats.getDataTransferStatsForService();
                stats.addBytesSent(sent);
                stats.addBytesReceived(received);
            }
        });
    }

    @Override
    public void onStartedWaitingForNetworkConnectivity() {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.v(R.string.waiting_for_network_connectivity, MyLog.Sensitivity.NOT_SENSITIVE);
            }
        });
    }

    @Override
    public void onExiting() {
    }

    @Override
    public void onActiveAuthorizationIDs(List<String> acceptedAuthorizationIds) {
        m_Handler.post(() -> {
            // Build a list of accepted authorizations from the authorizations snapshot.
            List<Authorization> acceptedAuthorizations = new ArrayList<>();

            for (String Id : acceptedAuthorizationIds) {
                for (Authorization a : tunnelConfigAuthorizations) {
                    if (a.Id().equals(Id)) {
                        acceptedAuthorizations.add(a);
                    }
                }
            }

            // Build a list if not accepted authorizations from the authorizations snapshot
            // by removing all elements of the  accepted authorizations list.
            List<Authorization> notAcceptedAuthorizations = tunnelConfigAuthorizations;
            notAcceptedAuthorizations.removeAll(acceptedAuthorizations);

            // Remove all not accepted authorizations from the database
            Authorization.removeAuthorizations(getContext(), notAcceptedAuthorizations);

            // Get not accepted authorization ids of speed-boost type
            // and store them to be used by PsiCash library later.
            List<String> notAcceptedSpeedBoostAuthorizationIds = new ArrayList<>();
            for (Authorization a : notAcceptedAuthorizations) {
                if (a.accessType().equals(Authorization.SPEED_BOOST_TYPE)) {
                    notAcceptedSpeedBoostAuthorizationIds.add(a.Id());
                }
            }

            // Subscription check
            String purchaseAuthorizationID = getPersistedPurchaseAuthorizationId(getContext());

            if (TextUtils.isEmpty(purchaseAuthorizationID)) {
                // There is no authorization for this purchase, do nothing
                return;
            }

            // If server hasn't accepted any authorizations or previously stored authorization id hasn't been accepted
            // then send authorizationStatusObservable() subscriber(s) a PurchaseAuthorizationStatus.REJECTED.
            if (acceptedAuthorizationIds.isEmpty() || !acceptedAuthorizationIds.contains(purchaseAuthorizationID)) {
                MyLog.g("TunnelManager::onActiveAuthorizationIDs: stored authorization has been rejected");

                // clear all persisted values too
                persistPurchaseTokenAndAuthorizationId("", "");

                m_activeAuthorizationSubject.onNext(PurchaseAuthorizationStatus.REJECTED);
                return;
            } //else
            m_activeAuthorizationSubject.onNext(PurchaseAuthorizationStatus.ACTIVE);
        });
    }
}
