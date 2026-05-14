package org.dolphinemu.dolphinemu.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.dolphinemu.dolphinemu.EmulatorCore;
import org.dolphinemu.dolphinemu.MainlineCore;
import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;
import org.dolphinemu.dolphinemu.controller.ButtonMap;
import org.dolphinemu.dolphinemu.controller.ControllerProfile;
import org.dolphinemu.dolphinemu.training.TrainingModeManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TrainingModeActivity extends AppCompatActivity {
    private static final String TAG = "TrainingModeActivity";
    private static final String PREF_KEY_ISO_URI = "iso_uri";
    private static final String PREF_KEY_BACKEND = "backend";
    private static final String PREF_KEY_AUDIO_BACKEND = "audio_backend";
    private static final String PREF_KEY_AUDIO_BUFFER_BURSTS = "audio_buffer_bursts";
    private static final String PREF_KEY_TRAINING_LAST_CHECK_MS = "training_latest_checked_ms";
    private static final String BACKEND_VULKAN = "Vulkan";
    private static final String AUDIO_BACKEND_OBOE = "Oboe";
    private static final int AUDIO_BURSTS_BALANCED = 4;
    private static final long TRAINING_UPDATE_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);

    private TextView isoStatus;
    private TextView trainingStatus;
    private TextView trainingVersion;
    private TextView trainingStorage;
    private TextView trainingInstalledPath;
    private Button trainingBuild;
    private Button trainingPlay;
    private Button trainingCheckUpdates;
    private Button trainingRemove;

    private final ExecutorService trainingExecutor = Executors.newSingleThreadExecutor();
    private TrainingModeManager.ReleaseInfo latestTrainingRelease;
    private TrainingModeManager.InstalledInfo installedTraining;
    private volatile boolean trainingBusy;

    private final ActivityResultLauncher<String[]> pickIso =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {}
                File copied = copyContentToCache(uri, "rom.iso");
                if (copied != null) {
                    prefs().edit().putString(PREF_KEY_ISO_URI, copied.getAbsolutePath()).apply();
                    refreshTrainingStatus();
                    maybeCheckTrainingUpdates(false);
                } else {
                    toast("Failed to import ISO");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_training_mode);
        isoStatus = findViewById(R.id.training_iso_status);
        trainingStatus = findViewById(R.id.training_status);
        trainingVersion = findViewById(R.id.training_version);
        trainingStorage = findViewById(R.id.training_storage);
        trainingInstalledPath = findViewById(R.id.training_installed_path);
        trainingBuild = findViewById(R.id.training_build);
        trainingPlay = findViewById(R.id.training_play);
        trainingCheckUpdates = findViewById(R.id.training_check_updates);
        trainingRemove = findViewById(R.id.training_remove);

        UserDirectoryBootstrap.ensureLayout(this);
        NativeLibrary.SetUserDirectory(UserDirectoryBootstrap.userDir(this).getAbsolutePath());
        NativeLibrary.CreateUserFolders();

        findViewById(R.id.training_back).setOnClickListener(v -> finish());
        findViewById(R.id.training_pick_iso).setOnClickListener(v -> pickIso.launch(new String[]{"*/*"}));
        trainingBuild.setOnClickListener(v -> prepareTrainingBuild());
        trainingPlay.setOnClickListener(v -> launchTrainingMode());
        trainingCheckUpdates.setOnClickListener(v -> checkTrainingUpdates(true));
        trainingRemove.setOnClickListener(v -> confirmRemoveTrainingMode());

        latestTrainingRelease = TrainingModeManager.loadCachedRelease(this);
        refreshTrainingStatus();
        maybeCheckTrainingUpdates(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTrainingStatus();
    }

    @Override
    protected void onDestroy() {
        trainingExecutor.shutdownNow();
        super.onDestroy();
    }

    private void maybeCheckTrainingUpdates(boolean force) {
        long lastCheckMs = prefs().getLong(PREF_KEY_TRAINING_LAST_CHECK_MS, 0L);
        boolean stale = System.currentTimeMillis() - lastCheckMs > TRAINING_UPDATE_INTERVAL_MS;
        if (force || latestTrainingRelease == null || stale) {
            checkTrainingUpdates(false);
        }
    }

    private void checkTrainingUpdates(boolean userInitiated) {
        if (trainingBusy) {
            if (userInitiated) toast("Training Mode is already working");
            return;
        }
        trainingBusy = true;
        refreshTrainingStatus();
        trainingExecutor.execute(() -> {
            try {
                TrainingModeManager.ReleaseInfo release =
                        TrainingModeManager.fetchLatestRelease(getApplicationContext());
                prefs().edit()
                        .putLong(PREF_KEY_TRAINING_LAST_CHECK_MS, System.currentTimeMillis())
                        .apply();
                runOnUiThread(() -> {
                    latestTrainingRelease = release;
                    trainingBusy = false;
                    refreshTrainingStatus();
                    if (userInitiated) {
                        toast(TrainingModeManager.isUpdateAvailable(installedTraining, release)
                                ? "Training Mode update found"
                                : "Training Mode is up to date");
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Training Mode update check failed", e);
                runOnUiThread(() -> {
                    trainingBusy = false;
                    refreshTrainingStatus();
                    if (userInitiated) toast("Couldn't check for Training Mode updates");
                });
            }
        });
    }

    private void prepareTrainingBuild() {
        File vanillaIso = selectedIsoFile();
        if (vanillaIso == null) {
            toast("Choose a Melee ISO first");
            return;
        }
        if (trainingBusy) {
            toast("Training Mode is already working");
            return;
        }
        trainingBusy = true;
        refreshTrainingStatus();
        trainingExecutor.execute(() -> {
            try {
                TrainingModeManager.ReleaseInfo release = latestTrainingRelease;
                if (release == null) {
                    release = TrainingModeManager.fetchLatestRelease(getApplicationContext());
                    prefs().edit()
                            .putLong(PREF_KEY_TRAINING_LAST_CHECK_MS, System.currentTimeMillis())
                            .apply();
                }
                TrainingModeManager.BuildPlan plan =
                        TrainingModeManager.createBuildPlan(getApplicationContext(), vanillaIso, release);
                TrainingModeManager.ReleaseInfo finalRelease = release;
                runOnUiThread(() -> {
                    latestTrainingRelease = finalRelease;
                    trainingBusy = false;
                    refreshTrainingStatus();
                    showTrainingStorageDialog(vanillaIso, plan);
                });
            } catch (Exception e) {
                Log.w(TAG, "Training Mode build preparation failed", e);
                runOnUiThread(() -> {
                    trainingBusy = false;
                    refreshTrainingStatus();
                    toast("Couldn't prepare Training Mode build");
                });
            }
        });
    }

    private void showTrainingStorageDialog(File vanillaIso, TrainingModeManager.BuildPlan plan) {
        boolean enoughSpace = plan.freeBytes >= plan.peakAdditionalBytes;
        String message = getString(R.string.training_storage_dialog_body,
                plan.release.name,
                plan.release.tagName,
                TrainingModeManager.formatBytes(plan.permanentAdditionalBytes),
                TrainingModeManager.formatBytes(plan.peakAdditionalBytes),
                TrainingModeManager.formatBytes(plan.patchDownloadBytes),
                TrainingModeManager.formatBytes(plan.freeBytes));
        if (!enoughSpace) {
            message += "\n\n" + getString(R.string.training_storage_dialog_not_enough);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.training_storage_dialog_title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.training_build, (d, which) ->
                        startTrainingBuild(vanillaIso, plan.release))
                .create();
        dialog.setOnShowListener(d -> {
            Button build = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (build != null) build.setEnabled(enoughSpace);
        });
        dialog.show();
    }

    private void startTrainingBuild(File vanillaIso, TrainingModeManager.ReleaseInfo release) {
        if (trainingBusy) return;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad / 2, pad, 0);
        TextView label = new TextView(this);
        label.setTextColor(getColor(R.color.text_primary));
        label.setText(R.string.training_building);
        ProgressBar progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setMax(1000);
        body.addView(label);
        body.addView(progress);
        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.training_build_progress_title)
                .setView(body)
                .setCancelable(false)
                .create();
        progressDialog.show();

        trainingBusy = true;
        refreshTrainingStatus();
        trainingExecutor.execute(() -> {
            try {
                TrainingModeManager.InstalledInfo installed =
                        TrainingModeManager.buildTrainingIso(
                                getApplicationContext(), vanillaIso, release,
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
                    installedTraining = installed;
                    trainingBusy = false;
                    progressDialog.dismiss();
                    refreshTrainingStatus();
                    toast("Training Mode is ready");
                });
            } catch (Exception e) {
                Log.e(TAG, "Training Mode build failed", e);
                runOnUiThread(() -> {
                    trainingBusy = false;
                    progressDialog.dismiss();
                    refreshTrainingStatus();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.training_build_failed_title)
                            .setMessage(e.getMessage() == null
                                    ? getString(R.string.training_build_failed_body)
                                    : e.getMessage())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        });
    }

    private void confirmRemoveTrainingMode() {
        installedTraining = TrainingModeManager.loadInstalled(this);
        if (installedTraining == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.training_remove_dialog_title)
                .setMessage(getString(R.string.training_remove_dialog_body,
                        TrainingModeManager.formatBytes(installedTraining.isoSizeBytes)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.training_remove, (dialog, which) -> removeTrainingMode())
                .show();
    }

    private void removeTrainingMode() {
        try {
            TrainingModeManager.deleteInstalled(this);
            installedTraining = null;
            refreshTrainingStatus();
            toast("Training Mode removed");
        } catch (IOException e) {
            Log.w(TAG, "remove Training Mode failed", e);
            toast("Couldn't remove Training Mode");
        }
    }

    private void refreshTrainingStatus() {
        installedTraining = TrainingModeManager.loadInstalled(this);
        if (latestTrainingRelease == null) {
            latestTrainingRelease = TrainingModeManager.loadCachedRelease(this);
        }

        File vanillaIso = selectedIsoFile();
        boolean hasIso = vanillaIso != null;
        boolean hasInstalled = installedTraining != null
                && !TextUtils.isEmpty(installedTraining.isoPath)
                && new File(installedTraining.isoPath).exists();
        boolean updateAvailable = TrainingModeManager.isUpdateAvailable(
                installedTraining, latestTrainingRelease);

        if (hasIso) {
            isoStatus.setText(vanillaIso.getName() + "  ·  " + (vanillaIso.length() >> 20) + " MiB");
        } else {
            isoStatus.setText(R.string.iso_label_empty);
        }

        if (trainingBusy) {
            trainingStatus.setText(R.string.training_status_working);
        } else if (!hasIso) {
            trainingStatus.setText(R.string.training_status_no_iso);
        } else if (hasInstalled && updateAvailable) {
            trainingStatus.setText(getString(R.string.training_status_update_available,
                    latestTrainingRelease.tagName));
        } else if (hasInstalled) {
            trainingStatus.setText(R.string.training_status_ready);
        } else if (latestTrainingRelease != null) {
            trainingStatus.setText(getString(R.string.training_status_can_build,
                    latestTrainingRelease.tagName));
        } else {
            trainingStatus.setText(R.string.training_status_needs_check);
        }

        if (hasInstalled) {
            trainingVersion.setText(getString(R.string.training_installed_format,
                    installedTraining.tagName == null || installedTraining.tagName.isEmpty()
                            ? getString(R.string.training_unknown_version)
                            : installedTraining.tagName,
                    TrainingModeManager.formatBytes(installedTraining.isoSizeBytes)));
            trainingInstalledPath.setText(installedTraining.isoPath);
        } else if (latestTrainingRelease != null) {
            trainingVersion.setText(getString(R.string.training_latest_format,
                    latestTrainingRelease.tagName));
            trainingInstalledPath.setText(R.string.training_no_installed_path);
        } else {
            trainingVersion.setText(R.string.training_no_version);
            trainingInstalledPath.setText(R.string.training_no_installed_path);
        }

        if (hasIso && latestTrainingRelease != null) {
            TrainingModeManager.BuildPlan plan =
                    TrainingModeManager.createBuildPlan(this, vanillaIso, latestTrainingRelease);
            trainingStorage.setText(getString(R.string.training_storage_summary,
                    TrainingModeManager.formatBytes(plan.permanentAdditionalBytes),
                    TrainingModeManager.formatBytes(plan.peakAdditionalBytes)));
        } else {
            trainingStorage.setText(R.string.training_storage_unknown);
        }

        setActionEnabled(trainingBuild, !trainingBusy && hasIso && latestTrainingRelease != null);
        trainingBuild.setText(hasInstalled && updateAvailable
                ? R.string.training_update
                : (hasInstalled ? R.string.training_rebuild : R.string.training_build));
        setActionEnabled(trainingPlay, !trainingBusy && hasInstalled);
        setActionEnabled(trainingCheckUpdates, !trainingBusy);
        setActionEnabled(trainingRemove, !trainingBusy && hasInstalled);
    }

    private void setActionEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.42f);
    }

    private void launchTrainingMode() {
        installedTraining = TrainingModeManager.loadInstalled(this);
        if (installedTraining == null || TextUtils.isEmpty(installedTraining.isoPath)
                || !new File(installedTraining.isoPath).exists()) {
            toast("Build Training Mode first");
            refreshTrainingStatus();
            return;
        }
        EmulatorCore core = EmulatorCore.fromPref(
                prefs().getString(EmulatorCore.PREF_KEY, EmulatorCore.DEFAULT.prefValue));
        if (core == EmulatorCore.MAINLINE) {
            launchMainlineTrainingMode(installedTraining.isoPath);
            return;
        }

        boolean useGcAdapter = applyTrainingRuntimeConfig();
        pushAdapterButtonMapsToNative();
        Intent it = new Intent(this, EmulationActivity.class);
        it.putExtra(EmulationActivity.EXTRA_ISO_PATH, installedTraining.isoPath);
        it.putExtra(EmulationActivity.EXTRA_USE_GC_ADAPTER, useGcAdapter);
        it.putExtra(EmulationActivity.EXTRA_LAUNCH_MODE, EmulationActivity.LAUNCH_MODE_TRAINING);
        startActivity(it);
    }

    private void launchMainlineTrainingMode(String isoPath) {
        if (!MainlineCore.isPackaged(this)) {
            toast(getString(R.string.core_mainline_launch_failed));
            return;
        }

        Intent it = new Intent(this, MainlineEmulationActivity.class);
        it.putExtra(MainlineEmulationActivity.EXTRA_ISO_PATH, isoPath);
        it.putExtra(MainlineEmulationActivity.EXTRA_USE_GC_ADAPTER, hasWiiUAdapter());
        it.putExtra(MainlineEmulationActivity.EXTRA_LAUNCH_MODE,
                MainlineEmulationActivity.LAUNCH_MODE_TRAINING);
        try {
            startActivity(it);
        } catch (RuntimeException ex) {
            Log.e(TAG, "Failed to launch mainline Training Mode", ex);
            toast(getString(R.string.core_mainline_launch_failed));
        }
    }

    private boolean applyTrainingRuntimeConfig() {
        boolean hasAdapter = applyControllerAndAvRuntimeConfig();
        File gcDir = new File(UserDirectoryBootstrap.userDir(this), "GC");
        if (!gcDir.exists()) gcDir.mkdirs();
        File trainingCard = new File(gcDir, "TrainingMode.USA.raw");
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SlotA",
                Integer.toString(NativeLibrary.EXI_DEVICE_MEMORYCARD));
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "MemcardAPath",
                trainingCard.getAbsolutePath());
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SerialPort1",
                Integer.toString(NativeLibrary.EXI_DEVICE_NONE));
        return hasAdapter;
    }

    private boolean applyControllerAndAvRuntimeConfig() {
        String backend = prefs().getString(PREF_KEY_BACKEND, BACKEND_VULKAN);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "GFXBackend", backend);

        String audioBackend = prefs().getString(PREF_KEY_AUDIO_BACKEND, AUDIO_BACKEND_OBOE);
        int bursts = prefs().getInt(PREF_KEY_AUDIO_BUFFER_BURSTS, AUDIO_BURSTS_BALANCED);
        NativeLibrary.SetConfig("Dolphin.ini", "DSP", "Backend", audioBackend);
        NativeLibrary.SetConfig("Dolphin.ini", "DSP", "AndroidAudioBufferBursts",
                Integer.toString(bursts));

        boolean hasAdapter = hasWiiUAdapter();
        String port0 = hasAdapter ? "12" : "6";
        String portN = hasAdapter ? "12" : "0";
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice0", port0);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice1", portN);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice2", portN);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice3", portN);
        return hasAdapter;
    }

    private void pushAdapterButtonMapsToNative() {
        ControllerProfile profile = new ControllerProfile(this);
        for (int port = 0; port < 4; port++) {
            String key = ControllerProfile.adapterDeviceKey(port);
            if (!profile.hasButtonMap(key)) {
                NativeLibrary.ClearGCAdapterButtonMap(port);
                continue;
            }
            ButtonMap map = profile.getButtonMap(key);
            NativeLibrary.ClearGCAdapterButtonMap(port);
            for (int gcBit : ButtonMap.GC_BUTTONS_DISPLAY_ORDER) {
                for (int sourceBit : map.keysBoundTo(gcBit)) {
                    NativeLibrary.SetGCAdapterButtonMap(port, sourceBit, gcBit);
                }
            }
        }
    }

    private boolean hasWiiUAdapter() {
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        if (manager == null) return false;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() == 0x057E && device.getProductId() == 0x0337) return true;
        }
        return false;
    }

    private File selectedIsoFile() {
        String isoPath = prefs().getString(PREF_KEY_ISO_URI, null);
        if (TextUtils.isEmpty(isoPath)) return null;
        File iso = new File(isoPath);
        return iso.exists() ? iso : null;
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

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
