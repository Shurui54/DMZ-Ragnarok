package net.shurui.dev.sdu.form;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-form minimum CHARACTER level to TRANSFORM INTO a form. A separate axis from the form-skill unlock level
 * ({@code unlockOnSkillLevel}, which gates BUYING) and from the quest gate ({@link FormQuestGateConfig}): this
 * gates USING (entering) a form the player already owns, keyed on DMZ's {@code StatsData.getLevel()}.
 *
 * <p>Stored in a sidecar rather than the DMZ form JSON because {@code FormFileManager.save} rewrites each group
 * through DMZ's own {@code FormConfig} serializer, which drops unknown fields; the same reason the form-combat,
 * aura, unlock-cost and quest-gate values all live in sdu sidecars. Persisted at
 * {@code config/sdu/form_level_gates.json}, keyed by the lower-cased {@code group.form} pair (e.g.
 * {@code godforms.supersaiyangod}, {@code ultimate.ultimate}) so a form name shared across two groups can carry
 * different minimums, and so a stack form (ultimate) keys the same way as a race form.
 *
 * <p>{@code config/sdu} is a shard-sync root, so this file travels between servers on its own; a per-key merge
 * entry in {@code ConfigMerge} keeps two admins editing different forms from clobbering each other. Synced to
 * clients on login and after an edit ({@link net.shurui.dev.sdu.network.FormLevelGateSyncPacket}) so the skills
 * GUI can show the requirement. A missing entry (or a value {@code <= 0}) means NO minimum, so nothing changes
 * until an operator sets one.
 */
public final class FormLevelGateConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;
    /** on-disk example block key; ignored when loading real gates */
    private static final String EXAMPLE_KEY = "_example";

    /** group.form (lower-cased) -> minimum character level to transform into it */
    private static final Map<String, Integer> BY_FORM = new LinkedHashMap<>();
    private static boolean loaded = false;

    private FormLevelGateConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("form_level_gates.json");
    }

    /** Minimum level for {@code group.form}, or 0 (no minimum). */
    public static int get(String group, String form) {
        return getByKey(key(group, form));
    }

    /** Minimum level for a pre-joined {@code group.form} key, or 0 (no minimum). */
    public static int getByKey(String formKey) {
        ensureLoaded();
        Integer v = BY_FORM.get(key(formKey));
        return v == null || v < 1 ? 0 : v;
    }

    /**
     * Highest minimum among every group whose form is named {@code formName}, or 0. Advisory only, for the
     * client skills tooltip, which knows a form's name but not always its group. Server enforcement always uses
     * the exact {@code group.form} key via {@link #get(String, String)}.
     */
    public static int requiredForFormName(String formName) {
        ensureLoaded();
        if (formName == null || formName.isBlank()) {
            return 0;
        }
        String suffix = "." + formName.trim().toLowerCase(Locale.ROOT);
        int best = 0;
        for (Map.Entry<String, Integer> e : BY_FORM.entrySet()) {
            if (e.getKey().endsWith(suffix) && e.getValue() != null && e.getValue() > best) {
                best = e.getValue();
            }
        }
        return best;
    }

    public static synchronized void load() {
        BY_FORM.clear();
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            writeExampleFile(path);
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null && root.has("gates") && root.get("gates").isJsonObject()) {
                JsonObject gates = root.getAsJsonObject("gates");
                for (String name : gates.keySet()) {
                    if (EXAMPLE_KEY.equals(name) || !gates.get(name).isJsonPrimitive()) {
                        continue;
                    }
                    int lvl = gates.get(name).getAsInt();
                    if (lvl >= 1) {
                        BY_FORM.put(key(name), lvl);
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded form-level-gate config: {} gated form(s).", DmzNpc.MODID, BY_FORM.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read form-level-gate config: {}", DmzNpc.MODID, e.toString());
        }
    }

    /** snapshot of every gate for server -> client sync */
    public static synchronized Map<String, Integer> all() {
        ensureLoaded();
        return new LinkedHashMap<>(BY_FORM);
    }

    /**
     * Drop every gate under {@code group} (keys {@code group.*}), so the form editor's save path can re-derive a
     * group's minimums from the forms it actually holds without leaving a stale entry for a deleted/cleared form.
     * Does not persist; caller batches and calls {@link #save()}. True if the map changed.
     */
    public static synchronized boolean removeGroupNoSave(String group) {
        ensureLoaded();
        if (group == null || group.isBlank()) {
            return false;
        }
        String prefix = group.trim().toLowerCase(Locale.ROOT) + ".";
        return BY_FORM.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Set {@code group.form}'s minimum (a value {@code < 1} removes it). Does not persist; caller runs
     * {@link #save()} once. True if the map changed. */
    public static synchronized boolean putNoSave(String group, String form, int minLevel) {
        ensureLoaded();
        String k = key(group, form);
        if (k.isEmpty() || k.indexOf('.') <= 0) {
            return false;
        }
        if (minLevel < 1) {
            return BY_FORM.remove(k) != null;
        }
        Integer prev = BY_FORM.put(k, minLevel);
        return prev == null || prev != minLevel;
    }

    /** persist to disk, keeping the self-documenting comment block */
    public static synchronized void save() {
        Path path = file();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("_comment", comment());
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject gates = new JsonObject();
            for (Map.Entry<String, Integer> e : BY_FORM.entrySet()) {
                gates.addProperty(e.getKey(), e.getValue());
            }
            root.add("gates", gates);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
            DmzNpc.LOGGER.info("[{}] Saved form-level-gate config: {} gated form(s).", DmzNpc.MODID, BY_FORM.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save form-level-gate config: {}", DmzNpc.MODID, e.toString());
        }
    }

    /** replace gates from a server sync (client-side; its own config has none) */
    public static synchronized void applySynced(Map<String, Integer> data) {
        BY_FORM.clear();
        loaded = true;
        if (data != null) {
            for (Map.Entry<String, Integer> e : data.entrySet()) {
                if (e.getValue() != null && e.getValue() >= 1) {
                    BY_FORM.put(key(e.getKey()), e.getValue());
                }
            }
        }
        DmzNpc.LOGGER.info("[{}] Applied synced form-level-gate config: {} gated form(s).", DmzNpc.MODID, BY_FORM.size());
    }

    /** write a starter file with a commented example (never loaded as a real gate) */
    private static void writeExampleFile(Path path) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("_comment", comment());
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject gates = new JsonObject();
            gates.addProperty(EXAMPLE_KEY, 100);
            root.add("gates", gates);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
            DmzNpc.LOGGER.info("[{}] Wrote example form-level-gate config to {}", DmzNpc.MODID, path);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to write example form-level-gate config: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static String comment() {
        return "Minimum character level to TRANSFORM INTO a form. Key each entry by the lower-cased "
                + "'group.form' pair (e.g. 'godforms.supersaiyangod', 'ultimate.ultimate'); the value is the "
                + "minimum DMZ character level. A player below it cannot transform into that form and is dropped "
                + "out of it if already in it. This gates USE, not buying (see form_quest_gates.json for buying). "
                + "Set from the form editor's 'Min Level To Use' field. Delete the '_example' entry (it is "
                + "ignored) and add real ones under 'gates'. A value of 0 or a missing entry means no minimum.";
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static String key(String group, String form) {
        String g = group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
        String f = form == null ? "" : form.trim().toLowerCase(Locale.ROOT);
        if (g.isEmpty() || f.isEmpty()) {
            return "";
        }
        return g + "." + f;
    }

    private static String key(String formKey) {
        return formKey == null ? "" : formKey.trim().toLowerCase(Locale.ROOT);
    }
}
