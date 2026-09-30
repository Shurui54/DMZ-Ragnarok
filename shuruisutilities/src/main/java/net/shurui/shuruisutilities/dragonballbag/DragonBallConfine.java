package net.shurui.shuruisutilities.dragonballbag;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;

import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * The one place that answers "may this item live here" for the two items the suite confines: a dragon ball of any set,
 * and the dragon ball bag itself. Every containment hook (the GUI {@code Slot} mixin, the {@code ItemStackHandler} /
 * {@code InvWrapper} / hopper mixins, the mod-specific compat mixins, and the container-open backstop) routes its
 * decision through here so the whitelist is defined exactly once and cannot drift between hooks.
 *
 * <h2>The two allow-lists</h2>
 * <ul>
 *   <li>A dragon BALL may live only in the player's own {@link Inventory} or the dragon ball bag
 *       ({@link DragonBallBagSlot} / {@link DragonBallBagStorage}). Everything else refuses it.</li>
 *   <li>The dragon ball BAG may live only in the player's own {@link Inventory} or its Curios slot (a
 *       {@link SlotItemHandler} over an {@link IDynamicStackHandler}, which is exactly Curios' per-slot storage). It is
 *       deliberately NOT allowed inside a dragon ball bag (no bag in a bag) nor in any other container.</li>
 * </ul>
 *
 * <p>The bag is a normal item with a legitimate non-inventory home (its Curios slot), which is itself a
 * {@code SlotItemHandler} over an {@code ItemStackHandler}. That is why the bag branch whitelists Curios explicitly:
 * without it the same hooks that confine the bag would also refuse EQUIPPING it. Curios' own {@code isItemValid} still
 * enforces that only the {@code dragonball_bag} slot accepts the bag, so whitelisting "any Curios slot" here does not
 * widen where the bag can actually go.
 *
 * <p>Fail-safe: {@link DragonBallSets#isDragonBall} swallows any DMZ read error and returns false, so a DMZ change
 * turns the ball half of every hook into a no-op rather than breaking inventories. The bag test is a plain
 * {@code instanceof} on SU's own item, so it never depends on DMZ.
 */
public final class DragonBallConfine
{
    private DragonBallConfine()
    {
    }

    /** True for a registered DMZ dragon ball of any set (earth, namek, blackstar, super, cerulean, corrupted). */
    public static boolean isBall(ItemStack stack)
    {
        return DragonBallSets.isDragonBall(stack);
    }

    /** True for the dragon ball bag item. Never depends on DMZ. */
    public static boolean isBag(ItemStack stack)
    {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof DragonBallBagItem;
    }

    /** True for either confined item: a dragon ball or the dragon ball bag. */
    public static boolean isConfined(ItemStack stack)
    {
        return isBall(stack) || isBag(stack);
    }

    /**
     * The decision for a GUI {@link Slot}: true means refuse placing {@code stack} into {@code slot}. Non-confined
     * items always return false (let vanilla / the mod decide).
     */
    public static boolean slotRefuses(ItemStack stack, Slot slot)
    {
        if (slot == null)
        {
            return false;
        }
        if (isBall(stack))
        {
            return !ballSlotAllowed(slot);
        }
        if (isBag(stack))
        {
            return !bagSlotAllowed(slot);
        }
        return false;
    }

    private static boolean ballSlotAllowed(Slot slot)
    {
        if (slot instanceof DragonBallBagSlot)
        {
            return true; // the bag's own GUI: balls belong here
        }
        return slot.container instanceof Inventory; // the player's own inventory rows
    }

    private static boolean bagSlotAllowed(Slot slot)
    {
        if (slot.container instanceof Inventory)
        {
            return true; // the player's own inventory rows
        }
        return isCuriosSlot(slot); // the bag's Curios slot
    }

    /**
     * The decision for a Forge {@link net.minecraftforge.items.ItemStackHandler}: true means refuse inserting
     * {@code stack} into {@code handler}. A ball is allowed only in the bag's own handler; the bag is allowed only in a
     * Curios handler.
     */
    public static boolean handlerRefuses(ItemStack stack, Object handler)
    {
        if (isBall(stack))
        {
            return !(handler instanceof DragonBallBagStorage);
        }
        if (isBag(stack))
        {
            return !(handler instanceof IDynamicStackHandler);
        }
        return false;
    }

    /**
     * The decision for a vanilla {@link Container} destination (a hopper push / pull, or an {@code InvWrapper} over a
     * container): true means refuse. The only allowed container is the player's own {@link Inventory}; the bag's Curios
     * slot is never a vanilla {@code Container}, so both items refuse every {@code Container} that is not the player
     * inventory.
     */
    public static boolean containerRefuses(ItemStack stack, Container destination)
    {
        if (!isConfined(stack))
        {
            return false;
        }
        return !(destination instanceof Inventory);
    }

    /** True when this slot is a Curios per-slot storage slot (a {@link SlotItemHandler} over an {@link IDynamicStackHandler}). */
    public static boolean isCuriosSlot(Slot slot)
    {
        return slot instanceof SlotItemHandler sih && sih.getItemHandler() instanceof IDynamicStackHandler;
    }
}
