package net.shurui.shuruisutilities.core.mixin.entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.api.key.ModerationHooks;
import net.shurui.shuruisutilities.util.events.entity.PressurePlateEvent;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;

@Mixin(Entity.class)
public abstract class MixinEntity
{
    /**
     * Send pressure-plate event on plate depress
     *
     * @author Shurui
     * @reason we want to add perms so players can't activate redstone in protected areas without permission
     */
    @Inject(method = "isIgnoringBlockTriggers()Z", at = @At("RETURN"), cancellable = true)
    public void isSUIgnoringBlockTriggers(CallbackInfoReturnable<Boolean> callback)
    {
        if (MinecraftForge.EVENT_BUS.post(new PressurePlateEvent((Entity) (Object) this)))
        {
            callback.setReturnValue(true);
        }
    }

    /**
     * Custom fix afk players being pushed by entities
     *
     * @author Shurui
     * @reason fix afk players being pushed by entities
     */
    @Inject(method = "canBeCollidedWith()Z", at = @At("RETURN"), cancellable = true)
    public void isAfkPlayer(CallbackInfoReturnable<Boolean> callback)
    {
        if (((Entity) (Object) this) instanceof Player)
        {
            if (ModerationHooks.isAfk(UserIdent.get((Player) (Object) this)))
            {
                callback.setReturnValue(true);
                LoggingHandler.sulog.debug("Prevented afk player from being moved");
            }
        }
    }
}
