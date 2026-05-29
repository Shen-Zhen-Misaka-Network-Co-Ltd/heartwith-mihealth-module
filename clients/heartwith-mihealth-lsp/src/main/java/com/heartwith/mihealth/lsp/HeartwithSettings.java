package com.heartwith.mihealth.lsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

final class HeartwithSettings {
    static final String PREFS = "heartwith_mihealth_lsp";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_SERVER_URL = "server_url";
    static final String KEY_DISPLAY_NAME = "display_name";
    static final String ACTION_CONFIG_CHANGED = "com.heartwith.mihealth.lsp.CONFIG_CHANGED";
    static final String EXTRA_ENABLED = "enabled";
    static final String EXTRA_SERVER_URL = "server_url";
    static final String EXTRA_DISPLAY_NAME = "display_name";
    static final String DEFAULT_SERVER_URL = "http://52.193.131.172:8000";
    private static final String LEGACY_EMULATOR_SERVER_URL = "http://10.0.2.2:8000";

    final boolean enabled;
    final String serverUrl;
    final String displayName;

    HeartwithSettings(boolean enabled, String serverUrl, String displayName) {
        this.enabled = enabled;
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
        return new HeartwithSettings(
                prefs.getBoolean(KEY_ENABLED, true),
                serverUrl,
                prefs.getString(KEY_DISPLAY_NAME, defaultDisplayName()));
    }

    static void writeLocal(Context context, HeartwithSettings settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, settings.enabled)
                .putString(KEY_SERVER_URL, settings.serverUrl)
                .putString(KEY_DISPLAY_NAME, settings.displayName)
                .apply();
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
