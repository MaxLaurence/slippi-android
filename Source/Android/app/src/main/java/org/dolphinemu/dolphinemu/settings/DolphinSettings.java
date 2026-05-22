package org.dolphinemu.dolphinemu.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;

public final class DolphinSettings {
    private static final String TAG = "DolphinSettings";
    public static final String PREF_KEY_BACKEND = "backend";
    public static final String PREF_KEY_AUDIO_BACKEND = "audio_backend";
    public static final String PREF_KEY_AUDIO_BUFFER_BURSTS = "audio_buffer_bursts";
    public static final String PREF_KEY_DISPLAY_LATENCY_MODE = "display_latency_mode";
    public static final String PREF_KEY_SMOOTH_NETPLAY = "smooth_netplay";
    public static final String PREF_KEY_GFX_ASPECT_RATIO = "gfx_aspect_ratio";
    public static final String PREF_KEY_EFB_SCALE = "gfx_efb_scale";
    public static final String PREF_KEY_WIDESCREEN_HACK = "gfx_widescreen_hack";
    public static final String PREF_KEY_SHOW_FPS = "gfx_show_fps";
    public static final String PREF_KEY_SHOW_NETPLAY_PING = "gfx_show_netplay_ping";
    public static final String PREF_KEY_TOUCH_CONTROLS_MODE = "touch_controls_mode";
    private static final String PREF_KEY_FORCE_TOUCH_CONTROLS_LEGACY = "force_touch_controls";

    public static final String BACKEND_VULKAN = "Vulkan";
    public static final String BACKEND_OGL = "OGL";
    public static final String TOUCH_CONTROLS_AUTO = "auto";
    public static final String TOUCH_CONTROLS_ALWAYS_ON = "always_on";
    public static final String TOUCH_CONTROLS_OFF = "off";
    public static final String DISPLAY_LATENCY_SMOOTH = "smooth";
    public static final String DISPLAY_LATENCY_FASTEST = "fastest";
    public static final String AUDIO_BACKEND_OBOE = "Oboe";
    public static final String AUDIO_BACKEND_AAUDIO = "AAudio";
    public static final String AUDIO_BACKEND_OPENSLES = "OpenSLES";

    public static final int AUDIO_BURSTS_ULTRA_LOW = 1;
    public static final int AUDIO_BURSTS_LOW = 2;
    public static final int AUDIO_BURSTS_BALANCED = 4;
    public static final int AUDIO_BURSTS_STABLE = 8;

    public static final int ASPECT_AUTO = 0;
    public static final int ASPECT_STRETCH = 3;
    public static final int ASPECT_4_3 = 4;
    public static final int ASPECT_MELEE = 5;
    public static final int ASPECT_16_9 = 6;
    public static final int EFB_AUTO_INTEGRAL = 2;
    public static final int EFB_1X = 3;
    public static final int EFB_2X = 5;
    public static final int EFB_3X = 7;
    public static final int EFB_4X = 8;

    private static final int MAINLINE_ASPECT_AUTO = 0;
    private static final int MAINLINE_ASPECT_FORCE_WIDE = 1;
    private static final int MAINLINE_ASPECT_FORCE_STANDARD = 2;
    private static final int MAINLINE_ASPECT_FORCE_MELEE = 3;
    private static final int MAINLINE_ASPECT_STRETCH = 4;

    public static final AudioPreset[] AUDIO_PRESETS = {
            new AudioPreset(AUDIO_BACKEND_OBOE, AUDIO_BURSTS_ULTRA_LOW,
                    R.string.audio_preset_oboe_ultra_low),
            new AudioPreset(AUDIO_BACKEND_OBOE, AUDIO_BURSTS_LOW,
                    R.string.audio_preset_oboe_low),
            new AudioPreset(AUDIO_BACKEND_OBOE, AUDIO_BURSTS_BALANCED,
                    R.string.audio_preset_oboe_balanced),
            new AudioPreset(AUDIO_BACKEND_OBOE, AUDIO_BURSTS_STABLE,
                    R.string.audio_preset_oboe_stable),
            new AudioPreset(AUDIO_BACKEND_OPENSLES, AUDIO_BURSTS_BALANCED,
                    R.string.audio_preset_opensles_balanced),
            new AudioPreset(AUDIO_BACKEND_OPENSLES, AUDIO_BURSTS_STABLE,
                    R.string.audio_preset_opensles_stable),
            new AudioPreset(AUDIO_BACKEND_AAUDIO, AUDIO_BURSTS_BALANCED,
                    R.string.audio_preset_aaudio_balanced),
            new AudioPreset(AUDIO_BACKEND_AAUDIO, AUDIO_BURSTS_ULTRA_LOW,
                    R.string.audio_preset_aaudio_ultra_low),
    };

