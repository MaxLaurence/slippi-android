package org.dolphinemu.dolphinemu.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.text.TextUtils;
import android.util.Log;

import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GameSettingsOverride {
    private static final String TAG = "GameSettingsOverride";
    private static final String PREFS = "game_settings_overrides";
    private static final String PREF_MELEE_WIDESCREEN = "melee_widescreen";
    private static final String PREF_CUSTOM_CODES = "custom_codes";
    private static final String PREF_CODE_PREFIX = "code_enabled.";
    private static final String WIDESCREEN_CODE_NAME = "Optional: Widescreen 16:9";
    private static final String LEGACY_WIDESCREEN_CODE_NAME = "Widescreen 16:9";
    private static final String ANDROID_HEADER =
            "# Android user GameSettings override";

    public static final String[] MELEE_INI_NAMES = {
            "GALE01r2.ini", "GALJ01r2.ini", "GALEXX.ini"
    };

    private GameSettingsOverride() {}

    public static boolean isMeleeWidescreenEnabled(Context context) {
        return prefs(context).getBoolean(PREF_MELEE_WIDESCREEN, false);
    }

    public static void setMeleeWidescreenEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_MELEE_WIDESCREEN, enabled).apply();
    }

    public static List<GeckoCodeEntry> loadBundledCodes(Context context) {
        return loadBundledCodes(context, "GALE01r2.ini");
    }

    private static List<GeckoCodeEntry> loadBundledCodes(Context context, String iniName) {
        try {
            return parseBundledCodes(context, "Sys/GameSettings/" + iniName);
        } catch (IOException ex) {
            Log.w(TAG, "could not load bundled Gecko codes", ex);
            return new ArrayList<>();
        }
    }

    public static boolean isCodeEnabled(Context context, GeckoCodeEntry entry) {
        if (entry.required) return true;
        SharedPreferences p = prefs(context);
        String key = codePrefKey(entry.name);
        if (p.contains(key)) return p.getBoolean(key, entry.defaultEnabled);
        if (isMeleeWidescreenCode(entry) && isMeleeWidescreenEnabled(context)) {
            return true;
        }
        return entry.defaultEnabled;
    }

    public static boolean isMeleeWidescreenCode(GeckoCodeEntry entry) {
        return entry != null && (WIDESCREEN_CODE_NAME.equals(entry.name)
                || LEGACY_WIDESCREEN_CODE_NAME.equals(entry.name));
    }

    public static void setCodeEnabled(Context context, GeckoCodeEntry entry, boolean enabled) {
        if (entry.required) return;
        prefs(context).edit().putBoolean(codePrefKey(entry.name), enabled).apply();
    }

    public static boolean hasCodeOverride(Context context, GeckoCodeEntry entry) {
        return prefs(context).contains(codePrefKey(entry.name));
    }

    public static List<CustomGeckoCode> loadCustomCodes(Context context) {
        List<CustomGeckoCode> result = new ArrayList<>();
        String raw = prefs(context).getString(PREF_CUSTOM_CODES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                result.add(new CustomGeckoCode(
                        o.optString("id"),
                        o.optString("title"),
                        o.optString("body"),
                        o.optBoolean("enabled", true)));
            }
        } catch (JSONException ex) {
            Log.w(TAG, "could not parse custom Gecko codes", ex);
        }
        return result;
    }

    public static void saveCustomCode(Context context, CustomGeckoCode code) {
        List<CustomGeckoCode> codes = loadCustomCodes(context);
        String id = TextUtils.isEmpty(code.id) ? UUID.randomUUID().toString() : code.id;
        CustomGeckoCode saved = new CustomGeckoCode(id, code.title, code.body, code.enabled);
        boolean replaced = false;
        for (int i = 0; i < codes.size(); i++) {
            if (id.equals(codes.get(i).id)) {
                codes.set(i, saved);
                replaced = true;
                break;
            }
        }
        if (!replaced) codes.add(saved);
        writeCustomCodes(context, codes);
    }

    public static void deleteCustomCode(Context context, String id) {
        List<CustomGeckoCode> codes = loadCustomCodes(context);
        List<CustomGeckoCode> kept = new ArrayList<>();
        for (CustomGeckoCode code : codes) {
            if (!code.id.equals(id)) kept.add(code);
        }
        writeCustomCodes(context, kept);
    }

    public static String validateCustomCode(String title, String body) {
        if (TextUtils.isEmpty(title) || title.trim().isEmpty()) {
            return "Enter a code name.";
        }
        if (TextUtils.isEmpty(body) || body.trim().isEmpty()) {
            return "Enter at least one Gecko code line.";
        }
        String[] lines = body.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("*")) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2 || !isEightHex(parts[0]) || !isEightHex(parts[1])) {
                return "Invalid Gecko line: " + trimmed;
            }
        }
        return null;
    }

    public static void applyLiveMode(Context context) {
        applyLiveMode(context, UserDirectoryBootstrap.userDir(context));
    }

    public static void applyLiveMode(Context context, File userDir) {
        File dir = overrideDir(userDir);
        if (!hasAnyUserChoice(context)) {
            deleteOverrides(dir, MELEE_INI_NAMES);
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir);
            return;
        }
        for (String name : MELEE_INI_NAMES) {
            IniDocument doc = new IniDocument();
            doc.setSection("Gecko", new ArrayList<>());
            applyUserChoicesToDocument(context, doc, name);
            writeDocument(new File(dir, name), doc);
        }
    }

    public static void applyUserChoices(Context context, File userDir, String[] iniNames) {
        File dir = overrideDir(userDir);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir);
            return;
        }
        for (String name : iniNames) {
            File file = new File(dir, name);
            IniDocument doc = IniDocument.read(file);
            applyUserChoicesToDocument(context, doc, name);
            writeDocument(file, doc);
        }
    }

    public static void forceCodeState(File userDir, String[] iniNames, String codeTitle,
                                      boolean enabled) {
        File dir = overrideDir(userDir);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir);
            return;
        }
        String line = codeTitle.startsWith("$") ? codeTitle : "$" + codeTitle;
        for (String name : iniNames) {
            File file = new File(dir, name);
            IniDocument doc = IniDocument.read(file);
            forceCodeState(doc, line, enabled);
            writeDocument(file, doc);
        }
    }

    public static File overrideDir(File userDir) {
        return new File(userDir, "GameSettings");
    }

    private static void forceCodeState(IniDocument doc, String line, boolean enabled) {
        LinkedHashSet<String> enabledLines = lineSet(doc.section("Gecko_Enabled"));
        LinkedHashSet<String> disabledLines = lineSet(doc.section("Gecko_Disabled"));
        if (enabled) {
            disabledLines.remove(line);
            enabledLines.add(line);
        } else {
            enabledLines.remove(line);
            disabledLines.add(line);
        }
        doc.setSection("Gecko_Enabled", new ArrayList<>(enabledLines));
        doc.setSection("Gecko_Disabled", new ArrayList<>(disabledLines));
    }

    private static void applyUserChoicesToDocument(Context context, IniDocument doc, String iniName) {
        List<GeckoCodeEntry> bundled = loadBundledCodes(context, iniName);
        SharedPreferences p = prefs(context);
        LinkedHashSet<String> enabled = lineSet(doc.section("Gecko_Enabled"));
        LinkedHashSet<String> disabled = lineSet(doc.section("Gecko_Disabled"));

        for (GeckoCodeEntry entry : bundled) {
            if (entry.required) continue;
            String line = "$" + entry.name;
            boolean hasOverride = p.contains(codePrefKey(entry.name));
            boolean enabledByUser = hasOverride && p.getBoolean(codePrefKey(entry.name), false);
            boolean disabledByUser = hasOverride && !enabledByUser;
            if (isMeleeWidescreenCode(entry) && isMeleeWidescreenEnabled(context)) {
                enabledByUser = true;
                disabledByUser = false;
            }
            if (enabledByUser) {
                disabled.remove(line);
                enabled.add(line);
            } else if (disabledByUser && entry.defaultEnabled) {
                enabled.remove(line);
                disabled.add(line);
            } else if (disabledByUser) {
                enabled.remove(line);
            }
        }

        List<String> geckoLines = new ArrayList<>();
        geckoLines.add(ANDROID_HEADER);
        for (CustomGeckoCode code : loadCustomCodes(context)) {
            if (TextUtils.isEmpty(code.title) || TextUtils.isEmpty(code.body)) continue;
            geckoLines.add("$" + code.title.trim() + " [Android]");
            for (String rawLine : code.body.split("\\r?\\n")) {
                String line = rawLine.trim();
                if (!line.isEmpty()) geckoLines.add(line);
            }
            if (code.enabled) {
                enabled.add("$" + code.title.trim());
            } else {
                disabled.add("$" + code.title.trim());
            }
        }

        if (!enabled.isEmpty()) doc.setSection("Gecko_Enabled", new ArrayList<>(enabled));
        if (!disabled.isEmpty()) doc.setSection("Gecko_Disabled", new ArrayList<>(disabled));
        if (geckoLines.size() > 1) doc.setSection("Gecko", geckoLines);
    }

    private static boolean hasAnyUserChoice(Context context) {
        if (isMeleeWidescreenEnabled(context)) return true;
        if (!loadCustomCodes(context).isEmpty()) return true;
        SharedPreferences p = prefs(context);
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(PREF_CODE_PREFIX)) return true;
        }
        return false;
    }

    private static List<GeckoCodeEntry> parseBundledCodes(Context context, String assetPath)
            throws IOException {
        AssetManager am = context.getAssets();
        Set<String> defaultEnabled = new LinkedHashSet<>();
        List<GeckoCodeEntry> entries = new ArrayList<>();
        String currentSection = "";
        try (InputStream in = am.open(assetPath);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSection = trimmed.substring(1, trimmed.length() - 1);
                    continue;
                }
                if (!trimmed.startsWith("$")) continue;
                String name = codeNameFromTitle(trimmed);
                if ("Gecko_Enabled".equals(currentSection)) {
                    defaultEnabled.add(name);
                } else if ("Gecko".equals(currentSection)) {
                    boolean required = name.startsWith("Required:");
                    boolean optional = name.startsWith("Optional:")
                            || WIDESCREEN_CODE_NAME.equals(name)
                            || LEGACY_WIDESCREEN_CODE_NAME.equals(name);
                    entries.add(new GeckoCodeEntry(
                            name,
                            titleWithoutDollar(trimmed),
                            required,
                            optional,
                            defaultEnabled.contains(name)));
                }
            }
        }
        return entries;
    }

    private static String codeNameFromTitle(String title) {
        String value = title.startsWith("$") ? title.substring(1) : title;
        int creatorStart = value.indexOf('[');
        if (creatorStart >= 0) value = value.substring(0, creatorStart);
        return value.trim();
    }

    private static String titleWithoutDollar(String title) {
        return title.startsWith("$") ? title.substring(1).trim() : title.trim();
    }

    private static LinkedHashSet<String> lineSet(List<String> lines) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private static void writeCustomCodes(Context context, List<CustomGeckoCode> codes) {
        JSONArray array = new JSONArray();
        for (CustomGeckoCode code : codes) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", code.id);
                o.put("title", code.title);
                o.put("body", code.body);
                o.put("enabled", code.enabled);
                array.put(o);
            } catch (JSONException ignored) {
            }
        }
        prefs(context).edit().putString(PREF_CUSTOM_CODES, array.toString()).apply();
    }

    private static boolean isEightHex(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}");
    }

    private static String codePrefKey(String name) {
        return PREF_CODE_PREFIX + name;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void deleteOverrides(File dir, String[] names) {
        for (String name : names) {
            File file = new File(dir, name);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "could not delete " + file);
            }
        }
    }

    private static void writeDocument(File file, IniDocument doc) {
        if (!doc.hasWritableSections()) {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "could not delete empty override " + file);
            }
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "could not create " + parent);
            return;
        }
        try (FileWriter writer = new FileWriter(file)) {
            doc.write(writer);
        } catch (IOException ex) {
            Log.w(TAG, "could not write " + file, ex);
        }
    }

    public static final class GeckoCodeEntry {
        public final String name;
        public final String title;
        public final boolean required;
        public final boolean optional;
        public final boolean defaultEnabled;

        GeckoCodeEntry(String name, String title, boolean required,
                       boolean optional, boolean defaultEnabled) {
            this.name = name;
            this.title = title;
            this.required = required;
            this.optional = optional;
            this.defaultEnabled = defaultEnabled;
        }
    }

    public static final class CustomGeckoCode {
        public final String id;
        public final String title;
        public final String body;
        public final boolean enabled;

        public CustomGeckoCode(String id, String title, String body, boolean enabled) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
            this.enabled = enabled;
        }
    }

    private static final class IniDocument {
        private final LinkedHashMap<String, List<String>> sections = new LinkedHashMap<>();

        static IniDocument read(File file) {
            IniDocument doc = new IniDocument();
            if (!file.isFile()) return doc;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String section = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        section = trimmed.substring(1, trimmed.length() - 1);
                        doc.sections.putIfAbsent(section, new ArrayList<>());
                    } else if (section != null) {
                        doc.sections.get(section).add(line);
                    }
                }
            } catch (IOException ex) {
                Log.w(TAG, "could not read " + file, ex);
            }
            return doc;
        }

        List<String> section(String name) {
            List<String> lines = sections.get(name);
            return lines == null ? new ArrayList<>() : new ArrayList<>(lines);
        }

        void setSection(String name, List<String> lines) {
            sections.put(name, lines);
        }

        boolean hasWritableSections() {
            for (List<String> lines : sections.values()) {
                if (!lines.isEmpty()) return true;
            }
            return false;
        }

        void write(FileWriter writer) throws IOException {
            for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                writer.write("[");
                writer.write(entry.getKey());
                writer.write("]\n");
                for (String line : entry.getValue()) {
                    writer.write(line);
                    writer.write("\n");
                }
                writer.write("\n");
            }
        }
    }
}
