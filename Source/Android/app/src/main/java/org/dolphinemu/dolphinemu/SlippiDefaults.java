package org.dolphinemu.dolphinemu;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Writes sane Slippi/Melee default config files on first launch.
 *
 * Values lifted from connoranastasio's ROCKNIX deploy script (which mirrors
 * what the Slippi desktop launcher writes) — tuned for an Adreno GPU running
 * Vulkan with the Slippi netplay flow. Differences from desktop:
 *   - Backend = OGL fallback would crash today; we lock to Vulkan.
 *   - DSP Backend = OpenSLES (only option available on the NDK).
 *   - SIDevice0/1 default to 6 (standard pad) instead of 12 (USB adapter),
 *     since no GC USB adapter is present on a phone.
 *   - EFBAccessEnable = False and EnableGPUTextureDecoding = True keep the
 *     Adreno R/B-channel artifacts and CMPR colored-square bug at bay.
 */
public final class SlippiDefaults {
    private static final String TAG = "SlippiDefaults";

    private SlippiDefaults() {}

    /**
     * Bump this whenever any of the canned configs below changes. We rewrite
     * the file in place when {@code <user>/Config/defaults_version} is older
     * so tuning improvements actually reach users on app upgrade without
     * requiring them to clear app data. Hand-edited overrides aren't a
     * concern — there's no settings UI yet, so anyone touching the ini files
     * directly is implicitly opting out of the version bump.
     */
    // v7 moves the Android low-latency audio default to Oboe and exposes a
    // configurable audio buffer burst count. Latency stays 2f.
    // v6 enables the Android low-latency profile: AAudio output, 120Hz-capable
    // display preference, and event-driven raw input.
    // v5 tightened netplay latency defaults: explicit SlippiOnlineDelay=2,
    // native EFB scale, and no runtime overlays.
    // v4 introduced the Slippi replay-dir default so the in-app browser
    // sees auto-saved netplay matches. Bump whenever any canned config
    // below changes — users with hand-edited overrides keep theirs only
    // until the next bump.
    private static final int DEFAULTS_VERSION = 7;
    private static final String DEFAULTS_VERSION_FILE = "defaults_version";

    public static void writeIfMissing(File configDir) {
        writeIfMissing(configDir, null);
    }

    public static void writeIfMissing(File configDir, Context ctx) {
        if (!configDir.exists() && !configDir.mkdirs()) {
            Log.w(TAG, "Could not create " + configDir);
            return;
        }
        File versionFile = new File(configDir, DEFAULTS_VERSION_FILE);
        int existing = readVersion(versionFile);
        boolean force = existing < DEFAULTS_VERSION;
        // SlippiReplayDir needs a runtime-resolved absolute path; the
        // rest of Dolphin.ini is canned. Substitute %REPLAY_DIR% with
        // either the on-device path or a sentinel that the C++ side
        // falls back from cleanly.
        String replayDir = ctx == null
                ? ""  // C++ side defaults to userdir/Slippi when empty
                : new File(ctx.getFilesDir(), "dolphin/Slippi/Replays").getAbsolutePath();
        String dolphinIni = DOLPHIN_INI.replace("%REPLAY_DIR%", replayDir);
        writeOrUpgrade(new File(configDir, "Dolphin.ini"), dolphinIni, force);
        writeOrUpgrade(new File(configDir, "GFX.ini"), GFX_INI, force);
        writeOrUpgrade(new File(configDir, "GCPadNew.ini"), GCPAD_INI, force);
        writeOrUpgrade(new File(configDir, "WiimoteNew.ini"), WIIMOTE_INI, force);
        writeOrUpgrade(new File(configDir, "Logger.ini"), LOGGER_INI, force);
        if (force) writeVersion(versionFile, DEFAULTS_VERSION);
    }

