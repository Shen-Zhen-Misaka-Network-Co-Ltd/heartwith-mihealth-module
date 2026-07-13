package com.heartwith.mihealth.lsp;

import android.content.Context;

/** Release keeps high-signal Logcat events but never performs debug file I/O. */
final class DebugSleepLog {
    private DebugSleepLog() {
    }

    static void init(Context context, String processName) {
    }

    static void line(Context context, String processName, String message) {
    }
}
