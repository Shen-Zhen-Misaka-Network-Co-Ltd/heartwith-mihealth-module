package com.heartwith.mihealth.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Looper;
import android.app.AlarmManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import io.github.libxposed.api.XposedInterface;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, instrumentedPackages = "com.heartwith.mihealth.lsp")
@LooperMode(LooperMode.Mode.PAUSED)
public final class MiHealthStateRegressionTest {
    private MiHealthHookModule module;

    @Before
    public void setUp() throws Exception {
        SystemClock.setCurrentTimeMillis(1_788_800_000_000L);
        module = new MiHealthHookModule();
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if ("log".equals(method.getName())) return null;
                    throw new AssertionError("Unexpected framework call: " + method.getName());
                });
        module.attachFramework(framework, () -> { });
        set("targetPackage", "com.mi.health");
        set("processName", "com.mi.health:device");
        set("heartRateHookEnabled", true);
    }

    @Test
    public void notificationRefreshIsFasterOnlyWhileInteractive() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        set("appContext", context);
        android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
        Shadows.shadowOf(power).setIsInteractive(true);
        Class<?>[] signature = {long.class};
        assertTrue((boolean) call("shouldUpdateNotification", signature, 1000L));
        assertFalse((boolean) call("shouldUpdateNotification", signature, 5999L));
        assertTrue((boolean) call("shouldUpdateNotification", signature, 6000L));
        Shadows.shadowOf(power).setIsInteractive(false);
        assertFalse((boolean) call("shouldUpdateNotification", signature, 11000L));
        assertFalse((boolean) call("shouldUpdateNotification", signature, 35999L));
        assertTrue((boolean) call("shouldUpdateNotification", signature, 36000L));
        Shadows.shadowOf(power).setIsInteractive(true);
        assertTrue((boolean) call("shouldUpdateNotification", signature, 41000L));
    }

    @Test
    public void firstAwakeReadingReconcilesCloudWithoutInventingSleepTimes() throws Exception {
        set("appContext", RuntimeEnvironment.getApplication());
        call("handleMiWearState", new Class<?>[]{String.class, boolean.class}, "awake", false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(59));
        assertNull(get("lastSleepStatusKey"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertTrue(((String) get("lastSleepStatusKey")).startsWith("awake|0|0|0|"));
        assertEquals("awake", get("sleepTrackingState"));
        assertFalse((Boolean) get("sleepFinalReportRequested"));
        assertFalse((Boolean) get("provisionalWakePublished"));
        String key = (String) get("lastSleepStatusKey");
        call("handleMiWearState", new Class<?>[]{String.class, boolean.class}, "awake", true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(6));
        assertEquals(key, get("lastSleepStatusKey"));
        assertFalse((Boolean) get("sleepFinalReportRequested"));
    }

    @Test
    public void initialAwakeReconciliationIsCancelledByNewSleepOrShutdown() throws Exception {
        set("appContext", RuntimeEnvironment.getApplication());
        call("handleMiWearState", new Class<?>[]{String.class, boolean.class}, "awake", false);
        call("handleMiWearState", new Class<?>[]{String.class, boolean.class}, "asleep", true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(60));
        assertNull(get("lastSleepStatusKey"));
        set("lifecycleActive", false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(6));
        assertNull(get("lastSleepStatusKey"));
    }

    @Test
    public void provisionalAwakeRejectsActiveRawBeforeFinalizationStarts() throws Exception {
        set("miWearState", "awake");
        set("provisionalWakePublished", true);
        set("sleepTrackingState", "active");
        set("sleepFinalReportRequested", false);
        assertTrue(ignoreActiveRaw());
    }

    @Test
    public void rawIsStillAcceptedBeforeWakeDebounceOrAfterResumingSleep() throws Exception {
        set("miWearState", "awake");
        set("provisionalWakePublished", false);
        assertFalse(ignoreActiveRaw());
        set("provisionalWakePublished", true);
        set("miWearState", "asleep");
        assertFalse(ignoreActiveRaw());
    }

    @Test
    public void confirmedSleepPropertyBlocksContradictoryCompletedDataOnlyWhileActive() throws Exception {
        set("miWearDeviceDid", "watch");
        set("miWearPropertyDid", "watch");
        set("miWearPropertySiid", 6);
        set("miWearSubscriptionActive", true);
        set("miWearState", "asleep");
        set("sleepTrackingState", "active");
        assertTrue((Boolean) call("shouldKeepMiWearAsleepState"));
        set("sleepFinalReportRequested", true);
        assertFalse((Boolean) call("shouldKeepMiWearAsleepState"));
        set("sleepFinalReportRequested", false);
        set("miWearSubscriptionActive", false);
        assertFalse((Boolean) call("shouldKeepMiWearAsleepState"));
    }

    @Test
    public void newSleepDoesNotReusePreviousWindowButKeepsFinalDeduplication() throws Exception {
        set("latestActiveSleepSnapshot", snapshot());
        set("activeRawSleepWindowKey", "old-window");
        set("pendingRawWakeWindowKey", "old-window");
        set("activeSleepDataIdBytes", new byte[]{1, 2, 3});
        set("sleepFinalUploadedKey", "final-window");
        set("sleepCandidateSeenToday", true);
        set("provisionalWakePublished", true);
        call("clearPreviousSleepWindow", new Class<?>[]{String.class}, "test");
        assertNull(get("latestActiveSleepSnapshot"));
        assertNull(get("activeRawSleepWindowKey"));
        assertNull(get("pendingRawWakeWindowKey"));
        assertNull(get("activeSleepDataIdBytes"));
        assertFalse((Boolean) get("sleepCandidateSeenToday"));
        assertFalse((Boolean) get("provisionalWakePublished"));
        assertEquals("final-window", get("sleepFinalUploadedKey"));
    }

    @Test
    public void finalizedHistoricalRawCannotClearNewSleepTracking() throws Exception {
        long now = testWallTimeMs() / 1000L;
        RawSleep finalized = new RawSleep(now, true);
        Object finished = call("sleepSnapshotFromAllDay", new Class<?>[]{String.class, Object.class},
                "all-day-parser", finalized);
        String finalKey = (String) call("finalSleepKey", new Class<?>[]{snapshotClass()}, finished);
        set("sleepFinalUploadedKey", finalKey);
        set("sleepFinalConfirmedKey", finalKey);
        set("sleepTrackingState", "active");
        set("sleepCandidateSeenToday", true);
        set("sleepTrackingDayStartMs", 456L);
        set("activeRawSleepWindowKey", "new-window");
        for (boolean complete : new boolean[]{false, true}) {
            call("publishSleepStatusFromAllDay", new Class<?>[]{String.class, Object.class, Object.class},
                    "all-day-parser", null, new RawSleep(now, complete));
            assertEquals("active", get("sleepTrackingState"));
            assertTrue((Boolean) get("sleepCandidateSeenToday"));
            assertEquals(456L, get("sleepTrackingDayStartMs"));
            assertEquals("new-window", get("activeRawSleepWindowKey"));
            assertNull(get("lastSleepStatusKey"));
        }
    }

    @Test
    public void mergedFinalReportRejectsItsLateRawSegmentWithoutChangingState() throws Exception {
        long now = testWallTimeMs();
        String mergedKey = "1|" + (now - 10_800_000L) + "|"
                + (now - 10_800_000L) + "|" + (now - 3_600_000L);
        set("sleepFinalUploadedKey", mergedKey);
        set("sleepFinalConfirmedKey", mergedKey);
        set("sleepTrackingState", "awake");
        for (boolean complete : new boolean[]{true, false}) {
            call("publishSleepStatusFromAllDay", new Class<?>[]{String.class, Object.class, Object.class},
                    "all-day-parser", null, new RawSleep(now / 1000L, complete));
            assertEquals("awake", get("sleepTrackingState"));
            assertFalse((Boolean) get("sleepCandidateSeenToday"));
            assertFalse((Boolean) get("sleepFinalReportRequested"));
            assertNull(get("lastSleepStatusKey"));
        }
    }

    @Test
    public void rawCoverageDoesNotSuppressExtendedOrNewSleepOrUnconfirmedReport() throws Exception {
        String key = "1|500|1000|100000";
        set("sleepFinalUploadedKey", key);
        set("sleepFinalConfirmedKey", key);
        assertTrue((Boolean) call("isRawCoveredByConfirmedSleepReport",
                new Class<?>[]{snapshotClass()}, snapshot()));
        for (String other : new String[]{"1|500|1000|90000", "1|500|3000|100000",
                "malformed", "1|0|invalid|100000"}) {
            set("sleepFinalUploadedKey", other);
            set("sleepFinalConfirmedKey", other);
            assertFalse((Boolean) call("isRawCoveredByConfirmedSleepReport",
                    new Class<?>[]{snapshotClass()}, snapshot()));
        }
        set("sleepFinalUploadedKey", key);
        set("sleepFinalConfirmedKey", "1|500|1000|99000");
        assertFalse((Boolean) call("isRawCoveredByConfirmedSleepReport",
                new Class<?>[]{snapshotClass()}, snapshot()));
    }

    @Test
    public void earlierSessionReportCannotReplaceUploadedFinalSleep() throws Exception {
        set("appContext", RuntimeEnvironment.getApplication());
        long now = testWallTimeMs();
        Object night = finalReport(now - 60_000L);
        String nightKey = (String) call("finalSleepKey", new Class<?>[]{snapshotClass()}, night);
        set("sleepFinalUploadedKey", nightKey);
        set("sleepFinalConfirmedKey", nightKey);
        Class<?>[] signature = {snapshotClass(), String.class};
        // Yesterday's nap, re-queried right after tonight's final report.
        Object nap = finalReport(now - 60_000L - 3_600_000L - 600_000L);
        assertFalse((Boolean) call("shouldPublishFinalSleepReport", signature, nap, "sleep-biz"));
        // A corrected report of the same session overlaps it and is still accepted.
        Object corrected = finalReport(now - 120_000L);
        assertTrue((Boolean) call("shouldPublishFinalSleepReport", signature, corrected, "sleep-biz"));
        assertFalse((Boolean) call("shouldPublishFinalSleepReport", signature, night, "sleep-biz"));
    }

    @Test
    public void hotReloadRestoresStateButDoesNotPretendOldTimersStillExist() throws Exception {
        Bundle saved = new Bundle();
        saved.putString("sleep_tracking_state", "finalizing");
        saved.putBoolean("provisional_wake_published", true);
        saved.putLong("sleep_tracking_day_start_ms", 123_000L);
        saved.putString("sleep_final_recheck_scheduled_key", "final-window");
        call("restoreHotReloadState", new Class<?>[]{Object.class}, saved);
        assertEquals("finalizing", get("sleepTrackingState"));
        assertEquals(123_000L, get("sleepTrackingDayStartMs"));
        assertTrue((Boolean) get("provisionalWakePublished"));
        assertNull(get("sleepFinalRecheckScheduledKey"));
        assertEquals("final-window", get("hotReloadFinalRecheckKey"));
    }

    @Test
    public void missingDeviceDoesNotLatchReportRecheckAsScheduled() throws Exception {
        set("appContext", RuntimeEnvironment.getApplication());
        set("targetClassLoader", getClass().getClassLoader());
        call("scheduleFinalSleepReportRechecks", new Class<?>[]{snapshotClass(), String.class},
                null, "final-window");
        assertNull(get("sleepFinalRecheckScheduledKey"));
    }

    @Test
    public void observedPacketTypeReplacesFallbackParserAndSameTypeKeepsCache() throws Exception {
        call("registerPacketClass", new Class<?>[]{Class.class, boolean.class}, OldPacket.class, false);
        Method oldParser = (Method) call("resolvePacketParser",
                new Class<?>[]{ClassLoader.class, boolean.class}, getClass().getClassLoader(), false);
        assertEquals(OldPacket.class, oldParser.getDeclaringClass());
        call("registerPacketClass", new Class<?>[]{Class.class, boolean.class}, NewPacket.class, false);
        assertFalse((Boolean) get("wearablePacketParserResolved"));
        assertNull(get("wearablePacketParser"));
        Method newParser = (Method) call("resolvePacketParser",
                new Class<?>[]{ClassLoader.class, boolean.class}, getClass().getClassLoader(), false);
        assertEquals(NewPacket.class, newParser.getDeclaringClass());
        call("registerPacketClass", new Class<?>[]{Class.class, boolean.class}, NewPacket.class, false);
        assertSame(newParser, get("wearablePacketParser"));
        assertTrue((Boolean) get("wearablePacketParserResolved"));
    }

    @Test
    public void subscriptionFailuresBackOffAndResetAfterRecovery() throws Exception {
        long[] delays = {60_000L, 300_000L, 900_000L, 3_600_000L, 3_600_000L};
        for (long delay : delays) {
            call("recordMiWearMonitorFailure", new Class<?>[]{String.class}, "test");
            assertEquals(delay, (Long) get("nextMiWearMonitorAttemptElapsedMs")
                    - SystemClock.elapsedRealtime());
        }
        call("resetMiWearMonitorBackoff");
        assertEquals(0, get("miWearMonitorFailureCount"));
        assertEquals(0L, get("nextMiWearMonitorAttemptElapsedMs"));
    }

    @Test
    public void rejectedNetworkingBindCanBeAttemptedAgain() throws Exception {
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
                return false;
            }
            @Override public void unbindService(ServiceConnection connection) { }
        };
        set("appContext", context);
        call("lookupMiWearLocalDeviceId", new Class<?>[]{String.class}, "watch");
        assertFalse(((AtomicBoolean) get("miWearNetworkingBound")).get());
        assertNull(get("networkingConnection"));
        assertNull(get("networkingBindContext"));
    }

    @Test
    public void staleNetworkingCallbacksCannotClearNewBinding() throws Exception {
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
                return true;
            }
            @Override public void unbindService(ServiceConnection connection) { }
        };
        set("appContext", context);
        call("lookupMiWearLocalDeviceId", new Class<?>[]{String.class}, "old-watch");
        ServiceConnection old = (ServiceConnection) get("networkingConnection");
        call("cancelMiWearNetworkingLookup");
        call("lookupMiWearLocalDeviceId", new Class<?>[]{String.class}, "new-watch");
        ServiceConnection current = (ServiceConnection) get("networkingConnection");
        ComponentName name = new ComponentName("test", "NetworkingService");
        old.onServiceDisconnected(name);
        old.onServiceConnected(name, null);
        assertSame(current, get("networkingConnection"));
        assertTrue(((AtomicBoolean) get("miWearNetworkingBound")).get());
        assertNull(get("miWearLocalDeviceId"));
        call("cancelMiWearNetworkingLookup");
    }

    @Test
    public void repositoryResultRequiresCurrentDeviceStateAndLifecycle() throws Exception {
        set("miWearDeviceDid", "watch");
        set("miWearStateGeneration", 4);
        assertTrue(repositoryRequestCurrent("watch", 4));
        assertFalse(repositoryRequestCurrent("previous-watch", 4));
        assertFalse(repositoryRequestCurrent("watch", 3));
        set("heartRateHookEnabled", false);
        assertFalse(repositoryRequestCurrent("watch", 4));
        set("heartRateHookEnabled", true);
        set("lifecycleActive", false);
        assertFalse(repositoryRequestCurrent("watch", 4));
    }

    private boolean repositoryRequestCurrent(String did, int generation) throws Exception {
        return (Boolean) call("isSleepRepositoryRequestCurrent",
                new Class<?>[]{String.class, int.class}, did, generation);
    }

    @Test
    public void reschedulingReplacesAlarmAndExplicitCancellationStillWorks() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        set("appContext", context);
        org.robolectric.shadows.ShadowAlarmManager alarms = Shadows.shadowOf(
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE));
        for (int generation = 1; generation <= 3; generation++) {
            call("scheduleSyncAlarm", new Class<?>[]{Context.class, long.class, int.class},
                    context, 60_000L * generation, generation);
            call("scheduleSleepStatusAlarm", new Class<?>[]{Context.class, long.class, int.class},
                    context, 60_000L * generation, generation);
            set("heartRateWatchdogAlarmTriggerElapsedMs", 0L);
            call("scheduleHeartRateAlarmWatchdog", new Class<?>[]{String.class}, "test");
            assertEquals(3, alarms.getScheduledAlarms().size());
        }
        call("cancelSleepStatusPoll", new Class<?>[]{Context.class}, context);
        assertEquals(2, alarms.getScheduledAlarms().size());
        call("cancelHeartRateAlarmWatchdog", new Class<?>[]{Context.class}, context);
        assertEquals(1, alarms.getScheduledAlarms().size());
    }

    @Test
    public void watchdogUsesNonWakingAlarmAndReusesPendingDeadline() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        set("appContext", context);
        org.robolectric.shadows.ShadowAlarmManager alarms = Shadows.shadowOf(
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE));
        call("scheduleHeartRateAlarmWatchdog", new Class<?>[]{String.class}, "heart-rate");
        Object pending = alarms.peekNextScheduledAlarm();
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarms.peekNextScheduledAlarm().type);
        assertEquals(300_000L, (long) get("heartRateWatchdogAlarmTriggerElapsedMs")
                - SystemClock.elapsedRealtime());
        int generation = (int) get("heartRateWatchdogGeneration");
        // Even a late, not-yet-delivered alarm must not be postponed by a sample.
        for (int i = 0; i < 100; i++) {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(5));
            call("scheduleHeartRateAlarmWatchdog", new Class<?>[]{String.class}, "heart-rate");
            assertSame(pending, alarms.peekNextScheduledAlarm());
            assertEquals(generation, get("heartRateWatchdogGeneration"));
        }
    }

    @Test
    public void deliveredWatchdogRearmsWithoutRestartingRecentHeartRate() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        set("appContext", context);
        set("targetClassLoader", getClass().getClassLoader());
        set("lastHr", 80);
        set("lastHrElapsedMs", SystemClock.elapsedRealtime());
        call("persistLastHeartRateSeen", new Class<?>[]{long.class}, SystemClock.elapsedRealtime());
        call("scheduleHeartRateAlarmWatchdog", new Class<?>[]{String.class}, "heart-rate");
        int generation = (int) get("heartRateWatchdogGeneration");
        call("handleHeartRateAlarmWatchdog", new Class<?>[]{});
        assertEquals(generation + 1, get("heartRateWatchdogGeneration"));
        assertEquals(80, get("lastHr"));
        assertEquals(0, get("heartRateWatchdogStaleStreak"));
        assertEquals(60_000L, get("HEART_RATE_WATCHDOG_MS"));
    }

    @Test
    public void runtimeSettingsRefreshIsRateLimitedForIdleEfficiency() throws Exception {
        long lastRefresh = SystemClock.elapsedRealtime();
        set("lastRuntimeSettingsRefreshElapsedMs", lastRefresh);
        assertEquals(300_000L, get("RUNTIME_SETTINGS_REFRESH_MS"));
        Context context = RuntimeEnvironment.getApplication();
        set("appContext", context);
        call("refreshRuntimeSettingsIfNeeded", new Class<?>[]{Context.class, boolean.class}, context, false);
        assertEquals(lastRefresh, get("lastRuntimeSettingsRefreshElapsedMs"));
    }

    @Test
    public void recoveredHeartRateShortensBackoffAndCancellationAllowsRearming() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        set("appContext", context);
        set("heartRateWatchdogStaleStreak", 3);
        call("scheduleHeartRateAlarmWatchdog", new Class<?>[]{String.class}, "alarm:stale");
        assertEquals(1_200_000L, (long) get("heartRateWatchdogAlarmTriggerElapsedMs")
                - SystemClock.elapsedRealtime());
        set("heartRateWatchdogStaleStreak", 0);
        call("scheduleHeartRateAlarmWatchdog", new Class<?>[]{String.class}, "heart-rate");
        assertEquals(300_000L, (long) get("heartRateWatchdogAlarmTriggerElapsedMs")
                - SystemClock.elapsedRealtime());
        org.robolectric.shadows.ShadowAlarmManager alarms = Shadows.shadowOf(
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE));
        assertEquals(1, alarms.getScheduledAlarms().size());
        call("cancelHeartRateAlarmWatchdog", new Class<?>[]{Context.class}, context);
        assertEquals(0L, get("heartRateWatchdogAlarmTriggerElapsedMs"));
        assertTrue(alarms.getScheduledAlarms().isEmpty());
        call("scheduleHeartRateAlarmWatchdog", new Class<?>[]{String.class}, "restart");
        assertEquals(1, alarms.getScheduledAlarms().size());
        call("cancelHeartRateAlarmWatchdog", new Class<?>[]{Context.class}, context);
        set("lifecycleActive", false);
        call("scheduleHeartRateAlarmWatchdog", new Class<?>[]{String.class}, "old-generation");
        assertTrue(alarms.getScheduledAlarms().isEmpty());
    }

    @Test
    public void conflictingReportReadsKnownPropertyOnceWithoutPublishingOrSyncing() throws Exception {
        FakeCore core = prepareReportConflict();
        Object report = finalReport(testWallTimeMs() - 60_000L);
        assertFalse((Boolean) call("shouldPublishFinalSleepReport",
                new Class<?>[]{snapshotClass(), String.class}, report, "sleep-biz"));
        assertFalse(verifyReport(report));
        assertEquals(0, core.calls);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, core.calls);
        assertTrue(core.payload.contains("\"method\":\"get_properties\""));
        assertTrue(core.payload.contains("\"siid\":2,\"piid\":1016"));
        assertEquals("asleep", get("miWearState"));
        assertEquals("active", get("sleepTrackingState"));
        assertFalse((Boolean) get("sleepFinalReportRequested"));
        assertNull(get("lastSleepStatusKey"));
        assertFalse(verifyReport(finalReport(testWallTimeMs())));
        SystemClock.sleep(300_001L);
        assertTrue(verifyReport(report));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, core.calls);
    }

    @Test
    public void conflictReadResponseUsesExistingStateMachineWithoutImmediateUpload() throws Exception {
        FakeCore core = prepareReportConflict();
        assertTrue(verifyReport(finalReport(testWallTimeMs())));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, core.calls);
        for (int value : new int[]{0, 1}) {
            String json = "{\"id\":" + (100 + value)
                    + ",\"result\":[{\"siid\":2,\"piid\":1016,\"code\":0,\"value\":"
                    + value + "}]}";
            byte[] frame = (byte[]) call("buildMiWearFrame",
                    new Class<?>[]{String.class, String.class, String.class}, "phone", "watch", json);
            call("handleMiWearPayload", new Class<?>[]{byte[].class}, (Object) frame);
            assertEquals(value == 0 ? "asleep" : "awake", get("miWearState"));
            assertEquals("active", get("sleepTrackingState"));
            assertFalse((Boolean) get("sleepFinalReportRequested"));
            assertFalse((Boolean) get("provisionalWakePublished"));
            assertNull(get("lastSleepStatusKey"));
        }
        assertEquals(1, core.calls);
        assertTrue((Long) get("miWearAwakeObservedAtMs") > 0L);
    }

    @Test
    public void conflictReadFailureKeepsStateAndCooldown() throws Exception {
        FakeCore core = prepareReportConflict();
        core.fail = true;
        Object report = finalReport(testWallTimeMs());
        assertTrue((Boolean) call("isWorkerProcess"));
        assertTrue((Boolean) call("shouldKeepMiWearAsleepState"));
        assertTrue((Boolean) call("isRecentFinalSleepReport",
                new Class<?>[]{snapshotClass()}, report));
        assertTrue(verifyReport(report));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, core.calls);
        assertFalse(verifyReport(report));
        assertEquals("asleep", get("miWearState"));
        assertFalse((Boolean) get("sleepFinalReportRequested"));
        assertNull(get("lastSleepStatusKey"));
    }

    @Test
    public void queuedConflictReadIsCancelledWhenDeviceOrStateOrLifecycleChanges() throws Exception {
        for (String field : new String[]{"miWearDeviceDid", "miWearStateGeneration",
                "lifecycleActive", "heartRateHookEnabled", "miWearRemoteCore"}) {
            FakeCore core = prepareReportConflict();
            set("lastMiWearReportVerifyElapsedMs", -1L);
            assertTrue(verifyReport(finalReport(testWallTimeMs())));
            if (field.equals("miWearDeviceDid")) set(field, "other-watch");
            else if (field.equals("miWearStateGeneration")) set(field, 1);
            else if (field.equals("miWearRemoteCore")) set(field, new FakeCore());
            else set(field, false);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(field, 0, core.calls);
        }
    }

    @Test
    public void conflictReadDoesNotDiscoverOrBindMissingChannel() throws Exception {
        FakeCore core = prepareReportConflict();
        Object report = finalReport(testWallTimeMs());
        set("miWearPropertySiid", -1);
        assertFalse(verifyReport(report));
        set("miWearPropertySiid", 2);
        set("miWearLocalDeviceId", null);
        assertFalse(verifyReport(report));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, core.calls);
        assertFalse(((AtomicBoolean) get("miWearNetworkingBound")).get());
    }

    @Test
    public void historicalOrConfirmedReportDoesNotTriggerConflictRead() throws Exception {
        FakeCore core = prepareReportConflict();
        long now = testWallTimeMs();
        Object report = finalReport(now - 60_000L);
        String key = (String) call("finalSleepKey", new Class<?>[]{snapshotClass()}, report);
        set("sleepFinalConfirmedKey", key);
        assertFalse(verifyReport(report));
        set("sleepFinalConfirmedKey", null);
        set("latestActiveSleepSnapshot", finalReport(now + 7_200_000L));
        assertFalse(verifyReport(report));
        set("latestActiveSleepSnapshot", null);
        assertFalse(verifyReport(finalReport(now - 7 * 86_400_000L)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, core.calls);
    }

    @Test
    public void freshPushOrManualReadPreventsRedundantConflictRead() throws Exception {
        FakeCore core = prepareReportConflict();
        Object report = finalReport(testWallTimeMs());
        set("lastMiWearPushElapsedMs", SystemClock.elapsedRealtime());
        assertFalse(verifyReport(report));
        set("lastMiWearPushElapsedMs", 0L);
        set("lastMiWearActiveRequestElapsedMs", SystemClock.elapsedRealtime());
        assertFalse(verifyReport(report));
        set("lastMiWearActiveRequestElapsedMs", 0L);
        assertTrue(verifyReport(report));
        set("lastMiWearPushElapsedMs", SystemClock.elapsedRealtime());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, core.calls);
    }

    private FakeCore prepareReportConflict() throws Exception {
        FakeCore core = new FakeCore();
        set("appContext", RuntimeEnvironment.getApplication());
        set("lifecycleActive", true);
        set("heartRateHookEnabled", true);
        set("miWearDeviceDid", "watch");
        set("miWearPropertyDid", "watch");
        set("miWearLocalDeviceId", "phone");
        set("miWearPropertySiid", 2);
        set("miWearSubscriptionActive", true);
        set("miWearMonitorRequested", true);
        set("miWearState", "asleep");
        set("miWearStateGeneration", 0);
        set("sleepTrackingState", "active");
        set("miWearRemoteCore", core);
        set("miWearCallback", new Object());
        return core;
    }

    private boolean verifyReport(Object report) throws Exception {
        return (Boolean) call("verifyMiWearStateForConflictingReport",
                new Class<?>[]{snapshotClass(), String.class}, report, "sleep-biz");
    }

    private Object finalReport(long wake) throws Exception {
        Constructor<?> constructor = snapshotClass().getDeclaredConstructor(
                String.class, long.class, long.class, long.class, long.class,
                long.class, long.class, long.class, long.class, String.class,
                boolean.class, long.class, List.class);
        constructor.setAccessible(true);
        long bed = wake - 3_600_000L;
        return constructor.newInstance("awake", testWallTimeMs(), bed, bed, wake,
                bed, bed, wake, wake, "sleep-biz", true, 60L, Collections.emptyList());
    }

    private long testWallTimeMs() {
        // Instrumented System.currentTimeMillis uses the paused Android clock;
        // the uninstrumented test class otherwise reads the host's real clock.
        return SystemClock.uptimeMillis();
    }

    public static final class FakeCore {
        int calls;
        boolean fail;
        String payload;
        public void call(int type, String did, byte[] frame, boolean reliable,
                         Object callback, int timeout) {
            calls++;
            payload = new String(frame, StandardCharsets.UTF_8);
            if (fail) throw new IllegalStateException("test transport failure");
        }
    }

    private boolean ignoreActiveRaw() throws Exception {
        return (Boolean) call("shouldIgnoreActiveRawAfterWake",
                new Class<?>[]{snapshotClass(), String.class}, snapshot(), "all-day-parser");
    }

    @Test
    public void officialEventRequiresEnabledWorkerAndActiveGeneration() throws Exception {
        set("heartRateHookEnabled", false);
        officialEvent(new byte[]{1}, "AA:BB", "watch", "aa:bb");
        assertNull(get("miWearState"));
        set("heartRateHookEnabled", true);
        set("processName", "com.mi.health");
        officialEvent(new byte[]{1}, "AA:BB", "watch", "aa:bb");
        assertNull(get("miWearState"));
        set("processName", "com.mi.health:device");
        set("lifecycleActive", false);
        officialEvent(new byte[]{1}, "AA:BB", "watch", "aa:bb");
        assertFalse((Boolean) get("officialSleepStateAvailable"));
    }

    @Test
    public void officialEventRejectsMissingOrDifferentDeviceIdentity() throws Exception {
        officialEvent(new byte[]{1}, "AA:BB", "watch", "CC:DD");
        officialEvent(new byte[]{1}, null, "watch", "AA:BB");
        officialEvent(new byte[]{1}, "AA:BB", null, "AA:BB");
        officialEvent(new byte[]{1}, "AA:BB", "watch", null);
        set("miWearDeviceDid", "other-watch");
        officialEvent(new byte[]{1}, "AA:BB", "watch", "AA:BB");
        assertNull(get("miWearState"));
        assertFalse((Boolean) get("officialSleepStateAvailable"));
    }

    @Test
    public void officialEventDoesNotTreatOnePacketAsPersistentSubscription() throws Exception {
        officialEvent(new byte[]{1}, "AA:BB", "watch", "aa:bb");
        assertEquals("asleep", get("miWearState"));
        assertEquals("watch", get("officialSleepStateDid"));
        assertFalse((Boolean) call("isMiWearSleepMonitorActive"));
        int generation = (Integer) get("miWearStateGeneration");
        officialEvent(new byte[]{1}, "AA:BB", "watch", "aa:bb");
        assertEquals(generation, get("miWearStateGeneration"));
        officialEvent(new byte[]{0}, "AA:BB", "watch", "aa:bb");
        assertEquals("awake", get("miWearState"));
        assertEquals(generation + 1, get("miWearStateGeneration"));
    }

    @Test
    public void officialEventRejectsUnknownOrEmptyPayload() throws Exception {
        officialEvent(null, "AA:BB", "watch", "AA:BB");
        officialEvent(new byte[0], "AA:BB", "watch", "AA:BB");
        officialEvent(new byte[]{2}, "AA:BB", "watch", "AA:BB");
        assertNull(get("miWearState"));
        assertFalse((Boolean) get("officialSleepStateAvailable"));
    }

    @Test
    public void changingOfficialDeviceInvalidatesPendingStateConfirmation() throws Exception {
        officialEvent(new byte[]{1}, "AA:BB", "watch", "AA:BB");
        int generation = (Integer) get("miWearStateGeneration");
        call("invalidateOfficialSleepDevice", new Class<?>[]{String.class}, "watch");
        assertEquals(generation, get("miWearStateGeneration"));
        call("invalidateOfficialSleepDevice", new Class<?>[]{String.class}, "other-watch");
        assertNull(get("miWearState"));
        assertNull(get("officialSleepStateDid"));
        assertFalse((Boolean) get("officialSleepStateAvailable"));
        assertTrue((Integer) get("miWearStateGeneration") > generation);
    }

    @Test
    public void coreCleanupClearsOfficialEventEvenWithoutType112Adapter() throws Exception {
        officialEvent(new byte[]{1}, "AA:BB", "watch", "AA:BB");
        call("cleanupIsolatedMiWearHandler", new Class<?>[]{String.class, int.class},
                "core-disconnected", get("miWearIsolatedGeneration"));
        assertFalse((Boolean) get("officialSleepStateAvailable"));
        assertNull(get("officialSleepStateDid"));
        assertNull(get("miWearState"));
    }

    @Test
    public void deviceDisconnectClearsOfficialOnlyChannel() throws Exception {
        set("miWearDeviceDid", "watch");
        officialEvent(new byte[]{1}, "AA:BB", "watch", "AA:BB");
        call("handleMiWearDeviceDisconnected", new Class<?>[]{String.class}, "watch");
        assertFalse((Boolean) get("officialSleepStateAvailable"));
        assertNull(get("miWearState"));
    }

    private void officialEvent(byte[] raw, String address, String did, String mac) throws Exception {
        call("handleOfficialSleepState", new Class<?>[]{Object.class, Object.class, byte[].class},
                new OfficialModule(address), new OfficialDevice(did, mac), raw);
    }

    public static final class OfficialModule {
        private final String address;
        OfficialModule(String address) { this.address = address; }
        public String getAddress() { return address; }
    }

    public static final class OfficialDevice {
        private final String did;
        private final String mac;
        OfficialDevice(String did, String mac) { this.did = did; this.mac = mac; }
        public String getDid() { return did; }
        public OfficialDevice getDeviceInfo() { return this; }
        public String getMac() { return mac; }
    }

    private Class<?> snapshotClass() throws Exception {
        return Class.forName(MiHealthHookModule.class.getName() + "$SleepSnapshot");
    }

    private Object snapshot() throws Exception {
        Constructor<?> constructor = snapshotClass().getDeclaredConstructor(
                String.class, long.class, long.class, long.class, long.class,
                long.class, long.class, long.class, long.class, String.class,
                boolean.class, long.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance("asleep", 100_000L, 1_000L, 2_000L, 0L,
                1_000L, 2_000L, 0L, 100_000L, "all-day-parser", false, 10L,
                Collections.emptyList());
    }

    private Object call(String name) throws Exception {
        return call(name, new Class<?>[0]);
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = MiHealthHookModule.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(module, args);
    }

    private void set(String name, Object value) throws Exception {
        Field field = MiHealthHookModule.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(module, value);
    }

    private Object get(String name) throws Exception {
        Field field = MiHealthHookModule.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(module);
    }

    public static final class OldPacket {
        public static OldPacket L(byte[] bytes) { return new OldPacket(); }
    }

    public static final class NewPacket {
        public static NewPacket parseFrom(byte[] bytes) { return new NewPacket(); }
    }

    public static final class RawSleep {
        private final long now;
        private final boolean complete;

        RawSleep(long now, boolean complete) {
            this.now = now;
            this.complete = complete;
        }

        public long getGoBedTime() { return now - 7200L; }
        public long getDeviceBedTime() { return now - 7140L; }
        public long getDeviceWakeupTime() { return now - 3600L; }
        public long getLeaveBedTime() { return now - 3600L; }
        public long getLinBedDuration() { return 3540L; }
        public boolean isSleepFinish() { return complete; }
    }
}
