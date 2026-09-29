package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * DMZ's impact frame: the brief freeze-and-flash its own heavy blows use.
 *
 * <p>Borrowed rather than reinvented so an impact of ours reads as the same KIND of event as one of DMZ's, and so it
 * honours whatever the player has set in DMZ's own impact-frame config. Guard only; {@link DmzImpactFrameImpl} names
 * the packet.
 */
public final class DmzImpactFrame
{
    private DmzImpactFrame() {}

    /** Play the frame for everyone who can see this player, and for the player themselves. */
    public static void play(ServerPlayer around)
    {
        if (around != null && ModList.get().isLoaded("dragonminez"))
            DmzImpactFrameImpl.play(around);
    }
}
