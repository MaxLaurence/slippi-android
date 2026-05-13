// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.replay;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;

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
 * code set cleanly active. The {@code [Core]} section of the playback
 * ini is stripped — it sets {@code CPUThread=False} which we can't
 * afford on the Thor, and we only want the gecko overrides here.
 */
public final class GeckoOverride {
    private static final String TAG = "GeckoOverride";

    /** Files we toggle. Mirror the names from Data/PlaybackGeckoCodes/. */
    private static final String[] INI_NAMES = {"GALE01r2.ini", "GALJ01r2.ini"};
    private static final String PLAYBACK_GENERAL_CODES = "$Required: Slippi Playback General Codes";
    private static final String NETPLAY_GENERAL_CODES = "$Required: General Codes";
    private static final String[] NETPLAY_DEFAULT_ENABLED = {
            NETPLAY_GENERAL_CODES,
            "$Required: Slippi Recording",
            "$Required: Slippi Online",
            "$Recommended: Normal Lag Reduction",
            "$Recommended: Apply Delay to all In-Game Scenes",
            "$Recommended: Lagless FoD"
    };

    private GeckoOverride() {}

    private static File overrideDir(Context ctx) {
        return new File(UserDirectoryBootstrap.userDir(ctx), "GameSettings");
    }

    /** Copy the playback ini into the user dir so the next BootCore picks it up. */
    public static void applyReplayMode(Context ctx) {
        File dir = overrideDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir);
            return;
        }
        AssetManager am = ctx.getAssets();
        for (String name : INI_NAMES) {
            String assetPath = "PlaybackGeckoCodes/" + name;
            File dst = new File(dir, name);
            try (InputStream in = am.open(assetPath);
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 FileWriter w = new FileWriter(dst)) {
                writeReplayIni(r, w);
            } catch (IOException e) {
                Log.w(TAG, "apply " + assetPath + " -> " + dst + ": " + e);
            }
        }
    }

    /** Remove the override so live launches use the bundled netplay ini. */
    public static void applyLiveMode(Context ctx) {
        File dir = overrideDir(ctx);
        for (String name : INI_NAMES) {
            File f = new File(dir, name);
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "could not delete " + f);
            }
        }
    }

    /**
     * Copy the ini, dropping any {@code [Core]} section. Dolphin merges
     * per-game ini's [Core] over the global Dolphin.ini, and the
     * playback ini's [Core] disables CPUThread — a non-starter on
     * Android where the emu thread already pins the big-core cluster.
     * The Gecko sections are otherwise preserved, except for the replay
     * launch fixes described in the class comment.
     */
    private static void writeReplayIni(BufferedReader r, FileWriter w) throws IOException {
        String line;
        boolean inCoreSection = false;
        while ((line = r.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inCoreSection = trimmed.equalsIgnoreCase("[Core]");
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
        w.write("[Gecko_Disabled]\n");
        for (String name : NETPLAY_DEFAULT_ENABLED) {
            w.write(name);
            w.write('\n');
        }
        w.write('\n');
    }
}
