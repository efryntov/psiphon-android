/*
 * Copyright (c) 2023, Psiphon Inc.
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
 */

package com.psiphon3;

import android.content.Context;
import android.content.IntentSender;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.R;

public class AppUpdateHelper implements InstallStateUpdatedListener {
    private static final int APP_UPDATE_REQUEST_CODE = 32137;
    private static final String APP_UPDATE_PREFERENCES_KEY = "APP_UPDATE_PREFERENCES_KEY";
    private static final String NEXT_APP_UPDATE_CHECK_TIME_MILLIS = "NEXT_APP_UPDATE_CHECK_TIME_MILLIS";
    private static final long TIME_24HR_MILLIS = 24 * 60 * 60 * 1000L;
    private final AppUpdateManager appUpdateManager;
    private final AppCompatActivity parentActivity;
    private @AppUpdateType int currentUpdateType = AppUpdateType.FLEXIBLE;

    public AppUpdateHelper(AppCompatActivity activity) {
        appUpdateManager = AppUpdateManagerFactory.create(activity);
        parentActivity = activity;
        appUpdateManager.registerListener(this);
    }

    void checkAppUpdate() {
        // Don't run the update check more often than once a day.
        final SharedPreferences sp = parentActivity.getSharedPreferences(APP_UPDATE_PREFERENCES_KEY, Context.MODE_PRIVATE);
        long nextAppUpdateCheckTimeMillis = sp.getLong(NEXT_APP_UPDATE_CHECK_TIME_MILLIS, 0);
        if (nextAppUpdateCheckTimeMillis > System.currentTimeMillis()) {
            return;
        }
        sp.edit().putLong(NEXT_APP_UPDATE_CHECK_TIME_MILLIS, System.currentTimeMillis() + TIME_24HR_MILLIS).apply();
        // Check if update is available
        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(info -> {
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                // If update is available there are two types of update UX flow:
                //
                // 1. "Immediate updates are fullscreen UX flows that require the user to
                // update and restart the app in order to continue using it. This UX flow is best for
                // cases where an update is critical to the core functionality of your app. After a
                // user accepts an immediate update, Google Play handles the update installation and
                // app restart."
                //
                // 2. "Flexible updates provide background download and installation with graceful state
                // monitoring. This UX flow is appropriate when it's acceptable for the user to use
                // the app while downloading the update. For example, you might want to encourage
                // users to try a new feature that's not critical to the core functionality of your
                // app."
                //
                // We are going to determine if we should start an IMMEDIATE update flow based on the
                // update priority and client staleness period
                Integer clientVersionStalenessDays = info.clientVersionStalenessDays();
                switch (info.updatePriority()) {
                    case 5:
                        // Highest priority, start IMMEDIATE update if allowed.
                        if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                            startAppUpdate(info, AppUpdateType.IMMEDIATE);
                            break;
                        }
                    case 4:
                        // Allow up to 5 days of staleness before starting IMMEDIATE update if allowed.
                        if (clientVersionStalenessDays != null &&
                                clientVersionStalenessDays >= 5
                                && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                            startAppUpdate(info, AppUpdateType.IMMEDIATE);
                            break;
                        }
                    case 3:
                        // Allow up to 15 days of staleness before starting IMMEDIATE update if allowed.
                        if (clientVersionStalenessDays != null &&
                                clientVersionStalenessDays >= 15
                                && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                            startAppUpdate(info, AppUpdateType.IMMEDIATE);
                            break;
                        }
                    case 2:
                        // Allow up to 30 days of staleness before starting IMMEDIATE update if allowed.
                        if (clientVersionStalenessDays != null &&
                                clientVersionStalenessDays >= 30
                                && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                            startAppUpdate(info, AppUpdateType.IMMEDIATE);
                            break;
                        }
                    case 1:
                        // Allow up to 60 days of staleness before starting IMMEDIATE update if allowed.
                        if (clientVersionStalenessDays != null &&
                                clientVersionStalenessDays >= 60
                                && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                            startAppUpdate(info, AppUpdateType.IMMEDIATE);
                            break;
                        }
                    default:
                        // If an update is available but IMMEDIATE is not applicable then try and
                        // start FLEXIBLE update flow.
                        if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                            startAppUpdate(info, AppUpdateType.FLEXIBLE);
                        }
                }
            }
        });
    }

    private void startAppUpdate(AppUpdateInfo info, @AppUpdateType int type) {
        try {
            appUpdateManager.startUpdateFlowForResult(info, type, parentActivity, APP_UPDATE_REQUEST_CODE);
            currentUpdateType = type;
        } catch (IntentSender.SendIntentException e) {
            MyLog.e("App update flow error: " + e);
        }
    }

    @Override
    public void onStateUpdate(@NonNull InstallState installState) {
        if (installState.installStatus() == InstallStatus.DOWNLOADED) {
            popSnackBarDownloadCompeted();
        }
    }

    private void popSnackBarDownloadCompeted() {
        Snackbar snackbar = Snackbar.make(parentActivity.findViewById(R.id.activity_main_layout),
                R.string.app_update_downloaded,
                Snackbar.LENGTH_INDEFINITE);
        snackbar.setAction(R.string.app_update_restart, view -> appUpdateManager.completeUpdate());
        snackbar.show();
    }

    void onDestroy() {
        appUpdateManager.unregisterListener(this);
    }

    public void onResume() {
        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(info -> {
            if (currentUpdateType == AppUpdateType.FLEXIBLE) {
                // FLEXIBLE only:
                // If the update is downloaded but not installed, notify the user to complete the update.
                if (info.installStatus() == InstallStatus.DOWNLOADED)
                    popSnackBarDownloadCompeted();
            } else if (currentUpdateType == AppUpdateType.IMMEDIATE) {
                // IMMEDIATE only:
                // If an in-app update is already running, resume the update.
                if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    startAppUpdate(info, AppUpdateType.IMMEDIATE);
                }
            }
        });
    }
}
