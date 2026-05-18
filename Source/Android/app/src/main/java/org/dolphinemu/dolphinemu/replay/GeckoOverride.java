// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.replay;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;
import org.dolphinemu.dolphinemu.settings.GameSettingsOverride;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Swaps in playback-mode gecko codes for the duration of a replay
 * launch. PC Dolphin ships two flavors of {@code GALE01r2.ini}: a
 * netplay one (enables "Slippi Online") in Data/Sys/GameSettings and a
 * playback one (enables "Slippi Playback") in Data/PlaybackGeckoCodes.
 * The PC playback build packages the playback ini in place of the
 * netplay one. This Android build is always the netplay flavor so
 * live netplay works.
 *
 * For replay launches we write the playback ini as a user-dir override
 * at {@code ${UserDir}/GameSettings/GAL*.ini}. The C++ side reads
 * these on top of the bundled Sys/ copies, but its Gecko merge keeps
 * default codes on name collisions and also enables bundled defaults.
 * So the generated override disables the bundled netplay defaults and
 * renames the playback "General Codes" block to make the playback boot
 * code set cleanly active. Android keeps only the gecko override data;
 * the upstream playback {@code [Core]} section forces single-core
 * emulation, which is too slow for this embedded Android path.
 */
public final class GeckoOverride {
    private static final String TAG = "GeckoOverride";

    /** Files we toggle. Mirror the names from Data/PlaybackGeckoCodes/. */
    private static final String[] INI_NAMES = {"GALE01r2.ini", "GALJ01r2.ini"};
    private static final String PLAYBACK_GENERAL_CODES = "$Required: Slippi Playback General Codes";
    private static final String NETPLAY_GENERAL_CODES = "$Required: General Codes";
    private static final String SLIPPI_RECORDING_CODE = "$Required: Slippi Recording";
    private static final String SLIPPI_ONLINE_CODE = "$Required: Slippi Online";
    private static final String APPLY_DELAY_CODE =
            "$Recommended: Apply Delay to all In-Game Scenes";
    private static final String[] NETPLAY_DEFAULT_ENABLED = {
            NETPLAY_GENERAL_CODES,
            SLIPPI_RECORDING_CODE,
            SLIPPI_ONLINE_CODE,
            "$Recommended: Normal Lag Reduction",
            APPLY_DELAY_CODE,
            "$Recommended: Lagless FoD"
    };

    private GeckoOverride() {}

    private static File overrideDir(Context ctx) {
        return overrideDir(UserDirectoryBootstrap.userDir(ctx));
    }

    private static File overrideDir(File userDir) {
        return new File(userDir, "GameSettings");
    }

    /** Copy the playback ini into the user dir so the next BootCore picks it up. */
    public static void applyReplayMode(Context ctx) {
        applyReplayMode(ctx, UserDirectoryBootstrap.userDir(ctx));
    }

    public static void applyReplayMode(Context ctx, File userDir) {
        applyReplayMode(ctx, userDir, "PlaybackGeckoCodes", true);
    }

    /**
     * Mainline has its own generated copy of upstream Data/PlaybackGeckoCodes
     * so updating the embedded mainline checkout also updates replay boot
     * codes. Keep the Android dual-core runtime by stripping [Core], same as
     * the Ishiiruka replay path.
     */
    public static void applyMainlineReplayMode(Context ctx, File userDir) {
        applyReplayMode(ctx, userDir, "MainlinePlaybackGeckoCodes", true);
    }

