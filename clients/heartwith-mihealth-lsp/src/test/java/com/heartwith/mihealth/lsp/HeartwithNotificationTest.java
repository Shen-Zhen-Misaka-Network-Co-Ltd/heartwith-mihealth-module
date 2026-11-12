package com.heartwith.mihealth.lsp;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Icon;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class HeartwithNotificationTest {
    private Context context;
    private NotificationManager manager;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(NotificationManager.class);
        manager.cancelAll();
        reset("notificationBuilder", null);
        reset("notificationChannelReady", false);
    }

    @Test
    public void updatesKeepTimestampAndSwapLines() {
        HeartwithStatus.showHookProcessNotification(context, 72, "wear-raw", 1000L);
        Icon firstIcon = notification().getSmallIcon();
        HeartwithStatus.showHookProcessNotification(context, 123, "sport-packet", 9000L);
        Notification second = notification();
        assertEquals(1000L, second.when);
        assertFalse(second.extras.getBoolean(Notification.EXTRA_SHOW_WHEN));
        assertTrue(second.extras.getString(Notification.EXTRA_TITLE).endsWith("sport-packet"));
        assertEquals("Heartwith · 123 BPM", second.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(Notification.CATEGORY_SERVICE, second.category);
        assertEquals(Notification.PRIORITY_DEFAULT, second.priority);
        assertEquals(Notification.VISIBILITY_PUBLIC, second.visibility);
        assertTrue((second.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        assertTrue((second.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertEquals(NotificationManager.IMPORTANCE_LOW,
                manager.getNotificationChannel(HeartwithStatus.HOOK_CHANNEL_ID).getImportance());
        assertEquals(Icon.TYPE_BITMAP, second.getSmallIcon().getType());
        assertNotSame(firstIcon, second.getSmallIcon());
    }

    @Test
    public void unchangedContentDoesNotRepostNotification() {
        HeartwithStatus.showHookProcessNotification(context, 72, "wear-raw", 1000L);
        // Clear the test sink to distinguish a skipped notify from Builder object reuse.
        manager.cancelAll();
        HeartwithStatus.showHookProcessNotification(context, 72, "wear-raw", 9000L);
        assertEquals(0, manager.getActiveNotifications().length);
        HeartwithStatus.showHookProcessNotification(context, 72, "sport-packet", 15000L);
        assertEquals(1, manager.getActiveNotifications().length);
        assertTrue(notification().extras.getString(Notification.EXTRA_TITLE).endsWith("sport-packet"));
    }

    @Test
    public void reloadPreservesPostedTimeAndReusesNumericIcon() throws Exception {
        HeartwithStatus.showHookProcessNotification(context, 72, "wear-raw", 1000L);
        Icon icon = notification().getSmallIcon();
        reset("notificationBuilder", null);
        HeartwithStatus.showHookProcessNotification(context, 72, "wear-raw", 9000L);
        assertEquals(1000L, notification().when);
        assertSame(icon, notification().getSmallIcon());
        assertEquals(1, manager.getActiveNotifications().length);
    }

    private Notification notification() {
        return manager.getActiveNotifications()[0].getNotification();
    }

    private static void reset(String name, Object value) throws Exception {
        Field field = HeartwithStatus.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
