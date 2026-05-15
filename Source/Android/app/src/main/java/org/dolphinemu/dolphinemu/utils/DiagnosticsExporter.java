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

import androidx.preference.PreferenceManager;

import org.dolphinemu.dolphinemu.BuildConfig;
import org.dolphinemu.dolphinemu.EmulatorCore;
import org.dolphinemu.dolphinemu.MainlineCore;
import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;
import org.dolphinemu.dolphinemu.gpu.GpuDriverManager;
import org.dolphinemu.dolphinemu.replay.ReplayConfig;
import org.dolphinemu.dolphinemu.settings.DolphinSettings;
import org.dolphinemu.dolphinemu.settings.GameSettingsOverride;
import org.dolphinemu.dolphinemu.training.TrainingModeManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public final class DiagnosticsExporter {
    private static final String PREF_KEY_ISO_URI = "iso_uri";
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss Z";
    private static final String FILE_PATTERN = "yyyyMMdd-HHmmss";

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

        Count replayCount = countFiles(ReplayConfig.replaysDir(context), ".slp");
        appendKV(sb, "Replay files", replayCount.files);
        appendKV(sb, "Replay bytes", formatBytes(replayCount.bytes));
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
        }
        appendKV(sb, "Android gamepad count", count);
    }

    private static void appendNative(StringBuilder sb) {
        appendSection(sb, "Native");
        appendKV(sb, "Native library loaded", NativeLibrary.isNativeLibraryLoaded());
        appendKV(sb, "Native version", safeNativeString(NativeLibrary::GetVersionString));
        appendKV(sb, "Git revision", safeNativeString(NativeLibrary::GetGitRevision));
        appendKV(sb, "Native user directory", safeNativeString(NativeLibrary::GetUserDirectory));
        appendKV(sb, "Native cache directory", safeNativeString(NativeLibrary::GetCacheDirectory));
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
