package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.regen.TerrainRegenRule;
import net.shurui.shuruisutilities.regen.TerrainRegenService;

/**
 * Keeps ki attacks from destroying a container while terrain regen is active over it.
 *
 * <p>Every DMZ ki terrain-destruction path except the laser asks {@code MainGameRules.canKiGrief} before it clears a
 * block, exactly like {@link MixinDmzKiGrief}, so denying it at HEAD covers KiBlast, KiWave, KiExplosion and the
 * momentum impact crater in one place. We deny only for CONTAINERS, and only where regen is switched on for that
 * position, because a broken chest is the one case regen cannot heal cleanly: its contents spill through vanilla
 * {@code onRemove -> Containers.dropContents} the instant the block is removed, which is independent of any drop flag,
 * and the regen snapshot then restores the chest still holding those same items, duplicating the whole inventory.
 * Leaving the container standing is the only way to keep both the items and the count honest.
 *
 * <p>The laser is handled separately: its real vanilla {@code Explosion} never consults this gate, so
 * {@code TerrainRegenHandler.onExplosion} drops container positions out of the blast list instead.
 *
 * <p>Ordering is for performance. {@code canKiGrief} runs once per block in destruction loops, so the cheap regen
 * predicate is checked first, then {@code hasBlockEntity()} inside {@code holdsItems} short circuits before we ever
 * call {@code getBlockEntity}, so an air block or plain terrain block never pays for a block entity fetch.
 *
 * <p>What counts as an item holder is {@code TerrainRegenService.holdsItems}, so this guard and the two in
 * {@code TerrainRegenHandler} answer the question one way instead of three. It tests {@code Clearable} rather than
 * {@code Container} or {@code BaseContainerBlockEntity}: the abstract class misses modded storage that implements the
 * interface directly, and {@code Container} in turn misses the campfire and the lectern, which drop what they hold on
 * removal without being containers.
 *
 * <p>remap=false (DMZ non-Mojmap descriptors; vanilla param types remap fine). require=0 so a rename or removal of
 * canKiGrief degrades gracefully instead of failing mod load, matching the three existing DMZ ki mixins.
 */
@Mixin(targets = "com.dragonminez.common.init.MainGameRules", remap = false)
public class MixinDmzKiGriefContainer
{
    @Inject(method = "canKiGrief", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$protectContainerDuringRegen(Level level, BlockPos pos, Entity source,
                                                       CallbackInfoReturnable<Boolean> cir)
    {
        try
        {
            if (level == null || pos == null || level.isClientSide)
                return;
            // A grave, and so a dragon ball totem, is denied wherever it stands, NOT only where regen is on: unlike a
            // container the hazard is not duplication, it is that the blocks are the only handle on the grave's
            // contents and a ki blast removes them with no drops at all. Cheap: isGraveBlock tests the block state
            // before it ever looks a grave up.
            if (net.shurui.shuruisutilities.grave.GraveManager.isGraveBlock(level, pos))
            {
                cir.setReturnValue(false);
                return;
            }
            // Cheap first: the regen predicate. If regen is not on for this position there is nothing to protect and we
            // let the normal grief answer stand. holdsItems does its own short circuit on hasBlockEntity, so a block
            // with no block entity still never pays for a block entity fetch.
            if (!TerrainRegenRule.isEnabledAt(level, pos))
                return;
            if (TerrainRegenService.holdsItems(level, pos))
                cir.setReturnValue(false);
        }
        catch (Throwable ignored)
        {
            // Protecting a container must never be able to crash the grief gate. On any failure we simply do not
            // intervene and DMZ's own answer is used.
        }
    }
}
