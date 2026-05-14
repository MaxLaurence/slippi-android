// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.replay;

import android.content.Context;
import android.util.Log;

import org.dolphinemu.dolphinemu.MainlineCore;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Owns the on-disk JSON config that drives SlippiReplayComm. The native
 * side polls this file via mtime, and uses {@code commandId} to detect
 * "is this a new replay request" — meaning we must mint a fresh id on
 * every launch even when re-playing the same .slp.
 *
 * Single file by design: it's the contract for one bridge surface, and
 * a separate "ReplayPaths" helper would just be indirection.
 */
public final class ReplayConfig {
    private static final String TAG = "ReplayConfig";

    private ReplayConfig() {}

    /** Where the JSON file lives. Stable path so the C++ side can be told once. */
    public static File commFile(Context ctx) {
        return new File(UserDirectoryBootstrap.userDir(ctx), "Slippi/playback.json");
    }

    /** Mainline runs with its own user dir, so keep its playback bridge there too. */
    public static File mainlineCommFile(Context ctx) {
        return new File(MainlineCore.userDir(ctx), "Slippi/playback.json");
    }

    /** Where imported and auto-saved .slp files live. Flat directory (no month folders). */
    public static File replaysDir(Context ctx) {
        return new File(UserDirectoryBootstrap.userDir(ctx), "Slippi/Replays");
    }

    /**
     * Write a "play this replay normally" config. {@link
     * org.dolphinemu.dolphinemu.NativeLibrary#SetSlippiInputPath(String)}
     * must be called separately (the C++ side doesn't watch
     * arbitrary paths — it reads {@code m_strSlippiInput} once at
     * CEXISlippi construction).
     */
    public static boolean writeNormal(Context ctx, File slp) {
        return writeNormal(commFile(ctx), slp);
    }

    public static boolean writeNormal(File commFile, File slp) {
        return write(commFile, buildNormalJson(slp.getAbsolutePath()));
    }

    /**
     * Neutralize the file so a non-replay launch (e.g. live netplay)
     * never re-enters playback mode if the user previously watched a
     * replay. Defense in depth — the path-clear JNI is the primary
     * guard, but a stale file would still get picked up if anything
     * re-set the path.
     */
    public static boolean writeEmpty(Context ctx) {
        return writeEmpty(commFile(ctx));
    }

    public static boolean writeEmpty(File commFile) {
        return write(commFile, "{\"mode\":\"off\"}\n");
    }

    private static boolean write(File f, String body) {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "could not create " + parent);
            return false;
        }
        try (FileWriter w = new FileWriter(f)) {
            w.write(body);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "write " + f + " failed: " + e);
            return false;
        }
    }

    private static String buildNormalJson(String slpAbsPath) {
        String commandId = "android-" + System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"mode\":\"normal\"");
        sb.append(",\"replay\":").append(quote(slpAbsPath));
        sb.append(",\"startFrame\":-123");
        sb.append(",\"endFrame\":").append(Integer.MAX_VALUE);
        sb.append(",\"commandId\":").append(quote(commandId));
        sb.append(",\"outputOverlayFiles\":false");
        sb.append(",\"isRealTimeMode\":false");
        sb.append(",\"shouldResync\":true");
        sb.append(",\"rollbackDisplayMethod\":\"off\"");
        sb.append('}');
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
