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

    boolean keepPollingWhenUnavailable();
}
