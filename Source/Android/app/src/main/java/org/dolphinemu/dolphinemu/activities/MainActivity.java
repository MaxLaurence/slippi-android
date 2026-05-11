package org.dolphinemu.dolphinemu.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButtonToggleGroup;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One-screen launcher: pick an ISO, drop in a Slippi user.json, pick a
 * graphics backend, hit Play. All heavy lifting is in the C++/Rust core;
 * this Activity just sets the dial.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREF_KEY_ISO_URI = "iso_uri";
    private static final String PREF_KEY_BACKEND = "backend";
    private static final String BACKEND_VULKAN = "Vulkan";
    private static final String BACKEND_OGL = "OGL";

    private TextView isoStatus;
    private TextView userJsonStatus;
    private TextView adapterStatus;
    private MaterialButtonToggleGroup backendToggle;

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
        userJsonStatus = findViewById(R.id.user_json_status);
        adapterStatus = findViewById(R.id.adapter_status);
        backendToggle = findViewById(R.id.backend_toggle);

        UserDirectoryBootstrap.ensureLayout(this);
        NativeLibrary.SetUserDirectory(UserDirectoryBootstrap.userDir(this).getAbsolutePath());
        NativeLibrary.CreateUserFolders();

        findViewById(R.id.pick_iso).setOnClickListener(v -> pickIso.launch(new String[]{"*/*"}));
        findViewById(R.id.pick_user_json).setOnClickListener(v ->
                pickUserJson.launch(new String[]{"application/json", "*/*"}));
        findViewById(R.id.play).setOnClickListener(v -> launchEmulation());

        // Restore the saved backend selection (defaults to OGL — empirically
        // a hair tighter than Vulkan on Adreno for Slippi). The
        // MaterialButtonToggleGroup callback fires on initial check too,
        // which is fine since we're idempotent.
        String saved = prefs().getString(PREF_KEY_BACKEND, BACKEND_OGL);
        backendToggle.check(BACKEND_VULKAN.equals(saved)
                ? R.id.backend_vulkan : R.id.backend_ogl);
        backendToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            String value = checkedId == R.id.backend_vulkan ? BACKEND_VULKAN : BACKEND_OGL;
            prefs().edit().putString(PREF_KEY_BACKEND, value).apply();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        String isoPath = prefs().getString(PREF_KEY_ISO_URI, null);
        File iso = isoPath != null ? new File(isoPath) : null;
        if (iso != null && iso.exists()) {
            isoStatus.setText(iso.getName() + "  ·  " + (iso.length() >> 20) + " MiB");
        } else {
            isoStatus.setText(R.string.iso_label_empty);
        }
        File u = UserDirectoryBootstrap.slippiUserJson(this);
        userJsonStatus.setText(u.exists()
                ? "Imported (" + u.length() + " B)"
                : getString(R.string.user_json_label_empty));
        boolean hasAdapter = hasWiiUAdapter();
        adapterStatus.setText(hasAdapter ? R.string.adapter_connected : R.string.adapter_none);
    }

    private void launchEmulation() {
        String isoPath = prefs().getString(PREF_KEY_ISO_URI, null);
        if (TextUtils.isEmpty(isoPath) || !new File(isoPath).exists()) {
            toast("Pick an ISO first");
            return;
        }
        applyRuntimeConfig();
        Intent it = new Intent(this, EmulationActivity.class);
        it.putExtra(EmulationActivity.EXTRA_ISO_PATH, isoPath);
        startActivity(it);
    }

    /**
     * Override any session-dependent values in Dolphin.ini just before
     * BootCore runs. Driven by SharedPrefs the user chose in this Activity,
     * plus runtime state (adapter presence) — both can change between
     * launches and we don't want to rewrite the whole defaults file.
     */
    private void applyRuntimeConfig() {
        // Graphics backend selection from the toggle.
        String backend = prefs().getString(PREF_KEY_BACKEND, BACKEND_OGL);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "GFXBackend", backend);

        // SIDevice routing: 12 = WIIU_ADAPTER, 6 = emulated GC pad (which
        // reads from our Touchscreen-via-ButtonManager input bus).
        boolean hasAdapter = hasWiiUAdapter();
        String port0 = hasAdapter ? "12" : "6";
        String portN = hasAdapter ? "12" : "0";
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice0", port0);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice1", portN);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice2", portN);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice3", portN);
    }

    private boolean hasWiiUAdapter() {
        UsbManager m = (UsbManager) getSystemService(USB_SERVICE);
        if (m == null) return false;
        for (UsbDevice d : m.getDeviceList().values()) {
            if (d.getVendorId() == 0x057E && d.getProductId() == 0x0337) return true;
        }
        return false;
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
}
