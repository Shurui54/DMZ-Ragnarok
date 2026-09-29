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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads/writes DMZ form groups directly in DMZ's config tree:
 * {@code config/dragonminez/races/<race>/forms/<group>.json} (per-race) and
 * {@code config/dragonminez/forms/<group>.json} (race-agnostic stack forms). After a write,
 * {@code ConfigManager.reload()} runs (via {@link DmzCompat}) so changes apply without a restart; clients
 * pick them up on rejoin.
 */
public final class FormFileManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private FormFileManager() {
    }

    private static Path dmzConfig() {
        return FMLPaths.CONFIGDIR.get().resolve("dragonminez");
    }

    private static Path fileFor(String ownerRace, String groupName) {
        Path base = dmzConfig();
        String group = net.shurui.dev.sdu.util.SduIds.sanitize(groupName);
        if (ownerRace == null || ownerRace.isBlank()) {
            return base.resolve("forms").resolve(group + ".json");
        }
        return base.resolve("races").resolve(net.shurui.dev.sdu.util.SduIds.sanitize(ownerRace))
                .resolve("forms").resolve(group + ".json");
    }

    /** All form groups DMZ currently has on disk: every race's {@code forms/} plus the stack forms. */
    public static List<FormGroupData> loadAll() {
        List<FormGroupData> result = new ArrayList<>();
        Path base = dmzConfig();
        Path racesDir = base.resolve("races");
        if (Files.isDirectory(racesDir)) {
            try (Stream<Path> races = Files.list(racesDir)) {
                for (Path raceDir : races.filter(Files::isDirectory).sorted().toList()) {
                    String race = raceDir.getFileName().toString();
                    readGroupsIn(raceDir.resolve("forms"), race, result);
                }
            } catch (Exception e) {
                DmzNpc.LOGGER.error("[{}] Failed to list race form folders: {}", DmzNpc.MODID, e.toString());
            }
        }
        // race-agnostic "stack" groups (empty owner)
        readGroupsIn(base.resolve("forms"), "", result);
        // attach combat tuning by form name, plus the per-form unlock TP cost read back from DMZ's gating
        for (FormGroupData group : result) {
            List<Integer> costs = readFormCosts(group.primaryOwner(), group.formType);
            for (Map.Entry<String, FormData> e : group.forms.entrySet()) {
                FormData form = e.getValue();
                form.combat = FormCombatConfig.get(form.name);
                form.extraAura = FormAuraConfig.get(form.name);
                int lvl = form.unlockOnSkillLevel;
                // resolve the current effective cost. no valid level or no entry: leave UNSET so a save
                // won't clobber it ("don't write what you didn't read"). -1 IS a real value, preserved.
                form.unlockCost = (lvl >= 1 && lvl <= costs.size()) ? costs.get(lvl - 1) : FormData.UNSET_COST;
                // minimum level to USE this form: sidecar, keyed group.form (the map KEY is the form id DMZ stores).
                form.minLevel = FormLevelGateConfig.get(group.groupName, e.getKey());
                // alignment unlock/use windows for this form: own sidecar, keyed group.form the same way.
                form.setAlignmentBounds(FormAlignmentGateConfig.get(group.groupName, e.getKey()));
            }
        }
        return result;
    }

    private static void readGroupsIn(Path formsDir, String ownerRace, List<FormGroupData> out) {
        if (!Files.isDirectory(formsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(formsDir)) {
            for (Path p : stream.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    JsonObject json = GSON.fromJson(Files.readString(p), JsonObject.class);
                    out.add(FormGroupData.fromGroupJson(ownerRace, json));
                } catch (Exception e) {
                    DmzNpc.LOGGER.error("[{}] Failed to read form group '{}': {}", DmzNpc.MODID, p, e.toString());
                }
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to list forms in {}: {}", DmzNpc.MODID, formsDir, e.toString());
        }
    }

    /** Write the group to each of its owner races' DMZ config files and reload. Null on success, else an error. */
    public static String save(FormGroupData group) {
        try {
            List<String> owners = new ArrayList<>(group.ownerRaces.isEmpty() ? List.of("") : dedup(group.ownerRaces));
            // A form group is EITHER a race-agnostic stack form (owner "") OR a per-race form, never both.
            // Writing to the stack folder AND a race's forms folder makes the SAME form show up twice: once in
            // the stack radial and once in that race's own form list (the "Ultra Ego duplicated as a stack form"
            // bug, from the editor's owner multi-select allowing "stack" to be ticked alongside races). When any
            // real race is targeted, drop the "" stack owner; cleanupStaleOwners then deletes a stale stack file
            // this group had written before, so an accidental duplicate self-heals on the next save.
            if (owners.stream().anyMatch(o -> o != null && !o.isBlank())) {
                owners.removeIf(o -> o == null || o.isBlank());
            }
            // register the formType as a form skill first, or DMZ's normalizeFormSkillKeys strips the price
            // entries on the next load. idempotent: no disk write for a DMZ default or an already-registered type.
            if (!group.ownerRaces.isEmpty() && group.formType != null && !group.formType.isBlank()) {
                FormTypeManager.ensureFormSkill(group.formType);
            }
            for (String owner : owners) {
                // one JSON per race so per-race colour overrides bake into that race's file
                String json = GSON.toJson(group.toGroupJson(owner));
                Path file = fileFor(owner, group.groupName);
                Files.createDirectories(file.getParent());
                Files.writeString(file, json);
                DmzNpc.LOGGER.info("[{}] Saved form group '{}' ({} forms) to {}",
                        DmzNpc.MODID, group.groupName, group.forms.size(), file);
                // gate the custom forms like DMZ's built-ins (stack forms have no race, so skipped)
                applyFormCosts(owner, group);
            }
            // drop this group's file from original owners no longer selected (or renamed away from), so
            // unchecking a race actually removes the form. scoped to this group's own original owners only.
            cleanupStaleOwners(group, owners);
            // addon-only combat tuning + extra aura layers go to our own config, never into DMZ's form JSON
            for (FormData form : group.forms.values()) {
                FormCombatConfig.put(form.name, form.combat);
                FormAuraConfig.put(form.name, form.extraAura);
            }
            FormCombatConfig.save();
            FormAuraConfig.save();
            // per-form minimum level to USE the form: own sidecar (DMZ's form JSON drops it), keyed group.form.
            // Re-derive this group's entries from scratch so clearing a form's minimum actually removes it.
            boolean levelsChanged = FormLevelGateConfig.removeGroupNoSave(group.groupName);
            for (Map.Entry<String, FormData> e : group.forms.entrySet()) {
                levelsChanged |= FormLevelGateConfig.putNoSave(group.groupName, e.getKey(), e.getValue().minLevel);
            }
            if (levelsChanged) {
                FormLevelGateConfig.save();
            }
            // alignment unlock/use windows: own sidecar, keyed group.form. Re-derived from scratch per group the
            // same way, so clearing a form's alignment fields actually removes its entry.
            boolean alignChanged = FormAlignmentGateConfig.removeGroupNoSave(group.groupName);
            for (Map.Entry<String, FormData> e : group.forms.entrySet()) {
                alignChanged |= FormAlignmentGateConfig.putNoSave(group.groupName, e.getKey(), e.getValue().toAlignmentBounds());
            }
            if (alignChanged) {
                FormAlignmentGateConfig.save();
            }
            // group is live again: un-tombstone name and type so a re-created group isn't stripped by the
            // SkillsRepairMixin / login scrub.
            FormTombstoneStore.removeFormGroup(net.shurui.dev.sdu.util.SduIds.sanitize(group.groupName));
            if (group.formType != null && !group.formType.isBlank()) {
                FormTombstoneStore.removeSkill(group.formType);
            }
            DmzCompat.reloadConfigs();
            return null;
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save form group '{}': {}", DmzNpc.MODID, group.groupName, e.toString());
            return e.getMessage();
        }
    }

    /**
     * Delete this group's file from each original owner race no longer current, and on a rename the
     * old-named file from all original owners. Scoped to this group's original owners so a same-named DMZ
     * group on unrelated races is never removed.
     */
    private static void cleanupStaleOwners(FormGroupData group, List<String> currentOwners) {
        boolean renamed = group.originalName != null && !group.originalName.isBlank()
                && !group.originalName.equals(group.groupName);
        java.util.Set<String> keep = new java.util.HashSet<>(currentOwners);
        for (String orig : group.originalOwners) {
            String o = orig == null ? "" : orig;
            if (renamed || !keep.contains(o)) {
                try {
                    String oldName = renamed ? group.originalName : group.groupName;
                    Path stale = fileFor(o, oldName);
                    if (Files.deleteIfExists(stale)) {
                        DmzNpc.LOGGER.info("[{}] Removed form group '{}' from {} (de-selected / renamed)",
                                DmzNpc.MODID, oldName, o.isBlank() ? "stack" : "race '" + o + "'");
                    }
                } catch (Exception e) {
                    DmzNpc.LOGGER.error("[{}] Failed to remove stale form file for '{}': {}",
                            DmzNpc.MODID, group.groupName, e.toString());
                }
            }
        }
        // on a rename the old name is gone from disk, so its UsedForms entries (race-agnostic key) are
        // orphaned: tombstone it. a plain de-select is NOT tombstoned: the name still lives on current
        // owner races, so its UsedForms must survive there.
        if (renamed) {
            FormTombstoneStore.addFormGroup(net.shurui.dev.sdu.util.SduIds.sanitize(group.originalName));
        }
    }

    public static void delete(String ownerRace, String groupName) {
        try {
            Files.deleteIfExists(fileFor(ownerRace, groupName));
            // tombstone the name so its UsedForms entries are stripped from offline players on login. the
            // online-player scrub runs in the packet handler (which holds the MinecraftServer). the formType
            // skill is left alone: other groups may still use it.
            FormTombstoneStore.addFormGroup(net.shurui.dev.sdu.util.SduIds.sanitize(groupName));
            DmzCompat.reloadConfigs();
            DmzNpc.LOGGER.info("[{}] Deleted form group '{}' (race '{}')", DmzNpc.MODID, groupName, ownerRace);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to delete form group '{}': {}", DmzNpc.MODID, groupName, e.toString());
        }
    }

    /** DMZ's config root ({@code config/dragonminez}); used by the form-type rename cascade. */
    public static Path dmzConfigDir() {
        return dmzConfig();
    }

    /**
     * Migrate a form-type's per-race cost block for a type RENAME: move
     * {@code formSkillsCosts.<oldType>} -> {@code formSkillsCosts.<newType>} in every race's
     * {@code character.json}, byte-preserving the block's shape, and DELETE the stale {@code <oldType>}
     * key ({@code applyFormCosts} writes a new block but never removes the old). Races with no
     * {@code <oldType>} entry untouched. Best-effort per race; logs and continues on a single-file failure.
     *
     * @return number of race files rewritten.
     */
    public static int migrateFormCostKey(String oldType, String newType) {
        String from = oldType == null ? "" : oldType.trim();
        String to = newType == null ? "" : newType.trim();
        int rewritten = 0;
        if (from.isEmpty() || to.isEmpty() || from.equals(to)) {
            return 0;
        }
        Path racesDir = dmzConfig().resolve("races");
        if (!Files.isDirectory(racesDir)) {
            return 0;
        }
        try (Stream<Path> races = Files.list(racesDir)) {
            for (Path raceDir : races.filter(Files::isDirectory).sorted().toList()) {
                Path file = raceDir.resolve("character.json");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    JsonObject character = GSON.fromJson(Files.readString(file), JsonObject.class);
                    if (character == null || !character.has("formSkillsCosts")
                            || !character.get("formSkillsCosts").isJsonObject()) {
                        continue;
                    }
                    JsonObject costs = character.getAsJsonObject("formSkillsCosts");
                    if (!costs.has(from)) {
                        continue;
                    }
                    // move the block verbatim ({buyFromMaster, prices} or legacy bare array), then drop old key
                    JsonElement block = costs.get(from).deepCopy();
                    costs.add(to, block);
                    costs.remove(from);
                    character.add("formSkillsCosts", costs);
                    Files.writeString(file, GSON.toJson(character));
                    rewritten++;
                    DmzNpc.LOGGER.info("[{}] Migrated formSkillsCosts '{}' -> '{}' in {}",
                            DmzNpc.MODID, from, to, file);
                } catch (Exception e) {
                    DmzNpc.LOGGER.error("[{}] Failed to migrate form costs in {}: {}",
                            DmzNpc.MODID, file, e.toString());
                }
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to list races for cost migration: {}", DmzNpc.MODID, e.toString());
        }
        return rewritten;
    }

    private static Path characterFile(String ownerRace) {
        return dmzConfig().resolve("races").resolve(net.shurui.dev.sdu.util.SduIds.sanitize(ownerRace)).resolve("character.json");
    }

    /**
     * The per-level unlock TP cost list DMZ currently gates {@code formType} with on {@code ownerRace}
     * (empty if none). From DMZ's live {@code ConfigManager}, which is authoritative: the race's on-disk
     * {@code formSkillsCosts[formType].prices} when a {@code character.json} exists, else DMZ's built-in
     * defaults (e.g. saiyan superforms = 13000,21000,...). This is what lets an untouched DMZ-default cost
     * round-trip unchanged on save. DMZ stores each entry as {@code {buyFromMaster, prices[]}}, not a bare
     * array, so we go through its parser ({@code getFormSkillTpCosts}), not the raw JSON.
     */
    private static List<Integer> readFormCosts(String ownerRace, String formType) {
        List<Integer> out = new ArrayList<>();
        if (formType == null || formType.isBlank()) {
            return out;
        }
        // a STACK group has no owner race and no formSkillsCosts block: its ladder is skills.json's
        // skills.<type>.costs, which ALSO caps the skill's max level. reading it here is half of what makes
        // a form added to such a group reachable; applyStackCosts is the other half.
        if (ownerRace == null || ownerRace.isBlank()) {
            return readStackCosts(formType);
        }
        try {
            Integer[] prices = com.dragonminez.common.config.ConfigManager
                    .getRaceCharacter(ownerRace).getFormSkillTpCosts(formType);
            if (prices != null) {
                for (Integer p : prices) {
                    out.add(p == null ? -1 : p);
                }
            }
        } catch (Throwable t) {
            // DMZ absent / not yet loaded (early boot): read the disk block so the editor shows a value
            // rather than defaulting everything to Priceless.
            DmzNpc.LOGGER.debug("[{}] Live form-cost read failed for race '{}' ({}); reading disk.",
                    DmzNpc.MODID, ownerRace, t.toString());
            out.addAll(readFormCostsFromDisk(ownerRace, formType));
        }
        return out;
    }

    private static Path skillsFile() {
        return dmzConfig().resolve("skills.json");
    }

    /**
     * The per-level TP ladder of a STACK form type, from {@code skills.<type>.costs} in skills.json.
     * Not just prices: {@code Skills.calculateMaxLevel} returns {@code min(costs.size(), 50)} for a non-form
     * skill, so its LENGTH is the skill's max level, and a form gated past the end can never be unlocked.
     * Live DMZ config first, disk fallback for early boot.
     */
    private static List<Integer> readStackCosts(String formType) {
        List<Integer> out = new ArrayList<>();
        try {
            var skills = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
            var entry = skills == null ? null : skills.getSkillCosts(formType);
            List<Integer> costs = entry == null ? null : entry.getCosts();
            if (costs != null) {
                for (Integer c : costs) {
                    out.add(c == null ? -1 : c);
                }
                return out;
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Live stack-cost read failed for '{}' ({}); reading disk.",
                    DmzNpc.MODID, formType, t.toString());
        }
        out.addAll(readStackCostsFromDisk(formType));
        return out;
    }

    private static List<Integer> readStackCostsFromDisk(String formType) {
        List<Integer> out = new ArrayList<>();
        Path file = skillsFile();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            JsonArray costs = stackCostsArray(root, formType);
            if (costs != null) {
                for (JsonElement e : costs) {
                    out.add(e.isJsonNull() ? -1 : e.getAsInt());
                }
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read stack costs for '{}': {}", DmzNpc.MODID, formType, e.toString());
        }
        return out;
    }

    /** The {@code skills.<type>.costs} array in a parsed skills.json, or null when the skill has no entry. */
    private static JsonArray stackCostsArray(JsonObject root, String formType) {
        if (root == null || !root.has("skills") || !root.get("skills").isJsonObject()) {
            return null;
        }
        JsonObject skills = root.getAsJsonObject("skills");
        if (!skills.has(formType) || !skills.get(formType).isJsonObject()) {
            return null;
        }
        JsonObject entry = skills.getAsJsonObject(formType);
        return entry.has("costs") && entry.get("costs").isJsonArray() ? entry.getAsJsonArray("costs") : null;
    }

    /** Disk fallback for {@code formSkillsCosts[formType]}, reading both the {@code {buyFromMaster, prices[]}}
     * block and the legacy bare array. Used only when DMZ's live config can't be queried. */
    private static List<Integer> readFormCostsFromDisk(String ownerRace, String formType) {
        List<Integer> out = new ArrayList<>();
        Path file = characterFile(ownerRace);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonObject character = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (character != null && character.has("formSkillsCosts") && character.get("formSkillsCosts").isJsonObject()) {
                JsonArray prices = pricesArray(character.getAsJsonObject("formSkillsCosts"), formType);
                if (prices != null) {
                    for (JsonElement e : prices) {
                        out.add(e.isJsonNull() ? -1 : e.getAsInt());
                    }
                }
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read form costs for race '{}': {}", DmzNpc.MODID, ownerRace, e.toString());
        }
        return out;
    }

    /** The {@code prices} array for {@code formType}, reading both the {@code {buyFromMaster, prices[]}} block
     * and a legacy bare array. {@code null} when the key is absent. */
    private static JsonArray pricesArray(JsonObject costs, String formType) {
        if (!costs.has(formType)) {
            return null;
        }
        JsonElement entry = costs.get(formType);
        if (entry.isJsonArray()) {
            return entry.getAsJsonArray();
        }
        if (entry.isJsonObject()) {
            JsonObject obj = entry.getAsJsonObject();
            if (obj.has("prices") && obj.get("prices").isJsonArray()) {
                return obj.getAsJsonArray("prices");
            }
        }
        return null;
    }

    /**
     * Merge this group's per-form unlock costs into the owning race's {@code character.json}
     * {@code formSkillsCosts[formType].prices}, each at index {@code level - 1}; the list grows only when a
     * form needs to write past the current end.
     *
     * <p>Don't write what you didn't read: a cost of {@link FormData#UNSET_COST} (never resolved, e.g. a
     * renamed DMZ default) is SKIPPED so the existing cost survives byte-identically. {@code -1} is DMZ's
     * real "not purchasable" and IS written. Growth fills gap slots with {@code -1} only to reach a level
     * being written at, never blanking an existing slot.
     *
     * <p>Kept in DMZ's {@code {buyFromMaster, prices[]}} shape (bare array only if the file already held
     * one): converting the block to a plain array was the bug that read every default back as Priceless.
     */
    private static void applyFormCosts(String ownerRace, FormGroupData group) {
        if (ownerRace == null || ownerRace.isBlank()) {
            // a stack group's ladder is in skills.json, not any race. this used to write nothing, so a form
            // added to default kaioken was never unlockable: the ladder stayed five long, calculateMaxLevel
            // capped the skill at five, and a sixth form gated on level six had no level to reach.
            applyStackCosts(group);
            return;
        }
        String formType = group.formType == null ? "" : group.formType.trim();
        if (formType.isEmpty()) {
            return;
        }
        Path file = characterFile(ownerRace);
        if (!Files.isRegularFile(file)) {
            return; // only touch races that already exist on disk
        }
        try {
            JsonObject character = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (character == null) {
                return;
            }
            JsonObject costs = character.has("formSkillsCosts") && character.get("formSkillsCosts").isJsonObject()
                    ? character.getAsJsonObject("formSkillsCosts") : new JsonObject();

            JsonArray existingPrices = pricesArray(costs, formType);
            List<Integer> arr = new ArrayList<>();
            if (existingPrices != null) {
                for (JsonElement e : existingPrices) {
                    arr.add(e.isJsonNull() ? -1 : e.getAsInt());
                }
            }
            int originalSize = arr.size();

            boolean changed = false;
            for (FormData form : group.forms.values()) {
                int lvl = form.unlockOnSkillLevel;
                // UNSET: leave whatever DMZ already gates it with. -1 is a real "not purchasable" value.
                if (form.unlockCost == FormData.UNSET_COST || lvl < 1) {
                    continue;
                }
                int cost = Math.max(form.unlockCost, -1);
                // grow only to reach this slot; gap levels default to -1 (locked), never blanking a set slot
                while (arr.size() < lvl) {
                    arr.add(-1);
                    changed = true;
                }
                if (!arr.get(lvl - 1).equals(cost)) {
                    arr.set(lvl - 1, cost);
                    changed = true;
                }
            }
            if (arr.size() > originalSize) {
                changed = true; // grew to raise the skill's max level
            }
            if (!changed) {
                return; // nothing resolved: leave DMZ's block as-is
            }
            JsonArray outArr = new JsonArray();
            for (int v : arr) {
                outArr.add(v);
            }
            // keep DMZ's block shape; bare array only if the disk already held one, else the save destroys
            // the structured entry and reads back as Priceless.
            JsonElement prior = costs.get(formType);
            if (prior != null && prior.isJsonArray()) {
                costs.add(formType, outArr);
            } else {
                JsonObject block = prior != null && prior.isJsonObject() ? prior.getAsJsonObject() : new JsonObject();
                // force buyFromMaster=false so any player with enough TP can buy from the skills tree (no
                // master-NPC gate). DMZ's master screen never read this flag, so master availability is unaffected.
                block.addProperty("buyFromMaster", false);
                block.add("prices", outArr);
                costs.add(formType, block);
            }
            character.add("formSkillsCosts", costs);
            Files.writeString(file, GSON.toJson(character));
            DmzNpc.LOGGER.info("[{}] Updated formSkillsCosts['{}'] for race '{}' -> {}",
                    DmzNpc.MODID, formType, ownerRace, outArr);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to write form costs for race '{}': {}", DmzNpc.MODID, ownerRace, e.toString());
        }
    }

    /**
     * Stack-group counterpart of {@link #applyFormCosts}: merges per-form unlock costs into
     * {@code skills.<formType>.costs} in skills.json.
     *
     * <p>A stack skill's ladder is its CEILING: {@code Skills.calculateMaxLevel} = {@code min(costs.size(), 50)}
     * for any skill outside {@code formSkills}, pushed onto every player by {@code refreshNonFormSkillMaxLevels}.
     * A form added to kaioken at unlock level 6 against the shipped five-entry ladder is gated on a level the
     * skill can never hold, and nothing reports it: the group loads, the form shows in the editor, in game the
     * node does nothing. Growing the ladder alongside the group is the fix.
     *
     * <p>Same discipline as the race path: {@link FormData#UNSET_COST} skipped (keeps {@code ultimate}'s
     * deliberate single {@code -1} intact), gap levels filled with {@code -1}, nothing written when nothing
     * resolved. Skill list MEMBERSHIP ({@code stackSkills} vs {@code formSkills}) is {@code FormTypeManager}'s
     * job and left alone; this only edits the cost block of an already-registered type.
     */
    private static void applyStackCosts(FormGroupData group) {
        String formType = group.formType == null ? "" : group.formType.trim();
        if (formType.isEmpty()) {
            return;
        }
        Path file = skillsFile();
        if (!Files.isRegularFile(file)) {
            return; // DMZ not installed / configs not generated yet
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null) {
                return;
            }
            JsonArray existing = stackCostsArray(root, formType);
            List<Integer> arr = new ArrayList<>();
            if (existing != null) {
                for (JsonElement e : existing) {
                    arr.add(e.isJsonNull() ? -1 : e.getAsInt());
                }
            }
            int originalSize = arr.size();

            boolean changed = false;
            for (FormData form : group.forms.values()) {
                int lvl = form.unlockOnSkillLevel;
                if (form.unlockCost == FormData.UNSET_COST || lvl < 1) {
                    continue;
                }
                int cost = Math.max(form.unlockCost, -1);
                while (arr.size() < lvl) {
                    arr.add(-1);
                    changed = true;
                }
                if (!arr.get(lvl - 1).equals(cost)) {
                    arr.set(lvl - 1, cost);
                    changed = true;
                }
            }
            if (arr.size() > originalSize) {
                changed = true; // the ladder grew, raising the skill's max level
            }
            if (!changed) {
                return;
            }
            JsonArray outArr = new JsonArray();
            for (int v : arr) {
                outArr.add(v);
            }
            JsonObject skills = root.has("skills") && root.get("skills").isJsonObject()
                    ? root.getAsJsonObject("skills") : new JsonObject();
            JsonObject entry = skills.has(formType) && skills.get(formType).isJsonObject()
                    ? skills.getAsJsonObject(formType) : new JsonObject();
            entry.add("costs", outArr);
            if (!entry.has("allowedRaces") || !entry.get("allowedRaces").isJsonArray()) {
                // DMZ reads an empty list as "every race". only added when missing, so a hand-restricted
                // skill keeps its restriction.
                entry.add("allowedRaces", new JsonArray());
            }
            skills.add(formType, entry);
            root.add("skills", skills);
            Files.writeString(file, GSON.toJson(root));
            DmzCompat.reloadConfigs();
            DmzNpc.LOGGER.info("[{}] Updated skills['{}'].costs (stack ladder, max level {}) -> {}",
                    DmzNpc.MODID, formType, arr.size(), outArr);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to write stack costs for '{}': {}", DmzNpc.MODID, formType, e.toString());
        }
    }

    private static List<String> dedup(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            String v = s == null ? "" : s;
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }
}
