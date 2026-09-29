package net.shurui.shuruisutilities.guilds.raid;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.items.IItemHandlerModifiable;

import net.shurui.shuruisutilities.compat.curios.CuriosAccess;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Holds, for a raider currently on a raid, their stashed inventory AND the exact spot to return them to when the
 * raid ends. It is the safety net for the single most dangerous part of the whole feature: a raider's real items
 * are taken off them on entry (like a no-items tournament) and this store is the ONLY place they live until the
 * raid resolves.
 *
 * <h2>Where the stash lives, and why it moved (2026-09)</h2>
 * The stash is kept in the raider's OWN persisted NBT (the {@code PlayerPersisted} sub-tag, exactly like {@link
 * GuildRaidParticipant} and {@link net.shurui.shuruisutilities.world.space.SurfaceTravelData}), NOT in a server-wide
 * {@link SavedData}. That single change is what makes the feature safe on a shard network.
 *
 * <p>On a multi-server (shard) network a player's whole persisted NBT is the unit that travels between backends:
 * {@code ShardPayload} captures {@code getPersistentData()} on the origin and restores it on the destination, in
 * the same single-owner player blob that carries their inventory. A SavedData stash does NOT travel: it stays on
 * the one server that took it, keyed by UUID, invisible to every other backend. So the old design had a data-loss
 * path: a raider who disconnected mid-raid and reconnected to a DIFFERENT backend was handed the EMPTIED inventory
 * the vault faithfully carried, while their real items sat behind in the raid server's SavedData, on a server they
 * might never return to. Keeping the stash in the player's own NBT closes that: the items ride with the player to
 * whichever backend they land on, as ONE atomic copy, and are handed back there by the very same login path.
 *
 * <p>Because the stash is a normal part of the player blob it is captured and restored ATOMICALLY with the rest of
 * their state. There is never a torn snapshot where the emptied inventory is durable but the stash is not (the flaw
 * a separate SavedData had): a capture always sees either the pre-stash state (items in the live inventory, no
 * stash tag) or the post-stash state (emptied inventory, stash tag present), never both and never neither. That is
 * why an item is guaranteed to exist in exactly one place at every instant.
 *
 * <h2>Legacy drain</h2>
 * This class is STILL a {@link SavedData} (name unchanged) purely so that any stash written by the PREVIOUS design
 * and still on disk at the moment this ships is honoured rather than orphaned: {@link #restore(ServerPlayer)} drains
 * such a legacy entry on the holding server, exactly as before. New stashes never write to it, so it only ever
 * shrinks and is empty once every raid that predates this change has resolved.
 *
 * <h2>How items come back in every case</h2>
 * Every path that ends a raider's participation calls {@link #restore(ServerPlayer)} (or, if the player is offline,
 * leaves the stash on their NBT so {@link #restore(ServerPlayer)} runs on their next login, wherever they land):
 * <ul>
 *   <li><b>Raid ends normally (win or loss) or times out:</b> {@code GuildRaid} resolution restores every online
 *       participant and returns them; an offline participant keeps the stash on their NBT and is restored on their
 *       next login, on any server.</li>
 *   <li><b>Raid aborted / guild disbands mid-raid:</b> both route through {@code GuildRaid#abort()} which reaches
 *       the same resolution, so the same restore runs.</li>
 *   <li><b>Player logs out mid-raid, then relogs (same server OR a different backend):</b> the stash rode with them
 *       in their player blob, so the login handler restores it the moment they return, whether or not the raid is
 *       still live and wherever they reconnected.</li>
 *   <li><b>Player dies mid-raid:</b> their live inventory during the raid holds only the entry kit, so a death drop
 *       loses nothing of value; the real items are on their NBT and are restored on resolution / next login.</li>
 *   <li><b>Server crashes and restarts mid-raid:</b> the live raid is gone (never persisted), but the stash was
 *       saved with the player's own data, so it survives; the login handler returns each raider's items on their
 *       next login and teleports them back to the recorded origin.</li>
 * </ul>
 */
public final class GuildRaidVault extends SavedData
{
    private static final String NAME = "shuruisutilities_guild_raid_vault";

    /** The stash sub-tag, kept under the player's {@code PlayerPersisted} compound so it survives relog AND travels
     *  between shard backends with the rest of the player blob. */
    private static final String STASH_TAG = "su_guild_raid_stash";

    // one stashed raider written by the PREVIOUS design: their taken items plus where to put them back. New code
    // never writes these; restore() drains any that are still on disk from before this change shipped.
    private static final class Entry
    {
        CompoundTag stash;      // main + offhand + curios (+ bonus), saved by the old store()
        ResourceLocation dim;   // origin dimension id
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
    }

    // player uuid -> legacy stash + return location
    private final Map<UUID, Entry> entries = new HashMap<>();

    public static GuildRaidVault get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(GuildRaidVault::load, GuildRaidVault::new, NAME);
    }

    private static GuildRaidVault load(CompoundTag tag)
    {
        GuildRaidVault s = new GuildRaidVault();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag e = list.getCompound(i);
            Entry entry = new Entry();
            entry.stash = e.getCompound("stash");
            entry.dim = new ResourceLocation(e.getString("dim"));
            entry.x = e.getDouble("x");
            entry.y = e.getDouble("y");
            entry.z = e.getDouble("z");
            entry.yaw = e.getFloat("yaw");
            entry.pitch = e.getFloat("pitch");
            s.entries.put(e.getUUID("player"), entry);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> me : entries.entrySet())
        {
            Entry entry = me.getValue();
            CompoundTag e = new CompoundTag();
            e.putUUID("player", me.getKey());
            e.put("stash", entry.stash);
            e.putString("dim", entry.dim.toString());
            e.putDouble("x", entry.x);
            e.putDouble("y", entry.y);
            e.putDouble("z", entry.z);
            e.putFloat("yaw", entry.yaw);
            e.putFloat("pitch", entry.pitch);
            list.add(e);
        }
        tag.put("entries", list);
        return tag;
    }

    // the player's PlayerPersisted compound, created if absent. Identical idiom to GuildRaidParticipant and
    // SurfaceTravelData, so the stash lives exactly where those markers do and travels the same way.
    private static CompoundTag persisted(Player player)
    {
        CompoundTag data = player.getPersistentData();
        CompoundTag pt = data.getCompound(Player.PERSISTED_NBT_TAG);
        if (!data.contains(Player.PERSISTED_NBT_TAG))
        {
            data.put(Player.PERSISTED_NBT_TAG, pt);
        }
        return pt;
    }

    private static boolean hasStashTag(Player player)
    {
        return persisted(player).contains(STASH_TAG, Tag.TAG_COMPOUND);
    }

    /**
     * True if this player currently has a raid stash: either the live one on their own NBT, or a legacy SavedData
     * entry left by the previous design. Takes the online player, because the authoritative store is now their own
     * persisted NBT, which is only readable while they are present (which is exactly when a restore can run).
     */
    public boolean has(ServerPlayer player)
    {
        return hasStashTag(player) || entries.containsKey(player.getUUID());
    }

    /**
     * Attach a REWARD stack to a raider's existing stash so it is handed over through the exact same delivery machinery
     * that returns their real items: immediately on resolution if they are online. Reusing the stash is what makes the
     * reward safe: it is stored with the player's own data, and its restore always teleports the player HOME before it
     * hands items over, so a bonus can never be dropped on the floor of a raid instance that is about to be torn down.
     *
     * <p>The reward is appended to the raider's live NBT stash, which is why this takes the ONLINE player: a bonus can
     * only be attached to somebody whose stash is loaded. A raider who is offline at resolution keeps their stashed
     * items (delivered on their next login, on any server) but is not handed the bonus, since there is no loaded stash
     * to attach it to. No-op if the player holds no stash (they never entered the raid, so there is nothing to reward).
     */
    public void addBonus(ServerPlayer player, ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return;
        }
        if (hasStashTag(player))
        {
            CompoundTag stash = persisted(player).getCompound(STASH_TAG);
            ListTag bonus = stash.getList("bonus", Tag.TAG_COMPOUND);
            bonus.add(stack.save(new CompoundTag()));
            stash.put("bonus", bonus);
            persisted(player).put(STASH_TAG, stash);
            return;
        }
        // legacy fallback: a stash written by the previous design still on disk. Appending here keeps a reward
        // working for an in-flight legacy raid, though those are drained out over the first restart after this ships.
        Entry entry = entries.get(player.getUUID());
        if (entry == null)
        {
            return;
        }
        ListTag bonus = entry.stash.getList("bonus", Tag.TAG_COMPOUND);
        bonus.add(stack.save(new CompoundTag()));
        entry.stash.put("bonus", bonus);
        setDirty();
    }

    /**
     * Take this player's items for a raid: record where they are standing NOW as the return spot, then save and clear
     * their main inventory, offhand and equipped Curios (worn armor is left on, exactly like the tournament stash) onto
     * their OWN persisted NBT. No-op if they already have a stash, so a double-entry can never overwrite a real stash
     * with an empty one. Because the stash is written to the player's own data it is saved with them and travels with
     * them between servers, so it can never be stranded on the server that took it.
     */
    public void store(ServerPlayer player)
    {
        if (hasStashTag(player) || entries.containsKey(player.getUUID()))
        {
            return; // already stashed; never overwrite a real stash
        }

        CompoundTag stash = new CompoundTag();
        stash.putString("dim", player.level().dimension().location().toString());
        stash.putDouble("x", player.getX());
        stash.putDouble("y", player.getY());
        stash.putDouble("z", player.getZ());
        stash.putFloat("yaw", player.getYRot());
        stash.putFloat("pitch", player.getXRot());

        Inventory inv = player.getInventory();
        stash.put("main", takeAll(inv.items));
        stash.put("offhand", takeAll(inv.offhand));

        IItemHandlerModifiable curios = CuriosAccess.getEquipped(player);
        if (curios != null)
        {
            ListTag curioList = new ListTag();
            for (int i = 0; i < curios.getSlots(); i++)
            {
                ItemStack itemStack = curios.getStackInSlot(i);
                if (!itemStack.isEmpty())
                {
                    curioList.add(slotTag(i, itemStack));
                    curios.setStackInSlot(i, ItemStack.EMPTY);
                }
            }
            stash.put("curios", curioList);
        }

        persisted(player).put(STASH_TAG, stash);
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * Give this player their stashed items back and teleport them to their recorded origin, then drop the stash.
     * No-op if they have no stash. Anything the player is carrying at restore time (raid kit, loot) is left in place
     * and the stashed items are written back into their original slots on top of it; where a stashed slot would
     * collide, we hand the current occupant back as an overflow so nothing is ever destroyed. The stash tag is removed
     * only after a successful write, so an unexpected failure leaves it intact for a later retry.
     *
     * <p>Drains the live NBT stash if present, otherwise a legacy SavedData entry (from the previous design) if one is
     * still on disk for this player. Never both: a player holds at most one stash, so there is no path that hands the
     * same items over twice.
     */
    public void restore(ServerPlayer player)
    {
        if (hasStashTag(player))
        {
            CompoundTag stash = persisted(player).getCompound(STASH_TAG);
            restoreFrom(player, stash, new ResourceLocation(stash.getString("dim")),
                    stash.getDouble("x"), stash.getDouble("y"), stash.getDouble("z"),
                    stash.getFloat("yaw"), stash.getFloat("pitch"));
            persisted(player).remove(STASH_TAG);
            player.inventoryMenu.broadcastChanges();
            return;
        }

        Entry entry = entries.get(player.getUUID());
        if (entry == null)
        {
            return;
        }
        restoreFrom(player, entry.stash, entry.dim, entry.x, entry.y, entry.z, entry.yaw, entry.pitch);
        entries.remove(player.getUUID());
        player.inventoryMenu.broadcastChanges();
        setDirty();
    }

    // hand a stash (from either store) back to the player: teleport home first, then write the items into their slots.
    private void restoreFrom(ServerPlayer player, CompoundTag stash, ResourceLocation dim,
                             double x, double y, double z, float yaw, float pitch)
    {
        // return them first so a restore always lands them out of the raid dimension, even if the item write below
        // has to overflow-drop. A missing dimension leaves them where they are, which is the safe outcome on a
        // backend whose world does not contain the recorded origin.
        MinecraftServer server = player.getServer();
        ServerLevel dest = server == null ? null
                : server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (dest != null)
        {
            player.teleportTo(dest, x, y, z, yaw, pitch);
        }
        else
        {
            LoggingHandler.sulog.warn("[GuildRaid] Return dimension {} missing for {}; restoring items in place.",
                    dim, player.getGameProfile().getName());
        }

        Inventory inv = player.getInventory();
        putAll(stash.getList("main", Tag.TAG_COMPOUND), inv.items, player);
        putAll(stash.getList("offhand", Tag.TAG_COMPOUND), inv.offhand, player);

        IItemHandlerModifiable curios = CuriosAccess.getEquipped(player);
        if (curios != null && stash.contains("curios"))
        {
            for (Tag t : stash.getList("curios", Tag.TAG_COMPOUND))
            {
                CompoundTag tag = (CompoundTag) t;
                int slot = tag.getInt("Slot");
                ItemStack itemStack = ItemStack.of(tag);
                if (slot >= 0 && slot < curios.getSlots() && curios.getStackInSlot(slot).isEmpty())
                {
                    curios.setStackInSlot(slot, itemStack);
                }
                else
                {
                    // slot gone or occupied: never destroy the stashed curio, hand it back through the inventory
                    // (which itself overflows to the ground if full).
                    giveOrDrop(player, itemStack);
                }
            }
        }

        // hand over any raid-win reward stacks last, once the player is already back at their HOME location and their
        // real items are restored. giveOrDrop adds to the inventory and drops only the overflow, here in the safe home
        // dimension, never in the raid instance, so a full inventory can never destroy the reward.
        if (stash.contains("bonus"))
        {
            for (Tag t : stash.getList("bonus", Tag.TAG_COMPOUND))
            {
                giveOrDrop(player, ItemStack.of((CompoundTag) t));
            }
        }
    }

    private static ListTag takeAll(NonNullList<ItemStack> slots)
    {
        ListTag list = new ListTag();
        for (int i = 0; i < slots.size(); i++)
        {
            ItemStack stack = slots.get(i);
            if (!stack.isEmpty())
            {
                list.add(slotTag(i, stack));
                slots.set(i, ItemStack.EMPTY);
            }
        }
        return list;
    }

    // write each stashed slot back into its original index. If that index is occupied (the raid kit or loot sits
    // there), the current occupant is handed back through giveOrDrop so it is never overwritten and never lost.
    private static void putAll(ListTag list, NonNullList<ItemStack> slots, ServerPlayer player)
    {
        for (Tag t : list)
        {
            CompoundTag tag = (CompoundTag) t;
            int slot = tag.getInt("Slot");
            ItemStack stashed = ItemStack.of(tag);
            if (slot >= 0 && slot < slots.size())
            {
                ItemStack occupant = slots.get(slot);
                slots.set(slot, stashed);
                if (!occupant.isEmpty())
                {
                    giveOrDrop(player, occupant);
                }
            }
            else
            {
                giveOrDrop(player, stashed);
            }
        }
    }

    // add to the player's inventory, dropping to the ground whatever does not fit, so an item can never be lost.
    private static void giveOrDrop(ServerPlayer player, ItemStack stack)
    {
        if (stack.isEmpty())
        {
            return;
        }
        player.getInventory().add(stack); // mutates stack down to whatever did not fit
        if (!stack.isEmpty())
        {
            player.drop(stack, false);
        }
    }

    private static CompoundTag slotTag(int slot, ItemStack stack)
    {
        CompoundTag tag = stack.save(new CompoundTag());
        tag.putInt("Slot", slot);
        return tag;
    }
}
