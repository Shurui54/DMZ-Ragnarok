package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-player wall-clock cooldown for Korin's weekly senzu handout.
 *
 * <p>Keyed by player UUID (NOT character slot: the handout is a real-life weekly gift to the person, and DMZ character
 * slots share one inventory, so slot-scoping would let a player reroll a slot to reset the gate). The value is the
 * absolute {@link System#currentTimeMillis()} millisecond the cooldown ends.
 *
 * <h2>Why wall clock, not game time</h2>
 *
 * <p>The owner asked for one REAL life week. {@code level.getGameTime()} advances only while the server runs and freezes
 * while it is down, so a game-time week would stretch across every restart and overnight stop. Storing an absolute
 * wall-clock expiry means the week is one real week regardless of uptime, and cannot be shortened by relogging, changing
 * dimension or rerolling a slot, none of which move real time. This mirrors {@link GodHakaiStorage}, which stamps a
 * real-time cooldown the same way and for the same reason.
 *
 * <h2>Deterministic save</h2>
 *
 * <p>{@link #save} sorts its rows by UUID string before writing, so the serialized bytes are identical for an unchanged
 * table on every save. A nondeterministic order would make the shard state-sync layer think the table changed on every
 * save and republish it forever, the loop that has bitten this repo before.
 *
 * <h2>Network-wide</h2>
 *
 * <p>Registered with the shard state sync as {@code su:korin_senzu} (see {@code ShuruisUtilities}), with {@link
 * #mergeInto} keeping the LATER expiry per player. That makes the gate network-wide: a player who claims on one shard
 * carries the wait to every other shard, so the reward cannot be farmed by hopping servers.
 */
public final class KorinCooldowns extends SavedData
{
    // Cross-shard state sync change signal: a monotonic counter bumped on every mutation. NEVER reset (unlike
    // SavedData's own dirty flag, which the autosave clears), so ShardStateSync can skip rebuilding this store's NBT
    // while it has not moved and can never miss a change. See ShardStateSync.register.
    private long shardDirtyVersion;

    @Override
    public void setDirty()
    {
        shardDirtyVersion++;
        super.setDirty();
    }

    /** Monotonic mutation counter for the cross-shard state sync; see the field note. */
    public long shardDirtyVersion()
    {
        return shardDirtyVersion;
    }

    // Pinned literal: the on-disk data/<NAME>.dat filename. Deriving it from MODID would silently orphan saved
    // cooldowns if MODID is renamed.
    public static final String NAME = "shuruisutilities_korin_senzu";

    private final Map<UUID, Long> expiry = new HashMap<>();

    private KorinCooldowns() {}

    public static KorinCooldowns get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(KorinCooldowns::load, KorinCooldowns::new, NAME);
    }

    public static KorinCooldowns load(CompoundTag tag)
    {
        KorinCooldowns c = new KorinCooldowns();
        for (Tag t : tag.getList("cooldowns", Tag.TAG_COMPOUND))
        {
            CompoundTag e = (CompoundTag) t;
            c.expiry.put(e.getUUID("player"), e.getLong("readyAt"));
        }
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        // Sort by UUID string so the byte output is stable for an unchanged table (see the class note on the sync loop).
        List<UUID> keys = new ArrayList<>(expiry.keySet());
        keys.sort((a, b) -> a.toString().compareTo(b.toString()));
        ListTag rows = new ListTag();
        for (UUID id : keys)
        {
            CompoundTag row = new CompoundTag();
            row.putUUID("player", id);
            row.putLong("readyAt", expiry.get(id));
            rows.add(row);
        }
        tag.put("cooldowns", rows);
        return tag;
    }

    /** True while the player still has an active cooldown at the given wall-clock instant; prunes an expired row. */
    public boolean onCooldown(UUID id, long now)
    {
        Long end = expiry.get(id);
        if (end == null)
            return false;
        if (now >= end)
        {
            expiry.remove(id);
            setDirty();
            return false;
        }
        return true;
    }

    /** Remaining cooldown in whole milliseconds, or 0 if none. */
    public long remainingMillis(UUID id, long now)
    {
        Long end = expiry.get(id);
        if (end == null || now >= end)
            return 0L;
        return end - now;
    }

    /** Stamp an absolute wall-clock expiry for a player. */
    public void stamp(UUID id, long expiryMillis)
    {
        expiry.put(id, expiryMillis);
        setDirty();
    }

    /**
     * Cross-server state sync write path: fold a sibling shard's cooldowns in, keeping the LATER expiry per player
     * rather than replacing the whole table. A cooldown is a gate the player must WAIT OUT, so a merge must never
     * shorten one, or a hop would be a free reset. Taking the later expiry can only hold a cooldown open, never end it
     * early, and a player is on one shard at a time so the live stamp always wins. A stale past expiry is harmless:
     * onCooldown prunes it.
     */
    public void mergeInto(CompoundTag tag)
    {
        boolean changed = false;
        for (Tag t : tag.getList("cooldowns", Tag.TAG_COMPOUND))
        {
            CompoundTag e = (CompoundTag) t;
            UUID id = e.getUUID("player");
            long incoming = e.getLong("readyAt");
            Long current = expiry.get(id);
            if (current == null || incoming > current)
            {
                expiry.put(id, incoming);
                changed = true;
            }
        }
        if (changed)
            setDirty();
    }
}
