package net.shurui.shuruisutilities.dragonballbag;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

/**
 * The 7-slot inventory carried by a single dragon ball bag {@link ItemStack}. The contents live in the stack's own
 * NBT (tag {@link #ITEMS_TAG}) so they travel with the item: an equipped bag, a bag lying on the ground and a bag
 * inside another bag all keep their balls.
 *
 * <p>Seven slots is the natural size: a full dragon ball set is exactly seven stars (one to seven), so one bag
 * holds one complete set and nothing more. That also dovetails with the "one dragon at a time" rule, since the
 * whole quest for a single dragon is exactly what fits.
 *
 * <p>No item is ever dropped by the read/write helpers: reading a malformed tag yields an empty handler (the stack
 * is left untouched), and writing only ever REPLACES the tag with the current, complete handler contents.
 */
public final class DragonBallBagInventory
{
    private DragonBallBagInventory()
    {
    }

    /** Slot count: one per dragon ball star (1..7). */
    public static final int SIZE = 7;

    // NBT key under which the ItemStackHandler serialises the seven slots on the bag stack.
    private static final String ITEMS_TAG = "DragonBallBag";

    /**
     * Build a fresh handler populated from the bag stack's NBT. Never throws; a bad tag yields an empty handler.
     *
     * <p>Always a {@link DragonBallBagHandler}, i.e. tagged {@link DragonBallBagStorage}, so the generic item-handler
     * containment mixin lets balls be inserted here (this is the one allowed handler) while refusing every other
     * {@code ItemStackHandler}. The pickup path {@link DragonBallCarry#insertIntoBag} inserts through exactly this
     * handler, so the marker is what keeps a pickup landing in the bag.
     */
    public static DragonBallBagHandler read(ItemStack bag)
    {
        DragonBallBagHandler handler = new DragonBallBagHandler(SIZE);
        if (bag == null || bag.isEmpty())
        {
            return handler;
        }
        try
        {
            CompoundTag tag = bag.getTag();
            if (tag != null && tag.contains(ITEMS_TAG))
            {
                handler.deserializeNBT(tag.getCompound(ITEMS_TAG));
            }
        }
        catch (Throwable t)
        {
            // a corrupt tag must never crash a pickup or a GUI open; fall back to an empty handler and leave the
            // stored tag exactly as it was so nothing is lost.
            return new DragonBallBagHandler(SIZE);
        }
        return handler;
    }

    /** Write the handler back onto the bag stack, replacing the stored contents. */
    public static void write(ItemStack bag, ItemStackHandler handler)
    {
        if (bag == null || bag.isEmpty() || handler == null)
        {
            return;
        }
        bag.getOrCreateTag().put(ITEMS_TAG, handler.serializeNBT());
    }
}
