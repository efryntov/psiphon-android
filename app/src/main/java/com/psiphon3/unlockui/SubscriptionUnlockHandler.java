/*
 * Copyright (c) 2025, Psiphon Inc.
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

package com.psiphon3.unlockui;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import androidx.cardview.widget.CardView;

import com.psiphon3.PaymentChooserActivity;
import com.psiphon3.UnlockOptions;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.R;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class SubscriptionUnlockHandler extends UnlockOptionHandler {
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    public SubscriptionUnlockHandler(UnlockOptions.UnlockEntry entry, Runnable dismissDialogRunnable) {
        super(UnlockOptions.UNLOCK_ENTRY_SUBSCRIPTION, entry, dismissDialogRunnable);
    }

    @Override
    protected View createView(ViewGroup parent) {
        View view = getLayoutInflater(parent).inflate(R.layout.unlock_option_subscription_layout, parent, false);

        CardView subscribeCard = view.findViewById(R.id.subscribeCardView);
        subscribeCard.setOnClickListener(v -> {
            MyLog.i("SubscriptionUnlockHandler: user clicked subscribe button, starting subscription activity.");
            startSubscriptionActivity(v.getContext());
            dismissDialogRunnable.run();
        });

        return view;
    }

    @Override
    public void onShowDialog() {
        subscribeToSubscriptionState();
    }

    @Override
    public void onResume() {
        subscribeToSubscriptionState();
    }

    @Override
    public void onPause() {
        compositeDisposable.clear();
    }

    @Override
    public void onDismissDialog() {
        compositeDisposable.clear();
    }

    private void subscribeToSubscriptionState() {
        // Clear any existing subscription first
        compositeDisposable.clear();

        GooglePlayBillingHelper googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(inflatedView.getContext());

        compositeDisposable.add(
                googlePlayBillingHelper.subscriptionStateFlowable()
                        .firstOrError()
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                subscriptionState -> {
                                    switch (subscriptionState.status()) {
                                        case HAS_UNLIMITED_SUBSCRIPTION:
                                        case HAS_TIME_PASS:
                                        case HAS_LIMITED_SUBSCRIPTION:
                                            MyLog.i("SubscriptionUnlockHandler: user has a valid subscription, dismissing dialog.");
                                            dismissDialogRunnable.run();
                                            break;
                                        case IAB_FAILURE:
                                        case HAS_NO_SUBSCRIPTION:
                                            // No subscription, keep showing option
                                            break;
                                    }
                                },
                                throwable -> {
                                    // Error checking subscription state, keep showing option
                                }
                        )
        );
    }

    private void startSubscriptionActivity(Context context) {
        try {
            Intent intent = new Intent(context, PaymentChooserActivity.class);
            intent.putExtra(PaymentChooserActivity.INTENT_EXTRA_UNLOCK_REQUIRED, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (RuntimeException ignored) {
        }
    }
}