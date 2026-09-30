package net.shurui.shuruisutilities.core.mixin.inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.wrapper.InvWrapper;

import net.shurui.shuruisutilities.dragonballbag.DragonBallConfine;

/**
 * The capability route into a vanilla-style {@link Container}. Forge exposes a {@link Container} to automation as an
 * {@link net.minecraftforge.items.IItemHandler} through {@link InvWrapper}, so a pipe / conduit / import bus / logistical
 * transporter inserting a ball into a chest, an Iron Chest, a Lootr chest, a barrel or a furniture container arrives
 * here rather than at {@code HopperBlockEntity}. Refusing at the head (return the stack unchanged) closes that route.
 *
 * <p>The one allowed home this class can wrap is the player's own inventory: Forge's {@code PlayerInvWrapper} /
 * {@code PlayerMainInvWrapper} are built over an {@code InvWrapper} whose backing {@link Container} is the player's
 * {@link Inventory}. So the sole exception is {@code getInv() instanceof Inventory}, which keeps every player-inventory
 * capability insert working while refusing every other container. The dragon ball bag is never an {@code InvWrapper}
 * (it is a marked {@code ItemStackHandler}, handled by {@code MixinForgeItemStackHandler}), so it needs no exception
 * here.
 *
 * <p>{@code remap = false}: {@code insertItem} and {@code getInv} are Forge API names, not obfuscated; the vanilla
 * {@code ItemStack} parameter type is a Mojmap class name valid verbatim in production. Fail-safe via
 * {@link DragonBallSets#isDragonBall}, which returns false on any DMZ read error.
 */
@Mixin(InvWrapper.class)
public abstract class MixinInvWrapper
{
    @Shadow(remap = false)
    public abstract Container getInv();

    @Inject(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void su$blockDragonBallIntoContainer(int slot, ItemStack stack, boolean simulate,
            CallbackInfoReturnable<ItemStack> cir)
    {
        // A ball or the bag reached through a capability is allowed only when this wraps the player's own inventory.
        // Any other vanilla container refuses it (nothing accepted).
        if (DragonBallConfine.containerRefuses(stack, getInv()))
        {
            cir.setReturnValue(stack);
        }
    }
}
