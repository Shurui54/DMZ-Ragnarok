package net.shurui.shuruisutilities.npcregion;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What counts as real ground, for the two features that place something on a region's terrain.
 *
 * <p>{@link NpcRegionManager} stands NPCs on it and {@code AirdropManager} rests crates on it. Both had the same
 * problem from opposite directions: scanning a column from above, the first thing you meet over a forest is the
 * canopy and over a town is the roof, and both are solid enough to pass a naive test. Neither feature refuses those
 * outright, since a region can legitimately be all treetops or all rooftops; they prefer this set and fall back to
 * whatever they found only when no attempt turned up anything better.
 *
 * <p>One definition rather than one per caller, so "what is ground" cannot drift into meaning two different things
 * in the same region.
 */
public final class GroundBlocks
{
    private GroundBlocks() {}

    /**
     * Natural ground: what something ought to be resting on when there is any choice in the matter.
     *
     * <p>Tags rather than a block list, so modded stone and modded dirt that declare themselves correctly are
     * included for free and the set does not need revisiting every time the pack changes.
     */
    public static boolean isPreferredGround(BlockState state)
    {
        return state.is(BlockTags.DIRT)                     // dirt, grass, podzol, mycelium, coarse + rooted dirt
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) // stone, granite, diorite, andesite, deepslate, tuff
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.TERRACOTTA)
                || state.is(BlockTags.NYLIUM)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.MUD)
                || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.SOUL_SAND)
                || state.is(Blocks.SOUL_SOIL)
                || state.is(Blocks.END_STONE);
    }
}
