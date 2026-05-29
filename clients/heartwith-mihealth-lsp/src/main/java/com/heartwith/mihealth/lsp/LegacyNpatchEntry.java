package com.heartwith.mihealth.lsp;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LegacyNpatchEntry implements IXposedHookLoadPackage {
    private static final String TAG = "HeartwithMiHealth";
    private static final String TARGET_PACKAGE = "com.mi.health";
    private static final String PATCHED_TARGET_PACKAGE = "com.mi.health.heartwith";
    private static final String MAIN_EXT_CLASS = "com.xiaomi.fitness.main.export.MainExtKt";
    private static final String MAIN_ACTIVITY_CLASS = "com.xiaomi.fitness.main.MainActivity";
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

    private static volatile Context appContext;
    private static volatile boolean npatchDetected;
    private static volatile String targetPackage = TARGET_PACKAGE;
    private static volatile String processName = TARGET_PACKAGE;
    private static volatile boolean routeDiagLogged;
    private static volatile boolean arouterIndexesInstalled;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !isSupportedPackage(lpparam.packageName)) {
            return;
        }
        if (!hasNpatchRuntimeStack()) {
            return;
        }
        npatchDetected = true;
        boolean mainProcess = lpparam.packageName.equals(lpparam.processName);
        boolean deviceProcess = (lpparam.packageName + ":device").equals(lpparam.processName);
        if (!mainProcess && !deviceProcess) {
            return;
        }
        targetPackage = lpparam.packageName;
        processName = lpparam.processName;
        log("legacy entry package=" + lpparam.packageName + " process=" + lpparam.processName);
        hookXCrashNativeHandler(lpparam.classLoader);
        hookApplicationAttach();
        hookLocalAccountLogin(lpparam.classLoader);
        hookLocalWearCore(lpparam.classLoader);
        if (mainProcess) {
            hookArouterIndexes(lpparam.classLoader);
            hookMainRoute(lpparam.classLoader);
        }
    }

    private static boolean isSupportedPackage(String packageName) {
        return TARGET_PACKAGE.equals(packageName)
                || PATCHED_TARGET_PACKAGE.equals(packageName);
    }

    private static boolean isMainProcess() {
        return targetPackage.equals(processName);
    }

    private static void hookXCrashNativeHandler(ClassLoader classLoader) {
        try {
            Class<?> nativeHandler = XposedHelpers.findClass("xcrash.NativeHandler", classLoader);
            for (final Method method : nativeHandler.getDeclaredMethods()) {
                if (!"d".equals(method.getName()) || method.getReturnType() != Integer.TYPE) {
                    continue;
                }
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        log("skip xcrash native handler for NPatch seccomp");
                        param.setResult(0);
                    }
                });
            }
        } catch (Throwable throwable) {
            log("xcrash native handler hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Void.TYPE) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return false;
        }
        if (type == Byte.TYPE) {
            return (byte) 0;
        }
        if (type == Short.TYPE) {
            return (short) 0;
        }
        if (type == Integer.TYPE) {
            return 0;
        }
        if (type == Long.TYPE) {
            return 0L;
        }
        if (type == Float.TYPE) {
            return 0f;
        }
        if (type == Double.TYPE) {
            return 0d;
        }
        if (type == Character.TYPE) {
            return (char) 0;
        }
        return null;
    }

    private static void hookApplicationAttach() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context base = param.args != null && param.args.length > 0 && param.args[0] instanceof Context
                            ? (Context) param.args[0]
                            : null;
                    Context applicationContext = base == null ? null : base.getApplicationContext();
                    appContext = applicationContext == null ? base : applicationContext;
                    if (isMainProcess() && param.thisObject instanceof Application) {
                        HeartwithSettingsPanel.installNpatchEntry((Application) param.thisObject);
                    }
                }
            });
        } catch (Throwable throwable) {
            log("legacy attach hook failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static void hookLocalAccountLogin(ClassLoader classLoader) {
        hookAccountManagerLocalMode(classLoader);
        hookOauthWebFallback(classLoader);
    }

    private static void hookLocalWearCore(ClassLoader classLoader) {
        try {
            Class<?> coreExt = XposedHelpers.findClass("com.xiaomi.wearable.core.CoreExtKt", classLoader);
            hookBooleanNoArg(coreExt, "useLyra", false);
            hookBooleanNoArg(coreExt, "getSupportLyra", false);
            hookBooleanNoArg(coreExt, "getHasLyra", false);
            hookBooleanNoArg(coreExt, "getLyraConnection", false);
            hookBooleanNoArg(coreExt, "isLyraEnabled", false);
            log("legacy local wear core hooks installed");
        } catch (Throwable throwable) {
            log("legacy local wear core hook failed: " + throwable.getClass().getSimpleName());
        }
    }

    private static void hookAccountManagerLocalMode(ClassLoader classLoader) {
        try {
            Class<?> accountManager = XposedHelpers.findClass(
                    "com.xiaomi.fitness.account.manager.AccountManagerImpl",
                    classLoader);
            hookBooleanNoArg(accountManager, "isLocal", true);
            hookBooleanNoArg(accountManager, "isUseLocal", true);
            hookBooleanNoArg(accountManager, "isUseSystem", false);
            hookMiAccountInternalLocalMode(classLoader);
            for (final Method method : accountManager.getDeclaredMethods()) {
                if (!"doSystemAccount".equals(method.getName())) {
                    continue;
                }
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(4);
                    }
                });
            }
            log("legacy local account login hooks installed");
        } catch (Throwable throwable) {
            log("legacy local account login hook failed: " + throwable.getClass().getSimpleName());
        }
    }

    private static void hookMiAccountInternalLocalMode(ClassLoader classLoader) {
        try {
            Class<?> internalManager = XposedHelpers.findClass(
                    "com.xiaomi.fitness.account.manager.MiAccountInternalManager",
                    classLoader);
            hookBooleanNoArg(internalManager, "isUseLocal", true);
            hookBooleanNoArg(internalManager, "isUseSystem", false);
            hookSetUserSystemToLocal(internalManager);
        } catch (Throwable throwable) {
            log("legacy mi account local hook failed: " + throwable.getClass().getSimpleName());
        }
    }

    private static void hookSetUserSystemToLocal(Class<?> internalManager) {
        try {
            final Method setUserLocal = internalManager.getDeclaredMethod("setUserLocal");
            setUserLocal.setAccessible(true);
            XposedHelpers.findAndHookMethod(internalManager, "setUserSystem", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject != null) {
                        setUserLocal.invoke(param.thisObject);
                    }
                    param.setResult(null);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void hookBooleanNoArg(Class<?> target, String methodName, final boolean value) {
        try {
            Method method = target.getDeclaredMethod(methodName);
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(value);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void hookOauthWebFallback(ClassLoader classLoader) {
        try {
            Class<?> factory = XposedHelpers.findClass("com.xiaomi.account.auth.OAuthFactory", classLoader);
            for (final Method method : factory.getDeclaredMethods()) {
                if (!"createOAuth".equals(method.getName())) {
                    continue;
                }
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0) {
                            forceOauthConfigWeb(param.args[0]);
                        }
                    }
                });
            }
            hookOauthServiceManager(classLoader);
            log("legacy web oauth fallback hooks installed");
        } catch (Throwable throwable) {
            log("legacy web oauth fallback hook failed: " + throwable.getClass().getSimpleName());
        }
    }

    private static void hookOauthServiceManager(ClassLoader classLoader) {
        try {
            Class<?> manager = XposedHelpers.findClass("com.xiaomi.account.auth.OAuthServiceManager", classLoader);
            for (final Method method : manager.getDeclaredMethods()) {
                String name = method.getName();
                if (!"blockGetDefaultIntent".equals(name) && !"hasOAuthService".equals(name)) {
                    continue;
                }
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(method.getReturnType() == Boolean.TYPE ? false : null);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private static void forceOauthConfigWeb(Object config) {
        if (config == null) {
            return;
        }
        setBooleanField(config, "notUseMiui", true);
        setBooleanField(config, "useSystemBrowserLogin", false);
    }

    private static void hookMainRoute(ClassLoader classLoader) {
        try {
            Class<?> mainExt = XposedHelpers.findClass(MAIN_EXT_CLASS, classLoader);
            int count = 0;
            for (final Method method : mainExt.getDeclaredMethods()) {
                String name = method.getName();
                if (!"showMainActivity".equals(name) && !"showMainActivity$default".equals(name)) {
                    continue;
                }
                count++;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Context context = findContext(param);
                        if (context == null) {
                            context = appContext;
                        }
                        if (context == null) {
                            return;
                        }
                        if (BuildConfig.DEBUG && !routeDiagLogged) {
                            routeDiagLogged = true;
                            log("legacy route check context=" + context.getClass().getName()
                                    + ", source=" + safeSourceDir(context)
                                    + ", packageCode=" + safePackageCodePath(context)
                                    + ", wrapped=" + isNpatchWrapped(context));
                        }
                        if (!npatchDetected && !isNpatchWrapped(context)) {
                            return;
                        }
                        installArouterIndexes(classLoader);
                        if (launchMainActivity(context)) {
                            log("legacy npatch route rescue: " + method.getName());
                            param.setResult(null);
                        }
                    }
                });
            }
            log("legacy route hooks installed count=" + count);
        } catch (Throwable throwable) {
            log("legacy route hook failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static void hookArouterIndexes(final ClassLoader classLoader) {
        try {
            Class<?> logisticsCenter = findFirstClass(classLoader,
                    "com.alibaba.android.arouter.core.LogisticsCenter",
                    "wpf");
            for (final Method method : logisticsCenter.getDeclaredMethods()) {
                if (!isArouterInitMethod(method)) {
                    continue;
                }
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context context = findContext(param);
                        if (context == null) {
                            context = appContext;
                        }
                        if (context != null && (npatchDetected || isNpatchWrapped(context))) {
                            installArouterIndexes(classLoader);
                        }
                    }
                });
            }
        } catch (Throwable throwable) {
            log("legacy arouter index hook failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static void installArouterIndexes(ClassLoader classLoader) {
        if (arouterIndexesInstalled) {
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
            arouterIndexesInstalled = true;
            log("legacy arouter indexes installed");
        } catch (Throwable throwable) {
            log("legacy arouter index install failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static boolean isArouterInitMethod(Method method) {
        String name = method.getName();
        if (!"init".equals(name) && !"c".equals(name)) {
            return false;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length >= 1 && Context.class.isAssignableFrom(parameterTypes[0]);
    }

    private static Class<?> findFirstClass(ClassLoader classLoader, String... names) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String name : names) {
            try {
                return XposedHelpers.findClass(name, classLoader);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        throw last == null ? new ClassNotFoundException() : last;
    }

    private static void loadArouterIndexes(ClassLoader classLoader, String[] classNames, Object targetMap)
            throws Exception {
        if (!(targetMap instanceof java.util.Map)) {
            return;
        }
        for (String className : classNames) {
            Class<?> routeClass = XposedHelpers.findClass(className, classLoader);
            Object routeIndex = routeClass.getDeclaredConstructor().newInstance();
            Method loadInto = routeClass.getDeclaredMethod("loadInto", java.util.Map.class);
            loadInto.setAccessible(true);
            loadInto.invoke(routeIndex, targetMap);
        }
    }

    private static Object getStaticObjectField(Class<?> clazz, String... names) throws Exception {
        NoSuchFieldException last = null;
        for (String name : names) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(null);
            } catch (NoSuchFieldException e) {
                last = e;
            }
        }
        throw last == null ? new NoSuchFieldException() : last;
    }

    private static Context findContext(XC_MethodHook.MethodHookParam param) {
        if (param == null) {
            return null;
        }
        if (param.thisObject instanceof Context) {
            return (Context) param.thisObject;
        }
        if (param.args == null) {
            return null;
        }
        for (Object arg : param.args) {
            if (arg instanceof Context) {
                return (Context) arg;
            }
        }
        return null;
    }

    private static boolean launchMainActivity(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(targetPackage, MAIN_ACTIVITY_CLASS);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            if (context instanceof Activity) {
                ((Activity) context).finish();
            }
            return true;
        } catch (Throwable throwable) {
            log("legacy route rescue launch failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            return false;
        }
    }

    private static boolean isNpatchWrapped(Context context) {
        if (findNpatchWrapperApk(context) != null || hasNpatchManifestMetadata(context) || hasNpatchRuntimeStack()) {
            npatchDetected = true;
            return true;
        }
        return false;
    }

    private static String safeSourceDir(Context context) {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            return info == null ? null : info.sourceDir;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safePackageCodePath(Context context) {
        try {
            return context.getPackageCodePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String findNpatchWrapperApk(Context context) {
        if (context == null) {
            return null;
        }
        String[] candidates = new String[4];
        try {
            ApplicationInfo info = context.getApplicationInfo();
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
            ApplicationInfo packageInfo = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);
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

    private static boolean apkContainsNpatchOrigin(String path) {
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

    private static boolean hasNpatchManifestMetadata(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(),
                    android.content.pm.PackageManager.GET_META_DATA);
            return info != null && info.metaData != null && info.metaData.containsKey("npatch");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasNpatchRuntimeStack() {
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

    private static void setBooleanField(Object instance, String name, boolean value) {
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

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
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

    private static void log(String message) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
