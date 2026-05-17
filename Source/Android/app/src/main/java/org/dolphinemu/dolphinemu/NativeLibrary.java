// Copyright 2013 Dolphin Emulator Project / Slippi
// Licensed under GPLv2+

package org.dolphinemu.dolphinemu;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import org.dolphinemu.dolphinemu.activities.EmulationActivity;
import org.dolphinemu.dolphinemu.activities.MainlineEmulationActivity;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

/**
 * JNI bridge to the native Dolphin/Slippi core. Method signatures here must
 * stay in lockstep with Source/Android/jni/MainAndroid.cpp.
 */
public final class NativeLibrary {
    private static final String TAG = "DolphinJNI";
    public static volatile EmulationActivity sEmulationActivity;
    public static final String TouchScreenDevice = "Touchscreen";
    private static final Semaphore sAlertSemaphore = new Semaphore(0);
    private static volatile boolean sNativeLibraryLoaded;
    private static volatile boolean sShowingAlertMessage;
    private static volatile WeakReference<Activity> sCurrentActivity =
            new WeakReference<>(null);

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
     * Blocks in native evdev until a raw-axis event is available or the
     * timeout expires, then returns the latest normalized axes. This lets the
     * input thread sleep in poll(2) instead of waking every millisecond.
     */
    public static native float[] WaitRawGamepadAxes(int timeoutMs);

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
    public static native long GetPadOverrideAgeUs(int port);

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

    /** Bounded native controller/input trace included in support diagnostics. */
    public static native String GetInputDiagnosticsLog();

    public static native String GetConfig(String configFile, String section, String key, String defaultValue);
    public static native void SetConfig(String configFile, String section, String key, String value);
    public static native void SetMeleeForceWidescreen(boolean enabled);
    public static native void SetEXIDeviceOverride(int slot, int device);
    public static native void ClearEXIDeviceOverrides();
    public static native int ApplyXdeltaPatch(String sourcePath, String patchPath, String outputPath);

    public static final int EXI_DEVICE_MEMORYCARD = 1;
    public static final int EXI_DEVICE_SLIPPI = 10;
    public static final int EXI_DEVICE_NONE = 255;

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
    public static native void SetCacheDirectory(String directory);
    public static native String GetCacheDirectory();
    public static native void Initialize();
    public static native String GetGitRevision();
    public static native void UpdateGCAdapterScanThread();

    /**
     * Point SlippiReplayComm at a JSON playback config file. Must be
     * called BEFORE Run(); the C++ side reads it once during CEXISlippi
     * construction. Pass an empty string to neutralize a stale config
     * left behind by a prior replay launch.
     */
    public static native void SetSlippiInputPath(String path);
    public static native void ClearSlippiInputPath();

    /** Latest frame index the parser has produced, or {@link Integer#MIN_VALUE} if no replay is active. */
    public static native int GetReplayLatestFrame();
    /** Current playback frame, or {@link Integer#MIN_VALUE} before the seek thread has started. */
    public static native int GetReplayCurrentFrame();
    /** Ask the seek thread to jump to a specific frame. Latest wins. */
    public static native void SetReplayTargetFrame(int frame);
    /** Discrete ±5s nudge in playback time. */
    public static native void SetReplayJump(boolean forward);
    /** 0 = normal, 1 = hard fast-forward (~4×). */
    public static native void SetReplaySpeedMode(int mode);

    public static native void Run();
    public static native void Run(String[] paths, boolean riivolution);
    public static native void Run(
            String[] paths, boolean riivolution, String savestatePath, boolean deleteSavestate);
    public static native void SurfaceChanged(Surface surface);
    public static native void SurfaceDestroyed();
    public static native boolean HasSurface();
    public static native void UnPauseEmulation();
    public static native void PauseEmulation();
    public static native void PauseEmulation(boolean overrideAchievementRestrictions);
    public static native void StopEmulation();
    public static native void SetIsBooting();
    public static native boolean IsRunning();
    public static native boolean IsRunningAndUnpaused();
    public static native boolean IsUninitialized();
    public static native void SetProfiling(boolean enable);
    public static native void WriteProfileResults();
    public static native void eglBindAPI(int api);
    public static native void SaveState(int slot, boolean wait);
    public static native void SaveStateAs(String path, boolean wait);
    public static native void LoadStateAs(String path);
    public static native float GetGameAspectRatio();
    /** Linux TID of the running emulation thread, or 0 if not started. */
    public static native int GetEmuThreadTid();
    private static native void CacheClassesAndMethods();

