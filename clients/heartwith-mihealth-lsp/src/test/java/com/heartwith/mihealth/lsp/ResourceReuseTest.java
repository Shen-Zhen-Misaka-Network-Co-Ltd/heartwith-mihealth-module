package com.heartwith.mihealth.lsp;

import static org.junit.Assert.*;

import android.content.Context;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, instrumentedPackages = "com.heartwith.mihealth.lsp")
@LooperMode(LooperMode.Mode.PAUSED)
public final class ResourceReuseTest {
    @After
    public void closeLog() {
        DebugSleepLog.close();
    }

    @Test
    public void uploadSourceReusesEqualInputAndPreservesNormalization() throws Exception {
        HeartwithUploader uploader = new HeartwithUploader(Runnable::run);
        try {
            Method method = HeartwithUploader.class.getDeclaredMethod("uploadSource", String.class);
            method.setAccessible(true);
            Object first = method.invoke(uploader, "wear-raw");
            assertEquals("mi_health_hook:wear-raw", first);
            assertSame(first, method.invoke(uploader, new String("wear-raw")));
            assertEquals("mi_health_hook:sport-packet", method.invoke(uploader, " sport-packet "));
            assertEquals("mi_health_hook", method.invoke(uploader, (Object) null));
            assertEquals("mi_health_hook", method.invoke(uploader, "  "));
            assertEquals("mi_health_hook:wear-raw", method.invoke(uploader, "wear-raw"));
        } finally {
            uploader.close();
        }
    }

    @Test
    public void eachLineIsVisibleImmediatelyAndWriterIsReused() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        DebugSleepLog.init(context, "test");
        Object writer = logField("writer").get(null);
        DebugSleepLog.line(context, "test", "first-record");
        assertTrue(contents().contains("first-record"));
        DebugSleepLog.line(context, "test", "second-record");
        assertTrue(contents().contains("second-record"));
        assertSame(writer, logField("writer").get(null));
        DebugSleepLog.init(context, "test");
        assertSame(writer, logField("writer").get(null));
    }

    @Test
    public void closePreventsLateWritesAndReinitAppends() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        DebugSleepLog.init(context, "test");
        DebugSleepLog.line(context, "test", "before-close");
        DebugSleepLog.close();
        assertNull(logField("writer").get(null));
        DebugSleepLog.line(context, "test", "late-record");
        assertFalse(contents().contains("late-record"));
        DebugSleepLog.init(context, "test");
        DebugSleepLog.line(context, "test", "after-reinit");
        assertTrue(contents().contains("before-close"));
        assertTrue(contents().contains("after-reinit"));
    }

    @Test
    public void dayChangeReopensWriterWithoutTruncatingRecords() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        DebugSleepLog.init(context, "test");
        DebugSleepLog.line(context, "test", "before-rotation");
        Object writer = logField("writer").get(null);
        logField("logDay").set(null, "previous-day");
        DebugSleepLog.line(context, "test", "after-rotation");
        assertNotSame(writer, logField("writer").get(null));
        assertTrue(contents().contains("before-rotation"));
        assertTrue(contents().contains("after-rotation"));
    }

    private Field logField(String name) throws Exception {
        Field field = DebugSleepLog.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private String contents() throws Exception {
        File file = (File) logField("logFile").get(null);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
