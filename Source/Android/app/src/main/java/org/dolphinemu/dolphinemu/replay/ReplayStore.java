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

    /** Recursive walk; tolerates the month-folder layout if a user enables it later. */
    public List<ReplayItem> list() {
        if (ReplayConfig.hasCustomReplayFolder(context)) {
            ReplayConfig.drainNativeReplayStaging(context);
        }
        List<ReplayItem> out = new ArrayList<>();
        DocumentFile custom = ReplayConfig.customReplayFolder(context);
        if (custom != null && custom.canRead()) {
            walk(custom, out);
        } else {
            walk(localRoot, out);
            if (ReplayConfig.hasCustomReplayFolder(context)) {
                walk(ReplayConfig.stagingReplaysDir(context), out);
            }
        }
        Collections.sort(out, this::comparePlayableThenMtimeDesc);
        return out;
    }

    public int count() { return list().size(); }

    public long totalSize() {
        long n = 0;
        for (ReplayItem item : list()) n += item.length();
        return n;
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
        for (ReplayItem item : list()) {
            if (item.lastModified() < cutoff && delete(item)) n++;
        }
        return n;
    }

    public int deleteAll() {
        int n = 0;
        for (ReplayItem item : list()) if (delete(item)) n++;
        return n;
    }

    private void walk(File dir, List<ReplayItem> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                walk(k, out);
            } else if (isSlpName(k.getName())) {
                out.add(ReplayItem.fromFile(k));
            }
        }
    }

    private void walk(DocumentFile dir, List<ReplayItem> out) {
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
                walk(kid, out);
            } else if (isSlpName(kid.getName())) {
                out.add(ReplayItem.fromDocument(kid));
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

    private int comparePlayableThenMtimeDesc(ReplayItem a, ReplayItem b) {
        boolean ap = ReplayMetadata.parse(context, a).isPlayable();
        boolean bp = ReplayMetadata.parse(context, b).isPlayable();
        if (ap != bp) return ap ? -1 : 1;
        return Long.compare(b.lastModified(), a.lastModified());
    }
}
