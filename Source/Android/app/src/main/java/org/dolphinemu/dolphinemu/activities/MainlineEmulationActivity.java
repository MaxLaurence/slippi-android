package org.dolphinemu.dolphinemu.activities;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.dolphinemu.dolphinemu.BuildConfig;
import org.dolphinemu.dolphinemu.MainlineCore;
import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;
import org.dolphinemu.dolphinemu.controller.ButtonMap;
import org.dolphinemu.dolphinemu.controller.ControllerProfile;
import org.dolphinemu.dolphinemu.controller.GameCubePadState;
import org.dolphinemu.dolphinemu.controller.StickCalibration;
import org.dolphinemu.dolphinemu.controller.TouchOverlayLayoutStore;
import org.dolphinemu.dolphinemu.features.settings.model.NativeConfig;
import org.dolphinemu.dolphinemu.replay.GeckoOverride;
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization;
import org.dolphinemu.dolphinemu.utils.PhysicalControllerDetector;
import org.dolphinemu.dolphinemu.utils.RawStickInputProvider;
import org.dolphinemu.dolphinemu.utils.RawStickInputProviders;
import org.dolphinemu.dolphinemu.utils.RawStickState;
import org.dolphinemu.dolphinemu.views.TouchControlOverlayView;

import java.io.File;

