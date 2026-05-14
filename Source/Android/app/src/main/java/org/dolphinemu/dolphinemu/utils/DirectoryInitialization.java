package org.dolphinemu.dolphinemu.utils;

public final class DirectoryInitialization {
    private DirectoryInitialization() {}

    public static native void SetSysDirectory(String path);

    public static native void SetGpuDriverDirectories(String path, String libraryPath);
}
