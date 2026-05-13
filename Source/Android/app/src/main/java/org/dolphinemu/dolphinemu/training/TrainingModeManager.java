package org.dolphinemu.dolphinemu.training;

import android.content.Context;
import android.util.Log;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the latest Training Mode Community Edition release and
 * applies its xdelta patch to the user's imported vanilla Melee ISO.
 */
public final class TrainingModeManager {
    private static final String TAG = "TrainingModeManager";
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/AlexanderHarrison/TrainingMode-CommunityEdition/releases/latest";
    private static final String RELEASE_ASSET_NAME = "TM-CE.zip";
    private static final String TRAINING_DIR = "training-mode";
    private static final String RELEASE_CACHE = "latest-release.json";
    private static final String INSTALLED_METADATA = "installed.json";
    private static final String RELEASE_ZIP = "tm-ce-release.zip";
    private static final String PATCH_FILE = "patch.xdelta";
    private static final String OUTPUT_ISO = "TrainingModeCE.iso";
    private static final String TEMP_OUTPUT_ISO = "TrainingModeCE.tmp.iso";
    private static final long PATCH_EXTRA_BYTES_FLOOR = 2L * 1024L * 1024L;

    private TrainingModeManager() {}

    public interface ProgressListener {
        void onProgress(String stage, long completedBytes, long totalBytes);
    }

