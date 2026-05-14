package org.dolphinemu.dolphinemu.utils;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;

public final class PermissionsHandler {
    private PermissionsHandler() {}

    public static boolean hasRecordAudioPermission(Context context) {
        return context != null
                && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestRecordAudioPermission(Activity activity) {
        if (activity != null) {
            activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 0);
        }
    }
}
