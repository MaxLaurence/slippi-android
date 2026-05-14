package org.dolphinemu.dolphinemu.utils;

public final class WiimoteAdapter {
    public static final byte[][] wiimotePayload = new byte[4][23];

    private WiimoteAdapter() {}

    public static boolean queryAdapter() {
        return false;
    }

    public static boolean openAdapter() {
        return false;
    }

    public static int input(int index) {
        return 0;
    }

    public static int output(int index, byte[] payload, int size) {
        return 0;
    }
}
