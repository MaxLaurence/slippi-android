package org.dolphinemu.dolphinemu;

import android.content.Context;

import java.io.File;

public final class MainlineCore {
    public static final String LIBRARY_NAME = "mainline_slippi";
    public static final String LIBRARY_FILE_NAME = "lib" + LIBRARY_NAME + ".so";
    public static final String PROCESS_SUFFIX = ":mainline";

    private MainlineCore() {}

    public static File userDir(Context context) {
        return new File(context.getFilesDir(), "mainline_dolphin");
    }

    public static File sysDir(Context context) {
        return new File(userDir(context), "Sys");
    }

    public static File cacheDir(Context context) {
        return new File(userDir(context), "Cache");
    }

    public static boolean isPackaged(Context context) {
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        return nativeLibraryDir != null
                && new File(nativeLibraryDir, LIBRARY_FILE_NAME).isFile();
    }
}
