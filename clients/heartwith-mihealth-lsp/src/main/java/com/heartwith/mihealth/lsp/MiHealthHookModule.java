package com.heartwith.mihealth.lsp;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Build;
import android.os.Bundle;
import android.security.NetworkSecurityPolicy;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.heartwith.uploader.HeartwithSleepStatus;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class MiHealthHookModule extends XposedModule {
    private static final String TAG = "HeartwithMiHealth";
    private static final String TARGET_PACKAGE = "com.mi.health";
    private static final String PATCHED_TARGET_PACKAGE = "com.mi.health.heartwith";
    private static final String RUNTIME_PREFS = "heartwith_mihealth_runtime";
    private static final String KEY_CACHED_SERVER_URL = "cached_server_url";
    private static final String KEY_SLEEP_FINAL_UPLOADED_KEY = "sleep_final_uploaded_key";
    private static final String ACTION_SPORT_MODE_CHANGED = "com.heartwith.mihealth.lsp.SPORT_MODE_CHANGED";
    private static final String ACTION_DEVICE_CHANGED = "com.heartwith.mihealth.lsp.DEVICE_CHANGED";
    private static final String ACTION_HEART_RATE_WATCHDOG = "com.heartwith.mihealth.lsp.HEART_RATE_WATCHDOG";
    private static final String ACTION_SLEEP_STATUS_POLL = "com.heartwith.mihealth.lsp.SLEEP_STATUS_POLL";
    private static final String EXTRA_SPORT_MODE_UNTIL_MS = "sport_mode_until_ms";
    private static final String EXTRA_DEVICE_DID = "device_did";
    private static final String EXTRA_DEVICE_NAME = "device_name";
    private static final String EXTRA_SYNC_GENERATION = "sync_generation";
    private static final String EXTRA_HEART_RATE_WATCHDOG_GENERATION = "heart_rate_watchdog_generation";
    private static final String EXTRA_SLEEP_STATUS_GENERATION = "sleep_status_generation";
    private static final String KEY_ACTIVE_SOURCE = "active_source";
    private static final String KEY_ACTIVE_SOURCE_SEEN_MS = "active_source_seen_ms";
    private static final String KEY_LAST_HR_SEEN_MS = "last_hr_seen_ms";
    private static final String KEY_LEGACY_KICK_NEEDED_MS = "legacy_kick_needed_ms";
    private static final String KEY_LAST_COLD_START_RECYCLE_MS = "last_cold_start_recycle_ms";
    private static final long DUPLICATE_WINDOW_MS = 900L;
    private static final long ACCEPTED_LOG_INTERVAL_MS = DebugBuild.ENABLED ? 5_000L : 60_000L;
    private static final long RESTORED_SOURCE_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final long CROSS_PROCESS_HR_RECENT_MS = 60_000L;
    private static final long LEGACY_KICK_REQUEST_TTL_MS = 60_000L;
    private static final long LAST_HR_SEEN_PERSIST_MS = 8_000L;
    private static final long DEVICE_MODEL_REFRESH_MS = 30_000L;
    private static final long DEVICE_MODEL_UNRESOLVED_RETRY_MS = 180_000L;
    private static final long HEART_RATE_WATCHDOG_MS =
            (DebugBuild.ENABLED ? 12L : 60L) * 1000L;
    private static final long HEART_RATE_ALARM_WATCHDOG_MS =
            (DebugBuild.ENABLED ? 2L : 5L) * 60L * 1000L;
    private static final long HEART_RATE_ALARM_WINDOW_MS = 2L * 60L * 1000L;
    private static final long HEART_RATE_ALARM_RESCHEDULE_MIN_MS =
            (DebugBuild.ENABLED ? 1L : 2L) * 60L * 1000L;
    private static final long START_HELPER_WATCHDOG_COOLDOWN_MS =
            (DebugBuild.ENABLED ? 60L : 10L * 60L) * 1000L;
    private static final long START_HELPER_SPORT_COOLDOWN_MS =
            (DebugBuild.ENABLED ? 15L : 60L) * 1000L;
    private static final long COLD_START_RECYCLE_MIN_UPTIME_MS = 90_000L;
    private static final long COLD_START_RECYCLE_MAX_UPTIME_MS = 10L * 60L * 1000L;
    private static final long COLD_START_RECYCLE_COOLDOWN_MS = 6L * 60L * 60L * 1000L;
    private static final long SPORT_MODE_GRACE_MS = 10_000L;
    private static final long STATUS_UPDATE_MIN_INTERVAL_MS = 10_000L;
    private static final long SYNC_MIN_TRIGGER_GAP_MS = 10L * 60L * 1000L;
    private static final long SYNC_MANUAL_MIN_TRIGGER_GAP_MS = 5_000L;
    private static final long SYNC_ALARM_WINDOW_MS = 15L * 60L * 1000L;
    private static final long DEBUG_SLEEP_PROBE_INTERVAL_MS = 10L * 60L * 1000L;
    private static final long DEBUG_SLEEP_PROBE_WINDOW_MS = 3L * 60L * 1000L;
    private static final long DEBUG_SLEEP_ONLY_SYNC_DELAY_MS = 30_000L;
    private static final long DEBUG_SLEEP_ONLY_SYNC_MIN_GAP_MS = 10L * 60L * 1000L;
    private static final long SLEEP_STATUS_POLL_INTERVAL_MS = 5L * 60L * 1000L;
    private static final long SLEEP_STATUS_POLL_WINDOW_MS = 2L * 60L * 1000L;
    private static final long SLEEP_STATUS_MIN_SLEEP_MS = 5L * 60L * 1000L;
    private static final long SLEEP_STATUS_MAX_AGE_MS = 20L * 60L * 60L * 1000L;
    private static final int STATUS_UPDATE_CHANGE_BPM = 3;
    private static final boolean VERBOSE_LOGS = DebugBuild.ENABLED;
    private static final String NPATCH_ORIGIN_ASSET = "assets/npatch/origin.apk";
    private static final String[] AROUTER_ROOTS = {
            "com.alibaba.android.arouter.routes.ARouter$$Root$$arouterapi",
            "com.alibaba.android.arouter.routes.ARouter$$Root$$devicemanager",
            "com.alibaba.android.arouter.routes.ARouter$$Root$$ecodevicemanager",
            "com.alibaba.android.arouter.routes.ARouter$$Root$$electronicscale",
            "com.alibaba.android.arouter.routes.ARouter$$Root$$health",
            "com.alibaba.android.arouter.routes.ARouter$$Root$$login",
            "com.alibaba.android.arouter.routes.ARouter$$Root$$main",
            "com.alibaba.android.arouter.routes.ARouter$$Root$$qrcode",
            "com.alibaba.android.arouter.routes.ARouter$$Root$$sport",
            "com.alibaba.android.arouter.routes.ARouter$$Root$$sporteco"
    };
    private static final String[] AROUTER_PROVIDERS = {
            "com.alibaba.android.arouter.routes.ARouter$$Providers$$arouterapi",
            "com.alibaba.android.arouter.routes.ARouter$$Providers$$devicemanager",
            "com.alibaba.android.arouter.routes.ARouter$$Providers$$ecodevicemanager",
            "com.alibaba.android.arouter.routes.ARouter$$Providers$$electronicscale",
            "com.alibaba.android.arouter.routes.ARouter$$Providers$$health",
            "com.alibaba.android.arouter.routes.ARouter$$Providers$$login",
            "com.alibaba.android.arouter.routes.ARouter$$Providers$$main",
            "com.alibaba.android.arouter.routes.ARouter$$Providers$$qrcode",
            "com.alibaba.android.arouter.routes.ARouter$$Providers$$sport",
            "com.alibaba.android.arouter.routes.ARouter$$Providers$$sporteco"
    };

    private static final String[] START_HELPERS = {
            "com.xiaomi.fitness.sport_eco.extension.EcoDeviceModelExtKt",
            "com.xiaomi.fitness.sport_eco_manager.extension.DeviceModelExtKt",
            "com.xiaomi.fitness.sport.extension.DeviceModelExtKt",
            "com.xiaomi.fitness.sport_manager.extension.DeviceModelExtKt"
    };

    private static final String[] LAUNCH_MODEL_CLASSES = {
            "com.xiaomi.fitness.sport_eco.model.LaunchSportModel",
            "com.xiaomi.fitness.sport_eco_manager.model.LaunchSportModel",
            "com.xiaomi.fitness.sport.model.LaunchSportModel",
            "com.xiaomi.fitness.sport_manager.model.LaunchSportModel"
    };

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "heartwith-mihealth");
            thread.setDaemon(true);
            return thread;
        }
    });
    private static final ExecutorService UPLOAD_WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "heartwith-upload");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean npatchHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean notificationPermissionRequested = new AtomicBoolean(false);
    private final AtomicBoolean heartRateWatchdogScheduled = new AtomicBoolean(false);
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean configReceiverRegistered = new AtomicBoolean(false);
    private final AtomicBoolean sportModeReceiverRegistered = new AtomicBoolean(false);
    private final AtomicBoolean deviceChangeReceiverRegistered = new AtomicBoolean(false);
    private final AtomicBoolean syncUiHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean sleepStatusHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean sleepStatusPollScheduled = new AtomicBoolean(false);
    private final AtomicBoolean sleepStatusRepositoryPending = new AtomicBoolean(false);
    private final AtomicBoolean sleepStatusTodayIdsPending = new AtomicBoolean(false);
    private final AtomicBoolean cleartextPolicyHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean coldStartRecycleScheduled = new AtomicBoolean(false);
    private final AtomicBoolean debugLifecycleRegistered = new AtomicBoolean(false);
    private final AtomicBoolean debugSleepHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean debugSleepOnlySyncPending = new AtomicBoolean(false);
    private final AtomicBoolean debugTodaySleepIdsPending = new AtomicBoolean(false);
    private final AtomicBoolean debugSleepRepositoryPending = new AtomicBoolean(false);
    private final HeartwithUploader uploader = new HeartwithUploader(UPLOAD_WORKER);
    private final List<Object> launchModels = new ArrayList<>();
    private volatile Context appContext;
    private volatile Object hrCallback;
    private volatile Object huamiControllerCallback;
    private volatile WeakReference<Object> huamiHrController = new WeakReference<>(null);
    private volatile WeakReference<Object> huamiBleDevice = new WeakReference<>(null);
    private volatile boolean started;
    private volatile long lastStartAt;
    private volatile String lastStartReason = "";
    private volatile long lastStartHelperScanElapsedMs;
    private volatile long lastSportStartHelperScanElapsedMs;
    private volatile String lastStartHelperDeviceIdentity;
    private volatile int lastHr = -1;
    private volatile long lastHrElapsedMs;
    private volatile int noHeartStartAttempts;
    private volatile int legacyKickChecks;
    private volatile boolean legacyKickRequestLogged;
    private volatile boolean legacyKickAttemptLogged;
    private volatile boolean firstHeartRateLogged;
    private volatile boolean deviceModelNullLogged;
    private volatile boolean deviceModelDumpLogged;
    private volatile long lastRawDiagElapsedMs;
    private volatile long lastAcceptedLogMs;
    private volatile String activeSource;
    private volatile long activeSourceElapsedMs;
    private volatile long lastActiveSourcePersistElapsedMs;
    private volatile boolean activeSourceRestored;
    private volatile String targetPackage = TARGET_PACKAGE;
    private volatile String processName = TARGET_PACKAGE;
    private volatile ClassLoader targetClassLoader;
    private volatile long lastDeviceModelCheckElapsedMs;
    private volatile long lastDeviceModelResolveElapsedMs;
    private volatile long lastHeartRateSeenPersistElapsedMs;
    private volatile long lastStatusUpdateElapsedMs;
    private volatile int lastStatusUpdateBpm = -1;
    private volatile long sportModeActiveUntilMs;
    private volatile String currentDeviceIdentity;
    private volatile boolean currentDeviceModelResolved;
    private volatile boolean legacyKickClearedAfterHeartRate;
    private volatile boolean npatchWrappedDetected;
    private volatile boolean npatchRouteDiagLogged;
    private volatile boolean npatchArouterIndexesInstalled;
    private volatile boolean cleartextPolicyAllowLogged;
    private volatile boolean heartRateHookEnabled;
    private volatile boolean periodicSyncEnabled;
    private volatile int periodicSyncIntervalHours = HeartwithSettings.DEFAULT_SYNC_INTERVAL_HOURS;
    private volatile int syncScheduleGeneration;
    private volatile int debugSleepProbeGeneration;
    private volatile boolean debugSleepProbeEnabled;
    private volatile int heartRateWatchdogGeneration;
    private volatile int sleepStatusPollGeneration;
    private volatile long lastSleepStatusFetchElapsedMs;
    private volatile String lastSleepStatusKey;
    private volatile long lastSleepStatusUploadElapsedMs;
    private volatile long sleepTrackingDayStartMs;
    private volatile boolean sleepCandidateSeenToday;
    private volatile boolean sleepFinalReportRequested;
    private volatile String sleepFinalUploadedKey;
    private volatile long lastHeartRateWatchdogAlarmElapsedMs;
    private volatile long lastSyncTriggerElapsedMs;
    private volatile long lastSyncSuccessElapsedMs;
    private volatile long lastDebugSleepOnlySyncElapsedMs;
    private volatile int lastDebugSleepOnlySyncHash;
    private volatile long lastRuntimeSettingsRefreshElapsedMs;
    private volatile WeakReference<View> syncButtonView = new WeakReference<>(null);
    private volatile WeakReference<View.OnClickListener> syncButtonListener = new WeakReference<>(null);

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        importantLine("module loaded api=" + getApiVersion()
                + ", version=" + BuildConfig.VERSION_NAME);
        if (DebugBuild.ENABLED) {
            logLine("module loaded api=" + getApiVersion());
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (!isSupportedPackage(packageName)) {
            return;
        }
        targetPackage = packageName;
        String currentProcess = getProcessName();
        processName = currentProcess == null ? targetPackage : currentProcess;
        importantLine("package ready package=" + packageName
                + ", process=" + processName
                + ", main=" + isMainProcess()
                + ", worker=" + isWorkerProcess()
                + ", uptime=" + SystemClock.elapsedRealtime());
        if (DebugBuild.ENABLED) {
            diagLine("package ready package=" + packageName
                    + ", process=" + processName
                    + ", main=" + isMainProcess()
                    + ", worker=" + isWorkerProcess()
                    + ", uptime=" + SystemClock.elapsedRealtime());
        }
        if (!isMainProcess() && !isWorkerProcess()) {
            if (DebugBuild.ENABLED) {
                diagLine("ignore process=" + processName);
            }
            return;
        }
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        ClassLoader classLoader = param.getClassLoader();
        targetClassLoader = classLoader;
        if (isWorkerProcess() || isMainProcess()) {
            hookHeartwithCleartextPolicy();
        }
        hookSleepStatus(classLoader);
        hookSleepDiagnostics(classLoader);
        hookLifecycle(classLoader);
        if (isWorkerProcess()) {
            hookHeartRateSinks(classLoader);
            hookHeartRateStopControls(classLoader);
        } else if (isMainProcess()) {
            hookSyncUiSignals(classLoader);
            hookPassiveSportHeartRateSinks(classLoader);
        }
        if (DebugBuild.ENABLED) {
            logLine("hooks installed process=" + processName);
        }
    }

    private void hookHeartwithCleartextPolicy() {
        if (!cleartextPolicyHookInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            Method method = NetworkSecurityPolicy.class.getDeclaredMethod("isCleartextTrafficPermitted", String.class);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object arg = chain.getArg(0);
                    if (arg instanceof String && isHeartwithServerHost((String) arg)) {
                        if (DebugBuild.ENABLED && !cleartextPolicyAllowLogged) {
                            cleartextPolicyAllowLogged = true;
                            Log.i(TAG, "allow cleartext for Heartwith host: " + arg);
                        }
                        return true;
                    }
                    return chain.proceed();
                }
            });
            Method globalMethod = NetworkSecurityPolicy.class.getDeclaredMethod("isCleartextTrafficPermitted");
            hook(globalMethod).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (HeartwithCleartextScope.isActive()) {
                        if (DebugBuild.ENABLED && !cleartextPolicyAllowLogged) {
                            cleartextPolicyAllowLogged = true;
                            Log.i(TAG, "allow cleartext for Heartwith request scope");
                        }
                        return true;
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("heartwith cleartext policy hook failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }
        hookAndroidOkHttpCleartextPolicy();
        if (DebugBuild.ENABLED) {
            diagLine("heartwith cleartext policy hook installed");
        }
    }

    private void hookAndroidOkHttpCleartextPolicy() {
        String[] classNames = {
                "com.android.okhttp.internal.Platform",
                "com.android.okhttp.internal.AndroidPlatform",
                "com.android.org.conscrypt.Platform",
                "com.android.okhttp.HttpHandler$CleartextURLFilter"
        };
        for (String className : classNames) {
            try {
                Class<?> target = Class.forName(className);
                int count = 0;
                for (final Method method : target.getDeclaredMethods()) {
                    boolean cleartextPermittedMethod = "isCleartextTrafficPermitted".equals(method.getName())
                            && method.getReturnType() == Boolean.TYPE;
                    boolean urlFilterMethod = "checkURLPermitted".equals(method.getName())
                            && method.getReturnType() == Void.TYPE;
                    if (!cleartextPermittedMethod && !urlFilterMethod) {
                        continue;
                    }
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length > 1) {
                        continue;
                    }
                    method.setAccessible(true);
                    hook(method).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (shouldAllowHeartwithCleartext(chain, method)) {
                                return method.getReturnType() == Boolean.TYPE ? true : null;
                            }
                            return chain.proceed();
                        }
                    });
                    count += 1;
                }
                if (count > 0) {
                    if (DebugBuild.ENABLED) {
                        diagLine("android okhttp cleartext hook installed: " + className + " count=" + count);
                    }
                }
            } catch (Throwable throwable) {
                if (DebugBuild.ENABLED) {
                    diagLine("android okhttp cleartext hook unavailable: " + className + ": " + throwable.getClass().getSimpleName());
                }
            }
        }
    }

    private boolean shouldAllowHeartwithCleartext(XposedInterface.Chain chain, Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 1) {
            Object arg = chain.getArg(0);
            if (arg instanceof String && isHeartwithServerHost((String) arg)) {
                if (DebugBuild.ENABLED) {
                    logCleartextAllow("allow cleartext for Heartwith host: " + arg);
                }
                return true;
            }
            if (arg instanceof URL && isHeartwithServerHost(((URL) arg).getHost())) {
                if (DebugBuild.ENABLED) {
                    logCleartextAllow("allow cleartext for Heartwith url: " + ((URL) arg).getHost());
                }
                return true;
            }
        }
        if (HeartwithCleartextScope.isActive()) {
            if (DebugBuild.ENABLED) {
                logCleartextAllow("allow cleartext for Heartwith request scope");
            }
            return true;
        }
        return false;
    }

    private void logCleartextAllow(String message) {
        if (DebugBuild.ENABLED && !cleartextPolicyAllowLogged) {
            cleartextPolicyAllowLogged = true;
            Log.i(TAG, message);
        }
    }

    private boolean isHeartwithServerHost(String host) {
        String expected = configuredHeartwithServerHost();
        return expected != null && expected.equalsIgnoreCase(host);
    }

    private String configuredHeartwithServerHost() {
        String serverUrl = HeartwithSettings.DEFAULT_SERVER_URL;
        Context context = appContext;
        if (context != null) {
            try {
                String cached = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_CACHED_SERVER_URL, serverUrl);
                if (cached != null && !cached.trim().isEmpty()) {
                    serverUrl = cached;
                }
            } catch (Throwable ignored) {
            }
        }
        if (serverUrl == null || !serverUrl.startsWith("http://")) {
            return null;
        }
        try {
            return new URL(serverUrl).getHost();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void hookXCrashNativeHandler(ClassLoader classLoader) {
        try {
            Class<?> nativeHandler = findClass("xcrash.NativeHandler", classLoader);
            for (final Method method : nativeHandler.getDeclaredMethods()) {
                if (!"d".equals(method.getName()) || method.getReturnType() != Integer.TYPE) {
                    continue;
                }
                method.setAccessible(true);
                hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) {
                        if (DebugBuild.ENABLED) {
                            diagLine("skip xcrash native handler for NPatch seccomp");
                        }
                        return 0;
                    }
                });
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("xcrash native handler hook unavailable: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void hookLifecycle(final ClassLoader classLoader) {
        hookAfter(Application.class, "attach", new Class<?>[]{Context.class}, new AfterHook() {
            @Override
            public void after(XposedInterface.Chain chain, Object result) {
                Context base = (Context) chain.getArg(0);
                Context applicationContext = base == null ? null : base.getApplicationContext();
                appContext = applicationContext == null ? base : applicationContext;
                DebugSleepLog.init(appContext, processName);
                importantLine("attach process=" + processName
                        + ", worker=" + isWorkerProcess()
                        + ", main=" + isMainProcess()
                        + ", version=" + BuildConfig.VERSION_NAME
                        + ", uptime=" + SystemClock.elapsedRealtime());
                if (DebugBuild.ENABLED) {
                    diagLine("attach process=" + processName
                            + ", app=" + describeObjectForDebug(chain.getThisObject())
                            + ", base=" + describeObjectForDebug(base)
                            + ", appContext=" + describeObjectForDebug(appContext)
                            + ", uptime=" + SystemClock.elapsedRealtime());
                    registerDebugLifecycleCallbacks(chain.getThisObject());
                }
                if (isMainProcess() && chain.getThisObject() instanceof Application) {
                    installNotificationPermissionRequest((Application) chain.getThisObject());
                }
                maybeInstallNpatchCompatibility(classLoader, chain.getThisObject());
                registerSportModeReceiver(appContext);
                registerDeviceChangeReceiver(appContext);
                registerConfigReceiver(appContext);
                warmUpUploaderConfig(appContext);
                restoreActiveSource(appContext);
                scheduleStartAfterAttach(classLoader);
                scheduleSleepStatusPoll(appContext, "attach");
            }
        });
    }

    private void registerDebugLifecycleCallbacks(Object application) {
        if (!DebugBuild.ENABLED || !(application instanceof Application) ||
                !debugLifecycleRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            ((Application) application).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    debugLifecycle("created", activity);
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    debugLifecycle("started", activity);
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    debugLifecycle("resumed", activity);
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    debugLifecycle("paused", activity);
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    debugLifecycle("stopped", activity);
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    debugLifecycle("destroyed", activity);
                }
            });
            diagLine("debug lifecycle callbacks registered process=" + processName);
        } catch (Throwable throwable) {
            diagLine("debug lifecycle callbacks failed: " + describeThrowable(throwable));
        }
    }

    private void debugLifecycle(String event, Activity activity) {
        if (!DebugBuild.ENABLED) {
            return;
        }
        diagLine("activity " + event
                + " process=" + processName
                + ", activity=" + (activity == null ? "null" : activity.getClass().getName())
                + ", uptime=" + SystemClock.elapsedRealtime()
                + ", started=" + started
                + ", lastStartReason=" + lastStartReason
                + ", lastHrAgeMs=" + heartRateAgeForDebug());
    }

    private void installNotificationPermissionRequest(Application application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || application == null) {
            notificationPermissionRequested.set(true);
            return;
        }
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                requestNotificationPermissionOnce(activity);
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private void requestNotificationPermissionOnce(Activity activity) {
        if (activity == null || notificationPermissionRequested.get()) {
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notificationPermissionRequested.set(true);
            return;
        }
        if (notificationPermissionRequested.compareAndSet(false, true)) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    HeartwithStatus.NOTIFICATION_ID);
        }
    }

    private void maybeInstallNpatchCompatibility(ClassLoader classLoader, Object application) {
        Context context = appContext;
        if (context == null || !isNpatchWrapped(context) || !npatchHooksInstalled.compareAndSet(false, true)) {
            return;
        }
        hookXCrashNativeHandler(classLoader);
        hookLocalAccountLogin(classLoader);
        hookLocalWearCore(classLoader);
        if (isMainProcess()) {
            hookNpatchArouterIndexes(classLoader);
            hookNpatchMainRouteRescue(classLoader);
        }
        if (isMainProcess() && application instanceof Application) {
            HeartwithSettingsPanel.installNpatchEntry((Application) application);
        }
    }

    private boolean isWorkerProcess() {
        return (targetPackage + ":device").equals(processName);
    }

    private boolean isMainProcess() {
        return targetPackage.equals(processName);
    }

    private boolean isSupportedPackage(String packageName) {
        return TARGET_PACKAGE.equals(packageName)
                || PATCHED_TARGET_PACKAGE.equals(packageName);
    }

    private void hookHeartRateStopControls(ClassLoader classLoader) {
        hookOriginalHuamiHeartRateController(classLoader);
        hookOriginalHuamiBleDevice(classLoader);
        hookDeviceHrStopHelpers(classLoader);
    }

    private void hookPassiveSportHeartRateSinks(final ClassLoader classLoader) {
        hookSportPacketHandler(classLoader, "com.xiaomi.fitness.sport.model.LaunchSportModel$DataHandlerImpl");
        hookSportPacketHandler(classLoader, "com.xiaomi.fitness.sport_manager.model.LaunchSportModel$DataHandlerImpl");
        hookEcoPacketHandler(classLoader, "com.xiaomi.fitness.sport_eco.model.LaunchSportModel$EcoDataHandlerImpl");
        hookEcoPacketHandler(classLoader, "com.xiaomi.fitness.sport_eco_manager.model.LaunchSportModel$EcoDataHandlerImpl");
        hookEcoPacketHandler(classLoader, "com.xiaomi.fitness.sport.model.LaunchSportModel$EcoDataHandlerImpl");
        hookEcoRawHandler(classLoader);
        hookEcoRemoteDataHandler(classLoader);
        hookSportWearDataSinks(classLoader);
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco_manager.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_manager.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_manager.state.data.HuamiDataReceive");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco_manager.state.data.HuamiDataReceive");
    }

    private void hookSyncUiSignals(ClassLoader classLoader) {
        if (!syncUiHooksInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            final Method setOnClickListener = View.class.getDeclaredMethod("setOnClickListener", View.OnClickListener.class);
            setOnClickListener.setAccessible(true);
            hook(setOnClickListener).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object view = chain.getThisObject();
                    Object listener = chain.getArg(0);
                    if (view instanceof View && listener instanceof View.OnClickListener) {
                        maybeCaptureSyncButton((View) view, (View.OnClickListener) listener, "setOnClickListener");
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("sync click hook failed: " + throwable.getClass().getSimpleName());
            }
        }
        try {
            final Method performClick = View.class.getDeclaredMethod("performClick");
            performClick.setAccessible(true);
            hook(performClick).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object view = chain.getThisObject();
                    if (view instanceof View) {
                        maybeCaptureSyncButton((View) view, null, "performClick");
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("sync performClick hook failed: " + throwable.getClass().getSimpleName());
            }
        }
        try {
            for (final Method method : Toast.class.getDeclaredMethods()) {
                if (!"makeText".equals(method.getName()) || method.getParameterTypes().length < 3) {
                    continue;
                }
                method.setAccessible(true);
                hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        Object text = chain.getArg(1);
                        if (text != null && isSyncSuccessText(String.valueOf(text))) {
                            lastSyncSuccessElapsedMs = SystemClock.elapsedRealtime();
                            if (DebugBuild.ENABLED) {
                                debugSyncLine("mihealth sync success toast captured");
                            }
                        }
                        return result;
                    }
                });
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("sync toast hook failed: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void maybeCaptureSyncButton(View view, View.OnClickListener listener, String reason) {
        if (!periodicSyncEnabled && !DebugBuild.ENABLED) {
            return;
        }
        String text = viewText(view);
        if (!isManualSyncText(text)) {
            return;
        }
        syncButtonView = new WeakReference<>(view);
        if (listener != null) {
            syncButtonListener = new WeakReference<>(listener);
        }
        if (DebugBuild.ENABLED) {
            debugSyncLine("entry captured reason=" + reason + ", text=" + text);
        }
    }

    private String viewText(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text == null ? "" : text.toString().trim();
        }
        CharSequence description = view.getContentDescription();
        return description == null ? "" : description.toString().trim();
    }

    private boolean isManualSyncText(String text) {
        if (text == null || text.isEmpty() || !text.contains("同步")) {
            return false;
        }
        return !text.contains("自动") && !text.contains("后台") && !text.contains("间隔");
    }

    private boolean isSyncSuccessText(String text) {
        return text != null && text.contains("同步") && (text.contains("成功") || text.contains("完成"));
    }

    private void schedulePeriodicSync(Context context) {
        if (context == null || !isMainProcess() || !periodicSyncEnabled) {
            return;
        }
        final int generation = ++syncScheduleGeneration;
        final long delayMs = Math.max(HeartwithSettings.MIN_SYNC_INTERVAL_HOURS, periodicSyncIntervalHours)
                * 60L * 60L * 1000L;
        scheduleSyncAlarm(context, delayMs, generation);
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (generation != syncScheduleGeneration || !periodicSyncEnabled) {
                        debugSyncLine("timer ignored generation=" + generation
                                + ", current=" + syncScheduleGeneration
                                + ", enabled=" + periodicSyncEnabled);
                        return;
                    }
                    triggerMiHealthSync(appContext, "timer", false);
                    schedulePeriodicSync(appContext);
                }
            }, delayMs);
        } catch (Throwable ignored) {
        }
        if (DebugBuild.ENABLED) {
            debugSyncLine("periodic scheduled hours=" + periodicSyncIntervalHours
                    + ", delayMs=" + delayMs
                    + ", generation=" + generation);
        }
    }

    private void cancelPeriodicSync(Context context) {
        syncScheduleGeneration++;
        debugSyncLine("periodic cancelled generation=" + syncScheduleGeneration);
        if (context == null || !isMainProcess()) {
            return;
        }
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.cancel(syncPendingIntent(context));
            }
        } catch (Throwable ignored) {
        }
    }

    private void scheduleSyncAlarm(Context context, long delayMs, int generation) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                return;
            }
            long triggerAtMs = System.currentTimeMillis() + delayMs;
            PendingIntent pendingIntent = syncPendingIntent(context, generation);
            alarmManager.cancel(pendingIntent);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setWindow(
                        AlarmManager.RTC,
                        triggerAtMs,
                        SYNC_ALARM_WINDOW_MS,
                        pendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC, triggerAtMs, pendingIntent);
            }
            debugSyncLine("alarm scheduled delayMs=" + delayMs
                    + ", windowMs=" + SYNC_ALARM_WINDOW_MS
                    + ", generation=" + generation
                    + ", triggerAtMs=" + triggerAtMs);
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                debugSyncLine("alarm schedule failed: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private PendingIntent syncPendingIntent(Context context) {
        return syncPendingIntent(context, syncScheduleGeneration);
    }

    private PendingIntent syncPendingIntent(Context context, int generation) {
        Intent intent = new Intent(HeartwithSettings.ACTION_SYNC_NOW);
        intent.setPackage(targetPackage);
        intent.putExtra(EXTRA_SYNC_GENERATION, generation);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, 0x23014332, intent, flags);
    }

    private void scheduleSleepStatusPoll(Context context, String reason) {
        if (context == null || !isWorkerProcess() || !heartRateHookEnabled) {
            return;
        }
        if (sleepStatusPollScheduled.get()) {
            debugSleepStateLine("poll-schedule-skip", reason, null, "alreadyScheduled");
            return;
        }
        final int generation = ++sleepStatusPollGeneration;
        sleepStatusPollScheduled.set(true);
        scheduleSleepStatusAlarm(context, SLEEP_STATUS_POLL_INTERVAL_MS, generation);
        if (DebugBuild.ENABLED) {
            debugSleepLine("sleep status poll scheduled reason=" + reason
                    + ", generation=" + generation
                    + ", intervalMs=" + SLEEP_STATUS_POLL_INTERVAL_MS);
        }
    }

    private void cancelSleepStatusPoll(Context context) {
        sleepStatusPollGeneration++;
        sleepStatusPollScheduled.set(false);
        if (context == null || !isWorkerProcess()) {
            return;
        }
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.cancel(sleepStatusPendingIntent(context, sleepStatusPollGeneration));
            }
        } catch (Throwable ignored) {
        }
    }

    private void scheduleSleepStatusAlarm(Context context, long delayMs, int generation) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                return;
            }
            long triggerAtMs = SystemClock.elapsedRealtime() + delayMs;
            PendingIntent pendingIntent = sleepStatusPendingIntent(context, generation);
            alarmManager.cancel(pendingIntent);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setWindow(
                        AlarmManager.ELAPSED_REALTIME,
                        triggerAtMs,
                        SLEEP_STATUS_POLL_WINDOW_MS,
                        pendingIntent);
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME, triggerAtMs, pendingIntent);
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                debugSleepLine("sleep status alarm failed: " + describeThrowable(throwable));
            }
        }
    }

    private PendingIntent sleepStatusPendingIntent(Context context, int generation) {
        Intent intent = new Intent(ACTION_SLEEP_STATUS_POLL);
        intent.setPackage(targetPackage);
        intent.putExtra(EXTRA_SLEEP_STATUS_GENERATION, generation);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, 0x23014337, intent, flags);
    }

    private void handleSleepStatusPoll(Context context, String reason) {
        sleepStatusPollScheduled.set(false);
        refreshRuntimeSettingsIfNeeded(context, false);
        if (!heartRateHookEnabled || !isWorkerProcess()) {
            debugSleepStateLine("poll-cancel", reason, null,
                    "hookEnabled=" + heartRateHookEnabled + ", worker=" + isWorkerProcess());
            cancelSleepStatusPoll(context);
            return;
        }
        debugSleepStateLine("poll-fired", reason, null, "fetch-start");
        triggerSleepStatusFetch(context, reason);
        scheduleSleepStatusPoll(context, reason + ":next");
    }

    private void triggerSleepStatusFetch(Context context, String reason) {
        if (context == null || !isWorkerProcess()) {
            debugSleepStateLine("fetch-skip", reason, null,
                    "context=" + (context != null) + ", worker=" + isWorkerProcess());
            return;
        }
        ClassLoader classLoader = targetClassLoader;
        if (classLoader == null) {
            debugSleepStateLine("fetch-skip", reason, null, "classLoader=null");
            return;
        }
        Object device = getCurrentDeviceModel(classLoader);
        if (device == null) {
            debugSleepStateLine("fetch-skip", reason, null, "device=null");
            return;
        }
        updateDeviceModel(device);
        String did = getCurrentDeviceId(device);
        if (did == null || did.length() == 0) {
            debugSleepStateLine("fetch-skip", reason, null, "did=null, device=" + shortObject(device));
            return;
        }
        debugSleepStateLine("fetch-raw-ids", reason, null,
                "did=" + maskDid(did) + ", device=" + describeDirectSyncDevice(device));
        lastSleepStatusFetchElapsedMs = SystemClock.elapsedRealtime();
        requestTodaySleepIds(classLoader, did, reason);
    }

    private void maybeFetchSleepStatusAfterHeartRate(final Context context,
                                                     long elapsedMs,
                                                     final String source) {
        if (context == null || !isWorkerProcess()) {
            return;
        }
        long last = lastSleepStatusFetchElapsedMs;
        if (last > 0 && elapsedMs - last < SLEEP_STATUS_POLL_INTERVAL_MS) {
            return;
        }
        lastSleepStatusFetchElapsedMs = elapsedMs;
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                debugSleepStateLine("piggyback-fired", "heart-rate:" + source, null, "fetch-start");
                triggerSleepStatusFetch(context, "heart-rate:" + source);
            }
        });
    }

    private void triggerMiHealthSync(Context context, String reason, boolean manual) {
        if (context == null || !isMainProcess()) {
            return;
        }
        if (!manual && !periodicSyncEnabled) {
            debugSyncLine("trigger ignored disabled reason=" + reason + ", manual=" + manual);
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        long minGap = manual ? SYNC_MANUAL_MIN_TRIGGER_GAP_MS : SYNC_MIN_TRIGGER_GAP_MS;
        if (lastSyncTriggerElapsedMs > 0L && elapsed - lastSyncTriggerElapsedMs < minGap) {
            if (DebugBuild.ENABLED) {
                debugSyncLine("trigger skipped reason=" + reason + ", gapMs=" + (elapsed - lastSyncTriggerElapsedMs));
            }
            return;
        }
        lastSyncTriggerElapsedMs = elapsed;
        debugSyncLine("trigger start reason=" + reason + ", manual=" + manual);
        ClassLoader classLoader = targetClassLoader;
        if (classLoader != null && triggerDirectDeviceSync(classLoader, reason, manual)) {
            return;
        }
        final View view = syncButtonView.get();
        final View.OnClickListener listener = syncButtonListener.get();
        if (view == null && listener == null) {
            if (DebugBuild.ENABLED) {
                debugSyncLine("trigger pending: open Xiaomi Health sync page once to capture entry");
            }
            return;
        }
        try {
            new Handler(context.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (view != null && view.isAttachedToWindow()) {
                            view.performClick();
                        } else if (listener != null && view != null) {
                            listener.onClick(view);
                        } else {
                            if (DebugBuild.ENABLED) {
                                debugSyncLine("trigger failed: captured sync view expired");
                            }
                        }
                    } catch (Throwable throwable) {
                        if (DebugBuild.ENABLED) {
                            debugSyncLine("trigger failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                        }
                    }
                }
            });
            if (DebugBuild.ENABLED) {
                debugSyncLine("trigger posted reason=" + reason + ", manual=" + manual);
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                debugSyncLine("trigger post failed: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private boolean triggerDirectDeviceSync(ClassLoader classLoader, String reason, boolean manual) {
        Object device = getCurrentDeviceModel(classLoader);
        if (device == null) {
            if (DebugBuild.ENABLED) {
                debugSyncLine("direct skipped: current device is null");
            }
            return false;
        }
        updateDeviceModel(device);
        String did = getCurrentDeviceId(device);
        if (did == null) {
            if (DebugBuild.ENABLED) {
                debugSyncLine("direct skipped: current device did is null");
            }
            return false;
        }
        if (triggerWearableDeviceSync(classLoader, did, reason, manual)) {
            return true;
        }
        return triggerEcoDeviceSync(classLoader, did, reason, manual);
    }

    private void triggerDebugSleepFetch(Context context, String reason) {
        if (!DebugBuild.ENABLED) {
            return;
        }
        if (context == null) {
            debugSleepLine("debug sleep ignored: process=" + processName + ", reason=" + reason);
            return;
        }
        ClassLoader classLoader = targetClassLoader;
        if (classLoader == null) {
            writeDebugSleepStatus("无法获取睡眠数据", "小米健康 ClassLoader 尚未就绪。");
            debugSleepLine("debug sleep skipped: classLoader null");
            return;
        }
        Object device = getCurrentDeviceModel(classLoader);
        if (device == null) {
            writeDebugSleepStatus("无法获取睡眠数据", "当前设备为空，请确认小米健康已连接手环/手表。");
            debugSleepLine("debug sleep skipped: current device null");
            return;
        }
        updateDeviceModel(device);
        String did = getCurrentDeviceId(device);
        if (did == null || did.length() == 0) {
            writeDebugSleepStatus("无法获取睡眠数据", "当前设备 did 为空。");
            debugSleepLine("debug sleep skipped: did null");
            return;
        }
        debugSleepLine("debug sleep fetch start process=" + processName
                + ", reason=" + reason
                + ", did=" + maskDid(did)
                + ", device=" + describeDeviceModel(device));
        writeDebugSleepStatus("正在获取睡眠数据", "设备：" + describeDeviceModel(device)
                + "\n正在读取本地仓库，并同时尝试 wearable / eco / syncer 直连同步入口。");
        requestDebugSleepSnapshot(classLoader, did, "before-sync:" + reason);
        triggerDebugFitnessSyncerSync(classLoader, did, "settings:" + reason);
        String syncReason = "debug-sleep:" + reason;
        boolean wearable = triggerWearableDeviceSync(classLoader, did, syncReason + ":wearable", true);
        boolean eco = triggerEcoDeviceSync(classLoader, did, syncReason + ":eco", true);
        debugSleepLine("debug sleep sync triggers reason=" + reason
                + ", wearable=" + wearable
                + ", eco=" + eco);
        scheduleDebugSleepFollowUpSnapshots(context, classLoader, did, reason);
        if (!wearable && !eco) {
            writeDebugSleepStatus("正在获取睡眠数据", "设备：" + describeDeviceModel(device)
                    + "\n直接同步入口不可用，已保留 today/history ids 与本地仓库快照。");
        }
    }

    private void requestDebugSleepSnapshot(ClassLoader classLoader, String did, String reason) {
        requestDebugSleepRepositoryReports(classLoader, did, reason);
        requestDebugLocalFdsSleepIds(classLoader, did, reason);
        requestDebugTodaySleepIds(classLoader, did, "debug-sleep-today:" + reason);
        requestDebugHistorySleepIds(classLoader, did, "debug-sleep-history:" + reason);
    }

    private void scheduleDebugSleepFollowUpSnapshots(final Context context,
                                                     final ClassLoader classLoader,
                                                     final String did,
                                                     final String reason) {
        if (!DebugBuild.ENABLED || context == null || classLoader == null || did == null || did.length() == 0) {
            return;
        }
        final long[] delays = new long[]{60_000L, 3L * 60L * 1000L, 10L * 60L * 1000L};
        final Handler handler = new Handler(context.getMainLooper());
        for (final long delay : delays) {
            try {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        debugSleepLine("debug sleep follow-up snapshot delayMs=" + delay
                                + ", reason=" + reason);
                        requestDebugSleepSnapshot(classLoader, did, "after-sync+" + delay + "ms:" + reason);
                    }
                }, delay);
            } catch (Throwable throwable) {
                debugSleepLine("debug sleep follow-up schedule failed delayMs=" + delay
                        + ": " + describeThrowable(throwable));
            }
        }
    }

    private void setDebugSleepProbe(Context context, boolean enabled, String reason) {
        if (!DebugBuild.ENABLED) {
            return;
        }
        debugSleepProbeEnabled = enabled;
        debugSleepProbeGeneration++;
        if (!enabled) {
            cancelDebugSleepProbeAlarm(context);
            debugSleepLine("sleep probe stopped reason=" + reason
                    + ", generation=" + debugSleepProbeGeneration);
            return;
        }
        debugSleepLine("sleep probe started reason=" + reason
                + ", intervalMs=" + DEBUG_SLEEP_PROBE_INTERVAL_MS
                + ", generation=" + debugSleepProbeGeneration);
        triggerDebugSleepFetch(context, "probe-start:" + reason);
        scheduleDebugSleepProbeAlarm(context, DEBUG_SLEEP_PROBE_INTERVAL_MS, debugSleepProbeGeneration);
    }

    private void handleDebugSleepProbeAlarm(Context context, Intent intent) {
        if (!DebugBuild.ENABLED) {
            return;
        }
        int generation = intent == null
                ? debugSleepProbeGeneration
                : intent.getIntExtra(EXTRA_SYNC_GENERATION, debugSleepProbeGeneration);
        if (!debugSleepProbeEnabled || generation != debugSleepProbeGeneration) {
            debugSleepLine("sleep probe alarm ignored generation=" + generation
                    + ", current=" + debugSleepProbeGeneration
                    + ", enabled=" + debugSleepProbeEnabled);
            return;
        }
        debugSleepLine("sleep probe alarm fired generation=" + generation);
        triggerDebugSleepFetch(context, "probe-alarm");
        scheduleDebugSleepProbeAlarm(context, DEBUG_SLEEP_PROBE_INTERVAL_MS, generation);
    }

    private void scheduleDebugSleepProbeAlarm(Context context, long delayMs, int generation) {
        if (!DebugBuild.ENABLED || context == null || !isMainProcess()) {
            return;
        }
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                return;
            }
            PendingIntent pendingIntent = debugSleepProbePendingIntent(context, generation);
            alarmManager.cancel(pendingIntent);
            long triggerAtMs = System.currentTimeMillis() + delayMs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setWindow(
                        AlarmManager.RTC,
                        triggerAtMs,
                        DEBUG_SLEEP_PROBE_WINDOW_MS,
                        pendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC, triggerAtMs, pendingIntent);
            }
            debugSleepLine("sleep probe alarm scheduled delayMs=" + delayMs
                    + ", windowMs=" + DEBUG_SLEEP_PROBE_WINDOW_MS
                    + ", generation=" + generation
                    + ", triggerAtMs=" + triggerAtMs);
        } catch (Throwable throwable) {
            debugSleepLine("sleep probe alarm schedule failed: " + describeThrowable(throwable));
        }
    }

    private void cancelDebugSleepProbeAlarm(Context context) {
        if (!DebugBuild.ENABLED || context == null || !isMainProcess()) {
            return;
        }
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.cancel(debugSleepProbePendingIntent(context, debugSleepProbeGeneration));
            }
        } catch (Throwable throwable) {
            debugSleepLine("sleep probe alarm cancel failed: " + describeThrowable(throwable));
        }
    }

    private PendingIntent debugSleepProbePendingIntent(Context context, int generation) {
        Intent intent = new Intent(HeartwithSettings.ACTION_DEBUG_SLEEP_PROBE);
        intent.setPackage(targetPackage);
        intent.putExtra(HeartwithSettings.EXTRA_DEBUG_SLEEP_PROBE_ENABLED, true);
        intent.putExtra(EXTRA_SYNC_GENERATION, generation);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, 0x23014337, intent, flags);
    }

    private void triggerDebugFitnessSyncerSync(ClassLoader classLoader, String did, String reason) {
        if (!DebugBuild.ENABLED) {
            return;
        }
        if (!isMainProcess()) {
            debugSleepLine("app-level sync skipped: process=" + processName + ", reason=" + reason);
            return;
        }
        try {
            Class<?> syncerClass = findClass("com.xiaomi.fit.fitness.sync.export.api.FitnessSyncer", classLoader);
            Object companion = getStaticObjectField(syncerClass, "INSTANCE", "Companion");
            Class<?> extClass = findClass("com.xiaomi.fit.fitness.sync.export.di.FitnessSyncExtKt", classLoader);
            Object syncer = callStaticMethod(extClass, "getInstance", companion);
            debugSleepLine("app-level sync request triggerDataSync(true), reason=" + reason
                    + ", syncer=" + describeObjectForDebug(syncer));
            callMethod(syncer, "triggerDataSync", Boolean.TRUE);
            if (did != null && did.length() > 0) {
                debugSleepLine("app-level sync request syncDataAuto(did), reason=" + reason
                        + ", did=" + maskDid(did));
                callMethod(syncer, "syncDataAuto", did, null);
            }
            debugSleepLine("app-level sync request testDeviceSync(true), reason=" + reason);
            callMethod(syncer, "testDeviceSync", Boolean.TRUE);
        } catch (Throwable throwable) {
            debugSleepLine("app-level sync failed: " + describeThrowable(throwable)
                    + ", reason=" + reason);
        }
    }

    private boolean triggerWearableDeviceSync(ClassLoader classLoader, String did, String reason, boolean manual) {
        try {
            Class<?> contactClass = findClass("com.xiaomi.fitness.device.contact.export.DeviceContact", classLoader);
            Object companion = getKotlinCompanion(contactClass, "com.xiaomi.fitness.device.contact.export.DeviceContact$Companion", classLoader);
            Class<?> extClass = findClass("com.xiaomi.fitness.device.contact.export.DeviceSyncExtKt", classLoader);
            Object contact = callStaticMethod(extClass, "getInstance", companion);
            callMethod(contact, "syncDataByWidget", did, Boolean.FALSE);
            if (DebugBuild.ENABLED) {
                debugSyncLine("direct requested: wearable did=" + maskDid(did) + ", reason=" + reason + ", manual=" + manual);
                requestDebugTodaySleepIds(classLoader, did, "wearable:" + reason);
            }
            return true;
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                debugSyncLine("direct wearable unavailable: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
            return false;
        }
    }

    private boolean triggerEcoDeviceSync(ClassLoader classLoader, String did, String reason, boolean manual) {
        try {
            Class<?> contactClass = findClass("com.xiaomi.fitness.eco.device.contact.export.EcoDeviceContact", classLoader);
            Object companion = getKotlinCompanion(contactClass, "com.xiaomi.fitness.eco.device.contact.export.EcoDeviceContact$Companion", classLoader);
            Class<?> extClass = findClass("com.xiaomi.fitness.eco.device.contact.export.EcoDeviceSyncExtKt", classLoader);
            Object contact = callStaticMethod(extClass, "getInstance", companion);
            callMethod(contact, "syncData", did, Boolean.FALSE);
            if (DebugBuild.ENABLED) {
                debugSyncLine("direct requested: eco did=" + maskDid(did) + ", reason=" + reason + ", manual=" + manual);
                requestDebugTodaySleepIds(classLoader, did, "eco:" + reason);
            }
            return true;
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                debugSyncLine("direct eco unavailable: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
            return false;
        }
    }

    private String maskDid(String did) {
        if (did == null || did.length() <= 6) {
            return "***";
        }
        return did.substring(0, 3) + "***" + did.substring(did.length() - 3);
    }

    private Object getKotlinCompanion(Class<?> ownerClass, String companionClassName, ClassLoader classLoader) throws Exception {
        try {
            return getStaticObjectField(ownerClass, "Companion", "INSTANCE");
        } catch (NoSuchFieldException ignored) {
            Class<?> companionClass = findClass(companionClassName, classLoader);
            return getStaticObjectField(companionClass, "$$INSTANCE", "INSTANCE");
        }
    }

    private void scheduleStartAfterAttach(final ClassLoader classLoader) {
        final Context context = appContext;
        if (context == null) {
            if (DebugBuild.ENABLED) {
                diagLine("scheduleStartAfterAttach skipped: context is null");
            }
            return;
        }
        if (!heartRateHookEnabled) {
            if (DebugBuild.ENABLED) {
                diagLine("scheduleStartAfterAttach skipped: hook disabled process=" + processName);
            }
            return;
        }
        if (isMainProcess()) {
            if (DebugBuild.ENABLED) {
                diagLine("scheduleStartAfterAttach main process: schedule legacy kick");
            }
            scheduleLegacyKickCheck(classLoader, 6_000L);
            return;
        }
        if (!isWorkerProcess()) {
            if (DebugBuild.ENABLED) {
                diagLine("scheduleStartAfterAttach skipped: not worker process=" + processName);
            }
            return;
        }
        if (!heartRateHookEnabled) {
            return;
        }
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    ensureRealtimeHrStarted(classLoader, "application:attach");
                }
            }, 2_000L);
            if (DebugBuild.ENABLED) {
                diagLine("scheduleStartAfterAttach worker process: start after 2000ms");
            }
        } catch (Throwable ignored) {
            if (DebugBuild.ENABLED) {
                diagLine("scheduleStartAfterAttach failed: " + describeThrowable(ignored));
            }
        }
    }

    private void scheduleLegacyKickCheck(final ClassLoader classLoader, long delayMs) {
        final Context context = appContext;
        if (context == null || !isMainProcess()) {
            return;
        }
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    legacyKickChecks++;
                    boolean hasRecentHeartRate = hasRecentHeartRateInAnyProcess();
                    if (!hasRecentHeartRate && hasPendingLegacyKickRequest()) {
                        if (!legacyKickAttemptLogged) {
                            legacyKickAttemptLogged = true;
                            if (DebugBuild.ENABLED) {
                                diagLine("legacy kick start requested from main process");
                            }
                        }
                        ensureRealtimeHrStarted(classLoader, "legacy-kick:no-heart-rate");
                    }
                    if (!hasRecentHeartRate && legacyKickChecks < 3) {
                        scheduleLegacyKickCheck(classLoader, 9_000L);
                    }
                }
            }, delayMs);
        } catch (Throwable ignored) {
        }
    }

    private void hookNpatchMainRouteRescue(final ClassLoader classLoader) {
        try {
            Class<?> mainExtClass = findClass("com.xiaomi.fitness.main.export.MainExtKt", classLoader);
            for (final Method method : mainExtClass.getDeclaredMethods()) {
                String name = method.getName();
                if (!"showMainActivity".equals(name) && !"showMainActivity$default".equals(name)) {
                    continue;
                }
                method.setAccessible(true);
                hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Context context = findContextArg(chain);
                        if (context == null) {
                            context = appContext;
                        }
                        if (context != null && VERBOSE_LOGS && !npatchRouteDiagLogged) {
                            npatchRouteDiagLogged = true;
                            diagLine("npatch route check context=" + context.getClass().getName()
                                    + ", source=" + safeSourceDir(context)
                                    + ", packageCode=" + safePackageCodePath(context)
                                    + ", wrapped=" + isNpatchWrapped(context));
                        }
                        if (context != null && (npatchWrappedDetected || isNpatchWrapped(context))) {
                            installArouterIndexes(classLoader);
                            if (launchMainActivity(context)) {
                                if (DebugBuild.ENABLED) {
                                    diagLine("npatch route rescue: " + method.getName());
                                }
                                return null;
                            }
                        }
                        return chain.proceed();
                    }
                });
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("npatch route rescue unavailable: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void hookLocalAccountLogin(final ClassLoader classLoader) {
        hookAccountManagerLocalMode(classLoader);
        hookOauthWebFallback(classLoader);
    }

    private void hookLocalWearCore(ClassLoader classLoader) {
        try {
            Class<?> coreExt = findClass("com.xiaomi.wearable.core.CoreExtKt", classLoader);
            hookBooleanNoArg(coreExt, "useLyra", false);
            hookBooleanNoArg(coreExt, "getSupportLyra", false);
            hookBooleanNoArg(coreExt, "getHasLyra", false);
            hookBooleanNoArg(coreExt, "getLyraConnection", false);
            hookBooleanNoArg(coreExt, "isLyraEnabled", false);
            if (DebugBuild.ENABLED) {
                diagLine("local wear core hooks installed");
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("local wear core hook unavailable: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void hookAccountManagerLocalMode(ClassLoader classLoader) {
        try {
            Class<?> accountManager = findClass("com.xiaomi.fitness.account.manager.AccountManagerImpl", classLoader);
            hookBooleanNoArg(accountManager, "isLocal", true);
            hookBooleanNoArg(accountManager, "isUseLocal", true);
            hookBooleanNoArg(accountManager, "isUseSystem", false);
            hookAccountVisibilityDecision(accountManager);
            hookMiAccountInternalLocalMode(classLoader);
            if (DebugBuild.ENABLED) {
                diagLine("local account mode hooks installed");
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("local account mode hook unavailable: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void hookMiAccountInternalLocalMode(ClassLoader classLoader) {
        try {
            Class<?> internalManager = findClass("com.xiaomi.fitness.account.manager.MiAccountInternalManager", classLoader);
            hookBooleanNoArg(internalManager, "isUseLocal", true);
            hookBooleanNoArg(internalManager, "isUseSystem", false);
            hookSetUserSystemToLocal(internalManager);
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("mi account local mode hook unavailable: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void hookSetUserSystemToLocal(final Class<?> internalManager) {
        try {
            final Method setUserSystem = internalManager.getDeclaredMethod("setUserSystem");
            final Method setUserLocal = internalManager.getDeclaredMethod("setUserLocal");
            setUserSystem.setAccessible(true);
            setUserLocal.setAccessible(true);
            hook(setUserSystem).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object receiver = chain.getThisObject();
                    if (receiver != null) {
                        setUserLocal.invoke(receiver);
                    }
                    return null;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookBooleanNoArg(Class<?> target, String methodName, final boolean value) {
        try {
            Method method = target.getDeclaredMethod(methodName);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return value;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookAccountVisibilityDecision(Class<?> accountManager) {
        for (final Method method : accountManager.getDeclaredMethods()) {
            if (!"doSystemAccount".equals(method.getName())) {
                continue;
            }
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) {
                    return 4;
                }
            });
        }
    }

    private void hookOauthWebFallback(final ClassLoader classLoader) {
        try {
            Class<?> factory = findClass("com.xiaomi.account.auth.OAuthFactory", classLoader);
            for (final Method method : factory.getDeclaredMethods()) {
                if (!"createOAuth".equals(method.getName())) {
                    continue;
                }
                method.setAccessible(true);
                hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        forceOauthConfigWeb(chain.getArg(0));
                        return chain.proceed();
                    }
                });
            }
            hookOauthServiceManager(classLoader);
            if (DebugBuild.ENABLED) {
                diagLine("web oauth fallback hooks installed");
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("web oauth fallback hook unavailable: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void hookOauthServiceManager(ClassLoader classLoader) {
        try {
            Class<?> manager = findClass("com.xiaomi.account.auth.OAuthServiceManager", classLoader);
            for (final Method method : manager.getDeclaredMethods()) {
                String name = method.getName();
                if (!"blockGetDefaultIntent".equals(name) && !"hasOAuthService".equals(name)) {
                    continue;
                }
                method.setAccessible(true);
                hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) {
                        return method.getReturnType() == Boolean.TYPE ? false : null;
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private void forceOauthConfigWeb(Object config) {
        if (config == null) {
            return;
        }
        setBooleanField(config, "notUseMiui", true);
        setBooleanObjectField(config, "useSystemBrowserLogin", false);
    }

    private void hookNpatchArouterIndexes(final ClassLoader classLoader) {
        try {
            Class<?> logisticsCenter = findFirstClass(classLoader,
                    "com.alibaba.android.arouter.core.LogisticsCenter",
                    "wpf");
            for (final Method method : logisticsCenter.getDeclaredMethods()) {
                if (!isArouterInitMethod(method)) {
                    continue;
                }
                method.setAccessible(true);
                hook(method).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        Context context = findContextArg(chain);
                        if (context == null) {
                            context = appContext;
                        }
                        if (context != null && (npatchWrappedDetected || isNpatchWrapped(context))) {
                            installArouterIndexes(classLoader);
                        }
                        return result;
                    }
                });
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("npatch arouter index hook unavailable: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void installArouterIndexes(ClassLoader classLoader) {
        if (npatchArouterIndexesInstalled) {
            return;
        }
        try {
            Class<?> warehouse = findFirstClass(classLoader,
                    "com.alibaba.android.arouter.core.Warehouse",
                    "jts");
            Object groupsIndex = getStaticObjectField(warehouse, "groupsIndex", "a");
            Object providersIndex = getStaticObjectField(warehouse, "providersIndex", "d");
            loadArouterIndexes(classLoader, AROUTER_ROOTS, groupsIndex);
            loadArouterIndexes(classLoader, AROUTER_PROVIDERS, providersIndex);
            npatchArouterIndexesInstalled = true;
            if (DebugBuild.ENABLED) {
                diagLine("npatch arouter indexes installed");
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("npatch arouter index install failed: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private boolean isArouterInitMethod(Method method) {
        String name = method.getName();
        if (!"init".equals(name) && !"c".equals(name)) {
            return false;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length >= 1 && Context.class.isAssignableFrom(parameterTypes[0]);
    }

    private void loadArouterIndexes(ClassLoader classLoader, String[] classNames, Object targetMap)
            throws Exception {
        if (!(targetMap instanceof java.util.Map)) {
            return;
        }
        for (String className : classNames) {
            Class<?> routeClass = findClass(className, classLoader);
            Object routeIndex = newInstance(routeClass);
            Method loadInto = routeClass.getDeclaredMethod("loadInto", java.util.Map.class);
            loadInto.setAccessible(true);
            loadInto.invoke(routeIndex, targetMap);
        }
    }

    private Context findContextArg(XposedInterface.Chain chain) {
        for (int i = 0; i < 8; i++) {
            try {
                Object arg = chain.getArg(i);
                if (arg instanceof Context) {
                    return (Context) arg;
                }
            } catch (Throwable ignored) {
                break;
            }
        }
        return null;
    }

    private boolean launchMainActivity(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(targetPackage, "com.xiaomi.fitness.main.MainActivity");
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).finish();
            }
            return true;
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                logLine("npatch route rescue failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
            return false;
        }
    }

    private boolean isNpatchWrapped(Context context) {
        if (findNpatchWrapperApk(context) != null || hasNpatchManifestMetadata(context) || hasNpatchRuntimeStack()) {
            npatchWrappedDetected = true;
            return true;
        }
        return false;
    }

    private String safeSourceDir(Context context) {
        try {
            android.content.pm.ApplicationInfo info = context.getApplicationInfo();
            return info == null ? null : info.sourceDir;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String safePackageCodePath(Context context) {
        try {
            return context.getPackageCodePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String findNpatchWrapperApk(Context context) {
        if (context == null) {
            return null;
        }
        String[] candidates = new String[4];
        try {
            android.content.pm.ApplicationInfo info = context.getApplicationInfo();
            if (info != null) {
                candidates[0] = info.sourceDir;
                candidates[1] = info.publicSourceDir;
            }
        } catch (Throwable ignored) {
        }
        try {
            candidates[2] = context.getPackageCodePath();
        } catch (Throwable ignored) {
        }
        try {
            android.content.pm.ApplicationInfo packageInfo =
                    context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);
            candidates[3] = packageInfo == null ? null : packageInfo.sourceDir;
        } catch (Throwable ignored) {
        }
        for (String candidate : candidates) {
            if (candidate != null && apkContainsNpatchOrigin(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean apkContainsNpatchOrigin(String path) {
        try {
            ZipFile zipFile = new ZipFile(path);
            try {
                return zipFile.getEntry(NPATCH_ORIGIN_ASSET) != null;
            } finally {
                zipFile.close();
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasNpatchManifestMetadata(Context context) {
        try {
            android.content.pm.ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(),
                    android.content.pm.PackageManager.GET_META_DATA);
            return info != null && info.metaData != null && info.metaData.containsKey("npatch");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasNpatchRuntimeStack() {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (StackTraceElement element : stackTrace) {
                String name = element.getClassName();
                if (name.startsWith("org.matrix.vector.") || name.startsWith("top.nkbe.npatch.")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void warmUpUploaderConfig(final Context context) {
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    uploader.warmUp(context);
                    applyRuntimeSettings(
                            context,
                            uploader.currentSettings(),
                            "settings warmup cache");
                } catch (Throwable throwable) {
                    if (DebugBuild.ENABLED) {
                        diagLine("warmup crashed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    }
                }
            }
        });
    }

    private void registerConfigReceiver(final Context context) {
        if (context == null || !configReceiverRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    if (HeartwithSettings.ACTION_SYNC_NOW.equals(intent.getAction())) {
                        final boolean manual = intent.getBooleanExtra(HeartwithSettings.EXTRA_SYNC_MANUAL, false);
                        if (!manual && intent.getIntExtra(EXTRA_SYNC_GENERATION, syncScheduleGeneration) != syncScheduleGeneration) {
                            debugSyncLine("alarm ignored stale generation intent="
                                    + intent.getIntExtra(EXTRA_SYNC_GENERATION, -1)
                                    + ", current=" + syncScheduleGeneration);
                            return;
                        }
                        debugSyncLine("broadcast received manual=" + manual
                                + ", generation=" + intent.getIntExtra(EXTRA_SYNC_GENERATION, -1));
                        triggerMiHealthSync(context, manual ? "manual" : "alarm", manual);
                        if (!manual) {
                            schedulePeriodicSync(context);
                        }
                        return;
                    }
                    if (HeartwithSettings.ACTION_DEBUG_SLEEP_NOW.equals(intent.getAction())) {
                        debugSleepLine("debug sleep broadcast received");
                        triggerDebugSleepFetch(context, "settings");
                        return;
                    }
                    if (HeartwithSettings.ACTION_DEBUG_SLEEP_PROBE.equals(intent.getAction())) {
                        if (!DebugBuild.ENABLED) {
                            return;
                        }
                        if (intent.hasExtra(EXTRA_SYNC_GENERATION)) {
                            handleDebugSleepProbeAlarm(context, intent);
                        } else {
                            boolean enabled = intent.getBooleanExtra(
                                    HeartwithSettings.EXTRA_DEBUG_SLEEP_PROBE_ENABLED,
                                    false);
                            setDebugSleepProbe(context, enabled, "settings");
                        }
                        return;
                    }
                    if (ACTION_HEART_RATE_WATCHDOG.equals(intent.getAction())) {
                        if (!isWorkerProcess()) {
                            return;
                        }
                        int generation = intent.getIntExtra(
                                EXTRA_HEART_RATE_WATCHDOG_GENERATION,
                                heartRateWatchdogGeneration);
                        if (generation != heartRateWatchdogGeneration) {
                            return;
                        }
                        handleHeartRateAlarmWatchdog();
                        return;
                    }
                    if (ACTION_SLEEP_STATUS_POLL.equals(intent.getAction())) {
                        if (!isWorkerProcess()) {
                            return;
                        }
                        int generation = intent.getIntExtra(
                                EXTRA_SLEEP_STATUS_GENERATION,
                                sleepStatusPollGeneration);
                        if (generation != sleepStatusPollGeneration) {
                            return;
                        }
                        handleSleepStatusPoll(context, "alarm");
                        return;
                    }
                    if (!HeartwithSettings.ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    final Context runtimeContext = context;
                    final boolean enabled = intent.hasExtra(HeartwithSettings.EXTRA_HOOK_ENABLED)
                            ? intent.getBooleanExtra(HeartwithSettings.EXTRA_HOOK_ENABLED, false)
                            : intent.getBooleanExtra(HeartwithSettings.EXTRA_ENABLED, false);
                    final boolean syncEnabled = intent.getBooleanExtra(HeartwithSettings.EXTRA_SYNC_ENABLED, false);
                    final int syncIntervalHours = intent.getIntExtra(
                            HeartwithSettings.EXTRA_SYNC_INTERVAL_HOURS,
                            HeartwithSettings.DEFAULT_SYNC_INTERVAL_HOURS);
                    final String serverUrl = intent.getStringExtra(HeartwithSettings.EXTRA_SERVER_URL);
                    final String displayName = intent.getStringExtra(HeartwithSettings.EXTRA_DISPLAY_NAME);
                    WORKER.execute(new Runnable() {
                        @Override
                        public void run() {
                            applyRuntimeSettings(
                                    runtimeContext,
                                    new HeartwithSettings(enabled, serverUrl, displayName, syncEnabled, syncIntervalHours),
                                    "settings broadcast synced");
                        }
                    });
                }
            };
            IntentFilter filter = new IntentFilter(HeartwithSettings.ACTION_CONFIG_CHANGED);
            filter.addAction(HeartwithSettings.ACTION_SYNC_NOW);
            filter.addAction(HeartwithSettings.ACTION_DEBUG_SLEEP_NOW);
            filter.addAction(HeartwithSettings.ACTION_DEBUG_SLEEP_PROBE);
            filter.addAction(ACTION_HEART_RATE_WATCHDOG);
            filter.addAction(ACTION_SLEEP_STATUS_POLL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            if (DebugBuild.ENABLED) {
                diagLine("config receiver registered process=" + processName);
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("config receiver failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }
    }

    private void applyRuntimeSettings(Context context, HeartwithSettings settings, String reason) {
        if (settings == null) {
            return;
        }
        lastRuntimeSettingsRefreshElapsedMs = SystemClock.elapsedRealtime();
        boolean wasHeartRateHookEnabled = heartRateHookEnabled;
        boolean wasPeriodicSyncEnabled = periodicSyncEnabled;
        int oldPeriodicSyncIntervalHours = periodicSyncIntervalHours;
        heartRateHookEnabled = settings.hookEnabled;
        periodicSyncEnabled = settings.syncEnabled;
        periodicSyncIntervalHours = settings.syncIntervalHours;
        uploader.applySettings(context, settings, reason);
        if (wasHeartRateHookEnabled != heartRateHookEnabled
                || wasPeriodicSyncEnabled != periodicSyncEnabled
                || oldPeriodicSyncIntervalHours != periodicSyncIntervalHours
                || (reason != null && (reason.contains("warmup") || reason.contains("cache")
                || reason.contains("settings broadcast")))) {
            importantLine("settings applied reason=" + reason
                    + ", process=" + processName
                    + ", hook=" + heartRateHookEnabled
                    + ", wasHook=" + wasHeartRateHookEnabled
                    + ", sync=" + periodicSyncEnabled
                    + ", intervalHours=" + periodicSyncIntervalHours
                    + ", worker=" + isWorkerProcess()
                    + ", main=" + isMainProcess());
        }
        if (DebugBuild.ENABLED) {
            diagLine("runtime settings applied reason=" + reason
                    + ", process=" + processName
                    + ", hook=" + heartRateHookEnabled
                    + ", wasHook=" + wasHeartRateHookEnabled
                    + ", sync=" + periodicSyncEnabled
                    + ", intervalHours=" + periodicSyncIntervalHours
                    + ", targetClassLoader=" + (targetClassLoader != null)
                    + ", uptime=" + SystemClock.elapsedRealtime());
            debugSyncLine("settings applied reason=" + reason
                    + ", process=" + processName
                    + ", sync=" + periodicSyncEnabled
                    + ", intervalHours=" + periodicSyncIntervalHours
                    + ", main=" + isMainProcess());
        }
        if (isMainProcess() &&
                (wasPeriodicSyncEnabled != periodicSyncEnabled ||
                        oldPeriodicSyncIntervalHours != periodicSyncIntervalHours)) {
            if (periodicSyncEnabled) {
                schedulePeriodicSync(context);
            } else {
                cancelPeriodicSync(context);
            }
        }
        if (settings.hookEnabled && !wasHeartRateHookEnabled && isWorkerProcess() && targetClassLoader != null) {
            scheduleStartAfterAttach(targetClassLoader);
        } else if (!settings.hookEnabled) {
            started = false;
            noHeartStartAttempts = 0;
            cancelHeartRateAlarmWatchdog(context);
        }
        if (settings.hookEnabled) {
            scheduleSleepStatusPoll(context, reason);
        } else {
            cancelSleepStatusPoll(context);
        }
    }

    private void refreshRuntimeSettingsIfNeeded(Context context, boolean force) {
        if (context == null) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        if (!force && lastRuntimeSettingsRefreshElapsedMs > 0L &&
                elapsed - lastRuntimeSettingsRefreshElapsedMs < 30_000L) {
            return;
        }
        applyRuntimeSettings(context, uploader.currentSettings(), "runtime settings refresh");
    }

    private void registerSportModeReceiver(final Context context) {
        if (context == null || !sportModeReceiverRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null || !ACTION_SPORT_MODE_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    long untilMs = intent.getLongExtra(EXTRA_SPORT_MODE_UNTIL_MS, 0L);
                    sportModeActiveUntilMs = Math.max(sportModeActiveUntilMs, untilMs);
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_SPORT_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            if (DebugBuild.ENABLED) {
                diagLine("sport mode receiver registered process=" + processName);
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("sport mode receiver failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }
    }

    private void registerDeviceChangeReceiver(final Context context) {
        if (context == null || !deviceChangeReceiverRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null || !ACTION_DEVICE_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    if (!isWorkerProcess()) {
                        return;
                    }
                    String did = intent.getStringExtra(EXTRA_DEVICE_DID);
                    String name = intent.getStringExtra(EXTRA_DEVICE_NAME);
                    String identity = did == null || did.length() == 0 ? null : "did:" + did;
                    if (identity != null && identity.equals(lastStartHelperDeviceIdentity)
                            && hasRecentHeartRateInAnyProcess()) {
                        if (DebugBuild.ENABLED) {
                            diagLine("device change ignored: same active device did=" + maskDid(did)
                                    + ", name=" + name);
                        }
                        return;
                    }
                    if (DebugBuild.ENABLED) {
                        diagLine("device change received did=" + maskDid(did)
                                + ", name=" + name
                                + ", previous=" + lastStartHelperDeviceIdentity);
                    }
                    scheduleRealtimeHrResume("device-changed:sync-start");
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_DEVICE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            if (DebugBuild.ENABLED) {
                diagLine("device change receiver registered process=" + processName);
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("device change receiver failed: " + describeThrowable(throwable));
            }
        }
    }

    private void hookHeartRateSinks(final ClassLoader classLoader) {
        hookWearRawHandler(classLoader);
        hookSportPacketHandler(classLoader, "com.xiaomi.fitness.sport.model.LaunchSportModel$DataHandlerImpl");
        hookSportPacketHandler(classLoader, "com.xiaomi.fitness.sport_manager.model.LaunchSportModel$DataHandlerImpl");
        hookEcoPacketHandler(classLoader, "com.xiaomi.fitness.sport_eco.model.LaunchSportModel$EcoDataHandlerImpl");
        hookEcoPacketHandler(classLoader, "com.xiaomi.fitness.sport_eco_manager.model.LaunchSportModel$EcoDataHandlerImpl");
        hookEcoPacketHandler(classLoader, "com.xiaomi.fitness.sport.model.LaunchSportModel$EcoDataHandlerImpl");
        hookEcoRawHandler(classLoader);
        hookEcoRemoteDataHandler(classLoader);
        hookSportWearDataSinks(classLoader);
        hookLegacyHuamiHeartRateProfile(classLoader);
        hookOriginalHuamiHeartRateController(classLoader);
        hookOriginalHuamiBleDevice(classLoader);
        hookDeviceHrStopHelpers(classLoader);
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco_manager.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_manager.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_manager.state.data.HuamiDataReceive");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco_manager.state.data.HuamiDataReceive");
        hookHuamiCallback(classLoader, "auu");
        hookHuamiCallback(classLoader, "com.xiaomi.hm.health.bt.sdk.HuamiDevice$y");
        hookHuamiCallback(classLoader, "com.xiaomi.fit.device.huami.HuaMiApiCallerImpl$startRealtimeMeasureHr$1");
        hookHuamiCallback(classLoader, "com.xiaomi.wearable.HuamiApiImpl$startRealtimeMeasureHr$1");
    }

    private void hookSleepDiagnostics(final ClassLoader classLoader) {
        if (!DebugBuild.ENABLED || !debugSleepHooksInstalled.compareAndSet(false, true)) {
            return;
        }
        debugSleepLine("installing sleep diagnostics process=" + processName);
        hookSleepParserDiagnostics(classLoader);
        hookSleepRecorderDiagnostics(classLoader);
        hookSleepSegmentUtilsDiagnostics(classLoader);
        hookFitnessSyncFlowDiagnostics(classLoader);
        hookSleepDirectDeviceSyncDiagnostics(classLoader);
        hookSleepStorageDiagnostics(classLoader);
        hookSleepAggregateDiagnostics(classLoader);
        hookSleepServerDiagnostics(classLoader);
    }

    private void hookSleepStatus(final ClassLoader classLoader) {
        if (!sleepStatusHooksInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            Class<?> allDayParser = findClass("com.xiaomi.fit.fitness.parser.daily.AllDaySleepParser", classLoader);
            hookAfter(allDayParser, "schemaParse", new Class<?>[]{dataIdClass, byte[].class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    publishSleepStatusFromAllDay("all-day-parser", result);
                }
            });
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                debugSleepLine("sleep status parser hook unavailable: " + describeThrowable(throwable));
            }
        }
        try {
            Class<?> sleepBizClass = findClass("com.xiaomi.fitness.repo.sleep.SleepBiz", classLoader);
            hookAfter(sleepBizClass, "splitDailyReport",
                    new Class<?>[]{String.class, String.class, long.class, java.util.Map.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            if (containsSleepRecordKey(chain.getArg(3))) {
                                publishSleepStatusFromReport("sleep-biz", result);
                            }
                        }
                    });
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                debugSleepLine("sleep status report hook unavailable: " + describeThrowable(throwable));
            }
        }
    }

    private void publishSleepStatusFromAllDay(String source, Object sleep) {
        SleepSnapshot snapshot = sleepSnapshotFromAllDay(source, sleep);
        if (snapshot == null) {
            debugSleepStateLine("raw-parse-empty", source, null, "object=" + shortObject(sleep));
            return;
        }
        debugSleepStateLine("raw-parse", source, snapshot, "candidate-mark");
        markSleepCandidateFromRaw(snapshot);
        if (HeartwithSleepStatus.STATE_AWAKE.equals(snapshot.state)) {
            if (isFinalSleepUploaded(snapshot)) {
                sleepCandidateSeenToday = false;
                sleepFinalReportRequested = false;
                debugSleepStateLine("raw-wake-skip", source, snapshot,
                        "finalAlreadyUploaded key=" + finalSleepKey(snapshot));
                if (DebugBuild.ENABLED) {
                    debugSleepLine("sleep raw wake skipped: final already uploaded, key=" + finalSleepKey(snapshot));
                }
                return;
            }
            debugSleepStateLine("raw-wake", source, snapshot, "submit-raw-and-request-final-report");
            publishSleepSnapshot(snapshot);
            requestFinalSleepReportAfterWake("raw-wake:" + source);
            return;
        }
        debugSleepStateLine("raw-active", source, snapshot, "submit-progress");
        publishSleepSnapshot(snapshot);
    }

    private void publishSleepStatusFromReport(String source, Object report) {
        SleepSnapshot snapshot = sleepSnapshotFromReport(source, report);
        debugSleepStateLine("report-parse", source, snapshot, "object=" + shortObject(report));
        if (!shouldPublishFinalSleepReport(snapshot, source)) {
            return;
        }
        debugSleepStateLine("report-final", source, snapshot, "submit-final");
        publishSleepSnapshot(snapshot);
        markFinalSleepUploaded(snapshot);
    }

    private void publishSleepSnapshot(SleepSnapshot snapshot) {
        if (snapshot == null || !heartRateHookEnabled) {
            debugSleepStateLine("upload-skip", "invalid", snapshot,
                    "hookEnabled=" + heartRateHookEnabled);
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        String key = snapshot.state + "|" + snapshot.bedAtMs + "|" + snapshot.sleepAtMs + "|"
                + snapshot.wakeAtMs + "|" + snapshot.goBedAtMs + "|" + snapshot.deviceBedAtMs + "|"
                + snapshot.leaveBedAtMs + "|" + snapshot.deviceWakeAtMs + "|"
                + snapshot.durationMinutes + "|" + snapshot.source + "|" + snapshot.segments.size();
        if (key.equals(lastSleepStatusKey)
                && elapsed - lastSleepStatusUploadElapsedMs < SLEEP_STATUS_POLL_INTERVAL_MS) {
            debugSleepStateLine("upload-skip", "duplicate-window", snapshot,
                    "ageMs=" + (elapsed - lastSleepStatusUploadElapsedMs));
            return;
        }
        lastSleepStatusKey = key;
        lastSleepStatusUploadElapsedMs = elapsed;
        uploader.onSleepStatus(appContext, new HeartwithSleepStatus(
                snapshot.state,
                snapshot.observedAtMs,
                snapshot.bedAtMs,
                snapshot.sleepAtMs,
                snapshot.wakeAtMs,
                snapshot.goBedAtMs,
                snapshot.deviceBedAtMs,
                snapshot.leaveBedAtMs,
                snapshot.deviceWakeAtMs,
                snapshot.source,
                snapshot.stable,
                snapshot.durationMinutes,
                snapshot.segments));
        debugSleepStateLine("upload-submit", "sdk", snapshot, "key=" + key);
        importantLine("sleep status uploaded state=" + snapshot.state
                + ", source=" + snapshot.source
                + ", bed=" + formatEpochMillis(snapshot.bedAtMs)
                + ", sleep=" + formatEpochMillis(snapshot.sleepAtMs)
                + ", wake=" + formatEpochMillis(snapshot.wakeAtMs)
                + ", durationMin=" + snapshot.durationMinutes
                + ", segments=" + snapshot.segments.size());
        if (DebugBuild.ENABLED) {
            debugSleepLine("sleep status uploaded state=" + snapshot.state
                    + ", source=" + snapshot.source
                    + ", bed=" + formatEpochMillis(snapshot.bedAtMs)
                    + ", sleep=" + formatEpochMillis(snapshot.sleepAtMs)
                    + ", wake=" + formatEpochMillis(snapshot.wakeAtMs)
                    + ", goBed=" + formatEpochMillis(snapshot.goBedAtMs)
                    + ", deviceBed=" + formatEpochMillis(snapshot.deviceBedAtMs)
                    + ", leaveBed=" + formatEpochMillis(snapshot.leaveBedAtMs)
                    + ", deviceWake=" + formatEpochMillis(snapshot.deviceWakeAtMs)
                    + ", durationMin=" + snapshot.durationMinutes
                    + ", segments=" + snapshot.segments.size());
        }
    }

    private SleepSnapshot sleepSnapshotFromAllDay(String source, Object sleep) {
        Object allDaySleep = allDaySleepObject(sleep);
        if (allDaySleep == null) {
            return null;
        }
        long goBedMs = secondsToMillis(longInvoke(allDaySleep, "getGoBedTime"));
        long deviceBedMs = secondsToMillis(longInvoke(allDaySleep, "getDeviceBedTime"));
        long leaveBedMs = secondsToMillis(longInvoke(allDaySleep, "getLeaveBedTime"));
        long deviceWakeMs = secondsToMillis(longInvoke(allDaySleep, "getDeviceWakeupTime"));
        long durationMs = Math.max(0L, longInvoke(allDaySleep, "getLinBedDuration")) * 1000L;
        boolean sleepFinished = isSleepFinished(allDaySleep);
        long bedAtMs = goBedMs > 0L ? goBedMs : deviceBedMs;
        long wakeAtMs = sleepFinished ? firstPositiveMillis(deviceWakeMs, leaveBedMs) : 0L;
        return buildSleepSnapshot(source, bedAtMs, deviceBedMs, wakeAtMs, durationMs,
                goBedMs, deviceBedMs, leaveBedMs, deviceWakeMs, sleepFinished, null);
    }

    private Object allDaySleepObject(Object value) {
        if (value == null) {
            return null;
        }
        Object nested = safeInvokeObject(value, "getAllDaySleepReport");
        if (looksLikeAllDaySleep(nested)) {
            return nested;
        }
        nested = safeInvokeObject(value, "getAllDaySleep");
        if (looksLikeAllDaySleep(nested)) {
            return nested;
        }
        nested = safeInvokeObject(value, "getAllDaySleepParseData");
        if (looksLikeAllDaySleep(nested)) {
            return nested;
        }
        if (looksLikeAllDaySleep(value)) {
            return value;
        }
        return null;
    }

    private boolean looksLikeAllDaySleep(Object value) {
        if (value == null) {
            return false;
        }
        return longInvoke(value, "getGoBedTime") > 0L
                || longInvoke(value, "getDeviceBedTime") > 0L
                || longInvoke(value, "getLeaveBedTime") > 0L
                || longInvoke(value, "getDeviceWakeupTime") > 0L
                || longInvoke(value, "getLinBedDuration") > 0L
                || booleanInvoke(value, "isSleepFinish");
    }

    private SleepSnapshot sleepSnapshotFromReport(String source, Object report) {
        if (report == null) {
            return null;
        }
        Object rawSegments = safeInvokeObject(report, "getSleepSegments");
        SegmentSleepBounds bounds = segmentSleepBounds(rawSegments);
        List<HeartwithSleepStatus.Segment> segments = sleepStatusSegments(rawSegments);
        long bedMs = bounds.bedMs;
        long deviceBedMs = bounds.deviceBedMs;
        long goBedMs = firstPositiveMillis(
                secondsToMillis(longInvoke(report, "getGoBedTime")),
                bounds.goBedMs,
                bedMs);
        long wakeMs = bounds.wakeMs;
        long deviceWakeMs = bounds.deviceWakeMs;
        long leaveBedMs = firstPositiveMillis(
                secondsToMillis(longInvoke(report, "getLeaveBedTime")),
                bounds.leaveBedMs,
                wakeMs);
        long reportDurationMinutes = Math.max(0L, longInvoke(report, "getTotalDuration"));
        long durationMinutes = reportDurationMinutes > 0L ? reportDurationMinutes : bounds.durationMinutes;
        long durationMs = durationMinutes * 60L * 1000L;
        boolean sleepFinished = isSleepFinished(report);
        long bedAtMs = earliestPositiveMillis(goBedMs, bedMs, deviceBedMs);
        long wakeAtMs = sleepFinished ? firstPositiveMillis(deviceWakeMs, leaveBedMs) : 0L;
        return buildSleepSnapshot(source, bedAtMs, deviceBedMs, wakeAtMs, durationMs,
                goBedMs, deviceBedMs, leaveBedMs, deviceWakeMs, sleepFinished, segments);
    }

    private SleepSnapshot buildSleepSnapshot(String source,
                                             long bedAtMs,
                                             long sleepAtMs,
                                             long wakeAtMs,
                                             long durationMs,
                                             long goBedAtMs,
                                             long deviceBedAtMs,
                                             long leaveBedAtMs,
                                             long deviceWakeAtMs,
                                             boolean sleepFinished,
                                             List<HeartwithSleepStatus.Segment> segments) {
        long nowMs = System.currentTimeMillis();
        long latestMs = Math.max(Math.max(bedAtMs, sleepAtMs), Math.max(wakeAtMs, deviceWakeAtMs));
        if (latestMs <= 0L || nowMs - latestMs > SLEEP_STATUS_MAX_AGE_MS) {
            return null;
        }
        String state;
        boolean stable = false;
        if (sleepFinished && wakeAtMs > 0L) {
            state = HeartwithSleepStatus.STATE_AWAKE;
            stable = true;
        } else if (sleepAtMs > 0L && (durationMs >= SLEEP_STATUS_MIN_SLEEP_MS
                || nowMs - sleepAtMs >= SLEEP_STATUS_MIN_SLEEP_MS)) {
            state = HeartwithSleepStatus.STATE_ASLEEP;
        } else if (bedAtMs > 0L) {
            state = HeartwithSleepStatus.STATE_IN_BED;
        } else {
            return null;
        }
        return new SleepSnapshot(
                state,
                nowMs,
                bedAtMs,
                sleepAtMs,
                wakeAtMs,
                goBedAtMs,
                deviceBedAtMs,
                leaveBedAtMs,
                deviceWakeAtMs,
                source == null ? "mi_health_sleep" : source,
                stable,
                Math.max(0L, durationMs / 60_000L),
                segments == null ? new ArrayList<>() : segments);
    }

    private boolean isSleepFinished(Object value) {
        if (value == null) {
            return false;
        }
        if (booleanInvoke(value, "isSleepFinish")
                || booleanInvoke(value, "isSleepFinished")
                || booleanInvoke(value, "getSleepFinish")
                || booleanInvoke(value, "getSleepFinished")) {
            return true;
        }
        long stage = longInvoke(value, "getStage");
        return stage >= 2L;
    }

    private long firstPositiveMillis(long... values) {
        if (values == null) {
            return 0L;
        }
        for (long value : values) {
            if (value > 0L) {
                return value;
            }
        }
        return 0L;
    }

    private long earliestPositiveMillis(long... values) {
        long selected = 0L;
        if (values == null) {
            return selected;
        }
        for (long value : values) {
            if (value > 0L && (selected == 0L || value < selected)) {
                selected = value;
            }
        }
        return selected;
    }

    private void markSleepCandidateFromRaw(SleepSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        sleepTrackingDayStartMs = sleepDayStartMillis(snapshot);
        sleepCandidateSeenToday = true;
        debugSleepStateLine("candidate-marked", snapshot.source, snapshot,
                "trackingDay=" + formatEpochMillis(sleepTrackingDayStartMs));
    }

    private boolean shouldPublishFinalSleepReport(SleepSnapshot snapshot, String source) {
        if (snapshot == null || !HeartwithSleepStatus.STATE_AWAKE.equals(snapshot.state)) {
            return false;
        }
        if (isFinalSleepUploaded(snapshot)) {
            debugSleepStateLine("report-final-skip", source, snapshot,
                    "alreadyUploaded key=" + finalSleepKey(snapshot));
            if (DebugBuild.ENABLED) {
                debugSleepLine("sleep final report skipped: already uploaded, source=" + source
                        + ", key=" + finalSleepKey(snapshot));
            }
            return false;
        }
        long dayStartMs = sleepDayStartMillis(snapshot);
        boolean sameSleepDay = sleepTrackingDayStartMs == 0L || sleepTrackingDayStartMs == dayStartMs;
        boolean allowed = sleepFinalReportRequested
                || (sleepCandidateSeenToday && sameSleepDay);
        if (!allowed && isRecentFinalSleepReport(snapshot)) {
            allowed = true;
            debugSleepStateLine("report-final-allow", source, snapshot,
                    "recentStableReportWithoutCandidate");
        }
        if (!allowed && DebugBuild.ENABLED) {
            debugSleepStateLine("report-final-skip", source, snapshot,
                    "notRequestedOrCandidate");
            debugSleepLine("sleep final report ignored source=" + source
                    + ", candidate=" + sleepCandidateSeenToday
                    + ", requested=" + sleepFinalReportRequested
                    + ", day=" + dayStartMs
                    + ", trackingDay=" + sleepTrackingDayStartMs);
        }
        return allowed;
    }

    private boolean isRecentFinalSleepReport(SleepSnapshot snapshot) {
        if (snapshot == null || !snapshot.stable || snapshot.wakeAtMs <= 0L) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        return snapshot.durationMinutes > 0L
                && nowMs >= snapshot.wakeAtMs
                && nowMs - snapshot.wakeAtMs <= SLEEP_STATUS_MAX_AGE_MS;
    }

    private boolean shouldRequestSleepRepository(String reason) {
        if (sleepFinalReportRequested || sleepCandidateSeenToday) {
            debugSleepStateLine("repository-allow", reason, null, "candidate-or-final-requested");
            return true;
        }
        debugSleepStateLine("repository-skip", reason, null, "no-candidate");
        if (DebugBuild.ENABLED) {
            debugSleepLine("sleep repository skipped: no sleep candidate, reason=" + reason);
        }
        return false;
    }

    private boolean isFinalSleepUploaded(SleepSnapshot snapshot) {
        String key = finalSleepKey(snapshot);
        if (key.length() == 0) {
            return false;
        }
        if (key.equals(sleepFinalUploadedKey)) {
            return true;
        }
        Context context = appContext;
        if (context == null) {
            return false;
        }
        String persisted = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SLEEP_FINAL_UPLOADED_KEY, "");
        if (persisted != null && persisted.equals(key)) {
            sleepFinalUploadedKey = persisted;
            return true;
        }
        return false;
    }

    private void markFinalSleepUploaded(SleepSnapshot snapshot) {
        String key = finalSleepKey(snapshot);
        if (key.length() == 0) {
            return;
        }
        sleepFinalUploadedKey = key;
        sleepFinalReportRequested = false;
        sleepCandidateSeenToday = false;
        debugSleepStateLine("final-marked", snapshot.source, snapshot, "key=" + key);
        Context context = appContext;
        if (context != null) {
            context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SLEEP_FINAL_UPLOADED_KEY, key)
                    .apply();
        }
    }

    private String finalSleepKey(SleepSnapshot snapshot) {
        if (snapshot == null || snapshot.wakeAtMs <= 0L) {
            return "";
        }
        return sleepDayStartMillis(snapshot) + "|"
                + snapshot.bedAtMs + "|"
                + snapshot.sleepAtMs + "|"
                + snapshot.wakeAtMs;
    }

    private long sleepDayStartMillis(SleepSnapshot snapshot) {
        long anchor = snapshot == null ? 0L : firstPositiveMillis(
                snapshot.sleepAtMs,
                snapshot.bedAtMs,
                snapshot.wakeAtMs,
                System.currentTimeMillis());
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(anchor);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private void requestFinalSleepReportAfterWake(String reason) {
        ClassLoader classLoader = targetClassLoader;
        if (classLoader == null || !isWorkerProcess()) {
            debugSleepStateLine("final-request-skip", reason, null,
                    "classLoader=" + (classLoader != null) + ", worker=" + isWorkerProcess());
            return;
        }
        Object device = getCurrentDeviceModel(classLoader);
        if (device == null) {
            debugSleepStateLine("final-request-skip", reason, null, "device=null");
            return;
        }
        String did = getCurrentDeviceId(device);
        if (did == null || did.length() == 0) {
            debugSleepStateLine("final-request-skip", reason, null, "did=null");
            return;
        }
        sleepFinalReportRequested = true;
        debugSleepStateLine("final-request", reason, null, "did=" + maskDid(did));
        requestSleepRepositoryReports(classLoader, did, reason);
    }

    private long secondsToMillis(long seconds) {
        return seconds <= 0L ? 0L : seconds * 1000L;
    }

    private void hookSleepParserDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            Class<?> parserClass = findClass("com.xiaomi.fit.fitness.parser.FitnessDataParser", classLoader);
            hookAfter(parserClass, "parseDailyData", new Class<?>[]{byte[].class, int.class, int.class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    logFitnessParseResult("FitnessDataParser.parseDailyData dailyType=" + chain.getArg(1)
                            + ", fileType=" + chain.getArg(2), result);
                }
            });
            hookAfter(parserClass, "parse", new Class<?>[]{dataIdClass, byte[].class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    logFitnessParseResult("FitnessDataParser.parse dataId=" + chain.getArg(0), result);
                }
            });
        } catch (Throwable throwable) {
            debugSleepLine("parser diagnostics unavailable: " + describeThrowable(throwable));
        }
        try {
            Class<?> binaryDataClass = findClass("com.xiaomi.fit.fitness.parser.data.FitnessBinaryData", classLoader);
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            Class<?> allDayParser = findClass("com.xiaomi.fit.fitness.parser.daily.AllDaySleepParser", classLoader);
            hookAfter(allDayParser, "nativeParse", new Class<?>[]{binaryDataClass}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    logFitnessParseResult("AllDaySleepParser.nativeParse", result);
                }
            });
            hookAfter(allDayParser, "schemaParse", new Class<?>[]{dataIdClass, byte[].class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    Object dataId = chain.getArg(0);
                    logFitnessParseResult("AllDaySleepParser.schemaParse dataId=" + dataId, result);
                    publishDebugSleepParseResult("AllDaySleepParser.schemaParse", dataId, result);
                    maybeScheduleDebugSleepOnlySync(dataId);
                }
            });
        } catch (Throwable throwable) {
            debugSleepLine("all-day sleep parser diagnostics unavailable: " + describeThrowable(throwable));
        }
    }

    private void hookSleepRecorderDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> recorderClass = findClass("com.xiaomi.fit.fitness.device.mi.parse.utils.MiParsedDataRecorder", classLoader);
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            Class<?> allDaySleepClass = findClass("com.xiaomi.fit.fitness.parser.data.AllDaySleepParseData", classLoader);
            Class<?> continuationClass = findClass("kotlin.coroutines.Continuation", classLoader);
            hookAfter(recorderClass, "recordSleepSegment",
                    new Class<?>[]{String.class, dataIdClass, allDaySleepClass, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("MiParsedDataRecorder.recordSleepSegment sid=" + chain.getArg(0)
                                    + ", dataId=" + chain.getArg(1)
                                    + ", sleep=" + describeAllDaySleep(chain.getArg(2))
                                    + ", result=" + describeCoroutineResult(result));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("mi parsed recorder sleep diagnostics unavailable: " + describeThrowable(throwable));
        }
        try {
            Class<?> recorderClass = findClass("com.xiaomi.fit.fitness.device.mi.parse.utils.MiParsedDataRecorder", classLoader);
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            Class<?> nightSleepClass = findClass("com.xiaomi.fit.fitness.parser.data.NightSleepReport", classLoader);
            Class<?> daytimeSleepClass = findClass("com.xiaomi.fit.fitness.parser.data.DaytimeSleepReport", classLoader);
            hookAfter(recorderClass, "recordNightSleepReport",
                    new Class<?>[]{String.class, dataIdClass, nightSleepClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("MiParsedDataRecorder.recordNightSleepReport sid=" + chain.getArg(0)
                                    + ", dataId=" + chain.getArg(1)
                                    + ", report=" + describeNightSleep(chain.getArg(2))
                                    + ", result=" + result);
                        }
                    });
            hookAfter(recorderClass, "recordDaytimeSleepReport",
                    new Class<?>[]{String.class, dataIdClass, daytimeSleepClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("MiParsedDataRecorder.recordDaytimeSleepReport sid=" + chain.getArg(0)
                                    + ", dataId=" + chain.getArg(1)
                                    + ", report=" + describeDaytimeSleep(chain.getArg(2))
                                    + ", result=" + result);
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("legacy sleep recorder diagnostics unavailable: " + describeThrowable(throwable));
        }
        try {
            Class<?> recorderClass = findClass("com.xiaomi.fit.fitness.impl.FitnessDataRecorderImpl", classLoader);
            Class<?> reportClass = findClass("com.xiaomi.fit.fitness.export.data.item.SleepSegmentReport", classLoader);
            Class<?> reportArrayClass = Array.newInstance(reportClass, 0).getClass();
            hookAfter(recorderClass, "recordSleepSegment",
                    new Class<?>[]{String.class, int.class, boolean.class, reportArrayClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("FitnessDataRecorderImpl.recordSleepSegment array sid=" + chain.getArg(0)
                                    + ", tz=" + chain.getArg(1)
                                    + ", complete=" + chain.getArg(2)
                                    + ", reports=" + describeSleepReports(chain.getArg(3))
                                    + ", result=" + result);
                        }
                    });
            hookAfter(recorderClass, "recordSleepSegment",
                    new Class<?>[]{String.class, int.class, reportArrayClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("FitnessDataRecorderImpl.recordSleepSegment default sid=" + chain.getArg(0)
                                    + ", tz=" + chain.getArg(1)
                                    + ", reports=" + describeSleepReports(chain.getArg(2))
                                    + ", result=" + result);
                        }
                    });
            hookAfter(recorderClass, "recordSleepSegment",
                    new Class<?>[]{String.class, int.class, java.util.List.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("FitnessDataRecorderImpl.recordSleepSegment list sid=" + chain.getArg(0)
                                    + ", tz=" + chain.getArg(1)
                                    + ", reports=" + describeSleepReports(chain.getArg(2))
                                    + ", result=" + result);
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("fitness recorder sleep diagnostics unavailable: " + describeThrowable(throwable));
        }
    }

    private void hookSleepSegmentUtilsDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> utilsClass = findClass("com.xiaomi.fit.fitness.device.mi.parse.utils.SleepSegmentUtils", classLoader);
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            Class<?> allDaySleepClass = findClass("com.xiaomi.fit.fitness.parser.data.AllDaySleepParseData", classLoader);
            Class<?> continuationClass = findClass("kotlin.coroutines.Continuation", classLoader);
            hookAfter(utilsClass, "computeSleepSegmentReport",
                    new Class<?>[]{dataIdClass, allDaySleepClass, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("SleepSegmentUtils.computeSleepSegmentReport dataId=" + chain.getArg(0)
                                    + ", sleep=" + describeAllDaySleep(chain.getArg(1))
                                    + ", result=" + describeCoroutineResult(result)
                                    + ", reports=" + describeSleepReports(result));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("sleep segment utils diagnostics unavailable: " + describeThrowable(throwable));
        }
    }

    private void hookFitnessSyncFlowDiagnostics(final ClassLoader classLoader) {
        try {
            final Class<?> sentCallbackClass =
                    findClass("com.xiaomi.fit.fitness.device.mi.send.FitnessWearSender$SentCallback", classLoader);
            Class<?> senderClass = findClass("com.xiaomi.fit.fitness.device.mi.send.FitnessWearSender", classLoader);
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            Class<?> continuationClass = findClass("kotlin.coroutines.Continuation", classLoader);
            hookAfter(senderClass, "requestData",
                    new Class<?>[]{String.class, sentCallbackClass, Boolean.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("sync flow requestData did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", isBgAutoSync=" + chain.getArg(2));
                        }
                    });
            hookAfter(senderClass, "requestTodayData",
                    new Class<?>[]{String.class, sentCallbackClass, Boolean.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("sync flow requestTodayData did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", isBgAutoSync=" + chain.getArg(2));
                        }
                    });
            hookAfter(senderClass, "requestHistoryData",
                    new Class<?>[]{String.class, sentCallbackClass, boolean.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("sync flow requestHistoryData did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", hasTodayDataToSync=" + chain.getArg(2));
                        }
                    });
            hookAfter(senderClass, "requestDataIdIfHas",
                    new Class<?>[]{String.class, byte[].class, boolean.class, sentCallbackClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("sync flow requestDataIdIfHas did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", today=" + chain.getArg(2)
                                    + ", ids=" + describeFitnessDataIdBytes(classLoader, (byte[]) chain.getArg(1), 16));
                        }
                    });
            hookAfter(senderClass, "sendRequestDataToWear",
                    new Class<?>[]{String.class, byte[].class, sentCallbackClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("sync flow sendRequestDataToWear did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", selected=" + describeFitnessDataIdBytes(classLoader, (byte[]) chain.getArg(1), 16));
                        }
                    });
            hookAfter(senderClass, "saveDataIdToSynced",
                    new Class<?>[]{String.class, dataIdClass, boolean.class, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataId = chain.getArg(1);
                            if (!isSleepFitnessDataId(dataId)) {
                                return;
                            }
                            debugSleepLine("sync db saveDataIdToSynced did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", dataId=" + dataId
                                    + ", forceSync=" + chain.getArg(2)
                                    + ", result=" + describeCoroutineResult(result)
                                    + ", stack=" + compactStackTrace(7));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("fitness wear sender diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> pbClass = findClass("com.xiaomi.fit.fitness.device.mi.send.FitnessWearPbImpl", classLoader);
            Class<?> idsCallbackClass =
                    findClass("com.xiaomi.fit.fitness.sync.export.api.OnGetFitnessIdsCallback", classLoader);
            Class<?> function1Class = findClass("kotlin.jvm.functions.Function1", classLoader);
            hookAfter(pbClass, "getTodayFitnessIds",
                    new Class<?>[]{String.class, Boolean.class, idsCallbackClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("sync pb getTodayFitnessIds did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", isBgAutoSync=" + chain.getArg(1));
                        }
                    });
            hookAfter(pbClass, "getHistoryFitnessIds",
                    new Class<?>[]{String.class, idsCallbackClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("sync pb getHistoryFitnessIds did=" + maskDid(String.valueOf(chain.getArg(0))));
                        }
                    });
            hookAfter(pbClass, "requestFitnessIds",
                    new Class<?>[]{String.class, byte[].class, function1Class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("sync pb requestFitnessIds did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", ids=" + describeFitnessDataIdBytes(classLoader, (byte[]) chain.getArg(1), 16));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("fitness pb diagnostics unavailable: " + describeThrowable(throwable));
        }
    }

    private void hookSleepStorageDiagnostics(final ClassLoader classLoader) {
        try {
            Class<?> headerCompanionClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataHeader$Companion", classLoader);
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            hookAfter(headerCompanionClass, "getDailyDataId",
                    new Class<?>[]{byte[].class, int.class, int.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            int dailyType = ((Number) chain.getArg(1)).intValue();
                            if (dailyType != 7 && dailyType != 8) {
                                return;
                            }
                            debugSleepLine("FitnessDataHeader.getDailyDataId dailyType=" + dailyType
                                    + ", fileType=" + chain.getArg(2)
                                    + ", header=" + describeBytes((byte[]) chain.getArg(0), 24)
                                    + ", dataId=" + describeFitnessDataId(result)
                                    + ", stack=" + compactStackTrace(8));
                        }
                    });
            hookAfter(headerCompanionClass, "parseDataHeader",
                    new Class<?>[]{dataIdClass, byte[].class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataId = chain.getArg(0);
                            if (!isSleepFitnessDataIdQuiet(dataId)) {
                                return;
                            }
                            debugSleepLine("FitnessDataHeader.parseDataHeader dataId=" + describeFitnessDataId(dataId)
                                    + ", header=" + describeBytes((byte[]) chain.getArg(1), 32)
                                    + ", result=" + shortObject(result)
                                    + ", dataValid=" + describeBytes((byte[]) safeInvokeObject(result, "getDataValid"), 16)
                                    + ", invalidVersion=" + safeInvoke(result, "getDataInvalidVersion"));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("fitness data header diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> updaterClass = findClass("com.xiaomi.fit.fitness.remote.FitnessDBDataUpdater", classLoader);
            hookAfter(updaterClass, "recordDailyRecord",
                    new Class<?>[]{String.class, String.class, java.util.List.class, boolean.class, boolean.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            String persistKey = String.valueOf(chain.getArg(0));
                            if (!"sleep".equals(persistKey)) {
                                return;
                            }
                            Object items = chain.getArg(2);
                            debugSleepLine("sleep db recordDailyRecord sid=" + maskDid(String.valueOf(chain.getArg(1)))
                                    + ", items=" + describeRecordItems(items, 3)
                                    + ", allowSwitchProcess=" + chain.getArg(3)
                                    + ", allowUpdate=" + chain.getArg(4)
                                    + ", result=" + result
                                    + ", stack=" + compactStackTrace(10));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("sleep db updater diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> baseClass = findClass("com.xiaomi.fit.fitness.device.FitnessDataSyncBaseImpl", classLoader);
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            Class<?> continuationClass = findClass("kotlin.coroutines.Continuation", classLoader);
            hookAfter(baseClass, "updateSyncStatus",
                    new Class<?>[]{String.class, dataIdClass, int.class, boolean.class, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataId = chain.getArg(1);
                            if (!isSleepFitnessDataId(dataId)) {
                                return;
                            }
                            debugSleepLine("sync db updateSyncStatus did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", dataId=" + dataId
                                    + ", status=" + chain.getArg(2)
                                    + ", forceSync=" + chain.getArg(3)
                                    + ", result=" + describeCoroutineResult(result)
                                    + ", stack=" + compactStackTrace(7));
                        }
                    });
            hookAfter(baseClass, "deleteSyncStatus",
                    new Class<?>[]{String.class, dataIdClass, int.class, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataId = chain.getArg(1);
                            if (!isSleepFitnessDataId(dataId)) {
                                return;
                            }
                            debugSleepLine("sync db deleteSyncStatus did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", dataId=" + dataId
                                    + ", status=" + chain.getArg(2)
                                    + ", result=" + describeCoroutineResult(result)
                                    + ", stack=" + compactStackTrace(7));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("sync db status diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> utilsClass = findClass("com.xiaomi.fit.fitness.persist.db.utils.FitnessFDSDataDaoUtils", classLoader);
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            hookAfter(utilsClass, "recordFDSDataIdToDB",
                    new Class<?>[]{String.class, dataIdClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataId = chain.getArg(1);
                            if (!isSleepFitnessDataIdQuiet(dataId)) {
                                return;
                            }
                            debugSleepLine("sleep fds recordFDSDataIdToDB sid=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", dataId=" + describeFitnessDataId(dataId)
                                    + ", result=" + result
                                    + ", stack=" + compactStackTrace(8));
                        }
                    });
            hookAfter(utilsClass, "getDailyFDSIds",
                    new Class<?>[]{String.class, int.class, long.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            int dailyType = ((Number) chain.getArg(1)).intValue();
                            if (dailyType != 7 && dailyType != 8) {
                                return;
                            }
                            debugSleepLine("sleep fds getDailyFDSIds sid=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", dailyType=" + dailyType
                                    + ", since=" + chain.getArg(2)
                                    + ", result=" + describeFdsEntities(classLoader, result, 12));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("sleep fds dao diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> uploaderClass = findClass("com.xiaomi.fit.fitness.persist.SleepDataFDSUploader", classLoader);
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            hookAfter(uploaderClass, "uploadSleepSrcData",
                    new Class<?>[]{String.class, dataIdClass, String.class, byte[].class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("SleepDataFDSUploader.uploadSleepSrcData sid=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", dataId=" + describeFitnessDataId(chain.getArg(1))
                                    + ", fileName=" + safeString(String.valueOf(chain.getArg(2)))
                                    + ", sleepData=" + describeBytes((byte[]) chain.getArg(3), 32)
                                    + ", result=" + result
                                    + ", stack=" + compactStackTrace(8));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("sleep fds uploader diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> syncerClass = findClass("com.xiaomi.fitness.sleep.MiuiSleepSyncer", classLoader);
            Class<?> reportClass = findClass("com.xiaomi.sleep.provider.SleepReport", classLoader);
            hookAfter(syncerClass, "onReceiveMiuiSleep",
                    new Class<?>[]{reportClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("miui phone sleep onReceiveMiuiSleep report="
                                    + shortObject(chain.getArg(0))
                                    + ", result=" + result
                                    + ", stack=" + compactStackTrace(8));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("miui phone sleep diagnostics unavailable: " + describeThrowable(throwable));
        }
    }

    private void hookSleepDirectDeviceSyncDiagnostics(final ClassLoader classLoader) {
        try {
            Class<?> receiverClass = findClass("com.xiaomi.fit.fitness.device.mi.receive.FitnessDataReceiver", classLoader);
            hookAfter(receiverClass, "onReceive", new Class<?>[]{byte[].class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    byte[] data = (byte[]) chain.getArg(0);
                    debugSleepLine("direct sync FitnessDataReceiver.onReceive sid="
                            + maskDid(String.valueOf(getFieldValueQuietly(chain.getThisObject(), "sid")))
                            + ", chunk=" + describeFitnessSyncChunk(data)
                            + ", current=" + getFieldValueQuietly(chain.getThisObject(), "mCurrentCount")
                            + "/" + getFieldValueQuietly(chain.getThisObject(), "mTotalCount")
                            + ", totalBytes=" + getFieldValueQuietly(chain.getThisObject(), "mTotalBytes"));
                }
            });
            hookAfter(receiverClass, "merge", new Class<?>[]{}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync FitnessDataReceiver.merge sid="
                            + maskDid(String.valueOf(getFieldValueQuietly(chain.getThisObject(), "sid")))
                            + ", totalBytes=" + getFieldValueQuietly(chain.getThisObject(), "mTotalBytes")
                            + ", segments=" + describeListShort(getFieldValueQuietly(chain.getThisObject(), "mSegments"), 3));
                }
            });
        } catch (Throwable throwable) {
            debugSleepLine("direct sync FitnessDataReceiver diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> managerClass = findClass("com.xiaomi.fit.fitness.device.mi.FitnessDeviceSyncManager", classLoader);
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            Class<?> callbackClass = findFirstClass(classLoader, "hfa", "defpackage.hfa");
            Class<?> continuationClass = findClass("kotlin.coroutines.Continuation", classLoader);
            hookAfter(managerClass, "startSyncWithDevice$fitness_sync_chinaProductRelease",
                    new Class<?>[]{String.class, callbackClass, Boolean.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("direct sync FitnessDeviceSyncManager.startSyncWithDevice sid="
                                    + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", isBgAutoSync=" + chain.getArg(2)
                                    + ", result=" + result);
                        }
                    });
            hookAfter(managerClass, "syncWithDevice",
                    new Class<?>[]{String.class, byte[].class, callbackClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("direct sync FitnessDeviceSyncManager.syncWithDevice sid="
                                    + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", ids=" + describeFitnessDataIdBytes(classLoader, (byte[]) chain.getArg(1), 16)
                                    + ", result=" + result);
                        }
                    });
            hookAfter(managerClass, "syncTheData",
                    new Class<?>[]{String.class, dataIdClass, byte[].class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataId = chain.getArg(1);
                            debugSleepLine("direct sync FitnessDeviceSyncManager.syncTheData sid="
                                    + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", dataId=" + describeFitnessDataId(dataId)
                                    + ", sleep=" + isSleepFitnessDataIdQuiet(dataId)
                                    + ", data=" + describeBytes((byte[]) chain.getArg(2), 32)
                                    + ", result=" + result);
                        }
                    });
            hookAfter(managerClass, "parseTheFitnessData",
                    new Class<?>[]{String.class, dataIdClass, byte[].class, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataId = chain.getArg(1);
                            if (!isSleepFitnessDataIdQuiet(dataId)) {
                                return;
                            }
                            debugSleepLine("direct sync FitnessDeviceSyncManager.parseTheFitnessData sid="
                                    + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", dataId=" + describeFitnessDataId(dataId)
                                    + ", data=" + describeBytes((byte[]) chain.getArg(2), 32)
                                    + ", result=" + describeCoroutineResult(result));
                        }
                    });
            hookAfter(managerClass, "parseBinaryData",
                    new Class<?>[]{String.class, dataIdClass, byte[].class, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataId = chain.getArg(1);
                            if (!isSleepFitnessDataIdQuiet(dataId)) {
                                return;
                            }
                            debugSleepLine("direct sync FitnessDeviceSyncManager.parseBinaryData sid="
                                    + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", dataId=" + describeFitnessDataId(dataId)
                                    + ", data=" + describeBytes((byte[]) chain.getArg(2), 32)
                                    + ", result=" + describeCoroutineResult(result));
                        }
                    });
            hookAfter(managerClass, "onParseSuccess",
                    new Class<?>[]{String.class, dataIdClass, int.class, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataId = chain.getArg(1);
                            if (!isSleepFitnessDataIdQuiet(dataId)) {
                                return;
                            }
                            debugSleepLine("direct sync FitnessDeviceSyncManager.onParseSuccess sid="
                                    + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", dataId=" + describeFitnessDataId(dataId)
                                    + ", code=" + chain.getArg(2)
                                    + ", result=" + describeCoroutineResult(result));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("direct sync FitnessDeviceSyncManager diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> processorClass = findClass("com.xiaomi.fit.fitness.device.mi.parse.FitnessDataProcessor", classLoader);
            Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
            Class<?> continuationClass = findClass("kotlin.coroutines.Continuation", classLoader);
            hookAfter(processorClass, "parseAndRecord",
                    new Class<?>[]{String.class, dataIdClass, byte[].class, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataId = chain.getArg(1);
                            if (!isSleepFitnessDataIdQuiet(dataId)) {
                                return;
                            }
                            debugSleepLine("direct sync FitnessDataProcessor.parseAndRecord sid="
                                    + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", dataId=" + describeFitnessDataId(dataId)
                                    + ", data=" + describeBytes((byte[]) chain.getArg(2), 32)
                                    + ", result=" + describeCoroutineResult(result));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("direct sync FitnessDataProcessor diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> remoteClass = findClass("com.xiaomi.fit.fitness.remote.FitnessSyncRemoteImpl", classLoader);
            hookAfter(remoteClass, "triggerDataSync", new Class<?>[]{boolean.class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync triggerDataSync manualForce=" + chain.getArg(0)
                            + ", result=" + result
                            + ", stack=" + compactStackTrace(8));
                }
            });
            hookAfter(remoteClass, "triggerDataSyncByWidget", new Class<?>[]{boolean.class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync triggerDataSyncByWidget manualForce=" + chain.getArg(0)
                            + ", result=" + result
                            + ", stack=" + compactStackTrace(8));
                }
            });
        } catch (Throwable throwable) {
            debugSleepLine("direct sync remote diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> contactClass = findClass("com.xiaomi.fitness.device.contact.DeviceContactImpl", classLoader);
            Class<?> modelClass = findClass("com.xiaomi.fitness.device.manager.export.WearableDeviceModel", classLoader);
            Class<?> callbackClass = findClass("com.xiaomi.fitness.device.contact.export.DeviceSyncCallback", classLoader);
            Class<?> handlerClass = findClass("com.xiaomi.fitness.device.contact.export.DataHandlerWrapper", classLoader);
            Class<?> packetClass = findClass("ixs", classLoader);
            Class<?> onSyncCallbackClass = findClass("com.xiaomi.fitness.device.contact.export.OnSyncCallback", classLoader);
            Class<?> stateListenerClass = findClass("com.xiaomi.fitness.device.contact.export.SyncStateListener", classLoader);

            hookAfter(contactClass, "syncData", new Class<?>[]{String.class, boolean.class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync DeviceContact.syncData did=" + maskDid(String.valueOf(chain.getArg(0)))
                            + ", isAuto=" + chain.getArg(1)
                            + ", result=" + result);
                }
            });
            hookAfter(contactClass, "syncData", new Class<?>[]{modelClass, boolean.class, callbackClass}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    Object device = chain.getArg(0);
                    debugSleepLine("direct sync DeviceContact.syncData model=" + describeDirectSyncDevice(device)
                            + ", isAuto=" + chain.getArg(1)
                            + ", callback=" + shortObject(chain.getArg(2))
                            + ", result=" + result);
                }
            });
            hookAfter(contactClass, "syncDataAtFixRate", new Class<?>[]{String.class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync DeviceContact.syncDataAtFixRate did=" + maskDid(String.valueOf(chain.getArg(0)))
                            + ", result=" + result);
                }
            });
            hookAfter(contactClass, "syncDataAtFixRate", new Class<?>[]{modelClass}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync DeviceContact.syncDataAtFixRate model="
                            + describeDirectSyncDevice(chain.getArg(0))
                            + ", result=" + result);
                }
            });
            hookAfter(contactClass, "addDataHandler", new Class<?>[]{int.class, handlerClass}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync DeviceContact.addDataHandler type=" + chain.getArg(0)
                            + ", handler=" + shortObject(chain.getArg(1)));
                }
            });
            hookAfter(contactClass, "call", new Class<?>[]{String.class, packetClass, boolean.class, onSyncCallbackClass, int.class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync DeviceContact.call packet did=" + maskDid(String.valueOf(chain.getArg(0)))
                            + ", packet=" + describePacketObject(chain.getArg(1))
                            + ", needResponse=" + chain.getArg(2)
                            + ", timeout=" + chain.getArg(4)
                            + ", result=" + result);
                }
            });
            hookAfter(contactClass, "call", new Class<?>[]{String.class, int.class, byte[].class, boolean.class, onSyncCallbackClass, int.class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync DeviceContact.call raw did=" + maskDid(String.valueOf(chain.getArg(0)))
                            + ", type=" + chain.getArg(1)
                            + ", data=" + describeBytes((byte[]) chain.getArg(2), 32)
                            + ", needResponse=" + chain.getArg(3)
                            + ", timeout=" + chain.getArg(5)
                            + ", result=" + result);
                }
            });
            hookAfter(contactClass, "sendFile",
                    new Class<?>[]{String.class, int.class, int.class, String.class, int.class, stateListenerClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("direct sync DeviceContact.sendFile did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", type=" + chain.getArg(1)
                                    + ", detailType=" + chain.getArg(2)
                                    + ", path=" + chain.getArg(3)
                                    + ", segmentLength=" + chain.getArg(4)
                                    + ", result=" + result);
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("direct sync DeviceContact diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> observersClass = findClass("com.xiaomi.fitness.device.contact.SyncObservers", classLoader);
            Class<?> callbackClass = findClass("com.xiaomi.fitness.device.contact.export.DeviceSyncCallback", classLoader);
            Class<?> syncerClass = findClass("com.xiaomi.fitness.device.contact.export.DeviceSyncer", classLoader);
            Class<?> modelClass = findClass("com.xiaomi.fitness.device.manager.export.WearableDeviceModel", classLoader);
            hookAfter(observersClass, "addSyncer", new Class<?>[]{syncerClass}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync SyncObservers.addSyncer syncer=" + shortObject(chain.getArg(0)));
                }
            });
            hookAfter(observersClass, "syncData", new Class<?>[]{String.class, callbackClass}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync SyncObservers.syncData did=" + maskDid(String.valueOf(chain.getArg(0)))
                            + ", callback=" + shortObject(chain.getArg(1))
                            + ", syncers=" + describeListShort(safeInvokeObject(chain.getThisObject(), "getMSyncers"), 8)
                            + ", injectSyncers=" + describeListShort(safeInvokeObject(chain.getThisObject(), "getInjectSyncers"), 8)
                            + ", result=" + result);
                }
            });
            hookAfter(observersClass, "dispatchResult", new Class<?>[]{String.class, int.class, boolean.class, Object.class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync SyncObservers.dispatchResult did=" + maskDid(String.valueOf(chain.getArg(0)))
                            + ", type=" + chain.getArg(1)
                            + ", success=" + chain.getArg(2)
                            + ", obj=" + shortObject(chain.getArg(3)));
                }
            });
            hookAfter(observersClass, "onStart", new Class<?>[]{modelClass}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    Object model = chain.getArg(0);
                    debugSleepLine("direct sync SyncObservers.onStart model=" + describeDirectSyncDevice(model));
                    notifyDeviceChangedFromSync(model);
                }
            });
            hookAfter(observersClass, "onFinish", new Class<?>[]{modelClass, int.class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync SyncObservers.onFinish model=" + describeDirectSyncDevice(chain.getArg(0))
                            + ", code=" + chain.getArg(1));
                }
            });
        } catch (Throwable throwable) {
            debugSleepLine("direct sync observer diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> engineClass = findClass("com.xiaomi.fitness.device.contact.remote.DeviceContactEngineImpl", classLoader);
            Class<?> binderHandlerClass = findFirstClass(classLoader, "c06", "defpackage.c06");
            Class<?> callbackClass = findClass("com.xiaomi.fitness.device.contact.export.a", classLoader);
            Class<?> fileCallbackClass = findFirstClass(classLoader, "iod", "defpackage.iod");
            hookAfter(engineClass, "addDataHandler", new Class<?>[]{binderHandlerClass}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync Engine.addDataHandler binder=" + shortObject(chain.getArg(0)));
                }
            });
            hookAfter(engineClass, "getDataHandler", new Class<?>[]{String.class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync Engine.getDataHandler did=" + maskDid(String.valueOf(chain.getArg(0)))
                            + ", result=" + shortObject(result));
                }
            });
            hookAfter(engineClass, "removeDataHandler", new Class<?>[]{String.class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync Engine.removeDataHandler did=" + maskDid(String.valueOf(chain.getArg(0))));
                }
            });
            hookAfter(engineClass, "callTimeoutWithData",
                    new Class<?>[]{String.class, int.class, byte[].class, boolean.class, callbackClass, int.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("direct sync Engine.callTimeoutWithData did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", type=" + chain.getArg(1)
                                    + ", data=" + describeBytes((byte[]) chain.getArg(2), 32)
                                    + ", needResponse=" + chain.getArg(3)
                                    + ", timeout=" + chain.getArg(5)
                                    + ", result=" + result);
                        }
                    });
            hookAfter(engineClass, "sendFile",
                    new Class<?>[]{String.class, int.class, int.class, String.class, int.class, fileCallbackClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("direct sync Engine.sendFile did=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", type=" + chain.getArg(1)
                                    + ", detailType=" + chain.getArg(2)
                                    + ", path=" + chain.getArg(3)
                                    + ", segmentLength=" + chain.getArg(4)
                                    + ", result=" + result);
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("direct sync engine diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> adapterClass = findClass("com.xiaomi.fitness.device.contact.DeviceDataHandlerAdapter", classLoader);
            hookAfter(adapterClass, "handleDataInternal", new Class<?>[]{String.class, int.class, byte[].class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync adapter.handleData did=" + maskDid(String.valueOf(chain.getArg(0)))
                            + ", type=" + chain.getArg(1)
                            + ", data=" + describeBytes((byte[]) chain.getArg(2), 32)
                            + ", handled=" + result);
                }
            });
            hookAfter(adapterClass, "handlePacketInternal", new Class<?>[]{String.class, int.class, byte[].class}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync adapter.handlePacket did=" + maskDid(String.valueOf(chain.getArg(0)))
                            + ", type=" + chain.getArg(1)
                            + ", data=" + describeBytes((byte[]) chain.getArg(2), 32)
                            + ", handled=" + result);
                }
            });
        } catch (Throwable throwable) {
            debugSleepLine("direct sync adapter diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> bleHandlerClass = findClass("com.xiaomi.fitness.sync.BleDataHandler", classLoader);
            Class<?> packetClass = findFirstClass(classLoader, "ixs", "defpackage.ixs");
            hookAfter(bleHandlerClass, "handlePacket", new Class<?>[]{String.class, int.class, packetClass}, new AfterHook() {
                @Override
                public void after(XposedInterface.Chain chain, Object result) {
                    debugSleepLine("direct sync BleDataHandler.handlePacket did=" + maskDid(String.valueOf(chain.getArg(0)))
                            + ", type=" + chain.getArg(1)
                            + ", packet=" + describePacketObject(chain.getArg(2))
                            + ", handled=" + result);
                }
            });
        } catch (Throwable throwable) {
            debugSleepLine("direct sync BleDataHandler diagnostics unavailable: " + describeThrowable(throwable));
        }
    }

    private String describeDirectSyncDevice(Object device) {
        if (device == null) {
            return "null";
        }
        return "Device{did=" + maskDid(String.valueOf(safeInvokeObject(device, "getDid")))
                + ", name=" + describeDeviceModel(device)
                + ", connected=" + safeInvoke(device, "getIsDeviceConnected")
                + ", status=" + safeInvoke(device, "getDeviceStatus")
                + ", class=" + device.getClass().getName()
                + "}";
    }

    private String describePacketObject(Object packet) {
        if (packet == null) {
            return "null";
        }
        return "Packet{class=" + packet.getClass().getName()
                + ", type=" + safeInvoke(packet, "getType")
                + ", e=" + safeField(packet, "e")
                + ", payload=" + describePacketPayload(packet)
                + ", text=" + shortObject(packet)
                + "}";
    }

    private String describePacketPayload(Object packet) {
        if (packet == null) {
            return "null";
        }
        String[] methods = {"G", "H", "I", "K", "M", "N", "toByteArray", "getData", "getPayload"};
        for (String method : methods) {
            Object value = safeInvokeObject(packet, method);
            if (value instanceof byte[]) {
                return method + "=" + describeBytes((byte[]) value, 32);
            }
        }
        return "unknown";
    }

    private String describeBytes(byte[] bytes, int maxBytes) {
        if (bytes == null) {
            return "null";
        }
        int count = Math.min(bytes.length, Math.max(0, maxBytes));
        StringBuilder builder = new StringBuilder("bytes(len=").append(bytes.length).append(", head=");
        for (int i = 0; i < count; i++) {
            int value = bytes[i] & 0xff;
            if (value < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(value));
        }
        if (bytes.length > count) {
            builder.append("...");
        }
        return builder.append(')').toString();
    }

    private String describeFitnessSyncChunk(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        String header = "";
        if (bytes.length >= 4) {
            int total = littleEndianU16(bytes, 0);
            int sequence = littleEndianU16(bytes, 2);
            header = "total=" + total + ", sequence=" + sequence + ", ";
        }
        return "Chunk{" + header + describeBytes(bytes, 24) + "}";
    }

    private int littleEndianU16(byte[] bytes, int offset) {
        if (bytes == null || bytes.length < offset + 2) {
            return -1;
        }
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private String describeFitnessDataId(Object dataId) {
        if (dataId == null) {
            return "null";
        }
        byte[] bytes = fitnessDataIdBytesQuiet(dataId);
        return "FitnessDataId{dataType=" + safeInvoke(dataId, "getDataType")
                + ", dailyType=" + safeInvoke(dataId, "getDailyType")
                + ", sportType=" + safeInvoke(dataId, "getSportType")
                + ", fileType=" + safeInvoke(dataId, "getFileType")
                + ", time=" + safeInvoke(dataId, "getTimeStamp")
                + ", tz=" + safeInvoke(dataId, "getTzOffsetInSec")
                + ", version=" + safeInvoke(dataId, "getVersion")
                + ", bytes=" + (bytes == null ? "null" : hex(bytes))
                + ", text=" + shortObject(dataId)
                + "}";
    }

    private String describeFieldList(Object target, String fieldName) {
        Object value = getFieldValueQuietly(target, fieldName);
        if (value == null) {
            return "null";
        }
        return describeListShort(value, 8);
    }

    private String safeField(Object target, String fieldName) {
        Object value = getFieldValueQuietly(target, fieldName);
        return value == null ? "null" : safeString(String.valueOf(value));
    }

    private void hookSleepAggregateDiagnostics(final ClassLoader classLoader) {
        try {
            Class<?> dataTypeClass = findClass("com.xiaomi.fit.fitness.export.data.annotation.HomeDataType", classLoader);
            Class<?> reportClass = findClass("com.xiaomi.fit.fitness.export.data.aggregation.DailyBasicReport", classLoader);
            Class<?> reportArrayClass = Array.newInstance(reportClass, 0).getClass();
            Class<?> proxyClass = findClass("com.xiaomi.fitness.aggregation.health.dao.FitnessDailyReportDaoProxy", classLoader);
            hookAfter(proxyClass, "insert",
                    new Class<?>[]{dataTypeClass, reportArrayClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataType = chain.getArg(0);
                            if (!isSleepHomeDataType(dataType)) {
                                return;
                            }
                            debugSleepLine("daily aggregate insert dataType=" + dataType
                                    + ", reports=" + describeDailyBasicReports(chain.getArg(1), 4)
                                    + ", result=" + result
                                    + ", stack=" + compactStackTrace(10));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("sleep aggregate dao diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> dataTypeClass = findClass("com.xiaomi.fit.fitness.export.data.annotation.HomeDataType", classLoader);
            Class<?> utilsClass = findClass("com.xiaomi.fit.fitness.persist.db.utils.DailyRecordDaoUtils", classLoader);
            hookAfter(utilsClass, "requestFromServer",
                    new Class<?>[]{dataTypeClass, long.class, Long.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object dataType = chain.getArg(0);
                            if (!isSleepHomeDataType(dataType)) {
                                return;
                            }
                            debugSleepLine("daily aggregate requestFromServer dataType=" + dataType
                                    + ", start=" + chain.getArg(1)
                                    + ", end=" + chain.getArg(2)
                                    + ", result=" + result
                                    + ", stack=" + compactStackTrace(8));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("sleep aggregate server-request diagnostics unavailable: " + describeThrowable(throwable));
        }

        try {
            Class<?> sleepBizClass = findClass("com.xiaomi.fitness.repo.sleep.SleepBiz", classLoader);
            Class<?> relativeDataModelClass =
                    findClass("com.xiaomi.fit.fitness.persist.server.data.RelativeDataModel", classLoader);
            hookAfter(sleepBizClass, "convertServerAggregateReport",
                    new Class<?>[]{long.class, String.class, relativeDataModelClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("SleepBiz.convertServerAggregateReport time=" + chain.getArg(0)
                                    + ", viewTag=" + chain.getArg(1)
                                    + ", dataModel=" + describeRelativeDataModel(chain.getArg(2))
                                    + ", result=" + describeAllDaySleepReport(result)
                                    + ", stack=" + compactStackTrace(8));
                        }
                    });
            hookAfter(sleepBizClass, "splitDailyReport",
                    new Class<?>[]{String.class, String.class, long.class, java.util.Map.class},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object itemMap = chain.getArg(3);
                            if (!containsSleepRecordKey(itemMap)) {
                                return;
                            }
                            debugSleepLine("SleepBiz.splitDailyReport sid=" + maskDid(String.valueOf(chain.getArg(0)))
                                    + ", viewTag=" + chain.getArg(1)
                                    + ", time=" + chain.getArg(2)
                                    + ", keys=" + describeMapKeys(itemMap)
                                    + ", result=" + describeAllDaySleepReport(result)
                                    + ", stack=" + compactStackTrace(8));
                        }
                    });
        } catch (Throwable throwable) {
            debugSleepLine("sleep biz aggregate diagnostics unavailable: " + describeThrowable(throwable));
        }
    }

    private void hookSleepServerDiagnostics(final ClassLoader classLoader) {
        try {
            Class<?> requestClass = findClass("com.xiaomi.fit.fitness.persist.server.FitnessDataRequest", classLoader);
            Class<?> continuationClass = findClass("kotlin.coroutines.Continuation", classLoader);
            Class<?> aggregateTimeParamClass =
                    findClass("com.xiaomi.fit.fitness.persist.dailyreport.bean.AggregateFitnessDataByTimeParam", classLoader);
            hookAfter(requestClass, "getAggregatedDataByTime",
                    new Class<?>[]{aggregateTimeParamClass, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object param = chain.getArg(0);
                            if (!looksSleepRelated(param)) {
                                return;
                            }
                            debugSleepLine("server getAggregatedDataByTime param=" + shortObject(param)
                                    + ", result=" + describeCoroutineResult(result));
                        }
                    });
            hookAfter(requestClass, "getAggregatedDataByWM",
                    new Class<?>[]{String.class, long.class, int.class, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            String tag = String.valueOf(chain.getArg(0));
                            if (!containsSleepText(tag)) {
                                return;
                            }
                            debugSleepLine("server getAggregatedDataByWM tag=" + tag
                                    + ", watermark=" + chain.getArg(1)
                                    + ", limit=" + chain.getArg(2)
                                    + ", result=" + describeCoroutineResult(result));
                        }
                    });
            Class<?> latestModelClass =
                    findClass("com.xiaomi.fit.fitness.persist.server.data.LatestFitnessDataModel", classLoader);
            hookAfter(requestClass, "getLatestFitnessData",
                    new Class<?>[]{java.util.List.class, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object requestDataList = chain.getArg(0);
                            if (!looksSleepRelated(requestDataList)) {
                                return;
                            }
                            debugSleepLine("server getLatestFitnessData request=" + describeListShort(requestDataList, 6)
                                    + ", result=" + describeCoroutineResult(result));
                        }
                    });
            Class<?> fitnessTimeParamClass =
                    findClass("com.xiaomi.fit.fitness.persist.server.data.GetFitnessDataByTime", classLoader);
            hookAfter(requestClass, "getFitnessDataByTime",
                    new Class<?>[]{fitnessTimeParamClass, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            Object param = chain.getArg(0);
                            if (!looksSleepRelated(param)) {
                                return;
                            }
                            debugSleepLine("server getFitnessDataByTime param=" + shortObject(param)
                                    + ", result=" + describeCoroutineResult(result));
                        }
                    });
            hookAfter(requestClass, "getFitnessDataByWM",
                    new Class<?>[]{long.class, continuationClass},
                    new AfterHook() {
                        @Override
                        public void after(XposedInterface.Chain chain, Object result) {
                            debugSleepLine("server getFitnessDataByWM watermark=" + chain.getArg(0)
                                    + ", result=" + describeCoroutineResult(result));
                        }
                    });
            if (latestModelClass == null) {
                debugSleepLine("server diagnostics impossible: latestModelClass null");
            }
        } catch (Throwable throwable) {
            debugSleepLine("sleep server diagnostics unavailable: " + describeThrowable(throwable));
        }
    }

    private void maybeScheduleDebugSleepOnlySync(Object dataId) {
        if (!DebugBuild.ENABLED || dataId == null || !isSleepFitnessDataId(dataId)) {
            return;
        }
        final byte[] dataIdBytes = fitnessDataIdBytes(dataId);
        if (dataIdBytes == null || dataIdBytes.length != 7) {
            debugSleepLine("sleep-only candidate ignored: invalid dataId bytes, dataId=" + dataId);
            return;
        }
        int hash = Arrays.hashCode(dataIdBytes);
        long elapsed = SystemClock.elapsedRealtime();
        if (lastDebugSleepOnlySyncElapsedMs > 0L
                && elapsed - lastDebugSleepOnlySyncElapsedMs < DEBUG_SLEEP_ONLY_SYNC_MIN_GAP_MS
                && hash == lastDebugSleepOnlySyncHash) {
            debugSleepLine("sleep-only candidate skipped: recent same dataId=" + dataId
                    + ", ageMs=" + (elapsed - lastDebugSleepOnlySyncElapsedMs));
            return;
        }
        if (!debugSleepOnlySyncPending.compareAndSet(false, true)) {
            debugSleepLine("sleep-only candidate skipped: pending exists dataId=" + dataId);
            return;
        }
        lastDebugSleepOnlySyncElapsedMs = elapsed;
        lastDebugSleepOnlySyncHash = hash;
        debugSleepLine("sleep-only candidate captured dataId=" + dataId
                + ", bytes=" + hex(dataIdBytes)
                + ", delayMs=" + DEBUG_SLEEP_ONLY_SYNC_DELAY_MS);
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(DEBUG_SLEEP_ONLY_SYNC_DELAY_MS);
                    ClassLoader classLoader = targetClassLoader;
                    if (classLoader == null) {
                        debugSleepLine("sleep-only sync skipped: classLoader null");
                        return;
                    }
                    Object device = getCurrentDeviceModel(classLoader);
                    String did = getCurrentDeviceId(device);
                    if (did == null || did.length() == 0) {
                        debugSleepLine("sleep-only sync skipped: current did null");
                        return;
                    }
                    triggerDebugSleepOnlySync(classLoader, did, dataIdBytes);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    debugSleepLine("sleep-only sync interrupted");
                } catch (Throwable throwable) {
                    debugSleepLine("sleep-only sync failed: " + describeThrowable(throwable));
                } finally {
                    debugSleepOnlySyncPending.set(false);
                }
            }
        });
    }

    private boolean isSleepFitnessDataId(Object dataId) {
        try {
            Object dataType = callMethod(dataId, "getDataType");
            Object dailyType = callMethod(dataId, "getDailyType");
            int daily = dailyType instanceof Number ? ((Number) dailyType).intValue() : -1;
            return dataType instanceof Number
                    && ((Number) dataType).intValue() == 0
                    && (daily == 7 || daily == 8);
        } catch (Throwable throwable) {
            debugSleepLine("sleep-only candidate inspect failed: " + describeThrowable(throwable)
                    + ", dataId=" + dataId);
            return false;
        }
    }

    private boolean isSleepFitnessDataIdQuiet(Object dataId) {
        try {
            Object dataType = callMethod(dataId, "getDataType");
            Object dailyType = callMethod(dataId, "getDailyType");
            int daily = dailyType instanceof Number ? ((Number) dailyType).intValue() : -1;
            return dataType instanceof Number
                    && ((Number) dataType).intValue() == 0
                    && (daily == 7 || daily == 8);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private byte[] fitnessDataIdBytes(Object dataId) {
        try {
            Object bytes = callMethod(dataId, "toByteArray");
            if (bytes instanceof byte[]) {
                return (byte[]) bytes;
            }
        } catch (Throwable throwable) {
            debugSleepLine("sleep-only candidate toByteArray failed: " + describeThrowable(throwable));
        }
        return null;
    }

    private byte[] fitnessDataIdBytesQuiet(Object dataId) {
        try {
            Object bytes = callMethod(dataId, "toByteArray");
            if (bytes instanceof byte[]) {
                return (byte[]) bytes;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void triggerDebugSleepOnlySync(ClassLoader classLoader, String did, byte[] dataIdBytes) throws Exception {
        Class<?> syncerClass = findClass("com.xiaomi.fit.fitness.sync.export.api.FitnessSyncer", classLoader);
        Object companion = getStaticObjectField(syncerClass, "INSTANCE", "Companion");
        Class<?> extClass = findClass("com.xiaomi.fit.fitness.sync.export.di.FitnessSyncExtKt", classLoader);
        Object syncer = callStaticMethod(extClass, "getInstance", companion);
        Object callback = newDebugSleepOnlySyncCallback(classLoader, did, dataIdBytes);
        debugSleepLine("sleep-only sync request did=" + maskDid(did)
                + ", bytes=" + hex(dataIdBytes)
                + ", syncer=" + describeObjectForDebug(syncer));
        callMethod(syncer, "syncData", did, dataIdBytes, callback);
    }

    private Object newDebugSleepOnlySyncCallback(ClassLoader classLoader, final String did, final byte[] dataIdBytes)
            throws ClassNotFoundException {
        Class<?> callbackClass = findClass("com.xiaomi.fitness.device.contact.export.DeviceSyncCallback", classLoader);
        return Proxy.newProxyInstance(classLoader, new Class<?>[]{callbackClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "HeartwithSleepOnlySyncCallback";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                }
                debugSleepLine("sleep-only sync callback method=" + name
                        + ", did=" + maskDid(did)
                        + ", bytes=" + hex(dataIdBytes)
                        + ", args=" + describeArgs(args));
                if (!"onSyncSuccess".equals(name)) {
                    writeDebugSleepStatus("睡眠同步状态：" + name, describeArgs(args));
                }
                return null;
            }
        });
    }

    private void requestDebugTodaySleepIds(final ClassLoader classLoader, final String did, final String reason) {
        if (!DebugBuild.ENABLED || did == null || did.length() == 0) {
            return;
        }
        if (!isWorkerProcess()) {
            debugSleepLine("today sleep ids skipped: process=" + processName + ", reason=" + reason);
            return;
        }
        final boolean diagnosticOnly = isDebugSleepSyncReason(reason);
        if (!debugTodaySleepIdsPending.compareAndSet(false, true)) {
            debugSleepLine("today sleep ids skipped: pending exists, reason=" + reason);
            if (!diagnosticOnly) {
                writeDebugSleepStatus("正在获取睡眠数据", "已有睡眠请求正在执行，请稍候。");
            }
            return;
        }
        if (!diagnosticOnly) {
            writeDebugSleepStatus("正在获取睡眠数据", "正在向小米健康请求今天的 fitness ids。");
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Object pbApi = newWearFitnessPbApi(classLoader);
                    Object callback = newDebugTodayIdsCallback(classLoader, did, "wear", reason);
                    debugSleepLine("today sleep ids request api=wear, did=" + maskDid(did)
                            + ", reason=" + reason
                            + ", pb=" + describeObjectForDebug(pbApi));
                    callMethod(pbApi, "getTodayFitnessIds", did, Boolean.TRUE, callback);
                } catch (Throwable wearThrowable) {
                    debugSleepLine("today sleep ids wear request failed: " + describeThrowable(wearThrowable)
                            + ", reason=" + reason);
                    try {
                        Object pbApi = newEcoFitnessPbApi(classLoader);
                        Object callback = newDebugTodayIdsCallback(classLoader, did, "eco", reason);
                        debugSleepLine("today sleep ids request api=eco, did=" + maskDid(did)
                                + ", reason=" + reason
                                + ", pb=" + describeObjectForDebug(pbApi));
                        callMethod(pbApi, "getTodayFitnessIds", did, Boolean.TRUE, callback);
                    } catch (Throwable ecoThrowable) {
                        debugSleepLine("today sleep ids eco request failed: " + describeThrowable(ecoThrowable)
                                + ", reason=" + reason);
                        writeDebugSleepStatus("获取睡眠失败", "请求 today fitness ids 失败："
                                + describeThrowable(ecoThrowable));
                        debugTodaySleepIdsPending.set(false);
                    }
                }
            }
        });
    }

    private void requestTodaySleepIds(final ClassLoader classLoader, final String did, final String reason) {
        if (did == null || did.length() == 0 || !isWorkerProcess()) {
            debugSleepStateLine("raw-ids-skip", reason, null,
                    "did=" + maskDid(did) + ", worker=" + isWorkerProcess());
            return;
        }
        if (!sleepStatusTodayIdsPending.compareAndSet(false, true)) {
            debugSleepStateLine("raw-ids-skip", reason, null, "pending=true");
            return;
        }
        debugSleepStateLine("raw-ids-request", reason, null, "api=wear, did=" + maskDid(did));
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Object pbApi = newWearFitnessPbApi(classLoader);
                    Object callback = newTodaySleepIdsCallback(classLoader, did, "wear", reason);
                    callMethod(pbApi, "getTodayFitnessIds", did, Boolean.TRUE, callback);
                } catch (Throwable wearThrowable) {
                    debugSleepStateLine("raw-ids-request-fallback", reason, null,
                            "wearFailed=" + describeThrowable(wearThrowable) + ", api=eco");
                    try {
                        Object pbApi = newEcoFitnessPbApi(classLoader);
                        Object callback = newTodaySleepIdsCallback(classLoader, did, "eco", reason);
                        callMethod(pbApi, "getTodayFitnessIds", did, Boolean.TRUE, callback);
                    } catch (Throwable ecoThrowable) {
                        sleepStatusTodayIdsPending.set(false);
                        debugSleepStateLine("raw-ids-request-failed", reason, null,
                                "ecoFailed=" + describeThrowable(ecoThrowable));
                        requestSleepRepositoryReports(classLoader, did, "raw-ids-request-failed:" + reason);
                        if (DebugBuild.ENABLED) {
                            debugSleepLine("sleep status today ids failed: " + describeThrowable(ecoThrowable));
                        }
                    }
                }
            }
        });
    }

    private Object newTodaySleepIdsCallback(final ClassLoader classLoader,
                                            final String did,
                                            final String api,
                                            final String reason)
            throws ClassNotFoundException {
        Class<?> callbackClass = findClass("com.xiaomi.fit.fitness.sync.export.api.OnGetFitnessIdsCallback", classLoader);
        return Proxy.newProxyInstance(classLoader, new Class<?>[]{callbackClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "HeartwithSleepStatusIdsCallback";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                }
                try {
                    if ("onSuccess".equals(name)) {
                        byte[] rawIds = args != null && args.length > 0 && args[0] instanceof byte[]
                                ? (byte[]) args[0]
                                : null;
                        byte[] sleepIds = filterSleepDataIds(classLoader, rawIds);
                        int rawLen = rawIds == null ? 0 : rawIds.length;
                        int rawCount = rawIds == null ? 0 : rawIds.length / 7;
                        int rawTrailing = rawIds == null ? 0 : rawIds.length % 7;
                        int sleepCount = sleepIds == null ? 0 : sleepIds.length / 7;
                        debugSleepStateLine("raw-ids-success", api + ":" + reason, null,
                                "rawLen=" + rawLen
                                        + ", rawCount=" + rawCount
                                        + ", trailing=" + rawTrailing
                                        + ", sleepCount=" + sleepCount
                                        + ", sleepBytes=" + hex(sleepIds));
                        if (sleepIds != null && sleepIds.length > 0) {
                            triggerSleepOnlySync(classLoader, did, sleepIds, api + ":" + reason);
                        } else {
                            debugSleepStateLine("raw-ids-empty", api + ":" + reason, null,
                                    "fallback=repository");
                            requestSleepRepositoryReports(classLoader, did, "raw-ids-empty:" + api + ":" + reason);
                        }
                    } else if ("onError".equals(name)) {
                        debugSleepStateLine("raw-ids-error", api + ":" + reason, null,
                                "args=" + describeArgs(args));
                        requestSleepRepositoryReports(classLoader, did, "raw-ids-error:" + api + ":" + reason);
                        if (DebugBuild.ENABLED) {
                            debugSleepLine("sleep status ids error api=" + api + ", args=" + describeArgs(args));
                        }
                    }
                } catch (Throwable throwable) {
                    debugSleepStateLine("raw-ids-callback-failed", api + ":" + reason, null,
                            describeThrowable(throwable));
                    requestSleepRepositoryReports(classLoader, did, "raw-ids-callback-failed:" + api + ":" + reason);
                    if (DebugBuild.ENABLED) {
                        debugSleepLine("sleep status ids callback failed: " + describeThrowable(throwable));
                    }
                } finally {
                    if ("onSuccess".equals(name) || "onError".equals(name)) {
                        sleepStatusTodayIdsPending.set(false);
                    }
                }
                return null;
            }
        });
    }

    private void triggerSleepOnlySync(ClassLoader classLoader, String did, byte[] dataIdBytes, String reason) throws Exception {
        if (dataIdBytes == null || dataIdBytes.length == 0) {
            debugSleepStateLine("raw-sync-skip", reason, null, "ids=0");
            return;
        }
        Class<?> syncerClass = findClass("com.xiaomi.fit.fitness.sync.export.api.FitnessSyncer", classLoader);
        Object companion = getStaticObjectField(syncerClass, "INSTANCE", "Companion");
        Class<?> extClass = findClass("com.xiaomi.fit.fitness.sync.export.di.FitnessSyncExtKt", classLoader);
        Object syncer = callStaticMethod(extClass, "getInstance", companion);
        Object callback = newSleepOnlySyncCallback(classLoader, did, reason);
        callMethod(syncer, "syncData", did, dataIdBytes, callback);
        debugSleepStateLine("raw-sync-request", reason, null,
                "did=" + maskDid(did) + ", ids=" + dataIdBytes.length / 7);
        if (DebugBuild.ENABLED) {
            debugSleepLine("sleep status sync requested did=" + maskDid(did)
                    + ", ids=" + dataIdBytes.length / 7
                    + ", reason=" + reason);
        }
    }

    private Object newSleepOnlySyncCallback(ClassLoader classLoader, final String did, final String reason)
            throws ClassNotFoundException {
        Class<?> callbackClass = findClass("com.xiaomi.fitness.device.contact.export.DeviceSyncCallback", classLoader);
        return Proxy.newProxyInstance(classLoader, new Class<?>[]{callbackClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (DebugBuild.ENABLED && !"toString".equals(method.getName())
                        && !"hashCode".equals(method.getName())
                        && !"equals".equals(method.getName())) {
                    debugSleepLine("sleep status sync callback method=" + method.getName()
                            + ", did=" + maskDid(did)
                            + ", reason=" + reason
                            + ", args=" + describeArgs(args));
                }
                if ("toString".equals(method.getName())) {
                    return "HeartwithSleepStatusSyncCallback";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                }
                if ("onError".equals(method.getName())) {
                    debugSleepStateLine("raw-sync-error", reason, null,
                            "did=" + maskDid(did) + ", args=" + describeArgs(args));
                    requestSleepRepositoryReports(classLoader, did, "raw-sync-error:" + reason);
                }
                return null;
            }
        });
    }

    private void requestSleepRepositoryReports(final ClassLoader classLoader,
                                               final String did,
                                               final String reason) {
        if (did == null || did.length() == 0) {
            debugSleepStateLine("repository-skip", reason, null, "did=null");
            return;
        }
        if (!shouldRequestSleepRepository(reason)) {
            return;
        }
        if (!sleepStatusRepositoryPending.compareAndSet(false, true)) {
            debugSleepStateLine("repository-skip", reason, null, "pending=true");
            return;
        }
        debugSleepStateLine("repository-request", reason, null, "did=" + maskDid(did));
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> repoManagerClass = findClass("com.xiaomi.fitness.repo.RepositoryManager", classLoader);
                    Class<?> sleepRepoClass =
                            findClass("com.xiaomi.fit.fitness.export.api.repository.ISleepRepository", classLoader);
                    Object repoManager = newInstance(repoManagerClass);
                    Object sleepRepo = callMethod(repoManager, "getRepository", sleepRepoClass);
                    long beginInSecond = todayStartSeconds();
                    Object reports = callMethod(sleepRepo, "getDailyReportListSync",
                            did, beginInSecond, 10, 1);
                    Object latest = selectLatestSleepReport(reports);
                    debugSleepStateLine("repository-result", reason, null,
                            "latest=" + describeAllDaySleepReport(latest));
                    publishSleepStatusFromReport("sleep-repository", latest);
                    if (DebugBuild.ENABLED) {
                        debugSleepLine("sleep status repository reason=" + reason
                                + ", latest=" + describeAllDaySleepReport(latest));
                    }
                } catch (Throwable throwable) {
                    debugSleepStateLine("repository-failed", reason, null, describeThrowable(throwable));
                    if (DebugBuild.ENABLED) {
                        debugSleepLine("sleep status repository failed: " + describeThrowable(throwable));
                    }
                } finally {
                    sleepStatusRepositoryPending.set(false);
                }
            }
        });
    }

    private void requestDebugHistorySleepIds(final ClassLoader classLoader, final String did, final String reason) {
        if (!DebugBuild.ENABLED || did == null || did.length() == 0) {
            return;
        }
        if (!isWorkerProcess()) {
            debugSleepLine("history sleep ids skipped: process=" + processName + ", reason=" + reason);
            return;
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Object pbApi = newWearFitnessPbApi(classLoader);
                    Object callback = newDebugTodayIdsCallback(classLoader, did, "wear-history", reason);
                    debugSleepLine("history sleep ids request api=wear, did=" + maskDid(did)
                            + ", reason=" + reason
                            + ", pb=" + describeObjectForDebug(pbApi));
                    callMethod(pbApi, "getHistoryFitnessIds", did, callback);
                } catch (Throwable wearThrowable) {
                    debugSleepLine("history sleep ids wear request failed: " + describeThrowable(wearThrowable)
                            + ", reason=" + reason);
                    try {
                        Object pbApi = newEcoFitnessPbApi(classLoader);
                        Object callback = newDebugTodayIdsCallback(classLoader, did, "eco-history", reason);
                        debugSleepLine("history sleep ids request api=eco, did=" + maskDid(did)
                                + ", reason=" + reason
                                + ", pb=" + describeObjectForDebug(pbApi));
                        callMethod(pbApi, "getHistoryFitnessIds", did, callback);
                    } catch (Throwable ecoThrowable) {
                        debugSleepLine("history sleep ids eco request failed: " + describeThrowable(ecoThrowable)
                                + ", reason=" + reason);
                    }
                }
            }
        });
    }

    private void requestDebugSleepRepositoryReports(final ClassLoader classLoader,
                                                    final String did,
                                                    final String reason) {
        if (!DebugBuild.ENABLED || did == null || did.length() == 0) {
            return;
        }
        if (!debugSleepRepositoryPending.compareAndSet(false, true)) {
            debugSleepLine("sleep repository skipped: pending exists, reason=" + reason);
            return;
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> repoManagerClass = findClass("com.xiaomi.fitness.repo.RepositoryManager", classLoader);
                    Class<?> sleepRepoClass =
                            findClass("com.xiaomi.fit.fitness.export.api.repository.ISleepRepository", classLoader);
                    Object repoManager = newInstance(repoManagerClass);
                    Object sleepRepo = callMethod(repoManager, "getRepository", sleepRepoClass);
                    long beginInSecond = todayStartSeconds();
                    Object reports = callMethod(sleepRepo, "getDailyReportListSync",
                            did, beginInSecond, 10, 1);
                    Object latest = selectLatestSleepReport(reports);
                    debugSleepLine("sleep repository result did=" + maskDid(did)
                            + ", reason=" + reason
                            + ", reports=" + describeSleepAllDayReports(reports)
                            + ", latest=" + describeAllDaySleepReport(latest));
                    if (latest != null) {
                        writeDebugSleepStatus(debugSleepReportSummary(latest),
                                debugSleepReportDetails("ISleepRepository.getDailyReportListSync", latest));
                        requestDebugCandidateSleepIds(classLoader, did, "repository-latest:" + reason, latest);
                    } else {
                        writeDebugSleepStatus("本地暂无睡眠报告",
                                "小米健康本地睡眠仓库最近 10 天没有可用睡眠报告。\n"
                                        + "已同时触发小米健康同步；如果刚睡醒，请等待同步完成后再点一次。");
                    }
                } catch (Throwable throwable) {
                    debugSleepLine("sleep repository failed: " + describeThrowable(throwable)
                            + ", reason=" + reason);
                    writeDebugSleepStatus("读取睡眠仓库失败", describeThrowable(throwable));
                } finally {
                    debugSleepRepositoryPending.set(false);
                }
            }
        });
    }

    private void requestDebugLocalFdsSleepIds(final ClassLoader classLoader,
                                             final String did,
                                             final String reason) {
        if (!DebugBuild.ENABLED || did == null || did.length() == 0) {
            return;
        }
        if (!isWorkerProcess()) {
            debugSleepLine("local fds sleep skipped: process=" + processName + ", reason=" + reason);
            return;
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> utilsClass =
                            findClass("com.xiaomi.fit.fitness.persist.db.utils.FitnessFDSDataDaoUtils", classLoader);
                    Object utils = getStaticObjectField(utilsClass, "INSTANCE");
                    long since = todayStartSeconds() - 10L * 86_400L;
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    for (int dailyType : new int[]{7, 8}) {
                        Object entities = callMethod(utils, "getDailyFDSIds", did, dailyType, since);
                        debugSleepLine("local fds sleep query did=" + maskDid(did)
                                + ", reason=" + reason
                                + ", dailyType=" + dailyType
                                + ", since=" + since
                                + ", result=" + describeFdsEntities(classLoader, entities, 20));
                        appendFdsEntityIdBytes(output, entities);
                    }
                    byte[] ids = output.toByteArray();
                    if (ids.length > 0) {
                        debugSleepLine("local fds sleep exact ids direct request did=" + maskDid(did)
                                + ", reason=" + reason
                                + ", ids=" + describeFitnessDataIdBytes(classLoader, ids, 24)
                                + ", note=local cache only; not auto-requesting device");
                    }
                } catch (Throwable throwable) {
                    debugSleepLine("local fds sleep query failed: " + describeThrowable(throwable)
                            + ", reason=" + reason);
                }
            }
        });
    }

    private void requestDebugCandidateSleepIds(final ClassLoader classLoader,
                                               final String did,
                                               final String reason,
                                               final Object report) {
        if (!DebugBuild.ENABLED || did == null || did.length() == 0) {
            return;
        }
        if (!isWorkerProcess()) {
            debugSleepLine("candidate sleep ids skipped: process=" + processName + ", reason=" + reason);
            return;
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] candidates = buildDebugSleepCandidateIds(report);
                    if (candidates == null || candidates.length == 0) {
                        debugSleepLine("candidate sleep ids skipped: empty, reason=" + reason);
                        return;
                    }
                    debugSleepLine("candidate sleep ids direct request did=" + maskDid(did)
                            + ", reason=" + reason
                            + ", candidates=" + describeFitnessDataIdBytes(classLoader, candidates, 24));
                    triggerDebugSleepOnlySync(classLoader, did, candidates);
                } catch (Throwable throwable) {
                    debugSleepLine("candidate sleep ids request failed: " + describeThrowable(throwable)
                            + ", reason=" + reason);
                }
            }
        });
    }

    private byte[] buildDebugSleepCandidateIds(Object report) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(7 * 24);
        List<Long> timestamps = new ArrayList<>();
        addPositiveUniqueTimestamp(timestamps, todayStartSeconds());
        addPositiveUniqueTimestamp(timestamps, todayStartSeconds() - 86_400L);
        addPositiveUniqueTimestamp(timestamps, todayStartSeconds() - 172_800L);
        Object primarySegment = report == null ? null : findPrimarySleepSegment(safeInvokeObject(report, "getSleepSegments"));
        addPositiveUniqueTimestamp(timestamps, longInvoke(primarySegment, "getWakeupTime"));
        addPositiveUniqueTimestamp(timestamps, longInvoke(primarySegment, "getDeviceWakeupTime"));
        addPositiveUniqueTimestamp(timestamps, longInvoke(report, "getTime"));
        int tzIn15Min = currentTimeZoneIn15Min();
        for (Long timestamp : timestamps) {
            long ts = timestamp == null ? 0L : timestamp;
            for (int dailyType : new int[]{7, 8}) {
                for (int version = 1; version <= 6; version++) {
                    byte[] id = fitnessDataIdBytes(ts, tzIn15Min, version, dailyType, 0);
                    output.write(id, 0, id.length);
                }
            }
        }
        return output.toByteArray();
    }

    private void addPositiveUniqueTimestamp(List<Long> timestamps, long value) {
        if (value <= 0L) {
            return;
        }
        for (Long existing : timestamps) {
            if (existing != null && existing.longValue() == value) {
                return;
            }
        }
        timestamps.add(value);
    }

    private int currentTimeZoneIn15Min() {
        Calendar calendar = Calendar.getInstance();
        int offsetMs = calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET);
        return offsetMs / (15 * 60 * 1000);
    }

    private byte[] fitnessDataIdBytes(long timestampSeconds,
                                      int tzIn15Min,
                                      int version,
                                      int dailyType,
                                      int fileType) {
        int ts = (int) timestampSeconds;
        return new byte[]{
                (byte) (ts & 0xFF),
                (byte) ((ts >> 8) & 0xFF),
                (byte) ((ts >> 16) & 0xFF),
                (byte) ((ts >> 24) & 0xFF),
                (byte) tzIn15Min,
                (byte) version,
                (byte) ((dailyType << 2) | (fileType & 0x03))
        };
    }

    private Object newDebugTodayIdsCallback(final ClassLoader classLoader, final String did,
                                           final String api, final String reason)
            throws ClassNotFoundException {
        Class<?> callbackClass = findClass("com.xiaomi.fit.fitness.sync.export.api.OnGetFitnessIdsCallback", classLoader);
        return Proxy.newProxyInstance(classLoader, new Class<?>[]{callbackClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "HeartwithTodaySleepIdsCallback";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                }
                try {
                    if ("onSuccess".equals(name)) {
                        byte[] rawIds = args != null && args.length > 0 && args[0] instanceof byte[]
                                ? (byte[]) args[0]
                                : null;
                        byte[] sleepIds = filterSleepDataIds(classLoader, rawIds);
                        int total = rawIds == null ? 0 : rawIds.length / 7;
                        int trailing = rawIds == null ? 0 : rawIds.length % 7;
                        int sleepCount = sleepIds == null ? 0 : sleepIds.length / 7;
                        debugSleepLine("today sleep ids success api=" + api
                                + ", did=" + maskDid(did)
                                + ", reason=" + reason
                                + ", rawLen=" + (rawIds == null ? 0 : rawIds.length)
                                + ", total=" + total
                                + ", trailing=" + trailing
                                + ", sleep=" + sleepCount
                                + ", ids=" + describeFitnessDataIdBytes(classLoader, rawIds, 24)
                                + ", sleepBytes=" + hex(sleepIds));
                        if (sleepIds != null && sleepIds.length > 0) {
                            writeDebugSleepStatus("已找到睡眠数据", "睡眠数据块：" + sleepCount
                                    + " 个\n正在同步并解析。");
                            triggerDebugSleepOnlySync(classLoader, did, sleepIds);
                        } else if (isDebugSleepSyncReason(reason)) {
                            debugSleepLine("today sleep ids has no sleep block; waiting for direct sync parser, reason=" + reason);
                        } else {
                            writeDebugSleepStatus("今天没有睡眠数据", "小米健康返回 " + total
                                    + " 个数据块，但没有 dailyType=8 的睡眠数据。\n"
                                    + describeFitnessDataIdBytes(classLoader, rawIds, 12));
                        }
                    } else if ("onError".equals(name)) {
                        debugSleepLine("today sleep ids error api=" + api
                                + ", did=" + maskDid(did)
                                + ", reason=" + reason
                                + ", args=" + describeArgs(args));
                        writeDebugSleepStatus("获取睡眠失败", "today fitness ids 返回错误：" + describeArgs(args));
                    } else {
                        debugSleepLine("today sleep ids callback method=" + name
                                + ", api=" + api
                                + ", args=" + describeArgs(args));
                    }
                } catch (Throwable throwable) {
                    debugSleepLine("today sleep ids callback failed api=" + api
                            + ", method=" + name
                            + ": " + describeThrowable(throwable));
                } finally {
                    if ("onSuccess".equals(name) || "onError".equals(name)) {
                        debugTodaySleepIdsPending.set(false);
                    }
                }
                return null;
            }
        });
    }

    private boolean isDebugSleepSyncReason(String reason) {
        return reason != null && reason.contains("debug-sleep");
    }

    private byte[] filterSleepDataIds(ClassLoader classLoader, byte[] rawIds) {
        if (rawIds == null || rawIds.length < 7) {
            return null;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(rawIds.length);
        int total = rawIds.length / 7;
        for (int i = 0; i < total; i++) {
            byte[] idBytes = Arrays.copyOfRange(rawIds, i * 7, i * 7 + 7);
            try {
                Object dataId = newFitnessDataId(classLoader, idBytes);
                boolean sleep = isSleepFitnessDataId(dataId);
                debugSleepLine("today fitness id[" + i + "] sleep=" + sleep
                        + ", bytes=" + hex(idBytes)
                        + ", dataId=" + dataId);
                if (sleep) {
                    output.write(idBytes, 0, idBytes.length);
                }
            } catch (Throwable throwable) {
                debugSleepLine("today fitness id[" + i + "] inspect failed bytes=" + hex(idBytes)
                        + ": " + describeThrowable(throwable));
            }
        }
        return output.size() == 0 ? null : output.toByteArray();
    }

    private void appendFdsEntityIdBytes(ByteArrayOutputStream output, Object entities) {
        if (!(entities instanceof Iterable)) {
            return;
        }
        for (Object entity : (Iterable<?>) entities) {
            try {
                Object idBytes = callMethod(entity, "getIdBytes");
                if (idBytes instanceof byte[]) {
                    byte[] bytes = (byte[]) idBytes;
                    output.write(bytes, 0, bytes.length);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private String describeFdsEntities(ClassLoader classLoader, Object entities, int maxItems) {
        if (entities == null) {
            return "null";
        }
        if (!(entities instanceof Iterable)) {
            return shortObject(entities);
        }
        StringBuilder builder = new StringBuilder();
        int total = 0;
        int appended = 0;
        for (Object entity : (Iterable<?>) entities) {
            total++;
            if (appended >= maxItems) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(appended).append(':');
            try {
                Object idBytesValue = callMethod(entity, "getIdBytes");
                byte[] idBytes = idBytesValue instanceof byte[] ? (byte[]) idBytesValue : null;
                Object dataId = idBytes == null ? null : newFitnessDataId(classLoader, idBytes);
                builder.append("time=").append(safeInvoke(entity, "getTime"))
                        .append(",daily=").append(safeInvoke(entity, "getDailyType"))
                        .append(",file=").append(safeInvoke(entity, "getFileType"))
                        .append(",upload=").append(safeInvoke(entity, "isUpload"))
                        .append(",bytes=").append(idBytes == null ? "null" : hex(idBytes))
                        .append(",dataId=").append(dataId);
            } catch (Throwable throwable) {
                builder.append("bad(").append(describeThrowable(throwable)).append(")")
                        .append(",entity=").append(shortObject(entity));
            }
            appended++;
        }
        return "count=" + total + (builder.length() == 0 ? "" : ", items=[" + builder + "]");
    }

    private String describeFitnessDataIdBytes(ClassLoader classLoader, byte[] rawIds, int maxItems) {
        if (rawIds == null) {
            return "null";
        }
        int total = rawIds.length / 7;
        int trailing = rawIds.length % 7;
        int sleep = 0;
        int daily = 0;
        int sport = 0;
        StringBuilder builder = new StringBuilder();
        builder.append("len=").append(rawIds.length)
                .append(", total=").append(total)
                .append(", trailing=").append(trailing);
        int limit = Math.min(total, Math.max(0, maxItems));
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < total; i++) {
            byte[] idBytes = Arrays.copyOfRange(rawIds, i * 7, i * 7 + 7);
            try {
                Object dataId = newFitnessDataId(classLoader, idBytes);
                int dataType = intInvoke(dataId, "getDataType", -1);
                int dailyType = intInvoke(dataId, "getDailyType", -1);
                if (dataType == 0) {
                    daily++;
                } else if (dataType == 1) {
                    sport++;
                }
                if (dataType == 0 && (dailyType == 7 || dailyType == 8)) {
                    sleep++;
                }
                if (i < limit) {
                    if (items.length() > 0) {
                        items.append(" | ");
                    }
                    items.append(i).append(':').append(describeFitnessDataIdForDebug(dataId));
                }
            } catch (Throwable throwable) {
                if (i < limit) {
                    if (items.length() > 0) {
                        items.append(" | ");
                    }
                    items.append(i).append(":bad(").append(hex(idBytes)).append(')');
                }
            }
        }
        builder.append(", daily=").append(daily)
                .append(", sport=").append(sport)
                .append(", sleep=").append(sleep);
        if (items.length() > 0) {
            builder.append(", ids=[").append(items).append(']');
            if (total > limit) {
                builder.append("...");
            }
        }
        return builder.toString();
    }

    private String describeFitnessDataIdForDebug(Object dataId) {
        if (dataId == null) {
            return "null";
        }
        long timestamp = longInvoke(dataId, "getTimeStamp");
        return "time=" + timestamp
                + "(" + formatEpochSeconds(timestamp) + ")"
                + ",dataType=" + safeInvoke(dataId, "getDataType")
                + ",dailyType=" + safeInvoke(dataId, "getDailyType")
                + ",fileType=" + safeInvoke(dataId, "getFileType")
                + ",version=" + safeInvoke(dataId, "getVersion")
                + ",raw=" + shortDebugText(String.valueOf(dataId), 90);
    }

    private Object newFitnessDataId(ClassLoader classLoader, byte[] idBytes) throws Exception {
        Class<?> dataIdClass = findClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId", classLoader);
        Constructor<?> constructor = dataIdClass.getDeclaredConstructor(byte[].class);
        constructor.setAccessible(true);
        return constructor.newInstance((Object) idBytes);
    }

    private Object newWearFitnessPbApi(ClassLoader classLoader) throws Exception {
        Class<?> apiClass = findClass("com.xiaomi.fit.fitness.device.mi.send.FitnessWearPbImpl", classLoader);
        return newInstance(apiClass);
    }

    private Object newEcoFitnessPbApi(ClassLoader classLoader) throws Exception {
        Class<?> apiClass = findClass("com.xiaomi.fit.fitness.eco.device.mi.send.FitnessEcoPbImpl", classLoader);
        try {
            Object companion = getStaticObjectField(apiClass, "INSTANCE", "Companion");
            try {
                return callMethod(companion, "getInstance");
            } catch (Throwable ignored) {
                return companion;
            }
        } catch (Throwable ignored) {
            return newInstance(apiClass);
        }
    }

    private void hookWearRawHandler(final ClassLoader classLoader) {
        try {
            Class<?> adapterClass = findClass("com.xiaomi.fitness.device.contact.DeviceDataHandlerAdapter", classLoader);
            Method method = adapterClass.getDeclaredMethod("handlePacketInternal", String.class, int.class, byte[].class);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    final String source = "wear-raw";
                    if (shouldIgnoreSource(source)) {
                        return chain.proceed();
                    }
                    int type = ((Number) chain.getArg(1)).intValue();
                    Object raw = chain.getArg(2);
                    if (type == 8 && raw instanceof byte[]) {
                        Integer hr = extractHrFromWearRaw(classLoader, (byte[]) raw);
                        diagRawPacket(source, type, raw, hr);
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    } else {
                        diagRawPacket(source, type, raw, null);
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookEcoPacketHandler(final ClassLoader classLoader, final String className) {
        try {
            Class<?> handlerClass = findClass(className, classLoader);
            Class<?> packetClass = findClass("kxs", classLoader);
            Method method = handlerClass.getDeclaredMethod("handleEcoPacket", String.class, int.class, packetClass);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    final String source = "eco-packet";
                    if (shouldIgnoreSource(source)) {
                        return result;
                    }
                    int type = ((Number) chain.getArg(1)).intValue();
                    if (type == 8) {
                        Integer hr = extractHrFromKxs(chain.getArg(2));
                        diagPacket(source, type, chain.getArg(2), hr);
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    }
                    return result;
                }
            });
            if (DebugBuild.ENABLED) {
                diagLine("eco packet hook installed: " + className);
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("eco packet hook unavailable: " + className + ": " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void hookSportPacketHandler(final ClassLoader classLoader, final String className) {
        try {
            Class<?> handlerClass = findClass(className, classLoader);
            Class<?> packetClass = findClass("ixs", classLoader);
            Method method = handlerClass.getDeclaredMethod("handlePacket", String.class, int.class, packetClass);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    final String source = "sport-packet";
                    if (shouldIgnoreSource(source)) {
                        return result;
                    }
                    int type = ((Number) chain.getArg(1)).intValue();
                    if (type == 8) {
                        Object packet = chain.getArg(2);
                        Integer hr = extractHrFromIxs(packet);
                        if (hr == null) {
                            diagPacketShape(source, packet);
                        } else {
                            diagPacket(source, type, packet, hr);
                        }
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    }
                    return result;
                }
            });
            if (DebugBuild.ENABLED) {
                diagLine("sport packet hook installed: " + className);
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("sport packet hook unavailable: " + className + ": " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void hookEcoRawHandler(final ClassLoader classLoader) {
        try {
            Class<?> wrapperClass = findClass(
                    "com.xiaomi.fitness.eco.device.contact.export.EcoDataHandlerWrapper", classLoader);
            Method method = wrapperClass.getDeclaredMethod("handleDataInternal", String.class, int.class, byte[].class);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    final String source = "eco-raw";
                    if (shouldIgnoreSource(source)) {
                        return result;
                    }
                    int type = ((Number) chain.getArg(1)).intValue();
                    Object raw = chain.getArg(2);
                    if (type == 8 && raw instanceof byte[]) {
                        Integer hr = extractHrFromRaw(classLoader, (byte[]) raw);
                        diagRawPacket(source, type, raw, hr);
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    } else {
                        diagRawPacket(source, type, raw, null);
                    }
                    return result;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookEcoRemoteDataHandler(final ClassLoader classLoader) {
        try {
            final Class<?> packetClass = findClass("kxs", classLoader);
            Class<?> handlerClass = findClass(
                    "com.xiaomi.fitness.eco.device.contact.remote.EcoDeviceDataHandler", classLoader);

            Method handleData = handlerClass.getDeclaredMethod("handleData", int.class, byte[].class);
            handleData.setAccessible(true);
            hook(handleData).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    final String source = "eco-remote-raw";
                    if (shouldIgnoreSource(source)) {
                        return chain.proceed();
                    }
                    int type = ((Number) chain.getArg(0)).intValue();
                    Object raw = chain.getArg(1);
                    if (type == 8 && raw instanceof byte[]) {
                        Integer hr = extractHrFromRaw(classLoader, (byte[]) raw);
                        diagRawPacket(source, type, raw, hr);
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    } else {
                        diagRawPacket(source, type, raw, null);
                    }
                    return chain.proceed();
                }
            });

            Method handlePacket = handlerClass.getDeclaredMethod("handlePacket", int.class, packetClass);
            handlePacket.setAccessible(true);
            hook(handlePacket).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    final String source = "eco-remote-packet";
                    if (shouldIgnoreSource(source)) {
                        return chain.proceed();
                    }
                    int type = ((Number) chain.getArg(0)).intValue();
                    if (type == 8) {
                        Integer hr = extractHrFromKxs(chain.getArg(1));
                        diagPacket(source, type, chain.getArg(1), hr);
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookSportWearDataSinks(final ClassLoader classLoader) {
        hookWearSportDataSink(classLoader,
                "com.xiaomi.fitness.sport_manager.receiver.FreeTrainingDataProcesser",
                "nxs",
                "sport-wear-data");
        hookWearSportDataSink(classLoader,
                "com.xiaomi.fitness.sport_manager.receiver.product.BeanDataProcesser",
                "nxs",
                "sport-wear-data");
        hookWearSportDataSink(classLoader,
                "com.xiaomi.fitness.sport_manager.server.SportDataServer",
                "nxs",
                "sport-wear-data");
        hookWearSportDataSink(classLoader, "i16", "nxs", "sport-wear-data");
        hookWearSportDataSink(classLoader, "uk6", "nxs", "sport-wear-data");

        hookWearSportDataSink(classLoader,
                "com.xiaomi.fitness.sport_eco_manager.receiver.FreeTrainingDataProcesser",
                "oxs",
                "eco-wear-data");
        hookWearSportDataSink(classLoader,
                "com.xiaomi.fitness.sport_eco_manager.server.EcoSportDataServer",
                "oxs",
                "eco-wear-data");
        hookWearSportDataSink(classLoader, "j16", "oxs", "eco-wear-data");
        hookWearSportDataSink(classLoader, "vk6", "oxs", "eco-wear-data");
    }

    private void hookWearSportDataSink(
            final ClassLoader classLoader,
            final String className,
            final String dataClassName,
            final String source) {
        try {
            Class<?> targetClass = findClass(className, classLoader);
            Class<?> dataClass = findClass(dataClassName, classLoader);
            Method method = targetClass.getDeclaredMethod("onReceiveWearData", dataClass);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (shouldIgnoreSource(source)) {
                        return result;
                    }
                    Integer hr = extractHrFromWearSportData(chain.getArg(0));
                    if (hr != null) {
                        onHeartRate(hr, source);
                    }
                    return result;
                }
            });
            if (DebugBuild.ENABLED) {
                diagLine("wear sport data hook installed: " + className + "#" + dataClassName);
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("wear sport data hook unavailable: " + className + "#" + dataClassName
                        + ": " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void hookLegacyHuamiHeartRateProfile(final ClassLoader classLoader) {
        try {
            Class<?> profileClass = findClass("twu", classLoader);
            hookLegacyHuamiRawMethod(profileClass, "e");
            hookLegacyHuamiRawMethod(profileClass, "i");
        } catch (Throwable ignored) {
        }
    }

    private void hookLegacyHuamiRawMethod(Class<?> profileClass, final String methodName) throws Exception {
        Method method = profileClass.getDeclaredMethod(methodName, byte[].class);
        method.setAccessible(true);
        hook(method).intercept(new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                final String source = "twu." + methodName;
                if (shouldIgnoreSource(source)) {
                    return chain.proceed();
                }
                Object raw = chain.getArg(0);
                if (raw instanceof byte[]) {
                    byte[] data = (byte[]) raw;
                    if (data.length > 1) {
                        onHeartRate(data[1] & 0xff, source);
                    }
                }
                return chain.proceed();
            }
        });
    }

    private void hookOriginalHuamiHeartRateController(final ClassLoader classLoader) {
        try {
            final Class<?> controllerClass = findClass("buu", classLoader);
            final Class<?> callbackClass = findClass("buu$b", classLoader);
            Method start = controllerClass.getDeclaredMethod("b", callbackClass);
            Method stop = controllerClass.getDeclaredMethod("g");
            start.setAccessible(true);
            stop.setAccessible(true);

            for (Constructor<?> constructor : controllerClass.getDeclaredConstructors()) {
                constructor.setAccessible(true);
                hook(constructor).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        rememberHuamiController(chain.getThisObject());
                        return result;
                    }
                });
            }

            hook(start).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    rememberHuamiController(chain.getThisObject());
                    return chain.proceed();
                }
            });

            hook(stop).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    rememberHuamiController(chain.getThisObject());
                    if (shouldKeepRealtimeHrActive()) {
                        if (DebugBuild.ENABLED) {
                            logLine("suppress huami controller stop");
                        }
                        scheduleRealtimeHrResume("stop:huami-controller");
                        return null;
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookOriginalHuamiBleDevice(final ClassLoader classLoader) {
        try {
            final Class<?> deviceClass = findClass("me", classLoader);
            final Class<?> callbackClass = findClass("buu$b", classLoader);
            Method getController = deviceClass.getDeclaredMethod("f1");
            Method startRealtime = deviceClass.getDeclaredMethod("i1", callbackClass);
            Method stopRealtime = deviceClass.getDeclaredMethod("h1");
            getController.setAccessible(true);
            startRealtime.setAccessible(true);
            stopRealtime.setAccessible(true);

            for (Constructor<?> constructor : deviceClass.getDeclaredConstructors()) {
                constructor.setAccessible(true);
                hook(constructor).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        rememberHuamiBleDevice(chain.getThisObject());
                        return result;
                    }
                });
            }

            hook(getController).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    rememberHuamiBleDevice(chain.getThisObject());
                    Object result = chain.proceed();
                    rememberHuamiController(result);
                    return result;
                }
            });

            hook(startRealtime).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    rememberHuamiBleDevice(chain.getThisObject());
                    return chain.proceed();
                }
            });

            hook(stopRealtime).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    rememberHuamiBleDevice(chain.getThisObject());
                    if (shouldKeepRealtimeHrActive()) {
                        if (DebugBuild.ENABLED) {
                            logLine("suppress huami device stop");
                        }
                        scheduleRealtimeHrResume("stop:huami-device");
                        return null;
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookDeviceHrStopHelpers(final ClassLoader classLoader) {
        for (String helperClassName : START_HELPERS) {
            try {
                Class<?> helperClass = findClass(helperClassName, classLoader);
                for (final Method method : helperClass.getDeclaredMethods()) {
                    if (!isDeviceHrStopMethod(method)) {
                        continue;
                    }
                    method.setAccessible(true);
                    hook(method).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (shouldKeepRealtimeHrActive()) {
                                if (DebugBuild.ENABLED) {
                                    logLine("suppress helper hr stop: " + method.getName());
                                }
                                scheduleRealtimeHrResume("stop:helper-" + method.getName());
                                return defaultValue(method.getReturnType());
                            }
                            return chain.proceed();
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isDeviceHrStopMethod(Method method) {
        String name = method.getName();
        String lower = name.toLowerCase();
        return "stopDeviceHr".equals(name)
                || "unregisterDeviceHr".equals(name)
                || "unRegisterDeviceHr".equals(name)
                || (lower.contains("stop") && (lower.contains("hr") || lower.contains("heart")));
    }

    private boolean shouldKeepRealtimeHrActive() {
        if (!isWorkerProcess()) {
            return false;
        }
        if (started || hrCallback != null || huamiControllerCallback != null) {
            return true;
        }
        return lastHr > 0 || hasRecentHeartRateInAnyProcess();
    }

    private void rememberHuamiController(Object controller) {
        if (controller != null) {
            huamiHrController = new WeakReference<>(controller);
        }
    }

    private void rememberHuamiBleDevice(Object device) {
        if (device != null) {
            huamiBleDevice = new WeakReference<>(device);
        }
    }

    private void hookHuamiCallback(ClassLoader classLoader, final String className) {
        try {
            Class<?> callbackClass = findClass(className, classLoader);
            Method method = callbackClass.getDeclaredMethod("onHeartRateChanged", int.class);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    final String source = "huami";
                    if (!shouldIgnoreSource(source)) {
                        onHeartRate(((Number) chain.getArg(0)).intValue(), source);
                    }
                    return result;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void ensureRealtimeHrStarted(final ClassLoader classLoader, final String reason) {
        if (!heartRateHookEnabled) {
            if (DebugBuild.ENABLED) {
                diagLine("start skipped: hook disabled reason=" + reason + ", process=" + processName);
            }
            return;
        }
        boolean force = isForceStartReason(reason);
        if (!force && !shouldStartNow(reason)) {
            if (DebugBuild.ENABLED) {
                diagLine("start skipped: shouldStartNow=false reason=" + reason
                        + ", process=" + processName
                        + ", started=" + started
                        + ", lastStartReason=" + lastStartReason
                        + ", lastStartAgeMs=" + (SystemClock.elapsedRealtime() - lastStartAt)
                        + ", lastHr=" + lastHr
                        + ", lastHrAgeMs=" + heartRateAgeForDebug()
                        + ", crossProcessRecent=" + hasRecentHeartRateInAnyProcess());
            }
            return;
        }
        if (!starting.compareAndSet(false, true)) {
            if (DebugBuild.ENABLED) {
                diagLine("start skipped: already starting reason=" + reason + ", process=" + processName);
            }
            return;
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    lastStartAt = SystemClock.elapsedRealtime();
                    lastStartReason = reason;
                    final boolean force = isForceStartReason(reason);
                    final boolean allowAggressiveStart = shouldRunStartHelpersForReason(reason, lastStartAt);
                    final boolean forceLaunchRegister =
                            shouldForceLaunchModelRegister(reason, force, allowAggressiveStart);
                    boolean originalDeviceStarted = startOriginalHuamiBleDevice(classLoader);
                    boolean originalControllerStarted = startOriginalHuamiController(classLoader);
                    boolean registered = ensureLaunchModels(classLoader, forceLaunchRegister);
                    boolean deviceStarted = startDeviceRealtimeHr(classLoader, reason, allowAggressiveStart);
                    started = originalDeviceStarted || originalControllerStarted || registered || deviceStarted;
                    if (DebugBuild.ENABLED) {
                        diagLine("start result reason=" + reason
                                + ", bleDevice=" + originalDeviceStarted
                                + ", controller=" + originalControllerStarted
                                + ", launchModels=" + registered
                                + ", forceLaunchRegister=" + forceLaunchRegister
                                + ", allowAggressiveStart=" + allowAggressiveStart
                                + ", deviceHr=" + deviceStarted);
                    }
                    scheduleRetryIfNeeded(classLoader, force);
                } catch (Throwable throwable) {
                    if (DebugBuild.ENABLED) {
                        logLine("start failed: " + describeThrowable(throwable));
                    }
                } finally {
                    starting.set(false);
                }
            }
        });
    }

    private boolean isForceStartReason(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.startsWith("watchdog:") || reason.startsWith("watchdog-alarm:") ||
                reason.startsWith("stop:") || reason.startsWith("device-changed:");
    }

    private boolean shouldForceLaunchModelRegister(
            String reason,
            boolean force,
            boolean allowAggressiveStart
    ) {
        if (reason == null) {
            return false;
        }
        if (reason.startsWith("application:") || reason.startsWith("legacy-kick:") ||
                reason.startsWith("stop:") || reason.startsWith("sport:") ||
                reason.startsWith("device-changed:")) {
            return true;
        }
        if (force || reason.contains("no-heart-rate")) {
            return allowAggressiveStart;
        }
        return false;
    }

    private boolean shouldRunStartHelpersForReason(String reason, long elapsed) {
        if (reason == null) {
            return lastStartHelperScanElapsedMs <= 0L ||
                    elapsed - lastStartHelperScanElapsedMs >= START_HELPER_WATCHDOG_COOLDOWN_MS;
        }
        if (reason.startsWith("application:") || reason.startsWith("legacy-kick:") ||
                reason.startsWith("stop:") || reason.startsWith("device-changed:")) {
            return true;
        }
        if (reason.startsWith("sport:")) {
            return lastSportStartHelperScanElapsedMs <= 0L ||
                    elapsed - lastSportStartHelperScanElapsedMs >= START_HELPER_SPORT_COOLDOWN_MS;
        }
        if (lastStartHelperScanElapsedMs <= 0L) {
            return true;
        }
        if (reason.startsWith("watchdog:") || reason.startsWith("watchdog-alarm:") ||
                reason.startsWith("timer:")) {
            return elapsed - lastStartHelperScanElapsedMs >= START_HELPER_WATCHDOG_COOLDOWN_MS;
        }
        return false;
    }

    private void markStartHelperScan(String reason, String deviceIdentity, long elapsed) {
        lastStartHelperScanElapsedMs = elapsed;
        if (deviceIdentity != null) {
            lastStartHelperDeviceIdentity = deviceIdentity;
        }
        if (reason != null && reason.startsWith("sport:")) {
            lastSportStartHelperScanElapsedMs = elapsed;
        }
    }

    private boolean shouldStartNow(String reason) {
        if (!started) {
            return true;
        }
        if (hasRecentHeartRateInAnyProcess()) {
            noHeartStartAttempts = 0;
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastHr > 0 && now - lastHrElapsedMs < 60_000L) {
            return false;
        }
        if (lastHr > 0 && now - lastHrElapsedMs >= 120_000L) {
            activeSource = null;
        }
        boolean movedPastSplash = lastStartReason.contains("SplashActivity") && !reason.contains("SplashActivity");
        long elapsed = now - lastStartAt;
        return movedPastSplash || elapsed >= 8_000L;
    }

    private void scheduleRetryIfNeeded(final ClassLoader classLoader, boolean forceRetry) {
        final Context context = appContext;
        if (context == null) {
            if (DebugBuild.ENABLED) {
                diagLine("retry skipped: context is null");
            }
            return;
        }
        if (!heartRateHookEnabled) {
            if (DebugBuild.ENABLED) {
                diagLine("retry skipped: hook disabled");
            }
            return;
        }
        if (!isWorkerProcess()) {
            if (DebugBuild.ENABLED) {
                diagLine("retry skipped: not worker process=" + processName);
            }
            return;
        }
        boolean hasRecent = hasRecentHeartRateInAnyProcess(HEART_RATE_WATCHDOG_MS);
        if (!forceRetry && lastHr > 0) {
            if (DebugBuild.ENABLED) {
                diagLine("retry skipped: local heart rate exists lastHr=" + lastHr
                        + ", ageMs=" + heartRateAgeForDebug());
            }
            return;
        }
        if (!forceRetry && hasRecentHeartRateInAnyProcess()) {
            if (DebugBuild.ENABLED) {
                diagLine("retry skipped: cross-process heart rate recent");
            }
            return;
        }
        if (forceRetry && hasRecent) {
            if (DebugBuild.ENABLED) {
                diagLine("retry skipped: watchdog window has recent heart rate");
            }
            return;
        }
        markLegacyKickNeeded();
        noHeartStartAttempts++;
        final String retryReason = forceRetry ? "watchdog:no-heart-rate-retry" : "timer:no-heart-rate";
        maybeScheduleColdStartRecycle();
        final long delayMs = noHeartStartAttempts <= 2 ? 9_000L : 60_000L;
        importantLine("retry scheduled reason=" + retryReason
                + ", delayMs=" + delayMs
                + ", attempts=" + noHeartStartAttempts
                + ", force=" + forceRetry
                + ", localHr=" + lastHr
                + ", ageMs=" + heartRateAgeForDebug());
        if (DebugBuild.ENABLED) {
            diagLine("retry scheduled reason=" + retryReason
                    + ", delayMs=" + delayMs
                    + ", attempts=" + noHeartStartAttempts
                    + ", force=" + forceRetry
                    + ", uptime=" + SystemClock.elapsedRealtime());
        }
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!hasRecentHeartRateInAnyProcess(HEART_RATE_WATCHDOG_MS)) {
                        ensureRealtimeHrStarted(classLoader, retryReason);
                    }
                }
            }, delayMs);
        } catch (Throwable ignored) {
            if (DebugBuild.ENABLED) {
                diagLine("retry schedule failed: " + describeThrowable(ignored));
            }
        }
    }

    private void maybeScheduleColdStartRecycle() {
        final Context context = appContext;
        if (context == null || !isWorkerProcess() || !heartRateHookEnabled) {
            return;
        }
        if (lastHr > 0 || noHeartStartAttempts < 1 || hasRecentHeartRateInAnyProcess()) {
            if (DebugBuild.ENABLED) {
                diagLine("cold-start recycle not scheduled: lastHr=" + lastHr
                        + ", attempts=" + noHeartStartAttempts
                        + ", recent=" + hasRecentHeartRateInAnyProcess());
            }
            return;
        }
        long uptime = SystemClock.elapsedRealtime();
        if (uptime > COLD_START_RECYCLE_MAX_UPTIME_MS) {
            if (DebugBuild.ENABLED) {
                diagLine("cold-start recycle not scheduled: uptime too high " + uptime);
            }
            return;
        }
        if (!coldStartRecycleScheduled.compareAndSet(false, true)) {
            return;
        }
        long delayMs = Math.max(0L, COLD_START_RECYCLE_MIN_UPTIME_MS - uptime);
        importantLine("cold-start recycle scheduled delayMs=" + delayMs
                + ", uptime=" + uptime
                + ", attempts=" + noHeartStartAttempts);
        if (DebugBuild.ENABLED) {
            diagLine("cold-start recycle scheduled delayMs=" + delayMs
                    + ", uptime=" + uptime
                    + ", attempts=" + noHeartStartAttempts);
        }
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    recycleColdStartIfStillStalled(context);
                }
            }, delayMs);
        } catch (Throwable ignored) {
            coldStartRecycleScheduled.set(false);
        }
    }

    private void recycleColdStartIfStillStalled(Context context) {
        if (context == null || !isWorkerProcess() || !heartRateHookEnabled) {
            coldStartRecycleScheduled.set(false);
            return;
        }
        long uptime = SystemClock.elapsedRealtime();
        if (uptime < COLD_START_RECYCLE_MIN_UPTIME_MS ||
                uptime > COLD_START_RECYCLE_MAX_UPTIME_MS ||
                lastHr > 0 ||
                hasRecentHeartRateInAnyProcess()) {
            coldStartRecycleScheduled.set(false);
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE);
            long lastRecycleMs = prefs.getLong(KEY_LAST_COLD_START_RECYCLE_MS, 0L);
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastRecycleMs < COLD_START_RECYCLE_COOLDOWN_MS) {
                coldStartRecycleScheduled.set(false);
                return;
            }
            if (!prefs.edit().putLong(KEY_LAST_COLD_START_RECYCLE_MS, nowMs).commit()) {
                coldStartRecycleScheduled.set(false);
                return;
            }
        } catch (Throwable ignored) {
            coldStartRecycleScheduled.set(false);
            return;
        }
        if (DebugBuild.ENABLED) {
            diagLine("cold-start heart-rate stalled, recycling device process once");
        }
        importantLine("cold-start heart-rate stalled, recycling device process once"
                + ", uptime=" + uptime
                + ", attempts=" + noHeartStartAttempts
                + ", lastHr=" + lastHr);
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    private boolean ensureLaunchModels(ClassLoader classLoader, boolean forceRegister) {
        if (!launchModels.isEmpty()) {
            if (forceRegister) {
                for (Object model : launchModels) {
                    registerLaunchModel(model);
                }
            }
            return true;
        }
        boolean ok = false;
        for (String className : LAUNCH_MODEL_CLASSES) {
            try {
                Class<?> modelClass = findClass(className, classLoader);
                Object model = newInstance(modelClass);
                launchModels.add(model);
                registerLaunchModel(model);
                ok = true;
                if (DebugBuild.ENABLED) {
                    debugLine("launch model registered: " + className);
                }
            } catch (Throwable ignored) {
                if (DebugBuild.ENABLED) {
                    debugLine("launch model unavailable: " + className + ": " + ignored.getClass().getSimpleName());
                }
            }
        }
        return ok;
    }

    private void registerLaunchModel(Object model) {
        if (model == null) {
            return;
        }
        try {
            callMethod(model, "init");
            if (DebugBuild.ENABLED) {
                debugLine("launch model init ok: " + model.getClass().getName());
            }
        } catch (Throwable ignored) {
            if (DebugBuild.ENABLED) {
                debugLine("launch model init failed: " + model.getClass().getName() + ": " + ignored.getClass().getSimpleName());
            }
        }
        try {
            callMethod(model, "registerDeviceHr");
            if (DebugBuild.ENABLED) {
                debugLine("launch model registerDeviceHr ok: " + model.getClass().getName());
            }
        } catch (Throwable ignored) {
            if (DebugBuild.ENABLED) {
                debugLine("launch model registerDeviceHr failed: " + model.getClass().getName() + ": " + ignored.getClass().getSimpleName());
            }
        }
    }

    private boolean startOriginalHuamiBleDevice(ClassLoader classLoader) {
        Object device = huamiBleDevice.get();
        if (device == null) {
            return false;
        }
        try {
            Object callback = getOrCreateHuamiControllerCallback(classLoader);
            callMethod(device, "i1", callback);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean startOriginalHuamiController(ClassLoader classLoader) {
        Object controller = huamiHrController.get();
        if (controller == null) {
            return false;
        }
        try {
            Object callback = getOrCreateHuamiControllerCallback(classLoader);
            callMethod(controller, "b", callback);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean startDeviceRealtimeHr(
            ClassLoader classLoader,
            String reason,
            boolean allowAggressiveStart
    ) {
        Object device = getCurrentDeviceModel(classLoader);
        if (device == null) {
            if (DebugBuild.ENABLED) {
                debugLine("startDeviceRealtimeHr skipped: current device is null"
                        + ", process=" + processName
                        + ", uptime=" + SystemClock.elapsedRealtime()
                        + ", lastStartReason=" + lastStartReason);
            }
            return false;
        }
        updateDeviceModel(device);
        long elapsed = SystemClock.elapsedRealtime();
        String deviceIdentity = describeDeviceIdentity(device);
        String previousDeviceIdentity = lastStartHelperDeviceIdentity;
        boolean hasNewDeviceIdentity = deviceIdentity != null && previousDeviceIdentity == null;
        boolean deviceChanged = deviceIdentity != null && previousDeviceIdentity != null
                && !deviceIdentity.equals(previousDeviceIdentity);
        boolean shouldBypassCooldown = hasNewDeviceIdentity || deviceChanged;
        if (!allowAggressiveStart && !shouldBypassCooldown) {
            if (DebugBuild.ENABLED) {
                debugLine("startDeviceRealtimeHr helper scan skipped: reason=" + reason
                        + ", lastScanAgeMs=" + (lastStartHelperScanElapsedMs > 0L
                        ? elapsed - lastStartHelperScanElapsedMs
                        : -1L)
                        + ", cooldownMs=" + START_HELPER_WATCHDOG_COOLDOWN_MS
                        + ", device=" + describeDeviceForDebug(device));
            }
            return false;
        }
        if (DebugBuild.ENABLED) {
            debugLine("startDeviceRealtimeHr device=" + describeDeviceForDebug(device)
                    + ", reason=" + reason
                    + ", allowAggressiveStart=" + allowAggressiveStart
                    + ", previousDevice=" + previousDeviceIdentity
                    + ", deviceChanged=" + deviceChanged);
        }
        if (shouldBypassCooldown) {
            if (DebugBuild.ENABLED) {
                debugLine("startDeviceRealtimeHr device identity initialized or changed, force launch model register");
            }
            ensureLaunchModels(classLoader, true);
        }
        Object callback = null;
        try {
            callback = getOrCreateHrCallback(classLoader);
            if (DebugBuild.ENABLED) {
                debugLine("startDeviceRealtimeHr callback=" + describeObjectForDebug(callback));
            }
        } catch (Throwable ignored) {
            if (DebugBuild.ENABLED) {
                debugLine("startDeviceRealtimeHr callback failed: " + ignored.getClass().getSimpleName());
            }
        }
        boolean startedAny = false;
        boolean attempted = false;
        boolean exhaustiveHelperScan = shouldBypassCooldown;
        for (String helperClassName : START_HELPERS) {
            attempted = true;
            try {
                Class<?> helperClass = findClass(helperClassName, classLoader);
                callStaticMethod(helperClass, "startDeviceHr", device, callback);
                startedAny = true;
                if (DebugBuild.ENABLED) {
                    debugLine("startDeviceHr helper ok: " + helperClassName
                            + ", exhaustive=" + exhaustiveHelperScan);
                }
                if (!exhaustiveHelperScan) {
                    break;
                }
            } catch (Throwable ignored) {
                if (DebugBuild.ENABLED) {
                    debugLine("startDeviceHr helper failed: " + helperClassName + ": " + ignored.getClass().getSimpleName());
                }
            }
        }
        if (attempted) {
            markStartHelperScan(reason, deviceIdentity, elapsed);
        }
        return startedAny;
    }

    private Object getCurrentDeviceModel(ClassLoader classLoader) {
        try {
            Class<?> managerClass = findClass("com.xiaomi.fitness.device.manager.export.WearableDeviceManager", classLoader);
            Object companion = getStaticObjectField(managerClass, "Companion");
            Class<?> extClass = findClass("com.xiaomi.fitness.device.manager.export.DeviceManagerExtKt", classLoader);
            Object manager = callStaticMethod(extClass, "getInstance", companion);
            Object device = callMethod(manager, "getCurrentDeviceModel");
            if (DebugBuild.ENABLED) {
                debugLine("getCurrentDeviceModel manager=" + describeObjectForDebug(manager)
                        + ", device=" + describeDeviceForDebug(device));
            }
            return device;
        } catch (Throwable ignored) {
            if (DebugBuild.ENABLED) {
                debugLine("getCurrentDeviceModel failed: " + describeThrowable(ignored));
            }
            return null;
        }
    }

    private void updateDeviceModel(Object device) {
        long elapsed = SystemClock.elapsedRealtime();
        String identity = describeDeviceIdentity(device);
        boolean sameDevice = identity != null && identity.equals(currentDeviceIdentity);
        if (sameDevice && currentDeviceModelResolved) {
            return;
        }
        if (sameDevice && lastDeviceModelResolveElapsedMs > 0L &&
                elapsed - lastDeviceModelResolveElapsedMs < DEVICE_MODEL_UNRESOLVED_RETRY_MS) {
            return;
        }
        lastDeviceModelResolveElapsedMs = elapsed;
        String model = describeDeviceModel(device);
        if (model == null) {
            currentDeviceIdentity = identity;
            currentDeviceModelResolved = false;
            return;
        }
        currentDeviceIdentity = identity;
        currentDeviceModelResolved = true;
        if (uploader.setDeviceModel(appContext, model)) {
            resetHeartRateSource("device changed: " + model);
        }
    }

    private void maybeRefreshCurrentDeviceModel(long elapsed) {
        if (!isWorkerProcess() && !isMainProcess()) {
            return;
        }
        if (lastDeviceModelCheckElapsedMs > 0L && elapsed - lastDeviceModelCheckElapsedMs < DEVICE_MODEL_REFRESH_MS) {
            return;
        }
        lastDeviceModelCheckElapsedMs = elapsed;
        ClassLoader classLoader = targetClassLoader;
        if (classLoader == null) {
            return;
        }
        Object device = getCurrentDeviceModel(classLoader);
        if (device != null) {
            updateDeviceModel(device);
        } else if (DebugBuild.ENABLED && !deviceModelNullLogged) {
            deviceModelNullLogged = true;
            if (DebugBuild.ENABLED) {
                diagLine("current device model is null");
            }
        }
    }

    private String describeDeviceIdentity(Object device) {
        if (device == null) {
            return null;
        }
        String did = getCurrentDeviceId(device);
        if (did != null) {
            return "did:" + did;
        }
        return device.getClass().getName() + "@" + System.identityHashCode(device);
    }

    private String describeObjectForDebug(Object object) {
        if (DebugBuild.ENABLED) {
            if (object == null) {
                return "null";
            }
            return object.getClass().getName() + "@" + System.identityHashCode(object)
                    + "/" + safeString(object.toString());
        }
        return "";
    }

    private String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private void logFitnessParseResult(String stage, Object result) {
        if (!DebugBuild.ENABLED || result == null) {
            return;
        }
        try {
            Object allDaySleep = callMethod(result, "getAllDaySleepReport");
            Object nightSleep = callMethod(result, "getNightSleepReport");
            Object daytimeSleep = callMethod(result, "getDaytimeSleepReport");
            if (allDaySleep == null && nightSleep == null && daytimeSleep == null) {
                return;
            }
            debugSleepLine(stage
                    + ", allDay=" + describeAllDaySleep(allDaySleep)
                    + ", night=" + describeNightSleep(nightSleep)
                    + ", daytime=" + describeDaytimeSleep(daytimeSleep));
        } catch (Throwable throwable) {
            debugSleepLine(stage + " parse-result describe failed: " + describeThrowable(throwable));
        }
    }

    private void publishDebugSleepParseResult(String stage, Object dataId, Object result) {
        if (!DebugBuild.ENABLED || result == null) {
            return;
        }
        try {
            Object allDaySleep = allDaySleepObject(result);
            if (allDaySleep == null) {
                return;
            }
            String summary = debugSleepSummary(allDaySleep);
            String details = debugSleepDetails(stage, dataId, allDaySleep);
            writeDebugSleepStatus(summary, details);
        } catch (Throwable throwable) {
            debugSleepLine(stage + " publish sleep result failed: " + describeThrowable(throwable));
        }
    }

    private void writeDebugSleepStatus(String summary, String details) {
        if (!DebugBuild.ENABLED) {
            return;
        }
        HeartwithSleepDebugStatus.writeRemote(appContext, summary, details, System.currentTimeMillis());
    }

    private String debugSleepSummary(Object sleep) {
        long bed = longInvoke(sleep, "getDeviceBedTime");
        long wake = longInvoke(sleep, "getDeviceWakeupTime");
        long durationSeconds = longInvoke(sleep, "getLinBedDuration");
        if (durationSeconds <= 0L && bed > 0L && wake > bed) {
            durationSeconds = wake - bed;
        }
        String duration = durationSeconds > 0L ? formatDurationSeconds(durationSeconds) : "未知时长";
        String finish = booleanInvoke(sleep, "isSleepFinish") ? "已结束" : "未结束";
        return "睡眠 " + duration + " · " + finish;
    }

    private String debugSleepDetails(String stage, Object dataId, Object sleep) {
        long bed = longInvoke(sleep, "getDeviceBedTime");
        long wake = longInvoke(sleep, "getDeviceWakeupTime");
        long goBed = longInvoke(sleep, "getGoBedTime");
        long leaveBed = longInvoke(sleep, "getLeaveBedTime");
        return "设备入睡：" + formatEpochSeconds(bed)
                + "\n设备醒来：" + formatEpochSeconds(wake)
                + "\n上床/离床：" + formatEpochSeconds(goBed) + " - " + formatEpochSeconds(leaveBed)
                + "\n睡眠效率：" + safeInvoke(sleep, "getSleepEfficiency")
                + "\n入睡耗时：" + formatDurationSeconds(longInvoke(sleep, "getEntrySleepDuration"))
                + "\n源数据长度：" + safeInvoke(sleep, "getSleepSrcDataLen")
                + "\n来源：" + stage
                + "\nDataId：" + shortDebugText(String.valueOf(dataId), 110);
    }

    private long todayStartSeconds() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis() / 1000L;
    }

    private Object selectLatestSleepReport(Object reports) {
        if (!(reports instanceof java.util.List)) {
            return null;
        }
        java.util.List<?> list = (java.util.List<?>) reports;
        Object selected = null;
        long selectedTime = Long.MIN_VALUE;
        for (Object report : list) {
            if (report == null || booleanInvoke(report, "isEmpty")) {
                continue;
            }
            long time = longInvoke(report, "getTime");
            long totalDuration = longInvoke(report, "getTotalDuration");
            Object segments = safeInvokeObject(report, "getSleepSegments");
            if (totalDuration <= 0L && collectionSize(segments) <= 0) {
                continue;
            }
            if (selected == null || time >= selectedTime) {
                selected = report;
                selectedTime = time;
            }
        }
        return selected;
    }

    private String debugSleepReportSummary(Object report) {
        long totalMinutes = longInvoke(report, "getTotalDuration");
        String duration = totalMinutes > 0L ? formatDurationMinutes(totalMinutes) : "未知时长";
        long score = longInvoke(report, "getScore");
        String suffix = score > 0L ? " · " + score + "分" : "";
        return "睡眠 " + duration + suffix;
    }

    private String debugSleepReportDetails(String stage, Object report) {
        Object segments = safeInvokeObject(report, "getSleepSegments");
        Object primarySegment = findPrimarySleepSegment(segments);
        StringBuilder builder = new StringBuilder();
        builder.append("报告日期：").append(formatEpochSeconds(longInvoke(report, "getTime"))).append('\n');
        if (primarySegment != null) {
            builder.append("睡眠段：")
                    .append(formatEpochSeconds(longInvoke(primarySegment, "getBedTime")))
                    .append(" - ")
                    .append(formatEpochSeconds(longInvoke(primarySegment, "getWakeupTime")))
                    .append('\n');
            builder.append("设备时间：")
                    .append(formatEpochSeconds(longInvoke(primarySegment, "getDeviceBedTime")))
                    .append(" - ")
                    .append(formatEpochSeconds(longInvoke(primarySegment, "getDeviceWakeupTime")))
                    .append('\n');
        }
        builder.append("总时长：").append(formatDurationMinutes(longInvoke(report, "getTotalDuration"))).append('\n')
                .append("深/浅/REM/清醒：")
                .append(formatDurationMinutes(longInvoke(report, "getDeepDuration"))).append(" / ")
                .append(formatDurationMinutes(longInvoke(report, "getLightDuration"))).append(" / ")
                .append(formatDurationMinutes(longInvoke(report, "getRemDuration"))).append(" / ")
                .append(formatDurationMinutes(longInvoke(report, "getAwakeDuration"))).append('\n')
                .append("评分：").append(safeInvoke(report, "getScore"))
                .append("，平均心率：").append(safeInvoke(report, "getAvgHr"))
                .append("，平均血氧：").append(safeInvoke(report, "getAvgSpo2")).append('\n')
                .append("睡眠段数量：").append(collectionSize(segments)).append('\n')
                .append("来源：").append(stage);
        return builder.toString();
    }

    private Object findPrimarySleepSegment(Object segments) {
        if (!(segments instanceof java.util.List)) {
            return null;
        }
        java.util.List<?> list = (java.util.List<?>) segments;
        Object selected = null;
        long selectedDuration = Long.MIN_VALUE;
        for (Object segment : list) {
            if (segment == null) {
                continue;
            }
            long duration = longInvoke(segment, "getSleepDuration");
            if (duration <= 0L) {
                long bed = longInvoke(segment, "getBedTime");
                long wake = longInvoke(segment, "getWakeupTime");
                if (wake > bed) {
                    duration = (wake - bed) / 60L;
                }
            }
            if (selected == null || duration > selectedDuration) {
                selected = segment;
                selectedDuration = duration;
            }
        }
        return selected;
    }

    private SegmentSleepBounds segmentSleepBounds(Object segments) {
        SegmentSleepBounds bounds = new SegmentSleepBounds();
        if (!(segments instanceof java.util.List)) {
            return bounds;
        }
        java.util.List<?> list = (java.util.List<?>) segments;
        for (Object segment : list) {
            if (segment == null) {
                continue;
            }
            long bedMs = secondsToMillis(longInvoke(segment, "getBedTime"));
            long wakeMs = secondsToMillis(longInvoke(segment, "getWakeupTime"));
            long deviceBedMs = secondsToMillis(longInvoke(segment, "getDeviceBedTime"));
            long deviceWakeMs = secondsToMillis(longInvoke(segment, "getDeviceWakeupTime"));
            long goBedMs = secondsToMillis(longInvoke(segment, "getGoBedTime"));
            long leaveBedMs = secondsToMillis(longInvoke(segment, "getLeaveBedTime"));
            long durationMinutes = longInvoke(segment, "getSleepDuration");
            if (durationMinutes <= 0L && wakeMs > bedMs) {
                durationMinutes = (wakeMs - bedMs) / 60_000L;
            }
            bounds.bedMs = earliestPositiveMillis(bounds.bedMs, bedMs);
            bounds.wakeMs = Math.max(bounds.wakeMs, wakeMs);
            bounds.deviceBedMs = earliestPositiveMillis(bounds.deviceBedMs, deviceBedMs);
            bounds.deviceWakeMs = Math.max(bounds.deviceWakeMs, deviceWakeMs);
            bounds.goBedMs = earliestPositiveMillis(bounds.goBedMs, goBedMs);
            bounds.leaveBedMs = Math.max(bounds.leaveBedMs, leaveBedMs);
            bounds.durationMinutes += Math.max(0L, durationMinutes);
        }
        if (bounds.deviceBedMs == 0L) {
            bounds.deviceBedMs = bounds.bedMs;
        }
        if (bounds.deviceWakeMs == 0L) {
            bounds.deviceWakeMs = bounds.wakeMs;
        }
        return bounds;
    }

    private List<HeartwithSleepStatus.Segment> sleepStatusSegments(Object segments) {
        List<HeartwithSleepStatus.Segment> result = new ArrayList<>();
        if (!(segments instanceof java.util.List)) {
            return result;
        }
        java.util.List<?> list = (java.util.List<?>) segments;
        for (Object segment : list) {
            if (segment == null) {
                continue;
            }
            long bedMs = secondsToMillis(longInvoke(segment, "getBedTime"));
            long wakeMs = secondsToMillis(longInvoke(segment, "getWakeupTime"));
            long deviceBedMs = secondsToMillis(longInvoke(segment, "getDeviceBedTime"));
            long deviceWakeMs = secondsToMillis(longInvoke(segment, "getDeviceWakeupTime"));
            long durationMinutes = longInvoke(segment, "getSleepDuration");
            if (durationMinutes <= 0L && wakeMs > bedMs) {
                durationMinutes = (wakeMs - bedMs) / 60_000L;
            }
            if (bedMs <= 0L && deviceBedMs > 0L) {
                bedMs = deviceBedMs;
            }
            if (wakeMs <= 0L && deviceWakeMs > 0L) {
                wakeMs = deviceWakeMs;
            }
            if (bedMs <= 0L && wakeMs <= 0L && durationMinutes <= 0L) {
                continue;
            }
            long awakeMinutes = longInvoke(segment, "getWakeDuration");
            if (awakeMinutes <= 0L) {
                awakeMinutes = longInvoke(segment, "getAwakeDuration");
            }
            long awakeCount = longInvoke(segment, "getWakeCount");
            if (awakeCount <= 0L) {
                awakeCount = longInvoke(segment, "getAwakeCount");
            }
            long score = longInvoke(segment, "getTotalScore");
            if (score <= 0L) {
                score = longInvoke(segment, "getScore");
            }
            result.add(new HeartwithSleepStatus.Segment(
                    bedMs,
                    wakeMs,
                    deviceBedMs,
                    deviceWakeMs,
                    durationMinutes,
                    longInvoke(segment, "getDeepDuration"),
                    longInvoke(segment, "getLightDuration"),
                    longInvoke(segment, "getRemDuration"),
                    awakeMinutes,
                    awakeCount,
                    score));
        }
        return result;
    }

    private int collectionSize(Object value) {
        if (value instanceof java.util.Collection) {
            return ((java.util.Collection<?>) value).size();
        }
        if (value != null && value.getClass().isArray()) {
            return Array.getLength(value);
        }
        return 0;
    }

    private long longInvoke(Object target, String methodName) {
        Object value = safeInvokeObject(target, methodName);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1L : 0L;
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private boolean booleanInvoke(Object target, String methodName) {
        Object value = safeInvokeObject(target, methodName);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String formatEpochSeconds(long seconds) {
        if (seconds <= 0L) {
            return "未知";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new Date(seconds * 1000L));
    }

    private String formatEpochMillis(long millis) {
        if (millis <= 0L) {
            return "未知";
        }
        return formatEpochSeconds(millis / 1000L);
    }

    private String formatDurationSeconds(long seconds) {
        if (seconds <= 0L) {
            return "未知";
        }
        long minutes = Math.max(1L, seconds / 60L);
        return formatDurationMinutes(minutes);
    }

    private String formatDurationMinutes(long minutes) {
        if (minutes <= 0L) {
            return "未知";
        }
        long hours = minutes / 60L;
        long remain = minutes % 60L;
        if (hours > 0L) {
            return hours + "小时" + remain + "分";
        }
        return minutes + "分钟";
    }

    private String shortDebugText(String value, int limit) {
        if (value == null) {
            return "";
        }
        String safe = safeString(value);
        return safe.length() > limit ? safe.substring(0, limit) + "..." : safe;
    }

    private String describeAllDaySleep(Object sleep) {
        if (sleep == null) {
            return "null";
        }
        return "AllDay{finish=" + safeInvoke(sleep, "isSleepFinish")
                + ", deviceBed=" + safeInvoke(sleep, "getDeviceBedTime")
                + ", deviceWake=" + safeInvoke(sleep, "getDeviceWakeupTime")
                + ", quality=" + safeInvoke(sleep, "getSleepQuality")
                + ", efficiency=" + safeInvoke(sleep, "getSleepEfficiency")
                + ", entryDuration=" + safeInvoke(sleep, "getEntrySleepDuration")
                + ", linBedDuration=" + safeInvoke(sleep, "getLinBedDuration")
                + ", goBed=" + safeInvoke(sleep, "getGoBedTime")
                + ", leaveBed=" + safeInvoke(sleep, "getLeaveBedTime")
                + ", srcLen=" + safeInvoke(sleep, "getSleepSrcDataLen")
                + ", hrInfo=" + shortObject(safeInvokeObject(sleep, "getHrInfo"))
                + ", spo2Info=" + shortObject(safeInvokeObject(sleep, "getSpo2Info"))
                + "}";
    }

    private String describeAllDaySleepReport(Object report) {
        if (report == null) {
            return "null";
        }
        return "AllDayReport{sid=" + safeInvoke(report, "getSid")
                + ", time=" + safeInvoke(report, "getTime")
                + ", total=" + safeInvoke(report, "getTotalDuration")
                + ", deep=" + safeInvoke(report, "getDeepDuration")
                + ", light=" + safeInvoke(report, "getLightDuration")
                + ", rem=" + safeInvoke(report, "getRemDuration")
                + ", awake=" + safeInvoke(report, "getAwakeDuration")
                + ", score=" + safeInvoke(report, "getScore")
                + ", avgHr=" + safeInvoke(report, "getAvgHr")
                + ", avgSpo2=" + safeInvoke(report, "getAvgSpo2")
                + ", segments=" + describeSleepReports(safeInvokeObject(report, "getSleepSegments"))
                + "}";
    }

    private String describeSleepAllDayReports(Object reports) {
        if (!(reports instanceof java.util.List)) {
            return shortObject(reports);
        }
        java.util.List<?> list = (java.util.List<?>) reports;
        StringBuilder builder = new StringBuilder("List(size=").append(list.size()).append(")[");
        int count = Math.min(list.size(), 5);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append("; ");
            }
            builder.append(describeAllDaySleepReport(list.get(i)));
        }
        if (list.size() > count) {
            builder.append("; ...");
        }
        return builder.append("]").toString();
    }

    private boolean isSleepHomeDataType(Object dataType) {
        if (dataType == null) {
            return false;
        }
        String text = String.valueOf(dataType);
        return containsSleepText(text) || "8".equals(safeInvoke(dataType, "getValue"));
    }

    private boolean containsSleepRecordKey(Object map) {
        if (!(map instanceof java.util.Map)) {
            return false;
        }
        for (Object key : ((java.util.Map<?, ?>) map).keySet()) {
            String text = String.valueOf(key);
            if ("sleep".equals(text)
                    || "NightSleep".equals(text)
                    || "DaytimeSleep".equals(text)
                    || containsSleepText(text)) {
                return true;
            }
        }
        return false;
    }

    private String describeDailyBasicReports(Object reports, int limit) {
        if (reports == null) {
            return "null";
        }
        if (reports.getClass().isArray()) {
            int length = Array.getLength(reports);
            StringBuilder builder = new StringBuilder("Array(size=").append(length).append(")[");
            int count = Math.min(length, Math.max(0, limit));
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    builder.append("; ");
                }
                builder.append(describeAllDaySleepReport(Array.get(reports, i)));
            }
            if (length > count) {
                builder.append("; ...");
            }
            return builder.append("]").toString();
        }
        if (reports instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) reports;
            StringBuilder builder = new StringBuilder("List(size=").append(list.size()).append(")[");
            int count = Math.min(list.size(), Math.max(0, limit));
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    builder.append("; ");
                }
                builder.append(describeAllDaySleepReport(list.get(i)));
            }
            if (list.size() > count) {
                builder.append("; ...");
            }
            return builder.append("]").toString();
        }
        return shortObject(reports);
    }

    private String describeRelativeDataModel(Object model) {
        if (model == null) {
            return "null";
        }
        String value = String.valueOf(safeInvokeObject(model, "getValue"));
        return "RelativeDataModel{key=" + safeInvoke(model, "getKey")
                + ", time=" + safeInvoke(model, "getTime")
                + ", value=" + shortDebugText(value, 220)
                + "}";
    }

    private String describeMapKeys(Object map) {
        if (!(map instanceof java.util.Map)) {
            return shortObject(map);
        }
        StringBuilder builder = new StringBuilder("[");
        int index = 0;
        for (Object key : ((java.util.Map<?, ?>) map).keySet()) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(String.valueOf(key));
            index++;
            if (index >= 12) {
                builder.append(", ...");
                break;
            }
        }
        return builder.append("]").toString();
    }

    private String describeListShort(Object value, int limit) {
        if (!(value instanceof java.util.List)) {
            return shortObject(value);
        }
        java.util.List<?> list = (java.util.List<?>) value;
        StringBuilder builder = new StringBuilder("List(size=").append(list.size()).append(")[");
        int count = Math.min(list.size(), Math.max(0, limit));
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append("; ");
            }
            builder.append(shortObject(list.get(i)));
        }
        if (list.size() > count) {
            builder.append("; ...");
        }
        return builder.append("]").toString();
    }

    private boolean looksSleepRelated(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof java.util.List) {
            for (Object item : (java.util.List<?>) value) {
                if (looksSleepRelated(item)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof java.util.Map) {
            for (Object entry : ((java.util.Map<?, ?>) value).entrySet()) {
                if (looksSleepRelated(entry)) {
                    return true;
                }
            }
            return false;
        }
        return containsSleepText(String.valueOf(value));
    }

    private boolean containsSleepText(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("sleep") || lower.contains("睡眠");
    }

    private String describeNightSleep(Object sleep) {
        if (sleep == null) {
            return "null";
        }
        return "Night{bed=" + safeInvoke(sleep, "getBedTime")
                + ", wake=" + safeInvoke(sleep, "getWakeUpTime")
                + ", duration=" + safeInvoke(sleep, "getSleepDuration")
                + ", deep=" + safeInvoke(sleep, "getDeepDuration")
                + ", light=" + safeInvoke(sleep, "getLightDuration")
                + ", rem=" + safeInvoke(sleep, "getRemDuration")
                + ", awakeDuration=" + safeInvoke(sleep, "getAwakeDuration")
                + ", awakeCount=" + safeInvoke(sleep, "getAwakeCount")
                + ", score=" + safeInvoke(sleep, "getTotalScore")
                + "}";
    }

    private String describeDaytimeSleep(Object sleep) {
        if (sleep == null) {
            return "null";
        }
        return "Daytime{duration=" + safeInvoke(sleep, "getSleepDuration")
                + ", items=" + shortObject(safeInvokeObject(sleep, "getSleepItems"))
                + "}";
    }

    private String describeSleepReports(Object reports) {
        if (reports == null) {
            return "null";
        }
        if (reports instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) reports;
            StringBuilder builder = new StringBuilder("List(size=").append(list.size()).append(")[");
            int count = Math.min(list.size(), 4);
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    builder.append("; ");
                }
                builder.append(describeSleepSegmentReport(list.get(i)));
            }
            if (list.size() > count) {
                builder.append("; ...");
            }
            return builder.append("]").toString();
        }
        Class<?> clazz = reports.getClass();
        if (clazz.isArray()) {
            int length = Array.getLength(reports);
            StringBuilder builder = new StringBuilder("Array(size=").append(length).append(")[");
            int count = Math.min(length, 4);
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    builder.append("; ");
                }
                builder.append(describeSleepSegmentReport(Array.get(reports, i)));
            }
            if (length > count) {
                builder.append("; ...");
            }
            return builder.append("]").toString();
        }
        return shortObject(reports);
    }

    private String describeRecordItems(Object items, int limit) {
        if (items == null) {
            return "null";
        }
        if (items instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) items;
            StringBuilder builder = new StringBuilder("List(size=").append(list.size()).append(")[");
            int count = Math.min(list.size(), Math.max(0, limit));
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    builder.append("; ");
                }
                builder.append(describeDailyRecordItem(list.get(i)));
            }
            if (list.size() > count) {
                builder.append("; ...");
            }
            return builder.append("]").toString();
        }
        if (items.getClass().isArray()) {
            int length = Array.getLength(items);
            StringBuilder builder = new StringBuilder("Array(size=").append(length).append(")[");
            int count = Math.min(length, Math.max(0, limit));
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    builder.append("; ");
                }
                builder.append(describeDailyRecordItem(Array.get(items, i)));
            }
            if (length > count) {
                builder.append("; ...");
            }
            return builder.append("]").toString();
        }
        return shortObject(items);
    }

    private String describeDailyRecordItem(Object item) {
        if (item == null) {
            return "null";
        }
        Object data = safeInvokeObject(item, "getData");
        if (data == null) {
            data = safeInvokeObject(item, "getValue");
        }
        return "DailyRecordItem{time=" + safeInvoke(item, "getTimeStamp")
                + ", tz=" + safeInvoke(item, "getTzIn15Min")
                + ", complete=" + safeInvoke(item, "getCompleteSleep")
                + ", data=" + (data == null ? shortObject(item) : describeSleepSegmentReport(data))
                + "}";
    }

    private String describeSleepSegmentReport(Object report) {
        if (report == null) {
            return "null";
        }
        return "SleepSegment{sid=" + safeInvoke(report, "getSid")
                + ", bed=" + safeInvoke(report, "getBedTime")
                + ", wake=" + safeInvoke(report, "getWakeupTime")
                + ", duration=" + safeInvoke(report, "getSleepDuration")
                + ", deep=" + safeInvoke(report, "getDeepDuration")
                + ", light=" + safeInvoke(report, "getLightDuration")
                + ", rem=" + safeInvoke(report, "getRemDuration")
                + ", wakeDuration=" + safeInvoke(report, "getWakeDuration")
                + ", wakeCount=" + safeInvoke(report, "getWakeCount")
                + ", score=" + safeInvoke(report, "getTotalScore")
                + ", deviceBed=" + safeInvoke(report, "getDeviceBedTime")
                + ", deviceWake=" + safeInvoke(report, "getDeviceWakeupTime")
                + ", hasStage=" + safeInvoke(report, "hasStageData")
                + "}";
    }

    private String describeCoroutineResult(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof java.util.List || result.getClass().isArray()) {
            return describeSleepReports(result);
        }
        String className = result.getClass().getName();
        if ("kotlin.coroutines.intrinsics.CoroutineSingletons".equals(className)
                || className.contains("COROUTINE_SUSPENDED")) {
            return "COROUTINE_SUSPENDED";
        }
        return shortObject(result);
    }

    private String safeInvoke(Object target, String methodName) {
        Object value = safeInvokeObject(target, methodName);
        return value == null ? "null" : safeString(String.valueOf(value));
    }

    private Object safeInvokeObject(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return callMethod(target, methodName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int intInvoke(Object target, String methodName, int fallback) {
        Object value = safeInvokeObject(target, methodName);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private String shortObject(Object object) {
        if (object == null) {
            return "null";
        }
        String value = safeString(String.valueOf(object));
        if (value.length() > 180) {
            value = value.substring(0, 180) + "...";
        }
        return object.getClass().getName() + "@" + System.identityHashCode(object) + "/" + value;
    }

    private String compactStackTrace(int limit) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder builder = new StringBuilder();
        int appended = 0;
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className == null
                    || className.equals(Thread.class.getName())
                    || className.startsWith("com.heartwith.mihealth.lsp.")
                    || className.startsWith("io.github.libxposed.")
                    || className.contains("Xposed")) {
                continue;
            }
            if (appended > 0) {
                builder.append(" <- ");
            }
            String simple = className;
            int dot = simple.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < simple.length()) {
                simple = simple.substring(dot + 1);
            }
            builder.append(simple).append('.').append(element.getMethodName())
                    .append(':').append(element.getLineNumber());
            appended++;
            if (appended >= limit) {
                break;
            }
        }
        return builder.length() == 0 ? "empty" : builder.toString();
    }

    private String describeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Object arg = args[i];
            if (arg instanceof byte[]) {
                builder.append("bytes(").append(((byte[]) arg).length).append(")=").append(hex((byte[]) arg));
            } else {
                builder.append(shortObject(arg));
            }
        }
        builder.append(']');
        return builder.toString();
    }

    private String hex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int value = b & 0xff;
            if (value < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(value));
        }
        return builder.toString();
    }

    private void debugSleepLine(String message) {
        if (!DebugBuild.ENABLED) {
            return;
        }
        String line = "sleep " + message;
        diagLine(line);
    }

    private void debugSleepStateLine(String event, String reason, SleepSnapshot snapshot, String detail) {
        if (!DebugBuild.ENABLED) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("sleep-state event=").append(event == null ? "unknown" : event)
                .append(", reason=").append(reason == null ? "" : reason)
                .append(", candidate=").append(sleepCandidateSeenToday)
                .append(", finalRequested=").append(sleepFinalReportRequested)
                .append(", trackingDay=").append(formatEpochMillis(sleepTrackingDayStartMs))
                .append(", finalUploadedKey=")
                .append(sleepFinalUploadedKey == null || sleepFinalUploadedKey.length() == 0
                        ? "none"
                        : sleepFinalUploadedKey)
                .append(", hookEnabled=").append(heartRateHookEnabled)
                .append(", worker=").append(isWorkerProcess());
        if (snapshot != null) {
            builder.append(", snapshot={").append(describeSleepSnapshot(snapshot)).append("}");
        }
        if (detail != null && detail.length() > 0) {
            builder.append(", detail=").append(detail);
        }
        debugSleepLine(builder.toString());
    }

    private String describeSleepSnapshot(SleepSnapshot snapshot) {
        if (snapshot == null) {
            return "null";
        }
        return "state=" + snapshot.state
                + ", source=" + snapshot.source
                + ", stable=" + snapshot.stable
                + ", observed=" + formatEpochMillis(snapshot.observedAtMs)
                + ", bed=" + formatEpochMillis(snapshot.bedAtMs)
                + ", sleep=" + formatEpochMillis(snapshot.sleepAtMs)
                + ", wake=" + formatEpochMillis(snapshot.wakeAtMs)
                + ", goBed=" + formatEpochMillis(snapshot.goBedAtMs)
                + ", deviceBed=" + formatEpochMillis(snapshot.deviceBedAtMs)
                + ", leaveBed=" + formatEpochMillis(snapshot.leaveBedAtMs)
                + ", deviceWake=" + formatEpochMillis(snapshot.deviceWakeAtMs)
                + ", durationMin=" + snapshot.durationMinutes
                + ", finalKey=" + finalSleepKey(snapshot);
    }

    private void debugSyncLine(String message) {
        if (!DebugBuild.ENABLED) {
            return;
        }
        String line = "sync " + message;
        diagLine(line);
    }

    private long heartRateAgeForDebug() {
        if (lastHrElapsedMs <= 0L) {
            return -1L;
        }
        return SystemClock.elapsedRealtime() - lastHrElapsedMs;
    }

    private String describeDeviceForDebug(Object device) {
        if (DebugBuild.ENABLED) {
            if (device == null) {
                return "null";
            }
            return describeObjectForDebug(device)
                    + ", id=" + getCurrentDeviceId(device)
                    + ", name=" + describeDeviceModel(device);
        }
        return "";
    }

    private String describeDeviceModel(Object device) {
        if (device == null) {
            return null;
        }
        String[] methods = {
                "getDeviceName", "getDisplayName", "getName", "getAlias",
                "getNickName", "getNickname", "getModelName", "getProductName",
                "getBluetoothName", "getBleName"
        };
        for (String method : methods) {
            String value = safeString(callNoArgMethod(device, method));
            if (looksLikeDeviceName(value)) {
                return value;
            }
        }
        String[] fields = {
                "deviceName", "displayName", "name", "alias", "nickName",
                "nickname", "modelName", "productName", "bluetoothName", "bleName"
        };
        for (String field : fields) {
            String value = safeString(getFieldValueQuietly(device, field));
            if (looksLikeDeviceName(value)) {
                return value;
            }
        }
        String did = getCurrentDeviceId(device);
        String value = findDeviceNameFromInfoList(device, did);
        if (value != null) {
            return value;
        }
        value = scanStringMethodsForDeviceName(device);
        if (value != null) {
            return value;
        }
        value = scanStringFieldsForDeviceName(device);
        if (value != null) {
            return value;
        }
        value = scanNestedObjectsForDeviceName(device);
        if (value != null) {
            return value;
        }
        value = safeString(device.toString());
        if (looksLikeDeviceName(value)) {
            return value;
        }
        dumpDeviceModelForDebug(device);
        return null;
    }

    private String getCurrentDeviceId(Object device) {
        String value = safeString(callNoArgMethod(device, "getDid"));
        if (value != null) {
            return value;
        }
        return safeString(getFieldValueQuietly(device, "did"));
    }

    private String findDeviceNameFromInfoList(Object device, String did) {
        Object[] nested = collectNestedObjects(device);
        for (Object object : nested) {
            Object list = callNoArgMethod(object, "getDeviceInfoList");
            String value = findDeviceNameInObject(list, did);
            if (value != null) {
                return value;
            }
            list = callNoArgMethod(object, "getAllDeviceModels");
            value = findDeviceNameInObject(list, did);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String findDeviceNameInObject(Object value, String did) {
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                String name = parseNamedDevice(item, did);
                if (name != null) {
                    return name;
                }
            }
            return null;
        }
        return parseNamedDevice(value, did);
    }

    private String parseNamedDevice(Object item, String did) {
        if (item == null) {
            return null;
        }
        String text = safeString(item.toString());
        if (text == null) {
            return null;
        }
        if (did != null && !text.contains("did='" + did + "'") && !text.contains("did=" + did)) {
            return null;
        }
        String name = extractQuotedValue(text, "name='");
        if (looksLikeDeviceName(name)) {
            return name.trim();
        }
        name = extractQuotedValue(text, "bleName='");
        if (looksLikeDeviceName(name)) {
            return name.trim();
        }
        return null;
    }

    private String extractQuotedValue(String text, String prefix) {
        int start = text.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        int end = text.indexOf('\'', start);
        if (end <= start) {
            return null;
        }
        return safeString(text.substring(start, end));
    }

    private void dumpDeviceModelForDebug(Object device) {
        if (!DebugBuild.ENABLED || deviceModelDumpLogged || device == null) {
            return;
        }
        deviceModelDumpLogged = true;
        StringBuilder builder = new StringBuilder();
        builder.append("device model dump: class=")
                .append(device.getClass().getName())
                .append(", toString=")
                .append(safeString(device.toString()));
        int count = appendStringFields(device, builder, 8);
        appendStringMethods(device, builder, Math.max(0, 12 - count));
        appendNestedObjectsForDebug(device, builder, 8);
        if (DebugBuild.ENABLED) {
            diagLine(builder.toString());
        }
    }

    private String scanNestedObjectsForDeviceName(Object device) {
        Object[] nested = collectNestedObjects(device);
        for (Object object : nested) {
            if (object == null) {
                continue;
            }
            String value = scanStringMethodsForDeviceName(object);
            if (value != null) {
                return value;
            }
            value = scanStringFieldsForDeviceName(object);
            if (value != null) {
                return value;
            }
            value = safeString(object.toString());
            if (looksLikeDeviceName(value)) {
                return value;
            }
        }
        return null;
    }

    private Object[] collectNestedObjects(Object device) {
        ArrayList<Object> objects = new ArrayList<>();
        Class<?> current = device.getClass();
        while (current != null && objects.size() < 12) {
            try {
                Field[] fields = current.getDeclaredFields();
                for (Field field : fields) {
                    if (objects.size() >= 12) {
                        break;
                    }
                    Class<?> type = field.getType();
                    if (type.isPrimitive() || type == String.class || type.isArray()) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(device);
                    if (value != null) {
                        objects.add(value);
                    }
                }
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }
        return objects.toArray(new Object[0]);
    }

    private void appendNestedObjectsForDebug(Object device, StringBuilder builder, int limit) {
        Object[] nested = collectNestedObjects(device);
        int count = 0;
        for (Object object : nested) {
            if (object == null || count >= limit) {
                return;
            }
            builder.append(", nested.")
                    .append(object.getClass().getName())
                    .append('=')
                    .append(safeString(object.toString()));
            appendInterestingNoArgMethods(object, builder, 4);
            count++;
        }
    }

    private void appendInterestingNoArgMethods(Object object, StringBuilder builder, int limit) {
        int count = 0;
        try {
            Method[] methods = object.getClass().getMethods();
            for (Method method : methods) {
                if (count >= limit) {
                    return;
                }
                if (method.getParameterTypes().length != 0) {
                    continue;
                }
                String name = method.getName();
                String lower = name.toLowerCase();
                if (!lower.contains("name") && !lower.contains("alias") &&
                        !lower.contains("device") && !lower.contains("model") &&
                        !lower.contains("product") && !lower.contains("did")) {
                    continue;
                }
                Object value;
                try {
                    value = method.invoke(object);
                } catch (Throwable ignored) {
                    continue;
                }
                String text = safeString(value);
                if (text == null) {
                    continue;
                }
                builder.append(", nestedMethod.")
                        .append(name)
                        .append('=')
                        .append(text);
                count++;
            }
        } catch (Throwable ignored) {
        }
    }

    private int appendStringFields(Object device, StringBuilder builder, int limit) {
        int count = 0;
        Class<?> current = device.getClass();
        while (current != null && count < limit) {
            try {
                Field[] fields = current.getDeclaredFields();
                for (Field field : fields) {
                    if (count >= limit) {
                        break;
                    }
                    if (field.getType() != String.class) {
                        continue;
                    }
                    field.setAccessible(true);
                    String value = safeString(field.get(device));
                    if (value == null) {
                        continue;
                    }
                    builder.append(", field.")
                            .append(field.getName())
                            .append('=')
                            .append(value);
                    count++;
                }
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }
        return count;
    }

    private void appendStringMethods(Object device, StringBuilder builder, int limit) {
        if (limit <= 0) {
            return;
        }
        int count = 0;
        try {
            Method[] methods = device.getClass().getMethods();
            for (Method method : methods) {
                if (count >= limit) {
                    return;
                }
                if (method.getParameterTypes().length != 0 || method.getReturnType() != String.class) {
                    continue;
                }
                String value = safeString(method.invoke(device));
                if (value == null) {
                    continue;
                }
                builder.append(", method.")
                        .append(method.getName())
                        .append('=')
                        .append(value);
                count++;
            }
        } catch (Throwable ignored) {
        }
    }

    private String scanStringMethodsForDeviceName(Object device) {
        try {
            Method[] methods = device.getClass().getMethods();
            for (Method method : methods) {
                if (method.getParameterTypes().length != 0 || method.getReturnType() != String.class) {
                    continue;
                }
                String value = safeString(method.invoke(device));
                if (looksLikeDeviceName(value)) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String scanStringFieldsForDeviceName(Object device) {
        Class<?> current = device.getClass();
        while (current != null) {
            try {
                Field[] fields = current.getDeclaredFields();
                for (Field field : fields) {
                    if (field.getType() != String.class) {
                        continue;
                    }
                    field.setAccessible(true);
                    String value = safeString(field.get(device));
                    if (looksLikeDeviceName(value)) {
                        return value;
                    }
                }
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Object callNoArgMethod(Object instance, String name) {
        try {
            return callMethod(instance, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object getFieldValueQuietly(Object instance, String name) {
        try {
            return getFieldValue(instance, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String safeString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    private boolean looksLikeDeviceName(String value) {
        if (value == null || value.length() < 2 || value.length() > 80) {
            return false;
        }
        String lower = value.toLowerCase();
        if (lower.startsWith("com.") || lower.startsWith("lcom/") ||
                lower.contains(".manager.") || lower.contains(".device.") ||
                lower.contains("/") || lower.contains("@")) {
            return false;
        }
        if (value.indexOf('@') >= 0 && value.indexOf(' ') < 0) {
            return false;
        }
        return lower.contains("xiaomi") ||
                lower.contains("redmi") ||
                lower.contains("mi band") ||
                lower.contains("smart band") ||
                lower.contains("watch") ||
                lower.contains("band") ||
                value.contains("手环") ||
                value.contains("手表");
    }

    private Object getOrCreateHrCallback(ClassLoader classLoader) throws Exception {
        Object callback = hrCallback;
        if (callback != null) {
            return callback;
        }
        Class<?> callbackClass = findClass("com.xiaomi.hm.health.bt.sdk.ISportHrCallback", classLoader);
        callback = Proxy.newProxyInstance(classLoader, new Class<?>[]{callbackClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "HeartwithMiHealthCallback";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return args != null && args.length > 0 && proxy == args[0];
                }
                if ("onHeartRateChanged".equals(name) && args != null && args.length > 0) {
                    final String source = "huami-proxy";
                    if (!shouldIgnoreSource(source)) {
                        onHeartRate(((Number) args[0]).intValue(), source);
                    }
                    return null;
                }
                return defaultValue(method.getReturnType());
            }
        });
        hrCallback = callback;
        return callback;
    }

    private Object getOrCreateHuamiControllerCallback(ClassLoader classLoader) throws Exception {
        Object callback = huamiControllerCallback;
        if (callback != null) {
            return callback;
        }
        Class<?> callbackClass = findClass("buu$b", classLoader);
        callback = Proxy.newProxyInstance(classLoader, new Class<?>[]{callbackClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "HeartwithMiHealthHuamiCallback";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return args != null && args.length > 0 && proxy == args[0];
                }
                if ("onHeartRateChanged".equals(name) && args != null && args.length > 0) {
                    final String source = "huami-controller-proxy";
                    if (!shouldIgnoreSource(source)) {
                        onHeartRate(((Number) args[0]).intValue(), source);
                    }
                    return null;
                }
                return defaultValue(method.getReturnType());
            }
        });
        huamiControllerCallback = callback;
        return callback;
    }

    private Integer extractHrFromWearRaw(ClassLoader classLoader, byte[] data) {
        try {
            Class<?> packetClass = findClass("ixs", classLoader);
            Object packet = callStaticMethod(packetClass, "L", data);
            Integer hr = extractHrFromIxs(packet);
            if (hr != null) {
                return hr;
            }
        } catch (Throwable ignored) {
        }
        return extractHrFromWearRawBytes(data);
    }

    private Integer extractHrFromRaw(ClassLoader classLoader, byte[] data) {
        try {
            Class<?> packetClass = findClass("kxs", classLoader);
            Object packet = callStaticMethod(packetClass, "y", data);
            return extractHrFromKxs(packet);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer extractHrFromKxs(Object packet) {
        if (packet == null) {
            return null;
        }
        try {
            Object paa = callMethod(packet, "r");
            Object sportRealtime = paa == null ? null : callMethod(paa, "o");
            if (sportRealtime == null) {
                return null;
            }
            int hr = ((Number) getFieldValue(sportRealtime, "f")).intValue();
            return hr >= 30 && hr <= 240 ? hr : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer extractHrFromIxs(Object packet) {
        if (packet == null) {
            return null;
        }
        try {
            Object fitness = callMethod(packet, "u");
            Integer sportHr = extractHrFromSportDataPayload(fitness);
            if (sportHr != null) {
                return sportHr;
            }
            Object sportRealtime = fitness == null ? null : callMethod(fitness, "p");
            if (sportRealtime == null) {
                return null;
            }
            int hr = ((Number) getFieldValue(sportRealtime, "f")).intValue();
            return hr >= 30 && hr <= 240 ? hr : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer extractHrFromSportDataPayload(Object fitness) {
        if (fitness == null) {
            return null;
        }
        try {
            Object sportData = callMethod(fitness, "k0");
            if (sportData == null) {
                return null;
            }
            int hr = ((Number) getFieldValue(sportData, "c")).intValue();
            return hr >= 30 && hr <= 240 ? hr : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer extractHrFromWearSportData(Object sportData) {
        if (sportData == null) {
            return null;
        }
        try {
            int hr = ((Number) getFieldValue(sportData, "c")).intValue();
            return hr >= 30 && hr <= 240 ? hr : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer extractHrFromWearRawBytes(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        for (int index = 0; index < data.length - 2; index++) {
            if ((data[index] & 0xff) != 0x20) {
                continue;
            }
            VarintResult value = readVarint(data, index + 1);
            if (value == null || value.nextIndex >= data.length) {
                continue;
            }
            if ((data[value.nextIndex] & 0xff) != 0x28) {
                continue;
            }
            int hr = value.value;
            if (hr >= 30 && hr <= 240) {
                return hr;
            }
        }
        return null;
    }

    private VarintResult readVarint(byte[] data, int start) {
        int value = 0;
        int shift = 0;
        int index = start;
        while (index < data.length && shift < 28) {
            int b = data[index] & 0xff;
            value |= (b & 0x7f) << shift;
            index++;
            if ((b & 0x80) == 0) {
                return new VarintResult(value, index);
            }
            shift += 7;
        }
        return null;
    }

    private static final class VarintResult {
        final int value;
        final int nextIndex;

        VarintResult(int value, int nextIndex) {
            this.value = value;
            this.nextIndex = nextIndex;
        }
    }

    private void diagRawPacket(String source, int type, Object raw, Integer hr) {
        if (!VERBOSE_LOGS || type != 8 || !(raw instanceof byte[])) {
            return;
        }
        byte[] data = (byte[]) raw;
        diagRaw(source, type, data.length, sampleHash(data), hr);
    }

    private void diagPacket(String source, int type, Object packet, Integer hr) {
        if (!VERBOSE_LOGS || type != 8) {
            return;
        }
        diagRaw(source, type, -1, packet == null ? 0 : System.identityHashCode(packet), hr);
    }

    private void diagRaw(String source, int type, int length, int hash, Integer hr) {
        if (DebugBuild.ENABLED) {
            long elapsed = SystemClock.elapsedRealtime();
            if (lastRawDiagElapsedMs > 0L && elapsed - lastRawDiagElapsedMs < 5_000L) {
                return;
            }
            lastRawDiagElapsedMs = elapsed;
            diagLine("raw packet source=" + source
                    + ", type=" + type
                    + ", len=" + length
                    + ", hash=" + hash
                    + ", hr=" + (hr == null ? "?" : String.valueOf(hr)));
        }
    }

    private void diagPacketShape(String source, Object packet) {
        if (!DebugBuild.ENABLED || packet == null) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        if (lastRawDiagElapsedMs > 0L && elapsed - lastRawDiagElapsedMs < 5_000L) {
            return;
        }
        lastRawDiagElapsedMs = elapsed;
        try {
            Object payload = getFieldValue(packet, "d");
            String payloadClass = payload == null ? "null" : payload.getClass().getName();
            Object nested = null;
            try {
                nested = payload == null ? null : getFieldValue(payload, "d");
            } catch (Throwable ignored) {
            }
            diagLine("packet shape source=" + source
                    + ", packet=" + packet.getClass().getName()
                    + ", c=" + getFieldValue(packet, "c")
                    + ", e=" + getFieldValue(packet, "e")
                    + ", f=" + getFieldValue(packet, "f")
                    + ", payload=" + payloadClass
                    + ", ints=" + summarizeIntFields(payload, 8)
                    + ", nested=" + (nested == null ? "null" : nested.getClass().getName())
                    + ", nestedInts=" + summarizeIntFields(nested, 24));
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("packet shape failed: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private String summarizeIntFields(Object object, int limit) {
        if (object == null) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        Class<?> current = object.getClass();
        int count = 0;
        while (current != null && count < limit) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                break;
            }
            for (Field field : fields) {
                if (count >= limit) {
                    break;
                }
                Class<?> type = field.getType();
                if (type != Integer.TYPE && type != Integer.class && type != Long.TYPE && type != Long.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (builder.length() > 0) {
                        builder.append(',');
                    }
                    builder.append(field.getName()).append('=').append(field.get(object));
                    count++;
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private int sampleHash(byte[] data) {
        int hash = 1;
        int step = Math.max(1, data.length / 16);
        for (int i = 0; i < data.length; i += step) {
            hash = 31 * hash + (data[i] & 0xff);
        }
        return hash;
    }

    private void onHeartRate(final int hr, final String source) {
        refreshRuntimeSettingsIfNeeded(appContext, false);
        if (!heartRateHookEnabled) {
            return;
        }
        if (hr < 30 || hr > 240) {
            return;
        }
        if (isDeprecatedHeartRateSource(source)) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        boolean sportSource = isMainProcess() && isSportHeartRateSource(source);
        if (sportSource) {
            markSportModeActive();
        }
        maybeRefreshCurrentDeviceModel(elapsed);
        if (!lockOrAcceptSource(source)) {
            return;
        }
        if (hr == lastHr && elapsed - lastHrElapsedMs < DUPLICATE_WINDOW_MS) {
            return;
        }
        lastHr = hr;
        lastHrElapsedMs = elapsed;
        noHeartStartAttempts = 0;
        scheduleHeartRateWatchdog();
        final Context context = appContext;
        final boolean handleHeartRate = context != null && shouldHandleAcceptedHeartRate(source);
        if (!firstHeartRateLogged && handleHeartRate) {
            firstHeartRateLogged = true;
            lastAcceptedLogMs = elapsed;
            importantLine("heart_rate first bpm=" + hr + ", source=" + source);
        } else if (handleHeartRate && elapsed - lastAcceptedLogMs >= ACCEPTED_LOG_INTERVAL_MS) {
            lastAcceptedLogMs = elapsed;
            importantLine("heart_rate bpm=" + hr + ", source=" + source);
        }
        if (!handleHeartRate) {
            return;
        }
        persistLastHeartRateSeen(elapsed);
        clearLegacyKickNeededOnce();
        if (isWorkerProcess()) {
            scheduleHeartRateAlarmWatchdog("heart-rate");
            maybeFetchSleepStatusAfterHeartRate(context, elapsed, source);
        }
        final boolean updateNotification = shouldUpdateStatus(hr, elapsed);
        final boolean uploadHeartRate = shouldUploadAcceptedHeartRate(source);
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                long seenMs = System.currentTimeMillis();
                if (updateNotification) {
                    try {
                        HeartwithStatus.writeLocal(context, hr, source, seenMs);
                        HeartwithStatus.sendRegisteredStatus(context, hr, source, seenMs);
                    } catch (Throwable throwable) {
                        if (DebugBuild.ENABLED) {
                            diagLine("status cache failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                        }
                    }
                }
                if (updateNotification) {
                    try {
                        HeartwithStatus.showHookProcessNotification(context, hr, source, seenMs);
                    } catch (Throwable throwable) {
                        if (DebugBuild.ENABLED) {
                            diagLine("notification failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                        }
                    }
                }
                if (uploadHeartRate) {
                    UPLOAD_WORKER.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                uploader.onHeartRate(context, hr, source);
                            } catch (Throwable throwable) {
                                if (DebugBuild.ENABLED) {
                                    diagLine("uploader crashed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                                }
                            }
                        }
                    });
                }
            }
        });
    }

    private boolean shouldHandleAcceptedHeartRate(String source) {
        if (isWorkerProcess()) {
            return !isSportModeActive();
        }
        return isMainProcess() && isSportHeartRateSource(source);
    }

    private boolean shouldUploadAcceptedHeartRate(String source) {
        if (isWorkerProcess()) {
            return !isSportModeActive();
        }
        return isMainProcess() && isSportHeartRateSource(source);
    }

    private boolean isSportHeartRateSource(String source) {
        if (source == null) {
            return false;
        }
        return source.startsWith("eco-") ||
                source.startsWith("sport-") ||
                source.startsWith("huami-") ||
                source.startsWith("twu.") ||
                "huami".equals(source);
    }

    private boolean isDeprecatedHeartRateSource(String source) {
        return source != null && source.startsWith("launch-");
    }

    private boolean shouldUpdateStatus(int hr, long elapsed) {
        if (lastStatusUpdateBpm <= 0) {
            lastStatusUpdateBpm = hr;
            lastStatusUpdateElapsedMs = elapsed;
            return true;
        }
        if (Math.abs(hr - lastStatusUpdateBpm) >= STATUS_UPDATE_CHANGE_BPM ||
                elapsed - lastStatusUpdateElapsedMs >= STATUS_UPDATE_MIN_INTERVAL_MS) {
            lastStatusUpdateBpm = hr;
            lastStatusUpdateElapsedMs = elapsed;
            return true;
        }
        return false;
    }

    private void resetHeartRateSource(String reason) {
        synchronized (this) {
            activeSource = null;
            activeSourceRestored = false;
            activeSourceElapsedMs = 0L;
            lastActiveSourcePersistElapsedMs = 0L;
            lastHr = -1;
            started = false;
            lastStatusUpdateBpm = -1;
            lastStatusUpdateElapsedMs = 0L;
            firstHeartRateLogged = false;
            persistActiveSource(null);
        }
        if (DebugBuild.ENABLED) {
            diagLine("heart-rate source reset: " + reason);
        }
        importantLine("heart-rate source reset: " + reason);
    }

    private boolean shouldIgnoreSource(String source) {
        return isDeprecatedHeartRateSource(source);
    }

    private boolean lockOrAcceptSource(String source) {
        if (isDeprecatedHeartRateSource(source)) {
            return false;
        }
        String selected = activeSource;
        if (isDeprecatedHeartRateSource(selected)) {
            synchronized (this) {
                if (isDeprecatedHeartRateSource(activeSource)) {
                    activeSource = null;
                    activeSourceRestored = false;
                    persistActiveSource(null);
                }
            }
            selected = activeSource;
        }
        if (selected != null) {
            if (selected.equals(source)) {
                activeSourceRestored = false;
                maybePersistActiveSource(source);
                return true;
            }
            if (shouldSwitchHeartRateSource(selected, source, SystemClock.elapsedRealtime())) {
                synchronized (this) {
                    activeSource = source;
                    activeSourceElapsedMs = SystemClock.elapsedRealtime();
                    activeSourceRestored = false;
                    maybePersistActiveSource(source);
                    importantLine("heart-rate source switched: " + selected + " -> " + source);
                    if (DebugBuild.ENABLED) {
                        diagLine("heart-rate source switched: " + selected + " -> " + source);
                    }
                }
                return true;
            }
        }
        if (selected != null) {
            if ((activeSourceRestored && lastHr <= 0) || isActiveSourceStale(SystemClock.elapsedRealtime())) {
                synchronized (this) {
                    if ((activeSourceRestored && lastHr <= 0) || isActiveSourceStale(SystemClock.elapsedRealtime())) {
                        activeSource = null;
                        activeSourceRestored = false;
                    }
                }
            } else {
                return false;
            }
        }
        synchronized (this) {
            if (activeSource == null) {
                activeSource = source;
                activeSourceElapsedMs = SystemClock.elapsedRealtime();
                activeSourceRestored = false;
                maybePersistActiveSource(source);
                importantLine("heart-rate source locked: " + source);
                if (DebugBuild.ENABLED) {
                    diagLine("heart-rate source locked: " + source);
                }
                return true;
            }
            return activeSource.equals(source);
        }
    }

    private boolean shouldSwitchHeartRateSource(String selected, String candidate, long elapsed) {
        if (selected == null || candidate == null || selected.equals(candidate)) {
            return false;
        }
        if (isDeprecatedHeartRateSource(candidate)) {
            return false;
        }
        if (isDeprecatedHeartRateSource(selected)) {
            return true;
        }
        if (activeSourceRestored && lastHr <= 0) {
            return true;
        }
        if (isActiveSourceStale(elapsed)) {
            return true;
        }
        if (isMainProcess() && isSportHeartRateSource(candidate) && !isSportHeartRateSource(selected)) {
            return true;
        }
        return sourcePriority(candidate) > sourcePriority(selected);
    }

    private int sourcePriority(String source) {
        if (source == null || isDeprecatedHeartRateSource(source)) {
            return -1;
        }
        if ("huami".equals(source) || source.startsWith("huami-")) {
            return 100;
        }
        if ("wear-raw".equals(source) || "eco-raw".equals(source) || "eco-remote-raw".equals(source)) {
            return 90;
        }
        if ("sport-packet".equals(source) || "eco-packet".equals(source) || "eco-remote-packet".equals(source)) {
            return 85;
        }
        if ("sport-wear-data".equals(source) || "eco-wear-data".equals(source)) {
            return 80;
        }
        if (source.startsWith("twu.")) {
            return 75;
        }
        return 50;
    }

    private boolean isActiveSourceStale(long elapsed) {
        return lastHr > 0 && elapsed - lastHrElapsedMs >= HEART_RATE_WATCHDOG_MS;
    }

    private void restoreActiveSource(Context context) {
        if (context == null || activeSource != null) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE);
            String source = prefs.getString(KEY_ACTIVE_SOURCE, null);
            long seenMs = prefs.getLong(KEY_ACTIVE_SOURCE_SEEN_MS, 0L);
            if (source != null && !source.isEmpty() &&
                    !isDeprecatedHeartRateSource(source) &&
                    System.currentTimeMillis() - seenMs < RESTORED_SOURCE_TTL_MS) {
                activeSource = source;
                activeSourceElapsedMs = SystemClock.elapsedRealtime();
                activeSourceRestored = true;
            }
        } catch (Throwable ignored) {
        }
    }

    private void persistActiveSource(String source) {
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            SharedPreferences.Editor editor = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE).edit();
            if (source == null || source.isEmpty()) {
                editor.remove(KEY_ACTIVE_SOURCE).remove(KEY_ACTIVE_SOURCE_SEEN_MS);
            } else {
                editor.putString(KEY_ACTIVE_SOURCE, source)
                        .putLong(KEY_ACTIVE_SOURCE_SEEN_MS, System.currentTimeMillis());
            }
            editor.apply();
        } catch (Throwable ignored) {
        }
    }

    private boolean hasRecentHeartRateInAnyProcess() {
        return hasRecentHeartRateInAnyProcess(CROSS_PROCESS_HR_RECENT_MS);
    }

    private boolean hasRecentHeartRateInAnyProcess(long maxAgeMs) {
        Context context = appContext;
        if (context == null) {
            return false;
        }
        if (hasRecentHeartRateInLocalPrefs(context, maxAgeMs)) {
            return true;
        }
        return false;
    }

    private void markSportModeActive() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        long untilMs = System.currentTimeMillis() + SPORT_MODE_GRACE_MS;
        sportModeActiveUntilMs = Math.max(sportModeActiveUntilMs, untilMs);
        try {
            Intent intent = new Intent(ACTION_SPORT_MODE_CHANGED);
            intent.setPackage(targetPackage);
            intent.putExtra(EXTRA_SPORT_MODE_UNTIL_MS, untilMs);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private void notifyDeviceChangedFromSync(Object device) {
        Context context = appContext;
        if (context == null || device == null) {
            return;
        }
        Object didObject = safeInvokeObject(device, "getDid");
        String did = didObject == null ? null : String.valueOf(didObject);
        if (did == null || did.length() == 0) {
            return;
        }
        String name = null;
        Object nameObject = safeInvokeObject(device, "getName");
        if (nameObject != null) {
            name = String.valueOf(nameObject);
        }
        try {
            Intent intent = new Intent(ACTION_DEVICE_CHANGED);
            intent.setPackage(targetPackage);
            intent.putExtra(EXTRA_DEVICE_DID, did);
            if (name != null) {
                intent.putExtra(EXTRA_DEVICE_NAME, name);
            }
            context.sendBroadcast(intent);
            if (DebugBuild.ENABLED) {
                debugLine("device change broadcast sent did=" + maskDid(did) + ", name=" + name);
            }
        } catch (Throwable ignored) {
            if (DebugBuild.ENABLED) {
                debugLine("device change broadcast failed: " + describeThrowable(ignored));
            }
        }
    }

    private boolean isSportModeActive() {
        Context context = appContext;
        if (context == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (sportModeActiveUntilMs > now) {
            return true;
        }
        return false;
    }

    private boolean hasRecentHeartRateInLocalPrefs(Context context, long maxAgeMs) {
        try {
            long seenMs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_HR_SEEN_MS, 0L);
            return seenMs > 0L && System.currentTimeMillis() - seenMs < maxAgeMs;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void persistLastHeartRateSeen(long elapsed) {
        Context context = appContext;
        if (context == null) {
            return;
        }
        if (lastHeartRateSeenPersistElapsedMs > 0L &&
                elapsed - lastHeartRateSeenPersistElapsedMs < LAST_HR_SEEN_PERSIST_MS) {
            return;
        }
        lastHeartRateSeenPersistElapsedMs = elapsed;
        try {
            context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_HR_SEEN_MS, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private boolean hasPendingLegacyKickRequest() {
        Context context = appContext;
        if (context == null) {
            return false;
        }
        try {
            long requestedMs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_LEGACY_KICK_NEEDED_MS, 0L);
            return requestedMs > 0L && System.currentTimeMillis() - requestedMs < LEGACY_KICK_REQUEST_TTL_MS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void markLegacyKickNeeded() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LEGACY_KICK_NEEDED_MS, System.currentTimeMillis())
                    .apply();
            legacyKickClearedAfterHeartRate = false;
            if (!legacyKickRequestLogged) {
                legacyKickRequestLogged = true;
                if (DebugBuild.ENABLED) {
                    diagLine("legacy kick marked because device process has no heart rate yet");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void clearLegacyKickNeededOnce() {
        if (legacyKickClearedAfterHeartRate) {
            return;
        }
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_LEGACY_KICK_NEEDED_MS)
                    .apply();
            legacyKickClearedAfterHeartRate = true;
        } catch (Throwable ignored) {
        }
    }

    private void scheduleRealtimeHrResume(final String reason) {
        final Context context = appContext;
        final ClassLoader classLoader = targetClassLoader;
        if (context == null || classLoader == null || !isWorkerProcess()) {
            return;
        }
        if (!heartRateHookEnabled) {
            return;
        }
        final long delayMs = reason != null && reason.startsWith("device-changed:")
                ? 250L
                : 1_500L;
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    resetHeartRateSource(reason);
                    ensureRealtimeHrStarted(classLoader, reason);
                }
            }, delayMs);
        } catch (Throwable ignored) {
        }
    }

    private void scheduleHeartRateWatchdog() {
        final Context context = appContext;
        final ClassLoader classLoader = targetClassLoader;
        if (context == null || classLoader == null || !isWorkerProcess()) {
            return;
        }
        if (!heartRateHookEnabled) {
            return;
        }
        if (!heartRateWatchdogScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    heartRateWatchdogScheduled.set(false);
                    long elapsed = SystemClock.elapsedRealtime();
                    if (lastHr > 0 && elapsed - lastHrElapsedMs >= HEART_RATE_WATCHDOG_MS) {
                        resetHeartRateSource("watchdog:no-heart-rate");
                        ensureRealtimeHrStarted(classLoader, "watchdog:no-heart-rate");
                    } else if (lastHr > 0) {
                        scheduleHeartRateWatchdog();
                    }
                }
            }, HEART_RATE_WATCHDOG_MS);
        } catch (Throwable ignored) {
            heartRateWatchdogScheduled.set(false);
        }
    }

    private void scheduleHeartRateAlarmWatchdog(String reason) {
        Context context = appContext;
        if (context == null || !isWorkerProcess() || !heartRateHookEnabled) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        if (lastHeartRateWatchdogAlarmElapsedMs > 0L &&
                elapsed - lastHeartRateWatchdogAlarmElapsedMs < HEART_RATE_ALARM_RESCHEDULE_MIN_MS) {
            return;
        }
        lastHeartRateWatchdogAlarmElapsedMs = elapsed;
        int generation = ++heartRateWatchdogGeneration;
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                return;
            }
            PendingIntent pendingIntent = heartRateWatchdogPendingIntent(context, generation);
            alarmManager.cancel(pendingIntent);
            long triggerAtMs = elapsed + HEART_RATE_ALARM_WATCHDOG_MS;
            if (DebugBuild.ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMs,
                        pendingIntent);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setWindow(
                        AlarmManager.ELAPSED_REALTIME,
                        triggerAtMs,
                        HEART_RATE_ALARM_WINDOW_MS,
                        pendingIntent);
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME, triggerAtMs, pendingIntent);
            }
            if (DebugBuild.ENABLED) {
                diagLine("hr alarm watchdog scheduled reason=" + reason + ", generation=" + generation);
            }
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                diagLine("hr alarm watchdog schedule failed: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private void cancelHeartRateAlarmWatchdog(Context context) {
        heartRateWatchdogGeneration++;
        lastHeartRateWatchdogAlarmElapsedMs = 0L;
        if (context == null || !isWorkerProcess()) {
            return;
        }
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.cancel(heartRateWatchdogPendingIntent(context, heartRateWatchdogGeneration));
            }
        } catch (Throwable ignored) {
        }
    }

    private PendingIntent heartRateWatchdogPendingIntent(Context context, int generation) {
        Intent intent = new Intent(ACTION_HEART_RATE_WATCHDOG);
        intent.setPackage(targetPackage);
        intent.putExtra(EXTRA_HEART_RATE_WATCHDOG_GENERATION, generation);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, 0x23014333, intent, flags);
    }

    private void handleHeartRateAlarmWatchdog() {
        if (!isWorkerProcess() || !heartRateHookEnabled) {
            return;
        }
        ClassLoader classLoader = targetClassLoader;
        if (classLoader == null) {
            return;
        }
        if (hasRecentHeartRateInAnyProcess(HEART_RATE_ALARM_WATCHDOG_MS)) {
            lastHeartRateWatchdogAlarmElapsedMs = 0L;
            scheduleHeartRateAlarmWatchdog("alarm:recent");
            return;
        }
        if (DebugBuild.ENABLED) {
            diagLine("hr alarm watchdog stale, restarting realtime heart rate");
        }
        importantLine("hr alarm watchdog stale, restarting realtime heart rate"
                + ", lastHr=" + lastHr
                + ", ageMs=" + heartRateAgeForDebug());
        resetHeartRateSource("watchdog-alarm:no-heart-rate");
        ensureRealtimeHrStarted(classLoader, "watchdog-alarm:no-heart-rate");
        lastHeartRateWatchdogAlarmElapsedMs = 0L;
        scheduleHeartRateAlarmWatchdog("alarm:stale");
    }

    private void maybePersistActiveSource(String source) {
        long elapsed = SystemClock.elapsedRealtime();
        if (lastActiveSourcePersistElapsedMs > 0L && elapsed - lastActiveSourcePersistElapsedMs < 600_000L) {
            return;
        }
        lastActiveSourcePersistElapsedMs = elapsed;
        persistActiveSource(source);
    }

    private void hookAfter(Class<?> target, String methodName, Class<?>[] parameterTypes, final AfterHook afterHook) {
        try {
            Method method = target.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        afterHook.after(chain, result);
                    } catch (Throwable throwable) {
                        if (DebugBuild.ENABLED) {
                            logLine("after hook failed " + target.getName() + "." + methodName
                                    + ": " + throwable.getClass().getSimpleName());
                        }
                    }
                    return result;
                }
            });
        } catch (Throwable throwable) {
            if (DebugBuild.ENABLED) {
                logLine("hook failed " + target.getName() + "." + methodName + ": " + throwable.getClass().getSimpleName());
            }
        }
    }

    private Class<?> findClass(String name, ClassLoader classLoader) throws ClassNotFoundException {
        return Class.forName(name, false, classLoader);
    }

    private Class<?> findFirstClass(ClassLoader classLoader, String... names) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String name : names) {
            try {
                return findClass(name, classLoader);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        throw last == null ? new ClassNotFoundException() : last;
    }

    private Object newInstance(Class<?> clazz) throws Exception {
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private Object getStaticObjectField(Class<?> clazz, String... names) throws Exception {
        NoSuchFieldException last = null;
        for (String name : names) {
            try {
                Field field = findField(clazz, name);
                return field.get(null);
            } catch (NoSuchFieldException e) {
                last = e;
            }
        }
        throw last == null ? new NoSuchFieldException() : last;
    }

    private Object getFieldValue(Object instance, String name) throws Exception {
        Field field = findField(instance.getClass(), name);
        return field.get(instance);
    }

    private void setBooleanField(Object instance, String name, boolean value) {
        try {
            Field field = findField(instance.getClass(), name);
            if (field.getType() == Boolean.TYPE) {
                field.setBoolean(instance, value);
            } else if (field.getType() == Boolean.class) {
                field.set(instance, Boolean.valueOf(value));
            }
        } catch (Throwable ignored) {
        }
    }

    private void setBooleanObjectField(Object instance, String name, boolean value) {
        setBooleanField(instance, name, value);
    }

    private Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private Object callMethod(Object instance, String name, Object... args) throws Exception {
        Method method = findCompatibleMethod(instance.getClass(), name, false, args);
        return method.invoke(instance, args);
    }

    private Object callStaticMethod(Class<?> clazz, String name, Object... args) throws Exception {
        Method method = findCompatibleMethod(clazz, name, true, args);
        return method.invoke(null, args);
    }

    private Method findCompatibleMethod(Class<?> clazz, String name, boolean requireStatic, Object[] args)
            throws NoSuchMethodException {
        Class<?> current = clazz;
        while (current != null) {
            Method[] methods = current.getDeclaredMethods();
            for (Method method : methods) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers()) != requireStatic) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == args.length && parametersMatch(parameterTypes, args)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(clazz.getName() + "." + name);
    }

    private boolean parametersMatch(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            if (args[i] == null) {
                if (parameterTypes[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!wrapPrimitive(parameterTypes[i]).isAssignableFrom(args[i].getClass())) {
                return false;
            }
        }
        return true;
    }

    private Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Integer.TYPE) return Integer.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Character.TYPE) return Character.class;
        return Void.class;
    }

    private Object defaultValue(Class<?> type) {
        if (type == Void.TYPE) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0f;
        if (type == Double.TYPE) return 0d;
        if (type == Character.TYPE) return (char) 0;
        return null;
    }

    private String getProcessName() {
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream("/proc/self/cmdline");
            byte[] buffer = new byte[128];
            int length = inputStream.read(buffer);
            if (length <= 0) {
                return null;
            }
            int end = 0;
            while (end < length && buffer[end] != 0) {
                end++;
            }
            return new String(buffer, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void logLine(String message) {
        if (!VERBOSE_LOGS) {
            return;
        }
        if (DebugBuild.ENABLED) {
            diagLine(message);
        }
    }

    private void debugLine(String message) {
        if (DebugBuild.ENABLED) {
            diagLine(message);
        }
    }

    private void importantLine(String message) {
        if (message == null || message.length() == 0) {
            return;
        }
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
        DebugSleepLog.line(appContext, processName, message);
    }

    private void diagLine(String message) {
        if (!VERBOSE_LOGS) {
            return;
        }
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
        if (DebugBuild.ENABLED) {
            DebugSleepLog.line(appContext, processName, message);
        }
    }

    private interface AfterHook {
        void after(XposedInterface.Chain chain, Object result) throws Throwable;
    }

    private static final class SegmentSleepBounds {
        long bedMs;
        long wakeMs;
        long deviceBedMs;
        long deviceWakeMs;
        long goBedMs;
        long leaveBedMs;
        long durationMinutes;
    }

    private static final class SleepSnapshot {
        final String state;
        final long observedAtMs;
        final long bedAtMs;
        final long sleepAtMs;
        final long wakeAtMs;
        final long goBedAtMs;
        final long deviceBedAtMs;
        final long leaveBedAtMs;
        final long deviceWakeAtMs;
        final String source;
        final boolean stable;
        final long durationMinutes;
        final List<HeartwithSleepStatus.Segment> segments;

        SleepSnapshot(String state,
                      long observedAtMs,
                      long bedAtMs,
                      long sleepAtMs,
                      long wakeAtMs,
                      long goBedAtMs,
                      long deviceBedAtMs,
                      long leaveBedAtMs,
                      long deviceWakeAtMs,
                      String source,
                      boolean stable,
                      long durationMinutes,
                      List<HeartwithSleepStatus.Segment> segments) {
            this.state = state;
            this.observedAtMs = observedAtMs;
            this.bedAtMs = bedAtMs;
            this.sleepAtMs = sleepAtMs;
            this.wakeAtMs = wakeAtMs;
            this.goBedAtMs = goBedAtMs;
            this.deviceBedAtMs = deviceBedAtMs;
            this.leaveBedAtMs = leaveBedAtMs;
            this.deviceWakeAtMs = deviceWakeAtMs;
            this.source = source;
            this.stable = stable;
            this.durationMinutes = durationMinutes;
            this.segments = segments == null ? new ArrayList<>() : segments;
        }
    }
}
