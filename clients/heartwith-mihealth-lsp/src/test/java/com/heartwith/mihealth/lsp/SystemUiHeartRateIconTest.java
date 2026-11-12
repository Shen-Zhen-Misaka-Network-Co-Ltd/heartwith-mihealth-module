package com.heartwith.mihealth.lsp;

import static org.junit.Assert.*;
import android.app.Notification;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class SystemUiHeartRateIconTest {
    @Test
    public void matchesOnlyHeartwithChannelAndIdInHealthPackages() {
        assertTrue(SystemUiHeartRateIcon.matches(notification("com.mi.health", 23015, null,
                HeartwithStatus.HOOK_CHANNEL_ID)));
        assertTrue(SystemUiHeartRateIcon.matches(notification("com.mi.health.heartwith", 23015, null,
                HeartwithStatus.HOOK_CHANNEL_ID)));
        assertFalse(SystemUiHeartRateIcon.matches(notification("com.other.app", 23015, null,
                HeartwithStatus.HOOK_CHANNEL_ID)));
        assertFalse(SystemUiHeartRateIcon.matches(notification("com.mi.health", 1001, null,
                HeartwithStatus.HOOK_CHANNEL_ID)));
        assertFalse(SystemUiHeartRateIcon.matches(notification("com.mi.health", 23015, "other",
                HeartwithStatus.HOOK_CHANNEL_ID)));
        assertFalse(SystemUiHeartRateIcon.matches(notification("com.mi.health", 23015, null,
                "other-channel")));
        assertFalse(SystemUiHeartRateIcon.matches(null));
    }

    private StatusBarNotification notification(String pkg, int id, String tag, String channel) {
        Notification notification = new Notification.Builder(RuntimeEnvironment.getApplication(), channel)
                .setSmallIcon(android.R.drawable.stat_notify_sync).build();
        return new StatusBarNotification(pkg, pkg, id, tag, 10000, 123, 0,
                notification, Process.myUserHandle(), 1000L);
    }
}
