package org.dolphinemu.dolphinemu.features.settings.model;

public final class NativeConfig {
    public static final int LAYER_BASE_OR_CURRENT = 0;
    public static final int LAYER_BASE = 1;
    public static final int LAYER_LOCAL_GAME = 2;
    public static final int LAYER_ACTIVE = 3;
    public static final int LAYER_CURRENT = 4;

    private NativeConfig() {}

    public static native void save(int layer);

    public static native void setString(
            int layer, String file, String section, String key, String value);

    public static native void setBoolean(
            int layer, String file, String section, String key, boolean value);

    public static native void setInt(
            int layer, String file, String section, String key, int value);
}
