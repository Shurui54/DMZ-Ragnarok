package net.shurui.shuruisutilities.compat.dmz;

import java.util.Collection;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * Handing a whole dragon ball set to a player, in item form.
 *
 * <p>DragonMineZ models a ball as a BLOCK, so its item form is the block item; a "set" is that block once per star the
 * set declares. Reading the star list from the definition rather than assuming seven is what keeps this correct for
 * SU's own sets, which are not all seven balls.
 *
 * <p>Every DMZ touch is guarded. A drifted DMZ degrades to handing over nothing and saying so, which is recoverable,
 * rather than throwing part way through a ritual the player has already paid for.
 */
public final class SuDragonBallItems
{
    private SuDragonBallItems() {}

    /** How many balls this set has, or 0 if it cannot be resolved. */
    public static int ballCount(String setId)
    {
        DragonBallSetDefinition set = find(setId);
        return set == null || set.getStars() == null ? 0 : set.getStars().size();
    }

    /**
     * Put one of every ball in this set into the player's inventory, dropping anything that will not fit at their feet
     * so a full inventory never silently eats a set they have just paid five thousand levels for. Returns how many were
     * actually handed over.
     */
    public static int giveSet(ServerPlayer player, String setId)
    {
        DragonBallSetDefinition set = find(setId);
        if (player == null || set == null || set.getStars() == null)
            return 0;
        int given = 0;
        for (Integer star : set.getStars())
        {
            if (star == null)
                continue;
            try
            {
                Block block = set.getBlockForStar(star);
                if (block == null)
                    continue;
                ItemStack stack = new ItemStack(block);
                if (stack.isEmpty())
                    continue;
                player.getInventory().add(stack); // mutates stack down to whatever did not fit
                if (!stack.isEmpty())
                    player.drop(stack, false);
                given++;
            }
            catch (Throwable ignored)
            {
                // one unresolvable star should not cost the player the rest of the set
            }
        }
        return given;
    }

    private static DragonBallSetDefinition find(String setId)
    {
        if (setId == null || setId.isEmpty())
            return null;
        try
        {
            Collection<DragonBallSetDefinition> sets = DragonBallDefinitions.getBallSets();
            if (sets == null)
                return null;
            for (DragonBallSetDefinition set : sets)
            {
                if (set != null && setId.equals(set.getId()))
                    return set;
            }
        }
        catch (Throwable ignored)
        {
        }
        return null;
    }
}
