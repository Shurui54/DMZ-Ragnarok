package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.corrupted.CorruptedBalls;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Describes a single dragon ball stack for the grave-totem AUDIT command: which set it belongs to, a human label
 * for that set, and which star (1..7) it is. This is a read-only diagnostic lookup; it never writes to DMZ's data
 * and never touches a grave.
 *
 * <p>The set id itself already comes from {@link DragonBallSets#setIdOf(ItemStack)}, which is the one authority for
 * "is this a ball and whose set". This class only adds the STAR and a friendly label, both of which the audit list
 * needs and neither of which {@code DragonBallSets} exposes. Star comes from DMZ's own
 * {@link DragonBallSetDefinition#getStarForBlock(Block)} for a DMZ set, and from {@link CorruptedBalls} for SU's
 * own corrupted balls, which live outside DMZ's ball-set system exactly as {@code DragonBallSets} handles them.
 *
 * <p>Same safety contract as {@code DragonBallSets}: every read of DMZ's definitions is wrapped in
 * try/catch(Throwable) and a single failure latches a one-shot warning, then degrades to "star unknown" (0). A DMZ
 * API shift can never crash the audit command; at worst a line reads {@code star ?} instead of a number.
 */
public final class DragonBallTotemInfo
{
    private DragonBallTotemInfo()
    {
    }

    /** Star sentinel when the ball's star could not be determined. */
    public static final int UNKNOWN_STAR = 0;

    // one-shot latch so a DMZ API mismatch logs exactly once instead of once per grave per list.
    private static volatile boolean loggedFailure = false;

    /** Immutable description of one dragon ball: its set id, a display label, and its star (or UNKNOWN_STAR). */
    public static final class Info
    {
        public final String setId;
        public final String setLabel;
        public final int star;

        Info(String setId, String setLabel, int star)
        {
            this.setId = setId;
            this.setLabel = setLabel;
            this.star = star;
        }

        /** e.g. {@code "earth star 4"} or {@code "corrupted star 7"}; star shows {@code ?} when unknown. */
        @Override
        public String toString()
        {
            return setLabel + " star " + (star == UNKNOWN_STAR ? "?" : Integer.toString(star));
        }
    }

    /**
     * Describe the given stack, or null when it is not a dragon ball. Never throws.
     */
    public static Info describe(ItemStack stack)
    {
        String setId = DragonBallSets.setIdOf(stack);
        if (setId == null)
        {
            return null;
        }
        Item item = stack.getItem();
        int star = starOf(setId, item);
        String label = labelFor(setId);
        return new Info(setId, label, star);
    }

    // star for a ball item. corrupted balls are SU's own and are resolved from CorruptedBalls; everything else is a
    // DMZ set and is resolved from DMZ's own getStarForBlock. UNKNOWN_STAR on any miss or failure.
    private static int starOf(String setId, Item item)
    {
        if (item == null || item == Items.AIR)
        {
            return UNKNOWN_STAR;
        }
        if (DragonBallSets.CORRUPTED_SET_ID.equals(setId))
        {
            return corruptedStarOf(item);
        }
        try
        {
            Block block = Block.byItem(item);
            if (block == null)
            {
                return UNKNOWN_STAR;
            }
            for (DragonBallSetDefinition set : DragonBallDefinitions.getBallSets())
            {
                if (set == null || !setId.equals(set.getId()))
                {
                    continue;
                }
                Integer s = set.getStarForBlock(block);
                if (s != null)
                {
                    return s;
                }
            }
        }
        catch (Throwable t)
        {
            latchFailure(t);
        }
        return UNKNOWN_STAR;
    }

    // corrupted balls are indexed 1..COUNT in CorruptedBalls.BALLS; reverse the item back to that index.
    private static int corruptedStarOf(Item item)
    {
        try
        {
            for (int star = 1; star <= CorruptedBalls.COUNT; star++)
            {
                RegistryObject<Block> ball = CorruptedBalls.BALLS[star];
                if (ball == null || !ball.isPresent())
                {
                    continue;
                }
                if (ball.get().asItem() == item)
                {
                    return star;
                }
            }
        }
        catch (Throwable ignored)
        {
            // leave it unknown rather than fail the audit line
        }
        return UNKNOWN_STAR;
    }

    // a friendly label for the set: DMZ's own display name when it has one, else the raw set id. corrupted has no
    // DMZ definition so it always falls back to its id, which is already readable.
    private static String labelFor(String setId)
    {
        if (DragonBallSets.CORRUPTED_SET_ID.equals(setId))
        {
            return setId;
        }
        try
        {
            for (DragonBallSetDefinition set : DragonBallDefinitions.getBallSets())
            {
                if (set == null || !setId.equals(set.getId()))
                {
                    continue;
                }
                return set.getDisplayName().orElse(setId);
            }
        }
        catch (Throwable t)
        {
            latchFailure(t);
        }
        return setId;
    }

    private static void latchFailure(Throwable t)
    {
        if (!loggedFailure)
        {
            loggedFailure = true;
            LoggingHandler.sulog.warn(
                    "[dragonballbag] Could not read DMZ ball star/label for the grave audit; lines will show "
                            + "'star ?'. Cause: {}",
                    t.toString());
        }
    }
}
