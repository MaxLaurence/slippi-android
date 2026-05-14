package org.dolphinemu.dolphinemu.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import org.dolphinemu.dolphinemu.DolphinApplication;

public final class Analytics {
    private static final String DEVICE_MANUFACTURER = "DEVICE_MANUFACTURER";
    private static final String DEVICE_OS = "DEVICE_OS";
    private static final String DEVICE_MODEL = "DEVICE_MODEL";
    private static final String DEVICE_TYPE = "DEVICE_TYPE";

    private Analytics() {}

    public static void sendReport(String endpoint, byte[] data) {
        Log.debug("Mainline analytics report ignored");
    }

    public static String getValue(String key) {
        if (key == null) {
            return "";
        }

        switch (key) {
            case DEVICE_MODEL:
                return Build.MODEL;
            case DEVICE_MANUFACTURER:
                return Build.MANUFACTURER;
            case DEVICE_OS:
                return Integer.toString(Build.VERSION.SDK_INT);
            case DEVICE_TYPE:
                return isLeanback() ? "android-tv" : "android-mobile";
            default:
                return "";
        }
    }

    private static boolean isLeanback() {
        Context context = DolphinApplication.getAppContext();
        return context != null && context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
}
