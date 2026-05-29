package com.heartwith.mihealth.lsp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

final class HeartwithSettingsPanel {
    private static final String TAG = "HeartwithMiHealth";
    static final String EXTRA_SHOW_SETTINGS = "heartwith_show_settings";
    private static final String[] TARGET_PACKAGES = {
            "com.mi.health",
            "com.mi.health.heartwith",
            "com.heartwith.mihealth.lsp"
    };
    private static final int COLOR_BG = 0xff000000;
    private static final int COLOR_CARD = 0xff242424;
    private static final int COLOR_INPUT = 0xff484848;
    private static final int COLOR_TEXT = 0xfff5f5f5;
    private static final int COLOR_MUTED = 0xffa5a5a5;
    private static final int COLOR_BLUE = 0xff0a84ff;
    private static final int TAG_BUTTON = 0x23014331;
    private static volatile boolean npatchEntryInstalled;
    private static volatile boolean notificationPermissionRequested;
    private static volatile AlertDialog showingDialog;

    private HeartwithSettingsPanel() {
    }

    static void installNpatchEntry(Application application) {
        if (application == null || npatchEntryInstalled) {
            return;
        }
        npatchEntryInstalled = true;
        debug("settings panel lifecycle installed package=" + application.getPackageName());
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                attachButton(activity);
                requestNotificationPermissionOnce(activity);
                Intent intent = activity.getIntent();
                if (intent != null && intent.getBooleanExtra(EXTRA_SHOW_SETTINGS, false)) {
                    intent.removeExtra(EXTRA_SHOW_SETTINGS);
                    showDialog(activity);
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    static Controller create(Activity activity, Runnable closeAction) {
        requestNotificationPermissionIfNeeded(activity);
        return new Controller(activity, closeAction);
    }

    static void sendConfigBroadcast(Context context, HeartwithSettings settings) {
        for (String packageName : TARGET_PACKAGES) {
            Intent intent = new Intent(HeartwithSettings.ACTION_CONFIG_CHANGED);
            intent.setPackage(packageName);
            intent.putExtra(HeartwithSettings.EXTRA_ENABLED, settings.enabled);
            intent.putExtra(HeartwithSettings.EXTRA_SERVER_URL, settings.serverUrl);
            intent.putExtra(HeartwithSettings.EXTRA_DISPLAY_NAME, settings.displayName);
            try {
                context.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    static void showDialog(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        AlertDialog existing = showingDialog;
        if (existing != null && existing.isShowing()) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(activity).create();
        final Controller controller = create(activity, new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
            }
        });
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                if (dialog.isShowing()) {
                    HeartwithStatus.markViewerActive(activity, true);
                    controller.refreshStatus();
                    handler.postDelayed(this, 1000L);
                }
            }
        };
        showingDialog = dialog;
        dialog.setView(controller.view());
        dialog.setOnDismissListener(d -> {
            handler.removeCallbacks(refresh);
            HeartwithStatus.markViewerActive(activity, false);
            showingDialog = null;
        });
        dialog.show();
        handler.post(refresh);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(rounded(activity, COLOR_BG, 26));
        }
    }

