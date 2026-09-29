package net.shurui.shuruisutilities.racing.block;

import net.minecraft.world.level.block.Block;

/**
 * The start/finish line block: a checkered full block. In R0 it is a plain decorative block; a track's build step
 * (R2) lays a row of these across the start gate, and it is always a member of a track's surface set (so driving
 * over it is never off-road). No behaviour of its own.
 */
public class RaceFinishLineBlock extends Block
{
    public RaceFinishLineBlock(Properties properties)
    {
        super(properties);
    }
}
