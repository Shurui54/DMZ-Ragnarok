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
 * Quest-gated form purchases: maps a DMZ form-skill name to a quest id that must be COMPLETED before the
 * player may spend TP on that form. Gate only, the form keeps its normal TP price; it does not grant the
 * form free like DMZ's native quest-reward forms.
 *
 * <p>Persisted at {@code config/sdu/form_quest_gates.json}, keyed by lower-cased form-skill name. Each entry
 * is {@code { questId, gateUpgrades }}: {@code questId} is {@code "<sagaId>:<questNumericId>"} for saga
 * quests, a string id for side quests; {@code gateUpgrades} false gates only the first buy (0 -&gt; 1), true
 * also gates every upgrade while the quest is incomplete.
 *
 * <p>Server reads it for the gate ({@code UpdateSkillC2SMixin}), synced to clients for advisory UX
 * ({@code SkillsMenuScreenMixin}). First run writes a commented {@code _example}, ignored by the loader.
 */
public final class FormQuestGateConfig {

    /** one form-skill's gate: the quest that unlocks buying, + whether upgrades are gated too */
    public static final class Gate {
        public final String questId;
        public final boolean gateUpgrades;

        public Gate(String questId, boolean gateUpgrades) {
            this.questId = questId == null ? "" : questId.trim();
            this.gateUpgrades = gateUpgrades;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("questId", questId);
            o.addProperty("gateUpgrades", gateUpgrades);
            return o;
        }

        static Gate fromJson(JsonObject o) {
            String qid = o.has("questId") && !o.get("questId").isJsonNull() ? o.get("questId").getAsString() : "";
            boolean gu = o.has("gateUpgrades") && !o.get("gateUpgrades").isJsonNull() && o.get("gateUpgrades").getAsBoolean();
            return new Gate(qid, gu);
        }

        public boolean isValid() {
            return questId != null && !questId.isBlank();
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;
    /** on-disk example block key; ignored when loading real gates */
    private static final String EXAMPLE_KEY = "_example";

    private static final Map<String, Gate> BY_FORM = new LinkedHashMap<>();
    private static boolean loaded = false;

    private FormQuestGateConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("form_quest_gates.json");
    }

    /**
     * The gate under a form key, or null. The key is the editor's {@code group.form} value (e.g.
     * {@code superforms.super_saiyan}), NOT a bare form-skill name: "is this skill gated at this level" must
     * go through {@link FormQuestGate}'s resolver, not this raw lookup.
     */
    public static Gate get(String formKey) {
        ensureLoaded();
        return BY_FORM.get(key(formKey));
    }

    public static boolean isGated(String formKey) {
        ensureLoaded();
        return BY_FORM.containsKey(key(formKey));
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
                    if (EXAMPLE_KEY.equals(name) || !gates.get(name).isJsonObject()) {
                        continue;
                    }
                    Gate gate = Gate.fromJson(gates.getAsJsonObject(name));
                    if (gate.isValid()) {
                        BY_FORM.put(key(name), gate);
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded form-quest-gate config: {} gated form(s).", DmzNpc.MODID, BY_FORM.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read form-quest-gate config: {}", DmzNpc.MODID, e.toString());
        }
    }

    /** snapshot of every gate for server -> client sync */
    public static synchronized Map<String, Gate> all() {
        ensureLoaded();
        return new LinkedHashMap<>(BY_FORM);
    }

    /**
     * Add or replace one form-skill's gate and persist. Only that entry is touched. {@code gateUpgrades} is
     * kept from an existing entry so re-saving a saga doesn't clobber a manual upgrade gate; new entries
     * default false. True if the map changed (worth resyncing).
     */
    public static synchronized boolean upsert(String formSkill, String questId) {
        ensureLoaded();
        String k = key(formSkill);
        if (k.isEmpty() || questId == null || questId.isBlank()) {
            return false;
        }
        Gate existing = BY_FORM.get(k);
        boolean gateUpgrades = existing != null && existing.gateUpgrades;
        Gate next = new Gate(questId.trim(), gateUpgrades);
        if (existing != null && existing.questId.equals(next.questId) && existing.gateUpgrades == next.gateUpgrades) {
            return false; // no change
        }
        BY_FORM.put(k, next);
        save();
        return true;
    }

