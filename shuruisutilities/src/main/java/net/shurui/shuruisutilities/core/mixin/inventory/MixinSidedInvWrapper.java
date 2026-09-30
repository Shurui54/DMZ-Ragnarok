package net.shurui.shuruisutilities.core.mixin.inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.wrapper.SidedInvWrapper;

import net.shurui.shuruisutilities.dragonballbag.DragonBallConfine;

/**
 * The sided capability route into a vanilla {@link net.minecraft.world.WorldlyContainer} (a furnace face, a brewing
 * stand, and similar), the sided sibling of {@code MixinInvWrapper}. A pipe or hopper inserting a ball through a
 * particular face arrives here; refusing at the head (return the stack unchanged) closes that route.
 *
 * <p>No whitelist exception is needed: {@link SidedInvWrapper} only ever wraps a {@code WorldlyContainer}, and the
 * player's inventory is not one, so a ball reaching this method is always bound for a block container and is refused.
 *
 * <p>{@code remap = false}: {@code insertItem} is a Forge API name; the vanilla {@code ItemStack} parameter type is a
 * Mojmap class name valid verbatim in production. Fail-safe via {@link DragonBallSets#isDragonBall}.
 */
@Mixin(SidedInvWrapper.class)
public abstract class MixinSidedInvWrapper
{
    @Inject(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void su$blockDragonBallIntoSidedContainer(int slot, ItemStack stack, boolean simulate,
            CallbackInfoReturnable<ItemStack> cir)
    {
        // A sided wrapper only ever wraps a WorldlyContainer (a furnace face, brewing stand, ...), never the player
        // inventory, so a ball or the bag reaching here is always bound for a block container and is refused.
        if (DragonBallConfine.isConfined(stack))
        {
            cir.setReturnValue(stack);
        }
    }
}
