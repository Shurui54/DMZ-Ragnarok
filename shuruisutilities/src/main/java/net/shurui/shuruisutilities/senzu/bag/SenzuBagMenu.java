package net.shurui.shuruisutilities.senzu.bag;

import net.shurui.shuruisutilities.client.gui.SUMenus;
import net.shurui.shuruisutilities.compat.curios.SenzuBagCurios;
import net.shurui.shuruisutilities.senzu.SenzuRegistry;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/**
 * Server-authoritative container for a single equipped senzu bean bag. The six bag slots are backed by an
 * {@link ItemStackHandler}; on the server that handler writes straight back onto the live equipped bag stack and
 * re-syncs it through Curios on every change, so the server is the sole authority on what the bag holds and the
 * client only ever mirrors it. Modelled directly on {@code DragonBallBagMenu}.
 *
 * <p>The bag slots accept ONLY beans (any {@link net.shurui.shuruisutilities.senzu.BeanItem} plus the golden
 * death-totem beans, i.e. anything {@link SenzuRegistry#isBean(net.minecraft.world.item.Item)} recognises) and reject
 * everything else. That check is enforced here server-side in the click pipeline (mayPlace is re-run on the server),
 * so a hacked client cannot stuff a non-bean into the bag. Unlike the dragon ball bag there is no cross-slot
 * containment mixin to satisfy, because beans are ordinary items that are also allowed anywhere else, so this menu
 * needs no marker interface on its slots.
 *
 * <p>No bean can be lost here: the write-through handler always serialises its full contents back, and closing the
 * menu persists once more. If the bag is unequipped while open, {@link #stillValid} closes the screen rather than
 * letting edits target a stale stack.
 */
public class SenzuBagMenu extends AbstractContainerMenu
{
    private static final int BAG_SLOTS = SenzuBagInventory.SIZE; // 6
    private static final int BAG_START = 0;
    private static final int BAG_END = BAG_SLOTS;

    // Bag slot positions are the top-left pixels of each 16x16 socket painted on the commissioned bag artwork
    // (assets/shuruisutilities/textures/gui/senzu_bag.png). The pocket on the bag body is a 3x2 grid of SIX sockets,
    // not a straight inventory row, so the slots are placed one-per-socket to sit dead centre inside each drawn
    // holder. Coordinates were read straight off the 256x256 sheet: the pocket's dark socket interiors are exactly
    // 16px each, with 1px divider lines between them, so a 16px item at the interior's top-left fills the socket.
    // The three columns start at x=62/80/98 (18px pitch, dividers at x=61/79/97) and the two rows start at y=86/104
    // (18px pitch, dividers at y=85/103). Layout, left-to-right then top-to-bottom:
    //   top row (3):    (62,86)  (80,86)  (98,86)
    //   bottom row (3): (62,104) (80,104) (98,104)
    private static final int[] BAG_SLOT_X = {62, 80, 98, 62, 80, 98};
    private static final int[] BAG_SLOT_Y = {86, 86, 86, 104, 104, 104};

    // server-only: who opened it. null on the client, which only renders the synced slot contents.
    private final Player owner;

    /** Client-side factory constructor: an empty local handler that the server fills via container sync. */
    public SenzuBagMenu(int containerId, Inventory playerInv)
    {
        this(containerId, playerInv, null, new ItemStackHandler(BAG_SLOTS));
    }

    /** Server-side constructor: a write-through handler bound to the player's live equipped bag stack. */
    public SenzuBagMenu(int containerId, Inventory playerInv, Player owner, ItemStack bagStack)
    {
        this(containerId, playerInv, owner, new BagBackedHandler(owner, bagStack));
    }

