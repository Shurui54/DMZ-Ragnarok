package net.shurui.shuruisutilities.core.mixin.inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;
import net.shurui.shuruisutilities.dragonballbag.DragonBallBagStorage;

/**
 * The container-agnostic heart of the dragon ball whitelist. A ball may live in exactly two homes: the player's own
 * {@link net.minecraft.world.entity.player.Inventory} and the dragon ball bag. Everything else must refuse it.
 *
 * <p>{@link ItemStackHandler} is Forge's standard {@link net.minecraftforge.items.IItemHandler} implementation and the
 * base almost every modded storage extends: Sophisticated Backpacks' {@code InventoryHandler}, Titanium's inventory
 * components (so Powah and friends), Functional Storage drawers, Curios' per-slot handlers, and countless machine
 * inventories. It is also what Forge's {@code SlotItemHandler} asks {@code isItemValid} on, so denying here covers BOTH
 * the GUI route (a mod screen whose slot bypasses vanilla {@code Slot.mayPlace}, which {@code MixinSlot} cannot reach)
 * and every programmatic route (hoppers / pipes / upgrades inserting through the capability). One hook, default deny, so
 * a storage type nobody anticipated is refused simply for being an {@code ItemStackHandler}.
 *
 * <h2>The one exception</h2>
 *
 * <p>The dragon ball bag's own handler is a {@link DragonBallBagHandler} tagged {@link DragonBallBagStorage}. That is
 * the single handler balls belong in, so it is waved through here; the bag's per-slot rules (dragon-ball-only, one set
 * at a time) live in {@code DragonBallBagMenu.BagSlot} and {@code DragonBallCarry}, not here.
 *
 * <p>NOT covered by this hook, on purpose, and covered elsewhere: the player's own inventory reached through a
 * capability is a {@code PlayerInvWrapper} / {@code PlayerMainInvWrapper} over an {@code InvWrapper}, never an
 * {@code ItemStackHandler}, so it is handled by {@code MixinInvWrapper} instead. The grave / totem is a
 * {@code SimpleContainer} filled by direct {@code setItem}, so it never reaches an item handler at all and keeps
 * working as the suite's own internal ball holder.
 *
 * <h2>Performance</h2>
 *
 * <p>These two methods sit on hot paths (every hopper tick that probes a modded inventory). The ball test is the first
 * thing done and is the cheap path for a non-ball stack: {@link DragonBallSets#isDragonBall} short-circuits on empty,
 * then does a single {@code HashMap} lookup keyed by the item against a cache that only rebuilds when the live ball-set
 * count changes. A non-ball stack therefore costs one map miss and returns immediately.
 *
 * <h2>Fail-safe</h2>
 *
 * <p>{@link DragonBallSets#isDragonBall} swallows any DMZ API error and returns false, so a DMZ change turns this hook
 * into a no-op (balls become ordinary items again) rather than breaking every inventory in the modpack. {@code remap =
 * false} on the injectors because {@code isItemValid} / {@code insertItem} are Forge API names, not obfuscated; the
 * vanilla {@code ItemStack} parameter type is a Mojmap class name valid verbatim in production.
 */
@Mixin(ItemStackHandler.class)
public abstract class MixinForgeItemStackHandler
{
    // The validity question: Forge's SlotItemHandler.mayPlace delegates here, and insertItem consults it too, so a
    // ball is refused at a mod GUI slot, a shift-click into modded storage, and a programmatic insert that checks
    // validity first.
    @Inject(method = "isItemValid(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void su$rejectBallValidity(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir)
    {
        if (su$isBag())
        {
            return; // the dragon ball bag: balls belong here
        }
        if (DragonBallSets.isDragonBall(stack))
        {
            cir.setReturnValue(false);
        }
    }

    // The programmatic insert: returning the whole stack means nothing was accepted. Belt over the validity hook for a
    // handler that inserts without re-checking validity.
    @Inject(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void su$rejectBallInsert(int slot, ItemStack stack, boolean simulate,
            CallbackInfoReturnable<ItemStack> cir)
    {
        if (su$isBag())
        {
            return; // the dragon ball bag: balls belong here
        }
        if (DragonBallSets.isDragonBall(stack))
        {
            cir.setReturnValue(stack);
        }
    }

    private boolean su$isBag()
    {
        return (Object) this instanceof DragonBallBagStorage;
    }
}
