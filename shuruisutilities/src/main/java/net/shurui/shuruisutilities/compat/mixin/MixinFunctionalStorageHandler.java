package net.shurui.shuruisutilities.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.dragonballbag.DragonBallConfine;

/**
 * Keeps dragon balls and the dragon ball bag out of Functional Storage drawers, cabinets and controllers.
 *
 * <p>Functional Storage does NOT back its storage with Forge's {@code ItemStackHandler}: every one of its handlers
 * ({@code BigInventoryHandler} for drawers, {@code CompactingInventoryHandler} for compacting drawers,
 * {@code ControllerInventoryHandler} for the storage controller, {@code ArmoryCabinetInventoryHandler} for the armory
 * cabinet) implements {@link net.minecraftforge.items.IItemHandler} directly. So {@code MixinForgeItemStackHandler}
 * never sees them, and both the GUI route (its slots ask the handler's {@code isItemValid}) and the automation route (a
 * hopper / pipe inserting through the capability calls {@code insertItem}) would otherwise let a ball or the bag in.
 * Injecting the head of both methods on all four handler bases closes every route in one place: {@code isItemValid}
 * returns false, and {@code insertItem} returns the whole stack (nothing accepted).
 *
 * <h2>Optionality and mappings</h2>
 *
 * <p>{@link Pseudo &#64;Pseudo} + {@code targets} strings + {@code remap = false}: Functional Storage is an optional
 * dependency and not on the compile classpath, so each class is named by string and never classloaded when the mod is
 * absent (the non-required compat config skips it). {@code remap = false} because the target classes and the
 * {@code insertItem} / {@code isItemValid} names are Forge / mod names, not obfuscated; the vanilla {@code ItemStack}
 * parameter type is a Mojmap class name valid verbatim in production. {@code require = 0} so a rename in a future build
 * degrades to "no containment" rather than crashing mod load. The bodies touch only vanilla and SU code.
 */
@Pseudo
@Mixin(targets = {
        "com.buuz135.functionalstorage.inventory.BigInventoryHandler",
        "com.buuz135.functionalstorage.inventory.CompactingInventoryHandler",
        "com.buuz135.functionalstorage.inventory.ControllerInventoryHandler",
        "com.buuz135.functionalstorage.inventory.ArmoryCabinetInventoryHandler"
}, remap = false)
public abstract class MixinFunctionalStorageHandler
{
    @Inject(method = "isItemValid(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"),
            cancellable = true, remap = false, require = 0)
    private void su$rejectValidity(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir)
    {
        if (DragonBallConfine.isConfined(stack))
        {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$rejectInsert(int slot, ItemStack stack, boolean simulate, CallbackInfoReturnable<ItemStack> cir)
    {
        if (DragonBallConfine.isConfined(stack))
        {
            cir.setReturnValue(stack);
        }
    }
}
