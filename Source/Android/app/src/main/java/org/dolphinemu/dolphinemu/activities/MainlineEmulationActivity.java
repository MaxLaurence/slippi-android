package org.dolphinemu.dolphinemu.activities;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
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
import org.dolphinemu.dolphinemu.replay.ReplayConfig;
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization;
import org.dolphinemu.dolphinemu.utils.PhysicalControllerDetector;
import org.dolphinemu.dolphinemu.utils.RawStickInputProvider;
import org.dolphinemu.dolphinemu.utils.RawStickInputProviders;
import org.dolphinemu.dolphinemu.utils.RawStickState;
import org.dolphinemu.dolphinemu.views.ReplayHudView;
import org.dolphinemu.dolphinemu.views.TouchControlOverlayView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public class MainlineEmulationActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    public static final String EXTRA_ISO_PATH = "iso_path";
    public static final String EXTRA_REPLAY_PATH = "replay_path";
    public static final String EXTRA_USE_GC_ADAPTER = "use_gc_adapter";
    public static final String EXTRA_LAUNCH_MODE = "launch_mode";
    public static final String LAUNCH_MODE_LIVE = "live";
    public static final String LAUNCH_MODE_REPLAY = "replay";
    public static final String LAUNCH_MODE_TRAINING = "training";
    private static final String TAG = "MainlineEmu";
    private static final String PREF_KEY_BACKEND = "backend";
    private static final String PREF_KEY_AUDIO_BACKEND = "audio_backend";
    private static final String PREF_KEY_AUDIO_BUFFER_BURSTS = "audio_buffer_bursts";
    private static final String BACKEND_VULKAN = "Vulkan";
    private static final String AUDIO_BACKEND_OBOE = "Oboe";
    private static final String AUDIO_BACKEND_AAUDIO = "AAudio";
    private static final String AUDIO_BACKEND_OPENSLES = "OpenSLES";
    private static final int AUDIO_BURSTS_BALANCED = 4;
    private static final String SETTING_PEAK_REFRESH_RATE = "peak_refresh_rate";
    private static final String SETTING_MIN_REFRESH_RATE = "min_refresh_rate";
    private static final String[] PERF_HINT_THREAD_NAMES = {
            "CPU thread",
            "CPU-GPU thread",
            "Video thread",
            "AudioTrack",
            "NetPlay Client",
    };
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
    private PerformanceHintManager.Session hintSession;
    private volatile boolean emuStarted;
    private volatile boolean rawInputPolling;
    private volatile int rawInputThreadTid = -1;
    private StickCalibration mainStickCal = StickCalibration.IDENTITY;
    private StickCalibration cStickCal = StickCalibration.IDENTITY;
    private ButtonMap buttonMap = ButtonMap.defaults();
    private RawStickInputProvider rawStickInput;
    private TouchControlOverlayView touchOverlay;
    private ReplayHudView replayHud;
    private boolean touchOverlayVisible;
    private String isoPath;
    private String replayPath;
    private boolean useGcAdapter;
    private boolean isTrainingMode;
    private boolean isReplayMode;
    private boolean refreshRateSettingsSaved;
    private boolean refreshRateSettingsOverridden;
    private String previousPeakRefreshRate;
    private String previousMinRefreshRate;
    private final Runnable controllerDetectorPoll = new Runnable() {
        @Override
        public void run() {
            updateTouchOverlayVisibility();
            updatePerfHintThreads();
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
        replayHud = findViewById(R.id.replay_hud);
        if (replayHud != null) replayHud.setVisibility(View.GONE);

        SurfaceView surfaceView = findViewById(R.id.emulation_surface);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.requestFocus();

        isoPath = getIntent().getStringExtra(EXTRA_ISO_PATH);
        replayPath = getIntent().getStringExtra(EXTRA_REPLAY_PATH);
        useGcAdapter = getIntent().getBooleanExtra(EXTRA_USE_GC_ADAPTER, false);
        String launchMode = getIntent().getStringExtra(EXTRA_LAUNCH_MODE);
        isTrainingMode = LAUNCH_MODE_TRAINING.equals(launchMode);
        isReplayMode = !isTrainingMode && replayPath != null && new File(replayPath).exists();
        if (isReplayMode) {
            useGcAdapter = false;
        }
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
            if (!useGcAdapter && !isReplayMode) {
                padState.reset();
                pushPad();
            }
            if (isReplayMode && replayHud != null) {
                replayHud.setVisibility(View.VISIBLE);
                replayHud.startPolling();
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
        if (isReplayMode && replayHud != null) {
            replayHud.setVisibility(View.VISIBLE);
            replayHud.startPolling();
        }
    }

    @Override
    protected void onPause() {
        stopRawInputPolling();
        ui.removeCallbacks(controllerDetectorPoll);
        if (replayHud != null) replayHud.stopPolling();
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
        if (replayHud != null) replayHud.stopPolling();
        if (isReplayMode) {
            try {
                NativeLibrary.SetReplaySpeedMode(0);
            } catch (Throwable ignored) {
            }
            try {
                NativeLibrary.SetSlippiInputPath("");
                ReplayConfig.writeEmpty(ReplayConfig.mainlineCommFile(this));
            } catch (Throwable ignored) {
            }
        }
        shutdownEmuThreadSync();
        if (hintSession != null) {
            try {
                hintSession.close();
            } catch (Throwable ignored) {
            }
            hintSession = null;
        }
        try {
            NativeLibrary.ClearPadOverride(0);
        } catch (Throwable ignored) {
        }
        if (rawStickInput != null) {
            rawStickInput.stop();
            rawStickInput = null;
        }
        restoreSystemRefreshRateSettings();
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
        applyLowLatencySurfaceConfig(holder);
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
        if (isReplayMode || useGcAdapter) {
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
        if (isReplayMode || useGcAdapter) {
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
        String audioBackend = sanitizeAudioBackend(prefs.getString(PREF_KEY_AUDIO_BACKEND,
                AUDIO_BACKEND_OBOE));
        int audioBursts = prefs.getInt(PREF_KEY_AUDIO_BUFFER_BURSTS, AUDIO_BURSTS_BALANCED);
        int port0 = useGcAdapter ? SI_WIIU_ADAPTER : SI_GC_CONTROLLER;
        int portN = useGcAdapter ? SI_WIIU_ADAPTER : SI_NONE;

        NativeConfig.setString(NativeConfig.LAYER_BASE, "Dolphin", "Core", "GFXBackend", backend);
        NativeConfig.setString(NativeConfig.LAYER_BASE, "Dolphin", "DSP", "Backend", audioBackend);
        NativeConfig.setInt(NativeConfig.LAYER_BASE, "Dolphin", "DSP",
                "AndroidAudioBufferBursts", audioBursts);
        Log.i(TAG, "mainline runtime config gfx=" + backend
                + " audio=" + audioBackend + "/" + audioBursts);
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

    private String sanitizeAudioBackend(String backend) {
        if (AUDIO_BACKEND_OBOE.equals(backend)
                || AUDIO_BACKEND_AAUDIO.equals(backend)
                || AUDIO_BACKEND_OPENSLES.equals(backend)) {
            return backend;
        }
        return AUDIO_BACKEND_OBOE;
    }

    private void applyMainlineExiRuntimeConfig() {
        File userDir = MainlineCore.userDir(this);
        File playbackConfig = ReplayConfig.mainlineCommFile(this);
        if (isTrainingMode) {
            File gcDir = new File(userDir, "GC");
            if (!gcDir.exists()) gcDir.mkdirs();
            File trainingCard = new File(gcDir, "TrainingMode.USA.raw");
            ReplayConfig.writeEmpty(playbackConfig);
            NativeLibrary.SetSlippiInputPath("");
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
        if (isReplayMode) {
            ReplayConfig.writeNormal(playbackConfig, new File(replayPath));
            GeckoOverride.applyMainlineReplayMode(this, userDir);
            NativeLibrary.SetSlippiInputPath(playbackConfig.getAbsolutePath());
            return;
        }
        ReplayConfig.writeEmpty(playbackConfig);
        GeckoOverride.applyLiveMode(userDir);
        NativeLibrary.SetSlippiInputPath(playbackConfig.getAbsolutePath());
    }

    private void setupCalibratedInput() {
        ControllerProfile profile = new ControllerProfile(this);
        mainStickCal = profile.getStick(ControllerProfile.DEVICE_BUILTIN,
                ControllerProfile.Stick.MAIN);
        cStickCal = profile.getStick(ControllerProfile.DEVICE_BUILTIN,
                ControllerProfile.Stick.C);
        buttonMap = profile.getButtonMap(ControllerProfile.DEVICE_BUILTIN);

        if (!useGcAdapter && !isReplayMode) {
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
        return !isReplayMode && !useGcAdapter && hasRawStickSource()
                && !BuildConfig.FORCE_TOUCH_CONTROLS;
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
        rawInputThreadTid = -1;
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
            rawInputThreadTid = Process.myTid();
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            } catch (Throwable t) {
                Log.w(TAG, "raw input priority failed: " + t);
            }
            Log.i(TAG, "mainline raw input thread tid=" + rawInputThreadTid
                    + " blocking=" + (rawStickInput != null && rawStickInput.supportsBlockingWait())
                    + " waitMs=" + RAW_INPUT_WAIT_MS
                    + " fallbackPollMs=" + RAW_INPUT_FALLBACK_POLL_MS);
            updatePerfHintThreads();
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
        registerPerfHintWhenReady();
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

    private void registerPerfHintWhenReady() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        final PerformanceHintManager mgr =
                (PerformanceHintManager) getSystemService(PERFORMANCE_HINT_SERVICE);
        if (mgr == null) return;
        ui.post(new Runnable() {
            int attempts = 0;

            @Override
            public void run() {
                int[] tids = discoverPerfHintThreadTids(false);
                if (!hasHotPerfThread()) {
                    if (++attempts < 250) {
                        ui.postDelayed(this, 10);
                        return;
                    }
                    tids = discoverPerfHintThreadTids(true);
                }
                if (tids.length == 0) {
                    return;
                }
                try {
                    hintSession = mgr.createHintSession(tids, 16_666_666L);
                    updatePerfHintThreads();
                    Log.i(TAG, "mainline PerformanceHintSession created for tids="
                            + tidsToString(tids));
                } catch (Throwable t) {
                    Log.w(TAG, "mainline PerformanceHintSession failed: " + t);
                }
            }
        });
    }

    private synchronized void updatePerfHintThreads() {
        if (hintSession == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return;
        }
        int[] tids = discoverPerfHintThreadTids(true);
        if (tids.length == 0) {
            return;
        }
        try {
            hintSession.setThreads(tids);
            Log.i(TAG, "mainline PerformanceHintSession threads=" + tidsToString(tids));
        } catch (Throwable t) {
            Log.w(TAG, "mainline PerformanceHintSession setThreads failed: " + t);
        }
    }

    private void applyLowLatencySurfaceConfig(SurfaceHolder holder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    holder.getSurface().setFrameRate(60f,
                            Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                            Surface.CHANGE_FRAME_RATE_ALWAYS);
                } else {
                    holder.getSurface().setFrameRate(60f,
                            Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                }
            } catch (IllegalStateException ignored) {
            }
        }
        preferLowLatencyDisplayMode();
    }

    private void preferLowLatencyDisplayMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        Display display = getWindowManager().getDefaultDisplay();
        if (display == null) {
            return;
        }

        Display.Mode best = null;
        for (Display.Mode mode : display.getSupportedModes()) {
            float refresh = mode.getRefreshRate();
            float multiple = refresh / 60f;
            float nearestMultiple = Math.round(multiple);
            if (nearestMultiple < 1f || Math.abs(multiple - nearestMultiple) > 0.02f) {
                continue;
            }
            if (best == null
                    || refresh > best.getRefreshRate()
                    || (refresh == best.getRefreshRate()
                    && mode.getPhysicalWidth() * mode.getPhysicalHeight()
                    > best.getPhysicalWidth() * best.getPhysicalHeight())) {
                best = mode;
            }
        }
        if (best == null) {
            return;
        }

        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        if (attrs.preferredDisplayModeId == best.getModeId()) {
            temporarilyLiftSystemRefreshRateCap(best.getRefreshRate());
            return;
        }
        attrs.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(attrs);
        Log.i(TAG, "mainline preferred low-latency display mode id=" + best.getModeId()
                + " refresh=" + best.getRefreshRate()
                + " size=" + best.getPhysicalWidth() + "x" + best.getPhysicalHeight());
        temporarilyLiftSystemRefreshRateCap(best.getRefreshRate());
    }

    private void temporarilyLiftSystemRefreshRateCap(float targetRefreshRate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || targetRefreshRate < 61f) {
            return;
        }

        String currentPeak = Settings.System.getString(getContentResolver(),
                SETTING_PEAK_REFRESH_RATE);
        String currentMin = Settings.System.getString(getContentResolver(),
                SETTING_MIN_REFRESH_RATE);
        if (!Settings.System.canWrite(this)) {
            Log.w(TAG, "WRITE_SETTINGS not granted; mainline refresh caps remain peak="
                    + currentPeak + " min=" + currentMin
                    + " while app requested " + targetRefreshRate + "Hz");
            return;
        }

        if (!refreshRateSettingsSaved) {
            previousPeakRefreshRate = currentPeak;
            previousMinRefreshRate = currentMin;
            refreshRateSettingsSaved = true;
        }

        boolean peakAllowed = settingRefreshAtLeast(currentPeak, targetRefreshRate);
        boolean minAllowed = settingRefreshAtLeast(currentMin, targetRefreshRate);
        boolean wrote = false;
        if (!peakAllowed) {
            wrote |= putSystemRefreshRateSetting(SETTING_PEAK_REFRESH_RATE,
                    formatRefreshRate(targetRefreshRate));
        }
        if (!minAllowed) {
            wrote |= putSystemRefreshRateSetting(SETTING_MIN_REFRESH_RATE,
                    formatRefreshRate(targetRefreshRate));
        }
        if (wrote) {
            refreshRateSettingsOverridden = true;
            Log.i(TAG, "mainline temporarily lifted system refresh caps to "
                    + targetRefreshRate + "Hz");
        }
    }

    private boolean settingRefreshAtLeast(String value, float targetRefreshRate) {
        try {
            return value != null && Float.parseFloat(value) >= targetRefreshRate - 0.5f;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String formatRefreshRate(float refreshRate) {
        return String.format(Locale.US, "%.1f", refreshRate);
    }

    private boolean putSystemRefreshRateSetting(String key, String value) {
        try {
            return Settings.System.putString(getContentResolver(), key, value);
        } catch (Throwable t) {
            Log.w(TAG, "mainline system refresh cap write blocked for " + key + "=" + value
                    + ": " + t);
            return false;
        }
    }

    private void restoreSystemRefreshRateSettings() {
        if (!refreshRateSettingsOverridden || !Settings.System.canWrite(this)) {
            return;
        }
        putSystemRefreshRateSetting(SETTING_PEAK_REFRESH_RATE, previousPeakRefreshRate);
        putSystemRefreshRateSetting(SETTING_MIN_REFRESH_RATE, previousMinRefreshRate);
        Log.i(TAG, "mainline restored system refresh caps peak=" + previousPeakRefreshRate
                + " min=" + previousMinRefreshRate);
        refreshRateSettingsOverridden = false;
    }

    private int[] discoverPerfHintThreadTids(boolean allowFallback) {
        Set<Integer> tids = new LinkedHashSet<>();
        File[] taskFiles = new File("/proc/self/task").listFiles();
        if (taskFiles != null) {
            for (File task : taskFiles) {
                int tid;
                try {
                    tid = Integer.parseInt(task.getName());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                String comm = readThreadComm(task);
                if (comm == null) {
                    continue;
                }
                for (String wanted : PERF_HINT_THREAD_NAMES) {
                    if (wanted.equals(comm)) {
                        tids.add(tid);
                        break;
                    }
                }
            }
        }

        if (rawInputThreadTid > 0 && shouldPollRawStickSource()) {
            tids.add(rawInputThreadTid);
        }
        if (allowFallback) {
            int emuTid = NativeLibrary.GetEmuThreadTid();
            if (emuTid > 0) {
                tids.add(emuTid);
            }
        }

        ArrayList<Integer> list = new ArrayList<>(tids);
        int[] result = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    private boolean hasHotPerfThread() {
        File[] taskFiles = new File("/proc/self/task").listFiles();
        if (taskFiles == null) {
            return false;
        }
        for (File task : taskFiles) {
            String comm = readThreadComm(task);
            if ("CPU thread".equals(comm) || "CPU-GPU thread".equals(comm)
                    || "Video thread".equals(comm)) {
                return true;
            }
        }
        return false;
    }

    private String readThreadComm(File taskDir) {
        File comm = new File(taskDir, "comm");
        try (BufferedReader reader = new BufferedReader(new FileReader(comm))) {
            return reader.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String tidsToString(int[] tids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(tids[i]);
        }
        return sb.append(']').toString();
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
