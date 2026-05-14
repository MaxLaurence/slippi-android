package org.dolphinemu.dolphinemu.utils;

public final class Log {
    private static final String TAG = "MainlineCompat";

    private Log() {}

    public static void debug(String message) {
        android.util.Log.d(TAG, message);
    }

    public static void info(String message) {
        android.util.Log.i(TAG, message);
    }

    public static void warning(String message) {
        android.util.Log.w(TAG, message);
    }

    public static void error(String message) {
        android.util.Log.e(TAG, message);
    }
}
