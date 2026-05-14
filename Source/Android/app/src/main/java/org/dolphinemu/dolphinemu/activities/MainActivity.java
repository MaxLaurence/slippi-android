package org.dolphinemu.dolphinemu.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButtonToggleGroup;

import org.dolphinemu.dolphinemu.EmulatorCore;
import org.dolphinemu.dolphinemu.MainlineCore;
import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;
import org.dolphinemu.dolphinemu.gpu.GpuDriverManager;
import org.dolphinemu.dolphinemu.settings.DolphinSettings;
// SlippiAuthClient / SlippiSession (Firebase-based) intentionally removed
// — the Slippi team prefers users go through their slippi.gg login flow
// in a WebView (SlippiLoginActivity), which we trigger from the auth card
// below.

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import org.dolphinemu.dolphinemu.controller.ControllerProfile;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One-screen launcher: pick an ISO, sign into Slippi (or drop in a
 * user.json), choose an emulator core and graphics backend, hit Play.
 * All heavy lifting is in the native core; this Activity just sets the dial.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREF_KEY_ISO_URI = "iso_uri";
    private static final String PREF_KEY_EMULATOR_CORE = EmulatorCore.PREF_KEY;
    private static final String PREF_KEY_BACKEND = "backend";
    private static final String PREF_KEY_AUDIO_BACKEND = "audio_backend";
    private static final String PREF_KEY_AUDIO_BUFFER_BURSTS = "audio_buffer_bursts";
    private static final String PREF_KEY_DISPLAY_LATENCY_MODE = "display_latency_mode";
    private static final String BACKEND_VULKAN = "Vulkan";
    private static final String BACKEND_OGL = "OGL";
    private static final String DISPLAY_LATENCY_SMOOTH = "smooth";
    private static final String DISPLAY_LATENCY_FASTEST = "fastest";
    private static final String AUDIO_BACKEND_OBOE = "Oboe";
    private static final String AUDIO_BACKEND_AAUDIO = "AAudio";
    private static final String AUDIO_BACKEND_OPENSLES = "OpenSLES";
    private static final int AUDIO_BURSTS_LOW = 2;
    private static final int AUDIO_BURSTS_ULTRA_LOW = 1;
    private static final int AUDIO_BURSTS_BALANCED = 4;
    private static final int AUDIO_BURSTS_STABLE = 8;
    private static final AudioPreset[] AUDIO_PRESETS = {
            new AudioPreset(AUDIO_BACKEND_OBOE, AUDIO_BURSTS_ULTRA_LOW,
                    R.string.audio_preset_oboe_ultra_low),
            new AudioPreset(AUDIO_BACKEND_OBOE, AUDIO_BURSTS_LOW,
                    R.string.audio_preset_oboe_low),
            new AudioPreset(AUDIO_BACKEND_OBOE, AUDIO_BURSTS_BALANCED,
                    R.string.audio_preset_oboe_balanced),
            new AudioPreset(AUDIO_BACKEND_OBOE, AUDIO_BURSTS_STABLE,
                    R.string.audio_preset_oboe_stable),
            new AudioPreset(AUDIO_BACKEND_OPENSLES, AUDIO_BURSTS_BALANCED,
                    R.string.audio_preset_opensles_balanced),
            new AudioPreset(AUDIO_BACKEND_OPENSLES, AUDIO_BURSTS_STABLE,
                    R.string.audio_preset_opensles_stable),
            new AudioPreset(AUDIO_BACKEND_AAUDIO, AUDIO_BURSTS_BALANCED,
                    R.string.audio_preset_aaudio_balanced),
            new AudioPreset(AUDIO_BACKEND_AAUDIO, AUDIO_BURSTS_ULTRA_LOW,
                    R.string.audio_preset_aaudio_ultra_low),
    };
    private static final DisplayLatencyMode[] DISPLAY_LATENCY_MODES = {
            new DisplayLatencyMode(DISPLAY_LATENCY_SMOOTH, R.string.display_latency_smooth),
            new DisplayLatencyMode(DISPLAY_LATENCY_FASTEST, R.string.display_latency_fastest),
    };

    private TextView isoStatus;
    private TextView adapterStatus;
    private TextView audioStatus;
    private TextView emulatorCoreStatus;
    private MaterialButtonToggleGroup emulatorCoreToggle;
    private MaterialButtonToggleGroup backendToggle;

    // Auth UI: three sibling containers, exactly one visible at a time.
    private LinearLayout authLoading;
    private LinearLayout authForm;
    private LinearLayout authSignedIn;
    private TextView authLoadingLabel;
    private EditText authEmail;
    private EditText authPassword;
    private Button authSignIn;
    private TextView authError;
    private TextView authImportLink;
    private TextView authDisplayName;
    private TextView authConnectCode;
    private Button authSignOut;

    // Triggers SlippiLoginActivity for an embedded slippi.gg sign-in.
    // RESULT_OK means a fresh user.json was written; refresh the UI.
    private final ActivityResultLauncher<Intent> signInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK) renderAuthFromUserJson();
                else renderAuthLoggedOut(null);
            });

    private final ActivityResultLauncher<String[]> pickIso =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {}
                File copied = copyContentToCache(uri, "rom.iso");
                if (copied != null) {
                    prefs().edit()
                            .putString(PREF_KEY_ISO_URI, copied.getAbsolutePath())
                            .apply();
                    refresh();
                } else {
                    toast("Failed to import ISO");
                }
            });

    private final ActivityResultLauncher<String[]> pickUserJson =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                File dst = UserDirectoryBootstrap.slippiUserJson(this);
                File parent = dst.getParentFile();
                if (parent != null) parent.mkdirs();
                if (copyContentToFile(uri, dst)) {
                    toast("Imported user.json");
                    renderAuthFromUserJson();
                    refresh();
                } else {
                    toast("Failed to import user.json");
                }
            });

    private final ActivityResultLauncher<String[]> pickGpuDriver =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                installImportedGpuDriver(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        isoStatus = findViewById(R.id.iso_status);
        adapterStatus = findViewById(R.id.adapter_status);
        audioStatus = findViewById(R.id.audio_status);
        emulatorCoreStatus = findViewById(R.id.emulator_core_status);
        emulatorCoreToggle = findViewById(R.id.emulator_core_toggle);
        backendToggle = findViewById(R.id.backend_toggle);

        authLoading = findViewById(R.id.auth_loading);
        authForm = findViewById(R.id.auth_form);
        authSignedIn = findViewById(R.id.auth_signed_in);
        authLoadingLabel = findViewById(R.id.auth_loading_label);
        authEmail = findViewById(R.id.auth_email);
        authPassword = findViewById(R.id.auth_password);
        authSignIn = findViewById(R.id.auth_sign_in);
        authError = findViewById(R.id.auth_error);
        authImportLink = findViewById(R.id.auth_import_link);
        authDisplayName = findViewById(R.id.auth_display_name);
        authConnectCode = findViewById(R.id.auth_connect_code);
        authSignOut = findViewById(R.id.auth_sign_out);

        UserDirectoryBootstrap.ensureLayout(this);
        NativeLibrary.SetUserDirectory(UserDirectoryBootstrap.userDir(this).getAbsolutePath());
        NativeLibrary.CreateUserFolders();

        findViewById(R.id.pick_iso).setOnClickListener(v -> pickIso.launch(new String[]{"*/*"}));
        authSignIn.setOnClickListener(v ->
                signInLauncher.launch(new Intent(this, SlippiLoginActivity.class)));
        authSignOut.setOnClickListener(v -> {
            File f = UserDirectoryBootstrap.slippiUserJson(this);
            if (f.exists()) f.delete();
            // Also nuke the WebView's slippi.gg session cookies and
            // localStorage so the next "Sign in" lands on the login
            // page instead of skipping straight through with the old
            // session.
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            android.webkit.WebStorage.getInstance().deleteAllData();
            renderAuthLoggedOut(null);
        });
        authImportLink.setOnClickListener(v ->
                pickUserJson.launch(new String[]{"application/json", "*/*"}));
        findViewById(R.id.launcher_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.play).setOnClickListener(v -> launchEmulation(null));
        findViewById(R.id.training_mode_button).setOnClickListener(v ->
                startActivity(new Intent(this, TrainingModeActivity.class)));
        findViewById(R.id.replays_button).setOnClickListener(v ->
                startActivity(new Intent(this, ReplayListActivity.class)));

        EmulatorCore savedCore = EmulatorCore.fromPref(
                prefs().getString(PREF_KEY_EMULATOR_CORE, EmulatorCore.DEFAULT.prefValue));
        emulatorCoreToggle.check(savedCore == EmulatorCore.MAINLINE
                ? R.id.core_mainline : R.id.core_ishiiruka);
        emulatorCoreToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            EmulatorCore value = checkedId == R.id.core_mainline
                    ? EmulatorCore.MAINLINE : EmulatorCore.ISHIIRUKA;
            prefs().edit().putString(PREF_KEY_EMULATOR_CORE, value.prefValue).apply();
            refreshCoreStatus();
        });

        // Restore the saved backend selection (defaults to Vulkan —
        // lower CPU overhead, better frame pacing on Adreno; OGL is
        // still available as a toggle).
        // The MaterialButtonToggleGroup callback fires on initial
        // check too, which is fine since we're idempotent.
        String saved = prefs().getString(PREF_KEY_BACKEND, BACKEND_VULKAN);
        backendToggle.check(BACKEND_VULKAN.equals(saved)
                ? R.id.backend_vulkan : R.id.backend_ogl);
        backendToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            String value = checkedId == R.id.backend_vulkan ? BACKEND_VULKAN : BACKEND_OGL;
            prefs().edit().putString(PREF_KEY_BACKEND, value).apply();
            applyGpuDriverConfig(value);
            refresh();
        });

        // Initial auth state: signed in iff there's a user.json on disk.
        if (UserDirectoryBootstrap.slippiUserJson(this).exists()) {
            renderAuthFromUserJson();
        } else {
            renderAuthLoggedOut(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /**
     * Read the saved user.json, pull `displayName` and `connectCode`,
     * and render the signed-in card. If parsing fails we still show
     * the file as "signed in" but with placeholder values — the file
     * itself is what netplay reads, so we don't want to gate the UI
     * on JSON correctness.
     */
    private void renderAuthFromUserJson() {
        File f = UserDirectoryBootstrap.slippiUserJson(this);
        if (!f.exists()) {
            renderAuthLoggedOut(null);
            return;
        }
        String name = "Slippi player";
        String code = "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = in.read(buf);
            if (n > 0) {
                JSONObject o = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (o.has("displayName")) name = o.optString("displayName", name);
                if (o.has("connectCode")) code = o.optString("connectCode", code);
            }
        } catch (IOException | JSONException ignored) {}
        renderAuthSignedIn(name, code);
    }

    // --- auth UI state machine -------------------------------------------

    private void renderAuthLoading(String label) {
        authLoadingLabel.setText(label);
        authLoading.setVisibility(View.VISIBLE);
        authForm.setVisibility(View.GONE);
        authSignedIn.setVisibility(View.GONE);
    }

    private void renderAuthLoggedOut(String errorMessage) {
        authLoading.setVisibility(View.GONE);
        authForm.setVisibility(View.VISIBLE);
        authSignedIn.setVisibility(View.GONE);
        if (errorMessage != null && !errorMessage.equals("no saved session")) {
            renderAuthError(errorMessage);
        } else {
            authError.setVisibility(View.GONE);
        }
    }

    private void renderAuthError(String msg) {
        authError.setText(msg);
        authError.setVisibility(View.VISIBLE);
        authLoading.setVisibility(View.GONE);
        authForm.setVisibility(View.VISIBLE);
        authSignedIn.setVisibility(View.GONE);
    }

    private void renderAuthSignedIn(String displayName, String connectCode) {
        authDisplayName.setText(displayName == null || displayName.isEmpty()
                ? "Slippi player" : displayName);
        authConnectCode.setText(connectCode == null || connectCode.isEmpty()
                ? "" : connectCode);
        authLoading.setVisibility(View.GONE);
        authForm.setVisibility(View.GONE);
        authSignedIn.setVisibility(View.VISIBLE);
    }

    private void refresh() {
        String isoPath = prefs().getString(PREF_KEY_ISO_URI, null);
        File iso = isoPath != null ? new File(isoPath) : null;
        if (iso != null && iso.exists()) {
            isoStatus.setText(iso.getName() + "  ·  " + (iso.length() >> 20) + " MiB");
        } else {
            isoStatus.setText(R.string.iso_label_empty);
        }
        boolean hasAdapter = hasWiiUAdapter();
        adapterStatus.setText(hasAdapter ? R.string.adapter_connected : R.string.adapter_none);
        AudioPreset audioPreset = currentAudioPreset();
        audioStatus.setText(getString(R.string.audio_status_format,
                audioPreset.backend, audioPreset.bursts));
        refreshCoreStatus();
    }

    private void launchEmulation(File replayOrNull) {
        String isoPath = prefs().getString(PREF_KEY_ISO_URI, null);
        if (TextUtils.isEmpty(isoPath) || !new File(isoPath).exists()) {
            toast("Pick an ISO first");
            return;
        }
        EmulatorCore core = EmulatorCore.fromPref(
                prefs().getString(PREF_KEY_EMULATOR_CORE, EmulatorCore.DEFAULT.prefValue));
        if (core == EmulatorCore.MAINLINE) {
            launchMainlineDolphin(replayOrNull);
            return;
        }
        boolean useGcAdapter = applyRuntimeConfig();
        pushAdapterButtonMapsToNative();
        Intent it = new Intent(this, EmulationActivity.class);
        it.putExtra(EmulationActivity.EXTRA_ISO_PATH, isoPath);
        it.putExtra(EmulationActivity.EXTRA_USE_GC_ADAPTER, useGcAdapter);
        if (replayOrNull != null) {
            it.putExtra(EmulationActivity.EXTRA_REPLAY_PATH, replayOrNull.getAbsolutePath());
            it.putExtra(EmulationActivity.EXTRA_LAUNCH_MODE, EmulationActivity.LAUNCH_MODE_REPLAY);
        } else {
            it.putExtra(EmulationActivity.EXTRA_LAUNCH_MODE, EmulationActivity.LAUNCH_MODE_LIVE);
        }
        startActivity(it);
    }

    private void launchMainlineDolphin(File replayOrNull) {
        if (!MainlineCore.isPackaged(this)) {
            showMainlineMissingDialog();
            return;
        }

        String isoPath = prefs().getString(PREF_KEY_ISO_URI, null);
        boolean replayLaunch = replayOrNull != null;
        Intent it = new Intent(this, MainlineEmulationActivity.class);
        it.putExtra(MainlineEmulationActivity.EXTRA_ISO_PATH, isoPath);
        it.putExtra(MainlineEmulationActivity.EXTRA_USE_GC_ADAPTER,
                !replayLaunch && hasWiiUAdapter());
        it.putExtra(MainlineEmulationActivity.EXTRA_LAUNCH_MODE,
                replayLaunch
                        ? MainlineEmulationActivity.LAUNCH_MODE_REPLAY
                        : MainlineEmulationActivity.LAUNCH_MODE_LIVE);
        if (replayLaunch) {
            it.putExtra(MainlineEmulationActivity.EXTRA_REPLAY_PATH,
                    replayOrNull.getAbsolutePath());
        }
        try {
            startActivity(it);
        } catch (RuntimeException ex) {
            Log.e(TAG, "Failed to launch embedded mainline Dolphin: " + ex);
            toast(getString(R.string.core_mainline_launch_failed));
        }
    }

    /**
     * List controllers available for button remapping. GC adapter ports stay
     * here because remapping buttons does not alter the controller's stick data.
     */
    private void showRemapChooser() {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<String> deviceKeys = new java.util.ArrayList<>();
        labels.add(getString(R.string.calibrate_device));
        deviceKeys.add(ControllerProfile.DEVICE_BUILTIN);
        if (hasWiiUAdapter()) {
            for (int i = 0; i < 4; i++) {
                labels.add(getString(R.string.calibrate_adapter_port, i + 1));
                deviceKeys.add(ControllerProfile.adapterDeviceKey(i));
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.remap_pick_controller)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    Intent it = new Intent(this, ButtonMapActivity.class);
                    it.putExtra(ButtonMapActivity.EXTRA_DEVICE_KEY, deviceKeys.get(which));
                    it.putExtra(ButtonMapActivity.EXTRA_DEVICE_LABEL, labels.get(which));
                    startActivity(it);
                })
                .show();
    }

    private void showCalibrationChooser() {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<String> deviceKeys = new java.util.ArrayList<>();
        labels.add(getString(R.string.calibrate_device));
        deviceKeys.add(ControllerProfile.DEVICE_BUILTIN);
        new AlertDialog.Builder(this)
                .setTitle(R.string.calibrate_pick_controller)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    Intent it = new Intent(this, CalibrationActivity.class);
                    it.putExtra(CalibrationActivity.EXTRA_DEVICE_KEY, deviceKeys.get(which));
                    it.putExtra(CalibrationActivity.EXTRA_DEVICE_LABEL, labels.get(which));
                    startActivity(it);
                })
                .show();
    }

    private void showAudioChooser() {
        CharSequence[] labels = new CharSequence[AUDIO_PRESETS.length];
        int checked = -1;
        AudioPreset current = currentAudioPreset();
        for (int i = 0; i < AUDIO_PRESETS.length; i++) {
            AudioPreset preset = AUDIO_PRESETS[i];
            labels[i] = getString(preset.labelResId);
            if (preset.equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.audio_pick_preset)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    AudioPreset preset = AUDIO_PRESETS[which];
                    prefs().edit()
                            .putString(PREF_KEY_AUDIO_BACKEND, preset.backend)
                            .putInt(PREF_KEY_AUDIO_BUFFER_BURSTS, preset.bursts)
                            .apply();
                    refresh();
                    d.dismiss();
                })
                .show();
    }

    private void showDisplayLatencyChooser() {
        CharSequence[] labels = new CharSequence[DISPLAY_LATENCY_MODES.length];
        int checked = -1;
        DisplayLatencyMode current = currentDisplayLatencyMode();
        for (int i = 0; i < DISPLAY_LATENCY_MODES.length; i++) {
            DisplayLatencyMode mode = DISPLAY_LATENCY_MODES[i];
            labels[i] = getString(mode.labelResId);
            if (mode.equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.display_latency_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    DisplayLatencyMode mode = DISPLAY_LATENCY_MODES[which];
                    prefs().edit()
                            .putString(PREF_KEY_DISPLAY_LATENCY_MODE, mode.configValue)
                            .apply();
                    NativeLibrary.SetConfig("GFX.ini", "Settings",
                            "AndroidPresentMode", mode.configValue);
                    toast(getString(R.string.launcher_settings_display_latency_current,
                            getString(mode.labelResId)));
                    d.dismiss();
                })
                .show();
    }

    private void showGpuDriverChooser() {
        if (!GpuDriverManager.canAttemptCustomDriverLoading()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.gpu_driver_title)
                    .setMessage(R.string.gpu_driver_unsupported)
                    .setPositiveButton(R.string.gpu_driver_use_system, (dialog, which) -> {
                        GpuDriverManager.useSystemDriver(this);
                        applyGpuDriverConfig();
                        toast(getString(R.string.gpu_driver_system_selected));
                    })
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
            return;
        }

        AlertDialog loading = new AlertDialog.Builder(this)
                .setTitle(R.string.gpu_driver_title)
                .setMessage(R.string.gpu_driver_fetching)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        loading.show();

        new Thread(() -> {
            try {
                List<GpuDriverManager.DriverPackage> packages = GpuDriverManager.fetchCatalog();
                runOnUiThread(() -> {
                    if (loading.isShowing()) loading.dismiss();
                    showGpuDriverCatalog(packages);
                });
            } catch (Exception e) {
                Log.w(TAG, "GPU driver catalog fetch failed", e);
                runOnUiThread(() -> {
                    if (loading.isShowing()) loading.dismiss();
                    toast(getString(R.string.gpu_driver_fetch_failed,
                            e.getMessage() == null ? e.toString() : e.getMessage()));
                    showGpuDriverCatalog(new ArrayList<>());
                });
            }
        }, "GpuDriverCatalog").start();
    }

    private void showGpuDriverCatalog(List<GpuDriverManager.DriverPackage> packages) {
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        List<GpuDriverManager.CachedDriver> cachedDrivers =
                GpuDriverManager.listCachedDrivers(this);

        labels.add(getString(R.string.gpu_driver_use_system));
        actions.add(() -> {
            GpuDriverManager.useSystemDriver(this);
            applyGpuDriverConfig();
            toast(getString(R.string.gpu_driver_system_selected));
        });
        labels.add(getString(R.string.gpu_driver_import));
        actions.add(() -> pickGpuDriver.launch(new String[]{
                "application/zip", "application/octet-stream", "*/*"}));
        for (GpuDriverManager.CachedDriver cached : cachedDrivers) {
            labels.add(getString(R.string.gpu_driver_cached_item,
                    cached.label, cached.summary()));
            actions.add(() -> selectCachedGpuDriver(cached));
        }
        if (!cachedDrivers.isEmpty()) {
            labels.add(getString(R.string.gpu_driver_remove));
            actions.add(() -> {
                GpuDriverManager.removeInstalledDriver(this);
                applyGpuDriverConfig();
                toast(getString(R.string.gpu_driver_removed));
            });
        }
        for (GpuDriverManager.DriverPackage pkg : packages) {
            labels.add(pkg.displayName + "\n" + pkg.summary() + " · " + pkg.repositoryLabel);
            actions.add(() -> confirmGpuDriverInstall(pkg));
        }

        if (packages.isEmpty() && cachedDrivers.isEmpty()) {
            labels.add(getString(R.string.gpu_driver_empty));
            actions.add(() -> {});
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.gpu_driver_title_current,
                        GpuDriverManager.currentLabel(this)))
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    Runnable action = actions.get(which);
                    if (action != null) action.run();
                })
                .show();
    }

    private void confirmGpuDriverInstall(GpuDriverManager.DriverPackage pkg) {
        new AlertDialog.Builder(this)
                .setTitle(pkg.displayName)
                .setMessage(getString(R.string.gpu_driver_install_confirm,
                        pkg.repositoryLabel, pkg.summary()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.gpu_driver_download,
                        (dialog, which) -> installGpuDriver(pkg))
                .show();
    }

    private void installGpuDriver(GpuDriverManager.DriverPackage pkg) {
        showGpuDriverInstallProgress(pkg.displayName, progress ->
                GpuDriverManager.installFromUrl(getApplicationContext(), pkg, progress));
    }

    private void selectCachedGpuDriver(GpuDriverManager.CachedDriver driver) {
        showGpuDriverInstallProgress(driver.label, progress ->
                GpuDriverManager.selectCachedDriver(getApplicationContext(), driver));
    }

    private void installImportedGpuDriver(Uri uri) {
        showGpuDriverInstallProgress(getString(R.string.gpu_driver_imported_driver), progress -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Could not open selected driver");
                return GpuDriverManager.installFromStream(getApplicationContext(), in,
                        getString(R.string.gpu_driver_imported_driver),
                        getString(R.string.gpu_driver_import_source),
                        uri.toString(), progress);
            }
        });
    }

    private void showGpuDriverInstallProgress(String title, GpuDriverInstallAction action) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad / 2, pad, 0);
        TextView label = new TextView(this);
        label.setTextColor(getColor(R.color.text_primary));
        label.setText(R.string.gpu_driver_installing);
        ProgressBar progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setMax(1000);
        body.addView(label);
        body.addView(progress);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(body)
                .setCancelable(false)
                .create();
        progressDialog.show();

        new Thread(() -> {
            try {
                GpuDriverManager.InstallResult result = action.run(
                        (stage, completedBytes, totalBytes) -> runOnUiThread(() -> {
                            label.setText(stage);
                            if (totalBytes > 0L && completedBytes >= 0L) {
                                progress.setIndeterminate(false);
                                progress.setProgress((int) Math.min(
                                        1000L, completedBytes * 1000L / totalBytes));
                            } else {
                                progress.setIndeterminate(true);
                            }
                        }));
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (!result.success) {
                        showGpuDriverInstallFailure(result.error);
                        return;
                    }
                    applyGpuDriverConfig();
                    toast(getString(R.string.gpu_driver_installed,
                            GpuDriverManager.currentLabel(this)));
                });
            } catch (Exception e) {
                Log.w(TAG, "GPU driver install failed", e);
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showGpuDriverInstallFailure(e.getMessage() == null
                            ? e.toString() : e.getMessage());
                });
            }
        }, "GpuDriverInstall").start();
    }

    private void showGpuDriverInstallFailure(String message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.gpu_driver_install_failed_title)
                .setMessage(message == null ? getString(R.string.gpu_driver_install_failed) : message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void applyGpuDriverConfig() {
        applyGpuDriverConfig(prefs().getString(PREF_KEY_BACKEND, BACKEND_VULKAN));
    }

    private void applyGpuDriverConfig(String backend) {
        GpuDriverManager.prepareNativeDirectoriesForBackend(this, backend);
        NativeLibrary.SetConfig("GFX.ini", "Settings", "DriverLibName",
                GpuDriverManager.selectedLibraryNameForBackend(this, backend));
    }

    private void showLauncherSettingsMenu(View anchor) {
        AudioPreset audioPreset = currentAudioPreset();
        DisplayLatencyMode displayLatencyMode = currentDisplayLatencyMode();
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenuInflater().inflate(R.menu.menu_launcher_settings, menu.getMenu());
        menu.getMenu().findItem(R.id.menu_launcher_audio).setTitle(
                getString(R.string.launcher_settings_audio_current,
                        audioPreset.backend, audioPreset.bursts));
        menu.getMenu().findItem(R.id.menu_launcher_gpu_driver).setTitle(
                getString(R.string.launcher_settings_gpu_driver_current,
                        GpuDriverManager.currentLabel(this)));
        menu.getMenu().findItem(R.id.menu_launcher_display_latency).setTitle(
                getString(R.string.launcher_settings_display_latency_current,
                        getString(displayLatencyMode.labelResId)));
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_launcher_audio) {
                showAudioChooser();
                return true;
            }
            if (id == R.id.menu_launcher_gpu_driver) {
                showGpuDriverChooser();
                return true;
            }
            if (id == R.id.menu_launcher_display_latency) {
                showDisplayLatencyChooser();
                return true;
            }
            if (id == R.id.menu_launcher_calibrate) {
                showCalibrationChooser();
                return true;
            }
            if (id == R.id.menu_launcher_remap) {
                showRemapChooser();
                return true;
            }
            if (id == R.id.menu_launcher_touch) {
                startActivity(new Intent(this, TouchOverlayActivity.class));
                return true;
            }
            return false;
        });
        menu.show();
    }

    private interface GpuDriverInstallAction {
        GpuDriverManager.InstallResult run(GpuDriverManager.ProgressListener progress)
                throws IOException;
    }

    /**
     * Push every saved per-port adapter button remap to the C++ side
     * so GCAdapter::Input picks them up on the next poll. Ports
     * without a saved remap are reset to identity to clear any state
     * from a previous run.
     */
    private void pushAdapterButtonMapsToNative() {
        org.dolphinemu.dolphinemu.controller.ControllerProfile profile =
                new org.dolphinemu.dolphinemu.controller.ControllerProfile(this);
        for (int port = 0; port < 4; port++) {
            String key = org.dolphinemu.dolphinemu.controller.ControllerProfile
                    .adapterDeviceKey(port);
            if (!profile.hasButtonMap(key)) {
                NativeLibrary.ClearGCAdapterButtonMap(port);
                continue;
            }
            org.dolphinemu.dolphinemu.controller.ButtonMap m =
                    profile.getButtonMap(key);
            // First clear so any stale customization is wiped, then
            // push every binding.
            NativeLibrary.ClearGCAdapterButtonMap(port);
            for (int gcBit : org.dolphinemu.dolphinemu.controller.ButtonMap.GC_BUTTONS_DISPLAY_ORDER) {
                for (int sourceBit : m.keysBoundTo(gcBit)) {
                    NativeLibrary.SetGCAdapterButtonMap(port, sourceBit, gcBit);
                }
            }
        }
    }

    /**
     * Override any session-dependent values in Dolphin.ini just before
     * BootCore runs. Driven by SharedPrefs the user chose in this Activity,
     * plus runtime state (adapter presence) — both can change between
     * launches and we don't want to rewrite the whole defaults file.
     */
    private boolean applyRuntimeConfig() {
        boolean hasAdapter = applyControllerAndAvRuntimeConfig();
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SlotA",
                Integer.toString(NativeLibrary.EXI_DEVICE_NONE));
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SerialPort1",
                Integer.toString(NativeLibrary.EXI_DEVICE_NONE));
        return hasAdapter;
    }

    private boolean applyControllerAndAvRuntimeConfig() {
        // Graphics backend selection from the toggle. Same default as
        // the initial-restore path so a fresh install gets Vulkan
        // without first touching the toggle.
        String backend = prefs().getString(PREF_KEY_BACKEND, BACKEND_VULKAN);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "GFXBackend", backend);
        applyGpuDriverConfig(backend);
        NativeLibrary.SetConfig("GFX.ini", "Settings", "AndroidPresentMode",
                currentDisplayLatencyMode().configValue);
        DolphinSettings.applyIshiirukaGraphicsConfig(this);

        AudioPreset audioPreset = currentAudioPreset();
        NativeLibrary.SetConfig("Dolphin.ini", "DSP", "Backend", audioPreset.backend);
        NativeLibrary.SetConfig("Dolphin.ini", "DSP", "AndroidAudioBufferBursts",
                Integer.toString(audioPreset.bursts));

        // SIDevice routing: 12 = WIIU_ADAPTER, 6 = emulated GC pad (which
        // reads from our Touchscreen-via-ButtonManager input bus).
        boolean hasAdapter = hasWiiUAdapter();
        String port0 = hasAdapter ? "12" : "6";
        String portN = hasAdapter ? "12" : "0";
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice0", port0);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice1", portN);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice2", portN);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice3", portN);
        return hasAdapter;
    }

    private AudioPreset currentAudioPreset() {
        SharedPreferences p = prefs();
        String backend = p.getString(PREF_KEY_AUDIO_BACKEND, AUDIO_BACKEND_OBOE);
        int bursts = p.getInt(PREF_KEY_AUDIO_BUFFER_BURSTS, AUDIO_BURSTS_BALANCED);
        AudioPreset candidate = new AudioPreset(backend, bursts, R.string.audio_preset_custom);
        for (AudioPreset preset : AUDIO_PRESETS) {
            if (preset.equals(candidate)) return preset;
        }
        return candidate;
    }

    private DisplayLatencyMode currentDisplayLatencyMode() {
        String value = prefs().getString(PREF_KEY_DISPLAY_LATENCY_MODE, DISPLAY_LATENCY_SMOOTH);
        for (DisplayLatencyMode mode : DISPLAY_LATENCY_MODES) {
            if (mode.configValue.equals(value)) return mode;
        }
        return DISPLAY_LATENCY_MODES[0];
    }

    private boolean hasWiiUAdapter() {
        UsbManager m = (UsbManager) getSystemService(USB_SERVICE);
        if (m == null) return false;
        for (UsbDevice d : m.getDeviceList().values()) {
            if (d.getVendorId() == 0x057E && d.getProductId() == 0x0337) return true;
        }
        return false;
    }

    private void refreshCoreStatus() {
        EmulatorCore core = EmulatorCore.fromPref(
                prefs().getString(PREF_KEY_EMULATOR_CORE, EmulatorCore.DEFAULT.prefValue));
        if (core == EmulatorCore.ISHIIRUKA) {
            emulatorCoreStatus.setText(R.string.core_status_ishiiruka);
            return;
        }
        emulatorCoreStatus.setText(!MainlineCore.isPackaged(this)
                ? R.string.core_status_mainline_missing
                : R.string.core_status_mainline_available);
    }

    private void showMainlineMissingDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.core_mainline_missing_title)
                .setMessage(R.string.core_mainline_missing_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }

    private File copyContentToCache(Uri uri, String name) {
        File dst = new File(getFilesDir(), name);
        return copyContentToFile(uri, dst) ? dst : null;
    }

    private boolean copyContentToFile(Uri uri, File dst) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) return false;
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (IOException ex) {
            Log.e(TAG, "copy failed: " + ex);
            return false;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static final class AudioPreset {
        final String backend;
        final int bursts;
        final int labelResId;

        AudioPreset(String backend, int bursts, int labelResId) {
            this.backend = backend == null ? AUDIO_BACKEND_OBOE : backend;
            this.bursts = bursts;
            this.labelResId = labelResId;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof AudioPreset)) return false;
            AudioPreset other = (AudioPreset) obj;
            return backend.equals(other.backend) && bursts == other.bursts;
        }

        @Override
        public int hashCode() {
            return 31 * backend.hashCode() + bursts;
        }
    }

    private static final class DisplayLatencyMode {
        final String configValue;
        final int labelResId;

        DisplayLatencyMode(String configValue, int labelResId) {
            this.configValue = configValue;
            this.labelResId = labelResId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof DisplayLatencyMode)) return false;
            DisplayLatencyMode that = (DisplayLatencyMode) other;
            return configValue.equals(that.configValue);
        }

        @Override
        public int hashCode() {
            return configValue.hashCode();
        }
    }
}
