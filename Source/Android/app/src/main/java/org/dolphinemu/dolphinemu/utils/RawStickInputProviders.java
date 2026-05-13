package org.dolphinemu.dolphinemu.utils;

import android.content.Context;
import android.os.Build;

import java.util.Arrays;
import java.util.List;

/**
 * Provider selection for built-in handheld controls. Android's public
 * MotionEvent path is intentionally not represented here because those axes
 * may already be normalized/clamped by the system.
 */
public final class RawStickInputProviders {
    private static final String TAG = "SlippiEmu";

    private RawStickInputProviders() {
    }

    public static RawStickInputProvider create(Context context) {
        List<RawStickInputProvider> providers = Arrays.asList(
                OdinMappingInput.getInstance(context),
                new NativeEvdevStickInputProvider(),
                new KnownUnsupportedHandheldProvider("retroid", "Retroid handheld raw provider"),
                new KnownUnsupportedHandheldProvider("ayaneo", "AYANEO handheld raw provider"),
                new KnownUnsupportedHandheldProvider("anbernic", "Anbernic handheld raw provider"),
                new KnownUnsupportedHandheldProvider("gpd", "GPD handheld raw provider"));

        for (RawStickInputProvider provider : providers) {
            if (provider.isAvailable()) {
                android.util.Log.i(TAG, "raw stick provider selected: "
                        + provider.id() + " (" + provider.label() + ")");
                return provider;
            }
        }

        logKnownUnsupportedDevice();
        return null;
    }

    private static void logKnownUnsupportedDevice() {
        String fingerprint = (Build.MANUFACTURER + " "
                + Build.BRAND + " "
                + Build.MODEL + " "
                + Build.DEVICE + " "
                + Build.PRODUCT).toLowerCase();

        if (fingerprint.contains("retroid")
                || fingerprint.contains("ayaneo")
                || fingerprint.contains("anbernic")
                || fingerprint.contains("gpd")) {
            android.util.Log.i(TAG, "no known raw stick provider for this handheld; "
                    + "using Android MotionEvent fallback");
        }
    }
}
