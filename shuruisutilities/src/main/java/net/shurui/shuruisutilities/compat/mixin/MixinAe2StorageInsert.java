package net.shurui.shuruisutilities.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.Item;

import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;
import net.shurui.shuruisutilities.dragonballbag.DragonBallBagItem;

/**
 * The catch-all for Applied Energistics 2: an ME grid never accepts a dragon ball or the dragon ball bag, whatever
 * feeds it. {@code NetworkStorage.insert} is the one entry every grid insert goes through (terminals, import buses,
 * annihilation planes picking up dropped items, interfaces, pattern providers returning, storage buses), and
 * {@code BasicCellInventory.insert} covers a cell used outside a grid (portable cells, the ME chest). Returning 0 means
 * "nothing inserted", so the item simply stays where it came from (the player, the ground, the source inventory).
 *
 * <p>{@code MixinAe2MeStorageMenu} stays for the terminal: it adds the refusal message there. This mixin is the
 * whitelist net for every route nobody listed.
 *
 * <p>The ball check is a registry lookup on the key's item only (no stack copy, no NBT walk), so it costs next to
 * nothing on the hot insert path. A ball nested inside another item cannot reach the grid because the container hooks
 * already keep balls out of shulkers and backpacks.
 *
 * <p>{@link Pseudo &#64;Pseudo}, string targets, {@code remap = false}, {@link Coerce &#64;Coerce} on the AE2 parameter
 * types: AE2 is optional and not on the compile classpath. {@code require = 0} so an AE2 rename degrades to "no guard".
 */
@Pseudo
@Mixin(targets = {"appeng.me.storage.NetworkStorage", "appeng.me.cells.BasicCellInventory"}, remap = false)
public abstract class MixinAe2StorageInsert
{
    @Inject(
        method = "insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;)J",
        at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void su$refuseConfined(@Coerce Object key, long amount, @Coerce Object mode, @Coerce Object source,
            CallbackInfoReturnable<Long> cir)
    {
        Item item = net.shurui.shuruisutilities.compat.Ae2KeyItem.itemOf(key);
        if (item != null && (item instanceof DragonBallBagItem || DragonBallSets.setIdOf(item) != null))
        {
            cir.setReturnValue(0L);
        }
    }
}
