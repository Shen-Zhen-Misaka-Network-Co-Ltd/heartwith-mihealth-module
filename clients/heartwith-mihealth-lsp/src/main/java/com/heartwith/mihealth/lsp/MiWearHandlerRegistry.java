package com.heartwith.mihealth.lsp;

import java.lang.reflect.Method;
import java.util.Map;

final class MiWearHandlerRegistry {
    private MiWearHandlerRegistry() {
    }

    static Object findAdapter(Object handlers, String did, int type) {
        if (!(handlers instanceof Map<?, ?>) || did == null || did.isEmpty()) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) handlers;
        Object direct = map.get(did);
        if (matches(direct, did, type)) {
            return direct;
        }
        // MiWear releases use either did or did+type as the key. The adapter's
        // public metadata is the contract, not the private key encoding.
        for (Object adapter : map.values()) {
            if (adapter != direct && matches(adapter, did, type)) {
                return adapter;
            }
        }
        return null;
    }

    private static boolean matches(Object adapter, String did, int type) {
        if (!matchesDevice(adapter, did)) {
            return false;
        }
        try {
            Method getType = adapter.getClass().getMethod("getType");
            getType.setAccessible(true);
            Object adapterType = getType.invoke(adapter);
            return adapterType instanceof Number && ((Number) adapterType).intValue() == type;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean matchesDevice(Object adapter, String did) {
        if (adapter == null) {
            return false;
        }
        try {
            Method getDid = adapter.getClass().getMethod("getDid");
            getDid.setAccessible(true);
            return did.equals(getDid.invoke(adapter));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }
}
