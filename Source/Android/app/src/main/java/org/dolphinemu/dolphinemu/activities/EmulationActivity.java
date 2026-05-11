package org.dolphinemu.dolphinemu.activities;

import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PerformanceHintManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;

/**
 * Hosts the SurfaceView that the C++ renderer draws into and pumps the
 * emulator thread.
 */
public class EmulationActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    public static final String EXTRA_ISO_PATH = "iso_path";
    private static final String TAG = "SlippiEmu";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Thread emuThread;
    private volatile boolean emuStarted;
    private SurfaceView surfaceView;
    private PerformanceHintManager.Session hintSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_emulation);
        surfaceView = findViewById(R.id.emulation_surface);
        surfaceView.getHolder().addCallback(this);
        // We dispatch key/motion events at the Activity level, but the system
        // only sends them to the foreground window — make sure the surface
        // actually owns focus so gamepad events reach dispatchKeyEvent.
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.requestFocus();

        NativeLibrary.setEmulationActivity(this);
        applyImmersive();

        String iso = getIntent().getStringExtra(EXTRA_ISO_PATH);
        if (iso == null) {
            Toast.makeText(this, "No ISO path passed", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        NativeLibrary.SetFilename(iso);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hintSession != null) {
            try { hintSession.close(); } catch (Throwable ignored) {}
            hintSession = null;
        }
        if (emuStarted) {
            try {
                NativeLibrary.StopEmulation();
            } catch (Throwable t) {
                Log.w(TAG, "stop emulation: " + t);
            }
        }
        if (NativeLibrary.sEmulationActivity == this) {
            NativeLibrary.setEmulationActivity(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        if (emuStarted) {
            try { NativeLibrary.UnPauseEmulation(); } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (emuStarted) {
            try { NativeLibrary.PauseEmulation(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // On Android 11+ tell the system compositor that this surface is a
        // 60Hz game. With FRAME_RATE_COMPATIBILITY_FIXED_SOURCE the
        // SurfaceFlinger can pick a display mode that matches without
        // resampling, which cuts a frame of latency on devices that idle at
        // 120Hz and would otherwise jitter our 60fps output. The
        // CHANGE_FRAME_RATE_ALWAYS strategy avoids the 2-second seamless
        // transition delay so Melee starts at 60Hz right away.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                holder.getSurface().setFrameRate(60f,
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            } catch (IllegalStateException ignored) {}
        }
        NativeLibrary.SurfaceChanged(holder.getSurface());
        if (!emuStarted) {
            emuStarted = true;
            emuThread = new Thread(NativeLibrary::Run, "DolphinEmuMain");
            emuThread.start();
            registerPerfHintWhenReady();
        }
    }

    /**
     * Register an Android PerformanceHintManager session (API 31+) keyed on
     * the emulation thread's Linux TID, with a 16.6 ms target work duration.
     * This tells the kernel scheduler "this thread has a hard 60fps deadline"
     * — without it, the SoC's energy-aware scheduler will sometimes downclock
     * the big core mid-frame, which surfaces as exactly the "feels like an
     * extra frame or two of latency" the user reported.
     *
     * The emulation thread doesn't call gettid() until it starts running, so
     * we poll the native getter on the main thread for up to a second.
     */
    private void registerPerfHintWhenReady() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        final PerformanceHintManager mgr =
                (PerformanceHintManager) getSystemService(PERFORMANCE_HINT_SERVICE);
        if (mgr == null) return;
        ui.post(new Runnable() {
            int attempts = 0;
            @Override public void run() {
                int tid = NativeLibrary.GetEmuThreadTid();
                if (tid <= 0) {
                    if (++attempts < 100) ui.postDelayed(this, 10);
                    return;
                }
                try {
                    hintSession = mgr.createHintSession(new int[]{tid}, 16_666_666L);
                    Log.i(TAG, "PerformanceHintSession created for tid=" + tid);
                } catch (Throwable t) {
                    Log.w(TAG, "PerformanceHintSession failed: " + t);
                }
            }
        });
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        NativeLibrary.SurfaceChanged(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        NativeLibrary.SurfaceDestroyed();
    }

    /*
     * Input model: ButtonManager has a single "Touchscreen" InputDevice that
     * GCPadNew.ini's Player 1 is bound to. We translate every physical key /
     * stick event into the codes that device speaks (BUTTON_* / STICK_*) and
     * call into JNI as if the touchscreen overlay had pressed them. This way
     * we get phone gamepads, the Thor's onboard sticks/buttons, and Bluetooth
     * controllers all funneling through one input path with zero per-device
     * setup.
     *
     * Trigger buttons (L1/L2/R1/R2) hit two paths: as digital buttons (TRIGGER_*)
     * and as analog axes (AXIS_LTRIGGER / AXIS_RTRIGGER) — the dispatch below
     * handles both.
     */

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        int gcButton = mapKeyToGcButton(keyCode);
        // Log once per real (non-repeat) event so we can see in logcat whether
        // events are reaching us at all and what the keycodes look like.
        if (event.getRepeatCount() == 0) {
            android.util.Log.i(TAG, "dispatchKeyEvent kc=" + keyCode
                    + " src=0x" + Integer.toHexString(event.getSource())
                    + " action=" + action + " gc=" + gcButton);
        }
        if (gcButton < 0) return super.dispatchKeyEvent(event);
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event);
        }
        int state = action == KeyEvent.ACTION_DOWN
                ? NativeLibrary.ButtonState.PRESSED
                : NativeLibrary.ButtonState.RELEASED;
        NativeLibrary.onGamePadEvent(NativeLibrary.TouchScreenDevice, gcButton, state);
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) == 0
                && (ev.getSource() & android.view.InputDevice.SOURCE_GAMEPAD) == 0) {
            return super.dispatchGenericMotionEvent(ev);
        }
        // Main stick (left analog): both directions of each axis get the same
        // raw value. The ButtonManager touchscreen binds have _neg=-1 on
        // LEFT/UP and +1 on RIGHT/DOWN, so the negative half gets clamped to 0
        // by GCPadEmu and only the active direction surfaces.
        feedStick(ev, MotionEvent.AXIS_X,
                NativeLibrary.ButtonType.STICK_MAIN_LEFT,
                NativeLibrary.ButtonType.STICK_MAIN_RIGHT);
        feedStick(ev, MotionEvent.AXIS_Y,
                NativeLibrary.ButtonType.STICK_MAIN_UP,
                NativeLibrary.ButtonType.STICK_MAIN_DOWN);
        // C-stick (right analog).
        feedStick(ev, MotionEvent.AXIS_Z,
                NativeLibrary.ButtonType.STICK_C_LEFT,
                NativeLibrary.ButtonType.STICK_C_RIGHT);
        feedStick(ev, MotionEvent.AXIS_RZ,
                NativeLibrary.ButtonType.STICK_C_UP,
                NativeLibrary.ButtonType.STICK_C_DOWN);
        // Analog triggers (Melee uses analog L for tech / wavedash buffer).
        float lt = ev.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float rt = ev.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        if (lt == 0f) lt = ev.getAxisValue(MotionEvent.AXIS_BRAKE);
        if (rt == 0f) rt = ev.getAxisValue(MotionEvent.AXIS_GAS);
        NativeLibrary.onGamePadMoveEvent(NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.TRIGGER_L, lt);
        NativeLibrary.onGamePadMoveEvent(NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.TRIGGER_R, rt);
        // D-pad on some pads comes through as HAT_X/HAT_Y axes instead of
        // KEYCODE_DPAD_*. Synthesize the discrete buttons here.
        float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
        NativeLibrary.onGamePadEvent(NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.BUTTON_LEFT,
                hx < -0.5f ? NativeLibrary.ButtonState.PRESSED : NativeLibrary.ButtonState.RELEASED);
        NativeLibrary.onGamePadEvent(NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.BUTTON_RIGHT,
                hx >  0.5f ? NativeLibrary.ButtonState.PRESSED : NativeLibrary.ButtonState.RELEASED);
        NativeLibrary.onGamePadEvent(NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.BUTTON_UP,
                hy < -0.5f ? NativeLibrary.ButtonState.PRESSED : NativeLibrary.ButtonState.RELEASED);
        NativeLibrary.onGamePadEvent(NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.BUTTON_DOWN,
                hy >  0.5f ? NativeLibrary.ButtonState.PRESSED : NativeLibrary.ButtonState.RELEASED);
        return true;
    }

    private void feedStick(MotionEvent ev, int androidAxis, int gcAxisLowHalf, int gcAxisHighHalf) {
        float v = ev.getAxisValue(androidAxis);
        // Apply a small deadzone for noisy sticks.
        if (Math.abs(v) < 0.05f) v = 0f;
        NativeLibrary.onGamePadMoveEvent(NativeLibrary.TouchScreenDevice, gcAxisLowHalf, v);
        NativeLibrary.onGamePadMoveEvent(NativeLibrary.TouchScreenDevice, gcAxisHighHalf, v);
    }

    private int mapKeyToGcButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:      return NativeLibrary.ButtonType.BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B:      return NativeLibrary.ButtonType.BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_X:      return NativeLibrary.ButtonType.BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y:      return NativeLibrary.ButtonType.BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_START:  return NativeLibrary.ButtonType.BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return NativeLibrary.ButtonType.BUTTON_Z;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return NativeLibrary.ButtonType.BUTTON_Z;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_L2:     return NativeLibrary.ButtonType.TRIGGER_L;
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_R2:     return NativeLibrary.ButtonType.TRIGGER_R;
            case KeyEvent.KEYCODE_DPAD_UP:       return NativeLibrary.ButtonType.BUTTON_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:     return NativeLibrary.ButtonType.BUTTON_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:     return NativeLibrary.ButtonType.BUTTON_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:    return NativeLibrary.ButtonType.BUTTON_RIGHT;
            default: return -1;
        }
    }

    public void showToast(String msg) {
        ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    public void finishFromNative() {
        ui.post(this::finish);
    }

    private void applyImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }
}
