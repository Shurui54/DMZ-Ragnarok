package net.shurui.shuruisutilities.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.regions.RegionEventHandler;
import net.shurui.shuruisutilities.regions.RegionFlag;

/**
 * Holds sand and gravel in place, controlled by the {@code sand-fall} region flag.
 *
 * <h2>Why an event and not a mixin</h2>
 * A mixin on {@code FallingBlock} was tried first and never bound: it compiled, registered and silently did
 * nothing, because {@code require = 0} turns an unmatched target into a no-op rather than an error. A Forge
 * event always fires, so this cannot fail quietly the same way.
 *
 * <h2>Why the block is put back</h2>
 * By the time a {@link FallingBlockEntity} exists the block has ALREADY been removed from the world: the fall
 * is "remove block, spawn entity". Cancelling the entity on its own would therefore delete the sand rather
 * than hold it. So the block is written back at the entity's own position first, and only then is the entity
 * refused. The net effect is that nothing ever moved.
 *
 * <p>This runs on every block update, which is the point: an update sweep is exactly what collapses a built
 * map, and the guard is on the fall itself rather than on whatever scheduled it.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class FallingBlockGuard
{
    private FallingBlockGuard()
    {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFallingBlockSpawn(EntityJoinLevelEvent event)
    {
        if (event.getLevel().isClientSide())
        {
            return;
        }
        if (!(event.getEntity() instanceof FallingBlockEntity falling))
        {
            return;
        }

        Level level = event.getLevel();
        BlockPos pos = falling.blockPosition();
        if (!held(level, pos))
        {
            return;
        }

        // Put the block back where it left, THEN refuse the entity. The other order would leave a hole.
        BlockState state = falling.getBlockState();
        if (state != null && level.getBlockState(pos).canBeReplaced())
        {
            level.setBlock(pos, state, 2);
        }
        event.setCanceled(true);
    }

    // Denied by the region flag, or held everywhere by the module's overworld default.
    public static boolean held(Level level, BlockPos pos)
    {
        try
        {
            if (RegionEventHandler.hasRegions() && RegionEventHandler.worldFlagDenied(level, pos, RegionFlag.SAND_FALL))
            {
                return true;
            }
        }
        catch (Throwable ignored)
        {
            // a region lookup problem must not turn into sand deleting itself
        }
        return WorldPhysicsModule.holdFallingBlocks(level);
    }
}
