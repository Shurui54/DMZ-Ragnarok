package net.shurui.shuruisutilities.dragonballbag;

import net.shurui.shuruisutilities.compat.curios.DragonBallBagCurios;
import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

/**
 * Read-side helpers for the "one dragon at a time" rule and bag insertion. A player is considered to be collecting
 * a set the moment they hold a single ball of it anywhere the rules permit a ball to live: their main inventory or
 * their equipped bag. Both places are scanned; the first ball found names the carried set.
 *
 * <p>Everything here is server-side truth. The values feed the pickup handler and the bag menu's slot validation.
 */
public final class DragonBallCarry
{
    private DragonBallCarry()
    {
    }

    /**
     * The ball set id the player is currently collecting, or null if they hold no dragon balls at all. Scans the
     * main inventory first, then the equipped bag. Never throws.
     */
    public static String carriedSet(Player player)
    {
        if (player == null)
        {
            return null;
        }

        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); ++i)
        {
            String set = DragonBallSets.setIdOf(inv.getItem(i));
            if (set != null)
            {
                return set;
            }
        }

        String inBag = setInBag(DragonBallBagCurios.findEquipped(player));
        return inBag; // null when the bag is empty or absent
    }

    /** The set id of whatever balls are inside this bag stack, or null if the bag is empty / not a bag. */
    public static String setInBag(ItemStack bag)
    {
        if (bag == null || bag.isEmpty())
        {
            return null;
        }
        ItemStackHandler handler = DragonBallBagInventory.read(bag);
        for (int i = 0; i < handler.getSlots(); ++i)
        {
            String set = DragonBallSets.setIdOf(handler.getStackInSlot(i));
            if (set != null)
            {
                return set;
            }
        }
        return null;
    }

    /**
     * Insert as much of {@code stack} as fits into the equipped bag, persisting the result, and return the
     * remainder (never null; {@link ItemStack#EMPTY} when it all fit). The caller is responsible for having already
     * checked the one-type rule; this only does the physical move. If anything goes wrong the ORIGINAL stack is
     * returned unchanged so the ball is never voided.
     */
    public static ItemStack insertIntoBag(Player owner, ItemStack bag, ItemStack stack)
    {
        if (owner == null || bag == null || bag.isEmpty() || stack == null || stack.isEmpty())
        {
            return stack == null ? ItemStack.EMPTY : stack;
        }
        try
        {
            ItemStackHandler handler = DragonBallBagInventory.read(bag);
            ItemStack remainder = stack.copy();
            for (int i = 0; i < handler.getSlots() && !remainder.isEmpty(); ++i)
            {
                remainder = handler.insertItem(i, remainder, false);
            }
            // write the updated contents back onto the live equipped stack and nudge Curios to re-sync.
            DragonBallBagInventory.write(bag, handler);
            DragonBallBagCurios.persist(owner, bag);
            return remainder;
        }
        catch (Throwable t)
        {
            // never lose the ball: on any failure, report "nothing was inserted" so the caller leaves it in the world.
            return stack;
        }
    }
}
