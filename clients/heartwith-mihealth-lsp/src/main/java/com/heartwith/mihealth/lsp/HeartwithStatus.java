package com.heartwith.mihealth.lsp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

final class HeartwithStatus {
    private static final String TAG = "HeartwithMiHealth";
    private static final String MODULE_PACKAGE = "com.heartwith.mihealth.lsp";
    private static final String RUNTIME_PREFS = "heartwith_mihealth_runtime";
    private static final String KEY_CACHED_DEVICE_MODEL = "cached_device_model";
    private static final String DEFAULT_DEVICE_MODEL = "Xiaomi Health Hook";
    static final int NOTIFICATION_ID = 23014;
    static final String KEY_LAST_BPM = "last_bpm";
    static final String KEY_LAST_SOURCE = "last_source";
    static final String KEY_LAST_SEEN_MS = "last_seen_ms";
    static final String KEY_PROCESS_NAME = "process_name";
    static final String KEY_ACTIVE_PROCESS = "active_process";
    static final String KEY_ACTIVE_PROCESS_SEEN_MS = "active_process_seen_ms";
    static final String KEY_VIEWER_ACTIVE_UNTIL_MS = "viewer_active_until_ms";
    static final String ACTION_STATUS_CHANGED = "com.heartwith.mihealth.lsp.STATUS_CHANGED";
    static final String EXTRA_BPM = "bpm";
    static final String EXTRA_SOURCE = "source";
    static final String EXTRA_SEEN_MS = "seen_ms";
    static final String EXTRA_PROCESS_NAME = "process_name";
    static final String HOOK_CHANNEL_ID = "heartwith_mihealth_hook_status";
    private static final long NOTIFICATION_MIN_INTERVAL_MS = 10_000L;
    private static final long VIEWER_ACTIVE_TTL_MS = 3_000L;
    private static final int NOTIFICATION_CHANGE_BPM = 3;
    private static long lastNotificationElapsedMs;
    private static int lastNotificationBpm = -1;
    private static long lastStatusFailureElapsedMs;
    private static long nextProviderStatusAttemptMs;
    private static long lastStatusReportElapsedMs;
    private static int lastStatusReportBpm = -1;
    private static boolean notificationChannelReady;

    final int bpm;
    final String source;
    final long seenMs;

    HeartwithStatus(int bpm, String source, long seenMs) {
        this.bpm = bpm;
        this.source = source == null ? "" : source;
        this.seenMs = seenMs;
    }

