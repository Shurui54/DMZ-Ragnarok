package net.shurui.dev.sdu.form;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Skill keys and form-group names the editor has DELETED, in {@code config/sdu/form_tombstones.json}, so
 * deleted forms/types don't resurface as phantom skills.
 *
 * <p>DMZ keeps every unlocked skill (regular AND form/stack) in one flat per-player map; a custom form type
 * is just another key. On load {@code Skills.repairSkillNames()} FUZZY-MIGRATES any key not in the current
 * config onto the closest surviving skill (alias, then Levenshtein &ge; 0.8), so a form type removed from
 * {@code skills.json} gets resurrected for a player who had unlocked it. Per-form usage lives separately in
 * {@code UsedForms} ({@code formGroup -> [formNames]}); deleting a group leaves stale entries there too.
 *
 * <p>Records ONLY names the addon removed (never generic unknown keys, which may belong to other mods).
 * {@link net.shurui.dev.sdu.mixin.SkillsRepairMixin} reads {@link #skills()} at {@code repairSkillNames}
 * HEAD to strip them before the fuzzy repair; the scrub paths read {@link #formGroups()} to purge
 * {@code UsedForms}. Re-creating a type/group with the same name clears its tombstone.
 *
 * <p>Loaded on {@code ServerAboutToStartEvent} (early enough for the mixin). All reads return an immutable
 * snapshot so the mixin, which may run off the server thread during data load, sees a thread-safe view.
 */
public final class FormTombstoneStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    /** Removed form-type SKILL keys (DMZ lowercases skill map keys, so we store them lowercase). */
    private static final Set<String> SKILLS = new LinkedHashSet<>();
    /** Removed form-group names (the key DMZ uses in {@code UsedForms}). */
    private static final Set<String> GROUPS = new LinkedHashSet<>();
    private static volatile boolean loaded = false;

    private FormTombstoneStore() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("form_tombstones.json");
    }

    /** Immutable snapshot of tombstoned skill keys (lowercase). Safe to read off the server thread. */
    public static synchronized Set<String> skills() {
        ensureLoaded();
        return Collections.unmodifiableSet(new LinkedHashSet<>(SKILLS));
    }

    /** Immutable snapshot of tombstoned form-group names. Safe to read off the server thread. */
    public static synchronized Set<String> formGroups() {
        ensureLoaded();
        return Collections.unmodifiableSet(new LinkedHashSet<>(GROUPS));
    }

    /** True if this exact (lowercased) skill key has been tombstoned. */
    public static synchronized boolean isSkillTombstoned(String skillKey) {
        ensureLoaded();
        return skillKey != null && SKILLS.contains(skillKey.toLowerCase());
    }

    /** Record a removed form-type skill key. Persists immediately. */
    public static synchronized void addSkill(String skillKey) {
        ensureLoaded();
        String k = key(skillKey);
        if (!k.isEmpty() && SKILLS.add(k)) {
            save();
        }
    }

    /** Record a removed form-group name. Persists immediately. */
    public static synchronized void addFormGroup(String groupName) {
        ensureLoaded();
        String g = trim(groupName);
        if (!g.isEmpty() && GROUPS.add(g)) {
            save();
        }
    }

    /** Un-tombstone a skill key that has just been (re-)created via the editor. Persists if it changed. */
    public static synchronized void removeSkill(String skillKey) {
        ensureLoaded();
        if (SKILLS.remove(key(skillKey))) {
            save();
        }
    }

    /** Un-tombstone a form-group name that has just been (re-)created via the editor. Persists if it changed. */
    public static synchronized void removeFormGroup(String groupName) {
        ensureLoaded();
        if (GROUPS.remove(trim(groupName))) {
            save();
        }
    }

    public static synchronized void load() {
        SKILLS.clear();
        GROUPS.clear();
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null) {
                readInto(root, "skills", SKILLS, true);
                readInto(root, "formGroups", GROUPS, false);
            }
            DmzNpc.LOGGER.info("[{}] Loaded form tombstones: {} skill(s), {} group(s).",
                    DmzNpc.MODID, SKILLS.size(), GROUPS.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read form tombstones: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            root.add("skills", toArray(SKILLS));
            root.add("formGroups", toArray(GROUPS));
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
            DmzNpc.LOGGER.info("[{}] Saved form tombstones ({} skill(s), {} group(s)).",
                    DmzNpc.MODID, SKILLS.size(), GROUPS.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save form tombstones: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static void readInto(JsonObject root, String key, Set<String> out, boolean lower) {
        if (root.has(key) && root.get(key).isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray(key)) {
                String v = e.isJsonNull() ? "" : (lower ? key(e.getAsString()) : trim(e.getAsString()));
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        }
    }

    private static JsonArray toArray(Set<String> in) {
        JsonArray arr = new JsonArray();
        for (String s : in) {
            arr.add(s);
        }
        return arr;
    }

    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
