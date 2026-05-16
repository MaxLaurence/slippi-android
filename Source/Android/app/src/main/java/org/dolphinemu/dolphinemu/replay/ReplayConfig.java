// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.replay;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.FileObserver;
import android.os.Handler;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import org.dolphinemu.dolphinemu.MainlineCore;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
    private static final String PREF_KEY_CUSTOM_REPLAY_FOLDER_URI = "custom_replay_folder_uri";
    private static final String REPLAY_DIR_RELATIVE = "Slippi/Replays";
    private static final String STAGING_DIR_RELATIVE = "Slippi/ReplayStaging";
    private static final String PLAYBACK_CACHE_RELATIVE = "Slippi/ReplayPlaybackCache";

    private ReplayConfig() {}

    /** Where the JSON file lives. Stable path so the C++ side can be told once. */
    public static File commFile(Context ctx) {
        return new File(UserDirectoryBootstrap.userDir(ctx), "Slippi/playback.json");
    }

    /** Mainline runs with its own user dir, so keep its playback bridge there too. */
    public static File mainlineCommFile(Context ctx) {
        return new File(MainlineCore.userDir(ctx), "Slippi/playback.json");
    }

    /** Default app-owned folder used when the user has not picked a SAF folder. */
    public static File replaysDir(Context ctx) {
        return defaultReplaysDir(ctx);
    }

    public static File defaultReplaysDir(Context ctx) {
        File externalRoot = ctx.getExternalFilesDir(null);
        if (externalRoot != null) {
            return new File(externalRoot, REPLAY_DIR_RELATIVE);
        }
        return legacyInternalReplaysDir(ctx);
    }

    /**
     * Native Slippi needs a normal filesystem path. With a custom SAF folder
     * selected, save to a staging directory and move completed files afterward.
     */
    public static File nativeReplayWriteDir(Context ctx) {
        return hasCustomReplayFolder(ctx) ? stagingReplaysDir(ctx) : defaultReplaysDir(ctx);
    }

    public static File stagingReplaysDir(Context ctx) {
        File externalRoot = ctx.getExternalFilesDir(null);
        if (externalRoot != null) {
            return new File(externalRoot, STAGING_DIR_RELATIVE);
        }
        return new File(UserDirectoryBootstrap.userDir(ctx), STAGING_DIR_RELATIVE);
    }

    public static File playbackCacheDir(Context ctx) {
        return new File(ctx.getCacheDir(), PLAYBACK_CACHE_RELATIVE);
    }

    /** Old replay location used by builds before the device-storage move. */
    public static File legacyInternalReplaysDir(Context ctx) {
        return new File(UserDirectoryBootstrap.userDir(ctx), REPLAY_DIR_RELATIVE);
    }

    public static void ensureReplayDirectory(Context ctx) {
        ensureDir(defaultReplaysDir(ctx));
        ensureDir(nativeReplayWriteDir(ctx));
        if (hasCustomReplayFolder(ctx)) {
            exportLocalReplaysToCustomFolder(ctx);
            drainNativeReplayStaging(ctx);
        }
    }

    public static boolean hasCustomReplayFolder(Context ctx) {
        return customReplayFolderUri(ctx) != null;
    }

    public static DocumentFile customReplayFolder(Context ctx) {
        Uri uri = customReplayFolderUri(ctx);
        if (uri == null) return null;
        DocumentFile folder = DocumentFile.fromTreeUri(ctx, uri);
        if (folder == null || !folder.exists() || !folder.isDirectory()) return null;
        return folder;
    }

    public static String replayFolderLabel(Context ctx) {
        DocumentFile folder = customReplayFolder(ctx);
        if (folder != null) {
            String name = folder.getName();
            if (name != null && !name.isEmpty()) return name;
            return folder.getUri().toString();
        }
        return defaultReplaysDir(ctx).getAbsolutePath();
    }

    public static boolean setCustomReplayFolder(Context ctx, Uri uri) {
        if (uri == null) return false;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            ctx.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ex) {
            Log.w(TAG, "could not persist replay folder permission: " + ex);
            return false;
        }
        DocumentFile folder = DocumentFile.fromTreeUri(ctx, uri);
        if (folder == null || !folder.exists() || !folder.isDirectory()
                || !folder.canRead() || !folder.canWrite()) {
            Log.w(TAG, "selected replay folder is not readable/writable: " + uri);
            return false;
        }
        prefs(ctx).edit().putString(PREF_KEY_CUSTOM_REPLAY_FOLDER_URI, uri.toString()).apply();
        ensureDir(stagingReplaysDir(ctx));
        return true;
    }

    public static int migrateLegacyInternalReplays(Context ctx) {
        File legacy = legacyInternalReplaysDir(ctx);
        File current = defaultReplaysDir(ctx);
        if (sameDirectory(legacy, current) || !legacy.isDirectory()) return 0;
        if (!current.exists() && !current.mkdirs()) {
            Log.w(TAG, "could not create replay migration target " + current);
            return 0;
        }
        return moveReplayFiles(legacy, current);
    }

    public static MoveResult exportLocalReplaysToCustomFolder(Context ctx) {
        DocumentFile folder = customReplayFolder(ctx);
        MoveResult result = new MoveResult();
        if (folder == null || !folder.canWrite()) return result;
        moveLocalTreeToFolder(ctx, defaultReplaysDir(ctx), folder, result);
        if (!sameDirectory(defaultReplaysDir(ctx), legacyInternalReplaysDir(ctx))) {
            moveLocalTreeToFolder(ctx, legacyInternalReplaysDir(ctx), folder, result);
        }
        return result;
    }

    public static MoveResult drainNativeReplayStaging(Context ctx) {
        MoveResult result = new MoveResult();
        DocumentFile folder = customReplayFolder(ctx);
        if (folder == null || !folder.canWrite()) return result;
        moveLocalTreeToFolder(ctx, stagingReplaysDir(ctx), folder, result);
        return result;
    }

    public static File prepareReplayForPlayback(Context ctx, ReplayItem item) {
        if (item == null) return null;
        if (item.isLocalFile()) return item.file();
        File cacheDir = playbackCacheDir(ctx);
        deleteRecursive(cacheDir);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return null;
        File dst = uniqueFile(cacheDir, safeSlpName(item.name()));
        try (InputStream in = ctx.getContentResolver().openInputStream(item.uri());
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) {
                if (!dst.delete()) Log.w(TAG, "could not remove empty playback cache " + dst);
                return null;
            }
            copy(in, out);
            return dst;
        } catch (IOException ex) {
            Log.w(TAG, "cache replay for playback failed: " + ex);
            if (dst.exists() && !dst.delete()) {
                Log.w(TAG, "could not remove failed playback cache " + dst);
            }
            return null;
        }
    }

    public static void clearPlaybackCache(Context ctx) {
        deleteRecursive(playbackCacheDir(ctx));
    }

    public static FileObserver createStagingDrainObserver(Context ctx, Handler handler) {
        if (!hasCustomReplayFolder(ctx)) return null;
        Context appContext = ctx.getApplicationContext();
        File staging = stagingReplaysDir(appContext);
        ensureDir(staging);
        return new FileObserver(staging.getAbsolutePath(), FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                if (!isSlpName(path)) return;
                handler.postDelayed(() -> drainNativeReplayStaging(appContext), 500);
            }
        };
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

    private static SharedPreferences prefs(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    private static Uri customReplayFolderUri(Context ctx) {
        String raw = prefs(ctx).getString(PREF_KEY_CUSTOM_REPLAY_FOLDER_URI, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Uri.parse(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void ensureDir(File dir) {
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create directory " + dir);
        }
    }

    private static boolean sameDirectory(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException ignored) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private static void moveLocalTreeToFolder(Context ctx, File srcDir, DocumentFile dstFolder,
            MoveResult result) {
        File[] kids = srcDir == null ? null : srcDir.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            if (kid.isDirectory()) {
                moveLocalTreeToFolder(ctx, kid, dstFolder, result);
                File[] leftovers = kid.listFiles();
                if (leftovers != null && leftovers.length == 0 && !kid.delete()) {
                    Log.w(TAG, "could not remove empty replay dir " + kid);
                }
                continue;
            }
            if (!isSlpName(kid.getName())) continue;
            if (moveLocalReplayToFolder(ctx, kid, dstFolder)) {
                result.moved++;
            } else {
                result.failed++;
            }
        }
    }

    private static boolean moveLocalReplayToFolder(Context ctx, File src, DocumentFile dstFolder) {
        String name = safeSlpName(src.getName());
        DocumentFile existing = dstFolder.findFile(name);
        if (existing != null && existing.isFile() && existing.length() == src.length()) {
            return deleteAfterMove(src);
        }
        if (existing != null) name = uniqueDocumentName(dstFolder, name);

        DocumentFile dst = dstFolder.createFile("application/octet-stream", name);
        if (dst == null) return false;
        try (InputStream in = new FileInputStream(src);
             OutputStream out = ctx.getContentResolver().openOutputStream(dst.getUri(), "wt")) {
            if (out == null) {
                if (!dst.delete()) Log.w(TAG, "could not delete failed replay export " + dst.getUri());
                return false;
            }
            copy(in, out);
            return deleteAfterMove(src);
        } catch (IOException ex) {
            Log.w(TAG, "move replay " + src + " -> " + dst.getUri() + " failed: " + ex);
            if (!dst.delete()) Log.w(TAG, "could not delete failed replay export " + dst.getUri());
            return false;
        }
    }

    private static boolean deleteAfterMove(File src) {
        if (src.delete()) return true;
        Log.w(TAG, "could not delete replay after export " + src);
        return false;
    }

    private static int moveReplayFiles(File srcDir, File dstDir) {
        File[] kids = srcDir.listFiles();
        if (kids == null) return 0;
        int moved = 0;
        for (File kid : kids) {
            if (kid.isDirectory()) {
                moved += moveReplayFiles(kid, dstDir);
                File[] leftovers = kid.listFiles();
                if (leftovers != null && leftovers.length == 0 && !kid.delete()) {
                    Log.w(TAG, "could not remove empty replay dir " + kid);
                }
                continue;
            }
            if (!isSlpName(kid.getName())) continue;
            File directDst = new File(dstDir, kid.getName());
            if (directDst.isFile() && directDst.length() == kid.length()) {
                if (!kid.delete()) {
                    Log.w(TAG, "could not delete migrated replay duplicate " + kid);
                }
                continue;
            }
            File dst = uniqueFile(dstDir, kid.getName());
            if (copyReplay(kid, dst)) {
                if (!dst.setLastModified(kid.lastModified())) {
                    Log.w(TAG, "could not preserve replay mtime " + dst);
                }
                if (!kid.delete()) {
                    Log.w(TAG, "could not delete migrated replay " + kid);
                }
                moved++;
            }
        }
        return moved;
    }

    private static boolean copyReplay(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            copy(in, out);
            return true;
        } catch (IOException ex) {
            Log.w(TAG, "copy replay " + src + " -> " + dst + " failed: " + ex);
            if (dst.exists() && dst.length() == 0 && !dst.delete()) {
                Log.w(TAG, "could not remove failed replay copy " + dst);
            }
            return false;
        }
    }

    private static boolean isSlpName(String name) {
        return name != null
                && name.toLowerCase(java.util.Locale.US).endsWith(".slp");
    }

    private static String safeSlpName(String name) {
        String safe = name == null ? "" : name.replace('/', '_').replace('\\', '_').trim();
        if (safe.isEmpty()) safe = "replay-" + System.currentTimeMillis() + ".slp";
        if (!isSlpName(safe)) safe += ".slp";
        return safe;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, stem + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + ext);
    }

    public static String uniqueDocumentName(DocumentFile dir, String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String candidate = stem + " (" + i + ")" + ext;
            if (dir.findFile(candidate) == null) return candidate;
        }
        return stem + "-" + System.currentTimeMillis() + ext;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) deleteRecursive(kid);
            }
        }
        if (!f.delete()) Log.w(TAG, "deleteRecursive failed " + f);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    public static final class MoveResult {
        public int moved;
        public int failed;
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
