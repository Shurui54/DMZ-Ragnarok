package net.shurui.shuruisutilities.npcregion;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * One percentage-based custom drop for an NPC region: an item id, a count range and a roll chance. When a
 * mob spawned by the region dies, each configured drop is rolled independently - on success it drops a
 * random count in {@code [minCount, maxCount]} of {@link #itemId}. Plain fields only (a vanilla item id +
 * primitives) so it serializes cleanly both to the region's JSON (Gson) and over the network.
 *
 * <p>Ported from the advanced-spawner {@code CustomDrop} in Shurui's DMZ Dungeons.
 */
public class CustomDrop
{
    /** Registered item id, e.g. {@code minecraft:diamond}. Blank/unknown ids simply drop nothing. */
    public String itemId = "minecraft:diamond";
    public int minCount = 1;
    public int maxCount = 1;
    // roll chance % in [0,100], 100 = always
    public float chance = 100.0f;
    // optional SNBT tag on the dropped stack (enchantments, names, modded data, anything)
    public String nbt = "";

    public CustomDrop() {}

    public CustomDrop(String itemId, int minCount, int maxCount, float chance)
    {
        this.itemId = itemId;
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.chance = chance;
    }

    /** Deep copy, for the editor's copy/paste of a whole region's NPC list. */
    public CustomDrop copy()
    {
        CustomDrop d = new CustomDrop(itemId, minCount, maxCount, chance);
        d.nbt = nbt;
        return d;
    }

    /**
     * Roll this drop and, on success, build the resulting {@link ItemStack}. Returns {@link ItemStack#EMPTY}
     * when the roll fails or the item id is blank/unregistered, so callers can just skip empty stacks.
     */
    public ItemStack roll(RandomSource random)
    {
        if (itemId == null || itemId.isBlank() || chance <= 0.0f)
            return ItemStack.EMPTY;
        if (chance < 100.0f && random.nextFloat() * 100.0f >= chance)
            return ItemStack.EMPTY;
        ResourceLocation loc = ResourceLocation.tryParse(itemId);
        Item item = loc == null ? null : ForgeRegistries.ITEMS.getValue(loc);
        if (item == null || item == Items.AIR)
            return ItemStack.EMPTY;
        int lo = Math.max(0, Math.min(minCount, maxCount));
        int hi = Math.max(0, Math.max(minCount, maxCount));
        int count = hi <= lo ? lo : lo + random.nextInt(hi - lo + 1);
        if (count <= 0)
            return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item, count);
        if (nbt != null && !nbt.isBlank())
        {
            try
            {
                stack.setTag(net.minecraft.nbt.TagParser.parseTag(nbt));
            }
            catch (Exception ignored)
            {
                // Malformed SNBT: drop the plain item rather than nothing.
            }
        }
        return stack;
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(itemId == null ? "" : itemId);
        buf.writeInt(minCount);
        buf.writeInt(maxCount);
        buf.writeFloat(chance);
        buf.writeUtf(nbt == null ? "" : nbt);
    }

    public static CustomDrop decode(FriendlyByteBuf buf)
    {
        CustomDrop d = new CustomDrop();
        d.itemId = buf.readUtf();
        d.minCount = buf.readInt();
        d.maxCount = buf.readInt();
        d.chance = buf.readFloat();
        d.nbt = buf.readUtf();
        return d;
    }
}
