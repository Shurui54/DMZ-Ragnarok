package net.shurui.shuruisutilities.core.mixin.inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.dragonballbag.DragonBallConfine;

/**
 * Containment for dragon balls at the GUI level. Almost every way a player moves an item into a container slot
 * (click-place, shift-click / quick-move into an empty slot, hotbar swap) funnels through
 * {@link Slot#mayPlace(ItemStack)} on the destination slot. By denying that for dragon balls whenever the
 * destination is NOT the player's own inventory and NOT the dragon ball bag, one hook covers chests, shulker box
 * GUIs, ender chests, hopper / dropper / dispenser GUIs, brewing stands, beacons and the like in a single place.
 *
 * <p>What this does NOT cover, by design: non-GUI item-handler insertion, i.e. a dropped ball being sucked up by a
 * hopper or pushed by a dropper. Those paths never call {@code mayPlace}, and Forge exposes no event to intercept
 * them without a broad, fragile block-entity mixin. That residual gap is small in practice: a ball can never be
 * PLACED into any block container through a GUI (this hook blocks it), so a ball only ever reaches automation if it
 * is physically dropped onto a hopper, and the death / logout drops land at the player, not on machinery.
 *
 * <p>Fail-safe: {@link DragonBallSets#isDragonBall(ItemStack)} swallows any DMZ API error and returns false, so a
 * DMZ change turns this hook into a no-op (balls become ordinary items again) rather than breaking every container
 * in the game.
 */
@Mixin(Slot.class)
public abstract class MixinSlot
{
    @Inject(method = "mayPlace(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void su$blockDragonBallIntoContainers(ItemStack stack, CallbackInfoReturnable<Boolean> cir)
    {
        // A dragon ball may be placed only into the player inventory or the bag; the bag only into the player
        // inventory or its Curios slot. Any other destination (chest, shulker, ender chest, hopper GUI, item frame
        // slot, a modded storage slot, ...) is refused. Non-confined items are left to vanilla / the mod.
        if (DragonBallConfine.slotRefuses(stack, (Slot) (Object) this))
        {
            cir.setReturnValue(false);
        }
    }
}
