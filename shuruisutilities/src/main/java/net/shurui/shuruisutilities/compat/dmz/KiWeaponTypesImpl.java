package net.shurui.shuruisutilities.compat.dmz;

import java.util.Map;

import com.dragonminez.common.config.CombatConfig;
import com.dragonminez.common.config.ConfigManager;

import net.shurui.shuruisutilities.god.RagnarokKiWeapon;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/** The only class naming DMZ types for ki weapon type registration. */
final class KiWeaponTypesImpl
{
    private KiWeaponTypesImpl() {}

    /**
     * Add our weapons to DMZ's ki weapon config, cloning an existing entry so every field DMZ expects is populated
     * with sane values rather than nulls.
     *
     * <p>Cloning {@code scythe} deliberately: it is DMZ's two-handed profile, which is the closest match for a staff,
     * so damage, cost, speed and combo all start somewhere sensible instead of at zero.
     */
    static int register()
    {
        try
        {
            CombatConfig combat = ConfigManager.getCombatConfig();
            if (combat == null)
                return 0;
            Map<String, CombatConfig.KiWeaponConfig> configs = combat.getKiWeaponsConfig();
            if (configs == null)
                return 0;

            CombatConfig.KiWeaponConfig template = combat.getKiWeaponConfig("scythe");
            if (template == null)
                return 0;

            int added = 0;
            for (RagnarokKiWeapon weapon : RagnarokKiWeapon.values())
            {
                if (configs.containsKey(weapon.type))
                    continue;
                configs.put(weapon.type, template);
                added++;
            }
            return added;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[kiweapon] could not register ki weapon types: {}", t.toString());
            return 0;
        }
    }
}
