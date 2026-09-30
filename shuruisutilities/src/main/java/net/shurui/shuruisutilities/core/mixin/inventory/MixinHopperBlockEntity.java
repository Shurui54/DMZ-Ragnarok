package net.shurui.shuruisutilities.core.mixin.inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import net.shurui.shuruisutilities.dragonballbag.DragonBallConfine;

/**
 * Closes the one gap the GUI containment mixin ({@code core.mixin.inventory.MixinSlot}) always admitted: a dragon ball
 * moved between vanilla-style containers by machinery rather than by a player, which never touches {@code Slot.mayPlace}.
 *
 * <p>{@code HopperBlockEntity.addItem(Container, Container, ItemStack, Direction)} is the single static choke point
 * every vanilla container-to-container transfer funnels through: a hopper pulling from or pushing into a chest, a
 * dropper firing into an adjacent container, a hopper minecart sucking an item and depositing it, and (because both
 * delegate to it) the item-entity pickup path {@code addItem(Container, ItemEntity)}. Vanilla itself only gates this
 * with the destination's {@code canPlaceItem}, which for a plain chest is always true, so without this a dropped ball
 * could be sucked into a chest, an Iron Chest, a Lootr chest, a furniture container or a Farmer's Delight basket by a
 * hopper. Refusing at the head (return the stack unchanged, meaning nothing moved) leaves the ball where it was: still
 * an item entity on the ground, which is a legitimate state and how the radar finds a scattered ball.
 *
 * <p>Modded storage backed by a Forge {@code IItemHandler} does not use this path (a hopper reaches it through the
 * item-handler capability instead), so it is covered by {@code MixinForgeItemStackHandler} and {@code MixinInvWrapper}
 * rather than here. This hook is purely the vanilla-Container automation route.
 *
 * <p>The player's own inventory is never a party to a hopper transfer (players are not {@code Container}s a hopper
 * feeds), so no whitelist exception is needed here: any ball reaching this method is bound for machinery and is refused.
 *
 * <p>Fail-safe: {@link DragonBallSets#isDragonBall} returns false on any DMZ read error, turning this into a no-op.
 */
@Mixin(HopperBlockEntity.class)
public abstract class MixinHopperBlockEntity
{
    @Inject(
        method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;"
                + "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)"
                + "Lnet/minecraft/world/item/ItemStack;",
        at = @At("HEAD"), cancellable = true)
    private static void su$blockDragonBallHopperMove(Container source, Container destination, ItemStack stack,
            Direction direction, CallbackInfoReturnable<ItemStack> cir)
    {
        // A hopper never feeds a player inventory, so any dragon ball or dragon ball bag reaching this method is bound
        // for machinery and is refused: nothing moved, the item stays where it is.
        if (DragonBallConfine.isConfined(stack))
        {
            cir.setReturnValue(stack);
        }
    }
}