    public static final DisplayLatencyMode[] DISPLAY_LATENCY_MODES = {
            new DisplayLatencyMode(DISPLAY_LATENCY_SMOOTH, R.string.display_latency_smooth),
            new DisplayLatencyMode(DISPLAY_LATENCY_FASTEST, R.string.display_latency_fastest),
    };

    public static final Choice[] ASPECT_RATIOS = {
            new Choice(ASPECT_MELEE, "Melee default (73:60)"),
            new Choice(ASPECT_16_9, "16:9 display"),
            new Choice(ASPECT_AUTO, "Auto"),
            new Choice(ASPECT_4_3, "Force 4:3"),
    };

    public static final Choice[] EFB_SCALES = {
            new Choice(EFB_1X, "Native (1x)"),
            new Choice(EFB_2X, "2x"),
            new Choice(EFB_3X, "3x"),
            new Choice(EFB_4X, "4x"),
            new Choice(EFB_AUTO_INTEGRAL, "Auto integral"),
    };

    public static final TouchControlsMode[] TOUCH_CONTROLS_MODES = {
            new TouchControlsMode(TOUCH_CONTROLS_AUTO, "Auto",
                    "Hide when a built-in or Bluetooth controller is detected"),
            new TouchControlsMode(TOUCH_CONTROLS_ALWAYS_ON, "Always on",
                    "Show even when Android detects a controller"),
            new TouchControlsMode(TOUCH_CONTROLS_OFF, "Off",
                    "Never show the on-screen controller"),
    };

    private DolphinSettings() {}

    public static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static String getBackend(Context context) {
        return prefs(context).getString(PREF_KEY_BACKEND, BACKEND_VULKAN);
    }

    public static void setBackend(Context context, String backend) {
        prefs(context).edit().putString(PREF_KEY_BACKEND, sanitizeBackend(backend)).apply();
    }

    public static AudioPreset getAudioPreset(Context context) {
        SharedPreferences p = prefs(context);
        String backend = sanitizeAudioBackend(p.getString(PREF_KEY_AUDIO_BACKEND,
                AUDIO_BACKEND_OBOE));
        int bursts = p.getInt(PREF_KEY_AUDIO_BUFFER_BURSTS, AUDIO_BURSTS_BALANCED);
        AudioPreset candidate = new AudioPreset(backend, bursts, R.string.audio_preset_custom);
        for (AudioPreset preset : AUDIO_PRESETS) {
            if (preset.equals(candidate)) return preset;
        }
        return candidate;
    }

    public static void setAudioPreset(Context context, AudioPreset preset) {
        prefs(context).edit()
                .putString(PREF_KEY_AUDIO_BACKEND, sanitizeAudioBackend(preset.backend))
                .putInt(PREF_KEY_AUDIO_BUFFER_BURSTS, preset.bursts)
                .apply();
    }

    public static DisplayLatencyMode getDisplayLatencyMode(Context context) {
        String value = prefs(context).getString(PREF_KEY_DISPLAY_LATENCY_MODE,
                DISPLAY_LATENCY_SMOOTH);
        for (DisplayLatencyMode mode : DISPLAY_LATENCY_MODES) {
            if (mode.configValue.equals(value)) return mode;
        }
        prefs(context).edit()
                .putString(PREF_KEY_DISPLAY_LATENCY_MODE, DISPLAY_LATENCY_SMOOTH)
                .apply();
        return DISPLAY_LATENCY_MODES[0];
    }

    public static void setDisplayLatencyMode(Context context, DisplayLatencyMode mode) {
        prefs(context).edit()
                .putString(PREF_KEY_DISPLAY_LATENCY_MODE, mode.configValue)
                .apply();
    }

    public static boolean isSmoothNetplayEnabled(Context context) {
        return prefs(context).getBoolean(PREF_KEY_SMOOTH_NETPLAY, false);
    }

