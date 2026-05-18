package org.dolphinemu.dolphinemu.utils;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.controller.ButtonMap;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ControllerDiagnosticsCapture {
    private static final String TAG = "ControllerDiagnostics";
    private static final String ARM_FILE = "controller-capture-next-launch.flag";
    private static final String CAPTURE_FILE = "controller-capture-latest.txt";
    private static final String SESSION_FILE = "controller-session-latest.txt";
    private static final long CAPTURE_DURATION_MS = 90_000L;
    private static final long SAMPLE_INTERVAL_MS = 16L;
    private static final long GENERIC_EVENT_MIN_INTERVAL_MS = 16L;
    private static final long MAX_CAPTURE_BYTES = 5L * 1024L * 1024L;
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss Z";

    private static final Object LOCK = new Object();
    private static ActiveCapture sActiveCapture;

    private ControllerDiagnosticsCapture() {
    }

    public static void armNextLaunch(Context context) throws IOException {
        File dir = diagnosticsDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create diagnostics directory");
        }
        try (BufferedWriter writer = writer(armFile(context))) {
            writer.write("armed_at=" + formatDate(System.currentTimeMillis()));
            writer.newLine();
            writer.write("duration_ms=" + CAPTURE_DURATION_MS);
            writer.newLine();
            writer.write("max_bytes=" + MAX_CAPTURE_BYTES);
            writer.newLine();
        }
    }

    public static boolean isArmed(Context context) {
        return armFile(context).isFile();
    }

    public static String captureStatus(Context context) {
        if (isArmed(context)) {
            return "Armed for the next game launch";
        }
        File capture = latestCaptureFile(context);
        if (capture.isFile()) {
            return "Latest capture will be included in the next export";
        }
        return "Record a bounded controller capture on next launch";
    }

    public static File latestCaptureFile(Context context) {
        return new File(diagnosticsDir(context), CAPTURE_FILE);
    }

    public static File latestSessionFile(Context context) {
        return new File(diagnosticsDir(context), SESSION_FILE);
    }

    public static void recordLaunch(Context context, String core, String launchMode,
                                    boolean useGcAdapter) {
        recordLaunch(context, core, launchMode, useGcAdapter, null);
    }

    public static void recordLaunch(Context context, String core, String launchMode,
                                    boolean useGcAdapter, String extraLine) {
        try {
            File dir = diagnosticsDir(context);
            if (!dir.exists() && !dir.mkdirs()) return;
            try (BufferedWriter writer = writer(latestSessionFile(context))) {
                writer.write("recorded_at=" + formatDate(System.currentTimeMillis()));
                writer.newLine();
                writer.write("process=" + context.getPackageName());
                writer.newLine();
                writer.write("core=" + safe(core));
                writer.newLine();
                writer.write("launch_mode=" + safe(launchMode));
                writer.newLine();
                writer.write("use_gc_adapter=" + useGcAdapter);
                writer.newLine();
                if (extraLine != null && !extraLine.isEmpty()) {
                    writer.write(extraLine);
                    writer.newLine();
                }
                writer.write("capture_armed=" + isArmed(context));
                writer.newLine();
            }
        } catch (IOException e) {
            Log.w(TAG, "recordLaunch failed", e);
        }
    }

    public static void startIfArmed(Context context, String core, String launchMode,
                                    boolean useGcAdapter) {
        File arm = armFile(context);
        if (!arm.isFile()) return;
        synchronized (LOCK) {
            if (sActiveCapture != null && sActiveCapture.running.get()) return;
            if (!arm.delete()) {
                Log.w(TAG, "could not delete capture arm marker");
            }
            ActiveCapture capture = new ActiveCapture(
                    context.getApplicationContext(), core, launchMode, useGcAdapter);
            sActiveCapture = capture;
            capture.thread.start();
        }
    }

    public static void stopActiveCapture(String reason) {
        ActiveCapture capture;
        synchronized (LOCK) {
            capture = sActiveCapture;
        }
        if (capture != null) {
            capture.stop(reason);
        }
    }

    public static void recordInputEvent(String source, String details) {
        ActiveCapture capture;
        synchronized (LOCK) {
            capture = sActiveCapture;
        }
        if (capture != null) {
            capture.appendInputEvent(source, details);
        }
    }

    public static String readSmallFile(File file, long maxBytes) {
        if (file == null || !file.isFile()) return "";
        StringBuilder sb = new StringBuilder();
        long remaining = Math.max(0L, maxBytes);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while (remaining > 0L && (line = reader.readLine()) != null) {
                byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                if (bytes.length + 1L > remaining) {
                    sb.append("[truncated]\n");
                    break;
                }
                sb.append(line).append('\n');
                remaining -= bytes.length + 1L;
            }
        } catch (IOException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return sb.toString();
    }

    public static String formatSnapshot(Java_GCAdapter.DiagnosticsSnapshot snapshot) {
        return formatSample(0L, snapshot);
    }

    private static File diagnosticsDir(Context context) {
        return new File(context.getFilesDir(), "diagnostics");
    }

    private static File armFile(Context context) {
        return new File(diagnosticsDir(context), ARM_FILE);
    }

    private static BufferedWriter writer(File file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8));
    }

    private static String formatDate(long millis) {
        return new SimpleDateFormat(DATE_PATTERN, Locale.US).format(new Date(millis));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void setNativeInputDiagnosticsEnabled(boolean enabled) {
        try {
            NativeLibrary.SetInputDiagnosticsEnabled(enabled, false);
        } catch (Throwable t) {
            Log.w(TAG, "native input diagnostics toggle failed", t);
        }
    }

    private static final class ActiveCapture implements Runnable {
        final Context context;
        final String core;
        final String launchMode;
        final boolean useGcAdapter;
        final AtomicBoolean running = new AtomicBoolean(true);
        final Thread thread;
        volatile String stopReason = "completed";
        long startElapsed;
        long bytesWritten;
        long samples;
        long genericEvents;
        long lastGenericEventElapsed = -GENERIC_EVENT_MIN_INTERVAL_MS;
        BufferedWriter captureWriter;

        ActiveCapture(Context context, String core, String launchMode, boolean useGcAdapter) {
            this.context = context;
            this.core = core;
            this.launchMode = launchMode;
            this.useGcAdapter = useGcAdapter;
            this.thread = new Thread(this, "ControllerDiagnosticsCapture");
        }

        @Override
        public void run() {
            startElapsed = SystemClock.elapsedRealtime();
            long deadline = startElapsed + CAPTURE_DURATION_MS;
            File out = latestCaptureFile(context);
            setNativeInputDiagnosticsEnabled(true);
            try {
                File dir = diagnosticsDir(context);
                if (!dir.exists() && !dir.mkdirs()) return;
                try (BufferedWriter writer = writer(out)) {
                    synchronized (this) {
                        captureWriter = writer;
                        bytesWritten += writeLine(writer, "== Controller Capture ==");
                        bytesWritten += writeLine(writer,
                                "started_at=" + formatDate(System.currentTimeMillis()));
                        bytesWritten += writeLine(writer, "core=" + safe(core));
                        bytesWritten += writeLine(writer, "launch_mode=" + safe(launchMode));
                        bytesWritten += writeLine(writer, "use_gc_adapter=" + useGcAdapter);
                        bytesWritten += writeLine(writer,
                                "duration_limit_ms=" + CAPTURE_DURATION_MS);
                        bytesWritten += writeLine(writer,
                                "sample_interval_ms=" + SAMPLE_INTERVAL_MS);
                        bytesWritten += writeLine(writer,
                                "generic_event_min_interval_ms=" + GENERIC_EVENT_MIN_INTERVAL_MS);
                        bytesWritten += writeLine(writer, "byte_limit=" + MAX_CAPTURE_BYTES);
                        bytesWritten += writeLine(writer,
                            "script=neutral, main directions/diagonals, c-stick directions, X/Y, shield, modifiers");
                        bytesWritten += writeLine(writer, "");
                    }

                    while (running.get() && SystemClock.elapsedRealtime() < deadline
                            && bytesWritten < MAX_CAPTURE_BYTES) {
                        Java_GCAdapter.DiagnosticsSnapshot snapshot =
                                Java_GCAdapter.GetDiagnosticsSnapshot();
                        long elapsed = SystemClock.elapsedRealtime() - startElapsed;
                        String line = formatSample(elapsed, snapshot);
                        synchronized (this) {
                            bytesWritten += writeLine(writer, line);
                            samples++;
                            if (((samples + genericEvents) & 31L) == 0L) writer.flush();
                        }
                        long next = startElapsed + samples * SAMPLE_INTERVAL_MS;
                        long sleep = next - SystemClock.elapsedRealtime();
                        if (sleep > 0L) {
                            SystemClock.sleep(Math.min(sleep, SAMPLE_INTERVAL_MS));
                        }
                    }

                    if (bytesWritten >= MAX_CAPTURE_BYTES) {
                        stopReason = "byte cap reached";
                    } else if (SystemClock.elapsedRealtime() >= deadline) {
                        stopReason = "duration completed";
                    }
                    synchronized (this) {
                        bytesWritten += writeLine(writer, "");
                        bytesWritten += writeLine(writer, "finished_at="
                                + formatDate(System.currentTimeMillis()));
                        bytesWritten += writeLine(writer, "stop_reason=" + stopReason);
                        bytesWritten += writeLine(writer, "samples=" + samples);
                        bytesWritten += writeLine(writer, "generic_events=" + genericEvents);
                        appendNativeInputLog(writer);
                        bytesWritten += writeLine(writer, "bytes_approx=" + bytesWritten);
                        captureWriter = null;
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "capture failed", e);
            } finally {
                setNativeInputDiagnosticsEnabled(false);
                running.set(false);
                synchronized (this) {
                    captureWriter = null;
                }
                synchronized (LOCK) {
                    if (sActiveCapture == this) sActiveCapture = null;
                }
            }
        }

        void stop(String reason) {
            stopReason = TextUtils.isEmpty(reason) ? "stopped" : reason;
            running.set(false);
            if (Thread.currentThread() != thread) {
                try {
                    thread.join(300L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        synchronized void appendInputEvent(String source, String details) {
            if (!running.get() || captureWriter == null || bytesWritten >= MAX_CAPTURE_BYTES) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - startElapsed;
            if (!isHighPriorityEvent(source)
                    && elapsed - lastGenericEventElapsed < GENERIC_EVENT_MIN_INTERVAL_MS) {
                return;
            }
            lastGenericEventElapsed = elapsed;
            String line = "event t=" + elapsed
                    + " source=" + safe(source)
                    + " " + safe(details);
            try {
                bytesWritten += writeLine(captureWriter, line);
                genericEvents++;
            } catch (IOException e) {
                Log.w(TAG, "event capture failed", e);
                running.set(false);
            }
        }

        private void appendNativeInputLog(BufferedWriter writer) throws IOException {
            try {
                String nativeLog = NativeLibrary.GetInputDiagnosticsLog();
                if (TextUtils.isEmpty(nativeLog)) {
                    bytesWritten += writeLine(writer, "native_input_log=none");
                    return;
                }
                bytesWritten += writeLine(writer, "");
                bytesWritten += writeLine(writer, "== Native Input Diagnostics ==");
                for (String line : nativeLog.split("\\r?\\n")) {
                    if (TextUtils.isEmpty(line)) continue;
                    bytesWritten += writeLine(writer, line);
                    if (bytesWritten >= MAX_CAPTURE_BYTES) break;
                }
            } catch (Throwable t) {
                bytesWritten += writeLine(writer, "native_input_log="
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        private static boolean isHighPriorityEvent(String source) {
            if (source == null) return false;
            return source.contains("key")
                    || source.contains("button")
                    || source.contains("dpad")
                    || source.contains("trigger");
        }
    }

    private static long writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
        return line.getBytes(StandardCharsets.UTF_8).length + 1L;
    }

    private static String formatSample(long elapsedMs, Java_GCAdapter.DiagnosticsSnapshot snapshot) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("t=").append(elapsedMs)
                .append(" open=").append(snapshot.adapterOpen)
                .append(" size=").append(snapshot.inputSize)
                .append(" age_ms=").append(snapshot.ageMs)
                .append(" input_count=").append(snapshot.inputCount)
                .append(" null_reads=").append(snapshot.nullReads)
                .append(" short_reads=").append(snapshot.shortReads)
                .append(" queue_failures=").append(snapshot.queueFailures)
                .append(" drained_completions=").append(snapshot.drainedCompletions)
                .append(" max_drain_burst=").append(snapshot.maxDrainBurst)
                .append(" payload=").append(hex(snapshot.payload));
        for (int port = 0; port < 4; port++) {
            appendPort(sb, snapshot.payload, port);
        }
        return sb.toString();
    }

    private static void appendPort(StringBuilder sb, byte[] payload, int port) {
        int base = 1 + 9 * port;
        if (payload == null || payload.length < base + 9) {
            sb.append(" p").append(port + 1).append("={unavailable}");
            return;
        }
        int typeByte = u(payload[base]);
        int type = typeByte >> 4;
        int b1 = u(payload[base + 1]);
        int b2 = u(payload[base + 2]);
        int buttons = decodeButtons(b1, b2);
        sb.append(" p").append(port + 1).append("={")
                .append("connected=").append(type != 0)
                .append(",type=").append(type)
                .append(",type_byte=0x").append(hexByte(typeByte))
                .append(",raw=0x").append(hexByte(b1)).append(hexByte(b2))
                .append(",buttons=0x").append(hexWord(buttons))
                .append(",names=").append(buttonNames(buttons))
                .append(",main=(").append(u(payload[base + 3]))
                .append(',').append(u(payload[base + 4])).append(')')
                .append(",c=(").append(u(payload[base + 5]))
                .append(',').append(u(payload[base + 6])).append(')')
                .append(",triggers=(").append(u(payload[base + 7]))
                .append(',').append(u(payload[base + 8])).append(")}");
    }

    private static int decodeButtons(int b1, int b2) {
        int buttons = 0;
        if ((b1 & (1 << 0)) != 0) buttons |= ButtonMap.GC_BTN_A;
        if ((b1 & (1 << 1)) != 0) buttons |= ButtonMap.GC_BTN_B;
        if ((b1 & (1 << 2)) != 0) buttons |= ButtonMap.GC_BTN_X;
        if ((b1 & (1 << 3)) != 0) buttons |= ButtonMap.GC_BTN_Y;
        if ((b1 & (1 << 4)) != 0) buttons |= ButtonMap.GC_BTN_LEFT;
        if ((b1 & (1 << 5)) != 0) buttons |= ButtonMap.GC_BTN_RIGHT;
        if ((b1 & (1 << 6)) != 0) buttons |= ButtonMap.GC_BTN_DOWN;
        if ((b1 & (1 << 7)) != 0) buttons |= ButtonMap.GC_BTN_UP;
        if ((b2 & (1 << 0)) != 0) buttons |= ButtonMap.GC_BTN_START;
        if ((b2 & (1 << 1)) != 0) buttons |= ButtonMap.GC_TRIG_Z;
        if ((b2 & (1 << 2)) != 0) buttons |= ButtonMap.GC_TRIG_R;
        if ((b2 & (1 << 3)) != 0) buttons |= ButtonMap.GC_TRIG_L;
        return buttons;
    }

    private static String buttonNames(int buttons) {
        if (buttons == 0) return "none";
        StringBuilder sb = new StringBuilder();
        for (int bit : ButtonMap.GC_BUTTONS_DISPLAY_ORDER) {
            if ((buttons & bit) == 0) continue;
            if (sb.length() > 0) sb.append('+');
            sb.append(ButtonMap.labelForGcBit(bit));
        }
        return sb.toString();
    }

    private static int u(byte value) {
        return value & 0xFF;
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(hexByte(u(b)));
        return sb.toString();
    }

    private static String hexByte(int value) {
        return String.format(Locale.US, "%02X", value & 0xFF);
    }

    private static String hexWord(int value) {
        return String.format(Locale.US, "%04X", value & 0xFFFF);
    }
}
