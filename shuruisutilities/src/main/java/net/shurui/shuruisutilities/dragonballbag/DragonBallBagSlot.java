package net.shurui.shuruisutilities.dragonballbag;

/**
 * Marker implemented by the bag's own menu slots. The containment mixin ({@code core.mixin.inventory.MixinSlot})
 * uses it to tell "this is the dragon ball bag, balls are allowed here" apart from every other container slot,
 * without the mixin having to classload the menu or the item handler types. Kept as a bare interface on purpose so
 * the mixin's reference is as light as possible.
 */
public interface DragonBallBagSlot
{
}
