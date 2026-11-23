package org.dolphinemu.dolphinemu.controller;

import android.view.InputDevice;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves stick, trigger, and hat axis assignments for an Android InputDevice.
 *
 * Standard Android gamepads put the right stick on (AXIS_Z, AXIS_RZ) and analog
 * triggers on (AXIS_LTRIGGER/AXIS_BRAKE, AXIS_RTRIGGER/AXIS_GAS). However:
 *   - Many generic USB / DirectInput controllers expose the right stick on
 *     (AXIS_RX, AXIS_RY) or (AXIS_Z, AXIS_RY).
 *   - Third-party Xbox 360 / XInput controllers (such as HayBox using Mad Catz
 *     VID/PID 0x0738:0x4726) running on Android builds without a vendor .kl file
 *     fall back to Generic.kl, where Linux xpad's ABS_Z / ABS_RZ triggers land
 *     on AXIS_Z / AXIS_RZ (in [-1, +1]) and right stick lands on AXIS_RX / AXIS_RY.
 */
public final class AndroidControllerAxisLayout {

    public static final int AXIS_NONE = -1;

    public static final AndroidControllerAxisLayout STANDARD = new AndroidControllerAxisLayout(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE, 0f, 1f,
            MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS, 0f, 1f,
            true, true, true);

    public static final class AxisRange {
        public final float min;
        public final float max;
        public final float flat;

        public AxisRange(float min, float max, float flat) {
            this.min = min;
            this.max = max;
            this.flat = flat;
        }
    }

    public static final class Capabilities {
        public final String name;
        public final Map<Integer, AxisRange> ranges;

        public Capabilities(String name, Map<Integer, AxisRange> ranges) {
            this.name = name == null ? "" : name;
            this.ranges = ranges;
        }

        public boolean has(int axis) {
            return ranges.containsKey(axis);
        }

        public AxisRange get(int axis) {
            return ranges.get(axis);
        }
    }

    public final int mainX;
    public final int mainY;
    public final int cX;
    public final int cY;
    public final int leftTriggerAxis;
    public final int leftTriggerFallbackAxis;
    public final float leftTriggerMin;
    public final float leftTriggerMax;
    public final int rightTriggerAxis;
    public final int rightTriggerFallbackAxis;
    public final float rightTriggerMin;
    public final float rightTriggerMax;
    public final boolean hasAnalogLeftTrigger;
    public final boolean hasAnalogRightTrigger;
    public final boolean hasHat;

    public AndroidControllerAxisLayout(int mainX, int mainY, int cX, int cY,
                                       int leftTriggerAxis, int leftTriggerFallbackAxis,
                                       float leftTriggerMin, float leftTriggerMax,
                                       int rightTriggerAxis, int rightTriggerFallbackAxis,
                                       float rightTriggerMin, float rightTriggerMax,
                                       boolean hasAnalogLeftTrigger,
                                       boolean hasAnalogRightTrigger,
                                       boolean hasHat) {
        this.mainX = mainX;
        this.mainY = mainY;
        this.cX = cX;
        this.cY = cY;
        this.leftTriggerAxis = leftTriggerAxis;
        this.leftTriggerFallbackAxis = leftTriggerFallbackAxis;
        this.leftTriggerMin = leftTriggerMin;
        this.leftTriggerMax = leftTriggerMax;
        this.rightTriggerAxis = rightTriggerAxis;
        this.rightTriggerFallbackAxis = rightTriggerFallbackAxis;
        this.rightTriggerMin = rightTriggerMin;
        this.rightTriggerMax = rightTriggerMax;
        this.hasAnalogLeftTrigger = hasAnalogLeftTrigger;
        this.hasAnalogRightTrigger = hasAnalogRightTrigger;
        this.hasHat = hasHat;
    }

