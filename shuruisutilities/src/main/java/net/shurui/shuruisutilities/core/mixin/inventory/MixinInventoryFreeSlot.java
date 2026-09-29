package net.shurui.shuruisutilities.core.mixin.inventory;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Keeps the slot a player is holding out of the way of items arriving in their inventory.
 *
 * <p>The problem is a combat one. DMZ is fought with empty hands, so a fighter's selected slot is normally empty, and
 * vanilla fills the FIRST empty slot it finds: {@code getFreeSlot} walks 0 upward, and slots 0 to 8 are the hotbar. Pick
 * anything up mid-fight with an empty slot below the one you are holding and the item lands in your hand, so the next
 * swing is a swing with an item in it and does no damage. Nothing announces this; the fight simply stops working, and
 * the fix in game is to notice, open the inventory and move it.
 *
 * <p>So the selected slot becomes the LAST resort rather than a candidate: any other empty slot is taken first, and it
 * is only used when it is the single empty slot left, because refusing it there would mean silently destroying an item
 * an inventory still had room for. A full inventory still answers -1 exactly as before.
 *
 * <p>What this deliberately does NOT touch is {@code getSlotWithRemainingSpace}, which is the other half of a pickup and
 * the one that prefers the selected slot on purpose. That path only fires when the incoming stack MERGES with what is
 * already in your hand, so holding nine dirt and picking up a tenth still stacks it where you would expect. An empty
 * slot cannot merge with anything ({@code hasRemainingSpaceForItem} requires a non-empty destination), so the empty
 * hand case falls through to here every time, which is why here is the only place that needs to change.
 *
 * <p>Written against the whole of {@code Inventory.add}, not item entities alone, so a {@code /give} or a stack handed
 * back by a closing container behaves the same way. That is wider than the pickup case that prompted it, and wider is
 * the point: the reason to keep the slot clear does not depend on where the item came from.
 */
@Mixin(Inventory.class)
public abstract class MixinInventoryFreeSlot
{
    @Shadow
    public int selected;

    @Shadow
    @Final
    public NonNullList<ItemStack> items;

    /**
     * Vanilla's own scan, with the held slot moved to the back of the queue.
     *
     * <p>Cancelling outright rather than adjusting the result afterwards, because the answer is a single index and
     * there is no way to express "not that one" to the caller after the fact.
     */
    @Inject(method = "getFreeSlot", at = @At("HEAD"), cancellable = true)
    private void su$keepTheHeldSlotClear(CallbackInfoReturnable<Integer> cir)
    {
        int heldIfNothingElse = -1;
        for (int i = 0; i < this.items.size(); i++)
        {
            if (!this.items.get(i).isEmpty())
                continue;
            if (i == this.selected)
            {
                // Remembered, not taken. If the walk finds nothing else this is still better than refusing the item.
                heldIfNothingElse = i;
                continue;
            }
            cir.setReturnValue(i);
            return;
        }
        cir.setReturnValue(heldIfNothingElse);
    }
}
