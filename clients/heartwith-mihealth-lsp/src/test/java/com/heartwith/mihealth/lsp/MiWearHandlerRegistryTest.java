package com.heartwith.mihealth.lsp;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class MiWearHandlerRegistryTest {
    @Test public void oldDidOnlyRegistryReusesMatchingType() {
        Adapter adapter = new Adapter("watch", 112);
        assertSame(adapter, MiWearHandlerRegistry.findAdapter(Map.of("watch", adapter), "watch", 112));
    }

    @Test public void oldFitnessAdapterMustNotBeReused() {
        Adapter adapter = new Adapter("watch", 199);
        assertNull(MiWearHandlerRegistry.findAdapter(Map.of("watch", adapter), "watch", 112));
    }

    @Test public void compositeKeyRegistryFindsExactTypeRegardlessOfIterationOrder() {
        Adapter sleep = new Adapter("watch", 112);
        Map<String, Object> handlers = new LinkedHashMap<>();
        handlers.put("watch_199", new Adapter("watch", 199));
        handlers.put("watch2_112", new Adapter("watch2", 112));
        handlers.put("watch_112", sleep);
        assertSame(sleep, MiWearHandlerRegistry.findAdapter(handlers, "watch", 112));
    }

    @Test public void legacyKeyDoesNotHideAnotherMatchingAdapter() {
        Adapter sleep = new Adapter("watch", 112);
        assertSame(sleep, MiWearHandlerRegistry.findAdapter(Map.of(
                "watch", new Adapter("watch", 199), "arbitrary-key", sleep), "watch", 112));
    }

    @Test public void lookupNeverCreatesAnEntry() {
        Map<String, Object> handlers = new LinkedHashMap<>();
        assertNull(MiWearHandlerRegistry.findAdapter(handlers, "watch", 112));
        org.junit.Assert.assertTrue(handlers.isEmpty());
    }

    @Test public void ignoresWrongDeviceAndUnknownShapes() {
        assertNull(MiWearHandlerRegistry.findAdapter(Map.of("watch", new Adapter("other", 112)), "watch", 112));
        assertNull(MiWearHandlerRegistry.findAdapter(Map.of("watch", new Object()), "watch", 112));
        assertNull(MiWearHandlerRegistry.findAdapter(null, "watch", 112));
        assertNull(MiWearHandlerRegistry.findAdapter(Map.of(), null, 112));
    }

    public static class Adapter {
        private final String did;
        private final int type;

        Adapter(String did, int type) {
            this.did = did;
            this.type = type;
        }

        public String getDid() { return did; }
        public int getType() { return type; }
    }
}
