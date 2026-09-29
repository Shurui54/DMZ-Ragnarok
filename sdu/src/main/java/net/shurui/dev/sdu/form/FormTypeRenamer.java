package net.shurui.dev.sdu.form;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ProgressionSyncS2C;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.skills.Skill;
import com.dragonminez.common.stats.skills.Skills;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzCompat;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.lang.GeneratedLangStore;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.util.SduIds;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-side cascade that renames a custom form-type id everywhere it is stored, keyed by
 * {@link SduIds#sanitize}, preserving online players' unlocks. The numbered steps below run in a fixed
 * order so DMZ's substring routing never mis-gates a group mid-rename ({@code newId} registered FIRST,
 * {@code oldId} unregistered LAST).
 *
 * <p><b>Offline players:</b> DMZ exposes no per-player offline migration hook (only its fuzzy repair,
 * which we suppress via tombstones), so {@code oldId} is tombstoned and {@code SkillsRepairMixin} strips
 * the orphaned key on their next login. The caller is told those players may need a re-grant. Online
 * players (the priority) keep their unlock under {@code newId}.
 */
public final class FormTypeRenamer {

    private FormTypeRenamer() {
    }

    /** Outcome of a rename attempt: {@code ok} plus a player-facing localised {@code message}. */
    public record Result(boolean ok, Component message) {
    }

    private static Result fail(Component reason) {
        return new Result(false, Component.translatable("message.dmz_ragnarok.npc.formtype.rename.failed", reason));
    }

    public static Result rename(MinecraftServer server, String oldIdRaw, String newIdRaw) {
        String oldId = SduIds.sanitize(oldIdRaw);
        String newId = SduIds.sanitize(newIdRaw);

        // 1) Validate & reject BEFORE touching anything. sanitize() never returns empty (it falls back to
        //    "npc"), so a blank raw id is the real "invalid" case to catch.
        if (newIdRaw == null || newIdRaw.isBlank()) {
            return fail(Component.translatable("message.dmz_ragnarok.npc.formtype.rename.new_blank"));
        }
        if (oldIdRaw == null || oldIdRaw.isBlank()) {
            return fail(Component.translatable("message.dmz_ragnarok.npc.formtype.rename.old_blank"));
        }
        if (oldId.equals(newId)) {
            return new Result(true, Component.translatable("message.dmz_ragnarok.npc.formtype.rename.unchanged"));
        }
        if (isDefault(oldId)) {
            return fail(Component.translatable("message.dmz_ragnarok.npc.formtype.rename.old_default", oldId));
        }
        // Collisions: stock ids, an existing skills.json list entry, existing meta, or the custom-type set.
        if (isDefault(newId)) {
            return fail(Component.translatable("message.dmz_ragnarok.npc.formtype.rename.new_reserved", newId));
        }
        if (FormTypeManager.listContaining(newId) != null || FormTypeManager.hasSkillEntry(newId)) {
            return fail(Component.translatable("message.dmz_ragnarok.npc.formtype.rename.new_registered", newId));
        }
        if (FormTypeMetaConfig.has(newId)) {
            return fail(Component.translatable("message.dmz_ragnarok.npc.formtype.rename.new_has_meta", newId));
        }
        if (CustomFormTypes.contains(newId)) {
            return fail(Component.translatable("message.dmz_ragnarok.npc.formtype.rename.new_active", newId));
        }

        // Which skills.json list holds the old id (form xor stack). If neither, there's nothing to rename.
        String listKey = FormTypeManager.listContaining(oldId);
        if (listKey == null) {
            return fail(Component.translatable("message.dmz_ragnarok.npc.formtype.rename.not_custom", oldId));
        }
        boolean stack = "stackSkills".equals(listKey);

        // 2) Register newId FIRST so DMZ substring routing gates renamed groups against their own skill.
        CustomFormTypes.register(newId);

        // 3) skills.json: add newId to the same list, copy stack cost block, drop oldId; reload configs.
        String err = FormTypeManager.renameInSkills(oldId, newId, stack);
        if (err != null) {
            CustomFormTypes.unregister(newId); // roll back the speculative registration
            return fail(Component.translatable("message.dmz_ragnarok.npc.formtype.rename.skills_failed", err));
        }

        // 4) Every group JSON on oldId -> newId, re-saved so originalName == groupName (no tombstone) and
        //    originalOwners == current owners (no stale-owner delete).
        int groupsMoved = 0;
        for (FormGroupData group : FormFileManager.loadAll()) {
            if (group.formType != null && group.formType.equals(oldId)) {
                group.formType = newId;
                // loadAll() already set originalName = groupName and originalOwners = [ownerRace], so
                // cleanupStaleOwners sees renamed=false and keeps the file. Re-assert defensively.
                group.originalName = group.groupName;
                if (group.originalOwners.isEmpty()) {
                    group.originalOwners.addAll(group.ownerRaces);
                }
                String saveErr = FormFileManager.save(group);
                if (saveErr != null) {
                    DmzNpc.LOGGER.error("[{}] Rename: failed to re-save group '{}' for type '{}': {}",
                            DmzNpc.MODID, group.groupName, newId, saveErr);
                } else {
                    groupsMoved++;
                }
            }
        }

        // 5) character.json cost blocks: move <oldId> -> <newId> and delete the stale key.
        int costsMoved = FormFileManager.migrateFormCostKey(oldId, newId);

        // 6) Meta: carry the presentation meta, drop the old key, sync to clients.
        if (FormTypeMetaConfig.has(oldId)) {
            FormTypeMetaConfig.put(newId, FormTypeMetaConfig.get(oldId));
            FormTypeMetaConfig.remove(oldId);
        }
        DmzNet.syncFormTypeMetaToAll();

        // 7) Lang: migrate skill.dragonminez.<oldId>(.desc) -> <newId>, then sync.
        migrateLang(oldId, newId);
        DmzNet.syncLangToAll(server);

        // 8) Player progress. Online: preserve (migrate level oldId -> newId). Offline: tombstone oldId
        //    so DMZ's fuzzy repair can't resurrect it; those players may need a re-grant.
        int onlineMigrated = migrateOnlinePlayers(server, oldId, newId);
        FormTombstoneStore.addSkill(oldId);

        // 9) Unregister oldId LAST (routing stayed correct throughout).
        CustomFormTypes.unregister(oldId);

        // 10) Push refreshed DMZ configs to everyone.
        DmzCompat.reloadConfigs();
        DmzCompat.resyncConfigsToAll(server);

        DmzNpc.LOGGER.info("[{}] Renamed form type '{}' -> '{}' ({} group(s), {} race cost block(s), {} online player(s) migrated).",
                DmzNpc.MODID, oldId, newId, groupsMoved, costsMoved, onlineMigrated);

        return new Result(true, Component.translatable("message.dmz_ragnarok.npc.formtype.rename.ok",
                oldId, newId, groupsMoved, onlineMigrated));
    }

