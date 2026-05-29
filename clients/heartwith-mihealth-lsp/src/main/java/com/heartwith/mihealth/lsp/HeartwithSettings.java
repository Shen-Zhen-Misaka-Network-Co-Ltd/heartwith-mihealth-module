package com.heartwith.mihealth.lsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

final class HeartwithSettings {
    static final String PREFS = "heartwith_mihealth_lsp";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_HOOK_ENABLED = "hook_enabled";
    static final String KEY_SYNC_ENABLED = "sync_enabled";
    static final String KEY_SYNC_INTERVAL_HOURS = "sync_interval_hours";
    static final String KEY_ONBOARDING_SEEN = "onboarding_seen";
    static final String KEY_SERVER_URL = "server_url";
    static final String KEY_DISPLAY_NAME = "display_name";
    static final String ACTION_CONFIG_CHANGED = "com.heartwith.mihealth.lsp.CONFIG_CHANGED";
    static final String ACTION_SYNC_NOW = "com.heartwith.mihealth.lsp.SYNC_NOW";
    static final String EXTRA_ENABLED = "enabled";
    static final String EXTRA_HOOK_ENABLED = "hook_enabled";
    static final String EXTRA_SYNC_ENABLED = "sync_enabled";
    static final String EXTRA_SYNC_INTERVAL_HOURS = "sync_interval_hours";
    static final String EXTRA_SYNC_MANUAL = "sync_manual";
    static final String EXTRA_SERVER_URL = "server_url";
    static final String EXTRA_DISPLAY_NAME = "display_name";
    static final String DEFAULT_SERVER_URL = "http://52.193.131.172:8000";
    static final int DEFAULT_SYNC_INTERVAL_HOURS = 6;
    static final int MIN_SYNC_INTERVAL_HOURS = 1;
    static final int MAX_SYNC_INTERVAL_HOURS = 168;
    private static final String LEGACY_EMULATOR_SERVER_URL = "http://10.0.2.2:8000";

    /**
     * Legacy alias kept for old uploader/runtime code. It now means heart-rate hook/upload enabled.
     */
    final boolean enabled;
    final boolean hookEnabled;
    final boolean syncEnabled;
    final int syncIntervalHours;
    final String serverUrl;
    final String displayName;

    HeartwithSettings(boolean enabled, String serverUrl, String displayName) {
        this(enabled, serverUrl, displayName, false, DEFAULT_SYNC_INTERVAL_HOURS);
    }

    HeartwithSettings(
            boolean hookEnabled,
            String serverUrl,
            String displayName,
            boolean syncEnabled,
            int syncIntervalHours) {
        this.enabled = hookEnabled;
        this.hookEnabled = hookEnabled;
        this.syncEnabled = syncEnabled;
        this.syncIntervalHours = clampSyncIntervalHours(syncIntervalHours);
        this.serverUrl = normalizeServerUrl(serverUrl);
        this.displayName = sanitizeDisplayName(displayName);
    }

    static HeartwithSettings readLocal(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
        if (LEGACY_EMULATOR_SERVER_URL.equals(normalizeServerUrl(serverUrl))) {
            serverUrl = DEFAULT_SERVER_URL;
            prefs.edit().putString(KEY_SERVER_URL, serverUrl).apply();
        }
        boolean hookEnabled;
        if (prefs.contains(KEY_HOOK_ENABLED)) {
            hookEnabled = prefs.getBoolean(KEY_HOOK_ENABLED, false);
        } else if (prefs.contains(KEY_ENABLED)) {
            hookEnabled = prefs.getBoolean(KEY_ENABLED, false);
        } else {
            hookEnabled = false;
        }
        return new HeartwithSettings(
                hookEnabled,
                serverUrl,
                prefs.getString(KEY_DISPLAY_NAME, defaultDisplayName()),
                prefs.getBoolean(KEY_SYNC_ENABLED, false),
                prefs.getInt(KEY_SYNC_INTERVAL_HOURS, DEFAULT_SYNC_INTERVAL_HOURS));
    }

    static void writeLocal(Context context, HeartwithSettings settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, settings.enabled)
                .putBoolean(KEY_HOOK_ENABLED, settings.hookEnabled)
                .putBoolean(KEY_SYNC_ENABLED, settings.syncEnabled)
                .putInt(KEY_SYNC_INTERVAL_HOURS, settings.syncIntervalHours)
                .putBoolean(KEY_ONBOARDING_SEEN, true)
                .putString(KEY_SERVER_URL, settings.serverUrl)
                .putString(KEY_DISPLAY_NAME, settings.displayName)
                .commit();
    }

    static boolean onboardingSeen(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDING_SEEN, false);
    }

    static int parseSyncIntervalHours(String value) {
        if (value == null) {
            return DEFAULT_SYNC_INTERVAL_HOURS;
        }
        try {
            return clampSyncIntervalHours(Integer.parseInt(value.trim()));
        } catch (Throwable ignored) {
            return DEFAULT_SYNC_INTERVAL_HOURS;
        }
    }

    static int clampSyncIntervalHours(int value) {
        if (value < MIN_SYNC_INTERVAL_HOURS) {
            return MIN_SYNC_INTERVAL_HOURS;
        }
        if (value > MAX_SYNC_INTERVAL_HOURS) {
            return MAX_SYNC_INTERVAL_HOURS;
        }
        return value;
    }

    private static String normalizeServerUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? DEFAULT_SERVER_URL : trimmed;
    }

    private static String sanitizeDisplayName(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? defaultDisplayName() : trimmed;
    }

    private static String defaultDisplayName() {
        String model = Build.MODEL == null ? "Android" : Build.MODEL.trim();
        return model.isEmpty() ? "Android" : model;
    }
}
