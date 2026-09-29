package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

/**
 * Are these two in the same DMZ party?
 *
 * <p>Guard only; {@link DmzPartyImpl} is the sole class naming DMZ's party manager. Answers false without DMZ, so
 * anything that treats party members as friends simply falls back to the other tests it makes.
 */
public final class DmzParty
{
    private DmzParty() {}

    public static boolean sameParty(Player a, Player b)
    {
        return a != null && b != null && ModList.get().isLoaded("dragonminez") && DmzPartyImpl.sameParty(a, b);
    }
}
