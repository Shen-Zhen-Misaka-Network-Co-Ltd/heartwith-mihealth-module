package com.heartwith.mihealth.lsp;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

final class HeartwithSleepChannelStatus {
    private static final String MODULE_PACKAGE = "com.heartwith.mihealth.lsp";
    static final Uri URI = Uri.parse("content://" + SettingsProvider.AUTHORITY + "/sleep-channel");
    static final String KEY_SUMMARY = "sleep_channel_summary";
    static final String KEY_DETAILS = "sleep_channel_details";
    static final String KEY_SEEN_MS = "sleep_channel_seen_ms";
    static final String KEY_REQUEST_ID = "sleep_channel_request_id";
    static final String KEY_REQUESTED_MS = "sleep_channel_requested_ms";
    static final String ACTION_REQUEST = "com.heartwith.mihealth.lsp.SLEEP_CHANNEL_NOW";
    static final String ACTION_CHANGED = "com.heartwith.mihealth.lsp.SLEEP_CHANNEL_CHANGED";
    static final String EXTRA_SUMMARY = "summary";
    static final String EXTRA_DETAILS = "details";
    static final String EXTRA_SEEN_MS = "seen_ms";

    final String summary;
    final String details;
    final long seenMs;

    static final class Request {
        final long id;
        final long requestedMs;

        Request(long id, long requestedMs) {
            this.id = id;
            this.requestedMs = requestedMs;
        }

        boolean isFresh(long nowMs) {
            return id > 0L
                    && requestedMs > 0L
                    && Math.abs(nowMs - requestedMs) <= 30_000L;
        }
    }

    HeartwithSleepChannelStatus(String summary, String details, long seenMs) {
        this.summary = summary == null ? "" : summary;
        this.details = details == null ? "" : details;
        this.seenMs = seenMs;
    }

    static HeartwithSleepChannelStatus readLocal(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE);
        return new HeartwithSleepChannelStatus(
                prefs.getString(KEY_SUMMARY, ""),
                prefs.getString(KEY_DETAILS, ""),
                prefs.getLong(KEY_SEEN_MS, 0L));
    }

    static void writeLocal(Context context, String summary, String details, long seenMs) {
        context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SUMMARY, summary == null ? "" : summary)
                .putString(KEY_DETAILS, details == null ? "" : details)
                .putLong(KEY_SEEN_MS, seenMs)
                .apply();
        notifyLocalChange(context);
    }

    static long requestNowLocal(Context context, String summary, String details, long seenMs) {
        SharedPreferences prefs = context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE);
        long requestId = Math.max(seenMs, prefs.getLong(KEY_REQUEST_ID, 0L) + 1L);
        prefs.edit()
                .putString(KEY_SUMMARY, summary == null ? "" : summary)
                .putString(KEY_DETAILS, details == null ? "" : details)
                .putLong(KEY_SEEN_MS, seenMs)
                .putLong(KEY_REQUEST_ID, requestId)
                .putLong(KEY_REQUESTED_MS, seenMs)
                .apply();
        notifyLocalChange(context);
        return requestId;
    }

    static Request readLocalRequest(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE);
        return new Request(
                prefs.getLong(KEY_REQUEST_ID, 0L),
                prefs.getLong(KEY_REQUESTED_MS, 0L));
    }

    static Request readRemoteRequest(Context context) {
        if (context == null) {
            return new Request(0L, 0L);
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    URI,
                    new String[]{KEY_REQUEST_ID, KEY_REQUESTED_MS},
                    null,
                    null,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                int idIndex = cursor.getColumnIndex(KEY_REQUEST_ID);
                int requestedIndex = cursor.getColumnIndex(KEY_REQUESTED_MS);
                return new Request(
                        idIndex >= 0 ? cursor.getLong(idIndex) : 0L,
                        requestedIndex >= 0 ? cursor.getLong(requestedIndex) : 0L);
            }
        } catch (Throwable throwable) {
            Log.w("HeartwithMiHealth", "sleep channel request read failed", throwable);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return new Request(0L, 0L);
    }

    private static void notifyLocalChange(Context context) {
        if (context != null && MODULE_PACKAGE.equals(context.getPackageName())) {
            try {
                context.getContentResolver().notifyChange(URI, null);
            } catch (Throwable ignored) {
            }
        }
    }

    static void writeRemote(Context context, String summary, String details, long seenMs) {
        if (context == null) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put(KEY_SUMMARY, summary == null ? "" : summary);
        values.put(KEY_DETAILS, details == null ? "" : details);
        values.put(KEY_SEEN_MS, seenMs);
        try {
            int updated = context.getContentResolver().update(URI, values, null, null);
            Log.i("HeartwithMiHealth", "sleep channel status write updated=" + updated);
        } catch (Throwable throwable) {
            Log.w("HeartwithMiHealth", "sleep channel status write failed", throwable);
        }
        Intent intent = new Intent(ACTION_CHANGED);
        intent.setPackage(MODULE_PACKAGE);
        intent.putExtra(EXTRA_SUMMARY, summary == null ? "" : summary);
        intent.putExtra(EXTRA_DETAILS, details == null ? "" : details);
        intent.putExtra(EXTRA_SEEN_MS, seenMs);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        try {
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }
}
