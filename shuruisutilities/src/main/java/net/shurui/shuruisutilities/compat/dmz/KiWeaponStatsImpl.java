package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.config.CombatConfig;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.DmzBridge;

/** The only class naming DMZ types for ki weapon damage. */
final class KiWeaponStatsImpl
{
    private KiWeaponStatsImpl() {}

    /**
     * The damage a DMZ ki weapon of this type would do for this player.
     *
     * <p>Read from DMZ's own {@code KiWeaponConfig}: base plus the ki-scaling term against the player's ki damage,
     * which is exactly how DMZ's own weapons scale. Taking the numbers from DMZ's config rather than inventing our
     * own means the summoned weapons stay in step when that config is tuned.
     */
    static float damageFor(ServerPlayer player, String weaponType)
    {
        try
        {
            CombatConfig combat = ConfigManager.getCombatConfig();
            if (combat == null)
                return 0.0f;
            CombatConfig.KiWeaponConfig cfg = combat.getKiWeaponConfig(weaponType);
            if (cfg == null)
                return 0.0f;

            double base = cfg.getBaseDamage();
            double scaling = cfg.getKiScalingDamage();
            StatsData stats = DmzBridge.stats(player);
            double ki = stats == null ? 0.0 : stats.getKiDamage();
            return (float) (base + scaling * ki);
        }
        catch (Throwable t)
        {
            return 0.0f;
        }
    }
}
