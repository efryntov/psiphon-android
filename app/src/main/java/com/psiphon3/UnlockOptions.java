package com.psiphon3;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jakewharton.rxrelay2.BehaviorRelay;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;

public class UnlockOptions {
    public static final String UNLOCK_ENTRY_SUBSCRIPTION = "Subscription";
    public static final String UNLOCK_ENTRY_CONDUIT = "Conduit";
    public static final String APP_INSTALL_PREFIX = "AppInstall.";

    // Default priorities when not specified in JSON (the lower the number, the higher the priority)
    public static final int DEFAULT_CONDUIT_PRIORITY = 10;
    public static final int DEFAULT_SUBSCRIPTION_PRIORITY = 50;
    public static final int DEFAULT_APP_INSTALL_PRIORITY = 80;

    private final Map<String, UnlockEntry> entries = new ConcurrentHashMap<>();
    private final BehaviorRelay<Set<String>> entriesSetRelay = BehaviorRelay.create();

    public static class UnlockEntry {
        public final @Nullable Supplier<Boolean> checker;
        public final Boolean display;
        public final int priority;

        // Service-side constructor (with checker)
        public UnlockEntry(@NonNull Supplier<Boolean> checker, @Nullable Boolean display, int priority) {
            this.checker = checker;
            this.display = display;
            this.priority = priority;
        }

        // Client-side constructor (without checker) used to render the unlock dialog
        private UnlockEntry(boolean display, int priority) {
            this.checker = null;
            this.display = display;
            this.priority = priority;
        }

        public boolean isDisplayable() {
            // If display is not set, we assume it should be displayed
            return display == null || display;
        }

        public Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putBoolean("display", isDisplayable());
            bundle.putInt("priority", priority);
            return bundle;
        }

        public static UnlockEntry fromBundle(@NonNull Bundle bundle) {
            boolean display = bundle.getBoolean("display", true);
            int priority = bundle.getInt("priority");
            return new UnlockEntry(display, priority);
        }
    }

    public static class AppInstallUnlockEntry extends UnlockEntry {
        public final String appId;
        public final String appName;
        public final String playStoreUrl;

        // Service-side constructor (with checker)
        public AppInstallUnlockEntry(@NonNull Supplier<Boolean> checker, @Nullable Boolean display, int priority,
                                     String appId, String appName, String playStoreUrl) {
            super(checker, display, priority);
            this.appId = appId;
            this.appName = appName;
            this.playStoreUrl = playStoreUrl;
        }

        // Client-side constructor (without checker)
        private AppInstallUnlockEntry(boolean display, int priority, String appId, String appName, String playStoreUrl) {
            super(display, priority);
            this.appId = appId;
            this.appName = appName;
            this.playStoreUrl = playStoreUrl;
        }

        @Override
        public Bundle toBundle() {
            Bundle bundle = super.toBundle();
            bundle.putString("appId", appId);
            bundle.putString("appName", appName);
            bundle.putString("playStoreUrl", playStoreUrl);
            return bundle;
        }

        public static AppInstallUnlockEntry fromBundle(@NonNull Bundle bundle) {
            boolean display = bundle.getBoolean("display", true);
            int priority = bundle.getInt("priority");
            String appId = bundle.getString("appId");
            String appName = bundle.getString("appName");
            String playStoreUrl = bundle.getString("playStoreUrl");
            return new AppInstallUnlockEntry(display, priority, appId, appName, playStoreUrl);
        }
    }

    // Check if we need to show the unlock dialog (service-side only)
    public boolean unlockRequired() {
        if (entries.isEmpty()) {
            // No checkers available, no need to show the unlock dialog
            return false;
        }

        boolean hasAtLeasOneDisplayableEntry = false;

        for (UnlockEntry entry : entries.values()) {
            if (entry.isDisplayable()) {
                hasAtLeasOneDisplayableEntry = true;
            }
            // This will crash if the checker is null, which is expected, since we only call this method
            // on the service side where all entries should have a checker.
            if (entry.checker.get()) {
                // If any checker passed, we don't need to show the unlock dialog
                return false;
            }
        }

        // If no checkers passed and we have at least one displayable entry,
        // we need to show the unlock dialog
        return hasAtLeasOneDisplayableEntry;
    }

    public synchronized void setEntries(@NonNull  Map<String, UnlockEntry> entryMap) {
        entries.clear();
        entries.putAll(entryMap);

        // Update the relay with the current set of checkers
        entriesSetRelay.accept(entryMap.keySet());
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        for (Map.Entry<String, UnlockEntry> entry : entries.entrySet()) {
            bundle.putBundle(entry.getKey(), entry.getValue().toBundle());
        }
        return bundle;
    }

    public static UnlockOptions fromBundle(@Nullable Bundle bundle) {
        Map<String, UnlockEntry> entries = new HashMap<>();
        if (bundle != null) {
            for (String key : bundle.keySet()) {
                Bundle entryBundle = bundle.getBundle(key);
                if (entryBundle != null) {
                    if (key.startsWith(APP_INSTALL_PREFIX)) {
                        entries.put(key, AppInstallUnlockEntry.fromBundle(entryBundle));
                    } else {
                        entries.put(key, UnlockEntry.fromBundle(entryBundle));
                    }
                }
            }
        }

        UnlockOptions unlockOptions = new UnlockOptions();
        unlockOptions.entries.putAll(entries);
        return unlockOptions;
    }

    public boolean hasConduitEntry() {
        return entries.containsKey(UNLOCK_ENTRY_CONDUIT);
    }

    public boolean hasSubscriptionEntry() {
        return entries.containsKey(UNLOCK_ENTRY_SUBSCRIPTION);
    }

    public boolean hasAppInstallEntries() {
        return entries.keySet().stream().anyMatch(key -> key.startsWith(APP_INSTALL_PREFIX));
    }

    public boolean isEntryDisplayable(String key) {
        UnlockEntry entry = entries.get(key);
        return entry != null && entry.isDisplayable();
    }

    public boolean hasDisplayableEntries() {
        return entries.values().stream().anyMatch(UnlockEntry::isDisplayable);
    }

    public Map<String, UnlockEntry> getAllEntries() {
        return new HashMap<>(entries);
    }

    public Flowable<Set<String>> getEntriesSetFlowable() {
        return entriesSetRelay
                .hide()
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }
}