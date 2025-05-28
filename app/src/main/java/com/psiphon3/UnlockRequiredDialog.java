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

package com.psiphon3;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.R;
import com.psiphon3.unlockui.AppInstallUnlockHandler;
import com.psiphon3.unlockui.ConduitUnlockHandler;
import com.psiphon3.unlockui.SubscriptionUnlockHandler;
import com.psiphon3.unlockui.UnlockOptionHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class UnlockRequiredDialog implements DefaultLifecycleObserver {
    private final Dialog dialog;
    private final LinearLayout unlockOptionsContainer;
    private final CardView disconnectButton;

    private UnlockOptions unlockOptions;
    private List<UnlockOptionHandler> handlers = new ArrayList<>();
    private Runnable disconnectTunnelRunnable;

    private UnlockRequiredDialog(Context context) {
        View contentView = LayoutInflater.from(context).inflate(R.layout.unlock_required_dialog_layout, null);

        unlockOptionsContainer = contentView.findViewById(R.id.unlockOptionsContainer);
        disconnectButton = contentView.findViewById(R.id.disconnectCardView);

        disconnectButton.setOnClickListener(v -> disconnectAndDismiss());

        dialog = new Dialog(context, R.style.Theme_NoTitleDialog);
        dialog.setCancelable(false);
        dialog.setContentView(contentView);
        dialog.setOnShowListener(dialogInterface -> {
            // Notify all handlers dialog is shown
            for (UnlockOptionHandler handler : handlers) {
                handler.onShowDialog();
            }
        });
    }

    private void show() {
        if (!hasDisplayableEntries()) {
            MyLog.w("UnlockRequiredDialogNew: no displayable entries present, not showing dialog");
            return;
        }

        createHandlers();
        populateContainer();

        dialog.show();

        // Full screen resize
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(dialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(lp);
    }

    private void createHandlers() {
        handlers.clear();

        if (unlockOptions == null) return;

        Map<String, UnlockOptions.UnlockEntry> entries = unlockOptions.getAllEntries();

        for (Map.Entry<String, UnlockOptions.UnlockEntry> entry : entries.entrySet()) {
            String key = entry.getKey();
            UnlockOptions.UnlockEntry unlockEntry = entry.getValue();

            UnlockOptionHandler handler = createHandler(key, unlockEntry);
            if (handler != null) {
                handlers.add(handler);
            }
        }

        // Sort by priority (lower number = higher priority = shown first)
        handlers.sort(Comparator.comparingInt(UnlockOptionHandler::getPriority));
    }

    private UnlockOptionHandler createHandler(String key, UnlockOptions.UnlockEntry entry) {
        if (key.equals(UnlockOptions.UNLOCK_ENTRY_SUBSCRIPTION)) {
            return new SubscriptionUnlockHandler(entry, this::dismiss);

        } else if (key.equals(UnlockOptions.UNLOCK_ENTRY_CONDUIT)) {
            return new ConduitUnlockHandler(entry, disconnectTunnelRunnable, this::dismiss);

        } else if (key.startsWith(UnlockOptions.APP_INSTALL_PREFIX)) {
            if (entry instanceof UnlockOptions.AppInstallUnlockEntry) {
                return new AppInstallUnlockHandler(key, (UnlockOptions.AppInstallUnlockEntry) entry,
                        disconnectTunnelRunnable, this::dismiss);
            } else {
                MyLog.w("UnlockRequiredDialogNew: entry for key " + key + " is not an AppInstallUnlockEntry");
                return null;
            }
        }

        MyLog.w("UnlockRequiredDialogNew: unknown unlock option type: " + key);
        return null;
    }

    private void populateContainer() {
        unlockOptionsContainer.removeAllViews();

        for (int i = 0; i < handlers.size(); i++) {
            UnlockOptionHandler handler = handlers.get(i);
            View view = handler.getView(unlockOptionsContainer);
            unlockOptionsContainer.addView(view);

            // Add divider between options (except last one)
            if (i < handlers.size() - 1) {
                addDivider();
            }
        }
    }

    private void addDivider() {
        View divider = LayoutInflater.from(dialog.getContext())
                .inflate(R.layout.unlock_option_divider_layout, unlockOptionsContainer, false);
        unlockOptionsContainer.addView(divider);
    }

    private boolean hasDisplayableEntries() {
        return unlockOptions != null && unlockOptions.hasDisplayableEntries();
    }

    private void disconnectAndDismiss() {
        if (disconnectTunnelRunnable != null) {
            disconnectTunnelRunnable.run();
        }
        dismiss();
    }

    // Forward lifecycle events to all handlers
    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        for (UnlockOptionHandler handler : handlers) {
            handler.onResume();
        }
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        for (UnlockOptionHandler handler : handlers) {
            handler.onPause();
        }
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        if (dialog.isShowing()) {
            dismiss();
        }
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public void dismiss() {
        // Notify handlers dialog is being dismissed
        for (UnlockOptionHandler handler : handlers) {
            handler.onDismissDialog();
        }

        handlers.clear();

        if (dialog.getContext() instanceof LifecycleOwner) {
            ((LifecycleOwner) dialog.getContext()).getLifecycle().removeObserver(this);
        }

        dialog.dismiss();
    }

    private void registerLifecycleOwner(LifecycleOwner owner) {
        owner.getLifecycle().addObserver(this);
    }

    private void setUnlockOptions(UnlockOptions unlockOptions) {
        this.unlockOptions = unlockOptions;
    }

    private void setDisconnectTunnelRunnable(Runnable runnable) {
        this.disconnectTunnelRunnable = runnable;
    }

    public static class Builder {
        private final UnlockRequiredDialog dialog;
        private final LifecycleOwner lifecycleOwner;

        public Builder(Context context, LifecycleOwner lifecycleOwner) {
            this.dialog = new UnlockRequiredDialog(context);
            this.lifecycleOwner = lifecycleOwner;
        }

        public Builder setDisconnectTunnelRunnable(Runnable runnable) {
            dialog.setDisconnectTunnelRunnable(runnable);
            return this;
        }

        public Builder setUnlockOptions(UnlockOptions unlockOptions) {
            dialog.setUnlockOptions(unlockOptions);
            return this;
        }

        public UnlockRequiredDialog show() {
            dialog.registerLifecycleOwner(lifecycleOwner);
            dialog.show();
            return dialog;
        }
    }
}