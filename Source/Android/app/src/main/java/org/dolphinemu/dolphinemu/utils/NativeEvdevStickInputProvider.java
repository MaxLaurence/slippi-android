package org.dolphinemu.dolphinemu.utils;

import org.dolphinemu.dolphinemu.NativeLibrary;

/**
 * Direct Linux evdev reader. This only works on rooted/privileged builds or
 * unusually permissive firmware; stock Android handhelds normally deny it.
 */
public final class NativeEvdevStickInputProvider implements RawStickInputProvider {
    @Override
    public String id() {
        return "linux_evdev";
    }

    @Override
    public String label() {
        return "Linux evdev raw axes";
    }

    @Override
    public boolean isAvailable() {
        return snapshot() != null;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public RawStickState snapshot() {
        return RawStickState.fromAxes(NativeLibrary.PollRawGamepadAxes());
    }

    @Override
    public RawStickState waitForSnapshot(int timeoutMs) {
        return RawStickState.fromAxes(NativeLibrary.WaitRawGamepadAxes(timeoutMs));
    }

    @Override
    public boolean supportsBlockingWait() {
        return true;
    }

    @Override
    public boolean keepPollingWhenUnavailable() {
        return false;
    }
}
