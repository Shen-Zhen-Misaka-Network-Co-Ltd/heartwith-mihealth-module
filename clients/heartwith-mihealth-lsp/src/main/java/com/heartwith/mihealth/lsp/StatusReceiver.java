package com.heartwith.mihealth.lsp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class StatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null ||
                !HeartwithStatus.ACTION_STATUS_CHANGED.equals(intent.getAction())) {
            return;
        }
        int bpm = intent.getIntExtra(HeartwithStatus.EXTRA_BPM, -1);
        String source = intent.getStringExtra(HeartwithStatus.EXTRA_SOURCE);
        long seenMs = intent.getLongExtra(HeartwithStatus.EXTRA_SEEN_MS, System.currentTimeMillis());
        if (bpm < 30 || bpm > 240) {
            return;
        }
        HeartwithStatus.writeLocal(context, bpm, source, seenMs);
    }
}
