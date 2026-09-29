package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.quest.PartyManager;

import net.minecraft.world.entity.player.Player;

/** The only class naming DMZ's party manager. See {@link DmzParty}. */
final class DmzPartyImpl
{
    private DmzPartyImpl() {}

    static boolean sameParty(Player a, Player b)
    {
        try
        {
            return PartyManager.areInSameParty(a, b);
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
