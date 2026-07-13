package com.heartwith.mihealth.lsp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public final class SettingsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.heartwith.mihealth.lsp.settings";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");
    public static final Uri STATUS_URI = Uri.parse("content://" + AUTHORITY + "/status");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if ("status".equals(uri.getLastPathSegment())) {
            HeartwithStatus status = HeartwithStatus.readLocal(getContext());
            MatrixCursor cursor = new MatrixCursor(new String[]{
                    HeartwithStatus.KEY_LAST_BPM,
                    HeartwithStatus.KEY_LAST_SOURCE,
                    HeartwithStatus.KEY_LAST_SEEN_MS,
                    HeartwithStatus.KEY_VIEWER_ACTIVE_UNTIL_MS,
            });
            long viewerActiveUntilMs = getContext()
                    .getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE)
                    .getLong(HeartwithStatus.KEY_VIEWER_ACTIVE_UNTIL_MS, 0L);
            cursor.addRow(new Object[]{status.bpm, status.source, status.seenMs, viewerActiveUntilMs});
            return cursor;
        }
        if (DebugBuild.ENABLED && "sleep".equals(uri.getLastPathSegment())) {
            HeartwithSleepDebugStatus status = HeartwithSleepDebugStatus.readLocal(getContext());
            MatrixCursor cursor = new MatrixCursor(new String[]{
                    HeartwithSleepDebugStatus.KEY_SLEEP_SUMMARY,
                    HeartwithSleepDebugStatus.KEY_SLEEP_DETAILS,
                    HeartwithSleepDebugStatus.KEY_SLEEP_SEEN_MS,
            });
            cursor.addRow(new Object[]{status.summary, status.details, status.seenMs});
            return cursor;
        }
        if (DebugBuild.ENABLED && "sleep-channel".equals(uri.getLastPathSegment())) {
            HeartwithSleepChannelStatus status = HeartwithSleepChannelStatus.readLocal(getContext());
            SharedPreferences prefs = getContext()
                    .getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE);
            MatrixCursor cursor = new MatrixCursor(new String[]{
                    HeartwithSleepChannelStatus.KEY_SUMMARY,
                    HeartwithSleepChannelStatus.KEY_DETAILS,
                    HeartwithSleepChannelStatus.KEY_SEEN_MS,
                    HeartwithSleepChannelStatus.KEY_REQUEST_ID,
                    HeartwithSleepChannelStatus.KEY_REQUESTED_MS,
            });
            cursor.addRow(new Object[]{
                    status.summary,
                    status.details,
                    status.seenMs,
                    prefs.getLong(HeartwithSleepChannelStatus.KEY_REQUEST_ID, 0L),
                    prefs.getLong(HeartwithSleepChannelStatus.KEY_REQUESTED_MS, 0L),
            });
            return cursor;
        }
        HeartwithSettings settings = HeartwithSettings.readLocal(getContext());
        MatrixCursor cursor = new MatrixCursor(new String[]{
                HeartwithSettings.KEY_ENABLED,
                HeartwithSettings.KEY_HOOK_ENABLED,
                HeartwithSettings.KEY_SYNC_ENABLED,
                HeartwithSettings.KEY_SYNC_INTERVAL_HOURS,
                HeartwithSettings.KEY_SERVER_URL,
                HeartwithSettings.KEY_DISPLAY_NAME,
        });
        cursor.addRow(new Object[]{
                settings.enabled ? 1 : 0,
                settings.hookEnabled ? 1 : 0,
                settings.syncEnabled ? 1 : 0,
                settings.syncIntervalHours,
                settings.serverUrl,
                settings.displayName,
        });
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.heartwith.mihealth.config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (values == null) {
            throw new UnsupportedOperationException("values required");
        }
        Context context = getContext();
        if (context == null) {
            return 0;
        }
        if (DebugBuild.ENABLED && "sleep".equals(uri.getLastPathSegment())) {
            String summary = values.getAsString(HeartwithSleepDebugStatus.KEY_SLEEP_SUMMARY);
            String details = values.getAsString(HeartwithSleepDebugStatus.KEY_SLEEP_DETAILS);
            Long seenMs = values.getAsLong(HeartwithSleepDebugStatus.KEY_SLEEP_SEEN_MS);
            HeartwithSleepDebugStatus.writeLocal(
                    context,
                    summary == null ? "" : summary,
                    details == null ? "" : details,
                    seenMs == null ? System.currentTimeMillis() : seenMs);
            return 1;
        }
        if (DebugBuild.ENABLED && "sleep-channel".equals(uri.getLastPathSegment())) {
            String summary = values.getAsString(HeartwithSleepChannelStatus.KEY_SUMMARY);
            String details = values.getAsString(HeartwithSleepChannelStatus.KEY_DETAILS);
            Long seenMs = values.getAsLong(HeartwithSleepChannelStatus.KEY_SEEN_MS);
            SharedPreferences prefs = context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(HeartwithSleepChannelStatus.KEY_SUMMARY, summary == null ? "" : summary)
                    .putString(HeartwithSleepChannelStatus.KEY_DETAILS, details == null ? "" : details)
                    .putLong(HeartwithSleepChannelStatus.KEY_SEEN_MS,
                            seenMs == null ? System.currentTimeMillis() : seenMs);
            Long requestId = values.getAsLong(HeartwithSleepChannelStatus.KEY_REQUEST_ID);
            Long requestedMs = values.getAsLong(HeartwithSleepChannelStatus.KEY_REQUESTED_MS);
            if (requestId != null) {
                editor.putLong(HeartwithSleepChannelStatus.KEY_REQUEST_ID, requestId);
            }
            if (requestedMs != null) {
                editor.putLong(HeartwithSleepChannelStatus.KEY_REQUESTED_MS, requestedMs);
            }
            editor.apply();
            context.getContentResolver().notifyChange(HeartwithSleepChannelStatus.URI, null);
            return 1;
        }
        if (!"status".equals(uri.getLastPathSegment())) {
            throw new UnsupportedOperationException("status only");
        }
        int bpm = values.getAsInteger(HeartwithStatus.KEY_LAST_BPM) != null
                ? values.getAsInteger(HeartwithStatus.KEY_LAST_BPM)
                : -1;
        String source = values.getAsString(HeartwithStatus.KEY_LAST_SOURCE);
        Long seenMs = values.getAsLong(HeartwithStatus.KEY_LAST_SEEN_MS);
        String processName = values.getAsString(HeartwithStatus.KEY_PROCESS_NAME);
        long now = seenMs != null ? seenMs : System.currentTimeMillis();
        SharedPreferences prefs = context.getSharedPreferences(HeartwithSettings.PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt(HeartwithStatus.KEY_LAST_BPM, bpm)
                .putString(HeartwithStatus.KEY_LAST_SOURCE, source == null ? "" : source)
                .putLong(HeartwithStatus.KEY_LAST_SEEN_MS, now)
                .putString(HeartwithStatus.KEY_ACTIVE_PROCESS, processName == null ? "" : processName)
                .putLong(HeartwithStatus.KEY_ACTIVE_PROCESS_SEEN_MS, now)
                .apply();
        context.getContentResolver().notifyChange(SettingsProvider.STATUS_URI, null);
        return 1;
    }
}