    private static boolean isDefault(String id) {
        return FormTypeManager.DEFAULTS.contains(id) || FormTypeManager.STACK_DEFAULTS.contains(id);
    }

    /** Rename the two DMZ skill lang keys in the server's authoritative store (skill name + description). */
    private static void migrateLang(String oldId, String newId) {
        Map<String, String> all = GeneratedLangStore.all();
        String oldName = "skill.dragonminez." + oldId;
        String oldDesc = oldName + ".desc";
        Map<String, String> add = new LinkedHashMap<>();
        if (all.containsKey(oldName)) {
            add.put("skill.dragonminez." + newId, all.get(oldName));
        }
        if (all.containsKey(oldDesc)) {
            add.put("skill.dragonminez." + newId + ".desc", all.get(oldDesc));
        }
        if (!add.isEmpty()) {
            GeneratedLangStore.putAll(add);
        }
        // Remove the stale keys so no <oldId> remnant lingers in the store or its next sync.
        GeneratedLangStore.remove(oldName);
        GeneratedLangStore.remove(oldDesc);
    }

    /**
     * Migrate every ONLINE player's unlocked skill from {@code oldId} to {@code newId}, preserving level and
     * active state, then progression-sync them. Returns how many players were migrated. Fully guarded so a
     * DMZ internals change degrades to a no-op rather than aborting the rename.
     */
    private static int migrateOnlinePlayers(MinecraftServer server, String oldId, String newId) {
        if (server == null) {
            return 0;
        }
        int migrated = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            StatsData stats = DmzForms.stats(player);
            if (stats == null) {
                continue;
            }
            try {
                Skills skills = stats.getSkills();
                if (skills == null || !skills.hasSkill(oldId)) {
                    continue;
                }
                int level = skills.getSkillLevel(oldId);
                boolean active = false;
                try {
                    Skill s = skills.getSkill(oldId);
                    active = s != null && s.isActive();
                } catch (Throwable ignored) {
                    // isActive not resolvable: default inactive.
                }
                skills.setSkillLevel(newId, level);
                if (active) {
                    try {
                        skills.setSkillActive(newId, true);
                    } catch (Throwable ignored) {
                        // active toggle unsupported for this skill: level is preserved regardless.
                    }
                }
                skills.removeSkill(oldId);
                sync(player);
                migrated++;
            } catch (Throwable t) {
                DmzNpc.LOGGER.debug("[{}] Could not migrate skill '{}' -> '{}' for {}: {}",
                        DmzNpc.MODID, oldId, newId, player.getName().getString(), t.toString());
            }
        }
        return migrated;
    }

    private static void sync(ServerPlayer player) {
        try {
            NetworkHandler.sendToTrackingEntityAndSelf(new ProgressionSyncS2C(player), player);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Progression sync failed for {}: {}",
                    DmzNpc.MODID, player.getName().getString(), t.toString());
        }
    }
}
