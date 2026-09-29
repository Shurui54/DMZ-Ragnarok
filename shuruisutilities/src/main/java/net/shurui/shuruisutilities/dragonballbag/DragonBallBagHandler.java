package net.shurui.shuruisutilities.dragonballbag;

import net.minecraftforge.items.ItemStackHandler;

/**
 * The {@link ItemStackHandler} that backs a dragon ball bag, tagged with {@link DragonBallBagStorage} so the generic
 * item-handler containment mixin ({@code core.mixin.inventory.MixinForgeItemStackHandler}) recognises it as the one
 * handler in the game that IS allowed to hold dragon balls.
 *
 * <p>Every handler the bag builds (the transient one {@link DragonBallBagInventory#read} hands back, the client mirror
 * in {@link DragonBallBagMenu}, and the write-through {@code BagBackedHandler}) is one of these, so the sack keeps
 * working while the mixin refuses balls into every other {@code ItemStackHandler} (modded storage, machine slots,
 * Curios slots, and so on). It adds no behaviour of its own; it is purely the marker made concrete.
 */
public class DragonBallBagHandler extends ItemStackHandler implements DragonBallBagStorage
{
    public DragonBallBagHandler(int size)
    {
        super(size);
    }
}
