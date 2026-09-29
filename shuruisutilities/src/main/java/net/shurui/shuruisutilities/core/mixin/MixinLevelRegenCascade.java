package net.shurui.shuruisutilities.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.regen.TerrainRegenService;

/**
 * Remembers the blocks a blast knocks over indirectly, and stops them dropping as loot.
 *
 * <h2>The hole this closes</h2>
 * DragonMineZ destroys terrain itself, and SU captures each of those blocks off {@code canKiDestroyBlock}. But a
 * block that dies because its SUPPORT went is never offered to that gate at all. Vanilla removes it as a shape
 * reaction: {@code BlockState.updateNeighbourShapes} asks each neighbour to recompute, a block that cannot survive
 * answers with air, and {@code Block.updateOrDestroy} turns that answer into
 * {@code Level.destroyBlock(pos, (flags & 32) == 0, ...)}.
 *
 * <p>Two separate bugs came out of that one path, and both were reported as regen being broken:
 * <ul>
 *   <li><b>Doors were never put back.</b> Blast the ground from under a door and BOTH halves go as shape
 *       reactions, so neither was ever captured and there was no debt to repay. Capturing the halves as a pair
 *       (in {@code TerrainRegenService.capture}) could not help, because that only pairs them once ONE of them has
 *       been captured, and here neither ever was. The same was true of torches, ladders, rails and signs.</li>
 *   <li><b>Cascaded blocks dropped their items.</b> DMZ destroys its own blocks with drops off, but it does not
 *       set flag 32 on the update, so everything knocked over by the shape reaction dropped normally. The blocks
 *       are owed back, so dropping them hands out a duplicate of every one.</li>
 * </ul>
 *
 * <h2>Why this is not simply always on</h2>
 * Guarded by {@code capturingCascades()}, which is armed only for the span of a destruction the repair engine is
 * already responsible for. Without that, mining one dirt block under a torch would remember the torch and put it
 * back seconds later, which is exactly the "regeneration undoes player work" the whole system exists to avoid.
 *
 * <h2>Why the drop is suppressed only when the capture succeeded</h2>
 * The two answers have to agree. {@code capture} refuses anything holding items, so a chest knocked over by a
 * cascade is not owed, and it must therefore still drop its contents the ordinary way. Suppressing the drop on a
 * block nothing has promised to return would delete it outright.
 *
 * <p>Vanilla target, so it remaps normally. {@code Level.destroyBlock} is the same method
 * {@link MixinLevelDestroyBlock} already guards, and the two are independent: that one decides whether the block
 * may break at all, this one only decides whether what is breaking is remembered and whether it drops.
 */
@Mixin(Level.class)
public abstract class MixinLevelRegenCascade
{
    /**
     * Capture the block about to be destroyed, and take its drops away once it is owed.
     *
     * <p>Runs at HEAD, so the level still holds the old state, which is the thing being snapshotted. Done in one
     * hook rather than an inject to capture plus a separate one to change the flag, because the flag depends on
     * whether the capture succeeded and two injects have no guaranteed order between them.
     */
    @ModifyVariable(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean su$captureCascadeAndSilenceDrops(boolean dropBlock, BlockPos pos, boolean unusedDropBlock,
                                                     Entity breaker, int recursionLeft)
    {
        try
        {
            if (!TerrainRegenService.capturingCascades())
                return dropBlock;
            if (!((Object) this instanceof ServerLevel level))
                return dropBlock;
            // ASK THE PROTECTION GUARD FIRST, because it can refuse this very call.
            //
            // MixinLevelDestroyBlock injects at HEAD of this same method and cancels it for a protected or claimed
            // block, and there is no ordering between two HEAD hooks. Capturing without asking would snapshot a
            // block that then goes on standing, and that debt never expires: restore declines while the block is
            // there, waits through unloads and restarts, and pays itself into the gap the day somebody breaks it
            // normally, putting a second copy back. Asking the same question keeps the capture set exactly equal
            // to the destruction set, which is the rule the ki capture hook already follows.
            if (!net.shurui.shuruisutilities.protection.BlockBreakGuard.mayBreak(level, pos, breaker))
                return dropBlock;
            // Owed means it comes back, so it must not also be handed to the player as an item.
            return TerrainRegenService.capture(level, pos) ? false : dropBlock;
        }
        catch (Throwable t)
        {
            // Never let remembering a block stop it breaking: this sits in the middle of a blast's block loop.
            return dropBlock;
        }
    }
}
