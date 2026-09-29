package net.shurui.shuruisutilities.dragonballbag;

/**
 * Marker implemented by the dragon ball bag's own {@link net.minecraftforge.items.ItemStackHandler} (see
 * {@link DragonBallBagHandler}). It is the item-handler analogue of {@link DragonBallBagSlot}: where that marker tells
 * the GUI {@code Slot} containment mixin "this slot is the bag", this one tells the generic
 * {@code core.mixin.inventory.MixinForgeItemStackHandler} "this handler is the bag", so a ball may be inserted here
 * while every other {@code ItemStackHandler} in the game refuses it.
 *
 * <p>Kept as a bare interface on purpose so the mixin's reference is as light as possible and it never has to classload
 * the bag's storage or menu types.
 */
public interface DragonBallBagStorage
{
}
