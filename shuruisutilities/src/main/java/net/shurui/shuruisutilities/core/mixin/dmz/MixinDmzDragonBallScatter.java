package net.shurui.shuruisutilities.core.mixin.dmz;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.shurui.shuruisutilities.worldborder.BorderClamp;

/**
 * Keep DragonMineZ's own dragon ball scatter inside the world border of the dimension it scatters in.
 *
 * <h2>The bug</h2>
 *
 * <p>{@code DragonBallsHandler.scatterDragonBalls} picks each ball's X/Z as {@code sharedSpawn +/- getDBSpawnRange()}
 * with no awareness of any border. When the configured spawn range reaches past the border, or the world spawn sits near
 * a border edge, a star lands OUTSIDE the border and cannot be reached without the bypass permission. This governs both
 * the first spawn AND DragonMineZ's post-wish rescatter (via {@code DragonWishEntity.onDespawn}), because both call
 * {@code scatterDragonBalls}.
 *
 * <h2>The fix, at the source</h2>
 *
 * <p>The chosen X/Z is materialised exactly once inside {@code scatterDragonBalls} as {@code new BlockPos(x, 0, z)} (the
 * dummy-Y target that is stored into the pending map, drives the radar, and is later resolved to a surface by
 * {@code generateBallSafely}). Redirecting that single constructor call is the one chokepoint that reaches every
 * downstream use: pending storage, radar markers, and physical placement all read this position. We clamp the X/Z into
 * the effective border ({@link BorderClamp}, the intersection of the vanilla and the enabled suite border, 16 blocks
 * inside) and keep the dummy Y untouched.
 *
 * <p>Clamping (rather than reject-and-reroll) is O(1), never loops even when the configured range dwarfs the border, and
 * loads no chunk, so it is safe on the server thread. The suite sets (Black Star, Super, Cerulean) scatter zero copies
 * (see {@code MixinDmzDragonBallCopies}) so this never fires for them; it matters for earth and namek.
 *
 * <p>remap=false: the target class, the method and the {@code BlockPos} constructor are matched by their descriptors,
 * not the vanilla refmap. require=0 per the standing rule for DMZ mixins: if DragonMineZ renames the method or changes
 * how it builds the target position, the redirect degrades to a no-op (balls scatter as before, possibly outside the
 * border) rather than crashing mod load. The handler is fully guarded and falls back to the original position on any
 * failure.
 */
@Mixin(targets = "com.dragonminez.server.events.DragonBallsHandler", remap = false)
public abstract class MixinDmzDragonBallScatter
{
    /** How far inside the border a scattered ball must land, in blocks. */
    private static final int SU_BORDER_MARGIN = 16;

    @Redirect(
            method = "scatterDragonBalls",
            at = @At(value = "NEW", target = "(III)Lnet/minecraft/core/BlockPos;"),
            require = 0,
            remap = false)
    private static BlockPos su$clampScatterTarget(int x, int y, int z, ServerLevel level, String setId)
    {
        try
        {
            int[] clamped = BorderClamp.clampInside(level, x, z, SU_BORDER_MARGIN);
            return new BlockPos(clamped[0], y, clamped[1]);
        }
        catch (Throwable t)
        {
            return new BlockPos(x, y, z);
        }
    }
}
