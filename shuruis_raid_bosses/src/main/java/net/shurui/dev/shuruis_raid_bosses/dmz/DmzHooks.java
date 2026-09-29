package net.shurui.dev.shuruis_raid_bosses.dmz;

import com.dragonminez.common.init.MainEffects;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ResourceSyncS2C;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * Thin integration layer over the DragonMineZ stats capability. Every access is null-safe so a
 * malformed player (no character created yet) never crashes the raid flow.
 */
public final class DmzHooks {
    private DmzHooks() {}

    public static Optional<StatsData> stats(Player player) {
        if (player == null || StatsCapability.INSTANCE == null) return Optional.empty();
        return player.getCapability(StatsCapability.INSTANCE).resolve();
    }

    /** Fully restores vanilla health plus DragonMineZ energy and stamina pools, then resyncs. */
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

    /**
     * Clears the DMZ combat lockout a raid can leave behind. While the {@code stun} effect or combat flags
     * linger, DMZ cancels attacks and block interactions, so a participant teleported out (or death-saved)
     * would be stuck unable to punch or mine until respawn. Removes STUN/STAGGER/KI_SLOW and resets the
     * knockedDown/stunEffect/strikeLocked flags, then resyncs. Null-safe.
     */
    public static void clearCombatLocks(Player player) {
        player.removeEffect(MainEffects.STUN.get());
        player.removeEffect(MainEffects.STAGGER.get());
        player.removeEffect(MainEffects.KI_SLOW.get());
        stats(player).ifPresent(data -> {
            data.getStatus().setKnockedDown(false);
            data.getStatus().setStunEffect(false);
            data.getStatus().setStrikeLocked(false);
            syncStats(player);
            syncResources(player);
        });
    }

    /** Grants DragonMineZ training points (the currency spent to raise stats), then resyncs resources. */
    public static boolean addTrainingPoints(Player player, float amount) {
        return stats(player).map(data -> {
            data.getResources().addTrainingPoints(amount);
            syncResources(player);
            return true;
        }).orElse(false);
    }

    /** Grants (adds) levels to a DragonMineZ skill by its registry id. */
    public static boolean addSkillLevel(Player player, String skillId, int levels) {
        return stats(player).map(data -> {
            data.getSkills().addSkillLevel(skillId, levels);
            syncStats(player);
            return true;
        }).orElse(false);
    }

    public static boolean hasCreatedCharacter(Player player) {
        return stats(player).map(d -> d.getStatus().isHasCreatedCharacter()).orElse(false);
    }

    /** Source name used for every Z-Soul bonus so it can be updated/removed cleanly (and read by the client HUD). */
    public static final String ZSOUL_BONUS_SOURCE = "srb_zsoul";

    /** The server's global stat cap (DMZ {@code maxValue}); 0 if unavailable. */
    public static int globalStatCap(Player player) {
        return stats(player).map(StatsData::getConfiguredMaxValue).orElse(0);
    }

    /**
     * Sets (or clears when {@code amount <= 0}) the Z-Soul beyond-cap bonus on a DMZ stat channel. A
     * positive amount adds flat points on top of the capped base, raising derived values above the global
     * cap. True if the DMZ stats were touched.
     */
    public static boolean setZSoulBonus(Player player, String bonusKey, int amount) {
        return stats(player).map(data -> {
            if (amount > 0) {
                data.getBonusStats().addBonus(bonusKey, ZSOUL_BONUS_SOURCE, "+", amount, false);
            } else {
                data.getBonusStats().removeBonus(bonusKey, ZSOUL_BONUS_SOURCE);
            }
            return true;
        }).orElse(false);
    }

    public static float trainingPoints(Player player) {
        return stats(player).map(d -> d.getResources().getTrainingPoints()).orElse(0f);
    }

    public static void removeTrainingPoints(Player player, float amount) {
        stats(player).ifPresent(d -> {
            d.getResources().removeTrainingPoints(amount);
            syncResources(player);
        });
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
