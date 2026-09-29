package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.stats.StatsData;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.DmzBridge;

/** The only class naming DMZ types for reading who is charging what. */
final class ChargeStateImpl
{
    private ChargeStateImpl() {}

    static String chargingIdOf(ServerPlayer player)
    {
        try
        {
            StatsData stats = DmzBridge.stats(player);
            return stats == null || stats.getTechniques() == null
                    ? null : stats.getTechniques().getChargingTechniqueId();
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    static float chargePercentOf(ServerPlayer player)
    {
        try
        {
            StatsData stats = DmzBridge.stats(player);
            return stats == null || stats.getTechniques() == null
                    ? 0.0f : stats.getTechniques().getTechniqueChargePercent();
        }
        catch (Throwable t)
        {
            return 0.0f;
        }
    }

    /** Clear DMZ's own charge state, so a cast that goes nowhere does not leave the player charging forever. */
    static void clearCharge(ServerPlayer player)
    {
        try
        {
            StatsData stats = DmzBridge.stats(player);
            if (stats != null && stats.getTechniques() != null)
                stats.getTechniques().clearTechniqueCharge();
        }
        catch (Throwable ignored)
        {
        }
    }
}