    static {
        boolean mainlineProcess = isMainlineProcess();
        try {
            System.loadLibrary(mainlineProcess ? MainlineCore.LIBRARY_NAME : "main");
            sNativeLibraryLoaded = true;
        } catch (UnsatisfiedLinkError ex) {
            Log.e(TAG, "Failed to load native library: " + ex);
        }
        if (!mainlineProcess && sNativeLibraryLoaded) {
            CacheClassesAndMethods();
        }
    }

    public static void displayAlertMsg(final String alert) {
        Log.e(TAG, "Native alert: " + alert);
        final EmulationActivity activity = sEmulationActivity;
        if (activity != null) {
            activity.showToast(alert);
        }
    }

    public static void displayToastMsg(final String text, final boolean longLength) {
        final Activity activity = sCurrentActivity.get();
        final android.content.Context context = activity != null
                ? activity : DolphinApplication.getAppContext();
        if (context == null) {
            Log.i(TAG, "Native toast with no context: " + text);
            return;
        }
        final int length = longLength ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT;
        if (activity != null) {
            activity.runOnUiThread(() -> Toast.makeText(context, text, length).show());
        } else {
            Toast.makeText(context, text, length).show();
        }
    }

    public static boolean displayAlertMsg(
            String caption, String text, boolean yesNo, boolean isWarning, boolean nonBlocking) {
        Log.e(TAG, "Native alert: " + caption + ": " + text);
        displayToastMsg(text, true);
        sShowingAlertMessage = false;
        return false;
    }

    public static boolean IsShowingAlertMessage() {
        return sShowingAlertMessage;
    }

    public static void NotifyAlertMessageLock() {
        sAlertSemaphore.release();
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
        sCurrentActivity = new WeakReference<>(activity);
    }

    public static void setMainlineEmulationActivity(MainlineEmulationActivity activity) {
        sCurrentActivity = new WeakReference<>(activity);
    }

    public static void clearEmulationActivity() {
        sCurrentActivity.clear();
        sEmulationActivity = null;
    }

    public static void finishEmulationActivity() {
        final Activity activity = sCurrentActivity.get();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    activity.finish();
                }
            });
        }
    }

    public static void updateTouchPointer() {
        final Activity activity = sCurrentActivity.get();
        if (activity instanceof MainlineEmulationActivity) {
            ((MainlineEmulationActivity) activity).initInputPointer();
        }
    }

    public static void onTitleChanged() {
        final Activity activity = sCurrentActivity.get();
        if (activity instanceof MainlineEmulationActivity) {
            ((MainlineEmulationActivity) activity).onTitleChangedFromNative();
        }
    }

    public static void updateEmulationLaunchProgress(String message) {
        final Activity activity = sCurrentActivity.get();
        if (activity instanceof EmulationActivity) {
            ((EmulationActivity) activity).onLaunchProgressFromNative(message);
        } else if (activity instanceof MainlineEmulationActivity) {
            ((MainlineEmulationActivity) activity).onLaunchProgressFromNative(message);
        }
    }

    public static float getRenderSurfaceScale() {
        final Activity activity = sCurrentActivity.get();
        return activity == null
                ? android.content.res.Resources.getSystem().getDisplayMetrics().scaledDensity
                : activity.getResources().getDisplayMetrics().scaledDensity;
    }

    public static boolean isNativeLibraryLoaded() {
        return sNativeLibraryLoaded;
    }

    private static boolean isMainlineProcess() {
        String processName = currentProcessName();
        return processName != null && processName.endsWith(MainlineCore.PROCESS_SUFFIX);
    }

    private static String currentProcessName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] buffer = new byte[256];
            int count = in.read(buffer);
            if (count <= 0) return null;
            int end = 0;
            while (end < count && buffer[end] != 0) end++;
            return new String(buffer, 0, end, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }
}
