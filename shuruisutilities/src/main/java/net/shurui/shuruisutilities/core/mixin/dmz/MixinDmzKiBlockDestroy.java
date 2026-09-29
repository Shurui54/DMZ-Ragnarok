package net.shurui.shuruisutilities.core.mixin.dmz;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.shurui.shuruisutilities.regen.TerrainRegenService;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Catches blocks as DragonMineZ's ki attacks remove them, so the repair engine has something to restore toward.
 *
 * <p>This mixin exists because the obvious hook does not fire. Ki attacks do NOT destroy terrain through vanilla
 * explosions or through {@code BlockEvent.BreakEvent}: {@code AbstractKiProjectile} clears blocks itself, through its own
 * {@code setKiBlockToAir} and {@code destroyKiBlock}. Neither posts a Forge event, so a repair system listening to
 * explosions sees a ki blast flatten a hillside and is told nothing at all. That is exactly what happened the first time
 * the gamerule was switched on: the rule was live, the drain was running, and the queue was empty because nothing was
 * ever captured.
 *
 * <p>The hook is on {@code canKiDestroyBlock}, at RETURN and only when the answer was yes, NOT at the head of the two
 * destroy methods. That distinction is the whole correctness of this class. Both destroy methods open by asking
 * {@code canKiDestroyBlock} and bail out when it says no, so a HEAD hook on them ran BEFORE the decision and captured
 * blocks DMZ then refused to touch: bedrock and anything else over 1000 blast resistance, dragon ball blocks, and worst
 * of all the containers that {@link MixinDmzKiGriefContainer} had just protected. Each of those was snapshotted while
 * the block went on standing. Restore declines against a block that is still there, so it looked harmless, but the debt
 * does not expire: it waits, across chunk unloads and across restarts. Break that chest yourself an hour later and the
 * queue pays itself into the empty space, putting the chest back with the inventory it held at capture time, which is
 * every item in it a second time. Capturing exactly what DMZ is about to destroy is what closes that.
 *
 * <p>RETURN of the gate is also still the right MOMENT: the gate runs before the block is cleared, so the level holds
 * the old state, which is the thing a HEAD hook was there to get. One tick later that information is gone for good.
 *
 * <p>{@code require = 0} throughout, so if DragonMineZ renames or restructures this method the repair system quietly
 * captures nothing rather than the client or server failing to start. {@code MixinDmzKiGriefCapture} covers the same
 * ground from DMZ's game-rule gate, so the two are deliberately redundant and either one alone keeps regen working.
 */
@Mixin(targets = "com.dragonminez.common.init.entities.ki.AbstractKiProjectile", remap = false)
public abstract class MixinDmzKiBlockDestroy
{
    // require = 0 fails SILENTLY, so without this there is no way to tell "the mixin never wove" from "the mixin wove
    // and the queue is draining correctly". Logged once, on the first block this actually sees.
    private static final java.util.concurrent.atomic.AtomicBoolean SU_KI_CAPTURE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ARM CASCADE CAPTURE FOR THE SPAN OF EACH DESTROY.
    //
    // Capturing the block DMZ names is only half of a blast. Anything that was resting ON that block, or attached
    // to it, is taken by vanilla as a shape reaction instead, through Block.updateOrDestroy into
    // Level.destroyBlock, and never passes through canKiDestroyBlock at all. That is why a door whose ground was
    // blasted never came back: both of its halves went that way and neither was ever owed. It is also why those
    // blocks dropped their items, since DMZ's update flags do not include the suppress-drops bit.
    //
    // The window is opened here and closed on the way out so that MixinLevelRegenCascade only captures while a
    // destruction the repair engine is responsible for is actually running, and never during ordinary mining.
    // Both DMZ entry points are wrapped: setKiBlockToAir writes air directly, destroyKiBlock goes through
    // Level.destroyBlock, and either can cascade.
    @Inject(method = "setKiBlockToAir", at = @At("HEAD"), require = 0, remap = false)
    private void su$openCascadeWindowForAir(BlockPos pos, int flags, CallbackInfoReturnable<Boolean> cir)
    {
        TerrainRegenService.beginCascadeCapture();
    }

    @Inject(method = "setKiBlockToAir", at = @At("RETURN"), require = 0, remap = false)
    private void su$closeCascadeWindowForAir(BlockPos pos, int flags, CallbackInfoReturnable<Boolean> cir)
    {
        TerrainRegenService.endCascadeCapture();
    }

    @Inject(method = "destroyKiBlock", at = @At("HEAD"), require = 0, remap = false)
    private void su$openCascadeWindowForDestroy(BlockPos pos, boolean dropBlock,
                                                CallbackInfoReturnable<Boolean> cir)
    {
        TerrainRegenService.beginCascadeCapture();
    }

    @Inject(method = "destroyKiBlock", at = @At("RETURN"), require = 0, remap = false)
    private void su$closeCascadeWindowForDestroy(BlockPos pos, boolean dropBlock,
                                                 CallbackInfoReturnable<Boolean> cir)
    {
        TerrainRegenService.endCascadeCapture();
    }

    @Inject(method = "canKiDestroyBlock", at = @At("RETURN"), require = 0, remap = false)
    private void su$captureDestroyableKiBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        // Only when DMZ said yes. This is what gives the capture set the same shape as the destruction set, so a block
        // DMZ refused, or that SU's own guards refused through this same gate, is never remembered as owed.
        if (!Boolean.TRUE.equals(cir.getReturnValue()))
            return;
        su$capture(pos);
    }

    // Both entry points funnel here. Wrapped whole: remembering a block must never be able to stop the block breaking,
    // because a thrown exception inside a ki attack's block loop would take the attack down with it.
    private void su$capture(BlockPos pos)
    {
        try
        {
            if (!((Object) this instanceof Entity self))
                return;
            if (self.level() instanceof ServerLevel level)
            {
                if (SU_KI_CAPTURE_LOGGED.compareAndSet(false, true))
                {
                    LoggingHandler.sulog.info("[regen] Ki block destruction hook bound; terrain capture is live "
                            + "(this line appearing at all is the proof the mixin wove).");
                }
                TerrainRegenService.capture(level, pos);
            }
        }
        catch (Throwable ignored)
        {
        }
    }
}
