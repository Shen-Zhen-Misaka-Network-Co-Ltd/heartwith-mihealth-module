package com.heartwith.mihealth.lsp;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.heartwith.uploader.HeartwithUploadConfig;
import com.heartwith.uploader.HeartwithUploadStatusListener;
import com.heartwith.uploader.HeartwithSleepStatus;
import com.heartwith.uploader.UrlConnectionHeartwithHttpClient;

import java.util.concurrent.Executor;

final class HeartwithUploader {
    private static final String TAG = "HeartwithMiHealth";
    private static final String RUNTIME_PREFS = "heartwith_mihealth_runtime";
    private static final String KEY_CACHED_ENABLED = "cached_enabled";
    private static final String KEY_CACHED_SYNC_ENABLED = "cached_sync_enabled";
    private static final String KEY_CACHED_SYNC_INTERVAL_HOURS = "cached_sync_interval_hours";
    private static final String KEY_CACHED_SERVER_URL = "cached_server_url";
    private static final String KEY_CACHED_DISPLAY_NAME = "cached_display_name";
    private static final String KEY_CACHED_DEVICE_MODEL = "cached_device_model";
    private static final String MODULE_PACKAGE = "com.heartwith.mihealth.lsp";
    private static final String DEFAULT_DEVICE_MODEL = "Xiaomi Health Hook";
    private static final String CLIENT_PLATFORM = "android-lsposed";

    private final com.heartwith.uploader.HeartwithUploader delegate;
    private HeartwithSettings settings = new HeartwithSettings(false, HeartwithSettings.DEFAULT_SERVER_URL, "Android");
    private String deviceModel = DEFAULT_DEVICE_MODEL;
    private boolean settingsLoaded;
    private boolean runtimeCacheLoaded;
    private long lastFailureLogElapsedMs;
    private long lastSettingsLogElapsedMs;
    private long lastStatusLogElapsedMs;
    private Context logContext;

    HeartwithUploader(Executor worker) {
        delegate = new com.heartwith.uploader.HeartwithUploader(
                worker,
                new UrlConnectionHeartwithHttpClient(new com.heartwith.uploader.HeartwithCleartextScope() {
                    @Override
                    public void enter() {
                        HeartwithCleartextScope.enter();
                    }

                    @Override
                    public void exit() {
                        HeartwithCleartextScope.exit();
                    }
                }, true));
        delegate.setStatusListener(new HeartwithUploadStatusListener() {
            @Override
            public void onUploadStatus(String status) {
                logUploadStatus(status);
            }
        });
    }

    synchronized void warmUp(Context context) {
        rememberContext(context);
        refreshSettingsIfNeeded(context, true);
        configureDelegate();
        logSettings("warmup");
    }

    synchronized void applySettings(Context context, HeartwithSettings next, String reason) {
        if (next == null) {
            return;
        }
        rememberContext(context);
        settings = next;
        settingsLoaded = true;
        runtimeCacheLoaded = true;
        persistRuntimeCache(context, next);
        configureDelegate();
        logSettings(reason);
    }

    synchronized HeartwithSettings currentSettings() {
        return settings;
    }

    synchronized void close() {
        delegate.close();
    }

    synchronized boolean setDeviceModel(Context context, String model) {
        rememberContext(context);
        String next = sanitizeDeviceModel(model);
        if (next.equals(deviceModel)) {
            return false;
        }
        deviceModel = next;
        persistDeviceModel(context, next);
        configureDelegate();
        if (DebugBuild.ENABLED) {
            Log.i(TAG, "device model resolved: " + next);
        }
        return true;
    }

    synchronized void onHeartRate(Context context, int bpm, String source) {
        if (bpm < 30 || bpm > 240) {
            return;
        }
        rememberContext(context);
        refreshSettingsIfNeeded(context, false);
        if (!settingsLoaded) {
            logState("settings unavailable; keep samples cached in uploader sdk");
            configureDelegate();
        }
        delegate.submitHeartRate(
                bpm,
                System.currentTimeMillis(),
                null,
                source == null || source.trim().isEmpty() ? "mi_health_hook" : "mi_health_hook:" + source.trim());
    }

    synchronized void onSleepStatus(Context context, HeartwithSleepStatus status) {
        if (status == null) {
            return;
        }
        rememberContext(context);
        refreshSettingsIfNeeded(context, false);
        if (!settingsLoaded) {
            logState("settings unavailable; keep sleep status cached in uploader sdk");
            configureDelegate();
        }
        delegate.submitSleepStatus(status);
    }

    private void configureDelegate() {
        delegate.configure(new HeartwithUploadConfig(
                settings.enabled,
                settings.serverUrl,
                settings.displayName,
                deviceModel,
                CLIENT_PLATFORM,
                BuildConfig.VERSION_NAME));
    }

    private void refreshSettingsIfNeeded(Context context, boolean force) {
        if (context == null) {
            return;
        }
        if (!force && settingsLoaded) {
            return;
        }
        loadRuntimeCacheIfNeeded(context);
        loadXposedSettingsIfNeeded(context);
        if (!settingsLoaded) {
            applySettings(context, settings, "settings fallback");
        }
    }

