package org.dolphinemu.dolphinemu;

public enum EmulatorCore {
    ISHIIRUKA("ishiiruka"),
    MAINLINE("mainline");

    public static final String PREF_KEY = "emulator_core";
    public final String prefValue;

    EmulatorCore(String prefValue) {
        this.prefValue = prefValue;
    }

    public static EmulatorCore fromPref(String value) {
        if (MAINLINE.prefValue.equals(value)) return MAINLINE;
        return ISHIIRUKA;
    }
}
