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
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.dolphinemu.dolphinemu.BuildConfig;
import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.controller.ButtonMap;
import org.dolphinemu.dolphinemu.controller.ControllerProfile;
import org.dolphinemu.dolphinemu.controller.GameCubePadState;
import org.dolphinemu.dolphinemu.controller.StickCalibration;
import org.dolphinemu.dolphinemu.controller.TouchOverlayLayoutStore;
import org.dolphinemu.dolphinemu.replay.GeckoOverride;
import org.dolphinemu.dolphinemu.replay.ReplayConfig;
import org.dolphinemu.dolphinemu.utils.PhysicalControllerDetector;
import org.dolphinemu.dolphinemu.utils.RawStickInputProvider;
import org.dolphinemu.dolphinemu.utils.RawStickInputProviders;
import org.dolphinemu.dolphinemu.utils.RawStickState;
import org.dolphinemu.dolphinemu.views.ReplayHudView;
import org.dolphinemu.dolphinemu.views.TouchControlOverlayView;

import java.io.File;

/**
 * Hosts the SurfaceView that the C++ renderer draws into and pumps the
 * emulator thread.
 */
public class EmulationActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    public static final String EXTRA_ISO_PATH = "iso_path";
    public static final String EXTRA_USE_GC_ADAPTER = "use_gc_adapter";
    /** Absolute path to a .slp file. Triggers replay playback mode when present. */
    public static final String EXTRA_REPLAY_PATH = "replay_path";
    private static final String TAG = "SlippiEmu";
    private static final boolean INPUT_DIAGNOSTICS = false;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // Loaded once at activity create from ControllerProfile. Identity by
    // default until the user runs the calibration wizard from the
    // launcher, so existing un-calibrated controllers behave identically
    // to the previous build (modulo the deadzone change baked into
    // StickCalibration.IDENTITY).
    private StickCalibration mainStickCal = StickCalibration.IDENTITY;
    private StickCalibration cStickCal    = StickCalibration.IDENTITY;
    private final StickCalibration.Out stickOut = new StickCalibration.Out();
    private final GameCubePadState padState = new GameCubePadState(0);
    // Loaded once at onCreate; remap takes effect when the user
    // exits the match and returns to the launcher.
    private ButtonMap buttonMap = ButtonMap.defaults();
    private RawStickInputProvider rawStickInput;
    private TouchControlOverlayView touchOverlay;
    private ReplayHudView replayHud;
    private boolean touchOverlayVisible;
    private boolean useGcAdapter;
    private boolean isReplayMode;
    private final Runnable rawInputPoll = new Runnable() {
        @Override
        public void run() {
            if (!shouldPollRawStickSource()) return;

            if (feedRawStickState()) {
                pushPad();
                ui.postDelayed(this, 8);
                return;
            }

            if (rawStickInput != null && rawStickInput.keepPollingWhenUnavailable()) {
                ui.postDelayed(this, 8);
                return;
            }

            mainStickCal = mainStickCal.withOuterScaleEnabled(false);
            cStickCal = cStickCal.withOuterScaleEnabled(false);
            if (rawStickInput != null) {
                rawStickInput.stop();
                rawStickInput = null;
            }
            Log.w(TAG, "raw gamepad axes disappeared; falling back to MotionEvent sticks");
        }
    };
    private final Runnable controllerDetectorPoll = new Runnable() {
        @Override
        public void run() {
            updateTouchOverlayVisibility();
            ui.postDelayed(this, 1000);
        }
    };
    private final TouchControlOverlayView.Listener touchOverlayListener =
            new TouchControlOverlayView.Listener() {
                @Override
                public void onOverlayButton(int gcBit, boolean pressed) {
                    padState.setButton(gcBit, pressed);
                    pushPad();
                }

                @Override
                public void onOverlayStick(String stickId, float x, float y) {
                    if (TouchOverlayLayoutStore.MAIN_STICK.equals(stickId)) {
                        padState.setMainStick(x, y);
                    } else if (TouchOverlayLayoutStore.C_STICK.equals(stickId)) {
                        padState.setCStick(x, y);
                    }
                    pushPad();
                }

                @Override
                public void onOverlayDpad(boolean up, boolean down, boolean left, boolean right) {
                    padState.setDirectionalButtons(up, down, left, right);
                    pushPad();
                }

                @Override
                public void onOverlayEditModeChanged(boolean editing) {
                    if (editing) {
                        padState.reset();
                        pushPad();
                    } else {
                        applyImmersive();
                    }
                }
            };
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
        touchOverlay = findViewById(R.id.touch_overlay);
        touchOverlay.setListener(touchOverlayListener);
        replayHud = findViewById(R.id.replay_hud);
        surfaceView.getHolder().addCallback(this);
        // We dispatch key/motion events at the Activity level, but the system
        // only sends them to the foreground window — make sure the surface
        // actually owns focus so gamepad events reach dispatchKeyEvent.
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.requestFocus();
        useGcAdapter = getIntent().getBooleanExtra(EXTRA_USE_GC_ADAPTER, false);

        // Pull the latest on-device stick calibration. GC adapter stick
        // bytes pass through the native adapter path unchanged.
        ControllerProfile profile = new ControllerProfile(this);
        mainStickCal = profile.getStick(ControllerProfile.DEVICE_BUILTIN, ControllerProfile.Stick.MAIN);
        cStickCal    = profile.getStick(ControllerProfile.DEVICE_BUILTIN, ControllerProfile.Stick.C);
        buttonMap    = profile.getButtonMap(ControllerProfile.DEVICE_BUILTIN);
        if (!useGcAdapter) {
            rawStickInput = RawStickInputProviders.create(this);
        }
        if (rawStickInput != null) {
            rawStickInput.start();
        }
        // When the raw provider is known to still saturate at the firmware
        // level (e.g. Ayn/Odin's currentRawEvent on the Thor clips at ~25%
        // of nominal range), uncalibrated sticks can't reach Melee's tilt
        // thresholds. Swap in compensating defaults so first-launch users
        // get usable sticks; the wizard still produces a precise capture.
        if (rawStickInput != null && rawStickInput.isPotentiallySaturated()) {
            if (!profile.hasCalibration(ControllerProfile.DEVICE_BUILTIN, ControllerProfile.Stick.MAIN)) {
                mainStickCal = StickCalibration.compensatingMain();
            }
            if (!profile.hasCalibration(ControllerProfile.DEVICE_BUILTIN, ControllerProfile.Stick.C)) {
                cStickCal = StickCalibration.compensatingC();
            }
        }
        if (!hasRawStickSource()) {
            mainStickCal = mainStickCal.withOuterScaleEnabled(false);
            cStickCal = cStickCal.withOuterScaleEnabled(false);
        }
        Log.i(TAG, "loaded mainStick cal: dz=" + mainStickCal.deadzone
                + " sens=" + mainStickCal.sensitivity
                + " cap=" + mainStickCal.outputCap
                + " centerX=" + mainStickCal.centerX
                + " scaleX+=" + mainStickCal.scaleXPos
                + " scaleX-=" + mainStickCal.scaleXNeg
                + " rawScale=" + mainStickCal.useOuterScale
                + " rawReader=" + hasRawStickSource()
                + " rawSource=" + rawSourceLabel()
                + (profile.hasCalibration(ControllerProfile.DEVICE_BUILTIN, ControllerProfile.Stick.MAIN)
                        ? " (saved)" : " (defaults — wizard never saved)"));
        Log.i(TAG, "touch controls force flag=" + BuildConfig.FORCE_TOUCH_CONTROLS);

        NativeLibrary.setEmulationActivity(this);
        applyImmersive();
        updateTouchOverlayVisibility();

        String iso = getIntent().getStringExtra(EXTRA_ISO_PATH);
        if (iso == null) {
            Toast.makeText(this, "No ISO path passed", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        NativeLibrary.SetFilename(iso);

        // Replay mode: write the JSON config and point the C++ side at
        // it before Run() boots. Live mode: write a neutral file AND
        // point at it — defense in depth so a stale config from a prior
        // replay launch can never bleed into a netplay session.
        String replayPath = getIntent().getStringExtra(EXTRA_REPLAY_PATH);
        isReplayMode = replayPath != null && new File(replayPath).exists();
        if (isReplayMode) {
            ReplayConfig.writeNormal(this, new File(replayPath));
            // Swap in the playback-mode gecko codes so the title
            // screen runs the Slippi Playback boot path instead of
            // dropping into the Online menu.
            GeckoOverride.applyReplayMode(this);
        } else {
            ReplayConfig.writeEmpty(this);
            GeckoOverride.applyLiveMode(this);
        }
        NativeLibrary.SetSlippiInputPath(ReplayConfig.commFile(this).getAbsolutePath());

        if (isReplayMode && replayHud != null) {
            replayHud.setVisibility(View.VISIBLE);
            replayHud.startPolling();
        }
    }

    @Override
    public void onBackPressed() {
        // Tear emulation down BEFORE super finishes the activity. The
        // emu thread holds the SurfaceView, the JNI mutexes, and the
        // GL/Vulkan context — if we let the activity destroy without
        // first joining the thread, subsequent JNI calls from the
        // launcher (e.g. opening CalibrationActivity or
        // ButtonMapActivity, both of which call into GCAdapter::* via
        // JNI) can hang behind native state that's still half-running.
        shutdownEmuThreadSync();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(rawInputPoll);
        ui.removeCallbacks(controllerDetectorPoll);
        if (replayHud != null) replayHud.stopPolling();
        if (isReplayMode) {
            // Belt-and-suspenders: if the user exits mid-FFW, the OC
            // settings would otherwise leak into the next session (the
            // CEXISlippi dtor restores them, but that runs on a thread
            // we can't guarantee finishes before the next BootCore).
            try { NativeLibrary.SetReplaySpeedMode(0); } catch (Throwable ignored) {}
        }
        super.onDestroy();
        if (hintSession != null) {
            try { hintSession.close(); } catch (Throwable ignored) {}
            hintSession = null;
        }
        try {
            NativeLibrary.ClearPadOverride(0);
        } catch (Throwable ignored) {}
        if (rawStickInput != null) {
            rawStickInput.stop();
        }
        // Idempotent: harmless if onBackPressed already stopped us.
        shutdownEmuThreadSync();
        if (NativeLibrary.sEmulationActivity == this) {
            NativeLibrary.setEmulationActivity(null);
        }
    }

    /**
     * Stop the emulator and wait (with a timeout) for the native thread
     * to actually exit before returning. Idempotent — calling it twice
     * after the thread is gone is a no-op.
     */
    private void shutdownEmuThreadSync() {
        if (!emuStarted) return;
        emuStarted = false;
        try {
            NativeLibrary.StopEmulation();
        } catch (Throwable t) {
            Log.w(TAG, "stop emulation: " + t);
        }
        Thread t = emuThread;
        emuThread = null;
        if (t != null && t.isAlive()) {
            try {
                t.join(3000);  // 3 s ceiling so we don't ANR if the JIT is stuck
                if (t.isAlive()) {
                    Log.w(TAG, "emu thread didn't exit within 3s — leaked");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        if (emuStarted) {
            try { NativeLibrary.UnPauseEmulation(); } catch (Throwable ignored) {}
        }
        updateTouchOverlayVisibility();
        ui.post(controllerDetectorPoll);
        if (shouldPollRawStickSource()) ui.post(rawInputPoll);
        if (isReplayMode && replayHud != null) {
            replayHud.setVisibility(View.VISIBLE);
            replayHud.startPolling();
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(rawInputPoll);
        ui.removeCallbacks(controllerDetectorPoll);
        if (replayHud != null) replayHud.stopPolling();
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

    private void pushPad() {
        padState.pushToNative();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (useGcAdapter) {
            return super.dispatchKeyEvent(event);
        }
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        int bit = mapKeyToGcBit(keyCode);
        if (INPUT_DIAGNOSTICS && event.getRepeatCount() == 0) {
            android.util.Log.i(TAG, "dispatchKeyEvent kc=" + keyCode
                    + " src=0x" + Integer.toHexString(event.getSource())
                    + " action=" + action + " bit=0x" + Integer.toHexString(bit));
        }
        if (bit == 0) return super.dispatchKeyEvent(event);
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event);
        }
        padState.setButton(bit, action == KeyEvent.ACTION_DOWN);
        pushPad();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) == 0
                && (ev.getSource() & android.view.InputDevice.SOURCE_GAMEPAD) == 0) {
            return super.dispatchGenericMotionEvent(ev);
        }
        if (useGcAdapter) {
            return super.dispatchGenericMotionEvent(ev);
        }
        if (!shouldPollRawStickSource()) {
            // Main + C stick: pair-wise calibration then convert to GC byte
            // space (center 128, swing ±127). GC Y is inverted relative to
            // Android (up = negative Y on Android, positive Y on GC).
            feedStickPairToBytes(ev, MotionEvent.AXIS_X, MotionEvent.AXIS_Y, mainStickCal,
                    /*invertY*/ true,
                    /*main*/ true);
            feedStickPairToBytes(ev, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, cStickCal,
                    /*invertY*/ true,
                    /*main*/ false);
        }

        // Analog triggers (Melee uses these for shield drop / tech).
        float lt = ev.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float rt = ev.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        if (lt == 0f) lt = ev.getAxisValue(MotionEvent.AXIS_BRAKE);
        if (rt == 0f) rt = ev.getAxisValue(MotionEvent.AXIS_GAS);
        padState.setAnalogTriggerL(lt);
        padState.setAnalogTriggerR(rt);

        // D-pad delivered as HAT_X/HAT_Y axes on most pads.
        float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
        padState.setHat(hx, hy);

        pushPad();
        return true;
    }

    private long lastFeedLog = 0;
    private void feedStickPairToBytes(MotionEvent ev, int androidAxisX, int androidAxisY,
                                      StickCalibration cal, boolean invertY, boolean main) {
        feedStickPairToBytes(ev.getAxisValue(androidAxisX), ev.getAxisValue(androidAxisY),
                cal, invertY, main);
    }

    private void feedStickPairToBytes(float rawX, float rawY,
                                      StickCalibration cal, boolean invertY, boolean main) {
        cal.apply(rawX, rawY, stickOut);
        float fx = stickOut.x;
        // Melee's float-stick convention has stickY positive = up; Android
        // AXIS_Y is positive = down. invertY captures that.
        float fy = invertY ? -stickOut.y : stickOut.y;
        // Byte encoding stays around for the SI override path (kept as a
        // fallback in case the direct-memory write isn't applied on some
        // frames — better to have stale-but-calibrated bytes than raw).
        int byteX = stickByteFromUnit(fx);
        int byteY = stickByteFromUnit(fy);
        if (main) {
            padState.setMainStick(fx, fy);
        } else {
            padState.setCStick(fx, fy);
        }
        if (INPUT_DIAGNOSTICS) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastFeedLog > 200 && (Math.abs(rawX) > 0.05f || Math.abs(rawY) > 0.05f)) {
                lastFeedLog = now;
                android.util.Log.i(TAG, "feedPad " + (main ? "main" : "C")
                        + " raw=(" + String.format("%+.2f", rawX) + ", " + String.format("%+.2f", rawY)
                        + ") cal=(" + String.format("%+.2f", stickOut.x) + ", " + String.format("%+.2f", stickOut.y)
                        + ") byte=(" + byteX + ", " + byteY + ")"
                        + " melee=(" + String.format("%+.2f", fx) + ", " + String.format("%+.2f", fy) + ")");
            }
        }
    }

    private boolean feedRawStickState() {
        if (rawStickInput == null) {
            return false;
        }

        RawStickState state = rawStickInput.snapshot();
        if (state == null) {
            return false;
        }
        boolean fed = false;
        Float mainX = state.valueForAxis(MotionEvent.AXIS_X);
        Float mainY = state.valueForAxis(MotionEvent.AXIS_Y);
        if (mainX != null && mainY != null) {
            feedStickPairToBytes(mainX, mainY, mainStickCal, true, true);
            fed = true;
        }

        Float cX = state.valueForAxis(MotionEvent.AXIS_Z);
        Float cY = state.valueForAxis(MotionEvent.AXIS_RZ);
        if (cX != null && cY != null) {
            feedStickPairToBytes(cX, cY, cStickCal, true, false);
            fed = true;
        }

        return fed;
    }

    private boolean hasRawStickSource() {
        return rawStickInput != null;
    }

    private boolean shouldPollRawStickSource() {
        return !useGcAdapter && hasRawStickSource() && !BuildConfig.FORCE_TOUCH_CONTROLS;
    }

    private void updateTouchOverlayVisibility() {
        if (touchOverlay == null) return;
        // Replay mode has no input; show the playback HUD instead of the
        // virtual gamepad.
        if (isReplayMode) {
            if (touchOverlayVisible) {
                touchOverlayVisible = false;
                touchOverlay.setControlsEnabled(false);
                touchOverlay.setVisibility(View.GONE);
            }
            return;
        }
        boolean show = !useGcAdapter && (BuildConfig.FORCE_TOUCH_CONTROLS
                || !PhysicalControllerDetector.hasUsableP1Controller(rawStickInput));
        if (show == touchOverlayVisible) return;

        touchOverlayVisible = show;
        touchOverlay.setControlsEnabled(show);
        touchOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            ui.removeCallbacks(rawInputPoll);
            padState.reset();
            pushPad();
        } else if (shouldPollRawStickSource()) {
            ui.post(rawInputPoll);
        }
        Log.i(TAG, "touch controls " + (show ? "shown" : "hidden")
                + " force=" + BuildConfig.FORCE_TOUCH_CONTROLS
                + " rawSource=" + hasRawStickSource());
    }

    private String rawSourceLabel() {
        if (rawStickInput != null) return rawStickInput.label();
        return "Android MotionEvent fallback";
    }

    private static int stickByteFromUnit(float v) {
        int b = Math.round(v * 127f + 128f);
        if (b < 0) return 0;
        if (b > 255) return 255;
        return b;
    }

    private int mapKeyToGcBit(int keyCode) {
        return buttonMap.gcBitForKey(keyCode);
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
