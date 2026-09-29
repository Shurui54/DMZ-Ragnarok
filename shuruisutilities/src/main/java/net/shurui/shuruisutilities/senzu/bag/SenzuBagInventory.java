package net.shurui.shuruisutilities.senzu.bag;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

/**
 * The bean inventory carried by a single senzu bean bag {@link ItemStack}. Its contents live in the stack's own NBT
 * (tag {@link #ITEMS_TAG}) so they travel with the item: an equipped bag, a bag lying on the ground and a bag stored
 * in a chest all keep their beans. This mirrors {@code DragonBallBagInventory} exactly, differing only in slot count
 * and NBT key so the two bags never read each other's data.
 *
 * <p>Six slots is the design size: the commissioned bag artwork
 * (assets/shuruisutilities/textures/gui/senzu_bag.png) paints a 3x2 pocket of exactly SIX sockets on the bag body,
 * so the inventory holds one bean stack per painted socket. (The bag was briefly nine slots, "one inventory row",
 * before the art existed; the art is the real design intent, so the count follows it.) Six stacks of 64 beans (384)
 * is still a generous but FINITE emergency stash, and because beans are ordinary stacking items a handful of sockets
 * holds far more beans than the seven-slot dragon ball bag holds balls.
 *
 * <p>No bean is ever dropped by the read/write helpers: reading a malformed tag yields an empty handler (the stack
 * is left untouched), and writing only ever REPLACES the tag with the current, complete handler contents.
 *
 * <p>LEGACY BAGS: {@link #read} sizes the returned handler to whatever the stored NBT recorded, NOT necessarily
 * {@link #SIZE}. A bag saved by an older nine-slot build therefore reads back as a nine-slot handler with its beans
 * still intact, so callers can see (and recover) any beans that lived in the now-removed slots instead of silently
 * truncating them. The six-slot menu does exactly that migration when such a bag is opened; see {@code SenzuBagMenu}.
 */
public final class SenzuBagInventory
{
    private SenzuBagInventory()
    {
    }

    /** Slot count: one bean stack per socket the bag art paints (a 3x2 pocket, so six). */
    public static final int SIZE = 6;

    // NBT key under which the ItemStackHandler serialises the bag's slots on the bag stack. Distinct from the dragon
    // ball bag's key so the two bags can never read each other's stored contents.
    private static final String ITEMS_TAG = "SenzuBag";

    /**
     * Build a fresh handler populated from the bag stack's NBT. Never throws; a bad tag yields an empty handler.
     *
     * <p>The returned handler is sized to the stored data, which for a legacy bag can exceed {@link #SIZE}. Callers
     * that only keep {@link #SIZE} slots MUST recover any beans in the extra slots (see the menu's migration) rather
     * than dropping them.
     */
    public static ItemStackHandler read(ItemStack bag)
    {
        ItemStackHandler handler = new ItemStackHandler(SIZE);
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
            // a corrupt tag must never crash a GUI open or a keybind pull; fall back to an empty handler and leave
            // the stored tag exactly as it was so nothing is lost.
            return new ItemStackHandler(SIZE);
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
