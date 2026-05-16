package org.dolphinemu.dolphinemu;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.dolphinemu.dolphinemu.replay.ReplayConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Prepares the on-device Dolphin user directory.
 *
 * Layout under /data/data/<pkg>/files/dolphin/:
 *   Config/, Cache/, GC/, Load/, Logs/, ScreenShots/, StateSaves/,
 *   Wii/, Sys/{GameSettings, GC, Wii, Shaders}, slippi/user.json
 *
 * The Sys/ tree is mirrored from app assets so the C++ core can locate
 * default game settings, fonts, DSP dumps, and shaders.
 */
public final class UserDirectoryBootstrap {
    private static final String TAG = "UserDirBootstrap";
    private static final String USER_DIR_NAME = "dolphin";
    private static final String SYS_DIR_NAME = "Sys";

    private UserDirectoryBootstrap() {}

    public static File userDir(Context ctx) {
        return new File(ctx.getFilesDir(), USER_DIR_NAME);
    }

    public static File slippiUserJson(Context ctx) {
        return new File(userDir(ctx), "Slippi/user.json");
    }

    /**
     * Bump this whenever the bundled Sys/ tree gains a new file (codehandler,
     * Slippi GameFiles, etc.). On launch we compare against the version
     * recorded under {@code <user>/sys_version} and wipe + re-extract Sys/ if
     * the recorded version is older — otherwise upgraded installs would keep
     * using the first-launch extract, which is missing whatever new content
     * we added.
     */
    private static final int SYS_VERSION = 3;
    private static final int MAINLINE_SYS_VERSION = 2;
    private static final String SYS_VERSION_FILE = "sys_version";

    public static synchronized void ensureLayout(Context ctx) {
        File root = userDir(ctx);
        for (String sub : new String[]{
                "Config", "Cache", "GC", "Load", "Logs",
                "ScreenShots", "StateSaves", "Wii", "Dump",
                "Slippi", "Slippi/Replays"
        }) {
            File d = new File(root, sub);
            if (!d.exists() && !d.mkdirs()) {
                Log.w(TAG, "could not create " + d);
            }
        }
        int migratedReplays = ReplayConfig.migrateLegacyInternalReplays(ctx);
        if (migratedReplays > 0) {
            Log.i(TAG, "migrated " + migratedReplays + " replay(s) to "
                    + ReplayConfig.replaysDir(ctx));
        }
        ReplayConfig.ensureReplayDirectory(ctx);
        File sysDir = new File(root, SYS_DIR_NAME);
        File versionFile = new File(root, SYS_VERSION_FILE);
        int existingVersion = readVersion(versionFile);
        if (existingVersion < SYS_VERSION) {
            Log.i(TAG, "Sys/ extract version=" + existingVersion
                    + " < " + SYS_VERSION + " — re-extracting");
            deleteRecursive(sysDir);
        }
        copyAssetDirIfMissing(ctx, SYS_DIR_NAME, sysDir);
        writeVersion(versionFile, SYS_VERSION);
        SlippiDefaults.writeIfMissing(new File(root, "Config"), ctx);
    }

    public static synchronized void ensureMainlineLayout(Context ctx) {
        File root = MainlineCore.userDir(ctx);
        for (String sub : new String[]{
                "Config", "Cache", "GC", "Load", "Logs",
                "ScreenShots", "StateSaves", "Wii", "Dump",
                "Slippi", "Slippi/Replays"
        }) {
            File d = new File(root, sub);
            if (!d.exists() && !d.mkdirs()) {
                Log.w(TAG, "could not create " + d);
            }
        }

        File sysDir = MainlineCore.sysDir(ctx);
        File versionFile = new File(root, SYS_VERSION_FILE);
        int existingVersion = readVersion(versionFile);
        if (existingVersion < MAINLINE_SYS_VERSION) {
            Log.i(TAG, "Mainline Sys/ extract version=" + existingVersion
                    + " < " + MAINLINE_SYS_VERSION + " — re-extracting");
            deleteRecursive(sysDir);
        }
        copyAssetDirIfMissing(ctx, "MainlineSys", sysDir);
        writeVersion(versionFile, MAINLINE_SYS_VERSION);
        SlippiDefaults.writeMainlineIfMissing(new File(root, "Config"), ctx);
        syncSlippiUserJsonToMainline(ctx);
    }

    private static void syncSlippiUserJsonToMainline(Context ctx) {
        File source = slippiUserJson(ctx);
        if (!source.isFile()) return;
        File destination = new File(MainlineCore.userDir(ctx), "Slippi/user.json");
        if (destination.isFile()
                && destination.length() == source.length()
                && destination.lastModified() >= source.lastModified()) {
            return;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "could not create " + parent);
            return;
        }
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException ex) {
            Log.w(TAG, "sync mainline user.json failed: " + ex);
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
        try (java.io.FileWriter w = new java.io.FileWriter(f)) {
            w.write(Integer.toString(v));
        } catch (IOException e) {
            Log.w(TAG, "writeVersion: " + e);
        }
    }

    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        if (!f.delete()) Log.w(TAG, "deleteRecursive: failed " + f);
    }

    private static void copyAssetDirIfMissing(Context ctx, String assetPath, File outDir) {
        if (outDir.exists()) return;
        if (!outDir.mkdirs()) {
            Log.w(TAG, "mkdirs failed: " + outDir);
            return;
        }
        AssetManager am = ctx.getAssets();
        try {
            String[] entries = am.list(assetPath);
            if (entries == null) return;
            for (String name : entries) {
                String childAsset = assetPath + "/" + name;
                File childOut = new File(outDir, name);
                String[] grand = am.list(childAsset);
                if (grand != null && grand.length > 0) {
                    copyAssetDirIfMissing(ctx, childAsset, childOut);
                } else {
                    copyAssetFile(am, childAsset, childOut);
                }
            }
        } catch (IOException ex) {
            Log.e(TAG, "asset copy failed at " + assetPath + ": " + ex);
        }
    }

    private static void copyAssetFile(AssetManager am, String src, File dst) {
        try (InputStream is = am.open(src);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } catch (IOException ex) {
            Log.e(TAG, "copy " + src + " -> " + dst + " failed: " + ex);
        }
    }
}
