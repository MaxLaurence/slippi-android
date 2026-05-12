package org.dolphinemu.dolphinemu.controller;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-device mapping from Android {@link KeyEvent} key codes to GameCube
 * pad bitmask bits.
 *
 * One physical button → one GC button is the common case. Multiple
 * physical buttons mapped to the same GC button is also supported — the
 * default Slippi mapping for example binds both L1 and L2 to TRIGGER_L,
 * and both THUMBL and THUMBR to Z.
 *
 * The map is one-way (key → GC bit) because lookups in the input hot
 * path go from physical → logical. For UI display we invert the map
 * on demand to show "which physical buttons trigger this GC button".
 *
 * Serialization format (stored as a single SharedPreferences string for
 * atomicity): "keyCode:gcBit,keyCode:gcBit,..." sorted by keyCode for
 * determinism. An empty string represents "no bindings"; missing key
 * means "use defaults".
 */
public final class ButtonMap {

    // ─── GC button bits (must match PAD_BUTTON_* / PAD_TRIGGER_* in C++
    //     and the constants in EmulationActivity.java) ───
    public static final int GC_BTN_LEFT  = 0x0001;
    public static final int GC_BTN_RIGHT = 0x0002;
    public static final int GC_BTN_DOWN  = 0x0004;
    public static final int GC_BTN_UP    = 0x0008;
    public static final int GC_TRIG_Z    = 0x0010;
    public static final int GC_TRIG_R    = 0x0020;
    public static final int GC_TRIG_L    = 0x0040;
    public static final int GC_BTN_A     = 0x0100;
    public static final int GC_BTN_B     = 0x0200;
    public static final int GC_BTN_X     = 0x0400;
    public static final int GC_BTN_Y     = 0x0800;
    public static final int GC_BTN_START = 0x1000;

    /** Display order for the remap UI. The order matters for muscle memory. */
    public static final int[] GC_BUTTONS_DISPLAY_ORDER = {
            GC_BTN_A, GC_BTN_B, GC_BTN_X, GC_BTN_Y,
            GC_TRIG_Z, GC_TRIG_L, GC_TRIG_R, GC_BTN_START,
            GC_BTN_UP, GC_BTN_DOWN, GC_BTN_LEFT, GC_BTN_RIGHT
    };

    public static String labelForGcBit(int gcBit) {
        switch (gcBit) {
            case GC_BTN_A:     return "A";
            case GC_BTN_B:     return "B";
            case GC_BTN_X:     return "X";
            case GC_BTN_Y:     return "Y";
            case GC_BTN_START: return "Start";
            case GC_TRIG_Z:    return "Z";
            case GC_TRIG_L:    return "L (trigger)";
            case GC_TRIG_R:    return "R (trigger)";
            case GC_BTN_UP:    return "D-Pad Up";
            case GC_BTN_DOWN:  return "D-Pad Down";
            case GC_BTN_LEFT:  return "D-Pad Left";
            case GC_BTN_RIGHT: return "D-Pad Right";
            default:           return "GC 0x" + Integer.toHexString(gcBit);
        }
    }

    public static ButtonMap defaults() {
        ButtonMap m = new ButtonMap();
        m.bindings.put(KeyEvent.KEYCODE_BUTTON_A,      GC_BTN_A);
        m.bindings.put(KeyEvent.KEYCODE_BUTTON_B,      GC_BTN_B);
        m.bindings.put(KeyEvent.KEYCODE_BUTTON_X,      GC_BTN_X);
        m.bindings.put(KeyEvent.KEYCODE_BUTTON_Y,      GC_BTN_Y);
        m.bindings.put(KeyEvent.KEYCODE_BUTTON_START,  GC_BTN_START);
        m.bindings.put(KeyEvent.KEYCODE_BUTTON_L1,     GC_TRIG_L);
        m.bindings.put(KeyEvent.KEYCODE_BUTTON_L2,     GC_TRIG_L);
        m.bindings.put(KeyEvent.KEYCODE_BUTTON_R1,     GC_TRIG_Z);
        m.bindings.put(KeyEvent.KEYCODE_BUTTON_R2,     GC_TRIG_R);
        m.bindings.put(KeyEvent.KEYCODE_DPAD_UP,       GC_BTN_UP);
        m.bindings.put(KeyEvent.KEYCODE_DPAD_DOWN,     GC_BTN_DOWN);
        m.bindings.put(KeyEvent.KEYCODE_DPAD_LEFT,     GC_BTN_LEFT);
        m.bindings.put(KeyEvent.KEYCODE_DPAD_RIGHT,    GC_BTN_RIGHT);
        return m;
    }

