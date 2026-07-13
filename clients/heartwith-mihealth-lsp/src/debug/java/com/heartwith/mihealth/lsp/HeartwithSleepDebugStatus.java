package com.heartwith.mihealth.lsp;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

final class HeartwithSleepDebugStatus {
    private static final String MODULE_PACKAGE = "com.heartwith.mihealth.lsp";
    static final Uri URI = Uri.parse("content://" + SettingsProvider.AUTHORITY + "/sleep");
    static final String KEY_SLEEP_SUMMARY = "debug_sleep_summary";
    static final String KEY_SLEEP_DETAILS = "debug_sleep_details";
    static final String KEY_SLEEP_SEEN_MS = "debug_sleep_seen_ms";
    static final String ACTION_REQUEST = "com.heartwith.mihealth.lsp.DEBUG_SLEEP_NOW";
    static final String ACTION_PROBE = "com.heartwith.mihealth.lsp.DEBUG_SLEEP_PROBE";
    static final String EXTRA_PROBE_ENABLED = "debug_sleep_probe_enabled";
    static final String ACTION_SLEEP_CHANGED = "com.heartwith.mihealth.lsp.DEBUG_SLEEP_CHANGED";
    static final String EXTRA_SUMMARY = "summary";
    static final String EXTRA_DETAILS = "details";
    static final String EXTRA_SEEN_MS = "seen_ms";

    final String summary;
    final String details;
    final long seenMs;

    HeartwithSleepDebugStatus(String summary, String details, long seenMs) {
        this.summary = summary == null ? "" : summary;
        this.details = details == null ? "" : details;
        this.seenMs = seenMs;
    }

    static HeartwithSleepDebugStatus readLocal(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE);
        return new HeartwithSleepDebugStatus(
                prefs.getString(KEY_SLEEP_SUMMARY, ""),
                prefs.getString(KEY_SLEEP_DETAILS, ""),
                prefs.getLong(KEY_SLEEP_SEEN_MS, 0L));
    }

    static void writeLocal(Context context, String summary, String details, long seenMs) {
        context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SLEEP_SUMMARY, summary == null ? "" : summary)
                .putString(KEY_SLEEP_DETAILS, details == null ? "" : details)
                .putLong(KEY_SLEEP_SEEN_MS, seenMs)
                .apply();
        if (MODULE_PACKAGE.equals(context.getPackageName())) {
            context.getContentResolver().notifyChange(URI, null);
        }
    }

    static void writeRemote(Context context, String summary, String details, long seenMs) {
        if (context == null) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put(KEY_SLEEP_SUMMARY, summary == null ? "" : summary);
        values.put(KEY_SLEEP_DETAILS, details == null ? "" : details);
        values.put(KEY_SLEEP_SEEN_MS, seenMs);
        try {
            context.getContentResolver().update(URI, values, null, null);
        } catch (Throwable ignored) {
        }
        sendRegisteredStatus(context, summary, details, seenMs);
    }

    static void sendRegisteredStatus(Context context, String summary, String details, long seenMs) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(ACTION_SLEEP_CHANGED);
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
