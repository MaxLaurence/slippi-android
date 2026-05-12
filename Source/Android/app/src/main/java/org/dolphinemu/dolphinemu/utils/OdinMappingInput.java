package org.dolphinemu.dolphinemu.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.Arrays;

/**
 * Lightweight client for Ayn/Odin's system mapping service.
 *
 * Thor exposes the onboard controls through Android MotionEvent as normalized
 * -1..+1 axes. The vendor mapping service has a currentRawEvent() binder call
 * whose first four lanes preserve the lower-level, unsaturated stick throw.
 * The listener callback can still be vendor-scaled and saturated, so it is kept
 * for diagnostics only.
 */
public final class OdinMappingInput implements RawStickInputProvider {
    private static final String TAG = "SlippiEmu";
    private static final String RAW_TAG = "SlippiOdinRaw";
    private static final String MAPPING_PACKAGE = "com.odin.mapping";
    private static final String MAPPING_SERVICE = "com.ro.mapping.service.ApiService";
    private static final String MAPPING_ACTION = "com.ro.mapping.action.MAPPING_SERVICE";
    private static final String SERVER_DESCRIPTOR = "com.ro.mapping.sdk.IServerApi";
    private static final String LISTENER_DESCRIPTOR =
            "com.ro.mapping.sdk.listener.OnInputEventListener";
    private static final String LEFT_JOYSTICK = "LEFT_JOYSTICK";
    private static final String RIGHT_JOYSTICK = "RIGHT_JOYSTICK";
    private static final int TRANSACTION_REGISTER_LISTENER = 2;
    private static final int TRANSACTION_UNREGISTER_LISTENER = 3;
    private static final int TRANSACTION_CURRENT_RAW_EVENT = 24;
    private static final float AXIS_MAX = 32767.0f;
    private static final float CURRENT_RAW_AXIS_MAX = 4096.0f;
    private static final int CURRENT_RAW_FAILURE_LIMIT = 120;
    private static final boolean RAW_DIAGNOSTICS = false;

    private static OdinMappingInput instance;

    private final Context context;
    private final Object lock = new Object();
    private final MappingListener listener = new MappingListener();
    private final ServiceConnection connection = new MappingConnection();
    private final RawStickState state = new RawStickState();

    private IBinder service;
    private boolean bindStarted;
    private boolean listenerRegistered;
    private boolean loggedRawEventShape;
    private boolean currentRawEventAvailable;
    private boolean currentRawEventUnavailable;
    private int currentRawEventFailures;
    private long lastAxisLog;

    private OdinMappingInput(Context context) {
        this.context = context.getApplicationContext();
    }

    public static OdinMappingInput getInstance(Context context) {
        synchronized (OdinMappingInput.class) {
            if (instance == null) {
                instance = new OdinMappingInput(context);
            }
            return instance;
        }
    }

    public static boolean isServiceAvailable(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent intent = serviceIntent();
        try {
            return packageManager.resolveService(intent, 0) != null;
        } catch (RuntimeException e) {
            android.util.Log.w(TAG, "Odin mapping service resolve failed", e);
            return false;
        }
    }

    @Override
    public String id() {
        return "ayn_odin_mapping";
    }

    @Override
    public String label() {
        return "Ayn/Odin mapping raw service";
    }

    @Override
    public boolean isAvailable() {
        return isServiceAvailable(context);
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (bindStarted || currentRawEventUnavailable) {
                return;
            }
            bindStarted = true;
        }

        boolean bound;
        try {
            bound = context.bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException e) {
            android.util.Log.w(TAG, "Odin mapping service bind failed", e);
            synchronized (lock) {
                bindStarted = false;
                currentRawEventUnavailable = true;
            }
            return;
        }

