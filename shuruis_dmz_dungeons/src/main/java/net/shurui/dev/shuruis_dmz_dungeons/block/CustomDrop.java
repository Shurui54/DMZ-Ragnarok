package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import net.minecraft.util.RandomSource;

// one percentage drop for a spawner: item id + count range + chance, rolled independently per mob death.
// only primitives and an item id, so it survives to NBT and needs no third-party types.
public class CustomDrop {

    public String itemId = "minecraft:diamond"; // blank/unknown -> drops nothing
    public int minCount = 1;
    public int maxCount = 1;
    public float chance = 100.0f; // percent, 100 = always
    public String nbt = ""; // optional SNBT applied to the dropped stack

    public CustomDrop() {
    }

    public CustomDrop(String itemId, int minCount, int maxCount, float chance) {
        this.itemId = itemId;
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.chance = chance;
    }

    // roll and build the stack, or EMPTY on a failed roll / blank / unregistered id. callers skip empties.
    public ItemStack roll(RandomSource random) {
        if (itemId == null || itemId.isBlank() || chance <= 0.0f) {
            return ItemStack.EMPTY;
        }
        if (chance < 100.0f && random.nextFloat() * 100.0f >= chance) {
            return ItemStack.EMPTY;
        }
        return build(random);
    }

    // build the stack IGNORING the chance gate: resolve the item, roll the count, apply the optional SNBT. used by the
    // crate loot pools, which use `chance` as a PICK WEIGHT (one reward is selected across the pool) rather than as an
    // independent yes/no gate, so once a drop is chosen it must always yield its stack. EMPTY only on a blank/unknown
    // id or a zero count.
    public ItemStack build(RandomSource random) {
        ResourceLocation loc = ResourceLocation.tryParse(itemId == null ? "" : itemId);
        Item item = loc == null ? null : ForgeRegistries.ITEMS.getValue(loc);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return ItemStack.EMPTY;
        }
        int lo = Math.max(0, Math.min(minCount, maxCount));
        int hi = Math.max(0, Math.max(minCount, maxCount));
        int count = hi <= lo ? lo : lo + random.nextInt(hi - lo + 1);
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item, count);
        if (nbt != null && !nbt.isBlank()) {
            try {
                stack.setTag(net.minecraft.nbt.TagParser.parseTag(nbt));
            } catch (Exception ignored) {
                // bad SNBT, just drop the plain item
            }
        }
        return stack;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(itemId == null ? "" : itemId);
        buf.writeInt(minCount);
        buf.writeInt(maxCount);
        buf.writeFloat(chance);
        buf.writeUtf(nbt == null ? "" : nbt);
    }

    public static CustomDrop decode(FriendlyByteBuf buf) {
        CustomDrop d = new CustomDrop();
        d.itemId = buf.readUtf();
        d.minCount = buf.readInt();
        d.maxCount = buf.readInt();
        d.chance = buf.readFloat();
        d.nbt = buf.readUtf();
        return d;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Item", itemId == null ? "" : itemId);
        tag.putInt("Min", minCount);
        tag.putInt("Max", maxCount);
        tag.putFloat("Chance", chance);
        if (nbt != null && !nbt.isBlank()) {
            tag.putString("Nbt", nbt);
        }
        return tag;
    }

    public static CustomDrop load(CompoundTag tag) {
        CustomDrop d = new CustomDrop();
        d.itemId = tag.getString("Item");
        d.minCount = tag.contains("Min") ? tag.getInt("Min") : 1;
        d.maxCount = tag.contains("Max") ? tag.getInt("Max") : d.minCount;
        d.chance = tag.contains("Chance") ? tag.getFloat("Chance") : 100.0f;
        d.nbt = tag.getString("Nbt");
        return d;
    }
}
