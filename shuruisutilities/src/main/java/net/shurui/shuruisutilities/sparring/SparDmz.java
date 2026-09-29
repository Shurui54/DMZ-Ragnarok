package net.shurui.shuruisutilities.sparring;

import java.util.Optional;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ResourceSyncS2C;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

/**
 * All DragonMineZ access the sparring module needs, in one guarded place. Every method is null/exception safe so
 * a statless player or a shifted DMZ internal degrades to a no-op rather than crashing a spar. Battle power is
 * delegated to the guilds bridge so the two features share one (android-safe) battle-power number.
 */
public final class SparDmz
{
    private SparDmz() {}

    private static final boolean LOADED = ModList.get().isLoaded("dragonminez");

    public static boolean available()
    {
        return LOADED;
    }

    private static Optional<StatsData> stats(Player player)
    {
        if (!LOADED || player == null)
            return Optional.empty();
        try
        {
            return player.getCapability(StatsCapability.INSTANCE).resolve();
        }
        catch (Throwable t)
        {
            return Optional.empty();
        }
    }

    public static boolean hasCharacter(Player player)
    {
        return stats(player).map(d -> {
            try { return d.getStatus().isHasCreatedCharacter(); }
            catch (Throwable t) { return false; }
        }).orElse(false);
    }

    /** DragonMineZ battle power (android-safe), or 0 when unavailable. Shared with the guild claim math. */
    public static double battlePower(Player player)
    {
        return net.shurui.shuruisutilities.guilds.integration.DmzBridge.battlePower(player);
    }

    /**
     * DragonMineZ BASE battle power (transformation-independent), or 0 when unavailable. Sparring compares the two
     * fighters by this so a form powering up mid-bout never collapses the closeness ratio or the payout.
     */
    public static double baseBattlePower(Player player)
    {
        return net.shurui.shuruisutilities.guilds.integration.DmzBridge.baseBattlePower(player);
    }

    /** True when both players are in the same DragonMineZ party. Guarded; false when DMZ is absent or errors. */
    public static boolean sameParty(Player a, Player b)
    {
        if (!LOADED || a == null || b == null)
            return false;
        try
        {
            return com.dragonminez.common.quest.PartyManager.areInSameParty(a, b);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    public static boolean isKnockedDown(Player player)
    {
        return stats(player).map(d -> {
            try { return d.getStatus().isKnockedDown(); }
            catch (Throwable t) { return false; }
        }).orElse(false);
    }

    public static boolean isFriendlyFist(Player player)
    {
        return stats(player).map(d -> {
            try { return d.getStatus().isFriendlyFistEnabled(); }
            catch (Throwable t) { return false; }
        }).orElse(false);
    }

    public static void setFriendlyFist(Player player, boolean enabled)
    {
        stats(player).ifPresent(d -> {
            try
            {
                d.getStatus().setFriendlyFistEnabled(enabled);
                syncStats(player);
            }
            catch (Throwable ignored) {}
        });
    }

    /**
     * DMZ's cost of this player's next stat point (the same value the training minigames scale their reward by).
     * Returns 0 when unavailable, which makes the progression-scaled payout fall back to zero for that fighter.
     */
    public static int singleStatCost(Player player)
    {
        return stats(player).map(d -> {
            try { return d.getSingleStatCost(d.getStats().getTotalStats()); }
            catch (Throwable t) { return 0; }
        }).orElse(0);
    }

    /**
     * Grant TP through DMZ's normal path so its cancelable TPGainEvent fires and the HUD updates. The two-arg form
     * is used with share-with-party OFF: each fighter is rewarded individually, so a party spar never leaks the
     * payout to the rest of the party (the single-arg form defaults party-sharing ON).
     */
    public static void awardTrainingPoints(ServerPlayer player, float amount)
    {
        if (amount <= 0f)
            return;
        stats(player).ifPresent(d -> {
            try
            {
                d.getResources().addTrainingPoints(amount, false);
                syncResources(player);
            }
            catch (Throwable ignored) {}
        });
    }

    /** Vanilla health + DMZ energy/stamina/poise, clear knockdown/stun, then resync. Used at spar start and end. */
    public static void fullHeal(ServerPlayer player)
    {
        try
        {
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setRemainingFireTicks(0);
            player.clearFire();
        }
        catch (Throwable ignored) {}
        stats(player).ifPresent(d -> {
            try
            {
                d.getResources().setCurrentEnergy(d.getMaxEnergy());
                d.getResources().setCurrentStamina(d.getMaxStamina());
                d.getResources().setCurrentPoise(d.getMaxPoise());
                d.getStatus().setKnockedDown(false);
                d.getStatus().setStunEffect(false);
                syncStats(player);
                syncResources(player);
            }
            catch (Throwable ignored) {}
        });
    }

    private static void syncStats(Player player)
    {
        if (LOADED && player instanceof ServerPlayer sp && NetworkHandler.INSTANCE != null)
        {
            try { NetworkHandler.sendToPlayer(new StatsSyncS2C(sp), sp); }
            catch (Throwable ignored) {}
        }
    }

    private static void syncResources(Player player)
    {
        if (LOADED && player instanceof ServerPlayer sp && NetworkHandler.INSTANCE != null)
        {
            try { NetworkHandler.sendToPlayer(new ResourceSyncS2C(sp), sp); }
            catch (Throwable ignored) {}
        }
    }
}
