package org.dolphinemu.dolphinemu.utils;

import android.view.MotionEvent;

/**
 * Snapshot of a raw built-in controller sample in Android axis convention:
 * X positive = right, Y/RZ positive = down.
 */
public final class RawStickState {
    private boolean hasLeft;
    private boolean hasRight;
    private float leftX;
    private float leftY;
    private float rightX;
    private float rightY;

    public RawStickState() {
    }

    public RawStickState(RawStickState other) {
        hasLeft = other.hasLeft;
        hasRight = other.hasRight;
        leftX = other.leftX;
        leftY = other.leftY;
        rightX = other.rightX;
        rightY = other.rightY;
    }

    public static RawStickState fromAxes(float[] axes) {
        if (axes == null || axes.length < 4) {
            return null;
        }

        RawStickState state = new RawStickState();
        state.setLeft(axes[0], axes[1]);
        state.setRight(axes[2], axes[3]);
        return state;
    }

    public boolean hasAnyAxis() {
        return hasLeft || hasRight;
    }

    public Float valueForAxis(int axis) {
        switch (axis) {
            case MotionEvent.AXIS_X:
                return hasLeft ? leftX : null;
            case MotionEvent.AXIS_Y:
                return hasLeft ? leftY : null;
            case MotionEvent.AXIS_Z:
                return hasRight ? rightX : null;
            case MotionEvent.AXIS_RZ:
                return hasRight ? rightY : null;
            default:
                return null;
        }
    }

    public void setLeft(float x, float y) {
        hasLeft = true;
        leftX = x;
        leftY = y;
    }

    public void setRight(float x, float y) {
        hasRight = true;
        rightX = x;
        rightY = y;
    }

    public void clear() {
        hasLeft = false;
        hasRight = false;
        leftX = 0.0f;
        leftY = 0.0f;
        rightX = 0.0f;
        rightY = 0.0f;
    }
}
