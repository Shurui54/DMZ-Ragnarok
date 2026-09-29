package net.shurui.shuruisutilities.god;

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
 * Persists the eight hour hakai cooldowns.
 *
 * <p>An eight hour REAL-TIME cooldown that only lived in memory would be cleared by every restart, which on a server
 * that restarts nightly would mean a daily ability rather than an eight-hourly one. Storing the absolute wall-clock
 * millisecond it expires means the cooldown survives restarts, and cannot be shortened by sleeping, changing
 * dimension or logging out either, since none of those move real time.
 */
public final class GodHakaiStorage extends SavedData
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

    private static final String NAME = "shuruisutilities_god_hakai";

    private GodHakaiStorage() {}

    public static GodHakaiStorage get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(GodHakaiStorage::load, GodHakaiStorage::new, NAME);
    }

    /** Load persisted cooldowns into {@link GodHakaiCooldowns} at server start. */
    public static GodHakaiStorage load(CompoundTag tag)
    {
        GodHakaiStorage data = new GodHakaiStorage();
        for (Tag t : tag.getList("cooldowns", Tag.TAG_COMPOUND))
        {
            CompoundTag e = (CompoundTag) t;
            GodHakaiCooldowns.restore(e.getUUID("player"), e.getLong("readyAt"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag rows = new ListTag();
        for (Map.Entry<UUID, Long> e : GodHakaiCooldowns.rows().entrySet())
        {
            CompoundTag row = new CompoundTag();
            row.putUUID("player", e.getKey());
            row.putLong("readyAt", e.getValue());
            rows.add(row);
        }
        tag.put("cooldowns", rows);
        return tag;
    }

    /** Mark dirty and let the world save carry the current cooldown set out. */
    public static void save(MinecraftServer server)
    {
        if (server == null)
            return;
        get(server).setDirty();
    }

    /**
     * Cross-server state sync write path: fold a sibling server's cooldowns in, keeping the LATER expiry per player
     * rather than replacing the whole table. A cooldown is a gate a god must WAIT OUT, so the merge must never shorten
     * one: a player who fired hakai on one server and hopped carries the wait with them, or the hop is a free reset.
     * Taking the later expiry can only ever hold a cooldown open, never end it early, and a player is on one server at
     * a time so the live stamp is always the one this promotes. A stale past expiry that arrives is harmless: the next
     * cast prunes it by comparing against real time.
     */
    public void mergeInto(CompoundTag tag)
    {
        boolean changed = false;
        for (Tag t : tag.getList("cooldowns", Tag.TAG_COMPOUND))
        {
            CompoundTag e = (CompoundTag) t;
            if (GodHakaiCooldowns.mergeCooldown(e.getUUID("player"), e.getLong("readyAt")))
                changed = true;
        }
        if (changed)
            setDirty();
    }
}
