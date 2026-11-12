package com.heartwith.mihealth.lsp;

import com.heartwith.uploader.HeartwithUploadStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Upload backlog kept in the host's private files dir, one file per process so the
 * main and :device processes never share or clobber each other's snapshot.
 */
final class HeartwithFileUploadStore implements HeartwithUploadStore {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final File file;

    HeartwithFileUploadStore(File dir, String processName) {
        String suffix = processName == null ? "main" : processName.replaceAll("[^A-Za-z0-9_.-]", "_");
        this.file = new File(dir, "heartwith-upload-backlog-" + suffix + ".bin");
    }

    @Override
    public byte[] load() throws IOException {
        if (!file.isFile()) {
            return null;
        }
        long length = file.length();
        if (length <= 0L || length > MAX_BYTES) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) length);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Override
    public void save(byte[] snapshot) throws IOException {
        if (snapshot == null || snapshot.length == 0) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return;
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(snapshot);
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename failed");
        }
    }
}
