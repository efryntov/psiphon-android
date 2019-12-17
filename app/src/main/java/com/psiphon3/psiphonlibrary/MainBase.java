/*
 * Copyright (c) 2019, Psiphon Inc.
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

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TabHost;
import android.widget.TabHost.OnTabChangeListener;
import android.widget.TabHost.TabSpec;
import android.widget.TextView;
import android.widget.Toast;

import com.psiphon3.StatusActivity;
import com.psiphon3.TunnelState;
import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psicash.PsiCashException;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psiphonlibrary.StatusList.StatusListViewManager;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.SharedPreferencesImport;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

import static android.nfc.NdefRecord.createMime;

public abstract class MainBase {
    public static abstract class Activity extends LocalizedActivities.AppCompatActivity implements MyLog.ILogger {
        public Activity() {
            Utils.initializeSecureRandom();
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            MyLog.setLogger(this);
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();

            MyLog.unsetLogger();
        }

        /*
         * Partial MyLog.ILogger implementation
         */

        @Override
        public Context getContext() {
            return this;
        }
    }

    public static abstract class TabbedActivityBase extends Activity implements OnTabChangeListener {
        public static final String STATUS_ENTRY_AVAILABLE = "com.psiphon3.MainBase.TabbedActivityBase.STATUS_ENTRY_AVAILABLE";
        public static final String INTENT_EXTRA_PREVENT_AUTO_START = "com.psiphon3.MainBase.TabbedActivityBase.PREVENT_AUTO_START";
        protected static final String ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION = "askedToAccessCoarseLocationPermission";
        protected static final String CURRENT_TAB = "currentTab";
        protected static final String CURRENT_PURCHASE = "currentPurchase";

        protected static final int REQUEST_CODE_PREPARE_VPN = 100;
        protected static final int REQUEST_CODE_PREFERENCE = 101;
        protected static final int REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION = 102;

        public static final String HOME_TAB_TAG = "home_tab_tag";
        public static final String PSICASH_TAB_TAG = "psicash_tab_tag";
        public static final String STATISTICS_TAB_TAG = "statistics_tab_tag";
        public static final String SETTINGS_TAB_TAG = "settings_tab_tag";
        public static final String LOGS_TAB_TAG = "logs_tab_tag";


        protected Button m_toggleButton;
        protected ProgressBar m_connectionProgressBar;

        private StatusListViewManager m_statusListManager = null;
        protected AppPreferences m_multiProcessPreferences;
        protected SponsorHomePage m_sponsorHomePage;
        private LocalBroadcastManager m_localBroadcastManager;
        private TextView m_elapsedConnectionTimeView;
        private TextView m_totalSentView;
        private TextView m_totalReceivedView;
        private DataTransferGraph m_slowSentGraph;
        private DataTransferGraph m_slowReceivedGraph;
        private DataTransferGraph m_fastSentGraph;
        private DataTransferGraph m_fastReceivedGraph;
        private RegionAdapter m_regionAdapter;
        protected SpinnerHelper m_regionSelector;
        protected CheckBox m_tunnelWholeDeviceToggle;
        protected CheckBox m_downloadOnWifiOnlyToggle;
        protected CheckBox m_disableTimeoutsToggle;
        private Toast m_invalidProxySettingsToast;
        private Button m_moreOptionsButton;
        private Button m_openBrowserButton;
        private LoggingObserver m_loggingObserver;
        private CompositeDisposable compositeDisposable = new CompositeDisposable();
        protected TunnelServiceInteractor tunnelServiceInteractor;
        private Disposable handleNfcIntentDisposable;

        protected boolean isAppInForeground;

        public TabbedActivityBase() {
            Utils.initializeSecureRandom();
        }

        private ImageButton m_statusViewImage;

        private View mHelpConnectButton;

        private NfcAdapter mNfcAdapter;
        private NfcAdapterCallback mNfcAdapterCallback;
        private String mConnectionInfoPayload = "";

        private CountDownLatch mNfcConnectionInfoExportLatch;

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
        private class NfcAdapterCallback implements NfcAdapter.CreateNdefMessageCallback {
            @Override
            public NdefMessage createNdefMessage(NfcEvent event) {
                // Request the connection info get updated
                tunnelServiceInteractor.nfcExportConnectionInfo();

                // Wait for the service to respond
                try {
                    mNfcConnectionInfoExportLatch = new CountDownLatch(1);
                    if (!mNfcConnectionInfoExportLatch.await(2, TimeUnit.SECONDS)) {
                        // We didn't get a response within two seconds so
                        // set the payload to something invalid so nothing happens to the receiver
                        mConnectionInfoPayload = "";
                    }
                } catch (InterruptedException e) {
                    // Set the payload to something invalid so nothing happens to the receiver
                    mConnectionInfoPayload = "";
                }

                // Decode the payload then clear it so we don't try to import an old payload
                byte[] payload = ConnectionInfoExchangeUtils.decodeConnectionInfo(mConnectionInfoPayload);
                mConnectionInfoPayload = "";

                return new NdefMessage(
                        new NdefRecord[] { createMime(
                                ConnectionInfoExchangeUtils.NFC_MIME_TYPE, payload)
                        });
            }
        }

        // Lateral navigation with TabHost:
        // Adapted from here:
        // http://danielkvist.net/code/animated-tabhost-with-slide-gesture-in-android
        private static final int ANIMATION_TIME = 240;
        protected TabHost m_tabHost;
        protected List<TabSpec> m_tabSpecsList;
        private int m_currentTab;
        private View m_previousView;
        private View m_currentView;
        private GestureDetector m_gestureDetector;
        protected enum TabIndex {HOME, STATISTICS, OPTIONS, LOGS}

        /**
         * A gesture listener that listens for a left or right swipe and uses
         * the swip gesture to navigate a TabHost that uses an AnimatedTabHost
         * listener.
         * 
         * @author Daniel Kvist
         * 
         */
        class LateralGestureDetector extends SimpleOnGestureListener {
            private static final int SWIPE_MIN_DISTANCE = 120;
            private static final int SWIPE_MAX_OFF_PATH = 250;
            private static final int SWIPE_THRESHOLD_VELOCITY = 200;
            private final int maxTabs;

            /**
             * An empty constructor that uses the tabhosts content view to
             * decide how many tabs there are.
             */
            public LateralGestureDetector() {
                maxTabs = m_tabHost.getTabContentView().getChildCount();
            }

            /**
             * Listens for the onFling event and performs some calculations
             * between the touch down point and the touch up point. It then uses
             * that information to calculate if the swipe was long enough. It
             * also uses the swiping velocity to decide if it was a "true" swipe
             * or just some random touching.
             */
            @Override
            public boolean onFling(MotionEvent event1, MotionEvent event2, float velocityX, float velocityY) {
                if (event1 != null && event2 != null) {
                    // Determine tab swipe direction
                    int direction;
                    if (Math.abs(event1.getY() - event2.getY()) > SWIPE_MAX_OFF_PATH) {
                        return false;
                    }
                    if (event1.getX() - event2.getX() > SWIPE_MIN_DISTANCE && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                        // Swipe right to left
                        direction = 1;
                    } else if (event2.getX() - event1.getX() > SWIPE_MIN_DISTANCE && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                        // Swipe left to right
                        direction = -1;
                    } else {
                        return false;
                    }

                    // Move in direction until we hit a visible tab, or go out of bounds
                    int newTab = m_currentTab + direction;
                    while (newTab >= 0 && newTab < maxTabs && m_tabHost.getTabWidget().getChildTabViewAt(newTab).getVisibility() != View.VISIBLE) {
                        newTab += direction;
                    }

                    if (newTab < 0 || newTab > (maxTabs - 1)) {
                        return false;
                    }

                    m_tabHost.setCurrentTab(newTab);
                }
                return super.onFling(event1, event2, velocityX, velocityY);
            }
        }

        /**
         * When tabs change we fetch the current view that we are animating to
         * and animate it and the previous view in the appropriate directions.
         */
        @Override
        public void onTabChanged(String tabId) {
            m_currentView = m_tabHost.getCurrentView();
            if (m_previousView != null) {
                if (m_tabHost.getCurrentTab() > m_currentTab) {
                    m_previousView.setAnimation(outToLeftAnimation());
                    m_currentView.setAnimation(inFromRightAnimation());
                } else {
                    m_previousView.setAnimation(outToRightAnimation());
                    m_currentView.setAnimation(inFromLeftAnimation());
                }
            }
            m_previousView = m_currentView;
            m_currentTab = m_tabHost.getCurrentTab();

            m_multiProcessPreferences.put(CURRENT_TAB, m_currentTab);
        }

        /**
         * Custom animation that animates in from right
         * 
         * @return Animation the Animation object
         */
        private Animation inFromRightAnimation() {
            Animation inFromRight = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 1.0f, Animation.RELATIVE_TO_PARENT, 0.0f,
                    Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(inFromRight);
        }

        /**
         * Custom animation that animates out to the right
         * 
         * @return Animation the Animation object
         */
        private Animation outToRightAnimation() {
            Animation outToRight = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 1.0f, Animation.RELATIVE_TO_PARENT,
                    0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(outToRight);
        }

        /**
         * Custom animation that animates in from left
         * 
         * @return Animation the Animation object
         */
        private Animation inFromLeftAnimation() {
            Animation inFromLeft = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, -1.0f, Animation.RELATIVE_TO_PARENT, 0.0f,
                    Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(inFromLeft);
        }

        /**
         * Custom animation that animates out to the left
         * 
         * @return Animation the Animation object
         */
        private Animation outToLeftAnimation() {
            Animation outtoLeft = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, -1.0f, Animation.RELATIVE_TO_PARENT,
                    0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(outtoLeft);
        }

        /**
         * Helper method that sets some common properties
         * 
         * @param animation
         *            the animation to give common properties
         * @return the animation with common properties
         */
        private Animation setProperties(Animation animation) {
            animation.setDuration(ANIMATION_TIME);
            animation.setInterpolator(new AccelerateInterpolator());
            return animation;
        }

        @SuppressLint("SetJavaScriptEnabled")
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            m_multiProcessPreferences = new AppPreferences(this);
            // Migrate 'More Options' SharedPreferences to tray preferences:
            // The name of the DefaultSharedPreferences is this.getPackageName() + "_preferences"
            // http://stackoverflow.com/questions/5946135/difference-between-getdefaultsharedpreferences-and-getsharedpreferences
            String prefName = this.getPackageName() + "_preferences";
            m_multiProcessPreferences.migrate(
                    // Top level  preferences
                    new SharedPreferencesImport(this, prefName, CURRENT_TAB, CURRENT_TAB),
                    new SharedPreferencesImport(this, prefName, getString(R.string.egressRegionPreference), getString(R.string.egressRegionPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.tunnelWholeDevicePreference), getString(R.string.tunnelWholeDevicePreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.downloadWifiOnlyPreference), getString(R.string.downloadWifiOnlyPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.disableTimeoutsPreference), getString(R.string.disableTimeoutsPreference)),
                    // More Options preferences
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithSound), getString(R.string.preferenceNotificationsWithSound)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithVibrate), getString(R.string.preferenceNotificationsWithVibrate)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceExcludeAppsFromVpnString), getString(R.string.preferenceExcludeAppsFromVpnString)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxySettingsPreference), getString(R.string.useProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useSystemProxySettingsPreference), getString(R.string.useSystemProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPreference), getString(R.string.useCustomProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsHostPreference), getString(R.string.useCustomProxySettingsHostPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPortPreference), getString(R.string.useCustomProxySettingsPortPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyAuthenticationPreference), getString(R.string.useProxyAuthenticationPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyUsernamePreference), getString(R.string.useProxyUsernamePreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyPasswordPreference), getString(R.string.useProxyPasswordPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyDomainPreference), getString(R.string.useProxyDomainPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceLanguageSelection), getString(R.string.preferenceLanguageSelection))
            );

            EmbeddedValues.initialize(this);
            tunnelServiceInteractor = new TunnelServiceInteractor(getApplicationContext());

            // remove logs from previous sessions
            if (!tunnelServiceInteractor.isServiceRunning(getApplicationContext())) {
                LoggingProvider.LogDatabaseHelper.truncateLogs(this, true);
            }

            // Listen to GOT_NEW_EXPIRING_PURCHASE intent from psicash module
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
            LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter);

            // Only handle NFC if the version is sufficient
            if (ConnectionInfoExchangeUtils.isNfcSupported(getApplicationContext())) {
                // Check for available NFC Adapter
                mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
                if (mNfcAdapter != null) {
                    // Register callback
                    mNfcAdapterCallback = new NfcAdapterCallback();

                    // Always enable receiving an NFC tag, and determine what to do with it when we receive it based on the service state.
                    // For example, in the Stopped state, we can receive a tag, and instruct the user to start the tunnel service and try again.
                    PackageManager packageManager = getPackageManager();
                    ComponentName componentName = new ComponentName(getPackageName(), NfcActivity.class.getName());
                    packageManager.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                }
            }
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();

            if (m_sponsorHomePage != null) {
                m_sponsorHomePage.stop();
                m_sponsorHomePage = null;
            }
            compositeDisposable.dispose();
        }

        protected void setupActivityLayout() {
            // Set up tabs
            m_tabHost.setup();

            m_tabSpecsList.clear();
            m_tabSpecsList.add(TabIndex.HOME.ordinal(), m_tabHost.newTabSpec(HOME_TAB_TAG).setContent(R.id.homeTab).setIndicator(getText(R.string.home_tab_name)));
            m_tabSpecsList.add(TabIndex.STATISTICS.ordinal(), m_tabHost.newTabSpec(STATISTICS_TAB_TAG).setContent(R.id.statisticsView).setIndicator(getText(R.string.statistics_tab_name)));
            m_tabSpecsList.add(TabIndex.OPTIONS.ordinal(), m_tabHost.newTabSpec(SETTINGS_TAB_TAG).setContent(R.id.settingsView).setIndicator(getText(R.string.settings_tab_name)));
            m_tabSpecsList.add(TabIndex.LOGS.ordinal(), m_tabHost.newTabSpec(LOGS_TAB_TAG).setContent(R.id.logsTab).setIndicator(getText(R.string.logs_tab_name)));

            for (TabSpec tabSpec : m_tabSpecsList) {
                m_tabHost.addTab(tabSpec);
            }

            m_gestureDetector = new GestureDetector(this, new LateralGestureDetector());
            OnTouchListener onTouchListener = new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    // Give the view a chance to handle the event first, ie a
                    // scrollview or listview
                    v.onTouchEvent(event);

                    return !m_gestureDetector.onTouchEvent(event);
                }
            };

            m_tabHost.setOnTouchListener(onTouchListener);
            findViewById(R.id.statisticsView).setOnTouchListener(onTouchListener);
            findViewById(R.id.settingsView).setOnTouchListener(onTouchListener);
            findViewById(R.id.regionSelector).setOnTouchListener(onTouchListener);
            findViewById(R.id.tunnelWholeDeviceToggle).setOnTouchListener(onTouchListener);
            findViewById(R.id.feedbackButton).setOnTouchListener(onTouchListener);
            findViewById(R.id.aboutButton).setOnTouchListener(onTouchListener);
            ListView statusListView = (ListView) findViewById(R.id.statusList);
            statusListView.setOnTouchListener(onTouchListener);

            int currentTab = m_multiProcessPreferences.getInt(CURRENT_TAB, 0);
            m_currentTab = currentTab;
            m_tabHost.setCurrentTab(currentTab);

            // Set TabChangedListener after restoring last tab to avoid triggering an interstitial,
            // we only want interstitial to be triggered by user actions
            m_tabHost.setOnTabChangedListener(this);

            m_elapsedConnectionTimeView = (TextView) findViewById(R.id.elapsedConnectionTime);
            m_totalSentView = (TextView) findViewById(R.id.totalSent);
            m_totalReceivedView = (TextView) findViewById(R.id.totalReceived);
            m_regionSelector = new SpinnerHelper(findViewById(R.id.regionSelector));
            m_tunnelWholeDeviceToggle = (CheckBox) findViewById(R.id.tunnelWholeDeviceToggle);
            m_disableTimeoutsToggle = (CheckBox) findViewById(R.id.disableTimeoutsToggle);
            m_downloadOnWifiOnlyToggle = (CheckBox) findViewById(R.id.downloadOnWifiOnlyToggle);
            m_moreOptionsButton = (Button) findViewById(R.id.moreOptionsButton);
            m_openBrowserButton = (Button) findViewById(R.id.openBrowserButton);

            m_slowSentGraph = new DataTransferGraph(this, R.id.slowSentGraph);
            m_slowReceivedGraph = new DataTransferGraph(this, R.id.slowReceivedGraph);
            m_fastSentGraph = new DataTransferGraph(this, R.id.fastSentGraph);
            m_fastReceivedGraph = new DataTransferGraph(this, R.id.fastReceivedGraph);

            // Set up the list view
            m_statusListManager = new StatusListViewManager(statusListView);

            m_localBroadcastManager = LocalBroadcastManager.getInstance(this);
            m_localBroadcastManager.registerReceiver(new StatusEntryAdded(), new IntentFilter(STATUS_ENTRY_AVAILABLE));

            m_regionAdapter = new RegionAdapter(this);
            m_regionSelector.setAdapter(m_regionAdapter);
            String egressRegionPreference = m_multiProcessPreferences
                    .getString(getString(R.string.egressRegionPreference),
                            PsiphonConstants.REGION_CODE_ANY);

            m_regionSelector.setSelectionByValue(egressRegionPreference);

            m_regionSelector.setOnItemSelectedListener(regionSpinnerOnItemSelected);

            boolean canWholeDevice = Utils.hasVpnService();

            m_tunnelWholeDeviceToggle.setEnabled(canWholeDevice);
            boolean tunnelWholeDevicePreference = m_multiProcessPreferences
                    .getBoolean(getString(R.string.tunnelWholeDevicePreference),
                            canWholeDevice);
            m_tunnelWholeDeviceToggle.setChecked(tunnelWholeDevicePreference);

            // Show download-wifi-only preference only in not Play Store build
            if (!EmbeddedValues.IS_PLAY_STORE_BUILD) {
                boolean downLoadWifiOnlyPreference = m_multiProcessPreferences.getBoolean(
                        getString(R.string.downloadWifiOnlyPreference),
                        PsiphonConstants.DOWNLOAD_WIFI_ONLY_PREFERENCE_DEFAULT);
                m_downloadOnWifiOnlyToggle.setChecked(downLoadWifiOnlyPreference);
            }
            else {
                m_downloadOnWifiOnlyToggle.setEnabled(false);
                m_downloadOnWifiOnlyToggle.setVisibility(View.GONE);
            }

            boolean disableTimeoutsPreference = m_multiProcessPreferences.getBoolean(
                    getString(R.string.disableTimeoutsPreference), false);
            m_disableTimeoutsToggle.setChecked(disableTimeoutsPreference);

            // The LoggingObserver will run in a separate thread than the main UI thread
            HandlerThread loggingObserverThread = new HandlerThread("LoggingObserverThread");
            loggingObserverThread.start();
            m_loggingObserver = new LoggingObserver(this, new Handler(loggingObserverThread.getLooper()));

            // Force the UI to display logs already loaded into the StatusList message history
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(STATUS_ENTRY_AVAILABLE));

            // Get the connection help buttons
            mHelpConnectButton = findViewById(R.id.howToHelpButton);

            compositeDisposable.addAll(
                    tunnelServiceInteractor.tunnelStateFlowable()
                            // Update app UI state
                            .doOnNext(state -> runOnUiThread(() -> updateServiceStateUI(state)))
                            // update WebView proxy settings
                            .doOnNext(this::updateWebViewProxySettings)
                            .map(state -> {
                                if (state.isRunning()) {
                                    if (state.connectionData().isConnected()) {
                                        return ConnectionHelpState.CAN_HELP;
                                    } else if (state.connectionData().needsHelpConnecting()) {
                                        return ConnectionHelpState.NEEDS_HELP;
                                    }
                                } // else
                                return ConnectionHelpState.DISABLED;
                            })
                            .distinctUntilChanged()
                            .doOnNext(this::setConnectionHelpState)
                            .subscribe(),

                    tunnelServiceInteractor.dataStatsFlowable()
                            .startWith(Boolean.FALSE)
                            .doOnNext(isConnected -> runOnUiThread(() -> updateStatisticsUICallback(isConnected)))
                            .subscribe(),

                    tunnelServiceInteractor.knownRegionsFlowable()
                            .doOnNext(__ -> m_regionAdapter.updateRegionsFromPreferences())
                            .subscribe(),

                    tunnelServiceInteractor.nfcExchangeFlowable()
                            .doOnNext(nfcExchange -> {
                                switch (nfcExchange.type()) {
                                    case EXPORT:
                                        handleNfcConnectionInfoExchangeResponseExport(nfcExchange.payload());
                                        break;
                                    case IMPORT:
                                        handleNfcConnectionInfoExchangeResponseImport(nfcExchange.success());
                                        break;
                                }
                            })
                            .subscribe(),

                    tunnelServiceInteractor.authorizationsRemovedFlowable()
                            .doOnNext(__ -> onAuthorizationsRemoved())
                            .subscribe()
            );
        }

        private void updateWebViewProxySettings(TunnelState state) {
            if (state.isUnknown()) {
                // do nothing
                return;
            }
            if (state.isRunning()) {
                if (state.connectionData().vpnMode()) {
                    // We're running in WDM
                    if (WebViewProxySettings.isLocalProxySet()) {
                        WebViewProxySettings.resetLocalProxy(getContext());
                    }
                } else {
                    // We're running in BOM
                    int httpPort = state.connectionData().httpPort();
                    if (httpPort > 0) {
                        WebViewProxySettings.setLocalProxy(getContext(), state.connectionData().httpPort());
                    }
                }
            } else {
                // Not running, reset
                if (WebViewProxySettings.isLocalProxySet()) {
                    WebViewProxySettings.resetLocalProxy(getContext());
                }
            }
        }

        private enum ConnectionHelpState {
            DISABLED,
            NEEDS_HELP,
            CAN_HELP,
        }

        @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
        private void setConnectionHelpState(ConnectionHelpState state) {
            // Make sure we aren't calling this before everything is set up
            if (mNfcAdapter == null) {
                return;
            }

            // Make sure the activity isn't destroyed (setNdefPushMessageCallback will throw IllegalStateException)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && this.isDestroyed()) {
                return;
            }

            switch (state) {
                case DISABLED:
                case NEEDS_HELP:
                    hideHelpConnectUI();
                    mNfcAdapter.setNdefPushMessageCallback(null, this);
                    break;
                case CAN_HELP:
                    showHelpConnectUI();
                    mNfcAdapter.setNdefPushMessageCallback(mNfcAdapterCallback, this);
                    break;
            }
        }

        private AlertDialog mConnectionHelpDialog;

        protected void showConnectionHelpDialog(Context context, int id) {
            LayoutInflater layoutInflater = LayoutInflater.from(context);
            // TODO: Determine what the root inflation should be.
            View dialogView = layoutInflater.inflate(id, null);
            mConnectionHelpDialog = new AlertDialog.Builder(context)
                    .setView(dialogView)
                    .setPositiveButton(R.string.label_ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .create();
            mConnectionHelpDialog.show();
        }

        private void showHelpConnectUI() {
            // Ensure that they have NFC
            if (!ConnectionInfoExchangeUtils.isNfcSupported(getApplicationContext())) {
                return;
            }

            mHelpConnectButton.setVisibility(View.VISIBLE);
        }

        private void hideHelpConnectUI() {
            // Ensure that they have NFC
            if (!ConnectionInfoExchangeUtils.isNfcSupported(getApplicationContext())) {
                return;
            }

            mHelpConnectButton.setVisibility(View.GONE);
        }

        protected void handleNfcConnectionInfoExchangeResponseExport(String payload) {
            // Store the data to be sent on an NFC exchange so we don't have to wait when beaming
            mConnectionInfoPayload = payload;

            // If the latch exists, let it wake up
            if (mNfcConnectionInfoExportLatch != null) {
                mNfcConnectionInfoExportLatch.countDown();
            }
        }

        protected void handleNfcConnectionInfoExchangeResponseImport(boolean success) {
            String message = success ? getString(R.string.nfc_connection_info_import_success) : getString(R.string.nfc_connection_info_import_failure);
            if (success) {
                // Dismiss the get help dialog if it is showing
                if (mConnectionHelpDialog != null && mConnectionHelpDialog.isShowing()) {
                    mConnectionHelpDialog.dismiss();
                }
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }

        @Override
        protected void onResume() {
            super.onResume();

            isAppInForeground = true;
            
            // Load new logs from the logging provider now
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                m_loggingObserver.dispatchChange(false, LoggingProvider.INSERT_URI);
            } else {
                m_loggingObserver.dispatchChange(false);
            }

            // Load new logs from the logging provider when it changes
            getContentResolver().registerContentObserver(LoggingProvider.INSERT_URI, true, m_loggingObserver);

            tunnelServiceInteractor.resume(getApplicationContext());

            // Don't show the keyboard until edit selected
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

            if (ConnectionInfoExchangeUtils.isNfcSupported(getApplicationContext())) {
                Intent intent = getIntent();
                // Check to see that the Activity started due to an Android Beam
                if (ConnectionInfoExchangeUtils.isNfcDiscoveredIntent(intent)) {
                    handleNfcIntent(intent);

                    // We only want to respond to the NFC Intent once,
                    // so we need to clear it (by setting it to a non-special intent).
                    setIntent(new Intent(
                            "ACTION_VIEW",
                            null,
                            this,
                            this.getClass()));
                }
            }
        }

        private void handleNfcIntent(Intent intent) {
            if(handleNfcIntentDisposable != null && !handleNfcIntentDisposable.isDisposed()) {
                // Already in progress, do nothing.
                return;
            }
            handleNfcIntentDisposable = tunnelServiceInteractor.tunnelStateFlowable()
                    // wait until we learn tunnel state
                    .filter(state -> !state.isUnknown())
                    .firstOrError()
                    .doOnSuccess(state -> {
                        if(!state.isRunning()) {
                            Toast.makeText(this, getString(R.string.nfc_connection_info_press_start), Toast.LENGTH_LONG).show();
                            return;
                        }

                        if (state.connectionData().isConnected()) {
                            return;
                        }

                        String connectionInfoPayload = ConnectionInfoExchangeUtils.getConnectionInfoPayloadFromNfcIntent(intent);

                        // If the payload is empty don't try to import just let the user know it failed
                        if (TextUtils.isEmpty(connectionInfoPayload)) {
                            Toast.makeText(this, getString(R.string.nfc_connection_info_import_failure), Toast.LENGTH_LONG).show();
                            return;
                        }

                        // Otherwise, send the received message to the TunnelManager to be imported
                        tunnelServiceInteractor.importConnectionInfo(connectionInfoPayload);
                    })
                    .subscribe();
        }

        @Override
        protected void onPause() {
            super.onPause();

            isAppInForeground = false;

            getContentResolver().unregisterContentObserver(m_loggingObserver);
            cancelInvalidProxySettingsToast();
            tunnelServiceInteractor.pause(getApplicationContext());
        }

        protected void doToggle() {
            compositeDisposable.add(
                    tunnelServiceInteractor.tunnelStateFlowable()
                            .firstOrError()
                            .doOnSuccess(state -> {
                                if (state.isRunning()) {
                                    stopTunnelService();
                                } else {
                                    startUp();
                                }
                            })
                            .subscribe()
            );
        }

        public class StatusEntryAdded extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (m_statusListManager != null) {
                    m_statusListManager.notifyStatusAdded();
                }
                // TODO: fix this - see if we can still fit the last log line
/*
                runOnUiThread(new Runnable() {
                    public void run() {
                        StatusList.StatusEntry statusEntry = StatusList.getLastStatusEntryForDisplay();
                        if (statusEntry != null) {
                            String msg = getContext().getString(statusEntry.stringId(), statusEntry.formatArgs());
                            m_statusTabLogLine.setText(msg);
                        }
                    }
                });
 */
            }
        }

        final protected String PsiCashModifyUrl(String originalUrlString) {
            if (TextUtils.isEmpty(originalUrlString)) {
                return originalUrlString;
            }

            try {
                return PsiCashClient.getInstance(getContext()).modifiedHomePageURL(originalUrlString);
            } catch (PsiCashException e) {
                MyLog.g("PsiCash: error modifying home page: " + e);
            }
            return originalUrlString;
        }

        protected abstract void startUp();

        protected void doAbout() {
            if (URLUtil.isValidUrl(EmbeddedValues.INFO_LINK_URL)) {
                // TODO: if connected, open in Psiphon browser?
                // Events.displayBrowser(this,
                // Uri.parse(PsiphonConstants.INFO_LINK_URL));

                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(EmbeddedValues.INFO_LINK_URL));
                startActivity(browserIntent);
            }
        }

        public void onMoreOptionsClick(View v) {
            startActivityForResult(new Intent(this, MoreOptionsPreferenceActivity.class), REQUEST_CODE_PREFERENCE);
        }

        public abstract void onFeedbackClick(View v);

        public void onAboutClick(View v) {
            doAbout();
        }

        private final AdapterView.OnItemSelectedListener regionSpinnerOnItemSelected = new AdapterView.OnItemSelectedListener() {

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String regionCode = parent.getItemAtPosition(position).toString();
                onRegionSelected(regionCode);
            }

            @Override
            public void onNothingSelected(AdapterView parent) {
            }
        };

        public void onRegionSelected(String selectedRegionCode) {
            // Just in case an OnItemSelected message is in transit before
            // setEnabled is processed...(?)
            if (!m_regionSelector.isEnabled()) {
                return;
            }
            String egressRegionPreference = m_multiProcessPreferences
                    .getString(getString(R.string.egressRegionPreference),
                            PsiphonConstants.REGION_CODE_ANY);
            if (selectedRegionCode.equals(egressRegionPreference)) {
                return;
            }

            updateEgressRegionPreference(selectedRegionCode);

            // NOTE: reconnects even when Any is selected: we could select a
            // faster server
            tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplicationContext(), this::startTunnel);
        }

        protected void updateEgressRegionPreference(String egressRegionPreference) {
            // No isRooted check: the user can specify whatever preference they
            // wish. Also, CheckBox enabling should cover this (but isn't
            // required to).
            m_multiProcessPreferences.put(getString(R.string.egressRegionPreference), egressRegionPreference);
        }

        public void onTunnelWholeDeviceToggle(View v) {
            // Just in case an OnClick message is in transit before setEnabled
            // is processed...(?)
            if (!m_tunnelWholeDeviceToggle.isEnabled()) {
                return;
            }

            boolean tunnelWholeDevicePreference = m_tunnelWholeDeviceToggle.isChecked();
            updateWholeDevicePreference(tunnelWholeDevicePreference);
            tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplicationContext(), this::startTunnel);
        }

        protected void updateWholeDevicePreference(boolean tunnelWholeDevicePreference) {
            // No isRooted check: the user can specify whatever preference they
            // wish. Also, CheckBox enabling should cover this (but isn't
            // required to).
            m_multiProcessPreferences.put(getString(R.string.tunnelWholeDevicePreference), tunnelWholeDevicePreference);

            // When enabling BOM, we don't use the TunnelVpnService, so we can disable it
            // which prevents the user having Always On turned on.

            PackageManager packageManager = getPackageManager();
            ComponentName componentName = new ComponentName(getPackageName(), TunnelVpnService.class.getName());
            packageManager.setComponentEnabledSetting(componentName,
                    tunnelWholeDevicePreference ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }

        public void onDisableTimeoutsToggle(View v) {
            boolean disableTimeoutsChecked = m_disableTimeoutsToggle.isChecked();
            updateDisableTimeoutsPreference(disableTimeoutsChecked);
            tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplicationContext(), this::startTunnel);
        }
        protected void updateDisableTimeoutsPreference(boolean disableTimeoutsPreference) {
            m_multiProcessPreferences.put(getString(R.string.disableTimeoutsPreference), disableTimeoutsPreference);
        }

        public void onDownloadOnWifiOnlyToggle(View v) {
            boolean downloadWifiOnly = m_downloadOnWifiOnlyToggle.isChecked();

            // There is no need to restart the service if the value of downloadWifiOnly
            // has changed because upgrade downloads happen in a different, temp tunnel

            m_multiProcessPreferences.put(getString(R.string.downloadWifiOnlyPreference), downloadWifiOnly);
        }

        // Basic check of proxy settings values
        private boolean customProxySettingsValuesValid() {
            UpstreamProxySettings.ProxySettings proxySettings = UpstreamProxySettings.getProxySettings(this);
            return proxySettings != null && proxySettings.proxyHost.length() > 0 && proxySettings.proxyPort >= 1 && proxySettings.proxyPort <= 65535;
        }

        private class DataTransferGraph {
            private final Activity m_activity;
            private final LinearLayout m_graphLayout;
            private GraphicalView m_chart;
            private final XYMultipleSeriesDataset m_chartDataset;
            private final XYMultipleSeriesRenderer m_chartRenderer;
            private final XYSeries m_chartCurrentSeries;
            private final XYSeriesRenderer m_chartCurrentRenderer;

            public DataTransferGraph(Activity activity, int layoutId) {
                m_activity = activity;
                m_graphLayout = (LinearLayout) activity.findViewById(layoutId);
                m_chartDataset = new XYMultipleSeriesDataset();
                m_chartRenderer = new XYMultipleSeriesRenderer();
                m_chartRenderer.setGridColor(Color.GRAY);
                m_chartRenderer.setShowGrid(true);
                m_chartRenderer.setShowLabels(false);
                m_chartRenderer.setShowLegend(false);
                m_chartRenderer.setShowAxes(false);
                m_chartRenderer.setPanEnabled(false, false);
                m_chartRenderer.setZoomEnabled(false, false);

                // Make the margins transparent.
                // Note that this value is a bit magical. One would expect
                // android.graphics.Color.TRANSPARENT to work, but it doesn't.
                // Nor does 0x00000000. Ref:
                // http://developer.android.com/reference/android/graphics/Color.html
                m_chartRenderer.setMarginsColor(0x00FFFFFF);

                m_chartCurrentSeries = new XYSeries("");
                m_chartDataset.addSeries(m_chartCurrentSeries);
                m_chartCurrentRenderer = new XYSeriesRenderer();
                m_chartCurrentRenderer.setColor(Color.YELLOW);
                m_chartRenderer.addSeriesRenderer(m_chartCurrentRenderer);
            }

            public void update(ArrayList<Long> data) {
                m_chartCurrentSeries.clear();
                for (int i = 0; i < data.size(); i++) {
                    m_chartCurrentSeries.add(i, data.get(i));
                }
                if (m_chart == null) {
                    m_chart = ChartFactory.getLineChartView(m_activity, m_chartDataset, m_chartRenderer);
                    m_graphLayout.addView(m_chart);
                } else {
                    m_chart.repaint();
                }
            }
        }

        private void updateStatisticsUICallback(boolean isConnected) {
            DataTransferStats.DataTransferStatsForUI dataTransferStats = DataTransferStats.getDataTransferStatsForUI();
            m_elapsedConnectionTimeView.setText(isConnected ? getString(R.string.connected_elapsed_time,
                    Utils.elapsedTimeToDisplay(dataTransferStats.getElapsedTime())) : getString(R.string.disconnected));
            m_totalSentView.setText(Utils.byteCountToDisplaySize(dataTransferStats.getTotalBytesSent(), false));
            m_totalReceivedView.setText(Utils.byteCountToDisplaySize(dataTransferStats.getTotalBytesReceived(), false));
            m_slowSentGraph.update(dataTransferStats.getSlowSentSeries());
            m_slowReceivedGraph.update(dataTransferStats.getSlowReceivedSeries());
            m_fastSentGraph.update(dataTransferStats.getFastSentSeries());
            m_fastReceivedGraph.update(dataTransferStats.getFastReceivedSeries());
        }

        private void cancelInvalidProxySettingsToast() {
            if (m_invalidProxySettingsToast != null) {
                View toastView = m_invalidProxySettingsToast.getView();
                if (toastView != null) {
                    if (toastView.isShown()) {
                        m_invalidProxySettingsToast.cancel();
                    }
                }
            }
        }

        private void updateServiceStateUI(final TunnelState tunnelState) {
            if(tunnelState.isUnknown()) {
                disableToggleServiceUI();
                m_openBrowserButton.setEnabled(false);
                m_toggleButton.setText(getText(R.string.waiting));
                m_connectionProgressBar.setVisibility(View.INVISIBLE);
            } else if (tunnelState.isRunning()) {
                enableToggleServiceUI();
                m_toggleButton.setText(getText(R.string.stop));
                if(tunnelState.connectionData().isConnected()) {
                    m_openBrowserButton.setEnabled(true);
                    m_connectionProgressBar.setVisibility(View.INVISIBLE);
                    ArrayList<String> homePages = tunnelState.connectionData().homePages();
                    final String url;
                    if(homePages != null && homePages.size() > 0) {
                        url = homePages.get(0);
                    } else {
                        url = null;
                    }
                    m_openBrowserButton.setOnClickListener(view -> displayBrowser(this, url));
                } else {
                    m_openBrowserButton.setEnabled(false);
                    m_connectionProgressBar.setVisibility(View.VISIBLE);
                }
            } else {
                // Service not running
                enableToggleServiceUI();
                m_toggleButton.setText(getText(R.string.start));
                m_openBrowserButton.setEnabled(false);
                m_connectionProgressBar.setVisibility(View.INVISIBLE);
            }
        }

        protected void enableToggleServiceUI() {
            m_toggleButton.setEnabled(true);
            m_tunnelWholeDeviceToggle.setEnabled(Utils.hasVpnService());
            m_disableTimeoutsToggle.setEnabled(true);
            m_regionSelector.setEnabled(true);
            m_moreOptionsButton.setEnabled(true);
        }

        protected void disableToggleServiceUI() {
            m_toggleButton.setText(getText(R.string.waiting));
            m_toggleButton.setEnabled(false);
            m_tunnelWholeDeviceToggle.setEnabled(false);
            m_disableTimeoutsToggle.setEnabled(false);
            m_regionSelector.setEnabled(false);
            m_moreOptionsButton.setEnabled(false);
        }

        protected void startTunnel() {
            // Tunnel core needs this dangerous permission to obtain the WiFi BSSID, which is used
            // as a key for applying tactics
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {
                proceedStartTunnel();
            } else {
                AppPreferences mpPreferences = new AppPreferences(this);
                if (mpPreferences.getBoolean(ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION, false)) {
                    proceedStartTunnel();
                } else if(!this.isFinishing()){
                    final Context context = this;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            new AlertDialog.Builder(context)
                                    .setCancelable(false)
                                    .setOnKeyListener(
                                            new DialogInterface.OnKeyListener() {
                                                @Override
                                                public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                                                    // Don't dismiss when hardware search button is clicked (Android 2.3 and earlier)
                                                    return keyCode == KeyEvent.KEYCODE_SEARCH;
                                                }})
                                    .setTitle(R.string.MainBase_AccessCoarseLocationPermissionPromptTitle)
                                    .setMessage(R.string.MainBase_AccessCoarseLocationPermissionPromptMessage)
                                    .setPositiveButton(R.string.MainBase_AccessCoarseLocationPermissionPositiveButton,
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int whichButton) {
                                                    m_multiProcessPreferences.put(ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION, true);
                                                    ActivityCompat.requestPermissions(TabbedActivityBase.this,
                                                            new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                                            REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION);
                                                }})
                                    .setNegativeButton(R.string.MainBase_AccessCoarseLocationPermissionNegativeButton,
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int whichButton) {
                                                    m_multiProcessPreferences.put(ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION, true);
                                                    proceedStartTunnel();
                                                }})
                                    .setOnCancelListener(
                                            new DialogInterface.OnCancelListener() {
                                                @Override
                                                public void onCancel(DialogInterface dialog) {
                                                    // Do nothing (this prompt may reappear)
                                                }})
                                    .show();
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode,
                                               String permissions[], int[] grantResults) {
            switch (requestCode) {
                case REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION:
                    proceedStartTunnel();
                    break;

                default:
                    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            }
        }

        private void proceedStartTunnel() {
            // Don't start if custom proxy settings is selected and values are
            // invalid
            boolean useHTTPProxyPreference = UpstreamProxySettings.getUseHTTPProxy(this);
            boolean useCustomProxySettingsPreference = UpstreamProxySettings.getUseCustomProxySettings(this);

            if (useHTTPProxyPreference && useCustomProxySettingsPreference && !customProxySettingsValuesValid()) {
                cancelInvalidProxySettingsToast();
                m_invalidProxySettingsToast = Toast.makeText(this, R.string.network_proxy_connect_invalid_values, Toast.LENGTH_SHORT);
                m_invalidProxySettingsToast.show();
                return;
            }

            boolean waitingForPrompt = false;

            boolean wantVPN = m_multiProcessPreferences
                    .getBoolean(getString(R.string.tunnelWholeDevicePreference),
                            false);

            if (wantVPN && Utils.hasVpnService()) {
                // VpnService backwards compatibility: for lazy class loading
                // the VpnService
                // class reference has to be in another function (doVpnPrepare),
                // not just
                // in a conditional branch.
                waitingForPrompt = doVpnPrepare();
            }
            if (!waitingForPrompt) {
                startAndBindTunnelService();
            }
        }

        protected boolean doVpnPrepare() {
            
            // Devices without VpnService support throw various undocumented
            // exceptions, including ActivityNotFoundException and ActivityNotFoundException.
            // For example: http://code.google.com/p/ics-openvpn/source/browse/src/de/blinkt/openvpn/LaunchVPN.java?spec=svn2a81c206204193b14ac0766386980acdc65bee60&name=v0.5.23&r=2a81c206204193b14ac0766386980acdc65bee60#376
            try {
                return vpnPrepare();
            } catch (Exception e) {
                MyLog.e(R.string.tunnel_whole_device_exception, MyLog.Sensitivity.NOT_SENSITIVE);

                // Turn off the option and abort.

                m_tunnelWholeDeviceToggle.setChecked(false);
                m_tunnelWholeDeviceToggle.setEnabled(false);
                updateWholeDevicePreference(false);

                // true = waiting for prompt, although we can't start the
                // activity so onActivityResult won't be called
                return true;
            }
        }

        @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
        protected boolean vpnPrepare() throws ActivityNotFoundException {
            // VpnService: need to display OS user warning. If whole device
            // option is
            // selected and we expect to use VpnService, so the prompt here in
            // the UI
            // before starting the service.

            Intent intent = VpnService.prepare(this);
            if (intent != null) {
                // TODO: can we disable the mode before we reach this this
                // failure point with
                // resolveActivity()? We'll need the intent from prepare() or
                // we'll have to mimic it.
                // http://developer.android.com/reference/android/content/pm/PackageManager.html#resolveActivity%28android.content.Intent,%20int%29

                startActivityForResult(intent, REQUEST_CODE_PREPARE_VPN);

                // startAndBindTunnelService will be called in onActivityResult
                return true;
            }

            return false;
        }

        private boolean isSettingsRestartRequired() {
            SharedPreferences prefs = getSharedPreferences(getString(R.string.moreOptionsPreferencesName), MODE_PRIVATE);

            // check if "excluded apps" list has changed
            String spExcludedAppsString = prefs.getString(getString(R.string.preferenceExcludeAppsFromVpnString), "");
            if (!spExcludedAppsString.equals(m_multiProcessPreferences.getString(getString(R.string.preferenceExcludeAppsFromVpnString), ""))) {
                return true;
            }


            // check if "use proxy" has changed
            boolean useHTTPProxyPreference = prefs.getBoolean(getString(R.string.useProxySettingsPreference),
                    false);
            if (useHTTPProxyPreference != UpstreamProxySettings.getUseHTTPProxy(this)) {
                return true;
            }

            // no further checking if "use proxy" is off and has not
            // changed
            if (!useHTTPProxyPreference) {
                return false;
            }

            //check if "add custom headers" checkbox changed
            boolean addCustomHeadersPreference = prefs.getBoolean(
                    getString(R.string.addCustomHeadersPreference), false);
            if (addCustomHeadersPreference != UpstreamProxySettings.getAddCustomHeadersPreference(this)) {
                return true;
            }

            // "add custom headers" is selected, check if
            // upstream headers string has changed
            if (addCustomHeadersPreference) {
                JSONObject newHeaders = new JSONObject();

                for (int position = 1; position <= 6; position++) {
                    int nameID = getResources().getIdentifier("customProxyHeaderName" + position, "string", getPackageName());
                    int valueID = getResources().getIdentifier("customProxyHeaderValue" + position, "string", getPackageName());

                    String namePrefStr = getResources().getString(nameID);
                    String valuePrefStr = getResources().getString(valueID);

                    String name = prefs.getString(namePrefStr, "");
                    String value = prefs.getString(valuePrefStr, "");
                    try {
                        if (!TextUtils.isEmpty(name)) {
                            JSONArray arr = new JSONArray();
                            arr.put(value);
                            newHeaders.put(name, arr);
                        }
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }

                JSONObject oldHeaders = UpstreamProxySettings.getUpstreamProxyCustomHeaders(this);

                if (0 != oldHeaders.toString().compareTo(newHeaders.toString())) {
                    return true;
                }
            }

            // check if "use custom proxy settings"
            // radio has changed
            boolean useCustomProxySettingsPreference = prefs.getBoolean(
                    getString(R.string.useCustomProxySettingsPreference), false);
            if (useCustomProxySettingsPreference != UpstreamProxySettings.getUseCustomProxySettings(this)) {
                return true;
            }

            // no further checking if "use custom proxy" is off and has
            // not changed
            if (!useCustomProxySettingsPreference) {
                return false;
            }

            // "use custom proxy" is selected, check if
            // host || port have changed
            if (!prefs.getString(getString(R.string.useCustomProxySettingsHostPreference), "")
                    .equals(UpstreamProxySettings.getCustomProxyHost(this))
                    || !prefs.getString(getString(R.string.useCustomProxySettingsPortPreference), "")
                            .equals(UpstreamProxySettings.getCustomProxyPort(this))) {
                return true;
            }

            // check if "use proxy authentication" has changed
            boolean useProxyAuthenticationPreference = prefs.getBoolean(
                    getString(R.string.useProxyAuthenticationPreference), false);
            if (useProxyAuthenticationPreference != UpstreamProxySettings.getUseProxyAuthentication(this)) {
                return true;
            }

            // no further checking if "use proxy authentication" is off
            // and has not changed
            if (!useProxyAuthenticationPreference) {
                return false;
            }

            // "use proxy authentication" is checked, check if
            // username || password || domain have changed
            return !prefs.getString(getString(R.string.useProxyUsernamePreference), "")
                    .equals(UpstreamProxySettings.getProxyUsername(this))
                    || !prefs.getString(getString(R.string.useProxyPasswordPreference), "")
                    .equals(UpstreamProxySettings.getProxyPassword(this))
                    || !prefs.getString(getString(R.string.useProxyDomainPreference), "")
                    .equals(UpstreamProxySettings.getProxyDomain(this));
        }

        @Override
        protected void onActivityResult(int request, int result, Intent data) {
            if (request == REQUEST_CODE_PREPARE_VPN) {
                if(result == RESULT_OK) {
                    startAndBindTunnelService();
                } else if(result == RESULT_CANCELED) {
                    onVpnPromptCancelled();
                }
            } else if (request == REQUEST_CODE_PREFERENCE) {

                // Verify if restart is required before saving new settings
                boolean bRestartRequired = isSettingsRestartRequired();

                // Import 'More Options' values to tray preferences
                String prefName = getString(R.string.moreOptionsPreferencesName);
                m_multiProcessPreferences.migrate(
                        new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithSound), getString(R.string.preferenceNotificationsWithSound)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithVibrate), getString(R.string.preferenceNotificationsWithVibrate)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.preferenceExcludeAppsFromVpnString), getString(R.string.preferenceExcludeAppsFromVpnString)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.downloadWifiOnlyPreference), getString(R.string.downloadWifiOnlyPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.disableTimeoutsPreference), getString(R.string.disableTimeoutsPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useProxySettingsPreference), getString(R.string.useProxySettingsPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useSystemProxySettingsPreference), getString(R.string.useSystemProxySettingsPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPreference), getString(R.string.useCustomProxySettingsPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsHostPreference), getString(R.string.useCustomProxySettingsHostPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPortPreference), getString(R.string.useCustomProxySettingsPortPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useProxyAuthenticationPreference), getString(R.string.useProxyAuthenticationPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useProxyUsernamePreference), getString(R.string.useProxyUsernamePreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useProxyPasswordPreference), getString(R.string.useProxyPasswordPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useProxyDomainPreference), getString(R.string.useProxyDomainPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.addCustomHeadersPreference), getString(R.string.addCustomHeadersPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName1), getString(R.string.customProxyHeaderName1)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue1), getString(R.string.customProxyHeaderValue1)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName2), getString(R.string.customProxyHeaderName2)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue2), getString(R.string.customProxyHeaderValue2)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName3), getString(R.string.customProxyHeaderName3)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue3), getString(R.string.customProxyHeaderValue3)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName4), getString(R.string.customProxyHeaderName4)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue4), getString(R.string.customProxyHeaderValue4)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName5), getString(R.string.customProxyHeaderName5)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue5), getString(R.string.customProxyHeaderValue5)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName6), getString(R.string.customProxyHeaderName6)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue6), getString(R.string.customProxyHeaderValue6))
                );

                if (bRestartRequired) {
                    tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplicationContext(), this::startTunnel);
                }

                if (data != null && data.getBooleanExtra(MoreOptionsPreferenceActivity.INTENT_EXTRA_LANGUAGE_CHANGED, false)) {
                    // This is a bit of a weird hack to cause a restart, but it works
                    // Previous attempts to use the alarm manager or others caused a variable amount of wait (up to about a second)
                    // before the activity would relaunch. This *seems* to provide the best functionality across phones.
                    finish();
                    Intent intent = new Intent(this, StatusActivity.class);
                    intent.putExtra(INTENT_EXTRA_PREVENT_AUTO_START, true);
                    startActivity(intent);
                    System.exit(1);
                }
            }
        }

        protected void onVpnPromptCancelled() {}

        protected void startAndBindTunnelService() {
            boolean wantVPN = m_multiProcessPreferences
                    .getBoolean(getString(R.string.tunnelWholeDevicePreference),
                            false);
            tunnelServiceInteractor.startTunnelService(getApplicationContext(), wantVPN);
        }

        private void stopTunnelService() {
            tunnelServiceInteractor.stopTunnelService();
        }

        protected void onAuthorizationsRemoved() {
            final AppPreferences mp = new AppPreferences(getContext());
            mp.put(this.getString(R.string.persistentAuthorizationsRemovedFlag), false);
        }

        /**
         * Determine if the Psiphon local service is currently running.
         * 
         * @see <a href="http://stackoverflow.com/a/5921190/729729">From
         *      StackOverflow answer:
         *      "android: check if a service is running"</a>
         * @return True if the service is already running, false otherwise.
         */
        protected class SponsorHomePage {
            private class SponsorWebChromeClient extends WebChromeClient {
                private final ProgressBar mProgressBar;

                public SponsorWebChromeClient(ProgressBar progressBar) {
                    super();
                    mProgressBar = progressBar;
                }

                private boolean mStopped = false;

                public void stop() {
                    mStopped = true;
                }

                @Override
                public void onProgressChanged(WebView webView, int progress) {
                    if (mStopped) {
                        return;
                    }

                    mProgressBar.setProgress(progress);
                    mProgressBar.setVisibility(progress == 100 ? View.GONE : View.VISIBLE);
                }
            }

            private class SponsorWebViewClient extends WebViewClient {
                private Timer mTimer;
                private boolean mWebViewLoaded = false;
                private boolean mStopped = false;

                public void stop() {
                    mStopped = true;
                    if (mTimer != null) {
                        mTimer.cancel();
                        mTimer = null;
                    }
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView webView, String url) {
                    if (mStopped) {
                        return true;
                    }

                    if (mTimer != null) {
                        mTimer.cancel();
                        mTimer = null;
                    }

                    if (mWebViewLoaded) {
                        // Do not PsiCash modify the URL, this is a link on the landing page
                        // that has been clicked
                        displayBrowser(getContext(), url, false);
                    }
                    return mWebViewLoaded;
                }

                @Override
                public void onPageFinished(WebView webView, String url) {
                    if (mStopped) {
                        return;
                    }

                    if (!mWebViewLoaded) {
                        mTimer = new Timer();
                        mTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                if (mStopped) {
                                    return;
                                }
                                mWebViewLoaded = true;
                            }
                        }, 2000);
                    }
                }
            }

            private final WebView mWebView;
            private final SponsorWebViewClient mWebViewClient;
            private final SponsorWebChromeClient mWebChromeClient;
            private final ProgressBar mProgressBar;

            @TargetApi(Build.VERSION_CODES.HONEYCOMB)
            public SponsorHomePage(WebView webView, ProgressBar progressBar) {
                mWebView = webView;
                mProgressBar = progressBar;
                mWebChromeClient = new SponsorWebChromeClient(mProgressBar);
                mWebViewClient = new SponsorWebViewClient();

                mWebView.setWebChromeClient(mWebChromeClient);
                mWebView.setWebViewClient(mWebViewClient);
                
                WebSettings webSettings = mWebView.getSettings();
                webSettings.setJavaScriptEnabled(true);
                webSettings.setDomStorageEnabled(true);
                webSettings.setLoadWithOverviewMode(true);
                webSettings.setUseWideViewPort(true);
            }

            public void stop() {
                mWebViewClient.stop();
                mWebChromeClient.stop();
            }

            public void load(String url, int httpProxyPort) {
                // Set WebView proxy only if we are not running in WD mode.
                boolean wantVPN = m_multiProcessPreferences
                        .getBoolean(getString(R.string.tunnelWholeDevicePreference),
                                false);

                if(!wantVPN || !Utils.hasVpnService()) {
                    WebViewProxySettings.setLocalProxy(mWebView.getContext(), httpProxyPort);
                } else {
                    // We are running in WDM, reset WebView proxy if it has been previously set.
                    if(WebViewProxySettings.isLocalProxySet()){
                        WebViewProxySettings.resetLocalProxy(mWebView.getContext());
                    }
                }

                mProgressBar.setVisibility(View.VISIBLE);
                mWebView.loadUrl(url);
            }
        }

        protected void displayBrowser(Context context, String url, boolean b) {

        }

        final protected void displayBrowser(Context context, String urlString) {
            // PsiCash modify URLs by default
            displayBrowser(context, urlString, true);
        }

        protected boolean shouldLoadInEmbeddedWebView(String url) {
            for (String homeTabUrlExclusion : EmbeddedValues.HOME_TAB_URL_EXCLUSIONS) {
                if (url.contains(homeTabUrlExclusion)) {
                    return false;
                }
            }
            return true;
        }

        private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    if (action.equals(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE)) {
                        tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplicationContext(), TabbedActivityBase.this::startTunnel);
                    }
                }
            }
        };
    }
}
