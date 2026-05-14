package org.dolphinemu.dolphinemu;

public enum EmulatorCore {
    MAINLINE("mainline"),
    ISHIIRUKA("ishiiruka");

    public static final String PREF_KEY = "emulator_core";
    public static final EmulatorCore DEFAULT = MAINLINE;
    public final String prefValue;

    EmulatorCore(String prefValue) {
        this.prefValue = prefValue;
    }

    public static EmulatorCore fromPref(String value) {
        if (MAINLINE.prefValue.equals(value)) return MAINLINE;
        if (ISHIIRUKA.prefValue.equals(value)) return ISHIIRUKA;
        return DEFAULT;
    }
}
