package org.dolphinemu.dolphinemu.utils;

public final class GCAdapter {
    public static final byte[] controllerPayload = new byte[37];

    private GCAdapter() {}

    public static boolean isUsbDeviceAvailable() {
        return Java_GCAdapter.QueryAdapter();
    }

    public static boolean openAdapter() {
        return Java_GCAdapter.OpenAdapter();
    }

    public static int input() {
        int size = Java_GCAdapter.Input();
        System.arraycopy(Java_GCAdapter.controller_payload, 0,
                controllerPayload, 0, Math.min(controllerPayload.length,
                        Java_GCAdapter.controller_payload.length));
        return size;
    }

    public static int output(byte[] rumble) {
        return Java_GCAdapter.Output(rumble);
    }

    public static int getFd() {
        return Java_GCAdapter.GetFD();
    }

    public static void enableHotplugCallback() {}

    public static void disableHotplugCallback() {}
}
