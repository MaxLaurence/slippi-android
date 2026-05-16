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

    private ReplayItem(File file, DocumentFile document, String stableKey) {
        this.file = file;
        this.document = document;
        this.stableKey = stableKey;
    }

    public static ReplayItem fromFile(File file) {
        return new ReplayItem(file, null, file.getAbsolutePath());
    }

    public static ReplayItem fromDocument(DocumentFile document) {
        return new ReplayItem(null, document, document.getUri().toString());
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
        if (file != null) return file.getName();
        String name = document.getName();
        return name == null ? "replay.slp" : name;
    }

    public long length() {
        return file != null ? file.length() : Math.max(0L, document.length());
    }

    public long lastModified() {
        return file != null ? file.lastModified() : Math.max(0L, document.lastModified());
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
