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
 * Persists per-form combat tuning ({@link FormCombatData}) in {@code config/sdu/form_combat.json}, keyed by
 * form name. Kept separate from DMZ's form configs so auto-dodge / damage-taken buffs layer on top of any
 * form without touching DMZ files. The in-memory map is the source of truth for the runtime handler and editor.
 */
public final class FormCombatConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    private static final Map<String, FormCombatData> BY_FORM = new LinkedHashMap<>();
    private static boolean loaded = false;

    /**
     * Bumped whenever the in-memory map changes (disk reload or an editor {@link #put}). The combat runtime
     * reads this so it can tell "nothing changed since last tick" and skip re-applying stat buffs, without
     * having to diff the config itself. Volatile: written under the class lock, read from the tick thread.
     */
    private static volatile int revision = 0;

    private FormCombatConfig() {
    }

    /** Current config revision (see {@link #revision}). Increments on every reload or edit. */
    public static int revision() {
        ensureLoaded();
        return revision;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("form_combat.json");
    }

    /** The combat settings for a form name (never null - blank defaults if none saved). */
    public static FormCombatData get(String formName) {
        ensureLoaded();
        FormCombatData d = BY_FORM.get(key(formName));
        return d == null ? new FormCombatData() : d.copy();
    }

    /** True if this form has non-default combat settings on disk. */
    public static boolean has(String formName) {
        ensureLoaded();
        return BY_FORM.containsKey(key(formName));
    }

    /** Store (or, if empty/default, remove) a form's combat settings in memory. Call {@link #save()} to persist. */
    public static void put(String formName, FormCombatData data) {
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
        revision++;
    }

    public static synchronized void load() {
        BY_FORM.clear();
        loaded = true;
        revision++;
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
                        BY_FORM.put(key(name), FormCombatData.fromJson(forms.getAsJsonObject(name)));
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded form-combat config: {} form(s).", DmzNpc.MODID, BY_FORM.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read form-combat config: {}", DmzNpc.MODID, e.toString());
        }
    }

    public static synchronized void save() {
        ensureLoaded();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject forms = new JsonObject();
            for (Map.Entry<String, FormCombatData> e : BY_FORM.entrySet()) {
                forms.add(e.getKey(), e.getValue().toJson());
            }
            root.add("forms", forms);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
            DmzNpc.LOGGER.info("[{}] Saved form-combat config ({} form(s)).", DmzNpc.MODID, BY_FORM.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save form-combat config: {}", DmzNpc.MODID, e.toString());
        }
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
