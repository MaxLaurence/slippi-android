package org.dolphinemu.dolphinemu.utils;

/**
 * Research stub for Android handheld families whose public software exposes
 * key-mapping or calibration UI but no app-callable raw stick service yet.
 * Keep these providers unavailable until we have a real binder/API contract.
 */
final class KnownUnsupportedHandheldProvider implements RawStickInputProvider {
    private final String id;
    private final String label;

    KnownUnsupportedHandheldProvider(String id, String label) {
        this.id = id;
        this.label = label;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public RawStickState snapshot() {
        return null;
    }

    @Override
    public boolean keepPollingWhenUnavailable() {
        return false;
    }
}
