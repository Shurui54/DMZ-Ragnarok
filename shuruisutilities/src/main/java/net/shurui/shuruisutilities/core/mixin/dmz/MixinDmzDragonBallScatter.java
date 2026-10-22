package net.shurui.shuruisutilities.core.mixin.dmz;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.compat.dmz.ScatterGuard;
import net.shurui.shuruisutilities.shard.ShardDimensions;

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
 * <p>The chosen X/Z is materialised exactly once inside {@code addPendingBall} (2.1: inside {@code scatterDragonBalls};
 * 2.2 moved it so scatter, top-ups and respawns all share it) as {@code new BlockPos(x, 0, z)} (the
 * dummy-Y target that is stored into the pending map, drives the radar, and is later resolved to a surface by
 * {@code generateBallSafely}). Redirecting that single constructor call is the one chokepoint that reaches every
 * downstream use: pending storage, radar markers, and physical placement all read this position. We clamp the X/Z into
 * the effective border ({@link BorderClamp}, the intersection of the vanilla and the enabled suite border, 16 blocks
 * inside) and keep the dummy Y untouched.
 *
 * <p>Clamping (rather than reject-and-reroll) is O(1), never loops even when the configured range dwarfs the border, and
 * loads no chunk, so it is safe on the server thread.
 *
 * <p>The same seam also keeps a ball off claimed ground ({@link ScatterGuard}: guild claims, regions and
 * serverclaims): a pick that lands on one is re-rolled in DMZ's own square a bounded number of times.
 *
 * <p>The suite sets (Black Star, Super, Cerulean) scatter zero copies (see {@code MixinDmzDragonBallCopies}) so this
 * never fires for them; it matters for earth and namek.
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
    /**
     * A10: never scatter into a level whose world content this server does not own; and a set scattered into a level
     * it does not scatter in (it was summoned there) is owed to its home instead (see the body). Before that: A10, never
     * scatter into a level whose world content this server does not own (on the shard network every
     * server loads every dimension, so the SMP has an empty, never-visited copy of the overworld; DMZ's first spawn
     * and re-scatter used to lay a set there that nobody can reach, which the radar then pointed at).
     */
    @Inject(method = "scatterDragonBalls", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$onlyOwnedLevels(ServerLevel level, String setId, CallbackInfo ci)
    {
        try
        {
            if (level != null && !ShardDimensions.ownsContentIn(level.dimension()))
            {
                ci.cancel();
                return;
            }
            // A set summoned away from home (any set may be summoned where it is gathered): DMZ re-scatters it in the
            // dragon's level, from the wish dragon's despawn and from the abandoned-summon expiry, and returns at once
            // there because the set does not scatter in that level. Owe the re-scatter to the set's home instead.
            // DMZ's own start-up scatter and the suite's callers always pass a home level, so they never get here.
            com.dragonminez.common.dragonball.DragonBallSetDefinition def =
                    level == null ? null : com.dragonminez.common.dragonball.DragonBallDefinitions.getBallSet(setId);
            if (def != null && def.isNaturalSpawn() && def.getCopies() > 0 && !def.supportsDimension(level.dimension()))
            {
                net.shurui.shuruisutilities.compat.dmz.BallRescatter.request(level.getServer(), setId,
                        level.dimension().location() + " (summoned away from home)");
                ci.cancel();
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    @Redirect(
            method = "addPendingBall",
            at = @At(value = "NEW", target = "(III)Lnet/minecraft/core/BlockPos;"),
            require = 0,
            remap = false)
    private static BlockPos su$clampScatterTarget(int x, int y, int z, ServerLevel level,
            com.dragonminez.server.world.data.DragonBallSavedData data,
            com.dragonminez.common.dragonball.DragonBallSetDefinition definition, int star)
    {
        return ScatterGuard.pickTarget(level, definition == null ? null : definition.getId(), x, y, z);
    }
}
