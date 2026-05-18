// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.replay;

import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;

/** A replay row backed either by a real file path or a user-picked SAF document. */
public final class ReplayItem {
    private final File file;
    private final DocumentFile document;
    private final String stableKey;
    private final String name;
    private final long length;
    private final long lastModified;

    private ReplayItem(File file, DocumentFile document, String stableKey,
            String name, long length, long lastModified) {
        this.file = file;
        this.document = document;
        this.stableKey = stableKey;
        this.name = name == null || name.isEmpty() ? "replay.slp" : name;
        this.length = Math.max(0L, length);
        this.lastModified = Math.max(0L, lastModified);
    }

    public static ReplayItem fromFile(File file) {
        return new ReplayItem(file, null, file.getAbsolutePath(),
                file.getName(), file.length(), file.lastModified());
    }

    public static ReplayItem fromDocument(DocumentFile document) {
        return new ReplayItem(null, document, document.getUri().toString(),
                document.getName(), document.length(), document.lastModified());
    }

    public boolean isLocalFile() {
        return file != null;
    }

    public File file() {
        return file;
    }

    public DocumentFile document() {
        return document;
    }

    public Uri uri() {
        return document == null ? null : document.getUri();
    }

    public String name() {
        return name;
    }

    public long length() {
        return length;
    }

    public long lastModified() {
        return lastModified;
    }

    public String stableKey() {
        return stableKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReplayItem
                && stableKey.equals(((ReplayItem) other).stableKey);
    }

    @Override
    public int hashCode() {
        return stableKey.hashCode();
    }
}
