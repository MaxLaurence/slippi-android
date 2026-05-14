package org.dolphinemu.dolphinemu.features.input.model;

import android.content.Context;
import android.os.Vibrator;

import org.dolphinemu.dolphinemu.DolphinApplication;

public final class DolphinVibratorManager {
    public Vibrator getVibrator(int vibratorId) {
        Context context = DolphinApplication.getAppContext();
        return context == null ? null : (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public int[] getVibratorIds() {
        return new int[0];
    }
}
