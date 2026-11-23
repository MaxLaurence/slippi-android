package org.dolphinemu.dolphinemu.controller;

import org.dolphinemu.dolphinemu.NativeLibrary;

import java.util.Locale;

/**
 * Mutable GameCube port state shared by hardware input and the touch overlay.
 * Values are kept in GC byte space for SI override and in Melee's float stick
 * space for the direct in-game stick path.
 */
public final class GameCubePadState {
    /**
     * Analog trigger threshold for asserting the digital GC L/R click bit.
     * Matches Dolphin's standard 90% trigger threshold: partial pulls (such
     * as HayBox's 49/255 light shield and 94/255 mid shield, or half-pulled
     * analog triggers) stay purely analog, while full pulls (>= 90%) assert
     * the digital click required for hard shield, wavedash, tech, and L-cancel.
     */
    public static final float ANALOG_TRIGGER_DIGITAL_THRESHOLD = 0.90f;
    private static final float ANALOG_TRIGGER_ACTIVE_EPSILON = 0.01f;

    public interface NativeSink {
        void setPadOverride(int port, int buttons,
                            int stickX, int stickY, int substickX, int substickY,
                            int triggerL, int triggerR, int analogA, int analogB);

        void setMeleePadFloats(int port, float mainX, float mainY, float cX, float cY);
    }

    private static final NativeSink DEFAULT_SINK = new NativeSink() {
        @Override
        public void setPadOverride(int port, int buttons,
                                   int stickX, int stickY, int substickX, int substickY,
                                   int triggerL, int triggerR, int analogA, int analogB) {
            NativeLibrary.SetPadOverride(port, buttons,
                    stickX, stickY, substickX, substickY,
                    triggerL, triggerR, analogA, analogB);
        }

        @Override
        public void setMeleePadFloats(int port, float mainX, float mainY, float cX, float cY) {
            NativeLibrary.SetMeleePadFloats(port, mainX, mainY, cX, cY);
        }
    };

    private final int port;
    private final NativeSink sink;

