package net.shurui.shuruisutilities.core.mixin.entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

import net.shurui.shuruisutilities.hologram.HologramManager;

/**
 * Holograms do not move once they are in the world.
 *
 * <p>A hologram line is a vanilla {@code TextDisplay} (its item an {@code ItemDisplay}) tagged {@link HologramManager#TAG}.
 * Display entities have no physics of their own, but DragonMineZ moves whatever its techniques catch by setting the
 * position directly: the Super Dragon Fist drags every entity in its box to the caster each tick
 * ({@code AbstractKiProjectile.holdTargetAtCaster}), strike attacks reposition their target, and to DragonMineZ a
 * display entity counts as a hostile target. So the text and item lines followed the fist, while a picture line (the
 * DMZ logo is a GIF billboard, drawn by the client at a fixed point with no entity behind it) never moved.
 *
 * <p>Every way to move an entity ends in {@code setPosRaw}, so refusing it for a tagged display that is already in the
 * world pins it against all of them, including holograms already standing in saved worlds (they carry the tag on disk).
 * Placement is unaffected: the hologram manager positions a display BEFORE adding it, and loading one from its chunk
 * sets the position before it is added, both while {@code isAddedToWorld()} is still false. Server side only; clients
 * keep whatever the server sends. Cheapest test first, since this runs on every position write of every entity.
 */
@Mixin(Entity.class)
public abstract class MixinEntityHologramPin
{
    @Inject(method = "setPosRaw(DDD)V", at = @At("HEAD"), cancellable = true)
    private void su$pinHologram(double x, double y, double z, CallbackInfo ci)
    {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Display) || !self.isAddedToWorld())
            return;
        if (self.level().isClientSide || !self.getTags().contains(HologramManager.TAG))
            return;
        ci.cancel();
    }
}
