package net.shurui.shuruisutilities.katchin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/**
 * Single source of truth for a katchi katchin tool's colour. The colour is stored as a small int on the stack's
 * NBT under {@link #NBT_KEY}; the model {@code overrides} pick a texture from it via the client-side property
 * function {@code shuruisutilities:colour} (see {@link KatchinClientEvents}), and the smithing result randomises
 * it here (see the katchi katchin tool items' {@code onCraftedBy}).
 *
 * <p>0 = blue_grey, 1 = orange, 2 = cream. A stack with no colour stored yet (a {@code /give} stack, or one from
 * before this feature existed) reads as {@link #DEFAULT} rather than a missing model.
 */
public final class KatchiKatchinColour
{
    private KatchiKatchinColour() {}

    // The single NBT key both the randomiser (write) and the model property function (read) agree on.
    public static final String NBT_KEY = "KatchiKatchinColour";

    public static final int COUNT = 3;
    public static final int DEFAULT = 0;

    // index-aligned to the stored int; used only to build registry/lang/model suffixes on the asset side.
    public static final String[] NAMES = { "blue_grey", "orange", "cream" };

    /** The colour stored on the stack, clamped into range, defaulting to {@link #DEFAULT} when absent. */
    public static int get(ItemStack stack)
    {
        if (stack == null)
        {
            return DEFAULT;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(NBT_KEY))
        {
            return DEFAULT;
        }
        return Mth.clamp(tag.getInt(NBT_KEY), 0, COUNT - 1);
    }

    /** Store an explicit colour, clamped into range. */
    public static void set(ItemStack stack, int colour)
    {
        if (stack == null)
        {
            return;
        }
        stack.getOrCreateTag().putInt(NBT_KEY, Mth.clamp(colour, 0, COUNT - 1));
    }

    /** Pick a fresh random colour among the three. Used when a katchi katchin tool is smithed. */
    public static void randomise(ItemStack stack, RandomSource random)
    {
        set(stack, random.nextInt(COUNT));
    }
}
