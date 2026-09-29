package net.shurui.shuruisutilities.core.mixin.entity.player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.protection.ProtectionPerms;
import net.shurui.shuruisutilities.util.PlayerInfo;

import net.minecraft.world.entity.player.Player;

@Mixin(Player.class)
public abstract class MixinPlayerEntity
{

    /**
     * Custom permissions for command blocks
     *
     * @author Shurui
     * @reason stuff
     */
    @Inject(method = "canUseGameMasterBlocks", at = @At("HEAD"), cancellable = true)
    public void canUseGameMasterBlocks2(CallbackInfoReturnable<Boolean> cir)
    {
        // SU not initialized yet: leave vanilla behaviour untouched.
        if (APIRegistry.perms == null)
        {
            return;
        }
        cir.setReturnValue(((Player) (Object) this).isCreative()
                && APIRegistry.perms.checkPermission((Player) (Object) this, ProtectionPerms.COMMANDBLOCK_PERM));
    }

    /**
     * Solve for noClip functionality
     *
     * @author Shurui
     * @reason stuff
     */
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isSpectator()Z"))
    public boolean onUpdate_NoClip(Player _this)
    {
        return _this.isSpectator() || PlayerInfo.get(_this).isNoClip();
    }
}
