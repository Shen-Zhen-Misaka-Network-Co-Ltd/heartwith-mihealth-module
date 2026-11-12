package com.heartwith.mihealth.lsp;

import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** SystemUI-only adapter; never changes application identity or global icon settings. */
final class SystemUiHeartRateIcon {
    static final String PACKAGE = "com.android.systemui";
    private static final String TAG = "HeartwithMiHealth";

    static void install(XposedModule module, ClassLoader loader) {
        if (BuildConfig.DEBUG) removeLegacyPinnedSection(module, loader);
        try {
            Class<?> entry = Class.forName(
                    "com.android.systemui.statusbar.notification.collection.NotificationEntry", false, loader);
            Class<?> manager = Class.forName(
                    "com.android.systemui.statusbar.notification.icon.IconManager", false, loader);
            Method descriptor = manager.getDeclaredMethod("getIconDescriptor", entry, boolean.class);
            Field sbn = entry.getDeclaredField("mSbn");
            Field icon = descriptor.getReturnType().getField("icon");
            if (!StatusBarNotification.class.isAssignableFrom(sbn.getType()) || icon.getType() != Icon.class) {
                throw new NoSuchFieldException("Unexpected notification descriptor shape");
            }
            sbn.setAccessible(true);
            icon.setAccessible(true);
            module.hook(descriptor).intercept(chain -> {
                Object result = chain.proceed();
                StatusBarNotification notification = (StatusBarNotification) sbn.get(chain.getArg(0));
                if (result != null && matches(notification)) {
                    // The descriptor belongs to this notification, including its cached copies.
                    icon.set(result, notification.getNotification().getSmallIcon());
                }
                return result;
            });
            Log.i(TAG, "SystemUI Heartwith numeric icon hook installed");
        } catch (ReflectiveOperationException | LinkageError error) {
            Log.w(TAG, "SystemUI numeric icon adapter unavailable; keeping system icon", error);
            return;
        }
        try {
            Class<?> expanded = Class.forName(
                    "com.android.systemui.statusbar.notification.ExpandedNotification", false, loader);
            Class<?> util = Class.forName(
                    "com.android.systemui.statusbar.notification.utils.NotifImageUtil", false, loader);
            Method substitute = util.getDeclaredMethod("shouldSubstituteSmallIcon", expanded);
            module.hook(substitute).intercept(chain -> matches((StatusBarNotification) chain.getArg(0))
                    ? false : chain.proceed());
        } catch (ReflectiveOperationException | LinkageError error) {
            Log.w(TAG, "SystemUI numeric icon tint adapter unavailable", error);
        }
    }

    // One-shot migration for the earlier debug build; no ranking hook remains afterwards.
    private static void removeLegacyPinnedSection(XposedModule module, ClassLoader loader) {
        try {
            Class<?> builder = Class.forName(
                    "com.android.systemui.statusbar.notification.collection.ShadeListBuilder", false, loader);
            Field sections = builder.getDeclaredField("mNotifSections");
            Class<?> section = Class.forName(
                    "com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection", false, loader);
            Field sectioner = section.getDeclaredField("sectioner");
            Field name = sectioner.getType().getSuperclass().getDeclaredField("mName");
            Field defaultSection = builder.getDeclaredField("DEFAULT_SECTIONER");
            sections.setAccessible(true);
            sectioner.setAccessible(true);
            name.setAccessible(true);
            defaultSection.setAccessible(true);
            Method setSectioners = builder.getDeclaredMethod("setSectioners", List.class);
            setSectioners.setAccessible(true);
            AtomicBoolean initialized = new AtomicBoolean();
            AtomicReference<XposedInterface.HookHandle> handle = new AtomicReference<>();
            XposedInterface.HookHandle installed = module.hook(builder.getDeclaredMethod("buildList")).intercept(chain -> {
                if (initialized.compareAndSet(false, true)) {
                    try {
                        List<?> current = (List<?>) sections.get(chain.getThisObject());
                        List<Object> next = new ArrayList<>(current.size());
                        Object fallback = defaultSection.get(null);
                        boolean changed = false;
                        for (Object item : current) {
                            Object value = sectioner.get(item);
                            if ("HeartwithPinned".equals(name.get(value))) {
                                changed = true;
                                continue;
                            }
                            if (value != fallback) next.add(value);
                        }
                        if (changed) {
                            setSectioners.invoke(chain.getThisObject(), next);
                            Log.i(TAG, "SystemUI Heartwith restored system notification order");
                        }
                    } catch (ReflectiveOperationException | LinkageError error) {
                        Log.w(TAG, "SystemUI debug section cleanup failed", error);
                    } finally {
                        XposedInterface.HookHandle active = handle.getAndSet(null);
                        if (active != null) active.unhook();
                    }
                }
                return chain.proceed();
            });
            handle.set(installed);
            if (initialized.get()) {
                XposedInterface.HookHandle active = handle.getAndSet(null);
                if (active != null) active.unhook();
            }
        } catch (ReflectiveOperationException | LinkageError error) {
            Log.w(TAG, "SystemUI debug section cleanup unavailable", error);
        }
    }

    static boolean matches(StatusBarNotification sbn) {
        if (sbn == null || sbn.getId() != HeartwithStatus.NOTIFICATION_ID + 1 || sbn.getTag() != null) {
            return false;
        }
        String pkg = sbn.getPackageName();
        if (!"com.mi.health".equals(pkg) && !"com.mi.health.heartwith".equals(pkg)) return false;
        return HeartwithStatus.HOOK_CHANNEL_ID.equals(sbn.getNotification().getChannelId())
                && sbn.getNotification().getSmallIcon() != null;
    }
}
