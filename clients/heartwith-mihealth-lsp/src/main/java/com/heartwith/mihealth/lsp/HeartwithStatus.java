package com.heartwith.mihealth.lsp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.LruCache;

final class HeartwithStatus {
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
    private static final long VIEWER_ACTIVE_TTL_MS = 3_000L;
    private static boolean notificationChannelReady;
    private static Notification.Builder notificationBuilder;
    private static int notifiedBpm;
    private static String notifiedSource;
    private static String notifiedDeviceModel;
    private static final LruCache<Integer, Icon> HEART_RATE_ICONS = new LruCache<>(16);

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
        if (MODULE_PACKAGE.equals(context.getPackageName())) {
            context.getContentResolver().notifyChange(SettingsProvider.STATUS_URI, null);
        }
    }

    static void sendRegisteredStatus(Context context, int bpm, String source, long seenMs) {
        Intent intent = new Intent(ACTION_STATUS_CHANGED);
        intent.setPackage(MODULE_PACKAGE);
        intent.putExtra(EXTRA_BPM, bpm);
        intent.putExtra(EXTRA_SOURCE, source == null ? "" : source);
        intent.putExtra(EXTRA_SEEN_MS, seenMs);
        intent.putExtra(EXTRA_PROCESS_NAME, context == null ? "" : context.getPackageName());
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        context.sendBroadcast(intent);
    }

    static void markViewerActive(Context context, boolean active) {
        long untilMs = active ? System.currentTimeMillis() + VIEWER_ACTIVE_TTL_MS : 0L;
        context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_VIEWER_ACTIVE_UNTIL_MS, untilMs)
                .apply();
    }

    @SuppressWarnings("deprecation")
    static synchronized void showHookProcessNotification(Context context, int bpm, String source, long seenMs) {
        if (bpm <= 0) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        String deviceModel = readDeviceModel(context);
        String displaySource = source == null || source.isEmpty() ? "hook" : source;
        if (notificationBuilder != null && notifiedBpm == bpm
                && displaySource.equals(notifiedSource) && deviceModel.equals(notifiedDeviceModel)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !notificationChannelReady) {
            NotificationChannel channel = new NotificationChannel(
                    HOOK_CHANNEL_ID,
                    "Heartwith 心率采集",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            manager.createNotificationChannel(channel);
            notificationChannelReady = true;
        }
        if (notificationBuilder == null) {
            Context appContext = context.getApplicationContext();
            if (appContext == null) appContext = context;
            long postedAt = seenMs > 0L ? seenMs : System.currentTimeMillis();
            // Preserve ranking across process restarts and API 102 hot reload as well as updates.
            for (StatusBarNotification active : manager.getActiveNotifications()) {
                if (active.getId() == NOTIFICATION_ID + 1 && active.getTag() == null
                        && active.getNotification().when > 0L) {
                    postedAt = active.getNotification().when;
                    break;
                }
            }
            notificationBuilder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(appContext, HOOK_CHANNEL_ID)
                    : new Notification.Builder(appContext))
                    .setContentIntent(settingsIntent(appContext))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(false)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setShowWhen(false)
                    .setWhen(postedAt)
                    .setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        String detail = "来自" + deviceModel + " · " + displaySource;
        String heartRate = "Heartwith · " + bpm + " BPM";
        notificationBuilder.setSmallIcon(heartRateIcon(bpm))
                .setContentTitle(detail)
                .setContentText(heartRate);
        manager.notify(NOTIFICATION_ID + 1, notificationBuilder.build());
        notifiedBpm = bpm;
        notifiedSource = displaySource;
        notifiedDeviceModel = deviceModel;
    }

    private static Icon heartRateIcon(int bpm) {
        Icon cached = HEART_RATE_ICONS.get(bpm);
        if (cached != null) return cached;
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(44f);
        String text = Integer.toString(bpm);
        float width = paint.measureText(text);
        if (width > 60f) paint.setTextSize(44f * 60f / width);
        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);
        canvas.drawText(text, 32f, 4f - bounds.top, paint);
        paint.setTextSize(18f);
        paint.getTextBounds("bpm", 0, 3, bounds);
        canvas.drawText("bpm", 32f, 60f - bounds.bottom, paint);
        Icon icon = Icon.createWithBitmap(bitmap);
        HEART_RATE_ICONS.put(bpm, icon);
        return icon;
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
