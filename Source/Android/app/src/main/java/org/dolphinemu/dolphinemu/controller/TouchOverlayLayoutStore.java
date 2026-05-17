package org.dolphinemu.dolphinemu.controller;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TouchOverlayLayoutStore {
    public static final String MAIN_STICK = "main_stick";
    public static final String C_STICK = "c_stick";
    public static final String DPAD = "dpad";
    public static final String A = "a";
    public static final String B = "b";
    public static final String X = "x";
    public static final String Y = "y";
    public static final String L = "l";
    public static final String R = "r";
    public static final String Z = "z";
    public static final String START = "start";

    public static final int KIND_STICK = 1;
    public static final int KIND_DPAD = 2;
    public static final int KIND_BUTTON = 3;

    public static final String SWIPE_GROUP_MELEE_TECH = "melee_tech";

    private static final String PREF_FILE = "touch_overlay_layout";
    private static final int VERSION = 2;

    private TouchOverlayLayoutStore() {
    }

    public static final class Control {
        public final String id;
        public final String label;
        public final int kind;
        public final int gcBit;
        public final String swipeGroup;
        public float x;
        public float y;
        public float size;

        private Control(String id, String label, int kind, int gcBit,
                        float x, float y, float size) {
            this(id, label, kind, gcBit, null, x, y, size);
        }

        private Control(String id, String label, int kind, int gcBit, String swipeGroup,
                        float x, float y, float size) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.gcBit = gcBit;
            this.swipeGroup = swipeGroup;
            this.x = x;
            this.y = y;
            this.size = size;
        }

        public Control copy() {
            return new Control(id, label, kind, gcBit, swipeGroup, x, y, size);
        }
    }

    public static final class Layout {
        private final LinkedHashMap<String, Control> controls = new LinkedHashMap<>();
        public float opacity = 0.58f;

        public Collection<Control> controls() {
            return controls.values();
        }

        public Control get(String id) {
            return controls.get(id);
        }

        private void add(Control control) {
            controls.put(control.id, control);
        }

        public Layout copy() {
            Layout out = new Layout();
            out.opacity = opacity;
            for (Control control : controls.values()) {
                out.add(control.copy());
            }
            return out;
        }
    }

    public static Layout load(Context context) {
        Layout layout = defaults();
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        if (prefs.getInt("version", VERSION) != VERSION) {
            return layout;
        }
        layout.opacity = clamp(prefs.getFloat("opacity", layout.opacity), 0.2f, 1f);
        for (Control control : layout.controls()) {
            String prefix = "control." + control.id + ".";
            control.x = clamp(prefs.getFloat(prefix + "x", control.x), 0f, 1f);
            control.y = clamp(prefs.getFloat(prefix + "y", control.y), 0f, 1f);
            control.size = clamp(prefs.getFloat(prefix + "size", control.size), 0.045f, 0.28f);
        }
        return layout;
    }

    public static void save(Context context, Layout layout) {
        SharedPreferences.Editor edit = context.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt("version", VERSION)
                .putFloat("opacity", clamp(layout.opacity, 0.2f, 1f));
        for (Control control : layout.controls()) {
            String prefix = "control." + control.id + ".";
            edit.putFloat(prefix + "x", clamp(control.x, 0f, 1f));
            edit.putFloat(prefix + "y", clamp(control.y, 0f, 1f));
            edit.putFloat(prefix + "size", clamp(control.size, 0.045f, 0.28f));
        }
        edit.apply();
    }

    public static void reset(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    public static Layout defaults() {
        Layout layout = new Layout();
        layout.opacity = 0.62f;
        layout.add(new Control(MAIN_STICK, "L", KIND_STICK, 0, 0.15f, 0.70f, 0.23f));
        layout.add(new Control(DPAD, "D", KIND_DPAD, 0, 0.10f, 0.43f, 0.10f));
        layout.add(new Control(C_STICK, "C", KIND_STICK, 0, 0.66f, 0.78f, 0.13f));
        layout.add(new Control(Y, "Y", KIND_BUTTON, ButtonMap.GC_BTN_Y,
                SWIPE_GROUP_MELEE_TECH, 0.78f, 0.43f, 0.110f));
        layout.add(new Control(R, "R", KIND_BUTTON, ButtonMap.GC_TRIG_R,
                SWIPE_GROUP_MELEE_TECH, 0.84f, 0.54f, 0.110f));
        layout.add(new Control(B, "B", KIND_BUTTON, ButtonMap.GC_BTN_B,
                SWIPE_GROUP_MELEE_TECH, 0.77f, 0.66f, 0.098f));
        layout.add(new Control(A, "A", KIND_BUTTON, ButtonMap.GC_BTN_A,
                SWIPE_GROUP_MELEE_TECH, 0.91f, 0.64f, 0.115f));
        layout.add(new Control(X, "X", KIND_BUTTON, ButtonMap.GC_BTN_X,
                SWIPE_GROUP_MELEE_TECH, 0.94f, 0.52f, 0.080f));
        layout.add(new Control(L, "L", KIND_BUTTON, ButtonMap.GC_TRIG_L,
                SWIPE_GROUP_MELEE_TECH, 0.14f, 0.13f, 0.115f));
        layout.add(new Control(Z, "Z", KIND_BUTTON, ButtonMap.GC_TRIG_Z,
                SWIPE_GROUP_MELEE_TECH, 0.95f, 0.28f, 0.080f));
        layout.add(new Control(START, "ST", KIND_BUTTON, ButtonMap.GC_BTN_START, 0.50f, 0.15f, 0.070f));
        return layout;
    }

    public static void copyInto(Layout source, Layout target) {
        target.opacity = source.opacity;
        for (Map.Entry<String, Control> entry : source.controls.entrySet()) {
            Control dst = target.controls.get(entry.getKey());
            if (dst == null) continue;
            Control src = entry.getValue();
            dst.x = src.x;
            dst.y = src.y;
            dst.size = src.size;
        }
    }

    public static boolean sameSwipeGroup(Control first, Control second) {
        if (first == null || second == null) return false;
        return first.swipeGroup != null && first.swipeGroup.equals(second.swipeGroup);
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
