package net.shurui.shuruisutilities.core.mixin.inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

import net.shurui.shuruisutilities.dragonballbag.DragonBallContainerEject;

/**
 * After every click a player makes in an open container menu (pickup, shift-click, number-key swap, drag, double
 * click, drop), hand back any dragon ball or dragon ball bag that now sits in a slot that is not an allowed home.
 *
 * <p>{@code MixinSlot} refuses a ball at {@code Slot.mayPlace}, but a slot CLASS that overrides {@code mayPlace}
 * without calling the base method never reaches that hook: vanilla's shulker box slot (which accepts anything that
 * may live inside a container item), and any modded slot type. This closes all of those at once, whatever the
 * container, by checking the menu's slots right after the click resolves. The sweep itself is the open-time
 * backstop's ({@link DragonBallContainerEject#sweepMenu}): allowed homes stay untouched (the player's inventory, the
 * ball bag, the bag's Curios slot, graves, /invsee's inventory rows), read-only views are skipped, and a stray item
 * goes back to the clicking player (dropped at their feet only if they have no room), so nothing is copied or lost.
 *
 * <p>Server side only (the client never reaches it with a ServerPlayer); the player's own inventory menu is skipped.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class MixinContainerMenuSweep
{
    @Inject(method = "clicked", at = @At("TAIL"))
    private void su$returnStrayDragonBalls(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci)
    {
        if (player instanceof ServerPlayer serverPlayer)
            DragonBallContainerEject.sweepMenu(serverPlayer, (AbstractContainerMenu) (Object) this);
    }
}