    private int buttons;
    private int hatButtons;
    private boolean l2KeyPressed;
    private boolean r2KeyPressed;
    private boolean l2AnalogOwned;
    private boolean r2AnalogOwned;
    private boolean analogDigitalL;
    private boolean analogDigitalR;
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
        this(port, DEFAULT_SINK);
    }

    public GameCubePadState(int port, NativeSink sink) {
        this.port = port;
        this.sink = sink != null ? sink : DEFAULT_SINK;
    }

    public synchronized void reset() {
        buttons = 0;
        hatButtons = 0;
        l2KeyPressed = false;
        r2KeyPressed = false;
        l2AnalogOwned = false;
        r2AnalogOwned = false;
        analogDigitalL = false;
        analogDigitalR = false;
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

    /**
     * Record a KeyEvent from KEYCODE_BUTTON_L2 when it is bound to GC_TRIG_L.
     * On controllers with analog L triggers, Android / OEM input layers often
     * synthesize KEYCODE_BUTTON_L2 at a low threshold (~10-15%), which would
     * turn light/mid shields into full digital shields. When {@code analogOwned}
     * is true, the analog trigger axis governs the digital click instead.
     */
    public synchronized void setTriggerKeyL2(boolean pressed, boolean analogOwned) {
        l2KeyPressed = pressed;
        l2AnalogOwned = analogOwned;
        syncTriggerBytes();
    }

    /** Symmetric counterpart of {@link #setTriggerKeyL2} for KEYCODE_BUTTON_R2 -> GC_TRIG_R. */
    public synchronized void setTriggerKeyR2(boolean pressed, boolean analogOwned) {
        r2KeyPressed = pressed;
        r2AnalogOwned = analogOwned;
        syncTriggerBytes();
    }

    public synchronized void setDirectionalButtons(boolean up, boolean down, boolean left, boolean right) {
        setButton(ButtonMap.GC_BTN_UP, up);
        setButton(ButtonMap.GC_BTN_DOWN, down);
        setButton(ButtonMap.GC_BTN_LEFT, left);
        setButton(ButtonMap.GC_BTN_RIGHT, right);
    }

    /**
     * Update D-pad state from MotionEvent HAT_X/HAT_Y axes without clobbering
     * digital KEYCODE_DPAD_* button state.
     */
    public synchronized void setHat(float x, float y) {
        int next = 0;
        if (y < -0.5f) next |= ButtonMap.GC_BTN_UP;
        if (y > 0.5f) next |= ButtonMap.GC_BTN_DOWN;
        if (x < -0.5f) next |= ButtonMap.GC_BTN_LEFT;
        if (x > 0.5f) next |= ButtonMap.GC_BTN_RIGHT;
        hatButtons = next;
    }

    public synchronized void setAnalogTriggerL(float value) {
        if (value > ANALOG_TRIGGER_ACTIVE_EPSILON) {
            l2AnalogOwned = true;
        }
        analogTriggerL = clampByteFromUnit(value);
        analogDigitalL = value >= ANALOG_TRIGGER_DIGITAL_THRESHOLD;
        syncTriggerBytes();
    }

    public synchronized void setAnalogTriggerR(float value) {
        if (value > ANALOG_TRIGGER_ACTIVE_EPSILON) {
            r2AnalogOwned = true;
        }
        analogTriggerR = clampByteFromUnit(value);
        analogDigitalR = value >= ANALOG_TRIGGER_DIGITAL_THRESHOLD;
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

    public synchronized int getEffectiveButtons() {
        return effectiveButtons();
    }

    public synchronized int getStickX() { return stickX; }
    public synchronized int getStickY() { return stickY; }
    public synchronized int getSubstickX() { return substickX; }
    public synchronized int getSubstickY() { return substickY; }
    public synchronized int getTriggerL() { return triggerL; }
    public synchronized int getTriggerR() { return triggerR; }

    public synchronized void pushToNative() {
        int effective = effectiveButtons();
        sink.setPadOverride(port, effective,
                stickX, stickY, substickX, substickY,
                triggerL, triggerR, analogA, analogB);
        sink.setMeleePadFloats(port, meleeMainX, meleeMainY, meleeCX, meleeCY);
    }

    public synchronized String snapshotString() {
        int effective = effectiveButtons();
        return "buttons=0x" + String.format(Locale.US, "%04X", effective & 0xFFFF)
                + " names=" + buttonNames(effective)
                + " main=(" + stickX + "," + stickY + ")"
                + " c=(" + substickX + "," + substickY + ")"
                + " triggers=(" + triggerL + "," + triggerR + ")"
                + " analogTriggers=(" + analogTriggerL + "," + analogTriggerR + ")"
                + " meleeMain=(" + formatUnit(meleeMainX) + "," + formatUnit(meleeMainY) + ")"
                + " meleeC=(" + formatUnit(meleeCX) + "," + formatUnit(meleeCY) + ")";
    }

    private int effectiveButtons() {
        int effective = buttons | hatButtons;
        if (analogDigitalL || (!l2AnalogOwned && l2KeyPressed)) {
            effective |= ButtonMap.GC_TRIG_L;
        }
        if (analogDigitalR || (!r2AnalogOwned && r2KeyPressed)) {
            effective |= ButtonMap.GC_TRIG_R;
        }
        return effective;
    }

    private static String buttonNames(int buttons) {
        if (buttons == 0) return "none";
        StringBuilder sb = new StringBuilder();
        for (int bit : ButtonMap.GC_BUTTONS_DISPLAY_ORDER) {
            if ((buttons & bit) == 0) continue;
            if (sb.length() > 0) sb.append('+');
            sb.append(ButtonMap.labelForGcBit(bit));
        }
        return sb.toString();
    }

    private void syncTriggerBytes() {
        int effective = effectiveButtons();
        triggerL = (effective & ButtonMap.GC_TRIG_L) != 0 ? 255 : analogTriggerL;
        triggerR = (effective & ButtonMap.GC_TRIG_R) != 0 ? 255 : analogTriggerR;
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

    private static String formatUnit(float v) {
        return String.format(Locale.US, "%+.3f", v);
    }
}
