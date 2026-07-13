# Keep only framework entry points. The module reflects into Xiaomi Health, not into its own
# private helpers, so allowing R8 to shrink unreachable helpers removes Release-only debug code.
-dontobfuscate
-keep,allowoptimization class com.heartwith.mihealth.lsp.MiHealthHookModule {
    public <init>();
    public void onModuleLoaded(io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
    public void onPackageReady(io.github.libxposed.api.XposedModuleInterface$PackageReadyParam);
    public boolean onHotReloading(io.github.libxposed.api.XposedModuleInterface$HotReloadingParam);
    public void onHotReloaded(io.github.libxposed.api.XposedModuleInterface$HotReloadedParam);
}
-keep,allowoptimization class com.heartwith.mihealth.lsp.LegacyNpatchEntry {
    public <init>();
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}
-keep,allowoptimization class com.heartwith.mihealth.lsp.SettingsProvider {
    public <init>();
    public boolean onCreate();
    public android.database.Cursor query(android.net.Uri, java.lang.String[], java.lang.String, java.lang.String[], java.lang.String);
    public java.lang.String getType(android.net.Uri);
    public android.net.Uri insert(android.net.Uri, android.content.ContentValues);
    public int delete(android.net.Uri, java.lang.String, java.lang.String[]);
    public int update(android.net.Uri, android.content.ContentValues, java.lang.String, java.lang.String[]);
}
-keep,allowoptimization class com.heartwith.mihealth.lsp.SettingsActivity {
    public <init>();
    protected void onCreate(android.os.Bundle);
    protected void onResume();
    protected void onPause();
}

# Release source sets compile these helpers to no-ops. Removing their invocations also removes
# diagnostic string construction at the call site.
-assumenosideeffects class com.heartwith.mihealth.lsp.MiHealthHookModule {
    private void debugSleepLine(java.lang.String);
    private void debugSleepStateLine(java.lang.String, java.lang.String, com.heartwith.mihealth.lsp.MiHealthHookModule$SleepSnapshot, java.lang.String);
    private void debugSyncLine(java.lang.String);
    private void debugLine(java.lang.String);
    private void diagLine(java.lang.String);
    private void logLine(java.lang.String);
}
-assumenosideeffects class com.heartwith.mihealth.lsp.DebugSleepLog {
    static void init(android.content.Context, java.lang.String);
    static void line(android.content.Context, java.lang.String, java.lang.String);
}

# These are diagnostic-only formatters. Their return value is still retained wherever
# production code consumes it, while calls left behind by removed debug logs disappear.
-assumenosideeffects class com.heartwith.mihealth.lsp.MiHealthHookModule {
    java.lang.String describe*(...);
    java.lang.String format*(...);
    java.lang.String short*(...);
    java.lang.String hex(...);
    java.lang.String maskDid(...);
}
