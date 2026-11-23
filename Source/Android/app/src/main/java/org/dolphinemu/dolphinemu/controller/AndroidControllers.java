package org.dolphinemu.dolphinemu.controller;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.view.InputDevice;
import android.view.MotionEvent;

import org.dolphinemu.dolphinemu.utils.RawStickInputProvider;
import org.dolphinemu.dolphinemu.utils.RawStickInputProviders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Discovers connected Android InputDevice gamepads (USB, Bluetooth, and
 * onboard handheld pads) and produces stable profile entries for Settings,
 * CalibrationActivity, ButtonMapActivity, and launcher status displays.
 */
public final class AndroidControllers {

    public static final class DeviceInfo {
        public final int inputDeviceId;
        public final String profileKey;
        public final String label;
        public final String rawName;
        public final int vendorId;
        public final int productId;
        public final boolean external;
        public final boolean usbAttached;

        public DeviceInfo(int inputDeviceId, String profileKey, String label, String rawName,
                          int vendorId, int productId, boolean external, boolean usbAttached) {
            this.inputDeviceId = inputDeviceId;
            this.profileKey = profileKey;
            this.label = label;
            this.rawName = rawName;
            this.vendorId = vendorId;
            this.productId = productId;
            this.external = external;
            this.usbAttached = usbAttached;
        }
    }

    public static final class ChooserEntry {
        public final String deviceKey;
        public final String label;

        public ChooserEntry(String deviceKey, String label) {
            this.deviceKey = deviceKey;
            this.label = label;
        }
    }

    private AndroidControllers() {
    }

    public static boolean isGamepadOrJoystick(InputDevice device) {
        if (device == null || device.isVirtual()) return false;
        int sources = device.getSources();
        boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (gamepad) return true;
        if (!joystick) return false;
        return hasAnyStickOrHatAxis(device);
    }

    private static boolean hasAnyStickOrHatAxis(InputDevice device) {
        int[] axes = {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        };
        for (int axis : axes) {
            if (device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null
                    || device.getMotionRange(axis) != null) {
                return true;
            }
        }
        return false;
    }

    public static List<DeviceInfo> listConnectedDevices(Context context) {
        Set<Long> usbVidPids = connectedUsbVidPids(context);
        List<DeviceInfo> raw = new ArrayList<>();
        Map<String, Integer> nameCounts = new HashMap<>();

        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (!isGamepadOrJoystick(device)) continue;
            String rawName = device.getName();
            String displayName = (rawName == null || rawName.trim().isEmpty())
                    ? "USB / Bluetooth controller"
                    : rawName.trim();
            int vid = device.getVendorId();
            int pid = device.getProductId();
            boolean usbAttached = (vid != 0 || pid != 0) && usbVidPids.contains(vidPidKey(vid, pid));
            boolean external = isDeviceExternal(device) || usbAttached;
            String key = ControllerProfile.androidDeviceKey(device);

            nameCounts.put(displayName, nameCounts.getOrDefault(displayName, 0) + 1);
            raw.add(new DeviceInfo(id, key, displayName, rawName == null ? "" : rawName,
                    vid, pid, external, usbAttached));
        }

        // Deduplicate by profileKey so identical dual-interface descriptors
        // don't appear twice in the chooser, while keeping distinct controllers
        // clearly labeled.
        Map<String, DeviceInfo> unique = new LinkedHashMap<>();
        for (DeviceInfo info : raw) {
            DeviceInfo existing = unique.get(info.profileKey);
            if (existing == null || (!existing.external && info.external)) {
                String label = info.label;
                if (nameCounts.getOrDefault(info.label, 0) > 1
                        && (info.vendorId != 0 || info.productId != 0)) {
                    label = String.format(Locale.US, "%s (%04X:%04X)",
                            info.label, info.vendorId & 0xFFFF, info.productId & 0xFFFF);
                }
                unique.put(info.profileKey, new DeviceInfo(
                        info.inputDeviceId, info.profileKey, label, info.rawName,
                        info.vendorId, info.productId, info.external, info.usbAttached));
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Controllers that should appear alongside "This device's controls" in
     * stick calibration and button remap choosers.
     */
    public static List<DeviceInfo> listSelectableExternalDevices(Context context) {
        List<DeviceInfo> all = listConnectedDevices(context);
        List<DeviceInfo> external = new ArrayList<>();
        for (DeviceInfo info : all) {
            if (info.external) {
                external.add(info);
            }
        }
        // If multiple internal-flagged gamepads are attached (rare OEM quirk
        // where a USB gamepad reports isExternal=false), expose all of them so
        // the user can still select their USB controller.
        if (external.isEmpty() && all.size() > 1) {
            return all;
        }
        return external;
    }

    public static boolean hasBuiltInGamepadControls(Context context) {
        RawStickInputProvider raw = RawStickInputProviders.create(context);
        if (raw != null) return true;
        for (DeviceInfo info : listConnectedDevices(context)) {
            if (!info.external) return true;
        }
        return false;
    }

    public static List<ChooserEntry> buildCalibrationChooserEntries(Context context,
                                                                    String builtInLabel) {
        return buildChooserEntries(context, builtInLabel, false, null);
    }

    public static List<ChooserEntry> buildRemapChooserEntries(Context context,
                                                              String builtInLabel,
                                                              boolean includeAdapterPorts,
                                                              AdapterPortLabeler portLabeler) {
        return buildChooserEntries(context, builtInLabel, includeAdapterPorts, portLabeler);
    }

    public interface AdapterPortLabeler {
        String labelForPort(int oneBasedPort);
    }

    private static List<ChooserEntry> buildChooserEntries(Context context,
                                                          String builtInLabel,
                                                          boolean includeAdapterPorts,
                                                          AdapterPortLabeler portLabeler) {
        List<ChooserEntry> entries = new ArrayList<>();
        List<DeviceInfo> external = listSelectableExternalDevices(context);
        boolean hasBuiltIn = hasBuiltInGamepadControls(context);

        if (hasBuiltIn || external.isEmpty()) {
            entries.add(new ChooserEntry(ControllerProfile.DEVICE_BUILTIN, builtInLabel));
            for (DeviceInfo info : external) {
                entries.add(new ChooserEntry(info.profileKey, info.label));
            }
        } else {
            // On a phone/tablet without onboard controls, show connected
            // external controllers first so the user's USB/BT controller is
            // immediately highlighted.
            for (DeviceInfo info : external) {
                entries.add(new ChooserEntry(info.profileKey, info.label));
            }
            entries.add(new ChooserEntry(ControllerProfile.DEVICE_BUILTIN, builtInLabel));
        }

        if (includeAdapterPorts && portLabeler != null) {
            for (int i = 0; i < 4; i++) {
                entries.add(new ChooserEntry(
                        ControllerProfile.adapterDeviceKey(i),
                        portLabeler.labelForPort(i + 1)));
            }
        }
        return entries;
    }

    public static boolean isDeviceExternal(InputDevice device) {
        if (device == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return device.isExternal();
        }
        return false;
    }

    private static Set<Long> connectedUsbVidPids(Context context) {
        Set<Long> result = new HashSet<>();
        if (context == null) return result;
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return result;
        try {
            for (UsbDevice dev : manager.getDeviceList().values()) {
                result.add(vidPidKey(dev.getVendorId(), dev.getProductId()));
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static long vidPidKey(int vid, int pid) {
        return (((long) (vid & 0xFFFF)) << 16) | (pid & 0xFFFFL);
    }
}
