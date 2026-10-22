package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;

import net.shurui.shuruisutilities.compat.dmz.BallRescatter;

/**
 * Tells {@link BallRescatter} when a wish dragon is removed, at the same call DragonMineZ uses to decide its own
 * post-wish re-scatter ({@code DragonWishEntity.remove}, which runs {@code onDespawn} for a DISCARDED dragon).
 * {@link BallRescatter#onDragonRemoved} only records a debt when DMZ's own re-scatter will not run, so the vanilla
 * path is never doubled.
 *
 * <p>{@code remove} is a vanilla override, so the method name is remapped ({@code remap = true}); the target class
 * is DMZ's. {@code require = 0}: a DMZ change degrades to DMZ's own behaviour, never a load crash.
 */
@Mixin(targets = "com.dragonminez.common.init.entities.dragon.DragonWishEntity", remap = false)
public abstract class MixinDmzWishDragonRemove
{
    @Inject(method = "remove", at = @At("HEAD"), require = 0, remap = true)
    private void su$noteWishDragonRemoved(Entity.RemovalReason reason, CallbackInfo ci)
    {
        try
        {
            BallRescatter.onDragonRemoved((Entity) (Object) this, reason);
        }
        catch (Throwable ignored)
        {
        }
    }
}
