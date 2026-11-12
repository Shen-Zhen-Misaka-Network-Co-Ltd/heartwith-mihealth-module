package com.heartwith.mihealth.lsp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

public final class HeartwithFileUploadStoreTest {
    @Test
    public void savesLoadsAndClearsPerProcess() throws Exception {
        File dir = Files.createTempDirectory("heartwith-store").toFile();
        HeartwithFileUploadStore device = new HeartwithFileUploadStore(dir, "com.mi.health:device");
        HeartwithFileUploadStore main = new HeartwithFileUploadStore(dir, "com.mi.health");
        assertNull(device.load());
        device.save(new byte[]{1, 2, 3});
        main.save(new byte[]{9});
        assertArrayEquals(new byte[]{1, 2, 3}, device.load());
        assertArrayEquals(new byte[]{9}, main.load());
        device.save(new byte[0]);
        assertNull(device.load());
        assertArrayEquals(new byte[]{9}, main.load());
        assertFalse(new File(dir, "heartwith-upload-backlog-com.mi.health_device.bin.tmp").exists());
    }
}
