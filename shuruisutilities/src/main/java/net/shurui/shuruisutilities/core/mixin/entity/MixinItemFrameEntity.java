package net.shurui.shuruisutilities.core.mixin.entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.shurui.shuruisutilities.util.events.entity.EntityAttackedEvent;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraftforge.common.MinecraftForge;

@Mixin(ItemFrame.class)
public class MixinItemFrameEntity
{
    // item frame bow-killing via EntityAttackedEvent
    @Inject(at = @At("HEAD"), method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", cancellable = true)
    public void hurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback)
    {
        EntityAttackedEvent event = new EntityAttackedEvent((ItemFrame) (Object) this, source, amount);
        if (MinecraftForge.EVENT_BUS.post(event))
        {
            callback.setReturnValue(event.result);
        }
    }
}
