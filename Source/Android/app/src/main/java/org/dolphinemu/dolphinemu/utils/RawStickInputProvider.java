package org.dolphinemu.dolphinemu.utils;

/**
 * Source of unsaturated built-in stick data. Providers must only report
 * available when they bypass Android's already-normalized MotionEvent axes.
 */
public interface RawStickInputProvider {
    String id();

    String label();

    boolean isAvailable();

    void start();

    void stop();

    RawStickState snapshot();

    default RawStickState waitForSnapshot(int timeoutMs) {
        return snapshot();
    }

    default boolean supportsBlockingWait() {
        return false;
    }

    boolean keepPollingWhenUnavailable();

    /**
     * True when the provider's values may still be clipped by firmware below
     * the nominal ±1.0 range, so an uncalibrated stick reading the provider
     * cannot reach full output without amplification. Consumers use this to
     * pick compensating defaults when the user hasn't run the wizard.
     */
    default boolean isPotentiallySaturated() {
        return false;
    }
}
