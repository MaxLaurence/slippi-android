package org.dolphinemu.dolphinemu.controller;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Per-device persistent calibration record. One row per
 * (deviceKey, stick) pair. Backed by a single SharedPreferences file —
 * encryption isn't useful here (no secrets, just stick coefficients),
 * and plain prefs are atomic enough for this rate of writes (only
 * during the calibration wizard).
 *
 * Device keys:
 *   "builtin"       — stick calibration and button maps for the host
 *                     device's onboard pad / any Bluetooth pad (we
 *                     deliberately don't distinguish Bluetooth pads yet —
 *                     the typical user has exactly one)
 *   "adapter:N"     — WUP-028 GameCube adapter port N (0..3), button maps
 *                     only. Adapter stick bytes are already calibrated by
 *                     the controller hardware and are not app-calibrated.
 *
 * Stick keys: {@link Stick#MAIN}, {@link Stick#C}.
 */
public final class ControllerProfile {

    private static final String PREF_FILE = "controller_profiles";

    public enum Stick { MAIN, C }

    public static final String DEVICE_BUILTIN = "builtin";
    public static String adapterDeviceKey(int port) { return "adapter:" + port; }

    private final SharedPreferences prefs;

    public ControllerProfile(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    public StickCalibration getStick(String deviceKey, Stick stick) {
        // Per-stick defaults: the main stick wants a tiny deadzone +
        // gentle curve (it's used for walking, tilts, jumps); the
        // C-stick wants a bigger deadzone + steeper curve (it ONLY
        // fires smashes in Melee, all of which happen at high
        // magnitude, so anything below should be killed).
        StickCalibration defaults = stick == Stick.MAIN
                ? StickCalibration.defaultsForMain()
                : StickCalibration.defaultsForC();
        String prefix = keyPrefix(deviceKey, stick);
        if (!prefs.contains(prefix + ".centerX")) return defaults;
        return new StickCalibration(
                prefs.getFloat(prefix + ".centerX", defaults.centerX),
                prefs.getFloat(prefix + ".centerY", defaults.centerY),
                prefs.getFloat(prefix + ".scaleXPos", defaults.scaleXPos),
                prefs.getFloat(prefix + ".scaleXNeg", defaults.scaleXNeg),
                prefs.getFloat(prefix + ".scaleYPos", defaults.scaleYPos),
                prefs.getFloat(prefix + ".scaleYNeg", defaults.scaleYNeg),
                prefs.getFloat(prefix + ".deadzone", defaults.deadzone),
                prefs.getFloat(prefix + ".sensitivity", defaults.sensitivity),
                prefs.getFloat(prefix + ".outputCap", defaults.outputCap),
                prefs.getBoolean(prefix + ".useOuterScale", defaults.useOuterScale));
    }

    public void putStick(String deviceKey, Stick stick, StickCalibration cal) {
        String prefix = keyPrefix(deviceKey, stick);
        prefs.edit()
                .putFloat(prefix + ".centerX", cal.centerX)
                .putFloat(prefix + ".centerY", cal.centerY)
                .putFloat(prefix + ".scaleXPos", cal.scaleXPos)
                .putFloat(prefix + ".scaleXNeg", cal.scaleXNeg)
                .putFloat(prefix + ".scaleYPos", cal.scaleYPos)
                .putFloat(prefix + ".scaleYNeg", cal.scaleYNeg)
                .putFloat(prefix + ".deadzone", cal.deadzone)
                .putFloat(prefix + ".sensitivity", cal.sensitivity)
                .putFloat(prefix + ".outputCap", cal.outputCap)
                .putBoolean(prefix + ".useOuterScale", cal.useOuterScale)
                .apply();
    }

    public void clearStick(String deviceKey, Stick stick) {
        String prefix = keyPrefix(deviceKey, stick);
        prefs.edit()
                .remove(prefix + ".centerX")
                .remove(prefix + ".centerY")
                .remove(prefix + ".scaleXPos")
                .remove(prefix + ".scaleXNeg")
                .remove(prefix + ".scaleYPos")
                .remove(prefix + ".scaleYNeg")
                .remove(prefix + ".deadzone")
                .remove(prefix + ".sensitivity")
                .remove(prefix + ".outputCap")
                .remove(prefix + ".useOuterScale")
                .apply();
    }

    public boolean hasCalibration(String deviceKey, Stick stick) {
        return prefs.contains(keyPrefix(deviceKey, stick) + ".centerX");
    }

    private static String keyPrefix(String deviceKey, Stick stick) {
        return deviceKey + "/" + stick.name();
    }

    // ─── Button map persistence ────────────────────────────────────
    //
    // Stored as a single serialized string per device so loading + saving
    // is atomic — we never observe a half-written remap, even mid-edit.
    // Absence of the key means "use defaults"; an empty string means
    // "user explicitly cleared all bindings".

    private static String buttonMapPrefKey(String deviceKey) {
        return deviceKey + "/buttonMap";
    }

    public ButtonMap getButtonMap(String deviceKey) {
        String prefKey = buttonMapPrefKey(deviceKey);
        if (!prefs.contains(prefKey)) return ButtonMap.defaults();
        return ButtonMap.deserialize(prefs.getString(prefKey, ""));
    }

    public void putButtonMap(String deviceKey, ButtonMap map) {
        prefs.edit().putString(buttonMapPrefKey(deviceKey), map.serialize()).apply();
    }

    public void clearButtonMap(String deviceKey) {
        prefs.edit().remove(buttonMapPrefKey(deviceKey)).apply();
    }

    public boolean hasButtonMap(String deviceKey) {
        return prefs.contains(buttonMapPrefKey(deviceKey));
    }
}
