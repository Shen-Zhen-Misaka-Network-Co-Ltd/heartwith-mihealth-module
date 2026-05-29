package com.heartwith.mihealth.lsp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public final class SettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HeartwithSettingsPanel.Controller controller;
    private boolean receiverRegistered;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !HeartwithStatus.ACTION_STATUS_CHANGED.equals(intent.getAction())) {
                return;
            }
            int bpm = intent.getIntExtra(HeartwithStatus.EXTRA_BPM, -1);
            if (bpm < 30 || bpm > 240) {
                return;
            }
            String source = intent.getStringExtra(HeartwithStatus.EXTRA_SOURCE);
            long seenMs = intent.getLongExtra(HeartwithStatus.EXTRA_SEEN_MS, System.currentTimeMillis());
            HeartwithStatus.writeLocal(SettingsActivity.this, bpm, source, seenMs);
            if (controller != null) {
                controller.refreshStatus();
            }
        }
    };
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            HeartwithStatus.markViewerActive(SettingsActivity.this, true);
            if (controller != null) {
                controller.refreshStatus();
            }
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = HeartwithSettingsPanel.create(this, null);
        setContentView(controller.view());
    }

    @Override
    protected void onResume() {
        super.onResume();
        HeartwithStatus.markViewerActive(this, true);
        registerStatusReceiver();
        HeartwithSettingsPanel.sendConfigBroadcast(this, HeartwithSettings.readLocal(this));
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        HeartwithStatus.markViewerActive(this, false);
        handler.removeCallbacks(refreshRunnable);
        unregisterStatusReceiver();
        super.onPause();
    }

    private void registerStatusReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(HeartwithStatus.ACTION_STATUS_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterStatusReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(statusReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        receiverRegistered = false;
    }
}
