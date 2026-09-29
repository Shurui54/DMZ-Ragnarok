package net.shurui.shuruisutilities.runes;

import java.util.Random;

import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.economy.EconomyManager;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

/**
 * The rune bench's container: one armour slot, one rune slot, and buttons for socketing and for prising a rune back
 * out of either slot.
 *
 * <p>Everything is done server side in {@link #clickMenuButton}. The screen only sends which button was pressed, so
 * a client cannot socket a rune it does not have, empty a slot that is not filled, or dodge the damage roll on
 * removal by lying about the result.
 *
 * <p>The container is transient and its contents are returned to the player on close, so nothing can be left in the
 * bench or destroyed with the block.
 */
public class RuneBenchMenu extends AbstractContainerMenu
{
    public static final int SLOT_ARMOR = 0;
    public static final int SLOT_RUNE = 1;

    /** Button ids the screen sends. Removal buttons are offset by socket index. */
    public static final int BTN_SOCKET = 0;
    public static final int BTN_REMOVE_BASE = 1;
    /** Sits after the removal ids so adding a socket slot later does not renumber it. */
    public static final int BTN_AWAKEN = 1 + ArmorRunes.SOCKETS;

    private final Container bench = new SimpleContainer(2)
    {
        @Override
        public void setChanged()
        {
            super.setChanged();
            RuneBenchMenu.this.slotsChanged(this);
        }
    };

    private final ContainerLevelAccess access;
    private final Player player;
    private final Random rng = new Random();

    /** Client constructor: no world access, the screen only mirrors slots and sends button ids. */
    public RuneBenchMenu(int id, Inventory inv)
    {
        this(id, inv, ContainerLevelAccess.NULL);
    }

