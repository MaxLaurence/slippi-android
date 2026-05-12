package org.dolphinemu.dolphinemu.utils;

import android.view.InputDevice;

import org.dolphinemu.dolphinemu.NativeLibrary;

public final class PhysicalControllerDetector {
    private PhysicalControllerDetector() {
    }

    public static boolean hasUsableP1Controller(RawStickInputProvider rawStickInput) {
        if (rawStickInput != null) return true;
        if (isAdapterPortConnected(0)) return true;
        return hasAndroidGamepad();
    }

    private static boolean isAdapterPortConnected(int port) {
        try {
            return NativeLibrary.IsGCAdapterPortConnected(port);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasAndroidGamepad() {
        int[] ids = InputDevice.getDeviceIds();
        for (int id : ids) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
            if (joystick || gamepad) return true;
        }
        return false;
    }
}