    public static AndroidControllerAxisLayout resolve(InputDevice device) {
        if (device == null) return STANDARD;
        int sources = device.getSources();
        int preferredSource = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                ? InputDevice.SOURCE_JOYSTICK : InputDevice.SOURCE_GAMEPAD;
        int[] axes = {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
                MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        };
        Map<Integer, AxisRange> map = new HashMap<>();
        for (int axis : axes) {
            InputDevice.MotionRange r = device.getMotionRange(axis, preferredSource);
            if (r == null) r = device.getMotionRange(axis);
            if (r != null) {
                map.put(axis, new AxisRange(r.getMin(), r.getMax(), r.getFlat()));
            }
        }
        return resolve(new Capabilities(device.getName(), map));
    }

    public static AndroidControllerAxisLayout resolve(Capabilities caps) {
        if (caps == null || caps.ranges.isEmpty()) return STANDARD;

        int mainX = MotionEvent.AXIS_X;
        int mainY = MotionEvent.AXIS_Y;

        boolean hasLTrig = caps.has(MotionEvent.AXIS_LTRIGGER);
        boolean hasRTrig = caps.has(MotionEvent.AXIS_RTRIGGER);
        boolean hasBrake = caps.has(MotionEvent.AXIS_BRAKE);
        boolean hasGas = caps.has(MotionEvent.AXIS_GAS);
        boolean hasExplicitTriggers = hasLTrig || hasRTrig || hasBrake || hasGas;

        boolean hasZ = caps.has(MotionEvent.AXIS_Z);
        boolean hasRZ = caps.has(MotionEvent.AXIS_RZ);
        boolean hasRX = caps.has(MotionEvent.AXIS_RX);
        boolean hasRY = caps.has(MotionEvent.AXIS_RY);
        boolean hasHat = caps.has(MotionEvent.AXIS_HAT_X) || caps.has(MotionEvent.AXIS_HAT_Y);

        // Unmapped xpad (Xbox 360 / Mad Catz / HayBox) under Generic.kl:
        // ABS_Z/ABS_RZ are 0..255 triggers mapped to AXIS_Z/AXIS_RZ (typically
        // flat == 0, min == -1..+1), while ABS_RX/ABS_RY are the right stick.
        if (!hasExplicitTriggers && hasZ && hasRZ && hasRX && hasRY
                && looksLikeGenericKlXpad(caps)) {
            AxisRange ltRange = caps.get(MotionEvent.AXIS_Z);
            AxisRange rtRange = caps.get(MotionEvent.AXIS_RZ);
            return new AndroidControllerAxisLayout(
                    mainX, mainY,
                    MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
                    MotionEvent.AXIS_Z, AXIS_NONE, ltRange.min, ltRange.max,
                    MotionEvent.AXIS_RZ, AXIS_NONE, rtRange.min, rtRange.max,
                    true, true, hasHat);
        }

        int cX;
        int cY;
        if (hasZ && hasRZ) {
            cX = MotionEvent.AXIS_Z;
            cY = MotionEvent.AXIS_RZ;
        } else if (hasRX && hasRY) {
            cX = MotionEvent.AXIS_RX;
            cY = MotionEvent.AXIS_RY;
        } else if (hasZ && hasRY) {
            cX = MotionEvent.AXIS_Z;
            cY = MotionEvent.AXIS_RY;
        } else if (hasRX && hasRZ) {
            cX = MotionEvent.AXIS_RX;
            cY = MotionEvent.AXIS_RZ;
        } else {
            cX = MotionEvent.AXIS_Z;
            cY = MotionEvent.AXIS_RZ;
        }

        int ltAxis = hasLTrig ? MotionEvent.AXIS_LTRIGGER
                : (hasBrake ? MotionEvent.AXIS_BRAKE : AXIS_NONE);
        int ltFallback = hasLTrig && hasBrake ? MotionEvent.AXIS_BRAKE : AXIS_NONE;
        AxisRange ltRange = ltAxis != AXIS_NONE ? caps.get(ltAxis) : null;

        int rtAxis = hasRTrig ? MotionEvent.AXIS_RTRIGGER
                : (hasGas ? MotionEvent.AXIS_GAS : AXIS_NONE);
        int rtFallback = hasRTrig && hasGas ? MotionEvent.AXIS_GAS : AXIS_NONE;
        AxisRange rtRange = rtAxis != AXIS_NONE ? caps.get(rtAxis) : null;

        return new AndroidControllerAxisLayout(
                mainX, mainY, cX, cY,
                ltAxis, ltFallback,
                ltRange != null ? ltRange.min : 0f, ltRange != null ? ltRange.max : 1f,
                rtAxis, rtFallback,
                rtRange != null ? rtRange.min : 0f, rtRange != null ? rtRange.max : 1f,
                ltAxis != AXIS_NONE, rtAxis != AXIS_NONE, hasHat);
    }