    /** Like {@link #upsert(String, String)} but doesn't persist; caller batches and calls {@link #save()} once. True if changed. */
    public static synchronized boolean upsertNoSave(String formKey, String questId) {
        ensureLoaded();
        String k = key(formKey);
        if (k.isEmpty() || questId == null || questId.isBlank()) {
            return false;
        }
        Gate existing = BY_FORM.get(k);
        boolean gateUpgrades = existing != null && existing.gateUpgrades;
        Gate next = new Gate(questId.trim(), gateUpgrades);
        if (existing != null && existing.questId.equals(next.questId) && existing.gateUpgrades == next.gateUpgrades) {
            return false; // no change
        }
        BY_FORM.put(k, next);
        return true;
    }

    /** Remove every gate for {@code questId}, so the save path can prune a saga's stale gates before
     * re-deriving from current FORM_PURCHASE rewards. Doesn't persist; caller batches + {@link #save()}. */
    public static synchronized boolean removeByQuest(String questId) {
        ensureLoaded();
        if (questId == null || questId.isBlank()) {
            return false;
        }
        String q = questId.trim();
        return BY_FORM.values().removeIf(g -> g != null && q.equals(g.questId));
    }

    /**
     * Drop every gate whose quest id {@code resolves} reports missing (returns false), plus any invalid/blank
     * entry. Used after a saga or side quest is deleted so no gate is left pointing at a quest that can never be
     * completed. Doesn't persist; caller runs {@link #save()} and resyncs when this returns true.
     */
    public static synchronized boolean removeUnresolved(java.util.function.Predicate<String> resolves) {
        ensureLoaded();
        if (resolves == null) {
            return false;
        }
        return BY_FORM.values().removeIf(g -> g == null || !g.isValid() || !resolves.test(g.questId));
    }

    /** persist to disk, keeping the self-documenting comment block */
    public static synchronized void save() {
        Path path = file();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("_comment", "Quest-gated form purchases. Key each entry by the lower-cased DMZ "
                    + "form-skill name (as in getFormSkills, e.g. 'superform'). questId: saga quests are "
                    + "'<sagaId>:<questNumericId>', side quests use their string id. gateUpgrades=false gates "
                    + "only the first buy (level 0->1); true also gates every upgrade while the quest is "
                    + "incomplete. The form keeps its normal TP price - the quest only unlocks the ability to "
                    + "buy. Entries authored in the saga editor (FORM_PURCHASE reward) are upserted here on "
                    + "save; hand-authored entries are preserved.");
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject gates = new JsonObject();
            for (Map.Entry<String, Gate> e : BY_FORM.entrySet()) {
                gates.add(e.getKey(), e.getValue().toJson());
            }
            root.add("gates", gates);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
            DmzNpc.LOGGER.info("[{}] Saved form-quest-gate config: {} gated form(s).", DmzNpc.MODID, BY_FORM.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save form-quest-gate config: {}", DmzNpc.MODID, e.toString());
        }
    }

    /** replace gates from a server sync (client-side; its own config has none). enables SkillsMenuScreenMixin UX. */
    public static synchronized void applySynced(Map<String, Gate> data) {
        BY_FORM.clear();
        loaded = true;
        if (data != null) {
            for (Map.Entry<String, Gate> e : data.entrySet()) {
                if (e.getValue() != null && e.getValue().isValid()) {
                    BY_FORM.put(key(e.getKey()), e.getValue());
                }
            }
        }
        DmzNpc.LOGGER.info("[{}] Applied synced form-quest-gate config: {} gated form(s).", DmzNpc.MODID, BY_FORM.size());
    }

    /** write a starter file with a commented example (never loaded as a real gate) */
    private static void writeExampleFile(Path path) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("_comment", "Quest-gated form purchases. Key each entry by the lower-cased DMZ "
                    + "form-skill name (as in getFormSkills, e.g. 'superform'). questId: saga quests are "
                    + "'<sagaId>:<questNumericId>', side quests use their string id. gateUpgrades=false gates "
                    + "only the first buy (level 0->1); true also gates every upgrade while the quest is "
                    + "incomplete. The form keeps its normal TP price - the quest only unlocks the ability to "
                    + "buy. Delete the '_example' entry (it is ignored) and add real ones under 'gates'.");
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject gates = new JsonObject();
            gates.add(EXAMPLE_KEY, new Gate("saga_frieza:3", false).toJson());
            root.add("gates", gates);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
            DmzNpc.LOGGER.info("[{}] Wrote example form-quest-gate config to {}", DmzNpc.MODID, path);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to write example form-quest-gate config: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static String key(String formSkill) {
        return formSkill == null ? "" : formSkill.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
