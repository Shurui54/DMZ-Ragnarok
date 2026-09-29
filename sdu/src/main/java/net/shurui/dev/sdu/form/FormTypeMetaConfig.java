package net.shurui.dev.sdu.form;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists per-form-type presentation meta ({@link FormTypeMeta}: stock icon + tint) in
 * {@code config/dragonminez/sdu_formtype_meta.json}, keyed by sanitized type id. Beside DMZ's
 * {@code skills.json} but never in it. Pushed to clients on login and after each edit, where
 * {@code AbstractRadialNodeMixin} and {@code SkillsMenuScreenMixin} read it to swap in a real icon/tint.
 */
public final class FormTypeMetaConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Map<String, FormTypeMeta> BY_TYPE = new LinkedHashMap<>();
    private static boolean loaded = false;

    private FormTypeMetaConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("dragonminez").resolve("sdu_formtype_meta.json");
    }

    /** The meta for a type id (never null - a stock default when nothing is saved). */
    public static FormTypeMeta get(String typeId) {
        ensureLoaded();
        FormTypeMeta m = BY_TYPE.get(key(typeId));
        return m != null ? m : new FormTypeMeta(FormTypeMeta.DEFAULT_ICON, FormTypeMeta.USE_AURA_TINT);
    }

    public static boolean has(String typeId) {
        ensureLoaded();
        return BY_TYPE.containsKey(key(typeId));
    }

    /** Store a type's meta and persist immediately (server-side edit path). */
    public static synchronized void put(String typeId, FormTypeMeta meta) {
        ensureLoaded();
        String k = key(typeId);
        if (k.isEmpty() || meta == null) {
            return;
        }
        BY_TYPE.put(k, meta);
        save();
    }

    /** Drop a type's meta (when the type is removed) and persist immediately. */
    public static synchronized void remove(String typeId) {
        ensureLoaded();
        if (BY_TYPE.remove(key(typeId)) != null) {
            save();
        }
    }

    public static synchronized void load() {
        BY_TYPE.clear();
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null && root.has("types") && root.get("types").isJsonObject()) {
                JsonObject types = root.getAsJsonObject("types");
                for (String id : types.keySet()) {
                    if (types.get(id).isJsonObject()) {
                        BY_TYPE.put(key(id), FormTypeMeta.fromJson(types.getAsJsonObject(id)));
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded form-type meta: {} type(s).", DmzNpc.MODID, BY_TYPE.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read form-type meta: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject types = new JsonObject();
            for (Map.Entry<String, FormTypeMeta> e : BY_TYPE.entrySet()) {
                types.add(e.getKey(), e.getValue().toJson());
            }
            root.add("types", types);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save form-type meta: {}", DmzNpc.MODID, e.toString());
        }
    }

    /** Snapshot of all stored meta ({@code typeId -> meta}) for server -> client sync. */
    public static synchronized Map<String, FormTypeMeta> all() {
        ensureLoaded();
        return new LinkedHashMap<>(BY_TYPE);
    }

    /** Replace the in-memory meta from a server sync (client on a dedicated server has no local file). */
    public static synchronized void applySynced(Map<String, FormTypeMeta> data) {
        BY_TYPE.clear();
        loaded = true;
        if (data != null) {
            for (Map.Entry<String, FormTypeMeta> e : data.entrySet()) {
                if (e.getValue() != null) {
                    BY_TYPE.put(key(e.getKey()), e.getValue());
                }
            }
        }
        DmzNpc.LOGGER.info("[{}] Applied synced form-type meta: {} type(s).", DmzNpc.MODID, BY_TYPE.size());
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static String key(String typeId) {
        return typeId == null ? "" : net.shurui.dev.sdu.util.SduIds.sanitize(typeId);
    }
}
