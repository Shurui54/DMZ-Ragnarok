package net.shurui.dev.sdu.form;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzCompat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Registers/unregisters custom form types by editing DMZ's {@code skills.json} {@code formSkills} list.
 * A form type is really a form skill: DMZ treats any name in that list as a levelable transformation skill
 * (skills menu, per-level costs from each race's {@code formSkillsCosts}). Adding a name makes a new
 * {@code formType} unlockable alongside DMZ's built-ins.
 */
public final class FormTypeManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** DMZ's built-in form skills (race transformations), never removable. */
    public static final List<String> DEFAULTS =
            List.of("superforms", "legendaryforms", "godforms", "androidforms", "kaioken");
    /** DMZ's built-in stack skills (e.g. Kaioken), never removable. */
    public static final List<String> STACK_DEFAULTS = List.of("kaioken", "ultimate");

    /**
     * Canonical DMZ default cost blocks for STACK_DEFAULTS ({@link #healStackDefaults()}). kaioken has five
     * TP-gated levels; ultimate is a single {@code -1} always-available sentinel.
     */
    private static final java.util.Map<String, List<Integer>> STACK_DEFAULT_COSTS = java.util.Map.of(
            "kaioken", List.of(1000, 1500, 2500, 4000, 7500),
            "ultimate", List.of(-1));

    private FormTypeManager() {
    }

    private static Path skillsFile() {
        return FMLPaths.CONFIGDIR.get().resolve("dragonminez").resolve("skills.json");
    }

    /** {@link #add(String)} plus store presentation {@code meta} (stock icon + tint) in
     * {@code sdu_formtype_meta.json}: skills.json makes it unlockable, the meta makes the icon render. */
    public static String add(String type, FormTypeMeta meta) {
        String err = add(type);
        if (err == null && meta != null) {
            FormTypeMetaConfig.put(type, meta);
        }
        return err;
    }

    /** {@link #addStack(String, List)} plus persist the type's presentation {@code meta}. */
    public static String addStack(String type, List<Integer> costs, FormTypeMeta meta) {
        String err = addStack(type, costs);
        if (err == null && meta != null) {
            FormTypeMetaConfig.put(type, meta);
        }
        return err;
    }

    /** Register a form type: a name in {@code formSkills}, unlocked per-race via {@code formSkillsCosts}. */
    public static String add(String type) {
        String t = net.shurui.dev.sdu.util.SduIds.sanitize(type);
        if (t.isEmpty()) {
            return "empty form type name";
        }
        // kaioken/ultimate are STACK defaults: they must stay in stackSkills (with their skills.<id> cost
        // block) or SkillsMenuScreen.buildFormsTree drops them from the purchase menu. converting one to a
        // plain form skill corrupts them, so re-route to the stack path preserving existing costs.
        if (STACK_DEFAULTS.contains(t)) {
            return addStack(t, existingStackCosts(t));
        }
        String err = editRoot(root -> {
            addToList(root, "formSkills", t);
            removeFromList(root, "stackSkills", t);   // a type is either a form skill or a stack skill
            removeSkillEntry(root, t);
        });
        if (err == null) {
            FormTombstoneStore.removeSkill(t);
            // track as custom so the routing mixins gate against its own skill, not a substring-matched stock one
            CustomFormTypes.register(t);
        }
        return err;
    }

    /**
     * Idempotently ensure {@code type} is a {@code formSkills} entry. Returns null without touching disk when
     * already present (so a plain save doesn't reload configs), else delegates to {@link #add(String)}. Keeps
     * DMZ's {@code normalizeFormSkillKeys} from stripping a free-text {@code formType}'s price entries.
     */
    public static String ensureFormSkill(String type) {
        String t = net.shurui.dev.sdu.util.SduIds.sanitize(type);
        if (t.isEmpty()) {
            return "empty form type name";
        }
        if (hasFormSkill(t)) {
            return null; // already registered; nothing to write
        }
        return add(t);
    }

    private static boolean hasFormSkill(String type) {
        String lower = type.toLowerCase(java.util.Locale.ROOT);
        try {
            Path file = skillsFile();
            if (!Files.isRegularFile(file)) {
                return false;
            }
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null || !root.has("formSkills") || !root.get("formSkills").isJsonArray()) {
                return false;
            }
            for (JsonElement e : root.getAsJsonArray("formSkills")) {
                if (lower.equals(e.getAsString().toLowerCase(java.util.Locale.ROOT))) {
                    return true;
                }
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read formSkills for '{}': {}", DmzNpc.MODID, type, e.toString());
        }
        return false;
    }

    /**
     * Register a stack form type: a name in {@code stackSkills} PLUS a {@code skills} entry
     * ({@code {"costs":[...], "allowedRaces":[]}}). A race-agnostic form group with this {@code formType}
     * then holds stack forms unlocked once the player buys the skill to the form's Unlock Skill Lv.
     * {@code costs} length IS the skill's max level (empty => one 1000-TP level).
     */
    public static String addStack(String type, List<Integer> costs) {
        String t = net.shurui.dev.sdu.util.SduIds.sanitize(type);
        if (t.isEmpty()) {
            return "empty form type name";
        }
        String err = editRoot(root -> {
            addToList(root, "stackSkills", t);
            removeFromList(root, "formSkills", t);
            JsonObject skills = obj(root, "skills");
            JsonObject entry = new JsonObject();
            JsonArray c = new JsonArray();
            List<Integer> cc = (costs == null || costs.isEmpty()) ? List.of(1000) : costs;
            for (int v : cc) {
                // keep the -1 sentinel (DMZ reads it as always-available, used by ultimate); floor other negatives to 0
                c.add(v == -1 ? -1 : Math.max(0, v));
            }
            entry.add("costs", c);
            entry.add("allowedRaces", new JsonArray());
            skills.add(t, entry);
        });
        if (err == null) {
            FormTombstoneStore.removeSkill(t);
            CustomFormTypes.register(t);
        }
        return err;
    }

    /** Remove a custom {@code type} (form or stack). Refuses to remove a DMZ default. */
    public static String remove(String type) {
        String t = net.shurui.dev.sdu.util.SduIds.sanitize(type);
        if (DEFAULTS.contains(t) || STACK_DEFAULTS.contains(t)) {
            return "cannot remove a default form type";
        }
        String err = editRoot(root -> {
            removeFromList(root, "formSkills", t);
            removeFromList(root, "stackSkills", t);
            removeSkillEntry(root, t);
        });
        if (err == null) {
            // persist the removed key so DMZ's fuzzy repairSkillNames can't resurrect it for an offline player
            // who had unlocked it (SkillsRepairMixin reads this). online-player scrub is in the packet handler.
            FormTombstoneStore.addSkill(t);
            FormTypeMetaConfig.remove(t);
            CustomFormTypes.unregister(t);
        }
        return err;
    }

    /** Seed {@link CustomFormTypes} from DMZ's live skills config, so custom types from a prior session
     * route correctly on startup before anyone edits them. */
    public static void refreshCustomTypesFromDmz() {
        try {
            var skills = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
            if (skills != null) {
                CustomFormTypes.seed(skills.getFormSkills(), skills.getStackSkills());
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not seed custom form types from DMZ skills config: {}",
                    DmzNpc.MODID, t.toString());
        }
    }

    /**
     * Self-heal stack defaults corrupted by an older sdu build (un-stacking kaioken/ultimate stripped them
     * from {@code stackSkills} and deleted their cost block, so {@code buildFormsTree} dropped them from the
     * purchase menu). For each STACK_DEFAULT missing from {@code stackSkills} or lacking a {@code skills.<id>}
     * entry, re-add it with its canonical cost block ({@link #STACK_DEFAULT_COSTS}) and drop bogus
     * {@code formSkills} membership. Never clobbers a present/customized entry. Runs at boot before players join.
     */
    public static void healStackDefaults() {
        try {
            Path file = skillsFile();
            if (!Files.isRegularFile(file)) {
                return; // DMZ not installed / configs not generated yet
            }
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null) {
                return;
            }
            java.util.List<String> restored = new java.util.ArrayList<>();
            for (String id : STACK_DEFAULTS) {
                boolean inStack = inList(root, "stackSkills", id);
                boolean hasEntry = root.has("skills") && root.get("skills").isJsonObject()
                        && root.getAsJsonObject("skills").has(id);
                if (inStack && hasEntry) {
                    continue; // present (default or customized); leave alone
                }
                // restore only what's missing; keep an existing cost block if just membership was lost
                addToList(root, "stackSkills", id);
                removeFromList(root, "formSkills", id); // undo bogus form-skill membership from the un-stack
                if (!hasEntry) {
                    JsonObject skills = obj(root, "skills");
                    JsonObject entry = new JsonObject();
                    JsonArray c = new JsonArray();
                    for (int v : STACK_DEFAULT_COSTS.getOrDefault(id, List.of(1000))) {
                        c.add(v);
                    }
                    entry.add("costs", c);
                    entry.add("allowedRaces", new JsonArray());
                    skills.add(id, entry);
                }
                // un-tombstone / re-register so the mixins don't strip it back out
                FormTombstoneStore.removeSkill(id);
                CustomFormTypes.register(id);
                restored.add(id);
            }
            if (!restored.isEmpty()) {
                Files.writeString(file, GSON.toJson(root));
                DmzCompat.reloadConfigs();
                DmzNpc.LOGGER.warn("[{}] Restored corrupted stack default(s) {} to skills.json "
                        + "(re-added to stackSkills with default costs).", DmzNpc.MODID, restored);
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Stack-default self-heal failed: {}", DmzNpc.MODID, e.toString());
        }
    }

    /** On-disk cost block of stack skill {@code id}, or null when absent. Used by {@link #add(String)} when it
     * re-routes a stack default, so re-registering kaioken/ultimate keeps their costs. */
    private static List<Integer> existingStackCosts(String id) {
        try {
            Path file = skillsFile();
            if (!Files.isRegularFile(file)) {
                return null;
            }
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null || !root.has("skills") || !root.get("skills").isJsonObject()) {
                return null;
            }
            JsonObject skills = root.getAsJsonObject("skills");
            if (!skills.has(id) || !skills.get(id).isJsonObject()) {
                return null;
            }
            JsonObject entry = skills.getAsJsonObject(id);
            if (!entry.has("costs") || !entry.get("costs").isJsonArray()) {
                return null;
            }
            List<Integer> out = new java.util.ArrayList<>();
            for (JsonElement e : entry.getAsJsonArray("costs")) {
                out.add(e.getAsInt());
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Which skills.json list holds {@code type}: {@code "formSkills"}, {@code "stackSkills"}, or null. Reads
     * the live file so it matches the on-disk membership the rename cascade must preserve. */
    public static String listContaining(String type) {
        String t = net.shurui.dev.sdu.util.SduIds.sanitize(type);
        if (t.isEmpty()) {
            return null;
        }
        try {
            Path file = skillsFile();
            if (!Files.isRegularFile(file)) {
                return null;
            }
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null) {
                return null;
            }
            if (inList(root, "formSkills", t)) {
                return "formSkills";
            }
            if (inList(root, "stackSkills", t)) {
                return "stackSkills";
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read skills.json membership for '{}': {}",
                    DmzNpc.MODID, type, e.toString());
        }
        return null;
    }

    public static boolean hasSkillEntry(String type) {
        String t = net.shurui.dev.sdu.util.SduIds.sanitize(type);
        try {
            Path file = skillsFile();
            if (!Files.isRegularFile(file)) {
                return false;
            }
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            return root != null && root.has("skills") && root.get("skills").isJsonObject()
                    && root.getAsJsonObject("skills").has(t);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean inList(JsonObject root, String key, String value) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (JsonElement e : root.getAsJsonArray(key)) {
            if (lower.equals(e.getAsString().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rename a form-type skill key, keeping list membership and (for stack skills) the cost block. Callers
     * pass the list {@code oldId} was in (from {@link #listContaining}) rather than re-deriving it, so the
     * rename is atomic against the snapshot the validation used. Null on success else an error.
     */
    public static String renameInSkills(String oldId, String newId, boolean stack) {
        String from = net.shurui.dev.sdu.util.SduIds.sanitize(oldId);
        String to = net.shurui.dev.sdu.util.SduIds.sanitize(newId);
        if (from.isEmpty() || to.isEmpty()) {
            return "empty form type name";
        }
        String listKey = stack ? "stackSkills" : "formSkills";
        String err = editRoot(root -> {
            addToList(root, listKey, to);
            if (stack && root.has("skills") && root.get("skills").isJsonObject()) {
                JsonObject skills = root.getAsJsonObject("skills");
                if (skills.has(from)) {
                    skills.add(to, skills.get(from).deepCopy());
                }
            }
            removeFromList(root, listKey, from);
            removeSkillEntry(root, from);
        });
        if (err == null) {
            // keep the tombstone/custom-type sets consistent with the new key
            FormTombstoneStore.removeSkill(to);
            CustomFormTypes.register(to);
        }
        return err;
    }

    private static String editRoot(Consumer<JsonObject> mutation) {
        try {
            Path file = skillsFile();
            if (!Files.isRegularFile(file)) {
                return "skills.json not found (is DragonMineZ installed / configs generated?)";
            }
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null) {
                return "skills.json is unreadable";
            }
            mutation.accept(root);
            Files.writeString(file, GSON.toJson(root));
            DmzCompat.reloadConfigs();
            DmzNpc.LOGGER.info("[{}] Updated skills.json form/stack types.", DmzNpc.MODID);
            return null;
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to edit form types: {}", DmzNpc.MODID, e.toString());
            return e.getMessage();
        }
    }

    private static void addToList(JsonObject root, String key, String value) {
        JsonArray arr = root.has(key) && root.get(key).isJsonArray() ? root.getAsJsonArray(key) : new JsonArray();
        for (JsonElement e : arr) {
            if (value.equals(e.getAsString())) {
                root.add(key, arr);
                return;
            }
        }
        arr.add(value);
        root.add(key, arr);
    }

    private static void removeFromList(JsonObject root, String key, String value) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return;
        }
        JsonArray out = new JsonArray();
        for (JsonElement e : root.getAsJsonArray(key)) {
            if (!value.equals(e.getAsString())) {
                out.add(e);
            }
        }
        root.add(key, out);
    }

    private static void removeSkillEntry(JsonObject root, String name) {
        if (root.has("skills") && root.get("skills").isJsonObject()) {
            root.getAsJsonObject("skills").remove(name);
        }
    }

    private static JsonObject obj(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonObject()) {
            return root.getAsJsonObject(key);
        }
        JsonObject o = new JsonObject();
        root.add(key, o);
        return o;
    }
}
