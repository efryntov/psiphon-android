package com.psiphon3;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class AppUpdateHelper implements InstallStateUpdatedListener {

    public interface CompletionListener {
        void onUpdateCompleted();
    }

    private static final String PREFS_KEY = "APP_UPDATE_PREFS";
    private static final String IGNORED_VERSIONS = "IGNORED_VERSIONS";
    private static final String LAST_PROMPTED_VERSION = "LAST_PROMPTED_VERSION";
    private static final String LAST_RECORDED_STALENESS = "LAST_RECORDED_STALENESS";

    // Staleness threshold in days after which we will prompt for an update
    // even if the user has explicitly ignored it before.
    private static final int STALENESS_THRESHOLD_DAYS = 30;
    // High priority threshold level for forced updates.
    private static final int HIGH_PRIORITY_THRESHOLD = 5;
    private static final int DEFAULT_STALENESS = 0;

    private final AppUpdateManager updateManager;
    private final AppCompatActivity activity;
    private final SharedPreferences prefs;
    private final Object prefsLock = new Object();
    private final ActivityResultLauncher<IntentSenderRequest> updateLauncher;
    private final List<CompletionListener> pendingListeners = new ArrayList<>();
    private final Object listenerLock = new Object();
    private final @NonNull View snackBarAnchor;

    private @AppUpdateType int currentUpdateType = AppUpdateType.FLEXIBLE;
    private final AtomicBoolean isUpdateCheckInProgress = new AtomicBoolean(false);
    private final AtomicBoolean isUpdateFlowInProgress = new AtomicBoolean(false);
    private volatile boolean isDestroyed = false;

    public AppUpdateHelper(@NonNull AppCompatActivity activity,
                           @NonNull ActivityResultLauncher<IntentSenderRequest> updateLauncher,
                           @NonNull View snackbarAnchor) {
        this(activity, AppUpdateManagerFactory.create(activity),
                activity.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE), updateLauncher, snackbarAnchor);
    }

    @VisibleForTesting
    AppUpdateHelper(@NonNull AppCompatActivity activity,
                    @NonNull AppUpdateManager updateManager,
                    @NonNull SharedPreferences prefs,
                    @NonNull ActivityResultLauncher<IntentSenderRequest> updateLauncher,
                    @NonNull View snackbarAnchor) {
        this.activity = activity;
        this.updateManager = updateManager;
        this.prefs = prefs;
        this.updateLauncher = updateLauncher;
        this.snackBarAnchor = snackbarAnchor;
        updateManager.registerListener(this);
    }

    public void addCompletionListener(CompletionListener listener) {
        synchronized (listenerLock) {
            if (listener != null) {
                pendingListeners.add(listener);
            }
        }
    }

    // Handle resume scenarios and optionally check for new updates
    public void handleUpdate(boolean shouldCheckForNew) {
        if (!isUpdateCheckInProgress.compareAndSet(false, true) ||
                isUpdateFlowInProgress.get()) {
            MyLog.i("AppUpdateHelper: update check already in progress, skipping");
            return;
        }

        updateManager.getAppUpdateInfo()
                .addOnSuccessListener(info -> {
                    isUpdateCheckInProgress.set(false);
                    if (! isDestroyed) {
                        handleUpdateInfo(info, shouldCheckForNew);
                    }
                })
                .addOnFailureListener(e -> {
                    isUpdateCheckInProgress.set(false);
                    if (!isDestroyed) {
                        notifyCompletion();
                    }
                    MyLog.e("AppUpdateHelper: failed to get update info: " + e.getMessage());
                });
    }

    private void handleUpdateInfo(AppUpdateInfo info, boolean shouldCheckForNew) {
        try {
            // ALWAYS handle any existing update flows (every onResume)
            if (currentUpdateType == AppUpdateType.FLEXIBLE &&
                    info.installStatus() == InstallStatus.DOWNLOADED) {
                // Show snackbar for downloaded flexible update
                showFlexibleUpdateSnackbar();
                notifyCompletion(); // Non-blocking UI, complete immediately
                return;
            }

            if (currentUpdateType == AppUpdateType.IMMEDIATE &&
                    info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                // Resume interrupted immediate update
                resumeImmediateUpdate(info);
                // Will complete in handleUpdateResult() when user responds
                return;
            }

            // ONLY check for NEW updates if shouldCheckForNew is true
            if (shouldCheckForNew && info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                processAvailableUpdate(info);
                // Will complete in handleUpdateResult() when user responds to dialog, or immediately if no dialog
            } else if (shouldCheckForNew) {
                MyLog.i("AppUpdateHelper: no updates available");
                notifyCompletion(); // No UI shown, complete immediately
            } else {
                notifyCompletion(); // Resume only, no new checks, complete immediately
            }
            // If shouldCheckForNew is false, do nothing (no new update checks)
        } catch (Exception e) {
            MyLog.e("AppUpdateHelper: error processing update info: " + e.getMessage());
            notifyCompletion();
        }
    }

    private void processAvailableUpdate(AppUpdateInfo info) {
        int versionCode = info.availableVersionCode();
        int priority = info.updatePriority();
        int staleness = info.clientVersionStalenessDays() != null ?
                info.clientVersionStalenessDays() : DEFAULT_STALENESS;

        // Get policy internally
        ServerPolicy policy = getServerPolicy();

        // Check if this is a forced update
        boolean isForced = shouldForceUpdate(priority, policy);

        if (isForced) {
            startBestEffortUpdate(info);
        } else if (shouldShowOptionalUpdate(versionCode, staleness)) {
            showOptionalUpdate(info, versionCode);
        } else {
            MyLog.i("AppUpdateHelper: skipping update prompt - conditions not met for version: " + versionCode);
            notifyCompletion(); // No UI shown, complete immediately
        }
    }

    private ServerPolicy getServerPolicy() {
        // Get policy however makes sense for your architecture
        return null; // Placeholder
    }

    private boolean shouldForceUpdate(int priority, ServerPolicy policy) {
        return priority >= HIGH_PRIORITY_THRESHOLD ||
                (policy != null && policy.shouldForceUpdate(BuildConfig.VERSION_CODE));
    }

    private boolean shouldShowOptionalUpdate(int versionCode, int staleness) {
        synchronized (prefsLock) {
            // For stale versions, show if staleness has changed since last recorded
            if (staleness >= STALENESS_THRESHOLD_DAYS) {
                int lastRecordedStaleness = prefs.getInt(LAST_RECORDED_STALENESS, -1);
                return staleness != lastRecordedStaleness;
            }

            // For newer versions, only show if user hasn't rejected this version before
            return !isVersionIgnored(versionCode);
        }
    }

    private boolean isVersionIgnored(int versionCode) {
        Set<String> ignoredSet = prefs.getStringSet(IGNORED_VERSIONS, new HashSet<>());
        return ignoredSet.contains(String.valueOf(versionCode));
    }

    private void showOptionalUpdate(AppUpdateInfo info, int versionCode) {
        if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            startUpdateFlow(info, AppUpdateType.FLEXIBLE);
            synchronized (prefsLock) {
                int staleness = info.clientVersionStalenessDays() != null ?
                        info.clientVersionStalenessDays() : DEFAULT_STALENESS;
                prefs.edit()
                        .putInt(LAST_RECORDED_STALENESS, staleness)
                        .putInt(LAST_PROMPTED_VERSION, versionCode)
                        .apply();
            }
        } else {
            MyLog.w("AppUpdateHelper: flexible update not allowed for version: " + versionCode);
            notifyCompletion(); // No update shown, complete immediately
        }
    }

    private void startBestEffortUpdate(AppUpdateInfo info) {
        if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            startUpdateFlow(info, AppUpdateType.IMMEDIATE);
        } else if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            startUpdateFlow(info, AppUpdateType.FLEXIBLE);
        } else {
            // No update types allowed - just log and do nothing
            MyLog.w("AppUpdateHelper: no update types allowed for forced update - possible device/policy restriction");
            notifyCompletion(); // No update shown, complete immediately
        }
    }

    private void startUpdateFlow(AppUpdateInfo info, @AppUpdateType int type) {
        if (!isUpdateFlowInProgress.compareAndSet(false, true)) {
            MyLog.i("AppUpdateHelper: update flow already in progress, skipping");
            return;
        }

        AppUpdateOptions options = AppUpdateOptions.newBuilder(type).build();
        updateManager.startUpdateFlowForResult(info, updateLauncher, options);
        currentUpdateType = type;
        MyLog.i("AppUpdateHelper: started app update flow: type=" + getUpdateTypeName(type));
    }

    private String getUpdateTypeName(@AppUpdateType int type) {
        return type == AppUpdateType.IMMEDIATE ? "IMMEDIATE" : "FLEXIBLE";
    }

    private void resumeImmediateUpdate(AppUpdateInfo info) {
        startUpdateFlow(info, AppUpdateType.IMMEDIATE);
    }

    @Override
    public void onStateUpdate(@NonNull InstallState state) {
        try {
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                showFlexibleUpdateSnackbar();
            } else if (state.installStatus() == InstallStatus.FAILED) {
                MyLog.e("AppUpdateHelper: app update installation failed");
                showInstallationError();
            }
        } catch (Exception e) {
            MyLog.e("AppUpdateHelper: error handling install state update: " + e.getMessage());
        }
    }

    private void showFlexibleUpdateSnackbar() {
        try {
            Snackbar.make(snackBarAnchor,
                            R.string.app_update_downloaded,
                            Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.app_update_restart, v -> completeUpdate())
                    .show();
        } catch (Exception e) {
            MyLog.e("AppUpdateHelper: failed to show flexible update snackbar: " + e.getMessage());
        }
    }

    private void completeUpdate() {
        try {
            updateManager.completeUpdate();
        } catch (Exception e) {
            MyLog.e("AppUpdateHelper: failed to complete update: " + e.getMessage());
            showGenericUpdateError();
        }
    }

    public void handleUpdateResult(ActivityResult result) {
        isUpdateFlowInProgress.set(false);
        try {
            int resultCode = result.getResultCode();
            if (resultCode == Activity.RESULT_CANCELED) {
                recordUserRejection();
            } else if (resultCode == Activity.RESULT_OK) {
                MyLog.i("AppUpdateHelper: user accepted update");
            } else {
                MyLog.w("AppUpdateHelper: unexpected update result code: " + resultCode);
            }
        } catch (Exception e) {
            MyLog.e("AppUpdateHelper: error handling update result: " + e.getMessage());
        }

        // Complete when user responds to update dialog
        notifyCompletion();
    }

    private void recordUserRejection() {
        synchronized (prefsLock) {
            int rejectedVersion = prefs.getInt(LAST_PROMPTED_VERSION, -1);
            if (rejectedVersion > 0) {
                Set<String> ignored = new HashSet<>(prefs.getStringSet(IGNORED_VERSIONS, new HashSet<>()));
                ignored.add(String.valueOf(rejectedVersion));
                prefs.edit().putStringSet(IGNORED_VERSIONS, ignored).apply();
                MyLog.i("AppUpdateHelper: user rejected update for version: " + rejectedVersion);
            }
        }
    }

    private void notifyCompletion() {
        synchronized (listenerLock) {
            // Make a copy and clear the original list
            List<CompletionListener> listeners = new ArrayList<>(pendingListeners);
            pendingListeners.clear();

            // Notify all listeners
            for (CompletionListener listener : listeners) {
                try {
                    listener.onUpdateCompleted();
                } catch (Exception e) {
                    MyLog.e("AppUpdateHelper: error in completion listener: " + e.getMessage());
                }
            }
        }
    }

    public void onDestroy() {
        isDestroyed = true;
        isUpdateFlowInProgress.set(false);

        // notify all listeners to avoid memory leaks
        notifyCompletion();
        try {
            updateManager.unregisterListener(this);
        } catch (Exception e) {
            MyLog.e("AppUpdateHelper: failed to unregister listener: " + e.getMessage());
        }
    }

    private void showGenericUpdateError() {
        // Generic update error for miscellaneous failures
        Toast.makeText(activity, R.string.app_update_error, Toast.LENGTH_SHORT).show();
    }

    private void showInstallationError() {
        // Installation failed after download
        Toast.makeText(activity, R.string.app_update_install_failed, Toast.LENGTH_SHORT).show();
    }
}