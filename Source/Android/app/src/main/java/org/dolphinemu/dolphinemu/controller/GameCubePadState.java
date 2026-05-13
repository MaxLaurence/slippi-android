package org.dolphinemu.dolphinemu.controller;

import org.dolphinemu.dolphinemu.NativeLibrary;

/**
 * Mutable GameCube port state shared by hardware input and the touch overlay.
 * Values are kept in GC byte space for SI override and in Melee's float stick
 * space for the direct in-game stick path.
 */
public final class GameCubePadState {
    private final int port;

    private int buttons;
    private int stickX = 128;
    private int stickY = 128;
    private int substickX = 128;
    private int substickY = 128;
    private int triggerL;
    private int triggerR;
    private int analogTriggerL;
    private int analogTriggerR;
    private int analogA;
    private int analogB;

    private float meleeMainX;
    private float meleeMainY;
    private float meleeCX;
    private float meleeCY;

    public GameCubePadState(int port) {
        this.port = port;
    }

    public synchronized void reset() {
        buttons = 0;
        stickX = 128;
        stickY = 128;
        substickX = 128;
        substickY = 128;
        triggerL = 0;
        triggerR = 0;
        analogTriggerL = 0;
        analogTriggerR = 0;
        analogA = 0;
        analogB = 0;
        meleeMainX = 0f;
        meleeMainY = 0f;
        meleeCX = 0f;
        meleeCY = 0f;
    }

    public synchronized void setButton(int gcBit, boolean pressed) {
        if (gcBit == 0) return;
        if (pressed) {
            buttons |= gcBit;
        } else {
            buttons &= ~gcBit;
        }
        syncTriggerBytes();
    }

    public synchronized void setDirectionalButtons(boolean up, boolean down, boolean left, boolean right) {
        setButton(ButtonMap.GC_BTN_UP, up);
        setButton(ButtonMap.GC_BTN_DOWN, down);
        setButton(ButtonMap.GC_BTN_LEFT, left);
        setButton(ButtonMap.GC_BTN_RIGHT, right);
    }

    public synchronized void setHat(float x, float y) {
        setDirectionalButtons(y < -0.5f, y > 0.5f, x < -0.5f, x > 0.5f);
    }

    public synchronized void setAnalogTriggerL(float value) {
        analogTriggerL = clampByteFromUnit(value);
        syncTriggerBytes();
    }

    public synchronized void setAnalogTriggerR(float value) {
        analogTriggerR = clampByteFromUnit(value);
        syncTriggerBytes();
    }

    public synchronized void setMainStick(float x, float y) {
        float cx = clampUnit(x);
        float cy = clampUnit(y);
        stickX = stickByteFromUnit(cx);
        stickY = stickByteFromUnit(cy);
        meleeMainX = cx;
        meleeMainY = cy;
    }

    public synchronized void setCStick(float x, float y) {
        float cx = clampUnit(x);
        float cy = clampUnit(y);
        substickX = stickByteFromUnit(cx);
        substickY = stickByteFromUnit(cy);
        meleeCX = cx;
        meleeCY = cy;
    }

    public synchronized void setMainStickCentered() {
        setMainStick(0f, 0f);
    }

    public synchronized void setCStickCentered() {
        setCStick(0f, 0f);
    }

    public synchronized void pushToNative() {
        NativeLibrary.SetPadOverride(port, buttons,
                stickX, stickY, substickX, substickY,
                triggerL, triggerR, analogA, analogB);
        NativeLibrary.SetMeleePadFloats(port, meleeMainX, meleeMainY, meleeCX, meleeCY);
    }

    private void syncTriggerBytes() {
        triggerL = (buttons & ButtonMap.GC_TRIG_L) != 0 ? 255 : analogTriggerL;
        triggerR = (buttons & ButtonMap.GC_TRIG_R) != 0 ? 255 : analogTriggerR;
    }

    private static int clampByteFromUnit(float v) {
        if (v <= 0f) return 0;
        if (v >= 1f) return 255;
        return Math.round(v * 255f);
    }

    private static int stickByteFromUnit(float v) {
        int b = Math.round(clampUnit(v) * 127f + 128f);
        if (b < 0) return 0;
        if (b > 255) return 255;
        return b;
    }

    private static float clampUnit(float v) {
        if (v < -1f) return -1f;
        if (v > 1f) return 1f;
        return v;
    }
}