    public RuneBenchMenu(int id, Inventory inv, ContainerLevelAccess access)
    {
        super(RuneBenchRegistry.MENU.get(), id);
        this.access = access;
        this.player = inv.player;

        // armour slot: only armour, and only one piece
        addSlot(new Slot(bench, SLOT_ARMOR, 26, 48)
        {
            @Override
            public boolean mayPlace(ItemStack stack)
            {
                return stack.getItem() instanceof ArmorItem;
            }

            @Override
            public int getMaxStackSize()
            {
                return 1;
            }
        });
        // rune slot: any rune. A dormant one cannot be socketed, but it IS what the awaken button consumes, so
        // refusing it here would leave the player holding a rune the bench visibly will not take.
        addSlot(new Slot(bench, SLOT_RUNE, 44, 48)
        {
            @Override
            public boolean mayPlace(ItemStack stack)
            {
                return stack.getItem() instanceof RuneItem;
            }
        });

        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, 8 + col * 18, 142));
    }

    public ItemStack armor()
    {
        return bench.getItem(SLOT_ARMOR);
    }

    public ItemStack rune()
    {
        return bench.getItem(SLOT_RUNE);
    }

    @Override
    public boolean clickMenuButton(Player who, int id)
    {
        if (who.level().isClientSide)
            return false;
        // Awakening is about the rune alone, so it is answered before the armour checks below.
        if (id == BTN_AWAKEN)
            return doAwaken();

        ItemStack armor = armor();
        if (armor.isEmpty() || !ArmorRunes.has(armor))
        {
            // an unrolled piece can still be worked on: give it its tier now rather than refusing
            if (!armor.isEmpty())
                ArmorRunes.ensureRolled(armor, rng);
            else
                return false;
        }

        if (id == BTN_SOCKET)
            return doSocket(armor);
        int index = id - BTN_REMOVE_BASE;
        if (index >= 0 && index < ArmorRunes.SOCKETS)
            return doRemove(who, armor, index);
        return false;
    }

    /**
     * Turn one dormant rune into a stat rune. The stat is rolled, not chosen: a dormant rune is the raw material a
     * broken piece leaves behind, and letting a player pick would make every colour equally common and remove any
     * reason to trade for one. The tier is rolled too, exactly as a crafted rune's is.
     */
    private boolean doAwaken()
    {
        ItemStack slot = rune();
        if (!(slot.getItem() instanceof RuneItem rune) || !rune.isDormant())
            return false;
        // Take the Zeni before anything is consumed or handed back: trySpend either debits in full or does nothing,
        // so a failed charge leaves the dormant rune untouched and the player never pays for a rune they do not get.
        // A cost of zero is today's free awakening, so skip the economy entirely rather than round-tripping a no-op.
        long cost = SUConfig.runeAwakenZeniCost;
        // The runes are public but the economy is not: without it the same configured amount is charged in DMZ
        // training points (TP) instead, so a keyless server can still awaken runes. With the economy, Zeni as before.
        if (cost > 0 && !net.shurui.shuruisutilities.api.key.EconomyHooks.available())
        {
            if (!spendTrainingPoints(cost))
                return false;
        }
        else if (cost > 0 && !EconomyManager.trySpend(player.getUUID(), cost))
        {
            ChatOutputHandler.chatError(player, "You need " + cost + " Zeni to awaken this rune (you have "
                    + EconomyManager.getBalance(player.getUUID()) + ").");
            return false;
        }
        RuneStat[] all = RuneStat.values();
        ItemStack awoken = RuneItem.rolled(all[rng.nextInt(all.length)], rng);
        slot.shrink(1);
        player.getInventory().add(awoken); // mutates awoken down to whatever did not fit
        if (!awoken.isEmpty())
            player.drop(awoken, false);
        bench.setChanged();
        return true;
    }

    /**
     * The keyless awakening charge: takes {@code cost} TP through DMZ's own resources and resyncs them, or refuses
     * (and says why) without taking anything. Like the Zeni path, it runs before the dormant rune is consumed.
     */
    private boolean spendTrainingPoints(long cost)
    {
        try
        {
            var stats = player.getCapability(com.dragonminez.common.stats.StatsCapability.INSTANCE).resolve();
            if (stats.isEmpty())
                return false;
            var resources = stats.get().getResources();
            float have = resources.getTrainingPoints();
            if (have < cost)
            {
                ChatOutputHandler.chatError(player, "You need " + cost + " TP to awaken this rune (you have "
                        + (long) have + ").");
                return false;
            }
            resources.removeTrainingPoints(cost);
            if (player instanceof net.minecraft.server.level.ServerPlayer sp)
                com.dragonminez.common.network.NetworkHandler.sendToPlayer(
                        new com.dragonminez.common.network.S2C.ResourceSyncS2C(sp), sp);
            return true;
        }
        catch (Throwable t)
        {
            // A DMZ API shift must not hand out a free rune: refuse the awakening instead.
            return false;
        }
    }

    private boolean doSocket(ItemStack armor)
    {
        ItemStack runeStack = rune();
        if (!(runeStack.getItem() instanceof RuneItem rune) || rune.isDormant())
            return false;
        // an admin rune fills the piece outright and needs no free socket: filling every stat would otherwise cost
        // six sockets, which no piece has.
        if (rune.isAdmin())
        {
            if (!ArmorRunes.socketAdmin(armor, rune.isAllStats() ? null : rune.stat(), rng))
                return false;
            runeStack.shrink(1);
            bench.setChanged();
            return true;
        }
        if (ArmorRunes.freeSockets(armor) <= 0)
            return false;
        // Roll the tier here too if it somehow arrived unstamped: this is the point where it decides how much the
        // rune is worth, and reading an unrolled stack as LOW is exactly the bug that made greater runes useless.
        if (!ArmorRunes.socket(armor, rune.stat(), RuneItem.ensureTier(runeStack, rng), rng))
            return false;
        runeStack.shrink(1);
        bench.setChanged();
        return true;
    }

    /**
     * Prise a rune out. The outcome is rolled here, on the server, and whatever survives is handed straight to the
     * player rather than placed in a slot: the rune slot may be occupied, and a result that quietly vanished because
     * a slot was full would be indistinguishable from the rune breaking.
     */
    private boolean doRemove(Player who, ItemStack armor, int index)
    {
        ArmorRunes.Socket socket = ArmorRunes.unsocket(armor, index);
        if (socket == null)
            return false;
        RuneExtraction.Outcome outcome = RuneExtraction.extract(socket, rng);
        ItemStack extracted = outcome.rune();
        if (!extracted.isEmpty())
        {
            who.getInventory().add(extracted); // mutates extracted down to whatever did not fit
            if (!extracted.isEmpty())
                who.drop(extracted, false);
        }

        who.displayClientMessage(switch (outcome.result())
        {
            case INTACT -> Component.translatable("message.dmz_ragnarok.rune.removed_intact",
                    Component.literal(socket.stat().label()).withStyle(socket.stat().colour()));
            case DEGRADED -> Component.translatable("message.dmz_ragnarok.rune.removed_degraded",
                    Component.literal(socket.stat().label()).withStyle(socket.stat().colour()));
            case BROKEN -> Component.translatable("message.dmz_ragnarok.rune.removed_broken",
                    Component.literal(socket.stat().label()).withStyle(socket.stat().colour()));
        }, true);
        bench.setChanged();
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
        int benchSlots = 2;
        if (index < benchSlots)
        {
            if (!moveItemStackTo(stack, benchSlots, slots.size(), true))
                return ItemStack.EMPTY;
        }
        else if (!moveItemStackTo(stack, 0, benchSlots, false))
        {
            return ItemStack.EMPTY;
        }
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
        // hand everything back: the bench keeps nothing, so a closed screen can never strand an armour piece
        access.execute((level, pos) -> clearContainer(who, bench));
    }

    @Override
    public boolean stillValid(Player who)
    {
        return access == ContainerLevelAccess.NULL
                || stillValid(access, who, RuneBenchRegistry.BENCH.get());
    }
}
