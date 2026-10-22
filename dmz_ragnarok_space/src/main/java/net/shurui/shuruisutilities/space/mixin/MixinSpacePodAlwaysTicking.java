package net.shurui.shuruisutilities.space.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.common.init.entities.SpacePodEntity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Keeps a space pod ticking on the CLIENT while it is in the space dimension, even inside a chunk the client has not
 * received yet. This is the main cause of the pod stutter.
 *
 * <p>The pilot's client moves the pod itself (vanilla vehicle model: the controlling client runs {@code travel} and
 * reports {@code ServerboundMoveVehiclePacket}). But the client's entity storage only ticks entities in chunks it has
 * loaded: the moment the pod crosses into a chunk that has not arrived, {@code TransientEntitySectionManager} drops it
 * from the ticking list ({@code onTickingEnd}) and the whole stack, pod and pilot, freezes until that chunk lands, then
 * lurches on. At pod speed (2 to 12+ blocks a tick) the pod outruns chunk generation and sending all the time, so this
 * happened over and over. Measured in the dev harness at 12 blocks a tick: 27 of 50 ticks frozen, 42 of 50 inside a
 * chunk the client did not have.
 *
 * <p>Vanilla already exempts players this way ({@code Player.isAlwaysTicking}). Space is open void, so moving through a
 * chunk before its (almost always empty) data arrives is harmless, and the server still collides the reported move
 * against its own copy of the world. Limited to SpacePodEntity, client side, in the space dimension only: a pod over a
 * planet surface or the overworld keeps vanilla's freeze, so it can never fly into terrain that has not loaded. The
 * answer is constant for a given pod instance (an entity never changes level), so the client's ticking bookkeeping
 * stays consistent.
 */
@Mixin(Entity.class)
public abstract class MixinSpacePodAlwaysTicking
{
    @Inject(method = "isAlwaysTicking", at = @At("HEAD"), cancellable = true, require = 0)
    private void dmzr$podAlwaysTicksInSpace(CallbackInfoReturnable<Boolean> cir)
    {
        if (!((Object) this instanceof SpacePodEntity pod))
        {
            return;
        }
        try
        {
            Level level = pod.level();
            if (level != null && level.isClientSide && SpaceKeys.isSpace(level))
            {
                cir.setReturnValue(true);
            }
        }
        catch (Throwable ignored)
        {
            // vanilla answer
        }
    }
}