    /** keyCode → GC bit. LinkedHashMap preserves insertion order for ties. */
    private final Map<Integer, Integer> bindings = new LinkedHashMap<>();

    /** Lookup the GC bit for a physical key, or 0 if no binding. */
    public int gcBitForKey(int keyCode) {
        Integer v = bindings.get(keyCode);
        return v == null ? 0 : v;
    }

    /** All Android keyCodes currently mapped to the given GC button bit. */
    public List<Integer> keysBoundTo(int gcBit) {
        List<Integer> result = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : bindings.entrySet()) {
            if (e.getValue() == gcBit) result.add(e.getKey());
        }
        return result;
    }

    /**
     * Bind a physical key to a GC button. If the key was previously bound
     * to a different GC button, that mapping is silently overwritten —
     * the caller (UI) is responsible for offering conflict resolution
     * before invoking this.
     */
    public void bind(int keyCode, int gcBit) {
        bindings.put(keyCode, gcBit);
    }

    /** Remove a single keyCode binding. */
    public void unbind(int keyCode) {
        bindings.remove(keyCode);
    }

    /** Remove ALL bindings for a given GC button (every keyCode mapped to it). */
    public void unbindAllFor(int gcBit) {
        bindings.values().removeAll(Collections.singleton(gcBit));
    }

    public int totalBindings() { return bindings.size(); }

    /** Returns the GC bit currently bound to keyCode, or 0 if none. */
    public int currentGcFor(int keyCode) {
        Integer v = bindings.get(keyCode);
        return v == null ? 0 : v;
    }

    public String serialize() {
        List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(bindings.entrySet());
        Collections.sort(entries, (a, b) -> Integer.compare(a.getKey(), b.getKey()));
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Integer, Integer> e : entries) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    public static ButtonMap deserialize(String s) {
        ButtonMap m = new ButtonMap();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split(",")) {
            int colon = pair.indexOf(':');
            if (colon <= 0 || colon == pair.length() - 1) continue;
            try {
                int k = Integer.parseInt(pair.substring(0, colon));
                int v = Integer.parseInt(pair.substring(colon + 1));
                m.bindings.put(k, v);
            } catch (NumberFormatException ignored) {}
        }
        return m;
    }

    /** Map a raw Android keyCode to a stable, readable name for UI display. */
    public static String keyName(int keyCode) {
        // KeyEvent.keyCodeToString returns "KEYCODE_BUTTON_A" — trim to "BUTTON_A".
        String full = KeyEvent.keyCodeToString(keyCode);
        if (full == null) return "Key " + keyCode;
        if (full.startsWith("KEYCODE_")) full = full.substring("KEYCODE_".length());
        // Lower-case the rest of the segments so "BUTTON_THUMBL" reads as "Button Thumbl".
        StringBuilder out = new StringBuilder();
        boolean firstChar = true;
        for (char c : full.toCharArray()) {
            if (c == '_') { out.append(' '); firstChar = true; continue; }
            out.append(firstChar ? c : Character.toLowerCase(c));
            firstChar = false;
        }
        return out.toString();
    }

    /** Comma-separated names of all keyCodes bound to a GC button (for UI). */
    public String describeBindingsFor(int gcBit) {
        return describeBindingsFor(gcBit, /*isAdapter*/ false);
    }

    /**
     * Adapter-aware variant. For adapter devices, the int we stored as
     * "keyCode" is actually a source GC bit (the bit the adapter would
     * normally emit when the user presses a specific physical button),
     * so we describe it as "GC A" instead of "Key 256".
     */
    public String describeBindingsFor(int gcBit, boolean isAdapter) {
        List<Integer> keys = keysBoundTo(gcBit);
        if (keys.isEmpty()) return "(unbound)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(isAdapter
                    ? "GC " + labelForGcBit(keys.get(i))
                    : keyName(keys.get(i)));
        }
        return sb.toString();
    }
}
