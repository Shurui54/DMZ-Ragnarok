package net.shurui.shuruisutilities.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gravel, sand and every other falling block stay put inside OUR dimensions.
 *
 * <h2>Why</h2>
 * The authored worlds are builds: gravel roads, sand paths, sandstone walls, structures with gravel packed into
 * them. Falling-block physics treats all of that as loose material, so the first block update anywhere near it
 * (a player mining, an explosion, a neighbouring update from a redstone tick or a chunk edge) drops a column of
 * road into whatever is underneath and leaves a hole in a build nobody touched. In a hand-built city that is
 * damage, not physics.
 *
 * <h2>Where it applies</h2>
 * Keyed on the dimension NAMESPACE rather than a list of dimension ids: every dimension this suite registers is
 * under {@code dmz_ragnarok}, and every one of them is an authored or generated world of ours. That covers the
 * planets, the dungeon dimensions and the private worlds without a list to maintain, and it leaves
 * {@code minecraft:} and {@code dragonminez:} dimensions completely alone, so vanilla gravel behaviour and any
 * gravel-based contraption a player builds in the ordinary world are untouched.
 *
 * <h2>Why {@code tick} and not {@code onPlace}</h2>
 * {@code onPlace} and {@code updateShape} only SCHEDULE the check; the fall itself is spawned in {@code tick}.
 * Cancelling here catches every route in, including ticks that were already scheduled before this ran, which a
 * placement-time cancel would miss.
 */
@Mixin(FallingBlock.class)
public abstract class MixinFallingBlockAnchored
{
    /** The namespace every dimension this suite registers lives under. */
    private static final String SU_DIMENSION_NAMESPACE = "dmz_ragnarok";

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void su$holdBuildsTogether(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                       CallbackInfo ci)
    {
        if (level == null)
            return;
        if (SU_DIMENSION_NAMESPACE.equals(level.dimension().location().getNamespace()))
        {
            ci.cancel();
            return;
        }
        // Blocks held by the world physics module (the overworld by default) or by a region's sand-fall deny are
        // refused HERE too, not only by FallingBlockGuard's join-event cancel. That guard let vanilla start the fall
        // first: build a FallingBlockEntity, remove the block, then put the block back and cancel the entity. Putting
        // a falling block back schedules its next fall check two ticks later, so every unsupported sand or gravel
        // block in a loaded chunk looped for ever. On 2026-09-12 that was about 70,000 entities and 140,000 block
        // changes a second on each open world shard. Refusing in tick builds nothing, changes nothing and schedules
        // nothing, so a held block goes quiet until a neighbour actually changes.
        if (net.shurui.shuruisutilities.world.FallingBlockGuard.held(level, pos))
        {
            ci.cancel();
        }
    }
}
