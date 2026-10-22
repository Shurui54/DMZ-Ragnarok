package net.shurui.shuruisutilities.dragonballbag;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import net.shurui.shuruisutilities.regions.RegionEventHandler;

import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/**
 * Answers "does anything in this block cube hold a dragon ball or a dragon ball bag", for code that moves a whole piece
 * of the world somewhere else (AE2 spatial storage). Nothing in such a cube is an allowed home, so the test is
 * deliberately blanket: a placed ball block, any block entity's inventory, and any entity at all (dropped items, item
 * frames, minecart and boat chests, mobs holding one, players carrying one). Contents are checked with
 * {@link DragonBallConfine#carriesConfined}, so a ball inside a shulker box or backpack inside a chest is found too.
 *
 * <p>Block entities are read from the already loaded chunks the cube covers; a chunk that is not loaded is skipped
 * (the caller is moving loaded terrain, so the cube's chunks are loaded). Read-only and never throws.
 */
public final class DragonBallRegionScan
{
    private DragonBallRegionScan()
    {
    }

    /**
     * Describes the first ball or bag found in the inclusive cube {@code [x0..x1] x [y0..y1] x [z0..z1]}, or returns
     * null when the cube is clean.
     */
    public static String findConfined(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1)
    {
        try
        {
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        if (RegionEventHandler.isDragonBall(level.getBlockState(cursor.set(x, y, z))))
                            return "a placed dragon ball at " + x + ", " + y + ", " + z;

            for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            {
                for (int cz = z0 >> 4; cz <= z1 >> 4; cz++)
                {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null)
                        continue;
                    for (BlockEntity be : chunk.getBlockEntities().values())
                    {
                        BlockPos p = be.getBlockPos();
                        if (p.getX() < x0 || p.getX() > x1 || p.getY() < y0 || p.getY() > y1 || p.getZ() < z0
                                || p.getZ() > z1)
                            continue;
                        if (holdsConfined(be))
                            return "a dragon ball or bag inside the block at " + p.getX() + ", " + p.getY() + ", "
                                    + p.getZ();
                    }
                }
            }

            AABB box = new AABB(x0, y0, z0, x1 + 1.0D, y1 + 1.0D, z1 + 1.0D);
            for (Entity e : level.getEntitiesOfClass(Entity.class, box))
            {
                if (holdsConfined(e))
                    return "a dragon ball or bag carried by " + e.getName().getString();
            }
        }
        catch (Throwable t)
        {
            // a read failure must not break the caller; treat as clean, the other guards still apply.
        }
        return null;
    }

    private static boolean holdsConfined(BlockEntity be)
    {
        if (be instanceof Container c && containerHolds(c))
            return true;
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).map(DragonBallRegionScan::handlerHolds)
                .orElse(false);
    }

    private static boolean holdsConfined(Entity e)
    {
        if (e instanceof ItemEntity item)
            return DragonBallConfine.carriesConfined(item.getItem());
        if (e instanceof ItemFrame frame && DragonBallConfine.carriesConfined(frame.getItem()))
            return true;
        if (e instanceof net.minecraft.world.entity.player.Player player)
        {
            if (containerHolds(player.getInventory()))
                return true;
        }
        else if (e instanceof LivingEntity living)
        {
            for (ItemStack s : living.getAllSlots())
                if (DragonBallConfine.carriesConfined(s))
                    return true;
        }
        if (e instanceof Container c && containerHolds(c))
            return true;
        if (e instanceof LivingEntity living)
        {
            IItemHandler curios = CuriosApi.getCuriosInventory(living)
                    .map(ICuriosItemHandler::getEquippedCurios).orElse(null);
            if (curios != null && handlerHolds(curios))
                return true;
        }
        return e.getCapability(ForgeCapabilities.ITEM_HANDLER, null).map(DragonBallRegionScan::handlerHolds)
                .orElse(false);
    }

    private static boolean containerHolds(Container c)
    {
        for (int i = 0; i < c.getContainerSize(); i++)
            if (DragonBallConfine.carriesConfined(c.getItem(i)))
                return true;
        return false;
    }

    private static boolean handlerHolds(IItemHandler h)
    {
        for (int i = 0; i < h.getSlots(); i++)
            if (DragonBallConfine.carriesConfined(h.getStackInSlot(i)))
                return true;
        return false;
    }
}
