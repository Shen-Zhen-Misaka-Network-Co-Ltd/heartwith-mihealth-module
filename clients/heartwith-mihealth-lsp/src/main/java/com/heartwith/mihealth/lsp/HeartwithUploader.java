package com.heartwith.mihealth.lsp;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HeartwithUploader {
    private static final String TAG = "HeartwithMiHealth";
    private static final String RUNTIME_PREFS = "heartwith_mihealth_runtime";
    private static final String KEY_CACHED_ENABLED = "cached_enabled";
    private static final String KEY_CACHED_SERVER_URL = "cached_server_url";
    private static final String KEY_CACHED_DISPLAY_NAME = "cached_display_name";
    private static final String KEY_CACHED_DEVICE_MODEL = "cached_device_model";
    private static final String DEFAULT_DEVICE_MODEL = "Xiaomi Health Hook";
    private static final String CLIENT_PLATFORM = "android-lsposed";
    private static final long BATCH_WINDOW_MS = 8_000L;
    private static final long MAX_BATCH_WINDOW_MS = 8_000L;
    private static final long OFFLINE_CACHE_MS = 300_000L;
    private static final long RETRY_BACKOFF_MS = 15_000L;
    private static final int CHANGE_FLUSH_BPM = 3;
    private static final Pattern COLLECTOR_ID = Pattern.compile("\"collector_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern COLLECTOR_TOKEN = Pattern.compile("\"collector_token\"\\s*:\\s*\"([^\"]+)\"");

    private final Executor worker;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private HeartwithSettings settings = new HeartwithSettings(true, HeartwithSettings.DEFAULT_SERVER_URL, "Android");
    private String deviceModel = DEFAULT_DEVICE_MODEL;
    private Session session;
    private long lastSettingsReadMs;
    private boolean settingsLoaded;
    private long lastFlushMs;
    private long nextUploadAttemptMs;
    private long lastFailureLogElapsedMs;
    private long lastSettingsLogElapsedMs;
    private long lastUploadSuccessLogElapsedMs;
    private long seq = 1;
    private int lastUploadedBpm = -1;
    private boolean uploadInFlight;
    private boolean runtimeCacheLoaded;
    private boolean delayedFlushScheduled;

    HeartwithUploader(Executor worker) {
        this.worker = worker;
    }

    synchronized void warmUp(Context context) {
        refreshSettingsIfNeeded(context, true);
        logSettings("warmup");
    }

    synchronized void applySettings(Context context, HeartwithSettings next, String reason) {
        if (!next.serverUrl.equals(settings.serverUrl) || !next.displayName.equals(settings.displayName)) {
            session = null;
            seq = 1;
        }
        settings = next;
        settingsLoaded = true;
        runtimeCacheLoaded = true;
        persistRuntimeCache(context, next);
        logSettings(reason);
    }

    synchronized boolean setDeviceModel(Context context, String model) {
        String next = sanitizeDeviceModel(model);
        if (next.equals(deviceModel)) {
            return false;
        }
        deviceModel = next;
        session = null;
        seq = 1;
        persistDeviceModel(context, next);
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "device model resolved: " + next);
        }
        return true;
    }

    synchronized void onHeartRate(Context context, int bpm, String source) {
        if (bpm < 30 || bpm > 240) {
            return;
        }
        long now = System.currentTimeMillis();
        samples.addLast(new Sample(now, bpm, source));
        trim(now);
        if (!shouldFlush(now, bpm) || uploadInFlight || now < nextUploadAttemptMs) {
            scheduleDelayedFlush(context);
            return;
        }
        uploadInFlight = true;
        try {
            refreshSettingsIfNeeded(context, false);
            if (!settingsLoaded) {
                logState("settings unavailable; keep samples cached");
                return;
            }
            if (settings.enabled) {
                uploadLocked();
            } else {
                samples.clear();
                session = null;
            }
        } finally {
            uploadInFlight = false;
        }
    }

    private void scheduleDelayedFlush(final Context context) {
        if (context == null || delayedFlushScheduled || samples.isEmpty()) {
            return;
        }
        delayedFlushScheduled = true;
        try {
            new Handler(context.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    worker.execute(new Runnable() {
                        @Override
                        public void run() {
                            flushDelayed(context);
                        }
                    });
                }
            }, MAX_BATCH_WINDOW_MS);
        } catch (Throwable ignored) {
            delayedFlushScheduled = false;
        }
    }

    private synchronized void flushDelayed(Context context) {
        delayedFlushScheduled = false;
        long now = System.currentTimeMillis();
        trim(now);
        if (samples.isEmpty()) {
            return;
        }
        if (uploadInFlight || now < nextUploadAttemptMs) {
            scheduleDelayedFlush(context);
            return;
        }
        uploadInFlight = true;
        try {
            refreshSettingsIfNeeded(context, false);
            if (!settingsLoaded) {
                logState("settings unavailable; keep delayed samples cached");
                scheduleDelayedFlush(context);
                return;
            }
            if (settings.enabled) {
                uploadLocked();
            } else {
                samples.clear();
                session = null;
            }
        } finally {
            uploadInFlight = false;
        }
    }

    private boolean shouldFlush(long now, int bpm) {
        if (samples.isEmpty()) {
            return false;
        }
        if (lastFlushMs == 0L) {
            lastFlushMs = samples.peekFirst().tMs;
        }
        Sample first = samples.peekFirst();
        if (now - lastFlushMs >= BATCH_WINDOW_MS) {
            return true;
        }
        if (first != null && now - first.tMs >= MAX_BATCH_WINDOW_MS) {
            return true;
        }
        return lastUploadedBpm > 0 && Math.abs(bpm - lastUploadedBpm) >= CHANGE_FLUSH_BPM;
    }

    private void refreshSettingsIfNeeded(Context context, boolean force) {
        loadRuntimeCacheIfNeeded(context);
        long elapsed = SystemClock.elapsedRealtime();
        if (!force && settingsLoaded && elapsed - lastSettingsReadMs < 30_000L) {
            return;
        }
        lastSettingsReadMs = elapsed;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(SettingsProvider.URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                boolean enabled = cursor.getInt(cursor.getColumnIndexOrThrow(HeartwithSettings.KEY_ENABLED)) != 0;
                String serverUrl = cursor.getString(cursor.getColumnIndexOrThrow(HeartwithSettings.KEY_SERVER_URL));
                String displayName = cursor.getString(cursor.getColumnIndexOrThrow(HeartwithSettings.KEY_DISPLAY_NAME));
                HeartwithSettings next = new HeartwithSettings(enabled, serverUrl, displayName);
                if (!next.serverUrl.equals(settings.serverUrl) || !next.displayName.equals(settings.displayName)) {
                    session = null;
                    seq = 1;
                }
                settings = next;
                settingsLoaded = true;
                persistRuntimeCache(context, next);
                logSettings("settings provider synced");
            }
        } catch (Throwable throwable) {
            logFailure("read settings failed", throwable);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (!settingsLoaded) {
            applySettings(context, settings, "settings fallback");
        }
    }

    private void loadRuntimeCacheIfNeeded(Context context) {
        if (runtimeCacheLoaded || settingsLoaded) {
            return;
        }
        runtimeCacheLoaded = true;
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE);
            String serverUrl = prefs.getString(KEY_CACHED_SERVER_URL, "");
            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                return;
            }
            boolean enabled = prefs.getBoolean(KEY_CACHED_ENABLED, true);
            String displayName = prefs.getString(KEY_CACHED_DISPLAY_NAME, "Android");
            deviceModel = sanitizeDeviceModel(prefs.getString(KEY_CACHED_DEVICE_MODEL, DEFAULT_DEVICE_MODEL));
            settings = new HeartwithSettings(enabled, serverUrl, displayName);
            settingsLoaded = true;
            logSettings("settings cache loaded");
        } catch (Throwable ignored) {
        }
    }

    private void persistDeviceModel(Context context, String model) {
        if (context == null) {
            return;
        }
        try {
            context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CACHED_DEVICE_MODEL, sanitizeDeviceModel(model))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void persistRuntimeCache(Context context, HeartwithSettings next) {
        try {
            context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_CACHED_ENABLED, next.enabled)
                    .putString(KEY_CACHED_SERVER_URL, next.serverUrl)
                    .putString(KEY_CACHED_DISPLAY_NAME, next.displayName)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void uploadLocked() {
        try {
            ensureSession();
            if (session == null || samples.isEmpty()) {
                return;
            }
            int sampleCount = samples.size();
            byte[] body = buildBatchCbor(session.collectorId, seq, settings.displayName, deviceModel);
            Response response = post(
                    settings.serverUrl + "/api/v1/hr/batches",
                    "application/cbor",
                    body,
                    "Bearer " + session.collectorToken);
            if (response.code < 200 || response.code >= 300) {
                throw new IllegalStateException("batch http " + response.code);
            }
            Sample last = samples.peekLast();
            lastUploadedBpm = last != null ? last.bpm : lastUploadedBpm;
            samples.clear();
            lastFlushMs = System.currentTimeMillis();
            nextUploadAttemptMs = 0L;
            seq += 1;
            logUploadSuccess(sampleCount);
        } catch (Throwable throwable) {
            nextUploadAttemptMs = System.currentTimeMillis() + RETRY_BACKOFF_MS;
            logFailure("upload failed", throwable);
        }
    }

    private void logFailure(String prefix, Throwable throwable) {
        long elapsed = SystemClock.elapsedRealtime();
        if (lastFailureLogElapsedMs > 0L && elapsed - lastFailureLogElapsedMs < 60_000L) {
            return;
        }
        lastFailureLogElapsedMs = elapsed;
        Log.w(TAG, prefix + ": " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
    }

    private void logState(String message) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        if (lastFailureLogElapsedMs > 0L && elapsed - lastFailureLogElapsedMs < 60_000L) {
            return;
        }
        lastFailureLogElapsedMs = elapsed;
        Log.i(TAG, message);
    }

    private void logSettings(String prefix) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        if (lastSettingsLogElapsedMs > 0L && elapsed - lastSettingsLogElapsedMs < 60_000L) {
            return;
        }
        lastSettingsLogElapsedMs = elapsed;
        Log.i(TAG, prefix + ": loaded=" + settingsLoaded
                + ", enabled=" + settings.enabled
                + ", server=" + settings.serverUrl
                + ", display=" + settings.displayName
                + ", device=" + deviceModel);
    }

    private void logUploadSuccess(int sampleCount) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        if (lastUploadSuccessLogElapsedMs > 0L && elapsed - lastUploadSuccessLogElapsedMs < 60_000L) {
            return;
        }
        lastUploadSuccessLogElapsedMs = elapsed;
        Log.i(TAG, "upload ok: samples=" + sampleCount + ", seq=" + (seq - 1));
    }

    private void ensureSession() throws Exception {
        if (session != null) {
            return;
        }
        String json = "{"
                + "\"display_name\":\"" + escapeJson(settings.displayName) + "\","
                + "\"device_model\":\"" + escapeJson(deviceModel) + "\","
                + "\"client_platform\":\"" + CLIENT_PLATFORM + "\","
                + "\"app_version\":\"" + BuildConfig.VERSION_NAME + "\""
                + "}";
        Response response = post(
                settings.serverUrl + "/api/v1/collector/sessions",
                "application/json; charset=utf-8",
                json.getBytes(StandardCharsets.UTF_8),
                null);
        if (response.code < 200 || response.code >= 300) {
            throw new IllegalStateException("session http " + response.code);
        }
        String collectorId = match(response.body, COLLECTOR_ID);
        String token = match(response.body, COLLECTOR_TOKEN);
        if (collectorId == null || token == null) {
            throw new IllegalStateException("session response missing credentials");
        }
        session = new Session(collectorId, token);
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "session created: collector=" + collectorId + ", device=" + deviceModel);
        }
    }

    private Response post(String url, String contentType, byte[] body, String authorization) throws Exception {
        try {
            return urlConnectionPost(url, contentType, body, authorization);
        } catch (Exception throwable) {
            if (!shouldFallbackToRawHttp(url, throwable)) {
                throw throwable;
            }
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "fallback raw http post: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage(), throwable);
            }
            return rawHttpPost(url, contentType, body, authorization);
        }
    }

    private Response urlConnectionPost(String url, String contentType, byte[] body, String authorization) throws Exception {
        boolean cleartext = url.startsWith("http://");
        if (cleartext) {
            HeartwithCleartextScope.enter();
        }
        try {
            HttpURLConnection connection = open(url, "POST", contentType);
            return execute(connection, body, authorization);
        } finally {
            if (cleartext) {
                HeartwithCleartextScope.exit();
            }
        }
    }

    private Response execute(HttpURLConnection connection, byte[] body, String authorization) throws Exception {
        boolean completed = false;
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        try {
            writeBody(connection, body);
            int code = connection.getResponseCode();
            String responseBody = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            completed = true;
            return new Response(code, responseBody);
        } finally {
            if (!completed) {
                connection.disconnect();
            }
        }
    }

    private boolean shouldFallbackToRawHttp(String url, Throwable throwable) {
        if (!url.startsWith("http://")) {
            return false;
        }
        String message = throwable.getMessage();
        if (message == null) {
            message = "";
        }
        String lower = message.toLowerCase();
        return throwable instanceof SecurityException
                || lower.contains("cleartext")
                || lower.contains("not permitted")
                || lower.contains("unknown service");
    }

    private Response rawHttpPost(String urlValue, String contentType, byte[] body, String authorization) throws Exception {
        URL url = new URL(urlValue);
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : 80;
        String path = url.getFile() == null || url.getFile().isEmpty() ? "/" : url.getFile();
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 2_500);
        socket.setSoTimeout(5_000);
        try {
            OutputStream output = socket.getOutputStream();
            StringBuilder headers = new StringBuilder();
            headers.append("POST ").append(path).append(" HTTP/1.1\r\n");
            headers.append("Host: ").append(host);
            if (url.getPort() > 0) {
                headers.append(':').append(port);
            }
            headers.append("\r\nConnection: close\r\n");
            headers.append("Content-Type: ").append(contentType).append("\r\n");
            headers.append("Content-Length: ").append(body.length).append("\r\n");
            if (authorization != null) {
                headers.append("Authorization: ").append(authorization).append("\r\n");
            }
            headers.append("\r\n");
            output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();

            byte[] responseBytes = readAllBytes(socket.getInputStream());
            String responseText = new String(responseBytes, StandardCharsets.UTF_8);
            int statusEnd = responseText.indexOf("\r\n");
            if (statusEnd < 0 || !responseText.startsWith("HTTP/")) {
                throw new IllegalStateException("bad http response");
            }
            String statusLine = responseText.substring(0, statusEnd);
            String[] parts = statusLine.split(" ");
            int code = parts.length >= 2 ? Integer.parseInt(parts[1]) : 0;
            int bodyStart = responseText.indexOf("\r\n\r\n");
            String responseBody = bodyStart >= 0 ? responseText.substring(bodyStart + 4) : "";
            return new Response(code, responseBody);
        } finally {
            socket.close();
        }
    }

    private HttpURLConnection open(String url, String method, String contentType) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(2_500);
        connection.setReadTimeout(5_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Connection", "keep-alive");
        connection.setDoOutput(true);
        return connection;
    }

    private void writeBody(HttpURLConnection connection, byte[] body) throws Exception {
        connection.setFixedLengthStreamingMode(body.length);
        OutputStream output = connection.getOutputStream();
        output.write(body);
        output.close();
    }

    private byte[] buildBatchCbor(String collectorId, long packetSeq, String displayName, String deviceModel) {
        long sentAtMs = System.currentTimeMillis();
        Cbor cbor = new Cbor();
        cbor.map(8);
        cbor.text("schema").uint(1);
        cbor.text("collector_id").text(collectorId);
        cbor.text("seq").uint(packetSeq);
        cbor.text("sent_at_ms").uint(sentAtMs);
        cbor.text("display_name").text(displayName);
        cbor.text("device_model").text(deviceModel);
        cbor.text("samples").array(samples.size());
        for (Sample sample : samples) {
            cbor.map(2);
            cbor.text("dt_ms").sint(sample.tMs - sentAtMs);
            cbor.text("bpm").uint(sample.bpm);
        }
        Sample last = samples.peekLast();
        cbor.text("ble").map(1);
        cbor.text("source").text(last == null ? "mi_health_hook" : "mi_health_hook:" + last.source);
        return cbor.bytes();
    }

    private void trim(long now) {
        long cutoff = now - OFFLINE_CACHE_MS;
        while (!samples.isEmpty() && samples.peekFirst().tMs < cutoff) {
            samples.removeFirst();
        }
    }

    private String match(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        return new String(readAllBytes(input), StandardCharsets.UTF_8);
    }

    private byte[] readAllBytes(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        input.close();
        return out.toByteArray();
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String sanitizeDeviceModel(String value) {
        if (value == null) {
            return DEFAULT_DEVICE_MODEL;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_DEVICE_MODEL;
        }
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("com.") || lower.startsWith("lcom/") ||
                lower.contains(".manager.") || lower.contains(".device.") ||
                lower.contains("/") || lower.contains("@")) {
            return DEFAULT_DEVICE_MODEL;
        }
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }

    private static final class Session {
        final String collectorId;
        final String collectorToken;

        Session(String collectorId, String collectorToken) {
            this.collectorId = collectorId;
            this.collectorToken = collectorToken;
        }
    }

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private static final class Sample {
        final long tMs;
        final int bpm;
        final String source;

        Sample(long tMs, int bpm, String source) {
            this.tMs = tMs;
            this.bpm = bpm;
            this.source = source;
        }
    }

    private static final class Cbor {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Cbor uint(long value) {
            typeAndValue(0, value);
            return this;
        }

        Cbor sint(long value) {
            if (value >= 0) {
                typeAndValue(0, value);
            } else {
                typeAndValue(1, -1L - value);
            }
            return this;
        }

        Cbor text(String value) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            typeAndValue(3, bytes.length);
            out.write(bytes, 0, bytes.length);
            return this;
        }

        Cbor array(int size) {
            typeAndValue(4, size);
            return this;
        }

        Cbor map(int size) {
            typeAndValue(5, size);
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        private void typeAndValue(int major, long value) {
            int prefix = major << 5;
            if (value < 24) {
                out.write(prefix | (int) value);
            } else if (value <= 0xffL) {
                out.write(prefix | 24);
                out.write((int) value);
            } else if (value <= 0xffffL) {
                out.write(prefix | 25);
                out.write((int) (value >>> 8));
                out.write((int) value);
            } else if (value <= 0xffff_ffffL) {
                out.write(prefix | 26);
                for (int shift = 24; shift >= 0; shift -= 8) {
                    out.write((int) (value >>> shift));
                }
            } else {
                out.write(prefix | 27);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) (value >>> shift));
                }
            }
        }
    }
}
