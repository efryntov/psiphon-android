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

package com.psiphon3;

import android.app.AlertDialog;
import android.arch.lifecycle.ViewModelProviders;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import com.android.billingclient.api.SkuDetails;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.billing.BillingRepository;
import com.psiphon3.billing.StatusActivityBillingViewModel;
import com.psiphon3.billing.SubscriptionState;
import com.psiphon3.kin.KinPermissionManager;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.core.ItemNotFoundException;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;


public class StatusActivity
    extends com.psiphon3.psiphonlibrary.MainBase.TabbedActivityBase implements PsiCashFragment.ActiveSpeedBoostListener {
    public static final String ACTION_SHOW_GET_HELP_DIALOG = "com.psiphon3.StatusActivity.SHOW_GET_HELP_CONNECTING_DIALOG";

    private View mRateLimitedTextSection;
    private TextView mRateLimitedText;
    private TextView mRateUnlimitedText;
    private Button mRateLimitSubscribeButton;

    private boolean m_tunnelWholeDevicePromptShown = false;
    private boolean m_firstRun = true;
    private static boolean m_startupPending = false;

    private PsiCashFragment psiCashFragment;

    private PsiphonAdManager psiphonAdManager;
    private Disposable startUpInterstitialDisposable;
    private boolean disableInterstitialOnNextTabChange;
    private PublishRelay<Boolean> activeSpeedBoostRelay;
    private KinPermissionManager kinPermissionManager;
    private Disposable toggleClickDisposable;

    private StatusActivityBillingViewModel billingViewModel;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();

    private View embeddedWebView;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        billingViewModel = ViewModelProviders.of(this).get(StatusActivityBillingViewModel.class);
        billingViewModel.startIab();

        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            kinPermissionManager = new KinPermissionManager();
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        TextView versionLabel = toolbar.findViewById(R.id.toolbar_version_label);
        versionLabel.setText(String.format(Locale.US, "v. %s", EmbeddedValues.CLIENT_VERSION));
        setSupportActionBar(toolbar);

        m_tabHost = (TabHost)findViewById(R.id.tabHost);
        m_tabSpecsList = new ArrayList<>();
        m_toggleButton = (Button)findViewById(R.id.toggleButton);
        m_connectionProgressBar = findViewById(R.id.connectionProgressBar);

        mRateLimitedTextSection = findViewById(R.id.rateLimitedTextSection);
        mRateLimitedText = (TextView)findViewById(R.id.rateLimitedText);
        mRateUnlimitedText = (TextView)findViewById(R.id.rateUnlimitedText);
        mRateLimitSubscribeButton = (Button)findViewById(R.id.rateLimitUpgradeButton);

        LayoutInflater inflater = (LayoutInflater)getBaseContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        embeddedWebView = inflater.inflate(R.layout.embedded_webview_layout, null);



        // PsiCash and rewarded video fragment
        FragmentManager fm = getSupportFragmentManager();
        psiCashFragment = (PsiCashFragment) fm.findFragmentById(R.id.psicash_fragment_container);
        if(psiCashFragment != null) {
            psiCashFragment.setActiveSpeedBoostListener(this);
        }

        // Rate limit observable
        Observable<RateLimitMode> currentRateLimitModeObservable =
                billingViewModel.subscriptionStateFlowable()
                        .toObservable()
                        .map(subscriptionState -> {
                            switch (subscriptionState.status()) {
                                case HAS_UNLIMITED_SUBSCRIPTION:
                                case HAS_TIME_PASS:
                                    return RateLimitMode.UNLIMITED_SUBSCRIPTION;
                                case HAS_LIMITED_SUBSCRIPTION:
                                    return RateLimitMode.LIMITED_SUBSCRIPTION;
                                default:
                                    return RateLimitMode.AD_MODE_LIMITED;
                            }
                        });

        activeSpeedBoostRelay = PublishRelay.create();

        // Update rate limit badge and 'Subscribe' button UI
        compositeDisposable.add(Observable.combineLatest(currentRateLimitModeObservable, activeSpeedBoostRelay,
                ((BiFunction<RateLimitMode, Boolean, Pair>) Pair::new))
                .map(pair -> {
                    RateLimitMode rateLimitMode = (RateLimitMode) pair.first;
                    Boolean hasActiveSpeedBoost = (Boolean) pair.second;
                    if (rateLimitMode == RateLimitMode.AD_MODE_LIMITED) {
                        if (hasActiveSpeedBoost) {
                            return RateLimitMode.SPEED_BOOST;
                        } else {
                            return RateLimitMode.AD_MODE_LIMITED;
                        }
                    }
                    return rateLimitMode;
                })
                .doOnNext(this::setRateLimitUI)
                .subscribe()
        );

        // bootstrap the activeSpeedBoost observable
        activeSpeedBoostRelay.accept(Boolean.FALSE);

        // ads
        psiphonAdManager = new PsiphonAdManager(this, findViewById(R.id.largeAdSlot),
                () -> onSubscribeButtonClick(null), true);
        psiphonAdManager.startLoadingAds();

        // Components IAB state notifications and PsiCash tab view state Rx subscription.
        compositeDisposable.add(
                billingViewModel.subscriptionStateFlowable()
                        .doOnNext(subscriptionState -> {
                            MyLog.g("Billing: subscription status: " + subscriptionState.status());
                            if (subscriptionState.error() != null) {
                                MyLog.g("Subscription state billing error: " + subscriptionState.error());
                            }
                            psiphonAdManager.onSubscriptionState(subscriptionState);
                            // TODO: make some kind of disable/enable PsiCash UI
                            /*
                            if (subscriptionState.hasValidPurchase()) {
                                hidePsiCashTab();
                            } else {
                                showPsiCashTabIfHasValidToken();
                            }
                            */
                        })
                        .subscribe()
        );

        // Setup Kin UI
        if (kinPermissionManager != null &&
                kinPermissionManager.hasOptInPreference(getApplicationContext())) {
            compositeDisposable.add(
                    billingViewModel.subscriptionStateFlowable()
                            .concatMapMaybe(subscriptionState -> {
                                if (subscriptionState.hasValidPurchase()) {
                                    enableKinOptInCheckBox(false);
                                    return Maybe.empty();
                                } else {
                                    enableKinOptInCheckBox(true);
                                    return kinPermissionManager.getUsersAgreementToKin(this)
                                            .doOnSuccess(this::setKinOptInUiState)
                                            .toMaybe();
                                }
                            })
                            .subscribe()
            );
        } else {
            enableKinOptInCheckBox(false);
        }

        compositeDisposable.add(
                tunnelServiceInteractor.tunnelStateFlowable()
                        // Update app UI state
                        .doOnNext(state -> psiphonAdManager.onTunnelConnectionState(state))
                        .doOnNext(state -> psiCashFragment.onTunnelConnectionState(state))
                        .subscribe()
        );

        setupActivityLayout();

        HandleCurrentIntent();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isFirstRun", m_firstRun);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        m_firstRun = savedInstanceState.getBoolean("isFirstRun");
    }

    private void preventAutoStart() {
        m_firstRun = false;
    }

    private boolean shouldAutoStart() {
        return m_firstRun &&
                !tunnelServiceInteractor.isServiceRunning(getApplicationContext()) &&
                !getIntent().getBooleanExtra(INTENT_EXTRA_PREVENT_AUTO_START, false);
    }

    @Override
    protected void onPause()
    {
        super.onPause();
    }

    @Override
    protected void onResume() {
        billingViewModel.queryCurrentSubscriptionStatus();
        billingViewModel.queryAllSkuDetails();
        super.onResume();
        if (m_startupPending) {
            m_startupPending = false;
            doStartUp();
        } else {
            compositeDisposable.add(
                    billingViewModel.subscriptionStateFlowable()
                            .firstOrError()
                            .doOnSuccess(subscriptionState -> {
                                // Automatically start if user has a valid purchase or if IAB check failed
                                // the IAB status check will be triggered again in onResume
                                if (subscriptionState.hasValidPurchase() || subscriptionState.status() == SubscriptionState.Status.IAB_FAILURE) {
                                    if (shouldAutoStart()) {
                                        preventAutoStart();
                                        doStartUp();
                                    }
                                }

                            })
                            .subscribe()
            );
        }
    }

    @Override
    public void onDestroy() {
        billingViewModel.stopIab();
        compositeDisposable.dispose();

        psiphonAdManager.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // If the app is already foreground (so onNewIntent is being called),
        // the incoming intent is not automatically set as the activity's intent
        // (i.e., the intent returned by getIntent()). We want this behaviour,
        // so we'll set it explicitly.
        setIntent(intent);

        // Handle explicit intent that is received when activity is already running
        HandleCurrentIntent();
    }

    @Override
    public void onTabChanged(String tabId) {
        if(mayTriggerInterstitial(tabId)) {
            psiphonAdManager.onTabChanged();
        }
        super.onTabChanged(tabId);
    }

    private boolean mayTriggerInterstitial(String tabId) {
        if(disableInterstitialOnNextTabChange) {
            disableInterstitialOnNextTabChange = false;
            return false;
        }
        if(tabId.equals(PSICASH_TAB_TAG)) {
            return false;
        }
        return true;
    }

    @Override
    protected void onAuthorizationsRemoved() {
        MyLog.g("PsiCash: received onAuthorizationsRemoved() notification");
        super.onAuthorizationsRemoved();
        psiCashFragment.removePurchases(getApplicationContext());
    }

    protected void HandleCurrentIntent() {
        Intent intent = getIntent();
        if (intent == null || intent.getAction() == null) {
            return;
        }
        // StatusActivity is exposed to other apps because it is declared as an entry point activity of the app in the manifest.
        // For the purpose of handling internal intents, such as handshake, etc., from the tunnel service we have declared a not
        // exported activity alias 'com.psiphon3.psiphonlibrary.TunnelIntentsHandler' that should act as a proxy for StatusActivity.
        // We expect our own intents have a component set to 'com.psiphon3.psiphonlibrary.TunnelIntentsHandler', all other intents
        // should be ignored.
        ComponentName tunnelIntentsActivityComponentName = new ComponentName(this, "com.psiphon3.psiphonlibrary.TunnelIntentsHandler");
        if (!tunnelIntentsActivityComponentName.equals(intent.getComponent())) {
            return;
        }

        if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_HANDSHAKE)) {
            Bundle data = intent.getExtras();
            if(data != null) {
                ArrayList<String> homePages = data.getStringArrayList(TunnelManager.DATA_TUNNEL_STATE_HOME_PAGES);
                if (homePages != null && homePages.size() > 0) {
                    String url = homePages.get(0);
                    // At this point we're showing the URL in either the embedded webview or in a browser.
                    // Some URLs are excluded from being embedded as home pages.
                    if (shouldLoadInEmbeddedWebView(url)) {
                        boolean isVpn = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_VPN, false);
                        int httpProxyPort = isVpn ? 0 : data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT, 0);
                        loadInEmbeddedWebView(url, httpProxyPort);
                    } else {
                        displayBrowser(this, url);
                    }
                }
            }

            // We only want to respond to the HANDSHAKE_SUCCESS action once,
            // so we need to clear it (by setting it to a non-special intent).
            setIntent(new Intent(
                    "ACTION_VIEW",
                    null,
                    this,
                    this.getClass()));
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE)) {
            // Switch to settings tab
            disableInterstitialOnNextTabChange = true;
            m_tabHost.setCurrentTabByTag(SETTINGS_TAB_TAG);

            // Set egress region preference to 'Best Performance'
            updateEgressRegionPreference(PsiphonConstants.REGION_CODE_ANY);

            // Set region selection to 'Best Performance' too
            m_regionSelector.setSelectionByValue(PsiphonConstants.REGION_CODE_ANY);

            // Show "Selected region unavailable" toast
            showToast(R.string.selected_region_currently_not_available);

            // We only want to respond to the INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE action once,
            // so we need to clear it (by setting it to a non-special intent).
            setIntent(new Intent(
                    "ACTION_VIEW",
                    null,
                    this,
                    this.getClass()));
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_VPN_REVOKED)) {
            showVpnAlertDialog(R.string.StatusActivity_VpnRevokedTitle, R.string.StatusActivity_VpnRevokedMessage);
        } else if (0 == intent.getAction().compareTo(ACTION_SHOW_GET_HELP_DIALOG)) {
            // OK to be null because we don't use it
            onGetHelpConnectingClick(null);
        }
    }

    private void loadInEmbeddedWebView(String url, int httpProxyPort) {
        final WebView webView = embeddedWebView.findViewById(R.id.sponsorWebView);
        final ProgressBar progressBar = embeddedWebView.findViewById(R.id.sponsorWebViewProgressBar);

        m_sponsorHomePage = new SponsorHomePage(webView, progressBar);
        m_sponsorHomePage.load(url, httpProxyPort);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(embeddedWebView);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            dialog.dismiss();
        });

        AlertDialog alertDialog = builder.create();
        alertDialog.setOnDismissListener(dialogInterface -> {
            webView.loadUrl("about:blank");
            ((ViewGroup)embeddedWebView.getParent()).removeView(embeddedWebView);
        });

        alertDialog.show();
    }

    public void onToggleClick(View v) {
        // Only check for payment when starting in WDM, also make sure subscribers don't get prompted for Kin.
        boolean wantVPN = m_multiProcessPreferences
                .getBoolean(getString(R.string.tunnelWholeDevicePreference),
                        false);

        if (wantVPN && Utils.hasVpnService() && kinPermissionManager != null) {
            // prevent multiple confirmation dialogs
            if (toggleClickDisposable != null && !toggleClickDisposable.isDisposed()) {
                return;
            }
            // else
            toggleClickDisposable = billingViewModel.subscriptionStateFlowable()
                    .switchMapSingle(subscriptionState -> {
                        if (subscriptionState.hasValidPurchase()) {
                            // Return any object to complete subscription, the value is ignored
                            return Single.just(new Object());
                        }
                        return Single.fromCallable(() -> kinPermissionManager.isOptedIn(getApplicationContext()))
                                .flatMap(optedIn -> optedIn ?
                                                kinPermissionManager.confirmDonation(this) :
                                                Single.just(false)
                                )
                                .doOnSuccess(this::notifyTunnelKinState)
                                .doOnSuccess(this::setKinOptInUiState);

                    })
                    .firstOrError()
                    .ignoreElement()
                    .doOnComplete(this::doToggle)
                    .subscribe();
            compositeDisposable.add(toggleClickDisposable);
        } else {
            doToggle();
        }
    }

    public void onGetHelpConnectingClick(View v) {
        showConnectionHelpDialog(this, R.layout.dialog_get_help_connecting);
    }

    public void onHowToHelpClick(View view) {
        showConnectionHelpDialog(this, R.layout.dialog_how_to_help_connect);
    }

    @Override
    public void onFeedbackClick(View v)
    {
        Intent feedbackIntent = new Intent(this, FeedbackActivity.class);
        startActivity(feedbackIntent);
    }

    public void onKinEnabledClick(View v) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return;
        }
        // Assume this will always be the kin enabled checkbox
        CheckBox checkBox = (CheckBox) v;
        // Prevent the default toggle, that's handled automatically by a subscription to the opted-in state
        checkBox.setChecked(!checkBox.isChecked());
        Single<Boolean> kinOptInStateSingle;
        if (kinPermissionManager.isOptedIn(this)) {
            kinOptInStateSingle = kinPermissionManager.optOut(this);
        } else {
            kinOptInStateSingle = kinPermissionManager.optIn(this);
        }
        compositeDisposable.add(
                kinOptInStateSingle
                        .doOnSuccess(this::setKinOptInUiState)
                        .doOnSuccess(this::notifyTunnelKinState)
                        .subscribe());
    }

    @Override
    protected void startUp() {
        if (startUpInterstitialDisposable != null && !startUpInterstitialDisposable.isDisposed()) {
            // already in progress, do nothing
            return;
        }
        int countdownSeconds = 10;
        startUpInterstitialDisposable = psiphonAdManager.getCurrentAdTypeObservable()
                .take(1)
                .switchMap(adResult -> {
                    if (adResult.type() == PsiphonAdManager.AdResult.Type.NONE) {
                        doStartUp();
                        return Observable.empty();
                    }
                    else if (adResult.type() == PsiphonAdManager.AdResult.Type.TUNNELED) {
                        MyLog.g("startUp interstitial bad ad type: " + adResult.type());
                        return Observable.empty();
                    }

                    Observable<PsiphonAdManager.InterstitialResult> interstitial =
                            Observable.just(adResult)
                                    .compose(psiphonAdManager.getInterstitialWithTimeoutForAdType(countdownSeconds, TimeUnit.SECONDS))
                                    .doOnNext(interstitialResult -> {
                                        if (interstitialResult.state() == PsiphonAdManager.InterstitialResult.State.READY) {
                                            m_startupPending = true;
                                            interstitialResult.show();
                                        }
                                    })
                                    .doOnComplete(() -> {
                                        if(m_startupPending) {
                                            m_startupPending = false;
                                            doStartUp();
                                        }
                                    });

                    Observable<Long> countdown =
                            Observable.intervalRange(0, countdownSeconds, 0, 1, TimeUnit.SECONDS)
                                    .map(t -> countdownSeconds - t)
                                    .concatWith(Observable.error(new TimeoutException("Ad countdown timeout.")))
                                    .doOnNext(t -> runOnUiThread(() ->m_toggleButton.setText(String.format(Locale.US, "%d", t))));

                    return countdown
                            .takeUntil(interstitial)
                            .doOnError(__->doStartUp());
                })
                .onErrorResumeNext(Observable.empty())
                .subscribe();
        compositeDisposable.add(startUpInterstitialDisposable);
    }

    private void doStartUp() {
        // cancel any ongoing startUp subscription
        if(startUpInterstitialDisposable != null) {
            startUpInterstitialDisposable.dispose();
        }
        // If the user hasn't set a whole-device-tunnel preference, show a prompt
        // (and delay starting the tunnel service until the prompt is completed)
        boolean hasPreference;
        try {
            m_multiProcessPreferences.getBoolean(getString(R.string.tunnelWholeDevicePreference));
            hasPreference = true;
        } catch (ItemNotFoundException e) {
            hasPreference = false;
        }
        if (Utils.hasVpnService() && !hasPreference) {
            if (!m_tunnelWholeDevicePromptShown && !this.isFinishing()) {
                final Context context = this;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        AlertDialog dialog = new AlertDialog.Builder(context)
                                .setCancelable(false)
                                .setOnKeyListener(
                                        new DialogInterface.OnKeyListener() {
                                            @Override
                                            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                                                // Don't dismiss when hardware search button is clicked (Android 2.3 and earlier)
                                                return keyCode == KeyEvent.KEYCODE_SEARCH;
                                            }
                                        })
                                .setTitle(R.string.StatusActivity_WholeDeviceTunnelPromptTitle)
                                .setMessage(R.string.StatusActivity_WholeDeviceTunnelPromptMessage)
                                .setPositiveButton(R.string.StatusActivity_WholeDeviceTunnelPositiveButton,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int whichButton) {
                                                // Persist the "on" setting
                                                updateWholeDevicePreference(true);
                                                startTunnel();
                                            }
                                        })
                                .setNegativeButton(R.string.StatusActivity_WholeDeviceTunnelNegativeButton,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int whichButton) {
                                                // Turn off and persist the "off" setting
                                                m_tunnelWholeDeviceToggle.setChecked(false);
                                                updateWholeDevicePreference(false);
                                                startTunnel();
                                            }
                                        })
                                .setOnCancelListener(
                                        new DialogInterface.OnCancelListener() {
                                            @Override
                                            public void onCancel(DialogInterface dialog) {
                                                // Don't change or persist preference (this prompt may reappear)
                                                startTunnel();
                                            }
                                        })
                                .show();
                        // Our text no longer fits in the AlertDialog buttons on Lollipop, so force the
                        // font size (on older versions, the text seemed to be scaled down to fit).
                        // TODO: custom layout
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                        }
                    }
                });

                m_tunnelWholeDevicePromptShown = true;
            } else {
                // ...there's a prompt already showing (e.g., user hit Home with the
                // prompt up, then resumed Psiphon)
            }
            // ...wait and let onClick handlers will start tunnel
        } else {
            // No prompt, just start the tunnel (if not already running)
            startTunnel();
        }
    }

    @Override
    public void displayBrowser(Context context, String urlString, boolean shouldPsiCashModifyUrls) {
        if (shouldPsiCashModifyUrls) {
            // Add PsiCash parameters
            urlString = PsiCashModifyUrl(urlString);
        }

        // Notify PsiCash fragment so it will know to refresh state on next app foreground.
        psiCashFragment.onOpenHomePage();

        try {
            boolean wantVPN = m_multiProcessPreferences
                    .getBoolean(getString(R.string.tunnelWholeDevicePreference),
                            false);

            if (wantVPN && Utils.hasVpnService()) {
                // TODO: support multiple home pages in whole device mode. This is
                // disabled due to the case where users haven't set a default browser
                // and will get the prompt once per home page.

                // If URL is not empty we will try to load in an external browser, otherwise we will
                // try our best to open an external browser instance without specifying URL to load
                // or will load "about:blank" URL if that fails.

                // Prepare browser starting intent.
                Intent browserIntent;
                if (TextUtils.isEmpty(urlString)) {
                    // If URL is empty, just start the app.
                    browserIntent = new Intent(Intent.ACTION_MAIN);
                } else {
                    // If URL is not empty, start the app with URL load intent.
                    browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
                }
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // query default 'URL open' intent handler.
                Intent queryIntent;
                if (TextUtils.isEmpty(urlString)) {
                    queryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.org"));
                } else {
                    queryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
                }
                ResolveInfo resolveInfo = getPackageManager().resolveActivity(queryIntent, PackageManager.MATCH_DEFAULT_ONLY);

                // Try and start default intent handler application if there is one
                if (resolveInfo != null &&
                        resolveInfo.activityInfo != null &&
                        resolveInfo.activityInfo.name != null &&
                        !resolveInfo.activityInfo.name.toLowerCase().contains("resolver")) {
                    browserIntent.setClassName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
                    context.startActivity(browserIntent);
                } else { // There is no default handler, try chrome
                    browserIntent.setPackage("com.android.chrome");
                    try {
                        context.startActivity(browserIntent);
                    } catch (ActivityNotFoundException ex) {
                        // We tried to open Chrome and it is not installed,
                        // so reinvoke with the default behaviour
                        browserIntent.setPackage(null);
                        // If URL is empty try loading a special URL 'about:blank'
                        if (TextUtils.isEmpty(urlString)) {
                            browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("about:blank"));
                        }
                        context.startActivity(browserIntent);
                    }
                }
            } else {
                Uri uri = null;
                if (!TextUtils.isEmpty(urlString)) {
                    uri = Uri.parse(urlString);
                }

                Intent intent = new Intent(
                        "ACTION_VIEW",
                        uri,
                        context,
                        org.zirco.ui.activities.MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // This intent displays the Zirco browser.
                // We use "extras" to communicate Psiphon settings to Zirco.
                // When Zirco is first created, it will use the homePages
                // extras to open tabs for each home page, respectively. When the intent
                // triggers an existing Zirco instance (and it's a singleton) this extra
                // is ignored and the browser is displayed as-is.
                // When a uri is specified, it will open as a new tab. This is
                // independent of the home pages.
                // Note: Zirco now directly accesses PsiphonData to get the current
                // local HTTP proxy port for WebView tunneling.

                if (urlString != null) {
                        if(shouldPsiCashModifyUrls) {
                            // Add PsiCash parameters
                        urlString = PsiCashModifyUrl(urlString);
                    }
                    intent.putExtra("homePages", new ArrayList<>(Collections.singletonList(urlString)));
                }

                context.startActivity(intent);
            }
        } catch (ActivityNotFoundException e) {
            // Thrown by startActivity; in this case, we ignore and the URI isn't opened
        }
    }

    @Override
    public void onActiveSpeedBoost(Boolean hasActiveSpeedBoost) {
        activeSpeedBoostRelay.accept(hasActiveSpeedBoost);
    }

    private void notifyTunnelKinState(Boolean state) {
        tunnelServiceInteractor.onKinOptInState(state);
    }

    enum RateLimitMode {AD_MODE_LIMITED, LIMITED_SUBSCRIPTION, UNLIMITED_SUBSCRIPTION, SPEED_BOOST}

    private void enableKinOptInCheckBox(boolean enable) {
        CheckBox checkBoxKinEnabled = findViewById(R.id.check_box_kin_enabled);
        // Disable the checkbox if Kin opt-in preference was never initialized
        enable = enable &&
                kinPermissionManager != null &&
                kinPermissionManager.hasOptInPreference(getApplicationContext());

        if(enable) {
            checkBoxKinEnabled.setVisibility(View.VISIBLE);
        } else {
            checkBoxKinEnabled.setVisibility(View.GONE);
        }
    }

    private void setRateLimitUI(RateLimitMode rateLimitMode) {
        // Update UI elements showing the current speed.
        if (rateLimitMode == RateLimitMode.UNLIMITED_SUBSCRIPTION) {
            mRateLimitedText.setVisibility(View.GONE);
            mRateUnlimitedText.setVisibility(View.VISIBLE);
            mRateLimitSubscribeButton.setVisibility(View.GONE);
            mRateLimitedTextSection.setVisibility(View.VISIBLE);
        } else{
            if(rateLimitMode == RateLimitMode.AD_MODE_LIMITED) {
                mRateLimitedText.setText(getString(R.string.rate_limit_text_limited, 2));
            } else if (rateLimitMode == RateLimitMode.LIMITED_SUBSCRIPTION) {
                mRateLimitedText.setText(getString(R.string.rate_limit_text_limited, 5));
            } else if (rateLimitMode == RateLimitMode.SPEED_BOOST) {
                mRateLimitedText.setText(getString(R.string.rate_limit_text_speed_boost));
            }
            mRateLimitedText.setVisibility(View.VISIBLE);
            mRateUnlimitedText.setVisibility(View.GONE);
            mRateLimitSubscribeButton.setVisibility(View.VISIBLE);
            mRateLimitedTextSection.setVisibility(View.VISIBLE);
        }
    }


    private final int PAYMENT_CHOOSER_ACTIVITY = 20001;

    public void onSubscribeButtonClick(View v) {
        Utils.MyLog.g("StatusActivity::onSubscribeButtonClick");
        compositeDisposable.add(
                billingViewModel.subscriptionStateFlowable()
                        .firstOrError()
                        .subscribe(subscriptionState -> {
                            switch (subscriptionState.status()) {
                                case HAS_UNLIMITED_SUBSCRIPTION:
                                case HAS_TIME_PASS:
                                    // User has a subscription, do nothing, the 'Subscribe' button
                                    // visibility will be updated by rate limit badge UI Rx subscription
                                    // that we have set up in onCreate().
                                    return;

                                case HAS_LIMITED_SUBSCRIPTION:
                                    // If user has limited subscription launch upgrade to unlimited
                                    // flow and replace current subscription sku.
                                    String currentSku = subscriptionState.purchase().getSku();
                                    compositeDisposable.add(
                                            billingViewModel.getUnlimitedSubscriptionSkuDetails()
                                                    .flatMapCompletable(skuDetailsList -> {
                                                        if (skuDetailsList.size() == 1) {
                                                            return billingViewModel.launchFlow(this, currentSku, skuDetailsList.get(0));
                                                        }
                                                        // else
                                                        return Completable.error(
                                                                new IllegalArgumentException("Bad unlimited subscription sku details list size: "
                                                                        + skuDetailsList.size())
                                                        );
                                                    })
                                                    .doOnError(err -> {
                                                        Utils.MyLog.g("Upgrade limited subscription error: " + err);
                                                        // Show "Subscription options not available" toast.
                                                        showToast(R.string.subscription_options_currently_not_available);
                                                    })
                                                    .onErrorComplete()
                                                    .subscribe()
                                    );
                                    return;

                                default:
                                    // If user has no subscription launch PaymentChooserActivity
                                    // to show all available subscriptions options.
                                    compositeDisposable.add(
                                            billingViewModel.allSkuDetailsSingle()
                                                    .toObservable()
                                                    .flatMap(Observable::fromIterable)
                                                    .filter(skuDetails -> {
                                                        String sku = skuDetails.getSku();
                                                        return BillingRepository.IAB_TIMEPASS_SKUS_TO_DAYS.containsKey(sku) ||
                                                                sku.equals(BillingRepository.IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU) ||
                                                                sku.equals(BillingRepository.IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU);
                                                    })
                                                    .map(SkuDetails::getOriginalJson)
                                                    .toList()
                                                    .doOnSuccess(jsonSkuDetailsList -> {
                                                        if(jsonSkuDetailsList.size() > 0) {
                                                            Intent paymentChooserActivityIntent = new Intent(this, PaymentChooserActivity.class);
                                                            paymentChooserActivityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                                            paymentChooserActivityIntent.putStringArrayListExtra(
                                                                    PaymentChooserActivity.SKU_DETAILS_ARRAY_LIST_EXTRA,
                                                                    new ArrayList<>(jsonSkuDetailsList));
                                                            startActivityForResult(paymentChooserActivityIntent, PAYMENT_CHOOSER_ACTIVITY);
                                                        } else {
                                                            showToast(R.string.subscription_options_currently_not_available);
                                                        }
                                                    })
                                                    .subscribe()
                                    );
                            }
                        })
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PAYMENT_CHOOSER_ACTIVITY) {
            if (resultCode == RESULT_OK) {
                String skuString = data.getStringExtra(PaymentChooserActivity.USER_PICKED_SKU_DETAILS_EXTRA);
                try {
                    if (TextUtils.isEmpty(skuString)) {
                        throw new IllegalArgumentException("SKU is empty.");
                    }
                    SkuDetails skuDetails = new SkuDetails(skuString);
                    billingViewModel.launchFlow(this, skuDetails).subscribe();
                } catch (JSONException | IllegalArgumentException e) {
                    Utils.MyLog.g("StatusActivity::onActivityResult purchase SKU error: " + e);
                    // Show "Subscription options not available" toast.
                    showToast(R.string.subscription_options_currently_not_available);
                }
            } else {
                Utils.MyLog.g("StatusActivity::onActivityResult: PaymentChooserActivity: canceled");
            }
        } else if (requestCode == PsiCashFragment.PSICASH_DETAILS_ACTIVITY_RESULT) {
            psiCashFragment.onActivityResult(requestCode, resultCode, data);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void showToast(int stringResId) {
        Toast toast = Toast.makeText(this, stringResId, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.show();
    }

    @Override
    protected void onVpnPromptCancelled() {
        showVpnAlertDialog(R.string.StatusActivity_VpnPromptCancelledTitle, R.string.StatusActivity_VpnPromptCancelledMessage);
    }

    private void showVpnAlertDialog(int titleId, int messageId) {
        new AlertDialog.Builder(getContext())
                .setCancelable(true)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(titleId)
                .setMessage(messageId)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void setKinOptInUiState(boolean optedIn) {
        CheckBox checkBoxKinEnabled = findViewById(R.id.check_box_kin_enabled);
        if (optedIn) {
            checkBoxKinEnabled.setChecked(true);
        } else {
            checkBoxKinEnabled.setChecked(false);
        }
    }
}
