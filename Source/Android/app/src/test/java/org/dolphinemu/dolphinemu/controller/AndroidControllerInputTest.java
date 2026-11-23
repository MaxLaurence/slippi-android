package org.dolphinemu.dolphinemu.controller;

import android.view.KeyEvent;
import android.view.MotionEvent;

import org.dolphinemu.dolphinemu.utils.RawStickState;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class AndroidControllerInputTest {

    private static final class RecordingSink implements GameCubePadState.NativeSink {
        int buttons;
        int stickX = 128;
        int stickY = 128;
        int substickX = 128;
        int substickY = 128;
        int triggerL;
        int triggerR;
        int pushCount;

        @Override
        public void setPadOverride(int port, int buttons,
                                   int stickX, int stickY, int substickX, int substickY,
                                   int triggerL, int triggerR, int analogA, int analogB) {
            this.buttons = buttons;
            this.stickX = stickX;
            this.stickY = stickY;
            this.substickX = substickX;
            this.substickY = substickY;
            this.triggerL = triggerL;
            this.triggerR = triggerR;
            this.pushCount++;
        }

        @Override
        public void setMeleePadFloats(int port, float mainX, float mainY, float cX, float cY) {
        }
    }

    private static final class InMemoryProfiles implements AndroidGamepadInputRouter.ProfileSource {
        final Map<String, StickCalibration> sticks = new HashMap<>();
        final Map<String, ButtonMap> maps = new HashMap<>();

        void putStick(String key, ControllerProfile.Stick stick, StickCalibration cal) {
            sticks.put(key + "/" + stick.name(), cal);
        }

        void putButtonMap(String key, ButtonMap map) {
            maps.put(key, map);
        }

        @Override
        public boolean hasCalibration(String deviceKey, ControllerProfile.Stick stick) {
            return sticks.containsKey(deviceKey + "/" + stick.name());
        }

        @Override
        public StickCalibration getStick(String deviceKey, ControllerProfile.Stick stick) {
            StickCalibration saved = sticks.get(deviceKey + "/" + stick.name());
            if (saved != null) return saved;
            if (ControllerProfile.isAndroidExternalDeviceKey(deviceKey)) {
                return StickCalibration.defaultsForExternal();
            }
            return stick == ControllerProfile.Stick.MAIN
                    ? StickCalibration.defaultsForMain()
                    : StickCalibration.defaultsForC();
        }

        @Override
        public boolean hasButtonMap(String deviceKey) {
            return maps.containsKey(deviceKey);
        }

        @Override
        public ButtonMap getButtonMap(String deviceKey) {
            ButtonMap saved = maps.get(deviceKey);
            return saved != null ? saved : ButtonMap.defaults();
        }
    }

    private static AndroidGamepadInputRouter.DeviceDescriptor hayboxDescriptor(int deviceId) {
        String key = ControllerProfile.androidDeviceKey(
                0x0738, 0x4726, "Mad Catz Xbox 360 Controller", "desc-haybox");
        return new AndroidGamepadInputRouter.DeviceDescriptor(
                deviceId, key, true, AndroidControllerAxisLayout.STANDARD);
    }

    private static AndroidGamepadInputRouter.DeviceDescriptor thorBuiltinDescriptor(int deviceId) {
        String key = ControllerProfile.androidDeviceKey(
                0x045E, 0x028E, "Odin Controller", "desc-odin");
        return new AndroidGamepadInputRouter.DeviceDescriptor(
                deviceId, key, false, AndroidControllerAxisLayout.STANDARD);
    }

    private static RawStickState rawState(float lx, float ly, float rx, float ry) {
        RawStickState s = new RawStickState();
        s.setLeft(lx, ly);
        s.setRight(rx, ry);
        return s;
    }

    @Test
    public void externalUsbControllerWorksWhenHandheldRawProviderIsActive() {
        InMemoryProfiles profiles = new InMemoryProfiles();
        RecordingSink sink = new RecordingSink();
        GameCubePadState pad = new GameCubePadState(0, sink);
        // Simulate AYN Thor: OdinMappingInput raw provider active + saturated (4x compensating defaults)
        AndroidGamepadInputRouter router = new AndroidGamepadInputRouter(
                profiles, pad, true, true, true);

        // Initial neutral raw polls from OdinMappingInput
        assertTrue(router.handleRawStickSnapshot(rawState(0f, 0f, 0f, 0f), 100L));
        assertTrue(router.handleRawStickSnapshot(rawState(0f, 0f, 0f, 0f), 102L));

        // HayBox (Mad Catz Xbox 360 Controller) sends cardinal Right:
        // (208 - 128) * 65535 / 255 + 128 = 20688 -> 20688 / 32767 = +0.63137f
        AndroidGamepadInputRouter.DeviceDescriptor haybox = hayboxDescriptor(42);
        float hayboxRight = 20688f / 32767f;
        router.handleMotionSample(new AndroidGamepadInputRouter.MotionSample(
                haybox, hayboxRight, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 250L));
        pad.pushToNative();

        // Must immediately yield exact Melee 1.0000 (stickX = 208), NOT 128 (ignored)
        // and NOT 255 (corrupted by Thor's 4x compensating scale) and NOT 191 (warped by 1.5 curve).
        assertEquals(208, sink.stickX);
        assertEquals(128, sink.stickY);

        // Subsequent neutral polls from Thor's onboard OdinMappingInput must NOT overwrite HayBox!
        assertFalse(router.handleRawStickSnapshot(rawState(0f, 0f, 0f, 0f), 252L));
        assertFalse(router.handleRawStickSnapshot(rawState(0.01f, -0.01f, 0f, 0f), 254L));
        assertEquals(208, pad.getStickX());

        // HayBox Mod X horizontal (128 + 53 = 181 -> 0.4157f) and C-stick Right (208 -> 0.63137f)
        float hayboxModX = ((53f * 65535f / 255f) + 128f) / 32767f;
        router.handleMotionSample(new AndroidGamepadInputRouter.MotionSample(
                haybox, hayboxModX, 0f, hayboxRight, 0f, 0f, 0f, 0f, 0f, 260L));
        pad.pushToNative();
        assertEquals(181, sink.stickX);
        assertEquals(208, sink.substickX);

        // HayBox returns to neutral
        router.handleMotionSample(new AndroidGamepadInputRouter.MotionSample(
                haybox, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 270L));
        pad.pushToNative();
        assertEquals(128, sink.stickX);
        assertEquals(128, sink.substickX);

        // Now user moves AYN Thor's onboard stick (raw provider deflects by 0.20, scaled 4x -> 0.80)
        assertTrue(router.handleRawStickSnapshot(rawState(0.20f, 0f, 0f, 0f), 300L));
        pad.pushToNative();
        assertTrue("Thor onboard raw stick should reclaim ownership", sink.stickX > 200);
    }

    @Test
    public void thorOnboardMotionEventDoesNotStealFromRawProvider() {
        InMemoryProfiles profiles = new InMemoryProfiles();
        RecordingSink sink = new RecordingSink();
        GameCubePadState pad = new GameCubePadState(0, sink);
        AndroidGamepadInputRouter router = new AndroidGamepadInputRouter(
                profiles, pad, true, true, true);

        AndroidGamepadInputRouter.DeviceDescriptor thor = thorBuiltinDescriptor(7);

        // Odin raw reports onboard stick movement at 100ms
        assertTrue(router.handleRawStickSnapshot(rawState(0.10f, 0f, 0f, 0f), 100L));
        pad.pushToNative();
        int rawStickX = sink.stickX;

        // 4ms later, Android delivers saturated MotionEvent (1.0f) from the same onboard pad
        router.handleMotionSample(new AndroidGamepadInputRouter.MotionSample(
                thor, 1.0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 104L));
        pad.pushToNative();

        // Must NOT overwrite the unsaturated raw value with MotionEvent's saturated 255!
        assertEquals(rawStickX, sink.stickX);
        assertNotEquals(255, sink.stickX);
    }

    @Test
    public void hayboxLightMidAndFullShieldWithSyntheticR2Key() {
        InMemoryProfiles profiles = new InMemoryProfiles();
        RecordingSink sink = new RecordingSink();
        GameCubePadState pad = new GameCubePadState(0, sink);
        AndroidGamepadInputRouter router = new AndroidGamepadInputRouter(
                profiles, pad, true, true, true);
        AndroidGamepadInputRouter.DeviceDescriptor haybox = hayboxDescriptor(42);

        // Even if Android/OEM synthesizes KEYCODE_BUTTON_R2 slightly BEFORE the analog motion event
        router.handleKeyEvent(haybox, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.ACTION_DOWN);
        router.handleMotionSample(new AndroidGamepadInputRouter.MotionSample(
                haybox, 0f, 0f, 0f, 0f, 0f, 49f / 255f, 0f, 0f, 100L));
        pad.pushToNative();

        // Light shield: analog 49, digital GC_TRIG_R must be OFF!
        assertEquals(49, sink.triggerR);
        assertEquals(0, sink.buttons & ButtonMap.GC_TRIG_R);

        // Release
        router.handleMotionSample(new AndroidGamepadInputRouter.MotionSample(
                haybox, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 120L));
        router.handleKeyEvent(haybox, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.ACTION_UP);
        pad.pushToNative();
        assertEquals(0, sink.triggerR);
        assertEquals(0, sink.buttons & ButtonMap.GC_TRIG_R);

        // Mid shield (94/255) with synthetic R2 key
        router.handleKeyEvent(haybox, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.ACTION_DOWN);
        router.handleMotionSample(new AndroidGamepadInputRouter.MotionSample(
                haybox, 0f, 0f, 0f, 0f, 0f, 94f / 255f, 0f, 0f, 140L));
        pad.pushToNative();
        assertEquals(94, sink.triggerR);
        assertEquals(0, sink.buttons & ButtonMap.GC_TRIG_R);

        // Full shield (255/255) even WITHOUT any KEYCODE_BUTTON_R2 key event!
        router.handleMotionSample(new AndroidGamepadInputRouter.MotionSample(
                haybox, 0f, 0f, 0f, 0f, 0f, 1.0f, 0f, 0f, 160L));
        pad.pushToNative();
        assertEquals(255, sink.triggerR);
        assertEquals(ButtonMap.GC_TRIG_R, sink.buttons & ButtonMap.GC_TRIG_R);

        // L1 bumper still triggers digital GC_TRIG_L immediately
        router.handleKeyEvent(haybox, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.ACTION_DOWN);
        pad.pushToNative();
        assertEquals(ButtonMap.GC_TRIG_L, sink.buttons & ButtonMap.GC_TRIG_L);
        assertEquals(255, sink.triggerL);
    }

    @Test
    public void digitalOnlyControllerL2R2StillTriggersDigitalShield() {
        InMemoryProfiles profiles = new InMemoryProfiles();
        RecordingSink sink = new RecordingSink();
        GameCubePadState pad = new GameCubePadState(0, sink);
        AndroidGamepadInputRouter router = new AndroidGamepadInputRouter(
                profiles, pad, false, false, false);

        // Switch Pro style: no analog trigger axes
        Map<Integer, AndroidControllerAxisLayout.AxisRange> ranges = new HashMap<>();
        ranges.put(MotionEvent.AXIS_X, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.05f));
        ranges.put(MotionEvent.AXIS_Y, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.05f));
        ranges.put(MotionEvent.AXIS_Z, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.05f));
        ranges.put(MotionEvent.AXIS_RZ, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.05f));
        AndroidControllerAxisLayout switchLayout = AndroidControllerAxisLayout.resolve(
                new AndroidControllerAxisLayout.Capabilities("Nintendo Switch Pro Controller", ranges));
        assertFalse(switchLayout.hasAnalogLeftTrigger);
        assertFalse(switchLayout.hasAnalogRightTrigger);

        AndroidGamepadInputRouter.DeviceDescriptor switchPro =
                new AndroidGamepadInputRouter.DeviceDescriptor(
                        15, "android:057e:2009:nintendo_switch_pro_controller", true, switchLayout);

        router.handleKeyEvent(switchPro, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.ACTION_DOWN);
        router.handleKeyEvent(switchPro, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.ACTION_DOWN);
        pad.pushToNative();
        assertEquals(ButtonMap.GC_TRIG_L | ButtonMap.GC_TRIG_R,
                sink.buttons & (ButtonMap.GC_TRIG_L | ButtonMap.GC_TRIG_R));
        assertEquals(255, sink.triggerL);
        assertEquals(255, sink.triggerR);
    }

    @Test
    public void axisLayoutDetectsGenericKlXpadAndRxRyCStick() {
        // Case 1: Mad Catz Xbox 360 / HayBox falling back to Generic.kl
        // ABS_Z/RZ -> AXIS_Z/RZ ([-1, 1], flat=0), ABS_RX/RY -> AXIS_RX/RY (flat>0)
        Map<Integer, AndroidControllerAxisLayout.AxisRange> xpadRanges = new HashMap<>();
        xpadRanges.put(MotionEvent.AXIS_X, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.004f));
        xpadRanges.put(MotionEvent.AXIS_Y, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.004f));
        xpadRanges.put(MotionEvent.AXIS_Z, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0f));
        xpadRanges.put(MotionEvent.AXIS_RZ, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0f));
        xpadRanges.put(MotionEvent.AXIS_RX, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.004f));
        xpadRanges.put(MotionEvent.AXIS_RY, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.004f));
        AndroidControllerAxisLayout xpadLayout = AndroidControllerAxisLayout.resolve(
                new AndroidControllerAxisLayout.Capabilities("Mad Catz Xbox 360 Controller", xpadRanges));

        assertEquals(MotionEvent.AXIS_RX, xpadLayout.cX);
        assertEquals(MotionEvent.AXIS_RY, xpadLayout.cY);
        assertEquals(MotionEvent.AXIS_Z, xpadLayout.leftTriggerAxis);
        assertEquals(MotionEvent.AXIS_RZ, xpadLayout.rightTriggerAxis);
        assertEquals(0.0f, xpadLayout.normalizeLeftTrigger(-1.0f, 0f), 0.001f);
        assertEquals(0.5f, xpadLayout.normalizeLeftTrigger(0.0f, 0f), 0.001f);
        assertEquals(1.0f, xpadLayout.normalizeLeftTrigger(1.0f, 0f), 0.001f);

        // Case 2: Generic USB GC controller exposing C-stick on RX/RY with no Z/RZ
        Map<Integer, AndroidControllerAxisLayout.AxisRange> usbGcRanges = new HashMap<>();
        usbGcRanges.put(MotionEvent.AXIS_X, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.05f));
        usbGcRanges.put(MotionEvent.AXIS_Y, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.05f));
        usbGcRanges.put(MotionEvent.AXIS_RX, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.05f));
        usbGcRanges.put(MotionEvent.AXIS_RY, new AndroidControllerAxisLayout.AxisRange(-1f, 1f, 0.05f));
        AndroidControllerAxisLayout usbGcLayout = AndroidControllerAxisLayout.resolve(
                new AndroidControllerAxisLayout.Capabilities("USB GameCube Controller", usbGcRanges));

        assertEquals(MotionEvent.AXIS_RX, usbGcLayout.cX);
        assertEquals(MotionEvent.AXIS_RY, usbGcLayout.cY);
    }

    @Test
    public void motionEventsDoNotClobberDigitalDpadKeys() {
        InMemoryProfiles profiles = new InMemoryProfiles();
        RecordingSink sink = new RecordingSink();
        GameCubePadState pad = new GameCubePadState(0, sink);
        AndroidGamepadInputRouter router = new AndroidGamepadInputRouter(
                profiles, pad, false, false, false);
        AndroidGamepadInputRouter.DeviceDescriptor haybox = hayboxDescriptor(42);

        router.handleKeyEvent(haybox, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN);
        // Stick moves while hat axes report (0, 0)
        router.handleMotionSample(new AndroidGamepadInputRouter.MotionSample(
                haybox, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 100L));
        pad.pushToNative();

        assertEquals(ButtonMap.GC_BTN_UP, sink.buttons & ButtonMap.GC_BTN_UP);
    }

    @Test
    public void perDeviceButtonMapIsolatesExternalFromBuiltIn() {
        InMemoryProfiles profiles = new InMemoryProfiles();
        ButtonMap builtInMap = ButtonMap.defaults();
        builtInMap.bind(KeyEvent.KEYCODE_BUTTON_A, ButtonMap.GC_BTN_B);
        profiles.putButtonMap(ControllerProfile.DEVICE_BUILTIN, builtInMap);

        String hayboxKey = ControllerProfile.androidDeviceKey(
                0x0738, 0x4726, "Mad Catz Xbox 360 Controller", "desc-1");
        String hayboxKeyAfterReplug = ControllerProfile.androidDeviceKey(
                0x0738, 0x4726, "Mad Catz Xbox 360 Controller", "desc-2-different-port");
        assertEquals(hayboxKey, hayboxKeyAfterReplug);

        ButtonMap hayboxMap = ButtonMap.defaults();
        hayboxMap.bind(KeyEvent.KEYCODE_BUTTON_Y, ButtonMap.GC_BTN_X);
        profiles.putButtonMap(hayboxKey, hayboxMap);

        RecordingSink sink = new RecordingSink();
        GameCubePadState pad = new GameCubePadState(0, sink);
        AndroidGamepadInputRouter router = new AndroidGamepadInputRouter(
                profiles, pad, true, true, true);

        AndroidGamepadInputRouter.DeviceDescriptor haybox = hayboxDescriptor(42);
        // Pressing A on HayBox uses HayBox's map (GC_BTN_A), NOT Thor's remapped GC_BTN_B
        router.handleKeyEvent(haybox, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN);
        // Pressing Y on HayBox uses HayBox's custom remap (GC_BTN_X)
        router.handleKeyEvent(haybox, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.ACTION_DOWN);
        pad.pushToNative();
        assertEquals(ButtonMap.GC_BTN_A | ButtonMap.GC_BTN_X, sink.buttons);
    }
}
