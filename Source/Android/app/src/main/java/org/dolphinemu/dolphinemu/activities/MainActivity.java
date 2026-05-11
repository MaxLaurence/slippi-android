package org.dolphinemu.dolphinemu.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Minimal launcher: pick an ISO, drop in a Slippi user.json, hit Play.
 *
 * Everything is intentionally low-fi — the heavy lifting happens in the
 * C++/Rust core. This is the dial that turns it on.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREF_KEY_ISO_URI = "iso_uri";

    private TextView isoLabel;
    private TextView userJsonLabel;

    private final ActivityResultLauncher<String[]> pickIso =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(uri, flags);
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
        isoLabel = findViewById(R.id.iso_label);
        userJsonLabel = findViewById(R.id.user_json_label);

        UserDirectoryBootstrap.ensureLayout(this);
        NativeLibrary.SetUserDirectory(UserDirectoryBootstrap.userDir(this).getAbsolutePath());
        NativeLibrary.CreateUserFolders();

        findViewById(R.id.pick_iso).setOnClickListener(v -> pickIso.launch(new String[]{"*/*"}));
        findViewById(R.id.pick_user_json).setOnClickListener(v -> pickUserJson.launch(new String[]{"application/json", "*/*"}));
        findViewById(R.id.play).setOnClickListener(v -> launchEmulation());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        String isoPath = prefs().getString(PREF_KEY_ISO_URI, null);
        File iso = isoPath != null ? new File(isoPath) : null;
        isoLabel.setText(iso != null && iso.exists()
                ? "ISO: " + iso.getName() + " (" + (iso.length() >> 20) + " MiB)"
                : "ISO: not selected");
        File u = UserDirectoryBootstrap.slippiUserJson(this);
        userJsonLabel.setText(u.exists()
                ? "user.json: " + (u.length()) + " bytes"
                : "user.json: not provided (offline play only)");
    }

    private void launchEmulation() {
        String isoPath = prefs().getString(PREF_KEY_ISO_URI, null);
        if (TextUtils.isEmpty(isoPath) || !new File(isoPath).exists()) {
            toast("Pick an ISO first");
            return;
        }
        applySiDevices(hasWiiUAdapter());
        Intent it = new Intent(this, EmulationActivity.class);
        it.putExtra(EmulationActivity.EXTRA_ISO_PATH, isoPath);
        startActivity(it);
    }

    private boolean hasWiiUAdapter() {
        android.hardware.usb.UsbManager m =
                (android.hardware.usb.UsbManager) getSystemService(USB_SERVICE);
        if (m == null) return false;
        for (android.hardware.usb.UsbDevice d : m.getDeviceList().values()) {
            if (d.getVendorId() == 0x057E && d.getProductId() == 0x0337) return true;
        }
        return false;
    }

    /**
     * Rewrite Dolphin.ini's SIDevice slots before booting so the C++ core sees
     * the right input source. SIDEVICE_WIIU_ADAPTER (12) makes Port N read from
     * the USB GC adapter; SIDEVICE_GC_CONTROLLER (6) routes through GCPadEmu
     * (and through there into the Thor's onboard buttons via Touchscreen).
     */
    private void applySiDevices(boolean hasAdapter) {
        String port0 = hasAdapter ? "12" : "6";
        String portN = hasAdapter ? "12" : "0";
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice0", port0);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice1", portN);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice2", portN);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice3", portN);
        Toast.makeText(this,
                hasAdapter ? "Using GameCube USB adapter" : "Using on-screen / Thor pad",
                Toast.LENGTH_SHORT).show();
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
