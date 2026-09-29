package net.shurui.dev.sdu.form;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ProgressionSyncS2C;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Character;
import com.dragonminez.common.stats.extras.UsedForms;
import com.dragonminez.common.stats.skills.Skills;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.mixin.UsedFormsAccessor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Applies {@link FormTombstoneStore} to live player state. Tombstoning covers OFFLINE players (via
 * {@link net.shurui.dev.sdu.mixin.SkillsRepairMixin}); this purges every ONLINE player's DMZ state too:
 * the orphaned {@code Skills} entry and the deleted group from both regular and stack {@code UsedForms}.
 * Remove+sync mirrors DMZ's {@code SkillsCommand}: {@code Skills.removeSkill} then
 * {@code NetworkHandler.sendToTrackingEntityAndSelf(new ProgressionSyncS2C(player), player)}.
 *
 * <p>DMZ classes are used directly (mandatory dep), but every access is guarded so a missing capability or
 * a DMZ internals change degrades to a no-op instead of crashing a deletion.
 */
public final class FormTombstoneScrub {

    private FormTombstoneScrub() {
    }

    /** Remove a deleted form-type skill key from every online player and sync. Name is lowercased by DMZ. */
    public static void scrubSkillOnline(MinecraftServer server, String skillKey) {
        if (server == null || skillKey == null || skillKey.isBlank()) {
            return;
        }
        String key = skillKey.trim().toLowerCase();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            StatsData stats = DmzForms.stats(player);
            if (stats == null) {
                continue;
            }
            try {
                Skills skills = stats.getSkills();
                if (skills != null && skills.hasSkill(key)) {
                    skills.removeSkill(key);
                    sync(player);
                }
            } catch (Throwable t) {
                DmzNpc.LOGGER.debug("[{}] Could not scrub skill '{}' from {}: {}",
                        DmzNpc.MODID, key, player.getName().getString(), t.toString());
            }
        }
    }

    /** Remove a deleted form group from every online player's UsedForms (regular + stack) and sync. */
    public static void scrubGroupOnline(MinecraftServer server, String groupName) {
        if (server == null || groupName == null || groupName.isBlank()) {
            return;
        }
        String group = groupName.trim();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (scrubGroupFor(player, group)) {
                sync(player);
            }
        }
    }

    /**
     * Purge all tombstoned form groups from one player's UsedForms, on login (offline players may keep
     * stale entries no DMZ hook clears). Returns true if anything was removed. Does NOT sync: online
     * deletion callers sync themselves, and login already gets a full progression sync after cap load.
     */
    public static boolean scrubTombstonedGroups(ServerPlayer player) {
        boolean changed = false;
        for (String group : FormTombstoneStore.formGroups()) {
            changed |= scrubGroupFor(player, group);
        }
        return changed;
    }

    private static boolean scrubGroupFor(ServerPlayer player, String group) {
        StatsData stats = DmzForms.stats(player);
        if (stats == null) {
            return false;
        }
        try {
            Character character = stats.getCharacter();
            if (character == null) {
                return false;
            }
            boolean changed = removeGroup(character.getFormsUsedBefore(), group);
            changed |= removeGroup(character.getStackFormsUsedBefore(), group);
            return changed;
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not scrub group '{}' from {}: {}",
                    DmzNpc.MODID, group, player.getName().getString(), t.toString());
            return false;
        }
    }

    /** Drop a group key from a UsedForms map via the accessor mixin (UsedForms has no public remove). */
    private static boolean removeGroup(UsedForms usedForms, String group) {
        if (usedForms == null) {
            return false;
        }
        Map<String, List<String>> map = ((UsedFormsAccessor) (Object) usedForms).sdu$usedForms();
        return map != null && map.remove(group) != null;
    }

    private static void sync(ServerPlayer player) {
        try {
            NetworkHandler.sendToTrackingEntityAndSelf(new ProgressionSyncS2C(player), player);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Progression sync failed for {}: {}",
                    DmzNpc.MODID, player.getName().getString(), t.toString());
        }
    }

    /** Convenience: scrub several groups online in one pass. */
    public static void scrubGroupsOnline(MinecraftServer server, Collection<String> groups) {
        if (server == null || groups == null) {
            return;
        }
        for (String g : groups) {
            scrubGroupOnline(server, g);
        }
    }
}
