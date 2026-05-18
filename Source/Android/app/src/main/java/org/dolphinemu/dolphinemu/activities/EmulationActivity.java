package org.dolphinemu.dolphinemu.activities;

import android.app.GameManager;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.FileObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.dolphinemu.dolphinemu.BuildConfig;
import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;
import org.dolphinemu.dolphinemu.controller.ButtonMap;
import org.dolphinemu.dolphinemu.controller.ControllerProfile;
import org.dolphinemu.dolphinemu.controller.GameCubePadState;
import org.dolphinemu.dolphinemu.controller.StickCalibration;
import org.dolphinemu.dolphinemu.controller.TouchOverlayLayoutStore;
import org.dolphinemu.dolphinemu.gpu.GpuDriverManager;
import org.dolphinemu.dolphinemu.replay.GeckoOverride;
import org.dolphinemu.dolphinemu.replay.ReplayConfig;
import org.dolphinemu.dolphinemu.settings.DolphinSettings;
import org.dolphinemu.dolphinemu.utils.ControllerDiagnosticsCapture;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Hosts the SurfaceView that the C++ renderer draws into and pumps the
 * emulator thread.
 */
public class EmulationActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    public static final String EXTRA_ISO_PATH = "iso_path";
    public static final String EXTRA_USE_GC_ADAPTER = "use_gc_adapter";
    /** Absolute path to a .slp file. Triggers replay playback mode when present. */
    public static final String EXTRA_REPLAY_PATH = "replay_path";
    public static final String EXTRA_LAUNCH_MODE = "launch_mode";
    public static final String EXTRA_TRAINING_SLIPPI_PARITY_DELAY =
            "training_slippi_parity_delay";
    public static final String LAUNCH_MODE_LIVE = "live";
    public static final String LAUNCH_MODE_LOCAL_PLAY = "local_play";
    public static final String LAUNCH_MODE_REPLAY = "replay";
    public static final String LAUNCH_MODE_TRAINING = "training";
    private static final String TAG = "SlippiEmu";
    private static final boolean INPUT_DIAGNOSTICS = false;
    private static final boolean LATENCY_TRACE = BuildConfig.DEBUG;
    private static final long RAW_INPUT_FALLBACK_POLL_MS = 4L;
    private static final long RAW_INPUT_LIVE_FALLBACK_POLL_MS = 2L;
    private static final int RAW_INPUT_WAIT_MS = 16;
    private static final long INPUT_LATENCY_LOG_INTERVAL_MS = 2000L;
    private static final long FRAME_LATENCY_LOG_INTERVAL_MS = 5000L;
    private static final String SETTING_PEAK_REFRESH_RATE = "peak_refresh_rate";
    private static final String SETTING_MIN_REFRESH_RATE = "min_refresh_rate";
    private static final String[] PERF_HINT_THREAD_NAMES = {
            "CPU thread",
            "CPU-GPU thread",
            "Video thread",
            "AudioTrack",
            "NetPlay Client",
            "GC Adapter Read Thread",
            "GC Adapter Write Thread",
            "GC Adapter Read",
            "GC Adapter Writ",
            "SlippiRawInput"
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread rawInputThread;
    private Handler rawInputHandler;
    private FileObserver replayStagingObserver;
    private volatile boolean rawInputPolling;
    private volatile int rawInputThreadTid = -1;
    private long lastInputLatencyLogMs;
    private long lastFrameLatencyLogMs;
    private long previousFrameTimeNs;
    private final ArrayList<Long> frameDeltasUs = new ArrayList<>(360);
    private final Choreographer.FrameCallback frameLatencyCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!emuStarted) {
                previousFrameTimeNs = 0L;
                return;
            }
            if (previousFrameTimeNs > 0L) {
                long deltaUs = Math.max(0L, (frameTimeNanos - previousFrameTimeNs) / 1000L);
                recordFrameDelta(deltaUs);
            }
            previousFrameTimeNs = frameTimeNanos;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

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
    private View loadingOverlay;
    private ProgressBar loadingProgress;
    private TextView loadingLabel;
    private boolean touchOverlayVisible;
    private boolean useGcAdapter;
    private boolean isReplayMode;
    private boolean isTrainingMode;
    private boolean isLocalPlayMode;
    private boolean trainingSlippiParityDelay;
    private String launchMode = LAUNCH_MODE_LIVE;
    private String previousPeakRefreshRate;
    private String previousMinRefreshRate;
    private boolean refreshRateSettingsSaved;
    private boolean refreshRateSettingsOverridden;
    private final Runnable rawInputPoll = new Runnable() {
        @Override
        public void run() {
            if (!rawInputPolling || !shouldPollRawStickSource()) return;

            long rawWaitStartMs = SystemClock.uptimeMillis();
            RawStickState state = rawStickInput != null && rawStickInput.supportsBlockingWait()
                    ? rawStickInput.waitForSnapshot(RAW_INPUT_WAIT_MS)
                    : (rawStickInput == null ? null : rawStickInput.snapshot());
            if (feedRawStickState(state)) {
                pushPad();
                ControllerDiagnosticsCapture.recordInputEvent("raw:" + rawSourceLabel(),
                        describeRawStickState(state) + " pad=" + padState.snapshotString());
                traceInputEvent("raw", rawWaitStartMs, 0);
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
    private final Runnable controllerDetectorPoll = new Runnable() {
        @Override
        public void run() {
            updateTouchOverlayVisibility();
            updatePerfHintThreads();
            ui.postDelayed(this, 1000);
        }
    };
    private final Runnable bootStatusPoll = new Runnable() {
        @Override
        public void run() {
            if (!emuStarted) {
                return;
            }
            try {
                if (NativeLibrary.IsRunning()) {
                    hideLoadingOverlay();
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "emulation boot status unavailable", t);
            }
            ui.postDelayed(this, 100);
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
        loadingOverlay = findViewById(R.id.emulation_loading_overlay);
        loadingProgress = findViewById(R.id.emulation_loading_progress);
        loadingLabel = findViewById(R.id.emulation_loading_label);
        showLoadingOverlay();
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
        GpuDriverManager.prepareNativeDirectoriesForCurrentBackend(this);
        NativeLibrary.SetConfig("GFX.ini", "Settings", "DriverLibName",
                GpuDriverManager.selectedLibraryNameForCurrentBackend(this));
        applyImmersive();
        updateTouchOverlayVisibility();

        String iso = getIntent().getStringExtra(EXTRA_ISO_PATH);
        if (iso == null) {
            Toast.makeText(this, "No ISO path passed", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        NativeLibrary.SetFilename(iso);

        String replayPath = getIntent().getStringExtra(EXTRA_REPLAY_PATH);
        launchMode = getIntent().getStringExtra(EXTRA_LAUNCH_MODE);
        if (launchMode == null) launchMode = LAUNCH_MODE_LIVE;
        isTrainingMode = LAUNCH_MODE_TRAINING.equals(launchMode);
        isLocalPlayMode = LAUNCH_MODE_LOCAL_PLAY.equals(launchMode);
        trainingSlippiParityDelay = isTrainingMode
                && getIntent().getBooleanExtra(EXTRA_TRAINING_SLIPPI_PARITY_DELAY, false);
        isReplayMode = !isTrainingMode && !isLocalPlayMode
                && replayPath != null && new File(replayPath).exists();
        ControllerDiagnosticsCapture.recordLaunch(this, "ishiiruka", launchMode, useGcAdapter,
                "training_slippi_parity_delay=" + trainingSlippiParityDelay);
        ReplayConfig.ensureReplayDirectory(this);
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SlippiReplayDir",
                ReplayConfig.nativeReplayWriteDir(this).getAbsolutePath());
        NativeLibrary.SetConfig("Dolphin.ini", "Core", "SlippiReplayMonthFolders", "False");
        if (!isReplayMode && !isTrainingMode && !isLocalPlayMode) {
            replayStagingObserver = ReplayConfig.createStagingDrainObserver(this, ui);
            if (replayStagingObserver != null) replayStagingObserver.startWatching();
        }

        if (isTrainingMode) {
            ReplayConfig.writeEmpty(this);
            GeckoOverride.applyTrainingMode(this, UserDirectoryBootstrap.userDir(this),
                    trainingSlippiParityDelay);
            NativeLibrary.SetSlippiInputPath(trainingSlippiParityDelay
                    ? ReplayConfig.commFile(this).getAbsolutePath()
                    : "");
            NativeLibrary.SetEXIDeviceOverride(0, NativeLibrary.EXI_DEVICE_MEMORYCARD);
            NativeLibrary.SetEXIDeviceOverride(1, trainingSlippiParityDelay
                    ? NativeLibrary.EXI_DEVICE_SLIPPI
                    : NativeLibrary.EXI_DEVICE_NONE);
            NativeLibrary.SetEXIDeviceOverride(2, NativeLibrary.EXI_DEVICE_NONE);
        } else if (isLocalPlayMode) {
            NativeLibrary.SetEXIDeviceOverride(0, NativeLibrary.EXI_DEVICE_NONE);
            NativeLibrary.SetEXIDeviceOverride(1, NativeLibrary.EXI_DEVICE_NONE);
            NativeLibrary.SetEXIDeviceOverride(2, NativeLibrary.EXI_DEVICE_NONE);
            ReplayConfig.writeEmpty(this);
            GeckoOverride.applyLocalPlayMode(this);
            NativeLibrary.SetSlippiInputPath("");
        } else if (isReplayMode) {
            NativeLibrary.SetEXIDeviceOverride(0, NativeLibrary.EXI_DEVICE_NONE);
            NativeLibrary.SetEXIDeviceOverride(1, NativeLibrary.EXI_DEVICE_SLIPPI);
            NativeLibrary.SetEXIDeviceOverride(2, NativeLibrary.EXI_DEVICE_NONE);
            ReplayConfig.writeNormal(this, new File(replayPath));
            // Swap in the playback-mode gecko codes so the title
            // screen runs the Slippi Playback boot path instead of
            // dropping into the Online menu.
            GeckoOverride.applyReplayMode(this);
            NativeLibrary.SetSlippiInputPath(ReplayConfig.commFile(this).getAbsolutePath());
        } else {
            NativeLibrary.SetEXIDeviceOverride(0, NativeLibrary.EXI_DEVICE_NONE);
            NativeLibrary.SetEXIDeviceOverride(1, NativeLibrary.EXI_DEVICE_SLIPPI);
            NativeLibrary.SetEXIDeviceOverride(2, NativeLibrary.EXI_DEVICE_NONE);
            // Live mode: write a neutral file AND point at it — defense
            // in depth so a stale config from a prior replay launch can
            // never bleed into a netplay session.
            ReplayConfig.writeEmpty(this);
            GeckoOverride.applyLiveMode(this);
            NativeLibrary.SetSlippiInputPath(ReplayConfig.commFile(this).getAbsolutePath());
        }

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
        shutdownRawInputThread();
        ui.removeCallbacks(controllerDetectorPoll);
        ui.removeCallbacks(bootStatusPoll);
        if (replayStagingObserver != null) {
            replayStagingObserver.stopWatching();
            replayStagingObserver = null;
        }
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
        ControllerDiagnosticsCapture.stopActiveCapture("activity destroyed");
        if (rawStickInput != null) {
            rawStickInput.stop();
            rawStickInput = null;
        }
        restoreSystemRefreshRateSettings();
        // Idempotent: harmless if onBackPressed already stopped us.
        shutdownEmuThreadSync();
        if (isReplayMode) {
            ReplayConfig.clearPlaybackCache(this);
        } else {
            ReplayConfig.drainNativeReplayStaging(this);
        }
        try {
            NativeLibrary.ClearEXIDeviceOverrides();
        } catch (Throwable ignored) {}
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
        startFrameLatencyTrace();
        updateTouchOverlayVisibility();
        ui.post(controllerDetectorPoll);
        logGameMode();
        if (shouldPollRawStickSource()) startRawInputPolling();
        if (isReplayMode && replayHud != null) {
            replayHud.setVisibility(View.VISIBLE);
            replayHud.startPolling();
        }
    }

    @Override
    protected void onPause() {
        stopRawInputPolling();
        stopFrameLatencyTrace();
        ui.removeCallbacks(controllerDetectorPoll);
        if (replayHud != null) replayHud.stopPolling();
        super.onPause();
        if (emuStarted) {
            try { NativeLibrary.PauseEmulation(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        applyLowLatencySurfaceConfig(holder);
        NativeLibrary.SurfaceChanged(holder.getSurface());
        if (!emuStarted) {
            emuStarted = true;
            emuThread = new Thread(NativeLibrary::Run, "DolphinEmuMain");
            emuThread.start();
            ControllerDiagnosticsCapture.startIfArmed(
                    this, "ishiiruka", launchMode, useGcAdapter);
            registerPerfHintWhenReady();
            startFrameLatencyTrace();
            ui.post(bootStatusPoll);
        }
    }

    /**
     * Register PerformanceHintManager against the native worker threads that
     * actually carry frame deadlines. The Java Run() entry thread goes idle
     * after BootCore starts CPU/video/audio workers, so creating the session
     * too early wastes the hint on the wrong TID.
     */
    private void registerPerfHintWhenReady() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        final PerformanceHintManager mgr =
                (PerformanceHintManager) getSystemService(PERFORMANCE_HINT_SERVICE);
        if (mgr == null) return;
        ui.post(new Runnable() {
            int attempts = 0;
            @Override public void run() {
                int[] tids = discoverPerfHintThreadTids(/*allowFallback*/ false);
                if (!hasHotPerfThread()) {
                    if (++attempts < 250) {
                        ui.postDelayed(this, 10);
                        return;
                    }
                    tids = discoverPerfHintThreadTids(/*allowFallback*/ true);
                }
                if (tids.length == 0) {
                    return;
                }
                try {
                    hintSession = mgr.createHintSession(tids, 16_666_666L);
                    updatePerfHintThreads();
                    Log.i(TAG, "PerformanceHintSession created for tids=" + tidsToString(tids));
                } catch (Throwable t) {
                    Log.w(TAG, "PerformanceHintSession failed: " + t);
                }
            }
        });
    }

    private synchronized void updatePerfHintThreads() {
        if (hintSession == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return;
        }
        int[] tids = discoverPerfHintThreadTids(/*allowFallback*/ true);
        if (tids.length == 0) {
            return;
        }
        try {
            hintSession.setThreads(tids);
            Log.i(TAG, "PerformanceHintSession threads=" + tidsToString(tids));
        } catch (Throwable t) {
            Log.w(TAG, "PerformanceHintSession setThreads failed: " + t);
        }
    }

    private void applyLowLatencySurfaceConfig(SurfaceHolder holder) {
        Trace.beginSection("SlippiSurfaceLowLatency");
        Display.Mode bestMode = findLowLatencyDisplayMode();
        float targetRefreshRate = bestMode != null ? bestMode.getRefreshRate() : 60f;
        // Melee is a fixed 60Hz workload, but high-refresh panels reduce
        // scanout wait. Keep the surface vote aligned with the display mode
        // request instead of letting a 60Hz layer vote pull the panel down.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    holder.getSurface().setFrameRate(targetRefreshRate,
                            Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                            Surface.CHANGE_FRAME_RATE_ALWAYS);
                } else {
                    holder.getSurface().setFrameRate(targetRefreshRate,
                            Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                }
            } catch (IllegalStateException ignored) {
            }
        }
        preferLowLatencyDisplayMode(bestMode);
        Trace.endSection();
    }

    private Display.Mode findLowLatencyDisplayMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }

        Display display = getWindowManager().getDefaultDisplay();
        if (display == null) {
            return null;
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
        return best;
    }

    private void preferLowLatencyDisplayMode(Display.Mode best) {
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
        Log.i(TAG, "preferred low-latency display mode id=" + best.getModeId()
                + " refresh=" + best.getRefreshRate()
                + " size=" + best.getPhysicalWidth() + "x" + best.getPhysicalHeight());
        temporarilyLiftSystemRefreshRateCap(best.getRefreshRate());
    }

    private void temporarilyLiftSystemRefreshRateCap(float targetRefreshRate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || targetRefreshRate < 61f) {
            return;
        }

        String currentPeak = Settings.System.getString(getContentResolver(), SETTING_PEAK_REFRESH_RATE);
        String currentMin = Settings.System.getString(getContentResolver(), SETTING_MIN_REFRESH_RATE);
        if (!Settings.System.canWrite(this)) {
            Log.w(TAG, "WRITE_SETTINGS not granted; system refresh caps remain peak="
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
            Log.i(TAG, "temporarily lifted system refresh caps to " + targetRefreshRate
                    + "Hz (previous peak=" + previousPeakRefreshRate
                    + " min=" + previousMinRefreshRate + ")");
        } else if (!peakAllowed || !minAllowed) {
            Log.w(TAG, "system refresh caps still block " + targetRefreshRate
                    + "Hz (peak=" + currentPeak + " min=" + currentMin + ")");
        } else {
            Log.i(TAG, "system refresh caps already allow " + targetRefreshRate
                    + "Hz (peak=" + currentPeak + " min=" + currentMin + ")");
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
            Log.w(TAG, "system refresh cap write blocked for " + key + "=" + value
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
        Log.i(TAG, "restored system refresh caps peak=" + previousPeakRefreshRate
                + " min=" + previousMinRefreshRate);
        refreshRateSettingsOverridden = false;
    }

    private void logGameMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        try {
            GameManager manager = getSystemService(GameManager.class);
            if (manager != null) {
                Log.i(TAG, "Android game mode=" + manager.getGameMode());
            }
        } catch (Throwable t) {
            Log.w(TAG, "GameMode query failed: " + t);
        }
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

    private synchronized void pushPad() {
        padState.pushToNative();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (useGcAdapter) {
            return super.dispatchKeyEvent(event);
        }
        traceInputEvent("key", event.getEventTime(), 0);
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
        ControllerDiagnosticsCapture.recordInputEvent("key",
                "device=" + safeDeviceName(event.getDevice())
                        + " deviceId=" + event.getDeviceId()
                        + " descriptor=" + safeDeviceDescriptor(event.getDevice())
                        + " keyCode=" + keyCode
                        + " action=" + action
                        + " repeat=" + event.getRepeatCount()
                        + " source=0x" + Integer.toHexString(event.getSource())
                        + " gcBit=0x" + Integer.toHexString(bit)
                        + " pad=" + padState.snapshotString());
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
        traceInputEvent("motion", ev.getEventTime(), ev.getHistorySize());
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
        ControllerDiagnosticsCapture.recordInputEvent("motion",
                describeMotionEvent(ev) + " pad=" + padState.snapshotString());
        return true;
    }

    private long lastFeedLog = 0;
    private synchronized void feedStickPairToBytes(MotionEvent ev, int androidAxisX, int androidAxisY,
                                      StickCalibration cal, boolean invertY, boolean main) {
        feedStickPairToBytes(ev.getAxisValue(androidAxisX), ev.getAxisValue(androidAxisY),
                cal, invertY, main);
    }

    private synchronized void feedStickPairToBytes(float rawX, float rawY,
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

    private String describeRawStickState(RawStickState state) {
        if (state == null) return "state=null";
        return "rawMain=(" + formatAxis(state.valueForAxis(MotionEvent.AXIS_X))
                + "," + formatAxis(state.valueForAxis(MotionEvent.AXIS_Y))
                + ") rawC=(" + formatAxis(state.valueForAxis(MotionEvent.AXIS_Z))
                + "," + formatAxis(state.valueForAxis(MotionEvent.AXIS_RZ))
                + ")";
    }

    private String describeMotionEvent(MotionEvent ev) {
        return "device=" + safeDeviceName(ev.getDevice())
                + " deviceId=" + ev.getDeviceId()
                + " descriptor=" + safeDeviceDescriptor(ev.getDevice())
                + " source=0x" + Integer.toHexString(ev.getSource())
                + " action=" + ev.getActionMasked()
                + " buttonState=0x" + Integer.toHexString(ev.getButtonState())
                + " history=" + ev.getHistorySize()
                + " axes={x=" + formatAxis(ev.getAxisValue(MotionEvent.AXIS_X))
                + ",y=" + formatAxis(ev.getAxisValue(MotionEvent.AXIS_Y))
                + ",z=" + formatAxis(ev.getAxisValue(MotionEvent.AXIS_Z))
                + ",rz=" + formatAxis(ev.getAxisValue(MotionEvent.AXIS_RZ))
                + ",lt=" + formatAxis(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER))
                + ",rt=" + formatAxis(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER))
                + ",brake=" + formatAxis(ev.getAxisValue(MotionEvent.AXIS_BRAKE))
                + ",gas=" + formatAxis(ev.getAxisValue(MotionEvent.AXIS_GAS))
                + ",hat=(" + formatAxis(ev.getAxisValue(MotionEvent.AXIS_HAT_X))
                + "," + formatAxis(ev.getAxisValue(MotionEvent.AXIS_HAT_Y)) + ")}";
    }

    private static String safeDeviceName(android.view.InputDevice device) {
        return device == null ? "" : device.getName();
    }

    private static String safeDeviceDescriptor(android.view.InputDevice device) {
        return device == null ? "" : device.getDescriptor();
    }

    private static String formatAxis(Float value) {
        return value == null ? "" : String.format(Locale.US, "%+.3f", value);
    }

    private static String formatAxis(float value) {
        return String.format(Locale.US, "%+.3f", value);
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
        updatePerfHintThreads();
    }

    private void stopRawInputPolling() {
        rawInputPolling = false;
        Handler handler = rawInputHandler;
        if (handler != null) {
            handler.removeCallbacks(rawInputPoll);
        }
        updatePerfHintThreads();
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
            Log.i(TAG, "raw input thread tid=" + rawInputThreadTid
                    + " blocking=" + (rawStickInput != null && rawStickInput.supportsBlockingWait())
                    + " waitMs=" + RAW_INPUT_WAIT_MS
                    + " fallbackPollMs=" + getRawInputFallbackPollMs());
            updatePerfHintThreads();
        });
    }

    private long getRawInputFallbackPollMs() {
        return LAUNCH_MODE_LIVE.equals(launchMode)
                || LAUNCH_MODE_LOCAL_PLAY.equals(launchMode)
                ? RAW_INPUT_LIVE_FALLBACK_POLL_MS
                : RAW_INPUT_FALLBACK_POLL_MS;
    }

    private void postRawInputPoll() {
        Handler handler = rawInputHandler;
        if (handler != null && rawInputPolling) {
            if (rawStickInput != null && rawStickInput.supportsBlockingWait()) {
                handler.post(rawInputPoll);
            } else {
                handler.postDelayed(rawInputPoll, getRawInputFallbackPollMs());
            }
        }
    }

    private int[] discoverPerfHintThreadTids(boolean allowFallback) {
        Set<Integer> tids = new LinkedHashSet<>();
        File taskDir = new File("/proc/self/task");
        File[] taskFiles = taskDir.listFiles();
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
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        for (int i = 0; i < tids.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(tids[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    private void traceInputEvent(String source, long eventTimeMs, int historySize) {
        if (!LATENCY_TRACE) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastInputLatencyLogMs < INPUT_LATENCY_LOG_INTERVAL_MS) {
            return;
        }
        lastInputLatencyLogMs = now;
        Log.i(TAG, "inputTrace source=" + source
                + " eventAgeMs=" + Math.max(0L, now - eventTimeMs)
                + " history=" + historySize
                + " rawPolling=" + rawInputPolling
                + " rawSource=" + rawSourceLabel()
                + " padOverrideAgeUs=" + NativeLibrary.GetPadOverrideAgeUs(0));
    }

    private void startFrameLatencyTrace() {
        if (!LATENCY_TRACE && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        Choreographer.getInstance().removeFrameCallback(frameLatencyCallback);
        Choreographer.getInstance().postFrameCallback(frameLatencyCallback);
    }

    private void stopFrameLatencyTrace() {
        Choreographer.getInstance().removeFrameCallback(frameLatencyCallback);
        previousFrameTimeNs = 0L;
        frameDeltasUs.clear();
    }

    private void recordFrameDelta(long deltaUs) {
        reportPerfHintFrameDuration(deltaUs);
        if (!LATENCY_TRACE) {
            return;
        }
        frameDeltasUs.add(deltaUs);
        if (frameDeltasUs.size() > 360) {
            frameDeltasUs.remove(0);
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastFrameLatencyLogMs < FRAME_LATENCY_LOG_INTERVAL_MS
                || frameDeltasUs.size() < 60) {
            return;
        }
        lastFrameLatencyLogMs = now;
        ArrayList<Long> sorted = new ArrayList<>(frameDeltasUs);
        Collections.sort(sorted);
        long p50 = percentile(sorted, 0.50f);
        long p95 = percentile(sorted, 0.95f);
        long p99 = percentile(sorted, 0.99f);
        int jank = 0;
        for (long sample : frameDeltasUs) {
            if (sample > 20_000L) {
                jank++;
            }
        }
        Log.i(TAG, "frameTrace samples=" + frameDeltasUs.size()
                + " p50Us=" + p50
                + " p95Us=" + p95
                + " p99Us=" + p99
                + " jankPct=" + String.format(Locale.US, "%.1f",
                frameDeltasUs.isEmpty() ? 0f : (jank * 100f / frameDeltasUs.size())));
    }

    private void reportPerfHintFrameDuration(long deltaUs) {
        if (hintSession == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        try {
            hintSession.reportActualWorkDuration(Math.max(1L, deltaUs * 1000L));
        } catch (Throwable t) {
            Log.w(TAG, "PerformanceHintSession reportActualWorkDuration failed: " + t);
        }
    }

    private long percentile(ArrayList<Long> sorted, float p) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = Math.min(sorted.size() - 1, Math.max(0,
                Math.round((sorted.size() - 1) * p)));
        return sorted.get(index);
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
            stopRawInputPolling();
            padState.reset();
            pushPad();
        } else if (shouldPollRawStickSource()) {
            startRawInputPolling();
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

    public void onLaunchProgressFromNative(String message) {
        ui.post(() -> updateLoadingProgress(message));
    }

    private void showLoadingOverlay() {
        if (loadingOverlay == null) {
            return;
        }
        if (loadingLabel != null) {
            loadingLabel.setText(R.string.emulation_loading_boot);
        }
        if (loadingProgress != null) {
            loadingProgress.setIndeterminate(true);
            loadingProgress.setMax(100);
            loadingProgress.setProgress(0);
        }
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoadingOverlay() {
        ui.removeCallbacks(bootStatusPoll);
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }

    private void updateLoadingProgress(String message) {
        if (loadingOverlay == null || loadingOverlay.getVisibility() != View.VISIBLE
                || message == null) {
            return;
        }
        String lower = message.toLowerCase(Locale.US);
        if (!lower.contains("compiling")) {
            return;
        }
        int percent = parsePercent(message);
        if (percent >= 0) {
            if (loadingProgress != null) {
                loadingProgress.setIndeterminate(false);
                loadingProgress.setProgress(percent);
            }
            if (loadingLabel != null) {
                loadingLabel.setText(getString(R.string.emulation_loading_shaders, percent));
            }
        }
    }

    private int parsePercent(String message) {
        int percent = message.indexOf('%');
        if (percent < 0) {
            return -1;
        }
        int end = percent - 1;
        while (end >= 0 && Character.isWhitespace(message.charAt(end))) {
            --end;
        }
        int start = end;
        while (start >= 0 && Character.isDigit(message.charAt(start))) {
            --start;
        }
        if (start == end) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(message.substring(start + 1, end + 1));
            return Math.max(0, Math.min(100, parsed));
        } catch (NumberFormatException ignored) {
            return -1;
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
