package net.shurui.shuruisutilities.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.dragonballbag.DragonBallConfine;

/**
 * Keeps dragon balls and the dragon ball bag out of an Applied Energistics 2 ME network, the one storage route the
 * capability mixins cannot reach.
 *
 * <p>An ME network does NOT store items in a Forge {@code IItemHandler}: a cell holds AE keys
 * ({@code appeng.api.stacks.AEItemKey}), and everything that feeds the network (a terminal, an import bus, a storage
 * bus, an interface) converts an {@code ItemStack} to a key first. So {@code MixinForgeItemStackHandler} and the vanilla
 * container mixins never see an ME insert, and {@code MixinAe2SpatialTransition} only covers spatial cells. The single
 * realistic way a ball reaches the network is a player at an ME terminal moving it out of their own inventory: an import
 * bus can only pull from an adjacent inventory, which the other hooks already keep a ball out of, and a storage bus
 * exposes the network to a container rather than the reverse. Both terminal routes funnel through
 * {@code appeng.menu.me.common.MEStorageMenu}:
 * <ul>
 *   <li>{@code transferStackToMenu(ItemStack)} : the shift-click / quick-move of a stack from the player inventory into
 *       the network. Returning the stack unchanged means nothing was transferred.</li>
 *   <li>{@code putCarriedItemIntoNetwork(boolean)} : left-clicking the carried stack onto the network view. Cancelling
 *       leaves the carried item in hand.</li>
 * </ul>
 * The wireless terminal, crafting terminal, pattern terminal and ME chest all use this same menu class, so both routes
 * cover every ME terminal variant at once.
 *
 * <p>The container-open backstop ({@code DragonBallContainerEject}) cannot recover a ball from the network because the
 * ME grid is a virtual key repository, not real {@link net.minecraft.world.inventory.Slot}s over a container, so
 * refusing at the insert is the only place this can be stopped.
 *
 * <h2>Optionality and mappings</h2>
 *
 * <p>{@link Pseudo &#64;Pseudo} + {@code targets} string + {@code remap = false}: AE2 is an optional dependency and is
 * not on the compile classpath, so the class is named by string and never classloaded when AE2 is absent (the
 * non-required compat config skips it). {@code remap = false} because the target class and its
 * {@code transferStackToMenu} / {@code putCarriedItemIntoNetwork} names are AE2 names, not obfuscated; the vanilla
 * parameter TYPES in each descriptor are Mojmap class names, valid verbatim in production. The handler bodies touch only
 * vanilla and SU code. {@code require = 0} so an AE2 rename degrades to "no guard" rather than crashing mod load.
 * {@code getPlayer()} is {@code appeng.menu.AEBaseMenu}'s public accessor (the superclass of the target), shadowed for
 * the refusal message; {@code getCarried()} is vanilla {@code AbstractContainerMenu}.
 */
@Pseudo
@Mixin(targets = "appeng.menu.me.common.MEStorageMenu", remap = false)
public abstract class MixinAe2MeStorageMenu
{
    @Shadow(remap = false)
    public abstract Player getPlayer();

    @Inject(
        method = "transferStackToMenu(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$refuseBallShiftClick(ItemStack stack, CallbackInfoReturnable<ItemStack> cir)
    {
        if (DragonBallConfine.isConfined(stack))
        {
            cir.setReturnValue(stack); // nothing transferred: the item stays in the player inventory
            su$notifyRefused(stack);
        }
    }

    @Inject(method = "putCarriedItemIntoNetwork(Z)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$refuseBallCarried(boolean singleItem, CallbackInfo ci)
    {
        ItemStack carried;
        try
        {
            carried = ((net.minecraft.world.inventory.AbstractContainerMenu) (Object) this).getCarried();
        }
        catch (Throwable t)
        {
            return; // could not read the carried stack: leave AE2's behaviour untouched
        }
        if (DragonBallConfine.isConfined(carried))
        {
            ci.cancel(); // the carried item stays in hand
            su$notifyRefused(carried);
        }
    }

    // Tell the player, once per action, why the item did not go into the network. Server side only, so the client
    // renders it once rather than twice.
    private void su$notifyRefused(ItemStack stack)
    {
        try
        {
            if (getPlayer() instanceof ServerPlayer server)
            {
                String key = DragonBallConfine.isBag(stack)
                        ? "message.dmz_ragnarok.dragonball.bag_refused"
                        : "message.dmz_ragnarok.dragonball.ball_refused";
                server.sendSystemMessage(net.minecraft.network.chat.Component.translatable(key));
            }
        }
        catch (Throwable t)
        {
            // never let a message failure break the refusal itself.
        }
    }
}
