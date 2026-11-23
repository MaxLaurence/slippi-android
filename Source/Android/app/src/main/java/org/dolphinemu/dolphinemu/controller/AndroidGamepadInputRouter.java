package org.dolphinemu.dolphinemu.controller;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.dolphinemu.dolphinemu.utils.RawStickState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Unified Android hardware controller input router shared by Ishiiruka
 * ({@code EmulationActivity}) and Mainline ({@code MainlineEmulationActivity}).
 *
 * Solves four real-world USB / Bluetooth controller failure modes:
 *   1. Raw handheld stick providers (OdinMappingInput on AYN Thor, Linux evdev)
 *      only read onboard sticks; previously they blocked all MotionEvent stick
 *      axes and continuously overwrote padState with neutral (0, 0). This router
 *      arbitrates stick ownership dynamically so external USB/BT controllers
 *      work immediately without breaking unsaturated onboard handheld sticks.
 *   2. Per-device StickCalibration and ButtonMap profiles so external USB/BT
 *      controllers do not inherit handheld 4x saturation scaling or handheld
 *      curves/remaps, while keeping linear passthrough defaults for external pads.
 *   3. Axis layout auto-detection (AXIS_Z/RZ vs AXIS_RX/RY, plus unmapped xpad
 *      Generic.kl triggers/sticks).
 *   4. Analog trigger digital-click arbitration: prevents synthetic L2/R2 key
 *      events from snapping light/mid shields (e.g. HayBox 49/255 and 94/255)
 *      into full digital shields, while ensuring full analog pulls (>= 90%)
 *      assert the digital GC L/R click even when no L2/R2 KeyEvent is emitted.
 */
public final class AndroidGamepadInputRouter {

    public interface ProfileSource {
        boolean hasCalibration(String deviceKey, ControllerProfile.Stick stick);
        StickCalibration getStick(String deviceKey, ControllerProfile.Stick stick);
        boolean hasButtonMap(String deviceKey);
        ButtonMap getButtonMap(String deviceKey);
    }

    public static final class DeviceDescriptor {
        public final int deviceId;
        public final String profileKey;
        public final boolean external;
        public final AndroidControllerAxisLayout axisLayout;

        public DeviceDescriptor(int deviceId, String profileKey, boolean external,
                                AndroidControllerAxisLayout axisLayout) {
            this.deviceId = deviceId;
            this.profileKey = profileKey;
            this.external = external;
            this.axisLayout = axisLayout != null ? axisLayout : AndroidControllerAxisLayout.STANDARD;
        }
    }

    public static final class MotionSample {
        public final DeviceDescriptor device;
        public final float mainX;
        public final float mainY;
        public final float cX;
        public final float cY;
        public final float leftTrigger;
        public final float rightTrigger;
        public final float hatX;
        public final float hatY;
        public final long eventTimeMs;

        public MotionSample(DeviceDescriptor device,
                            float mainX, float mainY,
                            float cX, float cY,
                            float leftTrigger, float rightTrigger,
                            float hatX, float hatY,
                            long eventTimeMs) {
            this.device = device;
            this.mainX = mainX;
            this.mainY = mainY;
            this.cX = cX;
            this.cY = cY;
            this.leftTrigger = leftTrigger;
            this.rightTrigger = rightTrigger;
            this.hatX = hatX;
            this.hatY = hatY;
            this.eventTimeMs = eventTimeMs;
        }
    }

    private static final int NO_DEVICE = Integer.MIN_VALUE;
    // Deflection from raw resting baseline that indicates the onboard handheld
    // sticks (e.g. AYN Thor's OdinMappingInput, whose raw range is ~±0.25) are
    // actively being moved.
    private static final float RAW_ACTIVE_THRESHOLD = 0.035f;
    private static final float RAW_IDLE_THRESHOLD = 0.025f;
    // Deflection on a MotionEvent stick while raw is neutral that claims stick
    // ownership for an external USB/BT controller. Well below Melee's ~0.173
    // deadzone so even gentle walks/tilts switch immediately on frame 0.
    private static final float MOTION_CLAIM_THRESHOLD = 0.08f;
    private static final long RAW_RECENT_WINDOW_MS = 120L;
    private static final long RAW_CORRELATION_WINDOW_MS = 50L;
    private static final float ANALOG_TRIGGER_SEEN_EPSILON = 0.01f;