    static HeartwithStatus readLocal(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE);
        return new HeartwithStatus(
                prefs.getInt(KEY_LAST_BPM, -1),
                prefs.getString(KEY_LAST_SOURCE, ""),
                prefs.getLong(KEY_LAST_SEEN_MS, 0L));
    }

    static void writeLocal(Context context, int bpm, String source, long seenMs) {
        context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_LAST_BPM, bpm)
                .putString(KEY_LAST_SOURCE, source == null ? "" : source)
                .putLong(KEY_LAST_SEEN_MS, seenMs)
                .apply();
        context.getContentResolver().notifyChange(SettingsProvider.STATUS_URI, null);
    }

    static void writeModuleStatus(Context context, int bpm, String source, long seenMs) {
        if (MODULE_PACKAGE.equals(context.getPackageName())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextProviderStatusAttemptMs) {
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(KEY_LAST_BPM, bpm);
            values.put(KEY_LAST_SOURCE, source == null ? "" : source);
            values.put(KEY_LAST_SEEN_MS, seenMs);
            values.put(KEY_PROCESS_NAME, context.getPackageName());
            context.getContentResolver().update(SettingsProvider.STATUS_URI, values, null, null);
        } catch (Throwable throwable) {
            nextProviderStatusAttemptMs = now + 60_000L;
            logFailure("provider status failed", throwable);
        }
    }

    static void markViewerActive(Context context, boolean active) {
        long untilMs = active ? System.currentTimeMillis() + VIEWER_ACTIVE_TTL_MS : 0L;
        context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_VIEWER_ACTIVE_UNTIL_MS, untilMs)
                .apply();
    }

    static void reportFromHook(Context context, int bpm, String source, String processName, long seenMs) {
        long elapsed = SystemClock.elapsedRealtime();
        if (lastStatusReportBpm > 0 &&
                Math.abs(bpm - lastStatusReportBpm) < NOTIFICATION_CHANGE_BPM &&
                elapsed - lastStatusReportElapsedMs < NOTIFICATION_MIN_INTERVAL_MS) {
            return;
        }
        lastStatusReportBpm = bpm;
        lastStatusReportElapsedMs = elapsed;
        try {
            Intent intent = new Intent(ACTION_STATUS_CHANGED);
            intent.setPackage("com.heartwith.mihealth.lsp");
            intent.putExtra(EXTRA_BPM, bpm);
            intent.putExtra(EXTRA_SOURCE, source == null ? "" : source);
            intent.putExtra(EXTRA_SEEN_MS, seenMs);
            intent.putExtra(EXTRA_PROCESS_NAME, processName == null ? "" : processName);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            logFailure("broadcast status failed", throwable);
        }
    }

    @SuppressWarnings("deprecation")
    static void showHookProcessNotification(Context context, int bpm, String source, long seenMs) {
        if (bpm <= 0) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        if (lastNotificationBpm > 0 &&
                Math.abs(bpm - lastNotificationBpm) < NOTIFICATION_CHANGE_BPM &&
                elapsed - lastNotificationElapsedMs < NOTIFICATION_MIN_INTERVAL_MS) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !notificationChannelReady) {
            NotificationChannel channel = new NotificationChannel(
                    HOOK_CHANNEL_ID,
                    "Heartwith 心率采集",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
            notificationChannelReady = true;
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, HOOK_CHANNEL_ID)
                : new Notification.Builder(context);
        String deviceModel = readDeviceModel(context);
        String detail = "来自" + deviceModel + " · " + (source == null || source.isEmpty() ? "hook" : source);
        int icon = context.getApplicationInfo().icon;
        if (icon == 0) {
            return;
        }
        builder.setSmallIcon(icon)
                .setContentTitle("Heartwith · " + bpm + " BPM")
                .setContentText(detail)
                .setContentIntent(settingsIntent(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(seenMs);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_DEFAULT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.setStyle(new Notification.BigTextStyle().bigText("当前心率 " + bpm + " BPM\n" + detail));
        }
        manager.notify(NOTIFICATION_ID + 1, builder.build());
        lastNotificationBpm = bpm;
        lastNotificationElapsedMs = elapsed;
    }

    private static PendingIntent settingsIntent(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent == null) {
            intent = new Intent();
            intent.setPackage(context.getPackageName());
        }
        intent.putExtra(HeartwithSettingsPanel.EXTRA_SHOW_SETTINGS, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, NOTIFICATION_ID, intent, flags);
    }

    private static void logFailure(String prefix, Throwable throwable) {
        long elapsed = SystemClock.elapsedRealtime();
        if (lastStatusFailureElapsedMs > 0L && elapsed - lastStatusFailureElapsedMs < 60_000L) {
            return;
        }
        lastStatusFailureElapsedMs = elapsed;
        Log.w(TAG, prefix + ": " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
    }

    private static String readDeviceModel(Context context) {
        try {
            String value = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_CACHED_DEVICE_MODEL, "");
            if (value == null) {
                return "小米运动健康";
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty() || DEFAULT_DEVICE_MODEL.equals(trimmed)) {
                return "小米运动健康";
            }
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("com.") || lower.startsWith("lcom/") ||
                    lower.contains(".manager.") || lower.contains(".device.") ||
                    lower.contains("/") || lower.contains("@")) {
                return "小米运动健康";
            }
            return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
        } catch (Throwable ignored) {
            return "小米运动健康";
        }
    }

    static String relativeTime(long nowMs, long seenMs) {
        if (seenMs <= 0L) {
            return "尚未采集";
        }
        long seconds = Math.max(0L, (nowMs - seenMs) / 1000L);
        if (seconds < 3) {
            return "刚刚";
        }
        if (seconds < 60) {
            return seconds + " 秒前";
        }
        long minutes = seconds / 60L;
        return minutes + " 分钟前";
    }
}
