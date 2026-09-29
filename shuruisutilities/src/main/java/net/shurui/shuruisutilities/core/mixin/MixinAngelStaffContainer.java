package net.shurui.shuruisutilities.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

import net.shurui.shuruisutilities.god.AngelStaffGuard;

/**
 * Stops the Angel staff being put into any container that is not the owner's own inventory.
 *
 * <p>{@code doClick} is the single funnel every inventory interaction goes through, including shift-click transfers,
 * hotbar swaps and drag placement, so one check here covers the routes that a per-slot {@code mayPlace} override
 * would miss. {@code Slot} itself is not enough: it does not know which player is clicking, and the rule is about
 * whose storage the destination is.
 *
 * <p>Refuses silently rather than messaging, because a blocked click is self-evident (the item stays in hand) and a
 * chat line on every attempted drag would be noise.
 *
 * <p>Vanilla target, so this is remapped normally and left at the config's default require: unlike the DMZ mixins,
 * {@code AbstractContainerMenu} is not going to move underneath us.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class MixinAngelStaffContainer
{
    @Inject(method = "doClick", at = @At("HEAD"), cancellable = true)
    private void su$angelStaffStaysHome(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci)
    {
        if (!(player instanceof ServerPlayer serverPlayer) || slotId < 0)
            return;
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        try
        {
            if (slotId >= self.slots.size())
                return;
            Slot slot = self.slots.get(slotId);

            // Placing the carried staff into a foreign slot.
            if (!AngelStaffGuard.mayPlaceIn(self.getCarried(), slot, serverPlayer))
            {
                ci.cancel();
                return;
            }
            // Shift-clicking a staff OUT of the player's inventory would push it into the open container, which is
            // the same move by another route.
            if (clickType == ClickType.QUICK_MOVE
                    && !AngelStaffGuard.mayPlaceIn(slot.getItem(), slot, serverPlayer))
            {
                ci.cancel();
            }
        }
        catch (Throwable ignored)
        {
            // Never let this break inventory handling; on any error the click proceeds as vanilla.
        }
    }
}
