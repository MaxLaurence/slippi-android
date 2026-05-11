package org.dolphinemu.dolphinemu.utils;

/**
 * Stub for the Wiimote USB host stack — same story as Java_GCAdapter:
 * the C++ side does FindClass(...) on this exact path. We need the
 * class to exist with matching method signatures; we don't need to
 * implement them because Slippi/Melee doesn't use Wiimotes.
 */
public final class Java_WiimoteAdapter {
    public static boolean QueryAdapter() { return false; }
    public static boolean OpenAdapter() { return false; }
    public static int Input(int index) { return 0; }
    public static int Output(int index, byte[] payload, int size) { return 0; }
}
