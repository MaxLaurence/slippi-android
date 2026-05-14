package org.dolphinemu.dolphinemu.features.input.model;

public final class CoreDevice {
    public final long pointer;

    public CoreDevice(long pointer) {
        this.pointer = pointer;
    }

    public static final class Control {
        public final CoreDevice device;
        public final long pointer;

        public Control(CoreDevice device, long pointer) {
            this.device = device;
            this.pointer = pointer;
        }
    }
}
