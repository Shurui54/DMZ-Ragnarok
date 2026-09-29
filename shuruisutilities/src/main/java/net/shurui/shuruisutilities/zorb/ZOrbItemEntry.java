package net.shurui.shuruisutilities.zorb;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * One weighted entry in a region's Z orb item pool: an item id, an optional SNBT tag, a count range and a
 * selection weight. The pool is rolled once AT SPAWN (so the chosen stack can be rendered inside the last orb
 * of the chain, per the owner's requirement), never at pickup. Plain fields (a vanilla item id plus primitives)
 * so it serialises to the region JSON via Gson and over the network identically, exactly like {@code CustomDrop}.
 */
public class ZOrbItemEntry
{
    /** Registered item id, e.g. {@code minecraft:diamond}. Blank / unregistered entries roll to nothing. */
    public String itemId = "minecraft:diamond";
    /** Optional SNBT tag placed on the rolled stack (enchantments, names, modded data). Blank = none. */
    public String nbt = "";
    public int minCount = 1;
    public int maxCount = 1;
    /** Selection weight within the pool; entries at or below zero are never chosen. */
    public int weight = 1;

    public ZOrbItemEntry() {}

    public ZOrbItemEntry(String itemId, int minCount, int maxCount, int weight)
    {
        this.itemId = itemId;
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.weight = weight;
    }

    /** Deep copy, for the editor's copy / paste of a whole pool. */
    public ZOrbItemEntry copy()
    {
        ZOrbItemEntry e = new ZOrbItemEntry(itemId, minCount, maxCount, weight);
        e.nbt = nbt;
        return e;
    }

    /** Clamp the fields to sane values. Never throws; leaves an unregistered id alone (it simply rolls empty). */
    public void sanitize()
    {
        if (itemId == null)
            itemId = "";
        if (nbt == null)
            nbt = "";
        if (minCount < 1)
            minCount = 1;
        if (maxCount < minCount)
            maxCount = minCount;
        if (weight < 0)
            weight = 0;
    }

    /** True when this entry names a registered, non-air item and can actually be chosen. */
    public boolean valid()
    {
        if (itemId == null || itemId.isBlank() || weight <= 0)
            return false;
        ResourceLocation loc = ResourceLocation.tryParse(itemId);
        Item item = loc == null ? null : ForgeRegistries.ITEMS.getValue(loc);
        return item != null && item != Items.AIR;
    }

    /**
     * Build the stack for this entry with a rolled count in {@code [minCount, maxCount]}. Returns
     * {@link ItemStack#EMPTY} when the id is blank or unregistered so callers can just skip it.
     */
    public ItemStack roll(RandomSource random)
    {
        if (itemId == null || itemId.isBlank())
            return ItemStack.EMPTY;
        ResourceLocation loc = ResourceLocation.tryParse(itemId);
        Item item = loc == null ? null : ForgeRegistries.ITEMS.getValue(loc);
        if (item == null || item == Items.AIR)
            return ItemStack.EMPTY;
        int lo = Math.max(1, Math.min(minCount, maxCount));
        int hi = Math.max(lo, Math.max(minCount, maxCount));
        int count = hi <= lo ? lo : lo + random.nextInt(hi - lo + 1);
        ItemStack stack = new ItemStack(item, count);
        if (nbt != null && !nbt.isBlank())
        {
            try
            {
                stack.setTag(net.minecraft.nbt.TagParser.parseTag(nbt));
            }
            catch (Exception ignored)
            {
                // Malformed SNBT: use the plain item rather than nothing.
            }
        }
        return stack;
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(itemId == null ? "" : itemId);
        buf.writeUtf(nbt == null ? "" : nbt);
        buf.writeInt(minCount);
        buf.writeInt(maxCount);
        buf.writeInt(weight);
    }

    public static ZOrbItemEntry decode(FriendlyByteBuf buf)
    {
        ZOrbItemEntry e = new ZOrbItemEntry();
        e.itemId = buf.readUtf();
        e.nbt = buf.readUtf();
        e.minCount = buf.readInt();
        e.maxCount = buf.readInt();
        e.weight = buf.readInt();
        return e;
    }
}
