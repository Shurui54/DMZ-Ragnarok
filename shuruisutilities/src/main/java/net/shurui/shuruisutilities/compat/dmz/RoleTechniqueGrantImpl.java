package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.techniques.KiAttackData;
import com.dragonminez.common.stats.techniques.PredefinedTechniques;
import com.dragonminez.common.stats.techniques.Techniques;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.DmzBridge;

/** The only class naming DMZ types for granting and removing role techniques. */
final class RoleTechniqueGrantImpl
{
    private RoleTechniqueGrantImpl() {}

    /** @return true when the player's unlocked set changed. */
    static boolean setUnlocked(ServerPlayer player, String techniqueId, boolean unlocked)
    {
        try
        {
            StatsData stats = DmzBridge.stats(player);
            if (stats == null)
                return false;
            Techniques techniques = stats.getTechniques();
            if (techniques == null)
                return false;

            boolean has = techniques.getUnlockedTechniques().containsKey(techniqueId);
            if (has == unlocked)
                return false;

            if (unlocked)
            {
                KiAttackData data = PredefinedTechniques.REGISTRY.get(techniqueId);
                if (data == null)
                    return false;
                // A DEEP COPY, never the registry instance. unlockTechnique stores the reference it is handed, so
                // granting the registry object itself would let one player's technique upgrades rewrite the
                // definition every other player is granted afterwards.
                KiAttackData granted = new KiAttackData();
                granted.load(data.save());
                techniques.unlockTechnique(granted);
            }
            else
            {
                // removeTechnique also clears it from any slot it was equipped in, which is the behaviour wanted:
                // losing the title should take the move off the bar, not leave a dead button.
                techniques.removeTechnique(techniqueId);
            }
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
