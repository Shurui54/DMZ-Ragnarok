package net.shurui.dev.shuruis_dmz_dungeons.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import net.shurui.dev.shuruis_dmz_dungeons.event.DungeonRuleEvents;

/**
 * Stops DMZ ki attacks from breaking blocks in the dungeon dimensions (legacy superflat + themed floors) when
 * DungeonRules.kiBlockDestruction is off, without ever writing a gamerule.
 *
 * DMZ ki grief calls Level.destroyBlock/setBlock directly and fires no Forge BreakEvent or explosion, so the
 * ExplosionEvent.Detonate / LivingDestroyBlockEvent handlers never see it. Every ki terrain path in 2.1.3
 * funnels through the static MainGameRules.canKiGrief, so cancelling it at HEAD covers all of them at once.
 * We do NOT flip DMZ's ki-grief gamerules instead: those are shared server-wide (Level.getGameRules delegates
 * to the single WorldData via DerivedLevelData) and persist to level.dat, so writing them would permanently
 * change the rule for every dimension.
 *
 * suppressesKiBlockDamage is dimension-scoped (isAnyDungeon + server-side + the rule), so non-dungeon levels
 * are untouched and gamerules are never read or written.
 *
 * remap=false (DMZ non-Mojmap descriptors; vanilla params remap fine). require=0 so a future rename or removal
 * of canKiGrief degrades gracefully instead of failing mod load.
 */
@Mixin(targets = "com.dragonminez.common.init.MainGameRules", remap = false)
public class MixinDmzKiGriefDungeon {

    @Inject(method = "canKiGrief", at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdd$blockKiGriefInDungeon(Level level, BlockPos pos, Entity source,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (DungeonRuleEvents.suppressesKiBlockDamage(level)) {
            cir.setReturnValue(false);
        }
    }
}