    public static ReleaseInfo fetchLatestRelease(Context context) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Slippi-Dolphin-Android");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("GitHub release check failed with HTTP " + code);
        }
        String body;
        try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
            body = readString(in);
        } finally {
            connection.disconnect();
        }
        ReleaseInfo info = parseRelease(new JSONObject(body));
        writeJson(latestReleaseCacheFile(context), info.toJson());
        return info;
    }

    public static ReleaseInfo loadCachedRelease(Context context) {
        File cache = latestReleaseCacheFile(context);
        if (!cache.exists()) return null;
        try {
            return ReleaseInfo.fromJson(readJson(cache));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "loadCachedRelease: " + e);
            return null;
        }
    }

    public static InstalledInfo loadInstalled(Context context) {
        File metadata = installedMetadataFile(context);
        File iso = outputIsoFile(context);
        if (!metadata.exists() || !iso.exists()) return null;
        try {
            InstalledInfo installed = InstalledInfo.fromJson(readJson(metadata));
            if (installed.isoPath == null || installed.isoPath.isEmpty()) {
                return new InstalledInfo(installed.tagName, installed.releaseName,
                        iso.getAbsolutePath(), iso.length(), installed.installedAtMillis);
            }
            return installed;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "loadInstalled: " + e);
            return new InstalledInfo("", "", iso.getAbsolutePath(), iso.length(), iso.lastModified());
        }
    }

    public static File outputIsoFile(Context context) {
        return new File(trainingDir(context), OUTPUT_ISO);
    }

    public static void deleteInstalled(Context context) throws IOException {
        deleteQuietly(new File(trainingDir(context), RELEASE_ZIP));
        deleteQuietly(new File(trainingDir(context), PATCH_FILE));
        deleteQuietly(new File(trainingDir(context), TEMP_OUTPUT_ISO));
        deleteIfExists(outputIsoFile(context));
        deleteIfExists(installedMetadataFile(context));
    }

    public static BuildPlan createBuildPlan(Context context, File vanillaIso, ReleaseInfo release) {
        File output = outputIsoFile(context);
        long vanillaBytes = vanillaIso != null && vanillaIso.exists() ? vanillaIso.length() : 0L;
        long existingOutputBytes = output.exists() ? output.length() : 0L;
        long patchTempBytes = Math.max(PATCH_EXTRA_BYTES_FLOOR, release.assetSizeBytes * 3L);
        long transientBytes = vanillaBytes + release.assetSizeBytes + patchTempBytes;
        long permanentExtraBytes = Math.max(0L, vanillaBytes - existingOutputBytes);
        File dir = trainingDir(context);
        long usableBytes = dir.exists() ? dir.getUsableSpace() : context.getFilesDir().getUsableSpace();
        return new BuildPlan(release, vanillaBytes, existingOutputBytes, release.assetSizeBytes,
                patchTempBytes, transientBytes, permanentExtraBytes, usableBytes);
    }

    public static InstalledInfo buildTrainingIso(
            Context context, File vanillaIso, ReleaseInfo release, ProgressListener progress)
            throws IOException {
        if (vanillaIso == null || !vanillaIso.exists()) {
            throw new IOException("Vanilla Melee ISO is missing");
        }
        File dir = trainingDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir.getAbsolutePath());
        }

        File zip = new File(dir, RELEASE_ZIP);
        File patch = new File(dir, PATCH_FILE);
        File tempOutput = new File(dir, TEMP_OUTPUT_ISO);
        File output = outputIsoFile(context);
        deleteQuietly(zip);
        deleteQuietly(patch);
        deleteIfExists(tempOutput);

        download(release.assetUrl, zip, progress);
        extractPatch(zip, patch, progress);
        if (progress != null) progress.onProgress("Applying patch", -1L, -1L);
        int result = NativeLibrary.ApplyXdeltaPatch(
                vanillaIso.getAbsolutePath(), patch.getAbsolutePath(), tempOutput.getAbsolutePath());
        if (result != 0) {
            deleteIfExists(tempOutput);
            throw new IOException("xdelta patch failed with code " + result);
        }
        if (!tempOutput.exists() || tempOutput.length() < vanillaIso.length() / 2L) {
            deleteIfExists(tempOutput);
            throw new IOException("Patched Training Mode ISO was not created");
        }
        moveReplace(tempOutput, output);

        InstalledInfo installed = new InstalledInfo(release.tagName, release.name,
                output.getAbsolutePath(), output.length(), System.currentTimeMillis());
        try {
            writeJson(installedMetadataFile(context), installed.toJson());
        } catch (JSONException e) {
            throw new IOException("Could not write Training Mode metadata", e);
        }
        deleteQuietly(zip);
        deleteQuietly(patch);
        if (progress != null) {
            progress.onProgress("Installed", output.length(), output.length());
        }
        return installed;
    }

    public static boolean isUpdateAvailable(InstalledInfo installed, ReleaseInfo release) {
        if (release == null) return false;
        return installed == null || installed.tagName == null
                || !installed.tagName.equals(release.tagName);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB"};
        int unit = -1;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        if (unit < 0) return bytes + " B";
        return String.format(Locale.US, value >= 10.0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private static ReleaseInfo parseRelease(JSONObject json) throws JSONException, IOException {
        JSONArray assets = json.getJSONArray("assets");
        JSONObject selected = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (RELEASE_ASSET_NAME.equals(asset.optString("name"))) {
                selected = asset;
                break;
            }
        }
        if (selected == null) {
            throw new IOException("Latest release does not include " + RELEASE_ASSET_NAME);
        }
        return new ReleaseInfo(
                json.optString("tag_name"),
                json.optString("name", json.optString("tag_name")),
                json.optString("published_at"),
                selected.optString("name"),
                selected.optString("browser_download_url"),
                selected.optLong("size"));
    }

    private static void download(String url, File output, ProgressListener progress) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "Slippi-Dolphin-Android");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Download failed with HTTP " + code);
        }
        long total = connection.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
            copyWithProgress(in, out, "Downloading patch", total, progress);
        } finally {
            connection.disconnect();
        }
    }

    private static void extractPatch(File zipFile, File output, ProgressListener progress) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory()
                        && (PATCH_FILE.equals(name) || name.endsWith("/" + PATCH_FILE))) {
                    long total = entry.getSize();
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                        copyWithProgress(zip, out, "Extracting patch", total, progress);
                    }
                    return;
                }
                zip.closeEntry();
            }
        }
        throw new IOException("Release zip did not contain " + PATCH_FILE);
    }

    private static void copyWithProgress(InputStream in, OutputStream out, String stage, long total,
                                         ProgressListener progress) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long completed = 0L;
        int n;
        if (progress != null) progress.onProgress(stage, 0L, total);
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            completed += n;
            if (progress != null) progress.onProgress(stage, completed, total);
        }
    }

    private static File trainingDir(Context context) {
        return new File(context.getFilesDir(), TRAINING_DIR);
    }

    private static File latestReleaseCacheFile(Context context) {
        return new File(trainingDir(context), RELEASE_CACHE);
    }

    private static File installedMetadataFile(Context context) {
        return new File(trainingDir(context), INSTALLED_METADATA);
    }

    private static JSONObject readJson(File file) throws IOException, JSONException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return new JSONObject(readString(in));
        }
    }

    private static String readString(InputStream in) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        StringBuilder builder = new StringBuilder();
        int n;
        while ((n = in.read(buffer)) > 0) {
            builder.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static void writeJson(File file, JSONObject json) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getAbsolutePath());
        }
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Could not serialize JSON", e);
        }
    }

    private static void moveReplace(File source, File destination) throws IOException {
        try {
            Files.move(source.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteIfExists(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not delete " + file.getAbsolutePath());
        }
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete " + file.getAbsolutePath());
        }
    }

    public static final class ReleaseInfo {
        public final String tagName;
        public final String name;
        public final String publishedAt;
        public final String assetName;
        public final String assetUrl;
        public final long assetSizeBytes;

        ReleaseInfo(String tagName, String name, String publishedAt, String assetName,
                    String assetUrl, long assetSizeBytes) {
            this.tagName = tagName;
            this.name = name;
            this.publishedAt = publishedAt;
            this.assetName = assetName;
            this.assetUrl = assetUrl;
            this.assetSizeBytes = assetSizeBytes;
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("tagName", tagName);
            json.put("name", name);
            json.put("publishedAt", publishedAt);
            json.put("assetName", assetName);
            json.put("assetUrl", assetUrl);
            json.put("assetSizeBytes", assetSizeBytes);
            return json;
        }

        static ReleaseInfo fromJson(JSONObject json) {
            return new ReleaseInfo(
                    json.optString("tagName"),
                    json.optString("name"),
                    json.optString("publishedAt"),
                    json.optString("assetName"),
                    json.optString("assetUrl"),
                    json.optLong("assetSizeBytes"));
        }
    }

    public static final class InstalledInfo {
        public final String tagName;
        public final String releaseName;
        public final String isoPath;
        public final long isoSizeBytes;
        public final long installedAtMillis;

        InstalledInfo(String tagName, String releaseName, String isoPath,
                      long isoSizeBytes, long installedAtMillis) {
            this.tagName = tagName;
            this.releaseName = releaseName;
            this.isoPath = isoPath;
            this.isoSizeBytes = isoSizeBytes;
            this.installedAtMillis = installedAtMillis;
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("tagName", tagName);
            json.put("releaseName", releaseName);
            json.put("isoPath", isoPath);
            json.put("isoSizeBytes", isoSizeBytes);
            json.put("installedAtMillis", installedAtMillis);
            return json;
        }

        static InstalledInfo fromJson(JSONObject json) {
            return new InstalledInfo(
                    json.optString("tagName"),
                    json.optString("releaseName"),
                    json.optString("isoPath"),
                    json.optLong("isoSizeBytes"),
                    json.optLong("installedAtMillis"));
        }
    }

    public static final class BuildPlan {
        public final ReleaseInfo release;
        public final long vanillaIsoBytes;
        public final long existingTrainingIsoBytes;
        public final long patchDownloadBytes;
        public final long patchScratchBytes;
        public final long peakAdditionalBytes;
        public final long permanentAdditionalBytes;
        public final long freeBytes;

        BuildPlan(ReleaseInfo release, long vanillaIsoBytes, long existingTrainingIsoBytes,
                  long patchDownloadBytes, long patchScratchBytes, long peakAdditionalBytes,
                  long permanentAdditionalBytes, long freeBytes) {
            this.release = release;
            this.vanillaIsoBytes = vanillaIsoBytes;
            this.existingTrainingIsoBytes = existingTrainingIsoBytes;
            this.patchDownloadBytes = patchDownloadBytes;
            this.patchScratchBytes = patchScratchBytes;
            this.peakAdditionalBytes = peakAdditionalBytes;
            this.permanentAdditionalBytes = permanentAdditionalBytes;
            this.freeBytes = freeBytes;
        }
    }
}
