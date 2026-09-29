package net.shurui.shuruisutilities.commands.util;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/**
 * A {@link Container} view of another player used by {@code /invsee}. The first 45 slots are the player's own
 * inventory (main + armour + offhand, 5 rows); the 6th row (slots 45-53) exposes the player's <b>Curios</b>
 * accessory slots so an admin can see and edit worn accessories too. Curios beyond the first 9 (rare) aren't
 * shown; a player with no Curios (or without the mod) simply shows an empty last row.
 */
public class SeeablePlayerInventory implements Container
{
    private static final int PLAYER_SLOTS = 45; // main + armour + offhand (indices the vanilla inventory maps)
    private static final int CURIO_SLOTS = 9;   // the 6th chest row
    private static final int SIZE = PLAYER_SLOTS + CURIO_SLOTS;

    public final Player victim;

    public SeeablePlayerInventory(Player player)
    {
        victim = player;
    }

    /** The player's combined Curios handler, or {@code null} if Curios is unavailable / not set up. */
    private IItemHandlerModifiable curios()
    {
        return CuriosApi.getCuriosInventory(victim).map(ICuriosItemHandler::getEquippedCurios).orElse(null);
    }

    @Override
    public int getContainerSize()
    {
        return SIZE;
    }

    @Override
    public boolean isEmpty()
    {
        return victim.getInventory().isEmpty();
    }

    @Override
    public ItemStack getItem(int section)
    {
        if (section < PLAYER_SLOTS)
            return victim.getInventory().getItem(section);
        IItemHandlerModifiable c = curios();
        int i = section - PLAYER_SLOTS;
        return (c != null && i < c.getSlots()) ? c.getStackInSlot(i) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int section, int number)
    {
        if (section < PLAYER_SLOTS)
            return victim.getInventory().removeItem(section, number);
        ItemStack cur = getItem(section);
        if (cur.isEmpty())
            return ItemStack.EMPTY;
        ItemStack split = cur.split(number);
        setItem(section, cur.isEmpty() ? ItemStack.EMPTY : cur);
        return split;
    }

    @Override
    public ItemStack removeItemNoUpdate(int section)
    {
        if (section < PLAYER_SLOTS)
            return victim.getInventory().removeItemNoUpdate(section);
        ItemStack cur = getItem(section);
        setItem(section, ItemStack.EMPTY);
        return cur;
    }

    @Override
    public void setItem(int section, ItemStack stack)
    {
        if (section < PLAYER_SLOTS)
        {
            victim.getInventory().setItem(section, stack);
            return;
        }
        IItemHandlerModifiable c = curios();
        int i = section - PLAYER_SLOTS;
        if (c != null && i < c.getSlots())
            c.setStackInSlot(i, stack);
    }

    @Override
    public int getMaxStackSize()
    {
        return victim.getInventory().getMaxStackSize();
    }

    @Override
    public void setChanged()
    {
        victim.getInventory().setChanged();
        victim.inventoryMenu.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player p_70300_1_)
    {
        return true;
    }

    @Override
    public boolean canPlaceItem(int section, ItemStack stack)
    {
        return section >= PLAYER_SLOTS || victim.getInventory().canPlaceItem(section, stack);
    }

    @Override
    public void clearContent()
    {
        victim.getInventory().clearContent();
    }
}
