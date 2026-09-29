package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.guilds.GuildManager;
import net.shurui.shuruisutilities.guilds.model.Guild;
import net.shurui.shuruisutilities.guilds.model.GuildFlag;

/**
 * Stops DMZ ki attacks from destroying blocks inside SU guild claims.
 *
 * every ki terrain-destruction path in 2.1.2 (KiBlast/KiWave/KiLaser/KiExplosion via
 * AbstractKiProjectile.canKiDestroyBlock) funnels through the static gate MainGameRules.canKiGrief, so
 * cancelling it at HEAD covers all of them in one place. needed because DMZ's ki grief calls
 * destroyBlock/setBlock directly and fires no Forge BreakEvent/explosion, so SU's claim handlers never see it.
 * (the laser's separate real explosion IS a vanilla Explosion, already covered by GuildProtectionHandler;
 * reusing EXPLOSIONS here keeps ki grief and TNT grief behind one toggle.)
 *
 * server-side only, respects protectClaims, blocks only when the claiming guild has EXPLOSIONS off (false
 * default = protected). unclaimed land untouched.
 *
 * remap=false (DMZ non-Mojmap descriptors; vanilla param types remap fine). require=0 so a future rename/removal
 * of canKiGrief degrades gracefully instead of failing mod load.
 */
@Mixin(targets = "com.dragonminez.common.init.MainGameRules", remap = false)
public class MixinDmzKiGrief
{
    @Inject(method = "canKiGrief", at = @At("HEAD"), cancellable = true, require = 0)
    private static void su$blockKiGriefInClaims(Level level, BlockPos pos, Entity source,
                                                CallbackInfoReturnable<Boolean> cir)
    {
        if (level.isClientSide) return;
        if (!GuildManager.config().protectClaims) return;
        Guild owner = GuildManager.claimOwner(level, pos);
        if (owner != null && !owner.flag(GuildFlag.EXPLOSIONS)) cir.setReturnValue(false);
    }
}
