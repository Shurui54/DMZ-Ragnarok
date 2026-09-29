package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.regen.TerrainRegenService;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The broad capture point for the repair engine: DragonMineZ's own permission gate for destroying a block with ki.
 *
 * <p>{@link MixinDmzKiBlockDestroy} hooks the two methods on {@code AbstractKiProjectile} that clear a block, and it
 * does weave and does run. What it does NOT cover is everything that destroys terrain without being a ki projectile at
 * all, most obviously the momentum impact handler, which craters the ground when a body is driven into it. This gate is
 * the one thing every ki destruction path in DMZ asks first, projectile or not, so hooking it covers all of them
 * including any DMZ adds later.
 *
 * <p>Captured on RETURN and only when the answer was yes, which gives the capture set the same shape as the destruction
 * set. It also means a block SU itself refused to let break, via {@code MixinDmzKiGrief} cancelling this same method for
 * guild claims, is never captured: cancelling at HEAD returns before this point, so prevention and repair still cover
 * disjoint sets rather than both claiming the same block.
 *
 * <p>Capturing the same position twice is free. The service keeps the FIRST snapshot for a position and ignores
 * repeats, so overlapping with {@code MixinDmzKiBlockDestroy} costs one hash lookup and changes nothing.
 */
@Mixin(targets = "com.dragonminez.common.init.MainGameRules", remap = false)
public abstract class MixinDmzKiGriefCapture
{
    // require = 0 fails SILENTLY, so this line appearing is the only proof the injector bound. Latched so it prints once.
    private static final AtomicBoolean SU_GRIEF_CAPTURE_LOGGED = new AtomicBoolean(false);

    @Inject(method = "canKiGrief", at = @At("RETURN"), require = 0, remap = false)
    private static void su$captureGriefedBlock(Level level, BlockPos pos, Entity source,
            CallbackInfoReturnable<Boolean> cir)
    {
        try
        {
            if (!Boolean.TRUE.equals(cir.getReturnValue()))
                return;
            if (!(level instanceof ServerLevel server))
                return;
            if (SU_GRIEF_CAPTURE_LOGGED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.info("[regen] Ki grief gate hook bound; every ki destruction path is now captured "
                        + "(this line appearing at all is the proof the mixin wove).");
            }
            TerrainRegenService.capture(server, pos);
        }
        catch (Throwable ignored)
        {
            // Remembering a block must never be able to stop the block breaking.
        }
    }
}