        if (!bound) {
            android.util.Log.i(TAG, "Odin mapping service not available");
            synchronized (lock) {
                bindStarted = false;
                currentRawEventUnavailable = true;
            }
        }
    }

    @Override
    public void stop() {
        boolean shouldUnbind;
        synchronized (lock) {
            shouldUnbind = bindStarted;
            if (shouldUnbind) {
                unregisterListenerLocked();
            }
            bindStarted = false;
            service = null;
            currentRawEventAvailable = false;
            currentRawEventUnavailable = false;
            currentRawEventFailures = 0;
            state.clear();
        }

        if (shouldUnbind) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @Override
    public RawStickState snapshot() {
        start();
        synchronized (lock) {
            if (currentRawEventUnavailable) {
                return null;
            }
        }
        pollCurrentRawEvent();
        synchronized (lock) {
            return state.hasAnyAxis() ? new RawStickState(state) : null;
        }
    }

    @Override
    public boolean keepPollingWhenUnavailable() {
        synchronized (lock) {
            return !currentRawEventUnavailable;
        }
    }

    @Override
    public boolean isPotentiallySaturated() {
        return true;
    }

    public double[] pollCurrentRawEvent() {
        IBinder currentService;
        synchronized (lock) {
            currentService = service;
        }

        if (currentService == null) {
            return null;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVER_DESCRIPTOR);
            if (!currentService.transact(TRANSACTION_CURRENT_RAW_EVENT, data, reply, 0)) {
                markCurrentRawEventFailure("transaction rejected");
                return null;
            }
            reply.readException();
            double[] values = reply.createDoubleArray();
            if (!loggedRawEventShape && values != null) {
                loggedRawEventShape = true;
                android.util.Log.i(RAW_TAG, "Odin currentRawEvent length=" + values.length
                        + " values=" + Arrays.toString(values));
            }
            if (!updateFromRawArray(values)) {
                markCurrentRawEventFailure("invalid currentRawEvent payload");
            }
            return values;
        } catch (RemoteException | RuntimeException e) {
            android.util.Log.w(TAG, "Odin currentRawEvent failed", e);
            markCurrentRawEventFailure("exception");
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static Intent serviceIntent() {
        Intent intent = new Intent(MAPPING_ACTION);
        intent.setComponent(new ComponentName(MAPPING_PACKAGE, MAPPING_SERVICE));
        return intent;
    }

    private void registerListenerLocked() {
        if (service == null || listenerRegistered) {
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVER_DESCRIPTOR);
            data.writeStrongBinder(listener);
            data.writeStringArray(new String[] {LEFT_JOYSTICK, RIGHT_JOYSTICK});
            if (service.transact(TRANSACTION_REGISTER_LISTENER, data, reply, 0)) {
                reply.readException();
                listenerRegistered = true;
                android.util.Log.i(TAG, "Odin mapping raw joystick listener registered");
            }
        } catch (RemoteException | RuntimeException e) {
            android.util.Log.w(TAG, "Odin mapping listener registration failed", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void unregisterListenerLocked() {
        if (service == null || !listenerRegistered) {
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVER_DESCRIPTOR);
            data.writeStrongBinder(listener);
            if (service.transact(TRANSACTION_UNREGISTER_LISTENER, data, reply, 0)) {
                reply.readException();
            }
        } catch (RemoteException | RuntimeException e) {
            android.util.Log.w(TAG, "Odin mapping listener unregister failed", e);
        } finally {
            listenerRegistered = false;
            reply.recycle();
            data.recycle();
        }
    }

    private void handleInputEvent(String id, short value1, short value2) {
        if (id == null) {
            return;
        }

        float x = normalize(value1);
        float y = normalize(value2);
        if (RAW_DIAGNOSTICS) {
            maybeLogAxis(id, value1, value2, x, y);
        }
        // The provider exposes samples exclusively from currentRawEvent().
    }

    private static float normalize(short value) {
        if (value == Short.MIN_VALUE) {
            return -1.0f;
        }
        return clamp(value / AXIS_MAX);
    }

    private static float normalizeRawDouble(double value) {
        if (Double.isNaN(value)) {
            return 0.0f;
        }
        if (value <= Short.MIN_VALUE) {
            return -1.0f;
        }
        if (value >= Short.MAX_VALUE) {
            return 1.0f;
        }
        return clamp((float) value / CURRENT_RAW_AXIS_MAX);
    }

    private static float clamp(float value) {
        return Math.max(-1.0f, Math.min(1.0f, value));
    }

    private boolean updateFromRawArray(double[] values) {
        if (values == null || values.length < 4) {
            return false;
        }

        synchronized (lock) {
            currentRawEventAvailable = true;
            currentRawEventFailures = 0;
            // currentRawEvent lane polarity is opposite of Android's
            // joystick axes: right/down show as negative raw deltas.
            state.setLeft(-normalizeRawDouble(values[0]), -normalizeRawDouble(values[1]));
            state.setRight(-normalizeRawDouble(values[2]), -normalizeRawDouble(values[3]));
        }
        return true;
    }

    private void markCurrentRawEventFailure(String reason) {
        synchronized (lock) {
            if (service == null || currentRawEventAvailable || currentRawEventUnavailable) {
                return;
            }

            currentRawEventFailures++;
            if (currentRawEventFailures >= CURRENT_RAW_FAILURE_LIMIT) {
                currentRawEventUnavailable = true;
                state.clear();
                android.util.Log.w(TAG, "Odin currentRawEvent unavailable after "
                        + currentRawEventFailures + " attempts (" + reason
                        + "); falling back to Android MotionEvent input");
            }
        }
    }

    private void maybeLogAxis(String id, short rawX, short rawY, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastAxisLog < 200 && Math.abs(x) < 0.05f && Math.abs(y) < 0.05f) {
            return;
        }
        if (now - lastAxisLog < 200) {
            return;
        }
        lastAxisLog = now;
        android.util.Log.i(RAW_TAG, "Odin raw axis id=" + id
                + " raw=(" + rawX + ", " + rawY + ")"
                + " norm=(" + String.format("%+.3f", x)
                + ", " + String.format("%+.3f", y) + ")");
    }

    private final class MappingConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder connectedService) {
            synchronized (lock) {
                service = connectedService;
                listenerRegistered = false;
                currentRawEventUnavailable = false;
                currentRawEventFailures = 0;
                registerListenerLocked();
            }
            pollCurrentRawEvent();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) {
                service = null;
                listenerRegistered = false;
                currentRawEventAvailable = false;
                state.clear();
            }
            android.util.Log.i(TAG, "Odin mapping service disconnected");
        }
    }

    private final class MappingListener extends Binder implements IInterface {
        MappingListener() {
            attachInterface(this, LISTENER_DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(LISTENER_DESCRIPTOR);
                return true;
            }

            if (code != 1) {
                return super.onTransact(code, data, reply, flags);
            }

            data.enforceInterface(LISTENER_DESCRIPTOR);
            if (data.readInt() != 0) {
                String id = data.readString();
                short value1 = (short) data.readInt();
                short value2 = (short) data.readInt();
                data.readInt(); // fd
                data.readInt(); // type
                data.readInt(); // code
                data.readInt(); // value
                handleInputEvent(id, value1, value2);
            }

            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(1);
            }
            return true;
        }
    }
}
