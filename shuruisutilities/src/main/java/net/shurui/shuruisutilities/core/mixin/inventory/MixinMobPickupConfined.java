package net.shurui.shuruisutilities.core.mixin.inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.dragonballbag.DragonBallConfine;

/**
 * No mob picks up a dropped dragon ball or dragon ball bag. Wraps the {@code wantsToPickUp} check inside
 * {@code Mob.aiStep}'s item pickup loop, so it holds for every mob that uses the vanilla loop, including modded mobs and
 * mobs that override {@code wantsToPickUp} itself. Mobs that pick items up through their own AI instead (villagers,
 * piglins, allays) are caught by {@code DragonBallEntityGuard}, which takes the item back.
 */
@Mixin(Mob.class)
public abstract class MixinMobPickupConfined
{
    @Redirect(method = "aiStep",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Mob;wantsToPickUp(Lnet/minecraft/world/item/ItemStack;)Z"),
            require = 0)
    private boolean su$neverPickUpConfined(Mob self, ItemStack stack)
    {
        return !DragonBallConfine.isConfined(stack) && self.wantsToPickUp(stack);
    }
}