    private static void attachButton(final Activity activity) {
        if (activity == null || activity instanceof SettingsActivity) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        if (!(decor instanceof FrameLayout)) {
            return;
        }
        FrameLayout root = (FrameLayout) decor;
        if (root.findViewWithTag(TAG_BUTTON) != null) {
            return;
        }
        TextView button = new TextView(activity);
        button.setTag(TAG_BUTTON);
        button.setText("Heartwith");
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(activity, 12), dp(activity, 7), dp(activity, 12), dp(activity, 7));
        button.setBackground(rounded(activity, 0xcc0a84ff, 18));
        button.setAlpha(0.92f);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDialog(activity);
            }
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        params.topMargin = dp(activity, 18);
        params.rightMargin = dp(activity, 16);
        root.addView(button, params);
        debug("settings panel button attached activity=" + activity.getClass().getName());
    }

    private static void requestNotificationPermissionIfNeeded(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, HeartwithStatus.NOTIFICATION_ID);
        }
    }

    private static void requestNotificationPermissionOnce(Activity activity) {
        if (notificationPermissionRequested) {
            return;
        }
        notificationPermissionRequested = true;
        requestNotificationPermissionIfNeeded(activity);
    }

    private static void debug(String message) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, message);
        }
    }

    static final class Controller {
        private final Activity activity;
        private final Runnable closeAction;
        private final ScrollView root;
        private final EditText serverUrl;
        private final EditText displayName;
        private final Switch enabled;
        private final TextView enabledText;
        private final TextView bpmText;
        private final TextView statusText;
        private final TextView sourceText;

        Controller(final Activity activity, final Runnable closeAction) {
            this.activity = activity;
            this.closeAction = closeAction;
            HeartwithSettings settings = HeartwithSettings.readLocal(activity);
            root = new ScrollView(activity);
            root.setFillViewport(true);
            root.setBackgroundColor(COLOR_BG);

            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(activity, 24), dp(activity, 32), dp(activity, 24), dp(activity, 24));
            root.addView(content);

            TextView title = label(activity, "Heartwith", 34, COLOR_TEXT, true);
            content.addView(title, matchWrap());
            TextView subtitle = label(activity, "小米健康 Hook 采集端", 17, COLOR_MUTED, true);
            subtitle.setPadding(0, dp(activity, 4), 0, dp(activity, 24));
            content.addView(subtitle, matchWrap());

            LinearLayout statusCard = card(activity);
            TextView chip = chip(activity, "Hook");
            statusCard.addView(chip, wrapWrap());
            bpmText = label(activity, "等待心率", 34, COLOR_TEXT, true);
            bpmText.setPadding(0, dp(activity, 14), 0, 0);
            statusCard.addView(bpmText, matchWrap());
            statusText = label(activity, "等待小米运动健康实时心率事件", 16, COLOR_MUTED, true);
            statusText.setPadding(0, dp(activity, 10), 0, 0);
            statusCard.addView(statusText, matchWrap());
            sourceText = label(activity, "来源：尚未采集", 13, COLOR_MUTED, false);
            sourceText.setPadding(0, dp(activity, 8), 0, 0);
            statusCard.addView(sourceText, matchWrap());
            content.addView(statusCard, matchWrap());

            LinearLayout configCard = card(activity);
            configCard.addView(label(activity, "采集端", 18, COLOR_TEXT, true), matchWrap());
            serverUrl = input(activity, "服务器地址", settings.serverUrl);
            configCard.addView(serverUrl, matchWrapWithTop(activity, 16));
            displayName = input(activity, "显示名称", settings.displayName);
            configCard.addView(displayName, matchWrapWithTop(activity, 12));

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(activity, 18), 0, 0);
            LinearLayout text = new LinearLayout(activity);
            text.setOrientation(LinearLayout.VERTICAL);
            enabledText = label(activity, settings.enabled ? "Hook 上传已启用" : "Hook 上传已关闭", 18, COLOR_TEXT, true);
            text.addView(enabledText, matchWrap());
            text.addView(label(activity, "关闭后小米健康心率不会上传。", 14, COLOR_MUTED, false), matchWrapWithTop(activity, 3));
            row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            enabled = new Switch(activity);
            enabled.setChecked(settings.enabled);
            enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    enabledText.setText(isChecked ? "Hook 上传已启用" : "Hook 上传已关闭");
                    save(false);
                }
            });
            row.addView(enabled, wrapWrap());
            configCard.addView(row, matchWrap());

            Button save = new Button(activity);
            save.setText("保存配置");
            save.setAllCaps(false);
            save.setTextSize(16);
            save.setTextColor(Color.WHITE);
            save.setBackground(rounded(activity, COLOR_BLUE, 18));
            save.setPadding(dp(activity, 18), dp(activity, 10), dp(activity, 18), dp(activity, 10));
            save.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    save(true);
                    if (closeAction != null) {
                        closeAction.run();
                    }
                }
            });
            configCard.addView(save, matchWrapWithTop(activity, 18));
            content.addView(configCard, matchWrapWithTop(activity, 18));
            refreshStatus();
        }

        View view() {
            return root;
        }

        void refreshStatus() {
            HeartwithStatus status = HeartwithStatus.readLocal(activity);
            if (status.bpm > 0) {
                bpmText.setText(status.bpm + " BPM");
                statusText.setText("已采集心率 · " + HeartwithStatus.relativeTime(System.currentTimeMillis(), status.seenMs));
                sourceText.setText("来源：" + (status.source.isEmpty() ? "小米健康 Hook" : status.source));
            } else {
                bpmText.setText("等待心率");
                statusText.setText("打开小米运动健康的运动页后开始采集");
                sourceText.setText("来源：尚未采集");
            }
        }

        private void save(boolean toast) {
            HeartwithSettings settings = new HeartwithSettings(
                    enabled.isChecked(),
                    serverUrl.getText().toString(),
                    displayName.getText().toString());
            HeartwithSettings.writeLocal(activity, settings);
            sendConfigBroadcast(activity, settings);
            if (toast) {
                Toast.makeText(activity, "已保存，小米健康下次启动会自动同步", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        card.setBackground(rounded(context, COLOR_CARD, 24));
        return card;
    }

    private static TextView chip(Context context, String text) {
        TextView view = label(context, text, 12, COLOR_BLUE, true);
        view.setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3));
        view.setBackground(rounded(context, 0x33248cff, 7));
        return view;
    }

    private static TextView label(Context context, String text, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(sp);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private static EditText input(Context context, String hint, String text) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(text);
        input.setTextSize(18);
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(0xff858585);
        input.setBackground(rounded(context, COLOR_INPUT, 18));
        input.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        return input;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams matchWrapWithTop(Context context, int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(context, topDp);
        return params;
    }

    private static GradientDrawable rounded(Context context, int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
