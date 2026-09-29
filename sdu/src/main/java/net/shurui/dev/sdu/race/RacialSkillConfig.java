package net.shurui.dev.sdu.race;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

// loads/persists the addon's JSON-editable racial-skill behaviour in config/sdu/racial_skills.json, keyed by
// racial id. separate from DMZ's configs so custom racials gain effects without editing DMZ files. the
// in-memory map is the source of truth for the runtime handler and race editor.
public final class RacialSkillConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    private static final Map<String, RacialSkillData> BY_ID = new LinkedHashMap<>();
    private static boolean loaded = false;

    private RacialSkillConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("racial_skills.json");
    }

    /** The behaviour for a racial id (never null - blank defaults if none saved). */
    public static RacialSkillData get(String racialId) {
        ensureLoaded();
        RacialSkillData d = BY_ID.get(key(racialId));
        return d == null ? new RacialSkillData() : d.copy();
    }

    /** True if this racial id has non-default behaviour configured. */
    public static boolean has(String racialId) {
        ensureLoaded();
        return BY_ID.containsKey(key(racialId));
    }

    /** Store (or, if empty, remove) a racial's behaviour in memory. Call {@link #save()} to persist. */
    public static void put(String racialId, RacialSkillData data) {
        ensureLoaded();
        String k = key(racialId);
        if (k.isEmpty()) {
            return;
        }
        if (data == null || data.isEmpty()) {
            BY_ID.remove(k);
        } else {
            BY_ID.put(k, data.copy());
        }
    }

    public static synchronized void load() {
        BY_ID.clear();
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null && root.has("racials") && root.get("racials").isJsonObject()) {
                JsonObject racials = root.getAsJsonObject("racials");
                for (String id : racials.keySet()) {
                    if (racials.get(id).isJsonObject()) {
                        BY_ID.put(key(id), RacialSkillData.fromJson(racials.getAsJsonObject(id)));
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded racial-skill config: {} racial(s).", DmzNpc.MODID, BY_ID.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read racial-skill config: {}", DmzNpc.MODID, e.toString());
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject racials = new JsonObject();
            for (Map.Entry<String, RacialSkillData> e : BY_ID.entrySet()) {
                racials.add(e.getKey(), e.getValue().toJson());
            }
            root.add("racials", racials);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
            DmzNpc.LOGGER.info("[{}] Saved racial-skill config ({} racial(s)).", DmzNpc.MODID, BY_ID.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save racial-skill config: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static String key(String racialId) {
        return racialId == null ? "" : racialId.trim();
    }
}
