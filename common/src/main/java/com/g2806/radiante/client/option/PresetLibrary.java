package com.g2806.radiante.client.option;

import com.g2806.radiante.client.RadianteClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The looks a player keeps: their own, saved from the settings, and those imported from codes other people shared.
 * Each is a name and a settings code (see {@link SettingsCode}), kept in {@code radiante/presets.json} so that a
 * look is switched to with a click, as a shader pack is, instead of being pasted in again.
 */
public final class PresetLibrary {

    public record Preset(String name, String code) {
    }

    private static final String FILE = "presets.json";
    private static final int MAX_NAME_LENGTH = 40;
    private static final int MAX_PRESETS = 200;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static List<Preset> presets;

    private PresetLibrary() {
    }

    public static synchronized List<Preset> all() {
        return Collections.unmodifiableList(new ArrayList<>(loaded()));
    }

    /**
     * Keeps a look under a name; a name already taken gets a number after it. Returns its place in the list, or -1
     * when the code is not a settings code or the library is full.
     */
    public static synchronized int add(String name, String code) {
        List<Preset> list = loaded();
        String trimmed = code == null ? "" : code.trim();
        if (SettingsCode.parse(trimmed) == null || list.size() >= MAX_PRESETS) {
            return -1;
        }
        // The same look twice is the one entry: importing a code again selects what is there.
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).code().equals(trimmed)) {
                return i;
            }
        }
        list.add(new Preset(freeName(clean(name), -1), trimmed));
        save();
        return list.size() - 1;
    }

    public static synchronized void remove(int index) {
        List<Preset> list = loaded();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            save();
        }
    }

    public static synchronized void rename(int index, String name) {
        List<Preset> list = loaded();
        if (index >= 0 && index < list.size() && !clean(name).isEmpty()) {
            list.set(index, new Preset(freeName(clean(name), index), list.get(index).code()));
            save();
        }
    }

    /** The place of the look the game has now, or -1 when it is none of the kept ones. */
    public static synchronized int current() {
        String now = SettingsCode.export();
        List<Preset> list = loaded();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).code().equals(now)) {
                return i;
            }
        }
        return -1;
    }

    private static String clean(String name) {
        String cleaned = name == null ? "" : name.strip().replaceAll("\\p{Cntrl}", "");
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }

    private static String freeName(String wanted, int except) {
        String base = wanted.isEmpty() ? "Preset" : wanted;
        String name = base;
        for (int number = 2; taken(name, except); number++) {
            name = base + " " + number;
        }
        return name;
    }

    private static boolean taken(String name, int except) {
        List<Preset> list = loaded();
        for (int i = 0; i < list.size(); i++) {
            if (i != except && list.get(i).name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<Preset> loaded() {
        if (presets != null) {
            return presets;
        }
        presets = new ArrayList<>();
        Path path = path();
        if (path == null || !Files.isRegularFile(path)) {
            return presets;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("presets")) {
                JsonObject entry = element.getAsJsonObject();
                String name = clean(entry.get("name").getAsString());
                String code = entry.get("code").getAsString().trim();
                // A file is whatever was put there; only names and codes this version would accept get in.
                if (!name.isEmpty() && SettingsCode.parse(code) != null && presets.size() < MAX_PRESETS) {
                    presets.add(new Preset(name, code));
                }
            }
        } catch (IOException | RuntimeException e) {
            RadianteClient.LOGGER.warn("Could not read the saved presets ({}); starting with none", path, e);
        }
        return presets;
    }

    private static void save() {
        Path path = path();
        if (path == null) {
            return;
        }
        JsonArray array = new JsonArray();
        for (Preset preset : presets) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", preset.name());
            entry.addProperty("code", preset.code());
            array.add(entry);
        }
        JsonObject root = new JsonObject();
        root.add("presets", array);
        try {
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            RadianteClient.LOGGER.warn("Could not save the presets to {}", path, e);
        }
    }

    private static Path path() {
        return RadianteClient.radianceDir == null ? null : RadianteClient.radianceDir.resolve(FILE);
    }
}
