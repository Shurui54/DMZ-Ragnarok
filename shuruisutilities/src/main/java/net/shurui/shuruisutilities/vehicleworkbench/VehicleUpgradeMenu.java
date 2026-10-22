package net.shurui.shuruisutilities.vehicleworkbench;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;

import net.shurui.shuruisutilities.spaceconsole.SpaceConsoleBlocks;

/**
 * The compact console's upgrade bench: five vehicle slots, one kit slot, and an Upgrade button.
 *
 * <p>Server authoritative like the workbench: the button only says "upgrade", the server re-plans from its own slots
 * ({@link VehicleUpgrades#plan}), consumes and puts the result in the first vehicle slot, so a kit chain can be run by
 * pressing again. The server's max tier and switches reach the screen through data slots, so its preview uses the
 * same limits. Nothing is stored in the block: the slots are handed back when the screen closes.
 */
public class VehicleUpgradeMenu extends AbstractContainerMenu
{
    public static final int VEHICLE_SLOTS = 5;
    public static final int KIT_SLOT = VEHICLE_SLOTS;
    public static final int BENCH_SLOTS = VEHICLE_SLOTS + 1;

    public static final int VEHICLE_X = 12;
    public static final int VEHICLE_Y = 40;
    public static final int KIT_X = 12;
    public static final int KIT_Y = 76;
    public static final int INV_X = 35;
    public static final int INV_Y = 128;

    public static final int BTN_UPGRADE = 0;

    private final Container bench = new SimpleContainer(BENCH_SLOTS)
    {
        @Override
        public void setChanged()
        {
            super.setChanged();
            VehicleUpgradeMenu.this.slotsChanged(this);
        }
    };
    private final ContainerData limits;
    private final ContainerLevelAccess access;

    public VehicleUpgradeMenu(int id, Inventory inv)
    {
        this(id, inv, ContainerLevelAccess.NULL);
    }

    public VehicleUpgradeMenu(int id, Inventory inv, ContainerLevelAccess access)
    {
        super(VehicleWorkbenchRegistry.UPGRADE_MENU.get(), id);
        this.access = access;
        this.limits = new SimpleContainerData(2);
        if (access != ContainerLevelAccess.NULL)
        {
            limits.set(0, VehicleTiers.maxTier());
            limits.set(1, (VehicleTierConfig.mergeEnabled ? 1 : 0) | (VehicleTierConfig.kitsEnabled ? 2 : 0));
        }
        addDataSlots(limits);

        for (int i = 0; i < VEHICLE_SLOTS; i++)
            addSlot(new Slot(bench, i, VEHICLE_X + i * 20, VEHICLE_Y)
            {
                @Override
                public boolean mayPlace(ItemStack stack)
                {
                    return VehicleTiers.isTiered(stack);
                }

                @Override
                public int getMaxStackSize()
                {
                    return 1;
                }
            });
        addSlot(new Slot(bench, KIT_SLOT, KIT_X, KIT_Y)
        {
            @Override
            public boolean mayPlace(ItemStack stack)
            {
                return stack.is(VehicleTiers.UPGRADE_KITS);
            }
        });

        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, INV_X + col * 18, INV_Y + 58));
    }

    public int maxTier()
    {
        return Math.max(1, limits.get(0));
    }

    public boolean mergeEnabled()
    {
        return (limits.get(1) & 1) != 0;
    }

    public boolean kitsEnabled()
    {
        return (limits.get(1) & 2) != 0;
    }

    public List<ItemStack> vehicles()
    {
        List<ItemStack> out = new ArrayList<>(VEHICLE_SLOTS);
        for (int i = 0; i < VEHICLE_SLOTS; i++)
            out.add(bench.getItem(i));
        return out;
    }

    public ItemStack kit()
    {
        return bench.getItem(KIT_SLOT);
    }

    public VehicleUpgrades.Plan plan(RecipeManager recipes)
    {
        return VehicleUpgrades.plan(vehicles(), kit(), recipes, maxTier(), mergeEnabled(), kitsEnabled());
    }

    @Override
    public boolean clickMenuButton(Player who, int id)
    {
        if (id != BTN_UPGRADE || !(who instanceof ServerPlayer sp))
            return false;
        // re-read the live config rather than the data slots, which are only the client's copy of it
        VehicleUpgrades.Plan plan = VehicleUpgrades.plan(vehicles(), kit(), sp.serverLevel().getRecipeManager(),
                VehicleTiers.maxTier(), VehicleTierConfig.mergeEnabled, VehicleTierConfig.kitsEnabled);
        if (!plan.ok())
        {
            sp.displayClientMessage(plan.problem(), true);
            return false;
        }
        for (int slot : plan.vehicleSlots())
            bench.setItem(slot, ItemStack.EMPTY);
        if (plan.kits() > 0)
            bench.getItem(KIT_SLOT).shrink(plan.kits());
        ItemStack result = plan.result().copy();
        bench.setItem(0, result);
        access.execute((level, pos) -> level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.8F, 1.2F));
        sp.displayClientMessage(Component.translatable("message.dmz_ragnarok.vehicle_upgrade.done",
                result.getHoverName(), VehicleTiers.label(VehicleTiers.tier(result))), true);
        broadcastChanges();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player who, int index)
    {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem())
            return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < BENCH_SLOTS)
        {
            if (!moveItemStackTo(stack, BENCH_SLOTS, slots.size(), true))
                return ItemStack.EMPTY;
        }
        else if (VehicleTiers.isTiered(stack))
        {
            if (!moveItemStackTo(stack, 0, VEHICLE_SLOTS, false))
                return ItemStack.EMPTY;
        }
        else if (stack.is(VehicleTiers.UPGRADE_KITS))
        {
            if (!moveItemStackTo(stack, KIT_SLOT, KIT_SLOT + 1, false))
                return ItemStack.EMPTY;
        }
        else
            return ItemStack.EMPTY;
        if (stack.isEmpty())
            slot.set(ItemStack.EMPTY);
        else
            slot.setChanged();
        return copy;
    }

    @Override
    public void removed(Player who)
    {
        super.removed(who);
        // hand everything back: the bench keeps nothing, so a closed screen can never strand a vehicle
        access.execute((level, pos) -> clearContainer(who, bench));
    }

    @Override
    public boolean stillValid(Player who)
    {
        return access == ContainerLevelAccess.NULL
                || stillValid(access, who, SpaceConsoleBlocks.SPACE_CONSOLE_B.get());
    }
}
