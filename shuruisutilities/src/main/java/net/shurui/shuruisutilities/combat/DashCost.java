package net.shurui.shuruisutilities.combat;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ResourceSyncS2C;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Resources;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * What a dash costs, in DragonMineZ's own pools.
 *
 * <p>A dash that is free is a movement key, not a technique. Charging both ki and stamina makes it compete with
 * everything else those pools pay for, which is what stops it becoming the default way to travel and keeps the five
 * second cooldown from being the only thing holding it back.
 *
 * <p>Charged as a PERCENTAGE of each maximum rather than a flat number, so it stays meaningful at every point on the
 * stat curve instead of being crippling early and free later.
 *
 * <p>Both pools are checked before either is spent, so a player who can afford one but not the other is refused
 * cleanly rather than being charged for a dash that never happened.
 *
 * <p>Every DMZ touch is guarded, per the workspace rule that a mod being present is not proof its API is unchanged. If
 * the stat runtime cannot be reached the dash is allowed through free rather than being blocked: a drifted DMZ build
 * should cost the player a charge, not the feature.
 */
public final class DashCost
{
    private DashCost() {}

    /** Fraction of maximum ki and maximum stamina one dash costs. */
    public static final float COST_FRACTION = 0.05F;

    private static boolean loggedFailure = false;

    /**
     * Take the price of a dash, or refuse it. Returns whether the dash may proceed.
     */
    public static boolean tryPay(ServerPlayer player)
    {
        if (player == null)
            return false;
        if (player.isCreative() || player.isSpectator())
            return true;
        if (StatsCapability.INSTANCE == null)
            return true;
        try
        {
            StatsData data = player.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
            if (data == null)
                return true; // no character yet; nothing to charge against
            Resources resources = data.getResources();
            if (resources == null)
                return true;

            float energyCost = data.getMaxEnergy() * COST_FRACTION;
            float staminaCost = data.getMaxStamina() * COST_FRACTION;
            if (resources.getCurrentEnergy() < energyCost || resources.getCurrentStamina() < staminaCost)
                return false;

            resources.removeEnergy(energyCost);
            resources.removeStamina(staminaCost);
            // The HUD is not refreshed on the same tick a pool is mutated, so without this the bars only catch up on
            // DMZ's next scheduled sync and the dash looks free for a moment.
            if (NetworkHandler.INSTANCE != null)
            {
                NetworkHandler.sendToPlayer(new StatsSyncS2C(player), player);
                NetworkHandler.sendToPlayer(new ResourceSyncS2C(player), player);
            }
            return true;
        }
        catch (Throwable t)
        {
            if (!loggedFailure)
            {
                loggedFailure = true;
                LoggingHandler.sulog.warn(
                        "[Dash] DragonMineZ stat access failed; dashes are free this run. Cause: {}", t.toString());
            }
            return true;
        }
    }
}
