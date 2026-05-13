// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.replay;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Single owner of the on-disk replays directory. All filesystem
 * mutations and queries go through here so the rest of the app
 * (browser activity, importer, share, delete) never touches
 * {@link File#delete()} or walks the tree directly.
 */
public final class ReplayStore {
    private static final String TAG = "ReplayStore";

    private final File root;

    public ReplayStore(Context ctx) {
        this.root = ReplayConfig.replaysDir(ctx);
    }

    public File root() { return root; }

    /** Recursive walk; tolerates the month-folder layout if a user enables it later. */
    public List<File> list() {
        List<File> out = new ArrayList<>();
        walk(root, out);
        Collections.sort(out, BY_PLAYABLE_THEN_MTIME_DESC);
        return out;
    }

    public int count() { return list().size(); }

    public long totalSize() {
        long n = 0;
        for (File f : list()) n += f.length();
        return n;
    }

    public boolean delete(File f) {
        if (f == null || !insideRoot(f)) return false;
        boolean ok = f.delete();
        if (!ok) Log.w(TAG, "delete failed: " + f);
        return ok;
    }

    public int deleteMany(List<File> files) {
        int n = 0;
        for (File f : files) if (delete(f)) n++;
        return n;
    }

    public int deleteOlderThan(long ageMs) {
        long cutoff = System.currentTimeMillis() - ageMs;
        int n = 0;
        for (File f : list()) {
            if (f.lastModified() < cutoff && delete(f)) n++;
        }
        return n;
    }

    public int deleteAll() {
        int n = 0;
        for (File f : list()) if (delete(f)) n++;
        return n;
    }

    private void walk(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                walk(k, out);
            } else if (k.getName().endsWith(".slp")) {
                out.add(k);
            }
        }
    }

    private boolean insideRoot(File f) {
        try {
            String r = root.getCanonicalPath();
            String p = f.getCanonicalPath();
            return p.startsWith(r);
        } catch (Throwable e) {
            return false;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return (bytes >> 10) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes >> 20) + " MB";
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static final Comparator<File> BY_PLAYABLE_THEN_MTIME_DESC = (a, b) -> {
        boolean ap = ReplayMetadata.parse(a).isPlayable();
        boolean bp = ReplayMetadata.parse(b).isPlayable();
        if (ap != bp) return ap ? -1 : 1;
        return Long.compare(b.lastModified(), a.lastModified());
    };
}
