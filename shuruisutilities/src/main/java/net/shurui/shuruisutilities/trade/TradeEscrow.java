package net.shurui.shuruisutilities.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Durable custody for every item a player has staged into a live trade. Keyed by player UUID, one list of offered
 * stacks per player, held ONLY here from the moment a stack is taken off a trader (see {@code TradeSession.add}, in
 * the Ragnarok Key) until it is returned to its owner or swapped to the other side on completion. It is never in a player's inventory
 * and in the escrow at the same time.
 *
 * <p>An overworld-attached {@link SavedData}, mirroring
 * {@link net.shurui.shuruisutilities.guilds.raid.GuildRaidVault} and
 * {@link net.shurui.shuruisutilities.auction.AuctionStore}: the old shared 54-slot container lived in memory only, so
 * a crash mid-trade lost every staged item. Persisting the escrow makes a crash a safe ROLLBACK instead: the pairing
 * and confirm flags (which live on the in-memory {@code TradeSession}) are gone after a restart, but the escrow
 * survives, so each trader's staged items are returned to that same trader on their next login (see
 * {@code ModuleTrade}, in the Ragnarok Key). A half-finished trade rolling back is always the correct
 * outcome, so this never needs to reconstruct the session itself.
 *
 * <h2>Why nothing dupes or is lost</h2>
 * <ul>
 *   <li>Every mutation that moves an item between an inventory and this store is done in ONE synchronous tick and
 *       marked with {@link #setDirty()} (never an immediate flush). Because the escrow co-persists with player data
 *       on the ordinary world save, a save captures either the pre-move state of BOTH sides or the post-move state of
 *       BOTH sides, so the item is counted exactly once across a crash.</li>
 *   <li>The store is keyed by the OWNER, so on {@link #takeAll} for a swap or a cancel we clear the owner's list and
 *       hand the items over; a crash before the next world save reverts the store to its last-saved contents, which
 *       still credit the items to their original owners, so they come back to those owners on login. That is strictly
 *       safer than a claim-queue escrow (no owner to revert to), so no flush-before-handover is needed here.</li>
 *   <li>{@link #giveOrDrop} adds to the inventory and drops only the overflow, so a full inventory can never destroy a
 *       returned or received item.</li>
 * </ul>
 */
public final class TradeEscrow extends SavedData
{
    private static final String NAME = "shuruisutilities_trade_escrow";

    // player uuid -> that player's currently-offered stacks. ConcurrentHashMap only because a login-return read and a
    // trade-action write can both touch it from the server thread; all real mutations run on the server thread.
    private final Map<UUID, List<ItemStack>> offers = new ConcurrentHashMap<>();

    public static TradeEscrow get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(TradeEscrow::load, TradeEscrow::new, NAME);
    }

    private static TradeEscrow load(CompoundTag tag)
    {
        TradeEscrow s = new TradeEscrow();
        ListTag list = tag.getList("offers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag e = list.getCompound(i);
            UUID player = e.getUUID("player");
            List<ItemStack> stacks = new ArrayList<>();
            for (Tag t : e.getList("items", Tag.TAG_COMPOUND))
            {
                // Player property: normalize a pre-merge item id in the staged stack before decoding, so a crash
                // rollback returns the real merged item to its owner instead of an empty stack (see AuctionListing).
                ItemStack stack = ItemStack.of(
                        net.shurui.shuruisutilities.ragnarok.LegacyIds.normalizeItemTag((CompoundTag) t));
                if (!stack.isEmpty())
                {
                    stacks.add(stack);
                }
            }
            if (!stacks.isEmpty())
            {
                s.offers.put(player, stacks);
            }
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, List<ItemStack>> me : offers.entrySet())
        {
            if (me.getValue().isEmpty())
            {
                continue;
            }
            CompoundTag e = new CompoundTag();
            e.putUUID("player", me.getKey());
            ListTag items = new ListTag();
            for (ItemStack stack : me.getValue())
            {
                items.add(stack.save(new CompoundTag()));
            }
            e.put("items", items);
            list.add(e);
        }
        tag.put("offers", list);
        return tag;
    }

    /** Copy of this player's offered stacks (for building a client view); empty if none. */
    public List<ItemStack> offer(UUID player)
    {
        List<ItemStack> stacks = offers.get(player);
        if (stacks == null || stacks.isEmpty())
        {
            return List.of();
        }
        List<ItemStack> copy = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks)
        {
            copy.add(stack.copy());
        }
        return copy;
    }

    public boolean hasAny(UUID player)
    {
        List<ItemStack> stacks = offers.get(player);
        return stacks != null && !stacks.isEmpty();
    }

    /** Stage a stack (already taken off the owner by the caller) into this player's offer. */
    public void add(UUID player, ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return;
        }
        offers.computeIfAbsent(player, k -> new ArrayList<>()).add(stack);
        setDirty();
    }

    /** Pull the stack at index out of this player's offer and return it (EMPTY if the index is invalid). */
    public ItemStack removeAt(UUID player, int index)
    {
        List<ItemStack> stacks = offers.get(player);
        if (stacks == null || index < 0 || index >= stacks.size())
        {
            return ItemStack.EMPTY;
        }
        ItemStack stack = stacks.remove(index);
        if (stacks.isEmpty())
        {
            offers.remove(player);
        }
        setDirty();
        return stack;
    }

    /** Remove and return this player's whole offer, clearing it. Used for a swap, a cancel or a login-return. */
    public List<ItemStack> takeAll(UUID player)
    {
        List<ItemStack> stacks = offers.remove(player);
        setDirty();
        return stacks == null ? new ArrayList<>() : stacks;
    }

    /**
     * Return a stack to a player who is being handed to ANOTHER SHARD in this same tick.
     *
     * <p>Same first move as {@link #giveOrDrop}: into the inventory, which is correct here because this runs from
     * {@code PreHopTeardown}, before {@code ShardPayload.capture} seals the vault copy, so the items travel with
     * them. What must NOT happen is the overflow behaviour: {@code drop} would leave the stacks lying on the floor
     * of a server the player is leaving in a fraction of a second, to despawn unwatched five minutes later. So
     * whatever does not fit goes straight back into this escrow, under its own owner, where the module's ordinary
     * login return hands it over the next time they are on this shard. Durable beats tidy.
     *
     * @param escrow the store the stack came out of, passed in so this cannot pick the wrong world's copy.
     */
    public static void giveOrKeep(ServerPlayer player, TradeEscrow escrow, ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return;
        }
        player.getInventory().add(stack); // mutates stack down to whatever did not fit
        if (!stack.isEmpty() && escrow != null)
        {
            escrow.add(player.getUUID(), stack.copy());
        }
        player.inventoryMenu.broadcastChanges();
    }

    /** Add to the player's inventory, dropping to the ground whatever does not fit, so an item can never be lost. */
    public static void giveOrDrop(ServerPlayer player, ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return;
        }
        player.getInventory().add(stack); // mutates stack down to whatever did not fit
        if (!stack.isEmpty())
        {
            player.drop(stack, false);
        }
        player.inventoryMenu.broadcastChanges();
    }
}