    private final Context appContext;
    private final ProfileSource profile;
    private final GameCubePadState padState;
    private final StickCalibration.Out stickOut = new StickCalibration.Out();
    private final boolean hasBuiltInControls;

    private boolean rawProviderEnabled;
    private boolean rawProviderSaturated;
    private StickCalibration builtInMainCal;
    private StickCalibration builtInCCal;
    private ButtonMap builtInButtonMap;

    private final Map<Integer, DeviceDescriptor> deviceCache = new HashMap<>();
    private final Map<String, StickCalibration> mainCalCache = new HashMap<>();
    private final Map<String, StickCalibration> cCalCache = new HashMap<>();
    private final Map<String, ButtonMap> buttonMapCache = new HashMap<>();
    private final Set<Integer> rawBackedDeviceIds = new HashSet<>();
    private final Set<Integer> seenAnalogLeftTriggerDevices = new HashSet<>();
    private final Set<Integer> seenAnalogRightTriggerDevices = new HashSet<>();

    // Stick ownership state: when activeMotionStickDeviceId == NO_DEVICE, raw
    // provider (if enabled) owns the sticks; otherwise that specific MotionEvent
    // device owns the sticks and idle raw polls will not overwrite padState.
    private int activeMotionStickDeviceId = NO_DEVICE;
    private int lastMotionStickEventDeviceId = NO_DEVICE;
    private long lastMotionStickEventTimeMs = Long.MIN_VALUE;

    private boolean rawBaselineInitialized;
    private float rawBaseMainX;
    private float rawBaseMainY;
    private float rawBaseCX;
    private float rawBaseCY;
    private float lastRawDeflection;
    private long lastRawActiveTimeMs = Long.MIN_VALUE;

    public AndroidGamepadInputRouter(Context context,
                                     ControllerProfile profile,
                                     GameCubePadState padState,
                                     boolean hasBuiltInControls,
                                     boolean rawProviderEnabled,
                                     boolean rawProviderSaturated) {
        this(context, wrapProfile(profile), padState, hasBuiltInControls,
                rawProviderEnabled, rawProviderSaturated);
    }

    public AndroidGamepadInputRouter(ProfileSource profile,
                                     GameCubePadState padState,
                                     boolean hasBuiltInControls,
                                     boolean rawProviderEnabled,
                                     boolean rawProviderSaturated) {
        this(null, profile, padState, hasBuiltInControls,
                rawProviderEnabled, rawProviderSaturated);
    }

