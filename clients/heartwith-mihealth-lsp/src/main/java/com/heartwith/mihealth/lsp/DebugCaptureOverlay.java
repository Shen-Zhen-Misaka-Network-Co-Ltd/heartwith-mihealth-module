package com.heartwith.mihealth.lsp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

final class DebugCaptureOverlay {
    private static final String TAG = "HeartwithMiHealth";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WindowManager windowManager;
    private static TextView view;
    private static long lastFailureMs;
    private static int lastBpm = -1;
    private static String lastSource = "";
    private static long lastSeenMs;
    private static boolean tickerScheduled;

    private DebugCaptureOverlay() {
    }

    static void update(final Context context, final int bpm, final String source, final long seenMs) {
        if (!BuildConfig.DEBUG || context == null || bpm <= 0) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                updateOnMain(context.getApplicationContext(), bpm, source, seenMs);
            }
        });
    }

    private static void updateOnMain(Context context, int bpm, String source, long seenMs) {
        if (context == null || !canDrawOverlays(context)) {
            return;
        }
        try {
            if (windowManager == null) {
                windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            }
            if (windowManager == null) {
                return;
            }
            if (view == null) {
                view = createView(context);
                windowManager.addView(view, layoutParams());
            }
            lastBpm = bpm;
            lastSource = source == null ? "" : source;
            lastSeenMs = seenMs;
            refreshText();
            scheduleTicker();
        } catch (Throwable throwable) {
            long now = System.currentTimeMillis();
            if (now - lastFailureMs > 60_000L) {
                lastFailureMs = now;
                Log.w(TAG, "debug overlay failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }
    }

    private static void scheduleTicker() {
        if (tickerScheduled) {
            return;
        }
        tickerScheduled = true;
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                tickerScheduled = false;
                if (view == null || lastBpm <= 0) {
                    return;
                }
                refreshText();
                scheduleTicker();
            }
        }, 1_000L);
    }

    private static void refreshText() {
        if (view == null || lastBpm <= 0) {
            return;
        }
        long ageSeconds = Math.max(0L, (System.currentTimeMillis() - lastSeenMs) / 1000L);
        String age = ageSeconds < 60L ? ageSeconds + "s ago" : (ageSeconds / 60L) + "m ago";
        view.setText("● " + lastBpm + " BPM\n" + cleanSource(lastSource) + "\n" + age + " · " + DateFormat.format("HH:mm:ss", lastSeenMs));
    }

    private static TextView createView(Context context) {
        TextView text = new TextView(context);
        text.setTextColor(Color.WHITE);
        text.setTextSize(12);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setGravity(Gravity.CENTER);
        int paddingH = dp(context, 9);
        int paddingV = dp(context, 6);
        text.setPadding(paddingH, paddingV, paddingH, paddingV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xe6ff3b30);
        bg.setCornerRadius(dp(context, 18));
        text.setBackground(bg);
        return text;
    }

    @SuppressWarnings("deprecation")
    private static WindowManager.LayoutParams layoutParams() {
        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 18;
        params.y = 110;
        return params;
    }

    private static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    private static String cleanSource(String source) {
        if (source == null || source.trim().isEmpty()) {
            return "source: hook";
        }
        String trimmed = source.trim();
        if (trimmed.length() > 28) {
            trimmed = trimmed.substring(0, 28);
        }
        return "source: " + trimmed;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
