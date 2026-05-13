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

    /**
     * Deprecated no-op kept for JNI compatibility. Physical GC adapter sticks
     * pass through unchanged; app-level calibration only applies to Android/HID
     * controller input.
     */
    public static native void SetGCAdapterStickCalibration(
            int port, int stickIdx,
            float centerX, float centerY,
            float scaleXPos, float scaleXNeg,
            float scaleYPos, float scaleYNeg,
            float deadzone, float sensitivity);

    /**
     * Returns the latest raw stick bytes from a given GC adapter port.
     * stickIdx 0 = main, 1 = C.
     * Returns null if the adapter isn't connected on that port.
     * Each element is in 0..255 (128 is center).
     */
    public static native int[] GetRawAdapterStick(int port, int stickIdx);

    /**
     * Configure per-port button remap for the GC adapter. sourceBit is
     * one of the PAD_BUTTON_* / PAD_TRIGGER_* bitmask constants (the
     * bit the adapter would normally emit when the user presses a
     * specific physical button on a controller plugged into this port);
     * targetBit is the bitmask we emit to the game in its place. The
     * map is per-port so each adapter slot (i.e. each player in a
     * 4-player game) can have its own bindings.
     */
    public static native void SetGCAdapterButtonMap(int port, int sourceBit, int targetBit);

    /** Restore the given adapter port to identity (no remap). */
    public static native void ClearGCAdapterButtonMap(int port);

    /**
     * Latest pressed-buttons bitmask on the given adapter port BEFORE
     * the remap. Returns 0 if no controller is connected. Used by the
     * remap wizard to detect which physical button the user just
     * pressed on an adapter controller.
     */
    public static native int GetGCAdapterButtonsRaw(int port);

    /** Returns whether the native GC adapter reader currently sees a pad on this port. */
    public static native boolean IsGCAdapterPortConnected(int port);

    /**
     * Polls the built-in controller through Linux evdev when the app can read
     * /dev/input directly. Returns normalized raw axes:
     *   [mainX, mainY, cStickX, cStickY]
     * or null when no readable raw gamepad device is available. This bypasses
     * Android's MotionEvent joystick normalization, which can saturate at
     * ±1.0 before the stick reaches its physical gate.
     */
    public static native float[] PollRawGamepadAxes();

    /**
     * Push an already-calibrated GC controller state into the SI
     * pipeline. Bypasses ControllerEmu entirely — values land directly
     * in SI_DeviceGCController::GetPadStatus. Use this whenever the
     * launcher applies its own calibration before the bytes reach the
     * emulator (i.e. the on-device pad path). All byte fields are in
     * GC controller byte space: sticks 0..255 (128=center), triggers
     * 0..255, button is a PAD_BUTTON_* / PAD_TRIGGER_* bitmask.
     */
    public static native void SetPadOverride(
            int port, int button,
            int stickX, int stickY, int substickX, int substickY,
            int triggerL, int triggerR, int analogA, int analogB);

    public static native void ClearPadOverride(int port);

    /**
     * Write calibrated stick floats DIRECTLY into Melee's per-port
     * HSDPad struct in PPC memory, bypassing the entire SI / Movie /
     * ControllerEmu pipeline. Each value is in -1..+1 (Melee's native
     * float-stick format — center is 0, max magnitude 1).
     * No-op when Core isn't running. Call once per input event.
     */
    public static native void SetMeleePadFloats(
            int port, float stickX, float stickY,
            float substickX, float substickY);

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
    /** Linux TID of the running emulation thread, or 0 if not started. */
    public static native int GetEmuThreadTid();
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