    public float readLeftTrigger(MotionEvent ev) {
        return readTrigger(ev, leftTriggerAxis, leftTriggerFallbackAxis,
                leftTriggerMin, leftTriggerMax);
    }

    public float readRightTrigger(MotionEvent ev) {
        return readTrigger(ev, rightTriggerAxis, rightTriggerFallbackAxis,
                rightTriggerMin, rightTriggerMax);
    }

    public float normalizeLeftTrigger(float primary, float fallback) {
        return normalizeTrigger(primary, fallback, leftTriggerAxis, leftTriggerFallbackAxis,
                leftTriggerMin, leftTriggerMax);
    }

    public float normalizeRightTrigger(float primary, float fallback) {
        return normalizeTrigger(primary, fallback, rightTriggerAxis, rightTriggerFallbackAxis,
                rightTriggerMin, rightTriggerMax);
    }

    private float readTrigger(MotionEvent ev, int primaryAxis, int fallbackAxis,
                              float min, float max) {
        if (primaryAxis == AXIS_NONE) return 0f;
        float primary = ev.getAxisValue(primaryAxis);
        float fallback = fallbackAxis != AXIS_NONE ? ev.getAxisValue(fallbackAxis) : 0f;
        return normalizeTrigger(primary, fallback, primaryAxis, fallbackAxis, min, max);
    }

    private static float normalizeTrigger(float primary, float fallback,
                                          int primaryAxis, int fallbackAxis,
                                          float min, float max) {
        if (primaryAxis == AXIS_NONE) return 0f;
        if (min < -0.5f && max > min) {
            return clamp01((primary - min) / (max - min));
        }
        float value = clamp01(primary);
        if (value == 0f && fallbackAxis != AXIS_NONE) {
            value = clamp01(fallback);
        }
        return value;
    }

    private static boolean looksLikeGenericKlXpad(Capabilities caps) {
        String lower = caps.name.toLowerCase(Locale.US);
        if (lower.contains("xbox") || lower.contains("x-box") || lower.contains("xinput")
                || lower.contains("360") || lower.contains("mad catz") || lower.contains("haybox")) {
            return true;
        }
        AxisRange z = caps.get(MotionEvent.AXIS_Z);
        AxisRange rz = caps.get(MotionEvent.AXIS_RZ);
        AxisRange rx = caps.get(MotionEvent.AXIS_RX);
        AxisRange ry = caps.get(MotionEvent.AXIS_RY);
        return z != null && rz != null && rx != null && ry != null
                && z.flat == 0f && rz.flat == 0f
                && (rx.flat > 0f || ry.flat > 0f);
    }

    private static float clamp01(float v) {
        if (v <= 0f) return 0f;
        if (v >= 1f) return 1f;
        return v;
    }

    public String describe() {
        return "main=(" + axisLabel(mainX) + "," + axisLabel(mainY) + ")"
                + " c=(" + axisLabel(cX) + "," + axisLabel(cY) + ")"
                + " lt=" + axisLabel(leftTriggerAxis)
                + " rt=" + axisLabel(rightTriggerAxis)
                + " hat=" + hasHat;
    }

    private static String axisLabel(int axis) {
        return axis == AXIS_NONE ? "none" : MotionEvent.axisToString(axis);
    }
}