    private SenzuBagMenu(int containerId, Inventory playerInv, Player owner, IItemHandler backing)
    {
        super(SUMenus.SENZU_BAG.get(), containerId);
        this.owner = owner;

        // six bag slots, one per painted socket in the artwork's 3x2 pocket (see BAG_SLOT_X / BAG_SLOT_Y above). This
        // is a pure presentation move: the slots still back the same handler in the same order, so nothing about what
        // the bag stores, validates or persists changes.
        for (int i = 0; i < BAG_SLOTS; ++i)
        {
            addSlot(new BeanSlot(backing, i, BAG_SLOT_X[i], BAG_SLOT_Y[i]));
        }

        // Player inventory (3 rows) then hotbar. X is the standard vanilla 8 + col*18 (the artwork's grey grid uses
        // exactly those columns). Y lands the rows inside the grey inventory grid the sheet draws BELOW the bag
        // sprite: rows at 140/158/176 (18px pitch) and the hotbar at 198 (the usual +58 gap), all measured directly
        // off senzu_bag.png (its grid lines sit at y=139/157/175/197).
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

    // bag slot: accepts a bean only. The same isBean check runs on both sides, and vanilla re-runs mayPlace on the
    // server during the click pipeline, so a non-bean can never be placed even by a hacked client.
    private static final class BeanSlot extends SlotItemHandler
    {
        BeanSlot(IItemHandler handler, int index, int x, int y)
        {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack)
        {
            return SenzuRegistry.isBean(stack.getItem());
        }

        // Tracker #949. Vanilla changes a slot's stack IN PLACE in some paths (a shift-click merging into a stack that
        // is already in the bag, a shift-click out of the bag that only partly fits) and then only calls
        // setChanged(). SlotItemHandler does not forward setChanged() to the handler, so onContentsChanged never ran
        // and the bag's NBT kept the old count: the menu showed the merged stack, and on close the bag reopened
        // with only what had been written before (one bean of a shift-clicked stack). Persist here as well.
        @Override
        public void setChanged()
        {
            super.setChanged();
            if (getItemHandler() instanceof BagBackedHandler bag)
            {
                bag.persist();
            }
        }
    }

    // write-through handler: initialised from the live equipped bag's NBT, and on every content change it writes the
    // full contents back onto that same stack and nudges Curios to re-sync. Because it edits the live equipped stack,
    // the bag's contents are always persisted the instant they change; no bean can be stranded in a menu.
    private static final class BagBackedHandler extends ItemStackHandler
    {
        private final Player owner;
        private final ItemStack bag;

        BagBackedHandler(Player owner, ItemStack bag)
        {
            super(BAG_SLOTS);
            this.owner = owner;
            this.bag = bag;
            ItemStackHandler loaded = SenzuBagInventory.read(bag);

            // Copy the beans that still fit the current sockets. Direct field set, not setStackInSlot, so loading
            // does not fire a redundant write-back.
            for (int i = 0; i < BAG_SLOTS; ++i)
            {
                this.stacks.set(i, loaded.getStackInSlot(i));
            }

            // MIGRATION for legacy bags. An older build saved this bag with more slots than the art now paints (it
            // was nine slots, "one inventory row", before the six-socket artwork existed). Reading such a bag into
            // this smaller handler would strand every bean that lived in the now-removed slots. Losing a player's
            // beans to a capacity change is the one unacceptable outcome, so hand each overflow bean straight back to
            // the player rather than letting it vanish.
            boolean migrated = false;
            for (int i = BAG_SLOTS; i < loaded.getSlots(); ++i)
            {
                ItemStack overflow = loaded.getStackInSlot(i);
                if (!overflow.isEmpty())
                {
                    giveBack(owner, overflow.copy());
                    migrated = true;
                }
            }
            if (migrated)
            {
                // Persist the shrunk contents at once so the removed slots are gone from storage. If we did not, the
                // overflow beans would still sit in the stored NBT (which is still nine slots wide until the first
                // real edit) and be handed out AGAIN on the next open, duplicating them.
                SenzuBagInventory.write(bag, this);
                SenzuBagCurios.persist(owner, bag);
            }
        }

        @Override
        protected void onContentsChanged(int slot)
        {
            persist();
        }

        void persist()
        {
            SenzuBagInventory.write(bag, this);
            SenzuBagCurios.persist(owner, bag);
        }

        // Return a recovered bean to the player: into the inventory if it fits, otherwise dropped at the player's
        // feet so it can never simply disappear. Server-side only, since this handler is only ever built with a
        // non-null owner (the client uses a plain ItemStackHandler that never runs this migration).
        private static void giveBack(Player owner, ItemStack stack)
        {
            owner.getInventory().add(stack);
            if (!stack.isEmpty())
            {
                owner.drop(stack, false);
            }
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
            // player inventory -> bag. moveItemStackTo respects BeanSlot.mayPlace, so non-beans simply do not move.
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
        return SenzuBagCurios.hasEquipped(owner);
    }
}
