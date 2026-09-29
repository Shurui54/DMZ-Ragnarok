package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.stats.StatsData;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.DmzBridge;

/**
 * The only class here that names DMZ types for the damage formula; {@link DragonDamage} is the guard in front of it.
 * Split this way so nothing classloads {@link StatsData} unless DMZ is actually present (the optional-dependency pattern).
 *
 * <p>Reads the POST-multiplier accessors ({@code getMeleeDamage}, {@code getStrikeDamage}, {@code getKiDamage}), not
 * the {@code ...NoForms} / {@code ...NoMultipliers} variants, so a transformed dragon's move scales with the form
 * they are actually in. That is the point of a shadow dragon technique: it should get stronger in Omega.
 */
final class DragonDamageStats
{
    private DragonDamageStats() {}

    static float total(ServerPlayer caster)
    {
        try
        {
            StatsData stats = DmzBridge.stats(caster);
            if (stats == null)
                return 0.0f;
            double sum = stats.getMeleeDamage() + stats.getStrikeDamage() + stats.getKiDamage();
            return (float) (sum / 2.0);
        }
        catch (Throwable t)
        {
            // A DMZ internals change must not take a move's whole cast down with it; no damage is the safe answer.
            return 0.0f;
        }
    }
}
