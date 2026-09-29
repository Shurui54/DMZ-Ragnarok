package net.shurui.dev.shuruis_dmz_tournaments.dmz;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ResourceSyncS2C;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

// thin layer over the DMZ stats capability. every access is null-safe so a player with no character yet
// never crashes the tournament flow.
public final class DmzHooks {
    private DmzHooks() {}

    public static Optional<StatsData> stats(Player player) {
        if (player == null || StatsCapability.INSTANCE == null) return Optional.empty();
        return player.getCapability(StatsCapability.INSTANCE).resolve();
    }

    // toggle DMZ friendly-fist (non-lethal) and resync
    public static void setFriendlyFist(Player player, boolean enabled) {
        stats(player).ifPresent(data -> {
            data.getStatus().setFriendlyFistEnabled(enabled);
            syncStats(player);
        });
    }

    public static boolean isFriendlyFist(Player player) {
        return stats(player).map(d -> d.getStatus().isFriendlyFistEnabled()).orElse(false);
    }

    // vanilla health + DMZ energy/stamina/poise, then resync
    public static void fullHeal(Player player) {
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.setRemainingFireTicks(0);
        player.clearFire();
        stats(player).ifPresent(data -> {
            data.getResources().setCurrentEnergy(data.getMaxEnergy());
            data.getResources().setCurrentStamina(data.getMaxStamina());
            data.getResources().setCurrentPoise(data.getMaxPoise());
            data.getStatus().setKnockedDown(false);
            data.getStatus().setStunEffect(false);
            syncStats(player);
            syncResources(player);
        });
    }

    // grant TP. fires DMZ's TPGainEvent so the HUD reacts like any other gain, then resyncs
    public static boolean addTrainingPoints(Player player, float amount) {
        return stats(player).map(data -> {
            data.getResources().addTrainingPoints(amount);
            syncResources(player);
            return true;
        }).orElse(false);
    }

    // add levels to a DMZ skill by registry id
    public static boolean addSkillLevel(Player player, String skillId, int levels) {
        return stats(player).map(data -> {
            data.getSkills().addSkillLevel(skillId, levels);
            syncStats(player);
            return true;
        }).orElse(false);
    }

    // DMZ's six core-stat keys, in display order (STR strength, SKP strike power, RES resistance,
    // VIT vitality, PWR ki power, ENE energy). Public so the Stat Gem picker can offer exactly these and the
    // server can re-validate a client's chosen key against them.
    public static final String[] CORE_STAT_KEYS = {"STR", "SKP", "RES", "VIT", "PWR", "ENE"};

    // true only for one of the six real DMZ core stats; the server uses this to reject a spoofed stat key
    // before touching the capability.
    public static boolean isCoreStat(String key) {
        if (key == null) return false;
        for (String k : CORE_STAT_KEYS) {
            if (k.equals(key)) return true;
        }
        return false;
    }

    // The Stat Gem apply (headroom per stat, overflow into a worn Z-Soul through ZSoulBridge) is private and lives
    // in the Ragnarok Key since S23; this class keeps the public DMZ helpers it reads (stats, syncStats, isCoreStat).

    // DMZ flight is a SKILL toggled active, not a vanilla ability, so this is "stop flying" for a DMZ player.
    // The name is DMZ's own id, read from the handlers that drive flight.
    private static final String FLY_SKILL = "fly";

    public static boolean isFlying(Player player) {
        return stats(player).map(d -> d.getSkills().isSkillActive(FLY_SKILL)).orElse(false);
    }

    /**
     * Switch a player's flight off and tell their client. The sync matters: the skill lives on the server but
     * the client keeps its own copy to drive the pose and movement, so an unsynced change leaves them flying on
     * their own screen.
     */
    public static void stopFlying(Player player) {
        stats(player).ifPresent(data -> {
            if (!data.getSkills().isSkillActive(FLY_SKILL)) return;
            data.getSkills().setSkillActive(FLY_SKILL, false);
            syncStats(player);
        });
    }

    public static boolean hasCreatedCharacter(Player player) {
        return stats(player).map(d -> d.getStatus().isHasCreatedCharacter()).orElse(false);
    }

    public static void syncStats(Player player) {
        if (player instanceof ServerPlayer sp && NetworkHandler.INSTANCE != null) {
            NetworkHandler.sendToPlayer(new StatsSyncS2C(sp), sp);
        }
    }

    public static void syncResources(Player player) {
        if (player instanceof ServerPlayer sp && NetworkHandler.INSTANCE != null) {
            NetworkHandler.sendToPlayer(new ResourceSyncS2C(sp), sp);
        }
    }
}
