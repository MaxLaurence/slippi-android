package org.dolphinemu.dolphinemu.utils;

public interface WiiUpdateCallback {
    boolean run(int processed, int total, long titleId);
}
