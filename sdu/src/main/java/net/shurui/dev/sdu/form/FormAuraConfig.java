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
 * Persists per-form extra aura layers ({@link FormAuraData}) in {@code config/sdu/form_auras.json}, keyed
 * by form name. Out of DMZ's configs so it layers on top of any form. Read by the editor and the
 * client-side {@code AuraRendererMixin}.
 */
public final class FormAuraConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    private static final Map<String, FormAuraData> BY_FORM = new LinkedHashMap<>();
    private static boolean loaded = false;

    private FormAuraConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("form_auras.json");
    }

    /** The extra aura layers for a form name (never null - empty if none saved). */
    public static FormAuraData get(String formName) {
        ensureLoaded();
        FormAuraData d = BY_FORM.get(key(formName));
        return d == null ? new FormAuraData() : d.copy();
    }

    public static boolean has(String formName) {
        ensureLoaded();
        return BY_FORM.containsKey(key(formName));
    }

    /** Store (or, if empty, remove) a form's extra aura layers in memory. Call {@link #save()} to persist. */
    public static void put(String formName, FormAuraData data) {
        ensureLoaded();
        String k = key(formName);
        if (k.isEmpty()) {
            return;
        }
        if (data == null || data.isEmpty()) {
            BY_FORM.remove(k);
        } else {
            BY_FORM.put(k, data.copy());
        }
    }

    public static synchronized void load() {
        BY_FORM.clear();
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null && root.has("forms") && root.get("forms").isJsonObject()) {
                JsonObject forms = root.getAsJsonObject("forms");
                for (String name : forms.keySet()) {
                    if (forms.get(name).isJsonObject()) {
                        BY_FORM.put(key(name), FormAuraData.fromJson(forms.getAsJsonObject(name)));
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded form-aura config: {} form(s).", DmzNpc.MODID, BY_FORM.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read form-aura config: {}", DmzNpc.MODID, e.toString());
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject forms = new JsonObject();
            for (Map.Entry<String, FormAuraData> e : BY_FORM.entrySet()) {
                forms.add(e.getKey(), e.getValue().toJson());
            }
            root.add("forms", forms);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save form-aura config: {}", DmzNpc.MODID, e.toString());
        }
    }

    /** Snapshot of all stored aura layers ({@code formName -> data}) for server -> client sync. */
    public static synchronized Map<String, FormAuraData> all() {
        ensureLoaded();
        Map<String, FormAuraData> out = new LinkedHashMap<>();
        for (Map.Entry<String, FormAuraData> e : BY_FORM.entrySet()) {
            out.put(e.getKey(), e.getValue().copy());
        }
        return out;
    }

    /** Replace the in-memory config from a server sync (client side on a dedicated server). */
    public static synchronized void applySynced(Map<String, FormAuraData> data) {
        BY_FORM.clear();
        loaded = true;
        if (data != null) {
            for (Map.Entry<String, FormAuraData> e : data.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    BY_FORM.put(key(e.getKey()), e.getValue().copy());
                }
            }
        }
        DmzNpc.LOGGER.info("[{}] Applied synced form-aura config: {} form(s).", DmzNpc.MODID, BY_FORM.size());
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static String key(String formName) {
        return formName == null ? "" : formName.trim();
    }
}
