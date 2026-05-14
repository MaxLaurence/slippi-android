package org.dolphinemu.dolphinemu.utils;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import org.dolphinemu.dolphinemu.DolphinApplication;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

public final class ContentHandler {
    private ContentHandler() {}

    public static int openFd(String pathOrUri, String mode) {
        try {
            ParcelFileDescriptor descriptor;
            if (pathOrUri != null && pathOrUri.startsWith("content://")) {
                ContentResolver resolver = DolphinApplication.getAppContext().getContentResolver();
                descriptor = resolver.openFileDescriptor(Uri.parse(pathOrUri), mode);
            } else {
                descriptor = ParcelFileDescriptor.open(
                        new File(uriToPath(pathOrUri)), modeToParcelMode(mode));
            }
            return descriptor == null ? -1 : descriptor.detachFd();
        } catch (Exception ignored) {
            return -1;
        }
    }

    public static boolean delete(String pathOrUri) {
        try {
            if (pathOrUri != null && pathOrUri.startsWith("content://")) {
                return DocumentsContract.deleteDocument(
                        DolphinApplication.getAppContext().getContentResolver(),
                        Uri.parse(pathOrUri));
            }
            File file = new File(uriToPath(pathOrUri));
            return !file.exists() || file.delete();
        } catch (FileNotFoundException ignored) {
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static long getSizeAndIsDirectory(String pathOrUri) {
        File file = new File(uriToPath(pathOrUri));
        if (!file.exists()) return -1;
        if (file.isDirectory()) return -2;
        return file.length();
    }

    public static String getDisplayName(String pathOrUri) {
        if (pathOrUri == null) return null;
        return new File(uriToPath(pathOrUri)).getName();
    }

    public static String[] getChildNames(String pathOrUri, boolean recursive) {
        File root = new File(uriToPath(pathOrUri));
        List<String> result = new ArrayList<>();
        collectChildNames(root, recursive, result);
        return result.toArray(new String[0]);
    }

    public static String[] doFileSearch(String directory, String[] extensions, boolean recursive) {
        File root = new File(uriToPath(directory));
        List<String> result = new ArrayList<>();
        collectFiles(root, extensions == null ? new String[0] : extensions, recursive, result);
        return result.toArray(new String[0]);
    }

    private static void collectChildNames(File root, boolean recursive, List<String> result) {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && recursive) {
                collectChildNames(child, true, result);
            } else {
                result.add(child.getName());
            }
        }
    }

    private static void collectFiles(
            File root, String[] extensions, boolean recursive, List<String> result) {
        File[] children = root.listFiles();
        if (children == null) return;
        boolean acceptAll = extensions.length == 0;
        for (File child : children) {
            if (child.isDirectory()) {
                if (recursive) collectFiles(child, extensions, true, result);
            } else if (acceptAll || matchesExtension(child.getName(), extensions)) {
                result.add(child.getAbsolutePath());
            }
        }
    }

    private static boolean matchesExtension(String name, String[] extensions) {
        for (String extension : extensions) {
            if (extension != null && name.toLowerCase().endsWith(extension.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String uriToPath(String pathOrUri) {
        if (pathOrUri == null) return "";
        return pathOrUri.startsWith("file://")
                ? Uri.parse(pathOrUri).getPath()
                : pathOrUri;
    }

    private static int modeToParcelMode(String mode) {
        if (mode == null || mode.equals("r")) return ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode.contains("w")) {
            return ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE
                    | ParcelFileDescriptor.MODE_READ_WRITE;
        }
        return ParcelFileDescriptor.MODE_READ_WRITE;
    }
}
