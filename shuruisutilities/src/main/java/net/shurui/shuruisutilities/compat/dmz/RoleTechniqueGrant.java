package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Guard for granting and removing the role techniques through DMZ's own unlock system.
 *
 * <p>This is how a title gates its moves. The alternative - leaving the technique unlocked for everyone and refusing
 * it at cast - means a player carries a technique they can equip, charge and fire, that then does nothing, which
 * reads as broken rather than as forbidden. Removing it means it is simply not in their list.
 */
public final class RoleTechniqueGrant
{
    private RoleTechniqueGrant() {}

    /** Unlock or remove one technique for this player. @return true when their set changed. */
    public static boolean setUnlocked(ServerPlayer player, String techniqueId, boolean unlocked)
    {
        if (player == null || techniqueId == null || !ModList.get().isLoaded("dragonminez"))
            return false;
        return RoleTechniqueGrantImpl.setUnlocked(player, techniqueId, unlocked);
    }
}
