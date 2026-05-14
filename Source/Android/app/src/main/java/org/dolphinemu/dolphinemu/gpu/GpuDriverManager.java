package org.dolphinemu.dolphinemu.gpu;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization;
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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GpuDriverManager {
    private static final String TAG = "GpuDriverManager";
    private static final String PREF_DRIVER_LIBRARY = "gpu_driver_library_name";
    private static final String PREF_DRIVER_LABEL = "gpu_driver_label";
    private static final String PREF_DRIVER_SOURCE = "gpu_driver_source";
    private static final String PREF_DRIVER_URL = "gpu_driver_url";
    private static final String PREF_DRIVER_CACHE_KEY = "gpu_driver_cache_key";
    private static final String PREF_BACKEND = "backend";
    private static final String ROOT_DIR = "GPUDrivers";
    private static final String EXTRACTED_DIR = "Extracted";
    private static final String CACHE_DIR = "Cache";
    private static final String TMP_DIR = "Tmp";
    private static final String REDIRECT_DIR = "FileRedirect";
    private static final String INSTALL_TMP_DIR = "InstallTmp";
    private static final String ACTIVE_TMP_DIR = "ActiveTmp";
    private static final String DOWNLOAD_TMP = "driver-download.tmp";
    private static final String META_JSON = "meta.json";
    private static final String MANAGER_JSON = "slippi_driver.json";
    private static final long MAX_DRIVER_BYTES = 96L * 1024L * 1024L;
    private static final String USER_AGENT = "Slippi-Dolphin-Android";
    private static final int RELEASES_TO_SCAN = 16;
    private static final int MAX_RECOMMENDED_TURNIP_DRIVERS = 2;
    private static final int MAX_RECOMMENDED_QUALCOMM_DRIVERS = 2;
    private static final int ANDROID_15_API = 35;
    private static final String BACKEND_VULKAN = "Vulkan";

    private static final DriverRepository DOLPHIN_DRIVER_REPOSITORY =
            new DriverRepository("K11MCH1/AdrenoToolsDrivers", "Dolphin AdrenoTools");

    private static boolean sNativeDirectoriesRegistered;

    private GpuDriverManager() {}

    public interface ProgressListener {
        void onProgress(String stage, long completedBytes, long totalBytes);
    }

    public static List<DriverPackage> fetchCatalog() throws IOException, JSONException {
        List<DriverPackage> packages = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            packages.addAll(fetchRepository(DOLPHIN_DRIVER_REPOSITORY));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Could not fetch GPU driver repo " + DOLPHIN_DRIVER_REPOSITORY.slug, e);
            errors.add(DOLPHIN_DRIVER_REPOSITORY.label + ": " + e.getMessage());
        }
        if (packages.isEmpty() && !errors.isEmpty()) {
            throw new IOException(TextUtils.join("\n", errors));
        }
        return packages;
    }

    public static InstallResult installFromUrl(Context context, DriverPackage pkg,
                                               ProgressListener progress) throws IOException {
        ensureDriverDirectories(context);
        File root = rootDir(context);
        File download = new File(root, DOWNLOAD_TMP);
        deleteIfExists(download);
        download(pkg.downloadUrl, pkg.fallbackDownloadUrl, download, progress);
        try (InputStream in = new BufferedInputStream(new FileInputStream(download))) {
            InstallResult result = installFromStream(context, in, pkg.displayName,
                    pkg.repositoryLabel, pkg.downloadUrl, progress);
            deleteIfExists(download);
            return result;
        } catch (IOException e) {
            deleteIfExists(download);
            throw e;
        }
    }

    public static InstallResult installFromStream(Context context, InputStream input,
                                                  String fallbackLabel, String source,
                                                  String sourceUrl,
                                                  ProgressListener progress) throws IOException {
        ensureDriverDirectories(context);
        if (progress != null) progress.onProgress("Extracting driver", -1L, -1L);

        File root = rootDir(context);
        File installTmp = new File(root, INSTALL_TMP_DIR);
        deleteRecursively(installTmp);
        if (!installTmp.mkdirs() && !installTmp.isDirectory()) {
            throw new IOException("Could not create " + installTmp.getAbsolutePath());
        }

        try {
            unzipDriver(input, installTmp);
            DriverMetadata metadata;
            try {
                metadata = readMetadata(installTmp);
            } catch (JSONException e) {
                return InstallResult.invalidMetadata("Driver metadata is not valid JSON");
            }
            if (metadata == null) {
                return InstallResult.missingMetadata();
            }
            if (Build.VERSION.SDK_INT < metadata.minApi) {
                return InstallResult.unsupportedAndroid(metadata.minApi);
            }
            if (TextUtils.isEmpty(metadata.libraryName)) {
                String found = findVulkanLibrary(installTmp);
                if (TextUtils.isEmpty(found)) {
                    return InstallResult.invalidMetadata("No Vulkan library listed in metadata");
                }
                metadata = metadata.withLibraryName(found);
            } else if (!isSafeVulkanLibraryName(metadata.libraryName)) {
                return InstallResult.invalidMetadata("Driver metadata lists an unsafe Vulkan library");
            }
            if (progress != null) progress.onProgress("Installing driver", -1L, -1L);
            String cacheKey = cacheKeyFor(sourceUrl, fallbackLabel, metadata);
            File cached = new File(cacheDir(context), cacheKey);
            deleteRecursively(cached);
            Files.move(installTmp.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING);
            CachedDriver cachedDriver = new CachedDriver(cacheKey, cached,
                    metadata.displayLabel(fallbackLabel), source, sourceUrl,
                    metadata.libraryName, metadata);
            writeCachedDriverInfo(cachedDriver);
            selectCachedDriver(context, cachedDriver);
            return InstallResult.success(metadata);
        } finally {
            deleteRecursively(installTmp);
        }
    }

    public static void useSystemDriver(Context context) {
        ensureDriverDirectories(context);
        migrateActiveDriverToCacheIfNeeded(context);
        prefs(context).edit()
                .remove(PREF_DRIVER_LIBRARY)
                .remove(PREF_DRIVER_LABEL)
                .remove(PREF_DRIVER_SOURCE)
                .remove(PREF_DRIVER_URL)
                .remove(PREF_DRIVER_CACHE_KEY)
                .apply();
        deleteRecursively(extractedDir(context));
        extractedDir(context).mkdirs();
        clearTransientDriverState(context);
    }

    public static void removeInstalledDriver(Context context) {
        useSystemDriver(context);
        deleteRecursively(cacheDir(context));
        deleteRecursively(extractedDir(context));
        deleteRecursively(new File(rootDir(context), ACTIVE_TMP_DIR));
        deleteIfExistsQuietly(new File(rootDir(context), DOWNLOAD_TMP));
        cacheDir(context).mkdirs();
        extractedDir(context).mkdirs();
    }

    public static String selectedLibraryName(Context context) {
        return prefs(context).getString(PREF_DRIVER_LIBRARY, "");
    }

    public static String selectedLibraryNameForBackend(Context context, String backend) {
        String library = selectedLibraryName(context);
        if (!isCustomDriverBackend(backend)
                || !canAttemptCustomDriverLoading()
                || TextUtils.isEmpty(library)
                || !containsLibrary(extractedDir(context), library)) {
            return "";
        }
        return library;
    }

    public static String selectedLibraryNameForCurrentBackend(Context context) {
        return selectedLibraryNameForBackend(context, currentBackend(context));
    }

    public static boolean isCustomDriverBackend(String backend) {
        return BACKEND_VULKAN.equalsIgnoreCase(backend);
    }

    public static String currentLabel(Context context) {
        String library = selectedLibraryName(context);
        if (TextUtils.isEmpty(library)) {
            return context.getString(R.string.gpu_driver_system);
        }
        if (!canAttemptCustomDriverLoading() || !containsLibrary(extractedDir(context), library)) {
            return context.getString(R.string.gpu_driver_system);
        }
        String label = prefs(context).getString(PREF_DRIVER_LABEL, "");
        if (!TextUtils.isEmpty(label)) {
            return label;
        }
        DriverMetadata metadata = installedMetadata(context);
        return metadata == null ? library : metadata.displayLabel(library);
    }

    public static boolean hasInstalledDriver(Context context) {
        return !listCachedDrivers(context).isEmpty() || installedMetadata(context) != null;
    }

    public static DriverMetadata installedMetadata(Context context) {
        File metadata = new File(extractedDir(context), META_JSON);
        if (!metadata.isFile()) return null;
        try {
            return DriverMetadata.fromJson(readString(metadata));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Could not read installed GPU driver metadata", e);
            return null;
        }
    }

    public static List<CachedDriver> listCachedDrivers(Context context) {
        ensureDriverDirectories(context);
        migrateActiveDriverToCacheIfNeeded(context);
        List<CachedDriver> drivers = new ArrayList<>();
        File[] files = cacheDir(context).listFiles();
        if (files == null) return drivers;
        for (File file : files) {
            if (!file.isDirectory()) continue;
            CachedDriver cachedDriver = readCachedDriver(file);
            if (cachedDriver != null) drivers.add(cachedDriver);
        }
        return drivers;
    }

    public static InstallResult selectCachedDriver(Context context, CachedDriver driver)
            throws IOException {
        ensureDriverDirectories(context);
        if (driver == null || !driver.directory.isDirectory()) {
            return InstallResult.invalidMetadata("Cached driver is missing");
        }
        if (Build.VERSION.SDK_INT < driver.metadata.minApi) {
            return InstallResult.unsupportedAndroid(driver.metadata.minApi);
        }
        if (!isSafeVulkanLibraryName(driver.libraryName)
                || !containsLibrary(driver.directory, driver.libraryName)) {
            return InstallResult.invalidMetadata("Cached driver is missing its Vulkan library");
        }
        replaceActiveDriver(driver.directory, extractedDir(context));
        clearTransientDriverState(context);
        prefs(context).edit()
                .putString(PREF_DRIVER_LIBRARY, driver.libraryName)
                .putString(PREF_DRIVER_LABEL, driver.label)
                .putString(PREF_DRIVER_SOURCE, driver.source == null ? "" : driver.source)
                .putString(PREF_DRIVER_URL, driver.sourceUrl == null ? "" : driver.sourceUrl)
                .putString(PREF_DRIVER_CACHE_KEY, driver.cacheKey)
                .apply();
        return InstallResult.success(driver.metadata);
    }

    public static synchronized void prepareNativeDirectories(Context context) {
        ensureDriverDirectories(context);
        if (sNativeDirectoriesRegistered) return;
        if (!NativeLibrary.isNativeLibraryLoaded()) {
            Log.w(TAG, "Native library is not loaded; GPU driver directories not registered");
            return;
        }
        DirectoryInitialization.SetGpuDriverDirectories(
                rootDir(context).getAbsolutePath(), context.getApplicationInfo().nativeLibraryDir);
        sNativeDirectoriesRegistered = true;
    }

    public static void prepareNativeDirectoriesForBackend(Context context, String backend) {
        if (isCustomDriverBackend(backend) && canAttemptCustomDriverLoading()) {
            prepareNativeDirectories(context);
        }
    }

    public static void prepareNativeDirectoriesForCurrentBackend(Context context) {
        prepareNativeDirectoriesForBackend(context, currentBackend(context));
    }

    private static String currentBackend(Context context) {
        return prefs(context).getString(PREF_BACKEND, BACKEND_VULKAN);
    }

    public static boolean canAttemptCustomDriverLoading() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && new File("/dev/kgsl-3d0").exists();
    }

    public static File rootDir(Context context) {
        return new File(context.getFilesDir(), ROOT_DIR);
    }

    public static File extractedDir(Context context) {
        return new File(rootDir(context), EXTRACTED_DIR);
    }

    private static File cacheDir(Context context) {
        return new File(rootDir(context), CACHE_DIR);
    }

    private static File tmpDir(Context context) {
        return new File(rootDir(context), TMP_DIR);
    }

    private static File redirectDir(Context context) {
        return new File(rootDir(context), REDIRECT_DIR);
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static List<DriverPackage> fetchRepository(DriverRepository repo)
            throws IOException, JSONException {
        String body = fetchText("https://api.github.com/repos/" + repo.slug
                + "/releases?per_page=" + RELEASES_TO_SCAN, "application/vnd.github+json");
        JSONArray releases = new JSONArray(body);
        List<DriverPackage> out = new ArrayList<>();
        int turnipCount = 0;
        int qualcommCount = 0;
        for (int r = 0; r < releases.length(); r++) {
            JSONObject release = releases.getJSONObject(r);
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            for (int a = 0; a < assets.length(); a++) {
                JSONObject asset = assets.getJSONObject(a);
                String assetName = asset.optString("name");
                if (isBaseTurnipDriverAsset(assetName)) {
                    if (turnipCount >= MAX_RECOMMENDED_TURNIP_DRIVERS) continue;
                    turnipCount++;
                } else if (isQualcommDriverAsset(assetName)) {
                    if (Build.VERSION.SDK_INT < ANDROID_15_API
                            || qualcommCount >= MAX_RECOMMENDED_QUALCOMM_DRIVERS) {
                        continue;
                    }
                    qualcommCount++;
                } else {
                    continue;
                }
                out.add(new DriverPackage(
                        repo.slug,
                        repo.label,
                        release.optString("tag_name"),
                        release.optString("name", release.optString("tag_name")),
                        release.optString("published_at"),
                        assetName,
                        asset.optString("browser_download_url"),
                        "",
                        asset.optLong("size")));
            }
        }
        return out;
    }

    private static String fetchText(String urlText, String accept) throws IOException {
        URL url = new URL(urlText);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException(urlText + " failed with HTTP " + code);
        }
        try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
            return readString(in);
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isQualcommDriverAsset(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith("_adpkg.zip")
                || (lower.contains("qualcomm_") && lower.endsWith(".zip"))
                || (lower.startsWith("vulkan-") && lower.endsWith("-adpkg.zip"));
    }

    private static boolean isBaseTurnipDriverAsset(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        if (!lower.endsWith(".zip") || !lower.contains("turnip")) return false;
        if (lower.contains("gmem")
                || lower.contains("sysmem")
                || lower.contains("a8xx")
                || lower.contains("cb_perf")
                || lower.contains("one_ui")
                || lower.contains("magisk")
                || lower.contains("ksu")
                || lower.contains("prefer")
                || lower.contains("profiled")
                || lower.contains("forced")
                || lower.contains("patched")
                || lower.contains("fix")) {
            return false;
        }
        return lower.matches(".*turnip[_-]v\\d+\\.\\d+\\.\\d+[_-]r\\d+"
                + "([_-](auto|autotuner))?\\.zip");
    }

    private static void ensureDriverDirectories(Context context) {
        rootDir(context).mkdirs();
        extractedDir(context).mkdirs();
        cacheDir(context).mkdirs();
        tmpDir(context).mkdirs();
        redirectDir(context).mkdirs();
    }

    private static void clearTransientDriverState(Context context) {
        deleteRecursively(tmpDir(context));
        deleteRecursively(redirectDir(context));
        tmpDir(context).mkdirs();
        redirectDir(context).mkdirs();
    }

    private static void migrateActiveDriverToCacheIfNeeded(Context context) {
        String libraryName = selectedLibraryName(context);
        DriverMetadata metadata = installedMetadata(context);
        if (metadata == null) return;
        if (TextUtils.isEmpty(metadata.libraryName) && !TextUtils.isEmpty(libraryName)) {
            metadata = metadata.withLibraryName(libraryName);
        } else if (TextUtils.isEmpty(metadata.libraryName)) {
            String found = findVulkanLibrary(extractedDir(context));
            if (TextUtils.isEmpty(found)) return;
            metadata = metadata.withLibraryName(found);
        }
        if (!isSafeVulkanLibraryName(metadata.libraryName)) return;
        String label = prefs(context).getString(PREF_DRIVER_LABEL, metadata.displayLabel(libraryName));
        String source = prefs(context).getString(PREF_DRIVER_SOURCE, "");
        String sourceUrl = prefs(context).getString(PREF_DRIVER_URL, "");
        String cacheKey = prefs(context).getString(PREF_DRIVER_CACHE_KEY, "");
        if (TextUtils.isEmpty(cacheKey)) {
            cacheKey = cacheKeyFor(sourceUrl, label, metadata);
        }
        File cached = new File(cacheDir(context), cacheKey);
        if (cached.isDirectory()) return;
        try {
            copyDirectory(extractedDir(context), cached);
            writeCachedDriverInfo(new CachedDriver(cacheKey, cached, label, source, sourceUrl,
                    metadata.libraryName, metadata));
            prefs(context).edit().putString(PREF_DRIVER_CACHE_KEY, cacheKey).apply();
        } catch (IOException e) {
            Log.w(TAG, "Could not migrate active GPU driver into cache", e);
            deleteRecursively(cached);
        }
    }

    private static CachedDriver readCachedDriver(File directory) {
        try {
            DriverMetadata metadata = readMetadata(directory);
            if (metadata == null) return null;
            String label = metadata.displayLabel(directory.getName());
            String source = "";
            String sourceUrl = "";
            String libraryName = metadata.libraryName;

            File manager = new File(directory, MANAGER_JSON);
            if (manager.isFile()) {
                JSONObject json = new JSONObject(readString(manager));
                label = json.optString("label", label);
                source = json.optString("source", "");
                sourceUrl = json.optString("sourceUrl", "");
                libraryName = json.optString("libraryName", libraryName);
            }

            if (TextUtils.isEmpty(libraryName)) {
                libraryName = findVulkanLibrary(directory);
                metadata = metadata.withLibraryName(libraryName);
            }
            if (Build.VERSION.SDK_INT < metadata.minApi
                    || !isSafeVulkanLibraryName(libraryName)
                    || !containsLibrary(directory, libraryName)) {
                return null;
            }
            return new CachedDriver(directory.getName(), directory, label, source, sourceUrl,
                    libraryName, metadata.withLibraryName(libraryName));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Could not read cached GPU driver " + directory.getAbsolutePath(), e);
            return null;
        }
    }

    private static void writeCachedDriverInfo(CachedDriver driver) throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("label", driver.label);
            json.put("source", driver.source == null ? "" : driver.source);
            json.put("sourceUrl", driver.sourceUrl == null ? "" : driver.sourceUrl);
            json.put("libraryName", driver.libraryName);
        } catch (JSONException e) {
            throw new IOException("Could not serialize driver cache metadata", e);
        }
        writeString(new File(driver.directory, MANAGER_JSON), json.toString());
    }

    private static void replaceActiveDriver(File source, File active) throws IOException {
        File activeTmp = new File(active.getParentFile(), ACTIVE_TMP_DIR);
        deleteRecursively(activeTmp);
        copyDirectory(source, activeTmp);
        deleteRecursively(active);
        Files.move(activeTmp.toPath(), active.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void download(String url, String fallbackUrl, File output,
                                 ProgressListener progress)
            throws IOException {
        try {
            downloadOnce(url, output, progress);
        } catch (IOException primaryError) {
            deleteIfExists(output);
            if (TextUtils.isEmpty(fallbackUrl)) {
                throw primaryError;
            }
            Log.w(TAG, "Primary GPU driver download failed; trying fallback", primaryError);
            downloadOnce(fallbackUrl, output, progress);
        }
    }

    private static void downloadOnce(String url, File output, ProgressListener progress)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Driver download failed with HTTP " + code);
        }
        long total = connection.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
            copyWithProgress(in, out, "Downloading driver", total, progress, MAX_DRIVER_BYTES);
        } finally {
            connection.disconnect();
        }
    }

    private static void unzipDriver(InputStream input, File outputDir) throws IOException {
        String root = outputDir.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[128 * 1024];
        long total = 0L;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File dst = new File(outputDir, entry.getName());
                String dstPath = dst.getCanonicalPath();
                if (!dstPath.startsWith(root)) {
                    throw new IOException("Driver archive contains unsafe path " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!dst.mkdirs() && !dst.isDirectory()) {
                        throw new IOException("Could not create " + dst.getAbsolutePath());
                    }
                    continue;
                }
                File parent = dst.getParentFile();
                if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("Could not create " + parent.getAbsolutePath());
                }
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_DRIVER_BYTES) {
                            throw new IOException("Driver archive is too large");
                        }
                        out.write(buffer, 0, n);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void copyWithProgress(InputStream in, OutputStream out, String stage, long total,
                                         ProgressListener progress, long maxBytes)
            throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long completed = 0L;
        if (progress != null) progress.onProgress(stage, 0L, total);
        int n;
        while ((n = in.read(buffer)) > 0) {
            completed += n;
            if (completed > maxBytes) {
                throw new IOException("Driver download is too large");
            }
            out.write(buffer, 0, n);
            if (progress != null) progress.onProgress(stage, completed, total);
        }
    }

    private static DriverMetadata readMetadata(File dir) throws IOException, JSONException {
        File metadata = new File(dir, META_JSON);
        if (!metadata.isFile()) return null;
        return DriverMetadata.fromJson(readString(metadata));
    }

    private static String findVulkanLibrary(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return "";
        for (File file : files) {
            if (file.isDirectory()) {
                String nested = findVulkanLibrary(file);
                if (!TextUtils.isEmpty(nested)) return nested;
            } else {
                String name = file.getName();
                if (name.startsWith("vulkan") && name.endsWith(".so")) {
                    return name;
                }
            }
        }
        return "";
    }

    private static boolean containsLibrary(File dir, String libraryName) {
        if (!isSafeVulkanLibraryName(libraryName)) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isDirectory()) {
                if (containsLibrary(file, libraryName)) return true;
            } else if (libraryName.equals(file.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeVulkanLibraryName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        return name.equals(new File(name).getName())
                && name.startsWith("vulkan")
                && name.endsWith(".so")
                && !name.contains("/")
                && !name.contains("\\");
    }

    private static String readString(InputStream in) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        StringBuilder builder = new StringBuilder();
        int n;
        while ((n = in.read(buffer)) > 0) {
            builder.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static String readString(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return readString(in);
        }
    }

    private static void writeString(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create " + parent.getAbsolutePath());
        }
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyDirectory(File source, File destination) throws IOException {
        if (!source.isDirectory()) {
            throw new IOException("Source directory does not exist: " + source.getAbsolutePath());
        }
        if (!destination.mkdirs() && !destination.isDirectory()) {
            throw new IOException("Could not create " + destination.getAbsolutePath());
        }
        File[] files = source.listFiles();
        if (files == null) return;
        for (File file : files) {
            File dst = new File(destination, file.getName());
            if (file.isDirectory()) {
                copyDirectory(file, dst);
            } else {
                Files.copy(file.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteIfExists(File file) throws IOException {
        if (file.exists()) Files.delete(file.toPath());
    }

    private static void deleteIfExistsQuietly(File file) {
        try {
            deleteIfExists(file);
        } catch (IOException e) {
            Log.w(TAG, "Could not delete " + file.getAbsolutePath(), e);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) deleteRecursively(child);
        }
        if (!file.delete()) {
            Log.w(TAG, "Could not delete " + file.getAbsolutePath());
        }
    }

    private static String cacheKeyFor(String sourceUrl, String fallbackLabel, DriverMetadata metadata) {
        String material = (sourceUrl == null ? "" : sourceUrl) + "\n"
                + (fallbackLabel == null ? "" : fallbackLabel) + "\n"
                + metadata.name + "\n"
                + metadata.packageVersion + "\n"
                + metadata.driverVersion + "\n"
                + metadata.libraryName;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("driver-");
            for (int i = 0; i < 8 && i < hash.length; i++) {
                builder.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return "driver-" + Integer.toHexString(material.hashCode());
        }
    }

    public static final class CachedDriver {
        public final String cacheKey;
        public final File directory;
        public final String label;
        public final String source;
        public final String sourceUrl;
        public final String libraryName;
        public final DriverMetadata metadata;

        CachedDriver(String cacheKey, File directory, String label, String source,
                     String sourceUrl, String libraryName, DriverMetadata metadata) {
            this.cacheKey = cacheKey;
            this.directory = directory;
            this.label = label;
            this.source = source;
            this.sourceUrl = sourceUrl;
            this.libraryName = libraryName;
            this.metadata = metadata;
        }

        public String summary() {
            if (!TextUtils.isEmpty(source)) return source;
            if (!TextUtils.isEmpty(sourceUrl)) return sourceUrl;
            return libraryName;
        }
    }

    public static final class DriverPackage {
        public final String repositorySlug;
        public final String repositoryLabel;
        public final String tagName;
        public final String releaseName;
        public final String publishedAt;
        public final String assetName;
        public final String downloadUrl;
        public final String fallbackDownloadUrl;
        public final long sizeBytes;
        public final String displayName;

        DriverPackage(String repositorySlug, String repositoryLabel, String tagName,
                      String releaseName, String publishedAt, String assetName,
                      String downloadUrl, String fallbackDownloadUrl, long sizeBytes) {
            this.repositorySlug = repositorySlug;
            this.repositoryLabel = repositoryLabel;
            this.tagName = tagName;
            this.releaseName = releaseName;
            this.publishedAt = publishedAt;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.fallbackDownloadUrl = fallbackDownloadUrl;
            this.sizeBytes = sizeBytes;
            this.displayName = makeDisplayName(repositoryLabel, releaseName, assetName);
        }

        public String summary() {
            return repositoryLabel + " - " + formatBytes(sizeBytes);
        }
    }

    public static final class DriverMetadata {
        public final String name;
        public final String author;
        public final String packageVersion;
        public final String vendor;
        public final String driverVersion;
        public final int minApi;
        public final String description;
        public final String libraryName;

        DriverMetadata(String name, String author, String packageVersion, String vendor,
                       String driverVersion, int minApi, String description, String libraryName) {
            this.name = name;
            this.author = author;
            this.packageVersion = packageVersion;
            this.vendor = vendor;
            this.driverVersion = driverVersion;
            this.minApi = minApi;
            this.description = description;
            this.libraryName = libraryName;
        }

        static DriverMetadata fromJson(String text) throws JSONException {
            JSONObject json = new JSONObject(text);
            return new DriverMetadata(
                    json.optString("name"),
                    json.optString("author"),
                    json.optString("packageVersion"),
                    json.optString("vendor"),
                    json.optString("driverVersion"),
                    json.optInt("minApi", 0),
                    json.optString("description"),
                    json.optString("libraryName"));
        }

        DriverMetadata withLibraryName(String libraryName) {
            return new DriverMetadata(name, author, packageVersion, vendor, driverVersion,
                    minApi, description, libraryName);
        }

        String displayLabel(String fallback) {
            if (!TextUtils.isEmpty(name)) {
                if (!TextUtils.isEmpty(packageVersion)) return name + " " + packageVersion;
                if (!TextUtils.isEmpty(driverVersion)) return name + " " + driverVersion;
                return name;
            }
            return fallback;
        }
    }

    public static final class InstallResult {
        public final boolean success;
        public final DriverMetadata metadata;
        public final String error;

        private InstallResult(boolean success, DriverMetadata metadata, String error) {
            this.success = success;
            this.metadata = metadata;
            this.error = error;
        }

        static InstallResult success(DriverMetadata metadata) {
            return new InstallResult(true, metadata, null);
        }

        static InstallResult missingMetadata() {
            return new InstallResult(false, null, "Driver package is missing meta.json");
        }

        static InstallResult invalidMetadata(String error) {
            return new InstallResult(false, null, error);
        }

        static InstallResult unsupportedAndroid(int minApi) {
            return new InstallResult(false, null,
                    "This driver requires Android API " + minApi + " or newer");
        }
    }

    private static final class DriverRepository {
        final String slug;
        final String label;

        DriverRepository(String slug, String label) {
            this.slug = slug;
            this.label = label;
        }
    }

    private static String makeDisplayName(String repoLabel, String releaseName, String assetName) {
        String base = assetName == null ? releaseName : assetName;
        if (base == null) base = repoLabel;
        base = base.replace(".adpkg.zip", "")
                .replace(".zip", "")
                .replace('_', ' ')
                .trim();
        return base;
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0L) return "unknown size";
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.US, unit == 0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }
}