    private AndroidGamepadInputRouter(Context context,
                                      ProfileSource profile,
                                      GameCubePadState padState,
                                      boolean hasBuiltInControls,
                                      boolean rawProviderEnabled,
                                      boolean rawProviderSaturated) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.profile = profile;
        this.padState = padState;
        this.hasBuiltInControls = hasBuiltInControls || rawProviderEnabled;
        this.rawProviderEnabled = rawProviderEnabled;
        this.rawProviderSaturated = rawProviderSaturated;
        loadBuiltInProfiles();
    }

    private static ProfileSource wrapProfile(final ControllerProfile cp) {
        return new ProfileSource() {
            @Override
            public boolean hasCalibration(String deviceKey, ControllerProfile.Stick stick) {
                return cp.hasCalibration(deviceKey, stick);
            }

            @Override
            public StickCalibration getStick(String deviceKey, ControllerProfile.Stick stick) {
                return cp.getStick(deviceKey, stick);
            }

            @Override
            public boolean hasButtonMap(String deviceKey) {
                return cp.hasButtonMap(deviceKey);
            }

            @Override
            public ButtonMap getButtonMap(String deviceKey) {
                return cp.getButtonMap(deviceKey);
            }
        };
    }

    private void loadBuiltInProfiles() {
        builtInMainCal = profile.getStick(ControllerProfile.DEVICE_BUILTIN,
                ControllerProfile.Stick.MAIN);
        builtInCCal = profile.getStick(ControllerProfile.DEVICE_BUILTIN,
                ControllerProfile.Stick.C);
        builtInButtonMap = profile.getButtonMap(ControllerProfile.DEVICE_BUILTIN);

        if (rawProviderEnabled && rawProviderSaturated) {
            if (!profile.hasCalibration(ControllerProfile.DEVICE_BUILTIN,
                    ControllerProfile.Stick.MAIN)) {
                builtInMainCal = StickCalibration.compensatingMain();
            }
            if (!profile.hasCalibration(ControllerProfile.DEVICE_BUILTIN,
                    ControllerProfile.Stick.C)) {
                builtInCCal = StickCalibration.compensatingC();
            }
        }
        if (!rawProviderEnabled) {
            builtInMainCal = builtInMainCal.withOuterScaleEnabled(false);
            builtInCCal = builtInCCal.withOuterScaleEnabled(false);
        }
    }

    public synchronized StickCalibration getBuiltInMainCalibration() {
        return builtInMainCal;
    }

    public synchronized StickCalibration getBuiltInCCalibration() {
        return builtInCCal;
    }

    public synchronized void onRawProviderLost() {
        rawProviderEnabled = false;
        rawProviderSaturated = false;
        builtInMainCal = builtInMainCal.withOuterScaleEnabled(false);
        builtInCCal = builtInCCal.withOuterScaleEnabled(false);
        activeMotionStickDeviceId = NO_DEVICE;
        rawBackedDeviceIds.clear();
    }

    public synchronized void onDeviceDisconnected(int deviceId) {
        deviceCache.remove(deviceId);
        rawBackedDeviceIds.remove(deviceId);
        seenAnalogLeftTriggerDevices.remove(deviceId);
        seenAnalogRightTriggerDevices.remove(deviceId);
        if (activeMotionStickDeviceId == deviceId) {
            activeMotionStickDeviceId = NO_DEVICE;
            padState.setMainStickCentered();
            padState.setCStickCentered();
        }
    }

    public synchronized int getActiveMotionStickDeviceId() {
        return activeMotionStickDeviceId;
    }

    /**
     * Handle a raw handheld stick poll snapshot. Returns true if padState was
     * updated and should be pushed to native; returns false if an external
     * MotionEvent controller currently owns the sticks and raw sticks are idle.
     */
    public synchronized boolean handleRawStickSnapshot(RawStickState state, long nowMs) {
        if (!rawProviderEnabled || state == null) return false;

        Float mainX = state.valueForAxis(MotionEvent.AXIS_X);
        Float mainY = state.valueForAxis(MotionEvent.AXIS_Y);
        Float cX = state.valueForAxis(MotionEvent.AXIS_Z);
        Float cY = state.valueForAxis(MotionEvent.AXIS_RZ);
        if ((mainX == null || mainY == null) && (cX == null || cY == null)) {
            return false;
        }

        float mx = mainX != null ? mainX : 0f;
        float my = mainY != null ? mainY : 0f;
        float rx = cX != null ? cX : 0f;
        float ry = cY != null ? cY : 0f;

        if (!rawBaselineInitialized) {
            // Seed rest baseline only when sticks look genuinely centered
            // (OdinMappingInput's full throw is ~±0.25, so 0.10 is already a
            // real stick deflection, not rest drift).
            if (maxAbs(mx, my, rx, ry) < 0.04f) {
                rawBaseMainX = mx;
                rawBaseMainY = my;
                rawBaseCX = rx;
                rawBaseCY = ry;
            }
            rawBaselineInitialized = true;
        }

        float deflection = maxAbs(
                mx - rawBaseMainX, my - rawBaseMainY,
                rx - rawBaseCX, ry - rawBaseCY);
        lastRawDeflection = deflection;

        if (deflection >= RAW_ACTIVE_THRESHOLD) {
            lastRawActiveTimeMs = nowMs;
            // If a MotionEvent device moved right before raw moved, that device
            // is the onboard handheld pad that shares these raw sensors.
            if (lastMotionStickEventDeviceId != NO_DEVICE
                    && nowMs - lastMotionStickEventTimeMs <= RAW_CORRELATION_WINDOW_MS) {
                rawBackedDeviceIds.add(lastMotionStickEventDeviceId);
            }
            activeMotionStickDeviceId = NO_DEVICE;
        }

        // An external USB/BT controller currently owns the sticks and the
        // onboard sticks are idle — do NOT stomp the external stick with (0, 0).
        if (activeMotionStickDeviceId != NO_DEVICE) {
            return false;
        }

        if (mainX != null && mainY != null) {
            applyStickPair(mx, my, builtInMainCal, true);
        }
        if (cX != null && cY != null) {
            applyStickPair(rx, ry, builtInCCal, false);
        }
        return true;
    }

    /**
     * Handle an Android KeyEvent. Returns the GC button bit if consumed (or 0
     * if this key should pass through to super.dispatchKeyEvent).
     */
    public synchronized int handleKeyEvent(KeyEvent event) {
        DeviceDescriptor desc = resolveDevice(event.getDevice(), event.getDeviceId());
        return handleKeyEvent(desc, event.getKeyCode(), event.getAction());
    }

    public synchronized int handleKeyEvent(DeviceDescriptor desc, int keyCode, int action) {
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return 0;
        }
        ButtonMap map = buttonMapForDevice(desc);
        int bit = map.gcBitForKey(keyCode);
        if (bit == 0) return 0;

        boolean pressed = (action == KeyEvent.ACTION_DOWN);
        // When L2/R2 is mapped to GC_TRIG_L/R on a controller that has analog
        // triggers, prevent premature synthetic L2/R2 key events from overriding
        // analog light/mid shields into full digital shields.
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2 && bit == ButtonMap.GC_TRIG_L) {
            boolean analogOwned = desc.axisLayout.hasAnalogLeftTrigger
                    && seenAnalogLeftTriggerDevices.contains(desc.deviceId);
            padState.setTriggerKeyL2(pressed, analogOwned);
            return bit;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2 && bit == ButtonMap.GC_TRIG_R) {
            boolean analogOwned = desc.axisLayout.hasAnalogRightTrigger
                    && seenAnalogRightTriggerDevices.contains(desc.deviceId);
            padState.setTriggerKeyR2(pressed, analogOwned);
            return bit;
        }

        padState.setButton(bit, pressed);
        return bit;
    }

    /**
     * Handle an Android MotionEvent from a joystick / gamepad source.
     */
    public synchronized void handleMotionEvent(MotionEvent ev, long nowMs) {
        DeviceDescriptor desc = resolveDevice(ev.getDevice(), ev.getDeviceId());
        AndroidControllerAxisLayout layout = desc.axisLayout;
        float mainX = ev.getAxisValue(layout.mainX);
        float mainY = ev.getAxisValue(layout.mainY);
        float cX = ev.getAxisValue(layout.cX);
        float cY = ev.getAxisValue(layout.cY);
        float lt = layout.readLeftTrigger(ev);
        float rt = layout.readRightTrigger(ev);
        float hx = layout.hasHat ? ev.getAxisValue(MotionEvent.AXIS_HAT_X) : 0f;
        float hy = layout.hasHat ? ev.getAxisValue(MotionEvent.AXIS_HAT_Y) : 0f;
        handleMotionSample(new MotionSample(desc, mainX, mainY, cX, cY, lt, rt, hx, hy, nowMs));
    }

    public synchronized void handleMotionSample(MotionSample sample) {
        DeviceDescriptor desc = sample.device;
        AndroidControllerAxisLayout layout = desc.axisLayout;
        long nowMs = sample.eventTimeMs;

        float stickDeflection = maxAbs(sample.mainX, sample.mainY, sample.cX, sample.cY);
        if (stickDeflection >= MOTION_CLAIM_THRESHOLD) {
            lastMotionStickEventDeviceId = desc.deviceId;
            lastMotionStickEventTimeMs = nowMs;
        }

        boolean shouldUseMotionSticks;
        if (!rawProviderEnabled) {
            shouldUseMotionSticks = true;
            activeMotionStickDeviceId = desc.deviceId;
        } else if (activeMotionStickDeviceId == desc.deviceId) {
            shouldUseMotionSticks = true;
        } else {
            boolean rawRecentlyActive = lastRawDeflection >= RAW_IDLE_THRESHOLD
                    || (lastRawActiveTimeMs != Long.MIN_VALUE
                            && nowMs - lastRawActiveTimeMs < RAW_RECENT_WINDOW_MS);
            if (rawRecentlyActive) {
                // Onboard raw provider is actively moving right now; this
                // MotionEvent is almost certainly the mirrored onboard pad.
                if (stickDeflection >= MOTION_CLAIM_THRESHOLD) {
                    rawBackedDeviceIds.add(desc.deviceId);
                }
                shouldUseMotionSticks = false;
            } else if (!rawBackedDeviceIds.contains(desc.deviceId)
                    && stickDeflection >= MOTION_CLAIM_THRESHOLD) {
                // External USB/BT controller stick moved while onboard raw
                // sticks remained neutral: switch ownership immediately.
                activeMotionStickDeviceId = desc.deviceId;
                shouldUseMotionSticks = true;
            } else {
                shouldUseMotionSticks = false;
            }
        }

        if (shouldUseMotionSticks) {
            StickCalibration mainCal = stickCalibrationForDevice(desc, ControllerProfile.Stick.MAIN);
            StickCalibration cCal = stickCalibrationForDevice(desc, ControllerProfile.Stick.C);
            applyStickPair(sample.mainX, sample.mainY, mainCal, true);
            applyStickPair(sample.cX, sample.cY, cCal, false);
        }

        if (layout.hasAnalogLeftTrigger) {
            if (sample.leftTrigger > ANALOG_TRIGGER_SEEN_EPSILON) {
                seenAnalogLeftTriggerDevices.add(desc.deviceId);
            }
            padState.setAnalogTriggerL(sample.leftTrigger);
        }
        if (layout.hasAnalogRightTrigger) {
            if (sample.rightTrigger > ANALOG_TRIGGER_SEEN_EPSILON) {
                seenAnalogRightTriggerDevices.add(desc.deviceId);
            }
            padState.setAnalogTriggerR(sample.rightTrigger);
        }

        if (layout.hasHat) {
            padState.setHat(sample.hatX, sample.hatY);
        }
    }

    private void applyStickPair(float rawX, float rawY, StickCalibration cal, boolean main) {
        cal.apply(rawX, rawY, stickOut);
        float fx = stickOut.x;
        // Melee float/byte stick convention has positive Y = up; Android AXIS_Y
        // and AXIS_RZ have positive = down.
        float fy = -stickOut.y;
        if (main) {
            padState.setMainStick(fx, fy);
        } else {
            padState.setCStick(fx, fy);
        }
    }

    private DeviceDescriptor resolveDevice(InputDevice device, int deviceId) {
        DeviceDescriptor cached = deviceCache.get(deviceId);
        if (cached != null) return cached;

        String key = ControllerProfile.androidDeviceKey(device);
        boolean external = AndroidControllers.isDeviceExternal(device) || isUsbAttached(device);
        AndroidControllerAxisLayout layout = AndroidControllerAxisLayout.resolve(device);
        DeviceDescriptor created = new DeviceDescriptor(deviceId, key, external, layout);
        deviceCache.put(deviceId, created);
        return created;
    }

    private boolean isUsbAttached(InputDevice device) {
        if (appContext == null || device == null) return false;
        int vid = device.getVendorId();
        int pid = device.getProductId();
        if (vid == 0 && pid == 0) return false;
        UsbManager manager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
        if (manager == null) return false;
        try {
            for (UsbDevice usb : manager.getDeviceList().values()) {
                if (usb.getVendorId() == vid && usb.getProductId() == pid) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isBuiltInDevice(DeviceDescriptor desc) {
        if (desc == null || ControllerProfile.DEVICE_BUILTIN.equals(desc.profileKey)) {
            return true;
        }
        if (rawBackedDeviceIds.contains(desc.deviceId)) {
            return true;
        }
        // When a handheld raw stick provider is active and this device claimed
        // MotionEvent stick ownership independently of raw sticks, treat it as
        // an external controller even if the OEM ROM flagged isExternal=false.
        if (rawProviderEnabled && activeMotionStickDeviceId == desc.deviceId) {
            return false;
        }
        return !desc.external;
    }

    private StickCalibration stickCalibrationForDevice(DeviceDescriptor desc,
                                                       ControllerProfile.Stick stick) {
        if (isBuiltInDevice(desc) && !profile.hasCalibration(desc.profileKey, stick)) {
            return stick == ControllerProfile.Stick.MAIN
                    ? builtInMainCal.withOuterScaleEnabled(false)
                    : builtInCCal.withOuterScaleEnabled(false);
        }

        Map<String, StickCalibration> cache = stick == ControllerProfile.Stick.MAIN
                ? mainCalCache : cCalCache;
        StickCalibration cached = cache.get(desc.profileKey);
        if (cached != null) return cached;

        StickCalibration resolved;
        if (profile.hasCalibration(desc.profileKey, stick)) {
            resolved = profile.getStick(desc.profileKey, stick).withOuterScaleEnabled(false);
        } else if (!hasBuiltInControls
                && profile.hasCalibration(ControllerProfile.DEVICE_BUILTIN, stick)) {
            // Backwards compatibility for phones with no onboard gamepad where
            // the user previously calibrated under "This device's controls".
            resolved = profile.getStick(ControllerProfile.DEVICE_BUILTIN, stick)
                    .withOuterScaleEnabled(false);
        } else {
            resolved = StickCalibration.defaultsForExternal();
        }
        cache.put(desc.profileKey, resolved);
        return resolved;
    }

    private ButtonMap buttonMapForDevice(DeviceDescriptor desc) {
        if (desc == null || ControllerProfile.DEVICE_BUILTIN.equals(desc.profileKey)) {
            return builtInButtonMap;
        }
        ButtonMap cached = buttonMapCache.get(desc.profileKey);
        if (cached != null) return cached;

        ButtonMap resolved;
        if (profile.hasButtonMap(desc.profileKey)) {
            resolved = profile.getButtonMap(desc.profileKey);
        } else if (isBuiltInDevice(desc)
                || (!hasBuiltInControls && profile.hasButtonMap(ControllerProfile.DEVICE_BUILTIN))) {
            resolved = builtInButtonMap;
        } else {
            resolved = ButtonMap.defaults();
        }
        buttonMapCache.put(desc.profileKey, resolved);
        return resolved;
    }

    private static float maxAbs(float a, float b, float c, float d) {
        return Math.max(Math.max(Math.abs(a), Math.abs(b)), Math.max(Math.abs(c), Math.abs(d)));
    }
}
