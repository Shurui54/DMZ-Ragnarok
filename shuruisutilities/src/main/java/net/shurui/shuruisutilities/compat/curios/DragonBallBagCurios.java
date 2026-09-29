package net.shurui.shuruisutilities.compat.curios;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import net.shurui.shuruisutilities.dragonballbag.DragonBallBagItem;

/**
 * All Curios API contact for the dragon ball bag, isolated here (imports {@code top.theillusivec4.curios.*}
 * directly) so the rest of the bag code never hard-links Curios. Every entry point first checks
 * {@link CuriosAccess#isPresent()}; when Curios is absent the methods short-circuit and the bag simply cannot be
 * equipped, so pickups fall through to the main inventory and nothing crashes. Every call is also wrapped in
 * try/catch(Throwable) so a Curios API shift degrades to "no bag" rather than throwing on a pickup or a death.
 */
public final class DragonBallBagCurios
{
    private DragonBallBagCurios()
    {
    }

    // the curios slot the bag equips into, registered by data/shuruisutilities/curios/slots/dragonball_bag.json
    // and mapped to the item by data/curios/tags/items/dragonball_bag.json. index 0: the slot holds exactly one.
    private static final String SLOT = "dragonball_bag";

    /** The live equipped dragon ball bag stack for this entity, or {@link ItemStack#EMPTY} when none / Curios absent. */
    public static ItemStack findEquipped(LivingEntity entity)
    {
        if (entity == null || !CuriosAccess.isPresent())
        {
            return ItemStack.EMPTY;
        }
        try
        {
            ItemStack stack = CuriosApi.getCuriosInventory(entity)
                    .map(h -> h.findCurio(SLOT, 0).map(SlotResult::stack).orElse(ItemStack.EMPTY))
                    .orElse(ItemStack.EMPTY);
            // only treat it as a bag if it really is one; a foreign item sharing the slot must not be driven as a bag.
            return stack.getItem() instanceof DragonBallBagItem ? stack : ItemStack.EMPTY;
        }
        catch (Throwable t)
        {
            return ItemStack.EMPTY;
        }
    }

    /** True when this entity has a dragon ball bag equipped in its curios slot. */
    public static boolean hasEquipped(LivingEntity entity)
    {
        return !findEquipped(entity).isEmpty();
    }

    /**
     * Re-seat the (already mutated) bag stack in its slot so Curios marks the inventory dirty and re-syncs it to
     * the client. The stack we get from {@link #findEquipped} is the live one, so writing to its NBT already
     * persists to the save; this is purely the "tell Curios it changed" nudge. Failures are swallowed.
     */
    public static void persist(LivingEntity entity, ItemStack bag)
    {
        if (entity == null || bag == null || bag.isEmpty() || !CuriosAccess.isPresent())
        {
            return;
        }
        try
        {
            CuriosApi.getCuriosInventory(entity)
                    .ifPresent(h -> h.setEquippedCurio(SLOT, 0, bag));
        }
        catch (Throwable t)
        {
            // no-op: the NBT write already happened on the live stack, so the contents are safe even if the
            // dirty-nudge fails.
        }
    }
}
