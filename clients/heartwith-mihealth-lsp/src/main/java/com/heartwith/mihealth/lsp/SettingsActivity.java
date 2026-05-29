package com.heartwith.mihealth.lsp;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public final class SettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HeartwithSettingsPanel.Controller controller;
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
        HeartwithSettingsPanel.sendConfigBroadcast(this, HeartwithSettings.readLocal(this));
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        HeartwithStatus.markViewerActive(this, false);
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }
}
