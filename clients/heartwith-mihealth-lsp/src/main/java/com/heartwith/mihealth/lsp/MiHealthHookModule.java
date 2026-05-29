package com.heartwith.mihealth.lsp;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Build;
import android.os.Bundle;
import android.security.NetworkSecurityPolicy;
import android.os.SystemClock;
import android.util.Log;

import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    private static final String ACTION_SPORT_MODE_CHANGED = "com.heartwith.mihealth.lsp.SPORT_MODE_CHANGED";
    private static final String EXTRA_SPORT_MODE_UNTIL_MS = "sport_mode_until_ms";
    private static final String KEY_ACTIVE_SOURCE = "active_source";
    private static final String KEY_ACTIVE_SOURCE_SEEN_MS = "active_source_seen_ms";
    private static final String KEY_LAST_HR_SEEN_MS = "last_hr_seen_ms";
    private static final String KEY_LEGACY_KICK_NEEDED_MS = "legacy_kick_needed_ms";
    private static final long DUPLICATE_WINDOW_MS = 900L;
    private static final long ACCEPTED_LOG_INTERVAL_MS = BuildConfig.DEBUG ? 5_000L : 60_000L;
    private static final long RESTORED_SOURCE_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final long CROSS_PROCESS_HR_RECENT_MS = 60_000L;
    private static final long LEGACY_KICK_REQUEST_TTL_MS = 60_000L;
    private static final long LAST_HR_SEEN_PERSIST_MS = 8_000L;
    private static final long DEVICE_MODEL_REFRESH_MS = 30_000L;
    private static final long DEVICE_MODEL_UNRESOLVED_RETRY_MS = 180_000L;
    private static final long HEART_RATE_WATCHDOG_MS = 12_000L;
    private static final long SPORT_MODE_GRACE_MS = 10_000L;
    private static final long STATUS_UPDATE_MIN_INTERVAL_MS = 10_000L;
    private static final long STATUS_VIEWER_CHECK_MIN_INTERVAL_MS = 1_000L;
    private static final int STATUS_UPDATE_CHANGE_BPM = 3;
    private static final boolean VERBOSE_LOGS = BuildConfig.DEBUG;
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

    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean npatchHooksInstalled = new AtomicBoolean(false);
    private final AtomicBoolean notificationPermissionRequested = new AtomicBoolean(false);
    private final AtomicBoolean heartRateWatchdogScheduled = new AtomicBoolean(false);
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean configReceiverRegistered = new AtomicBoolean(false);
    private final AtomicBoolean sportModeReceiverRegistered = new AtomicBoolean(false);
    private final AtomicBoolean cleartextPolicyHookInstalled = new AtomicBoolean(false);
    private final HeartwithUploader uploader = new HeartwithUploader(WORKER);
    private final List<Object> launchModels = new ArrayList<>();
    private volatile Context appContext;
    private volatile Object hrCallback;
    private volatile Object huamiControllerCallback;
    private volatile WeakReference<Object> huamiHrController = new WeakReference<>(null);
    private volatile WeakReference<Object> huamiBleDevice = new WeakReference<>(null);
    private volatile boolean started;
    private volatile long lastStartAt;
    private volatile String lastStartReason = "";
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
    private volatile long lastStatusViewerCheckElapsedMs;
    private volatile long statusViewerActiveUntilMs;
    private volatile long sportModeActiveUntilMs;
    private volatile String currentDeviceIdentity;
    private volatile boolean currentDeviceModelResolved;
    private volatile boolean legacyKickClearedAfterHeartRate;
    private volatile boolean npatchWrappedDetected;
    private volatile boolean npatchRouteDiagLogged;
    private volatile boolean npatchArouterIndexesInstalled;
    private volatile boolean cleartextPolicyAllowLogged;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        logLine("module loaded api=" + getApiVersion());
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
        if (!isMainProcess() && !isWorkerProcess()) {
            return;
        }
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        ClassLoader classLoader = param.getClassLoader();
        targetClassLoader = classLoader;
        if (isWorkerProcess()) {
            hookHeartwithCleartextPolicy();
        }
        hookLifecycle(classLoader);
        if (isWorkerProcess()) {
            hookHeartRateSinks(classLoader);
            hookHeartRateStopControls(classLoader);
        } else if (isMainProcess()) {
            hookPassiveSportHeartRateSinks(classLoader);
        }
        logLine("hooks installed process=" + processName);
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
                        if (BuildConfig.DEBUG && !cleartextPolicyAllowLogged) {
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
                        if (BuildConfig.DEBUG && !cleartextPolicyAllowLogged) {
                            cleartextPolicyAllowLogged = true;
                            Log.i(TAG, "allow cleartext for Heartwith request scope");
                        }
                        return true;
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable throwable) {
            diagLine("heartwith cleartext policy hook failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
        hookAndroidOkHttpCleartextPolicy();
        diagLine("heartwith cleartext policy hook installed");
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
                    diagLine("android okhttp cleartext hook installed: " + className + " count=" + count);
                }
            } catch (Throwable throwable) {
                diagLine("android okhttp cleartext hook unavailable: " + className + ": " + throwable.getClass().getSimpleName());
            }
        }
    }

    private boolean shouldAllowHeartwithCleartext(XposedInterface.Chain chain, Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 1) {
            Object arg = chain.getArg(0);
            if (arg instanceof String && isHeartwithServerHost((String) arg)) {
                logCleartextAllow("allow cleartext for Heartwith host: " + arg);
                return true;
            }
            if (arg instanceof URL && isHeartwithServerHost(((URL) arg).getHost())) {
                logCleartextAllow("allow cleartext for Heartwith url: " + ((URL) arg).getHost());
                return true;
            }
        }
        if (HeartwithCleartextScope.isActive()) {
            logCleartextAllow("allow cleartext for Heartwith request scope");
            return true;
        }
        return false;
    }

    private void logCleartextAllow(String message) {
        if (BuildConfig.DEBUG && !cleartextPolicyAllowLogged) {
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
                        diagLine("skip xcrash native handler for NPatch seccomp");
                        return 0;
                    }
                });
            }
        } catch (Throwable throwable) {
            diagLine("xcrash native handler hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    private void hookLifecycle(final ClassLoader classLoader) {
        hookAfter(Application.class, "attach", new Class<?>[]{Context.class}, new AfterHook() {
            @Override
            public void after(XposedInterface.Chain chain, Object result) {
                Context base = (Context) chain.getArg(0);
                Context applicationContext = base == null ? null : base.getApplicationContext();
                appContext = applicationContext == null ? base : applicationContext;
                diagLine("attach process=" + processName);
                if (isMainProcess() && chain.getThisObject() instanceof Application) {
                    installNotificationPermissionRequest((Application) chain.getThisObject());
                }
                maybeInstallNpatchCompatibility(classLoader, chain.getThisObject());
                registerSportModeReceiver(appContext);
                if (isWorkerProcess()) {
                    registerConfigReceiver(appContext);
                    warmUpUploaderConfig(appContext);
                }
                restoreActiveSource(appContext);
                scheduleStartAfterAttach(classLoader);
            }
        });
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
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco_manager.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_manager.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco_manager.state.data.HuamiDataReceive");
    }

    private void scheduleStartAfterAttach(final ClassLoader classLoader) {
        final Context context = appContext;
        if (context == null) {
            return;
        }
        if (isMainProcess()) {
            scheduleLegacyKickCheck(classLoader, 6_000L);
            return;
        }
        if (!isWorkerProcess()) {
            return;
        }
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    ensureRealtimeHrStarted(classLoader, "application:attach");
                }
            }, 2_000L);
        } catch (Throwable ignored) {
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
                            diagLine("legacy kick start requested from main process");
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
                                diagLine("npatch route rescue: " + method.getName());
                                return null;
                            }
                        }
                        return chain.proceed();
                    }
                });
            }
        } catch (Throwable throwable) {
            diagLine("npatch route rescue unavailable: " + throwable.getClass().getSimpleName());
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
            diagLine("local wear core hooks installed");
        } catch (Throwable throwable) {
            diagLine("local wear core hook unavailable: " + throwable.getClass().getSimpleName());
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
            diagLine("local account mode hooks installed");
        } catch (Throwable throwable) {
            diagLine("local account mode hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    private void hookMiAccountInternalLocalMode(ClassLoader classLoader) {
        try {
            Class<?> internalManager = findClass("com.xiaomi.fitness.account.manager.MiAccountInternalManager", classLoader);
            hookBooleanNoArg(internalManager, "isUseLocal", true);
            hookBooleanNoArg(internalManager, "isUseSystem", false);
            hookSetUserSystemToLocal(internalManager);
        } catch (Throwable throwable) {
            diagLine("mi account local mode hook unavailable: " + throwable.getClass().getSimpleName());
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
            diagLine("web oauth fallback hooks installed");
        } catch (Throwable throwable) {
            diagLine("web oauth fallback hook unavailable: " + throwable.getClass().getSimpleName());
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
            diagLine("npatch arouter index hook unavailable: " + throwable.getClass().getSimpleName());
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
            diagLine("npatch arouter indexes installed");
        } catch (Throwable throwable) {
            diagLine("npatch arouter index install failed: " + throwable.getClass().getSimpleName());
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
            logLine("npatch route rescue failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
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
                } catch (Throwable throwable) {
                    diagLine("warmup crashed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
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
                    if (intent == null || !HeartwithSettings.ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    final Context runtimeContext = context;
                    final boolean enabled = intent.getBooleanExtra(HeartwithSettings.EXTRA_ENABLED, true);
                    final String serverUrl = intent.getStringExtra(HeartwithSettings.EXTRA_SERVER_URL);
                    final String displayName = intent.getStringExtra(HeartwithSettings.EXTRA_DISPLAY_NAME);
                    WORKER.execute(new Runnable() {
                        @Override
                        public void run() {
                            uploader.applySettings(
                                    runtimeContext,
                                    new HeartwithSettings(enabled, serverUrl, displayName),
                                    "settings broadcast synced");
                        }
                    });
                }
            };
            IntentFilter filter = new IntentFilter(HeartwithSettings.ACTION_CONFIG_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            diagLine("config receiver registered process=" + processName);
        } catch (Throwable throwable) {
            diagLine("config receiver failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
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
            diagLine("sport mode receiver registered process=" + processName);
        } catch (Throwable throwable) {
            diagLine("sport mode receiver failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
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
        hookLegacyHuamiHeartRateProfile(classLoader);
        hookOriginalHuamiHeartRateController(classLoader);
        hookOriginalHuamiBleDevice(classLoader);
        hookDeviceHrStopHelpers(classLoader);
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco_manager.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_manager.model.LaunchSportModel$HuamiHrImpl");
        hookHuamiCallback(classLoader, "com.xiaomi.fitness.sport_eco_manager.state.data.HuamiDataReceive");
        hookHuamiCallback(classLoader, "auu");
        hookHuamiCallback(classLoader, "com.xiaomi.hm.health.bt.sdk.HuamiDevice$y");
        hookHuamiCallback(classLoader, "com.xiaomi.fit.device.huami.HuaMiApiCallerImpl$startRealtimeMeasureHr$1");
        hookHuamiCallback(classLoader, "com.xiaomi.wearable.HuamiApiImpl$startRealtimeMeasureHr$1");
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
                    diagRawPacket(source, type, raw);
                    if (type == 8 && raw instanceof byte[]) {
                        Integer hr = extractHrFromWearRaw(classLoader, (byte[]) raw);
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
            diagLine("eco packet hook installed: " + className);
        } catch (Throwable throwable) {
            diagLine("eco packet hook unavailable: " + className + ": " + throwable.getClass().getSimpleName());
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
            diagLine("sport packet hook installed: " + className);
        } catch (Throwable throwable) {
            diagLine("sport packet hook unavailable: " + className + ": " + throwable.getClass().getSimpleName());
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
                    diagRawPacket(source, type, raw);
                    if (type == 8 && raw instanceof byte[]) {
                        Integer hr = extractHrFromRaw(classLoader, (byte[]) raw);
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
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
                    diagRawPacket(source, type, raw);
                    if (type == 8 && raw instanceof byte[]) {
                        Integer hr = extractHrFromRaw(classLoader, (byte[]) raw);
                        if (hr != null) {
                            onHeartRate(hr, source);
                        }
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
                        logLine("suppress huami controller stop");
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
                        logLine("suppress huami device stop");
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
                                logLine("suppress helper hr stop: " + method.getName());
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
        boolean force = reason.startsWith("watchdog:") || reason.startsWith("stop:");
        if ((!force && !shouldStartNow(reason)) || !starting.compareAndSet(false, true)) {
            return;
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    lastStartAt = SystemClock.elapsedRealtime();
                    lastStartReason = reason;
                    final boolean force = reason.startsWith("watchdog:") || reason.startsWith("stop:");
                    boolean originalDeviceStarted = startOriginalHuamiBleDevice(classLoader);
                    boolean originalControllerStarted = startOriginalHuamiController(classLoader);
                    boolean registered = ensureLaunchModels(classLoader, force);
                    boolean deviceStarted = startDeviceRealtimeHr(classLoader);
                    started = originalDeviceStarted || originalControllerStarted || registered || deviceStarted;
                    diagLine("start result reason=" + reason
                            + ", bleDevice=" + originalDeviceStarted
                            + ", controller=" + originalControllerStarted
                            + ", launchModels=" + registered
                            + ", deviceHr=" + deviceStarted);
                    scheduleRetryIfNeeded(classLoader, force);
                } catch (Throwable throwable) {
                    logLine("start failed: " + throwable.getClass().getSimpleName());
                } finally {
                    starting.set(false);
                }
            }
        });
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
        if (context == null || lastHr > 0) {
            return;
        }
        if (!isWorkerProcess()) {
            return;
        }
        if (!forceRetry && hasRecentHeartRateInAnyProcess()) {
            return;
        }
        if (forceRetry && hasRecentHeartRateInAnyProcess(HEART_RATE_WATCHDOG_MS)) {
            return;
        }
        markLegacyKickNeeded();
        noHeartStartAttempts++;
        final long delayMs = noHeartStartAttempts <= 2 ? 9_000L : 60_000L;
        final String retryReason = forceRetry ? "watchdog-retry:no-heart-rate" : "timer:no-heart-rate";
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (lastHr <= 0 && !hasRecentHeartRateInAnyProcess(HEART_RATE_WATCHDOG_MS)) {
                        ensureRealtimeHrStarted(classLoader, retryReason);
                    }
                }
            }, delayMs);
        } catch (Throwable ignored) {
        }
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
            } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
        }
        try {
            callMethod(model, "registerDeviceHr");
        } catch (Throwable ignored) {
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

    private boolean startDeviceRealtimeHr(ClassLoader classLoader) {
        Object device = getCurrentDeviceModel(classLoader);
        if (device == null) {
            return false;
        }
        updateDeviceModel(device);
        Object callback = null;
        try {
            callback = getOrCreateHrCallback(classLoader);
        } catch (Throwable ignored) {
        }
        boolean startedAny = false;
        for (String helperClassName : START_HELPERS) {
            try {
                Class<?> helperClass = findClass(helperClassName, classLoader);
                callStaticMethod(helperClass, "startDeviceHr", device, callback);
                startedAny = true;
                break;
            } catch (Throwable ignored) {
            }
        }
        return startedAny;
    }

    private Object getCurrentDeviceModel(ClassLoader classLoader) {
        try {
            Class<?> managerClass = findClass("com.xiaomi.fitness.device.manager.export.WearableDeviceManager", classLoader);
            Object companion = getStaticObjectField(managerClass, "Companion");
            Class<?> extClass = findClass("com.xiaomi.fitness.device.manager.export.DeviceManagerExtKt", classLoader);
            Object manager = callStaticMethod(extClass, "getInstance", companion);
            return callMethod(manager, "getCurrentDeviceModel");
        } catch (Throwable ignored) {
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
        } else if (BuildConfig.DEBUG && !deviceModelNullLogged) {
            deviceModelNullLogged = true;
            diagLine("current device model is null");
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
        if (!BuildConfig.DEBUG || deviceModelDumpLogged || device == null) {
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
        diagLine(builder.toString());
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
            return extractHrFromIxs(packet);
        } catch (Throwable ignored) {
            return null;
        }
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

    private void diagRawPacket(String source, int type, Object raw) {
        if (!VERBOSE_LOGS || type != 8 || !(raw instanceof byte[])) {
            return;
        }
        byte[] data = (byte[]) raw;
        diagRaw(source, type, data.length, sampleHash(data), null);
    }

    private void diagPacket(String source, int type, Object packet, Integer hr) {
        if (!VERBOSE_LOGS || type != 8) {
            return;
        }
        diagRaw(source, type, -1, packet == null ? 0 : System.identityHashCode(packet), hr);
    }

    private void diagRaw(String source, int type, int length, int hash, Integer hr) {
        long elapsed = SystemClock.elapsedRealtime();
        if (lastRawDiagElapsedMs > 0L && elapsed - lastRawDiagElapsedMs < 5_000L) {
            return;
        }
        lastRawDiagElapsedMs = elapsed;
        diagLine("raw packet source=" + source +
                ", type=" + type +
                ", len=" + length +
                ", hash=" + hash +
                ", hr=" + (hr == null ? "?" : String.valueOf(hr)));
    }

    private void diagPacketShape(String source, Object packet) {
        if (!VERBOSE_LOGS || packet == null) {
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
            diagLine("packet shape source=" + source +
                    ", packet=" + packet.getClass().getName() +
                    ", c=" + getFieldValue(packet, "c") +
                    ", e=" + getFieldValue(packet, "e") +
                    ", f=" + getFieldValue(packet, "f") +
                    ", payload=" + payloadClass +
                    ", ints=" + summarizeIntFields(payload, 8) +
                    ", nested=" + (nested == null ? "null" : nested.getClass().getName()) +
                    ", nestedInts=" + summarizeIntFields(nested, 24));
        } catch (Throwable throwable) {
            diagLine("packet shape failed: " + throwable.getClass().getSimpleName());
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
        if (isWorkerProcess()) {
            persistLastHeartRateSeen(elapsed);
            clearLegacyKickNeededOnce();
        }
        final Context context = appContext;
        final boolean handleHeartRate = context != null && shouldHandleAcceptedHeartRate(source);
        if (!firstHeartRateLogged && handleHeartRate) {
            firstHeartRateLogged = true;
            diagLine("heart_rate=" + hr + ", source=" + source);
        }
        if (handleHeartRate && elapsed - lastAcceptedLogMs >= ACCEPTED_LOG_INTERVAL_MS) {
            lastAcceptedLogMs = elapsed;
            logLine("heart_rate=" + hr + ", source=" + source);
        }
        if (!handleHeartRate) {
            return;
        }
        final boolean viewerActive = isStatusViewerActive(elapsed);
        final boolean updateNotification = shouldUpdateStatus(hr, elapsed);
        final boolean updateStatusCache = updateNotification || viewerActive;
        final boolean uploadHeartRate = shouldUploadAcceptedHeartRate(source);
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                long seenMs = System.currentTimeMillis();
                if (updateStatusCache) {
                    try {
                        HeartwithStatus.writeLocal(context, hr, source, seenMs);
                        if (viewerActive) {
                            HeartwithStatus.writeModuleStatus(context, hr, source, seenMs);
                        }
                    } catch (Throwable throwable) {
                        diagLine("status cache failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    }
                }
                if (updateNotification) {
                    try {
                        HeartwithStatus.showHookProcessNotification(context, hr, source, seenMs);
                    } catch (Throwable throwable) {
                        diagLine("notification failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    }
                    try {
                        HeartwithStatus.reportFromHook(context, hr, source, processName, seenMs);
                    } catch (Throwable throwable) {
                        diagLine("status failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    }
                }
                if (uploadHeartRate) {
                    try {
                        uploader.onHeartRate(context, hr, source);
                    } catch (Throwable throwable) {
                        diagLine("uploader crashed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    }
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

    private boolean isStatusViewerActive(long elapsed) {
        if (statusViewerActiveUntilMs > System.currentTimeMillis()) {
            return true;
        }
        if (lastStatusViewerCheckElapsedMs > 0L &&
                elapsed - lastStatusViewerCheckElapsedMs < STATUS_VIEWER_CHECK_MIN_INTERVAL_MS) {
            return false;
        }
        lastStatusViewerCheckElapsedMs = elapsed;
        Context context = appContext;
        if (context == null) {
            return false;
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(SettingsProvider.STATUS_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                long untilMs = cursor.getLong(cursor.getColumnIndexOrThrow(HeartwithStatus.KEY_VIEWER_ACTIVE_UNTIL_MS));
                statusViewerActiveUntilMs = untilMs;
                return untilMs > System.currentTimeMillis();
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
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
        diagLine("heart-rate source reset: " + reason);
    }

    private boolean shouldIgnoreSource(String source) {
        if (isDeprecatedHeartRateSource(source)) {
            return true;
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
        if (selected == null || selected.equals(source)) {
            return false;
        }
        if (isMainProcess() && isSportHeartRateSource(source) && !isSportHeartRateSource(selected)) {
            return false;
        }
        if (activeSourceRestored && lastHr <= 0) {
            return false;
        }
        if (isActiveSourceStale(SystemClock.elapsedRealtime())) {
            return false;
        }
        return true;
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
            if (isMainProcess() && isSportHeartRateSource(source) && !isSportHeartRateSource(selected)) {
                synchronized (this) {
                    activeSource = null;
                    activeSourceRestored = false;
                }
                selected = null;
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
                diagLine("heart-rate source locked: " + source);
                return true;
            }
            return activeSource.equals(source);
        }
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
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(SettingsProvider.STATUS_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                long seenMs = cursor.getLong(cursor.getColumnIndexOrThrow(HeartwithStatus.KEY_LAST_SEEN_MS));
                return seenMs > 0L && System.currentTimeMillis() - seenMs < maxAgeMs;
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
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
                diagLine("legacy kick marked because device process has no heart rate yet");
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
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    resetHeartRateSource(reason);
                    ensureRealtimeHrStarted(classLoader, reason);
                }
            }, 1_500L);
        } catch (Throwable ignored) {
        }
    }

    private void scheduleHeartRateWatchdog() {
        final Context context = appContext;
        final ClassLoader classLoader = targetClassLoader;
        if (context == null || classLoader == null || !isWorkerProcess()) {
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
                    afterHook.after(chain, result);
                    return result;
                }
            });
        } catch (Throwable throwable) {
            logLine("hook failed " + target.getName() + "." + methodName + ": " + throwable.getClass().getSimpleName());
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
        diagLine(message);
    }

    private void diagLine(String message) {
        if (!VERBOSE_LOGS) {
            return;
        }
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
    }

    private interface AfterHook {
        void after(XposedInterface.Chain chain, Object result) throws Throwable;
    }
}
