package net.shurui.shuruisutilities.katchin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Marker for the five katchi katchin tools. They share one colour representation (see {@link KatchiKatchinColour})
 * and one randomisation moment: when the tool is produced by a smithing upgrade it is dyed a random one of the three
 * colours. The randomisation is done authoritatively on the server so the real stack the player receives carries
 * the colour, not just the client preview.
 */
public interface KatchiKatchinTool
{
    /**
     * Roll a fresh random colour onto a just-smithed katchi katchin tool. Server side only: on the client this is a
     * predicted craft and the server's roll is the authoritative one, synced back afterwards. A stack that briefly
     * has no colour on the client renders the default colour, never a missing model.
     */
    static void rollColourOnCraft(ItemStack stack, Level level)
    {
        if (level == null || level.isClientSide)
        {
            return;
        }
        KatchiKatchinColour.randomise(stack, level.getRandom());
    }
}