    private void loadRuntimeCacheIfNeeded(Context context) {
        if (context == null || runtimeCacheLoaded || settingsLoaded) {
            return;
        }
        runtimeCacheLoaded = true;
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE);
            String serverUrl = prefs.getString(KEY_CACHED_SERVER_URL, "");
            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                return;
            }
            boolean enabled = prefs.getBoolean(KEY_CACHED_ENABLED, false);
            boolean syncEnabled = prefs.getBoolean(KEY_CACHED_SYNC_ENABLED, false);
            int syncIntervalHours = prefs.getInt(KEY_CACHED_SYNC_INTERVAL_HOURS, HeartwithSettings.DEFAULT_SYNC_INTERVAL_HOURS);
            String displayName = prefs.getString(KEY_CACHED_DISPLAY_NAME, "Android");
            deviceModel = sanitizeDeviceModel(prefs.getString(KEY_CACHED_DEVICE_MODEL, DEFAULT_DEVICE_MODEL));
            settings = new HeartwithSettings(enabled, serverUrl, displayName, syncEnabled, syncIntervalHours);
            settingsLoaded = true;
            configureDelegate();
            logSettings("settings cache loaded");
        } catch (Throwable ignored) {
        }
    }

    private void persistDeviceModel(Context context, String model) {
        if (context == null) {
            return;
        }
        try {
            context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CACHED_DEVICE_MODEL, sanitizeDeviceModel(model))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void persistRuntimeCache(Context context, HeartwithSettings next) {
        if (context == null || next == null) {
            return;
        }
        try {
            context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_CACHED_ENABLED, next.enabled)
                    .putBoolean(KEY_CACHED_SYNC_ENABLED, next.syncEnabled)
                    .putInt(KEY_CACHED_SYNC_INTERVAL_HOURS, next.syncIntervalHours)
                    .putString(KEY_CACHED_SERVER_URL, next.serverUrl)
                    .putString(KEY_CACHED_DISPLAY_NAME, next.displayName)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void loadXposedSettingsIfNeeded(Context context) {
        if (context == null || settingsLoaded || MODULE_PACKAGE.equals(context.getPackageName())) {
            return;
        }
        try {
            Class<?> clazz = Class.forName("de.robv.android.xposed.XSharedPreferences");
            Object prefs = clazz
                    .getConstructor(String.class, String.class)
                    .newInstance(MODULE_PACKAGE, HeartwithSettings.PREFS);
            try {
                clazz.getMethod("reload").invoke(prefs);
            } catch (Throwable ignored) {
            }
            String serverUrl = (String) clazz
                    .getMethod("getString", String.class, String.class)
                    .invoke(prefs, HeartwithSettings.KEY_SERVER_URL, "");
            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                return;
            }
            boolean legacyEnabled = (Boolean) clazz
                    .getMethod("getBoolean", String.class, boolean.class)
                    .invoke(prefs, HeartwithSettings.KEY_ENABLED, false);
            boolean hookEnabled = (Boolean) clazz
                    .getMethod("getBoolean", String.class, boolean.class)
                    .invoke(prefs, HeartwithSettings.KEY_HOOK_ENABLED, legacyEnabled);
            boolean syncEnabled = (Boolean) clazz
                    .getMethod("getBoolean", String.class, boolean.class)
                    .invoke(prefs, HeartwithSettings.KEY_SYNC_ENABLED, false);
            int syncIntervalHours = (Integer) clazz
                    .getMethod("getInt", String.class, int.class)
                    .invoke(prefs, HeartwithSettings.KEY_SYNC_INTERVAL_HOURS, HeartwithSettings.DEFAULT_SYNC_INTERVAL_HOURS);
            String displayName = (String) clazz
                    .getMethod("getString", String.class, String.class)
                    .invoke(prefs, HeartwithSettings.KEY_DISPLAY_NAME, "Android");
            HeartwithSettings next = new HeartwithSettings(
                    hookEnabled,
                    serverUrl,
                    displayName,
                    syncEnabled,
                    syncIntervalHours);
            settings = next;
            settingsLoaded = true;
            persistRuntimeCache(context, next);
            configureDelegate();
            logSettings("xposed settings loaded");
        } catch (Throwable ignored) {
        }
    }

    private void logUploadStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        if (lastStatusLogElapsedMs > 0L && elapsed - lastStatusLogElapsedMs < 60_000L) {
            return;
        }
        lastStatusLogElapsedMs = elapsed;
        logImportant("upload status: " + status);
    }

    private void logState(String message) {
        long elapsed = SystemClock.elapsedRealtime();
        if (lastFailureLogElapsedMs > 0L && elapsed - lastFailureLogElapsedMs < 60_000L) {
            return;
        }
        lastFailureLogElapsedMs = elapsed;
        logImportant(message);
    }

    private void logSettings(String prefix) {
        long elapsed = SystemClock.elapsedRealtime();
        if (lastSettingsLogElapsedMs > 0L && elapsed - lastSettingsLogElapsedMs < 60_000L) {
            return;
        }
        lastSettingsLogElapsedMs = elapsed;
        logImportant(prefix + ": loaded=" + settingsLoaded
                + ", enabled=" + settings.enabled
                + ", sync=" + settings.syncEnabled
                + ", syncIntervalHours=" + settings.syncIntervalHours
                + ", server=" + settings.serverUrl
                + ", display=" + settings.displayName
                + ", device=" + deviceModel);
    }

    private void rememberContext(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        logContext = appContext == null ? context : appContext;
    }

    private void logImportant(String message) {
        if (message == null || message.length() == 0) {
            return;
        }
        Log.i(TAG, message);
        DebugSleepLog.line(logContext, "uploader", message);
    }

    private String sanitizeDeviceModel(String value) {
        if (value == null) {
            return DEFAULT_DEVICE_MODEL;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_DEVICE_MODEL;
        }
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("com.") || lower.startsWith("lcom/") ||
                lower.contains(".manager.") || lower.contains(".device.") ||
                lower.contains("/") || lower.contains("@")) {
            return DEFAULT_DEVICE_MODEL;
        }
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }
}
