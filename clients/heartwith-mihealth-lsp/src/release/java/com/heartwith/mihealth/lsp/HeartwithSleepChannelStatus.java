package com.heartwith.mihealth.lsp;

import android.content.Context;
import android.net.Uri;

/** Release keeps the call shape while excluding the manual sleep-channel debugger. */
final class HeartwithSleepChannelStatus {
    static final Uri URI = null;
    static final String KEY_SUMMARY = "";
    static final String KEY_DETAILS = "";
    static final String KEY_SEEN_MS = "";
    static final String KEY_REQUEST_ID = "";
    static final String KEY_REQUESTED_MS = "";
    static final String ACTION_REQUEST = "";
    static final String ACTION_CHANGED = "";
    static final String EXTRA_SUMMARY = "";
    static final String EXTRA_DETAILS = "";
    static final String EXTRA_SEEN_MS = "";

    final String summary;
    final String details;
    final long seenMs;

    static final class Request {
        final long id;
        final long requestedMs;

        Request(long id, long requestedMs) {
            this.id = 0L;
            this.requestedMs = 0L;
        }

        boolean isFresh(long nowMs) {
            return false;
        }
    }

    HeartwithSleepChannelStatus(String summary, String details, long seenMs) {
        this.summary = "";
        this.details = "";
        this.seenMs = 0L;
    }

    static HeartwithSleepChannelStatus readLocal(Context context) {
        return new HeartwithSleepChannelStatus("", "", 0L);
    }

    static void writeLocal(Context context, String summary, String details, long seenMs) {
    }

    static long requestNowLocal(Context context, String summary, String details, long seenMs) {
        return 0L;
    }

    static Request readLocalRequest(Context context) {
        return new Request(0L, 0L);
    }

    static Request readRemoteRequest(Context context) {
        return new Request(0L, 0L);
    }

    static void writeRemote(Context context, String summary, String details, long seenMs) {
    }
}
