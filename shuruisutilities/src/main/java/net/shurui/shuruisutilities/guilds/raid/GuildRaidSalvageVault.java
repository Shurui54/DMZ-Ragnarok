package net.shurui.shuruisutilities.guilds.raid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Overworld-attached saved data holding the RAID LOSS RECOVERY salvage: for each guild, the item stacks recovered when
 * one of its planets was DESTROYED. A destroyed planet evicts its owners and is unclaimed, so its owning guild would
 * otherwise lose everything they built and stored on it; this store hands a configurable share of that back, to be
 * withdrawn from the guild GUI by a sufficiently ranked member.
 *
 * <p>DO NOT CONFUSE THIS WITH {@link GuildRaidVault}. That sibling is the per-RAIDER item stash: a single raider's own
 * inventory, taken off them for the duration of a raid and returned when it ends, keyed by player UUID. THIS store is a
 * per-GUILD salvage pile keyed by guild id, filled only on planet destruction and never on raid entry. The two never
 * interact; they merely share the SavedData idiom.
 *
 * <p>Mirrors the SavedData idiom used across the suite ({@link net.shurui.shuruisutilities.space.GeneratedPlanetClaims},
 * {@link GuildRaidLockouts}, {@link GuildRaidSpoils}): persisted with the overworld data storage under a fixed name so
 * it survives restarts.
 *
 * <h2>Add, do not replace</h2>
 * A guild can lose more than one planet over its lifetime (it can re-claim after an unclaim, or lose a second world
 * later), and each loss is a separate misfortune the guild should be compensated for. So a second destruction ADDS to
 * whatever salvage is already waiting, rather than overwriting it. Incoming stacks are merged into existing stacks of
 * the same item where possible, so the pile stays compact instead of holding many partial stacks of one block.
 *
 * <h2>Capacity</h2>
 * An unbounded item store is a real save-file and memory risk (a destroyed planet's terrain alone can be hundreds of
 * thousands of blocks). The pile is therefore hard-capped at {@link #MAX_STACKS_PER_GUILD} stacks per guild. When a
 * deposit would exceed the cap the overflow is dropped and the caller is told it truncated, so the surrounding destroy
 * can log it once. Because the capture deposits container CONTENTS before bulk BLOCKS (see PlanetSalvage), the valuable
 * loot is stored first and only cheap bulk terrain is ever dropped at the cap.
 */
public final class GuildRaidSalvageVault extends SavedData
{
    private static final String NAME = "shuruisutilities_guild_raid_salvage";

    // Hard ceiling on stored stacks per guild. 1080 == twenty double-chests, generous for a guild base's worth of
    // salvage while keeping the on-disk list and in-memory footprint bounded no matter how large the destroyed
    // planet's terrain was. A deposit that would push a guild past this is truncated (overflow dropped, logged once).
    public static final int MAX_STACKS_PER_GUILD = 1080;

    // guild id -> the recovered item stacks waiting to be withdrawn. Never holds empty stacks.
    private final Map<String, List<ItemStack>> salvage = new HashMap<>();

    public static GuildRaidSalvageVault get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(
                GuildRaidSalvageVault::load, GuildRaidSalvageVault::new, NAME);
    }

    private static GuildRaidSalvageVault load(CompoundTag tag)
    {
        GuildRaidSalvageVault s = new GuildRaidSalvageVault();
        ListTag guilds = tag.getList("guilds", Tag.TAG_COMPOUND);
        for (int i = 0; i < guilds.size(); i++)
        {
            CompoundTag g = guilds.getCompound(i);
            String guildId = g.getString("guild");
            List<ItemStack> stacks = new ArrayList<>();
            ListTag items = g.getList("items", Tag.TAG_COMPOUND);
            for (int j = 0; j < items.size(); j++)
            {
                ItemStack stack = ItemStack.of(items.getCompound(j));
                if (!stack.isEmpty())
                {
                    stacks.add(stack);
                }
            }
            if (!stacks.isEmpty())
            {
                s.salvage.put(guildId, stacks);
            }
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag guilds = new ListTag();
        for (Map.Entry<String, List<ItemStack>> e : salvage.entrySet())
        {
            if (e.getValue().isEmpty())
            {
                continue;
            }
            CompoundTag g = new CompoundTag();
            g.putString("guild", e.getKey());
            ListTag items = new ListTag();
            for (ItemStack stack : e.getValue())
            {
                items.add(stack.save(new CompoundTag()));
            }
            g.put("items", items);
            guilds.add(g);
        }
        tag.put("guilds", guilds);
        return tag;
    }

    /**
     * Add recovered stacks to a guild's salvage pile, merging into existing stacks of the same item first so the pile
     * stays compact, then spilling into fresh stacks up to {@link #MAX_STACKS_PER_GUILD}. Returns true if EVERYTHING
     * was stored, false if the cap was hit and some overflow had to be dropped (the caller logs that once). A null or
     * empty input, or a null guild id, stores nothing and returns true (nothing was lost).
     */
    public boolean deposit(String guildId, List<ItemStack> incoming)
    {
        if (guildId == null || incoming == null || incoming.isEmpty())
        {
            return true;
        }
        List<ItemStack> pile = salvage.computeIfAbsent(guildId, k -> new ArrayList<>());
        boolean allStored = true;
        for (ItemStack in : incoming)
        {
            if (in == null || in.isEmpty())
            {
                continue;
            }
            ItemStack remaining = in.copy();
            // first pass: top up existing stacks of the same item + tags, so identical items pack together.
            for (ItemStack existing : pile)
            {
                if (remaining.isEmpty())
                {
                    break;
                }
                if (ItemStack.isSameItemSameTags(existing, remaining))
                {
                    int room = existing.getMaxStackSize() - existing.getCount();
                    if (room > 0)
                    {
                        int moved = Math.min(room, remaining.getCount());
                        existing.grow(moved);
                        remaining.shrink(moved);
                    }
                }
            }
            // second pass: whatever is left becomes new stacks, until the per-guild cap is reached.
            while (!remaining.isEmpty())
            {
                if (pile.size() >= MAX_STACKS_PER_GUILD)
                {
                    // cap reached: the rest is dropped. The caller reports truncation once.
                    allStored = false;
                    break;
                }
                int n = Math.min(remaining.getMaxStackSize(), remaining.getCount());
                ItemStack fresh = remaining.copy();
                fresh.setCount(n);
                pile.add(fresh);
                remaining.shrink(n);
            }
        }
        setDirty();
        return allStored;
    }

    /** The number of stacks a guild currently has waiting. Zero if it has none. */
    public int size(String guildId)
    {
        List<ItemStack> pile = salvage.get(guildId);
        return pile == null ? 0 : pile.size();
    }

    /**
     * A read-only snapshot (copies) of a guild's waiting stacks, for the GUI to render. Copies so a caller can never
     * mutate the stored stacks through the view. Empty list if the guild has none.
     */
    public List<ItemStack> view(String guildId)
    {
        List<ItemStack> pile = salvage.get(guildId);
        if (pile == null || pile.isEmpty())
        {
            return List.of();
        }
        List<ItemStack> out = new ArrayList<>(pile.size());
        for (ItemStack stack : pile)
        {
            out.add(stack.copy());
        }
        return out;
    }

    /**
     * Remove and return the stack at {@code index} in a guild's pile, or {@link ItemStack#EMPTY} if the index is out
     * of range (a stale click after the pile changed). The caller hands the returned stack to the withdrawing player.
     */
    public ItemStack take(String guildId, int index)
    {
        List<ItemStack> pile = salvage.get(guildId);
        if (pile == null || index < 0 || index >= pile.size())
        {
            return ItemStack.EMPTY;
        }
        ItemStack removed = pile.remove(index);
        if (pile.isEmpty())
        {
            salvage.remove(guildId);
        }
        setDirty();
        return removed;
    }

    /**
     * Remove and return EVERY stack a guild has waiting, clearing its pile. The caller hands them to the withdrawing
     * player (overflowing to the ground if their inventory is full). Empty list if the guild had none.
     */
    public List<ItemStack> takeAll(String guildId)
    {
        List<ItemStack> pile = salvage.remove(guildId);
        if (pile == null || pile.isEmpty())
        {
            return List.of();
        }
        setDirty();
        return pile;
    }

    /** Drop a guild's entire salvage pile (used when a guild disbands, so a dead guild id never lingers). */
    public void forgetGuild(String guildId)
    {
        if (salvage.remove(guildId) != null)
        {
            setDirty();
        }
    }

    /** Log a one-line info note that a guild's salvage was truncated at the cap, for operator visibility. */
    public static void logTruncated(String guildId)
    {
        LoggingHandler.sulog.info(
                "[GuildRaid] Salvage for guild {} hit the {}-stack cap; the overflow was dropped.",
                guildId, MAX_STACKS_PER_GUILD);
    }
}
