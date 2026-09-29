package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

// shared contract for the two real crate blocks (crate_chest, crate_barrel). Both carry the same four-value tier
// property, so one client colour handler tints either block's latch/band and the conversion pass stamps the rolled
// tier onto either.
//
// A 0..3 property, not eight blocks: the tier only changes one decal's colour, so this keeps the registry at two blocks
// and one model + texture set each, tinted by CrateTier.color over a greyscale overlay.
public interface CrateBlock {

    // 0=common, 1=uncommon, 2=rare, 3=mythic (matches CrateTier.ordinal()). Stamped at conversion, fixed on the block.
    IntegerProperty TIER = IntegerProperty.create("tier", 0, 3);

    /**
     * Which of sixteen 22.5 degree steps the crate is turned to, like a banner. Sixteen not four (FACING) so a crate
     * can sit at an angle against a corner. Renderer-only. FACING stays on the chest for the generation pass that
     * copies it from the vanilla chest, and a generated crate's ROTATION derives from that facing.
     */
    IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 15);

    /** NBT key an item stack carries when it places one specific crate look rather than a derived one. */
    String VARIANT_TAG = "CrateVariant";

    /** Stamp a stack so placing it yields exactly this rarity and metal. */
    static net.minecraft.world.item.ItemStack withVariant(net.minecraft.world.item.ItemStack stack,
                                                          int tierOrdinal, int metalOrdinal) {
        return withVariant(stack, tierOrdinal, metalOrdinal, "");
    }

    /**
     * Stamp a stack so placing it yields exactly this rarity, metal and LOOK. The skin names a whole model, so a Toffy
     * crate can stand in as a dungeon crate wearing a different model. Blank means the usual crate_&lt;rarity&gt;_&lt;metal&gt;.
     */
    static net.minecraft.world.item.ItemStack withVariant(net.minecraft.world.item.ItemStack stack,
                                                          int tierOrdinal, int metalOrdinal, String skin) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("Tier", tierOrdinal);
        tag.putInt("Metal", metalOrdinal);
        if (skin != null && !skin.isEmpty()) {
            tag.putString("Skin", skin);
        }
        stack.getOrCreateTag().put(VARIANT_TAG, tag);
        return stack;
    }

    /** The colour crates that can stand in as hand-placed dungeon crates, in the order they appear in creative. */
    String[] SKINS = { "black", "blue", "cinder", "green", "purple", "red", "viking" };

    default int tierOrdinal(BlockState state) {
        return state.hasProperty(TIER) ? state.getValue(TIER) : 0;
    }

    /** The crate's turn in degrees, for the renderer. */
    static float rotationDegrees(BlockState state) {
        int step = state.hasProperty(ROTATION) ? state.getValue(ROTATION) : 0;
        return step * -22.5f;
    }

    /** The sixteen-step rotation that best matches where a placer is looking. */
    static int rotationFromYaw(float yRot) {
        return Math.floorMod(Math.round(yRot / 22.5f), 16);
    }
}
