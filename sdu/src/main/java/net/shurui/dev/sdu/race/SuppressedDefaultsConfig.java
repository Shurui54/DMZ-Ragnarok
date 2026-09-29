package net.shurui.dev.sdu.race;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// server-wide list of DMZ DEFAULT races/classes to suppress. DMZ has no "disabled" flag and regenerates its
// six default races + default classes on every boot/reload, so deleting their config folders is undone. this
// records the ids we want gone; DmzCompat.applySuppression() re-strips them from DMZ's in-memory maps AFTER
// DMZ finishes (re)loading. stored at config/sdu/suppressed_defaults.json, both id sets lowercased.
// server-authoritative: clients drop a suppressed race once the stripped SERVER_SYNCED_* maps resync.
// suppressing a race a player currently IS doesn't migrate their data (DMZ falls back to synthesized defaults).
public final class SuppressedDefaultsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;
    private static final String COMMENT =
            "Default DMZ race/class ids suppressed by sdu. races: human, saiyan, namekian, frostdemon, "
                    + "bioandroid, majin. classes: warrior, martialartist, spiritualist, berserker, paladin, "
                    + "tank, cleric.";

    private static final Set<String> RACES = new LinkedHashSet<>();
    private static final Set<String> CLASSES = new LinkedHashSet<>();
    private static boolean loaded = false;

    private SuppressedDefaultsConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("suppressed_defaults.json");
    }

    public static synchronized Set<String> races() {
        ensureLoaded();
        return Collections.unmodifiableSet(new LinkedHashSet<>(RACES));
    }

    public static synchronized Set<String> classes() {
        ensureLoaded();
        return Collections.unmodifiableSet(new LinkedHashSet<>(CLASSES));
    }

    public static synchronized boolean isRaceSuppressed(String id) {
        ensureLoaded();
        return RACES.contains(key(id));
    }

    public static synchronized boolean isClassSuppressed(String id) {
        ensureLoaded();
        return CLASSES.contains(key(id));
    }

    // true if the set changed.
    public static synchronized boolean suppressRace(String id) {
        ensureLoaded();
        String k = key(id);
        if (k.isEmpty() || !RACES.add(k)) {
            return false;
        }
        save();
        return true;
    }

    // true if the set changed.
    public static synchronized boolean unsuppressRace(String id) {
        ensureLoaded();
        if (!RACES.remove(key(id))) {
            return false;
        }
        save();
        return true;
    }

    // true if the set changed.
    public static synchronized boolean suppressClass(String id) {
        ensureLoaded();
        String k = key(id);
        if (k.isEmpty() || !CLASSES.add(k)) {
            return false;
        }
        save();
        return true;
    }

    // true if the set changed.
    public static synchronized boolean unsuppressClass(String id) {
        ensureLoaded();
        if (!CLASSES.remove(key(id))) {
            return false;
        }
        save();
        return true;
    }

    // DMZ's config JSON for "races/<race>/stats" with every suppressed class removed from "classes". Returns the SAME
    // string instance when there is nothing to strip, so a caller can tell a no-op by identity. Used where DMZ reads
    // a config off disk to send to players (see SuppressedClassesSyncMixin). Never throws: on a parse problem the
    // original JSON goes out, which is exactly what DMZ would have sent anyway.
    public static String stripSuppressedClasses(String configName, String json) {
        if (json == null || configName == null) {
            return json;
        }
        String name = configName.replace('\\', '/');
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        if (!name.startsWith("races/") || !name.endsWith("/stats") || name.chars().filter(c -> c == '/').count() != 2) {
            return json;
        }
        Set<String> suppressed = classes();
        if (suppressed.isEmpty()) {
            return json;
        }
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("classes") || !root.get("classes").isJsonObject()) {
                return json;
            }
            JsonObject classes = root.getAsJsonObject("classes");
            boolean removed = false;
            for (String id : new java.util.ArrayList<>(classes.keySet())) {
                if (suppressed.contains(key(id))) {
                    classes.remove(id);
                    removed = true;
                }
            }
            return removed ? GSON.toJson(root) : json;
        } catch (Exception e) {
            return json;
        }
    }

    public static synchronized void load() {
        RACES.clear();
        CLASSES.clear();
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null) {
                readInto(root, "races", RACES);
                readInto(root, "classes", CLASSES);
            }
            DmzNpc.LOGGER.info("[{}] Loaded suppressed defaults: {} race(s), {} class(es).",
                    DmzNpc.MODID, RACES.size(), CLASSES.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read suppressed-defaults config: {}", DmzNpc.MODID, e.toString());
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            root.addProperty("_comment", COMMENT);
            root.add("races", toArray(RACES));
            root.add("classes", toArray(CLASSES));
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
            DmzNpc.LOGGER.info("[{}] Saved suppressed defaults ({} race(s), {} class(es)).",
                    DmzNpc.MODID, RACES.size(), CLASSES.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save suppressed-defaults config: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static void readInto(JsonObject root, String key, Set<String> into) {
        if (root.has(key) && root.get(key).isJsonArray()) {
            for (var el : root.getAsJsonArray(key)) {
                if (el.isJsonPrimitive()) {
                    String v = key(el.getAsString());
                    if (!v.isEmpty()) {
                        into.add(v);
                    }
                }
            }
        }
    }

    private static JsonArray toArray(Set<String> set) {
        JsonArray arr = new JsonArray();
        for (String s : set) {
            arr.add(s);
        }
        return arr;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