    private static void writeOrUpgrade(File f, String content, boolean force) {
        if (!force && f.exists()) return;
        try (FileWriter w = new FileWriter(f)) {
            w.write(content);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + f + ": " + e);
        }
    }

    private static int readVersion(File f) {
        if (!f.exists()) return 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            return Integer.parseInt(r.readLine().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeVersion(File f, int v) {
        try (FileWriter w = new FileWriter(f)) {
            w.write(Integer.toString(v));
        } catch (IOException e) {
            Log.w(TAG, "writeVersion: " + e);
        }
    }

    private static final String DOLPHIN_INI = ""
            + "[Display]\n"
            + "FullscreenResolution = Auto\n"
            + "Fullscreen = True\n"
            + "RenderToMain = False\n"
            + "[Core]\n"
            + "GFXBackend = Vulkan\n"
            + "HLE_BS2 = True\n"
            // Match desktop Slippi's TimingVariance — 40 (the upstream
            // Dolphin default) lets the emulator drift up to 40ms before
            // resyncing, which surfaces as occasional ~2-frame stutters
            // and a sloppier feel. 8 keeps the audio/video clock tight.
            + "TimingVariance = 8\n"
            + "CPUCore = 4\n"
            + "Fastmem = True\n"
            + "CPUThread = True\n"
            + "DSPHLE = True\n"
            + "SkipIdle = True\n"
            + "SyncOnSkipIdle = False\n"
            + "SyncGPU = False\n"
            + "FPRF = False\n"
            + "AccurateNaNs = False\n"
            + "SelectedLanguage = 0\n"
            + "OverrideGCLang = False\n"
            + "DPL2Decoder = False\n"
            + "Latency = 2\n"
            + "SlippiOnlineDelay = 2\n"
            // Match desktop Slippi: SlotA = 255 (NONE). The Slippi EXI device
            // is hardcoded into SlotB via the default of
            // `SConfig::m_EXIDevice[1] = EXIDEVICE_SLIPPI` in ConfigManager.h
            // (no config key reads SlotB) — and the netplay Gecko codes in
            // Sys/GameSettings/GALE01r2.ini explicitly say "Slippi device must
            // be in Slot B." With both slots set to Slippi, the EXI command
            // routing gets confused and Melee never escapes the memory-card
            // screen, so leave SlotA empty.
            + "SlotA = 255\n"
            + "SerialPort1 = 255\n"
            + "SIDevice0 = 6\n"
            + "AdapterRumble0 = False\n"
            + "SimulateKonga0 = False\n"
            + "SIDevice1 = 6\n"
            + "AdapterRumble1 = False\n"
            + "SimulateKonga1 = False\n"
            + "SIDevice2 = 0\n"
            + "SIDevice3 = 0\n"
            + "EmulationSpeed = 1.00000000\n"
            + "FrameSkip = 0x00000000\n"
            + "Overclock = 1.0\n"
            + "OverclockEnable = False\n"
            + "AutoDiscChange = True\n"
            // Slippi Jukebox = the OST player implemented in Rust on top of
            // cpal/rodio. cpal's Android backend needs extra init / AAudio
            // wiring that isn't hooked up here; it SIGABRTs the
            // SlippiJukebox thread on launch. Disable for now — Melee plays
            // its own DSP-emulated music regardless.
            + "SlippiJukeboxEnabled = False\n"
            // Replay browser sees this directory (Phase 3). Auto-saved
            // netplay replays land here; importer copies into here.
            // %REPLAY_DIR% is substituted at write time with the on-device
            // absolute path. Flat dir (MonthFolders=False) keeps the
            // listing simple — the row itself is timestamped.
            + "SlippiReplayDir = %REPLAY_DIR%\n"
            + "SlippiSaveReplays = True\n"
            + "SlippiReplayMonthFolders = False\n"
            + "[DSP]\n"
            + "EnableJIT = True\n"
            + "DumpAudio = False\n"
            + "Backend = Oboe\n"
            + "AndroidAudioBufferBursts = 4\n"
            + "Volume = 100\n"
            + "DSPThread = True\n"
            + "[General]\n"
            + "ISOPaths = 0\n";

    private static final String GFX_INI = ""
            + "[Hardware]\n"
            + "VSync = False\n"
            + "Adapter = 0\n"
            + "[Settings]\n"
            + "AspectRatio = 5\n"
            + "InternalResolution = 1\n"
            + "Crop = False\n"
            + "wideScreenHack = False\n"
            + "UseXFB = False\n"
            + "UseRealXFB = False\n"
            + "SafeTextureCacheColorSamples = 128\n"
            + "ShowFPS = False\n"
            + "ShowNetPlayPing = False\n"
            + "LogRenderTimeToFile = False\n"
            + "OverlayStats = False\n"
            + "OverlayProjStats = False\n"
            + "DumpTextures = False\n"
            + "HiresTextures = False\n"
            + "ConvertHiresTextures = False\n"
            + "CacheHiresTextures = False\n"
            + "DumpEFBTarget = False\n"
            + "FreeLook = False\n"
            + "UseFFV1 = False\n"
            + "EnablePixelLighting = False\n"
            + "FastDepthCalc = True\n"
            + "MSAA = 0\n"
            + "SSAA = False\n"
            // SCALE_1X. The prior value (2) was SCALE_AUTO_INTEGRAL, which can
            // silently raise GPU cost on high-density Android displays.
            + "EFBScale = 3\n"
            + "TexFmtOverlayEnable = False\n"
            + "TexFmtOverlayCenter = False\n"
            + "Wireframe = False\n"
            + "DisableFog = False\n"
            + "BorderlessFullscreen = False\n"
            + "SWZComploc = True\n"
            + "SWZFreeze = True\n"
            + "ShaderCompilationMode = 0\n"
            + "WaitForShadersBeforeStarting = True\n"
            // BackendMultithreading=True buffers GPU command submission on a
            // worker thread, which is a small frame-rate win but adds ~1
            // frame of input-to-display latency. Disable it for Slippi:
            // input feel matters more than a few extra %FPS on a phone GPU
            // that's already saturating at 60.
            + "BackendMultithreading = False\n"
            + "[Enhancements]\n"
            + "ForceTextureFiltering = False\n"
            + "MaxAnisotropy = 0\n"
            + "PostProcessingShader =\n"
            + "[Stereoscopy]\n"
            + "StereoMode = 0\n"
            + "StereoDepth = 20\n"
            + "StereoConvergencePercentage = 100\n"
            + "StereoSwapEyes = False\n"
            + "[Hacks]\n"
            + "EFBAccessEnable = False\n"
            + "BBoxEnable = False\n"
            + "ForceProgressive = True\n"
            + "EFBToTextureEnable = True\n"
            + "EFBScaledCopy = False\n"
            + "EFBEmulateFormatChanges = False\n"
            + "SkipDuplicateXFBs = True\n"
            + "XFBToTextureEnable = True\n"
            + "FullAsyncShaderCompilation = False\n"
            + "WaitForShaderCompilation = True\n"
            + "EnableGPUTextureDecoding = True\n";

    // Bind Player 1 of the GameCube pad to the Android Touchscreen device that
    // ButtonManager publishes (ciface::Android::PopulateDevices). The Java
    // EmulationActivity translates physical pad / Thor gamepad events into the
    // Touchscreen device's internal codes (BUTTON_A=0, STICK_MAIN_LEFT=13, etc.)
    // — see EmulationActivity.dispatchKeyEvent / dispatchGenericMotionEvent.
    //
    // The "Button N" / "Axis N" suffix references the index passed to
    // AddInput() in Source/Core/InputCommon/ControllerInterface/Android/Android.cpp
    // and matches the ButtonType enum in Source/Android/jni/ButtonManager.h.
    private static final String GCPAD_INI = ""
            + "[GCPad1]\n"
            + "Device = Android/0/Touchscreen\n"
            + "Buttons/A = `Button 0`\n"
            + "Buttons/B = `Button 1`\n"
            + "Buttons/X = `Button 3`\n"
            + "Buttons/Y = `Button 4`\n"
            + "Buttons/Z = `Button 5`\n"
            + "Buttons/Start = `Button 2`\n"
            + "Main Stick/Up = `Axis 11`\n"
            + "Main Stick/Down = `Axis 12`\n"
            + "Main Stick/Left = `Axis 13`\n"
            + "Main Stick/Right = `Axis 14`\n"
            + "C-Stick/Up = `Axis 16`\n"
            + "C-Stick/Down = `Axis 17`\n"
            + "C-Stick/Left = `Axis 18`\n"
            + "C-Stick/Right = `Axis 19`\n"
            + "Triggers/L = `Axis 20`\n"
            + "Triggers/R = `Axis 21`\n"
            + "Triggers/L-Analog = `Axis 20`\n"
            + "Triggers/R-Analog = `Axis 21`\n"
            + "D-Pad/Up = `Button 6`\n"
            + "D-Pad/Down = `Button 7`\n"
            + "D-Pad/Left = `Button 8`\n"
            + "D-Pad/Right = `Button 9`\n"
            + "[GCPad2]\nDevice = Android/1/Touchscreen\n"
            + "[GCPad3]\nDevice = Android/2/Touchscreen\n"
            + "[GCPad4]\nDevice = Android/3/Touchscreen\n";

    private static final String WIIMOTE_INI = ""
            + "[Wiimote1]\nSource = 0\n"
            + "[Wiimote2]\nSource = 0\n"
            + "[Wiimote3]\nSource = 0\n"
            + "[Wiimote4]\nSource = 0\n"
            + "[BalanceBoard]\nSource = 0\n";

    private static final String LOGGER_INI = ""
            + "[Options]\n"
            + "WriteToFile = False\n"
            + "WriteToConsole = False\n";
}
