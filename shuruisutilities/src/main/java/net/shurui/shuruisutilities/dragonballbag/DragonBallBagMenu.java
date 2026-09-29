package net.shurui.shuruisutilities.dragonballbag;

import net.shurui.shuruisutilities.client.gui.SUMenus;
import net.shurui.shuruisutilities.compat.curios.DragonBallBagCurios;
import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/**
 * Server-authoritative container for a single equipped dragon ball bag. The seven bag slots are backed by an
 * {@link ItemStackHandler}; on the server that handler writes straight back onto the live equipped bag stack and
 * re-syncs it through Curios on every change, so the server is the sole authority on what the bag holds and the
 * client only ever mirrors it.
 *
 * <p>The bag slots accept ONLY dragon balls, and only of the set the player is already collecting (see
 * {@link DragonBallCarry#carriedSet}). That enforces "one dragon at a time" inside the bag GUI in addition to the
 * pickup handler, and it is re-checked server-side in the click pipeline so a hacked client cannot bypass it.
 *
 * <p>No item can be lost here: the write-through handler always serialises its full contents back, and closing the
 * menu persists once more. If the bag is unequipped while open, {@link #stillValid} closes the screen rather than
 * letting edits target a stale stack.
 */
public class DragonBallBagMenu extends AbstractContainerMenu
{
    private static final int BAG_SLOTS = DragonBallBagInventory.SIZE; // 7
    private static final int BAG_START = 0;
    private static final int BAG_END = BAG_SLOTS;

    // Bag slot positions are the top-left pixels of each 16x16 socket drawn on the commissioned bag artwork
    // (assets/shuruisutilities/textures/gui/dragonball_bag.png). The seven dragon ball holders are painted as a
    // hexagonal cluster on the character's belly, NOT a straight row, so the slots are placed one-per-socket to sit
    // dead centre inside each drawn holder. Coordinates were read off the 256x256 sheet: the socket centres measured
    // at rows y=89/101/113 and columns x=65/74/83/92/101, and each top-left is that centre minus 8 (half of a 16px
    // item). Layout, left-to-right then top-to-bottom:
    //   top row (2):    (66,81) (84,81)
    //   middle row (3): (57,93) (75,93) (93,93)
    //   bottom row (2): (66,105) (84,105)
    private static final int[] BAG_SLOT_X = {66, 84, 57, 75, 93, 66, 84};
    private static final int[] BAG_SLOT_Y = {81, 81, 93, 93, 93, 105, 105};

    // server-only: who opened it. null on the client, which only renders the synced slot contents.
    private final Player owner;

    /** Client-side factory constructor: an empty local handler that the server fills via container sync. */
    public DragonBallBagMenu(int containerId, Inventory playerInv)
    {
        this(containerId, playerInv, null, new DragonBallBagHandler(BAG_SLOTS));
    }

    /** Server-side constructor: a write-through handler bound to the player's live equipped bag stack. */
    public DragonBallBagMenu(int containerId, Inventory playerInv, Player owner, ItemStack bagStack)
    {
        this(containerId, playerInv, owner, new BagBackedHandler(owner, bagStack));
    }

    private DragonBallBagMenu(int containerId, Inventory playerInv, Player owner, IItemHandler backing)
    {
        super(SUMenus.DRAGONBALL_BAG.get(), containerId);
        this.owner = owner;

        // seven bag slots, one per star, placed on the hexagonal socket cluster the artwork paints (see BAG_SLOT_X /
        // BAG_SLOT_Y above). This is a pure presentation move: the slots still back the same handler in the same
        // order, so nothing about what the bag stores, validates or persists changes.
        for (int i = 0; i < BAG_SLOTS; ++i)
        {
            addSlot(new BagSlot(backing, i, BAG_SLOT_X[i], BAG_SLOT_Y[i]));
        }

        // Player inventory (3 rows) then hotbar. X is the standard vanilla 8 + col*18 (the artwork's grey grid uses
        // exactly those columns). Y is pushed down to y=140 so the rows land inside the inventory grid the sheet
        // draws BELOW the bag sprite: rows at 140/158/176 (18px pitch) and the hotbar at 198 (the usual +58 gap),
        // all measured directly off dragonball_bag.png.
        for (int row = 0; row < 3; ++row)
        {
            for (int col = 0; col < 9; ++col)
            {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
            }
        }
        for (int col = 0; col < 9; ++col)
        {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 198));
        }
    }

    // bag slot: accepts a dragon ball only, and only of the set the player is already collecting (or any set when
    // the player carries none yet). implements the marker so the containment mixin treats this as an allowed home
    // for balls.
    private final class BagSlot extends SlotItemHandler implements DragonBallBagSlot
    {
        BagSlot(IItemHandler handler, int index, int x, int y)
        {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack)
        {
            if (!DragonBallSets.isDragonBall(stack))
            {
                return false;
            }
            String incoming = DragonBallSets.setIdOf(stack);
            if (incoming == null)
            {
                return false;
            }
            String carried = owner == null ? null : DragonBallCarry.carriedSet(owner);
            // no set collected yet -> this ball defines it; otherwise it must match.
            return carried == null || carried.equals(incoming);
        }
    }

    // write-through handler: initialised from the live equipped bag's NBT, and on every content change it writes the
    // full contents back onto that same stack and nudges Curios to re-sync. Because it edits the live equipped
    // stack, the bag's contents are always persisted the instant they change; no item can be stranded in a menu.
    private static final class BagBackedHandler extends DragonBallBagHandler
    {
        private final Player owner;
        private final ItemStack bag;

        BagBackedHandler(Player owner, ItemStack bag)
        {
            super(BAG_SLOTS);
            this.owner = owner;
            this.bag = bag;
            ItemStackHandler loaded = DragonBallBagInventory.read(bag);
            for (int i = 0; i < BAG_SLOTS; ++i)
            {
                // direct field set, not setStackInSlot, so loading does not fire a redundant write-back.
                this.stacks.set(i, loaded.getStackInSlot(i));
            }
        }

        @Override
        protected void onContentsChanged(int slot)
        {
            DragonBallBagInventory.write(bag, this);
            DragonBallBagCurios.persist(owner, bag);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index)
    {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem())
        {
            return ItemStack.EMPTY;
        }
        ItemStack inSlot = slot.getItem();
        ItemStack copy = inSlot.copy();

        if (index < BAG_END)
        {
            // bag -> player inventory
            if (!moveItemStackTo(inSlot, BAG_END, this.slots.size(), true))
            {
                return ItemStack.EMPTY;
            }
        }
        else
        {
            // player inventory -> bag. moveItemStackTo respects BagSlot.mayPlace, so non-balls and wrong-set balls
            // simply do not move.
            if (!moveItemStackTo(inSlot, BAG_START, BAG_END, false))
            {
                return ItemStack.EMPTY;
            }
        }

        if (inSlot.isEmpty())
        {
            slot.set(ItemStack.EMPTY);
        }
        else
        {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player)
    {
        // client has no owner reference and just mirrors the server; server closes the window if the bag is no
        // longer equipped so edits can never target a stale stack.
        if (owner == null)
        {
            return true;
        }
        return DragonBallBagCurios.hasEquipped(owner);
    }
}
