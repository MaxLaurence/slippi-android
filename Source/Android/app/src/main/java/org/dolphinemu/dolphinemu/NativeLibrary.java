// Copyright 2013 Dolphin Emulator Project / Slippi
// Licensed under GPLv2+

package org.dolphinemu.dolphinemu;

import android.util.Log;
import android.view.Surface;

import org.dolphinemu.dolphinemu.activities.EmulationActivity;

/**
 * JNI bridge to the native Dolphin/Slippi core. Method signatures here must
 * stay in lockstep with Source/Android/jni/MainAndroid.cpp.
 */
public final class NativeLibrary {
    private static final String TAG = "DolphinJNI";
    public static volatile EmulationActivity sEmulationActivity;
    public static final String TouchScreenDevice = "Touchscreen";

    public static final class ButtonType {
        public static final int BUTTON_A = 0;
        public static final int BUTTON_B = 1;
        public static final int BUTTON_START = 2;
        public static final int BUTTON_X = 3;
        public static final int BUTTON_Y = 4;
        public static final int BUTTON_Z = 5;
        public static final int BUTTON_UP = 6;
        public static final int BUTTON_DOWN = 7;
        public static final int BUTTON_LEFT = 8;
        public static final int BUTTON_RIGHT = 9;
        public static final int STICK_MAIN = 10;
        public static final int STICK_MAIN_UP = 11;
        public static final int STICK_MAIN_DOWN = 12;
        public static final int STICK_MAIN_LEFT = 13;
        public static final int STICK_MAIN_RIGHT = 14;
        public static final int STICK_C = 15;
        public static final int STICK_C_UP = 16;
        public static final int STICK_C_DOWN = 17;
        public static final int STICK_C_LEFT = 18;
        public static final int STICK_C_RIGHT = 19;
        public static final int TRIGGER_L = 20;
        public static final int TRIGGER_R = 21;
    }

    public static final class ButtonState {
        public static final int RELEASED = 0;
        public static final int PRESSED = 1;
    }

    public static native boolean onGamePadEvent(String device, int button, int action);
    public static native void onGamePadMoveEvent(String device, int axis, float value);

    public static native String GetConfig(String configFile, String section, String key, String defaultValue);
    public static native void SetConfig(String configFile, String section, String key, String value);

    public static native void SetFilename(String filename);
    public static native int[] GetBanner(String filename);
    public static native String GetTitle(String filename);
    public static native String GetDescription(String filename);
    public static native String GetGameId(String filename);
    public static native int GetCountry(String filename);
    public static native String GetCompany(String filename);
    public static native long GetFilesize(String filename);
    public static native int GetPlatform(String filename);
    public static native String GetVersionString();
    public static native void SaveScreenShot();
    public static native void SaveState(int slot);
    public static native void LoadState(int slot);

    public static native void CreateUserFolders();
    public static native void SetUserDirectory(String directory);
    public static native String GetUserDirectory();

    public static native void Run();
    public static native void SurfaceChanged(Surface surface);
    public static native void SurfaceDestroyed();
    public static native void UnPauseEmulation();
    public static native void PauseEmulation();
    public static native void StopEmulation();
    public static native void SetProfiling(boolean enable);
    public static native void WriteProfileResults();
    public static native void eglBindAPI(int api);
    private static native void CacheClassesAndMethods();

    static {
        try {
            System.loadLibrary("main");
        } catch (UnsatisfiedLinkError ex) {
            Log.e(TAG, "Failed to load native library: " + ex);
        }
        CacheClassesAndMethods();
    }

    public static void displayAlertMsg(final String alert) {
        Log.e(TAG, "Native alert: " + alert);
        final EmulationActivity activity = sEmulationActivity;
        if (activity != null) {
            activity.showToast(alert);
        }
    }

    public static void endEmulationActivity() {
        Log.i(TAG, "Native requested end of emulation.");
        final EmulationActivity activity = sEmulationActivity;
        if (activity != null) {
            activity.finishFromNative();
        }
    }

    public static void setEmulationActivity(EmulationActivity activity) {
        sEmulationActivity = activity;
    }
}
