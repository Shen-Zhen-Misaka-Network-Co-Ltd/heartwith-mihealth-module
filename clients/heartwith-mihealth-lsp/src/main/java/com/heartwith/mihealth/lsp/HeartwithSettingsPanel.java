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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
            Intent intent = configIntent(settings);
            intent.setPackage(packageName);
            try {
                context.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        }
        try {
            context.sendBroadcast(configIntent(settings));
        } catch (Throwable ignored) {
        }
    }

    static void sendSyncNowBroadcast(Context context) {
        for (String packageName : TARGET_PACKAGES) {
            Intent intent = new Intent(HeartwithSettings.ACTION_SYNC_NOW);
            intent.setPackage(packageName);
            intent.putExtra(HeartwithSettings.EXTRA_SYNC_MANUAL, true);
            try {
                context.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        }
        try {
            Intent intent = new Intent(HeartwithSettings.ACTION_SYNC_NOW);
            intent.putExtra(HeartwithSettings.EXTRA_SYNC_MANUAL, true);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    static void sendDebugSleepNowBroadcast(Context context) {
        for (String packageName : TARGET_PACKAGES) {
            Intent intent = new Intent(HeartwithSettings.ACTION_DEBUG_SLEEP_NOW);
            intent.setPackage(packageName);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            try {
                context.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        }
        try {
            Intent intent = new Intent(HeartwithSettings.ACTION_DEBUG_SLEEP_NOW);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    static void sendDebugSleepProbeBroadcast(Context context, boolean enabled) {
        for (String packageName : TARGET_PACKAGES) {
            Intent intent = new Intent(HeartwithSettings.ACTION_DEBUG_SLEEP_PROBE);
            intent.setPackage(packageName);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra(HeartwithSettings.EXTRA_DEBUG_SLEEP_PROBE_ENABLED, enabled);
            try {
                context.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        }
        try {
            Intent intent = new Intent(HeartwithSettings.ACTION_DEBUG_SLEEP_PROBE);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra(HeartwithSettings.EXTRA_DEBUG_SLEEP_PROBE_ENABLED, enabled);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private static Intent configIntent(HeartwithSettings settings) {
        Intent intent = new Intent(HeartwithSettings.ACTION_CONFIG_CHANGED);
        intent.putExtra(HeartwithSettings.EXTRA_ENABLED, settings.enabled);
        intent.putExtra(HeartwithSettings.EXTRA_HOOK_ENABLED, settings.hookEnabled);
        intent.putExtra(HeartwithSettings.EXTRA_SYNC_ENABLED, settings.syncEnabled);
        intent.putExtra(HeartwithSettings.EXTRA_SYNC_INTERVAL_HOURS, settings.syncIntervalHours);
        intent.putExtra(HeartwithSettings.EXTRA_SERVER_URL, settings.serverUrl);
        intent.putExtra(HeartwithSettings.EXTRA_DISPLAY_NAME, settings.displayName);
        return intent;
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
        if (DebugBuild.ENABLED) {
            Log.i(TAG, message);
        }
    }

    static final class Controller {
        private final Activity activity;
        private final Runnable closeAction;
        private final ScrollView root;
        private final EditText serverUrl;
        private final EditText displayName;
        private final EditText syncIntervalHours;
        private final Switch hookEnabled;
        private final Switch syncEnabled;
        private final TextView hookEnabledText;
        private final TextView syncEnabledText;
        private final TextView syncIntervalHelp;
        private final TextView bpmText;
        private final TextView statusText;
        private final TextView sourceText;
        private TextView sleepSummaryText;
        private TextView sleepDetailsText;

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

            if (!HeartwithSettings.onboardingSeen(activity)) {
                LinearLayout guideCard = card(activity);
                guideCard.addView(label(activity, "首次使用", 18, COLOR_TEXT, true), matchWrap());
                TextView guide = label(activity, "按需开启心率 Hook 或定时同步，保存后在小米健康进程内生效。", 14, COLOR_MUTED, false);
                guide.setPadding(0, dp(activity, 8), 0, 0);
                guideCard.addView(guide, matchWrap());
                content.addView(guideCard, matchWrap());
            }

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

            if (DebugBuild.ENABLED) {
                LinearLayout debugCard = card(activity);
                debugCard.addView(label(activity, "Debug 悬浮红点", 18, COLOR_TEXT, true), matchWrap());
                TextView debugText = label(activity, "前台调试时显示最近捕获的 BPM、source 和时间；后台仍看通知。", 14, COLOR_MUTED, false);
                debugText.setPadding(0, dp(activity, 8), 0, 0);
                debugCard.addView(debugText, matchWrap());
                Button overlayPermission = new Button(activity);
                overlayPermission.setText(canDrawDebugOverlay(activity) ? "悬浮窗权限已允许" : "允许悬浮窗权限");
                overlayPermission.setAllCaps(false);
                overlayPermission.setTextSize(15);
                overlayPermission.setTextColor(COLOR_TEXT);
                overlayPermission.setBackground(rounded(activity, COLOR_INPUT, 18));
                overlayPermission.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 8));
                overlayPermission.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openOverlaySettings(activity);
                    }
                });
                debugCard.addView(overlayPermission, matchWrapWithTop(activity, 12));
                content.addView(debugCard, matchWrapWithTop(activity, 18));

                LinearLayout sleepCard = card(activity);
                sleepCard.addView(label(activity, "Debug 睡眠", 18, COLOR_TEXT, true), matchWrap());
                TextView sleepText = label(activity, "点击后触发小米健康同步当前设备睡眠数据，解析结果会实时显示在这里。定期探测只在 debug 包用于夜间实验。", 14, COLOR_MUTED, false);
                sleepText.setPadding(0, dp(activity, 8), 0, 0);
                sleepCard.addView(sleepText, matchWrap());
                Button fetchSleep = new Button(activity);
                fetchSleep.setText("获取睡眠");
                fetchSleep.setAllCaps(false);
                fetchSleep.setTextSize(15);
                fetchSleep.setTextColor(COLOR_TEXT);
                fetchSleep.setBackground(rounded(activity, COLOR_INPUT, 18));
                fetchSleep.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 8));
                fetchSleep.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        long now = System.currentTimeMillis();
                        HeartwithSleepDebugStatus.writeLocal(activity, "正在请求睡眠数据", "等待小米健康同步并解析睡眠数据...", now);
                        refreshSleepDebug();
                        sendDebugSleepNowBroadcast(activity);
                        Toast.makeText(activity, "已请求睡眠数据", Toast.LENGTH_SHORT).show();
                    }
                });
                sleepCard.addView(fetchSleep, matchWrapWithTop(activity, 12));
                Button startSleepProbe = new Button(activity);
                startSleepProbe.setText("开始定期探测");
                startSleepProbe.setAllCaps(false);
                startSleepProbe.setTextSize(15);
                startSleepProbe.setTextColor(COLOR_TEXT);
                startSleepProbe.setBackground(rounded(activity, COLOR_INPUT, 18));
                startSleepProbe.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 8));
                startSleepProbe.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        long now = System.currentTimeMillis();
                        HeartwithSleepDebugStatus.writeLocal(activity,
                                "睡眠定期探测已开启",
                                "将立即探测一次，之后约每 10 分钟用非唤醒 alarm 尝试一次；每轮会在同步前后多次采样。日志会写入 sleep-debug.log。",
                                now);
                        refreshSleepDebug();
                        sendDebugSleepProbeBroadcast(activity, true);
                        Toast.makeText(activity, "已开启睡眠定期探测", Toast.LENGTH_SHORT).show();
                    }
                });
                sleepCard.addView(startSleepProbe, matchWrapWithTop(activity, 10));
                Button stopSleepProbe = new Button(activity);
                stopSleepProbe.setText("停止定期探测");
                stopSleepProbe.setAllCaps(false);
                stopSleepProbe.setTextSize(15);
                stopSleepProbe.setTextColor(COLOR_TEXT);
                stopSleepProbe.setBackground(rounded(activity, COLOR_INPUT, 18));
                stopSleepProbe.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 8));
                stopSleepProbe.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        long now = System.currentTimeMillis();
                        HeartwithSleepDebugStatus.writeLocal(activity,
                                "睡眠定期探测已停止",
                                "已取消后续定时探测。",
                                now);
                        refreshSleepDebug();
                        sendDebugSleepProbeBroadcast(activity, false);
                        Toast.makeText(activity, "已停止睡眠定期探测", Toast.LENGTH_SHORT).show();
                    }
                });
                sleepCard.addView(stopSleepProbe, matchWrapWithTop(activity, 10));
                sleepSummaryText = label(activity, "尚未获取", 16, COLOR_TEXT, true);
                sleepSummaryText.setPadding(0, dp(activity, 14), 0, 0);
                sleepCard.addView(sleepSummaryText, matchWrap());
                sleepDetailsText = label(activity, "点击“获取睡眠”后显示结果。", 13, COLOR_MUTED, false);
                sleepDetailsText.setPadding(0, dp(activity, 8), 0, 0);
                sleepCard.addView(sleepDetailsText, matchWrap());
                content.addView(sleepCard, matchWrapWithTop(activity, 18));
            }

            LinearLayout configCard = card(activity);
            configCard.addView(label(activity, "采集端", 18, COLOR_TEXT, true), matchWrap());
            serverUrl = input(activity, "服务器地址", settings.serverUrl);
            configCard.addView(serverUrl, matchWrapWithTop(activity, 16));
            displayName = input(activity, "显示名称", settings.displayName);
            configCard.addView(displayName, matchWrapWithTop(activity, 12));

            LinearLayout hookRow = switchRow(activity, settings.hookEnabled ? "心率 Hook 已开启" : "心率 Hook 已关闭", "开启后采集小米健康实时心率并上传。");
            hookEnabledText = (TextView) hookRow.findViewWithTag("title");
            hookEnabled = (Switch) hookRow.findViewWithTag("switch");
            hookEnabled.setChecked(settings.hookEnabled);
            hookEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    hookEnabledText.setText(isChecked ? "心率 Hook 已开启" : "心率 Hook 已关闭");
                    save(false);
                }
            });
            configCard.addView(hookRow, matchWrap());

            syncIntervalHours = input(activity, "同步间隔", String.valueOf(settings.syncIntervalHours));
            syncIntervalHours.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            configCard.addView(syncIntervalHours, matchWrapWithTop(activity, 12));
            syncIntervalHelp = label(activity,
                    syncIntervalHelpText(settings.syncIntervalHours),
                    13,
                    COLOR_MUTED,
                    false);
            syncIntervalHelp.setPadding(dp(activity, 2), dp(activity, 6), dp(activity, 2), 0);
            configCard.addView(syncIntervalHelp, matchWrap());

            LinearLayout syncRow = switchRow(activity, settings.syncEnabled ? "后台同步已开启" : "后台同步已关闭", "按填写间隔触发小米健康同步，间隔越长越省电。");
            syncEnabledText = (TextView) syncRow.findViewWithTag("title");
            syncEnabled = (Switch) syncRow.findViewWithTag("switch");
            syncEnabled.setChecked(settings.syncEnabled);
            syncEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    syncEnabledText.setText(isChecked ? "后台同步已开启" : "后台同步已关闭");
                    save(false);
                }
            });
            configCard.addView(syncRow, matchWrap());

            Button syncNow = new Button(activity);
            syncNow.setText("立即同步");
            syncNow.setAllCaps(false);
            syncNow.setTextSize(15);
            syncNow.setTextColor(COLOR_TEXT);
            syncNow.setBackground(rounded(activity, COLOR_INPUT, 18));
            syncNow.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 8));
            syncNow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    save(false);
                    sendSyncNowBroadcast(activity);
                    Toast.makeText(activity, "已请求小米健康同步", Toast.LENGTH_SHORT).show();
                }
            });
            configCard.addView(syncNow, matchWrapWithTop(activity, 12));

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
            refreshSleepDebug();
        }

        void refreshSleepDebug() {
            if (!DebugBuild.ENABLED || sleepSummaryText == null || sleepDetailsText == null) {
                return;
            }
            HeartwithSleepDebugStatus sleep = HeartwithSleepDebugStatus.readLocal(activity);
            if (sleep.summary.isEmpty()) {
                sleepSummaryText.setText("尚未获取");
                sleepDetailsText.setText("点击“获取睡眠”后显示结果。");
                return;
            }
            sleepSummaryText.setText(sleep.summary);
            String relative = HeartwithStatus.relativeTime(System.currentTimeMillis(), sleep.seenMs);
            if (sleep.details.isEmpty()) {
                sleepDetailsText.setText("更新时间：" + relative);
            } else {
                sleepDetailsText.setText(sleep.details + "\n更新时间：" + relative);
            }
        }

        private void save(boolean toast) {
            HeartwithSettings settings = new HeartwithSettings(
                    hookEnabled.isChecked(),
                    serverUrl.getText().toString(),
                    displayName.getText().toString(),
                    syncEnabled.isChecked(),
                    HeartwithSettings.parseSyncIntervalHours(syncIntervalHours.getText().toString()));
            HeartwithSettings.writeLocal(activity, settings);
            sendConfigBroadcast(activity, settings);
            syncIntervalHelp.setText(syncIntervalHelpText(settings.syncIntervalHours));
            if (toast) {
                syncIntervalHours.setText(String.valueOf(settings.syncIntervalHours));
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static LinearLayout switchRow(Context context, String title, String subtitle) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 18), 0, 0);
        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = label(context, title, 18, COLOR_TEXT, true);
        titleView.setTag("title");
        text.addView(titleView, matchWrap());
        text.addView(label(context, subtitle, 14, COLOR_MUTED, false), matchWrapWithTop(context, 3));
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch switchView = new Switch(context);
        switchView.setTag("switch");
        row.addView(switchView, wrapWrap());
        return row;
    }

    private static String syncIntervalHelpText(int hours) {
        return "单位：小时。当前表示每 " + hours + " 小时触发一次小米健康后台同步，间隔越长越省电。";
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

    private static boolean canDrawDebugOverlay(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    private static void openOverlaySettings(Activity activity) {
        if (activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(activity, "当前系统不需要单独授权", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Throwable throwable) {
            Toast.makeText(activity, "无法打开悬浮窗权限页", Toast.LENGTH_SHORT).show();
        }
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
