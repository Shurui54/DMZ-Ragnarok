package net.shurui.shuruisutilities.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;
import net.shurui.shuruisutilities.regions.RegionEventHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Stops a dragon ball being captured into an Applied Energistics 2 spatial storage cell.
 *
 * <p>A spatial cell moves a whole cube of the world into a private dimension. The one hook that gates that is
 * {@code appeng.items.storage.SpatialStorageCellItem#doSpatialTransition(ItemStack, ServerLevel, BlockPos, BlockPos,
 * int)}: the Spatial IO Port calls it with the region's {@code min}/{@code max} corners (the 1-block matrix frame
 * included), it validates size and plot, then calls {@code SpatialStorageHelper.swapRegions} to swap the interior
 * with the cell dimension. Injecting the HEAD lets us scan the interior BEFORE any swap and refuse the whole
 * transition (return false) if it holds a ball, so nothing is moved, no power is spent, and the cell stays in the
 * input slot. This is the deliberate choice the owner offered ("refuse the whole transition"): it is one hook that
 * covers BOTH capture routes (a placed ball block and a dropped ball item entity), it can never half-move a set, and
 * it leaves the balls exactly where they are in the real world, so the radar / one-copy-per-ball logic is untouched.
 *
 * <h2>The scanned region</h2>
 *
 * <p>{@code swapRegions} is called with source origin {@code (min+1)} and scale {@code (max-min-2)} on each axis, so
 * the cube it actually swaps is the inclusive interior {@code [min+1 .. max-1]}, i.e. everything inside the matrix
 * frame. We scan exactly that: every block through {@link RegionEventHandler#isDragonBall(net.minecraft.world.level.block.state.BlockState)}
 * (the registry-path match that covers DMZ's {@code dball1..7}, the added blackstar / super / cerulean sets and the
 * corrupted set, needs no DMZ API so it keeps working if DMZ shifts, and never false-matches the bag because that is
 * not a block), and every {@link ItemEntity} in the same box through {@link DragonBallSets#isDragonBall(ItemStack)}
 * (the precise item test, so a dropped bag is not mistaken for a ball). First hit refuses and stops. The scan is
 * O(volume) but runs only on a deliberate, rare transition, and AE2 itself walks the same cube during the swap, so it
 * adds no worse than the work AE2 was about to do anyway.
 *
 * <p>ME network storage is deliberately out of scope: a dragon ball cannot be inserted into an item handler (the
 * Sophisticated / vanilla containment mixins and DMZ's own no-item nature keep it out of any ME import path), so the
 * only realistic way a ball reaches a spatial cell is as a placed block or an item entity inside the captured cube,
 * which is exactly what this covers.
 *
 * <h2>Optionality</h2>
 *
 * <p>{@link Pseudo &#64;Pseudo} + {@code targets} string + {@code remap = false}: AE2 is an optional dependency and
 * is not on the compile classpath, so it is named by string and never classloaded when absent (the non-required
 * compat config skips it). {@code remap = false} because the target class and the {@code doSpatialTransition} method
 * name are AE2 names, not obfuscated; the vanilla parameter TYPES in the descriptor are Mojmap class names, valid
 * verbatim in production. The handler body touches only vanilla and SU code, so no AE2 type is referenced here.
 * {@code require = 0} so a rename in a future AE2 build degrades to "no guard" rather than crashing mod load.
 */
@Pseudo
@Mixin(targets = "appeng.items.storage.SpatialStorageCellItem", remap = false)
public abstract class MixinAe2SpatialTransition
{
    @Inject(
        method = "doSpatialTransition(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/server/level/ServerLevel;"
                + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;I)Z",
        at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$refuseBallCapture(ItemStack is, ServerLevel level, BlockPos min, BlockPos max, int playerId,
            CallbackInfoReturnable<Boolean> cir)
    {
        if (level == null || min == null || max == null)
        {
            return;
        }
        // The swapped interior is the inclusive cube strictly inside the matrix frame: [min+1 .. max-1] on each axis.
        int x0 = min.getX() + 1, y0 = min.getY() + 1, z0 = min.getZ() + 1;
        int x1 = max.getX() - 1, y1 = max.getY() - 1, z1 = max.getZ() - 1;
        if (x1 < x0 || y1 < y0 || z1 < z0)
        {
            return; // degenerate / empty region: nothing to guard.
        }

        // Placed ball blocks anywhere in the cube.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++)
        {
            for (int y = y0; y <= y1; y++)
            {
                for (int z = z0; z <= z1; z++)
                {
                    if (RegionEventHandler.isDragonBall(level.getBlockState(cursor.set(x, y, z))))
                    {
                        su$refuse(cir, level, min, max, "a placed dragon ball at " + x + ", " + y + ", " + z);
                        return;
                    }
                }
            }
        }

        // Ball item entities lying in the cube (a dropped or scattered ball waiting to be picked up).
        AABB box = new AABB(x0, y0, z0, x1 + 1.0D, y1 + 1.0D, z1 + 1.0D);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box))
        {
            if (DragonBallSets.isDragonBall(item.getItem()))
            {
                su$refuse(cir, level, min, max, "a dropped dragon ball item entity");
                return;
            }
        }
    }

    private static void su$refuse(CallbackInfoReturnable<Boolean> cir, ServerLevel level, BlockPos min, BlockPos max,
            String what)
    {
        cir.setReturnValue(false);
        LoggingHandler.sulog.info(
                "[dragonball] Refused an AE2 spatial transition in {} for region {}..{}: it contains {}. "
                        + "Dragon balls cannot be captured into a spatial cell.",
                level.dimension().location(), min, max, what);
    }
}
