package net.shurui.shuruisutilities.hoverbike;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Vehicles that were recalled while their chunk was unloaded, waiting to be removed the moment it loads again.
 *
 * <p>{@link VehicleRecallWatch} cannot discard an entity the server does not have in memory, and force loading the
 * chunk to reach it is a blocking load on the tick thread (the barrier-box watchdog hang of 2026-09-12). So the owner's
 * record is cleared at once, which frees their slot to deploy again, and the vehicle's id waits here until
 * {@code EntityJoinLevelEvent} brings it back in, at which point it is removed before anyone can ride a duplicate.
 * Persisted, so a restart in between does not turn the leftover into a free second vehicle.
 *
 * <p>Entries expire after {@link #EXPIRY_DAYS}: a vehicle in a chunk nobody visits for that long is not worth an entry
 * in a file the server gzips on its own thread for ever. The SavedData id {@code shuruisutilities_vehicle_recalls}
 * keys the file in the world save and must not change.
 */
public final class VehicleRecallStore extends SavedData
{
    private static final String NAME = "shuruisutilities_vehicle_recalls";
    private static final long EXPIRY_DAYS = 30;

    /** vehicle entity id -> when it was queued (epoch millis) */
    private final Map<UUID, Long> pending = new HashMap<>();

    /**
     * Whether anything at all is queued, readable without touching the world's data storage. Entity joins run at
     * thousands a second on a busy shard, so the join hook reads this first and does nothing else when it is false.
     * Set by {@link #load} (the store is loaded at server start, see {@link VehicleRecallWatch}) and kept current by
     * every change.
     */
    static volatile boolean anyPending;

    /** The store, or null before the overworld exists. */
    public static VehicleRecallStore get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null)
            return null;
        return overworld.getDataStorage().computeIfAbsent(VehicleRecallStore::load, VehicleRecallStore::new, NAME);
    }

    public void add(UUID vehicle)
    {
        pending.put(vehicle, System.currentTimeMillis());
        anyPending = true;
        setDirty();
    }

    /** True, and forgotten, when this vehicle was waiting to be removed. */
    public boolean take(UUID vehicle)
    {
        if (pending.remove(vehicle) == null)
            return false;
        anyPending = !pending.isEmpty();
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(EXPIRY_DAYS);
        ListTag list = new ListTag();
        for (Iterator<Map.Entry<UUID, Long>> it = pending.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() < cutoff)
            {
                it.remove();
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", e.getKey());
            entry.putLong("at", e.getValue());
            list.add(entry);
        }
        tag.put("vehicles", list);
        return tag;
    }

    public static VehicleRecallStore load(CompoundTag tag)
    {
        VehicleRecallStore store = new VehicleRecallStore();
        ListTag list = tag.getList("vehicles", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("id"))
                store.pending.put(entry.getUUID("id"), entry.getLong("at"));
        }
        anyPending = !store.pending.isEmpty();
        return store;
    }
}
