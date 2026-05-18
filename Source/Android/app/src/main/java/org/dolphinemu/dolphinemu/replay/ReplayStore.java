// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.replay;

import android.content.Context;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single owner of the on-disk replays directory. All filesystem
 * mutations and queries go through here so the rest of the app
 * (browser activity, share, delete) never touches {@link File#delete()}
 * or walks the tree directly.
 */
public final class ReplayStore {
    private static final String TAG = "ReplayStore";

    private final Context context;
    private final File localRoot;

    public ReplayStore(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.localRoot = ReplayConfig.defaultReplaysDir(ctx);
    }

    public File localRoot() { return localRoot; }

    /**
     * Recursive walk; tolerates the month-folder layout if a user enables it later.
     * This intentionally avoids parsing replay metadata so callers can run it on a
     * background thread without turning a simple directory scan into per-file I/O.
     */
    public ScanResult scan() {
        if (ReplayConfig.hasCustomReplayFolder(context)) {
            ReplayConfig.drainNativeReplayStaging(context);
        }
        List<ReplayItem> out = new ArrayList<>();
        long[] totalBytes = new long[1];
        DocumentFile custom = ReplayConfig.customReplayFolder(context);
        if (custom != null && custom.canRead()) {
            walk(custom, out, totalBytes);
        } else {
            walk(localRoot, out, totalBytes);
            if (ReplayConfig.hasCustomReplayFolder(context)) {
                walk(ReplayConfig.stagingReplaysDir(context), out, totalBytes);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return new ScanResult(out, totalBytes[0]);
    }

    public List<ReplayItem> list() { return scan().items; }

    public int count() { return scan().items.size(); }

    public long totalSize() {
        return scan().totalBytes;
    }

    public boolean delete(ReplayItem item) {
        if (item == null) return false;
        boolean ok;
        if (item.isLocalFile()) {
            File f = item.file();
            ok = insideRoot(f) && f.delete();
        } else {
            ok = item.document().delete();
        }
        if (!ok) Log.w(TAG, "delete failed: " + item.name());
        return ok;
    }

    public int deleteMany(List<ReplayItem> files) {
        int n = 0;
        for (ReplayItem f : files) if (delete(f)) n++;
        return n;
    }

    public int deleteOlderThan(long ageMs) {
        long cutoff = System.currentTimeMillis() - ageMs;
        int n = 0;
        for (ReplayItem item : scan().items) {
            if (item.lastModified() < cutoff && delete(item)) n++;
        }
        return n;
    }

    public int deleteAll() {
        int n = 0;
        for (ReplayItem item : scan().items) if (delete(item)) n++;
        return n;
    }

    private void walk(File dir, List<ReplayItem> out, long[] totalBytes) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                walk(k, out, totalBytes);
            } else if (isSlpName(k.getName())) {
                ReplayItem item = ReplayItem.fromFile(k);
                out.add(item);
                totalBytes[0] += item.length();
            }
        }
    }

    private void walk(DocumentFile dir, List<ReplayItem> out, long[] totalBytes) {
        DocumentFile[] kids;
        try {
            kids = dir.listFiles();
        } catch (Throwable t) {
            Log.w(TAG, "list selected replay folder failed: " + t);
            return;
        }
        for (DocumentFile kid : kids) {
            if (kid == null) continue;
            if (kid.isDirectory()) {
                walk(kid, out, totalBytes);
            } else if (isSlpName(kid.getName())) {
                ReplayItem item = ReplayItem.fromDocument(kid);
                out.add(item);
                totalBytes[0] += item.length();
            }
        }
    }

    private boolean insideRoot(File f) {
        try {
            String r = localRoot.getCanonicalPath();
            String s = ReplayConfig.stagingReplaysDir(context).getCanonicalPath();
            String p = f.getCanonicalPath();
            return p.startsWith(r) || p.startsWith(s);
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

    private static boolean isSlpName(String name) {
        return name != null
                && name.toLowerCase(java.util.Locale.US).endsWith(".slp");
    }

    public static final class ScanResult {
        public final List<ReplayItem> items;
        public final long totalBytes;

        private ScanResult(List<ReplayItem> items, long totalBytes) {
            this.items = items;
            this.totalBytes = totalBytes;
        }
    }
}
