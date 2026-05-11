package org.dolphinemu.dolphinemu.utils;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import org.dolphinemu.dolphinemu.DolphinApplication;

import java.util.HashMap;
import java.util.Map;

/**
 * USB host glue for the official Nintendo WUP-028 GameCube Controller adapter.
 *
 * The native side ({@code Source/Core/InputCommon/GCAdapter_Android.cpp}) finds
 * this class via {@code FindClass(...)} and invokes the static methods below
 * during input polling. Method signatures must match what the C++ side expects:
 * {@code QueryAdapter():Z}, {@code OpenAdapter():Z}, {@code Input():I},
 * {@code Output([B):I}, {@code GetFD():I}.
 *
 * Permission flow:
 *   1. Manifest declares android.hardware.usb.host + USB_DEVICE_ATTACHED filter
 *      so plugging the adapter in launches our app (and grants access for that
 *      device implicitly).
 *   2. If the adapter is already plugged in when the app starts, we have to
 *      call {@link UsbManager#requestPermission} explicitly; the user gets a
 *      one-time confirmation dialog and the result comes back via the
 *      {@link #ACTION_USB_PERMISSION} broadcast.
 *   3. {@link #OpenAdapter()} only succeeds once we hold permission. It claims
 *      the single USB interface, finds the bulk IN/OUT endpoints, and sends
 *      the 0x13 init byte that puts the adapter in "send input" mode.
 */
public final class Java_GCAdapter {
    private static final String TAG = "SlippiGCAdapter";
    private static final int NINTENDO_VID = 0x057E;
    private static final int WUP028_PID = 0x0337;
    private static final String ACTION_USB_PERMISSION =
            "org.dolphinemu.dolphinemu.USB_PERMISSION";

    private static UsbManager sManager;
    private static UsbDeviceConnection sConnection;
    private static UsbInterface sInterface;
    private static UsbEndpoint sIn;
    private static UsbEndpoint sOut;
    /**
     * Read buffer the C++ side picks up via JNI. Name and type ({@code byte[]})
     * are load-bearing — {@code Source/Core/InputCommon/GCAdapter_Android.cpp}
     * does {@code env->GetStaticFieldID(cls, "controller_payload", "[B")}.
     * 37 bytes matches the actual GC adapter polling packet length.
     */
    @SuppressWarnings("unused")
    public static final byte[] controller_payload = new byte[37];
    private static boolean sReceiverRegistered;

    private static final BroadcastReceiver sPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Log.i(TAG, "Permission result: granted=" + granted);
            }
        }
    };

    private static synchronized UsbManager manager() {
        if (sManager == null) {
            Context ctx = DolphinApplication.getAppContext();
            if (ctx == null) return null;
            sManager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (!sReceiverRegistered) {
                IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
                int flags = 0;
                try {
                    flags = Context.RECEIVER_NOT_EXPORTED;
                } catch (NoSuchFieldError ignored) {}
                ctx.registerReceiver(sPermissionReceiver, filter, flags);
                sReceiverRegistered = true;
            }
        }
        return sManager;
    }

    private static UsbDevice findAdapter() {
        UsbManager m = manager();
        if (m == null) return null;
        for (Map.Entry<String, UsbDevice> e : m.getDeviceList().entrySet()) {
            UsbDevice d = e.getValue();
            if (d.getVendorId() == NINTENDO_VID && d.getProductId() == WUP028_PID) {
                return d;
            }
        }
        return null;
    }

    private static void requestPermission(UsbDevice dev) {
        UsbManager m = manager();
        if (m == null) return;
        Context ctx = DolphinApplication.getAppContext();
        if (ctx == null) return;
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(ctx.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        try {
            flags |= PendingIntent.FLAG_MUTABLE;
        } catch (NoSuchFieldError ignored) {}
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent, flags);
        m.requestPermission(dev, pi);
    }

    public static synchronized boolean QueryAdapter() {
        UsbDevice dev = findAdapter();
        if (dev == null) return false;
        UsbManager m = manager();
        if (m == null) return false;
        if (m.hasPermission(dev)) return true;
        requestPermission(dev);
        return false;
    }

    public static synchronized boolean OpenAdapter() {
        UsbDevice dev = findAdapter();
        if (dev == null) {
            Log.w(TAG, "OpenAdapter: no WUP-028 plugged in");
            return false;
        }
        UsbManager m = manager();
        if (m == null) return false;
        if (!m.hasPermission(dev)) {
            requestPermission(dev);
            return false;
        }

        sConnection = m.openDevice(dev);
        if (sConnection == null) {
            Log.w(TAG, "OpenAdapter: openDevice returned null");
            return false;
        }
        Log.i(TAG, "OpenAdapter: configurations=" + dev.getConfigurationCount()
                + " interfaces=" + dev.getInterfaceCount());

        if (dev.getConfigurationCount() <= 0 || dev.getInterfaceCount() <= 0) {
            close();
            return false;
        }
        UsbConfiguration cfg = dev.getConfiguration(0);
        sInterface = cfg.getInterface(0);
        if (!sConnection.claimInterface(sInterface, true)) {
            Log.w(TAG, "OpenAdapter: claimInterface failed");
            close();
            return false;
        }
        Log.i(TAG, "OpenAdapter: endpoints=" + sInterface.getEndpointCount());

        sIn = null;
        sOut = null;
        for (int i = 0; i < sInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = sInterface.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) sIn = ep;
            else sOut = ep;
        }
        if (sIn == null || sOut == null) {
            Log.w(TAG, "OpenAdapter: missing bulk endpoint(s)");
            close();
            return false;
        }
        // Init: tell the adapter to start sending pad reports.
        sConnection.bulkTransfer(sOut, new byte[]{0x13}, 1, 16);
        Log.i(TAG, "OpenAdapter: ready");
        return true;
    }

    public static synchronized int Input() {
        if (sConnection == null || sIn == null) return 0;
        return sConnection.bulkTransfer(sIn, controller_payload, controller_payload.length, 16);
    }

    public static synchronized int Output(byte[] rumble) {
        if (sConnection == null || sOut == null || rumble == null) return 0;
        return sConnection.bulkTransfer(sOut, rumble, Math.min(rumble.length, 5), 16);
    }

    public static synchronized int GetFD() {
        return sConnection != null ? sConnection.getFileDescriptor() : -1;
    }

    public static synchronized void Shutdown() {
        close();
    }

    private static synchronized void close() {
        if (sConnection != null) {
            try {
                if (sInterface != null) sConnection.releaseInterface(sInterface);
            } catch (Throwable ignored) {}
            try { sConnection.close(); } catch (Throwable ignored) {}
        }
        sConnection = null;
        sInterface = null;
        sIn = null;
        sOut = null;
    }
}
