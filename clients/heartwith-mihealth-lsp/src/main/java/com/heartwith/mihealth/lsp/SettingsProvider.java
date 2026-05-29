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
        HeartwithSettings settings = HeartwithSettings.readLocal(getContext());
        MatrixCursor cursor = new MatrixCursor(new String[]{
                HeartwithSettings.KEY_ENABLED,
                HeartwithSettings.KEY_SERVER_URL,
                HeartwithSettings.KEY_DISPLAY_NAME,
        });
        cursor.addRow(new Object[]{
                settings.enabled ? 1 : 0,
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
        if (!"status".equals(uri.getLastPathSegment()) || values == null) {
            throw new UnsupportedOperationException("status only");
        }
        Context context = getContext();
        if (context == null) {
            return 0;
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
