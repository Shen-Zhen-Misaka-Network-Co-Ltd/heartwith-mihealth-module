package com.heartwith.mihealth.lsp;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DebugSleepLog {
    private static final String TAG = "HeartwithMiHealth";
    private static final long KEEP_LOG_DAYS = 3L;
    private static final int MAX_LINE_CHARS = 1200;
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat DAY_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static File logFile;
    private static String logDay;

    private DebugSleepLog() {
    }

    static synchronized void init(Context context, String processName) {
        File file = resolveLogFile(context);
        if (file == null) {
            return;
        }
        logFile = file;
        logDay = todayKey();
        pruneOldLogs(file.getParentFile());
        writeLine(processName, "runtime-log init path=" + file.getAbsolutePath());
    }

    static synchronized void line(Context context, String processName, String message) {
        File file = logFile;
        String today = todayKey();
        if (file == null || logDay == null || !logDay.equals(today)) {
            file = resolveLogFile(context);
            logFile = file;
            logDay = today;
            if (file != null) {
                pruneOldLogs(file.getParentFile());
                writeLine(processName, "runtime-log day-open path=" + file.getAbsolutePath());
            }
        }
        if (file == null) {
            return;
        }
        writeLine(processName, message);
    }

    private static File resolveLogFile(Context context) {
        if (context == null) {
            return null;
        }
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        File dir = null;
        try {
            dir = appContext.getExternalFilesDir("heartwith-debug");
        } catch (Throwable ignored) {
        }
        if (dir == null) {
            try {
                dir = new File(appContext.getFilesDir(), "heartwith-debug");
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            return null;
        }
        return new File(dir, "heartwith-debug-" + todayKey() + ".log");
    }

    private static void writeLine(String processName, String message) {
        File file = logFile;
        if (file == null) {
            return;
        }
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(file, true));
            try {
                writer.write(FORMAT.format(new Date()));
                writer.write(" pid=");
                writer.write(Integer.toString(Process.myPid()));
                writer.write(" tid=");
                writer.write(Integer.toString(Process.myTid()));
                writer.write(" elapsedMs=");
                writer.write(Long.toString(SystemClock.elapsedRealtime()));
                writer.write(" process=");
                writer.write(processName == null || processName.length() == 0 ? "unknown" : processName);
                writer.write(" ");
                writer.write(trimLine(message));
                writer.newLine();
            } finally {
                writer.close();
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "write runtime log failed: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static String todayKey() {
        return DAY_FORMAT.format(new Date());
    }

    private static void pruneOldLogs(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoffMs = System.currentTimeMillis() - KEEP_LOG_DAYS * 24L * 60L * 60L * 1000L;
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            if ((!name.startsWith("heartwith-debug-") && !name.startsWith("sleep-debug-")) ||
                    !name.endsWith(".log")) {
                continue;
            }
            if (file.lastModified() > 0L && file.lastModified() < cutoffMs) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static String trimLine(String message) {
        if (message == null) {
            return "";
        }
        if (message.length() <= MAX_LINE_CHARS) {
            return message;
        }
        return message.substring(0, MAX_LINE_CHARS) + "...";
    }
}
