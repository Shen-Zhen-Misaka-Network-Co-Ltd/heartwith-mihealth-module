package com.heartwith.mihealth.lsp;

import android.content.Context;
import android.net.Uri;

/** Release keeps the API shape but excludes debug storage, broadcasts, and UI state. */
final class HeartwithSleepDebugStatus {
    static final Uri URI = null;
    static final String KEY_SLEEP_SUMMARY = "";
    static final String KEY_SLEEP_DETAILS = "";
    static final String KEY_SLEEP_SEEN_MS = "";
    static final String ACTION_REQUEST = "";
    static final String ACTION_PROBE = "";
    static final String EXTRA_PROBE_ENABLED = "";
    static final String ACTION_SLEEP_CHANGED = "";
    static final String EXTRA_SUMMARY = "";
    static final String EXTRA_DETAILS = "";
    static final String EXTRA_SEEN_MS = "";

    final String summary;
    final String details;
    final long seenMs;

    HeartwithSleepDebugStatus(String summary, String details, long seenMs) {
        this.summary = "";
        this.details = "";
        this.seenMs = 0L;
    }

    static HeartwithSleepDebugStatus readLocal(Context context) {
        return new HeartwithSleepDebugStatus("", "", 0L);
    }

    static void writeLocal(Context context, String summary, String details, long seenMs) {
    }

    static void writeRemote(Context context, String summary, String details, long seenMs) {
    }

    static void sendRegisteredStatus(Context context, String summary, String details, long seenMs) {
    }
}