public class MainlineEmulationActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    public static final String EXTRA_ISO_PATH = "iso_path";
    public static final String EXTRA_USE_GC_ADAPTER = "use_gc_adapter";
    public static final String EXTRA_LAUNCH_MODE = "launch_mode";
    public static final String LAUNCH_MODE_LIVE = "live";
    public static final String LAUNCH_MODE_TRAINING = "training";
    private static final String TAG = "MainlineEmu";
    private static final String PREF_KEY_BACKEND = "backend";
    private static final String PREF_KEY_AUDIO_BACKEND = "audio_backend";
    private static final String PREF_KEY_AUDIO_BUFFER_BURSTS = "audio_buffer_bursts";
    private static final String BACKEND_VULKAN = "Vulkan";
    private static final String AUDIO_BACKEND_OPENSLES = "OpenSLES";
    private static final int AUDIO_BURSTS_BALANCED = 4;
    private static final int EXI_DEVICE_MEMORYCARD = 1;
    private static final int EXI_DEVICE_SLIPPI = 13;
    private static final int EXI_DEVICE_NONE = 0xFF;
    private static final int SI_NONE = 0;
    private static final int SI_GC_CONTROLLER = 6;
    private static final int SI_WIIU_ADAPTER = 12;
    private static final long RAW_INPUT_FALLBACK_POLL_MS = 4L;
    private static final int RAW_INPUT_WAIT_MS = 16;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StickCalibration.Out stickOut = new StickCalibration.Out();
    private final GameCubePadState padState = new GameCubePadState(0);
    private Thread emuThread;
    private HandlerThread rawInputThread;
    private Handler rawInputHandler;
    private volatile boolean emuStarted;
    private volatile boolean rawInputPolling;
    private StickCalibration mainStickCal = StickCalibration.IDENTITY;
    private StickCalibration cStickCal = StickCalibration.IDENTITY;
    private ButtonMap buttonMap = ButtonMap.defaults();
    private RawStickInputProvider rawStickInput;
    private TouchControlOverlayView touchOverlay;
    private boolean touchOverlayVisible;
    private String isoPath;
    private boolean useGcAdapter;
    private boolean isTrainingMode;
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
    private final Runnable rawInputPoll = new Runnable() {
        @Override
        public void run() {
            if (!rawInputPolling || !shouldPollRawStickSource()) return;

            RawStickState state = rawStickInput != null && rawStickInput.supportsBlockingWait()
                    ? rawStickInput.waitForSnapshot(RAW_INPUT_WAIT_MS)
                    : (rawStickInput == null ? null : rawStickInput.snapshot());
            if (feedRawStickState(state)) {
                pushPad();
                postRawInputPoll();
                return;
            }

            if (rawStickInput != null && rawStickInput.keepPollingWhenUnavailable()) {
                postRawInputPoll();
                return;
            }

            rawInputPolling = false;
            mainStickCal = mainStickCal.withOuterScaleEnabled(false);
            cStickCal = cStickCal.withOuterScaleEnabled(false);
            if (rawStickInput != null) {
                rawStickInput.stop();
                rawStickInput = null;
            }
            Log.w(TAG, "raw gamepad axes disappeared; falling back to MotionEvent sticks");
            ui.post(() -> updateTouchOverlayVisibility());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_emulation);

        touchOverlay = findViewById(R.id.touch_overlay);
        if (touchOverlay != null) {
            touchOverlay.setListener(touchOverlayListener);
            touchOverlay.setControlsEnabled(false);
            touchOverlay.setVisibility(View.GONE);
        }
        View replayHud = findViewById(R.id.replay_hud);
        if (replayHud != null) replayHud.setVisibility(View.GONE);

        SurfaceView surfaceView = findViewById(R.id.emulation_surface);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.requestFocus();

        isoPath = getIntent().getStringExtra(EXTRA_ISO_PATH);
        useGcAdapter = getIntent().getBooleanExtra(EXTRA_USE_GC_ADAPTER, false);
        isTrainingMode = LAUNCH_MODE_TRAINING.equals(
                getIntent().getStringExtra(EXTRA_LAUNCH_MODE));
        if (TextUtils.isEmpty(isoPath) || !new File(isoPath).isFile()) {
            Toast.makeText(this, "No ISO path passed", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        UserDirectoryBootstrap.ensureMainlineLayout(this);
        NativeLibrary.setMainlineEmulationActivity(this);
        if (!NativeLibrary.isNativeLibraryLoaded()) {
            Toast.makeText(this, R.string.core_mainline_launch_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setupCalibratedInput();

        try {
            configureMainlineDirectories();
            NativeLibrary.Initialize();
            applyMainlineRuntimeConfig();
            if (!useGcAdapter) {
                padState.reset();
                pushPad();
            }
        } catch (Throwable t) {
            Log.e(TAG, "mainline native initialization failed", t);
            Toast.makeText(this, R.string.core_mainline_launch_failed, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        if (emuStarted) {
            try {
                NativeLibrary.UnPauseEmulation();
            } catch (Throwable ignored) {
            }
        }
        updateTouchOverlayVisibility();
        ui.post(controllerDetectorPoll);
        if (shouldPollRawStickSource()) startRawInputPolling();
    }

    @Override
    protected void onPause() {
        stopRawInputPolling();
        ui.removeCallbacks(controllerDetectorPoll);
        super.onPause();
        if (emuStarted) {
            try {
                NativeLibrary.PauseEmulation(true);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        shutdownRawInputThread();
        ui.removeCallbacks(controllerDetectorPoll);
        shutdownEmuThreadSync();
        try {
            NativeLibrary.ClearPadOverride(0);
        } catch (Throwable ignored) {
        }
        if (rawStickInput != null) {
            rawStickInput.stop();
            rawStickInput = null;
        }
        NativeLibrary.clearEmulationActivity();
        super.onDestroy();
        if (isFinishing()) {
            Log.i(TAG, "exiting mainline process to avoid stale native globals");
            Process.killProcess(Process.myPid());
        }
    }

    @Override
    public void onBackPressed() {
        shutdownEmuThreadSync();
        super.onBackPressed();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        NativeLibrary.SurfaceChanged(holder.getSurface());
        startEmulationIfNeeded();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        NativeLibrary.SurfaceChanged(holder.getSurface());
        startEmulationIfNeeded();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        NativeLibrary.SurfaceDestroyed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (useGcAdapter) {
            return super.dispatchKeyEvent(event);
        }
        int action = event.getAction();
        int bit = buttonMap.gcBitForKey(event.getKeyCode());
        if (bit == 0) return super.dispatchKeyEvent(event);
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event);
        }
        padState.setButton(bit, action == KeyEvent.ACTION_DOWN);
        pushPad();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) == 0
                && (event.getSource() & android.view.InputDevice.SOURCE_GAMEPAD) == 0) {
            return super.dispatchGenericMotionEvent(event);
        }
        if (useGcAdapter) {
            return super.dispatchGenericMotionEvent(event);
        }
        if (!shouldPollRawStickSource()) {
            feedStickPairToBytes(event.getAxisValue(MotionEvent.AXIS_X),
                    event.getAxisValue(MotionEvent.AXIS_Y), mainStickCal, true, true);
            feedStickPairToBytes(event.getAxisValue(MotionEvent.AXIS_Z),
                    event.getAxisValue(MotionEvent.AXIS_RZ), cStickCal, true, false);
        }

        float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        if (lt == 0f) lt = event.getAxisValue(MotionEvent.AXIS_BRAKE);
        if (rt == 0f) rt = event.getAxisValue(MotionEvent.AXIS_GAS);
        padState.setAnalogTriggerL(lt);
        padState.setAnalogTriggerR(rt);
        padState.setHat(event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y));
        pushPad();
        return true;
    }

    public void initInputPointer() {
    }

    public void onTitleChangedFromNative() {
    }

    private void configureMainlineDirectories() {
        File userDir = MainlineCore.userDir(this);
        File cacheDir = MainlineCore.cacheDir(this);
        File sysDir = MainlineCore.sysDir(this);
        File driverDir = new File(getFilesDir(), "MainlineGPUDrivers");
        File extractedDriverDir = new File(driverDir, "Extracted");
        File tmpDriverDir = new File(driverDir, "Tmp");
        File redirectDriverDir = new File(driverDir, "FileRedirect");
        cacheDir.mkdirs();
        extractedDriverDir.mkdirs();
        tmpDriverDir.mkdirs();
        redirectDriverDir.mkdirs();

        NativeLibrary.SetUserDirectory(userDir.getAbsolutePath());
        NativeLibrary.SetCacheDirectory(cacheDir.getAbsolutePath());
        DirectoryInitialization.SetSysDirectory(sysDir.getAbsolutePath());
        DirectoryInitialization.SetGpuDriverDirectories(
                driverDir.getAbsolutePath(), getApplicationInfo().nativeLibraryDir);
    }

    private void applyMainlineRuntimeConfig() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String backend = prefs.getString(PREF_KEY_BACKEND, BACKEND_VULKAN);
        String requestedAudioBackend = prefs.getString(PREF_KEY_AUDIO_BACKEND,
                AUDIO_BACKEND_OPENSLES);
        String audioBackend = AUDIO_BACKEND_OPENSLES;
        if (!AUDIO_BACKEND_OPENSLES.equals(requestedAudioBackend)) {
            Log.w(TAG, "mainline Android audio supports OpenSLES only; ignoring requested "
                    + requestedAudioBackend);
        }
        int audioBursts = prefs.getInt(PREF_KEY_AUDIO_BUFFER_BURSTS, AUDIO_BURSTS_BALANCED);
        int port0 = useGcAdapter ? SI_WIIU_ADAPTER : SI_GC_CONTROLLER;
        int portN = useGcAdapter ? SI_WIIU_ADAPTER : SI_NONE;

        NativeConfig.setString(NativeConfig.LAYER_BASE, "Dolphin", "Core", "GFXBackend", backend);
        NativeConfig.setString(NativeConfig.LAYER_BASE, "Dolphin", "DSP", "Backend", audioBackend);
        NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "DSP",
                "AndroidAudioBufferBursts", audioBursts);
        NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "Core", "SIDevice0", port0);
        NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "Core", "SIDevice1", portN);
        NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "Core", "SIDevice2", portN);
        NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "Core", "SIDevice3", portN);
        NativeConfig.setBoolean(NativeConfig.LAYER_BASE, "Dolphin", "Slippi",
                "EnableJukebox", false);
        applyMainlineExiRuntimeConfig();
        NativeConfig.save(NativeConfig.LAYER_BASE);
        if (useGcAdapter) {
            NativeLibrary.UpdateGCAdapterScanThread();
        }
    }

    private void applyMainlineExiRuntimeConfig() {
        File userDir = MainlineCore.userDir(this);
        if (isTrainingMode) {
            File gcDir = new File(userDir, "GC");
            if (!gcDir.exists()) gcDir.mkdirs();
            File trainingCard = new File(gcDir, "TrainingMode.USA.raw");
            NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "Core",
                    "SlotA", EXI_DEVICE_MEMORYCARD);
            NativeConfig.setString(NativeConfig.LAYER_BASE, "Dolphin", "Core",
                    "MemcardAPath", trainingCard.getAbsolutePath());
            NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "Core",
                    "SlotB", EXI_DEVICE_NONE);
            NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "Core",
                    "SerialPort1", EXI_DEVICE_NONE);
            GeckoOverride.applyTrainingMode(userDir);
            return;
        }

        NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "Core",
                "SlotA", EXI_DEVICE_NONE);
        NativeConfig.setString(NativeConfig.LAYER_BASE, "Dolphin", "Core",
                "MemcardAPath", "");
        NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "Core",
                "SlotB", EXI_DEVICE_SLIPPI);
        NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "Core",
                "SerialPort1", EXI_DEVICE_NONE);
        GeckoOverride.applyLiveMode(userDir);
    }

    private void setupCalibratedInput() {
        ControllerProfile profile = new ControllerProfile(this);
        mainStickCal = profile.getStick(ControllerProfile.DEVICE_BUILTIN,
                ControllerProfile.Stick.MAIN);
        cStickCal = profile.getStick(ControllerProfile.DEVICE_BUILTIN,
                ControllerProfile.Stick.C);
        buttonMap = profile.getButtonMap(ControllerProfile.DEVICE_BUILTIN);

        if (!useGcAdapter) {
            rawStickInput = RawStickInputProviders.create(this);
            if (rawStickInput != null) {
                rawStickInput.start();
            }
        }
        if (rawStickInput != null && rawStickInput.isPotentiallySaturated()) {
            if (!profile.hasCalibration(ControllerProfile.DEVICE_BUILTIN,
                    ControllerProfile.Stick.MAIN)) {
                mainStickCal = StickCalibration.compensatingMain();
            }
            if (!profile.hasCalibration(ControllerProfile.DEVICE_BUILTIN,
                    ControllerProfile.Stick.C)) {
                cStickCal = StickCalibration.compensatingC();
            }
        }
        if (!hasRawStickSource()) {
            mainStickCal = mainStickCal.withOuterScaleEnabled(false);
            cStickCal = cStickCal.withOuterScaleEnabled(false);
        }
        Log.i(TAG, "loaded mainline calibrated input rawReader=" + hasRawStickSource()
                + " rawSource=" + rawSourceLabel()
                + " mainDz=" + mainStickCal.deadzone
                + " mainSens=" + mainStickCal.sensitivity
                + " mainCap=" + mainStickCal.outputCap
                + " rawScale=" + mainStickCal.useOuterScale);
    }

    private synchronized void pushPad() {
        padState.pushToNative();
    }

    private synchronized void feedStickPairToBytes(float rawX, float rawY,
                                                   StickCalibration cal, boolean invertY,
                                                   boolean main) {
        cal.apply(rawX, rawY, stickOut);
        float fx = stickOut.x;
        float fy = invertY ? -stickOut.y : stickOut.y;
        if (main) {
            padState.setMainStick(fx, fy);
        } else {
            padState.setCStick(fx, fy);
        }
    }

    private synchronized boolean feedRawStickState(RawStickState state) {
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

    private void startRawInputPolling() {
        if (!shouldPollRawStickSource()) {
            return;
        }
        ensureRawInputThread();
        Handler handler = rawInputHandler;
        if (handler == null) {
            return;
        }
        rawInputPolling = true;
        handler.removeCallbacks(rawInputPoll);
        handler.post(rawInputPoll);
    }

    private void stopRawInputPolling() {
        rawInputPolling = false;
        Handler handler = rawInputHandler;
        if (handler != null) {
            handler.removeCallbacks(rawInputPoll);
        }
    }

    private void shutdownRawInputThread() {
        stopRawInputPolling();
        HandlerThread thread = rawInputThread;
        rawInputThread = null;
        rawInputHandler = null;
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void ensureRawInputThread() {
        if (rawInputThread != null && rawInputThread.isAlive()) {
            return;
        }
        rawInputThread = new HandlerThread("SlippiRawInput",
                Process.THREAD_PRIORITY_URGENT_DISPLAY);
        rawInputThread.start();
        rawInputHandler = new Handler(rawInputThread.getLooper());
        rawInputHandler.post(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            } catch (Throwable t) {
                Log.w(TAG, "raw input priority failed: " + t);
            }
            Log.i(TAG, "mainline raw input polling started blocking="
                    + (rawStickInput != null && rawStickInput.supportsBlockingWait()));
        });
    }

    private void postRawInputPoll() {
        Handler handler = rawInputHandler;
        if (handler != null && rawInputPolling) {
            if (rawStickInput != null && rawStickInput.supportsBlockingWait()) {
                handler.post(rawInputPoll);
            } else {
                handler.postDelayed(rawInputPoll, RAW_INPUT_FALLBACK_POLL_MS);
            }
        }
    }

    private String rawSourceLabel() {
        if (rawStickInput != null) return rawStickInput.label();
        return "Android MotionEvent fallback";
    }

    private void updateTouchOverlayVisibility() {
        if (touchOverlay == null) return;
        boolean show = !useGcAdapter && (BuildConfig.FORCE_TOUCH_CONTROLS
                || !PhysicalControllerDetector.hasUsableP1Controller(rawStickInput));
        if (show == touchOverlayVisible) return;

        touchOverlayVisible = show;
        touchOverlay.setControlsEnabled(show);
        touchOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            stopRawInputPolling();
            padState.reset();
            pushPad();
        } else if (shouldPollRawStickSource()) {
            startRawInputPolling();
        }
        Log.i(TAG, "mainline touch controls " + (show ? "shown" : "hidden")
                + " force=" + BuildConfig.FORCE_TOUCH_CONTROLS
                + " rawSource=" + hasRawStickSource());
    }

    private void startEmulationIfNeeded() {
        if (emuStarted) return;
        emuStarted = true;
        try {
            NativeLibrary.SetIsBooting();
        } catch (Throwable ignored) {
        }
        emuThread = new Thread(() -> {
            try {
                NativeLibrary.Run(new String[]{isoPath}, false);
            } catch (Throwable t) {
                Log.e(TAG, "mainline emulation thread failed", t);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.core_mainline_launch_failed, Toast.LENGTH_LONG)
                            .show();
                    finish();
                });
            }
        }, "MainlineDolphin");
        emuThread.start();
    }

    private void shutdownEmuThreadSync() {
        if (!emuStarted) return;
        emuStarted = false;
        try {
            NativeLibrary.StopEmulation();
        } catch (Throwable t) {
            Log.w(TAG, "stop mainline emulation failed", t);
        }
        Thread thread = emuThread;
        emuThread = null;
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }
}
