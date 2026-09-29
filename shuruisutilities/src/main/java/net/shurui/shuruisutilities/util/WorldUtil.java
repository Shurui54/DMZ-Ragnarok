package net.shurui.shuruisutilities.util;

import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public abstract class WorldUtil
{

    // are the h blocks from [x,y,z] up all air/replaceable?
    public static boolean isFree(Level world, int x, int y, int z, int h)
    {
        int testedH = 0;
        for (int i = 0; i < h; i++)
        {
            BlockState state = world.getBlockState(new BlockPos(x, y + i, z));
            Block block = state.getBlock();
            if (block.isPossibleToRespawnInThis(state))
                testedH++;
        }
        return testedH == h;
    }

    // like isFree, but with replaceRock also allows non-tile-entity rock
    public static boolean isSafeToReplace(Level world, int x, int y, int z, int h, boolean replaceRock)
    {
        int testedH = 0;
        for (int i = 0; i < h; i++)
        {
            BlockPos pos = new BlockPos(x, y + i, z);
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            float hardness = block.getExplosionResistance();// .getBlockHardness(state, world, pos);
            // 1.20: Material was removed; use "requires correct tool" as a stone/rock heuristic
            boolean replaceable = replaceRock && (state.requiresCorrectToolForDrops() && hardness >= 0
                    && hardness <= 3 && world.getBlockEntity(pos) == null);
            if (block.isPossibleToRespawnInThis(state) || replaceable)
            {
                testedH++;
            }
        }

        return testedH == h;
    }

    // find a free spot of height h near [x,y,z]: if y is free, drop to the ground; else rise until one opens.
    public static int placeInWorld(Level world, int x, int y, int z, int h, boolean replaceRock)
    {
        if (y >= 0 && isSafeToReplace(world, x, y, z, h, false))
        {
            while (isSafeToReplace(world, x, y - 1, z, h, false) && y > 0)
                y--;
        }
        else
        {
            if (!ShuruisUtilities.isCubicChunksInstalled)
            {
                if (y < 0)
                    y = 0;
            }
            y++;
            while (y + h < world.getHeight() && !isSafeToReplace(world, x, y, z, h, replaceRock))
                y++;
        }
        if (y == 0)
            y = world.getHeight() - h;
        return y;
    }

    // height-2 variant
    public static int placeInWorld(Level world, int x, int y, int z)
    {
        return placeInWorld(world, x, y, z, 2, false);
    }

    public static WorldPoint placeInWorld(WorldPoint p)
    {
        return p.setY(placeInWorld(p.getWorld(), p.getX(), p.getY(), p.getZ()));
    }

    public static void placeInWorld(Player player)
    {
        WorldPoint p = placeInWorld(new WorldPoint(player));
        player.setPos(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
    }

}
