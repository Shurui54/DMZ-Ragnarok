package net.shurui.shuruisutilities.vehicleworkbench;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.spaceconsole.SpaceConsoleBlocks;

/**
 * The vehicle workbench's container: just the player's inventory, plus one button per blueprint.
 *
 * <p>Server authoritative: the screen only sends {@code clickMenuButton(index)}. The server rebuilds the blueprint list
 * from its own recipe manager, re-plans the cost against the real inventory and only then takes the parts, so a client
 * cannot build anything it cannot pay for. The bench holds no items of its own, so nothing is lost when it closes or
 * the block is broken.
 */
public class VehicleWorkbenchMenu extends AbstractContainerMenu
{
    /** Where the player inventory starts inside the screen (the screen lays itself out around these). */
    public static final int INV_X = 57;
    public static final int INV_Y = 154;

    private final ContainerLevelAccess access;

    /** Client constructor: no world access, the screen mirrors the inventory and sends button ids. */
    public VehicleWorkbenchMenu(int id, Inventory inv)
    {
        this(id, inv, ContainerLevelAccess.NULL);
    }

    public VehicleWorkbenchMenu(int id, Inventory inv, ContainerLevelAccess access)
    {
        super(VehicleWorkbenchRegistry.MENU.get(), id);
        this.access = access;
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, INV_X + col * 18, INV_Y + 58));
    }

    /** The parts a player may spend: main inventory, hotbar and offhand, never worn armour. */
    public static Container parts(Player who)
    {
        Inventory inv = who.getInventory();
        SimpleContainer view = new SimpleContainer(inv.items.size() + inv.offhand.size());
        // a view over the live stacks (same instances), so the plan's slot indices map straight back
        int i = 0;
        for (ItemStack s : inv.items)
            view.setItem(i++, s);
        for (ItemStack s : inv.offhand)
            view.setItem(i++, s);
        return view;
    }

    @Override
    public boolean clickMenuButton(Player who, int id)
    {
        if (!(who instanceof ServerPlayer sp))
            return false;
        List<VehicleRecipe> recipes = VehicleWorkbenchRegistry.sorted(sp.serverLevel().getRecipeManager());
        if (id < 0 || id >= recipes.size())
            return false;
        VehicleRecipe recipe = recipes.get(id);
        Container view = parts(sp);
        int[] take = recipe.plan(view);
        if (take == null)
        {
            sp.displayClientMessage(Component.translatable("message.dmz_ragnarok.vehicle_workbench.missing"), true);
            return false;
        }
        ItemStack built = recipe.assemble(view, sp.serverLevel().registryAccess());
        if (built.isEmpty())
            return false;
        for (int i = 0; i < take.length; i++)
            if (take[i] > 0)
                view.getItem(i).shrink(take[i]);
        // shrink(0) leaves count-0 stacks behind; vanilla treats those as empty, but clear them so they sync as such
        Inventory inv = sp.getInventory();
        for (int i = 0; i < inv.items.size(); i++)
            if (inv.items.get(i).isEmpty())
                inv.items.set(i, ItemStack.EMPTY);
        for (int i = 0; i < inv.offhand.size(); i++)
            if (inv.offhand.get(i).isEmpty())
                inv.offhand.set(i, ItemStack.EMPTY);

        built.onCraftedBy(sp.level(), sp, built.getCount());
        sp.awardStat(Stats.ITEM_CRAFTED.get(built.getItem()), built.getCount());
        net.minecraftforge.event.ForgeEventFactory.firePlayerCraftingEvent(sp, built, view);
        Component name = built.getHoverName();
        inv.add(built); // shrinks built to whatever did not fit
        if (!built.isEmpty())
            sp.drop(built, false);
        inv.setChanged();
        access.execute((level, pos) -> level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS,
                1.0F, 1.0F));
        sp.displayClientMessage(Component.translatable("message.dmz_ragnarok.vehicle_workbench.built", name), true);
        broadcastChanges();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player who, int index)
    {
        // shift-click moves between the main inventory and the hotbar, like a crafting table with an empty grid
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem())
            return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        boolean inMain = index < 27;
        if (!moveItemStackTo(stack, inMain ? 27 : 0, inMain ? 36 : 27, false))
            return ItemStack.EMPTY;
        if (stack.isEmpty())
            slot.set(ItemStack.EMPTY);
        else
            slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player who)
    {
        return access == ContainerLevelAccess.NULL
                || stillValid(access, who, SpaceConsoleBlocks.SPACE_CONSOLE_A.get());
    }
}
