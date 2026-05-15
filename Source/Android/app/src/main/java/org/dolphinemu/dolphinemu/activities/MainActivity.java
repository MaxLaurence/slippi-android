package org.dolphinemu.dolphinemu.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButtonToggleGroup;

import org.dolphinemu.dolphinemu.EmulatorCore;
import org.dolphinemu.dolphinemu.MainlineCore;
import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;
import org.dolphinemu.dolphinemu.gpu.GpuDriverManager;
import org.dolphinemu.dolphinemu.settings.DolphinSettings;
import org.dolphinemu.dolphinemu.utils.ContentCopyTask;
// SlippiAuthClient / SlippiSession (Firebase-based) intentionally removed
// — the Slippi team prefers users go through their slippi.gg login flow
// in a WebView (SlippiLoginActivity), which we trigger from the auth card
// below.

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One-screen launcher: pick an ISO, sign into Slippi (or drop in a
 * user.json), choose an emulator core and graphics backend, hit Play.
 * All heavy lifting is in the native core; this Activity just sets the dial.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREF_KEY_ISO_URI = "iso_uri";
    private static final String PREF_KEY_EMULATOR_CORE = EmulatorCore.PREF_KEY;
    private static final String PREF_KEY_LAUNCH_DISCLAIMER_SEEN =
            "launch_disclaimer_seen_v1";

    private TextView isoStatus;
    private TextView adapterStatus;
    private TextView audioStatus;
    private TextView displayStatus;
    private TextView refreshRateStatus;
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
                importIso(uri);
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        isoStatus = findViewById(R.id.iso_status);
        adapterStatus = findViewById(R.id.adapter_status);
        audioStatus = findViewById(R.id.audio_status);
        displayStatus = findViewById(R.id.display_status);
        refreshRateStatus = findViewById(R.id.refresh_rate_status);
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
        String saved = DolphinSettings.getBackend(this);
        backendToggle.check(DolphinSettings.BACKEND_VULKAN.equals(saved)
                ? R.id.backend_vulkan : R.id.backend_ogl);
        backendToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            String value = checkedId == R.id.backend_vulkan
                    ? DolphinSettings.BACKEND_VULKAN : DolphinSettings.BACKEND_OGL;
            DolphinSettings.setBackend(this, value);
            applyGpuDriverConfig(value);
            refresh();
        });

        // Initial auth state: signed in iff there's a user.json on disk.
        if (UserDirectoryBootstrap.slippiUserJson(this).exists()) {
            renderAuthFromUserJson();
        } else {
            renderAuthLoggedOut(null);
        }
        showLaunchDisclaimerIfNeeded();
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

    private void showLaunchDisclaimerIfNeeded() {
        SharedPreferences p = prefs();
        if (p.getBoolean(PREF_KEY_LAUNCH_DISCLAIMER_SEEN, false)) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.launch_disclaimer_title)
                .setMessage(R.string.launch_disclaimer_body)
                .setPositiveButton(R.string.launch_disclaimer_confirm, (dialog, which) ->
                        p.edit().putBoolean(PREF_KEY_LAUNCH_DISCLAIMER_SEEN, true).apply())
                .setOnCancelListener(dialog ->
                        p.edit().putBoolean(PREF_KEY_LAUNCH_DISCLAIMER_SEEN, true).apply())
                .show();
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
        DolphinSettings.AudioPreset audioPreset = DolphinSettings.getAudioPreset(this);
        audioStatus.setText(getString(R.string.audio_status_format,
                audioPreset.backend, audioPreset.bursts));
        DolphinSettings.DisplayLatencyMode displayLatencyMode =
                DolphinSettings.getDisplayLatencyMode(this);
        displayStatus.setText(getString(R.string.display_status_format,
                getString(displayLatencyMode.labelResId)));
        refreshRateStatus.setText(Settings.System.canWrite(this)
                ? R.string.refresh_caps_available
                : R.string.refresh_caps_permission_missing);
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

    private void applyGpuDriverConfig() {
        applyGpuDriverConfig(DolphinSettings.getBackend(this));
    }

    private void applyGpuDriverConfig(String backend) {
        GpuDriverManager.prepareNativeDirectoriesForBackend(this, backend);
        NativeLibrary.SetConfig("GFX.ini", "Settings", "DriverLibName",
                GpuDriverManager.selectedLibraryNameForBackend(this, backend));
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
        String backend = DolphinSettings.getBackend(this);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "GFXBackend", backend);
        applyGpuDriverConfig(backend);
        NativeLibrary.SetConfig("GFX.ini", "Settings", "AndroidPresentMode",
                DolphinSettings.getDisplayLatencyMode(this).configValue);
        DolphinSettings.applyIshiirukaGraphicsConfig(this);

        DolphinSettings.AudioPreset audioPreset = DolphinSettings.getAudioPreset(this);
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

    private void importIso(Uri uri) {
        File dst = new File(getFilesDir(), "rom.iso");
        ContentCopyTask.copy(this, uri, dst, R.string.iso_import_title,
                new ContentCopyTask.Callback() {
                    @Override
                    public void onCopied(File file) {
                        prefs().edit()
                                .putString(PREF_KEY_ISO_URI, file.getAbsolutePath())
                                .apply();
                        refresh();
                    }

                    @Override
                    public void onFailed(Exception exception) {
                        Log.e(TAG, "ISO import failed", exception);
                        toast(getString(R.string.iso_import_failed));
                    }

                    @Override
                    public void onCancelled() {
                        toast(getString(R.string.iso_import_cancelled));
                    }
                });
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
}
