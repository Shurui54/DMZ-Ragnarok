package net.shurui.shuruisutilities.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.dragonballbag.DragonBallConfine;

/**
 * Keeps dragon balls out of Sophisticated Backpacks storage by every route, at the one choke point they all funnel
 * through: {@code net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler}, the abstract {@code ItemStackHandler}
 * that backs EVERY backpack (the backpack's own {@code BackpackInventoryHandler} and any nested one both extend it).
 *
 * <h2>Why this class and not the GUI Slot mixin</h2>
 *
 * <p>SU's {@code core.mixin.inventory.MixinSlot} refuses balls into any container by injecting the HEAD of
 * {@code Slot#mayPlace}. Sophisticated Backpacks' storage slot ({@code StorageInventorySlot}) OVERRIDES
 * {@code mayPlace} and routes it to {@code getInventoryHandler().isItemValid(slot, stack, player)} without calling
 * {@code super}, so the Slot mixin never runs for a backpack GUI. This mixin covers that gap and every non-GUI route
 * besides, in one place.
 *
 * <h2>What each hook covers</h2>
 * <ul>
 *   <li>{@code isItemValid(int, ItemStack, Player)} : the GUI question. {@code StorageInventorySlot.mayPlace}
 *       delegates to it, so returning false for a ball refuses a slot place, a shift-click / quick-move into the
 *       backpack screen, and a hotbar swap onto a backpack slot. The two-arg {@code isItemValid(int, ItemStack)}
 *       forwards to this one, so IItemHandler callers that ask validity are covered too.</li>
 *   <li>{@code insertItem(int, ItemStack, boolean)} and {@code insertItem(ItemStack, boolean)} : the programmatic
 *       routes. Returning the stack UNCHANGED (nothing accepted) refuses hopper / pipe insertion into a placed
 *       backpack block (its capability exposes a {@code FilteredItemHandler} that delegates here), the pickup /
 *       magnet / feeding / filter / compacting / deposit upgrades (they all insert through this handler), and the
 *       "click a held stack onto the backpack item in your inventory" feature ({@code BackpackItem} calls
 *       {@code getInventoryForUpgradeProcessing().insertItem(stack, simulate)}, which resolves to the single-arg
 *       overload here). Forge's own {@code ItemStackHandler.insertItem} already consults {@code isItemValid}, so the
 *       insert hooks are belt-and-suspenders over the validity hook, guaranteeing a refusal even for a part handler
 *       that inserts without re-checking validity.</li>
 * </ul>
 *
 * <p>The dragon ball bag's own storage ({@code DragonBallBagStorage}) is SU's own handler and is NOT an
 * {@code InventoryHandler}, so this never touches the one place balls are meant to live. Extraction is never hooked, so
 * a ball or a bag already inside a backpack from before this fix can always be taken out (and is auto-ejected on open by
 * {@code compat.sophisticatedbackpacks.BackpackDragonBallEject} and the generic container-open backstop).
 *
 * <h2>Confined test and optionality</h2>
 *
 * <p>{@code DragonBallConfine.isConfined(ItemStack)} flags both a real dragon ball ITEM (earth, namek, blackstar,
 * super, cerulean, corrupted) and the {@code dragonball_bag} item, since neither belongs in a backpack (the bag's only
 * homes are the player inventory and its Curios slot, never other storage). The ball half degrades to "not a ball" if
 * DMZ's API shifts, turning that half of the hook into a no-op; the bag half is a plain {@code instanceof} on SU's own
 * item and never depends on DMZ.
 *
 * <p>{@link Pseudo &#64;Pseudo} + {@code targets} string + {@code remap = false}: Sophisticated Core is an optional
 * dependency and is NOT on the compile classpath, so the target is named by string and never classloaded when the mod
 * is absent (the non-required compat config simply skips it). {@code remap = false} because the target class and its
 * {@code insertItem} / {@code isItemValid} method names are Forge / mod names, not obfuscated; the vanilla parameter
 * TYPES in each descriptor are Mojmap class names, which are valid verbatim in production. The handler bodies touch
 * only vanilla and SU code, so no Sophisticated type is ever referenced here. {@code require = 0} (the config default)
 * so a rename in a future SB build degrades to "no containment" rather than crashing mod load.
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler", remap = false)
public abstract class MixinSophisticatedInventoryHandler
{
    // The GUI question: StorageInventorySlot.mayPlace delegates here, and the two-arg isItemValid forwards to it.
    @Inject(
        method = "isItemValid(ILnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)Z",
        at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$rejectBallValidity(int slot, ItemStack stack, Player player, CallbackInfoReturnable<Boolean> cir)
    {
        if (DragonBallConfine.isConfined(stack))
        {
            cir.setReturnValue(false);
        }
    }

    // Programmatic insert into a specific slot (hoppers via the filtered handler, upgrades, quick-move). Returning the
    // whole stack means nothing was accepted.
    @Inject(
        method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
        at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$rejectBallInsertSlot(int slot, ItemStack stack, boolean simulate,
            CallbackInfoReturnable<ItemStack> cir)
    {
        if (DragonBallConfine.isConfined(stack))
        {
            cir.setReturnValue(stack);
        }
    }

    // Programmatic insert with no slot preference (the "click a stack onto the backpack item" feature, and upgrades
    // that insert through getInventoryForUpgradeProcessing). Same refusal.
    @Inject(
        method = "insertItem(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
        at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$rejectBallInsert(ItemStack stack, boolean simulate, CallbackInfoReturnable<ItemStack> cir)
    {
        if (DragonBallConfine.isConfined(stack))
        {
            cir.setReturnValue(stack);
        }
    }
}
