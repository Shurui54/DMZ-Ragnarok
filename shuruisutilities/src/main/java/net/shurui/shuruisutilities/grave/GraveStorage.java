package net.shurui.shuruisutilities.grave;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension SavedData of every active grave in a level, keyed by the fence's packed BlockPos long. One
 * instance per level's data storage, so graves persist across restarts. Mirrors SU's PrestigeSettings pattern.
 */
public final class GraveStorage extends SavedData
{
    private static final String NAME = "shuruisutilities_graves";

    private final Map<Long, GraveData> graves = new HashMap<>();

    public static GraveStorage get(ServerLevel level)
    {
        return level.getDataStorage().computeIfAbsent(GraveStorage::load, GraveStorage::new, NAME);
    }

    private static GraveStorage load(CompoundTag tag)
    {
        GraveStorage storage = new GraveStorage();
        ListTag list = tag.getList("graves", 10); // 10 = CompoundTag
        for (int i = 0; i < list.size(); i++)
        {
            GraveData data = GraveData.load(list.getCompound(i));
            storage.graves.put(data.pos().asLong(), data);
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag list = new ListTag();
        for (GraveData data : graves.values())
            list.add(data.save());
        tag.put("graves", list);
        return tag;
    }

    public GraveData get(BlockPos pos)
    {
        return graves.get(pos.asLong());
    }

    public boolean has(BlockPos pos)
    {
        return graves.containsKey(pos.asLong());
    }

    // UUIDs of every live grave's name-marker ArmorStand in this level. Legacy graves with null markerId are
    // skipped, so this holds only positively-known live markers. The orphan sweep uses it to decide which tagged
    // ArmorStands are still owned by a grave and must NOT be discarded.
    public Set<UUID> liveMarkerIds()
    {
        Set<UUID> ids = new HashSet<>();
        for (GraveData data : graves.values())
        {
            UUID id = data.markerId();
            if (id != null)
                ids.add(id);
        }
        return ids;
    }

    /** Every live grave in this level. Read-only view; use put/remove to change the set. */
    public Collection<GraveData> all()
    {
        return java.util.Collections.unmodifiableCollection(graves.values());
    }

    public void put(GraveData data)
    {
        graves.put(data.pos().asLong(), data);
        setDirty();
    }

    public void remove(BlockPos pos)
    {
        if (graves.remove(pos.asLong()) != null)
            setDirty();
    }

    // Positions of graves aged out (created >= maxAgeTicks before nowGameTime). The map is NOT mutated here; the
    // caller removes the returned positions via removeGrave, so no concurrent-modification hazard.
    // Side effect (legacy migration): a grave with an un-stamped creation time gets stamped to nowGameTime for a
    // fresh despawn window instead of being treated as infinitely old, so it's never reported expired on the
    // sweep that first stamps it.
    public List<BlockPos> positionsOlderThan(long nowGameTime, long maxAgeTicks)
    {
        List<BlockPos> expired = new ArrayList<>();
        boolean stamped = false;
        for (GraveData data : graves.values())
        {
            if (data.stampCreatedGameTimeIfUnset(nowGameTime))
            {
                stamped = true;
                continue; // just stamped -> fresh window, cannot be expired this sweep
            }
            if (nowGameTime - data.createdGameTime() >= maxAgeTicks)
                expired.add(data.pos());
        }
        if (stamped)
            setDirty();
        return expired;
    }
}
