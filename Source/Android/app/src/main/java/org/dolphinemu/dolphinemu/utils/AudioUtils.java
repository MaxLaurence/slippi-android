package org.dolphinemu.dolphinemu.utils;

import android.content.Context;
import android.media.AudioManager;

import org.dolphinemu.dolphinemu.DolphinApplication;

public final class AudioUtils {
    private AudioUtils() {}

    public static int getSampleRate() {
        AudioManager manager = audioManager();
        if (manager == null) return 48000;
        String value = manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        try {
            return value == null ? 48000 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 48000;
        }
    }

    public static int getFramesPerBuffer() {
        AudioManager manager = audioManager();
        if (manager == null) return 256;
        String value = manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        try {
            return value == null ? 256 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 256;
        }
    }

    private static AudioManager audioManager() {
        Context context = DolphinApplication.getAppContext();
        return context == null ? null : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }
}
