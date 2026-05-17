package org.dolphinemu.dolphinemu.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.preference.PreferenceManager;

import org.dolphinemu.dolphinemu.BuildConfig;
import org.dolphinemu.dolphinemu.EmulatorCore;
import org.dolphinemu.dolphinemu.MainlineCore;
import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;
import org.dolphinemu.dolphinemu.controller.ButtonMap;
import org.dolphinemu.dolphinemu.controller.ControllerProfile;
import org.dolphinemu.dolphinemu.gpu.GpuDriverManager;
import org.dolphinemu.dolphinemu.replay.ReplayConfig;
import org.dolphinemu.dolphinemu.replay.ReplayStore;
import org.dolphinemu.dolphinemu.settings.DolphinSettings;
import org.dolphinemu.dolphinemu.settings.GameSettingsOverride;
import org.dolphinemu.dolphinemu.training.TrainingModeManager;

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
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiagnosticsExporter {
    private static final String PREF_KEY_ISO_URI = "iso_uri";
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss Z";
    private static final String FILE_PATTERN = "yyyyMMdd-HHmmss";
    private static final Pattern CAPTURE_P1_PATTERN = Pattern.compile(
            "^t=(\\d+).*?payload=([0-9A-F]+).*? p1=\\{.*?buttons=0x([0-9A-F]+).*?"
                    + "main=\\((\\d+),(\\d+)\\),c=\\((\\d+),(\\d+)\\),triggers=\\((\\d+),(\\d+)\\)");

    private DiagnosticsExporter() {
    }

    public static File write(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "diagnostics");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create diagnostics directory");
        }
        String stamp = new SimpleDateFormat(FILE_PATTERN, Locale.US).format(new Date());
        File file = new File(dir, "slippi-android-diagnostics-" + stamp + ".txt");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(buildReport(context));
        }
        return file;
    }

    public static String buildReport(Context context) {
        StringBuilder sb = new StringBuilder(8192);
        appendSection(sb, "Report");
        appendKV(sb, "Generated", formatDate(System.currentTimeMillis()));
        appendKV(sb, "Package", context.getPackageName());
        appendPackageInfo(sb, context);

        appendSection(sb, "Device");
        appendKV(sb, "Manufacturer", Build.MANUFACTURER);
        appendKV(sb, "Brand", Build.BRAND);
        appendKV(sb, "Model", Build.MODEL);
        appendKV(sb, "Device", Build.DEVICE);
        appendKV(sb, "Product", Build.PRODUCT);
        appendKV(sb, "Fingerprint", Build.FINGERPRINT);
        appendKV(sb, "SDK", Build.VERSION.SDK_INT);
        appendKV(sb, "Release", Build.VERSION.RELEASE);
        appendKV(sb, "Supported ABIs", Arrays.toString(Build.SUPPORTED_ABIS));
        appendKV(sb, "Target SDK", context.getApplicationInfo().targetSdkVersion);
        appendKV(sb, "Write settings permission", Settings.System.canWrite(context));

        appendSettings(sb, context);
        appendFiles(sb, context);
        appendControllers(sb, context);
        appendGcAdapterSnapshot(sb);
        appendLaunchSession(sb, context);
        appendAdapterConfiguration(sb, context);
        appendLatestControllerCapture(sb, context);
        appendNativeInputDiagnostics(sb);
        appendRecentControllerLogs(sb);
        appendNative(sb);
        return sb.toString();
    }

    private static void appendPackageInfo(StringBuilder sb, Context context) {
        appendKV(sb, "BuildConfig version", BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")");
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            appendKV(sb, "Installed version", info.versionName
                    + " (" + info.getLongVersionCode() + ")");
            appendKV(sb, "First install", formatDate(info.firstInstallTime));
            appendKV(sb, "Last update", formatDate(info.lastUpdateTime));
        } catch (PackageManager.NameNotFoundException e) {
            appendKV(sb, "Installed version", summarize(e));
        }
    }

    private static void appendSettings(StringBuilder sb, Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        appendSection(sb, "Settings");
        String isoPath = prefs.getString(PREF_KEY_ISO_URI, "");
        File iso = TextUtils.isEmpty(isoPath) ? null : new File(isoPath);
        appendKV(sb, "ISO selected", iso != null && iso.isFile()
                ? iso.getName() + " (" + formatBytes(iso.length()) + ")" : "no");
        appendKV(sb, "ISO path exists", iso != null && iso.exists());
        EmulatorCore core = EmulatorCore.fromPref(
                prefs.getString(EmulatorCore.PREF_KEY, EmulatorCore.DEFAULT.prefValue));
        appendKV(sb, "Emulator core", core.prefValue);
        appendKV(sb, "Mainline packaged", MainlineCore.isPackaged(context));

        String backend = DolphinSettings.getBackend(context);
        DolphinSettings.AudioPreset audio = DolphinSettings.getAudioPreset(context);
        DolphinSettings.DisplayLatencyMode latency = DolphinSettings.getDisplayLatencyMode(context);
        appendKV(sb, "Graphics backend", backend);
        appendKV(sb, "GPU driver", GpuDriverManager.currentLabel(context));
        appendKV(sb, "GPU driver custom loading supported",
                GpuDriverManager.canAttemptCustomDriverLoading());
        appendKV(sb, "GPU driver library",
                GpuDriverManager.selectedLibraryNameForBackend(context, backend));
        appendKV(sb, "Audio backend", audio.backend);
        appendKV(sb, "Audio buffer bursts", audio.bursts);
        appendKV(sb, "Display latency mode", latency.configValue);
        appendKV(sb, "Aspect ratio", DolphinSettings.labelForChoice(
                DolphinSettings.ASPECT_RATIOS, DolphinSettings.aspectRatioForLaunch(context)));
        appendKV(sb, "Internal resolution", DolphinSettings.labelForChoice(
                DolphinSettings.EFB_SCALES, DolphinSettings.getEfbScale(context)));
        appendKV(sb, "Melee widescreen mode",
                GameSettingsOverride.isMeleeWidescreenEnabled(context));
        appendKV(sb, "Dolphin widescreen hack",
                DolphinSettings.isWidescreenHackEnabled(context));
    }

    private static void appendFiles(StringBuilder sb, Context context) {
        appendSection(sb, "Files");
        File userDir = UserDirectoryBootstrap.userDir(context);
        File mainlineDir = MainlineCore.userDir(context);
        appendFile(sb, "Ishiiruka user dir", userDir);
        appendFile(sb, "Mainline user dir", mainlineDir);
        appendFile(sb, "Slippi user.json", UserDirectoryBootstrap.slippiUserJson(context));
        appendFile(sb, "Mainline user.json", new File(mainlineDir, "Slippi/user.json"));
        appendFile(sb, "Mainline library", new File(
                context.getApplicationInfo().nativeLibraryDir, MainlineCore.LIBRARY_FILE_NAME));

        TrainingModeManager.InstalledInfo training = TrainingModeManager.loadInstalled(context);
        appendKV(sb, "Training Mode installed", training != null);
        if (training != null) {
            appendKV(sb, "Training Mode tag", training.tagName);
            appendFile(sb, "Training Mode ISO", new File(training.isoPath));
        }

        ReplayStore replayStore = new ReplayStore(context);
        appendKV(sb, "Replay folder", ReplayConfig.replayFolderLabel(context));
        appendKV(sb, "Replay files", replayStore.count());
        appendKV(sb, "Replay bytes", formatBytes(replayStore.totalSize()));
    }

    private static void appendControllers(StringBuilder sb, Context context) {
        appendSection(sb, "Controllers");
        appendUsbDevices(sb, context);
        appendAndroidInputDevices(sb);
        try {
            RawStickInputProvider rawProvider = RawStickInputProviders.create(context);
            if (rawProvider == null) {
                appendKV(sb, "Raw stick provider", "none");
            } else {
                appendKV(sb, "Raw stick provider",
                        rawProvider.id() + " (" + rawProvider.label() + ")");
                appendKV(sb, "Raw provider blocking wait",
                        rawProvider.supportsBlockingWait());
                appendKV(sb, "Raw provider potentially saturated",
                        rawProvider.isPotentiallySaturated());
            }
        } catch (Throwable t) {
            appendKV(sb, "Raw stick provider", summarize(t));
        }
        for (int port = 0; port < 4; port++) {
            try {
                appendKV(sb, "GC adapter port " + (port + 1),
                        NativeLibrary.IsGCAdapterPortConnected(port));
            } catch (Throwable t) {
                appendKV(sb, "GC adapter port " + (port + 1), summarize(t));
            }
        }
    }

    private static void appendGcAdapterSnapshot(StringBuilder sb) {
        appendSection(sb, "GC Adapter Snapshot");
        try {
            Java_GCAdapter.DiagnosticsSnapshot snapshot =
                    Java_GCAdapter.GetDiagnosticsSnapshot();
            appendKV(sb, "Snapshot", ControllerDiagnosticsCapture.formatSnapshot(snapshot));
            appendKV(sb, "Stick calibration", "GC adapter sticks are pass-through");
        } catch (Throwable t) {
            appendKV(sb, "Snapshot", summarize(t));
        }
    }

    private static void appendLaunchSession(StringBuilder sb, Context context) {
        appendSection(sb, "Launch Session");
        File session = ControllerDiagnosticsCapture.latestSessionFile(context);
        appendFile(sb, "Last launch session", session);
        String sessionText = ControllerDiagnosticsCapture.readSmallFile(session, 16 * 1024L);
        if (TextUtils.isEmpty(sessionText)) {
            appendKV(sb, "Last launch session data", "none");
        } else {
            sb.append(sessionText);
            if (!sessionText.endsWith("\n")) sb.append('\n');
        }

        appendCoreConfig(sb, "Ishiiruka", new File(UserDirectoryBootstrap.userDir(context),
                "Config/Dolphin.ini"));
        appendCoreConfig(sb, "Mainline", new File(MainlineCore.userDir(context),
                "Config/Dolphin.ini"));
        appendGeckoOverrideSummary(sb, "Ishiiruka", UserDirectoryBootstrap.userDir(context));
        appendGeckoOverrideSummary(sb, "Mainline", MainlineCore.userDir(context));
    }

    private static void appendCoreConfig(StringBuilder sb, String label, File dolphinIni) {
        appendKV(sb, label + " Dolphin.ini path", dolphinIni.getAbsolutePath());
        appendKV(sb, label + " SIDevice0",
                readIniValue(dolphinIni, "Core", "SIDevice0", ""));
        appendKV(sb, label + " SIDevice1",
                readIniValue(dolphinIni, "Core", "SIDevice1", ""));
        appendKV(sb, label + " SIDevice2",
                readIniValue(dolphinIni, "Core", "SIDevice2", ""));
        appendKV(sb, label + " SIDevice3",
                readIniValue(dolphinIni, "Core", "SIDevice3", ""));
        appendKV(sb, label + " SlotA",
                readIniValue(dolphinIni, "Core", "SlotA", ""));
        appendKV(sb, label + " SlotB",
                readIniValue(dolphinIni, "Core", "SlotB", "default"));
        appendKV(sb, label + " SerialPort1",
                readIniValue(dolphinIni, "Core", "SerialPort1", ""));
        appendKV(sb, label + " PollingMethod",
                readIniValue(dolphinIni, "Core", "PollingMethod", "default"));
    }

    private static void appendGeckoOverrideSummary(StringBuilder sb, String label, File userDir) {
        File override = new File(userDir, "GameSettings/GALE01r2.ini");
        appendFile(sb, label + " GALE01r2 override", override);
        String mode = "none; bundled defaults apply";
        if (override.isFile()) {
            String sample = ControllerDiagnosticsCapture.readSmallFile(override, 64 * 1024L);
            if (sample.contains("Android Training Mode launch override")) {
                mode = "training override; Slippi Online/Recording defaults disabled";
            } else if (sample.contains("Slippi Playback")) {
                mode = "replay override";
            } else if (sample.contains("# Android user GameSettings override")) {
                mode = "live/user override";
            } else {
                mode = "custom override present";
            }
            appendKV(sb, label + " Slippi Online disabled",
                    sample.contains("$Required: Slippi Online"));
        }
        appendKV(sb, label + " Gecko override mode", mode);
    }

    private static void appendAdapterConfiguration(StringBuilder sb, Context context) {
        appendSection(sb, "Adapter Configuration");
        ControllerProfile profile = new ControllerProfile(context);
        for (int port = 0; port < 4; port++) {
            String key = ControllerProfile.adapterDeviceKey(port);
            appendKV(sb, "Adapter port " + (port + 1) + " stick calibration",
                    "pass-through; saved main="
                            + profile.hasCalibration(key, ControllerProfile.Stick.MAIN)
                            + " saved c="
                            + profile.hasCalibration(key, ControllerProfile.Stick.C));
            if (!profile.hasButtonMap(key)) {
                appendKV(sb, "Adapter port " + (port + 1) + " button map", "identity");
                continue;
            }
            ButtonMap map = profile.getButtonMap(key);
            appendKV(sb, "Adapter port " + (port + 1) + " button map",
                    "custom bindings=" + map.totalBindings()
                            + " serialized=" + map.serialize());
        }
        appendKV(sb, "Adapter note",
                "WUP-028 stick bytes bypass app calibration; only button remap can alter adapter input");
    }

    private static void appendLatestControllerCapture(StringBuilder sb, Context context) {
        appendSection(sb, "Latest Controller Capture");
        File capture = ControllerDiagnosticsCapture.latestCaptureFile(context);
        appendFile(sb, "Capture", capture);
        String text = ControllerDiagnosticsCapture.readSmallFile(capture, 6L * 1024L * 1024L);
        if (TextUtils.isEmpty(text)) {
            appendKV(sb, "Capture data", "none");
            return;
        }
        appendCaptureSummary(sb, text);
        sb.append(text);
        if (!text.endsWith("\n")) sb.append('\n');
    }

    private static void appendCaptureSummary(StringBuilder sb, String text) {
        int samples = 0;
        int genericEvents = 0;
        long lastT = 0L;
        int minMainX = 255, minMainY = 255, minCX = 255, minCY = 255;
        int minTriggerL = 255, minTriggerR = 255;
        int maxMainX = 0, maxMainY = 0, maxCX = 0, maxCY = 0;
        int maxTriggerL = 0, maxTriggerR = 0;
        Set<String> payloads = new HashSet<>();
        Set<String> buttonMasks = new HashSet<>();
        String stopReason = "";

        for (String line : text.split("\\r?\\n")) {
            if (line.startsWith("event t=")) {
                genericEvents++;
                continue;
            }
            if (line.startsWith("stop_reason=")) {
                stopReason = line.substring("stop_reason=".length());
                continue;
            }
            Matcher matcher = CAPTURE_P1_PATTERN.matcher(line);
            if (!matcher.find()) continue;
            samples++;
            lastT = parseLong(matcher.group(1), lastT);
            payloads.add(matcher.group(2));
            buttonMasks.add(matcher.group(3));
            int mainX = parseInt(matcher.group(4));
            int mainY = parseInt(matcher.group(5));
            int cX = parseInt(matcher.group(6));
            int cY = parseInt(matcher.group(7));
            int triggerL = parseInt(matcher.group(8));
            int triggerR = parseInt(matcher.group(9));
            minMainX = Math.min(minMainX, mainX);
            minMainY = Math.min(minMainY, mainY);
            minCX = Math.min(minCX, cX);
            minCY = Math.min(minCY, cY);
            minTriggerL = Math.min(minTriggerL, triggerL);
            minTriggerR = Math.min(minTriggerR, triggerR);
            maxMainX = Math.max(maxMainX, mainX);
            maxMainY = Math.max(maxMainY, mainY);
            maxCX = Math.max(maxCX, cX);
            maxCY = Math.max(maxCY, cY);
            maxTriggerL = Math.max(maxTriggerL, triggerL);
            maxTriggerR = Math.max(maxTriggerR, triggerR);
        }

        appendKV(sb, "Capture summary samples", samples);
        appendKV(sb, "Capture summary duration", lastT + " ms");
        appendKV(sb, "Capture summary generic events", genericEvents);
        appendKV(sb, "Capture summary unique WUP payloads", payloads.size());
        appendKV(sb, "Capture summary P1 button masks", buttonMasks.toString());
        if (samples > 0) {
            appendKV(sb, "Capture summary P1 main range",
                    "(" + minMainX + ".." + maxMainX + ", "
                            + minMainY + ".." + maxMainY + ")");
            appendKV(sb, "Capture summary P1 c range",
                    "(" + minCX + ".." + maxCX + ", " + minCY + ".." + maxCY + ")");
            appendKV(sb, "Capture summary P1 trigger range",
                    "(" + minTriggerL + ".." + maxTriggerL + ", "
                            + minTriggerR + ".." + maxTriggerR + ")");
        }
        appendKV(sb, "Capture summary stop reason", stopReason);
    }

    private static void appendRecentControllerLogs(StringBuilder sb) {
        appendSection(sb, "Recent Controller Logs");
        String logs = readFilteredLogcat();
        if (TextUtils.isEmpty(logs)) {
            appendKV(sb, "Logs", "none or unavailable");
            return;
        }
        sb.append(logs);
        if (!logs.endsWith("\n")) sb.append('\n');
    }

    private static void appendNativeInputDiagnostics(StringBuilder sb) {
        appendSection(sb, "Native Input Diagnostics");
        String logs = safeNativeString(NativeLibrary::GetInputDiagnosticsLog);
        if (TextUtils.isEmpty(logs)) {
            appendKV(sb, "Native input log", "none");
            return;
        }
        sb.append(logs);
        if (!logs.endsWith("\n")) sb.append('\n');
    }

    private static void appendUsbDevices(StringBuilder sb, Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            appendKV(sb, "USB devices", "unavailable");
            return;
        }
        int count = 0;
        boolean hasGcAdapter = false;
        for (UsbDevice device : manager.getDeviceList().values()) {
            count++;
            boolean gcAdapter = device.getVendorId() == 0x057E
                    && device.getProductId() == 0x0337;
            hasGcAdapter |= gcAdapter;
            appendKV(sb, "USB device " + count, String.format(Locale.US,
                    "vid=0x%04X pid=0x%04X class=%d name=%s product=%s%s",
                    device.getVendorId(), device.getProductId(),
                    device.getDeviceClass(), safe(device.getDeviceName()),
                    safe(device.getProductName()), gcAdapter ? " GC adapter" : ""));
        }
        appendKV(sb, "USB device count", count);
        appendKV(sb, "WUP-028 adapter attached", hasGcAdapter);
    }

    private static void appendAndroidInputDevices(StringBuilder sb) {
        int count = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK)
                    == InputDevice.SOURCE_JOYSTICK;
            boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD)
                    == InputDevice.SOURCE_GAMEPAD;
            if (!joystick && !gamepad) continue;
            count++;
            appendKV(sb, "Android input " + count, String.format(Locale.US,
                    "id=%d name=%s descriptor=%s sources=0x%08X vendor=%d product=%d",
                    id, safe(device.getName()), safe(device.getDescriptor()),
                    sources, device.getVendorId(), device.getProductId()));
            appendKV(sb, "Android input " + count + " axes",
                    describeGamepadAxes(device, sources));
        }
        appendKV(sb, "Android gamepad count", count);
    }

    private static String describeGamepadAxes(InputDevice device, int sources) {
        StringBuilder sb = new StringBuilder();
        int source = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                ? InputDevice.SOURCE_JOYSTICK : InputDevice.SOURCE_GAMEPAD;
        int[] axes = {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
                MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        };
        for (int axis : axes) {
            InputDevice.MotionRange range = device.getMotionRange(axis, source);
            if (range == null) {
                range = device.getMotionRange(axis);
            }
            if (range == null) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(MotionEvent.axisToString(axis))
                    .append("=[min=").append(formatFloat(range.getMin()))
                    .append(",max=").append(formatFloat(range.getMax()))
                    .append(",flat=").append(formatFloat(range.getFlat()))
                    .append(",fuzz=").append(formatFloat(range.getFuzz()))
                    .append("]");
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static void appendNative(StringBuilder sb) {
        appendSection(sb, "Native");
        appendKV(sb, "Native library loaded", NativeLibrary.isNativeLibraryLoaded());
        appendKV(sb, "Native version", safeNativeString(NativeLibrary::GetVersionString));
        appendKV(sb, "Git revision", safeNativeString(NativeLibrary::GetGitRevision));
        appendKV(sb, "Native user directory", safeNativeString(NativeLibrary::GetUserDirectory));
        appendKV(sb, "Native cache directory", safeNativeString(NativeLibrary::GetCacheDirectory));
    }

    private static String readIniValue(File file, String section, String key, String defaultValue) {
        if (file == null || !file.isFile()) return defaultValue;
        String currentSection = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSection = trimmed.substring(1, trimmed.length() - 1);
                    continue;
                }
                if (!section.equals(currentSection)) continue;
                int equals = trimmed.indexOf('=');
                if (equals <= 0) continue;
                String foundKey = trimmed.substring(0, equals).trim();
                if (key.equals(foundKey)) {
                    return trimmed.substring(equals + 1).trim();
                }
            }
        } catch (IOException e) {
            return summarize(e);
        }
        return defaultValue;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String readFilteredLogcat() {
        StringBuilder out = new StringBuilder(128 * 1024);
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-t", "400", "-v", "time"
            });
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lines = 0;
                int bytes = 0;
                while ((line = reader.readLine()) != null) {
                    if (!isControllerLogLine(line)) continue;
                    int lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (lines >= 250 || bytes + lineBytes > 192 * 1024) {
                        out.append("[truncated]\n");
                        break;
                    }
                    out.append(line).append('\n');
                    lines++;
                    bytes += lineBytes;
                }
            }
            process.waitFor(500, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return summarize(t);
        } finally {
            if (process != null) process.destroy();
        }
        return out.toString();
    }

    private static boolean isControllerLogLine(String line) {
        return line.contains("SlippiGCAdapter")
                || line.contains("SlippiInputDiag")
                || line.contains("SlippiPadStatus")
                || line.contains("SlippiPadBuffer")
                || line.contains("SlippiEmu")
                || line.contains("MainlineEmu")
                || line.contains("SlippiRawInput")
                || line.contains("SlippiPadOverride")
                || line.contains("SERIALINTERFACE")
                || line.contains("DolphinJNI");
    }

    private static void appendFile(StringBuilder sb, String label, File file) {
        appendKV(sb, label + " path", file == null ? "" : file.getAbsolutePath());
        appendKV(sb, label + " exists", file != null && file.exists());
        appendKV(sb, label + " size", file != null && file.isFile()
                ? formatBytes(file.length()) : "");
        appendKV(sb, label + " modified", file != null && file.exists()
                ? formatDate(file.lastModified()) : "");
    }

    private static Count countFiles(File root, String suffix) {
        Count count = new Count();
        countFiles(root, suffix, count);
        return count;
    }

    private static void countFiles(File file, String suffix, Count count) {
        if (file == null || !file.exists()) return;
        if (file.isFile()) {
            if (suffix == null || file.getName().endsWith(suffix)) {
                count.files++;
                count.bytes += file.length();
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            countFiles(child, suffix, count);
        }
    }

    private static void appendSection(StringBuilder sb, String title) {
        if (sb.length() > 0) sb.append('\n');
        sb.append("== ").append(title).append(" ==\n");
    }

    private static void appendKV(StringBuilder sb, String key, Object value) {
        sb.append(key).append(": ").append(value == null ? "" : value).append('\n');
    }

    private static String formatDate(long millis) {
        if (millis <= 0L) return "";
        return new SimpleDateFormat(DATE_PATTERN, Locale.US).format(new Date(millis));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024.0) return String.format(Locale.US, "%.1f KiB", kib);
        double mib = kib / 1024.0;
        if (mib < 1024.0) return String.format(Locale.US, "%.1f MiB", mib);
        return String.format(Locale.US, "%.2f GiB", mib / 1024.0);
    }

    private static String formatFloat(float value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private static String safeNativeString(NativeStringCall call) {
        try {
            return safe(call.get());
        } catch (Throwable t) {
            return summarize(t);
        }
    }

    private static String summarize(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName()
                + (TextUtils.isEmpty(message) ? "" : ": " + message);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private interface NativeStringCall {
        String get();
    }

    private static final class Count {
        int files;
        long bytes;
    }
}