    public static void setSmoothNetplayEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_KEY_SMOOTH_NETPLAY, enabled).apply();
        applyIshiirukaSmoothNetplayConfig(context);
    }

    public static String smoothNetplayIniValue(Context context) {
        return isSmoothNetplayEnabled(context) ? "True" : "False";
    }

    public static void applyIshiirukaSmoothNetplayConfig(Context context) {
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SlippiSmoothNetplay",
                smoothNetplayIniValue(context));
    }

    public static int getAspectRatio(Context context) {
        return sanitizeAspectRatio(prefs(context).getInt(PREF_KEY_GFX_ASPECT_RATIO, ASPECT_MELEE));
    }

    public static void setAspectRatio(Context context, int value) {
        prefs(context).edit().putInt(PREF_KEY_GFX_ASPECT_RATIO, sanitizeAspectRatio(value)).apply();
    }

    public static int aspectRatioForLaunch(Context context) {
        return GameSettingsOverride.isMeleeWidescreenEnabled(context)
                ? ASPECT_16_9 : getAspectRatio(context);
    }

    public static int mainlineAspectRatioForLaunch(Context context) {
        if (GameSettingsOverride.isMeleeWidescreenEnabled(context)) {
            return MAINLINE_ASPECT_FORCE_WIDE;
        }
        switch (getAspectRatio(context)) {
            case ASPECT_16_9:
                return MAINLINE_ASPECT_FORCE_WIDE;
            case ASPECT_4_3:
                return MAINLINE_ASPECT_FORCE_STANDARD;
            case ASPECT_STRETCH:
                return MAINLINE_ASPECT_STRETCH;
            case ASPECT_MELEE:
                return MAINLINE_ASPECT_FORCE_MELEE;
            case ASPECT_AUTO:
            default:
                return MAINLINE_ASPECT_AUTO;
        }
    }

    public static int getEfbScale(Context context) {
        return prefs(context).getInt(PREF_KEY_EFB_SCALE, EFB_1X);
    }

    public static void setEfbScale(Context context, int value) {
        prefs(context).edit().putInt(PREF_KEY_EFB_SCALE, value).apply();
    }

    public static boolean isWidescreenHackEnabled(Context context) {
        return prefs(context).getBoolean(PREF_KEY_WIDESCREEN_HACK, false);
    }

    public static void setWidescreenHackEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_KEY_WIDESCREEN_HACK, enabled).apply();
    }

    public static boolean isShowFpsEnabled(Context context) {
        return prefs(context).getBoolean(PREF_KEY_SHOW_FPS, true);
    }

    public static void setShowFpsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_KEY_SHOW_FPS, enabled).apply();
    }

    public static boolean isShowNetplayPingEnabled(Context context) {
        return prefs(context).getBoolean(PREF_KEY_SHOW_NETPLAY_PING, true);
    }

    public static void setShowNetplayPingEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_KEY_SHOW_NETPLAY_PING, enabled).apply();
    }

    public static TouchControlsMode getTouchControlsMode(Context context) {
        SharedPreferences p = prefs(context);
        String value = p.getString(PREF_KEY_TOUCH_CONTROLS_MODE, null);
        if (value == null && p.getBoolean(PREF_KEY_FORCE_TOUCH_CONTROLS_LEGACY, false)) {
            return TOUCH_CONTROLS_MODES[1];
        }
        for (TouchControlsMode mode : TOUCH_CONTROLS_MODES) {
            if (mode.prefValue.equals(value)) return mode;
        }
        return TOUCH_CONTROLS_MODES[0];
    }

    public static void setTouchControlsMode(Context context, TouchControlsMode mode) {
        String value = mode == null ? TOUCH_CONTROLS_AUTO : mode.prefValue;
        prefs(context).edit()
                .putString(PREF_KEY_TOUCH_CONTROLS_MODE, sanitizeTouchControlsMode(value))
                .remove(PREF_KEY_FORCE_TOUCH_CONTROLS_LEGACY)
                .apply();
    }

    public static boolean isTouchControlsAlwaysOn(Context context) {
        return TOUCH_CONTROLS_ALWAYS_ON.equals(getTouchControlsMode(context).prefValue);
    }

    public static boolean isTouchControlsOff(Context context) {
        return TOUCH_CONTROLS_OFF.equals(getTouchControlsMode(context).prefValue);
    }

    public static String booleanIniValue(boolean enabled) {
        return enabled ? "True" : "False";
    }

    public static void setMeleeWidescreenEnabled(Context context, boolean enabled) {
        GameSettingsOverride.setMeleeWidescreenEnabled(context, enabled);
        setAspectRatio(context, enabled ? ASPECT_16_9 : ASPECT_MELEE);
        applyIshiirukaGraphicsConfig(context, false);
        GameSettingsOverride.applyLiveMode(context);
    }

    public static void applyIshiirukaGraphicsConfig(Context context) {
        // The launcher calls this before UICommon::Init constructs SConfig.
        // File writes are safe pre-boot; the live JNI flag is not.
        applyIshiirukaGraphicsConfig(context, false);
    }

    public static void applyIshiirukaGraphicsConfig(
            Context context, boolean updateNativeRuntimeState) {
        int efbScale = getEfbScale(context);
        boolean meleeForceWidescreen = GameSettingsOverride.isMeleeWidescreenEnabled(context);
        NativeLibrary.SetConfig("GFX.ini", "Settings", "AspectRatio",
                Integer.toString(aspectRatioForLaunch(context)));
        NativeLibrary.SetConfig("GFX.ini", "Settings", "EFBScale",
                Integer.toString(efbScale));
        NativeLibrary.SetConfig("GFX.ini", "Settings", "InternalResolution",
                Integer.toString(legacyInternalResolutionForEfbScale(efbScale)));
        NativeLibrary.SetConfig("GFX.ini", "Settings", "wideScreenHack",
                booleanIniValue(isWidescreenHackEnabled(context)));
        NativeLibrary.SetConfig("GFX.ini", "Settings", "ShowFPS",
                booleanIniValue(isShowFpsEnabled(context)));
        NativeLibrary.SetConfig("GFX.ini", "Settings", "ShowNetPlayPing",
                booleanIniValue(isShowNetplayPingEnabled(context)));
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "MeleeForceWidescreen",
                booleanIniValue(meleeForceWidescreen));
        if (updateNativeRuntimeState) {
            try {
                NativeLibrary.SetMeleeForceWidescreen(meleeForceWidescreen);
            } catch (Throwable t) {
                Log.w(TAG, "Could not update live Melee widescreen flag", t);
            }
        }
    }

    public static int legacyInternalResolutionForEfbScale(int efbScale) {
        if (efbScale == EFB_2X) return 2;
        if (efbScale == EFB_3X) return 3;
        if (efbScale == EFB_4X) return 4;
        if (efbScale == EFB_AUTO_INTEGRAL) return 0;
        return 1;
    }

    public static String labelForChoice(Choice[] choices, int value) {
        for (Choice choice : choices) {
            if (choice.value == value) return choice.label;
        }
        return Integer.toString(value);
    }

    public static String sanitizeBackend(String backend) {
        return BACKEND_OGL.equals(backend) ? BACKEND_OGL : BACKEND_VULKAN;
    }

    public static String sanitizeAudioBackend(String backend) {
        if (AUDIO_BACKEND_OBOE.equals(backend)
                || AUDIO_BACKEND_AAUDIO.equals(backend)
                || AUDIO_BACKEND_OPENSLES.equals(backend)) {
            return backend;
        }
        return AUDIO_BACKEND_OBOE;
    }

    private static int sanitizeAspectRatio(int value) {
        if (value == 1) return ASPECT_4_3;
        if (value == ASPECT_AUTO || value == ASPECT_STRETCH || value == ASPECT_4_3
                || value == ASPECT_MELEE || value == ASPECT_16_9) {
            return value;
        }
        return ASPECT_MELEE;
    }

    private static String sanitizeTouchControlsMode(String value) {
        if (TOUCH_CONTROLS_ALWAYS_ON.equals(value) || TOUCH_CONTROLS_OFF.equals(value)) {
            return value;
        }
        return TOUCH_CONTROLS_AUTO;
    }

    public static final class AudioPreset {
        public final String backend;
        public final int bursts;
        public final int labelResId;

        public AudioPreset(String backend, int bursts, int labelResId) {
            this.backend = backend == null ? AUDIO_BACKEND_OBOE : backend;
            this.bursts = bursts;
            this.labelResId = labelResId;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof AudioPreset)) return false;
            AudioPreset other = (AudioPreset) obj;
            return backend.equals(other.backend) && bursts == other.bursts;
        }

        @Override
        public int hashCode() {
            return 31 * backend.hashCode() + bursts;
        }
    }

    public static final class DisplayLatencyMode {
        public final String configValue;
        public final int labelResId;

        public DisplayLatencyMode(String configValue, int labelResId) {
            this.configValue = configValue;
            this.labelResId = labelResId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof DisplayLatencyMode)) return false;
            DisplayLatencyMode that = (DisplayLatencyMode) other;
            return configValue.equals(that.configValue);
        }

        @Override
        public int hashCode() {
            return configValue.hashCode();
        }
    }

    public static final class Choice {
        public final int value;
        public final String label;

        public Choice(int value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    public static final class TouchControlsMode {
        public final String prefValue;
        public final String label;
        public final String summary;

        public TouchControlsMode(String prefValue, String label, String summary) {
            this.prefValue = prefValue;
            this.label = label;
            this.summary = summary;
        }
    }
}