    private static void applyReplayMode(Context ctx, File userDir, String assetDir,
                                        boolean stripCoreSection) {
        File dir = overrideDir(userDir);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir);
            return;
        }
        AssetManager am = ctx.getAssets();
        for (String name : INI_NAMES) {
            String assetPath = assetDir + "/" + name;
            File dst = new File(dir, name);
            try (InputStream in = am.open(assetPath);
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 FileWriter w = new FileWriter(dst)) {
                writeReplayIni(r, w, stripCoreSection);
            } catch (IOException e) {
                Log.w(TAG, "apply " + assetPath + " -> " + dst + ": " + e);
            }
        }
        GameSettingsOverride.applyUserChoices(ctx, userDir, GameSettingsOverride.MELEE_INI_NAMES);
        GameSettingsOverride.forceCodeState(userDir, GameSettingsOverride.MELEE_INI_NAMES,
                APPLY_DELAY_CODE, false);
    }

    /** Remove the override so live launches use the bundled netplay ini. */
    public static void applyLiveMode(Context ctx) {
        applyLiveMode(ctx, UserDirectoryBootstrap.userDir(ctx));
    }

    public static void applyLiveMode(Context ctx, File userDir) {
        GameSettingsOverride.applyLiveMode(ctx, userDir);
    }

    public static void applyLiveMode(File userDir) {
        File dir = overrideDir(userDir);
        for (String name : GameSettingsOverride.MELEE_INI_NAMES) {
            File f = new File(dir, name);
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "could not delete " + f);
            }
        }
    }

    public static void applyLocalPlayMode(Context ctx) {
        applyLocalPlayMode(ctx, UserDirectoryBootstrap.userDir(ctx));
    }

    public static void applyLocalPlayMode(Context ctx, File userDir) {
        File dir = overrideDir(userDir);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir);
            return;
        }
        for (String name : GameSettingsOverride.MELEE_INI_NAMES) {
            File dst = new File(dir, name);
            try (FileWriter w = new FileWriter(dst)) {
                writeLocalPlayIni(w);
            } catch (IOException e) {
                Log.w(TAG, "apply local play override " + dst + ": " + e);
            }
        }
        GameSettingsOverride.applyUserChoices(ctx, userDir, GameSettingsOverride.MELEE_INI_NAMES);
    }

    /**
     * Training Mode Community Edition is already a patched ISO, so the
     * Slippi Online and recording gecko defaults should not be injected
     * on top of it. Keep a tiny per-game override that disables those
     * bundled defaults while leaving the Training Mode DOL/files alone.
     */
    public static void applyTrainingMode(Context ctx) {
        applyTrainingMode(ctx, UserDirectoryBootstrap.userDir(ctx));
    }

    public static void applyTrainingMode(Context ctx, File userDir) {
        applyTrainingMode(ctx, userDir, false);
    }

    public static void applyTrainingMode(Context ctx, File userDir, boolean slippiParityDelay) {
        applyTrainingMode(userDir, slippiParityDelay);
        GameSettingsOverride.applyUserChoices(ctx, userDir,
                GameSettingsOverride.MELEE_TRAINING_INI_NAMES);
        GameSettingsOverride.forceCodeState(userDir, GameSettingsOverride.MELEE_TRAINING_INI_NAMES,
                APPLY_DELAY_CODE, slippiParityDelay);
    }

    public static void applyTrainingMode(File userDir) {
        applyTrainingMode(userDir, false);
    }

    public static void applyTrainingMode(File userDir, boolean slippiParityDelay) {
        File dir = overrideDir(userDir);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir);
            return;
        }
        for (String name : GameSettingsOverride.MELEE_TRAINING_INI_NAMES) {
            File dst = new File(dir, name);
            try (FileWriter w = new FileWriter(dst)) {
                writeTrainingIni(w, slippiParityDelay);
            } catch (IOException e) {
                Log.w(TAG, "apply training override " + dst + ": " + e);
            }
        }
    }

    /**
     * Copy the ini, optionally dropping the {@code [Core]} section. Dolphin merges
     * per-game ini's [Core] over the global Dolphin.ini, and the
     * playback ini's [Core] disables CPUThread. Android strips that for
     * replay launches so playback can still use the embedded dual-core
     * runtime. The Gecko sections are otherwise preserved, except for
     * the replay launch fixes described in the class comment.
     */
    private static void writeReplayIni(BufferedReader r, FileWriter w, boolean stripCoreSection)
            throws IOException {
        String line;
        boolean inCoreSection = false;
        while ((line = r.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inCoreSection = stripCoreSection && trimmed.equalsIgnoreCase("[Core]");
                if (inCoreSection) {
                    continue;
                }
                if (trimmed.equalsIgnoreCase("[Gecko]")) {
                    writeDisabledNetplayDefaults(w);
                }
            }
            if (inCoreSection) {
                continue;
            }
            w.write(renamePlaybackGeneralCodes(line));
            w.write('\n');
        }
    }

    private static String renamePlaybackGeneralCodes(String line) {
        if (line.startsWith(NETPLAY_GENERAL_CODES)) {
            return PLAYBACK_GENERAL_CODES + line.substring(NETPLAY_GENERAL_CODES.length());
        }
        return line;
    }

    private static void writeDisabledNetplayDefaults(FileWriter w) throws IOException {
        writeDisabledNetplayDefaults(w, false);
    }

    private static void writeDisabledNetplayDefaults(FileWriter w, boolean slippiParityDelay)
            throws IOException {
        w.write("[Gecko_Disabled]\n");
        for (String name : NETPLAY_DEFAULT_ENABLED) {
            if (slippiParityDelay && APPLY_DELAY_CODE.equals(name)) {
                continue;
            }
            w.write(name);
            w.write('\n');
        }
        w.write('\n');
    }

    private static void writeTrainingIni(FileWriter w, boolean slippiParityDelay) throws IOException {
        w.write("# Android Training Mode launch override\n");
        if (slippiParityDelay) {
            w.write("# Slippi parity delay diagnostic: keep the all-scenes delay enabled\n");
        }
        writeDisabledNetplayDefaults(w, slippiParityDelay);
    }

    private static void writeLocalPlayIni(FileWriter w) throws IOException {
        w.write("# Android local Play diagnostic override\n");
        writeDisabledNetplayDefaults(w);
    }
}
