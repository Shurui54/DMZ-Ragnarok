package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Which racial ability a player's RACE carries.
 *
 * <p>The id lives on the race's own character config ({@code racialSkill}), not on the player, so this is really
 * "what does being this race come with". Guard only; {@link DmzRacialImpl} names the DMZ types.
 */
public final class DmzRacial
{
    private DmzRacial() {}

    /** The racial ability id for this player's race, or null when they have no race, or no DMZ. */
    public static String idOf(ServerPlayer player)
    {
        return player != null && ModList.get().isLoaded("dragonminez") ? DmzRacialImpl.idOf(player) : null;
    }
}
