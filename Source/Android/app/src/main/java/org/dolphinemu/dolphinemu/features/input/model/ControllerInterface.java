package org.dolphinemu.dolphinemu.features.input.model;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Vibrator;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.dolphinemu.dolphinemu.DolphinApplication;

public final class ControllerInterface {
    private static HandlerThread sHotplugThread;
    private static InputManager.InputDeviceListener sInputDeviceListener;

    private ControllerInterface() {}

    public static native boolean dispatchKeyEvent(KeyEvent event);

    public static native boolean dispatchGenericMotionEvent(MotionEvent event);

    private static native boolean dispatchSensorEvent(
            String deviceQualifier, String axisName, float value);

    public static native void notifySensorSuspendedState(
            String deviceQualifier, String[] axisNames, boolean suspended);

    public static native void refreshDevices();

    public static native String[] getAllDeviceStrings();

    public static native CoreDevice getDevice(String deviceString);

    private static void onDevicesChanged() {
        // Called from native after devices have already been refreshed.
    }

    private static void registerInputDeviceListener() {
        Context context = DolphinApplication.getAppContext();
        if (context == null || sInputDeviceListener != null) return;
        InputManager inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        if (inputManager == null) return;
        sHotplugThread = new HandlerThread("MainlineHotplug");
        sHotplugThread.start();
        sInputDeviceListener = new InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int deviceId) { onDevicesChanged(); }
            @Override public void onInputDeviceRemoved(int deviceId) { onDevicesChanged(); }
            @Override public void onInputDeviceChanged(int deviceId) { onDevicesChanged(); }
        };
        inputManager.registerInputDeviceListener(
                sInputDeviceListener, new Handler(sHotplugThread.getLooper()));
    }

    private static void unregisterInputDeviceListener() {
        Context context = DolphinApplication.getAppContext();
        if (context != null && sInputDeviceListener != null) {
            InputManager inputManager =
                    (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (inputManager != null) {
                inputManager.unregisterInputDeviceListener(sInputDeviceListener);
            }
        }
        sInputDeviceListener = null;
        if (sHotplugThread != null) {
            sHotplugThread.quitSafely();
            sHotplugThread = null;
        }
    }

    private static DolphinVibratorManager getVibratorManager(InputDevice device) {
        return new DolphinVibratorManager();
    }

    private static DolphinVibratorManager getSystemVibratorManager() {
        return new DolphinVibratorManager();
    }

    private static void vibrate(Vibrator vibrator) {
        if (vibrator != null) {
            vibrator.vibrate(100);
        }
    }
}
